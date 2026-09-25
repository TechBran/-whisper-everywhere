package com.whispereverywhere.npu

import java.io.File

/**
 * The Play fetch flow's PURE state machine (4.2 F5) — every decision `NpuPackController` makes,
 * as executable functions, so a JVM test can prove them while the Android shell is pinned as
 * source. The same split, for the same reason, as `NpuImportController`/[NpuAssetImport]: the
 * shell is `AssetPackManager`-bound and cannot be constructed off-device, and the part that was
 * ever going to be wrong is the MAPPING — which status becomes which state, which error code
 * becomes which sentence.
 *
 * ### The status ints are MIRRORED constants, and the mirror is tested
 *
 * This object deliberately imports nothing from Play so the machine stays pure; the `STATUS_*`
 * and `ERROR_*` constants below are documented against
 * `com.google.android.play.core.assetpacks.model.AssetPackStatus` / `AssetPackErrorCode`, and
 * `NpuPackFetchTest` asserts each one equal to the library's own same-named field — so a
 * dependency upgrade that renumbers or extends either enum fails a named JVM test instead of
 * shipping a silent remap.
 *
 * ### COMPLETED means DELIVERED, never installed
 *
 * Play's terminal success status says the pack's bytes reached the device. Nothing about them
 * has been verified against the census, so [advance] maps COMPLETED to [FetchState.Verifying] —
 * the start of OUR work (`WhisperModelManager.installFromPack`: metadata cross-check, streamed
 * sha256, the shared parking transaction) — and [FetchState.Installed] is only ever published by
 * the shell after that returns `ImportState.Installed`.
 *
 * ### Failure is loud and total over Int
 *
 * [advance] answers for EVERY int, documented or not: an unrecognized status becomes
 * [FetchState.Failed] carrying its number, and [failureReason] names every error code the
 * library declares in user words with the honest next action — a sideloaded install is told the
 * import path, never shown a dead end. Silence is not a state anywhere in this machine.
 *
 * ### A pair may arrive in more than one pack (P2-4)
 *
 * A MediaTek pair is 1.88 GB against Play's 1.5 GB per-pack cap, so it is TWO packs — the census
 * row's [PackArtifact.parts], read through [packsFor]. The fetch is still ONE state: the
 * list overload of [advance] folds every part's reading into it — the worst status wins, bytes
 * are summed, and delivery is complete only when EVERY part is. A Qualcomm pair is one part, and
 * for one part the fold IS the per-part mapping, status for status (executed in
 * `NpuPackFetchTest`), so nothing about the Qualcomm flow moved.
 */
object NpuPackFetch {

    /**
     * Which committed pack module serves which paired tier ON THE QUALCOMM FLEET — spelled through
     * the tier ids' own HOMES (the npu constant, the turbo spec) so the map cannot drift from the
     * catalog side, and pinned against F4's `packName.set(…)` facts from the module side.
     *
     * Since P2-4 nothing fetches THROUGH this map: the machinery asks [packsFor], which answers the
     * device family's own [PackArtifact.parts]. It stays because it is what those parts default to
     * on a Qualcomm row — one part, this tier's pack, both entries — and `NpuFleetCensusTest` holds
     * every Qualcomm row's parts equal to it, so the two spellings cannot part company.
     */
    val PACK_BY_TIER: Map<String, String> = mapOf(
        NpuAssetImport.TIER_ID to "npu_small",
        NpuModelSpec.TURBO.tierId to "npu_turbo",
    )

    /**
     * The asset packs [tierId]'s pair arrives in on [family] — the census row's own
     * [PackArtifact.parts], in order, the encoder's part first (P2-4; design §2.7) — or EMPTY when
     * this build has nothing to fetch there: no family resolved, or no measured pair for the tier
     * on it. The ONE answer to "which packs": `NpuPackController` fetches, cancels and gives back
     * exactly these, and `WhisperModelManager.installFromPack` reads each entry out of the part
     * that carries it ([deliveredEntryDirs]). A Qualcomm row answers one part, [PACK_BY_TIER]'s
     * pack carrying both entries — what the single-pack machinery fetched before parts existed.
     */
    fun packsFor(tierId: String, family: NpuSocFamily?): List<PackPart> =
        family?.let { NpuFleetCensus.artifactFor(it.id, tierId) }?.parts.orEmpty()

