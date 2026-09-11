package com.whispereverywhere.transcription.stream

/**
 * WHETHER THE PREVIEWER'S PACK FETCHES ITSELF, and what Home's card says about it — 4.4.1, the
 * owner's discovery ruling of 2026-09-11:
 *
 * > *"users that already have these models, they should just see a card in the app and on open it
 * > should just automatically download. It's only seventy megabytes, it's nothing … That way the
 * > users don't have to discover the setting at all. But the setting works very well."*
 *
 * The previewer and its English gate were confirmed working on device that day; the gap was
 * DISCOVERY — the 73 MB model only ever arrived if the user found Settings → Live words. So the
 * pack now arrives on its own, and this object is the whole of the decision: pure, total over
 * [StreamingPackState], and JVM-pinned cell by cell (`PreviewAutoFetchTest`). The foreground hook
 * ([com.whispereverywhere.ui.screens.HomeScreen]'s live-words card) reads the inputs and calls
 * [decide]; the actuator ([PreviewAutoFetchController]) performs whatever it answered. Neither
 * decides anything of its own — `LiveWordsCardPinTest` holds them to that as source.
 *
 * ### The CONTROLLER RULING on data (2026-09-11), and where it lives
 *
 * The owner's *"it's nothing, everyone's phone can handle that"* is about STORAGE, and it is
 * accepted: 73 MB of `filesDir` is nothing on a modern phone. It is not nothing on a capped
 * plan, and this app has never moved a byte the user did not ask for. So the auto-fetch is
 * silent on an UNMETERED connection and becomes a CARD WITH A TAP on a metered one — the user's
 * consent, once, for their own data. One predicate ([unmetered]'s reading at the one call site)
 * is the whole flip if that ruling is ever overruled.
 *
 * The exception is [StreamingPackState.PackDelivered]: Google Play has already delivered those
 * bytes to the device and the install is a local verify + copy that touches no network at all
 * (`StreamingPackManager.installFromPack`). Metering cannot apply to a transfer that does not
 * happen, so that one route auto-installs on any connection — which is also the literal reading
 * of the brief's *"Installed/PackDelivered/in-flight never re-fetch"*: a delivered pack never
 * FETCHES, it installs.
 *
 * ### Why a [StreamingPackState.Repair] is never auto-anything
 *
 * A Repair means bytes are present under `filesDir` and the verdict was withdrawn — a load
 * failure's `markCorrupt`, or a file that went short. Auto-repairing it would re-fetch 73 MB on
 * EVERY launch of a device that keeps corrupting the install (the canary fails, the marker goes,
 * the next open fetches again), which is the one loop a silent fetch must never be able to enter.
 * The Settings row already reads "Repair the English preview model" and says what the repair
 * costs; a damaged install is the user's call, exactly as the delete is.
 *
 * ### What it does NOT re-decide
 *
 * `localPreviewArms` (FloatingBubbleService.kt:229) stays untouched — the owner tests on Auto
 * deliberately and found the English gate correct once explained. The INPUTS this decision shares
 * with that gate are read from the same places and never re-derived here: the switch
 * (`PreferencesManager.localPreviewEnabled`), a running batch job (`BatchJobController.active`),
 * and the pack's own installed state (through [StreamingPackState]). Two it deliberately does not
 * borrow: the session LANGUAGE (a pack that is not installed cannot arm in any language, and the
 * card is where the "pick English" sentence belongs) and `previewReady` (the recognizer cannot be
 * warm before the model exists). [localTierInstalled] is the SETUP-level reading of that gate's
 * `isCloudSession`: with no on-device tier installed every session is a cloud session, so the
 * previewer could never arm and 73 MB would buy the user nothing.
 */
object PreviewAutoFetch {

    /** What the foreground hook should do about the pack right now. */
    enum class Decision {
        /** Fetch and install it now, with no taps — the ruling's own case. */
        FETCH,

        /** Show the card with its one action and spend nothing until it is tapped. */
        OFFER,

        /** Say nothing: installed, declined, switched off, un-armable, or busy. */
        NONE,
    }

    /** What Home's live-words card shows. */
    enum class Card {
        NONE,

        /** The fetch or the install is running; the card carries its progress line. */
        WORKING,

        /** The metered (or backed-off) case: the offer, with the action that starts it. */
        OFFER,

        /** The one-time announcement that live words are on and English shows them. */
        INSTALLED,
    }

    /**
     * How long a failed auto-fetch stays quiet. The once-per-launch latch
     * ([PreviewAutoFetchController.attemptedThisLaunch]) already stops a retry inside one process;
     * this is what stops the OTHER loop the brief names — *"do not retry in a loop on a bad
     * network"* — where a user reopening the app all afternoon pays for a failing 73 MB transfer
     * once per process start. A day is the interval, because the failures that matter (no
     * connection, Play unavailable, no room) are the kind that are fixed in hours, not seconds,
     * and the card's own tap is available the whole time.
     */
    const val BACK_OFF_MS: Long = 24L * 60L * 60L * 1_000L

    /**
     * Whether a failure at [lastFailureAtMs] still silences the auto-fetch at [nowMs].
     *
     * 0 means "no failure recorded" — the pref's default — and is never a back-off, however the
     * clock reads. A NEGATIVE elapsed time (the stored stamp is in the future: a clock moved
     * back, a restored backup, a timezone-confused ROM) ENDS the back-off rather than extending
     * it: treating a future stamp as "still within the window" would silence the feature until
     * the clock caught up, which for a restored backup could be years. The cost of the other
     * choice is at most one extra attempt.
     */
    fun backedOff(lastFailureAtMs: Long, nowMs: Long): Boolean {
        if (lastFailureAtMs <= 0L) return false
        val elapsed = nowMs - lastFailureAtMs
        return elapsed in 0L until BACK_OFF_MS
    }

