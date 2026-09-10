#!/usr/bin/env python3
"""Rung-3 tables from the `mode=sherpa` result JSONs drive.py pulled (default ~/.androidbuild/probe-logs).

  python sherpa_summary.py                 # every sherpa <tag>.json
  python sherpa_summary.py r3_jfk_rt_t2    # only the named tags
  python sherpa_summary.py --words r3_...  # also the per-word partial-latency rows of run 0

Prints, per tag: the per-run table (final text, WER through WerMath, canary verdict, partial count, retractions,
partial latency p50/p95/max on the wall clock and on the audio clock, decode-burst p50/p95/max, compute RTF, wall
RTF, late chunks), then the per-tag line (load ms, warm-up first decode, RSS/PSS at before-load / after-load /
after-warm / end / after-release, thermal at start and end, the aggregate RTF and lag distribution). A duration
(thermal) run also prints first-minute vs last-minute and the per-minute snapshots. The kill-line arithmetic in
docs/measurements/2026-09-10-tab-sherpa-rung3.md is these numbers, copied.
"""
import glob
import json
import os
import sys


def f(x, d=3):
    return "-" if x is None else ("%.*f" % (d, x))


def q(o, k, d=3):
    if not o or k not in o:
        return "-"
    return f(o[k], d)


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


RUN_HDR = (
    "| tag | run | clip | final | WER (edits/ref) | canary | partials | retract | lag wall p50 / p95 / max s | "
    "lag audio p50 / p95 s | burst p50 / p95 / max ms | RTF compute | RTF wall | late chunks (max ms) |\n"
    "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
)


def run_row(tag, r):
    wer = "-" if "wer" not in r else "%.3f (%d/%d)" % (r["wer"], r.get("wer_edits", -1), r.get("wer_ref_tokens", -1))
    can = "-"
    if "canary_passes" in r:
        can = ("exact" if r.get("canary_exact") else "not exact") + " / " + ("pass" if r["canary_passes"] else "FAIL")
    lw = r.get("lag_wall_s") or {}
    la = r.get("lag_audio_s") or {}
    b = r.get("decode_burst_ms") or {}
    return "| %s | %s | %s | `%s` | %s | %s | %d | %d | %s / %s / %s | %s / %s | %s / %s / %s | %s | %s | %d (%s) |" % (
        tag, r.get("index"), r.get("clip"), (r.get("final") or "").replace("|", "/").replace("`", "'"), wer, can,
        r.get("n_partials", 0), r.get("n_retractions", 0),
        q(lw, "p50"), q(lw, "p95"), q(lw, "max"), q(la, "p50"), q(la, "p95"),
        q(b, "p50", 1), q(b, "p95", 1), q(b, "max", 1),
        f(r.get("rtf_compute"), 4), f(r.get("rtf_wall"), 4), r.get("late_chunks", 0), f(r.get("max_late_ms"), 1))


def tag_line(o):
    v = o.get("versions") or {}
    a = o.get("aggregate") or {}
    w = o.get("warmup") or {}
    rt = a.get("rtf_compute") or {}
    rl = a.get("rtf_compute_last_half") or {}
    lw = a.get("lag_wall_s") or {}
    la = a.get("lag_audio_s") or {}
    b = a.get("decode_burst_ms") or {}
    return (
        "- `%s`: sherpa %s / ORT %s, threads %s, provider `%s`, pace %s, pad %s ms, load %s, sme=%s; load %s ms, warm-up first decode %s ms; "
        "RSS/PSS MB before load %s, after load %s, after warm %s, end %s, after release %s; thermal start %s, end %s; "
        "runs %s in %s s; RTF compute mean %s max %s (last half mean %s); lag wall p50 %s p95 %s max %s (n=%s); lag audio p50 %s p95 %s; "
        "burst p50 %s p95 %s max %s ms; retractions %s; %s" % (
            o.get("tag"), v.get("sherpa_version"), v.get("onnxruntime_version"), o.get("threads"), o.get("provider"), o.get("pace"),
            o.get("pad_ms"), o.get("load_threads"), o.get("cpu_sme"), f(o.get("load_ms"), 0), f(w.get("first_decode_ms"), 1),
            mem(o, "mem_before_load"), mem(o, "mem_after_load"), mem(o, "mem_after_warm"), mem(o, "mem_end"), mem(o, "mem_after_release"),
            therm(o, "mem_before_load"), therm(o, "mem_end"),
            o.get("n_runs"), f(o.get("runs_wall_s"), 1), q(rt, "mean", 4), q(rt, "max", 4), q(rl, "mean", 4),
            q(lw, "p50"), q(lw, "p95"), q(lw, "max"), lw.get("n", 0), q(la, "p50"), q(la, "p95"),
            q(b, "p50", 1), q(b, "p95", 1), q(b, "max", 1), a.get("retractions"),
            "ok" if o.get("ok") else "FAIL `" + (o.get("error") or "")[:200].replace("|", "/") + "`"))


