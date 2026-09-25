package com.whispereverywhere.npu

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackException
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.whispereverywhere.WhisperEverywhereApp
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
 * The owner of a running Play pack fetch — process-scoped, single-flight, observable (4.2 F5),
 * `NpuImportController`'s proven shape on the third arrival route.
 *
 * ### Why this object exists, in that object's own words
 *
 * A rotation destroys the Compose tree, and a fetch three minutes into ~860 MB must not die
 * with it or — worse — die silently. So the fetch's state and its install coroutine live in a
 * process-scoped owner: a recreation re-collects [state] and finds the fetch exactly where it
 * was. Play's own download additionally survives the PROCESS (the Play Store service owns it),
 * so a relaunch that calls [start] again simply re-attaches to a download already in flight —
 * the listener reports the parts still moving, the fetch Task's own result the parts Play already
 * holds ([onFetchAnswered]), and the card catches up.
 *
 * ### The split, stated honestly
 *
 * This shell is `AssetPackManager`-bound and CANNOT be executed by a JVM test. Every decision
 * it makes is [NpuPackFetch]'s and is executed there; what remains here — which builder is
 * called where, the progress throttle's one call site, and the remove-after-install ORDER —
 * is pinned as source text by `NpuDiagTest`, the same F-rule discipline every diag line
 * carries.
 *
 * ### The order invariants this shell owns
 *
 *  - **COMPLETED starts verification, never Installed**: the listener maps every
 *    `AssetPackState` through [NpuPackFetch.advance], and the [NpuPackFetch.FetchState.Verifying]
 *    arrival is what launches `installFromPack` — metadata cross-check, streamed sha256, the
 *    shared parking transaction.
 *  - **`removePack` runs STRICTLY AFTER the staged pair is verified and renamed into place**
 *    (the call site sits below the `installFromPack` success branch, and the ORDER is pinned):
 *    the delivered pack is the ONLY copy of those bytes until the finalise commits, so a remove
 *    that runs early deletes the source mid-verify. A failed verify conversely LEAVES the pack
 *    in place — the retry costs nothing, Play redelivers from disk.
 *  - **Cellular consent is Play's own dialog**: [confirm] delegates to
 *    `showConfirmationDialog`, and there is deliberately no custom re-ask anywhere in this
 *    flow — Play already knows the download's size and the user's setting, and a second dialog
 *    of ours would be a second copy of a consent Play owns.
 *
 * ### A pair may be more than one pack (P2-4; design §2.7)
 *
 * The packs are the device family's census parts ([NpuPackFetch.packsFor]): one for a Qualcomm
 * pair, two for a MediaTek one (the encoder's module and the decoder's — 1.88 GB is over Play's
 * 1.5 GB per-pack cap). Every rule above applies to ALL of them: [start] fetches every part, which
 * after process death is what re-queries each one; every `AssetPackState` updates its part's
 * reading and the pair's state is re-folded from all of them ([NpuPackFetch.advance]'s list
 * overload — the one pure mapping, applied to every part), so the install begins only when EVERY
 * part is delivered; [cancel] cancels every part; Play's one confirmation dialog covers every
 * part waiting on it; and every part is given back strictly after the finalise, never before and
 * never on a refusal. For one part each of those is exactly what the single-pack shell did.
 */
object NpuPackController {

    /** Process-scoped on purpose, `NpuImportController`'s exact reasoning: the install must
     *  outlive every Activity, and a `SupervisorJob` keeps one failed install from poisoning
     *  the scope for the next. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state =
        MutableStateFlow<NpuPackFetch.FetchState>(NpuPackFetch.FetchState.Idle)

    /** What the fetch card renders. Survives recreation because this object does. */
    val state: StateFlow<NpuPackFetch.FetchState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var manager: AssetPackManager? = null

    @Volatile
    private var appContext: Context? = null

    private val _activeTier = MutableStateFlow<String?>(null)

    /**
     * WHICH tier the current (or most recent) fetch is for (4.2 F7). The chooser's fetch card
     * renders [state] only while this names its own tier — the controller is the one owner of
     * that fact, so a recreated screen still puts the fetch on the right card and a sibling
     * card can never wear it — and the onboarding ViewModel's attach guard reads it to refuse
     * mirroring another surface's fetch (F6 review M-3). Set at [start], never cleared: the
     * last fetch's terminal state stays attributable after the fact.
     */
    val activeTier: StateFlow<String?> = _activeTier.asStateFlow()

    /**
     * The packs the current (or most recent) fetch is for — the device family's census parts, in
     * order (P2-4). Written at [start], read by every other member.
     */
    @Volatile
    private var activeParts: List<PackPart> = emptyList()

