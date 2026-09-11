package com.whispereverywhere.transcription.stream

import com.whispereverywhere.npu.NpuPackFetch

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
 * why this row must answer [NpuPackFetch.FetchState.NeedsConfirmation] at all (Play raises its
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

    /** The row's name once the model is installed, and the feature's name everywhere else. */
    const val SETTINGS_TITLE = "Live words while you speak (English)"

    const val SWITCH_TITLE = "Show live words"

    const val DELETE_TITLE = "Delete the preview model"

    /** What deleting costs and — the promise again — what it does not cost. */
    val DELETE_SUBTITLE = "Frees $BADGE. Live words stop; the typed transcript is unchanged."

    val SETTINGS_INSTALLED =
        "Installed ($BADGE). Words appear on the bubble as you speak English; the typed transcript is unchanged."

    /**
     * RULING ASSUMED (R1): the canary is the only SME guard; this is what the row says after it
     * fails.
     *
     * NOT RENDERED BY THIS TASK, and deliberately so: the verdict lives on the previewer instance
     * the service builds (`StreamingPreviewEngine.disabled`), and Task 7 owns both that wiring
     * and the only reader of it. The sentence is pinned here now so the words are decided in the
     * one place the feature's copy is reviewed, rather than invented at the wiring site.
     */
    const val SETTINGS_DISABLED_ON_DEVICE =
        "Live words are off on this device: the preview model did not pass its start-up check. Your transcripts are unaffected."

    const val LANGUAGE_STEP_SENTENCE =
        "Live words on the bubble are English-only for now; other languages show a progress line while each sentence is transcribed."

    /** Rendered in the English row's subtitle slot on the language step when the pack is installed. */
    const val LANGUAGE_CHIP = "Live words on the bubble while you speak — preview model installed."

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
    val SETTINGS_INSTALL_DOWNLOAD = "Download a $BADGE English preview model. $ADDITIVE"

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
    fun settingsTitle(state: StreamingPackState): String = when (state) {
        StreamingPackState.Installed -> SETTINGS_TITLE
        StreamingPackState.PackDelivered -> "Install the English preview model"
        StreamingPackState.PackFetchable -> "Get the English preview model"
        StreamingPackState.Downloadable -> "Download the English preview model"
        is StreamingPackState.Repair -> "Repair the English preview model"
    }

    /** The row's subtitle, by the same table. See the class KDoc for why it is a table. */
    fun settingsSubtitle(state: StreamingPackState): String = when (state) {
        StreamingPackState.Installed -> SETTINGS_INSTALLED
        StreamingPackState.PackDelivered -> SETTINGS_INSTALL_FROM_PACK
        StreamingPackState.PackFetchable -> SETTINGS_INSTALL_FETCH
        StreamingPackState.Downloadable -> SETTINGS_INSTALL_DOWNLOAD
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
     * The discovery card's own name for the feature. The Settings row's [SETTINGS_TITLE] is a ROW
     * NAME — it answers "what is this row" for someone already reading a settings list — and this
     * card exists precisely because that list was never opened (owner, 2026-09-11: *"That way the
     * users don't have to discover the setting at all"*). So the headline names the surface the
     * words appear on, and the body underneath is the route's own sentence from the table above.
     */
    const val CARD_TITLE = "Live words on the bubble"

    /**
     * The card while the fetch or the install runs. It promises nothing about when, carries the
     * additive promise in the shortest true form, and asks for nothing — a working card that
     * mentioned Settings or a tap would undo the ruling it exists to serve. The live progress
     * line under it is [fetchLine]'s or [downloadProgress]'s, never a second wording.
     */
    const val CARD_WORKING =
        "The English preview model is arriving now; the typed transcript is unchanged."

    /** The one-time announcement's headline, once the model has landed. */
    const val CARD_INSTALLED_TITLE = "Live words are on"

    /**
     * The announcement's body — the owner's own sentence (*"Live words are on — pick English to
     * see them"*) split across the headline and here, and the ONE place the English gate is
     * explained. `localPreviewArms` is deliberately unchanged (the owner tests on Auto on purpose
     * and found the behaviour correct once explained), so this card is where the explanation
     * belongs.
     */
    const val CARD_INSTALLED =
        "Pick English as your transcription language to see them on the bubble as you speak; " +
            "the typed transcript is unchanged."

    /** The X's content description — the cloud-key note's own label, for the same gesture. */
    const val CARD_DISMISS = "Dismiss"

    /**
     * The working card's one action, and the only gesture that card can ever need: Google Play is
     * holding its own dialog (a cellular or size confirmation, or a wait for wifi — both
     * `STATUS_REQUIRES_USER_CONFIRMATION` and `STATUS_WAITING_FOR_WIFI` arrive as
     * [NpuPackFetch.FetchState.NeedsConfirmation]), and [fetchLine] says so, ending in *"tap to
     * answer"*.
     *
     * Without this button that sentence named a gesture the card did not have (review r1, B3):
     * the dialog is raised once per ENTRY into that state, so a user who backed out of it was
     * parked on an instruction with only the X left — and the X is the permanent no. The Settings
     * row solved the same thing with [fetchLineTappable] plus a tap that re-shows PLAY'S OWN
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
    fun cardOffer(state: StreamingPackState): String = settingsSubtitle(state)

    /** The offer card's action label — the ACTION's own name, so it names the source it will use. */
    fun cardAction(state: StreamingPackState): String = settingsTitle(state)

    // ---------------------------------------------------------------- our own work in flight

    /** Between the tap and the first byte — [StreamingPackManager.download]'s own dead time. */
    const val PROGRESS_STARTING = "Starting…"

    /**
     * The verify + land, whichever source the bytes came from: a hash of 72,654,782 B and a copy
     * into `filesDir`, with no meaningful progress to report on the pack route (its `onProgress`
     * is called twice, at 0 and at the end). Also what Play's own `Verifying` status reads as,
     * because it is the same work.
     */
    const val PROGRESS_INSTALLING = "Verifying and installing…"

    /**
     * The fallback download's progress. Invents no denominator when the total is unknown, and
     * rounds both halves through the catalog's one rule, so the line cannot end at "72 of 72 MB"
     * under a row that has just promised 73.
     */
    fun downloadProgress(soFar: Long, total: Long): String =
        if (total > 0L) {
            "${StreamingPackCatalog.megabytes(soFar)} of ${StreamingPackCatalog.sizeBadge(total)}"
        } else {
            "Downloading…"
        }

    /**
     * The last-resort failure sentence: every refusal the manager raises carries its own words
     * ([StreamingPackException]), so this is only reached by a throwable that named nothing.
     */
    const val INSTALL_FAILED = "The preview model could not be installed."

    // ---------------------------------------------------------------- the Play fetch in flight

    /**
     * What the row shows while [StreamingPackController] is working, or after it has stopped —
     * null at rest, where the row goes back to its own offer.
     *
     * A [NpuPackFetch.FetchState.Failed] is shown VERBATIM: the shell has already re-told every
     * refusal in this feature's words ([StreamingPackInstall.deliveryRefusal] /
     * [StreamingPackInstall.fetchRefusal]), so re-wording it here would be a second copy of the
     * copy — and the first one is the one that knows Play's error code.
     *
     * The voice's twin is `TtsModelManager.fetchLine`; the two differ only in the noun, which is
     * the whole reason they are separate (a row that called the preview model "the voice" is the
     * bug this feature's own sentences exist to prevent).
     */
    fun fetchLine(state: NpuPackFetch.FetchState): String? = when (state) {
        is NpuPackFetch.FetchState.Idle,
        is NpuPackFetch.FetchState.Installed,
        is NpuPackFetch.FetchState.Cancelled,
        -> null
        is NpuPackFetch.FetchState.Pending -> "Asking Google Play for the preview model…"
        is NpuPackFetch.FetchState.Downloading ->
            if (state.total > 0L) {
                "Fetching the preview model: ${state.soFar / 1_000_000} of " +
                    "${state.total / 1_000_000} MB"
            } else {
                "Fetching the preview model…"
            }
        is NpuPackFetch.FetchState.Transferring ->
            "Google Play is moving the preview model into place…"
        is NpuPackFetch.FetchState.Verifying -> PROGRESS_INSTALLING
        is NpuPackFetch.FetchState.NeedsConfirmation ->
            "Google Play needs your confirmation before it fetches the preview model — tap to answer."
        is NpuPackFetch.FetchState.Failed -> state.reason
    }

    /**
     * Whether a TAP on the row showing [fetchLine] does anything — `TtsModelManager`'s B1 lesson,
     * inherited rather than re-learned: the row renders a line for every state a fetch passes
     * through, `SettingsItem` makes itself clickable the moment it is handed an `onClick`, and a
     * tap during the copy+hash would otherwise re-enter the row's one action and start a SECOND
     * install into the same temp dir.
     *
     * The retry the branch exists for is the TERMINAL one; the one in-flight state that stays
     * tappable is [NpuPackFetch.FetchState.NeedsConfirmation], where the tap re-shows PLAY'S OWN
     * dialog and starts no install of ours. Total over the machine, with the three at-rest states
     * spelled out even though they render no line: a state added there must be answered rather
     * than fall through a wildcard into "tappable, mid-install".
     */
    fun fetchLineTappable(state: NpuPackFetch.FetchState): Boolean = when (state) {
        is NpuPackFetch.FetchState.Failed,
        is NpuPackFetch.FetchState.NeedsConfirmation,
        -> true
        is NpuPackFetch.FetchState.Pending,
        is NpuPackFetch.FetchState.Downloading,
        is NpuPackFetch.FetchState.Transferring,
        is NpuPackFetch.FetchState.Verifying,
        -> false
        is NpuPackFetch.FetchState.Idle,
        is NpuPackFetch.FetchState.Installed,
        is NpuPackFetch.FetchState.Cancelled,
        -> false
    }
}
