package com.whispereverywhere.transcription.live

import com.whispereverywhere.text.TextJoin
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
    fun config(apiKey: String, language: String?, includeEndpointDetection: Boolean = true): String =
        OUT.encodeToString(
            Config(
                apiKey = apiKey,
                // null (not false) when excluded — explicitNulls=false omits the field entirely,
                // which is the point of the reduced config: the live server 400'd the FULL config
                // (2026-07-31, on-device) despite the field being documented; the cascade retries
                // without it so streaming still works while the docs/server disagreement stands.
                enableEndpointDetection = if (includeEndpointDetection) true else null,
                languageHints = language?.let { listOf(it) },
            ),
        )

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

    /**
     * The ONE structure that ever holds the key outside SecureStore. Deliberately NOT a `data`
     * class: a data class's synthesized toString() renders every field — one debug log or
     * exception interpolation away from the key in cleartext. A plain class has Object's
     * identity toString, and the explicit override below makes the redaction permanent rather
     * than accidental. (Release-audit carried Minor, closed 2026-07-31.)
     */
    @Serializable
    private class Config(
        @SerialName("api_key") val apiKey: String,
        val model: String = "stt-rt-v5",
        @SerialName("audio_format") val audioFormat: String = "s16le",
        @SerialName("sample_rate") val sampleRate: Int = 16000,
        @SerialName("num_channels") val numChannels: Int = 1,
        // Server endpoint detection: Soniox emits an `<end>` token at each speech boundary, which is
        // the SERVER turn signal driving the engine's server-turn allocation (the 2026-07-31
        // inversion). Nullable: null OMITS the field (explicitNulls=false) — the reduced-config
        // cascade level after the live server rejected the full config.
        @SerialName("enable_endpoint_detection") val enableEndpointDetection: Boolean? = true,
        @SerialName("language_hints") val languageHints: List<String>? = null,
    ) {
        override fun toString(): String = "Config(api_key=<redacted>, model=$model)"
    }
}

/**
 * Soniox stt-rt-v5 behind the [RealtimeProtocol] seam, in SERVER-DRIVEN mode
 * (`enable_endpoint_detection:true`): the SERVER marks each speech boundary with an `<end>` token,
 * which is the turn signal driving the engine's server-turn allocation (the 2026-07-31 inversion).
 *
 * **The key never becomes a field of this object.** It arrives per open through [bootstrap] and is
 * consumed by [SonioxEvents.config]; [reset]/`toString`/any exception path therefore have nothing to
 * leak. [SonioxNoLogDisciplineTest] attacks exactly this: reflection over the instance finds no
 * key-holding field, and a full session proves the config frame is the sole carrier. Errors cross the
 * seam as a numeric code + a message LENGTH — never content — mirroring the OpenAI/ElevenLabs paths.
 *
 * **Audio is RAW s16le BINARY at 16 kHz** ([okhttp3.WebSocket.send] `ByteString` overload via
 * [Frame.Binary]) — no resample, no base64. Non-final tokens are the live preview (streamed as spoken)
 * and final tokens accumulate into the current turn.
 *
 * **The `<end>` token IS the turn boundary.** On each `<end>` (always final, once per segment) the
 * accumulated finals are snapshotted into a fresh synthetic id and the accumulator opens the next
 * empty turn: `onCommitted(id)` (rotate the mirror + allocate the seq) then `onCompleted(id, finals)`
 * (resolve it exactly-once). The client-VAD-era grace window / `onCommit` machinery is GONE — server
 * endpoint detection finalizes the trailing words itself, so the race the grace window guarded no
 * longer exists. [onCommit] is a benign no-op — NOT a retained fallback: the grace-window machinery a
 * client-VAD turn needs was removed here, so the serverDriven seam is documented, not built.
 *
 * **Turn-assembly edge cases the engine's exactly-once ledger relies on:**
 *  - *Zero finals:* an `<end>` with no accumulated finals resolves via `onCompleted(id, "")` — the
 *    engine's empty-completion outcome (EmptyUnexpected, rescued locally), NOT `onTranscriptionFailed`.
 *  - *The `<end>` token never enters `finals`*, so it can never leak into a transcript.
 *  - *Boundaries fire OUTSIDE [gate]* (built under the lock, fired after it) to preserve the no-deadlock
 *    discipline.
 *
 * **Session rotation under the ceiling.** A 413 max-duration signal sends the empty-frame finalize and
 * then [SessionControl.rotate], which rides the transport's `scheduleReconnect` and so respects
 * `maxReconnects` — a pathological session gives up instead of rotating forever. A 403 session-expiry
 * rotates ONCE per session (the retry counter is cleared only in [reset], i.e. on session close);
 * the plan-note's per-open reset is deliberately NOT taken (it would let inband 403s rotate forever,
 * since each successful reopen resets the transport's own reconnect counter).
 */
class SonioxRealtimeProtocol : RealtimeProtocol {

    override val endpoint = ENDPOINT
    override val tolerant4xxRetry = false

    private lateinit var control: SessionControl
    private lateinit var sink: RealtimeTransport.Listener
    private val ids = AtomicLong(0)

    // All turn state guarded by `gate`; control.* / sink.* are never called while holding it.
    private val gate = Any()
    private val finals = StringBuilder()  // accumulated FINAL text for the turn currently open
    private var lastPreview = ""          // last computed preview (finals + current non-finals)
    private var sessionExpiredRetries = 0 // 403 -> rotate once per session, then fatal

