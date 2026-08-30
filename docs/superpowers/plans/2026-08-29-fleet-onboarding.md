# 4.2 Implementation Plan — Fleet Onboarding (the detector, Play Asset Delivery, one flow)

Executes `docs/superpowers/specs/2026-08-29-fleet-onboarding-design.md` (committed 5683135; its
delivery-design decisions and its census table are **binding**) against the verified facts in
`docs/superpowers/research/2026-08-29-pad-soc-delivery.md`. Branch `feat/4.2-fleet-onboarding`,
off main at `431ab85` (the merged 3.7 + 4.0 + 4.1 stack plus the L9 owner's-pick commit — this
plan assumes all of it, including the arming epoch, the per-tier decline record, the set-typed
offer gate and turbo heading the capable steer). **Eight tasks, F1 → F8, ID order IS execution
order.**

The acceptance bar is the spec's: *the owner installs from the Play internal test track on the
Fold6 and reaches turbo dictation without touching adb.* This plan stops there. No Play listing
work, no census expansion past published packages, no cross-load experiment, no 3.8 cloud/Gemini
items.

> **F1 is the authority, not a lookup table.** Everything downstream — which skel is staged (F2),
> which digests an arrival must hash to (F3), which device group a pack variant targets (F4),
> which tiers a chooser may recommend (F6/F7) — reads the one census F1 lands. Play targeting is
> a bandwidth optimization; the research's own architectural conclusion (§6) is that **the app
> gate remains the correctness authority**, because an unmatched device can never be *prevented*
> from receiving the (empty) default variant. Every task below treats a Play answer as a hint and
> the census + sha256 + metadata cross-check as the decision.

---

## Global Constraints

Every task's requirements implicitly include this section.

### Build / test commands (PowerShell, repo root — set JAVA_HOME on every invocation)

- Full JVM suite: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
- One class: append `--tests "com.whispereverywhere.<pkg>.<Class>"` (repeat `--tests` for several)
- Compile + native: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`
- The bundle (F4 onward, pack payload built): `.\gradlew.bat :app:bundleDebug --no-daemon` — heavy
  (~4.4 GB of pack assets); **run only where a task's battery or the run-book says to**, never as
  a routine check.

Absolute interpreters only — `adb` is not on PATH
(`& "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"`), and neither is `python`
(`& "C:\Users\bastr\AppData\Local\Programs\Python\Python313\python.exe"`). Never use a bare `2>&1`
on a native exe from PowerShell (5.1 wraps stderr into ErrorRecords and flips `$?` even on exit 0).
`unzip` does not exist here — inspect an APK, AAB or AAR with
`[System.IO.Compression.ZipFile]::OpenRead($path).Entries`.

### Output paths (outside the repo — root `build.gradle.kts:17-22` relocates `buildDirectory`)

- Debug APK: `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk`
- Debug AAB: `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\bundle\debug\app-debug.aab`
- JVM test results (XML): `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest`
- The fleet asset workspace (vendor zips + extractions, F3): `C:\Users\bastr\.androidbuild\fleet-packs\`
- The 4.1 turbo vendor zip (already local, CRC-clean):
  `C:\Users\bastr\.androidbuild\npu-model-lab\whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8gen3.zip`

### NEVER install via Gradle — and the one sanctioned exception (a bounded window) to "never uninstall"

**NEVER run `:app:installDebug` or `:app:connectedDebugAndroidTest`.** Both uninstall first and
destroy the owner's on-device models (the 4.0 small pair, the 4.1 turbo pair, the ggml tiers —
this has happened twice). Every device install is owner-run and data-preserving:
`& "...\adb.exe" install -r <apk>`.

**bundletool `--local-testing` is documented as "updates unsupported — uninstall between
installs", which collides head-on with the rule above.** The collision is resolved in the F8
run-book, not silently: **the sanctioned exception is the bounded F8 §1→§2 local-testing
WINDOW, not one uninstall — it contains between two and four of them**, each named in the
run-book with its restore path (§1's three rungs need up to three — one before the first local
install where a prior build exists, one between each rung — and §2's pre-track install adds one
more). Each destroys everything in `filesDir`; the refusal rungs need no models at all, and the
final state is restored through the exact flows under test (the Play fetch for the NPU pair, the
in-app download for the ggml tier) — never by hand, because a hand-restore would un-test the
flow. The window is scheduled as the LAST device work of the branch, and no task other than F8's
run-book may prescribe an uninstall.

### Test evidence is XML aggregation (binding)

Evidence is the JUnit XML — never a Gradle summary line, never a green console line. Purge the
results directory before a measured run; abort on a dirty tree (`git status --porcelain` empty
before you measure). Every measured run must show `> Task :app:testDebugUnitTest` **without**
`UP-TO-DATE` (the hazard is documented verbatim in `app/build.gradle.kts` above the test-inputs
block), and the XML timestamps must postdate the run. If in doubt, `--rerun-tasks`.

```powershell
$dir = 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest'
Remove-Item "$dir\TEST-*.xml" -ErrorAction SilentlyContinue
# ... run the suite ...
$files = @(Get-ChildItem $dir -Filter 'TEST-*.xml')
$t=0;$f=0;$e=0;$s=0
foreach ($x in $files) { $d = [xml][System.IO.File]::ReadAllText($x.FullName); $t += [int]$d.testsuite.tests; $f += [int]$d.testsuite.failures; $e += [int]$d.testsuite.errors; $s += [int]$d.testsuite.skipped }
"suites=$($files.Count) tests=$t failures=$f errors=$e skipped=$s"
```

`[System.IO.File]::ReadAllText`, never `Get-Content -Raw` (PS 5.1 reads BOM-less UTF-8 as ANSI).

**Baseline at the branch point: 146 suites / 1,677 tests / 0 failures / 0 errors / 0 skipped**
(the merged main total, re-measured from raw XML at the 4.1 merge and recorded in the fleet
ledger). Per-task deltas below sum to **+4 suites / +128 tests → 150 suites, 1,805 tests**; F8
computes the branch total once, from a forced-fresh run, and reports **measured**, never
estimated.

### JVM test rules (TDD throughout)

- Write the failing test, run it, watch the *named* failure, then implement. Every task states its
  expected red.
- Tests live under `app/src/test/java/com/whispereverywhere/...`. **JUnit 4 only. No Robolectric,
  no mocking framework** (`junit:junit:4.13.2` + `kotlinx-coroutines-test`; `testOptions` at
  `app/build.gradle.kts:216-220` — `unitTests.isReturnDefaultValues = true`, so `android.util.Log`
  is a no-op and no JVM test can observe an emitted line).
- Emission is pinned *structurally*, with the house idiom: `source(relative)` +
  `liveOffsets`/`liveLineCount`. Template: `SegmentTimingTest.kt:87-128`. Line prefixes are
  **contiguous single string literals in source**; never build one by concatenation.
- **No JVM test may name `NpuWhisperBackend`, `QnnAsrNative`, `NpuPackController`, or anything
  that reaches them.** The first two dlopen (`QnnAsrNative.<clinit>` runs
  `System.loadLibrary("qnnasr")`); the third constructs `AssetPackManagerFactory`, an
  Android-bound Play service client. Their invariants are pinned as source text; their pure logic
  lives in `NpuGate`/`NpuFleetCensus`/`NpuPackFetch`/`NpuPackMetadata` where it is executed.
  `com.google.android.play.core.assetpacks.model.AssetPackStatus` and `AssetPackErrorCode` are
  plain `@interface` constant holders with no Android runtime dependency and MAY be referenced
  from a JVM test (F5 verifies this claim at its red step; if the classes fail to load, the
  equality pins fall back to source pins and the task says so).
- New assets, new tools scripts, and any file pinned by SOURCE TEXT must join the
  `inputs.files(...)` `sourcePinnedInputs` list in `app/build.gradle.kts` (**~30 entries today**),
  or a comment-only or asset-only edit leaves `:app:testDebugUnitTest` UP-TO-DATE and the pin
  passes against stale evidence.

### The standing lessons (4.0 + 4.1 ledgers — binding on every pin in this plan)

1. **Presence vs ORDER.** A pin that asserts a needle exists proves nothing about where it sits.
   Where the invariant is an order, pin the order (`liveOffsets`, site A before site B).
2. **Source order vs happens-before.** A source-order pin over two statements proves nothing about
   the order of their *effects* when either statement queues work onto another executor. Where the
   answer is "no, that does not order the effects", the invariant must be carried by identity or
   state (the arming epoch is the standing example — and F5's remove-after-stage rule is this
   plan's).
3. **Deferral needs a NAMED trigger.** Every out-of-plan item below names the change that revives
   it, and that name must appear in the dispatch of any task that could make it.
4. **Diagnostic strings are not pins** (4.1 L2, N11). An assertion that matches a *message* can be
   satisfied by the neighbor's message. Pin the predicate, then the message.
5. **Live-scope your counts.** Absence assertions and count assertions go through
   `liveOffsets`/`liveLineCount` like every sibling, so a comment cannot answer a predicate.
   (Known instrument bound, L5 review M4: the comment filter is line-START scoped, so a trailing
   `// …` comment quoting a needle still counts — do not write needles into trailing comments.)
6. **An executed kill beats a source pin where the mutation is reachable** (4.1 L7 IMP-1). Where a
   pure function exists, the test executes it; source pins are for what JVM tests cannot reach.

### Native verification rule

No task in this plan edits `.cpp`. The native battery is therefore: (1) `:app:assembleDebug`
green (still builds `libwhisper_jni.so` and `libqnnasr.so` — the packaging edits in F2 can break
this without touching a source file); (2) the full JVM suite green; (3) the named owner device
session, recorded in F8's run-book and never claimed done by the implementer.

### External dependencies required

