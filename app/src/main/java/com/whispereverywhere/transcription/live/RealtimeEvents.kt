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
 *  - conversation.item.input_audio_transcription.failed     -> [Inbound.Failed]
 *  - input_audio_buffer.committed                           -> [Inbound.Committed] (carries item_id)
 *  - error                                                  -> [Inbound.Error]
 *  - session.updated                                        -> [Inbound.Ack] (log-and-ignore)
 * Every other type -> null (the stream carries many events we do not need; forward-compatible).
 */
sealed interface Inbound {
    /** A partial transcript for an in-flight turn. Preview ONLY — never injected (Spec §5.6a). */
    data class Delta(val itemId: String, val text: String) : Inbound

    /** A finished turn's transcript. Resolves its mapped seq exactly once. */
    data class Completed(val itemId: String, val transcript: String) : Inbound

    /**
     * The server's ack of our `input_audio_buffer.commit`: it has created the item and assigned it
     * [itemId]. This is the DETERMINISTIC, in-commit-order signal the engine binds item_id<->seq on
     * — not the first delta/completed, whose order the protocol does not guarantee across turns.
     */
    data class Committed(val itemId: String) : Inbound

    /**
     * A per-item transcription failure (`...transcription.failed`). The item exists but yields no
     * transcript, so the engine resolves its bound seq Lost for the local fallback to rescue.
     */
    data class Failed(val itemId: String) : Inbound

    /**
     * An in-band error event. [message] is retained for the engine to map [code] -> FatalKind;
     * only its LENGTH is ever logged, never its content (it can echo request detail).
     */
    data class Error(val code: String?, val message: String) : Inbound

    /** A benign acknowledgement (`session.updated`). */
    data class Ack(val type: String) : Inbound
}

/**
 * Outbound event builders. Each returns a minified JSON `String` ready for `WebSocket.send`.
 *
 * The [sessionUpdate] shape is pinned VERBATIM against the live docs: pcm @ 24 kHz, model
 * `gpt-live-transcribe`, and `turn_detection: server_vad` — the SERVER detects speech boundaries and
 * auto-commits, so the item is created mid-speech and transcription deltas stream AS SPOKEN. WE no
 * longer commit turns on the live path (the 2026-07-31 server-driven inversion).
 * `RealtimeEventParserTest.outbound_session_update_shape_is_exact` is the contract pin.
 */
object RealtimeEvents {

    fun sessionUpdate(): String = OUT.encodeToString(SessionUpdate())

    /** [base64] is already base64-encoded pcm16 (the engine owns the java.util.Base64 encode). */
    fun append(base64: String): String = OUT.encodeToString(AppendEvent(audio = base64))

    fun commit(): String = OUT.encodeToString(CommitEvent())

    // encodeDefaults=true so the constant `type` discriminators AND the full server_vad turn_detection
    // object are emitted. The result is deterministic and key-ordered by declaration, which is what
    // the exact-shape test asserts.
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
        // server_vad — the SERVER detects speech boundaries and auto-commits, creating the item
        // mid-speech so transcription deltas stream AS SPOKEN. (Was null, which created no item until
        // our client commit — the field bug the 2026-07-31 server-driven inversion fixes.)
        @SerialName("turn_detection") val turnDetection: TurnDetection = TurnDetection(),
    )

    // Defaults from the realtime-vad guide; create_response / interrupt_response are inert in a
    // transcription session, so they are omitted.
    @Serializable
    private data class TurnDetection(
        val type: String = "server_vad",
        val threshold: Double = 0.5,
        @SerialName("prefix_padding_ms") val prefixPaddingMs: Int = 300,
        @SerialName("silence_duration_ms") val silenceDurationMs: Int = 500,
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
            TYPE_FAILED -> obj.string("item_id")?.let { Inbound.Failed(it) }
            TYPE_ERROR -> {
                val err = obj["error"] as? JsonObject
                Inbound.Error(code = err?.string("code"), message = err?.string("message").orEmpty())
            }
            TYPE_SESSION_UPDATED -> Inbound.Ack(TYPE_SESSION_UPDATED)
            // The commit ack carries the new item's id; without it there is nothing to bind, so it
            // degrades to a benign ack rather than a null (still forward-compatible).
            TYPE_COMMITTED -> obj.string("item_id")?.let { Inbound.Committed(it) } ?: Inbound.Ack(TYPE_COMMITTED)
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private val IN = Json { ignoreUnknownKeys = true }

    const val TYPE_DELTA = "conversation.item.input_audio_transcription.delta"
    const val TYPE_COMPLETED = "conversation.item.input_audio_transcription.completed"
    const val TYPE_FAILED = "conversation.item.input_audio_transcription.failed"
    const val TYPE_ERROR = "error"
    const val TYPE_SESSION_UPDATED = "session.updated"
    const val TYPE_COMMITTED = "input_audio_buffer.committed"
}
