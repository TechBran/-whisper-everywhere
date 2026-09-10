# Tab S10+ (MT6989) — whisper-large-v3-turbo END-TO-END on the Mali-G720 through LiteRT (E6), and the sherpa-onnx streaming APK (Rung 2)

Device, runtime and conventions are those of `docs/measurements/2026-09-09-tab-apu-probe.md` (§0): Samsung Galaxy
Tab S10+ SM-X828U, MediaTek MT6989, Mali-G720 Immortalis MC12, Android 16 / API 36, 12 GB, serial
`192.168.1.161:44483`; LiteRT `2.1.1` (`libLiteRtOpenClAccelerator.so`) in the probe app
`com.whispereverywhere.probe` (`tools/probes/litertlm-probe/`, this session's build: `app-debug.apk`
27,947,728 B, installed 05:56 with `adb install -r`, versionCode 1). The Play copy of Whisper Everywhere
(`com.whispereverywhere`, versionCode 86 / 4.3.2) stayed installed and untouched — §7 proves it. Session
2026-09-10 05:35–. Raw logs: `C:/Users/bastr/.androidbuild/probe-logs/<tag>.{json,filtered.log,full.log}`;
tables come from `tools/probes/litertlm-probe/whisper_e2e.py summarize <tag…>`.

The question E5 left open (§4.1 there): the turbo **encoder** runs on the Mali in 1,855 ms with `Options(GPU, CPU)`;
is "turbo on the Tab" real once the **decoder loop** is added, and is the text right? This file answers both, and
the answer to the second changes the first.

## 0. What was measured, and how

- **Models**: the litert-community `.tflite` pair already in the probe's `files/` (sha256 equal on PC and Tab,
  2026-09-09): `whisper_large_v3_turbo_30s_i8.tflite` (1,088,340,944 B, dynamic-range int8 weights / fp32
  activations, vocab 51866) and `whisper_base_30s_f32.tflite` (290,082,636 B, f32, vocab 51865) as the reference.
- **Clips**: the app's two fixtures, `app/src/androidTest/assets/jfk.wav` (11.00 s, 176,000 samples) and
  `app/src/main/assets/canary_digits.wav` (2.56 s, 40,960 samples), both 16 kHz mono PCM16. Their log-mels are
  computed **on the PC** by `whisper_e2e.py mel` (OpenAI whisper's `log_mel_spectrogram`: n_fft 400, hop 160,
  periodic Hann, reflect-centred STFT, last frame dropped → 3000 frames, slaney mel filters, log10, clamp to
  max−8, (x+4)/4) in 128 bins for turbo and 80 for base, and pushed as raw f32 `[n_mels][3000]`
  (`jfk_mel128.bin` sha256 `4d5ed762…`, `canary_mel128.bin` `6e4ed5e1…`, `jfk_mel80.bin` `b8107ac3…`,
  `canary_mel80.bin` `d09141ed…`; via `/data/local/tmp` + `run-as cp`, tmp copies deleted). The numpy filterbank
  equals the app's own `melbank-128.bin` (the ggml prefix `NpuModelSpec` pins) to **max |diff| 3.7e-9**
  (`whisper_e2e.py melbank`), so the frontend is the one the app ships.
- **Prompt**: `<|startoftranscript|>, <|en|>, <|transcribe|>, <|notimestamps|>` = `50258, 50259, 50360, 50364`
  for turbo (the model card's 51866-vocab ids; `tokenizer.json` of `openai/whisper-large-v3-turbo` agrees) and
  `50258, 50259, 50359, 50363` for base; EOT `50257`; greedy argmax; stop at EOT or **64** generated tokens. No
  suppression masks, no temperature ladder — the bare loop, so what is measured is the graph, not a policy.
- **Text**: the probe logs **token ids only**; the PC detokenises them (`tokenizers` 0.23.1 on the two HF
  `tokenizer.json` files). Nothing but the two fixtures' text ever appears anywhere.
- **Timing** (same convention as 09-09): every number is **run+read** (`CompiledModel.run()` followed by
  `TensorBuffer.readFloat()`), because the OpenCL accelerator's `run()` returns before the queue drains
  (measured again here: 2–6 ms "runs" on every GPU step). `encode ms` = write mel, run, read the `[1,1500,d]`
  states. `handoff ms` = writing those states into the decoder's input buffer (0.4–7 ms; included in the
  utterance total). `decode ms/token` = run + read of the `[1,128,vocab]` logits block, which is the only read
  the Kotlin API offers (26.6 MB per step for turbo) — so **`run-only ms/token` and `readback ms/token` are
  reported beside it**, and `decbench` (§3.2) separates the accelerator's real per-step compute from that
  readback. `utterance total` = encode + handoff + decode. RSS/PSS, battery °C and thermal status as before.
