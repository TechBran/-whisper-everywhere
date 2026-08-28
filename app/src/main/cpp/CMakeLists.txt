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

# ---------------------------------------------------------------------------------------------
# libqnnasr.so - the 4.0 NPU tier's QNN seam (Hexagon HTP via the QAIRT C API).
#
# A SECOND add_library in THIS file rather than a second cmake {} block: AGP accepts exactly one
# `path = file(...)` per externalNativeBuild.cmake block, so every native target this app ships has
# to be declared here. It shares nothing with whisper_jni - no headers, no link edge, no source -
# and the two live side by side in the same APK.
set(QNN_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/include/QNN)

# GUARDED ON HEADER PRESENCE. The QNN headers are Qualcomm-proprietary, gitignored, and fetched by
# tools/fetch_qnn_headers.py (wired as a dependency of configureCMake*/buildCMake* in
# app/build.gradle.kts). If that fetch could not run - no network, portal moved, fresh clone with
# neither - the NPU target is skipped with a loud message instead of failing the build. The CPU and
# GPU tiers are 100% of shipped transcription today and must not be brickable by a vendor download
# portal. The consequence is explicit and handled: no libqnnasr.so in the APK, so
# QnnAsrNative's System.loadLibrary throws, so NpuGate reports the tier unavailable (Q6).
if(EXISTS ${QNN_INCLUDE_DIR}/QnnInterface.h AND EXISTS ${QNN_INCLUDE_DIR}/QnnSdkBuildId.h)

    add_library(qnnasr SHARED qnn_asr.cpp)

    # We compile against the QNN headers but never LINK against the QNN libraries: libQnnHtp.so and
    # libQnnSystem.so are dlopen()ed at runtime and every entry point is reached through the
    # provider function-pointer tables, exactly as Qualcomm's own samples do. That keeps the .so set
    # swappable (a different QAIRT build drops in without relinking) and turns a missing library
    # into a readable message instead of a load-time crash.
    #
    # BOTH directories are needed, not just the parent: the SDK's own headers include each other as
    # "System/QnnSystemCommon.h" and "HTP/QnnHtpDevice.h", i.e. relative to the QNN/ directory.
    target_include_directories(qnnasr PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${QNN_INCLUDE_DIR})

    # No -O flag here on purpose: the file-global -O3 above already applies, and a per-target -O2
    # (as the spike carried) would come LATER on the command line and silently downgrade it.
    target_compile_options(qnnasr PRIVATE
        -Wall
        -Wno-unused-parameter)

    # log for __android_log_print, dl for dlopen/dlsym. Nothing else - deliberately.
    target_link_libraries(qnnasr
        log
        dl)
    # 16 KB page alignment comes free from the global CMAKE_SHARED_LINKER_FLAGS above.

else()

    message(WARNING
        "QNN headers not found at ${QNN_INCLUDE_DIR} - SKIPPING libqnnasr.so. The NPU tier will be "
        "unavailable at runtime; the CPU and GPU tiers are unaffected. To restore it run: "
        "C:/Users/bastr/AppData/Local/Programs/Python/Python313/python.exe tools/fetch_qnn_headers.py")

endif()
