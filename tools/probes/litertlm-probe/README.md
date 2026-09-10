# litertlm-probe — the Tab S10+ (MT6989) APU gates, in one throwaway app

A separate Gradle build (own `settings.gradle.kts`, own wrapper copy, `applicationId
com.whispereverywhere.probe`, `minSdk 31`, debuggable) that sideloads BESIDE the Play copy of
Whisper Everywhere and never touches it. It runs the experiments from
`docs/superpowers/research/2026-09-09-tab-turbo-gemma-scribe-gemini-live-research.md` §1.4:

| mode | what | gate |
|---|---|---|
| `info` | `Environment.availableAccelerators`, `NpuCompatibilityChecker.Mediatek` | sanity |
| `lm` | LiteRT-LM `Engine` on `Backend.NPU(nativeLibraryDir)` / `GPU` / `CPU`, one prompt, `BenchmarkInfo` | **E3** |
| `litert` | LiteRT `CompiledModel` one signature, `Accelerator.CPU` (XNNPACK, N threads) / `GPU` / `NPU` (JIT) — 1 cold + N warm | **E5**, **E4-lite** |

## Build (PC)

```
python fetch_mediatek_runtime.py            # LiteRT v2.1.1 mediatek_runtime -> app/src/main/jniLibs/arm64-v8a/
set JAVA_HOME=C:\Program Files\Android\Android Studio1\jbr
gradlew.bat :app:assembleDebug              # build dir: C:\Users\bastr\.androidbuild\litertlm-probe\
adb -s 192.168.1.161:44483 install -r C:\Users\bastr\.androidbuild\litertlm-probe\app\outputs\apk\debug\app-debug.apk
```

## Models (PC -> Tab, never through the Play copy)

```
adb -s <tab> push <file> /data/local/tmp/<file>
adb -s <tab> shell run-as com.whispereverywhere.probe cp /data/local/tmp/<file> files/<file>
adb -s <tab> shell run-as com.whispereverywhere.probe sha256sum files/<file>
adb -s <tab> shell rm /data/local/tmp/<file>
```

## Run

```
python drive.py --tag e5_turbo_cpu_1 mode=litert model=/data/user/0/com.whispereverywhere.probe/files/whisper_large_v3_turbo_30s_i8.tflite accel=cpu threads=4 warm=20
python drive.py --tag e4_base_npu_1  mode=litert model=/data/user/0/com.whispereverywhere.probe/files/whisper_base_30s_f32.tflite accel=npu warm=20
python drive.py --tag e3_gemma_npu_1 mode=lm     model=/data/user/0/com.whispereverywhere.probe/files/Gemma3-1B-IT_q4_ekv1280_mt6989.litertlm accel=npu
```

Results: `~/.androidbuild/probe-logs/<tag>.{json,filtered.log,full.log}`; `python summarize.py [tag ...]`
prints them as the markdown tables in `docs/measurements/2026-09-09-tab-apu-probe.md`.

## Utilization sampling (which unit actually ran — measurements §3.1, §3.2)

Start a sampler in a second shell just before `drive.py`, so its window brackets the warm phase:

```
python tools/sample_util.py --mode gpu --duration 60 --out ~/.androidbuild/probe-logs/util_<tag>.txt
python tools/sample_util.py --mode cpu --duration 40 --out ~/.androidbuild/probe-logs/cpustat_<tag>.txt
python tools/sample_util.py --summarize ~/.androidbuild/probe-logs/util_<tag>.txt   # §3.1 columns
python tools/cpustat.py            ~/.androidbuild/probe-logs/cpustat_<tag>.txt     # §3.2 columns
```

`--mode gpu` reads `/sys/kernel/gpu/{gpu_busy,gpu_clock}`; `--mode cpu` reads the aggregate `cpu` line of
`/proc/stat`. One `adb shell` per sample, so the round trip sets the cadence (~0.30 s / ~0.52 s measured).

Notes:
- `gradlew.bat` runs from PowerShell/cmd. From Git Bash, prefix every `drive.py` / `adb shell` call with
  `MSYS_NO_PATHCONV=1`, or `/data/...` arguments arrive on the device as `C:/Program Files/Git/data/...`.
- Also from Git Bash: an MSYS-style `--out /c/Users/...` used to be written literally and landed under
  `C:\c\Users\...`. `drive.py` and `sample_util.py` now normalise `/<drive>/…` to `<drive>:/…` themselves,
  so either spelling works.
- `<tag>.filtered.log` is a regex over all of logcat, so other processes' matching lines are in it too
  (Samsung's `e:iwhInfService` emits `TfLiteFlexDelegate` lines during NPU runs). `drive.py --pid` keeps
  only the probe's own pid plus lines that name it (the `apuware_server` `client_pid=` proof lines
  survive). `<tag>.full.log` is always the complete logcat.
- litert 2.1.1 is the default runtime (the last release whose NPU zip ships the MediaTek pair; the APK on
  the Tab is this exact build). `-PlitertVersion=2.2.0` builds the newer runtime, GPU/CPU only on MT6989.
- `litert-community/Gemma3-1B-IT` (the MT6989 `.litertlm` E3 wants) is `gated: auto` on Hugging Face: a
  read token from an account that accepted the Gemma licence is needed (HTTP 401 otherwise). The un-gated
  `litert-community/Qwen3-0.6B` `.litertlm` is the substitute E3 arm.
