/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2022 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include "xenia/kernel/xobject.h"

#include <atomic>
#include <fstream>

#include "xenia/base/byte_stream.h"
#include "xenia/base/filesystem.h"
#include "xenia/base/memory.h"
#include "xenia/emulator.h"
#include "xenia/kernel/kernel_state.h"
#include "xenia/kernel/util/shim_utils.h"
#include "xenia/kernel/xboxkrnl/xboxkrnl_private.h"
#include "xenia/kernel/xenumerator.h"
#include "xenia/kernel/xevent.h"
#include "xenia/kernel/xfile.h"
#include "xenia/kernel/xmodule.h"
#include "xenia/kernel/xmutant.h"
#include "xenia/kernel/xnotifylistener.h"
#include "xenia/kernel/xsemaphore.h"
#include "xenia/kernel/xsymboliclink.h"
#include "xenia/kernel/xthread.h"
#include "xenia/xbox.h"

namespace xe {
namespace kernel {

XObject::XObject(Type type)
    : kernel_state_(nullptr), pointer_ref_count_(1), type_(type) {
  handles_.reserve(10);
}

XObject::XObject(KernelState* kernel_state, Type type, bool host_object)
    : kernel_state_(kernel_state),
      type_(type),
      pointer_ref_count_(1),
      guest_object_ptr_(0),
      allocated_guest_object_(false),
      host_object_(host_object) {
  handles_.reserve(10);

  // TODO: Assert kernel_state != nullptr in this constructor.
  if (kernel_state) {
    kernel_state->object_table()->AddHandle(this, nullptr);
  }
}

XObject::~XObject() {
  assert_true(handles_.empty());
  assert_zero(pointer_ref_count_);

  if (allocated_guest_object_) {
    uint32_t ptr = guest_object_ptr_ - sizeof(X_OBJECT_HEADER);
    auto header = memory()->TranslateVirtual<X_OBJECT_HEADER*>(ptr);

    // Free the object creation info
    if (header->object_type_ptr) {
      memory()->SystemHeapFree(header->object_type_ptr);
    }

    memory()->SystemHeapFree(ptr);
  }
}

Emulator* XObject::emulator() const { return kernel_state_->emulator_; }
KernelState* XObject::kernel_state() const { return kernel_state_; }
Memory* XObject::memory() const { return kernel_state_->memory(); }

XObject::Type XObject::type() const { return type_; }

void XObject::RetainHandle() {
  kernel_state_->object_table()->RetainHandle(handles_[0]);
}

bool XObject::ReleaseHandle() {
  // FIXME: Return true when handle is actually released.
  return kernel_state_->object_table()->ReleaseHandle(handles_[0]) ==
         X_STATUS_SUCCESS;
}

void XObject::Retain() { ++pointer_ref_count_; }

void XObject::Release() {
  if (--pointer_ref_count_ == 0) {
    delete this;
  }
}

X_STATUS XObject::Delete() {
  if (kernel_state_ == nullptr) {
    // Fake return value for api-scanner
    return X_STATUS_SUCCESS;
  } else {
    if (!name_.empty()) {
      kernel_state_->object_table()->RemoveNameMapping(name_);
    }
    return kernel_state_->object_table()->RemoveHandle(handles_[0]);
  }
}

bool XObject::SaveObject(ByteStream* stream) {
  stream->Write<uint32_t>(allocated_guest_object_);
  stream->Write<uint32_t>(guest_object_ptr_);

  stream->Write(uint32_t(handles_.size()));
  stream->Write(&handles_[0], handles_.size() * sizeof(X_HANDLE));

  return true;
}

bool XObject::RestoreObject(ByteStream* stream) {
  allocated_guest_object_ = stream->Read<uint32_t>() > 0;
  guest_object_ptr_ = stream->Read<uint32_t>();

  handles_.resize(stream->Read<uint32_t>());
  stream->Read(&handles_[0], handles_.size() * sizeof(X_HANDLE));

  // Restore our pointer to our handles in the object table.
  for (size_t i = 0; i < handles_.size(); i++) {
    kernel_state_->object_table()->RestoreHandle(handles_[i], this);
  }

  return true;
}

object_ref<XObject> XObject::Restore(KernelState* kernel_state, Type type,
                                     ByteStream* stream) {
  switch (type) {
    case Type::Enumerator:
      break;
    case Type::Event:
      return XEvent::Restore(kernel_state, stream);
    case Type::File:
      return XFile::Restore(kernel_state, stream);
    case Type::IOCompletion:
      break;
    case Type::Module:
      return XModule::Restore(kernel_state, stream);
    case Type::Mutant:
      return XMutant::Restore(kernel_state, stream);
    case Type::NotifyListener:
      return XNotifyListener::Restore(kernel_state, stream);
    case Type::Semaphore:
      return XSemaphore::Restore(kernel_state, stream);
    case Type::Session:
      break;
    case Type::Socket:
      break;
    case Type::SymbolicLink:
      return XSymbolicLink::Restore(kernel_state, stream);
    case Type::Thread:
      return XThread::Restore(kernel_state, stream);
    case Type::Timer:
      break;
    case Type::Undefined:
      break;
  }

  assert_always("No restore handler exists for this object!");
  return nullptr;
}

void XObject::SetAttributes(uint32_t obj_attributes_ptr) {
  if (!obj_attributes_ptr) {
    return;
  }

  auto name = util::TranslateAnsiStringAddress(
      memory(), xe::load_and_swap<uint32_t>(
                    memory()->TranslateVirtual(obj_attributes_ptr + 4)));
  if (!name.empty()) {
    name_ = std::string(name);
    kernel_state_->object_table()->AddNameMapping(name_, handles_[0]);
  }
}

uint32_t XObject::TimeoutTicksToMs(int64_t timeout_ticks) {
  if (timeout_ticks > 0) {
    // NetDll_WSAWaitForMultipleEvents provides timeout in form of MS.
    return (uint32_t)timeout_ticks;
  } else if (timeout_ticks < 0) {
    // Relative time.
    return (uint32_t)(-timeout_ticks / 10000);  // Ticks -> MS
  } else {
    return 0;
  }
}

namespace {

// Renders the guest PPC back-chain from r1 as a list of saved return
// addresses, so a long-wait report names the blocked thread's full guest call
// path. Frame layout validated live on retail titles: [sp] = caller sp (big
// endian), the frame's saved LR sits at caller_sp - 8.
std::string GuestBacktrace(Memory* memory, uint32_t r1) {
  auto read_u32 = [memory](uint32_t addr, uint32_t* out) {
    if (addr < 0x1000 || (addr & 3)) {
      return false;
    }
    auto* heap = memory->LookupHeap(addr);
    uint32_t protect = 0;
    if (!heap || !heap->QueryProtect(addr, &protect) ||
        !(protect & kMemoryProtectRead)) {
      return false;
    }
    *out = xe::load_and_swap<uint32_t>(memory->TranslateVirtual(addr));
    return true;
  };
  std::string trace;
  uint32_t sp = r1;
  for (int i = 0; i < 12; ++i) {
    uint32_t caller_sp = 0;
    if (!read_u32(sp, &caller_sp) || caller_sp <= sp ||
        caller_sp - sp > 0x10000) {
      break;
    }
    uint32_t lr = 0;
    if (read_u32(caller_sp - 8, &lr) && lr >= 0x80000000 && lr < 0xA0000000 &&
        !(lr & 3)) {
      trace += fmt::format(" {:08X}", lr);
    }
    sp = caller_sp;
  }
  return trace.empty() ? std::string(" <unwalkable>") : trace;
}

// One-shot forensic dump for title deadlocks: when an infinite wait crosses
// a minute, write the guest regions needed for offline reversing (object
// heap, device heap, stacks, title code+data) to storage. Unreadable pages
// are written as zeros so file offset == guest offset within each range.
void DumpGuestForensics(KernelState* kernel_state) {
  static std::atomic<bool> dumped{false};
  bool expected = false;
  if (!dumped.compare_exchange_strong(expected, true)) {
    return;
  }
  struct Range {
    uint32_t base;
    uint32_t size;
  };
  static constexpr Range kRanges[] = {
      {0x30000000, 0x00400000},  // guest object heap (KTHREADs, dispatch hdrs)
      {0x40000000, 0x00200000},  // 64K-page virtual heap (engine devices)
      {0x70000000, 0x00400000},  // thread stacks
      {0x82000000, 0x02000000},  // title code + data
  };
  auto path = kernel_state->emulator()->storage_root() / "deadlock_dump.bin";
  std::ofstream f(path, std::ios::binary | std::ios::trunc);
  if (!f.is_open()) {
    XELOGE("DumpGuestForensics: cannot open {}", xe::path_to_utf8(path));
    return;
  }
  auto* memory = kernel_state->memory();
  char zeros[4096] = {};
  for (const auto& range : kRanges) {
    for (uint32_t page = range.base; page < range.base + range.size;
         page += 4096) {
      auto* heap = memory->LookupHeap(page);
      bool readable = false;
      if (heap) {
        auto access = heap->QueryRangeAccess(page, page + 4095);
        readable = access != xe::memory::PageAccess::kNoAccess;
      }
      if (readable) {
        f.write(memory->TranslateVirtual<const char*>(page), 4096);
      } else {
        f.write(zeros, 4096);
      }
    }
  }
  XELOGW(
      "DumpGuestForensics: wrote {} (ranges 30000000+400000 40000000+200000 "
      "70000000+400000 82000000+2000000)",
      xe::path_to_utf8(path));
}

}  // namespace

X_STATUS XObject::Wait(uint32_t wait_reason, uint32_t processor_mode,
                       uint32_t alertable, uint64_t* opt_timeout) {
  auto wait_handle = GetWaitHandle();
  if (!wait_handle) {
    // Object doesn't support waiting.
    return X_STATUS_SUCCESS;
  }

  auto timeout_ms =
      opt_timeout ? std::chrono::milliseconds(Clock::ScaleGuestDurationMillis(
                        TimeoutTicksToMs(*opt_timeout)))
                  : std::chrono::milliseconds::max();

  xe::threading::WaitResult result;
  if (timeout_ms == std::chrono::milliseconds::max()) {
    // Infinite wait: poll in 10s slices and report long waits, so a title
    // deadlock names the waiting thread and the awaited object in the log
    // instead of parking silently forever.
    uint32_t slices = 0;
    do {
      result = xe::threading::Wait(wait_handle, alertable ? true : false,
                                   std::chrono::milliseconds(10000));
      if (result == xe::threading::WaitResult::kTimeout) {
        ++slices;
        auto* current_thread = XThread::GetCurrentThread();
        uint64_t guest_lr = 0, guest_r1 = 0;
        if (current_thread && current_thread->thread_state()) {
          auto* context = current_thread->thread_state()->context();
          guest_lr = context->lr;
          guest_r1 = context->r[1];
        }
        uint32_t setter_thread = 0, setter_lr = 0, setter_ms = 0;
        uint32_t creator_thread = 0, creator_lr = 0;
        if (type() == Type::Event) {
          auto* event = static_cast<XEvent*>(this);
          setter_thread = event->last_set_thread();
          setter_lr = event->last_set_lr();
          setter_ms = event->last_set_uptime_ms();
          creator_thread = event->creator_thread();
          creator_lr = event->creator_lr();
        }
        XELOGW(
            "XObject::Wait: thread {:08X} waiting on type-{} object "
            "handle {:08X} '{}' for {}s (guest lr={:08X} r1={:08X}) "
            "[last set by {:08X} at lr={:08X}, t={}ms] "
            "[created by {:08X} at lr={:08X}] stack:{}",
            current_thread ? current_thread->handle() : 0,
            uint32_t(type()), handle(), name(), slices * 10,
            uint32_t(guest_lr), uint32_t(guest_r1), setter_thread, setter_lr,
            setter_ms, creator_thread, creator_lr,
            GuestBacktrace(memory(), uint32_t(guest_r1)));
        if (slices == 6) {
          DumpGuestForensics(kernel_state());
        }
      }
    } while (result == xe::threading::WaitResult::kTimeout);
  } else {
    result =
        xe::threading::Wait(wait_handle, alertable ? true : false, timeout_ms);
  }

  switch (result) {
    case xe::threading::WaitResult::kSuccess:
    case xe::threading::WaitResult::kUserCallback: {
      auto current_thread = XThread::GetCurrentThread();
      if (current_thread) {
        current_thread->BoostOnWake(priority_increment());
      }
      if (result == xe::threading::WaitResult::kSuccess) {
        WaitCallback();
        return X_STATUS_SUCCESS;
      }
      return X_STATUS_USER_APC;
    }
    case xe::threading::WaitResult::kTimeout:
      xe::threading::MaybeYield();
      return X_STATUS_TIMEOUT;
    default:
    case xe::threading::WaitResult::kAbandoned:
    case xe::threading::WaitResult::kFailed:
      return X_STATUS_ABANDONED_WAIT_0;
  }
}

X_STATUS XObject::SignalAndWait(XObject* signal_object, XObject* wait_object,
                                uint32_t wait_reason, uint32_t processor_mode,
                                uint32_t alertable, uint64_t* opt_timeout) {
  auto timeout_ms =
      opt_timeout ? std::chrono::milliseconds(Clock::ScaleGuestDurationMillis(
                        TimeoutTicksToMs(*opt_timeout)))
                  : std::chrono::milliseconds::max();

  if (signal_object->type() == Type::Event) {
    // SignalAndWait signals the host handle directly, bypassing XEvent::Set -
    // keep the setter diagnostics accurate for this path too.
    static_cast<XEvent*>(signal_object)->RecordSetter();
  }

  xe::threading::WaitResult result;
  if (timeout_ms == std::chrono::milliseconds::max()) {
    // Infinite: signal once, then wait in 10s slices with long-wait
    // reporting (see XObject::Wait). The signal must not be repeated, so
    // timed-out slices continue with a plain wait on the wait object.
    result = xe::threading::SignalAndWait(signal_object->GetWaitHandle(),
                                          wait_object->GetWaitHandle(),
                                          alertable ? true : false,
                                          std::chrono::milliseconds(10000));
    uint32_t slices = 0;
    while (result == xe::threading::WaitResult::kTimeout) {
      ++slices;
      auto* current_thread = XThread::GetCurrentThread();
      uint64_t guest_lr = 0, guest_r1 = 0;
      if (current_thread && current_thread->thread_state()) {
        guest_lr = current_thread->thread_state()->context()->lr;
        guest_r1 = current_thread->thread_state()->context()->r[1];
      }
      uint32_t setter_thread = 0, setter_lr = 0, setter_ms = 0;
      if (wait_object->type() == Type::Event) {
        auto* event = static_cast<XEvent*>(wait_object);
        setter_thread = event->last_set_thread();
        setter_lr = event->last_set_lr();
        setter_ms = event->last_set_uptime_ms();
      }
      XELOGW(
          "XObject::SignalAndWait: thread {:08X} signaled type-{} {:08X}, "
          "waiting on type-{} {:08X} for {}s (guest lr={:08X}) "
          "[wait obj last set by {:08X} at lr={:08X}, t={}ms] stack:{}",
          current_thread ? current_thread->handle() : 0,
          uint32_t(signal_object->type()), signal_object->handle(),
          uint32_t(wait_object->type()), wait_object->handle(), slices * 10,
          uint32_t(guest_lr), setter_thread, setter_lr, setter_ms,
          GuestBacktrace(wait_object->memory(), uint32_t(guest_r1)));
      result = xe::threading::Wait(wait_object->GetWaitHandle(),
                                   alertable ? true : false,
                                   std::chrono::milliseconds(10000));
    }
  } else {
    result = xe::threading::SignalAndWait(signal_object->GetWaitHandle(),
                                          wait_object->GetWaitHandle(),
                                          alertable ? true : false, timeout_ms);
  }

  switch (result) {
    case xe::threading::WaitResult::kSuccess:
    case xe::threading::WaitResult::kUserCallback: {
      auto current_thread = XThread::GetCurrentThread();
      if (current_thread) {
        current_thread->BoostOnWake(wait_object->priority_increment());
      }
      if (result == xe::threading::WaitResult::kSuccess) {
        wait_object->WaitCallback();
        return X_STATUS_SUCCESS;
      }
      return X_STATUS_USER_APC;
    }
    case xe::threading::WaitResult::kTimeout:
      xe::threading::MaybeYield();
      return X_STATUS_TIMEOUT;
    default:
    case xe::threading::WaitResult::kAbandoned:
    case xe::threading::WaitResult::kFailed:
      return X_STATUS_ABANDONED_WAIT_0;
  }
}

X_STATUS XObject::WaitMultiple(uint32_t count, XObject** objects,
                               uint32_t wait_type, uint32_t wait_reason,
                               uint32_t processor_mode, uint32_t alertable,
                               uint64_t* opt_timeout) {
  xe::threading::WaitHandle* wait_handles[64];

  for (size_t i = 0; i < count; ++i) {
    wait_handles[i] = objects[i]->GetWaitHandle();
    assert_not_null(wait_handles[i]);
  }

  auto timeout_ms =
      opt_timeout ? std::chrono::milliseconds(Clock::ScaleGuestDurationMillis(
                        TimeoutTicksToMs(*opt_timeout)))
                  : std::chrono::milliseconds::max();

  // Infinite multi-waits are sliced into 10s host waits purely for long-wait
  // reporting (a timed-out WaitAny/WaitAll consumes no objects, so re-issuing
  // is semantically transparent to the guest).
  const bool infinite = timeout_ms == std::chrono::milliseconds::max();
  uint32_t report_slices = 0;
  auto report_long_wait = [&]() {
    ++report_slices;
    auto* current_thread = XThread::GetCurrentThread();
    uint64_t guest_lr = 0, guest_r1 = 0;
    if (current_thread && current_thread->thread_state()) {
      guest_lr = current_thread->thread_state()->context()->lr;
      guest_r1 = current_thread->thread_state()->context()->r[1];
    }
    XELOGW(
        "XObject::WaitMultiple: thread {:08X} waiting ({}) on {} objects "
        "for {}s (guest lr={:08X}) first handles: {:08X} {:08X} {:08X} "
        "stack:{}",
        current_thread ? current_thread->handle() : 0,
        wait_type ? "any" : "all", count, report_slices * 10,
        uint32_t(guest_lr), count > 0 ? objects[0]->handle() : 0,
        count > 1 ? objects[1]->handle() : 0,
        count > 2 ? objects[2]->handle() : 0,
        count > 0 ? GuestBacktrace(objects[0]->memory(), uint32_t(guest_r1))
                  : std::string());
  };
  auto slice_timeout = [&]() {
    return infinite ? std::chrono::milliseconds(10000) : timeout_ms;
  };

  X_STATUS status;
  uint32_t boost_increment = 0;
  if (wait_type) {
    std::pair<xe::threading::WaitResult, size_t> result;
    do {
      result = xe::threading::WaitAny(wait_handles, count,
                                      alertable ? true : false,
                                      slice_timeout());
      if (infinite && result.first == xe::threading::WaitResult::kTimeout) {
        report_long_wait();
      }
    } while (infinite && result.first == xe::threading::WaitResult::kTimeout);
    switch (result.first) {
      case xe::threading::WaitResult::kSuccess:
        objects[result.second]->WaitCallback();
        boost_increment = objects[result.second]->priority_increment();
        status = X_STATUS(result.second);
        break;
      case xe::threading::WaitResult::kUserCallback:
        status = X_STATUS_USER_APC;
        break;
      case xe::threading::WaitResult::kTimeout:
        xe::threading::MaybeYield();
        status = X_STATUS_TIMEOUT;
        break;
      case xe::threading::WaitResult::kAbandoned:
        status = X_STATUS(X_STATUS_ABANDONED_WAIT_0 + result.second);
        break;
      default:
      case xe::threading::WaitResult::kFailed:
        status = X_STATUS_UNSUCCESSFUL;
        break;
    }
  } else {
    xe::threading::WaitResult result;
    do {
      result = xe::threading::WaitAll(wait_handles, count,
                                      alertable ? true : false,
                                      slice_timeout());
      if (infinite && result == xe::threading::WaitResult::kTimeout) {
        report_long_wait();
      }
    } while (infinite && result == xe::threading::WaitResult::kTimeout);
    switch (result) {
      case xe::threading::WaitResult::kSuccess:
        for (uint32_t i = 0; i < count; i++) {
          objects[i]->WaitCallback();
          // Use the largest increment among the signaled objects.
          if (objects[i]->priority_increment() > boost_increment) {
            boost_increment = objects[i]->priority_increment();
          }
        }
        status = X_STATUS_SUCCESS;
        break;
      case xe::threading::WaitResult::kUserCallback:
        status = X_STATUS_USER_APC;
        break;
      case xe::threading::WaitResult::kTimeout:
        xe::threading::MaybeYield();
        status = X_STATUS_TIMEOUT;
        break;
      default:
      case xe::threading::WaitResult::kAbandoned:
      case xe::threading::WaitResult::kFailed:
        status = X_STATUS_ABANDONED_WAIT_0;
        break;
    }
  }

  // Apply priority boost if the thread actually blocked (not on
  // timeout/failure).
  if (status != X_STATUS_TIMEOUT && status != X_STATUS_UNSUCCESSFUL &&
      status != X_STATUS_ABANDONED_WAIT_0) {
    auto current_thread = XThread::GetCurrentThread();
    if (current_thread) {
      current_thread->BoostOnWake(boost_increment);
    }
  }
  return status;
}

uint8_t* XObject::CreateNative(uint32_t size) {
  auto global_lock = xe::global_critical_region::AcquireDirect();

  uint32_t total_size = size + sizeof(X_OBJECT_HEADER);

  auto mem = memory()->SystemHeapAlloc(total_size);
  if (!mem) {
    // Out of memory!
    return nullptr;
  }

  allocated_guest_object_ = true;
  memory()->Zero(mem, total_size);
  SetNativePointer(mem + sizeof(X_OBJECT_HEADER), true);

  auto header = memory()->TranslateVirtual<X_OBJECT_HEADER*>(mem);

  auto object_type = memory()->SystemHeapAlloc(sizeof(X_OBJECT_TYPE));
  if (object_type) {
    // Set it up in the header.
    // Some kernel method is accessing this struct and dereferencing a member
    // @ offset 0x14
    header->object_type_ptr = object_type;
  }

  return memory()->TranslateVirtual(guest_object_ptr_);
}

void XObject::SetNativePointer(uint32_t native_ptr, bool uninitialized) {
  auto global_lock = xe::global_critical_region::AcquireDirect();

  // If hit: We've already setup the native ptr with CreateNative!
  assert_zero(guest_object_ptr_);

  auto header =
      kernel_state_->memory()->TranslateVirtual<X_DISPATCH_HEADER*>(native_ptr);

  // Memory uninitialized, so don't bother with the check.
  if (!uninitialized) {
    assert_true(!(header->wait_list.blink_ptr & 0x1));
  }

  // Stash pointer in struct.
  // FIXME: This assumes the object has a dispatch header (some don't!)
  StashHandle(header, handle());

  guest_object_ptr_ = native_ptr;
}

object_ref<XObject> XObject::GetNativeObject(KernelState* kernel_state,
                                             void* native_ptr, int32_t as_type,
                                             bool already_locked) {
  assert_not_null(native_ptr);

  // Unfortunately the XDK seems to inline some KeInitialize calls, meaning
  // we never see it and just randomly start getting passed events/timers/etc.
  // Luckily it seems like all other calls (Set/Reset/Wait/etc) are used and
  // we don't have to worry about PPC code poking the struct. Because of that,
  // we init on first use, store our handle in the struct, and dereference it
  // each time.
  // We identify this by setting wait_list.flink_ptr to a magic value. When set,
  // wait_list.blink_ptr will hold a handle to our object.
  if (!already_locked) {
    global_critical_region::mutex().lock();
  }

  XObject* result;

  auto header = reinterpret_cast<X_DISPATCH_HEADER*>(native_ptr);
  if (as_type == -1) {
    as_type = header->type;
  }

  if (header->wait_list.flink_ptr == kXObjSignature) {
    // Already initialized.
    // TODO: assert if the type of the object != as_type
    uint32_t handle = header->wait_list.blink_ptr;
    result = kernel_state->object_table()
                 ->LookupObject<XObject>(handle, true)
                 .release();
  } else {
    // First use, create new.
    // https://www.nirsoft.net/kernel_struct/vista/KOBJECTS.html
    XObject* object = nullptr;
    switch (as_type) {
      case 0:  // EventNotificationObject
      case 1:  // EventSynchronizationObject
      {
        auto ev = new XEvent(kernel_state);
        ev->InitializeNative(native_ptr, header);
        object = ev;
      } break;
      case 2:  // MutantObject
      {
        auto mutant = new XMutant(kernel_state);
        mutant->InitializeNative(native_ptr, header);
        object = mutant;
      } break;
      case 5:  // SemaphoreObject
      {
        auto sem = new XSemaphore(kernel_state);
        auto success = sem->InitializeNative(native_ptr, header);
        // Can't report failure to the guest at late initialization:
        assert_true(success);
        object = sem;
      } break;
      case 3:   // ProcessObject
      case 4:   // QueueObject
      case 6:   // ThreadObject
      case 7:   // GateObject
      case 8:   // TimerNotificationObject
      case 9:   // TimerSynchronizationObject
      case 18:  // ApcObject
      case 19:  // DpcObject
      case 20:  // DeviceQueueObject
      case 21:  // EventPairObject
      case 22:  // InterruptObject
      case 23:  // ProfileObject
      case 24:  // ThreadedDpcObject
      default:
        assert_always();
        result = nullptr;
    }
    // Stash pointer in struct.
    // FIXME: This assumes the object contains a dispatch header (some don't!)
    if (object) {
      StashHandle(header, object->handle());
    }
    result = object;
  }

  if (!already_locked) {
    global_critical_region::mutex().unlock();
  }
  return object_ref<XObject>(result);
}

}  // namespace kernel
}  // namespace xe
