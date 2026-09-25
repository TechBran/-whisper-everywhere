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
compiler plugin configured, buffers typed and sized by the compiled models' requirements and made WITHOUT their
strides (the v2.1.1 dispatch refuses a strided buffer; `LiteRtCreateManagedTensorBufferFromRequirements` would
have given every buffer the requirements' strides), the encoder on the NPU alone and the decoder on NPU + CPU (its
embedding lookups stay on the CPU), and a merged manifest whose only MediaTek declaration is
`libneuronusdk_adapter.mtk.so` (`stripAarMediatekDeclarations` removes the three the litert AAR re-adds, and fails
the build if one survives — this applies to every mode of this APK).

```
# in the repo root: BUILD the app (never install it), then stage its .so into this probe
gradlew.bat :app:assembleDebug -PlocalBuildRoot=C:/Users/bastr/.androidbuild/WhisperEverywhere-spike
python tools/mtk-apu/stage_litertasr_into_probe.py --build-root C:/Users/bastr/.androidbuild/WhisperEverywhere-spike
# here (fetch_mediatek_runtime.py first if jniLibs has no libLiteRtDispatch_MediaTek.so)
gradlew.bat :app:assembleDebug
```

The staging script copies `liblitertasr.so` and `libc++_shared.so` out of the app's built APK (the app builds with
`ANDROID_STL=c++_shared`) and `libLiteRt.so` 2.1.1 out of the litert AAR, refusing any `libLiteRt.so` whose sha256
is not the pinned `6ddc1b3d…`. The probe itself refuses a `files/litert_dispatch/libLiteRtDispatch_MediaTek.so`
that is neither of the dispatch's two identities — both the one v2.1.1 file, 409,728 B: the release zip member
`9e963c56…` (the design's pin) or the copy AGP packages into an APK, `f47bd9c0…` (what P0(c) staged there) — and
logs which one it found (`identity=zip-member|apk-copy`).

Run it with the AOT pair and the two mels already in `files/` (push recipe above):

```
F=/data/user/0/com.whispereverywhere.probe/files
python drive.py --serial R52XC00LL9K --pid --tag p1b_litertasr_rebind mode=litertasr \
    model=$F/turbo_encoder_qcio_f32_MediaTek_MT6989_apply_plugin.tflite \
    dec=$F/turbo_decoder_mtk_f32_MediaTek_MT6989_apply_plugin.tflite mels=jfk_mel128.bin,canary_mel128.bin utts=3
python drive.py ... --tag p1b_litertasr_copy ... kvstrategy=1      # the one-set arm: a native copy per step
```

The two self-KV strategies are the plan's "per-step time with one and with two self-KV sets", and both runs are
the gate: `kvstrategy=0` (default) swaps two sets by RE-BINDING — no byte moves, but the v2.1.1 dispatch
re-registers each of the 16 re-bound buffers at the next run, a cost inside the run's time (so the JSON's
`cache_copy_ms_mean` is `null`, not a 0.0 it never was); `kvstrategy=1` keeps one input set and copies the step's
8 cache tensors (~8 MB) back into it natively, with no binding ever changed. Compare `step_ms_mean`, which includes
either advance; the faster one becomes the default in a later commit.

**`perfmode` is not a comparison this runtime can make.** On LiteRT 2.1.1 with the AOT pair the MediaTek dispatch
never reads the performance mode: it hands its options to the adapter loader (which reads only the SDK version
type), and its bytecode load hard-codes `NEURON_PRIORITY_HIGH`, `NEURON_PREFER_SUSTAINED_SPEED` and an execution
boost hint of 100. Every run is already in `PreferSustainedSpeed`; the extra is passed through (the init line
says `perfmode=N (inert on LiteRT 2.1.1 AOT)`) and a run per value measures nothing.

Sequence: `nativeProbe` (the adapter walk, timed on its own — 169–239 ms at the first gate; with
`libneuron_sys_util.mtk.so` stripped from the merged manifest there is no 5 s wait at all, sheet §4b) →
`nativeInit` (runtime, environment, both files' `LiteRtStamp`, the IO census, both restores, the buffers, and the
APU check: the decoder's first step on zeroed caches, refused over 250 ms) → per round and mel: `nativeEncode` →
`nativeDetectLanguage` (`detect=false` skips it) → `nativeDecodeSegment` with the app's arguments →
`nativeRelease` → with `rearm=true` (default) a second `nativeInit` + one window + release: the re-arm after a trim,
which pays the restores and must not walk the adapter again. The result JSON is checkpointed after every
utterance, and a non-finite number (the stats' documented NaN for "not measured") is written as `null` with a
`litertasr|nonfinite|field=…` line. Extras: `kvstrategy` (0 | 1), `perfmode` (-1 default | 0..3, inert), `wantmajor` (8), `socstamp`
(mt6989), `diag` (true: native `npu-debug: steptime` lines for each segment's first four steps and its last),
`lang` (en | auto).

Read back: `PROBE litertasr|…` lines (probe/init ms, per-utterance encode/detect/decode ms, steps, ms per step,
nsp/lp/rung/terminator, timestamp pairing, `matches_reference` against t8's ids) and on `WE-DIAG` the native
`apu:` driver line, the `stamp=` lines, both restores with their accelerator sets, the `apu: decoder step … pass`
line, the `buffers:` line (each kind's requirements and the type made: 2 = AHWB, 4 = DMA-BUF for everything a
DISPATCH_OP touches, 1 = host memory for `input_ids` and `position_ids`), `decode: … nsp=… lp=… ent=… step=… ms
(run …, io …, kv …)` — an unmeasured stat printed as `nan(unmeasured: …)`, the advance as `kv re-bind, re-registered
inside run` (0) or `kv copy N ms xC` (1, per copy) — and, with diag, the bounded step times. The result JSON keeps `detok.py`'s `utterances[].ids` shape.
`mode=e2eqc` on the same pair is the Kotlin-API arm (a Kotlin copy per step).

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
