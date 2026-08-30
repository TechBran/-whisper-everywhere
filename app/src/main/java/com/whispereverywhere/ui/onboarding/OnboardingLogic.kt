package com.whispereverywhere.ui.onboarding

import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.npu.NpuPackFetch
import com.whispereverywhere.ui.onboarding.OnboardingSetupViewModel.EngineState

/**
 * Pure decisions for the guided onboarding flow and Home's permission chip — kept free of Compose
 * and Android so every rule is a plain JVM test ([OnboardingLogicTest]).
 */
object OnboardingLogic {

    /**
     * The flow's fixed step order. LANGUAGE sits between PERMISSIONS and ENGINES since 4.2 F6 —
     * the 3.8 owner ruling folded in (language BEFORE model download): the pick lands before the
     * model step, so the steer, the download and the first session all read it.
     */
    enum class Step { PERMISSIONS, LANGUAGE, ENGINES, CLOUD }

    fun next(step: Step): Step? = when (step) {
        Step.PERMISSIONS -> Step.LANGUAGE
        Step.LANGUAGE -> Step.ENGINES
        Step.ENGINES -> Step.CLOUD
        Step.CLOUD -> null
    }

    fun previous(step: Step): Step? = when (step) {
        Step.PERMISSIONS -> null
        Step.LANGUAGE -> Step.PERMISSIONS
        Step.ENGINES -> Step.LANGUAGE
        Step.CLOUD -> Step.ENGINES
    }

    // ------------------------------------------------------------------ language step (4.2 F6)

    /**
     * The language step's hint — the 3.8 spec's own sentence. OUR-OWN-APP relative (a fact about
     * how this app's multilingual models behave when handed a language), never a cross-app claim.
     */
    const val LANGUAGE_HINT = "Choosing a language makes multilingual transcription faster."

    /**
     * The auto row's subtitle — THE 3.8 OWNER RULING'S TEXT, CARRIED VERBATIM. It is the honest
     * disclosure of the cost [LANGUAGE_HINT] beside it asserts, and the plan certification
     * already restored it once after a softer substitute dropped the disclosed cost (cert round
     * 1, revision 5). The ruling stands unless the owner re-rules; nothing here re-asks.
     */
    const val AUTO_LANGUAGE_SUBTITLE = "Slower on multilingual models — detects per session."

    /** The chip on the device-locale row. A suggestion with a stated reason, never a pick. */
    const val DEVICE_LANGUAGE_BADGE = "Your device's language"

    /**
     * The supported code the device's language tag resolves to, or null when the 54-language
     * list does not carry it. Accepts either separator ("en-US", "en_GB") and any case — the
     * same tolerance as [com.whispereverywhere.model.ModelTierCopy.steerIdForLanguageTag],
     * because callers pass whatever `Locale.toLanguageTag()` handed them. `"auto"` is a list
     * entry, never a device language — no tag can resolve to it here.
     */
    fun deviceLanguageCode(deviceLanguageTag: String): String? {
        val code = deviceLanguageTag.substringBefore('-').substringBefore('_').lowercase()
        return PreferencesManager.SUPPORTED_LANGUAGES
            .firstOrNull { it.first != "auto" && it.first == code }
            ?.first
    }

    /**
     * The language step's rows, in render order: the DEVICE'S language first when
     * [PreferencesManager.SUPPORTED_LANGUAGES] carries it, then `"auto"`, then the remaining
     * entries in the list's own order (device language absent → auto leads). Always a
     * permutation of the one supported-language list — the same 54-plus-auto set Settings'
     * picker offers, so the two surfaces can never disagree about what exists.
     */
    fun languageRows(deviceLanguageTag: String): List<Pair<String, String>> {
        val all = PreferencesManager.SUPPORTED_LANGUAGES
        val device = deviceLanguageCode(deviceLanguageTag)?.let { code ->
            all.first { it.first == code }
        }
        val auto = all.first { it.first == "auto" }
        val leads = listOfNotNull(device, auto)
        return leads + all.filterNot { it in leads }
    }

    /**
     * The language step's Continue gate — enabled only once a row is PICKED. The 3.8 mandate is
     * a forced choice, the same no-preselection discipline as the model pick: the device-locale
     * row renders first and badged, and the user still taps.
     */
    fun languageContinueEnabled(picked: String?): Boolean = picked != null

    // ---------------------------------------------- the pack fetch on the engine card (4.2 F6)

    /** The engine card's label while the pack fetch is queued, transferring, or not yet begun. */
    const val FETCH_PREPARING = "Preparing"

    /** The engine card's label while Play's own download is moving bytes. Names the source. */
    const val FETCH_DOWNLOADING_FROM_PLAY = "Downloading from Google Play"

    /**
     * The engine card's label while Play waits for ITS OWN dialog to be answered — wifi-wait
     * and the >200 MB cellular consent both. The flow screen shows Play's dialog; this label is
     * what the row says meanwhile, so the wait is never silent.
     */
    const val FETCH_AWAITING_PLAY_CONSENT = "Waiting for your OK in the Google Play dialog"

    /** A cancelled fetch surfaces as Failed WITH the way forward — Retry re-enters ensureSpeech. */
    const val FETCH_CANCELLED_MESSAGE = "Download cancelled — tap Retry to start again."

