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
manifest may skip it) — untested here. P0's t7 seemed to refute it and P1b's device gate confirmed it: with the
library really absent from the MERGED manifest there is no wait at all (§4b, the correction).

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

## 4b. P0: the 5 s wait — read here as the adapter's own; CORRECTED at P1b's device gate: it was the magic-number read

**Correction (2026-09-24, P1b's device gate — runs `p1b_litertasr_kv0` and `p1b_litertasr_kv1`, the probe built at
478ecf2; logs on the MS-02 under `~/.androidbuild/probe-logs/`).** This section's conclusion was wrong. The 5 s was
LiteRT v2.1.1's NeuroPilot magic-number read through `libneuron_sys_util.mtk.so` — §2's research-lane reading
(`NeuronService_getNeuroPilotMagicNumber`) — and not the adapter's constructor, and the product's configuration
never pays it:

- **No wait, in either run: zero `Waiting for service` lines.** The driver line was `apu:
  driver=libneuronusdk_adapter.mtk.so 8.2.26 want=8 devices=3 device=mtk-gpu+mtk-dsp+mtk-mdla walk=169ms query=0ms
  pass` (`probe_ms=169.7`; the walk was 169–239 ms across the two runs), and that walk IS a `dlopen` of
  `libneuronusdk_adapter.mtk.so`, the first in the process; `.9`, `mgvi` and `<dispatch dir>/libneuron_adapter.so`
  did not load. Then: the environment in 1 ms, both files' stamps matched, the encoder opened in 1,729 ms and the
  decoder in 297 ms, the encoder compiled on the NPU alone in 1,404 ms (fully accelerated) and the decoder on
  NPU|CPU in 452 ms, 29 buffers (every dispatch buffer AHWB), the APU check's decoder step 35.6 ms — `init_ms`
  **4,044.6** (kv0) / **3,665.4** (kv1) for the whole arm, against 8.3–8.9 s for t2–t7's first create alone. PSS
  after init 5,494,090 kB (RSS 4,208,812).
- **What differed from t7 is the MERGED manifest.** t7's probe had the `libneuron_sys_util.mtk.so` declaration
  commented out of its SOURCE manifest, but the litert 2.1.1 AAR's own manifest declares it (with `.9` and `mgvi`)
  and the merger put it back. The `system_exposed_libraries` line read below as "confirms it absent" was cut at
  230 characters, before that library's name (the coordinator's re-read of the t7 log, 2026-09-24); the P0(c)-ii
  build's line shows it present (§4c, t10/t11 — recorded there, without the conclusion). The P1b probe (2beb43b
  onward) strips the three AAR declarations from the MERGED manifest (`stripAarMediatekDeclarations`, which fails
  the build if one survives), so these two runs are the first in this project in which `libneuron_sys_util.mtk.so`
  was really undeclared — and the wait went with it.
- **The t7 timeline does not contradict this.** v2.1.1's adapter loader reads the magic number before its
  candidate loop, so the read also falls between the dispatch load and `Loading MediaTek NeuronAdapter .so` — the
  placement taken below for "inside the adapter's dlopen".
- **What stands:** the candidate loop's last-wins rule (a source read, unaffected), and not declaring
  `libneuron_sys_util.mtk.so` — which is what removes the wait, rather than the no-op it looked like. **What falls:**
  "not removable from the app side", "paid once per process", and the design's need to hide 5 s behind a prewarm
  (design §2.3, §2.9, §4 and §6 P0(a), corrected the same day).

What was believed at P0, and why, as it was written:

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

## 4c. P0(c): the product's loading shape works — dispatch from app storage, no plugin, one declaration

The probe that produced every number above loaded the dispatch from `nativeLibraryDir` with the compiler
plugin beside it and nine MediaTek libraries declared. The product will stage the dispatch into
`filesDir/litert_dispatch/`, ship no plugin, and declare one library. Runs `t10_p0c_product_shape_enc` and
`t11_p0c_product_shape_e2eqc` (17:31) used a probe build whose source manifest declares only
`libneuronusdk_adapter.mtk.so`, with `libLiteRtDispatch_MediaTek.so` (sha256 `f47bd9c0…` as extracted from
the APK — the same bytes the September build pinned) pushed into `files/litert_dispatch/` and
`dispatchdir=` pointing there:

| run | what | create ms | encode / step | result |
|---|---|---|---|---|
| t10 | AOT encoder, `mode=litert` | 7,531.3 (one 5.2 s wait) | warm 1,725.1 ms (n=5) | `Loading shared library: …/files/litert_dispatch/libLiteRtDispatch_MediaTek.so`; no plugin applied; `FastAPU is available`; fingerprint as t2 |
| t11 | the pair, app decode mode | encoder 7,610.9, decoder 619.9 | — | ids **identical to t8** for jfk and canary (timestamps included) |

