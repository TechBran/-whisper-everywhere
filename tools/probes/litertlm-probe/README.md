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

Results: `~/.androidbuild/probe-logs/<tag>.{json,filtered.log,full.log}`; the numbers are copied into
`docs/measurements/2026-09-09-tab-apu-probe.md`.
