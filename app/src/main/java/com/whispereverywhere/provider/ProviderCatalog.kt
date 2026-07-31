package com.whispereverywhere.provider

/**
 * Stable identity for a cloud provider.
 *
 * Persistence keys off [name], NEVER the ordinal: reordering this enum would otherwise silently
 * repoint every user's stored credential at a different provider.
 */
enum class ProviderId { OPENAI, GEMINI, ELEVENLABS, SONIOX }

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
        Provider(
            id = ProviderId.SONIOX,
            displayName = "Soniox",
            // Bearer, like OpenAI — unlike ElevenLabs' bare xi-api-key / Gemini's bare header key.
            authHeaderName = "Authorization",
            authHeaderValue = { "Bearer $it" },
            // Cheapest authenticated GET; a bad key returns 401 unauthenticated, which the generic
            // KeyValidator already maps to Invalid — no per-provider marker needed (Soniox uses a
            // clean 401 for a bad key, and 402 for exhausted balance, both already handled).
            validationUrl = "https://api.soniox.com/v1/models",
            supportsStt = true,
            // STT-only this wave. Soniox has a TTS surface, but no adapter ships here; it stays out
            // of TTS_CAPABLE_PROVIDERS and TtsProviderFactory rejects it.
            supportsTts = false,
            // v1 is the per-VAD-segment async path (like Gemini/ElevenLabs). The realtime WebSocket
            // is a recorded follow-up, not this wave.
            supportsStreaming = false,
            // Verify against the live Console at resolve time; the API-keys page lives under the
            // Soniox Console.
            keyHelpUrl = "https://console.soniox.com/",
            // Quote-backed from Security & Privacy: audio + transcripts are "never used to improve
            // Soniox models or services." (The async path DOES store them until we DELETE — the
            // disclosure line pairs no-training with delete-after, which the adapter's cleanup makes
            // literally true.)
            trainsOnDataByDefault = false,
        ),
    )

    fun byId(id: ProviderId): Provider = all.first { it.id == id }

    /**
     * Providers this release can actually synthesize read-aloud through — the TTS analogue of
     * [com.whispereverywhere.ui.screens.STT_CAPABLE_PROVIDERS]. It is the honest "has a built cloud
     * TTS adapter" gate and MUST stay in lockstep with [com.whispereverywhere.tts.cloud.TtsProviderFactory]
     * (whose exhaustive `when` will not compile if a mapped provider is missing here, or vice-versa).
     * All three ship a [Provider.supportsTts] adapter as of this release; on-device Kokoro remains the
     * default and the fallback and is never a member of this set.
     */
    val TTS_CAPABLE_PROVIDERS: Set<ProviderId> =
        setOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS)
}
