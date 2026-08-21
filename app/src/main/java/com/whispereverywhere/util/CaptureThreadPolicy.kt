package com.whispereverywhere.util

/**
 * The two contracts every PCM capture thread in the app obeys (3.7 Workstream E).
 *
 * Both were latent until 3.7: at ~1% callback duty a mis-prioritised capture thread only cost
 * waveform smoothness, and a backwards teardown only cost one read period. The inline Silero
 * probe puts real native work in the capture callback, which promotes both to load-bearing.
 *
 * Pure and framework-light on purpose (only the one `android.os.Process` touch, which the unit
 * test's returnDefaultValues stubs make a no-op) so the ordering rule is JVM-pinned —
 * CaptureThreadPolicyTest.
 */
object CaptureThreadPolicy {

    /**
     * Capture threads must outrank ordinary background threads: a busy device otherwise
     * deschedules exactly the thread draining the AudioRecord ring, and a missed read is
     * unrecoverable audio. THREAD_PRIORITY_URGENT_AUDIO (-19), not THREAD_PRIORITY_AUDIO (-16) —
     * TtsEngine.kt:302 sets the same value on the render thread for the same reason.
     */
    const val CAPTURE_THREAD_PRIORITY: Int = android.os.Process.THREAD_PRIORITY_URGENT_AUDIO

    /**
     * How long teardown waits for a capture thread to exit. Pre-3.7 this was a bare `2000`
     * literal in both StreamingAudioRecorder.stop() (:107) and PlaybackAudioCapturer.stop()
     * (:96); naming it here is what lets the ordering rule and its bound be read in one place,
     * and preserves the value both sites already used.
     *
     * The bound is the point, not the number: teardown can be reached from Main, so the wait must
     * be capped well inside the input-dispatch ANR window rather than left open-ended.
     */
    const val CAPTURE_JOIN_MS: Long = 2_000L

    /**
     * The real priority setter. Named so [enterCaptureThread]'s default is a single thing rather
     * than an inline lambda buried in the signature.
     */
    private val systemSetThreadPriority: (Int) -> Unit = { android.os.Process.setThreadPriority(it) }

    /**
     * FIRST statement of every capture thread body.
     *
     * [applyPriority] is an injection seam, not a knob: `android.os.Process.setThreadPriority` is
     * stubbed to a no-op under the unit test's returnDefaultValues, so without the seam an EMPTY
     * body would be indistinguishable from a correct one (verified — that mutation survived the
     * whole suite). Production callers use the zero-arg form.
     */
    fun enterCaptureThread(applyPriority: (Int) -> Unit = systemSetThreadPriority) {
        applyPriority(CAPTURE_THREAD_PRIORITY)
    }

    /**
     * Teardown in the ONLY safe order: halt the recorder, THEN join its thread.
     *
     * `AudioRecord.read()` blocks until its buffer fills, and stopping the record is what
     * unblocks it immediately — joining first waits a full read period at best, and with native
     * work in the callback it can wait far longer, on Main. PlaybackAudioCapturer.kt:92-95 has
     * always done it this way and says why; this is that comment made reusable and testable.
     *
     * Both callbacks are individually guarded: a throwing [stopRecord] (AudioRecord.stop() on an
     * uninitialized record) must never skip the join, or the capture thread outlives release().
     *
     * [joinMs] is handed to [joinThread] unchanged — a join that ignores its bound is an
     * unbounded Main-thread wait, which is the failure this whole ordering exists to prevent.
     */
    fun stopThenJoin(joinMs: Long, stopRecord: () -> Unit, joinThread: (Long) -> Unit) {
        runCatching { stopRecord() }
        runCatching { joinThread(joinMs) }
    }
}
