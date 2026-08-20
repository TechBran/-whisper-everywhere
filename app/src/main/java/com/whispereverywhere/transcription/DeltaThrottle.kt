package com.whispereverywhere.transcription

/**
 * Rate limiter for preview-delta delivery (3.6.0 Workstream D). whisper.cpp's new-segment
 * callback can fire in bursts (several segments decoded back-to-back); forwarding every one
 * would post JNI-churned text onto Main faster than it can usefully render. At most one emit
 * per [minIntervalMs] keeps the preview fluid without flooding.
 *
 * Dropping intermediates loses nothing: deltas are preview-only and each carries the FULL
 * running text, so the next emit — or the segment's terminal resolution — supersedes them.
 *
 * Threading: confined to LocalWhisperEngine's single native-executor thread (reset at segment
 * start, checked inside the native callback, which whisper_full invokes on that same thread) —
 * no synchronization needed. [now] is injectable for deterministic JVM tests; milliseconds,
 * only differences are used.
 */
class DeltaThrottle(
    private val minIntervalMs: Long = 150L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var hasEmitted = false
    private var lastEmitMs = 0L

    /** True if the caller may emit now; records the emit when it says yes. */
    fun shouldEmit(): Boolean {
        val t = now()
        if (hasEmitted && t - lastEmitMs < minIntervalMs) return false
        hasEmitted = true
        lastEmitMs = t
        return true
    }

    /** New segment: its first delta should render immediately. */
    fun reset() {
        hasEmitted = false
    }
}
