package com.whispereverywhere.transcription.live

import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure Soniox stt-rt-v5 codec — Android-free (kotlinx-serialization only, no `org.json`, no
 * `android.util.Base64`), so it runs under `unitTests.isReturnDefaultValues = true`. Verbatim-doc
 * JSON drives [SonioxRealtimeProtocolTest]/[SonioxNoLogDisciplineTest].
 *
 * The no-log discipline starts HERE: [config] is a PURE FUNCTION of (apiKey, language). Its returned
 * String is the ONLY object that ever holds the key — it is handed straight to the transport as the
 * first frame and never stored, logged, `toString`'d, or placed in an exception. The intermediate
 * [Config] instance is serialized and discarded inside [config]; it is never retained or exposed.
 *
 * Wire (recon + docs 2026-07-31): first frame is the key-bearing config; audio is RAW s16le BINARY at
 * 16 kHz (no resample, no base64); inbound `{"tokens":[{"text","is_final"}],"finished":bool}` — non-
 * final tokens are the evolving preview and final tokens accumulate into the client-assembled turn;
 * numeric errors arrive as `{"error_code":N,"error_message":"…"}`. Unknown/empty frames return null.
 */
object SonioxEvents {
    private val OUT = Json { encodeDefaults = true; explicitNulls = false }
    private val IN = Json { ignoreUnknownKeys = true }

    /**
     * The key-bearing first message. This is the SOLE carrier of the key on Soniox; it exists only
     * as the returned String and is never held by the protocol. No log line belongs here, ever.
     */
    fun config(apiKey: String, language: String?): String =
        OUT.encodeToString(Config(apiKey = apiKey, languageHints = language?.let { listOf(it) }))

    class Token(val text: String, val isFinal: Boolean)
    class Result(
        val tokens: List<Token>,
        val finished: Boolean,
        val errorCode: Int?,
        /** LENGTH of `error_message` only — the content never leaves this codec. */
        val errorLen: Int,
    )

    fun parse(json: String): Result? {
        val o = try { IN.parseToJsonElement(json) as? JsonObject } catch (_: Throwable) { null } ?: return null
        val errCode = (o["error_code"] as? JsonPrimitive)?.intOrNull
        val errLen = ((o["error_message"] as? JsonPrimitive)?.contentOrNull).orEmpty().length
        val toks = (o["tokens"] as? JsonArray).orEmptyTokens()
        val finished = (o["finished"] as? JsonPrimitive)?.booleanOrNull ?: false
        // Nothing actionable (no tokens, no end-of-stream, no error) -> ignore; forward-compatible.
        if (toks.isEmpty() && !finished && errCode == null) return null
        return Result(toks, finished, errCode, errLen)
    }

