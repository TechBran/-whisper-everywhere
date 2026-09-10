#!/usr/bin/env python3
"""Sample the Tab's GPU or CPU counters from the PC while a probe run executes.

This is the sampler behind sections 3.1 and 3.2 of
`docs/measurements/2026-09-09-tab-apu-probe.md`. Run it in a second shell, started just before
`drive.py`, so its window brackets the run's warm phase:

  python tools/sample_util.py --mode gpu --duration 60 --out ~/.androidbuild/probe-logs/util_<tag>.txt
  python tools/sample_util.py --mode cpu --duration 40 --out ~/.androidbuild/probe-logs/cpustat_<tag>.txt

--mode gpu  reads `/sys/kernel/gpu/gpu_busy` and `/sys/kernel/gpu/gpu_clock` (world-readable on this
            tablet) and writes  `HH:MM:SS gpu_busy=<n>% gpu_clock=<hz>`.  Summarise with --summarize.
--mode cpu  reads the aggregate `cpu` line of `/proc/stat` and writes the PREVIOUS and the CURRENT
            reading on one line, `HH:MM:SS cpu <10 fields> | cpu <10 fields>`, which is the format
            `cpustat.py` reduces (busy = 1 - d(idle+iowait)/d(total)).

One `adb shell` per sample, so the round trip — not --interval — sets the real cadence: the recorded
files come out at ~0.30 s for gpu (--interval 0.25) and ~0.52 s for cpu (--interval 0.5). Timestamps
are the PC's local clock; on this setup that tracked the device's logcat clock to within a second.
Timestamps aside, nothing is read from or written to the device: `cat` on two sysfs nodes.

ADB is pinned to ONE serial; nothing here ever touches another device.
"""
import argparse
import os
import re
import subprocess
import sys
import time

GPU_BUSY = "/sys/kernel/gpu/gpu_busy"
GPU_CLOCK = "/sys/kernel/gpu/gpu_clock"


def adb_shell(serial, cmd):
    r = subprocess.run(
        ["adb", "-s", serial, "shell", cmd],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.stdout


def norm_out(p):
    """Git Bash on Windows hands over MSYS-style absolute paths: '/c/Users/...' means 'C:/Users/...'.
    Written literally, os.makedirs() would silently create C:\\c\\Users\\... instead."""
    p = os.path.expanduser(p)
    if os.name == "nt":
        m = re.match(r"^/([A-Za-z])/(.*)$", p)
        if m:
            p = m.group(1).upper() + ":/" + m.group(2)
    return os.path.abspath(p)


def sample_gpu(serial):
    out = adb_shell(serial, "cat %s %s" % (GPU_BUSY, GPU_CLOCK))
    vals = [l.strip() for l in out.splitlines() if l.strip()]
    if len(vals) < 2:
        return None
    busy, clock = vals[0].rstrip("%"), vals[1]
    return "gpu_busy=%s%% gpu_clock=%s" % (busy, clock)


def sample_cpu(serial, state):
    out = adb_shell(serial, "cat /proc/stat")
    cur = next((l.rstrip() for l in out.splitlines() if l.startswith("cpu ")), None)
    if cur is None:
        return None
    prev, state["prev"] = state.get("prev"), cur
    return None if prev is None else "%s | %s" % (prev, cur)


def summarize_gpu(paths):
    """The section 3.1 columns: samples, samples at gpu_clock 0, the clock values seen, max busy."""
    for path in paths:
        rows = []
        for line in open(path, encoding="utf-8", errors="replace"):
            m = re.search(r"gpu_busy=(\d+)%\s+gpu_clock=(\d+)", line)
            if m:
                rows.append((int(m.group(1)), int(m.group(2))))
        if not rows:
            print(path, "no samples")
            continue
        clocks = {}
        for _, c in rows:
            clocks[c] = clocks.get(c, 0) + 1
        zero = clocks.get(0, 0)
        seen = ", ".join("%d x%d" % (c, n) for c, n in sorted(clocks.items(), reverse=True) if c)
        print("%s: n=%d  gpu_clock==0 in %d  max gpu_busy=%d%%  non-zero clocks: %s" % (
            os.path.basename(path), len(rows), zero, max(b for b, _ in rows), seen or "none"))


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="192.168.1.161:44483")
    ap.add_argument("--mode", choices=("gpu", "cpu"), default="gpu")
    ap.add_argument("--interval", type=float, default=None, help="gpu: 0.25 default; cpu: 0.5")
    ap.add_argument("--duration", type=float, default=60.0, help="seconds to sample")
    ap.add_argument("--out", default=None, help="file to write (default: stdout)")
    ap.add_argument("--summarize", nargs="+", metavar="FILE",
                    help="reduce recorded --mode gpu files instead of sampling")
    a = ap.parse_args()

    if a.summarize:
        summarize_gpu([norm_out(p) for p in a.summarize])
        return 0

    interval = a.interval if a.interval is not None else (0.25 if a.mode == "gpu" else 0.5)
    sink = sys.stdout
    if a.out:
        out = norm_out(a.out)
        os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
        sink = open(out, "w", encoding="utf-8")
        print("sampling %s every ~%.2fs for %.0fs -> %s" % (a.mode, interval, a.duration, out))

    state, n, t0 = {}, 0, time.time()
    try:
        while time.time() - t0 < a.duration:
            line = sample_gpu(a.serial) if a.mode == "gpu" else sample_cpu(a.serial, state)
            if line:
                sink.write("%s %s\n" % (time.strftime("%H:%M:%S"), line))
                sink.flush()
                n += 1
            time.sleep(interval)
    except KeyboardInterrupt:
        pass
    finally:
        if a.out:
            sink.close()
    elapsed = time.time() - t0
    print("%d samples in %.1fs (%.2fs/sample)" % (n, elapsed, elapsed / n if n else 0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
