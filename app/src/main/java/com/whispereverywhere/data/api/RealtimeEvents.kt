package com.whispereverywhere.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Server → client events we care about, plus a catch-all. */
sealed class ServerEvent {
    data class Delta(val text: String) : ServerEvent()
    data class Completed(val text: String) : ServerEvent()
    data class Error(val message: String) : ServerEvent()
    data class Other(val type: String) : ServerEvent()
}

/**
 * Builds client → server JSON for OpenAI realtime transcription (GA interface).
 *
 * gpt-realtime-whisper does NOT support server-side turn detection, so we omit
 * `turn_detection` entirely and drive transcription with manual
 * `input_audio_buffer.commit` calls (see [commit]). `delay` is the model's
 * latency/accuracy dial: minimal | low | medium | high | xhigh.
 */
object RealtimeEventFactory {

    fun sessionUpdate(model: String, language: String?, delay: String = "low"): String =
        buildJsonObject {
            put("type", "session.update")
            putJsonObject("session") {
                put("type", "transcription")
                putJsonObject("audio") {
                    putJsonObject("input") {
                        putJsonObject("format") {
                            put("type", "audio/pcm")
                            put("rate", 24000)
                        }
                        putJsonObject("transcription") {
                            put("model", model)
                            if (language != null) put("language", language)
                            put("delay", delay)
                        }
                        // No turn_detection: gpt-realtime-whisper requires manual commits.
                    }
                }
            }
        }.toString()

    fun appendAudio(base64Pcm: String): String =
        buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("audio", base64Pcm)
        }.toString()

    fun commit(): String =
        buildJsonObject { put("type", "input_audio_buffer.commit") }.toString()
}

/** Parses a single server JSON line into a [ServerEvent]. */
object RealtimeEventParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val DELTA = "conversation.item.input_audio_transcription.delta"
    private const val COMPLETED = "conversation.item.input_audio_transcription.completed"

    fun parse(raw: String): ServerEvent {
        val obj: JsonObject = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return ServerEvent.Other("unparseable")
        }
        val type = obj["type"]?.jsonPrimitive?.content ?: return ServerEvent.Other("missing")
        return when (type) {
            DELTA -> ServerEvent.Delta(obj["delta"]?.jsonPrimitive?.content ?: "")
            COMPLETED -> ServerEvent.Completed(obj["transcript"]?.jsonPrimitive?.content ?: "")
            "error" -> {
                val msg = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                ServerEvent.Error(msg)
            }
            else -> ServerEvent.Other(type)
        }
    }
}
