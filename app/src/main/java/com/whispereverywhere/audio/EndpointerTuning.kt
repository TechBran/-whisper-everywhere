package com.whispereverywhere.audio

/**
 * Every knob the 3.7 Silero endpointer turns, in ONE object, JVM-pinned by EndpointerTuningTest.
 *
 * Deliberately SEPARATE from the batch VAD filter's tuning (`we_vad_filter`,
 * `whisper_jni.cpp:191-192` — threshold 0.40 / speech_pad 150 ms), which is untouched: the
 * streaming probe decides WHEN to cut an utterance, the batch filter decides WHAT audio inside
 * that commit reaches the encoder. Independent knobs on independent jobs — the batch filter's 0.40
 * buys onset headroom that `suppress_nst` absorbs at the token layer, and endpointing has no token
 * layer.
 *
 * There is deliberately NO smoothing/EMA constant. The reference implementation does not smooth
 * (`whisper_vad_segments_from_probs`, `whisper.cpp:5217-5451`): the Schmitt trigger, the minimum
 * speech duration and the hangover already low-pass the sequence. An EMA would add lag and a second
 * thing to tune.
 */
object EndpointerTuning {

    /** Silero's window: 512 samples of 16 kHz mono is exactly one probe frame (model n_window). */
    const val FRAME_SAMPLES = 512

    /**
     * 512 samples x 2 bytes (PCM16). `vadProbeFrame` returns [NO_VERDICT] for any other size.
     *
     * SINGLE OWNER: this object owns the JVM side of the native frame contract.
     * `VadProbe.FRAME_BYTES` (Task D4) is an alias of this constant, not a second literal —
     * `EndpointerFactory` sizes its direct buffer from one and fills it from the other, so a
     * divergence would be a `BufferOverflowException` on the capture thread rather than a doc
     * inconsistency.
     */
    const val FRAME_BYTES = 1024

    /**
     * 512 / 16 000 s. One mic callback nominally delivers this much audio; `read()` may return
     * short, so callers accumulate to exact [FRAME_BYTES] boundaries.
     */
    const val FRAME_MS = 32L

    /**
     * "No verdict" from the native probe — NEVER "silence". A short frame zero-padded into the
     * model still advances the LSTM and poisons the recurrence, so the native side refuses and the
     * client keeps the previous state.
     *
     * SINGLE OWNER, as for [FRAME_BYTES]: `VadProbe.NO_VERDICT` (Task D4) aliases this. Two
     * independent `-1.0f` literals in one package would let a future edit turn the native sentinel
     * into a legitimate probability on one side of the seam only.
     */
    const val NO_VERDICT = -1.0f

    /**
     * Native default (`whisper_vad_default_params`, whisper.cpp:4454). A frame at or above this
     * opens/holds the gate.
     */
    const val ONSET_THRESHOLD = 0.50f

    /**
     * Schmitt hysteresis, native `neg_threshold = threshold - 0.15f` (whisper.cpp:5258). This is
     * the exact mechanism whose absence causes today's 251-499 RMS dead band (the KNOWN LIMITATION
     * block at `com.whispereverywhere.util.SpeechSegmenter:22-30`: a room whose noise floor sits
     * between the two amplitude thresholds opens a segment that can never close). Widen to 0.30 if
     * mid-word splits appear in A/B.
     */
    const val RELEASE_THRESHOLD = 0.35f

    /**
     * Trailing silence that ends an utterance. NOT the native 100 ms (`whisper.cpp:4456`), which is
     * a file-segmentation value with a 200 ms merge pass behind it (`whisper.cpp:5359`).
     * Inter-clause pauses run 200-500 ms; the cost of cutting too early is one extra full encoder
     * pass PLUS a mid-clause boundary that `no_context = true` makes unrepairable. Also feeds the
     * batch filter's `speech_pad_ms = 150`, which needs trailing audio to expand into. Owner A/B
     * range 350-800.
     */
    const val HANGOVER_MS = 500L

    /**
     * Shortest run of speech that may be committed. The native filter already drops <250 ms before
     * `whisper_full`; 300 keeps client and native agreeing instead of fighting.
     * (`min_speech_duration_ms = 250`, `whisper.cpp:4455`.)
     */
    const val MIN_SPEECH_MS = 300L

    /**
     * A dip below [RELEASE_THRESHOLD] lasting longer than this is remembered as a cut point for the
     * wall-cap path (native `min_silence_samples_at_max_speech`, whisper.cpp:5255, compared
     * strictly at `whisper.cpp:5328`). At the 32 ms frame cadence, with the dip clock started at
     * the FIRST sub-[RELEASE_THRESHOLD] frame (as native starts temp_end at that frame's
     * curr_sample), the first qualifying frame is the 5th of the dip: 128 ms > 98 ms, while the 4th
     * is only 96 ms old.
     *
     * CLOCK DOMAIN: this floor is WALL-CLOCK milliseconds because the endpointer's dip clock is
     * `nowMs`, while native counts SAMPLES (`sample_rate * 98 / 1000` = 1568 at 16 kHz) — the same
     * floor in different units, never comparable without converting.
     */
    const val MICRO_PAUSE_MS = 98L

    /**
     * A probe frame slower than this is an overrun: the probe's own cost budget inside the 32 ms
     * frame period, not the frame period itself. Nothing measures against THIS spelling — the
     * comparison is made in microseconds, against [PROBE_BUDGET_US] below.
     *
     * Generous against the 0.2-1.5 ms the probe is expected to cost, so an overrun means something
     * is really wrong rather than that the estimate was tight.
     */
    const val PROBE_BUDGET_MS = 8L

    /**
     * [PROBE_BUDGET_MS] in the unit the endpointer actually measures. **ONE conversion for the
     * whole seam**, and that is the entire reason it exists rather than being spelled
     * `PROBE_BUDGET_MS * 1_000L` at each site.
     *
     * There are three consumers and they must agree exactly: `SileroEndpointer`'s own cutout latch,
     * the `ProbeStats` it constructs by default, and the `ProbeStats` `EndpointerFactory` (Task D8)
     * passes in. Task C10 retuned the latch from truncated milliseconds to microseconds precisely so
     * that the latch and the `probe:` line could not hold two opinions about what an overrun is; a
     * second conversion site is how that agreement would be lost again, silently, in a commit that
     * looked like a tidy-up. `ProbeStats` keeps its budget PRIVATE, so no `require` can catch a
     * mismatch at runtime — this constant is the only enforcement there is.
     */
    const val PROBE_BUDGET_US = PROBE_BUDGET_MS * 1_000L

    /** Consecutive overruns that latch the probe off for the rest of the session. */
    const val PROBE_CUTOUT_FRAMES = 16

    // NO COMMIT-INTERVAL CONSTANTS LIVE HERE. The measured per-tier cost governor
    // (1200 pro / 6000 multi / 8000 extreme+ultra / 3000 cloud batch) is owned solely by
    // com.whispereverywhere.service.CommitCadencePolicy, and reaches the endpointer per SESSION via
    // Endpointer.onSessionStart(nowMs, minCommitIntervalMs) — it depends on the installed tier AND
    // on whether every commit becomes a provider request, neither of which is an acoustic knob.
}
