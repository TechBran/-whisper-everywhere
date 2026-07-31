package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.TranscriptionEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cloud transcription over the SAME [TranscriptionEngine] contract the on-device engine satisfies.
 * The recorder and FloatingBubbleService are unchanged: they still call connect / sendAudio /
 * commit / awaitIdle / close and receive onSegmentResolved(seq, outcome).
 *
 * Four properties are load-bearing, and each of them is a bug the contract makes easy to write:
 *
 *  1. **[sendAudio] runs on the audio capture thread every ~32 ms and must never block.** It
 *     appends under the buffer lock and returns — no network I/O, no TLS handshake, no
 *     backpressure wait. That thread also drives the waveform, so blocking it both drops audio
 *     and visibly freezes the UI.
 *
 *  2. **seq is allocated INSIDE the same lock as the PCM snapshot**, exactly as
 *     [com.whispereverywhere.transcription.LocalWhisperEngine] does. Requests then complete in
 *     whatever order the network returns them, but identity was already fixed by AUDIO order, so
 *     [com.whispereverywhere.transcription.SegmentOrderer] can still release strictly in sequence.
 *     Local's orderer is a provable pass-through because it runs one segment at a time; here it is
 *     doing real work, which is the only reason [DEFAULT_MAX_IN_FLIGHT] above 1 is safe at all.
 *     Do not raise it without re-reading Release A.
 *
 *  3. **Every seq handed out by [commit] reaches onSegmentResolved exactly once** — success,
 *     failure, latched fatal, cancellation, or a scope that was already dead when commit() ran. A
 *     seq that never resolves stalls the orderer head forever and holds every later segment with
 *     it. Cancellation is the path that is easy to miss: a coroutine cancelled before its body is
 *     dispatched never executes a single line of it, so the guarantee cannot live in the body
 *     alone. It lives in [resolveOnce] + the job's completion handler, with an atomic claim making
 *     "at least once" into "exactly once".
 *
 *  4. **A fatal error latches.** Once the key is rejected or the credit is gone, retrying cannot
 *     help and every further request is a charge the user did not consent to. One fatal error must
 *     cost one request, not forty. [lastFatal] is what Task 5's fallback valve reads.
 *
 * An empty transcript is [SegmentOutcome.EmptyExpected], never a loss: the user said nothing.
 * Treating silence as a loss would stamp a "[…]" marker on the ordinary end of an ordinary session
 * and arm a fallback that re-runs the same silence for money to produce the same empty string.
 *
 * Never logs transcript content, headers, or anything derived from the key — lengths only.
 */
