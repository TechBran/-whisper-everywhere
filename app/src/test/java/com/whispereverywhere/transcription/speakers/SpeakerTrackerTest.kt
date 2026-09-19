package com.whispereverywhere.transcription.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * [SpeakerTracker] — every rule sessions 2 and 4 of `docs/measurements/2026-09-18-speaker-spike.md`
 * settled, on vectors whose similarities are arithmetic rather than measured (4.10 Task 2).
 *
 * ### Why synthetic vectors, and why THREE fixtures rather than one
 *
 * The bundled NeMo TitaNet-small emits 192 floats — `output_dim = 192` in the shipped graph's own
 * annotation, re-derived from the asset bytes by the adapter's pin test. Nothing in this class
 * cares: every decision the tracker makes is a function of ONE number per known speaker — the best
 * cosine against its recent fingerprints — so the fixtures are chosen to make that number exact
 * arithmetic. A test written with plausible-looking 192-float arrays would assert the same branches
 * while hiding which similarity it was actually exercising, and "0.5-ish" is precisely the value
 * the hysteresis band exists to treat differently from 0.52.
 *
 *  - **[unit]** — a unit vector at an angle on a circle, where `cos(a, b)` is exactly `cos(a - b)`.
 *    Every band, every duration rule and the latch are written in degrees.
 *  - **[tri]** — a sliding window of three axes, `e(k) + e(k+1) + e(k+2)`. Consecutive vectors sit
 *    at 2/3 and vectors three apart at 0, which is what lets a speaker's recent set WALK — and
 *    lets a probe reach the fingerprint that has just fallen out of it. 2-D cannot do that: on a
 *    circle everything eventually comes back round.
 *  - **[cone]** — five vectors at 75° from a shared axis, 60° apart around it. Their pairwise
 *    similarity is 0.533 (so they chain into one speaker) while each is only 0.259 from the axis
 *    itself and their centroid is 0.801 from it. That gap is the ONLY geometry in which the merge
 *    rule can fire, and the reason is worth stating: a speaker whose fingerprints are all alike has
 *    a centroid no further from a stranger than its members are, so a stranger that was far enough
 *    to open a speaker is still far from the centroid. It takes a speaker spread across a
 *    conversation for the merge to have anything to say.
 *
 * ### The angles the [unit] tests are built on, once
 *
 * With `T_SAME = 0.50` and `T_NEW = 0.30`:
 *
 * | angle | cosine | band |
 * |---|---|---|
 * | 0-60° | 1.000-0.500 | the SAME speaker (the fingerprint is learned) |
 * | 60.1-72.5° | 0.498-0.301 | the hysteresis band (nothing is learned) |
 * | 73°+ | 0.292 and below | a NEW speaker |
 *
 * ### What is NOT tested here, and why
 *
 * The three dumped sessions' real fingerprints are not replayed: they are CAM++ vectors, they live
 * outside the repo (`filesDir/speaker-spike`, pulled to the PC), and 512-float vectors cannot be
 * fed to a 192-float model's tracker anyway. What the repo can hold instead is the SETTLEMENT —
 * [theConstantsAreTheONESTheMeasurementDocSettled] pins all eight numbers with the doc named as
 * their source, so a future round that nudges one is making a deliberate edit against a
 * measurement rather than drifting. Session 4 is the round that split the one duration gate into
 * three, and it is named there too.
 */
class SpeakerTrackerTest {

    // ------------------------------------------------------------------ fixtures

    /** A unit vector at [deg] on the unit circle: `cos(unit(a), unit(b))` is exactly `cos(a - b)`. */
    private fun unit(deg: Double): FloatArray {
        val r = Math.toRadians(deg)
        return floatArrayOf(cos(r).toFloat(), sin(r).toFloat())
    }

    /** The same circle, embedded in the model's real 192 dimensions. */
    private fun unit192(deg: Double): FloatArray {
        val r = Math.toRadians(deg)
        return FloatArray(192).also { it[0] = cos(r).toFloat(); it[1] = sin(r).toFloat() }
    }

    /**
     * A sliding three-axis window: `e(k) + e(k+1) + e(k+2)`, normalised by the tracker.
     * `cos(tri(k), tri(k+1)) = 2/3`, `cos(tri(k), tri(k+2)) = 1/3`, and 0 from three apart.
     */
    private fun tri(k: Int, dim: Int = 8): FloatArray =
        FloatArray(dim).also { for (j in k..k + 2) it[j] = 1f }

    /** One axis, alone. `cos(axis(0), tri(0)) = 1/√3 = 0.577`; `cos(axis(0), tri(k>0)) = 0`. */
    private fun axis(index: Int, dim: Int = 8): FloatArray =
        FloatArray(dim).also { it[index] = 1f }

    /**
     * One of the five [CONE_PHI] vectors of the merge fixture: at 75° from axis 0, at [phi]
     * around it in the plane of axes 1 and 2. Padded to [CONE_DIM] so the cap test has room.
     */
    private fun cone(phi: Double): FloatArray {
        val t = Math.toRadians(75.0)
        val p = Math.toRadians(phi)
        return FloatArray(CONE_DIM).also {
            it[0] = cos(t).toFloat()
            it[1] = (sin(t) * cos(p)).toFloat()
            it[2] = (sin(t) * sin(p)).toFloat()
        }
    }

    /** The cone's axis — 0.259 from each of its five vectors, 0.801 from their centroid. */
    private fun coneAxis(): FloatArray = axis(0, CONE_DIM)

    /** A segment long enough to open a speaker, update one and earn credit — the top tier. */
    private val longSeg = 2.0f

    /**
     * The MATCH-ONLY tier (session 4): long enough to be recognised, too short to open a speaker,
     * confirm one or teach one. `shortSeg` keeps its name because that is what every test below
     * asks of it — "and it still cannot do the three things".
     */
    private val shortSeg = 1.4f

    /** Under [SpeakerTracker.MIN_MATCH_SECONDS]: it inherits and decides nothing at all. */
    private val tinySeg = 0.8f

    /** One of nine mutually orthogonal vectors, for the cap. */
    private fun basis(index: Int, dim: Int = 9): FloatArray =
        FloatArray(dim).also { it[index] = 1f }

    /** Two qualifying segments of the same voice: what it takes to CONFIRM a speaker. */
    private fun SpeakerTracker.confirm(deg: Double): Int {
        assign(unit(deg), longSeg)
        return assign(unit(deg), longSeg)
    }

