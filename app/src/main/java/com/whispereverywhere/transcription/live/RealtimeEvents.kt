package com.whispereverywhere.transcription.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Typed models + pure (Android-free) codec for the OpenAI Realtime transcription protocol.
 *
 * This file must run under `unitTests.isReturnDefaultValues = true`, so NOTHING from `android.*`
 * may appear here — not `android.util.Base64` (returns null under that flag) and not `org.json`
 * (ships in android.jar; its getters return type-defaults). Only kotlinx-serialization and, at the
 * seam above, `java.util.Base64` are used. See app/build.gradle.kts:166 and OpenAiStt's companion.
 *
 * Inbound events we consume, keyed by their `type` discriminator:
 *  - conversation.item.input_audio_transcription.delta      -> [Inbound.Delta]
 *  - conversation.item.input_audio_transcription.completed  -> [Inbound.Completed]
 *  - error                                                  -> [Inbound.Error]
 *  - session.updated / input_audio_buffer.committed         -> [Inbound.Ack] (log-and-ignore)
 * Every other type -> null (the stream carries many events we do not need; forward-compatible).
 */
sealed interface Inbound {
    /** A partial transcript for an in-flight turn. Preview ONLY — never injected (Spec §5.6a). */
    data class Delta(val itemId: String, val text: String) : Inbound

    /** A finished turn's transcript. Resolves its mapped seq exactly once. */
    data class Completed(val itemId: String, val transcript: String) : Inbound

    /**
     * An in-band error event. [message] is retained for the engine to map [code] -> FatalKind;
     * only its LENGTH is ever logged, never its content (it can echo request detail).
     */
    data class Error(val code: String?, val message: String) : Inbound

    /** A benign acknowledgement (`session.updated`, `input_audio_buffer.committed`). */
    data class Ack(val type: String) : Inbound
}

/**
 * Outbound event builders. Each returns a minified JSON `String` ready for `WebSocket.send`.
 *
 * The [sessionUpdate] shape is pinned VERBATIM against the live docs (2026-07-30): pcm @ 24 kHz,
 * model `gpt-live-transcribe`, `turn_detection: null` (WE commit turns via client VAD — no
 * double-VAD). `RealtimeEventParserTest.outbound_session_update_shape_is_exact` is the contract pin.
 */
object RealtimeEvents {

    fun sessionUpdate(): String = OUT.encodeToString(SessionUpdate())

    /** [base64] is already base64-encoded pcm16 (the engine owns the java.util.Base64 encode). */
    fun append(base64: String): String = OUT.encodeToString(AppendEvent(audio = base64))

    fun commit(): String = OUT.encodeToString(CommitEvent())

    // encodeDefaults=true so the constant `type` discriminators AND `turn_detection:null` are
    // emitted; explicitNulls (default true) writes the null rather than dropping it. The result
    // is deterministic and key-ordered by declaration, which is what the exact-shape test asserts.
    private val OUT = Json { encodeDefaults = true }

    @Serializable
    private data class SessionUpdate(
        val type: String = "session.update",
        val session: Session = Session(),
    )

    @Serializable
    private data class Session(
        val type: String = "transcription",
        val audio: Audio = Audio(),
    )

    @Serializable
    private data class Audio(val input: Input = Input())

    @Serializable
    private data class Input(
        val format: Format = Format(),
        val transcription: Transcription = Transcription(),
        // Always null: client-side VAD cuts turns, so the server must NOT run its own turn
        // detection. Declared nullable (never assigned non-null) purely to emit the null field.
        @SerialName("turn_detection") val turnDetection: String? = null,
    )

    @Serializable
    private data class Format(
        val type: String = "audio/pcm",
        val rate: Int = 24000,
    )

    @Serializable
    private data class Transcription(val model: String = "gpt-live-transcribe")

    @Serializable
    private data class AppendEvent(
        val type: String = "input_audio_buffer.append",
        val audio: String,
    )

    @Serializable
    private data class CommitEvent(val type: String = "input_audio_buffer.commit")
}

/**
 * Pure inbound parser. Total over the documented set, null-safe for everything else — an
 * unrecognized or malformed frame returns `null` rather than throwing, so a forward-compatible
 * stream never crashes the socket loop.
 */
object RealtimeEventParser {

    fun parse(json: String): Inbound? {
        val obj = try {
            IN.parseToJsonElement(json) as? JsonObject
        } catch (_: Throwable) {
            null
        } ?: return null

        return when (obj.string("type")) {
            TYPE_DELTA -> obj.string("item_id")?.let { Inbound.Delta(it, obj.string("delta").orEmpty()) }
            TYPE_COMPLETED -> obj.string("item_id")?.let { Inbound.Completed(it, obj.string("transcript").orEmpty()) }
            TYPE_ERROR -> {
                val err = obj["error"] as? JsonObject
                Inbound.Error(code = err?.string("code"), message = err?.string("message").orEmpty())
            }
            TYPE_SESSION_UPDATED -> Inbound.Ack(TYPE_SESSION_UPDATED)
            TYPE_COMMITTED -> Inbound.Ack(TYPE_COMMITTED)
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private val IN = Json { ignoreUnknownKeys = true }

    const val TYPE_DELTA = "conversation.item.input_audio_transcription.delta"
    const val TYPE_COMPLETED = "conversation.item.input_audio_transcription.completed"
    const val TYPE_ERROR = "error"
    const val TYPE_SESSION_UPDATED = "session.updated"
    const val TYPE_COMMITTED = "input_audio_buffer.committed"
}