    /**
     * The `pack=` field of the `pack:` lines for a fetch of [parts]: the one pack's name for a
     * one-part pair — `npu_turbo`, the line every Qualcomm fetch has always printed, byte for
     * byte — and the parts' names joined by `+` for more
     * (`npu_turbo_mt6989_enc+npu_turbo_mt6989_dec`), one token either way, so the line still
     * parses on spaces.
     */
    fun packLabel(parts: List<PackPart>): String = parts.joinToString("+") { it.packName }

    /**
     * Where each entry of a delivered pair is (P2-4): the directory Play delivered the PART that
     * carries it into — `<that part's assetsPath>/<that part's pack name>/`, which is where a
     * device-targeted variant lands once Play strips its `#group_<g>` suffix (4.2 F8) and where
     * an untargeted module's one directory already is — keyed by the entry's delivery name. So
     * the encoder of a two-part pair is read out of part 1's directory and its decoder out of
     * part 2's, and both
     * entries of a one-part pair out of the one pack's, as they always were. A part Play gave no
     * location for contributes nothing: its entries are simply absent, and the install's
     * both-present check refuses them by name.
     *
     * @param assetsPaths each delivered part's `AssetPackLocation.assetsPath()`, by pack name.
     */
    fun deliveredEntryDirs(parts: List<PackPart>, assetsPaths: Map<String, String>): Map<String, File> {
        val dirs = LinkedHashMap<String, File>()
        for (part in parts) {
            val partDir = assetsPaths[part.packName]?.let { File(it, part.packName) } ?: continue
            for (entry in part.entries) dirs[entry.fileName] = partDir
        }
        return dirs
    }

    /**
     * The delivered directory holding the pair's `metadata.json` — PART 1's (P2-4; design §2.7:
     * the metadata sits in part 1 and lists BOTH entries, so `NpuPackMetadata.parse` stays
     * "exactly two") — or null when Play gave part 1 no location, which the install reads as the
     * empty delivery it is.
     */
    fun deliveredMetadataDir(parts: List<PackPart>, assetsPaths: Map<String, String>): File? {
        val first = parts.firstOrNull() ?: return null
        return assetsPaths[first.packName]?.let { File(it, first.packName) }
    }

    // ---------------------------------------------------------------- AssetPackStatus mirror
    // All TEN documented statuses (asset-delivery 2.3.0), asserted equal to the library's own
    // fields by NpuPackFetchTest.theMirroredStatusConstantsEqualTheLibrarysOwn.

    /** `AssetPackStatus.UNKNOWN` — a real answer Play can give, mapped loudly, never silently. */
    const val STATUS_UNKNOWN: Int = 0

    /** `AssetPackStatus.PENDING` — the fetch is queued and no bytes have moved yet. */
    const val STATUS_PENDING: Int = 1

    /** `AssetPackStatus.DOWNLOADING` — bytes are moving; the state carries them. */
    const val STATUS_DOWNLOADING: Int = 2

    /** `AssetPackStatus.TRANSFERRING` — downloaded, being moved into the app's pack storage. */
    const val STATUS_TRANSFERRING: Int = 3

    /** `AssetPackStatus.COMPLETED` — DELIVERED. Our verification starts here; see the KDoc. */
    const val STATUS_COMPLETED: Int = 4

    /** `AssetPackStatus.FAILED` — the refusal carrier; the error code names the reason. */
    const val STATUS_FAILED: Int = 5

    /** `AssetPackStatus.CANCELED` — the user's own stop, a normal outcome and not a failure. */
    const val STATUS_CANCELED: Int = 6

    /** `AssetPackStatus.WAITING_FOR_WIFI` — Play wants the user's cellular consent. */
    const val STATUS_WAITING_FOR_WIFI: Int = 7

