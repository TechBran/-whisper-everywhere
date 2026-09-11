package com.whispereverywhere.transcription.stream

import com.whispereverywhere.npu.NpuPackFetch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * WHO caused a transfer — the one distinction the copy cannot derive from anything else.
 *
 * The asymmetry the 2026-09-11 acquisition rulings turn on: an UNASKED top-up spends the user's
 * data without being asked and must therefore behave differently from a transfer the user caused
 * by picking a language, where the pick IS the consent. A surface that cannot tell the two apart
 * cannot state that deal honestly.
 */
enum class PreviewStarter {
    /** The foreground hook's silent fetch — nobody asked for it. */
    TOP_UP,

    /** The user's own pick or tap: a Settings row, an offer card's action, a language selection. */
    PICK,
}

/**
 * WHICH of the three arrival routes is carrying the bytes, and — the part this type exists for —
 * whether a cancel on that route actually stops them.
 *
 * ### THE CANCEL CONTRACT, ROUTE BY ROUTE (4.5.0 Task 1)
 *
 * The brief's rule is *"cancelling stops the transfer on EVERY route, DownloadManager row
 * included — or the UI must not offer a cancel on the route where it cannot"*. [
 * stopsBeforeTheCopy] is that table, and the two halves of the rule are both discharged here:
 *
 *  - **[PLAY_FETCH] — STOPS, while the bytes are still MOVING.** `StreamingPackController
 *    .cancel()` asks Play to cancel the pack download through `AssetPackManager.cancel`, LATCHES
 *    the pack as abandoned so a delivery that lands anyway neither narrates nor installs, and
 *    publishes `Cancelled`. Nothing of ours is installed; a pack Play has ALREADY delivered stays
 *    delivered, so a later install costs no transfer. **It stops nothing at
 *    [PreviewPhase.TRANSFERRING]** — see the phase term below.
 *  - **[DIRECT_DOWNLOAD] — STOPS, as of 4.5.0.** The poll loop's `delay` is a suspension point,
 *    so the cancel is seen within one poll, AND `StreamingPackManager.fetchOne` now removes the
 *    `DownloadManager` row on every exit. 4.4.1 set `keepRow = true` on a `CancellationException`
 *    and left the row transferring the rest of the 73 MB, while `delete()`'s
 *    `removeStaleDownloads` removed the same row — so the app both stopped and did not stop one
 *    transfer, depending on which control the user found (review r1's H1). The staging dir goes
 *    with it, so no partial bytes are left behind either.
 *  - **[DELIVERED_PACK] — NOTHING TO STOP, and the UI offers nothing.** Play has already put
 *    those bytes on the device; the route's only phase is a local verify + copy that touches no
 *    network and spends none of the user's data. It is also not cancellation-cooperative (see
 *    [PreviewPhase.INSTALLING]), so the install lands. There is no transfer to cancel, which is
 *    why the honest answer is to offer no cancel rather than to publish a `Cancelled` that
 *    describes nothing.
 *
 * ### TWO PHASE TERMS CROSS EVERY ROW OF IT (fix round 1, review r1's B1a)
 *
 * A route can only stop bytes that are still moving, so the table is a conjunction and not an
 * answer on its own — [PreviewWork.cancellable] is the conjunction, and it is false for EVERY
 * route at two phases:
 *
 *  - **[PreviewPhase.TRANSFERRING]** — Play's `STATUS_TRANSFERRING`, AFTER the download and
 *    BEFORE `COMPLETED`, a phase every asset-pack delivery passes through. The bytes are already
 *    on the device and Play is moving them into the app's pack storage, so `AssetPackManager
 *    .cancel` has no download left to cancel. That is word for word the fact [DELIVERED_PACK]
 *    gives as its reason: *"Play has already put those bytes on the device"*. Until 4.5.0's fix
 *    round the table answered the SAME fact `false` on one route and `true` on another.
 *  - **[PreviewPhase.INSTALLING]** — our own verify + copy, which has no suspension point left.
 *
 * A fourth route must answer this table before it can be observed, which is the whole point of a
 * total `when` here rather than a Boolean on the record.
 */
