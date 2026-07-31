package com.whispereverywhere.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Home usage-stats footer promise ("No usage limits - transcription runs entirely on-device")
 * is honest ONLY on-device. This pins that it is shown when no cloud STT provider is selected
 * (sttEngineName == null) and suppressed for every cloud selection — where both clauses (no limits,
 * on-device) are false. Guards the exact over-promise the rest of the app was scrubbed of.
 */
class UsageStatsFooterLogicTest {

    @Test fun on_device_selection_shows_the_no_limits_line() {
        assertEquals(
            "No usage limits - transcription runs entirely on-device",
            usageStatsFooterLabel(sttEngineName = null),
        )
    }

    @Test fun any_cloud_selection_shows_no_footer_at_all() {
        // sttEngineName is a resolved provider display name for a cloud selection; both footer
        // clauses would be false for it, so nothing is shown rather than an over-promise.
        assertNull(usageStatsFooterLabel(sttEngineName = "OpenAI"))
        assertNull(usageStatsFooterLabel(sttEngineName = "Google Gemini"))
        assertNull(usageStatsFooterLabel(sttEngineName = "ElevenLabs"))
        assertNull(usageStatsFooterLabel(sttEngineName = "Soniox"))
    }
}
