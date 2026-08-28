# 4.0 — The NPU Tier (design, build-to-testable)

Owner mandate 2026-08-28: "build this out completely so we can finally test on the
NPU... keep building until we're ready to test." Scope = a build the owner can dictate
on, encoder AND decoder on the Hexagon, gated to capable devices, multi as the
untouched fallback. Ship-polish (Play packaging, fleet gating breadth, turbo, the 4.1
streaming layer) is explicitly OUT of this build.

Measured ground (the spike, 9 runs, `2026-08-28-npu-spike-g1-results.md`):
encoder 404.6ms sustained / 369.2 burst; cold 525ms; 26ms bands; zero thermal; the
raw-QNN owned seam is the architecture (owner decision); the sustained vote recipe is
final; the seven paid-for lessons bind this design.

## Goal

`NpuWhisperEngine`: PCM16 segment in → text out, with the encoder and decoder running
as QNN context binaries on the HTP, inside Whisper Everywhere, selectable as a tier,
falling back to multi (CPU) on any failure — loudly, never silently.

## The pipeline (per committed segment, the existing endpointer/commit flow unchanged)

```
PCM16 segment (existing capture/endpointer, 0.3-15s, zero-padded to 30s)
  → log-mel [80,3000]     whisper.cpp's own filterbank via a new JNI export
                          (the SAME mel the CPU path uses — one mel implementation
                          in the app, ever)
  → uint16 quantize       scale/offset read from the ENCODER'S OWN tensor metadata
                          (Qnn_QuantizeParams from the v2 tensor structs — never
                          hardcoded)
  → NPU encoder           one graphExecute → 24 cross-KV tensors (26.4MB, reused
                          buffers)
  → NPU decoder loop      autoregressive: tokens + self-KV + cross-KV → logits;
                          greedy decode; special-token handling (SOT, language,
                          task=transcribe, no-timestamps, EOT; suppress-non-speech)
  → detokenize            Whisper BPE vocab shipped as an asset; byte-level BPE
                          decode in Kotlin/JNI (no HF runtime dependency)
  → text                  into the existing segment-resolution flow (the funnel,
                          diagnostics, strip — untouched)
```

## Components

1. **`libqnnasr.so`** — the spike's seam productized (same repo, new cpp module
   beside whisper_jni): dlopen QNN libs, the metadata reader (v1/v2/v3, loud-fail),
   context-from-binary for encoder AND decoder, the sustained vote (create at session
   start, release at session end), deep-copied tensor descriptors (the UAF lesson),
   RAII aligned buffers, per-stage error strings to WE-DIAG. The decoder adds:
   per-step execute with self-KV cache in ping-pong buffers, position/token inputs
   per the decoder asset's enumerated IO (read at load, logged once).
2. **whisper.cpp mel export** — `we_pcm_to_mel(pcm) -> float[80*3000]` JNI wrapper
   over whisper.cpp's existing mel code (no new DSP code; the n_mel=80/3000-frame
   window matches whisper-small exactly).
3. **`NpuWhisperBackend`** (Kotlin) — implements the existing backend seam
   (load/transcribeStreaming/release shape): load = both contexts + vote arm;
   transcribe = the pipeline above; release = teardown. Emits the existing
   `segment-timing` line PLUS `npu: encode=<ms> decode=<ms> tokens=<n>` (new diag,
   format+emission pinned per the F-workstream rule).
4. **The tier**: `npu` in WhisperCatalog — MULTILINGUAL scope, gated (below), the
   190MB multi untouched as DEFAULT and fallback. ModelTierCopy: "Fastest
   multilingual — runs on your phone's AI chip" class copy (position words per the
   H3 rules). Chooser: visible ONLY on gated-capable devices, steered-to when
   capable + non-English locale (the H3/H4 steering integrates).
5. **Gating (testable-scope)**: `Build.SOC_MODEL` allowlist {SM8650, SM8650-AC} +
   the QNN provider probe (dlopen + providers==1) at tier-visibility time; the full
   fleet ladder + canary clip live in the ship-polish phase. ANY stage failure at
   session time → fall back to multi for the session + one WE-DIAG line + the tier
   card shows "unavailable on this device" (the H2 unsupported-card pattern).
6. **Model delivery (testable-scope)**: the encoder (127MB) + decoder (~215MB)
   as a zip via GitHub release, imported through the SAME SAF flow the spike proved
   (the import lives in the app's model manager for the npu tier; adb push
   alternate). Play Asset Delivery + SoC targeting = ship-polish.
7. **Manifest**: `<uses-native-library android:name="libcdsprpc.so"|"libadsprpc.so"
   android:required="false"/>` (required=false — the app must install everywhere;
   lesson 1).

## Decisions already made (do not reopen)

Raw QNN owned seam, no ORT (owner). Sustained vote only (measured +9.6%). Multi stays
default (owner). Loud fallback, never silent (the GPU-trap doctrine). One mel
implementation (whisper.cpp's). Quantization params from tensor metadata only.

## Open items this build ANSWERS by testing (not gates)

Decoder per-token cost on-device (AI Hub claims ~4.5ms/token; ~100 tokens ≈ 450ms —
if reality is far worse, the diag line shows it and the tier stays gated-experimental).
w8a16 WER: the owner's own dictation IS the first accuracy read; the formal harness
comes later. Peak RSS with both contexts + whisper.cpp multi resident (fallback
coexistence) — measured by the S-style session, ~500MB-class expected.

## Testing

JVM: the pure pieces (quantize math against known params, BPE detokenize against
golden token→text vectors, gating predicate truth table, tier catalog rules — the
existing census tests extend). Native/device: the spike pattern (loud stages, the
owner runs). The acceptance bar for "ready to test": the owner dictates a sentence
on the npu tier and reads it back correct, with `npu:` timings in the log.
