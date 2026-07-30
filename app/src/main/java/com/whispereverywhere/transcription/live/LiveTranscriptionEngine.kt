package com.whispereverywhere.transcription.live

import com.whispereverywhere.recording.Resampler
import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.TranscriptionEngine
import com.whispereverywhere.transcription.cloud.FatalKind
import com.whispereverywhere.tts.cloud.PcmBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Word-for-word live transcription over the OpenAI Realtime WebSocket, satisfying the SAME
 * [TranscriptionEngine] contract the on-device and batch-POST engines satisfy. The recorder and
 * FloatingBubbleService are unchanged: they still call connect / sendAudio / commit / awaitIdle /
 * close and receive onSegmentResolved(seq, outcome). What changes is the transport — a socket that
 * streams audio and returns partials — and one addition: partials reach [TranscriptionEngine.Listener.onDelta]
 * for the live preview strip and NEVER inject.
 *
 * This engine is the [com.whispereverywhere.transcription.cloud.CloudTranscriptionEngine] discipline
 * ported from a POST to a socket, so the same four properties carry it, plus one the socket forces:
 *
 *  1. **[sendAudio] runs on the audio capture thread every ~32 ms and must never block.** It appends
 *     one op to the send queue under [bufferLock] and returns. The upsample→base64→WS send happens
 *     on a separate sender coroutine ([senderLoop]); if that coroutine is wedged on a saturated
 *     socket, [sendAudio] still returns at once — it never touches the socket.
 *
 *  2. **seq is allocated INSIDE [bufferLock] as the turn is cut**, synchronously, exactly as batch
 *     does. [com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngine] wraps this engine
 *     and cuts its mirror buffer under its own lock across `cloud.commit()`; that pairing only holds
 *     if the seq this engine hands back names the turn it just cut, with no async gap.
 *
 *  3. **Every seq handed out by [commit] reaches onSegmentResolved exactly once** — on `completed`,
 *     on a duplicate/out-of-order `completed`, on a WS drop, on a latched fatal, on backpressure, and
 *     on close. A seq that never resolves stalls the orderer head forever. [resolveOnce] plus a
 *     per-turn atomic claim make "at least once" into "exactly once" across every racing caller.
 *
 *  4. **A fatal error latches.** Once the key is rejected or the credit is gone, retrying cannot help
 *     and every further commit is a charge the user did not consent to. [lastFatal] is what the
 *     service's latch-toast reads and what Task 5's valve keys on.
 *
 *  5. **An instant resolution must not out-run the fallback's mirror.** The fatal and backpressure
 *     paths resolve their seq via [scope].launch, NOT synchronously inside [commit]. Resolving inline
 *     would fire `CloudRelay.onSegmentResolved` before FallbackTranscriptionEngine has stored the
 *     retained PCM for that seq, and the segment would be lost as a marker instead of rescued
 *     locally — the exact race FallbackTranscriptionEngine's mirrorLock fix closes (recon §3). The
 *     caller's scope must NOT be `Dispatchers.Unconfined`, for the same reason batch requires it.
 *
 * item_id↔seq: OpenAI does NOT guarantee ordering between completion events from different turns
 * ("Use item_id"). Turns commit in order, and each turn's item_id first surfaces in order (its
 * deltas stream before the next turn's), so the first `delta`/`completed` naming an unmapped item_id
 * binds it to the OLDEST unbound pending seq. Completions then resolve their bound seq in any order.
 *
 * Never logs transcript content, the key, or audio — lengths and codes only.
 */
