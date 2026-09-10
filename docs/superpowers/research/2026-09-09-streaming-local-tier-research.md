# The streaming local tier — "word for word" on device, on the recognizer the APK already ships

Research synthesis, 2026-09-10. Owner ruling in force, verbatim (2026-09-09): **"my goal is word for
word"** — live transcription like the cloud providers, on device.

**Inputs.** Three maps (`st-map-sherpa.md`, `st-map-models.md`, `st-map-contracts.md`), three analyses
(design / quality / plan) and three adversarial refutations of them, all in the session scratchpad, plus
the prior research doc (`docs/superpowers/research/2026-09-09-tab-turbo-gemma-scribe-gemini-live-research.md`,
§2.3, §2.7, §4.1, §4.5 read first). Every code fact below is `file:line` on `main` at `2ecfd7e` (read via
`git show main:<path>`; the checkout is on `tools/tab-apu-probe`, whose `app/` tree is identical for every
file cited). AAR facts come from the pinned artefact `app/libs/sherpa-onnx-1.13.4.aar` (sha256
`03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780` = `app/build.gradle.kts:576`, recomputed
by two passes). Web facts carry a URL and the date read. **DOCUMENTED** = read in the checkout, the
artefact, or a primary source. **INFERRED** = reasoning over those facts, labelled at the sentence. Where a
number does not exist it says UNMEASURED; nothing here says "should work".

**How disagreements were ruled.** The three analyses agree on the recognizer, the model, the licence, the
endpointing and the measurement; they disagree on the *shape* — the design pass built the recognizer as
the session's committer (a new tier that types), the plan pass built it as a previewer beside whisper (a
tee that only paints the strip). §3 rules for the previewer first, on primary-source facts: the typed
transcript must not depend on a model with no out-of-domain WER (§4.1), the shipped runtime has a documented
silent miscompute on a census SoC (§1.4) and a previewer fails safe where a committer types nothing, and the
previewer touches one of the contracts map's eleven seams where the committer touches nine (§3.1). The
committer remains the phase-2 spec (§3.10). Refuted claims were dropped; weakened ones carry their caveat
in place (§7).

---

## 0. THE ANSWER IN ONE SCREEN

**Verdict — YES, build it, as a previewer first, English first, measure before scheduling.** The
sherpa-onnx AAR the app already ships for TTS carries a complete streaming transducer runtime with zero new
native bytes (§1). The one licence-clean small model, `streaming-zipformer-en-2023-06-26` (72.7 MB int8,
Apache-2.0), ties Whisper small on LibriSpeech (3.06/7.79 vs 3.4/7.6) and emits cumulative partials every
320 ms with a 450 ms lookahead — the cloud-live latency class the owner asked for (§2, §4). Build it as a
**tee beside the whisper tier** (Shape T): PCM mirrored to a sherpa `OnlineRecognizer` whose cumulative text
owns the bubble strip, while `LocalWhisperEngine` (or turbo) keeps every commit and resolution and the
typed transcript stays exactly today's (§3). Two gates come first: **the AAR must move from 1.13.4 to
≥ 1.13.5**, because the shipped ONNX Runtime 1.27.0 silently miscomputes the Zipformer2 encoder on FEAT_SME
CPUs — documented on SM8850, an app census family, and invisible on both test devices (§1.4, §6 R1) — and
**a one-day zero-code measurement on the Tab with stated kill lines** (§5.4), because no ORT-CPU RTF exists
for any streaming model on phone-class ARM (§4.3).

**What the user gets.** With English picked (or Auto on the English-only `pro` tier), lowercase unpunctuated
words appear on the bubble strip roughly 0.4–0.8 s behind their voice (INFERRED arithmetic on documented
constants, §4.2) — on every device, the Tab included, with nothing leaving the phone. The transcript that is
typed at stop is unchanged: whisper's, cased, punctuated, with numerals, in 100 languages on `multi`/turbo.
Auto on a multilingual tier, or any non-English pick, is byte-identical to today (§3.6). Not delivered:
multilingual word-for-word (the only Auto-capable streaming model is 682 MB and scores below the English
floor, §2.3), digits on the strip, Soniox-style per-token language switching.

**What it costs.** A 72,654,782 B download (four files at an immutable HF commit; the release tarball is
310 MB and must not be used, §3.7); zero new `.so` bytes but an AAR bump (three build lines + a digest + the
TTS suites + a Kokoro smoke, §1.4); one strip-ownership change that re-specs 3.7 G's two pin tests and its
anti-churn render rule (§3.4); a second CPU consumer beside whisper's bursts — RTF UNMEASURED, INFERRED
0.05–0.25 at two threads (§4.3); ≈ 9 engineering days plus 3 owner device sessions, after the one-day
measurement (§3.11). The optional 7.1 MB punctuation model has no readable weight licence today (§2.4).

**First step (this week, no app code).** (1) PC: run the en model over the canary wav and five minutes of
the owner's own dictation, diff against `multi` — the out-of-domain accuracy read that decides whether the
strip text would embarrass the app. (2) Tab: sideload k2-fsa's prebuilt
`sherpa-onnx-1.13.4-arm64-v8a-asr-en-zipformer2.apk` (own applicationId; the Play copy untouched) and
dictate — the feel. (3) Branch: bump the AAR to 1.13.7, run the TTS suites. (4) Then the probe app with the
kill lines (§5.4). Nothing is scheduled past the probe until its numbers exist.

**Owner rulings (six; R1–R3 gate code).**

