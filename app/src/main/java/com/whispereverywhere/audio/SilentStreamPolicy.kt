package com.whispereverywhere.audio

/**
 * Is a silent capture stream BLOCKED, or merely quiet right now?
 *
 * `PlaybackAudioCapturer` watches for digital silence because a DRM-protected app (Netflix,
 * Hulu, Sling) opts out of playback capture and its stream arrives as zeroes — there is nothing
 * to transcribe, and the only route to that audio is the speakers into the microphone.
 *
 * **Why the distinction is load-bearing (owner rule, 2026-09-02).** The watchdog used to fire on
 * "no energy for 3 s" alone, and a PAUSED video is silent by that measure. So pausing a YouTube
 * video for three seconds was read as a DRM block and handed the live session to the microphone
 * — the same bleed the device-audio latch exists to prevent ("we're trying to keep the microphone
 * out of it so someone could transcribe a YouTube video without their actual spoken words being
 * dictated"), arriving through a different door.
 *
 * A blocked stream is silent from its FIRST buffer and stays that way; a paused one has already
 * carried audio. That single bit — has this stream ever been loud? — separates them exactly, with
 * no new timers and no guessing about the app.
 *
 * Pure: no Android types, so the rule is JVM-testable without an `AudioRecord`.
 */
object SilentStreamPolicy {

    /** Silence this long on a stream that never carried audio means the app blocked capture. */
    const val SILENT_TIMEOUT_MS = 3000L

    /**
     * True when this stream should be treated as blocked by the source app.
     *
     * @param everCarriedAudio has ANY buffer on this capturer been above the noise floor?
     * @param silentForMs how long the stream has been below it.
     *
     * A stream that has carried audio can never be judged blocked, however long it goes quiet:
     * that is a pause, a silent passage, or a gap between tracks, and the session stays on device
     * audio where the user put it.
     */
    fun isBlockedByApp(everCarriedAudio: Boolean, silentForMs: Long): Boolean =
        !everCarriedAudio && silentForMs >= SILENT_TIMEOUT_MS
}
