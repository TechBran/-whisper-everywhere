# v69 / v73 NPU turbo and the Galaxy XR — research synthesis

Date: 2026-09-04 (late). Repo: `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere`. `main` = `3290bfc`
= **4.3.2 / versionCode 86, in production** (`app/build.gradle.kts:84-85` on the checked-out tree; the
memory note's "4.3.1/83 unpushed" index line is stale). The working tree is checked out on
`spike/turbo-cpu-gpu-s23` at `0b37c17` (11 commits over `main`, versionCode untouched, spike
properties inert by default); a second worktree holds `feat/accessibility-optional` at `1175bb4`
(4 commits — **4.3.3 in flight**). Nothing in this document was run on a device by the synthesis;
adb was forbidden tonight, so every device reading is carried from the brief and from the owner's
later-same-day entries in the ship-track memory note, labelled **[DEVICE]** and dated. Where the
brief, a memory note, a map, an analysis or a repo doc disagrees with the code or a primary source,
the primary wins and §5 names the loser.

Inputs: four maps (`v69xr-map-{npupipeline,permissions,webqnn,webxr}.md`), three analyses
(`v69xr-analysis-{npu,xr,plan}.md`) and their three adversarial refutations, all in the session
scratchpad under `review/`. Re-verified by the synthesis tonight, independently of all ten:
the vendor `metadata.json` of every published w8a16 Whisper `qnn_context_binary` zip (24 of 24,
Range-read via `review/htp_probe.py`, output saved as `review/htp_probe_synth_rerun.txt`); HTTP
HEADs of the `qcs8550-proxy` and `7gen4` zips in both flavours from the live manifests; the AOSP
`android14-release` lines cited for the accessibility gate; every `file:line` below; Google's
Android XR input-settings help page and Qualcomm's AI Hub FAQ.

**Line-number basis.** `app/build.gradle.kts` and `app/src/main/cpp/whisper_jni.cpp` are cited on
the spike tree (`0b37c17`); on `main` they sit ~31 lines earlier (main's `versionCode` is at
`:53-54`, the spike's at `:84-85`). Every other file cited is byte-identical between `main` and the
spike (`git diff --stat main..HEAD` touches only the gradle file, the JNI, `WhisperNative.kt`, the
bench tests and `tools/`). Files cited as `wt-ao/…` are on `feat/accessibility-optional`.

---

## 1. DIRECTION A — NPU turbo for v73 (S23 class) and v69 (Galaxy XR)

### 1.1 Verdict per target

**S23 Ultra class (SM8550 / SM8550-AC, Hexagon v73, `soc_model` 43): GO — and it needs no compile
job.** The brief, the memory note and the census ledger all say "Qualcomm publishes no precompiled
turbo for 8 Gen 2" (`NpuFleetCensus.kt:353-356`). That is true **by chipset key** and false **by
`soc_model`**: the vendor's `qualcomm-qcs8550-proxy` w8a16 packages — turbo *and* small — are
compiled for `htp_version 73, soc_model 43`, and Qualcomm's own header assigns 43 to **SM8550**
(`app/src/main/cpp/include/QNN/QnnTypes.h:1846`); QCS8550 has its *own* id, 66 (`:1868`). Re-read
tonight out of the live zips:

```
turbo  qualcomm_qcs8550_proxy  bytes=859,784,275  {'htp_version': 73, 'soc_model': 43, 'name': 'qualcomm-qcs8550-proxy'}
small  qualcomm_qcs8550_proxy  bytes=293,593,616  {'htp_version': 73, 'soc_model': 43, 'name': 'qualcomm-qcs8550-proxy'}
```

(`review/htp_probe_synth_rerun.txt`; manifests
https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized/raw/main/release_assets.json and
https://huggingface.co/qualcomm/Whisper-Small-Quantized/raw/main/release_assets.json, release
`0.61.0`.) The `precompiled_qnn_onnx` flavour — the one `tools/build_asset_packs.py:228-235`
consumes — HEADs at **859,787,788 B** (turbo) and **293,600,815 B** (small), `Last-Modified
Tue, 25 Aug 2026 04:04:28 / 04:05:32 GMT` — the same hash-stable re-upload event every existing
family's gate pins (`tools/build_asset_packs.py:74`). So the S23 target is a vendor-built,
vendor-calibrated pair for the S23's own SoC id, and the runtime half is already in the APK: the
V73 stub rides in `lib/` (only the four census *skels* are excluded, `app/build.gradle.kts:242-246`)
and `libQnnHtpV73Skel.so` (17,909,588 B, sha256 `7be4f8a4…`) is extracted into assets because
`7gen4` is a census family (`:665`; `NpuFleetCensus.kt:152-162`). What the S23 lacks is a census
row, a device-group entry, measured digests, and one device load. Caveat that is real: v73 has
**never armed on silicon** in this program — the `7gen4` row's evidence ends "no device evidence"
(`NpuFleetCensus.kt:160-161`) — so the first S23 arm is the first v73 execution in the project's
history, and a failure inside the loader is not evidence about the model.

**Galaxy XR (SXR2230P, Hexagon v69, `soc_model` 53): CONDITIONAL, and LAST.** No published w8a16
Whisper binary targets any v69 part: all 24 w8a16 zips probed tonight are htp 73/75/79/81; the only
v69 Whisper artifact of any precision is the *float* 8 Gen 1 pair (turbo 1,624,628,895 B, htp 69 /
soc 36 — over Play's 1.5 GB per-pack cap and the slowest published configuration; not a route). A
self-compile is demonstrably feasible: Qualcomm's own release pipeline compiled and profiled the
w8a16 turbo on the **Samsung Galaxy S22 5G (SM8450, v69)** — encoder job `jp2wl22xp` **1,527.758 ms**
(11,151 layers), decoder `jpyx699r5` **8.047 ms**, both `Passed`, QAIRT `2.45.0.260326154327`
(https://raw.githubusercontent.com/qualcomm/ai-hub-models/main/src/qai_hub_models/models/whisper_large_v3_turbo_quantized/perf.yaml;
saved `review/raw/whisper_large_v3_turbo_quantized.perf.yaml:1046-1060`). Three things bound it:
(a) the compile target has to be the hosted **Galaxy S22** — "Snapdragon XR2 Gen 2" is an
*unsupported* reference row whose perf numbers are the S22's job IDs duplicated
(`review/raw/similar_devices.yaml:1-11,79-87`: "Maps unsupported devices to similar (supported) AI
Hub devices … `reference_chipset`: the chipset whose perf numbers are duplicated onto this device";
`Snapdragon XR2 Gen 2: chipset: qualcomm-qcs8450, reference_chipset: qualcomm-snapdragon-8gen1`;
https://raw.githubusercontent.com/qualcomm/ai-hub-models/main/src/qai_hub_models/similar_devices.yaml);
(b) the binary will therefore be stamped `soc_model` **36** (SM8450), not 53, and AI Hub exposes no
compile option to change it (`--qnn_options` has no `soc_model`/`htp_arch` key —
https://workbench.aihub.qualcomm.com/docs/hub/api.html), so the XR's first arm is a same-arch /
different-`soc_model` load that Qualcomm documents in neither direction (§1.5); (c) it is **moot
until Direction B yields a product on the headset** — today the headset can host neither the bubble
nor any typing route (§2), so a ~2 s v69 turbo would serve an in-app panel that does not exist yet.
Preconditions, all three: an XR product decision (§3 M3 = go), the S23 recipe proven (M4), and the
190 MB CPU tier timed on the headset and beaten.

### 1.2 The compile path, step by step

**S23 — no compile; a `measure` run and a device load.**

- *Step 0 (desk, done tonight):* the metadata read above. `htp_version == 73`, `name ==
  qualcomm-qcs8550-proxy`, `soc_model == 43` for both tiers.
- *Step 1 (desk, minutes):* `python tools/build_asset_packs.py measure` with a temporary fifth
  `FAMILIES` entry `"8gen2": ("qualcomm-qcs8550-proxy", 73, "soc_8gen2")` (`:122-127`) and the two
  `EXPECTED_ZIP_BYTES` rows (`:135-141`: turbo 859,787,788; small 293,600,815). Every existing gate
  applies unchanged — HTTP 200, `Last-Modified` containing `25 Aug 2026` (`:74`), exact
  `Content-Length`, CRC, then the vendor `metadata.json` gate (`:330-360`): `htp_version` 73,
  self-described `name` equal to the key, and the per-graph IO census equal to the `npu-turbo`
  / `npu` spec rows. **No local-artifact mode is needed for this family** — it is a vendor key like
  the four in the table. Output: the raw encoder/decoder byte counts and sha256 digests, measured,
  never transcribed. (Note for the reader of `NpuFleetCensus.kt:180-186`: the compressed v73 proxy
  zip, 859,784,275 B, is within 1 KB of the v75 `8gen3` zip, 859,783,378 B, while the v73 `7gen4`
  zip is 871,114,782 B — "HTP v73 packs weights less densely" is not what the bytes say; the
  7 Gen 4's larger encoder is specific to that SoC's compile. The per-family installed-size gate
  (`NpuAssetImport.kt:42-54`) handles either outcome; `measure` settles the raw bytes.)
- *Step 2 (S23 paired, ~30 min, in the spike session that already uninstalls the Play copy):*
  build D = `main` + an `8gen2` census row (`socModels = {"SM8550-AC", "SM8550"}` — the memory
  note's 2026-08-30 field data records the owner's S23 Ultra as `SM8550-AC`; confirm with `getprop
  ro.soc.model` at pairing; `htpVersion = 73`; `skelAsset = "libQnnHtpV73Skel.so"`, shared with
  `7gen4`) plus a `soc_8gen2` device group, with the count/digest pins loosened *on the throwaway
  branch only*. Back up BYOK keys, `adb uninstall` the Play copy (`tools/spike/README.md:58-70`:
  the Play copy carries Google's signing key, every local build the upload key,
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; the keys die with the uninstall), install D, push the pair
  via `/data/local/tmp` + `run-as` (`docs/superpowers/sdd/2026-08-29-npu-model-lab/acceptance.md:20-43`;
  the owner's `adb push` route is hash-exempt by design, `NpuAssetImport.kt:52-54`), select the
  tier, and read `logcat -s WE-DIAG`: `socModel=43` (`qnn_asr.cpp:1035-1039`), `produced by QAIRT
  build 2.45…` (`:1047-1048`), `contextCreateFromBinary OK` **for both graphs** (`:1264-1270` — the
  per-family R7 re-proof, §1.5), the canary verdict, encoder ms over the README's 300 s drift loop
  with `dumpsys battery` temperatures. There is no instrumented test that drives `nativeInit` with
  explicit paths (`grep -rniE "qnn|npu" app/src/androidTest` → none; the G1 harness is a
  gitignored external project, `docs/superpowers/research/2026-08-28-npu-spike-g1-results.md:83-86`),
  so the census row is the cheapest gate bypass. Optional second arm in the same sitting: the
  `7gen4` pair (soc 86) — the census's pre-registered cross-load (`NpuFleetCensus.kt:345-350`),
  now a curiosity rather than the route.
- *Step 3 (only if step 2 fails to deserialise or fails the canary/WER):* an AI Hub export of
  `whisper_large_v3_turbo_quantized` against the hosted, real `Samsung Galaxy S23 Ultra` (exact
  `soc_model` 43; hosted General devices run "on that exact hosted device" —
  https://workbench.aihub.qualcomm.com/docs/hub/devices.html). Recipe and pins as for the XR below;
  this branch *does* need the modified `measure` tool (§1.3).

**Galaxy XR — a compile is unavoidable.**

- *Step 0 (headset, seconds, before any sideload):* `getprop ro.soc.manufacturer` — the gate's
  second input, **never read on an Android XR build** (`NpuGate.kt:79-84` requires `QTI` or
  `Qualcomm`, exact; `Build.SOC_MODEL` is `ro.soc.model` by construction —
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Build.java,
  https://android.googlesource.com/platform/system/libsysprop/+/refs/heads/main/srcs/android/sysprop/SocProperties.sysprop);
  `ls /vendor/lib64/libcdsprpc.so /vendor/lib/rfsa/adsp/ /vendor/dsp/cdsp` (the FastRPC path;
  the manifest's `<uses-native-library required="false">`, `AndroidManifest.xml:66-83`, makes an
  absence a silent probe failure); `dumpsys battery | grep temperature`; `pm list features | grep
  -E "touchscreen|faketouch|microphone|xr"`.
- *Step 1 (desk, free, hours):* Qualcomm ID → AI Hub Workbench → Settings → API token
  (https://workbench.aihub.qualcomm.com/docs/hub/getting_started.html; "completely free to use" —
  https://workbench.aihub.qualcomm.com/docs/hub/faq.html); `pip install qai-hub==0.55.0
  qai-hub-models==0.61.0` (PyPI latest; the 0.61.0 wheel was uploaded 2026-08-25T04:06:48Z, the
  same morning as the S3 re-upload the census gates on — https://pypi.org/pypi/qai-hub-models/json).
  Export `whisper_large_v3_turbo_quantized` for device **`Samsung Galaxy S22 5G`** (the S22 Family
  pool; `review/raw/utils_device.py:379`, `review/raw/scorecard_device.py:326-327,602`) — **not**
  "Snapdragon XR2 Gen 2". The quantization is *reused, not reproduced*: Qualcomm's v3 AIMET
  encodings are public (~3.65 GB; webqnn map §E HEAD-verified the five files). Two version facts to
  pin in the job: both `--target_runtime` values the wrapper still declares
  (`review/raw/export_v0.61.0.py`: `QNN_CONTEXT_BINARY`, `PRECOMPILED_QNN_ONNX`, `VOICE_AI`) are on
  AI Hub's deprecation path — `qnn_context_binary` "deprecated for 6 months and will soon be removed"
  (2026-07-20), `precompiled_qnn_onnx` deprecated 2026-05-28, replacement `submit_compile_job()` +
  `submit_link_job()` (https://workbench.aihub.qualcomm.com/docs/hub/release_notes.html); and
  `--qairt_version` — every fleet blob is QAIRT 2.45.0 (added 2026-04-14), the app's runtime is
  2.49.0 (`app/build.gradle.kts:882,886`; "latest" on AI Hub since 2026-08-17). Compile at 2.45 if
  `get_frameworks()` still offers it, else 2.49; never `latest` unchecked. Record the profile job's
  encoder ms against 1,527.8 and read the stamped `soc_model` from the output (expect 36).
- *Step 2 (device, after Direction B's Play-copy evidence is captured — the sideload requires
  uninstalling the Play copy):* spike build = `main` + the V69 packaging edits (§1.3) + an
  `xr2_gen2` census row keyed `SXR2230P`, `htpVersion = 69`; push the pair; arm; read the same log
  lines as the S23, plus: whether the HTP performance vote is honoured on Android XR (the decisive
  G1 variable: 1,007 → 405 ms, `g1-results.md:15,39`), PSS, and encoder ms under the drift loop
  with the fan audible or not.
- *Kill criteria:* `contextCreateFromBinary` refuses the SM8450-stamped blob on `soc_model` 53 →
  a local QAIRT SDK `qnn-context-binary-generator` with `soc_model 53 / dsp_arch v69` is the only
  exact-stamp route (the FAQ: "be sure to specify the correct `soc_model` to
  `qnn-context-binary-generator`") — a second toolchain, priced only then; sustained encoder above
  ~3.2 s (the SLOW row) or the vote denied → the 190 MB CPU tier stays the headset's answer; canary
  or WER off the Fold6's → same.

### 1.3 What the app must gain

**Fifth family `8gen2` (S23 class) — a new AAB by construction.** The pipeline map §6's fourteen
edit sites, of which the non-trivial ones: `NpuFleetCensus.kt:118-163` (the row), `:188-301` (two
`PackArtifact` rows), `:352-361` (`SM8550`/`SM8550-AC` leave `CPU_BY_CENSUS` with the measurement
date — rule 2, `:105-108`); `app/device_targeting_config.xml` (a fifth `<config:device-group
name="soc_8gen2">` with both `QTI` and `Qualcomm` selectors, exactly the shape of the four there);
`tools/build_asset_packs.py` `FAMILIES` + `EXPECTED_ZIP_BYTES` + `CENSUS`; `app/build.gradle.kts:747-756`
`npuPackCensusRows` (+2); a second `#group_soc_8gen2` payload dir per module (structurally
uncommittable, `npu_*/.gitignore`). Plus three the maps did not have to face: (1)
`NpuFleetCensusTest.familyIdsPackGroupsAndSkelAssetsAreAllDistinct` (`:78-92`) asserts
`skelAsset` distinctness across families and **breaks first** for a second `libQnnHtpV73Skel.so`
family — its intent ("one family's skel under another family's silicon") is not violated by two
families sharing one arch, so the pin becomes per-HTP-version; (2) the "four architectures, four
DISTINCT digests" pin (`:157-159`) and the count-of-four pins (`:60`, `:94`, `:113`) move the same
way; (3) the CPU-ledger pin (`:224-229`, "seven checked-absent strings") drops to five. No change
to `qnn_asr.cpp` (nothing native names an arch or SoC; `deviceCreate(nullptr, …)`, `:2693`),
`NpuModelSpec`, `WhisperCatalog`, `NpuPackFetch.PACK_BY_TIER`. APK unchanged (the V73 skel is
already in assets). The device-targeting config is packaged *inside* the bundle (`bundle {
deviceTargetingConfig = file("device_targeting_config.xml"); deviceGroup { enableSplit = true;
defaultGroup = "other" } }`, `app/build.gradle.kts:284-291`;
https://developer.android.com/google/play/device-targeting — beta, SoC selectors need API ≥ 31,
"packaged with your app bundle"), so a fifth family is a new AAB at a new versionCode.

**Sixth family `xr2_gen2` (headset) — all of the above plus:** un-exclude the V69 **stub** and move
the V69 **skel** into `extractQnnSkel` (`app/build.gradle.kts:250-253` today excludes both halves;
the rule at `:217-234`: one stub per family in `lib/`, one skel per family in assets; skel bytes
and sha256 **measured out of `qnn-runtime-2.49.0.aar`**, never transcribed — the task refuses a
wrong pair); `NpuSkelPackagingTest.noUncoveredArchitectureLosesItsExcludes` hard-codes
`listOf("V68", "V69")` (`:289-300`) → `listOf("V68")` — the one test edited by hand; +12 MB of
base-APK assets fleet-wide (skels live in the base module, not the packs); a **modified `measure`
tool** — a self-compiled pair has no manifest entry, no vendor `metadata.json` and no S3
`Last-Modified`, so it fails today's first gate; the IO census must be read from the binaries
themselves (the app already does so at arm, `NpuModelSpec.kt:227-263`) and provenance becomes the
AI Hub job IDs in the row's `evidence`; the row's KDoc must name the hazard that `SXR2230P` is also
the Quest 3's part string (TechInsights via the webxr map) — harmless while the binary is the right
one for the die, a hazard only for a cross-loaded stamp; and whether Play resolves `#group_` SoC
variants for an Android XR install is undocumented (Play endorses PAD for XR apps in general —
https://developer.android.com/develop/xr/package-and-distribute) — the SAF import
(`docs/superpowers/specs/2026-08-29-fleet-onboarding-design.md:47-49`) is the fallback either way.

### 1.4 Expected performance, with its basis

The only in-app/harness pair the program owns is the Fold6 (8gen3, v75): encoder **1,778.9 ms**
mean over 57 shipped-build segments, decode `44.4 ms + 10.08 ms/token`, **F = 1.89 s**
(`docs/superpowers/plans/2026-09-02-vad-hangover-retune.md:63-69`) against AI Hub's S24 row
(`qnn_context_binary` 1,317.681 ms / `precompiled_qnn_onnx` 1,421.004 ms) → app/harness **1.35**
(qcb) or **1.25** (pqo). Everything below scales that ratio; every input other than the Fold6's is
a harness number on substitute hardware, so these are **projections, not measurements**.

| target | AI Hub w8a16 harness (QAIRT 2.45) | projected in-app encoder / 30 s | assumption that carries it |
|---|---|---|---|
| Fold6 (reference) | S24: 1,317.7 qcb / 1,421.0 pqo | **1.78 s measured**; F 1.89 s | — |
| S23 Ultra (v73, soc 43) | QCS8550 (Proxy): **1,663.8** qcb `jpezz931p` / 1,763.4 pqo `j574xzyr5`; decoder 5.83 ms | **2.2–2.4 s** (1,663.8 × 1.35 = 2.25; 1,763.4 × 1.25 = 2.20; 1,763.4 × 1.35 = 2.38); ≈ 13 ms/token; **F ≈ 2.35–2.5 s**. Floor 3.0 s if the proxy hardware is not 8 Gen 2 (7 Gen 4 QRD 2,224.5 × 1.35) | the `soc_model 43` stamp says the "(Proxy)" substitute is an SM8550 device, but AI Hub's own caveat is that proxy "metrics … will vary" (devices page) |
| Galaxy XR (v69, soc 53) | S22 5G: **1,527.8** pqo `jp2wl22xp` (11,151 layers vs 5,674 elsewhere — a different lowering); decoder 8.05 ms; small encoder 361.4 vs S24 267.4 | **1.9–2.1 s** (1,527.8 × 1.35 = 2.06; Fold6 1,778.9 × the w8a16 v69/v75 ratio 1,527.8/1,421.0 = 1.075 → 1.91); ≈ 18 ms/token; small ≈ 0.55 s | the XR2+ Gen 2 HTP clocks like an 8 Gen 1's — Qualcomm claims only "15% higher GPU and 20% higher CPU max frequency" for the Plus and says nothing about the NPU (https://www.qualcomm.com/products/mobile/snapdragon/xr-vr-ar/snapdragon-xr2-plus-gen-2-platform); and no HTP contention from the platform's hand/eye/passthrough loads, which is unknown |

Consequences that hold under the projection: both targets' F sits above the **2,000 ms** turbo
cadence floor (`CommitCadencePolicy.kt:112`, an owner ruling whose arithmetic assumes F 1,890 ms)
that the Fold6 clears, so the backpressure governor's **3,200 ms** SLOW row (`:131`) engages under
staccato speech — adaptive, not a blocker; a per-family F row or the governor-as-answer is a
decision for after the measurement. Both are an order of magnitude better than the CPU turbo the
owner dropped (10–22 s projected on the S23 Ultra,
`docs/superpowers/research/2026-09-04-turbo-cpu-gpu-research.md`) and in the band of the CPU small
tier's measured 2.3–3.5 s fixed cost (`g1-results.md:30`). The webqnn map's "~2.8× slower on v69"
was read off the *float* file (S22 1,292.4 vs S24 466.4 ms) and does not transfer: w8a16 turbo is
already slow on v75 for reasons that do not scale with arch, and the w8a16 v69/v75 ratio is 1.075.
Thermal: the headset is actively cooled (reviews record a fan under load —
https://www.spacebar.news/samsung-galaxy-xr-review/, https://vrarwiki.com/wiki/Samsung_Galaxy_XR);
16 GB RAM (`MemTotal 16,066,680 kB` [DEVICE]) vs ~1.02 GiB mapped for turbo is not a constraint.

### 1.5 Risks specific to Direction A

- **Same-arch / different-`soc_model` loading is undocumented in both directions.** The FAQ's only
  sentence is scoped to "binaries built for older devices on newer chips … performance may suffer"
  (https://workbench.aihub.qualcomm.com/docs/hub/faq.html); the compile guide says a context binary
  "is expected that the model will be deployed to the same device"
  (https://workbench.aihub.qualcomm.com/docs/hub/compile_examples.html). The census's
  `QNN_COMMON_ERROR_INCOMPATIBLE_BINARIES` expectation (`NpuFleetCensus.kt:345-350`) is equally
  undocumented. **For the S23 this risk is gone** — the proxy pair is stamped 43 — and it survives
  only for the XR (36 on 53). Do not pre-label the arm's outcome; the native seam logs `socModel=`
  and refuses nothing on a mismatch (`qnn_asr.cpp:1035-1039`); the only refusal is a failed
  `contextCreateFromBinary` (`:1264-1270`), legible in `logcat` by name.
- **R7 is a per-family re-proof, not an open risk.** The comment "the decoder has never been
  deserialised under 2.49" (`qnn_asr.cpp:1045-1046`) is stale: the shipped 4.3.0 build (2.49.0 AAR
  in `718002a`) ran 57 npu-turbo segments with the decoder on the Fold6 (retune plan `:63-66`;
  57 `WE-DIAG … decode:` lines in `C:/Users/bastr/.androidbuild/capture-vad-headroom.txt`). Each
  new family re-proves both graphs with one `contextCreateFromBinary OK` line per graph.
- **v73 has never armed** (`7gen4`: "no device evidence"). A failure in `loadInterfaces →
  deviceCreate → contextCreateFromBinary` on the S23 is a runtime-path fact first; read the stage.
- **`ro.soc.manufacturer` on the XR is unread.** A spelling outside `{QTI, Qualcomm}` denies the
  tier regardless of any pack (`NpuGate.kt:54,79-84`). The HMD characteristic plays no part in the
  gate.
- **The XR compile stamps 36.** If 36-on-53 refuses, the only exact route is a local QAIRT SDK —
  QPM download, x86_64 Linux host, the ONNX + encodings conversion — a second toolchain.
- **Play device targeting is beta**; the app gate is the correctness authority by design. Whether
  `#group_` variants resolve for an Android XR install is unknown (SAF import fallback).
- **The AI Hub target-runtime values are deprecated.** If the 0.61.0 wrapper stops emitting
  `precompiled_qnn_onnx`, the compile+link route produces the same context binaries but the
  `measure` tool's flavour assumptions change.
- **Accuracy is a comparison, not an assumption:** identical model and encodings, per-arch lowering
  only — run the canary corpus and the Fold6's WER on the same clips per family.
- **The S23 is a phone; the XR shares its NPU with the platform.** Spike README §7's temperature
  discipline and the 300 s drift loop apply verbatim; on the XR the vote may not be honoured.

### 1.6 Owner actions (Direction A)

| # | action | for | cost |
|---|---|---|---|
| 1 | Pair the S23 Ultra (wireless-debugging pairing code; `R5CW12FVZXJ` at `192.168.1.156` refuses plain `adb connect` — memory note); `getprop ro.soc.model ro.soc.manufacturer` (expect `SM8550-AC` / `QTI`) | S23 | minutes |
| 2 | Approve build D (the `8gen2` census-row spike) in the S23 session already planned for the fix-E benches; BYOK backup first; Play copy uninstalled once for both | S23 | one gradle run + 30 min |
| 3 | On the XR, before any sideload: `getprop ro.soc.manufacturer`, the `/vendor` `ls`, `dumpsys battery`, `pm list features` | XR | seconds |
| 4 | AI Hub token + `pip install qai-hub==0.55.0 qai-hub-models==0.61.0` (~4 GB disk) — needed only for the S23's step 3 fallback and for the XR compile; may wait | both | minutes |
| 5 | Rule on the fifth family's release slot (a minor: a device class gains turbo) relative to 4.4 streaming | S23 | a decision |
| 6 | Nothing to buy; the packs still ship via Play, so the 08-27 licensing verdict stands (`2026-08-27-npu-whisper-turbo-research.md:116`); a self-compiled asset "typically has the same distribution license as your model" (FAQ) | both | — |

### 1.7 Which first

**The S23 class, by a wide margin, and it is not close.** Everything but a census row is paid for
(vendor binary with the right `soc_model`, runtime halves in the APK, the phone being paired
anyway, a hosted real S23 Ultra as the fallback compile target). The XR needs a compile, stamps
the wrong SoC id, has an unread manufacturer string and an unknown HTP contention profile — and,
decisively, has no product to serve until §2's panel exists. Order: S23 step 0 (done) → step 1
(desk) → step 2 (device) → fifth family; XR step 0 (free reads) now, step 1 only after M3 = go.

---

## 2. DIRECTION B — Galaxy XR

### 2.1 The accessibility root cause — what the primaries say, and what changed after the brief

**Timeline, and the device wins.** The brief's state: `ACCESS_RESTRICTED_SETTINGS: ignore` with a
`rejectTime` minutes after the Play install, `packageSource=0`, installer `com.android.vending`;
the controller applied `appops set … ACCESS_RESTRICTED_SETTINGS allow`; "the owner is retrying the
toggle now". The confirmation came back **negative** [DEVICE, memory note, same day, later]: the
XR Settings accessibility list shows only TalkBack; `settings put secure enabled_accessibility_services`
was refused by the platform — `AccessibilityManagerService: Skipping enabling service disallowed by
device admin policy: ComponentInfo{com.whispereverywhere/…WhisperAccessibilityService}`; `dumpsys
device_policy` on the headset reports permitted accessibility services **EMPTY** (none beyond
system) and permitted input-method packages exactly **two** (`com.samsung.android.honeyboard`,
`com.google.android.tts`), with no device or profile owner; and the overlay page in XR Settings says
verbatim **"This setting is not supported on your headset."** The brief's "the headset's
accessibility path is unblocked by the appops fix" is overridden: the appop was a real, *secondary*
blocker; the root on the headset is platform policy.

**Mechanism, from AOSP `android14-release` (the headset is API 34).** The quoted log line is
emitted whenever `isAccessibilityTargetAllowed(...)` is false (`review/ams14.java:2421-2428`), and
that function returns false on **either** of two branches (`:4095-4116`): (a)
`dpm.getPermittedAccessibilityServices(userId)` is non-null and lacks the package, or (b) the package
is DPM-allowed but `config_enhancedConfirmationModeEnabled` is true and
`OP_ACCESS_RESTRICTED_SETTINGS` is not `MODE_ALLOWED`
(https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java).
So the log line alone does not identify a device-admin policy. The identification rests on two
carried readings: the appop had already been set to `allow` when the `settings put` was tried
(the note's chronology), which removes branch (b); and the `dumpsys device_policy` list being an
*empty non-null* list, which under the DPM contract means "only the built-in system services"
(https://learn.microsoft.com/en-us/dotnet/api/android.app.admin.devicepolicymanager.setpermittedaccessibilityservices?view=net-android-35.0).
Both readings should be re-run verbatim in the next headset session (§2.5 T0); neither Google nor
Samsung publishes a statement that third-party accessibility services or IMEs are unsupported on
Android XR (two agents searched; Google's XR help lists only system keyboards —
https://support.google.com/android-xr/answer/16637308: "You can select a default keyboard and
manage settings for Samsung Keyboard. You can turn Google Voice Typing and Gboard on or off.").

**The Restricted Settings half — real, non-stock, and still the phone-side story.**

- Stock Android 14 restricts at install **only** `PACKAGE_SOURCE_LOCAL_FILE` / `DOWNLOADED_FILE`
  (`review/aosp/InstallPackageHelper14.java:2372-2377`; identical in QPR3, `…14qpr3.java:2897-2906`;
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/InstallPackageHelper.java).
  `PACKAGE_SOURCE_UNSPECIFIED` (= the device's `packageSource=0`) is never restricted in stock code.
- Stock Android 15 ECM would not have guarded it either: a package is exempt when its installer is
  allowlisted **or preinstalled** (`review/aosp/EnhancedConfirmationService15.java:236-241`,
  `:274-284`; preinstalled = `FLAG_SYSTEM`, `:320-322`), and Play is a system app on every GMS device
  (https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android15-release/service/java/com/android/ecm/EnhancedConfirmationService.java).
- Therefore **no Google rule produces a guarded `packageSource=0` Play install**: the XR build applies
  a third, unread rule. The brief's causal sentence — "the installer did not declare
  `PACKAGE_SOURCE_STORE`, so the platform treats the install as non-store" — is not a documented
  Android rule on API 34 (nor on 15). Keep `packageSource=0` as a recorded fact; do not build product
  logic on it as a cause. Whether the XR rule is Play-on-XR-wide is **unknown until T5** (a second
  Play app with an accessibility service) — the "app-agnostic guard" argument does not transfer to a
  rule nobody has read.
- **Notification-listener access is gated by the same op on stock 14** — the XR analysis's "only
  the accessibility list" was wrong one hop away: `ApprovalPreferenceController14.java:110-111`
  → `RestrictedSwitchPreference14.java:247-268` (`updateState` notes
  `OP_ACCESS_RESTRICTED_SETTINGS`, `appOpsAllowed = !ecmEnabled || mode == MODE_ALLOWED`,
  `setDisabledByAppOps(true)` when not yet enabled;
  https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/packages/SettingsLib/src/com/android/settingslib/RestrictedSwitchPreference.java).
  So the headset's Notification-access row was blocked by the same mechanism before the appop
  allow; the optional row needs the same third state as the accessibility row.
- **The overlay is "unsupported", not "restricted":** `DrawOverlayDetails` on 14 carries no op
  reference (`review/raw2/DOD14.java:145`), and neither the 15 nor the 16 Settings fragment carries an
  ECM hook even though ECM's `PROTECTED_SETTINGS` lists `OPSTR_SYSTEM_ALERT_WINDOW`
  (`EnhancedConfirmationService15.java:116-138`). The device's `SYSTEM_ALERT_WINDOW granted=false /
  appop default` [DEVICE] is an ungranted special permission that Settings refuses to offer.

### 2.2 Productisation — what 4.3.3 already does, and the amendments the AOSP reading adds

**4.3.3 as built** (`feat/accessibility-optional` at `1175bb4`: `4d634aa`, `1104ab7`, `fe367dc`,
`1175bb4`; spec `review/accessibility-optional-spec.md`): the accessibility step is *recommended*,
the Continue gate is `permissionsContinueEnabled(mic, overlay)` (`wt-ao/…/OnboardingLogic.kt:582-594`
— today's three-way gate is `OnboardingLogic.kt:507-508`, wired at `OnboardingFlowScreen.kt:227,238`,
and Home's `canEnable` at `HomeScreen.kt:207`); a pure `AccessibilityAvailability.classify(isHmd,
hasXrSpatialFeature, restrictedSettingsSuspected)` → `ENABLEABLE | BLOCKED_BY_DEVICE |
RESTRICTED_SETTINGS`, device veto first (`wt-ao/…/AccessibilityAvailability.kt:44-53`), fed by a
reflection read of `ro.build.characteristics`, `hasSystemFeature("android.software.xr.api.spatial")`
and `SDK_INT` (`…Probe.kt`); `HomeGate.canEnable` drops accessibility; delivery goes straight to the
clipboard when `isEnabled()` is false (`wt-ao/…/FloatingBubbleService.kt:3511`); copy pinned. That is
the right first ship on every platform that refuses the service.

**Amendments (small, pure, JVM-pinned; 4.3.3 if the branch is still open, else 4.3.4):**

1. **The app can read its own `packageSource`.** `packageManager.getInstallSourceInfo(packageName)
   .getPackageSource()` is public API 33 (`review/aosp/InstallSourceInfo14.java:160-165`;
   https://developer.android.com/sdk/api_diff/33/changes/android.content.pm.InstallSourceInfo),
   built from the same persisted field `dumpsys` prints. The permissions map's "no read-back exists"
   was wrong. Strengthen `restrictedSettingsSuspected` from "returned with the service off on API ≥ 33,
   not HMD" to "… **and** (`packageSource ∈ {0, 3, 4}` **or** installer ≠ `com.android.vending`)":
   a phone Play install that stamps `STORE (2)` must *not* show the guidance after one round trip
   (the user just backed out). The op itself **cannot** be read: it is `setRestrictRead(true)` and
   every check/note path enforces `MANAGE_APPOPS` (`review/aosp/AppOpsManager14.java:2715-2717`,
   `AppOpsService14.java:3608-3618`) — the webxr map's `unsafeCheckOpNoThrow` route throws
   `SecurityException`.
2. **The remedy is three ORDERED legs, and the copy must say so.** (i) Accessibility settings → tap
   the greyed **Whisper Everywhere Voice Input** → the "Restricted setting" dialog writes
   `deny → ignore` (`review/aosp/ActionDisabledByAppOpsDialog14.java:43-52`); (ii) App info ⋮ →
   **"Allow restricted settings"** — the item is visible **only** in `ignore`
   (`AppInfoDashboardFragment14.java:529-539`), and asks for the screen lock only if the keyguard is
   secure (`:441-445`), then writes `allow` (`:491-505`); (iii) back to Accessibility → enable.
   Google's page names the same path (https://support.google.com/android/answer/12623953). The
   4.3.3 string "Open App info -> the menu -> Allow restricted settings, then try again" skips leg (i):
   on a fresh restricted phone the menu item is not there yet. Both intents already exist
   (`Settings.ACTION_ACCESSIBILITY_SETTINGS` at `OnboardingFlowScreen.kt:370`;
   `ACTION_APPLICATION_DETAILS_SETTINGS` + `package:` at `SettingsScreen.kt:621-627`); the copy should
   describe the *shape* ("the three-dot menu at the top of App info"; "you may be asked for your
   screen lock") because OEM Settings relabel.
3. **The Notification-access row gets the same third state** (§2.1; optional, so no gate change).
4. **Diagnostics under `WE-DIAG`:** `packageSource`, `installingPackageName`, `SDK_INT`, round-trip
   count — so a headset or phone logcat explains itself.
   Cost, all four: ~1 day.

**The XR-specific amendment — an owner ruling.** The spec keeps the overlay required ("Overlay stays
required (the bubble is an overlay)", spec item 6) and the branch's code agrees. On the headset the
overlay setting is unsupported, so **after 4.3.3 a Play-installed XR user passes the accessibility
row and is held at the overlay row** — the same locked door, one row lower. Two options, not
mutually exclusive: **A0** exclude the Galaxy XR (SM-I610) in the Play Console device catalog until
an XR product exists (zero code; the listing promises typing-into-apps, which is false there;
reversible the day M3 ships; the distribution page documents that mobile-track apps are
auto-discoverable on XR "as long as the app doesn't include any unsupported features" —
https://developer.android.com/develop/xr/package-and-distribute); **A1** the overlay row also
learns `BLOCKED_BY_DEVICE` from the same classifier inputs — Continue passes, Home hides the bubble
control and fronts the two surfaces that work with neither permission (batch file transcription,
`BatchTranscribeScreen.kt`; the text-selection "Speak" action, `SpeakTextActivity`, manifest
`:105-121`), copy that does not promise dictation. Recommended: **both**. A1 also covers any phone
whose OEM Settings refuses the overlay.

### 2.3 The overlay question, and the test

**Documented: nothing.** No Google Android XR page states whether a `TYPE_APPLICATION_OVERLAY`
window renders in Home Space, in either direction (foundations, readiness, spaces help, devices,
spatial capabilities, distribution, get-started — re-opened by two agents; searches for
`SYSTEM_ALERT_WINDOW`/`TYPE_APPLICATION_OVERLAY`/PiP + "Android XR" return only phone material).
What *is* documented is a windowing model hostile to the bubble's premise: Home Space — "Multiple
apps run side by side", apps "contained within a single panel" with bounds 1024×720 dp default,
385×595 min, 2560×1800 max, "does not support spatial panels"; Full Space — "One app runs at a
time … All other apps are hidden" (https://developer.android.com/design/ui/xr/guides/foundations;
https://developer.android.com/codelabs/xr-fundamentals-part-1;
https://support.google.com/android-xr/answer/16638859). We ship exactly one such window:
`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN`, `TOP|START`, positioned
by a fraction of `resources.displayMetrics` and clamped (`FloatingBubbleService.kt:1553-1578`, added
`:1591`); the service stops itself without the permission (`:677-683`); the read-aloud lobe drops
`FLAG_NOT_FOCUSABLE` for 300 ms to read the clipboard (`:912-930`) — a trick that assumes an
overlay can take focus.

**The device fact reframes the test.** XR Settings: "This setting is not supported on your headset"
[DEVICE]. A Play user cannot grant `SYSTEM_ALERT_WINDOW`; the adb appop grant exists only for a draw
test. So **the draw test no longer decides the product**: the bubble cannot exist on Galaxy XR as a
Play product whatever it shows. Run it anyway — it costs one bubble start on the 4.3.3 debug
sideload (sheet row H3) and its result is the evidence for a Google/Samsung feature request. Design
it to answer four things, not one: (a) does anything draw at all; (b) inside our own panel, in its
own floating layer, or over *another* app's panel; (c) does a pinch on it start recording
(`WE-DIAG` "startRecording"); (d) where the `displayMetrics` clamp put it (`adb shell dumpsys window
windows | grep -A3 whispereverywhere`, the overlay's frame vs the panel's). If nothing draws with
the appop at `allow`, that closes it.

**The architectural fact, and its limit.** Injection does not depend on the overlay: the primitives
act on `AccessibilityNodeInfo`s from any window — `findFocusedEditText` iterates `windows` before
`rootInActiveWindow` (`WhisperAccessibilityService.kt:713-735`), `hasLiveInputTarget` (`:1216-1222`),
the record-start session bind (`:1228-1236`), `injectTextWithResult` (`:1240-1242`) — and the bubble
is only the trigger and preview (`FloatingBubbleService.kt:3503-3538`). On phones that means a
non-overlay trigger could drive the existing delivery. On the headset it is moot: the service itself
is policy-refused. And as wired, every caller of those primitives is a private member of the
3,877-line bubble service that `stopSelf()`s without the overlay — the headless extraction (B3
below) is the cost of using the fact anywhere.

### 2.4 The alternative surfaces, ranked for the headset, with build cost

All costs assume 4.3.3 merged. **B3 — the headless `DictationSession`** — is the shared driver and
is counted once: `startRecording` (`FloatingBubbleService.kt:2739`, single caller
`handleBubbleClick` `:1972` ← `handleTouch` `:1869`) and `stopRecording` (`:3044`) are private
members of a service that owns the engines (`:392-446`), the accumulator (`:582`), the sink (`:614`),
the endpointer, the cadence and final delivery (`:3503-3538`); `BatchTranscriptionService` proves
the *engine* runs headless for files (`BatchTranscriptionService.kt:94,269`) but the *live* path
does not. Extracting a session the bubble and an in-app screen can both drive: **~3–5 days**.

| rank | surface | needs on the headset | status | build cost | verdict |
|---|---|---|---|---|---|
| **1** | **The app as its own 2D Home-Space panel with a new in-app "Dictate here" screen** — live strip, copy/share/Speak, device audio via the existing `ProjectionConsentActivity` | mic only | 2D apps run "displayed as a 2D panel inside 3D space" by default (https://developer.android.com/develop/xr); no in-app live dictation screen exists (`BatchTranscribeScreen.kt:44-45`: "No recordings library, no record screen …") | B3 + screen ~2–3 days + XR-aware Home (~1 day; A1's hidden bubble control is the front door). **No Jetpack XR** (`1.0.0-alpha17`, 2026-08-12, API 34 runtime floor — https://developer.android.com/jetpack/androidx/releases/xr-compose), **no XR track, no build variant**; runtime `hasSystemFeature` (4.3.3's probe already reads it) | **The honest headset product.** Owner go/no-go first; its value is not XR-only (every overlay-refused phone gets the same screen) |
| 2 | Clipboard delivery through the bubble (what 4.3.3 ships) | overlay + mic | overlay unsupported on XR | in flight | Dead on XR; **the floor on phones** for service-refused installs |
| 3 | A non-overlay trigger (notification action — today the FGS notification's only action is "Stop", `:3847`; a `TileService`; the accessibility-button shortcut; a side-by-side panel) driving the existing accessibility injection | accessibility (policy-refused on XR) | QS tiles and third-party a11y-button targets on the XR shell unknown (T7); Samsung's setup page shows Quick Settings exist (https://www.samsung.com/us/support/answer/ANS10007502/) | B3 + 1–3 days each | Dead on XR; a phone-side option if a phone ever refuses the overlay |
| 4 | An IME ("Whisper Everywhere keyboard", `InputConnection.commitText`) | user enables + selects it; no overlay; **not a restricted setting** (the 14 IME-enable screen has no op hook — `review/raw2/IMP14.java`, `AVKF14.java`; no IME entry in 15/16 `PROTECTED_SETTINGS`) | the headset's permitted-IME list is two system packages [DEVICE]; Google's help names only Samsung Keyboard / Gboard / Voice Typing; nothing published names a third-party IME on Galaxy XR; T6 (`ime list -a`, a Play keyboard install) settles it; an IME's input view is itself a window, so whether it draws on XR is as untested as the overlay | B3 + 4–7 days (new component: no `InputMethodService` in the tree, manifest `:50-175`) | **Do not build for XR.** Survives only as a phone-side, permission-free alternative if the owner ever wants one |
| 5 | The bubble as-is | overlay | Settings: unsupported | 0 to test | Dead as a product; the draw test is a dev fact and a bug report |
| 6 | A Jetpack XR spatial panel / orbiter | Full Space; `android.software.xr.api.spatial` for the XR track | "Spatialization is only supported in Full Space" (https://developer.android.com/develop/xr/jetpack-xr-sdk/transition-home-space-to-full-space); Full Space hides every other app; the dedicated track requires the manifest feature and is visible only to XR devices (package-and-distribute page) | 1–2 weeks incl. an alpha dependency and a release-model fork | Not a dictate-into-any-app surface; at most a v2 polish of rank 1. Last |

Not options: notification Bubbles (`BubbleMetadata`, conversation shortcuts only); PiP (an Activity
mode with no text input; undocumented on XR). Platform dictation already exists on the headset
(Gemini; Samsung Keyboard's voice input) — the XR value proposition is on-device / private /
Whisper quality, never "dictation exists".

### 2.5 What is unavailable today, and the device tests that settle the unknowns

**Unavailable in the tree (`main`):** no IME, no `TileService`, no `flagRequestAccessibilityButton`,
no `getInstallSourceInfo`, no live in-app dictation screen, no XR/HMD detection (4.3.3 adds the read);
the recording pipeline is private to the bubble service; onboarding cannot be finished on the XR
(`OnboardingLogic.kt:507-508`; the first step's back exits without completion,
`OnboardingFlowScreen.kt:143-150`; a fresh install always routes to first-run, `ModeDashboard.kt:108-109`)
and after 4.3.3 as built still stops at the overlay row. The one onboarding-free surface, `SpeakTextActivity`
(`PROCESS_TEXT`), dead-ends on a fresh install until the voice is downloaded behind the gate
(`TtsController.kt:79-85`).

**Untested on the headset:** the IME-window catch-all (`AccessibilityWindowInfo.TYPE_INPUT_METHOD`,
`WhisperAccessibilityService.kt:559-600`); `softKeyboardController` suppression (`:616-624`);
MediaProjection / `MediaNotificationListener`; "Speak" in the XR selection toolbar; third-party QS
tiles; and `<uses-feature android:name="android.hardware.touchscreen" android:required="true"/>`
(`AndroidManifest.xml:48`) — Play let 4.3.0 through, so either the XR declares it or Play does not
filter on it for XR; a latent distribution risk, not tonight's problem.

**The session, ordered (read-only adb is the controller's; expected outputs stated so a result is a
result).** Run everything on the Play copy *before* the 4.3.3 sideload uninstalls it.

| # | test | decides |
|---|---|---|
| T0 | `dumpsys device_policy` **verbatim** (the permitted accessibility-services and input-methods blocks, with the admin that set them, if any); `settings get secure enabled_accessibility_services`; `getprop ro.soc.manufacturer` | Whether §2.1's policy reading is a non-null empty list from a baked-in policy; the NPU gate's second input |
| T5 | Install one other Play app that ships an accessibility service; tap it in Accessibility; `appops get <pkg> ACCESS_RESTRICTED_SETTINGS`; `dumpsys package <pkg> \| grep -i "packageSource\|installerPackageName"`; the Fold6 contrast row (`dumpsys package com.whispereverywhere \| grep packageSource` on a phone Play install) | Whether the restricted-settings rule and the policy are Play-on-XR-wide — the filable bug |
| T6 | `ime list -a -s`; `settings get secure default_input_method`; if a spare Play keyboard is at hand, install it and try to enable/select it | Rank 4's fate (expected: closed) |
| T2 | Screenshots: the greyed accessibility row's summary, the "Restricted setting" dialog, App info ⋮ with the op at `ignore` | The exact strings for the phone-side copy; whether Samsung's XR Settings kept the ⋮ item |
| T7 | Quick Settings edit-tiles affordance; Accessibility toolbar assignable shortcuts; the FGS notification's actions | Rank 3 feasibility (phone-side relevance only) |
| T9 | Select text in Chrome/Notes: does the toolbar offer **Speak**? | Whether read-aloud survives on XR with zero permissions |
| T10 | `pm list features \| grep -E "touchscreen\|faketouch\|microphone\|xr"` | The `uses-feature` distribution risk |
| H3 | Then the 4.3.3 debug sideload: onboarding passes with the `BLOCKED_BY_DEVICE` copy; the bubble start is the four-outcome overlay draw test (§2.3) | Recorded, not decisive |

---

## 3. THE PLAN

### 3.1 Order and dependencies

```
M0  free reads (S23 pairing; XR getprops/dumpsys verbatim; T5/T6/T2/T9/T10 on the Play copy; AI Hub token)
 │
 ├─► M1  4.3.3 / 87  accessibility optional (in flight) + the amendments (§2.2) + owner ruling A0/A1
 │      └─ H3 on the XR sideload = the overlay draw test (recorded, not decisive)
 │
 ├─► M2  the fix-E `pro`-on-GPU measurement on the S23 (and the XR, same Adreno 740) with the retained spike branch
 │      └─► 4.3.4 / 88 either way (code routes `pro` to the GPU on any Adreno 7xx, `GpuPolicy.kt:140,154`;
 │           the ruling says CPU-only, `CommitCadencePolicy.kt:17`) — scoped and decided in
 │           docs/superpowers/research/2026-09-04-turbo-cpu-gpu-research.md; not re-litigated here
 │
 ├─► M4-1  the qcs8550-proxy pair on the S23 (measure run + build D + 30-min load) — rides the M2 session
 │      └─► M4-2  AI Hub compile for the hosted S23 Ultra — ONLY if M4-1 fails to load or fails the canary
 │              └─► M4-3  fifth family `8gen2` = new AAB (4.5) — queued behind 4.4 in versionCode order
 │
 ├─► M3  XR product go/no-go (owner) ──► B3 headless session + the in-app dictation screen + XR-aware Home (4.5/4.6)
 │      └─► M5  v69 compile (hosted S22) ──► headset measurement ──► package only if it beats the CPU tier
 │
 └─► 4.4 streaming (the owner's queued next; untouched by this plan; owns the next AAB codes)
```

M0, M2, M4-1 and M4-2 are experiments — no AAB, no versionCode, owner device/token time only. M1,
M2's verdict release, M4-3, M3 and M5 each spend a versionCode and an internal-track session.

### 3.2 Milestones

- **M0 — free reads (tonight / next device session).** S23: pair (pairing-code flow), `getprop
  ro.soc.model ro.soc.manufacturer`, and on the Play build read the release-surviving `init: use_gpu=`
  line during a `pro` dictation (`whisper_jni.cpp:139` on the spike tree, `:119` on `main`) before
  any spike number exists. XR: §2.5's T0/T5/T6/T2/T9/T10 on the Play copy. Desk: the AI Hub token
  (or an explicit deferral); Play Console → device catalog → SM-I610 (supported? the exclusion
  control). Exit: S23 paired; `use_gpu=` recorded; T0 verbatim on file.
- **M1 — 4.3.3 / 87.** Merge `feat/accessibility-optional` with §2.2's four amendments folded in
  if the branch is still open (else they are 4.3.4's), plus the owner's A0/A1 ruling. Internal
  track → H1/H2 on a phone → promote. The XR debug sideload (uninstall the Play copy there —
  approved) runs H3 and touches Play not at all.
- **M2 — the fix-E measurement, then 4.3.4 / 88.** The S23 session per `tools/spike/README.md`
  (BYOK backup → uninstall → build A → `pro` bench → build C → `pro` bench, two cold runs each;
  skip every turbo arm — dropped by ruling), then the same two benches on the XR sideload. Decision
  and teardown per the turbo research §4.4/§4.5. Either "fix E ships for `pro`" or "the ruling
  becomes code"; a patch (last digit moves).
- **M3 — the XR product decision, then the build.** Owner: is a headset product wanted? If yes:
  B3 + the "Dictate here" screen + XR-aware Home (§2.4 rank 1), a design pass before code
  (brainstorm-grade). A **minor** (the user sees a new surface). Internal track → owner XR session →
  promote; undo A0's catalog exclusion the day it promotes. If no: the XR keeps 4.3.3's degraded mode
  or stays excluded, and M5 is dead.
- **M4 — v73 for the S23 class.** M4-1 (§1.2 steps 1–2; 30 min on the phone). M4-2 only on failure.
  M4-3 = the fifth family (§1.3): census row with the dated device evidence, digests from `measure`,
  the XML group, the gradle rows, the python rows, the loosened pins made real, `verifyNpuPacks`,
  a new AAB through the internal track with an S23 device sheet (the first v73 execution in the
  project's history; R7 re-proved for both graphs the same day). A **minor** — a device class gains
  turbo. Slot after 4.4 unless 4.4 slips and the pack is ready first (the two touch different files:
  census/packaging vs the streaming pipeline).
- **M5 — v69 for the headset, last and conditional** (M3 = go, M4 recipe proven, headset CPU tier
  timed with `bench_whisper_rtf_across_slices`, `WhisperBenchTest.kt:88`, on the M2 sideload — free,
  and it may end M5 on its own). Then §1.2's XR steps 1–2; package as the sixth family only if the
  measured encoder beats the CPU tier's. Rides M4-3's AAB if the timing lines up (two new groups in
  one config), else the next minor.

### 3.3 Owner actions, consolidated and ordered

1. Rule on M1's amendment: **A0** (exclude SM-I610 in the Play Console now), **A1** (overlay row
   `BLOCKED_BY_DEVICE`), both (recommended), or neither.
2. Pair the S23 Ultra; `getprop ro.soc.model ro.soc.manufacturer`; read `init: use_gpu=` on the
   Play build (M0).
3. On the XR, on the Play copy, before any sideload: T0 verbatim, T5, T6, T2, T9, T10; `getprop
   ro.soc.manufacturer`; the `/vendor` `ls`.
4. Phone `packageSource` contrast row (T5's second half) and the Play catalog check for SM-I610.
5. 4.3.3 device sheet H1/H2 on a phone; H3 on the XR sideload (draw test recorded).
6. One S23 sitting for M2 + M4-1 (BYOK backup → uninstall Play → A/C `pro` benches → build D →
   `measure`'d qcs8550-proxy pair pushed → load/measure → teardown, reinstall from Play, re-enter
   keys). Then the two `pro` benches on the XR sideload.
7. Rule on 4.3.4's direction from the M2 numbers.
8. Rule on M3 (a headset product, yes or no).
9. AI Hub token when M4-2 or M5 needs it; the S22 compile only after M3 = go and the headset CPU
   timing is on record.

### 3.4 Versioning and packaging rules, stated once

| rule | source |
|---|---|
| Every upload spends a versionCode; Play refuses a second upload at the same code; the **name** moves only when what the user sees changes (patch = last digit; a new surface or coverage = minor) | `app/src/test/java/com/whispereverywhere/ReleaseIdentityTest.kt` KDoc |
| Every bump re-arms `GpuPolicy`'s canary latches — inert with the multilingual toggle off, live for `.en` on the first load | same KDoc; `GpuPolicy.kt:237-249` |
| A new pack family = census row + `device_targeting_config.xml` + payload dirs + gradle rows + python rows; the config is **inside the bundle**, so it is a new AAB; Play device targeting is **beta**, SoC selectors need API ≥ 31; unmatched devices get the EMPTY `other` variant | `app/build.gradle.kts:277,284-291`; https://developer.android.com/google/play/device-targeting; https://developer.android.com/guide/playcore/asset-delivery/device-targeting |
| Pack limits: **1.5 GB per individual asset pack, 30 GB cumulative on-demand/fast-follow, compressed** — a fifth and sixth family (~1.15 GB each, turbo + small) are not a constraint; the 1.62 GB float 8gen1 pair would exceed the per-pack cap | https://support.google.com/googleplay/android-developer/answer/9859372; `2026-08-29-fleet-onboarding-design.md:19-21` |
| XR: **no build variant, no XR track** for M1/M3; runtime `hasSystemFeature("android.software.xr.api.spatial")`; the dedicated track only if a different manifest/permission set is ever wanted | https://developer.android.com/develop/xr/package-and-distribute |
| A sideload is never a Play install: `adb install -r` over the Play copy fails `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; uninstall first; BYOK keys die with it; the Fold6 (the only device with turbo device evidence) is never uninstalled | `tools/spike/README.md:58-70`; memory build-env note |
| Build B (`-PweAdrenoKernels=OFF`) never ships; the spike branch is never merged as-is | turbo research §4.5; README builds table |

Proposed ladder (owner may reorder): **87 = 4.3.3** (M1) → **88 = 4.3.4** (M2 verdict) → **89+ =
4.4** (streaming) → **4.5** (M4-3 v73 family and/or M3 XR panel) → **4.6** (whichever did not fit;
M5 if it wins).

### 3.5 What not to do

1. Do not add a V69 row, skel or stub to `main` before a v69 binary has loaded and been timed on
   the headset (rule 2, `NpuFleetCensus.kt:105-108`; the excludes at `app/build.gradle.kts:250-253`
   and their test are doing their job).
2. Do not treat Restricted Settings as the XR fix; it is the phone-side branch of the classifier.
3. Do not build an IME for the headset (policy-closed pending T6); do not make M3 depend on Jetpack
   XR; do not let the overlay draw test decide anything.
4. Do not run the spike's turbo arms; do not ship build B; do not merge the spike branch as-is.
5. Do not widen the census from AI Hub `supported_chipsets` / model-card lists (a model-card URL for
   `qcs8450` returned 403 — webqnn map §I.1); only `release_assets.json` keys or a measured
   self-compile count. Do not cross-load anything through Play — M4-1 is a spike branch + `adb push`.
6. Do not compile at `--qairt_version latest` unchecked against the 2.49.0 runtime.
7. Do not quote 1,292 ms (or 1,527.8 ms) as a Galaxy XR number — both are S22 rows with an XR label.
8. Do not change `android.hardware.touchscreen required="true"` until T10 and the catalog check.
9. Do not push compile artifacts, the AI Hub token or the QAIRT headers into git (`npu_*/.gitignore`;
   "NEVER commit QAIRT headers", 4.0 note).

---

## 4. RISKS

| # | risk | what settles or bounds it |
|---|---|---|
| R1 | **The XR policy reading is carried, not re-run**, and the AMS log line is ambiguous by source (device-policy branch vs appop branch, `review/ams14.java:4095-4116`) | T0 verbatim `dumpsys device_policy` + T5; until then the accessibility route on XR is treated as closed |
| R2 | **The XR restricted-settings rule is unread and non-stock** (neither 14 nor 15 ECM produces it); its inputs are unknown, so "Play-on-XR-wide" is an inference | T5 (a second Play a11y app) + the Fold6 `packageSource` contrast row → a filable bug |
| R3 | **4.3.3 as built still locks XR users** at the overlay row; the listing promises typing-into-apps | A0/A1 ruling (§2.2) |
| R4 | **v73 has never armed on silicon**; the S23 load is the first execution; a loader failure is not evidence about the model | read the log stage; the V73 stub/skel pair's `sha256` and the `ADSP_LIBRARY_PATH` staging are the first suspects |
| R5 | **Proxy-profiled numbers "will vary"**; the S23 projection (2.2–2.4 s) rests on one family's app/harness ratio | the 300 s drift loop with temperatures; a per-family F row or the governor |
| R6 | **Both new families sit above the 2,000 ms cadence floor** → the 3,200 ms SLOW row engages under saturation; the one-sentence-per-chunk experience degrades to sentence pairs while behind | measured F per family; decide row vs governor after the measurement (`CommitCadencePolicy.kt:112,131`) |
| R7 | **The 36-on-53 cross-load for the XR is undocumented** in either direction; the local-SDK exact-stamp route is a second toolchain | the arm; only then price the SDK |
| R8 | **AI Hub deprecations** (`qnn_context_binary` "will soon be removed"; `precompiled_qnn_onnx` deprecated) may break the 0.61.0 export wrapper before M4-2/M5 run | compile+link route; the `measure` tool's flavour assumptions are the thing that changes |
| R9 | **Play device targeting is beta** and undocumented for Android XR installs | the app gate is the authority; SAF import is the fallback; the empty default variant is fail-safe |
| R10 | **`ro.soc.manufacturer` on the XR is unread**; a third spelling denies the tier regardless | one `getprop` (T0) |
| R11 | **`SXR2230P` is also the Quest 3's part string**; a future non-Samsung Android XR device would match the row | the row's evidence names it; harmless while the binary is right for the die |
| R12 | **The HTP performance vote on Android XR** may be denied or contended by the platform's tracking loads (the decisive G1 variable) | the headset drift loop |
| R13 | **Third-party IME viability on Galaxy XR is undocumented**; Google's help contradicts "exactly two packages" as a platform-wide fact (Gboard on/off) | T6; either way no third-party route is opened by any source |
| R14 | **The overlay draw test is a dev fact**; a positive result could mislead a reader into thinking the bubble is viable on XR | record it with the "Play user cannot grant it" sentence attached |
| R15 | **`uses-feature touchscreen required=true`** is an unexamined dependency in front of XR distribution | T10 + the Play catalog check; change nothing until then |
| R16 | **Every sideload uninstalls the Play copy** (BYOK keys die); the Fold6 must never be one of them | the README's backup step; the device list (S23, Tab, XR are the approved sideload devices) |
| R17 | **The census KDoc's size explanation** ("v73 packs weights less densely") is contradicted by the proxy zip's size; a reader could pre-size the 8gen2 rows from `7gen4` | `measure` settles the raw bytes; the per-family installed-size gate handles either |

---

## 5. WHAT WAS REFUTED

Rule applied: refuted only with a citation; weakened where a load-bearing part is unverifiable;
the primary wins over every brief, note, map and analysis.

| # | claim | who said it | verdict, with the primary |
|---|---|---|---|
| 1 | "Qualcomm publishes NO precompiled turbo for 8 Gen 2" | the brief; the owner ruling; the memory note; `NpuFleetCensus.kt:353-356`; plan analysis M4 | **True by key name, false by `soc_model`.** `qualcomm-qcs8550-proxy` w8a16 (turbo and small) embeds `htp 73 / soc_model 43`; `QnnTypes.h:1846` says 43 = SM8550. Re-read tonight from the live zips (§1.1). The 8 Gen 2 keys still leave `CPU_BY_CENSUS` only with a dated device measurement (rule 2). |
| 2 | The proxy asset "carries QCS8550's `soc_model`, not SM8550's" | npupipeline map §5 | **Refuted.** QCS8550 has its own id 66 (`QnnTypes.h:1868`); the zip embeds 43. |
| 3 | "Either [7gen4 or qcs8550-proxy] is a same-arch/different-`soc_model` cross-load" | NPU analysis C4 | **Half refuted.** The 7gen4 pair (86) is; the proxy pair (43) is not a cross-load at all on SM8550. |
| 4 | `build_asset_packs.py` needs a local-artifact mode for the S23 family | plan analysis M4-3 | **Refuted for this family.** It is a vendor key; the four existing gates apply unchanged (`:74,122-141,330-360`). Needed only for a self-compiled pair (the S23 fallback or the XR). |
| 5 | 08-29 research §7: IoT keys "not phone-relevant" (HTP left blank) | `2026-08-29-pad-soc-delivery.md:250` | **Closed a door worth re-opening.** qcm6690 73, qcs8275 75, qcs8550-proxy 73 (soc 43), qcs9075 73, sa7255p 75, sa8775p 73 (tonight's probe). Right for the phone fleet's *keys*, wrong for a v73 retarget. |
| 6 | "No w8a16 turbo number exists for any v69 or v73 part … a headset turbo encoder lands in the multi-second range (~2.8× slower)" | webqnn map §G; plan analysis C10's 2.8× | **Refuted (wrong rows).** w8a16 rows exist for S22 5G (v69) 1,527.8 ms, 7 Gen 4 QRD (v73) 2,224.5–2,227.6 ms, QCS8550 (Proxy) (v73) 1,663.8–1,763.4 ms; the 2.8× is the *float* file (S22 1,292.4 vs S24 466.4). The w8a16 v69/v75 ratio is 1.075. |
| 7 | "512 MB per fast-follow/on-demand asset pack — the shipping turbo packs may not clear it" | webqnn map §H6/§J8 | **Refuted.** "Individual asset packs: 1.5GB"; on-demand/fast-follow cumulative 30GB; compressed (https://support.google.com/googleplay/android-developer/answer/9859372). The fleet spec already said so. |
| 8 | Export "for device Snapdragon XR2 Gen 2" | NPU analysis §5 XR step 1 | **Weakened → corrected.** "Snapdragon XR2 Gen 2" is an *unsupported* device mapped to `qualcomm-snapdragon-8gen1` for perf duplication (`similar_devices.yaml`); the schedulable target is the S22 (Family) pool. The stamp will be 36 by construction of that pool (the published 8gen1 float zip embeds `htp 69 / soc 36`); read it back from the blob. |
| 9 | XR proxy devices were "removed 2026-06-09" | plan analysis M5/C9 | **Wording.** The note says "will be removed" — announced, not verified executed (`qai-hub list-devices` is the check). |
| 10 | AI Hub's "performance may suffer" means the 7gen4→SM8550 cross-load is expected to work | webqnn map §F; NPU analysis ledger row 7 | **Weakened.** The FAQ sentence is scoped to *older*-device binaries on *newer* chips; 7gen4 (2025 part) on SM8550 (2022) is the opposite direction, and the compile guide says "deployed to the same device". Undocumented both ways; the census's `INCOMPATIBLE_BINARIES` wording is equally a guess. Moot for the S23 now (row 1). |
| 11 | R7: "the decoder has never been deserialised under 2.49" | `qnn_asr.cpp:1045-1046`; npupipeline map §8 | **Stale.** 57 shipped-build npu-turbo segments with the decoder on the Fold6 under the 2.49.0 AAR (`718002a`; retune plan `:63-66`; the capture file). Per-family re-proof, not an open risk. |
| 12 | "The headset's accessibility path is unblocked by the appops fix (pending owner confirmation)" | the brief | **Overridden by the device.** The confirmation was negative: policy-refused after the appop allow; permitted list empty; IMEs two system packages; overlay "not supported on your headset" (memory note, same day). The appop was a real, secondary blocker. |
| 13 | Root cause: "the installer did not declare `PACKAGE_SOURCE_STORE`, so the platform treats the install as non-store" | the brief; the memory note; the 4.3.3 spec/KDoc wording | **Not a documented rule.** Stock 14 restricts only `LOCAL_FILE`/`DOWNLOADED_FILE` (`InstallPackageHelper14.java:2372-2377`); stock 15 ECM exempts preinstalled installers (`EnhancedConfirmationService15.java:236-241,274-284,320-322`). `packageSource=0` is a fact; the cause is an unread XR rule. |
| 14 | "The shape most consistent with the evidence is the Android 15 ECM rule backported" | XR analysis §2.2 | **Refuted.** Stock-15 ECM would not have guarded a Play install (row 13). |
| 15 | "On API 34 restricted settings gate only the accessibility list; notification access is not gated" | XR analysis C5 / ledger row 5 (dismissing Kaspersky) | **Refuted (notification half).** `ApprovalPreferenceController14.java:110-111` → `RestrictedSwitchPreference14.java:247-268` gates the notification-listener toggle on the same op. The overlay half stands. |
| 16 | "There is no `PackageManager` read-back of an installed package's `packageSource`" | permissions map §5.3 | **Refuted.** `InstallSourceInfo.getPackageSource()` is public API 33 (`InstallSourceInfo14.java:160-165`; the API-33 diff page). |
| 17 | `AppOpsManager.unsafeCheckOpNoThrow("android:access_restricted_settings", …)` "needs no permission" | webxr map §3.4 | **Refuted.** `setRestrictRead(true)` + `verifyIncomingOp → enforcePermission(MANAGE_APPOPS)` (`AppOpsManager14.java:2715-2717`; `AppOpsService14.java:3608-3618`); throws. |
| 18 | "Allow restricted settings … requires the lock-screen credential" | XR analysis C4 | **Corrected.** Only if `isKeyguardSecure()` (`AppInfoDashboardFragment14.java:441-445`); and the dialog cite is `ActionDisabledByAppOpsDialog14.java:43-52`, not `:17-29`. |
| 19 | "The restriction is Play-on-XR-wide for third-party a11y apps (the guard is app-agnostic)" | XR analysis C11 | **Weakened → unknown until T5.** Neither stock rule produces the device's state, so nothing is known about the XR rule's inputs. |
| 20 | "The permitted IME list is exactly two system packages" (platform-wide) | memory note; plan analysis C3 | **Weakened.** Google's XR help says Gboard can be turned on or off (https://support.google.com/android-xr/answer/16637308). Build-specific or a reading of the dump; direction (no third-party route) unchanged; T6 settles it. |
| 21 | "A spatial panel via Jetpack XR could host the bubble / the XR product needs Jetpack XR" | the brief's option list; the memory note's "spatial panel app (Jetpack XR)" | **Refuted for the premise; unnecessary for the product.** Spatial UI is Full Space only, which hides every other app; a 2D panel app needs nothing from `androidx.xr` (`1.0.0-alpha17`). |
| 22 | The app "has a clipboard-only mode" | loose reading of `CLIPBOARD_ONLY` | **Half-true on `main`** (a fallback inside an accessibility session; `injectTextWithResult` returns `FAILED` with the service off, `WhisperAccessibilityService.kt:1240-1242`); **true after 4.3.3** (`wt-ao/…/FloatingBubbleService.kt:3511`), but still behind the overlay. |
| 23 | "One worktree; HEAD `c7facdf`; 8 commits; `feat/accessibility-optional` not in `git branch`" | plan analysis C1/§2 | **Stale.** Two worktrees; HEAD `0b37c17`; 11 commits; the branch exists at `1175bb4` with 4 commits. |
| 24 | Memory index: "4.3.1/83 on local main … UNPUSHED"; "main at 44b2db0" | `MEMORY.md` index line | **Stale.** `versionCode = 86`, `versionName = "4.3.2"`, in production 2026-09-04 (`app/build.gradle.kts:84-85`; the note body agrees); `main` = `3290bfc`. |
| 25 | `whisper_jni.cpp:119` for the `init: use_gpu=` line | plan analysis; the turbo research | **Line offset.** `:119` on `main`, `:139` on the spike tree. |
| 26 | "v73 packs weights less densely" (why the 7gen4 encoder is +9.1 %) | `NpuFleetCensus.kt:180-186` | **Contradicted by the zip sizes** (§1.2 step 1): the v73 proxy zip equals the v75 zip within 1 KB; the size is SoC-specific to SM7750. The measured bytes stand; the explanation does not. |
| 27 | The brief's "NPU tiers = precompiled … `whisper_large_v3_turbo_quantized`" | the brief | **Incomplete.** Two NPU tiers ship: `npu` (Whisper-Small-Quantized) and `npu-turbo`; both are census-wide and both have a `qcs8550-proxy` package. |

### 5.1 Source index

**Code** (repo, read-only): `app/build.gradle.kts:84-85,215-253,277,284-291,660-680,747-756,882-886`;
`app/device_targeting_config.xml`; `app/src/main/AndroidManifest.xml:10,47-48,50-175`;
`app/src/main/cpp/qnn_asr.cpp:1025-1050,1260-1272,2693`; `app/src/main/cpp/include/QNN/QnnTypes.h:1839-1888`;
`app/src/main/cpp/whisper_jni.cpp:139`; `app/src/main/java/com/whispereverywhere/npu/{NpuFleetCensus.kt:93-115,150-165,178-188,206,276,290-303,340-368; NpuGate.kt:54,79-84; NpuAssetImport.kt:38-58; NpuModelSpec.kt:227-263}`;
`app/src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt:69-73`;
`app/src/main/java/com/whispereverywhere/service/{CommitCadencePolicy.kt:17,112,131; FloatingBubbleService.kt:392-446,582,614,677-683,912-930,1553-1591,1869,1972,2739,3044,3503-3538,3847; WhisperAccessibilityService.kt:559-600,616-624,713-735,1216-1242}`;
`app/src/main/java/com/whispereverywhere/transcription/GpuPolicy.kt:140,154,237-249`;
`app/src/main/java/com/whispereverywhere/ui/onboarding/OnboardingLogic.kt:499-516`;
`app/src/main/java/com/whispereverywhere/ui/screens/{OnboardingFlowScreen.kt:143-150,227,238,370; HomeScreen.kt:207; SettingsScreen.kt:621-627; BatchTranscribeScreen.kt:44-45}`;
`app/src/main/java/com/whispereverywhere/ui/ModeDashboard.kt:108-109`; `app/src/main/java/com/whispereverywhere/tts/TtsController.kt:79-85`;
`app/src/test/java/com/whispereverywhere/npu/{NpuFleetCensusTest.kt:60,78-95,113,155-160,210-232; NpuSkelPackagingTest.kt:286-302}`;
`app/src/test/java/com/whispereverywhere/ReleaseIdentityTest.kt`; `app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt:88`;
`tools/build_asset_packs.py:60-82,118-145,218-240,330-362`; `tools/spike/README.md:58-70`.
**Worktree `feat/accessibility-optional` (`1175bb4`):** `ui/onboarding/{OnboardingLogic.kt:530-600; AccessibilityAvailability.kt:1-120; AccessibilityAvailabilityProbe.kt}`; `ui/screens/HomeGate.kt:15-18`; `service/FloatingBubbleService.kt:3511`.
**Repo docs:** `docs/superpowers/research/{2026-08-27-npu-whisper-turbo-research.md:116; 2026-08-28-npu-spike-g1-results.md:15,27,30,39,83-86; 2026-08-29-pad-soc-delivery.md:243-300; 2026-09-04-turbo-cpu-gpu-research.md}`;
`docs/superpowers/plans/{2026-09-02-vad-hangover-retune.md:60-70; 2026-08-28-npu-tier.md:1110}`;
`docs/superpowers/specs/2026-08-29-fleet-onboarding-design.md:15-60`; `docs/superpowers/sdd/2026-08-29-npu-model-lab/acceptance.md:20-43`.
**Session scratchpad (`review/`):** the four maps, three analyses, three refutations;
`accessibility-optional-spec.md`; `htp_probe.py` and `htp_probe_synth_rerun.txt`; `ams14.java`;
`aosp/*14.java`, `aosp/*15.java`, `raw2/*`; `raw/{whisper_large_v3_turbo_quantized.perf.yaml,
whisper_large_v3_turbo.perf.yaml, whisper_small_quantized.perf.yaml, similar_devices.yaml,
utils_device.py, scorecard_device.py, export_v0.61.0.py, qc_schema.py, release_notes.html,
api.html, devices.html}`. **Local (outside the repo):** `C:/Users/bastr/.androidbuild/capture-vad-headroom.txt`.
**Memory:** `~/.claude/projects/C--Users-bastr-OneDrive-Desktop-whisper-Everywhere/memory/whisper-everywhere-ship-track.md`
(2026-09-04 entries: owner rulings, XR identification, permission root cause, both walls, IME
route, 4.3.3 in flight).

**Web (all fetched 2026-09-04):**
- https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized/raw/main/release_assets.json
- https://huggingface.co/qualcomm/Whisper-Small-Quantized/raw/main/release_assets.json
- https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo/raw/main/release_assets.json
- https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/whisper_large_v3_turbo_quantized/releases/v0.61.0/whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-qualcomm_qcs8550_proxy.zip (HEAD + Range)
- https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/whisper_small_quantized/releases/v0.61.0/whisper_small_quantized-precompiled_qnn_onnx-w8a16-qualcomm_qcs8550_proxy.zip (HEAD + Range)
- https://raw.githubusercontent.com/qualcomm/ai-hub-models/main/src/qai_hub_models/models/whisper_large_v3_turbo_quantized/perf.yaml
- https://raw.githubusercontent.com/qualcomm/ai-hub-models/main/src/qai_hub_models/models/whisper_large_v3_turbo/perf.yaml
- https://raw.githubusercontent.com/qualcomm/ai-hub-models/main/src/qai_hub_models/models/whisper_small_quantized/perf.yaml
- https://raw.githubusercontent.com/qualcomm/ai-hub-models/main/src/qai_hub_models/similar_devices.yaml
- https://raw.githubusercontent.com/qualcomm/ai-hub-models/main/src/qai_hub_models/utils/device.py
- https://raw.githubusercontent.com/qualcomm/ai-hub-models/main/src/qai_hub_models/scorecard/device.py
- https://raw.githubusercontent.com/qualcomm/ai-hub-models/v0.61.0/src/qai_hub_models/models/whisper_large_v3_turbo_quantized/export.py
- https://raw.githubusercontent.com/pytorch/executorch/main/backends/qualcomm/serialization/qc_schema.py
- https://workbench.aihub.qualcomm.com/docs/hub/faq.html · /release_notes.html · /api.html · /devices.html · /compile_examples.html · /getting_started.html
- https://pypi.org/pypi/qai-hub-models/json · https://pypi.org/pypi/qai-hub/json
- https://www.qualcomm.com/internet-of-things/products/q8-series/qcs8550
- https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/Snapdragon-8-Gen-2-Product-Brief.pdf
- https://www.qualcomm.com/products/mobile/snapdragon/xr-vr-ar/snapdragon-xr2-plus-gen-2-platform
- https://support.google.com/googleplay/android-developer/answer/9859372
- https://developer.android.com/google/play/device-targeting · https://developer.android.com/guide/playcore/asset-delivery/device-targeting
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Build.java
- https://android.googlesource.com/platform/system/libsysprop/+/refs/heads/main/srcs/android/sysprop/SocProperties.sysprop
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/pm/InstallPackageHelper.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/app/AppOpsManager.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/services/core/java/com/android/server/appop/AppOpsService.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/content/pm/InstallSourceInfo.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/packages/SettingsLib/src/com/android/settingslib/RestrictedSwitchPreference.java
- https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android14-release/src/com/android/settings/applications/appinfo/AppInfoDashboardFragment.java
- https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android14-release/src/com/android/settings/applications/specialaccess/notificationaccess/ApprovalPreferenceController.java
- https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/android14-release/src/com/android/settings/ActionDisabledByAppOpsDialog.java
- https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/android15-release/service/java/com/android/ecm/EnhancedConfirmationService.java
- https://developer.android.com/sdk/api_diff/33/changes/android.content.pm.InstallSourceInfo
- https://learn.microsoft.com/en-us/dotnet/api/android.app.admin.devicepolicymanager.setpermittedaccessibilityservices?view=net-android-35.0
- https://support.google.com/android/answer/12623953
- https://support.google.com/android-xr/answer/16637308 · https://support.google.com/android-xr/answer/16638859 · https://support.google.com/android-xr/answer/16659361
- https://developer.android.com/develop/xr · /develop/xr/check-mobile-app-readiness · /develop/xr/package-and-distribute · /develop/xr/jetpack-xr-sdk/transition-home-space-to-full-space
- https://developer.android.com/design/ui/xr/guides/foundations · https://developer.android.com/codelabs/xr-fundamentals-part-1
- https://developer.android.com/jetpack/androidx/releases/xr-compose
- https://www.samsung.com/us/support/answer/ANS10007565/ · https://www.samsung.com/us/support/answer/ANS10007502/
- https://www.techinsights.com/blog/qualcomm-snapdragon-xr2-gen-2-ai-integration-digital-floorplan-analysis
- https://www.spacebar.news/samsung-galaxy-xr-review/ · https://vrarwiki.com/wiki/Samsung_Galaxy_XR
