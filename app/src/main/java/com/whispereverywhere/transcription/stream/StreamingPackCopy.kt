package com.whispereverywhere.transcription.stream


/**
 * Every user-facing string of the previewer (spec §9, as amended on 2026-09-10) — pure,
 * Compose-free, pinned verbatim by `StreamingPackCopyTest` and scanned for the app's banned
 * speed words. "Live" is the app's own word for the surface (`CLOUD_LIVE`); no sentence promises
 * a latency. The size badge is the catalog's ([StreamingPackCatalog.sizeBadge] → "73 MB"), never
 * a retyped number.
 *
 * ### Why the install sentence is a TABLE and not one constant
 *
 * The spec wrote one `SETTINGS_INSTALL` ("Download a 73 MB English preview model…") because the
 * previewer was going to be a Hugging Face download on every build. The 2026-09-10 amendment
 * moved it onto Play Asset Delivery and ruled the copy with it: *"the previewer's install row
 * says 'included with the app' on Play builds (it is fetched, not downloaded from a third
 * party); the fallback wording only on non-Play builds."* That parenthetical is about
 * PROVENANCE: the bytes are the app's own, published in the same AAB, and no third party ever
 * serves them. Naming Play and the size says that, and a row promising a Hugging Face download
 * on a Play build would be false in the direction that matters (a data-cost claim).
 *
 * ### Why only ONE of the two Play rows says "included with the app" (fix round 1, B1)
 *
 * `preview_en` is `deliveryType.set("on-demand")` (`preview_en/build.gradle.kts:35`), so those
 * 73 MB ride the AAB we UPLOADED — not the install the user HAS. Until Play has delivered the
 * pack, a tap starts a real 73 MB transfer over the user's own connection, which is precisely
 * why this row must answer [PreviewPhase.AWAITING_ANSWER] at all (Play raises its
 * own metered/size dialog before a transfer that size). So "included with the app" belongs to
 * [StreamingPackState.PackDelivered], where the bytes really are on the device; the
 * [StreamingPackState.PackFetchable] row keeps the provenance clause but drops the cost claim
 * and names the size, Play, and the connection instead. The split the state machine already
 * draws is the split the copy draws.
 *
 * So the row's words are keyed by [StreamingPackState], the state machine that already knows
 * which source THIS install has ([StreamingPackInstall.resolve]) — no second discriminator, no
 * `BuildConfig.DEBUG` read up here, and no way for the row to name a source the action will not
 * use. The spec's sentence survives as the [StreamingPackState.Downloadable] row, which is the
 * only row where a third-party download is what actually happens.
 *
 * The voice's own table is `TtsModelManager.installRowTitle`/`installRowSubtitle`, amended the
 * same morning for the same reason. They are siblings rather than one table because the SENTENCE
 * differs — this one promises the typed transcript is untouched, that one says what a voice
 * does — while the ROUTING question they ask is the same one, answered once, in
 * [StreamingPackInstall.resolve].
 */
object StreamingPackCopy {

    private val BADGE = StreamingPackCatalog.sizeBadge(StreamingPackCatalog.EN.totalBytes)

    /**
     * The one promise that matters, spelled ONCE and carried by every install sentence: the
     * previewer is ADDITIVE (spec §10). Whatever route the bytes take, the typed transcript is
     * still whisper's, word for word.
     */
    private const val ADDITIVE =
        "Words appear on the bubble as you talk; the typed transcript is still the speech model's."

    // ---------------------------------------------------------------- the state-free strings

    /**
     * The row's name once the model is installed, and the feature's name everywhere else.
     *
     * PARAMETERISED by language since 4.4.1's acquisition amendment (owner rulings 2026-09-11):
     * packs are per language and the pack that arrives is the SELECTED language's, so every
     * sentence about a pack names the language it is about rather than a literal that was only
     * ever true while one row existed.
     */
    fun featureTitle(language: String): String = "Live words while you speak ($language)"

    const val SWITCH_TITLE = "Show live words"

    const val DELETE_TITLE = "Delete the preview model"

