package com.whispereverywhere.transcription.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * [SpeakerTracker] — every branch of the three-band rule, on vectors whose similarities are
 * arithmetic rather than measured (4.10 Task 2).
 *
 * ### Why two dimensions
 *
 * CAM++ emits 192 floats; nothing in this class cares. Every decision the tracker makes is a
 * function of ONE number per known speaker — the cosine against its centroid — so the fixtures are
 * unit vectors on a circle, `unit(deg)`, where `cos(a, b) = cos(a - b)` exactly. A test written
 * with plausible-looking 192-float arrays would assert the same branches while hiding which
 * similarity it was actually exercising, and "0.5-ish" is precisely the value the hysteresis band
 * exists to treat differently from 0.56.
 *
 * ### The drift fixture, stated once
 *
 * [theCentroidFollowsAVoiceThatDrifts] feeds ten vectors 6° apart. With `ema = 0.2` and
 * renormalisation the centroid LAGS — it lands at ~38.3° while the last input was at 60° — and the
 * probe at 70° is the whole point of the test: **0.342 against the original vector (below
 * `tNew`, i.e. a NEW speaker) and 0.851 against the drifted centroid (above `tSame`, i.e. the same
 * speaker)**. The same probe against a fresh tracker opens speaker 2, which is the control the
 * assertion needs to mean anything.
 */
class SpeakerTrackerTest {

    /** A unit vector at [deg] on the unit circle: `cos(unit(a), unit(b))` is exactly `cos(a - b)`. */
    private fun unit(deg: Double): FloatArray {
        val r = Math.toRadians(deg)
        return floatArrayOf(cos(r).toFloat(), sin(r).toFloat())
    }

    /** A segment long enough to open a speaker — every branch that is not about duration uses it. */
    private val longSeg = 2.0f

    /** One of eight mutually orthogonal vectors, for the cap. */
    private fun basis(index: Int, dim: Int = 9): FloatArray =
        FloatArray(dim).also { it[index] = 1f }

    // ------------------------------------------------------------------ the two easy ends

    @Test fun theSameVoiceTwiceIsOneSpeaker() {
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals("one voice, one speaker", 1, tracker.speakerCount)
    }

    @Test fun anOrthogonalVoiceOpensASecondSpeaker() {
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(2, tracker.assign(unit(90.0), longSeg))
        assertEquals(2, tracker.speakerCount)
    }

    @Test fun aSpeakerWhoComesBackKeepsTheirNumber() {
        // The owner's sentence: "then when speaker one comes back, says maybe a few words, we want
        // to be able to switch that". Numbering is per session and a return is not a new speaker.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(2, tracker.assign(unit(90.0), longSeg))
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals("and no third speaker was invented", 2, tracker.speakerCount)
        assertEquals(1, tracker.currentSpeaker())
    }

    // ------------------------------------------------------------------ the band in the middle

    @Test fun aSegmentInsideTheHysteresisBandNeverFlipsTheLabelOnItsOwn() {
        // cos(60°) = 0.5, which is >= tNew (0.45) and < tSame (0.55): the one region where the
        // tracker is asked to decide and refuses. It answers the CURRENT speaker — not the nearest,
        // not a new one — because a borderline segment that opened a speaker would cascade: every
        // later segment of the real voice would then be borderline against two centroids.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(1, tracker.assign(unit(60.0), longSeg))
        assertEquals("no speaker opened from the band", 1, tracker.speakerCount)
    }

    @Test fun aSegmentInTheBandDoesNotMoveTheCentroidItWasAttributedTo() {
        // The centroid moves only on a CONFIDENT match (>= tSame). This is the quiet half of the
        // rule above: attributing a borderline segment to speaker 1 is a label decision, and
        // letting it drag speaker 1's centroid 20 % of the way toward a voice we were not sure
        // about is how one wrong label becomes a wrong speaker — every later segment of the real
        // voice then reads as borderline too.
        //
        // The probe is chosen to make the two implementations answer differently. After the band
        // segment at 60° the centroid must still be at 0°, where 66° is cos 0.4067 — below tNew, a
        // NEW speaker. Had the segment dragged the centroid to ~10.9° (0.8 * v0 + 0.2 * v60,
        // renormalised), 66° would be cos 0.572 — above tSame, the SAME speaker.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals("the band segment lands on the current speaker", 1, tracker.assign(unit(60.0), longSeg))
        assertEquals("…and left the centroid where it was", 2, tracker.assign(unit(66.0), longSeg))
        assertEquals(2, tracker.speakerCount)
    }

    // ------------------------------------------------------------------ duration

