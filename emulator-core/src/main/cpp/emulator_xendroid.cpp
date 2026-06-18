// SPDX-License-Identifier: WTFPL
#include "emulator_xendroid.h"
#include "xendroid_emu.h"

#include <atomic>   // single-xe::Memory-per-process guard in extract_xex_meta

#include "xenia/app/emulator_window.h"
#include "xenia/emulator.h"
#include "xenia/apu/nop/nop_audio_system.h"
#include "xenia/gpu/null/null_graphics_system.h"
#include "xenia/hid/nop/nop_hid.h"
#include "xenia/base/logging.h"
#include "xenia/vfs/devices/stfs_xbox.h"
#include "xenia/base/mapped_memory.h"
#include "xenia/base/cvar.h"
#include "xenia/base/frame_stats.h"
#include "xenia/base/shader_compile_counter.h"

#include "xenia/cpu/xex_module.h"             // XexModule::GetOptHeader, kXEX2Signature/kXEX1Signature
#include "xenia/kernel/util/xex2_info.h"      // xex2_header, xex2_opt_execution_info, XEX_HEADER_EXECUTION_INFO
#include "xenia/memory.h"                      // xe::Memory standalone guest address space
#include "xenia/cpu/processor.h"               // xe::cpu::Processor (bare, for XexModule ctor)
#include "xenia/kernel/xam/xdbf/spa_info.h"    // xe::kernel::xam::SpaInfo + title_icon()
#include "xe_saf_disc_image_device.h"         // SAF_DiscImageDevice (ISO)
#include "xe_saf_disc_image_entry.h"          // SAF_DiscImageEntry accessors (mmap/data_offset/data_size)
#include "document_file.h"                    // DocumentFile::find / open_fd

#include <cstdio>

#include "cpuinfo.h"
#include "vkapi.h"
#include "vkutil.h"

//#include "cpptoml/include/cpptoml.h"

jclass g_class_DocumentFile;
jclass g_class_Emulator;

jobject g_context;
jobject g_doocument_file_tree;

jmethodID mid_open_uri_fd;

// Set in JNI_OnLoad (compose.cpp); used by DocumentFile::find for the ISO path.
extern JavaVM* g_jvm;

// Title-id format codes -- MUST match GameFormat.titleIdCode in Kotlin.
enum : jint { TID_FMT_ISO = 0, TID_FMT_XEX_FOLDER = 1 };

std::vector<std::string> g_launch_args;
std::string g_uri_info_list_file_path;
std::string g_native_lib_dir;
static void j_setup_context(JNIEnv* env,jobject self,jobject context ){
    g_context = env->NewGlobalRef(context);
    //getApplicationInfo().nativeLibraryDir;
    jmethodID mid_get_application_info = env->GetMethodID(env->GetObjectClass(context), "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");
    jobject app_info = env->CallObjectMethod(context, mid_get_application_info);
    jfieldID mid_native_library_dir = env->GetFieldID(env->GetObjectClass(app_info), "nativeLibraryDir", "Ljava/lang/String;");
    jstring native_library_dir = (jstring)env->GetObjectField(app_info, mid_native_library_dir);
    const char* native_library_dir_c_str=env->GetStringUTFChars(native_library_dir,NULL);
    g_native_lib_dir=native_library_dir_c_str;
    env->ReleaseStringUTFChars(native_library_dir,native_library_dir_c_str);
}

//public native void setup_document_file_tree(DocumentFile tree);
static void j_setup_document_file_tree(JNIEnv* env,jobject self,jobject tree ){
    g_doocument_file_tree = env->NewGlobalRef(tree);
}

//public native void setup_launch_args(String[] args);
static void j_setup_launch_args(JNIEnv* env,jobject self,jobjectArray args ){
    g_launch_args.clear();
    for(int i=0;i<env->GetArrayLength(args);i++){
        jstring arg=(jstring)env->GetObjectArrayElement(args,i);
        g_launch_args.push_back(env->GetStringUTFChars(arg,NULL) );
    }
}


static jstring j_simple_device_info(JNIEnv* env, jobject thiz)
{
    std::string info;

    auto get_gpu_info=[]()->std::string {
        std::pair<std::string,bool> lib_info={"libvulkan.so",false};
        vk_load(lib_info.first.c_str(),lib_info.second);

        struct clean_t{
            std::vector<std::function<void()>> funcs;
            ~clean_t(){
                for(auto it=funcs.rbegin();it!=funcs.rend();it++){
                    (*it)();
                }
            }
        }clean;

        clean.funcs.push_back([](){
            vk_unload();
        });

        std::optional<VkInstance> inst=vk_create_instance("compose-gpu_info");
        if(!inst) {
            return "获取gpu信息失败";
        }

        clean.funcs.push_back([=](){
            vk_destroy_instance(*inst);
        });

        if(int count=vk_get_physical_device_count(*inst);count!=1) {

            if(count<1){
                return "获取gpu信息失败";
            }
            if(count>1){
                return "多个gpu!";
            }
        }
        if(auto pdev=vk_get_physical_device(*inst);pdev) {
            std::string gpu_name=vk_get_physical_device_properties(*pdev).deviceName;
            std::string gpu_vk_ver=[](uint32_t v) {
                std::ostringstream oss;
                oss << (v >> 22) << "." << ((v >> 12) & 0x3ff) << "." << (v & 0xfff);
                return oss.str();
            }(vk_get_physical_device_properties(*pdev).apiVersion);

            std::string gpu_ext=[&]() {
                std::ostringstream oss;
                for (auto ext : vk_get_physical_device_extension_properties(*pdev)) {
                    oss <<"    * " << ext.extensionName << "\n";
                }
                return oss.str();
            }();
            return "GPU [" + gpu_name +"(Vulkan: "+gpu_vk_ver+ ")]:\n" + gpu_ext;

        }
        return "获取gpu信息失败";
    };

    auto get_cpu_info=[]()->std::string {

        std::vector<core_info_t> core_info=cpu_get_core_info();
        std::string cpu_name=cpu_get_simple_info(core_info);
        std::string cpu_features=[&](){
            std::ostringstream oss;
            for(const auto& feature : core_info[0].features){
                oss <<"    * " << feature << "\n";
            }
            return oss.str();
        }();
        return "CPU [" + cpu_name + "]:\n" + cpu_features;
    };

    info+=get_cpu_info();
    info+="\n"+get_gpu_info();

    return env->NewStringUTF(info.c_str());
}

// Light, boot-free XEX2 header walk -> execution_info.title_id.
// data/size span at least the first header_size bytes of the .xex (the whole
// mmap is fine). Returns false (and leaves *out untouched) on any malformed /
// missing-header case. Mirrors XexModule::GetOptHeader offset semantics for the
// EXECUTION_INFO (0x00040006, low-byte 0x06 -> offset) optional header.
// NOTE: the xex2_* structs live in namespace xe (xex2_info.h), while XexModule /
// kXEX*Signature live in xe::cpu (xex_module.h).
static bool read_xex_title_id(const uint8_t* data, size_t size, uint32_t* out) {
    using namespace xe;            // xex2_header, xex2_opt_*, XEX_HEADER_EXECUTION_INFO
    using namespace xe::cpu;       // XexModule, kXEX2Signature, kXEX1Signature

    if (!data || size < sizeof(xex2_header)) return false;
    auto* h = reinterpret_cast<const xex2_header*>(data);
    const uint32_t magic = h->magic.get();
    if (magic != kXEX2Signature && magic != kXEX1Signature) return false;

    // Bound the optional-header directory inside the buffer (0x18 fixed header,
    // then header_count entries of sizeof(xex2_opt_header) each).
    const uint32_t count = h->header_count.get();
    if (0x18ull + uint64_t(count) * sizeof(xex2_opt_header) > size) return false;

    // Use the non-templated void** overload directly: the templated form casts
    // away const on the out pointer, which the compiler rejects. GetOptHeader only
    // does pointer arithmetic off `h`, never writes through it.
    void* exec_raw = nullptr;
    if (!XexModule::GetOptHeader(h, XEX_HEADER_EXECUTION_INFO, &exec_raw))
        return false;              // title lacks execution_info
    if (!exec_raw) return false;

    // GetOptHeader's default branch computes header + offset with no bounds check
    // on offset, so this guard is load-bearing for untrusted files.
    const auto* p = reinterpret_cast<const uint8_t*>(exec_raw);
    if (p < data || p + sizeof(xex2_opt_execution_info) > data + size) return false;

    auto* exec_info = reinterpret_cast<const xex2_opt_execution_info*>(p);
    *out = exec_info->title_id.get();
    return true;
}

