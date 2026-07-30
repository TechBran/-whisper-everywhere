package com.whispereverywhere.data.local

import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.tts.resolveTtsProvider
import com.whispereverywhere.tts.ttsCloudVoiceKey
import com.whispereverywhere.ui.screens.selectionAfterKeyRemoval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cloud-voice preference logic, tested PURE (SharedPreferences is a framework class and this
 * project runs plain JVM unit tests, so the round-trip is proven through the pure resolution and
 * namespacing functions the getters/setters delegate to — the same shape as EngineSelectionTest and
 * CloudProvidersScreenLogicTest).
 */
class PreferencesTtsCloudTest {

    @Test fun default_provider_is_null_meaning_local() {
        // No stored value => on-device Kokoro, the default and the shipped behaviour.
        assertNull(resolveTtsProvider(null))
    }

    @Test fun provider_id_round_trips_for_all_tts_capable_providers() {
        // A stored ProviderId NAME resolves back to that provider (persistence keys off name, never
        // ordinal), so a cloud voice selection survives a round-trip through prefs.
        assertEquals(ProviderId.OPENAI, resolveTtsProvider("OPENAI"))
        assertEquals(ProviderId.GEMINI, resolveTtsProvider("GEMINI"))
        assertEquals(ProviderId.ELEVENLABS, resolveTtsProvider("ELEVENLABS"))
    }

    @Test fun unknown_or_blank_or_stale_provider_resolves_to_null() {
        // A foreign / corrupt / empty value must resolve to on-device, never crash or half-select.
        assertNull(resolveTtsProvider(""))
        assertNull(resolveTtsProvider("NOT_A_PROVIDER"))
        assertNull(resolveTtsProvider("openai")) // enum names are upper-case; lower must not match
    }

    @Test fun per_provider_voice_keys_are_namespaced_and_distinct() {
        // A speakerId:Int cannot carry a provider voice string, and one shared string key would let
        // OpenAI's voice masquerade as Gemini's. Each provider gets its OWN namespaced key.
        assertEquals("tts_cloud_voice_OPENAI", ttsCloudVoiceKey(ProviderId.OPENAI))
        assertEquals("tts_cloud_voice_GEMINI", ttsCloudVoiceKey(ProviderId.GEMINI))
        assertEquals("tts_cloud_voice_ELEVENLABS", ttsCloudVoiceKey(ProviderId.ELEVENLABS))
        val keys = ProviderId.entries.map { ttsCloudVoiceKey(it) }
        assertEquals("keys must be distinct per provider", keys.size, keys.toSet().size)
    }

    @Test fun per_provider_voice_store_does_not_bleed_across_providers() {
        // Simulate the SharedPreferences round-trip through the namespaced keys: a voice set for one
        // provider is invisible to another, and an unselected provider reads back null.
        val store = HashMap<String, String>()
        store[ttsCloudVoiceKey(ProviderId.OPENAI)] = "marin"
        store[ttsCloudVoiceKey(ProviderId.GEMINI)] = "Kore"

        assertEquals("marin", store[ttsCloudVoiceKey(ProviderId.OPENAI)])
        assertEquals("Kore", store[ttsCloudVoiceKey(ProviderId.GEMINI)])
        assertNull(store[ttsCloudVoiceKey(ProviderId.ELEVENLABS)])
    }

    @Test fun removing_the_selected_providers_key_returns_to_local() {
        // A cloud voice selection must not outlive its own credential (mirror of the STT gate,
        // extended to the TTS provider selection).
        assertNull(selectionAfterKeyRemoval(ProviderId.OPENAI.name, ProviderId.OPENAI))
    }

    @Test fun removing_a_different_providers_key_leaves_the_selection() {
        assertEquals(
            ProviderId.OPENAI.name,
            selectionAfterKeyRemoval(ProviderId.OPENAI.name, ProviderId.ELEVENLABS),
        )
    }
}
