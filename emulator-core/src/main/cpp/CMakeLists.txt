
# Native build for ax360e (arm64-v8a only). See ../../../BUILD.md for the
# toolchain requirements (CMake version is pinned via cmake.dir in
# local.properties and selected by the Gradle externalNativeBuild block).
cmake_minimum_required(VERSION 3.30.3)

project(ax360e LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Global compile options applied to every target in this build.
add_compile_options(-march=armv8-a)
add_compile_options(-stdlib=libc++)
add_compile_options(-fno-omit-frame-pointer)
add_compile_options(-funwind-tables)

# 16 KB max page size: required for Android 15+ on arm64.
add_link_options(-Wl,-z,max-page-size=16384)

add_library(hardware_ProcessorInfo SHARED hardware_ProcessorInfo.cpp)
target_link_libraries(hardware_ProcessorInfo PRIVATE vulkan)

add_library(emu SHARED)
set_target_properties(emu PROPERTIES OUTPUT_NAME "e")

# Hide the static dependencies' symbols; only the JNI entry points are exported.
target_link_options(emu PRIVATE -Wl,--exclude-libs,ALL)

target_sources(emu
        PRIVATE
        aarch64_disasm.cpp
        ax360e.cpp
        ax360e_emu.cpp
        emulator.cpp
        emulator_ax360e.cpp
        document_file.cpp
        xe_aaudio_audio_driver.cpp
        xe_aaudio_audio_system.cpp
        xe_android_hid.cpp
        xe_android_input_driver.cpp
        xe_android_util.cpp
        xe_opensles_audio_driver.cpp
        xe_opensles_audio_system.cpp
        xe_saf_disc_image_device.cpp
        xe_saf_disc_image_entry.cpp
        xe_saf_disc_image_file.cpp
        xe_saf_disc_archive_file.cpp
        xe_saf_disc_archive_device.cpp
        xe_saf_disc_archive_entry.cpp
        xe_saf_stfs_device.cpp
        xe_saf_stfs_entry.cpp
        xe_saf_stfs_file.cpp
        xe_saf_xex_device.cpp
        xe_saf_xex_entry.cpp
        xe_saf_xex_file.cpp
        xe_system_ax360e.cpp
        xenia-canary/src/xenia/app/emulator_window.cc
        xenia-canary/src/xenia/app/profile_dialogs.cc
        xenia-canary/src/xenia/app/console_settings_dialog.cc
        xenia-canary/src/xenia/ui/file_picker_android.cc
        xenia-canary/src/xenia/ui/surface_android.cc
        cpuinfo.cpp
        vkapi.cpp
        vkutil.cpp
)

add_subdirectory(xenia-canary EXCLUDE_FROM_ALL)

add_subdirectory(libadrenotools EXCLUDE_FROM_ALL)

target_include_directories(emu PRIVATE
        xenia-canary
        xenia-canary/src
        xenia-canary/third_party
        xenia-canary/..
)

target_link_libraries(emu
        PRIVATE
        xenia-apu
        xenia-apu-nop
        xenia-base
        xenia-core
        xenia-cpu
        xenia-cpu-backend-a64
        xenia-gpu
        xenia-gpu-null
        xenia-gpu-vulkan
        xenia-hid
        xenia-hid-nop
        xenia-kernel
        xenia-ui
        xenia-ui-vulkan
        xenia-vfs
        aes_128
        capstone
        fmt
        dxbc
        discord-rpc
        glslang-spirv
        imgui
        libavcodec
        libavutil
        libavformat
        mspack
        snappy
        xxhash
        android
        dl
        log
        vulkan
        OpenSLES
        aaudio
        z
        pugixml
        xenia-patcher
        adrenotools
)
