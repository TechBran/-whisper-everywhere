# NPU Model Lab — Which Whisper Models Actually Exist as SM8650 Context Binaries

**Asset survey + turbo download/verification, 2026-08-29.**
Follows the [2026-08-27 NPU decision brief](2026-08-27-npu-whisper-turbo-research.md) and the
[2026-08-28 G1 spike results](2026-08-28-npu-spike-g1-results.md).

Everything below was measured today against the live S3 bucket and the Hugging Face API — not
inferred from cards. Method is in §8.

---

## 0. Answer in one table

Per-model availability of a **precompiled QAIRT/QNN context binary for `qualcomm-snapdragon-8gen3`
(SM8650, HTP v75), public download, no login**:

| Model | Precompiled 8gen3 asset? | Precision | Mel | Vocab | Package (zip → unpacked) |
|---|---|---|---|---|---|
| **Whisper-Large-V3-Turbo-Quantized** | **YES** | w8a16 | **128** | **51866** | 859.8 MB → 1.02 GiB |
| **Whisper-Large-V3-Turbo** (float) | **YES** | float16 | **128** | **51866** | 2.02 GB → 2.06 GiB |
| **Whisper-Large-V3** (full, any precision) | **NO — does not exist** | — | — | — | not published, no source recipe |
| **Whisper-Medium** | **YES** | float16 only | 80 | 51865 | 1.52 GB → 1.54 GiB |
| **Whisper-Small-Quantized** *(we ship this)* | **YES** | w8a16 | 80 | 51865 | 293.6 MB → 341.7 MiB |
| **Whisper-Small** (float) | **YES** | float16 | 80 | 51865 | 572.8 MB → 600.0 MiB |
| **Whisper-Base** | **YES** | float16 only | 80 | 51865 | 180.8 MB → 192.5 MiB |
| **Whisper-Tiny** | **YES** | float16 only | 80 | 51865 | 105.8 MB → 111.9 MiB |
| Whisper-Small-V2 | **NO** | — | — | — | source recipe exists, no published assets |
| Distil-Whisper | **NO** | — | — | — | `chipset_assets` is empty `{}` |
| Whisper-{Tiny,Base,Small,Medium}-En | **NO** | — | — | — | no `release_assets.json` at all |

**Only two w8a16 Whisper models exist on AI Hub: Small-Quantized and Large-V3-Turbo-Quantized.**
Everything else is float16 only. There is no quantized Medium, no quantized Base/Tiny, and no
full Large-V3 in any form.

---

## 1. The download mechanism, written down

The G1 spike's no-login download was not a fluke — it is the documented public path. The chain:

1. **Discovery** — each model's chipset manifest lives on Hugging Face, *not* S3:
   `https://huggingface.co/qualcomm/<Model-Card-Name>/resolve/main/release_assets.json`
   (verified: the small manifest fetched here is byte-identical, 16,042 B, to the copy in
   `C:\Users\bastr\.androidbuild\npu-spike\model-cache\release_assets.json`.)
2. **Delivery** — that manifest holds absolute S3 URLs, publicly readable, no credentials:
   ```
   https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/
     {model_id}/releases/v{version}/{model_id}-{runtime}-{precision}-{chipset_key}.zip
   ```
   with `{chipset_key}` = the manifest key with `-` → `_` (`qualcomm-snapdragon-8gen3` →
   `qualcomm_snapdragon_8gen3`). The same URLs are also printed as a plain download table in each
   model card's README under "Option 1: Download Pre-Exported Models".

**The manifest path on S3 is *not* public** — `…/releases/v0.61.0/release_assets.json` returns
**403**. Only the zips are readable. Go through Hugging Face for discovery.

All Whisper assets are at **release `v0.61.0`**, all built with **QAIRT
`2.45.0.260326154327`** (+ ONNX Runtime 1.27.1 for the `precompiled_qnn_onnx` flavour), all
re-uploaded **2026-08-25**. Our shipped `qnn-runtime` 2.49.0 ≥ 2.45.0 satisfies forward-compat.

### Three runtime flavours ship the *same* binaries

Every model publishes three packages per chipset. They are **not** three different compilations:

| Flavour | Contents |
|---|---|
| `precompiled_qnn_onnx` | `encoder_qairt_context.bin`, `decoder_qairt_context.bin`, `metadata.json`, + two tiny ONNX EPContext wrappers (<20 KB) |
| `qnn_context_binary` | `encoder.bin`, `decoder.bin`, `metadata.json` — same bytes, renamed, no wrappers |
| `voice_ai` | same bins + `config.json` + **`vocab.bin`** |

