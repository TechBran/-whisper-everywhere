# Should the CPU finalizer tier move up? — synthesis, 2026-09-13

**Inputs:** four lane reports (`cputier-throughput`, `cputier-accuracy`, `cputier-memory`,
`cputier-appceilings`) and two refutations (`cputier-refute-throughput` → REFUTED,
`cputier-refute-worth` → WEAKENED), all read in full. Repo read at local `main` `70adb96`,
`app/build.gradle.kts:53-54` = **versionCode 94 / 4.5.2**, in production.

Every claim is tagged **READ** (I opened the file and line, or the URL with the date), **MEASURED**
(an on-device number from this repo, with its duty and device stated), or **DERIVED** (arithmetic on
those, reasoning shown so it can be attacked). Where I re-checked a lane's claim myself and it held,
I say so; where it did not, §0.4 lists the four corrections.

---

## 1. THE ANSWER IN ONE SCREEN

**The refuters landed. NO — do not move the CPU tier up, and do not steer, default, or recommend
anyone to a bigger CPU model.** `multi` (small-q5_1, 190 MB) stays the multilingual pick.

But they landed on the question as posed, and **the question as posed is the wrong one.** The three
findings below are mine, from this repo, and no lane had them. They do not overturn the verdict —
they relocate the cause:

> **The app's finalizer is running at 7-11% of the arithmetic throughput its own hardware already
> delivers to a properly-GEMM'd path — measured on the target device, on this very model family, in
> this repo.** The binding constraint is not model size. Getting whisper's matmuls onto a GEMM path
> is worth several times more than any rung on the ladder, and it is the same conclusion reached
> independently from source reading (§2.5) and from an on-device measurement of a different runtime
> on the same chip (§0.2).

**Per candidate, with the reason each dies:**

| candidate | verdict | why, in one line |
|---|---|---|
| `multi` small-q5_1 190 MB | **KEEP — the answer** | F = 2.3 s MEASURED, duty 0.42 on the Fold6 / 0.48-0.54 DERIVED on the Tab. The only rung that clears the app's own rule. |
| **medium multilingual q5_0 539 MB** | **DO NOT SHIP. Offer only as an instrument.** | F = 6.0-9.2 s (Fold6, three independent derivations) → 6.9-12.0 s on the Tab. The app's own rule demands **F ≤ 5.3 s**. Zero of three derivations reach it, on the best device, cold, previewer off. |
| `extreme` medium.en 539 MB | **NEVER — wrong artefact** | Costs the entire bill to serve the one language with the smallest prize (**−0.8 WER** on dictation-like English; TED-LIUM3 a dead heat at 4.3). |
| `ultra` large-v3-turbo q5_0 574 MB | **REFUTED BY ARITHMETIC — zero device minutes** | Turbo's whole saving is 28 removed *decoder* layers in a workload that is 83-88% *encoder*. Its encoder **is** large-v3's, 2.08× medium's. Crossover needs ~750 decoded tokens in one encode against a 448-token context: physically unreachable. And now MEASURED on the Tab: **7.2 s cold / 8.3-9.4 s sustained** on a runtime 7-9× faster than the app's (§0.2). |
| large-v3 q5_0 1.08 GB | **REFUTED — strictly worse** | The same 32×1280 encoder plus 8× turbo's decoder, at 1,081,140,203 B. |
| distil-* | **REFUTED four ways** | English-only; *less* accurate than large-v3 (9.7 vs 8.4); whisper.cpp lacks the chunking its numbers assume; no ggml at the pinned commit. And it optimises the decoder — the half this app does not spend on. |

**For whom, if a rung is offered at all:** multilingual medium q5_0, **un-steered, un-recommended,
un-defaulted, not a migration target**, on 12 GB-class devices, as the *instrument* for the owner's
six-device session — with `minRamBytes` set above any shipping phone so **no device is ever told it
is recommended** (§6.3). Offering is not steering, and every objection in this document except one
is an objection to steering. The single objection to *offering* is §2.6: nobody has ever validated
the `audio_ctx 512` floor on an encoder deeper than 12 layers, and the existing harness **cannot**
detect that failure as written (§0.3).

**The one product action that survives independent of the whole tier question** is the reversal the
worth-refuter found: for **zh** (and partly ko), the ~50-73 MB streaming previewer is already
*measurably more accurate* than the 190 MB finalizer that overwrites it. The app paints correct
words and types worse ones over them, in production, today. That is an open, named, unowned defect
(`O8`, `2026-09-11-language-qualification-table.md:382`) and it is a better reason to want a bigger
finalizer than the WER table is. It is also **not yet established in magnitude** — see §3.4 for the
haircut I had to apply to both of its numbers, and why `E7` is the gate.

**The 30-second padding question, answered in one line and unpacked in §2.1:** yes, whisper.cpp pads
unconditionally to 30 s; **no**, this app does not pay for it (`audio_ctx = clamp(samples/320+64,
512, 1500)`); and the *residual* is decisive in a way that is worse than the naive version — not
because the arithmetic is larger, but because **it makes the arithmetic rigid.** Every lever that
would normally buy a slow model room is already at its stop or blocked by an accuracy cliff.

**What I would actually spend the session on**, in order: (0) the accuracy-cliff check on medium at
ctx 512 — if it fails, stop, the prize is negative; (1) **small-q8_0 vs small-q5_1 on one device** —
the cheapest experiment in the research and the only one that could reopen the tier question; (2) a
single 15-minute sustained medium run with the previewer armed. Turbo and large-v3 get zero minutes.

---

## 0. Provenance — the three repo measurements no lane found, and four corrections

Each lane wrote "no whisper measurement exists for any Dimensity", "no thermal curve exists for any
CPU tier", and "the repack gain is folklore with no whisper measurement anywhere". **All three are
false, and the evidence is in `docs/`.** This matters because it converts the two weakest
load-bearing numbers in the whole research into measurements.

### 0.1 There IS a measured Dimensity ggml whisper number — and the Tab is SLOWER than the Fold6

**MEASURED**, `docs/superpowers/research/2026-09-09-tab-turbo-gemma-scribe-gemini-live-research.md:61`,
Galaxy Tab S10+ (MT6989), **Play build 86 / 4.3.2** (so **previewer-OFF** — the previewer landed in
4.4.0), CPU `small-q5_1`, production seam, verbatim:

> *"a 17.6 s chunk transcribed in ≈ 6.05 s service time — the capture shows
> `VAD: 281088 -> 281088 samples (1 segments)` at 21:30:00.029 (zero VAD trim), the recorder stop at
> 21:30:00.916, and the next chunk's `VAD:` line at 21:30:06.081, queued behind the 17.6 s
> transcribe on the single executor"*

DERIVED from it: 281,088 samples = **17.568 s**; `audio_ctx = 281088/320 + 64 = 942` frames, i.e.
**above the 512 floor — this is the asymptotic regime measured directly**; and

> **Tab S10+, CPU, 4 threads, small-q5_1, previewer off, asymptotic: RTF = 6.05 / 17.568 = 0.344.**

Against the Fold6 anchor (4.25 ms per audio_ctx frame + 47.5-73.2 ms marginal decode per second of
speech, both DERIVED from the 2026-08-20 floor bench): predicted Fold6 wall at ctx 942 = **4.84-5.29
s**. Measured Tab = 6.05 s.

> **Tab ≈ Fold6 × 1.14-1.25 on wall time, × 1.19-1.30 per encoder frame (5.06-5.54 ms/frame vs
> 4.25).** The target class is **SLOWER** than the upper-bound device, not faster.

This **inverts** the throughput lane's "Tab wall ≈ Fold6 × 0.8-1.0" (its §4c), which was INFERRED
from a core-layout argument (4× X4, no little cores) and labelled low-confidence. The core-layout
argument is sound about cores and wrong about outcome — most likely memory-bandwidth bound, which is
exactly what an un-GEMM'd per-row `vec_dot` path over q5 blocks is (§2.5).

Caveats, stated: one chunk, one session; "service time" is VAD-line-to-VAD-line and so includes
executor handoff, making it an **upper bound** on the whisper call; and it is a single datapoint.
But it is the only ggml whisper measurement on the target class that exists, and the alternative was
a reasoned transfer in the opposite direction.

**Cross-check that raises my confidence in the whole method:** the asymptotic model built from the
Fold6 anchor, scaled by ×1.19-1.30, predicts Tab small at RTF **0.30-0.34**. The measurement is
**0.344**. The method reproduces the one Tab number it did not use to calibrate itself.

### 0.2 There IS a measured big-model CPU number on the Tab — and it prices the GEMM lever

**MEASURED**, `docs/measurements/2026-09-10-tab-turbo-e2e-gpu.md:203-215` (E6, 2026-09-10), Tab
S10+, `whisper_large_v3_turbo_30s_i8.tflite` (1,088,340,944 B, int8 weights / fp32 activations) via
LiteRT/XNNPACK, greedy loop, **full 1500-frame window — the graph has no `audio_ctx` floor**:

| arm | encode | per token | 20-token commit | RSS |
|---|---|---|---|---|
| **all-CPU 4 threads, cold process** | **4,777 ms** | 121.8 ms | **7,213 ms** | 2.1 GB |
| **all-CPU 4 threads, sustained (thermalStatus 2)** | **5,500-6,365 ms** | 140-153 ms | **8.3-9.4 s** | 2.1 GB |
| GPU fp32 encoder + CPU decoder (the only correct GPU config) | 3,507 ms | 119.0 ms | 5,887 ms | 5.8-6.0 GB |

Two things fall out, and they point in opposite directions.

**(a) It kills turbo-on-CPU independently, on the target device.** 7.2 s cold / 8.3-9.4 s sustained
per commit, against the 8,000 ms row. The doc's own verdict (`:258`): *"The Tab's honest turbo today
is the CPU at 7-9 s, which the owner already ruled out."* No arithmetic transfer needed.

**(b) It prices the GEMM lever — the thing the refuter correctly called the weakest number in the
research.** Both documents count GFLOP in the same convention (the E6 doc cites the 2026-09-04
research doc's figures: turbo's encoder is **706.6 GFLOP at 512 frames, 2,313 GFLOP at 1500**,
`2026-09-04-turbo-cpu-gpu-research.md:79-84` READ):

| path | device | ms per GFLOP | effective GFLOP/s |
|---|---|---|---|
| **ggml q5, per-row `vec_dot`, no GEMM** | Fold6 | **19.22** (READ, `:93-94`, fit to four floors within 3.5%) | 52 |
| **tflite int8 via XNNPACK, GEMM'd, cold** | Tab | **2.07** (4,777 / 2,313) | 484 |
| **tflite int8 via XNNPACK, GEMM'd, sustained** | Tab | **2.38-2.75** | 364-420 |

