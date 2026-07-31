package com.whispereverywhere.transcription.batch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant #2 pinned: cloud requires ALL THREE of a selected provider, a stored key, and accepted
 * disclosure v3. The service consults this ONE predicate instead of re-deriving the triad in prose —
 * FloatingBubbleService.resolveTranscriptionEngine is private and does NOT itself read the
 * disclosure flag (that gating lives upstream in provider setup), so batch needs its own gate.
 */
class BatchCloudGateTest {

    @Test fun the_full_triad_is_eligible() {
        assertTrue(BatchCloudGate.cloudEligible("OPENAI", "sk-test", disclosureAccepted = true))
    }

    @Test fun no_selected_provider_is_never_eligible() {
        assertFalse(BatchCloudGate.cloudEligible(null, "sk-test", disclosureAccepted = true))
    }

    @Test fun no_stored_key_is_never_eligible() {
        assertFalse(BatchCloudGate.cloudEligible("OPENAI", null, disclosureAccepted = true))
    }

    @Test fun a_blank_key_is_never_eligible() {
        // decideEngineChoice treats a blank key as "no key"; the batch gate must agree.
        assertFalse(BatchCloudGate.cloudEligible("OPENAI", "   ", disclosureAccepted = true))
    }

    @Test fun without_disclosure_v2_is_never_eligible() {
        // The upgrade case: key stored under C1's future-tense v1 consent, v2 never accepted.
        assertFalse(BatchCloudGate.cloudEligible("OPENAI", "sk-test", disclosureAccepted = false))
    }

    @Test fun the_default_state_is_ineligible() {
        assertFalse(BatchCloudGate.cloudEligible(null, null, disclosureAccepted = false))
    }
}