**Proved by CRC-32, read out of the remote zip central directories:**

| | encoder | decoder |
|---|---|---|
| small-quantized `precompiled_qnn_onnx` | 132,927,488 B · CRC `27FB485A` | 225,316,864 B · CRC `D2888226` |
| small-quantized `qnn_context_binary` | 132,927,488 B · CRC `27FB485A` | 225,316,864 B · CRC `D2888226` |
| turbo-quantized `precompiled_qnn_onnx` | 775,831,552 B · CRC `5A398D89` | 295,854,080 B · CRC `B86CAA33` |
| turbo-quantized `qnn_context_binary` | 775,831,552 B · CRC `5A398D89` | 295,854,080 B · CRC `B86CAA33` |

Two consequences:

- **You never need to download a second flavour to change runtime.** The `precompiled_qnn_onnx`
  zip already contains a raw QAIRT context binary — which is exactly what the G1 spike consumed as
  `encoder_qairt_context.bin` through `QnnContext_createFromBinary`. Our raw-QNN direction costs us
  nothing in asset choice.
- **The published latency gap between the two flavours is pure harness overhead, not different
  weights** (turbo encoder 1421.0 ms via ORT/EPContext vs 1340.6 ms raw QNN — 5.7 %, same binary).
  The raw-QNN path we now use is entitled to the *lower* number, and to the
  `QNN_CONTEXT_BINARY` memory column (1–8 MB, not 63–75 MB) that §3-R3 of the brief flagged.

---

## 2. Full SM8650 asset matrix (measured today, all HTTP 200)

Unpacked context-binary sizes, read from remote zip central directories without downloading:

| Model | Prec | encoder.bin | decoder.bin | zip |
|---|---|---|---|---|
| Whisper-Tiny | float | 19,832,832 | 97,452,032 | 105,787,882 |
| Whisper-Base | float | 49,561,600 | 152,141,824 | 180,790,490 |
| **Whisper-Small-Quantized** | **w8a16** | **132,927,488** | **225,316,864** | **293,598,974** |
| Whisper-Small | float | 267,292,672 | 361,648,128 | 572,845,911 |
| **Whisper-Large-V3-Turbo-Quantized** | **w8a16** | **775,831,552** | **295,854,080** | **859,786,903** |
| Whisper-Medium | float | 729,468,928 | 926,945,280 | 1,517,554,109 |
| Whisper-Large-V3-Turbo | float | 1,755,942,912 | 452,481,024 | 2,018,865,656 |

*(zip column = `precompiled_qnn_onnx`. `qnn_context_binary` is 1.5–3.5 KB smaller, `voice_ai`
~197 KB larger. Immaterial.)*

### Chipset coverage differs by precision — correction to the brief

The 2026-08-27 brief states prebuilt assets exist for "4 phone SoCs … no 8 Gen 1, no 8 Gen 2, no
888". **That is true only of the w8a16 models.** The float models ship a *different* 12-key list:

| | Mobile SoCs covered |
|---|---|
| **w8a16** (Small-Quantized, Turbo-Quantized) | 8gen3, 8-elite-for-galaxy, 8-elite-gen5-for-galaxy, **7gen4** |
| **float** (Tiny/Base/Small/Medium/Turbo-float) | 8gen3, 8-elite-for-galaxy, 8-elite-gen5-for-galaxy, **8gen1** |

Ten IoT/auto/compute keys are common to both. The deltas are exactly:
float-only `{snapdragon-8gen1, sa8295p}`, w8a16-only `{snapdragon-7gen4, qcm6690}`.

Still no 8 Gen 2 and no 888 anywhere. But **a float path reaches 8 Gen 1 and a w8a16 path does
not** — that shifts the fleet-coverage arithmetic in §3 of the brief, and it should be checked
against the Play Console device catalogue alongside the G0 pull.

---

## 3. Published 8 Gen 3 latency, raw-QNN column

From the model cards (`QNN_CONTEXT_BINARY` rows — the flavour matching our runtime):

| Model | Prec | Encoder | Decoder/token | Bytes on disk |
|---|---|---|---|---|
| Whisper-Small | float | **97.3 ms** | 9.918 ms | 600 MiB |
| Whisper-Small-Quantized | w8a16 | 270.8 ms | 6.038 ms | 342 MiB |
| Whisper-Medium | float | 276.5 ms | 28.835 ms | 1.54 GiB |
| Whisper-Large-V3-Turbo | float | 466.4 ms | 7.961 ms | 2.06 GiB |
| Whisper-Large-V3-Turbo-Quantized | w8a16 | 1340.6 ms | 4.468 ms | 1.02 GiB |

