# Game-specific settings (per-game configs)

Some titles need configuration overrides to run correctly. Keep these out of the
global config — most of them cost performance or change behavior that other
titles don't want — and use the emulator's per-game config mechanism instead.

## How per-game configs work

- Path: `<storage_root>/config/<TITLEID>.config.toml`
  (on Android: `/sdcard/Android/data/<package>/files/ax360e/config/`)
- Loaded automatically when a title launches, *after* the global
  `xenia-canary.config.toml`, so its values override the global ones
  (`src/xenia/emulator.cc` → `config::LoadGameConfig`,
  `src/xenia/config.cc` → `ReadGameConfig`).
- Keys are looked up as `category.name`, so the section header is **required**.
  A bare `clear_memory_page_state = true` without `[GPU]` is silently ignored.
- The title ID is the 8-hex-digit uppercase ID shown in `xe.log` at launch
  (`Title ID: XXXXXXXX`); it is also the title's folder name under `content/`.

Example (adb):

```sh
adb shell 'mkdir -p /sdcard/Android/data/aenu.ax360e.free/files/ax360e/config'
adb push 544307D5.config.toml /sdcard/Android/data/aenu.ax360e.free/files/ax360e/config/
```

## Known title requirements

### Ninja Gaiden 2 — `544307D5`

`config/544307D5.config.toml`:

```toml
[GPU]
clear_memory_page_state = true
depth_float24_convert_in_pixel_shader = true
```

- `clear_memory_page_state = true` — fixes missing/broken **character models**.
  This is the documented workaround for Team Ninja titles (the engine reads
  back GPU-written memory; the page-state refresh makes those writes visible).
  Costs a per-frame page-state pass, so don't enable it globally.
- `depth_float24_convert_in_pixel_shader = true` — prevents **GPU hangs →
  device-loss crashes** on Adreno/kgsl (observed on Adreno 830 + Turnip:
  `kgsl ... Fault id:2`, "gpu fault threshold exceeded" in dmesg, then
  `VK_ERROR_DEVICE_LOST` at the swap submission). The default float24↔float32
  host-depth transfer machinery faults the GPU; converting depth in the draw
  pixel shaders bypasses it. Verified 2026-06-12: with both flags NG2 is fully
  playable (the separate job-system freeze was a code fix — FIFO semaphore,
  commit 5b4113562).

### Fable II — `4D5307F1`

No special configuration needed (as of 2026-06-13). The title previously
black-screened at boot and then crashed at several later stages — all of it
was emulator bugs, fixed in the six-commit series ending at the
`movi(VReg2D)` JIT fix: lost `NtReadFile` completion APCs on POSIX hosts
(VFS manifest read never finished), a pause-path deadlock that swallowed
guest crash dumps, a physical-allocator alignment check testing the wrong
address space (every 32KB-aligned E0 allocation failed), a thread-exit
lifetime race (FORTIFY abort on the game's short-lived bank loader threads),
and an a64 JIT vector-constant encoding bug (uncaught Xbyak error on select
masks). Each was general — no Fable-specific hacks shipped.

Recommended global setting: `mount_cache = true` (the game can use the
cache partition for its streaming installer; upstream desktop default since
2024-08, Android fork defaults `false`).

Known remaining issues match the upstream compat report
(xenia-canary/game-compatibility#74): missing ground geometry in places and
dialogue audio cutoffs.

> **Caveat: `mount_*` cvars cannot be set per-game.** Cache/scratch/MU devices
> are registered during emulator setup (`ax360e_emu.cpp`, right after
> `Emulator::Setup`), which happens *before* the title launch loads
> `config/<TITLEID>.config.toml` — a per-game override is read too late.

## Device/driver-level notes (global config, not per-game)

These belong in the device's global config because they are properties of the
GPU/driver, not of a title:

- `vulkan_mid_frame_submission_draws` — split long frames into multiple queue
  submissions every N draws. On kgsl-based Adreno devices this prevents
  GPU-idle bubbles on heavy frames (e.g. `1300` on AYN Odin3 / Adreno 830
  locks Forza titles from 20 to 30 fps). `0` (default) disables splitting.
- `depth_float24_convert_in_pixel_shader = true` can also be set globally on
  devices whose driver faults in the float32-depth transfer path regardless of
  title (seen on Retroid Pocket 5 / Adreno 650).
- `vulkan_dynamic_constant_buffers` must stay `false` (the default) on
  Qualcomm proprietary drivers — pixel shaders read zeros through
  dynamic-offset uniform descriptors (a650/a740/a830 blobs). Turnip handles it
  and may opt in for the small binding-update win.
