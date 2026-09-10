#!/usr/bin/env python3
"""Turn the pulled result JSONs (drive.py --out, default ~/.androidbuild/probe-logs) into markdown tables.

  python summarize.py                      # every <tag>.json, grouped by mode/model/accel
  python summarize.py e5_turbo_cpu_211_1   # only the named tags

Columns are the ones docs/measurements/2026-09-09-tab-apu-probe.md carries: artefact, backend, warm n,
mean / median / min / max ms (run+read), cold run ms, create ms, RSS / PSS after create and after warm,
battery temperature (tenths of a degree C from BatteryManager.EXTRA_TEMPERATURE) and PowerManager
thermal status at start and after warm. `lm` runs print the LiteRT-LM BenchmarkInfo columns instead.
"""
import glob
import json
import os
import sys


def f1(x):
    return "-" if x is None else ("%.1f" % x)


def mem(o, k):
    m = o.get(k)
    if not m:
        return "-"
    return "%d / %d" % (m.get("rss_kb", -1) // 1024, m.get("pss_kb", -1) // 1024)


def therm(o, k):
    m = o.get(k)
    if not m:
        return "-"
    return "%.1f C / %d" % (m.get("batt_temp_tenths_c", -1) / 10.0, m.get("thermal_status", -1))


def litert_row(o):
    w = o.get("warm")
    if not isinstance(w, dict):   # a failed run still carries the requested warm COUNT here
        w = {}
    art = os.path.basename(o.get("model") or "-")
    err = o.get("error")
    err_s = ("`" + err.replace("\n", " ").replace("|", "/")[:160] + "`") if err else ""
    return "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
        o.get("tag"), art, o.get("accel"), o.get("threads") if o.get("accel") == "cpu" else "-",
        w.get("n", 0), f1(w.get("mean_ms")), f1(w.get("median_ms")), f1(w.get("min_ms")), f1(w.get("max_ms")),
        f1(o.get("cold_run_ms")), f1(o.get("create_ms")),
        mem(o, "mem_after_create"), mem(o, "mem_after_warm"),
        therm(o, "mem_start"), therm(o, "mem_after_warm"),
        ("ok" if o.get("ok") else "FAIL " + err_s),
    )


LITERT_HDR = (
    "| tag | artefact | backend | thr | warm n | mean ms | median ms | min ms | max ms | cold run ms | create ms | "
    "RSS/PSS MB after create | RSS/PSS MB after warm | batt C / thermal at start | after warm | result |\n"
    "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
)


def lm_row(o):
    b = o.get("benchmark") or {}
    art = os.path.basename(o.get("model") or "-")
    err = o.get("error")
    err_s = ("`" + err.replace("\n", " ").replace("|", "/")[:200] + "`") if err else ""
    return "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
        o.get("tag"), art, o.get("accel"), f1(o.get("initialize_ms")),
        f1(o.get("stream_ttft_ms")), f1(o.get("stream_total_ms")),
        b.get("prefill_tokens", "-"), f1(b.get("prefill_tps")), b.get("decode_tokens", "-"), f1(b.get("decode_tps")),
        mem(o, "mem_after_init"), mem(o, "mem_after_generate"), therm(o, "mem_after_generate"),
        ("ok" if o.get("ok") else "FAIL " + err_s),
    )


LM_HDR = (
    "| tag | artefact | backend | initialize ms | TTFT ms | total ms | prefill tok | prefill tok/s | decode tok | "
    "decode tok/s | RSS/PSS MB after init | after generate | batt C / thermal after | result |\n"
    "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
)


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    out = os.path.join(os.path.expanduser("~"), ".androidbuild", "probe-logs")
    tags = sys.argv[1:]
    files = sorted(glob.glob(os.path.join(out, "*.json")))
    rows = []
    for f in files:
        tag = os.path.basename(f)[:-5]
        if tags and tag not in tags:
            continue
        try:
            o = json.load(open(f, encoding="utf-8"))
        except Exception as e:
            print("skip", f, e, file=sys.stderr)
            continue
        rows.append(o)
    lit = [o for o in rows if o.get("mode") == "litert"]
    lms = [o for o in rows if o.get("mode") == "lm"]
    if lit:
        print(LITERT_HDR)
        for o in lit:
            print(litert_row(o))
        print()
    if lms:
        print(LM_HDR)
        for o in lms:
            print(lm_row(o))
        print()
    for o in lit:
        fp = o.get("fingerprint_warm")
        if fp:
            print("- `%s` fingerprint: n=%d nan=%d mean=%.5f mean_abs=%.5f min=%.4f max=%.4f head=%s" % (
                o["tag"], fp["count"], fp["nan_or_inf"], fp["mean"], fp["mean_abs"], fp["min"], fp["max"],
                ",".join("%.4f" % v for v in fp["head"][:4])))


if __name__ == "__main__":
    main()