Cost model is `encoder + n_tokens × decoder` (fixed 30 s window, so the encoder is a flat floor).
Against the Fold6 measured baseline of **F ≈ 2.3 s**:

| Segment (tokens) | Small-w8a16 | **Small-float** | Medium-float | Turbo-float | Turbo-w8a16 |
|---|---|---|---|---|---|
| 0.5 s (2) | 283 ms | **117 ms** | 334 ms | 482 ms | 1350 ms |
| 2 s (7) | 313 ms | **167 ms** | 478 ms | 522 ms | 1372 ms |
| 5 s (17) | 373 ms | **266 ms** | 767 ms | 602 ms | 1417 ms |
| 15 s (51) | **579 ms** | 603 ms | 1747 ms | 872 ms | 1569 ms |

**Whisper-Medium is dominated.** It is slower than Small-Quantized at *every* segment length while
costing 5.3x the bytes (1.54 GiB vs 342 MiB) and existing only in float. Its sole argument is
accuracy. It is not a speed option.

**Whisper-Small float remains the standout** and crosses over with w8a16 at ~14 s, as the brief
predicted. Two new reasons it is the cheapest thing to try, both from the shape table in §4: it is
**80-mel and vocab 51865 — bit-identical front-end and tokenizer to the Small-Quantized model we
already run.** Swapping it in is a file swap plus a layer-count constant. Nothing else changes.

---

## 4. IO shapes — measured from every `metadata.json`

Pulled from all seven remote zips via HTTP Range (no full downloads except turbo):

| Model | Prec | Mel | Dec layers | Heads | d_model | **cross-KV tensors** | Vocab | KV ctx | dec in/out |
|---|---|---|---|---|---|---|---|---|---|
| whisper_tiny | float | 80 | 4 | 6 | 384 | 8 | 51865 | 199 | 19 / 9 |
| whisper_base | float | 80 | 6 | 8 | 512 | 12 | 51865 | 199 | 27 / 13 |
| whisper_small | float | 80 | 12 | 12 | 768 | 24 | 51865 | 199 | 51 / 25 |
| **whisper_small_quantized** | w8a16 | **80** | 12 | 12 | 768 | **24** | 51865 | 199 | 51 / 25 |
| whisper_medium | float | 80 | 24 | 16 | 1024 | 48 | 51865 | 199 | 99 / 49 |
| whisper_large_v3_turbo | float | 128 | 4 | 20 | 1280 | 8 | 51866 | 199 | 19 / 9 |
| **whisper_large_v3_turbo_quantized** | w8a16 | **128** | **4** | 20 | 1280 | **8** | **51866** | 199 | 19 / 9 |

Every model hard-compiles a **200-token decode cap** (self-KV context 199 + 1 current) and a
**3000-frame / 30.0 s** encoder window. No dynamic axes anywhere.

> **Note on the "12" in small's tensor shapes.** Small's cross-KV shape is `[12, 1, 64, 1500]` and
> it has 12 decoder layers — a coincidence of two different 12s. The **leading dim is the head
> count**, not the layer count; the layer count is the *number of tensors* ÷ 2. Turbo makes this
> unambiguous: 20 heads in the shape, 8 tensors → 4 layers.

---

## 5. What was downloaded and verified

**Location:** `C:\Users\bastr\.androidbuild\npu-model-lab\`

```
whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8gen3.zip
    859,786,903 B   sha256 1e0e05c347ab96915f17dfcd1173fb1b78bed85bfcefd873e4ea31597913e297
extracted/metadata.json                    16,330 B
voice_ai_extras/vocab.bin                 357,313 B   (Range-extracted, no 820 MB re-download)
voice_ai_extras/config.json                   875 B
```

Source URL (public, no login, HTTP 200, `Last-Modified: Tue, 25 Aug 2026 04:05`):

```
https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/
  whisper_large_v3_turbo_quantized/releases/v0.61.0/
  whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8gen3.zip
