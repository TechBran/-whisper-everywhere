package com.whispereverywhere.ui.onboarding

import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.npu.NpuPackFetch
import com.whispereverywhere.ui.onboarding.OnboardingLogic.Step
import com.whispereverywhere.ui.onboarding.OnboardingSetupViewModel.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLogicTest {

    // ---------------------------------------------------------------- step order

    @Test fun the_flow_walks_permissions_language_engines_cloud_and_ends() {
        assertEquals(Step.LANGUAGE, OnboardingLogic.next(Step.PERMISSIONS))
        assertEquals(Step.ENGINES, OnboardingLogic.next(Step.LANGUAGE))
        assertEquals(Step.CLOUD, OnboardingLogic.next(Step.ENGINES))
        assertNull(OnboardingLogic.next(Step.CLOUD))
    }

    @Test fun back_walks_the_flow_in_reverse_and_null_means_skip() {
        assertNull("back on the first step is a skip, never a block", OnboardingLogic.previous(Step.PERMISSIONS))
        assertEquals(Step.PERMISSIONS, OnboardingLogic.previous(Step.LANGUAGE))
        assertEquals(Step.LANGUAGE, OnboardingLogic.previous(Step.ENGINES))
        assertEquals(Step.ENGINES, OnboardingLogic.previous(Step.CLOUD))
    }

    // ---------------------------------------------------------------- language step (4.2 F6)

    @Test fun theLanguageStepSitsBetweenPermissionsAndEngines() {
        // The 3.8 owner ruling: language BEFORE model download — the step walks
        // PERMISSIONS -> LANGUAGE -> ENGINES, and back retraces the same road.
        assertEquals(Step.LANGUAGE, OnboardingLogic.next(Step.PERMISSIONS))
        assertEquals(Step.ENGINES, OnboardingLogic.next(Step.LANGUAGE))
        assertEquals(Step.LANGUAGE, OnboardingLogic.previous(Step.ENGINES))
        assertEquals(Step.PERMISSIONS, OnboardingLogic.previous(Step.LANGUAGE))
    }

    @Test fun the_language_rows_lead_with_the_device_language_when_the_list_carries_it() {
        val rows = OnboardingLogic.languageRows("es-MX")
        assertEquals("the device's language renders first", "es", rows[0].first)
        assertEquals("auto is one tap away, directly under it", "auto", rows[1].first)
        assertEquals(
            "the remainder is the supported list in its own order, minus the promoted rows",
            PreferencesManager.SUPPORTED_LANGUAGES.filter { it.first != "es" && it.first != "auto" },
            rows.drop(2),
        )
        // Either separator and any case — callers pass whatever the Locale handed them.
        assertEquals("es", OnboardingLogic.languageRows("es_ES")[0].first)
        assertEquals("es", OnboardingLogic.languageRows("ES")[0].first)
        assertEquals("es", OnboardingLogic.deviceLanguageCode("es-419"))
    }

    @Test fun the_language_rows_lead_with_auto_when_the_device_language_is_absent() {
        val rows = OnboardingLogic.languageRows("sq-AL") // Albanian: not in the 54-language list
        assertEquals("device language absent -> auto leads", "auto", rows[0].first)
        assertEquals(
            PreferencesManager.SUPPORTED_LANGUAGES.filter { it.first != "auto" },
            rows.drop(1),
        )
        assertNull(OnboardingLogic.deviceLanguageCode("sq-AL"))
        // "auto" is a list entry, never a device language: no tag can promote it twice.
        assertEquals("auto", OnboardingLogic.languageRows("auto")[0].first)
        assertEquals(1, OnboardingLogic.languageRows("auto").count { it.first == "auto" })
        assertNull(OnboardingLogic.deviceLanguageCode("auto"))
    }

    @Test fun the_language_rows_are_always_a_permutation_of_the_supported_list() {
        // The same 54-plus-auto set Settings' picker offers — nothing lost, nothing invented,
        // whatever the device reports (including degenerate tags).
        for (tag in listOf("en-US", "bn-BD", "sq-AL", "zh_CN", "auto", "")) {
            val rows = OnboardingLogic.languageRows(tag)
            assertEquals(
                "no row lost, none invented ($tag)",
                PreferencesManager.SUPPORTED_LANGUAGES.toSet(),
                rows.toSet(),
            )
            assertEquals(
                "no row duplicated ($tag)",
                PreferencesManager.SUPPORTED_LANGUAGES.size,
                rows.size,
            )
        }
    }

    @Test fun language_continue_stays_locked_until_a_row_is_picked() {
        // The 3.8 mandate is a FORCED choice — the same no-preselection discipline as the model
        // pick: the device-locale row renders first and badged, and the user still taps.
        assertFalse(OnboardingLogic.languageContinueEnabled(null))
        assertTrue(OnboardingLogic.languageContinueEnabled("en"))
        assertTrue(OnboardingLogic.languageContinueEnabled("auto"))
    }

    @Test fun the_language_hint_is_the_38_specs_own_sentence() {
        // Our-own-app relative — a fact about this app's multilingual models, no cross-app claim.
        assertEquals(
            "Choosing a language makes multilingual transcription faster.",
            OnboardingLogic.LANGUAGE_HINT,
        )
        assertEquals("Your device's language", OnboardingLogic.DEVICE_LANGUAGE_BADGE)
    }

    @Test fun the_auto_subtitle_is_the_ruled_text_verbatim() {
        // THE 3.8 OWNER RULING, CARRIED VERBATIM — the plan certification already restored this
        // text once after a softer substitute dropped the disclosed cost (cert round 1, revision
        // 5). It is the honest disclosure of the cost LANGUAGE_HINT beside it asserts; the
        // ruling stands unless the owner re-rules, and nothing here re-asks.
        assertEquals(
            "Slower on multilingual models — detects per session.",
            OnboardingLogic.AUTO_LANGUAGE_SUBTITLE,
        )
    }

    // ------------------------------------------- the pack fetch on the engine card (4.2 F6)

    @Test fun fetch_pending_transferring_and_idle_all_read_preparing() {
        val preparing = EngineState.Working(OnboardingSetupViewModel.INDETERMINATE, "Preparing")
        assertEquals(preparing, OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Pending))
        assertEquals(preparing, OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Transferring))
        // Idle mid-collect is a fetch that has not published yet (the collector only runs after
        // start()), not an error — it reads as preparing, never as a refusal.
        assertEquals(preparing, OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Idle))
    }

    @Test fun fetch_downloading_names_google_play_and_carries_the_pct() {
        assertEquals(
            EngineState.Working(25, "Downloading from Google Play"),
            OnboardingLogic.engineStateForFetch(
                NpuPackFetch.FetchState.Downloading(soFar = 225_443_840L, total = 901_775_360L)
            ),
        )
        // Total-safe like every pct in this codebase: an unknown total is 0%, never a crash.
        assertEquals(
            EngineState.Working(0, "Downloading from Google Play"),
            OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Downloading(0L, 0L)),
        )
    }

    @Test fun fetch_verifying_reads_verifying_with_the_pct() {
        // The same word the download path's verify phase uses — one vocabulary on the card.
        assertEquals(
            EngineState.Working(50, OnboardingSetupViewModel.VERIFYING),
            OnboardingLogic.engineStateForFetch(
                NpuPackFetch.FetchState.Verifying(soFar = 535_842_816L, total = 1_071_685_632L)
            ),
        )
    }

    @Test fun fetch_needs_confirmation_names_plays_dialog_and_installed_means_ready() {
        assertEquals(
            EngineState.Working(
                OnboardingSetupViewModel.INDETERMINATE,
                "Waiting for your OK in the Google Play dialog",
            ),
            OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.NeedsConfirmation),
        )
        // Installed is only ever published after the pair is census-verified, renamed into
        // place and announced (the controller's contract) — so it IS Ready, nothing more to do.
        assertEquals(
            EngineState.Ready,
            OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Installed),
        )
    }

    @Test fun fetch_failures_carry_the_reason_verbatim_and_cancelled_names_the_retry() {
        // Failed is the F5 refusal CARRIER: the reason is finished user-facing copy, rendered
        // verbatim — this mapping never rewrites a refusal.
        val reason = NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_NOT_OWNED)
        assertEquals(
            EngineState.Failed(reason),
            OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Failed(reason)),
        )
        assertEquals(
            EngineState.Failed("Download cancelled — tap Retry to start again."),
            OnboardingLogic.engineStateForFetch(NpuPackFetch.FetchState.Cancelled),
        )
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
