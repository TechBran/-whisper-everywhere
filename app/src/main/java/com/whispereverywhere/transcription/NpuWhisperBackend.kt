package com.whispereverywhere.transcription

import android.content.Context
import android.os.SystemClock
import com.whispereverywhere.npu.HallucinationPolicy
import com.whispereverywhere.npu.NpuApuDriverCheck
import com.whispereverywhere.npu.NpuAssetStage
import com.whispereverywhere.npu.NpuDecodePolicy
import com.whispereverywhere.npu.NpuDecodeStats
import com.whispereverywhere.npu.NpuDiag
import com.whispereverywhere.npu.NpuGate
import com.whispereverywhere.npu.NpuQuantize
import com.whispereverywhere.npu.NpuSentences
import com.whispereverywhere.npu.NpuTierStatus
import com.whispereverywhere.npu.NpuModelSpec
import com.whispereverywhere.npu.NpuSocFamily
import com.whispereverywhere.npu.WhisperBpeDecoder
import com.whispereverywhere.whisper.GgmlBackends
import com.whispereverywhere.whisper.WhisperNative
import java.nio.ByteBuffer
import java.util.Locale

/**
 * The 4.0 NPU tier behind the ordinary [WhisperBackend] seam: mel on the CPU, encoder and decoder
 * on the AI chip, and one CPU-tier fallback that is never silent.
 *
 * ```
 * load(encoder, decoder)   companion? -> mel (64 KB) -> vocab -> engine.prepare (QNN: the
 *                          family's own skel, once) -> engine.init (QNN: nativeInit, 376 MiB, and
 *                          the input quant pair) -> engine.epoch
 * transcribe(samples)      engine.epoch (is this still my session?) -> pcmToMel -> engine.encode
 *                          (QNN: melToU16 -> nativeEncode) -> engine.detectLanguage
 *                          -> engine.decodeSegment -> WhisperBpeDecoder
 * release(ctx)             engine.release(armedEpoch) + WhisperNative.free
 * ```
 *
 * ### The engine seam: this file is the policy, [engine] is the vendor (P1a)
 *
 * Every decision a session takes lives here and is written once for every vendor: the
 * cheapest-refusal-first order of [load], the arming epoch, the one loud fallback, the mel, the
 * language resolution, the two blanks, the sentence slot and every diag line. What differs between
 * NPU runtimes lives behind [NpuAsrEngine] — how the runtime is staged, how the pair is armed, and
 * what the encoder is fed — and the engine's refusals come back as values naming an `NpuStage`,
 * which this file routes through the same one funnel as its own, printing the stage's wire word.
 * [QnnAsrEngine] owns what was QNN-shaped here until the seam: the skel stage, the input quant pair
 * and its buffer, `melToU16` and the Q10a-D2 `melprobe` line (design
 * `docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md` §2.4). The lines a device prints
 * did not change by one character.
 *
 * ### The 64 KB that must never become a whole CPU tier
 *
 * The spectrogram is whisper.cpp's, because the spec allows exactly one mel in this app and a
 * second implementation would be free to drift from the accuracy the CPU and GPU tiers were
 * measured at. The filterbank is model data, so *some* whisper context is structurally required —
 * and there are two ways to get one. [WhisperNative.initMelOnly] reads the contiguous
 * magic -> hparams -> filterbank prefix and stops: **64,320 bytes of coefficients**, no weights, no
 * vocab, no ggml context, no backend. The full loader would hold 60-874 MB resident beside the
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
 * loaded. Loading a whisper CPU tier (264 MB for the 4.7 default, up to 874 MB) while 376 MiB of
 * NPU contexts are still held is a ~660 MB+
 * transient on the one path that exists to be safe — and the ordering is an invariant, not a
 * preference, so it is pinned as one. One `npu: unavailable stage=… detail=…` line names the stage,
 * Q8's card says so, and the session runs on the CPU model. A fallback that quietly ran on the CPU
 * while the card still promised the AI chip is the exact failure this project has already paid for
 * once.
 *
 * ### The spec is required, and it has no default
 *
 * `spec` says which model's assets these paths point at: its mel width, its layer and head counts,
 * its vocabulary and its context window. Every one of those is passed to the engine's init (for
 * QNN, `nativeInit`), which derives the graph census it refuses a mismatched asset on — so the spec
 * is what makes the F2 guard a guard once more than one npu-class tier exists.
 *
 * **It has no default value, deliberately.** A default would let a future call site arm one model's
 * 338 MB of context binaries under another model's census, and the outcomes run from a refusal at
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
 * memoised once on the app), and it is what makes the skel stage a per-device decision: the row's
 * QNN runtime needs name WHICH DSP-side skel this silicon's FastRPC loader can open (`skelAsset`)
 * and the exact bytes it must be (`skelBytes`/`skelSha256`). **No default value, the same doctrine as `spec`
 * and for the same shape of reason:** a defaulted family would stage the default's skel under
 * another family's silicon, and the failure is not a compile error and not a named refusal — it
 * is a FastRPC mystery on a device, inside a loader whose search path this code only ever sets
 * up. `NpuBackendSelector` resolves the row from the app's one memo and answers
 * `WhisperNativeBackend` when there is none, so a device off the census cannot reach this
 * constructor at all. Since the seam the row reaches its stage through [NpuAsrEngine.prepare],
 * whose QNN implementation is that skel stage, moved whole.
 *
 * ### The engine is required as well, with no default (P1a)
 *
 * `engine` is the runtime this tier's session runs on. The selector constructs it by the row's
 * vendor — [QnnAsrEngine] for a Qualcomm row, [LiteRtAsrEngine] for a MediaTek one (P2-7) — and a
 * default here would be the same silent wrong choice as a defaulted family, one layer down: a
 * runtime chosen by this file instead of by the row that says which silicon it is on.
 *
 * ### Handles
 *
 * [load] returns [HANDLE] on success and `0L` on failure, matching [WhisperNativeBackend]'s
 * convention; the native side holds the real session state, and [release] ignores the value. There
 * is one session per process on the native side, so there is nothing for a handle to identify.
 *
 * ### Testing
 *
 * **No JVM test may name this class.** It touches [WhisperNative] (directly and through
 * [GgmlBackends]), whose `init` block runs `System.loadLibrary("whisper_jni")`, and its capability
 * probe and its production engines touch `com.whispereverywhere.npu.QnnAsrNative`, whose `init`
 * block runs `System.loadLibrary("qnnasr")` — and, on a MediaTek row, `LiteRtAsrNative`
 * (`"litertasr"`); none of those libraries is on the unit-test classpath. Its
 * invariants are therefore pinned as SOURCE TEXT in `NpuNativeContractTest`, `NpuDiagTest` and
 * `NpuStageTest`, its pure parts live in `NpuGate`, `NpuDiag`, `NpuQuantize` and
 * `NpuDecodePolicy` where they are fully tested, and its runtime behaviour is first executed on
 * device at Q10a.
 */