```

**Zip inventory — `ZipFile.testzip()` returned OK, all entries CRC-clean:**

| Entry | Stored | Original |
|---|---:|---:|
| `encoder_qairt_context.bin` | 598,925,130 | **775,831,552** |
| `decoder_qairt_context.bin` | 260,856,033 | **295,854,080** |
| `metadata.json` | 1,162 | 16,330 |
| `encoder.onnx` (EPContext wrapper) | 872 | 4,658 |
| `decoder.onnx` (EPContext wrapper) | 2,022 | 13,863 |
| **TOTAL** | **859,785,219** | **1,071,720,483** (1.02 GiB) |

Download size matches the manifest HEAD exactly. Byte sizes match the brief's measurements.

### IO shapes from `metadata.json` — confirmed, not assumed

`chipset_attributes`: `htp_version 75`, `soc_model 57`, aliases `[qualcomm-snapdragon-8gen3,
sm8650]`, reference device Samsung Galaxy S24 (Family), `supports_weight_sharing: true`.
Identical block to the small package we already run on the Fold6.

**Encoder** — 1 input, 8 outputs:

```
input   input_features        [1, 128, 3000]  uint16   scale 4.746109e-05  zp 31605
output  k_cache_cross_{0..3}  [20, 1, 64, 1500]  uint8   (4 tensors)
output  v_cache_cross_{0..3}  [20, 1, 1500, 64]  uint8   (4 tensors)
```

**8 cross-KV tensors — confirmed, exactly as predicted.** 4 decoder layers × {k, v}. Small emits
24 (12 layers). Leading dim 20 = attention heads; 20 × 64 = d_model 1280.

**Decoder** — 19 inputs, 9 outputs:

```
in   input_ids                 [1, 1]              int32
in   attention_mask            [1, 1, 1, 200]      uint16
in   k_cache_self_{0..3}_in    [20, 1, 64, 199]    uint8   (4)
in   v_cache_self_{0..3}_in    [20, 1, 199, 64]    uint8   (4)
in   k_cache_cross_{0..3}      [20, 1, 64, 1500]   uint8   (4)  ← from encoder
in   v_cache_cross_{0..3}      [20, 1, 1500, 64]   uint8   (4)  ← from encoder
in   position_ids              [1]                 int32
out  logits                    [1, 51866, 1, 1]    uint16
out  k_cache_self_{0..3}_out   [20, 1, 64, 199]    uint8   (4)
out  v_cache_self_{0..3}_out   [20, 1, 199, 64]    uint8   (4)
```

**Integration deltas vs the small model we run today** — turbo is *not* a drop-in:

| | Small-Quantized | Turbo-Quantized |
|---|---|---|
| Mel bins | **80** | **128** — different front-end |
| Vocab | **51865** | **51866** — *different tokenizer* |
| Encoder outputs to marshal | 24 | 8 |
| Decoder inputs to bind | 51 | 19 |
| Encoder context binary | 127 MB | **740 MB** |

The vocab delta is the easy one to miss: large-v3/turbo add one token (`<|yue|>`, Cantonese) over
every other Whisper. A 51865-entry tokenizer will mis-decode turbo output.

### Bonus: `vocab.bin` is a usable tokenizer table

Risk #3 in the brief was hand-writing the 51866-BPE tokenizer. The `voice_ai` package ships one,
and it Range-extracts in seconds without pulling the 820 MB body. Format, decoded here:

- **50,257 NUL-terminated raw-UTF-8 byte strings**, concatenated, in token-id order
  (`idx 0 = "!"`, `1 = "\""`, `2 = "#"` … — GPT-2 byte-level BPE ordering, but stored as **raw
  bytes rather than the Ġ/ł unicode remapping**, i.e. already decoded).
- 50,257 = the GPT-2 base vocab. The remaining **1,609** ids up to 51,866 are Whisper's special
  tokens (100+ language tags, `<|transcribe|>`/`<|translate|>`, and 1,501 timestamp tokens) and
  are **synthesised procedurally, not stored** — standard Whisper layout.
- **Caveat to check before trusting it:** NUL-termination cannot represent a token whose bytes
  include `0x00`, which byte-level BPE nominally has. Verify id 188 / the `0x00` slot against a
  reference tokenizer before shipping.

`config.json` also confirms the intended asset triple (`encoder.bin` / `decoder.bin` /
`vocab.bin`), `qnn_version 2.45.0`, `arch 64`, and declares `streaming: false`,
`language_detection: true`, `confidence_scores: false`.

---

## 6. Full Large-V3 — not published, and what compiling it would take

**It does not exist.** Confirmed three ways:

1. The `qualcomm` HF org holds **248 models**; enumerating all of them yields 14 Whisper-ish
   entries and **no `Whisper-Large-V3`** (nor `-Large-V2`, nor `-Large`). Direct API probes of
   those names return **401** where every real card returns **200** — HF's response for
   nonexistent-or-private, against a verified control.
2. `qualcomm/ai-hub-models` (BSD-3-Clause, not archived, default branch `main`) contains **9**
   Whisper source recipes under `src/qai_hub_models/models/` — `distil_whisper`, `whisper_base`,
   `whisper_large_v3_turbo`, `whisper_large_v3_turbo_quantized`, `whisper_medium`, `whisper_small`,
   `whisper_small_quantized`, `whisper_small_v2`, `whisper_tiny`. **No `whisper_large_v3`.**
3. No `release_assets.json` exists under any large-v3 name.

### Feasibility of compiling it ourselves

The recipes show two very different costs, and the difference is the whole answer.

**A float export would be nearly trivial.** `whisper_medium/model.py` is ~50 lines: a subclass of
the shared `HfWhisper` template whose entire model-specific content is
`WHISPER_VERSION = "openai/whisper-medium"` plus a capabilities struct. A `whisper_large_v3`
float recipe is a copy of that file with the checkpoint string changed. Then an AI Hub account +
`submit_compile_job(device="Snapdragon 8 Gen 3", target_runtime=QNN_CONTEXT_BINARY)`.

**A w8a16 export is a different order of magnitude, and the blocker is calibration, not compute.**
`whisper_large_v3_turbo_quantized/model.py` does **not** quantize anything at export time. It
calls `get_calibrated_aimet_model()`, which *downloads Qualcomm's pre-computed AIMET artefacts* —
`encoder.aimet/model.onnx`, `model.onnx.data`, `model.encodings` — from their asset store. Those
encodings are the product of an AIMET calibration run Qualcomm did and published **per model**.
For a model they never published, they do not exist. You would have to:

- install `aimet-onnx` (Linux-only, the recipe hard-gates on `ensure_aimet_onnx_installed`),
- assemble a calibration corpus and run AIMET quantsim to produce your own `.encodings`,
- handle the encoder exceeding the **ONNX 2 GB protobuf limit** — the turbo recipe already carries
  a comment and an external-data (`model.onnx.data`) path specifically for this, and full
  large-v3's encoder is the same size,
- then submit a compile job per target SoC (`is_aimet: true`, `requires_aot_prepare: true`).

**Expected asset size — a partly *known* quantity, not a guess.** Turbo is a pruned large-v3 with
the decoder cut 32 → 4 layers; the **encoder is untouched** (the card says so verbatim, and our
metadata agrees: 128 mel, 20 heads, d_model 1280). So full large-v3's w8a16 encoder would be
**exactly the 775,831,552 B we just downloaded**. Only the decoder grows:

- turbo w8a16 decoder = 295.9 MB for 4 layers. Decomposing at d_model 1280 — embedding
  51866×1280 ≈ 66.4 MB, per-layer ≈ 26.2 MB — leaves ≈ 125 MB of fixed graph/activation overhead.
  The same decomposition against small-quantized (225.3 MB, 12 layers, d 768) leaves ≈ 72 MB, and
  125/72 ≈ 1.73 ≈ 1280/768 — overhead tracks **d_model, not layer count**, so ~125 MB carries over.
- large-v3 decoder ≈ 66.4 (embed) + 32 × 26.2 (layers) + ~125 (overhead) + ~16 (deeper self-KV)
  ≈ **1.05 GB**.

**Total ≈ 1.82 GiB unpacked / ≈ 1.46 GB zipped per SoC** — 2.1x turbo, 5.5x what we ship today,
and that is *before* four-SoC coverage (≈ 5.8 GB of bundle, past comfortable AAB territory).

**Recommendation: do not.** The cost is not the compile job, it is owning an AIMET calibration
pipeline and its unmeasured WER forever. If a large-v3-class model is ever wanted, the published
turbo-quantized asset is the supported route, and turbo already carries large-v3's full encoder —
the accuracy difference between them lives entirely in the 4-vs-32-layer decoder.

---

## 7. Licensing — zero delta

**The licence terms covering turbo and large are identical to the ones we already ship
Small-Quantized under. Nothing new to clear.**

Verified by fetching `LICENSE` from all seven cards and hashing:

| Model | LICENSE bytes | sha256 (first 16) |
|---|---|---|
| Whisper-Small-Quantized | 124 | `0650e5f2f2072a41` |
| Whisper-Large-V3-Turbo-Quantized | 124 | `0650e5f2f2072a41` |
| Whisper-Large-V3-Turbo | 124 | `0650e5f2f2072a41` |
| Whisper-Medium | 124 | `0650e5f2f2072a41` |
| Whisper-Small / Base / Tiny | 124 | `0650e5f2f2072a41` |

**All seven byte-identical.** Content in full:

> The license of the original trained model can be found at
> https://github.com/huggingface/transformers/blob/v4.42.3/LICENSE

All seven cards declare `license: apache-2.0` in front-matter. The delivery bucket, the release
version, and the AI Hub Proprietary License PDF are shared, so §6 of the 2026-08-27 brief transfers
unchanged — including the **"standalone basis"** clause and the Play-Asset-Delivery answer to it.

Deltas that are *not* licensing but travel with the model, and still bind if turbo ships:

- **No speech-to-English-translation claims** — turbo was fine-tuned without translation data.
- **Cantonese and Thai carve-outs** — turbo CER 10.5 % → 43.3 % on Cantonese vs large-v3.
- `config.json` declares `confidence_scores: false` and `streaming: false`.

One genuinely new observation: the **Voice AI** flavour's assets are downloadable from the same
public bucket under the same Apache-2.0 card, even though the *SDK* is QPM-gated. The brief treated
Voice AI as an unverified, gated path worth "one email". The **model-side artefacts, including
`vocab.bin`, are not gated at all** — only the runtime is. That is worth knowing before spending
the email on it.

---

## 8. Method

- **Enumeration:** `GET https://huggingface.co/api/models?author=qualcomm&limit=1000` → 248 models,
  filtered locally. (The `search=` parameter returns 0 results and must not be trusted.)