    /**
     * Each part's latest `AssetPackState`, index-aligned with [activeParts]; null = Play has said
     * nothing about that part yet in this fetch. Reset at every [start], so a new fetch — and a
     * re-attach after process death — re-queries every part. Guarded by this object's monitor.
     */
    private val readings: MutableList<NpuPackFetch.PartReading?> = mutableListOf()

    /**
     * Which fetch [readings] belong to — bumped at every [start] (the P2b review's FIX-NOW), so the
     * fetch Task's answer to an EARLIER fetch (a retry tapped before it arrived) never fills a later
     * fetch's readings — and at every [cancel] (the P2c review's later item), so an answer to a
     * CANCELLED fetch never folds in after the user cancelled it. Guarded by this object's monitor.
     */
    private var fetchGeneration: Int = 0

    /** The last progress percentage a `pack:` line carried; negative = none this phase. */
    @Volatile
    private var lastLoggedPct: Int = -1

    /** True while a fetch or its install is in flight — the single-flight guard's predicate. */
    fun isBusy(): Boolean = when (_state.value) {
        is NpuPackFetch.FetchState.Pending,
        is NpuPackFetch.FetchState.Downloading,
        is NpuPackFetch.FetchState.Transferring,
        is NpuPackFetch.FetchState.NeedsConfirmation,
        is NpuPackFetch.FetchState.Verifying,
        -> true
        else -> job?.isActive == true
    }

    /**
     * Start (or re-attach to) the fetch of [tierId]'s pack. Single-flight: a call while one is
     * in flight does nothing and returns false, the same guard-with-reason as the import's.
     */
    fun start(context: Context, tierId: String): Boolean = synchronized(this) {
        if (isBusy()) return false
        // The requested tier is named BEFORE any refusal below can return (F7 fix round 1, m-1).
        // The no-pack branch publishes a Failed the CARD must render, and both surfaces decide
        // whose fetch a denied start belongs to by comparing this value: written after that
        // branch, a second denial would inherit the PREVIOUS tier's name and be rewritten as
        // "another model is downloading", burying the controller's own words. Still exactly one
        // write site (pinned == 1) — it simply moved above the early return.
        _activeTier.value = tierId
        val appCtx = context.applicationContext
        // (P2-4) The packs are the device family's own census parts — the Main-safe family memo,
        // never a dlopen — so a Qualcomm pair is its tier's one pack and a MediaTek pair the
        // encoder's module and the decoder's.
        val parts = NpuPackFetch.packsFor(tierId, (appCtx as? WhisperEverywhereApp)?.npuSocFamily)
        if (parts.isEmpty()) {
            // A tier without a pack for this device (no pair measured for its family, or no
            // family at all, which no fetch card is ever shown for) is a caller bug, refused
            // loudly rather than crashed on.
            _state.value = NpuPackFetch.FetchState.Failed(
                "This build has no Google Play pack for the '$tierId' tier."
            )
            return false
        }
        appContext = appCtx
        activeParts = parts
        // Every part unanswered: this fetch — or the re-attach after a process death — re-queries
        // each one. A part still moving answers through the listener; a part Play ALREADY holds
        // answers COMPLETED only in the fetch Task's own result, because the listener fires on
        // changes and a finished pack has none (onFetchAnswered — the P2b review's FIX-NOW). So a
        // retry moves only the bytes still missing, and the delivered part is counted.
        readings.clear()
        repeat(parts.size) { readings += null }
        val generation = ++fetchGeneration
        val packName = NpuPackFetch.packLabel(parts)
        lastLoggedPct = -1
        val mgr = manager ?: AssetPackManagerFactory.getInstance(appCtx).also {
            it.registerListener(listener)
            manager = it
        }
        publish(tierId, packName, NpuPackFetch.FetchState.Pending)
        mgr.fetch(parts.map { it.packName })
            .addOnSuccessListener { states -> onFetchAnswered(generation, states.packStates()) }
            .addOnFailureListener { failure ->
                // The Task can fail before any AssetPackState update exists (a sideloaded install
                // fails HERE). The error code flows through the same table as everything else.
                val code = (failure as? AssetPackException)?.errorCode
                    ?: NpuPackFetch.ERROR_INTERNAL_ERROR
                publish(
                    tierId, packName,
                    NpuPackFetch.FetchState.Failed(NpuPackFetch.failureReason(code)),
                )
            }
        true
    }

