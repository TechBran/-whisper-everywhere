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
 * ### THE OWNER'S RULING ON DATA (2026-09-11, settled) — THERE IS NO METERED TEST HERE
 *
 * Asked directly whether BOTH acquisition paths should simply download, the owner answered:
 *
 * > *"Yes. I wanted to silently download on cellular and Wi Fi."*
 *
 * Earlier in the same exchange: *"You switch a language, it downloads the model"*, and *"not make
 * the users tap anything"*. So **both starters — a language the user just picked
 * ([PreviewStarter.PICK]) and the unasked foreground top-up ([PreviewStarter.TOP_UP]) — fetch on
 * any connection, with no tap, no card in the way and no confirmation.**
 *
 * The quote and the date are IN THE CODE deliberately. Three consecutive review rounds rewrote
 * this one cell in three directions, each of them correct against the document it could see, and
 * a decision whose only authority is a scratch file that keeps moving gets reversed a fourth time.
 * What this replaces, by name so nobody restores it: a CONTROLLER ruling that a metered
 * connection should become *"a card with a tap — the user's consent, once, for their own data"*,
 * and 4.4.1's **AF2** acceptance row, which that ruling produced and which the owner validated on
 * his own device. AF2 describes behaviour this build deliberately removes; it is REWRITTEN on the
 * sheet rather than deleted, because a row that once passed and now describes the opposite is how
 * a regression gets mistaken for a fix.
 *
 * **What it costs, surfaced rather than hidden:** this feature can now spend up to 128 MB of a
 * user's mobile data unasked (French is the largest pack; English 73 MB, German 71 MB), including
 * for a language the user selected before the last app update and has not touched today. The owner
 * ruled that three times, the last time explicitly after the cellular cost was put to him. It is
 * his call about his users and it is not re-litigated here.
 *
 * **What makes it a deal rather than a surprise is the copy at the point of choice**, and with no
 * tap gate left those sentences are the ONLY disclosure the product has: `StreamingPackCopy
 * .PICKER_DEAL` above the in-app picker and `StreamingPackCopy.LANGUAGE_STEP_SENTENCE` on
 * onboarding's language step. They are required at BOTH selection sites for that reason — a
 * first-run user's first pick is an onboarding pick.
 *
 * One thing the app does not control: for an on-demand fetch this size Google Play may raise its
 * OWN confirmation or wifi-wait dialog ([PreviewPhase.AWAITING_ANSWER], `CARD_ANSWER_PLAY`).
 * *"Silently"* is as silent as Play allows; nothing of OURS asks for anything.
 *
 * ### What survives the ruling, and it is NOT a data gate: the network has to WORK
 *
 * [workingNetwork] is `NET_CAPABILITY_INTERNET && NET_CAPABILITY_VALIDATED`
 * (`ConnectivityMonitor.hasValidatedNetwork`). The predicate it replaces — `isUnmetered`, deleted
 * from the app with this ruling — asked *"may this app spend these bytes"*. This one asks *"is
 * there a network at all"*, which is a different question, and it is the reason the previewer
 * still references `ConnectivityMonitor`: a captive-portal wifi (a hotel, an airport, a coffee
 * shop) reports connected while every request fails, and an unasked fetch started there fails and
 * then parks the pack behind the 24 h [BACK_OFF_MS] stamp — withholding the model for a DAY after
 * the user reaches a network that would have worked. **Metered goes; validated stays.**
 *
 * ### What the STARTER still buys, now that it does not buy the bytes
 *
 * Two things, both on the unasked path and neither about money: the working-network wait above,
 * and the 24 h back-off. A [PreviewStarter.PICK] consults neither — a pick is a gesture the user
 * just made, not the across-launch loop the back-off exists for — and is bounded instead by
 * [attemptedThisLaunch], which binds on both starters. What tells the two apart is [PreviewPicks]:
 * a pick is an event this process watched happen, not a value a preference can report. (So the
 * register is NOT dead after this ruling. It also feeds `PreviewWork.starter`, which is Task 1's
 * observable answering *"who started this"*.)
 *
 * [StreamingPackState.PackDelivered] is exempt from even that: Google Play has already delivered
 * those bytes to the device and the install is a local verify + copy that touches no network at
 * all (`StreamingPackManager.installFromPack`), so a wait for a network cannot apply to a transfer
 * that does not happen. That is also the literal reading of the brief's
 * *"Installed/PackDelivered/in-flight never re-fetch"*: a delivered pack never FETCHES, it
 * installs.
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

        /**
         * The offer, with the action that starts it — and since Fix 1 **never because of what the
         * bytes cost**. Its KDoc said *"the metered (or backed-off) case"*; the metered half is
         * gone with the owner's ruling and what is left is the cases where the app has nothing
         * more to try silently and a tap is the way on: the third-party route
         * ([StreamingPackState.Downloadable], whose consent is about WHO serves the bytes), this
         * launch's one attempt already spent ([PreviewAutoFetchController.attemptedThisLaunch]),
         * the 24 h back-off after a failure, and the unasked path's wait for a network that works.
         */
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
     *        literal. **It no longer decides anything about the COST of the bytes** (Fix 1: there
     *        is no metered test in this feature). What is left is two rules, both on the unasked
     *        path: an unasked [PreviewStarter.TOP_UP] waits for a [workingNetwork] and is held to
     *        the offer by the 24 h back-off, while a [PreviewStarter.PICK] consults neither. The
     *        rule they SHARE is [attemptedThisLaunch]: both are re-decided by a recomposition, so
     *        both are latched, or a failed pick retries itself for the life of the process.
     * @param workingNetwork the platform says there is a network that WORKS —
     *        `NET_CAPABILITY_INTERNET && NET_CAPABILITY_VALIDATED`
     *        (`ConnectivityMonitor.hasValidatedNetwork`, false when there is no active network at
     *        all and false on a captive portal). **Not a metering read and not a consent
     *        question** — see the class KDoc: the metered half of what it replaces was deleted by
     *        the owner's ruling, and the validated half deliberately survives so a doomed fetch
     *        cannot park the pack behind the 24 h back-off. Read on the UNASKED path only, exactly
     *        where it was.
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
        workingNetwork: Boolean,
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
        val needsTheNetwork = when (state) {
            // Play has already put these bytes on the device: the install is a local verify +
            // copy and no connection is touched at all.
            StreamingPackState.PackDelivered -> false
            // 73 MB of the APP'S OWN asset pack, from Google Play, over the user's connection.
            StreamingPackState.PackFetchable -> true
            // 73 MB from a THIRD PARTY (the catalog's commit-pinned base), which this app never
            // moves unasked however good the connection: OFFER, and the card carries the
            // download's own sentence for the user to answer. See the class KDoc.
            //
            // (4.5.0 Task 3b) AND A PICK DOES NOT CHANGE IT, deliberately. The owner's ruling is
            // about what a transfer COSTS — "no one's gonna care about sixty more megabytes" —
            // and this route's consent is about WHO serves the bytes: the pick says nothing about
            // Hugging Face, and `installDownload` is the one sentence in the feature that admits
            // a third party. Unreachable on a Play install; see the report's concern.
            StreamingPackState.Downloadable -> return Decision.OFFER
            // Both answered above; spelled so this `when` is total over the machine rather than
            // wildcarding a future state into a silent transfer.
            StreamingPackState.Installed -> return Decision.NONE
            is StreamingPackState.Repair -> return Decision.NONE
        }
        // THE ONE CONNECTION TERM LEFT, AND IT IS NOT ABOUT MONEY (Fix 1). The owner, 2026-09-11,
        // asked directly whether both paths should simply download: *"Yes. I wanted to silently
        // download on cellular and Wi Fi."* **THERE IS NO METERED TEST IN THIS FEATURE.** The
        // line that stood here was `!unmetered && starter == TOP_UP -> OFFER`, i.e. 4.4.1's card
        // with a sized one-tap fetch on cellular (AF2, owner-validated, now rewritten), and the
        // CONTROLLER ruling behind it is overruled.
        //
        // What this tests instead is whether the network WORKS — VALIDATED, never NOT_METERED,
        // which `ConnectivityMonitor` no longer reads at all. A captive portal reports connected
        // while every request fails, and an unasked fetch started there fails and then parks the
        // pack behind the 24 h back-off: a day of silence bought by asking the wrong question. So
        // metered went and validated stayed, on the unasked path only, exactly where it was.
        if (needsTheNetwork && !workingNetwork && starter == PreviewStarter.TOP_UP) {
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
     *        install — `PreferencesManager.livePreviewArmedOnce`, whose own KDoc is the one home
     *        for when it is written (`onOpen`, not where the gate answers) and why arming alone
     *        is not enough. Silences the announcement and nothing else — it is not a "no", so it
     *        must never suppress the offer or the working card.
     * @param localTierInstalled the same input [decide] reads: an on-device whisper tier exists.
     *        Without one no word reaches the bubble, so the announcement would be permanently
     *        false; [PreviewUnreachable]'s KDoc is that fact's one home and states the mechanism,
     *        which is NOT this gate. Silences the announcement and nothing else, for the reason
     *        above.
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