    /** One CONFIRMED, widely spread speaker, plus one singleton that will be merged into it. */
    private fun mergeFixture(): SpeakerTracker {
        val tracker = SpeakerTracker()
        for (phi in CONE_PHI) assertEquals("the cone chains into ONE speaker", 1, tracker.assign(cone(phi), longSeg))
        assertEquals("…which is confirmed", 1, tracker.confirmedCount)
        assertEquals("…and holds all five fingerprints", 1, tracker.speakerCount)
        assertEquals("the axis is 0.259 from every one of them, so it OPENS", 2, tracker.assign(coneAxis(), longSeg))
        assertEquals(0.259f, tracker.lastBestSimilarity, 0.002f)
        return tracker
    }

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

    // ------------------------------------------------------------------ the MAXIMUM over recent K

    @Test fun aMatchIsTheBESTOfASpeakersRecentFingerprintsAndNotTheirAverage() {
        // THE change that made session 2's numbers work, and the assertion that separates this
        // implementation from the mean-centroid one it replaced.
        //
        // Speaker 1 holds 0°; speaker 2 opens at 80° (0.174 against speaker 1 — below T_NEW). The
        // probe at 78° is 0.208 against speaker 1's only fingerprint and 0.999 against speaker 2's.
        // A MAXIMUM answers speaker 2 on the strength of that 0.999; a mean of a one-vector set
        // would answer the same thing for the wrong reason, which is why the similarity itself is
        // asserted rather than only the id.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(2, tracker.assign(unit(80.0), longSeg))
        assertEquals(2, tracker.assign(unit(78.0), longSeg))
        assertEquals("the number it decided on is the BEST one, not an average", 0.999f, tracker.lastBestSimilarity, 0.002f)
        assertEquals("and nobody new was invented", 2, tracker.speakerCount)
    }

    @Test fun aVoiceThatDRIFTSStaysOneSpeakerBecauseTheRecentSetFollowsIt() {
        // Ten steps of 12°, each a confident match on the step before it (cos 12° = 0.978). The
        // recent set ends up holding 72°..120° while the original 0° has long fallen out of it
        // (RECENT_K is 5). The probe at 130° is 0.985 against the newest fingerprint and -0.643
        // against where the voice started — one speaker, all the way, which the mean-centroid
        // version also managed but only because the EMA happened to lag by the right amount.
        val tracker = SpeakerTracker()
        tracker.assign(unit(0.0), longSeg)
        for (k in 1..10) {
            assertEquals("the drift itself must stay one speaker", 1, tracker.assign(unit(12.0 * k), longSeg))
        }
        assertEquals(1, tracker.speakerCount)
        assertEquals(1, tracker.assign(unit(130.0), longSeg))
        assertEquals(0.985f, tracker.lastBestSimilarity, 0.002f)
        assertEquals("still one voice", 1, tracker.speakerCount)
    }

    @Test fun theRecentSetHoldsFIVEFingerprintsAndTheSIXTHPushesTheOldestOut() {
        // The window is what bounds how long a voice's past speaks for it, and it is asserted from
        // both sides with the [tri] walk: each step is 2/3 against the one before it, so the whole
        // chain lands in ONE speaker, and `axis(0)` is 0.577 against tri(0) and 0 against every
        // later one — a probe that can only be answered by the fingerprint that opened the speaker.
        val five = SpeakerTracker()
        five.assign(tri(0), longSeg)
        for (k in 1..4) assertEquals(1, five.assign(tri(k), longSeg))
        assertEquals("five fingerprints, the oldest still among them", 1, five.assign(axis(0), longSeg))
        assertEquals(0.577f, five.lastBestSimilarity, 0.002f)
        assertEquals(1, five.speakerCount)

        val six = SpeakerTracker()
        six.assign(tri(0), longSeg)
        for (k in 1..5) assertEquals(1, six.assign(tri(k), longSeg))
        assertEquals("the sixth push evicted tri(0), and nothing else can answer", 2, six.assign(axis(0), longSeg))
        assertEquals(0f, six.lastBestSimilarity, 0.002f)
    }

    // ------------------------------------------------------------------ the band in the middle

    @Test fun aSegmentInsideTheHysteresisBandNeverFlipsTheLabelOnItsOwn() {
        // cos(66°) = 0.407, which is >= tNew (0.30) and < tSame (0.50): the one region where the
        // tracker is asked to decide and refuses. It answers the CURRENT speaker — not the nearest,
        // not a new one — because a borderline segment that opened a speaker would cascade: every
        // later segment of the real voice would then be borderline against two speakers.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(1, tracker.assign(unit(66.0), longSeg))
        assertEquals("no speaker opened from the band", 1, tracker.speakerCount)
        assertEquals(0.407f, tracker.lastBestSimilarity, 0.002f)
    }

    @Test fun aSegmentInTheBandDoesNotJoinTheRecentSetItWasAttributedTo() {
        // The quiet half of the rule above, and the one that needs a probe to be visible at all.
        // Attributing a borderline segment to speaker 1 is a label decision; letting its
        // fingerprint into speaker 1's recent set is how one wrong label becomes a wrong speaker.
        //
        // After the band segment at 66° the set must still hold only 0°, where 100° is cos -0.174
        // — below tNew, a NEW speaker. Had the band segment joined the set, 100° would be 0.829
        // against it: the SAME speaker, and the split would never have happened.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals("the band segment lands on the current speaker", 1, tracker.assign(unit(66.0), longSeg))
        assertEquals("…and never entered its recent set", 2, tracker.assign(unit(100.0), longSeg))
        assertEquals(2, tracker.speakerCount)
    }

    @Test fun theSameEdgeIsINCLUSIVEAndTheNewEdgeIsEXCLUSIVE() {
        // Both edges, to a thousandth, and observed through what they LEARN rather than what they
        // answer: inside the band and at `>= tSame` the tracker returns the same id, so the id
        // alone cannot tell the two apart. The 100° probe can — it matches a set that took the
        // segment in (0.829 against 60°) and opens a speaker against one that did not (-0.174
        // against 0°).
        val at = SpeakerTracker()
        at.assign(unit(0.0), longSeg)
        assertEquals(1, at.assign(unit(60.0), longSeg))
        assertEquals("cos 60° is T_SAME to the last bit of a Float", 0.5f, at.lastBestSimilarity, 0f)
        assertEquals("…and `>=` means it was LEARNED", 1, at.assign(unit(100.0), longSeg))
        assertEquals(1, at.speakerCount)

        val justAbove = SpeakerTracker()
        justAbove.assign(unit(0.0), longSeg)
        assertEquals(1, justAbove.assign(unit(60.1), longSeg))
        assertEquals(0.498f, justAbove.lastBestSimilarity, 0.002f)
        assertEquals("a tenth of a degree past the edge learns NOTHING", 2, justAbove.assign(unit(100.0), longSeg))

        val justInside = SpeakerTracker()
        justInside.assign(unit(0.0), longSeg)
        assertEquals("0.3007 is still inside the band", 1, justInside.assign(unit(72.5), longSeg))
        assertEquals(1, justInside.speakerCount)

        val outside = SpeakerTracker()
        outside.assign(unit(0.0), longSeg)
        assertEquals("0.2924 is below T_NEW and opens a speaker", 2, outside.assign(unit(73.0), longSeg))
    }

