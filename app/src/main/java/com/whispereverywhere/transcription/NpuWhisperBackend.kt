package com.whispereverywhere.transcription

import android.content.Context
import android.os.SystemClock
import com.whispereverywhere.npu.NpuAssetStage
import com.whispereverywhere.npu.NpuDecodePolicy
import com.whispereverywhere.npu.NpuDecodeStats
import com.whispereverywhere.npu.NpuDiag
import com.whispereverywhere.npu.NpuGate
import com.whispereverywhere.npu.NpuQuantize
import com.whispereverywhere.npu.NpuTierStatus
import com.whispereverywhere.npu.QnnAsrNative
import com.whispereverywhere.npu.NpuModelSpec
import com.whispereverywhere.npu.NpuSocFamily
import com.whispereverywhere.npu.WhisperBpeDecoder
import com.whispereverywhere.whisper.GgmlBackends
import com.whispereverywhere.whisper.WhisperNative
import java.nio.ByteBuffer
import java.util.Locale

/**
 * The 4.0 NPU tier behind the ordinary [WhisperBackend] seam: mel on the CPU, encoder and decoder
 * on the Hexagon, and one CPU-tier fallback that is never silent.
 *
 * ```
 * load(encoder, decoder)   companion? -> mel (64 KB) -> vocab -> skel (the family's own, once)
 *                          -> nativeInit (376 MiB) -> nativeEpoch
 * transcribe(samples)      nativeEpoch (is this still my session?) -> pcmToMel -> nativeInputQuant
 *                          -> melToU16 -> nativeEncode -> nativeDetectLanguage
 *                          -> nativeDecodeSegment -> WhisperBpeDecoder
 * release(ctx)             nativeRelease(armedEpoch) + WhisperNative.free
 * ```
 *
 * ### The 64 KB that must never become 190 MB
 *
 * The spectrogram is whisper.cpp's, because the spec allows exactly one mel in this app and a
 * second implementation would be free to drift from the accuracy the CPU and GPU tiers were
 * measured at. The filterbank is model data, so *some* whisper context is structurally required —
 * and there are two ways to get one. [WhisperNative.initMelOnly] reads the contiguous
 * magic -> hparams -> filterbank prefix and stops: **64,320 bytes of coefficients**, no weights, no
 * vocab, no ggml context, no backend. The full loader would hold 60-190 MB resident beside the
 * NPU's own ~376 MiB, produce a **byte-identical** mel, and surface first as an LMK kill on a
 * mid-range device. Nothing downstream can tell the two apart, which is why the choice is pinned in
 * source by `NpuNativeContractTest` rather than merely explained here.
 *
 * ### One mel implementation, two DATA sources (4.1 L3)
 *
 * What the tier's spec decides is not how the mel is computed but where its coefficients come from,
 * and `spec.melAsset` is the whole of that decision:
 *
 *  - **null** — an installed 80-bin whisper model, via `paths.cpuTierModelPath()`. Every 80-bin
 *    model carries a byte-identical 80x201 matrix, so any of them is a donor. The `npu` tier's arm,
 *    and byte-for-byte the 4.0 path: same stages, same messages.
 *  - **an asset name** — `melbank-128.bin`, staged out of the APK by [NpuAssetStage]. A 128-bin
 *    tier has no donor to borrow from, because the only 128-bin model in the catalog is `ultra` at
 *    574 MB and it need not be installed. The asset is **102,968 bytes** — `56 + 128 * 201 * 4`,
 *    which is exactly the prefix `initMelOnly` reads — so it is not a second format and needs no
 *    second loader. **Both arms reach the same `initMelOnly` call.**
 *
 * `cpuTierModelPath()` therefore stops being the mel donor for a bundled-filterbank tier and stays
 * the **CPU fallback**, which is what [fallBackToCpuTier] reads. Those were one question only
 * because 4.0 had one tier.
 *
 * The mel context is held for the tier's LIFETIME, not per segment: re-reading 64 KB off flash for
 * every utterance is the worse trade by a wide margin. It is freed in [release], and — the part
 * that matters — in the same teardown that frees the NPU, **before** the CPU tier is loaded.
 *
 * ### The fallback is loud, and it releases first
 *
 * Any stage that declines ends the same way: [releaseNpuResources] runs FIRST, then the CPU tier is
 * loaded. Loading a 190 MB whisper model while 376 MiB of NPU contexts are still held is a ~570 MB
 * transient on the one path that exists to be safe — and the ordering is an invariant, not a
 * preference, so it is pinned as one. One `npu: unavailable stage=… detail=…` line names the stage,
 * Q8's card says so, and the session runs on the CPU model. A fallback that quietly ran on the CPU
 * while the card still promised the AI chip is the exact failure this project has already paid for
 * once.
 *
 * ### The spec is required, and it has no default
 *
 * `spec` says which model's assets these paths point at: its mel width, its layer and head counts,
 * its vocabulary and its context window. Every one of those is passed to `nativeInit`, which
 * derives the graph census it refuses a mismatched asset on — so the spec is what makes the F2
 * guard a guard once more than one npu-class tier exists.
 *
 * **It has no default value, deliberately.** A default would let a future call site arm one model's
 * 358 MB of context binaries under another model's census, and the outcomes run from a refusal at
 * load (the good case, because the census guard fires) to a decode driven by the wrong token family
 * — another model's transcript, fluent and confident, with nothing failing. `NpuBackendSelector`
 * resolves it from the tier id through `NpuModelSpec.forTier`, and answers `WhisperNativeBackend`
 * when there is no row, so a tier without a spec cannot reach this constructor at all.
 *
 * Note what the 4.1 L1 arming epoch does NOT cover here: it identifies the SESSION, not the SPEC.
 * An instance armed with the wrong model's assets under this spec has a perfectly valid, perfectly
 * matching epoch. The epoch closes the cross-instance teardown and encode hazards; the required
 * spec is the only thing that closes this one.
 *
 * ### The family is required too, and it has no default either (4.2 F2)
 *
 * `family` is the census row this device resolved to ([com.whispereverywhere.npu.NpuGate.familyFor],
 * memoised once on the app), and it is what makes the skel stage a per-device decision: the row
 * names WHICH DSP-side skel this silicon's FastRPC loader can open (`skelAsset`) and the exact
 * bytes it must be (`skelBytes`/`skelSha256`). **No default value, the same doctrine as `spec`
 * and for the same shape of reason:** a defaulted family would stage the default's skel under
 * another family's silicon, and the failure is not a compile error and not a named refusal — it
 * is a FastRPC mystery on a device, inside a loader whose search path this code only ever sets
 * up. `NpuBackendSelector` resolves the row from the app's one memo and answers
 * `WhisperNativeBackend` when there is none, so a device off the census cannot reach this
 * constructor at all.
 *
 * ### Handles
 *
 * [load] returns [HANDLE] on success and `0L` on failure, matching [WhisperNativeBackend]'s
 * convention; the native side holds the real session state, and [release] ignores the value. There
 * is one session per process on the native side, so there is nothing for a handle to identify.
 *
 * ### Testing
 *
 * **No JVM test may name this class.** It touches [QnnAsrNative], whose `init` block runs
 * `System.loadLibrary("qnnasr")`, and there is no `libqnnasr.so` on the unit-test classpath — the
 * mere reference kills the test. Its invariants are therefore pinned as SOURCE TEXT in
 * `NpuNativeContractTest` and `NpuDiagTest`, its pure parts live in `NpuGate`, `NpuDiag`,
 * `NpuQuantize` and `NpuDecodePolicy` where they are fully tested, and its runtime behaviour is
 * first executed on device at Q10a.
 */