// Combined name+icon (+title_id) result for the single-decompress scan path.
struct XexMeta {
    std::string name;            // "" if absent / unreadable / failed charset validation
    std::vector<uint8_t> icon;   // empty if absent / unreadable
    uint32_t title_id = 0;       // 0 if unreadable
};

// Lightweight, allocation-free structural validation that accepts only well-formed,
// BMP-only (1-3 byte) UTF-8 -- the subset JNI NewStringUTF can consume. Rejects
// embedded NUL, overlong-2, lone continuations, and ALL 4-byte (supplementary-plane)
// sequences. We do NOT use xe::to_utf8 here: that overload only takes
// std::u16string_view (xenia/base/string.h) and is for the UTF-16 GOD title; the SPA
// title is already a raw std::string of (Xbox-convention) UTF-8/ASCII bytes.
static bool is_well_formed_utf8(const std::string& s) {
    const auto* p = reinterpret_cast<const unsigned char*>(s.data());
    const size_t n = s.size();
    for (size_t i = 0; i < n;) {
        const unsigned char c = p[i];
        if (c == 0x00) return false;              // embedded NUL -> reject
        size_t extra;
        if (c < 0x80) { extra = 0; }
        else if ((c & 0xE0) == 0xC0) { if (c < 0xC2) return false; extra = 1; }  // reject overlong 2-byte
        else if ((c & 0xF0) == 0xE0) { extra = 2; }
        // Reject ALL 4-byte leads: JNI NewStringUTF consumes MODIFIED UTF-8, which has
        // no 4-byte form (supplementary chars are CESU-8 surrogate pairs). Game titles
        // are effectively always BMP, so a 4-byte char -> reject -> filename fallback
        // rather than feed NewStringUTF a form it cannot represent.
        else { return false; }                    // 4-byte (0xF0-0xF7), lone cont. (0x80-0xBF), 0xF8-0xFF
        if (i + extra >= n) return false;          // truncated multibyte
        for (size_t k = 1; k <= extra; k++) {
            if ((p[i + k] & 0xC0) != 0x80) return false;  // bad continuation
        }
        i += extra + 1;
    }
    return true;
}

// BOUNDED title-name read for ONE language from an already-parsed XDBF/SPA. Does NOT
// call spa.Load(): SpaInfo::LoadLanguageData (spa_info.cc:50-83) walks the XSTR string
// table with `ptr += string_length + 4` and NO clamp to section->data.size(), so a
// crafted SPA can OOB-read -> host SIGSEGV the caller's try/catch cannot intercept.
// Here we walk the SAME section ourselves, clamping every read to the bounded
// section->data vector (a real memcpy'd copy of size info.size, xdbf_io.h:156-160), so
// the worst crafted input yields "" instead of a crash.
//
// Section keying mirrors LoadLanguageData/title_name: the kStringTable (0x0003) section
// is keyed by the LANGUAGE NUMBER; the per-string id is matched on
// XdbfStringTableEntry.id == kXdbfIdTitle (0x8000). GetEntry(uint16,uint64) const is
// PUBLIC (xdbf_io.h:175) so no Load()/const_cast is needed.
static std::string read_spa_title_for_language(
        const xe::kernel::xam::SpaInfo& spa, xe::XLanguage lang) {
    using namespace xe::kernel::xam;

    const Entry* section = spa.GetEntry(
        static_cast<uint16_t>(SpaSection::kStringTable),
        static_cast<uint64_t>(lang));
    if (!section) return "";

    const uint8_t* const begin = section->data.data();
    const size_t avail = section->data.size();
    if (avail < sizeof(XdbfSectionHeaderEx)) return "";

    const auto* header = reinterpret_cast<const XdbfSectionHeaderEx*>(begin);
    if (header->magic != kXdbfSignatureXstr) return "";  // not a string table

    const uint8_t* ptr = begin + sizeof(XdbfSectionHeaderEx);
    const uint8_t* const end = begin + avail;
    const uint16_t count = header->count;

    for (uint16_t i = 0; i < count; i++) {
        // Need the 4-byte entry header.
        if (ptr + sizeof(XdbfStringTableEntry) > end) return "";
        const auto* entry = reinterpret_cast<const XdbfStringTableEntry*>(ptr);
        const uint16_t str_len = entry->string_length;
        const uint8_t* str_ptr = ptr + sizeof(XdbfStringTableEntry);
        // Need str_len payload bytes (no wrap: str_ptr <= end already, str_len<=0xFFFF).
        if (str_ptr + str_len > end) return "";

        if (entry->id == static_cast<uint16_t>(kXdbfIdTitle)) {
            std::string result(reinterpret_cast<const char*>(str_ptr), str_len);
            // Cut at first embedded NUL (Modified-UTF-8/NewStringUTF mis-handles it).
            const size_t nul = result.find('\0');
            if (nul != std::string::npos) result.resize(nul);
            if (result.empty() || !is_well_formed_utf8(result)) return "";
            return result;  // valid UTF-8 title
        }
        ptr = str_ptr + str_len;
    }
    return "";  // title id not present in this language's table
}

// PREFER the ENGLISH title so a game authored for another region still shows its
// English name in the library; fall back to the game's default language only when an
// English string table is absent (e.g. a Japan-only release), so we still show
// *something* rather than the filename.
static std::string read_spa_title_name_bounded(
        const xe::kernel::xam::SpaInfo& spa) {
    std::string name = read_spa_title_for_language(spa, xe::XLanguage::kEnglish);
    if (!name.empty()) return name;

    const xe::XLanguage def = spa.default_language();  // public; ctor-parsed, no Load()
    if (def != xe::XLanguage::kEnglish) {
        name = read_spa_title_for_language(spa, def);
    }
    return name;
}