    // -------------------------------------------- the THREE graded duration gates (session 4)

    @Test fun aMATCHONLYSegmentTakesTheSpeakerItRECOGNISESWithoutCONFIRMINGHim() {
        // THE 20:39 FIX, in one test. Session 2's single 2.0 s gate made this segment inherit
        // whoever spoke last; eleven of fifteen segments in that dump were here, on "clearly
        // distinct voices", and the session produced no labels at all.
        //
        // Speaker 1 holds 0°, speaker 2 holds 90°, and speaker 1 spoke last — so "inherit" and
        // "recognise" give DIFFERENT answers and the assertion is about which rule ran. The
        // 1.2 s probe at 53° is 0.799 against speaker 2 and 0.602 against speaker 1.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(2, tracker.assign(unit(90.0), longSeg))
        assertEquals("speaker 1 holds the floor going in", 1, tracker.assign(unit(0.0), longSeg))
        assertEquals("one confirmed speaker so far", 1, tracker.confirmedCount)

        assertEquals("1.2 s is enough to be RECOGNISED", 2, tracker.assign(unit(53.0), 1.2f))
        assertEquals(0.799f, tracker.lastBestSimilarity, 0.002f)
        assertEquals("…and not enough to CONFIRM: speaker 2 still has one qualifying segment", 1, tracker.confirmedCount)
        assertFalse("so the panel still shows nothing", tracker.secondSpeakerConfirmed)
        assertEquals("and it opened nobody", 2, tracker.speakerCount)

        // 1.6 s of the same voice clears MIN_OPEN_SECONDS, and THAT one counts.
        assertEquals(2, tracker.assign(unit(90.0), 1.6f))
        assertEquals("the second qualifying segment confirms speaker 2", 2, tracker.confirmedCount)
        assertTrue(tracker.secondSpeakerConfirmed)
    }

    @Test fun aMATCHONLYSegmentThatRecognisesNobodyInheritsRatherThanOpening() {
        // The other half of the tier: below T_SAME there is no band and no new speaker, only the
        // speaker who already had the floor. 1.2 s at 90° is 0.0 against the only known voice —
        // far enough to open one at 1.6 s, and not allowed to here.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(1, tracker.assign(unit(90.0), 1.2f))
        assertEquals("a segment this short may never claim a person", 1, tracker.speakerCount)
    }

    @Test fun underTheMATCHFloorItInheritsEvenFromAPerfectMatch() {
        // MIN_MATCH_SECONDS is itself a gate, and this is the case that fails without it: the
        // 0.8 s segment IS speaker 1's voice exactly, and it is still labelled 2 — because
        // speaker 2 has the floor and 0.8 s of audio is a vector with no speaker in it.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(2, tracker.assign(unit(90.0), longSeg))
        assertEquals("it inherits the floor, it does not recognise", 2, tracker.assign(unit(0.0), tinySeg))
    }

    @Test fun aSegmentAtTheMATCHFloorRecognisesAndOneJustUnderItCannot() {
        // The same boundary argument one tier down, and the lowest gate's `>=` is only pinned
        // here: every other duration any test passes to [SpeakerTracker.assign] sits clear of
        // 1.0 s on one side or the other, so `>` would go unnoticed everywhere else.
        //
        // Speaker 2 holds the floor and the probe IS speaker 1's voice exactly, so the two rules
        // give different answers: recognised is 1, inherited is 2.
        val at = SpeakerTracker()
        assertEquals(1, at.assign(unit(0.0), longSeg))
        assertEquals(2, at.assign(unit(90.0), longSeg))
        assertEquals(
            "exactly MIN_MATCH_SECONDS is recognised",
            1,
            at.assign(unit(0.0), SpeakerTracker.MIN_MATCH_SECONDS),
        )
        assertEquals("…and recognising is the whole of what this tier may do", 2, at.speakerCount)

        val under = SpeakerTracker()
        assertEquals(1, under.assign(unit(0.0), longSeg))
        assertEquals(2, under.assign(unit(90.0), longSeg))
        assertEquals(
            "a hundredth of a second under it, the same perfect match inherits",
            2,
            under.assign(unit(0.0), SpeakerTracker.MIN_MATCH_SECONDS - 0.01f),
        )
    }

    @Test fun aSegmentAtTheOPENFloorOpensASpeakerAndOneJustUnderItCannot() {
        // The boundary of the middle gate, both sides, AT the constant — because `>=` is the
        // whole of session 4's answer to the 20:39 dump (eleven of fifteen segments between 1 and
        // 2 s), and a floor written `>` spends exactly the boundary case and nothing else. A
        // nearby value like 1.6 s asserts the tier and leaves the comparator free; the duration
        // is therefore READ off [SpeakerTracker], so a round that moves the floor moves the edge
        // this test stands on with it.
        val opens = SpeakerTracker()
        assertEquals(1, opens.assign(unit(0.0), longSeg))
        assertEquals(
            "a stranger at exactly MIN_OPEN_SECONDS opens a speaker",
            2,
            opens.assign(unit(90.0), SpeakerTracker.MIN_OPEN_SECONDS),
        )
        assertEquals(2, opens.speakerCount)

        val cannot = SpeakerTracker()
        assertEquals(1, cannot.assign(unit(0.0), longSeg))
        assertEquals(1, cannot.assign(unit(90.0), SpeakerTracker.MIN_OPEN_SECONDS - 0.01f))
        assertEquals("…and a hundredth of a second under it cannot", 1, cannot.speakerCount)
    }

