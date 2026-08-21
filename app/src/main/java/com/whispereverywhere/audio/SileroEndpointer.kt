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
 *    counts as silence. The native onset guard at `whisper.cpp:5282` and the silence guard at
 *    `whisper.cpp:5321` test DIFFERENT thresholds, and the gap between them falls through both.
 *    Only a frame at or above `ONSET` resets the hangover clock. That is what makes the hangover a
 *    HARD TIMER rather than a decaying one.
 *  - **The micro-pause memory.** The most recent dip below `RELEASE` that outlived
 *    [EndpointerTuning.MICRO_PAUSE_MS] is remembered (native `prev_end = temp_end`,
 *    `whisper.cpp:5328`), so the 15 s wall cap can cut at a real boundary instead of an arbitrary
 *    millisecond. `no_context = true` makes mid-word cuts unrepairable, so a better boundary is
 *    free quality at the same latency bound.
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
     * True when speech has been seen since the last commit/reset.
     *
     * STUB. Task C3 replaces this stub and MUST implement the predicate the LOCAL-silence re-arm
     * at `FloatingBubbleService.kt:1716` reads: ">= [EndpointerTuning.MIN_SPEECH_MS] of frames at
     * or above [EndpointerTuning.ONSET_THRESHOLD] since the last commit/reset". It describes the
     * UNCOMMITTED BUFFER, not the gate.
     *
     * The stub is not a neutral placeholder and must not be mistaken for finished code: a constant
     * `false` re-arms the 4 s first-cap window on EVERY local wall-cap cut, so a continuously loud
     * local session would cut at 4 s forever instead of 4 s once and 15 s after. Cloud sessions are
     * unaffected — the `|| cloudWrapper != null` half of that branch consumes the window
     * regardless.
     */
    override fun hasPendingSpeech(): Boolean = false

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
        return false
    }

    private fun clearForNextSegment() {
        fill = 0
        probeReset()
    }
}