    @Test fun aShortSegmentFarFromEveryoneTakesTheCurrentSpeakerAndOpensNothing() {
        // 0.8 s: below MIN_EMBED_SECONDS, so in the app it never reaches here at all — but the
        // tracker is pure and must still answer. Orthogonal to the only known voice, which is the
        // one input that WOULD open a speaker if duration were not a gate.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(1, tracker.assign(unit(90.0), 0.8f))
        assertEquals("a short segment can never open a speaker", 1, tracker.speakerCount)
    }

    @Test fun aSegmentJustUnderTheNewSpeakerMinimumStillCannotOpenOne() {
        // The boundary, both sides of it. 1.5 s is the spec's MIN_NEW_SPEAKER_SECONDS: a two-word
        // interjection by a new voice must not open a speaker (§5), so the gate is >=, not >.
        val below = SpeakerTracker()
        below.assign(unit(0.0), longSeg)
        assertEquals(1, below.assign(unit(90.0), SpeakerTracker.MIN_NEW_SPEAKER_SECONDS - 0.01f))
        assertEquals(1, below.speakerCount)

        val at = SpeakerTracker()
        at.assign(unit(0.0), longSeg)
        assertEquals(2, at.assign(unit(90.0), SpeakerTracker.MIN_NEW_SPEAKER_SECONDS))
        assertEquals(2, at.speakerCount)
    }

    @Test fun theFirstSegmentOfASessionOpensSpeakerOneWhateverItsLength() {
        // There is nobody for it to be confused with and nobody to inherit from. The duration gate
        // is about not SPLITTING a session on a brief interjection; it cannot be about refusing to
        // start one, because then a session whose every segment is short would have no speaker and
        // the sink would have nothing to attribute text to.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), 0.4f))
        assertEquals(1, tracker.speakerCount)
    }

    // ------------------------------------------------------------------ the cap

    @Test fun theNinthDistinctVoiceTakesTheClosestExistingSpeaker() {
        val tracker = SpeakerTracker()
        for (i in 0 until SpeakerTracker.MAX_SPEAKERS) {
            assertEquals(i + 1, tracker.assign(basis(i), longSeg))
        }
        assertEquals(8, tracker.speakerCount)
        // Mostly the 9th axis (so, far from everyone: 0.287 against speaker 3, 0 against the rest)
        // but nearest to speaker 3. The cap sends it to the CLOSEST known speaker — spec §3.2 —
        // never to the current one, because past the cap the tracker is a classifier with no
        // "none of the above" answer left.
        val ninth = FloatArray(9).also { it[2] = 0.3f; it[8] = 1f }
        assertEquals(3, tracker.assign(ninth, longSeg))
        assertEquals("the cap holds", 8, tracker.speakerCount)
    }

    // ------------------------------------------------------------------ EMA drift

    @Test fun theCentroidFollowsAVoiceThatDrifts() {
        val tracker = SpeakerTracker()
        tracker.assign(unit(0.0), longSeg)
        for (k in 1..10) {
            assertEquals("the drift itself must stay one speaker", 1, tracker.assign(unit(6.0 * k), longSeg))
        }
        assertEquals(1, tracker.speakerCount)
        // 70° is 0.342 against the ORIGINAL vector — below tNew, i.e. a new speaker — and 0.851
        // against the centroid the ten steps dragged to ~38.3°.
        assertEquals(1, tracker.assign(unit(70.0), longSeg))
        assertEquals("still one voice", 1, tracker.speakerCount)
    }

    @Test fun theSameProbeAgainstAnUndriftedCentroidOpensASecondSpeaker() {
        // The control for the test above. Without it, "70° is speaker 1" is satisfied by a tracker
        // that never opens a second speaker at all.
        val tracker = SpeakerTracker()
        tracker.assign(unit(0.0), longSeg)
        assertEquals(2, tracker.assign(unit(70.0), longSeg))
    }

    // ------------------------------------------------------------------ the latch

    @Test fun theSecondSpeakerIsConfirmedOnlyWhenBothHaveHeldTheFloorForTheMinimum() {
        val tracker = SpeakerTracker()
        tracker.assign(unit(0.0), 2.0f)
        assertFalse("one speaker is never a confirmation", tracker.secondSpeakerConfirmed)

        // A 1.2 s second voice: too short to open a speaker at all, so there is nothing to confirm.
        assertEquals(1, tracker.assign(unit(90.0), 1.2f))
        assertFalse(tracker.secondSpeakerConfirmed)

        // 1.6 s: opens speaker 2, and speaker 1 already holds a 2.0 s segment.
        assertEquals(2, tracker.assign(unit(90.0), 1.6f))
        assertTrue("two speakers, each with a segment >= 1.5 s", tracker.secondSpeakerConfirmed)
    }

