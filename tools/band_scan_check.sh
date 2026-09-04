#!/usr/bin/env bash
# tools/band_scan_check.sh - builds and runs app/src/test/cpp/band_scan_test.cpp on THIS machine.
#
# band_scan.h is the one piece of libqnnasr.so's detect pass with no device in it, and this is the
# only test a change to it can have short of a phone. No gradle, no device, no CMake:
#
#   1. A hosted C++ compiler on PATH (or $CXX) builds it the ordinary way.
#   2. Otherwise - the shape of the owner's Windows machine, which has the NDK and nothing else -
#      the NDK's own clang compiles it FREESTANDING for the host and the NDK's lld links it with no
#      C runtime. The test is written for exactly that: no <cstdio>, exit code = the number of the
#      first failing case.
#
# Exit code: 0 when every case holds; the failing case's number otherwise (read the test for the
# case); 100 and up when the build itself could not be done here.
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/app/src/test/cpp/band_scan_test.cpp"
OUT="${BAND_SCAN_OUT:-${TMPDIR:-/tmp}/band_scan_check}"
mkdir -p "$OUT" || exit 100

report() {
    if [ "$1" -eq 0 ]; then
        echo "band_scan_test: every case holds"
    else
        echo "band_scan_test: case $1 FAILED (see app/src/test/cpp/band_scan_test.cpp)"
    fi
    return "$1"
}

# 1. Hosted.
HOSTED="${CXX:-}"
if [ -z "$HOSTED" ]; then
    for c in clang++ g++; do
        if command -v "$c" >/dev/null 2>&1; then HOSTED="$c"; break; fi
    done
fi
if [ -n "$HOSTED" ] && command -v "$HOSTED" >/dev/null 2>&1; then
    echo "band_scan_check: hosted build with $HOSTED"
    "$HOSTED" -std=c++17 -O1 -Wall -Wextra -Werror "$SRC" -o "$OUT/band_scan_test" || exit 101
    "$OUT/band_scan_test"
    report $?
    exit $?
fi

# 2. The NDK's clang, freestanding. Windows only: the entry point and the link flavour are COFF's.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ;;
    *)
        echo "band_scan_check: no clang++/g++ on PATH, and the freestanding route is Windows-only;" \
             "install a host compiler or set CXX"
        exit 102
        ;;
esac
NDK_ROOT="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK_ROOT" ]; then
    LOCAL="$(cygpath -m "${LOCALAPPDATA:-}" 2>/dev/null || echo "${LOCALAPPDATA:-}")"
    SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCAL/Android/Sdk}}"
    NDK_ROOT="$(ls -d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -n 1)"
fi
BIN="${NDK_ROOT%/}/toolchains/llvm/prebuilt/windows-x86_64/bin"
if [ ! -x "$BIN/clang++.exe" ] || [ ! -x "$BIN/ld.lld.exe" ]; then
    echo "band_scan_check: no NDK clang at $BIN (set ANDROID_NDK_HOME)"
    exit 103
fi
echo "band_scan_check: freestanding host build with $BIN/clang++"
"$BIN/clang++.exe" --target=x86_64-pc-windows-msvc -std=c++17 -O1 -Wall -Wextra -Werror \
    -ffreestanding -fno-exceptions -fno-rtti -DBAND_SCAN_FREESTANDING \
    -c "$SRC" -o "$OUT/band_scan_test.obj" || exit 104
"$BIN/ld.lld.exe" -flavor link -entry:mainCRTStartup -subsystem:console -nodefaultlib \
    "-out:$OUT/band_scan_test.exe" "$OUT/band_scan_test.obj" || exit 105
"$OUT/band_scan_test.exe"
report $?