| # | Ruling | Recommendation |
|---|---|---|
| R1 | Ship on a FEAT_SME device unverified? The bump is necessary but not shown sufficient for SM8850 (§1.4). Options: (a) obtain one S26-class device for the canary before promote; (b) ship with the load-time canary as the only guard, accepting "no live words" there until a field read | **(a) if a device is reachable; else (b), stated in the release notes as a known gap** |
| R2 | Strip ownership when a local producer is active: the "(N in queue)" label is (a) displaced, (b) shared, or (c) moved | **(a)** — the previewer's strip carries the pending text itself (§3.4); the `queue:` diag keeps the depth |
| R3 | Default for a fixed-English user with the pack installed: opt-in switch or default-on | **default-on, additive** (the whisper step stays the mandatory one); off on Auto with a multilingual tier |
| R4 | Auto semantics: Auto = whisper only, or Auto + en pack = English partials regardless | **Auto = whisper only** on multilingual tiers (English partials over Spanish speech would be garbage); Auto on `pro` = English, as `pro` already resolves it (`FloatingBubbleService.kt:2839-2841`) |
| R5 | Names: streaming tier = 4.4.0 and Gemini live = 4.3.4, or the reverse (the prior doc's O5, still open) | **streaming = 4.4.0** — the memory has called "4.4" streaming since 4.3.0 and this adds a download and a surface; either order satisfies the release-identity rule (§5.2) |
| R6 | Engine order: the streaming week before or after Gemini live's | **streaming first** — the owner's own 2026-08-27 rule ("local sovereignty outranks cloud features", 3.8 spec ruling 3) and the owner's stated goal; Gemini's PC-only days run in parallel either way |

---

## 1. WHAT IS ALREADY IN THE APK

### 1.1 The artefact and how it ships (DOCUMENTED, repo + AAR)

| Fact | Value | Where |
|---|---|---|
| Artefact | `sherpa-onnx-1.13.4.aar`, 48,847,529 B, fetched on demand from the GitHub release and sha256-checked on every build | `app/build.gradle.kts:572-595`; dependency `:809` |
| What the APK keeps | `lib/arm64-v8a/libonnxruntime.so` 21,688,912 B + `libsherpa-onnx-jni.so` 4,710,728 B = **26,399,640 B already shipping** (the C/CXX API libs and `libparakeet.so` are excluded, `:167-172`; arm64-v8a only, `:67`) | read from the built release APK |
| Sole consumer today | `tts/TtsEngine.kt:11-14` (`OfflineTts*` only), `numThreads = 4`, `provider = "cpu"`, absolute `filesDir` paths (`:827-848`) | repo |
| R8 | `-keep class com.k2fsa.sherpa.onnx.** { *; }` — package-wide, under `isMinifyEnabled = true` (`:125`); the AAR's own `proguard.txt` is empty | `app/proguard-rules.pro:72-76` |
| ONNX Runtime | **1.27.0** (`VERS_1.27.0` on every export; k2-fsa's own `onnxruntime-libs` build) | strings/symbol versioning on the extracted `.so` |
| Execution providers compiled in | **CPU and NNAPI only** — `OrtSessionOptionsAppendExecutionProvider_{CPU,Nnapi}` are the only appenders; no XNNPACK, QNN, CoreML source trees | symbol + `__FILE__` scan |
| sherpa's QNN path | **not compiled** (the `-DSHERPA_ONNX_ENABLE_QNN=ON` refusal string is present; no QNN runtime symbol) — `QnnConfig` on `OnlineTransducerModelConfig` is inert in this artefact | string scan |
| `NativeComputeGate` | serialises **whisper** only, inside `WhisperNativeBackend`'s methods (`transcription/NativeComputeGate.kt:6-37`; taken at `TranscriptionEngine.kt:283`, `:480`, `:521`); sherpa/TTS is outside it today and correctly so | repo |

Consequence: a streaming tier adds Kotlin and a model download. The build changes **only** if the AAR
version moves — which §1.4 says it must.

### 1.2 The streaming API present in `classes.jar` (DOCUMENTED, `javap -p` / `javap -c` on the extracted jar)

```kotlin
class OnlineRecognizer(assetManager: AssetManager? = null, val config: OnlineRecognizerConfig) {
    fun createStream(hotwords: String = ""): OnlineStream
    fun isReady(stream): Boolean;  fun decode(stream);  fun isEndpoint(stream): Boolean
    fun getResult(stream): OnlineRecognizerResult;  fun reset(stream);  fun release()
}
class OnlineStream(var ptr: Long = 0) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int);  fun inputFinished()
    fun setOption(key, value);  fun getOption(key): String;  fun release()
}
class OnlineRecognizerResult(val text: String, val tokens: Array<String>, val timestamps: FloatArray, val ysProbs: FloatArray)
```

| Config | Fields and THIS AAR's defaults (Kotlin defaults live in the bytecode, not the signature) |
|---|---|
| `OnlineRecognizerConfig` | `featConfig, modelConfig, lmConfig, ctcFstDecoderConfig, hr, endpointConfig, enableEndpoint = true, decodingMethod = "greedy_search", maxActivePaths = 4, hotwordsFile = "", hotwordsScore = 1.5f, ruleFsts = "", ruleFars = "", blankPenalty = 0f` — **no `reset_encoder` field**, and the v1.13.4 JNI never reads one |
| `OnlineModelConfig` | `transducer, paraformer, zipformer2Ctc, neMoCtc, toneCtc, tokens, numThreads = 1, debug, provider = "cpu", modelType, modelingUnit, bpeVocab` — `modelType` must be `"zipformer2"` for the en-2023-06-26 model (the compiled-in catalogue's type 6) |
| `OnlineTransducerModelConfig` | `encoder, decoder, joiner, qnnConfig` (inert here) |
| `EndpointConfig` | rule1 `(false, 2.4 s, 0)`, rule2 `(true, 1.4 s, 0)`, rule3 `(false, 0, 20 s)` — **not upstream's CLI 5.0/2.0/20.0**, and an order of magnitude above the app's `HANGOVER_MS = 350L` (`audio/EndpointerTuning.kt:87`) |
| `OnlinePunctuation(assetManager?, OnlinePunctuationConfig(OnlinePunctuationModelConfig(cnnBilstm, bpeVocab, numThreads, debug, provider)))` | `addPunctuation(String): String` — Kotlin + JNI present (§2.4) |
| Also present | `Vad`/`SileroVadModelConfig` (wants a `silero_vad.onnx` the app does not ship — redundant with the native Silero in whisper.cpp, `transcription/VadModel.kt:8,:16`); `SpokenLanguageIdentification` (offline, needs its own whisper tiny/base int8 pair); `OnlineSpeechDenoiser`; `VersionInfo.getVersion()` for the diag line |

Facts a caller must know: `assetManager = null` routes to `newFromFile` (absolute paths, as TTS does);
`decodeStreams` exists in the `.so` but has **no Kotlin binding** (one stream at a time); nothing is
documented thread-safe (one thread drives one stream); `getResult().text` is the **cumulative text of the
open segment** until `reset` — the replace-only strip's exact contract (`service/FloatingBubbleService.kt:2961`).

### 1.3 The JNI halves and model implementations in the shipped `.so` (DOCUMENTED, `llvm-nm` + string scan)

`Java_com_k2fsa_sherpa_onnx_OnlineRecognizer_{newFromFile,newFromAsset,createStream,decode,decodeStreams,isReady,isEndpoint,getResult,reset,delete,prependAdspLibraryPath}`,
`OnlineStream_{acceptWaveform,inputFinished,setOption,getOption,hasOption,delete}`,
`OnlinePunctuation_{newFromFile,newFromAsset,addPunctuation,delete}`, `Vad_*`, `SpokenLanguageIdentification_*`.
Compiled model sources include `online-zipformer2-transducer-model.cc`, `online-zipformer2-ctc-model.cc`,
`online-paraformer-model.cc`, `online-nemo-ctc-model.cc`, `online-transducer-nemo-model.cc` (with the
`auto_prompt_id` / `prompt_dictionary` strings — the Nemotron-3.5 auto-language path), `online-cnn-bilstm-model.cc`
(punctuation), `online-transducer-modified-beam-search-decoder.cc`. The AAR's compiled-in `getModelConfig`
helper names 42 streaming model directories (en, zh, zh-en, fr, ko, es/de/fr/en Kroko, ru, bn, NeMo/Nemotron en).

### 1.4 The runtime defect that forces a version bump (DOCUMENTED, primary sources read 2026-09-10)

| Fact | Source |
|---|---|
| On **SM8850** (Xiaomi 17, Android 16, `/proc/cpuinfo` `sme smei8i32 smef16f32 smeb16f32 smef32f32`), a streaming Zipformer through `OnlineRecognizer` returns **empty text for the whole stream**; encoder output sum −14.069 on ORT 1.27.0 vs +30.029 on x86 and on ORT 1.28.0; decoder/joiner correct; "no crash, no exception, no NaN". Open; maintainer's only reply (2026-09-04): test master | sherpa-onnx #3845 (opened 2026-08-07) — https://github.com/k2-fsa/sherpa-onnx/issues/3845 |
| Same class on Apple M4 (FEAT_SME): ORT 1.27.0's KleidiAI `ConvolveSme` is wrong for Conv `pads=[0,1,0,1]` — the Zipformer2 frontend's first node; en-2023-06-26 transcribes "what time is it right now" as `"MY WOMAN"`; **fixed in ORT 1.27.1** (microsoft/onnxruntime PR #28571, merged 2026-06-19; "Confirmed on an M4 Pro … with 1.27.1", 2026-08-09) | sherpa-onnx #3791 (2026-07-23) — https://github.com/k2-fsa/sherpa-onnx/issues/3791 |
| sherpa-onnx **1.13.5 (2026-08-11)**: "Update onnxruntime to v1.27.1 (#3861)"; `build-android-arm64-v8a.sh:93` = 1.27.0 at v1.13.4, **1.27.1 at v1.13.5 and v1.13.7**; 1.13.7 released 2026-09-01, `sherpa-onnx-1.13.7.aar` = 49,113,869 B | https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/CHANGELOG.md ; `gh api repos/k2-fsa/sherpa-onnx/releases` (2026-09-10) |
| **SM8850-AD is an app census family** (`8elite5_galaxy`, HTP v81) | `npu/NpuFleetCensus.kt:141-150` |
| Neither test device can reproduce it: Fold6 = SM8650-AC (1×X4 + 5×A720 + 2×A520), Tab = MT6989 (4×X4 + 4×A720) — no FEAT_SME (secondary source for the core feature set: https://en.wikipedia.org/wiki/List_of_ARM_processors, 2026-09-10; the probe's `cpuinfo` read closes it per device) | https://www.gsmarena.com/samsung_galaxy_z_fold6-13147.php (2026-09-10); prior doc §1.1 (owner's adb read) |
| The TTS consumer survives the bump: `OfflineTtsKokoroModelConfig` is byte-identical at v1.13.4 and v1.13.7; the only `Tts.kt` change is a new `require(ptr != 0L)` in the `OfflineTts` constructors, and both app call sites are already guarded (`TtsEngine.kt:165` `runCatching`; `:225-755` `try/catch`) | `kotlin-api/Tts.kt` at both tags (GitHub contents API, 2026-09-10) |

**The honest reading.** The shipped AAR is documented-broken for this model class on one SM8850 device.
The bump to ≥ 1.13.5 is **necessary** (it is the only lever) but **not shown sufficient** for SM8850: nobody
has run 1.27.1 on an SM8850, and #3845 reports `mlas.disable_kleidiai=1` made "no difference" — though that
test may have run on the 1.13.4 AAR, which parses that key and never forwards it (forwarding landed in #3792,
merged 2026-07-24, i.e. 1.13.5). So: bump, **and** a load-time canary through the recognizer (§3.9), **and**
ruling R1. "Zero build-file change" no longer holds; "zero new native payload" still does (the two `.so`
files are replaced, ~+0.3 MB by the AAR delta).

Two capabilities the bump also unlocks (DOCUMENTED): on ≥ 1.13.5 sherpa forwards every
`SessionConfig.<key>=<value>` line of a `provider = "cpu:<cfg-path>"` file to `Ort::SessionOptions::AddConfigEntry`
(v1.13.7 `session.cc:201-228`), so `session.intra_op.allow_spinning=0` — ORT's default-on busy-wait that
"consumes more CPU cycles, resources, and power" (https://onnxruntime.ai/docs/performance/tune-performance/threading.html,
2026-09-10) — becomes reachable from Kotlin; and 1.13.5 re-exported the Nemotron-3.5 streaming model.

---

## 2. THE MODELS

### 2.1 Streaming models the shipped runtime can load, per language (DOCUMENTED sizes; all reads 2026-09-09/10)

Sizes are on-disk int8 unless stated; `tar` = the compressed GitHub release asset
(`gh api repos/k2-fsa/sherpa-onnx/releases/tags/asr-models`). Docs page:
https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html.

| Model | Lang | Files / size | Published WER | Licence (HF tag) | Punctuation from the ASR |
|---|---|---|---|---|---|
| **`streaming-zipformer-en-2023-06-26`** (chunk-16-left-128, 66.11 M) | en | encoder int8 71,083,163 + decoder 1,307,236 + joiner 259,335 + `tokens.txt` 5,048 = **72,654,782 B**; tar 310,414,022 B (fp32 + int8 + wavs) | LS **3.06 / 7.79** (320 ms greedy, chunk-wise); 3.05/7.69 beam; 2.81/7.15 at 640 ms | **apache-2.0** (`csukuangfj/…`, commit `672fbf1b…`, 2024-01-31); LibriSpeech CC-BY-4.0 | none — all-caps, unpunctuated; the vocabulary has **no digits** (500 uppercase BPE pieces + `<blk> <sos/eos> <unk>`; only `#0 #1` contain a digit) |
| `streaming-zipformer-en-20M-2023-02-17` | en | ≈ 42 MB int8 (tar 127,887,156 B); 53.4 MB as a prebuilt APK | LS 3.94 / 9.79 | **apache-2.0** (LibriSpeech, `desh2608` small recipe) | none |
| `streaming-zipformer-fr-2023-04-14` | fr | ≈ 123 MB int8 (tar 398,444,115 B) | not published | apache-2.0 (`shaojieli/…`; no training-data statement) | none |
| `streaming-zipformer-bilingual-zh-en-2023-02-20` | zh + en | ≈ 190 MB int8 (tar 511,274,346 B) | not published | apache-2.0 ("internal multilingual dataset") | none |
| `streaming-zipformer-small-ru-vosk-2025-08-16` / `bn-vosk-2026-02-09` | ru / bn | ru int8 tar 24,110,855 B; bn fp32 ≈ 90 MB | not published | apache-2.0 (`alphacep/…`; also `uz`) | none |
| `streaming-zipformer-{en,de,fr,es}-kroko-2025-08-06` | en/de/fr/es, one each | tars 57,267,600 / 57,565,698 / 57,220,361 / 124,394,665 B | not published; repeated digits "inconsistent or merged" (sherpa-onnx #3443, 2026-03-30, open) | **CC-BY-SA community models**; commercial/OEM licence sold separately; the sherpa mirror carries no tag (https://huggingface.co/Banafo/Kroko-ASR, 2026-09-10) | none |
| `streaming-zipformer-korean-2024-06-16` | ko | ≈ 126 MB | not published | **no licence field** (KsponSpeech) | none |
| PengChengStarling `…-ar_en_id_ja_ru_th_vi_zh-2025-02-10` | 8 | ≈ 340 MB (tar 258,999,581 B) | beats large-v3 on 5/6 own sets (vi 7.09 vs 17.94) | **none** (`gh api repos/yangb05/PengChengStarling` license null, 2026-09-10) | none; language by `<langtag>` input |
| `nemotron-speech-streaming-en-0.6b` | en | 600 M; 471 MB APK / tar 463,945,051 B | LS 2.32/4.84; leaderboard avg **6.93** (1.12 s) / 7.07 (0.56 s) / 7.67 / 8.43 | NVIDIA Open Model License ("ready for commercial/non-commercial use") — https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b (2026-09-10) | **yes, native** |
| **`nemotron-3.5-asr-streaming-0.6b`** | 40 locales / 19 ready | int8 **682,215,356 B** (encoder 657,601,403 + decoder 14,978,075 + joiner 9,504,438 + tokens 131,440); tar 475,271,763 B | FLEURS en **7.91 LangID / 8.84 auto** (1.12 s); es 4.11, it 4.25, pt 5.48, de 8.31, fr 9.03, ru 9.17; hi 6.81 / 8.23 auto | **OpenMDW-1.1**, "ready for commercial use" — https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b (2026-09-10) | **yes, native**; `target_lang=auto` detects per utterance and emits a language tag |
| `x-asr-*ms-streaming-zipformer-transducer-zh-en-2026-06-05-punct` | zh + en | int8 tar 133,898,007 B | not published | not verified | the only Zipformer package with punctuation from the ASR |
| streaming Paraformer bilingual / trilingual (zh-yue-en) | zh(+yue)+en | ≈ 226–228 MB | not published | FunASR `MODEL_LICENSE`, commercial-silent | — |

Not available as streaming through sherpa-onnx (searched, not found): SenseVoice, Whisper, Moonshine
(offline only); no streaming de/es/ja Zipformer from icefall (Kroko is the only de/es).

### 2.2 English WER — what is comparable and what is not (DOCUMENTED)

| System | LS clean | LS other | Params | Streams? | Source |
|---|---|---|---|---|---|
| streaming Zipformer en 2023-06-26, 320 ms, greedy | **3.06** | **7.79** | 66.11 M | yes | icefall `egs/librispeech/ASR/RESULTS.md:811-824` (2026-09-10) |
| same, 640 ms export (a different download) | 2.81 | 7.15 | 66.11 M | yes | same, `:829` |
| 20 M streaming Zipformer | 3.94 | 9.79 | ~20 M | yes | same, `:1391` |
| Whisper small (`multi`, 190,085,487 B) | **3.4** | **7.6** | 244 M | no | Whisper paper Table D.1.1 — https://ar5iv.labs.arxiv.org/html/2212.04356 (2026-09-10) |
| Whisper small.en (`pro`) | 3.1 | 7.4 | 244 M | no | same |
| Whisper large-v2 | 2.7 | 5.2 | 1,550 M | no | same |
| large-v3-turbo (`npu-turbo`) | not published | — | 809 M | no | leaderboard mean **7.83** — https://huggingface.co/openai/whisper-large-v3-turbo (2026-09-10) |
| nemotron-speech-streaming-en-0.6b, 1.12 s | 2.32 | 4.84 | 600 M | yes | model card (2026-09-10) |

**The caveat that travels with every Zipformer number:** the shipped export is converted from
`Zengwei/icefall-asr-librispeech-streaming-zipformer-2023-05-17` and "supports only English as it is trained
on the LibriSpeech corpus" (docs page + HF README, 2026-09-10) — LibriSpeech is **in-domain** for it and
zero-shot for Whisper. **No out-of-domain WER exists for it anywhere read** (sherpa page, HF card, icefall
RESULTS; the GigaSpeech recipe publishes only a non-streaming Zipformer, 10.31/10.50). Whisper small's
out-of-domain rows are: TED 4.3, WSJ 4.0, CallHome 17.5, Switchboard 14.5, Common Voice 5.1 13.5, CORAAL 18.1,
CHiME6 29.3, AMI-IHM 19.0, AMI-SDM1 39.6, VoxPopuli 8.3, FLEURS-en 6.6. INFERRED expectation, to be
falsified by the first experiment (§5.4): a 66 M model on 960 h of read speech degrades faster than Whisper
on conversational, accented and noisy audio — possibly 1.5–2× on the Common Voice / CallHome class. The
only streaming model with published out-of-domain numbers that beat Whisper small on every shared set is
Nemotron-en (TED 3.50, VoxPopuli 7.91, AMI 11.73, LS-other 4.84) — 600 M params, CPU-unmeasured, INFERRED
RTF 0.3–0.5 with every big core busy, i.e. not a universal-floor candidate.

### 2.3 Beyond English (DOCUMENTED numbers, non-comparable sets)

Multilingual word-for-word on device today means Nemotron-3.5: 682 MB, OpenMDW-1.1, a real per-utterance
Auto mode — and **7.91 (LangID) / 8.84 (auto) on FLEURS-en against Whisper small's 6.6** (large-v2 4.4), so
"multilingual and streaming" costs 1.3–2.2 pt of English accuracy relative to today's floor, at 9× the
parameters and no CPU number. Its Qualcomm QNN context binaries are published for three of the four census
families (SM8650, SM8750, SM8850 — `asr-models-qnn-binary-3`, 2026-07-09; not SM7750) with no published
latency, and sherpa's QNN path is not compiled into the shipped AAR (§1.1). Every other language on the
picker stays `multi`. The bilingual zh-en and trilingual Paraformer models need no language input and honour
Auto *within their pair* — a narrow exception with no published WER.

### 2.4 Punctuation and casing (DOCUMENTED)

`sherpa-onnx-online-punct-en-2024-08-06`: an online CNN-BiLSTM text→text model, `model.int8.onnx` 7.1 MB +
`bpe.vocab` 146 KB, `"how are you i am fine thank you"` → `"How are you? I am fine. Thank you."`, 0.013 s
int8 on the docs' machine, "designed for English only", "from the Edge-Punct-Casing repository"
(https://k2-fsa.github.io/sherpa/onnx/punctuation/pretrained_models.html, 2026-09-10). Release tar
30,667,839 B, sha256 `9f5e5a72c7d2829635bd074fce92b6bbd5b78da8a52e7ad8ed1be933f366b99d`
(`punctuation-models/checksum.txt`, 2026-09-10). Kotlin class + JNI in the shipped AAR (§1.2, §1.3) — zero
new native bytes. **Licence: the source repo `frankyoujian/Edge-Punct-Casing` is Apache-2.0 (GitHub API,
2026-09-10) but is code only — no weights, no checkpoint link, a two-line README; the converted artefact's HF
page returns 401; the paper (arXiv 2407.13142) names IWSLT2011 for evaluation and no training corpus.** The
weights' licence is unreadable today; it is an owner/counsel item, not a formality. Inverse text
normalisation: the `ruleFsts`/`ruleFars` mechanism is in the `.so`; the only published rule file is
`itn_zh_number.fst` (Chinese) — English numbers stay words on any streaming output.

### 2.5 The licence verdict for a paid app

| Row | Verdict |
|---|---|
| `streaming-zipformer-en-2023-06-26` (Apache-2.0, LibriSpeech CC-BY-4.0) | **SHIP** — the v1 model |
| `streaming-zipformer-en-20M-2023-02-17` (Apache-2.0, LibriSpeech) | ship-able; a low-end rung if the 66 M model fails the RTF line |
| fr-2023-04-14, zh-en bilingual, ru/bn/uz vosk (Apache-2.0 tags) | ship-able at the tag; **no WER**; an HF tag is the mirror owner's tag and does not launder a corpus agreement — counsel per row before a pack |
| Nemotron-en (NVIDIA OML), Nemotron-3.5 (OpenMDW-1.1) | ship-able, heavy (471 / 682 MB), CPU-unmeasured; 3.5 regresses English |
| Kroko en/de/fr/es | **not as-is** — CC-BY-SA copyleft-on-adaptation ambiguity for an APK bundle; buy Banafo's commercial licence or skip |
| Korean 2024-06-16, PengChengStarling | **blocked** (no licence); the single highest-leverage owner email is asking `yangb05` for Apache-2.0 on PengChengStarling |
| online-punct-en | **hold** until the weights' licence is readable |
| the AAR itself | Apache-2.0, shipping already |

The prior read's "exactly one licence-clean small streaming model" was **refuted** (§7): for English there
are two (66 M and 20 M), and fr / zh-en / ru / bn carry Apache-2.0 tags. What survives: **no small streaming
model honours Auto**; the one that does is 682 MB.

---

## 3. THE DESIGN

### 3.1 The shape: previewer (Shape T) first, committer (Shape C) second — the ruling and its evidence

| | Shape T — the tee (chosen for v1) | Shape C — the committer (phase 2) |
|---|---|---|
| Who types | whisper (`multi`/`pro` on CPU, turbo on NPU), exactly today | the transducer's finals |
| What the strip shows | the transducer's cumulative text, ~0.4–0.8 s behind speech | same |
| Session kind | local; `sessionIsLive = false`; client VAD, wall cap, flatline cut, speech evidence, governor, cadence table, seq contract **untouched** (`transcription/live/RealtimeTurnPolicy.kt:19`; the gate at `FloatingBubbleService.kt:2027`; `engine.sendAudio` unconditional at `:2005-2006`) | local, `sessionIsLive = false` too (the design pass's C3, verified) — but its own seq/outcome machine, a cadence row, batch refusal, catalog runtime |
| Seams touched (contracts map's eleven) | **one** (strip ownership, `:277`) + a language gate + a separate pack manager | nine: strip, engine-choice arm, seq/outcome, cadence row (`CommitCadencePolicy.kt:231` falls through to 8,000 ms), catalog `runtime` reaching **nine** sites (`WhisperModel.kt:435`, `:401-402`, `:445-447`; `WhisperModelManager.kt:70-79`, `:104-108`, `:149-151`, `:281-284`; `BatchLocalModel.kt:64-65`; `OnboardingModelScreen.kt:650`) plus the unconditional `warmLocalEngine` at `:2605` that would prewarm whisper on `encoder.onnx` |
| Out-of-domain accuracy risk (UNMEASURED, §2.2) | confined to the strip; the typed text cannot regress | the typed text depends on a model with no out-of-domain WER |
| The SME miscompute (§1.4) | fails safe: no/garbage partials, whisper's final still lands; the canary switches the preview off | the session types nothing; needs canary + fallback-to-whisper plumbing (a new local→local valve) |
| Punctuation / casing / numerals / 100 languages on the final | free — whisper's | the 7.1 MB punct model (licence unreadable) for casing/punctuation; numbers stay words; English only |
| CPU | **two consumers** — the streamer's steady load plus whisper's bursts (the Tab is the hard case: `multi` at RTF ≈ 0.34 on 4 threads, prior doc §1.1) — the probe's "under load" row | one consumer; lower duty; the only argument for C that T cannot answer |
| Strip vs window | lowercase strip, cased window — a divergence no cloud provider produces (measured by the rung-1 side-by-side) | one text |
| Days (INFERRED) | ≈ 9 + the measurement | ≈ 12–14 + the measurement |

**Ruling (INFERRED over the documented rows):** T first. The owner's sentence is about what they *see* while
speaking, and delivery is final-only (`FloatingBubbleService.kt:3492-3504`: "NOTHING leaves the app until
stopRecording's `deliverFinalTranscript`"; the one external write at `:3513-3515`), so the strip is the only
surface where latency is felt — T closes the goal without putting the typed transcript on an unmeasured
model or an unproven runtime. T's `StreamingPreviewEngine` and pack manager are reused verbatim by C; nothing
in T forecloses C. C is built when the rung-1 WER diff earns it (§5.4).

### 3.2 The engine (INFERRED design over DOCUMENTED contracts)

Two classes in `transcription/stream/`:

- **`StreamingPreviewEngine`** — owns one `OnlineRecognizer` + one `OnlineStream` on its own single-thread
  executor (the `TtsEngine.kt:44` ownership pattern: `@Volatile` handle, one thread touches the native
  object, explicit `release()`, idle auto-unload). Behind a thin `Recognizer` seam (`createStream / accept /
  isReady / decode / result / release`) so the loop is JVM-testable with a fake, as `WhisperBackend` lets
  `LocalWhisperEngine` be tested (`TranscriptionEngine.kt:154`). Production config: `OnlineRecognizer(assetManager = null, …)`
  (the `newFromFile` branch), `provider = "cpu"`, `modelType = "zipformer2"`, **`enableEndpoint = false`**,
  `decodingMethod = "greedy_search"`, `numThreads = 2`.
- **`PreviewTeeEngine(preview, local): TranscriptionEngine`** — the `FallbackTranscriptionEngine` shape
  (`transcription/cloud/FallbackTranscriptionEngine.kt:69-100`, `:195-200`: "sendAudio never blocks … two
  buffer appends under one lock, then return"): `sendAudio` forwards to `local` first (the `CapSeamPinTest`
  order) then offers the chunk to the preview's **bounded** queue (capacity 128 chunks ≈ 4 s, the
  `LiveTranscriptionEngine.kt:582` backlog figure; overflow marks the segment `shed`, drops, logs once);
  `commit`/`commitRetainingTailMs`/`awaitIdle`/`close`/`shutdown`/`releaseContext`/`prewarm` delegate to
  `local` and then inform the preview; `onSegmentResolved` comes from `local` only (exactly-once, in commit
  order — the "provable pass-through", `LocalWhisperEngine.kt:19-21`); `onDelta` comes from the preview only,
  and `LocalWhisperEngine`'s own deltas (its terminal blank at `LocalWhisperEngine.kt:600`) are swallowed.

The decode loop, verbatim upstream shape (`android/SherpaOnnx/.../MainActivity.kt` at v1.13.4 —
https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.4/android/SherpaOnnx/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt,
2026-09-09): drain the queue → `acceptWaveform(float32 = pcm16 / 32768f)` → `while (isReady) decode()` →
`getResult().text` → `DeltaThrottle` (≥ 150 ms, `transcription/DeltaThrottle.kt:18`) → `onDelta`. Threads:
the capture thread (`util/StreamingAudioRecorder.kt:80-85`, 1024-byte / 32 ms chunks, URGENT_AUDIO per
`util/CaptureThreadPolicy.kt:25`) never runs `decode` — it already carries the inline Silero probe; the
preview executor runs at `THREAD_PRIORITY_AUDIO` (below capture, above default; INFERRED); Main renders.
**No new `TranscriptionEngine` member**: the transducer's partials are pulled by the engine's own loop off
`sendAudio`, the `senderLoop` shape (`LiveTranscriptionEngine.kt:290-321`), so the house rule
(`TranscriptionEngine.kt:163-179`) is honoured with zero interface change.

### 3.3 Endpointing — who cuts, and what a cut does to the stream (DOCUMENTED mechanism, INFERRED settings)

**sherpa's endpointer stays off.** Its "trailing silence" is `num_trailing_blanks * 4` decoder frames
(`online-recognizer-transducer-impl.h:385` at v1.13.4 —
https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.4/sherpa-onnx/csrc/online-recognizer-transducer-impl.h,
2026-09-10; `endpoint.cc:15-21` has frame-count terms only, no amplitude), i.e. the decoder emitting blanks —
not acoustics. The app's Silero endpointer keeps cutting exactly as for `multi`: `ONSET_THRESHOLD 0.50`,
`HANGOVER_MS = 350L` (`audio/EndpointerTuning.kt:87`), `MIN_SPEECH_EVIDENCE_MS = 192L` (`:197`), and the
**flatline cut** on device audio (`FLATLINE_RMS_MAX = 10`, `FLATLINE_CHUNKS = 5`, `:235`, `:259`) which has
no sherpa analogue; the wall caps `FIRST_SEGMENT_WALL_MS = 4_000L` / `MAX_SEGMENT_WALL_MS = 15_000L`
(`service/SegmentCapPolicy.kt:54`, `:57`). In the tee none of this changes: the funnel (`:3364-3400`) commits
`local` as today and the preview is told afterwards.

**At every commit the preview pads, drains, freezes, and re-creates the stream.** Three primary-source facts:
`IsReady ⇔ processed + ChunkSize() < ready` (impl.h `:248-250`); `ChunkSize() = T_` and `ChunkShift() =
decode_chunk_len_` are read from the model's own metadata (`online-zipformer2-transducer-model.cc:125-126`),
and the shipped int8 encoder's metadata reads **`T = 45`, `decode_chunk_len = 32`**, `left_context_len =
128,64,32,16,32,64` (HTTP range read of the file tail on HF, 2026-09-10; icefall's export comment
`# 32+7+2*3=45`, `export-onnx-streaming.py:517`); and `OnlineStream::Reset()` keeps the feature extractor and
shifts `start_frame_index_` so unprocessed frames become the **next** segment's head (`online-stream.cc:49-54`
at v1.13.4). So at the app's 350 ms hangover the chunk holding the last syllable may not have decoded yet, and
a bare `reset` would decode it into the next utterance's partial. Rule: feed **≥ T frames of zeros** (450 ms
chosen; the derived minimum is ~110 ms; upstream's own file decoder pads 800 ms, `sherpa-onnx.cc:141-147` —
A/B 450 vs 800 on the canary wav), drain, take `getResult()`, **release the stream and `createStream()`**
rather than `reset`: `reset` can never drop encoder state through this AAR (no `reset_encoder` field,
§1.2), and a fresh stream is what makes a mic↔device SWITCH (`switchSource` commits at `:2267` then
`endpointer.reset()` at `:2268` for the same acoustic reason) correct with no cut-kind plumbing.

**Retained tails** (`commitRetainingTailMs`, the cap-cut micro-pause retain): the preview keeps a ring of the
last `CAP_CUT_MAX_RETAIN_MS = 3_000` ms of PCM (the fallback engine's mirror shape) and re-feeds the retained
tail to the fresh stream; the frozen prefix (§3.4) is trimmed at the cut point using the result's per-token
`timestamps` (40 ms resolution) — the one place the transducer's timestamps earn their keep in v1.

### 3.4 The strip (DOCUMENTED rule and readers; INFERRED change)

Today `deltaOwnsPreviewStrip(sessionIsLive) = sessionIsLive` (`FloatingBubbleService.kt:277`) with four
readers — `resolvedTextClearsStrip` (`:348-349`), `onDelta`'s first statement (`:2941`, every non-live delta
dropped), `renderInFlightStrip` (`:3429`), and the resolve branch (`:3463`) — pinned by
`InFlightStripTest.kt:16, :25, :131, :137, :143-144` and structurally by `InFlightStripWiringPinTest.kt:126-140`
(the exact one-argument string exactly once in the render; no `cloudWrapper`) and `:184-201` (the exact gate
string as `onDelta`'s first statement). The change:

```kotlin
internal fun deltaOwnsPreviewStrip(sessionIsLive: Boolean, sessionHasLocalPreview: Boolean): Boolean =
    sessionIsLive || sessionHasLocalPreview
```

with `sessionHasLocalPreview` a second `@Volatile` session flag beside `sessionIsLive` (`:2597`), set only
when the tee is constructed (§3.6), so `sessionIsLive` keeps its three existing jobs. **The cost is the 3.7 G
re-spec, and it is larger than "one input plus two pins":** every reader chooses which input it passes and
both pin tests assert the one-argument string; the live render's blank branch is `View.GONE` (`:2973`) and a
non-blank after GONE pays `reclampNow()` (`:2955-2959`) — the per-utterance geometry churn the anti-churn rule
exists to remove (`:343-346`: "the rule can only cost one geometry change per session if nothing returns the
strip to GONE mid-session"; `OCCUPYING_BLANK = View.INVISIBLE` at `:3434-3436`). A local previewer therefore
needs an INVISIBLE-not-GONE blank branch in the delta render — a render change, pinned — not only a rule
input. `resolvedTextClearsStrip` stays false for local sessions: the preview clears its own text.

**What the strip shows (INFERRED composition rule, the subtle part).** Whisper's final for utterance N lands
2–6 s after its cut (F ≈ 2.3 s on the Fold6 CPU, ≤ 6 s on the Tab, ≈ 1.9 s on turbo), and the strip is
replace-only, so a naive tee would drop N's words from the strip the moment N+1's partials start and show
them nowhere until the window catches up. Rule: the preview emits **`<frozen text of every closed-but-unresolved
seq> + <current partial>`**; when `local` resolves seq N the tee drops N's prefix; when nothing is pending
and no partial is live it blanks (INVISIBLE). The strip is then exactly "what the window does not have yet",
the "(N in queue)" label is not painted while a producer is active (ruling R2), and the `queue:` diag line
keeps the depth. Under the governor's merge branch (`audio/SileroEndpointer.kt:910-933`, the 6 s `multi`
floor) no commit happens, the stream is not reset, and the partial simply spans the merged pauses — consistent
by construction.

### 3.5 Delivery — unchanged (DOCUMENTED)

`onSegmentResolved` → Main → orderer → `deliverReleasedText` (`:3453`) → `handleTranscriptionResult`
(`:3492-3504`) → the sink → the one write at stop (`:3513`). The tee never resolves from a partial; the stop
path (`commitSegment(STOP)` at `:3106`, `awaitIdle`, `segmentOrderer.flush()`, `deliverFinalTranscript`)
runs `local` as today, and the preview drains and blanks on `awaitIdle`/`close`. Nothing in
`FinalDeliveryPolicy`, `TranscriptSink`, `TextJoin` or the injection paths is touched.

### 3.6 Language — the gate (DOCUMENTED inputs, INFERRED rule)

Today: `selectedLanguage` defaults `"auto"`, `getLanguageForApi()` maps auto → `null`
(`data/local/PreferencesManager.kt:178-181`); the service forces `"en"` on `ModelScope.ENGLISH` tiers and
honours the pick on multilingual ones (`FloatingBubbleService.kt:2838-2844`) — the site T1 (the 3.8 leak
fix) turns into `sessionLanguageFor(installedModel, selection, engineKind)`. The tee is constructed iff

```
sessionLanguage == "en" && "en" in installedPacks && !batchJobRunning && userEnabled
```

Consequences, each a pinned row: Auto + `multi`/turbo ⇒ no preview (today's session, byte-identical);
Auto + `pro` ⇒ "en" ⇒ preview (correct: `:2839` already resolves it); fixed "en" + any tier ⇒ preview; fixed
"es" ⇒ none until an es pack exists. `LanguagePin` and `detectsPerUtterance` are untouched (whisper receives
the same `lang`). A second language pack is a catalog row whose file set follows the pick; no per-language
cards (§2.5 for which rows can ever exist). Onboarding: `Step { PERMISSIONS, LANGUAGE, ENGINES, CLOUD }`
(`ui/onboarding/OnboardingLogic.kt:18`) gains one pinned sentence and a "Live words" chip on rows that have
a pack; the no-absolutes rule forbids "instant"/"real-time" in local copy too (`model/ModelTierCopy.kt:39-40`).

### 3.7 Model delivery (DOCUMENTED precedents; the choice is INFERRED)

- **Not a `WhisperModel` row, not `pairedArtifact`.** `PairedArtifact` is one second file; `download()`
  fetches one URL and deletes `fileName` first (`WhisperModelManager.kt:289`); `pairedArtifact != null` is
  simultaneously the download refusal (`WhisperModel.kt:435`), the batch substitution predicate
  (`BatchLocalModel.kt:64-65`) and half the mel-donor exclusion (`WhisperModel.kt:401-402`); and a runtime
  discriminator would have to reach nine sites (§3.1). **Not a Play asset pack**: packs are SoC-targeted
  (`app/device_targeting_config.xml`, four Qualcomm groups; `npu_turbo/build.gradle.kts:26-31` on-demand) and
  need Play; this model targets every device and the app sideloads on the internal-track flow.
- **The `TtsModelManager` shape**: own root under `filesDir`, a `.installed` marker written last
  (`tts/TtsModelManager.kt:33-45`), `DownloadManager`, the ±5 % size band "same policy as the whisper
  downloads" (`:213-215`), atomic swap, zip-slip-free — as a `StreamingPackManager` + `StreamingPackCatalog`
  keyed by language.
- **Source: four files at an immutable HF commit**, the catalog's own rule (`WhisperModel.kt:103-108`:
  "resolve/main is a MUTABLE ref … A commit sha can never change out from under us"; the app's
  `DownloadManager` already follows HF `resolve/<sha>/` redirects for 190 MB files, so the transport is proven):

| File at `https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/` | Bytes | sha256 (LFS oid; decoder, joiner and tokens re-downloaded and re-hashed 2026-09-10) |
|---|---|---|
| `encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 71,083,163 | `563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1` (oid only — the landing pin test hashes it once) |
| `decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 1,307,236 | `98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02` |
| `joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 259,335 | `d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297` |
| `tokens.txt` | 5,048 | `49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb` |
| **total** | **72,654,782** | vs the release tarball 310,414,022 B (fp32 + int8 + wavs) — never ship that under a small badge |

Layout `filesDir/zf-stream/en-2023-06-26/{…}` + `.installed`; the badge reads **"73 MB"** under the house
decimal convention (`ModelTierCopy.kt:25` says "190 MB" for 190,085,487 B), not "70 MB".

### 3.8 Punctuation strategy (DOCUMENTED costs; INFERRED ranking)

| Option | v1 verdict |
|---|---|
| Raw lowercase strip; whisper's cased, punctuated, numeral-normalised final | **v1.** Zero bytes, zero licence questions, 100 languages on the final |
| `OnlinePunctuation` on the strip text (text→text, composes with the replace-only strip before `onDelta`) | **hold** — the weights' licence is unreadable (§2.4); one line to enable once it is; cost per call UNMEASURED (docs 0.013 s) |
| whisper-small as a *finalizer beside a committer* | rejected for the committer shape (it re-imposes `multi`'s 6 s floor and 2.3 s F) — in the tee this is simply what happens today, at no new cost |
| ITN | a documented gap on any streaming output; the final is whisper's |

### 3.9 Threads, `NativeComputeGate`, TTS, batch, RAM, battery, canary

- **`numThreads = 2`** to start; measure 1/2/4 (§5.4). `numThreads` is ORT intra- and inter-op per session,
  three sessions per transducer, two `Ort::Env`s in the process, nothing shareable through the Kotlin API
  (`online-zipformer-transducer-model.h` private members at v1.13.4, 2026-09-09). TTS asks for 4
  (`TtsEngine.kt:842`); the Tab has 4×X4 + 4×A720 and whisper `multi` runs 4 threads.
- **Outside `NativeComputeGate`, deliberately** — the gate is a whole-call whisper lock
  (`NativeComputeGate.kt:23-27`); wrapping a decode loop in it stops it being streaming. One sentence goes into
  the gate's KDoc so the next reader does not "fix" it.
- **TTS**: `AudioArbiter.requestCapture()` at `startRecording` (`:2756-2757`) stops speech, and
  `requestSpeak()` refuses while capturing — but `isCapturing` is registered as `RECORDING || FINALIZING`
  (`:705-707`) and `requestCapture()` runs once while the state is CONNECTING (`:2754`, `:2757`), so a read-aloud
  requested during CONNECTING starts Kokoro at 4 threads and nothing stops it when RECORDING begins. One-line
  fix (include CONNECTING, or re-assert at the transition) lands with the tee.
- **Batch**: `BatchTranscriber` is hard-wired to `WhisperNativeBackend` (`BatchLocalModel.kt:12-19`) and can
  run beside the bubble; the tee is not constructed while a batch job runs (§3.6), and if the probe shows
  contention the lever is `numThreads = 1` under a "batch active" flag, never the gate.
- **RAM**: 72.7 MB of weights plus three ORT sessions and arenas — INFERRED +150–300 MB RSS; UNMEASURED
  (Kokoro's 2.3× ratio, ~0.8 GB for a 349 MB tar, `TtsEngine.kt:37`, does not transfer to int8). Kill line
  +400 MB. `onTrimMemory` releases the preview beside `localEngine?.releaseContext()` (`:3298-3313`).
- **Battery**: `multi` is bursty (F ≈ 2.3 s per commit); the streamer is a steady `RTF × threads` while fed —
  and in the tee the user pays **both**. ORT's thread spinning is on by default and unreachable on 1.13.4 but
  reachable on ≥ 1.13.5 via `provider = "cpu:<cfg>"` (§1.4) — the probe runs three arms (1 thread, 2 threads,
  2 threads spinning-off). Feeding only while the endpointer is in speech + hangover is the engine's choice
  and the cheap lever.
- **Canary, mandatory**: at first load per (versionCode, pack) run `canary_digits.wav` through the recognizer
  (the `transcription/CanaryAudio.kt` precedent, "one two three four five"); a mismatch disables the preview for
  this build with a diag line and never latches a permanent ban (the canary's null-verdict rule). It is the only
  thing that distinguishes "the user is silent" from "the encoder is wrong" on an SME device (§1.4).
- **Diag** (debug builds; release strips app diag lines by design): `stream-open: model=… sherpa=<VersionInfo>
  threads=N provider=cpu canary=pass|fail loadMs=…` and `stream-timing: seq=N audio=<ms> decodes=<n>
  decodeMs=<total> p50=<µs> p99=<µs> rtf=<x.xx> partials=<n> firstPartialMs=<ms> padMs=450 shed=0|1` as one
  pure pinned object, the `SegmentTiming.kt:16-17` discipline. The funnel's `endpoint:` / `queue:` /
  `perceived:` lines are untouched.

### 3.10 Shape C — what the committer adds, for when the WER diff earns it (the design pass, verified)

`StreamingLocalEngine` as the session engine (its own seq/outcome machine with the live engine's per-seq
`AtomicBoolean` claim; `EmptyExpected` for under-evidence and blank, `Lost` for not-loaded); a
`LOCAL_STREAMING` arm in `when (choice)` (`:2612-2701`) and `decideEngineChoice`; `warmLocalEngine` (`:2605`)
guarded so whisper is never prewarmed on `encoder.onnx`; the cadence row `"zf-stream-en" -> 0L` (else the
tier inherits `MIN_COMMIT_INTERVAL_LARGE_MS = 8_000L`, `CommitCadencePolicy.kt:150`, `:231`, and the merge
branch declines every pause inside 8 s); the first-cap window closed at `onOpen` as cloud does (`:2864`); the
catalog `runtime` discriminator across the nine sites of §3.1; a batch refusal sentence; the punctuation model
mandatory (its licence read); the CONNECTING label reading the streaming engine's warm flag. ≈ 4–5 further
days. It buys lower duty and sub-second *finals* into the window; it does not change what the user sees while
speaking.

### 3.11 Sizing (days INFERRED and uncalibrated — the prior doc `:759` records that history does not calibrate them)

| # | Work | Days | Owner |
|---|---|---|---|
| G0 | The measurement (§5.4): PC WER diff; the 1.13.4 prebuilt APK on the Tab; the probe app on the bump-candidate AAR with kill lines | 1 | 1 Tab session |
| B0 | AAR bump 1.13.4 → 1.13.7 on a branch: `app/build.gradle.kts:575`, `:582`, `:809` + digest; TTS suites; Kokoro smoke | 0.5 | 1 smoke |
| P0 | Strip prep commit on `main`: the second input, the INVISIBLE blank branch, the R2 label rule as a pure function, both pin tests re-specced; behaviour-neutral until a producer exists | 1.5 | — |
| E1 | `StreamingPreviewEngine` + `Recognizer` seam + fake + JVM suite (pad-and-recreate, prefix rule, timestamps trim, queue shed, canary, awaitIdle/close races) | 2 | — |
| E2 | `PreviewTeeEngine` + the `LOCAL_*` wrap + the gate + the arbiter CONNECTING fix + batch policy + `StreamDiag` + `onTrimMemory` | 1.5 | — |
| E3 | `StreamingPackCatalog` + `StreamingPackManager` (four pinned files, marker, ±5 %, atomic) + Settings row + language-step sentence + chip + copy tests | 2 | — |
| E4 | Internal-track AAB; device sheet Z1–Z10 (§5.3); fixes | 1 | 2 sessions |
| — | **Total** | **≈ 9.5 + G0** | 3–4 sessions |

---

## 4. QUALITY AND LATENCY — the honest experience

### 4.1 Accuracy (DOCUMENTED where numbered)

On read English the 72.7 MB streamer ties Whisper small (§2.2). Off LibriSpeech nothing is known; the
owner's own field verdict on the CPU floor ("Still works and is pretty fast, accuracy just suffers a bit",
ship-track memory) sets the bar the strip must not fall below — and in the tee only the strip is exposed to
it. Concretely lost on the strip versus a cloud-live strip: casing and punctuation (all-caps, unpunctuated
output — the docs' own example is `" AFTER EARLY NIGHTFALL THE YELLOW LAMPS …"`); numerals (the vocabulary has
no digits — "twenty twenty six"); proper nouns and brand names (a 500-piece BPE on 960 h of public-domain
books; hotwords exist but only with `modified_beam_search`); code-switching (an English-only vocabulary
forces foreign words into English pieces); long-context recovery (left context 128 frames = 2.56 s vs
whisper's 30 s window). Greedy decoding emits a token once and never revisits it, so the strip is append-only
in practice (INFERRED from the decoder class) — the Soniox "final tokens" feel. Silence: blanks, no stock
phrases (INFERRED from the model class; `ysProbs` per token is the natural gate — UNMEASURED).

### 4.2 Latency (DOCUMENTED constants, INFERRED arithmetic)

| Event | Delay after the acoustic event | Composition |
|---|---|---|
| first hypothesis after speech onset | ≥ 450 ms + compute | `T = 45` frames must exist before the first decode |
| a word becomes visible, steady state | **mean ≈ 0.4–0.6 s, worst ≈ 0.7–0.8 s** | chunk-boundary wait 0–320 ms (mean 160) + lookahead 130 ms + `320 ms × RTF` (16–80 ms) + `DeltaThrottle` ≤ 150 ms + Main hop/render |
| utterance close on the strip | the app's 350 ms hangover + pad + last decode | the frozen prefix stays until whisper's final lands |
| whisper's final into the window | + F ≈ 2.3 s (Fold6 CPU) / ≤ 6 s (Tab, 17.6 s chunk in 6.05 s) / ≈ 1.9 s (turbo) | unchanged from today |

Contrast, today (DOCUMENTED): `multi` shows nothing until a commit resolves — 6,000 ms floor
(`CommitCadencePolicy.kt:134`, `:229`); first-visible-text ≈ 13.8 s on the Fold6 in 3.6.0 (memory);
`npu-turbo` F = 1.89 s with no partials. Cloud-live: OpenAI `server_vad` 0.5 / 300 / 500 ms
(`RealtimeEvents.kt:111-113`), ElevenLabs claims "~150ms†", Soniox states "low latency" with no number
(provider docs read 2026-09-10). **The streaming tier's partial latency is the cloud-live class and an order
of magnitude ahead of every local tier the app has — that is the whole reason to build it.**

### 4.3 Real-time factor on the two devices (DOCUMENTED datums, INFERRED band)

Published: en int8 **RTF 0.062** / fp32 0.077 at 2 threads on an unnamed desktop CPU (docs page, 2026-09-10);
the one sherpa-onnx ARM datum is a maintainer's **Raspberry Pi 4 Model B, 1 thread, 0.697** with "We have
not benchmarked sherpa-onnx on Android" (sherpa-onnx #126, 2023-04-21 — https://github.com/k2-fsa/sherpa-onnx/issues/126);
RK3588 boards on the RKNN NPU path print 0.2 / 0.38 for streaming Zipformers (https://k2-fsa.github.io/sherpa/onnx/rknn/models.html,
2026-09-10) — a different runtime, 3–6× the desktop figure, a warning not a comfort. **No ORT-CPU RTF exists
for any streaming model on phone-class ARM.** Reference points on the target devices: Fold6 19.22 ms/GFLOP +
227 ms on `multi-q5_1` at 4 threads (prior doc §1.3); Tab `multi` 17.6 s in 6.05 s ⇒ RTF 0.34 (prior doc
§1.1); the NPU pipeline idle 61–75 % (`EndpointerTuning.kt:74-76`). INFERRED band at `numThreads = 2` on the
big cores: **0.05–0.25** (1–3 GFLOP per audio-second for a 66 M streaming encoder, 4–10× under whisper-small's
11; cross-checked from the RPi datum by a 4–8× per-thread X4-vs-A72 factor → 0.09–0.17 single-thread). Per
320 ms chunk that is 16–80 ms of compute. **Kill line: sustained RTF > 0.5 at 2 threads on either device**
(above it the tier is Fold6-class-only and the universal-floor claim is false).

### 4.4 The honest comparison

| | Soniox `stt-rt-v5` | OpenAI live | ElevenLabs `scribe_v2_realtime` | **`zf-stream` tee (en)** | `multi` today |
|---|---|---|---|---|---|
| Partial shape | tokens with `is_final` (`SonioxRealtimeProtocol.kt:29`, `:77`) | `delta` fragments folded by the app (`OpenAiRealtimeProtocol.kt:66-70`) | server-segmented | cumulative segment text; append-only in practice | none for ≥ 6 s |
| Word latency | "low latency" | presets `minimal…xhigh` | "~150ms†" | 0.4–0.8 s + compute (§4.2) | 6–14 s |
| Casing / punctuation on the strip | yes | yes | not stated | **no** | (no strip) |
| Numbers | formatted | digits | — | words | digits (final) |
| Languages | 60+, per-token switching | many | 90+ | **one** (en); the final is whisper's 100 | 100, session latch |
| Endpointing | server `<end>` tokens | server VAD | server VAD | the app's Silero + flatline (unchanged) | same |
| Word timestamps | per token | no | — | yes, 40 ms (unused in v1 except the retain trim) | segment |
| Privacy / cost | audio leaves the device, per minute | same | same | on device, free | on device, free |
| Music / noise | provider-trained | same | same | UNMEASURED; INFERRED weaker; the final is whisper's | whisper |
| FEAT_SME phones | yes | yes | yes | **only on AAR ≥ 1.13.5 + canary** | yes (whisper.cpp) |

**The sentence for the owner:** an English speaker on any phone gets Soniox-shaped words on the strip, on
device, for a 73 MB download — after the AAR bump — and the typed result is exactly today's; punctuation on the
strip is a 7 MB add-on behind a licence read; any other language is `multi` as it is now. Not delivered:
multilingual word-for-word, digits on the strip, per-token language switching.

---

## 5. THE PLAN

### 5.1 The 4.4 arc, and the Tab probe (DOCUMENTED state; INFERRED placement)

"4.4 streaming" in the memory was two arcs under one name: **(F)** fast visible text — the recorded vehicle
was small-on-NPU partials at ~0.7 s + turbo finals, which the 2026-09-02 review killed on arithmetic (row B
"~130 % — impossible"; 0.7 s under the `npu` row's 1,200 ms floor; "nothing on the NPU stack gives sub-second
visible text", `docs/superpowers/reviews/2026-09-02-realtime-chunks-review.md:549`, `:565-568`; the surviving
recorded shape is turbo fast chunks + 25 s finals "about a second", Qualcomm-only, after a `qnn_asr.cpp`
redesign) — and **(Q)** delivered-text quality, the 25 s window + per-seq journal. **The streaming tier
replaces (F)** on every device with no JNI redesign and no duty collision (the previewer runs on the CPU while
turbo runs on the Hexagon); **the co-residency spike is cancelled**; **(Q) is untouched** — the previewer
never writes to the sink, so the journal stays a later quality arc for turbo devices.

**The Tab probe is orthogonal.** As of `tools/tab-apu-probe` at `27dd446` (six commits past `main`;
`docs/measurements/2026-09-09-tab-apu-probe.md` §6, verdicts written 2026-09-10): E3 is **NO-GO on public
artefacts** (LiteRT-LM 0.17.0 refuses the only published MediaTek dispatch library; the gated Gemma3-1B MT6989
model needs the owner's HF licence acceptance) while the APU itself is reachable (whisper-base ≈ 100 ms JIT,
Google's AOT tiny 43 ms, but every run logs the #6462 restore-failure signature — "multi-class at best on the
public toolchain, R1 stays a research bet"); E5 puts turbo on the Mali G720 at **1.86 s per 30 s window, 3.4 GB
RSS, fp16** ("the only sub-2 s turbo number of the day … R3 is not closed"). Whatever comes of it is a
whole-window, commit-based accelerator arm with no partials; the previewer pairs with it exactly as with
Qualcomm turbo, and if nothing comes of it the Tab stays on `multi` and the previewer is the *whole* felt-speed
fix there. Tooling coupling only: the sherpa mode joins the same throwaway probe app
(`tools/probes/litertlm-probe`, `applicationId com.whispereverywhere.probe`, minSdk 31, "sideloads BESIDE the
Play copy … and never touches it") after that branch lands; a GPU-turbo session at 3.4 GB is one extra
"under load" row.

### 5.2 Sequence and versioning

| Item | Needs | Gives |
|---|---|---|
| 87 = 4.3.3 (`app/build.gradle.kts:53-54`) | owner: upload, H1/H2, promote, push | the base |
| T1 leak fix (`sessionLanguageFor`, 3.8 spec §1; 0.5 d) | nothing | the one helper **both** Gemini live and the streaming gate read (`:2838-2844`) |
| P0 strip prep commit on `main` (needs R2) | R2 | `deltaOwnsPreviewStrip(sessionIsLive, sessionHasLocalPreview)`; behaviour-neutral; neither engine branch carries the pin re-spec |
| B0 AAR bump branch | nothing | 1.13.7 + digest; TTS suites green; Kokoro smoke |
| G0 measurement (§5.4) | the Tab; the bump branch | the kill-line numbers + the WER diff |
| Gemini T0 probes (PC) | a free-tier key (O2) | P1–P9 |
| **the streaming week → 89** | T1, P0, B0, G0 passing, R1–R4 | the tee, pack manager, gate, copy, sheet |
| Gemini T2–T7 → 88 | T0, T1, T4 | one line in the `CLOUD_LIVE` arm (`:2664`) + `ProviderCatalog.kt:60` |
| (Q) the finalizer | the governor (in since 85) | later, turbo devices, its own schedule |

The two engines collide only inside `when (choice)` (`:2612-2701`) and the pin tests; a rebase resolves it in
minutes whichever lands first. **Names:** the release-identity rule is "the name moves when what the user
sees changes" (`ReleaseIdentityTest.kt` KDoc; 87 = 4.3.3 moved the last digit for the accessibility-optional
path). The prior doc's primary proposal was 4.4.0 for Gemini live with 4.3.4 the alternative; this synthesis
recommends the reverse — **streaming = 4.4.0** (a download, a surface, and the name the memory has carried for
streaming), **Gemini live = 4.3.4** — and either satisfies the test; only the upload order is fixed (88 then
89). Owner rules (R5, R6). No asset pack, no device-targeting XML, no `verifyNpuPacks` impact; the streaming
AAB has 87's pack shape.

### 5.3 The first two weeks (days INFERRED)

| Day | Who | What | Output / gate |
|---|---|---|---|
| 0–1 | owner | Upload 87; H1/H2; promote; push. Gemini key (O2). Sideload the 1.13.4 prebuilt en APK on the Tab and dictate three sentences (rung 2). Record 5 min of own speech (phone mic, ordinary room) for rung 1 | 87 live; the feel row; the WAV |
| 1 | eng | **T1** leak fix + JVM truth table | commit on `main` |
| 1 | eng | **B0** AAR bump branch: three lines + digest; TTS suites | branch; owner Kokoro smoke |
| 1–2 | eng | **P0** strip prep commit (needs R2) | commit on `main`; suite green; behaviour-neutral |
| 1–2 | eng (PC) | **rung 1**: en model vs `multi` on the canary + the owner WAV; WER + error kinds + side-by-side | `docs/measurements/2026-09-xx-zf-stream-pc-diff.md` |
| 1–3 | eng (PC) | Gemini **T0** P1–P9 | the probes doc |
| 2–4 | eng + one Tab session | **rung 3**: `mode=sherpa` in the probe app on the 1.13.7 AAR (one row on 1.13.4); the numbers table | `docs/measurements/2026-09-xx-zf-stream-probe.md`; **kill lines** |
| 4 | owner | GO/NO-GO on the probe; R1–R6 | the week's assignment |
| 4–10 | eng | E1–E3 (§3.11): the preview engine + fake suite; the tee (seq pass-through pinned, prefix rule pinned, SWITCH/STOP rows); pack catalog + manager; gate + truth table; the `LOCAL_*` wrap; copy; diag; identity 89 / 4.4.0; AAB | 89 on the internal track |
| ‖ | eng | Gemini T2–T7 → 88 = 4.3.4 (before or after, per R6) | 88 on the track |
| 10–14 | owner | **4.4.0 sheet** (Fold6 + Tab from the track): Z1 words appear while speaking (en on pro/multi/turbo); Z2 the final matches today's; Z3 a 5-minute read — the strip never lags more than a tick (no growing backlog); Z4 Auto on `multi` = byte-identical to 4.3.3; Z5 Spanish fixed = no live words, copy explains; Z6 YouTube session — strip streams, flatline cut still fires; Z7 source switch mid-utterance — no duplicated or lost words; Z8 read-aloud during/after — no overlap, no stutter; Z9 batch file job while dictating — policy holds; Z10 stop mid-sentence — tail lands, strip clears; Z11 canary line on both devices | promote when Z1–Z11 pass |

Deliberately not in the two weeks: Shape C, a second language, the punctuation model, the finalizer/journal,
NNAPI beyond one probe row, anything on the S23/XR.

### 5.4 The first experiment — three rungs, zero app code, with kill lines

**Rung 1 — PC, hours.** Run `streaming-zipformer-en-2023-06-26` (the four int8 files) through the sherpa-onnx
CLI or Python API over (a) `canary_digits.wav`, (b) the dense-speech clip, (c) five minutes of the owner's
dictation; diff against `multi`'s text for the same audio (`util/WerMath.kt:41` for the number; the error
*kinds* — dropped vs wrong vs numbers-as-words — by hand). PC RTF is irrelevant. **Decision rule:** if the
side-by-side reads as *wrong* (not "less polished" — that is expected), stop: the tier is Shape-C-only on a
different model, or nothing.

**Rung 2 — the Tab, minutes, zero build.** `https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/asr/1.13.4/sherpa-onnx-1.13.4-arm64-v8a-asr-en-zipformer2.apk`
(72,640,211 B, HTTP 200, 2026-09-10; the generator `scripts/apk/generate-asr-apk-script.py` maps
`zipformer2` to `…-en-2023-06-26`, bundling the int8 encoder with the fp32 decoder/joiner; applicationId
`com.k2fsa.sherpa.onnx`) — the same sherpa release the app pins — and its 1.13.7 sibling (72.6 MB, ORT 1.27.1)
as the bump candidate; also `…-asr-en-small_zipformer_20M_2023_02_17.apk` (53.4 MB). Caveats: upstream's
own loop with 2.4 s / 1.4 s endpointing, not the app's; not on the Fold6 ("never `adb install` on the Fold6"
stands; its numbers come from the track build's sheet).

**Rung 3 — the probe app, days 2–4.** `mode=sherpa` in `tools/probes/litertlm-probe`, linking the 1.13.7 AAR
(one comparison row on `app/libs/sherpa-onnx-1.13.4.aar`), `Metrics.kt` verbatim (`rssKb`, `batteryTempTenths`,
`thermalStatus`), the four model files pushed via `run-as`; the §3.2 loop fed at the app's 32 ms / 1024-byte
chunking, `enableEndpoint = false`, `modelType = "zipformer2"`; log `T`, every partial with its wall time,
per-chunk decode ms, and `/proc/cpuinfo` flags.

| Measurement | Clip | Metric | **Kill line** |
|---|---|---|---|
| canary (first, both AARs) | `canary_digits.wav` | `getResult().text` lower-cased == "one two three four five" | any mismatch → that AAR does not ship; with `sme` in cpuinfo on 1.13.4 a mismatch is #3845 reproduced |
| partial latency | canary + `jfk.wav` at real-time pace | frame ending each word (token `timestamps`) → the partial that first contains it; p50 / p95 | p95 > 1.0 s at 2 threads |
| RTF | `jfk.wav` at max pace, 10 loops; `numThreads ∈ {1, 2, 4}`; `provider ∈ {cpu, cpu:<spinning-off cfg>, nnapi}` | decode ms / audio ms | sustained > 0.5 at 2 threads |
| under load | same, with a 4-thread busy loop standing in for whisper `n_threads = 4` | RTF, p95 cadence | RTF > 0.5 or p95 > 1.0 s ⇒ the tee does not fit beside the CPU tier |
| WER, strip | `jfk.wav` + one 3–5 min dense owner clip | `WerMath.wer` vs a hand-corrected reference; `multi` beside it | > 1.5× `multi` ⇒ strip-only forever (never Shape C); > 2× ⇒ stop |
| RSS | cold load → 10 min | `rssKb` delta | > 400 MB |
| thermal / battery | 10 min continuous speech, streamer + `multi` together, Tab, threads 1 and 2 | `thermalStatus`, `batteryTempTenths` | > 1 status step or > 3 °C |
| music bed | the endpointer-retune bed clip | tokens under bed-only audio, fed (a) unconditionally (b) under Silero | (a) any full word per 10 s; (b) any token |
| edited video | `scratchpad/review/jfk-gated.wav` (352,044 B; digital-zero gates of 128–1,152 ms) vs `jfk.wav` | boundary-word errors at the flatline cuts | > 1 per 10 cuts |
| pad length | canary | last-syllable loss at 450 vs 800 ms pad | choose the smaller pad with zero loss |

**Decision rule.** All lines clear on the Tab (the harder case) and the canary passes → the tier is a
universal-floor previewer and §3 is the work. RTF or thermal fail on the Tab only → a Fold6-class feature
gated by measured RTF. WER fail → strip-only stays the ceiling; Shape C never. Canary fail on 1.13.7 → the
Nemotron/other-model question, or nothing.

### 5.5 What not to do

1. Do not build the small+turbo co-residency spike; the previewer makes it moot.
2. Do not model the pack as a `WhisperModel` or reuse `pairedArtifact`; do not use a Play asset pack.
3. Do not make the recognizer the committer in 4.4.0.
4. Do not enable sherpa's endpointer beside the app's Silero, and do not `reset` a stream at a commit — pad and re-create.
5. Do not wrap the decode loop in `NativeComputeGate.serialized {}`; do not put `decode()` on the capture thread.
6. Do not ship on the 1.13.4 AAR; do not ship without the canary.
7. Do not write "real-time" or "instant" in any copy; do not badge 310 MB as 73 MB.
8. Do not narrow `-keep class com.k2fsa.sherpa.onnx.** { *; }` — it is the only thing between release builds and a `GetFieldID` SIGABRT; the landing commit adds that sentence to the rule's comment.
9. Do not add a Kroko / Korean / PengChengStarling / punct-model row before its licence cell is green.
10. Do not let the strip-rule change ride either engine branch; it is a `main` prep commit.
11. Do not touch 87.

---

## 6. RISKS, RANKED

| # | Risk | Status | Mitigation |
|---|---|---|---|
| 1 | **Silent miscompute on FEAT_SME CPUs** with the shipped ORT 1.27.0 (SM8850 documented; INFERRED extension to any SME/SME2 core); the bump to 1.27.1 fixes the M4 case and is unproven on SM8850; neither test device can see it | DOCUMENTED defect, unproven fix | AAR ≥ 1.13.5 + load-time canary + ruling R1; the tee fails safe (whisper's final still lands) |
| 2 | **Out-of-domain accuracy unknown** — every WER is in-domain; INFERRED 1.5–2× worse than `multi` on conversational/noisy audio | UNMEASURED | rung 1 before any code; the tee confines the risk to the strip; the WER kill line |
| 3 | **RTF / thermal on the Tab beside whisper** — two CPU consumers; no phone-class ORT-CPU datum exists | UNMEASURED | rung 3 "under load" + thermal rows; kill lines; levers `numThreads = 1`, spinning off (≥ 1.13.5), the 20 M model, feed-under-Silero |
| 4 | **Strip/window divergence** — lowercase transducer text on the strip, whisper's cased text below, words may differ; a new UX artefact | INFERRED | rung 1's side-by-side; the prefix rule keeps every word visible somewhere; Shape C removes it later |
| 5 | **The 3.7 G re-spec** — the strip change is a render change (INVISIBLE blank, the label rule), not one input; two structural pin tests | DOCUMENTED cost | P0 as its own behaviour-neutral commit on `main` with pins |
| 6 | **Punctuation-model licence unreadable** | DOCUMENTED gap | hold; v1 needs no punctuation model |
| 7 | **RAM** — +150–300 MB INFERRED beside Kokoro's 0.8 GB resident context and a batch whisper job | UNMEASURED | RSS row; `onTrimMemory` release; kill line 400 MB |
| 8 | **TTS/decoder overlap during CONNECTING** (`isCapturing` excludes CONNECTING) and batch contention | DOCUMENTED gap | one-line arbiter fix; no tee while a batch job runs |
| 9 | **Tail-syllable loss at commits** if the pad is skipped or too short | DOCUMENTED mechanism, INFERRED frequency | ≥ T frames of pad + fresh stream; A/B 450 vs 800 |
| 10 | **Multilingual expectations** — the only Auto-capable streaming model is 682 MB and below the English floor; Kroko/Korean/PengChengStarling are licence-blocked | DOCUMENTED | the language-step sentence states the trade; R4 |
| 11 | **Day counts** are uncalibrated (the Soniox protocol + two suites landed in one day; history does not calibrate) | INFERRED | the measurement gate, not the calendar, decides |

---

## 7. WHAT WAS REFUTED (and what was corrected on the way)

| Claim (source) | Verdict | Correction |
|---|---|---|
| "Licence-clean and small enough to ship is **exactly one** streaming model" (quality Q10) | **refuted** | Two English rows are Apache-2.0 (66 M and 20 M, both LibriSpeech); fr-2023-04-14, zh-en bilingual, alphacep ru/bn/uz carry Apache-2.0 HF tags (HF model API, 2026-09-10). Kroko / Korean / PengChengStarling verdicts stand |
| "Only en-2023-06-26 is licence-clean among the small per-language packs" (plan C7) | **refuted** | same as above; the plan's own §4.3 listed the other Apache-2.0 rows |
| "No streaming model the shipped AAR can run honours Auto" (plan C6) | **refuted** | Nemotron-3.5 has `target_lang=auto` (card `README.md:279`, `:403`), sherpa v1.13.4 wires it (`online-transducer-nemo-model.cc:45`, `:98`, `:335`, `:528`; per-stream `"language"` option) and the shipped `.so` carries the strings. Surviving statement: **no small streaming model honours Auto**; R4 is a size/quality ruling (682 MB, −1.3 to −2.2 pt English) |
| "ORT thread spinning is unreachable from the Kotlin API" (quality Q9) | **refuted** for the AAR the tier ships on | On ≥ 1.13.5 `provider = "cpu:<cfg>"` forwards `SessionConfig.session.intra_op.allow_spinning=0` (`session.cc:201-228` at v1.13.7; #3792 merged 2026-07-24). True only on 1.13.4 |
| "No published ARM/Android RTF for any streaming model" (plan C8, quality Q5) | **weakened** | RK3588 RKNN-NPU docs print 0.2 / 0.38 (not ORT-CPU, not Android, not en); the RPi 4 0.697 is sherpa-onnx's only ARM-CPU datum; sherpa-ncnn #44 is an Android datum on a different runtime. The operative premise — no ORT-CPU RTF on phone-class ARM — stands |
| "1.13.5 (ORT 1.27.1) is the fix for SM8850" (quality Q1) | **weakened** | Nobody has run 1.27.1 on SM8850; #3845's `disable_kleidiai` negative may point at a second fault; the bump is necessary, not shown sufficient — hence the canary and R1 |
| "A bare reset loses ≤ 100 ms in ~30 % of utterances; 450 ms of pad is required" (design C2) | **weakened** | Mechanism confirmed (`online-stream.cc:49-54`); the derived minimum is ~110 ms, 450 is a chosen margin, upstream pads 800 ms; the 30 % is arithmetic on an assumed `ready ≈ t_e + 35`. Rule kept, numbers re-labelled, A/B added |
| "Four predicates must learn the runtime discriminator" (design C6) | **weakened** (undercount) | Nine sites plus the unconditional `warmLocalEngine` — one reason the tee (which needs none of them) is v1 |
| "TTS and the decoder never run concurrently" (design C8) | **weakened** | `isCapturing` excludes CONNECTING; a read-aloud requested then overlaps the session. One-line fix |
| "The queue-depth label is structurally unreachable on this tier" (design C9) | **weakened** | Depth 2 is reachable in sub-second windows at SWITCH (`:2267`) and STOP (`:3106`); the "not painted" outcome is a product ruling (R2), not structure |
| "The tee's only strip change is a second input + two pin re-specs" (plan C3) | **weakened** | Four readers plus the resolve branch; the live render's blank → GONE re-introduces the per-utterance churn the anti-churn rule removed; R2 needs render logic. Costed as P0 (1.5 d) |
| "The transducer emits ALL-CAPS unpunctuated word-form numbers; repeated digits unreliable" (quality Q8) | **weakened** on attribution | All-caps and word-form now DOCUMENTED from the artefact's `tokens.txt` (no digit pieces); the repeated-digit weakness is documented on Kroko en (#3443), not on 2023-06-26; the punct source repo's Apache-2.0 covers code only |
| "The recorded 4.4 plan cannot deliver word-for-word" (plan C1) | stands, scoped | The memory had already retired row B; the surviving shape is "about a second", Qualcomm-only, after a JNI redesign; the streaming tier's honest advantage is *every device + no redesign + sub-second*, not "the alternative is impossible". No owner text defines word-for-word as sub-second |
| "88 = 4.3.4, 89 = 4.4.0" (plan C11) | **weakened** | The prior doc's primary proposal was the reverse; "new surface = minor" is a paraphrase the test does not enforce; O5 is genuinely open (R5) |
| "The 4.4 Tab probe shape is untracked / APU-only" (plan C12 evidence) | corrected | `tools/probes/` is tracked (six commits); the live Tab accelerator candidate is now GPU-turbo at 1.86 s / 30 s window — still commit-based; the shape argument holds |
| "Build it as a committer; whisper-small finalizer rejected" (design §0, §7) | overruled for v1 (§3.1) | The design pass's own C3 shows the committer also uses the local plumbing, so "VAD stack untouched" is not the tee's unique advantage — the tee's unique advantages are the typed text, the fail-safe on SME, and one seam vs nine; the design stands as Shape C's spec |
| Prior doc §2.7: "no streaming model covers three or more languages"; "per model — en, zh, zh-en, ko, bn" | **refuted** (models map) | PengChengStarling (8, unlicensed), Nemotron-3.5 (40 locales), trilingual Paraformer; fr, ru, de/es (Kroko), NeMo/Nemotron en also exist |
| Prior doc §2.7: `decodeStreams` among the reachable symbols; `SpokenLanguageIdentification` "in the same AAR" as a free companion; Moonshine streaming-tiny as an Android option | corrected (sherpa + models maps) | `decodeStreams` has no Kotlin binding; SLID is offline and needs its own whisper tiny/base download; no sherpa-onnx runtime for streaming Moonshine |
| "Zero build-file change" (sherpa map §1.1, plan C2) | corrected by §1.4 | zero new native payload still holds; the AAR version must move (three lines + digest) |
| "70 MB" badge (plan) | nit | 72,654,782 B is "73 MB" under the house decimal convention |

---

## SOURCES (web; all read 2026-09-10 unless dated otherwise)

- sherpa-onnx v1.13.4 sources: https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.4/sherpa-onnx/csrc/online-recognizer-transducer-impl.h ; `…/csrc/endpoint.cc` ; `…/csrc/online-stream.cc` ; `…/csrc/online-zipformer2-transducer-model.{h,cc}` ; `…/csrc/online-zipformer-transducer-model.h` (2026-09-09) ; `…/csrc/session.cc` (2026-09-09; v1.13.7 copy 2026-09-10) ; `…/csrc/sherpa-onnx.cc` ; `…/csrc/online-transducer-nemo-model.cc` ; `…/csrc/online-recognizer-transducer-nemo-impl.h` ; `…/jni/online-recognizer.cc` ; `…/android/SherpaOnnx/app/src/main/java/com/k2fsa/sherpa/onnx/MainActivity.kt` (2026-09-09) ; `build-android-arm64-v8a.sh:93` and `kotlin-api/Tts.kt` at v1.13.4 / v1.13.5 / v1.13.7 (GitHub contents API)
- sherpa-onnx issues: #3845 https://github.com/k2-fsa/sherpa-onnx/issues/3845 ; #3791 https://github.com/k2-fsa/sherpa-onnx/issues/3791 ; #3490 ; #3443 https://github.com/k2-fsa/sherpa-onnx/issues/3443 ; #126 https://github.com/k2-fsa/sherpa-onnx/issues/126 ; CHANGELOG https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/CHANGELOG.md ; releases via `gh api repos/k2-fsa/sherpa-onnx/releases`, `…/releases/tags/asr-models`, `…/tags/punctuation-models` (+ `checksum.txt`), `…/tags/asr-models-qnn-binary-3` (2026-09-09) ; `scripts/apk/generate-asr-apk-script.py` (master)
- sherpa docs: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html ; https://k2-fsa.github.io/sherpa/python/streaming_asr/endpointing.html ; https://k2-fsa.github.io/sherpa/onnx/punctuation/pretrained_models.html ; https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html (2026-09-09) ; https://k2-fsa.github.io/sherpa/onnx/rknn/models.html ; https://k2-fsa.github.io/sherpa/onnx/qnn/models.html ; https://k2-fsa.github.io/sherpa/onnx/android/apk.html ; paraformer / ctc / sense-voice pages (2026-09-09)
- icefall: https://raw.githubusercontent.com/k2-fsa/icefall/master/egs/librispeech/ASR/RESULTS.md ; `…/egs/gigaspeech/ASR/RESULTS.md` ; https://raw.githubusercontent.com/k2-fsa/icefall/master/egs/librispeech/ASR/zipformer/export-onnx-streaming.py
- Hugging Face: https://huggingface.co/api/models/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26?blobs=true and `resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/{tokens.txt, joiner…, decoder…, export-onnx-zipformer-online.sh}` (downloaded and hashed) ; HF model API licence tags for `…-en-20M-2023-02-17`, `…-bilingual-zh-en-2023-02-20`, `shaojieli/…-fr-2023-04-14`, `alphacep/vosk-model-small-streaming-{ru,bn,uz}`, `k2-fsa/…-korean-2024-06-16`, `csukuangfj/…-en-kroko-2025-08-06`, `Banafo/Kroko-ASR`, `apbaxel/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-int8` ; https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b ; https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b ; https://huggingface.co/openai/whisper-large-v3-turbo ; https://huggingface.co/csukuangfj2/sherpa-onnx-apk/tree/main/asr/1.13.7 and `…/resolve/main/asr/1.13.4/sherpa-onnx-1.13.4-arm64-v8a-asr-en-zipformer2.apk` (HEAD)
- Other: Whisper paper Appendix D https://ar5iv.labs.arxiv.org/html/2212.04356 ; https://github.com/yangb05/PengChengStarling ; `gh api repos/yangb05/PengChengStarling` ; `gh api repos/frankyoujian/Edge-Punct-Casing` (+ tree) ; https://arxiv.org/abs/2407.13142 ; https://onnxruntime.ai/docs/performance/tune-performance/threading.html ; https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html ; https://openmdw.ai/ (2026-09-09) ; https://raw.githubusercontent.com/modelscope/FunASR/main/MODEL_LICENSE (2026-09-09) ; https://www.gsmarena.com/samsung_galaxy_z_fold6-13147.php ; https://en.wikipedia.org/wiki/List_of_ARM_processors ; https://support.google.com/googleplay/android-developer/answer/9859372 ; Soniox / OpenAI / ElevenLabs docs pages named in the quality analysis
- Repo: `docs/superpowers/research/2026-09-09-tab-turbo-gemma-scribe-gemini-live-research.md` ; `docs/superpowers/reviews/2026-09-02-realtime-chunks-review.md` ; `docs/superpowers/specs/2026-08-27-gemini-live-language-routing-design.md` ; `docs/measurements/2026-09-09-tab-apu-probe.md` (branch `tools/tab-apu-probe` at `27dd446`) ; `tools/probes/litertlm-probe/{README.md, app/build.gradle.kts, …/Metrics.kt}` ; memory `whisper-everywhere-ship-track.md`