    /** `AssetPackStatus.NOT_INSTALLED` — no fetch exists; the flow's rest state. */
    const val STATUS_NOT_INSTALLED: Int = 8

    /** `AssetPackStatus.REQUIRES_USER_CONFIRMATION` — Play wants its own dialog shown. */
    const val STATUS_REQUIRES_USER_CONFIRMATION: Int = 9

    // ---------------------------------------------------------------- AssetPackErrorCode mirror
    // Every code the 2.3.0 library declares (13 — NpuPackFetchTest enumerates the class), plus
    // PLAY_STORE_NOT_FOUND: a Play Core 1.x code the current AssetPackErrorCode no longer
    // declares (measured at this task's red step), kept because the service side can still
    // surface -11 and the table is total over Int — an arm costs nothing, silence costs a user.

    /** `AssetPackErrorCode.NO_ERROR` — a FAILED status wearing no reason; still named. */
    const val ERROR_NO_ERROR: Int = 0

    /** `AssetPackErrorCode.APP_UNAVAILABLE`. */
    const val ERROR_APP_UNAVAILABLE: Int = -1

    /**
     * `AssetPackErrorCode.PACK_UNAVAILABLE` — Play has no such pack for this app version: either
     * the version does not declare it, or Play has not finished staging it YET (2026-09-25: -2 for
     * about three hours after an upload, while Play prepared the Tab's 1.2 GB pack, then served).
     */
    const val ERROR_PACK_UNAVAILABLE: Int = -2

    /** `AssetPackErrorCode.INVALID_REQUEST`. */
    const val ERROR_INVALID_REQUEST: Int = -3

    /** `AssetPackErrorCode.DOWNLOAD_NOT_FOUND`. */
    const val ERROR_DOWNLOAD_NOT_FOUND: Int = -4

    /** `AssetPackErrorCode.API_NOT_AVAILABLE` — one of the sideload truths; see [failureReason]. */
    const val ERROR_API_NOT_AVAILABLE: Int = -5

    /** `AssetPackErrorCode.NETWORK_ERROR`. */
    const val ERROR_NETWORK_ERROR: Int = -6

    /** `AssetPackErrorCode.ACCESS_DENIED`. */
    const val ERROR_ACCESS_DENIED: Int = -7

    /** `AssetPackErrorCode.INSUFFICIENT_STORAGE` — the refusal names the pair's size. */
    const val ERROR_INSUFFICIENT_STORAGE: Int = -10

    /** Play Core 1.x `PLAY_STORE_NOT_FOUND` — not in 2.3.0's class; see the section comment. */
    const val ERROR_PLAY_STORE_NOT_FOUND: Int = -11

    /** `AssetPackErrorCode.APP_NOT_OWNED` — THE sideload code: not acquired from Play. */
    const val ERROR_APP_NOT_OWNED: Int = -13

    /** `AssetPackErrorCode.CONFIRMATION_NOT_REQUIRED`. */
    const val ERROR_CONFIRMATION_NOT_REQUIRED: Int = -14

    /** `AssetPackErrorCode.UNRECOGNIZED_INSTALLATION` — the sideload truth, newer spelling. */
    const val ERROR_UNRECOGNIZED_INSTALLATION: Int = -15

    /** `AssetPackErrorCode.INTERNAL_ERROR`. */
    const val ERROR_INTERNAL_ERROR: Int = -100

    /**
     * The fetch as the UI sees it — one type, so the F6/F7 screens cannot render a state this
     * machine never produces. [Failed] is the flow's REFUSAL CARRIER (the certification's
     * carrier ruling): a fetch that failed — a fetched-but-corrupt pack included — surfaces its
     * reason HERE, on the fetch card, and never through the `unavailableReason` machinery,
     * whose job stays what it has always been: a tier that installed and then declined at load.
     */
    sealed interface FetchState {
        /** No fetch exists. The rest state, and NOT_INSTALLED's honest mapping. */
        object Idle : FetchState