    @Test fun aSegmentUnderTheUPDATEFloorNeverJoinsTheRecentSetAndOneAtItDoes() {
        // THE THIRD GATE, asserted from both sides on the same geometry — and the only way to see
        // a recent set from outside is to probe it.
        //
        // Speaker 1 opens at 0°. A 50° segment matches it (0.643) and is labelled 1 either way.
        // The probe at 100° is -0.174 against 0° (below T_NEW: it OPENS) and 0.643 against 50°
        // (above T_SAME: it MATCHES). So the probe's answer is a direct reading of whether the
        // 50° fingerprint was learned.
        val short = SpeakerTracker()
        assertEquals(1, short.assign(unit(0.0), longSeg))
        assertEquals(1, short.assign(unit(50.0), 1.6f))
        assertEquals("1.6 s earned the label and the confirmation…", 1, short.confirmedCount)
        assertEquals("…but taught speaker 1 nothing", 2, short.assign(unit(100.0), longSeg))

        val long = SpeakerTracker()
        assertEquals(1, long.assign(unit(0.0), longSeg))
        assertEquals(1, long.assign(unit(50.0), 2.1f))
        assertEquals("2.1 s is learned, so the probe is speaker 1 after all", 1, long.assign(unit(100.0), longSeg))
        assertEquals(1, long.speakerCount)
    }

    @Test fun theFingerprintThatOPENSASpeakerIsAlwaysLearnedEvenBelowTheUpdateFloor() {
        // The one exception to MIN_UPDATE_SECONDS, and it is structural rather than a preference:
        // a speaker with an empty recent set can never be matched by anybody and has no centroid
        // for the merge pass. A 1.6 s opener is therefore defined by a fingerprint it would not
        // have been allowed to ADD — asserted here so the exception is a decision on the record.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(2, tracker.assign(unit(90.0), 1.6f))
        assertEquals("the opener is speaker 2's only fingerprint, and it answers", 2, tracker.assign(unit(90.0), longSeg))
        assertEquals(1f, tracker.lastBestSimilarity, 0.002f)
    }

    @Test fun aShortSegmentFarFromEveryoneTakesTheCurrentSpeakerAndOpensNothing() {
        // 1.4 s, orthogonal to the only known voice — the one input that WOULD open a speaker if
        // duration were not a gate. The owner's accepted limit, in one assertion: "an interruption
        // shorter than two seconds is labelled as the current speaker".
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(1, tracker.assign(unit(90.0), shortSeg))
        assertEquals("a short segment can never open a speaker", 1, tracker.speakerCount)
    }

    @Test fun aShortSegmentNeverJOINSTheSpeakerItInheritsEither() {
        // "never opens a speaker and never UPDATES one" — the second half, which is the half
        // session 1's failure was actually about: a 1.0-1.1 s fingerprint taken over music went
        // into a speaker's state and made later genuine segments of that voice look foreign.
        //
        // The short segment is at 90°, so it inherits speaker 1; if it had joined speaker 1's set,
        // the 90° segment after it would match at 1.0 instead of opening speaker 2.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals("it inherits", 1, tracker.assign(unit(90.0), shortSeg))
        assertEquals("…and taught speaker 1 nothing", 2, tracker.assign(unit(90.0), longSeg))
    }

    @Test fun aShortSegmentDoesNotCountTOWARDAConfirmation() {
        // "CONFIRM_N = 2 qualifying segments", and a short one does not qualify. Speaker 1 opens on
        // a 2 s segment and is then fed three 1.4 s segments of the same voice: still one
        // qualifying segment, still unconfirmed, still no label anywhere.
        val tracker = SpeakerTracker()
        tracker.assign(unit(0.0), longSeg)
        repeat(3) { tracker.assign(unit(0.0), shortSeg) }
        assertEquals(0, tracker.confirmedCount)
        // One 2 s segment of the same voice, and it is confirmed.
        tracker.assign(unit(0.0), longSeg)
        assertEquals(1, tracker.confirmedCount)
    }

    @Test fun theFirstSegmentOfASessionIsUNLABELLEDWhenItIsTooShortToOpenASpeaker() {
        // The rule read strictly, and it REVERSES the first implementation, which opened speaker 1
        // off any usable embedding whatsoever. 0 is the caller's "unlabelled" and the run inherits;
        // inventing speaker 1 from four tenths of a second is exactly the claim the spike showed
        // the model cannot support.
        val tracker = SpeakerTracker()
        assertEquals(0, tracker.assign(unit(0.0), 0.4f))
        assertEquals("and nothing was opened", 0, tracker.speakerCount)
        assertTrue("nor was a similarity measured — there was nobody to measure against", tracker.lastBestSimilarity.isNaN())
        // …and the session still starts normally at the first segment that is long enough.
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
    }

    // ------------------------------------------------------------------ confirmation

    @Test fun aSpeakerIsConfirmedOnItsSecondQualifyingSegmentAndNotItsFirst() {
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals("one qualifying segment is not a person yet", 0, tracker.confirmedCount)
        assertEquals(1, tracker.assign(unit(30.0), longSeg))
        assertEquals(1, tracker.confirmedCount)
    }

    @Test fun aBandAssignmentIsNotEVIDENCEAndNeverConfirmsASpeaker() {
        // The three cases the tracker was unsure about — the band, an over-cap guess, anything
        // under 2 s — are not qualifying segments. Counting an "I don't know" as evidence of a
        // person is how a wrong label becomes a wrong speaker for a whole session.
        val tracker = SpeakerTracker()
        tracker.assign(unit(0.0), longSeg)
        repeat(4) { assertEquals(1, tracker.assign(unit(66.0), longSeg)) }
        assertEquals("four band segments, and still no confirmation", 0, tracker.confirmedCount)
        assertFalse(tracker.secondSpeakerConfirmed)
    }

    // ------------------------------------------------------------------ the latch

    @Test fun theLatchIsTWOCONFIRMEDSpeakersAndNotMerelyTwoSpeakers() {
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertFalse("one speaker is never a confirmation", tracker.secondSpeakerConfirmed)

        assertEquals(2, tracker.assign(unit(90.0), longSeg))
        assertFalse("two speakers, one qualifying segment each: not yet", tracker.secondSpeakerConfirmed)

        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertFalse("speaker 1 is confirmed; speaker 2 is not", tracker.secondSpeakerConfirmed)

        assertEquals(2, tracker.assign(unit(90.0), longSeg))
        assertTrue("two CONFIRMED speakers", tracker.secondSpeakerConfirmed)
        assertEquals(2, tracker.confirmedCount)
    }

