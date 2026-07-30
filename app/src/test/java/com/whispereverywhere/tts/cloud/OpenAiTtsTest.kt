package com.whispereverywhere.tts.cloud

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResultBytes
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Decodes only the two fields this test cares about — kotlinx.serialization, not string-match,
 *  so the pin cannot pass by accident on a key that merely appears as a substring elsewhere. */
@Serializable
private data class CapturedSpeechBody(val model: String = "", val response_format: String = "")

class OpenAiTtsTest {

    private fun okBytes(bytes: ByteArray) = FakeHttpTransport().apply { queueBytes(HttpResultBytes.Ok(200, bytes)) }
    private fun errBytes(code: Int, body: String) =
        FakeHttpTransport().apply { queueBytes(HttpResultBytes.HttpError(code, body)) }

    @Test fun pcm_body_is_delivered_as_le_short_chunks() = runBlocking {
        val bytes = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
        val fake = okBytes(bytes)
        val collected = mutableListOf<Short>()

        val result = OpenAiTts(fake, "sk-test").synth("hello", "alloy", 1.0f) { pcm ->
            collected.addAll(pcm.toList())
            true
        }

        assertEquals(TtsResult.Done, result)
        assertEquals(listOf<Short>(0, 32767, -32768), collected)
    }

    @Test fun onPcm_returning_false_stops_and_returns_Cancelled() = runBlocking {
        val fake = okBytes(byteArrayOf(0x00, 0x00))
        val result = OpenAiTts(fake, "sk-test").synth("hello", "alloy", 1.0f) { false }
        assertEquals(TtsResult.Cancelled, result)
    }

    @Test fun http_401_is_Fatal_INVALID_KEY() = runBlocking {
        val fake = errBytes(401, "")
        val r = OpenAiTts(fake, "sk-test").synth("hello", "alloy", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as TtsError.Fatal).kind)
    }

    @Test fun http_403_is_Fatal_FORBIDDEN() = runBlocking {
        val fake = errBytes(403, "")
        val r = OpenAiTts(fake, "sk-test").synth("hello", "alloy", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.FORBIDDEN, (r.error as TtsError.Fatal).kind)
    }

    @Test fun a_429_with_quota_marker_is_Fatal_OUT_OF_CREDIT() = runBlocking {
        val fake = errBytes(429, """{"error":{"code":"insufficient_quota"}}""")
        val r = OpenAiTts(fake, "sk-test").synth("hello", "alloy", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as TtsError.Fatal).kind)
    }

    @Test fun a_plain_429_is_Transient() = runBlocking {
        val fake = errBytes(429, "slow down")
        val r = OpenAiTts(fake, "sk-test").synth("hello", "alloy", 1.0f) { true } as TtsResult.Failed
        assertTrue(r.error is TtsError.Transient)
    }

    @Test fun a_400_is_BadUnit() = runBlocking {
        val fake = errBytes(400, "")
        val r = OpenAiTts(fake, "sk-test").synth("hello", "alloy", 1.0f) { true } as TtsResult.Failed
        assertEquals(TtsError.BadUnit, r.error)
    }

    @Test fun a_422_is_BadUnit() = runBlocking {
        val fake = errBytes(422, "")
        val r = OpenAiTts(fake, "sk-test").synth("hello", "alloy", 1.0f) { true } as TtsResult.Failed
        assertEquals(TtsError.BadUnit, r.error)
    }

    @Test fun a_network_error_is_Offline() = runBlocking {
        val fake = FakeHttpTransport().apply { queueBytes(HttpResultBytes.NetworkError(IOException("no route"))) }
        val r = OpenAiTts(fake, "sk-test").synth("hello", "alloy", 1.0f) { true } as TtsResult.Failed
        assertEquals(TtsError.Offline, r.error)
    }

    @Test fun request_body_pins_model_and_pcm_format() = runBlocking {
        val fake = okBytes(ByteArray(0))
        OpenAiTts(fake, "sk-test").synth("hello there", "alloy", 1.0f) { true }

        val captured = Json { ignoreUnknownKeys = true }.decodeFromString<CapturedSpeechBody>(fake.lastJsonBody!!)
        assertEquals(OpenAiTts.DEFAULT_MODEL, captured.model)
        assertEquals("pcm", captured.response_format)
    }

    @Test fun voice_catalog_has_13_and_flags_marin_cedar_recommended() {
        assertEquals(13, OpenAiTtsVoices.ALL.size)
        val recommendedIds = OpenAiTtsVoices.ALL.filter { it.recommended }.map { it.voiceId }.toSet()
        assertEquals(setOf("marin", "cedar"), recommendedIds)
    }
}
