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
     * The engines step releases the user only when DICTATION is possible — the speech model is
     * Ready. Owner decision 2026-08-18 (mandatory model): this deliberately reverses the earlier
     * never-wedge rule that let a FAILED download unlock Continue — the app cannot do its job
     * without a local model, so a failure holds the step and the row's Retry is the way forward.
     * The ~365 MB voice still downloads in the background and never blocks (activity-scoped
     * ViewModel); read-aloud is not load-bearing for dictation.
     */
    fun enginesContinueEnabled(speechReady: Boolean): Boolean = speechReady

    /** Sub-line under the engines Continue button; null when nothing needs saying. */
    fun enginesContinueHint(speechReady: Boolean, voiceReady: Boolean): String? = when {
        speechReady && !voiceReady ->
            "The read-aloud voice keeps downloading in the background — no need to wait."
        else -> null
    }

    /** The chooser hint under the model cards (spec A3) — plants the switching habit. */
    const val TIER_SWITCH_HINT = "Not sure? Pick one — you can switch models anytime in Settings."

    /** The engines step's single primary action: Download until downloads begin, then Continue. */
    data class EnginesAction(val label: String, val enabled: Boolean, val startsDownloads: Boolean)

    /**
     * One pick, then no buttons (3.5.0 evolution of the 2026-08-01 owner decision): before any
     * download exists the primary action is "Download", enabled ONLY once a tier card is picked —
     * there is deliberately no preselection, so the disabled button is what forces the informed
     * choice. From the moment downloads begin the action is "Continue", gated by the
     * mandatory-model rule (owner decision 2026-08-18, [enginesContinueEnabled]): only a Ready
     * speech model unlocks it — a Failed download holds the step instead.
     */
    fun enginesPrimaryAction(
        downloadsBegun: Boolean,
        tierPicked: Boolean,
        speechReady: Boolean,
    ): EnginesAction =
        if (!downloadsBegun) {
            EnginesAction(label = "Download", enabled = tierPicked, startsDownloads = true)
        } else {
            EnginesAction(
                label = "Continue",
                enabled = enginesContinueEnabled(speechReady),
                startsDownloads = false,
            )
        }

    /**
     * How many of the three permissions the BUBBLE needs (mic, overlay, accessibility) are
     * missing. Notification access is deliberately not counted: media detection degrades
     * gracefully without it, and the bubble's canEnable gate has never included it.
     */
    fun missingBubblePermissions(mic: Boolean, overlay: Boolean, accessibility: Boolean): Int =
        listOf(mic, overlay, accessibility).count { !it }

    /**
     * The permissions step's Continue gate (owner decision 2026-08-18: crucial steps are
     * mandatory). The three bubble permissions are required; notification access is deliberately
     * NOT required — media detection degrades gracefully without it, matching
     * [missingBubblePermissions].
     */
    fun permissionsContinueEnabled(mic: Boolean, overlay: Boolean, accessibility: Boolean): Boolean =
        missingBubblePermissions(mic, overlay, accessibility) == 0

    /** Sub-line under the permissions Continue button; null once nothing required is missing. */
    fun permissionsContinueHint(missing: Int): String? = when {
        missing <= 0 -> null
        missing == 1 -> "1 required permission still needed — notification access is optional."
        else -> "$missing required permissions still needed — notification access is optional."
    }

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
