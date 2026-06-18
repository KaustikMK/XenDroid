<p align="center">
       <img height="256px" src="https://github.com/rfandango/XenDroid/blob/rebrand/XenDroid_foreground.png"/>
    </a>
</p>

<h1 align="center">XenDroid - Android Xbox 360 Emulator</h1>

XenDroid is based off xa360e, which was forked from [Xenia Canary](https://github.com/xenia-canary/xenia-canary). 
We are looking foward to migrate to Edge for better Vulkan backend, xex swap behavior and XMA Audio decoder. 

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
- Adreno GPU, starting from 7xx series at least.
- [Freedreno Mesa Turnip](https://github.com/s1mptom/freedreno_turnip-CI/releases/tag/mesa_v26.1-eden-fix-latest-crash-fix) drivers to use on XenDroid