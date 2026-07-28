package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OpenAiSttTest {

    private fun provider(result: HttpResult, fake: FakeHttpTransport = FakeHttpTransport { _, _ -> result }) =
        fake to OpenAiStt(fake, "sk-test")

    private val pcm = ByteArray(3200) { (it % 127).toByte() }   // 100 ms of 16 kHz PCM16

    @Test fun a_200_yields_the_transcript_text() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, """{"text":"hello world"}"""))
        assertEquals(SttResult.Text("hello world"), p.transcribe(pcm, null))
    }

    @Test fun the_upload_is_named_audio_wav_because_openai_infers_format_from_the_filename() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        OpenAiStt(fake, "sk-test").transcribe(pcm, null)
        assertEquals("audio.wav", fake.lastFilePart?.fileName)
        assertEquals("file", fake.lastFilePart?.fieldName)
    }

    @Test fun the_uploaded_bytes_are_a_wav_container_not_raw_pcm() = runBlocking {
        // OpenAI REJECTS raw PCM outright.
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        OpenAiStt(fake, "sk-test").transcribe(pcm, null)
        val sent = fake.lastFilePart!!.bytes
        assertEquals("RIFF", String(sent, 0, 4, Charsets.US_ASCII))
        assertEquals(pcm.size + 44, sent.size)
    }

    @Test fun a_bearer_header_is_sent() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        OpenAiStt(fake, "sk-abc").transcribe(pcm, null)
        assertEquals("Bearer sk-abc", fake.lastHeaders["Authorization"])
    }

    @Test fun the_verified_openai_model_alias_is_sent() = runBlocking {
        // Commit 55cbb29 ("spec: OpenAI STT model -> gpt-transcribe, verified against live docs")
        // deliberately REVERSED this plan's original "pin a dated snapshot" instruction: a dated
        // snapshot pins behaviour but IS eventually retired under a shipped APK a user may not
        // update for months (gpt-4o-mini-transcribe-2025-03-20 shut down 23 Jul 2026), whereas the
        // gpt-transcribe alias stays reachable and gpt-transcribe publishes no dated snapshot at
        // all. This asserts the model actually sent matches that settled, live-doc-verified id
        // rather than the superseded dated-snapshot shape.
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        OpenAiStt(fake, "sk-test").transcribe(pcm, null)
        assertEquals(OpenAiStt.DEFAULT_MODEL, fake.lastFields["model"])
    }

    @Test fun a_language_hint_is_forwarded_and_omitted_when_null() = runBlocking {
        val f1 = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        OpenAiStt(f1, "sk-test").transcribe(pcm, "en")
        assertEquals("en", f1.lastFields["language"])

        val f2 = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        OpenAiStt(f2, "sk-test").transcribe(pcm, null)
        assertTrue("auto-detect must omit the field", !f2.lastFields.containsKey("language"))
    }

    @Test fun a_401_is_fatal_invalid_key() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(401, ""))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_429_with_a_quota_body_is_fatal_out_of_credit_not_transient() = runBlocking {
        // Backing off exponentially against an empty wallet retries forever.
        val body = """{"error":{"code":"insufficient_quota"}}"""
        val (_, p) = provider(HttpResult.HttpError(429, body))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as SttError.Fatal).kind)
    }

    @Test fun a_plain_429_is_transient() = runBlocking {
        val (_, p) = provider(HttpResult.HttpError(429, "slow down"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_413_is_a_bad_segment_not_a_fatal_account_problem() = runBlocking {
        // Too-large audio is this segment's problem; it must not disable the provider.
        val (_, p) = provider(HttpResult.HttpError(413, ""))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
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
        // 25 MB cap. Spending an upload to learn it is too big wastes the user's data and money.
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"x"}""") }
        val huge = ByteArray(26 * 1024 * 1024)
        val r = OpenAiStt(fake, "sk-test").transcribe(huge, null) as SttResult.Failed
        assertEquals(SttError.BadSegment, r.error)
        assertEquals("must not hit the network", 0, fake.callCount)
    }

    @Test fun a_200_with_an_unparseable_body_is_transient_not_silently_empty() = runBlocking {
        // Returning "" here would look like silence and suppress fallback.
        val (_, p) = provider(HttpResult.Ok(200, "not json"))
        val r = p.transcribe(pcm, null) as SttResult.Failed
        assertTrue(r.error is SttError.Transient)
    }

    @Test fun a_200_with_an_empty_text_field_is_a_legitimate_empty_transcript() = runBlocking {
        val (_, p) = provider(HttpResult.Ok(200, """{"text":""}"""))
        assertEquals(SttResult.Text(""), p.transcribe(pcm, null))
    }
}
