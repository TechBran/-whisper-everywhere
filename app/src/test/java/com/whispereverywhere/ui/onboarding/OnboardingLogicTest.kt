package com.whispereverywhere.ui.onboarding

import com.whispereverywhere.ui.onboarding.OnboardingLogic.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLogicTest {

    // ---------------------------------------------------------------- step order

    @Test fun the_flow_walks_permissions_engines_cloud_and_ends() {
        assertEquals(Step.ENGINES, OnboardingLogic.next(Step.PERMISSIONS))
        assertEquals(Step.CLOUD, OnboardingLogic.next(Step.ENGINES))
        assertNull(OnboardingLogic.next(Step.CLOUD))
    }

    @Test fun back_walks_the_flow_in_reverse_and_null_means_skip() {
        assertNull("back on the first step is a skip, never a block", OnboardingLogic.previous(Step.PERMISSIONS))
        assertEquals(Step.PERMISSIONS, OnboardingLogic.previous(Step.ENGINES))
        assertEquals(Step.ENGINES, OnboardingLogic.previous(Step.CLOUD))
    }

    // ---------------------------------------------------------------- engines gating

    @Test fun continue_unlocks_when_dictation_is_possible_not_when_everything_is() {
        // The ~365 MB voice keeps downloading behind the flow; holding the user for it would
        // punish exactly the slow connections that need the escape most.
        assertTrue(OnboardingLogic.enginesContinueEnabled(speechReady = true, speechFailed = false))
        assertFalse(OnboardingLogic.enginesContinueEnabled(speechReady = false, speechFailed = false))
    }

    @Test fun a_failed_speech_download_still_unblocks_continue() {
        // Onboarding must never wedge: the row shows Retry, and Home's setup banner remains the
        // manual path for a user who moves on.
        assertTrue(OnboardingLogic.enginesContinueEnabled(speechReady = false, speechFailed = true))
    }

    @Test fun the_background_voice_hint_shows_exactly_while_speech_is_ready_and_voice_is_not() {
        assertEquals(
            "The read-aloud voice keeps downloading in the background — no need to wait.",
            OnboardingLogic.enginesContinueHint(speechReady = true, voiceReady = false),
        )
        assertNull(OnboardingLogic.enginesContinueHint(speechReady = true, voiceReady = true))
        assertNull(OnboardingLogic.enginesContinueHint(speechReady = false, voiceReady = false))
    }

    // ---------------------------------------------------------------- home permission chip

    @Test fun the_chip_counts_only_bubble_blocking_permissions() {
        assertEquals(0, OnboardingLogic.missingBubblePermissions(mic = true, overlay = true, accessibility = true))
        assertEquals(1, OnboardingLogic.missingBubblePermissions(mic = true, overlay = true, accessibility = false))
        assertEquals(3, OnboardingLogic.missingBubblePermissions(mic = false, overlay = false, accessibility = false))
    }

    @Test fun the_chip_is_absent_when_everything_is_granted() {
        // The clean dashboard stays clean — the chip exists only while something is actually
        // wrong (owner report 2026-08-01: granted permissions were visible only in Settings,
        // missing ones nowhere at all).
        assertNull(OnboardingLogic.homePermissionChipText(0))
    }

    @Test fun the_chip_text_counts_honestly() {
        assertEquals("1 permission still needed — tap to review", OnboardingLogic.homePermissionChipText(1))
        assertEquals("3 permissions still needed — tap to review", OnboardingLogic.homePermissionChipText(3))
    }
}
