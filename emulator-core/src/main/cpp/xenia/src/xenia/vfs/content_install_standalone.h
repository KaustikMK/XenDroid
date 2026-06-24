// SPDX-License-Identifier: WTFPL
#ifndef XENIA_VFS_CONTENT_INSTALL_STANDALONE_H_
#define XENIA_VFS_CONTENT_INSTALL_STANDALONE_H_

#include <atomic>
#include <cstdint>
#include <filesystem>
#include <string>
#include <vector>

#include "xenia/xbox.h"  // X_STATUS

namespace xe {
namespace vfs {

// Live progress for a running standalone install (file-static atomics back the
// JNI getter; concurrent reads are safe). current/total are payload bytes.
struct ContentProgress {
  std::atomic<uint64_t> current{0};
  std::atomic<uint64_t> total{0};
};

// Extract an STFS/SVOD package's inner file tree into the on-disk content tree,
// kernel-free (no Emulator / kernel_state). DLC (content_type 0x2,
// kMarketplaceContent) is forced under machine XUID 0000000000000000 to match
// the runtime resolver (content_manager.cc:108-112), NOT header.profile_id.
// Returns X_STATUS (0 == success). Blocking VFS walk -- caller MUST run off the
// main thread.
X_STATUS InstallContentPackageStandalone(
    const std::filesystem::path& src_path,
    const std::filesystem::path& content_root, ContentProgress& progress);

struct InstalledContentItem {
  std::string pkg_dir;
  std::string display_name;
  uint64_t size;
};

// Enumerate installed packages of content_type (DLC 0x2, Title Update 0xB0000)
// under XUID 0, mirroring the standalone installer's on-disk layout. Returns an
// empty vector (not an error) when the tree is absent.
std::vector<InstalledContentItem> ListInstalledContent(
    const std::filesystem::path& content_root, uint32_t title_id,
    uint32_t content_type);

// Remove one installed package's data dir + its .header sidecar. Returns
// X_STATUS (0 == success).
X_STATUS DeleteInstalledContent(const std::filesystem::path& content_root,
                                uint32_t title_id, uint32_t content_type,
                                const std::string& pkg_dir);

}  // namespace vfs
}  // namespace xe

#endif  // XENIA_VFS_CONTENT_INSTALL_STANDALONE_H_
