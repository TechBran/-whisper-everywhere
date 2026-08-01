package com.whispereverywhere.ui.onboarding

/**
 * Pure decisions for the guided onboarding flow and Home's permission chip — kept free of Compose
 * and Android so every rule is a plain JVM test ([OnboardingLogicTest]).
 */
object OnboardingLogic {

    /** The flow's fixed step order. */
    enum class Step { PERMISSIONS, ENGINES, CLOUD }

    fun next(step: Step): Step? = when (step) {
        Step.PERMISSIONS -> Step.ENGINES
        Step.ENGINES -> Step.CLOUD
        Step.CLOUD -> null
    }

    fun previous(step: Step): Step? = when (step) {
        Step.PERMISSIONS -> null
        Step.ENGINES -> Step.PERMISSIONS
        Step.CLOUD -> Step.ENGINES
    }

    /**
     * The engines step lets the user move on as soon as DICTATION is possible — the speech model.
     * The ~365 MB voice explicitly keeps downloading behind the flow (activity-scoped ViewModel);
     * holding the user hostage to it would punish exactly the slow connections that need the
     * escape most. A FAILED speech model also unblocks Continue: onboarding must never wedge — the
     * row shows Retry, and Home's setup banner remains the manual path.
     */
    fun enginesContinueEnabled(speechReady: Boolean, speechFailed: Boolean): Boolean =
        speechReady || speechFailed

    /** Sub-line under the engines Continue button; null when nothing needs saying. */
    fun enginesContinueHint(speechReady: Boolean, voiceReady: Boolean): String? = when {
        speechReady && !voiceReady ->
            "The read-aloud voice keeps downloading in the background — no need to wait."
        else -> null
    }

    /**
     * How many of the three permissions the BUBBLE needs (mic, overlay, accessibility) are
     * missing. Notification access is deliberately not counted: media detection degrades
     * gracefully without it, and the bubble's canEnable gate has never included it.
     */
    fun missingBubblePermissions(mic: Boolean, overlay: Boolean, accessibility: Boolean): Int =
        listOf(mic, overlay, accessibility).count { !it }

    /**
     * Home's permission chip line, or null when everything is granted (the clean dashboard stays
     * clean — the chip only exists while something is actually wrong; owner report 2026-08-01:
     * granted permissions were visible ONLY in Settings, missing ones nowhere at all).
     */
    fun homePermissionChipText(missing: Int): String? = when {
        missing <= 0 -> null
        missing == 1 -> "1 permission still needed — tap to review"
        else -> "$missing permissions still needed — tap to review"
    }
}
