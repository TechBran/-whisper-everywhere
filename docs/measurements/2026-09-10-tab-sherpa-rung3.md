# Rung 3 — the sherpa-onnx streaming Zipformer on the Galaxy Tab S10+, in the probe app

Executes §5.4 rung 3 of `docs/superpowers/research/2026-09-09-streaming-local-tier-research.md`: `mode=sherpa` in
`tools/probes/litertlm-probe`, linking the **same AAR the app ships** (`sherpa-onnx-1.13.7.aar`), fed the app's
32 ms chunking with endpointing off, measured against the design document's kill lines. The PC baseline it
extends is `docs/measurements/2026-09-10-zipformer-en-pc-rung1.md`.

---

## STATUS: MEASURED — every arm ran on the Tab on 2026-09-10, 13:40-14:07 local; **no kill line fires on 1.13.7, and 1.13.4 measures the same**

The Tab came back on wireless debugging (same serial `192.168.1.161:44483`, still advertising as
`adb-R52XC00LL9K-MHdBME`); the §3 sequence ran end to end in a second worktree (`C:/Users/bastr/.androidbuild/wt-rung3b`,
branch `tools/tab-rung3-results`). One probe defect surfaced on the first sherpa run and was fixed in `tools/probes/`
before any number was taken (§2.2). §4 is the kill-line table with the device column filled; §5-§7 are
`sherpa_summary.py`'s output and the log lines, copied; §8 the 1.13.4 delta; §9 the teardown proofs.

**The headline, 2 threads, real-time pace, the app's 32 ms chunking (§5):**