> **DERIVED: a 7.0-9.3× effective-throughput gap between a GEMM'd path and ggml's un-GEMM'd q5
> path.** And the device difference points the *wrong* way for ggml — the Tab is 1.19-1.30× slower
> on ggml (§0.1), so **like-for-like on one device the gap is ~9-11×.**

Third independent corroboration that this magnitude is not absurd: the app *already* runs a
different ASR encoder through a GEMM'd runtime on this same chip — the streaming previewer, at
**RTF 0.054 at max pace on 2 threads** (MEASURED, `2026-09-10-tab-sherpa-rung3.md`). Three
different instruments, same direction.

**What this does and does not license.** The refuter's objection stands in its narrow form: this is
a *different runtime* (XNNPACK's blocked microkernels are more mature than ggml's `repack.cpp`) and
a *different quantisation* (int8 weights + fp32 activations, not q8_0 blocks), measured in the
**1500-column** regime where a GEMM path wins biggest. So **7-9× is an upper bound on what ggml's
repack path would deliver, not a prediction.** What it destroys is the claim that the lever's size is
unknowable folklore. It is bounded from below at far more than 2.5×, on the target device, for this
model family — and §5.2 makes measuring the real figure a 264 MB download and one bench run.

DERIVED, to show what is at stake. Medium's encoder at ctx 512 in the same convention = **349-384
GFLOP** (arithmetic ratio 3.56× small, corrected by the repo's own count factor 6.24/6.87; the
refuter's independent count brackets it):

| medium @ ctx 512 | ms/GFLOP | encoder wall | verdict vs the 5.3 s rule |
|---|---|---|---|
| ggml q5_0, as shipped | 19.22 | **6.7 - 7.4 s** | **fails** |
| ggml + repack, if it buys 2× | 9.6 | 3.4 - 3.7 s | passes with thin margin |
| ggml + repack, if it buys 4× | 4.8 | **1.7 - 1.8 s** | passes with margin |
| the GEMM throughput the chip demonstrably delivers | 2.07-2.75 | **0.7 - 1.1 s** | **faster than `small` runs today** |

> **If whisper's matmuls ran at the throughput this hardware already gives a GEMM'd path, medium
> would finalize faster than `small` does now.** That is the finding. It is also why the answer to
> "should the tier move up?" is "not on this inference path" rather than "not ever".

### 0.3 The thermal curve exists, and the floor-A-B harness cannot answer the question asked of it

**MEASURED**, same E6 run: the all-CPU turbo arm drifted **4,777 → 5,500-6,365 ms (×1.15-1.33)** and
reached **`thermalStatus` 2 (MODERATE) within six utterances**, per-token 121.8 → 140-153 ms
(×1.15-1.26). Four threads, ~100% duty, on the target chip. The memory lane's §3 correctly rejected
the previewer's flat ten-minute run as evidence (its own author's caveat: *"~12% of one core… which
is why nothing moved"*) and concluded no curve existed. One does. It corroborates `multi`'s
in-session ×1.09-1.57 and npu-turbo's +13%, and it **already fails** the memory lane's own proposed
gate of `thermalStatus ≤ LIGHT`.

**And a correction that changes the protocol.** The refuter's highest-priority experiment — does
medium hold accuracy at ctx 512? — cannot be run on the existing harness as written. READ,
`WhisperBenchTest.kt:63-66, 186-195`:

```
const val PRODUCTION_FLOOR = 512
val FLOOR_CANDIDATES = listOf(384, 256)     // strictly BELOW production
// "Reference arm FIRST, at the production floor: its text is the 'A' of every A-B."
val wer = WerMath.wer(refTexts.getValue(seconds), text)
```

> **The reference arm IS floor 512, and every candidate is scored against 512's own output.** So if
> medium at 512 garbles, the harness scores the candidates against the garbled reference, reports
> `wer≈0.000`, and prints **PASS**. The harness is structurally incapable of detecting "512 itself
> is wrong" — it can only ever detect "a candidate is worse than 512."

The fix is two literals — reference → 1500, candidates → `listOf(768, 512)` — and `floorBinds`
already handles upward candidates correctly (`floor > neededFrames`: 1500 > 114 at the 1 s slice).
But someone must make the change. "One run, no code change" is wrong, and this is the run that gates
every other one.

### 0.4 The four corrections to the lanes

| # | lane claim | correction |
|---|---|---|
| 1 | "No whisper.cpp measurement exists for ANY Dimensity" (throughput §9.1, appceilings §9.1, memory §3.3) | One does, on the production build, in the asymptotic regime (§0.1) — and it says the Tab is **1.14-1.30× slower** than the Fold6, inverting the throughput lane's ×0.8-1.0 transfer. |
| 2 | "The 1.5-2.5× repack figure is the weakest load-bearing number… a general ggml-on-ARM expectation, not a whisper-specific measurement" (throughput §9.3; refuter §4.1 built his strongest counter on this) | A whisper-class encoder on a GEMM'd path is MEASURED on the target device at **2.07-2.75 ms/GFLOP against ggml's 19.22** (§0.2). The lever is bounded from below at ~7×, not unbounded folklore. |
| 3 | "No thermal curve exists for any whisper tier at any duty on any device" (memory §3.3, its stated biggest gap) | One does: **×1.15-1.33 drift, thermalStatus 2 within six utterances**, 4 threads, ~100% duty, Tab (§0.3). |
| 4 | "`inFlightStripLabel`'s first arm is the declared flip point" — a one-line change (appceilings §8) | It is **two** edits. `FloatingBubbleService.kt:5357` — `if (deltaOwnsPreviewStrip(...)) return` — returns **before** line 5358 ever calls `inFlightStripLabel`. The label's own comment says so (`:870`): *"this row never fires in production and is the flip's declaration rather than the whole of it."* Flipping only the label changes nothing on screen. |

---

## 2. THE THROUGHPUT TABLE

### 2.1 The 30-second padding question, answered explicitly — it may change everything, and it does

The brief flagged this as potentially decisive and unexamined. It is decisive. It has three layers
and only the third one matters.

**Layer 1 — the mechanic is real. READ.** `src/whisper.cpp` `log_mel_spectrogram`: `int64_t
stage_1_pad = WHISPER_SAMPLE_RATE * 30;` — an unconditional 480,000 zero samples appended to *any*
input, with no short-circuit for short audio. And the encoder's input tensor is sized by context,
not by audio: `whisper_build_graph_conv` allocates `ggml_new_tensor_2d(ctx0, GGML_TYPE_F32, 2*n_ctx,
n_mels)` and `whisper_get_mel_segment_with_state` `memset`s every column with no source frame to
zero. **Encoder FLOPs are a function of `audio_ctx` alone and are completely independent of how much
real audio the chunk contains.**

**Layer 2 — the naive conclusion is FALSE of this app, and arguing it would be correctly rebutted.**
READ, `whisper_jni.cpp:897-912`: `neededFrames = pcm.size()/320 + 64`, raised to
`g_audio_ctx_floor` (512) and capped at 1500. A 2 s utterance is billed **512** frames, not 1500.
The project pulled this lever at 3.6.0 and recorded it as a latency win.

**Layer 3 — the residual, which is where the decision lives.** `neededFrames = 50·T + 64`, so the
floor binds for all **T < 8.96 s**: every normal dictation commit. The app's own comment states the
consequence (`whisper_jni.cpp:911-913` READ): *"the floor is the entire reason this counter exists
(a 2.4 s utterance needs ~184 frames and still pays 512)."* Four consequences, each DERIVED from
that READ arithmetic:

1. **RTF-against-audio is the wrong metric, and using it is how a bad tier ships.** Below ~9 s the
   cost per commit is *constant*. The real ceiling is **commits per second**, and the queue grows
   iff **F > the commit floor** — a wall-clock comparison in which the audio duration never appears.
   Every "RTF 1.13" in this research is really "four big cores pinned at 113% duty for the whole
   dictation."
2. **Sparse speech buys no proportional relief.** A user who pauses produces commits with *less*
   speech at the *same* 512-frame cost. The bill is per commit, not per word. "Real dictation is
   easier than a benchmark" is false here.
3. **At T ≥ 8.96 s, `audio_ctx` grows with T, so RTF goes FLAT — there is no amortisation left to
   buy by pacing**, and the app already paces the big tiers at 8,000 ms
   (`MIN_COMMIT_INTERVAL_LARGE_MS`, READ).
4. **Every escape route is already at its stop.** `audio_ctx` cannot go lower — 512 is the *measured
   accuracy* floor (384/256 garble at `wer 0.500`, "the cliff sits between 512 and 384", READ). The
   floor cannot go higher — satisfying the 0.70 rule at medium's central F ≈ 8 s needs
   `F/0.70 ≈ 11.4 s`, which collides with `SegmentCapPolicy.MAX_SEGMENT_WALL_MS = 15_000` and makes
   every commit a wall-cap cut delivering 30-40 words in a block. Threads are capped at 4 with no
   runtime override.

> **So the padding mechanic does not make the arithmetic worse than naive RTF suggests — it makes it
> RIGID.** That is why this is a structural refutation and not a tuning question. And it is why the
> *only* lever with room left in it is the one that changes ms-per-FLOP rather than FLOPs (§0.2).

**The sting in the tail, and the reason §2.6 is the gate ahead of every other gate:** the 512 floor
is the single reason medium is even arguable, and its accuracy validation covers **"both 190 MB
tiers"** only (READ, `whisper_jni.cpp:898-906`) — 12-layer/768-wide encoders. Nobody has swept it on
24 or 32 layers. The mechanism named in that comment is *positional-embedding mismatch*, and the
observed failure is a **cliff** (`wer 0.500`, garbled phrases), not a slope. **The accuracy floor and
the throughput ceiling are the same parameter.**

### 2.2 The gate is 0.70 duty, not RTF 1.0

READ verbatim, `CommitCadencePolicy.kt:29-31`:

> *"THE ELIGIBILITY RULE FOR THIS TABLE… a tier keeps a floor only while its full-segment F is
> **MEASURED** and `F/floor + m <= 0.70` at saturation."*

At the 8,000 ms floor with m ≈ 0.04 (READ, the repo's marginal figure while the floor binds), that
demands **F ≤ 5.3 s** — a bar 43% stricter than RTF < 1.0. How seriously this project treats it,
all READ: overridden **exactly once** (npu-turbo, with arithmetic and a named owner ruling in the
constant's KDoc); `eco`/`base` described as *"EXEMPTED, not cleared… THE CHECK FAILS EVEN SO"*; and
`extreme`/`ultra` labelled *"UNMEASURED. 8 s is the conservative placeholder."* The rule requires F
**MEASURED**, and `grep tier=extreme|tier=ultra` over `docs/` returns **nothing, on any device,
ever**. The rule has no provisional branch for tiers someone hopes are fine.

### 2.3 The table — sustained RTF and duty, per candidate per device, threads named

**All rows: `n_threads = 4`** (READ, `whisper_jni.cpp:857-863`, `min(cores,4)`, one site, no per-tier
branch, no knob). **`audio_ctx` 512** for any chunk under 8.96 s. **Previewer = 2 threads + 169 MB
concurrent** where stated (READ: `StreamingPreviewTuning.NUM_THREADS = 2`;
`localPreviewArms` has six terms and **none is the tier**; `NativeComputeGate` KDoc — sherpa runs
outside the whisper lock, *"Do not 'fix' this"*).

**Fold6 (SM8650, upper bound), cool, previewer OFF — the kindest condition that exists:**

| tier | chunk @ its own floor | F | RTF | duty `F/floor + m` | vs the 0.70 rule |
|---|---|---|---|---|---|
| `multi` small-q5_1 | 6 s @ 6,000 | **2.3 s MEASURED** | 0.38 | **0.42** | **PASSES**, 1.7× margin |
| medium q5_0 | 8 s @ 8,000 | **6.0 - 9.2 s** | 0.75 - 1.15 | **0.79 - 1.19** | **FAILS** (needs ≤ 5.3 s) |
| turbo q5_0 | 8 s @ 8,000 | **9 - 17 s** | 1.13 - 2.13 | **1.17 - 2.17** | **FAILS badly** |
| large-v3 q5_0 | 8 s @ 8,000 | > turbo | > 2 | > 2 | **FAILS** |

Provenance of the medium band — **three independent derivations, stated so they can be attacked
separately**, all converging: (A) the repo's own 19.22 ms/GFLOP calibration × a GFLOP count →
**6.0-7.6 s**; (B) the measured anchor's fixed term × the architecture ratio with decode kept
separate → **8.7 s**; (C) the measured anchor × discussion #89's four-machine ladder → **8.1-9.2 s**.
Band **6.0-9.2 s, central ≈ 8 s**. Turbo's 9-17 s is the repo's own prior figure
(`2026-09-04-turbo-cpu-gpu-research.md:100`), whose own conclusion reads: *"the app's own cadence
rule then needs a 20-33 s commit floor — above the 15 s wall cap — so under continuous speech the
never-shed queue grows without bound. That is 'unbearably slow' by construction, not by tuning."*

**Tab S10+ (MT6989, the TARGET class) — scaled by the MEASURED ×1.14-1.30 of §0.1, not by the
inferred ×0.8-1.0:**

| tier | F @ its floor | duty | previewer armed (×1.10-1.25) | + thermal drift (×1.15-1.33) | verdict |
|---|---|---|---|---|---|
| `multi` small-q5_1 | **2.6 - 3.0 s** | 0.48 - 0.54 | 0.52 - 0.66 | 0.59 - 0.86 | **PASSES** — thin at the top, and it is what ships |
| **medium q5_0** | **6.9 - 12.0 s** | **0.90 - 1.54** | **0.99 - 1.92** | **1.04 - 2.55** | **FAILS every arm** |
| turbo q5_0 | 10 - 22 s | 1.3 - 2.8 | 1.4 - 3.5 | 1.7 - 4.6 | **FAILS** — and MEASURED independently at 7.2-9.4 s on a 7-9× faster runtime (§0.2) |

**Asymptotic (T ≥ 8.96 s, the floor no longer binding — what perfect pacing could ever buy):**

| tier | Fold6 | **Tab (target)** | note |
|---|---|---|---|
| small-q5_1 | 0.26 | **0.30 - 0.34** | **MEASURED 0.344** ✓ — the model reproduces the one Tab datapoint |
| medium q5_0 | 0.83 - 0.94 | **0.96 - 1.22** | inside 1.0 on the Fold6 with zero margin; **over 1.0 on the target class** |
| turbo q5_0 | 1.23 - 1.64 | **1.41 - 2.13** | above 1.0 at any pacing — cannot work |

> **The single strongest statement in the throughput case: even with unlimited chunk length and the
> previewer disarmed, medium-q5_0 on the target device asymptotes at RTF 0.96-1.22 — over the line —
> and turbo asymptotes at 1.41-2.13 and therefore cannot work at any pacing.**

### 2.4 What is MEASURED vs INFERRED in that table

| MEASURED (this repo, device + duty stated) | DERIVED / INFERRED |
|---|---|
| `multi` F = 2.3 s, Fold6, CPU 4T, vc77, previewer-free (floor bench 2026-08-20) | every medium and turbo F (anchor × ladder, three derivations, ±20% band shown) |
| the four per-floor wallMs rows (2323/2257/2376/2542 at floor 512) | the 4.25 ms/frame anchor and the 47.5-73.2 ms/s marginal (a fit to 4 points at ±100 ms) |
| **Tab small-q5_1, ctx 942, 6.05 s, RTF 0.344** (§0.1) | the ×1.14-1.30 Tab factor (one chunk, one session; "service time" is an upper bound) |
| **Tab turbo encoder, tflite/XNNPACK, 4T: 4,777 ms cold / 5,500-6,365 sustained, thermalStatus 2 in six utterances** (§0.2, §0.3) | the 7-9× GEMM gap (same GFLOP convention, but a different runtime and quantisation → an upper bound on ggml-repack) |
| npu-turbo F = 1.89 s, Hexagon, Fold6 | the previewer tax ×1.10-1.25 (mechanism READ — per-node `ggml_barrier`; magnitude unmeasured) |
| `multi` in-session drift ×1.09-1.57; npu-turbo throttled +13% | the 8 GB two-big-core mid-ranger factor ×2-3 (**no repo data, no external benchmark — direction certain, size estimated**) |
| the previewer: RTF 0.054/0.116 @ 2T, +169 MB, 10-min flat at ~12% of one core | the encoder share 83-88% (bounded, not measured — the fitted "fixed" term bundles prompt-prefix decode) |

**Nothing has ever been measured for `extreme` or `ultra` on the ggml path on any device.** Every
medium/turbo ggml figure above is a scaled anchor. I have not interpolated a Tab number and
presented it as fact; the ×1.14-1.30 factor is one measurement and is labelled as one.

### 2.5 Why the ceiling is the kernel, not the model — and the lever nobody has pulled

Re-verified by me this session, independently of two lanes that found the same thing:

- `app/build.gradle.kts:80` compiles `-DGGML_CPU_ARM_ARCH=armv8-a+dotprod+i8mm`. ✓
- `ggml/CMakeLists.txt:152` `option(GGML_CPU_REPACK … ON)`; the app does not disable it. ✓
- `GGML_LLAMAFILE` defaults **OFF** (`:113-114, 197`) and the app never sets it — so **the one GEMM
  path that does handle Q5_0 is absent from the shipped binary.** ✓
- whisper.cpp v1.9.1 **does** wire the extra buffer types (`src/whisper.cpp:1363-1403`,
  `select_weight_buft` at `:1461`), so the repack path is genuinely reachable for whisper weights. ✓
- `repack.cpp:4528-4722` repacks **Q4_0, Q4_K, Q5_K, Q6_K, Q8_0, IQ4_NL, MXFP4** — and **Q5_0 and
  Q5_1 do not appear in the function at all.** ✓
- the fallback per-row kernels have no 2-row i8mm path for q5 either: the
  `__ARM_FEATURE_MATMUL_INT8` blocks in `arch/arm/quants.c` sit at 228/241 (q4_0), 521/534 (q4_1),
  1081/1094 (q8_0), 2262 (q4_K), 2892 (q6_K) — **nothing between the q5_0 entry at 846 and the q8_0
  entry at 1076.** ✓

> **Every CPU tier in production — `eco`/`base`/`pro`/`multi` q5_1, `extreme`/`ultra` q5_0 — runs
> its encoder matmuls one output row at a time, with no GEMM path of any kind.** And the encoder at
> n_ctx 512 is exactly GEMM-shaped: 512 columns of activations against each weight matrix, the shape
> a repacked kernel wins biggest on and a per-row `vec_dot` wastes most.

Available at the pinned commit (READ, HF tree API, 2026-09-13): **q8_0 for every candidate** — small
264 MB, medium 823,369,779 B, turbo 874,188,075 B — all on the repack i8mm path. **q4_K and q5_K
exist for no whisper model anywhere** and would need whisper.cpp's own `quantize` tool plus
self-hosting, which takes the app off the pinned upstream commit every `sha256` literal depends on.

One divisibility trap, READ + DERIVED: the repack path needs `ne[1] % 4 == 0` (q8_0/q4_0) or `% 8 ==
0` (K-quants). All layer weights qualify at d_model 768/1024/1280 and FFN 3072/4096/5120. The
**output/vocab projection does not for multilingual v3 and turbo** — `51866 % 8 = 2`, `% 4 = 2` — so
that one matmul stays slow there, while the English tiers' `51864` divides by both. The app's own
build comment already noticed the same trap for OpenCL (`build.gradle.kts:82-84`).

**The honest reading of the lever, and it is not the one the tier question wants.** If q8_0 + repack
buys ≥2×, it buys it for **`small` too** — taking the *currently shipping* tier from F 2.3 s toward
~1.2 s and off the 6,000 ms floor, at **264 MB instead of 823 MB**. The refuter is right about this
and it survives everything: **the quantisation lever is an argument for a faster `small`, not a
bigger tier.** §0.2 only makes the lever larger and better-evidenced than either of them thought.

### 2.6 Threads

READ, `whisper_jni.cpp:857-863`, with its in-code rationale: *"On mobile big.LITTLE, ggml's per-op
barriers make extra efficiency-core threads a NET LOSS: 4 threads (the performance-core count on
typical flagships) beats 6-8. Cap at 4."* The rationale is **a comment with no bench behind it**, and
the D9300+ has **no little cores** (4× X4 + 4× A720 @2.0), so the stated reason does not apply to the
target chip. With the previewer holding 2 of the 4 X4 cores, the right Tab answer may be
**n_threads 2-3, not 4**. INFERRED, untested, and it is the one lever needing no model or asset
change — but it needs a `setThreadCount` twin of the existing `setAudioCtxFloor`
(`whisper_jni.cpp:66-73`), which does not exist in `main`. Bounded at ≤ ×1.25 by the prior synthesis,
so it cannot rescue medium on its own.

**GPU is not an escape on the target class.** `GpuPolicy.ALLOWED_RENDERERS =
Regex("""Adreno \(TM\) (7\d\d|8\d\d|X\d)""")` never matches Mali-G720 → always CPU. And
`isGpuSafeModel` requires `.en`, because multilingual models are *empirically corrupted* on the
Adreno backend without crashing (small → garbage, turbo → empty). Note the unresolved contradiction:
`extreme` is a `.en` file and **would** be GPU-loaded on any Adreno 7xx/8xx, untested there, against
a standing owner ruling that the GPU path is dead.

---

## 3. WHAT THE UPGRADE BUYS, PER LANGUAGE

All figures **READ** from the Whisper paper PDF (`arxiv.org/pdf/2212.04356`, Appendix D, read
2026-09-13). The accuracy lane resolved a live mis-mapping hazard three independent ways — the
appendix's language headers are rotated 90° and `pdftotext -layout` emits them in **reverse** column
order, which silently assigns French's number to Spanish. Mappings are taken from `-raw` (true
left-to-right), cross-checked digit-for-digit against `-layout`, and calibrated against this
project's own prior observation of FLEURS small en 6.1 / fr 15.0. That is the standing
never-trust-one-rendering rule catching a real error.

### 3.1 FLEURS, small → medium — the only table covering all seven previewer languages

**zh is CER, not WER** (the paper spaces every character for languages without word separators;
Appendix C calls it *"an imperfect solution"*). **Korean is NOT in that list** — ko figures are true
word WER. Raw, `zh` 20.8 looks worse than `ko` 19.6 at small; they are different units and the
comparison is meaningless.

| language | small | **medium** | large-v2 | Δ rel small→medium | verdict |
|---|---|---|---|---|---|
| **fr** French | 15.0 | **8.7** | 8.3 | **−42.0%** | **large prize** |
| **zh** Chinese *(CER)* | 20.8 | **12.1** | 14.7 | **−41.8%** | **large prize — and medium is the ceiling** |
| **id** Indonesian | 16.3 | **10.2** | 7.1 | **−37.4%** | **large prize** |
| **ru** Russian | 11.4 | **7.2** | 5.6 | **−36.8%** | **large prize** |
| **de** German | 10.2 | **6.5** | 4.5 | **−36.3%** | **large prize** |
| **en** English *(multilingual)* | 6.1 | 4.4 | 4.2 | −27.9% | modest |
| **en** English *(`.en`, the checkpoint the app SHIPS)* | 5.3 | 4.5 | 4.2 | **−0.8 pts / −15.1%** | **NOT WORTH IT** |
| **ko** Korean | 19.6 | 16.4 | 14.3 | **−16.3%** | **NOT WORTH IT** |

Corroborated on CommonVoice 9 and MLS where those cover the language; every language except Korean
is cross-validated across two or three datasets.

### 3.2 The languages where it is NOT worth it, named

1. **English — and this is the app's own flagship.** Table 9 measures the actual A/B the app would
   run: `small.en` (`pro`) vs `medium.en` (`extreme`). **Fleurs.en_us 5.3 → 4.5 (−0.8).
   TED-LIUM3 4.3 → 4.3, a dead heat.** LibriSpeech-clean 3.2 → 3.0. The larger English gains
   (−2.2 to −3.1) sit only on far-field and conversational corpora (AMI-SDM1, CHiME6, CallHome,
   CORAAL) that do not describe a held phone. Quoting AMI-SDM 42.5 → 39.4 as "the English prize"
   would be a category error. And `small.en` already **beats** multilingual `small` on English
   (5.3 vs 6.1), so `pro` pre-banks part of the apparent prize. **Under one WER point for a 2.8×
   download — 4.3× if the variant that could actually run is the one that ships (§4.1).**
2. **Korean — scale does not rescue Korean.** 19.6 → 16.4 → still **14.3 at large-v2**: the
   second-smallest relative gain of the seven from the worst absolute start, roughly 2-3× French's
   WER at every size. The chooser must not promise Korean users that a bigger model fixes Korean.
3. **Chinese above medium is a published REGRESSION.** FLEURS zh: medium **12.1**, large 19.6,
   large-v2 14.7. CommonVoice 9 agrees independently (medium 23.2, large 29.1, large-v2 26.8) — two
   datasets, so not a rendering artefact. Turbo is additionally the variant the maintainer flags as
   degrading most on Cantonese. `zh-en` is a shipped pack. **Steering a Chinese user above medium
   makes their transcription worse.**
4. **French and English get nothing from turbo over medium** — fr 8.7 → 8.3, en 4.5 → 4.2. The
   large-model prize over medium is real only for id/de/ru/ko (1.6-3.1 pts), and no per-language WER
   for large-v3 or turbo is published **anywhere** — the paper stops at large-v2. §3.1's large-v2
   column is a labelled proxy resting on the maintainer's "performs similarly to large-v2", bounded
   by turbo's aggregate English penalty (open-ASR mean 7.83 vs large-v3's 7.44).

### 3.3 Three caveats that make every number above an upper bound

1. **All figures are fp16. No q5-vs-fp16 accuracy number exists for any whisper model, anywhere.**
   ggerganov's own `issues/2454` ("tests : add WER benchmarks", opened 2024-10-05, high priority,
   help wanted) is **still open with no results** — whisper.cpp ships no WER benchmark suite. And the
   upgrade changes *quantisation as well as size*: `pro`/`multi` are **q5_1**, `extreme`/`ultra` are
   **q5_0** (coarser) — in the direction that favours the incumbent.
