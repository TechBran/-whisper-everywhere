# Amendment to the 4.4.0 previewer plan — Play Asset Delivery for BOTH model packs (owner ruling 2026-09-10)

> Applies to `docs/superpowers/plans/2026-09-10-streaming-previewer.md` and its spec. Where this amendment and the
> plan disagree, this amendment wins. The controller wrote it from the owner's ruling and the code; the implementer of
> the amended task reads it first.

## The ruling, verbatim

> "we can deliver both of those in the same way, in the same go and I can test it all at once — along with the previewer."

Two things move onto **Play Asset Delivery (on-demand packs)**, the mechanism the NPU tiers already use, in the same
build as the previewer engine:

1. **The previewer model** — the four `streaming-zipformer-en-2023-06-26` files (encoder int8 71,083,163 B; decoder
   int8 1,307,236 B; joiner int8 259,335 B; tokens.txt 5,048 B; sha256s pinned in the plan's Task 2 and verified on
   2026-09-10). The plan's Task 2 (`StreamingPackManager` downloading from Hugging Face) becomes: **a pack module
   `preview_en`** carrying the four files, plus the same manager reading them from the pack's asset location, with the
   commit-pinned HF download kept ONLY as the non-Play fallback (debug/sideload builds, where `AssetPackManager` has no
   Play to talk to — exactly the "fetch affordance" the NPU tiers have).
2. **The read-aloud voice** — `kokoro-multi-lang-v1_0.tar.bz2` (349,906,910 B, sha256
   `c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298`, the 2026-09-08 archive verified compatible on
   2026-09-10: 53 voice slots unchanged + `em_santa` appended at id 53). **A pack module `tts_kokoro`** carrying the
   archive as-is; `TtsModelManager.verifyExtractInstall` runs unchanged on the pack's copy of the tar (same size gate,
   same known-good hash set, same extract/atomic-swap). The GitHub download stays as the non-Play fallback only. This
   closes the 2026-09-08 incident structurally: a voice update becomes a deliberate new AAB.

## Why PAD, and the shape to mirror (read these before writing anything)

- `app/build.gradle.kts` ~:230-236 (`assetPacks += listOf(":npu_turbo", ":npu_small")`) and ~:490-505 (the pack module
  build files pinned by the release-identity/pack-layout tests), ~:700-730 (why no two packs may share an asset path).
- `npu_turbo/build.gradle.kts`, `npu_small/build.gradle.kts` (packName, `deliveryType = "on-demand"`), their `.gitignore`
  and `src/main/assets/...` layout; `app/device_targeting_config.xml` is NPU-specific (SoC targeting) — the two new
  packs are NOT device-targeted: one variant, every device.
- `tools/build_asset_packs.py` — the pipeline that places pack payloads with digests pinned as data and re-verifies what
  landed; add the two packs to it the same way (download from the pinned URL → sha256 → place → re-verify), so a clean
  clone reproduces the packs.
- The runtime side: how the app fetches/locates an NPU pack (`AssetPackManager` usage — grep `AssetPackManager`,
  `AssetPackLocation`, `assetsPath`, the pack-state flow, the chooser's fetch affordance and its progress UI, the
  `NpuPackLayoutTest` that pins the pack layout). Reuse the same helper for the two new packs; do not fork it.
- Sizes: the AAB grows by ~350 MB (voice) + ~73 MB (previewer) — each far under the per-pack cap; the existing AAB is
  4.68 GB with two NPU packs, so total size is not the constraint.

## What changes in the plan's tasks

- **Task 2 (P1)** — rewrite: `StreamingPackManager` becomes pack-first. `installState()` checks the pack's asset
  location first (files present + exact sizes + the marker); if the pack is not fetched, the UI's install action
  requests the pack through the shared `AssetPackManager` helper (progress, failure, retry as the NPU fetch does); the
  HF download path remains behind a single `isPlayInstall()`-style discriminator (the NPU code already has one — reuse
  it) for non-Play builds. Verification (exact sizes, sha256, marker-last) is IDENTICAL on both sources — one function.
  Tests: the state machine over {pack present, pack absent, fallback present, corrupt} with fake locations.
- **New Task 2b** — `tts_kokoro` pack: the module, the gradle wiring, `tools/build_asset_packs.py` entry,
  `TtsModelManager` gains the pack source ahead of the download (same discriminator, same `verifyExtractInstall`),
  the Settings voice install action fetches the pack on Play builds; `TtsModelManagerPinTest` extended; the pack-layout
  pin test extended for both new packs.
- **Task 6 (P5)** — the copy: the previewer's install row says "included with the app" on Play builds (it is fetched,
  not downloaded from a third party); the fallback wording only on non-Play builds. The voice row likewise.
- **Task 8** — certification: the AAB must build with FOUR packs; `bundleRelease` is part of certification; the
  acceptance sheet gains Z12 (voice installs from the pack on the track build — the 2026-09-08 incident's row) and Z13
  (the previewer model fetches from the pack).
- **Identity**: the plan says versionCode 89 — that is now spent on the internal track. **4.4.0 = versionCode 90.** The
  identity bump is the controller's, after the merge.

## What must not change
Everything the plan already lists, plus: the NPU packs' layout and digests (the pack-layout test must go green with four
packs without any NPU row changing), and the download fallbacks stay functional for debug builds (the probe and dev
sideloads have no Play).
