# large-v3-turbo on the CPU/GPU path — research synthesis

Date: 2026-09-04. Repo state read: local `main` = `77b2498` (4.3.2 / versionCode 86,
`app/build.gradle.kts:53-54`); vendored whisper.cpp submodule `8772322f`, whose
`ggml/src/ggml-opencl/` is byte-identical to `714224a3`, the pin of 2026-07-17
(`git diff --stat 714224a3 HEAD -- ggml/src/ggml-opencl` is empty). Nothing in this document was
run on a device. Every number is either a repo measurement (cited) or a derivation labelled as
such; where a brief, a memory note or an analyst disagreed with the code, the code was opened and
it wins (§7 lists every such ruling).

Paths are relative to `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/`; `src/whisper.cpp`,
`ggml/…`, `kernels/…` are under `app/src/main/cpp/whisper.cpp/`. Inputs: four maps
(`turbo-map-{cpu,gpu,catalog,upstream}.md`), four analyses (`turbo-analysis-{cpufeas,gpuroot,
spike,product}.md`) and their four adversarial refutations, all in the session scratchpad.

---

## 1. THE ANSWER SO FAR

**The owner's premise — turbo is the fast large model — is true of its decoder and false of the
path that matters.** On the CPU streaming path the fixed per-commit cost is the *encoder*, and
turbo's encoder is large-v3's unchanged: 32 layers at d = 1280 vs small's 12 at 768
(`src/whisper.cpp:1533-1534`, `:1541-1546`). Counted at the production 512-frame floor it is
**706.6 GFLOP vs 113.3 — 6.24×** small's (§2.1). Scaled by the one calibrated CPU pass in the repo
(multi-q5_1 on the Fold6: 19.22 ms/GFLOP, fit to four measured floors within 3.5 %), that is
**F ≈ 9–17 s on a Fold6-class CPU and ≈ 10–22 s on the S23 Ultra** (central ~16 s), against
multi's measured 2.3 s (2.5–3.6 s warm in-session). The app's own cadence rule then needs a
20–33 s commit floor — above the 15 s wall cap that commits regardless of any floor — so under
continuous speech the single-thread, never-shed queue grows without bound. That is "unbearably
slow" by construction, not by tuning. The GPU cannot rescue it: the vendored Adreno backend has
two real, unfixed defects for multilingual vocabularies and has never run turbo or anything on an
Adreno 740.

| Quantity | Status | Number | Where |
|---|---|---|---|
| turbo-q5_0 F on any CPU, any device | **UNMEASURED** — zero `tier=ultra` lines in `docs/` or `app/` | model: 9–17 s Fold6, 10–22 s S23U | §2 |
| multi F on the Fold6 CPU at floor 512 | measured | 2.25 s fit / 2.3 s recorded; m = 0.037 | `docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md:104-107` |
| multi F, owner-measured in session, warm | measured | 2.5–3.6 s; speech-end-to-visible 2.8–5.8 s, typ. 3.6–4.3 s | `docs/superpowers/specs/2026-08-20-i-owner-acceptance.md:115-116` |
| npu-turbo F (the comparison the brief asks for) | measured | 1.89 s = encode 1,778.9 ms + 44.4 ms + 10.08 ms/token | `docs/superpowers/plans/2026-09-02-vad-hangover-retune.md:65-69` |
| ultra cadence row | placeholder | 8,000 ms, "UNMEASURED" | `app/src/main/java/com/whispereverywhere/service/CommitCadencePolicy.kt:47,150,230` |
| multi on Adreno 750 GPU | measured | **8–9× slower** than CPU, wer 0.000, all canaries pass | `docs/superpowers/specs/2026-08-19-gpu-ab-bench.md:108-117,130-176` |
| pro (.en) on Adreno 750 GPU | measured | 1.3–1.5× faster (8 s), ~2× (3 s) | same, `:152-167` |
| turbo on any GPU; anything on Adreno 740 | **UNMEASURED** | — | — |
| the multilingual GPU corruption | mechanism found in source, unfixed upstream | odd-vocab GEMV stride bug + `%4` GEMM tile spill + per-token 45.6 MB `d_te` copy | §3 |
| turbo's July "EMPTY" signature | **unexplained** by any traced path | needs one instrumented run | §3.2 |
| first Auto segment after a model load | code fact, unmeasured | detect encode runs at **1500 frames** → ≈ 3.9–4.3× F (applies to multi today) | §2.3 |
| `pro` on the owner's S23 | code fact, unverified on device | the code routes `.en` to the GPU on an Adreno 740; the cadence KDoc says pro is CPU-only | §2.6 |
| CPU `+i8mm` kernels for q5_0/q5_1 | code fact | **dead code** — both q5 dot kernels are dotprod-only, no repack tile | §2.5 |
| thermal sustain, RAM, S23U `totalMem` | UNMEASURED | RAM exact floor 624 MB, estimate 1.0–1.3 GB | §2.7, §6 |

**What would overturn this.** A measured `encode time` for turbo at 512 frames on the S23 Ultra of
≤ 5.2 s makes the 8 s placeholder honest; ≤ 3.9 s puts it in multi's class; ≥ 9 s makes it
unschedulable under continuous speech. The model predicts 10–22 s. The bench that produces that
number needs no catalog change and one phone session (§4). The cheapest fact of all costs nothing
and should come first: `adb logcat -s whisper_jni` for `init: use_gpu=` while dictating on `pro`
with the Play build (§2.6) — if the S23's incumbent is a GPU session paced by a CPU-derived 6 s
row, the "unbearably slow" the owner feels is a governor artefact no turbo tier addresses.

---

## 2. CPU: the cost model and the honest experience at each plausible F

### 2.1 What the engine bills, and the FLOP count

Per segment the JNI computes `audio_ctx = clamp(samples/320 + 64, 512, 1500)`
(`app/src/main/cpp/whisper_jni.cpp:897-908`; floor default 512 at `:64`). The floor binds up to
(512 − 64) × 320 = 143,360 samples = **8.96 s of post-VAD speech**, so every commit at or under
that pays the same encoder pass: `cost ≈ F + m·S` with F dominant. Attention runs the explicit
KQ/KQV path because `flash_attn = false` is pinned (`whisper_jni.cpp:118`); `n_threads =
min(hardware_concurrency, 4)` at one site, identical on CPU and GPU loads (`:857-863`); greedy
decode with `temperature_inc = 0.2f` (`:854`); the cross-attention K/V for every decoder layer are
projected once per encoder pass (`src/whisper.cpp:3506-3517`, inside the encode timer
`:2440-2452`).

Counted from the hparams (FLOP = 2·MAC; layer matmuls 24·d²·L per frame, conv, cross-KV
4·d²·n_text_layer per frame, attention 4·T²·d·L), re-derived independently by two agents to three
figures:

| frames T | speech that bills it | small (GFLOP) | turbo (GFLOP) | ratio |
|---|---|---|---|---|
| **512** | ≤ 8.96 s (production floor) | **113.3** | **706.6** | **6.24×** |
| 768 | the pre-2026-08-20 floor | 177.2 | 1,092 | 6.16× |
| 814 | 15 s (the wall cap) | 189.2 | 1,163.8 | 6.15× |
| 1500 | ≥ 28.7 s (30 s buffer cap) | 386.7 | 2,313 | 5.98× |

The naive 32·1280²/12·768² = 7.4× applies to the linear term only; the product analysis's 7.1×
omitted the cross-KV projection. **6.2× is the all-in figure.**

### 2.2 Calibration and the projection

The multi 1 s slice was recorded at four floors on the same device, same run (Fold6, vc77,
2026-08-20, CPU, warm): 1,316 / 1,822 / 2,323 / 3,674 ms at 256 / 384 / 512 / 768 frames
(`audio-ctx-floor-bench.md:114,109,104,100`). Least squares of wall ms on GFLOP: **slope
19.22 ms/GFLOP (52 GFLOP/s effective), intercept 227 ms**, residuals −3.5 / +0.3 / +3.5 / −1.1 %.
Cross-check on a different model and date: base-q5_1 at 768 = 1,234 ms measured
(`docs/measurements/2026-07-28-whisper-stt-bench-fold6.log:8`) vs 1,047 ms predicted (−15 %).

| Case | Fold6-class CPU, warm | S23 Ultra, warm |
|---|---|---|
| F_encode(512), language pinned | 706.6 × 19.22 + 0.23 = **13.8 s**; ±20 % cross-model band → 11–17 s; the repo's own base→small pair scales sub-linearly (arithmetic 4.18×, measured 2.8–3.0×), which if it repeats gives ~9.2 s → **honest band 9–17 s** | ×1.15–1.3 (X4/A720@3.1 vs X3/A715/A710@2.8 — **external judgement, not repo data**) → **10–22 s, central ~16 s** |
| 15 s wall-capped segment (814 frames, ×1.65) | 15–28 s | 17–36 s |
| the 2026-07-27 configuration (768 floor) | 1,092 × 19.22 = **21 s** | — |

