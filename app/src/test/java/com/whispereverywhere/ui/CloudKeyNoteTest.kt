package com.whispereverywhere.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudKeyNoteTest {

    // ------------------------------------------------------------------ visibility truth table
    // The full configured × dismissed table — the note shows in exactly ONE of the four cells.

    @Test fun shows_only_while_unconfigured_and_undismissed() {
        assertTrue(CloudKeyNote.shouldShow(cloudProviderConfigured = false, dismissed = false))
    }

    @Test fun dismissal_hides_it() {
        assertFalse(CloudKeyNote.shouldShow(cloudProviderConfigured = false, dismissed = true))
    }

    @Test fun a_configured_cloud_provider_hides_it_even_when_never_dismissed() {
        // "Configuring a cloud key hides it permanently regardless of dismissal" (spec B).
        assertFalse(CloudKeyNote.shouldShow(cloudProviderConfigured = true, dismissed = false))
    }

    @Test fun configured_and_dismissed_hides_it() {
        assertFalse(CloudKeyNote.shouldShow(cloudProviderConfigured = true, dismissed = true))
    }

    // ------------------------------------------------------------------ copy discipline
    // The discipline surface for this card: an accuracy/language pitch is allowed, a speed
    // claim is not (owner decision, same rule that pins HowToGuide and the listing copy).

    @Test fun the_card_copy_makes_no_speed_claims() {
        val text = (CloudKeyNote.HEADLINE + " " + CloudKeyNote.BODY + " " + CloudKeyNote.BUTTON)
            .lowercase()
        listOf("faster", "fastest", "quicker", "instant", "speed").forEach { banned ->
            assertFalse("cloud-key note contains banned speed word: $banned", text.contains(banned))
        }
    }

    @Test fun the_copy_is_the_owner_approved_text_verbatim() {
        assertEquals("Want top accuracy or more languages?", CloudKeyNote.HEADLINE)
        assertEquals(
            "Add your own API key — large cloud models from OpenAI, Gemini, ElevenLabs, " +
                "or Soniox, billed to your own account.",
            CloudKeyNote.BODY
        )
        assertEquals("Open Engines & voices", CloudKeyNote.BUTTON)
    }

    @Test fun the_billing_truth_is_present() {
        // Cloud is always the user's own account, never ours — same invariant HowToGuideTest pins.
        assertTrue(CloudKeyNote.BODY.lowercase().contains("billed to your own account"))
    }
}