enum class PreviewRoute {
    /** Ask Google Play for the on-demand pack — the ordinary store path. */
    PLAY_FETCH,

    /** Play already delivered it: verify + copy into `filesDir`, no network at any point. */
    DELIVERED_PACK,

    /** The commit-pinned Hugging Face base — a debug build, a sideload, or a refusal Play named. */
    DIRECT_DOWNLOAD;

    /**
     * Whether a cancel can stop this route's bytes AT ALL, while they are still moving. WHEN they
     * are still moving is the phase term's answer, not this one — see the class KDoc's two
     * crossing phases, and [PreviewWork.cancellable] for the conjunction that is the whole table.
     */
    val stopsBeforeTheCopy: Boolean
        get() = when (this) {
            PLAY_FETCH -> true
            DIRECT_DOWNLOAD -> true
            DELIVERED_PACK -> false
        }

    companion object {
        /**
         * The route [state]'s one install action would take, or null for an
         * [StreamingPackState.Installed] pack — which is on no route, because nothing is arriving.
         *
         * The reduction is [StreamingPackInstall.sourceOf]'s and is NOT re-derived: a repair takes
         * the route a first install would have taken, and a second `when` over the state machine
         * is how two surfaces come to disagree about which source they are describing.
         */
        fun of(state: StreamingPackState): PreviewRoute? =
            when (StreamingPackInstall.sourceOf(state)) {
                StreamingPackState.PackDelivered -> DELIVERED_PACK
                StreamingPackState.PackFetchable -> PLAY_FETCH
                StreamingPackState.Downloadable -> DIRECT_DOWNLOAD
                StreamingPackState.Installed -> null
                // sourceOf strips every Repair wrapper recursively, so this arm is unreachable;
                // spelled so a state added to the machine is answered here rather than mapped to
                // whichever route happens to be listed first.
                is StreamingPackState.Repair -> null
            }
    }
}

/**
 * WHAT the work is doing right now — the union of Play's own fetch phases and ours, so both
 * surfaces read ONE vocabulary rather than each mapping its own half.
 */
enum class PreviewPhase {
    /** Queued with Google Play; no bytes have moved. */
    ASKING,

    /** Play is holding its OWN dialog (a size or cellular confirmation, or a wait for wifi).
     *  No byte has moved yet, which is exactly why the row may ask for a tap here. */
    AWAITING_ANSWER,

    /** Bytes are moving — Play's pack fetch, or the fallback download. [PreviewWork.soFar] of
     *  [PreviewWork.total]. */
    DOWNLOADING,

    /**
     * Play is moving the delivered pack into the app's own pack storage — its
     * `STATUS_TRANSFERRING`, AFTER the download and BEFORE `COMPLETED`. Brief, but real, and a
     * phase every delivery passes through.
     *
     * THE SECOND PHASE NO ROUTE CAN CANCEL (fix round 1, review r1's B1a). The bytes are already
     * on the device, so there is no download left for `AssetPackManager.cancel` to stop — the
     * same fact [PreviewRoute.DELIVERED_PACK] is built on, arriving here on another route.
     * [PreviewWork.cancellable] is therefore false for every route while it lasts.
     */
    TRANSFERRING,

    /**
     * OUR verify + copy into `filesDir` — a sha256 of 72,654,782 B and a copy, whichever source
     * the bytes came from.
     *
     * THE ONE PHASE NO ROUTE CAN CANCEL. `StreamingPackManager.installFromPack` has no suspension
     * point between `withContext(Dispatchers.IO)`'s entry and its return (`StreamingPackInstall
     * .verify` and `install` are blocking and `onProgress` is a plain lambda), so a cancel during
     * it lets the copy finish and the marker land. Stated rather than papered over: the UI must
     * not offer a cancel here, and [PreviewWork.cancellable] is false for every route while it runs.
     */
    INSTALLING,

    /** Terminal: the marker landed and the recognizer can open the install. */
    INSTALLED,

