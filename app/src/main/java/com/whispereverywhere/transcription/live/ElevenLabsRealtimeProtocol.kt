package com.whispereverywhere.transcription.live

import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure ElevenLabs Scribe-v2-Realtime codec — Android-free (kotlinx-serialization only, no
 * `org.json`, no `android.util.Base64`), so it runs under `unitTests.isReturnDefaultValues = true`.
 * Verbatim-doc JSON drives [ElevenLabsRealtimeProtocolTest].
 *
 * Wire (recon + docs 2026-07-31): outbound a single frame type carries base64 PCM16 with a commit
 * flag; inbound `partial_transcript` is the live preview and `committed_transcript` is the finished
 * turn; errors arrive as `input_error`. Unknown types return null (forward-compatible).
 */
object ElevenLabsEvents {
    private val OUT = Json { encodeDefaults = true }
    private val IN = Json { ignoreUnknownKeys = true }

    /** [audioB64] is base64 of NATIVE 16 kHz PCM16 — ElevenLabs takes 16 k directly (no 24 k upsample). */
    fun audioChunk(audioB64: String, commit: Boolean): String =
        OUT.encodeToString(Chunk(audioBase64 = audioB64, commit = commit))

    sealed interface In {
        data class Partial(val text: String) : In
        data class Committed(val text: String) : In
        data class Error(val message: String) : In
    }

    fun parse(json: String): In? {
        val o = try { IN.parseToJsonElement(json) as? JsonObject } catch (_: Throwable) { null } ?: return null
        return when (o.str("message_type")) {
            "partial_transcript" -> In.Partial(o.str("text").orEmpty())
            "committed_transcript" -> In.Committed(o.str("text").orEmpty())
            "input_error" -> In.Error(o.str("message").orEmpty())
            else -> null // forward-compatible
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    @Serializable
    private data class Chunk(
        @SerialName("message_type") val messageType: String = "input_audio_chunk",
        @SerialName("audio_base_64") val audioBase64: String,
        val commit: Boolean,
        @SerialName("sample_rate") val sampleRate: Int = 16000,
    )
}

/**
 * ElevenLabs Scribe v2 Realtime behind the [RealtimeProtocol] seam, in SERVER-DRIVEN mode
 * (`commit_strategy=vad`, on the query string): the SERVER segments on its own VAD and emits a
 * `committed_transcript` at each boundary, carrying the finished turn's final text. ElevenLabs emits
 * NO item ids, so this adapter SYNTHESIZES the correlation the engine's FIFO bind expects — but now
 * the boundary and the completion are ONE event, so there is no in-flight window to protect:
 *  - every audio chunk streams immediately `commit:false` (the mic is open; partials stream as spoken),
 *  - a `committed_transcript` mints a synthetic id, emits `onCommitted(id)` (rotate the mirror +
 *    allocate the seq) then `onCompleted(id, text)` (resolve it exactly-once), and
 *  - `partial_transcript` is the live preview (`onDelta`), never binding or completing.
 *
 * The fold-slot / single-in-flight serialization / burned-count / timeout machinery of the client-VAD
 * era is GONE: it existed only to correlate CLIENT commits, which no longer exist under server VAD.
 * [onCommit] is a benign no-op — NOT a retained fallback: the machinery a client-VAD turn needs was
 * removed here, so the serverDriven seam is documented, not built (re-enabling it would rebuild that).
 *
 * Credential rule: the key rides the `xi-api-key` UPGRADE HEADER only (never a config frame, never a
 * field of this object). Content never crosses to a callback — deltas/completions carry text to the
 * engine's preview/ledger; errors carry code + length only.
 */
class ElevenLabsRealtimeProtocol : RealtimeProtocol {

    override val endpoint = ENDPOINT
    override val tolerant4xxRetry = false

    private lateinit var control: SessionControl
    private lateinit var sink: RealtimeTransport.Listener
    private val ids = AtomicLong(0)

    override fun upgradeHeaders(apiKey: String): List<Pair<String, String>> {
        val p = ProviderCatalog.byId(ProviderId.ELEVENLABS) // xi-api-key, bare value
        return listOf(p.authHeaderName to p.authHeaderValue(apiKey))
    }

    override fun bind(control: SessionControl, sink: RealtimeTransport.Listener) {
        this.control = control
        this.sink = sink
    }

    /** No config frame — `commit_strategy=vad` rides the query string; nothing is held per open. */
    override fun bootstrap(apiKey: String, language: String?): List<Frame> = emptyList()

    /** Stream every chunk immediately, commit:false — the SERVER VAD commits segments. 16 k native, no resample. */
    override fun onAppend(pcm16k: ByteArray): Boolean {
        val b64 = Base64.getEncoder().encodeToString(pcm16k)
        return control.send(Frame.Text(ElevenLabsEvents.audioChunk(b64, commit = false)))
    }

    /** Unreachable no-op — the server commits under VAD; the client-VAD commit path was removed, not retained here. */
    override fun onCommit(): Boolean = true

    override fun onText(text: String) {
        when (val e = ElevenLabsEvents.parse(text)) {
            is ElevenLabsEvents.In.Partial -> sink.onDelta("", e.text) // preview only, streams as spoken
            is ElevenLabsEvents.In.Committed -> {
                // Server turn boundary + final text in one event: allocate+bind the seq, then resolve it.
                val id = ids.incrementAndGet().toString()
                sink.onCommitted(id)          // rotate the mirror + allocate the seq (server-driven turn)
                sink.onCompleted(id, e.text)  // resolve it exactly-once via the engine path
            }
            is ElevenLabsEvents.In.Error ->
                sink.onErrorEvent("input_error", e.message.length) // length only — never content
            null -> Unit
        }
    }

    override fun classifyFatal(code: Int): FatalKind? = when (code) {
        401, 403 -> FatalKind.INVALID_KEY
        429 -> FatalKind.OUT_OF_CREDIT
        else -> null // 5xx / network -> transient -> reconnect
    }

    override fun reset() {}

    companion object {
        const val ENDPOINT =
            "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&commit_strategy=vad"
    }
}
