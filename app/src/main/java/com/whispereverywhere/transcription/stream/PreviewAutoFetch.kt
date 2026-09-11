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
 * **RULING 3a, 2026-09-11, KEEPS THIS EXACTLY AS 4.4.1 SHIPPED IT — do not delete it.** The
 * owner validated AF1 *and* AF2 on device that day, and then ruled only on the SELECTION path
 * (the section below). An earlier reading of that ruling deleted the OFFER-because-metered
 * branch, which turned a metered unasked top-up into a card-less, tap-less silence; the owner
 * withdrew that instruction (4.5.0 Task 3 review r1, B1). He has never ruled on the unasked
 * top-up's metered behaviour, so the metered case stays an OFFER: *"a cellular user who already
 * had that language selected still gets the 4.4.1 card and has to tap it."*
 *
 * ### AND ITS OTHER HALF: A PICK IS A CONSENT (owner, 2026-09-11 — Task 3b)
 *
 * > *"And if you select a different language, then automatically download and set up the language
 * > pack for that language automatically. … No one's gonna care about sixty more megabytes."*
 *
 * **AN UNASKED BACKGROUND TRANSFER WAITS FOR WIFI; A TRANSFER THE USER JUST CAUSED BY PICKING A
 * LANGUAGE HAPPENS AT ONCE, BECAUSE THE PICK IS THE CONSENT.** That sentence is the whole of the
 * asymmetry, it is deliberate, and [decide]'s [PreviewStarter] parameter is where it lives —
 * written down here and there because someone reading either half alone will otherwise "fix" it
 * into symmetry, in one of the two directions that each undo a ruling.
 *
 * The consequence, surfaced rather than hidden: **a cellular user who PICKS a language gets an
 * immediate download with no tap, while a cellular user who already had that language selected
 * still gets 4.4.1's offer card and has to tap it.** Both are the ruling — the brief states this
 * cost in those words and forbids the copy from pretending otherwise. What tells the two apart is
 * [PreviewPicks] — a pick is an event this process watched happen, not a value a preference can
 * report.
 *
 * The exception is [StreamingPackState.PackDelivered]: Google Play has already delivered those
 * bytes to the device and the install is a local verify + copy that touches no network at all
 * (`StreamingPackManager.installFromPack`). Metering cannot apply to a transfer that does not
 * happen, so that one route auto-installs on any connection — which is also the literal reading
 * of the brief's *"Installed/PackDelivered/in-flight never re-fetch"*: a delivered pack never
 * FETCHES, it installs.
 *
 * ### Why [StreamingPackState.Downloadable] is never silent, on any connection (review r1, B1)
 *
 * That route is the non-Play fallback, and its bytes come from the catalog's commit-pinned base
 * at Hugging Face (`StreamingPackCatalog.EN.baseUrl`) — a THIRD PARTY. It is reached on every
 * install Play cannot serve: a debug build, a sideload of the public repo, or a release Play has
 * already refused by name. The ruling authorized fetching *"that pack from the assets"* — the
 * app's own asset pack — and the app's own copy draws exactly this line:
 * `StreamingPackCopy.settingsInstallFetch` promises *"never from a third party"*, and
 * `StreamingPackCopy.installDownload` is the one sentence that admits one. A silent fetch is the case
 * where the user never reads that sentence, because no card precedes the transfer. So this route
 * answers OFFER whatever the network reads: the card shows the download's own sentence, and one
 * tap is the consent. Nothing else about it changes — the tap takes the same route the Settings
 * row takes, and AF1's subject (the Play routes) is untouched.
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
 * `localPreviewArms` (FloatingBubbleService.kt) keeps its own shape — the owner tests on Auto
 * deliberately and found the gate correct once explained; 4.4.1's acquisition amendment only
 * generalises its English literal into a catalogue lookup. The INPUTS this decision shares with
 * that gate are read from the same places and never re-derived here: the switch
 * (`PreferencesManager.localPreviewEnabled`), a running batch job (`BatchJobController.active`),
 * and the pack's own installed state (through [StreamingPackState]). One it deliberately does not
 * borrow: `previewReady` (the recognizer cannot be warm before the model exists).
 * [localTierInstalled] is NOT a reading of that gate's `isCloudSession` — the gate has no tier
 * term at all, and the two are different facts (4.5.0 T4 fix round 1; the mechanism is
 * `PreviewUnreachable`'s KDoc). It is the standing fact that nothing transcribes on this device at
 * all: the session dies at connect with *"No speech model installed"*, no word can reach the
 * bubble, and 73 MB would buy the user nothing.
 *
 * ### The LANGUAGE is an input, as of the acquisition amendment (owner rulings 2026-09-11)
 *
 * *"for each language and we make it explicit that users just have to select their language. Now
 * if they leave it in auto, then you get no live streaming at all."* So the pack that may arrive
 * is the SELECTED language's and no other ([selectedLanguage] × [packLanguage]) — which makes
 * Auto fetch nothing by construction, and makes a Chinese-only user's phone safe from 73 MB of an
 * English model they can never use. The gate's language term and this decision's are deliberately
 * NOT the same reading: the gate asks about a session's RESOLVED language, this asks what the
 * user picked, and only the second one can be a reason to spend their data.
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
     * @param selectedLanguage `PreferencesManager.selectedLanguage` — the RAW picker code, with
     *        `"auto"` as itself rather than mapped to null. No pack's language is `"auto"`, so
     *        Auto refuses here by construction, which is the owner's *"if they leave it in auto,
     *        then you get no live streaming at all"* (2026-09-11): no selected language, no pack
     *        to choose, nothing to fetch.
     * @param packLanguage the language of the pack [state] was read for
     *        ([StreamingPackCatalog.forLanguage]'s answer for [selectedLanguage], null when the
     *        catalogue has no row). The two are compared rather than assumed equal because the
     *        question is not "is a pack missing" but *"does the selected language have a pack, and
     *        is THAT pack the one missing?"* — and the case that answers is a user who only ever
     *        dictates in Chinese being pushed 73 MB of English they can never use. The one call
     *        site derives one from the other, so a mismatch is unreachable there; a gate that
     *        trusts its caller to have matched them is not a gate.
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
     *        nothing transcribes on this device at all and no word can reach the bubble.
     * @param starter WHO caused this look — [PreviewTrigger.starter] at the one call site, never a
     *        literal. **The asymmetry of rulings 3a and 3b hangs on this one input, and on nothing
     *        else**: an unasked [PreviewStarter.TOP_UP] waits for an unmetered network — offering
     *        4.4.1's card with a tap while it waits — and is held to that offer by the 24 h
     *        back-off, while a [PreviewStarter.PICK] spends the connection at once and ignores
     *        that back-off, *because the pick IS the consent*. The one rule they
     *        SHARE is [attemptedThisLaunch]: both are re-decided by a recomposition, so both are
     *        latched, or a failed pick retries itself for the life of the process.
     * @param unmetered the platform's own NOT_METERED *and* VALIDATED reading
     *        (`ConnectivityMonitor.isUnmetered`, false when there is no active network at all and
     *        false on a captive portal — CONTROLLER RULING 2026-09-11, CHANGE 1). The CONTROLLER
     *        RULING's one predicate, and it is read on the UNASKED path only: a [starter] of
     *        [PreviewStarter.PICK] never consults it (ruling 3b).
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
     *        It binds on BOTH starters (see [starter]); a tap is exempt because it never reaches
     *        this decision at all.
     * @param backedOff [backedOff] of the persisted failure stamp. It silences the UNASKED path
     *        only: its own KDoc scopes it to *"a failed auto-fetch"* and to the across-launch loop
     *        of *"a user reopening the app all afternoon"*, and a pick in a later launch is a new
     *        consent rather than that loop. Within one launch [attemptedThisLaunch] is what bounds
     *        a pick.
     */
    fun decide(
        selectedLanguage: String,
        packLanguage: String?,
        state: StreamingPackState,
        userSaidNo: Boolean,
        showLiveWords: Boolean,
        localTierInstalled: Boolean,
        starter: PreviewStarter,
        unmetered: Boolean,
        sessionActive: Boolean,
        batchJobActive: Boolean,
        packWorkInFlight: Boolean,
        attemptedThisLaunch: Boolean,
        backedOff: Boolean,
    ): Decision {
        // THE PACK MUST BE THE SELECTED LANGUAGE'S. Answered first because every input below it
        // is about a pack this user has no use for otherwise. `"auto"` refuses here by
        // construction — no pack's language is "auto" — which is the fair trade the owner named.
        if (packLanguage == null || packLanguage != selectedLanguage) return Decision.NONE
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
            // 73 MB of the APP'S OWN asset pack, from Google Play, over the user's connection.
            StreamingPackState.PackFetchable -> true
            // 73 MB from a THIRD PARTY (the catalog's commit-pinned base), which this app never
            // moves unasked however cheap the connection: OFFER, and the card carries the
            // download's own sentence for the user to answer. See the class KDoc.
            //
            // (4.5.0 Task 3b) AND A PICK DOES NOT CHANGE IT, deliberately. Ruling 3b is about
            // what a transfer COSTS — "no one's gonna care about sixty more megabytes" — and this
            // route's consent is about WHO serves the bytes: the pick says nothing about Hugging
            // Face, and `installDownload` is the one sentence in the feature that admits a third
            // party. Unreachable on a Play install; see the report's concern.
            StreamingPackState.Downloadable -> return Decision.OFFER
            // Both answered above; spelled so this `when` is total over the machine rather than
            // wildcarding a future state into a silent transfer.
            StreamingPackState.Installed -> return Decision.NONE
            is StreamingPackState.Repair -> return Decision.NONE
        }
        // (4.5.0 Task 3a + 3b) THE ASYMMETRY, AND IT IS DELIBERATE: AN UNASKED BACKGROUND
        // TRANSFER WAITS FOR WIFI; A TRANSFER THE USER JUST CAUSED BY PICKING A LANGUAGE HAPPENS
        // AT ONCE, BECAUSE THE PICK IS THE CONSENT.
        //
        // 3a KEEPS 4.4.1's answer for the unasked half, unchanged and by name: the card with a
        // sized one-tap fetch, which the owner validated on device as AF2. What 3b adds is the
        // starter term — *"if you select a different language, then automatically download and
        // set up the language pack for that language automatically"* — so a PICK falls through to
        // FETCH here instead of being offered a card it has already answered.
        if (wouldSpendTheUsersData && !unmetered && starter == PreviewStarter.TOP_UP) {
            return Decision.OFFER
        }
        // THE LOOP GUARD BINDS ON BOTH STARTERS. A pick is decided in composition and the effect
        // that performs it is keyed on this answer, so a latch-exempt pick whose transfer FAILED
        // would go straight back to FETCH the instant `busy()` cleared — once per failure, for
        // the life of the process. Still the user's to have: one tap, and the tap is consent the
        // latch never was.
        if (attemptedThisLaunch) return Decision.OFFER
        // ...and the 24 h back-off silences the UNASKED path ONLY. It exists for the
        // across-launch loop its own KDoc names — a user reopening the app all afternoon on a bad
        // connection — and a pick made in a later launch is a new consent, not that loop.
        if (backedOff && starter == PreviewStarter.TOP_UP) return Decision.OFFER
        return Decision.FETCH
    }

    /**
     * WHAT THE CARD SHOWS, as a total mapping of the eight things Home knows. Pure for the reason
     * every card rule in this app is pure (`CloudKeyNote.shouldShow` is the precedent): a
     * conjunction inside a composable is a rule no test can reach.
     *
     * [hasPackForSelection] is answered FIRST and absolutely. A card is about ONE language's
     * pack, and with no pack for the selected language — Auto, or a language the catalogue has no
     * row for — there is no true sentence it can spell. The case that makes this load-bearing
     * rather than tidy: [workInFlight] spans BOTH starters on purpose, so a user on Auto who taps
     * the Settings row and returns to Home has a transfer running with no pack of their own,
     * which without this input rendered *"The Auto-detect preview model is arriving now"*.
     *
     * [userSaidNo] is answered next and just as absolutely. It is the whole of AF3 and AF4 — delete the
     * model and reopen, or dismiss the card and reopen, and the card is gone — and it has to
     * outrank [workInFlight] as well as [decision]: a user who dismissed this card and then
     * installed the model from the Settings row must not have it reappear as a progress card.
     *
     * [showLiveWords] is answered third and just as absolutely (review r1, B2). It is NOT the
     * same gesture as the X or the delete — a user who turned the switch off has not declined the
     * card, and [decide] deliberately leaves [userSaidNo] unwritten for it — but every sentence
     * this card can spell is false while the switch is off: "Live words are on" most of all, on
     * an install that landed from the Settings row (a 4.4.0 user upgrading) or from the
     * auto-fetch before the switch was turned off. A card that cannot say anything true says
     * nothing, and the Settings switch is where that decision was made and can be unmade.
     *
     * [installed] then wins over any work in flight: a landed install is what the user has, and a
     * stale "arriving…" line over a working model is a lie they cannot dismiss (the fetch line's
     * own rule, `StreamingPackCopyTest`).
     *
     * ### Why the announcement retires itself (CONTROLLER RULING 2026-09-11, CHANGE 4)
     *
     * The brief asked for a ONE-TIME announcement — *"then it stops appearing"* — and until this
     * ruling that was true only via the X, which is also the permanent no. So a 4.4.0 user who
     * already had the pack had to choose between being told about live words on every single open
     * and declining the feature forever. [previewHasArmed] is the third answer: once the previewer
     * has armed for a real session the user has seen live words with their own eyes, and
     * announcing them is noise. The X keeps working and keeps meaning no.
     *
     * It is a SEPARATE flag from [userSaidNo] on purpose, and the two say different things: "I
     * have seen this" is not "I do not want this", it is not per language, and it is written by
     * the arm path rather than by a gesture.
     *
     * ### Why the ANNOUNCEMENT asks about the local tier (4.4.1 pass 3, ITEM 3)
     *
     * `Card.INSTALLED` says *"Live words are on"* — and for a user with the pack but NO on-device
     * whisper tier that is simply false, permanently: nothing transcribes on their device at all,
     * so no word can reach the bubble (`PreviewUnreachable`'s KDoc for the mechanism, which is not
     * this gate's `!isCloudSession`). [decide]
     * already reads [localTierInstalled] and would never have FETCHED the pack for them, but the
     * pack can be there anyway — the Settings row installs on demand, and a 4.4.0 user may have
     * had it before they went cloud-only. [previewHasArmed] cannot retire the announcement for
     * them either, because the thing that writes it can never happen — the write is `onOpen`'s as
     * of 4.5.0 T4 fix round 1, and a modelless session never opens. (It used to be the GATE's
     * answer, which on such a phone fires, so the flag was written and this announcement was
     * suppressed for the one reader it exists for: review r1's B1.) So the announcement asks the
     * same question the acquisition side asks, and says nothing rather than something false
     * (review r1's nit 2).
     *
     * It is answered on the announcement's cell and NOT at the top of the `when`, deliberately:
     * [workInFlight] spans the Settings row's own fetch, so a cloud-only user who taps *"Get the
     * English preview model"* and returns to Home is watching a transfer THEY started, and
     * hiding its progress card would be hiding their own action from them.
     *
     * @param hasPackForSelection the selected language has a catalogue row
     *        (`StreamingPackCatalog.forLanguage(selected) != null`) — the same fact [decide] reads
     *        as [packLanguage] being non-null and equal to [selectedLanguage].
     * @param installed `StreamingPackState.isInstalled`.
     * @param previewHasArmed the previewer has armed for at least one real session on this
     *        install (`PreferencesManager.livePreviewArmedOnce`, written from the gate's own call
     *        site). Silences the announcement and nothing else — it is not a "no", so it must
     *        never suppress the offer or the working card.
     * @param localTierInstalled the same input [decide] reads: an on-device whisper tier exists.
     *        Without one the previewer can never arm, so the announcement would be permanently
     *        false. Silences the announcement and nothing else, for the reason above.
     * @param userSaidNo the same persisted flag [decide] reads.
     * @param showLiveWords the same switch [decide] reads (`PreferencesManager.localPreviewEnabled`).
     * @param workInFlight a fetch or install of THIS pack is running — one read of the one
     *        observable (`PreviewWorkboard.of(language)?.inFlight`, 4.5.0 Task 1). It was a
     *        disjunction of `StreamingPackInstall.fetchInFlight` over the Play shell's state and
     *        "our own progress line is non-null", which is the pair Task 1 retires.
     * @param decision [decide]'s answer for this same moment — passed in rather than recomputed,
     *        so the card and the hook can never disagree about what is about to happen.
     */
    fun card(
        hasPackForSelection: Boolean,
        installed: Boolean,
        previewHasArmed: Boolean,
        localTierInstalled: Boolean,
        userSaidNo: Boolean,
        showLiveWords: Boolean,
        workInFlight: Boolean,
        decision: Decision,
    ): Card = when {
        !hasPackForSelection -> Card.NONE
        userSaidNo -> Card.NONE
        !showLiveWords -> Card.NONE
        // The announcement, and its two silences: the user has already seen live words, or they
        // never can — with no on-device speech model nothing transcribes on this device at all, so
        // "Live words are on" would be permanently false (ITEM 3; the mechanism is
        // `PreviewUnreachable`'s KDoc, and it is NOT this gate, which has no tier term).
        installed -> if (previewHasArmed || !localTierInstalled) Card.NONE else Card.INSTALLED
        workInFlight || decision == Decision.FETCH -> Card.WORKING
        decision == Decision.OFFER -> Card.OFFER
        else -> Card.NONE
    }
}