    /**
     * WHAT DELETING COSTS, for each of the FIVE facts the row can be looking at
     * ([PreviewDeleteCase]) — and the promise again, on all five.
     *
     * ### Why this is five sentences and not one (4.5.0 Task 1, fix round 1)
     *
     * 4.4.1's one `DELETE_SUBTITLE` — *"Frees 73 MB. Live words stop; the typed transcript is
     * unchanged."* — was rendered across all of them, and four made it false:
     *
     *  - the model is installed for a language the user is NOT transcribing (they picked another,
     *    or Auto) — live words are already off, so *"Live words stop"* stops nothing. The row is
     *    deliberately OUTSIDE the selection gate, because 73 MB installed for English must stay
     *    reclaimable after the user picks French, so this is not an edge case but the case the
     *    row's placement exists for;
     *  - **the *"Show live words"* switch is OFF** — the switch THIS SECTION DRAWS ONE ROW ABOVE
     *    the delete. Nothing stops, and until fix round 1 the two adjacent rows contradicted each
     *    other on the default surface, one tap away, with no device, tier or connection
     *    requirement (review r1's B2). The switch is an arming term everywhere else in the
     *    feature — `localPreviewArms` conjoins it, and `PreviewAutoFetch.card` refuses on it
     *    because *"every sentence this card can spell is false while the switch is off"* — and
     *    this subtitle was the one sentence that did not ask;
     *  - the install is a `StreamingPackState.Repair` — `markCorrupt` removed the marker and left
     *    the bytes, so again live words are already off;
     *  - **a write is in flight**, where *"Frees …"* frees nothing at all: `delete` clears
     *    the install dir under a verify + copy that is not cancellation-cooperative, so the copy
     *    finishes, the marker lands, and the user gets *"Installed"* from pressing *"Frees"* —
     *    with the declined flag written (review r3's H3-B1). The row renders this sentence with
     *    NO tap; it is a receipt, the way every other in-flight row in this feature is.
     *
     * @param language the PACK's language, as the picker spells it. Four of the five sentences
     *        name it, because four of them are about a model that is not the one in use; the day
     *        a second catalogue row lands, a sentence that named none would describe one pack
     *        under another's name.
     * @param sizeBytes the PACK's own byte count, rounded through [StreamingPackCatalog.sizeBadge]
     *        here rather than accepted as a string — English is 73 MB, German 71 MB and French
     *        128 MB, so a caller that could pass a literal would be wrong for two of the three.
     */
    fun deleteSubtitle(case: PreviewDeleteCase, language: String, sizeBytes: Long): String {
        val badge = StreamingPackCatalog.sizeBadge(sizeBytes)
        return when (case) {
            PreviewDeleteCase.LIVE ->
                "Frees $badge. Live words stop; the typed transcript is unchanged."
            // The switch's own TITLE, not a second spelling of it: the sentence quotes the
            // control the user has to find, and one rename must not leave it pointing at a row
            // that no longer says that. It deliberately does not say "above" — the switch row is
            // inside the selection gate, so with this pack installed for a language the user is
            // not transcribing there is no switch on this screen to point at.
            PreviewDeleteCase.OFF_SWITCH ->
                "Frees $badge. Live words are already off: '$SWITCH_TITLE' is switched off. " +
                    "Deleting the $language model stops nothing; the typed transcript is unchanged."
            PreviewDeleteCase.OFF_SELECTION ->
                "Frees $badge. Live words are already off: they appear only while $language is " +
                    "the language you pick. The typed transcript is unchanged."
            PreviewDeleteCase.DAMAGED ->
                "Frees $badge. The $language model is damaged and live words are already off; " +
                    "the typed transcript is unchanged."
            PreviewDeleteCase.WORKING ->
                "Nothing to free yet: the $language model is being written right now. Deleting " +
                    "becomes available when it finishes; the typed transcript is unchanged either way."
        }
    }

    fun installed(language: String): String =
        "Installed ($BADGE). Words appear on the bubble as you speak $language; the typed transcript is unchanged."

