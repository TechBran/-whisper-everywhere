# Tab S10+ (MT6989) — whisper-large-v3-turbo ENCODER on the MediaTek APU, ahead-of-time compiled

**Owner ruling (2026-09-24):** the tablet goal is turbo on its NPU; Small is not needed for tablets. This sheet
records the first turbo-class Whisper graph ever run on the tablet's APU in this project, and how it was built.

Device: Samsung Galaxy Tab S10+ SM-X828U, `ro.soc.manufacturer=Mediatek`, `ro.soc.model=MT6989`, Android 16 /
API 36, build `X828USQS6CZA3` (security patch 2026-01-01), 12 GB, Neuron USDK runtime **8.2.26**
(`/system_ext/lib64/libneuronusdk_adapter.mtk.so`). The tablet was on USB on the MS-02 (`~/platform-tools/adb`,
serial `R52XC00LL9K`), screen kept awake, charging (25 → 38 %), battery 30.1–30.2 °C. Probe app: the September
`tools/probes/litertlm-probe` (`com.whispereverywhere.probe`, LiteRT **2.1.1** + the v2.1.1 MediaTek dispatch /
compiler-plugin pair from `litert_npu_runtime_libraries_jit.zip`), driven by `drive.py` from the MS-02. Every run
is a fresh process. The Play/sideload copy of Whisper Everywhere was never touched. Raw logs and result JSON:
`~/.androidbuild/probe-logs/<tag>.{json,filtered.log,full.log}` on the MS-02.

## 0. The artefacts and how they were built (MS-02, Ubuntu 24.04, 24 cores, 125 GB, RTX 2000 Ada)

| Step | Tool | Result |
|---|---|---|
| Weights | `hf download openai/whisper-large-v3-turbo` (MIT) | 1.6 GB safetensors |
| Encoder export | `litert-torch 0.9.4` (`ai_edge_litert 2.2.0`, torch 2.13, transformers 5.17): the plain HF `WhisperEncoder` wrapped as signature `encode`, `args_0 f32[1,128,3000] → output_0 f32[1,1500,1280]`, static shapes, no quantization (`scratchpad/convert_encoder.py`) | `turbo_encoder_f32.tflite` **2,548,356,368 B**, sha256 `002e9055…`; convert 29.5 s; 1,693 ops; 999 GMAC per window. Host check with the LiteRT interpreter vs PyTorch on the probe input: max abs diff 0.109, **mean abs diff 2.9e-5** |
| AOT compile | `ai-edge-litert-sdk-mediatek 2.2.0` (the pip stub downloads MediaTek's host compiler, NeuroPilot v8_0_10 for MT6989, Linux x86 only) via `ai_edge_litert.aot.aot_compile(..., target=Target(SocModel.MT6989))` (`scratchpad/aot_mt6989.py`) | `turbo_encoder_f32_MediaTek_MT6989_apply_plugin.tflite` **1,276,346,288 B**, sha256 `74edf0a0…`; **19.3 s**; the whole graph became **one `DISPATCH_OP`** (`Partition_0`, bytecode 1,276,213,728 B stored outside the flatbuffer, `LiteRtStamp` = `MediaTek` / `mt6989`); the size halving = fp16 weights (`--relax-fp32`, the only flag the public path emits, which is also what Google's own MT6989 whisper-tiny carries) |

The probe input is the same on every backend and on the host: `java.util.Random(42)` floats in [-1, 1]
(bit-exact reimplementation on the host), so the output fingerprint identifies the unit that computed it.
PyTorch fp32 reference fingerprint: mean **-0.004241**, mean_abs **0.26062**, min -9.5136, max 10.6985, head
0.0921, 0.4606, -0.1859, 0.3554.

## 1. Results

| tag | artefact | accel | warm n | mean ms | median | min | max | sd | cold run ms | create ms | RSS/PSS MB after create | after warm | thermal start → end |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `t2_turbo_enc_aot_npu_1` | AOT MT6989 | NPU | 10 | **1,712.9** | 1,715.2 | 1,696.0 | 1,724.1 | 9.5 | 1,701.3 | 8,901.8 | 3,226 / 4,159 | 2,764 / 3,756 | 0 → 0 |
| `t3_turbo_enc_aot_npu_sustained` | AOT MT6989 | NPU | **120** | **1,717.1** | 1,717.2 | 1,700.0 | 1,729.0 | **5.4** | 1,699.7 | 8,397.1 | 3,387 / 4,707 | 1,578 / 3,778 | 0 → **1 (LIGHT)** |

(`mean` is run + `readFloat()` of the 1,920,000-value output; run-only is ~12 ms less: 1,702 / 1,705 ms.)

**Fingerprint on the APU (both runs, identical):** mean -0.004237, mean_abs 0.26031, min -9.625, max 10.8125,
head 0.0959, 0.4431, -0.1598, 0.3577, **0 NaN/Inf**. Every value is fp16-representable (e.g. `0.095947265625`),
which is the reduced-precision unit's signature; the agreement with the fp32 reference is at fp16 level
(mean_abs 0.26031 vs 0.26062; extremes within 1.2 %). That the APU and not the CPU computed it: the probe
requested `Accelerator.NPU` only (a refusal is an exception), `apuware_hidl: FastAPU is available`, and
`apuware_server: apusysSession_deleteInstance session from pid:<probe>` at close.