    @Test fun theLatchNeverUnflips() {
        // A session that has shown labels cannot take them back: the panel was already rewritten
        // and un-rewriting it would be the only thing on screen that moved backwards.
        val tracker = SpeakerTracker()
        tracker.confirm(0.0)
        tracker.confirm(90.0)
        assertTrue(tracker.secondSpeakerConfirmed)
        repeat(5) { tracker.assign(unit(0.0), longSeg) }
        assertTrue(tracker.secondSpeakerConfirmed)
    }

    // ------------------------------------------------------------------ the merge, and the remap

    @Test fun anUnconfirmedSINGLETONInsideAConfirmedSpeakerIsMergedAtTheEndOfTheChunk() {
        // Session 1's actual failure mode — "spurious speakers are singletons or near-singletons" —
        // and the rule that answers it. The axis vector is 0.259 from every one of speaker 1's five
        // fingerprints, so ONLINE it is a new voice and opening speaker 2 is the right call with
        // the evidence available. At the end of the chunk the question is different: is this whole
        // speaker a different person? Its centroid is 0.801 from speaker 1's, which is well inside
        // T_SAME, and the answer is no.
        val tracker = mergeFixture()
        assertEquals(2, tracker.speakerCount)

        val moved = tracker.endChunk()
        assertEquals("speaker 2 was never a separate person", mapOf(2 to 1), moved)
        assertEquals("…and is no longer live", 1, tracker.speakerCount)
        assertEquals("…and the session's remap says so", mapOf(2 to 1), tracker.remap())
        assertFalse("one voice, however many ids it briefly had, is not two", tracker.secondSpeakerConfirmed)
    }

    @Test fun aMergeMOVESTheFingerprintsSoTheEvidenceIsNotThrownAway() {
        // The segment that proved the merge is the survivor's only evidence about that edge of the
        // voice, so it moves across rather than being dropped. The probe is the same axis vector:
        // if it moved, the similarity is 1.0; if it did not, the best speaker 1 can offer is the
        // 0.259 it scored the first time. The ID is the same either way, which is why the number
        // is what is asserted.
        val tracker = mergeFixture()
        tracker.endChunk()

        assertEquals("the merged fingerprint now speaks for speaker 1", 1, tracker.assign(coneAxis(), longSeg))
        assertEquals(1.0f, tracker.lastBestSimilarity, 0.002f)
        assertEquals("no third speaker", 1, tracker.speakerCount)
    }

    @Test fun aCONFIRMEDSpeakerIsNeverMergedAwayHoweverCloseItIs() {
        // Only unconfirmed speakers are absorbed, which is what keeps the remap one link deep and
        // keeps a label that has been EARNED from evaporating. The same geometry as the merge test
        // — 0.801 between the two centroids — except the axis speaker now holds the floor twice and
        // is confirmed, and nothing moves.
        val tracker = mergeFixture()
        assertEquals("a second axis segment matches speaker 2 at 1.0", 2, tracker.assign(coneAxis(), longSeg))
        assertEquals(2, tracker.confirmedCount)
        assertTrue("…and two confirmed speakers is the latch", tracker.secondSpeakerConfirmed)

        assertEquals("nothing gives way", emptyMap<Int, Int>(), tracker.endChunk())
        assertEquals(2, tracker.speakerCount)
        assertTrue(tracker.remap().isEmpty())
    }

    @Test fun anUnconfirmedSpeakerFARFromEveryConfirmedOneSurvivesTheMergePass() {
        // The control every assertion above needs: the pass is not "absorb every singleton", it is
        // "absorb a singleton that is within T_SAME of a confirmed centroid". Speaker 1 is
        // confirmed on two identical fingerprints at 0°, so its centroid is 0° too; speaker 2 opens
        // at 120° and is -0.5 from it.
        val tracker = SpeakerTracker()
        tracker.assign(unit(0.0), longSeg)
        tracker.assign(unit(0.0), longSeg)
        assertEquals(1, tracker.confirmedCount)
        assertEquals(2, tracker.assign(unit(120.0), longSeg))
        assertEquals(emptyMap<Int, Int>(), tracker.endChunk())
        assertEquals("a genuinely different voice keeps its id", 2, tracker.speakerCount)
        assertTrue(tracker.remap().isEmpty())
    }

    @Test fun nothingMergesUntilSomebodyIsCONFIRMED() {
        // A merge needs an anchor. Two unconfirmed speakers stay two unconfirmed speakers: neither
        // has earned the right to absorb the other, and picking one arbitrarily is how a session's
        // very first paragraph gets the wrong name.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(2, tracker.assign(unit(90.0), longSeg))
        assertEquals(0, tracker.confirmedCount)
        assertEquals(emptyMap<Int, Int>(), tracker.endChunk())
        assertEquals(2, tracker.speakerCount)
    }

    @Test fun anEmptyChunkAndAOneSpeakerSessionBothMergeNothingAndSaySo() {
        val empty = SpeakerTracker()
        assertEquals(emptyMap<Int, Int>(), empty.endChunk())

        val lone = SpeakerTracker()
        lone.confirm(0.0)
        assertEquals("a confirmed speaker alone has nothing to absorb", emptyMap<Int, Int>(), lone.endChunk())
        assertEquals(1, lone.speakerCount)
        assertFalse(lone.secondSpeakerConfirmed)
    }

    @Test fun theCURRENTSpeakerFOLLOWSAMergeSoNothingCanInheritARetiredId() {
        // The band and the short-segment rule both answer `currentSpeaker()`. If the current
        // speaker were merged away and the field left pointing at it, the very next borderline
        // segment would be labelled with an id that no longer belongs to anybody.
        val tracker = mergeFixture()
        assertEquals("the speaker about to be merged is the current one", 2, tracker.currentSpeaker())
        tracker.endChunk()
        assertEquals("…and is now the survivor", 1, tracker.currentSpeaker())
        assertEquals("a short segment inherits the survivor", 1, tracker.assign(cone(30.0), shortSeg))
    }

    @Test fun anIdIsNEVERREUSEDSoARemapCanOnlyPointBackwards() {
        // Ids are slots, issued in order, and a merged slot is retired rather than recycled. If the
        // number were handed to the next new voice, a label already on screen would come to mean a
        // different person — and the remap that was supposed to fix it would point the wrong way.
        val tracker = mergeFixture()
        assertEquals(mapOf(2 to 1), tracker.endChunk())
        assertEquals(1, tracker.speakerCount)

        assertEquals("a genuinely new voice takes 3, not the retired 2", 3, tracker.assign(axis(5, CONE_DIM), longSeg))
        assertEquals(2, tracker.speakerCount)
    }

