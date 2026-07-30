package com.whispereverywhere.ui.screens

import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure (non-Composable) logic behind the read-aloud voice picker (Task 6):
 * which cloud providers may be OFFERED as a read-aloud engine, and the honest preview copy —
 * the caption, the per-provider price notes, and the no-speed-control disclosure for the two
 * providers that expose no speed parameter. Deliberately Compose-free so it runs as a plain JVM
 * unit test (no Robolectric).
 */
class TtsVoicePickerLogicTest {

    // A cloud VOICE selection sends selected read-aloud TEXT off the device (a new data class), so
    // it is gated exactly like the STT engine selector: disclosure v3 accepted AND a stored key.

    @Test fun no_cloud_voice_is_offered_before_the_v3_disclosure_is_accepted() {
        // The upgrade case: a user who accepted the audio-only v2 has never been told read-aloud
        // text is sent now. Selecting a cloud voice must be unreachable until they accept v3.
        assertTrue(
            ttsSelectableProviders(
                configured = setOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS),
                disclosureAccepted = false,
            ).isEmpty(),
        )
    }

    @Test fun a_provider_with_no_key_is_never_offered_even_after_v3_acceptance() {
        assertTrue(
            ttsSelectableProviders(configured = emptySet(), disclosureAccepted = true).isEmpty(),
        )
    }

    @Test fun a_configured_provider_is_offered_once_v3_is_accepted() {
        val offered = ttsSelectableProviders(setOf(ProviderId.OPENAI), disclosureAccepted = true)
        assertEquals(listOf(ProviderId.OPENAI), offered.map { it.id })
    }

    @Test fun offered_set_is_the_intersection_of_configured_and_tts_capable() {
        val offered = ttsSelectableProviders(
            configured = setOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS),
            disclosureAccepted = true,
        ).map { it.id }.toSet()
        assertEquals(ProviderCatalog.TTS_CAPABLE_PROVIDERS, offered)
    }

    // --- ttsPreviewCaption: previews cost real money and use the user's own key. No speed claim. ---

    @Test fun preview_caption_says_previews_use_the_users_key() {
        assertTrue(ttsPreviewCaption(), ttsPreviewCaption().contains("your key"))
    }

    @Test fun preview_caption_makes_no_speed_claim() {
        // Voice previews cost real money and are not sold on speed — the copy must never imply it.
        val caption = ttsPreviewCaption()
        SPEED_WORDS.forEach { word ->
            assertFalse(caption, caption.contains(word, ignoreCase = true))
        }
    }

    // --- per-provider price notes: honest, from the re-verified numbers, never a speed claim ---

    @Test fun every_tts_provider_has_a_non_blank_price_note() {
        ProviderCatalog.TTS_CAPABLE_PROVIDERS.forEach { id ->
            assertTrue(id.name, ttsProviderPriceNote(id).isNotBlank())
        }
    }

    @Test fun no_price_note_makes_a_speed_claim() {
        ProviderCatalog.TTS_CAPABLE_PROVIDERS.forEach { id ->
            val note = ttsProviderPriceNote(id)
            SPEED_WORDS.forEach { word ->
                assertFalse("$id: $note", note.contains(word, ignoreCase = true))
            }
        }
    }

    // --- no-speed-control disclosure: ElevenLabs and Gemini have no request-time speed param ---

    @Test fun elevenlabs_and_gemini_disclose_they_have_no_speed_control() {
        assertNotNull(ttsNoSpeedControlNote(ProviderId.ELEVENLABS))
        assertNotNull(ttsNoSpeedControlNote(ProviderId.GEMINI))
    }

    @Test fun openai_has_a_speed_param_so_it_shows_no_such_note() {
        assertNull(ttsNoSpeedControlNote(ProviderId.OPENAI))
    }

    private companion object {
        // Same guard-list the STT copy tests use, so "no speed claim" means the same thing here.
        val SPEED_WORDS = listOf("faster", "quicker", "speed", "instant", "quick")
    }
}
