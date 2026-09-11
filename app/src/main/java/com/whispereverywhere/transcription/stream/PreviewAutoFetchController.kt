package com.whispereverywhere.transcription.stream

import android.util.Log
import com.whispereverywhere.WhisperEverywhereApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * PERFORMS the previewer's acquisition (4.4.1; the ONE actuator since 4.5.0) — the actuator of
 * [PreviewAutoFetch.Decision.FETCH], of the offer card's tap AND of the Settings row's tap, and
 * the holder of the one piece of state neither a pure function nor a Compose tree can hold: the
 * once-per-launch latch.
 *
 * ### THE ONE ACTUATOR (4.5.0 Task 1, review r3's H3-B2)
 *
 * Until 4.5.0 the Settings row had an actuator of its own — a `when` over the route inside the
 * composable, running in a `rememberCoroutineScope()`, guarded only by
 * `StreamingPackController.isBusy()` and never by [busy]. Two consequences, both shipped:
 *
 *  - it OFFERED, and STARTED, a second 73 MB over work this object was already doing, because its
 *    guard could not see our two routes at all;
 *  - and its scope died with the screen, so leaving Settings mid-download cancelled the transfer
 *    while `fetchOne`'s `keepRow = true` left the `DownloadManager` row running.
 *
 * Both surfaces now call [start]. The guard is [busy] for all three starters, and the work is on
 * this object's process-scoped scope whoever began it.
 *
 * ### What it does and does not decide
 *
 * It decides HOW, never WHETHER. Whether the fetch may happen is [PreviewAutoFetch.decide]'s,
 * answered before this object is called and pinned cell by cell on the JVM; HOW is the route
 * reduction [StreamingPackInstall.sourceOf] — the same one the Settings row makes, spelled once
 * here so the card and the row cannot take different routes from the same state.
 * `LiveWordsCardPinTest` holds both halves to that as source.
 *
 * ### Why it is a sibling of [StreamingPackController] rather than part of it
 *
 * That object is the PLAY FETCH's shell: one pack, `AssetPackManager`, the refusal latch, the
 * install that follows a delivery. This one is the auto-fetch's POLICY EXECUTION, and it spans all
 * three routes — Play's fetch, a delivered pack's local install, and the non-Play download — plus
 * the back-off that is common to them. Folding it in would put a `PreferencesManager` write and a
 * launch-scoped latch inside the object `StreamingPackShellPinTest` pins as a pure relay of
 * `NpuPackFetch`'s decisions, and would give that file a second reason to exist.
 *
 * ### The four orders it owns
 *
 *  - **Single flight first.** [busy] is answered before the route, and it includes
 *    `StreamingPackController.isBusy()` — so no two of the three starters can double one 73 MB.
 *    Two installs write the same staging paths (the import controller's N4 lesson).
 *  - **THE BOARD IS BEGUN BY THE STARTER, and narrated after** (4.5.0 Task 1). Each route calls
 *    `PreviewWorkboard.begin` with its own [PreviewRoute] and [starterOf]'s answer before its
 *    first byte, and `note`s every step after — so no surface ever sees a phase for work with no
 *    owner, and both surfaces see work whichever starter began it.
 *  - **The latch belongs to the AUTO path only, and to one LANGUAGE at a time.** A tap is consent
 *    and may be repeated; the *"once per launch at most"* rule is about the silent fetch of ONE
 *    pack, and a second language selected in the same launch is a new decision rather than a
 *    repeat of an old one (owner rulings 2026-09-11).
 *  - **Every failure records the back-off, and a cancellation is not a failure.** One write site,
 *    reached from our own two routes' `catch` and from Play's terminal `Failed` — the loop this
 *    closes is a user reopening the app on a bad connection all afternoon. A
 *    `CancellationException` is rethrown untouched: a user who cancelled has not hit a bad
 *    network, and recording it would silence the feature for a day for the wrong reason.
 */
object PreviewAutoFetchController {

    /** Process-scoped for [StreamingPackController]'s reason: a 73 MB transfer must outlive the
     *  Compose tree that started it, and a `SupervisorJob` keeps one failure from poisoning the
     *  scope for the next attempt. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The languages the AUTO path has already tried this launch — never a tap's, never cleared:
     * *"once per launch at most"*.
     *
     * PER LANGUAGE since 4.4.1's acquisition amendment (owner rulings 2026-09-11). The loop the
     * latch exists to stop is a re-fetch of ONE pack; a user who picks a second language in the
     * same launch has made a NEW decision — *"when you change your language and the model's not
     * installed, it should just automatically download right there on the spot"* — and a global
     * latch would answer that with the offer card instead. A concurrent set because
     * [attemptedThisLaunch] is read from composition while [start] writes it under its monitor.
     */
    private val autoAttempted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var job: Job? = null

    fun attemptedThisLaunch(pack: StreamingPack): Boolean = pack.language in autoAttempted

    /**
     * Whether a fetch or install is in flight ANYWHERE — ours, or one [StreamingPackController]
     * is holding. Both the decision's `packWorkInFlight` and this object's own guard read it, so
     * every surface and the actuator agree about what is running.
     *
     * **GLOBAL, not per language, and deliberately** (4.5.0 Task 1). [PreviewWorkboard] is keyed
     * by language so two arrivals can be SEEN separately; letting two arrivals RUN is a different
     * decision, and this build does not take it: [StreamingPackController] holds one
     * `AssetPackManager`, one `activePack` and one listener filter, so two concurrent Play fetches
     * would need that shell re-keyed too. While the actuator's capacity is one, the DECISION's
     * `packWorkInFlight` must be this same global answer — a per-language reading there would let
     * [PreviewAutoFetch.decide] answer FETCH for a second pack that [start] then refuses, and the
     * card would sit on a progress line for work that never began.
     */
    fun busy(): Boolean = job?.isActive == true || StreamingPackController.isBusy()

    /**
     * WHO is asking, as the board records it.
     *
     * The asymmetry this exists to carry is the 2026-09-11 acquisition rulings': an unasked
     * background transfer waits for wifi, and a transfer the user just caused by picking a
     * language happens at once, **because the pick IS the consent**. A surface that cannot tell
     * the two apart cannot state that deal honestly, and the copy is downstream of this.
     */
    private fun starterOf(auto: Boolean): PreviewStarter =
        if (auto) PreviewStarter.TOP_UP else PreviewStarter.PICK

    /**
     * Start the arrival of [pack] by whichever route [state] names — for ALL THREE STARTERS since
     * 4.5.0: the foreground hook's silent top-up, Home's offer card, and the Settings row.
     * Returns false when nothing was started: work already in flight, the launch's auto attempt
     * already spent, or a state with no install action (an [StreamingPackState.Installed] pack).
     *
     * A [StreamingPackState.Repair] DOES reach here as of 4.5.0 — it is the Settings row's repair
     * offer, which `decide` refuses for the auto path and Home's card therefore never taps — and
     * it is answered by the reduction, not by the `when`: [StreamingPackInstall.sourceOf] strips
     * every Repair wrapper first, so a repair takes the route a first install would have taken.
     * The `is Repair` arm below is totality, and unreachable for that reason.
     *
     * @param auto true for the foreground hook's silent fetch — the only caller the once-per-launch
     *        latch applies to, and the only one recorded as a [PreviewStarter.TOP_UP]. A tap from
     *        either surface is a [PreviewStarter.PICK]: consent, repeatable, latch-exempt.
     */
    fun start(
        app: WhisperEverywhereApp,
        pack: StreamingPack,
        state: StreamingPackState,
        auto: Boolean,
    ): Boolean = synchronized(this) {
        if (busy()) return false
        // Test and set in one step, so two auto attempts for the same language cannot both pass.
        if (auto && !autoAttempted.add(pack.language)) return false
        when (StreamingPackInstall.sourceOf(state)) {
            StreamingPackState.PackFetchable -> askPlay(app, pack, auto)
            StreamingPackState.PackDelivered -> landDeliveredPack(app, pack, auto)
            StreamingPackState.Downloadable -> downloadFallback(app, pack, auto)
            // Nothing to start. Spelled rather than wildcarded so a state added to the machine is
            // answered here instead of falling into the third-party download below. `sourceOf`
            // strips every Repair wrapper, so that arm is totality and nothing else — a repair
            // takes the route a first install would have taken, which is what lets a delivered
            // pack repair a half install without touching the network.
            StreamingPackState.Installed -> return false
            is StreamingPackState.Repair -> return false
        }
        true
    }

    /**
     * ABANDON the arrival of [language]'s pack — the card's X, by the CONTROLLER RULING of
     * 2026-09-11 (CHANGE 2, answering the auto-fetch round's own C4). That X is the same gesture
     * that writes the permanent no, and a "no" that lets 73 MB finish landing is not a no.
     *
     * ### ONE CANCEL, ONE MEANING (4.5.0 Task 1)
     *
     * THE GUARD IS [PreviewWork.cancellable], read from the one observable for the ONE PACK this
     * gesture is about. It replaces 4.4.1's `if (!busy()) return`, which was global and therefore
     * wrong in both directions: the X on one language's card could abandon another language's
     * transfer, and it fired on routes where a cancel stops nothing.
     *
     * The route half of that answer is [PreviewRoute.stopsBeforeTheCopy]'s table and the phase
     * half is [PreviewPhase.INSTALLING]'s; this method only ACTS on it, so the table and the
     * behaviour cannot drift apart — *"make the BEHAVIOUR match the table"*. What the answer means
     * at each route, read at the code rather than assumed:
     *
     *  - **[PreviewRoute.PLAY_FETCH] — the bytes stop, while they are still moving.**
     *    `StreamingPackController.cancel()` LATCHES the pack as abandoned, asks PLAY to cancel the
     *    pack download and publishes `Cancelled`, which reaches the board through that shell's one
     *    note site; the watcher job goes with it. The latch is what makes the "no" a no rather
     *    than a request: a `COMPLETED` that beat the cancel is neither narrated nor installed, and
     *    the pack counts as busy until Play has finished with it, so no surface offers a second
     *    73 MB over a delivery Play is still making. Nothing of ours is installed, and the partial
     *    transfer is Play's own to keep or discard. A pack Play has ALREADY delivered stays
     *    delivered, so a later install costs no transfer at all.
     *  - **[PreviewRoute.DIRECT_DOWNLOAD] — the bytes stop, as of 4.5.0.** The poll loop's `delay`
     *    IS a suspension point, so the cancel is seen within one poll, and
     *    `StreamingPackManager.fetchOne` now removes the `DownloadManager` row on every exit while
     *    `download` sweeps what it had staged. 4.4.1 kept the row (`keepRow = true`) and left that
     *    transfer running behind a UI that said it had stopped, while `delete()` removed the same
     *    row — review r1's H1.
     *  - **[PreviewRoute.DELIVERED_PACK] — REFUSED, and the UI offers no cancel.** There is no
     *    transfer to stop: Play has already put those bytes on the device and the route's only
     *    phase is a local verify + copy that spends none of the user's data. It is also not
     *    cancellation-cooperative — no suspension point between `withContext(Dispatchers.IO)`'s
     *    entry and its return (`StreamingPackInstall.verify` and `install` are blocking and
     *    `onProgress` is a plain lambda) — so the copy FINISHES and the marker lands whatever
     *    anyone presses. Saying so and doing nothing is the honest answer; publishing a `Cancelled`
     *    that describes nothing is not. The declined flag the X writes BEFORE this call still keeps
     *    the card and the auto-fetch silent afterwards, and Settings then shows the installed rows
     *    and its delete.
     *
     * The last point is true of EVERY route once the work reaches [PreviewPhase.TRANSFERRING] or
     * [PreviewPhase.INSTALLING] — bytes already on the device, by Play's move or by ours — which
     * is why the phase term crosses the table rather than sitting inside one row of it. A
     * `TRANSFERRING` cancel therefore takes the same honest refusal as `DELIVERED_PACK`'s, on the
     * primary Play route (fix round 1, review r1's B1a).
     *
     * A cancellation is NOT a failure: [ours] rethrows `CancellationException` untouched, so no
     * back-off stamp is written and the model stays one tap away.
     */
    fun cancel(language: String) {
        val work = PreviewWorkboard.of(language) ?: return
        if (!work.cancellable) {
            // Said out loud rather than swallowed: "the X did nothing" is the one outcome a
            // support log has to be able to explain.
            log(route = "dismiss", auto = false, outcome = "uncancellable")
            return
        }
        when (work.route) {
            PreviewRoute.PLAY_FETCH -> {
                StreamingPackController.cancel()
                job?.cancel()
            }
            PreviewRoute.DIRECT_DOWNLOAD -> job?.cancel()
            // Unreachable: `cancellable` is false for every phase of this route and the guard
            // above has already returned. Spelled so a route added to the table is answered here
            // rather than silently taking Play's branch and cancelling someone else's fetch.
            PreviewRoute.DELIVERED_PACK -> return
        }
        log(route = "dismiss", auto = false, outcome = "cancelled")
    }

    /**
     * Ask Google Play, then WATCH for the terminal state — the fetch shell owns the whole flow
     * from here (progress, the install that follows COMPLETED, the refusal latch), so all that is
     * left is the one thing it cannot know: that this attempt was ours, and a failure of it must
     * start the back-off.
     *
     * The terminal test is the pure predicate the shell's own single-flight guard uses, not a
     * hand-rolled list: Idle is excluded because the flow starts there and `start` publishes
     * `Pending` synchronously, so a flow still reading Idle has not begun.
     */
    private fun askPlay(app: WhisperEverywhereApp, pack: StreamingPack, auto: Boolean) {
        StreamingPackController.start(app, pack, starterOf(auto))
        job = scope.launch {
            // The terminal test is the BOARD's own in-flight predicate — the same one the card,
            // the row and the delete guard read — so "this attempt is over" cannot mean two
            // things. `start` has already written the entry synchronously (ASKING, or FAILED for
            // a build with no pack module), so a null here would mean the board was never
            // written: a bug, not a state to wait on.
            val terminal = PreviewWorkboard.work
                .map { it[pack.language] }
                .first { it != null && !it.inFlight }!!
            if (terminal.phase == PreviewPhase.FAILED) {
                noteFailure(app, route = "play", auto = auto, kind = "refused")
            } else {
                log(route = "play", auto = auto, outcome = terminal.phase.name.lowercase())
            }
        }
    }

    /** Play already delivered the 73 MB: verify + copy into `filesDir`, no network at any point. */
    private fun landDeliveredPack(app: WhisperEverywhereApp, pack: StreamingPack, auto: Boolean) {
        job = scope.launch {
            PreviewWorkboard.begin(
                pack.language,
                PreviewRoute.DELIVERED_PACK,
                starterOf(auto),
                // The route has exactly ONE phase: the bytes are here, and what is left is the
                // copy. It is also the phase no cancel can stop, which is why the route's own
                // `stopsBeforeTheCopy` is false.
                PreviewStep(PreviewPhase.INSTALLING),
            )
            ours(app, pack, route = "pack", auto = auto) {
                app.streamingPackManager.installFromPack(pack) { _, _ -> }
            }
        }
    }

    /** The non-Play fallback — a debug build, a sideload, or an install Play refused by name. */
    private fun downloadFallback(app: WhisperEverywhereApp, pack: StreamingPack, auto: Boolean) {
        job = scope.launch {
            PreviewWorkboard.begin(
                pack.language,
                PreviewRoute.DIRECT_DOWNLOAD,
                starterOf(auto),
                // No denominator yet: `download` gates free space and clears stale rows before
                // the first byte, and the line says "Downloading the preview model…" until it can
                // say a number rather than inventing one.
                PreviewStep(PreviewPhase.DOWNLOADING),
            )
            ours(app, pack, route = "download", auto = auto) {
                app.streamingPackManager.download(pack) { soFar, total ->
                    // `download` reports the CUMULATIVE staged bytes and calls this one last time
                    // at the total, immediately before the sha256 + copy — which is the same work
                    // the pack route calls INSTALLING. Saying so is what keeps the line off
                    // "73 of 73 MB" for the whole hash of 72,654,782 B.
                    PreviewWorkboard.note(
                        pack.language,
                        if (total > 0L && soFar >= total) {
                            PreviewStep(PreviewPhase.INSTALLING)
                        } else {
                            PreviewStep(PreviewPhase.DOWNLOADING, soFar, total)
                        },
                    )
                }
            }
        }
    }

    /**
     * The two routes WE run, wrapped once: every outcome reaches the ONE observable, the failure
     * also goes to the back-off, and the cancellation goes nowhere else.
     *
     * 4.4.1 cleared its progress line in a `finally` instead, on the rule that *"a stale
     * 'Verifying and installing…' over a finished install is a lie the card cannot dismiss"* —
     * which is still true, and is now served by writing the TERMINAL phase rather than by
     * erasing the record: `workLine` is null for INSTALLED and CANCELLED, so the row goes quiet
     * either way, and a FAILED keeps the sentence the user needs. That is what lets the Settings
     * row show a refusal at all; it used to be a Toast, gone by the time the user looked.
     */
    private suspend fun ours(
        app: WhisperEverywhereApp,
        pack: StreamingPack,
        route: String,
        auto: Boolean,
        work: suspend () -> Unit,
    ) {
        try {
            work()
            PreviewWorkboard.note(pack.language, PreviewStep(PreviewPhase.INSTALLED))
            log(route = route, auto = auto, outcome = "installed")
        } catch (cancelled: CancellationException) {
            PreviewWorkboard.note(pack.language, PreviewStep(PreviewPhase.CANCELLED))
            throw cancelled
        } catch (t: Throwable) {
            // The manager's refusals carry their own sentence (`StreamingPackException`: the
            // storage gate, a size or hash mismatch); anything else gets the copy object's one
            // last-resort line rather than an exception type shown to a user.
            PreviewWorkboard.note(
                pack.language,
                PreviewStep(
                    PreviewPhase.FAILED,
                    reason = (t as? StreamingPackException)?.message
                        ?: StreamingPackCopy.INSTALL_FAILED,
                ),
            )
            noteFailure(app, route = route, auto = auto, kind = t.javaClass.simpleName)
        }
    }

    /**
     * THE BACK-OFF'S ONE WRITE SITE. Every route's failure lands here, which is what makes
     * [PreviewAutoFetch.backedOff] able to hold — a route that failed quietly would retry once
     * per process start for as long as the network stayed bad.
     */
    private fun noteFailure(app: WhisperEverywhereApp, route: String, auto: Boolean, kind: String) {
        app.preferencesManager.livePreviewAutoFetchFailedAt = System.currentTimeMillis()
        log(route = route, auto = auto, outcome = "failed:$kind")
    }

    /**
     * One `stream-auto:` line per attempt outcome, so "why did the model not arrive?" is one grep.
     * A route name, whether it was silent, and an outcome word — no file path, no exception
     * message, and nothing this object could not see anyway: it moves a model file and asks Play
     * for a pack, and never touches a transcript.
     */
    private fun log(route: String, auto: Boolean, outcome: String) {
        Log.i("WE-DIAG", "stream-auto: route=$route auto=${if (auto) 1 else 0} outcome=$outcome")
    }
}
