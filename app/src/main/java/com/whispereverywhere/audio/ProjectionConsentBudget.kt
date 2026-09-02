package com.whispereverywhere.audio

/**
 * How many times ONE recording session may raise the screen-capture consent dialog (4.3.1 D).
 *
 * Why it exists: the dialog's own appearance pauses the video and its dismissal resumes it, so a
 * cancel makes the media detector fire "playback started" again, and the handover asked again —
 * every cancel, forever, unless the user stopped the session first (owner report 2026-09-02).
 * Two asks: the second is the "cancelled by mistake" recovery; the third would be the trap.
 * Counts ASKS launched, not answers, so a dialog the system dismissed still spent one.
 */
class ProjectionConsentBudget(private val maxAsks: Int = MAX_ASKS_PER_SESSION) {
    var asked: Int = 0
        private set

    fun mayAsk(): Boolean = asked < maxAsks
    fun noteAsked() { asked++ }
    fun reset() { asked = 0 }

    companion object {
        const val MAX_ASKS_PER_SESSION = 2
    }
}
