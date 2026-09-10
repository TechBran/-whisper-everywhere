#!/usr/bin/env python3
"""Drive one probe run from the PC and collect everything it logged.

  python drive.py --tag e5_turbo_cpu_1 mode=litert model=/data/user/0/com.whispereverywhere.probe/files/whisper_large_v3_turbo_30s_i8.tflite accel=cpu threads=4 warm=20

Every run: force-stop the probe (so the run is a fresh process = a true cold start), clear logcat,
`am start` the activity with the key=value pairs as extras (ints for threads/warm/maxtokens, booleans for
nofallback, strings otherwise), wait for `PROBE ... DONE|tag=<tag>`, then save the FULL logcat and a
filtered view (PROBE + LiteRT/LiteRT-LM/neuron/apusys/linker/crash lines) under --out, and pull the
result JSON the app wrote to files/results/<tag>.json (via run-as; the package is debuggable).
ADB is pinned to ONE serial; nothing here ever touches another device.
"""
import argparse
import os
import re
import subprocess
import sys
import time

PKG = "com.whispereverywhere.probe"
ACT = PKG + "/.MainActivity"
INT_KEYS = {"threads", "warm", "maxtokens"}
BOOL_KEYS = {"nofallback"}
FILTER = re.compile(
    r"PROBE|LiteRt|litert|LITERT|tflite|TfLite|TFLite|neuron|Neuron|NEURON|apusys|APUSYS|apuware|mtk|MTK|"
    r"MediaTek|Mediatek|dispatch|Dispatch|xnnpack|XNNPACK|OpenCL|opencl|clGl|Mali|mali|linker|AndroidRuntime|"
    r"DEBUG|libc|SIGSEGV|SIGABRT|Fatal|FATAL|probe|npu|NPU|JIT|restoreFrom|CompilerPlugin|nnapi|NNAPI"
)
HILITE = re.compile(
    r"PROBE|litert|LiteRt|LITERT|tflite|neuron|Neuron|NEURON|apusys|Dispatch_|dispatch_|JIT|restoreFrom|"
    r"CompilerPlugin|xnnpack|XNNPACK|OpenCL|libc    :|DEBUG   :|AndroidRuntime|SIGSEGV|SIGABRT|nativeloader|"
    r" linker|Fatal signal|mali|Mali"
)


def adb(serial, *a):
    return subprocess.run(["adb", "-s", serial, *a], capture_output=True, text=True, encoding="utf-8", errors="replace")


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="192.168.1.161:44483")
    ap.add_argument("--tag", required=True)
    ap.add_argument("--timeout", type=int, default=1200)
    ap.add_argument("--out", default=os.path.join(os.path.expanduser("~"), ".androidbuild", "probe-logs"))
    ap.add_argument("kv", nargs="*")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    extras = ["--es", "tag", a.tag]
    for kv in a.kv:
        k, v = kv.split("=", 1)
        if k in INT_KEYS:
            extras += ["--ei", k, v]
        elif k in BOOL_KEYS:
            extras += ["--ez", k, v]
        else:
            extras += ["--es", k, v]
    adb(a.serial, "shell", "am", "force-stop", PKG)
    time.sleep(1.0)
    adb(a.serial, "logcat", "-c")
    t0 = time.time()
    r = adb(a.serial, "shell", "am", "start", "-W", "-n", ACT, *extras)
    print(r.stdout.strip())
    if r.stderr.strip():
        print(r.stderr.strip())
    done = None
    while time.time() - t0 < a.timeout:
        time.sleep(2.0)
        lc = adb(a.serial, "logcat", "-d", "-s", "PROBE:*").stdout
        m = re.search(r"DONE\|tag=" + re.escape(a.tag) + r"\|ok=(true|false)", lc)
        if m:
            done = m.group(1)
            break
        pid = adb(a.serial, "shell", "pidof", PKG).stdout.strip()
        if not pid:
            done = "crashed"  # process gone without a DONE line: a native crash
            time.sleep(3.0)
            break
    full = adb(a.serial, "logcat", "-d", "-v", "threadtime").stdout
    open(os.path.join(a.out, a.tag + ".full.log"), "w", encoding="utf-8").write(full)
    filt = "\n".join(l for l in full.splitlines() if FILTER.search(l))
    open(os.path.join(a.out, a.tag + ".filtered.log"), "w", encoding="utf-8").write(filt)
    js = adb(a.serial, "shell", "run-as", PKG, "cat", "files/results/" + a.tag + ".json").stdout
    if js.strip().startswith("{"):
        open(os.path.join(a.out, a.tag + ".json"), "w", encoding="utf-8").write(js)
    print("=== status:", done, "elapsed %.1fs" % (time.time() - t0))
    for l in filt.splitlines():
        if HILITE.search(l):
            print(l)
    adb(a.serial, "shell", "am", "force-stop", PKG)
    return 0 if done == "true" else 1


if __name__ == "__main__":
    sys.exit(main())