    /**
     * RULING ASSUMED (R1): the canary is the only SME guard; this is what the row says after it
     * fails.
     *
     * NOT RENDERED ANYWHERE, still — the 4.4.0 acceptance sheet records that, and 4.5.0 T2 did
     * not change it. The verdict lives on the previewer instance the service builds
     * (`StreamingPreviewEngine.isDisabled(pack)` / `disabledLanguages`, per-LANGUAGE since
     * defect 4), and nothing reads it. The sentence is pinned here so the words are decided in
     * the one place the feature's copy is reviewed rather than invented at a wiring site — but
     * note that it now says "on this device" about a per-language fact, so whoever renders it
     * owes it the language, exactly as every other sentence in this object takes one.
     */
    const val SETTINGS_DISABLED_ON_DEVICE =
        "Live words are off on this device: the preview model did not pass its start-up check. Your transcripts are unaffected."

    /**
     * What the language step says BEFORE it offers any language — the fair trade, at the one
     * moment it is actually being made.
     *
     * REWRITTEN by the acquisition amendment (owner ruling 1, 2026-09-11: *"we make it explicit
     * that users just have to select their language. Now if they leave it in auto, then you get no
     * live streaming at all. And that will seem to be a very fair trade-off."*). The old sentence
     * said live words were English-only and that other languages "show a progress line" — true of
     * the pack list, and silent about the thing the user is about to decide. This one names what
     * the pick buys, what Auto costs, and the promise neither choice touches.
     *
     * It names ONE language because one pack exists; when the language list lands this sentence is
     * where it goes, and the badge in the picker replaces the card as the permanent signpost.
     */
    const val LANGUAGE_STEP_SENTENCE =
        "Live words on the bubble follow the language you pick: English has a preview model today, and Auto-detect shows none at all. Your typed transcript is the same either way."

    /** Rendered in the English row's subtitle slot on the language step when the pack is installed. */
    const val LANGUAGE_CHIP = "Live words on the bubble while you speak — preview model installed."

    // ------------------------------------------- what a selection with no pack costs, and where

    /**
     * The Settings row and the in-app picker's own version of the trade, for the user who is
     * standing on Auto right now (owner ruling 1, 2026-09-11). It has to be SAID and not merely
     * be true: a user on Auto sees no card, no progress and no offer, and without this sentence
     * the feature is simply missing rather than declined.
     *
     * It names no language, deliberately: it is the sentence for having picked NONE, and the row
     * above it already names what a pick would get. The additive promise is repeated because this
     * is the one place a reader could otherwise conclude Auto degrades their transcript.
     */
    const val AUTO_ROW_TITLE = "Live words need a chosen language"

    const val AUTO_NO_LIVE_WORDS =
        "On Auto-detect there are none at all: pick your transcription language to see words on the bubble as you speak. Your typed transcript is unchanged either way."

    /**
     * THE CAVEAT FOR A SELECTION THE PREVIEWER HAS NO PACK FOR, as two total functions over ONE
     * input: [language] is the picked language's own word, or **null on Auto**, where no language
     * was picked at all.
     *
     * (4.4.1 pass 3, ITEM 1.) [AUTO_NO_LIVE_WORDS] answered only the user standing on Auto, and
     * the Settings rows gated it on `selectedLanguage == "auto"` — so a user who picked French was
     * offered *"Get the English preview model"*, spent 73 MB, and was then told *"Installed. Words
     * appear on the bubble as you speak English"*, which owner ruling 1 has already decided can
     * never happen for them on ANY tier: the gate arms for the language they PICKED. The app was
     * taking someone's storage for a feature it had already refused them. The honest predicate is
     * the catalogue's ([StreamingPackCatalog.forLanguage] answering null), and this is the sentence
     * that predicate needs.
     *
     * TWO sentences and not one, deliberately. Auto is a CHOICE, unmade in the picker directly
     * above these rows; a language with no catalogue row is a GAP in the app, and no pick can close
     * it today. They are different facts about the world, and one sentence covering both would
     * either tell a French user to pick the language they have just picked or tell an Auto user to
     * wait for something that is already here.
     *
     * Neither of them names English. Telling a French user which OTHER language has a model is one
     * short step from offering it to them, which is the thing this pass exists to stop; the
     * language step ([LANGUAGE_STEP_SENTENCE]) already says which language has one, at the one
     * moment that is a choice being made. And naming no language keeps these two true on the day a
     * second catalogue row lands.
     */
    fun noLiveWordsTitle(language: String?): String =
        if (language == null) AUTO_ROW_TITLE else "Live words are not available in $language yet"