2. **`no_context = true`** (READ, `whisper_jni.cpp:846`). The paper decodes with prior text carried
   across windows; every chunk here is decoded cold. A size step that buys decoder quality is being
   spent in the one configuration that withholds the decoder's input — and the decoder is only
   5-17% of this app's bill.
3. **`audio_ctx = 512`, validated on 12-layer encoders only.** §2.1 layer 3. **The prize may be
   negative**, and §5.1 is the check.

**Delivery fact that reshapes the whole section:** `ggml-medium-q5_0.bin` (539,212,467 B,
**multilingual**) exists in the repo the app already pins, and the catalogue does not contain it. The
app's only medium is **medium.en**. So today, the **largest accuracy prize in this entire research —
the 36-42% cut for fr/de/ru/id/zh — is not reachable from any tier the app ships**, while the one
medium tier it does ship serves the language with the smallest prize. Any reading that treats
`extreme` as "the upgrade" is shipping the wrong artefact.

### 3.4 The strongest pro-upgrade argument, and the haircut it needs

The worth-refuter went looking for evidence that the previewer made the remaining gain invisible and
found the opposite in this project's own record — `2026-09-11-language-qualification-table.md`:

| language | previewer (published upstream) | whisper-`small` | ratio |
|---|---|---|---|
| **zh** (shipped pack) | wenetspeech `test_net` **9.66 CER**, aishell-1 **3.63** | 20.8 FLEURS / 29.4 CV9 | **≈0.2×** — previewer ~5× better |
| **ko** (shipped pack) | **8.635 CER** chunk-16 beam-8; greedy stand-in 10.21/11.07 | INFERRED ~18.1 | **~0.48-0.59×** |

