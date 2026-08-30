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
     * The sideload family's own sentence — present in exactly the refusals whose CAUSE is the
     * install itself (`NpuPackFetch`'s sideload answer: API_NOT_AVAILABLE, PLAY_STORE_NOT_FOUND,
     * APP_NOT_OWNED, UNRECOGNIZED_INSTALLATION). [onboardingFetchRefusal] splits the adjacency
     * family on it (4.2 F7, the F6 re-review's rider): "This install can't fetch" is TRUE for
     * these and FALSE for the family's other members — APP/PACK_UNAVAILABLE and the empty
     * delivery are transient, app-version or device-group causes, not install causes.
     */
    const val SIDELOAD_MARKER = "it wasn't installed from Play"

    /**
     * The ONBOARDING surface's refusal for a fetch Play will not serve this install (F6 fix
     * round 1, I-1): truthful on THIS surface — no reference to an affordance the flow lacks,
     * and the way forward it actually has (an on-device model now, Settings' import later).
     * Since 4.2 F7 it renders only for the INSTALL-CAUSE family ([SIDELOAD_MARKER]), whose
     * refusals its leading clause is true of. The CHOOSER surface keeps the ruled adjacency
     * copy; see [onboardingFetchRefusal].
     */
    const val ONBOARDING_FETCH_REFUSAL =
        "This install can't fetch from Google Play — finish setup with an on-device model " +
            "and import from Settings later."

    /**
     * The onboarding refusal for an adjacency-family failure that is NOT the install's fault
     * (4.2 F7, the F6 re-review's rider): APP_UNAVAILABLE is transient, PACK_UNAVAILABLE is an
     * app-version cause, the empty delivery a device-group one — "This install can't fetch"
     * would be false for each. The leading claim here is deliberately the weakest true one:
     * "couldn't deliver" holds for all three, and for any FUTURE adjacency-marked refusal that
     * lacks the sideload sentence (the fail-safe direction: an unknown family member gets the
     * claim that cannot be false, never the one that blames the install).
     */
    const val ONBOARDING_FETCH_UNDELIVERED =
        "Google Play couldn't deliver this model — finish setup with an on-device model " +
            "and import from Settings later."

    /** The failed engine card's second action — the no-wedge escape (F6 fix round 1, I-1). */
    const val CHOOSE_DIFFERENT_MODEL = "Choose a different model"

    /**
     * Per-surface refusal copy (F6 fix round 1, I-1; split by marker family at F7 per the F6
     * re-review's rider). F5's carrier rule renders `Failed.reason` VERBATIM — conditioned, in
     * F5's own handoff, on the SAF import affordance sitting below the card. The CHOOSER keeps
     * that adjacency and the ruled copy; ONBOARDING has no import affordance, so a reason that
     * names it would send the user to a control that does not exist (and, on a sideloaded
     * install, wedge the mandatory step behind a dead Retry). Any reason carrying
     * [IMPORT_ADJACENCY_MARKER] therefore renders this surface's own copy — SPLIT so each
     * family's leading claim is true: the install-cause family ([SIDELOAD_MARKER]) renders
     * [ONBOARDING_FETCH_REFUSAL], everything else in the adjacency family renders
     * [ONBOARDING_FETCH_UNDELIVERED]. Every non-adjacency reason flows verbatim — the carrier
     * rule, narrowed only where its own precondition fails.
     */
    fun onboardingFetchRefusal(reason: String): String = when {
        IMPORT_ADJACENCY_MARKER !in reason -> reason
        SIDELOAD_MARKER in reason -> ONBOARDING_FETCH_REFUSAL
        else -> ONBOARDING_FETCH_UNDELIVERED
    }

    /**
     * The NO-WEDGE rule (F6 fix round 1, I-1): a FAILED speech engine — every Failed terminal,
     * gated fetch refusals included — offers the way back to the chooser, where the CPU tiers
     * are always pickable, so the mandatory model step stays completable on every path. Retry
     * stays the primary action (owner decision 2026-08-18); this is the second, and it never
     * unlocks Continue.
     */
    fun showChooseDifferentModel(speech: EngineState): Boolean = speech is EngineState.Failed

    /**
     * The refusal published when the gated fetch cannot ATTACH: the controller answered
     * `start() == false` while ITS active fetch is some other tier's (F6 review M-3, landed in
     * F7 — the chooser can start a fetch this ViewModel never asked for). Retry re-enters
     * ensureSpeech once the other fetch reaches a terminal state; the choose-different escape
     * stays available, so nothing wedges.
     */
    const val FETCH_BUSY_WITH_ANOTHER_MODEL =
        "Another model is downloading from Google Play right now. Wait for it to finish, " +
            "then tap Retry."

    /**
     * The CHOOSER's sentence for that same refusal (F7 fix round 1, I-1). Every clause is true on
     * THIS surface: the other card really is downloading, its Cancel really is on it, and this
     * card's own button really is named Get. No position word — the sibling card can sit above or
     * below this one depending on the steer.
     */
    const val CHOOSER_FETCH_BUSY =
        "Another model is already downloading from Google Play. Cancel that download, or wait " +
            "for it to finish, then tap Get again."

    /**
     * Whether the gated route may attach its collector to the controller's state — null — or
     * must refuse instead (the returned copy). The ONE shape that refuses: `start()` was
     * denied AND the controller's active fetch names a DIFFERENT tier — attaching there would
     * mirror that tier's states onto this card and, on its Installed, persist the selection
     * for a tier this card never fetched (F6 review M-3, carried to F7 by name). Every other
     * shape attaches: a successful start is ours by definition; a denied start with OUR tier
     * active is the re-attach path (double tap, relaunch onto Play's surviving download); and
     * a denied start with NO active tier is the controller's own no-pack refusal, whose
     * published words the collector should mirror rather than bury under an invented busy
     * story.
     */
    fun fetchAttachRefusal(started: Boolean, activeTierId: String?, tierId: String): String? =
        if (!started && activeTierId != null && activeTierId != tierId) {
            FETCH_BUSY_WITH_ANOTHER_MODEL
        } else {
            null
        }

    /**
     * The CHOOSER's words for the same refusal (F7 fix round 1, I-1). The chooser is the surface
     * where the collision is most reachable — on a capable fresh Play install BOTH gated cards
     * render "Get on Google Play" at once, so tapping the second one while the first fetches is
     * one tap away — and before this fix that tap was a silent no-op: `start()` returned false,
     * the controller published nothing, and the card kept its enabled button. The house rule the
     * import panel states thirty lines below that button ("a silent 'nothing happened' is the one
     * outcome an import is never allowed to have") applies to a fetch exactly as it does to an
     * import.
     *
     * Per-surface COPY over one shared RULE — the F6 fix-round doctrine: [fetchAttachRefusal]
     * remains the single decision (this function cannot disagree with it about WHEN a tap is
     * refused, because it asks it), and only the sentence differs, because the onboarding card's
     * "tap Retry" names a control the chooser card does not have. [CHOOSER_FETCH_BUSY] names what
     * the chooser DOES have: the other card's Cancel, and this card's own button.
     */
    fun chooserFetchRefusal(started: Boolean, activeTierId: String?, tierId: String): String? =
        if (fetchAttachRefusal(started, activeTierId, tierId) == null) null else CHOOSER_FETCH_BUSY

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