    /** Terminal: [PreviewWork.reason] is the feature's own re-told refusal, rendered verbatim. */
    FAILED,

    /** Terminal: the user stopped it, or Play no longer holds a fetch for this pack. */
    CANCELLED;

    /** Whether work is RUNNING. Total by construction — an unclassified phase would read as
     *  running forever, which is the state that hides the offer and the delete together. */
    val inFlight: Boolean
        get() = when (this) {
            ASKING, AWAITING_ANSWER, DOWNLOADING, TRANSFERRING, INSTALLING -> true
            INSTALLED, FAILED, CANCELLED -> false
        }
}

/**
 * One reading of a pack's progress: the phase, the bytes, and — on a failure only — the sentence
 * the feature has already chosen for it.
 *
 * It is a separate type from [PreviewWork] so that a narration site can hand over everything that
 * CHANGES while the things that do not (the language, the route, who started it) are carried by
 * the record itself and cannot be rewritten by a progress tick.
 */
data class PreviewStep(
    val phase: PreviewPhase,
    val soFar: Long = 0L,
    val total: Long = 0L,
    val reason: String? = null,
) {
    companion object {
        /**
         * The Play fetch shell's own state machine, as a step of this board — TOTAL over
         * `NpuPackFetch.FetchState`, so the shell interprets no status of its own and a state
         * added to that machine must be answered here.
         *
         * `NpuPackFetch.FetchState.Idle` becomes [PreviewPhase.CANCELLED]: Play reports
         * NOT_INSTALLED for a pack we are actively fetching only once it no longer holds a fetch
         * for it — nothing installed, no reason named — which is the same outcome as its own
         * CANCELED status, and in practice the way a cancel arrives.
         *
         * `Verifying` becomes [PreviewPhase.INSTALLING] and not a phase of Play's: COMPLETED
         * means DELIVERED, `advance` maps it to `Verifying`, and what follows is OUR copy.
         */
        fun of(state: NpuPackFetch.FetchState): PreviewStep = when (state) {
            is NpuPackFetch.FetchState.Idle -> PreviewStep(PreviewPhase.CANCELLED)
            is NpuPackFetch.FetchState.Pending -> PreviewStep(PreviewPhase.ASKING)
            is NpuPackFetch.FetchState.Downloading ->
                PreviewStep(PreviewPhase.DOWNLOADING, state.soFar, state.total)
            is NpuPackFetch.FetchState.Transferring -> PreviewStep(PreviewPhase.TRANSFERRING)
            is NpuPackFetch.FetchState.Verifying ->
                PreviewStep(PreviewPhase.INSTALLING, state.soFar, state.total)
            is NpuPackFetch.FetchState.Installed -> PreviewStep(PreviewPhase.INSTALLED)
            is NpuPackFetch.FetchState.Failed ->
                PreviewStep(PreviewPhase.FAILED, reason = state.reason)
            is NpuPackFetch.FetchState.Cancelled -> PreviewStep(PreviewPhase.CANCELLED)
            is NpuPackFetch.FetchState.NeedsConfirmation ->
                PreviewStep(PreviewPhase.AWAITING_ANSWER)
        }
    }
}

/**
 * THE ONE OBSERVABLE'S record: everything the previewer's two surfaces are allowed to know about
 * one pack's arrival — *which pack, what phase, how many bytes of how many, who started it, and
 * is it cancellable*.
 *
 * Before 4.5.0 that question was answered from TWO composition-local values — the Settings row's
 * own `previewInstallStatus` and `StreamingPackController.state` — while THREE starters could
 * begin a 73 MB transfer (that row, and `PreviewAutoFetchController`'s two routes). Home
 * collected one of them and Settings collected the other, and every blocker of review rounds 1-3
 * was a different consequence of that one gap: a second 73 MB started over work already running,
 * a delete row drawn over a live write, and an X that stopped the transfer on two routes out of
 * three. There is now one record per pack and both surfaces read it.
 */
