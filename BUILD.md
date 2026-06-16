# Building ax360e

ax360e is an Android (arm64-v8a only) build of a xenia-canary fork with an
ARM64 JIT backend. The Gradle build produces two modules:

- `:emulator-core` — the native build (CMake -> `libe.so` +
  `libhardware_ProcessorInfo.so`) plus the JNI-bound Java classes. Built once,
  consumed transitively.
- `:app-compose` — the Kotlin/Jetpack-Compose frontend (`applicationId
  aenu.ax360e.compose`).

## Toolchain

| Tool        | Version            | Notes |
|-------------|--------------------|-------|
| JDK         | 21                 | `JAVA_HOME` must point at a JDK 21. |
| Android SDK | platform 35        | `sdk.dir` in `local.properties`. |
| Android NDK | 27.2.12479018      | Pinned in `emulator-core/build.gradle` (`ndkVersion`). |
| CMake       | 3.30.3             | Pinned in `emulator-core/build.gradle` (`cmake { version '3.30.3' }`) and selected via `cmake.dir`. Uses Ninja. |
| Gradle      | 8.11.1             | Provided by the wrapper (`./gradlew`); the distribution is SHA-256 pinned in `gradle/wrapper/gradle-wrapper.properties`. |
| Python      | 3.x                | Runs on the **build host**; drives the shader compile step. |
| SPIR-V tools| glslang + SPIRV-Tools | `glslangValidator`, `spirv-opt`, `spirv-dis` on the build host (or under `$VULKAN_SDK/bin`). |

Install the SDK pieces once:

```
sdkmanager "ndk;27.2.12479018" "cmake;3.30.3"
```

Install the host shader toolchain (Debian/Ubuntu example):

```
sudo apt install python3 glslang-tools spirv-tools
```

## Generated shaders

The GPU/UI Vulkan shaders live as `.xesl`/`.xesli` GLSL sources under
`emulator-core/src/main/cpp/xenia-canary/src/xenia/{gpu,ui}/shaders/`. The CMake
build compiles them to SPIR-V C headers (`bytecode/vulkan_spirv/*.h`) as a
pre-build step, via `tools/build/compile_shader_spirv.py`
(`glslangValidator` -> `spirv-opt` -> `spirv-dis` -> `.h`). This is wired
through `xe_shader_rules_spirv()` in `cmake/XeniaHelpers.cmake` and runs
automatically on every build — the first build generates the headers.

The generated `bytecode/` headers are **not** checked in (they are
`.gitignore`d); they are regenerated whenever a shader source changes. The host
shader tools above must therefore be installed for a fresh clone to build. Set
`VULKAN_SDK` to use a specific Vulkan SDK; otherwise the tools are found on
`PATH`.

## First-time setup

1. Copy the example config and edit the paths:

   ```
   cp local.properties.example local.properties
   ```

   Set `sdk.dir` and `cmake.dir` to your machine's paths.

2. **CMake shadowing gotcha.** The native build must use the SDK's CMake
   3.30.3. A different `cmake` earlier on `PATH` (a pip-installed
   `~/.local/bin/cmake`, or a distro `/usr/bin/cmake` 4.x) can shadow it and
   break the configure step. Pointing `cmake.dir` at
   `$SDK/cmake/3.30.3` makes Gradle use the right one regardless of `PATH`.

## Building

```
# Debug APK (Compose frontend):
./gradlew :app-compose:assembleDebug

# Clean native + app, then build:
./gradlew clean :app-compose:assembleDebug

# Install to a connected device:
./gradlew :app-compose:installDebug
```

The APK lands in `app-compose/build/outputs/apk/debug/`.

## Corporate TLS interception (Zscaler etc.)

If your network re-signs TLS, Gradle's downloads fail certificate validation.
Point Gradle at the system truststore via `GRADLE_OPTS`:

```
export GRADLE_OPTS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
                    -Djavax.net.ssl.trustStorePassword=changeit"
```