    @Test fun aSpeakerWhoseOnlySegmentsAreShortDoesNotConfirmTheLatch() {
        // The rule read strictly: TWO speakers each with one segment >= MIN_NEW_SPEAKER_SECONDS.
        // Speaker 1 here only ever held the floor for 1.1 s, so the panel stays label-free even
        // though the tracker holds two speakers. That is the conservative direction: a label is a
        // claim about who spoke, and one 1.1 s segment is not enough evidence to relabel a whole
        // session's first paragraph.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), 1.1f))
        assertEquals(2, tracker.assign(unit(90.0), 2.0f))
        assertEquals(2, tracker.speakerCount)
        assertFalse(tracker.secondSpeakerConfirmed)
        // One long segment from speaker 1 and the latch flips.
        assertEquals(1, tracker.assign(unit(0.0), 1.6f))
        assertTrue(tracker.secondSpeakerConfirmed)
    }

    @Test fun theLatchNeverUnflips() {
        // A session that has shown labels cannot take them back: the panel was already rewritten
        // and un-rewriting it would be the only thing on screen that moved backwards.
        val tracker = SpeakerTracker()
        tracker.assign(unit(0.0), 2.0f)
        tracker.assign(unit(90.0), 2.0f)
        assertTrue(tracker.secondSpeakerConfirmed)
        repeat(5) { tracker.assign(unit(0.0), 2.0f) }
        assertTrue(tracker.secondSpeakerConfirmed)
    }

    // ------------------------------------------------------------------ unusable input

    @Test fun anUnusableEmbeddingDecidesNothing() {
        // Zero id, never a guess: the embedder answers null on failure, but a vector that is
        // ALL ZEROS or carries a NaN arrives looking like data, and normalising it would divide by
        // zero and label the segment with whatever the arithmetic produced. Nothing known yet, so
        // there is not even a current speaker to inherit — the caller must treat 0 as "unlabelled".
        val tracker = SpeakerTracker()
        assertEquals(0, tracker.assign(FloatArray(0), longSeg))
        assertEquals(0, tracker.assign(floatArrayOf(0f, 0f), longSeg))
        assertEquals(0, tracker.assign(floatArrayOf(Float.NaN, 1f), longSeg))
        assertEquals("and none of them opened a speaker", 0, tracker.speakerCount)
    }

    @Test fun anUnusableEmbeddingInheritsTheCurrentSpeakerOnceThereIsOne() {
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(1, tracker.assign(floatArrayOf(0f, 0f), longSeg))
        assertEquals(1, tracker.speakerCount)
    }

    @Test fun anEmbeddingOfTheWrongWidthIsUnusableRatherThanAnException() {
        // One session, one model, so this cannot happen — and if a future model swap ever makes it
        // happen, the failure must be a missing label and not a crash on the embedder's executor.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(1, tracker.assign(FloatArray(192) { 1f }, longSeg))
        assertEquals(1, tracker.speakerCount)
    }

    // ------------------------------------------------------------------ session boundaries

    @Test fun resetClearsEverySpeakerTheNumberingAndTheLatch() {
        val tracker = SpeakerTracker()
        tracker.assign(unit(0.0), longSeg)
        tracker.assign(unit(90.0), longSeg)
        assertTrue(tracker.secondSpeakerConfirmed)

        tracker.reset()
        assertEquals(0, tracker.speakerCount)
        assertEquals(0, tracker.currentSpeaker())
        assertFalse(tracker.secondSpeakerConfirmed)
        // Numbering restarts at 1 for whoever speaks first next session — the voice that was
        // speaker 2 has no claim on the number 2.
        assertEquals(1, tracker.assign(unit(90.0), longSeg))
    }

    @Test fun theShippedThresholdsAreTheSpecsStartingPointUntilTheDeviceSessionSetsThem() {
        // Spec §3.2: 0.55 / 0.45 for CAM++, "set by the spike (§6), not by this document". Task 4
        // replaces these two numbers from the `best=` distribution the device session logs; this
        // assertion is what makes that a deliberate edit rather than a drift.
        val tracker = SpeakerTracker()
        assertEquals(0.55f, tracker.tSame, 0f)
        assertEquals(0.45f, tracker.tNew, 0f)
        assertEquals(8, tracker.maxSpeakers)
        assertEquals(0.2f, tracker.ema, 0f)
        assertEquals(1.0f, SpeakerTracker.MIN_EMBED_SECONDS, 0f)
        assertEquals(1.5f, SpeakerTracker.MIN_NEW_SPEAKER_SECONDS, 0f)
    }
}