And the open, unowned defect, verbatim (`:382`):

> **O8 — The rung-3 rule has no arm for a previewer FAR BETTER than the finalizer** — *"> 1.5× →
> strip-only; > 2× → stop" only catches the previewer being worse… The strip would advertise an
> accuracy the app cannot deliver.*

> **So the previewer did not make finalizer quality invisible. It made it VISIBLE for the first
> time, by putting a better reference 0.4 s in front of it on the same screen.** The value claim,
> correctly stated, is not "buy more accuracy" — it is **"stop the finalizer from typing worse text
> than the app already shows the user."** That is a defect fix, not a feature, and it is the only
> framing that survives all five of the worth-refuter's attacks.

**The haircut I have to apply, which the refuter did not.** Reading the source rows myself:

- The zh **9.66 / 3.63** figures are from the **other export** of that checkpoint (chunk-32 /
  left-256, `zrjin/…-ctc-streaming-2023-11-05`), and the table says so explicitly (`:519`): the
  published figure is *"an **optimistic bound** on ours"*. The app runs chunk-16. **Nothing is
  published at our export.**
- The ko **8.635** is **beam-8**, while the app runs `greedy_search`; there is **no published greedy
  CER for that model at any chunk** (`:518`), and the 10.21/11.07 greedy stand-in belongs to a
  *different author's* model.
- Both ratios are **cross-corpus and cross-metric**, and the table's own `E7` warns that without a
  traditional→simplified fold *"the ratio flatters us."*

**Direction: near-certain — a 5× and a 2× margin survive a heavy haircut.** Magnitude: not
established. And what medium does to it, per §3.1: **zh 20.8 → 12.1 closes most of the gap to the
previewer's ~9.66 — the inversion is fixed, and medium specifically, since large/large-v2 are zh
regressions. ko 19.6 → 16.4 leaves the finalizer ~1.5× worse than the previewer at any size —
Korean needs `O8`'s third arm, not a bigger finalizer.** `E7` (~1 day, desktop, no device) prices
this directly and is the cheapest experiment in the research.

---

## 4. THE CEILINGS

### 4.1 Memory beside a 169 MB previewer — and the disk sizes invert the ranking

Hparams **READ exactly**, decoded from the first 48 bytes of the pinned HF artefacts by HTTP Range
(all four returned 206). The whole story is in one column: **medium is 24 text layers, turbo is 4** —
and the cross-attention KV cache scales with *text* layers.

KV formula READ from `src/whisper.cpp:968-1013` and `:3387-3428`, `itype = F16`:
`2 × n_text_state × n_text_layer × GGML_PAD(ctx,256) × 2`. **Fully resident, not merely allocated** —
`whisper_kv_cache_init:1008` calls `ggml_backend_buffer_clear`, which is `memset` on CPU
(`ggml-backend.cpp:2262-2265`). Every page is dirty before a single sample of audio arrives.

| tier | weights | KV total | **EXACT fully-resident floor** | **after ONE temperature fallback** |
|---|---|---|---|---|
| `pro`/`multi` small | 190.1 MB | 80.22 MB | **270.3 MB** | 383.6 MB |
| **`extreme`** medium.en 539 MB | 539.2 MB | **207.62 MB** | **746.8 MB** | **1,048,833,469 B = 1.049 GB** |
| **`ultra`** turbo 574 MB | 574.0 MB | 49.81 MB | **623.8 MB** | 686.8 MB |

> **`extreme` (539 MB on disk) costs 123 MB MORE resident RAM than `ultra` (574 MB on disk).** Disk
> size inverts the memory ranking. The `ultra` row reproduces an independent prior derivation's
> "exact floor 624 MB" to the MB, and the small row reproduces its "small 80 MB" KV — formula
> cross-validated.

**The mechanism nobody in this repo had written down**, chain all READ: `temperature_inc = 0.2` stays
ON (`whisper_jni.cpp:846-856` — the sole defence against degenerate repetition loops, hit on-device
2026-07-18, and it must never be disabled) → greedy `best_of = 5` (`:6278-6281`) → at any
`t_cur > 0`, `n_decoders_cur = 5` (`:7317-7336`) → `kv_self` is **freed and reallocated at
`factor = n_decoders_cur + 2` = 7×** (`:7402-7419`) → `kv_self_n_dec` is **never lowered; no path
shrinks it back** for the life of the `whisper_state`. **On `extreme` that is +302 MB, permanent,
triggered by exactly the failure case the fallback exists to catch.**

