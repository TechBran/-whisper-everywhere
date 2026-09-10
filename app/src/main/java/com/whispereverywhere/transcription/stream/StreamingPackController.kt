package com.whispereverywhere.transcription.stream

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
 * ASKS GOOGLE PLAY for the previewer's pack — the actuator of
 * [StreamingPackState.PackFetchable] (4.4.0, the 2026-09-10 amendment: *"if the pack is not
 * fetched, the UI's install action requests the pack through the shared `AssetPackManager` helper
 * (progress, failure, retry as the NPU fetch does)"*).
 *
 * Without this object the previewer is uninstallable on every Play install: `state()` answers
 * `PackFetchable` and nothing can move it to `PackDelivered`, and — because the fallback latch is
 * only ever set by a Play failure — [StreamingPackState.Downloadable] is unreachable on every
 * non-debug build too, so the sideloader cannot reach the fallback either.
 *
 * ### Why this is a SIBLING of `NpuPackController`, not that object generalised
 *
 * One MACHINE, two shells. Every decision here is [NpuPackFetch]'s, reused verbatim — [
 * NpuPackFetch.advance] maps every `AssetPackState`, `statusWord`/`pct`/`shouldLogProgress`
 * narrate it, `failureReason` names every error code — and the Play locate and give-back are
 * [PlayPacks]'. What differs is only what a delivered pack MEANS: the NPU shell hands its bytes
 * to `WhisperModelManager` keyed by a tier and a silicon family, this one hands them to
 * [StreamingPackManager] keyed by a language. Folding both into one object would mean a second
 * `publish`/`packOk`/`packRefused`/`removePack` call site inside `NpuPackController` — each
 * pinned `== 1` by `NpuDiagTest` — or renaming its `tierId` parameter, which `NpuDiagTest` pins
 * as literal source text. Those pins are FILE-scoped (`source(".../npu/NpuPackController.kt")`),
 * so a sibling in this package trips none of them and forks no decision.
 *
 * ### The split, stated honestly
 *
 * This object is `AssetPackManager`-bound and CANNOT be constructed by a JVM test. Its own
 * decisions are pure and executed elsewhere — the single-flight predicate is
 * [StreamingPackInstall.fetchInFlight], the fallback classifier is
 * [StreamingPackInstall.playRefusedThisInstall] (reached through
 * [StreamingPackManager.notePlayFailure]), the status mapping is [NpuPackFetch.advance] — and
 * what remains here (which call sits where, and in which order) is pinned as source text by
 * `StreamingPackShellPinTest`, the `NpuDiagTest` discipline for the third fetch shell.
 *
 * ### The order invariants this shell owns
 *
 *  - **COMPLETED starts the install, never Installed**: `advance` maps Play's terminal success to
 *    [NpuPackFetch.FetchState.Verifying], and that arrival is what launches
 *    [StreamingPackManager.installFromPack] — one verification for both sources, the marker
 *    written last, and only then the give-back to Play, which the manager owns and performs
 *    strictly after the land.
 *  - **A refusal Play named as THIS INSTALL's own fault latches the fallback**: every Failed —
 *    the listener's, and a `fetch` Task that fails before any `AssetPackState` exists — passes
 *    through [latchRefusal] first, so `playCanDeliver()` can actually go false and the
 *    commit-pinned download becomes reachable on a release sideload.
 *  - **Cellular consent is Play's own dialog**: [confirm] delegates to `showConfirmationDialog`
 *    and there is deliberately no re-ask of ours — Play already knows the download's size and the
 *    user's setting.
 *
 * Retry is [start] called again: the state machine's terminal states are not busy, so the same
 * entry point re-attaches to a download Play still owns or begins a new one.
 */
object StreamingPackController {

    /** Process-scoped for `NpuPackController`'s reason: a 73 MB fetch must outlive the Compose
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

    @Volatile
    private var activePackName: String? = null

    /** The last progress percentage a `stream-pack:` line carried; negative = none this phase. */
    @Volatile
    private var lastLoggedPct: Int = -1

    /** True while a fetch or its install is in flight — the single-flight guard's predicate. */
    fun isBusy(): Boolean =
        StreamingPackInstall.fetchInFlight(_state.value) || job?.isActive == true