        /** Queued with Play; no bytes yet. */
        object Pending : FetchState

        /** [soFar] of [total] compressed bytes delivered so far. */
        data class Downloading(val soFar: Long, val total: Long) : FetchState

        /** Play is moving the downloaded pack into place — brief, but a real state. */
        object Transferring : FetchState

        /**
         * DELIVERED, and now being verified by US: metadata cross-check, streamed sha256, the
         * shared parking transaction. [soFar]/[total] are the verify-copy's own progress.
         */
        data class Verifying(val soFar: Long, val total: Long) : FetchState

        /** The pair verified and renamed into place — `installFromPack` returned Installed. */
        object Installed : FetchState

        /** The refusal carrier. [reason] is user-facing copy, rendered verbatim by the card. */
        data class Failed(val reason: String) : FetchState

        /** The user's own stop. A retry is one tap; nothing was installed. */
        object Cancelled : FetchState

        /** Play wants its OWN confirmation dialog (wifi-wait or explicit consent) — the card
         *  offers exactly `showConfirmationDialog`, never a custom re-ask. */
        object NeedsConfirmation : FetchState
    }

    /**
     * Map one `AssetPackState` reading to exactly one [FetchState] — TOTAL over Int, whatever
     * the library documents. UNKNOWN and any unrecognized value land in [FetchState.Failed]
     * with their number: never silence, the same discipline as the error table.
     */
    fun advance(status: Int, errorCode: Int, soFar: Long, total: Long): FetchState = when (status) {
        STATUS_UNKNOWN -> FetchState.Failed("Google Play reported status 0 (unknown)")
        STATUS_PENDING -> FetchState.Pending
        STATUS_DOWNLOADING -> FetchState.Downloading(soFar, total)
        STATUS_TRANSFERRING -> FetchState.Transferring
        // Completion of DELIVERY is the start of OUR verification, never Installed.
        STATUS_COMPLETED -> FetchState.Verifying(0, total)
        STATUS_FAILED -> FetchState.Failed(failureReason(errorCode, total))
        STATUS_CANCELED -> FetchState.Cancelled
        STATUS_WAITING_FOR_WIFI -> FetchState.NeedsConfirmation
        STATUS_REQUIRES_USER_CONFIRMATION -> FetchState.NeedsConfirmation
        STATUS_NOT_INSTALLED -> FetchState.Idle
        else -> FetchState.Failed("Google Play reported status $status")
    }

    /**
     * ONE part's latest `AssetPackState` — the four numbers the per-part [advance] has always
     * taken, kept per part (P2-4) so the pair's state can be re-folded from all of them on every
     * update, whichever part the update was about.
     */
    data class PartReading(val status: Int, val errorCode: Int, val soFar: Long, val total: Long)

