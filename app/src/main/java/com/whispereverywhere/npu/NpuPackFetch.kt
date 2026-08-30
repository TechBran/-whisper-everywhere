package com.whispereverywhere.npu

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
 */
object NpuPackFetch {

    /**
     * Which committed pack module serves which paired tier — spelled through the tier ids' own
     * HOMES (the npu constant, the turbo spec) so the map cannot drift from the catalog side,
     * and pinned against F4's `packName.set(…)` facts from the module side.
     */
    val PACK_BY_TIER: Map<String, String> = mapOf(
        NpuAssetImport.TIER_ID to "npu_small",
        NpuModelSpec.TURBO.tierId to "npu_turbo",
    )

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

    /** `AssetPackErrorCode.PACK_UNAVAILABLE` — this app version doesn't declare the pack. */
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
     * The sideload answer — the one failure the primary test device will actually show, so it
     * gets exact copy: the truth stated as the PATH FORWARD (the SAF import), never a dead end.
     */
    private const val SIDELOAD_ANSWER: String =
        "Google Play can't deliver the model to this install — it wasn't installed from Play. " +
            "Use 'Import model pair…' below instead."

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
        ERROR_APP_UNAVAILABLE ->
            "Google Play says this app is currently unavailable, so it can't deliver the " +
                "model right now. Try again later, or use 'Import model pair…' below."
        ERROR_PACK_UNAVAILABLE ->
            "This version of the app doesn't offer that model pack on Google Play. Update " +
                "the app from Play, or use 'Import model pair…' below."
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
     * The refusal for a pack that arrived EMPTY — the F4 default variant, which is what a
     * device outside every census group receives. A missing `metadata.json` in a delivered
     * pack IS that signature: our build writes it as the first file of every real variant, so
     * its absence means Play resolved this device to the empty default, and the refusal states
     * Play's answer — not corruption, not a mystery — with the import fallback named as the
     * path forward.
     */
    fun emptyDeliveryRefusal(): String =
        "Google Play delivered no model for this device — it is not in any device group this " +
            "app publishes a pack for, so the pack arrived empty. Use 'Import model pair…' " +
            "below instead. Nothing was installed."

    private fun mb(bytes: Long): Long = bytes / 1_000_000
}
