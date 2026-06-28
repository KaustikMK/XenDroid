// SPDX-License-Identifier: WTFPL
#include "xenia/vfs/content_install_standalone.h"

#include <array>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <system_error>
#include <vector>

#include "third_party/fmt/include/fmt/format.h"
#include "xenia/base/filesystem.h"
#include "xenia/base/logging.h"
#include "xenia/base/string.h"
#include "xenia/kernel/title_id_utils.h"
#include "xenia/kernel/xam/content_manager.h"
#include "xenia/vfs/devices/xcontent_container_device.h"
#include "xenia/vfs/virtual_file_system.h"
#include "xenia/xbox.h"

namespace xe {
namespace vfs {

X_STATUS InstallContentPackageStandalone(
    const std::filesystem::path& src_path,
    const std::filesystem::path& content_root, ContentProgress& progress) {
  progress.current.store(0);
  progress.total.store(0);

  // CreateContentDevice validates the CON/LIVE/PIRS magic and Initialize()
  // enforces the size floor (0x971A) + a kStfs/kSvod volume_type (F11). A bad
  // or unreadable container fails here -> X_STATUS_INVALID_PARAMETER.
  std::unique_ptr<XContentContainerDevice> device =
      XContentContainerDevice::CreateContentDevice("", src_path);
  if (!device || !device->Initialize()) {
    XELOGE("InstallContentPackageStandalone: bad/unreadable package: {}",
           src_path.string());
    return X_STATUS_INVALID_PARAMETER;
  }

  const uint32_t content_type = device->content_type();
  const uint64_t pkg_xuid = device->xuid();

  // Placement is content-type aware so xenia's runtime resolver / ProfileManager
  // find the package. Profiles land under kDashboardID/00010000 with the account
  // XUID as the leaf (ProfileManager scans for that path, no .header sidecar).
  // Everything else -- DLC, title updates, arcade/GoD -- lives under machine
  // XUID 0, which is where the per-game manager lists + deletes it.
  uint64_t xuid;
  uint32_t place_title_id;
  std::string leaf;
  bool write_header;
  if (content_type == static_cast<uint32_t>(XContentType::kProfile)) {
    if (pkg_xuid == 0 || pkg_xuid == ~uint64_t(0)) {
      XELOGE("InstallContentPackageStandalone: profile package has no XUID");
      return X_STATUS_INVALID_PARAMETER;
    }
    xuid = pkg_xuid;
    place_title_id = kernel::kDashboardID;
    leaf = fmt::format("{:016X}", pkg_xuid);
    write_header = false;
  } else {
    xuid = 0ull;
    place_title_id = device->title_id();
    leaf = src_path.filename().string();
    write_header = true;
  }

  // F6: data   = <root>/<XUID:016X>/<TitleID:08X>/<Type:08X>/<leaf>
  //     header = <root>/<XUID:016X>/<TitleID:08X>/Headers/<Type:08X>/<leaf>
  // ExtractContentHeader() appends ".header" to the leaf and writes into the
  // parent dir, so header_base carries the full leaf path (mirrors emulator.cc).
  const std::filesystem::path data_path =
      content_root / fmt::format("{:016X}/{:08X}/{:08X}/{}", xuid,
                                 place_title_id, content_type, leaf);
  const std::filesystem::path header_base =
      content_root / fmt::format("{:016X}/{:08X}/Headers/{:08X}/{}", xuid,
                                 place_title_id, content_type, leaf);

  // Disk-space guard (F11): need ~1.1x the payload, mirroring emulator.cc:1066.
  std::error_code ec;
  std::filesystem::create_directories(content_root, ec);
  const auto space = std::filesystem::space(content_root, ec);
  if (!ec && space.available < device->data_size() * 1.1f) {
    XELOGE("InstallContentPackageStandalone: insufficient disk space");
    return X_STATUS_DISK_FULL;
  }

  ec.clear();
  std::filesystem::create_directories(data_path, ec);
  if (ec) {
    XELOGE("InstallContentPackageStandalone: mkdir failed: {}", ec.message());
    return X_STATUS_ACCESS_DENIED;
  }

  progress.total.store(device->data_size());

  // .header sidecar (F9) then the inner file tree (F8). No kernel broadcast /
  // ReloadProfiles -- that is the whole point of the standalone variant (F14).
  if (write_header) {
    VirtualFileSystem::ExtractContentHeader(device.get(), header_base);
  }
  X_STATUS status = VirtualFileSystem::ExtractContentFiles(
      device.get(), data_path, progress.current);
  if (status == X_STATUS_SUCCESS) {
    progress.current.store(progress.total.load());
  } else {
    XELOGE("InstallContentPackageStandalone: extract failed 0x{:08X} for {}",
           status, src_path.string());
    std::error_code cleanup_ec;
    std::filesystem::remove_all(data_path, cleanup_ec);
  }
  return status;
}

namespace {

uint64_t DirectorySizeOnDisk(const std::filesystem::path& dir) {
  uint64_t total = 0;
  std::error_code ec;
  for (std::filesystem::recursive_directory_iterator it(dir, ec), end;
       it != end; it.increment(ec)) {
    if (ec) {
      break;
    }
    if (it->is_regular_file(ec)) {
      total += it->file_size(ec);
    }
  }
  return total;
}

bool ReadHeaderDisplayName(const std::filesystem::path& header_path,
                           std::string& out_name) {
  std::error_code ec;
  if (!std::filesystem::exists(header_path, ec)) {
    return false;
  }
  if (std::filesystem::file_size(header_path, ec) <
          sizeof(kernel::xam::XCONTENT_AGGREGATE_DATA) ||
      ec) {
    return false;
  }
  FILE* file = xe::filesystem::OpenFile(header_path, "rb");
  if (!file) {
    return false;
  }
  kernel::xam::XCONTENT_AGGREGATE_DATA data;
  size_t read = fread(&data, 1, sizeof(data), file);
  fclose(file);
  if (read != sizeof(data)) {
    return false;
  }
  out_name = xe::to_utf8(data.display_name());
  return true;
}

}  // namespace

std::vector<InstalledContentItem> ListInstalledContent(
    const std::filesystem::path& content_root, uint32_t title_id,
    uint32_t content_type) {
  std::vector<InstalledContentItem> items;

  const uint64_t xuid = 0ull;

  const std::filesystem::path data_root =
      content_root / fmt::format("{:016X}/{:08X}/{:08X}", xuid, title_id,
                                 content_type);
  const std::filesystem::path headers_root =
      content_root / fmt::format("{:016X}/{:08X}/Headers/{:08X}", xuid,
                                 title_id, content_type);

  std::error_code ec;
  if (!std::filesystem::is_directory(data_root, ec)) {
    return items;
  }

  for (std::filesystem::directory_iterator it(data_root, ec), end; it != end;
       it.increment(ec)) {
    if (ec) {
      break;
    }
    if (!it->is_directory(ec)) {
      continue;
    }
    const std::string pkg_dir = it->path().filename().string();
    const std::filesystem::path header_path =
        headers_root / (pkg_dir + ".header");

    InstalledContentItem item;
    item.pkg_dir = pkg_dir;
    std::string name;
    if (ReadHeaderDisplayName(header_path, name) && !name.empty()) {
      item.display_name = name;
      item.size = DirectorySizeOnDisk(it->path());
    } else {
      item.display_name = pkg_dir;
      item.size = DirectorySizeOnDisk(it->path());
    }
    items.push_back(std::move(item));
  }

  return items;
}

X_STATUS DeleteInstalledContent(const std::filesystem::path& content_root,
                                uint32_t title_id, uint32_t content_type,
                                const std::string& pkg_dir) {
  if (pkg_dir.empty() || pkg_dir == "." || pkg_dir == ".." ||
      pkg_dir.find('/') != std::string::npos ||
      pkg_dir.find('\\') != std::string::npos) {
    return X_STATUS_INVALID_PARAMETER;
  }

  const uint64_t xuid = 0ull;

  const std::filesystem::path data_path =
      content_root / fmt::format("{:016X}/{:08X}/{:08X}/{}", xuid, title_id,
                                 content_type, pkg_dir);
  const std::filesystem::path header_path =
      content_root / fmt::format("{:016X}/{:08X}/Headers/{:08X}/{}.header",
                                 xuid, title_id, content_type, pkg_dir);

  std::error_code ec;
  if (!std::filesystem::exists(data_path, ec)) {
    return X_STATUS_OBJECT_NAME_NOT_FOUND;
  }

  std::filesystem::remove_all(data_path, ec);
  if (ec) {
    return X_STATUS_ACCESS_DENIED;
  }

  std::error_code header_ec;
  std::filesystem::remove(header_path, header_ec);

  return X_STATUS_SUCCESS;
}

}  // namespace vfs
}  // namespace xe
