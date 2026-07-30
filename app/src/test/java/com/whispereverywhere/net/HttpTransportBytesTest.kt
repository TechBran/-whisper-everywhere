package com.whispereverywhere.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * `postForBytes` exists because `postJson` reads the body via `response.body?.string()`, which is
 * lossy for the binary TTS audio (headerless PCM16 / mp3) OpenAI/ElevenLabs/Gemini-decoded return.
 * These tests pin that the Ok path carries RAW bytes unmodified — the guard against `.string()`
 * being silently reintroduced — while HttpError keeps reading the body as String, since error
 * bodies are text/JSON consumed by each provider's `classify`.
 */
class HttpTransportBytesTest {

    @Test fun ok_returns_raw_bytes_unmodified() = runBlocking {
        // 0xFF and 0x80 are not valid standalone UTF-8 bytes: a `.string()` round-trip would
        // replace them with the U+FFFD replacement character and silently corrupt the PCM this
        // method exists to protect. contentEquals (not the data class's own equals, which compares
        // ByteArray by reference) is the actual assertion here.
        val raw = byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte())
        val fake = FakeHttpTransport().apply { queueBytes(HttpResultBytes.Ok(200, raw)) }

        val result = fake.postForBytes("https://example.test/tts", emptyMap(), "{}")

        val ok = result as HttpResultBytes.Ok
        assertEquals(200, ok.code)
        assertTrue("bytes must round-trip unmodified, not been through .string()", raw.contentEquals(ok.bytes))
    }

    @Test fun http_error_carries_code_and_string_body() = runBlocking {
        val fake = FakeHttpTransport().apply {
            queueBytes(HttpResultBytes.HttpError(401, """{"error":{"message":"invalid_api_key"}}"""))
        }

        val result = fake.postForBytes("https://example.test/tts", emptyMap(), "{}")

        assertEquals(HttpResultBytes.HttpError(401, """{"error":{"message":"invalid_api_key"}}"""), result)
    }

    @Test fun network_error_wraps_cause() = runBlocking {
        val cause = IOException("no route to host")
        val fake = FakeHttpTransport().apply { queueBytes(HttpResultBytes.NetworkError(cause)) }

        val result = fake.postForBytes("https://example.test/tts", emptyMap(), "{}")

        assertEquals(cause, (result as HttpResultBytes.NetworkError).cause)
    }
}