    /**
     * THE DECISION, and the only one. Total over [StreamingPackState]: a state added to that
     * machine must be answered here rather than fall through a wildcard into a silent 73 MB
     * transfer.
     *
     * The refusals are answered before the routes, and in this order, because each of them is a
     * reason the fetch would be WRONG rather than merely early:
     *
     * @param state `StreamingPackManager.state(pack)` — the one triage
     *        ([StreamingPackInstall.resolve]) that already knows which source this install has.
     * @param userSaidNo the persisted decision (`PreferencesManager.livePreviewDeclined`): the
     *        card was dismissed, or the model was DELETED in Settings. A delete is a decision and
     *        an auto-fetch must never undo it, so this is absolute — it silences the card too,
     *        forever, and only the Settings row can install after it.
     * @param showLiveWords `PreferencesManager.localPreviewEnabled`, the switch the gate reads
     *        (R3, default true). Off means the user turned the feature off; fetching its model
     *        would be 73 MB for a surface that will not draw.
     * @param localTierInstalled an on-device whisper tier exists. See the class KDoc: without one
     *        every session is a cloud session and the previewer can never arm.
     * @param unmetered the platform's own NOT_METERED reading (`ConnectivityMonitor.isUnmetered`,
     *        false when there is no active network at all). The CONTROLLER RULING's one predicate.
     * @param sessionActive a dictation session is being set up, recording, or finishing
     *        (`AudioArbiter.isCapturing` — the house's single owner of that question). A 73 MB
     *        transfer and a sha256 of it beside a live transcription is the same CPU contention
     *        `localPreviewArms` refuses a running batch job for.
     * @param batchJobActive `BatchJobController.active != null`, the gate's own input.
     * @param packWorkInFlight a fetch or install of this pack is already running, wherever it was
     *        started from ([PreviewAutoFetchController.busy], which includes the Settings row's
     *        own fetch through `StreamingPackController.isBusy`). Starting a second would write
     *        the same staging paths.
     * @param attemptedThisLaunch this process has already tried once
     *        ([PreviewAutoFetchController.attemptedThisLaunch]) — *"once per launch at most"*.
     * @param backedOff [backedOff] of the persisted failure stamp.
     */
    fun decide(
        state: StreamingPackState,
        userSaidNo: Boolean,
        showLiveWords: Boolean,
        localTierInstalled: Boolean,
        unmetered: Boolean,
        sessionActive: Boolean,
        batchJobActive: Boolean,
        packWorkInFlight: Boolean,
        attemptedThisLaunch: Boolean,
        backedOff: Boolean,
    ): Decision {
        // Nothing to arrive, and nothing to say: the recognizer already opens this install.
        if (state.isInstalled) return Decision.NONE
        // A damaged install is the user's call, never a silent re-fetch. See the class KDoc.
        if (state is StreamingPackState.Repair) return Decision.NONE
        if (userSaidNo) return Decision.NONE
        if (!showLiveWords) return Decision.NONE
        if (!localTierInstalled) return Decision.NONE
        if (sessionActive || batchJobActive) return Decision.NONE
        if (packWorkInFlight) return Decision.NONE
        val wouldSpendTheUsersData = when (state) {
            // Play has already put these bytes on the device: the install is a local verify +
            // copy and no connection is touched, so metering cannot apply to it.
            StreamingPackState.PackDelivered -> false
            // 73 MB over the user's own connection, from Play or from the commit-pinned base.
            StreamingPackState.PackFetchable -> true
            StreamingPackState.Downloadable -> true
            // Both answered above; spelled so this `when` is total over the machine rather than
            // wildcarding a future state into a silent transfer.
            StreamingPackState.Installed -> return Decision.NONE
            is StreamingPackState.Repair -> return Decision.NONE
        }
        if (wouldSpendTheUsersData && !unmetered) return Decision.OFFER
        // Still the user's to have — one tap, and the tap is consent the latch never was.
        if (attemptedThisLaunch || backedOff) return Decision.OFFER
        return Decision.FETCH
    }

    /**
     * WHAT THE CARD SHOWS, as a total mapping of the four things Home knows. Pure for the reason
     * every card rule in this app is pure (`CloudKeyNote.shouldShow` is the precedent): a
     * conjunction inside a composable is a rule no test can reach.
     *
     * [userSaidNo] is answered FIRST and absolutely. It is the whole of AF3 and AF4 — delete the
     * model and reopen, or dismiss the card and reopen, and the card is gone — and it has to
     * outrank [workInFlight] as well as [decision]: a user who dismissed this card and then
     * installed the model from the Settings row must not have it reappear as a progress card.
     *
     * [installed] then wins over any work in flight: a landed install is what the user has, and a
     * stale "arriving…" line over a working model is a lie they cannot dismiss (the fetch line's
     * own rule, `StreamingPackCopyTest`).
     *
     * @param installed `StreamingPackState.isInstalled`.
     * @param userSaidNo the same persisted flag [decide] reads.
     * @param workInFlight a fetch or install is running: `StreamingPackInstall.fetchInFlight` of
     *        the Play shell's state, or our own progress line being non-null.
     * @param decision [decide]'s answer for this same moment — passed in rather than recomputed,
     *        so the card and the hook can never disagree about what is about to happen.
     */
    fun card(
        installed: Boolean,
        userSaidNo: Boolean,
        workInFlight: Boolean,
        decision: Decision,
    ): Card = when {
        userSaidNo -> Card.NONE
        installed -> Card.INSTALLED
        workInFlight || decision == Decision.FETCH -> Card.WORKING
        decision == Decision.OFFER -> Card.OFFER
        else -> Card.NONE
    }
}
