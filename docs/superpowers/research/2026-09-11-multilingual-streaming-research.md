# Multilingual live words — how the 4.4.0 word-for-word previewer reaches other languages

Research synthesis, **2026-09-11**. Four maps + four analyses + four adversarial refutation passes, then
this. Written after the owner's sentence on 2026-09-11, having tested 4.4.0 on device: *"this preview model
is exactly what I have been looking for. It works, now the app works exactly like cloud providers do for
English only at the moment. But if we could do multi language somehow, that will be incredible."*

**Reading rules.** **DOCUMENTED** = read in this checkout (`file:line`, main @ `3f982b9`), in the shipped
AAR, or in a primary source with its URL and the date read. **INFERRED** = reasoning over those facts,
labelled at the sentence. **UNMEASURED / NOT PUBLISHED** where no number exists. Nothing here says "should
work". **Licence discipline:** this is a **paid** app, so a non-commercial weight licence is a dead end and
is flagged as one — and an `apache-2.0` tag on a Hugging Face mirror is the *mirror owner's* tag, never a
corpus clearance.

**Verified by this synthesizer today, not taken on report** (the disputes that changed the answer): the
German model's card, WER files, token file and ONNX metadata; the Whisper paper's per-language appendix
tables extracted from the PDF; Vosk's per-language licence column; Moonshine's deployment artefact sizes;
Kroko's pricing page; the app's own `warm()` guard, gate predicate, catalog literals and version.

---

## 0. THE ANSWER IN ONE SCREEN

**The owner can have live words in French and German next release, and the reason he could not have them
last week was partly a false negative in our own research.**

1. **German is available after all — and it is the cheapest row in the whole survey.**
   `daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de` declares `license: apache-2.0` on
   CommonVoice 17.0, ships a **four-file int8 export summing to 70,938,534 B** (97.6 % of the shipped
   English pack — badge **"71 MB"**), carries `model_type = "zipformer2"` and `decode_chunk_len = 32` in
   its own ONNX metadata (**the same family and the same 320 ms cadence the app already runs**), has a
   **502-line ALL-CAPS `▁`-BPE vocabulary with only `#0`/`#1` carrying a digit** — byte-for-byte the
   vocabulary shape rung 1 audited for English — and publishes a **greedy streaming WER of 10.58 (test) /
   8.58 (dev)** in its own repo. Every one of those facts I read myself today (§1.1). Against
   whisper-small's CommonVoice-9 German of **13.0**, the German previewer is *more* accurate than the model
   that types the text. It is the app's **#4** picker language.
