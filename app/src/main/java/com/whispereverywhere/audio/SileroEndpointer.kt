package com.whispereverywhere.audio

/**
 * The 3.7 real-VAD endpointer: streaming Silero probabilities in, "commit now" out.
 *
 * It replaces the amplitude DECISION only. Everything structural around it is unchanged: the wall
 * caps stay in the `else if` at `FloatingBubbleService.kt:1695` as backstops, `sendAudio` stays
 * unconditional and first (`FloatingBubbleService.kt:1668`), and the stop flush stays
 * unconditional. With this endpointer stubbed to never fire, session behaviour is byte-identical
 * to 3.6.0 — that is the whole de-risking argument.
 *
 * ## The state machine
 * Ported from `whisper.cpp:5271-5347` — the state variables and the per-frame loop of the vendored
 * Silero post-processor `whisper_vad_segments_from_probs` (`whisper.cpp:5217-5451`) — including two
 * details that are easy to lose:
 *  - **The dead band.** A frame with `RELEASE <= p < ONSET` neither clears the pending end nor
 *    counts as silence. The native onset guard at `whisper.cpp:5283` and the silence guard at
 *    `whisper.cpp:5322` test DIFFERENT thresholds, and the gap between them falls through both.
 *    Only a frame at or above `ONSET` resets the hangover clock. That is what makes the hangover a
 *    HARD TIMER rather than a decaying one.
 *  - **The micro-pause memory.** The most recent dip below `RELEASE` that outlived
 *    [EndpointerTuning.MICRO_PAUSE_MS] is remembered (native `prev_end = temp_end`,
 *    `whisper.cpp:5329`, guarded by the strict comparison at `:5328` that
 *    [EndpointerTuning.MICRO_PAUSE_MS] cites), so the 15 s wall cap can cut at a real boundary
 *    instead of an arbitrary millisecond. `no_context = true` makes mid-word cuts unrepairable, so
 *    a better boundary is free quality at the same latency bound.
 *
 * ## Clock
 * ONE clock: the caller's `nowMs`, stamped on the chunk the frames came from. The native reference
 * counts sample indices; wall clock is equivalent here because capture is real time, and it is what
 * [com.whispereverywhere.service.SegmentCapPolicy] and the log lines already use. A burst
 * delivery (the AudioRecord ring holds >=128 ms) makes the hangover fire slightly LATE, never
 * early — the conservative direction.
 *
 * ## Threading
 * [onFrame] runs on the capture thread. [reset] is also called from Main (switchSource
 * `FloatingBubbleService.kt:1819`, onOpen `:2224`, stopRecording `:2393`). Every mutable field is
 * therefore @Volatile, with the same tolerance [com.whispereverywhere.service.SegmentCapPolicy]
 * documents: the writes are not atomic together, but a torn observation costs at most one 32 ms
 * chunk of slack. The annotation is not decoration — without it a Main-thread [reset] shares no
 * happens-before edge with the capture thread, so the cleared state may never become visible at
 * all. `SileroEndpointerTest` fails the build if a later task adds a `var` without it.
 *
 * What @Volatile buys here is VISIBILITY, not atomicity: `fill += n` is a non-atomic
 * read-modify-write, so a Main-thread [reset] racing the capture thread can be LOST, leaving at
 * most one frame of pre-reset audio in the accumulator — inside the same one-chunk tolerance. No
 * ordering of the volatile reads can produce an out-of-bounds copy or a short frame, because every
 * length in [onFrame] is recomputed from the field it just read.
 *
 * @param probe hands a frame of exactly [EndpointerTuning.FRAME_BYTES] PCM16 bytes to the native
 *        Silero probe and returns its probability, or [EndpointerTuning.NO_VERDICT]. **The array is
 *        REUSED between calls — the probe must copy anything it retains.** The real binding to
 *        `WhisperNative.vadProbeFrame` is made by `EndpointerFactory` (Workstream D).
 * @param probeReset resets the native probe's LSTM state; fired on every commit and every [reset].
 *
 * The per-tier cost governor is NOT a constructor parameter: it is handed over per session through
 * [Endpointer.onSessionStart] (Task C6), because it depends on the installed tier AND on whether
 * every commit becomes a provider request — both of which change between sessions of one service.
 */
