package com.whispereverywhere.transcription

import android.content.Context
import android.os.SystemClock
import com.whispereverywhere.npu.NpuDecodePolicy
import com.whispereverywhere.npu.NpuDiag
import com.whispereverywhere.npu.NpuGate
import com.whispereverywhere.npu.NpuQuantize
import com.whispereverywhere.npu.NpuTierStatus
import com.whispereverywhere.npu.QnnAsrNative
import com.whispereverywhere.npu.WhisperBpeDecoder
import com.whispereverywhere.whisper.WhisperNative
import java.nio.ByteBuffer
import java.util.Locale

/**
 * The 4.0 NPU tier behind the ordinary [WhisperBackend] seam: mel on the CPU, encoder and decoder
 * on the Hexagon, and one CPU-tier fallback that is never silent.
 *
 * ```
 * load(encoder, decoder)   initMelOnly (64 KB)  ->  vocab  ->  nativeInit (376 MiB)
 * transcribe(samples)      pcmToMel -> nativeInputQuant -> melToU16 -> nativeEncode
 *                          -> nativeDetectLanguage -> nativeDecodeSegment -> WhisperBpeDecoder
 * release(ctx)             nativeRelease + WhisperNative.free
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
) : WhisperBackend {

    // ---------------------------------------------------------------- session state

    /** The ~64 KB mel-only whisper context, or 0. Held for the tier's lifetime. */
    private var melCtx: Long = 0L

    /** Built once at [load] — 563 KB of JSON and 51,865 strings is not a per-segment cost. */
    private var decoder: WhisperBpeDecoder? = null

    /** 960,000 B direct, native order. Reused; [WhisperNative.pcmToMel] overwrites all of it. */
    private var melBuffer: ByteBuffer? = null

    /** 480,000 B direct, native order — the `ufixed16` block `nativeEncode` copies in. */
    private var quantBuffer: ByteBuffer? = null

    /** True once `nativeInit` has succeeded and every artefact is in hand. */
    private var armed: Boolean = false

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
     * `@Volatile` AND every read and write of them happens inside [NativeComputeGate], because
     * they need two different guarantees. The gate gives mutual exclusion, so the pair cannot be
     * half-updated while another thread routes on it; `@Volatile` gives publication, so the pair a
     * reader sees is the pair a writer wrote. Neither alone is enough and neither is redundant —
     * dropping the gate lets two threads both observe `null` and both fall back (leaking a whole
     * 60-190 MB whisper context), and dropping `@Volatile` lets a reader see a non-null backend
     * beside a stale `0L` handle and silently lose a segment on `transcribe(0L, …)`.
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
     * **The setter publishes to [NpuTierStatus] (Q8).** Nothing in the app holds this instance —
     * the engine layer builds it per session (Q9) and a Compose screen can never reach it — so the
     * card subscribes to the process-scoped mirror instead. It is done HERE, in the setter, and not
     * at the two assignment sites, for the same reason `notifyModelInstalled` is one function: a
     * stage that declines cannot set the reason and forget to announce it, including a stage nobody
     * has written yet.
     */
    @Volatile
    var unavailableReason: String? = null
        private set(value) {
            field = value
            NpuTierStatus.publish(value)
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
     * Arms the tier: mel context, vocabulary, then the two QAIRT context binaries.
     *
     * **The order is the design.** `initMelOnly` is first because it is ~64 KB and its failure is a
     * clean "tier unavailable" **before** 358 MB of NPU assets have been touched; the vocabulary is
     * second for the same reason (563 KB, and a decoder that failed to construct does not exist, so
     * there is nothing to run degraded); `nativeInit` — the expensive, ~342 MiB, ~525 ms one — is
     * last. Every failure before it costs nothing.
     *
     * Must not run on Main: `nativeInit` reads 342 MB from disk and deserialises it.
     *
     * @param modelPath the encoder context binary.
     * @param companionPath the decoder context binary. Null is a refusal, not an omission.
     * @return [HANDLE], or 0L when neither the NPU nor the CPU fallback could be brought up.
     */
    override fun load(modelPath: String, companionPath: String?): Long =
        NativeComputeGate.serialized {
            // A re-load with a previous session still held is the co-residency hazard in miniature:
            // whichever way round it happened, two model-sized things would be resident at once.
            releaseEverything()
            unavailableReason = null

            // (1) The mel donor. Null here means the tier cannot come up AND cannot fall back —
            // one file answers both, so its absence is total. See ModelPathProvider.cpuTierModelPath.
            val donorPath = paths.cpuTierModelPath()
                ?: return@serialized fallBackToCpuTier(
                    "mel-donor", "no installed 80-bin whisper model to take the mel filterbank from"
                )

            // (2) 64 KB, not 190 MB. The full loader is byte-identical downstream and is the one
            // mistake nothing but a source pin can catch — see the class KDoc.
            melCtx = WhisperNative.initMelOnly(donorPath)
            if (melCtx == 0L) {
                return@serialized fallBackToCpuTier(
                    "mel-init", "initMelOnly found no usable 80-bin filterbank in $donorPath"
                )
            }

            // (3) The vocabulary. IOException = absent from the APK; IllegalStateException = present
            // and wrong (not JSON, wrong entry count, a token outside the byte-level alphabet).
            // Those two cover every way this asset can fail, and all of them fire here rather than
            // under a user who has already pressed record.
            decoder = try {
                WhisperBpeDecoder.fromJson(
                    appContext.assets.open(WhisperBpeDecoder.ASSET_NAME)
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                )
            } catch (cause: java.io.IOException) {
                return@serialized fallBackToCpuTier("vocab", "${cause.javaClass.simpleName}: ${cause.message}")
            } catch (cause: IllegalStateException) {
                return@serialized fallBackToCpuTier("vocab", "${cause.javaClass.simpleName}: ${cause.message}")
            }

            // (4) The paired artifact. A two-artifact tier with one artifact is not a degraded
            // tier; it is an uninstalled one.
            if (companionPath.isNullOrBlank()) {
                return@serialized fallBackToCpuTier(
                    "companion", "the npu tier needs both context binaries and the decoder half is missing"
                )
            }

            // (5) 342 MiB and ~525 ms. runCatching, not try/catch on a named type: libqnnasr.so is
            // absent by design on builds where the proprietary QNN headers could not be fetched, and
            // the FIRST touch throws UnsatisfiedLinkError while every touch after it throws
            // ExceptionInInitializerError / NoClassDefFoundError, because the <clinit> has already
            // failed. Catching the first one by name would crash the tier-unavailable path on the
            // second call rather than the first.
            val initError = runCatching {
                QnnAsrNative.nativeInit(modelPath, companionPath, libDir())
            }.getOrElse { cause -> "init: ${cause.javaClass.simpleName}: ${cause.message}" }
            if (initError.isNotEmpty()) {
                return@serialized fallBackToCpuTier("init", initError)
            }

            melBuffer = NpuQuantize.newMelFloatBuffer()
            quantBuffer = NpuQuantize.newInputFeaturesBuffer()
            armed = true
            HANDLE
        }

    // ---------------------------------------------------------------- transcribe

    /**
     * One 30 s segment: mel, quantise, encode, resolve the language, decode, detokenise.
     *
     * [useVad] is IGNORED, and that is correct rather than unimplemented: the encoder's
     * `input_features` is a fixed `[1,80,3000]`, so the window is 30 s whatever the VAD would have
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
            if (!WhisperNative.pcmToMel(melCtx, samples, mel)) {
                return@serialized fallBackAndRun("mel", "pcmToMel refused or failed", samples, lang, useVad)
            }

            // THE STRIDE BISECTOR (4.0, Q9 fix round, I2). Three row sums, 9,000 float adds against
            // a ~405 ms encode, and `row40 == row79` is the copy-stride defect stated in one glance
            // — see NpuDiag.mel. Its POSITION is the invariant, on both sides:
            //   - AFTER pcmToMel returned TRUE. The mel buffer is reused across segments, so a line
            //     emitted above this guard would print the PREVIOUS segment's spectrogram and
            //     attribute it to a segment whose mel was never computed.
            //   - BEFORE melToU16. This must measure whisper's floats, not anything the quantiser
            //     has been near; a bisector that cannot separate the mel from the quantisation is
            //     not a bisector.
            // A fresh asFloatBuffer() view, read absolutely, so the shared direct buffer handed to
            // melToU16 and then to nativeEncode keeps its position untouched.
            val melView = mel.asFloatBuffer()
            android.util.Log.i(
                NpuDiag.TAG,
                NpuDiag.mel(
                    NpuQuantize.melRowSum(melView, 0),
                    NpuQuantize.melRowSum(melView, 40),
                    NpuQuantize.melRowSum(melView, NpuQuantize.MEL_BINS - 1),
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
                mel.asFloatBuffer(), quant[0], quant[1].toInt(), quantised.asShortBuffer()
            )

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
                NpuDecodePolicy.resolveLangToken(lang, detected, Locale.getDefault().toLanguageTag())
            } catch (cause: IllegalArgumentException) {
                // An explicit selection this asset cannot name. Refused rather than coerced to
                // English — and handed to the CPU tier, which accepts language strings this one
                // cannot and is therefore a genuine repair rather than a shrug.
                return@serialized fallBackAndRun("lang", "${cause.message}", samples, lang, useVad)
            }

            val prompt = NpuDecodePolicy.promptTokens(resolution.token)
            val out = IntArray(NpuDecodePolicy.maxTokensFor(prompt.size))
            val written = QnnAsrNative.nativeDecodeSegment(
                prompt,
                NpuDecodePolicy.suppressList,
                NpuDecodePolicy.beginSuppressList,
                out.size,
                out,
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
            // outside 0 until 51865 was written, which is a contract breach between this file and
            // native — not an asset problem, and not something to bury in the fallback path.
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
     */
    override fun detectedLanguage(ctx: Long): String? {
        return NativeComputeGate.serialized {
            fallbackBackend?.let { return@serialized it.detectedLanguage(fallbackCtx) }
            lastReportedLanguage
        }
    }

    // ---------------------------------------------------------------- teardown and fallback

    /**
     * Frees everything the NPU tier holds: the QNN contexts and the sustained power vote, then the
     * mel context, then the buffers and the decoder.
     *
     * `runCatching` on both native calls because this runs on failure paths, where a partially
     * armed session is the normal case and a teardown that throws would strand the rest of it.
     */
    private fun releaseNpuResources() {
        runCatching { QnnAsrNative.nativeRelease() }
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
    }
}