    @Test fun endChunkReportsONLYThisChunksMergesWhileRemapAccumulatesTheSession() {
        val tracker = mergeFixture()
        assertEquals(mapOf(2 to 1), tracker.endChunk())

        // Chunk 2: a genuinely new voice, nothing to absorb.
        assertEquals(3, tracker.assign(axis(5, CONE_DIM), longSeg))
        assertEquals("a chunk that moved nothing reports nothing", emptyMap<Int, Int>(), tracker.endChunk())
        assertEquals("…and the session does not forget what chunk 1 moved", mapOf(2 to 1), tracker.remap())
    }

    @Test fun theRemapACallerGetsIsACOPYItCannotEditTheTrackerThrough() {
        val tracker = mergeFixture()
        tracker.endChunk()
        val taken = tracker.remap()
        assertEquals(mapOf(2 to 1), taken)
        @Suppress("UNCHECKED_CAST")
        (taken as? MutableMap<Int, Int>)?.clear()
        assertEquals("the tracker still holds its own", mapOf(2 to 1), tracker.remap())
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
        assertEquals(3, tracker.assign(ninth(), longSeg))
        assertEquals(0.287f, tracker.lastBestSimilarity, 0.002f)
        assertEquals("the cap holds", 8, tracker.speakerCount)
    }

    @Test fun anOverCapSegmentTeachesTheSpeakerItWasGuessedOntoNothing() {
        // Past the cap the answer is a guess, and a guess must not become evidence: it neither
        // joins the closest speaker's recent set nor counts toward its confirmation. Otherwise the
        // ninth voice of a session slowly becomes the third one's definition of itself.
        val tracker = SpeakerTracker()
        for (i in 0 until SpeakerTracker.MAX_SPEAKERS) tracker.assign(basis(i), longSeg)
        assertEquals(0, tracker.confirmedCount)
        repeat(3) { assertEquals(3, tracker.assign(ninth(), longSeg)) }
        assertEquals("three over-cap guesses confirmed nobody", 0, tracker.confirmedCount)

        // And speaker 3 never learned the 9th axis. The probe is 0.9999 against `ninth` and 0.270
        // against the bare 3rd axis, so the SIMILARITY is what tells the two states apart — the id
        // is 3 either way.
        assertEquals(3, tracker.assign(nearlyNinth(), longSeg))
        assertEquals("speaker 3 is still just its own axis", 0.270f, tracker.lastBestSimilarity, 0.003f)
    }

    @Test fun aMergeGIVESTheCapItsRoomBack() {
        // The cap counts LIVE speakers, which is what makes absorbing a spurious singleton worth
        // doing: it hands a genuine later voice the slot the singleton was occupying. Eight live
        // speakers, one of them the mergeable singleton; after the pass a ninth voice OPENS.
        val tracker = mergeFixture()
        for (i in 3..8) {
            assertEquals("e$i is orthogonal to everything so far", i, tracker.assign(axis(i, CONE_DIM), longSeg))
        }
        assertEquals("the cap is full", 8, tracker.speakerCount)

        assertEquals(mapOf(2 to 1), tracker.endChunk())
        assertEquals(7, tracker.speakerCount)
        // The room is real: a ninth distinct voice opens a NEW speaker, and takes the next id.
        assertEquals(9, tracker.assign(axis(9, CONE_DIM), longSeg))
        assertEquals(8, tracker.speakerCount)
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
        // The session below runs at the model's real 192 and is then handed a 512-wide vector,
        // which is the width of the CAM++ graph this repo bundled until session 2 replaced it: the
        // exact shape a mid-session swap would take. The tracker compares against the width it
        // already holds, never against a constant.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit192(0.0), longSeg))
        assertEquals(1, tracker.assign(FloatArray(512) { 1f }, longSeg))
        assertEquals(1, tracker.speakerCount)
        assertTrue("and nothing was measured against it", tracker.lastBestSimilarity.isNaN())
    }

    // ------------------------------------------------------------------ the retrospective reseed

    /**
     * A [SpeakerReclusterer.Relabel] built by hand: the clusters this tracker is to become, each
     * given the vectors its recent set should hold. The map is identity unless a test needs
     * otherwise, which is what the real pass produces once its fingerprints have been rewritten
     * into cluster space.
     */
    private fun relabelOf(
        vararg clusters: Pair<Boolean, List<FloatArray>>,
        map: Map<Int, Int> = emptyMap(),
    ): SpeakerReclusterer.Relabel = SpeakerReclusterer.Relabel(
        map = map,
        windowLabels = emptyMap(),
        clusters = clusters.mapIndexed { index, (confirmed, seeds) ->
            SpeakerReclusterer.Cluster(
                id = index + 1,
                confirmed = confirmed,
                totalSec = 10f,
                fingerprints = seeds.size,
                longest = seeds,
            )
        },
        clusterCount = clusters.size,
        confirmedCount = clusters.count { it.first },
    )

    @Test fun aReseedMakesTheTRACKERSSpeakersTheClustersAndTheNextCallFollowSUIT() {
        // Session 6's 03:27 failure in miniature: the tracker has locked onto ONE speaker whose
        // recent set now holds vectors from both voices, so every later segment of either one
        // matches it and the transcript is one run-on paragraph. The retrospective pass says
        // there were two, and this is the call that makes the tracker believe it.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals("the second voice was swallowed", 1, tracker.assign(unit(50.0), longSeg))
        assertEquals(1, tracker.speakerCount)

        tracker.reseed(relabelOf(true to listOf(unit(0.0)), true to listOf(unit(90.0))))

        assertEquals("two speakers, numbered as the clusters were", 2, tracker.speakerCount)
        assertEquals(2, tracker.confirmedCount)
        // And the ids it hands out from here on are IN THE CLUSTER'S id space — which is what
        // keeps the next chunk from contradicting the relabel the panel has just been given.
        assertEquals(2, tracker.assign(unit(88.0), longSeg))
        assertEquals(1, tracker.assign(unit(3.0), longSeg))
    }

    @Test fun aReseedRaisesTheLatchAndCanNeverLowerIt() {
        val tracker = SpeakerTracker()
        assertFalse(tracker.secondSpeakerConfirmed)

        // Two confirmed clusters is the latch, even though the ONLINE pass never confirmed two.
        tracker.reseed(relabelOf(true to listOf(unit(0.0)), true to listOf(unit(90.0))))
        assertTrue(tracker.secondSpeakerConfirmed)

        // And a later pass that finds only one voice does NOT take it back: the panel has already
        // been rewritten with labels and un-rewriting it is the one thing that moves backwards.
        tracker.reseed(relabelOf(true to listOf(unit(0.0))))
        assertEquals(1, tracker.speakerCount)
        assertTrue("the latch never goes back", tracker.secondSpeakerConfirmed)
    }

