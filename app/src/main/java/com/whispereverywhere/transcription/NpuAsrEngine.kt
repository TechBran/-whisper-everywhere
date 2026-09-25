package com.whispereverywhere.transcription

import android.content.Context
import com.whispereverywhere.npu.NpuModelSpec
import com.whispereverywhere.npu.NpuSocFamily
import com.whispereverywhere.npu.Refusal
import java.nio.ByteBuffer

/**
 * THE ENGINE SEAM (P1a; design `docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md`
 * §2.4) — the narrow interface [NpuWhisperBackend]'s policy body drives an NPU runtime through.
 *
 * **The backend keeps the policy; the engine owns the vendor.** Everything a session DECIDES — the
 * cheapest-refusal-first order of `load`, the arming epoch, the one loud fallback, the mel, the
 * language resolution, the no-speech and stock-phrase blanks, the sentence slot, every diag line —
 * stays in the backend and is written once for every vendor. What differs between runtimes lives
 * behind this interface: how the runtime is staged ([prepare]: QNN's DSP-side skel, LiteRT's
 * dispatch library), how the pair is armed ([init]), and what the encoder is fed ([encode]: QNN
 * quantises the float mel to its `ufixed16` block inside; LiteRT takes the float mel as it is).
 * Two engines: `QnnAsrEngine` over `QnnAsrNative` for Qualcomm rows, and `LiteRtAsrEngine` over
 * `LiteRtAsrNative` for MediaTek rows (P2-7 — P1b's Kotlin half, whose native signatures this
 * interface was shaped against); `NpuBackendSelector` picks one by the census row's vendor.
 *
 * **Two error conventions, one per shape of answer, both the native seams' own.** A stage that can
 * decline answers a [Refusal] — its stage from the closed `NpuStage` set, and a one-line detail the
 * backend carries verbatim into `npu: unavailable stage=… detail=…` — or null when it went through.
 * The three members that answer a NUMBER ([decodeSegment], [detectLanguage]) report failure as a
 * negative one with the words in [lastError], exactly as their native twins do; [probe] keeps the
 * native `""` / `"probe: …"` string, because its reader is the capability gate, not the backend.
 *
 * **Threading and lifecycle are the native seams'**: one process-global session behind one mutex,
 * never touched from Main, every call made under `NativeComputeGate` by the backend that owns the
 * engine; [init] is idempotent, every successful arm issues an epoch ([epoch]), and [release]
 * ignores any epoch that is not the live session's (4.1 L1 — the only thing that makes two
 * npu-class tiers safe to switch between).
 *
 * No member has a default argument, by the house doctrine for `spec` and `family`: a defaulted
 * family stages the default's runtime under another family's silicon, and a defaulted spec arms one
 * model's assets under another model's census.
 */
interface NpuAsrEngine {

    /**
     * The capability probe: does this runtime's stack load on this device? Creates no session.
     *
     * @param dispatchOrLibDir QNN: `applicationInfo.nativeLibraryDir`, where the bundled QNN `.so`
     *        set lives. LiteRT: the staged dispatch directory.
     * @return `""` on a pass, else the refusal text (`"probe: …"`).
     */
    fun probe(dispatchOrLibDir: String): String

    /**
     * The vendor's own staging, run by `load` after every cheap refusal and before [init]: QNN
     * stages THIS FAMILY's DSP-side skel into `filesDir`; LiteRT stages its dispatch library.
     *
     * @param family the census row this device resolved to — required, no default (4.2 F2).
     * @return null when staged, else the refusal (QNN: `skel`; LiteRT: `dispatch`).
     */
    fun prepare(appContext: Context, family: NpuSocFamily): Refusal?

