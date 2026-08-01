package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Injectable factory for the WebSocket. Production wraps a long-lived OkHttp socket
 * ([OkHttpWebSocketFactory]); JVM tests return a fake, so the whole lifecycle is driveable without
 * a network. This is the single seam that keeps `okhttp3.WebSocket` (a framework class) out of the
 * pure logic below.
 */
fun interface WebSocketFactory {
    fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket
}

/**
 * Injectable delayed executor for reconnect backoff. Production posts to a daemon scheduler
 * ([ExecutorReconnectScheduler]); tests capture the delay and run the task synchronously, which is
 * how the backoff schedule is pinned. (The plan sketches this constructor slot as `clock`; a
 * delayed-task scheduler is what reconnect timing actually needs, and it makes the schedule
 * assertable without real waits — the deviation is deliberate and reported.)
 */
fun interface ReconnectScheduler {
    fun schedule(delayMs: Long, task: () -> Unit)
}

/**
 * Thin OkHttp WebSocket wrapper for the realtime transcription session — the shared socket+reconnect
 * shell; the per-provider wire (endpoint, headers, bootstrap, frame building, inbound vocabulary,
 * status→fatal) lives in the injected [RealtimeProtocol]. This class owns exactly four things and
 * NOTHING above them:
 *
 *  1. connect + per-open bootstrap (send the protocol's [RealtimeProtocol.bootstrap] frames once per open),
 *  2. outbound send ([sendAppend] / [sendCommit]) — delegated to the protocol, framed via [control],
 *  3. typed inbound dispatch to [Listener] (via [RealtimeProtocol.onText]),
 *  4. the reconnect-with-backoff policy — which lives HERE and nowhere else.
 *
 * Credential safety is load-bearing: the API key goes into the upgrade header the protocol returns
 * ONLY (empty for config-first providers), and a handshake failure logs the STATUS CODE ONLY — never
 * the body (which can echo request detail), never a header. The [Listener] fatal callback carries a
 * [FatalKind] and an int code, so no body/transcript can reach a consumer through this class by
 * construction.
 */