class NpuWhisperBackend(
    private val paths: ModelPathProvider,
    private val appContext: Context,
    private val spec: NpuModelSpec,
    private val family: NpuSocFamily,
) : WhisperBackend {

    // ---------------------------------------------------------------- session state

    /** The ~64 KB mel-only whisper context, or 0. Held for the tier's lifetime. */
    private var melCtx: Long = 0L

    /** Built once at [load], over `spec.vocabAsset` — half a megabyte of JSON and ~52k strings is not a per-segment cost. */
    private var decoder: WhisperBpeDecoder? = null

    /** `spec.melFloatBytes` direct, native order. Reused; [WhisperNative.pcmToMel] overwrites all of it. */
    private var melBuffer: ByteBuffer? = null

    /** `spec.inputFeaturesBytes` direct, native order — the `ufixed16` block `nativeEncode` copies in. */
    private var quantBuffer: ByteBuffer? = null

    /** True once `nativeInit` has succeeded and every artefact is in hand. */
    private var armed: Boolean = false

    /**
     * **The arming epoch — the identity of the QNN session THIS instance armed**, or `0L` when it
     * owns none (4.1 L1, final review F4/I1).
     *
     * [armed] says a session exists. This says *which one*, and the difference is the whole task.
     * The QNN session is a process-global that `nativeInit` releases before building a new one,
     * and `LocalWhisperEngine.shutdown()` **queues** this backend's release onto the stale engine's
     * executor while the replacement loads on a different one. So an `npu → npu-class` rebuild has
     * an interleaving in which a dead instance's release destroys the successor's session — and no
     * arrangement of the two source statements prevents it, because source order does not order
     * two executors' effects.
     *
     * It is read in exactly two places, and both of them are refusals rather than actions:
     * [releaseNpuResources] passes it so that a stale teardown names a session that no longer
     * exists and is ignored, and [transcribe] compares it against the live one so that a stale
     * instance cannot encode into — or decode out of — a session belonging to a different model.
     * That second one is the *fluent wrong text* shape at its worst: another model's transcript,
     * with nothing failing.
     */
    private var armedEpoch: Long = 0L

    /**
     * The CPU tier's native handle, valid only while [fallbackBackend] is non-null, else `0L`.
     *
     * **It is a `Long`, so it can carry no routing decision of its own.** `0L` is
     * `WhisperNativeBackend`'s failure value AND its uninitialised value, and nothing here can tell
     * those apart — which is exactly why the pair needs a separate guard and why that guard is
     * [fallbackBackend]. Read it only after that one has answered non-null; see its declaration for
     * the publication order that makes doing so safe.
     */
    @Volatile
    private var fallbackCtx: Long = 0L

    /**
     * The CPU tier this session fell back to, or null while the NPU is live. **Non-null is the
     * whole of the routing decision**: [transcribe], [detectedLanguage] and [releaseEverything]
     * each check this field, and this field only, before delegating.
     *
     * **Two mechanisms, and they are one mechanism.** This field and [fallbackCtx] are both
     * `@Volatile` AND every read and write that can ACT on them — route a transcribe, load a
     * tier, tear one down — happens inside [NativeComputeGate], because they need two different
     * guarantees. The gate gives mutual exclusion, so the pair cannot be
     * half-updated while another thread routes on it; `@Volatile` gives publication, so the pair a
     * reader sees is the pair a writer wrote. Neither alone is enough and neither is redundant —
     * dropping the gate lets two threads both observe `null` and both fall back (leaking a whole
     * 60-190 MB whisper context), and dropping `@Volatile` lets a reader see a non-null backend
     * beside a stale `0L` handle and silently lose a segment on `transcribe(0L, …)`.
     *
     * **Two bounded exemptions, both passive (4.1 L7).** [detectsPerUtterance] reads the guard
     * ALONE, ungated: it routes nothing, touches nothing native, and either stale answer costs
     * at most one segment resolved under the other arm's language policy — both of which are
     * honest. [lastSegmentStats] reads the pair ungated for its delegate's own documented
     * reason: the gate is FAIR, so taking it for a diagnostic would park the timing line (and
     * the segment resolution behind it) behind an in-flight batch chunk — see that member. Both
     * lean on the publication order below, and every reader that can ACT still holds the gate.
     *
     * **This one is the GUARD**: it is written LAST when arming ([fallBackToCpuTier]) and cleared
     * FIRST when tearing down ([releaseEverything]), so a reader that sees it non-null is
     * guaranteed to see the handle that goes with it. Same discipline, and the same reason, as
     * `WhisperNativeBackend`'s `lastStats`/`lastStatsCtx`.
     */
    @Volatile
    private var fallbackBackend: WhisperBackend? = null

    /**
     * What [detectedLanguage] may report for the last segment — [NpuDecodePolicy.LangResolution.reportable],
     * never the bare `code`. A device-locale or English fallback is a guess this tier made, and
     * reporting it as a detection is how it becomes a session-wide language pin; see that property.
     */
    @Volatile
    private var lastReportedLanguage: String? = null

    /**
     * `"<stage>: <detail>"` for the stage that declined, or null while the tier is live. Read by
     * the tier card (Q8) so the UI states the same fact the log line does.
     *
     * **The setter publishes to [NpuTierStatus] (Q8), under this instance's own tier id (4.1
     * L8).** Nothing in the app holds this instance — the engine layer builds it per session (Q9)
     * and a Compose screen can never reach it — so the card subscribes to the process-scoped
     * mirror instead. It is done HERE, in the setter, and not at the two assignment sites, for the
     * same reason `notifyModelInstalled` is one function: a stage that declines cannot set the
     * reason and forget to announce it, including a stage nobody has written yet. The key is
     * `spec.tierId` so the record lands on the tier it is ABOUT — a turbo decline must never ban
     * npu's routing or wear npu's card, which is the coupling the per-tier mirror removes.
     */
    @Volatile
    var unavailableReason: String? = null
        private set(value) {
            field = value
            NpuTierStatus.publish(spec.tierId, value)
        }

    // ---------------------------------------------------------------- load

    /**
     * The one-path form, for callers that do not know this is a two-artifact tier. It resolves the
     * companion itself rather than declining: the seam's single-path [load] is what
     * `LocalWhisperEngine` and `BatchTranscriber` call, and neither of them should have to learn
     * that one tier has a second file.
     */
    override fun load(modelPath: String): Long = load(modelPath, paths.companionModelPath())

    /**
     * Arms the tier: the companion check, the mel context, the vocabulary, then the two QAIRT
     * context binaries.
     *
     * **The order is the design, and it is ordered by cost.** The companion check is first because
     * it is a null test on a `String?` and is the likeliest reason this tier does not come up
     * (4.1 L3, Q6 M2 — until then it sat fourth, behind everything it could have saved). The mel
     * context is next because it is ~64 KB and its failure is a clean "tier unavailable" **before**
     * 358 MB of NPU assets have been touched; the vocabulary follows for the same reason (563 KB,
     * and a decoder that failed to construct does not exist, so there is nothing to run degraded);
     * `nativeInit` — the expensive, ~342 MiB, ~525 ms one — is last. Every failure before it costs
     * nothing.
     *
     * Must not run on Main: `nativeInit` reads 342 MB from disk and deserialises it.
     *
     * @param modelPath the encoder context binary.
     * @param companionPath the decoder context binary. Null is a refusal, not an omission.
     * @return [HANDLE], or 0L when neither the NPU nor the CPU fallback could be brought up.
     */
    override fun load(modelPath: String, companionPath: String?): Long =
        // ONE serialized hold spans this whole body, and that single hold is LOAD-BEARING for
        // the epoch handshake (4.1 L1; stated here per its m8 rider): stages (6) and (7) are two
        // JNI crossings — nativeInit, then nativeEpoch — and it is this one gate hold that makes
        // the pair atomic against every other arm. Split the hold, or move either crossing out
        // of it, and another instance's nativeInit can land BETWEEN them, handing this instance
        // the successor's epoch: a stale backend armed with a live identity, the exact shape the
        // epoch exists to refuse. The hold, not source order, carries the invariant.
        NativeComputeGate.serialized {
            // FIRST STATEMENT, and it is an ORDER invariant (4.0, Q9b). The build is
            // GGML_BACKEND_DL, so the ggml backend registry starts EMPTY and only
            // WhisperNative.loadBackends populates it — which, until Q9b, happened solely inside
            // WhisperNativeBackend.load. This tier is the first session shape that never loads a
            // CPU tier, so it inherited an empty registry and every session SIGABRTed at the VAD
            // probe's make_buft_list. The probe now asserts the precondition at its own site too;
            // this call is the tier's half, placed above everything so that no native whisper
            // entry reachable from here — initMelOnly, pcmToMel, or the CPU fallback's own load —
            // can ever be the one that finds the registry empty.
            //
            // MEASURED, not assumed: whisper_init_from_file_mel_only and whisper_pcm_to_mel do
            // NOT call make_buft_list today (it appears at whisper.cpp:1712 in the full loader and
            // :5126 in the VAD init, nowhere else). Ensuring first makes that a fact this file
            // does not have to keep depending on.
            GgmlBackends.ensureLoaded()

            // A re-load with a previous session still held is the co-residency hazard in miniature:
            // whichever way round it happened, two model-sized things would be resident at once.
            releaseEverything()
            unavailableReason = null

            // (1) THE PAIRED ARTIFACT, and it is first because it costs nothing (4.1 L3, Q6 M2).
            // A two-artifact tier with one artifact is not a degraded tier; it is an uninstalled
            // one. This is a null test on a String and it is the single likeliest reason this tier
            // does not come up on a real device — the state of every device where the two-file
            // import ran halfway or never ran at all. It sat at stage 4 until 4.1, behind a
            // filesystem lookup, a 64 KB model load and 563 KB of JSON, which is the one place
            // load's own cheapest-refusal-first ordering was violated.
            if (companionPath.isNullOrBlank()) {
                return@serialized fallBackToCpuTier(
                    "companion", "the npu tier needs both context binaries and the decoder half is missing"
                )
            }

            // (2) THE MEL FILTERBANK, from whichever of the two sources this tier's spec names
            // (4.1 L3). One mel implementation, two data sources — and the second is not a second
            // format: `melbank-128.bin` is the magic → hparams → filterbank prefix of a 128-bin
            // ggml, which is exactly what initMelOnly reads before it stops.
            //
            // `cpuTierModelPath()` answers TWO questions in 4.0 — the mel donor and the CPU
            // fallback — and they were one question only because there was one tier. It stops being
            // the donor for a bundled-filterbank tier and STAYS the fallback, which is what
            // fallBackToCpuTier reads below.
            val melSourcePath: String
            if (spec.melAsset == null) {
                // The 80-bin arm, unchanged. Null here means the tier cannot come up AND cannot
                // fall back — one file answers both, so its absence is total. See
                // ModelPathProvider.cpuTierModelPath.
                melSourcePath = paths.cpuTierModelPath()
                    ?: return@serialized fallBackToCpuTier(
                        "mel-donor", "no installed 80-bin whisper model to take the mel filterbank from"
                    )
            } else {
                // The bundled arm. A 128-bin tier has no donor to borrow from: the only 128-bin
                // model in the catalog is `ultra`, 574 MB, and it need not be installed. Staged
                // against the two constants tools/extract_melbank.py and MelbankAssetTest also
                // assert, so all three readings are one value.
                melSourcePath = NpuAssetStage.stagedPath(
                    appContext,
                    spec.melAsset,
                    NpuModelSpec.MELBANK_128_BYTES,
                    NpuModelSpec.MELBANK_128_SHA256,
                ) ?: return@serialized fallBackToCpuTier(
                    "mel-asset",
                    "${spec.melAsset} could not be staged from the APK — see the WE-DIAG line above"
                )
            }

            // (3) 64 KB, not 190 MB. The full loader is byte-identical downstream and is the one
            // mistake nothing but a source pin can catch — see the class KDoc. ONE loader for both
            // arms: a second one would be a second mel path, which is the thing the spec forbids
            // outright.
            melCtx = WhisperNative.initMelOnly(melSourcePath)
            if (melCtx == 0L) {
                return@serialized fallBackToCpuTier(
                    "mel-init",
                    "initMelOnly found no usable ${spec.melBins}-bin filterbank in $melSourcePath"
                )
            }

            // (4) The vocabulary — THIS tier's, by name AND by size (4.1 L4). Both halves come off
            // the one spec object, because they are one fact stated twice: the file at
            // spec.vocabAsset must resolve exactly spec.tokens.vocab ids — 51,865 for the
            // whisper-small family, 51,866 for large-v3/turbo — and a decoder built from the
            // wrong pairing still binds, still decodes, and renders the other family's specials
            // as text boundaries. IOException = absent from the APK; IllegalStateException =
            // present and wrong (not JSON, an entry count other than the spec's, a token outside
            // the byte-level alphabet). Those two cover every way this asset can fail, and all of
            // them fire here rather than under a user who has already pressed record.
            decoder = try {
                WhisperBpeDecoder.fromJson(
                    appContext.assets.open(spec.vocabAsset)
                        .use { it.readBytes().toString(Charsets.UTF_8) },
                    expectedSize = spec.tokens.vocab,
                )
            } catch (cause: java.io.IOException) {
                return@serialized fallBackToCpuTier(
                    "vocab", "${spec.vocabAsset}: ${cause.javaClass.simpleName}: ${cause.message}"
                )
            } catch (cause: IllegalStateException) {
                return@serialized fallBackToCpuTier(
                    "vocab", "${spec.vocabAsset}: ${cause.javaClass.simpleName}: ${cause.message}"
                )
            }

            // (5) THE DSP-SIDE SKEL — THIS FAMILY'S ROW, staged from the APK's assets into
            // filesDir (4.1 L6 — the I5 answer; fleet-wide at 4.2 F2). packaging.jniLibs
            // EXCLUDES every census family's skel: under extractNativeLibs="false" a lib/ copy
            // is provably unopenable by the FastRPC loader, which needs a real file on disk and
            // searches only ADSP_LIBRARY_PATH. The extractQnnSkel Gradle task re-materialises
            // all four families' skels from the resolved AAR into assets — asserting the same
            // census-pinned (bytes, sha256) pairs at build time — and this stage copies exactly
            // ONE of them, the row this device resolved to, into filesDir, the FIRST
            // ADSP_LIBRARY_PATH entry, where nativeInit's dlopen of libQnnHtp.so will have
            // FastRPC find it. The three values are the family row's — the census is their one
            // home, and a skel staged under another family's values is precisely the FastRPC
            // mystery the required `family` parameter exists to prevent. The RETURN PATH IS
            // DELIBERATELY UNUSED: FastRPC searches the environment, never Kotlin, so the
            // call's value is its refusal gate.
            //
            // stagedPathWithMarker, NOT stagedPath — the L3 handoff's explicit warning to this
            // stage: the plain arm full-hashes the destination on EVERY arm, free at the
            // melbank's 103 KB and a per-session ~17.9-18.8 MiB flash read here. The first arm
            // pays one verified write (once per install); every later arm is a handful of stats
            // against the stored marker. A null is a stage refusal like any other stage's:
            // without it the HTP backend would come up and then fail somewhere far less
            // legible, inside FastRPC.
            NpuAssetStage.stagedPathWithMarker(
                appContext,
                family.skelAsset,
                family.skelBytes,
                family.skelSha256,
            ) ?: return@serialized fallBackToCpuTier(
                "skel",
                "${family.skelAsset} (family ${family.id}) could not be staged from the APK " +
                    "into filesDir — the FastRPC loader would find no DSP-side skel to open"
            )

            // (6) 342 MiB and ~525 ms. runCatching, not try/catch on a named type: libqnnasr.so is
            // absent by design on builds where the proprietary QNN headers could not be fetched, and
            // the FIRST touch throws UnsatisfiedLinkError while every touch after it throws
            // ExceptionInInitializerError / NoClassDefFoundError, because the <clinit> has already
            // failed. Catching the first one by name would crash the tier-unavailable path on the
            // second call rather than the first.
            // THE FIVE VARYING SCALARS (4.1 L2). Native derives its own census from these and
            // compares the graphs' own enumeration against it, so a spec that does not describe
            // the asset on disk is refused HERE — at load, by name — instead of surfacing later as
            // a session whose buffer sizing and whose model disagree. `headDim`, `audioCtx` and
            // `melFrames` are deliberately NOT passed: they are identical on every published
            // Whisper AI Hub asset, and an argument carrying a number that cannot vary is a number
            // a caller can get wrong.
            val initError = runCatching {
                QnnAsrNative.nativeInit(
                    modelPath,
                    companionPath,
                    libDir(),
                    spec.melBins,
                    spec.decLayers,
                    spec.heads,
                    spec.tokens.vocab,
                    spec.maxPositions,
                )
            }.getOrElse { cause -> "init: ${cause.javaClass.simpleName}: ${cause.message}" }
            if (initError.isNotEmpty()) {
                return@serialized fallBackToCpuTier("init", initError)
            }

            // (7) THE ARMING EPOCH, read the instant the session exists and BEFORE `armed = true`
            // below. The order is an invariant, not a tidiness: between those two statements this
            // instance would be a live backend holding epoch 0 — i.e. one whose release names no
            // session — which is precisely the unguarded shape L1 removed. Native refuses 0
            // outright, so the window is not dangerous; it is simply a state that must not exist.
            armedEpoch = QnnAsrNative.nativeEpoch()

            // Q10a-D1. The decoder runs and emits nothing, and every hypothesis about why is a
            // statement about numbers only the native loop can see. Armed here, after init, because
            // there is no session to instrument before it — and with BuildConfig.DEBUG, so the
            // owner's debug build talks and a release build does not.
            QnnAsrNative.nativeSetDiag(com.whispereverywhere.BuildConfig.DEBUG)

            melBuffer = NpuQuantize.newMelFloatBuffer(spec)
            quantBuffer = NpuQuantize.newInputFeaturesBuffer(spec)
            armed = true
            HANDLE
        }

    // ---------------------------------------------------------------- transcribe

    /**
     * One 30 s segment: mel, quantise, encode, resolve the language, decode, detokenise.
     *
     * [useVad] is IGNORED, and that is correct rather than unimplemented: the encoder's
     * `input_features` is a fixed `[1,melBins,3000]`, so the window is 30 s whatever the VAD would have
     * said, and trimming the samples before the mel would only move silence from one end of a
     * zero-padded window to the other.
     *
     * Held under [NativeComputeGate] end to end — **including the fallback short-circuit**, which
     * is inside the hold rather than in front of it. `pcmToMel` REPLACES the mel context's internal
     * state, so two segments on this one handle would race each other; the QNN session is a single
     * process-global behind its own mutex and must not see an encode and a decode interleaved; and
     * the routing decision itself is shared mutable state, so reading it outside the hold is how
     * two threads both decide to fall back and one 60-190 MB whisper context is leaked. The lock is
     * reentrant and the delegate takes it again, which costs nothing.
     */
    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
        return NativeComputeGate.serialized {
            fallbackBackend?.let {
                return@serialized it.transcribe(fallbackCtx, samples, lang, useVad)
            }

            // THE EPOCH CHECK, and it is this function's first act for a reason (4.1 L1).
            //
            // The QNN session is a process-global. If a newer arm — another npu-class tier — has
            // replaced the session this instance was built on, then encoding into it and decoding
            // out of it produces ANOTHER MODEL'S TRANSCRIPT: fluent, confident, and wrong, with
            // nothing failing anywhere. That is the worst failure shape this tier has, so the
            // refusal is taken before any work at all. Below `pcmToMel` it would be taken after
            // the shared mel context's state had already been replaced, on a segment already paid
            // for; the check costs one ~100 ns JNI crossing against a ~405 ms encode.
            //
            // Guarded on `armedEpoch != 0L` so an instance that never armed does not touch
            // QnnAsrNative — and therefore does not dlopen it — merely to find that out; and read
            // ONCE, into a local, so the number the refusal reports is the number the branch was
            // taken on rather than a second reading of a value that has no reason to agree.
            if (armedEpoch != 0L) {
                val liveEpoch = QnnAsrNative.nativeEpoch()
                if (liveEpoch != armedEpoch) {
                    return@serialized fallBackAndRun(
                        "epoch",
                        sessionReplacedDetail(armedEpoch, liveEpoch),
                        samples,
                        lang,
                        useVad,
                    )
                }
            }

            val mel = melBuffer
            val quantised = quantBuffer
            val bpe = decoder
            if (!armed || mel == null || quantised == null || bpe == null) {
                return@serialized fallBackAndRun(
                    "session", "transcribe on a tier that is not armed", samples, lang, useVad
                )
            }

            val encodeStart = SystemClock.elapsedRealtime()

            // pcmToMel zero-pads or truncates to the encoder's 480,000-sample window itself, so the
            // caller hands it whatever the segment actually was.
            if (!WhisperNative.pcmToMel(melCtx, samples, mel, spec.melBins)) {
                return@serialized fallBackAndRun("mel", "pcmToMel refused or failed", samples, lang, useVad)
            }

            // THE STRIDE BISECTOR (4.0, Q9 fix round, I2). Three row sums, 9,000 float adds against
            // a ~405 ms encode, and `row40 == row79` is the copy-stride defect stated in one glance
            // — see NpuDiag.mel. Its POSITION is the invariant, on both sides:
            //   - AFTER pcmToMel returned TRUE. The mel buffer is reused across segments, so a line
            //     emitted above this guard would print the PREVIOUS segment's spectrogram and
            //     attribute it to a segment whose mel was never computed.
            // The three rows are 0, melBins/2 and melBins-1 — the spec's, not 0/40/79 — because a
            // fixed 79 names a row that does not exist on a 128-bin tier and, worse, would silently
            // report a row from the middle of one where the claim is about the last (4.1 L2).
            //   - BEFORE melToU16. This must measure whisper's floats, not anything the quantiser
            //     has been near; a bisector that cannot separate the mel from the quantisation is
            //     not a bisector.
            // A fresh asFloatBuffer() view, read absolutely, so the shared direct buffer handed to
            // melToU16 and then to nativeEncode keeps its position untouched.
            val melView = mel.asFloatBuffer()
            android.util.Log.i(
                NpuDiag.TAG,
                NpuDiag.mel(
                    spec.melBins,
                    NpuQuantize.melRowSum(spec, melView, 0),
                    NpuQuantize.melRowSum(spec, melView, spec.melBins / 2),
                    NpuQuantize.melRowSum(spec, melView, spec.melBins - 1),
                ),
            )

            // NEVER literals. The affine parameters belong to the asset and are read off
            // input_features' own metadata; a hardcoded scale would survive an asset re-export and
            // scale every spectrogram wrongly, which the encoder transcribes fluently into different
            // words with nothing downstream able to notice.
            val quant = QnnAsrNative.nativeInputQuant()
            if (quant.size < 2) {
                return@serialized fallBackAndRun("quant", QnnAsrNative.nativeLastError(), samples, lang, useVad)
            }
            NpuQuantize.melToU16(
                spec, mel.asFloatBuffer(), quant[0], quant[1].toInt(), quantised.asShortBuffer()
            )

            // Q10a-D2. The KOTLIN half of the encoder read — the same two sums and the same three
            // cells native is about to report from the buffer the DSP is bound to, computed here
            // from the float mel by an independent route. One reading describes a buffer; the pair
            // decides whether the block the graph sees is the block this code wrote, and in which
            // orientation. Emitted BEFORE nativeEncode so the two halves land adjacent in the log.
            //
            // BuildConfig.DEBUG, like nativeSetDiag above it: `melBins + melFrames` extra quantise
            // calls — 3,080 on this tier, 3,128 on a 128-bin one — and three float reads, which is
            // nothing against a ~405 ms encode. (The 4.0 comment here said 6,000; it was double the
            // real figure, corrected at 4.1 L3. This file's comments are read as measurements and
            // one wrong measurement devalues the rest.) A release build has no business narrating
            // the spectrogram it is working on.
            if (com.whispereverywhere.BuildConfig.DEBUG) {
                val probe = mel.asFloatBuffer()
                val half = spec.melFrames / 2
                android.util.Log.i(
                    NpuDiag.TAG,
                    NpuDiag.melProbe(
                        spec,
                        NpuQuantize.quantisedRowSum(spec, probe, 0, quant[0], quant[1].toInt()),
                        NpuQuantize.quantisedColumnSum(spec, probe, 0, quant[0], quant[1].toInt()),
                        floatArrayOf(
                            probe.get(0),
                            probe.get(half),
                            probe.get(spec.melFrames * (spec.melBins / 2) + half),
                        ),
                        quant[0],
                        quant[1].toInt(),
                    ),
                )
            }

            val encodeError = QnnAsrNative.nativeEncode(quantised)
            if (encodeError.isNotEmpty()) {
                return@serialized fallBackAndRun("encode", encodeError, samples, lang, useVad)
            }
            val encodeMs = SystemClock.elapsedRealtime() - encodeStart

            val decodeStart = SystemClock.elapsedRealtime()

            // The detect pass runs ONLY when the user has not chosen — one extra graphExecute,
            // ~4.5 ms against a ~405 ms encode. It does not consume the encode: this same encoded
            // segment is what nativeDecodeSegment reads next, in place.
            val detected = if (lang == null) QnnAsrNative.nativeDetectLanguage() else DETECT_NOT_RUN
            val resolution = try {
                NpuDecodePolicy.resolveLangToken(
                    spec.tokens, lang, detected, Locale.getDefault().toLanguageTag()
                )
            } catch (cause: IllegalArgumentException) {
                // An explicit selection this asset cannot name. Refused rather than coerced to
                // English — and handed to the CPU tier, which accepts language strings this one
                // cannot and is therefore a genuine repair rather than a shrug.
                return@serialized fallBackAndRun("lang", "${cause.message}", samples, lang, useVad)
            }

            val prompt = NpuDecodePolicy.promptTokens(spec.tokens, resolution.token)
            val out = IntArray(NpuDecodePolicy.maxTokensFor(spec.tokens, prompt.size))
            val written = QnnAsrNative.nativeDecodeSegment(
                prompt,
                NpuDecodePolicy.suppressList(spec.tokens),
                NpuDecodePolicy.beginSuppressList(spec.tokens),
                out.size,
                out,
                NpuDecodePolicy.TEMPERATURES,
                NpuDecodePolicy.ENTROPY_THOLD,
                NpuDecodePolicy.LOGPROB_THOLD,
                NpuDecodePolicy.NO_SPEECH_THOLD,
                spec.tokens.noSpeech,
                NpuDecodeStats.newArray(),
            )
            if (written < 0) {
                return@serialized fallBackAndRun("decode", QnnAsrNative.nativeLastError(), samples, lang, useVad)
            }
            val decodeMs = SystemClock.elapsedRealtime() - decodeStart

            // The SLICE, never the buffer: everything past `written` is untouched memory from the
            // previous segment. And no filtering before this call — the drop rule for ids at or
            // above EOT lives in exactly one place, because the 99 language ids are deliberately
            // unsuppressed native-side and this is the only thing that removes them.
            //
            // An IllegalArgumentException out of decode is deliberately NOT caught: it means an id
            // outside 0 until spec.tokens.vocab was written (51,865 for the whisper-small family,
            // 51,866 for large-v3-turbo — the decoder's bound is per-family, 4.1 L4/L8), which is
            // a contract breach between this file and native — not an asset problem, and not
            // something to bury in the fallback path.
            val text = bpe.decode(out.copyOf(written))

            // `.reportable`, NEVER `.code`. A (locale) or (fallback) resolution is a guess this
            // tier made, and the engine feeds whatever crosses this seam to LanguagePin, which
            // latches the first usable code for the whole session and never revises it. Reporting
            // a guess there turns one failed detect pass on segment 1 into a session pinned to
            // English — after which the detect pass stops running at all and the diag line prints
            // the BARE `en` note that means "the user chose this". See LangResolution.reportable.
            lastReportedLanguage = resolution.reportable

            // ONE line per segment, and `tokens` is native's returned count rather than the text's
            // length: the count exists before the text does, and reading it off the string would be
            // one step from logging the string.
            android.util.Log.i(
                NpuDiag.TAG, NpuDiag.line(encodeMs, decodeMs, written, resolution.note)
            )
            text
        }
    }

    /**
     * Live previews AFTER a fallback; the interface default's exact behaviour before one
     * (Q6 M4, folded at 4.1 L7).
     *
     * This override did not exist in 4.0, so after any NPU decline the session silently lost
     * partial streaming for the rest of its life — `WhisperNativeBackend` supplies it, but the
     * inherited default routed the call through [transcribe] and dropped the closure. §9.2's
     * "it behaves like any other WhisperBackend" was simply wrong there, and the user
     * experienced a degradation nothing named. The live-NPU arm stays a plain [transcribe] with
     * zero deltas, deliberately: the NPU decode loop has no per-segment native callback, and
     * forging deltas would be worse than omitting them.
     *
     * Same gate-then-route shape as [transcribe], for the same reasons: this member runs native
     * compute, so it is an ACTING reader of the routing pair. The lock is reentrant, so the
     * live arm's delegation to [transcribe] costs nothing.
     */
    override fun transcribeStreaming(
        ctx: Long,
        samples: FloatArray,
        lang: String?,
        useVad: Boolean,
        onNewSegment: (String) -> Unit,
    ): String = NativeComputeGate.serialized {
        fallbackBackend?.let {
            return@serialized it.transcribeStreaming(fallbackCtx, samples, lang, useVad, onNewSegment)
        }
        transcribe(ctx, samples, lang, useVad)
    }

    /**
     * True exactly while the NPU session is what answers transcribes — LIVE, never a constant
     * (4.1 L7).
     *
     * On this tier the detect pass is one extra `graphExecute`, ~4.5 ms against a ~405 ms
     * encode — about 1%, the same machinery the decode loop already runs — so the 3.7 session
     * latch has nothing to amortise and auto may honestly re-resolve every utterance. On the
     * CPU tier the same question costs roughly HALF of multi's steady-state native cost, which
     * is the entire reason that latch exists. So the moment a stage declines and this backend
     * starts delegating, the answer must flip back to false and the CPU latch must resume — a
     * `val` initialised at construction is evaluated while [fallbackBackend] is still ALWAYS
     * null and could never do that.
     *
     * The read is of the GUARD alone, ungated — one of the two bounded exemptions the routing
     * pair's declaration names: it decides no routing, touches nothing native, and either stale
     * answer costs at most one segment resolved under the other arm's (equally honest) language
     * policy. The engine reads it per segment, which is what lets the fallback edge re-acquire
     * the latch from the exact segment that declined.
     */
    override val detectsPerUtterance: Boolean get() = fallbackBackend == null

    /**
     * ISO code the LAST segment's language was **answered** with — by the user's selection or by
     * the model's own detection pass — or `null` when this tier only guessed.
     *
     * The null is the contract, not a gap. `LocalWhisperEngine` hands this straight to
     * `LanguagePin.onDetected`, which latches the first usable code for the rest of the session, so
     * the only codes that may cross here are ones something actually decided. A `(locale)` or
     * `(fallback)` resolution answers `null`, which is precisely the case `onDetected`'s
     * `isNullOrBlank()` branch exists for: the pin stays open and **the next segment re-attempts
     * detection**, which is the behaviour a failed detect pass should produce.
     *
     * Also null before the first segment. Under the gate because the routing fields it reads are
     * shared mutable state; see their declaration.
     *
     * Since 4.1 L7 the ENGINE queries this only AFTER a fallback — [detectsPerUtterance] gates
     * its pin-feed — so the engine always lands in the delegating arm, and
     * `LangResolution.reportable`'s whole argument (a `(locale)` or `(fallback)` guess must not
     * become a session-wide pin) is exactly the invariant that delegating case still needs. The
     * non-delegating arm keeps answering honestly for any OTHER caller, so the member means the
     * same thing on both tiers.
     */
    override fun detectedLanguage(ctx: Long): String? {
        return NativeComputeGate.serialized {
            fallbackBackend?.let { return@serialized it.detectedLanguage(fallbackCtx) }
            lastReportedLanguage
        }
    }

    /**
     * The CPU tier's cost counters AFTER a fallback; null while the NPU is live (Q6 M4, folded
     * at 4.1 L7 — the second member a declined session silently lost, alongside
     * [transcribeStreaming]: every post-decline timing line dropped its vadIn/vadOut/ctxFrames
     * suffix with nothing naming why).
     *
     * The live-NPU arm answers null through the safe-call, never an all-zero
     * [NativeSegmentStats]: this path has no native counters, and null ("no counters exist")
     * and zeros ("a transcribe ran and cost nothing") are DIFFERENT ANSWERS the type's KDoc
     * forbids collapsing — [SegmentTiming.line] is already built to omit the fields on null.
     *
     * DELIBERATELY NOT under [NativeComputeGate], mirroring the delegate's own documented
     * exemption: this is a diagnostic over volatile Kotlin snapshots that touches no native
     * memory, and the gate is FAIR — taking it here would park the timing line (and the segment
     * resolution behind it) behind an in-flight batch chunk, which would re-tag the delegate's
     * slot before the wait ended anyway. The ungated PAIR read is safe here and only here: the
     * guard is written last and cleared first (see [fallbackBackend]), so a non-null guard
     * vouches for the handle beside it, and the pair's only writers run under the gate on the
     * engine's own executor — the same single thread that issues this read.
     */
    override fun lastSegmentStats(ctx: Long): NativeSegmentStats? =
        fallbackBackend?.lastSegmentStats(fallbackCtx)

    // ---------------------------------------------------------------- teardown and fallback

    /**
     * Frees everything the NPU tier holds: the QNN contexts and the sustained power vote, then the
     * mel context, then the buffers and the decoder.
     *
     * `runCatching` on both native calls because this runs on failure paths, where a partially
     * armed session is the normal case and a teardown that throws would strand the rest of it.
     */
    private fun releaseNpuResources() {
        // NAMED, and only when there is something to name.
        //
        // `armedEpoch` rather than a fresh `nativeEpoch()` read: a fresh read names whatever is
        // live NOW, which on the losing interleaving is the SUCCESSOR's session — the unguarded
        // release with an argument added to it. Native ignores an epoch that is not the live one,
        // so a stale instance's teardown becomes a WE-DIAG line instead of a destroyed session.
        //
        // And the guard, which closes Q6 M1 — its claim stated NARROWLY (4.2 F2, the L1 m2
        // correction): `armedEpoch != 0L` is the QNN-side fact; `melCtx != 0L` is a WHISPER-side
        // fact and proves nothing about QnnAsrNative. What the disjunction guarantees is only
        // that the refusals reached before ANY native touch — companion and mel-donor, the
        // every-session path of every device with no ggml model installed — never dlopen
        // ~25 MiB of Qualcomm runtime on their way out to release a session that was never
        // created. A decline BETWEEN the mel arm and nativeInit (vocab, skel) still takes the
        // release call holding only whisper-side state: that pays the dlopen for a release
        // native refuses (epoch 0 is never live), which is bounded and deliberately preferred
        // over a cleverer test that could learn to skip a real release.
        if (armedEpoch != 0L || melCtx != 0L) {
            runCatching { QnnAsrNative.nativeRelease(armedEpoch) }
        }
        armedEpoch = 0L
        if (melCtx != 0L) {
            runCatching { WhisperNative.free(melCtx) }
            melCtx = 0L
        }
        melBuffer = null
        quantBuffer = null
        decoder = null
        armed = false
    }

    /**
     * The NPU side plus any CPU tier this session had already fallen back to.
     *
     * The guard is cleared FIRST and the handle after it — the mirror of the arming order in
     * [fallBackToCpuTier], and for the same reason: no reader may ever see a non-null
     * [fallbackBackend] beside a handle that is no longer valid.
     */
    private fun releaseEverything() {
        releaseNpuResources()
        val previous = fallbackBackend
        fallbackBackend = null
        previous?.release(fallbackCtx)
        fallbackCtx = 0L
        lastReportedLanguage = null
    }

    /**
     * A stage declined: **release the NPU FIRST, then bring up the CPU tier** (I11).
     *
     * The ordering is the whole point of this function existing at all. Loading a 190 MB whisper
     * model while 376 MiB of NPU contexts and a sustained power vote are still held is a ~570 MB+
     * transient on the exact path that exists to be safe, and it would be an LMK kill on the
     * devices most likely to reach it. Two statements, one order, pinned as an ORDER invariant by
     * `NpuNativeContractTest` — presence alone would be satisfied by swapping them.
     *
     * **AT MOST ONCE per session, and the guard is the first statement.** A second entry is a no-op
     * that returns the live fallback rather than a second `WhisperNativeBackend.load` — which is a
     * decision, so here is the reasoning. Without the guard, a second entry overwrites [fallbackCtx]
     * with a fresh handle and the previous whisper context — a whole CPU tier, 60-190 MB — is
     * leaked for the life of the process, because `releaseEverything` only ever frees the current
     * one. Release-before-overwrite would close the leak but is the wrong repair: the first fallback
     * is already serving this session, so replacing it drops a live handle mid-session and emits a
     * second `npu: unavailable` line for a tier that declined once. Idempotence keeps
     * [unavailableReason] naming the FIRST stage that declined, which is the one that is true.
     *
     * Reachable twice only by a race, since [transcribe] short-circuits to the fallback before any
     * NPU stage can run — which is exactly why the guard is here rather than in the callers: a
     * funnel that can silently discard a live native handle is a sharp edge whatever the threading
     * turns out to be.
     *
     * @return [HANDLE] when the CPU tier came up (or was already up), else 0L — the caller sees a
     *         working backend or a failed load, never a half-armed one.
     */
    private fun fallBackToCpuTier(stage: String, detail: String): Long {
        if (fallbackBackend != null) return HANDLE
        releaseNpuResources()
        unavailableReason = "$stage: $detail"
        android.util.Log.w(NpuDiag.TAG, NpuDiag.unavailable(stage, detail))
        val cpuPath = paths.cpuTierModelPath() ?: return 0L
        val handle = WhisperNativeBackend.load(cpuPath)
        if (handle == 0L) return 0L
        // Handle FIRST, guard LAST: fallbackBackend is what every reader branches on, so it must
        // never become visible before the handle it implies.
        fallbackCtx = handle
        fallbackBackend = WhisperNativeBackend
        return HANDLE
    }

    /**
     * [fallBackToCpuTier] for a stage that declined mid-segment, then runs THIS segment on the CPU
     * tier so the user's utterance is not the thing that pays for the tier's failure. Returns "" if
     * even the CPU tier could not be brought up — at which point the session has no backend at all
     * and the engine's own empty-result handling takes over.
     */
    private fun fallBackAndRun(
        stage: String,
        detail: String,
        samples: FloatArray,
        lang: String?,
        useVad: Boolean,
    ): String {
        fallBackToCpuTier(stage, detail)
        return fallbackBackend?.transcribe(fallbackCtx, samples, lang, useVad) ?: ""
    }

    /** Releases the session. The handle is ignored: the native side holds the state. */
    override fun release(ctx: Long) = NativeComputeGate.serialized { releaseEverything() }

    private fun libDir(): String = appContext.applicationInfo.nativeLibraryDir

    companion object {

        /**
         * The only non-zero handle this backend returns. There is one QNN session per process and
         * it is native-side, so a handle has nothing to identify; 1L/0L simply matches
         * [WhisperNativeBackend]'s success/failure convention at the seam.
         */
        const val HANDLE: Long = 1L

        /** Passed as `detected` when the user chose a language, so no detect pass ran. */
        private const val DETECT_NOT_RUN: Int = -1

        // The 4.1 single-family skel companions lived here. DELETED at 4.2 F2: the census row
        // (`family`) is the one home of every family's (bytes, sha256) pair, and
        // NpuSkelPackagingTest holds this file empty of all their spellings so they cannot
        // quietly come back.

        /**
         * Whether the npu tier may be OFFERED on this device: the right silicon, and a QNN stack
         * that actually loads.
         *
         * **The SoC gate is first and the short circuit is load-bearing.** `nativeProbe` dlopens
         * `libQnnSystem.so` and `libQnnHtp.so`; running it on a Tensor, an Exynos or a MediaTek is
         * pointless work to reach a foregone answer, and asking a Snapdragon 7-series would get a
         * yes — the probe reports whether the HTP *stack* is present, and cannot tell one Hexagon
         * apart from another. Only [NpuGate] can, so it decides first and the probe merely confirms
         * that the stack it needs is loadable.
         *
         * `runCatching` covers [LinkageError] and everything downstream of it: on a build where
         * the proprietary QNN headers were unavailable, `libqnnasr.so` is deliberately absent, the
         * first touch of [QnnAsrNative] throws `UnsatisfiedLinkError` and every touch afterwards
         * throws `ExceptionInInitializerError` instead. Both mean the same thing here — no tier.
         *
         * @param socModel `Build.SOC_MODEL`, or null below API 31. The version guard lives in the
         *        CALLER because `minSdk` is 26; see [NpuGate].
         * @param socManufacturer `Build.SOC_MANUFACTURER`, or null below API 31.
         */
        fun isTierAvailable(socModel: String?, socManufacturer: String?, libDir: String): Boolean =
            NpuGate.isSocSupported(socModel, socManufacturer) &&
                runCatching { QnnAsrNative.nativeProbe(libDir).isEmpty() }.getOrDefault(false)

        /**
         * The `epoch` refusal's detail line, built in one place so that **both** numbers are always
         * in it (4.1 L1).
         *
         * A message that says only "the session was replaced" cannot be checked against anything.
         * The `WE-DIAG` capture carries `nativeInit: session armed with epoch N` on every arm and
         * `nativeRelease: epoch M is not the live session (N)` on every refused teardown, so a
         * Kotlin-side refusal that names neither number leaves the reader unable to say which arm
         * won — which is the single question the L8 device A/B has to answer about this mechanism.
         *
         * @param mine the epoch this backend was armed with.
         * @param live what `nativeEpoch()` answered, i.e. the session that exists now.
         */
        private fun sessionReplacedDetail(mine: Long, live: Long): String =
            "this backend's session ($mine) was replaced by a newer arm ($live)"
    }
}