    @Test fun aReseedClearsTheSessionsMergesBecauseTheIdsHaveBeenRenumbered() {
        // The merge map's keys are ids from BEFORE the renumbering. Carried across, an old key
        // that collides with a new id would relabel a live speaker into somebody else — and the
        // caller has already published those merges chunk by chunk, so nothing is lost.
        val tracker = mergeFixture()
        tracker.endChunk()
        assertEquals(mapOf(2 to 1), tracker.remap())

        tracker.reseed(relabelOf(true to listOf(unit(0.0)), true to listOf(unit(90.0))))

        assertTrue(tracker.remap().isEmpty())
    }

    @Test fun anUnconfirmedClusterStillHasToEarnItsConfirmationTheOnlineWay() {
        // The degenerate answer — nothing cleared the mass bar, so the whole session is ONE
        // unconfirmed speaker. The tracker must not treat that as a person already proven.
        val tracker = SpeakerTracker()
        tracker.reseed(relabelOf(false to listOf(unit(0.0))))
        assertEquals(1, tracker.speakerCount)
        assertEquals(0, tracker.confirmedCount)
        assertFalse(tracker.secondSpeakerConfirmed)
    }

    @Test fun theCURRENTSpeakerFollowsTheMapAndIsUnlabelledWhenThePassNeverSawIt() {
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(2, tracker.assign(unit(90.0), longSeg))
        assertEquals(2, tracker.currentSpeaker())

        // The pass says the voice on the floor is cluster 1.
        tracker.reseed(
            relabelOf(true to listOf(unit(90.0)), true to listOf(unit(0.0)), map = mapOf(2 to 1)),
        )
        assertEquals(1, tracker.currentSpeaker())

        // An id the pass never saw — its windows fell off the cap — leaves the floor UNLABELLED
        // rather than pointing at whoever happens to hold that number now.
        tracker.reseed(relabelOf(true to listOf(unit(90.0)), true to listOf(unit(0.0))))
        assertEquals(0, tracker.currentSpeaker())
    }

    @Test fun aReseedItCannotHonourIsIgnoredEntirelyRatherThanHalfApplied() {
        // A tracker left holding half a reseed would answer ids belonging to neither id space.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(2, tracker.assign(unit(90.0), longSeg))

        // No clusters at all.
        tracker.reseed(relabelOf())
        assertEquals(2, tracker.speakerCount)

        // Ids that are not 1..n in order — the second cluster claims number 3.
        tracker.reseed(
            SpeakerReclusterer.Relabel(
                map = emptyMap(),
                windowLabels = emptyMap(),
                clusters = listOf(
                    SpeakerReclusterer.Cluster(1, true, 10f, 2, listOf(unit(0.0))),
                    SpeakerReclusterer.Cluster(3, true, 10f, 2, listOf(unit(90.0))),
                ),
                clusterCount = 2,
                confirmedCount = 2,
            ),
        )
        assertEquals("nothing moved", 2, tracker.speakerCount)

        // A cluster with no usable seed.
        tracker.reseed(relabelOf(true to listOf(FloatArray(2))))
        assertEquals(2, tracker.speakerCount)
    }

    @Test fun theRecentSetIsTheClustersSeedsAndNothingOlder() {
        // The tracker matches by MAXIMUM cosine over the recent set, so a reseed that ADDED to
        // the old set instead of replacing it would leave the locked-on vectors in place and the
        // correction would not take. RECENT_K seeds go in; the pre-reseed fingerprints do not.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertEquals(1, tracker.assign(unit(40.0), longSeg))

        tracker.reseed(relabelOf(true to listOf(unit(90.0)), true to listOf(unit(180.0))))

        // 0° was speaker 1's own fingerprint a moment ago. It is now 0.0 from cluster 1 (90°) and
        // -1.0 from cluster 2 (180°), so it opens a THIRD speaker instead of matching what the
        // tracker used to know.
        assertEquals(3, tracker.assign(unit(0.0), longSeg))
    }

    // ------------------------------------------------------------------ session boundaries

    @Test fun resetClearsEverySpeakerTheNumberingTheMergesAndTheLatch() {
        val tracker = mergeFixture()
        tracker.endChunk()
        assertEquals(mapOf(2 to 1), tracker.remap())

        tracker.reset()
        assertEquals(0, tracker.speakerCount)
        assertEquals(0, tracker.confirmedCount)
        assertEquals(0, tracker.currentSpeaker())
        assertFalse(tracker.secondSpeakerConfirmed)
        assertTrue("a merge from the last session cannot relabel this one", tracker.remap().isEmpty())
        // Numbering restarts at 1 for whoever speaks first next session — the voice that was
        // speaker 2 has no claim on the number 2.
        assertEquals(1, tracker.assign(unit(90.0), longSeg))
    }

    @Test fun aResetAlsoForgetsTheWIDTHSoTheNextSessionMayRunADifferentModel() {
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        tracker.reset()
        assertEquals("a 192-wide session after a 2-wide one", 1, tracker.assign(unit192(0.0), longSeg))
        assertEquals(1, tracker.speakerCount)
    }

    // ------------------------------------------------------------------ what the spike reads

    @Test fun theBestSimilarityIsPublishedForEveryDecisionThatMEASUREDOne() {
        // This is the column the bands came from, so every band has to publish the number it
        // decided on — including the ones that change nothing.
        val tracker = SpeakerTracker()

        // The FIRST speaker measured nothing: there was nobody to compare against.
        assertEquals(1, tracker.assign(unit(0.0), longSeg))
        assertTrue("no similarity exists for the first voice", tracker.lastBestSimilarity.isNaN())

        // A confident match publishes its similarity (cos 30° = 0.866).
        assertEquals(1, tracker.assign(unit(30.0), longSeg))
        assertEquals(0.866f, tracker.lastBestSimilarity, 0.002f)

        // A new speaker: below tNew against every recent fingerprint, so nothing was matched — but
        // a similarity WAS measured, and it is the one that has to land in the distribution.
        assertEquals(2, tracker.assign(unit(120.0), longSeg))
        assertEquals("the reading that OPENED a speaker is the sharpest input of all", 0f, tracker.lastBestSimilarity, 0.002f)

        // The hysteresis band: 186° is 0.407 against speaker 2's 120° fingerprint.
        val before = tracker.currentSpeaker()
        assertEquals(before, tracker.assign(unit(186.0), longSeg))
        assertEquals(0.407f, tracker.lastBestSimilarity, 0.002f)
    }

