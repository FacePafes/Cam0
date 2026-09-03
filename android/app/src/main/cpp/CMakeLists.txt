# SPDX-License-Identifier: GPL-3.0-only

cmake_minimum_required(VERSION 3.22.1)
project(camera_overlay_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pull in the platform independent GuiLite overlay (core/CMakeLists.txt),
# which in turn expects GuiLite itself as a submodule under
# third_party/GuiLite at the repo root.
add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../core
    ${CMAKE_CURRENT_BINARY_DIR}/camera_overlay_core
)

add_library(camera_overlay_jni SHARED
    jni_bridge.cpp
    jni_callback.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(camera_overlay_jni
    camera_overlay_core
    ${log-lib}
    ${jnigraphics-lib}
)
