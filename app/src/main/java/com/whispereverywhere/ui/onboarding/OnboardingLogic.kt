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
     * The language step's hint — what picking ONE language does, in the owner's 2026-09-03 ruling
     * (the build 85 re-rule of the 3.8 sentence). The 3.8 text said a picked language makes
     * multilingual transcription "faster": on the shipping AI chip tier the saving is a ~9 ms
     * detect step inside a ~2 s commit, and on the CPU tier [com.whispereverywhere.transcription.LanguagePin]
     * already skips the detect from the second segment on — a speed claim nobody can notice. What
     * a pick actually does on a MULTILINGUAL tier: an explicit language passes through to whisper
     * unchanged (LanguagePin never pins it; NpuWhisperBackend runs no detect for it), so every
     * phrase is that language — the accuracy case for a one-language speaker, and for dictation.
     * SCOPED to the multilingual models on purpose (review MC-1): on an ENGLISH-scope tier the
     * service REPLACES the pick with "en" (FloatingBubbleService, `connect lang resolved=`), and
     * `pro` (small.en) is pickable on every non-NPU device and heads the lineup on an en-US phone
     * whatever was picked — an unscoped "locks every phrase" was false there. "Multilingual" is
     * the app's own name for those tiers (WhisperModel displayNames; ModelTierCopy). OUR-OWN-APP
     * relative, never a cross-app claim. Pinned verbatim by OnboardingLogicTest.
     */
    const val LANGUAGE_HINT =
        "On the multilingual models, choosing one language locks every phrase to it — the most " +
            "accurate choice if you only ever speak one, and the right one for dictation."

    /**
     * The auto row's subtitle — the owner's 2026-09-03 ruling: KEEP the Auto option, and make its
     * explanation true for the shipping tier. The 3.8 text ("Slower on multilingual models —
     * detects per session.") described the CPU path (LanguagePin: first detection wins for the
     * session) and was false on both counts for the AI chip model, where the detect pass runs per
     * utterance (NpuWhisperBackend: `lang == null` -> nativeDetectLanguage on every segment, ~9 ms
     * of a ~2 s commit) and a mixed-language video therefore comes out in each language. The
     * sentence is SCOPED to that model on purpose: this step runs before the model is picked, so
     * nothing here can tell the tier, and the CPU tiers' per-session pin is neither claimed nor
     * denied. Pinned verbatim by OnboardingLogicTest; a change here is a decision, not a tidy-up.
     */
    const val AUTO_LANGUAGE_SUBTITLE =
        "On the AI chip model, detects the language of each phrase as you speak — mixed-language " +
            "audio (a video, a bilingual conversation) comes out in each language, at no cost " +
            "you will notice."

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
     *
     * Its other half since 4.3 is [chooserAlsoOfferedIds] — the escape is only an escape if the
     * chooser it returns to still HAS the CPU tiers.
     */
    fun showChooseDifferentModel(speech: EngineState): Boolean = speech is EngineState.Failed

    /**
     * What joins a capable device's one-card lineup on the ONBOARDING chooser (4.3) —
     * `WhisperCatalog.pickableFor`'s `alsoOfferedIds`, produced for this surface.
     *
     * **This is the no-wedge escape's other half, and without it 4.3 breaks the F6 contract.**
     * 4.3 narrows a capable device's chooser to `npu-turbo` alone. A SIDELOADED capable device is
     * offered that tier — `fetchableNpuTierIds` asks the census whether the FAMILY has a measured
     * pack, which it cannot know Play will refuse for this particular install — so the fetch
     * fails, `showChooseDifferentModel` sends the user back to the chooser, and a chooser holding
     * one undeliverable card would wedge a MANDATORY step with no completable path. That is
     * precisely the defect I-1 was written to close, re-opened by a narrowing rule that could not
     * see it.
     *
     * So the one-tier rule is **suspended once the delivery has actually failed** — not before.
     * The owner's ruling is about what a working capable device is OFFERED; it was never about
     * refusing a user any model at all on a device Play cannot serve. Before a failure this
     * returns the installed ids alone, so a fresh capable install still sees exactly one card.
     *
     * The suspension carries no new mechanism: the CPU ids simply join `alsoOfferedIds`, the same
     * door an already-installed tier walks through, so the ordering, the steer and the badge are
     * untouched — turbo still heads the lineup wearing the chip, with the CPU tiers below it in
     * the 3.7 language order.
     *
     * @param oneTierDeliveryFailed the user reached the chooser through the failed-engine escape.
     *        It is DURABLE state on the flow screen rather than a read of the live engine state,
     *        because `resetSpeechForReChoice()` returns that state to `Pending` on the way back —
     *        by the time the chooser renders, the failure is over and only the reason the user is
     *        standing here remains true.
     */
    fun chooserAlsoOfferedIds(
        installedIds: Set<String>,
        oneTierDeliveryFailed: Boolean,
    ): Set<String> =
        if (oneTierDeliveryFailed) {
            installedIds + com.whispereverywhere.model.WhisperCatalog.pickable.map { it.id }
        } else {
            installedIds
        }

    /**
     * Whether escaping THIS failed engine means the one tier could not be DELIVERED here — the
     * input [chooserAlsoOfferedIds] actually needs (4.3 fix round, I-2).
     *
     * **The first shipping version latched on the escape itself**, and the escape is offered for
     * every `EngineState.Failed` there is. So a user on a perfectly deliverable Play device who
     * cancelled the pack download once — their OWN cancel — or hit one transient refusal, and then
     * tapped "Choose a different model", had the 190 MB and 358 MB tiers restored for the rest of
     * onboarding. The latch is never cleared, so that is permanent: a transient event undoing the
     * ruling the branch exists to apply. It failed safe (no wedge, turbo still heads the lineup)
     * but it was broader than its own contract, and the contract is the honest one.
     *
     * **Two conditions, and each excludes a different non-delivery.**
     *
     *  1. **The failure must be the GATED tier's.** A CPU tier's own download exception
     *     (`OnboardingSetupViewModel`'s catch) says nothing about Play's ability to deliver a
     *     pack — and on a capable device a CPU tier can only have been picked from an already
     *     suspended lineup, so latching on it would be circular.
     *  2. **It must be an OUTCOME of a delivery, not a non-start.** [FETCH_CANCELLED_MESSAGE] is
     *     the user's own cancel and [FETCH_BUSY_WITH_ANOTHER_MODEL] is a fetch that never began;
     *     neither is Play answering about this install, and neither may cost the owner the ruling.
     *
     * **Everything else about a gated tier's fetch DOES latch, and that is deliberately one step
     * wider than "the sideload/undeliverable family".** That family (the two `ONBOARDING_FETCH_*`
     * refusals) is the clear case, but a pack that downloads and then fails VERIFICATION is also a
     * delivery that did not produce a model, and it can be persistent — a device whose pack never
     * verifies would otherwise sit on one card it can never install, on a MANDATORY step, which is
     * the wedge this whole mechanism exists to prevent. The narrowing must not re-open the hole it
     * was narrowed inside of. Retry stays the primary action either way.
     *
     * @param failedTierId the tier the failed engine was working on — the flow's `pickedTierId`,
     *        read BEFORE the escape clears it.
     */
    fun oneTierDeliveryFailed(failedTierId: String?, reason: String): Boolean {
        val tier = com.whispereverywhere.model.WhisperCatalog.byId(failedTierId) ?: return false
        if (!tier.gated) return false
        return reason != FETCH_CANCELLED_MESSAGE && reason != FETCH_BUSY_WITH_ANOTHER_MODEL
    }

    /**
     * The tier pick, revalidated against the lineup actually on screen (4.3 fix round, I-3).
     *
     * **Both producers on the engines step are async** — `produceState`, and the gate's first read
     * dlopens ~7.9 MiB of QNN — so a capable device renders the pre-4.3 `[pro, multi]` lineup for
     * the length of that window and then narrows to `[npu-turbo]`. A tap inside the window used to
     * SURVIVE the narrowing: the card vanished, `pickedTierId` kept its value, `tierPicked` stayed
     * true, and the footer's Download wrote `prefs.selectedModelId = pro` or `multi` **on a capable
     * device, with no card on screen for it** — precisely the outcome the ruling forbids, reached
     * without the user doing anything wrong.
     *
     * Pre-4.3 the same race existed and was harmless, because a pick's card never left the list.
     * 4.3 is what made a lineup able to shrink under a pick, so 4.3 owns the guard.
     *
     * Dropping the pick rather than re-pointing it is the honest repair: re-pointing would choose
     * FOR the user, and this chooser's oldest rule is that the steer suggests and the user taps.
     * The Download button simply returns to disabled, which is the state a fresh capable install
     * is in anyway.
     */
    fun revalidatePick(picked: String?, lineup: List<String>): String? =
        if (picked != null && picked in lineup) picked else null

    /**
     * Whether a finished download should LEAVE the chooser (4.3 fix round, I-1c).
     *
     * `DownloadState.Done` is the screen's global model-ready signal and the activity pops the
     * picker to Home on it. That is right for every download the user started by choosing a
     * model — and wrong for the decline recovery, which is not a choice of model but a repair of
     * a broken one: it pops the user off the very screen that was explaining what just happened,
     * before [com.whispereverywhere.npu.NpuTierStatus.RECOVERY_SWITCH_NOTE] can be read and
     * before the note and button they were looking at retire. The explanation must outlive the
     * action that needs explaining.
     *
     * Keyed on the RECOVERY TAP, not on the tier id alone: an ordinary Download tap on the
     * `multi` card — the entire non-capable fleet's normal path — completes with the same
     * `Done(modelId)` and must keep navigating exactly as it always has.
     */
    fun downloadLeavesTheChooser(doneModelId: String?, recoveryTapped: Boolean): Boolean =
        !(recoveryTapped && doneModelId == com.whispereverywhere.npu.NpuTierStatus.RECOVERY_TIER_ID)

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
        "Another model is already downloading from Google Play. Wait for it to finish, then " +
            "tap Get again."

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
     * Whether a shown [CHOOSER_FETCH_BUSY] is STILL TRUE (F7 micro-round, m-2 of the re-review).
     * The sentence claims another fetch is running; the moment that fetch reaches a terminal
     * state it becomes a lie that the card would otherwise keep displaying until the user
     * happened to tap again. The screen clears the refusal on every controller-state change
     * this answers false for.
     *
     * These are the STATE half of `NpuPackController.isBusy()` — the half a screen can observe.
     * The job half (an install coroutine finishing just after `Installed` is published) is not
     * observable here, so this can clear a refusal a moment EARLY: the safe direction, since a
     * stale-cleared card simply offers Get again and a genuinely busy controller answers with a
     * fresh, accurate refusal on the next tap.
     */
    fun chooserRefusalStillStands(fetch: NpuPackFetch.FetchState): Boolean = when (fetch) {
        is NpuPackFetch.FetchState.Pending,
        is NpuPackFetch.FetchState.Downloading,
        is NpuPackFetch.FetchState.Transferring,
        is NpuPackFetch.FetchState.NeedsConfirmation,
        is NpuPackFetch.FetchState.Verifying,
        -> true
        is NpuPackFetch.FetchState.Idle,
        is NpuPackFetch.FetchState.Installed,
        is NpuPackFetch.FetchState.Failed,
        is NpuPackFetch.FetchState.Cancelled,
        -> false
    }

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
