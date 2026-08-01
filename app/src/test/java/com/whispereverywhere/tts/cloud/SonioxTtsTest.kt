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

/**
 * Decodes only the fields this test pins — kotlinx.serialization (org.json is BANNED), not a
 * substring match, so a pin cannot pass by accident on a value that merely appears elsewhere.
 */
@Serializable
private data class CapturedSonioxBody(
    val model: String = "",
    val language: String = "",
    val voice: String = "",
    val audio_format: String = "",
    val text: String = "",
    val sample_rate: Int = 0,
    val speed: Float = 0f,
)

class SonioxTtsTest {

    private fun decode(json: String) =
        Json { ignoreUnknownKeys = true }.decodeFromString<CapturedSonioxBody>(json)

    private fun bytesFake(vararg results: HttpResultBytes) =
        FakeHttpTransport().apply { results.forEach { queueBytes(it) } }

    private fun okBytes(bytes: ByteArray) = bytesFake(HttpResultBytes.Ok(200, bytes))
    private fun errBytes(code: Int, body: String) = bytesFake(HttpResultBytes.HttpError(code, body))

    // --- the bank's hard contract ---

    @Test fun sample_rate_is_the_24k_bank_contract() {
        assertEquals(24_000, SonioxTts(bytesFake(), "sk-test").sampleRate)
    }

    // --- happy path: headerless LE PCM16 drops straight into the bank ---

    @Test fun pcm_body_is_delivered_as_le_short_chunks() = runBlocking {
        val bytes = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x7F, 0x00, 0x80.toByte())
        val collected = mutableListOf<Short>()

        val result = SonioxTts(okBytes(bytes), "sk-test").synth("hello", "Maya", 1.0f) { pcm ->
            collected.addAll(pcm.toList()); true
        }