The last row matters: the model reproduces the retirement verdict ("the larger tiers are the
source of the latency complaints", `docs/superpowers/plans/2026-07-27-model-catalog-trim.md:24`).
The only speed change ultra has not been measured under is the 768→512 floor move (`3638262`,
2026-08-20 17:37), worth ×0.65 (`audio-ctx-floor-bench.md:132-133`) — the dotprod/i8mm kernels
(`d8a4d5b`, 2026-07-16 18:02) *predate* the retirement (`cb519a7`, 2026-07-27 18:16), contrary to
the brief. ×0.65 is not the ×6 that would be needed.

**There is no cold-start penalty in the repo's data.** The CPU-feasibility analysis's "cold /
throttled 17–33 s" column rested on a ×1.33–1.52 spread between the cold gpu-ab CPU arms and the
warm floor bench; that spread is 768-vs-512 frames in disguise (§7). At matched frames the cold
arms read ×0.84–1.02 against the warm rows. The only in-repo warm-device spread is multi's
in-session drift, 2.3 → 2.5–3.6 s (×1.09–1.57).

### 2.3 The decoder is not the problem; the first Auto segment is a bigger one than anyone wrote

Per token: turbo 4 layers × 14·1280² + head 2·1280·51,866 = **316 MFLOP**, 109 MB of q5_0 weights
streamed; small 12 × 14·768² + 2·768·51,865 = 278 MFLOP, 104 MB (×1.14, ×1.04). Small measured
**11.4 ms/token** (fit on the floor-bench 512 rows with `tokens = 1.84 + 3.208·D` from
`vad-hangover-retune.md:68`; gpu-ab deltas give 4.6–16.6). Turbo estimate **5–26 ms/token,
central ~13** — the class of npu-turbo's measured 10.08 (`vad-hangover-retune.md:66`). Marginal m
while the floor binds ≈ 0.04; beyond the floor each extra second of speech adds ~69 GFLOP ≈
**1.3–1.9 s (RTF > 1 on the margin)**.

**The Auto-language cost, ruled from the code.** `whisper_full_with_state` runs
`whisper_lang_auto_detect_with_state` at `src/whisper.cpp:7095` (language "auto",
`whisper_jni.cpp:878-884` with `detect_language = false` so it does not return at `:7104-7106`);
that function encodes at `:4307` and decodes one token at `:4314`; the main loop encodes again at
`:7300` with no already-encoded guard. **But `state->exp_n_audio_ctx = params.audio_ctx` is the
only store of that field (`:7231`) and it executes 136 lines after the detect.** The detect
encode therefore reads the *previous* call's ctx (`:1982`, `:2383`), which is 0 on a fresh state
(`:921`) → `hparams.n_audio_ctx` = **1500 frames**. Nothing warms it: `prewarm()` only loads
(`LocalWhisperEngine.kt:627-641`); the GPU canary transcribe never runs for a multilingual tier on
the S23 (§3). So:

- first Auto commit after a model load: (2,313 + 707)/707 ≈ **4.3× F** (3.9× on the linear model)
  — turbo ≈ 55–70 s on the S23U by this model; **multi pays the same path today: ≈ 9.6 s on the
  Fold6 model, a number nobody has looked for**;
- later Auto sessions on the same loaded context: 2× F if the previous commit was floor-bound, up
  to 3.9× after a long stop-flush;
- `LanguagePin` latches after the first segment that produced text (`LanguagePin.kt:41-45`,
  reset at connect `:49-51`; `TranscriptionEngine.kt:226` `detectsPerUtterance = false`), so it
  is paid once per session, not per commit. The KDoc's "roughly HALF of multi's steady-state
  cost" (`TranscriptionEngine.kt:206-209`) describes the steady state and undercounts the first.

### 2.4 Cadence, duty, and what the user sees

The governor's rule: `F·N + m·S ≤ 0.70 × 60 s` per minute, and a tier keeps a floor only while
`F/floor + m ≤ 0.70` at saturation (`CommitCadencePolicy.kt:11-14, 29-31`). The 15 s wall cap
(`service/SegmentCapPolicy.kt:57`) commits on its own branch, independent of any floor
(`service/FloatingBubbleService.kt:2019-2023`, the `else if` after `endpointer.onFrame`). The
engine is one `Executors.newSingleThreadExecutor()` (`LocalWhisperEngine.kt:46-51`) with an
unbounded queue and no shed; the build-85 backpressure governor returns the fast row for every
tier but npu-turbo/npu (`CommitCadencePolicy.kt:269-276`), so it is inert on ultra.

Two conventions live in the repo and they do not agree: (a) the vad-endpointing plan's arithmetic
with a whole-segment rtf m = 0.45 reproduces the shipped multi row (floor ≈ 2.43·F → 5.6 s →
6,000); (b) the KDoc rule with the *marginal* m ≈ 0.04 gives floor = F/0.66 = 1.52·F. The CPU map
flagged that m = 0.45 is not a marginal; both are shown.

| F (s) | floor by (b) F/(0.70−m) | floor by (a) 2.43·F | continuous speech, cap regime: service 1.59·F + 0.6 | duty | queue |
|---|---|---|---|---|---|
| 2.3 (multi) | 3.5 | 5.6 → **6,000 shipped** | 4.3 s | 28 % | drains |
| 3 | 4.5 | 7.3 | 5.4 s | 36 % | drains |
| 4.5 | 6.8 | 10.9 | 7.8 s | 52 % | drains |
| 6 | 9.1 | 14.6 (= the cap) | 10.1 s | 68 % | drains until warm (+20 % throttle → 82 %) |
| 8 | 12.1 | 19.4 | 13.3 s | 89 % | marginal |
| **9.06** | 13.7 | 22 | **15.0 s** | **100 %** | **the stability line** |
| 12 | 18 | 29 | 19.7 s | 131 % | grows without bound |
| 16 (projection, central) | 24 | 39 | 26 s | 173 % | grows ~11 s per 15 s spoken |

The 8 s placeholder admits F ≤ 5.3 s (m = 0.04) or 4.8 s (m = 0.10); the 6 s multi row admits
F ≤ 4.0 / 3.6 s. **F_max ≈ 9 s is the hard product line for a live CPU tier**: above it, the
"(N in queue)" label (`FloatingBubbleService.kt:291`) counts up for the length of any
continuous-speech session and the stop tap drains for up to 300 s (`:525`). The projection lands
on the wrong side of it by 1–2.4×.

### 2.5 Levers that exist in the engine, bounded

| Lever | Bound | Basis |
|---|---|---|
| `n_threads` 4 → 5/6/8 | ≤ ×1.25, possibly < 1; no knob, the cap is a JNI literal | `whisper_jni.cpp:857-863` ("NET LOSS" rationale is a comment with no bench line behind it) |
| `audio_ctx` floor < 512 | not available: 384/256 FAIL on multi (wer 0.500 at 1 s, `audio-ctx-floor-bench.md:109,114`); turbo's 128-bin positional tolerance at 512 itself is untested (`whisper_jni.cpp:898-906`) | — |
| **the `+i8mm` half of `-DGGML_CPU_ARM_ARCH=armv8-a+dotprod+i8mm`** (`app/build.gradle.kts:80`) | **dead for q5_0/q5_1**: `ggml_vec_dot_q5_0_q8_0` (`ggml/src/ggml-cpu/arch/arm/quants.c:846`, `assert(nrc == 1)` `:855`, `#if __ARM_NEON` `:863`) and the q5_1 twin (`:958`) have no `__ARM_FEATURE_MATMUL_INT8` branch — that branch exists only for q4_0 (`:228,241`), q4_1 (`:521,534`), q8_0 (`:1081,1094`) and the K-quants; the repack GEMM tile table (`ggml/src/ggml-cpu/repack.cpp:4573-4720`) covers Q4_0/Q4_K/Q2_K/Q5_K/Q6_K/IQ4_NL/MXFP4/Q8_0, never Q5_0/Q5_1. Both shipped 190 MB tiers and turbo-q5_0 run on dotprod alone. (`ggml_cpu_has_matmul_int8()` is compile-time, `ggml-cpu.c:3744-3750`, not the getauxval dispatch the build comment at `:76-77` describes.) | |
| **a re-quantised turbo on a type with an i8mm tile** — q8_0 (published, 874,188,075 B, sha `317eb69c…`, same pinned HF commit), q4_0 (~470 MB, self-quantised), Q5_K | the only CPU lever with a non-zero chance; whisper.cpp at `8772322` builds its weight buft list from the CPU extra bufts (`src/whisper.cpp:1390-1401`, `:1461-1471`) so such a file WOULD be repacked at load; gain **×1.5–3 is a guess, unmeasured**; even ×3 leaves F ≈ 4–7 s on the S23U — still worse than multi, borderline for 8 s, and a new file/sha/copy | `repack.cpp:4578-4582` (q4_0 4x8 needs `matmul_int8`), `:4644-4648` (Q5_K), `:4699-4703` (q8_0) |

### 2.6 The `pro`-on-GPU question — the code contradicts the ruling

`GpuPolicy.decideUseGpuForLoad` skips the multilingual block for any file containing `.en`
(`transcription/GpuPolicy.kt:154`, `:191`), then admits any renderer matching
`Adreno \(TM\) (7\d\d|8\d\d|X\d)` (`:140`, `:231-235`) and arms the GPU trial (`:237-249`). The
S23 Ultra's `Adreno (TM) 740` matches. Its sole caller loads with `init(modelPath, true)`
(`TranscriptionEngine.kt:283-290`). The cadence KDoc says the opposite — "`pro` is CPU-ONLY BY
DESIGN … the GPU path is essentially dead" (`CommitCadencePolicy.kt:220-223`, owner ruling
2026-09-02) — and paces pro on multi's CPU-derived 6,000 ms row (`:229`). **The code routes; the
ruling does not.** If the S23's pro session is on the GPU, its fixed cost is in the class the
Fold6 measured at ~0.77 s (floor bench `:85`) and its slowness is the governor, not the model.
The one release-surviving line that says which: `LOGI("init: use_gpu=%d flash_attn=0")`
(`whisper_jni.cpp:119`; native, untouched by `proguard-rules.pro:82-92`). Whether the 740 passes
`ggml_opencl_probe_device` (`ggml-opencl.cpp:4025-4060`) is unmeasured.

