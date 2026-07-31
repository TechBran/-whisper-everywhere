package com.whispereverywhere.data.local

import com.whispereverywhere.data.local.PreferencesManager.Companion.CLOUD_DISCLOSURE_KEY
import com.whispereverywhere.data.local.PreferencesManager.Companion.CLOUD_DISCLOSURE_VERSION
import com.whispereverywhere.data.local.PreferencesManager.Companion.readCloudDisclosureAccepted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consent-version invariant — the release-level gate that a disclosure MEANING change must
 * re-prompt everyone. It was previously defended only by a string literal (the version lived only
 * in the SharedPreferences key suffix), so every gate/decision test that consumes a precomputed
 * `disclosureAccepted: Boolean` structurally could not catch a forgotten version bump or a default
 * flipped to true. This pins the real production read seam ([readCloudDisclosureAccepted], which the
 * live getter now calls) so those two invariants are covered.
 */
class CloudDisclosureConsentTest {

    /** Simulate SharedPreferences.getBoolean over an in-memory store. */
    private fun store(vararg entries: Pair<String, Boolean>): (String, Boolean) -> Boolean {
        val map = entries.toMap()
        return { key, default -> map[key] ?: default }
    }

    @Test fun a_fresh_unset_store_reads_as_not_accepted() {
        // The safe default the entire cloud triad rests on: nothing stored => no consent => no egress.
        assertFalse(readCloudDisclosureAccepted(store()))
    }

    @Test fun acceptance_under_the_current_version_key_is_honoured() {
        assertTrue(readCloudDisclosureAccepted(store(CLOUD_DISCLOSURE_KEY to true)))
    }

    @Test fun acceptance_under_a_prior_version_key_does_NOT_satisfy_the_current_getter() {
        // The whole point of the versioned key: a returning user who accepted v1/v2 must NOT have
        // that stale consent silently authorize a v3-meaning data flow. Reading is version-scoped.
        assertFalse(readCloudDisclosureAccepted(store("cloud_disclosure_accepted_v1" to true)))
        assertFalse(readCloudDisclosureAccepted(store("cloud_disclosure_accepted_v2" to true)))
    }

    @Test fun the_key_carries_the_documented_current_version() {
        // Ties the key literal to the version int, so a meaning edit that bumps one but forgets the
        // other fails here and forces a deliberate re-prompt decision rather than silent stale consent.
        assertEquals("cloud_disclosure_accepted_v$CLOUD_DISCLOSURE_VERSION", CLOUD_DISCLOSURE_KEY)
    }
}
