package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GeminiSttTest {

    private val ok = """{"candidates":[{"content":{"parts":[{"text":"hello world"}]}}]}"""

    private fun provider(result: HttpResult, fake: FakeHttpTransport = FakeHttpTransport { _, _ -> result }) =
        fake to GeminiStt(fake, "g-test")

    private val pcm = ByteArray(3200) { (it % 127).toByte() }

    @Test fun a_200_yields_the_transcript_text() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, ok))
        assertEquals(SttResult.Text("hello world"), p.transcribe(pcm, null))
    }

    @Test fun the_key_rides_the_header_and_is_never_in_the_url() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, ok) }
        GeminiStt(fake, "g-secret").transcribe(pcm, null)
        assertEquals("g-secret", fake.lastHeaders["x-goog-api-key"])
        assertFalse("key must never appear in the URL", fake.lastUrl!!.contains("g-secret"))
        assertFalse("no ?key= query param", fake.lastUrl!!.contains("key="))
    }

    @Test fun the_body_carries_base64_wav_inline_data_and_the_verbatim_instruction() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, ok) }
        GeminiStt(fake, "g-test").transcribe(pcm, null)
        val body = fake.lastJsonBody!!
        assertTrue(body.contains("inline_data"))
        assertTrue(body.contains("audio/wav"))
        assertTrue(body.contains("Transcribe this audio verbatim"))
        // base64 of the WAV-wrapped bytes must be present (RIFF -> "UklGR" prefix).
        assertTrue("expected base64 WAV in body", body.contains("UklGR"))
    }

    @Test fun the_pinned_model_is_in_the_endpoint_path() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, ok) }
        GeminiStt(fake, "g-test").transcribe(pcm, null)
        assertTrue(fake.lastUrl!!.contains("gemini-3.6-flash:generateContent"))
    }

    @Test fun a_400_with_an_api_key_invalid_marker_is_fatal_invalid_key() = runBlocking {
        // The C1 trap: Gemini rejects a bad key with 400, not 401.
        val body = """{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}"""
        val (_, p) = provider(HttpResult.HttpError(400, body))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_plain_400_without_the_marker_is_a_bad_segment_not_a_key_fault() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(400, """{"error":{"message":"malformed request"}}"""))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
    }

    @Test fun a_429_resource_exhausted_is_fatal_out_of_credit() = runBlocking {
        val body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}"""
        val (_, p) = provider(HttpResult.HttpError(429, body))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_plain_429_is_transient() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(429, "slow down"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_404_model_not_found_is_fatal_model_unavailable_not_transient() = runBlocking {
        // The model id is in the URL path, so a 404 is a permanently-wrong pin. It MUST latch as
        // Fatal after one request, not fall to `else -> Transient` and retry every segment forever.
        val body = """{"error":{"code":404,"status":"NOT_FOUND","message":"models/gemini-3.6-flash is not found"}}"""
        val (_, p) = provider(HttpResult.HttpError(404, body))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.MODEL_UNAVAILABLE, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_403_is_fatal_forbidden() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(403, "PERMISSION_DENIED"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.FORBIDDEN, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_5xx_is_transient() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(503, "upstream"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_network_error_is_offline() = runBlocking {
        val (_, p) = provider(HttpResult.NetworkError(IOException("no route")))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(SttError.Offline, r.error)
    }

    @Test fun oversized_audio_fails_locally_without_a_request() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, ok) }
        val huge = ByteArray(15 * 1024 * 1024)
        val r = GeminiStt(fake, "g-test").transcribe(huge, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
        assertEquals("must not hit the network", 0, fake.callCount)
    }

    @Test fun a_200_with_an_unparseable_body_is_transient_not_silently_empty() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, "not json"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_200_refusal_with_no_text_part_is_transient_not_empty() = runBlocking {
        // A safety refusal returns a candidate with no parts. That is NOT silence.
        val (_, p) = provider(HttpResult.Ok(200, """{"candidates":[{"finishReason":"SAFETY"}]}"""))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_200_with_a_blank_text_part_is_a_legitimate_empty_transcript() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, """{"candidates":[{"content":{"parts":[{"text":""}]}}]}"""))
        assertEquals(SttResult.Text(""), p.transcribe(pcm, null))
    }
}
