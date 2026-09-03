# SPDX-License-Identifier: GPL-3.0-only
#
# Builds the platform independent GuiLite overlay code as a static
# library named "camera_overlay_core". Consumed by android/app's CMake
# build nothing here is Android specific.

cmake_minimum_required(VERSION 3.10)
project(camera_overlay_core CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# GuiLite is vendored directly at third_party/GuiLite. Only need the single amalgamated GuiLite.h header.
set(GUILITE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/GuiLite")

if(NOT EXISTS "${GUILITE_DIR}/GuiLite.h")
    message(FATAL_ERROR
        "GuiLite.h not found at ${GUILITE_DIR}/GuiLite.h -- "
        "this should be vendored directly in the repo, so if it's "
        "missing something went wrong with your Download. "
        "Find GuiLite in there repo or redownload")
endif()

add_library(camera_overlay_core STATIC
    camera_overlay.cpp
)

target_include_directories(camera_overlay_core PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${GUILITE_DIR}
)
