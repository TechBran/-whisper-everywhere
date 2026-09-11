package com.whispereverywhere.transcription.stream


/**
 * Every user-facing string of the previewer (spec §9, as amended on 2026-09-10) — pure,
 * Compose-free, pinned verbatim by `StreamingPackCopyTest` and scanned for the app's banned
 * speed words. "Live" is the app's own word for the surface (`CLOUD_LIVE`); no sentence promises
 * a latency.
 *
 * ### THE SIZE IS THE PACK'S OWN, and every sentence that names one takes its bytes
 *
 * The badge is the catalog's ([StreamingPackCatalog.sizeBadge] → "73 MB"), never a retyped number
 * — and, since 4.5.0 Task 3d, never a SHARED one either. Until this task the sizes came from one
 * class-init `val` over `StreamingPackCatalog.EN.totalBytes`, carried by six sentences: correct
 * while one row existed, and wrong for most rows the moment a second landed, because **a row is
 * not the same size as another row** (English 73 MB, German 71 MB, **French 128 MB** — the
 * qualification table, §4). The brief's instruction is *"use the pack's OWN size via
 * `StreamingPackCatalog.sizeBadge`, never a fixed number"*, and the strongest form of that is a
 * signature: a sentence about a pack takes that pack's `totalBytes`, so there is no shared figure
 * left for a second row to be wrong about. Every rendered string is byte-identical for `preview_en`
 * — this moved no validated copy, it removed the one place a future row could be mis-described.
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
     * WHAT DELETING COSTS, for each of the SIX facts the row can be looking at
     * ([PreviewDeleteCase]) — and the promise again, on all six.
     *
     * ### Why this is six sentences and not one (4.5.0 Task 1, fix round 1; Task 4)
     *
     * 4.4.1's one `DELETE_SUBTITLE` — *"Frees 73 MB. Live words stop; the typed transcript is
     * unchanged."* — was rendered across all of them, and five made it false:
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
     *  - **there is no on-device speech model** (4.5.0 Task 4) — nothing transcribes on this
     *    device at all (the session dies at connect with *"No speech model installed"*; the
     *    mechanism is `PreviewUnreachable`'s KDoc and it is NOT a term of `localPreviewArms`),
     *    and no pick, switch or repair on this screen can change it. Reachable in one gesture from this very screen: the *"Delete
     *    <tier>"* dialog three sections up clears `selectedModelId` and says *"On-device
     *    transcription will stop working until you download a model again"*;
     *  - **a write is in flight**, where *"Frees …"* frees nothing at all: `delete` clears
     *    the install dir under a verify + copy that is not cancellation-cooperative, so the copy
     *    finishes, the marker lands, and the user gets *"Installed"* from pressing *"Frees"* —
     *    with the declined flag written (review r3's H3-B1). The row renders this sentence with
     *    NO tap; it is a receipt, the way every other in-flight row in this feature is.
     *
     * @param language the PACK's language, as the picker spells it. Five of the six sentences
     *        name it, because five of them are about a model that is not the one in use; the day
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
            // (4.5.0 Task 4) The DEVICE axis, in the shared clause the section's own caveat row
            // and Home's working card use, so one fact has one spelling. It deliberately does not
            // borrow the other two's *"Live words are already off:"* opening: this sentence states
            // the RULE, and "already off" would imply a state that could turn back on from this
            // screen. It cannot — the remedy is a speech-model download, three sections up.
            PreviewDeleteCase.OFF_TIER ->
                "Frees $badge. $NO_TIER Deleting the $language model stops nothing; the typed " +
                    "transcript is unchanged."
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

    /**
     * @param sizeBytes the PACK's own byte count. See the class KDoc's SIZE section for why every
     *        sentence that carries a size takes one.
     */
    fun installed(language: String, sizeBytes: Long): String =
        "Installed (${StreamingPackCatalog.sizeBadge(sizeBytes)}). Words appear on the bubble as you speak $language; the typed transcript is unchanged."

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

    // ------------------------------------- what the PICKER's own copy says (4.5.0 Task 3d)

    /**
     * THE DEAL, STATED WHERE THE SWITCH IS MADE — owner ruling 3d, 2026-09-11:
     *
     * > *"And we could just say that in the copy for the drop down for the multi languages, we
     * > could just say that when you switch language, a new light model will be downloaded, and it
     * > will be used as your preview model."*
     *
     * Under ruling 3b a pick spends the user's data at once, on any connection. This is the
     * sentence that makes that a deal rather than a surprise, and it sits at the control that
     * makes it.
     *
     * ### Why it carries NO NUMBER
     *
     * The owner said *"sixty megabytes"* twice, and the real sizes are English 73 MB, German 71 MB
     * and **French 128 MB** — so any figure in a sentence about *"whichever language you switch
     * to"* would be wrong for most of them. The brief's instruction is to *"use the pack's OWN
     * size via [StreamingPackCatalog.sizeBadge], never a fixed number"*, and a size that is per
     * language belongs on the per-language ROW: [pickerRowBadge] is that, derived from the pack's
     * own `totalBytes`, and this sentence points at it. A figure here would be the one thing the
     * ruling names as forbidden, dressed as helpfulness.
     *
     * It is deliberately NOT added to the onboarding language step, whose
     * [LANGUAGE_STEP_SENTENCE] owns that moment's copy: the ENGINES step comes after the language
     * step, so no pick made there downloads anything until setup finishes, and "is downloaded"
     * would be the wrong tense in the one place it would be read first. Flagged for a controller
     * rather than decided here.
     */
    const val PICKER_DEAL =
        "Switch to a language with a preview model and that model is downloaded and becomes your " +
            "preview model — words appear on the bubble as you speak. The menu names the size of " +
            "each language that has one; your typed transcript is the same either way."

    /**
     * WHAT THIS ONE LANGUAGE'S PREVIEW MODEL COSTS, beside its row in the picker — the per-language
     * half of ruling 3d, and the only place a number belongs.
     *
     * @param sizeBytes the PACK's own byte count, rounded here through
     *        [StreamingPackCatalog.sizeBadge] rather than accepted as a string, so a caller cannot
     *        pass a literal. English is 73 MB, German 71 MB and French 128 MB: one retyped figure
     *        would be wrong for two of the three.
     *
     * A language with NO pack gets no badge at all, and its own sentence instead — 4.4.1's AF8
     * pair ([noLiveWordsTitle] / [noLiveWordsSubtitle]), which the brief says *"stands and must not
     * be collapsed into Auto's"*: a missing model and a deliberate Auto are different facts about
     * the world. A badge reading "no model" on fifty rows would be the collapse by another route.
     */
    fun pickerRowBadge(sizeBytes: Long): String =
        "Live words · ${StreamingPackCatalog.sizeBadge(sizeBytes)}"

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

    // ----------------------------------- what a DEVICE that can never arm costs (4.5.0 Task 4)

    /**
     * THE TIER AXIS, SPELLED ONCE and carried by every sentence that has to say it — in the
     * user's terms rather than the machine's. The machine's version is TWO facts, not one, and
     * `PreviewUnreachable`'s KDoc states them: nothing transcribes on this device at all (the
     * session dies at connect), and a configured cloud provider additionally makes every session
     * one `localPreviewArms` refuses on `!isCloudSession`. This clause is true of both, which is
     * the second reason it names the rule rather than either mechanism.
     *
     * It names the RULE and not the missing file, deliberately: *"live words appear only while
     * transcription runs on this device"* is the whole of why the previewer cannot arm, and it
     * stays true word for word if the open ruling in [PreviewUnreachable]'s KDoc ever widens the
     * input to include a device that HAS a tier but runs every session in the cloud. A sentence
     * that said *"no model is installed"* alone would have to be rewritten for that; this one only
     * changes who reads it.
     *
     * Three sentences share it, so a screen cannot come to describe one fact three ways — which is
     * the defect this whole object is one table for: the Settings section's standing caveat
     * ([NO_TIER_SUBTITLE]), the delete row's reason ([PreviewDeleteCase.OFF_TIER]) and Home's
     * working card, whose usual note ([cardLanguageNote]) is the one sentence on it that this cell
     * makes false.
     */
    private const val NO_TIER =
        "Live words appear only while transcription runs on this device, and this device has no speech model."

    /**
     * The previewer section's own heading on a device that can never arm. It names the REQUIREMENT
     * rather than the refusal, because the requirement is the only actionable half — and the
     * action is the app's own ([com.whispereverywhere.ui.screens.setupBannerState]'s *"Download a
     * model to transcribe on-device"*, and the delete dialog's *"until you download a model
     * again"*), so this sentence sends the reader nowhere new.
     */
    const val NO_TIER_TITLE = "Live words need an on-device speech model"

    /**
     * ...and its body. It offers NOTHING — no tap, no size, no pack — because 73 MB buys this
     * device nothing at all, which is exactly the spend 4.4.1 pass 3's ITEM 1 closed one axis over.
     */
    const val NO_TIER_SUBTITLE =
        "$NO_TIER Download a speech model and live words follow the language you pick. " +
            "Your typed transcript is unchanged."

    /**
     * WHAT THE PREVIEWER'S CAVEAT ROW SAYS, for whichever standing fact is in the way — one pair
     * of functions over [PreviewUnreachable], so the two surfaces that draw that row (the Settings
     * section and Home's language card) cannot answer the same pair of facts differently.
     *
     * The [PreviewUnreachable.NO_PACK_FOR_SELECTION] arm is 4.4.1's own pair, delegated and
     * UNCHANGED — validated copy, reached by one more reader. Only the tier arm is new, and it is
     * new because nothing in the feature said it.
     *
     * @param language the PICKED language's own word, or **null on Auto** — [noLiveWordsTitle]'s
     *        input, and ignored by the tier arm, which is about the device and not about the pick.
     *        That is the precedence: a fact no pick can change outranks a fact a pick would.
     */
    fun unreachableTitle(case: PreviewUnreachable, language: String?): String = when (case) {
        PreviewUnreachable.NO_LOCAL_TIER -> NO_TIER_TITLE
        PreviewUnreachable.NO_PACK_FOR_SELECTION -> noLiveWordsTitle(language)
    }

    fun unreachableSubtitle(case: PreviewUnreachable, language: String?): String = when (case) {
        PreviewUnreachable.NO_LOCAL_TIER -> NO_TIER_SUBTITLE
        PreviewUnreachable.NO_PACK_FOR_SELECTION -> noLiveWordsSubtitle(language)
    }

    // ---------------------------------------------------------------- the offer, by source

    /** Play delivered the pack: verify + copy into `filesDir`, no network at any point. */
    fun settingsInstallFromPack(sizeBytes: Long): String =
        "Included with the app (${StreamingPackCatalog.sizeBadge(sizeBytes)}) and already on this device — nothing to fetch. $ADDITIVE"

    /**
     * Play can serve this install: the ordinary on-demand fetch, and still not a third party.
     *
     * It says the SIZE and the CONNECTION as well as the source, because this is the row where a
     * tap costs the user 73 MB of their data. It does NOT borrow
     * [settingsInstallFromPack]'s "included with the app": the pack is `on-demand`, so on this
     * row the bytes are not on the device yet (fix round 1, B1 — see the class KDoc).
     */
    fun settingsInstallFetch(sizeBytes: Long): String =
        "The app's own ${StreamingPackCatalog.sizeBadge(sizeBytes)} model, fetched from Google " +
            "Play over your connection when you ask for it — never from a third party. $ADDITIVE"

    /**
     * The NON-PLAY row, and the spec's original sentence verbatim. Reached only where
     * [StreamingPackInstall.playCanDeliver] is false — a debug build, a sideload, or a refusal
     * Play has already named as this install's own fault — which is exactly where a download
     * from the commit-pinned Hugging Face base is what the tap does.
     */
    fun installDownload(language: String, sizeBytes: Long): String =
        "Download a ${StreamingPackCatalog.sizeBadge(sizeBytes)} $language preview model. $ADDITIVE"

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
     * [settingsInstallFetch] does.
     */
    fun settingsRepairFetch(sizeBytes: Long): String =
        "$DAMAGED Get it again from Google Play (${StreamingPackCatalog.sizeBadge(sizeBytes)} over your connection) to restore live words."

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

    /**
     * The row's subtitle, by the same table. See the class KDoc for why it is a table, and its
     * SIZE section for why [sizeBytes] is a parameter rather than a constant.
     */
    fun settingsSubtitle(
        state: StreamingPackState,
        language: String,
        sizeBytes: Long,
    ): String = when (state) {
        StreamingPackState.Installed -> installed(language, sizeBytes)
        StreamingPackState.PackDelivered -> settingsInstallFromPack(sizeBytes)
        StreamingPackState.PackFetchable -> settingsInstallFetch(sizeBytes)
        StreamingPackState.Downloadable -> installDownload(language, sizeBytes)
        is StreamingPackState.Repair -> when (state.via) {
            StreamingPackState.PackDelivered -> SETTINGS_REPAIR_FROM_PACK
            StreamingPackState.PackFetchable -> settingsRepairFetch(sizeBytes)
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
     *
     * ### Why it asks about the tier, when it is the one card state that survives without one
     *
     * `PreviewAutoFetch.card` answers `Card.NONE` for the announcement on `!localTierInstalled`
     * (4.4.1 pass 3's ITEM 3) and `Card.OFFER` is unreachable there because
     * `PreviewAutoFetch.decide` refuses before it can answer OFFER — but WORKING is reached from
     * `workInFlight` as well as from the decision, and that disjunct is deliberate: *"hiding its
     * progress card would be hiding their own action from them"* (D17). So this is the one card
     * a device that can never arm can read, and until 4.5.0 Task 4 its second half —
     * [cardLanguageNote]'s *"English shows them"* — was simply false there while the first half
     * was true. The arriving clause STAYS (a running 73 MB must never be hidden, ruling 3c) and
     * the promise becomes the fact.
     *
     * The cell is narrow and it is real: the offer that starts this transfer is withdrawn on such
     * a device (`LivePreviewRows`) and `decide` refuses it, so what remains is a fetch authorised
     * while a tier was installed and the tier deleted from the Settings screen while the bytes
     * were still moving. Narrow is not absent, and the alternative — `Card.NONE` — is the hiding
     * D17 refused.
     */
    fun cardWorking(language: String, localTierInstalled: Boolean): String =
        "The $language preview model is arriving now; the typed transcript is unchanged. " +
            if (localTierInstalled) cardLanguageNote(language) else NO_TIER

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
    fun cardOffer(state: StreamingPackState, language: String, sizeBytes: Long): String =
        settingsSubtitle(state, language, sizeBytes)

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
     * WHAT A SURFACE CAN OFFER FOR PLAY'S OWN DIALOG — the input [workLine]'s one instructing
     * sentence is chosen by, and the whole of what a caller is asked about itself.
     *
     * **Three values because there are three surfaces and they really do differ** (4.5.0 Task 3
     * review r2's N1). Until fix round 2 this was a `Boolean` called `tappable`, and a boolean can
     * only say *"I have the tap"* or *"I do not"* — so the one surface that has NO tap and for
     * which the alternative gesture is also inert had to claim one of the two, and claimed the
     * tap. `AWAITING_ANSWER` is the phase where that costs something: it is Play waiting for a
     * confirmation, and the sentence is the only thing on screen that says what to do about it.
     *
     *  - [ON_THIS_SURFACE] — this row or card carries the gesture itself: the Settings in-flight
     *    row while the selection is still this pack's language (its `onClick` calls
     *    `StreamingPackController.confirm`), and Home's card, which draws [CARD_ANSWER_PLAY].
     *  - [RE_PICK] — no gesture here, but PICKING THIS LANGUAGE AGAIN would raise Play's dialog,
     *    because it would really move the selection: the Settings row once the selection has
     *    moved off this pack (review r3's H3-B3), and the strip for a record that is not the
     *    current selection's.
     *  - [NONE] — no gesture here and no gesture the reader can perform on this surface at all.
     *    The progress strip above the language selector, for the language that IS selected: it is
     *    pinned to have no `onClick` of any kind (`LivePreviewSelectorStripPinTest`
     *    `theStripDecidesNothingAndActuatesNothing`), and a re-pick of the already-selected
     *    language writes the same `String` into the same flow, so nothing emits and nothing
     *    re-raises. Both other forms are false there, which is exactly what fix round 1 shipped.
     *
     * The rule this enum exists to keep is stated in the repo twice over — at the Settings row
     * (*"so AWAITING_ANSWER cannot say 'tap to answer' where there is no tap"*) and in
     * [CARD_ANSWER_PLAY]'s own reason for existing (*"it left a user who back-pressed out of
     * Play's dialog on a note reading 'tap to answer' with nothing to tap"*): **no sentence may
     * instruct a gesture its own surface does not offer.**
     */
    enum class AnswerGesture { ON_THIS_SURFACE, RE_PICK, NONE }

    /**
     * The FACT all three of [AnswerGesture]'s sentences state, before any of them instructs
     * anything. Shared so the three forms cannot drift into three descriptions of one Play state
     * — the defect the whole of this object is one table for.
     */
    private const val AWAITING_PLAY =
        "Google Play needs your confirmation before it fetches the preview model"

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
     * @param answer WHAT THE SURFACE THIS LINE IS RENDERED ON CAN OFFER for Play's own dialog —
     *        the caller's own honest answer, never [workLineTappable]'s *"does this phase deserve
     *        a tap"*. See [AnswerGesture] for why there are three of them and not two. Defaulted
     *        to [AnswerGesture.ON_THIS_SURFACE] so the card, which carries [CARD_ANSWER_PLAY],
     *        reads as before.
     */
    fun workLine(
        work: PreviewWork,
        answer: AnswerGesture = AnswerGesture.ON_THIS_SURFACE,
    ): String? = when (work.phase) {
        PreviewPhase.ASKING -> "Asking Google Play for the preview model…"
        PreviewPhase.AWAITING_ANSWER -> when (answer) {
            AnswerGesture.ON_THIS_SURFACE -> "$AWAITING_PLAY — tap to answer."
            AnswerGesture.RE_PICK -> "$AWAITING_PLAY. Pick that language again to answer."
            // No gesture here and none this row's reader can perform by tapping THIS surface, so
            // the sentence stops at the fact. It names nothing, deliberately: the one control
            // that answers Play is the card's own button, and that card is not always on screen
            // (`PreviewAutoFetch.card` is `Card.NONE` with the switch off, while a running
            // transfer's line is ungated) — so a pointer at it would be the same lie one surface
            // further out.
            AnswerGesture.NONE -> "$AWAITING_PLAY."
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
     * fetch that said *"Downloading"* would contradict [settingsInstallFetch]'s *"never from a
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
            // Play, never a third party — settingsInstallFetch's own promise.
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
     * ### IT ASKS THE SAME QUESTIONS THE CARD TWELVE LINES AWAY ASKS (review r1's B2)
     *
     * `PreviewAutoFetch.card` answers `Card.NONE` on `!showLiveWords` and on `!localTierInstalled`
     * before it will say anything, and each of those guards was earned by a review round —
     * *"every sentence this card can spell is false while the switch is off"* (4.4.1 review r1's
     * B2) and *"no device may be told live words are on where none can appear"* (pass 3's ITEM 3 —
     * whose own mechanism sentence named the wrong reason and is corrected in
     * `PreviewUnreachable`'s KDoc, 4.5.0 T4 fix round 1). `LivePreviewSelectorStrip` and `LiveWordsCard` are
     * two composables in one `HomeScreen` narrating the SAME pack, so a sentence that is false on
     * one is false on the other. The first version of this function asked nothing, and its READY
     * receipt therefore outlived the switch going off and a device that can never arm.
     *
     * Those facts are terms HERE, and not events that retire the record, because the ARRIVAL
     * still happened and the pack is still installed: only the promise is false. The facts that
     * mean the arrival is no longer the case — the model deleted, the verdict withdrawn by
     * `markCorrupt`, the permanent no — retire the record itself ([PreviewWorkboard.retire],
     * where the axis is stated), because after any of them there is no arrival left for any
     * surface to have a sentence about.
     *
     * **And the terms are not a guess about which facts matter: they are `localPreviewArms`' own
     * STANDING terms.** That gate is `sessionLanguage in installedPackLanguages && !isCloudSession
     * && !batchJobActive && userEnabled && previewReady`. `userEnabled` is [showLiveWords].
     * [localTierInstalled] is NOT that gate's `!isCloudSession` — the gate has no tier term at all
     * and the two are different facts (`PreviewUnreachable`'s KDoc, fix round 1); it is the
     * STANDING fact that nothing transcribes on this device at all, which is why a READY receipt
     * is false without it. `installedPackLanguages` is what
     * the delete and the withdrawn verdict take away (and with them the record), and
     * `batchJobActive` is momentary rather than standing — *"whenever you pick it"* is a promise
     * about the next session, not about a batch job running now.
     *
     * `previewReady` is the one that is BOTH, and reading it as momentary is the hole review r2's
     * N2 found: it is the engine's `isWarmFor(pack)`, which is false while a model loads — a
     * moment — and false **for the rest of the process** once that language has been disabled by a
     * load that threw, a failed canary, a missing clip or three decode throws. That second half
     * never comes back, so it is a standing term, and [disabledLanguages] is it. Without it the
     * receipt outlived a pack the previewer had taken out from under it, with the bytes still
     * installed and nothing anywhere saying so.
     *
     * There is deliberately no `userSaidNo` term, because the gate has none either: a user who
     * declined and then installed from the Settings row really does get live words, and the card's
     * silence there is about not nagging rather than about truth.
     *
     * ### Why the SELECTION is an input, and what it buys
     *
     * **The strip's answer about ITSELF is [AnswerGesture.NONE] and can never be anything else**,
     * because it has no `onClick` anywhere in it and is pinned never to grow one
     * (`LivePreviewSelectorStripPinTest.theStripDecidesNothingAndActuatesNothing` — a tap here
     * would be a fourth actuator). So the selection does NOT choose whether this surface has a
     * gesture; it chooses between the two sentences that are about a control SOMEWHERE ELSE:
     *
     *  - **off-selection** — picking this record's language would really move the selection,
     *    emit, and re-raise Play's dialog, and the control that does it is the selector
     *    immediately below this strip. [AnswerGesture.RE_PICK], and it points at something the
     *    reader can see.
     *  - **on-selection** — a re-pick writes the same `String` into the same flow, `Set.plus`
     *    returns an equal set and `MutableStateFlow` conflates by `equals`, so nothing emits,
     *    nothing recomposes and `LaunchedEffect(previewPhase)` cannot re-fire. The re-pick
     *    sentence is inert (fix round 1's own finding) and *"tap to answer"* is false (fix round
     *    2, review r2's N1): there is no tap on this row and none can be added. So the line
     *    states the fact and instructs nothing.
     *
     * On-selection is the ORDINARY case here, which is why getting it wrong mattered: an
     * `AWAITING_ANSWER` record exists BECAUSE that language was picked.
     *
     * **This is deliberately NOT the Settings row's formula.** That row computes
     * `workLineTappable(work) && selectedPack == previewPack` and both halves are about a tap it
     * really has. Fix round 1 borrowed the second half alone and called it *"the Settings row's
     * own formula"*; it was half of it, with the meaning of the parameter changed underneath —
     * which is how a row with no gesture came to instruct one.
     *
     * @param work the board's record for ONE language. The strip renders a row per record, so two
     *        arrivals are two rows rather than one overwriting the other.
     * @param language that record's own language as the picker spells it — never the SELECTED
     *        one. A transfer keeps its surface when the selection moves off it (the Settings row's
     *        H-B3), so the row has to name the pack it is about.
     * @param selectedLanguage the user's picked code (`PreferencesManager.selectedLanguage`, or
     *        the onboarding step's own pick), compared with [PreviewWork.language] rather than
     *        with [language] — the display name is a word, and the record is keyed by the code.
     * @param showLiveWords the *"Show live words"* switch (`PreferencesManager
     *        .localPreviewEnabled`), the same input `card` and `decide` read.
     * @param localTierInstalled an on-device whisper tier exists — the same input, for the same
     *        reason: without one nothing transcribes on this device at all (the session dies at
     *        connect — `PreviewUnreachable`'s KDoc, not `localPreviewArms`, is where that is
     *        stated) and no word can ever reach the bubble, however installed the pack is.
     * @param disabledLanguages the languages whose previewer THIS PROCESS has taken off
     *        ([PreviewDisabled], written by the engine's own `disable`) — **not** the user's
     *        switch, which is [showLiveWords]. A SET rather than a Boolean for
     *        `installedPackLanguages`' reason: the strip renders a row per language, and one
     *        language going off says nothing about another's. Compared against
     *        [PreviewWork.language] rather than [language], because the verdict is keyed by code.
     */
    fun selectorLine(
        work: PreviewWork,
        language: String,
        selectedLanguage: String?,
        showLiveWords: Boolean,
        localTierInstalled: Boolean,
        disabledLanguages: Set<String>,
    ): String? = when (work.phase) {
        // The one sentence the work line has no phase for, and the one the ruling asks for by
        // name. The board keeps a terminal record, so this is the receipt for an arrival THIS
        // PROCESS made — not a badge on every installed pack, which is the Settings row's job and
        // the language step's chip's. It is a PROMISE about the future ("words appear... whenever
        // you pick it"), so it is made only where every standing fact it depends on holds; the
        // facts that mean the arrival is no longer the case have already taken the record away.
        PreviewPhase.INSTALLED ->
            if (showLiveWords && localTierInstalled && work.language !in disabledLanguages) {
                selectorReady(language)
            } else {
                null
            }
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
        // transfer by picking, so the place they picked is the place that owes them the news. The
        // card's own retry offer is reached by a different route (the launch latch, not the
        // connection), and a user who has scrolled past it would otherwise read nothing at all.
        PreviewPhase.FAILED,
        // The IN-FLIGHT lines are deliberately NOT gated on the switch or the tier: they describe
        // a transfer that is actually happening, in the present tense, and it is happening whoever
        // started it and whatever the device can arm. Hiding a running 73 MB from the user is the
        // silent spend ruling 3c exists to close.
        -> workLine(
            work,
            // This surface has no gesture, ever (see the KDoc): the selection only chooses
            // between naming the selector below — which really does re-raise Play's dialog for a
            // record that is not the current pick — and naming nothing at all.
            answer = if (selectedLanguage == work.language) {
                AnswerGesture.NONE
            } else {
                AnswerGesture.RE_PICK
            },
        )
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