- **Manifests:** `release_assets.json` per card from HF `resolve/main`.
- **Sizes/liveness:** HTTP `HEAD` on all 21 8gen3 asset URLs — every one 200.
- **Inventories and CRCs without downloading:** a seekable file object over HTTP `Range` requests
  fed to Python's `zipfile`, which reads only the End-of-Central-Directory and the entries it is
  asked for. This is how `metadata.json` for all seven models and `vocab.bin` for turbo were
  obtained — a few hundred KB of transfer against ~7 GB of remote zips.
- **Turbo package:** downloaded in full, `testzip()` CRC-verified, sha256 recorded.
- Working scripts are in the session scratchpad; nothing was added to the repo but this file.

---

## 9. Corrections to the 2026-08-27 brief

| Brief said | Correct |
|---|---|
| Turbo w8a16 8gen3 zip = 859,783,378 B; small = 293,591,763 B | Those are the **`qnn_context_binary`** zips. The `precompiled_qnn_onnx` zips are 859,786,903 B and 293,598,974 B. The brief conflated two flavours (the numbers are otherwise right). |
| "Prebuilt assets exist for only 4 phone SoCs … no 8 Gen 1" | True for **w8a16 only**. Float models drop 7gen4 and **add 8gen1** + sa8295p. |
| "Raw QNN context binary — buys nothing over EPContext" | It buys the published **1340.6 ms vs 1421.0 ms** encoder and the **1–8 MB vs 63–75 MB** memory column, from a **byte-identical** binary. Post-G1 this is moot — we already went raw — but the asset choice is now proven free. |
| Risk #3: hand-write the 51866 tokenizer | A 50,257-token table ships in `voice_ai/vocab.bin`, publicly, 357 KB. Specials still synthesised. |
| §7 "also run turbo-w8a16 and small-float" in G1 | Both assets confirmed to exist and are cheap to add. **Whisper-Medium should be dropped from consideration** — dominated at every segment length, float-only, 1.54 GiB. |

---

## 10. Blockers and open questions

1. **No blocker on availability or licensing.** Every asset needed for a small-vs-small-float-vs-
   turbo comparison on SM8650 is public, no-login, and covered by terms we already ship under.
2. **Turbo is not a drop-in.** 128-mel front-end and a 51866 tokenizer are prerequisites — neither
   is needed for the small-float experiment, which is why small-float is the cheaper next
   measurement.
3. **`vocab.bin` NUL-byte handling unverified** (§5). Check before relying on it.
4. **Unchanged from the brief, and still the deciding numbers:** no published WER for any w8a16
   Whisper variant, and the float-faster-than-quantized inversion is now visible in a *third* model
   (Medium float encoder 276.5 ms ≈ Small-w8a16's 270.8 ms at 3x the parameters). Only device
   measurement settles it.
5. **Only turbo was downloaded**, per the task. Small-float (572.8 MB) is the one other asset worth
   pulling if the G1 session is extended — same mel, same vocab, same tokenizer, one constant
   changed.
