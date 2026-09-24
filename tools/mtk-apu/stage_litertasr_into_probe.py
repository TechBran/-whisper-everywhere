#!/usr/bin/env python3
"""Stage the app's liblitertasr.so into the litertlm-probe for mode=litertasr (P1b task 9, the device gate).

Copies into tools/probes/litertlm-probe/app/src/main/jniLibs/arm64-v8a/ (gitignored):

  liblitertasr.so    from the app's BUILT APK (lib/arm64-v8a/) - the exact bytes the app packages
  libc++_shared.so   from the same APK: the app builds with ANDROID_STL=c++_shared, so liblitertasr.so NEEDS it,
                     and nothing else in the probe ships one
  libLiteRt.so       2.1.1 from the litert AAR - the Gradle cache's copy, else downloaded from Google Maven - and
                     refused unless its sha256 is the pinned 6ddc1b3d... (design §2.6). The probe's litert AAR
                     dependency packages the same file; the probe's build takes the first of the two (pickFirsts),
                     which are one file by this check.

The dispatch (libLiteRtDispatch_MediaTek.so) is fetch_mediatek_runtime.py's; the probe stages it into
files/litert_dispatch/ itself at run time. This script only warns when it is missing.

Build the app first (a BUILD, never an install):
  gradlew.bat :app:assembleDebug -PlocalBuildRoot=C:/Users/bastr/.androidbuild/WhisperEverywhere-spike
then:
  python tools/mtk-apu/stage_litertasr_into_probe.py --build-root C:/Users/bastr/.androidbuild/WhisperEverywhere-spike
  (cd tools/probes/litertlm-probe && gradlew.bat :app:assembleDebug)
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import io
import os
import sys
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
PROBE_JNI = os.path.join(ROOT, "tools", "probes", "litertlm-probe", "app", "src", "main", "jniLibs", "arm64-v8a")
HOME = os.path.expanduser("~")
DEFAULT_ROOTS = [os.path.join(HOME, ".androidbuild", "WhisperEverywhere-spike"),
                 os.path.join(HOME, ".androidbuild", "WhisperEverywhere")]

LITERT_SO_SHA256 = "6ddc1b3df38f3f0e039558c023f3bd9ec2f7bec55b67cfd6be4131130481a5d5"
LITERT_SO_BYTES = 5104832
LITERT_AAR_URL = "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/2.1.1/litert-2.1.1.aar"
LITERT_AAR_GLOB = os.path.join(HOME, ".gradle", "caches", "modules-2", "files-2.1", "com.google.ai.edge.litert",
                               "litert", "2.1.1", "*", "litert-2.1.1.aar")
FROM_APK = ["lib/arm64-v8a/liblitertasr.so", "lib/arm64-v8a/libc++_shared.so"]


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def find_apk(args) -> str:
    if args.apk:
        return args.apk
    roots = [args.build_root] if args.build_root else DEFAULT_ROOTS
    for r in roots:
        p = os.path.join(r, "app", "outputs", "apk", "debug", "app-debug.apk")
        if os.path.exists(p):
            return p
    sys.exit("no app-debug.apk under " + ", ".join(roots) + " - build :app:assembleDebug first (never install it)")


def litert_so(args) -> bytes:
    aars = [args.aar] if args.aar else sorted(glob.glob(LITERT_AAR_GLOB))
    data = None
    for a in aars:
        if a and os.path.exists(a):
            print("litert AAR:", a)
            data = open(a, "rb").read()
            break
    if data is None:
        print("litert AAR not in the Gradle cache; downloading", LITERT_AAR_URL)
        data = urllib.request.urlopen(LITERT_AAR_URL).read()
    so = zipfile.ZipFile(io.BytesIO(data)).read("jni/arm64-v8a/libLiteRt.so")
    got = sha(so)
    if got != LITERT_SO_SHA256 or len(so) != LITERT_SO_BYTES:
        sys.exit(f"libLiteRt.so is {len(so)} B sha256 {got}; the tier is pinned to {LITERT_SO_BYTES} B {LITERT_SO_SHA256}")
    return so


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", help="the app's built APK (default: <build root>/app/outputs/apk/debug/app-debug.apk)")
    ap.add_argument("--build-root", help="the app's local build root (-PlocalBuildRoot); default: the spike's, then main's")
    ap.add_argument("--aar", help="a litert-2.1.1.aar to take libLiteRt.so from (default: the Gradle cache)")
    a = ap.parse_args()

    apk = find_apk(a)
    print("app APK:", apk, os.path.getsize(apk), "B")
    os.makedirs(PROBE_JNI, exist_ok=True)
    z = zipfile.ZipFile(apk)
    names = set(z.namelist())
    for member in FROM_APK:
        if member not in names:
            sys.exit(f"{member} is not in {apk} - was liblitertasr.so skipped by CMake (the header guard)?")
        b = z.read(member)
        dst = os.path.join(PROBE_JNI, os.path.basename(member))
        open(dst, "wb").write(b)
        print(f"staged {os.path.basename(member):22s} {len(b):>9,} B sha256 {sha(b)}  (from the APK)")
    so = litert_so(a)
    dst = os.path.join(PROBE_JNI, "libLiteRt.so")
    open(dst, "wb").write(so)
    print(f"staged {'libLiteRt.so':22s} {len(so):>9,} B sha256 {sha(so)}  (pinned 2.1.1)")
    disp = os.path.join(PROBE_JNI, "libLiteRtDispatch_MediaTek.so")
    if os.path.exists(disp):
        print(f"present {'libLiteRtDispatch_MediaTek.so':21s} {os.path.getsize(disp):>9,} B sha256 {sha(open(disp, 'rb').read())}")
    else:
        print("WARNING: libLiteRtDispatch_MediaTek.so is not in the probe's jniLibs - run "
              "tools/probes/litertlm-probe/fetch_mediatek_runtime.py, or push it to files/litert_dispatch/ yourself")
    print("now build the probe: cd tools/probes/litertlm-probe && gradlew.bat :app:assembleDebug")
    return 0


if __name__ == "__main__":
    sys.exit(main())