// Decompress default.xex into a transient guest address space and pull BOTH the
// XDBF/SPA title NAME and title icon PNG out of its title-id resource section, plus
// the title id (free, already computed). Returns a XexMeta with any subset of fields
// populated; empty/zero on failure. base/size must span the full .xex image.
// SAFETY: three guards on the decompress path (single-Memory atomic guard,
// resource-header bounds, res-span image-extent clamp). The NAME is read via the
// BOUNDED read_spa_title_name_bounded (no spa.Load(), no OOB-walk).
static XexMeta extract_xex_meta(const uint8_t* base, size_t size) {
    using namespace xe;            // xe::Memory, xex2_* structs, XEX_HEADER_RESOURCE_INFO
    using namespace xe::cpu;       // XexModule, Processor, kXEX*Signature

    XexMeta out;
    if (!base || size < sizeof(xex2_header)) return out;

    // Quick magic pre-check so we don't stand up a 4GB mapping for non-XEX data.
    auto* hdr = reinterpret_cast<const xex2_header*>(base);
    const uint32_t magic = hdr->magic.get();
    if (magic != kXEX2Signature && magic != kXEX1Signature) return out;
    // header_size must be inside the buffer (Load memcpy's header_size bytes).
    if (hdr->header_size.get() > size) return out;

    // xe::Memory owns a PROCESS-GLOBAL singleton (active_memory_) and, on
    // XE_PLATFORM_xendroid, a single FIXED host-base mapping -- so only ONE may be
    // alive per process at a time. The only caller is the library scan, which runs
    // in the main/library process and never boots a game in-process (the emulator
    // runs in the separate :emu process, with its own per-process active_memory_),
    // so it never collides with a live game's Memory. This guard ADDITIONALLY
    // enforces that invariant within this process: if a Memory is already standing
    // (a concurrent/re-entrant scan), bail with no icon rather than letting a second
    // ctor silently corrupt the active_memory_ singleton in release builds.
    static std::atomic<bool> s_memory_in_use{false};
    bool expected = false;
    if (!s_memory_in_use.compare_exchange_strong(expected, true)) return out;
    struct MemoryGuard { ~MemoryGuard() { s_memory_in_use.store(false); } } mem_guard;

    // Transient guest address space + bare processor. RAII frees both on scope
    // exit; ~Memory unmaps the fixed-base views. processor must outlive xex_module
    // (XexModule holds memory_ = processor->memory()); declare in that order so
    // destruction is xex_module -> processor -> memory.
    xe::Memory memory;
    if (!memory.Initialize()) return out;
    xe::cpu::Processor processor(&memory, nullptr /*export_resolver*/);

    // kernel_state may be null: Load()/ReadImage never dereference it (only
    // LoadContinue/import-resolution do, which we deliberately skip).
    auto xex_module = std::make_unique<XexModule>(&processor, nullptr);

    // Wrap in a try/catch: ReadImage hits assert_*/may throw on crafted inputs
    // in debug; in release asserts are off but keep the guard for any std throw.
    bool loaded = false;
    try {
        loaded = xex_module->Load("default", "default.xex", base, size);
    } catch (...) {
        loaded = false;
    }
    if (!loaded) return out;  // bad magic / all keys failed / no valid PE

    // Replicate UserModule::GetSection (user_module.cc:281-302) to find the title's
    // resource section. The resource directory is an optional header that lives
    // UNCOMPRESSED in the XEX header region (the first header_size bytes), so read it
    // straight from the raw file with the SAME load-bearing bounds guard as
    // read_xex_title_id -- GetOptHeader's default branch computes header+offset with
    // NO validation, so a crafted resource offset could otherwise point anywhere.
    void* res_raw = nullptr;
    if (!XexModule::GetOptHeader(hdr, XEX_HEADER_RESOURCE_INFO, &res_raw) || !res_raw)
        return out;  // no resources
    const uint8_t* rp = reinterpret_cast<const uint8_t*>(res_raw);
    if (rp < base || rp + sizeof(xex2_opt_resource_info) > base + size) return out;
    auto* res_hdr = reinterpret_cast<const xex2_opt_resource_info*>(rp);
    const uint32_t res_blob_size = res_hdr->size.get();
    if (res_blob_size < 4) return out;
    const uint32_t count = (res_blob_size - 4) / uint32_t(sizeof(xex2_resource));
    // Bound the full resources[count] array inside the file before iterating it.
    if (uint64_t(rp - base) + 4 + uint64_t(count) * sizeof(xex2_resource) > size)
        return out;

    // The title-id resource is named with the 8-char uppercase-hex title id.
    // Pull the title id straight from the header (reuse read_xex_title_id).
    uint32_t title_id = 0;
    if (!read_xex_title_id(base, size, &title_id) || title_id == 0) return out;
    out.title_id = title_id;                                   // capture (free)
    const std::string res_name = fmt::format("{:08X}", title_id);  // exactly 8 chars

    uint32_t res_addr = 0, res_size = 0;
    for (uint32_t i = 0; i < count; i++) {
        const xex2_resource& r = res_hdr->resources[i];
        if (std::memcmp(r.name, res_name.data(), 8) == 0) {
            res_addr = r.address.get();
            res_size = r.size.get();
            break;
        }
    }
    if (res_addr == 0 || res_size == 0) return out;  // no title resource

    // Validate [res_addr, res_addr+res_size) lies WHOLLY inside the loaded image
    // extent [base_address, base_address+image_size) before handing it to the parser.
    // res_addr/res_size come from the untrusted header, so this keeps the resource
    // span backed by real, decompressed image bytes.
    if (uint64_t(res_addr) + res_size < res_addr) return out;            // wrap
    const uint32_t img_base = xex_module->base_address();
    const uint64_t img_end = uint64_t(img_base) + xex_module->image_size();
    if (res_addr < img_base || uint64_t(res_addr) + res_size > img_end) return out;
    if (!memory.LookupHeap(res_addr)) return out;
    uint8_t* res_ptr = memory.TranslateVirtual(res_addr);
    if (!res_ptr) return out;

    // Parse XDBF/SPA once: icon via ctor-parsed title_icon(); name via the BOUNDED
    // reader (NO spa.Load()). Both copied out BEFORE memory unmaps. The bounded name
    // reader cannot OOB, but title_icon() and the SpaInfo ctor (Entry memcpy, no
    // offset/size clamp -- xdbf_io.h:156-160) carry a residual XDBF-trust risk on a
    // crafted-but-loadable XEX, the same one the upstream module_xdbf path carries;
    // the try/catch below only catches std throws, not a SIGSEGV from that parser.
    try {
        xe::kernel::xam::SpaInfo spa(std::span<uint8_t>(res_ptr, res_size));
        std::span<const uint8_t> icon = spa.title_icon();
        if (!icon.empty()) {
            out.icon.assign(icon.begin(), icon.end());  // copy out BEFORE memory unmaps
        }
        out.name = read_spa_title_name_bounded(spa);  // "" on any failure
    } catch (...) {
        out.icon.clear();
        out.name.clear();
    }
    return out;  // memory/processor/xex_module torn down here
}

// nullptr jstring (treated as "unavailable") if title_id == 0 or unreadable.
static jstring make_title_id_jstring(JNIEnv* env, bool ok, uint32_t title_id) {
    if (!ok || title_id == 0) return nullptr;
    return env->NewStringUTF(fmt::format("{:08X}", title_id).c_str());
}

