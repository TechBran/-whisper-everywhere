package com.whispereverywhere.util

/**
 * Decides when to commit the realtime audio buffer for gpt-realtime-whisper,
 * which has no server-side turn detection. Pure logic (no Android deps) so it
 * is unit-testable: feed it amplitude samples via [onAmplitude] and it returns
 * true when a commit should fire — either after a natural pause following
 * speech, or after a maximum segment length so long continuous talking still
 * flushes text periodically.
 *
 * @param voiceThreshold amplitude (0..32767) at/above which we consider speech present
 * @param silenceThreshold amplitude at/below which we consider it quiet
 * @param pauseMs quiet duration after speech that ends a segment
 * @param maxSegmentMs hard cap on a segment's length before forcing a commit
 */
class SpeechSegmenter(
    private val voiceThreshold: Int = 500,
    // KNOWN LIMITATION: a room whose noise floor sits between 251 and 499 opens a segment
    // (amplitude >= voiceThreshold) but can never satisfy the close condition
    // (amplitude <= silenceThreshold), so dispatch silently degrades to the maxSegmentMs
    // wall-clock cap. An adaptive floor was attempted and reverted on 2026-07-28: the EMA update
    // ran unconditionally, above the `!hasSpoken` guard, so it was fed the user's own sub-pauseMs
    // inter-syllable speech instead of only room tone (talking raised the bar for detecting that
    // they stopped), and it reset to 0 on every commit, so it was re-seeded from a speech tail
    // after every segment rather than converging on the room. A correct fix must sample room tone
    // only while `!hasSpoken` and must not reset per segment.
    private val silenceThreshold: Int = 250,
    private val pauseMs: Long = 800,
    private val maxSegmentMs: Long = 15000,
) {
    private var hasSpoken = false
    private var lastVoiceMs = 0L
    private var segmentStartMs = 0L

    /** @return true when the caller should commit the buffer now. */
    fun onAmplitude(amplitude: Int, nowMs: Long): Boolean {
        if (amplitude >= voiceThreshold) {
            if (!hasSpoken) {
                hasSpoken = true
                segmentStartMs = nowMs
            }
            lastVoiceMs = nowMs
            return false
        }
        if (!hasSpoken) return false

        val pausedLongEnough = amplitude <= silenceThreshold && (nowMs - lastVoiceMs) >= pauseMs
        val segmentTooLong = (nowMs - segmentStartMs) >= maxSegmentMs
        if (pausedLongEnough || segmentTooLong) {
            reset()
            return true
        }
        return false
    }

    /** True if speech has been detected since the last commit/reset. */
    fun hasPendingSpeech(): Boolean = hasSpoken

    fun reset() {
        hasSpoken = false
        lastVoiceMs = 0L
        segmentStartMs = 0L
    }
}
