# 4.1 Implementation Plan — The NPU Model Lab (build-to-A/B)

Executes `docs/superpowers/specs/2026-08-29-npu-model-lab-design.md` against the asset facts in
`docs/superpowers/research/2026-08-29-npu-model-lab-assets.md`. Branch `feat/4.1-npu-model-lab`,
off the merge-ready 4.0 at `2a718ca` (the five merge conditions F1-F5 are landed there; this plan
assumes them). **Eight tasks, L1 → L8, ID order IS execution order.**

The acceptance bar is the spec's: *the owner dictates on `npu`, `npu-turbo` and `multi`, compares
transcripts and feel, and picks the default lineup.* This plan stops there. No Play packaging, no
fleet gating beyond SM8650, no streaming partials.

> **L1 is a gate, not a preference.** The 4.0 final review's F4/I1 named the defect that a second
> npu-class tier makes reachable, and `NpuBackendWiringTest > exactlyOneTierIdRoutesToTheNpuBackend`
> is the tripwire it left behind. That pin goes **red by design** the moment `npu-turbo` routes
> (L8). No task may reach L8 without L1 landed: the arming epoch is what makes two npu-class tiers
> safe, and its absence is a session that comes up `armed` with nothing behind it.

---

## Global Constraints

Every task's requirements implicitly include this section.

### Build / test commands (PowerShell, repo root — set JAVA_HOME on every invocation)

- Full JVM suite: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:testDebugUnitTest --no-daemon`
- One class: append `--tests "com.whispereverywhere.<pkg>.<Class>"` (repeat `--tests` for several)
- Compile + native: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'; .\gradlew.bat :app:assembleDebug --no-daemon`

Absolute interpreters only — `adb` is not on PATH
(`& "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"`), and neither is `python`
(`& "C:\Users\bastr\AppData\Local\Programs\Python\Python313\python.exe"`). Never use a bare `2>&1`
on a native exe from PowerShell (5.1 wraps stderr into ErrorRecords and flips `$?` even on exit 0).
`unzip` does not exist here either — inspect an APK or an AAR with
`[System.IO.Compression.ZipFile]::OpenRead($path).Entries`.

### Output paths (outside the repo — root `build.gradle.kts:17-22` relocates `buildDirectory`)