    /**
     * Abandon the fetch: Play's download of EVERY part is cancelled through the manager (a part
     * already delivered keeps its bytes for the retry), the install coroutine (if any) is
     * cancelled — `installFromPack`'s own finally clears its `.part` files — and the card reads
     * Cancelled at once, because from the user's point of view the fetch they cancelled is over
     * the moment they say so.
     *
     * **The cancel ends the fetch's GENERATION too (the P2c review, a LATER item).** The fetch
     * Task's answer ([onFetchAnswered]) is dropped only when its generation is stale, and until
     * this line only [start] moved the generation: an answer arriving after a Cancel still folded
     * in, and could flip Cancelled back to Pending — or, when Play already held every part, to
     * Verifying, and run the install the user had just cancelled (reachable on Qualcomm too, since
     * the Task success listener). So the cancel advances it FIRST, under the same monitor the fetch
     * bump and the answer's check take, and any answer to the cancelled fetch is an answer to an
     * earlier one.
     */
    fun cancel() {
        synchronized(this) { fetchGeneration++ }
        val tierId = _activeTier.value
        val parts = activeParts
        if (parts.isNotEmpty()) runCatching { manager?.cancel(parts.map { it.packName }) }
        job?.cancel()
        if (tierId != null && parts.isNotEmpty()) {
            publish(tierId, NpuPackFetch.packLabel(parts), NpuPackFetch.FetchState.Cancelled)
        } else {
            _state.value = NpuPackFetch.FetchState.Cancelled
        }
    }

    /**
     * Show PLAY'S OWN confirmation dialog for [NpuPackFetch.FetchState.NeedsConfirmation] —
     * wifi-wait and explicit consent both. Deliberately no custom dialog of ours: the consent
     * is Play's to ask, sized and worded by Play, and the listener narrates the outcome.
     */
    fun confirm(activity: Activity) {
        manager?.showConfirmationDialog(activity)
    }

    private val listener = AssetPackStateUpdateListener { packState -> onPackState(packState) }

    /**
     * THE FETCH TASK'S OWN ANSWER (the P2b review's FIX-NOW): the `AssetPackStates` of every pack
     * this fetch requested, as they stood when Play took the request — the ONLY report of a part
     * Play already holds, since the listener fires on changes and a pack that is already COMPLETED
     * has none. Without it, a retry after the encoder's 1.3 GB landed and the decoder failed, was
     * cancelled or lost its process folded [null, …] to Pending for good: [isBusy] stayed true and
     * [start] refused every tap.
     *
     * Each answer fills only a part the listener has not spoken for in THIS fetch
     * ([NpuPackFetch.unansweredParts]: a listener reading is never older than the request's), and
     * goes through [onPackState] — the one fold, so it is re-folded, published and, on Verifying,
     * installed exactly as a listener update would be. Held under the monitor across the check and
     * the fill, so a listener update cannot land between them; an answer to an earlier fetch is
     * dropped by [fetchGeneration]. When the listener answered first this does nothing, which keeps
     * the one-part path what it was.
     */
    private fun onFetchAnswered(generation: Int, states: Map<String, AssetPackState>) {
        synchronized(this) {
            if (generation != fetchGeneration) return
            for (name in NpuPackFetch.unansweredParts(activeParts, readings, states.keys)) {
                states[name]?.let { onPackState(it) }
            }
        }
    }

    private fun onPackState(packState: AssetPackState) {
        val tierId = _activeTier.value ?: return
        // Read under the monitor start writes under: this fetch's parts and its readings, together.
        val (parts, next) = synchronized(this) {
            val parts = activeParts
            val part = parts.indexOfFirst { it.packName == packState.name() }
            if (part < 0 || part >= readings.size) return
            readings[part] = NpuPackFetch.PartReading(
                packState.status(),
                packState.errorCode(),
                packState.bytesDownloaded(),
                packState.totalBytesToDownload(),
            )
            // EVERY AssetPackState goes through the one pure mapping — no status is interpreted
            // here, which is what keeps the shell too boring to be wrong. (P2-4) The list
            // overload folds every part's latest reading, whichever part this update was about.
            parts to NpuPackFetch.advance(readings.toList())
        }
        val packName = NpuPackFetch.packLabel(parts)
        publish(tierId, packName, next)
        // COMPLETED means DELIVERED: Verifying is where OUR work begins — and the fold answers it
        // only once EVERY part is delivered, so a partial pair never installs.
        if (next is NpuPackFetch.FetchState.Verifying) beginInstall(tierId, packName)
    }

    /**
     * Publish a state and narrate it: one `pack:` line per STATUS TRANSITION, plus at most one
     * per 10% of progress — the throttle's decision is [NpuPackFetch.shouldLogProgress], pure
     * and tested, with exactly this one call site.
     */
    private fun publish(tierId: String, packName: String, next: NpuPackFetch.FetchState) {
        val previousWord = NpuPackFetch.statusWord(_state.value)
        // (The P3a review, small 1) Every refusal is worded for THIS device's import route: the
        // machine's sentences name "'Import model pair…' below", which is true where the device is
        // offered the import and false on a MediaTek row, which is offered none (no zip is
        // published for its pair). This is the one funnel every published state passes, so the
        // picker's card and the onboarding row both read the device's truth.
        _state.value = if (next is NpuPackFetch.FetchState.Failed) {
            NpuPackFetch.FetchState.Failed(NpuPackFetch.reasonFor(next.reason, importRouteOffered()))
        } else {
            next
        }
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
        Log.i(NpuDiag.TAG, NpuDiag.packLine(tierId, packName, word, soFar, total))
    }

