package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.TranscriptionEngine
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure decisions about a cloud segment's second chance. Kept free of engine state so both halves
 * can be reasoned about — and tested — without a network, a model or a thread.
 */
object FallbackPolicy {

    /** Is this outcome worth a second attempt on the local engine? */
    fun shouldFallBack(outcome: SegmentOutcome): Boolean = when (outcome) {
        is SegmentOutcome.Lost -> true
        SegmentOutcome.EmptyUnexpected -> true
        // The user said nothing. Re-running silence burns seconds to produce the same empty
        // string — and would have cost money on the cloud path too.
        SegmentOutcome.EmptyExpected -> false
        is SegmentOutcome.Text -> false
    }

    /**
     * Which outcome the user actually sees once the local retry has answered.
     *
     * Two local answers win over the cloud's loss, and they are the two the local engine can only
     * produce by actually having LOOKED at the audio:
     *
     *  - a real transcript — the rescue worked;
     *  - [SegmentOutcome.EmptyExpected] — whisper ran on the very same PCM the cloud lost, its
     *    Silero VAD found no speech in it, and a segment with no speech in it cannot be a lost
     *    sentence. Keeping the cloud's [SegmentOutcome.Lost] here is what put a "[…]" marker at
     *    the end of EVERY live session (owner-reported 2026-07-31): the tail turn between the
     *    user's last word and the stop tap is trailing room tone, the server never transcribed it,
     *    and whisper correctly said there was nothing there — so the marker claimed a sentence had
     *    vanished when none had existed.
     *
     * This trust is only sound because
     * [com.whispereverywhere.transcription.LocalWhisperEngine] reserves EmptyExpected for that one
     * verdict: its two "whisper never ran" branches (no model loaded, transcribe threw) resolve
     * Lost, so they fall to the `else` here and the cloud's visible loss survives. Loosening
     * either side re-opens the silent-vanish hole this asymmetry used to guard.
     */
    fun reconcile(cloudOutcome: SegmentOutcome, localOutcome: SegmentOutcome): SegmentOutcome = when {
        localOutcome is SegmentOutcome.Text && localOutcome.text.isNotBlank() -> localOutcome
        localOutcome is SegmentOutcome.EmptyExpected -> SegmentOutcome.EmptyExpected
        else -> cloudOutcome
    }
}

/**
 * Runs [cloud] first and retries failed segments on [local], preserving seq so the orderer still
 * releases in order.
 *
 * THE VALVE IS ONE-WAY. This decorator only ever goes cloud -> local, never local -> cloud. A user
 * who chose on-device must never have audio shipped off-device because a local load failed. That is
 * structural here, not a check: [local] is reached only from [CloudRelay], and [LocalRelay] has no
 * path back to [cloud] at all.
 *
 * It keeps its OWN copy of each segment's PCM because [cloud] consumes and discards the audio on
 * commit(); once cloud reports a loss the audio is gone, and you cannot fall back to audio you no
 * longer have. The copy costs one extra buffer of the same size the cloud engine already holds, and
 * each committed snapshot is retained only until its seq resolves.
 *
 * Three invariants carry the whole class:
 *
 *  1. **[sendAudio] never blocks.** It runs on the audio capture thread every ~32 ms, and that same
 *     thread draws the waveform. Two buffer appends under one lock, then return.
 *
 *  2. **Every seq [commit] hands out reaches onSegmentResolved exactly once.** The retry path is
 *     where that is easy to lose, because [local] owns its own seq counter and its resolutions must
 *     be re-labelled with the ORIGINAL seq. Every give-up path — local refuses the segment, local
 *     throws, the session closes or restarts mid-retry — still owes that one resolution, and an
 *     atomic claim per retry makes "at least once" into "exactly once".
 *
 *  3. **Resolutions are addressed to the listener that cut the segment**, not to whatever is
 *     attached when the retry finishes. seq restarts at 0 every session, so delivering a straggler
 *     to a newer listener would look like the new session's first segment and corrupt its
 *     transcript. Both relays capture their owner.
 *
 * NOTE for the caller: the [CoroutineScope] handed to [cloud] must not be `Dispatchers.Unconfined`.
 * [commit] calls `cloud.commit()` inside the mirror lock so the two buffers are cut atomically, and
 * an unconfined dispatcher would run the upload coroutine inline on the audio thread.
 */
