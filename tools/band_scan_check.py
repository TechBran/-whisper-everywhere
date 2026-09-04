#!/usr/bin/env python3
"""tools/band_scan_check.py - builds and runs app/src/test/cpp/band_scan_test.cpp on THIS machine.

band_scan.h is the one piece of libqnnasr.so's detect pass with no device in it, and this is the
only test a change to it can have short of a phone. No gradle, no device, no CMake:

  1. A hosted C++ compiler (``$CXX``, else clang++ or g++ on PATH) builds it the ordinary way.
  2. Otherwise - the shape of the owner's Windows machine, which has the NDK and nothing else - the
     NDK's own clang compiles it FREESTANDING for the host and the NDK's lld links it with no C
     runtime. The test is written for exactly that: no <cstdio>, exit code = the number of the
     first failing case.

Python rather than a shell script for the reason every other tool here is Python: this checkout
runs core.autocrlf=true with no .gitattributes, so a .sh comes out of the next checkout with CRLF
line endings and bash refuses it. Python does not care.

Exit code: 0 when every case holds; the failing case's number otherwise (read the test for the
case); 100 and up when the build itself could not be done here.

    python tools/band_scan_check.py
"""
from __future__ import annotations

import glob
import os
import platform
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src" / "test" / "cpp" / "band_scan_test.cpp"
# The same warning set the .so is built with (-Wall) plus -Wextra, and warnings are errors here:
# the header is 60 lines and a warning in it is a defect, not a style note.
FLAGS = ["-std=c++17", "-O1", "-Wall", "-Wextra", "-Werror"]


def report(rc: int) -> int:
    if rc == 0:
        print("band_scan_test: every case holds")
    else:
        print(f"band_scan_test: case {rc} FAILED (see app/src/test/cpp/band_scan_test.cpp)")
    return rc


def hosted(out: Path) -> int | None:
    """Route 1. None when there is no hosted compiler, so the caller falls through."""
    cxx = os.environ.get("CXX") or next((c for c in ("clang++", "g++") if shutil.which(c)), None)
    if not cxx or not shutil.which(cxx):
        return None
    print(f"band_scan_check: hosted build with {cxx}")
    exe = out / ("band_scan_test.exe" if os.name == "nt" else "band_scan_test")
    if subprocess.call([cxx, *FLAGS, str(SRC), "-o", str(exe)]) != 0:
        return 101
    return report(subprocess.call([str(exe)]))


def _version_key(path: str) -> list[int]:
    return [int(x) if x.isdigit() else 0 for x in os.path.basename(path).split(".")]


def ndk_bin() -> Path | None:
    """The NDK's host toolchain: $ANDROID_NDK_HOME, else the newest ndk/ under the SDK."""
    root = os.environ.get("ANDROID_NDK_HOME")
    if not root:
        sdk = (
            os.environ.get("ANDROID_HOME")
            or os.environ.get("ANDROID_SDK_ROOT")
            or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
        )
        found = sorted(glob.glob(os.path.join(sdk, "ndk", "*")), key=_version_key)
        root = found[-1] if found else None
    if not root:
        return None
    b = Path(root) / "toolchains" / "llvm" / "prebuilt" / "windows-x86_64" / "bin"
    return b if (b / "clang++.exe").is_file() and (b / "ld.lld.exe").is_file() else None


def freestanding(out: Path) -> int:
    """Route 2. Windows only: the entry point and the link flavour are COFF's."""
    if platform.system() != "Windows":
        print(
            "band_scan_check: no clang++/g++ on PATH, and the freestanding route is Windows-only; "
            "install a host compiler or set CXX"
        )
        return 102
    b = ndk_bin()
    if b is None:
        print("band_scan_check: no NDK clang found (set ANDROID_NDK_HOME)")
        return 103
    print(f"band_scan_check: freestanding host build with {b / 'clang++'}")
    obj = out / "band_scan_test.obj"
    exe = out / "band_scan_test.exe"
    compile_cmd = [
        str(b / "clang++.exe"), "--target=x86_64-pc-windows-msvc", *FLAGS,
        "-ffreestanding", "-fno-exceptions", "-fno-rtti", "-DBAND_SCAN_FREESTANDING",
        "-c", str(SRC), "-o", str(obj),
    ]
    if subprocess.call(compile_cmd) != 0:
        return 104
    link_cmd = [
        str(b / "ld.lld.exe"), "-flavor", "link", "-entry:mainCRTStartup", "-subsystem:console",
        "-nodefaultlib", f"-out:{exe}", str(obj),
    ]
    if subprocess.call(link_cmd) != 0:
        return 105
    return report(subprocess.call([str(exe)]))


def main() -> int:
    out = Path(os.environ.get("BAND_SCAN_OUT") or os.path.join(tempfile.gettempdir(), "band_scan_check"))
    out.mkdir(parents=True, exist_ok=True)
    rc = hosted(out)
    return rc if rc is not None else freestanding(out)


if __name__ == "__main__":
    sys.exit(main())