    /**
     * THE PARTS MACHINE (P2-4; design §2.7): the pair's ONE [FetchState], folded from every
     * part's latest reading, in part order. A null reading is a part Play has said nothing about
     * yet in this fetch — asked for, unanswered — and reads as [FetchState.Pending].
     *
     * Each part goes through the per-part [advance] (the ONE status mapping, so no status is
     * interpreted twice), and **the worst status wins**, by this order, worst first:
     *
     * ```
     *   Failed > Cancelled > NeedsConfirmation > Idle > Pending > Downloading > Transferring > Verifying
     * ```
     *
     * which is "furthest from delivered", with the three that need the USER on top: a failure the
     * card must name (the first failing part's, in part order), the user's own stop, and Play's
     * consent dialog — which covers every pack waiting on it, so one confirmation serves all
     * parts.
     *
     * **Once EVERY part has a reading, bytes moving outrank waiting** (the P2b review's small 1):
     *
     * ```
     *   Failed > Cancelled > NeedsConfirmation > Downloading > Transferring > Pending > Idle > Verifying
     * ```
     *
     * Play may download the parts one after another, and under the first order a part still queued
     * (Pending) or not started (Idle) beside a part downloading showed a bar-less Pending for the
     * whole 1.3 GB encoder — or, for Idle, the card's Get button while bytes were moving. With every
     * part answered the summed total covers the pair, so the bar is honest; while ANY part is
     * unanswered the first order holds, and the pair stays Pending (never a bar over half its
     * bytes, never a second Get). Delivery gating is the same in both: Verifying is the best rank,
     * answered only when every part is COMPLETED. What the order buys, as rules:
     *
     *  - **Install begins only when EVERY part is delivered.** [FetchState.Verifying] — the state
     *    the controller launches the install on — is the best rank, so the fold answers it only
     *    when every part reads COMPLETED. One part delivered and the other failed is Failed, never
     *    a partial install; the retry fetches the pair again, and only the failed part moves
     *    bytes. The part Play already holds reports COMPLETED in the fetch TASK's own result — the
     *    `AssetPackStates` of every requested pack — and NOT through the state listener, which
     *    fires on changes alone and has none to report for a finished pack; so the controller
     *    fills that part's reading from the Task's answer ([unansweredParts]). (Corrected at P2-7:
     *    this line once said Play answered "at once", which is true of the Task result only, and
     *    the controller read nothing but the listener — the P2b review's FIX-NOW.)
     *  - **Bytes are summed.** Downloading and Verifying carry the sum over every part's reported
     *    bytes, so one progress bar covers the pair (a delivered part counts as all of its bytes).
     *    The pair's total also stands in for the part's in the per-part mapping, so a storage
     *    refusal names what the PAIR needs.
     *  - **Re-attach re-queries every part.** After process death the controller starts with no
     *    readings — every part Pending — and fetching the pair answers each part's status into
     *    this fold: the fetch Task's result for a part Play already holds, the listener for one
     *    still moving.
     *
     * For ONE part the fold is the per-part [advance], status for status — executed in
     * `NpuPackFetchTest` over every documented status and two off-table ones — which is what
     * keeps every Qualcomm fetch exactly as it was. An empty list (no parts at all: a caller bug
     * the controller refuses before it gets here) folds to [FetchState.Idle]: nothing requested.
     */
    fun advance(parts: List<PartReading?>): FetchState {
        if (parts.isEmpty()) return FetchState.Idle
        val soFar = parts.sumOf { it?.soFar ?: 0L }
        val total = parts.sumOf { it?.total ?: 0L }
        val each = parts.map { reading ->
            if (reading == null) FetchState.Pending
            else advance(reading.status, reading.errorCode, reading.soFar, total)
        }
        val everyPartAnswered = parts.all { it != null }
        // maxBy keeps the FIRST element of the highest rank: the first failing part, in part order.
        return when (val worst = each.maxBy { rank(it, everyPartAnswered) }) {
            is FetchState.Downloading -> FetchState.Downloading(soFar, total)
            is FetchState.Verifying -> FetchState.Verifying(0, total)
            else -> worst
        }
    }

    /**
     * WHICH PARTS THE FETCH TASK'S OWN ANSWER FILLS (the P2b review's FIX-NOW) — the pack names,
     * in part order, of every part Play answered for in the Task result ([answered]: the result's
     * `packStates()` keys) whose reading is still null in this fetch.
     *
     * Why the Task's answer matters at all: Play's state listener fires on CHANGES — its per-pack
     * session updates — and a pack that is already COMPLETED (the encoder that landed before the
     * decoder failed, was cancelled, or lost its process) has no change to report. Its COMPLETED
     * arrives in exactly one place, the result of the `fetch` Task: the `AssetPackStates` of every
     * requested pack as it stood at the request. A controller that read only the listener folded
     * [null, …] to Pending for good — `isBusy()` true, every retry tap refused.
     *
     * Why only the null ones: a listener reading is never OLDER than the request's snapshot, so it
     * is never overwritten — which also keeps the one-part path exactly what it was whenever the
     * listener answers first. (bundletool's `--local-testing` fake replays PENDING, DOWNLOADING,
     * TRANSFERRING for every pack on every fetch, so local testing cannot show the difference; a
     * Play delivery can.)
     */
    fun unansweredParts(parts: List<PackPart>, readings: List<PartReading?>, answered: Set<String>): List<String> =
        parts.withIndex()
            .filter { (i, part) -> i < readings.size && readings[i] == null && part.packName in answered }
            .map { it.value.packName }

