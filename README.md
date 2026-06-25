<p align="center">
       <img height="256px" src="https://github.com/rfandango/XenDroid/blob/main/XenDroid_foreground.png"/>
    </a>
</p>

<h1 align="center">XenDroid - Android Xbox 360 Emulator</h1>

## History
XenDroid was initially forked form xa360e, which was based off [Xenia Canary](https://github.com/xenia-canary/xenia-canary).
However, a complete rebase was made on [Xenia Edge](https://github.com/has207/xenia-edge) for better Vulkan backend, 
XEX swap behavior, occlusion queries and XMA Audio decoder, with a Kotlin + JNI layer.

We are looking foward to keep the project updated alongside the Edge fork,
and keep the code compatible with Xenia licenses.

## Be aware of scams
XenDroid is a free project, and will NEVER ask for money. If you paid for this, then you got scammed.
The apk is available under the releases section, along with the distributed source code.

## Status (to update)

Build (Android ARM64-v8a): [![CI](https://github.com/has207/xenia-edge/actions/workflows/CI.yml/badge.svg?branch=edge)](https://github.com/has207/xenia-edge/actions/workflows/CI.yml) [![Codacy Badge](https://app.codacy.com/project/badge/Grade/cd506034fd8148309a45034925648499)](https://app.codacy.com/gh/has207/xenia-edge/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade)

Releases (to update)
--------
[Latest](https://github.com/has207/xenia-edge/releases/latest)


## Building (to update)

See [BUILD.md](BUILD.md) for build instructions (Linux and Windows). In short:
`./gradlew :app-compose:assembleDebug` (Linux) or `gradlew.bat :app-compose:assembleDebug` (Windows).

LICENSE:

Please check the LICENSE file under the appropriate file header and directory for detailed information.

## Device Requirements
- Snapdragon SoC, GEN 2 or higher
- Adreno GPU 7xx or higher.

## Drivers list
We recommend using these custom Vulkan drivers to achieve a better experience:
- [Adreno 8xx](https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/v32) Turnip drivers
  - Might work with 7xx series too, needs testing.
- [Adreno 7xx](https://github.com/StevenMXZ/Adreno-Tools-Drivers/releases/tag/v26.2.0-R6) Turnip drivers

You can check your device specs with [CPU X](https://play.google.com/store/apps/details?id=com.abs.cpu_z_advance&hl=it) to get the matching driver.