package com.whispereverywhere.transcription.stream

import android.util.Log
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.npu.NpuPackFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * PERFORMS the previewer's auto-fetch (4.4.1) — the actuator of [PreviewAutoFetch.Decision.FETCH]
 * and of the offer card's tap, and the holder of the two pieces of state neither a pure function
 * nor a Compose tree can hold: the once-per-launch latch, and OUR OWN work's progress line.
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
 * ### The three orders it owns
 *
 *  - **Single flight first.** [busy] is answered before the route, and it includes
 *    `StreamingPackController.isBusy()` — so a fetch the SETTINGS ROW started is never doubled by
 *    an auto-fetch, and vice versa. Two installs write the same staging paths (the import
 *    controller's N4 lesson).
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
     * OUR OWN work's progress line — the verify+copy of a delivered pack, or the fallback
     * download — and null at rest. Play's own fetch narrates itself through
     * [StreamingPackController.state], which the card renders through [StreamingPackCopy.fetchLine];
     * this is the Settings row's `previewInstallStatus`, moved to process scope because an
     * auto-fetch must survive the user leaving the dashboard.
     */
    private val _line = MutableStateFlow<String?>(null)
    val line: StateFlow<String?> = _line.asStateFlow()

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
     * Whether a fetch or install of this pack is in flight ANYWHERE — ours, or one the Settings
     * row started through [StreamingPackController]. Both the decision's `packWorkInFlight` and
     * this object's own guard read it, so the card and the actuator agree about what is running.
     */
    fun busy(): Boolean = job?.isActive == true || StreamingPackController.isBusy()

    /**
     * Start the arrival of [pack] by whichever route [state] names. Returns false when nothing was
     * started: work already in flight, the launch's auto attempt already spent, or a state with no
     * install action (an [StreamingPackState.Installed] pack; a [StreamingPackState.Repair] cannot
     * reach here, because [PreviewAutoFetch.decide] answers NONE for one and the card therefore
     * offers no tap).
     *
     * @param auto true for the foreground hook's silent fetch — the only caller the once-per-launch
     *        latch applies to.
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
            // answered here instead of falling into the third-party download below.
            StreamingPackState.Installed -> return false
            is StreamingPackState.Repair -> return false
        }
        true
    }

    /**
     * ABANDON the arrival, whichever route is carrying it — the card's X, by the CONTROLLER
     * RULING of 2026-09-11 (CHANGE 2, answering the auto-fetch round's own C4). That X is the
     * same gesture that writes the permanent no, and a "no" that lets 73 MB finish landing is not
     * a no.
     *
     * THE GUARD IS FIRST, and it is [busy] — the same predicate [start] refuses on, so "there is
     * something to cancel" and "there is something to refuse to start" cannot disagree. The two
     * card states with nothing in flight (the offer, the installed announcement) therefore
     * publish no `Cancelled` into the fetch shell's StateFlow and do not move the Settings row's
     * own line for a transfer that was never running.
     *
     * It cancels a fetch the SETTINGS ROW started too, and deliberately: [busy] spans both
     * starters, so that work is exactly what the card is rendering as WORKING, and the X is
     * pressed on a card describing the transfer that is running.
     *
     * ### WHAT IT ACTUALLY DOES, ROUTE BY ROUTE (4.4.1 pass 3, ITEM 4 — review r1's nit 1)
     *
     * The earlier sentence here — *"partial bytes are discarded and nothing is installed"* — was
     * true of the route the ruling had in mind and not of the one most users are on. What each
     * route really does, read at the code rather than assumed:
     *
     *  - **[StreamingPackState.PackFetchable]** — `StreamingPackController.cancel()` asks PLAY to
     *    cancel the pack download and publishes `Cancelled`; the watcher job goes with it. Nothing
     *    of ours is installed, and the partial transfer is Play's own to keep or discard. A pack
     *    Play has ALREADY delivered stays delivered, so the Settings row can still install it with
     *    no further transfer.
     *  - **[StreamingPackState.PackDelivered]** — `installFromPack` is **not**
     *    cancellation-cooperative: between `withContext(Dispatchers.IO)`'s entry and its return
     *    there is no suspension point (`StreamingPackInstall.verify` and `install` are blocking
     *    and `onProgress` is a plain lambda), so a dismiss during it lets the verify + copy FINISH
     *    and the marker land. The model ends up INSTALLED. Nothing of the user's data was spent —
     *    Play had already put those bytes on the device and that route touches no network — and
     *    the declined flag, written before this call, keeps the card and the auto-fetch silent
     *    afterwards; Settings then shows the installed rows and its delete.
     *  - **[StreamingPackState.Downloadable]** — the poll loop's `delay` IS a suspension point, so
     *    the cancel is seen within one poll, but the bytes are deliberately KEPT: `fetchOne`'s
     *    `catch` sets `keepRow = true` so the `DownloadManager` row is not removed
     *    (`StreamingPackManager.kt`), and the staging dir is emptied only on failure or success.
     *    So that transfer CONTINUES in `DownloadManager` after the X, nothing is installed, and
     *    the next attempt's `removeStaleDownloads` clears the row before re-fetching. This is
     *    inherited `TtsModelManager` behaviour and the one route where the X does not stop the
     *    data cost — reachable only where Play cannot serve the install (a debug build, a
     *    sideload, a refusal Play named).
     *
     * A cancellation is NOT a failure: [ours] rethrows `CancellationException` untouched, so no
     * back-off stamp is written and the model stays one tap away.
     */
    fun cancel() {
        if (!busy()) return
        job?.cancel()
        StreamingPackController.cancel()
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
        StreamingPackController.start(app, pack)
        job = scope.launch {
            val terminal = StreamingPackController.state.first {
                it !is NpuPackFetch.FetchState.Idle && !StreamingPackInstall.fetchInFlight(it)
            }
            if (terminal is NpuPackFetch.FetchState.Failed) {
                noteFailure(app, route = "play", auto = auto, kind = "refused")
            } else {
                log(route = "play", auto = auto, outcome = NpuPackFetch.statusWord(terminal))
            }
        }
    }

    /** Play already delivered the 73 MB: verify + copy into `filesDir`, no network at any point. */
    private fun landDeliveredPack(app: WhisperEverywhereApp, pack: StreamingPack, auto: Boolean) {
        job = scope.launch {
            _line.value = StreamingPackCopy.PROGRESS_INSTALLING
            ours(app, route = "pack", auto = auto) {
                app.streamingPackManager.installFromPack(pack) { _, _ -> }
            }
        }
    }

    /** The non-Play fallback — a debug build, a sideload, or an install Play refused by name. */
    private fun downloadFallback(app: WhisperEverywhereApp, pack: StreamingPack, auto: Boolean) {
        job = scope.launch {
            _line.value = StreamingPackCopy.PROGRESS_STARTING
            ours(app, route = "download", auto = auto) {
                app.streamingPackManager.download(pack) { soFar, total ->
                    _line.value = StreamingPackCopy.downloadProgress(soFar, total)
                }
            }
        }
    }

    /**
     * The two routes WE run, wrapped once: the failure goes to the back-off, the cancellation goes
     * nowhere, and the line is cleared whatever happened — a stale "Verifying and installing…"
     * over a finished install is a lie the card cannot dismiss.
     */
    private suspend fun ours(
        app: WhisperEverywhereApp,
        route: String,
        auto: Boolean,
        work: suspend () -> Unit,
    ) {
        try {
            work()
            log(route = route, auto = auto, outcome = "installed")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            noteFailure(app, route = route, auto = auto, kind = t.javaClass.simpleName)
        } finally {
            _line.value = null
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
