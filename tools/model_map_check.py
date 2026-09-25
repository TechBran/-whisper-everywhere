#!/usr/bin/env python3
"""tools/model_map_check.py - builds and runs app/src/test/cpp/model_map_test.cpp, model_map.h's host check.

model_map.h is liblitertasr.so's model mapping (4.16.1, the Tab S10+ ship sheet's F2): the read-only mmap
the two model files are handed to LiteRtCreateModelFromBuffer through, its two madvise phases, the mincore
count, the munmap, and the two fallbacks to LiteRT's file loader. The test runs THAT header with the REAL
system calls over real files, so it needs a Linux kernel - and it gets one on either machine:

  1. On Linux: a hosted compiler (``$CXX``, else clang++ or g++ on PATH) builds it and it runs in place.
  2. On Windows - the owner's machine, which has the NDK and WSL and no host compiler - the NDK's own clang
     builds it as a STATIC x86_64 Android executable (bionic's headers and libc, the ones the product
     compiles against: MADV_COLD comes from the same sysroot) and WSL runs it. A static bionic binary makes
     plain Linux system calls, so the mmap, madvise and mincore it exercises are a real Linux kernel's.

No gradle, no device, no CMake. Python for the reason every tool here is: this checkout runs
core.autocrlf=true, and a .sh would come out of the next checkout with CRLF line endings.

Exit code: 0 when every case holds; the first failing case's number otherwise (the test prints every
case); 100 and up when the build or the run could not be done here.

    python tools/model_map_check.py
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
SRC = ROOT / "app" / "src" / "test" / "cpp" / "model_map_test.cpp"
# band_scan_check.py's warning set: -Wall as the .so, plus -Wextra, and warnings are errors here.
FLAGS = ["-std=c++17", "-O1", "-Wall", "-Wextra", "-Werror"]
# The API level only picks the sysroot's headers; the static libc.a is the same for every level.
NDK_TARGET = "--target=x86_64-linux-android26"


def report(rc: int) -> int:
    if rc == 0:
        print("model_map_check: every case holds")
    elif rc < 100:
        print(f"model_map_check: case {rc} FAILED (see app/src/test/cpp/model_map_test.cpp)")
    else:
        print(f"model_map_check: the test did not run to completion here (exit {rc})")
    return rc


def hosted(out: Path) -> int | None:
    """Route 1, Linux only. None when this is not Linux or there is no compiler, so the caller falls through."""
    if platform.system() != "Linux":
        return None
    cxx = os.environ.get("CXX") or next((c for c in ("clang++", "g++") if shutil.which(c)), None)
    if not cxx or not shutil.which(cxx):
        return None
    print(f"model_map_check: hosted build with {cxx}", flush=True)
    exe = out / "model_map_test"
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
    return b if (b / "clang++.exe").is_file() else None


def wsl_path(p: Path) -> str:
    """C:\\x\\y -> /mnt/c/x/y, WSL's default automount of a Windows drive."""
    s = str(p.resolve())
    return "/mnt/" + s[0].lower() + s[2:].replace("\\", "/")


def through_wsl(out: Path) -> int:
    """Route 2, Windows only: the NDK's clang builds a static bionic executable; WSL's kernel runs it."""
    if platform.system() != "Windows":
        print("model_map_check: not Linux and not Windows - no route here (install a compiler on a Linux host)")
        return 102
    b = ndk_bin()
    if b is None:
        print("model_map_check: no NDK clang found (set ANDROID_NDK_HOME)")
        return 103
    wsl = shutil.which("wsl.exe") or shutil.which("wsl")
    if not wsl:
        print("model_map_check: no WSL on this machine - the test needs a Linux kernel to run on")
        return 104
    exe = out / "model_map_test"
    print(f"model_map_check: static x86_64 bionic build with {b / 'clang++.exe'}, run inside WSL", flush=True)
    build = [str(b / "clang++.exe"), NDK_TARGET, *FLAGS, "-static", "-static-libstdc++", str(SRC), "-o", str(exe)]
    if subprocess.call(build) != 0:
        return 105
    # Copied into WSL's own /tmp and run from there. The test's temp files go to /var/tmp - WSL's root ext4,
    # a disk-backed filesystem like the tablet's (WSL's /tmp is a tmpfs, whose pages are swap-backed shmem) -
    # and never to the Windows drive (/mnt/c is a 9p mount with its own mapping semantics).
    run = (
        f"cp '{wsl_path(exe)}' /tmp/we_model_map_test && chmod +x /tmp/we_model_map_test && "
        "/tmp/we_model_map_test; rc=$?; rm -f /tmp/we_model_map_test; exit $rc"
    )
    return report(subprocess.call([wsl, "-e", "sh", "-c", run]))


def main() -> int:
    out = Path(os.environ.get("MODEL_MAP_OUT") or os.path.join(tempfile.gettempdir(), "model_map_check"))
    out.mkdir(parents=True, exist_ok=True)
    rc = hosted(out)
    return rc if rc is not None else through_wsl(out)


if __name__ == "__main__":
    sys.exit(main())
