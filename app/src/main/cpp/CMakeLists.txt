cmake_minimum_required(VERSION 3.22.1)
project(HyperCeilerNative LANGUAGES CXX)

# Layout: nativehook/ holds the target-agnostic hook infrastructure; targets/
# holds the per-feature entry points and resolvers that bind it to one host.
# The root include dir keeps `nativehook/...` includes valid from any target.
# The library name is the LSPosed native-hook contract: it must match the
# `libHyperCeilerNative.so` entry of META-INF/xposed/native_init.list and the
# `System.loadLibrary` name in NativeHomeHooksOS4.
add_library(HyperCeilerNative SHARED targets/home/hyperceiler_home.cpp)

option(HYPERCEILER_DOCK_NATIVE_MOTION "Dynamically resolve OS4 native recents animation" ON)
if(HYPERCEILER_DOCK_NATIVE_MOTION)
    enable_language(ASM)
    target_sources(HyperCeilerNative PRIVATE
        targets/home/dock_native_hooks.cpp
        targets/home/dock_native_motion.cpp
        targets/home/dock_native_motion_arm64.S)
    target_compile_definitions(HyperCeilerNative PRIVATE HYPERCEILER_DOCK_NATIVE_MOTION=1)
endif()

target_include_directories(HyperCeilerNative PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_features(HyperCeilerNative PRIVATE cxx_std_20)
target_compile_options(HyperCeilerNative PRIVATE
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
)

target_link_libraries(HyperCeilerNative PRIVATE android binder_ndk log)
