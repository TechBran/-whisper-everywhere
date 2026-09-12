package com.whispereverywhere.transcription.stream

import com.whispereverywhere.npu.NpuPackFetch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * WHY an arrival is being started — the CAUSE, as the one call site that knows it spells it, and
 * the input the unasked path's two cautions and the once-per-launch latch branch on (Task 3b).
 *
 * Three values rather than [PreviewStarter]'s two, because the two questions they answer split
 * differently and a boolean cannot carry both:
 *
 * | | [starter] — the unasked path's two cautions? | [latchedForTheLaunch] — once per launch? |
 * |---|---|---|
 * | [TOP_UP] | yes: nobody asked for this one | yes |
 * | [SELECTION] | **no: the user just made this gesture** | **yes** |
 * | [TAP] | no: the user just made this gesture | no: consent may be repeated |
 *
 * The two cautions are a wait for a network that WORKS and the 24 h back-off after a failure:
 * both exist so a transfer nobody asked for cannot fail and then park the pack for a day.
 *
 * **Neither column is about METERING** (4.5.0 pass 2, Fix 1). The [starter] column used to read
 * *"may it spend a metered connection?"*, and the owner deleted that question from the feature:
 * *"Yes. I wanted to silently download on cellular and Wi Fi."* `PreviewAutoFetch`'s KDoc is that
 * ruling's home.
 *
 * [SELECTION] is the row the 4.4.1 `auto: Boolean` could not express: it skips the unasked path's
 * two cautions like a tap does, and is latched like a top-up is — because a pick that FAILED must
 * not retry itself on the next recomposition, which a latch-exempt selection would (`decision`
 * returns to FETCH the instant `busy()` goes false, and the effect is keyed on `decision`).
 */
enum class PreviewTrigger {
    /** The foreground hook's unasked look at a pack the user has not touched. */
    TOP_UP,

    /** The user picked a language — the in-app dropdown, or onboarding's Continue. */
    SELECTION,

    /** The user tapped an offer: Home's card action, or the Settings row. */
    TAP;

    /** WHO caused it, as the board records it. Three causes, two starters. */
    val starter: PreviewStarter
        get() = when (this) {
            TOP_UP -> PreviewStarter.TOP_UP
            SELECTION, TAP -> PreviewStarter.PICK
        }

    /**
     * Whether this attempt spends the once-per-launch latch
     * (`PreviewAutoFetchController.attemptedThisLaunch`).
     *
     * True for everything the app starts on its own initiative — the top-up AND the selection —
     * because both are re-decided by a recomposition and would otherwise loop on a failure. False
     * for a [TAP], which happens only because a finger moved: *"a tap is consent and may be
     * repeated"*, and a latched tap would answer a user's retry with the same offer card they
     * just pressed.
     */
    val latchedForTheLaunch: Boolean
        get() = when (this) {
            TOP_UP, SELECTION -> true
            TAP -> false
        }
}

