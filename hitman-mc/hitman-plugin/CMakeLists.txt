cmake_minimum_required(VERSION 3.15)
project(McCamBridge CXX)

set(CMAKE_CXX_STANDARD 23)
add_compile_definitions(WIN32_LEAN_AND_MEAN NOMINMAX)
add_compile_options(/utf-8)

# Path to the unzipped DevPkg-ZHMModSDK.zip (v4.1.1)
set(ZHMMODSDK_DEVPKG "" CACHE PATH "unzipped DevPkg-ZHMModSDK")
# Path to a clone of https://github.com/microsoft/DirectX-Headers (needs include/directx/d3d12.h)
set(DIRECTX_HEADERS "" CACHE PATH "DirectX-Headers clone")
add_subdirectory(${ZHMMODSDK_DEVPKG} ZHMModSDK)

add_library(McCamBridge SHARED Src/McCamBridge.cpp Src/McCamBridge.h)
target_include_directories(McCamBridge PRIVATE Src ${DIRECTX_HEADERS}/include)
target_link_libraries(McCamBridge PRIVATE ZHMModSDK)
