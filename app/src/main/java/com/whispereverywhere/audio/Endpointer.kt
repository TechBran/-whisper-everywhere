package com.whispereverywhere.audio

/**
 * The ONE commit-decision surface the capture path asks "should I cut the segment now?"
 * (3.7, Task D2).
 *
 * The seam it plugs into (FloatingBubbleService.onAudioChunk) is deliberately NOT restructured:
 * the wall-cap check stays the `else if` it already was, so a never-firing endpointer leaves cap
 * behaviour byte-identical to 3.6.0. Only the verdict inside the `if` changes hands.
 *
 * THREE abstract members — everything else is a defaulted extension point that
 * [AmplitudeEndpointer] deliberately does not override, which is exactly what makes the
 * model-missing fallback shipped behaviour rather than a new code path.
 *
 * Threading: [onFrame], [hasPendingSpeech], [pendingCutPointMs] and [reset] are all called from
 * the CAPTURE thread (StreamingAudioRecorder / PlaybackAudioCapturer), ~31.25 Hz, with one
 * exception — [reset] is additionally called from Main at switchSource / onOpen / stopRecording.
 * [onSessionStart] and [onSessionEnd] are Main-only. Implementations must be allocation-free and
 * lock-light on the [onFrame] path: it runs inline on the audio thread against a 32 ms budget.
 */
interface Endpointer {

    /**
     * One captured PCM16 chunk (mono, 16 kHz — nominally 512 samples / 1024 bytes, but `read()`
     * returns *up to* the buffer size, so a short chunk is legal and implementations must
     * accumulate rather than assume). [amp] is its RMS (0..32767), already computed by the
     * capture thread. [nowMs] is `System.currentTimeMillis()` at the call site.
     *
     * @return true when the caller should commit NOW. Returning true MUST leave this endpointer
     * in the same state [reset] would — including any native probe state — so the caller never
     * has to reset after a positive verdict.
     */
    fun onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean

    /**
     * True when speech has been detected since the last commit/reset. Its ONE consumer is the
     * LOCAL-silence re-arm in the wall-cap branch: a cap cut on genuinely silent audio re-arms
     * the 4 s first-cap window instead of consuming it.
     */
    fun hasPendingSpeech(): Boolean

    /** Drop all in-flight endpoint state, including any native recurrence state. */
    fun reset()

    /**
     * Session open (Main). [nowMs] is the session's wall-clock anchor (`sessionOpenMs` at the call
     * site) and [minCommitIntervalMs] is the MEASURED cost governor from
     * [com.whispereverywhere.service.CommitCadencePolicy] — the endpointer keeps cutting at real
     * pauses but merges utterances until the interval has elapsed. Default: no-op, so the
     * amplitude path is untouched by cadence.
     *
     * Cadence arrives HERE and not in a constructor because it is per SESSION, not per service: it
     * depends on the installed tier AND on whether every commit becomes a provider request, and
     * both can change between two sessions without the service being rebuilt. An implementation
     * that has not been given one yet must assume the expensive end
     * ([com.whispereverywhere.service.CommitCadencePolicy.MIN_COMMIT_INTERVAL_LARGE_MS]).
     *
     * An implementation that owns a native probe ARMS it here and initialises it lazily on the
     * first [onFrame] — i.e. on the capture thread, never on Main.
     */
    fun onSessionStart(nowMs: Long, minCommitIntervalMs: Long) {}

    /**
     * Session end (Main), called from stopRecording AFTER both capture sources have stopped and
     * JOINED their threads and after the unconditional stop flush. An implementation that owns a
     * native probe frees it here. Default: no-op.
     */
    fun onSessionEnd() {}

    /**
     * The endpointer's remembered micro-pause: the wall-clock ms of the most recent silence dip
     * inside the currently open stretch, or [NO_CUT_POINT] when none was observed.
     *
     * Read ONLY by the wall-cap branch. Silero's own answer to "speech forever"
     * (`max_speech_duration_s`) does not cut blind — it cuts at the last observed micro-pause —
     * and with `no_context = true` making a mid-word boundary permanently unrepairable, that is a
     * strictly better cut for the same latency bound. Default [NO_CUT_POINT]: no offer, so the
     * cap commits the whole buffer exactly as it does today.
     */
    fun pendingCutPointMs(): Long = NO_CUT_POINT

    companion object {
        /** "No micro-pause was observed in this stretch." */
        const val NO_CUT_POINT = 0L
    }
}