/**
 * WHO caused a transfer — the one distinction the copy cannot derive from anything else.
 *
 * An UNASKED top-up is one nobody asked for, so the two cautions that protect it (a wait for a
 * working network, and the 24 h back-off) apply to it and not to a gesture the user just made.
 * The surfaces need the distinction for a second reason and it is the durable one: a progress line
 * over a transfer the user started reads differently from one over a transfer they did not.
 *
 * It is NOT a spending distinction. The 2026-09-11 acquisition rulings once divided the two on a
 * metered connection; the owner settled that the other way (*"Yes. I wanted to silently download
 * on cellular and Wi Fi"*) and `PreviewAutoFetch`'s KDoc is that ruling's home.
 *
 * Derived from [PreviewTrigger], never written at a call site: the cause is what a caller knows,
 * and the mapping from three causes to two starters is a rule with one home.
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
 *    transfer, depending on which control the user found (review r1's H1). **The bytes are swept
 *    by `download`'s own `staging.deleteRecursively()` and not by that row removal** (pass 2's
 *    Fix 2): one sweep takes the `.part` in flight and every file that had already landed, which
 *    is more than a row could ever have reached.
 *  - **[DELIVERED_PACK] — NOTHING TO STOP, and the UI offers nothing.** Play has already put
 *    those bytes on the device; the route's only phase is a local verify + copy that touches no
 *    network and spends none of the user's data. It is also not cancellation-cooperative (see
 *    [PreviewPhase.INSTALLING]), so the install lands. There is no transfer to cancel, which is
 *    why the honest answer is to offer no cancel rather than to publish a `Cancelled` that
 *    describes nothing.
 *
 * ### THE PHASE TERM CROSSES EVERY ROW OF IT (fix round 1, review r1's B1a)
 *
 * **A route can only abandon an arrival that has not yet delivered its bytes.** That is the one
 * rule, and it is NOT *"the bytes are still moving"* — which is what this section said until fix
 * round 2 and is false on two of the five phases (review r2's nit 1): at [PreviewPhase.ASKING]
 * and [PreviewPhase.AWAITING_ANSWER] no byte has moved yet, and a cancel there IS honoured,
 * because Play still holds a fetch it can drop. Stating the criterion wrongly is how a table
 * comes to answer one fact two ways, so it is stated once here and the grid
 * (`PreviewWorkTest.onlyTheArrivalsNotYetDeliveredAreEverCancellableAnywhere`) is its teeth.
 *
 * The table is therefore a conjunction and not an answer on its own — [PreviewWork.cancellable]
 * is the conjunction, and it is false for EVERY route at three phases:
 *
 *  - **[PreviewPhase.TRANSFERRING]** — Play's `STATUS_TRANSFERRING`, AFTER the download and
 *    BEFORE `COMPLETED`, a phase every asset-pack delivery passes through. The bytes are already
 *    on the device and Play is moving them into the app's pack storage, so `AssetPackManager
 *    .cancel` has no download left to cancel. That is word for word the fact [DELIVERED_PACK]
 *    gives as its reason: *"Play has already put those bytes on the device"*. Until 4.5.0's fix
 *    round the table answered the SAME fact `false` on one route and `true` on another.
 *  - **[PreviewPhase.INSTALLING]** — our own verify + copy, which has no suspension point left.
 *  - **[PreviewPhase.ABANDONED]** — the cancel has already been accepted and the store has not
 *    answered it yet, so there is nothing left for a second press to ask for.
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

    /**
     * THE USER'S "NO", BEFORE THE STORE HAS ANSWERED IT (4.5.0 Task 1, fix round 2 — review r2's
     * B1a). A cancel has been accepted and Google Play has been asked to drop the download;
     * until Play says it has, this pack's arrival is neither running nor over.
     *
     * **It is a phase of the ONE OBSERVABLE rather than a field of the fetch shell, and that is
     * the whole of this task.** Fix round 1 recorded this same fact in a private
     * `StreamingPackController.abandonedPackName` AND published a terminal `CANCELLED` onto the
     * board, so *"is work running?"* had two answers again: the actuator read the field (through
     * `isBusy()`) and both surfaces read the board. Settings therefore fell through its
     * *"something is in flight"* arm into its OFFER row and drew *"Get the English preview model
     * — 73 MB"* with a live tap that `PreviewAutoFetchController.start` then refused in silence.
     * Now `isBusy()` reads THIS phase, so the actuator and the surfaces cannot disagree.
     *
     * [inFlight] is TRUE: Play may still be delivering, and no second transfer may be started
     * over a delivery it has not finished. [PreviewWork.cancellable] and
     * [PreviewWork.dismissable] are FALSE: the abandon has already been asked for.
     * [PreviewWork.writeCanStillLand] is FALSE too, and it is the one phase where that differs
     * from [inFlight] — nothing of OURS can be written from here, because the install that
     * follows a delivery is cancelled and a delivery that lands anyway is suppressed.
     *
     * Written by exactly one gesture (the shell's `cancel`) and never by [PreviewStep.of]: no
     * status Play reports means *"the user changed their mind"*.
     */
    ABANDONED,

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
            // The user's no, not yet confirmed by Play: work is not over, because Play may still
            // be delivering and a second transfer must not be started over it.
            ABANDONED -> true
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
     * The phase term comes first because it is the one that crosses every route — THREE phases
     * do — for the one rule: **a route can only abandon an arrival that has not yet delivered
     * its bytes.** At [PreviewPhase.TRANSFERRING] the download is over and Play is moving the
     * delivered pack into its own storage, so `AssetPackManager.cancel` has no download left to
     * cancel — [PreviewRoute.DELIVERED_PACK]'s own fact, arriving on another route. At
     * [PreviewPhase.INSTALLING] our verify + copy has no suspension point left and it lands. At
     * [PreviewPhase.ABANDONED] the cancel has already happened. The route term is
     * [PreviewRoute.stopsBeforeTheCopy], whose KDoc is the table.
     *
     * The rule is deliberately NOT *"the bytes are still moving"*: [PreviewPhase.ASKING] and
     * [PreviewPhase.AWAITING_ANSWER] have moved no bytes at all and are cancellable, because
     * Play still holds a fetch it can drop (review r2's nit 1).
     */
    val cancellable: Boolean
        get() = when (phase) {
            PreviewPhase.ASKING,
            PreviewPhase.AWAITING_ANSWER,
            PreviewPhase.DOWNLOADING,
            -> route.stopsBeforeTheCopy
            PreviewPhase.TRANSFERRING, PreviewPhase.INSTALLING -> false
            PreviewPhase.ABANDONED -> false
            PreviewPhase.INSTALLED, PreviewPhase.FAILED, PreviewPhase.CANCELLED -> false
        }

    /**
     * WHETHER A SURFACE MAY OFFER THE GESTURE THAT ABANDONS THIS WORK — the X on Home's card
     * (4.5.0 Task 1, fix round 2 — review r2's B1c).
     *
     * That X is ONE gesture with TWO halves: it writes the PERMANENT no
     * (`PreferencesManager.setLivePreviewDeclined`) and then it abandons the arrival. Only the
     * second half is refusable, so offering it where [cancellable] is false fires the first half
     * alone — and fix round 1 made exactly the two phases where the bytes are already on the
     * device uncancellable, which is where it hurts most. Press the X during the `INSTALLING`
     * phase of a Play fetch (a streamed sha256 of 72,654,782 B plus a copy — many seconds, not a
     * race) and: the declined flag is written, `PreviewAutoFetch.card`'s `userSaidNo` outranks
     * `workInFlight` so the card vanishes as if the no had taken effect, the install lands
     * anyway, and `localPreviewArms` has no declined term — so live words appear for the user
     * who pressed the only control on screen to refuse them. *Installed AND declined* is the
     * outcome this feature's own comments name twice as unacceptable.
     *
     * So the brief's second half — *"or the UI must not offer a cancel on the route where it
     * cannot"* — is a property of the UI, and this is where the UI asks for it: **a control in
     * this feature is enabled by the record or it is not offered.** Work that cannot be stopped
     * is a receipt with nothing to press; the announcement that follows its install carries an X
     * that really does mean no, because by then there is nothing in flight to contradict it.
     */
    val dismissable: Boolean get() = !inFlight || cancellable

    /**
     * WHETHER AN INSTALL OF OURS MAY STILL WRITE UNDER A DELETE while this record stands — the
     * delete row's question ([PreviewDeleteCase.WORKING]), and deliberately not [inFlight].
     *
     * The two differ at exactly one phase, and the difference is the point:
     * [PreviewPhase.ABANDONED] IS in flight, because Play may still be delivering and no second
     * transfer may start over it — but the install that would follow a delivery has been
     * cancelled and a delivery that lands anyway is suppressed by the shell, so nothing of ours
     * can be written. *"Nothing to free yet: the model is being written right now"* would be
     * false there, and the delete is safe.
     */
    val writeCanStillLand: Boolean
        get() = when (phase) {
            PreviewPhase.ASKING,
            PreviewPhase.AWAITING_ANSWER,
            PreviewPhase.DOWNLOADING,
            PreviewPhase.TRANSFERRING,
            PreviewPhase.INSTALLING,
            -> true
            PreviewPhase.ABANDONED -> false
            PreviewPhase.INSTALLED, PreviewPhase.FAILED, PreviewPhase.CANCELLED -> false
        }

    /** The same arrival, one step on. The language, the route and the starter are immutable by
     *  construction: a progress tick must not be able to rewrite whose work this is. */
    fun at(step: PreviewStep): PreviewWork = copy(step = step)
}

