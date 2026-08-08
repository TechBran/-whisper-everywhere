package com.whispereverywhere.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HowToGuideTest {

    @Test fun the_guide_makes_no_speed_claims_anywhere() {
        // The same copy discipline as every other user-facing string: measured on-device
        // transcription is a tie at best against cloud, so a speed claim would be a lie.
        val text = HowToGuide.plainText().lowercase()
        listOf("faster", "fastest", "quicker", "quickest", "instant", "blazing", "lightning")
            .forEach { banned ->
                assertFalse("guide contains banned speed word: $banned", text.contains(banned))
            }
    }

    @Test fun money_talk_stays_honest() {
        val text = HowToGuide.plainText().lowercase()
        // Prices are "approximate"; the monthly line is an "estimate, not your provider's bill".
        assertTrue(text.contains("approximate"))
        assertTrue(text.contains("estimate, not your provider's bill"))
        // Cloud is always the user's own account, never ours.
        assertTrue(text.contains("your own account") || text.contains("your own api keys"))
    }

    @Test fun the_privacy_promise_is_stated_and_scoped() {
        // On-device by default; cloud only when the USER sets it up. Both halves must be present —
        // the first alone would overpromise now that device audio follows the engine selection.
        val text = HowToGuide.plainText().lowercase()
        assertTrue(text.contains("on your phone by default"))
        assertTrue(text.contains("unless you set up a cloud provider"))
    }

    @Test fun the_dictation_flow_is_final_only_and_the_window_resizable() {
        // 3.4.0: text lands once, at stop — and the transcript window has a resize handle.
        val text = HowToGuide.plainText().lowercase()
        assertTrue(text.contains("when you tap the bubble again to stop"))
        assertTrue(text.contains("typed at the cursor in one go"))
        assertTrue(text.contains("double arrow"))
    }

    @Test fun the_spoken_guide_carries_every_section() {
        val text = HowToGuide.plainText()
        HowToGuide.sections.forEach { s ->
            assertTrue("plainText missing section: ${s.title}", text.contains(s.title))
            assertTrue("plainText missing body of: ${s.title}", text.contains(s.body))
        }
        // And it opens by naming itself — the listener should know what they are hearing.
        assertTrue(text.startsWith("How to use Whisper Everywhere."))
    }

    @Test fun every_section_has_real_content() {
        HowToGuide.sections.forEach { s ->
            assertTrue(s.title.isNotBlank())
            assertTrue("section '${s.title}' suspiciously short", s.body.length > 80)
        }
    }
}