- Debug APK: `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk`
- JVM test results (XML): `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\test-results\testDebugUnitTest`
- Installed ggml tiers (the mel donors, and L3's melbank source): `C:\Users\bastr\.androidbuild\WhisperEverywhere\*.bin`
- The turbo package: `C:\Users\bastr\.androidbuild\npu-model-lab\`

### NEVER install via Gradle

**NEVER run `:app:installDebug` or `:app:connectedDebugAndroidTest`.** Both uninstall first and
destroy the owner's on-device models — which in 4.1 means the 4.0 pair (358 MB) *plus* the turbo
pair (1.07 GB) plus whatever staged into `filesDir`. Every device install is owner-run and
data-preserving:
`& "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r <apk>`

### Test evidence is XML aggregation (binding)

Evidence is the JUnit XML — **never a Gradle summary line, never a green console line.**
Purge the results directory before a measured run, and abort on a dirty tree
(`git status --porcelain` must be empty before you measure, so the delta is attributable).

**Confirm the task actually EXECUTED.** The build file documents this hazard verbatim at
`app/build.gradle.kts:170-176` — a verified case of *"Task :app:testDebugUnitTest UP-TO-DATE /
BUILD SUCCESSFUL"* **without running a single test**. Every measured run must show
`> Task :app:testDebugUnitTest` **without** `UP-TO-DATE`, and the XML file timestamps must postdate
the run. If in doubt, `--rerun-tasks`.

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

**Baseline at the branch point: 138 suites / 1,532 tests / 0 failures / 0 errors / 0 skipped**
(re-measured for this plan at `d53955d`: `suites=138 tests=1532 failures=0 errors=0 skipped=0`).
Per-task deltas below sum to **+8 suites / +150 tests → 146 suites, 1,682 tests**; L8 computes the
branch total once, from a forced-fresh run, and reports **measured**, never estimated.

### Never hand-reconstruct a tracked file

If a file needs reverting, restore it from the committed blob (`git checkout -- <path>` or
`git show <rev>:<path>`). Never retype it from memory. L4's vocabulary asset and L5's in-place test
edits are exactly where this bites.

### JVM test rules (TDD throughout)

- Write the failing test, run it, watch the *named* failure, then implement. Every task states its
  expected red.
- Tests live under `app/src/test/java/com/whispereverywhere/...`.
- **JUnit 4 only. No Robolectric, no mocking framework** — the whole test dependency set is
  `junit:junit:4.13.2` plus `kotlinx-coroutines-test`, and `testOptions`
  (`app/build.gradle.kts:188-191`) is the block that decides how the Android stubs behave. Anything
  touching `Context` is unreachable from a JVM test. Do not add a mocking dependency to make a task
  easier.
- `unitTests.isReturnDefaultValues = true` (`app/build.gradle.kts:191`), so **`android.util.Log` is
  a no-op in tests** — no JVM
  test can observe an emitted line. Emission is pinned *structurally*, with the house idiom:
  `private fun source(relative: String): String` + `private fun liveLineCount(scope, needle)`.
  **Template: `SegmentTimingTest.kt:87-104`** (the locator, CRLF-normalised) and **`:106-128`**
  (the emission pin). Copy that shape; do not invent a new one.
- **No JVM test may name `NpuWhisperBackend`, `QnnAsrNative`, or anything that reaches them.**
  `QnnAsrNative`'s `<clinit>` runs `System.loadLibrary("qnnasr")`; the mere reference kills the
  test. Their invariants are pinned as source text, and every task that adds one says so.
- New `.cpp`/`.h` files, new **assets**, and any file pinned by SOURCE TEXT must be added to the
  `inputs.files(...)` `nativeSourceContract` list at `app/build.gradle.kts:259-292` (**18 entries
  today**), or a comment-only or asset-only edit leaves `:app:testDebugUnitTest` UP-TO-DATE and the
  pin passes against stale evidence.

### The presence/ORDER/happens-before rule (the 4.0 lessons 3 and 2, binding)

The 4.0 branch institutionalised **presence vs ORDER**; its final review named the next one:
**source order vs happens-before**. Both apply to every pin written in this plan.

1. A pin that asserts a needle *exists* proves nothing about where it sits. Where the invariant is
   an order, pin the order (`liveOffsets`, site A before site B).
2. A source-order pin over two statements proves **nothing** about the order of their *effects*
   when either statement queues work onto another executor. `LocalWhisperEngine.shutdown()` queues;
   `NativeComputeGate` bounds concurrency, not order. **Where the answer is "no, that does not order
   the effects", the invariant must be carried by identity or state — which is exactly what L1's
   epoch is.**
3. Absence assertions go through `liveOffsets` like every sibling, so documenting a superseded
   design in a comment cannot fail the suite for a reason unrelated to the code.

### Native verification rule

1. `:app:assembleDebug` green (builds `libwhisper_jni.so` **and** `libqnnasr.so`).
2. The full JVM suite still green.
3. A named owner on-device check, recorded in L8 — never claimed done by the implementer.

### External dependencies required

Network for the QAIRT headers (`fetchQnnHeaders`, unchanged from 4.0) and for Maven Central
(`qnn-runtime:2.49.0`, already resolved into the Gradle cache at
`C:\Users\bastr\.gradle\caches\modules-2\files-2.1\com.qualcomm.qti\qnn-runtime\2.49.0\5fb50c874f213bb13261a124088aa4e757a7ac85\qnn-runtime-2.49.0.aar`).
**No network is required for any asset in this plan**: the turbo package is already downloaded and
CRC-verified locally, and the 128-bin filterbank comes out of the `ultra` tier's ggml, which is
already on this machine and whose sha256 matches the catalog byte for byte (see the asset block).

### Commit trailer (exact, every commit)

```
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

### The 4.0 lessons that still bind

1. **`<uses-native-library ... required="false"/>`** for `libcdsprpc.so` + `libadsprpc.so`.
2. **`Qnn_Tensor_t` is not self-contained.** Deep-copy everything you keep; hold the system context
   until teardown.
3. **Never `#ifdef` an enumerator.**
4. **Verify every precondition the design rests on**, not just the ones with a version field.
5. **Bind by NAME, never by index.**
6. **A vote that fails silently looks exactly like slow silicon.** Report the vote result always.
7. **Own the buffers, 64-byte aligned, RAII, copy deleted.**
8. **(4.1)** *A deferred item whose severity rests on "unreachable because X" must name X, and X
   must appear in the dispatch of every task that could change it.* This plan's folded-items table
   at the end is that record.

---

## Shared contracts (load-bearing exact names — identical at every mention)

| Contract | Exact signature | Owner | Consumers |
|---|---|---|---|
| Arming epoch (native) | `QnnAsrNative.nativeEpoch(): Long` — the live session's epoch, `0L` when no session is initialised. `QnnAsrNative.nativeRelease(epoch: Long)` — tears the session down **only** when `epoch == g.epoch && epoch != 0L`; otherwise a logged no-op. `nativeInit` increments `g.epoch` on success and never wraps to 0. | L1 | L2, L7, L8 |
| Arming epoch (Kotlin) | `NpuWhisperBackend.armedEpoch: Long` — private, `0L` until `load` succeeds, the value `nativeEpoch()` returned then, never cleared except by a successful `releaseNpuResources` | L1 | L8 |
| Model spec | `data class NpuModelSpec` (`com.whispereverywhere.npu`): `tierId: String`, `melBins: Int`, `melFrames: Int`, `decLayers: Int`, `heads: Int`, `headDim: Int`, `audioCtx: Int`, `maxPositions: Int`, `tokens: WhisperTokenFamily`, `melAsset: String?`, `vocabAsset: String`; derived `encIn/encOut/encInBytes/encOutBytes/decIn/decOut/decInBytes/decOutBytes: Int|Long`, `melFloatBytes: Int`, `inputFeaturesBytes: Int`. `NpuModelSpec.SMALL`, `NpuModelSpec.TURBO` (L4), `fun forTier(tierId: String?): NpuModelSpec?` | L2 | L3, L4, L5, L6, L7, L8 |
| Token family | `data class WhisperTokenFamily(val langCount: Int)` (`com.whispereverywhere.npu`): `eot`, `sot`, `langFirst`, `langLast`, `translate`, `transcribe`, `startOfLm`, `startOfPrev`, `noSpeech`, `noTimestamps`, `timestampBegin`, `vocab` — **all derived from `langCount`**; `suppress: IntArray`, `beginSuppress: IntArray`, `languageCodes: List<String>`, `langToken(code: String): Int`, `codeForToken(id: Int): String?`. `WhisperTokens.SMALL = WhisperTokenFamily(99)`, `WhisperTokens.LARGE_V3 = WhisperTokenFamily(100)` (L4). **`object WhisperTokens` keeps every 4.0 literal unchanged**; a test asserts `SMALL` reproduces each one. | L2 | L4, L5, L8 |
| Native init | `QnnAsrNative.nativeInit(encoderPath: String, decoderPath: String, libDir: String, melBins: Int, decLayers: Int, heads: Int, vocab: Int, maxPositions: Int): String` — `""` on success or `stage: detail`. The five ints are the spec's shape scalars; native **derives** its census from them by the formula in the asset block and compares against the graphs' own enumeration. | L2 | L3, L8 |
| Mel export (JNI) | `WhisperNative.pcmToMel(ctx: Long, samples: FloatArray, out: java.nio.ByteBuffer, melBins: Int): Boolean` — refuses unless `whisper_model_n_mels(ctx) == melBins` **and** `out.capacity() == melBins * 3000 * 4`, both named in the refusal | L3 | L7 |
| Asset staging | `object NpuAssetStage` (`com.whispereverywhere.npu`): `fun stagedPath(context: Context, assetName: String, expectedBytes: Long, expectedSha256: String): String?` — copies an APK asset into `filesDir` once, streaming sha256 during the copy, `.part`-then-rename, idempotent (returns the existing path when size **and** digest already match); `null` with a `WE-DIAG` line on any failure | L3 | L6 |
| Quantizer | `object NpuQuantize` — `MEL_BINS`/`MEL_FRAMES` constants **removed**; every member takes the bin count: `newMelFloatBuffer(spec)`, `newInputFeaturesBuffer(spec)`, `melToU16(mel, scale, zeroPoint, out, spec)`, `melRowSum(mel, row, spec)`, `quantisedRowSum(...)`, `quantisedColumnSum(...)` | L2 | L3, L7 |
| Detokenizer | `class WhisperBpeDecoder(vocabulary: List<String>, expectedSize: Int)`; `fun fromJson(json: String, expectedSize: Int): WhisperBpeDecoder`; `ASSET_NAME` replaced by `NpuModelSpec.vocabAsset` | L4 | L7 |
| Per-utterance language | `WhisperBackend.detectsPerUtterance: Boolean get() = false` — a **NEW member with a default BODY**, never a widened parameter list. `NpuWhisperBackend` overrides it as a **live** property (`fallbackBackend == null`), so a fallen-back session re-acquires the CPU latch | L7 | — |
| Per-tier decline | `object NpuTierStatus`: `reasons: StateFlow<Map<String, String>>`, `publish(tierId: String, reason: String?)`, `reasonFor(tierId: String?): String?`, `declinedTiers: Set<String>` | L8 | L5 (cards), L8 (routing) |
| Selector | `object NpuBackendSelector`: `fun routesToNpu(tierId: String?, offeredNpuTierIds: Set<String>, declinedTiers: Set<String>): Boolean` · `fun backendFor(tierId, offeredNpuTierIds, declinedTiers, paths, appContext): WhisperBackend` — **the value keeps its name for the whole journey** (`WhisperEverywhereApp.offeredNpuTierIds()` → the screens' and service's `npuTierIds` → this parameter); only `WhisperCatalog`'s spelling differs, deliberately, because the catalog knows about `gated` and not about the NPU | L8 | — |
| Offer gate | `WhisperEverywhereApp.offeredNpuTierIds(): Set<String>` — replaces `isNpuTierOffered()`. **Installed half FIRST**: if no gated tier's files are on disk it returns `emptySet()` without touching `npuCapableDevice`, so the ~7.9 MiB `dlopen` no longer runs at bubble-service start on every SM8650 (Q7b NEW-1 / m3) | L5 | L5, L8 |
| Catalog gate | `WhisperCatalog.pickableFor(offeredGatedIds: Set<String>): List<WhisperModel>` and `ModelTierCopy.steerIdForLanguageTagFor(languageTag: String, offeredGatedIds: Set<String>)` / `orderedForLanguageTagFor(languageTag: String, offeredGatedIds: Set<String>)` — the `Boolean` becomes a set because two gated tiers can be independently installed | L5 | L8 |
| Import | `WhisperModelManager.importNpuAssetPair(tierId: String, source: Uri, onProgress: (Long, Long) -> Unit): NpuAssetImport.ImportState`; `NpuAssetImport.PAIRED_TIER_IDS: List<String>`, `requiredEntriesFor(model)` gains a sha256 per entry: `EntryVerdict.Accept(fileName, expectedBytes, expectedSha256)` | L6 | L8 |
| Diag line | `NpuDiag.line(...)` unchanged in shape; `npu: encode=` stays a contiguous literal. `NpuDiag.mel(...)` gains the bin count: `mel: bins=<n> frames=3000 row0=… rowMid=… rowLast=…` | L2 | L8 |

**Line prefixes are contiguous single string literals in source**, because L8 greps the source with
`-SimpleMatch`. Never build one by concatenation.

---

## Measured ground and BAKED asset facts

Everything below was **measured on this machine for this plan** on 2026-08-29, out of the real
artefacts. **It is not first-contact for the implementer** — bind against these facts, and let the
load-time guards confirm them.

### The `npu-turbo` asset pair

Source zip (already downloaded, `testzip()` CRC-clean, 859,786,903 B, sha256
`1e0e05c347ab96915f17dfcd1173fb1b78bed85bfcefd873e4ea31597913e297`):

```
C:\Users\bastr\.androidbuild\npu-model-lab\whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8gen3.zip
```

| Extracted file | Bytes | sha256 |
|---|---|---|
| `encoder_qairt_context.bin` | 775,831,552 | `f7d11c08a20ea671f59b3ace2f9421da00b06170ac9fe946f29092ee59be6bbe` |
| `decoder_qairt_context.bin` | 295,854,080 | `c19b067766180843fca6266531605bf037820c5e5ae178bd6dc03785df4c6ae4` |

Sum **1,071,685,632 B (1,071.7 MB / 1022 MiB)**. Both digests were computed here by streaming the
entries out of the local zip; **no placeholder ships** (4.0's I6 rule).

> ### TWO MEASURED FACTS THAT CHANGE THE DELIVERY, and neither is in the research doc
>
> **1. The AI Hub zip's entries are NOT top-level.** Every entry is prefixed
> `whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8gen3/`.
> `NpuAssetImport.classifyEntry`'s allow-list is bare filenames — a name containing a separator is
> structurally unrepresentable in it — so **the vendor zip cannot be imported as downloaded**.
> The delivery zip must be repacked (L8's script), which is what the spec's "top-level entries!"
> is about.
>
> **2. The two families' entry names COLLIDE.** The turbo zip's binaries are called
> `encoder_qairt_context.bin` / `decoder_qairt_context.bin` — **byte-identical names to the 4.0
> `npu` tier's installed files**, which land in the same `filesDir/models` directory. Importing
> turbo as-is would overwrite the owner's 358 MB pair. The repack therefore **renames turbo's
> entries** and `npu` keeps its 4.0 names untouched (the owner's device already has them; renaming
> would strand 358 MB and force a re-import for nothing):
>
> | tier | primary | bytes | paired | bytes |
> |---|---|---:|---|---:|
> | `npu` | `encoder_qairt_context.bin` | 132,927,488 | `decoder_qairt_context.bin` | 225,316,864 |
> | `npu-turbo` | `turbo_encoder_qairt_context.bin` | 775,831,552 | `turbo_decoder_qairt_context.bin` | 295,854,080 |
>
> `npu`'s two lengths are 4.0's shipped, device-confirmed values (`WhisperCatalog`'s
> `primaryBytes` / `pairedArtifact.approxBytes`), carried here because **this is the table the
> run-book's provisioning gate reads** and it has to name all four files on the device, not just
> the two this branch adds.

### Turbo IO — read out of its own `metadata.json`, not inferred

`chipset_attributes`: `htp_version 75`, `soc_model 57`, aliases `[qualcomm-snapdragon-8gen3,
sm8650]`, `supports_weight_sharing: true` — the identical block to the small package.

| | encoder | decoder |
|---|---|---|
| inputs | 1 (`input_features [1,128,3000]` uint16) | 19 |
| outputs | 8 (`k/v_cache_cross_0..3`) | 9 |
| in bytes | **768,000** | **17,398,168** |
| out bytes | **15,360,000** | **2,141,492** |

`input_features` quantisation: scale `4.746108970721252e-05`, zero_point `31605`.
`logits [1,51866,1,1]` uint16, scale `0.0007861469639465213`, zero_point `23109` — `scale > 0`, so
the 4.0 argmax-on-raw-codes argument transfers **exactly**.
`attention_mask [1,1,1,200]` uint16, scale `0.0015259021893143654`, zero_point `65535` — **byte for
byte the small asset's mask quantisation**, so `kMaskAttend = 65535 → 0.0` and
`kMaskBlocked = 0 → −100.0`, and F5's tightened `blocked <= -30.0` threshold passes with the same
2½ orders of magnitude of slack. Self-KV `[20,1,64,199]`/`[20,1,199,64]` uint8, depth 199, mask
width 200 — **the same shift register**, so the right-aligned fill and `lastPosition` arithmetic are
architecture-identical.

**Two preconditions the 4.0 design rests on, verified against turbo's metadata at plan time (and
still guarded at load, because a plan-time observation is not a runtime fact):**

- **Alias equality** — all 8 encoder cross-KV outputs match the decoder's 8 cross-KV inputs in
  shape, dtype **and** `{scale, zero_point}`. **0 mismatches.** (`k_cache_cross_0`: scale
  `0.12609827518463135`, zp `128`, both sides — a *different* scale from small's `0.06918466…`,
  which is exactly why the guard reads it from metadata and never from a constant.)
- **Self-KV `_in`/`_out` quantisation equality** — **0 mismatches**, so the ping-pong needs no
  requantisation between steps on turbo either.

### The per-tier spec table, and the census formula native derives

The census has **eight factors, five of which vary**. `NpuModelSpec` computes it in Kotlin from all
eight; native derives it again from the five scalars `nativeInit` receives —
`melBins, decLayers, heads, vocab, maxPositions` — plus **three that stay native constants because
they are universal across every published Whisper AI Hub asset**: `headDim = 64`,
`audioCtx = 1500` (the 30 s encoder window's 1500 frames) and `melFrames = 3000`. All seven models
in the research survey carry those three identically, and an asset that changed one would fail the
census guard on the resulting byte totals rather than pass with a wrong buffer. **Two independent
readings of five varying factors**, then — not of all eight — and a disagreement in any of the five
is visible rather than silent. Stated precisely because "the scalars determine the census" would be
false as written: they determine it *given* the three constants, and those constants are an asset
fact this seam is asserting, not deriving.

| | `npu` | `npu-turbo` |
|---|---|---|
| `melBins` | 80 | **128** |
| `melFrames` | 3000 | 3000 |
| `decLayers` | 12 | **4** |
| `heads` | 12 | **20** |
| `headDim` | 64 | 64 |
| `audioCtx` | 1500 | 1500 |
| `maxPositions` | 200 | 200 |
| `tokens.langCount` | 99 | **100** |
| `tokens.vocab` | 51865 | **51866** |

```
encIn       = 1
encOut      = 2 * decLayers                                       24        8
encInBytes  = melBins * melFrames * 2                        480,000  768,000
encOutBytes = 2*decLayers * heads * headDim * audioCtx    27,648,000  15,360,000
decIn       = 3 + 4 * decLayers                                   51       19
decOut      = 1 + 2 * decLayers                                   25        9
selfKvBytes = 2*decLayers * heads * headDim * (maxPositions-1)  3,667,968  2,037,760
decInBytes  = 4 + 4 + maxPositions*2 + selfKvBytes + encOutBytes
                                                          31,316,376  17,398,168
decOutBytes = vocab * 2 + selfKvBytes                      3,771,698   2,141,492
melFloatBytes       = melBins * melFrames * 4                960,000  1,536,000
inputFeaturesBytes  = melBins * melFrames * 2                480,000    768,000
```

The four `npu` census values reproduce `kEncoderExpectation{"encoder", 1, 24, 480000, 27648000}` and
`kDecoderExpectation{"decoder", 51, 25, 31316376, 3771698}` — 4.0's shipped, device-confirmed
constants — **exactly**. The four `npu-turbo` values reproduce its `metadata.json` **exactly**. Both
are asserted in `NpuModelSpecTest`, which is what makes the formula a reading rather than a guess.

### The token families

Every id is `langCount`-derived; nothing is written out twice.

```
eot            = 50257                     50257    50257
sot            = 50258                     50258    50258
langFirst      = 50259                     50259    50259
langLast       = 50259 + langCount - 1     50357    50358      (<|su|> / <|yue|>)
translate      = 50259 + langCount         50358    50359
transcribe     = translate + 1             50359    50360
startOfLm      = translate + 2             50360    50361
startOfPrev    = translate + 3             50361    50362
noSpeech       = translate + 4             50362    50363
noTimestamps   = translate + 5             50363    50364
timestampBegin = translate + 6             50364    50365
vocab          = timestampBegin + 1501     51865    51866
```

**`suppress` is derived, not transcribed twice.** The 88-entry `generation_config.json` list splits
into **82 base BPE ids** (every entry `< eot`, ending `…, 49870, 50254`) plus **6 special ids**
(`sot`, `translate`, `transcribe`, `startOfLm`, `startOfPrev`, `noSpeech`). The base half is
identical across the two families — **measured, see below** — so `WhisperTokenFamily.suppress` is
`BASE_SUPPRESS + intArrayOf(sot, translate, transcribe, startOfLm, startOfPrev, noSpeech)`, which
reproduces the 4.0 array verbatim for `SMALL` and shifts the tail by one for `LARGE_V3`.
`beginSuppress = [220, eot]` for both.

### The vocabulary — measured, and it settles research blocker #3

The turbo package's own `voice_ai/vocab.bin` (357,313 B, already Range-extracted to
`C:\Users\bastr\.androidbuild\npu-model-lab\voice_ai_extras\vocab.bin`) was parsed and compared,
byte-level-encoded, against the shipped `app/src/main/assets/whisper_vocab.json`:

- It splits into **exactly 50,257** NUL-terminated raw-byte tokens (`idx 0 = "!"`), i.e. the GPT-2
  base, with the specials synthesised procedurally rather than stored — as the research doc said.
- Against the shipped asset's first 50,257 entries there is **exactly ONE mismatch: id 188.**
  `vocab.bin` has it empty; the shipped asset has `U+0100`, which is the byte-level encoding of
  `0x00`. **That is the NUL casualty the research doc flagged as unverified (§5 caveat, §10 blocker
  3), now measured: one token, and it is the one NUL-termination cannot represent.** Id 50256 is
  legitimately the empty string in *both*, so it is not a second casualty.
- **Therefore turbo's base BPE is our shipped base BPE**, verified against turbo's own published
  tokenizer table, and the shipped copy is the *correct* side of the single difference.

So `whisper_vocab_turbo.json` (L4) is **built from the shipped asset's first 50,257 entries plus the
`LARGE_V3` special layout**, and `vocab.bin` is its *verifier*, not its source. That is a stronger
check than Q5 ran (Q5 re-derived only the 99 language codes) and it costs no new download.

### The 128-bin mel filterbank — provenance chain, fully local

The Q2b mel-only loader reads a contiguous prefix and stops:
`magic(4) + 11 int32 hparams(44) + filters.n_mel/n_fft(8) + n_mel*n_fft float32`. For a 128-bin
model that is **4 + 44 + 8 + 128*201*4 = 102,968 bytes**, and a file truncated there **is** a valid
mel-only ggml.

The source is already on this machine and needs no fetch:

```
C:\Users\bastr\.androidbuild\WhisperEverywhere\ggml-large-v3-turbo-q5_0.bin
    574,041,195 B   sha256 394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2
```

That digest is **`WhisperCatalog.SHA256_ULTRA`, character for character** — the `ultra` tier's own
catalog-pinned value. The chain is therefore: catalog-pinned ggml → first 102,968 bytes →
`melbank-128.bin`, re-derivable by anyone holding the `ultra` tier's file.

Header read from it (so nothing is assumed): `magic 0x67676d6c`, `n_vocab 51866`,
`n_audio_ctx 1500`, `n_audio_state 1280`, `n_audio_head 20`, `n_audio_layer 32`, `n_text_layer 4`,
**`n_mels 128`**, `ftype 2008`; `filters.n_mel 128`, `filters.n_fft 201`. `hparams.n_mels ==
filters.n_mel`, which is the fork loader's own self-consistency check.

**Extraction result (measured):** 102,968 bytes, sha256
`72814246f9837a7afb189ed3850c20cac8a5736e42993b749f86e96370a5157c`.

> **Why an exact truncation loads and a near-miss does not.** `std::istream::read` sets `eofbit`
> only when it extracts *fewer* bytes than asked. A file ending exactly on the last filterbank byte
> leaves the stream good, so `whisper_init_from_file_mel_only`'s `if (loaded && !fin)` guard passes.
> **One byte short** and the read comes up short, `failbit`/`eofbit` set, and the guard rejects the
> file with the fork's own *"ended early — the file is truncated"* message. The extraction is
> therefore self-checking at load, which is why L3 does not need a JVM harness for the native load.

### The skel, measured out of the resolved AAR

`jni/arm64-v8a/` in `qnn-runtime-2.49.0.aar`: `libQnnHtpV75Skel.so` **17,913,608 B, sha256
`a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c`**, `libQnnSystem.so` 4,072,432,
`libQnnHtp.so` 3,786,336, `libQnnHtpV75Stub.so` 772,200 — **26,544,576 B (≈25.3 MiB)** in every APK,
of which the 17.9 MiB skel is unreachable under this app's `extractNativeLibs="false"` packaging.
L6 answers I5.

### Memory budget

| tier | encoder | decoder | cross-KV | self-KV ×2 | mel/logits | NPU-side total |
|---|---|---|---|---|---|---|
| `npu` | 127 MB | 215 MB | 26.4 MiB | 7.0 MiB | 0.5 MiB | **≈ 376 MiB** |
| `npu-turbo` | 740 MB | 282 MB | 14.6 MiB | 3.9 MiB | 2.3 MiB | **≈ 1,043 MiB** |

**Turbo is ~2.8× the residency of the tier the I11 no-co-residency rule was written for.** Tear
`multi` down before arming, release the NPU before falling back, and — new in 4.1 — **release the
outgoing npu-class tier before arming the incoming one**, which is what L1's epoch makes safe to
rely on (`nativeInit` already releases any existing session; the epoch is what stops the stale
instance's queued release from undoing the new one).

---

## Task L1 — THE ARMING EPOCH  **[NATIVE]**

> **The gate.** Final review **F4/I1**: the QNN session is a process-global, `nativeInit` releases
> any existing one, and `LocalWhisperEngine.shutdown()` *queues* the stale backend's release onto a
> different executor. An `npu → npu-turbo` rebuild therefore has a losing interleaving — new `load`
> builds a session, then the stale instance's queued `nativeRelease()` destroys **it** — leaving a
> backend with `armed = true` and nothing behind it. It fails loudly at `encode` and falls back, so
> it is not silent; it is wrong, and it is exactly what the turbo A/B would have discovered on a
> device. **This lands before anything that can make two npu-class tiers exist.**
>
> The fix is not an ordering. Ordering is what does not hold here (global constraint rule 2). The
> fix is **identity**: a release names the session it means, and a session that is no longer that
> one refuses to be torn down.

**Files**
- `app/src/main/cpp/qnn_asr.cpp` (edit)
- `app/src/main/java/com/whispereverywhere/npu/QnnAsrNative.kt` (edit)
- `app/src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt` (edit)
- `app/src/test/java/com/whispereverywhere/npu/NpuNativeContractTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/transcription/NpuBackendWiringTest.kt` (edit — the J10
  pin's **message**, not its assertion)

**Steps**

1. `NpuState` gains `uint64_t epoch = 0;`. `releaseLocked()` sets it to `0`. **The successful tail
   of `nativeInit` increments a separate monotonic `nextEpoch` counter and assigns `g.epoch` from
   it**, so an epoch value is never reused within the process. `nextEpoch` starts at 1 and the
   increment happens *after* every stage has succeeded — an epoch is a receipt for a live session,
   not for an attempt.
2. `nativeRelease` becomes `nativeRelease(jlong epoch)`:
   ```cpp
   std::lock_guard<std::mutex> lock(g.mu);
   const uint64_t want = static_cast<uint64_t>(epoch);
   if (want == 0 || want != g.epoch) {
       LOGDIAG("nativeRelease: epoch %llu is not the live session (%llu) - ignored",
               (unsigned long long) want, (unsigned long long) g.epoch);
       return;
   }
   releaseLocked();
   LOGDIAG("nativeRelease complete (epoch %llu)", (unsigned long long) want);
   ```
   **The check precedes `releaseLocked()` and that is an ORDER invariant, not a presence one** — a
   guard that runs after the teardown is not a guard.
3. `nativeEpoch(): Long` — the live epoch under the same mutex, `0` when none. It is the *only*
   way Kotlin learns its epoch; `nativeInit` keeps returning `""`/`stage: detail` unchanged, because
   widening its return type to carry two things is how a stage error becomes a number nobody reads.
4. **Do NOT epoch-guard `nativeEncode`/`nativeDecodeSegment`/`nativeInputQuant`/
   `nativeDetectLanguage`.** State the reason rather than leaving it implied: every one of them runs
   inside `NativeComputeGate.serialized` on the same process-global lock as `load` and `release`, and
   `NpuWhisperBackend` checks its own `armedEpoch` against `nativeEpoch()` before the first of them
   (step 6) — so a stale instance's segment is refused at the Kotlin boundary, one check instead of
   four. **`nativeRelease` is guarded natively anyway**, because it is the destructive one and
   because "safe by a property of a different object" is precisely the shape this branch has paid
   for twice.
5. `NpuWhisperBackend` gains `private var armedEpoch: Long = 0L`. Set from `QnnAsrNative.nativeEpoch()`
   immediately after `nativeInit` returns `""` **and before `armed = true`** (a backend that is armed
   without an epoch could call the unguarded release). Cleared to `0L` in `releaseNpuResources`.
6. `releaseNpuResources()` calls `QnnAsrNative.nativeRelease(armedEpoch)`. **Guard the whole native
   touch on `armedEpoch != 0L || melCtx != 0L`** — which also closes **Q6 M1**: the `mel-donor`
   refusal is the cheapest refusal in `load` and the one a device with no ggml installed hits every
   session, and it currently `dlopen`s `libqnnasr.so` on its way out for a session that never
   existed.
7. `transcribe`'s first act inside the gate, after the fallback short-circuit: if
   `armedEpoch != 0L && QnnAsrNative.nativeEpoch() != armedEpoch`, `fallBackAndRun("epoch", "this
   backend's session (…) was replaced by a newer arm (…)", …)`. One extra JNI call per segment
   against a ~405 ms encode; the thing it buys is that a stale instance can never encode into — or
   decode out of — a session that belongs to a different tier, which is the *fluent wrong text*
   shape at its worst: another model's transcript.
8. **Q6 M3 (folded):** `NpuNativeContractTest.kotlinMemberBody`'s anti-widening guard is vacuous for
   `isTierAvailable` because that is the last companion member — adding anything after it silently
   widens the pin's scope with no failure. Terminate the extraction on the companion's closing brace
   at the recorded indent, and add a member after `isTierAvailable` in the same commit so the fix is
   exercised rather than asserted.
9. **The J10 pin's message, not its assertion.** `exactlyOneTierIdRoutesToTheNpuBackend` still
   asserts `listOf("npu")` and still passes — nothing routes turbo yet. Its failure message
   currently reads *"give `NpuWhisperBackend` an arming epoch before letting two npu-class tiers
   coexist"*. Rewrite it to: the epoch **exists** (naming `nativeEpoch`/`nativeRelease(epoch)` and
   this task), so a second id here is now a deliberate re-spec in L8, and the thing to check before
   widening it is that `armedEpoch` is threaded through release **and** transcribe. A pin whose
   guidance has been carried out but still says "do this first" trains the reader to ignore it.

**Test (red first)** — `NpuNativeContractTest`, source-contract:
- `nativeRelease` takes an epoch parameter and the **epoch comparison's offset precedes
  `releaseLocked()`'s** in the same function body (ORDER, via `liveOffsets`);
- `nativeInit` assigns `g.epoch` **after** the last stage's error return and before its success log
  (ORDER);
- `releaseLocked` zeroes `g.epoch`;
- `NpuWhisperBackend` assigns `armedEpoch` **before** `armed = true` (ORDER), passes `armedEpoch` to
  `nativeRelease` at exactly one site, and compares `nativeEpoch()` in `transcribe` **before** the
  first `pcmToMel` (ORDER);
- `QnnAsrNative` declares `external fun nativeRelease(epoch: Long)` and `external fun nativeEpoch(): Long`.

**Expected red:** `NpuNativeContractTest > nativeReleaseIsGuardedByTheArmingEpoch FAILED`.

**Battery:** `:app:assembleDebug`, then the full JVM suite.
**Expected delta:** +0 suites, +7 tests.

**Commit**

```
fix(npu): the arming epoch — a release names the session it means

Final review F4/I1. The QNN session is a process-global, nativeInit releases any
existing one, and LocalWhisperEngine.shutdown() QUEUES the stale backend's release
onto a different executor — so an npu->npu-class rebuild has an interleaving in
which the stale release destroys the session the new init just built, leaving a
backend armed with nothing behind it.

Source order cannot fix this: the two effects are not ordered by the two
statements. Identity can. nativeInit hands out a monotonic epoch, nativeRelease
refuses any epoch that is not the live one, and transcribe refuses to run on a
session that was replaced. exactlyOneTierIdRoutesToTheNpuBackend still asserts one
id and still passes; its message now says the epoch is landed, because a pin that
asks for work already done is a pin people learn to ignore.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task L2 — `NpuModelSpec`: the census the tier carries, not the one it was compiled with  **[NATIVE]**

Spec decision 1. Every number 4.0 hard-compiled becomes a field of a per-tier spec, and the F2
census guard consumes it. **This task lands the machinery and the `SMALL` row only** — `TURBO`'s row
arrives in L4, behind its vocabulary. Nothing observable changes for the `npu` tier: every derived
value must reproduce 4.0's shipped constant exactly, and the tests say so.

**Files**
- `app/src/main/java/com/whispereverywhere/npu/NpuModelSpec.kt` (new)
- `app/src/main/java/com/whispereverywhere/npu/WhisperTokenFamily.kt` (new)
- `app/src/main/java/com/whispereverywhere/npu/WhisperTokens.kt` (edit — `SMALL`, `BASE_SUPPRESS`)
- `app/src/main/java/com/whispereverywhere/npu/NpuQuantize.kt` (edit — spec-parametrised)
- `app/src/main/java/com/whispereverywhere/npu/NpuDecodePolicy.kt` (edit — family-parametrised)
- `app/src/test/java/com/whispereverywhere/npu/NpuDecodePolicyTest.kt` (edit — **937 lines, ~63 call
  sites**; every one takes the family from this task on. The largest mechanical edit in the plan)
- `app/src/main/java/com/whispereverywhere/npu/NpuDiag.kt` (edit — `mel` carries the bin count)
- `app/src/main/java/com/whispereverywhere/npu/QnnAsrNative.kt` (edit — `nativeInit`'s five scalars)
- `app/src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt` (edit — takes a spec)
- `app/src/main/java/com/whispereverywhere/transcription/NpuBackendSelector.kt` (edit — passes it)
- `app/src/main/cpp/qnn_asr.cpp` (edit)
- `app/build.gradle.kts` (edit — the `nativeSourceContract` list's property name and contents)
- `app/src/test/java/com/whispereverywhere/npu/NpuModelSpecTest.kt` (new)
- `app/src/test/java/com/whispereverywhere/npu/WhisperTokenFamilyTest.kt` (new)
- `app/src/test/java/com/whispereverywhere/npu/NpuNativeContractTest.kt` (edit)

**Steps**

1. `WhisperTokenFamily(langCount)` derives every id by the table in the asset block, plus
   `languageCodes` (the first `langCount` entries of the canonical order — the vendored
   `whisper.cpp` `g_lang` table, whose index 98 is `su` and index 99 is `yue`, both verified in
   source), `langToken`/`codeForToken` (bidirectional, throwing on an unknown code exactly as 4.0
   does), and `suppress` derived from `BASE_SUPPRESS` (the 82 entries `< eot`) plus the six specials.
2. **`object WhisperTokens` keeps every literal it has today.** Not a refactor into the derivation —
   two independent readings, the same discipline as native's `kEotToken` vs Kotlin's `EOT`, and it
   keeps 4.0's source-text pins passing unchanged. `WhisperTokens.SMALL = WhisperTokenFamily(99)`
   joins it, and `WhisperTokenFamilyTest` asserts `SMALL` reproduces all twelve ids, the 99 codes and
   the 88-entry `SUPPRESS` **element for element**.
3. `NpuModelSpec` carries the nine shape fields and computes the ten derived values by the formula in
   the asset block, with the formula written once in a KDoc that names each factor
   (`2*decLayers` = k and v per layer; `heads*headDim` = d_model; `maxPositions-1` = the cache depth).
   `NpuModelSpec.SMALL` is the `npu` row; `forTier("npu") == SMALL`, everything else `null`.
4. `NpuQuantize` loses `MEL_BINS`/`MEL_FRAMES` as constants; every entry point takes the spec. The
   `require` messages name the spec's numbers, so a 128-bin mel handed to an 80-bin buffer says so.
5. **`nativeInit` takes the five VARYING scalars** `(melBins, decLayers, heads, vocab, maxPositions)`
   and native derives `kEncoderExpectation`/`kDecoderExpectation` from them at the top of the call
   instead of reading file-scope constants. **`kHeadDim = 64`, `kAudioCtx = 1500` and
   `kMelFrames = 3000` stay native constants** and are *not* passed: they are universal across every
   published Whisper AI Hub asset (all seven in the research survey), and a fourth and fifth
   argument carrying a number that cannot vary is a number a caller can get wrong. `NpuModelSpec`
   carries the same three as fields so the Kotlin census is complete, and
   `NpuNativeContractTest` pins the three native literals against the spec's values — which is what
   keeps the two derivations one derivation. `kNpuMelBins`-style constants in `qnn_asr.cpp`
   (`kCrossKvLayers`, `kLangTokenFirst`, `kLangTokenLast`, the two `GraphExpectation`s) become
   session state on `NpuState`, set once from the scalars and used everywhere the constants were.
   **The census guard at `qnn_asr.cpp:890` is unchanged in shape and now compares against the spec**
   — which is the whole point: turbo differs there *by construction*, so the guard must be reading
   the tier's own expectation or it fires on a correct asset.
6. **Refuse an implausible scalar set before any file is opened.** `melBins ∈ {80,128}`,
   `decLayers ∈ 1..64`, `heads ∈ 1..64`, `vocab ∈ 1..65535` (it bounds a `uint16` argmax),
   `maxPositions ∈ 2..1024`, returning `spec: …` as a normal stage error. A garbage scalar must not
   reach an allocation.
7. `NpuWhisperBackend(paths, appContext, spec)` — **no default value**. A default would let a future
   call site arm turbo's assets under small's spec, and the symptom is a census mismatch at best and
   another model's transcript at worst. `NpuBackendSelector.backendFor` resolves
   `NpuModelSpec.forTier(tierId)` and returns `WhisperNativeBackend` when it is null.
7b. **`NpuDecodePolicy` takes the family the same way, and for the same reason — REQUIRED, no
   default.** Every member becomes family-taking:
   `promptTokens(family, languageCode)`, `promptTokens(family, langToken)`,
   `suppressList(family)`, `beginSuppressList(family)`, `maxTokensFor(family, promptLen)`,
   `resolveLangToken(family, requested, detected, deviceLocale)` — the two `val`s become functions
   because a per-family array is not a constant. `NpuWhisperBackend` passes `spec.tokens`.

   > **A defaulted family is exactly the hazard step 7 forbids one line above**, and it is *worse*
   > here than on the spec: `promptTokens` defaulting to `SMALL` would silently build a turbo prompt
   > with `<|transcribe|>` = 50359, which under `LARGE_V3` is `<|translate|>` — a valid,
   > unsuppressed, perfectly decodable token that puts the model in the wrong task. Fluent wrong
   > text, from a default nobody typed. See L4's `50358` note for why no per-id check catches this.
   >
   > **This reds `NpuDecodePolicyTest` at compile, and that is the point** — 937 lines and ~63 call
   > sites, every one forced to name a family. A defaulted parameter would have let all 63 keep
   > compiling while meaning something the author never chose. The edit is mechanical
   > (`WhisperTokens.SMALL` at every existing site, since every existing assertion is about the small
   > family) and it is not a rewrite: no expected value changes.
8. `NpuDiag.mel(bins, row0, rowMid, rowLast)` → `mel: bins=<n> frames=3000 row0=… rowMid=… rowLast=…`.
   The stride bisector's whole value is that `rowMid == rowLast` reads as a stride failure at a
   glance; with two bin counts, a fixed `row40`/`row79` would be meaningless on one of them. Indices
   become `0`, `melBins/2`, `melBins-1`.

**Folded 4.1 items (each with its own assertion)**

- **Q1 M-3** — `nativeProbe` does not clear `g.lastError`, so a stale `"probe: …"` is reported as
  this segment's reason on the `quant` and `decode` fallback paths. One line, and this file is open.
- **Q1 N-1** — `tensorRepoint` does not copy `quantizeParams`, which is safe only because the system
  context is held to teardown: *a lifetime property of a different object*, this branch's signature
  failure shape. Copy it, and assert at load that no tensor uses `AXIS_SCALE_OFFSET` (checked here
  against turbo's metadata: it does not — every quantised tensor is scalar `SCALE_OFFSET`).
- **Q4 M1** — the 199-token prompt edge: native refuses `promptLen > maxPositions-2` while
  `maxTokensFor(199) == 1`. With `maxPositions` now a variable, settle the two halves on one
  expression and pin the boundary.
- **Q10a-D open question** — `lastPosition = maskLen - 2` leaves one token per segment on the table
  under the confirmed right-aligned layout (`p = maskLen-1` is also exact: 199 cache slots + the
  current token = all 200 columns). Settle it **with** Q4 M1, as the verdict asks, and state which
  answer was taken and why in the code.
- **Q4 M3** — `bindDecoderLocked` logs *"all by name"* before `tensorSetClientBuf` runs on the 48
  self-KV tensors. Move `bindSelfKvLocked(0)` to be the last statement so the log line and the
  "nothing unbound" claim are literally true.
- **Q10a-D M1** — the `firstLive` ternary is unpinned; deleting it still matches the needle. The
  verdict calls it *"first on the turbo list"* because turbo has its own mask geometry. **Pin the
  ternary, not its prefix.**
- **Q10a-D M2** — the absence assertion that does not filter comments; route it through `liveOffsets`
  like every sibling (global constraint rule 3).
- **I3, the WE-NPU sweep** — `#define TAG "WE-NPU"` at `qnn_asr.cpp:77` puts **41 `LOGI`/`LOGW`/
  `LOGE` sites** on a tag `adb logcat -s WE-DIAG` cannot see, including `vote: %s`, whose entire
  design note is *"always logged, never silently empty (lesson 6)"*. One line: `#define TAG
  "WE-DIAG"`. `LOGDIAG`/`LOGDIAGE` keep their separate identity (they are the `g.diag`-gated pair),
  and `NpuNativeContractTest` gains an assertion that `qnn_asr.cpp` contains **no live occurrence of
  the string `WE-NPU`** at all. This task is the last one that touches this file, which is why the
  sweep lands here.
- **Q7a M4(ii)** — `withPropertyName("nativeSourceContract")` no longer describes its contents (it
  holds two assets and eleven Kotlin files). Rename it `sourcePinnedInputs` here, while the list is
  being edited anyway and before L3/L4/L6/L8 each add to it.

**Test (red first)**
- `NpuModelSpecTest`: `SMALL`'s ten derived values equal 4.0's shipped constants exactly
  (`1/24/480000/27648000`, `51/25/31316376/3771698`, `960000`, `480000`); the formula is checked
  against a hand-computed second row so a transposed factor cannot pass; `forTier` answers `null`
  for `"npu-turbo"` **today** (a red here in L4 is that task's own signal); the scalar-refusal table.
- `WhisperTokenFamilyTest`: `SMALL` reproduces all twelve ids, the 99 codes in order, the round trip,
  the 88-entry suppress element-for-element, and `beginSuppress == [220, 50257]`. Plus the 4.0
  census alarm, re-homed: every `PreferencesManager.SUPPORTED_LANGUAGES` code except `"auto"`
  resolves through `SMALL`.
- `NpuNativeContractTest` gains: the census guard reads the session's expectation and not a
  file-scope constant; **`kHeadDim`/`kAudioCtx`/`kMelFrames` are literals `64`/`1500`/`3000` and
  equal `NpuModelSpec`'s three fields**; the `AXIS_SCALE_OFFSET` refusal; the `firstLive` ternary;
  no live `WE-NPU`.
- `NpuDecodePolicyTest` — all ~63 sites take `WhisperTokens.SMALL` and **every expected value is
  unchanged**, which is the assertion that this refactor moved no behaviour. Four new tests: the
  family parameter has **no default** (source-contract — a default is the whole hazard), and
  `suppressList`/`beginSuppressList`/`maxTokensFor` answer per family rather than per process.

**Expected red:** `NpuModelSpecTest > smallReproducesTheShippedEncoderCensus FAILED`.
*(`NpuDecodePolicyTest`'s ~63 sites go compile-red in the same task; that is the required parameter
working, and it is resolved in this commit — see step 7b.)*

**Battery:** `:app:assembleDebug`, full JVM suite.
**Expected delta:** +2 suites, +30 tests.

**Commit**

```
feat(npu): NpuModelSpec — the census the tier carries, not the one it compiled with

Every number 4.0 hard-compiled — mel bins, cross-KV layers, vocab, the language
band, the two GraphExpectations, the mask arithmetic's bounds — becomes a field of
a per-tier spec, and nativeInit takes the five scalars the rest derive from. The
F2 census guard now compares against the tier's own expectation, which is the only
way it can stay a guard once a second asset exists: turbo differs there by
construction.

Nothing observable changes for `npu`. Every derived value reproduces the shipped,
device-confirmed constant exactly, and NpuModelSpecTest asserts each one, so the
formula is a second reading of the asset rather than a new source of truth.

Also: the WE-NPU tag sweep (41 sites the owner's only capture cannot see,
including the power vote), the cross-KV quantizeParams deep-copy, and the two
position-cap off-by-ones settled together at the boundary they share.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task L3 — The 128-bin filterbank: one mel implementation, two data sources  **[NATIVE + ASSET]**

Spec decision 2. The 80-bin path keeps the installed-tier donor and **nothing changes for `npu`**.
The 128-bin path ships a 102,968-byte filterbank asset and hands `initMelOnly` the same kind of file
it already loads.

**Files**
- `tools/extract_melbank.py` (new)
- `app/src/main/assets/melbank-128.bin` (new — 102,968 B)
- `app/src/main/java/com/whispereverywhere/npu/NpuAssetStage.kt` (new)
- `app/src/main/java/com/whispereverywhere/whisper/WhisperNative.kt` (edit — `pcmToMel` bin arg)
- `app/src/main/cpp/whisper_jni.cpp` (edit)
- `app/src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt` (edit — mel source)
- `app/src/main/assets/oss_licenses.html` (edit — the filterbank's attribution)
- `app/build.gradle.kts` (edit — the asset joins `sourcePinnedInputs`)
- `app/src/test/java/com/whispereverywhere/npu/MelbankAssetTest.kt` (new)
- `app/src/test/java/com/whispereverywhere/npu/NpuAssetStageTest.kt` (new)
- `app/src/test/java/com/whispereverywhere/npu/MelExportContractTest.kt` (edit)
- Fork (`app/src/main/cpp/whisper.cpp`, `TechBran/whisper.cpp`, `we/v1.9.1-android`):
  `src/whisper.cpp`, `include/whisper.h` (two one-liners — see the folded items)

**Steps**

1. `tools/extract_melbank.py` — absolute interpreter
   (`C:\Users\bastr\AppData\Local\Programs\Python\Python313\python.exe`), takes the source ggml path
   and the output path, defaulting the source to
   `C:\Users\bastr\.androidbuild\WhisperEverywhere\ggml-large-v3-turbo-q5_0.bin`. It:
   - **asserts the source's sha256 is
     `394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2`** — the catalog's
     `SHA256_ULTRA`, so the provenance is checked against a value the app already ships and not
     against the script's own opinion;
   - reads `magic == 0x67676d6c`, the 11 `int32` hparams and `filters.{n_mel,n_fft}`, asserts
     `hparams.n_mels == filters.n_mel == 128` and `n_fft == 201` (the fork loader's own two checks,
     run before the file is written rather than after it ships);
   - writes exactly `56 + n_mel*n_fft*4 = 102,968` bytes and **asserts the output's sha256 is
     `72814246f9837a7afb189ed3850c20cac8a5736e42993b749f86e96370a5157c`**;
   - fails loudly, with a message naming the expected and actual value, on any mismatch.
   > **No HTTP Range fetch, and no `ggml-large-v3-turbo` download.** The spec allowed one as a
   > fallback if no local turbo ggml existed. One does: the `ultra` tier's file is on this machine at
   > the catalog's exact size and digest. A network path would have added a failure mode and a
   > provenance argument for an artefact we can derive from a value already pinned in the app.
2. `NpuAssetStage.stagedPath(context, assetName, expectedBytes, expectedSha256)` — copies an APK
   asset into `filesDir` once. `assets/` entries are not files on disk, and `initMelOnly` (like the
   FastRPC loader in L6) needs a path. **Streaming sha256 during the copy, never a second read**;
   `.part`-then-rename; idempotent — if the destination already matches size *and* digest it returns
   immediately, so the second arm costs one stat and one hash of 103 KB. Any failure returns `null`
   with a named `WE-DIAG` line, and the caller treats it as a stage refusal.
3. `pcmToMel` gains `melBins`. Native refuses unless **both** `whisper_model_n_mels(ctx) == melBins`
   **and** `GetDirectBufferCapacity(out) == melBins * 3000 * 4`, each with its own message. Two
   checks, not one: the first catches the wrong donor, the second catches the wrong buffer, and a
   single combined check would name the wrong one half the time. `kNpuMelBins` is deleted;
   `kNpuMelFrames`/`kNpuMelSamples` stay (both families share a 30 s / 3000-frame window, and the
   asset block says so).
4. `NpuWhisperBackend.load` resolves the mel context **per spec**:
   - `spec.melAsset == null` (the `npu` row) → `paths.cpuTierModelPath()`, unchanged, same
     `mel-donor` / `mel-init` stages, same messages;
   - `spec.melAsset != null` (turbo) → `NpuAssetStage.stagedPath(appContext, spec.melAsset,
     MELBANK_128_BYTES, MELBANK_128_SHA256)`, stage name `mel-asset`, then the same `initMelOnly`.
     Those two constants live on `NpuModelSpec` beside the asset name — `102_968L` and
     `"72814246f9837a7afb189ed3850c20cac8a5736e42993b749f86e96370a5157c"` — and are the same values
     `MelbankAssetTest` and `tools/extract_melbank.py` assert, so all three readings are one value.
   **`cpuTierModelPath()` stops being the mel donor for turbo but stays the CPU fallback**, and those
   were one question only because 4.0 had one tier. `isMelDonorEligible`'s `model.id != "npu"`
   exclusion becomes `NpuModelSpec.forTier(model.id) == null` — structural, so `npu-turbo` is
   excluded by the same clause that excludes `npu` rather than by a second literal somebody has to
   remember to add. The `ultra` exclusion stays as-is: it is a 128-bin *ggml*, which is a different
   fact from being an NPU tier, and the KDoc already says the real fix is a mel-bin count in the
   catalog.
5. `oss_licenses.html` gains one line: the 128-bin filterbank is derived from OpenAI Whisper
   large-v3-turbo's ggml conversion, MIT model weights / Apache-2.0 tooling, with the derivation
   named (first 102,968 bytes = magic + hparams + filterbank). Q5's I1 established that the shipped
   licence page is a ship gate and that its edits must be pin-protected; this asset is the same
   class of thing.
6. **Stage order (Q6 M2, folded):** move the `companionPath.isNullOrBlank()` refusal from stage 4 to
   **stage 1**. It is a null test on a string and *the single most likely reason the tier does not
   come up on a real device*; today it sits behind 563 KB of vocabulary parsing. The principle the
   rest of `load` is built on is "the cheapest refusal first", and this is the one place it is
   violated. Pin the order.

**Folded 4.1 items**

- **Q2 M2** — the fork's `whisper_init_from_file_mel_only` never sets `ctx->params`, and
  `whisper_init_state(ctx)` reads it. One line (`ctx->params = whisper_context_default_params();`)
  makes the mel-only context inert-safe **by construction** rather than by "nobody calls that" —
  which is the shape of assumption this branch was defeated by twice. The melbank asset is exactly a
  new caller of that loader, so it lands here.
- **Q2 M12** — `whisper.h`'s return-line for `whisper_get_mel_segment` is narrower than the runtime
  guard. The header is the contract the fork publishes and the one artefact a rebase forces someone
  to re-read.
- **Q2 M7** — `MelExportContractTest.theKotlinExternTakesFloatArrayNotPcm16` bypasses `liveOffsets`;
  one word, and it is the exact hole class that let M8 survive its first pass.
- **Q2 M8** — `kNpuMelSamples` and the capacity check are unpinned. The verdict flagged them because
  *"the turbo asset has a different window shape"*. It does not (both are 3000 frames) — but the
  **capacity** is now genuinely per-tier, so pin it against `melBins * 3000 * 4` rather than a
  literal.
- **Q10a-D M3** — `NpuWhisperBackend.kt`'s *"6,000 extra quantise calls"* comment; it is 3,080, and
  with the spec it is `melBins + melFrames`. This file's comments are treated as measurements and one
  wrong measurement devalues the rest.

**Test (red first)**
- `MelbankAssetTest` — loads the **shipped** asset by path with the house `source(relative)` walker
  (`app/src/main/assets` is not on the JVM test classpath) and asserts: length exactly 102,968;
  sha256 exactly `72814246f9837a7afb189ed3850c20cac8a5736e42993b749f86e96370a5157c` (compared
  against `NpuModelSpec.MELBANK_128_SHA256`, so the test and the runtime read one value);
  `magic == 0x67676d6c`; `hparams.n_mels == 128`;
  `filters.n_mel == 128`; `filters.n_fft == 201`; `hparams.n_mels == filters.n_mel`; the file length
  is **exactly** `56 + 128*201*4`, so a byte of slop in either direction is a red; and
  `tools/extract_melbank.py` contains both pinned digests as literals.
- `NpuAssetStageTest` — the pure parts against a temp dir: a fresh copy verifies and renames; a
  destination that already matches is a no-op (asserted by mtime, not by output); a digest mismatch
  refuses, names the asset, and **leaves no `.part`**; a short read refuses.
- `MelExportContractTest` — `pcmToMel` declares `melBins`, checks `whisper_model_n_mels` against it
  **and** the capacity against `melBins * 3000 * 4`, with the two refusals distinct; the fork's
  `ctx->params` assignment exists; the `liveOffsets` fix.

**Expected red:** `MelbankAssetTest > theShippedFilterbankIsExactlyTheTurboPrefix FAILED`.

> **Known limit, stated honestly.** Nothing here executes `initMelOnly` — no JVM test can. The load
> is proved on device at L8, and it is *self-checking there*: the fork's `if (loaded && !fin)` guard
> rejects a file that is one byte short, and the `n_mels`/`n_fft` checks reject a file that is not a
> filterbank at all. The JVM assertions target the exact mutations (the length, the digest, the two
> header agreements) rather than merely asserting the file exists.

**Battery:** `:app:assembleDebug`, full JVM suite.
**Expected delta:** +2 suites, +18 tests.

**Commit (app repo; the submodule carries its own)**

```
feat(npu): melbank-128.bin — one mel implementation, two data sources

The Q2b mel-only loader reads magic -> hparams -> filterbank and stops, so a file
truncated at the filterbank boundary IS a valid mel-only ggml: 102,968 bytes for a
128-bin model, extracted from the `ultra` tier's own ggml, whose sha256 the catalog
already pins. No donor model, no second mel implementation, no download.

An exact truncation loads and a near-miss does not — istream::read sets eofbit only
on a SHORT read — so the fork's own "ended early" guard is the net under the
extraction, and the extractor asserts both digests besides.

pcmToMel takes the bin count and checks it twice (the donor's bands AND the
destination's capacity), because one combined check names the wrong one half the
time. The companion refusal moves to stage 1: it is a null test on a string and the
likeliest reason this tier does not come up.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task L4 — The `LARGE_V3` family: turbo's vocabulary, verified against turbo's own

Spec decision 3. The band moves to `50259..50358`, every special above it shifts by one, and the
census alarm extends to both families.

**Files**
- `tools/build_turbo_vocab.py` (new)
- `app/src/main/assets/whisper_vocab_turbo.json` (new — 51,866 entries)
- `app/src/main/java/com/whispereverywhere/npu/WhisperTokenFamily.kt` (edit — `LARGE_V3`)
- `app/src/main/java/com/whispereverywhere/npu/WhisperTokens.kt` (edit — the `LARGE_V3` handle)
- `app/src/main/java/com/whispereverywhere/npu/NpuModelSpec.kt` (edit — the `TURBO` row)
- `app/src/main/java/com/whispereverywhere/npu/WhisperBpeDecoder.kt` (edit — `expectedSize`)
- `app/src/main/cpp/qnn_asr.cpp` (edit — the detect line's token rendering)
- `app/src/main/assets/oss_licenses.html` (edit)
- `app/build.gradle.kts` (edit — the asset joins `sourcePinnedInputs`)
- `app/src/test/java/com/whispereverywhere/npu/TurboVocabAssetTest.kt` (new)
- `app/src/test/java/com/whispereverywhere/npu/WhisperBpeDecoderTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/npu/WhisperTokenFamilyTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/npu/NpuDecodePolicyTest.kt` (edit)

**Steps**

1. `tools/build_turbo_vocab.py` builds `whisper_vocab_turbo.json` as **51,866 entries**:
   `whisper_vocab.json[0 until 50257]` (the shared base) followed by the `LARGE_V3` special layout —
   `<|endoftext|>`, `<|startoftranscript|>`, the 100 `<|xx|>` tags in `g_lang` order ending
   `<|yue|>`, `<|translate|>`, `<|transcribe|>`, `<|startoflm|>`, `<|startofprev|>`,
   `<|nocaptions|>`, `<|notimestamps|>`, then 1,501 `<|0.00|>`..`<|30.00|>` timestamps.
   **The spellings match the shipped small asset's exactly** (including `<|nocaptions|>`, which is
   what that asset uses where HF writes `<|nospeech|>`) so the two files are diffable.
2. **The script's verification step is the point, and it is a real cross-check.** It parses
   `C:\Users\bastr\.androidbuild\npu-model-lab\voice_ai_extras\vocab.bin` — **turbo's own published
   tokenizer table**, 357,313 B, shipped in the same Apache-2.0 `voice_ai` flavour — splits it on
   `0x00`, byte-level-encodes each token, and compares against the base half it just copied. It
   **requires exactly 50,257 tokens and exactly one mismatch, at id 188**, and fails otherwise.
   > **That one mismatch is `vocab.bin`'s NUL casualty, and this is where the research doc's blocker
   > #3 gets closed.** Id 188 is the `0x00` byte token, which a NUL-terminated table cannot
   > represent; the shipped asset has it (`U+0100`, the byte-level encoding of `0x00`) and
   > `vocab.bin` does not. Id 50256 is legitimately empty in both, so it is not a second casualty.
   > A *second* mismatch anywhere would mean the two families' base vocabularies are not the same
   > vocabulary, which is the assumption the whole task rests on — hence the exact count, not a
   > threshold.
3. `WhisperTokenFamily.LARGE_V3 = WhisperTokenFamily(100)`; `languageCodes` gains `yue` at index 99,
   from the vendored `g_lang` table (`src/whisper.cpp:380`, `{ "yue", { 99, "cantonese" } }`).
4. `NpuModelSpec.TURBO` — the `npu-turbo` row from the asset block, `melAsset = "melbank-128.bin"`,
   `vocabAsset = "whisper_vocab_turbo.json"`, `tokens = WhisperTokens.LARGE_V3`. `forTier` answers it.
   **`NpuModelSpecTest`'s "forTier answers null for npu-turbo" assertion goes red here and is
   replaced with the turbo census, in this commit** — that is the alarm working, not a break.
5. `WhisperBpeDecoder(vocabulary, expectedSize)` / `fromJson(json, expectedSize)`. The constructor's
   error message keeps naming the wrong-file case, now in both directions ("51,865 is whisper-small
   and 51,866 is large-v3/turbo; a vocabulary of the wrong size still binds, still decodes, and
   produces fluent wrong text"). `ASSET_NAME` is replaced by `spec.vocabAsset` at the one call site.
6. **The census alarm extends** (`WhisperTokenFamilyTest`): every `PreferencesManager.SUPPORTED_LANGUAGES`
   code except `"auto"` must resolve **through both families**, and both families' boundary ids are
   pinned explicitly — `SMALL.langFirst == 50259`, `SMALL.langLast == 50357`,
   `LARGE_V3.langFirst == 50259`, `LARGE_V3.langLast == 50358`, plus `SMALL.vocab == 51865` and
   `LARGE_V3.vocab == 51866`. A code the picker offers that one family cannot name is exactly the
   silent-wrong-language failure the 4.0 alarm was built for, and there are now two ways to have it.
7. Native's language band comes from the spec's `langFirst`/`langLast` (already session state after
   L2); this task only supplies turbo's values through `NpuModelSpec`.

**Folded 4.1 items**

- **Q10a-D M4** — the `npu-debug: detect` line prints token ids with raw `%d`, bypassing
  `diagToken`. It is safe today by a *call-site* property (`argmaxInRange` bounded to the language
  band) — precisely the shape D1's own battery row exists to forbid. The rule belongs in the helper.
- **Q5 M3** — `WhisperBpeDecoderTest.kt:472` can throw `StringIndexOutOfBoundsException` instead of
  asserting. Two words (`token.length >= 4`), and it is one of the three tests guarding the shipped
  vocabulary — i.e. the ones a future asset edit will actually trip.
- **Q5 M5** — `UNICODE_TO_BYTE` is `internal` with no external consumer; `private` is tighter and free.
- **Q8 M7** — the orphaned KDoc at `WhisperBpeDecoderTest.kt:248-254`, now attached to the wrong
  function. Same defect class as Q6's fix-round KDoc swap, which this branch already paid to fix once.

**Test (red first)**
- `TurboVocabAssetTest` — against the **shipped** asset, by path: exactly 51,866 entries; entries
  `0 until 50257` are **element-for-element identical** to `whisper_vocab.json`'s first 50,257 (the
  claim the whole design rests on, asserted rather than argued); `[50257] == "<|endoftext|>"`;
  `[50258] == "<|startoftranscript|>"`; `[50259] == "<|en|>"`; `[50358] == "<|yue|>"`;
  `[50359] == "<|translate|>"`; `[50360] == "<|transcribe|>"`; `[50364] == "<|notimestamps|>"`;
  `[50365] == "<|0.00|>"`; `[51865] == "<|30.00|>"`; every entry passes the byte-level-alphabet
  check; and `tools/build_turbo_vocab.py` contains the literal `50257` count and the `188` mismatch
  id, so the verification cannot be quietly loosened to a threshold.
- `WhisperBpeDecoderTest` — a decoder built on the turbo asset with `expectedSize = 51866`
  round-trips the same golden vectors as the small one (identical base ⇒ identical output), drops
  `50358` (`<|yue|>`) as a special, and **refuses** the turbo asset at `expectedSize = 51865` and the
  small asset at `51866`, each with the wrong-file message.
- `WhisperTokenFamilyTest` — `LARGE_V3`'s twelve ids against the table above; the 100 codes; the
  round trip; `LARGE_V3.suppress` equals `SMALL.suppress` on the 82 base ids and shifts the five task
  ids by exactly one; both families' boundary pins; the two-family census alarm.
- `NpuDecodePolicyTest` — `promptTokens` under `LARGE_V3` is `[50258, <lang>, 50360, 50364]` against
  `SMALL`'s `[50258, <lang>, 50359, 50363]`, and **id `50358` is asserted under both families with
  its two different meanings**: `<|translate|>` under `SMALL` (a task token — `codeForToken` must
  answer `null` and `promptTokens(50358)` must be **refused**), and `<|yue|>` under `LARGE_V3` (a
  valid language token — `codeForToken` must answer `"yue"` and `promptTokens(50358)` must be
  **accepted**).

  > **This is the sharpest cross-family confusion in the branch, and no per-id check can catch it.**
  > `50358` is not invalid in either family; it is *valid in both and means different things*. A
  > validity guard — `require(codeForToken(id) != null)`, exactly the one `promptTokens` already
  > carries — passes under one family and fails under the other for the *same* id, so it cannot be
  > the thing that keeps them apart. **The family has to be threaded to every caller**, which is why
  > L2 makes it a required parameter with no default rather than a check. Refusing `50358` at the
  > turbo family would break Cantonese, and asserting it under `SMALL` alone would prove nothing.

**Expected red:** `TurboVocabAssetTest > theTurboBaseIsTheSmallBaseElementForElement FAILED`.

**Battery:** full JVM suite.
**Expected delta:** +1 suite, +19 tests.

**Commit**

```
feat(npu): the LARGE_V3 token family and turbo's vocabulary asset

large-v3 adds <|yue|>, so the band is 50259..50358 and EVERY special above it
shifts by one: <|transcribe|> is 50360 here and 50359 there, <|notimestamps|>
50364 and 50363. Those are not two constants, they are one derivation from
langCount, and the family table is what stops a 51,865-shaped assumption reaching a
51,866-wide logits layer.

The asset is BUILT from the shipped base and VERIFIED against turbo's own
voice_ai/vocab.bin: 50,257 tokens, exactly one mismatch, at id 188 — the 0x00 byte
token a NUL-terminated table cannot represent. That closes the research doc's
blocker #3 with a measurement, and it proves the two families share a base vocabulary
rather than assuming it. A second mismatch anywhere fails the build.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task L5 — The eighth tier: catalog, the offer gate as a set, chooser copy  ⚠️ **pin breaks 1-4**

Spec decisions 5 (the catalog half) and 8. Steering behaviour is **unchanged**: `multi` stays the
default, `npu` stays the multilingual steer where it is offered, and **turbo never steers and never
auto-selects.** Only the gate's *type* changes, because two gated tiers can be independently
installed and a single Boolean cannot say which.

> ### THE `npu-small-float` DECISION — **DROPPED**, with the one line the spec permits
>
> **Whisper-Small float is not a fourth `NpuModelSpec` row; it is a second dtype regime through
> every load-bearing line of the native seam.** Its `input_features` is not `ufixed16`, so
> `nativeInputQuant`/`melToU16` do not apply; its cross-KV and self-KV are `float16`, so every
> census byte-count factor changes and the alias guard compares a different field; its `logits` are
> `float16`, so the argmax-on-raw-codes monotonicity argument — *"scale × (q − zp) with scale > 0,
> strictly monotonic, so the argmax over the codes IS the argmax over the values"*, the reason the
> suppression mask can be applied to raw codes — does not hold as written; and its `attention_mask`
> carries no quantisation codes, so `checkMaskCodesLocked` has nothing to check. That is four
> correctness arguments to re-derive and re-review for one measurement the spec itself marks
> stretch. **The quantisation-cost question it answers is a measurement, and 4.1 already ships the
> instrument** (`npu: encode= decode= tokens=`, per-tier): if the owner's A/B says turbo's accuracy
> is worth its latency, a dtype-generic seam is the honest next branch, and if it does not, the
> question never needed asking.

**Files**
- `app/src/main/java/com/whispereverywhere/model/WhisperModel.kt` (edit)
- `app/src/main/java/com/whispereverywhere/model/ModelTierCopy.kt` (edit)
- `app/src/main/java/com/whispereverywhere/service/CommitCadencePolicy.kt` (edit)
- `app/src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt` (edit — `offeredNpuTierIds`)
- `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt` (edit)
- `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt` (edit)
- `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt` (edit)
- `app/src/test/java/com/whispereverywhere/model/WhisperCatalogHelpersTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/model/ModelTierCopyTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/service/CommitCadencePolicyTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/ui/screens/ChooserSteerWiringPinTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/npu/NpuDiagTest.kt` (edit)

**Steps**

1. The `npu-turbo` catalog entry, every value measured (asset block):
   ```
   id            = "npu-turbo"
   displayName   = "Multilingual on NPU (large-v3-turbo)"
   fileName      = "turbo_encoder_qairt_context.bin"
   url           = NPU_TURBO_ASSET_ZIP_URL
   approxBytes   = 1_071_685_632L        // the PAIR — what the badge states
   primaryBytes  =   775_831_552L        // the encoder alone — what isInstalled gates
   sha256        = "f7d11c08a20ea671f59b3ace2f9421da00b06170ac9fe946f29092ee59be6bbe"
   scope         = ModelScope.MULTILINGUAL
   minRamBytes   = 0L                    // the SoC gate already restricts this to 8 Gen 3-class
   gated         = true
   pairedArtifact = PairedArtifact(
       "turbo_decoder_qairt_context.bin", NPU_TURBO_ASSET_ZIP_URL,
       "c19b067766180843fca6266531605bf037820c5e5ae178bd6dc03785df4c6ae4", 295_854_080L)
   ```
   The filenames are the **repacked** ones — see the asset block's collision note. `NPU_TURBO_ASSET_ZIP_URL`
   is the vendor S3 URL (provenance, not a download source: `isInstallableByDownload` is
   `pairedArtifact == null`, so both npu tiers stay out of every download path structurally).
2. **`pickableFor(offeredGatedIds: Set<String>)`.** `pickable` is untouched (`!retired && !gated`)
   and still `[pro, multi]`, so every caller that cannot answer the gate keeps the identical lineup.
   The `it.id == "npu"` special case disappears — a gated tier is offered iff its id is in the set,
   which is what the parameter now means. Same change to
   `ModelTierCopy.steerIdForLanguageTagFor`/`orderedForLanguageTagFor`; the steer body becomes
   `if ("npu" in offeredGatedIds && cpuSteer == "multi") "npu" else cpuSteer`, i.e. **behaviourally
   identical**, and turbo simply joins the lineup below the steer.
3. **`WhisperEverywhereApp.offeredNpuTierIds(): Set<String>` replaces `isNpuTierOffered()`, and the
   conjunction order flips** — this is **Q7b NEW-1 / m3**, folded, and the flip is only cheap
   because the shape is changing anyway:
   ```kotlin
   val installed = WhisperCatalog.entries.filter { it.gated && whisperModelManager.isInstalled(it) }
   if (installed.isEmpty()) return emptySet()          // no dlopen on a device with no assets
   if (!npuCapableDevice) return emptySet()            // the ~7.9 MiB probe, now conditional
   return installed.map { it.id }.toSet()
   ```
   Today the capable half runs first and unconditionally, so every SM8650 maps ~7.9 MiB of QNN at
   bubble-service start — *above* the `delay(1500)` that exists to keep boot work out of app launch —
   whether or not any pair was ever imported. NEW-1 recorded that a pin *enforces* the eager form,
   which is why this was never a one-liner; the pin is re-spelled here against the new shape. The
   one-per-process `NpuDiag.offer` line gains the tier-id set so a "the card never showed" report
   still separates wrong-SoC from stack-did-not-load from nothing-installed.
4. Both chooser producers become `Set<String>`, and the local keeps the value's name:
   `val npuTierIds by produceState(initialValue = emptySet<String>(), key1 = installGeneration) { … }`.
   `npuCapable` (the import panel's capability-only gate) is unchanged in meaning and stays.
5. `ModelTierCopy` gains the turbo card:
   ```
   headline = "Best quality, slower"
   badges   = listOf("90+ languages", "1072 MB")
   body     = "Large-v3's own encoder, on your phone's AI chip. Bigger and slower than " +
              "Multilingual on NPU — the reason to pick it is the words, not the speed."
   ```
   `"Best quality"` is the spec's own owner-approved framing (decision 8), and `", slower"` is the
   disclosure the house rules require beside it — it is also already in `POSITION_WORDS`, so the
   position pin passes **without editing the test's constant to fit the copy**, which is the wrong
   way round. The body does the rest of decision 8's work (`"Large-v3's own encoder, on your phone's
   AI chip"`) and then declines to make a speed claim at all: **no WER has been measured for any
   w8a16 Whisper variant** (residual risk 5), so *"the reason to pick it is the words"* is the most
   the copy is entitled to say and is exactly what the A/B is for. `1072 MB` is within the ±5 MB
   tolerance of `approxBytes / 1_000_000` (1,071). No cross-app comparison, no absolute,
   our-own-before/after only.
6. `CommitCadencePolicy`: `"npu-turbo"` joins the `MIN_COMMIT_INTERVAL_FAST_MS` row **with its
   reason recorded**: published 8 Gen 3 raw-QNN figures put turbo at ~1.37-1.57 s per segment against
   npu's ~1.0 s measured, both well under the 2.3 s that `multi`'s 6 s floor exists for, and the
   floor is a minimum interval rather than a metronome — the VAD cuts at real pauses. **Provisional
   on L8's measurement, exactly as `npu`'s was:** if the owner's `npu:` lines show per-segment cost
   above the cadence, commits will visibly lag and that is the signal to give turbo its own constant.

**Test (red first) — run the suite BEFORE touching any test**

**Break 1 — `CommitCadencePolicyTest:119 everyCatalogTierIsNamedExplicitly`.** *"a catalog tier
gained or lost an entry — decide its cadence."* Resolution: `"npu-turbo" to 1_200L`.

**Break 2 — `WhisperCatalogHelpersTest:13 catalog_hasFiveEntries_withExpectedIds`** (7 → 8 ids).
Also extend `every_sha256_is_lowercase_hex_of_the_right_length` to assert **every catalog sha256 is
distinct** — with four npu digests in the file, a copy-paste between two `PairedArtifact`s is now a
real mutation and it would install the wrong half of a pair with a passing verification.

**Break 3 — `ModelTierCopyTest`'s census loops and the three `steerIdForLanguageTagFor(tag, true)`
cases** (the `Boolean` no longer compiles). Resolution: `setOf("npu")` for the existing expectations —
**identical assertions, new spelling** — plus new turbo cases asserting `setOf("npu", "npu-turbo")`
still steers to `npu` and that `orderedForLanguageTagFor` is a permutation of
`pickableFor(setOf("npu","npu-turbo"))` with the steered tier leading.

**Break 4 — `ChooserSteerWiringPinTest`'s indentation-exact needles** on both screens (`(languageTag,
npuAvailable)` → `(languageTag, npuTierIds)` and the two `produceState` blocks). Resolution: update
every needle in this commit and **KEEP the `count(..., "WhisperCatalog.pickable") == 0` assertions** —
those encode the Bengali review and must not be relaxed. Folded here: **Q7b M2**, the
`listOf("SOC_MODEL", "SOC_MANUFACTURER")` loop's three holes, of which hole (i) is *"the widest-
blast-radius test hole on the branch"* — `import android.os.Build.SOC_MODEL` makes a bare unguarded
read raise neither the total nor the guarded count, so the API-31 guard whose failure mode is
`NoSuchFieldError` **on every pre-S device that opens the chooser** can be bypassed without the pin
noticing. Assert on the import as well as the qualified reads.

**New turbo copy assertions** (mirroring the npu ones Q7a's I5 established, because the census loops
still cannot reach a gated tier through `pickable`): headline exact and position-bearing; badges
exactly `["90+ languages", "1072 MB"]`; the MB badge within 5 of `approxBytes / 1_000_000` and
**greater than `primaryBytes / 1_000_000`** (so a future edit that badges only the encoder fires);
no cross-app comparison; and — new — **no two offered tiers share a headline**, since the lineup now
has two NPU cards a user must tell apart at a glance.

**Folded 4.1 items**

- **Q7a M2** — `NPU_ASSET_ZIP_URL`'s value is unpinned, and it is *"the only record of where 358 MB
  came from"*. There are two such URLs now. Pin both by `endsWith` on the vendor path.
- **Q7b NEW-5** — the over-broad `Log.i` pin in `WhisperEverywhereApp`. An over-broad pin fails for
  reasons unrelated to its invariant, which trains people to weaken pins; that file is open here.
- **m2** — `SettingsScreen.kt:417-420`'s subtitle enumerates `WhisperCatalog.pickable`, which now
  excludes **two** tiers the screen it opens will show. Drop the enumeration.

**Expected red (first run):** `CommitCadencePolicyTest > everyCatalogTierIsNamedExplicitly FAILED`
and `WhisperCatalogHelpersTest > catalog_hasFiveEntries_withExpectedIds FAILED`.

**Battery:** full JVM suite.
**Expected delta:** +0 suites, +22 tests.

**Commit**

```
feat(npu): npu-turbo joins the catalog; the offer gate becomes a set

Four census pins fired as designed and are resolved here, not worked around.

The gate's Boolean becomes Set<String> because two gated tiers can be installed
independently and one bit cannot say which — and because `it.id == "npu"` was a
literal that a second gated tier would have had to be remembered into. Steering is
byte-for-byte the behaviour it was: multi is still default, npu is still the
multilingual steer, turbo never steers and never auto-selects.

The conjunction also flips: installed-half first, so the ~7.9 MiB QNN dlopen no
longer runs at bubble-service start on every 8 Gen 3 that never imported a pair.
That was NEW-1, deferred because a pin enforced the eager form; the pin is
re-spelled against the shape that had to change anyway.

Turbo's headline states the trade rather than claiming a win. No WER has been
measured for any w8a16 Whisper variant — that is what the owner's A/B is for.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task L6 — Hash-verified imports, and the skel packaging ANSWER

Spec decisions 5 (the import half) and 7. Two ~GB-class pairs are about to land through a path that
has **never executed on a device even once** (residual risk 9) and that verifies nothing but size
(residual risk 10).

> ### THE I5 ANSWER — **trim the packaging, stage the skel from a build-generated asset**
>
> The two options were: the import zips carry `libQnnHtpV75Skel.so`, or the gradle dependency gets
> trimmed. **Taken: trimmed.** `packaging.jniLibs` excludes `**/libQnnHtpV75Skel.so`; a Gradle task
> extracts that exact entry from the *resolved* `qnn-runtime-2.49.0.aar` into a generated assets
> directory, asserting its size (**17,913,608 B**, measured from the AAR in the cache); and
> `NpuAssetStage` — L3's helper, reused unchanged — copies it into `filesDir` on first arm, which is
> already first on `ADSP_LIBRARY_PATH`.
>
> **What it costs.** The APK does not shrink: the same 17.9 MiB moves from `lib/` to `assets/`. The
> first arm on a device pays one ~17.9 MB write (once per install, digest-checked and idempotent
> after). And a generated asset is one more build step that can fail — mitigated by the same
> size-assert discipline `fetchSherpaAar` already uses.
>
> **What it buys, and why the other option loses.** Today the APK carries a **provably dead** copy —
> `extractNativeLibs="false"` leaves the FastRPC loader no real file to open, which is the branch's
> own finding, written into `configureFastRpcLibraryPath`'s KDoc — and the tier works *only*
> because Q10a `adb push`ed one by hand. No packaged path has ever been exercised end to end. The
> zip option would put 17.9 MB into **each** of two ~GB delivery zips, add a third allow-listed
> entry with its own hash into a transaction whose entire contract is *both-or-neither for a model
> pair*, and make a device that imported a 4.0-era zip unable to arm on 4.1. It also leaves the
> dead APK copy in place. This answer retires the OWNER-PENDING zip-packaging question outright.
>
> **Explicitly NOT in scope:** I5's second consequence — the residual ~8.6 MiB of QNN runtime
> shipping to every non-Qualcomm device. That needs a variant- or flavor-scoped dependency, which
> is a real packaging decision and a Play-listing conversation, not this task.
>
> **The blob is never committed.** It is generated into the build directory (outside the repo) and
> `.gitignore` is hardened in the same commit — see the folded proprietary-boundary item.

**Files**
- `app/build.gradle.kts` (edit — the exclude, the extract task, the generated assets srcDir, `sourcePinnedInputs`)
- `app/src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt` (edit — the debris sweep)
- `app/src/main/java/com/whispereverywhere/model/WhisperModelManager.kt` (edit — per-tier, hashed import)
- `app/src/main/java/com/whispereverywhere/npu/NpuAssetImport.kt` (edit)
- `app/src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt` (edit — the skel stage)
- `.gitignore` (edit)
- `app/src/test/java/com/whispereverywhere/npu/NpuAssetImportTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/npu/NpuSkelPackagingTest.kt` (new)
- `app/src/test/java/com/whispereverywhere/npu/NpuNativeContractTest.kt` (edit)

**Steps**

1. **Hash-verified import, streamed during the copy — never a second read.** `EntryVerdict.Accept`
   gains `expectedSha256`; `requiredEntriesFor(model)` returns `(bytes, sha256)` per entry, reading
   `model.sha256`/`primaryBytes` and `pairedArtifact.sha256`/`approxBytes`. The inflate loop feeds
   each written buffer into a `MessageDigest` alongside the bounded read, and the digest is checked
   **immediately after the size check, before `accepted += fileName`**, with its own named refusal
   (`NpuAssetImport.wrongDigestRefusal`). A second pass over 776 MB would double the import's I/O for
   nothing. **adb-push stays hash-exempt** — it never enters this path, and the run-book says so.
2. **The import becomes per-tier.** `importNpuAssetPair(tierId, source, onProgress)`;
   `NpuAssetImport.PAIRED_TIER_IDS` is `WhisperCatalog.entries.filter { it.pairedArtifact != null }
   .map { it.id }` (structural, not a literal list); `TIER_ID` stays as `npu`'s constant, so
   `NpuBackendSelector`'s existing pin and `isMelDonorEligible`'s reasoning are untouched. The card's
   "Import model pair…" button passes **its own** tier id; the free-space precheck, the `.prev`
   parking transaction and the rollback are unchanged in shape and now scoped to one tier's two files.
3. **The skel.** In `app/build.gradle.kts`:
   - `packaging.jniLibs.excludes += "**/libQnnHtpV75Skel.so"`, beside the existing QNN excludes and
     with the reason in the comment block that is already there;
   - a `Copy`-style task that resolves the `qnn-runtime` artifact through its configuration, reads
     `jni/arm64-v8a/libQnnHtpV75Skel.so` out of the AAR, **asserts both `17_913_608L` and
     `a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c`** (the same discipline
     `fetchSherpaAar` uses on a hardcoded digest, and the same two values the runtime staging
     checks), and writes it to `layout.buildDirectory/generated/qnnSkel/assets/libQnnHtpV75Skel.so`;
   - `android.sourceSets["main"].assets.srcDir(<that dir>)` and the task ordered before
     `merge*Assets` (the same "order it before the task that actually needs it, not just `preBuild`"
     rule `fetchQnnHeaders` learned).
4. `NpuWhisperBackend.load` stages the skel through `NpuAssetStage` **before `nativeInit`** and after
   the cheap refusals, stage name `skel`:
   `NpuAssetStage.stagedPath(appContext, "libQnnHtpV75Skel.so", SKEL_BYTES, SKEL_SHA256)`, with
   `SKEL_BYTES = 17_913_608L` and
   `SKEL_SHA256 = "a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c"` — **both
   measured for this plan out of `qnn-runtime-2.49.0.aar` in the Gradle cache**, and both asserted a
   second time by the Gradle extract task, so a runtime version bump produces a named build failure
   and a named stage refusal rather than a mystery. A `null` return is a stage refusal like any
   other: the HTP backend would otherwise come up and then fail somewhere less legible.
5. **`.gitignore` hardening (proprietary boundary, folded).** The 4.0 ignore is exact-path only, so
   *"a header copied one directory too high, or a `libQnnHtp.so` extracted from the AAR to sideload,
   would be stageable by `git add -A`"* — and this task extracts exactly such a blob. Add
   `*.so`, `*.dlc`, `*.context` and `app/src/main/cpp/include/Qnn*.h`, and pin all four in
   `NpuNativeContractTest` beside the entry it already guards. The generated assets directory lives
   outside the repo, so this is defence in depth rather than the only line.

**Folded 4.1 items**

- **Q8 M1 + m4** — stale `.part`/`.prev` debris is swept **only from inside a running import**, so a
  process death between the park and the rename leaves `isInstalled` false with the primary parked
  under a `.prev` name: *the tier silently vanishes from the chooser and nothing on screen explains
  why*. One call to `reconcileStagingDebris` from `Application.onCreate`, over every paired tier,
  closes both halves (the `.prev` disappearance and the `.part` orphan that makes the `StatFs`
  precheck count reusable space as unavailable).
- **Q8 M2** — `unreadableRefusal("${t.javaClass.simpleName}: ${t.message}")` interpolates arbitrary
  text into user-visible card copy, and an `IOException` message typically carries the full internal
  `.part` path. Bound it with `SAFE_NAME_CHARS` like every other echo.
- **Q8 M3** — a duplicate allowed entry double-counts `written`, corrupting
  `npu: import ok entries=2 bytes=…` — *the one number the run-book greps for as the success
  landmark* — and the model-lab zips are the next inputs nobody has inspected entry by entry.
  Refuse the duplicate outright; with a digest per entry, a second copy of a name is a repack fault.
- **Q8 M4** — `filesDir` is read inside a `try` catching only `ErrnoException`, so an
  `IllegalStateException` from `getFilesDir()` escapes `Application.onCreate` — against that
  method's own documented promise that losing the NPU tier must never cost the app its launch. Move
  the read above the try. This task is already in that file for the sweep.

**Test (red first)**
- `NpuAssetImportTest` — the digest verdict: a correct name at the correct size with the **wrong
  digest** refuses and names the file; the refusal string carries no path; a duplicate allowed name
  refuses; `requiredEntriesFor` returns two entries with distinct digests for each paired tier;
  `PAIRED_TIER_IDS` is exactly `["npu", "npu-turbo"]` and is derived from the catalog rather than
  written out; the free-space precheck's numbers scale to turbo's 1.07 GB pair (the transient is
  ~2.1 GB when a pair is already installed, and the refusal must name the shortfall).
- `NpuSkelPackagingTest` — source-contract over `app/build.gradle.kts`: `libQnnHtpV75Skel.so` appears
  in `jniLibs.excludes` exactly once; the extract task asserts the literals `17913608` **and**
  `a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c`; the generated directory is
  registered as an assets `srcDir`; the task is ordered against `merge*Assets` and not merely
  `preBuild`; and the same two literals appear in `NpuWhisperBackend.kt`'s staging constants, so the
  build's copy and the runtime's check cannot drift apart. Plus `NpuWhisperBackend` stages the skel
  **before** `nativeInit` (ORDER) and after the companion refusal.
- `NpuNativeContractTest` — the four new `.gitignore` patterns.

**Expected red:** `NpuAssetImportTest > aCorrectlySizedEntryWithTheWrongDigestIsRefused FAILED`.

**Battery:** `:app:assembleDebug`, then inspect the APK — `libQnnHtpV75Skel.so` **absent** from
`lib/arm64-v8a/` and **present** under `assets/` at 17,913,608 B:

```powershell
[System.IO.Compression.ZipFile]::OpenRead($apk).Entries |
  Where-Object FullName -match 'QnnHtpV75Skel' |
  Select-Object FullName, Length
```

Then the full JVM suite.
**Expected delta:** +1 suite, +21 tests.

**Commit**

```
feat(npu): sha256-verified imports, and the skel moves to where it can be opened

Two ~GB-class pairs are about to arrive through a path that verifies only size, on
a route that has never executed on a device once. The digest is now computed
DURING the copy — a second pass over 776 MB would double the import's I/O to learn
what the first pass already knew — and checked before an entry is accepted.

I5, answered: the 17.9 MiB HTP skel leaves jniLibs (where extractNativeLibs=false
makes it provably unopenable) and is re-materialised from the resolved AAR into
assets at build time, then staged into filesDir on first arm — which is already
first on ADSP_LIBRARY_PATH. Same bytes, one copy, reachable, and it retires the
zip-packaging question rather than putting 17.9 MB into each of two ~GB zips and a
non-model entry into a both-or-neither model transaction. The blob is generated
outside the repo and .gitignore is hardened accordingly.

Debris is now swept at launch, not only from inside a later import: a death between
the park and the rename made the tier vanish from the chooser with nothing on
screen to explain it.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task L7 — Per-utterance language on the NPU tiers

Spec decision 6, and the owner's ruling: *start in one language, finish in another.* Explicit
selection stays absolute. **CPU tiers keep the 3.7 latch, untouched.**

**Files**
- `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt` (edit — the new member)
- `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt` (edit)
- `app/src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt` (edit)
- `app/build.gradle.kts` (edit — `LocalWhisperEngine.kt` joins `sourcePinnedInputs`)
- `app/src/test/java/com/whispereverywhere/transcription/PerUtteranceLanguageTest.kt` (new)
- `app/src/test/java/com/whispereverywhere/transcription/WhisperBackendSeamTest.kt` (edit)

**Steps**

1. **A NEW member with a default BODY, never a widened parameter list.** The house precedent is in
   this same interface three times over (`detectedLanguage(ctx) = null`, `transcribeStreaming(…) =
   transcribe(…)`, `lastSegmentStats(ctx) = null`), and 4.0's NEW-C1 measured what the alternative
   costs: a Kotlin default *parameter* value helps callers, not implementors, and would have
   un-overridden **23 overrides across 10 files**.
   ```kotlin
   /** True when this backend detects the language of EVERY segment cheaply enough not to latch. */
   val detectsPerUtterance: Boolean get() = false
   ```
2. `LocalWhisperEngine.runSegment`, the one line at `:366`:
   ```kotlin
   val effectiveLang =
       if (backend.detectsPerUtterance) lang else languagePin.languageFor(lang)
   ```
   and the pin-feeding block at `:450` gains `&& !backend.detectsPerUtterance`. Nothing else moves.
   `languagePin.reset()` at `connect()` stays: a session that falls back mid-life must find the pin
   empty, and it does.
3. **`NpuWhisperBackend.detectsPerUtterance` is LIVE, not a constant:**
   ```kotlin
   override val detectsPerUtterance: Boolean get() = fallbackBackend == null
   ```
   Detection costs ~4.5 ms against a ~405 ms encode on the NPU — about 1% — and it is the same
   `graphExecute` machinery the decode loop already runs. **On the CPU tier it is roughly half of
   `multi`'s steady-state native cost**, which is the entire reason 3.7's latch exists. So the moment
   a stage declines and this backend starts delegating, the answer must flip back to `false` and the
   CPU latch must resume — and a `val` initialised at construction could not do that. This is the
   same field, read the same way, that `transcribe`/`detectedLanguage`/`releaseEverything` already
   branch on, and every read of it is inside `NativeComputeGate`.
4. The diag line already carries the honest provenance per segment: `auto->fr(detected)` when the
   detect pass answered, `auto->de(locale)` and `auto->en(fallback)` when it did not. With no latch,
   **every segment prints its own note**, so a session that changes language mid-utterance is
   visible in the log rather than inferred — which is what makes the owner's ruling checkable.
5. `detectedLanguage(ctx)` keeps returning `resolution.reportable` (never the bare `code`). It is now
   read only by a CPU-delegating session, and `LangResolution.reportable`'s whole argument — that a
   `(locale)` or `(fallback)` guess must not become a session-wide pin — is exactly the invariant the
   delegating case still needs.

**Folded 4.1 item — Q6 M4, the most user-visible on the list.** `NpuWhisperBackend` does not override
`transcribeStreaming` or `lastSegmentStats`, so **after any NPU decline the session silently loses
live previews and cost counters for the rest of its life**, even though `WhisperNativeBackend`
supplies both. §9.2's *"It behaves like any other `WhisperBackend`"* is wrong today and the user
experiences a degradation nothing names. Two delegating overrides, guarded by the same
`fallbackBackend` field: delegate when fallen back, keep the default otherwise (the NPU path has no
streaming callback and no native counters, and forging zeros would be worse than omitting the fields
— which is what `SegmentTiming.line` is already built to do).

**Test (red first)** — `PerUtteranceLanguageTest`, with a fake `WhisperBackend`:
- the default is `false`, so all 23 existing implementors are unchanged (asserted against
  `WhisperNativeBackend` directly);
- a backend reporting `true` receives `lang` **unchanged** for every segment — including `null` —
  and a backend reporting `false` receives the pinned code from segment 2 onwards;
- an **explicit** session language is passed through untouched under both, and never pins under
  either (the ruling's absolute half);
- source-contract on `LocalWhisperEngine.kt`: `languagePin.languageFor(` appears exactly once and
  **inside** the `detectsPerUtterance` conditional (ORDER via `liveOffsets` — a presence assertion
  would be satisfied by a second, unconditional call);
- source-contract on `NpuWhisperBackend.kt`: `detectsPerUtterance` is a `get()` over
  `fallbackBackend`, not a stored `val`; `transcribeStreaming` and `lastSegmentStats` are overridden
  and both delegate through `fallbackBackend`.
- `WhisperBackendSeamTest` gains the interface census: `detectsPerUtterance` has a default body, and
  the count of members without one is unchanged.

**Expected red:** `PerUtteranceLanguageTest > anNpuClassBackendSeesTheSessionLanguageOnEverySegment FAILED`.

**Battery:** full JVM suite.
**Expected delta:** +1 suite, +14 tests.

**Commit**

```
feat(npu): per-utterance language on the NPU tiers; the CPU latch is untouched

Owner ruling: start in one language, finish in another. On the NPU the detect pass
is one graphExecute — ~4.5 ms against a ~405 ms encode — so there is nothing to
amortise and the 3.7 session latch is pure loss. On the CPU it is about half of
multi's steady-state cost, which is why that latch exists and why it stays.

detectsPerUtterance is a NEW member with a default BODY (the house precedent three
times over in this interface); a default PARAMETER would have un-overridden 23
overrides across 10 files, which 4.0 measured. It is LIVE on the NPU backend
(fallbackBackend == null), so a session that declines mid-life re-acquires the CPU
latch instead of paying a detect pass per segment on whisper.cpp forever.

Also: transcribeStreaming and lastSegmentStats now delegate after a fallback. A
declined session has been silently losing live previews and cost counters for the
rest of its life, and nothing named it.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## Task L8 — Routing two npu-class tiers, the delivery zips, and the A/B run-book  ⚠️ **the J10 re-spec** **[DEVICE]**

The J10 pin goes red here, on purpose, and is re-specified with its resolution in the same commit.

**Files**
- `app/src/main/java/com/whispereverywhere/npu/NpuTierStatus.kt` (edit — per-tier)
- `app/src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt` (edit — **`:148`
  calls the one-arg `NpuTierStatus.publish(value)` from `unavailableReason`'s setter and will not
  compile under the per-tier signature**; it becomes `publish(spec.tierId, value)`)
- `app/src/test/java/com/whispereverywhere/npu/NpuDiagTest.kt` (edit — **`:462` and `:514` call the
  one-arg `publish` directly, same compile break**; `:536`/`:542` are `liveLineCount` needles on
  `"NpuTierStatus.publish(value)"` and `"NpuTierStatus.publish("`, so the first needle must be
  re-spelled with the setter's new call text and the second still counts exactly one site)
- `app/src/main/java/com/whispereverywhere/transcription/NpuBackendSelector.kt` (edit)
- `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` (edit)
- `app/src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt` (edit — per-tier note)
- `app/build.gradle.kts` (edit — `versionName`/`versionCode`; `NpuBackendSelector.kt` joins `sourcePinnedInputs`)
- `tools/pack_npu_zip.py` (new)
- `docs/superpowers/sdd/2026-08-29-npu-model-lab/acceptance.md` (new — the run-book + A/B sheet)
- `app/src/test/java/com/whispereverywhere/transcription/NpuBackendWiringTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/npu/NpuTierStatusTest.kt` (new)
- `app/src/test/java/com/whispereverywhere/npu/NpuAssetImportTest.kt` (edit)
- `app/src/test/java/com/whispereverywhere/ReleaseIdentityTest.kt` (edit)

**Steps**

1. **`NpuTierStatus` becomes per-tier.** `reasons: StateFlow<Map<String, String>>`,
   `publish(tierId, reason)`, `reasonFor(tierId)`, `declinedTiers`. Without this, a turbo decline —
   *"init: could not deserialise 740 MB"* — bans `npu` for the rest of the process, which in a lab
   whose purpose is A/B-ing the two is the worst possible coupling. `NpuWhisperBackend` publishes
   under `spec.tierId`. The card renders `reasonFor(model.id)`, so the note appears on the tier it
   is about; `cardNote`'s *"for this session"* wording, corrected to **process** by F3, is unchanged
   and now scoped correctly as well.
2. **`routesToNpu(tierId, offeredNpuTierIds, declinedTiers)`** — the id must be a known npu-class
   tier (`NpuModelSpec.forTier(tierId) != null`), it must be in the offered set, and it must not be
   in the declined set. Still one predicate with two readers, still no literal of its own; the
   service's field is renamed `npuTierIds` to match, so the value carries one name end to end.
3. **`localEngineOnNpu: Boolean` → `localEngineNpuTierId: String?`** in `FloatingBubbleService`, and
   the rebuild guard becomes `routedNpuTierId == localEngineNpuTierId`. A Boolean cannot tell `npu`
   from `npu-turbo`, so today an `npu → npu-turbo` switch would read as *"no change"* and never
   rebuild — the user would keep dictating on the tier they just switched away from, with the card
   showing the other one. **The `null` case is deliberately unchanged for CPU tiers**: every CPU tier
   shares `WhisperNativeBackend`, so a CPU→CPU switch still keeps the cached engine and re-prewarms,
   exactly as it does now.
4. **The J10 re-spec.** `exactlyOneTierIdRoutesToTheNpuBackend` becomes
   `exactlyTheTwoNpuClassTierIdsRoute`, asserting `listOf("npu", "npu-turbo")`.

   > **First, delete the now-redundant `"npu-turbo"` literal from the candidate list**
   > (`NpuBackendWiringTest.kt:222`). The list is
   > `WhisperCatalog.entries.map { it.id } + listOf(null, "", "NPU", "npu ", "npu-turbo", "turbo",
   > "nope")`, and **L5 put `npu-turbo` into the catalog** — so the id now appears twice, `routed`
   > comes back as `["npu", "npu-turbo", "npu-turbo"]`, and the re-specified assertion **cannot
   > pass**. Delete the literal rather than deduplicating: it was there to arm this exact tripwire
   > *while the id did not exist yet*, and now that the catalog supplies it, keeping a hand-written
   > copy beside the catalog's is a second source of truth for the thing the test is measuring. The
   > other six entries stay — `"NPU"`, `"npu "`, `"turbo"`, `""`, `null`, `"nope"` are shapes that
   > are *not* ids and never will be, which is exactly why they must be written out.

   Its message carries the whole argument forward: two ids are safe **because** the arming
   epoch (L1) makes a stale instance's queued `nativeRelease` a no-op and a stale instance's
   `transcribe` a refusal, **and** because the rebuild guard compares ids rather than a Boolean; a
   third id is a new spec, not a widening. The `"ultra"` sentence is kept and sharpened — `ultra` is
   a 128-bin ggml, and now that one NPU tier is *also* 128-bin, "the NPU tier cannot even take a mel
   filterbank from it" needs restating as "it is a ggml, not a context binary".
5. **`tools/pack_npu_zip.py`** — the delivery zips, and it exists because of the two measured facts
   in the asset block. For each paired tier it reads the vendor zip, **strips the top-level directory
   prefix**, **renames turbo's two entries** to the catalog's filenames, writes a zip whose only
   entries are the two bare names, and then **re-verifies its own output** by re-reading it through
   the same allow-list-and-digest logic the app's importer uses: two entries, both allow-listed, both
   at the catalog's exact byte length, both at the catalog's exact sha256. It prints the output zip's
   own sha256 for the owner to publish alongside. `npu`'s zip is regenerated too — the 4.0 zip was
   never checked for the directory prefix, and the owner provisioned by `adb push`.
6. **The run-book and the A/B sheet** (`acceptance.md`). **§1 is the provisioning hazard and it
   comes before the install step**, because it is the one action in the whole session that can
   destroy data and the only one with no undo:
   - **§1 — THE `adb push` ROUTE, AND ITS ONE DESTRUCTIVE HAZARD.** `adb push` is how the owner
     provisioned 4.0 (Q10a; the SAF import has still never run on a device), it is deliberately
     **hash-exempt**, and it **bypasses the import allow-list entirely** — so *nothing in the app can
     stop it landing a file on the wrong name*. The rename that keeps the two pairs apart is
     enforced in the catalog, in `classifyEntry` and in `pack_npu_zip.py`, and **none of those three
     is on this route.** The run-book therefore states the exact recipe and the destination
     filenames, and states why:

     > **The models directory is `filesDir/models`** — `/data/data/com.whispereverywhere/files/models`
     > (`/data/user/0/…` is the same directory on a single-user device). It is **not**
     > `/sdcard/Android/data/…`, which is `getExternalFilesDir` and is read by nothing here. App-
     > private storage is not writable by a plain `adb push` at all; the debuggable-build route is
     > `push` to `/data/local/tmp/` then `run-as … cp`, which is what 4.0 actually executed
     > (`task-Q8-report.md:578-583`, `task-Q9-report.md:519-524`).
     >
     > **Push turbo's binaries under `turbo_encoder_qairt_context.bin` and
     > `turbo_decoder_qairt_context.bin`. Never under their vendor names.** The rename happens at
     > the `cp`, which is the only step on this route that names a destination:
     >
     > ```
     > adb push <extracted>/encoder_qairt_context.bin /data/local/tmp/
     > adb push <extracted>/decoder_qairt_context.bin /data/local/tmp/
     > adb shell run-as com.whispereverywhere mkdir -p files/models
     > adb shell run-as com.whispereverywhere cp /data/local/tmp/encoder_qairt_context.bin files/models/turbo_encoder_qairt_context.bin
     > adb shell run-as com.whispereverywhere cp /data/local/tmp/decoder_qairt_context.bin files/models/turbo_decoder_qairt_context.bin
     > adb shell rm /data/local/tmp/encoder_qairt_context.bin /data/local/tmp/decoder_qairt_context.bin
     > ```
     >
     > **Why the names matter:** the AI Hub zips for *both* families call their binaries
     > `encoder_qairt_context.bin` / `decoder_qairt_context.bin`, and both pairs live in this one
     > directory. A `cp` that keeps the vendor names **overwrites the installed `npu` pair** — 358 MB,
     > hand-provisioned, and the only copy on the device. There is no verification on this route to
     > catch it and no rollback: the encoder is simply gone, `isInstalled(npu)` goes false, and the
     > card disappears from the chooser with nothing on screen to explain why.
     >
     > **The gate, and it is the only check this route has** — four files, four exact lengths:
     >
     > ```
     > adb shell run-as com.whispereverywhere ls -l files/models
     > ```
     >
     > | file | bytes |
     > |---|---|
     > | `encoder_qairt_context.bin` | 132,927,488 |
     > | `decoder_qairt_context.bin` | 225,316,864 |
     > | `turbo_encoder_qairt_context.bin` | 775,831,552 |
     > | `turbo_decoder_qairt_context.bin` | 295,854,080 |
     >
     > **A push does NOT bump `ModelInstallSignal`** — only `notifyModelInstalled()` does, and
     > nothing calls it on this route (4.0's Q8/Q9 reports both say so). Re-enter the chooser or
     > restart the app before reading "the card did not appear" as a gate failure, and check the
     > `npu: offer …` line first.
   - **§2 — install.** `adb install -r` the debug APK (`keystore.properties` is present, so debug is
     already signed with the release key and installs straight over 4.0 — `assembleDebug`, like every
     other task, and for the reason 4.0's Q10b recorded: `release` would put R8 on the JNI surface
     for the first time at the acceptance run);
   - **the census guard's expected first-trip.** It is the first guard a re-exported asset meets and
     `npu-turbo` differs there by construction, so the run-book states the exact line it must NOT
     produce and what it means if it does: `turbo io: differs from expected census — expected 1 in /
     8 out, 768000 B in / 15360000 B out; got …`. A trip here is an asset or a scalar problem, not a
     decoder one, and it is attributable in one glance;
   - the other watch items, per tier: both context binaries deserialise (turbo's decoder blob has
     **never** been run under the 2.49 runtime); the alias guard passes for all 8 cross-KV pairs;
     `mask: … attend code 65535 = 0.0000, blocked code 0 = -100.0000`; the vote line present and
     `OK`; `mel: bins=128 frames=3000 row0=… rowMid=… rowLast=…` with the three sums **distinct and
     non-zero** (`rowMid == rowLast` is the stride signature, and it is the only place the melbank
     asset is proved numerically); `skel` staged, i.e. no FastRPC refusal with nothing hand-pushed;
   - the greps, `-SimpleMatch`: `npu: encode=`, `segment-timing: `, `mel: bins=`, `auto->`,
     `(detected)`, `(locale)`, `(fallback)`;
   - **the A/B script** — one fixed passage, dictated three times per tier (`multi`, `npu`,
     `npu-turbo`), plus one deliberately code-switched utterance (start in English, finish in
     another language) which is the acceptance for L7. Per run the owner records: the transcript,
     the `npu:` line's `encode`/`decode`/`tokens`, `segment-timing:`'s `transcribeMs`, and one
     subjective note. **The measurement is the transcript comparison, not the timing** — timing is
     published for turbo and unmeasured for accuracy, and accuracy is the thing nobody has a number
     for (residual risk 5);
   - **peak RSS on both npu-class tiers.** Turbo is ~1,043 MiB of NPU-side residency against npu's
     ~376 MiB, and I11's no-co-residency rule was written for the smaller one.
7. **Version identity.** `versionName "4.1.0"`, `versionCode 80`, and the `ReleaseIdentityTest` pin
   updated in the same commit — a deliberate identity change, like the census.
8. **Folded 4.1 item — Q9 M3.** The silent rebuild (a tier change away from `npu` with **no**
   decline) is correct behaviour with missing narration, and it is now the *ordinary* path: the A/B
   is a sequence of exactly those switches. One run-book line, and one `WE-DIAG` line at the rebuild
   site naming the from-tier and the to-tier.
9. **Folded: `NpuBackendSelector.kt` is source-pinned by `NpuBackendWiringTest` but is NOT in
   `sourcePinnedInputs`** — a comment-only edit to it leaves the suite UP-TO-DATE and every one of
   those pins passes against stale evidence. Found while writing this plan; it is the same hole
   Q7a measured and I3 named, on the one file that carries the routing decision. Add it.

**Test (red first)**
- `NpuBackendWiringTest`: the re-spec above; `npu-turbo` + offered + not declined routes to the NPU
  arm while `npu` declined routes to CPU **and vice versa** (the independence the per-tier decline
  buys); the construction-site block needle updated to `backendFor`'s new argument list, with all
  five arguments still **named** (a positional call that transposed the two adjacent `Set<String>`s
  would compile and would route a declined tier on a device that never offered it — the same hazard
  the 4.0 needle was written for, now with two sets instead of two Booleans); the rebuild guard
  compares `localEngineNpuTierId` and the comparison sits **above** the teardown (ORDER — a
  permission check after `shutdown()` is not a check).
- `NpuTierStatusTest`: `publish` is per-tier and independent; `declinedTiers` is the key set;
  `reasonFor(null)` and an unknown id answer `null`; **an `@After` resets the process singleton**
  (Q8 M5 asked for this at the same time as F3 and it did not land).
- `NpuAssetImportTest`: `tools/pack_npu_zip.py` names both tiers' four filenames and asserts the
  catalog's four digests as literals; a zip entry carrying a directory prefix is refused by
  `classifyEntry` (the vendor-zip shape, pinned so nobody "helpfully" relaxes the allow-list).
- `ReleaseIdentityTest`: `4.1.0` / `80`.

**Expected red:** `NpuBackendWiringTest > exactlyTheTwoNpuClassTierIdsRoute FAILED` — the pin the 4.0
final review installed, firing exactly when and for exactly the reason it said it would.

**Battery:** `:app:assembleDebug`; the packaging inspection from L6 re-run on the final APK; a
**forced-fresh** full JVM suite against a purged results directory with a clean tree, confirming the
task line is not `UP-TO-DATE`; the source greps; then the owner device session, recorded in
`acceptance.md` and never claimed done by the implementer.
**Expected delta:** +1 suite, +19 tests. (`ReleaseIdentityTest` changes two constants, not a count.)

**Commit**

```
feat(npu): route two npu-class tiers; the delivery zips; the A/B run-book

The J10 pin fired exactly as the 4.0 final review said it would, and it is
re-specified here with its resolution rather than relaxed: the routing set is
exactly {npu, npu-turbo}, and two ids are safe BECAUSE of the arming epoch (L1) and
because the rebuild guard now compares tier ids. A Boolean could not tell the two
apart, so an npu->npu-turbo switch read as "no change" and never rebuilt — the user
would have kept dictating on the tier they had just left.

The decline record becomes per-tier. A turbo failure banning the small tier for the
rest of the process is the worst possible coupling in a branch whose entire purpose
is A/B-ing the two.

The packing script exists because of two facts measured for this plan and absent
from the research: the vendor zips carry a top-level DIRECTORY (which the importer's
allow-list cannot represent), and both families' entries are called
encoder_qairt_context.bin — so importing turbo as downloaded would have overwritten
the owner's 358 MB pair.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01MVWn31XgwtTFfbj5KjkTJT
```

---

## JVM-vs-device test split

| Task | JVM (real tests) | Device / structural |
|---|---|---|
| L1 | — | epoch ORDER pins; the interleaving is first exercised at L8's tier switch |
| L2 | `NpuModelSpec` + `WhisperTokenFamily` arithmetic — **fully JVM, real assertions** | census guard, alias guard, mask codes → L8 |
| L3 | the shipped filterbank's bytes, digest and header; `NpuAssetStage` — **fully JVM** | `initMelOnly` on the 128-bin file → L8's `mel: bins=128` line |
| L4 | the shipped vocab asset vs the shipped base vs `vocab.bin` — **fully JVM** | first turbo transcript → L8 |
| L5 | catalog, cadence, copy, the four census pins — **fully JVM** | the chooser showing two NPU cards → L8 |
| L6 | digest verdicts, refusal strings, the packaging source pins | the SAF import itself → L8 (**never executed on a device, once**) |
| L7 | the pin-bypass truth table with a fake backend | code-switched utterance → L8 |
| L8 | routing truth table, per-tier status, suite aggregation | the A/B session |

Native and Compose surfaces get structural pins, never pretend unit tests. Everything with real
logic — the census arithmetic, the token families, the asset bytes, the import verdicts, the
language policy, backend selection — is a genuine JVM test with real assertions.

---

## 4.0 items folded here, and what is left out of plan

The final verdict triaged **28 items to 4.1**. Deferral without a trigger is a silent discard, so
here is the disposition of every one.

| Task | Item | Folded into |
|---|---|---|
| Q1 | M-3 `nativeProbe` leaves a stale `lastError` | **L2** |
| Q1 | N-1 `tensorRepoint` drops `quantizeParams` | **L2** |
| Q2 | M2 fork `ctx->params` never set | **L3** (fork commit) |
| Q2 | M7 `liveOffsets` bypass | **L3** |
| Q2 | M8 `kNpuMelSamples` / capacity unpinned | **L3** |
| Q2 | M12 `whisper.h` return line narrower than the guard | **L3** (fork commit) |
| Q4 | M1 the 199-prompt off-by-one | **L2** |
| Q4 | M3 "all by name" logged before the self-KV bind | **L2** |
| Q5 | M3 test can throw instead of asserting | **L4** |
| Q5 | M5 `UNICODE_TO_BYTE` → `private` | **L4** |
| Q6 | M1 the cheapest refusal still `dlopen`s | **L1** |
| Q6 | M2 companion check at stage 4 | **L3** |
| Q6 | M3 vacuous anti-widening guard | **L1** |
| Q6 | M4 streaming/stats lost after a fallback | **L7** |
| Q7a | M2 `NPU_ASSET_ZIP_URL` unpinned | **L5** |
| Q7a | M4(ii) `nativeSourceContract` misnamed | **L2** |
| Q7b | M2 the SOC_MODEL loop's three holes | **L5** |
| Q7b | NEW-1 / m3 eager `dlopen` at service start | **L5** |
| Q7b | NEW-5 over-broad `Log.i` pin | **L5** |
| Q8 | M1 + m4 debris swept only inside an import | **L6** |
| Q8 | M2 unbounded text in card copy | **L6** |
| Q8 | M3 duplicate entry double-counts `written` | **L6** |
| Q8 | M4 `filesDir` inside the wrong `try` | **L6** |
| Q8 | M7 orphaned KDoc | **L4** |
| Q9 | M3 the silent rebuild needs a run-book line | **L8** |
| Q10a-D | M1 the `firstLive` ternary | **L2** |
| Q10a-D | M2 absence assertion bypasses `liveOffsets` | **L2** |
| Q10a-D | M3 a wrong measurement in a comment | **L3** |
| Q10a-D | M4 `%d` bypasses `diagToken` | **L4** |
| Q10a-D | open question: `lastPosition = maskLen - 2` | **L2** (with Q4 M1) |
| branch | I3 the WE-NPU sweep (41 sites) | **L2** — the last task to touch `qnn_asr.cpp` |
| branch | I5 the skel packaging question | **L6** — answered |
| branch | m2 Settings subtitle enumerates `pickable` | **L5** |
| branch | proprietary boundary: `*.so`/`*.dlc`/`*.context`/`Qnn*.h` unignored | **L6** |
| — | `NpuBackendSelector.kt` source-pinned but not a test input | **L8** (found writing this plan) |

**Out of plan, and named so they are not lost:**

- **Q8 M9** — `select()` from Settings lands the user on Home. Pre-existing for the download path
  and widened by 4.0 to one tap from every installed card, but it is a navigation decision in a
  file no task here opens. It belongs to whoever next touches that navigation.
- **m1** — `GgmlBackends`' failure latch is now shared by three consumers where its justifying
  argument was written for one. Every known failure mode really is permanent, no task here touches
  that file, and inventing a reason to open it would be worse than recording the risk.
- **I5's second consequence** — ~8.6 MiB of QNN runtime still ships to every non-Qualcomm device.
  A variant- or flavor-scoped dependency and a Play-listing conversation, not a task.
- The **26 permanently-ACCEPTed** items from the final verdict's triage. The record is the
  disposition; nothing to do.

---

## Residual risk this plan does not close

Stated plainly, because a hand-wave here is how a device-only defect becomes a surprise. These are
**in addition to** the 4.0 review's twelve, most of which still stand.

1. **No JVM test executes a single QNN call, and none can.** Everything about turbo's runtime
   behaviour — the 740 MB encoder deserialising under the 2.49 runtime for the first time ever, the
   8-tensor cross-KV alias, the 4-layer ping-pong — is defended by source pins and by load-time
   guards whose first execution is L8.
2. **The 128-bin mel is never computed on the host.** The filterbank's *bytes* are pinned exactly;
   the spectrogram it produces is first seen as three row sums on a device.
3. **`melbank-128.bin` is a truncation of a q5_0 ggml.** The filterbank region is float32 in every
   ggml quantisation, and the header confirms 128×201 — but the plan has not run a 128-bin mel
   through whisper.cpp to compare against a reference. `rowMid != rowLast != 0` at L8 is the whole
   proof.
4. **The SAF import has still never executed on a device.** L6 hardens it and L8 exercises it for the
   first time, with a 1.07 GB pair — the largest input it will ever see — as its first real run.
5. **Turbo's accuracy is unmeasured, and so is npu's.** No WER exists for any w8a16 Whisper variant.
   The A/B produces the owner's judgement on their own speech, which is the right instrument for the
   decision being made and is not a number.
6. **One device, one SoC.** `SM8650-AC` is still in the allow-list on the strength of a naming
   argument. Turbo adds nothing here and removes nothing.
7. **The release variant still has not been built with the tier**, and now there is more of it: an
   assets-staged 17.9 MiB skel, two vocabulary assets and a filterbank. One `assembleRelease` plus an
   APK listing would close it and is not in this plan's acceptance bar.