/**
 * WHAT THE DELETE ROW IS ACTUALLY LOOKING AT. SIX facts, and `StreamingPackCopy.deleteSubtitle`
 * has a true sentence for each — because 4.4.1 rendered ONE sentence (*"Frees 73 MB. Live words
 * stop; the typed transcript is unchanged."*) across all of them, and it is the largest untrue
 * sentence the feature renders.
 *
 * The fifth arrived in fix round 1 (review r1's B2): [LIVE] asserted *"Live words stop"* without
 * asking the switch immediately above it, so the screen contradicted itself in two adjacent rows.
 * The sixth is 4.5.0 Task 4's: [LIVE] also asserted it on a device where no session can ever run
 * the previewer, which is the one cell Task 1 explicitly left open (*"`PreviewDeleteCase.of`
 * deliberately takes no tier or cloud-only term… that is Task 4's enumeration and its ruling"*).
 */
enum class PreviewDeleteCase {
    /** Installed, it is the PICKED language's pack, *"Show live words"* is on, AND this device
     *  can run an on-device session at all: live words really are showing, so deleting really
     *  does stop them. */
    LIVE,

    /**
     * No on-device speech model, so nothing transcribes on this device at all — the session dies
     * at connect with *"No speech model installed"*; the mechanism is `PreviewUnreachable`'s
     * KDoc, and it is NOT a term of `localPreviewArms` — and nothing this row's reader can do to
     * the switch or the selection would put a word on the bubble
     * (4.5.0 Task 4). Deleting frees the bytes and stops nothing, and this is the reason to say —
     * because it is the one the user cannot reach from this screen's own controls.
     */
    OFF_TIER,