    @Test fun aSegmentTooShortToOPENStillPublishesTheSimilarityItMeasured() {
        // The embedder was paid for this vector — MIN_EMBED_SECONDS is 1.0 s, below MIN_OPEN's
        // 1.5 — and the reading is a row in the distribution whatever tier decided the label.
        val tracker = SpeakerTracker()
        tracker.assign(unit(0.0), longSeg)
        assertEquals(1, tracker.assign(unit(30.0), shortSeg))
        assertEquals(0.866f, tracker.lastBestSimilarity, 0.002f)
    }

    @Test fun anUnusableEmbeddingAndAResetBothPublishNOSimilarityRatherThanTheLastOne() {
        // A stale number here would be read as a real measurement of the segment that produced
        // it — the one failure mode a `best=` column cannot survive.
        val tracker = SpeakerTracker()
        tracker.assign(unit(0.0), longSeg)
        tracker.assign(unit(10.0), longSeg)
        assertFalse(tracker.lastBestSimilarity.isNaN())

        tracker.assign(FloatArray(2), longSeg) // all-zero: not normalisable
        assertTrue("an unusable embedding measured nothing", tracker.lastBestSimilarity.isNaN())

        tracker.assign(unit(10.0), longSeg)
        tracker.reset()
        assertTrue("and a new session starts with nothing measured", tracker.lastBestSimilarity.isNaN())
    }

    // ------------------------------------------------------------------ the settlement

    @Test fun theConstantsAreTheONESTheMeasurementDocSettled() {
        // THE SOURCE: docs/measurements/2026-09-18-speaker-spike.md.
        //
        // The BAND, the recent-window, the confirm count and the cap come from **session 2** —
        // "the fingerprint dump, and the offline model comparison": three clips of exactly one,
        // two and three speakers; 132 segments' fingerprints and audio pulled to the PC; five
        // embedding models scored; these are the rules that produced 1/2/3 in simulation on that
        // data, and the band is the CENTRE of the twelve pairs that worked.
        //
        // The THREE DURATION GATES come from **session 4** — "the labels build in the owner's
        // hands", failure mode A. Session 2's single 2.0 s gate was measured on clips whose
        // segments ran 2.4-7 s; the 20:39 dump had eleven of fifteen segments under it, on
        // clearly distinct voices, and produced no labels at all. 1.0 / 1.5 / 2.0 is that one
        // gate split into the three rights it was conflating.
        //
        // The dumped sessions themselves are NOT replayable here — session 2's fingerprints are
        // CAM++'s 512-float vectors and they live outside the repo — so this assertion is the
        // repo's whole memory of the measurement. Every one of these numbers had a DIFFERENT
        // value at some point that day (0.55 / 0.45 / EMA 0.2 / one 2.0 s gate), reasoned from
        // the spec and wrong enough to turn one voice into five speakers, and then wrong enough
        // to turn five turns into one. None of them may move again without a new measurement.
        assertEquals("T_SAME", 0.50f, SpeakerTracker.T_SAME, 0f)
        assertEquals("T_NEW", 0.30f, SpeakerTracker.T_NEW, 0f)
        assertEquals("MIN_MATCH_SECONDS", 1.0f, SpeakerTracker.MIN_MATCH_SECONDS, 0f)
        assertEquals("MIN_OPEN_SECONDS", 1.5f, SpeakerTracker.MIN_OPEN_SECONDS, 0f)
        assertEquals("MIN_UPDATE_SECONDS", 2.0f, SpeakerTracker.MIN_UPDATE_SECONDS, 0f)
        assertEquals("RECENT_K", 5, SpeakerTracker.RECENT_K)
        assertEquals("CONFIRM_N", 2, SpeakerTracker.CONFIRM_N)
        assertEquals("MAX_SPEAKERS", 8, SpeakerTracker.MAX_SPEAKERS)
        // The embed floor is NOT one of the eight, and since session 4 it is no longer a number
        // with nothing behind it: it is MIN_MATCH_SECONDS, because the shortest segment worth
        // paying an embedder for is exactly the shortest one whose answer can be used.
        assertEquals("MIN_EMBED_SECONDS", 1.0f, SpeakerTracker.MIN_EMBED_SECONDS, 0f)
        assertEquals(
            "the embed floor and the match floor are the same number on purpose",
            SpeakerTracker.MIN_MATCH_SECONDS,
            SpeakerTracker.MIN_EMBED_SECONDS,
            0f,
        )
        assertTrue(
            "the gates are graded, never equal and never crossed",
            SpeakerTracker.MIN_MATCH_SECONDS < SpeakerTracker.MIN_OPEN_SECONDS &&
                SpeakerTracker.MIN_OPEN_SECONDS < SpeakerTracker.MIN_UPDATE_SECONDS,
        )

        // …and a default-constructed tracker actually runs on them.
        val tracker = SpeakerTracker()
        assertEquals(SpeakerTracker.T_SAME, tracker.tSame, 0f)
        assertEquals(SpeakerTracker.T_NEW, tracker.tNew, 0f)
        assertEquals(SpeakerTracker.RECENT_K, tracker.recentK)
        assertEquals(SpeakerTracker.CONFIRM_N, tracker.confirmN)
        assertEquals(SpeakerTracker.MAX_SPEAKERS, tracker.maxSpeakers)
    }

    // ------------------------------------------------------------------ shared fixtures' numbers

    /** Mostly the 9th axis, nearest to speaker 3 at 0.287 — just under T_NEW. */
    private fun ninth(): FloatArray = FloatArray(9).also { it[2] = 0.3f; it[8] = 1f }

    /** 0.9999 against [ninth] and 0.270 against the bare 3rd axis: the discriminating probe. */
    private fun nearlyNinth(): FloatArray = FloatArray(9).also { it[2] = 0.28f; it[8] = 1f }

    private companion object {
        /** The five positions of the [cone] fixture, 60° apart — pairwise 0.533, so they chain. */
        val CONE_PHI = listOf(0.0, 60.0, 120.0, 180.0, 240.0)

        /** Room for the cone (axes 0-2) plus seven more orthogonal voices, for the cap test. */
        const val CONE_DIM = 12
    }
}