    /**
     * The one string every import-adjacency refusal carries — `NpuPackFetch.SIDELOAD_ANSWER`
     * (four sideload codes), the APP/PACK_UNAVAILABLE alternatives and the empty-delivery
     * refusal all name `'Import model pair…' below` — and the marker [onboardingFetchRefusal]
     * rewrites on, because the onboarding flow has no import affordance below anything.
     */
    const val IMPORT_ADJACENCY_MARKER = "'Import model pair…' below"

    /**
     * The ONBOARDING surface's refusal for a fetch Play will not serve this install (F6 fix
     * round 1, I-1): truthful on THIS surface — no reference to an affordance the flow lacks,
     * and the way forward it actually has (an on-device model now, Settings' import later).
     * The CHOOSER surface keeps the ruled adjacency copy; see [onboardingFetchRefusal].
     */
    const val ONBOARDING_FETCH_REFUSAL =
        "This install can't fetch from Google Play — finish setup with an on-device model " +
            "and import from Settings later."

    /** The failed engine card's second action — the no-wedge escape (F6 fix round 1, I-1). */
    const val CHOOSE_DIFFERENT_MODEL = "Choose a different model"

    /**
     * Per-surface refusal copy (F6 fix round 1, I-1). F5's carrier rule renders `Failed.reason`
     * VERBATIM — conditioned, in F5's own handoff, on the SAF import affordance sitting below
     * the card. The CHOOSER keeps that adjacency and the ruled copy; ONBOARDING has no import
     * affordance, so a reason that names it would send the user to a control that does not
     * exist (and, on a sideloaded install, wedge the mandatory step behind a dead Retry). Any
     * reason carrying [IMPORT_ADJACENCY_MARKER] therefore renders [ONBOARDING_FETCH_REFUSAL]
     * here; every other reason flows verbatim — the carrier rule, narrowed only where its own
     * precondition fails.
     */
    fun onboardingFetchRefusal(reason: String): String =
        if (IMPORT_ADJACENCY_MARKER in reason) ONBOARDING_FETCH_REFUSAL else reason

    /**
     * The NO-WEDGE rule (F6 fix round 1, I-1): a FAILED speech engine — every Failed terminal,
     * gated fetch refusals included — offers the way back to the chooser, where the CPU tiers
     * are always pickable, so the mandatory model step stays completable on every path. Retry
     * stays the primary action (owner decision 2026-08-18); this is the second, and it never
     * unlocks Continue.
     */
    fun showChooseDifferentModel(speech: EngineState): Boolean = speech is EngineState.Failed

    /**
     * One [NpuPackFetch.FetchState] to exactly one [EngineState] — the whole translation between
     * the F5 pack controller and the engines step's card, total over the sealed vocabulary so
     * the card cannot receive a state this table never produced (4.2 F6).
     *
     * [NpuPackFetch.FetchState.Failed] is the F5 refusal CARRIER: its reason is finished,
     * user-facing copy and flows through VERBATIM — except the import-adjacency family, which
     * this surface rewrites through [onboardingFetchRefusal] (F6 fix round 1, I-1: the copy's
     * own precondition, the import control below the card, does not hold here).
     * `Idle` reads as preparing for two reasons: the collector only ever runs after `start()`,
     * so the rest state mid-collect is usually a fetch that has not published yet — and Idle is
     * ALSO `NOT_INSTALLED`'s mapping (`advance`), which Play can replay after an out-of-band
     * cancel; StateFlow conflation can then skip the terminal Cancelled and park the card at
     * Preparing (narrow, device-only — an F8 watch item beside the F5 replay note; recovery is
     * relaunch). Failed-on-Idle would flash false refusals in the start gap, so non-terminal
     * stays the right reading. `Installed` maps to Ready — by the controller's contract it is
     * only published after the pair is census-verified, renamed into place and announced.
     */
    fun engineStateForFetch(fetch: NpuPackFetch.FetchState): EngineState = when (fetch) {
        is NpuPackFetch.FetchState.Idle,
        is NpuPackFetch.FetchState.Pending,
        is NpuPackFetch.FetchState.Transferring,
        -> EngineState.Working(OnboardingSetupViewModel.INDETERMINATE, FETCH_PREPARING)
        is NpuPackFetch.FetchState.Downloading -> EngineState.Working(
            NpuPackFetch.pct(fetch.soFar, fetch.total), FETCH_DOWNLOADING_FROM_PLAY,
        )
        is NpuPackFetch.FetchState.Verifying -> EngineState.Working(
            NpuPackFetch.pct(fetch.soFar, fetch.total), OnboardingSetupViewModel.VERIFYING,
        )
        is NpuPackFetch.FetchState.NeedsConfirmation ->
            EngineState.Working(OnboardingSetupViewModel.INDETERMINATE, FETCH_AWAITING_PLAY_CONSENT)
        is NpuPackFetch.FetchState.Installed -> EngineState.Ready
        is NpuPackFetch.FetchState.Failed -> EngineState.Failed(onboardingFetchRefusal(fetch.reason))
        is NpuPackFetch.FetchState.Cancelled -> EngineState.Failed(FETCH_CANCELLED_MESSAGE)
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
