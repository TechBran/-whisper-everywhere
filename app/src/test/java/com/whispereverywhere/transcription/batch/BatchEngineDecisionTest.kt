package com.whispereverywhere.transcription.batch

import com.whispereverywhere.provider.ProviderId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the per-job engine floor (review Critical + Important, the useCloud regression).
 *
 * The bug: a cloud-eligible user who explicitly picked "On-device" on a sub-threshold file was
 * still uploaded and billed, because the service re-derived cloud from global prefs and ignored the
 * Ready-screen radio. The floor is now [BatchEngineDecision.cloudAllowed]'s first check.
 */
class BatchEngineDecisionTest {

    // A fully cloud-eligible, below-threshold, confirmed context — cloud would be allowed but for
    // the useCloud floor. subThreshold bytes: 1 MB is far under the §6.5 confirm threshold.
    private val subThreshold = 1L * 1024 * 1024

    @Test fun on_device_pick_forbids_cloud_and_never_probes_network_or_notifications() {
        var networkProbed = false
        var notificationsProbed = false
        val allowed = BatchEngineDecision.cloudAllowed(
            useCloud = false,                       // the user picked On-device
            providerId = ProviderId.OPENAI,
            key = "sk-live",
            disclosureAccepted = true,
            byteLength = subThreshold,
            costConfirmed = false,
            hasValidatedNetwork = { networkProbed = true; true },
            notificationsEnabled = { notificationsProbed = true; true },
        )
        assertFalse("On-device is a hard floor — cloud must be forbidden", allowed)
        // The floor short-circuits BEFORE any side-effecting probe: zero network work for a local job.
        assertFalse("must not probe the network for an on-device job", networkProbed)
        assertFalse("must not probe notifications for an on-device job", notificationsProbed)
    }

    @Test fun cloud_pick_with_full_triad_below_threshold_is_allowed() {
        val allowed = BatchEngineDecision.cloudAllowed(
            useCloud = true,
            providerId = ProviderId.OPENAI,
            key = "sk-live",
            disclosureAccepted = true,
            byteLength = subThreshold,
            costConfirmed = false,
            hasValidatedNetwork = { true },
            notificationsEnabled = { true },
        )
        assertTrue(allowed)
    }

    @Test fun cloud_pick_missing_triad_degrades_to_local() {
        val allowed = BatchEngineDecision.cloudAllowed(
            useCloud = true,
            providerId = ProviderId.OPENAI,
            key = null,                              // no stored key — triad broken
            disclosureAccepted = true,
            byteLength = subThreshold,
            costConfirmed = false,
            hasValidatedNetwork = { true },
            notificationsEnabled = { true },
        )
        assertFalse(allowed)
    }

    @Test fun cloud_pick_no_network_degrades_to_local() {
        val allowed = BatchEngineDecision.cloudAllowed(
            useCloud = true, providerId = ProviderId.OPENAI, key = "sk", disclosureAccepted = true,
            byteLength = subThreshold, costConfirmed = false,
            hasValidatedNetwork = { false }, notificationsEnabled = { true },
        )
        assertFalse(allowed)
    }

    @Test fun cloud_pick_notifications_off_degrades_to_local() {
        val allowed = BatchEngineDecision.cloudAllowed(
            useCloud = true, providerId = ProviderId.OPENAI, key = "sk", disclosureAccepted = true,
            byteLength = subThreshold, costConfirmed = false,
            hasValidatedNetwork = { true }, notificationsEnabled = { false },
        )
        assertFalse(allowed)
    }

    @Test fun a_sub_threshold_gemini_job_without_cost_confirm_degrades_to_local() {
        // Finding #2 end-to-end: Gemini's price is unpublished, so even a sub-threshold job (which an
        // OpenAI job of the same size is allowed to skip, see cloud_pick_..._below_threshold_is_allowed)
        // must NOT proceed to cloud without the explicit confirm — the force-confirm reaches the floor.
        val allowed = BatchEngineDecision.cloudAllowed(
            useCloud = true, providerId = ProviderId.GEMINI, key = "gem-k", disclosureAccepted = true,
            byteLength = subThreshold, costConfirmed = false,
            hasValidatedNetwork = { true }, notificationsEnabled = { true },
        )
        assertFalse("Gemini's unpublished price forces the confirm even below threshold", allowed)
    }

    @Test fun above_threshold_without_cost_confirm_degrades_to_local() {
        // A large file (well past the confirm threshold) that never got the explicit confirm.
        val huge = 400L * 1024 * 1024
        val allowed = BatchEngineDecision.cloudAllowed(
            useCloud = true, providerId = ProviderId.OPENAI, key = "sk", disclosureAccepted = true,
            byteLength = huge, costConfirmed = false,
            hasValidatedNetwork = { true }, notificationsEnabled = { true },
        )
        assertFalse("a large cloud job needs the explicit cost confirm", allowed)
    }

    @Test fun above_threshold_with_cost_confirm_is_allowed() {
        val huge = 400L * 1024 * 1024
        val allowed = BatchEngineDecision.cloudAllowed(
            useCloud = true, providerId = ProviderId.OPENAI, key = "sk", disclosureAccepted = true,
            byteLength = huge, costConfirmed = true,
            hasValidatedNetwork = { true }, notificationsEnabled = { true },
        )
        assertTrue(allowed)
    }
}
