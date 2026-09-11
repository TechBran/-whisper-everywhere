package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load-time canary — RULING ASSUMED (R1): the ONLY guard against the FEAT_SME silent
 * miscompute (sherpa-onnx #3845: EMPTY text for the whole stream on SM8850 + ORT 1.27.0;
 * #3791: `"MY WOMAN"` for a five-word clip on an M4). The verdict rule is
 * `GpuCanaryPolicy.canaryPasses`, unchanged: it already fails empty, fails garbage (fewer than
 * 4 of 5 positions), fails a runaway, and passes the measured `ONE TWO THREE FOUR FIVE`
 * (rung 3 §4: exact in 8 of 8 runs).
 */
class PreviewCanaryTest {

    /** 2.560 s of "audio" — the canary clip's length (40,960 frames, rung 1 §1.6). Values are irrelevant to the fake. */
    private val clip = FloatArray(40_960)

    @Test fun theMeasuredTextPasses() {
        val rec = ScriptedRecognizer(listOf("ONE", "ONE TWO THREE", "ONE TWO THREE FOUR", "ONE TWO THREE FOUR FIVE"))
        val v = PreviewCanary.run(rec, clip)
        assertTrue(v is CanaryVerdict.Pass)
        assertEquals("pass", v.code)
        assertEquals(23, (v as CanaryVerdict.Pass).outLen)
    }

    @Test fun emptyTextIsTheSmeSignatureAndFails() {
        val rec = ScriptedRecognizer(listOf(""))
        val v = PreviewCanary.run(rec, clip)
        assertTrue(v is CanaryVerdict.Fail)
        assertEquals("fail", v.code)
        assertEquals(0, (v as CanaryVerdict.Fail).outLen)
    }

    @Test fun garbageFails() {
        assertTrue(PreviewCanary.run(ScriptedRecognizer(listOf("MY WOMAN")), clip) is CanaryVerdict.Fail)
    }

    @Test fun oneDroppedDigitIsOrdinaryAndPasses() {
        // GpuCanaryPolicy.MIN_MATCHES = 4 — the tolerance the GPU canary was written with.
        assertTrue(PreviewCanary.run(ScriptedRecognizer(listOf("ONE TWO THREE FOUR")), clip) is CanaryVerdict.Pass)
    }

    @Test fun theClipIsFedInAppSizedChunksThenPaddedThenFinishedThenDrained() {
        val rec = ScriptedRecognizer(listOf("ONE TWO THREE FOUR FIVE"))
        PreviewCanary.run(rec, clip)
        val s = rec.streams.single()
        // 40,960 samples = 80 chunks of 512, then ONE pad of 8,000 zeros.
        assertEquals(81, s.fed.size)
        assertTrue(s.fed.dropLast(1).all { it == 512 })
        assertEquals(8_000, s.fed.last())
        // ORDER, not just occurrence: the pad must reach the extractor BEFORE input closes
        // (SherpaProbe.kt:295-296). fedAtFinish = 81 means all 80 chunks AND the pad were in.
        assertEquals("inputFinished after the pad", 81, s.fedAtFinish)
        // 48,960 samples: the first decode at 7,200, then every 5,120 → 1 + (48,960 − 7,200) / 5,120 = 9 decodes.
        assertEquals(9, s.decodes)
        assertTrue("the throwaway stream is released", s.released)
        assertFalse("the recognizer itself is NOT released by the canary", rec.released)
    }

    @Test fun aMissingClipIsNoVerdict() {
        val rec = ScriptedRecognizer(listOf("ONE TWO THREE FOUR FIVE"))
        assertEquals(CanaryVerdict.NoClip, PreviewCanary.run(rec, null))
        assertEquals(CanaryVerdict.NoClip, PreviewCanary.run(rec, FloatArray(0)))
        assertEquals("none", CanaryVerdict.NoClip.code)
        assertTrue("no stream is opened for no clip", rec.streams.isEmpty())
    }

    @Test fun theStreamIsReleasedEvenWhenDecodeThrows() {
        val rec = ScriptedRecognizer(listOf("ONE"), failDecodesFrom = 2)
        val thrown = runCatching { PreviewCanary.run(rec, clip) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(rec.streams.single().released)
    }

    @Test fun theVerdictRuleIsTheGpuCanarys() {
        assertTrue(PreviewCanary.passes("ONE TWO THREE FOUR FIVE"))
        assertTrue(PreviewCanary.passes("one two three four five"))
        assertFalse(PreviewCanary.passes(""))
        assertFalse(PreviewCanary.passes("   "))
        assertFalse(PreviewCanary.passes("MY WOMAN"))
    }
}
