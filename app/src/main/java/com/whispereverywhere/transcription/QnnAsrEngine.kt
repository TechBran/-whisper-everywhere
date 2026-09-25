package com.whispereverywhere.transcription

import android.content.Context
import com.whispereverywhere.npu.NpuAssetStage
import com.whispereverywhere.npu.NpuDiag
import com.whispereverywhere.npu.NpuModelSpec
import com.whispereverywhere.npu.NpuQuantize
import com.whispereverywhere.npu.NpuRuntimeNeeds
import com.whispereverywhere.npu.NpuSocFamily
import com.whispereverywhere.npu.NpuStage
import com.whispereverywhere.npu.QnnAsrNative
import com.whispereverywhere.npu.Refusal
import java.nio.ByteBuffer

/**
 * The Qualcomm engine behind [NpuAsrEngine]: QAIRT (QNN) on the Hexagon, through [QnnAsrNative]
 * (P1a — the engine seam; design `docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md`
 * §2.4).
 *
 * **It owns what was QNN-shaped in [NpuWhisperBackend]**, moved here verbatim with the reasoning
 * that was recorded beside it: the DSP-side skel stage ([prepare]); `nativeInit` and the encoder's
 * input quantisation, read once per arm ([init]); and the `ufixed16` block — `melToU16` into this
 * engine's own buffer, the Q10a-D2 `melprobe` line, `nativeEncode` ([encode]). Every other member
 * hands its call to [QnnAsrNative] unchanged, and `qnn_asr.cpp` is untouched by the seam: a device
 * prints every line it printed at 4.15, in the same order, with the same values.
 *
 * **One read moved, and it is the only change in what the device is asked.** `nativeInputQuant`
 * used to be read per segment, between the `mel:` line and `melToU16`. Its two numbers are cached
 * natively at `nativeInit` and never change within a session, and it prints nothing on success, so
 * reading them once in [init] feeds every segment the same values and leaves every line as it was.
 * What changes is only WHEN its (practically unreachable) refusal would fire: the `quant` stage
 * declines at arm now, directly after `init`, instead of on the first segment — which is where
 * `NpuStage` declares it. Because the backend learns the epoch only when [init] answers null,
 * [init] owns the session between its `nativeInit` and that answer: the quant buffer is allocated
 * BEFORE `nativeInit`, and everything after it runs under one `finally` that releases this arm's
 * session on every exit that is not a success.
 *
 * **No JVM test may name this class**: it touches [QnnAsrNative], whose `init` block runs
 * `System.loadLibrary("qnnasr")`. Its invariants are pinned as SOURCE TEXT — the skel stage in
 * `NpuSkelPackagingTest`, the melprobe order in `NpuDiagTest`, every [QnnAsrNative] entry point in
 * `NpuNativeContractTest` — and it is a declared input of the test task.
 *
 * One engine per backend instance: the native session is process-global, but the quant pair and
 * the quantised buffer are this arm's own, dropped by [release] whatever native decides about the
 * epoch it is handed.
 */
class QnnAsrEngine : NpuAsrEngine {

    /** The spec this engine last armed with, or null — the shape [encode] quantises against. */
    private var armedSpec: NpuModelSpec? = null

    /** `[scale, zeroPoint]` exactly as `nativeInputQuant` answered it at [init], or null. */
    private var inputQuant: FloatArray? = null

    /** `spec.inputFeaturesBytes` direct, native order — the `ufixed16` block `nativeEncode` copies in. */
    private var quantBuffer: ByteBuffer? = null

    override fun probe(dispatchOrLibDir: String): String = QnnAsrNative.nativeProbe(dispatchOrLibDir)

