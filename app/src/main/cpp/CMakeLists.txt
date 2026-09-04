cmake_minimum_required(VERSION 3.22.1)
project(hyperceiler_home LANGUAGES CXX)

add_library(hyperceiler_home SHARED hyperceiler_home.cpp)

target_compile_features(hyperceiler_home PRIVATE cxx_std_20)
target_compile_options(hyperceiler_home PRIVATE
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
)

target_link_libraries(hyperceiler_home PRIVATE android log)
