cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Release-grade native code: -O3 + NEON is on by default for arm64-v8a.
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -O3")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3")

# 16 KB page alignment for Android 15+ / Play Store, applied to EVERY shared lib built here
# (libggml*.so, libwhisper.so, libwhisper_jni.so) - not just the JNI target.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

# Keep whisper.cpp lean for a mobile CPU build.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

# Vendored whisper.cpp submodule (builds ggml as a subtarget, exposes the `whisper` target).
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

add_library(whisper_jni SHARED whisper_jni.cpp)

target_link_libraries(whisper_jni
    whisper
    android
    log)
# Note: 16 KB page alignment is applied globally above via CMAKE_SHARED_LINKER_FLAGS,
# covering libggml/libwhisper/libwhisper_jni - no per-target link option needed.