data class PreviewWork(
    val language: String,
    val route: PreviewRoute,
    val starter: PreviewStarter,
    val step: PreviewStep,
) {
    val phase: PreviewPhase get() = step.phase
    val soFar: Long get() = step.soFar
    val total: Long get() = step.total
    val reason: String? get() = step.reason

    /** Work is running. What the single-flight guard, the card and the delete row all ask. */
    val inFlight: Boolean get() = phase.inFlight

    /**
     * Whether a cancel would actually stop THIS work — the conjunction of the two facts that
     * decide it, and the only answer any surface may offer a cancel from.
     *
     * The phase term comes first because it is the one that crosses every route — TWO phases do
     * (fix round 1, review r1's B1a), for the one reason: **a route can only stop bytes that are
     * still MOVING.** At [PreviewPhase.TRANSFERRING] the download is over and Play is moving the
     * delivered pack into its own storage, so `AssetPackManager.cancel` has no download left to
     * cancel — [PreviewRoute.DELIVERED_PACK]'s own fact, arriving on another route. At
     * [PreviewPhase.INSTALLING] our verify + copy has no suspension point left and it lands. The
     * route term is [PreviewRoute.stopsBeforeTheCopy], whose KDoc is the table.
     */
    val cancellable: Boolean
        get() = when (phase) {
            PreviewPhase.ASKING,
            PreviewPhase.AWAITING_ANSWER,
            PreviewPhase.DOWNLOADING,
            -> route.stopsBeforeTheCopy
            PreviewPhase.TRANSFERRING, PreviewPhase.INSTALLING -> false
            PreviewPhase.INSTALLED, PreviewPhase.FAILED, PreviewPhase.CANCELLED -> false
        }

    /** The same arrival, one step on. The language, the route and the starter are immutable by
     *  construction: a progress tick must not be able to rewrite whose work this is. */
    fun at(step: PreviewStep): PreviewWork = copy(step = step)
}

/**
 * WHAT THE DELETE ROW IS ACTUALLY LOOKING AT. Four facts, and `StreamingPackCopy.deleteSubtitle`
 * has a true sentence for each — because 4.4.1 rendered ONE sentence (*"Frees 73 MB. Live words
 * stop; the typed transcript is unchanged."*) across all four, and it is the largest untrue
 * sentence the feature renders.
 */
enum class PreviewDeleteCase {
    /** Installed, and it is the PICKED language's pack: live words really are showing. */
    LIVE,

    /** Installed for a language the user is not transcribing — they picked another one, or Auto.
     *  Deleting frees the bytes, but "live words stop" is false: they are already not showing. */
    OFF_SELECTION,

    /** `StreamingPackState.Repair` — `markCorrupt` removed the marker and LEFT the bytes, so up
     *  to 73 MB is on disk with live words already off. */
    DAMAGED,

    /** A fetch or install of this pack is in flight: nothing can be freed while it is being
     *  written, and a delete would race the copy. */
    WORKING;

    companion object {
        /**
         * The row's case, or null where there is no delete row at all.
         *
         * The order is the order the facts outrank each other:
         *
         *  1. **No bytes, no row.** `isInstalled` is `this is Installed` only, and a `Repair` is
         *     the other state with bytes under `filesDir`. A first install in flight has nothing
         *     to free, so it gets no row — the progress row above it is the one that speaks.
         *  2. **A WRITE outranks everything** (review r3's H3-B1). `previewState` is remembered on
         *     keys our own install does not change, so through a repair install it stays `Repair`
         *     and the row used to draw beside the running copy — where `delete` clears the install
         *     dir under a verify + copy that is not cancellation-cooperative, so the copy lands
         *     anyway and the pack ends up installed AND declined. The work answer is read from the
         *     board, which sees all three starters; 4.4.1's guard read one composable's own `var`.
         *  3. **Damaged before selected.** A damaged install's live words are off for everyone.
         *  4. Then the selection decides whether deleting stops anything.
         *
         * @param selectedForThisPack the user's picked language is the one this pack serves —
         *        `StreamingPackCatalog.forLanguage(selected) == pack`. NOT a tier or cloud-only
         *        term: whether a device can arm at all is Task 4's axis and deliberately absent here.
         */
        fun of(
            state: StreamingPackState,
            selectedForThisPack: Boolean,
            work: PreviewWork?,
        ): PreviewDeleteCase? {
            val bytesOnDisk = state.isInstalled || state is StreamingPackState.Repair
            if (!bytesOnDisk) return null
            if (work?.inFlight == true) return WORKING
            if (state is StreamingPackState.Repair) return DAMAGED
            return if (selectedForThisPack) LIVE else OFF_SELECTION
        }
    }
}

