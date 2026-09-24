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
| `sherpa` | sherpa-onnx `OnlineRecognizer` on `streaming-zipformer-en-2023-06-26` (int8), fed 512-sample / 32 ms chunks at real-time or max pace, endpointing off, zero-pad + `inputFinished` + release/createStream per clip; every partial with its wall time, per-word partial latency, WER through the app's `WerMath`, retractions, compute RTF, RSS, thermal; `load=N` busy threads, `duration=S` thermal loop | **rung 3** of `docs/superpowers/research/2026-09-09-streaming-local-tier-research.md` §5.4 |

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

## `mode=sherpa` (rung 3 — `docs/measurements/2026-09-10-tab-sherpa-rung3.md`)

The AAR is the one the main app ships (`sherpa-onnx-1.13.7.aar`, ORT 1.27.1; taken from the main checkout's
`app/libs` or fetched, sha256-verified on every build); `-PsherpaVersion=1.13.4` builds the comparison arm on the
previously shipped AAR (ORT 1.27.0). The four model files go under `files/zipformer-en/`, the clips under `files/`
(the README's push recipe; the probe hashes all of them and logs the hashes). `model` is the model DIRECTORY.

```
M=/data/user/0/com.whispereverywhere.probe/files/zipformer-en
python drive.py --tag r3_canary_rt_t2   mode=sherpa model=$M clips=canary_digits.wav threads=2 pace=realtime padms=500
python drive.py --tag r3_jfk_rt_t2      mode=sherpa model=$M clips=jfk.wav,jfk-gated.wav threads=2 pace=realtime
python drive.py --tag r3_jfk_max_t2     mode=sherpa model=$M clips=jfk.wav threads=2 pace=max loops=10
python drive.py --tag r3_jfk_rt_t2_load mode=sherpa model=$M clips=jfk.wav threads=2 pace=realtime loops=3 load=4
python drive.py --tag r3_jfk_thermal_t2 mode=sherpa model=$M clips=jfk.wav threads=2 pace=realtime duration=600 --timeout 900
python sherpa_summary.py [--words] [tag ...]
```

`pace=realtime` feeds chunk i no earlier than t0 + (i+1)·32 ms (late if the previous decode burst overran — the
queue the app would carry); `pace=max` is the compute-only RTF read. Partial latency per word = wall time of the
first partial whose k-th `WerMath` word equals the final's k-th word, minus the word's last token timestamp (rung
1's definition). `provider=nospin` writes an ORT session-config file (`SessionConfig.session.intra_op.allow_spinning=0`)
and passes `cpu:<path>` — forwarded on >= 1.13.5 only. Never `reset`: each clip is its own stream, released.

## `mode=litertasr` (P1b device gate — the app's `liblitertasr.so` in the product's shape)

The MediaTek tier's native engine (`app/src/main/cpp/litert_asr.cpp`) driven through the app's OWN Kotlin
declarations: the `stageAppSeam` task compiles `LiteRtAsrNative.kt` and the four decode-policy files
(`WhisperTokens`, `WhisperTokenFamily`, `NpuDecodePolicy`, `NpuDecodeStats`) into this APK verbatim, so the JNI
names, the prompt, the masks, the ladder and the guard constants are the ones `NpuWhisperBackend` uses. The shape:
dispatch from `files/litert_dispatch/` (staged there from this APK's own copy when absent; exactly one file), no
compiler plugin configured, requirement-typed buffers only, and a merged manifest whose only MediaTek declaration
is `libneuronusdk_adapter.mtk.so` (`stripAarMediatekDeclarations` removes the three the litert AAR re-adds, and
fails the build if one survives — this applies to every mode of this APK).

```
# in the repo root: BUILD the app (never install it), then stage its .so into this probe
gradlew.bat :app:assembleDebug -PlocalBuildRoot=C:/Users/bastr/.androidbuild/WhisperEverywhere-spike
python tools/mtk-apu/stage_litertasr_into_probe.py --build-root C:/Users/bastr/.androidbuild/WhisperEverywhere-spike
# here (fetch_mediatek_runtime.py first if jniLibs has no libLiteRtDispatch_MediaTek.so)
gradlew.bat :app:assembleDebug
```

The staging script copies `liblitertasr.so` and `libc++_shared.so` out of the app's built APK (the app builds with
`ANDROID_STL=c++_shared`) and `libLiteRt.so` 2.1.1 out of the litert AAR, refusing any `libLiteRt.so` whose sha256
is not the pinned `6ddc1b3d…`.

Run it with the AOT pair and the two mels already in `files/` (push recipe above):

```
F=/data/user/0/com.whispereverywhere.probe/files
python drive.py --serial R52XC00LL9K --pid --tag p1b_litertasr_default mode=litertasr \
    model=$F/turbo_encoder_qcio_f32_MediaTek_MT6989_apply_plugin.tflite \
    dec=$F/turbo_decoder_mtk_f32_MediaTek_MT6989_apply_plugin.tflite mels=jfk_mel128.bin,canary_mel128.bin utts=3
python drive.py ... --tag p1b_litertasr_sustained ... perfmode=2      # PreferSustainedSpeed vs the default (-1)
```

Sequence: `nativeProbe` (the adapter walk — the 5 s — timed on its own) → `nativeInit` (runtime, environment,
both files' `LiteRtStamp`, the IO census, both restores, the buffers) → per round and mel: `nativeEncode` →
`nativeDetectLanguage` (`detect=false` skips it) → `nativeDecodeSegment` with the app's arguments → `nativeRelease`
→ with `rearm=true` (default) a second `nativeInit` + one window + release: the re-arm after a trim, which must pay
the restores and NOT the 5 s. Extras: `perfmode` (-1 default | 0..3), `wantmajor` (8), `socstamp` (mt6989),
`diag` (true: native per-step `npu-debug: steptime` lines), `lang` (en | auto).

Read back: `PROBE litertasr|…` lines (probe/init ms, per-utterance encode/detect/decode ms, steps, ms per step,
nsp/lp/rung/terminator, timestamp pairing, `matches_reference` against t8's ids) and on `WE-DIAG` the native
`apu:` driver line, the `stamp=` lines, both restore times, the requirement types of the buffers (2 = AHWB,
4 = DMA-BUF), `decode: … step=… ms (run …, io …)` and, with diag, every step's time. The result JSON keeps
`detok.py`'s `utterances[].ids` shape. The one-self-KV-set arm of the plan's comparison is `mode=e2eqc` on the
same pair (a Kotlin copy per step); this mode is the two-set, zero-copy arm.

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