- **Network to the Qualcomm S3 bucket, ~3.8 GB, in F3** (the measure step): three turbo vendor
  zips (859,689,781 + 860,709,426 + 871,118,306 B) and four small vendor zips (the 8gen3 one is
  293,598,974 B; the other three are gated by manifest + HTTP checks and measured). URLs are
  resolved from the two pinned Hugging Face release manifests
  (`.../Whisper-Large-V3-Turbo-Quantized/resolve/main/release_assets.json`,
  `.../Whisper-Small-Quantized/resolve/main/release_assets.json`, release **v0.61.0**), never
  hand-typed. Disk: ~12 GB free under `C:\Users\bastr\.androidbuild\fleet-packs\`.
- **Google Maven**: `com.google.android.play:asset-delivery-ktx:2.3.0` — the long-GA client
  library (latest on Google Maven, verified live 2026-08-29). The AI-pack twin
  (`ai-delivery:0.2.0-beta01`) is the spec's named one-day fallback and is NOT added.
- **The resolved `qnn-runtime:2.49.0` AAR** is already in the Gradle cache at
  `C:\Users\bastr\.gradle\caches\modules-2\files-2.1\com.qualcomm.qti\qnn-runtime\2.49.0\5fb50c874f213bb13261a124088aa4e757a7ac85\qnn-runtime-2.49.0.aar`
  (67,007,460 B) — F2's four skels come out of it; no download needed.
- **bundletool 1.18.3** (`bundletool-all-1.18.3.jar`, latest GitHub release, verified live
  2026-08-29) — F8's run-book only; downloaded to `C:\Users\bastr\.androidbuild\fleet-packs\`.
- AGP is **8.13.2** (root `build.gradle.kts:2`) — above the ≥ 8.10 floor device-targeting
  requires. The config API additionally needs
  `android.experimental.enableDeviceTargetingConfigApi=true` in `gradle.properties` (F4).

### The proprietary boundary (unchanged, and two new walls)

Model bytes, QNN headers and every extracted `.so` stay out of the repo (`.gitignore` already
carries `*.so`, `*.dlc`, `*.context`, `Qnn*.h`, `app/src/main/cpp/include/QNN/`). New in 4.2:
**the pack payloads are BUILD artifacts** — `tools/build_asset_packs.py` assembles them from the
vendor zips into `npu_turbo/src/main/assets/model#group_*/` and
`npu_small/src/main/assets/model#group_*/`, and those directories are gitignored inside each pack
module (F4). The committed pack modules contain only their build file, the empty default variant
marker and the `.gitignore` that keeps it that way. Nothing under `fleet-packs\` is ever staged.

### Commit trailer (exact, every commit)

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Shared contracts (load-bearing exact names — identical at every mention)

| Contract | Exact signature | Owner | Consumers |
|---|---|---|---|
| Family row | `data class NpuSocFamily` (`com.whispereverywhere.npu`): `id: String`, `packGroup: String`, `htpVersion: Int`, `socModels: Set<String>`, `skelAsset: String`, `skelBytes: Long`, `skelSha256: String`, `evidence: String` | F1 | F2, F3, F4, F5, F6 |
| Census | `object NpuFleetCensus`: `families: List<NpuSocFamily>`, `familyById(id: String): NpuSocFamily?`, `data class PackArtifact(familyId, tierId, chipsetKey: String, vendorZipBytes: Long, encoderBytes: Long, encoderSha256: String, decoderBytes: Long, decoderSha256: String)`, `artifacts: List<PackArtifact>` (F3), `fun artifactFor(familyId: String, tierId: String): PackArtifact?` (F3), `fun fetchableTierIds(family: NpuSocFamily?, capable: Boolean, gatedTierIds: Set<String>, installedGatedIds: Set<String>): Set<String>` (F6) | F1/F3/F6 | F2–F7 |
| Gate | `NpuGate.familyFor(socModel: String?, socManufacturer: String?): NpuSocFamily?` — exact-string, null/unknown deny; `NpuGate.isSocSupported(socModel, socManufacturer) == (familyFor(...) != null)`; `SUPPORTED_SOCS` becomes `NpuFleetCensus.families.flatMap { it.socModels }.toSet()` | F1 | F2 (backend), F6 (app) |
| App family memo | `WhisperEverywhereApp.npuSocFamily: NpuSocFamily?` — `by lazy { NpuGate.familyFor(npuSocModel, npuSocManufacturer) }`; **no new `Build` read sites** (the two private getters stay the only two, per the counted pin) | F2 | F2 (selector), F3 (import), F5 (install), F6 (fetchable) |
| Backend | `NpuWhisperBackend(paths, appContext, spec, family: NpuSocFamily)` — the fourth parameter is **required, no default** (the L2 doctrine); `NpuBackendSelector.backendFor(tierId, offeredNpuTierIds, declinedTiers, paths, appContext)` resolves it from the app memo and answers `WhisperNativeBackend` when it is null | F2 | F2 |
| Family-aware verify | `NpuAssetImport.requiredEntriesFor(model: WhisperModel?, artifact: NpuFleetCensus.PackArtifact?): Map<String, RequiredEntry>` — **no default on `artifact`**; null → `emptyMap()` (refuses everything) | F3 | F3 (import), F5 (pack install) |
| Pack metadata | `object NpuPackMetadata`: `data class Meta(version: Int, tierId: String, familyId: String, packGroup: String, htpVersion: Int, socModels: List<String>, entries: List<Entry>)`, `data class Entry(name: String, bytes: Long, sha256: String)`; `fun parse(json: String): Meta` (throws `IllegalStateException` with a named reason); `fun crossCheckRefusal(meta: Meta, family: NpuSocFamily, artifact: PackArtifact, tierId: String): String?` — null when consistent | F3 | F3 (import peek), F5 (install) |
| Fetch machine | `object NpuPackFetch`: `PACK_BY_TIER: Map<String, String>` = `{"npu" → "npu_small", "npu-turbo" → "npu_turbo"}`; `tierForPack(packName: String): String?`; `sealed interface FetchState` { `Idle`, `Pending`, `Downloading(soFar: Long, total: Long)`, `Transferring`, `NeedsConfirmation`, `Verifying(soFar: Long, total: Long)`, `Installed`, `Failed(reason: String)`, `Cancelled` }; `fun advance(status: Int, errorCode: Int, soFar: Long, total: Long): FetchState`; `fun failureReason(errorCode: Int): String` | F5 | F5, F6, F7 |
| Fetch shell | `object NpuPackController` (process-scoped, single-flight, the `NpuImportController` shape): `state: StateFlow<NpuPackFetch.FetchState>`, `activeTierId: StateFlow<String?>`, `fun start(tierId: String): Boolean`, `fun cancel()`, `fun confirm(activity: Activity)` | F5 | F6, F7 |
| Pack install | `WhisperModelManager.installFromPack(tierId: String, family: NpuSocFamily, packAssetsPath: String, onProgress: (Long, Long) -> Unit): NpuAssetImport.ImportState` | F5 | F5 |
| Fetchable set | `WhisperEverywhereApp.fetchableNpuTierIds(): Set<String>` — capability half (family + probe) AND census artifact row exists AND not installed; disjoint from `offeredNpuTierIds()` by construction | F6 | F6, F7 |
| Diag | `NpuDiag.packLine(tierId, packName, status: String, soFar: Long, total: Long)`, `NpuDiag.packOk(tierId, entries: Int, bytes: Long)`, `NpuDiag.packRefused(tierId, reason)` — the `pack: ` prefix is one contiguous literal per builder; the `npu: offer` line is **unchanged** | F5 | F5, F8 |
| Onboarding | `OnboardingLogic.Step` gains `LANGUAGE` (PERMISSIONS → LANGUAGE → ENGINES → CLOUD); `fun languageRows(deviceLanguageTag: String): List<Pair<String, String>>`; `fun languageContinueEnabled(pickedCode: String?): Boolean`; `const val LANGUAGE_HINT = "Choosing a language makes multilingual transcription faster."` | F6 | F6 |

**Tier ids resolve through their one home** — `NpuAssetImport.TIER_ID` for `npu`,
`NpuModelSpec.TURBO.tierId` for `npu-turbo` — exactly as the catalog already does. `PACK_BY_TIER`
spells its keys through those two, never as fresh literals.

---

## Measured ground and BAKED facts

Everything below was measured on this machine for this plan on 2026-08-29, or is quoted from the
binding spec census / the live-verified research. **Bind against these values; let the guards
confirm them.**

### The four-family census (the spec's table, binding — evidence dates included)

| family id | packGroup | HTP | `socModels` (exact, complete) | turbo vendor zip (compressed B) | evidence |
|---|---|---|---|---|---|
| `8gen3` | `soc_8gen3` | 75 | `SM8650`, `SM8650-AC` | 859,786,903 | AI Hub v0.61.0 HEAD-verified 2026-08-29; Last-Modified 2026-08-25; device-executed (Fold6) 2026-08-29 |
| `8elite_galaxy` | `soc_8elite_galaxy` | 79 | `SM8750-AC` | 859,689,781 | AI Hub v0.61.0 HEAD-verified 2026-08-29; Last-Modified 2026-08-25; no device evidence |
| `8elite5_galaxy` | `soc_8elite5_galaxy` | 81 | `SM8850-AD` | 860,709,426 | same |
| `7gen4` | `soc_7gen4` | 73 | `SM7750` | 871,118,306 | same |

Release-manifest chipset keys (the strings `release_assets.json` indexes by, used to RESOLVE the
vendor URLs rather than hand-typing them): `8gen3`, `8-elite-for-galaxy`,
`8-elite-gen5-for-galaxy`, `7gen4`. Small-model zip for `8gen3`: 293,598,974 B.

**CPU by census, stated with the evidence date (2026-08-29 re-fetch of both release manifests):**
8 Gen 2 (`SM8550`/`SM8550-AC`), 8+ Gen 1 (`SM8475`), 8 Gen 1 (`SM8450`), 888 (`SM8350`),
non-Galaxy 8 Elite (`SM8750`), non-Galaxy 8 Elite Gen 5 (`SM8850`) have **no published w8a16
package** — they are CPU devices, and the census records them as such so the next reader knows the
absence was checked, not overlooked. The 7gen4-v73-on-8gen2 cross-load curiosity is explicitly
OUT (research §7 — unverified). The research §3 sketch listed plain `SM8750`/`SM8850` inside the
Play groups; **the spec's table supersedes it** and the plan follows the spec: the app census and
the Play groups carry the same strings, because two censuses is how a device passes one gate and
fails the other.

### The four HTP skels + stubs — measured out of `qnn-runtime-2.49.0.aar` today

| entry (`jni/arm64-v8a/`) | bytes | sha256 |
|---|---:|---|
| `libQnnHtpV73Skel.so` | 17,909,588 | `7be4f8a4ec21a9d8d51f59c73094154f42d2f8fc91cfaadaef03441b77d7ddb1` |
| `libQnnHtpV75Skel.so` | 17,913,608 | `a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c` |
| `libQnnHtpV79Skel.so` | 17,721,548 | `9cad65a621d154e5282ea9d2849d0a8838932ed91dc7e2514db4e992e2d933c6` |
| `libQnnHtpV81Skel.so` | 18,844,384 | `b3453265c4574c69bb446bcb98dda117ded531b86b2307e0f02c595050fab8b1` |
| `libQnnHtpV73Stub.so` | 772,200 | `f89096915f6707c9e7a780deaf47dedfec5cb7e3e2c3459208ef66e3861441ba` |
| `libQnnHtpV75Stub.so` | 772,200 | `78025b9ff8c5cf1c0017560bee0f447ae58fb8255f5fca0daca7d6a4818b909e` |
| `libQnnHtpV79Stub.so` | 772,200 | `9908fb2cdc22bd35651e358bc851d203dcb170dec52df0f8779437863158599c` |
| `libQnnHtpV81Stub.so` | 796,352 | `a5235e7927a5074c4d22244696f84f2c007d90f2f609c6ba0f047e2f0c6abf65` |

The V75 skel row reproduces 4.1 L6's two shipped pins **exactly** — which is what makes this
table a second reading rather than a new source. Base-module cost of the fleet, stated honestly
(F2): assets grow by the three new skels, +54,475,520 B raw; `lib/` grows by the three returning
stubs, +2,340,752 B. **The skels do NOT ride in the packs**: AI packs forbid code outright and
standard asset packs are documented as asset-only — a `.so` in a pack is a store-pipeline gamble
this plan does not take. One base APK covers all four families (the research §8 finding), and the
device stages exactly one skel into `filesDir`.

### The 8gen3 pair digests (already the catalog's — the reference family)

| tier | file (repacked name) | bytes | sha256 |
|---|---|---:|---|
| `npu` | `encoder_qairt_context.bin` | 132,927,488 | `3e92ac26545b6b9d22ecfab594ae57523134006e2722b09fa10e16b193e9e5ec` |
| `npu` | `decoder_qairt_context.bin` | 225,316,864 | `fda23d731e6b0ab7fb0a50373a49efe2d1792faa5dad456837624d8b8e44b0e4` |
| `npu-turbo` | `turbo_encoder_qairt_context.bin` | 775,831,552 | `f7d11c08a20ea671f59b3ace2f9421da00b06170ac9fe946f29092ee59be6bbe` |
| `npu-turbo` | `turbo_decoder_qairt_context.bin` | 295,854,080 | `c19b067766180843fca6266531605bf037820c5e5ae178bd6dc03785df4c6ae4` |

The other three families' pair digests **do not exist anywhere yet** — no one has downloaded
those six zips. F3 measures them (the script downloads, CRC-checks, extracts, hashes) and writes
them into `NpuFleetCensus.artifacts` in the same commit, under tests that refuse a blank, a
non-64-hex, or a duplicate digest. That is the same measure-then-pin discipline the 4.1 plan
applied at plan time, executed at task time because 3.8 GB was not a plan-authoring download.

### The bundle layout (research §5, verbatim shape)

```
npu_turbo/                                  ← asset-pack module (standard, NOT ai-pack)
  build.gradle.kts: com.android.asset-pack; packName "npu_turbo"; deliveryType "on-demand"
  src/main/assets/
    model#group_soc_8gen3/           turbo_encoder_qairt_context.bin  turbo_decoder_qairt_context.bin  metadata.json
    model#group_soc_8elite_galaxy/   …
    model#group_soc_8elite5_galaxy/  …
    model#group_soc_7gen4/           …
    model/                           .gitkeep       ← the EMPTY default variant