- **Schedule**: `utts` rounds over the clip list (jfk, canary, jfk, canary, …); utterance 0 is the cold one and
  is excluded from the warm statistics. Every run is a fresh process (`am force-stop` first). Runs were
  sequential, never overlapping, with a cool-down gate (thermal status 0 and battery ≤ 27.0 °C) before each
  turbo run.

## 1. The decoder's shape (from the graphs' signatures, `ai_edge_litert` on the PC, 2026-09-10)

| graph | signature | inputs | output |
|---|---|---|---|
| turbo `_30s_i8` | `encode` | `args_0` f32 `[1,128,3000]` | `output_0` f32 `[1,1500,1280]` |
| turbo `_30s_i8` | `decode` | `args_0` f32 `[1,1500,1280]` (encoder states), `args_1` **i32 `[1,128]`** (the whole token window), `args_2` **f32 `[1,1,128,128]`** (attention mask) | `output_0` f32 **`[1,128,51866]`** (logits for all 128 positions) |
| base `_30s_f32` | `encode` | `args_0` f32 `[1,80,3000]` | `output_0` f32 `[1,1500,512]` |
| base `_30s_f32` | `decode` | `args_0` f32 `[1,1500,512]`, `args_1` i32 `[1,128]`, `args_2` f32 `[1,1,128,128]` | `output_0` f32 `[1,128,51865]` |

So, verified rather than read off the model card:

- **No KV-cache tensors of any kind.** The decoder takes the *entire* 128-token window and the encoder states
  every call and returns logits for every position; the loop writes the next id at `pos+1` and reads row `pos`.
