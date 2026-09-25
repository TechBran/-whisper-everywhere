# The MediaTek APU tier — design

**Status:** revision 2, 2026-09-24, after a three-judge review of revision 1 (architecture, native/runtime,
delivery). Every fix-before-plan finding is folded in below; the review's own record is in this session's
workflow journal (`wf_cc4f0c46-392`). Owner ruling the same day: *"Build the tier. Yes."*, plus *"a version
checker there to make sure we're hitting the right chip for the driver."* The owner is reading MediaTek's
NeuroPilot Express SDK licence themself; nothing ships to Play before that is accepted.

**What this is:** `npu-turbo` — the app's fastest local tier, large-v3-turbo on an AI chip — on MediaTek's APU,
for the Galaxy Tab S10+/S10 Ultra (MT6989) in this release, and for the Tab S11/S11 Ultra (MT6991) in a later
one once a hosted unit has executed it. Same tier id, same card, same cadence and policies as the Qualcomm
tier; a second vendor behind an engine seam, not a second tier.

**Measured on the owner's tablet (`docs/measurements/2026-09-24-tab-apu-turbo-encoder.md`):** turbo encoder
1,713–1,783 ms per 30 s window, flat over 120 runs; decoder step 22.8–24.3 ms per token; real speech through
both, word-perfect (in the probe's decode mode — see §5 for the app's); PSS 4.8–4.95 GB with the pair
resident (RSS 1.2–3.5 GB); first model's cold create 8.3 s in the probe, of which 5.0 s was LiteRT's
magic-number read through `libneuron_sys_util.mtk.so` — a library the product does not declare, so the product
never waits (P1b's device gate: 3.7–4.0 s for the whole arm, both models; §2.3) — second model 0.9 s. A 20-token
commit ≈ 2.24 s; the S23 was admitted at 2.47 s.

## 1. Goals and non-goals

Goals
- The Tab S10+ class runs `npu-turbo` from the Play build with the bubble, cadence, sentence, speaker, language
  and fallback behaviour unchanged.
- One census, one gate shape, one pack pipeline, one Kotlin policy body. Vendor differences live in an engine
  seam and in per-family data.
- A driver check that admits only the driver the tier was measured on, at the major the bytecode was built
  for, on the chip the family names — decided before any model file is opened, and never on a UI thread.
- Every number the tier depends on is measured on the tablet in the product's own configuration (§6, P1's
  device gate), not only in the probe's.

Non-goals
- `npu` (Whisper Small) on MediaTek. Owner: *"Don't necessarily need small for the tablets."* **This is an
  amendment to the census contract, stated as such:** today every family carries both tiers, and commit
  7494e4a *restored* qcs8550's small row after an attempt to drop it ("hide, do not delete"). A MediaTek family
  is different in kind: no Small was ever built for it, so there is nothing to hide. The two pins that hold
  the cross-product (`WhisperCatalogHelpersTest.kt:1203-1209`, `NpuFleetCensusTest.kt:313-320`) and the pack
  instrument's cross-product become vendor-scoped: a Qualcomm family has both tiers, a MediaTek family has
  turbo only, and the census KDoc records the owner's quote as the reason.
- Phones. MT6989 phones (vivo, OPPO, Xiaomi Dimensity 9300) match the same string and receive the packs; the
  ship gate is the Samsung tablet the tier was measured on. A ROM whose driver fails the check gets the CPU
  tier, as an unsupported Qualcomm part does today.
- Integer quantisation on the APU, chunked or short-context encoders, on-device JIT, a public SAF delivery
  zip for MediaTek (Play only until the licence ruling), MT6991 in this release.

## 2. The pieces

### 2.1 Census: a vendor per family

`NpuSocFamily` (`npu/NpuFleetCensus.kt`) gains

```kotlin
enum class NpuVendor { QUALCOMM, MEDIATEK }
val vendor: NpuVendor
val manufacturers: Set<String>       // the Build.SOC_MANUFACTURER spellings this family admits
val runtime: NpuRuntimeNeeds          // sealed
sealed interface NpuRuntimeNeeds {
    data class Qnn(val htpVersion: Int, val skelAsset: String, val skelBytes: Long, val skelSha256: String) : NpuRuntimeNeeds
    data class LiteRtMediatek(val neuronMajor: Int, val socStamp: String) : NpuRuntimeNeeds   // 8, "mt6989"
}
val tiers: Set<String>                // {"npu","npu-turbo"} for Qualcomm rows, {"npu-turbo"} for MediaTek rows
```

The six Qualcomm families (after workstream A adds `8gen1`) keep `manufacturers = setOf("QTI", "Qualcomm")` on
each row and their skel/htp triples inside `Qnn(...)`. One new row this release:

| id | packGroup | socModels | manufacturers | runtime | tiers | evidence |
|---|---|---|---|---|---|---|
| `mt6989` | `soc_mt6989` | `{"MT6989"}` | `{"Mediatek"}` | `LiteRtMediatek(neuronMajor = 8, socStamp = "mt6989")` | `{npu-turbo}` | Tab S10+ E0 read (`ro.soc.manufacturer=Mediatek`, `ro.soc.model=MT6989`); Play catalog 24 rows "Mediatek MT6989"; t2–t7 on the owner's SM-X828U, Neuron 8.2.26 |