So: LiteRT finds the dispatch by absolute path in an app-storage directory; the compiled models need no plugin;
the adapter dlopens its own `apuware` dependencies from the system namespace without the app declaring them
(`nativeloader: Extending system_exposed_libraries: libneuronusdk_adapter.mtk.so:libneuron_sys_util.mtk.so`
was the whole exposed set). One thing to note for the product: `libneuron_sys_util.mtk.so` appears in that
exposed set although the source manifest no longer declares it — the litert 2.1.1 **AAR's own manifest**
declares it (and `.9` and `mgvi`) and the merger added it back. The product ships no AAR (it extracts
`libLiteRt.so` only), so its merged manifest carries exactly what it declares; the design pins the merged
manifest for that reason. The 5 s wait was present in every configuration here — every one of which, it turned
out, still had `libneuron_sys_util.mtk.so` merged in: that declaration WAS the wait (§4b, the correction).

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

**Memory of the resident pair (t6 snapshots):** PSS **4.80 GB** after both creates, **4.85–4.95 GB** during and
after the utterances (RSS 1.2–3.5 GB; PSS is the number to budget — it counts the device memory the APU
mapped that RSS does not). Battery 30.0 °C, thermal 0 throughout.

### 5b. The reference in the APP's decode mode (t8, 17:12) — also correct, with timestamps

The design judges pointed out that t6 used the probe's old discipline (a 4-token prompt with
`<|notimestamps|>`, raw argmax). Since 4.11 the app sends the 3-token prompt `[SOT, <|lang|>, TRANSCRIBE]`,
masks `WhisperTokens.BASE_SUPPRESS` (82 ids) + the six large-v3 control ids + `<|notimestamps|>` at every
generated step, masks `[220, EOT]` at the first generated step, and **emits timestamps** (its only sentence
timing). `t8_appmode_e2eqc_npu_npu` ran exactly that (probe `suppress=…89 ids`, `beginsuppress=220,50257`,
`tokens=50258,50259,50360`), both models on the APU:

| utt | clip | tokens | EOT | encode ms | step ms | timestamp tokens (s) | text |
|---|---|---|---|---|---|---|---|
| 0 (cold) | jfk | 28 | yes | 1,826 | 19.1 | `<\|0.00\|>` … `<\|11.00\|>`, paired, monotonic | ` And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country.` |
| 1 | canary | 12 | yes | 1,784 | 23.8 | `<\|0.00\|>` … `<\|2.56\|>` | ` One, two, three, four, five.` |
| 2 | jfk | 28 | yes | 1,804 | 20.9 | as utt 0 | as utt 0 |
| 3 | canary | 12 | yes | 1,862 | 18.3 | as utt 1 | as utt 1 |

Warm over utts 1–3: encode 1,816.8 ms (sd 40.5), step **21.0 ms** (sd 3.2), cache copy 9.3 ms. Deterministic
across rounds. (The canary reads "One, two, three" under the app's masks and "1, 2, 3" under t6's raw argmax —
the begin-suppress of the leading-space token changes the first choice; both are the spoken digits.) This is
the acceptance reference for the tier: text equality plus paired, monotonic timestamps.