class RealtimeTransport(
    private val factory: WebSocketFactory,
    private val scheduler: ReconnectScheduler,
    private val listener: Listener,
    /** Per-provider wire behind the seam. Default = today's OpenAI path, so the 3-arg ctor and every
     *  existing call site + test compile and behave byte-identically. */
    private val protocol: RealtimeProtocol = OpenAiRealtimeProtocol(),
    private val backoff: Backoff = Backoff.DEFAULT,
    /**
     * How many consecutive reconnects to attempt before giving up for this session. A dead network,
     * DNS failure, sustained 5xx, or a persistent non-fatal 4xx must NOT loop connect->fail->backoff
     * forever, waking the radio every [Backoff.capMs] for the whole mic session. Reset to zero on a
     * successful open; once exceeded the socket stays down and the engine rides its local fallback
     * (every send returns false -> the turn resolves Lost) until the next explicit [connect].
     */
    private val maxReconnects: Int = DEFAULT_MAX_RECONNECTS,
) {

    /**
     * Typed transport callbacks. All parsing and classification happen below; the consumer (the
     * LiveTranscriptionEngine) receives only decoded facts.
     */
    interface Listener {
        /** A socket opened and was bootstrapped. */
        fun onConnected()

        /** Partial transcript for an in-flight turn. Preview only — never injected. */
        fun onDelta(itemId: String, text: String)

        /** A finished turn's transcript. */
        fun onCompleted(itemId: String, transcript: String)

        /**
         * The server acknowledged our commit and created the item [itemId]. Emitted in commit order,
         * so the consumer binds item_id<->seq HERE (deterministic) rather than on first delta/completed.
         */
        fun onCommitted(itemId: String)

        /** A per-item transcription failure: the item [itemId] will yield no transcript. */
        fun onTranscriptionFailed(itemId: String)

        /**
         * An in-band `error` event. Only the message LENGTH crosses this boundary — never its
         * content. The consumer maps [code] to a fatal latch.
         */
        fun onErrorEvent(code: String?, messageLength: Int)

        /**
         * The socket dropped for a transient reason; a reconnect has been scheduled. Outstanding
         * turns should resolve `Lost` (the server buffer is gone) so the local fallback rescues
         * them — but the transport will re-establish the session itself.
         */
        fun onDisconnected()

        /**
         * A terminal, non-retryable handshake failure (bad key / no credit / forbidden). No
         * reconnect is attempted. [code] is the HTTP status only.
         */
        fun onFatal(kind: FatalKind, code: Int)
    }

    /**
     * Capped exponential backoff. Pure and pinned so the reconnect schedule is asserted directly:
     * 500, 1000, 2000, 4000, 8000, 8000, ... (ms). A successful open resets the attempt counter.
     */
    data class Backoff(val baseMs: Long, val capMs: Long) {
        fun delayFor(attempt: Int): Long {
            if (attempt <= 0) return minOf(baseMs, capMs)
            // Guard the shift against overflow at absurd attempt counts before capping.
            val grown = if (attempt >= 40) Long.MAX_VALUE else baseMs shl attempt
            return if (grown < 0) capMs else minOf(grown, capMs)
        }

        companion object {
            val DEFAULT = Backoff(baseMs = 500L, capMs = 8_000L)
        }
    }

    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var apiKey: String = ""
    private var language: String? = null
    private var closed = false
    private var reconnectAttempts = 0

    /**
     * The tolerant connector. Current docs omit the `OpenAI-Beta: realtime=v1` header; older
     * examples require it. We connect WITHOUT it, and on the FIRST 4xx handshake we flip this and
     * retry once, immediately and silently — before any fatal classification. Without the retry, a
     * header-caused 401 would mis-latch as INVALID_KEY and toast "key rejected" at a user whose
     * key is fine. Once flipped it stays on for the transport's lifetime: if the server wanted the
     * header once, it wants it on every reconnect too.
     */
    private var useBetaHeader = false

    /**
     * False from the moment a socket is created until its bootstrap frames have actually been
     * sent. THE bug behind Soniox live (proven 2026-07-31 by probing their server with a real
     * key): `OkHttpClient.newWebSocket()` returns a WebSocket **immediately**, before the HTTP
     * upgrade completes, and [openSocket] assigns it to [webSocket] — so the socket looked
     * sendable the instant [connect] returned. The audio pump is already running by then, so its
     * frames were enqueued into OkHttp AHEAD of the config that [onOpen] sends later, and the
     * first thing Soniox saw on the wire was audio: `400 invalid_request — "Start request must
     * be a text message."` (exactly the 37-char body the device logged).
     *
     * Providers differ only in tolerance — OpenAI and ElevenLabs accept audio before their
     * session/config message, which is why the same ordering bug was invisible on two of three
     * providers and looked like a Soniox schema problem through two wrong fixes.
     *
     * Audio dropped in this window is not lost: [sendAppend] returning false sheds the turn, and
     * the fallback re-transcribes it from the mirror — the behavior [connect]'s KDoc already
     * documents for the pre-handshake window.
     */
    @Volatile private var bootstrapped = false

    /**
     * The ONE place `okhttp3.WebSocket.send` is called, now handling both frame types and the
     * queueSize backpressure that used to sit inline in [sendAppend] — so it is enforced ONCE, for
     * every provider and both frame types. The protocol drives this; it never touches the socket.
     */
    private val control = object : SessionControl {
        override fun send(frame: Frame): Boolean = synchronized(lock) {
            val ws = webSocket ?: return false
            if (ws.queueSize() > MAX_OUTBOUND_BYTES) return false
            when (frame) {
                is Frame.Text -> ws.send(frame.json)
                is Frame.Binary -> ws.send(frame.bytes) // OkHttp 4.12.0 binary send — Soniox audio
            }
        }

        override fun rotate() = synchronized(lock) {
            if (closed) return@synchronized
            // Empty-frame finalize is the protocol's job before it calls rotate(); here we just cycle
            // the socket under the SAME reconnect ceiling, so a pathological rotation loop still gives up.
            webSocket?.close(NORMAL_CLOSURE, null)
            webSocket = null
            bootstrapped = false // the next socket must re-bootstrap before any audio
            scheduleReconnect()
        }
    }

    /** Open the session for [language] (null = auto) using [apiKey]. Resets backoff state. */
    fun connect(apiKey: String, language: String?) {
        synchronized(lock) {
            this.apiKey = apiKey
            this.language = language
            closed = false
            reconnectAttempts = 0
            protocol.bind(control, listener) // once per session: hand the protocol its socket + sink
            openSocket()
        }
    }

    /**
     * Enqueue one append as raw 16 kHz PCM16; the [protocol] frames it (OpenAI: 24 k upsample +
     * base64). Returns false if there is no live socket OR the socket's own outbound buffer is over
     * [MAX_OUTBOUND_BYTES] (network backpressure, now enforced in [control]).
     *
     * The threshold is the real fix for a live-but-stalled socket: OkHttp's `send()` is a
     * non-blocking enqueue into its OWN buffer (bounded at 16 MiB, past which it cancels the
     * socket). If the engine only watched its own send queue, that queue would drain to ~0 while
     * bytes piled up invisibly inside OkHttp for minutes before the hard cap fired. Watching
     * [WebSocket.queueSize] in [control] lets the false return reach the engine as a prompt shed signal.
     */
    fun sendAppend(pcm: ByteArray): Boolean = synchronized(lock) {
        // [bootstrapped], not just a non-null socket: newWebSocket() hands back a socket before the
        // handshake, so "non-null" is not "ready to receive audio". See the [bootstrapped] KDoc.
        if (webSocket == null || !bootstrapped) return false
        protocol.onAppend(pcm)
    }

    /** Finalize the current turn per the [protocol] (commit event / commit-flag / client assembly). */
    fun sendCommit(): Boolean = synchronized(lock) {
        if (webSocket == null || !bootstrapped) return false
        protocol.onCommit()
    }

    /** Clean, idempotent close. A second call no-ops; a pending reconnect is cancelled. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            webSocket?.close(NORMAL_CLOSURE, null)
            webSocket = null
            bootstrapped = false // the next socket must re-bootstrap before any audio
            protocol.reset() // drop any protocol-held state; OpenAI's reset is a no-op
        }
    }

    private fun openSocket() {
        // caller holds [lock]. The key is placed ONLY in the upgrade header(s) the protocol returns
        // (empty for config-first providers). OkHttp silently rewrites the wss:// URL to https://.
        val builder = Request.Builder().url(protocol.endpoint)
        protocol.upgradeHeaders(apiKey).forEach { (name, value) -> builder.header(name, value) }
        if (useBetaHeader && protocol.tolerant4xxRetry) builder.header(BETA_HEADER, BETA_VALUE)
        bootstrapped = false // newWebSocket returns pre-handshake; gate stays shut until onOpen
        webSocket = factory.newWebSocket(builder.build(), InternalListener())
    }

    private fun scheduleReconnect() {
        // caller holds [lock]
        if (reconnectAttempts >= maxReconnects) {
            // Give up rather than burn battery retrying a dead network forever. The socket stays
            // down; the engine keeps routing turns Lost -> local until the next explicit connect().
            android.util.Log.w(TAG, "realtime giving up after $reconnectAttempts consecutive reconnects")
            return
        }
        val delay = backoff.delayFor(reconnectAttempts)
        reconnectAttempts++
        scheduler.schedule(delay) {
            synchronized(lock) {
                if (closed) return@synchronized
                openSocket()
            }
        }
    }

    private inner class InternalListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (closed) return
                reconnectAttempts = 0
                this@RealtimeTransport.webSocket = webSocket
                // Bootstrap INSIDE the publish lock, then open the audio gate. Until this line
                // runs, sendAppend/sendCommit refuse — so the config is provably the first frame
                // on the wire for every provider.
                // Bootstrap INSIDE the publish lock, atomically with the socket becoming visible.
                // [sendAppend] synchronizes on the SAME lock, so no audio frame can interleave
                // between publication and the protocol's first message(s). The old shape published
                // the socket, released the lock, THEN bootstrapped — and the engine's audio pump,
                // already running, could slip a binary frame out first. Proven on-device
                // 2026-07-31: Soniox strictly 400s any pre-config frame (config must be message
                // #1), while ElevenLabs and OpenAI tolerate the same race — the per-provider skew
                // that made a transport ordering bug masquerade as a config-schema bug through TWO
                // fix rounds. (The PC probe with a fake key was the tell: every config variant
                // passed schema and drew a clean 401, so the rejected 'config' was never the
                // config.) send() is a non-blocking enqueue; holding the lock across it is safe,
                // and control.send's synchronized(lock) is reentrant from this thread.
                protocol.bootstrap(apiKey, language).forEach { control.send(it) }
                bootstrapped = true // the gate opens ONLY after the config is on the wire
            }
            listener.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = protocol.onText(text)

        // No provider sends inbound BINARY — decline it explicitly rather than inherit a silent no-op.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            // STATUS CODE ONLY. Never touch response.body (it can echo request detail) or headers.
            android.util.Log.w(TAG, if (code != null) "realtime http $code" else "realtime dropped")

            // The tolerant connector's one free retry (OpenAI only, via [protocol.tolerant4xxRetry]):
            // the FIRST 4xx may be the missing OpenAI-Beta header rather than a bad key. Retry once
            // with it, silently — no fatal, no disconnect callback, no reconnect-attempt consumed. If
            // the retry fails too, the failure falls through here a second time with useBetaHeader
            // already true and takes the normal classification below.
            if (code != null && code in 400..499) {
                val retried = synchronized(lock) {
                    if (!closed && !useBetaHeader && protocol.tolerant4xxRetry) {
                        useBetaHeader = true
                        this@RealtimeTransport.webSocket = null
                        bootstrapped = false // the next socket must re-bootstrap before any audio
                        openSocket()
                        true
                    } else false
                }
                if (retried) return
            }

            val fatal = code?.let { protocol.classifyFatal(it) }
            synchronized(lock) {
                if (closed) return
                this@RealtimeTransport.webSocket = null
                bootstrapped = false // the next socket must re-bootstrap before any audio
                if (fatal == null) scheduleReconnect()
            }
            if (fatal != null) listener.onFatal(fatal, code!!) else listener.onDisconnected()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Server-initiated close: complete the handshake unless we already closed ourselves.
            synchronized(lock) {
                if (closed) return
                webSocket.close(NORMAL_CLOSURE, null)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val wasSelfInitiated: Boolean
            synchronized(lock) {
                wasSelfInitiated = closed
                this@RealtimeTransport.webSocket = null
                bootstrapped = false // the next socket must re-bootstrap before any audio
            }
            // An unexpected server close surfaces as a disconnect so outstanding turns resolve
            // Lost; our own close() is silent.
            if (!wasSelfInitiated) listener.onDisconnected()
        }
    }

    companion object {
        private const val TAG = "WE-DIAG"
        private const val NORMAL_CLOSURE = 1000

        /** Consecutive reconnects before the transport gives up for the session (see [maxReconnects]). */
        const val DEFAULT_MAX_RECONNECTS = 6

        /**
         * Shed the turn once OkHttp's outbound buffer passes this, well under its 16 MiB hard cap
         * (past which OkHttp cancels the socket). 2 MiB of un-drained base64 audio already means the
         * network is far enough behind that the turn is better rescued locally than buffered.
         */
        const val MAX_OUTBOUND_BYTES = 2L * 1024 * 1024

        /**
         * Transcription intent per the pinned protocol. The `OpenAI-Beta: realtime=v1` header is
         * NOT sent on the first attempt (current docs omit it); [useBetaHeader] adds it after the
         * first 4xx and keeps it for the transport's lifetime.
         */
        const val ENDPOINT = "wss://api.openai.com/v1/realtime?intent=transcription"
        const val BETA_HEADER = "OpenAI-Beta"
        const val BETA_VALUE = "realtime=v1"
    }
}

/**
 * Production [WebSocketFactory]: derives a no-timeout, ping-kept variant of the shared OkHttp client
 * for the long-lived socket. `readTimeout(0)` / `callTimeout(0)` disable the finite timeouts that
 * make sense for one-shot POSTs but would kill a streaming session; a 20 s ping keeps the
 * connection and its NAT mapping alive.
 */
class OkHttpWebSocketFactory(base: OkHttpClient) : WebSocketFactory {
    private val client = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
        client.newWebSocket(request, listener)
}

/** Production [ReconnectScheduler] on a single daemon thread — never blocks the caller. */
class ExecutorReconnectScheduler(
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "realtime-reconnect").apply { isDaemon = true }
    },
) : ReconnectScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit) {
        exec.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    }
    /** Stop the daemon executor. onDestroy must call this or the "realtime-reconnect" thread leaks. */
    fun shutdown() { exec.shutdownNow() }
}