class CloudTranscriptionEngine(
    private val provider: SttProvider,
    private val scope: CoroutineScope,
    maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
    /**
     * How many committed-but-unresolved segments may pile up before further commits are shed to the
     * fallback instead of queued. See [commit] for why a bound is necessary at all.
     */
    private val maxBacklog: Int = maxInFlight * DEFAULT_BACKLOG_MULTIPLE,
) : TranscriptionEngine {

    private val bufferLock = Any()
    private val buffer = ByteArrayOutputStream()

    /**
     * Monotonic segment identity for the CURRENT session, allocated INSIDE [bufferLock] with the
     * PCM snapshot (see [commit]). Reset per session in [connect] because a fresh SegmentOrderer
     * starts at head 0.
     */
    private var nextSeq = 0L

    @Volatile private var language: String? = null
    @Volatile private var listener: TranscriptionEngine.Listener? = null

    /** Latched for the rest of the session once the account or key is proven unusable. */
    @Volatile private var fatal: SttError.Fatal? = null

    private val gate = Semaphore(maxInFlight)

    /**
     * Every seq that has been handed out and not yet resolved, with the listener that was attached
     * when it was cut. Resolutions are addressed to THAT listener rather than to whatever is
     * attached at completion time: seq restarts at 0 each session, so delivering a straggler to a
     * newer listener would look like the new session's first segment and corrupt its transcript.
     */
    private val pending = ConcurrentHashMap<Long, Pending>()

    private class Pending(val owner: TranscriptionEngine.Listener, val job: Job) {
        val claimed = AtomicBoolean(false)
    }

    /** The latched fatal error for this session, or null. Read by the cloud→local fallback valve. */
    fun lastFatal(): SttError.Fatal? = fatal

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        // A new session supersedes anything still in flight from the last one. This MUST run
        // before nextSeq resets, or the old session's seq 0 and the new one's would collide in
        // [pending] and one of them would never resolve.
        abandonOutstanding(SUPERSEDED)
        this.language = language
        this.listener = listener
        synchronized(bufferLock) {
            buffer.reset()
            nextSeq = 0L
        }
        // The user may have fixed the key or topped up between sessions, so the latch must not
        // outlive the session that earned it.
        fatal = null
        // Delivered synchronously, unlike the local engine's. Local defers onOpen to an executor
        // because it has a multi-hundred-megabyte model to mmap first; there is nothing to load
        // here, so making readiness depend on the coroutine scope still being alive would only
        // add a way to hang in CONNECTING forever. The service marshals to Main itself.
        listener.onOpen()
    }

    /**
     * Runs on the AUDIO CAPTURE THREAD every ~32 ms. Appends under the lock and returns — that is
     * the entire body, deliberately.
     *
     * Unlike the local engine there is no forced self-commit at a byte cap: the local cap exists
     * because a runaway buffer would eventually OOM before whisper ever saw it, whereas here
     * [SttProvider.maxRequestBytes] rejects an oversized segment locally, without a request, as
     * [SttError.BadSegment] — a visible loss rather than a crash. Adding a cap here would mean
     * running commit() on the audio thread.
     */
    override fun sendAudio(pcm: ByteArray) {
        synchronized(bufferLock) { buffer.write(pcm) }
    }

    override fun commit(): Long {
        val myListener = listener ?: return NO_SEGMENT

        // seq and the snapshot are taken under ONE lock acquisition. commit() is called from the
        // audio thread AND the main thread (stopRecording, source switch), so splitting them would
        // let two callers snapshot A-then-B and number them B-then-A.
        val (seq, pcm) = synchronized(bufferLock) {
            val snapshot = buffer.toByteArray()
            if (snapshot.isEmpty()) return NO_SEGMENT
            buffer.reset()
            (nextSeq++) to snapshot
        }
        val lang = language
        android.util.Log.i(TAG, "commit: seq=$seq pcmBytes=${pcm.size}")

        // BOUND THE BACKLOG. [gate] throttles concurrent UPLOADS, not commits: on a degraded link
        // three uploads can each sit for the whole call timeout while the VAD keeps cutting
        // segments, so commits arrive faster than they retire and the queue grows monotonically.
        // Each backlogged segment pins TWO independent arrays — the snapshot
        // FallbackTranscriptionEngine retains for a possible local retry, and this closure's own
        // copy — which is roughly 320 KB per 5 s segment and up to ~960 KB at the 15 s wall cap.
        // Nothing else sheds load, so the eventual outcome is an OutOfMemoryError on whichever
        // thread allocates next, frequently the audio capture thread.
        //
        // The local engine cannot do this: it drains faster than real time (measured 1.1-1.3 s for
        // a 3 s segment), so its queue is self-limiting. Cloud is this app's first unbounded queue.
        //
        // Shedding is a FLAG consumed inside the job, not an early resolution here, and that is
        // deliberate: commit() must not resolve re-entrantly, because FallbackTranscriptionEngine
        // sets up its retry bookkeeping for this seq only AFTER this call returns. The job then
        // resolves without taking a permit or uploading, so it completes at once and releases both
        // this closure's copy and the retained snapshot.
        //
        // Lost is the right outcome rather than a silent drop: FallbackPolicy.shouldFallBack(Lost)
        // is true, so under the fallback engine the segment is re-transcribed on-device — exactly
        // what "the network cannot keep up" should mean. The user gets their words from the local
        // model instead of a loss marker.
        val shed = pending.size >= maxBacklog
        if (shed) {
            android.util.Log.w(TAG, "backlog at $maxBacklog -> shedding seq=$seq to the fallback")
        }

        // LAZY + register + completion handler + start, in that order, is what makes the
        // exactly-once guarantee hold. Registering before the body can run means the body can
        // never race ahead and remove an entry that was not there yet; the completion handler
        // covers the case where the body never runs at all (scope already cancelled, or cancelled
        // before dispatch), which is precisely the cancellation hole that leaves a seq dangling.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outcome = try {
                val latched = fatal
                when {
                    // Do not even queue for a permit — the account is the problem.
                    latched != null -> SegmentOutcome.Lost(latched.message)
                    // Nor when the network is already further behind than it can recover from.
                    shed -> SegmentOutcome.Lost(BACKLOG)
                    else -> gate.withPermit { runOne(pcm, lang) }
                }
            } catch (c: CancellationException) {
                SegmentOutcome.Lost(CANCELLED)
            } catch (t: Throwable) {
                SegmentOutcome.Lost(t.message ?: "cloud transcription failed")
            }
            resolveOnce(seq, outcome)
        }
        pending[seq] = Pending(myListener, job)
        job.invokeOnCompletion { resolveOnce(seq, SegmentOutcome.Lost(CANCELLED)) }
        job.start()
        return seq
    }

    private suspend fun runOne(pcm: ByteArray, lang: String?): SegmentOutcome {
        // Re-checked after the permit, not only before it: with three in flight a whole backlog can
        // be queued on the semaphore when the first 401 comes back. Without this second check the
        // latch would still spend every queued request.
        fatal?.let { return SegmentOutcome.Lost(it.message) }

        return when (val result = provider.transcribe(pcm, lang)) {
            is SttResult.Text -> {
                // Lengths only — the transcript itself never reaches logcat.
                android.util.Log.i(TAG, "transcribed len=${result.text.length}")
                if (result.text.isBlank()) {
                    // Expected silence, NOT a loss: the user said nothing.
                    SegmentOutcome.EmptyExpected
                } else {
                    SegmentOutcome.Text(result.text.trim())
                }
            }
            is SttResult.Failed -> {
                (result.error as? SttError.Fatal)?.let { fatal = it }
                SegmentOutcome.Lost(describe(result.error))
            }
        }
    }

    private fun describe(error: SttError): String = when (error) {
        SttError.Offline -> "offline"
        is SttError.Fatal -> error.message
        is SttError.Transient -> "temporary provider error"
        SttError.BadSegment -> "segment rejected"
        SttError.Undecodable -> "unreadable provider response"
    }

    /**
     * Delivers the terminal outcome for [seq] to the listener that cut it, at most once, ever.
     *
     * Several racing callers can legitimately arrive here for the same seq — the coroutine body,
     * the job's completion handler, and [abandonOutstanding] — so the atomic claim, not the map
     * removal, is what makes it exactly once. The entry is removed only AFTER the callback
     * returns, so [awaitIdle] draining [pending] genuinely means every resolution has been
     * delivered rather than merely scheduled.
     */
    private fun resolveOnce(seq: Long, outcome: SegmentOutcome) {
        val entry = pending[seq] ?: return
        if (!entry.claimed.compareAndSet(false, true)) return
        try {
            entry.owner.onSegmentResolved(seq, outcome)
        } finally {
            pending.remove(seq)
        }
    }

    /**
     * Cancels every in-flight request and resolves its seq. Cancelling alone is not enough: the
     * caller is about to stop waiting, and a seq that never resolves stalls the orderer head
     * forever. Resolving here is synchronous so the guarantee holds by the time [close] returns,
     * even if the cancelled coroutines take a while to unwind.
     */
    private fun abandonOutstanding(reason: String) {
        if (pending.isEmpty()) return
        val seqs = pendingSeqs()
        seqs.forEach { pending[it]?.job?.cancel() }
        seqs.forEach { resolveOnce(it, SegmentOutcome.Lost(reason)) }
    }

    /**
     * Snapshot of the outstanding seqs, built by hand on purpose.
     *
     * `Collection.toList()` has a `size == 1` fast path that reads the size and THEN calls
     * `iterator().next()`. [pending] shrinks from other threads between those two steps, so on a
     * single outstanding request that fast path throws NoSuchElementException — reliably, not
     * rarely. Iterating a ConcurrentHashMap view directly is weakly consistent and safe.
     */
    private fun pendingSeqs(): List<Long> {
        val seqs = ArrayList<Long>(pending.size)
        for (seq in pending.keys) seqs.add(seq)
        return seqs
    }

    /**
     * Ends the session: clears the buffer, cancels and resolves anything still in flight, detaches
     * the listener and reports onClosed. The service calls [awaitIdle] first, so in the normal
     * path there is nothing outstanding to abandon; when the drain times out on a slow connection
     * the stragglers become visible losses rather than silent holes.
     */
    override fun close() {
        val owner = listener
        synchronized(bufferLock) { buffer.reset() }
        // Before detaching, so the resolutions reach the session that is still listening for them.
        abandonOutstanding(CANCELLED)
        listener = null
        owner?.onClosed()
    }

    /**
     * Blocks the CALLING thread until every already-committed segment has been resolved, or
     * [timeoutMs] elapses. This is the fence the service uses before close() detaches the listener,
     * so it must genuinely await the network rather than returning a hopeful true.
     *
     * MUST be called off the main thread. The scope is NOT cancelled on timeout — a request that
     * is merely slow keeps running and still resolves.
     */
    override fun awaitIdle(timeoutMs: Long): Boolean = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            while (pending.isNotEmpty()) {
                pendingSeqs().forEach { pending[it]?.job?.join() }
                // A job whose seq is resolved by the completion handler rather than by its own
                // body can be complete a moment before [pending] loses the entry; yield instead of
                // spinning on it. Bounded by the enclosing timeout either way.
                if (pending.isNotEmpty()) yield()
            }
            true
        } ?: false
    }

    /**
     * Permanent teardown. The coroutine scope belongs to the CALLER (the service owns its
     * lifetime and shares it with other work), so this ends the session without cancelling it.
     */
    override fun shutdown() = close()

    companion object {
        /**
         * Three concurrent uploads. Safe ONLY because Release A landed segment identity and strict
         * in-order release — without them, concurrent completion scrambles the user's text field
         * (injection is a full-field read-modify-write, so an out-of-order write silently deletes
         * the previous segment). Raising it also means more simultaneous TLS connections on a
         * phone radio for no latency win.
         */
        const val DEFAULT_MAX_IN_FLIGHT = 3

        /**
         * Backlog ceiling as a multiple of [DEFAULT_MAX_IN_FLIGHT], i.e. 24 segments by default.
         *
         * Chosen to be generous enough that an ordinary slow response never sheds — a burst of
         * short segments while three uploads are in flight has ample room — while still capping
         * retained audio at roughly 8-23 MB worst case rather than at "until the process dies".
         * Reaching it means uploads are retiring far slower than speech is arriving, which is a
         * connection that will not recover on its own.
         */
        const val DEFAULT_BACKLOG_MULTIPLE = 8

        private const val TAG = "WE-DIAG"

        /** commit() cut nothing, so no seq was allocated and nothing is owed a resolution. */
        private const val NO_SEGMENT = -1L

        private const val CANCELLED = "cancelled"
        private const val SUPERSEDED = "session restarted"
        private const val BACKLOG = "network too far behind"
    }
}