    /**
     * Is THIS device offered the SAF import route at all? `NpuAssetImport.panelOfferedOn` for its
     * census family — the one rule the Settings picker's panel, the gated card's import control
     * and every refusal that names the import all follow (the P3a review, small 1). Read off the
     * app's family memo, a table lookup.
     */
    private fun importRouteOffered(): Boolean =
        NpuAssetImport.panelOfferedOn((appContext as? WhisperEverywhereApp)?.npuSocFamily)

    /** Launch the install exactly once per delivery, joining a cancelled predecessor first —
     *  the import controller's N4 lesson, kept: two installs write the same staging paths. */
    private fun beginInstall(tierId: String, packName: String) {
        synchronized(this) {
            if (job?.isActive == true) return
            val previous = job
            job = scope.launch {
                previous?.join()
                runInstall(tierId, packName)
            }
        }
    }

    private suspend fun runInstall(tierId: String, packName: String) {
        val mgr = manager ?: return
        val app = appContext as? WhisperEverywhereApp
        val family = app?.npuSocFamily
        val parts = activeParts
        val outcome = if (family == null) {
            // Unreachable behind the F6/F7 capability gates (no fetch card without a resolved
            // family) — refused anyway, by name: an unverifiable pack must never install.
            NpuAssetImport.ImportState.Refused(
                "this device's silicon family could not be resolved, so the delivered pack " +
                    "could not be verified against the family's published digests. Nothing " +
                    "was installed."
            )
        } else {
            // (4.4.0) Located through the SHARED helper, with the manager this object already
            // registered its listener on — same instance, same call, same nullability. The
            // previewer's pack (and the voice's) read their delivered assets through the very
            // same function, so there is exactly one spelling of "where a delivered pack is".
            // (P2-4) Every PART's location, by pack name: the install reads each entry out of the
            // part that carries it.
            val assetsPaths = parts.mapNotNull { part ->
                PlayPacks.assetsPath(mgr, part.packName)?.let { part.packName to it }
            }.toMap()
            if (assetsPaths.size != parts.size) {
                // Delivered, but Play answers no location for a part: treat as the empty
                // delivery — the fail-safe reading, with the import path named, worded by how
                // THIS pair's packs are delivered (targeted or not).
                NpuAssetImport.ImportState.Refused(NpuPackFetch.emptyDeliveryRefusal(parts))
            } else {
                try {
                    app.whisperModelManager.installFromPack(
                        tierId, family, assetsPaths,
                    ) { soFar, total ->
                        publish(tierId, packName, NpuPackFetch.FetchState.Verifying(soFar, total))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    NpuAssetImport.ImportState.Refused(
                        NpuAssetImport.unreadableRefusal(
                            "${t.javaClass.simpleName}: ${t.message}"
                        )
                    )
                }
            }
        }
        when (outcome) {
            is NpuAssetImport.ImportState.Installed -> {
                publish(tierId, packName, NpuPackFetch.FetchState.Installed)
                val artifact = family?.let { NpuFleetCensus.artifactFor(it.id, tierId) }
                val pairBytes =
                    (artifact?.encoder?.bytes ?: 0L) + (artifact?.decoder?.bytes ?: 0L)
                Log.i(NpuDiag.TAG, NpuDiag.packOk(tierId, entries = 2, bytes = pairBytes))
                // STRICTLY AFTER the staged pair is verified and renamed into place (ORDER
                // pin — the 10th+ instance of the remove-after-land rule on this branch): the
                // delivered pack is the ONLY copy of those bytes until the finalise commits,
                // so a remove that runs early deletes the source mid-verify. (P2-4) EVERY part,
                // all of them after the finalise — the pair landed as one, so it is given back
                // as one.
                parts.forEach { mgr.removePack(it.packName) }
            }
            is NpuAssetImport.ImportState.Refused -> {
                publish(tierId, packName, NpuPackFetch.FetchState.Failed(outcome.reason))
                Log.w(NpuDiag.TAG, NpuDiag.packRefused(tierId, outcome.reason))
                // NO removePack on this path: a failed verify leaves every delivered part in
                // place, so the retry costs nothing — Play redelivers from disk.
            }
            else -> Unit // installFromPack's terminal states are exactly the two above.
        }
    }
}
