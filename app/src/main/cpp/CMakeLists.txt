cmake_minimum_required(VERSION 3.22.1)
project(mgba_android LANGUAGES C CXX)

set(DISABLE_FRONTENDS ON CACHE BOOL "" FORCE)
set(BUILD_QT OFF CACHE BOOL "" FORCE)
set(BUILD_SDL OFF CACHE BOOL "" FORCE)
set(BUILD_LIBRETRO OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_GL OFF CACHE BOOL "" FORCE)
set(BUILD_GLES2 OFF CACHE BOOL "" FORCE)
set(BUILD_GLES3 OFF CACHE BOOL "" FORCE)
set(BUILD_EGL OFF CACHE BOOL "" FORCE)
set(BUILD_GBM OFF CACHE BOOL "" FORCE)
set(BUILD_FFMPEG OFF CACHE BOOL "" FORCE)
set(BUILD_PNG OFF CACHE BOOL "" FORCE)
set(BUILD_ZIP ON CACHE BOOL "" FORCE)
set(ENABLE_SCRIPTING OFF CACHE BOOL "" FORCE)
set(ENABLE_DEBUGGERS OFF CACHE BOOL "" FORCE)
set(ENABLE_GDB_STUB OFF CACHE BOOL "" FORCE)
set(BUILD_TEST OFF CACHE BOOL "" FORCE)
set(BUILD_SUITE OFF CACHE BOOL "" FORCE)
set(BUILD_PERF OFF CACHE BOOL "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(M_CORE_GB ON CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/mgba ${CMAKE_CURRENT_BINARY_DIR}/mgba)

add_library(mgba_android SHARED native_bridge.cpp)
target_include_directories(mgba_android PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/mgba/include
    ${CMAKE_CURRENT_BINARY_DIR}/mgba/include
)
target_link_libraries(mgba_android PRIVATE mgba android log)

# mCore has fields guarded by build definitions such as ENABLE_DIRECTORIES.
# Use the core target's actual definitions; flags.h does not capture all of them.
target_compile_definitions(mgba_android PRIVATE
    $<TARGET_PROPERTY:mgba,COMPILE_DEFINITIONS>
)
