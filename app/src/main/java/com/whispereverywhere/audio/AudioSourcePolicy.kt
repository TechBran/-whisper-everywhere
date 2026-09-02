package com.whispereverywhere.audio

/** Which physical audio source a recording session is using right now. (Consumed by the
 *  FloatingBubbleService source state machine — Task 5 of the device-audio-capture plan.) */
enum class ActiveSource { MIC, PLAYBACK }

/** What the session SHOULD do, given the current world. Pure — JVM-unit-testable. */
sealed interface SourceDecision {
    data object UseMic : SourceDecision
    data object UsePlayback : SourceDecision
    data object RequestConsent : SourceDecision
}

object AudioSourcePolicy {
    /**
     * Decision table (user decisions 2026-07-17: media transcription cuts the mic entirely;
     * playback capture requires API 29+ and the user preference):
     *  - no media / pref off / pre-Q  -> mic (classic behavior)
     *  - media + projection token     -> playback capture
     *  - media, no token, budget spent -> mic (4.3.1 D: at most two dialogs per session)
     *  - media, no token yet          -> ask for consent (caller launches the trampoline)
     *
     * [consentAvailable] is the caller's per-session [ProjectionConsentBudget.mayAsk]. It only
     * ever turns an ask into the microphone: a stored projection is used whatever it says.
     */
    fun decide(
        mediaPlaying: Boolean,
        hasProjection: Boolean,
        sdkInt: Int,
        preferDeviceAudio: Boolean,
        consentAvailable: Boolean,
    ): SourceDecision = when {
        !mediaPlaying || !preferDeviceAudio || sdkInt < 29 -> SourceDecision.UseMic
        hasProjection -> SourceDecision.UsePlayback
        //  - media, no token, budget spent -> mic (4.3.1 D: at most two dialogs per session)
        !consentAvailable -> SourceDecision.UseMic
        else -> SourceDecision.RequestConsent
    }
}
