# 4.1 — The NPU Model Lab (design)

Owner mandate 2026-08-29: "let's do the turbo models so we can pick the best overall
model — from the best to the 190 MB that we have now. And then you can merge
everything." Executes on `feat/4.1-npu-model-lab` off the merge-ready 4.0 branch
(2a718ca); the merge-stack merge (3.5/3.6/3.7/4.0/4.1) follows the owner's pick.

## Goal

Three NPU-class tiers selectable in the chooser on gate-passing devices — the owner
A/Bs quality-vs-speed with real dictation and picks the winner:

| tier id | model | assets | mel | vocab | expected feel |
|---|---|---|---|---|---|
| `npu` (live) | Whisper-Small w8a16 | 127+215 MB | 80 | 51865 | ~11 ms/token (proven) |
| `npu-turbo` | Large-v3-Turbo w8a16 | 776+296 MB | 128 | 51866 | ~2-3× slower, large-v3's own encoder |
| `npu-small-float` (stretch) | Whisper-Small fp16 | 573 MB | 80 | 51865 | isolates the quantization cost |

Downloaded + verified: turbo at `C:\Users\bastr\.androidbuild\npu-model-lab\`
(sha256 1e0e05c3…, IO confirmed from its own metadata.json: input [1,128,3000]
uint16, 8 cross-KV [20,1,64,1500]/[20,1,1500,64] uint8, decoder 19 in/9 out,
logits [1,51866,1,1], self-KV depth 199, mask [1,1,1,200]). Large-v3 full does NOT
exist precompiled (no AIMET encodings published — self-compile rejected); Medium is
dominated (slower than small-q at every length); both are OUT.

PLUS: per-utterance language detection on auto (owner ruling — start in one
language, finish in another; explicit selection stays absolute).

## Decisions already made (do not reopen)

1. **`NpuModelSpec` parametrizes what 4.0 hardcoded** — mel bins, vocab size,
   language-band bounds, cross-KV census, IO dims, prompt token ids, max positions —
   ALL read from the asset's own metadata/tensors at load where possible (the
   branch's doctrine), with the per-tier expectations table (the F2 census guard's
   `expect`) carrying the spec. The alias guard, mask arithmetic, and right-aligned
   fill are ARCHITECTURE-IDENTICAL across both models (turbo's mask is [1,1,1,200],
   self-KV 199 — same shift register; verified from metadata).
2. **The 128-bin mel arrives via a filterbank asset, not a donor ggml.** The Q2b
   mel-only loader reads exactly magic→hparams→filterbank and stops — so a file
   truncated at the end of the filterbank IS a valid mel-only ggml. Ship
   `melbank-128.bin` (~103 KB, extracted once from the large-v3-turbo ggml's
   prefix) as an app asset; `initMelOnly` loads it unchanged. The 80-bin path keeps
   the installed-tier donor (nothing changes for `npu`). One mel implementation,
   ever — only the filterbank DATA source varies, per tier.
3. **Vocab**: a second vocab asset for turbo (51866 entries; large-v3 adds `yue` —
   the language band becomes 50259..50358, 100 entries). WhisperBpeDecoder is
   already data-driven; WhisperTokens gains a per-family table (the census alarm
   extends: turbo's band, turbo's specials — SOT/EOT/task ids SHIFT in large-v3's
   vocab; take them from the model family table, verify against vocab content at
   asset-build time, pin both families' boundary ids).
4. **THE ARMING EPOCH lands FIRST (F4's named fix)** — the second npu-class backend
   makes npu→npu-class switches real; `exactlyOneTierIdRoutesToTheNpuBackend` goes
   red the moment `npu-turbo` routes, BY DESIGN. The fix: an arming epoch so a
   rebuilt session's queued teardown can never destroy a successor's init
   (happens-before, not source order). No lab task may proceed past that red
   without the epoch landed.
5. **Hash-verified imports** (the final review's shipping-posture item): the import
   flow gains sha256 verification against the catalog's per-file hashes for ALL
   paired tiers — the lab ships two new ~GB-class pairs; size-only was ruled
   insufficient at that scale. (adb-push stays hash-exempt: dev route.)
6. **Per-utterance auto, NPU scope**: on the npu-class tiers with auto selected,
   each segment's detection resolves that segment's language — no session latch
   (detect ≈ 5 ms there). CPU tiers keep the 3.7 latch (detection is expensive on
   CPU; 3.8's onboarding makes explicit selection the default UX anyway). The diag
   langNote per segment already carries the honest provenance.
7. **Delivery**: two new zips via GitHub release (turbo ~860 MB, small-float ~573 MB
   if built), top-level entries, imported through the existing SAF flow. The skel
   packaging question (I5: qnn-runtime already ships 25.3 MiB incl. the unreachable
   17.9 MiB skel) is decided IN this plan: either the import zips carry the skel or
   the gradle dep gets trimmed — one packaging answer, owner-visible in the plan
   review.
8. **Chooser copy**: position words per the H3 rules; turbo's card says what it is
   ("Best quality — Large-v3's encoder on your AI chip") without outranking
   language the owner hasn't approved; multi stays default; nothing auto-selects.

## Acceptance

The owner dictates on all three (two if small-float is dropped) NPU-class tiers plus
multi, compares transcripts and feel, and picks the default lineup. The diag lines
(`npu: encode= decode= tokens=`, `mel:`, langNote) are the measurement. The 4.0
run-book's watch items apply to turbo's first run (the census guard now returns a
structured error naming any surprise).

## Non-goals

Streaming partials (4.1-streaming is the separate owner-prompt arc); Play packaging/
asset delivery (ship-polish); fleet gating beyond SM8650; the 3.8 Gemini/language
work (own branch, after).
