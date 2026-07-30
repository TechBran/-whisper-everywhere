package com.whispereverywhere.tts.cloud

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResultBytes
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

/** Decodes only the nested fields this test cares about — kotlinx.serialization, not string-match,
 *  so the pin cannot pass by accident on a substring that merely appears elsewhere in the body. */
@Serializable private data class CapturedTtsBody(val generationConfig: CapturedGenConfig = CapturedGenConfig())
@Serializable private data class CapturedGenConfig(
    val responseModalities: List<String> = emptyList(),
    val speechConfig: CapturedSpeechConfig = CapturedSpeechConfig(),
)
@Serializable private data class CapturedSpeechConfig(val voiceConfig: CapturedVoiceConfig = CapturedVoiceConfig())
@Serializable private data class CapturedVoiceConfig(val prebuiltVoiceConfig: CapturedPrebuilt = CapturedPrebuilt())
@Serializable private data class CapturedPrebuilt(val voiceName: String = "")

class GeminiTtsTest {

    private fun bytesFake(vararg results: HttpResultBytes) =
        FakeHttpTransport().apply { results.forEach { queueBytes(it) } }

    /** Builds the JSON envelope Gemini wraps base64 PCM in: candidates[0].content.parts[0].inlineData.data. */
    private fun audioJson(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val v = shorts[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val b64 = Base64.getEncoder().encodeToString(bytes)
        return """{"candidates":[{"content":{"parts":[{"inlineData":{"data":"$b64"}}]}}]}""".toByteArray(Charsets.UTF_8)
    }

    @Test fun flash_preview_success_decodes_base64_pcm() = runBlocking {
        // A test that would silently pass under android.util.Base64's JVM-test default is the
        // exact C1 trap this pins against — java.util.Base64 must be what actually decodes it.
        val fake = bytesFake(HttpResultBytes.Ok(200, audioJson(shortArrayOf(0, 32767, -32768))))
        val collected = mutableListOf<Short>()

        val result = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { pcm ->
            collected.addAll(pcm.toList()); true
        }

        assertEquals(TtsResult.Done, result)
        assertEquals(listOf<Short>(0, 32767, -32768), collected)
    }

    @Test fun onPcm_returning_false_stops_and_returns_Cancelled() = runBlocking {
        val fake = bytesFake(HttpResultBytes.Ok(200, audioJson(shortArrayOf(1, 2, 3))))
        val result = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { false }
        assertEquals(TtsResult.Cancelled, result)
    }

    @Test fun first_model_404_falls_back_to_second_model() = runBlocking {
        val fake = bytesFake(
            HttpResultBytes.HttpError(
                404,
                """{"error":{"code":404,"status":"NOT_FOUND","message":"models/gemini-3.1-flash-tts-preview is not found"}}""",
            ),
            HttpResultBytes.Ok(200, audioJson(shortArrayOf(7, 8, 9))),
        )
        val collected = mutableListOf<Short>()

        val result = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { pcm ->
            collected.addAll(pcm.toList()); true
        }

        assertEquals(TtsResult.Done, result)
        assertEquals(2, fake.callCount)
        assertTrue(fake.lastUrl!!, fake.lastUrl!!.contains("gemini-2.5-flash-preview-tts"))
        assertEquals(listOf<Short>(7, 8, 9), collected)
    }

    @Test fun both_models_404_is_Fatal_MODEL_UNAVAILABLE() = runBlocking {
        val fake = bytesFake(
            HttpResultBytes.HttpError(404, """{"error":{"code":404,"status":"NOT_FOUND"}}"""),
            HttpResultBytes.HttpError(404, """{"error":{"code":404,"status":"NOT_FOUND"}}"""),
        )
        val r = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.MODEL_UNAVAILABLE, (r.error as TtsError.Fatal).kind)
        assertEquals(2, fake.callCount) // both pinned models were tried, not just the first
    }

