package com.whispereverywhere.transcription.stream

/**
 * The previewer's constants — every one a MEASUREMENT with its provenance (spec §2), pinned by
 * StreamingPreviewTuningTest. Nothing here is inferred.
 */
object StreamingPreviewTuning {
    /** rung 3 §5.4: RTF 0.054 @ 2 threads, 0.072 @ 4 (slower), 0.051 @ 1. Two. */
    const val NUM_THREADS = 2

    /** rung 1 §4 / rung 3 §5.3: the canary's `VE` is lost at 450 ms and kept at 500; 800 buys nothing. */
    const val PAD_MS = 500L

    /** The capture pipeline's rate (SegmentTiming.SAMPLE_RATE_HZ). */
    const val SAMPLE_RATE = 16_000

    /** StreamingAudioRecorder reads 1,024 bytes = 512 samples = 32 ms. */
    const val CHUNK_SAMPLES = 512

    /** PCM16 mono @ 16 kHz: 32 bytes per millisecond (LocalWhisperEngine.BYTES_PER_MS). */
    const val BYTES_PER_MS = 32

    /** 128 chunks ≈ 4.1 s — LiveTranscriptionEngine.DEFAULT_MAX_BACKLOG. Overflow sheds, never blocks. */
    const val QUEUE_CAPACITY = 128

    /** The retained-tail ring: CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS of PCM. */
    const val RETAIN_RING_MS = 3_000L

    /**
     * Consecutive decode failures inside one SESSION before the previewer disables itself for the
     * process. Session, not segment: only a decode that returned clears the count (9 of 10 bursts
     * decode nothing, so clearing per burst would make this constant dead code), so it carries
     * across a commit and is cleared by `StreamingPreviewEngine.open`.
     */
    const val MAX_CONSECUTIVE_FAILURES = 3

    fun padSamples(): Int = (PAD_MS * SAMPLE_RATE / 1000L).toInt()
    fun ringBytes(): Int = (RETAIN_RING_MS * BYTES_PER_MS).toInt()
}