    /**
     * The fold's order (see the list [advance]): higher is worse. The top three and the bottom one
     * never move; the four in-flight states reorder once [everyPartAnswered] — activity outranks
     * waiting, so a part moving bytes names the pair's state.
     */
    private fun rank(state: FetchState, everyPartAnswered: Boolean): Int = when (state) {
        // Installed is never produced by a Play status (NpuPackFetchTest proves it for every
        // one), so it cannot reach the fold; ranked with Verifying for totality.
        is FetchState.Verifying, is FetchState.Installed -> 0
        is FetchState.Transferring -> if (everyPartAnswered) 3 else 1
        is FetchState.Downloading -> if (everyPartAnswered) 4 else 2
        is FetchState.Pending -> if (everyPartAnswered) 2 else 3
        is FetchState.Idle -> if (everyPartAnswered) 1 else 4
        is FetchState.NeedsConfirmation -> 5
        is FetchState.Cancelled -> 6
        is FetchState.Failed -> 7
    }

    /**
     * The sideload answer — the one failure the primary test device will actually show, so it
     * gets exact copy: the truth stated as the PATH FORWARD (the SAF import), never a dead end.
     */
    private const val SIDELOAD_ANSWER: String =
        "Google Play can't deliver the model to this install — it wasn't installed from Play. " +
            "Use 'Import model pair…' below instead."

    // ---------------------------------- the reasons that name the import, and their twins (P3a)

    /** [failureReason]'s APP_UNAVAILABLE sentence — one of the family that names the import. */
    private const val APP_UNAVAILABLE_ANSWER: String =
        "Google Play says this app is currently unavailable, so it can't deliver the " +
            "model right now. Try again later, or use 'Import model pair…' below."

    /**
     * [failureReason]'s PACK_UNAVAILABLE sentence — one of the family that names the import.
     *
     * TRUE IN BOTH CASES PLAY ANSWERS -2 FOR (reworded 4.16.1). It used to say *"This version of the
     * app doesn't offer that model pack on Google Play. Update the app from Play"* — right only when
     * the version really lacks the pack. On 2026-09-25 the Tab S10+ was on the current version and
     * Play answered -2 for about three hours after the upload while it staged the 1.2 GB pack
     * ("Request to PGS failed because all packs are unavailable"), then served it on the owner's
     * Retry: the sentence had told him to update an app that was already up to date. It now states
     * the fact both cases share (not available for this version YET), the staging case's step (try
     * again in a few hours — "may", because nothing here knows Play's staging time) and the missing
     * pack's (update the app, after a day). No promise, no comparative. The card's Retry stays
     * beside it: a Failed fetch leaves the controller's single-flight guard open.
     */
    private const val PACK_UNAVAILABLE_ANSWER: String =
        "That model pack isn't available from Google Play for this version of the app yet. If the " +
            "app was just updated, Play may still be preparing it — try again in a few hours. If " +
            "it's still unavailable after a day, update the app from Play, " +
            "or use 'Import model pair…' below."

    /** [emptyDeliveryRefusal] for a device-targeted pair — names the import. */
    private const val EMPTY_TARGETED: String =
        "Google Play delivered no model for this device — it is not in any device group this " +
            "app publishes a pack for, so the pack arrived empty. Use 'Import model pair…' " +
            "below instead. Nothing was installed."

    /** [emptyDeliveryRefusal] for an untargeted pair — names the import. */
    private const val EMPTY_UNTARGETED: String =
        "Google Play delivered no model for this device — the model's pack was not delivered. " +
            "Retry the download, or use 'Import model pair…' below instead. Nothing was installed."

