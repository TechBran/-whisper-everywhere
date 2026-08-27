package com.whispereverywhere.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wall-cap cut's policy bookkeeping, extracted from FloatingBubbleService's `else if` and
 * pinned (3.7, Workstream D). The branch itself is untouched — only the predicate under it is now
 * a named, tested unit, which matters because 3.7 makes hasPendingSpeech() HONEST for the soft
 * talker who was permanently false under the amplitude segmenter.
 *
 *  - real speech, any session  -> consume the first-cap window and restart the clock
 *  - CLOUD silence             -> also consume: the 4 s window must NEVER re-open on cloud
 *                                 (`cap=4000ms` in a cloud session is the bug signature)
 *  - LOCAL silence             -> re-arm, so a user who pauses to think still gets the 4 s first
 *                                 cut on their first real speech (3.5.0 parity guarantee)
 */
class CapCutBookkeepingTest {

    @Test
    fun realSpeechConsumesTheWindowInEverySession() {
        assertTrue(capCutConsumesWindow(hasPendingSpeech = true, isCloudSession = false))
        assertTrue(capCutConsumesWindow(hasPendingSpeech = true, isCloudSession = true))
    }

    @Test
    fun cloudSilenceStillConsumesTheWindow() {
        // Re-opening the 4 s window on cloud costs an extra billable provider request.
        assertTrue(capCutConsumesWindow(hasPendingSpeech = false, isCloudSession = true))
    }

    @Test
    fun localSilenceReArmsTheFirstCapWindow() {
        assertFalse(capCutConsumesWindow(hasPendingSpeech = false, isCloudSession = false))
    }

    @Test
    fun theRuleIsExhaustiveOverBothInputs() {
        val truthTable = listOf(
            Triple(true, true, true),
            Triple(true, false, true),
            Triple(false, true, true),
            Triple(false, false, false),
        )
        for ((speech, cloud, expected) in truthTable) {
            org.junit.Assert.assertEquals(
                "speech=$speech cloud=$cloud",
                expected,
                capCutConsumesWindow(speech, cloud),
            )
        }
    }
}