- **The window is fixed at 128, not 448** (whisper's native context) and it does not grow: **every step costs the
  same full 128-position decoder pass** — self-attention over 128×128, cross-attention 128×1500, the FFNs and
  the `[128,1280]×[1280,51866]` logits projection for all 128 rows — whether 4 or 100 tokens are live. Per-step
  cost is therefore a constant, not quadratic in the emitted length; the price is paid up front, on every step,
  for 128 positions when a KV-cached decoder would pay for one. (Budget: 128 − 4 prompt = **124 generated tokens
  max**; the app's 196-token budget on the QNN tier does not fit this graph.)
- **The mask is additive**, 0 on and below the diagonal and a large negative above (HF's 4-D causal mask). Proven
  on the PC interpreter with base/jfk: additive 0/−1e9 → the JFK sentence; a 0/1 mask → `' (D.'`; an all-zero mask
  → immediate EOT. −1e4 behaves identically to −1e9 (device runs `e6_base_gpu_diag_mask1e4`, `…gpu32_diag_mask1e4`).
- The decoder's `args_*` order differs from its subgraph input order (`args_2` is subgraph input 0), so the probe
  binds the three inputs **by name and verifies each by shape** (`e2e|decode|bound enc=args_0 ids=args_1
  mask=args_2` in every log) — the model card's "bind by shape on Android" warning, handled.

**PC validation of the whole recipe** (`whisper_e2e.py pc-run`, XNNPACK 8 threads, x86): base decodes jfk as
`' And so my fellow Americans, ask not what your country can do for you, ask what you can do for your country.'`
(25 tokens) and the canary as `' One, two, three, four, five.'` (10); turbo decodes jfk as `' And so, my fellow
Americans, ask not what your country can do for you, ask what you can do for your country.'` (26 tokens) and the
canary as `' 1, 2, 3, 4, 5.'` (10). Turbo on the PC: encode 7.2–8.6 s, **decode 216–225 ms/step** — 94 ms/step is
what the model card reports for i8 on a desktop 8-thread CPU, so the per-step cost of this decoder is
large-model-class even before the tablet.

## 2. The base reference on the Tab — and the finding that re-reads E5

Every configuration of the base graph, 4-token prompt, both clips (`utts` 1–3; the full tables are in
`whisper_e2e.py summarize e6_base_*`). "CPU" is XNNPACK 4 threads; "GPU" is `Options(GPU, CPU)` with the
OpenCL accelerator (`Replacing 343 out of 343 … (LITERT_CL) … subgraph 0` and `535 out of 535 … subgraph 1`);
"default" precision is the accelerator's default (fp16 storage/compute), "fp32" is `GpuOptions(precision = FP32)`.

| tag | encoder | decoder | encode ms (warm) | decode ms/token run+read (run-only / readback) | jfk text | canary text | RSS MB |
|---|---|---|---|---|---|---|---|
| e6_base_cpu_1 | CPU | CPU | 514 | 150 (143 / 7) | **correct** (25 tok, = PC) | **correct** (10 tok, = PC) | 894 |
| e6_base_gpu_1 | GPU default | GPU default | 112 | 45 (3.7 / 41) | `' >> >> >> …'` ×64, no EOT | `' - - - …'` ×64, no EOT | 1,099 |
| e6_base_gpu_fp32_1 | GPU fp32 | GPU fp32 | 219 | 64 (3.6 / 61) | `' And so my my my my …'` ×64 | `' One Two Three Four Five'` (5 tok, EOT) | 1,404 |
| e6_base_gpuenc_cpudec | GPU default | CPU | 128 | 152 (144 / 8) | `' >> >> …'` | `' >> >> …'` | 1,518 |
| e6_base_gpu32enc_cpudec_dump | GPU fp32 | CPU | 224 | 189 (180 / 9) | **correct** | **correct** | 1,846 |

Three diagnostics that did **not** change a single logit of the GPU decoder (identical `argmax`/`second`/`eot`
values step for step): fresh `TensorBuffer`s every step (`…diag_fresh`), rewriting the encoder states and mask
before every step (`…diag_rewrite`), padding the window with id 0 instead of EOT (`…pad0`, both precisions —
so the causal mask is applied on the GPU; it does not leak the padding).

**The encoder states themselves, compared** (`--ez dumpstates`, utterance 0 = jfk, pulled with `adb exec-out`
and compared to the PC interpreter's CPU encode by `compare_states.py`):

| encoder | corr with CPU reference | mean abs err (signal mean abs 0.730) | max abs err | permutation explains it? |
|---|---|---|---|---|
| Tab CPU / XNNPACK | 1.00000 | 0.000 | 0.0006 | — |
| Tab GPU **fp32** | **1.00000** | **0.000** | **0.0009** | — |
| Tab GPU **default (fp16)** | **0.825** | **0.650** | 16.86 | no — transpose and every channel-block layout tried correlate at ≤ 0.03 |

So the Mali OpenCL accelerator at its default precision produces an encoder output that is **not an fp16
rounding of the right answer** (a relative error of 0.89 on the signal; the worst channels are off by 2–3.5
on average, which is the signature of an fp16 overflow or a broken kernel inside the encoder, not of storage
precision). Fed to a *correct* CPU decoder it yields `' >> >> >>'` for both clips. **E5's 09-09 turbo-encoder
fingerprint ("within ~3 % of the CPU on aggregate statistics") could not see this, because mean / min / max /
mean-abs are permutation- and error-blind at this level; the 1,855 ms was the time of a wrong encoder.** At FP32
the GPU encoder is exact (1e-3), the base encode costs 209–237 ms instead of 109–130, and the text is right when
the decoder is the CPU's.

The GPU **decoder** is a second, separate defect: at fp32 it is a near-miss (jfk `' And so my'` then a `my` loop
where the CPU's row has `fellow` at 27.1 and `my` at 12.3; canary decodes as `' One Two Three Four Five'` with EOT),
at fp16 it is garbage from step 1 (`' >>'`, `' -'`). The CPU reference rows for the same steps are in
`probe-impl`-style detail in the run logs (`e2e|diag|…` lines).

Timing of that base reference, for the record: the CPU decoder costs **143 ms/step** for a 6-layer d=512
decoder over a 128-token window (the full-window re-run at work), the GPU one 3.6 ms enqueue + 41–61 ms to read
26.5 MB of logits back.

## 3. E6 — turbo end-to-end (`whisper_large_v3_turbo_30s_i8.tflite`, 4-token prompt, greedy to EOT or 64 tokens)

Four configurations, one fresh process each, sequential, cool-down gated (thermal 0 and ≤ 27.0 °C before each
start; the fp32-all-GPU repeat `e6_turbo_gpu32_2` started at status 1). Warm statistics exclude utterance 0.

### 3.1 The per-utterance table (`whisper_e2e.py summarize e6_turbo_*`; every clip, every round)

| tag | encoder | decoder | clip | utt | encode ms | tokens | decode ms/token run+read | run-only / readback ms | utterance total ms | text | RSS MB | batt / thermal |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| e6_turbo_gpu16_1 | GPU default (fp16) | GPU default | jfk | 0 (cold) | 1,792 | 8 | 79.8 | 3.5 / 76.3 | 2,606 | `' And'` then SOT ×7 | 3,480 | 26.9 C / 0 |
| e6_turbo_gpu16_1 | GPU default | GPU default | canary | 1 | 1,807 | 12 | 74.7 | 2.5 / 72.1 | 2,782 | `' One 1 1 1 1'` (+ SOT ×7) | 3,485 | 26.9 C / 0 |
| e6_turbo_gpu16_1 | GPU default | GPU default | jfk / canary | 2–5 | 1,834 / 1,847 / 1,868 / 1,854 | 8 / 12 | 75.0–79.5 | 2.4–2.7 / 72–77 | 2,512–2,870 | same two wrong strings, every round | 3,483 | 26.9 C / 0 |
| e6_turbo_gpu32_1 | GPU **fp32** | GPU fp32 | jfk | 0 (cold) | 3,413 | 6 | 130.3 | 5.3 / 124.9 | 4,429 | `' And'` then SOT ×5 | 4,486 | 26.5 C / 0 |
| e6_turbo_gpu32_1 | GPU fp32 | GPU fp32 | canary | 1 | 3,447 | 7 | 123.2 | 2.8 / 120.4 | 4,435 | `' 1'` then SOT ×6 | 4,510 | 26.5 C / 0 |
| e6_turbo_gpu32_1 | GPU fp32 | GPU fp32 | jfk / canary | 2–9 | 3,446–3,479 (mean 3,463, sd 12) | 6 / 7 | 120–131 | 2.2–3.5 / 118–128 | 4,321–4,501 | same two wrong strings, every round | 4,485–4,515 | 26.5 C / 0 to 1 |
| e6_turbo_gpu32_2 | GPU fp32 | GPU fp32 | jfk / canary | 0 / 1 | 3,839 / 3,993 | 6 / 7 | 157 / 142 | 4.6 / 152 and 2.5 / 140 | 5,033 / 5,133 | same | 4,871–4,898 | 26.9 C / 1 (started warm) |
| e6_turbo_gpu32enc_cpudec_1 | GPU **fp32** | **CPU 4 thr** | jfk | 0 (cold) | 3,396 | 26 | 124.3 | 117.8 / 6.6 | 6,819 | **`' And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country.'`** | 5,852 | 26.6 C / 0 |
| e6_turbo_gpu32enc_cpudec_1 | GPU fp32 | CPU 4 thr | canary | 1 | 3,513 | 10 | 138.7 | 131.2 / 7.6 | 5,042 | **`' One, two, three, four, five.'`** | 5,851 | 26.6 C / 0 |
| e6_turbo_gpu32enc_cpudec_1 | GPU fp32 | CPU 4 thr | jfk / canary | 2–5 | 3,490–3,518 (mean 3,507, sd 11) | 26 / 10 | 131–141 | 124–134 / 7.2–7.6 | 7,034–7,164 / 5,034–5,055 | **correct, every round (ids identical to the PC's)** | 5,826 | 26.6 C / 0 to 1 |
| e6_turbo_cpu_1 | CPU 4 thr | CPU 4 thr | jfk | 0 (cold) | 4,777 | 26 | 129.4 | 122.1 / 7.3 | 8,341 | **correct** (same 26 ids) | 2,103 | 26.9 C / 0 |
| e6_turbo_cpu_1 | CPU 4 thr | CPU 4 thr | canary | 1 | 5,500 | 10 | 150.4 | 141.3 / 9.1 | 7,157 | **`' 1, 2, 3, 4, 5.'`** | 2,105 | 26.9 C / 1 |
| e6_turbo_cpu_1 | CPU 4 thr | CPU 4 thr | jfk / canary | 2–5 | 5,852 / 5,861 / 6,361 / 6,365 (thermal ramp) | 26 / 10 | 149–163 | 140–153 / 9.3–10.0 | 9,883 / 7,633 / 10,764 / 8,164 | **correct, every round** | 2,080 | 27.2 C / 1 to 2 |

Whole-run figures: `e6_turbo_gpu16_1` create 3,428 ms, RSS 3.33 GB after create / 3.48 GB at the end;
`e6_turbo_gpu32_1` create 5,781 ms, RSS 4.36 / 4.46 GB; `e6_turbo_gpu32enc_cpudec_1` create 6,269 ms + 1,294 ms
for the second (CPU) model, RSS 4.50 GB after the GPU model, **5.66 GB after the CPU one, 5.83 GB at the end**;
`e6_turbo_cpu_1` create 1,336 ms, RSS 1.75 / 2.08 GB. Text on the turbo CPU path is the turbo's own reading
(`' And so, my fellow …'` with the comma, `' 1, 2, 3, 4, 5.'` in digits), byte-identical in ids to the PC's
XNNPACK run of the same file, so the graph is right and the Mali is what differs.

### 3.2 Which backend ran what — the proof lines, and the decoder step split

`e6_turbo_gpu16_1` / `e6_turbo_gpu32_1` / `e6_turbo_gpu32enc_cpudec_1` (GPU model): `tflite: Loaded OpenCL library
with dlopen.` · `litert: [gpu_environment.cc:223] Created OpenCL device from provided device id and platform id.` ·
`E tflite: GATHER: Only support 1D indices` · `E tflite: 320 operations will run on the GPU, and the remaining 1
operations will run on the CPU.` · `tflite: Replacing 320 out of 321 node(s) with delegate (LITERT_CL) node,
yielding 2 partitions for subgraph 0.` (the encoder, one positional GATHER on the CPU) ·
`tflite: Replacing 1462 out of 1462 node(s) with delegate (LITERT_CL) node, yielding 1 partitions for subgraph 1.`
(**the whole decoder on the GPU**). The second model of `…gpu32enc_cpudec_1` and all of `e6_turbo_cpu_1`:
`tflite: Created TensorFlow Lite XNNPACK delegate for CPU.` · `Replacing 320 out of 321 node(s) with delegate
(TfLiteXNNPackDelegate) node, yielding 2 partitions for subgraph 0.` · `Replacing 1462 out of 1462 node(s) with
delegate (TfLiteXNNPackDelegate) node, yielding 1 partitions for subgraph 1.`

The Kotlin `TensorBuffer` API reads the decoder's whole `[1,128,51866]` output (26.6 MB) per step, and on the
GPU that read is also where the asynchronous `run()` is waited for; `decbench` (8 runs enqueued, one read,
C = (t8 − t1) / 7) separates the two:

| decoder | per-step compute ms | logits readback ms | loop's run+read ms/token | text |
|---|---|---|---|---|
| GPU default (fp16) | **58.5** | 22.0 | 75–80 | wrong |
| GPU fp32 | **134.5** | 13.7 | 120–157 | wrong |
| CPU 4 thr (beside the fp32 GPU encoder) | **119.0** | 3.6 | 124–141 | **right** |
| CPU 4 thr (all-CPU process) | **121.8** | 2.8 | 129–163 (thermal) | **right** |

A product loop reading one 51,866-float row through the C API would pay the compute column plus about 0.2 ms;
the compute column is the honest per-token number. The GPU's fp32 decoder is **slower than the CPU's** (134 vs
119 ms) and wrong; its fp16 decoder is 2× faster than the CPU's and wrong.

### 3.3 What is wrong on the GPU, exactly

- **Encoder, default precision**: the base experiment (§2) measured it — corr 0.82 with the reference, mean
  error 0.89× the signal. For turbo the encoder-only E5 fingerprint (`mean_abs 0.26142` vs CPU `0.26226`) looked
  fine for the same reason base's did; fed to a correct decoder it does not transcribe (the turbo fp16 all-GPU
  run's `' And'` / `' One 1 1 1 1'`, and `e6_base_gpuenc_cpudec`'s `' >>'` from a *correct* CPU decoder).
  **The 1,855 ms encoder of 09-09 §4.1 is the time of a wrong encoder.** FP32 fixes it (states equal to 1e-3)
  at 3,463–3,507 ms.
- **Decoder on the GPU, both precisions**: with a *correct* fp32 encoder the GPU decoder emits the first word and
  then `<|startoftranscript|>` until it stops (turbo), or loops a word (base). The mask is applied (pad-id test),
  buffers are fresh (fresh-buffer test), the inputs are the ones the CPU decodes correctly from — the OpenCL
  kernels of this decoder graph (int8 dynamic-range FULLY_CONNECTED / BatchMatMul dequantised in fp32, the
  embedding GATHER, softmax over the additive mask) produce different logits. Not isolated further here; it is
  reproducible in one `drive.py` line each (`e6_turbo_gpu32_1`, `e6_base_gpu_fp32_1`).

## 4. The per-commit number, against the bars

The app's turbo cadence row bills a commit as **encode + tokens × per-token**, and a typical commit is ~20
tokens (the NPU tier's calibration: encode 1,778.9 + 44.4 + 10.08 ms/token = **1.89 s** on the Fold6,
`docs/superpowers/plans/2026-09-02-vad-hangover-retune.md:65-69`; the row's bar is **2,000 ms**). Every
turbo commit on this artefact bills the full 30 s window — there is no `audio_ctx` floor in the graph.

| configuration | text | encode (warm) | per token (compute) | 20-token commit | vs 2,000 ms bar | vs NPU tier 1.89 s | RSS |
|---|---|---|---|---|---|---|---|
| all-GPU default (fp16) | **wrong** | 1,842 | 58.5 | **3,012 ms** | 1.5× over | 1.6× | 3.5 GB |
| all-GPU fp32 | **wrong** | 3,463 | 134.5 | 6,153 ms | 3.1× over | 3.3× | 4.5 GB |
| **GPU fp32 encoder + CPU decoder** | **right** | 3,507 | 119.0 | **5,887 ms** (+ 1 ms handoff) | **2.9× over** | 3.1× | **5.8–6.0 GB** |
| all-CPU 4 thr, cold process | right | 4,777 | 121.8 | 7,213 ms | 3.6× over | 3.8× | 2.1 GB |
| all-CPU 4 thr, sustained (thermal 2) | right | 5,500–6,365 | 140–153 | 8.3–9.4 s | 4.2–4.7× over | 4.4–5.0× | 2.1 GB |

**Is the no-KV-cache decoder quadratic?** No — and stating it precisely matters. Each step re-runs the whole
fixed 128-position window regardless of how many positions are live, so the per-step cost is a **constant** (58.5
/ 134.5 / 119 ms above) and a commit of N tokens costs N × that constant, linearly, up to the window's hard cap of
**124 generated tokens**. What blows the budget is the size of the constant, not its growth: at 119 ms the decoder
alone crosses the 2,000 ms bar at **17 tokens**, with the encoder's 3.5 s already spent before the first one (at
the fp16 GPU decoder's 58.5 ms — wrong output — it would be 34 tokens). Against the KV-cached QNN decoder's
10.08 ms/token, this decoder is 12× per token on the CPU and 6× on the (wrong) fp16 GPU, because it does 128
positions of self-attention, cross-attention and FFN plus the 128-row logits projection for one token of output.
There is no token count at which this graph's decode fits the row; the encoder alone does not.

## 5. Verdict

**A turbo-on-Mali tier for the Tab is not within the 2 s bar end-to-end, and the number that suggested it might
be was wrong output.** The only configuration of the published artefact that transcribes correctly on this
device with the GPU involved is *fp32 encoder on the Mali + decoder on the CPU*: **about 5.9 s per 20-token
commit at 5.8–6.0 GB RSS**, against the 2,000 ms row (2.9× over), the 8,000 ms row's honest ceiling (5.28 s,
research doc §1.3 — over that too), and the Fold6's NPU tier at 1.89 s. The all-CPU path is 7.2 s cold and
8.3–9.4 s sustained, at thermal status 2 within six utterances.

What a real tier would need, each item unbuilt today:

1. **An encoder the GPU computes correctly in fp16, or the fp32 price.** Default-precision OpenCL on this
   encoder is numerically wrong (§2: corr 0.82 vs the reference; the same on base, so it is not the int8
   weights); `GpuOptions(precision = FP32)` is exact and costs 3.46–3.51 s per window — 1.9× the 1,855 ms E5
   recorded — at 4.4–4.5 GB of OpenCL-side memory. An fp16-safe export (scaled LayerNorms, or whatever overflows
   isolated) is a conversion nobody has done; whether the Mali could then hold the 1.86 s is unknown.
2. **A floor.** The graph bills 1,500 frames per commit. A static 512-frame export would cut the encoder's
   FLOPs to ~30 % (706.6 vs 2,313 GFLOP): at the fp32 GPU's measured ~1.5 ms/GFLOP about 1.1 s — still over half
   the bar before decoding, on an artefact that does not exist.
3. **A KV-cached, static-shape decoder** (the QNN tier's shape), because this one costs 119 ms per token on the
   CPU and is wrong on the GPU at both precisions. Fixing the GPU decoder is a LiteRT OpenCL-accelerator bug
   report, not app work; even fixed, its fp16 speed (58.5 ms/token) is 6× the QNN tier's.
4. **Memory.** 5.8–6.0 GB RSS for the correct configuration on a 12 GB tablet (two mappings of a 1.09 GB file,
   OpenCL fp32 copies, XNNPACK's repacked weights), 3.5 GB for the wrong one — before the app's own footprint.
5. The single `GATHER` fallback is harmless (one positional gather on the CPU, 2 partitions, no measurable cost)
   and stays.

**Kill lines (all measured, 2026-09-10):** the fp16 GPU encoder's output does not transcribe (`' >>'` / `' -'`
on base, `' And'` + SOT on turbo) — E5 §4.1's 1,855 ms is void as a tier number; the correct GPU encoder is
3.46–3.51 s, at least 1.7× the bar with zero tokens decoded; the GPU decoder is wrong at fp16 and fp32; the correct
end-to-end commit is 5.9 s at about 6 GB. **R3 (research doc §1.2) is closed for the 2,000 ms row by E6**, and it
is closed for the 8,000 ms row too unless a 512-frame fp32 export lands under 5.3 s with a cached decoder — three
artefacts away. The Tab's honest turbo today is the CPU at 7–9 s, which the owner already ruled out.

## 6. Rung 2 — the prebuilt k2-fsa sherpa-onnx streaming English APK: BLOCKED by Play Protect on this tablet

Fetched 2026-09-10 05:37 from the k2-fsa APK mirror the docs page links
(`https://huggingface.co/csukuangfj2/sherpa-onnx-apk/resolve/main/asr/<ver>/…`):

| file | bytes | sha256 | package / version | targetSdk | bundled model |
|---|---|---|---|---|---|
| `sherpa-onnx-1.13.4-arm64-v8a-asr-en-zipformer2.apk` | 72,640,211 (the research's "~72.6 MB") | `e02a41f6aa1947847a137a3e0f63ed9f44793223151d89558b33a59900e82531` | `com.k2fsa.sherpa.onnx` 1.13.4 (versionCode 20260707) | **32** | `sherpa-onnx-streaming-zipformer-en-2023-06-26`: encoder int8 70,108,816 B, decoder 2,093,080 B, joiner 1,026,462 B, `tokens.txt`; `libonnxruntime.so` 21,688,920 B + `libsherpa-onnx-jni.so` |
| `sherpa-onnx-1.13.7-arm64-v8a-asr-en-zipformer2.apk` | 72,644,307 | `2902526c75cb65b8df0d52fb2419cec7ca48152d8f4224893092fa44d0838a7a` | same package, 1.13.7 (versionCode 20260901) | **32** | identical model assets |

(`aapt2 dump badging`; the only permission is `RECORD_AUDIO`; application label "ASR".) Both on
`C:/Users/bastr/.androidbuild/sherpa-apk/`.

**Install (1.13.7), after the 5-minute cool (06:06:47, battery 27.0 °C, thermal 0):** `adb -s <tab> install -r`
never returned — killed after 20 minutes; a second attempt via `adb push` + `pm install -r /data/local/tmp/sherpa.apk`
timed out at 180 s the same way. The tablet's foreground window throughout was
`com.android.vending/com.google.android.finsky.protectdialogs.activity.PlayProtectDialogsActivity`, and its
text (`uiautomator dump`) is: **"Google Play Protect — Unsafe app blocked — ASR — This app was built for an
older version of Android and doesn't include the latest privacy protections."** with `More details` / `Got it`.
That is Play Protect on Android 16 refusing a sideload that targets API 32; `BACK` does not dismiss it. The
package was **not** installed (`pm list packages | grep k2fsa` → nothing), no launch happened, no logcat or RSS
exists, and the prompt was left on the screen for the owner rather than clicked through — overriding a Play
Protect block on the owner's device is the owner's decision, not the probe's.

What this leaves the owner: (a) tap `More details → Install anyway` on the Tab (or pause Play Protect scanning
in the Play Store's settings for the one install) and rerun the launch half of
`scratchpad/e6/rung2.sh` (`am start -W -n com.k2fsa.sherpa.onnx/.MainActivity`, logcat, `/proc/<pid>/status`);
the "feel" dictation was always going to be the owner's — the app reads the microphone, and no clip can be
injected from the PC; or (b) build the sherpa-onnx Android demo from source against the AAR the app already
pins (`sherpa-onnx-1.13.4.aar`, `app/build.gradle.kts:582`) with targetSdk 36, which is what a tier would do
anyway. The first RTF number for the streaming zipformer on this tablet is therefore **still unmeasured**; the
research's route survives untested, not refuted.

## 7. Teardown proof (2026-09-10 06:31, `adb -s 192.168.1.161:44483`)

```
$ adb devices -l
192.168.1.161:44483    device product:gts10psqw model:SM_X828U device:gts10p transport_id:8
$ dumpsys package com.whispereverywhere | grep -E 'versionCode|versionName|lastUpdateTime|installerPackageName|codePath'
    codePath=/data/app/~~9genVoVOFixkBuwhZjv7HA==/com.whispereverywhere-FOMQ4iihLzFvw2x31Zy08g==
    versionCode=86 minSdk=26 targetSdk=36
    versionName=4.3.2
    lastUpdateTime=2026-09-04 19:49:37
    installerPackageName=com.android.vending
$ dumpsys package com.whispereverywhere.probe | grep -E 'versionCode|lastUpdateTime'
    versionCode=1 minSdk=31 targetSdk=36
    lastUpdateTime=2026-09-10 05:56:54
$ pm list packages | grep -ic k2fsa
0
$ ls -la /data/local/tmp
drwxrwxr-x 5 shell shell 3452 2026-06-17 17:00 .studio          (the pm-install's dalvik-cache/ and sherpa.apk removed; only the pre-existing .studio/)
$ dumpsys battery | grep temperature ; dumpsys thermalservice | grep -m1 'Thermal Status'
  temperature: 258
Thermal Status: 0
```

The Play copy was never installed to, uninstalled, force-stopped or launched (same `lastUpdateTime` and
`codePath` as on 09-09; installer `com.android.vending`). The probe's `files/` keeps the five models (2.25 GB)
plus the four mel files (5.0 MB) and `files/results/` (62 JSONs + three 3 MB encoder-state dumps, 9.2 MB).
Only the Tab was ever addressed; every adb command carried `-s 192.168.1.161:44483`.

## 8. Files

- Probe: `tools/probes/litertlm-probe/app/src/main/java/com/whispereverywhere/probe/E2eProbe.kt` (mode `e2e`),
  `ProbeArgs.kt` / `ProbeRunner.kt` / `drive.py` (new keys), `whisper_e2e.py` (mel, PC run, detok, summarize),
  `compare_states.py` (encoder-state comparison). Branch `tools/tab-e6` off `tools/tab-apu-probe`.
- Logs: `C:/Users/bastr/.androidbuild/probe-logs/e6_*.{json,filtered.log,full.log}` (20 runs this session) and
  `e6_base_*_dump.states.bin`; mels and tokenizers under `C:/Users/bastr/.androidbuild/probe-models/`.
