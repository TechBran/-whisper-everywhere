package com.whispereverywhere.provider

import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class KeyValidatorTest {

    private fun validator(result: HttpResult, capture: ((String, Map<String, String>) -> Unit)? = null) =
        KeyValidator(FakeHttpTransport { url, headers -> capture?.invoke(url, headers); result })

    @Test fun a_200_means_the_key_is_valid() = runBlocking {
        assertEquals(KeyStatus.Valid, validator(HttpResult.Ok(200, "{}")).validate(ProviderId.OPENAI, "sk-x"))
    }

    @Test fun a_401_means_the_key_is_invalid() = runBlocking {
        assertEquals(KeyStatus.Invalid, validator(HttpResult.HttpError(401, "")).validate(ProviderId.OPENAI, "sk-x"))
    }

    @Test fun a_403_is_unknown_not_invalid_because_it_can_mean_a_working_key() = runBlocking {
        // 403 is ALSO the standard code for a WORKING key that's region-blocked (OpenAI
        // unsupported_country_region_territory), scope-restricted, or on a project without the
        // API enabled. Asserting "check you copied all of it" here sends a correct key back for
        // regeneration.
        val body = """{"error":{"code":403,"message":"unsupported_country_region_territory"}}"""
        val status = validator(HttpResult.HttpError(403, body)).validate(ProviderId.OPENAI, "sk-x")
        assertTrue(status is KeyStatus.Unknown)
        assertTrue((status as KeyStatus.Unknown).detail.contains("403"))
    }

    @Test fun gemini_400_api_key_invalid_is_classified_invalid_not_unknown() = runBlocking {
        // Gemini answers a wrong/revoked key with HTTP 400 and API_KEY_INVALID in the body, not
        // 401. Without this, garbage falls to `else` -> Unknown -> the UI's "Save anyway"
        // affordance -> a persisted, unusable key. Body shape pinned to what
        // generativelanguage.googleapis.com actually returns.
        val body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid """ +
            """API key.","status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com""" +
            """/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com"}]}}"""
        assertEquals(
            KeyStatus.Invalid,
            validator(HttpResult.HttpError(400, body)).validate(ProviderId.GEMINI, "g-bad"),
        )
    }

    @Test fun a_400_without_an_invalid_key_marker_is_unknown_not_invalid() = runBlocking {
        // A 400 that ISN'T Gemini's specific invalid-key shape must not be misclassified either
        // way — it falls through to Unknown like any other unrecognized status.
        val status = validator(HttpResult.HttpError(400, "bad request")).validate(ProviderId.GEMINI, "g-x")
        assertTrue(status is KeyStatus.Unknown)
    }

    @Test fun a_402_means_out_of_credit_not_invalid() = runBlocking {
        // Telling a user their key is wrong when their card declined sends them to regenerate a
        // perfectly good key.
        assertEquals(KeyStatus.NoCredit, validator(HttpResult.HttpError(402, "")).validate(ProviderId.ELEVENLABS, "k"))
    }

    @Test fun a_429_quota_body_means_out_of_credit() = runBlocking {
        // OpenAI returns 429 for BOTH transient rate limiting and permanently exhausted credit;
        // only the body distinguishes them. Backing off against an empty wallet retries forever.
        val body = """{"error":{"code":"insufficient_quota","message":"exceeded quota"}}"""
        assertEquals(KeyStatus.NoCredit, validator(HttpResult.HttpError(429, body)).validate(ProviderId.OPENAI, "sk-x"))
    }

    @Test fun a_429_without_a_quota_body_is_rate_limiting() = runBlocking {
        assertEquals(KeyStatus.RateLimited, validator(HttpResult.HttpError(429, "slow down")).validate(ProviderId.OPENAI, "sk-x"))
    }

    @Test fun a_network_error_is_offline_not_invalid() = runBlocking {
        // Marking a good key invalid because the user was on a train is the worst outcome here.
        assertEquals(
            KeyStatus.Offline,
            validator(HttpResult.NetworkError(IOException("no route"))).validate(ProviderId.OPENAI, "sk-x"),
        )
    }

    @Test fun an_unexpected_status_is_unknown_and_carries_detail() = runBlocking {
        val status = validator(HttpResult.HttpError(500, "boom")).validate(ProviderId.OPENAI, "sk-x")
        assertTrue(status is KeyStatus.Unknown)
    }

    @Test fun a_blank_key_is_invalid_without_any_network_call() = runBlocking {
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, "{}") }
        assertEquals(KeyStatus.Invalid, KeyValidator(fake).validate(ProviderId.OPENAI, "   "))
        assertEquals("must not hit the network for a blank key", 0, fake.callCount)
    }

    @Test fun a_key_with_a_zero_width_space_is_invalid_without_any_network_call() = runBlocking {
        // U+200B is Unicode category Cf ("format"), so Char.isWhitespace() is false and it
        // survives .trim() intact. Left unrejected, this reaches OkHttp's Headers.checkValue,
        // which throws IllegalArgumentException embedding the raw header value for every header
        // except Authorization/Cookie/Proxy-Authorization/Set-Cookie -- for xi-api-key /
        // x-goog-api-key that is the plaintext key in an uncaught crash trace. Must be rejected
        // here, before any request is even attempted.
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, "{}") }
        val keyWithZeroWidthSpace = "sk-abc​def"
        assertEquals(KeyStatus.Invalid, KeyValidator(fake).validate(ProviderId.OPENAI, keyWithZeroWidthSpace))
        assertEquals("must not hit the network for an unheaderable key", 0, fake.callCount)
    }

    @Test fun a_key_with_a_curly_smart_quote_is_invalid_without_any_network_call() = runBlocking {
        // A realistic trigger: pasting a key typed or auto-corrected with smart punctuation.
        // U+2019 (RIGHT SINGLE QUOTATION MARK) is also outside 0x21-0x7e.
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, "{}") }
        assertEquals(KeyStatus.Invalid, KeyValidator(fake).validate(ProviderId.ELEVENLABS, "el’key"))
        assertEquals(0, fake.callCount)
    }

    @Test fun openai_gets_a_bearer_header_at_the_right_url() = runBlocking {
        var url: String? = null
        var headers: Map<String, String> = emptyMap()
        validator(HttpResult.Ok(200, "{}")) { u, h -> url = u; headers = h }
            .validate(ProviderId.OPENAI, "sk-abc")
        assertEquals("https://api.openai.com/v1/models", url)
        assertEquals("Bearer sk-abc", headers["Authorization"])
    }

    @Test fun elevenlabs_gets_a_bare_xi_api_key_header() = runBlocking {
        var headers: Map<String, String> = emptyMap()
        validator(HttpResult.Ok(200, "{}")) { _, h -> headers = h }
            .validate(ProviderId.ELEVENLABS, "el-abc")
        assertEquals("el-abc", headers["xi-api-key"])
        assertTrue("must not send an Authorization header", !headers.containsKey("Authorization"))
    }

    @Test fun gemini_gets_a_bare_goog_header() = runBlocking {
        var headers: Map<String, String> = emptyMap()
        validator(HttpResult.Ok(200, "{}")) { _, h -> headers = h }
            .validate(ProviderId.GEMINI, "g-abc")
        assertEquals("g-abc", headers["x-goog-api-key"])
    }
}
