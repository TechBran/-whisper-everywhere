# GPU/CPU A-B bench — recorded verdicts (3.6.0 Workstream C, spec Decision Gate 2)

Where `WhisperBenchTest#bench_gpu_vs_cpu_ab` (Task C8) numbers land. Decision Gate 2 asks one
question: **does the multilingual GPU path earn the DEFAULT on the owner's device?** The 3.6.0
answer ships as "no — experimental toggle, canary-gated, off by default"; this record is what could
change that in a later release. It is a separate artifact from
`2026-08-19-audio-ctx-floor-bench.md` on purpose — that file's `RESULT:` line is Task G4's gate and
must never be confused with this one. **This file's verdict token is `GPU-VERDICT:`.**

## What the bench measures, and what it does not

Per installed tier it runs two FORCED arms — `WhisperNative.init(path, useGpu=false)` then
`init(path, useGpu=true)` — and prints, per arm, the load time, a 3 s and an 8 s `rtf`, and whether
the bundled canary clip transcribed correctly. It then scores the two arms' 8 s transcriptions
against each other as `gpuVsCpuWer`.

The arms bypass `GpuPolicy` entirely: they read no latch, write none, and arm no crash sentinel. A
standing `gpu_canary=false` latch does not stop the GPU arm from running — re-measuring the ban is
the point.

**A number here is evidence, not a production behaviour change.** Production still needs the
experimental toggle ON *and* a passing canary for that (versionCode, model). Latches are keyed per
(versionCode, model), so a `gpu_canary=false` already on the device stands until the versionCode
bumps, however good the bench looks.

## Run protocol (owner device) — the ORDER matters

Build both APKs. Never `:app:installDebug` / `:app:connectedDebugAndroidTest` — gradle install tasks
wipe app data and the downloaded models; `adb install -r` preserves them. adb is not on PATH: use
`& "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"`.

    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
    adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk
    adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk

1. **Enable the toggle:** Settings → "Try GPU for multilingual (experimental)".
2. **Force a cold load and capture the CANARY first** — switch away from multi and back (or restart
   the bubble service), then:

        adb logcat -d -s WE-DIAG | findstr gpu-canary

   Record what it says (`gpu-canary: passed=true|false`, and the following
   `GpuPolicy: canary PASSED|FAILED` line) BEFORE running the bench. This is the production
   verdict, and it is the one that latches.
3. **Then run the bench and grep it:**

        adb logcat -c
        adb shell am instrument -w -e class com.whispereverywhere.whisper.WhisperBenchTest#bench_gpu_vs_cpu_ab com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner
        adb logcat -d -s WE-BENCH | findstr "gpu-ab"

**Why that order (the latch warning).** The bench's warm load goes through
`WhisperNativeBackend.load` for backend-registration parity, and that seam calls
`GpuPolicy.decideUseGpuForLoad`. With the toggle on, a multilingual first installed tier, and no
verdict yet for this (versionCode, model), the warm load runs the FULL production canary and
`onCanaryResult` writes a **permanent** per-(versionCode, model) latch — a PASS latch decided by the
~1 s digit clip alone, before the WER arms have said anything. Nothing about it appears in the
`WE-BENCH` grep; it logs under `WE-DIAG`. Running the bench standalone with the toggle on therefore
lets the harness silently write a production latch. The H2 ordering above — toggle → canary check →
bench — makes that write visible and deliberate. (If a latch is already recorded, the warm load just
honours it and writes nothing new.)

Do not dictate or run batch jobs while the bench runs: `NativeComputeGate` serializes the calls, so
your own audio would not corrupt the arms, but it would sit in the timing and it would run on
whichever backend the bench forced.

## Reading the lines

Per tier the grep prints, in order: `arm=cpu loadMs=`, two `arm=cpu slice=…s wallMs= rtf=` lines
(3 s, 8 s), the same three for `arm=gpu`, then one summary line:

    BENCH gpu-ab tier=<id> gpuVsCpuWer=%.3f canaryCpu=<bool> canaryGpu=<bool> cpuRtf8s=%.3f gpuRtf8s=%.3f

**Read `canaryGpu` only ALONGSIDE `gpuVsCpuWer`, from that same line.** The canary is a ~1 s
corruption SCREEN, not an accuracy measurement — it can pass on a GPU that still degrades real
speech. Only the WER against the CPU arm says the GPU decodes like the CPU does. `canaryGpu=false`
is equally a result: it reproduces the documented Adreno corruption and closes the question for that
model+driver.

**The thermal rule: run the whole bench TWICE, both times from cold.** The CPU arm always runs
first, so a device that has heated up during it hands the GPU arm a throttled SoC — and a cold-start
run biases the other way. With two arms, no interleaving and no repetition, a 10-20 % `rtf`
difference is not distinguishable from thermal noise. Only a **consistent large gap across both
runs** is signal. Paste both runs below.