**And it is the worst kind of memory: `grep -c mmap src/whisper.cpp` = 0.** The loader `read()`s
straight into `tensor->data` (`:1922-1925`). Every weight byte is dirty anonymous — not file-backed,
not droppable, only zram (poor ratio on entropy-dense q5 blocks) or a kill. **Do not read the
previewer's good pressure behaviour across:** its +140 MB post-`release()` residual is ORT/sherpa
*libraries staying mapped* — clean, file-backed, free to reclaim.

Totals with the previewer armed (its **+169 MB RSS / +146 MB PSS** is MEASURED on the Tab, and is the
shipped configuration for all seven pack languages):

| | `extreme` | `ultra` |
|---|---|---|
| fully-resident floor + previewer | **916 MB** | **793 MB** |
| after one fallback + previewer | **1,218 MB** | 856 MB |
| allocated ceiling (DERIVED ±20%) + previewer + baseline | **~1.4-1.5 GB** | ~1.3 GB |
| **worst realistic: TTS spoke in the last 5 min** (`TtsEngine.kt:37` ~0.8 GB, `IDLE_UNLOAD_MS = 5 min`, and AudioArbiter stops playback without unloading) | **~2.2-2.6 GB** | ~2.1-2.3 GB |

**Memory scales ~zero with thread count** — ggml shares weights, KV and activations across workers.
Threads move heat and time, not footprint.

Corroborating from §0.2, different runtime, same chip: the only *correct* turbo configuration
measured on the Tab ran at **5.8-6.0 GB RSS** on a 12 GB tablet. Big-model CPU inference footprints
are not modest.

### 4.2 The thermal curve — MEASURED, and it fails the gate

| anchor | figure | what it is |
|---|---|---|
| **Tab, 4 threads, turbo encoder, ~100% duty (§0.3)** | **×1.15-1.33 drift; `thermalStatus` 2 (MODERATE) within six utterances** | the curve the memory lane said did not exist. Different runtime, same chip, same threads, same duty. |
| `multi` in-session drift | F **2.3 → 2.5-3.6 s (×1.09-1.57)** | the only warm-device *ggml* CPU spread in the repo — **at ~38% duty, idling between commits** |
| npu-turbo throttled vs cool | 2,140 vs 1,890 ms (**+13%**) | the Hexagon — a lower bound, since the NPU is the efficient path |
| the previewer's flat 10-minute run | RTF 0.119 → 0.116, +1.2 °C, `thermalStatus` 0 → 0 | **NOT evidence about a finalizer.** Its own author: *"~12% of one core… which is why nothing moved."* On AC, on a tablet, looping an 11-second clip. |
| cold-start penalty | **none** | the earlier "cold/throttled 17-33 s" column was a 768-vs-512-frame artefact; at matched frames cold reads ×0.84-1.02 against warm. So drift is a *warming* effect: **the first minute of a session is the best minute**, which is the wrong direction for a dictation app. |