/**
 * THE ONE OBSERVABLE. A [PreviewWork] per LANGUAGE, written by whoever started the work and read
 * by both of the previewer's surfaces — and by nothing else, so neither surface derives policy
 * from a second value.
 *
 * ### Why a MAP and not one job and one line
 *
 * This retires pass 2's parked *"one job, one progress line"* contract. The next build has N
 * packs, and two languages must be able to arrive without either becoming invisible: with one
 * `line` the second arrival overwrote the first's sentence, and with one `StateFlow` of Play's
 * machine a Settings row could not tell whose transfer it was narrating.
 *
 * **The ACTUATOR is still single-flight across all languages, and that is a separate fact.**
 * `PreviewAutoFetchController.busy()` refuses a second start while any pack is arriving, because
 * `StreamingPackController` holds one `AssetPackManager`, one `activePackName` and one listener
 * filter — two concurrent 73 MB Play fetches would be a decision, not a consequence of this map.
 * The map makes both arrivals OBSERVABLE; letting both RUN is a controller ruling this build does
 * not take.
 *
 * **The back-off stamp stays GLOBAL and deliberately** (`PreferencesManager
 * .livePreviewAutoFetchFailedAt`, read by `PreviewAutoFetch.backedOff`). It self-expires after a
 * day, so re-keying it by language would need no migration — and is still not done, because the
 * loop it closes is *"a user reopening the app on a bad connection all afternoon"*, which is a
 * property of the connection and not of the language. Said here in one line so the next reader
 * does not re-key it for symmetry with this map and call it a fix.
 *
 * Process-scoped like the two controllers that write it: a 73 MB transfer must outlive the Compose
 * tree that started it.
 */
object PreviewWorkboard {

    private val _work = MutableStateFlow<Map<String, PreviewWork>>(emptyMap())

    /** Every pack the feature is currently doing something about, keyed by language code. */
    val work: StateFlow<Map<String, PreviewWork>> = _work.asStateFlow()

    fun of(language: String?): PreviewWork? = if (language == null) null else _work.value[language]

    fun inFlight(language: String?): Boolean = of(language)?.inFlight == true

    /** Whether ANY pack is arriving — the single-flight question, while the actuator is one. */
    fun anyInFlight(): Boolean = _work.value.values.any { it.inFlight }

    /**
     * Record that [language]'s pack has started arriving by [route], because of [starter], at
     * [step]. Called by the STARTER, before the first byte, so no surface can see a phase for
     * work that has no owner.
     */
    fun begin(
        language: String,
        route: PreviewRoute,
        starter: PreviewStarter,
        step: PreviewStep,
    ) {
        _work.update { it + (language to PreviewWork(language, route, starter, step)) }
    }

    /**
     * Narrate an existing arrival's next [step]. A step for a language nobody [begin]-ned writes
     * NOTHING: the route and the starter are facts only the starter knows, and inventing them
     * here would put a row on screen with no owner and no cancel contract.
     */
    fun note(language: String, step: PreviewStep) {
        _work.update { current ->
            val existing = current[language] ?: return@update current
            current + (language to existing.at(step))
        }
    }

    /** Drop one language's record. A TERMINAL entry is deliberately kept until then — the row
     *  that shows a refusal is rendered from this board, and a Toast the user walked away from
     *  shows nothing. */
    fun forget(language: String) {
        _work.update { it - language }
    }

    /** Test-only: the board is process-scoped, so a JVM test must be able to start from empty. */
    fun forgetAll() {
        _work.value = emptyMap()
    }
}
