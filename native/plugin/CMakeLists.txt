cmake_minimum_required(VERSION 3.21)
project(SkyrimAYN LANGUAGES CXX RC)

# SkyrimAYN.dll: SKSE64 plugin for the Skyrim AYN bottom-screen companion.
# Built against SKSE 2.3.1's own source (game definitions for Skyrim SE 1.7.104).
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")   # static CRT: no VC++ redist needed

set(SKSE_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../skse64/skse64_2_03_01/src/x/skse64-2.3.1" CACHE PATH "SKSE 2.3.1 source")
set(RUNTIME_VERSION_PACKED 0x01070680)            # 1.7.104

find_package(common REQUIRED CONFIG)              # ianpatt/common, installed to deps/extern
add_subdirectory("${SKSE_SRC}/skse64_common" skse64_common)

# The game-definition sources a plugin needs (classic plugin setup: compile them in).
file(GLOB SKSE_GAME_SOURCES
    "${SKSE_SRC}/skse64/Game*.cpp"
    "${SKSE_SRC}/skse64/Ni*.cpp"
    "${SKSE_SRC}/skse64/BSModelDB.cpp"
    "${SKSE_SRC}/skse64/HashUtil.cpp"
    "${SKSE_SRC}/skse64/ScaleformAPI.cpp"
    "${SKSE_SRC}/skse64/ScaleformTypes.cpp"
    "${SKSE_SRC}/skse64/ScaleformValue.cpp"
    "${SKSE_SRC}/skse64/ScaleformMovie.cpp"
    "${SKSE_SRC}/skse64/PapyrusArgs.cpp"
    "${SKSE_SRC}/skse64/PapyrusInterfaces.cpp"
    "${SKSE_SRC}/skse64/PapyrusValue.cpp"
    "${SKSE_SRC}/skse64/PapyrusVM.cpp"
    "${SKSE_SRC}/skse64/Colors.cpp"
)

add_library(SkyrimAYN SHARED src/SkyrimAYN.cpp ${SKSE_GAME_SOURCES})
target_include_directories(SkyrimAYN PRIVATE "${SKSE_SRC}")
target_compile_definitions(SkyrimAYN PRIVATE RUNTIME RUNTIME_VERSION=${RUNTIME_VERSION_PACKED} NOMINMAX)
target_compile_options(SkyrimAYN PRIVATE /MP /W3 /permissive /D_CRT_SECURE_NO_WARNINGS)
target_link_libraries(SkyrimAYN PRIVATE skse64::skse64_common xse::common shell32 ole32 version)