npu_small/                                  ← same shape; entries keep the 4.0 bare names
app/device_targeting_config.xml             ← committed; the exact groups below
app/build.gradle.kts: assetPacks += listOf(":npu_turbo", ":npu_small")
                      bundle { deviceTargetingConfig; deviceGroup { enableSplit = true; defaultGroup = "other" } }
gradle.properties:    android.experimental.enableDeviceTargetingConfigApi=true
```

App-side read path after delivery: `getPackLocation(pack)?.assetsPath() + "/model/<file>"` — the
`#group_` suffix is stripped at build time; each device sees exactly one variant.

### Our pack `metadata.json` (schema, one per group variant, written by the build script)

```json
{"version": 1, "tierId": "npu-turbo", "familyId": "8gen3", "packGroup": "soc_8gen3",
 "htpVersion": 75, "socModels": ["SM8650", "SM8650-AC"],
 "entries": [
   {"name": "turbo_encoder_qairt_context.bin", "bytes": 775831552, "sha256": "f7d11c08a20ea671f59b3ace2f9421da00b06170ac9fe946f29092ee59be6bbe"},
   {"name": "turbo_decoder_qairt_context.bin", "bytes": 295854080, "sha256": "c19b067766180843fca6266531605bf037820c5e5ae178bd6dc03785df4c6ae4"}]}
```

It is **our** file (the vendor's own `metadata.json` never ships), it is assembled FROM the
census, and at arrival it must EQUAL the census row — a disagreement is a mis-built pack, refused
by name. Its job is the spec's family cross-check: a wrong-variant delivery (a Play targeting
surprise, an asserted-group local test) is refused before a byte is hashed.

### Library and tool versions (verified live 2026-08-29)

