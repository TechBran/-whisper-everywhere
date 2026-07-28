package com.whispereverywhere.provider

import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.net.HttpTransport

/**
 * Outcome of checking a key. Deliberately more than valid/invalid: telling a user their key is
 * wrong when the real cause was an expired card, a rate limit, or a dead radio sends them off to
 * regenerate a perfectly good key.
 */
sealed interface KeyStatus {
    data object Valid : KeyStatus
    data object Invalid : KeyStatus
    data object NoCredit : KeyStatus
    data object RateLimited : KeyStatus
    data object Offline : KeyStatus
    data class Unknown(val detail: String) : KeyStatus
}

/**
 * Substrings a provider embeds in an error body when the key itself is the problem, even under a
 * status code that isn't the clean 401 — Gemini answers a bad key with 400 `API_KEY_INVALID`, not
 * 401. Shared with the UI (`CloudProvidersScreen`) so "Save anyway" can be suppressed for a key
 * that is recognizably wrong rather than merely unverifiable.
 */
val INVALID_KEY_MARKERS = listOf("API_KEY_INVALID", "invalid_api_key", "API key not valid")

/**
 * Verifies a key with one cheap authenticated GET per provider.
 *
 * NEVER log the key, the header map, or the raw request. [KeyStatus.Unknown.detail] carries only
 * the status code and a truncated body, which providers do not echo credentials into — but keep
 * it short and never widen it to include the request.
 */
class KeyValidator(private val transport: HttpTransport) {

    suspend fun validate(id: ProviderId, key: String): KeyStatus {
        val trimmed = key.trim()
        // Short-circuit before touching the network: a blank key cannot be valid, and firing a
        // request for it wastes a round trip and can count against a rate limit.
        if (trimmed.isEmpty()) return KeyStatus.Invalid

        // Reject anything outside printable ASCII (0x21-0x7e) BEFORE a header is ever built. A
        // char copied from a rendered web page/PDF (e.g. U+200B zero-width space) or typed with
        // smart punctuation survives `.trim()` — U+200B is Unicode category Cf, so
        // Char.isWhitespace() is false — and would otherwise reach OkHttp's Headers.checkValue,
        // which throws IllegalArgumentException embedding the raw header value for every header
        // except Authorization/Cookie/Proxy-Authorization/Set-Cookie. For xi-api-key and
        // x-goog-api-key that means the plaintext key in an uncaught crash trace. A bad paste
        // must be an ordinary Invalid outcome, never a crash.
        if (trimmed.any { it.code !in 0x21..0x7e }) return KeyStatus.Invalid

        val provider = ProviderCatalog.byId(id)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(trimmed))

        return when (val result = transport.get(provider.validationUrl, headers)) {
            is HttpResult.Ok -> KeyStatus.Valid
            is HttpResult.NetworkError -> KeyStatus.Offline
            is HttpResult.HttpError -> classify(result.code, result.body)
        }
    }

    private fun classify(code: Int, body: String): KeyStatus = when {
        code == 401 -> KeyStatus.Invalid
        // Gemini answers a wrong/revoked key with HTTP 400 and API_KEY_INVALID in the body, not
        // 401. Without this, garbage falls to the `else` branch below -> Unknown -> the UI's
        // "Save anyway" affordance -> a persisted, unusable key.
        code == 400 && INVALID_KEY_MARKERS.any { body.contains(it, ignoreCase = true) } ->
            KeyStatus.Invalid
        // 403 is ALSO the standard code for a WORKING key that is region-blocked (OpenAI
        // unsupported_country_region_territory), scope-restricted, or on a project without the
        // API enabled. Asserting "check you copied all of it" here sends a correct key back for
        // regeneration. Unknown carries the provider's own message instead.
        code == 403 -> KeyStatus.Unknown("HTTP 403: ${body.take(200)}")
        code == 402 -> KeyStatus.NoCredit
        // OpenAI returns 429 for BOTH transient rate limiting and permanently exhausted credit,
        // distinguishable only from the body. Collapsing them makes the client back off
        // exponentially against an empty wallet, forever.
        code == 429 -> if (QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }) {
            KeyStatus.NoCredit
        } else {
            KeyStatus.RateLimited
        }
        else -> KeyStatus.Unknown("HTTP $code: ${body.take(200)}")
    }

    private companion object {
        val QUOTA_MARKERS = listOf("insufficient_quota", "quota_exceeded", "RESOURCE_EXHAUSTED")
    }
}