class SileroEndpointer(
    private val probe: (ByteArray) -> Float,
    private val probeReset: () -> Unit = {},
) : Endpointer {
    /** The accumulator. One array for the life of the endpointer: no per-frame allocation. */
    private val frame = ByteArray(EndpointerTuning.FRAME_BYTES)

    @Volatile private var fill = 0
    @Volatile private var lastFrameMs = 0L

    /** The Schmitt gate: open from the first frame at or above ONSET until a hangover ends it. */
    @Volatile private var speaking = false

    /** `nowMs` of the frame that opened the gate — the MIN_SPEECH_MS clock's zero. */
    @Volatile private var speechStartMs = 0L

    /** The uncommitted-buffer latch [hasPendingSpeech] returns. Cleared only by commit/reset. */
    @Volatile private var pendingSpeech = false

    /**
     * The PENDING END: `nowMs` of the FIRST sub-RELEASE frame of the current dip, 0 when the gate
     * is not in one. Native `temp_end` (`whisper.cpp:5323-5324`).
     *
     * Stamped ONCE per dip and cleared by exactly one thing — a frame back at or above ONSET. That
     * is the whole hard-timer mechanism: the hangover is `nowMs - tempEndMs`, wall time, so neither
     * a dead-band mumble nor a run of no-verdict frames can push it back.
     */
    @Volatile private var tempEndMs = 0L

    /**
     * @param chunk PCM16 mono 16 kHz, ANY length (short reads are normal).
     * @param amp the chunk's RMS, ignored here — it exists for the amplitude fallback that shares
     *        this call shape.
     * @param nowMs the capture wall clock for this chunk.
     * @return true when the caller should commit the buffer NOW.
     *
     * `AudioRecord.read` returns UP TO the buffer size (`StreamingAudioRecorder.kt:80` reads into a
     * 1024-byte array and forwards `buffer.copyOf(read)`) and the 48 kHz decimator documents
     * "~1024" (`PlaybackAudioCapturer.kt:62`), so one chunk = one frame is the common case and
     * never the contract: accumulating to exact frame boundaries is the CONTRACT, not an
     * optimisation. A short frame zero-padded into the model still advances the LSTM one step and
     * poisons the recurrence for every frame after it — silent, gradual accuracy loss with no
     * symptom at the call site, which is why the native side refuses the frame outright rather
     * than padding it.
     */
    override fun onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean {
        lastFrameMs = nowMs
        var src = 0
        while (src < chunk.size) {
            val n = minOf(EndpointerTuning.FRAME_BYTES - fill, chunk.size - src)
            System.arraycopy(chunk, src, frame, fill, n)
            fill += n
            src += n
            if (fill < EndpointerTuning.FRAME_BYTES) break
            // The accumulator is cleared BEFORE the probe runs, so the commit return below leaves
            // it clean: on a commit the rest of the in-flight chunk is dropped deliberately. Those
            // bytes belong to audio the caller has just handed off, and carrying them forward
            // would prepend the previous utterance's tail to the next segment.
            fill = 0
            if (onProb(probe(frame), nowMs)) return true
        }
        return false
    }

    /**
     * True when speech has been seen since the last commit/reset: at least
     * [EndpointerTuning.MIN_SPEECH_MS] has elapsed since a frame at or above
     * [EndpointerTuning.ONSET_THRESHOLD] opened the gate.
     *
     * It describes the UNCOMMITTED BUFFER, not the gate: a merged or discarded utterance leaves it
     * true, because that audio really is still sitting there. Only a commit or a [reset] clears it
     * — a closing gate does not, and neither does a silent frame. The two are easy to conflate and
     * the cost of conflating them is specific: `FloatingBubbleService.kt:1716` re-arms the 4 s
     * first-cap window whenever this reads false, so a predicate that meant "speech in the current
     * frame" would re-arm on every cap cut that happened to land in a pause.
     *
     * This is the semantics upgrade that re-arm has been waiting for: the soft talker in a noisy
     * room, whose RMS never clears 500, flips from permanently-false to true. The branch above it
     * is unchanged — only the predicate gets honest.
     */
    override fun hasPendingSpeech(): Boolean = pendingSpeech

    /** A commit happened elsewhere (cap cut, source switch, session open, stop). */
    override fun reset() {
        clearForNextSegment()
    }

    /**
     * One frame's verdict.
     *
     * [EndpointerTuning.NO_VERDICT] (any negative) means "no verdict": the previous state is kept
     * exactly — it can neither open nor close the gate. It does not stall the hangover either,
     * because that clock is wall time from the pending end, so the next real verdict sees the full
     * elapsed silence.
     *
     * The guard is `p < 0f` and not an equality test against the sentinel: a probability is by
     * definition in [0, 1], so any negative is a refusal, and an exact float comparison on a value
     * that crossed a JNI boundary is the wrong instrument. `EndpointerTuningTest` pins the sentinel
     * to -1.0f absolutely and `SileroEndpointerTest` pins the premise that it is negative, so the
     * two forms cannot drift apart silently.
     */
    private fun onProb(p: Float, nowMs: Long): Boolean {
        if (p < 0f) return false

        // whisper.cpp:5283-5296 — the two native blocks this one branch merges. A frame at or
        // above ONSET clears the pending end (`:5283`) — the HARD reset that makes the hangover a
        // timer rather than a decay — and opens the gate if it is closed (`:5291`).
        if (p >= EndpointerTuning.ONSET_THRESHOLD) {
            tempEndMs = 0L
            if (!speaking) {
                speaking = true
                speechStartMs = nowMs
            }
            if (nowMs - speechStartMs >= EndpointerTuning.MIN_SPEECH_MS) pendingSpeech = true
            return false
        }

        // THE DEAD BAND (RELEASE <= p < ONSET) is deliberately inert: it is neither an onset nor a
        // silence. The native guards at :5283 and :5322 use DIFFERENT thresholds and the gap
        // between them falls through both — that is the Schmitt hysteresis, and its absence is
        // exactly what strands today's amplitude segmenter in the 251-499 RMS band. And because it
        // does not clear the pending end either, the hangover below counts straight THROUGH a
        // mumble — a dead-band frame is not silence, but it does not buy the speaker any time.
        if (p >= EndpointerTuning.RELEASE_THRESHOLD) return false

        // Below RELEASE is SILENCE, and only after speech: with the gate shut there is no
        // utterance to end (`whisper.cpp:5322`, whose `&& is_speech_segment` this guard is).
        if (!speaking) return false

        // The pending end is stamped ONCE, at the FIRST frame of the dip (`:5323-5324`), and the
        // hangover then elapses on wall time from it (`:5333`). Nothing in this branch re-stamps
        // it: that is the difference between a hard timer and a decaying one, and it is what lets
        // a 500 ms hangover survive a talker whose pauses are full of breath and paper noise.
        if (tempEndMs == 0L) tempEndMs = nowMs
        if (nowMs - tempEndMs < EndpointerTuning.HANGOVER_MS) return false

        // The utterance is measured to the PENDING END, not to now: the hangover's own 500 ms is
        // silence, not speech (native `temp_end - curr_speech_start`, `:5337`).
        val speechMs = tempEndMs - speechStartMs
        if (speechMs <= EndpointerTuning.MIN_SPEECH_MS) {
            // whisper.cpp:5337 — too short to be an utterance. Drop it and re-arm; the native VAD
            // filter would drop it before whisper_full anyway, so committing would buy an
            // EmptyExpected round trip and nothing else. STRICT, as the native comparison is:
            // exactly MIN_SPEECH_MS is discarded. `pendingSpeech` is NOT cleared — the audio is
            // still in the engine's buffer, and the cap-policy branch above needs to know that.
            // The inclusive latch in the onset branch is the same asymmetry seen from the other
            // side, and it is deliberate in both directions: err toward "there IS speech".
            closeGate()
            return false
        }
        commitAt(nowMs)
        return true
    }

    /**
     * The utterance gate only — the pending buffer's bookkeeping survives.
     *
     * The discarded-burst path comes through here, and a discard is NOT a commit: `pendingSpeech`
     * is left exactly as it was, because that audio really is still sitting in the caller's
     * buffer. Only [clearForNextSegment] speaks for the buffer.
     */
    private fun closeGate() {
        speaking = false
        speechStartMs = 0L
        tempEndMs = 0L
    }

    /**
     * A real endpoint, taken: the caller is about to send the buffer, so everything about it goes.
     *
     * Separate from [clearForNextSegment] because Task C6 hangs the per-tier cadence governor's
     * bookkeeping here — the commit instant `nowMs` is what it will pace against — while [reset]
     * keeps calling the clear directly.
     */
    private fun commitAt(nowMs: Long) {
        clearForNextSegment()
    }

    private fun clearForNextSegment() {
        closeGate()
        pendingSpeech = false
        fill = 0
        probeReset()
    }
}