    /**
     * Arms the pair: both model files opened and the session built, against [spec]'s census.
     * The expensive stage (~2.5 s for QNN's npu-turbo pair) and the last one `load` runs; never on
     * Main. Idempotent — a live session is released first — and an arm that refuses OR throws
     * leaves nothing live that this engine armed: the caller learns the new session's epoch only
     * from a null answer, so until then the session is the engine's to clean up.
     *
     * @param spec the tier's shape; the backend passes its own required spec, so the census the
     *        runtime checks and the tier the backend serves are one object.
     * @return null when armed (read the new session's [epoch] next), else the refusal
     *         (QNN: `init`, or `quant` when the encoder's input quantisation cannot be read).
     */
    fun init(spec: NpuModelSpec, files: NpuEngineFiles, dirs: NpuEngineDirs): Refusal?

    /**
     * Runs the encoder over one 30 s window.
     *
     * @param melF32 whisper.cpp's float mel — `spec.melFloatBytes`, a DIRECT buffer in native
     *        order (`NpuQuantize.newMelFloatBuffer`), exactly as `pcmToMel` wrote it. The engine
     *        reads it and leaves its position alone; QNN quantises it inside, LiteRT copies it in.
     * @return null when the encode succeeded (it arms the decode side: [detectLanguage] and
     *         [decodeSegment] read this window's state in place), else the refusal (`encode`).
     */
    fun encode(melF32: ByteBuffer): Refusal?

    /**
     * Decodes one segment against the last successful [encode] — the whole loop native, in one
     * call. `QnnAsrNative.nativeDecodeSegment`'s contract, argument for argument and slot for
     * slot; read its KDoc for each parameter (`LiteRtAsrNative.nativeDecodeSegment` implements
     * the same contract as its own float loop).
     *
     * @return the number of ids written (`>= 0`; `0` = EOT came first, i.e. silence), or `< 0`
     *         with the reason in [lastError].
     */
    fun decodeSegment(
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
    ): Int

    /**
     * The detect pass over the last successful [encode]: a `<|xx|>` token id inside this
     * session's own language band, or `< 0` with the reason in [lastError]. Does not consume the
     * encode — [decodeSegment] reads the same window next.
     */
    fun detectLanguage(): Int

    /** The live session's arming epoch, or `0L` when there is none. A reader and nothing else. */
    fun epoch(): Long

    /**
     * Releases the session — IF [epoch] names the live one; `0L` and stale epochs are ignored
     * (and reported) natively. Also drops this engine's own per-arm state.
     *
     * @param epoch the value [epoch] answered when this caller armed; never a fresh read at
     *        teardown, which names whatever is live NOW — the unguarded release with an argument
     *        added to it.
     */
    fun release(epoch: Long)

    /** The last `"stage: detail"` any entry point recorded, or `""`; cleared by every success. */
    fun lastError(): String

    /**
     * The native `npu-debug:` instrumentation, on or off. The backend passes `BuildConfig.DEBUG`
     * — the decision is Kotlin's, because that is where the flag exists — and an engine hands it
     * to its runtime unchanged.
     */
    fun setDiag(on: Boolean)
}

/**
 * The two model files a pair tier arms, ENCODER FIRST — named fields, so a call site cannot hand
 * the decoder in the encoder's place by position (the census would refuse it at [NpuAsrEngine.init],
 * but a transposition should not have to reach a guard to be seen).
 */
data class NpuEngineFiles(val encoderPath: String, val decoderPath: String)

/**
 * The app directories an engine resolves its own paths under, filled by the backend and read by
 * the engine that needs them — so no vendor's directory layout is spelled in the policy body.
 *
 * @property libDir `applicationInfo.nativeLibraryDir`: QNN's backend search path (FastRPC's is the
 *           process-wide `ADSP_LIBRARY_PATH`, set once in `WhisperEverywhereApp`); where LiteRT's
 *           `libLiteRt.so` is packaged.
 * @property filesDir `Context.filesDir`: where a runtime's staged files live. QNN's skel is staged
 *           there by [NpuAsrEngine.prepare] and FastRPC finds it through the environment, so the
 *           QNN engine does not read this; the LiteRT engine derives its dispatch directory under it.
 */
data class NpuEngineDirs(val libDir: String, val filesDir: String)