class LiveTranscriptionEngine(
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val maxBacklog: Int = DEFAULT_MAX_BACKLOG,
    /**
     * Builds the transport wired to the engine's own listener. A factory, not a ready-made transport,
     * because [RealtimeTransport] takes its listener at construction and the listener IS this engine
     * — the two cannot be built in either order otherwise. Production passes
     * `{ listener -> LiveTranscriptionEngine.realTransport(RealtimeTransport(factory, scheduler, listener)) }`;
     * tests pass a fake. (Deviation from the plan's `LiveTranscriptionEngine(RealtimeTransport(…), …)`
     * sketch, forced by that construction order — reported.)
     */
    makeTransport: (RealtimeTransport.Listener) -> Transport,
) : TranscriptionEngine {

    /**
     * The four outbound operations this engine needs from the socket. [RealtimeTransport] already has
     * exactly these signatures; [realTransport] adapts it. Narrowing to an interface is what lets a
     * JVM test drive a fake socket with no OkHttp.
     */
    interface Transport {
        fun connect(apiKey: String, language: String?)
        fun sendAppend(base64: String): Boolean
        fun sendCommit(): Boolean
        fun close()
    }

    private val transportListener = TransportListener()
    private val transport: Transport = makeTransport(transportListener)

    // -------- capture thread / sender (guarded by bufferLock) --------

    private val bufferLock = Any()
    private val sendQueue = ArrayDeque<SendOp>()
    /** Count of un-drained [SendOp.Append] ops; the backpressure signal. Guarded by [bufferLock]. */
    private var queuedAppends = 0
    /** Any audio captured since the last cut. This — not the drained queue — is commit()'s emptiness. */
    private var turnHasAudio = false
    /** The current turn overflowed the backlog and cannot be delivered whole. */
    private var turnShed = false
    /** Monotonic identity for the CURRENT session, allocated with the cut. Reset per [connect]. */
    private var nextSeq = 0L

    /** Wakes [senderLoop] when work is enqueued. Conflated: many enqueues collapse to one signal. */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var senderJob: Job? = null

    // -------- correlation + resolution (guarded by correlationLock) --------

    private val correlationLock = Any()
    /** seq -> turn, in commit (insertion) order so the oldest unbound turn is the first value. */
    private val pending = LinkedHashMap<Long, PendingTurn>()
    /** item_id -> seq, once a turn's item_id has first surfaced. */
    private val itemToSeq = HashMap<String, Long>()

    @Volatile private var listener: TranscriptionEngine.Listener? = null
    @Volatile private var language: String? = null
    /** Latched for the rest of the session once the key or account is proven unusable. */
    @Volatile private var fatal: FatalKind? = null

    private class PendingTurn(
        val seq: Long,
        val owner: TranscriptionEngine.Listener,
        /** False for fatal/shed turns: they own no real item and must never absorb a stray completed. */
        val bindable: Boolean,
    ) {
        var itemId: String? = null // guarded by correlationLock
        val claimed = AtomicBoolean(false)
    }

    private sealed interface SendOp {
        class Append(val pcm: ByteArray) : SendOp
        /** Carries the seq it finalizes so a commit that fails to send (socket down) can resolve it. */
        class Commit(val seq: Long) : SendOp
    }

    /** The latched fatal for this session, or null. Read by the service's latch-toast and Task 5's valve. */
    fun lastFatal(): FatalKind? = fatal

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        // Supersede anything still in flight from the previous session BEFORE nextSeq resets, or the
        // old session's seq 0 and the new one's would collide in [pending] and one would never resolve.
        abandonOutstanding(SUPERSEDED)
        senderJob?.cancel()
        clearSendBuffer()
        synchronized(bufferLock) { nextSeq = 0L }
        // The user may have fixed the key or topped up between sessions, so the latch must not outlive
        // the session that earned it.
        fatal = null
        this.language = language
        this.listener = listener
        senderJob = scope.launch { senderLoop() }
        transport.connect(apiKey, language)
        // Delivered synchronously (batch-compatible): the recorder may start buffering audio at once.
        // The socket handshake completes in the background; audio captured before it is live is
        // dropped by sendAppend and the turn, coming back short, is rescued by the fallback.
        listener.onOpen()
    }

    /**
     * AUDIO CAPTURE THREAD, every ~32 ms, same thread that draws the waveform. Enqueue one op under
     * the lock and return — no upsample, no base64, no socket.
     *
     * Backpressure: if the sender is behind by more than [maxBacklog] un-drained appends, the socket
     * cannot keep up. Mark the current turn shed and DROP this chunk rather than grow the queue
     * without bound. The turn still "has audio", so commit() will allocate its seq and resolve it
     * Lost(BACKLOG) — a visible loss the fallback re-transcribes locally, never a block or a crash.
     */
    override fun sendAudio(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        synchronized(bufferLock) {
            turnHasAudio = true
            if (queuedAppends >= maxBacklog) {
                turnShed = true
                return
            }
            sendQueue.addLast(SendOp.Append(pcm))
            queuedAppends++
        }
        wakeups.trySend(Unit)
    }

    override fun commit(): Long {
        val owner = listener ?: return NO_SEGMENT
        val latched = fatal
        var seq: Long
        var shed: Boolean
        synchronized(bufferLock) {
            if (!turnHasAudio) return NO_SEGMENT
            seq = nextSeq++
            shed = turnShed
            turnHasAudio = false
            turnShed = false
            // Only a deliverable turn tells the server to finalize. A shed or fatal turn sends no
            // commit — its audio is gone or its session is dead — so the server raises no item for it.
            if (latched == null && !shed) sendQueue.addLast(SendOp.Commit(seq))
        }
        if (latched == null && !shed) wakeups.trySend(Unit)

        val bindable = latched == null && !shed
        synchronized(correlationLock) { pending[seq] = PendingTurn(seq, owner, bindable) }

        // Resolve the dead-on-arrival turns OFF this thread. Doing it inline would fire the owner's
        // callback before FallbackTranscriptionEngine.commit() (which is calling us under its mirror
        // lock) has retained this seq's PCM — see property 5. The scope is not Unconfined, so the
        // launch runs after commit() returns and after the mirror snapshot lands.
        when {
            latched != null -> scope.launch { resolveOnce(seq, SegmentOutcome.Lost(reasonFor(latched))) }
            shed -> scope.launch { resolveOnce(seq, SegmentOutcome.Lost(BACKLOG)) }
        }
        return seq
    }

    /** Drains [sendQueue] off the capture thread: upsample 16k→24k, LE-encode, base64, send. */
    private suspend fun senderLoop() {
        while (currentScopeIsActive()) {
            val op = synchronized(bufferLock) {
                val next = sendQueue.removeFirstOrNull()
                if (next is SendOp.Append) queuedAppends--
                next
            }
            if (op == null) {
                wakeups.receive() // suspends (cancellable) until sendAudio/commit enqueues work
                continue
            }
            when (op) {
                is SendOp.Append ->
                    // A false append is the transport's backpressure/socket-down signal. Shed the
                    // current turn so commit() resolves it Lost(BACKLOG) for the local fallback,
                    // rather than pretend the audio reached the server. This is the shed the
                    // engine's own maxBacklog cannot see — that only guards a CPU-starved sender,
                    // not a live-but-stalled socket where our queue drains but OkHttp's does not.
                    if (!transport.sendAppend(encodeAppend(op.pcm))) markTurnShed()
                is SendOp.Commit ->
                    // A commit that cannot be sent (socket down in the reconnect gap or the pre-onOpen
                    // handshake window) means the server will NEVER raise an item for this turn. Left
                    // alone the seq sits bindable in `pending` forever — the next real turn's item
                    // binds to it, poisoning correlation off-by-one for the rest of the session, and
                    // the tail seq never resolves (orderer stall / awaitIdle timeout). Resolve it Lost
                    // so the fallback rescues it from the mirror and correlation stays aligned.
                    if (!transport.sendCommit()) resolveOnce(op.seq, SegmentOutcome.Lost(WS_DROP))
            }
        }
    }

    /** Mark the in-flight turn undeliverable; commit() will resolve it Lost(BACKLOG) off-thread. */
    private fun markTurnShed() = synchronized(bufferLock) { turnShed = true }

    private suspend fun currentScopeIsActive(): Boolean =
        kotlin.coroutines.coroutineContext[Job]?.isActive ?: true

    /** 16 kHz PCM16 bytes -> 24 kHz PCM16 -> base64, the format `gpt-live-transcribe` expects. */
    private fun encodeAppend(pcm: ByteArray): String {
        val out24k = Resampler.upsample16kTo24k(PcmBytes.toShortArrayLE(pcm))
        return Base64.getEncoder().encodeToString(shortsToBytesLE(out24k))
    }

    // -------- inbound (transport callback thread) --------

    private inner class TransportListener : RealtimeTransport.Listener {
        override fun onConnected() {
            // onOpen already fired in connect(); nothing to surface here. No content ever logged.
            android.util.Log.i(TAG, "realtime session live")
        }

        override fun onDelta(itemId: String, text: String) {
            bindItem(itemId) // first mention binds the item to the oldest unbound seq, in order
            listener?.onDelta(text) // preview strip ONLY — deltas never resolve, never inject
        }

        override fun onCompleted(itemId: String, transcript: String) {
            val seq = bindItem(itemId) ?: return
            resolveOnce(seq, outcomeFor(transcript))
        }

        override fun onErrorEvent(code: String?, messageLength: Int) {
            // Code + length only — the message can echo request detail and never crosses this line.
            android.util.Log.w(TAG, "realtime error code=${code ?: "?"} len=$messageLength")
            mapErrorCode(code)?.let { latchFatal(it) }
        }

        override fun onDisconnected() {
            // The server buffer is gone; the transport reconnects itself. Outstanding turns can never
            // complete, so resolve them Lost for the fallback to rescue from the mirror. nextSeq is
            // NOT reset — the orderer runs continuously across a reconnect.
            //
            // Clear the send buffer too: a pre-drop turn's queued Commit (and trailing appends) must
            // NOT survive to flush onto the reconnected socket. That turn is already being resolved
            // Lost here; a stale Commit crossing over would commit the fresh socket's partial buffer
            // into a phantom item that mis-correlates to a later seq. Same reset connect()/close() do.
            clearSendBuffer()
            abandonOutstanding(WS_DROP)
        }

        override fun onFatal(kind: FatalKind, code: Int) {
            android.util.Log.w(TAG, "realtime fatal kind=$kind code=$code")
            latchFatal(kind)
        }
    }

    /** Binds [itemId] to the oldest unbound bindable pending seq, or returns its existing binding. */
    private fun bindItem(itemId: String): Long? = synchronized(correlationLock) {
        itemToSeq[itemId]?.let { return it }
        val turn = pending.values.firstOrNull { it.bindable && it.itemId == null } ?: return null
        turn.itemId = itemId
        itemToSeq[itemId] = turn.seq
        turn.seq
    }

    private fun outcomeFor(transcript: String): SegmentOutcome {
        val trimmed = transcript.trim()
        // A committed live turn had voiced audio (client VAD cut it), so an empty transcript is a
        // lost sentence the user must see — EmptyUnexpected, which the fallback re-runs locally.
        return if (trimmed.isEmpty()) SegmentOutcome.EmptyUnexpected else SegmentOutcome.Text(trimmed)
    }

    private fun latchFatal(kind: FatalKind) {
        fatal = kind
        // A session-level fatal dooms every in-flight turn; resolve them Lost so none stalls the
        // orderer. Later commits fail fast in commit() via the latch.
        abandonOutstanding(reasonFor(kind))
        // Retrying cannot help once the key/credit is proven unusable, so stop spending: drop any
        // queued audio and CLOSE the socket. Without this, sendAudio keeps enqueuing appends that
        // the sender keeps pushing to a live socket for the rest of the session — wasted work on a
        // doomed session, and the socket never gives up. connect() reopens for the next session.
        clearSendBuffer()
        transport.close()
    }

    /** In-band error `code` -> latched fatal, or null for a transient/unknown error we do not latch. */
    private fun mapErrorCode(code: String?): FatalKind? = when {
        code == null -> null
        code.contains("api_key", true) || code.contains("invalid_key", true) -> FatalKind.INVALID_KEY
        code.contains("quota", true) || code.contains("insufficient", true) ||
            code.contains("billing", true) -> FatalKind.OUT_OF_CREDIT
        code.contains("forbidden", true) || code.contains("permission", true) -> FatalKind.FORBIDDEN
        code.contains("model", true) -> FatalKind.MODEL_UNAVAILABLE
        else -> null
    }

    // -------- resolution (exactly-once) --------

    /**
     * Delivers the terminal outcome for [seq] to the listener that cut it, at most once, ever. The
     * atomic claim — not the map removal — is what makes it exactly-once across the racing callers:
     * a completion, a duplicate completion, [abandonOutstanding], and the deferred fatal/shed launch.
     */
    private fun resolveOnce(seq: Long, outcome: SegmentOutcome) {
        val turn = synchronized(correlationLock) { pending[seq] } ?: return
        if (!turn.claimed.compareAndSet(false, true)) return
        try {
            turn.owner.onSegmentResolved(seq, outcome)
        } finally {
            synchronized(correlationLock) {
                turn.itemId?.let { itemToSeq.remove(it) }
                pending.remove(seq)
            }
        }
    }

    /** Resolves every outstanding turn Lost. Cancelling in-flight work does not resolve — this does. */
    private fun abandonOutstanding(reason: String) {
        val turns = synchronized(correlationLock) { ArrayList(pending.values) }
        turns.forEach { resolveOnce(it.seq, SegmentOutcome.Lost(reason)) }
        synchronized(correlationLock) { itemToSeq.clear() }
    }

    /** Drop every queued send op and reset per-turn flags. Does NOT touch nextSeq or correlation. */
    private fun clearSendBuffer() = synchronized(bufferLock) {
        sendQueue.clear()
        queuedAppends = 0
        turnHasAudio = false
        turnShed = false
    }

    override fun close() {
        val owner = listener
        clearSendBuffer()
        // Resolve everything still owed BEFORE detaching, so the resolutions reach the session that
        // is still listening for them.
        abandonOutstanding(CLOSED)
        senderJob?.cancel()
        senderJob = null
        transport.close()
        listener = null
        owner?.onClosed()
    }

    /** The scope belongs to the caller (the service), so teardown ends the session without cancelling it. */
    override fun shutdown() = close()

    /**
     * Blocks the CALLING thread until the sender has drained and every committed turn has resolved,
     * or [timeoutMs] elapses. MUST be called off the main thread. The scope is never cancelled here —
     * a turn that is merely slow keeps running and still resolves.
     */
    override fun awaitIdle(timeoutMs: Long): Boolean = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            while (synchronized(bufferLock) { sendQueue.isNotEmpty() }) yield()
            while (synchronized(correlationLock) { pending.isNotEmpty() }) yield()
            true
        } ?: false
    }

    // -------- inlined audio math (upsample lifted to Resampler.upsample16kTo24k — plan Task 1) --------

    /** PCM16 samples -> headerless little-endian bytes (inverse of [PcmBytes.toShortArrayLE]). */
    private fun shortsToBytesLE(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    companion object {
        private const val TAG = "WE-DIAG"

        /** commit() cut nothing, so no seq was allocated and nothing is owed a resolution. */
        private const val NO_SEGMENT = -1L

        /** Max un-drained append ops before the current turn is shed. ~4 s of capture at 32 ms/chunk. */
        const val DEFAULT_MAX_BACKLOG = 128

        private const val BACKLOG = "network too far behind"
        private const val WS_DROP = "connection dropped"
        private const val CLOSED = "session closed"
        private const val SUPERSEDED = "session restarted"

        private fun reasonFor(kind: FatalKind): String = when (kind) {
            FatalKind.INVALID_KEY -> "key rejected"
            FatalKind.OUT_OF_CREDIT -> "account has no remaining credit"
            FatalKind.FORBIDDEN -> "access denied"
            FatalKind.MODEL_UNAVAILABLE -> "model unavailable"
        }

        /** Adapts the concrete [RealtimeTransport] to the engine's [Transport] seam for production wiring. */
        fun realTransport(rt: RealtimeTransport): Transport = object : Transport {
            override fun connect(apiKey: String, language: String?) = rt.connect(apiKey, language)
            override fun sendAppend(base64: String): Boolean = rt.sendAppend(base64)
            override fun sendCommit(): Boolean = rt.sendCommit()
            override fun close() = rt.close()
        }
    }
}