    @Test fun gemini_400_API_KEY_INVALID_is_Fatal_INVALID_KEY() = runBlocking {
        // The C1/C2b trap: Gemini rejects a bad key with 400, not 401 -- discriminate on the
        // API_KEY_INVALID marker, not the status code, and do NOT walk to the second model.
        val body = """{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}"""
        val fake = bytesFake(HttpResultBytes.HttpError(400, body))
        val r = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as TtsError.Fatal).kind)
        assertEquals(1, fake.callCount) // a bad key must not spend a second request on model 2
    }

    @Test fun a_plain_400_without_the_marker_is_BadUnit_not_a_key_fault() = runBlocking {
        val fake = bytesFake(HttpResultBytes.HttpError(400, """{"error":{"message":"malformed request"}}"""))
        val r = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { true } as TtsResult.Failed
        assertEquals(TtsError.BadUnit, r.error)
    }

    @Test fun a_403_is_Fatal_FORBIDDEN() = runBlocking {
        val fake = bytesFake(HttpResultBytes.HttpError(403, "PERMISSION_DENIED"))
        val r = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.FORBIDDEN, (r.error as TtsError.Fatal).kind)
    }

    @Test fun a_429_resource_exhausted_is_Fatal_OUT_OF_CREDIT() = runBlocking {
        val fake = bytesFake(HttpResultBytes.HttpError(429, """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}"""))
        val r = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as TtsError.Fatal).kind)
    }

    @Test fun a_plain_429_is_Transient() = runBlocking {
        val fake = bytesFake(HttpResultBytes.HttpError(429, "slow down"))
        val r = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { true } as TtsResult.Failed
        assertTrue(r.error is TtsError.Transient)
    }

    @Test fun a_5xx_is_Transient() = runBlocking {
        val fake = bytesFake(HttpResultBytes.HttpError(503, "upstream"))
        val r = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { true } as TtsResult.Failed
        assertTrue(r.error is TtsError.Transient)
    }

    @Test fun a_network_error_is_Offline() = runBlocking {
        val fake = bytesFake(HttpResultBytes.NetworkError(IOException("no route")))
        val r = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { true } as TtsResult.Failed
        assertEquals(TtsError.Offline, r.error)
    }

    @Test fun no_audio_part_is_Transient_not_empty() = runBlocking {
        // A refusal / safety finishReason returns a candidate with no inlineData part. That is
        // NOT silence -- mirrors GeminiStt's "a refusal is not an empty transcript" discipline.
        val fake = bytesFake(HttpResultBytes.Ok(200, """{"candidates":[{"finishReason":"SAFETY"}]}""".toByteArray()))
        val r = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { true } as TtsResult.Failed
        assertTrue(r.error is TtsError.Transient)
    }

    @Test fun an_unparseable_200_body_is_Transient_not_a_throw() = runBlocking {
        val fake = bytesFake(HttpResultBytes.Ok(200, "not json".toByteArray()))
        val r = GeminiTts(fake, "g-test").synth("hello", "Zephyr", 1.0f) { true } as TtsResult.Failed
        assertTrue(r.error is TtsError.Transient)
    }

    @Test fun an_empty_unit_fails_locally_without_a_request() = runBlocking {
        val fake = bytesFake(HttpResultBytes.Ok(200, audioJson(shortArrayOf(1))))
        val r = GeminiTts(fake, "g-test").synth("   ", "Zephyr", 1.0f) { true } as TtsResult.Failed
        assertEquals(TtsError.BadUnit, r.error)
        assertEquals("must not hit the network", 0, fake.callCount)
    }

    @Test fun the_key_rides_the_header_and_is_never_in_the_url() = runBlocking {
        val fake = bytesFake(HttpResultBytes.Ok(200, audioJson(shortArrayOf(1))))
        GeminiTts(fake, "g-secret").synth("hello", "Zephyr", 1.0f) { true }
        assertEquals("g-secret", fake.lastHeaders["x-goog-api-key"])
        assertFalse("key must never appear in the URL", fake.lastUrl!!.contains("g-secret"))
        assertFalse("no ?key= query param", fake.lastUrl!!.contains("key="))
    }

    @Test fun request_body_pins_the_voice_name_and_audio_modality() = runBlocking {
        val fake = bytesFake(HttpResultBytes.Ok(200, audioJson(shortArrayOf(1))))
        GeminiTts(fake, "g-test").synth("hello there", "Kore", 1.0f) { true }

        val captured = Json { ignoreUnknownKeys = true }.decodeFromString<CapturedTtsBody>(fake.lastJsonBody!!)
        assertEquals(listOf("AUDIO"), captured.generationConfig.responseModalities)
        assertEquals("Kore", captured.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName)
        assertTrue(fake.lastUrl!!, fake.lastUrl!!.contains("gemini-3.1-flash-tts-preview:generateContent"))
    }

    @Test fun voice_catalog_has_30() {
        assertEquals(30, GeminiTtsVoices.ALL.size)
        // Every voice id round-trips as its own display name -- the catalog is static, not
        // reshaped, and voiceId is exactly the string the API's prebuiltVoiceConfig.voiceName wants.
        assertTrue(GeminiTtsVoices.ALL.all { it.displayName == it.voiceId })
    }
}