def minute_stats(runs):
    if not runs:
        return "-"
    rtf = [r["rtf_compute"] for r in runs]
    lw = sorted(x for r in runs for x in (r.get("lag_wall_all") or []))
    bs = sorted(x for r in runs for x in (r.get("decode_burst_all") or []))

    def pc(s, p):
        if not s:
            return None
        i = max(0, min(len(s) - 1, int(-(-p * len(s) // 1)) - 1))
        return s[i]
    return "runs %d, RTF mean %.4f max %.4f, lag wall p50 %s p95 %s max %s, burst p95 %s max %s ms, late chunks %d" % (
        len(runs), sum(rtf) / len(rtf), max(rtf), f(pc(lw, 0.5)), f(pc(lw, 0.95)), f(lw[-1] if lw else None),
        f(pc(bs, 0.95), 1), f(bs[-1] if bs else None, 1), sum(r.get("late_chunks", 0) for r in runs))


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    args = sys.argv[1:]
    words = "--words" in args
    tags = [a for a in args if not a.startswith("--")]
    out = os.path.join(os.path.expanduser("~"), ".androidbuild", "probe-logs")
    for fpath in sorted(glob.glob(os.path.join(out, "*.json"))):
        tag = os.path.basename(fpath)[:-5]
        if tags and tag not in tags:
            continue
        try:
            o = json.load(open(fpath, encoding="utf-8"))
        except Exception as e:
            print("skip", fpath, e, file=sys.stderr)
            continue
        if o.get("mode") != "sherpa":
            continue
        print("### " + tag)
        print()
        runs = o.get("runs") or []
        if runs:
            print(RUN_HDR)
            for r in runs:
                print(run_row(tag, r))
            print()
        print(tag_line(o))
        if o.get("duration_s"):
            d = o["duration_s"]
            first = [r for r in runs if r.get("t_start_s", 0) < 60]
            last = [r for r in runs if r.get("t_start_s", 0) >= d - 60]
            print("- first minute: " + minute_stats(first))
            print("- last minute:  " + minute_stats(last))
            snaps = o.get("snapshots") or []
            if snaps:
                print()
                print("| t (s) | runs done | RSS / PSS MB | batt C / thermal |\n|---|---|---|---|")
                s0 = o.get("mem_before_load") or {}
                print("| before load | 0 | %s | %s |" % (mem(o, "mem_before_load"), therm(o, "mem_before_load")))
                for s in snaps:
                    print("| %d | %s | %d / %d | %.1f C / %d |" % (s.get("t_s", 0), s.get("runs_done"), s.get("rss_kb", -1) // 1024, s.get("pss_kb", -1) // 1024,
                                                              s.get("batt_temp_tenths_c", -1) / 10.0, s.get("thermal_status", -1)))
                print("| end | %s | %s | %s |" % (o.get("n_runs"), mem(o, "mem_end"), therm(o, "mem_end")))
        if words and runs:
            r = runs[0]
            print()
            print("| word | end s (last token ts) | first partial wall s | lag wall s | first partial audio s | lag audio s |\n|---|---|---|---|---|---|")
            for w in r.get("words") or []:
                print("| %s | %.3f | %.3f | %.3f | %.3f | %.3f |" % (w["word"], w["end_s"], w["first_wall_s"], w["lag_wall_s"], w["first_audio_s"], w["lag_audio_s"]))
            print()
            print("partials of run 0:")
            for p in r.get("partials") or []:
                print("  audio %6.3f  wall %6.3f  %-4s %s" % (p["audio_s"], p["wall_s"], p.get("phase", ""), p["text"]))
        print()


if __name__ == "__main__":
    main()