    private fun JsonArray?.orEmptyTokens(): List<Token> = this.orEmpty().mapNotNull {
        val t = it as? JsonObject ?: return@mapNotNull null
        val text = (t["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        Token(text, (t["is_final"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    @Serializable
    private data class Config(
        @SerialName("api_key") val apiKey: String,
        val model: String = "stt-rt-v5",
        @SerialName("audio_format") val audioFormat: String = "s16le",
        @SerialName("sample_rate") val sampleRate: Int = 16000,
        @SerialName("num_channels") val numChannels: Int = 1,
        @SerialName("language_hints") val languageHints: List<String>? = null,
    )
}

/**
 * Soniox stt-rt-v5 behind the [RealtimeProtocol] seam.
 *
 * **The key never becomes a field of this object.** It arrives per open through [bootstrap] and is
 * consumed by [SonioxEvents.config]; [reset]/`toString`/any exception path therefore have nothing to
 * leak. [SonioxNoLogDisciplineTest] attacks exactly this: reflection over the instance finds no
 * key-holding field, and a full session proves the config frame is the sole carrier. Errors cross the
 * seam as a numeric code + a message LENGTH — never content — mirroring the OpenAI/ElevenLabs paths.
 *
 * **Audio is RAW s16le BINARY at 16 kHz** ([okhttp3.WebSocket.send] `ByteString` overload via
 * [Frame.Binary]) — no resample, no base64. There is no server-issued turn boundary: non-final tokens
 * are the live preview and final tokens accumulate.
 *
 * **Client-assembled turns with a grace window (deviation from the plan's synchronous sketch — see the
 * report).** At the moment VAD cuts a turn ([onCommit]) the last words spoken are still *non-final*
 * (Soniox finalizes them a beat later as the continuing audio stream confirms them). Assembling
 * `finals` synchronously at [onCommit] would drop those trailing words AND misattribute them to the
 * NEXT turn once they finalize. So [onCommit] instead arms a short grace timer on the injected
 * [scheduler]; finals that land during the window keep accumulating for the just-cut turn, and when
 * the timer fires (or an inbound `finished:true` flushes early) the accumulated finals are assembled
 * and [RealtimeTransport.Listener.onCompleted] fires for the just-cut seq (the engine's oldest-unbound
 * fallback bind — binding stays deterministic because the grace timers fire in commit order). This is
 * exactly the async pattern [ElevenLabsRealtimeProtocol] uses for its deferred commit, on the same
 * scheduler.
 *
 * **Turn-assembly edge cases the engine's exactly-once ledger relies on:**
 *  - *Zero finals:* a cut turn that produced no final tokens resolves via `onCompleted(id, "")` — the
 *    engine's empty-completion outcome, NOT `onTranscriptionFailed` (which would be a hard loss). See
 *    the report for the EmptyExpected-vs-EmptyUnexpected note (the untouched engine classifies an
 *    empty completion as EmptyUnexpected, which the fallback rescues locally).
 *  - *Rotation mid-turn:* a 413/403 rotation closes the socket, and the transport surfaces that as
 *    `onDisconnected` -> the engine resolves the in-flight turn Lost -> local rescue. [bootstrap]
 *    (fired on every reopen) drops the pre-rotation turn state so it never bleeds into the reopened
 *    session, and any still-armed grace timer is invalidated by the superseded `pending` token.
 *
 * **Session rotation under the ceiling.** A 413 max-duration signal sends the empty-frame finalize and
 * then [SessionControl.rotate], which rides the transport's `scheduleReconnect` and so respects
 * `maxReconnects` — a pathological session gives up instead of rotating forever. A 403 session-expiry
 * rotates ONCE per session (the retry counter is cleared only in [reset], i.e. on session close);
 * the plan-note's per-open reset is deliberately NOT taken — see the report (it would let inband 403s
 * rotate forever, since each successful reopen resets the transport's own reconnect counter).
 */
class SonioxRealtimeProtocol(
    private val scheduler: ReconnectScheduler,
    /** How long to keep accumulating trailing finals for a just-cut turn before assembling it. */
    private val graceMs: Long = DEFAULT_GRACE_MS,
) : RealtimeProtocol {

    override val endpoint = ENDPOINT
    override val tolerant4xxRetry = false

    private lateinit var control: SessionControl
    private lateinit var sink: RealtimeTransport.Listener
    private val ids = AtomicLong(0)

    // All turn state guarded by `gate`; control.* / sink.* are never called while holding it.
    private val gate = Any()
    private val finals = StringBuilder()  // accumulated FINAL text for the turn currently open/closing
    private var lastPreview = ""          // last computed preview (finals + current non-finals)
    private var pending: Pending? = null  // a VAD-cut turn inside its grace window
    private var graceCounter = 0L         // one unique token per armed grace window (staleness guard)
    private var sessionExpiredRetries = 0 // 403 -> rotate once per session, then fatal

    private class Pending(val id: String, val token: Long)
    private class Completion(val id: String, val text: String)
    private class Armed(val id: String, val token: Long, val prior: Completion?)

    override fun upgradeHeaders(apiKey: String): List<Pair<String, String>> = emptyList() // key rides config

    override fun bind(control: SessionControl, sink: RealtimeTransport.Listener) {
        this.control = control
        this.sink = sink
    }

    /**
     * The ONLY carrier of the key: built here from [apiKey], returned, sent by the transport, never
     * retained. Also the per-open reset point for TURN state (finals/preview/pending) so a reconnect
     * or rotation starts clean — but NOT for [sessionExpiredRetries] (see the class KDoc / report).
     */
    override fun bootstrap(apiKey: String, language: String?): List<Frame> {
        synchronized(gate) {
            finals.setLength(0)
            lastPreview = ""
            pending = null // supersedes any still-armed grace timer via its now-stale token
        }
        return listOf(Frame.Text(SonioxEvents.config(apiKey, language)))
    }

    override fun onAppend(pcm16k: ByteArray): Boolean =
        control.send(Frame.Binary(pcm16k.toByteString())) // raw s16le binary — no resample, no base64

    /**
     * VAD close: arm the grace window for the just-cut turn. Nothing is sent; the turn resolves when
     * the grace timer fires (or `finished:true` flushes it). If a prior turn is still in its window
     * (two VAD cuts within [graceMs]), it is finalized first, OFF this thread, so no sink callback
     * runs under the transport's send lock.
     */
    override fun onCommit(): Boolean {
        val armed = synchronized(gate) {
            val prior = closePendingLocked() // finalize any in-grace turn before opening the next
            val id = ids.incrementAndGet().toString()
            val token = ++graceCounter
            pending = Pending(id, token)
            Armed(id, token, prior)
        }
        armed.prior?.let { c -> scheduler.schedule(0L) { fire(c) } } // in commit order, before the new grace
        scheduler.schedule(graceMs) { onGrace(armed.id, armed.token) }
        return true
    }

    override fun onText(text: String) {
        val r = SonioxEvents.parse(text) ?: return
        r.errorCode?.let { handleError(it, r.errorLen); return }

        var preview = ""
        var flushed: Completion? = null
        synchronized(gate) {
            val nonFinal = StringBuilder()
            for (t in r.tokens) if (t.isFinal) finals.append(t.text) else nonFinal.append(t.text)
            lastPreview = finals.toString() + nonFinal.toString()
            preview = lastPreview
            if (r.finished) flushed = closePendingLocked() // end-of-stream flush of the in-grace turn
        }
        sink.onDelta("", preview) // preview strip ONLY — never injected
        flushed?.let { fire(it) }
    }

    /**
     * Numeric error map. The content never crosses — [onErrorEvent] carries the code + length only.
     * 401/402 are terminal; 403 rotates once (session-expiry) then latches; 413 (max duration) sends
     * the empty-frame finalize and rotates; 429/5xx ride the transport's own drop-driven reconnect.
     * control.* runs OUTSIDE [gate] to keep the gate -> send-lock ordering out of a deadlock.
     */
    private fun handleError(code: Int, len: Int) {
        sink.onErrorEvent(code.toString(), len)
        when (code) {
            401 -> sink.onFatal(FatalKind.INVALID_KEY, code)
            402 -> sink.onFatal(FatalKind.OUT_OF_CREDIT, code)
            403 -> {
                val rotate = synchronized(gate) { sessionExpiredRetries++ == 0 }
                if (rotate) control.rotate() else sink.onFatal(FatalKind.FORBIDDEN, code)
            }
            413 -> {
                control.send(Frame.Binary(ByteArray(0).toByteString())) // finalize the session…
                control.rotate()                                        // …then reopen under the ceiling
            }
            429, in 500..599 -> Unit // transient: the socket drop/close drives reconnect (existing path)
            else -> Unit             // 400/408 and unknowns: benign, forward-compatible
        }
    }

    /** Handshake status -> fatal, or null = transient. Soniox authenticates INBAND (via [bootstrap]),
     *  so a handshake 4xx is rare; map it conservatively and terminally, never the inband 403 rotate. */
    override fun classifyFatal(code: Int): FatalKind? = when (code) {
        401 -> FatalKind.INVALID_KEY
        403 -> FatalKind.FORBIDDEN
        429 -> FatalKind.OUT_OF_CREDIT
        else -> null // 5xx / network -> transient -> reconnect
    }

    override fun reset() = synchronized(gate) {
        finals.setLength(0)
        lastPreview = ""
        pending = null
        sessionExpiredRetries = 0
    }

    /** Grace timer: assemble the accumulated finals for [id] and resolve its seq, unless superseded. */
    private fun onGrace(id: String, token: Long) {
        val completion = synchronized(gate) {
            val p = pending
            if (p == null || p.token != token) return // torn down / superseded by a later commit
            closePendingLocked()
        } ?: return
        fire(completion)
    }

    /** Caller holds [gate]: snapshot the in-grace turn's finals, clear the accumulator, return it. */
    private fun closePendingLocked(): Completion? {
        val p = pending ?: return null
        pending = null
        val text = finals.toString()
        finals.setLength(0)
        lastPreview = ""
        return Completion(p.id, text)
    }

    /** Resolve the just-cut seq exactly-once via the engine's oldest-unbound fallback bind. Never gated. */
    private fun fire(c: Completion) = sink.onCompleted(c.id, c.text)

    companion object {
        const val ENDPOINT = "wss://stt-rt.soniox.com/transcribe-websocket"

        /** Trailing finals almost always land inside this after a VAD cut on a continuous stream. */
        const val DEFAULT_GRACE_MS = 600L
    }
}