| | Tab S10+ (MT6989), rung 3 | PC (i7-8700K), rung 1 |
|---|---|---|
| canary (`canary_digits.wav`, pad 500 ms) | `ONE TWO THREE FOUR FIVE` — exact, WER 0.000, every run (8 of 8 canary runs across arms) | exact at pad ≥ 500 ms |
| `jfk` final / WER | byte-identical to the PC's: `AND SAW MY FELLOW AMERICANS ASK NOT WHAT'S YOUR COUNTRY CAN DO FOR YOU AS BUT YOU CAN DO FOR YOUR COUNTRY` — **0.182 (4/22)** | 0.182 (4/22) |
| `jfk-gated` final / WER | byte-identical: `AN THO MY FELLOW AMERICA AS FOR NOT WHAT YOU'RE CUTTER CAN DO FOR YOU ASK WHAT YOU CAN DO FOR YOUR COUNTRY` — **0.318 (7/22)** | 0.318 (7/22) |
| partial latency per word, wall clock (n = 100 words over 6 runs) | **p50 0.401 s, p95 0.523 s, max 0.526 s** (audio clock: 0.360 / 0.480 — the PC's exact structural numbers) | p50 0.36 / p95 0.48 (audio clock; no compute queue) |
| retractions | **0** in every run of every arm (62 runs of 1.13.7 before the thermal run) | 0 |
| RTF compute, max pace, 10 loops of `jfk` | **0.054 @ 2 threads** (0.051 @ 1, 0.072 @ 4); nospin 0.076 | 0.080 @ 2 (0.107 @ 1) |
| RTF compute at real-time pace (the cores wake cold every 32 ms) | 0.116 @ 2 threads (0.145 @ 1) | — |
| beside a 4-thread busy loop | real-time: RTF 0.099, lag p50 0.390 / p95 0.511 / max 0.514 s; max pace: RTF 0.076 | — |
| model load / warm-up first decode | 802-860 ms / 20-28 ms | — |
| RSS | before load ~181 MB → after load ~341 MB → end-of-arm 341-352 MB → after release ~308-321 MB | — |
| thermal across the ~45 s arms | battery 21.6 → 23.4 °C, `thermalStatus` 0 throughout (Tab on AC, charging) | — |
| 10-minute thermal run (55 × `jfk`, real-time, 2 threads) | RSS before load 180 MB → end 349 MB (**+169 MB**; peak snapshot +191 MB); battery **24.6 → 25.8 °C (+1.2 °C)**, `thermalStatus` **0 → 0**; RTF 0.119 first minute → 0.116 last; lag p50 0.399 / p95 0.522 (n = 1,210); 0 retractions (§6) | — |
| 1.13.4 (ORT 1.27.0) | canary exact; jfk / gated byte-identical (0.182 / 0.318); max-pace RTF **0.054 vs 0.054** — no delta (§8) | — |
| `provider=nnapi` | not a path in this AAR: `Android NNAPI requires API level >= 27. Current API level 21 Fallback to cpu!` — runs as CPU, RTF 0.051 (§5.6) | — |

---

## 1. WHAT WAS READ (first session 11:36-11:41; re-read at 13:39-13:41 before the arms — every value below held)

Re-read 13:39-13:41 in the second session, before anything was installed or pushed: `adb devices -l` listed the Tab as
`device` under both transports; `/data/local/tmp` held only `.studio/` (no `r3/` — the first session's push never
landed, as §STATUS of the blocked draft predicted); the Play copy read `versionName=4.3.2 versionCode=86
lastUpdateTime=2026-09-04 19:49:37`; the probe was still the pre-rung-3 build (`lastUpdateTime=2026-09-10 05:56:54`);
`dumpsys battery` level 99, `temperature: 219` (21.9 °C), `status: 2` (charging, AC — the Tab was on its charger for the
whole session, which is the warmer case for the thermal line); `mCurrentFocus` = the Samsung launcher (no dialog);
`df /data` 126 GB free. The seven files were re-hashed on the PC (all equal to §1.2) before the push.

### 1.1 The device

| Fact | Value | How |
|---|---|---|
| Model / OS | `SM-X828U`, Android **16**, SDK **36** | `getprop` |
| **Play copy** | `com.whispereverywhere` **versionName 4.3.2, versionCode 86**, `lastUpdateTime=2026-09-04 19:49:37`, minSdk 26 targetSdk 36 | `dumpsys package` — the baseline the teardown proof must match |
| Probe | `com.whispereverywhere.probe` 0.1-probe (versionCode 1), installed 2026-09-10 05:56:54 — the pre-rung-3 build (`info`/`litert`/`lm`/`e2e` modes only; **no `sherpa` mode on the Tab yet**) | `dumpsys package` |
| Screen | `mCurrentFocus=Window{… NotificationShade}`, `mFocusedApp=…launcher` — **no Play Protect dialog was showing** | `dumpsys window` |
| `/proc/cpuinfo` Features | `fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm sb paca pacg dcpodp sve2 sveaes svepmull svebitperm svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm bf16 dgh bti ecv afp wfxt` — **no `sme`** (SVE2 + i8mm + bf16 present) | `grep Features` — so a canary mismatch here would NOT be #3845 (that is the FEAT_SME/KleidiAI class); the probe logs this flag set per run |
| `/data/local/tmp` | only `.studio/` (2026-06-17) — clean | `ls -la` |
| Probe `files/` | the E3-E6 artefacts (`whisper_large_v3_turbo_30s_i8.tflite` 1,088,340,944 B, `whisper_base_30s_f32.tflite`, `whisper_tiny_*`, `Qwen3-0.6B.litertlm`, the four mel `.bin`s) — 2.25 GB, left alone | `run-as … ls -la files/` |
| adb | `adb devices -l` listed the Tab twice — `192.168.1.161:44483` (transport 2) and its own mDNS name `adb-R52XC00LL9K-MHdBME._adb-tls-connect._tcp` (transport 1): one device, two transports; only the serial was ever used | |

### 1.2 The artefacts (PC side; the device side re-hashes on every run and logs it)

**The AAR** — the one the main app ships since 4.3.4 (`app/build.gradle.kts:596-598`), copied from the main
checkout's `app/libs` into the worktree probe's `app/libs` and re-hashed there:

| AAR | Bytes | sha256 | `libonnxruntime.so` | `libsherpa-onnx-jni.so` | Version strings inside the `.so` |
|---|---|---|---|---|---|
| `sherpa-onnx-1.13.7.aar` | **49,113,869** | `c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8` ✔ (matches the app's pin and the task's) | 21,684,880 B | 4,761,536 B | symbol version tag **`VERS_1.27.1`** in both `.so`; `1.13.7` in the JNI |
| `sherpa-onnx-1.13.4.aar` (comparison arm) | 48,847,529 | `03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780` ✔ (the digest the app pinned at commit `2c00f81`) | 21,688,920 B | 4,710,728 B | **`VERS_1.27.0`**; `1.13.4` |

So the ORT-version claim of §1.4 of the research doc, which rung 1 could not read out of a binary, is now read
out of both binaries: **1.13.7 carries ORT 1.27.1, 1.13.4 carries 1.27.0.** The probe additionally logs
`VersionInfo.onnxruntimeVersion` at run time (the Kotlin getter exists from 1.13.5 on — `javap` on both
`classes.jar`s: 1.13.7 has `getOnnxruntimeVersion()`, 1.13.4 does not; the probe reads it by reflection so the
1.13.4 arm still runs and reports `n/a`).

**The model** — `C:/Users/bastr/.androidbuild/streaming-models/en-2023-06-26/`, re-hashed this pass, identical to
rung 1 §1.2 and to the research doc's §3.7 table:

| File | Bytes | sha256 |
|---|---|---|
| `encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 71,083,163 | `563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1` |
| `decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 1,307,236 | `98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02` |
| `joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 259,335 | `d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297` |
| `tokens.txt` | 5,048 | `49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb` |

**The clips** (PCM16 mono 16 kHz):

| Clip | Source | Bytes | sha256 |
|---|---|---|---|
| `canary_digits.wav` | `app/src/main/assets/` | 81,998 | `a3079109f735d4acea2756ce5398c67119ab36fa832571f5a5b45b616a7a5cd4` |
| `jfk.wav` | `app/src/androidTest/assets/` | 352,078 | `59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e` |
| `jfk-gated.wav` | the session scratchpad's `review/jfk-gated.wav` (rung 1's; 17 digital-zero gates) — not in the repo | 352,044 | `ab44bbd1adf412552a335cf4232ac642375ccc453da44a4e9dde01d6bc3635dc` |

---

## 2. WHAT WAS BUILT — `mode=sherpa` in the probe (first session: branch `tools/tab-rung3`, worktree `wt-rung3`, merged to main as `a0032f1`; this session: branch `tools/tab-rung3-results`, worktree `C:/Users/bastr/.androidbuild/wt-rung3b`, where §2.2's fix and rebuild happened)

Files, all under `tools/probes/litertlm-probe/` (no `app/` change anywhere):

| File | Change |
|---|---|
| `app/build.gradle.kts` | `-PsherpaVersion` (default **1.13.7**; `1.13.4` for the comparison arm); `fetchSherpaAar` task wired before `preBuild` — takes the AAR from the main checkout's `app/libs` (else the GitHub release URL) and **sha256-verifies it on every build** against the two pinned digests above; `implementation(files("libs/sherpa-onnx-${sherpaVersion}.aar"))`; the c-api / cxx-api `.so` excluded exactly as the app excludes them |
| `app/src/main/java/…/SherpaProbe.kt` | the mode (below) |
| `app/src/main/java/…/WerMath.kt` | `util/WerMath.kt` **verbatim** (package line only; `diff` clean) — the app's own normalisation scores the finals |
| `ProbeArgs.kt`, `ProbeRunner.kt` | extras `clips`, `pace`, `padms`, `loops`, `duration`, `load`, `provider`; `"sherpa" -> SherpaProbe` |
| `drive.py` | the new int extras; `sherpa|onnxruntime|onnx` in the log filter |
| `sherpa_summary.py` | the result JSONs → the tables of §5-§7 (per-run rows, per-tag line, first-minute vs last-minute, per-minute snapshots, `--words` per-word latency) |
| `README.md` | the mode's row and recipe |

**It builds.** `gradlew :app:assembleDebug` (JAVA_HOME = Android Studio's JBR, build dir
`C:/Users/bastr/.androidbuild/litertlm-probe/`): `sherpa-onnx-1.13.7.aar 49113869 B sha256 c4ef49e3… OK`,
`BUILD SUCCESSFUL in 38s`. The APK, copied to `C:/Users/bastr/.androidbuild/litertlm-probe/probe-sherpa-1.13.7.apk`,
is **38,638,786 B** and carries `lib/arm64-v8a/libonnxruntime.so` 21,684,872 B + `libsherpa-onnx-jni.so`
4,761,536 B beside the LiteRT / LiteRT-LM / MediaTek libraries of the earlier modes; the c-api / cxx-api `.so` are
absent, as in the app. The 1.13.4 arm: `-PsherpaVersion=1.13.4` → `probe-sherpa-1.13.4.apk` (§2.1).

**The recognizer, as configured** (the research doc §1.2 / rung 1 §1.4, unchanged):
`OnlineRecognizer(assetManager = null, OnlineRecognizerConfig(featConfig = FeatureConfig(16000, 80, dither 0),
modelConfig = OnlineModelConfig(transducer = (encoder, decoder, joiner), tokens, numThreads = <threads>, debug = true,
provider = <cpu | nnapi | cpu:<cfg>>, modelType = "zipformer2"), enableEndpoint = false, decodingMethod =
"greedy_search"))`. `debug = true` makes sherpa log its config and the model metadata it read — the proving lines
for §7. Every clip is its own stream: `createStream("")` … `release()`; **`reset` is never called** (§3.9-3.10).

**The feed** (rung 1 §1.5, on the device clock): 512-sample / 32 ms chunks; `acceptWaveform`; `while (isReady)
decode()` — every `decode()` timed individually; `getResult()` after each burst; a partial is recorded whenever the
cumulative text changes, with its **wall time from the run's t0** and its audio time. `pace=realtime`: chunk i is
fed no earlier than t0 + (i+1)·32 ms (`LockSupport.parkNanos`), and if the previous burst overran the chunk is fed
immediately and counted as **late** (the queue the app would carry). `pace=max`: no sleeping — the compute-only
read. Tail: `padms` of zeros (default **500**, rung 1's measured floor; 800 is the other arm), `inputFinished`,
drain, final text + tokens + timestamps. One warm-up (the first second of the first clip, its own stream, released)
precedes the runs so the first-call cost is measured once and reported as `warmup.first_decode_ms`, not folded into
a clip.

**The numbers each run records:** final text; `WerMath.wer` against the fixture reference (canary: the WORDS
one..five; jfk: whisper.cpp's sentence) with edits/ref-tokens; `GpuCanaryPolicy.canaryPasses` (ported) and an
exact-match flag on the canary; retractions (a partial whose predecessor is not its prefix); **partial latency per
word** = wall time of the first partial whose k-th `WerMath` word EQUALS the final's k-th word, minus the word's
audio time = its LAST token's timestamp — rung 1's definition, so the device numbers compare with the PC's 0.36 /
0.48 s p50/p95 structural floor; also the audio-clock version of the same lag (chunk quantisation only); p50 / p90
/ p95 / p99 / min / max of every `decode()` and of every per-chunk burst; **RTF compute** = Σ `decode()` time /
clip audio (feed+tail, and feed-only), RTF wall; late chunks and the max lateness; RSS/PSS + battery temperature +
`PowerManager.currentThermalStatus` at before-load / after-load / after-warm / end / after-release (and every 60 s in
a `duration` run); `load=N` spins N daemon busy-loop threads for the whole run (the stand-in for whisper `multi`'s
`n_threads = 4` — steadier than `multi`, which is bursty, so it is the worse case); `duration=S` loops the clip
list for S seconds (the thermal run). The device also hashes the four model files and the clips and logs the hashes,
so "verified on both sides" is one grep. Result JSON: `files/results/<tag>.json`, pulled by `drive.py` to
`~/.androidbuild/probe-logs/<tag>.json` beside the full and filtered logcat.

### 2.1 The 1.13.4 arm

Built from the same tree with `-PsherpaVersion=1.13.4`: `sherpa-onnx-1.13.4.aar 48847529 B sha256 03f9c4df… OK`,
`BUILD SUCCESSFUL in 25s` → `C:/Users/bastr/.androidbuild/litertlm-probe/probe-sherpa-1.13.4.apk` (38,638,786 B;
`libonnxruntime.so` 21,688,912 B + `libsherpa-onnx-jni.so` 4,710,728 B — the 1.27.0 pair). Same `applicationId`,
so `adb install -r` swaps the runtime under the same `files/` and the pushed model is reused. (`app-debug.apk` in
the build dir was put back to the 1.13.7 build afterwards, so the README's default path is the shipping arm.)
On this AAR `provider=nospin` is parsed but not forwarded (§1.4 of the research doc), and `VersionInfo` has no ORT
getter — both are expected and logged as such.

### 2.2 The one probe fix the device forced (commit `e9cc8a2`, `tools/probes/` only)

The very first sherpa run on the Tab (the canary, 13:41:11) completed — `DONE|ok=true`, canary exact, 0 retractions —
but with **`word_map_ok=false` and an empty per-word table**: `lag_wall_p50=-`. The JSON showed why: the AAR's JNI
returns `getResult().tokens` as `" ONE", " TWO", " THREE", " F", "OUR", " FI", "VE"` — the BPE word marker U+2581 has
already been turned into an ASCII space by the time Kotlin sees it — and `wordEndTimes` looked for U+2581 only, so
every piece folded into one word (1 word end vs 5 final words). Fix: a leading space opens a word too; the JSON now
also carries `word_ends_s`, `n_word_ends`, `n_final_words` so the mapping is auditable. Rebuilt both arms from the
worktree (`gradlew.bat :app:assembleDebug`, and `--% ... -PsherpaVersion=1.13.4` — PowerShell otherwise tokenises the
`1.13.4` and Gradle looks for a task `.13.4`):

| APK | Bytes | sha256 | `.so` pair inside |
|---|---|---|---|
| `probe-sherpa-1.13.7.apk` (13:43:21) | 38,638,946 | `369d969f2ba28eac30d47916d4d1a23097eb3ec7ac2b1caa0d6b8c38b3996877` | `libonnxruntime.so` 21,684,872 + `libsherpa-onnx-jni.so` 4,761,536 |
| `probe-sherpa-1.13.4.apk` (13:44:59) | 38,638,946 | `0af56540aa73adfabe1912dfde0cf586a2f14f42a5fedd55fe2008f295041659` | 21,688,912 + 4,710,728 |

The canary was re-run on the fixed build (the numbers in §4-§5 are all from it): `word_map_ok=true`, five word ends
`0.96 / 1.28 / 1.48 / 2.04 / 2.68 s`, lag wall p50 0.401 / p95 0.514 s. The pre-fix run's text, WER and RTF
(`ONE TWO THREE FOUR FIVE`, 0.000, 0.108) were the same; only the per-word column was missing. Nothing in the feed,
the decode loop or the scoring changed.

Two session mechanics worth one line each, for the next person: under `MSYS_NO_PATHCONV=1` the *local* path handed
to `adb install` must be Windows-style (`C:/Users/...`), or adb cannot stat it; and the fresh worktree needs the
gitignored inputs copied in (`app/libs/sherpa-onnx-1.13.{7,4}.aar` from the main checkout's `app/libs`, the MediaTek
`.so` pair into `app/src/main/jniLibs/arm64-v8a/`, `local.properties`) before `gradlew` will build.

---

## 3. THE COMMAND SEQUENCE (executed 13:40-14:07 exactly as written, with `WT=/c/Users/bastr/.androidbuild/wt-rung3b/...`; every adb call pinned to the Tab)

Executed in this order; the only deviations: the canary arm ran twice (pre-fix, then on the rebuilt probe — §2.2), and
`r3_info_1` (mode=info) ran first as the launch proof of the new build. Elapsed per arm: canary 4.7-4.9 s, the 3-clip
real-time arms 27-51 s, the max-pace arms 7-11 s, the load arms 7-44 s, the thermal run 600 s after a 5-minute idle.

Environment: Git Bash, `export MSYS_NO_PATHCONV=1`; `ADB=/c/Users/bastr/AppData/Local/Android/Sdk/platform-tools/adb.exe`
(adb is not on PATH); `S=192.168.1.161:44483`; the worktree probe `WT=/c/Users/bastr/.androidbuild/wt-rung3/tools/probes/litertlm-probe`;
`M=/data/user/0/com.whispereverywhere.probe/files/zipformer-en`; `SPR=<this session's scratchpad>/review`.

```sh
# 0. reconnect + pre-flight (if the port moved, `adb mdns services` shows the new one; the serial in every command follows it)
$ADB mdns services; $ADB connect $S; $ADB devices -l
$ADB -s $S shell "ls -la /data/local/tmp; dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"   # no r3/, no Play Protect dialog

# 1. install the rung-3 build (built already; rebuild from $WT with gradlew if the tree moved)
$ADB -s $S install -r /c/Users/bastr/.androidbuild/litertlm-probe/probe-sherpa-1.13.7.apk

# 2. push (never through the Play copy): models -> files/zipformer-en/, clips -> files/; hash on the device; clean tmp
$ADB -s $S shell mkdir -p /data/local/tmp/r3
for f in /c/Users/bastr/.androidbuild/streaming-models/en-2023-06-26/*.onnx /c/Users/bastr/.androidbuild/streaming-models/en-2023-06-26/tokens.txt \
         "/c/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/main/assets/canary_digits.wav" \
         "/c/Users/bastr/OneDrive/Desktop/whisper Everywhere/app/src/androidTest/assets/jfk.wav" \
         "$SPR/jfk-gated.wav"; do $ADB -s $S push "$f" /data/local/tmp/r3/; done
$ADB -s $S shell "run-as com.whispereverywhere.probe sh -c 'mkdir -p files/zipformer-en && cp /data/local/tmp/r3/*.onnx /data/local/tmp/r3/tokens.txt files/zipformer-en/ && cp /data/local/tmp/r3/*.wav files/ && sha256sum files/zipformer-en/* files/*.wav'; rm -rf /data/local/tmp/r3; ls -la /data/local/tmp"
#    -> the seven sha256s must equal §1.2

# 3. the arms (cd $WT; each is a fresh process = a cold load; ~5-40 s each except the thermal run)
python drive.py --pid --tag r3_canary_rt_t2_p500  mode=sherpa model=$M clips=canary_digits.wav threads=2 pace=realtime padms=500   # THE GATE: canary on 1.13.7
python drive.py --pid --tag r3_canary_rt_t2_p800  mode=sherpa model=$M clips=canary_digits.wav threads=2 pace=realtime padms=800   # pad-length arm
python drive.py --pid --tag r3_clips_rt_t2        mode=sherpa model=$M clips=canary_digits.wav,jfk.wav,jfk-gated.wav threads=2 pace=realtime padms=500 loops=2   # partial latency + WER + retractions + gated audio
python drive.py --pid --tag r3_clips_rt_t1        mode=sherpa model=$M clips=canary_digits.wav,jfk.wav,jfk-gated.wav threads=1 pace=realtime padms=500
python drive.py --pid --tag r3_jfk_max_t1         mode=sherpa model=$M clips=jfk.wav threads=1 pace=max loops=10                   # RTF, threads 1 / 2 / 4
python drive.py --pid --tag r3_jfk_max_t2         mode=sherpa model=$M clips=jfk.wav threads=2 pace=max loops=10
python drive.py --pid --tag r3_jfk_max_t4         mode=sherpa model=$M clips=jfk.wav threads=4 pace=max loops=10
python drive.py --pid --tag r3_jfk_max_t2_nospin  mode=sherpa model=$M clips=jfk.wav threads=2 pace=max loops=10 provider=nospin   # ORT spinning off (>= 1.13.5)
python drive.py --pid --tag r3_jfk_rt_t2_load4    mode=sherpa model=$M clips=jfk.wav,canary_digits.wav threads=2 pace=realtime loops=3 load=4   # beside a 4-thread busy loop
python drive.py --pid --tag r3_jfk_max_t2_load4   mode=sherpa model=$M clips=jfk.wav threads=2 pace=max loops=5 load=4
python drive.py --pid --tag r3_jfk_thermal_t2     mode=sherpa model=$M clips=jfk.wav threads=2 pace=realtime duration=600 --timeout 900   # the 10-minute run (RSS delta, thermal, drift)
python drive.py --pid --tag r3_jfk_max_t2_nnapi   mode=sherpa model=$M clips=jfk.wav threads=2 pace=max loops=3 provider=nnapi    # optional; expect fallback/slow

# 4. the 1.13.4 arm (same package; files/ persists across install -r), then back to 1.13.7
$ADB -s $S install -r /c/Users/bastr/.androidbuild/litertlm-probe/probe-sherpa-1.13.4.apk
python drive.py --pid --tag r3_1134_canary_rt_t2  mode=sherpa model=$M clips=canary_digits.wav threads=2 pace=realtime padms=500
python drive.py --pid --tag r3_1134_clips_rt_t2   mode=sherpa model=$M clips=jfk.wav,jfk-gated.wav threads=2 pace=realtime padms=500
python drive.py --pid --tag r3_1134_jfk_max_t2    mode=sherpa model=$M clips=jfk.wav threads=2 pace=max loops=10
$ADB -s $S install -r /c/Users/bastr/.androidbuild/litertlm-probe/probe-sherpa-1.13.7.apk

# 5. tables + proofs
python sherpa_summary.py --words > /c/Users/bastr/.androidbuild/probe-logs/r3_summary.md
grep -hE "sherpa-onnx|onnxruntime|PROBE.*sherpa\|(versions|file|config|load_ms)" /c/Users/bastr/.androidbuild/probe-logs/r3_canary_rt_t2_p500.filtered.log | head -60   # §7
$ADB -s $S shell "ls -la /data/local/tmp; dumpsys package com.whispereverywhere | grep -E 'versionName|versionCode|lastUpdateTime'"   # must read 4.3.2 / 86 / 2026-09-04 19:49:37
```

Rules that stand for the session: no `adb` to any other serial; never `uninstall com.whispereverywhere`; if a
Play Protect dialog is on screen, do not click through it — report and stop; the Tab's audio never leaves the
fixture clips; the probe stays installed at the end.

---

## 4. THE KILL LINES (§5.4) — the Tab column, from §5-§6

| Line | Metric | Kill at | **PC (rung 1, i7-8700K)** | **Tab (rung 3, MT6989, 1.13.7 / ORT 1.27.1)** | Verdict |
|---|---|---|---|---|---|
| canary, both AARs | `getResult().text` lower-cased == "one two three four five" | any mismatch → that AAR does not ship | `ONE TWO THREE FOUR FIVE`, WER 0.000 at pad ≥ 0.50 s (`FI` at 0.45) | **`ONE TWO THREE FOUR FIVE`** — exact (`canary_exact=true`, `canaryPasses=true`), WER 0.000, in all 8 canary runs of 1.13.7 (pad 500 ×7, pad 800 ×1, threads 1 and 2, with and without the busy load, and in the 55-run thermal arm's sibling canary after the reinstall); no `sme` in cpuinfo. **1.13.4: exact too** (`r3_1134_canary_rt_t2`, §8) | **PASS — both AARs** |
| partial latency, 2 threads | p95 over words, wall clock, real-time pace | > 1.0 s | 0.48 s **structural** (no compute queueing on the PC) | **p95 0.523 s** (p50 0.401, max 0.526, n = 100 words, `r3_clips_rt_t2`); on the audio clock p50 0.360 / p95 0.480 — identical to the PC — so the compute adds **~40 ms** at p50/p95 on this device (burst p50 38 ms per 320 ms decode). 1 thread: p95 0.530 | **PASS** (0.52× the line) |
| RTF, 2 threads | Σ decode / audio, max pace, 10 loops, sustained (last half) | > 0.5 | 0.080 (0.107 at 1 thread) | **0.054 mean, 0.054 last-half mean, 0.054 max** (`r3_jfk_max_t2`); 1 thread 0.051 / 0.052; 4 threads 0.072 / 0.073 (slower — see §10); nospin 0.076. At real-time pace (cold cores) Σ decode / audio is 0.110-0.125 | **PASS** (0.11× the line) |
| under a 4-thread load | RTF and p95 lag beside the busy loop | RTF > 0.5 or p95 > 1.0 s | — | real-time (`r3_jfk_rt_t2_load4`, 3 loops of jfk+canary): **RTF 0.099 mean / 0.106 max, lag p50 0.390 / p95 0.511 / max 0.514 s**, late chunks 2-10 per run (max lateness 10 ms), text unchanged; max pace (`r3_jfk_max_t2_load4`, 5 loops): RTF 0.076 mean / 0.079 max | **PASS** |
| WER, strip | `WerMath.wer` vs reference; `multi`'s 0.000 beside it | > 1.5× `multi` → strip-only; > 2× → stop | jfk 0.182 (4/22), jfk-gated 0.318 (7/22) — vs whisper-small 0.000 | **jfk 0.182 (4/22), jfk-gated 0.318 (7/22)** — the finals are byte-identical to rung 1's on every run at 1, 2 and 4 threads, so the device adds nothing to the WER. Beside `multi`'s 0.000 the ratio is not a number (any non-zero WER is > 1.5 × 0); read the way rung 1 read it and the research doc's decision rule asks (side-by-side: *wrong* vs *less polished*) — every content word of `jfk` survives, the four edits are unstressed monosyllables | **NOT A DEVICE FINDING — carried as rung 1 carried it (§5.2 there): by the ratio's letter it cannot pass; by the decision rule it did not kill.** Unchanged by this rung |
| RSS | `rssKb` cold → after the 10-minute run | > 400 MB delta | — | before load 180 MB → end of the 609 s run 349 MB: **+169 MB** (after load → end **+12.5 MB**; the highest per-minute snapshot, 371 MB at t+597 s, is +191 MB from cold) — §6 | **PASS** (0.42× the line) |
| thermal / battery | `thermalStatus` steps, `batteryTempTenths` over 10 min | > 1 step or > 3 °C | — | **24.6 → 25.8 °C (+1.2 °C)** over the 609 s run, `thermalStatus` **0 → 0 (0 steps)**, on the charger the whole time (the 5-minute idle before it had already drifted 23.9 → 24.7 °C on charge alone) — §6 | **PASS** |
| pad length | canary last-syllable loss at 500 vs 800 ms | pick the smaller pad with zero loss | 500 ms is the floor (§4 of rung 1) | **500 ms: `ONE TWO THREE FOUR FIVE`; 800 ms: identical.** Zero loss at 500 in every run; 800 buys nothing (the tail's `FIVE` lands at wall 2.60 s either way) | **500 ms stands** |
| edited video | boundary-word errors at the 16 in-speech gates | > 1 per 10 cuts | 3 edits over 16 cuts (0.19/cut, flagged) | **3 edits over 16 cuts (0.19/cut = 1.9 per 10)** — the same three (`AND SO→AN THO`, `AMERICANS ASK→AMERICA AS FOR`, `COUNTRY→CUTTER`), byte-identical to the PC | **over the line, exactly as on the PC** — a model property, not a device one; stays flagged, not newly killed (see §10) |
| retractions | partials whose predecessor is not their prefix | (the strip contract) | **0** in every greedy run | **0** in every one of the 134 runs of the session (62 across the 1.13.7 arms, 55 in the thermal run, 3 on `nnapi`, 13 on 1.13.4, 1 post-reinstall canary), 22 partials per `jfk`, 21 per `jfk-gated`, 4 per canary — cumulative text only grows | **PASS** |
| *(music bed)* | *§5.4 also lists a bed-clip line* | *(a) any full word per 10 s; (b) any token* | — | **not run** — no bed clip was pushed; this rung's §3 never planned it | open |

**On the thermal line's letter.** §5.4 says "10 min continuous speech, streamer + `multi` together, threads 1 and
2". What ran: the streamer alone at 2 threads for 600 s (§6), and the `multi` stand-in (four spinning threads) as its
own 41 s + 4 s arms. The 10-minute run beside the busy loop, and the 1-thread 10-minute run, were **not** run; §6's
verdict is for the streamer alone, and §10 says what that leaves open.

---

## 5. PER-CLIP / THREADS / ARM TABLES — `sherpa_summary.py` output, copied (1.13.7 / ORT 1.27.1, `sme=false`)

Columns: lag = per-word partial latency (wall clock, then audio clock); burst = one chunk's `decode()` drain; RTF
compute = Σ `decode()` / clip audio (feed + tail); RTF wall = total wall / audio (≈ 1.0 at real-time pace by
construction); late chunks = chunks fed after their 32 ms deadline because the previous burst overran (max lateness).

### 5.1 Real-time pace, 2 threads, pad 500 — the headline arm (`r3_clips_rt_t2`, 2 loops of the three clips)

| run | clip | final | WER (edits/ref) | canary | partials | retract | lag wall p50 / p95 / max s | lag audio p50 / p95 s | burst p50 / p95 / max ms | RTF compute | RTF wall | late chunks (max ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | canary_digits | `ONE TWO THREE FOUR FIVE` | 0.000 (0/5) | exact / pass | 4 | 0 | 0.401 / 0.523 / 0.523 | 0.360 / 0.480 | 39.2 / 44.4 / 44.4 | 0.1246 | 1.0197 | 7 (13.3) |
| 1 | jfk | `AND SAW MY FELLOW AMERICANS ASK NOT WHAT'S YOUR COUNTRY CAN DO FOR YOU AS BUT YOU CAN DO FOR YOUR COUNTRY` | 0.182 (4/22) | - | 22 | 0 | 0.401 / 0.522 / 0.526 | 0.360 / 0.480 | 39.8 / 44.5 / 45.5 | 0.1228 | 1.0059 | 32 (14.4) |
| 2 | jfk-gated | `AN THO MY FELLOW AMERICA AS FOR NOT WHAT YOU'RE CUTTER CAN DO FOR YOU ASK WHAT YOU CAN DO FOR YOUR COUNTRY` | 0.318 (7/22) | - | 21 | 0 | 0.405 / 0.523 / 0.526 | 0.360 / 0.480 | 38.3 / 44.9 / 45.1 | 0.1203 | 1.0054 | 32 (14.0) |
| 3 | canary_digits | `ONE TWO THREE FOUR FIVE` | 0.000 (0/5) | exact / pass | 4 | 0 | 0.402 / 0.507 / 0.507 | 0.360 / 0.480 | 32.4 / 41.0 / 41.0 | 0.1041 | 1.0184 | 4 (9.6) |
| 4 | jfk | (identical) | 0.182 (4/22) | - | 22 | 0 | 0.397 / 0.517 / 0.523 | 0.360 / 0.480 | 35.8 / 43.4 / 45.1 | 0.1097 | 1.0060 | 25 (13.7) |
| 5 | jfk-gated | (identical) | 0.318 (7/22) | - | 21 | 0 | 0.400 / 0.522 / 0.525 | 0.360 / 0.480 | 36.4 / 45.1 / 46.3 | 0.1146 | 1.0047 | 30 (15.1) |

- `r3_clips_rt_t2`: sherpa 1.13.7 / ORT 1.27.1, threads 2, provider `cpu`, pace realtime, pad 500 ms, load 0, sme=False; load 811 ms, warm-up first decode 19.9 ms; RSS/PSS MB before load 181 / 105, after load 341 / 252, after warm 346 / 264, end 341 / 259, after release 308 / 224; thermal start 22.2 C / 0, end 22.2 C / 0; runs 6 in 49.5 s; RTF compute mean 0.1160 max 0.1246 (last half mean 0.1095); **lag wall p50 0.401 p95 0.523 max 0.526 (n=100)**; lag audio p50 0.360 p95 0.480; burst p50 37.8 p95 44.7 max 46.3 ms; retractions 0; ok

**The partial-latency distribution, `jfk` run 1** (22 of 22 words mapped; `word_map_ok=true`): mean 0.389, min 0.240,
p50 0.401, p90 0.519, p95 0.522, p99 0.526, max 0.526 s. Per word (end = last token's timestamp; first = the first
partial whose k-th word equals the final's):

| word | end s | first partial wall s | lag wall s | lag audio s |
|---|---|---|---|---|
| and | 0.720 | 1.155 | 0.435 | 0.400 |
| saw | 1.080 | 1.481 | 0.401 | 0.360 |
| my | 1.440 | 1.799 | 0.359 | 0.320 |
| fellow | 1.920 | 2.439 | 0.519 | 0.480 |
| americans | 2.760 | 3.079 | 0.319 | 0.280 |
| ask | 4.120 | 4.365 | 0.245 | 0.200 |
| not | 4.400 | 4.683 | 0.283 | 0.240 |
| what's | 6.000 | 6.276 | 0.276 | 0.240 |
| your | 6.080 | 6.595 | 0.515 | 0.480 |
| country | 6.600 | 6.921 | 0.321 | 0.280 |
| can | 6.840 | 7.244 | 0.404 | 0.360 |
| do | 7.120 | 7.556 | 0.436 | 0.400 |
| for | 7.360 | 7.882 | 0.522 | 0.480 |
| you | 7.640 | 7.882 | 0.242 | 0.200 |
| as | 8.640 | 9.158 | 0.518 | 0.480 |
| but | 9.040 | 9.485 | 0.445 | 0.400 |
| you | 9.320 | 9.800 | 0.480 | 0.440 |
| can | 9.560 | 9.800 | 0.240 | 0.200 |
| do | 9.800 | 10.124 | 0.324 | 0.280 |
| for | 10.000 | 10.445 | 0.445 | 0.400 |
| your | 10.240 | 10.766 | 0.526 | 0.480 |
| country | 10.760 | 11.065 | 0.305 | 0.240 |

The 22 partials of that run, audio s → wall s (every one a prefix-growth of the previous; the last is the tail's):
`1.120→1.155 AND` · `1.440→1.481 AND SAW` · `1.760→1.799 … MY` · `2.080→2.124 … FELL` · `2.400→2.439 … FELLOW A` ·
`2.720→2.760 … AMERICAN` · `3.040→3.079 … AMERICANS` · `4.320→4.365 … ASK` · `4.640→4.683 … NOT` · `6.240→6.276 … WHAT'S` ·
`6.560→6.595 … YOUR COUNT` · `6.880→6.921 … COUNTRY` · `7.200→7.244 … CAN` · `7.520→7.556 … DO` · `7.840→7.882 … FOR YOU` ·
`9.120→9.158 … AS` · `9.440→9.485 … BUT` · `9.760→9.800 … YOU CAN` · `10.080→10.124 … DO` · `10.400→10.445 … FOR` ·
`10.720→10.766 … YOUR COUNT` · `11.000→11.065 (tail) … COUNTRY`. The wall-minus-audio gap is 35-45 ms on every
partial — one decode burst — which is the whole of the device's addition to the PC's structural lag.

`jfk-gated` run 2: 23 words mapped (the final has 23 words), lag wall mean 0.402, p50 0.405, p95 0.523, max 0.526 s;
21 partials, all prefix-growths; `CUTTER` first appears at audio 6.880 s (`C` at 6.240, `CUT` at 6.560), on the 64 ms
gate cluster at 5.856-6.944 s that rung 1 §6 named.

### 5.2 Real-time pace, 1 thread (`r3_clips_rt_t1`)

| run | clip | final | WER | canary | partials | retract | lag wall p50 / p95 / max s | lag audio p50 / p95 s | burst p50 / p95 / max ms | RTF compute | RTF wall | late chunks (max ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | canary_digits | `ONE TWO THREE FOUR FIVE` | 0.000 (0/5) | exact / pass | 4 | 0 | 0.406 / 0.532 / 0.532 | 0.360 / 0.480 | 45.7 / 57.2 / 57.2 | 0.1474 | 1.0207 | 7 (25.9) |
| 1 | jfk | (identical to 5.1) | 0.182 (4/22) | - | 22 | 0 | 0.399 / 0.527 / 0.532 | 0.360 / 0.480 | 46.0 / 52.1 / 59.3 | 0.1425 | 1.0064 | 33 (28.1) |
| 2 | jfk-gated | (identical to 5.1) | 0.318 (7/22) | - | 21 | 0 | 0.410 / 0.527 / 0.530 | 0.360 / 0.480 | 46.4 / 54.0 / 55.1 | 0.1452 | 1.0071 | 33 (23.7) |

- `r3_clips_rt_t1`: threads 1; load 821 ms, warm-up first decode 21.4 ms; RSS/PSS MB before load 178 / 119, after load 342 / 253, after warm 348 / 265, end 344 / 262, after release 313 / 230; thermal 22.2 C / 0 → 22.2 C / 0; runs 3 in 24.8 s; RTF compute mean 0.1450 max 0.1474; lag wall p50 0.406 p95 0.530 max 0.532 (n=50); burst p50 46.1 p95 54.0 max 59.3 ms; retractions 0; ok

### 5.3 The canary arms (pad 500 vs 800, 2 threads, real-time)

| tag | final | WER | canary | partials | retract | lag wall p50 / p95 / max s | burst p50 / p95 / max ms | RTF compute | late chunks (max ms) | word ends s |
|---|---|---|---|---|---|---|---|---|---|---|
| `r3_canary_rt_t2_p500` | `ONE TWO THREE FOUR FIVE` | 0.000 (0/5) | exact / pass | 4 | 0 | 0.401 / 0.514 / 0.514 | 37.0 / 40.6 / 40.6 | 0.1161 | 6 (9.2) | 0.96 / 1.28 / 1.48 / 2.04 / 2.68 |
| `r3_canary_rt_t2_p800` | `ONE TWO THREE FOUR FIVE` | 0.000 (0/5) | exact / pass | 4 | 0 | 0.381 / 0.515 / 0.515 | 37.0 / 49.1 / 49.1 | 0.1175 | 5 (18.6) | (same) |

Both: load 807-829 ms, warm-up first decode 21.8-22.7 ms; RSS/PSS MB before load 178-181 → after load 335-341 → end 342-343 → after release 311-312; 22.2 C / 0 throughout. The four partials are the same in both: `ONE` (audio 1.44 → wall 1.47), `ONE TWO THREE` (1.76 → 1.79), `… FOUR` (2.40 → 2.44), `… FIVE` (tail, 2.56 → 2.60-2.62). `FIVE`'s pieces are ` FI`+`VE` at 2.40 / 2.68 s — the second piece sits past the clip's 2.56 s end, inside the pad, which is why 450 ms lost it on the PC and 500 ms keeps it on both.

### 5.4 Max pace, `jfk` × 10 loops — the RTF table (rows: run 0 and run 9 of each arm; the tag line has all 10)

| arm | threads | provider | RTF compute mean / last-half mean / max | burst p50 / p95 / max ms | per-run examples (run 0 → run 9) | RSS MB after load → end | batt °C start → end |
|---|---|---|---|---|---|---|---|
| `r3_jfk_max_t1` | 1 | cpu | **0.0514 / 0.0518 / 0.0521** | 16.2 / 16.6 / 17.7 | 0.0502 → 0.0521 | 334 → 346 | 22.2 → 23.4 |
| `r3_jfk_max_t2` | 2 | cpu | **0.0539 / 0.0542 / 0.0544** | 17.0 / 17.3 / 19.0 | 0.0513 → 0.0544 | 338 → 352 | 23.4 → 23.4 |
| `r3_jfk_max_t4` | 4 | cpu | 0.0716 / 0.0726 / 0.0740 | 21.1 / 31.2 / 38.3 | 0.0673 → 0.0740 | 341 → 349 | 23.4 → 23.4 |
| `r3_jfk_max_t2_nospin` | 2 | `cpu:<cfg>` (spinning off, forwarded — sherpa echoes `Provider config: SessionConfig.session.intra_op.allow_spinning=0` and the `inter_op` twin) | 0.0756 / 0.0764 / 0.0776 | 23.9 / 25.1 / 31.3 | 0.0748 → 0.0754 | 341 → 347 | 23.4 → 23.4 |
| `r3_jfk_max_t2_load4` (5 loops) | 2 | cpu, 4 busy threads | 0.0758 / 0.0777 / 0.0793 | 23.7 / 27.6 / 32.4 | 0.0730 → 0.0793 | 336 → 347 | 23.4 → 23.4 |

Every run of every max-pace arm: final byte-identical, WER 0.182 (4/22), 22 partials, 0 retractions, 0 late chunks
(max pace has no deadline). Load 802-860 ms; warm-up first decode 19.8-27.5 ms (27.5 under nospin). The wall-clock
lag columns are negative at max pace (wall runs ~20× faster than audio) and mean nothing there; the audio-clock lag
is 0.360 / 0.480 in every arm, as it must be (chunk quantisation only).

### 5.5 Real-time pace beside the 4-thread busy loop (`r3_jfk_rt_t2_load4`, 3 loops of jfk + canary)

| run | clip | WER | canary | partials | retract | lag wall p50 / p95 / max s | burst p50 / p95 / max ms | RTF compute | late chunks (max ms) |
|---|---|---|---|---|---|---|---|---|---|
| 0 | jfk | 0.182 (4/22) | - | 22 | 0 | 0.388 / 0.510 / 0.511 | 29.0 / 32.6 / 37.4 | 0.0922 | 4 (6.0) |
| 1 | canary_digits | 0.000 (0/5) | exact / pass | 4 | 0 | 0.398 / 0.511 / 0.511 | 30.2 / 37.0 / 37.0 | 0.1036 | 3 (5.5) |
| 2 | jfk | 0.182 (4/22) | - | 22 | 0 | 0.390 / 0.511 / 0.512 | 29.1 / 32.1 / 37.1 | 0.0938 | 5 (5.7) |
| 3 | canary_digits | 0.000 (0/5) | exact / pass | 4 | 0 | 0.388 / 0.509 / 0.509 | 28.4 / 33.2 / 33.2 | 0.0982 | 2 (1.8) |
| 4 | jfk | 0.182 (4/22) | - | 22 | 0 | 0.391 / 0.512 / 0.514 | 30.2 / 34.8 / 41.5 | 0.0973 | 10 (10.0) |
| 5 | canary_digits | 0.000 (0/5) | exact / pass | 4 | 0 | 0.390 / 0.512 / 0.512 | 31.2 / 34.2 / 34.2 | 0.1060 | 3 (3.4) |

- `r3_jfk_rt_t2_load4`: threads 2, load 4; load 838 ms, warm-up first decode 21.0 ms; RSS/PSS MB before load 181 / 124, after load 341 / 253, after warm 344 / 263, under-load idle 345 / 263, end 342 / 260, after release 314 / 231; thermal 23.4 C / 0 → 23.4 C / 0; runs 6 in 41.1 s; RTF compute mean 0.0985 max 0.1060 (last half mean 0.1005); lag wall p50 0.390 p95 0.511 max 0.514 (n=81); burst p50 29.5 p95 33.9 max 41.5 ms; retractions 0; ok

Read beside 5.1: **under the busy load the real-time bursts got shorter** (29 ms vs 38 ms p50) and the late chunks
fewer (2-10 vs 25-32 per jfk) — the four spinning threads keep the cluster clocked up, so the decode thread no longer
wakes on a cold, down-clocked core every 32 ms. That is the same effect as the max-pace / real-time RTF gap (0.054
vs 0.116): on this SoC the streamer's cost at real-time pace is set by DVFS, not by the model. It also means the
busy-loop stand-in is *not* the worse case for latency on the Tab — see §10.

### 5.6 `provider=nnapi` (`r3_jfk_max_t2_nnapi`, 3 loops, max pace) — there is no NNAPI path in this AAR

sherpa echoes `provider="nnapi"` in its config, then logs, three times (encoder, decoder, joiner):
`Android NNAPI requires API level >= 27. Current API level 21 Fallback to cpu!` — the AAR's native code is compiled
against `android-21`, so the NNAPI execution provider is compiled out and the "nnapi" arm is a CPU arm: RTF 0.0512
mean / 0.0518 max, burst p50 15.9 / p95 17.0 / max 17.2 ms, same text, 0 retractions, load 827 ms. The research doc's
`provider ∈ {cpu, cpu:<cfg>, nnapi}` row therefore has two real members on this AAR, not three.

## 6. THE 10-MINUTE THERMAL RUN (`r3_jfk_thermal_t2`: `jfk` looped at real-time pace, 2 threads, pad 500, for 600 s)

Preceded by a 5-minute idle (13:49:16-13:54:16; battery 23.9 → 24.7 °C over the idle, on the charger); run 13:54:16-14:04:28.

- `r3_jfk_thermal_t2`: sherpa 1.13.7 / ORT 1.27.1, threads 2, provider `cpu`, pace realtime, pad 500 ms, load 0, sme=False; load 836 ms, warm-up first decode 21.3 ms; RSS/PSS MB before load 180 / 107, after load 337 / 253, after warm 346 / 263, end 349 / 264, after release 320 / 235; thermal start 24.6 C / 0, end 25.8 C / 0; **runs 55 in 608.8 s**; RTF compute mean 0.1168 max 0.1253 (last half mean 0.1165); **lag wall p50 0.399 p95 0.522 max 0.527 (n=1210)**; lag audio p50 0.360 p95 0.480; burst p50 38.1 p95 44.2 max 49.6 ms; retractions 0; ok
- first minute: runs 6, RTF mean 0.1188 max 0.1253, lag wall p50 0.397 p95 0.524 max 0.526, burst p95 44.7 max 48.9 ms, late chunks 181
- last minute:  runs 6, RTF mean 0.1155 max 0.1185, lag wall p50 0.398 p95 0.522 max 0.525, burst p95 43.8 max 45.5 ms, late chunks 171

| t (s) | runs done | RSS / PSS MB | batt C / thermal |
|---|---|---|---|
| before load | 0 | 180 / 107 | 24.6 C / 0 |
| 66 | 6 | 347 / 263 | 24.6 C / 0 |
| 132 | 12 | 350 / 266 | 24.6 C / 0 |
| 199 | 18 | 352 / 267 | 24.6 C / 0 |
| 265 | 24 | 355 / 270 | 24.6 C / 0 |
| 332 | 30 | 357 / 273 | 24.6 C / 0 |
| 398 | 36 | 360 / 276 | 25.8 C / 0 |
| 464 | 42 | 363 / 278 | 25.8 C / 0 |
| 531 | 48 | 368 / 283 | 25.8 C / 0 |
| 597 | 54 | 371 / 286 | 25.8 C / 0 |
| end | 55 | 349 / 264 | 25.8 C / 0 |

Derived from the 55 per-run records: **one distinct final** (the §5.1 `jfk` text), WER 0.182 in all 55, **0 retractions**;
RTF per run min 0.108 / mean 0.117 / max 0.125; per-minute RTF means 0.119, 0.117, 0.118, 0.115, 0.116, 0.121, 0.114,
0.120, 0.113, 0.116 — **no drift** over the ten minutes; late chunks 1,602 over 55 runs (29 per 344-chunk clip, as in
§5.1), max lateness 18.4 ms.

**The RSS reading.** The kill line is the cold → end delta: **+169 MB** (180 → 349), well under 400. Two things to say
about the shape: (1) the per-minute snapshots climb steadily, 347 → 371 MB over nine minutes (~2.7 MB/min), but the
`end` snapshot — taken after the run loop, before `release()` — is 349 MB, 22 MB *below* the last snapshot, so at least
that much of the climb was collectable Java garbage; and the probe itself accumulates every run's result JSON (55 runs ×
per-decode arrays, per-word rows, partial texts) in the Java heap for the whole run, which is the obvious source.
(2) `after load → end` is +12.5 MB and `after release` returns to 320 MB (before-load + 140 MB, i.e. the ORT/sherpa
libraries stay mapped after the recognizer is released, as after every other arm). A native leak in sherpa/ORT is
therefore not what this run shows, but it is also not excluded by a run in which the harness itself grows; a
longer run with the probe's own accumulation turned off is the clean test if anyone needs one.

**The thermal reading.** +1.2 °C over the run against a line of 3 °C, and 0 status steps against a line of 1 — on a
tablet sitting on its charger at 100 % (the charger alone added 0.8 °C during the idle before the run). The streamer
at 2 threads is ~12 % of one core at real-time pace (§5.1's RTF), which is why nothing moved.



## 7. THE PROVING LOG LINES (from `r3_canary_rt_t2_p500.filtered.log`, the post-fix run; every arm's log carries the same set)

The probe's own (`PROBE` tag, pid-filtered):

```
device|soc=Mediatek/MT6989|model=SM-X828U|sdk=36|nativeLibraryDir=/data/app/~~1RLS5pc8rMJNH3MrQEsZoQ==/com.whispereverywhere.probe-X-WqLXbJXjoB4cQ_bA5LQQ==/lib/arm64|libs=libLiteRt.so,libLiteRtCompilerPlugin_MediaTek.so,libLiteRtDispatch_MediaTek.so,libLiteRtOpenClAccelerator.so,liblitertlm_jni.so,libonnxruntime.so,libsherpa-onnx-jni.so
sherpa|cpuinfo|sme=false|features=fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve asimdfhm dit uscat ilrcpc flagm sb paca pacg dcpodp sve2 sveaes svepmull svebitperm svesha3 svesm4 flagm2 frint svei8mm svebf16 i8mm bf16 dgh bti ecv afp wfxt
sherpa|versions|sherpa=1.13.7|git=574210e0 Tue Sep 1 07:39:27 2026|onnxruntime=1.27.1
sherpa|file|encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx|bytes=71083163|sha256=563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1
sherpa|file|decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx|bytes=1307236|sha256=98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02
sherpa|file|joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx|bytes=259335|sha256=d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297
sherpa|file|tokens.txt|bytes=5048|sha256=49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb
sherpa|config|threads=2|provider=cpu|modelType=zipformer2|decoding=greedy_search|enableEndpoint=false|featureDim=80|dither=0|pace=realtime|padMs=500|loops=1|duration=0|load=0
sherpa|load_ms=829.4
sherpa|clip|canary_digits.wav|bytes=81998|samples=40960|audio_s=2.560|sha256=a3079109f735d4acea2756ce5398c67119ab36fa832571f5a5b45b616a7a5cd4|ref=true
sherpa|warmup|wall_ms=75.2|decode_ms=71.9|first_decode_ms=22.7|text=ONE
```

So `VersionInfo` reports **sherpa 1.13.7 (git 574210e0, 2026-09-01) on onnxruntime 1.27.1** at run time — the
binary-tag reading of §1.2 confirmed from inside the process — and the four device-side hashes equal §1.2's. The
`onnxruntime` library itself logs nothing under its own tag on Android; the only `onnxruntime` strings in logcat are
the probe's line above and the model metadata's `onnx.infer=onnxruntime.quant`.

sherpa's own (`sherpa-onnx` tag, `debug=true`) — the config echo from `online-recognizer.cc:newFromFile:300`,
reassembled from its 80-character pieces:

```
OnlineRecognizerConfig(feat_config=FeatureExtractorConfig(sampling_rate=16000, feature_dim=80, low_freq=20, high_freq=-400, dither=0, normalize_samples=True, snip_edges=False), model_config=OnlineModelConfig(transducer=OnlineTransducerModelConfig(encoder=".../files/zipformer-en/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", decoder=".../decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", joiner=".../joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"), paraformer=…, wenet_ctc=…, zipformer2_ctc=…, nemo_ctc=…, t_one_ctc=…, provider_config=ProviderConfig(device=0, provider="cpu", cuda_config=…, trt_config=…), tokens=".../tokens.txt", num_threads=2, warm_up=0, debug=True, model_type="zipformer2", modeling_unit="", bpe_vocab=""), lm_config=OnlineLMConfig(model="", scale=0.5, lodr_scale=0.01, lodr_fst="", lodr_backoff_id=-1, shallow_fusion=True), endpoint_config=EndpointConfig(rule1=EndpointRule(must_contain_nonsilence=False, min_trailing_silence=2.4, min_utterance_length=0), rule2=EndpointRule(must_contain_nonsilence=True, min_trailing_silence=1.4, min_utterance_length=0), rule3=EndpointRule(must_contain_nonsilence=False, min_trailing_silence=0, min_utterance_length=20)), ctc_fst_decoder_config=OnlineCtcFstDecoderConfig(graph="", max_active=3000), enable_endpoint=False, max_active_paths=4, hotwords_score=1.5, hotwords_file="", decoding_method="greedy_search", blank_penalty=0, temperature_scale=2, rule_fsts="", rule_fars="", reset_encoder=False, hr=HomophoneReplacerConfig(lexicon="", rule_fsts=""))
```

and the encoder metadata block from `online-zipformer2-transducer-model.cc:InitEncoder:111` (~0.8 s after the config
echo — the load):

```
---encoder---
num_heads=4,4,4,8,4,4
num_encoder_layers=2,2,3,4,3,2
cnn_module_kernels=31,31,15,15,15,31
model_type=zipformer2
T=45
model_author=k2-fsa
version=1
comment=streaming zipformer2
left_context_len=128,64,32,16,32,64
decode_chunk_len=32
value_head_dims=12,12,12,12,12,12
encoder_dims=192,256,384,512,384,256
onnx.infer=onnxruntime.quant
query_head_dims=32,32,32,32,32,32
```

`T=45` frames per decode with `decode_chunk_len=32` → one `decode()` per 320 ms of audio (35 decodes for 11.0 s +
0.5 s pad; 9 for the 2.56 s canary) — the cadence every partial in §5 sits on. `onnx.infer=onnxruntime.quant` appears
three times (encoder, decoder, joiner: all three are the int8 files).

## 8. THE 1.13.4 (ORT 1.27.0) ARM — three arms on the same `files/`, then back to 1.13.7

`adb install -r probe-sherpa-1.13.4.apk` at 14:05:15 (same `applicationId`; `files/` persisted, the model was reused);
`probe-sherpa-1.13.7.apk` re-installed at 14:06:04 and proven with one more canary (`r3_post_canary_1137`: `sherpa=1.13.7|…|onnxruntime=1.27.1`, `ONE TWO THREE FOUR FIVE`).

`VersionInfo` on 1.13.4: `sherpa=1.13.4|git=14280725 Tue Jul 7 11:21:34 2026|onnxruntime=n/a (NoSuchMethodException)` —
the getter does not exist on this AAR, exactly as §1.2 predicted, so its ORT version rests on the binary's
`VERS_1.27.0` symbol tag. Its JNI also returns the tokens with a leading space (`" ONE", " TWO", …`; `" AND", " SAW",
" MY", " FE", "LL", "OW"`), so §2.2's fix is needed for both AARs, and `word_map_ok=true` on all 13 runs.

| tag | run | clip | final | WER (edits/ref) | canary | partials | retract | lag wall p50 / p95 / max s | lag audio p50 / p95 s | burst p50 / p95 / max ms | RTF compute | RTF wall | late chunks (max ms) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| r3_1134_canary_rt_t2 | 0 | canary_digits | `ONE TWO THREE FOUR FIVE` | 0.000 (0/5) | exact / pass | 4 | 0 | 0.394 / 0.520 / 0.520 | 0.360 / 0.480 | 38.6 / 44.4 / 44.4 | 0.1206 | 1.0203 | 6 (13.0) |
| r3_1134_clips_rt_t2 | 0 | jfk | `AND SAW MY FELLOW AMERICANS ASK NOT WHAT'S YOUR COUNTRY CAN DO FOR YOU AS BUT YOU CAN DO FOR YOUR COUNTRY` | 0.182 (4/22) | - | 22 | 0 | 0.402 / 0.522 / 0.522 | 0.360 / 0.480 | 36.5 / 44.0 / 46.4 | 0.1110 | 1.0050 | 27 (15.1) |
| r3_1134_clips_rt_t2 | 1 | jfk-gated | `AN THO MY FELLOW AMERICA AS FOR NOT WHAT YOU'RE CUTTER CAN DO FOR YOU ASK WHAT YOU CAN DO FOR YOUR COUNTRY` | 0.318 (7/22) | - | 21 | 0 | 0.404 / 0.517 / 0.520 | 0.360 / 0.480 | 35.5 / 42.1 / 43.6 | 0.1102 | 1.0056 | 29 (12.3) |
| r3_1134_jfk_max_t2 | 0 | jfk | (identical) | 0.182 (4/22) | - | 22 | 0 | — (max pace) | 0.360 / 0.480 | 16.7 / 17.1 / 18.7 | 0.0533 | 0.0546 | 0 (0.0) |
| r3_1134_jfk_max_t2 | 9 | jfk | (identical) | 0.182 (4/22) | - | 22 | 0 | — (max pace) | 0.360 / 0.480 | 17.0 / 17.4 / 17.6 | 0.0543 | 0.0556 | 0 (0.0) |

- `r3_1134_canary_rt_t2`: load 801 ms, warm-up first decode 26.2 ms; RSS/PSS MB before load 179 / 103, after load 342 / 253, after warm 345 / 263, end 343 / 261, after release 313 / 230; 25.8 C / 0 throughout; retractions 0; ok
- `r3_1134_clips_rt_t2`: load 837 ms, warm-up first decode 20.8 ms; RSS/PSS MB before load 179 / 117, after load 342 / 254, after warm 348 / 265, end 344 / 261, after release 313 / 230; runs 2 in 22.1 s; RTF compute mean 0.1106 max 0.1110; lag wall p50 0.404 p95 0.520 max 0.522 (n=45); burst p50 35.7 p95 42.1 max 46.4 ms; retractions 0; ok
- `r3_1134_jfk_max_t2`: load 817 ms, warm-up first decode 22.5 ms; RSS/PSS MB before load 183 / 111, after load 342 / 253, after warm 346 / 263, end 348 / 265, after release 318 / 235; runs 10 in 6.1 s; **RTF compute mean 0.0542 max 0.0550 (last half mean 0.0544)**; burst p50 17.0 p95 17.6 max 20.4 ms; retractions 0; ok

**The delta, 1.13.7 (ORT 1.27.1) vs 1.13.4 (ORT 1.27.0), same device, same session, minutes apart:**

| | 1.13.7 | 1.13.4 | delta |
|---|---|---|---|
| canary | exact | exact | none |
| `jfk` / `jfk-gated` final | (§5.1) | byte-identical | none |
| WER jfk / gated | 0.182 / 0.318 | 0.182 / 0.318 | none |
| retractions | 0 | 0 | none |
| RTF max pace, 2 threads, 10 loops: mean / last-half / max | 0.0539 / 0.0542 / 0.0544 | 0.0542 / 0.0544 / 0.0550 | +0.6 % (noise; the thermal run sat between them and the Tab was 2.4 °C warmer) |
| burst p50 / p95 / max ms (max pace) | 17.0 / 17.3 / 19.0 | 17.0 / 17.6 / 20.4 | none |
| real-time lag p50 / p95 (jfk + gated) | 0.401 / 0.523 | 0.404 / 0.520 | none |
| real-time RTF (jfk) | 0.110-0.123 | 0.110-0.111 | none |
| load ms / warm-up first decode | 802-860 / 20-28 | 801-837 / 21-26 | none |
| RSS after load / after release MB | 335-342 / 308-321 | 342 / 313-318 | none |
| `provider=nospin` forwarded | yes (§5.4) | not tested here (§1.4 of the research doc: not forwarded on < 1.13.5) | — |
| `VersionInfo.onnxruntimeVersion` | `1.27.1` | absent | the one visible difference |

So on a device **without** FEAT_SME the two AARs are the same recognizer to every metric this rung takes; the
1.13.7 pin's reason (#3845, the SME/KleidiAI class) stays a reason for SME devices, which this Tab is not.


## 9. TEARDOWN PROOFS (14:06:35, after the last arm; every command `-s 192.168.1.161:44483`)

```
--- /data/local/tmp
drwxrwx--x 3 shell shell 3452 2026-09-10 13:41 .
drwxr-x--x 6 root  root  3452 2025-05-04 03:18 ..
drwxrwxr-x 5 shell shell 3452 2026-06-17 17:00 .studio          <- only .studio; r3/ was removed at 13:41 right after the copy
--- Play copy (dumpsys package com.whispereverywhere)
    versionCode=86 minSdk=26 targetSdk=36
    versionName=4.3.2
    lastUpdateTime=2026-09-04 19:49:37
      firstInstallTime=2026-08-30 14:57:15                       <- identical to §1.1: never touched
--- probe (dumpsys package com.whispereverywhere.probe)
    versionCode=1 minSdk=31 targetSdk=36
    versionName=0.1-probe
    lastUpdateTime=2026-09-10 14:06:04                            <- the 1.13.7 re-install; left installed
--- probe files (run-as)
files/canary_digits.wav 81998, files/jfk-gated.wav 352044, files/jfk.wav 352078, files/ort-nospin.cfg 104 (13:47)
files/zipformer-en/: the four model files, 13:41
files/results/: 79 JSONs; files/ in total 2,335,935 kB
--- battery: status 5 (full, on AC), level 100, temperature 263 (26.3 C)
--- focus: mCurrentFocus=…com.sec.android.app.launcher…LauncherActivity   <- no dialog at any point
--- adb devices -l: 192.168.1.161:44483 device (transport 14) + adb-R52XC00LL9K-MHdBME._adb-tls-connect._tcp (transport 13)
```

Post-reinstall proof that the installed probe is the 1.13.7 build: `r3_post_canary_1137` (14:06:30) logged
`sherpa|versions|sherpa=1.13.7|git=574210e0 Tue Sep 1 07:39:27 2026|onnxruntime=1.27.1` and `final=ONE TWO THREE FOUR FIVE`.
No `adb` command in the session named any serial but the Tab's; nothing was uninstalled; the Tab's microphone was never
opened (every clip is a file in `files/`); no text beyond the three fixtures' own words exists in any log.

The result JSONs and logs are outside the repo at `C:/Users/bastr/.androidbuild/probe-logs/r3_*.{json,filtered.log,full.log}`
(17 tags), with `sherpa_summary.py --words` written to `r3_summary.md` there (836 lines) and the thermal driver's
transcript in `r3_thermal_driver.log`.


## 10. CONCERNS AND OBSERVATIONS

1. **The two flagged lines are model lines, not device lines.** The Tab produced byte-identical finals to the PC on
   every clip at every thread count, so the WER-vs-`multi` line and the edited-video line carry exactly rung 1's
   verdicts: the ratio to 0.000 is not a number, and 3 boundary edits over 16 cuts is 1.9 per 10 against a line of
   1 per 10. Rung 1 argued both in words and proceeded; nothing measured here changes that argument either way. If
   the owner reads the gated line by its letter, it kills on the PC too — it did not, and this rung is not the
   place to re-litigate it.
2. **Real-time pace costs 2× the compute of max pace on this SoC, and a busy load makes it *cheaper*.** Σ decode /
   audio is 0.054 at max pace, 0.116 at real-time pace, 0.099 at real-time pace beside four spinning threads
   (§5.4-§5.5). The decode thread sleeps 32 ms between chunks, the core down-clocks, and the next 16 ms burst runs
   as 38 ms. The kill line is still cleared 4× over at the worst of these, but it means (a) the RTF column of §5.4
   is the *compute* budget, not the *energy* budget, (b) the busy-loop stand-in is a worse case for RTF and a
   *better* case for latency, so a `multi`-shaped follow-up (bursty, 2.3 s commits) remains the honest load test
   for the p95 line, and (c) an app-side decoder that batches two chunks (64 ms) would halve the wake-ups at the
   price of one chunk of lag — a decision for §3, not for this rung.
3. **Four threads are slower than two** (0.072 vs 0.054), and `nospin` slower still (0.076): ORT's intra-op pool
   on a 4×X4 + 4×A720 cluster pays for thread hand-offs on 16 ms bursts. Two threads is the right number for
   the app; one thread costs almost nothing (0.051 max pace; 0.145 real-time) and would be the choice if the tee
   has to sit beside whisper's four.
4. **The thermal line's letter was not fully executed** (§4's note): the streamer alone for 10 minutes (+1.2 °C,
   0 steps, §6), the busy load for 45 s. The Tab was on AC the whole session (the warmer case). A 10-minute run
   beside the busy loop is a 10-minute follow-up; with the streamer at ~12 % of a core and 2.4× headroom under
   the 3 °C line, it is not one this rung needs before §3 starts.
5. **`late chunks` is a queue-depth read, not a latency read.** 25-33 of 344 chunks per `jfk` arrive 10-28 ms
   late at real-time pace because the previous burst (38-46 ms) overran the 32 ms slot; the queue never exceeds
   one chunk (max lateness < one chunk period) and the lag columns already contain it.
6. **The music-bed line was not run**; no bed clip was pushed. It is a 5-minute addition once a clip is chosen.
7. **The probe's `files/` still holds the 2.25 GB of E3-E6 artefacts** plus this rung's 73 MB model and three clips;
   nothing was removed. The `ort-nospin.cfg` (3 lines) written by the nospin arm is also there.
8. **`provider=nnapi` is not a path** (§5.6): the AAR is built against API 21 and ORT's NNAPI EP is compiled out;
   sherpa falls back to CPU with a warning and the numbers are the CPU numbers. Nothing for the app to try there
   short of a custom AAR build, which is out of scope for a tee that already runs at 0.054.
9. **RSS creeps ~2.7 MB/min during the 10-minute run and drops 22 MB at the end** (§6) — consistent with the probe's
   own per-run result accumulation, not proven to be. If the app's streamer is ever seen growing, the clean test is
   this run with the harness's accumulation off.
10. **The tokens carry a leading space, not U+2581** (§2.2), on both AARs. Any app-side code that maps sherpa's
    `tokens` to words — the strip's per-word timing, if §3 builds one — must expect `" AND"`, not `"▁AND"`.
