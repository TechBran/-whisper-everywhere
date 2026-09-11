package com.whispereverywhere.transcription.stream

/**
 * The previewer's constants — every one a MEASUREMENT with its provenance (spec §2), pinned by
 * StreamingPreviewTuningTest. Nothing here is inferred.
 */
object StreamingPreviewTuning {
    /** rung 3 §5.4: RTF 0.054 @ 2 threads, 0.072 @ 4 (slower), 0.051 @ 1. Two. */
    const val NUM_THREADS = 2

    /**
     * rung 1 §4 / rung 3 §5.3: the canary's `VE` is lost at 450 ms and kept at 500; 800 buys
     * nothing. **The one pad ever MEASURED, and it was measured on ONE pack** — English, whose
     * encoder metadata reads `T = 45`. It is the FLOOR of [padMsFor], never its answer.
     */
    const val MEASURED_PAD_MS = 500L

    /**
     * kaldi-native-fbank's frame shift. The previewer's own measurement records this cadence
     * directly: the first decode of a stream fires at 7,200 samples = 450 ms = `T = 45` frames
     * (rung 3 §7, and `ScriptedRecognizer.FIRST_DECODE_SAMPLES` is that number), so N frames of
     * feature need N × 10 ms of audio.
     */
    const val FRAME_SHIFT_MS = 10L

    /**
     * What the measured cliff bought over `T`'s own frame requirement: 500 − 45 × 10.
     *
     * The window-inclusive reading of the same floor — 25 ms of window plus (T − 1) shifts, so
     * 465 ms for English — differs from `T × 10` by a constant 15 ms, which is INSIDE this
     * margin. The two readings therefore agree on every candidate `T` in the qualification
     * table, which is why the margin is spelled against the simpler one.
     */
    const val PAD_MARGIN_MS = 50L

    /**
     * The commit pad for a pack whose encoder metadata says `T = `[encoderT] — the frames one
     * forward pass needs, in milliseconds, plus [PAD_MARGIN_MS], and never below the one pad that
     * has been measured on a device.
     *
     * **Why this is derived and not a constant.** `T` is per-pack: 45 for the shipping English
     * pack and for de/zh/ko, **39** for the `zipformer` v1 exports (fr, zh-en), and **77** for
     * every 640 ms pack (ru, id, tr, et, pt). At `T = 77` one pass needs 770 ms of feature, so a
     * flat 500 ms pad leaves `isReady` false, the tail chunk is never decoded and **the last word
     * of every utterance silently never emits** — and the canary Fails into a verdict behind a
     * sentence the 4.4.0 acceptance sheet records as rendered nowhere (qualification table §6(4),
     * E1). Upstream corroborates the direction: Vosk's own Russian `decode.py` pads 600 ms, and
     * 2.0 s for its 128-shift variant. Nobody pads 500 for a `T = 77` model.
     *
     * **Why [MEASURED_PAD_MS] is a floor rather than a default.** The two errors are not
     * symmetric. Too long costs zeros nobody hears and at most one extra decode at commit (rung 1
     * measured 800 ms as harmless, buying nothing); too short drops the last word of every
     * utterance, silently, which is the defect this function exists to close. So the derivation
     * RAISES the pad and never lowers it below the only value a device has confirmed — `T = 39`
     * would arithmetically take 440 ms and gets 500 anyway. The 60 ms is the cheapest insurance
     * in the feature.
     */
    fun padMsFor(encoderT: Int): Long =
        maxOf(MEASURED_PAD_MS, FRAME_SHIFT_MS * encoderT + PAD_MARGIN_MS)

    /** [padMsFor] in samples at [SAMPLE_RATE] — the zeros `freeze` and the canary actually feed. */
    fun padSamplesFor(encoderT: Int): Int = (padMsFor(encoderT) * SAMPLE_RATE / 1000L).toInt()

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

    fun ringBytes(): Int = (RETAIN_RING_MS * BYTES_PER_MS).toInt()
}