### 2.7 RAM and thermal

whisper.cpp does not mmap (`grep mmap src/whisper.cpp` is empty; the loader reads into
`tensor->data`, `:1922-1925`). KV is f16 padded to 256 (`:968-1000`, `:3387-3428`): turbo self
10.5 + cross 31.5 + pad 7.9 = **50 MB** (small 80 MB). Compute buffers are allocated, not reserved,
at state init for the worst case (`whisper_sched_graph_init`, `:557-568`): the encoder at
`exp_n_audio_ctx = 0` → 1500 frames (`:1982`, `:2044`) and the decoder at 448 tokens (`:3524-3529`)
with logits for all tokens (`:2816-2819`, 448 × 51,866 × 4 B = 93 MB); ggml-alloc only grows
(`ggml-alloc.c:1008-1055`), so the 512-frame runtime graph never shrinks them. **Exact floor
624 MB (574 + 50); estimate 1.0–1.3 GB resident** vs ~0.5–0.7 GB for small, inside an overlay
foreground service. `minRamBytes = 7.0e9` (`model/WhisperModel.kt:235-236`) is advisory only
(§5.1). Four `ggml`-tag lines at load settle it (`src/whisper.cpp:1938, 3485, 3501, 3517, 3541`).

Thermal sustain is unmeasured for any CPU tier in this repo. What exists: multi's in-session
drift ×1.09–1.57; the gpu-ab protocol's 10–20 % thermal noise band (`gpu-ab-bench.md:79-84`);
npu-turbo's throttled F 2,140 vs 1,890 ms (+13 %, `CommitCadencePolicy.kt:89`). A tier at
> 100 % duty never idles between commits, which multi at ~30 % does; the direction is certain,
the magnitude is bench item 6 in §4.

### 2.8 The honest experience per outcome (S23 Ultra, language pinned, queue empty)

For the last sentence of a chunk: 0.35 s hangover (`EndpointerTuning.kt:87`) + F_eff + decode;
first sentence adds the rest of the chunk; chunk length follows the floor.

| F (s) | 8 s chunk: last / first sentence | 15 s cap chunk: last / first | first text: cap 4 s + F; Auto after load ≈ 4 s + 3.9·F | verdict |
|---|---|---|---|---|
| 2.3 (multi today) | 3.0 / 9.0 | 4.6 / 18.6 | 6.3 / 13 | the incumbent the owner calls slow |
| 3 | 3.7 / 9.7 | 5.7 / 18.7 | 7 / 16 | feels like multi with better words; row 8,000 by (a) |
| 4.5 | 5.2 / 11.2 | 8.1 / 21.1 | 8.5 / 22 | 1.5–2× slower than the incumbent; paragraph blocks |
| 6 | 6.7 / 12.7 | 10.5 / 23.5 | 10 / 27 | every commit a 15 s cap cut; stable until warm |
| 9 | 9.7 / 15.7 | 15 / 30 | 13 / 39 | the stability line; a queue under any sustained speech |
| 16 (projection) | 16.7 / 22.7 | 27 / 42 — unstable | 20 / 66 | not a live tier: a 60 s continuous session ends ~100 s of work behind |

**And on every CPU tier the session language is latched at segment 1** (§2.3), so the bilingual
one-sentence-per-chunk use case that motivates the 2 s npu-turbo cadence is structurally
unreachable on turbo-CPU regardless of F; un-latching it costs ≈ 1.9× per commit and still
decodes an 8 s+ chunk under one language token.

---

## 3. GPU: the corruption — ranked causes, the discriminating experiment, the fix candidates

### 3.0 The record, corrected

The corruption was observed on 2026-07-17 (`GpuPolicy.kt:142-153`; ban commit `66c5016`
22:32 that day), *before* 3.6.0 Workstream C, which built the canary to re-test an existing ban.
The 2026-08-20 A/B on the Fold6 recorded `GPU-VERDICT: BAN reason=slower` with `gpuVsCpuWer=0.000`
and every canary passing (`gpu-ab-bench.md:108-117`) and concluded the corruption "does NOT
reproduce". **That reading is wrong, and the reason is one decode parameter.** The July build ran
`params.temperature_inc = 0.0f` (`git show 79804e4:app/src/main/cpp/whisper_jni.cpp` line 195;
same in `66c5016`); `fe96e7a` (2026-07-18) restored the fallback to `0.2f`, which is what the
August A/B and today's `whisper_jni.cpp:854` run. At T = 0 whisper runs one decoder
(`src/whisper.cpp:7317-7325`); at T > 0 greedy runs `best_of = 5` decoders batched into one graph
(`:6277`, `:7711-7736`) → `ne1 = 5` → the GEMM kernel, not the GEMV. **August's correct text was
whisper's own retry repairing a broken T = 0 pass, and the retry is the 8–9× slowdown.** No
"correct-but-slow path" exists in the code: `use_adreno_kernels` (`ggml-opencl.cpp:5014-5024`),
`supports_op` for MUL_MAT (`:5237-5255`) and the q5 dispatch (`:13846-13873`) carry no parity
term. The two vocabularies: small multilingual **51,865** (odd, %4 = 1); large-v3-turbo
**51,866** (even, %4 = 2); `.en` 51,864 (%4 = 0). `d_te` is `[n_text_state, n_vocab]`
(`src/whisper.cpp:1799`), the only whisper weight whose dims are not all multiples of 4, and it
clears the Adreno threshold (1280 ≥ 512, 51,866 ≥ 512).

### 3.1 Ranked causes