// public native String title_id_from_uri(Context ctx, String uri, int format)
static jstring j_title_id_from_uri(JNIEnv* env, jobject self,
                                   jobject context, jstring uri_str, jint format) {
    if (!uri_str) return nullptr;

    jclass uri_class = env->FindClass("android/net/Uri");
    jmethodID parse_method =
            env->GetStaticMethodID(uri_class, "parse",
                                   "(Ljava/lang/String;)Landroid/net/Uri;");
    jobject uri = env->CallStaticObjectMethod(uri_class, parse_method, uri_str);
    if (!uri) return nullptr;

    uint32_t title_id = 0;
    bool ok = false;

    switch (format) {
        case TID_FMT_XEX_FOLDER: {
            // launchUri already points at default.xex; read its bytes directly.
            int fd = env->CallStaticIntMethod(g_class_Emulator, mid_open_uri_fd,
                                              context, uri);
            if (fd == -1) break;
            std::unique_ptr<xe::MappedMemory> mmap =
                    xe::MappedMemory::OpenForUnixFd(fd);  // takes ownership of fd
            if (!mmap) break;
            ok = read_xex_title_id(mmap->data(), mmap->size(), &title_id);
            break;
        }
        case TID_FMT_ISO: {
            // Mount the disc just long enough to resolve+read default.xex. RAII
            // frees the fd/mmap on scope exit; do NOT cache Entry/mmap past it.
            std::unique_ptr<DocumentFile> file = DocumentFile::find(g_jvm, uri);
            if (!file) break;                              // not in granted tree
            xe::vfs::SAF_DiscImageDevice dev("\\Device\\Cdrom0", std::move(file));
            if (!dev.Initialize()) break;                  // not XDVDFS / corrupt
            xe::vfs::Entry* e = dev.ResolvePath("default.xex");
            if (!e) break;                                 // multi-disc / custom launch
            auto* de = static_cast<xe::vfs::SAF_DiscImageEntry*>(e);
            if (!de->mmap()) break;
            // GDFX file extents come straight from the on-disc directory and are NOT
            // validated against the mapping size, so clamp the span to the actual mmap
            // before the (untrusted-file) header read -- a truncated/crafted ISO must
            // not OOB-read.
            const size_t map_size = de->mmap()->size();
            if (de->data_offset() > map_size) break;
            const size_t span = std::min<size_t>(de->data_size(), map_size - de->data_offset());
            const uint8_t* base = de->mmap()->data() + de->data_offset();
            ok = read_xex_title_id(base, span, &title_id);
            break;                                         // dev (mmap/fd) freed here
        }
        default:
            break;                                         // GOD/ZAR not routed here
    }

    return make_title_id_jstring(env, ok, title_id);
}

// public native GameInfo meta_from_xex(Context ctx, String uri, int format)
// ISO (0): uri = ISO container; XEX_FOLDER (1): uri = default.xex child. Returns a
// GameInfo {name, titleId, icon} from a SINGLE default.xex decompress, or null when
// nothing was readable. name/icon may individually be absent (null) -> Kotlin falls
// back to the filename name / app_icon. uri field echoes the input.
static jobject j_meta_from_xex(JNIEnv* env, jobject self,
                               jobject context, jstring uri_str, jint format) {
    if (!uri_str) return nullptr;

    jclass uri_class = env->FindClass("android/net/Uri");
    jmethodID parse_method =
            env->GetStaticMethodID(uri_class, "parse",
                                   "(Ljava/lang/String;)Landroid/net/Uri;");
    jobject uri = env->CallStaticObjectMethod(uri_class, parse_method, uri_str);
    if (!uri) return nullptr;

    XexMeta meta;  // name/icon empty, title_id 0 on failure

    switch (format) {
        case TID_FMT_XEX_FOLDER: {
            // launchUri already points at default.xex; read its bytes directly.
            int fd = env->CallStaticIntMethod(g_class_Emulator, mid_open_uri_fd,
                                              context, uri);
            if (fd == -1) break;
            std::unique_ptr<xe::MappedMemory> mmap =
                    xe::MappedMemory::OpenForUnixFd(fd);  // takes ownership of fd
            if (!mmap) break;
            meta = extract_xex_meta(mmap->data(), mmap->size());
            break;  // mmap (fd) freed here
        }
        case TID_FMT_ISO: {
            // Mount the disc just long enough to resolve+read default.xex. RAII
            // frees the fd/mmap on scope exit; do NOT cache Entry/mmap past it.
            std::unique_ptr<DocumentFile> file = DocumentFile::find(g_jvm, uri);
            if (!file) break;                              // not in granted tree
            xe::vfs::SAF_DiscImageDevice dev("\\Device\\Cdrom0", std::move(file));
            if (!dev.Initialize()) break;                  // not XDVDFS / corrupt
            xe::vfs::Entry* e = dev.ResolvePath("default.xex");
            if (!e) break;                                 // multi-disc / custom launch
            auto* de = static_cast<xe::vfs::SAF_DiscImageEntry*>(e);
            if (!de->mmap()) break;
            // Clamp the span to the actual mmap (untrusted on-disc extents).
            const size_t map_size = de->mmap()->size();
            if (de->data_offset() > map_size) break;
            const size_t span = std::min<size_t>(de->data_size(),
                                                 map_size - de->data_offset());
            const uint8_t* dbase = de->mmap()->data() + de->data_offset();
            meta = extract_xex_meta(dbase, span);
            break;                                         // dev (mmap/fd) freed here
        }
        default:
            break;                                         // GOD/ZAR not routed here
    }

    // Nothing readable at all -> null (Kotlin keeps the filename name, no icon).
    if (meta.name.empty() && meta.icon.empty() && meta.title_id == 0) {
        return nullptr;
    }

    jclass cls = env->FindClass("xendroid/compose/Emulator$GameInfo");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "()V");
    jfieldID fid_name = env->GetFieldID(cls, "name", "Ljava/lang/String;");
    jfieldID fid_uri = env->GetFieldID(cls, "uri", "Ljava/lang/String;");
    jfieldID fid_title_id = env->GetFieldID(cls, "titleId", "Ljava/lang/String;");
    jfieldID fid_icon = env->GetFieldID(cls, "icon", "[B");

    jobject game_info = env->NewObject(cls, ctor);
    env->SetObjectField(game_info, fid_uri, uri_str);

    // NAME: already NUL-cut + UTF-8-validated by the bounded reader. Empty -> leave
    // field null so Kotlin uses the filename fallback (do NOT set "").
    if (!meta.name.empty()) {
        jstring name_j = env->NewStringUTF(meta.name.c_str());
        env->SetObjectField(game_info, fid_name, name_j);
    }

    // TITLE_ID: reuse the existing 8-hex helper (null when 0).
    env->SetObjectField(game_info, fid_title_id,
                        make_title_id_jstring(env, meta.title_id != 0, meta.title_id));

    // ICON: PNG bytes, or leave null.
    if (!meta.icon.empty()) {
        jbyteArray icon = env->NewByteArray(static_cast<jsize>(meta.icon.size()));
        if (icon) {
            env->SetByteArrayRegion(icon, 0, static_cast<jsize>(meta.icon.size()),
                                    reinterpret_cast<const jbyte*>(meta.icon.data()));
            env->SetObjectField(game_info, fid_icon, icon);
        }
    }
    return game_info;
}

