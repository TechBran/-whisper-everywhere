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

    // --- word-for-word is OpenAI-only (mirrors decideEngineChoice); sttLiveMode is left set after
    //     an engine switch, so the chip must re-apply the OpenAI rule itself ---
    @Test fun live_active_only_for_openai() {
        assertTrue(dictationLiveActive("OPENAI", sttLiveMode = true))
        assertFalse(dictationLiveActive("GEMINI", sttLiveMode = true))
        assertFalse(dictationLiveActive("ELEVENLABS", sttLiveMode = true))
        assertFalse(dictationLiveActive(null, sttLiveMode = true))
        assertFalse(dictationLiveActive("OPENAI", sttLiveMode = false))
    }
    @Test fun dictation_chip_no_word_for_word_on_stale_live_after_switch_to_gemini() {
        // Repro: select OpenAI, enable word-for-word, switch engine to Gemini. sttLiveMode stays true
        // but is inert (Gemini runs batch). The chip must read "Gemini", never "Gemini · word-for-word".
        val live = dictationLiveActive("GEMINI", sttLiveMode = true)
        assertEquals("Gemini", dictationChip("Gemini", null, live))
    }

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

    // --- main control button copy (permissions live in Settings now, not "below") ---
    @Test fun main_control_enabled_says_active() =
        assertEquals("Bubble is active", mainControlLabels(isEnabled = true, canEnable = false).subtitle)
    @Test fun main_control_ready_says_inactive() =
        assertEquals("Bubble is inactive", mainControlLabels(isEnabled = false, canEnable = true).subtitle)
    @Test fun main_control_incomplete_points_to_settings_not_below() {
        val l = mainControlLabels(isEnabled = false, canEnable = false)
        assertTrue(l.subtitle, l.subtitle.contains("Settings"))
        assertFalse(l.subtitle, l.subtitle.contains("below"))
    }
}
