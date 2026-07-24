// SPDX-License-Identifier: WTFPL
#include "xenia/game_quirks.h"

#include <string>
#include <variant>

#include "xenia/base/cvar.h"
#include "xenia/base/logging.h"

namespace xe {
namespace game_quirks {

using QuirkValue = std::variant<bool, int64_t, double, const char*>;

struct Quirk {
  uint32_t title_id;
  const char* cvar;
  QuirkValue value;
  const char* note;
};

static const Quirk kQuirks[] = {
    // Ace Combat 6: audio handshake deadlocks without strict event hand-off.
    {0x4E4D07D1, "auto_reset_event_handoff", true, "audio-handshake deadlock"},
};

// Same path/priority as a per-game config file.
static bool ApplyOne(const Quirk& quirk) {
  if (!cvar::ConfigVars) {
    return false;
  }
  auto it = cvar::ConfigVars->find(quirk.cvar);
  if (it == cvar::ConfigVars->end()) {
    XELOGW("Game quirk for {:08X} references unknown cvar '{}'", quirk.title_id,
           quirk.cvar);
    return false;
  }
  auto* config_var = static_cast<cvar::IConfigVar*>(it->second);
  std::visit(
      [config_var](auto&& value) {
        using V = std::decay_t<decltype(value)>;
        if constexpr (std::is_same_v<V, const char*>) {
          toml::value<std::string> node{std::string(value)};
          config_var->LoadGameConfigValue(&node);
        } else {
          toml::value<V> node{value};
          config_var->LoadGameConfigValue(&node);
        }
      },
      quirk.value);
  return true;
}

size_t Apply(uint32_t title_id) {
  size_t applied = 0;
  for (const auto& quirk : kQuirks) {
    if (quirk.title_id != title_id) {
      continue;
    }
    if (ApplyOne(quirk)) {
      XELOGI("Game quirk for {:08X}: {} ({})", title_id, quirk.cvar,
             quirk.note);
      ++applied;
    }
  }
  return applied;
}

}  // namespace game_quirks
}  // namespace xe
