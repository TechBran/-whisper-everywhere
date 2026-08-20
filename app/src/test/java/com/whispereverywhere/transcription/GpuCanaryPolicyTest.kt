package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstream C (3.6.0): the canary match rule that decides whether a multilingual model may
 * use the Adreno OpenCL backend on THIS device. Both documented corruption signatures —
 * garbage tokens and empty output — must FAIL, and so must a repetition runaway.
 */
class GpuCanaryPolicyTest {

    @Test
    fun theExpectedSetIsTheFiveSpokenDigits() {
        assertEquals(
            listOf(
                setOf("one", "1"), setOf("two", "2"), setOf("three", "3"),
                setOf("four", "4"), setOf("five", "5"),
            ),
            GpuCanaryPolicy.EXPECTED_TOKENS,
        )
        assertEquals(4, GpuCanaryPolicy.MIN_MATCHES)
    }

    @Test
    fun aCleanTranscriptionPasses() {
        assertTrue(GpuCanaryPolicy.canaryPasses(" One two three four five."))
    }

    @Test
    fun numeralRenderingsPassToo() {
        // whisper renders spoken digits as NUMERALS constantly (the JNI decodes with
        // no_context=true and suppress_nst=true on a ~1 s clip), and normalize deliberately keeps
        // digits. "1, 2, 3, 4, 5." is a PERFECT transcription of the canary clip — failing it
        // would latch a correct GPU to CPU for the whole app version on a formatting coin-flip.
        assertTrue(GpuCanaryPolicy.canaryPasses("1, 2, 3, 4, 5."))
        assertTrue(GpuCanaryPolicy.canaryPasses("one 2 three 4 five"))   // mixed rendering
    }

    @Test
    fun punctuationAndCasingDoNotMatter() {
        assertTrue(GpuCanaryPolicy.canaryPasses("ONE, TWO, THREE, FOUR, FIVE!"))
    }

    @Test
    fun oneMissedDigitStillPasses_butTwoDoNot() {
        // Whisper legitimately drops a leading digit on a 1 s clip; two misses is a red flag.
        assertTrue(GpuCanaryPolicy.canaryPasses("two three four five"))
        assertFalse(GpuCanaryPolicy.canaryPasses("three four five"))
    }

    @Test
    fun emptyOutputFails() {
        // The ggml-large-v3-turbo-q5_0 GPU signature (the empirical-corruption docblock above GpuPolicy.isGpuSafeModel): empty transcriptions.
        assertFalse(GpuCanaryPolicy.canaryPasses(""))
        assertFalse(GpuCanaryPolicy.canaryPasses("   "))
    }

    @Test
    fun garbageTokensFail() {
        // The ggml-small-q5_1 GPU signature: decodes to garbage.
        assertFalse(GpuCanaryPolicy.canaryPasses("шшш ののの ¿¿¿ qwx zzz"))
    }

    @Test
    fun aRepetitionRunawayFails_evenWithEveryDigitPresent() {
        // Corruption can also surface as a degenerate loop that happens to contain the digits.
        val runaway = "one two three four five " + "five ".repeat(40)
        assertFalse(GpuCanaryPolicy.canaryPasses(runaway))
    }

    @Test
    fun normalizeStripsPunctuationAndLowercases() {
        assertEquals(listOf("one", "two"), GpuCanaryPolicy.normalize("  One, TWO! "))
    }

    @Test
    fun runTogetherNumeralsPassToo() {
        // Whisper renders "one two three four five" as "12345" on this clip.
        assertTrue(GpuCanaryPolicy.canaryPasses("12345."))
    }

    @Test
    fun digitCharWideningCannotGameTheCount() {
        // The digit decomposition is a rendering aid, not a shortcut to the count.
        // "11111" decomposes to only "1", match=1, which is < MIN_MATCHES.
        assertFalse(GpuCanaryPolicy.canaryPasses("11111"))
    }

    @Test
    fun theRunawayCapSitsExactlyAtTwentyTokens() {
        // MAX_TOKENS = 20 is the boundary: 20 passes, 21 fails.
        val twentyTokens = listOf("one", "two", "three", "four", "five") + (6..20).map { "token$it" }
        assertTrue(GpuCanaryPolicy.canaryPasses(twentyTokens.joinToString(" ")))

        val twentyOneTokens = twentyTokens + "token21"
        assertFalse(GpuCanaryPolicy.canaryPasses(twentyOneTokens.joinToString(" ")))
    }
}