static jobject j_meta_info_from_god_game(JNIEnv* env,jobject self,jobject context,jstring uri_str ) {
    jclass cls_Emulator$GameInfo = env->FindClass("xendroid/compose/Emulator$GameInfo");
    jmethodID mid_Emulator$GameInfo = env->GetMethodID(cls_Emulator$GameInfo, "<init>", "()V");
    jfieldID fid_name = env->GetFieldID(cls_Emulator$GameInfo, "name", "Ljava/lang/String;");
    jfieldID fid_uri = env->GetFieldID(cls_Emulator$GameInfo, "uri", "Ljava/lang/String;");
    jfieldID fid_title_id = env->GetFieldID(cls_Emulator$GameInfo, "titleId", "Ljava/lang/String;");
    jfieldID fid_icon = env->GetFieldID(cls_Emulator$GameInfo, "icon", "[B");

    jclass uri_class = env->FindClass("android/net/Uri");
    jmethodID parse_method = env->GetStaticMethodID(uri_class, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");

    jobject game_info = env->NewObject(cls_Emulator$GameInfo, mid_Emulator$GameInfo);
    env->SetObjectField(game_info, fid_uri, uri_str);

    jobject uri = env->CallStaticObjectMethod(uri_class, parse_method, uri_str);

    xe::vfs::XContentContainerHeader header;
    // read header
    {
        //public static int nc_open_uri_fd(Context ctx,Uri uri)
        int header_file_fd = env->CallStaticIntMethod(g_class_Emulator, mid_open_uri_fd, context, uri);

        if (header_file_fd == -1) {
            return NULL;
        }
        std::unique_ptr<xe::MappedMemory> mmap = xe::MappedMemory::OpenForUnixFd(header_file_fd);
        if (!mmap) {
            return NULL;
        }
        if(mmap->size() < sizeof(header)) {
            return NULL;
        }
        std::memcpy(&header, mmap->data(), sizeof(header));
    }

    std::string name = xe::to_utf8(header.content_metadata.title_name());
    env->SetObjectField(game_info, fid_name, env->NewStringUTF(name.c_str()));

    // Title id for the per-game config file stem (<TITLE_ID>.config.toml). Same
    // accessor + format spec xenia uses at emulator.cc:995 / :1687 so the stem
    // matches what config::LoadGameConfig consumes.
    uint32_t title_id = header.content_metadata.execution_info.title_id.get();
    std::string title_id_hex = fmt::format("{:08X}", title_id);
    env->SetObjectField(game_info, fid_title_id,
                        env->NewStringUTF(title_id_hex.c_str()));

    jbyteArray icon = env->NewByteArray(header.content_metadata.thumbnail_size);
    env->SetByteArrayRegion(icon, 0, header.content_metadata.thumbnail_size, (const jbyte*)header.content_metadata.thumbnail);
    env->SetObjectField(game_info, fid_icon, icon);
    return game_info;
}
#if 0
static std::unique_ptr<xe::apu::AudioSystem> create_nop_audio_system(
        xe::cpu::Processor* processor) {
    return std::make_unique<xe::apu::nop::NopAudioSystem>(processor);
}

static std::unique_ptr<xe::gpu::GraphicsSystem> create_null_graphics_system() {
    return std::make_unique<xe::gpu::null::NullGraphicsSystem>();
}

static std::vector<std::unique_ptr<xe::hid::InputDriver>> create_nop_input_drivers(
        xe::ui::Window* window) {

    std::vector<std::unique_ptr<xe::hid::InputDriver>> drivers;
    drivers.emplace_back(xe::hid::nop::Create(window, xe::app::EmulatorWindow::kZOrderHidInput));

    return drivers;
}
//public native GameInfo meta_info_from_uri(String uri) throws RuntimeException;
static jobject j_meta_info_from_uri(JNIEnv* env,jobject self,jstring uri_str ){

    /*
    public static class GameInfo{
        public String name;
        public String uri;
        public int fd;
        public byte[] icon;
     */
    jclass cls_Emulator$GameInfo = env->FindClass("xendroid/compose/Emulator$GameInfo");
    jmethodID mid_Emulator$GameInfo = env->GetMethodID(cls_Emulator$GameInfo, "<init>", "()V");
    jobject game_info = env->NewObject(cls_Emulator$GameInfo, mid_Emulator$GameInfo);
    jfieldID fid_name = env->GetFieldID(cls_Emulator$GameInfo, "name", "Ljava/lang/String;");
    jfieldID fid_uri = env->GetFieldID(cls_Emulator$GameInfo, "uri", "Ljava/lang/String;");
    env->SetObjectField(game_info, fid_uri, uri_str);


    jclass uri_class = env->FindClass("android/net/Uri");
    jmethodID parse_method = env->GetStaticMethodID(uri_class, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
    jobject uri = env->CallStaticObjectMethod(uri_class, parse_method, uri_str);

    std::unique_ptr<DocumentFile> file = DocumentFile::find(env, uri);

    std::vector<char*> args;
    args.push_back(NULL);
    for(auto& i:g_launch_args){
        args.push_back((char*)i.c_str());
    }

    int argc=args.size();
    char** argv=args.data();

    cvar::ParseLaunchArguments(argc, argv, "",{});
    xe::InitializeLogging(file->getName());

    AndroidWindowedAppContext app_context;
    std::unique_ptr<xe::Emulator> emulator = std::make_unique<xe::Emulator>("","","","");
    auto emulator_wnd = xe::app::EmulatorWindow::Create(emulator.get(), app_context);
    xe::X_STATUS result = emulator->Setup(
            emulator_wnd->window(), emulator_wnd->imgui_drawer(), true,
            create_nop_audio_system, create_null_graphics_system, create_nop_input_drivers);
    if (XFAILED(result)) {
        env->SetObjectField(game_info, fid_name, env->NewStringUTF("???")) ;
        return game_info;
    }
    std::string result_str;
    bool ret=false;
    emulator->on_launch.AddListener([&](auto title_id, const auto& game_title) {
        result_str=game_title.empty() ? "Unknown Title" : std::string(game_title);
        XELOGI("#############: {}", result_str);
        ret=true;
    });

    std::string name = file->getName();
    if(name.ends_with(".xex")){
        result = emulator->LaunchXexFile(std::move(file));
    }
    else{
        const char* path = env->GetStringUTFChars(uri_str,NULL);
        std::string data_dir = std::string (path)+".data";
        env->ReleaseStringUTFChars(uri_str,path);

        jstring data_dir_str = env->NewStringUTF(data_dir.c_str());
        jobject data_dir_uri = env->CallStaticObjectMethod(uri_class, parse_method, data_dir_str);

        std::unique_ptr<DocumentFile> data_dir_file =
                DocumentFile::find(env, data_dir_uri);

        result = emulator->LaunchStfsContainer(std::move(file), std::move(data_dir_file));
    }

    if (XFAILED(result)) {
        env->SetObjectField(game_info, fid_name, env->NewStringUTF("????")) ;
        return game_info;
    }

    while (!ret);
    XELOGI("################Game: {}", result_str);
    env->SetObjectField(game_info, fid_name, env->NewStringUTF(result_str.c_str())) ;
    return game_info;
}
#endif

static const std::string gen_skips[]={
        //"CPU",
        "Config",
        "a64",
        "Profiles",
        "Vulkan|vulkan_device",

        "Storage|cache_root",
        "Storage|content_root",
        "Storage|storage_root",
        "Kernel|kernel_display_gamma_power",
        "Kernel|cl",
        "Kernel|kernel_build_version",
        "Kernel|default_achievements_backend",

        "Display|postprocess_ffx_cas_additional_sharpness",
        "Display|present_safe_area_y",
        "Display|postprocess_ffx_fsr_max_upsampling_passes",
        "Display|present_safe_area_x",
        "Display|postprocess_ffx_fsr_sharpness_reduction",


        "GPU|dump_shaders",
        "GPU|draw_resolution_scale_x",
        "GPU|primitive_processor_cache_min_indices",
        "GPU|query_occlusion_fake_sample_count",
        "GPU|texture_cache_memory_limit_soft_lifetime",
        "GPU|draw_resolution_scale_y",
        "GPU|trace_gpu_prefix",
        "GPU|texture_cache_memory_limit_render_to_texture",

        "GPU|query_occlusion_sample_lower_threshold",
        "GPU|query_occlusion_sample_upper_threshold",
        "GPU|framerate_limit",

        "CPU|pvr",
        "CPU|load_module_map",
         "CPU|break_condition_op",
         "CPU|trace_function_data",
         "CPU|trace_function_data_path",
          "CPU|break_condition_value",
           "CPU|break_on_instruction",
            "CPU|break_condition_gpr",

        "Logging|log_file",
        "Logging|log_mask",

        "Video|internal_display_resolution_x",
        "Video|internal_display_resolution_y",

        "HID|left_stick_deadzone_percentage",
        "HID|right_stick_deadzone_percentage",
        "HID|vibration",

        "XConfig|audio_flag",

        "General|notification_sound_path",
        "General|launch_module",


};
using entries=std::vector<std::string>;
static const std::pair<std::string,entries> gen_list[]={
        //str
        {"APU|apu",{"nop","aaudio","opensles"}},
        {"Display|postprocess_antialiasing",{"none", "fxaa", "fxaa_extreme"}},
        {"Display|postprocess_scaling_and_sharpening",{"bilinear", "cas", "fsr"}},
        {"GPU|gpu",{"vulkan", "null"}},
        {"GPU|render_target_path_vulkan",{"any", "fbo","fsi"}},
        {"HID|hid",{"android", "nop"}},
        {"CPU|cpu",{"any","a64"}},

        //int
        {"Content|license_mask",{"disable@0","first@1","all@-1"}},
        {"XConfig|user_country",{"AE@1","AL@2", "AM@3", "AR@4", "AT@5", "AU@6", "AZ@7", "BE@8", "BG@9"
                                 , "BH@10", "BN@11", "BO@12", "BR@13", "BY@14", "BZ@15", "CA@16", "CH@18", "CL@19"
                                 , "CN@20", "CO@21", "CR@22", "CZ@23", "DE@24", "DK@25", "DO@26", "DZ@27", "EC@28"
                                 , "EE@29", "EG@30", "ES@31", "FI@32", "FO@33", "FR@34", "GB@35", "GE@36", "GR@37"
                                 , "GT@38", "HK@39", "HN@40", "HR@41", "HU@42", "ID@43", "IE@44", "IL@45", "IN@46"
                                 , "IQ@47", "IR@48", "IS@49", "IT@50", "JM@51", "JO@52", "JP@53", "KE@54", "KG@55"
                                 , "KR@56", "KW@57", "KZ@58", "LB@59", "LI@60", "LT@61", "LU@62", "LV@63", "LY@64"
                                 , "MA@65", "MC@66", "MK@67", "MN@68", "MO@69", "MV@70", "MX@71", "MY@72", "NI@73"
                                 , "NL@74", "NO@75", "NZ@76", "OM@77", "PA@78", "PE@79", "PH@80", "PK@81", "PL@82"
                                 , "PR@83", "PT@84", "PY@85", "QA@86", "RO@87", "RU@88", "SA@89", "SE@90", "SG@91"
                                 , "SI@92", "SK@93", "SV@95", "SY@96", "TH@97", "TN@98", "TR@99", "TT@100","TW@101"
                                 , "UA@102", "US@103", "UY@104", "UZ@105", "VE@106", "VN@107", "YE@108", "ZA@109"
                                 }},
        {"XConfig|user_language",{"en@1","ja@2","de@3","fr@4","es@5","it@6","ko@7","zh@8"
                                 ,"pt@9","pl@11","ru@12","sv@13","tr@14","nb@15","nl@16","zh@17"}},
        {"Vulkan|vulkan_debug_utils_messenger_severity",{"error@0","warning@1","info@2","verbose@3"}},

        {"Kernel|kernel_display_gamma_type",{"linear@0","sRGB(CRT)@1","BT.709(HDTV)@2",/*kernel_display_gamma_power@3*/}},
        {"Logging|log_level",{"error@0","warning@1","info@2","debug@3",}},
        /*
                                                  	#  0 = PAL-60 Component (SD)
                                                  	#  1 = Unused
                                                  	#  2 = PAL-60 SCART
                                                  	#  3 = 480p Component (HD)
                                                  	#  4 = HDMI+A
                                                  	#  5 = PAL-60 Composite/S-Video
                                                  	#  6 = VGA
                                                  	#  7 = TV PAL-60
                                                  	#  8 = HDMI (default)*/
        {"Video|avpack",{"PAL-60 Component (SD)@0", "Unused@1","PAL-60 SCART@2","480p Component (HD)@3","HDMI+A@4","PAL-60 Composite/S-Video@5","VGA@6","TV PAL-60@7","HDMI@8"}},
        /*#    1=NTSC
                                                  	#    2=NTSC-J
                                                  	#    3=PAL*/
        {"Video|video_standard",{ "NTSC@1","NTSC-J@2","PAL-60@3"}},
/*#    0=640x480
                                                  	#    1=640x576
                                                  	#    2=720x480
                                                  	#    3=720x576
                                                  	#    4=800x600
                                                  	#    5=848x480
                                                  	#    6=1024x768
                                                  	#    7=1152x864
                                                  	#    8=1280x720 (Default)
                                                  	#    9=1280x768
                                                  	#    10=1280x960
                                                  	#    11=1280x1024
                                                  	#    12=1360x768
                                                  	#    13=1440x900
                                                  	#    14=1680x1050
                                                  	#    15=1920x540
                                                  	#    16=1920x1080*/
        {"Video|internal_display_resolution",{ "640x480@0","640x576@1","720x480@2","720x576@3","800x600@4","848x480@5","1024x768@6","1152x864@7","1280x720@8"
                                               ,"1280x768@9","1280x960@10","1280x1024@11","1360x768@12","1440x900@13", "1680x1050@14","1920x540@15","1920x1080@16"}},
                                               /*Kernel = 1, Apu = 2, Cpu = 4.*/
        //{"Logging|log_file"}

};

using range=std::pair<int,int>;
static const std::pair<std::string,range> gen_seekbar[]={
        {"GPU|texture_cache_memory_limit_hard",{512,4096}},
        {"GPU|texture_cache_memory_limit_soft",{512,4096}},
        {"Memory|mmap_address_high",{2,63}},
        {"APU|apu_max_queued_frames",{4,64}},
        {"APU|xmp_default_volume",{0,100}},
        {"General|time_scalar",{1,8}},
        //{"Video|internal_display_resolution_x",{1,1920}},
        //{"Video|internal_display_resolution_y",{1,1080}},
};

#define SEEKBAR_PREF_TAG "xendroid.preference.SeekBarPreference"
#define CHECKBOX_PREF_TAG "xendroid.preference.CheckBoxPreference"
#define LIST_PREF_TAG "xendroid.preference.ListPreference"
#if 1

static jstring generate_config_xml(JNIEnv* env,jobject self,jstring toml_path){
    return env->NewStringUTF("out.str().c_str()");
}
#else
static jstring generate_config_xml(JNIEnv* env,jobject self,jstring toml_path){

    jboolean is_copy=false;
    const char* path=env->GetStringUTFChars(toml_path,&is_copy);

    std::shared_ptr<cpptoml::table> toml=cpptoml::parse_file(path);
    env->ReleaseStringUTFChars(toml_path,path);

    std::ostringstream out;
    out<<R"(
<?xml version="1.0" encoding="utf-8"?>
<PreferenceScreen
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    )";

    for(auto table_iter=toml->begin() ;table_iter!=toml->end();table_iter++){
        const std::string& table_name=table_iter->first;
        if(std::find(std::begin(gen_skips),std::end(gen_skips),table_name)!=std::end(gen_skips))
            continue;
        std::string table_name_l=table_name; std::transform(table_name_l.begin(),table_name_l.end(),table_name_l.begin(),::tolower);
        std::shared_ptr<cpptoml::table> table=table_iter->second->as_table();
        out<<"<PreferenceScreen app:title=\"@string/es_"<<table_name_l<<"\" \n";
        out<<"app:key=\""<<table_name<<"\" >\n";

        for(auto iter=table->begin();iter!=table->end();iter++){
            const std::string& key_name=iter->first;
            const std::string find_key=table_name+"|"+key_name;
            if(std::find(std::begin(gen_skips),std::end(gen_skips),find_key)!=std::end(gen_skips))
                continue;

            {
                auto find_iter=std::begin(gen_list);
                find_iter=std::find_if(find_iter,std::end(gen_list),[&find_key](const std::pair<std::string,entries>& entry){
                    return entry.first==find_key;
                });
                if(find_iter!=std::end(gen_list)){
                    out<<"<" LIST_PREF_TAG " app:title=\"@string/es_"<<table_name_l<<"_"<<key_name<<"\" \n";
                    if(std::find(std::begin(find_iter->second[0]),std::end(find_iter->second[0]),'@')!=std::end(find_iter->second[0])){
                        out<<"app:entryValues=\"@array/es_arr_v_"<<table_name_l<<"_"<<key_name<<"\" \n";
                    }
                    else{
                        out<<"app:entryValues=\"@array/es_arr_"<<table_name_l<<"_"<<key_name<<"\" \n";
                    }
                    out<<"app:entries=\"@array/es_arr_"<<table_name_l<<"_"<<key_name<<"\" \n";
                    out<<"app:key=\""<<table_name<<"|"<<key_name<<"\" />\n";
                    continue;
                }
            }

            {
                auto find_iter=std::begin(gen_seekbar);
                find_iter=std::find_if(find_iter,std::end(gen_seekbar),[&find_key](const std::pair<std::string,range>& entry){
                    return entry.first==find_key;
                });
                if(find_iter!=std::end(gen_seekbar)){
                    out<<"<" SEEKBAR_PREF_TAG " app:title=\"@string/es_"<<table_name_l<<"_"<<key_name<<"\" \n";
                    out<<"app:min=\""<<find_iter->second.first<<"\"\n";
                    out<<"android:max=\""<<find_iter->second.second<<"\"\n";
                    out<<"app:showSeekBarValue=\"true\"\n";
                    out<<"app:key=\""<<table_name<<"|"<<key_name<<"\" />\n";
                    continue;
                }
            }

            if(const auto val=table->get_as<bool>(key_name);val){
                std::string val_str=*val?"true":"false";
                out<<"<" CHECKBOX_PREF_TAG " app:title=\"@string/es_"<<table_name_l<<"_"<<key_name<<"\" \n";
                out<<"app:key=\""<<table_name<<"|"<<key_name<<"\" />\n";
            }
            /*else if(const auto val=table->get_as<int>(key_name);val){
                out<<"<" SEEKBAR_PREF_TAG " app:title=\"@string/es_"<<table_name_l<<"_"<<key_name<<"\" \n";
                out<<"app:showSeekBarValue=\"true\"\n";
                out<<"app:key=\""<<table_name<<"|"<<key_name<<"\" />\n";
            }*/
            else if(const auto val=table->get_as<double>(key_name);val){
                //FIXME
                out<<"<PreferenceScreen app:title=\"@string/es_"<<table_name_l<<"_"<<key_name<<"\" \n";
                out<<"app:key=\""<<table_name<<"|"<<key_name<<"\" />\n";
            }
            else if(const auto val=table->get_as<std::string>(key_name);val){
                //FIXME
                out<<"<PreferenceScreen app:title=\"@string/es_"<<table_name_l<<"_"<<key_name<<"\" \n";
                out<<"app:key=\""<<table_name<<"|"<<key_name<<"\" />\n";
            }
        }

        out<<"</PreferenceScreen>\n";
    }

    out<<"</PreferenceScreen>\n";

    //JAVA const String[]
    out<<"\n\n\n\n";

    out<<"final String[] BOOL_KEYS={\n";
    for(auto table_iter=toml->begin() ;table_iter!=toml->end();table_iter++){
        const std::string& table_name=table_iter->first;
        std::shared_ptr<cpptoml::table> table=table_iter->second->as_table();
        if(std::find(std::begin(gen_skips),std::end(gen_skips),table_name)!=std::end(gen_skips))
            continue;
        for(auto iter=table->begin();iter!=table->end();iter++){
            const std::string& key_name=iter->first;
            const std::string find_key=table_name+"|"+key_name;
            if(std::find(std::begin(gen_skips),std::end(gen_skips),find_key)!=std::end(gen_skips))
                continue;
                if(const auto val=table->get_as<bool>(key_name);val){
                    out<<"\""<<table_name<<"|"<<key_name<<"\",\n";
                }
        }
    }
    out<<"};\n";

    out<<"final String[] INT_KEYS={\n";
    for(auto table_iter=toml->begin() ;table_iter!=toml->end();table_iter++){
        const std::string& table_name=table_iter->first;
        std::shared_ptr<cpptoml::table> table=table_iter->second->as_table();
        if(std::find(std::begin(gen_skips),std::end(gen_skips),table_name)!=std::end(gen_skips))
            continue;
        for(auto iter=table->begin();iter!=table->end();iter++){
            const std::string& key_name=iter->first;
            const std::string find_key=table_name+"|"+key_name;
            if(std::find(std::begin(gen_skips),std::end(gen_skips),find_key)!=std::end(gen_skips))
                continue;
            {
                auto find_iter=std::begin(gen_seekbar);
                find_iter=std::find_if(find_iter,std::end(gen_seekbar),[&find_key](const std::pair<std::string,range>& entry){
                    return entry.first==find_key;
                });
                if(find_iter!=std::end(gen_seekbar)){
                    out<<"\""<<table_name<<"|"<<key_name<<"\",\n";
                }
            }

        }
    }
    out<<"};\n";
    out<<"final String[] STRING_ARR_KEYS={\n";
    for(auto table_iter=toml->begin() ;table_iter!=toml->end();table_iter++){
        const std::string& table_name=table_iter->first;
        std::shared_ptr<cpptoml::table> table=table_iter->second->as_table();
        if(std::find(std::begin(gen_skips),std::end(gen_skips),table_name)!=std::end(gen_skips))
            continue;
            for(auto iter=table->begin();iter!=table->end();iter++){
                const std::string& key_name=iter->first;
                const std::string find_key=table_name+"|"+key_name;
                if(std::find(std::begin(gen_skips),std::end(gen_skips),find_key)!=std::end(gen_skips))
                    continue;
                {
                    auto find_iter=std::begin(gen_list);
                    find_iter=std::find_if(find_iter,std::end(gen_list),[&find_key](const std::pair<std::string,entries>& entry){
                        return entry.first==find_key;
                    });
                    if(find_iter!=std::end(gen_list)){
                        out<<"\""<<table_name<<"|"<<key_name<<"\",\n";
                    }
                }
            }
    }
    out<<"};\n";

#if 0
    //STRING XML
    out<<"\n\n\n\n";

    auto convert_to_name=[](const std::string& key){
        std::string result=key;
        replace(result.begin(),result.end(),'_',' ');
        result[0]=toupper(result[0]);
        for(int i=1;i<result.size();i++){
            if(result[i]==' '){
                if(i+1<result.size())
                    result[i+1]=toupper(result[i+1]);
            }
        }
        return result;
    };

    for(auto table_iter=toml->begin() ;table_iter!=toml->end();table_iter++){
        const std::string& table_name=table_iter->first;
        std::string table_name_l=table_name;
        std::transform(table_name_l.begin(),table_name_l.end(),table_name_l.begin(),::tolower);
        out<<"<string name=\"es_"<<table_name_l<<"\">"<<table_name<<"</string>\n";

        std::shared_ptr<cpptoml::table> table=table_iter->second->as_table();
        for(auto iter=table->begin();iter!=table->end();iter++){
            const std::string& key_name=iter->first;
            out<<"<string name=\"es_"<<table_name_l<<"_"<<key_name<<"\">"<<convert_to_name(key_name)<<"</string>\n";
        }
    }

#endif
#if 0
    //STRING ARRAY XML
    out<<"\n\n\n\n";

    for(auto table_iter=toml->begin() ;table_iter!=toml->end();table_iter++){
        const std::string& table_name=table_iter->first;
        std::string table_name_l=table_name;
        std::transform(table_name_l.begin(),table_name_l.end(),table_name_l.begin(),::tolower);

        std::shared_ptr<cpptoml::table> table=table_iter->second->as_table();
        for(auto iter=table->begin();iter!=table->end();iter++){
            const std::string& key_name=iter->first;
            auto find_iter=std::begin(gen_list);
            find_iter=std::find_if(find_iter,std::end(gen_list),[&table_name,&key_name](const std::pair<std::string,entries>& entry){
                return entry.first==table_name+"|"+key_name;
            });
            if(find_iter!=std::end(gen_list)){
                auto list=find_iter->second;
                if(std::find(std::begin(list[0]),std::end(list[0]),'@')!=std::end(list[0])){
                    out<<"<string-array name=\"es_arr_v_"<<table_name_l<<"_"<<key_name<<"\">\n";
                    for(auto entry:list){
                        int pos=entry.find("@");
                        std::string entry_str=entry.substr(pos+1);
                        out<<"<item>"<<entry_str<<"</item>\n";
                    }
                    out<<"</string-array>\n";
                    out<<"<string-array name=\"es_arr_"<<table_name_l<<"_"<<key_name<<"\">\n";
                    for(auto entry:list){
                        int pos=entry.find("@");
                        std::string entry_str=entry.substr(0,pos);
                        out<<"<item>"<<entry_str<<"</item>\n";
                    }
                    out<<"</string-array>\n";
                }
                else{
                    out<<"<string-array name=\"es_arr_"<<table_name_l<<"_"<<key_name<<"\">\n";
                    for(auto entry:list){
                        out<<"<item>"<<entry<<"</item>\n";
                    }
                    out<<"</string-array>\n";
                }
            }
        }
    }
#endif
    return env->NewStringUTF(out.str().c_str());
}
#endif
#undef SEEKBAR_PREF_TAG
#undef CHECKBOX_PREF_TAG
#undef LIST_PREF_TAG

//public  native void setup_uri_info_list_file(String path);
static void j_setup_uri_info_list_file(JNIEnv* env,jobject self,jstring jpath ){
    const char* path = env->GetStringUTFChars(jpath,NULL);
    g_uri_info_list_file_path=path;
    env->ReleaseStringUTFChars(jpath,path);
}

// Toggled by the "Display|show_debug_overlay" cvar (defined in presenter.cc).
DECLARE_bool(show_debug_overlay);

// Returns the formatted overlay text, or null when the overlay is disabled so
// the Java side can simply hide the view. Polled (~4 Hz) from the UI thread;
// reads lock-free atomics published by the GPU command-processor thread.
static jstring j_debug_overlay_text(JNIEnv* env, jobject thiz) {
    if (!cvars::show_debug_overlay) {
        return nullptr;
    }
    float instant_ms = 0.f, avg_ms = 0.f, fps = 0.f;
    xe::GetFrameStats(instant_ms, avg_ms, fps);
    uint32_t compiling = xe::shader_compiles_in_flight_count();

    char buf[256];
    int n = std::snprintf(buf, sizeof(buf), "FPS %.0f\n%.1f ms (avg %.1f ms)",
                          fps, instant_ms, avg_ms);
    if (compiling > 0 && n > 0 && n < (int)sizeof(buf)) {
        std::snprintf(buf + n, sizeof(buf) - n, "\ncompiling %u", compiling);
    }
    return env->NewStringUTF(buf);
}

// Last presented guest-frame interval in ms (the raw present-to-present delta,
// NOT the 120-frame average). 0 before the first present / right after a pause.
// Backed by the same lock-free atomic RecordGuestPresent() publishes; safe from
// any thread. Independent of the show_debug_overlay cvar (the Compose overlay
// owns its own visibility), unlike debug_overlay_text().
static jdouble j_last_frame_time_ms(JNIEnv* env, jobject thiz) {
    float instant_ms = 0.f, avg_ms = 0.f, fps = 0.f;
    xe::GetFrameStats(instant_ms, avg_ms, fps);
    return (jdouble)instant_ms;
}

// INSTANT fps = 1000/last-frame-ms (derived from frame_instant_ms), distinct from
// the smoothed average fps that debug_overlay_text() / frame_fps report. Returns 0
// when no valid frame has been timed yet (instant_ms <= 0).
static jdouble j_instant_fps(JNIEnv* env, jobject thiz) {
    float instant_ms = 0.f, avg_ms = 0.f, fps = 0.f;
    xe::GetFrameStats(instant_ms, avg_ms, fps);
    return instant_ms > 0.f ? (jdouble)(1000.0 / (double)instant_ms) : (jdouble)0.0;
}

// EFFECTIVE Display|show_debug_overlay, i.e. the live cvar AFTER any per-game config
// overlay. xenia's LoadGameConfig applies a game-specific override into this cvar
// during module load (on the detached boot thread), so the Compose overlay gate must
// POLL this post-boot instead of reading the global TOML file once at onCreate --
// otherwise a game-specific override (global off, per-game on) is never seen. Cheap
// read-only bool; safe from any thread.
static jboolean j_show_debug_overlay_enabled(JNIEnv* env, jobject thiz) {
    return cvars::show_debug_overlay ? JNI_TRUE : JNI_FALSE;
}

int register_xendroid_Emulator(JNIEnv* env){

    g_class_DocumentFile=env->FindClass("androidx/documentfile/provider/DocumentFile");
    g_class_DocumentFile=(jclass)env->NewGlobalRef(g_class_DocumentFile);

    g_class_Emulator = env->FindClass("xendroid/compose/Emulator");
    g_class_Emulator = (jclass)env->NewGlobalRef(g_class_Emulator);

    //public static int nc_open_uri_fd(Context ctx,String uri)
    mid_open_uri_fd = env->GetStaticMethodID(g_class_Emulator, "nc_open_uri_fd", "(Landroid/content/Context;Landroid/net/Uri;)I");

    static const JNINativeMethod methods[] = {
            { "setup_context", "(Landroid/content/Context;)V", (void *) j_setup_context },
            { "setup_document_file_tree", "(Landroidx/documentfile/provider/DocumentFile;)V", (void *) j_setup_document_file_tree },
            { "setup_launch_args", "([Ljava/lang/String;)V", (void *) j_setup_launch_args },
            { "meta_info_from_god_game", "(Landroid/content/Context;Ljava/lang/String;)Lxendroid/compose/Emulator$GameInfo;", (void *) j_meta_info_from_god_game },
            { "title_id_from_uri", "(Landroid/content/Context;Ljava/lang/String;I)Ljava/lang/String;", (void *) j_title_id_from_uri },
            { "meta_from_xex", "(Landroid/content/Context;Ljava/lang/String;I)Lxendroid/compose/Emulator$GameInfo;", (void *) j_meta_from_xex },
            { "setup_uri_info_list_file", "(Ljava/lang/String;)V", (void *) j_setup_uri_info_list_file },
            {"simple_device_info", "()Ljava/lang/String;", (void *) j_simple_device_info}
            ,{"generate_config_xml", "(Ljava/lang/String;)Ljava/lang/String;", (void *) generate_config_xml}
            ,{"debug_overlay_text", "()Ljava/lang/String;", (void *) j_debug_overlay_text}
            ,{"instant_fps", "()D", (void *) j_instant_fps}
            ,{"last_frame_time_ms", "()D", (void *) j_last_frame_time_ms}
            ,{"show_debug_overlay_enabled", "()Z", (void *) j_show_debug_overlay_enabled}
    };
    return env->RegisterNatives(g_class_Emulator,methods, sizeof(methods)/sizeof(methods[0]));
}
