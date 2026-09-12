package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.GpuCanaryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The load-time canary — RULING ASSUMED (R1): the ONLY guard against the FEAT_SME silent
 * miscompute (sherpa-onnx #3845: EMPTY text for the whole stream on SM8850 + ORT 1.27.0;
 * #3791: `"MY WOMAN"` for a five-word clip on an M4). The rule fails empty, fails garbage (fewer
 * than 4 of 5 positions), fails a runaway, and passes the measured `ONE TWO THREE FOUR FIVE`
 * (rung 3 §4: exact in 8 of 8 runs).
 *
 * (4.5.0 T2, defect 4) The clip and the rule are the PACK's, because the English digits clip
 * cannot pass for a non-English model and a non-pass reads exactly like the corruption signature
 * this exists to catch. The English rule is `GpuCanaryPolicy`'s values RESTATED — that object's
 * verdict is a persisted whisper-GPU latch and must not gain a second caller who can move it — and
 * the two are held equal here both structurally and behaviourally.
 */
class PreviewCanaryTest {

    /** 2.560 s of "audio" — the canary clip's length (40,960 frames, rung 1 §1.6). Values are irrelevant to the fake. */
    private val clip = FloatArray(40_960)

    /** The pack is the canary's PAD (and, from the per-pack-canary commit, its clip and its rule). */
    private val pack = StreamingPackCatalog.EN

    @Test fun theMeasuredTextPasses() {
        val rec = ScriptedRecognizer(listOf("ONE", "ONE TWO THREE", "ONE TWO THREE FOUR", "ONE TWO THREE FOUR FIVE"))
        val v = PreviewCanary.run(rec, clip, pack)
        assertTrue(v is CanaryVerdict.Pass)
        assertEquals("pass", v.code)
        val pass = v as CanaryVerdict.Pass
        assertEquals(23, pass.outLen)
        // The verdict's own decode count, not the stream's: 1 + (48,960 − 7,200) / 5,120 = 9.
        assertEquals(9, pass.decodes)
    }

    @Test fun emptyTextIsTheSmeSignatureAndFails() {
        val rec = ScriptedRecognizer(listOf(""))
        val v = PreviewCanary.run(rec, clip, pack)
        assertTrue(v is CanaryVerdict.Fail)
        assertEquals("fail", v.code)
        assertEquals(0, (v as CanaryVerdict.Fail).outLen)
    }

    @Test fun garbageFails() {
        assertTrue(PreviewCanary.run(ScriptedRecognizer(listOf("MY WOMAN")), clip, pack) is CanaryVerdict.Fail)
    }

    @Test fun oneDroppedDigitIsOrdinaryAndPasses() {
        // GpuCanaryPolicy.MIN_MATCHES = 4 — the tolerance the GPU canary was written with.
        assertTrue(PreviewCanary.run(ScriptedRecognizer(listOf("ONE TWO THREE FOUR")), clip, pack) is CanaryVerdict.Pass)
    }

