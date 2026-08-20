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

GPU-VERDICT: PENDING

(paste EVERY `BENCH gpu-ab` line from BOTH cold runs here, plus the `WE-DIAG gpu-canary` line
captured in step 2, then fill the table)

Run 1 (cold):

Run 2 (cold):

| tier  | run | cpuRtf8s | gpuRtf8s | gpuVsCpuWer | canaryCpu | canaryGpu |
|-------|-----|----------|----------|-------------|-----------|-----------|
| pro   | 1   |          |          |             |           |           |
| pro   | 2   |          |          |             |           |           |
| multi | 1   |          |          |             |           |           |
| multi | 2   |          |          |             |           |           |

Production canary captured in step 2 (WE-DIAG): `gpu-canary: passed=____`, latch line: ____

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