        assertEquals(TtsResult.Done, result)
        assertEquals(listOf<Short>(0, 32767, -32768), collected)
    }

    @Test fun onPcm_returning_false_stops_and_returns_Cancelled() = runBlocking {
        val result = SonioxTts(okBytes(byteArrayOf(0x00, 0x00)), "sk-test").synth("hi", "Maya", 1.0f) { false }
        assertEquals(TtsResult.Cancelled, result)
    }

    @Test fun onPcm_returning_true_returns_Done() = runBlocking {
        val result = SonioxTts(okBytes(byteArrayOf(0x00, 0x00)), "sk-test").synth("hi", "Maya", 1.0f) { true }
        assertEquals(TtsResult.Done, result)
    }

    // --- fail locally rather than spend a request ---

    @Test fun a_blank_unit_fails_locally_without_a_request() = runBlocking {
        val fake = okBytes(byteArrayOf(0x00, 0x00))
        val r = SonioxTts(fake, "sk-test").synth("   ", "Maya", 1.0f) { true } as TtsResult.Failed
        assertEquals(TtsError.BadUnit, r.error)
        assertEquals("must not hit the network", 0, fake.callCount)
    }

    @Test fun an_oversized_unit_fails_locally_without_a_request() = runBlocking {
        val fake = okBytes(byteArrayOf(0x00, 0x00))
        val r = SonioxTts(fake, "sk-test").synth("x".repeat(5001), "Maya", 1.0f) { true } as TtsResult.Failed
        assertEquals(TtsError.BadUnit, r.error)
        assertEquals("must not spend a request the server will 400", 0, fake.callCount)
    }

    // --- request shape: exactly what the pinned fact-sheet endpoint expects ---

    @Test fun request_shape_pins_model_format_rate_voice_text_and_auto_language() = runBlocking {
        val fake = okBytes(ByteArray(0))
        SonioxTts(fake, "sk-secret").synth("hello there", "Adrian", 1.0f) { true }

        val body = decode(fake.lastJsonBody!!)
        assertEquals(SonioxTts.DEFAULT_MODEL, body.model)   // tts-rt-v1
        assertEquals("pcm_s16le", body.audio_format)
        assertEquals(24_000, body.sample_rate)
        assertEquals("Adrian", body.voice)
        assertEquals("hello there", body.text)
        assertEquals("auto", body.language)                 // multilingual default — NOT forced
        assertEquals("https://tts-rt.soniox.com/tts", fake.lastUrl)
        assertEquals("Bearer sk-secret", fake.lastHeaders["Authorization"])
    }

    @Test fun the_key_rides_the_bearer_header_and_is_never_in_the_url() = runBlocking {
        // No-leak at the request level: the credential lives in the Authorization header, never the
        // URL. (classify() logs status code + unitLen only — never key/header/body/text — enforced
        // by inspection under the pure-JVM harness, which cannot intercept android.util.Log.)
        val fake = okBytes(ByteArray(0))
        SonioxTts(fake, "sk-secret").synth("hello", "Maya", 1.0f) { true }
        assertEquals("Bearer sk-secret", fake.lastHeaders["Authorization"])
        assertFalse("key must never appear in the URL", fake.lastUrl!!.contains("sk-secret"))
    }

    @Test fun speed_is_coerced_into_the_soniox_range() = runBlocking {
        val tooFast = okBytes(ByteArray(0))
        SonioxTts(tooFast, "sk-test").synth("hi", "Maya", 9.0f) { true }
        assertEquals(1.3f, decode(tooFast.lastJsonBody!!).speed, 0.0001f)

        val tooSlow = okBytes(ByteArray(0))
        SonioxTts(tooSlow, "sk-test").synth("hi", "Maya", 0.1f) { true }
        assertEquals(0.7f, decode(tooSlow.lastJsonBody!!).speed, 0.0001f)

        val inRange = okBytes(ByteArray(0))
        SonioxTts(inRange, "sk-test").synth("hi", "Maya", 1.1f) { true }
        assertEquals(1.1f, decode(inRange.lastJsonBody!!).speed, 0.0001f)
    }

    // --- multilingual: "auto" is undocumented for generate → ONE retry with a concrete code ---

    @Test fun a_400_naming_the_language_field_retries_once_with_the_default_code() = runBlocking {
        val fake = bytesFake(
            HttpResultBytes.HttpError(400, """{"error_code":"invalid_request","message":"language 'auto' is not supported"}"""),
            HttpResultBytes.Ok(200, byteArrayOf(0x01, 0x00)),
        )
        val collected = mutableListOf<Short>()
        val result = SonioxTts(fake, "sk-test").synth("hola", "Maya", 1.0f) { pcm ->
            collected.addAll(pcm.toList()); true
        }

        assertEquals(TtsResult.Done, result)
        assertEquals("exactly two requests: auto then the fallback code", 2, fake.callCount)
        assertEquals("en", decode(fake.lastJsonBody!!).language)
        assertEquals(listOf<Short>(1), collected)
    }

    @Test fun a_400_not_naming_language_is_BadUnit_with_no_wasted_retry() = runBlocking {
        val fake = errBytes(400, """{"error_code":"invalid_request","message":"malformed body"}""")
        val r = SonioxTts(fake, "sk-test").synth("hi", "Maya", 1.0f) { true } as TtsResult.Failed
        assertEquals(TtsError.BadUnit, r.error)
        assertEquals("a non-language 400 must not spend a second request", 1, fake.callCount)
    }

    // --- error taxonomy via classify ---

    @Test fun http_401_is_Fatal_INVALID_KEY() = runBlocking {
        val r = SonioxTts(errBytes(401, ""), "sk-test").synth("hi", "Maya", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.INVALID_KEY, (r.error as TtsError.Fatal).kind)
    }

    @Test fun http_403_is_Fatal_FORBIDDEN() = runBlocking {
        val r = SonioxTts(errBytes(403, ""), "sk-test").synth("hi", "Maya", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.FORBIDDEN, (r.error as TtsError.Fatal).kind)
    }

    @Test fun http_402_payment_is_Fatal_OUT_OF_CREDIT_not_404() = runBlocking {
        // Soniox STT taxonomy: 402 payment → OUT_OF_CREDIT (the primary credit signal on this host).
        val r = SonioxTts(errBytes(402, ""), "sk-test").synth("hi", "Maya", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as TtsError.Fatal).kind)
    }

    @Test fun a_bare_429_is_Transient() = runBlocking {
        val r = SonioxTts(errBytes(429, "slow down"), "sk-test").synth("hi", "Maya", 1.0f) { true } as TtsResult.Failed
        assertTrue(r.error is TtsError.Transient)
    }

    @Test fun a_429_with_a_quota_marker_is_Fatal_OUT_OF_CREDIT() = runBlocking {
        val r = SonioxTts(errBytes(429, """{"error_code":"insufficient_credit"}"""), "sk-test")
            .synth("hi", "Maya", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.OUT_OF_CREDIT, (r.error as TtsError.Fatal).kind)
    }

    @Test fun a_408_is_Transient() = runBlocking {
        val r = SonioxTts(errBytes(408, ""), "sk-test").synth("hi", "Maya", 1.0f) { true } as TtsResult.Failed
        assertTrue(r.error is TtsError.Transient)
    }

    @Test fun a_5xx_is_Transient() = runBlocking {
        val r = SonioxTts(errBytes(503, "upstream"), "sk-test").synth("hi", "Maya", 1.0f) { true } as TtsResult.Failed
        assertTrue(r.error is TtsError.Transient)
    }

    @Test fun a_404_is_defensively_Fatal_MODEL_UNAVAILABLE() = runBlocking {
        val r = SonioxTts(errBytes(404, ""), "sk-test").synth("hi", "Maya", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.MODEL_UNAVAILABLE, (r.error as TtsError.Fatal).kind)
    }

    @Test fun a_400_naming_an_unknown_model_is_Fatal_MODEL_UNAVAILABLE() = runBlocking {
        // Soniox folds unknown-voice AND unknown-model into the one 400 bucket; a model marker →
        // MODEL_UNAVAILABLE (parity with the siblings' model-retirement latch), one request only.
        val fake = errBytes(400, """{"error_code":"invalid_request","message":"unknown model tts-rt-v99"}""")
        val r = SonioxTts(fake, "sk-test").synth("hi", "Maya", 1.0f) { true } as TtsResult.Failed
        assertEquals(FatalKind.MODEL_UNAVAILABLE, (r.error as TtsError.Fatal).kind)
        assertEquals(1, fake.callCount)
    }

    // --- undecodable / offline ---

    @Test fun an_odd_length_body_drops_the_trailing_byte_and_still_Done() = runBlocking {
        // Half a sample is not a sample: toShortArrayLE drops the trailing byte, delivery still Done.
        val collected = mutableListOf<Short>()
        val result = SonioxTts(okBytes(byteArrayOf(0x01, 0x00, 0x7F)), "sk-test").synth("hi", "Maya", 1.0f) { pcm ->
            collected.addAll(pcm.toList()); true
        }
        assertEquals(TtsResult.Done, result)
        assertEquals(listOf<Short>(1), collected)
    }

    // --- no-audio 200: never a silent skip / soft-latch reset ---

    @Test fun an_empty_200_body_is_Transient_not_a_silent_Done() = runBlocking {
        // A no-audio 200 must fail Transient (re-synth on local + counts toward the soft-latch),
        // NEVER onPcm(empty)→Done — a Done would silently drop the clause AND reset the engine's
        // money circuit breaker so cloud re-bills every remaining clause.
        var onPcmCalls = 0
        val r = SonioxTts(okBytes(ByteArray(0)), "sk-test").synth("hi", "Maya", 1.0f) {
            onPcmCalls++; true
        } as TtsResult.Failed
        assertTrue("empty 200 must be Transient", r.error is TtsError.Transient)
        assertEquals("words must never silently vanish: onPcm not called with empty audio", 0, onPcmCalls)
    }

    @Test fun a_sub_sample_200_body_that_decodes_to_nothing_is_Transient() = runBlocking {
        // One byte is half a sample: toShortArrayLE drops it → empty → a no-audio 200, not a Done.
        val r = SonioxTts(okBytes(byteArrayOf(0x7F)), "sk-test").synth("hi", "Maya", 1.0f) { true } as TtsResult.Failed
        assertTrue(r.error is TtsError.Transient)
    }

    @Test fun an_empty_200_on_the_language_retry_is_also_Transient() = runBlocking {
        // The retry path shares deliver(), so a no-audio 200 after the "auto"→code fallback fails
        // Transient too — the second Ok branch cannot silently emit an empty clause either.
        val fake = bytesFake(
            HttpResultBytes.HttpError(400, """{"error_code":"invalid_request","message":"language 'auto' is not supported"}"""),
            HttpResultBytes.Ok(200, ByteArray(0)),
        )
        val r = SonioxTts(fake, "sk-test").synth("hola", "Maya", 1.0f) { true } as TtsResult.Failed
        assertTrue(r.error is TtsError.Transient)
        assertEquals("exactly two requests: auto then the fallback code", 2, fake.callCount)
    }

    @Test fun a_network_error_is_Offline() = runBlocking {
        val fake = bytesFake(HttpResultBytes.NetworkError(IOException("no route")))
        val r = SonioxTts(fake, "sk-test").synth("hi", "Maya", 1.0f) { true } as TtsResult.Failed
        assertEquals(TtsError.Offline, r.error)
    }

    // --- static voice catalog: Maya (recommended default) + Adrian ---

    @Test fun voice_catalog_is_exactly_maya_and_adrian() {
        assertEquals(2, SonioxTtsVoices.ALL.size)
        assertEquals(listOf("Maya", "Adrian"), SonioxTtsVoices.ALL.map { it.voiceId })
        // displayName is the literal voiceId the request expects (Gemini-shaped static catalog).
        assertTrue(SonioxTtsVoices.ALL.all { it.displayName == it.voiceId })
    }

    @Test fun only_maya_is_flagged_recommended() {
        val byId = SonioxTtsVoices.ALL.associateBy { it.voiceId }
        assertTrue(byId.getValue("Maya").recommended)
        assertFalse(byId.getValue("Adrian").recommended)
    }
}