- `com.google.android.play:asset-delivery-ktx:2.3.0` — latest on Google Maven, GA.
- `bundletool-all-1.18.3.jar` — latest GitHub release.
- AGP 8.13.2 (already the repo's), Kotlin 2.0.21, minSdk 26 — the SoC selector's API-31 floor is
  handled by the existing guarded reads + the empty default (research §3: the two floors
  coincide, and pre-31 devices land in the empty default by construction).

---

## Task F1 — The fleet census and the gate: four families, exact strings, CPU by census

Spec: "The detector (the fleet ladder)". `NpuGate` grows from the owner-device allowlist to the
four-family census. Devices outside it answer CPU — no NPU UI, no pack fetch, the CPU tiers
exactly as shipped.

**Files**
- `app/src/main/java/com/whispereverywhere/npu/NpuFleetCensus.kt` (new)
- `app/src/main/java/com/whispereverywhere/npu/NpuGate.kt` (edit)
- `app/src/test/java/com/whispereverywhere/npu/NpuFleetCensusTest.kt` (new)
- `app/src/test/java/com/whispereverywhere/npu/NpuGateTest.kt` (edit)

**Steps**

1. `NpuSocFamily` + `NpuFleetCensus.families` — the four rows of the census table above,
   verbatim: ids, `packGroup`s, HTP versions, `socModels` sets, the four skel rows from the
   measured table (asset name, bytes, sha256), and the `evidence` string per row (the dates
   above, one line each). The KDoc carries the two maintenance rules as prose with teeth:
   **the -AC/-AD trap** (Play matches literal `Build.SOC_MODEL` strings; a missing suffix lands a
   capable device in the empty default — fail-safe, lost coverage; the census is where suffixes
   are added, with evidence, and the device-group XML must be regenerated in the same commit —
   F4's layout pin enforces the agreement), and **widening is a measurement, never a guess** (the
   4.0 rule, unchanged: a context binary on the wrong HTP does not degrade, it fails to
   deserialise — or worse, does not).
2. `CPU_BY_CENSUS: Map<String, String>` — the six absent parts above mapped to their evidence
   line (`"SM8550" to "8 Gen 2 — no published w8a16 package as of 2026-08-29 (both release
   manifests re-fetched)"` and siblings). It is documentation with an assertion attached: the
   test proves it disjoint from every family's `socModels` and proves `familyFor` answers null
   for every key. The cross-load curiosity is named OUT in this KDoc with its trigger (an 8 Gen 2
   device materialising for the 30-minute experiment the research describes).
3. `NpuGate`: `SUPPORTED_SOCS` becomes `NpuFleetCensus.families.flatMap { it.socModels }.toSet()`
   (derived — a fifth family joins by editing the census, nowhere else);
   `familyFor(socModel, socManufacturer): NpuSocFamily?` — manufacturer in
   `SUPPORTED_SOC_MANUFACTURERS` (unchanged: `{QTI, Qualcomm}`), then the exact-string family
   lookup; `isSocSupported` becomes `familyFor(socModel, socManufacturer) != null` so the two can
   never disagree. Exact matching doctrine (no `startsWith`, no `ignoreCase`, null/`unknown`
   deny) kept verbatim in the KDoc — it is now a fleet rule, not an owner-device rule.
4. Nothing else moves. `NpuWhisperBackend.isTierAvailable` still composes
   `isSocSupported(...) && nativeProbe(...)`; the offer line is untouched; the chooser sets are
   untouched. This task is pure census + pure gate, fully executed on the JVM.

**Test (red first)**
- `NpuGateTest` (write these against the CURRENT gate first — that is the red): every census
  string × both manufacturer spellings passes; **`SM8750` plain fails while `SM8750-AC` passes**
  (the sharpest row — the suffix is the difference between a covered Galaxy bin and an uncovered
  plain bin); `SM8850` plain fails / `SM8850-AD` passes; `SM7750` passes; every `CPU_BY_CENSUS`
  key fails; `sm8650` (case), `SM8650X` (superstring), `null`, `"unknown"`, `"UNKNOWN"` fail;
  `familyFor` and `isSocSupported` agree on every row above (executed equivalence, not a source
  pin).
- `NpuFleetCensusTest`: four families; ids, packGroups, skel assets all distinct; htpVersions ==
  {73, 75, 79, 81}; socModels pairwise disjoint across families; every skelSha256 is 64-hex and
  all four distinct; the 8gen3 row's skel pair equals `17_913_608L` /
  `a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c` (the 4.1-shipped values —
  the continuity pin); every `evidence` contains `"2026-08-2"` (a date was recorded, not a
  vibe); `CPU_BY_CENSUS` disjointness.

**Expected red:** `NpuGateTest > aGalaxyEightEliteSm8750AcPassesTheGate FAILED` (the current
two-string allowlist denies it).

**Battery:** full JVM suite.
**Expected delta:** +1 suite, +20 tests.

**Commit**

```
feat(npu): the fleet census — four families, exact strings, CPU by census

NpuGate grows from the owner-device allowlist to the published-coverage census:
8gen3/v75, 8 Elite for Galaxy/v79, 8 Elite Gen 5 for Galaxy/v81, 7 Gen 4/v73 —
every soc_model suffix variant written out, because Play and this gate both match
literal strings and a missing -AC lands a capable device in the empty default.

Devices outside the census answer CPU, and the census SAYS SO per part with an
evidence date: 8 Gen 2 / 8 Gen 1 / 888 / non-Galaxy 8 Elite have no published
w8a16 package, so their absence is a checked fact, not an oversight. The
cross-load curiosity stays out, with its trigger named.

isSocSupported is now derived from familyFor, so the offer gate and the family
resolution can never disagree about a device.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task F2 — Four skels in the base, one staged per device

The 4.1 L6 relocation, fleet-wide. Under `extractNativeLibs="false"` a `lib/` skel is provably
unopenable (the FastRPC loader needs a real file on `ADSP_LIBRARY_PATH`), so every family's skel
ships in assets and exactly the device's own is staged into `filesDir` at arm.

**Files**
- `app/build.gradle.kts` (edit — `extractQnnSkel` loops the four; jniLibs excludes adjusted)
- `app/src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt` (edit — `npuSocFamily` memo)
- `app/src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt` (edit — the family
  parameter; per-family skel stage)
- `app/src/main/java/com/whispereverywhere/transcription/NpuBackendSelector.kt` (edit)
- `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (edit — construction
  site named-argument needle)
- `app/src/main/java/com/whispereverywhere/npu/NpuAssetStage.kt` (edit — folded item)
- `app/src/test/java/com/whispereverywhere/npu/NpuSkelPackagingTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/npu/NpuAssetStageTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/transcription/NpuBackendWiringTest.kt` (edit)

**Steps**

1. **`extractQnnSkel` extracts all four.** The single-entry body becomes a loop over a local
   `qnnSkels` table of Triples — exactly the four measured rows, full literals:
   ```kotlin
   val qnnSkels = listOf(
       Triple("libQnnHtpV73Skel.so", 17_909_588L, "7be4f8a4ec21a9d8d51f59c73094154f42d2f8fc91cfaadaef03441b77d7ddb1"),
       Triple("libQnnHtpV75Skel.so", 17_913_608L, "a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c"),
       Triple("libQnnHtpV79Skel.so", 17_721_548L, "9cad65a621d154e5282ea9d2849d0a8838932ed91dc7e2514db4e992e2d933c6"),
       Triple("libQnnHtpV81Skel.so", 18_844_384L, "b3453265c4574c69bb446bcb98dda117ded531b86b2307e0f02c595050fab8b1"),
   )
   ```
   (the same four (bytes, sha256) pairs the F1 census rows carry — two spellings, pinned equal
   by step 6, per the fetchSherpaAar discipline). Each entry: missing-from-AAR → the same named
   `GradleException`; size and digest `check(...)`s per entry, each naming the entry and the
   census as the co-updated reader.
2. **jniLibs excludes:** the three skel excludes for V73/V79/V81 STAY (their skels ship via
   assets, same as V75); **the V73/V79/V81 `*Stub.so` excludes are DELETED** — the stub is the
   CPU-side half `libQnnHtp.so` dlopens straight out of the APK, and a family whose stub is
   excluded arms up to `nativeInit` and dies inside the QNN loader with nothing naming why. V68/
   V69 skel+stub and the Dsp/Gpu/Prepare excludes stay (still no covered family). The comment
   block is rewritten to say "one stub per census family in lib/, one skel per census family in
   assets/" so the next family's edit has a rule, not an example.
3. **`WhisperEverywhereApp.npuSocFamily`**: `val npuSocFamily: NpuSocFamily? by lazy {
   NpuGate.familyFor(npuSocModel, npuSocManufacturer) }` — reads the two existing private guarded
   getters, adds **no** new `Build` read (the `ChooserSteerWiringPinTest` count is unchanged, and
   the test proves it by the same count it already runs).
4. **`NpuWhisperBackend` gains `family: NpuSocFamily` — required, no default** (the same L2
   doctrine as `spec`, and for the same shape of reason: a defaulted family would stage the
   default's skel under another family's silicon, and the failure is a FastRPC mystery on a
   device, not a compile error). The `skel` stage becomes
   `NpuAssetStage.stagedPathWithMarker(appContext, family.skelAsset, family.skelBytes,
   family.skelSha256)`; the refusal message names `family.skelAsset` and `family.id`. The
   `SKEL_BYTES`/`SKEL_SHA256` companions are DELETED — the census is the one home, and
   `NpuSkelPackagingTest` re-pins the gradle literals against `NpuFleetCensus` instead (step 6).
   Stage name stays `skel`; nothing else in `load`'s order moves.
5. `NpuBackendSelector.backendFor` resolves the family beside the spec:
   `val family = (appContext.applicationContext as? WhisperEverywhereApp)?.npuSocFamily` — null →
   `WhisperNativeBackend(...)`, same clause shape as the null-spec answer, with the comment
   stating why the state is near-unreachable (routing requires the offered set, which requires
   `npuCapableDevice`, which requires the gate) and refused anyway ("safe by a property of a
   different object" is the shape this stack has paid for twice). The construction site keeps
   **all named arguments**; `FloatingBubbleService`'s pinned block needle is re-spelled in this
   commit (a conscious pin break, resolved in-commit).
6. `NpuSkelPackagingTest` re-spec: for EACH census family — the skel exclude present exactly once
   in jniLibs; the gradle table carries that family's exact bytes AND sha256 literals; **the
   gradle literals equal `NpuFleetCensus`'s values** (executed equality — the test reads the
   census object and greps the build script, so the build's copy and the runtime's check cannot
   drift); the stub exclude for that family is ABSENT (live-zero — the V68/V69 stub excludes must
   still be present, which keeps the assertion honest); the srcDir registration and merge-task
   ordering pins unchanged.
7. **Folded 4.1 items** (this task opens every file they live in):
   - **L1 m2** — the `melCtx != 0L || armedEpoch != 0L` guard comment in
     `releaseNpuResources` overstates the whisper-side fact; narrow the claim, not the guard.
   - **L1 m8** — state, at `load`'s gate entry, that the single `NativeComputeGate.serialized`
     hold across `nativeInit` → `nativeEpoch` is load-bearing for the epoch handshake (two JNI
     crossings, one gate hold — the reason the pair cannot interleave with another arm).
   - **L1 m6** — `NpuBackendWiringTest.memberBody` still cuts on a fixed `"\n    }\n"`, the same
     defect L1 fixed in `NpuNativeContractTest.kotlinMemberBody`. Terminate on the closing brace
     at the recorded indent, and exercise the fix by the member this task adds to the file it
     reads.
   - **L6 minor (final-triage)** — `NpuAssetStage`'s two unchecked `marker.delete()` returns:
     check them and emit one named `WE-DIAG` line on failure (a stale marker that survives a
     failed delete re-validates a corrupt skel by fast path — bounded, but a line is cheap).
     `NpuAssetStageTest` gains the refusal-path assertion.

**Test (red first)** — `NpuSkelPackagingTest > everyCensusFamilysSkelIsExtractedAndPinned FAILED`
(three families have no gradle rows yet).

**Battery:** `:app:assembleDebug`, then inspect the APK:

```powershell
[System.IO.Compression.ZipFile]::OpenRead($apk).Entries |
  Where-Object FullName -match 'QnnHtp' | Select-Object FullName, Length
```

Expected: four `assets/libQnnHtpV7*Skel.so` / `assets/libQnnHtpV81Skel.so` at exactly
17,909,588 / 17,913,608 / 17,721,548 / 18,844,384 B; **zero** `*Skel.so` under `lib/`; stubs
V73/V75/V79/V81 present under `lib/arm64-v8a/` at 772,200 / 772,200 / 772,200 / 796,352 B. Then
the full JVM suite. State the cost in the report: assets +54,475,520 B, lib +2,340,752 B — the
price of one APK covering four families, paid once, per the research §8 verdict.

**Expected delta:** +0 suites, +14 tests.

**Commit**

```
feat(npu): every census family's skel ships in assets; the device stages its own

The 4.1 L6 relocation, fleet-wide: under extractNativeLibs=false a lib/ skel is
provably unopenable, so V73/V79/V81 join V75 in generated assets — size- and
digest-asserted per entry out of the resolved AAR — and NpuWhisperBackend stages
exactly the device family's skel into filesDir at arm. The three stubs return to
lib/: they are the CPU-side halves libQnnHtp dlopens from the APK, and a family
whose stub is excluded dies inside the QNN loader with nothing naming why.

The backend takes its family as a required parameter with no default, the same
doctrine as the spec and for the same shape of reason: a defaulted family stages
the wrong DSP-side skel and the symptom is a FastRPC mystery on someone else's
phone. The census is the single home for all four (bytes, sha256) pairs; the
build script's literals are pinned equal to it.

Base cost stated, not hidden: +54.5 MB assets, +2.3 MB lib — one APK, four
families, one skel staged per device.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task F3 — The artifact census: measure the fleet, verify per family

The six unmeasured pairs get measured, the census gets its artifact rows, and every arrival route
(import today, packs in F5) verifies against **the device family's** digests instead of the
reference family's.

**Files**
- `tools/build_asset_packs.py` (new — `measure` mode this task; `build` + `delivery-zip` modes in F4)
- `app/src/main/java/com/whispereverywhere/npu/NpuFleetCensus.kt` (edit — `PackArtifact` rows)
- `app/src/main/java/com/whispereverywhere/npu/NpuPackMetadata.kt` (new)
- `app/src/main/java/com/whispereverywhere/npu/NpuAssetImport.kt` (edit — family-aware
  `requiredEntriesFor`)
- `app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt` (edit —
  `importNpuAssetPair` threads the family; the metadata peek)
- `app/build.gradle.kts` (edit — `sourcePinnedInputs` += `tools/build_asset_packs.py`,
  `NpuFleetCensus.kt`)
- `app/src/test/java/com/whispereverywhere/npu/NpuFleetCensusTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/npu/NpuPackMetadataTest.kt` (new)
- `app/src/test/java/com/whispereverywhere/npu/NpuAssetImportTest.kt` (edit)

**Steps**

1. **`tools/build_asset_packs.py measure`** (absolute interpreter; workspace
   `C:\Users\bastr\.androidbuild\fleet-packs\`). For each of the two models it fetches the pinned
   HF `release_assets.json` (release **v0.61.0** asserted — a different release string is a hard
   failure naming both), resolves the `precompiled_qnn_onnx` w8a16 zip URL for each census
   `chipsetKey`, then per zip:
   - HEAD first: 200, `Last-Modified` date `2026-08-25` asserted (the hash-stable re-upload event
     the research pinned; a bucket rewrite fails loudly rather than silently measuring new
     bytes), `Content-Length` asserted against the census's exact value where one exists (the
     four turbo zips + 8gen3 small) and recorded where not (the other three small zips);
   - download (skip when the local copy already matches length — the 4.1 turbo zip and workspace
     re-runs cost nothing), `zipfile.testzip()` CRC-clean;
   - read the vendor `metadata.json` and assert `chipset_attributes.htp_version` equals the
     census family's `htpVersion` **and** the encoder/decoder IO census equals `NpuModelSpec`'s
     row for the tier (turbo: 1/8/768,000/15,360,000 and 19/9/17,398,168/2,141,492; small:
     1/24/480,000/27,648,000 and 51/25/31,316,376/3,771,698) — the executed form of "per-SoC
     packs carry the SAME model";
   - stream-extract the two context binaries, sha256 during the copy, print one census row.
   The 8gen3 rows must reproduce the four known digests exactly — the run's self-check.
2. **`NpuFleetCensus.artifacts`** — eight `PackArtifact` rows (4 families × 2 tiers), written in
   this commit from step 1's printed table; the 8gen3 rows carry the values from the measured
   table above. `artifactFor(familyId, tierId)`. The script embeds the same 16 digests as
   literals in its own verification table, and `NpuFleetCensusTest` reads the `.py` by
   `source(relative)` and asserts every census digest appears there — the `pack_npu_zip.py`
   pattern, so the census and the instrument that fills the packs cannot drift.
3. **`NpuPackMetadata`** — `parse` (strict: version 1, every field present, entries exactly two,
   64-hex digests, named `IllegalStateException` otherwise) and `crossCheckRefusal(meta, family,
   artifact, tierId)`: null when `meta.tierId == tierId && meta.familyId == family.id &&
   meta.htpVersion == family.htpVersion && meta.packGroup == family.packGroup` and the two
   entries equal the artifact row name-for-name, byte-for-byte, digest-for-digest; otherwise one
   sentence naming the first disagreement ("this pack is the <meta.familyId> variant and this
   device is <family.id>" for the family arm — the wrong-variant case gets the clearest words
   because it is the one Play could plausibly produce).
4. **`requiredEntriesFor(model, artifact)`** — the map's digests and byte counts come from the
   ARTIFACT row when the model is a paired gated tier; `artifact == null` → `emptyMap()` (which
   refuses every entry — the existing missing-entries refusal fires; a device whose family is
   unknown must not import NPU bytes it cannot verify). **No default on the parameter** — every
   call site is forced to answer the family question, which is the entire hazard (the old
   one-argument form silently verified a v79 zip against 8gen3 digests: a TRUE refusal for the
   WRONG stated reason — "corrupted download" — on every non-reference device). This is a
   compile-red across `WhisperModelManager` and `NpuAssetImportTest`; resolved in this commit.
5. **`importNpuAssetPair`**: resolves `family = (context.applicationContext as?
   WhisperEverywhereApp)?.npuSocFamily` at entry; null → `Refused` with a named reason (the
   import affordance is capability-gated, so this is a belt for a suspenders failure). **The
   metadata peek**: when the picked zip carries a `metadata.json` entry ≤ 65,536 B, parse it and
   run `crossCheckRefusal` BEFORE any binary inflates — a v79 user who grabbed the 8gen3 zip
   learns "wrong family variant" in one second instead of "sha256 mismatch" after hashing 776 MB.
   A zip WITHOUT metadata (the 4.0/4.1 published zips) proceeds straight to the digest gate,
   unchanged — legacy zips stay importable on the reference family. `classifyEntry` still
   `Ignore`s the metadata entry itself (it is not a model file).
6. **Catalog cross-pin** (`NpuFleetCensusTest`): the 8gen3 artifact rows equal the catalog's four
   digests and four byte counts **value for value** — `WhisperCatalog` keeps its constants as the
   reference family's record (provenance + the published delivery zips), and this pin is what
   makes the two records one record. All 16 census digests 64-hex and pairwise distinct (a
   copy-paste between rows would install one family's decoder under another family's encoder with
   a passing metadata check).
7. *(A fold that turned out to be already done: the L5 review's M1 "seven tiers" comment was
   corrected by the 4.1 L8 carry set — `WhisperCatalogHelpersTest.kt:324` already reads "all
   eight tiers" on current main, verified for this plan. Nothing to do here; the row in the fold
   table says so, so the implementer does not hunt for a defect that does not exist.)*

**Test (red first)**
- `NpuAssetImportTest > requiredEntriesComeFromTheDeviceFamilysArtifactRow FAILED` (the
  one-argument form has no family to differ by), plus the compile-red the required parameter
  forces — both named here, both resolved in-commit.
- `NpuPackMetadataTest`: golden parse; every missing/malformed field named; the family-mismatch
  refusal names both families; the digest-mismatch arm defers to the entry equality (metadata
  cross-check is identity, the stream hash in F5 is integrity — two different guards, stated).
- `NpuFleetCensusTest`: the sixteen-digest census (distinctness, hex, the 8gen3 catalog
  equality); every artifact row's `vendorZipBytes` positive and — for the four turbo rows — equal
  to the spec table's exact values; the script-literal cross-pin.

**Battery:** the measure run's printed table recorded in the task report (with the 8gen3
self-check line); full JVM suite.
**Expected delta:** +1 suite, +26 tests.

**Commit**

```
feat(npu): the artifact census — six pairs measured, every arrival verifies per family

Nobody had ever downloaded the v79/v81/v73 packages; their digests existed
nowhere. The measure mode fetches all eight vendor zips through the pinned
release manifests (200 + Last-Modified 2026-08-25 + CRC + exact size where one
was known), asserts each family's vendor metadata carries the census htp AND the
same IO census as the spec row — per-SoC packs really do carry the SAME model —
and hashes the pairs. The census now carries all sixteen digests, pairwise
distinct, with the 8gen3 rows pinned equal to the catalog's.

requiredEntriesFor takes the device family's artifact row, with no default: the
one-argument form verified every family against 8gen3's digests, which refused
correct zips on every non-reference device with the WRONG stated reason. A zip
that carries our metadata.json is family-checked in one second, before 776 MB
inflates to learn the same thing.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task F4 — The asset packs and the device groups: modules, the config XML, the EMPTY default

Spec delivery decisions 1, 2 and 4: two on-demand standard asset packs, four SoC-targeted
variants each, an empty default, raw bins (never pre-zipped — Play deflates in transit and
delta-patches across updates).

**Files**
- `settings.gradle.kts` (edit — `include(":npu_turbo", ":npu_small")`)
- root `build.gradle.kts` (edit — `id("com.android.asset-pack") version "8.13.2" apply false`)
- `gradle.properties` (edit — `android.experimental.enableDeviceTargetingConfigApi=true`)
- `npu_turbo/build.gradle.kts` (new), `npu_small/build.gradle.kts` (new)
- `npu_turbo/src/main/assets/model/.gitkeep` (new), `npu_small/src/main/assets/model/.gitkeep` (new)
- `npu_turbo/.gitignore` (new), `npu_small/.gitignore` (new — `src/main/assets/model#group_*/`)
- `app/device_targeting_config.xml` (new)
- `app/build.gradle.kts` (edit — `assetPacks`, `bundle { }`, the `verifyNpuPacks` task,
  `sourcePinnedInputs` += the XML, both pack build files, `settings.gradle.kts`,
  `gradle.properties`)
- `tools/build_asset_packs.py` (edit — `build` and `delivery-zip` modes)
- `app/src/test/java/com/whispereverywhere/npu/NpuPackLayoutTest.kt` (new)

**Steps**

1. Pack modules, the research §5 shape exactly:
   ```kotlin
   // npu_turbo/build.gradle.kts
   plugins { id("com.android.asset-pack") }
   assetPack {
       packName.set("npu_turbo")
       dynamicDelivery { deliveryType.set("on-demand") }
   }
   ```
   (`npu_small` identical with its name.) **Standard asset packs, not AI packs** — the spec's
   tooling-maturity decision; the layout is identical if we later migrate, and the research's
   policy finding (in-process use only) is satisfied by construction either way.
2. `app/device_targeting_config.xml` — the census, spelled for Play. Spec group names, spec
   strings, **both manufacturer spellings** per group (the gate's own `{QTI, Qualcomm}` doctrine,
   mirrored; a "Qualcomm"-spelled OEM build otherwise passes the app gate and lands in the empty
   default — fail-safe, but exactly the lost-coverage trap the census exists to prevent; two
   selectors per group is well under the 5-selector cap):
   ```xml
   <config:device-targeting-config xmlns:config="http://schemas.android.com/apk/config">
     <config:device-group name="soc_8gen3">
       <config:device-selector>
         <config:system-on-chip manufacturer="QTI" model="SM8650"/>
         <config:system-on-chip manufacturer="QTI" model="SM8650-AC"/>
       </config:device-selector>
       <config:device-selector>
         <config:system-on-chip manufacturer="Qualcomm" model="SM8650"/>
         <config:system-on-chip manufacturer="Qualcomm" model="SM8650-AC"/>
       </config:device-selector>
     </config:device-group>
     <config:device-group name="soc_8elite_galaxy">
       <config:device-selector>
         <config:system-on-chip manufacturer="QTI" model="SM8750-AC"/>
       </config:device-selector>
       <config:device-selector>
         <config:system-on-chip manufacturer="Qualcomm" model="SM8750-AC"/>
       </config:device-selector>
     </config:device-group>
     <config:device-group name="soc_8elite5_galaxy">
       <config:device-selector>
         <config:system-on-chip manufacturer="QTI" model="SM8850-AD"/>
       </config:device-selector>
       <config:device-selector>
         <config:system-on-chip manufacturer="Qualcomm" model="SM8850-AD"/>
       </config:device-selector>
     </config:device-group>
     <config:device-group name="soc_7gen4">
       <config:device-selector>
         <config:system-on-chip manufacturer="QTI" model="SM7750"/>
       </config:device-selector>
       <config:device-selector>
         <config:system-on-chip manufacturer="Qualcomm" model="SM7750"/>
       </config:device-selector>
     </config:device-group>
     <!-- everything else → default group "other" → the EMPTY variant. Groups are pairwise
          disjoint by exact string, so XML order carries no priority weight here. -->
   </config:device-targeting-config>
   ```
3. `app/build.gradle.kts`:
   ```kotlin
   android {
       assetPacks += listOf(":npu_turbo", ":npu_small")
       bundle {
           deviceTargetingConfig = file("device_targeting_config.xml")
           deviceGroup {
               enableSplit = true
               defaultGroup = "other"
           }
       }
   }
   ```
4. **`build_asset_packs.py build`** — from the measured workspace, per family × tier: strip the
   vendor directory prefix, **rename turbo's entries to the catalog's `turbo_*` names** (the 4.1
   collision facts hold for every family — all eight vendor zips use the same two bare names),
   write the pair + our `metadata.json` (the schema block above, values FROM the census table the
   script carries) into `<pack>/src/main/assets/model#group_<packGroup>/`, then **re-verify its
   own output through the same allow-list-and-digest logic the app uses**: exactly three files,
   the two bins at the census bytes and digests, the metadata parsing clean and cross-check-null
   against the census. `delivery-zip <familyId> <tierId>` writes the per-family SAF delivery zip
   (bare names + `metadata.json`), prints its sha256 — the fleet's sideload story, same
   verification. `tools/pack_npu_zip.py` is untouched (its pins stand; it remains the 8gen3
   recipe the 4.1 acceptance used).
5. **`verifyNpuPacks`** Gradle task: for each pack × census group dir — three files present, two
   bins at exact census bytes (sha256 of ~4.3 GB per bundle build is too slow for a gate that
   runs per release; the byte counts + the script's own hash-verified build step carry it, and
   the app's arrival hash is the invariant anyway), metadata.json parseable with the right
   `packGroup`; the DEFAULT `model/` dir contains nothing but `.gitkeep` (**the empty-default CI
   check the research §6 prescribes**). Wired `dependsOn`-style before every
   `package*Bundle` task; `assembleDebug` does NOT depend on it (everyday builds must not demand
   4.3 GB of payload — an APK build carries no packs at all).
6. `NpuPackLayoutTest` (source-contract over the five committed files): the XML's group names ==
   census `packGroup`s exactly and exhaustively; every census `socModels` string appears in its
   group under BOTH manufacturer spellings and no other model string appears anywhere (the
   research-sketch plain-`SM8750`/`SM8850` strings are asserted live-zero — the superseded wider
   census must not creep back in); `defaultGroup = "other"`; both pack modules declare
   `deliveryType.set("on-demand")` and their exact `packName`s; `assetPacks` lists exactly the
   two; the gradle.properties flag present; both pack `.gitignore`s carry the payload pattern;
   `verifyNpuPacks` names the census byte literals.

**Test (red first):** `NpuPackLayoutTest > theDeviceGroupsAreTheCensusSpelledForPlay FAILED` (no
XML exists).

**Battery:** `:app:assembleDebug` green **with the pack payload absent** (the everyday build must
not care); a deliberate `verifyNpuPacks` run against the empty modules → the named failure
listing every missing group dir (the gate proving it gates); then the `build` mode run + a green
`verifyNpuPacks`; full JVM suite. `bundleDebug` itself is exercised in F8's run-book, where its
output is actually consumed.

**Expected delta:** +1 suite, +14 tests.

**Commit**

```
feat(play): two on-demand asset packs, SoC device groups, and an EMPTY default

npu_turbo and npu_small — standard asset packs, on-demand, four #group_ variants
each, raw bins (Play deflates in transit; pre-zipping would break delta patching
and double on-device disk for zero win). The device-group XML is the census
spelled for Play: same groups, same exact strings, both manufacturer spellings,
and nothing the census does not name — the app gate stays the correctness
authority and Play targeting stays a bandwidth optimization.

The default variant is EMPTY and a build gate keeps it that way: an unmatched
device can never be prevented from receiving the default, so the default must
contain nothing worth receiving. The payloads are BUILD artifacts assembled from
the vendor zips by tools/build_asset_packs.py — renamed to the collision-safe
turbo_* names, verified through the importer's own logic, and structurally
uncommittable.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task F5 — The Play fetch flow: the pure machine, the shell, install-from-pack

Fetch → verify (the sha256 invariant) → stage → `notifyModelInstalled()`. The state machine is
pure and executed; the Android shell mirrors `NpuImportController`'s proven shape; failure is
loud, cancel is real, cellular consent is Play's own dialog.

**Files**
- `app/build.gradle.kts` (edit — `implementation("com.google.android.play:asset-delivery-ktx:2.3.0")`;
  `sourcePinnedInputs` += `NpuPackController.kt`)
- `app/src/main/java/com/whispereverywhere/npu/NpuPackFetch.kt` (new)
- `app/src/main/java/com/whispereverywhere/npu/NpuPackController.kt` (new)
- `app/src/main/java/com/whispereverywhere/npu/NpuDiag.kt` (edit — the `pack:` family; folded item)
- `app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt` (edit — `installFromPack`)
- `app/src/test/java/com/whispereverywhere/npu/NpuPackFetchTest.kt` (new)
- `app/src/test/java/com/whispereverywhere/npu/NpuDiagTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/npu/NpuAssetImportTest.kt` (edit — the shared finalize pin)

**Steps**

1. **`NpuPackFetch`** — the pure machine. `PACK_BY_TIER` spelled through the tier-id homes
   (`NpuAssetImport.TIER_ID to "npu_small"`, `NpuModelSpec.TURBO.tierId to "npu_turbo"`);
   `advance(status, errorCode, soFar, total)` maps **all ten** `AssetPackStatus` values to
   exactly one `FetchState` (UNKNOWN → `Failed("Google Play reported status 0 (unknown)")` —
   never silence, the same discipline as the error table; PENDING → `Pending`, DOWNLOADING →
   `Downloading(soFar, total)`, TRANSFERRING → `Transferring`, COMPLETED → `Verifying(0, total)`
   — completion of DELIVERY is the start of OUR verification, never `Installed`; FAILED →
   `Failed(failureReason(errorCode))`, CANCELED → `Cancelled`, WAITING_FOR_WIFI and
   REQUIRES_USER_CONFIRMATION → `NeedsConfirmation`, NOT_INSTALLED → `Idle`) **plus the
   unrecognized-int arm**: `advance` is total over `Int` whatever the library documents, so any
   other value maps to `Failed("Google Play reported status <n>")` rather than falling through a
   `when` nobody wrote an else for. The red test enumerates the library's own class (10 of 10)
   AND asserts an out-of-range int (99) lands in the `Failed` arm. `failureReason` names every
   `AssetPackErrorCode` in user words with the honest next action — the three that matter get
   exact copy:
   - `APP_NOT_OWNED` / `PLAY_STORE_NOT_FOUND` / `API_NOT_AVAILABLE`: `"Google Play can't deliver
     the model to this install — it wasn't installed from Play. Use 'Import model pair…' below
     instead."` (the sideload truth, stated as the path forward, never a dead end);
   - `INSUFFICIENT_STORAGE`: names the pair's size;
   - `NETWORK_ERROR`: `"The download couldn't reach Google Play. Check your connection and
     retry."`
   Unknown codes render `"Google Play reported error <n>"` — never silence. The status ints are
   mirrored constants documented against `AssetPackStatus`; the test asserts equality against the
   library's own class (loadable on the JVM — verified at the red step; if it is not, the
   equality pins become source pins over the shell's imports and the task report says so).
2. **The `pack:` diag family (F-rule — format here, emission pinned at the shell):**
   - `NpuDiag.packLine(tierId, packName, status, soFar, total)` →
     `pack: fetch tier=npu-turbo pack=npu_turbo status=downloading soFar=105906176 total=901775360`
     — one line per STATUS TRANSITION plus at most one per 10% of progress (the throttle is the
     shell's, asserted as a pure decision `NpuPackFetch.shouldLogProgress(lastPct, pct)`);
   - `NpuDiag.packOk(tierId, entries, bytes)` → `pack: ok tier=npu-turbo entries=2
     bytes=1071685632` — the run-book's success landmark, deliberately shaped like the import's
     `okLine`;
   - `NpuDiag.packRefused(tierId, reason)` → `pack: refused tier=npu-turbo reason=…`.
   Each prefix is a contiguous literal. **The `npu: offer` line is untouched** — fetch state is
   pack lifecycle, not offer state.
   **Folded 4.1 item — L1 m5**: `NpuDiag.unavailable`'s stage enumeration KDoc still reads
   "mel-donor, mel-init, mel, assets, init, quant, encode, decode" — stale by six stages
   (`companion`, `mel-asset`, `vocab`, `skel`, `epoch`, `lang`). Corrected while this file is
   open, and the list is re-derived from the source rather than retyped from memory (which is
   also how a wrong count like this one gets caught).
3. **`NpuPackController`** — the shell, `NpuImportController`'s shape exactly: process-scoped
   `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, `StateFlow`, single-flight `start`
   (`synchronized`, previous-job join), `cancel()` delegating to
   `assetPackManager.cancel(listOf(packName))` + state to `Cancelled`, `confirm(activity)`
   delegating to `showConfirmationDialog`. The listener maps every `AssetPackState` through
   `advance` and emits the `pack:` line on transition. On `Verifying`:
   `installFromPack(tierId, family, location.assetsPath(), onProgress)` where family comes from
   the app memo (null → `Failed` with the capability reason — unreachable behind the F6/F7
   gates, refused anyway); `Installed` only after it returns `ImportState.Installed`; then
   `assetPackManager.removePack(packName)` — **remove strictly AFTER the staged pair is verified
   and renamed into place (ORDER pin: the `removePack` call site sits below the
   `installFromPack` success branch), because a remove that runs early deletes the only copy
   mid-verify** — lesson 2's shape: the invariant is carried by the state machine, not by hope.
   A failed verify leaves the pack in place for a costless retry and emits `packRefused`.
4. **`WhisperModelManager.installFromPack`**: read `model/metadata.json` from the pack path
   (parse + `crossCheckRefusal` — the wrong-variant/empty-default cases die here by name: a
   missing metadata file IS the empty-default signature and the refusal says "Google Play
   delivered no model for this device" with the import fallback named); free-space precheck via
   `NpuAssetImport.requiredFreeBytes(pairBytes, pairAlreadyInstalled)` (the pack copy is already
   on disk — staging adds one more pair, so the existing `copies` arithmetic is exactly right);
   stream-copy each bin into `models/<name>.part` hashing DURING the copy against
   `requiredEntriesFor(model, artifact)` (the same verdict machinery, the same
   `wrongSizeRefusal`/`wrongDigestRefusal` vocabulary); then **the existing parking transaction**
   — extract `importNpuAssetPair`'s park/rename/rollback tail into a shared private
   `finalizeVerifiedPair(model, staged)` used by both routes (one transaction, two arrival
   routes; `NpuAssetImportTest` pins that both callers route through the one function —
   live-count == 2); `notifyModelInstalled()` on success. Returns the same `ImportState` type the
   import uses — one vocabulary for every arrival. **Carrier precision (the certification's
   supersession ruling (b)):** a fetched-but-corrupt pack surfaces its reason through the fetch
   card's `Failed` state, NOT through the `unavailableReason` machinery the spec's sentence
   names — a corrupt pair is refused here and never installs, so the load-path machinery
   structurally cannot be the carrier; same affordance on the same card, different carrier, and
   `unavailableReason` keeps its existing job for tiers that installed and then declined at load.
5. `NpuDiagTest`: the three formats exact (including the `Locale`-free integer rendering); the
   emission pins over `NpuPackController.kt` source (each builder called at exactly its site
   count; the transition-not-tick rule via `shouldLogProgress` at the one call site); the
   remove-after-install ORDER pin via `liveOffsets`.

**Test (red first):** `NpuPackFetchTest > everyAssetPackStatusMapsToExactlyOneFetchState FAILED`.

**Battery:** `:app:assembleDebug` (the new dependency must not disturb packaging — re-run the F2
APK inspection, expected unchanged except the play-core classes); full JVM suite.
**Expected delta:** +1 suite, +28 tests.

**Commit**

```
feat(play): the fetch flow — fetch, verify, stage, announce; failure with a name

NpuPackFetch is the pure machine (every AssetPackStatus to exactly one state,
every error code to honest words — a sideload is told the import path, never
shown a dead end); NpuPackController is the NpuImportController-shaped shell.
COMPLETED means delivered, not installed: our verification starts there — the
pack's metadata must equal the device family's census row, both bins must hash
to the census digests DURING the copy, and the pair lands through the same
parking transaction the SAF import uses. One transaction, one refusal
vocabulary, several arrival routes.

removePack runs strictly after the staged pair is renamed into place — a remove
that runs early deletes the only copy mid-verify, and no source ordering of two
async completions carries that; the state machine does.

The pack: line family narrates the lifecycle under the F-rule; the npu: offer
line is untouched.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task F6 — Onboarding: language first, then the detector-shaped model step

The 3.8 owner ruling folds in (language BEFORE model download), and the model step becomes
detector-shaped: capable devices see turbo recommended and fetch it from Play inside the flow.

**Files**
- `app/src/main/java/com/whispereverywhere/ui/onboarding/OnboardingLogic.kt` (edit)
- `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt` (edit)
- `app/src/main/java/com/whispereverywhere/ui/onboarding/OnboardingSetupViewModel.kt` (edit)
- `app/src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt` (edit — `fetchableNpuTierIds`;
  folded item)
- `app/src/main/java/com/whispereverywhere/npu/NpuFleetCensus.kt` (edit — `fetchableTierIds`)
- `app/src/main/java/com/whispereverywhere/model/ModelTierCopy.kt` (edit — the `offeredGatedIds`
  KDoc widens to name the union producers; no body changes)
- `app/src/test/java/com/whispereverywhere/ui/onboarding/OnboardingLogicTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/npu/NpuFleetCensusTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/ui/screens/ChooserSteerWiringPinTest.kt` (edit — pin
  break, resolved in-commit)

**Steps**

1. **The language step** (`OnboardingLogic`): `Step.LANGUAGE` between PERMISSIONS and ENGINES;
   `next`/`previous` re-wired (compile-forced exhaustively — the `when`s are sealed).
   `languageRows(deviceLanguageTag)`: the device's language first **when
   `PreferencesManager.SUPPORTED_LANGUAGES` carries it**, then `"auto"`, then the remaining
   entries in list order (device language absent → auto leads). `languageContinueEnabled(picked)
   = picked != null` — the 3.8 mandate is a forced choice, the same no-preselection discipline as
   the model pick: the device-locale row renders first and badged, and the user still taps.
   `const val LANGUAGE_HINT = "Choosing a language makes multilingual transcription faster."`
   (the 3.8 spec's own sentence — our-own-app relative, no cross-app claim); the auto row's
   subtitle is **the 3.8 ruled text, carried verbatim**:
   `"Slower on multilingual models — detects per session."` — the ruling's honest disclosure of
   the cost the LANGUAGE_HINT beside it asserts; the ruling stands unless the owner re-rules,
   and this plan carries it rather than re-asking.
2. **The step UI** (`OnboardingFlowScreen`): title `"What language will you speak?"`; the rows as
   selectable cards (the `PermissionRow` visual family); Continue writes
   `preferencesManager.setSelectedLanguage(picked)` (the existing setter,
   `PreferencesManager.kt:170` — `selected_language` is the one store, no new storage per the
   3.8 spec) and advances. Back from ENGINES returns here; the flow's mandatory-model contract is
   untouched.
3. **`fetchableTierIds`** (pure, census): `family != null && capable` → every id in
   `gatedTierIds` with an `artifactFor(family.id, id)` row, minus `installedGatedIds`; else
   empty. Executed truth table in `NpuFleetCensusTest`. `WhisperEverywhereApp.fetchableNpuTierIds()`
   is the Android binding: `NpuFleetCensus.fetchableTierIds(npuSocFamily, npuCapableDevice,
   gated-catalog-ids, installed-gated-ids)` — same off-Main contract as `offeredNpuTierIds()`
   (the probe dlopens), documented and pinned the same way. **`offeredNpuTierIds()` itself is
   untouched**: offered still means installed + capable, and everything that ROUTES (the service,
   the selector) keeps reading it — a fetchable tier must never route.
4. **The model step goes detector-shaped** (`EnginesStep`): the produced set becomes
   `offeredNpuTierIds() union fetchableNpuTierIds()` — the local keeps the name `npuTierIds`, the
   producer body is the only change. The steer and ordering functions are **unchanged in body**:
   `steerIdForLanguageTagFor` already answers `npu-turbo` whenever the set names it (L9), so on a
   capable fresh Play install turbo heads the lineup and carries the steer badge — the spec's
   "turbo recommended", ridden entirely on L9's ordering with zero new rules. The union is a
   DISPLAY/steer set; the pick still writes `selectedModelId` and the download phase resolves the
   route per tier.
5. **`ensureSpeech` routes gated tiers through the pack flow**: when the selected model is gated
   and not installed — `NpuPackController.start(model.id)`, then collect its state mapped to
   `EngineState`: `Pending`/`Transferring` → `Working(-1, "Preparing")`, `Downloading` →
   `Working(pct, "Downloading from Google Play")`, `Verifying` → `Working(pct, "Verifying")`,
   `NeedsConfirmation` → `Working(-1, "Waiting for your OK in the Google Play dialog")`,
   `Installed` → `Ready`, `Failed(reason)` → `Failed(reason)` (Retry re-enters `ensureSpeech`,
   which re-`start`s — single-flight makes the double-tap safe), `Cancelled` → `Failed("Download
   cancelled — tap Retry to start again.")`. The flow screen, on observing `NeedsConfirmation`,
   calls `NpuPackController.confirm(activity)` once per entry into that state (Play's own >200 MB
   cellular dialog — the consent surface the spec names). Non-gated tiers: byte-for-byte today's
   download path.
6. **Existing installs see no forced re-onboarding**: the flow only runs where onboarding was
   never completed (the existing completion pref, untouched); the language step therefore reaches
   only fresh installs — existing users keep the Settings language picker (already shipped). One
   sentence in the flow KDoc; no code.
7. **Folded 4.1 item — L8 review M3**: the offer-line generation latch's `getAndSet` can
   regress under two concurrent evaluations holding different generations (one spurious extra
   line); make the update monotonic (`updateAndGet { max(it, generation) }`-shaped) while this
   file is open, and say why in one line. *(The L5 review's M2 — `forId`'s singular KDoc — was
   already fixed by the 4.1 L8 carry set and verified plural on current main; the fold table
   records it, and this task carries nothing for it.)*
8. `ChooserSteerWiringPinTest`: the flow screen's producer needle re-spelled for the union (a
   conscious pin break, resolved in-commit); the `WhisperCatalog.pickable` live-zero assertions
   are **kept** (the Bengali-review encoding must not relax); the Build-read count is unchanged
   and still asserted.

**Test (red first):** `OnboardingLogicTest > theLanguageStepSitsBetweenPermissionsAndEngines
FAILED`.

**Battery:** full JVM suite.
**Expected delta:** +0 suites, +18 tests.

**Commit**

```
feat(onboarding): language first, then the detector-shaped model step

The 3.8 owner ruling lands where it was always headed: Welcome -> language ->
model -> done. The language list is device-locale-first with auto one tap away
and honestly subtitled; the pick writes the existing selected_language pref and
nothing new.

The model step is shaped by the detector: on a capable Play install the lineup
is offered UNION fetchable, and L9's ordering does the rest — turbo heads the
steer because the set names it, not because a new rule promotes it. Choosing an
NPU tier drives the Play fetch inside the flow with Play's own cellular-consent
dialog; failure shows the reason and Retry, and the mandatory-model gate is
unchanged. offeredNpuTierIds is untouched: fetchable is a chooser fact, and
nothing that routes a session ever reads it.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task F7 — The chooser's fetch affordance: Play installs stop being import-only

Spec: "the chooser gains the fetch affordance where the gate passes (replacing the import-only
story for Play installs)". SAF import stays, permanently.

**Files**
- `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt` (edit)
- `app/src/test/java/com/whispereverywhere/ui/screens/NpuImportWiringPinTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/ui/screens/ChooserSteerWiringPinTest.kt` (edit)

**Steps**

1. The screen's lineup set becomes the same union F6 gave the flow (`offeredNpuTierIds() union
   fetchableNpuTierIds()` — the producer keeps the name `npuTierIds`); `installedIds` and the
   selection/steer logic are unchanged (a fetchable card cannot be selected — it has nothing on
   disk to select).
2. **The card affordance for a fetchable tier** (in `ModelTierCard`'s action slot, where an
   uninstalled downloadable tier shows Download): the button reads `"Get on Google Play"`, and
   the card's byte badge already states the pair size (H3 rules hold: position words unchanged,
   size stated, no cross-app claim, no absolute — the copy in `ModelTierCopy` is not edited at
   all). Tapping calls `NpuPackController.start(model.id)`; while `activeTierId == model.id` the
   card renders the fetch state: progress bar + `"Downloading from Google Play… <pct>%
   (<soFar> / <total>)"`, a real `"Cancel download"` (→ `NpuPackController.cancel()`),
   `NeedsConfirmation` → `NpuPackController.confirm(activity)` invoked once (same rule as F6),
   `Failed` → the reason in error color + `"Retry"` + one plain sentence: `"You can also import
   the model pair from a zip below."` — the honest bridge to the panel that has always existed.
   `Installed` needs no rendering: `notifyModelInstalled()` bumps the generation, the producers
   re-run, and the card becomes the installed card (the existing machinery — nothing bespoke).
3. **The import panel is untouched in behavior** (capability-gated exactly as today — it is the
   sideload path and the Play-failure fallback); its idle copy gains one leading sentence when a
   fetchable tier exists: `"On Google Play installs the model downloads right from the card
   above — importing is the manual route."` so the two affordances read as one story, primary
   and fallback, rather than two competing buttons.
4. Pins: `NpuImportWiringPinTest` gains the fetch wiring needles — the Get button's
   `NpuPackController.start(` at exactly one site with `model.id` (never a literal); cancel and
   confirm each at one site; the failure branch renders `state.reason` (the same
   render-what-the-machine-said rule the import panel is pinned to). `ChooserSteerWiringPinTest`:
   the union producer needle re-spelled for this screen (conscious break, resolved in-commit);
   the `pickable` live-zeros kept.

**Test (red first):** `NpuImportWiringPinTest > theFetchAffordanceRoutesThroughNpuPackController
FAILED`.

**Battery:** full JVM suite.
**Expected delta:** +0 suites, +8 tests.

**Commit**

```
feat(chooser): the fetch affordance — Play installs stop being import-only

Where the gate passes and the tier's files have not arrived, the card now says
"Get on Google Play" and drives the pack fetch in place: progress, a real
cancel, Play's own cellular consent, and on failure the reason plus the import
route named in one sentence. The SAF import panel stays exactly where and what
it was — the sideload path and the fallback — with one line telling Play users
which button is the primary.

Selection is untouched: a fetchable card has nothing on disk to select, and the
installed-card transition rides the install signal like every other arrival.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task F8 — Version identity, the run-book, and the internal-track session  **[DEVICE]**

**Files**
- `app/build.gradle.kts` (edit — `versionCode = 81`, `versionName = "4.2.0"`)
- `app/src/test/java/com/whispereverywhere/ReleaseIdentityTest.kt` (edit — the two constants)
- `docs/superpowers/sdd/2026-08-29-fleet-onboarding/acceptance.md` (new — the run-book)

**Steps**

1. **Version identity**: `4.2.0` / `81`, `ReleaseIdentityTest` updated in the same commit — a
   deliberate identity change. State the known side effect the 4.1 sheet recorded: `GpuPolicy`
   keys its canary latches on `versionCode`, so 80 → 81 clears recorded GPU verdicts (inert with
   the experimental toggle OFF, the shipped default).
2. **The run-book** (`acceptance.md`), owner-facing, in this order:
   - **§0 — Build the payload and the bundle.** The measure/build/verify command sequence
     (`build_asset_packs.py measure` then `build`; `verifyNpuPacks`; then
     `:app:bundleRelease`). **This is the first RELEASE build of the whole NPU stack** — R8
     meets the JNI surface here (4.1 residual risk 7, now due). The gate: install the
     release-signed universal APK set locally via bundletool (next rung) and reach turbo
     dictation BEFORE anything is uploaded; a release-only failure at this rung is a
     proguard-rules task, not a track surprise.
   - **§1 — bundletool local-testing (plumbing, wrong-variant, empty-default).** Runnable as
     written (absolute interpreters — the plan's own rule; bundletool's `install-apks` shells
     out to adb, which is not on PATH here):
     ```powershell
     & "C:\Program Files\Android\Android Studio1\jbr\bin\java.exe" -jar `
       C:\Users\bastr\.androidbuild\fleet-packs\bundletool-all-1.18.3.jar build-apks `
       --bundle=C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\bundle\release\app-release.aab `
       --output=C:\Users\bastr\.androidbuild\fleet-packs\fleet.apks --local-testing
     & "C:\Program Files\Android\Android Studio1\jbr\bin\java.exe" -jar `
       C:\Users\bastr\.androidbuild\fleet-packs\bundletool-all-1.18.3.jar install-apks `
       --apks=C:\Users\bastr\.androidbuild\fleet-packs\fleet.apks `
       --device-groups=soc_8gen3 `
       --adb="C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"
     ```
     **The device-group is ASSERTED, not evaluated** (bundletool does
     not read the SoC) — which is exactly what makes the negative rungs possible on one phone:
     `--device-groups=soc_7gen4` → the fetch lands the v73 variant → `pack: refused …` naming
     the family mismatch, the tier card carries the reason, nothing installs; no
     `--device-groups` flag → the empty default → the metadata-absent refusal with the import
     fallback named. Local-testing limits quoted from the research (fetch from external storage,
     no network-error or consent testing, **uninstall between installs**).
     **THE UNINSTALL WARNING, in a box, before the first command**: every `install-apks` over an
     existing install requires an uninstall, and an uninstall destroys `filesDir` — every model
     on the device. **This window contains up to FOUR sanctioned uninstalls (the global
     constraints' bounded exception), each named here with its restore path:**
     - *u1, before the first local install* (only if a dev build is present) — restores nothing;
       rung 1 lands its own models;
     - *u2, before rung 2 (wrong variant)* — needs no models: the rung's whole point is the
       named refusal, which requires nothing on disk;
     - *u3, before rung 3 (empty default)* — same, a refusal rung;
     - *u4, before the §2 track install* — the recovery IS the feature under test: onboarding →
       language → turbo fetched from Play → ggml tier re-downloaded in-app.
     Nothing is ever hand-restored; a hand-restore would un-test the flow. Outside this boxed
     window the never-uninstall rule is absolute.
   - **§2 — THE INTERNAL-TRACK SESSION (the acceptance).** Upload the AAB to the internal test
     track; the owner installs from the track on the Fold6 (versionCode 81 installs over any
     local 81-signed build; if the local-testing build is present, uninstall via §1's boxed rule
     first — Play cannot downgrade-install over it otherwise). Then, WITHOUT adb: onboarding →
     the language step (record the first row shown) → the model step shows **turbo recommended**
     with the steer badge → tap Get/Download → (cellular consent if on data) → fetch → verify →
     staged → dictate on turbo. Record: the transcript, the `pack: ok tier=npu-turbo entries=2
     bytes=1071685632` landmark, the `npu: offer` line, one `npu: encode=` line.
     **The one adb read in the session, and it is evidence-gathering, not acceptance:**
     `adb shell getprop ro.soc.model` on the Fold6 — records whether the device says `SM8650` or
     `SM8650-AC`, closing the research §5 question; the answer is written into the census's
     8gen3 `evidence` string (both strings stay listed regardless — the group carries both by
     design). Play's server-side group resolution delivering the 8gen3 variant to the Fold6 is
     itself the -AC suffix check executing in production.
   - **§3 — The greps** (`-SimpleMatch`, against a saved capture): `pack: fetch `, `pack: ok `,
     `pack: refused `, `npu: offer `, `npu: encode=`, `segment-timing: `, `mel: bins=`.
   - **§4 — Sign-off table**: §0 release rung green | §1 three local rungs (right variant /
     wrong variant refused by name / empty default refused by name) | §2 track install → turbo
     dictation, no adb | getprop recorded | owner verdict.
   (The L8 review's M2 lesson is applied to this sheet: no section header claims more than its
   items deliver.)
3. **The final measured total**: purge, clean tree, forced-fresh full suite, report
   suites/tests/failures from XML aggregation — measured, never estimated.

**Test (red first):** `ReleaseIdentityTest` fails on the old constants after the gradle edit —
the census alarm shape; updated in the same commit.

**Battery:** `:app:assembleDebug`; the F2 APK inspection re-run on the final APK; the
forced-fresh full JVM suite against a purged results directory with a clean tree; then the owner
device session per the run-book, recorded in `acceptance.md` and never claimed done by the
implementer.

**Expected delta:** +0 suites, +0 tests (two constants change; no counts).

**Commit**

```
feat(release): 4.2.0 — the fleet run-book and the internal-track acceptance

versionCode 81 / versionName 4.2.0, and the acceptance sheet for the first full
rehearsal of the store pipeline: build the pack payloads, gate the bundle, prove
the plumbing with bundletool's asserted device groups — including the
wrong-variant and empty-default refusals firing BY NAME on one phone — then the
internal test track, where the acceptance is the spec's sentence: install on the
Fold6 from the track and reach turbo dictation without touching adb.

The one uninstall in the whole program is boxed, scheduled last-before-track,
and its recovery is deliberately the feature under test. The one adb read is
evidence, not acceptance: getprop ro.soc.model settles the -AC question the
census has carried since 4.0.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## JVM-vs-device test split

| Task | JVM (executed) | Device / structural |
|---|---|---|
| F1 | the census + gate truth tables — fully JVM | — |
| F2 | census↔gradle equality, packaging source pins | the skel staging per family → F8 §1/§2 |
| F3 | metadata parse/cross-check, family-aware verdicts, the 16-digit census — fully JVM | a non-8gen3 import (no such device exists here) |
| F4 | the XML↔census layout pins | bundletool local rungs → F8 §1 |
| F5 | the status/error truth tables, the pack: formats, ORDER pins | the real fetch → F8 §2 |
| F6 | step order, language rows, fetchable truth table, EngineState mapping rules | the flow on device → F8 §2 |
| F7 | wiring pins | the card affordance live → F8 §2 |
| F8 | ReleaseIdentity | the whole acceptance |

Compose surfaces and the Play client get structural pins, never pretend unit tests. Everything
with real logic — the census, the gate, the verdicts, the state machine, the step rules — is a
genuine JVM test with real assertions.

---

## 4.1 items folded here, and what is left out of plan

The 4.1 branch closed its reviews at 0 open with a handful of minors ledgered "to final triage".
Deferral without a trigger is a silent discard, so here is the disposition of every one this plan
touches or declines.

| Source | Item | Disposition |
|---|---|---|
| L1 review m2 | `releaseNpuResources` guard comment overstates the whisper-side fact | **F2** (file open) |
| L1 review m5 | `NpuDiag.unavailable` stage enumeration stale | **F5** (file open) |
| L1 review m6 | `NpuBackendWiringTest.memberBody` fixed-cut helper (the Q6 M3 shape) | **F2** (suite open) |
| L1 review m8 | the load-scoped gate hold is load-bearing for the epoch, nowhere stated | **F2** (comment at the site) |
| L5 review M1 | `WhisperCatalogHelpersTest` "all seven tiers" comment | **VERIFIED ALREADY FIXED** — `WhisperCatalogHelpersTest.kt:324` reads "all eight tiers" on current main (landed with the 4.1 L8 carry set); no task carries it |
| L5 review M2 | `ModelTierCopy.forId` KDoc singular "npu tier" | **VERIFIED ALREADY FIXED** — the KDoc reads "the gated npu-class tiers (`npu` and `npu-turbo`)" on current main (same L8 carry set); no task carries it |
| L6 review minor | two unchecked `marker.delete()` in `NpuAssetStage` | **F2** (staging surface open) |
| L8 review M3 | the offer-line generation latch's bounded `getAndSet` regression | **F6** (file open) |

**Out of plan, each with its named trigger:**

- **L1 m1** (the one raw `.contains` in `NpuNativeContractTest`) — no task opens that suite;
  trigger: the next task that edits `NpuNativeContractTest`.
- **L1 m3/m4/m7, L5 M3, L8 M2** — 4.1 report-precision items about 4.1 artifacts; record-only,
  nothing to change in 4.2 (L8 M2's *lesson* is applied to the new sheet in F8).
- **L5 review M4** — `liveLineCount`'s trailing-comment corner; inherited instrument precision,
  restated in the global constraints so needle authors avoid it; trigger: the first false-green
  it ever produces.
- **L4 review m3** — `oss_licenses.html` "at build time" wording; trigger: the next task that
  edits the licence page.
- **Q8 M9 (4.0)** — `select()` from Settings lands on Home; still a navigation decision in files
  F7 deliberately does not re-flow; trigger: the next navigation rework.
- **m1 (4.0)** — `GgmlBackends`' shared failure latch; untouched surface; trigger: a fourth
  consumer or a non-permanent failure mode.
- **I5's second consequence (4.0/4.1)** — ~8.6 MiB of QNN runtime (and now ~57 MB of fleet
  skels/stubs) ships to every non-Qualcomm device. 4.2 makes the number BIGGER and still does not
  take it: conditional delivery of base-module `lib/`/assets needs a dynamic-feature or
  flavor split, which is a store-listing and release-process decision, not a task in a branch
  whose acceptance is the internal track. Trigger: the Play-listing/size-optimization track, by
  name — and F2's report states the exact added bytes so that track starts with a measurement.
- **The cross-load experiment and any census widening past published packages** — spec non-goals,
  restated in F1's KDoc with their triggers (a device materialising; a new published package).
- **Play listing / launch work, the 3.8 cloud/Gemini items** — spec non-goals; their own tracks.

---

## Residual risk this plan does not close

1. **Three of the four families ship on build-time evidence only.** No SM8750-AC, SM8850-AD or
   SM7750 device exists in this program; their packs are measured, cross-checked and refused-on-
   mismatch, but never executed. The first execution is a customer's. The containment is the
   ladder this stack already trusts: census guard, alias guard, sha256, metadata cross-check,
   per-tier decline with the CPU tiers unharmed — every *detectable* wrong outcome is a named
   decline. **One path survives the whole ladder, and it is the irreducible one**: a
   vendor-published binary that is metadata-consistent (right htp_version, right IO census),
   deserializes on that family's HTP, and is semantically wrong — bad weights, a subtle bin
   miscompile — passes every guard and produces fluent wrong text. Only device execution detects
   it (it is exactly what the Fold6 session detected for 8gen3); the first detector on an
   uncovered family is a human reading a transcript, the per-tier decline ladder is the recovery
   once reported, and the census evidence dates are the accountability for what was and was not
   ever run.
2. **The store pipeline itself executes for the first time at F8.** AGP's device-targeting config
   embed is beta and repo-unexecuted; the first `bundleRelease` is also R8's first meeting with
   the JNI surface. Both are gated inside the run-book before the track upload, but they are
   this branch's first contact, not its hundredth.
3. **Play-side group resolution is observable only through the track.** bundletool asserts
   groups; internal app sharing / the internal track are the only real evaluations, and the
   Fold6 exercises exactly one family's row.
4. **The empty-default refusal path is exercised locally, not in production.** A real unmatched
   device fetching (which the gate prevents) is simulated by the no-flag local rung only.
5. **The 200 MB cellular-consent dialog cannot be tested locally** (research §5's local-testing
   limit); its first exercise is the track session on cellular, and the sheet records it.
6. **Vendor bucket drift.** The zips are pinned by size + Last-Modified + our own digests, and
   the bucket has re-uploaded in place before; a future re-upload fails the measure gates loudly,
   which is the designed outcome — but it would stall a pack rebuild until re-measured.
