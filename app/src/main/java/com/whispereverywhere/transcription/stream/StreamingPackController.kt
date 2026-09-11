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
import kotlinx.coroutines.delay
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

    /**
     * How long an abandoned pack stays ABANDONED on the board — and therefore how long [isBusy]
     * stays true — while waiting for GOOGLE PLAY to confirm the cancel, before the app releases
     * it itself (fix round 2, review r2's B2). Ten seconds: Play answers a cancel in well under
     * one, and the two errors are wildly asymmetric.
     *
     * Releasing too EARLY costs nothing recoverable: [release] clears [activePack], so a delivery
     * that completes afterwards narrates nothing and installs nothing, the pack simply stays with
     * Play for a costless later install, and a retry is `fetch` re-attaching to a download Play
     * still owns. Releasing too LATE — never, which is what fix round 1 risked at
     * [PreviewPhase.AWAITING_ANSWER] — refuses every language on every route for the life of the
     * process with no line anywhere saying so.
     *
     * It is a watchdog rather than a timestamp read by [isBusy] deliberately: the board is a
     * `StateFlow` that composition collects, and a value that silently expires would leave
     * *"Cancelling the preview model…"* on both surfaces until something unrelated recomposed.
     * A release that WRITES is a release the user can see.
     */
    private const val ABANDON_GRACE_MS: Long = 10_000L

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

    /** The last progress percentage a `stream-pack:` line carried; negative = none this phase. */
    @Volatile
    private var lastLoggedPct: Int = -1

    /**
     * True while a fetch or its install is in flight — the single-flight guard's predicate.
     *
     * An ABANDONED pack counts as in flight until Play has finished with it, because a second
     * `fetch` over a delivery Play is still making is exactly the defect this shell's cancel used
     * to re-open. That term is read from THE ONE OBSERVABLE — see [abandoned].
     */
    fun isBusy(): Boolean =
        StreamingPackInstall.fetchInFlight(_state.value) ||
            job?.isActive == true ||
            abandonedButUnconfirmed()

    /**
     * THE ABANDON IS ON THE BOARD, NOT IN THIS OBJECT (4.5.0 Task 1, fix round 2 — review r2's
     * B1a): whether [pack] has been given up on by a [cancel] and PLAY has not confirmed it yet.
     * ONE fact, in the one place both surfaces already read, so [isBusy] and the rows cannot
     * answer it differently.
     *
     * Fix round 1 held this in a private `abandonedPackName` field AND published a terminal
     * `CANCELLED` onto the board — which is Task 1's own defect one level up, a second answer to
     * *"is work running"* that the actuator read and the surfaces could not. Three consequences,
     * all shipped in that round:
     *
     *  - Settings saw NO work line (`workLine` is null for `CANCELLED`), fell through its
     *    in-flight arm into the OFFER row, and drew *"Get the English preview model — 73 MB"*
     *    with a live tap that `PreviewAutoFetchController.start` then refused on `busy()` with
     *    no transfer, no Toast, no line and no state change;
     *  - `PreviewAutoFetch.decide`'s `packWorkInFlight` is that same global `busy()`, so a user
     *    who cancelled one language and then picked another got `Decision.NONE`, `Card.NONE` and
     *    no row anywhere in the app, silently;
     *  - and the field's single clear site sat inside a callback this app does not own, with
     *    nothing pinning that it was reachable (review r2's B2).
     *
     * [PreviewPhase.ABANDONED] answers all three: `inFlight` true, so no second transfer may
     * start and both surfaces keep a row; `cancellable` and `dismissable` false, so no control is
     * offered over it; a sentence of its own; and [release] as the one function that clears it,
     * reached by Play's own answer AND by [cancel]'s bounded watchdog, so neither is
     * load-bearing alone.
     */
    private fun abandoned(pack: StreamingPack): Boolean =
        PreviewWorkboard.of(pack.language)?.phase == PreviewPhase.ABANDONED

    /** [abandoned] for whichever pack this shell last worked on — [isBusy]'s own term. Null
     *  [activePack] means [release] has already ended this shell's interest in it. */
    private fun abandonedButUnconfirmed(): Boolean =
        activePack?.let { abandoned(it) } == true

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
     * Abandon the fetch: the pack is noted ABANDONED on the one observable, Play's download is
     * cancelled through the manager, and the install coroutine (if any) is cancelled. Nothing was
     * installed, and a delivered-but-uninstalled pack stays with Play for a costless retry.
     *
     * ### IT IS A LATCH, NOT A REQUEST (fix round 1, review r1's B1) — AND THE LATCH IS A PHASE
     *
     * The user's "no" and "Play has stopped talking" are two different facts, and 4.5.0's first
     * cut recorded only the first. [PreviewPhase.ABANDONED] is the second, and it is on the BOARD
     * rather than in a field of this object (fix round 2, review r2's B1a — see [abandoned]):
     * written BEFORE Play is asked, because the listener runs on the main thread and a state that
     * raced the ask must land on the abandoned side of it; consulted by [onPackState] before it
     * publishes anything and before [beginInstall]; and cleared only by [release]. So a delivery
     * that completes anyway does not install, does not narrate, and does not let a second 73 MB
     * be offered over it — and every surface can SEE that, because it is the phase they render
     * from.
     *
     * The shell's own machine goes to `Cancelled` in the same call: from the user's point of view
     * the fetch they cancelled is over the moment they say so, and [isBusy] then rests on the
     * board's phase alone rather than on two independently-expiring facts.
     *
     * ### AND THE RELEASE IS OURS, NOT PLAY'S (fix round 2, review r2's B2)
     *
     * Fix round 1 argued the release was always reachable because *"it is only ever reached at a
     * phase where Play still has a download to cancel"*. [PreviewWork.cancellable] admits
     * [PreviewPhase.AWAITING_ANSWER], which is `NpuPackFetch.advance`'s mapping of
     * `STATUS_WAITING_FOR_WIFI` and `STATUS_REQUIRES_USER_CONFIRMATION` — where, as this
     * feature's own copy says twice, NO BYTE HAS MOVED and there is no download —
     * and `AssetPackManager.cancel` is documented as cancelling downloads, with the caveat that
     * only active ones can be cancelled. So the one release sat inside a callback this app does
     * not own, at the one phase where Play's own contract does not promise another state. While
     * it was held [isBusy] was true, so `start` refused for EVERY language and every route and
     * `decide` answered NONE everywhere: a stuck cancel killed the whole feature for the life of
     * the process, silently, and that is a strict regression on the pre-latch behaviour (which
     * was wrong in the recoverable direction).
     *
     * So this call also arms a bounded watchdog: after [ABANDON_GRACE_MS] the app releases the
     * pack itself. Play's answer is now one of TWO releases and neither is load-bearing alone.
     *
     * WHETHER it may be called at all is [PreviewWork.cancellable]'s answer, decided by
     * [PreviewAutoFetchController.cancel] — this object holds no route table of its own.
     */
    fun cancel() {
        val pack = activePack
        val packName = pack?.packName
        if (pack == null || packName == null) {
            // Nothing was ever asked for on this shell, so there is nothing to abandon.
            _state.value = NpuPackFetch.FetchState.Cancelled
            return
        }
        publish(
            packName,
            pack.language,
            NpuPackFetch.FetchState.Cancelled,
            // THE ONE PLACE THE BOARD IS TOLD SOMETHING PLAY DID NOT SAY, and the only step that
            // is not `PreviewStep.of`'s: no status Play reports means "the user changed their
            // mind". It is written before the ask below, so a state that races it is abandoned.
            step = PreviewStep(PreviewPhase.ABANDONED),
        )
        runCatching { manager?.cancel(listOf(packName)) }
        job?.cancel()
        // THE BOUNDED RELEASE (fix round 2, review r2's B2). Its own scope, never `job`: that
        // one belongs to the install and is being cancelled one line above. `release` is
        // idempotent and guarded on the pack, so whichever of the two arrives first wins and the
        // other is a no-op.
        scope.launch {
            delay(ABANDON_GRACE_MS)
            release(pack, packName)
        }
    }

    /**
     * THE RELEASE, AND THE ONLY ONE: this shell is finished with an abandoned [pack], so the
     * board goes terminal and [isBusy] goes false.
     *
     * [activePack] is cleared here rather than by [cancel] — the listener filters on its name, so
     * clearing it at the cancel would leave a delivery Play is still making unobserved and
     * unreleasable. Clearing it HERE is the point: after the release a late `AssetPackState` for
     * that pack narrates nothing and installs nothing, which is what keeps the permanent no the X
     * wrote from being contradicted by a 73 MB that lands later.
     *
     * Idempotent and guarded on the pack it was asked about, so a release for a pack a later
     * [start] has replaced cannot free the wrong one — it is reached from two threads, Play's
     * listener (main) and [cancel]'s watchdog (IO), and whichever arrives first wins.
     */
    private fun release(pack: StreamingPack, packName: String) {
        synchronized(this) {
            if (activePack !== pack || !abandoned(pack)) return
            activePack = null
            publish(packName, pack.language, NpuPackFetch.FetchState.Cancelled)
        }
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
        // phase is held until PLAY is done with the pack — which is what keeps isBusy() true over
        // a delivery Play is still making, so the Settings row cannot offer a second 73 MB on top
        // of it — and this is where Play's own answer to the cancel arrives.
        if (abandoned(pack)) {
            if (!StreamingPackInstall.playStillHoldsTheDelivery(next)) release(pack, packName)
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
     *
     * @param step the board's own reading, defaulted to the pure mapping of [next]. [cancel] is
     *        the ONE caller that overrides it, with [PreviewPhase.ABANDONED]: the machine is over
     *        the moment the user says so, and the BOARD is not over until Play has answered. Both
     *        move in one call, so the two cannot expire independently.
     */
    private fun publish(
        packName: String,
        language: String,
        next: NpuPackFetch.FetchState,
        step: PreviewStep = PreviewStep.of(next),
    ) {
        val previousWord = NpuPackFetch.statusWord(_state.value)
        _state.value = next
        PreviewWorkboard.note(language, step)
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
