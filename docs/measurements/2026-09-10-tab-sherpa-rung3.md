# Rung 3 — the sherpa-onnx streaming Zipformer on the Galaxy Tab S10+, in the probe app

Executes §5.4 rung 3 of `docs/superpowers/research/2026-09-09-streaming-local-tier-research.md`: `mode=sherpa` in
`tools/probes/litertlm-probe`, linking the **same AAR the app ships** (`sherpa-onnx-1.13.7.aar`), fed the app's
32 ms chunking with endpointing off, measured against the design document's kill lines. The PC baseline it
extends is `docs/measurements/2026-09-10-zipformer-en-pc-rung1.md`.

---

## STATUS: BLOCKED — the Tab dropped off wireless debugging at ~11:44 on 2026-09-10, before any run

Nothing in this rung has been measured on the device yet. The sequence was: worktree created, the Tab
connected (`adb connect 192.168.1.161:44483` → `connected`, `adb devices` listed it as `device`), the pre-flight
reads of §1.1 succeeded (11:36-11:41), the probe extension was written — and the model push was the first
command to come back `adb.exe: device offline`. From then on `adb connect` fails with WSA 10060 and `adb mdns
services` lists nothing (the Tab no longer advertises `adb-R52XC00LL9K-…`), i.e. wireless debugging is off or the
Tab is asleep, which only the owner can fix. The reconnect loop was stopped at 11:49 on the coordinator's
instruction.

What this file therefore is: (a) the facts that WERE read (§1), (b) exactly what was built, that it builds, and
how it measures (§2), (c) the command sequence that runs every arm when the Tab is back (§3), (d) the kill-line
table with the device column empty and the PC column filled from rung 1 (§4). §5-§7 are to be filled from
`sherpa_summary.py` output; nothing in them is to be invented.

**The push: nothing landed.** `adb shell mkdir -p /data/local/tmp/r3` was the command that returned `device
offline`, and the loop is `mkdir && for … push …`, so no file was pushed and `/data/local/tmp` should still hold
only `.studio` (its state at 11:40, §1.1). The first thing to do when the Tab is back is `ls -la /data/local/tmp`
and remove `r3/` if it somehow exists.

---

## 1. WHAT WAS READ BEFORE THE DROP (all 2026-09-10, 11:36-11:41 local)

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

## 2. WHAT WAS BUILT — `mode=sherpa` in the probe (branch `tools/tab-rung3`, worktree `C:/Users/bastr/.androidbuild/wt-rung3`)

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

---

## 3. THE COMMAND SEQUENCE FOR THE DEVICE SESSION (run in this order; every adb call pinned to the Tab)

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

## 4. THE KILL LINES (§5.4) — device column EMPTY until the arms run

| Line | Metric | Kill at | **PC (rung 1, i7-8700K)** | **Tab (rung 3)** | Verdict |
|---|---|---|---|---|---|
| canary, both AARs | `getResult().text` lower-cased == "one two three four five" | any mismatch → that AAR does not ship | `ONE TWO THREE FOUR FIVE`, WER 0.000 at pad ≥ 0.50 s (`FI` at 0.45) | NOT MEASURED | — |
| partial latency, 2 threads | p95 over words, wall clock, real-time pace | > 1.0 s | 0.48 s **structural** (no compute queueing on the PC) | NOT MEASURED | — |
| RTF, 2 threads | Σ decode / audio, max pace, 10 loops, sustained (last half) | > 0.5 | 0.080 (0.107 at 1 thread) | NOT MEASURED | — |
| under a 4-thread load | RTF and p95 lag beside the busy loop | RTF > 0.5 or p95 > 1.0 s | — | NOT MEASURED | — |
| WER, strip | `WerMath.wer` vs reference; `multi`'s 0.000 beside it | > 1.5× `multi` → strip-only; > 2× → stop | jfk 0.182 (4/22), jfk-gated 0.318 (7/22) — vs whisper-small 0.000 | NOT MEASURED | — |
| RSS | `rssKb` cold → after the 10-minute run | > 400 MB delta | — | NOT MEASURED | — |
| thermal / battery | `thermalStatus` steps, `batteryTempTenths` over 10 min | > 1 step or > 3 °C | — | NOT MEASURED | — |
| pad length | canary last-syllable loss at 500 vs 800 ms | pick the smaller pad with zero loss | 500 ms is the floor (§4 of rung 1) | NOT MEASURED | — |
| edited video | boundary-word errors at the 16 in-speech gates | > 1 per 10 cuts | 3 edits over 16 cuts (0.19/cut, flagged) | NOT MEASURED | — |
| retractions | partials whose predecessor is not their prefix | (the strip contract) | **0** in every greedy run | NOT MEASURED | — |

