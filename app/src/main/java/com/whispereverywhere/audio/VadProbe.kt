package com.whispereverywhere.audio

import com.whispereverywhere.whisper.WhisperNative
import java.nio.ByteBuffer

/**
 * Thin seam over the four streaming-VAD externs (3.7, Workstream A) so the Kotlin endpointer is
 * JVM-testable without JNI — the same discipline `WhisperBackend` applies to `whisper_full`.
 *
 * The probe deliberately does NOT go through `NativeComputeGate`: its context is forced CPU-only
 * natively regardless of params, owns its own backend, work buffers and sched, and costs ~2.6 MB.
 * Routing 32 ms frames through a FAIR process-global lock would queue them behind 4-15 s
 * whisper_full calls (or Batch's ~54 s gate holds) and recreate the exact stall 3.7 fixes. That
 * argument is recorded here because this seam is the only Kotlin-side place it is visible.
 */
interface VadProbe {
    /** Load the Silero model into the dedicated probe context. False = unavailable this session. */
    fun init(modelPath: String): Boolean

    /**
     * One 512-sample window. [pcm] MUST be a DIRECT buffer and [nBytes] MUST be
     * [FRAME_BYTES] — anything else returns [NO_VERDICT], never a silence verdict.
     */
    fun frame(pcm: ByteBuffer, nBytes: Int): Float

    /** Zero the LSTM recurrence (h/c state only; model weights are in a different buffer). */
    fun reset()

    /** Free the probe context. */
    fun free()

    companion object {
        // ALIASES, not literals. [EndpointerTuning] (Task C1, same package) is the SINGLE OWNER of
        // the native frame contract; these exist so a caller holding a VadProbe need not import the
        // tuning object. EndpointerFactory sizes its direct buffer from one of these pairs and
        // fills it from the other, so two independent literals would be a BufferOverflowException
        // on the capture thread — or a native sentinel silently readable as a probability.
        //
        // Both spellings are pinned by VadProbeLifecycleTest: the VALUES by
        // theFrameContractConstantsAreTheNativeOnes, and the ALIASING itself — which no value
        // assertion can see — by theFrameContractConstantsAreAliasesNotSecondLiterals, a scan of
        // this file's source.

        /** 512 samples of PCM16 mono @16 kHz — exactly one Silero window. */
        const val FRAME_BYTES = EndpointerTuning.FRAME_BYTES

        /** "No verdict." NEVER to be read as silence: a short frame poisons the recurrence. */
        const val NO_VERDICT = EndpointerTuning.NO_VERDICT
    }
}

/** Production probe: delegates to the four `WhisperNative` externs. */
object NativeVadProbe : VadProbe {
    override fun init(modelPath: String): Boolean = WhisperNative.vadProbeInit(modelPath)
    override fun frame(pcm: ByteBuffer, nBytes: Int): Float = WhisperNative.vadProbeFrame(pcm, nBytes)
    override fun reset() = WhisperNative.vadProbeReset()
    override fun free() = WhisperNative.vadProbeFree()
}