    /**
     * The tolerant-config cascade (the OpenAI beta-header pattern, applied to Soniox's config
     * message). Live evidence 2026-07-31: the server answers 400 to the FULL config even though
     * `enable_endpoint_detection` is documented — docs and server disagree, and the server wins.
     * On the FIRST pre-token 400 we flip to the REDUCED config (field omitted) and rotate once,
     * silently. Reduced mode still streams deltas word-for-word; without `<end>` boundaries the
     * open turn resolves at session stop (the existing stop-path drain), a degraded-but-working
     * cadence. A 400 on the reduced config latches MODEL_UNAVAILABLE — visible, never silent.
     * Once flipped, reduced sticks for the protocol's lifetime. [tokensSeen] distinguishes a
     * config rejection (pre-token) from a mid-session 400 (left benign, forward-compatible).
     */
    @Volatile private var reducedConfig = false
    @Volatile private var tokensSeen = false

    private class Completion(val id: String, val text: String)

    override fun upgradeHeaders(apiKey: String): List<Pair<String, String>> = emptyList() // key rides config

    override fun bind(control: SessionControl, sink: RealtimeTransport.Listener) {
        this.control = control
        this.sink = sink
    }

    /**
     * The ONLY carrier of the key: built here from [apiKey], returned, sent by the transport, never
     * retained. Also the per-open reset point for TURN state (finals/preview) so a reconnect or
     * rotation starts clean — but NOT for [sessionExpiredRetries] (see the class KDoc).
     */
    override fun bootstrap(apiKey: String, language: String?): List<Frame> {
        synchronized(gate) {
            finals.setLength(0)
            lastPreview = ""
        }
        tokensSeen = false
        return listOf(Frame.Text(SonioxEvents.config(apiKey, language, includeEndpointDetection = !reducedConfig)))
    }

    override fun onAppend(pcm16k: ByteArray): Boolean =
        control.send(Frame.Binary(pcm16k.toByteString())) // raw s16le binary — no resample, no base64

    /** Unreachable no-op — the server marks turns via `<end>`; the client-VAD grace-window path was removed, not retained here. */
    override fun onCommit(): Boolean = true

    override fun onText(text: String) {
        val r = SonioxEvents.parse(text) ?: return
        r.errorCode?.let { handleError(it, r.errorLen); return }
        if (r.tokens.isNotEmpty()) tokensSeen = true

        var preview = ""
        val boundaries = ArrayList<Completion>() // built under gate, fired outside it (deadlock discipline)
        synchronized(gate) {
            val nonFinal = StringBuilder()
            for (t in r.tokens) when {
                t.isFinal && t.text == END_TOKEN -> {
                    // Server segment boundary: snapshot the finals for this turn and open the next empty.
                    boundaries.add(Completion(ids.incrementAndGet().toString(), finals.toString()))
                    finals.setLength(0)
                }
                // A real finalized word — melt-proof join (a no-op over Soniox's baked spacing, a single
                // space only where two alphanumerics would otherwise touch).
                t.isFinal -> appendJoined(finals, t.text)
                // Preview only, replaced each message.
                else -> nonFinal.append(t.text)
            }
            lastPreview = TextJoin.join(finals.toString(), nonFinal.toString())
            preview = lastPreview
        }
        sink.onDelta("", preview) // streams non-final tokens AS SPOKEN — preview strip only, never injected
        for (c in boundaries) { sink.onCommitted(c.id); fire(c) } // allocate+bind then resolve, in order
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
            400 -> when {
                // Pre-token 400 = the server rejected our CONFIG (live-proven 2026-07-31 against
                // the documented enable_endpoint_detection). One silent cascade to the reduced
                // config; a second pre-token 400 is believed and latches VISIBLY — a config the
                // server rejects cannot self-heal, and silent degradation is the failure mode
                // this whole codebase exists to prevent.
                !tokensSeen && !reducedConfig -> {
                    reducedConfig = true
                    android.util.Log.w(TAG, "soniox config rejected -> reduced config, rotating")
                    control.rotate()
                }
                !tokensSeen -> sink.onFatal(FatalKind.MODEL_UNAVAILABLE, code)
                else -> Unit // mid-session 400: unknown cause on a working stream — leave it alone
            }
            else -> Unit             // 408 and unknowns: benign, forward-compatible
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
        sessionExpiredRetries = 0
    }

    /** Resolve the just-cut seq exactly-once via the engine's oldest-unbound fallback bind. Never gated.
     *  A zero-final `<end>` fires `onCompleted(id, "")` — the engine's EmptyUnexpected -> local rescue. */
    private fun fire(c: Completion) = sink.onCompleted(c.id, c.text)

    /** Append [text] to [sb] under the shared join policy: a single space only where two runs melt. */
    private fun appendJoined(sb: StringBuilder, text: String) {
        if (text.isEmpty()) return
        if (sb.isNotEmpty() && TextJoin.needsSpace(sb, text)) sb.append(' ')
        sb.append(text)
    }

    companion object {
        private const val TAG = "WE-DIAG"
        const val ENDPOINT = "wss://stt-rt.soniox.com/transcribe-websocket"

        /** The server's endpoint-detection boundary token; always final, once per segment. Never injected. */
        const val END_TOKEN = "<end>"
    }
}
