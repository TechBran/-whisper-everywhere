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
 * [onSessionStart] and [onSessionEnd] are Main-only; [onQueueDepth] arrives from EITHER thread
 * (see its KDoc). Implementations must be allocation-free and
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
     *
     * [slowCommitIntervalMs] (build 85) is THE BACKPRESSURE GOVERNOR's second floor: the interval
     * to pace at while the segment queue — published through [onQueueDepth] — is at or over
     * [BackpressureRule.ENTER_DEPTH], until it falls to [BackpressureRule.LEAVE_DEPTH]. Defaulted
     * to [minCommitIntervalMs] so every existing caller and test compiles and behaves as before:
     * slow == fast is the inert governor, and it is also what every tier but npu-turbo hands over
     * ([com.whispereverywhere.service.CommitCadencePolicy.slowCommitIntervalMs]). An implementation
     * that does not pace ignores it.
     */
    fun onSessionStart(
        nowMs: Long,
        minCommitIntervalMs: Long,
        slowCommitIntervalMs: Long = minCommitIntervalMs,
    ) {}

    /**
     * THE BACKPRESSURE GOVERNOR's signal (build 85): the committed-but-unresolved segment backlog,
     * published on EVERY change — each commit and each resolution — by the service's
     * `SegmentQueueDepth`, which calls this from inside its own monitor so the values arrive in
     * the order the set changed. NOT Main-only: the commit funnel runs on the capture thread for
     * the endpoint and cap cuts and on Main for switchSource / stopRecording / the consent flush,
     * and resolutions arrive on Main. An implementation must therefore treat the depth as a
     * plain published integer — a `@Volatile` write, nothing else — and step its own mode from
     * it on the capture thread, where the floor is consulted. Default: no-op; the amplitude
     * fallback has no floor to govern.
     *
     * A session opens at depth 0 ([onSessionStart] clears the last one's) and the service resets
     * its counter at the same moment, so a backlog the previous session left in flight is never
     * inherited. [reset] leaves it alone: a cap cut is a commit, not a change in what is queued.
     */
    fun onQueueDepth(depth: Int) {}

    /**
     * Session end (Main), called from stopRecording AFTER both capture sources have stopped and
     * JOINED their threads and after the unconditional stop flush. An implementation that owns a
     * native probe frees it here. Default: no-op.
     */
    fun onSessionEnd() {}

    /**
     * THE FLATLINE CUT's arming (4.4). ARMED IFF THE ACTIVE SOURCE IS CAPTURED PLAYBACK — that is
     * the whole rule, and the service applies it at the ONE place its active source changes
     * (`setActiveSource` in FloatingBubbleService), which is reached at session start once the
     * source is picked, at every `switchSource`, and at the one DRM handover the device-audio latch
     * allows. Main-only, like [onSessionStart]: the flag it sets is read on the capture thread.
     *
     * Why per SOURCE and not per session. The trigger fires on a run of chunks whose RMS is at or
     * below [EndpointerTuning.FLATLINE_RMS_MAX] — digital silence, which a microphone in a room never
     * produces but an edited video's gate produces at every cut. On the mic the trigger could only
     * ever fire on a muted or disconnected input, so it stays off there and a mic session is
     * byte-identical to the endpointer without it. A session that starts on the mic and hands over
     * to device audio arms at the handover; the latch means device audio never hands back to the mic
     * except through the announced DRM fallback, which disarms. [reset] neither arms nor disarms —
     * it is an external commit, not a source change — and an implementation's [onSessionStart]
     * opens every session DISARMED, because every session opens on the microphone by construction
     * and the source pick that follows re-arms it if it needs to.
     *
     * Default: no-op. The amplitude fallback has no gate for a flat run to close and ignores it.
     */
    fun armFlatline(armed: Boolean) {}

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

    /**
     * THE SPEECH EVIDENCE (4.3.2): how many milliseconds of the UNCOMMITTED buffer the probe
     * scored at or above [EndpointerTuning.ONSET_THRESHOLD], or [UNKNOWN_SPEECH_EVIDENCE_MS] when
     * this endpointer cannot say. Read by the commit funnel, once, immediately before it hands the
     * buffer to the engine — after `onFrame` returned true on the capture thread, or on Main at
     * the cap-less commit sites — and passed through `TranscriptionEngine.commit`, where the local
     * engine skips the ENCODE of a buffer under [EndpointerTuning.MIN_SPEECH_EVIDENCE_MS].
     *
     * EVIDENCE ONLY. Nothing in any implementation may read it to decide a CUT: it gates the
     * encoder downstream, never the endpoint. UNKNOWN is the honest default — it means "never
     * skipped", and it is what the amplitude fallback answers (no probe, no evidence) and what a
     * Silero endpointer answers before its probe has scored a frame of this buffer or after the
     * slow-probe latch has silenced it.
     */
    fun speechEvidenceMs(): Long = UNKNOWN_SPEECH_EVIDENCE_MS

    /**
     * The commit funnel has handed the pending buffer to the engine (4.3.2) — the evidence it just
     * read through [speechEvidenceMs] belonged to that buffer, and the count re-bases for the next
     * one. Called from the funnel on whichever thread committed, after the engine's commit and
     * BEFORE the [reset] the cap, switch and stop sites call next.
     *
     * [tailRetained] is true for the wall-cap cut that kept the audio after the endpointer's
     * offered micro-pause ([pendingCutPointMs]): that audio is still in the engine's buffer and
     * opens the next segment, so its onset frames must open the next count too. Without the
     * carry, a speaker whose last words fell inside the retained tail and who then stopped would
     * have that tail skipped at the stop flush as "no evidence". The tail is exactly the frames
     * after the offered cut point, which a Silero endpointer can count precisely.
     *
     * This is the ONE re-base site short of a session start, deliberately, and [reset] is NOT one:
     * every service-side [reset] follows a funnel commit that has already re-based the count, and
     * on the cap site the re-base carried the retained tail — a clear inside [reset] would erase
     * exactly that carry. Default: no-op, for an endpointer that keeps no count.
     */
    fun onBufferCommitted(tailRetained: Boolean) {}

    companion object {
        /**
         * "This endpointer cannot say how much speech the buffer holds" — the amplitude fallback
         * always, and a Silero endpointer with no verdict yet or a latched-off probe. Negative,
         * so that a legitimate count (0 included: a fully scored buffer of pure silence) can never
         * collide with it; `TranscriptionEngine` reads any negative as UNKNOWN and never skips.
         */
        const val UNKNOWN_SPEECH_EVIDENCE_MS = -1L

        /**
         * "No micro-pause was observed in this stretch."
         *
         * WALL CLOCK ONLY. This sentinel shares its value space with the clock
         * [pendingCutPointMs] reports: 0L is a legal `System.currentTimeMillis()` reading, merely
         * unreachable in practice. An implementation that switches to a monotonic or
         * session-relative clock — where 0 IS reachable at the origin — would silently report
         * "no micro-pause" on its first frame. Change the sentinel before changing the clock.
         */
        const val NO_CUT_POINT = 0L
    }
}