    /** Installed and armable, but *"Show live words"* is switched OFF — the switch this same
     *  section draws one row above. Deleting frees the bytes; *"live words stop"* would be false,
     *  because the switch has already stopped them (fix round 1, review r1's B2). */
    OFF_SWITCH,

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
         *  2. **A WRITE THAT CAN STILL LAND outranks everything** (review r3's H3-B1). The
         *     question is [PreviewWork.writeCanStillLand] and not `inFlight`, which differ at
         *     [PreviewPhase.ABANDONED]: there Play may still be delivering (so the feature is
         *     busy) while no install of ours can follow (so a delete races nothing and the row
         *     may promise the bytes back). `previewState` is remembered on
         *     keys our own install does not change, so through a repair install it stays `Repair`
         *     and the row used to draw beside the running copy — where `delete` clears the install
         *     dir under a verify + copy that is not cancellation-cooperative, so the copy lands
         *     anyway and the pack ends up installed AND declined. The work answer is read from the
         *     board, which sees all three starters; 4.4.1's guard read one composable's own `var`.
         *  3. **THE DEVICE BEFORE EVERY REASON A GESTURE WOULD CHANGE** (4.5.0 Task 4). With no
         *     on-device speech model, `OFF_SWITCH` and `OFF_SELECTION` are both still TRUE —
         *     words are off, and deleting stops nothing — but each of them names a reason that is
         *     not the operative one, and each points at a control on this very screen that would
         *     not change the answer. `DAMAGED` loses its rank here too, and for its own stated
         *     reason: it outranks the switch because *"the repair is the more actionable fact"*,
         *     and on this device the repair is not offered at all (`LivePreviewRows` withdraws
         *     the whole offer/repair branch), so there is nothing more actionable about it. The
         *     rule this order follows is `PreviewUnreachable`'s: **a fact no gesture can change
         *     outranks a fact a gesture would.**
         *  4. **THE SWITCH BEFORE THE SELECTION** (fix round 1, review r1's B2). Both say *"live
         *     words are already off"* and both are true when both are false, so the order only
         *     decides which REASON the sentence names — and the switch is the one the user can
         *     see, one row above the delete on the same screen, one tap from being the answer.
         *  5. Then the selection decides whether deleting stops anything.
         *
         * @param selectedForThisPack the user's picked language is the one this pack serves —
         *        `StreamingPackCatalog.forLanguage(selected) == pack`.
         * @param localTierInstalled an on-device whisper tier exists — the DEVICE axis (4.5.0
         *        Task 4), and the same input `PreviewAutoFetch.decide`, `PreviewAutoFetch.card`,
         *        `StreamingPackCopy.selectorLine` and [PreviewUnreachable] read. Task 1 left this
         *        term out by name and said why: *"`LIVE` means 'installed and it is the picked
         *        language's pack' and can still be false on a cloud-only device with no local
         *        tier. That is Task 4's."* It is the third arming fact this row can be wrong
         *        about, after the switch and the selection, and the only one whose remedy is not
         *        on this screen.
         * @param showLiveWords the *"Show live words"* switch — `PreferencesManager
         *        .localPreviewEnabled`, drawn by this very section one row above the delete. It is
         *        a first-class ARMING term everywhere else in the feature (`localPreviewArms`
         *        conjoins it; `PreviewAutoFetch.decide` returns NONE on it; `PreviewAutoFetch.card`
         *        returns NONE on it, *"every sentence this card can spell is false while the
         *        switch is off"*) and the delete subtitle was the one sentence that did not ask.
         */
        fun of(
            state: StreamingPackState,
            selectedForThisPack: Boolean,
            showLiveWords: Boolean,
            localTierInstalled: Boolean,
            work: PreviewWork?,
        ): PreviewDeleteCase? {
            val bytesOnDisk = state.isInstalled || state is StreamingPackState.Repair
            if (!bytesOnDisk) return null
            if (work?.writeCanStillLand == true) return WORKING
            if (!localTierInstalled) return OFF_TIER
            if (state is StreamingPackState.Repair) return DAMAGED
            if (!showLiveWords) return OFF_SWITCH
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

    /**
     * RETIRE ONE LANGUAGE'S RECORD BECAUSE THE ARRIVAL IT IS THE RECEIPT FOR IS NO LONGER THE
     * CASE — [forget]'s production door, and the answer to *"how does a terminal record ever stop
     * being read?"* (4.5.0 Task 3 review r1, B2).
     *
     * Keeping terminal entries is deliberate, and until this build it meant keeping them FOREVER:
     * [forget] had no production caller at all. That was safe only while nothing rendered a
     * present-tense sentence off one — `StreamingPackCopy.workLine` answers null for both
     * terminal-and-quiet phases. Ruling 3c's strip is the first surface that does
     * (`selectorReady`: *"English is ready: words appear on the bubble whenever you pick it"*),
     * so it is the first for which a record has to be able to stop being true.
     *
     * ### THE AXIS, because a LIST of things that have gone wrong is how a fourth round happens
     *
     * Fix round 1 split the facts on *"can the user un-say it"*, which put the two gestures here
     * and the two switchable facts in the sentence — and then had no box at all for the two facts
     * **nobody says** (review r2's N2). The axis that has a box for every fact is not about who
     * said it but about WHICH CLAIM it falsifies:
     *
     *  - **it means the arrival did not happen, or no longer has** → the record goes, HERE. There
     *    is nothing left for any surface to have a sentence about, and the fact reaches this door
     *    from the one place that performs it.
     *      - the model is DELETED (`StreamingPackManager.delete`) — the 73 MB is gone;
     *      - the verdict is WITHDRAWN (`StreamingPackManager.markCorrupt`, review r2's N2) —
     *        `isInstalled` answers false, `state()` is `Repair`, and the app's own answer to
     *        *"is this pack installed"* is now no;
     *      - the user says the PERMANENT NO (Home's X, through
     *        [PreviewAutoFetchController.cancel]) — `PreviewAutoFetch.card` has always answered
     *        `Card.NONE` on `userSaidNo`, and the strip is the same claim on a second surface.
     *  - **the arrival stands but no word can appear** → a TERM in the sentence
     *    ([StreamingPackCopy.selectorLine]), because the record is still an honest receipt and
     *    only the PROMISE is false. The *"Show live words"* switch, the on-device tier, and the
     *    previewer's own per-language verdict for this process ([PreviewDisabled] — a failed
     *    canary leaves the bytes valid and the Settings row right to call them installed). A
     *    receipt destroyed by a switch the user flips twice would be a receipt they cannot get
     *    back, and the pack would still be installed.
     *
     * A new fact goes wherever it answers that question, and nowhere else.
     *
     * **IN-FLIGHT WORK IS LEFT ALONE, and that is the rule rather than a precaution.**
     * `PreviewAutoFetchController.busy()` reads this board, so dropping a RUNNING record would
     * tell the actuator and both surfaces that nothing is happening while Play is still
     * delivering — review r2's B1a, arriving through a new door. A delete cannot reach a running
     * record anyway ([PreviewDeleteCase.WORKING] holds that row), and a cancel of one goes down
     * the route table instead of here.
     */
    fun retire(language: String) {
        _work.update { current ->
            val existing = current[language] ?: return@update current
            if (existing.inFlight) current else current - language
        }
    }

    /** Test-only: the board is process-scoped, so a JVM test must be able to start from empty. */
    fun forgetAll() {
        _work.value = emptyMap()
    }
}