    override fun prepare(appContext: Context, family: NpuSocFamily): Refusal? {
        // THE ROW'S QNN NEEDS, or no stage at all (P2 — the census reshape moved the HTP
        // version and the skel off the row and into its sealed `runtime`). A row of another
        // vendor carries no DSP-side skel, and handing one to THIS engine is a wiring fault —
        // yet the selector builds the QNN engine for every family until P2-7's vendor switch,
        // so whatever keeps such a row from routing here is a property of other objects. Safe
        // by a property of a different object is the shape this stack has paid for twice, so
        // the engine refuses it by name, at the stage it is — its own staging — before
        // anything QNN is touched: no skel is written and libQnnHtp.so is never dlopened on a
        // chip that is not a Hexagon. The `when` is exhaustive on purpose: a third runtime
        // variant fails to compile HERE rather than falling through to a cast.
        val qnn = when (val needs = family.runtime) {
            is NpuRuntimeNeeds.Qnn -> needs
            is NpuRuntimeNeeds.LiteRtMediatek -> return Refusal(
                NpuStage.SKEL,
                "family ${family.id} is a ${family.vendor} row (LiteRT, Neuron " +
                    "${needs.neuronMajor}) with no DSP-side skel — the QNN engine cannot arm it"
            )
        }
        // THE DSP-SIDE SKEL — THIS FAMILY'S ROW, staged from the APK's assets into
        // filesDir (4.1 L6 — the I5 answer; fleet-wide at 4.2 F2). packaging.jniLibs
        // EXCLUDES every census family's skel: under extractNativeLibs="false" a lib/ copy
        // is provably unopenable by the FastRPC loader, which needs a real file on disk and
        // searches only ADSP_LIBRARY_PATH. The extractQnnSkel Gradle task re-materialises
        // the census's skels from the resolved AAR into assets — five of them for six
        // families, one per architecture (qcs8550 and 7gen4 share V73) — asserting the same
        // census-pinned (bytes, sha256) pairs at build time, and this stage copies exactly
        // ONE of them, the row this device resolved to, into filesDir, the FIRST
        // ADSP_LIBRARY_PATH entry, where nativeInit's dlopen of libQnnHtp.so will have
        // FastRPC find it. The three values are the family row's QNN needs — the census is
        // their one home, and a skel staged under another family's values is precisely the
        // FastRPC mystery the required `family` parameter exists to prevent. The RETURN PATH
        // IS DELIBERATELY UNUSED: FastRPC searches the environment, never Kotlin, so the
        // call's value is its refusal gate.
        //
        // stagedPathWithMarker, NOT stagedPath — the L3 handoff's explicit warning to this
        // stage: the plain arm full-hashes the destination on EVERY arm, free at the
        // melbank's 103 KB and a per-session 12.5-19.7 MB flash read here (V69 12,529,660 B
        // to V81 19,708,192 B at QNN 2.50). The first arm pays one verified write (once per
        // install); every later arm is a handful of stats against the stored marker. A null
        // is a stage refusal like any other stage's: without it the HTP backend would come
        // up and then fail somewhere far less legible, inside FastRPC.
        NpuAssetStage.stagedPathWithMarker(
            appContext,
            qnn.skelAsset,
            qnn.skelBytes,
            qnn.skelSha256,
        ) ?: return Refusal(
            NpuStage.SKEL,
            "${qnn.skelAsset} (family ${family.id}) could not be staged from the APK " +
                "into filesDir — the FastRPC loader would find no DSP-side skel to open"
        )
        return null
    }