**Sustained:** 120 back-to-back windows = 3 min 26 s of continuous encoding: 1,700–1,729 ms, no drift (sd 5.4 ms),
battery 30.1 °C throughout; `PowerManager` thermal status reached 1 (LIGHT) only at the end. No throttling step
is visible in the series.

## 2. What `create` = 8.4–8.9 s is made of

`ServiceManagerCppClient: Waiting for service 'vendor.mediatek.hardware.neuropilot.neuronservice.INeuronService/default'`
at 15:51:21.076, then the adapter loads at 15:51:26.464 — **one 5 s binder timeout** (the AOT path instantiates
the adapter once; the September JIT path paid four). Then `dispatch_api.cc: Neuron SDK version: 8.2.26`,
`Found graph: Partition_0 / 1 subgraphs in the bytecode`, the v2.1.1 dispatch's FIRST load attempt
(`LoadFromDlaBytecode`) logs `The header of DLA is invalid … NeuronModel_restoreFromCompiledNetwork - Failed …
PrepareTensors: Currently we can't support dynamic shape … Fail to convert model`, after which the plain
restore succeeds silently (the same two-path behaviour §1.2 of the 2026-09-09 sheet documented for base) and
`FastAPU is available` follows at 15:51:27.240. So: ~5.0 s wait + ~1.3 s restore of the 1.27 GB bytecode + ~2 s
of LiteRT model load/partition bookkeeping. **The wait is removable in principle** (research lane, 2026-09-24:
it is `NeuronService_getNeuroPilotMagicNumber` in `libneuron_sys_util.mtk.so`; not declaring that library in the
manifest may skip it) — untested.

**Memory:** RSS 3.2–3.4 GB right after create (the 1.28 GB file mapped + the restored network), 1.6–2.8 GB
after warm; PSS 3.8–4.7 GB. On a 12 GB tablet this leaves room for the decoder (~0.9 GB f32 / ~0.45 GB fp16).

## 3. Against the bars

| | encode per 30 s window |
|---|---|
| **Tab S10+ APU, this sheet** | **1,713–1,717 ms** |
| Fold6, 8 Gen 3 Hexagon, shipped npu-turbo (`vad-hangover-retune.md`) | 1,778.9 ms |
| S23 Ultra, 8 Gen 2, admitted 2026-09-22 (37 % of the 8 s floor) | 2,472 ms |
| Tab S10+ Mali fp32 (correct output), 2026-09-10 | 3,463–3,507 ms |
| Tab S10+ CPU int8 4 thr, sustained, 2026-09-09 | 4,900–6,500 ms |
| September projection for turbo on this APU (FLOP-scaled from base) | 2,000–2,600 ms |

The tablet's APU beats the projection and lands between the two Hexagon phones that already ship the tier.

## 4. Not yet measured (next on the same rig)

