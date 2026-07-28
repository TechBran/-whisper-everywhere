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
    private val silenceThreshold: Int = 250,
    private val pauseMs: Long = 800,
    private val maxSegmentMs: Long = 15000,
) {
    private var hasSpoken = false
    private var lastVoiceMs = 0L
    private var segmentStartMs = 0L

    /** EMA of non-voiced chunk amplitude — the room's noise floor. */
    private var floorEma = 0f

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

        floorEma = if (floorEma == 0f) amplitude.toFloat()
                   else floorEma + FLOOR_ALPHA * (amplitude - floorEma)

        if (!hasSpoken) return false

        val pausedLongEnough = amplitude <= effectiveSilence() && (nowMs - lastVoiceMs) >= pauseMs
        val segmentTooLong = (nowMs - segmentStartMs) >= maxSegmentMs
        if (pausedLongEnough || segmentTooLong) {
            reset()
            return true
        }
        return false
    }

    /**
     * Effective silence threshold. `max()` with the configured floor makes regression IMPOSSIBLE
     * by construction: in a quiet room floorEma * 1.6 is below silenceThreshold, so this returns
     * exactly the original value and behaviour is byte-identical. `min()` with
     * voiceThreshold - 1 stops a loud room from raising the bar until speech itself counts as
     * silence.
     *
     * Fixes a real dead band: with a fixed 250, any room whose floor sits between 251 and 499
     * opens a segment (>=500) but can NEVER satisfy the close condition, so dispatch silently
     * degrades to the 15-second wall-clock cap with no log distinguishing it.
     *
     * Only the SILENCE threshold adapts. voiceThreshold stays fixed — raising it would hurt soft
     * talkers, which is the opposite of the goal.
     */
    private fun effectiveSilence(): Int =
        minOf(maxOf(silenceThreshold, (floorEma * NOISE_FLOOR_MULTIPLIER).toInt()), voiceThreshold - 1)

    /** True if speech has been detected since the last commit/reset. */
    fun hasPendingSpeech(): Boolean = hasSpoken

    fun reset() {
        hasSpoken = false
        lastVoiceMs = 0L
        segmentStartMs = 0L
        floorEma = 0f
    }

    companion object {
        /** ~2 s of 32 ms chunks to converge. */
        private const val FLOOR_ALPHA = 0.02f
        /** Headroom above the measured floor before a sample counts as silence. */
        private const val NOISE_FLOOR_MULTIPLIER = 1.6f
    }
}
