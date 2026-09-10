"""Summarise the /proc/stat samples written by the probe's adb sampler: each line is
'<HH:MM:SS> cpu <prev 10 fields> | cpu <cur 10 fields>'; busy% = 1 - idle_delta/total_delta (idle+iowait)."""
import sys, statistics

for path in sys.argv[1:]:
    rows = []
    for line in open(path, encoding="utf-8", errors="replace"):
        if " | " not in line or "cpu " not in line:
            continue
        ts, rest = line.split(" ", 1)
        prev, cur = rest.split(" | ")
        p = [int(x) for x in prev.split()[1:]]
        c = [int(x) for x in cur.split()[1:]]
        if len(p) < 5 or len(c) < 5:
            continue
        tot = sum(c) - sum(p)
        idle = (c[3] + c[4]) - (p[3] + p[4])
        if tot <= 0:
            continue
        rows.append((ts, 100.0 * (1 - idle / tot)))
    if not rows:
        print(path, "no samples")
        continue
    vals = [v for _, v in rows]
    # 8 CPUs: 100% busy on all cores = 100 here (proc/stat 'cpu' line aggregates cores), so 12.5% = one core.
    print("%s: n=%d mean=%.1f%% median=%.1f%% p90=%.1f%% max=%.1f%% (aggregate of 8 cores; 12.5%% = one core saturated)" % (
        path.split("/")[-1], len(vals), statistics.mean(vals), statistics.median(vals),
        sorted(vals)[int(0.9 * (len(vals) - 1))], max(vals)))
    # print a compact timeline (every sample, rounded)
    print("   timeline:", " ".join("%s=%d" % (t[3:], v) for t, v in rows))