    /**
     * EVERY SENTENCE THE MACHINE WRITES THAT NAMES THE IMPORT, and the same sentence for a device
     * that is offered no import route (the P3a review, small 1). The machine's reasons name
     * "'Import model pair…' below" because on the Settings picker that control IS below — the F5
     * carrier rule — and a Qualcomm row keeps them verbatim. A MediaTek row is offered no import at
     * all (`NpuAssetImport.panelOfferedOn`: no zip is published for its pair — Play only, until
     * the owner's NeuroPilot Express ruling), so there the same failure is worded without it:
     * the fact, and the one path forward that exists. Keyed on the exact sentence, so the twin can
     * never drift from its original; `NpuPackFetchTest` holds that no reason any builder produces
     * still names the import once it is worded for such a device.
     */
    private val WITHOUT_IMPORT_ROUTE: Map<String, String> = mapOf(
        SIDELOAD_ANSWER to
            "Google Play can't deliver the model to this install — it wasn't installed from Play, " +
            "and on this device the model comes from Google Play only.",
        APP_UNAVAILABLE_ANSWER to
            "Google Play says this app is currently unavailable, so it can't deliver the " +
            "model right now. Try again later.",
        PACK_UNAVAILABLE_ANSWER to
            "That model pack isn't available from Google Play for this version of the app yet. If " +
            "the app was just updated, Play may still be preparing it — try again in a few hours. " +
            "If it's still unavailable after a day, update the app from Play.",
        EMPTY_TARGETED to
            "Google Play delivered no model for this device — it is not in any device group this " +
            "app publishes a pack for, so the pack arrived empty. Nothing was installed.",
        EMPTY_UNTARGETED to
            "Google Play delivered no model for this device — the model's pack was not delivered. " +
            "Retry the download. Nothing was installed.",
    )

    /**
     * [reason] as THIS device should read it (the P3a review, small 1): verbatim where the device
     * is offered the import route ([importRoute] — `NpuAssetImport.panelOfferedOn` for its
     * family), and without the import where it is not. The pack controller publishes every
     * refusal through this, so every surface that renders the fetch's state — the Settings
     * picker's card, the onboarding flow's engine row — reads the device's truth.
     */
    fun reasonFor(reason: String, importRoute: Boolean): String =
        if (importRoute) reason else WITHOUT_IMPORT_ROUTE[reason] ?: reason

    /**
     * Every error code in user words with the honest next action. Unknown codes render
     * `"Google Play reported error <n>"` — never silence; `NpuPackFetchTest` enumerates the
     * library's own class and holds every DECLARED code to real words, so the numbered fallback
     * is reserved for codes this build has genuinely never heard of.
     *
     * @param pairBytes the download's size as Play reported it (`totalBytesToDownload`), used by
     *        the storage refusal to name a real number — 0 when Play never said, in which case
     *        no number is invented.
     */
    fun failureReason(errorCode: Int, pairBytes: Long = 0L): String = when (errorCode) {
        ERROR_NO_ERROR ->
            "Google Play reported a failure without naming a reason. Retry the download."
        ERROR_APP_UNAVAILABLE -> APP_UNAVAILABLE_ANSWER
        ERROR_PACK_UNAVAILABLE -> PACK_UNAVAILABLE_ANSWER
        ERROR_INVALID_REQUEST ->
            "Google Play rejected the download request as invalid. Restart the app and retry."
        ERROR_DOWNLOAD_NOT_FOUND ->
            "Google Play lost track of this download. Retry it."
        ERROR_API_NOT_AVAILABLE -> SIDELOAD_ANSWER
        ERROR_NETWORK_ERROR ->
            "The download couldn't reach Google Play. Check your connection and retry."
        ERROR_ACCESS_DENIED ->
            "Google Play refused this app access to the download. Check that the Play Store " +
                "is signed in, then retry."
        ERROR_INSUFFICIENT_STORAGE ->
            if (pairBytes > 0L) {
                "Not enough free storage to fetch the model pair: the download is about " +
                    "${mb(pairBytes)} MB. Free some space and retry."
            } else {
                "Not enough free storage to fetch the model pair. Free some space and retry."
            }
        ERROR_PLAY_STORE_NOT_FOUND -> SIDELOAD_ANSWER
        ERROR_APP_NOT_OWNED -> SIDELOAD_ANSWER
        ERROR_CONFIRMATION_NOT_REQUIRED ->
            "Google Play answered that no confirmation was needed. Retry the download."
        ERROR_UNRECOGNIZED_INSTALLATION -> SIDELOAD_ANSWER
        ERROR_INTERNAL_ERROR ->
            "Google Play hit an internal error while delivering the model pack. Retry the " +
                "download."
        else -> "Google Play reported error $errorCode"
    }