class FallbackTranscriptionEngine(
    private val cloud: TranscriptionEngine,
    private val local: TranscriptionEngine,
    /**
     * Part of the constructor contract for the engine factory, deliberately unused. Hopping the
     * retry onto a coroutine would BREAK a property [awaitIdle] depends on: the retry is submitted
     * to [local] synchronously inside cloud's own resolution callback, so once cloud has drained,
     * every retry this session will ever start is already queued on local.
     */
    @Suppress("unused") private val scope: CoroutineScope,
) : TranscriptionEngine {

    /**
     * Guards the mirror AND the paired cut in [commit]. Also the outer lock whenever both this and
     * the cloud engine's own buffer lock are held: mirrorLock -> cloud's, never the reverse.
     */
    private val mirrorLock = Any()
    private val mirror = ByteArrayOutputStream()

    /** seq -> that segment's PCM, held only until the cloud engine reports how the segment ended. */
    private val retained = ConcurrentHashMap<Long, ByteArray>()

    /**
     * Guards [retries] and [submitting] together. Held across `local.commit()` so a retry cannot
     * resolve before it has been registered — see [localRetry].
     */
    private val retryLock = Any()

    /** local seq -> the retry it belongs to. Guarded by [retryLock]. */
    private val retries = HashMap<Long, Retry>()

    /** The retry whose `local.commit()` has not returned yet. Guarded by [retryLock]. */
    private var submitting: Retry? = null

    /**
     * False outside a live session. Set false FIRST in [close], [shutdown] and [connect] because
     * each of those makes the cloud engine resolve everything still in flight as a loss, and
     * arming a retry there would hand audio to a local session that is about to be detached.
     */
    @Volatile private var accepting = false

    /**
     * One segment's second attempt. [claimed] is what makes the resolution exactly-once across the
     * three callers that can legitimately race for it: the local engine's callback, the give-up
     * branches in [localRetry], and [abandonRetries].
     */
    private class Retry(
        val originalSeq: Long,
        val cloudOutcome: SegmentOutcome,
        val owner: TranscriptionEngine.Listener,
    ) {
        val claimed = AtomicBoolean(false)

        fun resolve(localOutcome: SegmentOutcome) {
            if (!claimed.compareAndSet(false, true)) return
            owner.onSegmentResolved(originalSeq, FallbackPolicy.reconcile(cloudOutcome, localOutcome))
        }

        /** Give up on the retry and surface what the cloud said, which reconcile() returns as-is. */
        fun giveUp() = resolve(cloudOutcome)
    }

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        // Stop arming retries before anything else disturbs the previous session.
        accepting = false
        // Owed to the OLD listener, and owed before local.connect() below detaches the local
        // session those retries were running in — after that they can never call back.
        abandonRetries()
        synchronized(mirrorLock) { mirror.reset() }
        // Cleared BEFORE cloud.connect(), which abandons the previous session's in-flight segments
        // as losses. With no retained PCM those forward straight through to the listener that cut
        // them instead of starting a retry on audio from a session that is over.
        retained.clear()

        // local first: its connect() enqueues the model load on its executor, so a retry committed
        // moments later queues behind that load rather than finding no context. Its relay swallows
        // every lifecycle callback — a cloud user must never be shown "No speech model installed"
        // for a safety net they never asked about.
        local.connect(language, LocalRelay())
        accepting = true
        cloud.connect(language, CloudRelay(listener))
    }

    /**
     * AUDIO CAPTURE THREAD, every ~32 ms, and the same thread drives the waveform. Two buffer
     * appends under one lock and return: no I/O, no waiting, no allocation beyond the copies the
     * buffers make anyway.
     *
     * `cloud.sendAudio` is called INSIDE the mirror lock on purpose. It is what keeps the two
     * buffers byte-identical, which is what lets [commit] cut them together and know that the seq
     * cloud hands back names exactly the bytes in our snapshot.
     */
    override fun sendAudio(pcm: ByteArray) {
        synchronized(mirrorLock) {
            mirror.write(pcm)
            cloud.sendAudio(pcm)
        }
    }

    /**
     * Cuts both buffers as one operation.
     *
     * Holding [mirrorLock] across `cloud.commit()` adds no waiting to the audio thread that was not
     * already there — that thread is the one calling commit() in the hot path — but it does stop
     * the main thread's commit (stopRecording, source switch) from interleaving a cut between our
     * snapshot and cloud's, which would retain the wrong bytes for the wrong seq.
     *
     * Cutting cloud FIRST also means a commit cloud refuses (-1: no session, or nothing buffered)
     * leaves the mirror intact instead of throwing away audio the cloud engine is still holding.
     */
    override fun commit(): Long {
        synchronized(mirrorLock) {
            val snapshot = mirror.toByteArray()
            val seq = cloud.commit()
            if (seq < 0) return seq
            mirror.reset()
            // An empty snapshot with a real seq would mean the buffers had diverged; retaining
            // nothing simply means that seq cannot fall back, which is safe.
            if (snapshot.isNotEmpty()) retained[seq] = snapshot
            return seq
        }
    }

    /** Everything the user sees comes from here. [owner] is the listener that opened the session. */
    private inner class CloudRelay(private val owner: TranscriptionEngine.Listener) :
        TranscriptionEngine.Listener {

        override fun onOpen() = owner.onOpen()
        override fun onDelta(text: String) = owner.onDelta(text)
        override fun onError(message: String) = owner.onError(message)
        override fun onClosed() = owner.onClosed()

        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            // Synchronized with commit(), which holds mirrorLock from before cloud.commit() until
            // after the snapshot lands in [retained]. A cloud engine that resolves a fresh seq
            // near-instantly — the fatal latch and the backlog shed both do, with no permit and no
            // network — can deliver this callback inside that window; without the lock the lookup
            // finds nothing, skips the local retry, and the user gets a loss marker for audio we
            // were holding the whole time. Proven on-device 2026-07-29: with a latched fatal,
            // every VAD segment lost this race. Blocking here for the microseconds commit() needs
            // to finish is the fix; no deadlock is possible because cloud.commit() never waits on
            // its resolver thread.
            val pcm = synchronized(mirrorLock) { retained.remove(seq) }
            if (!accepting || pcm == null || !FallbackPolicy.shouldFallBack(outcome)) {
                owner.onSegmentResolved(seq, outcome)
                return
            }
            // Retry this seq locally. The orderer holds later segments until it resolves, which is
            // exactly the intended behaviour: bursty-but-correct beats fast-but-deleted.
            localRetry(Retry(seq, outcome, owner), pcm)
        }
    }

    /**
     * The local engine owns its own seq counter, so its resolutions cannot be forwarded directly.
     * Retries are tracked here and re-labelled with the ORIGINAL seq.
     *
     * Every lifecycle callback is swallowed. The safety net is invisible: it emits no deltas, it
     * did not open this session, and its errors are about an engine the user did not choose.
     */
    private inner class LocalRelay : TranscriptionEngine.Listener {
        override fun onOpen() = Unit
        override fun onDelta(text: String) = Unit
        override fun onError(message: String) = Unit
        override fun onClosed() = Unit

        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            val retry = synchronized(retryLock) {
                // Two arrival orders, both real:
                //
                //  * asynchronous — local.commit() has already returned and registered the mapping.
                //    The lock is what guarantees "already": [localRetry] holds it across commit()
                //    AND the registration, so an engine thread arriving here waits for both.
                //
                //  * synchronous — the local engine resolved INSIDE commit(), before there was a
                //    seq to map it against. That is not contrived: LocalWhisperEngine resolves on
                //    its executor, and a same-thread executor lands here RE-ENTRANTLY on the
                //    submitting thread, which still holds this lock. [submitting] is that retry.
                //    Missing it would leave the ORIGINAL seq unresolved and stall the orderer head
                //    forever, holding every later segment with it.
                retries[seq] ?: submitting
            }
            // Not ours: a straggler from a session that has already been abandoned, and one that
            // was resolved at abandon time. `claimed` makes a double delivery a no-op regardless.
            retry?.resolve(outcome)
            // Removed only AFTER resolve() returns: the ledger is a DELIVERY fence, not a
            // scheduling fence — the same discipline as CloudTranscriptionEngine.resolveOnce, for
            // the same reason. awaitIdle's happy-path skip consults retriesOutstanding(), and
            // "left the ledger" must imply "delivered", or a stop landing in the gap flushes the
            // orderer and detaches the sink while the rescued text is mid-delivery. Removing a
            // key that was never registered (the synchronous [submitting] arrival) is a no-op,
            // and a concurrent abandon's double resolve is already absorbed by `claimed`.
            synchronized(retryLock) { retries.remove(seq) }
        }
    }

    /**
     * Hands one segment's audio to the local engine and remembers which original seq the answer
     * belongs to.
     *
     * The registration MUST be atomic with `local.commit()`, hence one lock across both. Splitting
     * them leaves a window in which the local engine resolves a seq that is not in [retries] yet —
     * and on a same-thread executor that window is not a race but the guaranteed order.
     */
    private fun localRetry(retry: Retry, pcm: ByteArray) {
        var giveUp = false
        synchronized(retryLock) {
            submitting = retry
            try {
                local.sendAudio(pcm)
                val localSeq = local.commit()
                when {
                    // Resolved re-entrantly inside commit(); there is nothing left to map.
                    retry.claimed.get() -> Unit
                    // Local could not accept it (no session, nothing buffered, executor gone).
                    // Surface the cloud outcome rather than stalling on an answer that will never
                    // come. Resolved outside the lock, below.
                    localSeq < 0 -> giveUp = true
                    else -> retries[localSeq] = retry
                }
            } catch (t: Throwable) {
                // A throwing local engine must not become an unresolvable seq.
                android.util.Log.w(TAG, "local retry submission failed", t)
                giveUp = true
            } finally {
                submitting = null
            }
        }
        // Outside the lock: this callback runs the orderer and, through it, the service's typing.
        if (giveUp) retry.giveUp()
    }

    /**
     * Resolves every outstanding retry with the outcome its cloud attempt produced. Called whenever
     * the local session is about to be detached — a retry that is still in flight then can never
     * call back, and a seq that never resolves stalls the orderer head forever.
     */
    private fun abandonRetries() {
        val outstanding = synchronized(retryLock) {
            // A plain HashMap under this lock, so unlike a ConcurrentHashMap view this snapshot
            // cannot shrink underneath the copy.
            val all = ArrayList(retries.values)
            retries.clear()
            submitting = null
            all
        }
        // Outside the lock, for the same reason as in [localRetry].
        outstanding.forEach { it.giveUp() }
    }

    /**
     * Ends the session. Retries are resolved BEFORE cloud.close() so every resolution this session
     * owes has been delivered by the time the cloud engine reports onClosed.
     */
    override fun close() {
        accepting = false
        synchronized(mirrorLock) { mirror.reset() }
        retained.clear()
        abandonRetries()
        // Resolves anything still in flight as a loss. accepting is already false, so those
        // forward straight to the listener instead of arming a retry on a dying session.
        cloud.close()
        local.close()
    }

    override fun shutdown() {
        accepting = false
        synchronized(mirrorLock) { mirror.reset() }
        retained.clear()
        abandonRetries()
        cloud.shutdown()
        local.shutdown()
    }

    /**
     * Warms BOTH engines. The safety net has to be warm before it is needed: loading a
     * multi-hundred-megabyte model inside the first fallback would add seconds to a segment that is
     * already late, and the fallback only ever fires when something has already gone wrong.
     */
    override fun prewarm() {
        cloud.prewarm()
        local.prewarm()
    }

    override fun releaseContext() {
        cloud.releaseContext()
        local.releaseContext()
    }

    /**
     * Drains cloud FIRST, then local, and that order is load-bearing rather than arbitrary: a retry
     * is submitted to local synchronously inside cloud's own resolution callback, so by the time
     * cloud reports drained, every retry this session will ever start is already queued on local.
     * Draining local first would race the work it is supposed to wait for.
     *
     * That same property makes the happy-path skip sound: cloud drained means the retry ledger can
     * only shrink from here. If it is EMPTY, local owes this session nothing — every seq the
     * session handed out has already resolved AND been delivered ([LocalRelay.onSegmentResolved]
     * removes a ledger entry only after its resolve() returns: the same delivery-fence discipline
     * as CloudTranscriptionEngine.resolveOnce, and load-bearing for this skip) — and the only work
     * its executor can still be running is the safety-net model (re)load connect()/prewarm() enqueued. Fencing behind that
     * load made a short cloud session's stop pay up to the whole load (~7 s on Adreno OpenCL) for
     * a result nothing pending needed (owner-reported finalize lag, spec 2026-08-18 C2). Nothing
     * is cancelled — the load keeps running and the net stays warm — we just stop waiting for it.
     * FallbackPolicy.reconcile and the SegmentOrderer contracts are untouched: no outcome changes,
     * only a wait on an executor that owes no resolutions is dropped.
     *
     * The two drains share ONE budget by deadline rather than splitting it in half, so a fast cloud
     * drain leaves the local retry almost all of the time. If cloud consumes the whole budget the
     * result is false regardless of what local reports.
     */
    override fun awaitIdle(timeoutMs: Long): Boolean {
        val budget = timeoutMs.coerceIn(0L, MAX_DRAIN_MS)
        val deadlineNs = System.nanoTime() + budget * NANOS_PER_MS
        val cloudDrained = cloud.awaitIdle(budget)
        if (cloudDrained && !retriesOutstanding()) {
            // Same phase name as LocalWhisperEngine.awaitIdle's C1 line, so the owner's
            // finalize-timing log always shows all six phases.
            android.util.Log.i(TAG, "finalize-timing: local-drain=0ms (skipped: no outstanding retries)")
            return true
        }
        val remainingMs = ((deadlineNs - System.nanoTime()) / NANOS_PER_MS).coerceAtLeast(0L)
        val localDrained = local.awaitIdle(remainingMs)
        return cloudDrained && localDrained
    }

    /**
     * True while local still owes this session a resolution: an armed retry not yet answered, or a
     * [localRetry] whose local.commit() has not returned. Meaningful as a skip guard ONLY after a
     * full cloud drain — before that, cloud callbacks can still arm new retries.
     */
    private fun retriesOutstanding(): Boolean =
        synchronized(retryLock) { retries.isNotEmpty() || submitting != null }

    private companion object {
        const val TAG = "WE-DIAG"
        const val NANOS_PER_MS = 1_000_000L

        /** Guards the deadline arithmetic against overflow; far beyond any real drain. */
        const val MAX_DRAIN_MS = 60L * 60L * 1000L
    }
}
