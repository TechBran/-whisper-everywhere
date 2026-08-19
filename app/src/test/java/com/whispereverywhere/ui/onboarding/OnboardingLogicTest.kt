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

    @Test fun continue_unlocks_only_once_the_speech_model_is_ready() {
        // Owner decision 2026-08-18 (mandatory model): rewritten from the earlier never-wedge
        // pinning — dictation needs the local model, so Continue now tracks speechReady alone.
        assertTrue(OnboardingLogic.enginesContinueEnabled(speechReady = true))
        assertFalse(OnboardingLogic.enginesContinueEnabled(speechReady = false))
    }

    @Test fun a_failed_download_holds_the_step_instead_of_unlocking_continue() {
        // Owner decision 2026-08-18 (mandatory model): the deliberate reversal of the old
        // never-wedge rule — a failed download now HOLDS the step; the row's Retry is the way
        // forward, not a bypass. (speechFailed is gone from the signature entirely.)
        val action = OnboardingLogic.enginesPrimaryAction(
            downloadsBegun = true, tierPicked = true, speechReady = false,
        )
        assertEquals("Continue", action.label)
        assertFalse(action.enabled)
        assertFalse(action.startsDownloads)
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

    // ---------------------------------------------------------------- engines chooser (3.5.0)

    @Test fun no_preselection_means_the_download_action_starts_disabled() {
        // Owner decision: the user must make an informed pick — the disabled Download button is
        // what forces it.
        val action = OnboardingLogic.enginesPrimaryAction(
            downloadsBegun = false, tierPicked = false, speechReady = false,
        )
        assertEquals("Download", action.label)
        assertFalse(action.enabled)
        assertTrue(action.startsDownloads)
    }

    @Test fun picking_a_tier_is_all_it_takes_to_unlock_download() {
        val action = OnboardingLogic.enginesPrimaryAction(
            downloadsBegun = false, tierPicked = true, speechReady = false,
        )
        assertTrue(action.enabled)
        assertTrue(action.startsDownloads)
    }

    @Test fun once_downloads_begin_the_action_is_continue_gated_on_speech_ready() {
        // One pick, then no buttons: after the confirm the footer is Continue, gated on the
        // speech model reaching Ready (owner decision 2026-08-18: mandatory model). The Failed
        // case is pinned separately in
        // a_failed_download_holds_the_step_instead_of_unlocking_continue.
        val working = OnboardingLogic.enginesPrimaryAction(
            downloadsBegun = true, tierPicked = true, speechReady = false,
        )
        assertEquals("Continue", working.label)
        assertFalse(working.enabled)
        assertFalse(working.startsDownloads)
        assertTrue(
            OnboardingLogic.enginesPrimaryAction(
                downloadsBegun = true, tierPicked = true, speechReady = true,
            ).enabled
        )
    }

    @Test fun the_switch_anytime_hint_is_pinned_exactly() {
        // Spec A3: plants the switching habit and lowers the stakes of the forced choice.
        assertEquals(
            "Not sure? Pick one — you can switch models anytime in Settings.",
            OnboardingLogic.TIER_SWITCH_HINT,
        )
    }

    // ---------------------------------------------------------------- permissions gate (3.5.x)

    @Test fun permissions_continue_unlocks_only_when_all_three_bubble_permissions_are_granted() {
        assertTrue(OnboardingLogic.permissionsContinueEnabled(mic = true, overlay = true, accessibility = true))
        assertFalse(OnboardingLogic.permissionsContinueEnabled(mic = false, overlay = true, accessibility = true))
        assertFalse(OnboardingLogic.permissionsContinueEnabled(mic = true, overlay = false, accessibility = true))
        assertFalse(OnboardingLogic.permissionsContinueEnabled(mic = true, overlay = true, accessibility = false))
    }

    @Test fun permissions_hint_counts_whats_missing_and_stays_silent_when_nothing_is() {
        assertNull(OnboardingLogic.permissionsContinueHint(0))
        assertEquals(
            "1 required permission still needed — notification access is optional.",
            OnboardingLogic.permissionsContinueHint(1),
        )
        assertEquals(
            "3 required permissions still needed — notification access is optional.",
            OnboardingLogic.permissionsContinueHint(3),
        )
    }
}