    fun noLiveWordsSubtitle(language: String?): String =
        if (language == null) {
            AUTO_NO_LIVE_WORDS
        } else {
            "The bubble shows live words only for a language with a preview model, and there is " +
                "none for $language yet. Your typed transcript in $language is unchanged."
        }

    // ---------------------------------------------------------------- the offer, by source

    /** Play delivered the pack: verify + copy into `filesDir`, no network at any point. */
    val SETTINGS_INSTALL_FROM_PACK =
        "Included with the app ($BADGE) and already on this device — nothing to fetch. $ADDITIVE"

    /**
     * Play can serve this install: the ordinary on-demand fetch, and still not a third party.
     *
     * It says the SIZE and the CONNECTION as well as the source, because this is the row where a
     * tap costs the user 73 MB of their data. It does NOT borrow
     * [SETTINGS_INSTALL_FROM_PACK]'s "included with the app": the pack is `on-demand`, so on this
     * row the bytes are not on the device yet (fix round 1, B1 — see the class KDoc).
     */
    val SETTINGS_INSTALL_FETCH =
        "The app's own $BADGE model, fetched from Google Play over your connection when you ask " +
            "for it — never from a third party. $ADDITIVE"

    /**
     * The NON-PLAY row, and the spec's original sentence verbatim. Reached only where
     * [StreamingPackInstall.playCanDeliver] is false — a debug build, a sideload, or a refusal
     * Play has already named as this install's own fault — which is exactly where a download
     * from the commit-pinned Hugging Face base is what the tap does.
     */
    fun installDownload(language: String): String =
        "Download a $BADGE $language preview model. $ADDITIVE"

    // ---------------------------------------------------------------- the damaged install

    private const val DAMAGED = "The preview model is damaged."

    /** Repair on a non-Play install: the same fallback the first install would have used. */
    const val SETTINGS_REPAIR = "$DAMAGED Download it again to restore live words."

    /** Repair from the delivered pack — the bytes are already here, so nothing is fetched. */
    const val SETTINGS_REPAIR_FROM_PACK =
        "$DAMAGED Install it again from the copy included with the app to restore live words."

    /**
     * Repair by asking Play again. Still not a download from anyone else — and still a real
     * transfer of the whole pack, so it carries the size for the same reason
     * [SETTINGS_INSTALL_FETCH] does.
     */
    val SETTINGS_REPAIR_FETCH =
        "$DAMAGED Get it again from Google Play ($BADGE over your connection) to restore live words."

    // ---------------------------------------------------------------- the row

    /**
     * The row's title: the ACTION's own name, so the row names the source it will actually use.
     * Total over [StreamingPackState] — a state added to that machine must be answered here
     * rather than fall through a wildcard into an offer to download.
     *
     * A [StreamingPackState.Repair] reads the same whatever would repair it: the user is
     * repairing, not choosing, and the subtitle already says what the repair will cost.
     */
    fun settingsTitle(state: StreamingPackState, language: String): String = when (state) {
        StreamingPackState.Installed -> featureTitle(language)
        StreamingPackState.PackDelivered -> "Install the $language preview model"
        StreamingPackState.PackFetchable -> "Get the $language preview model"
        StreamingPackState.Downloadable -> "Download the $language preview model"
        is StreamingPackState.Repair -> "Repair the $language preview model"
    }

