cmake_minimum_required(VERSION 3.22.1)
project(hyperceiler_home LANGUAGES CXX)

add_library(hyperceiler_home SHARED hyperceiler_home.cpp)

option(HYPERCEILER_DOCK_NATIVE_PROBE "Build the opt-in, bounded OS4 scale diagnostic" OFF)
option(HYPERCEILER_DOCK_NATIVE_MOTION "Follow pinned OS4 native recents animation" ON)
if(HYPERCEILER_DOCK_NATIVE_PROBE OR HYPERCEILER_DOCK_NATIVE_MOTION)
    enable_language(ASM)
    target_sources(hyperceiler_home PRIVATE dock_native_probe.cpp dock_native_probe_arm64.S)
    target_compile_definitions(hyperceiler_home PRIVATE HYPERCEILER_DOCK_NATIVE_OBSERVER=1)
endif()
if(HYPERCEILER_DOCK_NATIVE_PROBE)
    target_compile_definitions(hyperceiler_home PRIVATE HYPERCEILER_DOCK_NATIVE_PROBE=1)
endif()
if(HYPERCEILER_DOCK_NATIVE_MOTION)
    target_sources(hyperceiler_home PRIVATE dock_native_motion.cpp dock_native_motion_arm64.S)
    target_compile_definitions(hyperceiler_home PRIVATE HYPERCEILER_DOCK_NATIVE_MOTION=1)
endif()

target_compile_features(hyperceiler_home PRIVATE cxx_std_20)
target_compile_options(hyperceiler_home PRIVATE
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
)

target_link_libraries(hyperceiler_home PRIVATE android log)
