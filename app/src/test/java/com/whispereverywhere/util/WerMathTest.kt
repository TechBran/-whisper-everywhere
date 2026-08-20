package com.whispereverywhere.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstream G (3.6.0): token-level WER, and the executable gate Task G4 branches on. Pure
 * because both consumers (the audio_ctx floor sweep, the GPU/CPU A-B) are instrumented tests
 * that the JVM suite cannot run — the arithmetic has to be pinned here or nowhere.
 */
class WerMathTest {

    private val eps = 1e-9

    @Test fun identicalTextScoresZero() {
        assertEquals(0.0, WerMath.wer("one two three", "one two three"), eps)
    }

    @Test fun casingAndPunctuationAreIgnored() {
        // Two decodes of the same audio differ in punctuation constantly; that is not an error.
        assertEquals(0.0, WerMath.wer("Ask not what your country can do.", "ask not what your country can do"), eps)
    }

    @Test fun oneSubstitutionInFiveWordsIsPointTwo() {
        assertEquals(0.2, WerMath.wer("one two three four five", "one two THREEE four five"), eps)
    }

    @Test fun oneDeletionAndOneInsertionEachCountAsOneError() {
        assertEquals(0.25, WerMath.wer("one two three four", "one three four"), eps)
        assertEquals(0.25, WerMath.wer("one two three four", "one two extra three four"), eps)
    }

    @Test fun aCompletelyDifferentHypothesisScoresOne() {
        assertEquals(1.0, WerMath.wer("one two three", "alpha beta gamma"), eps)
    }

    @Test fun anEmptyHypothesisLosesEveryReferenceWord() {
        assertEquals(1.0, WerMath.wer("one two three", "   "), eps)
    }

    @Test fun anEmptyReferenceScoresZeroOnlyWhenTheHypothesisIsEmptyToo() {
        assertEquals(0.0, WerMath.wer("", ""), eps)
        assertEquals(1.0, WerMath.wer("", "unexpected words"), eps)
    }

    @Test fun theGateIsTenPercent() {
        assertEquals(0.10, WerMath.FLOOR_WER_GATE, eps)
    }

    @Test fun floorQualifiesOnlyWhenEveryRecordedWerIsAtOrUnderTheGate() {
        assertTrue(WerMath.floorQualifies(listOf(0.0, 0.05, 0.10)))
        assertFalse("one bad slice disqualifies the floor", WerMath.floorQualifies(listOf(0.0, 0.11)))
        assertFalse("no measurements is not a pass", WerMath.floorQualifies(emptyList()))
    }
}