`PackArtifact(npu-turbo, mt6989)` carries the AOT files' exact lengths and sha256 (encoder 1,302,606,488 B,
decoder 584,862,184 B) and a `parts` list (§2.7). `vendorZipBytes` becomes nullable `sourceBytes`: the vendor
zip's length for Qualcomm rows, `null` for LOCAL rows, whose provenance is the compile recipe in
`tools/mtk-apu/` plus a recorded reproducibility check (does `aot_enc_qcio.py` reproduce the sha256 from the
same f32 export? — recorded in the sheet before the row lands). Maintenance rule 2 is amended: "a vendor
package, OR a recorded self-compile whose recipe is in the repo and whose artefacts are held in the private
artefact store" (the MS-02 `~/mtk-whisper/out/pair/` mirrored to a second checksummed location before P2).

Every reader of the fields that move is listed in P2's task (the judges enumerated them):
`NpuPackLayoutTest.kt:54` (the renderer's manufacturer source), `NpuGateTest.kt:271-313`,
`NpuPackMetadata.kt:88,135-139`, `NpuPackLayoutTest.kt:462`, `build_asset_packs.py:511`,
`NpuSkelPackagingTest.kt:144,178,202,260-269,413-431,539,565`, `NpuFleetCensusTest.kt:98-133,176-185,300-305,368`.
The device-group renderer emits **the family's own** manufacturer set, so `device_targeting_config.xml` gains
`<config:device-group name="soc_mt6989"><config:device-selector><config:system-on-chip manufacturer="Mediatek" model="MT6989"/>…`
and Qualcomm groups keep exactly their two selectors; `NpuGate.SUPPORTED_SOC_MANUFACTURERS` survives only as
the derived union for the XML-wide equality at `NpuPackLayoutTest.kt:230-234`.
*Amended at P2-5 (§2.7 "Modules"):* the MediaTek pair's modules are untargeted, so the renderer emits the
device-targeted (Qualcomm) families only and `soc_mt6989` is not in the XML; the row keeps its `packGroup` for
the census and the pack metadata, and the XML's spelling pin reads the rendered families' own union.

### 2.2 Gate: the family decides its manufacturer

`NpuGate.familyFor(socModel, socManufacturer)` asks the row: `family.manufacturers.contains(manufacturer)`.
The gate tests that assert the global set and "every family passes under QTI" become per-vendor
(`NpuGateTest.kt:271-313`). The capability half, `WhisperEverywhereApp.npuCapableDevice`, becomes
vendor-dispatched: Qualcomm keeps its QNN dlopen probe; MediaTek reads the **stored verdict** of §2.3 and never
performs the dlopen itself (the walk holds bionic's loader lock — 169–239 ms at P1's device gate, no longer the
5 s P0 measured, §2.3 — and this value is forced on the onboarding and chooser
paths — `OnboardingModelScreen.kt:89-101`, `WhisperEverywhereApp.kt:244-254`). `offeredNpuTierIds()` and
`pickableFor` are untouched; `fetchableNpuTierIds` and the catalog consult `family.tiers`, so a MediaTek family
offers turbo only.

### 2.3 The driver check (owner ruling)

Why: LiteRT's MediaTek dispatch loads the Neuron driver by trying names in order — `libneuronusdk_adapter.mtk.so`,
then `libneuronusdk_adapter.9.mtk.so` (only if a magic number read from `libneuron_sys_util.mtk.so` says the
ROM is v9-class), then `libneuron_adapter_mgvi.so`, then `<dispatch dir>/libneuron_adapter.so` — and v2.1.1's
loop has no `break` (`neuron_adapter_api.cc` lines 109-116): **the last loadable candidate wins**. The bytecode
restores only on a Neuron runtime of the same major as the compiler that produced it. On the Tab S10+ only the
first name loads and it reports 8.2.26.

**Where the 5 s went — corrected at P1b's device gate (sheet §4b).** P0 (run t7) placed it inside
`dlopen("libneuronusdk_adapter.mtk.so")`, as the adapter's own constructor waiting on an unregistered binder
service, and concluded it was not removable and paid once per process. It was LiteRT's NeuroPilot magic-number
read: while building the candidate list, v2.1.1 dlopens `libneuron_sys_util.mtk.so` and asks it for the magic
number (`GetNeuroPilotMagicNumber`, the `.9` decision), and that call waits 5 s on the `INeuronService` binder
service this ROM never registers. Every P0 build still declared that library — the litert AAR's manifest re-adds it
through the merger, and t7's truncated `nativeloader` line hid it. The product does not declare it, so it never
pays the wait: P1's gate (runs `p1b_litertasr_kv0` / `_kv1`, the merged manifest stripped of it) saw no
`Waiting for service` line, and the whole adapter walk took 169–239 ms. Bionic holds its loader lock for the walk,
so it still runs off every UI thread.

What, therefore:

1. **The probe runs off every UI path — and it is cheap.** `LiteRtAsrNative.nativeProbe(dispatchDir)`
   is started once from `Application.onCreate` on a background thread on MediaTek families (and by the
   service's boot prewarm if it has not run yet), where it fills `npuCapableDevice`'s stored verdict as
   designed. There is no wait for it to hide: the product needs no warm-up for the adapter, the walk is
   169–239 ms (P1's gate), and an arm that finds no probe run walks the adapter itself at that cost. It walks
   LiteRT's candidate list in LiteRT's order — with
   `RTLD_NOW | RTLD_NODELETE` where LiteRT uses `RTLD_LAZY | RTLD_LOCAL`, which on bionic admit exactly the same
   candidates (`RTLD_LAZY` is unsupported there, `RTLD_LOCAL` is the default) — keeps the LAST candidate that
   loads (`RTLD_NODELETE`; the handle is never closed, so
   LiteRT's later dlopen only raises a refcount — t6's 911 ms second create shows a loaded adapter is reused),
   and reads `Neuron_getVersion(NeuronRuntimeVersion*)` (`NeuronAdapter.h:888`) plus
   `Neuron_getDeviceCount` / `NeuronDevice_getName` for the diag line.
2. **Verdict rules.** No candidate loads → `refuse(adapter-missing)`. The winner is not
   `libneuronusdk_adapter.mtk.so` → `refuse(adapter-<name>)`. `major != family.runtime.neuronMajor` →
   `refuse(driver-major-<got>-want-<want>)`. Otherwise `pass`.
3. **The verdict is stored** in preferences keyed by `Build.FINGERPRINT` (name, version, verdict, timestamp)
   and re-probed only after an OTA. `npuCapableDevice` reads the stored verdict; until the first probe
   finishes it reads "unknown", which the chooser treats as not-yet-capable and re-reads when the probe
   completes (a flow, not a lazy val — the lazy `npuCapableDevice` KDoc's "cannot change within a process"
   holds for Qualcomm and is amended for MediaTek).
   *Corrected at P2-2, where this met the code:* `nativeProbe` answers only `""` or `"probe: <reason>"` —
   the driver's name and version are printed natively on its `apu:` line — so the stored record
   (`NpuApuVerdict`, device-local) keeps the fingerprint, the app's versionCode, the family's wanted major,
   the verdict and the timestamp; and it is re-probed when the fingerprint OR the app build OR the major
   changes, because the answer depends on the build too (the adapter declaration and `libLiteRt.so`
   packaging arrive at P2-6, and a refusal stored by a build without them must not be inherited by one
   with them).
   *Corrected again at P2-7, where the P2a review met the code (its L3 and L4):* the key (`NpuApuKey`)
   gains the install's `PackageInfo.lastUpdateTime` — every debug and internal-sharing build of a cycle
   shares one versionCode, and a stale REFUSAL never corrects itself (a stale pass does, at
   `nativeInit`) — so the record is format 2; a refusal that came from an exception inside the probe
   (an out-of-memory, a missing `liblitertasr.so`) is answered for the process and never stored; and the
   walk is guarded against a crash loop: an in-flight marker is committed synchronously before the walk
   and retired with the verdict in one write, and a launch that finds its own key's marker with no
   verdict records `refuse(probe-crashed)` and never walks again under that key (a native crash in the
   adapter walk is uncatchable, and would otherwise re-probe and die on every launch). The settle is one
   per process (`WhisperEverywhereApp.awaitApuDriverVerdict`): `onCreate` publishes a reusable stored
   verdict on Main and starts it on a thread of its own, and the service's boot prewarm awaits it — off
   Main, outside `NativeComputeGate` — before its first read of the gate, so a service BootReceiver
   starts milliseconds after `onCreate` never memoises "unknown" (the review's L1). The `apu: verdict`
   line goes out through native logging (`WhisperNative.diag`), because release builds strip
   `android.util.Log` and a launch that reuses a stored verdict runs no native probe (L5).
4. **The chip check** (the half of the owner's ask the driver version does not answer): at `init`, the model's
   `LiteRtStamp` (`MediaTek` / `mt6989`, read from the file's metadata) must equal `family.runtime.socStamp`;
   the bytecode's compiler stamp is recorded at build time in the pack metadata (§2.7, provenance "host
   adapter 8.2.30, NeuroPilot v8_0_10" — the version string of the host `libneuron_adapter.so` that compiled
   it, read and recorded once) and cross-checked against the census at install/import time, the MediaTek twin
   of today's htp arm (`NpuPackMetadata.crossCheckRefusal`, `:135`). Nothing at capability time reads a pack.
   *Corrected at P2-5, where this met the files:* the `LiteRtStamp` carries the vendor and chip only
   (`MediaTek` / `mt6989`); the compiler's version is in the bytecode itself, whose DLA trailer (the last
   bytes of each file) is `{"Compiler": "adapter 8.2.30", "Neuron SHA1": "76b05e138c"}`. So the pack build
   READS both from every file at every build — the stamp against the row's `socStamp`, the compiler against
   a pinned `adapter 8.2.30` whose major must equal the row's `neuronMajor` — and writes them into metadata
   version 2 (`socStamp: "mt6989"`, `compiler: "adapter 8.2.30"`, `neuronMajor: 8`); the install-time twin
   compares `socStamp` and `neuronMajor` with the row, and `compiler` is recorded, not compared (the census
   has no compiler field, and the digests already name the bytes).
5. The diag line, on the same channel as the QNN probe:
   `apu: driver=libneuronusdk_adapter.mtk.so 8.2.26 want=8 device=<name> stamp=mt6989 pass`.
   *Corrected at P3a, where this met a release build:* the "same channel" held on debug builds
   only. The `npu: offer` line was `android.util.Log.i`, which `proguard-rules.pro` strips from
   every release build, so a Play build never printed it — on any vendor — while the native `apu:`
   lines (and P2-7's `apu: verdict` line, through `WhisperNative.diag`) did. The offer line goes
   out through `WhisperNative.diag` now, one route, under the same `WE-DIAG` tag, so the ship sheet
   reads `soc=MT6989:pass` and the `apu:` lines side by side on the internal track.

A refusal is visible in logcat and on the offer line (`probe=fail:<reason>`); the tier is then simply not
offered — there is no card to say why (`NpuTierStatus` is fed only by a backend that exists), which is the
QNN behaviour today, stated rather than promised otherwise. *Implemented at P2-7 (the P2a review's L6),
with a third word the code needed:* `probe=unknown` while the verdict has not landed — which the first
cut printed as `probe=fail` — and the offer line goes out once more when the verdict lands after an
`unknown` one, so the install-epoch latch cannot leave `unknown` as the only record.

The manifest declares `libneuronusdk_adapter.mtk.so` and nothing else from the MediaTek set — P0(c) (runs
t10/t11, sheet §4c) showed the adapter loads its own `apuware` dependencies from the system namespace with
only that one declaration, the dispatch loads from `filesDir/litert_dispatch/`, no plugin is needed, and the
pair's output is identical to the reference. Not `libneuron_sys_util.mtk.so`, not
`libneuronusdk_adapter.9.mtk.so`, not `libneuron_adapter_mgvi.so`. A pin reads the MERGED release manifest
(not the source one): P0(c) also showed the litert 2.1.1 AAR's own manifest re-adding `libneuron_sys_util`
through the merger — and that re-added declaration is what cost every P0 run its 5 s (above), so the pin
guards the tier's cold-arm time as well as its declaration set. The product takes `libLiteRt.so` out of that
AAR without depending on it (§2.6), so no foreign manifest is merged — and the pin is what proves it stays that
way.

### 2.4 The engine seam (narrow, and landed first as a Qualcomm-only refactor)

`NpuWhisperBackend` keeps the policy body. The seam is as narrow as the two engines allow:

```kotlin
interface NpuAsrEngine {
    fun probe(dispatchOrLibDir: String): String                       // "" = pass, else the refusal text
    fun prepare(appContext: Context, family: NpuSocFamily): Refusal?  // vendor staging: QNN skels; LiteRT dispatch
    fun init(spec: NpuModelSpec, files: NpuEngineFiles, dirs: NpuEngineDirs): Refusal?
    fun encode(melF32: ByteBuffer): Refusal?                          // direct buffer; QNN quantises inside
    fun decodeSegment(...): Int                                       // nativeDecodeSegment's contract, unchanged
    fun detectLanguage(): Int
    fun epoch(): Long; fun release(epoch: Long); fun lastError(): String; fun setDiag(on: Boolean)
}
data class Refusal(val stage: NpuStage, val detail: String)          // NpuStage is a closed enum: the stage
                                                                     // names NpuDiagTest derives today, plus "dispatch"
```

`QnnAsrEngine` owns what is QNN-shaped in the backend today: the skel stage (`NpuWhisperBackend.kt:446-455`),
the quant buffer (`:525`), `melToU16` and the D2 `melProbe` (`:633-671`), `nativeInputQuant`. `LiteRtAsrEngine`
owns the dispatch staging and the float mel. The stage names come from the enum, so `NpuDiagTest`'s regex
derivation (`:853-878`) is re-pointed at the enum and the engine files, and the order pins in
`NpuSkelPackagingTest.kt:505-585` / `NpuDiagTest.kt:314-424` move to `QnnAsrEngine`. Every such pin is named in
P1a's task list; the new files join `sourcePinnedInputs` in the same commits.

**P1a lands this seam alone, over today's flat family fields, with `qnn_asr.cpp` byte-identical, and is
device-checked on the Fold6 and S23 through the internal track before anything MediaTek touches the tree.**

### 2.5 `liblitertasr.so` — the native engine

A third `add_library` beside `whisper_jni` and `qnnasr`, built against the LiteRT **2.1.1** C headers
(vendored `third_party/litert-2.1.1/`), linking only `log` and `dl`, reaching every runtime entry point through
`dlopen`/`dlsym` of `libLiteRt.so` (all needed symbols are exported at `VERS_1.0`, verified by the delivery
judge: `LiteRtCreateEnvironment`, `LiteRtCreateModelFromFile`, `LiteRtCreateOptions`,
`LiteRtSetOptionsHardwareAccelerators`, `LiteRtCreateCompiledModel`, `LiteRtRunCompiledModel`,
`LiteRtMediatekOptionsCreate`, `LiteRtMediatekOptionsSetPerformanceMode`, plus the buffer-requirements and
managed-buffer family).

**Buffers — corrected twice.** The v2.1.1 MediaTek dispatch accepts only AHardwareBuffer and DMA-BUF tensor
buffers (it imports `LiteRtGetTensorBufferAhwb` / `LiteRtGetTensorBufferDmaBufBuffer` and binds only
`NeuronMemory_createFromAHardwareBuffer` / `createFromFd`; host memory is "Unsupported buffer type"), and it
refuses any buffer whose layout carries strides ("Tensor strides are not supported") — while the requirements it
reports always carry strides (computed from its padded dimensions). `LiteRtCreateManagedTensorBufferFromRequirements`
copies those strides into the buffer (2.1.1, `c/litert_tensor_buffer.cc`), so it is NOT used (P1b review): every
buffer's type and size are read from `LiteRtGetCompiledModel{Input,Output}BufferRequirements` and the buffer is
made with `LiteRtCreateManagedTensorBuffer` from the tensor's own unstrided type — exactly what the Kotlin API did
on every tablet run (`CompiledModel::CreateBufferImpl`). The 27 buffers a `DISPATCH_OP` reads or writes are AHWB,
else DMA-BUF; `input_ids` and `position_ids`, read only by the decoder's CPU-side embedding lookups, are host
memory. Each shared cross-KV buffer's type and size come from `LiteRtJoinTensorBufferRequirements(encoder output,
decoder input)`, each self-KV set's from the join of its `_in` and `_out` requirements (the join takes the larger
size and refuses unequal strides); mel, ids, mask and logits are reached through `LiteRtLockTensorBuffer` /
`Unlock`. **The self-KV advance is measured in P1's device gate before any per-token number is quoted**, and
both ways are built (`nativeInit`'s `selfKvStrategy`): `0`, two sets swapping roles by re-binding — no byte
moves, but the v2.1.1 dispatch kernel re-registers every re-bound buffer at the next run
(`dispatch_delegate_kernel.cc`), 16 per step, so it is not the free pointer swap of the QNN engine's ping-pong
(`bindSelfKvLocked`, `qnn_asr.cpp:1883-1890`); and `1`, one input set with the step's 8 cache tensors (~8 MB)
copied back into it natively and no binding ever changed. The probe's 22.8 ms included one Kotlin copy per step;
the faster strategy becomes the default — and P1's gate chose `1`, the copy (runs `p1b2_litertasr_kv1` / `_kv0`,
sheet §6): faster and steadier, with `0` kept as the measured alternative (`kSelfKvDefault` in `litert_asr.cpp`).
Options: the encoder on the NPU alone (it is one `DISPATCH_OP`; a refusal is an error); the decoder on NPU + CPU,
because its two embedding lookups and their guards stay on the CPU and 2.1.1's `LiteRtCreateCompiledModel`
refuses a partly delegated model without CPU in the set ("Some ops are not accelerated") — the Kotlin API had
added CPU to a lone NPU silently on every tablet run. CPU in the decoder's set can run only those ops: the layers
exist only as the `DISPATCH_OP`'s bytecode, and an unclaimed `DISPATCH_OP` fails the run (LiteRT's stub kernel).
`init` then times the decoder's first step on zeroed caches and refuses one over 250 ms (an APU step is 19–24 ms).
The MediaTek performance mode is passed through from the real enum (`LiteRtMediatekNeuronAdapterPerformanceMode`:
`PreferLowPower`, `PreferFastSingleAnswer`, `PreferSustainedSpeed`, `PreferTurboBoost`) but is **inert on 2.1.1
with AOT files**: the dispatch never reads it, and its bytecode load hard-codes `NEURON_PRIORITY_HIGH`,
`NEURON_PREFER_SUSTAINED_SPEED` and an execution boost hint of 100 — so `PreferSustainedSpeed` vs default is not
measurable on this runtime (every run is already the former). Kept for a runtime that reads it.

**Output identity.** The exported outputs are positional (`output_0..N`); the encoder's eight cross-KV tensors
are matched to the decoder's inputs by export order only. The export names them (`k_cache_cross_i`, `logits`,
`k/v_cache_self_i_out`) where litert-torch allows, and either way the order is pinned in the pack metadata and
asserted at `init` with `LiteRtGetSignatureOutputName`; every shared pair's requirement join must succeed
with equal strides (the buffer takes the larger size) or `init` refuses (the LiteRT twin of the QNN C7 alias
guard).

**The decode loop.** Not a template over `qnn_asr.cpp`. That loop is ufixed16 through and through
(`logitsScaleLocked` returns 0 for non-UFIXED16 tensors and "0 ⇒ no probability gates this segment",
`kLogitFloor = 0` stands for the mask's −inf, `suppressThenArgmax/Sample`, `logSumExp`, `noSpeechProbability`
and `band_scan.h` all take `uint16_t*`), and it runs on every shipping Qualcomm family while workstream A
validates 2.50 on the same code. So `litert_asr.cpp` carries **its own float loop** with the same
observable contract (the 3-token prompt, the always-on and begin masks, timestamp tokens emitted, the
temperature ladder, avg_logprob and p(nospeech) computed in float with floor = −inf and scale = 1.0 — never
0, which would switch the 4.3.2 silence fix off — a per-step non-finite check that fails the step into the
loud fallback, the 199-slot window, `NpuSentences`' timestamp bounds). Only pure helpers are shared
(`band_scan.h` gets a float instantiation; the precedent is that header). Equivalence is proven by a
closed-loop host differential test (§5), and the two loops converge into one only after both are
device-proven.

### 2.6 Runtime packaging

- `libLiteRt.so` (2.1.1, 5,104,832 B, sha256 `6ddc1b3df38f3f0e039558c023f3bd9ec2f7bec55b67cfd6be4131130481a5d5`,
  from `com.google.ai.edge.litert:litert:2.1.1`, AAR 7,569,479 B; 16 KB-aligned; `DT_NEEDED` only system
  libraries) ships in `lib/arm64-v8a/`. Mechanism: a `litertRuntime` configuration (`isTransitive = false`)
  extracts it into a generated **jniLibs** directory registered with `jniLibs.srcDir` and ordered before
  `merge*JniLibFolders` — not the assets precedent `qnnSkelSource` uses (`build.gradle.kts:289-293, 1049-1055`
  record why task order bit once already). Pinned by sha256 and by "the two coordinates agree".
- `libLiteRtDispatch_MediaTek.so` (v2.1.1, 409,728 B, sha256 `9e963c56a65b6146b0e94aed82dd0f73dbaee6805fc6ae090580565b57680706`)
  ships as an APK asset and is staged into `filesDir/litert_dispatch/` — a directory that must contain
  exactly that one file, because LiteRT scans it and the adapter loader's fourth candidate is
  `<that dir>/libneuron_adapter.so`. `NpuAssetStage` gets a subdirectory-aware overload whose `.part` and
  `.staged` marker files live OUTSIDE the scanned directory (`filesDir/litert_dispatch.staged/`), with its own
  marker test; staging is serialised with `load` (it is not concurrency-guarded, `NpuAssetStage.kt:105-108`).
- No compiler plugin, no OpenCL accelerator: the CPU kernels for the 24 leftover ops are inside `libLiteRt.so`.
  The "no plugin, dispatch from filesDir" configuration is exercised in P0(c) before P1 (the probe set
  `CompilerPluginLibraryDir` and loaded from `nativeLibraryDir`).
- Lifecycle: the LiteRT environment, the dispatch and the adapter handle are created once per process and
  never destroyed; `release` frees only the compiled models and their buffers. Re-arms after a trim therefore
  pay the two restores (1.4 s + 0.45 s at P1's gate) and never re-load the adapter.
- Runtime pinned at 2.1.1 until Google publishes a MediaTek dispatch for a newer LiteRT; building the dispatch
  from source is the upgrade path, out of scope.

### 2.7 Packs and delivery — a real section

The pair is 1.88 GB; a Play asset pack holds 1.5 GB. Today's machinery is single-pack end to end
(`NpuPackFetch.PACK_BY_TIER`, `NpuPackController.activePackName`, `installFromPack(tierId, family, assetsPath)`,
`removePack`, a metadata parser demanding exactly two entries, `verifyNpuPacks` expecting both names in every
variant, `NpuFleetCensusTest.kt:330-342` pinning per-tier delivery names). So:

- **Names:** the MediaTek files land under the tier's catalog names, `turbo_encoder_qairt_context.bin` and
  `turbo_decoder_qairt_context.bin`. A device only ever holds one family; the engine picks its loader by
  `family.vendor`, and `LiteRtCreateModelFromFile` does not care about the extension. Zero call sites change
  and the name pin holds.
- **Modules:** `npu_turbo` stays Qualcomm-only. Two new on-demand modules, `npu_turbo_mtk_enc` and
  `npu_turbo_mtk_dec`, carry the encoder and the decoder for MediaTek device groups (`soc_mt6989` now,
  `soc_mt6991` later). Every module keeps one uniform layout rule (one variant dir per group with its files
  and, in the encoder module only, `metadata.json`).
  *Amended at P2-5, where this met bundletool:* bundletool 1.18.1 (what AGP 8.13.2 resolves) runs
  `DeviceGroupParityValidator` — "all modules with device group targeting must support the same set of
  groups", read from each module's `#group_` folders — so MediaTek modules carrying `{soc_mt6989, other}`
  beside `npu_small`/`npu_turbo`'s six Qualcomm groups would fail `bundleRelease`. The MediaTek pair
  therefore ships in two **untargeted** modules, named per **family** because an untargeted module cannot
  hold a per-group variant: `npu_turbo_mt6989_enc` and `npu_turbo_mt6989_dec` (a later MT6991 gets its own
  two). Each is one payload directory named after the pack (`assets/<module>/`: the part's entry,
  `metadata.json` in the encoder module, the tracked `.gitkeep` — *corrected at P3a, where the
  packaging probe met the bundle (sheet §8):* the `.gitkeep` shipped as a zero-byte asset, because
  AGP 8.13.2's asset-pack plugin zips `src/main/assets` whole, with no filter and no DSL to add one
  (`AssetPackExtension` is `packName` + `dynamicDelivery`), so the marker left the packaged tree:
  the module `.gitignore` walls `src/main/assets/` whole and a delivered pack holds exactly its
  payload files), with no `#group_` folder, so the validator
  skips them and the census gate alone decides who fetches them (`packsFor` names them only for the mt6989
  row). `soc_mt6989` leaves `device_targeting_config.xml` (§2.1 below said it joined): with untargeted
  modules nothing uses it, and Play's acceptance of a declared-but-unused group is undocumented. The Qualcomm
  rule is unchanged; the untargeted one is a second rule beside it (`verifyNpuPacks`, `NpuPackLayoutTest`,
  `build_asset_packs.py`).
- **Parts:** `PackArtifact` gets `parts: List<PackPart>` (pack name + the entries it carries). Qualcomm rows
  have one part — today's behaviour, unchanged. The pure pack machine aggregates across parts: the worst status
  wins, bytes are summed, cellular confirmation and cancel apply to all parts, install begins only when EVERY
  part is `COMPLETED`, `metadata.json` sits in part 1 and lists both entries (so `parse` stays "exactly two"),
  all parts are removed strictly after the finalise, and one part failing after the other delivered is a
  retry of the failed part, never a partial install. Re-attach after process death re-queries every part.
  This is the F5 controller generalised plus its pins — sized as such in P2, not as a census detail.
- **Metadata:** Qualcomm packs stay byte-identical at version 1. Version 2 adds `neuronMajor`, `socStamp` and
  `compiler` and makes `htpVersion` optional; it is written for MediaTek rows only; readers accept 1 and 2.
  `vendor` is not stored — it is derived from the census row.
- **Build:** `tools/build_asset_packs.py` gains a `LOCAL` family source (artefact dir, digests+lengths pinned
  in `CENSUS`, the compiler stamp read from the file, a LOCAL IO gate that reads the tflite signatures and
  checks names, shapes and dtypes against `NpuModelSpec.TURBO` — the "same model" proof the vendor rows get
  from their metadata). `verifyNpuPacks` learns the two modules.
- **Size:** +1.88 GB for `mt6989` (AAB ≈ 9.7 GB after A's ≈ 7.8 GB); per-pack ≤ 1.5 GB and the 30 GB
  on-demand budget hold. A Tab S10+ downloads 1.88 GB.

### 2.8 Card, copy, status

The turbo copy is not vendor-neutral today: the badge is the 8gen3 pair's "1072 MB" (`ModelTierCopy.kt:411-413`),
the body says "phone" (`:441-442`), and "the fastest on this device" is false on the Tab S10+, where the CPU
small-q8 commit is 1,217 ms against the APU's 2.24 s (`2026-09-17-tab-cpu-ladder.md:12`). So: the badge takes
the family's census pair bytes; "phone" becomes device-neutral; the speed claim on MediaTek families needs an
**owner ruling** on wording (the claim rules forbid unscoped superlatives) — proposed: "the most accurate model
this device can run, on its AI chip". Onboarding's size line reads the family's pair bytes too
(`OnboardingFlowScreen.kt:995`). HowToGuide names the Galaxy Tab S10+/S10 Ultra.

*Implemented at P3a, and corrected where this met the code.* The badge was the 8gen3 pair's
**"981 MB"** by then (4.15's v0.63.0 pair), not "1072 MB". Both chooser surfaces now render
`ModelTierCopy.forIdOn(id, family)`: every gated card's badge is the family's census pair by the
badge rule (SI MB, truncated — mt6989 1,302,606,488 + 584,862,184 = 1,887,468,672 B, "1887 MB"),
and a MediaTek family reads its own turbo card. The owner's wording for a MediaTek speed claim is
still PENDING, so that card carries **no speed claim**: headline "Best AI-chip accuracy", body
"The most accurate model this device can run, on its AI chip." (this proposal, accuracy only, with
the tablet's ladder sheet and the APU sheet §6 cited beside it), and a `TODO(owner)` pin fails if
the Qualcomm "the fastest on this device" ever renders on a MediaTek row. The Qualcomm body says
"this device's AI chip" (the census has tablets on both vendors). The onboarding size line states
the family's pair, approximately: "about 1.9 GB" on mt6989. Two surfaces this section did not list
made claims false on a MediaTek row and were fixed with it: the decline note's "It is slower" (the
tablet's CPU fallbacks commit faster than its APU turbo — the speed half now renders on Qualcomm
rows only) and the Settings picker's small-pair import panel (338 MB, "much faster than the CPU", a
release-page zip — offered now only where the family offers the tier it imports). **HowToGuide was
not changed:** it names no device on any branch — its sections are device-neutral — so there is no
list to add the tablets beside, and naming devices there would be a new claim for the owner to
rule on.

### 2.9 Cadence, calibration, the ring

Per-commit cost on the tablet = 1,783 + 22.8 × tokens ms (t6), re-measured in-app at P3. The cold-arm budget,
from the measured parts — corrected at P1b's device gate: P0's 8.3 s first create held a 5.0 s wait the product
never pays (§2.3) — is `nativeInit` 3.7–4.0 s end to end (both opens, both restores, the buffers and the APU
check), + the 169–239 ms walk when no probe has run, + 1.6 s (`load`'s non-native stages,
`NpuWhisperBackend.kt:471-473`) ≈ 5.5–5.8 s — against `StartupRing.CAPACITY_MS = 6,000`, inside it by a margin
too thin to lean on. (P0's figures were ≈ 10.8 s worst case and ≈ 5.8 s with the adapter warm.) So: the ring
stays sized per family (12 s = 384 KB for MediaTek rows) until P3's in-app cold-arm number decides it, the probe
still runs at process start for the stored verdict (§2.3) — not to hide a wait — and the environment survives
trims (§2.6).
The Tab sheet records cold-tap audio loss.

## 3. A commit, end to end

1. The service's mel path (whisper.cpp's, with the bundled 128-bin bank — unchanged) produces the float mel.
2. `NpuWhisperBackend.transcribe` → `engine.encode(mel)`: the mel is written under lock into the encoder's
   managed input buffer; `LiteRtRunCompiledModel(encoder)` fills the eight shared cross-KV buffers (1.78 s).
3. `engine.decodeSegment(...)`: the float loop runs the decoder step per token, reading logits under lock,
   returning the token/timestamp structures the QNN engine returns. *Corrected at P2-7, where this met the
   engine:* the self-KV cache advances by ONE set with the step's output copied back into it
   (`selfKvStrategy = 1`, which `LiteRtAsrEngine` passes as a literal — P1's device gate chose it over
   alternating two sets, §2.5), not by alternating.
4. Everything after — sentence slots, speakers, the bubble — is the code that runs today.

## 4. Failure modes

| Failure | Where it is caught | What the user sees |
|---|---|---|
| Driver absent / wrong adapter / wrong major | the stored probe verdict (§2.3), before any pack is fetched | tier not offered; `apu:` line and `probe=fail:<reason>` in the log |
| Wrong chip for the file (`LiteRtStamp` ≠ family) | `init` | loud CPU fallback, diag line |
| Bytecode restore refused by the runtime | `init` (`LiteRtCreateCompiledModel` error) | loud CPU fallback, diag line, the tier-status card |
| A pack part missing or a digest mismatch | pack machine / install / SAF import | tier not installed; existing copy untouched |
| Non-finite logits at a step | the float loop | that segment falls back loudly; the session continues |
| lmkd kills the process (PSS ≈ 4.9 GB resident + the app) | not catchable: a SIGKILL | the service restarts; the Tab sheet measures whether it happens in a 30-min session beside a foreground app, and the PSS ceiling is a ship-gate number |
| Cold tap before the tier is armed | the ring | the cold arm is ≈ 5.5–5.8 s (P1's gate: `nativeInit` 3.7–4.0 s + the walk + `load`'s 1.6 s, §2.9) against the default 6 s ring — no speech lost, by a thin margin; none with the per-family ring. (P0's "up to ~4.8 s lost" counted a 5 s adapter wait the product never pays, §2.3.) |

## 5. Testing and acceptance

- **The reference run is redone in the app's decode mode** (P0(d)): the 3-token prompt `[SOT, <|en|>, TRANSCRIBE]`,
  the always-on mask (`WhisperTokens.BASE_SUPPRESS` + the six large-v3 control ids + `<|notimestamps|>`), the
  begin mask `[220, EOT]` at the first generated step, timestamps allowed — and the probe exports per-step
  top-k logits. Text equality plus monotonic, paired timestamps is the acceptance; t6's 4-token,
  no-timestamps result is the probe's mode, not the app's.
- **Host differential test** for the float loop: the f32 decoder `.tflite` in the LiteRT interpreter on the
  MS-02, driven by the same loop logic, against the tablet's per-step top-k trace (closed-loop, same prompt,
  same masks), plus a recorded QNN uint16 trace for the contract's other instantiation.
- **JVM pins:** census (vendor per row, `tiers`, `runtime` sealed, the MediaTek row's digests, the LOCAL
  provenance), gate (per-family manufacturers; `Mediatek`/`MT6989` → mt6989; other spellings refused until a
  device reports them), layout (the new group; the two MediaTek modules; byte-identical XML), metadata v1/v2,
  parts aggregation (the pure machine, every combination of part states), selector (vendor → engine),
  packaging (libLiteRt in lib/, the dispatch asset digest, the MERGED manifest's native-library set),
  release identity, `sourcePinnedInputs` membership for every new file a pin reads.
- **Qualcomm regression gate for P1a and P1b:** Fold6 and S23 Ultra on the internal track — canary/jfk
  transcripts and the `nsp/lp/tokens` diag lines equal to a pre-refactor capture; AI Hub matrix unchanged.
- **P1 device gate (the product configuration, before P2):** a probe-app mode loads `liblitertasr.so` with the
  product's shape — dispatch from a `filesDir` directory, no compiler plugin, only the product's declared
  libraries, managed buffers via the C API, the app's prompt and masks — and reports create, encode, per-step
  time (with and without the second buffer set), transcripts and PSS.
- **Ship gate (owner, Tab S10+ via the internal track):** the offer line `soc=MT6989:pass`, the `apu:` driver
  line, cold-arm time with and without a warm adapter, cold-tap audio loss, per-commit encode+decode against
  the P1 gate, canary + jfk equal to the P0(d) reference with paired timestamps, a 30-minute session with
  thermal status and PSS beside a foreground app, and no lmkd kill.

## 6. Phases and order

Order of the tree: `feat/bubble-tab-mute` (110) merges to main → workstream A `feat/qnn-250-8gen1` (111) →
this tier on `feat/mediatek-apu-tier` (112+), each with its `ReleaseIdentityTest` paragraph. For the Tab test
loop, Play internal app sharing (no versionCode spent) is checked as an alternative to burning codes.

- **P0 — probe runs on the tablet (no app code) — DONE 2026-09-24:** (a) the 5 s wait — read at P0 as the
  adapter's own (run t7); **corrected at P1b's device gate**: it was LiteRT's magic-number read through
  `libneuron_sys_util.mtk.so`, which every P0 build still declared through the AAR's manifest merge. With the
  library really undeclared, as in the product, there is no wait and the whole adapter walk is 169–239 ms (runs
  `p1b_litertasr_kv0` / `_kv1`; §2.3, sheet §4b). (b) `PreferSustainedSpeed` vs default — not reachable from the probe's Kotlin API, and **not
  measurable on LiteRT 2.1.1 at all** (P1b review): with AOT files the dispatch never reads the mode and
  hard-codes `NEURON_PREFER_SUSTAINED_SPEED`, so every run so far was already in it; P1b passes the value
  through, inert. (c) the product's loading shape passes: dispatch from `files/litert_dispatch/`, no plugin, one
  declaration; encoder 1,725 ms; the pair's ids identical to the reference (runs t10/t11, sheet §4c). (d) the
  app-mode reference: word-perfect with paired, monotonic timestamps, 21 ms per step (t8, sheet §5b), and the
  per-step top-k trace for the host test (t8b, `t8b_appmode_topk_e2eqc.steps.jsonl`, 31 steps, archived on
  the PC under `~/.androidbuild/probe-logs/tab-apu-2026-09-24/`).
- **P1a — the Kotlin seam, Qualcomm-only (2 days, after A merges):** `NpuAsrEngine`, `QnnAsrEngine`,
  `Refusal`/`NpuStage`, the backend on the seam, every pin re-pointed, `qnn_asr.cpp` untouched; the Qualcomm
  regression gate.
- **P1b — the LiteRT engine (3–4 days):** `litert_asr.cpp` + `LiteRtAsrNative` + `LiteRtAsrEngine`, managed
  buffers, the float loop, the host differential test, the probe-app device gate.
- **P2 — census, gate, packs (3 days):** §2.1–2.3 (the census reshape as its own no-behaviour-change commit
  first), §2.6–2.7 including the parts machine, `build_asset_packs.py` LOCAL source + IO gate, the two
  modules, metadata v2, the pins; AAB built and size-verified on the MS-02.
- **P3 — ship (1 day + owner sessions):** copy/status (§2.8, with the owner's wording), the per-family ring,
  version bump, internal track, the Tab S10+ sheet.
- **Later:** MT6991 as a census edit plus a variant, after a Samsung RTL run with the probe and the MT6991 pair
  records its sizes, digests, compiler stamp and the Tab S11's Neuron version.

## 7. Open questions

1. Spelling: the tablet and the Play catalog say `Mediatek`; LiteRT's enum says `MediaTek`. The row admits
   only what a device reports.
2. Whether Google publishes a MediaTek dispatch for a newer LiteRT (licence talks, #9482).
3. The owner's wording for the speed claim on MediaTek families (§2.8).
4. ~~Whether the `apuware` dependency libraries must be declared~~ — answered by P0(c): they need not be.
