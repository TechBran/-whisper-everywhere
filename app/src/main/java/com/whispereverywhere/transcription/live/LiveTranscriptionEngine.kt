package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.TranscriptionEngine
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
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
 *     one op to the send queue under [bufferLock] and returns. The per-provider framing (upsample /
 *     base64 / binary, behind the transport's protocol) + WS send happens on a separate sender
 *     coroutine ([senderLoop]); if that coroutine is wedged on a saturated socket, [sendAudio] still
 *     returns at once — it never touches the socket.
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
 * ("Use item_id"). It DOES emit `input_audio_buffer.committed` in commit order, each carrying the
 * new item's id — so binding happens THERE, at the commit ack, mapping each item_id to the OLDEST
 * unbound bindable seq deterministically. Deltas and completions then resolve their bound seq in any
 * order. Binding on first delta/completed (as an earlier revision did) let first-mention races swap
 * transcripts between seqs or strand a seq forever; the ack is the only ordered signal (C4).
 *
 * Never logs transcript content, the key, or audio — lengths and codes only.
 */
class LiveTranscriptionEngine(
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val maxBacklog: Int = DEFAULT_MAX_BACKLOG,
    /**
     * Bytes of 16 kHz PCM16 a turn must carry before we send its commit. The Realtime server rejects
     * a commit under ~100 ms (`input_audio_buffer_commit_empty`) and raises NO item, which would
     * strand the turn's seq and poison correlation. A turn below this is resolved Lost locally and
     * never committed. Default 3200 B = 100 ms; tests pass 0 to exercise the correlation machine
     * with tiny fixtures.
     */
    private val minCommitBytes: Int = DEFAULT_MIN_COMMIT_BYTES,
    /**
     * true for the open-mic live modes: the SERVER cuts turns (server VAD / endpoint detection), so
     * the engine allocates seqs from SERVER turn events and never enqueues a client commit or applies
     * the too-short gate. false keeps the client-VAD ledger (still a valid mode of THIS class — its
     * unit tests exercise it). The client-mode default path is byte-identical to before this flag.
     *
     * Fallback honesty: the client-VAD fallback is only HALF-present. This engine's client ledger is
     * retained and tested, but each provider's PROTOCOL-layer commit machinery (OpenAI's commit-frame
     * send, ElevenLabs' fold-slot/timeout, Soniox's grace window) was DELETED with the 2026-07-31
     * inversion — so re-enabling client-VAD for a genuinely non-segmenting provider would require
     * rebuilding that protocol path, not just flipping this flag. The seam is a construction flag; the
     * fallback is documented, not built. All three shipped providers segment server-side.
     */
    private val serverDriven: Boolean = false,
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
        /** One append as raw 16 kHz PCM16; the transport's protocol frames it per provider. */
        fun sendAppend(pcm: ByteArray): Boolean
        fun sendCommit(): Boolean
        fun close()
    }

    private val transportListener = TransportListener()
    private val transport: Transport = makeTransport(transportListener)

    /**
     * In server-driven mode a SERVER turn event must rotate the fallback mirror AND allocate the seq
     * as ONE operation — the same pairing the client [commit] gives batch. The engine is WRAPPED by
     * [com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngine], so it calls "up"
     * through this callback (wired by the service to `fallback.commit()`), which snapshots the mirror
     * and calls back into [commit], returning the allocated seq (or a negative NO_SEGMENT). Null in
     * client mode.
     */
    @Volatile private var serverTurnRotation: (() -> Long)? = null

    /** Wire the server-turn rotation (server-driven mode only). See [serverTurnRotation]. */
    fun attachServerTurnRotation(cb: () -> Long) { serverTurnRotation = cb }

    // -------- capture thread / sender (guarded by bufferLock) --------

    private val bufferLock = Any()
    private val sendQueue = ArrayDeque<SendOp>()
    /** Count of un-drained [SendOp.Append] ops; the backpressure signal. Guarded by [bufferLock]. */
    private var queuedAppends = 0
    /** Any audio captured since the last cut. This — not the drained queue — is commit()'s emptiness. */
    private var turnHasAudio = false
    /** Bytes of 16 kHz PCM16 captured since the last cut; gates the sub-minimum commit. Under [bufferLock]. */
    private var turnAudioBytes = 0
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
            turnAudioBytes += pcm.size // counts even a dropped chunk: the turn still HAD that audio
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
        var tooShort: Boolean
        synchronized(bufferLock) {
            if (!turnHasAudio) return NO_SEGMENT
            seq = nextSeq++
            shed = turnShed
            // Client mode: a turn under the server's ~100 ms minimum would be rejected with no item
            // raised, stranding this seq and mis-binding the next turn — treat it like a shed turn and
            // resolve Lost locally. Server mode: the SERVER already decided this turn is real (it cut
            // it) and we send no client commit anyway, so the too-short gate does not apply.
            tooShort = !serverDriven && turnAudioBytes < minCommitBytes
            turnHasAudio = false
            turnShed = false
            turnAudioBytes = 0
            // REGISTER THE TURN BEFORE ITS COMMIT OP CAN BE DEQUEUED. [senderLoop] takes ops off
            // [sendQueue] under THIS lock and, on a false sendCommit (socket down), resolves the seq
            // INLINE on the sender thread. Registering after the enqueue — as this did until S0 —
            // left a window between `addLast` and the registration in which the sender could dequeue
            // the op, fail to send, and call [resolveOnce] while `pending` was still empty.
            // resolveOnce no-ops on an unknown seq, so the ONLY resolution this turn would ever be
            // offered was silently dropped: the seq then sat in `pending` forever, unclaimed —
            // exactly the dangling seq (off-by-one poisoning + tail-stall / awaitIdle timeout) this
            // path exists to prevent. That is a genuine hole in property 3, not a test artefact; it
            // surfaced 16 times as the "socket-down flake" only because the window is a few
            // instructions wide and needs the sender thread scheduled precisely inside it.
            //
            // Holding [bufferLock] across the registration closes it: the sender cannot dequeue
            // until this block exits, so registration always happens-before any possible inline
            // resolution. This restores the ordering [com.whispereverywhere.transcription.cloud.CloudTranscriptionEngine.commit]
            // states in its own comment ("register before the body can run") and gets structurally
            // from `CoroutineStart.LAZY` + register + start. The socket port lost it because its
            // resolver is not a job it starts last but a QUEUE the sender drains — so the ENQUEUE is
            // the start, and it had been moved ahead of the registration.
            //
            // Lock order is bufferLock -> correlationLock, the only nesting in this class. No path
            // takes bufferLock while holding correlationLock ([resolveOnce] invokes the listener
            // OUTSIDE the lock), so it cannot deadlock. correlationLock is never held across a
            // callback, so the added hold is a bounded map write and property 1 (sendAudio never
            // blocks) still stands — pinned by sendAudio_never_blocks_capture_thread.
            synchronized(correlationLock) {
                pending[seq] = PendingTurn(seq, owner, bindable = latched == null && !shed && !tooShort)
            }
            // Only a deliverable turn tells the server to finalize. A shed, fatal, or too-short turn
            // sends no commit — its audio is gone, its session is dead, or the server would reject it
            // — so the server raises no item, and this turn owns no bindable slot. Server mode NEVER
            // sends a client input_audio_buffer.commit: the server auto-commits under its own VAD.
            if (latched == null && !shed && !tooShort && !serverDriven) sendQueue.addLast(SendOp.Commit(seq))
        }
        val deliverable = latched == null && !shed && !tooShort
        if (deliverable) wakeups.trySend(Unit)

        // Resolve the dead-on-arrival turns OFF this thread. Doing it inline would fire the owner's
        // callback before FallbackTranscriptionEngine.commit() (which is calling us under its mirror
        // lock) has retained this seq's PCM — see property 5. The scope is not Unconfined, so the
        // launch runs after commit() returns and after the mirror snapshot lands.
        when {
            latched != null -> scope.launch { resolveOnce(seq, SegmentOutcome.Lost(reasonFor(latched))) }
            shed -> scope.launch { resolveOnce(seq, SegmentOutcome.Lost(BACKLOG)) }
            tooShort -> scope.launch { resolveOnce(seq, SegmentOutcome.Lost(TOO_SHORT)) }
        }
        return seq
    }

    /** Drains [sendQueue] off the capture thread: hands each raw 16 kHz PCM append to the transport,
     *  which frames it per provider (OpenAI: upsample 16k→24k, LE-encode, base64), then sends. */
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
                    if (!transport.sendAppend(op.pcm)) markTurnShed()
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

    // -------- inbound (transport callback thread) --------

    private inner class TransportListener : RealtimeTransport.Listener {
        override fun onConnected() {
            // onOpen already fired in connect(); nothing to surface here. No content ever logged.
            android.util.Log.i(TAG, "realtime session live")
        }

        override fun onDelta(itemId: String, text: String) {
            // Preview strip ONLY — deltas never resolve, never inject, and (post-C4) never BIND.
            // Binding is the committed ack's job; a reordered or delta-less turn cannot shear seqs.
            listener?.onDelta(text)
        }

        override fun onCommitted(itemId: String) {
            if (serverDriven) {
                // Server-driven: this event IS the turn boundary. Rotate the fallback mirror + allocate
                // the seq for the just-cut turn FIRST, THEN bind the server item to it. A NO_SEGMENT
                // (no audio since the last boundary) binds nothing — otherwise bindItem would attach
                // this item to a stale earlier seq (permanent off-by-one).
                val seq = serverTurnRotation?.invoke() ?: NO_SEGMENT
                if (seq < 0) return
            }
            // The deterministic, in-commit-order binding point: map this item to the oldest unbound
            // bindable seq. Every later delta/completed for it resolves the RIGHT seq regardless of
            // cross-turn event ordering.
            bindItem(itemId)
        }

        override fun onCompleted(itemId: String, transcript: String) {
            // Normally already bound by the committed ack; bindItem returns that binding. The
            // fallback bind (oldest unbound) fires only if the ack was missed, so a completed still
            // resolves rather than strands — but the deterministic path is the ack, not this.
            val seq = bindItem(itemId) ?: return
            resolveOnce(seq, outcomeFor(transcript))
        }

        override fun onTranscriptionFailed(itemId: String) {
            // The item exists but produced no transcript. Resolve its bound seq Lost so the fallback
            // rescues it and it never lingers in `pending` to mis-bind the next turn.
            val seq = bindItem(itemId) ?: return
            resolveOnce(seq, SegmentOutcome.Lost(TRANSCRIBE_FAILED))
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
            //
            // Server mode: no client commit has cut the in-progress audio, so snapshot the tail under a
            // fresh seq FIRST (rotating the mirror). abandonOutstanding then resolves that seq — and
            // all pending — Lost, and the fallback rescues the tail from the retained PCM. Client mode
            // is unchanged (the service's next commit / stop snapshots the tail).
            if (serverDriven) serverTurnRotation?.invoke()
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
        // Live was the ONE completion path not routed through TranscriptText.clean — batch and
        // segment cloud both clean. Found on-device 2026-07-31: gpt-transcribe (a GPT-family
        // model) sometimes wraps live output in markdown code fences, which landed verbatim in
        // the user's field. clean() strips fences, bracketed non-speech markers, and collapses
        // whitespace — the same contract every other injected transcript already gets.
        val cleaned = com.whispereverywhere.transcription.TranscriptText.clean(transcript)
        // EmptyExpected — NOT EmptyUnexpected. That classification was written when OUR client VAD
        // cut live turns: our VAD firing meant confirmed voiced audio, so an empty transcript was a
        // lost sentence worth a "[…]" marker. The 2026-07-31 server-driven inversion changed the
        // premise and this line was not revisited: the SERVER's VAD now cuts turns, and a server
        // VAD is deliberately trigger-happy — it fires on a cough, a door, a breath. An empty
        // completion is that: the provider heard something speech-shaped and transcribed nothing.
        // Marking it Unexpected stamped "[…]" into the user's text for ordinary room noise
        // (owner-reported artifact, 2026-07-31). Silence contributes nothing, exactly as it does on
        // every other path. A genuine mid-sentence loss is still visible: a dropped socket resolves
        // Lost (never Empty), the mirror rescues it locally, and only a rescue that also fails
        // leaves a marker.
        return if (cleaned.isEmpty()) SegmentOutcome.EmptyExpected else SegmentOutcome.Text(cleaned)
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
        // Session-CONFIG rejections (invalid_value, unknown_parameter, invalid_request_error,
        // missing_required_parameter, invalid_type…). A config the server rejects will be rejected
        // on every retry — it cannot self-heal, so it must LATCH (one toast, local fallback for the
        // session) rather than fall through to null. Proven on-device 2026-07-31: an unmapped
        // `invalid_value` left the whole session silently degrading turn-by-turn to local with the
        // user none the wiser — the exact silent-degradation failure the latch toast exists for.
        code.contains("invalid", true) || code.contains("unknown_parameter", true) ||
            code.contains("missing", true) || code.contains("unsupported", true) ->
            FatalKind.MODEL_UNAVAILABLE
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

    /**
     * Resolves every outstanding turn Lost so the mirror rescues each one locally.
     *
     * Deliberately NOT conditioned on whether the server had opened an item: unbound does not mean
     * "no speech". A user who says a short sentence and taps stop immediately leaves an unbound
     * turn that is FULL of speech — the server simply had not replied yet. Treating unbound as
     * silence would drop exactly that sentence (the Critical the stop-tail rescue exists to fix).
     * Whether the audio held speech is whisper's call, made during the rescue; see
     * [com.whispereverywhere.transcription.cloud.FallbackPolicy.reconcile].
     */
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
        turnAudioBytes = 0
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
     * The session is STOPPING (server-driven live only). Resolve every still-outstanding server turn
     * — the final open utterance the stop-commit just cut, plus any committed turn whose completion
     * has not yet arrived — Lost RIGHT NOW, while the wrapping
     * [com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngine] still holds each seq's
     * retained PCM and is still `accepting`. This is the graceful-stop twin of [onDisconnected]'s tail
     * discipline.
     *
     * Without it the tail seq the stop-commit allocated sits in [pending] forever: audio capture has
     * stopped, so no provider VAD can endpoint the tail (OpenAI server_vad needs silence frames it
     * will never receive; neither Soniox `<end>` nor ElevenLabs `committed_transcript` fires on a
     * graceful close). The service's finalize `awaitIdle` then loops the ENTIRE timeout budget on the
     * non-empty [pending], and when close() finally resolves the tail it has already cleared the
     * retained PCM — so the tail is dropped as a bare loss marker with NO on-device rescue. Resolving
     * here instead drives the tail through [abandonOutstanding] while retained/accepting are valid, so
     * `CloudRelay` rescues each turn from the mirror during the drain and `awaitIdle` sees [pending]
     * empty at once.
     *
     * No-op in client-VAD mode: there the service's own stop commit cuts the tail and the batch drain
     * finalizes it exactly as before — this path must stay byte-identical.
     */
    fun finishServerTurns() {
        if (!serverDriven) return
        // Match onDisconnected's order: drop any still-queued appends for the tail (the sender must
        // not keep pushing to a socket we are about to close), THEN resolve every pending turn Lost.
        clearSendBuffer()
        abandonOutstanding(ENDED)
    }

    /**
     * Blocks the CALLING thread until the sender has drained and every committed turn has resolved,
     * or [timeoutMs] elapses. MUST be called off the main thread. The scope is never cancelled here —
     * a turn that is merely slow keeps running and still resolves.
     */
    override fun awaitIdle(timeoutMs: Long): Boolean = runBlocking {
        val startNs = System.nanoTime()
        val drained = withTimeoutOrNull(timeoutMs) {
            while (synchronized(bufferLock) { sendQueue.isNotEmpty() }) yield()
            while (synchronized(correlationLock) { pending.isNotEmpty() }) yield()
            true
        } ?: false
        // C1 finalize-timing: after finishServerTurns this should be near-zero — a large value
        // here convicts the live drain (spec C2 "live path" candidate).
        android.util.Log.i(TAG, "finalize-timing: cloud-drain=${(System.nanoTime() - startNs) / 1_000_000}ms")
        drained
    }

    companion object {
        private const val TAG = "WE-DIAG"

        /** commit() cut nothing, so no seq was allocated and nothing is owed a resolution. */
        private const val NO_SEGMENT = -1L

        /** Max un-drained append ops before the current turn is shed. ~4 s of capture at 32 ms/chunk. */
        const val DEFAULT_MAX_BACKLOG = 128

        /** ~100 ms of 16 kHz PCM16 (0.1 * 16000 * 2 B) — the Realtime server's minimum commit size. */
        const val DEFAULT_MIN_COMMIT_BYTES = 3_200

        private const val BACKLOG = "network too far behind"
        private const val WS_DROP = "connection dropped"
        private const val CLOSED = "session closed"
        private const val ENDED = "session ended"
        private const val SUPERSEDED = "session restarted"
        private const val TOO_SHORT = "utterance too short to transcribe"
        private const val TRANSCRIBE_FAILED = "transcription failed"

        private fun reasonFor(kind: FatalKind): String = when (kind) {
            FatalKind.INVALID_KEY -> "key rejected"
            FatalKind.OUT_OF_CREDIT -> "account has no remaining credit"
            FatalKind.FORBIDDEN -> "access denied"
            FatalKind.MODEL_UNAVAILABLE -> "model unavailable"
        }

        /** Adapts the concrete [RealtimeTransport] to the engine's [Transport] seam for production wiring. */
        fun realTransport(rt: RealtimeTransport): Transport = object : Transport {
            override fun connect(apiKey: String, language: String?) = rt.connect(apiKey, language)
            override fun sendAppend(pcm: ByteArray): Boolean = rt.sendAppend(pcm)
            override fun sendCommit(): Boolean = rt.sendCommit()
            override fun close() = rt.close()
        }
    }
}
