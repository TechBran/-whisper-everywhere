package com.whispereverywhere.npu

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
 * The owner of a running asset-pair import — process-scoped, single-flight, observable
 * (4.0, Q8 fix round 1, I3).
 *
 * ### Why the composition may not own it
 *
 * `MainActivity` declares no `android:configChanges`, so a rotation, a theme change, a font-size
 * change or any other process-preserved recreation **destroys and rebuilds the whole Compose tree**.
 * The first draft held the import's state in `remember` and launched it from
 * `rememberCoroutineScope()` inside the nav composition: both die with that tree, so a rotation
 * three minutes into a 358 MB copy cancelled the import and returned the panel to
 * "Import model pair…" **with no message at all** — the silent failure the whole import is written
 * to avoid, arriving through the one door nothing was watching.
 *
 * The house pattern for a long install is an owner that outlives the view: `ModelDownloadViewModel`
 * runs its download in `viewModelScope`, which survives configuration change because the
 * `ViewModelStore` is retained. That shape is unavailable here — the document picker's launcher must
 * be registered from the **activity's** composition (a screen that may be off the back stack when
 * the result returns cannot own one), and the screen's `ModelDownloadViewModel` is scoped to a
 * `NavBackStackEntry` the activity cannot reach. So the import gets a process-scoped owner instead,
 * which is the same answer `ModelInstallSignal` and [NpuTierStatus] already gave to "the composition
 * cannot hold this".
 *
 * A recreation now re-collects [state] and finds the import exactly where it was.
 *
 * ### Why the work arrives as a lambda
 *
 * So this object can be **executed** by a JVM test. The real import needs a `Context`, a
 * `ContentResolver` and 358 MB; the *state machine* around it — single-flight, `Running` published
 * before the work starts, the terminal state published after, a thrown exception becoming a refusal
 * rather than a crash — is the part that can be proved, and it is the part that was wrong. Same
 * split, same reason, as every other object in this package.
 *
 * ### What it deliberately does not survive
 *
 * Process death. A 358 MB copy is not worth a foreground service on a tier that ships to one owner
 * on one phone, so an in-flight import is lost and the next one starts over.
 *
 * What cleans up after it is worth stating precisely, because an earlier draft of this KDoc got it
 * wrong: within a live process the `.part` files are deleted by `importNpuAssetPair`'s own
 * `finally`, which runs on every failure, refusal and cancellation. Only after a process death is
 * there anything left for the next import to sweep, and that sweep is
 * `WhisperModelManager.reconcileStagingDebris` — whose real subject is the `.prev` files of an
 * interrupted finalise; it clears orphaned `.part` files on the same pass.
 */
object NpuImportController {

    /**
     * Process-scoped on purpose: the import must outlive every Activity, and a `SupervisorJob`
     * means one failed import cannot poison the scope for the next.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state =
        MutableStateFlow<NpuAssetImport.ImportState>(NpuAssetImport.ImportState.Idle)

    /** What the panel renders. Survives recreation because this object does. */
    val state: StateFlow<NpuAssetImport.ImportState> = _state.asStateFlow()

    @Volatile
    private var job: Job? = null

    /** True while a copy is in flight. */
    fun isRunning(): Boolean = job?.isActive == true

    /**
     * Start an import, unless one is already running.
     *
     * **Single-flight, and the guard is not decoration:** the picker can be re-opened while a copy
     * is in flight (the panel keeps the activity interactive, and a recreation re-arms the button
     * for one frame), and two concurrent imports would write the same two `.part` files from two
     * threads and then both try to finalise them.
     *
     * @param work the actual copy, given the progress callback to report through. It must not
     *        throw for a bad file — it returns [NpuAssetImport.ImportState.Refused] — but anything
     *        unexpected is caught here rather than crashing the process.
     * @return false when an import was already running and this call did nothing.
     */
    fun start(
        work: suspend (onProgress: (soFar: Long, total: Long) -> Unit) -> NpuAssetImport.ImportState,
    ): Boolean = synchronized(this) {
        if (isRunning()) return false
        // The PREVIOUS job, which may be a cancelled one still unwinding (micro-round 2, N4).
        // `cancel()` returns immediately, but the coroutine's `finally` — the one that deletes this
        // tier's `.part` files — runs afterwards, and a cancelled read blocked inside SAF can take
        // a while to get there. Two attempts write the SAME staging paths, so a new import that
        // starts in that gap races the dying one's cleanup and can have its freshly written bytes
        // deleted out from under it.
        //
        // Held by JOIN rather than by widening the guard, and the difference matters: a guard that
        // stayed closed until the finally had run would make "cancel, then immediately tap Import"
        // a silent no-op, which is exactly the I3 failure shape. This way the tap is accepted, the
        // panel shows Running at once, and the copy simply waits for its predecessor to finish
        // clearing the paths it is about to use.
        val previous = job
        // Published BEFORE the work is launched, so there is no window in which the button is back
        // and the import has already begun.
        _state.value = NpuAssetImport.ImportState.Running(0L, 0L)
        job = scope.launch {
            previous?.join()
            val outcome = try {
                work { soFar, total ->
                    _state.value = NpuAssetImport.ImportState.Running(soFar, total)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                NpuAssetImport.ImportState.Refused(
                    NpuAssetImport.unreadableRefusal("${t.javaClass.simpleName}: ${t.message}")
                )
            }
            _state.value = outcome
        }
        true
    }

    /**
     * Abandon a running import. The copy's own `finally` clears its `.part` files, so a retry
     * starts clean; the state returns to [NpuAssetImport.ImportState.Idle] immediately, because
     * from the user's point of view the import they cancelled is over the moment they say so.
     *
     * **The job reference is deliberately KEPT** (micro-round 2, N4). Nulling it here dropped the
     * only handle on a coroutine that had not finished deleting its staging files, so the next
     * import raced it for the same paths. [start] joins whatever is left here before it writes.
     */
    fun cancel() {
        job?.cancel()
        _state.value = NpuAssetImport.ImportState.Idle
    }
}