    /** The row's subtitle, by the same table. See the class KDoc for why it is a table. */
    fun settingsSubtitle(state: StreamingPackState, language: String): String = when (state) {
        StreamingPackState.Installed -> installed(language)
        StreamingPackState.PackDelivered -> SETTINGS_INSTALL_FROM_PACK
        StreamingPackState.PackFetchable -> SETTINGS_INSTALL_FETCH
        StreamingPackState.Downloadable -> installDownload(language)
        is StreamingPackState.Repair -> when (state.via) {
            StreamingPackState.PackDelivered -> SETTINGS_REPAIR_FROM_PACK
            StreamingPackState.PackFetchable -> SETTINGS_REPAIR_FETCH
            // A Repair's `via` is the source a FIRST install would have taken, so it is never
            // Installed and never another Repair (StreamingPackInstall.resolve builds it from
            // the three source states only). Downloadable is the remaining one, and the
            // fallback sentence is the safe answer for anything a later state adds.
            else -> SETTINGS_REPAIR
        }
    }

    // ---------------------------------------------------------------- Home's card (4.4.1)

    /**
     * The discovery card's own name for the feature. The Settings row's [featureTitle] is a ROW
     * NAME — it answers "what is this row" for someone already reading a settings list — and this
     * card exists precisely because that list was never opened (owner, 2026-09-11: *"That way the
     * users don't have to discover the setting at all"*). So the headline names the surface the
     * words appear on, and the body underneath is the route's own sentence from the table above.
     */
    const val CARD_TITLE = "Live words on the bubble"

    /**
     * THE FAIR TRADE, said on every card state (owner ruling 1, 2026-09-11) — one sentence source
     * used three ways, so no two cards can state the trade differently. The card only ever
     * appears while a language with a pack IS selected, so the useful half for its reader is the
     * flip side: these words follow that selection, and switching to Auto ends them.
     *
     * The CONTROLLER's instruction was *"Say what the selected language is, on every card state,
     * and say what Auto costs"*; this is the second half, and [featureTitle]'s parameterisation
     * plus the per-source table's is the first.
     */
    fun cardLanguageNote(language: String): String =
        "Live words follow your transcription language: $language shows them, and Auto-detect " +
            "shows none at all."

    /**
     * The card while the fetch or the install runs. It promises nothing about when, carries the
     * additive promise in the shortest true form, and asks for nothing — a working card that
     * mentioned Settings or a tap would undo the ruling it exists to serve. The live progress
     * line under it is [workLine]'s, never a second wording.
     *
     * It carries [cardLanguageNote] INLINE rather than in the note slot, because on this state
     * that slot holds the progress line — and this is the state review r1's nit 1 flagged for
     * naming the language only through the model's name.
     */
    fun cardWorking(language: String): String =
        "The $language preview model is arriving now; the typed transcript is unchanged. " +
            cardLanguageNote(language)

    /** The one-time announcement's headline, once the model has landed. */
    const val CARD_INSTALLED_TITLE = "Live words are on"

    /**
     * The announcement's body — the owner's own sentence (*"Live words are on — pick English to
     * see them"*) split across the headline and here.
     *
     * It CONFIRMS rather than instructs, as of the acquisition amendment: the pack only ever
     * arrives for a language the user has already selected, so "pick English" would be telling
     * them to do the thing they just did. The gate's own explanation moves to
     * [cardLanguageNote], rendered under this one.
     */
    fun cardInstalled(language: String): String =
        "You'll see them on the bubble as you speak $language; the typed transcript is unchanged."

    /** The X's content description — the cloud-key note's own label, for the same gesture. */
    const val CARD_DISMISS = "Dismiss"

