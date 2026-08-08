package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The W2 final-only-commit decision table. Four booleans in, one plan out; every one of the
 * 16 combinations is pinned here. The semantics rows (spec 2026-08-08, Workstream 2):
 *   transcriptBlank            -> (null, false)                       — nothing to deliver
 *   field && !degraded         -> (SESSION_BOUND, false)              — the one injection
 *   field && degraded          -> (null, true)                        — one consolidated copy
 *   !field                     -> (FINALIZE_FOCUS if live target else null, true)
 */
class FinalDeliveryPolicyTest {

    // ---------------------------------------------- row 1: blank transcript wins over everything

    @Test fun a_blank_transcript_delivers_nothing_no_matter_what() {
        for (field in listOf(true, false))
            for (degraded in listOf(true, false))
                for (live in listOf(true, false)) {
                    assertEquals(
                        "field=$field degraded=$degraded live=$live",
                        FinalDeliveryPlan(inject = null, copyWholeToClipboard = false),
                        FinalDeliveryPolicy.decide(
                            isTextFieldSession = field,
                            degradedToClipboard = degraded,
                            hasLiveInputTarget = live,
                            transcriptBlank = true,
                        ),
                    )
                }
    }

    // ---------------------------------------------- row 2: healthy field session -> session-bound

    @Test fun a_healthy_field_session_injects_into_the_session_bound_target() {
        // hasLiveInputTarget is deliberately IRRELEVANT here: the session-bound write resolves
        // dead nodes itself (resolveInjectionTarget's focused-field fallback) — the policy must
        // not second-guess it, or a dead node would silently demote a field session to clipboard.
        for (live in listOf(true, false)) {
            assertEquals(
                "hasLiveInputTarget=$live",
                FinalDeliveryPlan(inject = InjectTarget.SESSION_BOUND, copyWholeToClipboard = false),
                FinalDeliveryPolicy.decide(
                    isTextFieldSession = true,
                    degradedToClipboard = false,
                    hasLiveInputTarget = live,
                    transcriptBlank = false,
                ),
            )
        }
    }

    // ---------------------------------------------- row 3: degraded field session -> one copy

    @Test fun a_degraded_field_session_gets_one_consolidated_clipboard_copy() {
        for (live in listOf(true, false)) {
            assertEquals(
                "hasLiveInputTarget=$live",
                FinalDeliveryPlan(inject = null, copyWholeToClipboard = true),
                FinalDeliveryPolicy.decide(
                    isTextFieldSession = true,
                    degradedToClipboard = true,
                    hasLiveInputTarget = live,
                    transcriptBlank = false,
                ),
            )
        }
    }

    // ---------------------------------------------- row 4: preview session, live target at stop

    @Test fun a_preview_session_with_a_live_target_copies_and_injects_at_the_focus() {
        // Targeting the finalize-time focus is BY DESIGN for non-field sessions (the
        // capture-video-then-tap-into-prompt flow); degraded is a field-session concept only.
        for (degraded in listOf(true, false)) {
            assertEquals(
                "degraded=$degraded",
                FinalDeliveryPlan(inject = InjectTarget.FINALIZE_FOCUS, copyWholeToClipboard = true),
                FinalDeliveryPolicy.decide(
                    isTextFieldSession = false,
                    degradedToClipboard = degraded,
                    hasLiveInputTarget = true,
                    transcriptBlank = false,
                ),
            )
        }
    }

    // ---------------------------------------------- row 5: preview session, no target -> copy only

    @Test fun a_preview_session_with_no_target_copies_to_clipboard_only() {
        for (degraded in listOf(true, false)) {
            assertEquals(
                "degraded=$degraded",
                FinalDeliveryPlan(inject = null, copyWholeToClipboard = true),
                FinalDeliveryPolicy.decide(
                    isTextFieldSession = false,
                    degradedToClipboard = degraded,
                    hasLiveInputTarget = false,
                    transcriptBlank = false,
                ),
            )
        }
    }
}
