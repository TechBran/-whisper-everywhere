# The MediaTek APU tier — implementation plan

**From:** `docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md` (revision 2, P0 complete) and
`docs/measurements/2026-09-24-tab-apu-turbo-encoder.md`. **Branch:** `feat/mediatek-apu-tier`, cut from main
after `feat/qnn-250-8gen1` (workstream A, 4.15.0/111) merges; versionCode 112+. The spike's tools and probe
(`spike/tab-apu-turbo`) merge into it first.

**Owner rulings in force (2026-09-24):** build the tier; turbo only on tablets; a driver version check; the
NeuroPilot Express licence read and accepted by the owner before the first Play upload. **Owner ruling
still needed:** the wording of the speed claim on MediaTek families (P3-2).

Every task below names its files, its tests and its done-condition. Tasks inside a phase are ordered; P1a
must land before P1b's Kotlin half and before P2; P1b's native half may proceed in a worktree in parallel
with P1a.

## P1a — the engine seam, Qualcomm-only, zero behaviour change (after A merges)

1. **`NpuStage` and `Refusal`.** New `npu/NpuStage.kt`: a closed enum of the stage names the backend's decline
   sites use today (`NpuDiagTest.kt:853-878` derives them by regex from `fallBackToCpuTier("…")` /
   `fallBackAndRun("…")`), plus `DISPATCH`. `data class Refusal(val stage: NpuStage, val detail: String)`.
   Test: `NpuStageTest` pins the enum's names equal to the regex-derived set (the derivation moves here).
2. **`NpuAsrEngine` + `NpuEngineFiles` + `NpuEngineDirs`.** New `transcription/NpuAsrEngine.kt` with the
   interface of spec §2.4 (`probe`, `prepare`, `init`, `encode(melF32: ByteBuffer)`, `decodeSegment`,
   `detectLanguage`, `epoch`, `release`, `lastError`, `setDiag`).
3. **`QnnAsrEngine`.** New `transcription/QnnAsrEngine.kt` wrapping `QnnAsrNative`: `prepare` = the skel stage
   (`NpuWhisperBackend.kt:446-455` moves here verbatim, stage `SKEL`); `init` = `nativeInit` + `nativeInputQuant`
   (stage `QUANT`); `encode` = `melToU16` + the DEBUG `melProbe` (`:633-671`) + `nativeEncode(quantised)`; the
   rest delegates. `qnn_asr.cpp` untouched.
4. **`NpuWhisperBackend` on the seam.** Constructor takes `engine: NpuAsrEngine`; every `QnnAsrNative.` call and
   the three QNN-shaped blocks leave the file; `fallBackToCpuTier(stage.name, detail)` keeps its literal shape
   for the diag readers. `NpuBackendSelector.backendFor` constructs `QnnAsrEngine` for every family (the vendor
   switch arrives in P2). Files: `NpuWhisperBackend.kt`, `NpuBackendSelector.kt`.
