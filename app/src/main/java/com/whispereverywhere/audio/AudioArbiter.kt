package com.whispereverywhere.audio

/**
 * Single owner of the STT/TTS exclusivity rule (Track F, user decision: "both things won't
 * happen at the same time"). Deliberately holds NO state of its own — it queries the live
 * components through providers so it can never drift from reality.
 *
 * Rules:
 *  - Capture wins instantly over speech: starting a recording stops any read-aloud dead.
 *  - Speech defers to capture: asking to speak during a session asks the session to FINISH
 *    (graceful — the transcript completes) and is denied for now; the user taps again once
 *    the session wraps. Speech never barges into a live transcription.
 */
object AudioArbiter {

    /** RECORDING or FINALIZING — a transcript is still being produced. */
    @Volatile var isCapturing: () -> Boolean = { false }

    /** Ask the live capture session to finish normally (same path as a stop tap). */
    @Volatile var stopCaptureGracefully: () -> Unit = {}

    @Volatile var isSpeaking: () -> Boolean = { false }

    /** Instant TTS kill (cancel synthesis + flush playback). */
    @Volatile var stopSpeech: () -> Unit = {}

    /** TTS asks to start. True = clear to speak now. */
    fun requestSpeak(): Boolean {
        if (isCapturing()) {
            stopCaptureGracefully()
            return false
        }
        if (isSpeaking()) stopSpeech() // restart-with-new-text case
        return true
    }

    /** Recording asks to start. Always granted; speech is stopped first. */
    fun requestCapture(): Boolean {
        if (isSpeaking()) stopSpeech()
        return true
    }

    /** Test/reset hook — restores the no-op defaults. */
    fun reset() {
        isCapturing = { false }
        stopCaptureGracefully = {}
        isSpeaking = { false }
        stopSpeech = {}
    }
}