2. **So Route A — one pack per language, on the AAR already in the APK — reaches eight languages, and two
   of the app's own top four.** Licence-clean, word-emitting, four-file int8, loadable by
   `sherpa-onnx-1.13.7.aar` with **zero new native bytes**: **en** (shipping), **fr** (#3, 128 MB),
   **de** (#4, 71 MB), **ru** (#9, **29 MB**), **zh** (#10, 72 MB), **ko** (#12, 73 MB — corpus counsel),
   **id** (#18, 71 MB), plus **zh-en** bilingual (198 MB) and **et-en** (156 MB). §1.1.
3. **Spanish is still the hole — but it is a RUNTIME decision, not a licence dead end, and the prior
   framing was wrong about that.** Spanish has three licence-clean streaming options today: **Vosk-es**
   (Apache-2.0, 39 MB, Kaldi — needs a 13.5 MB second runtime), **moonshine-streaming-small-es** (MIT,
   121,800,392 B — needs an NDK build against a *second* ONNX Runtime), and **Nemotron-3.5** (OpenMDW-1.1,
   682 MB). What Spanish does **not** have is a model that loads through the runtime already shipping.
   The only Apache-2.0 Spanish *streaming Zipformer* emits **IPA phonemes**. §1.3.
4. **Route B (one multilingual model) is refused on memory and unvalidated on lag — measure it once, never
   schedule it.** `nemotron-3.5-asr-streaming-0.6b` is genuinely streaming, commercially licensed, covers
   19 transcription-ready locales, and its per-stream language switch **is reachable from Kotlin with no AAR
   change** (one sibling map said otherwise and was wrong). It costs **682,215,471 B**, **≥ ~1.1 GB
   resident (UNMEASURED on ORT)**, and it **regresses English by +2.27 / +2.84 FLEURS points** against the
   app's actual default tier. It cannot replace `preview_en`; it can only sit beside it, which makes Route B
   two families and 755 MB, not a simplification. §3.
5. **Auto: stop treating it as unserveable.** Today an Auto user on a multilingual tier gets **no live
   words even while speaking English with the 73 MB English pack installed and warm**, because the gate's
   first conjunct is `sessionLanguage == "en"` (`FloatingBubbleService.kt:237`) and Auto resolves to null
   (`:206-212`). Closing that costs **zero new model bytes, zero new RAM and zero new CPU** — the bytes are
   already spent. Recommendation: seed from `lastDetected ?: deviceLocale`, arm **only** into an installed
   pack, name the guess on screen, and stand down when whisper's own detection disagrees. §4.
6. **The plan: `4.5.0` at versionCode `91` ships the ROUTE plus fr and de.** Stage 0 is 3–4 desk/probe days
   that can collapse later stages. Stage 1 is 8–10 days: the seam (pack identity, per-pack `modelType`,
   per-pack canary, per-pack `disabled`, per-pack copy) plus two languages. Then ru/zh/ko/id batched.
   Nothing needs a native speaker: every engineering gate is machine-judged, accuracy is a **ratio against
   whisper's own output on a corpus that ships its reference**, and the copy is *derived* from the token
   file so the app can never promise what only a speaker could confirm. §5.
7. **Three things that must land before any second pack, or it is a defect, not a polish item.**
   `warm()` is idempotent on the **engine**, not the **pack** (`StreamingPreviewEngine.kt:113`) — so a
   naive second pack paints **English over French** while the gate logs `preview=1`. `modelType` is
   hard-coded `"zipformer2"` (`SherpaPreviewRecognizer.kt:46`) and is **wrong for French** (a zipformer v1
   export) — and at v1.13.7 a family mismatch is **crash-class**, not a caught load failure. And the
   English digits canary cannot pass for a non-English model, while a Fail disables live words
   **process-wide** behind a sentence the 4.4.0 acceptance sheet records as **rendered nowhere**. §2.2.

**The honest headline.** "Multi language" is answerable for **French, German, Russian, Chinese, Korean and
Indonesian** on the engine already shipping, at 29–128 MB a language, keeping the measured 0.4 s lag and the
zero-retraction strip. It is **not** answerable for **Spanish, Italian, Portuguese, Dutch, Polish, Japanese,
Arabic, Hindi, Turkish or Vietnamese** without either buying Kroko's commercial licence (**from $150/mo**,
13 languages, built on sherpa-onnx) or adding a second runtime. That is one owner decision, and it is worth
more than all the engineering below it.

---

## 1. THE MODELS

### 1.1 Per-language streaming models that load through the AAR already in the APK

Pack bytes are the **sum of the four files a pack would actually carry**, from the Hugging Face tree API
(all reads 2026-09-11). A release-tar size is never quoted: the shipped English pack is **72,654,782 B on
disk against a 310,414,022 B tar**, a 4.3× over-statement. Badge = `StreamingPackCatalog.sizeBadge`
= `(bytes + 500_000) / 1_000_000` MB (`StreamingPackCatalog.kt:76-83`). "Picker #" is the row's position in
`PreferencesManager.SUPPORTED_LANGUAGES` (`:541-597`) — the app's only written statement of priority.

| # | Lang | Model | Pack bytes | Badge | `modelType` | Published streaming WER | whisper-**small** on that language | Licence |
|---|---|---|---|---|---|---|---|---|
| 1 | **en** | `csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26` | **72,654,782** | 73 MB | `zipformer2` | LibriSpeech **3.06 / 7.79** greedy @320 ms (icefall) | LS test-clean **3.1** (small.en, greedy, Table 8) | apache-2.0 — **SHIPPING** |
| 3 | **fr** | `shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14` | **128,227,451** | 128 MB | **`zipformer` (v1)** | CommonVoice-fr test **9.95** greedy (icefall) | CV9 **22.7** · MLS **16.2** · FLEURS **15.0** | **apache-2.0** |
| 4 | **de** | `daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de` | **70,938,534** | **71 MB** | `zipformer2` | **CV17-de test 10.58 / dev 8.58**, greedy, **streaming** chunk-32-left-128 | CV9 **13.0** · MLS **10.5** · FLEURS **10.2** | **apache-2.0**, author-declared — see the caveat below |
| 9 | **ru** | `csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16` | **28,572,945** | **29 MB** | verify | CommonVoice-ru **11.3** (upstream card metric, unverified) | CV9 **15.0** · FLEURS **11.4** | apache-2.0 upstream; **mirror carries no tag** |
| 10 | **zh** | `k2-fsa/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12` | **72,470,080** | 72 MB | `zipformer2` | **NOT PUBLISHED at chunk-16** | CV9 **29.4** · FLEURS **20.8** | apache-2.0; corpus = the recipe's |
| — | **zh-en** | `csukuangfj/…-streaming-zipformer-bilingual-zh-en-2023-02-20` | **198,270,793** | 198 MB | `zipformer` (v1) | **NOT PUBLISHED** | — | apache-2.0; *"internal multilingual dataset"* |
| 12 | **ko** | `kangkyu/icefall-asr-ko-streaming-zipformer-72m` | **72,969,700** | 73 MB | `zipformer2` | **8.25 % CER** @chunk-64 int8, **RTF 0.038** — the only non-English row with a published sherpa RTF | FLEURS **19.6 WER** — **not metric-comparable** | apache-2.0 in the README; **AI-Hub / KsponSpeech ⇒ counsel** |
| 18 | **id** | `spacewave/sherpa-onnx-streaming-zipformer2-id` | **70,908,694** | 71 MB | `zipformer2` | YODAS **11.90** · CV **11.58** · FLEURS **8.96** · LibriVox **6.79**, avg **9.81** | CV9 **18.4** · FLEURS **16.3** | **mit**; YODAS2 (YouTube-derived) ⇒ counsel |
| — | **et-en** | `TalTechNLP/streaming-zipformer-large.et-en` | **156,221,502** | 156 MB | verify | **NOT PUBLISHED** | — | **mit**; the only non-English card that *states* sherpa-onnx usage |
| 41 | **bn** | `csukuangfj2/…-streaming-zipformer-bn-vosk-2026-02-09` | **94,119,939 — fp32 only** | 94 MB | — | **NOT PUBLISHED** | CV9 **118.6** · FLEURS **104.4** ⇒ **the gate is meaningless here** | apache-2.0 upstream; mirror untagged |
| 39 | **fa** | `nemo-ctc-fa-shenava-*-streaming` ×3 | **11.7 / 31.0 / 99.3 MB int8** — **CTC**, not a transducer | — | `neMoCtc` | **NOT PUBLISHED** | — | apache-2.0 |

**The whisper-small denominators are DOCUMENTED**, and this synthesizer extracted them from the paper PDF
today rather than trusting a rendering: arXiv **2212.04356**, Appendix **D.2** *Multilingual Transcription*
— **Table 10** *WER (%) on MLS* (p. 23), **Table 11** *WER (%) on CommonVoice9* (p. 23), **Table 13**
*WER (%) on Fleurs* (p. 24) — each with **languages as columns and `Whisper tiny/base/small/medium/large`
as rows**. `https://arxiv.org/pdf/2212.04356` (read 2026-09-11). One analysis reported that this table does
not exist; the ar5iv rendering truncates the paper mid-D.1.1, which is how that false negative arose (§7).

**The German caveat, stated exactly, because it is the one thing gating the cheapest win in the research.**
The repo's `README.md` is **180 bytes and Xet/LFS-stored**, so Hugging Face does not parse it: the model
page shows *"empty or missing yaml metadata in repo card"* and `api/models/…` reports
`license: Not specified`, `cardData: Not provided` — both of which I confirmed today. Fetched at
`resolve/main`, the file's entire content is front matter and reads verbatim:

```
---
license: apache-2.0
datasets:
- mozilla-foundation/common_voice_17_0
language:
- de
metrics: [wer, cer]
pipeline_tag: automatic-speech-recognition
tags: [icefall, sherpa]
---
```

(`https://huggingface.co/daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de/resolve/main/README.md`,
read 2026-09-11, my own fetch.) So the grant is **author-declared in a file the platform does not surface**.
For a paid app that is a **one-email confirmation**, not a green cell — and it is the highest-leverage email
in this document. The rest of the German row I verified directly:

- **int8 files + digests**, from the tree API with LFS oids: encoder **70,133,342**
  (`e0163b48f89a81fafc4eb1804a77cdd33646970160f5d954a82774dc86e93fa5`), decoder **540,689**, joiner
  **259,417**, `lang_bpe_500/tokens.txt` **5,086** ⇒ **70,938,534 B**.
- **WER**, from the repo's own summary files: `exp/epoch-30/wer-summary-{test,dev}-greedy_search-epoch-30-avg-1-chunk-32-left-context-128-context-2-max-sym-per-frame-1.txt`
  ⇒ `greedy_search 10.58` and `greedy_search 8.58`.
- **Token file**: exactly **502 lines**, 5,086 B; the only pieces containing a digit are `#0` and `#1`; the
  only pieces containing lowercase are the three specials; `▁` at id 3; entries are ALL-CAPS German BPE
  (`▁DIE`, `EN`, `ER`).
- **ONNX metadata** (HTTP Range read of the int8 encoder's tail, my own): `model_type=zipformer2`,
  `comment=streaming zipformer2`, `model_author=k2-fsa`, `decode_chunk_len=32`, `T=45`,
  `num_encoder_layers=2,2,3,4,3,2`, `encoder_dims=192,256,384,512,384,256`, and **`query_head_dims` /
  `value_head_dims` present** — the two keys `OnlineZipformer2TransducerModel` reads and whose absence is
  fatal (§2.2).

**What that adds up to (INFERRED from the four reads above):** German is the only candidate in the survey
that needs **no** model-seam change at all — same family, same hard-coded `modelType`, same 320 ms cadence,
same vocabulary shape, so `PreviewText.normalize`, the `before()` concatenation rule and even the
**digits-canary recipe** transfer unchanged. The two costs it does carry: its files live in
subdirectories with **commas in the names** (`exp/epoch-30/encoder-…-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx`,
`lang_bpe_500/tokens.txt`) while `StreamingPack.urlOf` is `baseUrl + file.name` and the installer writes one
flat name per file (`StreamingPackCatalog.kt:36`) — so either a small `path`/`name` split or a re-mirror
(+0.25 d); and Kokoro ships **no German voice** (`TtsVoices.kt` covers es, fr, hi, it, ja, pt, zh), so its
canary clip must come from FLEURS or a recording.

### 1.2 What the published numbers let anyone conclude — the free pre-screen

Because the whisper-small per-language table exists, four rows can be pre-screened **before any engineering**
against rung 3's own decision rule (*"> 1.5× `multi` → strip-only; > 2× → stop"*,
`docs/measurements/2026-09-10-tab-sherpa-rung3.md:257`). Same-corpus comparisons only:

| Lang | Candidate, streaming greedy | whisper-small, same corpus family | Ratio | Read |
|---|---|---|---|---|
| **fr** | **9.95** CommonVoice-fr | **22.7** CV9-fr | **0.44×** | comfortably better than the final |
| **de** | **10.58** CommonVoice-17 de, streaming | **13.0** CV9-de | **0.81×** | better than the final |
| **id** | **11.58** CV · **8.96** FLEURS | **18.4** CV9 · **16.3** FLEURS | **0.63× / 0.55×** | better on both |
| **ru** | **11.3** CV-ru (card metric) | **15.0** CV9-ru | **0.75×** | better, on a weaker source |
| **ko** | 8.25 % **CER** @chunk-64 | 19.6 **WER** FLEURS | — | **not comparable** — different metric |
| **zh** | NOT PUBLISHED at chunk-16 | 29.4 CV9 · 20.8 FLEURS | — | must be measured |
| **bn** | NOT PUBLISHED | **104.4** FLEURS · **118.6** CV9 | — | **the gate is meaningless**: whisper-small does not work in Bengali |

Three cautions that keep this a pre-screen and not a result. **(i)** Corpus versions differ (CommonVoice 17
vs CV9) and Whisper applies its own normaliser, so these are indicative ratios, not the gate. **(ii)** All
of them are read speech; the app's VAD produces flatline-gated audio, which rung 1 found to be the shipped
model's single largest differential weakness (WER 0.182 clean → **0.318** gated,
`2026-09-10-zipformer-en-pc-rung1.md`). **(iii)** The metric trap is real and this project already knows the
rule: Whisper's normaliser spaces out only *Chinese, Japanese, Thai, Lao and Burmese*, so a "CER" from a
Korean card cannot be set beside Whisper's Korean **WER**.

### 1.3 Licence-clean streaming models that need a DIFFERENT runtime

This is the correction that matters most for Spanish. **es/de/it/pt/nl/pl/tr/hi/ja are not a licence wall —
they are a runtime wall.**

**Vosk (Kaldi), Apache-2.0, per language.** Read at `https://alphacephei.com/vosk/models` (2026-09-11, my
own fetch, licence column quoted verbatim as **"Apache 2.0"** for every row below):

| Lang | Model | Size | Published WER (page's own text) | vs whisper-small CV9 | Ratio |
|---|---|---|---|---|---|
| **es** | `vosk-model-small-es-0.42` | 39M | 16.02 (cv test), 11.21 (mls) | 10.3 | **1.56×** |
| **de** | `vosk-model-small-de-0.15` | 45M | 13.75 (Tuda-de test) | 13.0 | ~1.06× (different set) |
| **it** | `vosk-model-small-it-0.22` | 48M | 16.88 (cv test) | 16.0 | **1.06×** |
| **pt** | `vosk-model-small-pt-0.3` | 31M | 32.60 (cv test) | 12.5 | **2.61× → STOP** |
| **nl** | `vosk-model-small-nl-0.22` | 39M | 22.45 (cv test) | 14.2 | **1.58×** |
| **pl** | `vosk-model-small-pl-0.22` | 50M | 18.36 (CV test) | 16.9 | **1.09×** |
| **tr** | `vosk-model-small-tr-0.3` | 35M | TBD | 23.7 | unknown |
| **hi** | `vosk-model-small-hi-0.22` | 42M | 20.89 (IITM) | 43.6 | better |
| **ja** | `vosk-model-small-ja-0.22` | 48M | 9.52 (csj **CER**) | 14.0 WER | not comparable |

Runtime cost, DOCUMENTED by my own reads: `com.alphacephei:vosk-android` is on Maven Central, latest
**0.3.75**, and `vosk-android-0.3.75.aar` is **13,472,638 B** (`repo1.maven.org`, `Last-Modified
2025-12-08`). That is a **second ASR runtime in the APK** beside sherpa's, with its own partial contract
(Kaldi's `PartialResult` is a growing hypothesis that *may* change), a Kaldi-generation accuracy penalty,
and a per-language licence read that this page happens to answer cleanly for these nine rows but does not
for all of Vosk (other rows on the same page carry CC-BY-NC-SA / AGPL / GPLv3).

**Moonshine Streaming, MIT, per language (en, es, de, ja; tiny adds zh, ar, vi, tl).** The deployment
artefacts are an `.ort` set, and their byte counts **are** pinnable from the publisher's own host — I
HEAD-ed all eight files per model today at `https://download.moonshine.ai/model/<name>/quantized_26_08_24/`
(the docs' printed `quantized_26_08_21` tag 404s for non-English):

| Model | Total | Files |
|---|---|---|
| `small-streaming-es` | **121,800,392 B** | adapter 2,869,296 · cross_kv 5,358,752 · decoder_kv 61,314,512 · encoder 44,358,376 · frontend.weights 7,769,280 · frontend.model 26,776 · tokenizer.bin 102,888 · streaming_config 512 |
| `small-streaming-de` | **121,800,823 B** | as above, tokenizer 103,319 |
| `tiny-streaming-es` | **32,316,573 B** | 8 files |
| `tiny-streaming-de` | **32,317,004 B** | 8 files |

Accuracy is the best in the field for the size: **es small 4.9 %**, **de small 7.5 %** (FLEURS + MLS),
en small 2.61 % LS-clean. The blocker is the runtime, twice over: there is **no Maven artifact**
(`search.maven.org?q=g:ai.moonshine` → `numFound: 0`) and the in-repo Android binding links a **vendored
ONNX Runtime** — a second ORT beside the one inside `libsherpa-onnx-jni.so`; and the shipped AAR has **no
`OnlineMoonshine*` class at all** (only `OfflineMoonshineModelConfig`), so sherpa's Moonshine support is
offline-only. Eight files per language also does not fit `StreamingPack`'s four named slots.

**Kroko (Banafo) — the commercial option, and the two questions to ask.** Read at `https://kroko.ai/`
(2026-09-11, my own fetch), verbatim: *"Kroko currently supports 13 languages, with more in active
development: Bulgarian, Dutch, English, French, German, Hebrew, Italian, Polish, Portuguese, Spanish,
Swedish, Swiss German, Turkish, Japanese coming soon"*; *"On-Device SDK From $150/mo"*, *"iOS, Android &
browser / WASM"*, *"Licensing by app scale & redistribution"*; *"The commercial models add lower-latency
options and deliver more than 20 % lower word error rate than the community models."* Their HF card
(`https://huggingface.co/Banafo/Kroko-ASR`, 2026-09-11): *"Apache-2.0 engine. Models licensed separately
(CC-BY-SA community or commercial OEM)"* and *"built on top of Sherpa-ONNX"*.

Two corrections to how this has been written up so far. **(a)** CC-BY-SA is **not** a non-commercial
licence — it permits commercial use; the disqualifiers for a closed paid APK are the ShareAlike-on-adaptation
ambiguity plus the card's own `license: other` / `license_name: "test"` and a **0-byte LICENSE** file. Using
"DEAD END" for both Kroko and a genuinely non-commercial row (the Vietnamese `hynt/Zipformer-30M-RNNT-Streaming-6000h`
at **cc-by-nc-nd-4.0** — non-commercial *and* no-derivatives) flattens a distinction that matters.
**(b)** *"therefore needs zero new native bytes"* is an **inference** from "built on sherpa-onnx", and
kroko.ai **does not state the format or runtime of the commercial weights** — I checked specifically. So the
owner's email must ask **two** things: does the licence permit redistribution inside a Play-distributed paid
app, and do the commercial weights ship as sherpa-format int8 encoder/decoder/joiner + tokens loadable by
our own sherpa build, or only through their SDK?

### 1.4 Dead ends, recorded so nobody re-finds them

| Row | Why it is closed |
|---|---|
| `bookbot/sherpa-onnx-zipformer-streaming-robust-es-v0` | apache-2.0, streaming, right shape — and its README says it *"was trained to predict sequence of phonemes, e.g. `["w","ɑ","ʃ","i","ɑ"]`"*. A strip painting IPA is worse than no strip. **Check `tokens.txt` before believing any size.** |
| `hynt/Zipformer-30M-RNNT-Streaming-6000h` (vi) | **cc-by-nc-nd-4.0** — non-commercial **and** no-derivatives. A true dead end. |
| **PengChengStarling** (8 languages, apache-2.0 on `stdo/PengChengStarling`) | Licence is fine — the *door* is missing. Language is selected by replacing `<SOS>` with a `<langtag>`, and `langtag` matches **zero** of the ASCII strings in the shipped `libsherpa-onnx-jni.so`, zero in upstream v1.13.7's own streaming server, and exists only in the project's private fork. Its 8 languages also include no Spanish or German. |
| Trilingual / bilingual **Paraformer** | FunASR `MODEL_LICENSE`, commercial-**silent**, and **no published WER of any kind**. |
| Streaming **SenseVoice** | **Does not exist.** No `OnlineSenseVoice*` class in the shipped jar; the third-party project's own title is *"**Pseudo** Streaming SenseVoice"*. |
| `microsoft/VibeVoice-ASR-Streaming-1.5B` | MIT, 10 languages — and **5,628,388,290 B** of safetensors. Not a phone previewer. **WATCH.** |
| Whisper re-transcribe loop (LocalAgreement) | The NPU bills a **fixed** 30 s encode at ~1.78 s warm per pass with no `audio_ctx` floor; LocalAgreement-2 needs two agreeing passes ⇒ **≥ 3.6 s** confirmed-word lag against a measured **0.401 s**. Whisper stays the finalizer. |
| **ML Kit GenAI Speech Recognition (Basic)** | 15 locales covering almost the whole gap list, streaming partials, **zero APK bytes** — and *"offered in alpha, and is not subject to any SLA or deprecation policy"* against terms barring production use of Preview/Experimental services. **The best WATCH item in the research**; re-check at GA. |
| **QNN / NPU escape hatch for any of this** | Closed three ways: QNN is **not compiled into the shipped AAR** (*"Please rebuild sherpa-onnx with `-DSHERPA_ONNX_ENABLE_QNN=ON`"* is in the binary), sherpa's QNN SoC floor is *"a Qualcomm SM8350 processor or a newer"* which **excludes the MediaTek Tab (MT6989) entirely**, and on the `npu-turbo` tier the Hexagon is already running the model whose output gets typed. |

### 1.5 The licence verdict for a paid app, in one column

**SHIP:** en (shipping) · fr · id (MIT) · et-en (MIT) · zh / zh-en · fa (CTC) — apache-2.0/MIT, corpus
counsel per row. **SHIP AFTER ONE EMAIL:** **de** (author-declared apache-2.0 in an unparsed README).
**SHIP AFTER COUNSEL:** ko (AI-Hub / KsponSpeech), ru (mirror untagged, upstream apache-2.0), bn (same, and
fp32-only). **COMMERCIALLY CLEAN BUT REFUSED ON COST:** Nemotron-3.5 (OpenMDW-1.1, *"ready for commercial
use"*). **BUY:** Kroko. **CLEAN LICENCE, WRONG RUNTIME:** Vosk (Apache-2.0), Moonshine (MIT).
**DEAD END:** Kroko community weights as-is, the Vietnamese CC-BY-NC-ND row, Silero (CC-BY-NC), Kyutai
(no Android/ONNX path at all). **COMMERCIAL-SILENT ⇒ HOLD:** FunASR/Paraformer lineage, the English
`online-punct-en` weights (unreadable, unchanged since 2026-09-09).

---

## 2. ROUTE A — ONE PACK PER LANGUAGE

### 2.1 Why this is the route whose cost is fully known

No new native bytes, no AAR bump (1.13.7 already carries everything needed), no new runtime, no new UI
surface, and **no Auto ruling** — because `StreamingPackCatalog.forLanguage(null)` already returns null
(`:73`), pinned by `StreamingPackCatalogTest.onlyEnglishHasAPackAndAutoHasNone` with the message *"auto
(null) never resolves to a pack — the gate reads the RESOLVED language"*. The catalog's own KDoc says the
design intent out loud (`:43-44`): *"A second language is a second row whose licence cell is green first —
no per-language cards, no chooser change."* Everything downstream of the catalog is already pack-generic:
`StreamingPackManager` takes a `pack` in every method, and `StreamingPackInstall` iterates `pack.files`
everywhere (`:80, 97, 313, 318, 347`).

**Play is not the constraint.** Re-verified at
`https://support.google.com/googleplay/android-developer/answer/9859372` (2026-09-11, three independent
lanes, identical text): individual asset pack **1.5 GB**; base/feature module 500 MB; cumulative modules +
install-time **4 GB**; cumulative on-demand + fast-follow **30 GB**; total per app **34 GB**; **maximum 100
asset packs**; all as **compressed download size as computed by Play Console at upload**. All four current
packs are on-demand, so **4 of 100 slots are used and ~96 remain**; at 71–128 MB a language the **pack count
binds long before the bytes** (96 × ~100 MB ≈ 9.6 GB against ~24.9 GB of on-demand headroom). A 199 MB
addition (fr + de) takes the bundle from a measured **5,086,892,220 B** to ≈**5.29 GB** (INFERRED — int8
ONNX is near-incompressible, though note the in-bundle listing shows `preview_en`'s ONNX files *do* deflate,
73 MB → 59,181,612 B, so the AAB will grow by somewhat less than the payload).

### 2.2 What changes in the code — and the three things that are defects, not polish

**DOCUMENTED, verified line by line in the main checkout.**

**(1) `warm()` is idempotent on the ENGINE, not the PACK.** `StreamingPreviewEngine.kt:109`
`fun warm(packDir, pack)`; `:110` `if (disabled) return`; `:113` `if (recognizer != null || disabled)
return@execute` — **before** `factory.load(packDir, pack, …)` at `:116`. The guard tests the recognizer's
existence and never looks at `pack`. The service holds **one** resident engine in **one** field (`:541`),
reused via `streamingPreview ?: …` (`:3030`), and the gate's `previewReady` is that engine's `isWarm()`
(= `warm && !disabled`, `:106`) at `:3461`. So `warm(frenchDir, FR)` after `warm(englishDir, EN)` is a
**no-op**, `isWarm()` stays true, a `pack != null` predicate arms, and the **English recognizer decodes
French onto the strip while the gate logs `preview=1`**. The fix surface is pack identity on the engine plus
`release()` (which exists at `:222-234` and deliberately preserves `disabled`) on a language change; the
reload bill is the measured **802–860 ms load + 20–28 ms warm-up decode**.

**(2) `modelType` is hard-coded and wrong for French — and the failure is crash-class.**
`SherpaPreviewRecognizer.kt:46` sets `modelType = "zipformer2"` unconditionally. The French repo's own
export script names `pruned_transducer_stateless7_streaming` with `--zipformer-downsampling-factors` and
`--decode-chunk-len 32`, i.e. **zipformer v1**
(`https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/raw/main/export-onnx-stateless7-streaming.sh`,
2026-09-11), and a Range read of its int8 encoder shows `model_type="zipformer"`, `version 1`, no
`query_head_dims`. At **v1.13.7** — the AAR in the APK — `OnlineTransducerModel::Create` short-circuits on a
non-empty `config.model_type` and never consults the encoder metadata, so `"zipformer2"` **forces**
`OnlineZipformer2TransducerModel` against a v1 encoder, which then reads `query_head_dims` through
`SHERPA_ONNX_READ_META_DATA_VEC` whose miss path is `SHERPA_ONNX_EXIT(-1)` = **`_Exit(-1)`**. That is an
immediate process kill: it never reaches `warm()`'s `catch` (`:114-121`), `onLoadFailure()`,
`markCorrupt(pack)` or `disabled`. **A family/`modelType` mismatch is a crash, not a caught load failure**,
and any ruling written as "load failure or garbage" must be rewritten. Two fixes work: a per-pack
`modelType` field, or setting it to `""` and letting the encoder metadata decide (smaller, and valid for
every candidate). Either way `:46` must change before row 2. **German needs neither** — its metadata says
`zipformer2` (§1.1).

**(3) The English canary cannot pass for a non-English model, and a Fail is silent and permanent.**
`PreviewCanary.run` feeds `CanaryAudio.samples()` — `assets/canary_digits.wav`, **81,998 B**, 16 kHz mono
PCM16, **2.560 s of "one two three four five"** — in 512-sample chunks, pads `PAD_MS = 500`, calls
`inputFinished()`, drains, and scores with `GpuCanaryPolicy.canaryPasses` (`PreviewCanary.kt:32-61`), whose
rule is `EXPECTED_TOKENS` = `setOf("one","1")` … `setOf("five","5")` with `MIN_MATCHES = 4` and
`MAX_TOKENS = 20` (`GpuCanaryPolicy.kt:33-45`). A non-Pass releases the recognizer and sets
`disabled = true` for the **process** (`StreamingPreviewEngine.kt:136-142`), and the sentence for that
state, `StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE`, is recorded by the 4.4.0 acceptance sheet as
**rendered nowhere** (`acceptance.md:519`, `:666`; `progress.md:49`). So a naive second pack produces
**silently no live words, forever, with no explanation** — and with a shared `disabled`, one bad pack kills
live words in **every** language until the process restarts.

**The fix: a per-pack clip + per-pack alias table, and a pack with no canary asset does not ship.** Cost
**81,998 B per language** = **0.0016 %** of the 5.09 GB AAB (ten languages ≈ 820 KB = 0.016 %).
`GpuCanaryPolicy` itself **must not change** — its verdict is a permanent per-(versionCode, model, device)
latch for whisper's GPU path (`:18-20`); extract the scoring into a shared pure function, or give the
previewer its own policy object. Spoken digits remain the right content: short, high-frequency, in every
model's vocabulary, and checkable by a non-speaker (`un deux trois quatre cinq`; `eins zwei drei vier fünf`;
`один два три четыре пять`; `satu dua tiga empat lima`). **For fr and de this works unchanged** — both token
files are digit-free ALL-CAPS `▁`-BPE, which I verified for German myself and which the fr audit reports.
**Korean needs numeral aliases too** (its 2,460-token vocabulary contains standalone digits).

**Chinese breaks the canary RULE, not just the clip.** `GpuCanaryPolicy.normalize` splits on
`[^\p{L}\p{Nd}]+` (`:47-51`) and `canaryPasses` rejects on `isEmpty()` or `size > MAX_TOKENS` (`:54-57`). At
**v1.13.7** `online-recognizer-transducer-transducer-impl`'s text assembly ends with
`text = RemoveSpaceBetweenCjk(text)`, which drops every space between two CJK characters — so a pure-Hanzi
utterance reaches `normalize` space-free, Hanzi are `\p{L}`, and the whole output normalises to **one
token**: `MAX_TOKENS` is structurally unreachable and per-position alias matching is meaningless. A zh canary
needs a different rule (character-set overlap + a length band). Combined with the copy's noun changing from
"words" to "characters", **zh costs the most design per byte** despite being #10 with a Kokoro voice.

**Everything else, mechanically.**

- **The gate.** `localPreviewArms`' first conjunct `sessionLanguage == "en"` (`:237`) becomes
  "there is a pack for this resolved language, and it is installed". Because `packInstalled` is computed
  separately from `EN` at `:3453`, **pack identity must flow into the gate**, not just the language.
  `LocalPreviewGateTest`'s truth table and `LocalPreviewWiringPinTest.kt:62-73` (which pins the literal call
  text and the `== 1` wrap site) both move. The row pinned at `LocalPreviewGateTest.kt:57-60` — a Spanish
  pick on an ENGLISH-scope tier still arms English, because whisper types English there — **survives by
  construction**, and it is the invariant the whole gate defends.
- **The two hard-coded `EN` reads** at `:3452` and `:3028` become `forLanguage(lang)`. `:3028` is inside
  `warmStreamingPreview()`, which has **no language parameter** and is also called by the boot prewarm at
  `:936` with no session at all — so "which language do we warm before the user speaks?" is a new decision
  (recommendation: `getSelectedLanguage()`, and warm nothing on Auto, until §4's seed).
- **The copy.** Eight strings name English (`StreamingPackCopy.kt:62, 71-72, 86-87, 90, 116, 149-152`) and
  `BADGE` is a **class-init `val`** computed once from `EN.totalBytes` (`:49`) that appears in seven
  sentences — with fr at 128 MB, de at 71 MB and ru at 29 MB, every sentence carrying a size must take the
  pack. The good news: the language **name** is already in the app
  (`PreferencesManager.SUPPORTED_LANGUAGES` is `("fr" to "French")`, `("de" to "German")`, …), so this is a
  **lookup over a template**, not a per-language string table, and `StreamingPackCopyTest`'s verbatim pins
  stay verbatim. Add **three capability flags** to `StreamingPack` — `emitsCase`, `emitsPunctuation`,
  `emitsDigits`, plus `normalizeLocale` — each **read off `tokens.txt`**, so a pack whose model punctuates
  can never ship under a sentence saying it does not. New strings inherit the house rules: no banned speed
  words (`faster, instant, real-time, …`), no `\d+ms` claim, and every install string must contain the
  literal `"typed transcript"` (`StreamingPackCopyTest.kt:129-146`).
- **A real defect in the Settings fetch shell.** `StreamingPackController` is an `object` with **one**
  `_state` (`:90`), one `job` (`:97-98`), one **private** `activePackName` with no accessor (`:106-107`) and
  a **global** `isBusy()` (`:113-115`); `LivePreviewRows` collects that single flow unconditionally and
  renders `StreamingPackCopy.fetchLine(previewFetch)` (`SettingsScreen.kt:1093-1094`) with no comparison
  against which pack owns the in-flight fetch. Keeping the download **single-flight** across languages is
  the right call (two 100 MB Play fetches at once is worse for the user), but the row must compare
  `activePackName` — which, because that field is private, is a **controller** change, not only a composable
  one.
- **Six census spellings per pack, forever**, each held equal by a named pin in `PreviewPackLayoutTest`:
  the module `build.gradle.kts` (`:81-101`), the `.gitignore` payload wall + `.gitkeep` (`:135-151`), a
  **new** `include(":preview_xx")` in `settings.gradle.kts` (`:103-116`, which pins the existing include
  lines exactly — append, never rewrite), an append to the **one** `assetPacks` list (`:118-133`, asserting
  `count(appGradle, "assetPacks +=") == 1`), the placement table + verify task
  (`app/build.gradle.kts:904-954`, pinned at `:188-215`), and `sourcePinnedInputs`
  (`app/build.gradle.kts:542-543`, pinned at `:220-229` — omit it and an edit confined to the new module
  leaves `testDebugUnitTest` **UP-TO-DATE against stale evidence**, which `:537-541` states verbatim).
  `tools/build_asset_packs.py`'s `PREVIEW_MODULE` / `PREVIEW_BASE_URL` / `PREVIEW_FILES` /
  `DEFAULT_PREVIEW_MIRROR` (`:740-758`) are module-level singletons and must become table-driven.
  **Pay this once**: generalise the Gradle task and the Python placer over a list at pack 1, don't copy them.

### 2.3 Per-language cost

**First language: 5–7 engineering days**, because it pays for the seam (0.5 catalog + fields · 1.0 engine
pack identity · 0.5 gate + pins · 1.0 canary · 1.0 copy · 1.0 Settings · 0.75 Gradle generalised ·
0.5 Python table-driven · 0.5 the row itself). **Each language after that: ~2.5 days** — catalog row with
four re-hashed digests (0.5) · module + six census spellings (0.25) · build-script row (0.1) · canary clip +
alias table (0.5) · copy flags + name (0.1) · three test rows (0.25) · a PC rung 1 (0.5) · a device rung 3
read (0.25 of our time plus owner device hours).

**Surcharges, so nobody is surprised:** zipformer **v1** +0.5 d (fr pays it once) · a **CTC** family +1.5 d
(file-count change + a second config branch — fa) · a **non-int8** publication +1 d (quantize and own the
digests — bn) · a **non-16-kHz** model +0.5 d (per-pack `FeatureConfig.sampleRate` — the 8 kHz T-one
Russian) · **zh/ja** +1.5 d (canary rule, copy noun, wrapping) · **subdirectory/comma file paths** +0.25 d
(de, unless re-mirrored) · a **native-speaker read** of the strip per language, which is owner time and not
ours. **These day figures are INFERRED**: no estimate in this repo is recorded against an actual. What is
DOCUMENTED is that every site they price exists at the line cited.

### 2.4 The staged order, and why

| Rank | Lang | # | Why this position |
|---|---|---|---|
| **1** | **fr** | 3 | Highest-ranked picker language with a licence cell that needs no email. **It forces the `modelType` field** (zipformer v1), which every later non-zipformer2 pack needs anyway — so the seam gets its hardest easy case first, at a desk instead of on a device. Broadest training mix of any row (LibriSpeech + GigaSpeech + CommonVoice; **GigaSpeech's own terms are a counsel item**). Kokoro ships `ff_siwis`, so its canary is synthesizable **in-house**. |
| **2** | **de** | 4 | **The cheapest row in the survey**: 71 MB, `zipformer2`, 320 ms cadence, English-shaped vocabulary ⇒ **no model-seam change, no copy invention, the digits canary transfers**. Published *streaming* greedy WER that beats whisper-small on two panels. Gated on **one email** (§1.1) and a FLEURS/recorded canary clip (no Kokoro German voice). One copy row of its own: **German nouns arrive lowercase**, which a German reader reads as *wrong*, not *rough* — the sentence must say "no capitals, **including nouns**". |
| **3** | **ru** | 9 | **28,572,945 B — 39 % of the English pack.** Its cheapness is the argument: it proves the multi-pack machinery at the lowest byte and download cost anyone could ask for, on a different script with an already-lowercase vocabulary (so the copy flags get exercised without CJK problems). Needs the mirror's licence provenance nailed down and a FLEURS canary. |
| **4** | **id** | 18 | Out of picker order **deliberately**: it is the row with the **least unknown** in the table — MIT, `zipformer2`, ALL-CAPS `▁` digit-free vocabulary, and the **only** non-English row with a four-set published WER, one set of which is **spontaneous YouTube speech** (YODAS 11.90). Counsel on YODAS2. |
| **5** | **ko** | 12 | apache-2.0 README, 73 MB, spontaneous corpus, and the **only** published sherpa-onnx int8 RTF outside this project. Gated on counsel over the **AI-Hub / KsponSpeech** agreement, and on the fact that its published CER is at chunk-64 while we would run chunk-16. |
| **6** | **zh / zh-en** | 10 | Last despite #10 and a Kokoro voice, because it costs the most **design** per byte (§2.2) and neither candidate publishes a WER at our chunk. Pick by probe between `multi-zh-hans-2023-12-12` (72,470,080 B, drop-in), the 2023 bilingual (198,270,793 B — and **only 1.73 MB** under Play's 200 MB cellular-confirmation line), and `pkufool/zipformer-medium-streaming`. |

**One probe worth an hour before any of this: `pkufool/zipformer-medium-streaming`** (apache-2.0,
lastModified 2026-06-25). Chinese **and** English, chunk-16/left-128, and it publishes the thing no other
non-English row publishes — an **eight-set table including out-of-domain sets**: transducer head
LibriSpeech **3.64 / 8.08**, gigaspeech 12.13, CommonVoice-en 18.97, tedlium 10.9, aishell 3.90/4.79,
CommonVoice-zh 12.41. That is within **0.6 / 0.3 points** of the shipped English model's LibriSpeech floor
while also covering Chinese. It is a **probe, not a pack**: the only int8 files are the **CTC head**
(74,410,051 B — a **two-file** pack `StreamingPack`'s four slots cannot express), the transducer ships
fp16/fp32 only (159,434,359 B mixed), its card's stated eval config (chunk-16/left-128) **matches no
exported file** (exports are chunk-16-left-**64**, chunk-32-left-**128**, chunk-64-left-**256**), and
**sherpa-onnx compatibility is UNVERIFIED** — the project ships its own runtime and mentions sherpa nowhere.
If it loads, the zh row becomes "74 MB, Apache-2.0, published out-of-domain WER at the English floor, and it
covers English too."

**Not in the first six, and the owner must hear it first: es (#2), it (#5), pt (#6), nl (#7), pl (#8).**
Route A cannot serve them on the runtime already shipping, at any price short of Kroko's licence or a second
runtime.

---

## 3. ROUTE B — ONE MULTILINGUAL MODEL

### 3.1 The honest comparison

`nvidia/nemotron-3.5-asr-streaming-0.6b` is the only real candidate, and the 2026-09-09 finding is
**CONFIRMED and now exact**.

**What is genuinely good about it (DOCUMENTED, model card + sherpa sources + the shipped artefact).**
Licence **OpenMDW-1.1** with NVIDIA's own *"This model is ready for commercial use"* — the licence is the
good news. 600 M params, 40 locales in three tiers (**19 transcription-ready**: en, es, fr, it, pt, nl, de,
tr, ru, ar, hi, ja, ko, vi, uk …), five real chunk profiles (80/160/320/560/1120 ms), natively punctuated and
cased, and `target_lang=auto` detects the language per utterance. **Size is independent of the chunk
profile**: the four-file sherpa-convention int8 totals are **682,215,469 / 682,215,471 / 682,215,356 /
682,215,474 B** at 80 / 320 / 560 / 1120 ms — a full spread of 118 B, so choosing 320 ms over 1120 ms costs
accuracy, never bytes.

**And the plumbing is already in the APK — one sibling map was wrong about this.**
`OnlineStream.setOption(String,String)` and `getOption(String)` are `public final` Kotlin methods in the
shipped `classes.jar` (238,364 B), each with a `private final native` half, and
`Java_com_k2fsa_sherpa_onnx_OnlineStream_setOption` plus `prompt_dictionary`, `auto_prompt_id`,
`GetLanguagePromptId`, `InitLanguagePromptIds`, `nemo_transducer` and *"Unsupported language '%s' for
multilingual NeMo transducer; using auto"* are all strings in the shipped
`jni/arm64-v8a/libsherpa-onnx-jni.so` (4,761,536 B). The v1.13.7 C++ reads it **per stream**
(`GetLanguagePromptId(ss[i]->GetOption("language"))`) and selects the NeMo impl by **decoder output arity >
1**, before any `model_type` dispatch on the app's `provider = "cpu"` config. `ml-map-other` §6.1 checked
`OnlineModelConfig` *fields* — where there is indeed no language field — and drew the wrong conclusion. **So
Route B needs zero new native bytes and lands as two one-line changes inside
`SherpaPreviewRecognizer.createStream()`, which the engine already calls afresh on every commit**
(`StreamingPreviewEngine.kt:151, 191, 346`). Nemotron is blocked on **bytes and compute**, not plumbing —
which is what makes its probe cheap and its refusal evidence-based rather than architectural.

**Why it is still refused.**

| Axis | Nemotron-3.5 | The shipped previewer, MEASURED |
|---|---|---|
| Pack bytes | **682,215,471 B** — over Play's **200 MB cellular-confirmation** line, so the first language gets a consent dialog and a Wi-Fi wait | **72,654,782 B**, under it |
| Resident RAM | **≥ ~1.1 GB, UNMEASURED on ORT** (see below) | **+169 MB** RSS over a 609 s real-time run, peak +191 MB |
| RTF @2 threads, 320 ms | **UNMEASURED** — the only phone figure is 0.416 at **4 threads**, on **LiteRT**, on a **981 MB** partially-quantized graph, at the **560 ms** profile | **0.116** real-time / 0.054 max-pace |
| Partial lag | **UNKNOWN** (see below) | **p50 0.401 s / p95 0.523 s**, 0 retractions in 134 runs |
| Model load | **UNMEASURED**; the repo's own span is 1.6–11.8 ms/MB, so 2–8 s | **802–860 ms** |
| **English accuracy** | FLEURS-en **8.27** (LangID) / **8.84** (auto) @320 ms | the default tier is small.en, **greedy**, FLEURS-en **6.0** ⇒ **+2.27 / +2.84** |

**On the memory number, precisely.** The community LiteRT card reports *2.02 GiB sustained resident PSS* on
an S23 Ultra — but that same card reports FP32 (2.49 GB graph) → 2.46 GiB PSS and FP16 (1.27 GB graph) →
**3.59 GiB** PSS, i.e. half the bytes reporting 46 % *more* memory, so its ordering is not trustworthy; and
its INT8 is a **981,082,800 B** partially-quantized graph, 1.44× the sherpa ONNX int8's 682 MB. Scaling the
app's own measured **2.33× weights** ratio gives ≈1.59 GB, but that is single-point linear scaling with no
intercept. **The defensible statement is: ≥ ~1.1 GB resident, UNMEASURED on this runtime** — in a service
whose own KDoc says *"a background overlay holding 342 MiB to 1.02 GiB is trimmed routinely"*
(`FloatingBubbleService.kt:259-260`) and whose `onTrimMemory` releases the previewer at
`TRIM_MEMORY_RUNNING_LOW` (`:4121-4132`).

**On lag, precisely.** The lag model `structural + chunk_s × RTF` reproduces the shipped 0.401 s to 4 ms —
but the compute term is only **9 %** of that total, so a 4 ms agreement validates the intercept, not the
coefficient, and extrapolating it 3.6–7× is not evidence. Worse, the chain's own correction is not
propagated: RTF(320 ms) is **1.2–1.7× RTF(560 ms)**, which on 0.416 gives **0.50–0.71 at four threads**,
before correcting to the app's **two** (`StreamingPreviewTuning.kt:9`). **Lag is UNKNOWN until RTF(320 ms,
2 threads, ORT, on device) is measured.** "Lag is not the blocker" is an unvalidated extrapolation.

**On the non-English accuracy claim, precisely.** Nemotron @320 ms LangID does beat whisper-small on FLEURS
in most languages where both numbers exist (es −1.21, de −1.37, fr −5.21, it −4.97, pt −1.49, ru −1.53,
hi −30.99, zh −0.77, ja +0.22 wash). **Two corrections keep this directional rather than assertable.**
**(i) FLEURS is in Nemotron's own training blend** — its card lists *"Multilingual LibriSpeech (MLS),
Mozilla Common Voice, FLEURS, VoxPopuli"* under Training Data and FLEURS again under Evaluation, while the
Whisper paper §3.1 states *"we evaluate Whisper in a zero-shot setting without using any of the training
data for each of these datasets"*. The whole table is an in-domain model measured against a zero-shot one on
the in-domain model's own corpus: direction of bias known, magnitude unbounded. **(ii) The Korean row
compares CER to WER** — Nemotron reports ko/ja/zh as **CER**, and Whisper's normaliser spaces out only
Chinese, Japanese, Thai, Lao and Burmese, so Table 13's **ko 19.6 is a genuine WER** against Nemotron's
7.27 CER. The −12.33 is a metric artefact. Net: at most **8 of 10 comparable**, all on a corpus Nemotron
trained on.

### 3.2 Why Route B is not the simplification it claims to be

**Nemotron cannot replace `preview_en` — it can only sit beside it.** English is the default tier
(`DEFAULT_MODEL_ID = "pro"` → `small.en`, `ModelScope.ENGLISH`, `WhisperModel.kt:193-202`), the language the
owner tested and praised, and the one where the shipped previewer's numbers are the best in the whole
research. Nemotron regresses it by **+2.27 / +2.84** points (against Table 8's **greedy** small.en FLEURS-en
of **6.0**, which is the right basis because the app decodes greedily — `app/src/main/cpp/whisper_jni.cpp:839`
`whisper_full_default_params(WHISPER_SAMPLING_GREEDY)`), and that figure is an in-domain number against a
zero-shot one, so the true regression is larger.

So a shipped Route B is `preview_en` (72.7 MB, Zipformer, lowercase-unpunctuated normalisation, English
canary) **plus** `preview_multi` (682 MB, NeMo family, as-is normalisation, its own canary, its own gate,
its own copy) — **two families, two normalisation policies, 755 MB of packs, and a gigabyte-class resident
model on the non-English path**. The "one model, no per-language packs" version of Route B exists only if
English regresses, and English is not allowed to regress. (One extra-work item **is** already handled
upstream: sherpa's docs say *"sherpa-onnx strips these language tags so the returned transcript stays
clean"*, and `strip_lang_tags` matches zero strings in the shipped `.so`, consistent with sherpa stripping
in its own decoder — so no tag-stripper is needed.)

### 3.3 One probe day, six kill lines stated in advance

The 320 ms **and** 560 ms int8 packages through the shipped AAR in the existing rung-3 probe harness
(`tools/probes/litertlm-probe` already has a `sherpa` mode, `ProbeRunner.kt:27` — **no versionCode, no app
code, no acceptance sheet**), on the Tab and the Fold6, with `modelType = ""` so arity routes it and
`stream.setOption("language", …)` on a fresh stream:

| Kill line | Threshold | Basis |
|---|---|---|
| RSS delta over 10 min | **≤ 600 MB** | measured +169 MB for 72.7 MB of weights |
| RTF, real-time pace, **2 threads** | **≤ 0.35** | measured 0.116 |
| Partial lag p95 | **≤ 0.8 s** | measured 0.523 s |
| Retractions | **0** | measured 0 in 134 runs; a retracting previewer is a different UI contract |
| Model load | **≤ 3 s** | measured 802–860 ms; beyond 3 s the trim-release cycle makes the feature unreliable |
| Spanish + English canaries a human reads | pass | there is no automated way to trust a language nobody here speaks |

**RSS is the one I expect to fail.** If all six pass, Route B becomes the better product for
es/de/it/pt/nl/tr — and it ships as a **second** pack, never as a replacement. If any fails, Route B is
closed for phone CPU.

### 3.4 What would change the answer

A **streaming** checkpoint that is **≤ 250 MB on disk at int8** (≤ 200 MB to clear Play's cellular line),
**commercially licensed**, covers **Spanish and German in one file**, and emits **orthographic words**.
250 MB is not arbitrary: at the measured 2.33× weights ratio it lands at ≈580 MB RSS, just inside the kill
line. Three concrete releases would satisfy it, and **none exists as of 2026-09-11**:

1. **A sub-0.6 B `nemotron-3.5-asr` distillation.** NVIDIA publishes exactly **one**
   `nemotron-3.5-asr` model (`?author=nvidia&search=nemotron-3.5-asr` → 1 row). The entire plumbing is
   already in the shipped AAR, so a 0.2 B sibling would be a **drop-in**: one pack, one `setOption`, Auto for
   free, 19 locales. **Highest-value watch item in the research, and the cheapest to re-check — one API call.**
2. **A multilingual-in-one-checkpoint `moonshine-streaming-small`.** The size is already right (123 M
   params, MIT, per-language WER published); today it is per-language, and its Android path is an NDK build
   against a second ORT, so it would also have to reach sherpa's runtime.
3. **An upstream per-stream `language`/`langtag` option for PengChengStarling.** Not a new model at all —
   the exact mechanism already exists in the same codebase for the NeMo transducer. Smallest intervention,
   largest payoff, and it is an upstream request rather than a release. **But its 8 languages include no
   Spanish and no German**, so it fixes Route A's coverage, not Route B's problem.

**Two things that would NOT change the answer:** a *bigger* multilingual model (VibeVoice 5.6 GB, Fun-ASR
1.97 GB `.pt`, FireRedASR2S with an **offline** ASR core), and a QNN/NPU route (§1.4).

---

## 4. THE AUTO PROBLEM

### 4.1 The headline is not multilingual at all

**Today an Auto user on a multilingual tier gets no live words even while speaking English, with the 73 MB
English pack installed and warm.** `sessionLanguageFor` returns the raw selection on a multilingual tier
(`FloatingBubbleService.kt:206-212`), `PreferencesManager.getLanguageForApi()` maps `"auto"` → **null**
(`:178-181`), and `localPreviewArms`' first conjunct is `sessionLanguage == "en"` (`:237`). Pinned by
`LocalPreviewGateTest.autoOnAMultilingualTierIsWhisperOnly` and by acceptance row **Z4**
(`acceptance.md:575-579`), which is a **promote-gate** row (`:677`).

And the app pays for it anyway: `:3460` is `if (packInstalled && userEnabled) warmStreamingPreview()` with
**no reference to `lang`**, and the boot prewarm at `:936` does the same. So the device already spends the
measured **+169 MB** and an **802–860 ms** load for a recognizer the gate then switches off. **Closing this
is not "zero new bytes" — the bytes are already spent.**

### 4.2 The recommendation

```
effectivePreviewLanguage =
    sessionLanguage                                  // an explicit pick always wins, unchanged
        ?: lastDetectedLanguage                      // persisted; written ONLY from a real detection
        ?: deviceLanguageCode(Locale.getDefault())   // the mapper already exists
                                                     // ?: null → the floor: today's behaviour

previewPack = StreamingPackCatalog.forLanguage(effectivePreviewLanguage)   // already written, :73
armed = previewPack != null && isInstalled(previewPack) &&
        !cloud && !batch && userEnabled && previewReady
```

Then **name the guess on the strip's first paint**, let whisper's own utterance-1 detection either confirm
it or **stand the previewer down for the session** (never swap — a swap is a ~1 s silent gap) while
persisting the detected code for next session, and never let the seed touch the typed transcript,
`LanguagePin`, or `reportable`.

**The whole argument is one asymmetry, and it must ship in writing.** The previewer never allocates or
resolves a seq (`PreviewTeeEngine.kt:18, 61-64`) and whisper's final sweeps its text
(`PreviewComposer.kt:64-68`), so a wrong **preview** language costs throwaway words on a replace-only strip
and **nothing typed**. That is the opposite stake to the committer. **State the divergence precisely,
though:** `NpuDecodePolicy` does **not** forbid locale for the committer — its heading is *"Detection rather
than device locale FIRST"* (`:270-272`), it **does** feed `family.langToken(localeCode)` into the decode
prompt (`:314-320`), and only `reportable` is suppressed for LOCALE/FALLBACK (`:251-255`). The honest
divergence is about **ordering**: the previewer seeds *before* any detection exists; the committer reaches
for locale only *after* detection fails. That is a narrower claim and therefore an easier one to land.

**The blast radius, stated correctly.** `PreviewComposer.resolve` returns remaining frozen prefixes **plus
the live partial** (`:70-81`), so wrong words are visible for **utterance-length + F per utterance** — and
for *every* utterance of the session unless the stand-down ships. F is 1.9 s (npu-turbo) / 2.3 s (Fold6
CPU); on the Tab, **utterance 1 of an Auto session is ~9–12 s**, not ≤ 6 s, because whisper.cpp pays a
throwaway detect-encode pass worth *"roughly half of `multi`'s steady-state native cost"*
(`LanguagePin.kt:6-9`) before the pin latches — and utterance 1 is exactly this lane's subject. **So the
stand-down is part of the day-one scope, not a follow-up.**

### 4.3 What utterance one looks like

- **Auto, English-locale phone, English speech, `preview_en` installed** (the common case): words from the
  first utterance at the measured p50 0.401 s, a one-line "guessing English, from your phone's language"
  chip on the first paint that the first real partial replaces, whisper's detection confirms `en`, the chip
  never returns. **No new download, no new RAM, no new CPU.**
- **Auto, English-locale phone, Spanish speech**: up to F seconds of English words with the guess named on
  screen, then one stand-down line, then today's "Transcribing…" behaviour for the rest of the session —
  and the **typed text is correct throughout**. Next session, `lastDetected = es` resolves to no pack, so no
  words on utterance 1. The seed learned.
- **Auto, Spanish-locale phone**: no words at all, today's behaviour exactly.
- **French picked on `pro`** (an ENGLISH-scope tier): **English** words, because whisper types English
  there. The invariant the gate defends, already pinned.

### 4.4 The copy

Two new sentences and one condition on both. The precedent for a suggestion with a stated reason is the
app's own `DEVICE_LANGUAGE_BADGE = "Your device's language"` (`OnboardingLogic.kt:71-73`).

```kotlin
const val PREVIEW_SEED_FROM_LOCALE      = "Live words in %s — your phone's language."
const val PREVIEW_SEED_FROM_LAST_SPOKEN = "Live words in %s — the language you last spoke."
const val PREVIEW_SEED_WRONG =
    "That was not %s — live words are off for this session. Your typed transcript is unaffected."
```

`"Your typed transcript is unaffected"` deliberately echoes `SETTINGS_DISABLED_ON_DEVICE`'s *"Your
transcripts are unaffected."* (`StreamingPackCopy.kt:83-84`) so the two failure sentences read as one voice.
**Two constants, not one**, because the *reason* differs and the reason is the whole point of naming the
guess.

**And one existing sentence becomes false, so it must be re-drafted rather than supplemented.**
`LANGUAGE_STEP_SENTENCE` (`:86-87`, rendered at `OnboardingFlowScreen.kt:613`) promises *"Live words on the
bubble are English-only for now; other languages show a progress line while each sentence is transcribed."*
For a seeded Auto user speaking Spanish on an English-locale phone, **both clauses fail**: they get English
words *and* no progress line, because ruling **R2** displaces the label whenever the preview owns the strip
(`inFlightStripLabel` returns null, `FloatingBubbleService.kt:405-411`; `renderInFlightStrip` returns early
on the same predicate, `:4330-4331`). With a second pack it is false anyway.

**Where the guess line goes:** put it through the composer's initial partial — the tee is the one `onDelta`
source (`PreviewTeeEngine.kt:79-96`), so seeding `composer.onPartial(seedLine)` at `connect()` puts the line
on the strip and **the first real partial replaces it**, with no render-rule change and no risk of it
reaching `freeze()` (which clears `partial` and stores only recognizer text, `PreviewComposer.kt:49-53`).
The alternative — returning the line from `inFlightStripLabel` while nothing is painted — **re-opens R2**,
which is a ruling, not a tidy-up.

### 4.5 The ranked alternatives, and the one probe that was missed

| Rank | Option | Verdict |
|---|---|---|
| **1** | **Seed from `lastDetected ?: deviceLocale`, pack-gated** | **DO THIS.** Zero new model bytes; the bytes are already spent. |
| 2 | **Whisper's utterance-1 detection as the CORRECTION** | Use as the correction, never as the primary: first word ≈2.7 s (turbo, already-warmed pack) to ~10 s (Tab, Auto utterance 1) against a 0.40 s promise. |
| 3 | A separate user-facing "live words language" setting | Cheapest of all, but it is a **pick** — and this lane is about a user who never picks. |
| 4 | **The app's own NPU detector on a PARTIAL buffer** | **The probe nobody costed.** `nativeDetectLanguage()` takes no arguments and answers against whatever `nativeEncode` last produced, guarded by *"a validity flag, not a one-shot token — a decode does NOT consume it"* (`qnn_asr.cpp:697-704`). So encode+detect on a partial buffer answers **before the cut**, in-process, **with a margin** (`:3434-3437`), for **zero new model bytes** and with no audio leaving the app. SoC-gated to SM8650/-AC and ~405 ms of encode per probe. Cheaper than option 5 and it was missed. |
| 5 | `android.speech.SpeechRecognizer` on-device streaming LID | The only source of a per-chunk answer with a **documented confidence enum** (`DETECTED_LANGUAGE` *"of the most recent audio chunk"*, `LANGUAGE_DETECTION_CONFIDENCE_LEVEL` ∈ {UNKNOWN, NOT_CONFIDENT, CONFIDENT, HIGHLY_CONFIDENT}), and `EXTRA_AUDIO_SOURCE` takes a `ParcelFileDescriptor` at PCM16/mono/16000 — the app's own capture format. **Probe, not plan**: every extra is documented *"may have no effect"* and availability on the Fold6/Tab is UNVERIFIED. |
| 6 | Nemotron `language=auto` | §3 — measure once, never schedule. |
| 7 | sherpa's offline `SpokenLanguageIdentification` | **REJECT — strictly dominated.** `DetectLanguage` is `lang_id = all_language_ids[0]` then `if (p > this_logit)`, returning a bare `int32_t` with **no confidence, no runner-up, no margin**; the impl pads to 30 s and runs the full encoder; the model is a **new ~99 MB download** (tiny int8: encoder 12 M + decoder 86 M + tokens 798 K). All to learn something the app already computes for free, **with** a margin. |
| 8 | N recognizers in parallel | **REJECT on RAM.** +169 MB each on top of npu-turbo's 1,071,685,632 B, in a service that releases the previewer at `TRIM_MEMORY_RUNNING_LOW` with a 4,107 ms cold-load penalty. (The seam is *not* the blocker — `PreviewResult` dropping `ysProbs` is a two-line widening of a data class the app owns.) The free special case: the **zh-en bilingual**, which honours Auto inside its own pair with one recognizer. |
| 9 | Auto keeps no live words | **The floor** — and it must remain the behaviour whenever no seed and no pack exist. |

**Two things that must land before the seed, or it is a defect:** pack identity on
`StreamingPreviewEngine` (§2.2 item 1 — latent with one pack, live the first session whose seed differs from
the warmed language), and a **language for the boot prewarm**, which today warms `EN` with no session at all
and under a seed must warm the seed's pack and warm **nothing** when the seed resolves to nothing. And
`reportable`'s rule must be preserved (`NpuDecodePolicy.kt:251-255`) so a locale-seeded language can never
be persisted as "last detected" — otherwise the seed becomes **self-confirming** and one wrong guess is
sticky forever. Note this guard is needed on the **CPU tier only**: `NpuWhisperBackend.detectedLanguage`
already returns `reportable`, while `WhisperNativeBackend.detectedLanguage` is raw and its own KDoc warns
*"a ctx that never ran returns 'en', the lang_id 0 default, so there is NO in-band no-detection-yet signal"*.

**Acceptance:** **Z4** is the row this deliberately inverts and it is a promote-gate row — it needs an owner
**re-ruling**, not a rewrite, and its replacement needs both halves (words when the seed has a pack, no
words when it does not). **Z5** ("Spanish picked on `multi` ⇒ no live words") **survives unchanged**,
because an explicit pick still wins and there is no Spanish pack; only its *reason* changes from
"English-only" to "no pack for this language".

**And the framing the owner should hear:** "no Auto ruling required" is true of the *code* and false of the
*feature*. A German user on a multilingual tier with Auto — the default — gets no live words even after
`preview_de` ships, unless the seed lands. So: **Route A ships without an Auto ruling, and the first support
question after it ships will be about Auto.**

---

## 5. THE PLAN

### 5.1 Builds

| Stage | Version | What it is | Agent-days (INFERRED) | Device hours | versionCodes |
|---|---|---|---|---|---|
| **0** | none | Desk + probe work that can collapse later stages | **3–4** | ~3 | **0** |
| **1** | **4.5.0 / 91** | The generalisation (N packs possible) + **fr** + **de** | **8–10** | ~5 | 1 |
| **2** | 4.6.0 / 92 | Seeded Auto + the "guessing X" chip + the stand-down | **3–5** | ~3 | 1 |
| **3** | 4.7.0 / 93 | **ru + id** batched (and **zh** if its probe lands) | **5–6** | ~3 | 1 |
| **4** | 4.8.0+ | The owner-gated branch: Kroko's 13 · Vosk's nine at Apache-2.0 · Moonshine's MIT es/de/ja · Nemotron if the probe passes · or stop | owner-gated | — | 1 per release |

**Why `4.5.0` at `91`.** The project rule, written down: *"the **name** moves only when what the user sees
changes (patch = last digit; a new surface or coverage = minor)"*
(`docs/superpowers/research/2026-09-04-v69-v73-npu-and-galaxy-xr-research.md:609`, citing
`ReleaseIdentityTest`'s KDoc). A new language is new coverage **and** a new download **and** a new Settings
row. `app/build.gradle.kts:53-54` reads `versionCode = 90` / `versionName = "4.4.0"`, pinned by
`ReleaseIdentityTest.release_identity_is_4_4_0_at_version_code_90` — so **91**, provided nothing is uploaded
first (that KDoc's own history records **87** consumed by an upload that never shipped).

**Stage 0, ordered by "can this change the plan":**

| # | Task | Output | Days | Device |
|---|---|---|---|---|
| **0a** | **Email `daniel-dona`** to confirm the Apache-2.0 declared in the 180-byte Xet README | German = picker #4 unblocked, or a dead end | 0.1 | none |
| **0b** | **Token-file + ONNX-metadata audit** of every remaining candidate (ru ×2, zh, zh-en, ko, id, bn, et-en) | a row per language: `emitsCase/emitsPunctuation/emitsDigits/normalizeLocale/modelType/family/sampleRate` | 0.5 | none |
| **0c** | **PC rung 1** for fr, de, ru, id in one afternoon (§5.3) | WER vs corpus + **vs whisper-small's own output**, RTF, cadence, retractions | 1.0 | none |
| **0d** | **Pin the census**: download the int8 trio + tokens per Stage-1 language, hash, record exact bytes | four `PackFile` literals per language (fr **done**, de **done** — digests already in hand) | 0.25 | none |
| **0e** | **The Nemotron probe** (§3.3) | six kill lines answered; RSS first | 1.5 | ~2 h |
| **0f** | **The `pkufool` sherpa-loadability probe** | the zh row's answer, and possibly a better *English* previewer | 0.25 | none |
| **0g** | **The NPU partial-buffer LID probe** (§4.5 rank 4) | whether Auto's seed can be a real detection with a margin, for zero bytes | 0.5 | ~1 h |
| **0h** | Put the owner decisions (§5.6) in front of the owner | rulings | 0.25 | none |

**Stage 1's honest framing:** you cannot add row 2 without the generalisation, and you cannot validate the
generalisation without row 2. **They ship together.** French forces the `modelType` field; German proves a
second `zipformer2` row needs nothing beyond the row. If 0a comes back negative, Stage 1 is fr alone at
6–8 days and de is replaced by ru.

**The two release costs that are not Play's.** (i) **Every upload spends a versionCode and the internal
track is the only test route** — a local build can never install over the Play copy — so *app-level*
validation costs one release per **batch** of languages. That argues for batching once the route is proven.
(ii) **Model-level validation costs nothing**: `tools/probes/litertlm-probe` has a `sherpa` mode
(`ProbeRunner.kt:27`) that links the same AAR, and rungs 1 and 3 ran there, not in the app. **This split is
the single most important cost fact in the plan.**

### 5.2 The acceptance sheet

A new **§AA**, patterned on §Z (`docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md:468-694`):
two setup blocks (English picked, French picked, German picked); AA1/AA2/AA3 = words appear per language;
AA-typed per language, **with whisper `multi`'s output as the French/German reference, not 4.3.4's
English**; a five-minute read per language for lag/thermal; **Z4 unchanged in 4.5.0** and re-ruled in Stage
2; **Z5 survives** with its reason rewritten; AA13 = each pack fetches, installs, and its marker is written
last. Two new rows that did not exist:

- **AA-swap** — pick French, run a session, stop, pick English, run a session: **the strip must be English,
  not French.** This is §2.2 item 1's acceptance row and **it must fail on the un-fixed build.**
- **AA-mixed** — French picked, speak English (and the reverse): EXPECTED nonsense on the strip and
  **correct typed text**. The row that proves the additive promise under the worst input.

And a decision to take rather than inherit: **Z11 stays NOT EXECUTABLE** because
`SETTINGS_DISABLED_ON_DEVICE` is rendered nowhere. With two packs that gap is worse, not the same. Either
render it in 4.5.0 or carry it forward **explicitly**.

### 5.3 The measurement gate, per language

Same table as rung 3, because **the kill lines are device properties, not language properties** — split
into what a machine judges and what only a person can.

**Tier A — machine-judged, no speaker, in the probe app (zero versionCodes).** Partial lag p95 > 1.0 s;
RTF > 0.5 at 2 threads max pace; RTF/lag under a 4-thread load; **RSS delta > 400 MB**; thermal > 1
`thermalStatus` step or > 3 °C; **any retraction**; model load and warm-up (informational); the pad floor
re-derived per language; the canary. The English column is the reference: lag p50 **0.401** / p95
**0.523** s, RTF **0.054** max-pace / **0.116** real-time, RSS **+169 MB**, **+1.2 °C** with
`thermalStatus` 0→0, **0 retractions** in 134 runs, load 802–860 ms, pad floor 500 ms.

**Two corrections to how this table has been projected.** (i) **Do not scale RSS by a per-weight
multiplier.** Rung 3's own data says `after release` returns to 320 MB — *"the ORT/sherpa libraries stay
mapped after the recognizer is released"* — so **140 of the 169 MB survives releasing the recognizer and is
not weights**. With one weights data point, `F + k·weights` is unidentifiable: French lands anywhere from
+191 MB to +299 MB and German at ≈+167 MB (i.e. **below English**) under the additive reading. The
10-minute arm is worth running; "75 % of the kill line" is not a number to decide on.
(ii) **"The Tab is CPU-bound" is contradicted by rung 3's own beside-load arm**: with four cores
deliberately busy the previewer's RTF *fell* to 0.0985 and p95 *improved* to 0.511 s. At N=1 the Tab has
headroom. What genuinely has **never** been run is the previewer beside whisper — `acceptance.md:573` says
so verbatim: *"This is the streamer-beside-whisper thermal read rung 3 never ran."*

**Tier B — accuracy with no speaker, and this is the key move.** The reference transcript **comes with the
corpus**. Run the language's FLEURS and/or Common Voice test split through (i) the previewer and (ii)
whisper `multi`, compute both WERs with the repo's own `WerMath`, and gate on **the ratio** exactly as rung
3 did for English (`> 1.5× → strip-only; > 2× → stop`). Nobody needs to understand a word of it.

Reproduce `2026-09-10-zipformer-en-pc-rung1.md` per candidate, on the PC, holding constant: the venv
(`C:/Users/bastr/.androidbuild/sherpa-venv`, Python 3.13.7, the `sherpa-onnx` **1.13.7** wheel), the machine
(i7-8700K — so ratios are comparable), the config (`SherpaPreviewRecognizer.kt:34-50` verbatim: 16 kHz /
80-bin / dither 0, `greedy_search`, endpointing off, `provider = "cpu"`, `num_threads` 1 **and** 2, with
`model_type` **per candidate** or empty), the 512-sample feed loop with a **500 ms** zero pad before
`input_finished`, and `WerMath` ported verbatim. **The baseline runs in the same run**:
`sherpa_onnx.OfflineRecognizer.from_whisper` on `csukuangfj/sherpa-onnx-whisper-small` @
`8f3c18b358db4d1f2fc1eae49d75cd20989e4309`, `language=<lang>`, greedy, 2 threads. The published
whisper-small tables (§1.2) are a **pre-screen**; the in-run baseline is the **gate**, because corpus
versions, normalisers and decoding all differ.

**The clips cannot be reused; the clip RECIPE can.** Per language: (1) a ~2.5 s spoken-digits clip, which
doubles as the **canary candidate**; (2) **20 FLEURS test utterances** in that language, which give a real
WER instead of rung 1's two data points; (3) the same 20 re-run through the **flatline-gating script** that
produced `jfk-gated.wav`, because gated audio is the shipped model's largest differential weakness and the
app's own VAD produces exactly that; (4) five minutes of real dictation if anyone on hand speaks it.
**Check FLEURS's licence before committing any of its audio as a shipped fixture** — a PC evaluation is a
different question from an APK asset.

**Kill lines calibrated on the English precedent rather than invented:** WER vs **whisper's own output**
> 0.25 on the FLEURS set (English measured **0.182** clean / **0.318** gated, so 0.25 sits between them);
**any** retraction under greedy; RTF > 0.12 at 2 threads on the i7-8700K (English: 0.0798); partial cadence
materially above 320 ms → flag, do not stop; and a `tokens.txt` that is phonemic, lacks word boundaries, or
carries case the strip would destroy → **stop before any timing is measured** (the rule that catches the
Spanish phoneme trap in one minute).

**Tier C — the canary, per language.** §2.2. Source order: (1) **the app's own Kokoro voices**, which ship
for es, fr, hi, it, ja, pt, zh — synthesize a fixed phrase, commit the wav, and the expectation set is
correct **by construction**, so nobody has to be able to hear whether it is right; (2) a fixed clip from
the language's FLEURS split with its published reference (required for **de** and **ru** — no Kokoro voice);
(3) **never** the English `canary_digits.wav`. Write the caution into the code: a TTS-spoken canary proves
the graph computes, not that the model handles real voices — that is what Tier A's real-audio arms and Tier
B's corpus WER are for.

### 5.4 How a language ships with no native speaker to judge it

The owner is the only speaker available and does not speak French, German, Russian, Chinese, Korean or
Indonesian. Here is exactly how a language ships anyway, and exactly what does not.

1. **Every engineering risk is machine-judgeable.** Tiers A, B and C cover lag, RTF, RSS, thermal,
   retractions, load time, the pad floor, the canary and accuracy-relative-to-whisper. **None needs a
   speaker**, and a language that fails any of them does not ship.
2. **The linguistic risk is not — so the promise is narrowed to what `tokens.txt` mechanically proves.**
   `emitsCase`, `emitsPunctuation`, `emitsDigits` and `normalizeLocale` are each **read off the token
   file**, and the copy is **derived** from them. The app then *cannot* promise something only a speaker
   could confirm. **fr and de both pass this today**: 502-line ALL-CAPS `▁`-BPE, accents present, no digits
   ⇒ the existing "no capitals, no punctuation, no numerals" sentence is **provably** true. **ko fails
   half of it** (its vocabulary carries standalone digits) and **zh changes the noun**.
3. **The structural licence to ship at all: the previewer never commits.** A wrong French preview costs
   throwaway words on a replace-only strip; the typed transcript is whisper's multilingual output either
   way. **That asymmetry — and only that asymmetry — makes shipping a language you cannot personally judge
   defensible.** Write it down as a deliberate divergence from the committer's ordering (§4.2), or the next
   reader restores `== "en"` and is right to.
4. **Reading is cheaper than speaking, and it is a different ask.** A script the strip has never rendered —
   **RTL (ar, he)** and **Indic (hi, bn, ta)** — needs one person who can *read a screenshot*, once, ever.
   Not a speaker, not a session, not a recording. **Until that read exists, RTL and Indic packs do not
   ship**, because the bubble's geometry rules were written for LTR.
5. **Stage by script risk, not market size:** Latin without a case trap (**fr**, **de**) → Cyrillic
   (**ru**) → CJK (**zh/ja**, which change "words appear" to "characters appear" and make
   `PreviewText.normalize` a no-op) → RTL/Indic last, with (4)'s read → **tr never, until `normalize` takes
   the pack's locale** (`PreviewText.kt:9` is deliberately `Locale.US`, *"never a Turkish dotless i"* —
   correct for English *input*, wrong for Turkish *output*).
6. **German gets its own copy row even though no speaker is needed to spot it**: German nouns arrive
   lowercase on the strip, which a German reader reads as *wrong*, not *rough*. The sentence must say
   "no capitals, **including nouns**".
7. **The reversibility that replaces pre-ship certainty.** There is **no telemetry in this app** — the
   fleet reports nothing — so a bad language cannot be discovered from data. Two mitigations, both already
   built: the per-pack **delete** row (`StreamingPackCopy.kt:66-69`) and the user-facing enable switch
   (`localPreviewEnabled`, default true). **Say it in the release notes:** a new language's live words are a
   preview, deletable, and never the typed text.
8. **What does NOT ship without a speaker**, stated so it is not quietly skipped: any claim about *quality*
   in that language beyond the corpus WER ratio; any copy promising punctuation, casing or numerals; any
   language whose script has not been read on a device screenshot; and Turkish.

### 5.5 Device sessions

| Session | Device(s) | What runs | Hours |
|---|---|---|---|
| S0-a | Tab (MT6989) | Nemotron 320/560 ms int8, rung-3 arms, six kill lines | ~2 |
| S0-b | Fold6 (SM8650-AC) | NPU partial-buffer LID probe; ML Kit `checkStatus()` on both | ~1 |
| S1-a | Tab + Fold6 | fr + de rung 3: lag, RTF, RSS, retractions, **and the beside-whisper thermal read rung 3 never ran** | ~3 |
| S1-b | Tab + Fold6 | §AA sheet on the track build: AA1/2/3, AA-typed, **AA-swap**, AA-mixed, AA13, Z4 | ~2 |

### 5.6 Owner decisions

See the structured list returned with this document. The one that outranks every engineering item: **what
happens to Spanish** — Kroko's licence (two questions to ask Banafo), a second runtime (Vosk Apache-2.0
39 MB at ~1.56× whisper-small, or Moonshine MIT 121.8 MB at 4.9 % WER), Nemotron if the probe passes, or
don't offer it.

---

## 6. RISKS, RANKED

| # | Risk | Why it ranks here | Mitigation |
|---|---|---|---|
| **1** | **A second pack ships without pack identity on the engine**, and the English recognizer decodes French while the gate logs `preview=1` | It is silent, it looks like a bad model rather than a bug, and the acceptance sheet has no row that would catch it today | The three §2.2 fixes land **before** row 2, and **AA-swap must fail on the un-fixed build** |
| **2** | **A family/`modelType` mismatch kills the process** | At v1.13.7 the miss path is `_Exit(-1)` — no catch, no `onLoadFailure`, no `markCorrupt`, no `disabled`. A crash in an overlay service is the worst failure mode in the app | Per-pack `modelType` (or `""` + metadata); verify every candidate's ONNX `model_type` **at a desk** (0b) before it reaches a device |
| **3** | **The German licence is author-declared in a file Hugging Face does not parse** | The whole "two of the top four languages" answer rests on it, and the platform reports `license: Not specified` | One email (0a). If it comes back negative, de drops out and Stage 1 is fr alone |
| **4** | **A canary Fail disables live words process-wide, silently, for every language** | Shared `disabled` + a sentence rendered nowhere = a user whose feature stopped with no explanation and no way to diagnose it | Per-pack `disabled`, per-pack clip + alias table, and render `SETTINGS_DISABLED_ON_DEVICE` — or carry the gap forward explicitly |
| **5** | **Corpus clearances, per row** | An `apache-2.0`/`mit` tag is the repo owner's tag. fr's **GigaSpeech** lineage, id's **YODAS2** (YouTube-derived), ko's **AI-Hub KsponSpeech**, zh's *"internal multilingual dataset"*, ru's unreadable statement. This is a paid app | Counsel per row before a pack is built. **de is the cleanest row in the table on this axis** — CommonVoice 17.0 only |
| **6** | **RSS beside whisper has never been measured** | Rung 3's beside-load arm used a synthetic busy loop and its thermal arm ran the previewer **alone**; the previewer-beside-whisper read *"rung 3 never ran"*. The Fold6 holds npu-turbo's 1,071,685,632 B resident | S1-a runs it on both devices; the additive RSS reading (not the multiplicative one) is the projection to trust |
| **7** | **The copy drifts from what the model emits** | Eight strings name English, `BADGE` is a class-init `val`, and `LANGUAGE_STEP_SENTENCE` becomes false the moment a second pack or a seeded Auto ships | Three capability flags **derived from `tokens.txt`**, a language-name lookup over a template, and `StreamingPackCopyTest`'s verbatim pins over the template |
| **8** | **Auto ships without the stand-down** | The wrong-language exposure is then utterance-length + F for **every** utterance of the session, not one utterance — and on the Tab utterance 1 alone is ~9–12 s | The stand-down is day-one scope for the seed, not a follow-up |
| **9** | **The AAB reaches ~5.3–5.5 GB** | Build and upload time at that size is **UNMEASURED** in this repo, and every upload spends a versionCode on a track-only test route | Batch languages per release once the route is proven; keep Play's count ceiling (96 free slots) in view rather than its byte ceiling |
| **10** | **Discovery gap: no open-web keyword sweep happened in any lane** | All four lanes hit `WebSearch` 200/200 before their first query. The catalogue is complete *with respect to* the HF/GitHub APIs, sherpa's releases and docs, icefall, and vendor pages — a September-2026 model with no HF presence is invisible here. **The German model was missed for a whole research cycle by exactly this class of error** (an LFS-stored card defeating an API-driven sweep) | Re-run discovery with a real search budget before Stage 3; re-check `?author=nvidia&search=nemotron-3.5-asr` (one API call) each cycle |
| **11** | **Turkish ships by accident** | `lowercase(Locale.US)` on `İ` is orthographically wrong, and the comment at `PreviewText.kt:9` says the locale was chosen for English on purpose | `normalizeLocale` on `StreamingPack`, and **tr does not ship until it exists** — free today, since tr has no model |
| **12** | **`StreamingPackController` narrates the wrong pack's download** | One global `_state`, one private `activePackName`, global `isBusy()` — a French row will narrate an English fetch | Keep the fetch single-flight; compare `activePackName` in the row (a controller change, not only a composable one) |

---

## 7. WHAT WAS REFUTED

Adversarial passes ran over all four analyses; 34 of 48 claims stand, 9 were weakened and kept with their
caveat, 5 were dropped. The ones that changed the answer:

| Claim | Ruling | Basis |
|---|---|---|
| *"German has no commercially-licensed word-emitting streaming model; Route A reaches six of 54 languages and es/de/it/pt are all absent"* | **REFUTED** | `daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de` declares `license: apache-2.0` in a 180-byte Xet-stored README (my own `resolve/main` fetch), ships a four-file int8 export of **70,938,534 B** with all four sha256s, carries `model_type=zipformer2` / `decode_chunk_len=32` in its ONNX metadata, has a 502-line digit-free ALL-CAPS `▁`-BPE vocabulary, and publishes **greedy streaming WER 10.58 test / 8.58 dev** — all verified by me today. The reach is **eight** four-file int8 languages, and **two of the app's top four**. The original read failed because `raw/main` returns an LFS pointer and the models API therefore reports no licence. |
| *"The Whisper paper contains NO per-language-by-model-size WER table, so 'within a distance of whisper-small' has no published denominator for any language"* | **REFUTED** | arXiv 2212.04356 Appendix **D.2** — Table 10 (MLS), Table 11 (CommonVoice9), Table 13 (Fleurs) — each with languages as columns and model sizes as rows. I extracted them from the PDF (`https://arxiv.org/pdf/2212.04356`, read 2026-09-11): whisper-small FLEURS en 6.1, es 5.6, de 10.2, fr 15.0, it 9.8, pt 7.3, ru 11.4, zh 20.8, ko 19.6, ja 12.0, id 16.3, bn 104.4, nl 16.4, pl 14.7. **ar5iv truncates the paper mid-D.1.1**, which is how the false negative arose. The *method* half of the claim survives as a recommendation (§5.3), not a necessity. |
| *"whisper-small's FLEURS-en 6.6 was an ar5iv error superseded by 6.1"* | **REFUTED (conclusion kept, basis corrected)** | Both numbers are in the paper and the difference is the **decoding method**: Table 8 (greedy) small = **6.6**, small.en = **6.0**; Table 13 (beam+fallback) = 6.1. The app decodes **greedily** (`whisper_jni.cpp:839`) and its default tier is **small.en**, so Nemotron's English regression is **+2.27 / +2.84**, stated against Table 8's 6.0 — never as "6.6 was wrong". |
| *"Spanish/German/… have no licence-clean word-emitting streaming model — it is a licence and corpus wall"* | **REFUTED** | Three separate licence-clean options exist for Spanish (Vosk Apache-2.0 39 MB; Moonshine MIT 121,800,392 B; Nemotron OpenMDW-1.1) and Nemotron is licence-clean and word-emitting for **all eleven** named languages. The wall is **runtime and cost**, not licence. Also: **CC-BY-SA is not non-commercial** — Kroko's real disqualifiers are the ShareAlike-on-adaptation ambiguity for a closed APK, `license: other` / `license_name: "test"`, and a **0-byte LICENSE**. Using "DEAD END" for both Kroko and the genuinely non-commercial CC-BY-NC-ND Vietnamese row flattens a distinction that matters. |
| *"N recognizers in parallel is blocked by the seam, not by RAM — `PreviewResult` drops `ysProbs` so no winner policy is expressible"* | **REFUTED** | The facts are right and the ranking inverts which blocker is hard: widening a data class the app owns is two lines with no AAR or native change, while +507 MB on top of npu-turbo's 1,071,685,632 B in a service that releases the previewer at `TRIM_MEMORY_RUNNING_LOW` is unfixable. (Also: `decodeStreams` **is** exported from the shipped `.so` — only the Kotlin declaration is missing — and it batches streams of *one* recognizer, so it was never the mechanism for N different ones.) |
| *"Moonshine's per-language `.ort` sizes cannot be pinned from any published page"* | **REFUTED** | HEAD-ing all eight files per model at `download.moonshine.ai/model/<name>/quantized_26_08_24/` gives exact `Content-Length`s: small-es **121,800,392 B**, small-de **121,800,823 B**, tiny-es **32,316,573 B**, tiny-de **32,317,004 B** (my own reads, 2026-09-11). The docs' printed `quantized_26_08_21` tag 404s for non-English, which is why it looked unpinnable. Digests still need a download. |
| *"sherpa-onnx's Kotlin API exposes no language field, so Nemotron's multilingual switch is unreachable from Kotlin"* | **REFUTED** | `OnlineStream.setOption(String,String)` is `public final` in the shipped `classes.jar` with a native half exported from the shipped `.so`, and the v1.13.7 C++ reads it per stream. The error is field-vs-option: the language is **per-stream**. Nemotron is blocked on bytes and compute. |
| *"Nemotron is refused on RAM (2.02 GiB measured / 1.59 GB inferred) and lag is not the blocker"* | **WEAKENED, kept** | The interval is not supportable — the LiteRT card's own FP16 row reports **more** PSS than its FP32 row, and its INT8 is a 981 MB partially-quantized graph on a different runtime. **Say: ≥ ~1.1 GB, UNMEASURED on ORT.** And lag is **UNKNOWN**, not fine: the chain's own RTF(320) ≈ 1.2–1.7× RTF(560) correction lands 0.50–0.71 at four threads before correcting to two. |
| *"Nemotron beats whisper-small in 9 of 11 non-English languages"* | **WEAKENED, kept** | **FLEURS is in Nemotron's training blend** (its own card) while Whisper's evaluation is explicitly zero-shot; and the Korean row compares Nemotron's **CER** to Whisper's **WER** (Whisper's normaliser spaces out only zh/ja/th/lo/my — **not** ko). At most **8 of 10 comparable**, all in-domain for Nemotron. |
| *"A wrong preview language costs at most F seconds of throwaway words, and `NpuDecodePolicy` forbids locale-first for the committer"* | **WEAKENED, kept** | The bound is **utterance-length + F per utterance**, for every utterance of the session unless the stand-down ships; the Tab's Auto utterance 1 is ~9–12 s, not ≤ 6 s. And `NpuDecodePolicy` **does** feed locale into the decode prompt (`:314-320`) — only `reportable` is suppressed (`:251-255`). The divergence is about **ordering**, which is a narrower and more landable claim. |
| *"Pack-gating the seed means no shipped sentence becomes false"* | **WEAKENED, kept** | `LANGUAGE_STEP_SENTENCE`'s second clause fails for exactly the user the design exists for: a seeded Auto Spanish speaker gets English words **and** no progress line, because R2 displaces the label. And two seed sentences are needed, not one — the reason differs and the reason is the point. |
| *"The platform is the only reachable streaming LID with a confidence level"* | **WEAKENED, kept** | The app's own `nativeDetectLanguage()` can run on a **partial** buffer before the cut — *"a validity flag, not a one-shot token"* (`qnn_asr.cpp:697-704`) — in-process, with a margin, for zero new bytes. Cheaper than the ML Kit probe and it was missed. The platform remains the only source of a per-chunk answer with a documented confidence **enum** on both devices. |
| *"The Tab is CPU-bound because whisper-small shares the cores"* | **WEAKENED, kept** | Rung 3's own beside-load arm measured RTF **falling** to 0.0985 and p95 **improving** to 0.511 s under a deliberate 4-core load. At N=1 the Tab has headroom. The two-devices-two-constraints framing survives; "CPU-bound" does not. |
| *"French is projected at +299 MB RSS = 75 % of the kill line"* | **WEAKENED, kept** | Rung 3 records that `after release` returns to 320 MB because *"the ORT/sherpa libraries stay mapped"* — **140 of the 169 MB is not weights**, so scaling the whole delta by a per-weight multiplier double-counts. The 10-minute arm is worth running; 75 % is not a number to decide on. **German lands ≈+167 MB, below English, under the additive reading.** |
| *"The 4.4.0 AAB is 99.27 % pack payload and the arithmetic closes to 0.7 %"* | **WEAKENED, kept** | The in-bundle listing eleven lines below the cited one gives base **37,947,714** + BUNDLE-METADATA **12,581,627** and `preview_en` compressing to **59,181,612** — so the pack share is **99.00 %** and the real decomposition closes to **0.0014 %**. The claim compared uncompressed payload against a compressed archive; two errors cancelled. **Play is still not the constraint** and the count still binds before the bytes. |
| *"icefall's `multi_zh-hans` streaming rows are offline (chunk_size −1)"* | **REFUTED (conclusion kept)** | Two of the four rows are explicitly labelled *"CTC Greedy **Streaming**"* and *"Transducer Greedy **Streaming**"*; `--chunk_size -1` appears only in the single offline decode command printed above the table. What survives: those are the ~160 M Large and ~700 M XL checkpoints, **not** the ~70 MB 2023-12-12 candidate, and **no chunk size is published for any streaming row** — so zh must still be measured. |
| *"Play's 200 MB line is a per-pack limit, so the 198 MB zh-en pack clears it by 1.73 MB"* | **WEAKENED, kept** | The 200 MB figure is an **app-download** warning on Play's limits page; the fetch-time gate is Google's own asset-delivery documentation: *"If the download is larger than 200 MB and the user is not on Wi-Fi, the download does not start until the user explicitly gives their consent"* (`developer.android.com/guide/playcore/asset-delivery/integrate-java`, 2026-09-11) — cellular-only, `WAITING_FOR_WIFI` / `REQUIRES_USER_CONFIRMATION`. So the comparison was against the wrong limit and right by accident; the conclusion (a 682 MB Nemotron pack trips it, 29–198 MB packs do not) holds at the correct source. |
| *"Kroko converts eleven languages from impossible to one catalog row each, and needs zero new native bytes"* | **WEAKENED, kept** | kroko.ai **does not state the format or runtime of the commercial weights** (my own read); "zero new native bytes" is an inference from *"built on top of Sherpa-ONNX"*. And "one row each" is false for at least two of the eleven by this plan's own rules (tr is refused until per-pack locale; he is RTL and refused until a screenshot read exists), and de is no longer "impossible". **Ask Banafo two questions, not one.** |
| *"A per-language canary clip costs 0.016 % of the AAB"* | **CORRECTED** | 81,998 / 5,086,892,220 = **0.0016 %** per language. 0.016 % is the ten-language figure. |
| *"`pkufool/zipformer-medium-streaming` is within 0.6/0.3 points of the English floor"* | **STANDS, with a caveat that changes the probe** | Its card's stated eval config (chunk-16 / left-128) **matches no exported file** — the exports are chunk-16-left-**64**, chunk-32-left-**128**, chunk-64-left-**256** — so the probe cannot test the published numbers at the published config and must choose less left context than English or a 640 ms cadence. The card also does not state its decoding method while the 3.06/7.79 baseline is explicitly greedy. |
| *"`sherpa-onnx` streaming models are per language (en, zh, zh-en, ko, bn)"* — prior research §2.7, 2026-09-09 | **REFUTED AGAIN, and further** | Add fr, **de**, ru ×2 (one a T-one CTC), id, fa ×3, uz, et-en, yue, plus three NeMo English families, Kroko in 10–13 languages, and two 3+-language packs. |
| *"The only Auto-capable streaming model is a 682 MB Nemotron scoring 1.3–2.2 points below the English floor"* — prior research §2.3 | **CONFIRMED, and now exact** | 682,215,471 B at the 320 ms export; OpenMDW-1.1 with *"ready for commercial use"*; the gap at the chunk that matches this app's measured lag is **+2.27 / +2.84** against the app's actual default tier. The prior range was right for the wrong chunk and the wrong baseline. |
| *"No streaming model covers three or more languages"* — prior research §2.7 | **REFUTED, and now bounded** | Nemotron-3.5 (40 locales), PengChengStarling (8), trilingual Paraformer (3), VibeVoice (10). **What survives after two independent recency sweeps: no *small* (≤ 250 MB) multilingual streaming model exists at any licence.** |
| *"Streaming SenseVoice"* (the brief's list) | **DOES NOT EXIST** | No `OnlineSenseVoice*` class in the shipped jar (full 122-class enumeration); the third-party project's own GitHub description is *"**Pseudo** Streaming SenseVoice with Hotwords"*. |

---

## 8. SOURCES

**This checkout (read-only, `main` @ `3f982b9`)** — `app/build.gradle.kts:53-54` (version), `:248`
(asset packs), `:530-547` (`sourcePinnedInputs` + the UP-TO-DATE reasoning), `:657-674` (AAR pin), `:904-954`
(placement table + `verifyPreviewPack`), `:1023` (AAR dependency);
`service/FloatingBubbleService.kt:206-212, 220-244, 405-411, 541, 936, 3020-3037, 3436-3480, 4116-4156`;
`transcription/stream/{StreamingPackCatalog.kt:24-88, StreamingPackCopy.kt:49-152, StreamingPreviewEngine.kt:51-234, 307-366, SherpaPreviewRecognizer.kt:24-62, PreviewRecognizer.kt:24-42, PreviewCanary.kt:14-61, PreviewText.kt:1-26, PreviewTeeEngine.kt:17-96, PreviewComposer.kt:15-81, StreamingPackController.kt:83-292, StreamingPackInstall.kt:75-385, StreamingPackManager.kt:35-175, StreamingPackTuning.kt:9-24}`;
`transcription/{GpuCanaryPolicy.kt:18-70, CanaryAudio.kt:19-95, LanguagePin.kt:1-52, LocalWhisperEngine.kt:45, 78-79, 113, 138, 447, 542-543, TranscriptionEngine.kt:107-226, WhisperModel.kt:193-296, WhisperNativeBackend.kt:190-201, 518, NpuWhisperBackend.kt:650-660, 727, 821-826}`;
`npu/{NpuDecodePolicy.kt:251-365, NpuFleetCensus.kt:118-290}`; `app/src/main/cpp/{whisper_jni.cpp:839, qnn_asr.cpp:697-704, 3405-3486}`;
`data/local/PreferencesManager.kt:178-181, 541-597`; `ui/onboarding/OnboardingLogic.kt:40-95`;
`ui/screens/SettingsScreen.kt:1080-1094`; `ui/screens/OnboardingFlowScreen.kt:129, 613-628`; `tts/TtsVoices.kt:28-55`;
`settings.gradle.kts:17-35`; `preview_en/build.gradle.kts:5-37`; `tools/build_asset_packs.py:740-992`;
`tools/probes/litertlm-probe/.../ProbeRunner.kt:27`; tests
`{PreviewPackLayoutTest, StreamingPackCatalogTest, StreamingPackCopyTest, LocalPreviewGateTest, LocalPreviewWiringPinTest, LivePreviewRowsPinTest, ReleaseIdentityTest}`.

**Measurements and prior research in this repo** —
`docs/measurements/2026-09-10-tab-sherpa-rung3.md` · `2026-09-10-zipformer-en-pc-rung1.md` ·
`2026-09-10-tab-turbo-e2e-gpu.md` · `2026-09-10-startup-cutoff-investigation.md` ·
`2026-09-09-tab-apu-probe.md` · `2026-09-10-gemini-live-probes-t0.md` ·
`docs/superpowers/research/{2026-09-09-streaming-local-tier-research.md, 2026-09-09-tab-turbo-gemma-scribe-gemini-live-research.md, 2026-09-04-v69-v73-npu-and-galaxy-xr-research.md, 2026-08-29-pad-soc-delivery.md}` ·
`docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md:468-694` ·
`.superpowers/sdd/2026-09-10-440-streaming-previewer/progress.md`.

**The shipped artefact** — `app/libs/sherpa-onnx-1.13.7.aar` (49,113,869 B, sha256 pinned at
`app/build.gradle.kts:659`): `classes.jar` (238,364 B) via `javap -p`; `jni/arm64-v8a/libsherpa-onnx-jni.so`
(4,761,536 B) via ASCII-string scan; `libonnxruntime.so` (21,684,880 B).

**Web — verified by this synthesizer today (2026-09-11)** ·
`https://huggingface.co/daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de` + `/resolve/main/README.md`
+ `/resolve/main/lang_bpe_500/tokens.txt` + `/resolve/main/exp/epoch-30/wer-summary-{test,dev}-…txt`
+ `api/models/…?blobs=true` + `api/models/…/tree/main/exp/epoch-30` + an HTTP Range read of the int8
encoder's metadata tail ·
`https://arxiv.org/pdf/2212.04356` (Appendix D Tables 8, 10, 11, 13, extracted with pypdf) ·
`https://ar5iv.labs.arxiv.org/html/2212.04356` (confirmed truncated) ·
`https://alphacephei.com/vosk/models` ·
`https://repo1.maven.org/maven2/com/alphacephei/vosk-android/{maven-metadata.xml,0.3.75/vosk-android-0.3.75.aar}` ·
`https://download.moonshine.ai/model/{small,tiny}-streaming-{es,de}/quantized_26_08_24/*` (HEAD) ·
`https://kroko.ai/`.

**Web — read by the four lanes on 2026-09-11, cited above** ·
`https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b{,/raw/main/README.md}` ·
`https://huggingface.co/{csukuangfj2,Lorqa,apbaxel}/…nemotron-3.5-asr-streaming…` (four chunk profiles, `?blobs=true`) ·
`https://huggingface.co/spybyscript/nemotron-3.5-asr-streaming-0.6b-litert` ·
`https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14{,/raw/main/export-onnx-stateless7-streaming.sh}` ·
`https://huggingface.co/{csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16,spacewave/sherpa-onnx-streaming-zipformer2-id,kangkyu/icefall-asr-ko-streaming-zipformer-72m,k2-fsa/sherpa-onnx-streaming-zipformer-multi-zh-hans-2023-12-12,csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20,csukuangfj2/sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09,TalTechNLP/streaming-zipformer-large.et-en,pkufool/zipformer-medium-streaming,bookbot/sherpa-onnx-zipformer-streaming-robust-es-v0,Banafo/Kroko-ASR,hynt/Zipformer-30M-RNNT-Streaming-6000h,stdo/PengChengStarling,microsoft/VibeVoice-ASR-Streaming-1.5B,moonshine-ai/moonshine-streaming-small-es}` (+ their `/tree/main` and `/raw/main/README.md`) ·
`https://huggingface.co/api/models?{author=nvidia&search=nemotron-3.5-asr,author=moonshine-ai,author=alphacep,filter=automatic-speech-recognition&search=streaming&sort=lastModified}` ·
`https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.7/sherpa-onnx/csrc/{online-recognizer-impl.cc,online-transducer-model.cc,online-transducer-nemo-model.cc,online-recognizer-transducer-nemo-impl.h,online-recognizer-transducer-impl.h,online-stream.cc,text-utils.cc,sherpa-onnx.cc,spoken-language-identification-whisper-impl.h,offline-whisper-model.cc,macros.h}` ·
`https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/CHANGELOG.md` ·
`https://k2-fsa.github.io/sherpa/onnx/{nemo/nemotron-streaming.html,qnn/index.html,spoken-language-identification/pretrained_models.html,pretrained_models/online-transducer/zipformer-transducer-models.html,pretrained_models/online-ctc/{index,t-one-ctc-models}.html,punctuation/pretrained_models.html}` ·
icefall `egs/{librispeech,commonvoice,multi_zh-hans,csj}/ASR/RESULTS.md` and `pruned_transducer_stateless7_streaming/export-onnx.py` ·
`https://support.google.com/googleplay/android-developer/answer/9859372` ·
`https://developer.android.com/guide/playcore/asset-delivery/integrate-java` ·
`https://developers.google.com/ml-kit/genai/speech-recognition/android` + `https://developers.google.com/ml-kit/genai-terms` ·
`https://search.maven.org/solrsearch/select?q=g:ai.moonshine` ·
`https://raw.githubusercontent.com/moonshine-ai/moonshine/main/{README.md,LICENSE,CHANGELOGS.md,docs/**}` ·
`https://api.github.com/repos/{k2-fsa/sherpa-onnx/releases,yangb05/PengChengStarling,voicekit-team/T-one,pengzhendong/streaming-sensevoice,frankyoujian/Edge-Punct-Casing}` ·
AOSP `RecognizerIntent.java` / `SpeechRecognizer.java`.

**Method note, stated so the reader can discount it.** All four research lanes hit `WebSearch` 200/200
before their first query, so discovery ran entirely through primary-source APIs. That is stronger for
provenance and weaker for serendipity — and it is exactly how the German model was missed for a whole
cycle. See risk 10.
