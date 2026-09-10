package com.whispereverywhere.tts

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackException
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.npu.NpuPackFetch
import com.whispereverywhere.play.PlayPacks
import com.whispereverywhere.transcription.stream.StreamingPackInstall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ASKS GOOGLE PLAY for the read-aloud voice's pack — the actuator of
 * [VoiceInstallRoute.Fetch] (4.4.0, the 2026-09-10 amendment, Task 2b: *"the Settings voice
 * install action requests the pack on Play builds through the shared helper (progress/failure/retry
 * as the NPU fetch does)"*).
 *
 * ### Why this is a SIBLING of `NpuPackController` / `StreamingPackController`
 *
 * One MACHINE, three shells. Every decision here is [NpuPackFetch]'s, reused verbatim —
 * [NpuPackFetch.advance] maps every `AssetPackState`, `statusWord`/`pct`/`shouldLogProgress`
 * narrate it — and the Play locate and give-back are [PlayPacks]'. What differs is only what a
 * delivered pack MEANS: the NPU shell hands its bytes to `WhisperModelManager` keyed by a tier and
 * a silicon family, the previewer's hands them to `StreamingPackManager` keyed by a language, and
 * this one hands ONE archive to [TtsModelManager]'s unchanged verify + extract. Folding them into
 * one object would mean a second `publish`/`packOk`/`packRefused`/`removePack` call site inside
 * `NpuPackController` — each pinned `== 1` by `NpuDiagTest` — or renaming its `tierId` parameter,
 * which that test pins as literal source text. Those pins are FILE-scoped, so a sibling trips none
 * of them and forks no decision.
 *
 * ### The split, stated honestly
 *
 * This object is `AssetPackManager`-bound and CANNOT be constructed by a JVM test. Its own
 * decisions are pure and executed elsewhere — the single-flight predicate is
 * [StreamingPackInstall.fetchInFlight] (the fetch machine is the same machine, so the predicate is
 * the same function), the fallback classifier is `StreamingPackInstall.playRefusedThisInstall`
 * reached through [TtsModelManager.notePlayFailure], the status mapping is [NpuPackFetch.advance],
 * the WORDS are [TtsModelManager.packRefusal]'s — and what remains here (which call sits where, and
 * in which order) is pinned as source text by `TtsPackShellPinTest`.
 *
 * ### The order invariants this shell owns
 *
 *  - **COMPLETED starts the install, never Installed**: `advance` maps Play's terminal success to
 *    [NpuPackFetch.FetchState.Verifying], and that arrival is what launches
 *    [TtsModelManager.installFromPack] — the same size gate, hash set, extract and atomic swap the
 *    download route runs, and only then the give-back to Play, which the manager owns and performs
 *    strictly after the land.
 *  - **A refusal Play named as THIS INSTALL's own fault latches the fallback**: every Failed —
 *    the listener's, and a `fetch` Task that fails before any `AssetPackState` exists — passes
 *    through [latchRefusal] first, so `playCanDeliver()` can actually go false and the GitHub
 *    download becomes reachable on a release sideload.
 *  - **Cellular consent is Play's own dialog**: [confirm] delegates to `showConfirmationDialog`
 *    and there is deliberately no re-ask of ours — Play already knows the download's size and the
 *    user's setting.
 *  - **The refusal card speaks the VOICE's words**: [NpuPackFetch] maps every status and names
 *    every error code, but its failure sentences are the NPU model chooser's copy — six of them
 *    send the user to `'Import model pair…'`, that feature's ggml SAF importer, which is not on
 *    this row — so every `Failed` published here is re-told by [TtsModelManager.deliveryRefusal]
 *    (the listener) or [TtsModelManager.packRefusal] (the `fetch` Task's own failure) first.
 *
 * Retry is [start] called again: the state machine's terminal states are not busy, so the same
 * entry point re-attaches to a download Play still owns or begins a new one.
 */
object TtsPackController {

    /** Process-scoped for `NpuPackController`'s reason: a 350 MB fetch must outlive the Compose
     *  tree that started it, and a `SupervisorJob` keeps one failed install from poisoning the
     *  scope for the next. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state =
        MutableStateFlow<NpuPackFetch.FetchState>(NpuPackFetch.FetchState.Idle)

    /** What the Settings row renders while a fetch is in flight. Survives recreation because
     *  this object does; Play's own download survives the PROCESS. */
    val state: StateFlow<NpuPackFetch.FetchState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var manager: AssetPackManager? = null

    @Volatile
    private var appContext: Context? = null

    /** The last progress percentage a `voice-pack:` line carried; negative = none this phase. */
    @Volatile
    private var lastLoggedPct: Int = -1

    /** True while a fetch or its install is in flight — the single-flight guard's predicate. */
    fun isBusy(): Boolean =
        StreamingPackInstall.fetchInFlight(_state.value) || job?.isActive == true

    /**
     * Start (or re-attach to) the fetch of the voice's Play pack. Single-flight: a call while one
     * is in flight does nothing and returns false, the import's and the NPU fetch's own guard.
     */
    fun start(context: Context): Boolean = synchronized(this) {
        if (isBusy()) return false
        val appCtx = context.applicationContext
        appContext = appCtx
        lastLoggedPct = -1
        val mgr = manager ?: PlayPacks.managerFor(appCtx).also {
            it.registerListener(listener)
            manager = it
        }
        publish(NpuPackFetch.FetchState.Pending)
        mgr.fetch(listOf(TtsModelManager.PACK_NAME)).addOnFailureListener { failure ->
            // The Task can fail before any AssetPackState exists — a sideloaded install fails
            // HERE, which is the one failure that must reach the latch, or the download the
            // amendment kept alive for sideloads can never be offered.
            val code = (failure as? AssetPackException)?.errorCode
                ?: NpuPackFetch.ERROR_INTERNAL_ERROR
            latchRefusal(code)
            publish(NpuPackFetch.FetchState.Failed(TtsModelManager.packRefusal(code)))
        }
        true
    }

    /**
     * Abandon the fetch: Play's download is cancelled through the manager, the install coroutine
     * (if any) is cancelled, and the row reads Cancelled at once — from the user's point of view
     * the fetch they cancelled is over the moment they say so. Nothing was installed, and a
     * delivered-but-uninstalled pack stays with Play for a costless retry.
     */
    fun cancel() {
        runCatching { manager?.cancel(listOf(TtsModelManager.PACK_NAME)) }
        job?.cancel()
        publish(NpuPackFetch.FetchState.Cancelled)
    }

    /** Show PLAY'S OWN confirmation dialog for [NpuPackFetch.FetchState.NeedsConfirmation] —
     *  wifi-wait and explicit consent both. */
    fun confirm(activity: Activity) {
        manager?.showConfirmationDialog(activity)
    }

    private val listener = AssetPackStateUpdateListener { packState -> onPackState(packState) }

    private fun onPackState(packState: AssetPackState) {
        if (packState.name() != TtsModelManager.PACK_NAME) return
        // EVERY AssetPackState goes through the one pure mapping — no status is interpreted here,
        // which is what keeps the shell too boring to be wrong.
        val next = NpuPackFetch.advance(
            packState.status(),
            packState.errorCode(),
            packState.bytesDownloaded(),
            packState.totalBytesToDownload(),
        )
        if (next is NpuPackFetch.FetchState.Failed) latchRefusal(packState.errorCode())
        // The MAPPING is NpuPackFetch's; the WORDS on this row are the voice's own. That table's
        // failure sentences send the user to 'Import model pair…' — the NPU chooser's ggml SAF
        // importer, which is not on this row and cannot read a Kokoro archive — so every Failed
        // is re-told before it is published.
        val shown = if (next is NpuPackFetch.FetchState.Failed) {
            NpuPackFetch.FetchState.Failed(
                TtsModelManager.deliveryRefusal(
                    packState.status(),
                    packState.errorCode(),
                    packState.totalBytesToDownload(),
                )
            )
        } else {
            next
        }
        publish(shown)
        // COMPLETED means DELIVERED: verify + extract + land is where OUR work begins.
        if (next is NpuPackFetch.FetchState.Verifying) beginInstall()
    }

    /**
     * The ONE latch site. [TtsModelManager.notePlayFailure] asks
     * `StreamingPackInstall.playRefusedThisInstall` whether Play named THIS INSTALL as the reason
     * — the `NpuPackFetch` sideload family, not a second opinion about it — and flips
     * `playCanDeliver()` for the rest of the process if it did.
     */
    private fun latchRefusal(errorCode: Int) {
        packManager()?.notePlayFailure(errorCode)
    }

    /** Launch the install exactly once per delivery, joining a cancelled predecessor first —
     *  the import controller's N4 lesson: two installs write the same staging paths. */
    private fun beginInstall() {
        synchronized(this) {
            if (job?.isActive == true) return
            val previous = job
            job = scope.launch {
                previous?.join()
                runInstall()
            }
        }
    }

    private suspend fun runInstall() {
        val voice = packManager() ?: return
        try {
            voice.installFromPack(
                onProgress = { soFar, total ->
                    publish(NpuPackFetch.FetchState.Verifying(soFar, total))
                },
                onExtracting = {
                    publish(NpuPackFetch.FetchState.Verifying(0L, TtsModelManager.TAR_BYTES))
                },
            )
            publish(NpuPackFetch.FetchState.Installed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // A TtsDownloadException already carries the manager's own sentence (the storage gate,
            // a size or hash refusal); anything else is named by its type rather than guessed at.
            // NO give-back on this path — the manager only hands the pack back after a landed
            // install, so the retry costs nothing and Play redelivers from disk.
            val reason = (t as? TtsModelManager.TtsDownloadException)?.message
                ?: "The read-aloud voice could not be installed (${t.javaClass.simpleName})."
            publish(NpuPackFetch.FetchState.Failed(reason))
        }
    }

    /**
     * Publish a state and narrate it: one `voice-pack:` line per STATUS TRANSITION, plus at most
     * one per 10 % of progress — the throttle's decision is [NpuPackFetch.shouldLogProgress],
     * pure and tested, with exactly this one call site. Numbers, codes and a pack name only; a
     * pack name is not transcript content.
     */
    private fun publish(next: NpuPackFetch.FetchState) {
        val previousWord = NpuPackFetch.statusWord(_state.value)
        _state.value = next
        val word = NpuPackFetch.statusWord(next)
        val soFar: Long
        val total: Long
        when (next) {
            is NpuPackFetch.FetchState.Downloading -> { soFar = next.soFar; total = next.total }
            is NpuPackFetch.FetchState.Verifying -> { soFar = next.soFar; total = next.total }
            else -> { soFar = 0L; total = 0L }
        }
        if (word == previousWord) {
            // Only the two progress-bearing states ever repeat their word; both throttle.
            val pct = NpuPackFetch.pct(soFar, total)
            if (!NpuPackFetch.shouldLogProgress(lastLoggedPct, pct)) return
            lastLoggedPct = pct
        } else {
            // A new phase starts its progress narration afresh.
            lastLoggedPct = -1
        }
        Log.i(
            "WE-DIAG",
            "voice-pack: pack=$packName status=$word soFar=$soFar total=$total",
        )
    }

    /** The one pack this shell ever fetches — spelled through the manager's own constant. */
    private val packName: String get() = TtsModelManager.PACK_NAME

    private fun packManager(): TtsModelManager? =
        (appContext as? WhisperEverywhereApp)?.ttsModelManager
}
