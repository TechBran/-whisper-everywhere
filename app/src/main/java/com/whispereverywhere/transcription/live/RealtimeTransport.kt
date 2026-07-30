package com.whispereverywhere.transcription.live

import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
 * Thin OkHttp WebSocket wrapper for the OpenAI Realtime transcription session. It owns exactly four
 * things and NOTHING above them:
 *
 *  1. connect + session bootstrap (send [RealtimeEvents.sessionUpdate] once per open),
 *  2. outbound send ([sendAppend] / [sendCommit]),
 *  3. typed inbound dispatch to [Listener] (via [RealtimeEventParser]),
 *  4. the reconnect-with-backoff policy — which lives HERE and nowhere else.
 *
 * Credential safety is load-bearing: the API key goes into the `Authorization` header of the
 * upgrade request ONLY, and a handshake failure logs the STATUS CODE ONLY — never the body (which
 * can echo request detail), never a header. The [Listener] fatal callback carries a [FatalKind] and
 * an int code, so no body/transcript can reach a consumer through this class by construction.
 */
class RealtimeTransport(
    private val factory: WebSocketFactory,
    private val scheduler: ReconnectScheduler,
    private val listener: Listener,
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

    /** Open the session for [language] (null = auto) using [apiKey]. Resets backoff state. */
    fun connect(apiKey: String, language: String?) {
        synchronized(lock) {
            this.apiKey = apiKey
            this.language = language
            closed = false
            reconnectAttempts = 0
            openSocket()
        }
    }

    /**
     * Enqueue one `input_audio_buffer.append`. Returns false if there is no live socket OR the
     * socket's own outbound buffer is over [MAX_OUTBOUND_BYTES] (network backpressure).
     *
     * The threshold is the real fix for a live-but-stalled socket: OkHttp's `send()` is a
     * non-blocking enqueue into its OWN buffer (bounded at 16 MiB, past which it cancels the
     * socket). If the engine only watched its own send queue, that queue would drain to ~0 while
     * bytes piled up invisibly inside OkHttp for minutes before the hard cap fired. Watching
     * [WebSocket.queueSize] here lets the false return reach the engine as a prompt shed signal.
     */
    fun sendAppend(base64: String): Boolean = synchronized(lock) {
        val ws = webSocket ?: return false
        if (ws.queueSize() > MAX_OUTBOUND_BYTES) return false
        ws.send(RealtimeEvents.append(base64))
    }

    /** Enqueue one `input_audio_buffer.commit`. Returns false if there is no live socket. */
    fun sendCommit(): Boolean =
        synchronized(lock) { webSocket?.send(RealtimeEvents.commit()) ?: false }

    /** Clean, idempotent close. A second call no-ops; a pending reconnect is cancelled. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            webSocket?.close(NORMAL_CLOSURE, null)
            webSocket = null
        }
    }

    private fun openSocket() {
        // caller holds [lock]. The key is placed in the Authorization header of the upgrade
        // request ONLY. OkHttp silently rewrites the wss:// URL to https:// for the upgrade.
        val provider = ProviderCatalog.byId(ProviderId.OPENAI)
        val request = Request.Builder()
            .url(ENDPOINT)
            .header(provider.authHeaderName, provider.authHeaderValue(apiKey))
            .build()
        webSocket = factory.newWebSocket(request, InternalListener())
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

    /** Handshake status -> fatal kind, or null for a transient failure that should reconnect. */
    private fun classifyFatal(code: Int): FatalKind? = when (code) {
        401 -> FatalKind.INVALID_KEY
        403 -> FatalKind.FORBIDDEN
        // The body distinguishes rate-limit from exhausted credit, but the body is OFF-LIMITS on a
        // handshake (credential safety). A 429 on the upgrade is treated as the wallet being the
        // problem: fail terminal, latch, and let the local fallback rescue — never hammer the
        // socket against an empty account. See OpenAiStt.classify for the batch-path analogue.
        429 -> FatalKind.OUT_OF_CREDIT
        else -> null // 5xx and network-level drops are transient -> reconnect with backoff
    }

    private inner class InternalListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (closed) return
                reconnectAttempts = 0
                this@RealtimeTransport.webSocket = webSocket
            }
            // Bootstrap exactly once per open. turn_detection:null => WE own turn commits.
            webSocket.send(RealtimeEvents.sessionUpdate())
            listener.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val event = RealtimeEventParser.parse(text)) {
                is Inbound.Delta -> listener.onDelta(event.itemId, event.text)
                is Inbound.Completed -> listener.onCompleted(event.itemId, event.transcript)
                is Inbound.Error -> listener.onErrorEvent(event.code, event.message.length)
                is Inbound.Ack -> Unit // benign ack — log-and-ignore
                null -> Unit // unknown/forward-compat type — ignore
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            // STATUS CODE ONLY. Never touch response.body (it can echo request detail) or headers.
            android.util.Log.w(TAG, if (code != null) "openai realtime http $code" else "openai realtime dropped")
            val fatal = code?.let { classifyFatal(it) }
            synchronized(lock) {
                if (closed) return
                this@RealtimeTransport.webSocket = null
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

        /** Transcription intent per the pinned protocol. `OpenAI-Beta` is added only on a 4xx. */
        const val ENDPOINT = "wss://api.openai.com/v1/realtime?intent=transcription"
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
}
