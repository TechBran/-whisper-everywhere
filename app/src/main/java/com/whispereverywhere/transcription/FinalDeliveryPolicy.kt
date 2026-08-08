package com.whispereverywhere.transcription

/** Where the ONE stop-time write goes (W2 final-only commit). */
enum class InjectTarget {
    /** The field captured at beginInjectionSession — a TEXT_FIELD session's target. */
    SESSION_BOUND,

    /** Whatever field is focused AT STOP — the non-field session's opportunistic inject. */
    FINALIZE_FOCUS,
}

/** The whole delivery, decided once: at most one injection, at most one clipboard write. */
data class FinalDeliveryPlan(val inject: InjectTarget?, val copyWholeToClipboard: Boolean)

/**
 * The single decision point for what happens to a finished transcript. Pure and Android-free
 * so the whole table is a JVM test (FinalDeliveryPolicyTest pins all 16 combinations).
 *
 * Mid-session, NOTHING leaves the app (segments only accumulate); this table runs exactly once,
 * at stopRecording (and best-effort at onDestroy), on the full accumulated transcript.
 */
object FinalDeliveryPolicy {
    fun decide(
        isTextFieldSession: Boolean,
        degradedToClipboard: Boolean,
        hasLiveInputTarget: Boolean,
        transcriptBlank: Boolean,
    ): FinalDeliveryPlan = when {
        // Nothing was said: no write of any kind (the "No speech detected" toast covers UX).
        transcriptBlank -> FinalDeliveryPlan(inject = null, copyWholeToClipboard = false)

        // Field session, delivery healthy: the ONE injection, into the session-bound target.
        // Dead-node fallback lives INSIDE the write (resolveInjectionTarget), not here.
        isTextFieldSession && !degradedToClipboard ->
            FinalDeliveryPlan(inject = InjectTarget.SESSION_BOUND, copyWholeToClipboard = false)

        // Field session that degraded to clipboard: one consolidated copy, no injection.
        isTextFieldSession -> FinalDeliveryPlan(inject = null, copyWholeToClipboard = true)

        // Preview session: clipboard once, plus the finalize-time-focus inject when a real
        // target exists at stop (that targeting is BY DESIGN for non-field sessions).
        else -> FinalDeliveryPlan(
            inject = if (hasLiveInputTarget) InjectTarget.FINALIZE_FOCUS else null,
            copyWholeToClipboard = true,
        )
    }
}
