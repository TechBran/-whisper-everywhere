package com.whispereverywhere.util

/**
 * The two contracts every PCM capture thread in the app obeys (3.7 Workstream E).
 *
 * Both were latent until 3.7: at ~1% callback duty a mis-prioritised capture thread only cost
 * waveform smoothness, and a backwards teardown only cost one read period. The inline Silero
 * probe puts real native work in the capture callback, which promotes both to load-bearing.
 *
 * Pure and framework-light on purpose — one `android.os.Process` touch and two `android.util.Log`
 * diagnostics, all of which the unit test's returnDefaultValues stubs make no-ops — so the
 * ordering rule is JVM-pinned (CaptureThreadPolicyTest). The flip side of stubbed-out framework
 * calls is that nothing observes them from a JVM test: the priority setter and both teardown
 * diagnostics are pinned STRUCTURALLY, on this file's source, by
 * captureThreadPolicySource_pinsWhatNoJvmCallerCanReach.
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
     * How long teardown waits for a capture thread to exit. Pre-3.7 this was a bare `2000` literal
     * at both capture sites; naming it here is what lets the ordering rule and its bound be read in
     * one place, and preserves the value both sites already used. E2 wired
     * StreamingAudioRecorder.stop() to this constant; PlaybackAudioCapturer.stop() keeps its own
     * `2000` (PlaybackAudioCapturer.kt:99) because the 3.7 plan fences that method off — it was
     * already correct and is the precedent this policy is named after.
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
     * work in the callback it can wait far longer, on Main. PlaybackAudioCapturer.kt:95-99 has
     * always done it this way and says why — the comment AND the stop/join pair it explains; this
     * is that made reusable and testable.
     *
     * Both callbacks are individually guarded: a throwing [stopRecord] (AudioRecord.stop() on an
     * uninitialized record) must never skip the join, or the capture thread outlives release().
     * Each guard logs what it swallowed: a capture thread that refused to die is undebuggable if
     * teardown fails silently. Note the swallow is total — an interrupted join loses the interrupt
     * flag too (`Thread.join` clears it when it throws); deliberate, and pinned as such.
     *
     * [joinMs] is handed to [joinThread] unchanged — a join that ignores its bound is an
     * unbounded Main-thread wait, which is the failure this whole ordering exists to prevent.
     */
    fun stopThenJoin(joinMs: Long, stopRecord: () -> Unit, joinThread: (Long) -> Unit) {
        runCatching { stopRecord() }
            .onFailure { android.util.Log.w("WE-DIAG", "capture teardown: stop failed", it) }
        runCatching { joinThread(joinMs) }
            .onFailure { android.util.Log.w("WE-DIAG", "capture teardown: join failed", it) }
    }
}