**`rtf` here is NOT comparable to production `segment-timing rtf=`.** The bench arms call
`WhisperNative.transcribe(..., vadModelPath = null)` — VAD OFF, the raw encoder cost of the tiled
clip. Production live dictation runs the Silero VAD first (`useVad=true`), which trims silence
near-free and lowers the apparent rtf. Compare bench-to-bench (CPU arm vs GPU arm, same run), never
bench-to-session. The same caution applies against `2026-08-19-audio-ctx-floor-bench.md`, whose arms
go through `WhisperNativeBackend.transcribe` with VAD on.

**Matching `loadMs`/`rtf` between the two arms means no GPU ran.** On a device without OpenCL, ggml
silently falls back to CPU inside the "GPU" arm and both arms measure the same backend. Such a run
is not evidence about the GPU — say so rather than recording a verdict.

**A `gpu_validated=true` with no canary verdict** in prefs is the known benign window (a process
death between the canary's transcribe returning and `onCanaryResult` committing). Re-launch once and
the canary re-runs; it is not a bench result.

## Prerequisite

`app/src/main/assets/canary_digits.wav` (Task C4, owner-supplied) must be bundled. Until it is, the
test SKIPS with "canary_digits.wav not bundled" — an intended skip, not a failure, and no numbers
can be recorded.

## Results

GPU-VERDICT: BAN reason=slower

**The multilingual question (Decision Gate 2's question) is CLOSED: BAN, for speed, not
corruption** (Fold6, Adreno 750, versionCode 77, 2026-08-20). Across all four cold runs (two
multi-only, then two both-tier after pro was installed) the GPU decodes **identically** to the
CPU (gpuVsCpuWer=0.000 everywhere, every canary passes — the documented Adreno corruption does
NOT reproduce on this device/driver) and multi-GPU is **~8–9× slower** (rtf8s 0.41–0.48 CPU vs
3.48–3.84 GPU). Not thermal noise: the protocol's 10–20 % band is two orders of magnitude below
the gap, the CPU arm ran first every time, and the arms measured different backends (loadMs
~200/290 CPU vs ~1515–1933 GPU).

**Pro is the mirror image — its rows VALIDATE the existing `.en` GPU default with data:**
gpuRtf8s 0.309/0.278 vs cpuRtf8s 0.406/0.419 (GPU consistently faster, and 2× faster on the
floor-bound 3 s slice: ~1.5 s vs ~3.2 s), wer 0.000, canaries pass. Meets every ALLOW-DEFAULT
condition — no policy change needed since `.en` already defaults to GPU. Read together, the two
tiers confirm the vocab-shape kernel hypothesis (upstream whisper.cpp #3708 family): the .en
vocab (51864, %4==0) rides Adreno's fast matmul path; the multilingual vocab (51865, %4==1) now
takes a correct-but-slow path where it used to corrupt. `GpuPolicy.isGpuSafeModel`'s ".en" gate
is exactly right as shipped.

Runs 1–2 (multi only, pro not yet installed; cold, 5-min idle before each):

    BENCH gpu-ab tier=multi arm=cpu loadMs=201
    BENCH gpu-ab tier=multi arm=cpu slice=3s wallMs=3241 rtf=1.080
    BENCH gpu-ab tier=multi arm=cpu slice=8s wallMs=3315 rtf=0.414
    BENCH gpu-ab tier=multi arm=gpu loadMs=1777
    BENCH gpu-ab tier=multi arm=gpu slice=3s wallMs=12106 rtf=4.035
    BENCH gpu-ab tier=multi arm=gpu slice=8s wallMs=29380 rtf=3.673
    BENCH gpu-ab tier=multi gpuVsCpuWer=0.000 canaryCpu=true canaryGpu=true cpuRtf8s=0.414 gpuRtf8s=3.673
    ----
    BENCH gpu-ab tier=multi arm=cpu loadMs=206
    BENCH gpu-ab tier=multi arm=cpu slice=3s wallMs=3171 rtf=1.057
    BENCH gpu-ab tier=multi arm=cpu slice=8s wallMs=3274 rtf=0.409
    BENCH gpu-ab tier=multi arm=gpu loadMs=1933
    BENCH gpu-ab tier=multi arm=gpu slice=3s wallMs=11346 rtf=3.782
    BENCH gpu-ab tier=multi arm=gpu slice=8s wallMs=27831 rtf=3.479
    BENCH gpu-ab tier=multi gpuVsCpuWer=0.000 canaryCpu=true canaryGpu=true cpuRtf8s=0.409 gpuRtf8s=3.479

Runs 3–4 (both tiers; cold, 5-min idle before each):

    BENCH gpu-ab tier=pro arm=cpu loadMs=190
    BENCH gpu-ab tier=pro arm=cpu slice=3s wallMs=3183 rtf=1.061
    BENCH gpu-ab tier=pro arm=cpu slice=8s wallMs=3244 rtf=0.406
    BENCH gpu-ab tier=pro arm=gpu loadMs=1729
    BENCH gpu-ab tier=pro arm=gpu slice=3s wallMs=1547 rtf=0.516
    BENCH gpu-ab tier=pro arm=gpu slice=8s wallMs=2469 rtf=0.309
    BENCH gpu-ab tier=pro gpuVsCpuWer=0.000 canaryCpu=true canaryGpu=true cpuRtf8s=0.406 gpuRtf8s=0.309
    BENCH gpu-ab tier=multi arm=cpu loadMs=302
    BENCH gpu-ab tier=multi arm=cpu slice=3s wallMs=3200 rtf=1.067
    BENCH gpu-ab tier=multi arm=cpu slice=8s wallMs=3466 rtf=0.433
    BENCH gpu-ab tier=multi arm=gpu loadMs=1761
    BENCH gpu-ab tier=multi arm=gpu slice=3s wallMs=11539 rtf=3.846
    BENCH gpu-ab tier=multi arm=gpu slice=8s wallMs=28620 rtf=3.578
    BENCH gpu-ab tier=multi gpuVsCpuWer=0.000 canaryCpu=true canaryGpu=true cpuRtf8s=0.433 gpuRtf8s=3.578
    ----
    BENCH gpu-ab tier=pro arm=cpu loadMs=181
    BENCH gpu-ab tier=pro arm=cpu slice=3s wallMs=3240 rtf=1.080
    BENCH gpu-ab tier=pro arm=cpu slice=8s wallMs=3355 rtf=0.419
    BENCH gpu-ab tier=pro arm=gpu loadMs=1515
    BENCH gpu-ab tier=pro arm=gpu slice=3s wallMs=1445 rtf=0.482
    BENCH gpu-ab tier=pro arm=gpu slice=8s wallMs=2220 rtf=0.278
    BENCH gpu-ab tier=pro gpuVsCpuWer=0.000 canaryCpu=true canaryGpu=true cpuRtf8s=0.419 gpuRtf8s=0.278
    BENCH gpu-ab tier=multi arm=cpu loadMs=289
    BENCH gpu-ab tier=multi arm=cpu slice=3s wallMs=3618 rtf=1.206
    BENCH gpu-ab tier=multi arm=cpu slice=8s wallMs=3874 rtf=0.484
    BENCH gpu-ab tier=multi arm=gpu loadMs=1566
    BENCH gpu-ab tier=multi arm=gpu slice=3s wallMs=11981 rtf=3.994
    BENCH gpu-ab tier=multi arm=gpu slice=8s wallMs=30731 rtf=3.841
    BENCH gpu-ab tier=multi gpuVsCpuWer=0.000 canaryCpu=true canaryGpu=true cpuRtf8s=0.484 gpuRtf8s=3.841

| tier  | run | cpuRtf8s | gpuRtf8s | gpuVsCpuWer | canaryCpu | canaryGpu |
|-------|-----|----------|----------|-------------|-----------|-----------|
| pro   | 3   | 0.406    | 0.309    | 0.000       | true      | true      |
| pro   | 4   | 0.419    | 0.278    | 0.000       | true      | true      |
| multi | 1   | 0.414    | 3.673    | 0.000       | true      | true      |
| multi | 2   | 0.409    | 3.479    | 0.000       | true      | true      |
| multi | 3   | 0.433    | 3.578    | 0.000       | true      | true      |
| multi | 4   | 0.484    | 3.841    | 0.000       | true      | true      |

Production canary captured in step 2 (WE-DIAG, cold app relaunch 14:56 local):
`GpuPolicy: ggml-small-q5_1.bin passed the canary on this device -> GPU allowed`, and the
persisted latch (shared_prefs/gpu_policy.xml) after it:
`gpu_canary_v77_ggml-small-q5_1.bin=true`, `gpu_validated_v77_ggml-small-q5_1.bin=true`,
both inflight flags false, `renderer=Adreno (TM) 750`.

**Consequence the canary cannot see (flagged for Decision Gate 2):** the canary is a CORRUPTION
screen and it correctly passed — so with the experimental toggle ON, production now routes multi
to a GPU that is 9× slower than the CPU it replaced. "Correct but slower" latches as allowed by
design. Until the gate decides (pull the toggle row, add a speed term to the canary, or leave it
as an informed-consent experiment), the owner guidance is simply: keep the toggle OFF.

When filled, replace `GPU-VERDICT: PENDING` with exactly one of:

- `GPU-VERDICT: ALLOW-DEFAULT tier=<multi|pro|both>` — `canaryGpu=true` AND `gpuVsCpuWer` at or
  under 0.10 AND a consistently lower `gpuRtf8s` across BOTH cold runs. The multilingual GPU default
  may be proposed for a following release (a versionCode bump is required anyway for existing
  latches to clear).
- `GPU-VERDICT: KEEP-EXPERIMENT` — mixed or noisy: canary passes but the WER or the speed gap does
  not hold across both runs, or the two arms measured the same backend. 3.6.0's shipped behaviour
  (toggle, off by default) stands unchanged. **This is the expected outcome and a complete answer.**
- `GPU-VERDICT: BAN reason=<corruption|slower>` — `canaryGpu=false`, or a WER that shows real
  degradation, or the GPU arm is not faster. The empirical ban is confirmed on this device+driver
  and the question is closed for this model tier.

## Decision

DECISION: PENDING (owner; Decision Gate 2 — no task is auto-gated on this file, unlike the floor
record. A flip of the multilingual GPU default is a follow-up release, never a same-branch edit.)