class NpuWhisperBackend(
    private val paths: ModelPathProvider,
    private val appContext: Context,
    private val spec: NpuModelSpec,
    private val family: NpuSocFamily,
    private val engine: NpuAsrEngine,
) : WhisperBackend {

    // ---------------------------------------------------------------- session state

    /** The ~64 KB mel-only whisper context, or 0. Held for the tier's lifetime. */
    private var melCtx: Long = 0L

    /** Built once at [load], over `spec.vocabAsset` — half a megabyte of JSON and ~52k strings is not a per-segment cost. */
    private var decoder: WhisperBpeDecoder? = null

    /**
     * `spec.melFloatBytes` direct, native order. Reused; [WhisperNative.pcmToMel] overwrites all of
     * it, and it is what [NpuAsrEngine.encode] is handed — the float mel, whatever the runtime
     * makes of it (the QNN engine quantises it into its own `ufixed16` block).
     */
    private var melBuffer: ByteBuffer? = null

    /** True once the engine's init has succeeded and every artefact is in hand. */
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
     * 60-874 MB whisper context), and dropping `@Volatile` lets a reader see a non-null backend
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

    // 4.11 Task 3: THE SENTENCE SLOT — `[t0cs, t1cs, byteStart, byteEnd]` per sentence for the
    // last segment this arm decoded, and the only timing this tier has. It is a ONE-SLOT,
    // CTX-TAGGED pair on `WhisperNativeBackend`'s discipline, and every word of the argument
    // recorded at that object's `lastStats`/`lastStatsCtx` applies here unchanged:
    //
    //   PAYLOAD FIRST, TAG LAST, both @Volatile. The tag is the guard: a reader that sees a
    //   matching tag is guaranteed to see the array that goes with it.
    //
    //   CLEARED AT THE TOP OF THE HOLD, above the fallback short-circuit, and the invalidation is
    //   half of the mechanism rather than a tidy-up. A transcribe that THREW — or one the CPU
    //   tier answered after a decline, which publishes no sentences of its own — would otherwise
    //   leave the PREVIOUS segment's bounds behind a still-matching tag, and the next chunk's
    //   text would be cut where the last chunk's sentences ended. The labels would land on the
    //   wrong words and every number involved would look reasonable.
    //
    // One thing is weaker here than there and is worth naming rather than implying: this tier's
    // handle is the constant `HANDLE` (there is one QNN session per process and it is
    // native-side), so the tag distinguishes "a segment ran under this instance" from "none did",
    // not one context from another. That is what it is used for — the `ctx != 0L` guard and the
    // reset — and it is all the seam asks of it.
    @Volatile
    private var lastSentencesArr: IntArray = IntArray(0)

    @Volatile
    private var lastSentencesCtx: Long = 0L

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
     * Arms the tier: the companion check, the mel context, the vocabulary, the engine's own
     * staging, then the two model files (for QNN, the two QAIRT context binaries).
     *
     * **The order is the design, and it is ordered by cost.** The companion check is first because
     * it is a null test on a `String?` and is the likeliest reason this tier does not come up
     * (4.1 L3, Q6 M2 — until then it sat fourth, behind everything it could have saved). The mel
     * context is next because it is ~64 KB and its failure is a clean "tier unavailable" **before**
     * 338 MB of NPU assets have been touched; the vocabulary follows for the same reason (563 KB,
     * and a decoder that failed to construct does not exist, so there is nothing to run degraded);
     * the engine's init — `nativeInit` for QNN, the expensive one — is last. Every failure before it
     * costs nothing.
     *
     * **How expensive, measured on the shipped tier** (4.4.0 startup amendment S2; the old
     * "~342 MiB, ~525 ms" here and at stage (6) was a 127 MB encoder-only SPIKE figure and
     * understated it about eightfold): `nativeInit` alone is **~2,480 ms** and this whole function
     * end to end, `dlopen` through "session armed", is **4,107 ms** cold on npu-turbo —
     * `C:/Users/bastr/.androidbuild/capture-yt-84-flatline-0903-1936.txt`, 19:29:48.854 ->
     * 52.961, broken down at stage (6) and in
     * `docs/superpowers/research/2026-09-10-startup-cutoff-investigation.md` §3a. Resident:
     * ~342 MiB for the `npu` pair, ~1.02 GiB for `npu-turbo`'s.
     *
     * Must not run on Main: `nativeInit` reads 342 MB (npu) to 1.02 GB (npu-turbo) from disk and
     * deserialises it. And not, any more, inside the capture seam's critical path either — 4.1 s
     * is why 4.4.0 buffers the audio spoken during it rather than losing it
     * (`audio/StartupRing.kt`).
     *
     * @param modelPath the encoder context binary.
     * @param companionPath the decoder context binary. Null is a refusal, not an omission.
     * @return [HANDLE], or 0L when neither the NPU nor the CPU fallback could be brought up.
     */
    override fun load(modelPath: String, companionPath: String?): Long =
        // ONE serialized hold spans this whole body, and that single hold is LOAD-BEARING for
        // the epoch handshake (4.1 L1; stated here per its m8 rider): stages (6) and (7) are two
        // JNI crossings — the engine's init, then its epoch (QNN: nativeInit, then nativeEpoch) —
        // and it is this one gate hold that makes the pair atomic against every other arm. Split
        // the hold, or move either crossing out of it, and another instance's init can land
        // BETWEEN them, handing this instance the successor's epoch: a stale backend armed with a
        // live identity, the exact shape the epoch exists to refuse. The hold, not source order,
        // carries the invariant.
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

            // (3) 64 KB, not a whole CPU tier. The full loader is byte-identical downstream and is the one
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

            // (5) THE RUNTIME'S OWN STAGING — THIS FAMILY'S ROW, through the engine (P1a). For the
            // QNN engine this is the DSP-side skel stage, moved whole into QnnAsrEngine.prepare
            // with its reasoning: the family row's one skel, staged into filesDir through the
            // marker fast path, before the dlopen that makes FastRPC look for it. For the LiteRT
            // engine (P2-7) it is the MediaTek dispatch, staged into the directory the LiteRT
            // environment is bound to at init. Here, after every cheap refusal and before the
            // expensive stage, because the first arm of it writes ~18 MB (the skel) or ~400 KB
            // (the dispatch) — and its refusal leaves through the same funnel as every stage in
            // this file, under the stage's own wire word.
            engine.prepare(appContext, family)?.let { refusal ->
                return@serialized fallBackToCpuTier(refusal.stage.wire, refusal.detail)
            }

            // (6) THE EXPENSIVE STAGE, and its cost is now MEASURED ON THE SHIPPED TIER rather
            // than estimated from the spike (4.4.0 startup amendment S2). It used to read
            // "342 MiB and ~525 ms"; the ~525 ms was the 127 MB ENCODER-ONLY spike harness
            // (docs/superpowers/research/2026-08-28-npu-spike-g1-results.md:19), not the shipped
            // pair, and it understated this stage by roughly FIVE and the whole load by roughly
            // EIGHT. The real numbers, from an unmodified shipped app on the Fold6
            // (C:/Users/bastr/.androidbuild/capture-yt-84-flatline-0903-1936.txt,
            // 19:29:48.854 -> 52.961, whisper_large_v3_turbo_quantized_* on HTP_QTI_AISW):
            //
            //   nativeInit ALONE (this stage):            ~2,480 ms
            //     backendCreate 1 ms, deviceCreate 172 ms, encoder blob read 674 ms (775,831,552 B
            //     @ ~1,151 MB/s), encoder contextCreateFromBinary 1,234 ms, decoder blob read
            //     234 ms (295,854,080 B), decoder contextCreateFromBinary 71 ms, bind/quant/
            //     alias-guard/epoch ~93 ms
            //   load() END TO END, dlopen -> "session armed":  4,107 ms
            //     the balance is stage (2)'s mel-only context + the 563 KB vocabulary + asset
            //     staging, which the same capture times at 1,592 ms
            //
            // Resident cost: ~342 MiB is the `npu` pair; `npu-turbo`'s two context binaries are
            // 740 MB + 282 MB = ~1.02 GiB, which is why onTrimMemory frees this at all.
            //
            // THE NUMBER IS LOAD-BEARING, not decoration: it is the stated reason this stage is
            // ordered LAST, and at 4.1 s it is also the entire reason 4.4.0 needed a startup ring
            // — the capture seam could not keep waiting on it (audio/StartupRing.kt).
            //
            // THROUGH THE ENGINE (P1a). QnnAsrEngine.init is nativeInit — wrapped in the same
            // runCatching, for the libqnnasr-absent reason recorded there, and fed the five
            // varying scalars off THIS spec, the constructor's required one — then the encoder's
            // input quant pair, read once per arm. The spec travels as the object, never as
            // scalars assembled here; the two files travel named, encoder first.
            engine.init(
                spec,
                NpuEngineFiles(encoderPath = modelPath, decoderPath = companionPath),
                NpuEngineDirs(libDir = libDir(), filesDir = appContext.filesDir.absolutePath),
            )?.let { refusal ->
                return@serialized fallBackToCpuTier(refusal.stage.wire, refusal.detail)
            }

            // (7) THE ARMING EPOCH, read as soon as the engine's init has answered null — the first
            // moment this file knows a session exists — and BEFORE `armed = true` below. Between
            // the runtime's own arm (QNN: nativeInit) and this line the session is the ENGINE's to
            // clean up: its init releases what it armed on every exit that is not a success, so
            // no refusal and no Throwable can leave this file holding epoch 0 beside a live
            // session. The order here is an invariant, not a tidiness: between these two
            // statements this instance would be a live backend holding epoch 0 — i.e. one whose
            // release names no session — which is precisely the unguarded shape L1 removed. Native
            // refuses 0 outright, so the window is not dangerous; it is simply a state that must
            // not exist.
            armedEpoch = engine.epoch()

            // Q10a-D1. The decoder runs and emits nothing, and every hypothesis about why is a
            // statement about numbers only the native loop can see. Armed here, after init, because
            // there is no session to instrument before it — and with BuildConfig.DEBUG, so the
            // owner's debug build talks and a release build does not. The decision is this
            // file's; the engine hands it to its runtime unchanged.
            engine.setDiag(com.whispereverywhere.BuildConfig.DEBUG)

            melBuffer = NpuQuantize.newMelFloatBuffer(spec)
            armed = true
            HANDLE
        }

    // ---------------------------------------------------------------- transcribe

    /**
     * One 30 s segment: mel, encode (the engine quantises it first when its runtime needs a
     * quantised block, as QNN's does), resolve the language, decode, detokenise.
     *
     * [useVad] is IGNORED, and that is correct rather than unimplemented: the encoder's
     * `input_features` is a fixed `[1,melBins,3000]`, so the window is 30 s whatever the VAD would have
     * said, and trimming the samples before the mel would only move silence from one end of a
     * zero-padded window to the other.
     *
     * Held under [NativeComputeGate] end to end — **including the fallback short-circuit**, which
     * is inside the hold rather than in front of it. `pcmToMel` REPLACES the mel context's internal
     * state, so two segments on this one handle would race each other; the engine's native session
     * is a single process-global behind its own mutex and must not see an encode and a decode
     * interleaved; and the routing decision itself is shared mutable state, so reading it outside
     * the hold is how two threads both decide to fall back and one 60-874 MB whisper context is
     * leaked. The lock is reentrant and the delegate takes it again, which costs nothing.
     */
    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
        return NativeComputeGate.serialized {
            // FIRST, above every path INCLUDING the fallback short-circuit below — the mirror of
            // `WhisperNativeBackend`'s reset at the top of its own hold, and for the same reason:
            // a sentence read may only ever be answered by the decode that completed in THIS
            // hold. See the slot's declaration. Never move these below anything.
            lastSentencesArr = IntArray(0)
            lastSentencesCtx = 0L
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
            // Guarded on `armedEpoch != 0L` so an instance that never armed does not touch its
            // engine's runtime — for QNN, does not dlopen libqnnasr.so — merely to find that out;
            // and read ONCE, into a local, so the number the refusal reports is the number the
            // branch was taken on rather than a second reading of a value that has no reason to
            // agree.
            if (armedEpoch != 0L) {
                val liveEpoch = engine.epoch()
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
            val bpe = decoder
            if (!armed || mel == null || bpe == null) {
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
            //   - BEFORE the engine's encode — for QNN, before melToU16, which now lives inside
            //     it. This must measure whisper's floats, not anything the quantiser has been near;
            //     a bisector that cannot separate the mel from the quantisation is not a bisector.
            // A fresh asFloatBuffer() view, read absolutely, so the shared direct buffer handed on
            // to the engine (for QNN: melToU16, then nativeEncode) keeps its position untouched.
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

            // THE ENCODE, THROUGH THE ENGINE (P1a), handed the float mel exactly as pcmToMel wrote
            // it. For QNN it is the block that stood here until the seam, moved whole into
            // QnnAsrEngine.encode: melToU16 against the quant pair read once at arm (the asset's
            // own metadata, never literals), the Q10a-D2 `melprobe` line under BuildConfig.DEBUG,
            // then nativeEncode — the same statements, in the same order, printing the same lines.
            // A refusal leaves through the funnel under its own stage's wire word, and this
            // segment still runs, on the CPU tier.
            engine.encode(mel)?.let { refusal ->
                return@serialized fallBackAndRun(refusal.stage.wire, refusal.detail, samples, lang, useVad)
            }
            val encodeMs = SystemClock.elapsedRealtime() - encodeStart

            val decodeStart = SystemClock.elapsedRealtime()

            // The detect pass runs ONLY when the user has not chosen — one extra graphExecute,
            // ~4.5 ms against a ~405 ms encode. It does not consume the encode: this same encoded
            // segment is what the engine's decodeSegment reads next, in place.
            val detected = if (lang == null) engine.detectLanguage() else DETECT_NOT_RUN
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
            // 4.3.1 A: the guards travel as data, like the suppress lists; the six stats come back
            // in this OUT array and the no-speech decision is taken HERE, from them.
            val stats = NpuDecodeStats.newArray()
            val written = engine.decodeSegment(
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
                NpuDecodePolicy.CYCLE_MAX_DISTINCT,
                stats,
            )
            if (written < 0) {
                return@serialized fallBackAndRun("decode", engine.lastError(), samples, lang, useVad)
            }
            val decodeMs = SystemClock.elapsedRealtime() - decodeStart

            // whisper.cpp:7865 — the model said "silence" AND was unsure of its words: type nothing.
            // A blank reaches LocalWhisperEngine's existing blank branch and resolves EmptyExpected,
            // the same outcome the CPU tier's VAD-empty takes. Decided BEFORE detokenising so a
            // segment this gate blanks never exists as a String at all.
            val noSpeech = NpuDecodePolicy.isNoSpeech(stats[NpuDecodeStats.NO_SPEECH_PROB], stats[NpuDecodeStats.AVG_LOGPROB])

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
            val decoded = if (noSpeech) "" else bpe.decode(out.copyOf(written))

            // THE STOCK-PHRASE BLOCKLIST (4.3.2, Layer 2) — the second blank, at the SAME site with
            // the SAME outcome. The no-speech rule above is whisper.cpp's and has a known hole: the
            // stock silence hallucinations ("Thank you for watching", the Chinese Amara credit, a
            // lone "you") decode CONFIDENT, so lp >= -1.0 and the rule keeps them. What they still
            // carry is an ELEVATED no-speech vote, and HallucinationPolicy blanks an EXACT seed
            // phrase only while nsp is over STOCK_PHRASE_NSP_MIN — a user who genuinely says
            // "thank you" has nsp ~0 and is typed. Applied to the decoded String, which is never
            // logged: the diag line below says `blank=stock`, not what was blanked. NPU tier only;
            // the CPU tier has no per-segment nsp to gate on and keeps its pre-encode VAD filter.
            val stock = !noSpeech && HallucinationPolicy.shouldBlank(decoded, stats[NpuDecodeStats.NO_SPEECH_PROB])
            val text = if (stock) "" else decoded
            val blankReason = when {
                noSpeech -> NpuDiag.BLANK_NSP
                stock -> NpuDiag.BLANK_STOCK
                else -> ""
            }

            // 4.11 Task 3 — THE SENTENCE BOUNDS OF THE TEXT THIS CALL IS ABOUT TO RETURN.
            //
            // Guarded on `text.isEmpty()` rather than on the two gate flags, and that is the
            // stronger statement: the byte offsets index into the string this function RETURNS,
            // so publishing them for a segment whose text was blanked — by the no-speech rule or
            // by the stock-phrase blocklist — would hand the splitter offsets into nothing. A
            // decode that genuinely produced no tokens takes the same branch for the same reason.
            //
            // The family is the SPEC's, never a constant: 50364 is <|0.00|> under whisper-small
            // and <|notimestamps|> under large-v3, so a fixed timestamp base shifts every
            // sentence in the chunk by one slot and nothing anywhere can see it.
            //
            // `runCatching` on the same rule `WhisperNativeBackend.captureGeometry` states — a
            // label is worth less than a sentence. This is a refinement below delivered text and
            // it must never be able to fail a transcribe that has already succeeded; a chunk
            // that loses its bounds keeps 4.10.1's one coarse label, which is the behaviour the
            // empty array means everywhere else in this seam.
            //
            // PAYLOAD FIRST, TAG LAST. See the slot's declaration.
            lastSentencesArr = if (text.isEmpty()) IntArray(0) else runCatching {
                NpuSentences.of(out.copyOf(written), spec.tokens, bpe::decode)
            }.getOrDefault(IntArray(0))
            lastSentencesCtx = ctx

            // `.reportable`, NEVER `.code`. A (locale) or (fallback) resolution is a guess this
            // tier made, and the engine feeds whatever crosses this seam to LanguagePin, which
            // latches the first usable code for the whole session and never revises it. Reporting
            // a guess there turns one failed detect pass on segment 1 into a session pinned to
            // English — after which the detect pass stops running at all and the diag line prints
            // the BARE `en` note that means "the user chose this". See LangResolution.reportable.
            lastReportedLanguage = resolution.reportable

            // ONE line per segment. `tokens` is native's count even when the gate blanked the text,
            // so the line still says what the decoder produced; `nsp`/`lp` say why it was dropped.
            android.util.Log.i(
                NpuDiag.TAG,
                NpuDiag.line(
                    encodeMs = encodeMs,
                    decodeMs = decodeMs,
                    tokens = written,
                    langNote = resolution.note,
                    noSpeechProb = stats[NpuDecodeStats.NO_SPEECH_PROB],
                    avgLogprob = stats[NpuDecodeStats.AVG_LOGPROB],
                    entropy = stats[NpuDecodeStats.ENTROPY],
                    rung = stats[NpuDecodeStats.RUNG].toInt(),
                    terminator = NpuDecodeStats.terminatorName(stats[NpuDecodeStats.TERMINATOR]),
                    blank = blankReason,
                ),
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

    /**
     * The CPU tier's segment geometry AFTER a fallback; null while the NPU is live (4.10 speaker
     * labels) — the third member to follow [lastSegmentStats]' delegation, for the same reason and
     * with the same ungated-pair-read argument, which is recorded in full on that member.
     *
     * DELEGATION, NOT EMPTY ARRAYS, and the distinction is the behaviour: a declined session is
     * running whisper.cpp with its native VAD filter, so its chunks have real geometry and the
     * user gets speaker labels. Answering empty arrays here would silently make speaker detection
     * a tier-dependent feature that stops working after a mid-session decline, with nothing in the
     * UI naming why — the exact class of silent post-decline loss 4.1 L7 closed for the other two
     * members.
     *
     * The live-NPU arm answers null through the safe-call: this path runs its own encoder and
     * decoder on the HTP with no whisper.cpp VAD filter anywhere in it, so there is no geometry —
     * and null, not a pair of empty arrays, is what says so. Empty arrays would claim a VAD ran
     * and found no speech (see [SegmentGeometry]).
     */
    override fun lastGeometry(ctx: Long): SegmentGeometry? =
        fallbackBackend?.lastGeometry(fallbackCtx)

    /**
     * @see WhisperBackend.lastSentences — and this is the backend the member exists for (4.11
     * Task 3). The bounds of the segment the NPU arm last decoded, or EMPTY.
     *
     * **NOT DELEGATED, unlike its three neighbours, and the asymmetry is the point.** The CPU
     * tier answers this question through [lastGeometry] instead: whisper.cpp publishes segment
     * bounds and, since Task 1, per-TOKEN times, which are strictly finer than sentences. After
     * a decline `publishesGeometry` flips to true, the engine takes the geometry route, and this
     * member is not read at all — so delegating it would be a second, coarser answer to a
     * question already better answered, reachable only if that routing were wrong. An empty
     * array here says what is true after a decline: this arm decoded nothing.
     *
     * The `ctx != 0L` guard is [WhisperNativeBackend]'s, for its reason: 0L is a failed load's
     * value, and without the guard a caller passing it would match an untouched tag.
     *
     * DELIBERATELY NOT under [NativeComputeGate], on exactly the argument [lastSegmentStats]
     * records: two volatile reads of a Kotlin snapshot touch no native memory, and taking the
     * FAIR gate here would park the segment's resolution behind an in-flight batch chunk that
     * would have re-tagged this slot before the wait ended.
     */
    override fun lastSentences(ctx: Long): IntArray =
        if (ctx != 0L && ctx == lastSentencesCtx) lastSentencesArr else IntArray(0)

    /**
     * @see WhisperBackend.publishesGeometry — and this is the backend the member exists for
     * (4.10, round 1 of review).
     *
     * `fallbackBackend != null` is the SAME guard [lastGeometry] delegates through, read as a
     * structural fact instead of per chunk: while the NPU arm is live nothing under this object
     * will ever publish geometry, for any chunk, because no whisper.cpp VAD filter runs; after a
     * decline the delegate publishes it for every chunk. There is no third state, which is why
     * the guard alone can answer, and it is the exact inverse of [detectsPerUtterance]'s.
     *
     * `LocalWhisperEngine` reads THIS, not "was this chunk's geometry null", to decide whether a
     * chunk takes the whole-chunk speaker route. The difference is the CPU tier: a whisper.cpp
     * chunk whose geometry snapshot was lost answers null too, and it must keep 4.9's no-labels
     * answer rather than collapse to one speaker.
     *
     * The read is of the guard alone and ungated, on the same argument [detectsPerUtterance]
     * records: it touches nothing native, decides no routing, and a stale answer costs at most
     * one segment — labelled per window when it could have been labelled per chunk, or the
     * reverse, on the single segment that straddles the decline.
     */
    override val publishesGeometry: Boolean get() = fallbackBackend != null

    // ---------------------------------------------------------------- teardown and fallback

    /**
     * Frees everything the NPU tier holds: the engine's session (for QNN, the contexts and the
     * sustained power vote) and its per-arm state, then the mel context, then the buffer and the
     * decoder.
     *
     * `runCatching` on both native calls because this runs on failure paths, where a partially
     * armed session is the normal case and a teardown that throws would strand the rest of it.
     */
    private fun releaseNpuResources() {
        // NAMED, and only when there is something to name.
        //
        // `armedEpoch` rather than a fresh epoch read: a fresh read names whatever is live NOW,
        // which on the losing interleaving is the SUCCESSOR's session — the unguarded release
        // with an argument added to it. Native ignores an epoch that is not the live one, so a
        // stale instance's teardown becomes a WE-DIAG line instead of a destroyed session.
        //
        // And the guard, which closes Q6 M1 — its claim stated NARROWLY (4.2 F2, the L1 m2
        // correction): `armedEpoch != 0L` is the engine-side fact; `melCtx != 0L` is a
        // WHISPER-side fact and proves nothing about the engine. What the disjunction guarantees
        // is only that the refusals reached before ANY native touch — companion and mel-donor, the
        // every-session path of every device with no ggml model installed — never reach the
        // engine (for QNN, never dlopen ~25 MiB of Qualcomm runtime) on their way out to release a
        // session that was never created. A decline BETWEEN the mel arm and the engine's init
        // (vocab, skel) still takes the release call holding only whisper-side state: that pays
        // the dlopen for a release native refuses (epoch 0 is never live), which is bounded and
        // deliberately preferred over a cleverer test that could learn to skip a real release.
        if (armedEpoch != 0L || melCtx != 0L) {
            runCatching { engine.release(armedEpoch) }
        }
        armedEpoch = 0L
        if (melCtx != 0L) {
            runCatching { WhisperNative.free(melCtx) }
            melCtx = 0L
        }
        melBuffer = null
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
        // 4.11 Task 3: the sentence slot dies with the session too — TAG FIRST here, which is the
        // publish order inverted, so no reader can see a matching tag beside an array this
        // teardown has already abandoned. Nothing may outlive the session that decoded it: the
        // bounds index into a string only that session produced.
        lastSentencesCtx = 0L
        lastSentencesArr = IntArray(0)
    }

    /**
     * A stage declined: **release the NPU FIRST, then bring up the CPU tier** (I11).
     *
     * The ordering is the whole point of this function existing at all. Loading a whisper CPU
     * tier (264 MB for the 4.7 default, up to 874 MB) while 376 MiB of NPU contexts and a
     * sustained power vote are still held is a ~660 MB+
     * transient on the exact path that exists to be safe, and it would be an LMK kill on the
     * devices most likely to reach it. Two statements, one order, pinned as an ORDER invariant by
     * `NpuNativeContractTest` — presence alone would be satisfied by swapping them.
     *
     * **AT MOST ONCE per session, and the guard is the first statement.** A second entry is a no-op
     * that returns the live fallback rather than a second `WhisperNativeBackend.load` — which is a
     * decision, so here is the reasoning. Without the guard, a second entry overwrites [fallbackCtx]
     * with a fresh handle and the previous whisper context — a whole CPU tier, 60-874 MB — is
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
         * The only non-zero handle this backend returns. There is one native session per process
         * and it is the engine's, so a handle has nothing to identify; 1L/0L simply matches
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
         * Whether the npu tier may be OFFERED on this device: the right silicon, and a runtime that
         * actually loads — the QNN stack on a Qualcomm row, the Neuron driver on a MediaTek one.
         *
         * **The SoC gate is first and the short circuit is load-bearing.** The probe —
         * [QnnAsrEngine.probe], which is `nativeProbe` — dlopens `libQnnSystem.so` and
         * `libQnnHtp.so`; running it on a Tensor, an Exynos or a MediaTek is pointless work to
         * reach a foregone answer, and asking a Snapdragon 7-series would get a yes — the probe
         * reports whether the HTP *stack* is present, and cannot tell one Hexagon apart from
         * another. Only [NpuGate] can, so it decides first and the probe merely confirms that the
         * stack it needs is loadable.
         *
         * **Vendor-dispatched since P2** ([NpuGate.runtimeAvailable], a truth table `NpuGateTest`
         * executes). A Qualcomm row asks the QNN engine's probe, a throwaway [QnnAsrEngine] that
         * holds no state until it is armed — exactly the P1a question, deferred into the lambda
         * that only the Qualcomm arm invokes. A MediaTek row reads the driver check's STORED
         * verdict ([NpuApuDriverCheck.verdict], filled at process start off Main) and never
         * dlopens anything here: neither the QNN stack, which is not its chip's, nor its own
         * adapter, whose walk holds bionic's loader lock on the chooser's path. An unknown verdict
         * (not probed yet) answers false — which is why the MediaTek half of the caller's memo is
         * re-read rather than memoised (`WhisperEverywhereApp.npuCapableDevice`). The verdict is
         * deferred too (P2-7), into the lambda only the MediaTek arm invokes, so a Qualcomm
         * process never creates the driver check's flow on this path.
         *
         * `runCatching` covers [LinkageError] and everything downstream of it: on a build where
         * the proprietary QNN headers were unavailable, `libqnnasr.so` is deliberately absent, the
         * first touch of `QnnAsrNative` throws `UnsatisfiedLinkError` and every touch afterwards
         * throws `ExceptionInInitializerError` instead. Both mean the same thing here — no tier.
         *
         * @param socModel `Build.SOC_MODEL`, or null below API 31. The version guard lives in the
         *        CALLER because `minSdk` is 26; see [NpuGate].
         * @param socManufacturer `Build.SOC_MANUFACTURER`, or null below API 31.
         */
        fun isTierAvailable(socModel: String?, socManufacturer: String?, libDir: String): Boolean =
            NpuGate.isSocSupported(socModel, socManufacturer) &&
                NpuGate.runtimeAvailable(
                    family = NpuGate.familyFor(socModel, socManufacturer),
                    qnnProbePasses = {
                        runCatching { QnnAsrEngine().probe(libDir).isEmpty() }.getOrDefault(false)
                    },
                    apuVerdict = { NpuApuDriverCheck.verdict.value },
                )

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
         * @param live what the engine's `epoch()` answered, i.e. the session that exists now.
         */
        private fun sessionReplacedDetail(mine: Long, live: Long): String =
            "this backend's session ($mine) was replaced by a newer arm ($live)"
    }
}