    /**
     * Start (or re-attach to) the fetch of [pack]'s Play pack. Single-flight: a call while one is
     * in flight does nothing and returns false, the import's and the NPU fetch's own guard.
     *
     * A row with no pack module ([StreamingPack.packName] null) is refused loudly rather than
     * silently: the caller's own state machine offered the fetch, so a missing module is a bug in
     * the catalog, not a user situation.
     */
    fun start(context: Context, pack: StreamingPack): Boolean = synchronized(this) {
        if (isBusy()) return false
        val packName = pack.packName
        if (packName == null) {
            _state.value = NpuPackFetch.FetchState.Failed(
                "This build has no Google Play pack for the ${pack.language} preview model."
            )
            return false
        }
        val appCtx = context.applicationContext
        appContext = appCtx
        activePackName = packName
        lastLoggedPct = -1
        val mgr = manager ?: PlayPacks.managerFor(appCtx).also {
            it.registerListener(listener)
            manager = it
        }
        publish(packName, NpuPackFetch.FetchState.Pending)
        mgr.fetch(listOf(packName)).addOnFailureListener { failure ->
            // The Task can fail before any AssetPackState exists — a sideloaded install fails
            // HERE, which is the one failure that must reach the latch, or the fallback the
            // amendment kept alive for sideloads can never be offered.
            val code = (failure as? AssetPackException)?.errorCode
                ?: NpuPackFetch.ERROR_INTERNAL_ERROR
            latchRefusal(code)
            publish(
                packName,
                NpuPackFetch.FetchState.Failed(NpuPackFetch.failureReason(code)),
            )
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
        val packName = activePackName
        if (packName != null) runCatching { manager?.cancel(listOf(packName)) }
        job?.cancel()
        if (packName != null) {
            publish(packName, NpuPackFetch.FetchState.Cancelled)
        } else {
            _state.value = NpuPackFetch.FetchState.Cancelled
        }
    }

    /** Show PLAY'S OWN confirmation dialog for [NpuPackFetch.FetchState.NeedsConfirmation] —
     *  wifi-wait and explicit consent both. */
    fun confirm(activity: Activity) {
        manager?.showConfirmationDialog(activity)
    }

    private val listener = AssetPackStateUpdateListener { packState -> onPackState(packState) }

    private fun onPackState(packState: AssetPackState) {
        val packName = activePackName ?: return
        if (packState.name() != packName) return
        // EVERY AssetPackState goes through the one pure mapping — no status is interpreted here,
        // which is what keeps the shell too boring to be wrong.
        val next = NpuPackFetch.advance(
            packState.status(),
            packState.errorCode(),
            packState.bytesDownloaded(),
            packState.totalBytesToDownload(),
        )
        if (next is NpuPackFetch.FetchState.Failed) latchRefusal(packState.errorCode())
        publish(packName, next)
        // COMPLETED means DELIVERED: verify + land is where OUR work begins.
        if (next is NpuPackFetch.FetchState.Verifying) beginInstall(pack = packOf(packName), packName = packName)
    }

    /**
     * The ONE latch site. [StreamingPackManager.notePlayFailure] asks
     * [StreamingPackInstall.playRefusedThisInstall] whether Play named THIS INSTALL as the reason
     * — the `NpuPackFetch` sideload family, not a second opinion about it — and flips
     * `playCanDeliver()` for the rest of the process if it did.
     */
    private fun latchRefusal(errorCode: Int) {
        packManager()?.notePlayFailure(errorCode)
    }

    /** Launch the install exactly once per delivery, joining a cancelled predecessor first —
     *  the import controller's N4 lesson: two installs write the same staging paths. */
    private fun beginInstall(pack: StreamingPack?, packName: String) {
        if (pack == null) return
        synchronized(this) {
            if (job?.isActive == true) return
            val previous = job
            job = scope.launch {
                previous?.join()
                runInstall(pack, packName)
            }
        }
    }

    private suspend fun runInstall(pack: StreamingPack, packName: String) {
        val packs = packManager() ?: return
        try {
            packs.installFromPack(pack) { soFar, total ->
                publish(packName, NpuPackFetch.FetchState.Verifying(soFar, total))
            }
            publish(packName, NpuPackFetch.FetchState.Installed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // A StreamingPackException already carries the manager's own sentence (the storage
            // gate, a size or hash refusal); anything else is named by its type rather than
            // guessed at. NO give-back on this path — the manager only hands the pack back after
            // a landed install, so the retry costs nothing and Play redelivers from disk.
            val reason = (t as? StreamingPackException)?.message
                ?: "The preview model could not be installed (${t.javaClass.simpleName})."
            publish(packName, NpuPackFetch.FetchState.Failed(reason))
        }
    }

    /**
     * Publish a state and narrate it: one `stream-pack:` line per STATUS TRANSITION, plus at most
     * one per 10 % of progress — the throttle's decision is [NpuPackFetch.shouldLogProgress],
     * pure and tested, with exactly this one call site. Numbers, codes and a pack name only; a
     * pack name is not transcript content.
     */
    private fun publish(packName: String, next: NpuPackFetch.FetchState) {
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
            "stream-pack: pack=$packName status=$word soFar=$soFar total=$total",
        )
    }

    private fun packOf(packName: String): StreamingPack? =
        StreamingPackCatalog.packs.firstOrNull { it.packName == packName }

    private fun packManager(): StreamingPackManager? =
        (appContext as? WhisperEverywhereApp)?.streamingPackManager
}