The product-shaped PAIR in Qualcomm's HfWhisper IO (encoder emitting `k/v_cache_cross_i`, a KV-cached decoder
with the concat+slice cache update — no DynamicUpdateSlice, which MediaTek lacks) was exported the same afternoon:
`turbo_encoder_qcio_f32.tflite` 2,600,829,680 B and `turbo_decoder_qcio_f32.tflite` 901,986,016 B
(`scratchpad/export_pair.py`, transformers 4.56.2 + Qualcomm's vendored `model_adaptation.py`).

- The cross-KV **encoder compiled** for MT6989 in 20.3 s: `turbo_encoder_qcio_f32_MediaTek_MT6989_apply_plugin.tflite`
  **1,302,606,488 B**, and ran on the APU (`t4_turbo_encqcio_aot_npu_2`, probe `mode=sig`): **1,729.3 ms** warm
  mean (n=10, sd 4.7, run-only 1,717.6; cold 1,719.1; create 8,254.9 ms; RSS 3.9 GB / PSS 4.9 GB) — the eight
  `k/v_cache_cross_i` outputs `[20,1,64,1500]` / `[20,1,1500,64]` cost ~12 ms over the plain encoder. 0 NaN.
- Qualcomm's adapted **decoder was REJECTED whole** by the MediaTek plugin (0 of 2,025 ops selected): `Unsupported
  element type: 4` (INT64 — from `position_ids.to(torch.int64)` and the embedding gathers) and `Invalid
  BatchMatMulLayer: input rank is invalid` on every attention matmul of Qualcomm's single-head-attention form. So
  the decoder is re-written in the form the encoder compiled in — plain multi-head attention with rank-4 matmuls,
  int32 indices, Qualcomm's cache layout only at the boundary, concat+slice cache update
  (`scratchpad/export_decoder_mtk.py`), checked on the host against HF's own cached decoder at steps 0 and 1.

- That decoder **compiled** for MT6989 in 5.4 s: `turbo_decoder_mtk_f32_MediaTek_MT6989_apply_plugin.tflite`
  **584,862,184 B**. One `DISPATCH_OP` holds the four transformer layers and the logits projection; **24 small ops
  stay on the CPU** — the two embedding lookups (token, position) lowered as bounds-checked `GATHER_ND` with their
  `LESS/GREATER_EQUAL/SELECT/REDUCE_ALL` guards — exactly the op the research lane said MediaTek does not legalize.
  LiteRT runs them on the CPU in front of the dispatch; they cost nothing measurable.
- **Decode step on the APU** (`t5_turbo_dec_aot_npu_2`, `mode=sig`, synthetic inputs): **24.3 ms** per step
  (n=50, sd 2.9, min 19.3, max 31.5; run-only 23.3), cold 23.4, create 5,608.5 ms (the same single 5 s wait + a
  0.6 s restore), RSS 1.1 GB. Hexagon's shipped turbo decoder is 10.08 ms/token (Fold6).

## 4b. P0: the 5 s wait is the adapter's own, not the magic-number read

`t7_p0_nosysutil_enc_aot_npu` (probe rebuilt with `libneuron_sys_util.mtk.so` NOT declared; nativeloader's
`system_exposed_libraries` line confirms it absent): the AOT encoder, `create` **8,456.8 ms**, exactly one
`Waiting for service '…neuronservice.INeuronService/default'` line, warm 1,727 ms (n=5) — unchanged. The
timestamps: `litert_dispatch.cc: Loading shared library: …libLiteRtDispatch_MediaTek.so` at 16:43:49.879, the
wait at 16:43:49.885 → 16:43:54.900 (`didn't start. Returning NULL`), `Faild to get neuron serivce` (a MediaTek
library logging under the app's tag), `apuware_hidl: can't get getUtilsHidl service`, `ApuWare: open library
libapuwareutils_v2.mtk.so`, and only then `neuron_adapter_api.cc:113 Loading MediaTek NeuronAdapter .so from:
libneuronusdk_adapter.mtk.so` at 16:43:55.227 — which v2.1.1 logs AFTER a successful `dlopen`. So the 5 s is
spent inside the adapter's constructor connecting to a service this ROM never registers, before it falls back
to the `apuware` path that works. It is not removable from the app side; it is paid once per process (the
second model's `create` was 911 ms in t6), so the tier pays it on the boot prewarm thread (design §2.3).
The same source read settles the loader's rule for v2.1.1: the candidate loop has no `break`, so the LAST
loadable adapter wins — the driver check walks the same list.

## 5. END-TO-END: real speech through the pair, both on the APU — CORRECT

`t6_turbo_e2eqc_npu_npu_1`, probe `mode=e2eqc` (encoder `encode` → 8 cross-KV tensors → greedy KV-cached decode
loop, prompt `50258,50259,50360,50364`, EOT 50257, mask -1e4, both models `Accelerator.NPU` only, ids
detokenised on the MS-02 with the HF tokenizer). Mels are the September `jfk_mel128.bin` / `canary_mel128.bin`.

| utt | clip | tokens | EOT | encode ms | step ms (run+read logits) | cache copy ms (Kotlin, per step) | text |
|---|---|---|---|---|---|---|---|
| 0 (cold) | jfk | 26 | yes | 1,775 | 18.9 | 7.6 | ` And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country.` |
| 1 | canary | 10 | yes | 1,763 | 22.9 | 9.4 | ` 1, 2, 3, 4, 5.` |
| 2 | jfk | 26 | yes | 1,781 | 22.7 | 10.1 | (identical to utt 0) |
| 3 | canary | 10 | yes | 1,804 | 22.8 | 9.8 | (identical to utt 1) |

**Both transcripts are word-perfect**, deterministic across rounds, 0 NaN in every logits row (the `diag` lines
show the language/task/no-timestamps tokens chosen at t=0..2 with wide margins). Warm over utts 1–3: encode
**1,782.9 ms** (sd 20.6), step **22.8 ms** (sd 2.7), cache copy 9.9 ms. The cache copy is the probe's
`readFloat`→`writeFloat` round trip of 8 × 1 MB per step; a native engine ping-pongs the buffers and pays ~0.
Process cold start: encoder create 8,332 ms (the single 5 s binder wait + restore), **decoder create 911 ms** —
the second model in the same process pays NO wait, which settles the research lane's open question.

**Per-commit arithmetic** (the app's cadence row: encode + tokens × per-token): 20 tokens = 1,783 + 20 × 22.8 =
**2.24 s** (2.44 s with the probe's copy overhead), against the Fold6's 1.89 s and the S23's 2.47 + 20 × ~10 ms
≈ 2.7 s. **The Tab S10+ qualifies for the NPU tier at S23 class or better, on turbo, with correct output.**

This closes K0 (fp16 Whisper correctness on the MDLA, never checked before today), K1 (single-partition
encoder), K2/K3 (encode under the S23 bar at 1500 positions, RSS under 4 GB), and the decoder question (an
APU decoder exists and is 2.3× Hexagon's per token, with no CPU decoder needed).
