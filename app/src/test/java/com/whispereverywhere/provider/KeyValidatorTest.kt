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

    @Test fun a_403_also_means_invalid() = runBlocking {
        assertEquals(KeyStatus.Invalid, validator(HttpResult.HttpError(403, "")).validate(ProviderId.GEMINI, "k"))
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