This closes K0 (fp16 Whisper correctness on the MDLA, never checked before today), K1 (single-partition
encoder), K2/K3 (encode under the S23 bar at 1500 positions, RSS under 4 GB), and the decoder question (an
APU decoder exists and is 2.3× Hexagon's per token, with no CPU decoder needed).

### 5c. P1b's host differential test (MS-02, no tablet) — the float loop agrees up to near-ties

`tools/mtk-apu/host_decode_diff.py` re-runs the app-mode loop `liblitertasr.so` implements (3-token prompt, the 89-id
always-on mask and `[220, EOT]` begin mask as −inf on float logits, greedy, timestamps emitted, the 199-slot
right-aligned window, caches fed back) over the UNCOMPILED f32 pair in the LiteRT 2.2.0 interpreter, and compares it
step by step with t8b's per-step top-8 (fp16 on the APU):

| clip | compared steps | argmax equal | top-8 overlap | max \|Δlogit\| on shared ids | ids | first divergence |
|---|---|---|---|---|---|---|
| jfk | 30 (t=0..29) | 29 | 5–8 of 8 (29 of 30 steps ≥ 6; t=10 is 5) | 2.08 (t=14) | text identical; closing stamp `<\|10.40\|>` on the host vs `<\|11.00\|>` | t=29, timestamp vs timestamp: host margin 0.58, tablet margin 1.39 |
| canary | (no trace) | — | — | — | host ` 1, 2, 3, 4, 5.` vs tablet ` One, two, three, four, five.`, stamps identical (`<\|0.00\|>` … `<\|2.56\|>`) | t=3, the first word: host `" 1"` 10.7571 vs `" One"` 10.7432 — margin **0.014** |

Every text step of jfk agrees. The two divergences are both near-ties on the host's own logits (under 1 logit, the
script's `--fp16-tolerance`), i.e. precision decides them; the canary's `" 1"`/`" One"` tie is the same choice t6's
raw argmax made the other way on the tablet (§5). `--fp16-io` (every boundary tensor — cross-KV, self-KV, logits —
rounded to fp16) moves the logits by ≤ 0.03 and changes neither divergence, so the 1–2 logit gaps are the APU's fp16
COMPUTE inside the graphs (32 encoder layers relaxed to fp16), not storage. Host stats: jfk nsp 0.0000, lp −0.087,
rung 0, 31 steps; canary nsp 0.0000, lp −0.163, 15 steps. The acceptance stays device-vs-device (P1's gate against
t8, fp16 against fp16); this run shows the loop logic — prompt, masks, cache shift, window — is the tablet's.

## 6. P1's device gate — `liblitertasr.so` in the product's shape, on the tablet: PASS (20:19)

Runs `p1b2_litertasr_kv0` and `p1b2_litertasr_kv1` (probe APK sha256 `87193e4c…`, `liblitertasr.so` 170,480 B,
`libLiteRt.so` 2.1.1 `6ddc1b3d…`, the dispatch staged in `files/litert_dispatch/` as the APK copy `f47bd9c0…`;
merged manifest holding the single MediaTek declaration; the app's own `LiteRtAsrNative`, `WhisperTokens` and
`NpuDecodePolicy` compiled in). The engine's own LiteRT C-API path: encoder requested on the NPU alone, decoder on
NPU + CPU, 29 unstrided AHWB/host buffers from the compiled models' requirements, the float decode loop with the
app's 3-token prompt, both masks and timestamps, `nativeDetectLanguage` before every decode, three rounds of the
two clips, then a release and a re-arm (a second `nativeInit` + one window). Raw logs and JSON archived on the PC
under `~/.androidbuild/probe-logs/tab-apu-2026-09-24/`.

| | kv0 — two self-KV sets, re-bound per step | kv1 — one set, native copy per step |
|---|---|---|
| driver probe (`apu:` line) | 207 ms, `libneuronusdk_adapter.mtk.so 8.2.26 want=8 devices=3 mtk-gpu+mtk-dsp+mtk-mdla` **pass** | 192 ms, same |
| `Waiting for service` lines | **0** | **0** |
| init (both restores, buffers, the APU check) | 3,555 ms | 2,752 ms |
| encode, warm mean (n=5) | 1,725.4 ms (sd 14.6) | 1,718.2 ms (sd 5.0) |
| decode step, warm mean (n=5 utterances) | 32.5 ms (30.0–35.8; utt 0 33.3) | **30.0 ms** (25.6–33.2; utt 0 23.4) |
| jfk decode, 28 tokens | 930–1,109 ms | 727–999 ms |
| transcripts (all 7 utterances incl. after the re-arm) | `matches_reference=true`, stamps paired + monotonic | same |
| nsp / lp / rung / terminator | 0.000 / −0.087 (jfk), −0.114 (canary) / 0 / EOT | same |
| PSS after init → during → after release | 4.79 GB → 4.5–4.8 GB → **94 MB** | 5.29 GB → 4.5–5.2 GB → 101 MB |
| re-arm: init / PSS / first utterance | 3,558 ms / 5.55 GB / step 45.7 ms | 3,354 ms / 5.39 GB / step 29.7 ms |
| thermal / battery | 0 / 26.1 °C throughout | same |

**What this settles.**
- The product's engine transcribes exactly what the reference decode mode transcribed (§5b), on every run, and the
  re-armed session does too. `ent=nan` is the documented "window not reached" value (26 text ids; the entropy
  window needs 33), identical to the Qualcomm engine's report on short segments.
- **There is no 5 s wait in the product's shape** (see §4b's correction): the driver walk is ~200 ms, and a cold
  arm is the two restores — ≈ 2.8–3.6 s — well inside the StartupRing's 6 s.
- **The self-KV default is the copy strategy (kv1):** 30.0 ms per step against 32.5 with re-binding, steadier
  (the re-bind arm's first steps after a fresh init were 45.7 ms — the dispatch's re-registration of 16 buffers),
  and a simpler binding lifetime. A 20-token commit is 1,718 + 20 × 30 ≈ **2.32 s**, S23 class.
- Memory: about 4.5–5.3 GB PSS while armed, released cleanly to ~100 MB; the re-arm climbs to 5.4–5.6 GB. On the
  12 GB tablet this is the number the 30-minute session (P3) watches beside a foreground app.

The plan's P1b done-conditions hold except one number: per-step ≤ 24 ms was the Kotlin probe's figure with
runtime-created buffers; the product engine's 30 ms includes the 8 MB cache copy and the locked logits read, and it
is the figure the cadence row now uses.

**Addendum (20:31, run `p1b3_litertasr_default`, probe APK `0cbc8865…`, `liblitertasr.so` 171,056 B `a39500a7…`).**
The default flipped in 7099161 and run with no `kvstrategy` argument: the init line reports `kvstrategy=1`,
probe 165 ms, init 3,632 ms, re-arm 3,410 ms, encode warm 1,723.2 ms (sd 5.3), step warm mean 32.4 ms
(23.1–36.6, sd 5.4 — a wider spread than the 20:19 kv1 run's 25.6–33.2; the two runs sit inside each other's
range, so the per-step figure to quote is "≈30 ms, 23–37"), all seven utterances `matches_reference=true`,
zero waits. The 20-token commit estimate stays ≈ 2.3–2.4 s.
