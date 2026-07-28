package com.whispereverywhere.provider

/**
 * Stable identity for a cloud provider.
 *
 * Persistence keys off [name], NEVER the ordinal: reordering this enum would otherwise silently
 * repoint every user's stored credential at a different provider.
 */
enum class ProviderId { OPENAI, GEMINI, ELEVENLABS }

/**
 * Everything the app needs to know about a provider that is not a secret.
 *
 * @param authHeaderValue builds the header value from a raw key. Modelled as a function because
 *   the three providers genuinely differ: OpenAI prefixes "Bearer ", the other two send the key
 *   bare. Sending "Bearer <key>" to ElevenLabs 401s.
 * @param trainsOnDataByDefault drives per-provider disclosure copy. One generic sentence would be
 *   materially inaccurate for OpenAI.
 */
data class Provider(
    val id: ProviderId,
    val displayName: String,
    val authHeaderName: String,
    val authHeaderValue: (String) -> String,
    val validationUrl: String,
    val supportsStt: Boolean,
    val supportsTts: Boolean,
    val supportsStreaming: Boolean,
    val keyHelpUrl: String,
    val trainsOnDataByDefault: Boolean,
)

/** Pure, Android-free. JVM-unit-testable like WhisperCatalog. */
object ProviderCatalog {

    val all: List<Provider> = listOf(
        Provider(
            id = ProviderId.OPENAI,
            displayName = "OpenAI",
            authHeaderName = "Authorization",
            authHeaderValue = { "Bearer $it" },
            validationUrl = "https://api.openai.com/v1/models",
            supportsStt = true,
            supportsTts = true,
            supportsStreaming = true,
            keyHelpUrl = "https://platform.openai.com/api-keys",
            trainsOnDataByDefault = false,
        ),
        Provider(
            id = ProviderId.GEMINI,
            displayName = "Google Gemini",
            authHeaderName = "x-goog-api-key",
            authHeaderValue = { it },
            validationUrl = "https://generativelanguage.googleapis.com/v1beta/models",
            supportsStt = true,
            supportsTts = true,
            // NOT a preference. The Live API is preview, session-capped at 15 minutes, and
            // recommends ephemeral tokens minted by a backend this app does not have — so no
            // usable streaming path exists for a client holding only the user's own key.
            supportsStreaming = false,
            keyHelpUrl = "https://aistudio.google.com/apikey",
            // Unpaid tier: Google uses submitted content to improve its products and human
            // reviewers may read API input and output. Paid tier excludes this.
            trainsOnDataByDefault = true,
        ),
        Provider(
            id = ProviderId.ELEVENLABS,
            displayName = "ElevenLabs",
            // NOT a Bearer scheme — this is the most commonly got-wrong header of the three.
            authHeaderName = "xi-api-key",
            authHeaderValue = { it },
            // /v1/voices, NOT /v1/user. ElevenLabs is the only one of the three that supports
            // per-endpoint API-key restrictions, and /v1/user needs `user_read` — which a key
            // scoped for speech work will not have, so a perfectly good key 401s. Probed
            // 2026-07-28: /v1/voices returns 200 unauthenticated but 401 for a BAD key, so it
            // still validates, at a lower privilege. It is also the endpoint the voice picker
            // will need anyway once cloud TTS lands.
            validationUrl = "https://api.elevenlabs.io/v1/voices",
            supportsStt = true,
            supportsTts = true,
            supportsStreaming = true,
            keyHelpUrl = "https://elevenlabs.io/app/settings/api-keys",
            trainsOnDataByDefault = true,
        ),
    )

    fun byId(id: ProviderId): Provider = all.first { it.id == id }
}
