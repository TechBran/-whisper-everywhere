# Rung 1 — `streaming-zipformer-en-2023-06-26` on the PC, over the app's canary audio

Executes §5.4 rung 1 of `docs/superpowers/research/2026-09-09-streaming-local-tier-research.md`
(that doc is **not on `main`** — it lives on the unmerged commit `d8d49c3`; read read-only via
`git show d8d49c3:<path>`, no checkout, no edit to the working tree).

PC only. No adb, no device, no gradle, no repo edit. Every number below was produced on this machine
on 2026-09-10 and the raw logs sit beside this file.

---

## 0. THE ANSWER

| Question | Answer |
|---|---|
| Does the model load and stream on the app's own feed shape (512-sample / 32 ms chunks, endpointing off)? | **Yes**, first try, no tuning |
| Canary — are the digit **words** right? | **Yes, exactly**: `ONE TWO THREE FOUR FIVE`, **WER 0.000** — but only with a tail pad ≥ **0.50 s** (§4) |
| JFK | **WER 0.182** (4 edits / 22 ref words); all four are short function-word substitutions; every content word survives |
| Do partials ever retract under greedy? | **No. Zero retractions in every greedy run.** The partial stream is strictly prefix-growing — an exact fit for the replace-only strip |
| Partial lag (word's last frame → the partial that first contains it) | **p50 0.36 s, p95 0.48 s, max 0.48 s**, bounded by the model's 320 ms chunk |
| RTF, i7-8700K, `provider=cpu` | **0.107 at 1 thread, 0.080 at 2 threads** (jfk). whisper-small int8 on the *same* runtime and CPU: **0.321** — the Zipformer is **4.0×** faster |
| **Verdict against the kill line** ("stop if the strip text reads as *wrong* rather than *unpolished*") | **DOES NOT KILL — PROCEED TO RUNG 2**, carrying two findings: the pad floor is **500 ms, not 450**, and flatline-gated (edited-video) audio is a **real differential weakness** vs whisper (§6) |

Two things this run **cannot** say anything about, and nobody should read into it: the arm64 SME /
KleidiAI defect class (sherpa-onnx #3845 / #3791) is unreachable on x86-64, and there is **no
owner-dictation clip on this PC**, so §5.4 rung 1 item (c) — five minutes of the owner's real speech —
**is not done** (§8).

---

## 1. SETUP — versions, files, hashes

### 1.1 Environment

| Thing | Value |
|---|---|
| Machine CPU | **Intel Core i7-8700K, 6 cores / 12 threads, 3.70 GHz base** (Coffee Lake, AVX2, no AVX-512, no SME — see §8) |
| OS | Windows 11 Pro 10.0.22621 |
| venv | `C:/Users/bastr/.androidbuild/sherpa-venv`, from `C:/Users/bastr/AppData/Local/Programs/Python/Python313/python.exe` |
| Python | **3.13.7** |
| **`sherpa-onnx`** | **1.13.7** (PyPI wheel `sherpa_onnx-1.13.7-cp313-cp313-win_amd64.whl`, plus `sherpa-onnx-core 1.13.7`) |
| `soundfile` | 0.14.0 |
| `numpy` | 2.5.3 |

**On the version ask.** The task preferred `>= 1.13.5` for the ORT 1.27.1 fix; pip resolved to
**1.13.7**, which is the same release line the research doc names as the AAR bump candidate
(`sherpa-onnx-1.13.7.aar`, 2026-09-01). So the Python side is on the *fixed* ORT lineage, not the
shipped AAR's 1.13.4 / ORT 1.27.0. Caveat: the wheel's bundled ORT build number was **not**
independently read out of the binary this pass — the 1.27.1 claim rests on the upstream CHANGELOG
line the doc already cites, not on a local read.

### 1.2 The four files — sizes and sha256 both verified against the doc's §3.7 table

Downloaded from the immutable HF commit
`https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/`
into `C:/Users/bastr/.androidbuild/streaming-models/en-2023-06-26/`. The four raw files, never the
tarball (§3.7's rule).

| File | Bytes | Size | sha256 | Hash |
|---|---|---|---|---|
| `encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 71,083,163 | **OK** | `563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1` | **OK** |
| `decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 1,307,236 | **OK** | `98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02` | **OK** |
| `joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx` | 259,335 | **OK** | `d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297` | **OK** |
| `tokens.txt` | 5,048 | **OK** | `49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb` | **OK** |
| **total** | **72,654,782** | | | |

**Zero mismatches.** All four sizes and all four sha256s are byte-for-byte what §3.7 records,
including the encoder's, which §3.7 flagged as "oid only — the landing pin test hashes it once".
**That hash is now hashed: the LFS oid and the file's sha256 agree.** The landing pin test can use the
table as written.

### 1.3 `tokens.txt` — the doc's claims, checked

| Doc claim | Read |
|---|---|
| all-caps BPE | **Confirmed.** The only pieces containing a lowercase letter are `<blk>`, `<sos/eos>`, `<unk>` |
| no digit pieces; "only `#0 #1` contain a digit" | **Confirmed.** The only digit-bearing entries are `#0` (id 500) and `#1` (id 501) |
| "500 uppercase BPE pieces + `<blk> <sos/eos> <unk>`" | **Minor correction:** the file is **502 lines** — 3 specials (ids 0–2), **497 uppercase pieces** (ids 3–499), and `#0`/`#1` (ids 500–501). Not 500 uppercase pieces |

Consequence, now empirical rather than inferred: **numbers can only ever come out as words**, and the
raw strip text is **ALL CAPS**. Both are visible in every final below. The app must lower-case (or
sentence-case) the partial before it reaches the strip, or the preview shouts.

### 1.4 The recognizer config, as fed

`sherpa_onnx.OnlineRecognizer.from_transducer(...)`, matching the st-map-sherpa config exactly:

```
tokens/encoder/decoder/joiner = the four files above
model_type                    = "zipformer2"
decoding_method               = "greedy_search"    (modified_beam_search also run — §7)
sample_rate = 16000, feature_dim = 80, dither = 0.0
enable_endpoint_detection     = False              (the app's Silero endpointer cuts, not sherpa's)
provider                      = "cpu"
num_threads                   = 1 and 2
```

Recognizer construction cost on this PC: **1.94 s** (1 thread) / **1.69 s** (2 threads).

### 1.5 The feed loop, as the app would drive it

```
for each 512-sample (32 ms) chunk:            # StreamingAudioRecorder.kt reads ByteArray(1024) = 512 PCM16 samples
    stream.accept_waveform(16000, chunk)
    while recognizer.is_ready(stream):        # drain, never a single decode
        recognizer.decode_stream(stream)
    text = recognizer.get_result(stream)      # cumulative text of the open segment
# tail
stream.accept_waveform(16000, zeros(pad_s * 16000))
stream.input_finished()
while recognizer.is_ready(stream): recognizer.decode_stream(stream)
final = recognizer.get_result(stream)
```

### 1.6 The clips

| Name | Path | Format | Duration |
|---|---|---|---|
| `canary_digits` | `app/src/main/assets/canary_digits.wav` | 16 kHz mono PCM16, 81,998 B | **2.560 s** (40,960 frames) |
| `jfk` | `app/src/androidTest/assets/jfk.wav` | 16 kHz mono PCM16, 352,078 B | **11.000 s** (176,000 frames) |
| `jfk_gated` | `scratchpad/review/jfk-gated.wav` | 16 kHz mono PCM16, 352,044 B | 11.000 s — the same audio with **17 digital-zero gates** (§6) |

`app/src/main/cpp/whisper.cpp/samples/jfk.wav` is byte-size-identical to the androidTest copy
(352,078 B, same duration) — one clip, two locations. `scratchpad/review/jfk-48k.wav` is the 48 kHz
source and was not used (the app's path is 16 kHz).

**No dense-speech clip and no owner-dictation clip exists anywhere on this PC** — see §8.

### 1.7 The references and the normalisation

- **Canary reference: `"one two three four five"`** — the WORDS. Source: `GpuCanaryPolicy.kt`'s KDoc,
  which states "The clip contains the WORDS one-through-five (that is the C4 asset contract and it
  does not change)". `EXPECTED_TOKENS` carries numeral aliases (`{"one","1"}` …) only because
  *whisper* renders spoken digits as numerals; the Zipformer cannot (§1.3).
- **JFK reference:** `"And so my fellow Americans, ask not what your country can do for you, ask what
  you can do for your country."` (22 WerMath tokens) — and this run **confirms it against the audio**:
  whisper-small int8 transcribes the clip to exactly that string (§5).
- **Normalisation: `util/WerMath.kt`, ported verbatim** — `tokens()` = lowercase, `U+2019 → '`, split
  on `[^\p{L}\p{Nd}']+`, drop empties; `wer()` = Levenshtein over those tokens / `ref.size`.
  `GpuCanaryPolicy.normalize`/`canaryPasses` ported too (note the difference the port preserves:
  `GpuCanaryPolicy`'s `NON_WORD` **excludes** the apostrophe, `WerMath`'s **includes** it).

---

## 2. PER-CLIP RESULTS — the finals

| Clip | Threads | Tail pad | **Final text** | WER | Edits |
|---|---|---|---|---|---|
| `canary_digits` | 1 & 2 | 0.45 s | `ONE TWO THREE FOUR FI` | **0.200** | 1/5 |
| `canary_digits` | 1 & 2 | **0.80 s** | **`ONE TWO THREE FOUR FIVE`** | **0.000** | 0/5 |
| `jfk` | 1 & 2 | 0.45 s | `AND SAW MY FELLOW AMERICANS ASK NOT WHAT'S YOUR COUNTRY CAN DO FOR YOU AS BUT YOU CAN DO FOR YOUR COUNTRY` | **0.182** | 4/22 |
| `jfk_gated` | 1 & 2 | 0.45 s | `AN THO MY FELLOW AMERICA AS FOR NOT WHAT YOU'RE CUTTER CAN DO FOR YOU ASK WHAT YOU CAN DO FOR YOUR COUNTRY` | **0.318** | 7/22 |

**The finals are bit-identical at 1 and 2 threads on every clip.** `numThreads` moves speed only, not
text — worth knowing, because it means the device can pick threads on an RTF/thermal basis without
re-qualifying accuracy.

**`GpuCanaryPolicy.canaryPasses(final)` = `true`** for both canary runs, including the truncated
0.45 s one (4 of 5 positions matched clears `MIN_MATCHES = 4`). So the app's existing canary rule
already accepts this model's output — but see §4: it accepts a clip that is *visibly* missing a
syllable, which is exactly what that rule's "one dropped digit is ordinary" tolerance is for.

### 2.1 The error kinds, by hand (the doc's §5.4 ask)

**`jfk`, 4 edits — all substitutions of short function words, zero content-word loss:**

| ref | hyp | kind |
|---|---|---|
| `so` | `SAW` | substitution, acoustically adjacent |
| `what` | `WHAT'S` | substitution (spurious possessive) |
| `ask` | `AS` | substitution |
| `what` | `BUT` | substitution |

`FELLOW`, `AMERICANS`, `NOT`, `YOUR`, `COUNTRY`, `CAN DO FOR YOU`, and the whole second clause
`YOU CAN DO FOR YOUR COUNTRY` are **exactly right**. There is **no hallucination, no repetition
runaway, no dropped content word, and no empty output**. The failure mode is "mis-hears unstressed
monosyllables", which is the textbook signature of a small in-domain acoustic model on out-of-domain
audio — not the signature of a broken model.

**`jfk_gated`, 7 edits (6 subs + 1 insertion)** — and here the damage reaches content:
`americans → AMERICA`, `your → YOU'RE`, and **`country → CUTTER`**. That last one is a content word
destroyed, and it sits directly on top of four 64 ms gates at 5.856 / 6.144 / 6.432 / 6.944 s, which
straddle the words `what your country` (token timestamps 5.76–6.60 s). §6.

**Numbers-as-words:** the canary's digits come out as `ONE TWO THREE FOUR FIVE` — **the words are
correct**. This is not a stylistic choice the model could have made differently; §1.3 shows the
vocabulary has no digit piece at all. §5.4's "numbers-as-words" error kind is therefore **structural
and permanent** for this model, and the doc's §3.8 answer (whisper's final normalises) is the only
answer available.

---

## 3. THE PARTIAL TIME-SERIES

Full per-chunk cumulative text (every one of the 877 lines, all four runs) is in
**`rung1-zipformer-raw-partials.log`**. Change-only series, tokens and per-token timestamps are in
**`rung1-zipformer-partials.json`**. Abridged here.

### 3.1 Cadence, first partial, retractions

| Clip | First non-empty partial | Partial **changes** | Inter-change gaps | **Retractions** |
|---|---|---|---|---|
| `canary_digits` | audio **1.44 s** (chunk 45), text `ONE` | 4 | 0.32, 0.64, 0.16 s | **0** |
| `jfk` | audio **1.12 s** (chunk 35), text `AND` | 22 | 0.32 s ×17, 1.28 s ×2, 1.60 s ×1, 0.28 s ×1 | **0** |
| `jfk_gated` | audio **1.12 s** (chunk 35), text `AN` | 21 | 0.32 s ×15, 1.28 s ×2, 1.60 s, 0.64 s, 0.28 s | **0** |

**The cadence is 320 ms, exactly the model's chunk (`chunk-16-left-128`).** Feeding 32 ms chunks does
not buy 32 ms partials — st-map-sherpa §3.3 predicted this and it is confirmed: `is_ready` returns
false for nine of every ten 512-sample feeds. The loop wakes 344 times on jfk and decodes 35 times.
The longer gaps (1.28 / 1.60 s) are the clip's own pauses — real silence produces no new token, so the
text simply does not change.

**Zero retractions, in every greedy run.** Definition used: a partial whose immediate predecessor is
**not a prefix of it** — i.e. a rewrite rather than a growth. There were none: the cumulative text only
ever grew, token by token. This is the single most product-relevant result in the file, because it means
the replace-only strip (`FloatingBubbleService.kt:2961`, `transcriptionDeltaText.text = text`) needs
**no folding, no journal, no diffing, and never visibly un-types a word**. (Beam search breaks this —
§7.)

### 3.2 Abridged series — `jfk`, greedy, 2 threads

Verbatim from `rung1-zipformer-partials.json` (all 22 changes; `…` abbreviates the unchanged prefix
only where marked):

```
 audio_s  cumulative partial
   1.12   AND
   1.44   AND SAW
   1.76   AND SAW MY
   2.08   AND SAW MY FELL
   2.40   AND SAW MY FELLOW A
   2.72   AND SAW MY FELLOW AMERICAN
   3.04   AND SAW MY FELLOW AMERICANS
   4.32   AND SAW MY FELLOW AMERICANS ASK
   4.64   AND SAW MY FELLOW AMERICANS ASK NOT
   6.24   AND SAW MY FELLOW AMERICANS ASK NOT WHAT'S
   6.56   AND SAW MY FELLOW AMERICANS ASK NOT WHAT'S YOUR COUNT
   6.88   AND SAW MY FELLOW AMERICANS ASK NOT WHAT'S YOUR COUNTRY
   7.20   … COUNTRY CAN
   7.52   … CAN DO
   7.84   … DO FOR YOU
   9.12   … FOR YOU AS
   9.44   … YOU AS BUT
   9.76   … AS BUT YOU CAN
  10.08   … YOU CAN DO
  10.40   … CAN DO FOR
  10.72   … DO FOR YOUR COUNT
  11.00   … FOR YOUR COUNTRY          <- last feed chunk
  FINAL   (after 0.45 s zero pad + input_finished) — unchanged
```

Note `FELL → FELLOW A → AMERICAN → AMERICANS` (2.08–3.04 s) and `COUNT → COUNTRY` (6.56 → 6.88 s,
and again at 10.72 → 11.00 s): those are BPE pieces arriving, i.e. **prefix growth**, not retraction —
the strip shows a word completing itself, which reads as typing rather than as correction. It is also
why the tail pad matters (§4): a partial can sit on a half-finished word (`COUNT`, `FI`) until the next
chunk lands, and at end-of-utterance there is no next chunk unless one is padded in.

### 3.3 Partial latency — the doc's §5.4 "partial latency" metric

For each output word, the lag between its **last token's timestamp** (the audio frame the model says
the word ended on) and the **audio time of the first partial that contains it**. Full per-word lags in
`rung1-partial-latency.json`.

| Clip | Words measured | **p50** | **p95** | min | max |
|---|---|---|---|---|---|
| `canary_digits` (pad 0.80) | 5 | 0.360 s | 0.480 s | −0.120 s | 0.480 s |
| `jfk` | 22 | **0.360 s** | **0.480 s** | 0.200 s | 0.480 s |
| `jfk_gated` | 23 | 0.360 s | 0.480 s | 0.200 s | 0.480 s |

Identical at 1 and 2 threads. The distribution is tight and bounded by the 320 ms chunk plus one
32 ms feed quantum; the single negative value is the canary's last word, whose token timestamp falls
inside the tail pad.

**Read this as the structural floor, not the device number.** The PC feeds at max pace, so this lag
contains **no compute queueing** — it is chunk quantisation only. The device number is this plus the
device's own per-chunk decode time. Against §5.4's `p95 > 1.0 s` kill line at 2 threads, the structural
component spends **0.48 s of the 1.00 s budget**, leaving ~0.5 s for device compute. That is the
number rung 3 has to defend.

---

## 4. THE TAIL PAD — the doc's 450 ms is one chunk too short

The task specified a ≥ 45-frame (0.45 s) pad; §5.4's rung-3 table asks for "450 vs 800 ms". Both runs
were done, and then swept, because 0.45 s **lost the canary's last syllable**.

`canary_digits`, greedy, 2 threads, exact match against `ONE TWO THREE FOUR FIVE`:

| Pad | Final | Exact |
|---|---|---|
| 0.00 s | `ONE TWO THREE FOUR` | no — whole word lost |
| 0.10 s | `ONE TWO THREE FOUR` | no |
| 0.20 – **0.45 s** | `ONE TWO THREE FOUR FI` | **no — `VE` missing** |
| **0.50 s** | **`ONE TWO THREE FOUR FIVE`** | **yes** |
| 0.55 – 1.00 s | `ONE TWO THREE FOUR FIVE` | yes |

**The floor is 500 ms, and 450 ms sits one 32 ms chunk below it.** `jfk` needs only 0.30 s (its final
word `COUNTRY` completes; at 0.00 s pad it degrades to `COUNT`, WER 0.227), so the requirement is a
function of the clip's final phoneme, not a constant — and the canary, ending on a fricative-plus-vowel
`five`, is the harder case of the two.

**Carried finding for rung 3 and for §3.3 of the doc:** the pad-length row should read **500 ms vs
800 ms**, not 450 vs 800, and the canary is the right clip to set it on. 800 ms costs 300 ms of extra
finalize latency for no measured accuracy gain over 500 ms.

---

## 5. RTF — and the whisper-small baseline on the same runtime

Wall time covers the whole feed-and-drain loop (accept + decode + get_result + tail pad), max pace,
warmed. RTF = wall / clip duration. **`provider = "cpu"`, i7-8700K.**

| Clip | Decoding | Pad | **RTF @ 1 thread** | **RTF @ 2 threads** | Wall @ 2 th |
|---|---|---|---|---|---|
| `jfk` (11.0 s) | greedy | 0.45 s | **0.1073** | **0.0798** | 0.877 s |
| `jfk_gated` | greedy | 0.45 s | 0.1151 | 0.0805 | 0.886 s |
| `canary_digits` (2.56 s) | greedy | 0.45 s | 0.1092 | 0.0780 | 0.200 s |
| `canary_digits` | greedy | 0.80 s | 0.1392 | 0.1014 | 0.260 s |
| `jfk` | beam (4 paths) | 0.45 s | — | 0.1115 | 1.226 s |
| `canary_digits` | beam (4 paths) | 0.80 s | — | 0.0941 | — |

The canary's pad-0.80 RTF (0.1014) is an artefact of dividing by the 2.56 s clip while decoding
3.36 s of audio; **jfk is the honest RTF read: 0.107 at 1 thread, 0.080 at 2.** Both are in the
neighbourhood of the sherpa docs' published 0.062 int8 on their own unnamed CPU — same order,
slightly slower machine.

### 5.1 whisper-small int8, offline, same wheel / same CPU / same threads

Run through `sherpa_onnx.OfflineRecognizer.from_whisper` on
`csukuangfj/sherpa-onnx-whisper-small` @ `8f3c18b358db4d1f2fc1eae49d75cd20989e4309`
(`small-encoder.int8.onnx` 112,442,483 B + `small-decoder.int8.onnx` 262,226,114 B +
`small-tokens.txt` 816,730 B = 375,485,327 B), `language="en"`, greedy, 2 threads, load 2.10 s.
**This is not the app's `multi`** — `multi` is ggml-small **q5_1** through whisper.cpp; this is the
same weights at ONNX int8 through the same ORT the streaming model uses. Text should be
near-identical; the *speed* comparison is apples-to-apples because both run on one runtime, one CPU,
two threads.

| Clip | whisper-small final | WER | RTF @ 2 th |
|---|---|---|---|
| `canary_digits` | `One, two, three, four, five.` | **0.000** | 0.567 |
| `jfk` | `And so my fellow Americans, ask not what your country can do for you, ask what you can do for your country.` | **0.000** | 0.321 |
| `jfk_gated` | `And so my fellow Americans, ask not what your country can do for you. Ask what you can do for your country.` | **0.000** | 0.330 |

### 5.2 The side-by-side the doc's decision rule actually asks for

WER of the Zipformer final against **whisper's own output as the reference** (the "diff against
`multi`" of §5.4):

| Clip | Zipformer WER vs whisper | Zipformer WER vs the hand reference |
|---|---|---|
| `canary_digits` (pad 0.80) | **0.000** | 0.000 |
| `jfk` | **0.182** | 0.182 |
| `jfk_gated` | **0.318** | 0.318 |

Identical, because whisper scored 0.000 on all three — so on these clips "vs whisper" and "vs truth"
are the same measurement.

**Two numbers to carry forward.**
1. **Speed: the Zipformer is 4.0× faster than whisper-small** on the same runtime, CPU and thread
   count (0.0798 vs 0.321 on jfk), at **19 %** of the bytes (72.7 MB vs 375.5 MB int8, or 38 % of the
   app's 190.1 MB q5_1). That is the first apples-to-apples speed ratio between these two models
   anywhere in this project's docs — §6 of the research doc had to infer it from two different
   machines' published RTFs.
2. **Accuracy: whisper-small is perfect on all three clips; the Zipformer is perfect on one.** On
   `jfk` the gap is 4 words in 22 — and `jfk` is a 1961 narrow-band broadcast recording, i.e. squarely
   **out-of-domain** for a LibriSpeech model. §2.2 of the research doc states plainly that "**no
   out-of-domain WER exists for it anywhere read**". **These are the first two out-of-domain data
   points that exist: 0.182 on narrow-band broadcast speech, 0.318 on the same audio after
   flatline gating.** Two clips is not a WER; it is a direction, and the direction matches the doc's
   INFERRED expectation of "possibly 1.5–2×" degradation.

---

## 6. THE GATED-AUDIO FINDING — the one place this run says "be careful"

`jfk-gated.wav` is `jfk.wav` with digital-zero gates written over it — the edited-video signature the
VAD work was retuned against. Measured this pass: **17 zero-runs ≥ 50 ms** (77,841 zero samples vs
925 in the clean clip):

```
0.000s  320 ms   (leading)      5.856s   64 ms
0.576s   96 ms                  6.144s   64 ms
1.280s   64 ms                  6.432s   64 ms
2.016s   64 ms                  6.944s   64 ms
2.112s 1152 ms                  7.552s  640 ms
3.712s  288 ms                  8.512s  128 ms
4.288s 1120 ms                  8.768s  128 ms
                                9.664s   64 ms
                               10.240s   96 ms
                               10.368s  352 ms
```

| | clean `jfk` | `jfk_gated` | delta |
|---|---|---|---|
| Zipformer WER | 0.182 (4 edits) | **0.318 (7 edits)** | **+3 edits over 16 in-speech cuts** |
| whisper-small WER | 0.000 | **0.000** | **0** |

**whisper-small is completely unmoved by the gating; the Zipformer loses a content word to it.**
`country → CUTTER` sits on the 64 ms gate cluster at 5.856–6.944 s that straddles `what your country`.
Against §5.4's rung-3 line "edited video: > 1 boundary-word error per 10 cuts" the ratio here is
**3 / 16 = 0.19 per cut — roughly double that line.** (That line is written for the device rung, and
the metric there is boundary-word errors specifically rather than whole-clip WER delta, so this is a
flag rather than a formal fail. It is also *one* clip.)

Why this matters more than the raw number: a streaming transducer carries left context through the
gate, whereas whisper re-attends a 30 s window and can reconstruct across a hole. The app's own
history says this audio shape is not hypothetical — the endpointer was retuned 500 → 350 ms on
exactly this class of clip. **Rung 3 should treat gated audio as a first-class case, not a footnote,
and the tee design (streaming strip + whisper final) is precisely the mitigation: whisper's final is
untouched by the gates.**

---

## 7. `modified_beam_search` — the errors are acoustic, not decoding-limited (and beam breaks the strip)

Same model, `decoding_method = "modified_beam_search"`, `max_active_paths = 4`, 2 threads:

| Clip | greedy | beam | Beam's verdict |
|---|---|---|---|
| `canary_digits` (pad 0.80) | 0.000 — `ONE TWO THREE FOUR FIVE` | 0.000 — identical | no change |
| `jfk` | 0.182 — 4 edits | **0.182 — byte-identical output** | **no change** |
| `jfk_gated` | 0.318 — 7 edits | 0.273 — 6 edits (`COUNTRY` recovered) | small gain |
| RTF (jfk) | 0.0798 | 0.1115 (**+40 %**) | costs 40 % |
| **Retractions (jfk_gated)** | **0** | **4** | **breaks the prefix-growth property** |

Two conclusions:

1. **The `jfk` errors are acoustic, not a decoding artefact.** Beam search with 4 paths produced the
   *same four substitutions*, byte for byte. `SAW` for `so` and `AS BUT` for `ask what` are what this
   66 M model hears in that audio; no cheap decoding change fixes them. (Hotwords and an external LM
   remain untested levers — both cost bytes and latency.)
2. **Greedy is the right default for a previewer, and it is not a close call.** Beam costs 40 % more
   compute, buys nothing on clean audio, and **introduces 4 retractions** — visibly rewriting words
   already shown on the strip. Greedy's zero-retraction property (§3.1) is worth more to the strip
   than one recovered word on a gated clip. This corroborates the AAR's own `"greedy_search"` default
   and §5.5's instinct to keep the first cut simple.

---

## 8. WHAT DID NOT WORK, AND WHAT THIS RUN CANNOT TELL YOU

1. **The research-doc path in the task does not exist on `main`.** It is on the unmerged commit
   `d8d49c3` ("docs(research): the on-device word-for-word tier…"). Read via
   `git show d8d49c3:docs/superpowers/research/2026-09-09-streaming-local-tier-research.md` into the
   scratchpad — **no checkout, no branch change, no working-tree edit** in the shared checkout, as
   instructed. Section numbering in the task (§2, §3.7, §5.4) maps to that doc, not to
   `st-map-models.md`, whose §2/§3 are different content.
2. **The arm64 defect class is unreachable here.** sherpa-onnx #3845 (SM8850, empty text, encoder sum
   −14.069) and #3791 (Apple M4, `ConvolveSme` wrong for `pads=[0,1,0,1]`, `"MY WOMAN"`) are both
   **FEAT_SME / KleidiAI** bugs on arm64. This PC is an x86-64 Coffee Lake part — no SME, no KleidiAI
   path, and a different ORT build entirely. **This run is evidence about the model, not about the
   AAR.** It neither reproduces nor clears #3845, and the load-time canary the doc requires (§3.9)
   remains mandatory.
3. **§5.4 rung 1 item (c) is not done: there is no owner-dictation clip on this PC**, and no
   dense-speech clip either. Searched the repo and the scratchpad for `*.wav/*.mp3/*.m4a/*.ogg/
   *.flac/*.opus`: the only speech audio that exists is `canary_digits.wav`, the two identical copies
   of `jfk.wav`, `jfk.mp3` (the same clip), `jfk-48k.wav` and `jfk-gated.wav`. The "five minutes of
   the owner's dictation" leg — the leg that actually settles out-of-domain quality on the app's real
   traffic, and the doc's own #1 unknown — **needs the owner to supply audio.** Two clips of
   broadcast English are a direction, not a verdict.
4. **The wheel's ORT build number was not read out of the binary.** 1.13.7 ⇒ ORT 1.27.1 rests on the
   upstream CHANGELOG line the doc cites, not on a local check.
5. **The whisper baseline is not the app's `multi`.** It is whisper-small at ONNX int8 through sherpa,
   not ggml-small q5_1 through whisper.cpp. Same weights, different quantisation and runtime. Fine for
   the text comparison; the 4.0× speed ratio is only valid as "same runtime, same CPU, same threads".
6. **PC RTF is not device RTF**, as §5.4 says outright ("PC RTF is irrelevant"). It is reported because
   it establishes the whisper-relative ratio, which *does* travel better than an absolute number.
7. Nothing was installed into `tools/vadsim/.venv` and nothing in the repo was written or staged. The
   vadsim venv has `torch 2.14` + `silero-vad` but **no whisper and no transformers**, so the whisper
   comparison was done via the already-installed sherpa wheel instead — zero new dependencies, and no
   touch to the other agent's checkout.

---

## 9. VERDICT AGAINST THE KILL LINE

> §5.4 rung 1: *"if the side-by-side reads as **wrong** (not 'less polished' — that is expected),
> stop: the tier is Shape-C-only on a different model, or nothing."*

### **DOES NOT KILL. PROCEED TO RUNG 2.**

The case, plainly:

- **The canary is exact.** `ONE TWO THREE FOUR FIVE`, WER 0.000, and the digit **words are correct** —
  which was the specific thing asked. `GpuCanaryPolicy.canaryPasses` returns `true`.
- **The `jfk` output reads as unpolished, not wrong.** Read it as a strip: *"AND SAW MY FELLOW
  AMERICANS ASK NOT WHAT'S YOUR COUNTRY CAN DO FOR YOU AS BUT YOU CAN DO FOR YOUR COUNTRY."* Every
  content word is right; the sentence's meaning is fully intact; the four errors are unstressed
  monosyllables on a 1961 narrow-band broadcast. There is no empty output, no garbage, no repetition
  runaway, no hallucinated content — none of the corruption shapes `GpuCanaryPolicy`'s docblock was
  written to catch. This is a legible preview with visible slack, which is exactly what the doc said
  to expect and explicitly told us not to kill on.
- **The strip contract is a clean fit, measured rather than assumed.** Zero retractions under greedy;
  cumulative text only grows; 320 ms cadence; p95 word lag 0.48 s.
- **The economics are real.** 4.0× whisper-small's speed at 19 % of its int8 bytes, and 0.080 RTF at
  2 threads on a 2017 desktop part.

Three findings that must travel with the go:

1. **The tail pad floor is 500 ms, not 450** (§4). Fix the doc's rung-3 row before it is measured.
2. **Flatline-gated audio is a real differential weakness** — 0.318 vs whisper's 0.000, a content word
   destroyed, ~0.19 boundary errors per cut (§6). Promote it in rung 3; it is also the strongest
   single argument for the previewer-plus-whisper-final tee rather than a committer.
3. **Greedy, not beam** (§7). Beam costs 40 % RTF, fixes nothing on clean audio, and introduces
   retractions that the replace-only strip would show as un-typing.

And the one thing this rung did **not** close, which no amount of PC work can: **out-of-domain
quality on the owner's own voice and the app's real traffic.** Two broadcast clips point the right
way; the doc's #1 unknown stays open until the owner supplies five minutes of dictation.

---

## FILES

All in `scratchpad/review/`:

| File | What |
|---|---|
| **`rung1-zipformer-pc.md`** | this report |
| **`rung1-zipformer-raw-partials.log`** | 63,856 B / 877 lines — the **raw per-chunk log**: every 32 ms chunk's cumulative partial with audio time, cumulative decode wall-ms and decode count, for all four runs, plus tokens and per-token timestamps |
| `rung1-zipformer-partials.json` | machine-readable: all 8 greedy runs (2 thread settings × 4 clip/pad combos) — finals, change-only partial series, tokens, timestamps, decode counts, wall times, RTF, WER, retractions, canary verdict |
| `rung1-partial-latency.json` | per-word partial lag, p50/p95/min/max, per run |
| `rung1-whisper-small-baseline.json` | whisper-small int8 finals, WER, RTF |
| `rung1-addons.json` | the 13-point canary pad sweep and the `modified_beam_search` comparison |

Scripts (in the scratchpad root, one level up): `rung1_stream.py` (the harness, with `WerMath` and
`GpuCanaryPolicy` ported), `dl.py` (the four-file download + verify), `dl_whisper.py`.

Reproduce:

```sh
V=C:/Users/bastr/.androidbuild/sherpa-venv/Scripts/python.exe
$V .../scratchpad/dl.py            # four files, size + sha256 verify
$V .../scratchpad/rung1_stream.py  # the streaming runs
```
