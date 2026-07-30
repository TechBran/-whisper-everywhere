package com.whispereverywhere.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeDashboardLogicTest {

    // --- setup banner ---
    @Test fun no_model_no_key_shows_two_path() =
        assertEquals(SetupBanner.TWO_PATH, setupBannerState(hasModel = false, hasAnyKey = false))
    @Test fun model_only_is_partial() =
        assertEquals(SetupBanner.PARTIAL_LINE, setupBannerState(hasModel = true, hasAnyKey = false))
    @Test fun key_only_is_partial() =
        assertEquals(SetupBanner.PARTIAL_LINE, setupBannerState(hasModel = false, hasAnyKey = true))
    @Test fun both_configured_shows_nothing() =
        assertEquals(SetupBanner.NONE, setupBannerState(hasModel = true, hasAnyKey = true))

    @Test fun partial_line_model_path_mentions_key_no_speed_claim() {
        val s = partialSetupLine(hasModel = true)
        assertTrue(s, s.contains("key"))
        assertFalse(s, s.contains("fast"))
    }
    @Test fun partial_line_key_path_mentions_model() =
        assertTrue(partialSetupLine(hasModel = false).contains("model"))

    // --- transcription / dictation chips ---
    @Test fun on_device_chip_with_tier() =
        assertEquals("On-device · Eco", transcriptionEngineChip(engineDisplayName = null, localModelLabel = "Eco"))
    @Test fun on_device_chip_without_tier() =
        assertEquals("On-device", transcriptionEngineChip(engineDisplayName = null, localModelLabel = null))
    @Test fun cloud_engine_chip_is_display_name() =
        assertEquals("OpenAI", transcriptionEngineChip(engineDisplayName = "OpenAI", localModelLabel = "Eco"))

    @Test fun dictation_word_for_word_appends_only_for_cloud() {
        assertEquals("OpenAI · word-for-word", dictationChip("OpenAI", null, liveMode = true))
        // liveMode can never be true on-device (liveModeRowVisible gates it to OpenAI); the guard
        // proves it never leaves a dangling suffix on the on-device chip.
        assertEquals("On-device · Eco", dictationChip(null, "Eco", liveMode = true))
    }
    @Test fun dictation_non_live_matches_engine_chip() =
        assertEquals("OpenAI", dictationChip("OpenAI", null, liveMode = false))
    @Test fun dictation_chip_makes_no_speed_claim() =
        assertFalse(dictationChip("OpenAI", null, liveMode = true).contains("fast"))

    // --- read-aloud chip ---
    @Test fun kokoro_chip_with_voice() =
        assertEquals("Kokoro · af_heart", readAloudChip(engineDisplayName = null, voiceDisplayName = "af_heart"))
    @Test fun kokoro_chip_without_voice() =
        assertEquals("Kokoro", readAloudChip(engineDisplayName = null, voiceDisplayName = null))
    @Test fun cloud_voice_chip_names_voice_and_provider() =
        assertEquals("marin (OpenAI)", readAloudChip(engineDisplayName = "OpenAI", voiceDisplayName = "marin"))
    @Test fun cloud_engine_without_voice_prompts_choice() =
        assertEquals("OpenAI · choose a voice", readAloudChip(engineDisplayName = "OpenAI", voiceDisplayName = null))

    // --- first-run routing ---
    @Test fun fresh_install_goes_to_chooser() =
        assertEquals(ROUTE_FIRST_RUN, firstRunStartDestination(hasModel = false, onboardingCompleted = false))
    @Test fun existing_user_with_model_goes_home() =
        assertEquals(ROUTE_HOME, firstRunStartDestination(hasModel = true, onboardingCompleted = false))
    @Test fun skipper_or_key_user_goes_home() =
        assertEquals(ROUTE_HOME, firstRunStartDestination(hasModel = false, onboardingCompleted = true))
}
