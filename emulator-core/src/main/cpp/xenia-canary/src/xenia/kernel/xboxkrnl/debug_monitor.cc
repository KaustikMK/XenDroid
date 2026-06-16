/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2013 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include <atomic>

#include "xenia/base/debugging.h"
#include "xenia/base/logging.h"
#include "xenia/base/memory.h"
#include "xenia/cpu/ppc/ppc_context.h"
#include "xenia/kernel/kernel_state.h"
#include "xenia/kernel/util/shim_utils.h"
#include "xenia/kernel/xboxkrnl/xboxkrnl_module.h"
#include "xenia/kernel/xboxkrnl/xboxkrnl_private.h"

DECLARE_bool(kernel_pix);

namespace xe {
namespace kernel {
namespace xboxkrnl {

enum class DebugMonitorCommand {
  PIXCommandResult = 27,
  SetPIXCallback = 28,
  Unknown66 = 66,
  Unknown89 = 89,
  Unknown94 = 94,
};

void KeDebugMonitorCallback(cpu::ppc::PPCContext* ppc_context,
                            kernel::KernelState* kernel_state) {
  auto id = static_cast<DebugMonitorCommand>(ppc_context->r[3] & 0xFFFFFFFFu);
  auto arg = static_cast<uint32_t>(ppc_context->r[4] & 0xFFFFFFFFu);

  // Titles stuck at boot can spam command 94 from runtime worker callbacks
  // every tick - log the first payloads in full (the record contents name
  // what the runtime is reporting), then throttle the plain line.
  static std::atomic<uint32_t> id94_count{0};
  if (id == DebugMonitorCommand::Unknown94) {
    uint32_t n = id94_count.fetch_add(1);
    if (n < 8 || (n & 0xFFF) == 0) {
      auto* mem = kernel_state->memory();
      std::string payload;
      if (arg && mem->LookupHeap(arg)) {
        auto* p = mem->TranslateVirtual<const uint8_t*>(arg);
        for (uint32_t i = 0; i < 0x40; i += 4) {
          payload += fmt::format(" {:08X}", xe::load_and_swap<uint32_t>(p + i));
        }
      }
      XELOGI("KeDebugMonitorCallback(94, {:08X}) #{} lr={:08X} payload:{}",
             arg, n, uint32_t(ppc_context->lr), payload);
    }
  } else {
    XELOGI("KeDebugMonitorCallback({}, {:08X})", static_cast<uint32_t>(id),
           arg);
  }

  switch (id) {
    case DebugMonitorCommand::PIXCommandResult:
    case DebugMonitorCommand::SetPIXCallback:
    case DebugMonitorCommand::Unknown66:
    case DebugMonitorCommand::Unknown89:
    case DebugMonitorCommand::Unknown94:
      break;
    default:
      // Unknown commands include the XDK stall reporter (id 82) that titles
      // spam while stuck in GPU sync waits. The caller's nonvolatile
      // registers identify the wait context (D3D device, target counter) -
      // log them so a frozen title can be diagnosed from xe.log alone.
      XELOGI(
          "KeDebugMonitorCallback: unknown id {} r1={:08X} r5={:08X} "
          "r6={:08X} r29={:08X} r30={:08X} r31={:08X} lr={:08X}",
          static_cast<uint32_t>(id), uint32_t(ppc_context->r[1]),
          uint32_t(ppc_context->r[5]), uint32_t(ppc_context->r[6]),
          uint32_t(ppc_context->r[29]), uint32_t(ppc_context->r[30]),
          uint32_t(ppc_context->r[31]), uint32_t(ppc_context->lr));
      break;
  }

  if (!cvars::kernel_pix) {
    SHIM_SET_RETURN_32(-1);
    return;
  }

  auto xboxkrnl = kernel_state->GetKernelModule<XboxkrnlModule>("xboxkrnl.exe");

  switch (id) {
    case DebugMonitorCommand::PIXCommandResult: {
      auto s = kernel_state->memory()->TranslateVirtual<const char*>(arg);
      debugging::DebugPrint("{}\n", s);
      XELOGD("PIX command result: {}\n", s);
      if (strcmp(s, "PIX!{CaptureFileCreationEnded} 0x00000000") == 0) {
        xboxkrnl->SendPIXCommand("{BeginCapture}");
      }
      SHIM_SET_RETURN_32(0);
      break;
    }
    case DebugMonitorCommand::SetPIXCallback:
      xboxkrnl->set_pix_function(arg);
      xboxkrnl->SendPIXCommand("{LimitCaptureSize} 100");  // in MB
      xboxkrnl->SendPIXCommand("{BeginCaptureFileCreation} scratch:\\test.cap");
      SHIM_SET_RETURN_32(0);
      break;
    case DebugMonitorCommand::Unknown66: {
      struct callback_info {
        xe::be<uint32_t> callback_fn;
        xe::be<uint32_t> callback_arg;  // D3D device object?
      };
      auto cbi = kernel_state->memory()->TranslateVirtual<callback_info*>(arg);
      SHIM_SET_RETURN_32(0);
      break;
    }
    case DebugMonitorCommand::Unknown89:
      // arg = function pointer?
      SHIM_SET_RETURN_32(0);
      break;
    case DebugMonitorCommand::Unknown94:
      // xboxkrnl->SendPIXCommand("{EndCapture}");
      SHIM_SET_RETURN_32(0);
      break;
    default:
      SHIM_SET_RETURN_32(-1);
      break;
  }
}

}  // namespace xboxkrnl
}  // namespace kernel
}  // namespace xe
