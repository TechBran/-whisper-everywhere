package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ElevenLabsSttTest {

    private fun provider(result: HttpResult, fake: FakeHttpTransport = FakeHttpTransport { _, _ -> result }) =
        fake to ElevenLabsStt(fake, "xi-test")

    private val pcm = ByteArray(3200) { (it % 127).toByte() }

    @Test fun a_200_yields_the_transcript_text() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, """{"text":"hello world"}"""))
        assertEquals(SttResult.Text("hello world"), p.transcribe(pcm, null))
    }

    @Test fun the_upload_is_a_wav_container_in_the_file_part() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        ElevenLabsStt(fake, "xi-test").transcribe(pcm, null)
        assertEquals("file", fake.lastFilePart?.fieldName)
        val sent = fake.lastFilePart!!.bytes
        assertEquals("RIFF", String(sent, 0, 4, Charsets.US_ASCII))
        assertEquals(pcm.size + 44, sent.size)
    }

    @Test fun the_xi_api_key_header_is_sent_bare_no_bearer() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        ElevenLabsStt(fake, "xi-abc").transcribe(pcm, null)
        assertEquals("xi-abc", fake.lastHeaders["xi-api-key"])
    }

    @Test fun the_scribe_v2_model_id_is_sent() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        ElevenLabsStt(fake, "xi-test").transcribe(pcm, null)
        assertEquals("scribe_v2", fake.lastFields["model_id"])
    }

    @Test fun a_language_hint_is_forwarded_as_language_code_and_omitted_when_null() = runBlocking {
        val f1 = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        ElevenLabsStt(f1, "xi-test").transcribe(pcm, "en")
        assertEquals("en", f1.lastFields["language_code"])
        val f2 = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        ElevenLabsStt(f2, "xi-test").transcribe(pcm, null)
        assertTrue(!f2.lastFields.containsKey("language_code"))
    }

    @Test fun a_401_is_fatal_invalid_key() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(401, ""))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_422_validation_error_is_a_bad_segment() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(422, "unprocessable"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
    }

    @Test fun a_429_with_a_quota_body_is_fatal_out_of_credit() = runBlocking {
        val body = """{"detail":{"status":"quota_exceeded"}}"""
        val (_, p) = provider(HttpResult.HttpError(429, body))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_plain_429_is_transient() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(429, "slow down"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_403_is_fatal_forbidden() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(403, ""))
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

    @Test fun a_200_with_an_unparseable_body_is_transient_not_silently_empty() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, "not json"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_200_with_an_empty_text_field_is_a_legitimate_empty_transcript() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, """{"text":""}"""))
        assertEquals(SttResult.Text(""), p.transcribe(pcm, null))
    }
}
