# tools/mtk-apu — whisper-large-v3-turbo for the MediaTek APU (Tab S10+ MT6989, Tab S11 MT6991)

The scripts that produced the artefacts measured in `docs/measurements/2026-09-24-tab-apu-turbo-encoder.md`.
They run on a Linux x86-64 host (the MS-02) because MediaTek's host compiler installs nowhere else.

| Script | venv | What it makes |
|---|---|---|
| `convert_encoder.py` | `venv` (litert-torch 0.9.4, ai-edge-litert 2.2.0, transformers 5.x) | the plain HF encoder as `encode`: `args_0[1,128,3000] → output_0[1,1500,1280]`, f32; host check vs PyTorch on the probe's `java.util.Random(42)` input |
| `aot_mt6989.py`, `aot_enc_qcio.py`, `aot_dec_mtk.py`, `aot_mt6991.py` | `venv` + `ai-edge-litert-sdk-mediatek 2.2.0` | `ai_edge_litert.aot.aot_compile(..., Target(SocModel.MT6989 / MT6991))` — one `DISPATCH_OP` per graph, fp16 bytecode (`--relax-fp32`, the only flag the public path emits) |
| `export_pair.py` | `venv-exp` (litert-torch 0.9.4, **transformers 4.56.2**) with `PYTHONPATH=qcwrap` (Qualcomm's vendored `hf_whisper/model_adaptation.py` + `utils/model_adapters.py`, `torch_typing_helpers.py`) | the encoder in Qualcomm's HfWhisper IO (plain HF body + cross-KV tail → 8 `k/v_cache_cross_i`). Its decoder half (Qualcomm's adapted decoder) is REJECTED by the MediaTek plugin (int64, BatchMatMul rank) — kept for the record |
| `export_decoder_mtk.py` | `venv-exp` | the KV-cached decoder step in the form MediaTek compiles: plain MHA, rank-4 matmuls, int32 `index_select`, concat+slice cache, Qualcomm's IO at the boundary; checked against HF's cached decoder at steps 0 and 1 (1e-5) |
| `detok.py` | `venv-exp` | detokenises a probe `e2eqc` result's ids with the HF tokenizer |
| `host_decode_diff.py` | `venv-exp` | P1b's host differential test: `liblitertasr.so`'s float decode loop re-written in Python over the UNCOMPILED f32 pair in the LiteRT interpreter, compared step by step against the tablet's `t8b` top-8 trace (argmax, top-8 overlap, logit gaps); `--fp16-io` rounds every boundary tensor to fp16; exit 0 / 2 (agree up to a near-tie precision decides) / 1 (a finding) |
| `litert_stamp_check.cpp` | system `g++` | the host check for `app/src/main/cpp/litert_stamp.h` (the chip check's `LiteRtStamp` parser): the same header over the real compiled files, under ASan/UBSan, with truncation and corruption passes |
| `stage_litertasr_into_probe.py` | any Python 3 (the PC) | copies the app's built `liblitertasr.so` + `libc++_shared.so` and the pinned `libLiteRt.so` 2.1.1 into the probe's jniLibs for `mode=litertasr` (P1b's device gate) |

Weights: `hf download openai/whisper-large-v3-turbo --local-dir ~/mtk-whisper/models/whisper-large-v3-turbo`.
The MediaTek host compiler comes under MediaTek's **NeuroPilot Express SDK licence** (downloaded by the pip
stub from a public S3 URL; no account, no NDA; revocable; indemnity; object-code redistribution only inside an
app for MediaTek chips) — an owner decision before anything ships.

On the tablet the artefacts are driven by `tools/probes/litertlm-probe` (`mode=sig` times any signature by
input/output names; `mode=e2eqc` runs real mel through the pair with the KV cache). `drive.py` always needs
`model=`; with USB on the MS-02 use `--serial R52XC00LL9K` and `~/platform-tools/adb`.