| # | Cause | small-q5_1 (51,865) | turbo-q5_0 (51,866) | Evidence |
|---|---|---|---|---|
| **1** | **Odd-vocab GEMV stride bug.** `LINE_STRIDE_A = M / 2` in 32-bit units against unpadded M-element lines (`kernels/gemv_noshuffle_q5_0_f32.cl:211`; q5_1 twin `:212`; upload layout `ggml-opencl.cpp:6215-6222` → `kernels/transpose.cl:61-71`). For odd M every plane is misread: qs by the sub-line index (≤ 7 rows, resetting per block because `BLOCK_STRIDE_A = NSUBGROUPS·M` at `:212` is exact), qh by the byte-line index (≤ K/8−1), scales by the block index. | **Confirmed mechanism** — garbage at T = 0, masked by the batched-GEMM retry at T > 0 (the GEMM reads with element-unit stride `m`, `gemm_noshuffle_q5_0_f32.cl:36-45`) | n/a — even M is exact | `GpuPolicy.kt:146` "garbage tokens" is still true at T = 0 |
| **2** | **`%4` GEMM tile spill.** Grid `CEIL_DIV(ne01, 4)` (`ggml-opencl.cpp:12624`); each work-item stores four rows with a *whole-buffer* guard `idx+3 < m·n_no_padding` (`gemm_noshuffle_q5_0_f32.cl:30, 98-130`). For m = 51,866 the last tile's rows 51,866/51,867 land on **rows 0/1 of the next column**. Re-derived for the 3-token prompt: s = 1 stores at idx 103,730 (103,733 < 155,598) → **rows 0/1 of the last, sampled column** (`i_batch = prompt.size()−1`, `src/whisper.cpp:7446`, `:6458`), racing that column's own tile; the last column's rows 51,864/51,865 (timestamps ≥ token_beg+1499) go unwritten and are −INF'd by `max_initial_ts` (`:6580-6586`). Row 1 = `"` is −INF'd by `suppress_nst` (`:6538-6546`); **row 0 = `!` is a live logit.** | present (3 spill rows) on top of #1 | **not excluded, not shown**: a scrambled `!` logit in the sampled column does not produce emptiness by any traced path | the gpuroot analysis's "never reaches a sampled cell" was refuted (§7) |
| **3** | **`GET_ROWS(d_te)` unsupported → a 45.6 MB GPU→CPU copy per decoder graph.** OpenCL `supports_op` admits GET_ROWS only for F32/F16 (`ggml-opencl.cpp:5068-5082`; `GGML_OPENCL_SOA_Q` is unconditional, `ggml-opencl/CMakeLists.txt:17`); whisper's `weight_buft_supported` short-circuits `op_supported = true` for any GPU device (`src/whisper.cpp:1406-1413`) so `d_te` is placed in the OpenCL buffer anyway; the scheduler assigns the node to the CPU (`ggml-backend.cpp:845-863`, `:1238-1241`), makes the weight a split input (`:1273-1287`) and copies it on **every** compute (`:1551-1575`; OpenCL `cpy_tensor_async = NULL` at `ggml-opencl.cpp:5390` → blocking `ggml_backend_tensor_copy` `:500-518` → `tensor_get` → three transposes + a `local={1,1,1}` restore kernel + blocking read, `ggml-opencl.cpp:7457-7496`). One decoder graph per token (`src/whisper.cpp:7738`). Bytes: 1280×51,866/32 × 22 = **45,642,080** (turbo), 29,874,240 (small). | a per-token COST on every GPU tier incl. pro; not a corruption cause | same | measured shadow: at floor 768 the 3 s→8 s deltas are decode-only; pro-GPU **155–184 ms per audio-second vs 12–53 on CPU** (`gpu-ab-bench.md:152-176`) on the model whose GPU encoder is ~2× faster |
| 4 | q5_0 kernel family itself (turbo is the only q5_0 model ever put on this path; `extreme` has no GPU line) | n/a | **open**; convert/restore bit plumbing inspected consistent (`kernels/cvt.cl:587-636`) | untested on device |
| 5 | Adreno image KQ/KQV M-tile OOB (upstream llama.cpp PR #27632 class, merged 2026-09-02, **not vendored**) — grid `{64,(M+63)/64,…}` with no M-tile check (`ggml-opencl.cpp:13819-13843`, `:12046`); M = audio_ctx | bites only ≥ 8.96 s of post-VAD speech (664 % 64 = 24; 814 % 64 = 46) on any GPU tier incl. pro today; not July's cause (11 s jfk → 614 → floored to 768, aligned) | same | unmeasured |
| 6 | The canary (`GpuCanaryPolicy.kt`) | blind, not a cause: corruption-only, no speed term; passes a T = 0-broken kernel because the retry repairs it | would FAIL on empty output (`:56`) | `gpu-ab-bench.md:193-197` |
| 7 | q8_0 | **hard abort at load**: `enable_adreno_trans_weight` true (66,388,480 < 2²⁷, `:5032-5039`) → `GGML_ASSERT(M % 4 == 0)` at `ggml-opencl.cpp:6574` — this *is* whisper.cpp issue #3708, open, no fix | same (51,866 % 4 = 2) | — |

The GEMV store overrun (`gemv_noshuffle_q5_0_f32.cl:288` unguarded `vstore2`, 102–104 floats past
the row for all three vocabs) is UB, not a discriminator; upstream master has since guarded it.

### 3.2 Turbo's EMPTY signature is unexplained; one run classifies it

On the vendored code turbo at T = 0 runs an exact single-token GEMV after a prompt pass whose
only live corrupted cell is `!` in the sampled column. Nothing traced yields emptiness. The
instrumented run below reads it directly: `whisper_full failed` (`LOGE`, `whisper_jni.cpp:947-949`)
= a decode failure (−6/−7), not corruption; text + `Δfallbacks ≥ 1` = garbage repaired by the
retry (#1/#2 class); empty + `0 p` = a first-step EOT / `result_len == 0` skip
(`src/whisper.cpp:7651-7657`).

### 3.3 The discriminating experiment — one JNI line pair, survives release

Around `whisper_full` (`whisper_jni.cpp:943`): `whisper_reset_timings(ctx)` before,
`whisper_print_timings(ctx)` after (`include/whisper.h:523-524`). It prints `fallbacks = N p / M h`,
`encode time … / n runs`, `decode … per run`, `batchd`, `prompt` (`src/whisper.cpp:4525-4547`) at
`WHISPER_LOG_INFO`, which the installed forwarder (`whisper_jni.cpp:31-36`, both `ggml_log_set`
and `whisper_log_set` at `:48-49`) puts in logcat under tag `ggml` in every build type.
**Caveat ruled from the code:** `whisper_reset_timings` (`:4549-4563`) zeroes the timers and run
counts but not `n_fail_p/n_fail_h` (`:847-848`, incremented `:7838/:7812`) — they are cumulative
per context; each bench arm inits a fresh context (`WhisperBenchTest.kt:481`) so per-arm counts
are clean, but a parser must print deltas across consecutive calls.

Then `bench_gpu_vs_cpu_ab` (`WhisperBenchTest.kt:387-467`, forced arms bypass GpuPolicy) on multi:
**#1 predicts** GPU arm `Δp ≥ 1`, CPU `0 p`; **#3 predicts** `0/0` on both and a uniformly larger
decode ms/token on GPU. With the turbo file on disk the same run classifies EMPTY (§3.2).

### 3.4 Fix candidates, with the lines

| Candidate | The change | Correctness | Speed | Risk / verdict |
|---|---|---|---|---|
| **E — `d_te` on the CPU (recommended first)** | `src/whisper.cpp:1409-1413`: for `op == GGML_OP_GET_ROWS` (the op `d_te` is mapped to, `src/whisper-arch.h:113-117`) do not take the GPU short-circuit, so the existing probe (`:1414-1456`, `ggml_get_rows(w, I32[8])` → `ggml_backend_dev_supports_op`) runs for GPU devices; OpenCL answers false → `select_weight_buft` (`:1461-1470`) advances to the CPU bufts. Both GET_ROWS and the logits MUL_MAT then stay on the CPU: `ggml-backend.cpp:916-928` (op follows the weight buffer; `op_offload` is true from `:558` but `ggml_backend_offload_op` → `dev->iface.offload_op` = `NULL`, `ggml-opencl.cpp:8436`), and OpenCL's `supports_buft` accepts only its own buft (`:8409-8414`). **Implementation caveat:** the else-branch default is `op_supported = false` (`:1450-1453`) — bypass the short-circuit for GET_ROWS only, or every ADD/MUL/IM2COL-mapped weight moves to the CPU. | **Correct by construction** for every vocab: no Adreno kernel touches the vocab dimension; makes q8_0 `d_te` loadable | removes the 45.6 MB/token copy; adds a CPU head GEMV (66.4 MMAC, 45.6 MB read ≈ **5–6 ms/token** at the ~8.5 GB/s the CPU decoder demonstrably sustains — derived, unmeasured); encoder unchanged | fork-local patch to carry across merges; same shape as llama.cpp PR #26440 (merged 2026-08-21, "keep the vocab-scale K-quant lm_head on the CPU for Adreno A7X"). Re-bench pro too — it should gain |
| **D — `%4` guard** | `ggml-opencl.cpp:5022`: `… && tensor->ne[1] % 4 == 0 &&` — one predicate gates both the upload repack (`:6198`, `:6349`, `:6568`) and the dispatch (`:13846`). Pair with `ne01 % 64 == 0` on the KQ/KQV branches (`:13825-13842`) as the local equivalent of PR #27632 | `d_te` falls to the row-guarded generic kernels (`kernels/mul_mm_q5_0_f32_l4_lm.cl:82,123,168`); every other whisper weight (768/1280/3072/5120) keeps the Adreno path; q8_0 becomes loadable | generic vs Adreno on one matmul per token: unmeasured; **does not remove the copy** (read-back becomes the plain restore, `:7503-7519`, still `local={1,1,1}`) | minimal, upstream-shaped, safe for `.en`. Belt-and-braces with E |
| **A — `-DGGML_OPENCL_USE_ADRENO_KERNELS=OFF`** | `app/build.gradle.kts` after `:91`: `arguments += "-DGGML_OPENCL_USE_ADRENO_KERNELS=OFF"` (default ON at `ggml/CMakeLists.txt:264`, directory-scoped define `ggml-opencl/CMakeLists.txt:20-23`; the app never passes it, `:88-97`) | shape-safe everywhere; compiles out `:13819-13906`, the noshuffle repacks, the `M%4` asserts, the KQ/KQV image kernel **and the non-Adreno rejection at `:4037-4042`** | gives up the kernels behind pro's 1.3–2× (`gpu-ab-bench.md:152-167`); keeps the copy; no measurement exists for such a build on any device | **diagnostic only**: opens OpenCL to Mali behind nothing but `GpuPolicy.kt:140`. Must never ship |
| B1 — q8_0 turbo (874 MB) | file only | aborts at load on A-default (#3708); loads under D/A; copy grows to 70.5 MB | — | no (GPU); a CPU arm only, §2.5 |
| B2 — f16 turbo (1.62 GB) | file only | GET_ROWS F16 is supported → no copy; generic row-guarded head | 2.7× the encoder bytes | no |
| B3 — q5_0 body + f16 `d_te` (+87 MB) | fork `quantize` skip, new sha | same as B2 for `d_te` | 132.8 MB f16 read per token on GPU | E does it with no new artefact. no |
| C — pad `n_vocab` to 51,868 | model + parser (`:1544` keys v3 on `n_vocab == 51866`) + tokenizer asset | fixes #2 only | none | no |
| F — bump `ggml-opencl` to upstream master | re-apply fork-local `714224a3` | brings PR #27632, the `cl_program` cache (#26050), q5_K image fallback; **does not fix #1/#2** (`LINE_STRIDE_A = M/2` and the whole-buffer guard are unchanged at master; #3708 open; #26477 is K-quant, A7X excluded; GET_ROWS still F32/F16-only) | none certain | later |

Order: E (+D) → re-run `bench_gpu_vs_cpu_ab` on pro to prove the per-token drop with unchanged
WER → then the turbo number. **Speed, honestly:** FLOP-scaling the Fold6 GPU encoder (pro@768,
~80 GMAC/s on small.en shapes) to turbo@512 (343.6 GMAC) gives **~4–6 s per commit on an Adreno
750** — a band whose transfer from 12-layer/768-wide to 32-layer/1280-wide shapes nothing in the
repo supports — vs ~14–16 s on that CPU; both miss the 2,000 ms cadence the NPU meets at 1.78 s,
and the GPU figure assumes E is applied. Adreno 740: no number. Mali (Tab S10+): refused twice
(`GpuPolicy.kt:140`; `ggml-opencl.cpp:4037-4042`) and can never have a GPU flavour.

---

## 4. THE SPIKE — one S23 Ultra session

### 4.0 Before touching the phone (free)

`adb logcat -s whisper_jni` while dictating on `pro` with the **Play build** → `init: use_gpu=`
(§2.6). No reinstall. If `use_gpu=1`, record it: the incumbent is a GPU session paced by a CPU
row, and that reframes "unbearably slow" before any turbo number exists.

### 4.1 Builds (one branch `spike/turbo-cpu-gpu-s23` off `77b2498`; versionCode stays 86; no catalog/chooser/GpuPolicy/cadence edits)

Four bench/diag-only source changes:

1. `app/build.gradle.kts`, inside `externalNativeBuild.cmake.arguments` after `:91`:
   ```kotlin
   val adreno = (project.findProperty("weAdrenoKernels") as String?)?.uppercase() ?: "ON"
   arguments += "-DGGML_OPENCL_USE_ADRENO_KERNELS=$adreno"
   // defaultConfig: buildConfigField("String", "SPIKE_ADRENO_KERNELS", "\"$adreno\"")
   ```
   `ON` re-states the define ggml already applies — `main`'s binary is unchanged by the property.
2. `whisper_jni.cpp` around `:943`: `whisper_reset_timings(ctx)` / `whisper_full` /
   `whisper_print_timings(ctx)` (§3.3; parser prints Δp/Δh).
3. `whisper_jni.cpp`: `static std::atomic<int> g_n_threads_override{0}` + a
   `setThreadOverride(n)` JNI mirroring `setAudioCtxFloor` (`:64-75`); at `:863`
   `params.n_threads = ov > 0 ? ov : ((cores < 4) ? cores : 4)`. whisper.cpp honours it per call
   (`src/whisper.cpp:190-205`). Production never calls it.
4. `app/src/androidTest/…/whisper/TurboSpikeBenchTest.kt` driven by `-e` args
   (`InstrumentationRegistry.getArguments()`, the precedent at `WhisperNativeSmokeTest.kt:55-58,70`):
   `models`, `arms`, `threads` (4,5,6,8), `driftSec` (300), `select`, `gpuExperiment`.

| Build | Property | Binary difference | Identity in `logcat -s ggml` |
|---|---|---|---|
| **A** | ON (default) | = `main` + changes 2–4 | `ggml_opencl: using kernels optimized for Adreno` (`ggml-opencl.cpp:3980`) present |
| **B** | OFF | `libggml-opencl.so` only; CPU path same source/flags — **hash the four non-OpenCL `.so`s from A and B and record equality as a measurement** | that line absent |
| **D** (optional, decided on the spot) | ON + working-tree patch at `ggml-opencl.cpp:5022` (+ `:13825-13842`) | Adreno kernels everywhere except `d_te` | line present + dirty submodule logged |

```
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon                       # A
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon -PweAdrenoKernels=OFF # B
```
Debug is signed with the upload key on this machine (`build.gradle.kts:133-140`), same
`applicationId` (`:51`): it cannot go over the Play copy (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`),
and debug→debug `adb install -r` preserves app data. Never `:app:installDebug` /
`connectedAndroidTest` (they wipe models).

### 4.2 Files (pinned HF commit `5359861c…`, `WhisperModel.kt:107-108`; LFS pointers re-fetched 2026-09-04)

| file | bytes | sha256 |
|---|---|---|
| `ggml-large-v3-turbo-q5_0.bin` | 574,041,195 | `394221709c…ffa7e2` (`WhisperModel.kt:122`) |
| `ggml-large-v3-turbo-q8_0.bin` | 874,188,075 | `317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1` |
| `ggml-small-q5_1.bin` | 190,085,487 | `ae85e4a9…120411bb` (`:121`) |

`adb push <file> /data/local/tmp/` → `adb shell run-as com.whispereverywhere sh -c 'mkdir -p
files/models && cp /data/local/tmp/<file> files/models/'` → `run-as … sha256sum` → delete the tmp
copy. Peak on-device ≈ 2.5 GB. The first two names match the catalog's `fileName`s, so the three
existing benches pick them up with **zero source changes** (they filter `WhisperCatalog.entries`
by file present + size within 5 %, `WhisperBenchTest.kt:96-99`, `:391-394`;
`sizeWithinTolerance` `WhisperModel.kt:471-475`). q8_0 has no catalog entry and is reachable only
through the spike test's `-e models` (or, for a one-shot correctness smoke, `WhisperNativeSmokeTest`
with `-e modelPath`).

### 4.3 Run order (≈ 90–120 min; every arm's line carries `dumpsys battery` temperature; an arm that starts > 3 °C above the session's first reading waits)

| # | Step | Records |
|---|---|---|
| 1 | Device identity | `getprop ro.soc.model ro.soc.manufacturer`; `/proc/meminfo MemTotal` (the S23U `totalMem` against the 7.0e9 gate is unmeasured — this is the first) |
| 2 | **Owner backs up every BYOK key** (they live in Keystore-backed `SecureStore`, `PreferencesManager.kt:100-110`, and die with the uninstall); `adb uninstall com.whispereverywhere` (only the 190 MB pro model is lost) | — |
| 3 | `adb install -r A-app.apk` + `A-test.apk`; open once to create `filesDir`. **Check `selectedModelId` after every install** — `allowBackup="true"` with `whisper_everywhere_prefs.xml` allow-listed (`AndroidManifest.xml:52-54`) may restore non-credential prefs | — |
| 4 | Push + place + sha256sum (§4.2) | — |
| 5 | **Zero-code baseline, cold:** `am instrument … WhisperBenchTest#bench_whisper_rtf_across_slices` | `BENCH stt tier=<multi|ultra> slice=<1,3,8,15>s wallMs= rtf=` — **the first turbo-CPU numbers in the project's history**, and multi's on the same device (replaces the external ×1.15–1.3); the `ggml` timings block under each gives the encode/decode split |
| 6 | `bench_audio_ctx_floor_ab` | `BENCH audioctx tier=ultra floor=512 … wer=` — whether the 128-bin model tolerates the floor the 80-bin tiers were cleared on (`whisper_jni.cpp:898-906`); the 384/256 rows are the cliff check. A 1500-reference arm (`setAudioCtxFloor(1500)` reference, 512 candidate) is the honest accuracy check and is a small harness edit |
| 7 | Spike test, build A, CPU arms (`-e arms cpu`): per model — load (`loadMs`, `pssKb`, the `OpenCL driver:` line names the Adreno compiler version that flips the 512/128 threshold, `ggml-opencl.cpp:5017-5021`), canary, slices 1/3/8/15 s with VAD on and `lang="en"`, **Auto arm on a fresh context** (`lang=null` → `null` → `"en"` on the 8 s slice: the first auto's `encode time … / 2 runs` carries the 1500-frame detect, §2.3), threads sweep 4/5/6/8 (8 s × 3, median), 300 s drift loop at the best n (first-5 vs last-5 encode ms, the NPU spike's own metric), `pssKb` at end and after free | `werVsMultiCpu` on the 8 s slice; Δp/Δh; `dumpsys meminfo` once during the drift |
| 8 | Cool 5 min. Build A, GPU arms for multi and q5_0 (`-e arms gpu -e driftSec 0`). The q8_0 GPU arm, if wanted on record, **alone and last** — predicted `GGML_ASSERT(M % 4 == 0)` abort at load. If OpenCL init fails on the 740 the "gpu" arm silently runs on CPU (bench KDoc `WhisperBenchTest.kt:361-362`): equal `loadMs`/rtf between arms is the tell | `werVsOwnCpu`, `canary`, Δp/Δh — a GPU arm with wer 0.000 and Δp ≥ 1 is "correct because it retried" |
| 9 | `adb install -r B-app.apk` + `B-test.apk` (models survive). Cool 5 min. GPU arms on B for all three; one CPU 8 s sanity rep (must match A within noise) | same |
| 9a | **Run D only if** A-GPU fails correctness, B-GPU passes it, and `F_g(B) ≤ 2.0 × F_c` | GPU arms only |
| 9b | **Five sentences live** (`-e select ultra`; the tier is selectable with no catalog change, `WhisperModelManager.kt:82-85`; the 8,000 ms row and the migration card are in force): Settings language = English, dictate the 4.3.1 acceptance set one per session (`docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md:55-73`), write down what each typed; then language = Auto, sentences (1) and (5) again. Capture `logcat -s WE-DIAG ggml whisper_jni`: `segment-timing: … transcribe= ctxFrames=` (Kotlin, survives in debug), `VAD: … wallMs=`, the timings per commit. Optional `-e gpuExperiment true` → production canary → GPU session feel — only on a build whose GPU arm passed the gate | in-session F; the first-Auto-commit cost for real |
| 10 | Teardown (§4.5) | — |

### 4.4 Decision table

**Correctness gate first (no speed number counts without it):** canary pass on the arm;
`werVsMultiCpu ≤ 0.10` on the 8 s slice (`WerMath.FLOOR_WER_GATE`, `util/WerMath.kt:27`); Δp/Δh
= 0 on jfk and canary; GPU arms additionally `werVsOwnCpu ≤ 0.10`; the CPU winner types the five
sentences correctly by eye (one substitution allowed; the comma-separated digits intact).

Thresholds from `F/floor + m ≤ 0.70` (§2.4): fits the 6,000 row iff `F ≤ 6·(0.70−m)` = 3.6–3.9 s;
clears the 8,000 placeholder iff `F ≤ 8·(0.70−m)` = 4.8–5.2 s.

| Verdict | Conditions | What follows |
|---|---|---|
| **CPU-tier** | gate on CPU; `F_c/(0.70−m_c) ≤ 8.0 s` at the best thread count; drift ≤ 25 % over 300 s (25–50 %: use the last-5 F); PSS at load ≤ 40 % of `MemTotal` | re-offer per §5 with the row **re-derived from F_c**, never the placeholder; RAM gate made hard; no steer; no speed claim. 6 s → a straight upgrade path; 7–8 s → offer, badged slower than multi, and say so |
| **GPU-tier** | GPU gate on that build; `F_g ≤ 0.80 × F_c` **and** GPU ms/token ≤ CPU ms/token on two cold runs (the gpu-ab ALLOW-DEFAULT rule, `gpu-ab-bench.md:201-204`); Δp/Δh = 0 on the GPU arm | only with a **speed term added to the canary** (`docs/superpowers/research/2026-08-27-npu-whisper-turbo-research.md:74`), and only on the build that produced it: B = shipping OFF fleet-wide and re-measuring pro's forfeited `.en` win; D/E = a fork commit. A follow-up release either way |
| **Stay-retired** | CPU gate fails, **or** `F_c/(0.70−m_c) > 8.0 s` with drift accounted, and no GPU row passes | the retirement stands with a **recorded number** for the first time; the KDoc at `CommitCadencePolicy.kt:47` gets the measured value |
| q8_0 over q5_0 (CPU) | `F_q8 ≤ 0.85 × F_q5` or q8_0 corrects a five-sentence error, with PSS ≤ 40 % MemTotal | otherwise q5_0 (+300 MB and ×1.52 RAM buy nothing) |
| Threads | lowest median beats 4 by ≥ 10 % and its drift is not worse by > 10 points | a `min(cores, n)` proposal for 8 Gen 2-class parts only, until the Fold6 re-measures |

Two readings need the owner, not the table: a CPU tier that clears 8 s but not 6 s is *offering
something slower than the incumbent*; and if the S23's own multi F lands far above the Fold6's
2.3 s, the complaint is the device and turbo's *ratio* to multi is the number to read.

### 4.5 Teardown and Play impact

`adb uninstall com.whispereverywhere` (removes the debug build, 1.64 GB of models, `filesDir/vad`,
every pref including any GPU latch step 9b wrote); reinstall from Play; onboarding; re-download
pro; re-enter the BYOK keys; `adb shell ls /data/local/tmp` shows none of the three files. Play:
nothing — no AAB, no versionCode change, no catalog/chooser/cadence/GpuPolicy edit. The captures
go to `docs/measurements/2026-09-xx-turbo-spike-s23.log`. **Build B's define must never ship by
accident** (it removes ggml's non-Adreno rejection).

---

## 5. THE TIER IF IT WORKS

Premise: the spike returned a CPU-tier verdict (F_c measured, gate passed). Everything here is a
chooser-and-copy change; the download path needs nothing.

### 5.1 Gating — two filters that do not exist yet

`pickableFor` filters only `!it.retired && (!it.gated || it.id in offeredGatedIds)` and then
narrows to `ONE_TIER_ID` (`model/WhisperModel.kt:368-375`): no RAM, no family. So:

- `retired = false` alone puts ultra in the device-independent `pickable` and offers a 574 MB
  multilingual model to the NPU fleet — the opposite of the ask. The exclusion must live in
  `pickableFor` and key on **`npuSocFamily == null`** (`WhisperEverywhereApp.kt:98-100`, the
  memoised pure `NpuGate.familyFor`, `npu/NpuGate.kt:79-84`) — **not** on the gate's set, because
  `offeredNpuTierIds()` is empty on a capable device with nothing installed
  (`WhisperEverywhereApp.kt:160-166`). The S23 Ultra resolves no family and is named in
  `CPU_BY_CENSUS` (`npu/NpuFleetCensus.kt:353-357`); the Tab S10+ fails the manufacturer set at
  `NpuGate.kt:82`. One edge to decide explicitly: a census device whose QNN probe fails is
  non-NPU in practice yet excluded under this rule.
- "Enough RAM" exists only as a badge: `isRecommendedForDevice` (`WhisperModel.kt:464-465`) is
  consumed by one surface (`OnboardingModelScreen.kt:297` → a Warning note, "You can still pick
  it") and the onboarding step reads no RAM at all (`OnboardingFlowScreen.kt:592-641`). A hard
  gate means threading `totalMem` into `pickableFor` from both call sites; `orderedForLanguageTagFor`
  must remain a permutation of `pickableFor`'s output. The 7.0e9 threshold has never been checked
  against a real S23U reading.
- Flags: `retired = false` **and** `unsupported = false` together —
  `every_unsupported_tier_is_also_retired` (`WhisperCatalogHelpersTest.kt:148-153`) goes red on a
  half change, correctly, because `unsupported` is what raises the migration card. Leave the
  `model.id != "ultra"` mel-donor clause alone (`WhisperModel.kt:401-402`): the 128-bin exclusion
  from the NPU fallback is a separate, still-true fact. Do not un-retire `extreme`.

### 5.2 Copy — what the tests demand and what is true

Un-retiring makes `ModelTierCopyTest` (census = `entries.filter { !it.retired }`, `:24`) demand
an entry: `forId` non-null; a `"574 MB"` badge (`:42-54`, derived from `approxBytes`); `"90+
languages"`; a position word from {fastest, fast, slower, accuracy} (`:56-66`, `:674`); no
word-anchored mention of a still-retired id; and `retired_and_unknown_tiers_have_no_copy` drops its
ultra line (`:94`). True today with no bench: same model family as the AI-chip tier
(`NpuModelSpec.kt:338`); 90+ languages; 574 MB; slower than Multilingual (small) by architecture.
Not defensible: any number of seconds (house rule, `ModelTierCopy.kt:49-52`) and "same accuracy as
the NPU tier" — the NPU tier is w8a16 with a hand-written decode loop, ultra is ggml q5_0 with
whisper.cpp's greedy + temperature ladder, and no WER exists for either; the owner's A/B was
turbo-w8a16 vs small-w8a16 on the Hexagon. Multi's headline is pinned to exactly `"Best
multilingual accuracy"` (`ModelTierCopyTest.kt:86-89`); offering a card positioned on quality
beside it is a **product-truth decision**, not a red test — the pin stays green either way.

```
"ultra" to TierCopy(
    headline = "Best multilingual quality, slower",
    badges = listOf("90+ languages", "574 MB"),
    body = "The large-v3-turbo model the AI-chip tier runs, on this phone's processor. " +
        "Each line takes longer to appear than with Multilingual (small) — the reason to " +
        "pick it is the words, not the speed. Best downloaded on Wi-Fi.",
)
```
No steer: `steerIdForLanguageTagFor` keeps `multi` for non-English locales
(`ModelTierCopy.kt:121-129`); the L9 precedent promoted turbo only after an on-device A/B. The
Wi-Fi line is earned: the download allows metered and roaming silently
(`WhisperModelManager.kt:343-344`), needs ~631 MB free on two filesystems (`:309-319`), can peak
near 1.15 GB during a cross-filesystem copy (`:405-410`), and leaves 764 MB of models resident
(the 190 MB tier is never deleted on switch).

### 5.3 Cadence

The row is re-derived from the measured F under the stated rule (`CommitCadencePolicy.kt:29-31`)
or is an owner ruling with the arithmetic written down, the way `MIN_COMMIT_INTERVAL_TURBO_MS` is
(`:66-131`). Never the placeholder: an 8 s floor between commits *is* the unbearably slow
experience. `EndpointerTuning.kt:290` and `EndpointerTuningTest.kt:397-401` carry the tier list as
documentation and take the same edit. A slow row is pointless above 15,000 ms (the cap fires
first).

### 5.4 Migration

`ModelMigration.decide` fires only on `unsupported` (`model/ModelMigration.kt:48`); the card's
"**is much faster** and works well for everyday dictation" (`ui/screens/SettingsScreen.kt:246-251`)
is true only while ultra is the slow tier (`WhisperModel.kt:62-70`: "extreme/ultra keep both
flags: their targets really are faster"). With `unsupported = false` the card stops for all three
cohorts — already migrated (on multi, file deleted by `SwapAndDelete`; nothing runs in reverse and
must not), never-opened-Settings (still on ultra at the 8,000 row — the only field evidence of
ultra-on-CPU this app has, none collected), and offline-when-they-looked. Edit the KDoc sentence
to say *extreme* alone and `UnsupportedTierGatePinTest` with it.

### 5.5 What streaming feels like, and whether the finalizer shape fits CPU better

The table in §2.8 is the experience per F. At F = 3 it is today's multi with better words (3–4
sentences per block at a brisk pace, 36 % duty under continuous speech); at F = 4.5–6 it is
1.5–2× slower than the incumbent the owner already calls unbearable (5–7 s to the last sentence of
a block, 11–13 s to its first, blocks of 5–8 sentences, every commit a 15 s cap cut at F = 6); at
F ≥ 9 it is not a live tier. On every outcome the session language is latched at segment 1 and the
first Auto commit after a load pays ~3.9× F (§2.3).

**The 25 s finalizer shape (multi fast chunks + turbo window finals) does not fit CPU better —
it fits worse.** Per 25 s: the fast path costs 4.17 × 2.52 = 10.5 s (42 %); a turbo final over the
window costs 2.57·F + 1.0 (all speech, ctx 1,314) or 1.59·F + 0.6 (60 % speech, ctx 814); both
engines serialise on one executor and the process-global `NativeComputeGate`
(`TranscriptionEngine.kt:279-283`), so a final **stalls the fast path for its whole length**.

| F (s) | final, all speech | final, 60 % | total duty (all / 60 %) | fast-path stall every 25 s |
|---|---|---|---|---|
| 3 | 8.7 s (35 %) | 5.4 s (22 %) | 77 % / 64 % | 5–9 s |
| 4.5 | 12.6 s | 7.8 s | 92 % / 73 % | 8–13 s |
| 6 | 16.4 s | 10.1 s | 108 % / 82 % | 10–16 s |
| 16 | 42 s | 26 s | 210 % / 146 % | 26–42 s |
| NPU turbo, for scale | 2.7 s (11 %) | 2.7 s | 73 % | 2.7 s |

It clears the 0.70 ceiling only at **F ≤ 4.0 s at 60 % speech, ≤ 2.3 s all-speech**, and even then
freezes the fast path 5–9 s per window — a regression of the thing the fast chunks exist for — with
two weight sets (190 + 574 MB) resident. The NPU's finalizer budget does not transfer because on
CPU the expensive term is the encoder and the finalizer pays it 1.6–2.6× per window. The one CPU
shape indifferent to F is an explicit **re-transcribe at stop / on a file** with a progress bar:
nothing leaves the app before the stop tap (`FloatingBubbleService.kt:3478`), so a replacement
has no retraction problem; cost per 30 s piece at ctx 1500 = 2.93·F + ~1.2 s → rtf 0.33 at F = 3,
0.63 at F = 6, **1.5 at F = 15 (slower than real time)**; it needs a session-long PCM store (the
engine buffer is capped at 30 s, `LocalWhisperEngine.kt:79`) — a different feature.

---

## 6. RISKS, ranked, with how you would know on the device

| # | Risk | How you know on the device |
|---|---|---|
| 1 | **F ≥ 9 s → an unbounded queue under any sustained speech** (§2.4). The model says 10–22 s. | `ggml` `encode time = … / 1 runs` ≥ 9,000 ms on a floor-bound slice; the "(N in queue)" strip label climbs during continuous dictation; stop-tap tail of tens of seconds |
| 2 | **First Auto commit after load ≈ 3.9–4.3× F** (§2.3) — turbo ~1 min; multi ~9.6 s today, never noticed | the fresh-context auto arm's `encode time … / 2 runs` with the first run ~2.9× the second; in the live run, the first `segment-timing` line of an Auto session vs the second |
| 3 | The 128-bin model garbles at `audio_ctx = 512` (validated on the 80-bin tiers only) | `BENCH audioctx tier=ultra floor=512 … wer=` and the 1500-reference arm; non-blank assertions at `WhisperBenchTest.kt:310-313` |
| 4 | `pro` on the S23 is already a GPU session paced by a CPU row — the slowness is the governor, and no turbo tier addresses it | `init: use_gpu=1` in `logcat -s whisper_jni` on the Play build (§2.6) |
| 5 | GPU "correct" output that is a repaired T = 0 garbage pass (the canary passes it; 8–9× cost) | GPU arm with wer 0.000 **and** Δp ≥ 1; decode ms/token far above the CPU arm's |
| 6 | The 45.6 MB `d_te` copy per token makes any GPU arm slower on decode regardless of the encoder | Δp = 0 on both arms yet GPU decode ms/token uniformly larger; the 3 s→8 s delta on the GPU arm |
| 7 | Temperature cascades on a 574 MB model produce multi-second outliers that read as cost | Δp/Δh non-zero on clean jfk/canary; a slice whose wall is > 2× its neighbours |
| 8 | Thermal drift at > 100 % duty | drift % first-5 vs last-5 over 300 s; `dumpsys battery` temperature before/after each arm; 10–20 % is the noise band |
| 9 | RAM ~1.0–1.3 GB in an overlay foreground service → an LMK target multi never was | the four `compute buffer` lines + `model size` at load; `dumpsys meminfo` PSS during the drift; the ≤ 40 % MemTotal rule |
| 10 | The GPU arm silently runs on CPU because OpenCL init fails on the 740 (never probed here) | `loadMs` and rtf equal between arms; no `OpenCL driver:` / Adreno init line under `ggml` |
| 11 | The q8_0 GPU arm aborts the process at load (#3708) | `GGML_ASSERT(M % 4 == 0)` in logcat; run it last, alone |
| 12 | Adreno image KQ/KQV M-tile OOB on ≥ 8.96 s post-VAD segments (PR #27632 not vendored) — on every GPU tier, pro today included | `werVsOwnCpu` on the 15 s slice (814 frames) of a GPU arm; the 8 s slice (464 → 512, aligned) cannot show it |
| 13 | The 740's OpenCL compiler is older than E031.38.11, flipping the Adreno-kernel threshold to 128 and changing which tensors take the noshuffle path | the `OpenCL driver:` line at load (`ggml-opencl.cpp:3945`) |
| 14 | Build B's define ships by accident and opens OpenCL to Mali behind only the Kotlin allowlist | the Adreno init line absent on a release APK; the property must never reach `main` without its ON default |
| 15 | The reinstall restores non-credential prefs via Auto Backup and the live run selects the wrong tier | check `selectedModelId` after every install, not only after `-e select` |
| 16 | The `+i8mm` lever proves empty even for q8_0 (compute-bound encode may favour it; memory-bound decode may not) | the q8_0 CPU arm's `encode time` vs q5_0's on the same slice |

---

## 7. WHAT WAS REFUTED

Dropped or corrected, with the code that decided it:

1. **"Cold / throttled F 17–33 s" (CPU-feasibility C3, C12).** The ×1.33–1.52 "measured
   warm/cold spread" compared gpu-ab CPU arms billed at **768** frames with floor-bench rows at
   512. `bench_gpu_vs_cpu_ab` never calls `setAudioCtxFloor`; the A/B was committed at 15:13 and
   17:17 on 2026-08-20 (`0cea395`, `00543c0`) and the floor moved to 512 at 17:37 (`3638262`); the
   floor-bench header says the production floor was 768 (`audio-ctx-floor-bench.md:6`). At matched
   frames the cold arms read ×0.84–1.02. **No cold penalty exists in the repo.**
2. **"The first Auto segment costs 2× F" (CPU-feasibility C4, product C6).** A lower bound:
   `exp_n_audio_ctx` is stored at `src/whisper.cpp:7231`, after the detect at `:7095`; the first
   call after load detects at 1500 frames → ≈ 3.9–4.3× F. Applies to multi today.
3. **"For turbo the `%4` GEMM spill never reaches a sampled cell" (GPU-root-cause C4).** Refuted
   by the store loop's arithmetic (`gemm_noshuffle_q5_0_f32.cl:98-130`): the last, sampled column
   receives rows 0/1; `!` (id 0) is a live logit. Turbo's EMPTY is *not shown* by H1, not excluded.
4. **"Odd-M GEMV misreads line j by j rows, up to 191" (GPU-root-cause C2 magnitude).**
   `BLOCK_STRIDE_A = NSUBGROUPS·M` (`:212`) is exact; the qs error is ≤ 7 rows within each block;
   qh by the byte-line index, scales by the block index. Conclusion (odd M garbage, even M exact)
   unchanged.
5. **"~4.3 s GPU / ~15 s CPU for turbo" as point numbers (GPU-root-cause C11).** The CPU divisor
   was 2.1 s, not the 2.25 s fit; the 80 GMAC/s transfer from small.en shapes to 32-layer/1280-wide
   shapes has no support. Bands only; the qualitative conclusion (both miss the 2 s cadence) stands.
6. **`gpu-ab-bench.md:110-117` "the corruption does NOT reproduce" and `:124-125` "now takes a
   correct-but-slow path".** No parity predicate exists anywhere in the backend; July ran
   `temperature_inc = 0.0f`, August `0.2f`; the "slow path" is whisper's 5-decoder retry.
7. **Catalog map: "the A/B ran at floor 512".** It ran at 768 (item 1).
8. **The brief:** "measured before the dotprod+i8mm CPU kernels" — the kernels (`d8a4d5b`,
   2026-07-16) predate the retirement (`cb519a7`, 2026-07-27); "3.6.0 Workstream C found the
   corruption" — observed 2026-07-17, before Workstream C; "`build.gradle.kts:83` blames 51,866" —
   `:82-83` names only the `.en` 51,864, and the small multilingual vocab is 51,865 (51,866 is
   turbo's); "existing ultra users migrated to eco" — to `multi` (`ModelMigration.kt:33-36`);
   "q8_0 ~830 MB" — 874,188,075 B; "canary-gated behind the toggle" — true, but the Settings row
   is deleted and source-pinned dead (`GpuExperimentRowRetiredTest.kt`), so the multilingual GPU
   path is unreachable in a shipping build.
9. **Memory note "4.3.1/83 on local main, unpushed; next 4.4".** Local main is 4.3.2 / vc86
   (`build.gradle.kts:53-54`); 84 is in production, 85 went internal. The session snapshot's
   `3f982b9` is three commits stale.
10. **Upstream map: "enable the developer toggle or you will bench nothing".** The forced arms of
    `bench_gpu_vs_cpu_ab` call `WhisperNative.init(path, useGpu)` directly (`WhisperBenchTest.kt:481`)
    and bypass `GpuPolicy`; the toggle matters only for the optional in-session GPU run.
11. **Upstream map: "#26477 is aimed at exactly the vocab GEMV whisper runs"; "could not prove
    whether sched caches the split input".** #26477 is q4_K/q6_K and excludes A7X; #26440 is
    K-quant only; the copy is per compute (`ggml-backend.cpp:1551-1575`), proved.
12. **`CommitCadencePolicy.kt:220-223` "pro is CPU-only; the GPU path is essentially dead"
    (owner ruling, 2026-09-02).** The code routes `.en` to the GPU on every allowlisted Adreno
    (`GpuPolicy.kt:154, 191, 140, 231-249`). The ruling and the routing disagree; the routing is
    what runs. Recorded as an open question for the owner, answered by one logcat line.
13. **Product C9: "un-retiring makes multi's pinned headline false; the copy test forbids any
    speed number".** Ultra-q5_0's accuracy vs multi is unmeasured (the A/B was w8a16 on the
    Hexagon), so "false" overstates it — a product-truth decision; and the no-speed-number rule is
    a comment (`ModelTierCopy.kt:49-52`), not a test.
14. **Product C12 / GPU map: "the Adreno 740 has never run anything".** Never been *measured*;
    the code says it is probably running pro today (item 12).
15. **Spike C5: "the fallbacks line is per call".** Cumulative per context
    (`whisper_reset_timings`, `src/whisper.cpp:4549-4563`, never touches `n_fail_p/h`); print deltas.
16. **Spike F12 citation `NpuDiag.kt:122`.** That is a mel-probe log formatter; the gate is
    `NpuGate.kt:79-84` / `NpuFleetCensus.kt:123-156`.
17. **`build.gradle.kts:76-77` "runtime-dispatched via getauxval".** Not for the repack tiles:
    `ggml_cpu_has_matmul_int8()` is compile-time (`ggml-cpu.c:3744-3750`); and the `+i8mm` half is
    unused by q5_0/q5_1 regardless (§2.5).
18. **`GpuPolicy.kt:150-151` "multilingual tiny passes on GPU — its dims stay under the Adreno
    threshold".** Compiler-version-conditional: the threshold drops to 128 on compilers older than
    E031.38.11 (`ggml-opencl.cpp:5017-5021`), which the docblock does not say and the 740's version
    is unknown.

Kept with their caveats: the S23U ×1.15–1.3 device factor (external; replaced by the multi row on
the S23U in the same run); the ×1.5–3 re-quantisation lever (unmeasured); the 1.0–1.3 GB RAM total
(estimate above an exact 624 MB floor); the ~5–6 ms/token CPU head GEMV under candidate E
(derived); every thermal drift figure (unmeasured; the in-repo spread is multi's ×1.09–1.57
in-session drift).
