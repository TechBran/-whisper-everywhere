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
 *
 * 4.3.3 (accessibility-optional-spec §4) adds a fifth input in front of those four:
 *   !serviceEnabled && !blank  -> (null, true)                        — no service, no attempt
 * Every row above is walked with `serviceEnabled = true`, so the with-service table is
 * byte-identical to the W2 table by construction; the service-off rows have their own test.
 */
class FinalDeliveryPolicyTest {

    private val tf = listOf(true, false)

    // ----------------------- row 0 (4.3.3): no accessibility service -> straight to the clipboard

    @Test fun without_the_accessibility_service_every_transcript_goes_straight_to_the_clipboard() {
        // When WhisperAccessibilityService.isEnabled() is false there is nothing to inject INTO —
        // no session-bound node, no finalize-time focus (both are the service's) — so the plan
        // is the ONE clipboard write the CLIPBOARD_ONLY branch already performs, and no injection
        // is attempted. Decided before the field table is consulted, over every combination of
        // the other three inputs: a TEXT_FIELD context cannot exist without the service (the
        // focus listener that sets it is the service's), but the rule does not lean on that.
        for (field in tf) for (degraded in tf) for (live in tf) {
            assertEquals(
                "field=$field degraded=$degraded live=$live",
                FinalDeliveryPlan(inject = null, copyWholeToClipboard = true),
                FinalDeliveryPolicy.decide(
                    serviceEnabled = false,
                    isTextFieldSession = field,
                    degradedToClipboard = degraded,
                    hasLiveInputTarget = live,
                    transcriptBlank = false,
                ),
            )
        }
        // A blank transcript still delivers nothing: the service being off invents no copy.
        for (field in tf) for (degraded in tf) for (live in tf) {
            assertEquals(
                "blank: field=$field degraded=$degraded live=$live",
                FinalDeliveryPlan(inject = null, copyWholeToClipboard = false),
                FinalDeliveryPolicy.decide(
                    serviceEnabled = false,
                    isTextFieldSession = field,
                    degradedToClipboard = degraded,
                    hasLiveInputTarget = live,
                    transcriptBlank = true,
                ),
            )
        }
    }

    @Test fun with_the_service_enabled_the_table_is_the_w2_table_row_for_row() {
        // The whole with-service function against its own spec, in one walk — so a future
        // service-off tweak cannot leak into the enabled rows unnoticed (brief §4: "no new
        // behaviour when the service IS enabled").
        for (field in tf) for (degraded in tf) for (live in tf) for (blank in tf) {
            val expected = when {
                blank -> FinalDeliveryPlan(inject = null, copyWholeToClipboard = false)
                field && !degraded -> FinalDeliveryPlan(inject = InjectTarget.SESSION_BOUND, copyWholeToClipboard = false)
                field -> FinalDeliveryPlan(inject = null, copyWholeToClipboard = true)
                else -> FinalDeliveryPlan(
                    inject = if (live) InjectTarget.FINALIZE_FOCUS else null,
                    copyWholeToClipboard = true,
                )
            }
            assertEquals(
                "field=$field degraded=$degraded live=$live blank=$blank",
                expected,
                FinalDeliveryPolicy.decide(
                    serviceEnabled = true,
                    isTextFieldSession = field,
                    degradedToClipboard = degraded,
                    hasLiveInputTarget = live,
                    transcriptBlank = blank,
                ),
            )
        }
    }

    // ---------------------------------------------- row 1: blank transcript wins over everything

    @Test fun a_blank_transcript_delivers_nothing_no_matter_what() {
        for (field in listOf(true, false))
            for (degraded in listOf(true, false))
                for (live in listOf(true, false)) {
                    assertEquals(
                        "field=$field degraded=$degraded live=$live",
                        FinalDeliveryPlan(inject = null, copyWholeToClipboard = false),
                        FinalDeliveryPolicy.decide(
                            serviceEnabled = true,
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
                    serviceEnabled = true,
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
                    serviceEnabled = true,
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
                    serviceEnabled = true,
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
                    serviceEnabled = true,
                    isTextFieldSession = false,
                    degradedToClipboard = degraded,
                    hasLiveInputTarget = false,
                    transcriptBlank = false,
                ),
            )
        }
    }
}
