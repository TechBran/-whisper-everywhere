package com.whispereverywhere.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The re-read key both chooser surfaces hang their npu gate on (4.0, Q7b fix round, I1).
 *
 * This is the half of the freshness mechanism that can be **executed**. The other half — the two
 * `produceState(initialValue = false, key1 = installGeneration)` producers and
 * `PreferencesManager.notifyModelInstalled()` bumping this object — needs Compose and a `Context`
 * respectively, so it is pinned as source by `ChooserSteerWiringPinTest`. Neither half is worth
 * much alone: a key that never changes makes the producers decoration, and producers that ignore
 * the key make the signal decoration.
 *
 * **Every assertion here is about a DELTA, never an absolute.** This is process-global state and
 * JUnit gives no ordering guarantee, so a test that assumed `generation.value == 0` would pass or
 * fail depending on which other test ran first — the exact flakiness that teaches people to delete
 * tests.
 */
class ModelInstallSignalTest {

    @Test fun a_bump_changes_the_key() {
        val before = ModelInstallSignal.generation.value
        ModelInstallSignal.bump()
        assertNotEquals(
            "an install did not change the key, so no keyed producer would re-read",
            before,
            ModelInstallSignal.generation.value,
        )
        assertEquals(before + 1, ModelInstallSignal.generation.value)
    }

    @Test fun installing_the_SAME_tier_twice_still_changes_the_key_both_times() {
        // The reason this is a counter and not a Boolean or the installed id. A StateFlow of
        // either conflates a repeat — re-importing the npu pair, or replacing a corrupt file with
        // a good one — into no emission at all, which is precisely the note
        // WhisperModelManager.verifyDest already carries about selectedModelId. Compose compares
        // keys by value, so a conflated key is a producer that never re-runs.
        val seen = mutableListOf<Int>()
        repeat(5) {
            ModelInstallSignal.bump()
            seen += ModelInstallSignal.generation.value
        }
        assertEquals("five installs produced fewer than five distinct keys", 5, seen.toSet().size)
        assertTrue("the key must be strictly increasing", seen.zipWithNext().all { it.first < it.second })
    }

    @Test fun the_key_is_never_read_for_its_magnitude_only_for_change() {
        // Guards the contract rather than the number: nothing may infer "how many models are
        // installed" from this. It counts install EVENTS, including repeats of one tier, so the
        // two quantities are not the same and were never meant to be.
        val before = ModelInstallSignal.generation.value
        ModelInstallSignal.bump()
        ModelInstallSignal.bump()
        assertEquals(
            "two installs of anything advance the key by two",
            before + 2,
            ModelInstallSignal.generation.value,
        )
    }
}