    override fun init(spec: NpuModelSpec, files: NpuEngineFiles, dirs: NpuEngineDirs): Refusal? {
        // A fresh arm owns nothing yet, whatever the last one left: a refused init must leave no
        // quant pair behind it for encode to feed a session that does not exist.
        armedSpec = null
        inputQuant = null
        quantBuffer = null

        // THE ARM'S ONE ALLOCATION, BEFORE nativeInit (the P1a review's fix). `spec.inputFeaturesBytes`
        // direct — the ufixed16 block nativeEncode copies in. Allocated after nativeInit, an
        // OutOfMemoryError here would escape load with a live session this arm armed and no one
        // holding its epoch — the backend records it only once init answers null, and the teardown
        // that follows a failed load then names epoch 0, which native refuses. Allocated first, its
        // failure costs nothing: nothing native has been touched.
        val buffer = NpuQuantize.newInputFeaturesBuffer(spec)

        // runCatching, not try/catch on a named type: libqnnasr.so is
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
                files.encoderPath,
                files.decoderPath,
                dirs.libDir,
                spec.melBins,
                spec.decLayers,
                spec.heads,
                spec.tokens.vocab,
                spec.maxPositions,
            )
        }.getOrElse { cause -> "init: ${cause.javaClass.simpleName}: ${cause.message}" }
        if (initError.isNotEmpty()) {
            return Refusal(NpuStage.INIT, initError)
        }

        // FROM HERE THE SESSION IS LIVE, IT IS THIS ARM'S, AND NO ONE ELSE KNOWS ITS EPOCH. The
        // backend reads engine.epoch() only once this function answers null, so every OTHER way out
        // of it — the quant refusal and any Throwable alike (NewFloatArray's out-of-memory inside
        // nativeInputQuant arrives here as a thrown OutOfMemoryError, not as a short array) —
        // releases the session, at ONE site: the finally below, keyed on the one flag that says
        // this arm got as far as success. The epoch it names is read HERE, right after this arm's
        // own nativeInit, and that is the only place in this file where such a read is safe: the
        // backend holds NativeComputeGate across the whole of load, so nothing can arm or release
        // between nativeInit and this block, and "whatever is live now" is this arm. Anywhere
        // else — a teardown on a fresh read — it names whatever is live THEN (the 4.1 L1 F4
        // shape), which is why NpuNativeContractTest counts every `release(` in this file.
        var armedHere = false
        try {
            // NEVER literals. The affine parameters belong to the asset and are read off
            // input_features' own metadata; a hardcoded scale would survive an asset re-export and
            // scale every spectrogram wrongly, which the encoder transcribes fluently into different
            // words with nothing downstream able to notice.
            val quant = QnnAsrNative.nativeInputQuant()
            if (quant.size < 2) {
                // Native answers an EMPTY pair on one path only: the session is not initialised
                // (`quant: session not initialised`). Straight after a successful nativeInit, under
                // the load's gate hold, that cannot happen in practice — and if it did, there would
                // be no session to release: g.epoch is zeroed with g.initialised, so the finally's
                // release names epoch 0 and native refuses it. The branch stays because the refusal
                // is a stage like any other; its cleanup is the finally's, so it is a property of
                // this function rather than of native's failure modes. The error text is read in
                // the return expression, which runs BEFORE the finally releases anything.
                return Refusal(NpuStage.QUANT, QnnAsrNative.nativeLastError())
            }
            armedSpec = spec
            inputQuant = quant
            quantBuffer = buffer
            armedHere = true
        } finally {
            // runCatching: a teardown that throws must neither mask the Throwable already in
            // flight nor turn the quant refusal into one — the backend's releaseNpuResources
            // wraps its release the same way, for the same reason.
            if (!armedHere) runCatching { release(QnnAsrNative.nativeEpoch()) }
        }
        return null
    }

    override fun encode(melF32: ByteBuffer): Refusal? {
        val spec = armedSpec
        val quant = inputQuant
        val quantised = quantBuffer
        if (spec == null || quant == null || quantised == null) {
            return Refusal(
                NpuStage.ENCODE, "encode: this engine holds no armed session — init has not succeeded"
            )
        }

        NpuQuantize.melToU16(
            spec, melF32.asFloatBuffer(), quant[0], quant[1].toInt(), quantised.asShortBuffer()
        )

        // Q10a-D2. The KOTLIN half of the encoder read — the same two sums and the same three
        // cells native is about to report from the buffer the DSP is bound to, computed here
        // from the float mel by an independent route. One reading describes a buffer; the pair
        // decides whether the block the graph sees is the block this code wrote, and in which
        // orientation. Emitted BEFORE nativeEncode so the two halves land adjacent in the log.
        //
        // BuildConfig.DEBUG, like the backend's setDiag: `melBins + melFrames` extra quantise
        // calls — 3,080 on this tier, 3,128 on a 128-bin one — and three float reads, which is
        // nothing against a ~405 ms encode. (The 4.0 comment here said 6,000; it was double the
        // real figure, corrected at 4.1 L3. This file's comments are read as measurements and
        // one wrong measurement devalues the rest.) A release build has no business narrating
        // the spectrogram it is working on.
        if (com.whispereverywhere.BuildConfig.DEBUG) {
            val probe = melF32.asFloatBuffer()
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
            return Refusal(NpuStage.ENCODE, encodeError)
        }
        return null
    }

    override fun decodeSegment(
        prompt: IntArray,
        suppress: IntArray,
        beginSuppress: IntArray,
        maxTokens: Int,
        out: IntArray,
        temperatures: FloatArray,
        entropyThold: Float,
        logprobThold: Float,
        noSpeechThold: Float,
        noSpeechToken: Int,
        cycleMaxDistinct: Int,
        stats: FloatArray,
    ): Int = QnnAsrNative.nativeDecodeSegment(
        prompt,
        suppress,
        beginSuppress,
        maxTokens,
        out,
        temperatures,
        entropyThold,
        logprobThold,
        noSpeechThold,
        noSpeechToken,
        cycleMaxDistinct,
        stats,
    )

    override fun detectLanguage(): Int = QnnAsrNative.nativeDetectLanguage()

    override fun epoch(): Long = QnnAsrNative.nativeEpoch()

    override fun release(epoch: Long) {
        // This arm's own state FIRST: it is this instance's whatever native decides about the
        // epoch, and it must go even when the native call below throws — the backend wraps the
        // release in runCatching for exactly the libqnnasr-absent case.
        armedSpec = null
        inputQuant = null
        quantBuffer = null
        QnnAsrNative.nativeRelease(epoch)
    }

    override fun lastError(): String = QnnAsrNative.nativeLastError()

    override fun setDiag(on: Boolean) = QnnAsrNative.nativeSetDiag(on)
}
