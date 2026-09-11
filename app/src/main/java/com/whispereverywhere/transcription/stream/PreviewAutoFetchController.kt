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
 *  - **The latch belongs to the AUTO path only.** A tap is consent and may be repeated; the
 *    *"once per launch at most"* rule is about the silent fetch.
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

    /** Set by the AUTO path, never by a tap, and never cleared: "once per launch at most". */
    @Volatile
    private var autoAttempted = false

    @Volatile
    private var job: Job? = null

    fun attemptedThisLaunch(): Boolean = autoAttempted

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
        if (auto) {
            if (autoAttempted) return false
            autoAttempted = true
        }
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
