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

        val provider = ProviderCatalog.byId(id)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(trimmed))

        return when (val result = transport.get(provider.validationUrl, headers)) {
            is HttpResult.Ok -> KeyStatus.Valid
            is HttpResult.NetworkError -> KeyStatus.Offline
            is HttpResult.HttpError -> classify(result.code, result.body)
        }
    }

    private fun classify(code: Int, body: String): KeyStatus = when (code) {
        401, 403 -> KeyStatus.Invalid
        402 -> KeyStatus.NoCredit
        // OpenAI returns 429 for BOTH transient rate limiting and permanently exhausted credit,
        // distinguishable only from the body. Collapsing them makes the client back off
        // exponentially against an empty wallet, forever.
        429 -> if (QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }) {
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
