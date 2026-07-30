package com.whispereverywhere.tts.cloud

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.net.HttpResultBytes
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Decodes only the two fields this test cares about — kotlinx.serialization, not string-match, so
 *  the flash-id pin cannot pass by accident on a substring that appears elsewhere in the body. */
@Serializable
private data class CapturedSynthBody(val text: String = "", val model_id: String = "")

class ElevenLabsTtsTest {

    // A stand-in for the framework mp3 decode. The real decodeMp3ToPcm24k runs MediaCodec, which is
    // instrumented compile-check only; a logic test proves "the mp3 branch was taken" by asserting
    // THIS marker was what reached onPcm — never running the codec.
    private val decodeMarker = shortArrayOf(11, 22, 33)

    private fun bytesFake(vararg results: HttpResultBytes) =
        FakeHttpTransport().apply { results.forEach { queueBytes(it) } }

    private fun el(
        transport: FakeHttpTransport,
        decode: (ByteArray) -> ShortArray = { decodeMarker },
    ) = ElevenLabsTts(transport, "xi-test", context = null, decodeMp3 = decode)

    @Test fun pcm_24000_success_delivers_shorts() = runBlocking {
        val body = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
        val fake = bytesFake(HttpResultBytes.Ok(200, body))
        val collected = mutableListOf<Short>()

        val result = el(fake).synth("hello", "voiceX", 1.0f) { pcm ->
            collected.addAll(pcm.toList()); true
        }

        assertEquals(TtsResult.Done, result)
        assertEquals(listOf<Short>(0, 32767, -32768), collected)
        assertTrue(fake.lastUrl!!, fake.lastUrl!!.contains("output_format=pcm_24000"))
    }

    @Test fun tier_rejection_on_pcm_triggers_mp3_retry() = runBlocking {
        val fake = bytesFake(
            HttpResultBytes.HttpError(
                401,
                """{"detail":{"status":"invalid_output_format","message":"output_format pcm_24000 is not available on your tier"}}""",
            ),
            HttpResultBytes.Ok(200, byteArrayOf(1, 2, 3, 4)), // pretend-mp3 body; decode is stubbed
        )
        val collected = mutableListOf<Short>()

        val result = el(fake) { decodeMarker }.synth("hello", "voiceX", 1.0f) { pcm ->
            collected.addAll(pcm.toList()); true
        }

        assertEquals(TtsResult.Done, result)
        assertEquals(2, fake.callCount) // pcm attempt + mp3 retry
        assertTrue(fake.lastUrl!!, fake.lastUrl!!.contains("output_format=mp3_44100_128"))
        assertEquals(decodeMarker.toList(), collected) // the DECODED mp3 shorts were delivered
    }

    @Test fun plain_401_without_format_marker_is_Fatal_INVALID_KEY() = runBlocking {
        val fake = bytesFake(
            HttpResultBytes.HttpError(401, """{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}"""),
        )
        val r = el(fake).synth("hello", "voiceX", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as TtsError.Fatal).kind)
        assertEquals(1, fake.callCount) // a real bad key must NOT be retried as mp3
    }

    @Test fun a_422_is_BadUnit() = runBlocking {
        val fake = bytesFake(HttpResultBytes.HttpError(422, ""))
        val r = el(fake).synth("hello", "voiceX", 1.0f) { true } as TtsResult.Failed
        assertEquals(TtsError.BadUnit, r.error)
    }

    @Test fun onPcm_returning_false_stops_and_returns_Cancelled() = runBlocking {
        val fake = bytesFake(HttpResultBytes.Ok(200, byteArrayOf(0x00, 0x00)))
        val result = el(fake).synth("hello", "voiceX", 1.0f) { false }
        assertEquals(TtsResult.Cancelled, result)
    }

    @Test fun a_network_error_is_Offline() = runBlocking {
        val fake = bytesFake(HttpResultBytes.NetworkError(IOException("no route")))
        val r = el(fake).synth("hello", "voiceX", 1.0f) { true } as TtsResult.Failed
        assertEquals(TtsError.Offline, r.error)
    }

    @Test fun request_body_pins_flash_model_and_text() = runBlocking {
        val fake = bytesFake(HttpResultBytes.Ok(200, ByteArray(0)))
        el(fake).synth("hello there", "voiceX", 1.0f) { true }

        val captured = Json { ignoreUnknownKeys = true }.decodeFromString<CapturedSynthBody>(fake.lastJsonBody!!)
        assertEquals(ElevenLabsTts.DEFAULT_MODEL, captured.model_id)
        assertEquals("hello there", captured.text)
    }

    @Test fun voice_catalog_parses_v1_voices_json() = runBlocking {
        val json = """{"voices":[{"voice_id":"abc","name":"Rachel"},{"voice_id":"def","name":"Adam"}]}"""
        val fake = FakeHttpTransport(script = { _, _ -> HttpResult.Ok(200, json) })

        val voices = ElevenLabsTts(fake, "xi-test", context = null).voices()

        assertEquals(2, voices.size)
        assertEquals("abc", voices[0].voiceId)
        assertEquals("Rachel", voices[0].displayName)
        assertEquals(ProviderId.ELEVENLABS, voices[0].providerId)
    }

    @Test fun voice_catalog_is_cached_per_session() = runBlocking {
        val json = """{"voices":[{"voice_id":"abc","name":"Rachel"}]}"""
        val fake = FakeHttpTransport(script = { _, _ -> HttpResult.Ok(200, json) })
        val el = ElevenLabsTts(fake, "xi-test", context = null)

        val first = el.voices()
        val second = el.voices()

        assertEquals(first, second)
        assertEquals(1, fake.callCount) // second voices() made NO second GET
    }
}