    /**
     * The working card's one action, and the only gesture that card can ever need: Google Play is
     * holding its own dialog (a cellular or size confirmation, or a wait for wifi — both
     * `STATUS_REQUIRES_USER_CONFIRMATION` and `STATUS_WAITING_FOR_WIFI` arrive as
     * [PreviewPhase.AWAITING_ANSWER]), and [workLine] says so, ending in *"tap to
     * answer"*.
     *
     * Without this button that sentence named a gesture the card did not have (review r1, B3):
     * the dialog is raised once per ENTRY into that state, so a user who backed out of it was
     * parked on an instruction with only the X left — and the X is the permanent no. The Settings
     * row solved the same thing with [workLineTappable] plus a tap that re-shows PLAY'S OWN
     * dialog; this is that tap, with a label, because a card's action is a button. It names Play
     * because the dialog is Play's and the decision in it is Play's.
     */
    const val CARD_ANSWER_PLAY = "Answer Google Play"

    /**
     * The offer card's body: the SAME per-source table the Settings row reads, by delegation
     * rather than by a second set of sentences held to the same rule by a second test. A card
     * with its own wording is how "included with the app" ends up over an undelivered on-demand
     * pack (fix round 1's B1, on the row) one edit later; delegating makes that unexpressible,
     * and `StreamingPackCopyTest` holds the two equal for every state.
     */
    fun cardOffer(state: StreamingPackState, language: String): String =
        settingsSubtitle(state, language)

    /** The offer card's action label — the ACTION's own name, so it names the source it will use. */
    fun cardAction(state: StreamingPackState, language: String): String =
        settingsTitle(state, language)

    // ---------------------------------------------------------------- work in flight

    /**
     * Between the DECISION and the starter's first board write — the one frame in which Home's
     * card knows work is about to begin and [PreviewWorkboard] has no record of it yet
     * (`decision == FETCH` re-renders the card before the `LaunchedEffect` calls
     * `PreviewAutoFetchController.start`). Every other gap this used to cover is closed: the
     * starters write the board synchronously.
     */
    const val PROGRESS_STARTING = "Starting…"

    /**
     * The verify + land, whichever source the bytes came from: a hash of 72,654,782 B and a copy
     * into `filesDir`, with no meaningful progress to report on the pack route (its `onProgress`
     * is called twice, at 0 and at the end). Also what Play's own `Verifying` status reads as,
     * because it is the same work.
     */
    const val PROGRESS_INSTALLING = "Verifying and installing…"

    /**
     * The last-resort failure sentence: every refusal the manager raises carries its own words
     * ([StreamingPackException]), so this is only reached by a throwable that named nothing.
     */
    const val INSTALL_FAILED = "The preview model could not be installed."

    // ------------------------------------------- the ONE observable's own line (4.5.0 Task 1)

    /**
     * WHAT BOTH SURFACES SAY about work in flight — one function over the one observable
     * ([PreviewWork]), replacing the two that came before it: `fetchLine` for Play's own machine
     * and the Settings row's `previewInstallStatus` for ours. That split is the defect Task 1
     * exists to retire: Home collected one of them, Settings collected the other, and a transfer
     * one surface started was invisible on the other.
     *
     * Null for the two phases that are OVER — a stale *"fetching…"* under an installed model is a
     * lie the user cannot dismiss — and a sentence for every phase that is running. A
     * [PreviewPhase.FAILED] renders its reason VERBATIM: the fetch shell has already re-told every
     * Play refusal in this feature's words ([StreamingPackInstall.deliveryRefusal] /
     * [StreamingPackInstall.fetchRefusal]) and our own routes carry `StreamingPackException`'s own
     * sentence, so re-wording here would be a second copy of the copy — and the first one knows
     * Play's error code.
     *
     * @param tappable the caller's OWN answer to *"does this row have an onClick right now"*, not
     *        [workLineTappable]'s answer to *"does this phase deserve one"* — the two differ, and
     *        review r3 (H3-B3) is what the difference costs. `AWAITING_ANSWER` says *tap to
     *        answer* because a tap opens Play's dialog; but the Settings row withholds that tap
     *        once the selection has moved off this pack's language, and then the sentence
     *        instructs a gesture the app has decided to refuse, with no ripple and no feedback
     *        when it is performed. Off-selection the line must be a RECEIPT, and it must name the
     *        one thing that unlocks it, because nothing else on screen does. Defaulted true so the
     *        card and every other caller read as before.
     */
    fun workLine(work: PreviewWork, tappable: Boolean = true): String? = when (work.phase) {
        PreviewPhase.ASKING -> "Asking Google Play for the preview model…"
        PreviewPhase.AWAITING_ANSWER ->
            if (tappable) {
                "Google Play needs your confirmation before it fetches the preview model — tap to answer."
            } else {
                "Google Play needs your confirmation before it fetches the preview model. " +
                    "Pick that language again to answer."
            }
        PreviewPhase.DOWNLOADING -> bytesMoving(work)
        PreviewPhase.TRANSFERRING -> "Google Play is moving the preview model into place…"
        PreviewPhase.INSTALLING -> PROGRESS_INSTALLING
        // The user's own no, waiting on the store's answer (4.5.0 Task 1, fix round 2). It is a
        // sentence rather than a null for the reason the phase exists: with nothing on the line,
        // Settings fell into its OFFER row and drew a 73 MB tap that the actuator then refused in
        // silence. No provenance verb — no bytes are moving in either direction.
        PreviewPhase.ABANDONED -> "Cancelling the preview model…"
        PreviewPhase.INSTALLED, PreviewPhase.CANCELLED -> null
        PreviewPhase.FAILED -> work.reason ?: INSTALL_FAILED
    }

