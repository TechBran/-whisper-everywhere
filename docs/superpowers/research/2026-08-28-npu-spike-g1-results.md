# NPU Spike G1 — RESULTS: GO (2026-08-28)

The measurement the 4.x roadmap gated on, taken on the owner's Galaxy Z Fold6
(SM8650, Snapdragon 8 Gen 3, Hexagon HTP v75, Android 16 / One UI, retail firmware),
via a browser-installed third-party app with app-bundled DSP libraries — no root, no
cable, no ONNX Runtime.

## The answer

**Whisper-Small-Quantized (w8a16) encoder, one full 30s window, end-to-end including
input prep and the 26.4MB KV-cache output drain:**

| Metric | Unvoted (run 7) | **Voted (run 8, the ceiling)** | Bar | Verdict |
|---|---|---|---|---|
| Warm median | 1007.4 ms | **372.7 ms** | ≤400 ms | **PASS** |
| p95 | 1226.1 ms | 385.4 ms | — | 24 ms band |
| min / max | 838.9 / 1275.5 | 362.3 / 386.0 | — | spread collapsed |
| First run | 920.9 ms | 374.7 ms | — | no warmup needed |
| Cold context load (127 MB) | 488 ms | 498 ms | ≤8 s | PASS, 16× under |
| Input prep | 0.43 ms | 0.40 ms | — | negligible |
| Drift (last-5 vs first-5) | −10.9 ms | +2.9 ms | stable | no thermal decay |

- vs the **CPU multi baseline** (same `openai/whisper-small` checkpoint, measured
  2.3–3.5 s fixed cost on this device): **6–9× faster**, on a compute unit that
  leaves the CPU free.
- vs the **AI Hub harness figure** (270.8 ms, excludes buffer copies): the ~100 ms
  delta is the integration tax (mostly the 24-tensor output drain), exactly as
  budgeted before the spike ran.
- The 372.7 ms fits the 4.1 streaming-partials cadence window (250–400 ms).

## The decisive variable: the HTP performance vote

Unvoted, the Hexagon runs at DCVS power-saver clocks: 1007 ms and a 437 ms spread.
The vote (DCVS off, bus+core corners pinned MAX, sleep disabled 40 µs, RPC control
latency 100 µs, polling 9999 µs via `QnnHtpPerfInfrastructure`) recovered 2.7× and
collapsed the spread to 24 ms. **The vote is the ceiling configuration, not the
shipping one** — the sustained-mode vote and its power draw are the next measurement.

## The architecture that produced it (owner decision, 2026-08-28)

**Raw QNN C API behind an owned JNI seam — no ONNX Runtime.** Both ORT EP routes
(plugin-EP and static) refused the EPContext model on-device; the raw seam is 8.7 MB
of harness instead of ~30 MB of runtime, and every failure names itself. This seam is
the product integration path.

## Lessons paid for on the way (8 runs, 7 instructive failures)

1. **Android 12+ silently refuses `libcdsprpc.so` without `<uses-native-library>`**
   in the manifest — the probable root cause behind ORT's opaque "not compatible"
   refusals as well.
2. **`Qnn_Tensor_t` is not self-contained**: `name`/`dimensions` point into
   system-context storage; freeing that context before binding = use-after-free at
   the first bind. Deep-copy + defer the free.
3. **`QNN_TENSOR_VERSION_*` are enum constants, not macros** — `#ifdef` on them
   compiles the guarded code out silently.
4. The AI Hub asset's metadata is **binary-info/graph-info v3, tensor v2**; readers
   must fail loudly on unknown versions (twice proven the right call).
5. Qualcomm's published latency assumes the perf vote; an unvoted integration ships
   3.7× slower than the datasheet and nothing warns you.
6. The public no-login QAIRT SDK download serves exact-version headers
   (range-fetchable); vendored header repos had wrong versions and excluding licenses.
7. The graph, confirmed on-device: `whisper_small_quantized_encoder`, input
   `ufixed16 [1,80,3000]` (480,000 B), 24× `ufixed8` KV outputs (27,648,000 B total).

## What remains before productization (no kill-switches, normal engineering)

1. **Sustained-vote profile**: the shipping vote (power/thermal budget under real
   dictation duty cycles) — measure `sustained_high_performance`-class settings.
2. **w8a16 accuracy**: the WER cells nobody has published (the AI Hub `evaluate.py`
   harness exists; our own canary/clip corpus applies).
3. **The decoder design**: encoder-on-NPU is proven; decoder options (NPU decoder
   asset vs whisper.cpp CPU decode over the NPU's cross-KV) are the next study.
4. Device gating + canary per the decision brief §3 (the detection ladder now has a
   proven probe to run).

Companion documents: the decision brief (`2026-08-27-npu-whisper-turbo-research.md`),
the spike harness reports (`.superpowers/sdd/2026-08-20-vad-endpointing/`
`npu-spike-report.md` + task outputs, gitignored), the harness project
(`C:\Users\bastr\.androidbuild\npu-spike\`, local-only — proprietary QNN headers are
never committed).
