package com.whispereverywhere.service

import com.whispereverywhere.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the pure (Context-free) decision logic FloatingBubbleService uses to pick a
 * transcription engine. Kept free of Android/Service state so it runs as a plain JVM test — the
 * Service itself cannot be unit-tested without Robolectric, but the branch table that decides
 * local vs. cloud can and must be, since it IS the one-way valve the brief calls out: cloud must
 * be reachable ONLY when a provider has been explicitly selected.
 */
class EngineSelectionTest {

    // --- decideEngineChoice: the one-way valve ---

    @Test fun no_provider_selected_is_always_local_regardless_of_key_or_network() {
        assertEquals(
            EngineChoice.LOCAL_ONLY,
            decideEngineChoice(sttProviderId = null, hasKey = true, hasValidatedNetwork = true),
        )
        assertEquals(
            EngineChoice.LOCAL_ONLY,
            decideEngineChoice(sttProviderId = null, hasKey = false, hasValidatedNetwork = false),
        )
    }

    @Test fun provider_selected_but_no_key_falls_back_local() {
        assertEquals(
            EngineChoice.LOCAL_NO_KEY,
            decideEngineChoice(sttProviderId = "OPENAI", hasKey = false, hasValidatedNetwork = true),
        )
    }

    @Test fun provider_and_key_but_no_validated_network_falls_back_local() {
        assertEquals(
            EngineChoice.LOCAL_OFFLINE,
            decideEngineChoice(sttProviderId = "OPENAI", hasKey = true, hasValidatedNetwork = false),
        )
    }

    @Test fun provider_key_and_validated_network_selects_cloud_with_fallback() {
        assertEquals(
            EngineChoice.CLOUD_WITH_FALLBACK,
            decideEngineChoice(sttProviderId = "OPENAI", hasKey = true, hasValidatedNetwork = true),
        )
    }

    @Test fun missing_key_and_missing_network_together_still_report_the_key_as_the_reason() {
        // Branch ORDER matters here: hasKey is checked before hasValidatedNetwork, so when both
        // are false the more actionable "no key" message is what the user sees, not "offline".
        assertEquals(
            EngineChoice.LOCAL_NO_KEY,
            decideEngineChoice(sttProviderId = "OPENAI", hasKey = false, hasValidatedNetwork = false),
        )
    }

    // --- resolveSttProvider: C2a ships only the OpenAI adapter (Gemini/ElevenLabs are C2b) ---

    @Test fun resolves_openai_by_its_stored_name() {
        assertEquals(ProviderId.OPENAI, resolveSttProvider("OPENAI"))
    }

    @Test fun null_preference_resolves_to_null() {
        assertNull(resolveSttProvider(null))
    }

    @Test fun an_unimplemented_but_valid_provider_name_resolves_to_null_not_a_crash() {
        // Gemini/ElevenLabs are real ProviderId names but have no STT adapter in this build; a
        // stale or foreign preference value must degrade to "no key", never throw.
        assertNull(resolveSttProvider("GEMINI"))
        assertNull(resolveSttProvider("ELEVENLABS"))
    }

    @Test fun a_garbage_preference_value_resolves_to_null_without_throwing() {
        assertNull(resolveSttProvider("not-a-real-provider"))
    }
}