    /**
     * The bytes in flight, in the words of the ROUTE that is carrying them.
     *
     * The verb is the amendment's provenance distinction, kept alive on the progress line: a Play
     * fetch that said *"Downloading"* would contradict [SETTINGS_INSTALL_FETCH]'s *"never from a
     * third party"* while those very bytes were moving, and [PreviewRoute.DIRECT_DOWNLOAD] is the
     * one route where a third party really is serving them ([installDownload] is its offer).
     *
     * BOTH halves round through [StreamingPackCatalog.megabytes], so the line cannot end at
     * *"72 of 72 MB"* under a row that has just promised 73 — which is exactly what Play's own
     * line did until 4.5.0, having divided by 1,000,000 and truncated. An unknown total invents no
     * denominator.
     */
    private fun bytesMoving(work: PreviewWork): String {
        val verb = when (work.route) {
            // Play, never a third party — SETTINGS_INSTALL_FETCH's own promise.
            PreviewRoute.PLAY_FETCH -> "Fetching the preview model"
            // Unreachable: a delivered pack moves no bytes over any connection, and its only
            // phase is the local copy. Answered anyway, so a route cannot fall through into the
            // wrong provenance word.
            PreviewRoute.DELIVERED_PACK -> "Fetching the preview model"
            // The ONE route that admits a third party, and says so.
            PreviewRoute.DIRECT_DOWNLOAD -> "Downloading the preview model"
        }
        return if (work.total > 0L) {
            "$verb: ${StreamingPackCatalog.megabytes(work.soFar)} of " +
                StreamingPackCatalog.sizeBadge(work.total)
        } else {
            "$verb…"
        }
    }

    // ------------------------------------- above the LANGUAGE SELECTOR (4.5.0 Task 3c)