5. **Pins re-pointed, in the same commit:** `NpuDiagTest.kt:314-424` (mel → melToU16 → melProbe order, now in
   `QnnAsrEngine`), `NpuDiagTest.kt:853-878` (stage derivation → the enum), `NpuSkelPackagingTest.kt:505-585`
   (the skel stage, `stagedPathWithMarker(`, `family.skelAsset,` etc. → `QnnAsrEngine`), `NpuNativeContractTest`
   (every `QnnAsrNative` symbol quoted from the backend → the engine), `BubbleColoursWiringPinTest`-style call-site
   counters that name the backend, and `sourcePinnedInputs` gains `NpuStage.kt`, `NpuAsrEngine.kt`,
   `QnnAsrEngine.kt`. Done when `:app:testDebugUnitTest` ends at 0 failures with no guard weakened (each
   re-spec's commit message says what it now guards).
6. **Qualcomm regression gate.** A 4.15.x internal-track build; the owner runs canary + jfk on the Fold6 and
   S23 Ultra; the `nsp/lp/tokens` diag lines and the transcripts equal a capture taken on 4.15.0 before this
   refactor (captured in P1a-0, before task 1, and archived under `docs/measurements/raw/`). Done when equal.

## P1b — `liblitertasr.so`, the LiteRT engine (native half in parallel with P1a)

1. **Headers and build.** Vendor `litert_cc_sdk.zip` (v2.1.1) under `app/src/main/cpp/third_party/litert-2.1.1/`
   (headers only, licence file beside them). `CMakeLists.txt`: `add_library(litertasr SHARED litert_asr.cpp)`,
   headers from the vendored dir, link `log` and `dl` only, guarded like `qnnasr` (absent headers → skipped with
   the same loud message). Test: `NpuNativeContractTest` gains the CMake pins (library name, link set, guard).
2. **The runtime portal.** `litert_asr.cpp`: `dlopen("libLiteRt.so")` from `nativeLibraryDir`, `dlsym` of the
   entry points spec §2.5 lists; a missing symbol is a probe failure, never a crash. `LiteRtEnvironment` created
   once per process with `kLiteRtEnvOptionTagDispatchLibraryDir` = the staged directory, never destroyed.
3. **The driver probe (`nativeProbe(dispatchDir)`).** Walk LiteRT v2.1.1's candidate list in order with the same
   flags, keep the last that loads (`RTLD_NODELETE`, handle retained), `Neuron_getVersion`,
   `Neuron_getDeviceCount` / `NeuronDevice_getName`; return the `apu:` line or the refusal (spec §2.3 rules).
   Test: a host unit of the rule table (which winner/major → which verdict) over a fake loader.
4. **Models and buffers.** `nativeInit(encoderPath, decoderPath, spec scalars, socStamp)`: `LiteRtCreateModelFromFile`
   ×2; check the file's `LiteRtStamp` soc against `socStamp`; options NPU-only + MediaTek performance mode
   (measured: default vs `PreferSustainedSpeed`); `LiteRtCreateCompiledModel` ×2; buffers from requirements
   with joins for the eight cross-KV pairs and the self-KV `_in`/`_out` pairs (two sets); refuse on any failed
   join or on an output-name/order mismatch (`LiteRtGetSignatureOutputName` against the pack metadata's order).
5. **Encode.** `nativeEncode(melF32)`: lock the mel buffer, copy in, unlock, run, done — the cross-KV stays in
   the shared buffers.
6. **The float decode loop.** `nativeDecodeSegment` with the QNN contract: prompt fed through the same step path,
   the always-on and begin masks, greedy with the temperature ladder, timestamps emitted, `avg_logprob` and
   `p(nospeech)` in float (floor −inf, scale 1.0), a per-step non-finite check → refusal, the 199-slot window,
   alternating self-KV sets (swap = re-binding the two sets' roles, zero at segment start). `band_scan.h` gets a
   float instantiation for `nativeDetectLanguage`.
7. **Host differential test.** `tools/mtk-apu/host_decode_diff.py`: the same loop logic in Python over the f32
   decoder `.tflite` in the LiteRT interpreter, fed jfk's cross-KV (from the f32 encoder on the host), against
   the tablet's `t8b_appmode_topk_e2eqc.steps.jsonl` (31 steps): argmax equal at every step, top-8 sets equal,
   logits within fp16 tolerance. Then the same script against a JNI-driven trace from the probe app (task 9).
   Done when both agree.
8. **`LiteRtAsrNative` + `LiteRtAsrEngine`.** The Kotlin twin of `QnnAsrNative` and the engine: `prepare` stages
   the dispatch (P2-6's helper; until then a test-only path), `init`/`encode`/… delegate; `sourcePinnedInputs`
   gains both files.
9. **The device gate.** A probe-app mode `mode=litertasr` that loads `liblitertasr.so` (built from the app's
   CMake, copied into the probe's jniLibs by a script) with the product's shape — dispatch from
   `files/litert_dispatch/`, no plugin, one declared library, requirement-typed buffers, the app's prompt and
   masks — and reports create, encode, per-step time with one and with two self-KV sets, PSS, transcripts, and
   the per-step top-k trace. Done when: transcripts equal t8, timestamps paired, per-step ≤ 24 ms, and the
   per-family numbers are written into the sheet.

## P2 — census, gate, packs (after A merges and P1a lands)

1. **Census reshape, no behaviour change (its own commit).** `NpuSocFamily` gains `vendor`, `manufacturers`,
   `runtime: NpuRuntimeNeeds` (sealed), `tiers`; the six Qualcomm rows move their skel/htp triples into
   `Qnn(...)` and get `manufacturers = setOf("QTI","Qualcomm")`, `tiers = {npu, npu-turbo}`. Every reader the
   spec lists (§2.1) moves: `NpuPackLayoutTest.kt:54,462`, `NpuGateTest.kt:271-313`, `NpuPackMetadata.kt:88,135-139`,
   `build_asset_packs.py:511`, `NpuSkelPackagingTest` and `NpuFleetCensusTest` line sets. Done when the suite
   is green and the rendered `device_targeting_config.xml` is byte-identical to before.
2. **The gate on the row.** `NpuGate.familyFor` uses `family.manufacturers`; `SUPPORTED_SOC_MANUFACTURERS`
   becomes the derived union; `npuCapableDevice` becomes vendor-dispatched and, for MediaTek, reads the stored
   verdict (a `StateFlow`), with the probe started from `Application.onCreate` on a background thread and the
   verdict persisted per `Build.FINGERPRINT`. Tests: gate per vendor, verdict store round-trip, "unknown until
   probed" in the chooser's `produceState`.
3. **The `mt6989` row.** `NpuSocFamily("mt6989", "soc_mt6989", {"MT6989"}, {"Mediatek"}, LiteRtMediatek(8, "mt6989"),
   {npu-turbo}, evidence …)`; `PackArtifact(npu-turbo, mt6989)` with the AOT pair's digests/lengths, `parts`,
   `sourceBytes = null`; the turbo-only amendment written into the census KDoc with the owner's quote; the
   cross-product pins (`WhisperCatalogHelpersTest.kt:1203-1209`, `NpuFleetCensusTest.kt:313-320`) become
   vendor-scoped; `device_targeting_config.xml` gains the group; the reproducibility check (recompile →
   sha256 equal?) recorded in the sheet and the artefacts mirrored to the private store.
4. **Parts.** `PackArtifact.parts: List<PackPart>`; `NpuPackFetch.PACK_BY_TIER` → `packsFor(tier, family)`;
   the pure pack machine aggregates across parts (worst status wins, bytes summed, install only when every
   part COMPLETED, cancel/remove all parts, re-attach re-queries every part, one failed part retries alone);
   `NpuPackController` and `WhisperModelManager.installFromPack` read the encoder from part 1 and the decoder
   from part 2 of a two-part artifact; `metadata.json` in part 1 lists both entries. Tests: the machine over
   every combination of two part states; the Qualcomm one-part path byte-for-byte unchanged (existing tests).
5. **Modules and build.** `npu_turbo_mtk_enc` and `npu_turbo_mtk_dec` (on-demand) in `settings.gradle.kts`;
   `build_asset_packs.py` LOCAL source with the IO gate (tflite signatures vs `NpuModelSpec.TURBO`), the
   compiler stamp read from the file, CENSUS rows; `verifyNpuPacks` and `npuPackCensusRows` learn both modules;
   `NpuPackLayoutTest` learns two-module families; metadata v2 written for MediaTek rows only, readers accept
   1 and 2 (`NpuPackMetadata` tests for both).
6. **Runtime packaging.** `litertRuntime` configuration → generated jniLibs dir ordered before
   `merge*JniLibFolders`, sha256 pin, "coordinates agree" pin; the dispatch as an asset with
   `NpuAssetStage.stageIntoDir` (markers outside the scanned directory, own test); manifest
   `<uses-native-library android:name="libneuronusdk_adapter.mtk.so" android:required="false"/>`; a pin over the
   MERGED release manifest (usdk present; sys_util, .9, mgvi absent); `NpuSkelPackagingTest`-style pins for
   `libLiteRt.so` in lib/ and the dispatch asset digest.
7. **Selector and lifecycle.** `NpuBackendSelector.backendFor` picks the engine by `family.vendor`; the boot
   prewarm calls the engine's environment-only warm-up outside `NativeComputeGate`; `release` frees models and
   buffers only; `StartupRing` capacity per family (12 s for MediaTek rows) with its test.
8. **AAB.** `build_asset_packs.py build`, `verifyNpuPacks`, `bundleRelease` on the MS-02; size recorded
   (≈ 9.7 GB expected; per-pack ≤ 1.5 GB asserted).

## P3 — ship

1. **Copy and status.** Badge from the family's pair bytes; "phone" → device-neutral; onboarding size line from
   the family; HowToGuide names the Tab S10+/S10 Ultra; `apu:` line on the offer channel; copy census pins.
2. **The speed claim** on MediaTek families — the owner's wording (proposed: "the most accurate model this
   device can run, on its AI chip").
3. **Version** 4.16.0 / 112 (or the next free code), `ReleaseIdentityTest` paragraph; internal track.
4. **The Tab S10+ sheet** (owner session, logcat captured and read afterwards): offer line `soc=MT6989:pass`,
   the driver line, cold-arm with and without a warm adapter, cold-tap loss, per-commit encode+decode vs the P1b
   gate, canary + jfk equal to t8 with paired timestamps, a 30-minute session with thermal status and PSS beside
   a foreground app, no lmkd kill. Ship when every row passes.

## Later

- MT6991 (Tab S11/S11 Ultra): a Samsung Remote Test Lab session with the probe and the MT6991 pair; then a
  census row + a pack variant, with sizes, digests, compiler stamp and the Tab S11's Neuron version recorded.
- Converging the two decode loops once both are device-proven.
- A MediaTek dispatch for a newer LiteRT, if Google publishes one (or a from-source build on the MS-02).