    @Test fun theClipIsFedInAppSizedChunksThenPaddedThenFinishedThenDrained() {
        val rec = ScriptedRecognizer(listOf("ONE TWO THREE FOUR FIVE"))
        PreviewCanary.run(rec, clip, pack)
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

    @Test fun thePadIsThePacksOwnAndNotAConstant() {
        // A 640 ms row (ru, id, tr, et, pt: decode_chunk_len 64 / T 77) needs 820 ms of zeros for
        // one more forward pass. The canary must pad what the COMMIT hook pads, or it renders a
        // verdict on a configuration the feature never runs.
        val rec = ScriptedRecognizer(listOf("ONE TWO THREE FOUR FIVE"))
        val sixForty = pack.copy(language = "xx", decodeChunkLen = 64, encoderT = 77)
        PreviewCanary.run(rec, clip, sixForty)
        assertEquals(13_120, rec.streams.single().fed.last())
    }

    @Test fun aMissingClipIsNoVerdict() {
        // The pack HAS a canary; the named asset would not load. That is a build defect, and
        // `StreamingPreviewEngineTest.aMissingClipIsNoVerdictAndTheProcessStaysOff` holds the
        // consequence: the language goes off.
        val rec = ScriptedRecognizer(listOf("ONE TWO THREE FOUR FIVE"))
        assertEquals(CanaryVerdict.NoClip, PreviewCanary.run(rec, null, pack))
        assertEquals(CanaryVerdict.NoClip, PreviewCanary.run(rec, FloatArray(0), pack))
        assertEquals("none", CanaryVerdict.NoClip.code)
        assertTrue("no stream is opened for no clip", rec.streams.isEmpty())
    }

    @Test fun aPackWithNoCanarySOURCEDYetIsNoVerdictAndNeverAFailure() {
        // (4.5.0 T1) A row whose clip has not been sourced is `canary = null`, and the ONLY safe
        // answer for it is no verdict — never Fail. A Fail is a verdict, it switches live words off
        // for that language for the process, and a language cannot be found guilty of a clip
        // nobody has made yet. The alternative the type refuses is worse: a sentinel rule
        // (`expected = emptyList()`) either passes everything (minMatches 0) or fails everything
        // (minMatches 1), and both are lies dressed as verdicts.
        //
        // Review r1 (B1): that answer is `Unscored`, and its being a DIFFERENT object from NoClip
        // is the whole fix — routing it into NoClip gave it NoClip's consequence, which is the same
        // as Fail's, so all six new languages switched themselves off on first warm. The
        // consequence is asserted where consequences live:
        // `StreamingPreviewEngineTest.aRowWithNoCLIPSOURCEDYetARMSUnscoredRatherThanGoingOff`.
        val rec = ScriptedRecognizer(listOf("ONE TWO THREE FOUR FIVE"))
        val unsourced = pack.copy(language = "xx", canary = null)
        assertEquals(CanaryVerdict.Unscored, PreviewCanary.run(rec, clip, unsourced))
        assertNotEquals(
            "Unscored and NoClip must not be the same object: they have different consequences",
            CanaryVerdict.NoClip as CanaryVerdict, CanaryVerdict.Unscored as CanaryVerdict,
        )
        assertEquals("unscored", CanaryVerdict.Unscored.code)
        assertTrue("and no stream is opened for a pack with nothing to score", rec.streams.isEmpty())
        // A null clip does not change the answer either: the row is asked first, so an unsourced
        // row reports Unscored and not the defect verdict — which is exactly the shape the service
        // produces, because `canaryClip` maps a null canary to a null clip.
        assertEquals(CanaryVerdict.Unscored, PreviewCanary.run(rec, null, unsourced))
    }

    @Test fun theEnglishRowStillCarriesItsClipAndItsRuleTogether() {
        // One nullable field, not two: an asset without a rule (or a rule without an asset) is a
        // state nothing in the app could act on, so it is not representable.
        val canary = pack.canary!!
        assertEquals("canary_digits.wav", canary.asset)
        assertEquals(5, canary.rule.expected.size)
    }

    @Test fun theStreamIsReleasedEvenWhenDecodeThrows() {
        val rec = ScriptedRecognizer(listOf("ONE"), failDecodesFrom = 2)
        val thrown = runCatching { PreviewCanary.run(rec, clip, pack) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(rec.streams.single().released)
    }

    @Test fun theEnglishRuleRESTATESTheGpuCanarysValuesRatherThanBorrowingThem() {
        // GpuCanaryPolicy's verdict is a PERSISTED per-(app version, model, device) CPU latch, so
        // it must not acquire a second caller who can move it. The English pack therefore carries
        // its own copy of the values — and these two assertions are what make "a copy" safe: a
        // change on either side is a red test rather than a silent re-scoring of the other.
        assertEquals(GpuCanaryPolicy.EXPECTED_TOKENS, pack.canary!!.rule.expected)
        assertEquals(GpuCanaryPolicy.MIN_MATCHES, pack.canary!!.rule.minMatches)
        assertEquals(20, pack.canary!!.rule.maxTokens)
    }

    @Test fun theTwoRulesAGREEOnEveryShapeTheGpuCanarysOwnTestsPin() {
        // Behavioural equality, not structural: the same texts through both scorers. The battery
        // is GpuCanaryPolicyTest's own cases, including the 20/21-token boundary (which is the
        // only way to check maxTokens against a private constant) and the `12345` decomposition.
        val battery = listOf(
            "ONE TWO THREE FOUR FIVE", "one two three four five", " One two three four five.",
            "1, 2, 3, 4, 5.", "one 2 three 4 five", "12345.", "11111",
            "two three four five", "three four five", "", "   ", "MY WOMAN",
            "шшш ののの ¿¿¿ qwx zzz",
            List(20) { "one" }.joinToString(" "), List(21) { "one" }.joinToString(" "),
        )
        for (text in battery) {
            assertEquals(
                "the previewer's rule and the GPU canary's disagree on: '$text'",
                GpuCanaryPolicy.canaryPasses(text),
                PreviewCanary.passes(text, pack.canary!!.rule),
            )
        }
    }

    @Test fun aPacksOwnRuleIsWhatScoresItsOwnClip() {
        // The route: a pack whose clip says "un deux trois quatre cinq" scores against ITS
        // positions, and the English text that passes above fails there. (No such pack exists in
        // this build — the point is that the verdict is the pack's, not the object's.)
        val french = PreviewCanaryRule(
            expected = listOf(setOf("un"), setOf("deux"), setOf("trois"), setOf("quatre"), setOf("cinq")),
            minMatches = 4,
            maxTokens = 20,
        )
        assertTrue(PreviewCanary.passes("UN DEUX TROIS QUATRE CINQ", french))
        assertFalse(
            "and the English clip's text is not a pass for it — which is exactly why one shared " +
                "clip would have refused every non-English pack as corrupt",
            PreviewCanary.passes("ONE TWO THREE FOUR FIVE", french),
        )
    }
}