    /**
     * WHAT THE STRIP ABOVE THE LANGUAGE SELECTOR SAYS about one language's arrival, or null where
     * it says nothing — owner ruling 3c, 2026-09-11:
     *
     * > *"And you can incorporate the status for that model being downloaded right there above the
     * > language selector. That way users can see the progress right away and know that their
     * > language is ready for selection."*
     *
     * It is the honest half of ruling 3b: a selection spends the user's data at once, so the place
     * they picked is the place that has to show them it happening. This retires AF5's
     * Home-not-onboarding compromise — the progress no longer hides on Home's card.
     *
     * ### Why this is a DELEGATION and not a second table
     *
     * [workLine] already has a sentence for every running phase, and the language is named by the
     * row's own title ([featureTitle]) exactly as the Settings in-flight row names it. A parallel
     * table of *"the English preview model is…"* sentences would be a second wording of Play's
     * phases held to the same rules by a second test, which is how *"included with the app"* came
     * to sit over an undelivered pack (fix round 1's B1, on the row). So this function adds exactly
     * the one sentence [workLine] has no phase for — the READY receipt — and answers the rest by
     * asking it.
     *
     * `tappable = false` because this strip has no tap. That is not a compromise: the sentence it
     * selects for [PreviewPhase.AWAITING_ANSWER] is the receipt form, *"Pick that language again
     * to answer"*, and the control it names is the selector immediately below. It is the one place
     * in the app where that sentence points at something the reader can see.
     *
     * @param work the board's record for ONE language. The strip renders a row per record, so two
     *        arrivals are two rows rather than one overwriting the other.
     * @param language that record's own language as the picker spells it — never the SELECTED
     *        one. A transfer keeps its surface when the selection moves off it (the Settings row's
     *        H-B3), so the row has to name the pack it is about.
     */
    fun selectorLine(work: PreviewWork, language: String): String? = when (work.phase) {
        // The one sentence the work line has no phase for, and the one the ruling asks for by
        // name. The board keeps a terminal record, so this is the receipt for an arrival THIS
        // PROCESS made — not a badge on every installed pack, which is the Settings row's job and
        // the language step's chip's.
        PreviewPhase.INSTALLED -> selectorReady(language)
        // The user's own no. The strip is about arrivals; a withdrawn one is not one, and the
        // dismissal was itself the receipt.
        PreviewPhase.CANCELLED -> null
        PreviewPhase.ASKING,
        PreviewPhase.AWAITING_ANSWER,
        PreviewPhase.DOWNLOADING,
        PreviewPhase.TRANSFERRING,
        PreviewPhase.INSTALLING,
        PreviewPhase.ABANDONED,
        // A FAILED stays on the strip, and it has to: under ruling 3b the user CAUSED this
        // transfer by picking, and on a metered connection there is no card anywhere else in the
        // app that would tell them it did not arrive (3a deleted it).
        PreviewPhase.FAILED,
        -> workLine(work, tappable = false)
    }

    /**
     * *"...and know that their language is ready for selection"* — the ruling's own words for the
     * end of the strip's job.
     *
     * It CONFIRMS rather than instructs, for [cardInstalled]'s reason: the pack only ever arrives
     * for a language the user has already picked, so *"pick English"* would tell them to do the
     * thing they just did. *"whenever you pick it"* is true both for the user who is transcribing
     * in this language right now and for one who has since moved to another — which is reachable,
     * because the record outlives the selection.
     */
    fun selectorReady(language: String): String =
        "$language is ready: words appear on the bubble whenever you pick it, and the typed " +
            "transcript is unchanged."

    /**
     * Whether a TAP on the row showing [workLine] does anything — `TtsModelManager`'s B1 lesson,
     * inherited rather than re-learned: the row renders a line for every phase, `SettingsItem`
     * makes itself clickable the moment it is handed an `onClick`, and a tap during the copy+hash
     * would otherwise re-enter the row's one action and start a SECOND install into the same temp
     * dir.
     *
     * The retry the branch exists for is the TERMINAL one; the one in-flight phase that stays
     * tappable is [PreviewPhase.AWAITING_ANSWER], where the tap re-shows PLAY'S OWN dialog and
     * starts no install of ours. Null — no work at all — is not tappable: the row that is
     * narrating nothing is the row that is OFFERING the install, and that is a different onClick.
     */
    fun workLineTappable(work: PreviewWork?): Boolean = when (work?.phase) {
        PreviewPhase.FAILED, PreviewPhase.AWAITING_ANSWER -> true
        PreviewPhase.ASKING,
        PreviewPhase.DOWNLOADING,
        PreviewPhase.TRANSFERRING,
        PreviewPhase.INSTALLING,
        -> false
        // A cancel already accepted: the row is a receipt for the user's own no, and a tap here
        // would re-enter the install they have just refused.
        PreviewPhase.ABANDONED -> false
        PreviewPhase.INSTALLED, PreviewPhase.CANCELLED -> false
        null -> false
    }
}