**Why drift is a correctness problem, not a slowness problem.** At duty 0.9 the app recovers on every
pause; at duty 1.2 it never catches up while speech continues. **Thermal drift moves an arm across
that line during a session.** And there is no relief and no instrument: `slowCommitIntervalMs` has a
distinct slow row **only** for `npu-turbo` — its own KDoc says *"on those tiers the governor is INERT
BY CONSTRUCTION"* — and `grep -rn "thermalStatus\|getThermalHeadroom\|THERMAL" app/src/main
--include=*.kt` returns **nothing**. Zero thermal instrumentation in the product.

Applied to §2.3's Tab medium row: duty 0.99-1.92 armed, × 1.15-1.33 drift = **1.04-2.55. Every
point over 1.0.**

### 4.3 Which device RAM classes are excluded

| nominal RAM | `extreme` | `ultra` | reasoning |
|---|---|---|---|
| **4 GB** (~3.6 GB `totalMem`) | **RULED OUT** | **RULED OUT** | 0.9-1.2 GB unreclaimable anonymous in a non-foreground-adj process |
| **6 GB** (~5.5 GB) | **RULED OUT** | **RULED OUT** | the fallback floor alone (1.05 GB) plus previewer plus baseline against a `MemAvailable` that is commonly 1.5-2.5 GB. **`extreme`'s own `minRamBytes = 5.5e9` was written to mean "6 GB-class" — on these numbers it is one class too low.** |
| **8 GB** (~7.4 GB) | **MARGINAL — DO NOT STEER** | **MARGINAL — DO NOT STEER** | passes the repo's "PSS ≤ 40% MemTotal" line with room, but `MemTotal` is the wrong denominator for lmkd, there is no pressure warning on API 34+, and the TTS case lands at 2.2-2.6 GB |
| **12 GB** (Fold6, Tab S10+) | fits | fits | **memory does not block a bigger tier on the devices the owner tests on. Throughput does.** |

### 4.4 On API 34+ the app cannot be told it is low on memory — so the failure is a kill, not a degradation

Verbatim from AOSP `ComponentCallbacks2.java` (read 2026-09-13, fetched twice, both renderings
agree): **`@deprecated Apps are not notified of this level since API level 34`** on
`TRIM_MEMORY_RUNNING_MODERATE` (5), `RUNNING_LOW` (10), `RUNNING_CRITICAL` (15), `MODERATE` (60) and
`COMPLETE` (80). Only **`UI_HIDDEN` (20)** and **`BACKGROUND` (40)** survive — and those are
*lifecycle* signals, not pressure signals.

Consequences: the app's guard `level >= TRIM_MEMORY_RUNNING_LOW` (`FloatingBubbleService.kt:5138`)
now only ever fires on 20/40, and it correctly excludes RECORDING/FINALIZING/CONNECTING anyway — so
**during a session there is no pressure signal at all.** Then: `ro.lmk.critical` defaults to **0 =
"any process"** eligible; `ro.lmk.kill_heaviest_task` prefers **the heaviest**; and the bubble is a
microphone FGS with a `SYSTEM_ALERT_WINDOW` overlay, so it sits at **`PERCEPTIBLE_APP_ADJ = 200` at
best, not `FOREGROUND_APP_ADJ = 0`** — because the user is by design in *another* app, which is the
entire product. At 1-1.5 GB of dirty anonymous memory it is the heaviest task on the device.

> **The sequence on a pressured device is: session running → no callback → SIGKILL → the bubble dies
> mid-sentence, taking the queued un-typed chunks with it.** No `largeHeap` in the manifest, and it
> would not matter — the allocation is native, so `OutOfMemoryError` never fires.

**And `TRIM_MEMORY_UI_HIDDEN` fires on merely leaving the app**, which releases both engines and
re-arms 1.5 s later. On a big tier that re-load is a full `read()` + tensor copy of every byte (no
mmap): measured CPU small = **237 ms** (Tab) / **1,515-1,933 ms** (Fold6) / **11,672 ms**
(first-in-process), DERIVED **×2.8-3.0** for 539/574 MB and **×4.3** at q8_0's 823 MB. And
`startAudioInput()` is nested inside the engine's `onOpen()`, so during that window **the microphone
is shut and the audio is never captured — irrecoverable.** The previewer cannot paper over it: with
no mic open, the strip is blank too. **The upgrade's own byte count widens, by ~3-4×, the one window
in which this app silently loses speech — several times a day.**

### 4.5 The RAM gate is not a gate, and it currently lies to the most vulnerable user

`minRamBytes` reaches exactly one predicate — `isRecommendedForDevice(model, totalRamBytes) =
totalRamBytes >= model.minRamBytes` (`WhisperModel.kt:464-465`) — which reaches exactly one place: a
**badge and a note** on the onboarding card. Nothing refuses the download; nothing refuses the
selection. The note reads, verbatim (`OnboardingModelScreen.kt:783-793`):

> *"High-end devices only — this tier needs more RAM than this device reports. You can still pick it,
> but performance may suffer."*

Two problems. **(a) The copy names the wrong failure.** "Performance may suffer" is what a slow model
does. What an over-budget model does here is grow an unbounded queue and eventually get the service
killed mid-dictation. **(b) The badge asserts something false to exactly the user most likely to be
hurt.** DERIVED from three READ facts: an 8 GB mid-range phone — two big cores rather than four —
reports ~7.4 GB `totalMem`, clears `extreme`'s 5,500,000,000 threshold, and is shown a green
**"Recommended for your device"** badge (`OnboardingModelScreen.kt:750`) on a tier it cannot run. RAM
is the only axis the card reads and it is not the axis that binds.

And the fleet is not the owner's fleet: `minSdk = 26` (READ) — hardware back to 2017. The app's own
code already says this better than I can, on why `pro` had to leave the fast row: *"the CHOOSER
cannot see any of that (pro's eligibility is RAM-only), so pro is offered to, and installable on, the
whole fleet, including exactly the devices the NPU gate declines."*

---

## 5. THE MEASUREMENT PROTOCOL

**Designed against the failure the previewer hides.** Three READ facts compose into the detection
hole, and it is already shipped:

1. `inFlightStripLabel` returns **`null`** on `sessionHasLocalPreview` **before it ever looks at
   `depth`** — and per `CommitCadencePolicy.kt:95-100` that label was *"the **only** field signal
   that this floor was losing, because the engine queue never sheds."*
2. Its replacement is a Kotlin `Log.i` (`EndpointDiag.queueLine`), and `proguard-rules.pro:85-95`
   `-assumenosideeffects android.util.Log` strips **every** Kotlin `Log` call from release — which is
   what the internal track is.
3. **And the label is doubly suppressed** (my correction, §0.4 #4): `FloatingBubbleService.kt:5357`
   returns *before* line 5358 ever calls the label function.

> **On the shipped configuration, for every pack language, a growing finalizer queue is invisible on
> screen AND invisible in logcat. A casual device session cannot falsify a bad tier — "it feels
> fine" is the EXPECTED report from a tier that is failing**, because the strip is 0.4 s behind the
> voice regardless of what the finalizer is doing.

Worse than lateness. `FloatingBubbleService.kt:4955-4966` is the app describing this exact tier:
*"A slow model (e.g. the large tier) lags several segments behind real time; those queued transcribes
finish after the last utterance, and without waiting they'd complete post-teardown and be dropped by
the stale-listener guard."* With `FINALIZE_TIMEOUT_MS = 300_000L`, **duty 1.5 sustained ~10 minutes
(or 2.3 over ~4) overruns the drain, the listener detaches, and the tail is SILENTLY DROPPED** — text
loss, reported as "it lost the end of what I said", traced only by a `Log.w` release builds strip.

**So before any session: make the queue observable.** Cheapest of three, pick one:
(i) run on a **debug build on a device that never had the Play copy** (`Log` survives, `queue:` is
readable); (ii) spend the **two** lines — `inFlightStripLabel`'s first arm and the `:5357` owner gate
— behind the existing developer toggle; (iii) the one-line `proguard-rules.pro` change that keeps the
app's own diag lines in release, which is a standing open owner decision.

### Run order. Each step can kill the next, so do not reorder.

**STEP 0 — the accuracy cliff. The gate ahead of every other gate. One session.**
If medium at ctx 512 does not hold the line `small` held, the prize is *negative* and no throughput
work is worth doing. Nobody has ever run this on an encoder deeper than 12 layers.
- Harness: `WhisperBenchTest.bench_audio_ctx_floor_ab` — but **it needs the two-literal fix of §0.3
  first**: reference → **1500**, `FLOOR_CANDIDATES` → `listOf(768, 512)`. As written its reference is
  512 itself, so it would score medium's garble against medium's garble and print PASS.
- Slices 1/2/3/8 s (all binding); WER via `util/WerMath.kt`; honour the `binding=true|false` field.
- Gate: `WerMath.FLOOR_WER_GATE = 0.10` on binding slices.

**STEP 1 — the quantisation A/B, on `small`. The highest value per hour in the whole research.**
This is the experiment that could reopen the tier question, and §0.2 says the lever is large.
- Fetch `ggml-small-q8_0.bin` (**264 MB**) from the pinned commit; bench against the shipped
  `ggml-small-q5_1.bin` (190 MB), same device, same session, `bench_whisper_rtf_across_slices`.
- q8_0 gets the repack i8mm GEMM path; q5_1 cannot reach it (§2.5). **Confirm i8mm at runtime** —
  `ggml_cpu_has_matmul_int8()` is a `getauxval` check and is verified on neither SoC; ggml prints its
  system info at load to logcat tag `ggml`, which **survives release stripping** (proguard strips
  only `android.util.Log`).
- If the gap is ≥2×, the right first use is `pro`/`multi` — the *currently shipping* tier off its
  6,000 ms floor at 264 MB, which is a better product than any big tier can offer.

**STEP 2 — medium, sustained, at duty. THE ship gate, and nothing substitutes for it.**
- **Tab S10+** (the target class), **OFF the charger**, normal ambient.
- **Continuous speech for 15 minutes** — not a looped clip. The queue only grows *while speech
  continues*; at the 8,000 ms floor that is ~110 commits.
- **Previewer ARMED** (English pack installed, "Show live words" on). That is the shipped
  configuration: +2 threads and +169 MB *beside* the finalizer, not instead of it.
- Log per commit: **F**, **queue depth**, `PowerManager.getCurrentThermalStatus()`,
  `dumpsys battery` temperature, **`RssAnon`** (from `/proc/<pid>/status` — it is the unreclaimable
  part, and confirming the anon-vs-file split validates the whole §4.3 ruling).
- Run it **twice — armed and disarmed** — and report the ratio. The ×1.10-1.25 previewer tax is
  unmeasured and load-bearing.
- Watch the **FINALIZING wait at stop**: on the shipped build that is the only surviving symptom.
- Harness: `bench_whisper_rtf_across_slices` already enumerates `WhisperCatalog.entries` (**not
  `pickable`** — retired tiers are included), so it benches `extreme` with no code change, and its
  **15 s slice** needs 814 frames and so escapes the 512 floor, measuring the asymptotic regime
  directly. It is a **burst** bench; it needs a repeat-count `-e` arg to become the sustained run.
- Prerequisite, and it is awkward: `extreme`/`ultra` are `retired + unsupported` and out of
  `pickable`, so there is **no in-app download path** — they need a manual file drop into
  `filesDir/models/` at the exact catalog filename and size.
- **Never `installDebug` / `connectedAndroidTest`** — they uninstall first and wipe the owner's
  models. `adb install -r` both APKs + `am instrument` preserves app data, debug→debug only. A
  locally-built APK **cannot** install over a Play copy, and the Tab carries one (vc 86).

**STEP 3 — free, no device, once F exists.** Run the measured F through `tools/vadsim` for the
predicted commit cadence and queue trajectory before anyone ships a floor.

**Also cheap and worth doing in the same sessions:** (a) `adb logcat -s ggml:I` on the shipped
release APK replaces every DERIVED compute-buffer figure in §4.1 with exact numbers in ~3 minutes,
no build (whisper.cpp logs `model size`, `kv self/cross size` and all four `compute buffer` lines to
tag `ggml`); (b) `RssAnon` before and after deliberately tripping the quality gate proves the +302 MB
ratchet of §4.1; (c) `dumpsys batterystats` per tier — **no mAh figure for any tier exists anywhere
in this repo**, and the ~2.6× duty claim is arithmetic, not measurement.

**Turbo and large-v3: zero minutes.** Settled by arithmetic on a shared 32×1280 encoder and now by a
direct Tab measurement on a 7-9× faster runtime (§0.2). One 15-minute medium run answers more than
six devices × four models × two minutes each.

**On "ship all the models and see which one feels the best":** the instinct is right — six real
devices *are* a better instrument than a literature search, and the owner is right that the workload
changed under these tiers and nobody re-measured. But **on the shipped build both failure modes this
upgrade introduces are undetectable by feel, by construction, in code.** A six-device tour will
return "they all feel fine" and that answer will be uninformative. Make the queue observable first
(above), or the session is spent for nothing.

---

## 6. WHAT WOULD CHANGE IN THE APP IF THE ANSWER IS YES

Exact files and lines. Note the shape: this is a **retirement-reversal + copy + cadence + migration**
change, not a threshold tweak, and four JVM tests go red by design on a partial one.

### 6.1 Un-retire (the prerequisite for anything else)

| file:line | edit |
|---|---|
| `WhisperModel.kt:214-215` (`extreme`) / `:237-238` (`ultra`) | `retired = false` **and** `unsupported = false` **together** — a half change correctly reddens `WhisperCatalogHelpersTest.kt:148` (`every_unsupported_tier_is_also_retired`), since `unsupported` is what raises the migration card |
| `WhisperModel.kt:62-70` | the KDoc sentence *"extreme/ultra keep both flags: their targets really are faster"* becomes false |
| `WhisperModel.kt:401-402` (`isCpuFallbackEligible`) | **leave the `model.id != "ultra"` clause alone.** The 128-bin filterbank exclusion from the NPU mel-donor path is a separate, still-true fact (`pcmToMel` is 80-bin) |
| `WhisperCatalogHelpersTest.kt:124` | `pickable_is_exactly_pro_and_multi` — **the hard pin**; plus the retirement family at `:106`, `:114`, `:137`, `:148` |
| `ModelTierCopy.kt:22-67` | a `copyById` entry becomes **mandatory** — `ModelTierCopyTest.kt:26` censuses `entries.filter { !it.retired }` |
| `ModelTierCopyTest.kt:91` | `retired_and_unknown_tiers_have_no_copy` drops that tier's line |
| `UnsupportedTierGatePinTest` | takes the same edit |

### 6.2 `DEFAULT_MODEL_ID` — it does NOT move

**READ, `WhisperModel.kt:459`: `const val DEFAULT_MODEL_ID = "pro"`. It must stay `"pro"` on this
recommendation, and it is not the chooser's highlight anyway.** Its three real jobs (`:455-458`) are
fallbacks: the auto-setup re-entry in `OnboardingSetupViewModel.kt:106`, the download-phase
re-resolve in `OnboardingFlowScreen.kt:915`, and `ModelMigration`'s ENGLISH target
(`ModelMigration.kt:34`). The highlight is the **steer** —
`ModelTierCopy.steerIdForLanguageTag` (`:87-89`: `en*` → `pro`, else `multi`) — and
`OnboardingModelScreen.kt:299-305` carries a comment saying the flag is named `isSteered` rather
than `isDefault` precisely because conflating them was the Bengali-review defect.

Two structural bars a new default would have to clear: `default_is_pickable`
(`WhisperCatalogHelpersTest.kt:160`) and `a_gated_tier_is_never_pickable_and_is_never_the_default`
(`:252`) mean it must be non-retired and non-gated. And `ChooserSteerWiringPinTest.kt:33,218,614`
**forbids the `DEFAULT_MODEL_ID` spelling in the chooser file at all**, because rebinding the
highlight to it is the Bengali defect. A default change touches 4 call sites plus 7 test literals; a
steer change is one function (`steerIdForLanguageTagFor`, `:121-129`) plus its truth tables.

**Neither should move on this evidence.** `ModelTierCopy.kt:100-116` records the precedent exactly:
turbo was **refused promotion** under decision 8 while its accuracy claim was unproved, and got it
only after the owner's on-device A/B.

### 6.3 `minRamBytes` — what it should become, and it is not a bigger number

The ladder brief asks for "an honest `minRamBytes`" and rightly insists the threshold stay advisory
so the owner can try big models on small devices. The correction it is missing: **on this axis no
threshold is honest**, because RAM is not what binds, and the green badge asserts something false to
exactly the 8 GB mid-range user most likely to be hurt (§4.5).

> **Set `minRamBytes` on both new rungs ABOVE any shipping phone — e.g. `64_000_000_000L` — so that
> `isRecommendedForDevice` returns false on every device and NO device is ever told these tiers are
> recommended.** That leaves the existing Warning surface as the only hardware claim either card
> makes, keeps the rungs fully reachable (the owner's whole requirement), and stops the app vouching
> for them. `ramGated` is `minRamBytes > 0L` (`OnboardingModelScreen.kt:645`), so the note renders.

Fix the copy in the same commit: *"performance may suffer"* names the wrong failure. The real ones
are an unbounded queue and a mid-dictation service kill.

If a **hard** gate is ever wanted, `pickableFor` (`WhisperModel.kt:366-374`) takes no RAM argument;
threading `totalMem` in means editing both chooser call sites and keeping
`ModelTierCopy.orderedForLanguageTagFor` a permutation (`ModelTierCopyTest.kt:159`, `:222`).
`WhisperModelManager.kt:228-238` already provides `totalMem`.

### 6.4 The turbo card's "Best accuracy, fastest" is about the NPU and would be FALSE of a CPU turbo

READ, `ModelTierCopy.kt:59-66`, exact strings:

> headline **`"Best accuracy, fastest"`**; badges `"90+ languages"`, `"1072 MB"`; body
> *"Large-v3's own encoder, on your phone's AI chip. The most accurate model this app ships, and the
> fastest on this device — ahead of the 190 MB Multilingual model on both counts."*

The KDoc above it records the owner ruling (2026-09-10) and scopes both claims correctly: *"on your
phone's AI chip"*, *"the fastest on this device"*, on a **MEASURED F = 1.89 s on the Hexagon**, with
the explicit note *"'fastest' and 'most accurate' rank our lineup, not the world."* **The claim is
true of the NPU tier and must keep it — removing a true, owner-ruled claim is a regression.**

A CPU `large-v3-turbo` shares the *weights* and none of the silicon: F ≈ 9-17 s on a Fold6-class CPU
(DERIVED), and **7.2 s cold / 8.3-9.4 s sustained MEASURED on the Tab** (§0.2).

> **`"Best accuracy, fastest"` on a CPU turbo card would be false on its second word.** And the
> reason belongs in the code rather than merely being avoided: whisper's encoder cost is set by
> `audio_ctx`, not by the audio, so this app's bill is ~85% encoder — and turbo is large-v3's **full**
> 32-layer/1280-wide encoder with the decoder cut to 4 layers. **Turbo wins on decode and loses on
> encode.** Published benchmarks run long files where decode dominates; **in this app medium may well
> be faster than turbo, the opposite of every benchmark table.**

Constraints any new card must satisfy, all JVM-pinned in `ModelTierCopyTest.kt`: a truthful size
badge derived from `approxBytes` (`:32`, `:42`); a language badge matching `ModelScope` (`:68`); a
position word from the allowed set (`:56`, `:674`); no reference to a retired tier (`:101`); no
cross-app comparison (`:558`); no absolutes (`:575`); and a headline no other offered tier shares
(`:670`). **Invent no comparative speed claim for a new rung — nothing has been measured.** Say what
the model *is* (size, languages, what it is good at) and let the hardware say the rest. The prior
synthesis already drafted an honest headline — `"Best multilingual quality, slower"` with a body
saying the reason to pick it is the words, not the speed, plus a Wi-Fi line — worth reusing rather
than re-deriving.

### 6.5 Cadence — the 8,000 ms placeholder must never survive a ship

| file:line | edit |
|---|---|
| `CommitCadencePolicy.kt:150` | `MIN_COMMIT_INTERVAL_LARGE_MS = 8_000L` — **never keep the placeholder.** Re-derive from a MEASURED F under the object's own rule, or make it an owner ruling with the arithmetic written into the KDoc the way `MIN_COMMIT_INTERVAL_TURBO_MS` does |
| `:247` | the `"extreme", "ultra" ->` row if they diverge |
| **`:288-296`** | **`slowCommitIntervalMs` — a CPU tier gets a real slow row ONLY if one is added here.** Today it falls through to its own fast floor, so the governor is inert. **A slow row must be derived from a THROTTLED F, not a cool one** (§4.2) |
| `CommitCadencePolicyTest.kt:157` | `everyCatalogTierIsNamedExplicitly` holds the whole map — the alarm that fires on any tier change; plus `:469` and `:509` |
| `CommitCadencePolicy.kt:143-149` + `CommitCadencePolicyTest.kt:356` | **changing 8,000 without changing `SileroEndpointer`'s pre-session literal fails `theEndpointersPreSessionFloorIsThisObjectsLargeInterval`** |
| `EndpointerTuning.kt:288-300` | the tier list is repeated **as documentation** in the "NO COMMIT-INTERVAL CONSTANTS LIVE HERE" comment and takes the same edit |

**And note the ceiling on this lever:** `SegmentCapPolicy.MAX_SEGMENT_WALL_MS = 15_000L` commits
regardless of any floor, so a slow row above ~15,000 ms is inert — and an 8,000 ms floor *already*
delivers 5-8 sentences per commit. There is no floor that rescues a tier at F ≈ 8-12 s.

### 6.6 Migration, and the one thing that must NOT be removed

`ModelMigration.decide` fires only on `unsupported` (`:48`), so clearing that flag stops the card for
all three cohorts. **A migration target that is itself retired is the one unrecoverable defect here**
— `targetIdFor`'s own KDoc records why: *"moving a MULTILINGUAL user to the ENGLISH-only default
silently breaks dictation in every other language with no warning (that was the MF3 bug)."*

And counter-intuitively: **`sessionLanguageFor`'s resolution of Auto to `"en"` on an ENGLISH-scope
tier stays** (the pin whose leak into the previewer was fixed in 4.4.1, ruling B1). Retiring a tier
hides it from the chooser; it does not uninstall it, so users dictating on `small.en` still exist and
that pin is still correct for them. A future reader will see a vacant-looking special case and delete
it — add a comment saying exactly that.

### 6.7 What I would actually change, given the verdict

No catalogue promotion, no steer, no default, no cadence move. Instead, in descending value:

1. **Measure the GEMM lever** (§5 step 1). It is the only finding that could move the ceiling, it is
   bounded from below at ~7× by an on-device measurement, and it argues for a **faster small**.
2. **Fix the two shipped inaccuracies regardless of the tier decision**: the `extreme`/`ultra`
   cadence rows are 8,000 ms placeholders with **no slow row**, so the governor is inert on exactly
   the two tiers most likely to need it; and the RAM "gate" is a badge whose copy names the wrong
   failure (§4.5, §6.3).
3. **Close the detection hole** (§5) — two lines behind the dev toggle, or the one proguard line.
   Without it, no device session can falsify a bad tier, now or later.
4. **Own `O8`** (§3.4). The zh previewer-beats-finalizer inversion is live in production and is a
   better reason to want a bigger finalizer than the WER table is. `E7` prices it in ~1 day of
   desktop compute.
5. **The cheaper encoder, which the repo already contains.** The vendored whisper.cpp v1.9.1 already
   builds a **`parakeet` library target** (`src/parakeet.cpp`, `include/parakeet.h`,
   `src/CMakeLists.txt:112-120`). Parakeet TDT is a fundamentally cheaper *encoder* architecture —
   the only axis that actually cuts this app's bill — and the app's own engine can host it with no
   new dependency. **The whole turbo/distil family optimises the decoder; this app's bill is the
   encoder. Every member of that family is a non-answer here.**

---

## 7. KILL LINES, STATED IN ADVANCE AS NUMBERS

Each is a number, measurable in §5's runs, decided before the data arrives.

| # | quantity | kill line | what it kills |
|---|---|---|---|
| **K0** | medium at ctx 512, `maxWer` on binding slices vs a **ctx-1500 reference** | **> 0.10** (`WerMath.FLOOR_WER_GATE`) | **The prize is NEGATIVE — stop here.** The 512 floor is the only reason medium is affordable and it is validated on 12-layer encoders only. Raising it toward 768 re-multiplies the encoder by a MEASURED ×1.50 and makes medium hopeless. |
| **K1** | medium F, Tab, ctx 512, previewer ARMED, 15 s slice | **> 5.3 s** | medium cannot hold the 8,000 ms row under the app's own written rule (`F/floor + m ≤ 0.70`, m ≈ 0.04). All three current derivations put it at 6.9-12.0 s. |
| **K2** | medium F vs its own commit floor | **> 8.0 s** | **absolute kill** — the queue grows without bound while speech continues, and the engine queue never sheds. |
| **K3** | F drift, first 5 min → last 5 min | **> 25%** ⇒ needs a slow row before it can ship. **> 50%** ⇒ does not ship. | the tier moves across the queue-growth line *during* a session and the governor is inert on every CPU row. |
| **K4** | `getCurrentThermalStatus()` within 15 min | **any step to 2 (MODERATE)** | **already breached** on the Tab by a 4-thread big-model CPU encoder within **six utterances** (§0.3). |
| **K5** | queue depth | reaches `BACKPRESSURE_ENTER_DEPTH` (**2**) twice in a row, or **fails to return to 0 on a pause** | the direct empirical form of the RTF gate; the arm is not recovering. |
| **K6** | `RssAnon`, previewer armed | **> 1.2 GB**, or **any monotonic climb** after the first fallback step | a climb is a leak, not a budget; 1.2 GB in a `PERCEPTIBLE_APP_ADJ` process with no API-34 pressure warning is a kill waiting for a busy device. |
| **K7** | previewer armed ÷ disarmed F | **> 1.10** | the ×1.10-1.25 tax is real and load-bearing, and the shipped configuration is *armed* for all seven pack languages. |
| **K8** | `small-q8_0` ÷ `small-q5_1` on the same device, same session | **< 2.0×** | the GEMM lever is not worth pursuing and **the tier question stays closed on this inference path**. At ≥2.0× it reopens — for a faster `small` first. |
| **K9** | turbo / large-v3 on CPU | **already killed** — zero device minutes | 2.08× medium's encoder in an ~85%-encoder workload; crossover needs ~750 decoded tokens in one encode against a 448-token context; and MEASURED at 7.2 s cold / 8.3-9.4 s sustained on the Tab on a runtime 7-9× faster than the app's. |
| **K10** | any tier that is steered, recommended or defaulted | **F not MEASURED on that device class** | the app's own rule: *"a tier keeps a floor only while its full-segment F is MEASURED."* No provisional branch exists for tiers someone hopes are fine. |

---

## 8. WHAT WOULD MAKE ME WRONG

Ordered by how likely I think each is to actually be the thing that overturns this.

1. **The GEMM lever delivers what §0.2 says the hardware can deliver.** This is the most likely way
   I am wrong, and it is my own strongest finding pointed at my own conclusion. If ggml's repack
   i8mm path on q8_0 recovers even half the measured 7-9× gap, medium at ctx 512 lands at F ≈ 1.7-1.8
   s and passes the 0.70 rule with margin; at the full gap it lands at 0.7-1.1 s and finalizes faster
   than `small` does today. **My verdict is explicitly conditional on "this inference path", and K8
   is the one experiment that settles it.** What I cannot know: XNNPACK's microkernels are more
   mature than `repack.cpp`'s, the measurement is int8-weights/fp32-activations rather than q8_0
   blocks, and it was taken in the 1500-column regime where a GEMM wins biggest. 7-9× is an upper
   bound, not a prediction.
2. **My Tab factor rests on ONE chunk.** The ×1.14-1.30 of §0.1 is a single 17.6 s commit from a
   single session, and "service time" is VAD-line-to-VAD-line so it *includes* executor handoff —
   making it an upper bound on the whisper call. If the true whisper wall was 5.2 s rather than 6.05
   s, the Tab is at parity with the Fold6 and every Tab row in §2.3 improves by ~15%. Medium still
   fails K1, but by less. One `bench_whisper_rtf_across_slices` run on the Tab replaces it with four
   slices and a p50/p95.
3. **The three medium derivations could all share one wrong assumption.** A, B and C converge on
   6.0-9.2 s, but A and B both scale the *same* Fold6 anchor and C uses a desktop-BLAS-measured
   ladder on a path that has no BLAS. If the real small→medium step on an un-GEMM'd ARM q5 path is
   materially *sub*-linear — the repo's own base→small pair scales at ×0.67-0.72 of arithmetic, which
   if it repeated would give medium F ≈ 4.7 s — medium clears K1. I rejected that transfer because
   the independent four-machine ladder measures medium/small at ×0.87 of arithmetic, not ×0.67. **One
   measurement of `extreme` on any device replaces the entire argument**, and there has never been
   one.
4. **The encoder share could be materially below 83-88%.** My fitted "fixed" term bundles the
   prompt-prefix decode with the encoder, so the true encoder share is *somewhat below* what I state.
   If the decoder were, say, 30% rather than 12-17%, turbo's 0.26× decoder starts to matter and the
   turbo refutation weakens (though its 2.08× encoder still dominates). `whisper-bench` measures the
   encoder alone and would separate them cleanly — it is vendored but disabled
   (`WHISPER_BUILD_EXAMPLES OFF`), and cross-compiling it for arm64-v8a needs **no app install at
   all**.
5. **The mid-range ×2-3 factor is my weakest number and I will not dress it up.** It has **no repo
   data and no external benchmark** behind it — no whisper.cpp measurement exists for any Snapdragon,
   any Dimensity, or any mid-range ARM SoC; the canonical benchmark thread (#89) is desktop-dominated
   with a single iPhone 13 Mini row running *base*. The *direction* is certain (fewer big cores, a
   barrier gated by the slowest thread, and the Helio G85 core-cluster bug where "throughput roughly
   halves" points the same way); the *size* is estimated. One cheap phone replaces it. Nothing in my
   verdict depends on it — strike it entirely and medium still fails K1 on both flagships.
6. **The previewer tax is inherited, not derived.** I verified the mechanism from source (per-node
   `ggml_barrier`, 2 threads held, only 4 X4 cores on the Tab) and it is certain; the ×1.10-1.25
   magnitude is unmeasured. Strike it entirely and medium still fails the 0.70 rule across its whole
   band on the target device — so it is not load-bearing, but K7 should replace it before any ruling.
7. **`O8` could be larger than I allow.** If `E7` shows the zh previewer really is ~5× better than
   the finalizer on one corpus with the traditional→simplified fold applied, the *defect* framing
   gets much stronger and the case for a bigger finalizer on multilingual devices strengthens
   independently of the WER table. I applied a haircut for the export mismatch and beam-vs-greedy
   (§3.4) because the source table applies it itself; if the haircut is smaller than I assumed, the
   value case improves. It still has to clear K0 through K4.
8. **A published Dimensity or Snapdragon 8 Gen 3 whisper.cpp benchmark could exist and none of us
   saw it.** Every lane's WebSearch budget was exhausted (200/200) before the first one started, so
   the external sweep was WebFetch-only against discussion #89 plus a few GitHub issue-search API
   queries. Recorded as an **absence of evidence, not evidence of absence.**
9. **Four things I did not check and am flagging rather than assuming:** whether the repack path
   doubles peak memory at load (if it does, §4.1's q8_0 figures are underestimates and the rescue is
   worse); whether `getauxval` actually reports i8mm on either SoC at runtime (one `ggml` logcat line
   at load closes it); whether the 128-mel `ultra` exclusion affects the plain CPU whisper.cpp path
   (I believe it is scoped to the NPU mel-donor path since whisper.cpp computes its own mel from the
   model's `n_mels` — **believed, not verified**, and it does not change the verdict); and the
   GFLOP-convention agreement between the two documents §0.2 compares (both cite the same
   2026-09-04 figures, so I believe it holds, but I did not re-derive either count).

---

## Sources

**Repo, READ this session at `main` `70adb96` / vc 94 / 4.5.2:**
`app/build.gradle.kts:49-54,80`;
`app/src/main/cpp/whisper_jni.cpp:31-49,64-73,116-119,164-180,834,846-863,893-915`;
`app/src/main/cpp/whisper.cpp/src/whisper.cpp:942,968-1013,1363-1403,1461,1509-1520,1922-1925,1939,2040-2052,2163-2189,3387-3428,3455-3545,6278-6281,7135-7175,7317-7419` and `log_mel_spectrogram`/`whisper_build_graph_conv`/`whisper_get_mel_segment_with_state`;
`ggml/CMakeLists.txt:113-115,152,197`; `ggml/src/ggml-cpu/CMakeLists.txt:574-578`;
`ggml/src/ggml-cpu/repack.cpp:4528-4800`; `ggml/src/ggml-cpu/arch/arm/quants.c:228,241,521,534,846,958,1076,1094,2262,2892`;
`ggml/src/ggml-cpu/llamafile/sgemm.cpp:3716-4019`; `ggml/src/ggml-alloc.c:22-47`; `ggml/src/ggml-backend.cpp:2262-2265`;
`src/CMakeLists.txt:112-120` + `src/parakeet.cpp` + `include/parakeet.h`;
`app/src/main/java/com/whispereverywhere/model/WhisperModel.kt:166-299,305,366-374,401-402,449-465`;
`.../model/ModelTierCopy.kt:22-67,85-92,100-129`; `.../model/ModelMigration.kt:33-48`;
`.../service/CommitCadencePolicy.kt:1-60,64-152,204-250,257-317`; `.../audio/BackpressureRule.kt:36-62`;
`.../service/SegmentCapPolicy.kt:53-57`;
`.../service/FloatingBubbleService.kt:258-268,837-877,915-949,1093,1167,4088,4379-4460,4689,4744,4863,4955-4966,5133-5185,5334-5391`;
`.../transcription/NativeComputeGate.kt:33-36`; `.../transcription/GpuPolicy.kt:140-155,176-250`;
`.../transcription/stream/StreamingPreviewTuning.kt:1-14`; `.../transcription/stream/StreamingPreviewEngine.kt:289,422-451`;
`.../transcription/stream/PreviewUnreachable.kt:29-45`; `.../transcription/LocalWhisperEngine.kt:708-725`;
`.../tts/TtsEngine.kt:37,931`; `.../model/WhisperModelManager.kt:228-238,306-321`;
`.../net/ConnectivityMonitor.kt:15-27`; `.../ui/screens/OnboardingModelScreen.kt:297,330,645,749-752,783-798` (the
refute-throughput lane cites the badge at `:643-646`; it is at **`:750`**, with `ramGated` at `:645`);
`.../util/WerMath.kt:27,67-72`; `.../audio/EndpointerTuning.kt:288-300`;
`app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt:25-85,87-130,163-240`;
`app/src/test/.../WhisperCatalogHelpersTest.kt:106,114,124,137,148,155,160,252`;
`app/src/test/.../ModelTierCopyTest.kt:26,32,42,56-66,68-76,86,91,101,140-160,558,575,608-665,670`;
`app/src/test/.../CommitCadencePolicyTest.kt:157,183,356,423,434,458,469,509`;
`app/src/test/.../ChooserSteerWiringPinTest.kt:33,218,614`;
`app/src/main/AndroidManifest.xml:10,127`; `app/proguard-rules.pro:85-95`;
`app/src/main/cpp/CMakeLists.txt:16-17`.

**In-repo measurements relied on, with device and duty stated in text:**
`docs/superpowers/research/2026-09-09-tab-turbo-gemma-scribe-gemini-live-research.md:52-61` — **the
Tab S10+ ggml `small-q5_1` datapoint (§0.1)**, Play build 86, 17.6 s chunk / 6.05 s, plus the SoC and
RAM facts;
`docs/measurements/2026-09-10-tab-turbo-e2e-gpu.md:0-8,203-262` — **E6, the Tab CPU turbo arms and
the thermal drift (§0.2, §0.3)**, and its own verdict;
`docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md:104-107,133` — **the measured anchor**
(Fold6, vc 77, per-floor wallMs and WER, the CPU/GPU caution);
`docs/superpowers/research/2026-09-04-turbo-cpu-gpu-research.md:20-30,79-125,138,150,180-241,329-440,446-502` —
the 19.22 ms/GFLOP calibration, the 706.6/2,313 GFLOP counts, `multi`'s in-session drift, the
experience-per-F table, the spike design, the draft honest copy, the `pro`-on-GPU contradiction;
`docs/measurements/2026-09-10-tab-sherpa-rung3.md:33,289-432` — the previewer's +169 MB and the
ten-minute run with its "~12% of one core" caveat;
`docs/superpowers/research/2026-09-10-startup-cutoff-investigation.md:225-255,525-540` — measured cold
loads per tier, "never captured — irrecoverable", the ~8× in-source memory error;
`docs/superpowers/research/2026-09-11-language-qualification-table.md:108,111,382,392,518-519` — the
zh/ko previewer figures, **`O8`**, **`E7`**, and the export / beam-vs-greedy caveats of §3.4;
`docs/measurements/2026-07-28-whisper-stt-bench-fold6.log` — the repo's only slice bench (eco/base
only, pre-512-floor);
`docs/superpowers/plans/2026-08-20-vad-endpointing.md:11144-11160` — the owner's 60 MB retirement.

**External, all read 2026-09-13:**
`https://arxiv.org/pdf/2212.04356` (Tables 9/10/11/13 and Appendix C's CER caveat; extracted locally
with `pdftotext` in both `-layout` and `-raw` modes);
`https://huggingface.co/openai/whisper-large-v3`; `.../whisper-large-v3-turbo` and its
`raw/main/config.json`; `https://github.com/openai/whisper`;
`https://github.com/openai/whisper/discussions/2363`;
`https://huggingface.co/distil-whisper/distil-large-v3`;
`https://github.com/ggml-org/whisper.cpp` + `models/README.md`;
`https://github.com/ggml-org/whisper.cpp/issues/2454` (open, result-free — whisper.cpp has no WER
suite); `.../discussions/89` and `.../issues/89` (the canonical bench thread — **no Android/ARM-mobile
entries**); `.../issues/3602` (the Helio G85 core-cluster bug);
`https://huggingface.co/ggerganov/whisper.cpp/tree/main` and byte-0-47 HTTP Range reads of the four
pinned tier files at commit `5359861c739e955e79d9a303bcbc70fb988958b1`;
`https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ComponentCallbacks2.java`
(fetched twice, both renderings agree on which levels carry the API-34 deprecation);
`.../services/core/java/com/android/server/am/ProcessList.java`;
`https://source.android.com/docs/core/perf/lmkd`;
`https://developer.android.com/about/versions/14/behavior-changes-all`.

**Lane reports and refutations, read in full:**
`cputier-throughput.md`, `cputier-accuracy.md`, `cputier-memory.md`, `cputier-appceilings.md`,
`cputier-refute-throughput.md`, `cputier-refute-worth.md`, plus `cpu-ladder-brief.md` and
`ladder-recompose-brief.md`, all under
`C:/Users/bastr/AppData/Local/Temp/claude/C--Users-bastr-OneDrive-Desktop-whisper-Everywhere/f0fe6e9c-f5ba-497d-8286-004288122e7c/scratchpad/review/`.
Facts inherited rather than re-verified are named as such at the point of use.