    /**
     * The `pack:` line throttle, as a pure decision: one line per status TRANSITION (the
     * shell's rule) plus at most one per 10% of progress — this function answers the second
     * half. A per-tick line would bury the run-book's landmarks under ~200 lines per fetch.
     *
     * @param lastLoggedPct the percentage the last progress line carried, or negative when no
     *        progress line has printed yet (the first tick is a landmark and always logs).
     */
    fun shouldLogProgress(lastLoggedPct: Int, pct: Int): Boolean {
        val lastDecile = if (lastLoggedPct < 0) -1 else lastLoggedPct / 10
        return pct / 10 > lastDecile
    }

    /** Whole percent of [soFar] over [total] — total-safe (an unknown total is 0%) and clamped
     *  to 100, because a resumed fetch's bookkeeping can briefly overshoot. */
    fun pct(soFar: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((soFar * 100L) / total).toInt().coerceIn(0, 100)
    }

    /** The `pack:` line's status field — one lowercase greppable token per state, forever. */
    fun statusWord(state: FetchState): String = when (state) {
        is FetchState.Idle -> "idle"
        is FetchState.Pending -> "pending"
        is FetchState.Downloading -> "downloading"
        is FetchState.Transferring -> "transferring"
        is FetchState.Verifying -> "verifying"
        is FetchState.Installed -> "installed"
        is FetchState.Failed -> "failed"
        is FetchState.Cancelled -> "cancelled"
        is FetchState.NeedsConfirmation -> "needs-confirmation"
    }

    /**
     * Is the pair [parts] ship in DEVICE-TARGETED — the `#group_<g>` variant of a tier's shared
     * module ([PACK_BY_TIER]'s `npu_small` / `npu_turbo`), which Play resolves per device group —
     * rather than in UNTARGETED modules of its own family (the MediaTek rows since P2-5, which carry
     * no `#group_` folder and are the same bytes for every device that fetches them)? The fact
     * [emptyDeliveryRefusal]'s sentence turns on.
     */
    fun isDeviceTargeted(parts: List<PackPart>): Boolean =
        parts.isNotEmpty() && parts.all { it.packName in PACK_BY_TIER.values }

    /**
     * The refusal for a pair that arrived EMPTY, worded by how its packs are delivered ([parts],
     * the pair's own — the P2b review's small 3):
     *
     *  - **device-targeted** (a Qualcomm pair): the F4 default variant, which is what a device
     *    outside every census group receives. A missing `metadata.json` in a delivered pack IS
     *    that signature — our build writes it as the first file of every real variant — so the
     *    refusal states Play's answer, not corruption, not a mystery;
     *  - **untargeted** (a MediaTek pair): there is no default variant and no group to be outside
     *    of — the module carries its payload for every device — so "not in any device group" would
     *    be false. What is true is that the pack was not delivered (Play gave a part no location,
     *    or part 1 arrived without its `metadata.json`), and a retry is the first thing to try.
     *
     * Both name the import fallback as the path forward, and both say nothing was installed. (On
     * a device offered no import route the controller words them without it — [reasonFor].)
     */
    fun emptyDeliveryRefusal(parts: List<PackPart>): String =
        if (isDeviceTargeted(parts)) EMPTY_TARGETED else EMPTY_UNTARGETED

    private fun mb(bytes: Long): Long = bytes / 1_000_000
}
