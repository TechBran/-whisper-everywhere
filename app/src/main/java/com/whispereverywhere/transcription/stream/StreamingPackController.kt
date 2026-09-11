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
 *  - **The refusal card speaks the PREVIEWER's words**: [NpuPackFetch] maps every status and
 *    names every error code, but its failure sentences are the NPU model chooser's copy — six of
 *    them send the user to `'Import model pair…'`, that feature's ggml SAF importer, which is not
 *    on this row — so every `Failed` published here is re-told by
 *    [StreamingPackInstall.deliveryRefusal] (the listener) or [StreamingPackInstall.fetchRefusal]
 *    (the `fetch` Task's own failure) first. `StreamingPackShellPinTest` pins ZERO
 *    `NpuPackFetch.failureReason` call sites in this file; the classifier that keys the latch
 *    still applies it, once, inside [StreamingPackManager.notePlayFailure].
 *
 * Retry is [start] called again: the state machine's terminal states are not busy, so the same
 * entry point re-attaches to a download Play still owns or begins a new one.
 */
object StreamingPackController {

    /** Process-scoped for `NpuPackController`'s reason: a 73 MB fetch must outlive the Compose
     *  tree that started it, and a `SupervisorJob` keeps one failed install from poisoning the
     *  scope for the next. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * PRIVATE since 4.5.0 (Task 1). This object's own machine is how it decides what to do next —
     * the single-flight predicate, the install that follows a delivery — and it is no longer what
     * any SURFACE reads: the one thing both surfaces read is [PreviewWorkboard], which [publish]
     * writes. A second public flow of the same fetch is exactly how "is work running?" came to
     * have two answers, one of which Settings collected and Home did not.
     */
    private val _state =
        MutableStateFlow<NpuPackFetch.FetchState>(NpuPackFetch.FetchState.Idle)

    @Volatile
    private var job: Job? = null

    @Volatile
    private var manager: AssetPackManager? = null

    @Volatile
    private var appContext: Context? = null

    /** The pack this shell is currently fetching — the PACK and not just its name, because every
     *  board write is keyed by its language (4.5.0 Task 1). */
    @Volatile
    private var activePack: StreamingPack? = null

    /**
     * THE ABANDONED LATCH (4.5.0 Task 1, fix round 1 — review r1's B1): the pack name a [cancel]
     * has given up on, held until PLAY itself has finished with that pack
     * ([StreamingPackInstall.playStillHoldsTheDelivery] over the states that keep arriving).
     *
     * 4.5.0's first cut published a terminal `Cancelled` and cleared nothing, so the cancel was a
     * REQUEST and not a latch, with three consequences that are all the absence of this one fact:
     *
     *  - [onPackState] guarded only on [activePack] and the name, so a post-cancel `COMPLETED`
     *    still ran [beginInstall] and the 73 MB landed AFTER the X had written the permanent no —
     *    *installed AND declined*, the failure mode this feature's own comments name twice;
     *  - the same listener re-published Play's progress onto the board, resurrecting a row the
     *    user had dismissed;
     *  - and `Cancelled` is not [StreamingPackInstall.fetchInFlight], so [isBusy] went false the
     *    instant the X was pressed and the Settings row offered a second 73 MB over a delivery
     *    Play had not finished (H3-B2, reopened through the cancel path).
     *
     * **[activePack] is deliberately NOT cleared by the cancel**: the listener filters on its
     * name, so clearing it would make this latch unreleasable and leave the feature busy for the
     * life of the process. The give-back path needs nothing here either — the manager only hands
     * a pack back after a LANDED install, and an abandoned pack is never installed.
     */
    @Volatile
    private var abandonedPackName: String? = null

    /** The last progress percentage a `stream-pack:` line carried; negative = none this phase. */
    @Volatile
    private var lastLoggedPct: Int = -1

    /**
     * True while a fetch or its install is in flight — the single-flight guard's predicate.
     *
     * An ABANDONED pack counts as in flight until Play has finished with it, because a second
     * `fetch` over a delivery Play is still making is exactly the defect this shell's cancel used
     * to re-open. See [abandonedPackName].
     */
    fun isBusy(): Boolean =
        StreamingPackInstall.fetchInFlight(_state.value) ||
            job?.isActive == true ||
            abandonedPackName != null

    /**
     * Start (or re-attach to) the fetch of [pack]'s Play pack. Single-flight: a call while one is
     * in flight does nothing and returns false, the import's and the NPU fetch's own guard.
     *
     * A row with no pack module ([StreamingPack.packName] null) is refused loudly rather than
     * silently: the caller's own state machine offered the fetch, so a missing module is a bug in
     * the catalog, not a user situation.
     *
     * @param starter WHO asked — an unasked top-up or the user's own pick. It is recorded on the
     *        board here and nowhere else, because this call is the only place that knows.
     */
    fun start(context: Context, pack: StreamingPack, starter: PreviewStarter): Boolean = synchronized(this) {
        if (isBusy()) return false
        val packName = pack.packName
        val noPackModule =
            "This build has no Google Play pack for the ${pack.language} preview model."
        // THE BOARD IS BEGUN BY THE STARTER (4.5.0 Task 1), before a byte moves and before any
        // refusal: the route and who asked are facts only this call knows, so `begin` is the one
        // place they are ever set. The no-pack refusal is begun TERMINAL rather than skipped, so
        // the row that shows it renders from the same one observable as everything else instead
        // of from a `Failed` nobody is collecting.
        PreviewWorkboard.begin(
            pack.language,
            PreviewRoute.PLAY_FETCH,
            starter,
            if (packName == null) {
                PreviewStep(PreviewPhase.FAILED, reason = noPackModule)
            } else {
                PreviewStep(PreviewPhase.ASKING)
            },
        )
        if (packName == null) {
            _state.value = NpuPackFetch.FetchState.Failed(noPackModule)
            return false
        }
        val appCtx = context.applicationContext
        appContext = appCtx
        activePack = pack
        lastLoggedPct = -1
        val mgr = manager ?: PlayPacks.managerFor(appCtx).also {
            it.registerListener(listener)
            manager = it
        }
        publish(packName, pack.language, NpuPackFetch.FetchState.Pending)
        mgr.fetch(listOf(packName)).addOnFailureListener { failure ->
            // The Task can fail before any AssetPackState exists — a sideloaded install fails
            // HERE, which is the one failure that must reach the latch, or the fallback the
            // amendment kept alive for sideloads can never be offered.
            val code = (failure as? AssetPackException)?.errorCode
                ?: NpuPackFetch.ERROR_INTERNAL_ERROR
            latchRefusal(code)
            publish(
                packName,
                pack.language,
                NpuPackFetch.FetchState.Failed(StreamingPackInstall.fetchRefusal(code)),
            )
        }
        true
    }

    /**
     * Abandon the fetch: the pack is LATCHED as abandoned, Play's download is cancelled through
     * the manager, the install coroutine (if any) is cancelled, and the row reads Cancelled at
     * once — from the user's point of view the fetch they cancelled is over the moment they say
     * so. Nothing was installed, and a delivered-but-uninstalled pack stays with Play for a
     * costless retry.
     *
     * ### IT IS A LATCH, NOT A REQUEST (fix round 1, review r1's B1)
     *
     * The user's "no" and "Play has stopped talking" are two different facts, and 4.5.0's first
     * cut recorded only the first. [abandonedPackName] is the second: set BEFORE Play is asked
     * (the listener runs on the main thread, and a state that raced the ask must land on the
     * latched side of it), consulted by [onPackState] before it publishes anything and before
     * [beginInstall], and released only when
     * [StreamingPackInstall.playStillHoldsTheDelivery] says Play has finished with the pack. So a
     * delivery that completes anyway does not install, does not narrate, and does not let a
     * second 73 MB be offered over it.
     *
     * WHETHER it may be called at all is [PreviewWork.cancellable]'s answer, decided by
     * [PreviewAutoFetchController.cancel] — this object holds no route table of its own, and it
     * is only ever reached for [PreviewRoute.PLAY_FETCH] at a phase where Play still has a
     * download to cancel ([PreviewPhase.TRANSFERRING] is not one of them). Every such phase is
     * one Play is actively working on, which is what makes the release reachable: the next
     * `AssetPackState` for the pack — its own CANCELED, or the COMPLETED that beat the cancel —
     * is the one that clears the latch.
     */
    fun cancel() {
        val pack = activePack
        val packName = pack?.packName
        if (pack == null || packName == null) {
            // Nothing was ever asked for on this shell, so there is nothing to latch.
            _state.value = NpuPackFetch.FetchState.Cancelled
            return
        }
        abandonedPackName = packName
        runCatching { manager?.cancel(listOf(packName)) }
        job?.cancel()
        publish(packName, pack.language, NpuPackFetch.FetchState.Cancelled)
    }

    /** Show PLAY'S OWN confirmation dialog for [NpuPackFetch.FetchState.NeedsConfirmation] —
     *  wifi-wait and explicit consent both. */
    fun confirm(activity: Activity) {
        manager?.showConfirmationDialog(activity)
    }

    private val listener = AssetPackStateUpdateListener { packState -> onPackState(packState) }

    private fun onPackState(packState: AssetPackState) {
        val pack = activePack ?: return
        val packName = pack.packName ?: return
        if (packState.name() != packName) return
        // EVERY AssetPackState goes through the one pure mapping — no status is interpreted here,
        // which is what keeps the shell too boring to be wrong.
        val next = NpuPackFetch.advance(
            packState.status(),
            packState.errorCode(),
            packState.bytesDownloaded(),
            packState.totalBytesToDownload(),
        )
        // THE FALLBACK LATCH IS ABOVE THE ABANDONED CHECK, and deliberately: a refusal Play NAMES
        // is a durable fact about this INSTALL, not about this attempt, so a pack the user
        // dismissed still teaches `playCanDeliver()` what Play has refused. Without that, the next
        // attempt repeats a Play fetch Play has already refused by name.
        if (next is NpuPackFetch.FetchState.Failed) latchRefusal(packState.errorCode())
        // THE ABANDONED PACK IS NOT NARRATED AND NOT INSTALLED (fix round 1, review r1's B1).
        // The X wrote the permanent no; a COMPLETED that beat the cancel must not land 73 MB
        // behind it, and a DOWNLOADING tick must not put the dismissed row back on screen. The
        // latch is held until PLAY is done with the pack — which is what keeps isBusy() true over
        // a delivery Play is still making, so the Settings row cannot offer a second 73 MB on top
        // of it — and it is released HERE because this listener is the only thing that learns it.
        if (packName == abandonedPackName) {
            if (!StreamingPackInstall.playStillHoldsTheDelivery(next)) abandonedPackName = null
            return
        }
        // The MAPPING is NpuPackFetch's; the WORDS on this card are the previewer's own. That
        // table's failure sentences send the user to 'Import model pair…' — the NPU chooser's
        // ggml SAF importer, which is not on this row and cannot read these four ONNX files — so
        // every Failed is re-told by StreamingPackInstall.deliveryRefusal before it is published.
        val shown = if (next is NpuPackFetch.FetchState.Failed) {
            NpuPackFetch.FetchState.Failed(
                StreamingPackInstall.deliveryRefusal(
                    packState.status(),
                    packState.errorCode(),
                    packState.totalBytesToDownload(),
                )
            )
        } else {
            next
        }
        publish(packName, pack.language, shown)
        // COMPLETED means DELIVERED: verify + land is where OUR work begins.
        if (next is NpuPackFetch.FetchState.Verifying) beginInstall(pack = pack, packName = packName)
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
    private fun beginInstall(pack: StreamingPack, packName: String) {
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
                publish(packName, pack.language, NpuPackFetch.FetchState.Verifying(soFar, total))
            }
            publish(packName, pack.language, NpuPackFetch.FetchState.Installed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // A StreamingPackException already carries the manager's own sentence (the storage
            // gate, a size or hash refusal); anything else is named by its type rather than
            // guessed at. NO give-back on this path — the manager only hands the pack back after
            // a landed install, so the retry costs nothing and Play redelivers from disk.
            val reason = (t as? StreamingPackException)?.message
                ?: "The preview model could not be installed (${t.javaClass.simpleName})."
            publish(packName, pack.language, NpuPackFetch.FetchState.Failed(reason))
        }
    }

    /**
     * Publish a state, PUT IT ON THE ONE OBSERVABLE, and narrate it: one `stream-pack:` line per
     * STATUS TRANSITION, plus at most one per 10 % of progress — the throttle's decision is
     * [NpuPackFetch.shouldLogProgress], pure and tested, with exactly this one call site.
     * Numbers, codes and a pack name only; a pack name is not transcript content.
     *
     * The board write is the shell's ONLY one after [start]'s `begin`, and it interprets nothing:
     * the phase and the bytes are [PreviewStep.of]'s answer, pure and total over the fetch
     * machine. The throttle deliberately does NOT gate it — the log is for a human reading a
     * run-book and can be sampled, while a progress bar that moved once per 10 % would be a worse
     * bar than the one this replaces.
     */
    private fun publish(packName: String, language: String, next: NpuPackFetch.FetchState) {
        val previousWord = NpuPackFetch.statusWord(_state.value)
        _state.value = next
        PreviewWorkboard.note(language, PreviewStep.of(next))
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

    private fun packManager(): StreamingPackManager? =
        (appContext as? WhisperEverywhereApp)?.streamingPackManager
}
