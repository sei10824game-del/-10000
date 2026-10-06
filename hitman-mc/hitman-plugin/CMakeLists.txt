cmake_minimum_required(VERSION 3.15)
project(McCamBridge CXX)

set(CMAKE_CXX_STANDARD 23)
add_compile_definitions(WIN32_LEAN_AND_MEAN NOMINMAX)

# Path to the unzipped DevPkg-ZHMModSDK.zip (v4.1.1)
set(ZHMMODSDK_DEVPKG "" CACHE PATH "unzipped DevPkg-ZHMModSDK")
add_subdirectory(${ZHMMODSDK_DEVPKG} ZHMModSDK)

add_library(McCamBridge SHARED Src/McCamBridge.cpp Src/McCamBridge.h)
target_include_directories(McCamBridge PRIVATE Src)
target_link_libraries(McCamBridge PRIVATE ZHMModSDK)
