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
 * the listener replays the current status and the card catches up.
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

    @Volatile
    private var activeTierId: String? = null

    @Volatile
    private var activePackName: String? = null

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
        val packName = NpuPackFetch.PACK_BY_TIER[tierId]
        if (packName == null) {
            // A tier without a pack is a caller bug, refused loudly rather than crashed on.
            _state.value = NpuPackFetch.FetchState.Failed(
                "This build has no Google Play pack for the '$tierId' tier."
            )
            return false
        }
        val appCtx = context.applicationContext
        appContext = appCtx
        activeTierId = tierId
        activePackName = packName
        lastLoggedPct = -1
        val mgr = manager ?: AssetPackManagerFactory.getInstance(appCtx).also {
            it.registerListener(listener)
            manager = it
        }
        publish(tierId, packName, NpuPackFetch.FetchState.Pending)
        mgr.fetch(listOf(packName)).addOnFailureListener { failure ->
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
     * Abandon the fetch: Play's download is cancelled through the manager, the install
     * coroutine (if any) is cancelled — `installFromPack`'s own finally clears its `.part`
     * files — and the card reads Cancelled at once, because from the user's point of view the
     * fetch they cancelled is over the moment they say so.
     */
    fun cancel() {
        val tierId = activeTierId
        val packName = activePackName
        if (packName != null) runCatching { manager?.cancel(listOf(packName)) }
        job?.cancel()
        if (tierId != null && packName != null) {
            publish(tierId, packName, NpuPackFetch.FetchState.Cancelled)
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

    private fun onPackState(packState: AssetPackState) {
        val tierId = activeTierId ?: return
        val packName = activePackName ?: return
        if (packState.name() != packName) return
        // EVERY AssetPackState goes through the one pure mapping — no status is interpreted
        // here, which is what keeps the shell too boring to be wrong.
        val next = NpuPackFetch.advance(
            packState.status(),
            packState.errorCode(),
            packState.bytesDownloaded(),
            packState.totalBytesToDownload(),
        )
        publish(tierId, packName, next)
        // COMPLETED means DELIVERED: Verifying is where OUR work begins.
        if (next is NpuPackFetch.FetchState.Verifying) beginInstall(tierId, packName)
    }

    /**
     * Publish a state and narrate it: one `pack:` line per STATUS TRANSITION, plus at most one
     * per 10% of progress — the throttle's decision is [NpuPackFetch.shouldLogProgress], pure
     * and tested, with exactly this one call site.
     */
    private fun publish(tierId: String, packName: String, next: NpuPackFetch.FetchState) {
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
        Log.i(NpuDiag.TAG, NpuDiag.packLine(tierId, packName, word, soFar, total))
    }

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
        val outcome = if (family == null) {
            // Unreachable behind the F6/F7 capability gates (no fetch card without a resolved
            // family) — refused anyway, by name: an unverifiable pack must never install.
            NpuAssetImport.ImportState.Refused(
                "this device's silicon family could not be resolved, so the delivered pack " +
                    "could not be verified against the family's published digests. Nothing " +
                    "was installed."
            )
        } else {
            val assetsPath = mgr.getPackLocation(packName)?.assetsPath()
            if (assetsPath == null) {
                // Delivered, but Play answers no location: treat as the empty delivery — the
                // fail-safe reading, with the import path named.
                NpuAssetImport.ImportState.Refused(NpuPackFetch.emptyDeliveryRefusal())
            } else {
                try {
                    app.whisperModelManager.installFromPack(
                        tierId, family, assetsPath,
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
                // so a remove that runs early deletes the source mid-verify.
                mgr.removePack(packName)
            }
            is NpuAssetImport.ImportState.Refused -> {
                publish(tierId, packName, NpuPackFetch.FetchState.Failed(outcome.reason))
                Log.w(NpuDiag.TAG, NpuDiag.packRefused(tierId, outcome.reason))
                // NO removePack on this path: a failed verify leaves the delivered pack in
                // place, so the retry costs nothing — Play redelivers from disk.
            }
            else -> Unit // installFromPack's terminal states are exactly the two above.
        }
    }
}