**On the WER line's ratio:** rung 1's whisper-small scores 0.000 on all three clips, so "1.5× multi" is not a
number on these fixtures; the device line is to be reported as absolute WER against the same references, with
`multi`'s known 0.000 beside it, and the verdict argued in words (the research doc's own decision rule reads the
side-by-side, not the ratio). Text is expected to be bit-identical across thread counts (rung 1) and — the real
question of this rung — across AARs.

---

## 5. PER-CLIP / THREADS / ARM TABLES — to be filled from `sherpa_summary.py`

_Not run. The per-run table has the columns: run | clip | final | WER (edits/ref) | canary | partials | retractions |
lag wall p50/p95/max | lag audio p50/p95 | burst p50/p95/max ms | RTF compute | RTF wall | late chunks (max ms);
the per-tag line carries load ms, warm-up first decode, RSS/PSS at the five checkpoints, thermal at start and end,
and the aggregate RTF / lag / burst distributions._

## 6. THE 10-MINUTE THERMAL RUN — to be filled

_Not run. `sherpa_summary.py` prints first-minute vs last-minute (RTF mean/max, lag p50/p95/max, burst p95/max,
late chunks) and the per-minute RSS/PSS/battery/thermal snapshots for `r3_jfk_thermal_t2`._

## 7. THE PROVING LOG LINES — to be filled

_Not run. Expected from `debug = true`: sherpa's `OnlineRecognizerConfig(...)` echo, the encoder metadata block
(`model_type=zipformer2`, `decode_chunk_len`, `T`, `num_encoder_layers`…), and the probe's own
`sherpa|versions|sherpa=1.13.7|…|onnxruntime=1.27.1` line (from `VersionInfo`), plus `sherpa|file|…|sha256=…` ×4
and `sherpa|cpuinfo|sme=false|…`._

---

## 8. CONCERNS, already visible

1. **The device is the whole rung.** Everything above is preparation; no kill line has a device number, and none
   is to be inferred from the PC (the research doc says outright that PC RTF is irrelevant).
2. **The real-time feed runs decode on the feed thread.** That is the right *measurement* (the partial's wall time
   is when the text exists), and it also models the queue honestly — a burst that overruns 32 ms delays the next
   chunk, which is exactly what the app's off-capture-thread decoder would see as queue depth — but it means the
   `late chunks` column is to be read beside the p95 lag, not in place of it.
3. **`load=4` is steadier than `multi`.** Four spinning threads on an 8-core MT6989 (4×X4 + 4×A720) is a worse
   case than whisper's bursty F ≈ 2.3 s commits; a PASS under it is conservative, a FAIL under it needs the
   `multi`-shaped follow-up before it kills anything.
4. **The Tab's wireless-debugging drop** is the same failure mode earlier Tab sessions recorded; a USB cable would
   remove it, but the rule that only serial `192.168.1.161:44483` is touched stands, so the owner would have to
   re-pair either way (and if the port moves, every command above takes the new serial).
5. **The probe's `files/` holds 2.25 GB of E3-E6 artefacts.** Not touched here; if the Tab is short of space for the
   73 MB model, `run-as … rm files/whisper_large_v3_turbo_30s_i8.tflite` (1.09 GB) is the candidate, on the
   owner's say-so only — the e2e measurements are already in the repo.
