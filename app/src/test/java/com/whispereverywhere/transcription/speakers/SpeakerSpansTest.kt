package com.whispereverywhere.transcription.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 4.10 Task 1: the pure half of the speaker pipeline — the native segment geometry
 * ([com.whispereverywhere.whisper.WhisperNative.lastVadSegments] /
 * `lastWhisperSegments`) turned into FINGERPRINT WINDOWS and `(windowIndex, text)` spans.
 *
 * THE WINDOWS ARE SPIKE SESSION 4's answer to failure mode B — the endpointer cuts on silence,
 * so when nobody pauses it can hand over six to fourteen seconds in one segment, and two voices
 * sharing those seconds would share one fingerprint. A segment of at least `LONG_SEGMENT_SECONDS`
 * with two or more whisper segments in it is cut along whisper's own boundaries, which are the
 * only boundaries in this pipeline placed by something that listened to the words.
 *
 * Session 4 set that floor at 5.0 s because failure mode B was never demonstrated — its one long
 * session turned out to be a single narrator — and the tests below were written about a split
 * that had to be CORRECT and BOUNDED. **The 2026-09-18 late session unbounded it**: the owner's
 * 02:12 dump held 218 windows at a median of 3.0 s, so the 5 s floor cut almost nothing and a new
 * speaker's first sentence kept landing inside the previous speaker's window. At 2.0 s / 1.0 s
 * the split is the ordinary case, and these tests are re-pinned to it: a window is a SENTENCE in
 * nearly every segment, and the "one window" cases below are now the genuinely uncuttable ones —
 * a segment under two seconds, a segment with one sentence, a segment with none.
 *
 * THE TWO TIMELINES ARE THE WHOLE SUBJECT, and mixing them is the defect this class exists to
 * catch. A VAD segment carries FOUR numbers: where it sat in the RAW chunk (what a speaker
 * embedder must fingerprint) and where it sat in the STITCHED buffer whisper actually saw (what
 * whisper's own centisecond timestamps are measured against). Overlap must be computed on the
 * TRIMMED pair; computing it on the original pair looks identical for a chunk whose first segment
 * starts at sample 0 and silently mislabels every chunk that begins with silence.
 *
 * BYTES, NOT CHARACTERS, is the other half. `transcribeRaw` returns raw UTF-8 and the native side
 * reports byte offsets into it, because a Kotlin `Char` index cannot be mapped back to a native
 * offset when a code point spans one to four bytes. Slice the bytes, then decode.
 */
class SpeakerSpansTest {

    private companion object {
        /** 16 kHz: one centisecond is 160 samples. The native side's only unit conversion. */
        const val SPC = 160

        /** `we_vad_filter`'s stitch gap — 100 ms of injected silence between segments. */
        const val GAP = 1600

        /** The app's one sample rate, so a window test can be written in seconds. */
        const val RATE = 16_000
    }

    /**
     * Encodes whisper's per-segment texts the way the native layer hands them over: one
     * concatenated UTF-8 buffer plus each segment's `[byteStart, byteEnd)` into it. Computed
     * rather than hand-counted so a test can use a 4-byte code point without the offsets becoming
     * a puzzle — which is exactly the arithmetic the production code must not get wrong either.
     */
    private fun encode(vararg segs: String): Pair<ByteArray, List<Pair<Int, Int>>> {
        val out = java.io.ByteArrayOutputStream()
        val ranges = segs.map { s ->
            val start = out.size()
            out.write(s.toByteArray(Charsets.UTF_8))
            start to out.size()
        }
        return out.toByteArray() to ranges
    }

    /** `[origStart, origEnd, trimmedStart, trimmedEnd]` * n, flattened the way the JNI does. */
    private fun vadRaw(vararg segs: VadSeg): IntArray =
        segs.flatMap { listOf(it.origStart, it.origEnd, it.trimmedStart, it.trimmedEnd) }
            .toIntArray()

    /** `[t0cs, t1cs, byteStart, byteEnd]` * n, flattened the way the JNI does. */
    private fun whisperRaw(cs: List<Pair<Int, Int>>, bytes: List<Pair<Int, Int>>): IntArray {
        assertEquals("one centisecond pair per byte range", cs.size, bytes.size)
        return cs.indices.flatMap {
            listOf(cs[it].first, cs[it].second, bytes[it].first, bytes[it].second)
        }.toIntArray()
    }

    /**
     * `[t0cs, t1cs, byteStart, byteEnd]` * n from `lastTokenTimes` — the SAME four fields as
     * [whisperRaw] at TOKEN grain, named separately because that is the only thing a reader of
     * one of these tests needs to keep straight: both arrays are on the TRIMMED timeline and
     * both carry byte offsets into the one returned buffer, and the difference is only how
     * finely they cut it.
     */
    private fun tokenRaw(cs: List<Pair<Int, Int>>, bytes: List<Pair<Int, Int>>): IntArray =
        whisperRaw(cs, bytes)

    // ------------------------------------------------------------------ vadSegments

    @Test
    fun vadSegmentsUnpacksFourIntsPerSegmentInChunkOrder() {
        val raw = intArrayOf(0, 32_000, 0, 32_000, 40_000, 72_000, 33_600, 65_600)
        assertEquals(
            listOf(
                VadSeg(origStart = 0, origEnd = 32_000, trimmedStart = 0, trimmedEnd = 32_000),
                VadSeg(
                    origStart = 40_000, origEnd = 72_000,
                    trimmedStart = 33_600, trimmedEnd = 65_600,
                ),
            ),
            SpeakerSpans.vadSegments(raw),
        )
    }

    @Test
    fun emptyRawArraysYieldEmptyLists() {
        assertEquals(emptyList<VadSeg>(), SpeakerSpans.vadSegments(IntArray(0)))
        assertEquals(
            emptyList<SpeakerSpan>(),
            SpeakerSpans.spans(IntArray(0), ByteArray(0), emptyList()),
        )
    }

    @Test
    fun aTruncatedTailIsIgnoredRatherThanRead_becauseHalfASegmentIsNotASegment() {
        // Five ints: one whole segment and a stub. The stub must not become a VadSeg with
        // three of its four bounds invented.
        assertEquals(
            listOf(VadSeg(0, 16_000, 0, 16_000)),
            SpeakerSpans.vadSegments(intArrayOf(0, 16_000, 0, 16_000, 17_600)),
        )
    }

    // ------------------------------------------------------------- windows (spike session 4)

    @Test
    fun aVadSegmentUNDERTwoSecondsIsOneWindowHoweverManySentencesAreInIt() {
        // The long-segment floor is on the SEGMENT, and below it there is nothing to cut into:
        // 1.8 s cannot hold two windows of MIN_WINDOW_SECONDS. This is the only "too short to
        // split" case left after the 2026-09-18 late session — four seconds, which used to be
        // this test, is now firmly on the cut side.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 28_800, 0, 28_800)))
        val raw = whisperRaw(listOf(0 to 80, 90 to 180), listOf(0 to 10, 10 to 20))

        assertEquals(listOf(SpeakerWindow(0, 0, 28_800)), SpeakerSpans.windows(raw, vad))
    }

    @Test
    fun aFourSecondSegmentWithTwoSentencesIsCUT_whichIsTheWholeOfTheLateSessionsChange() {
        // THE REGRESSION THE OWNER HEARD, as one assertion. The 02:12 dump's median window was
        // 3.0 s and 131 of its 218 windows were 2.5 s or longer; under the old 5.0 s floor a
        // chunk of exactly this shape was ONE fingerprint, so when the second sentence was the
        // other person's opening line it silently took the first speaker's label.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 4 * RATE, 0, 4 * RATE)))
        val raw = whisperRaw(listOf(0 to 180, 190 to 400), listOf(0 to 10, 10 to 20))

        assertEquals(
            listOf(SpeakerWindow(0, 0, 30_400), SpeakerWindow(0, 30_400, 4 * RATE)),
            SpeakerSpans.windows(raw, vad),
        )
    }

    @Test
    fun theFloorIsINCLUSIVE_soExactlyTwoSecondsWithTwoSentencesIsTwoWindows() {
        // 2.0 s is the SMALLEST segment that can hold two windows at MIN_WINDOW_SECONDS, which
        // is the only reason the floor sits there; excluding the boundary would exclude the one
        // case the number was chosen for.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 2 * RATE, 0, 2 * RATE)))
        val raw = whisperRaw(listOf(0 to 100, 100 to 200), listOf(0 to 10, 10 to 20))

        assertEquals(
            listOf(SpeakerWindow(0, 0, RATE), SpeakerWindow(0, RATE, 2 * RATE)),
            SpeakerSpans.windows(raw, vad),
        )
    }

    @Test
    fun aLongVadSegmentWithONEWhisperSegmentIsStillOneWindow() {
        // The other term of the guard. A cut has to be made somewhere defensible, and whisper's
        // segment boundaries are the only boundaries here that were placed by something that
        // listened to the words. With one segment there is no such boundary inside.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 12 * RATE, 0, 12 * RATE)))
        val raw = whisperRaw(listOf(0 to 1_200), listOf(0 to 10))

        assertEquals(listOf(SpeakerWindow(0, 0, 12 * RATE)), SpeakerSpans.windows(raw, vad))
    }

    @Test
    fun aLongSegmentsWindowsAreMappedBackThroughItsOwnOffset_notReadOffTheTrimmedTimeline() {
        // THE ARITHMETIC THE 100 ms STITCH GAPS MAKE WRONG IF IT IS SKIPPED. Segment 1 sits at
        // sample 100 000 of the RAW chunk and at 33 600 of the stitched buffer whisper timed its
        // output against: an offset of 66 400 samples, which is what separates the audio a
        // speaker embedder must be handed from the audio whisper thought it was reading.
        //
        // Reading the whisper timestamps as original samples — the mistake this test exists for —
        // would hand the embedder the wrong two thirds of a minute-long chunk.
        val vad = SpeakerSpans.vadSegments(
            vadRaw(
                VadSeg(0, 32_000, 0, 32_000),
                VadSeg(100_000, 300_000, 33_600, 233_600),
            )
        )
        // Two sentences inside segment 1: trimmed [33 600, 129 600) and [131 200, 233 600).
        val raw = whisperRaw(listOf(210 to 810, 820 to 1_460), listOf(0 to 10, 10 to 20))

        assertEquals(
            listOf(
                SpeakerWindow(vadIndex = 0, origStart = 0, origEnd = 32_000),
                SpeakerWindow(vadIndex = 1, origStart = 100_000, origEnd = 197_600),
                SpeakerWindow(vadIndex = 1, origStart = 197_600, origEnd = 300_000),
            ),
            SpeakerSpans.windows(raw, vad),
        )
    }

    @Test
    fun shortWhisperSegmentsAreCOALESCEDUntilAWindowIsWorthFingerprinting() {
        // MIN_WINDOW_SECONDS is MIN_MATCH_SECONDS since the 2026-09-18 late session: a window
        // under it is below the embedder floor too, so cutting it costs an embedding and buys a
        // window that can only inherit. Two half-second sentences therefore share the first
        // window; every sentence that clears a second on its own keeps its own — which at 1.0 s
        // is most of them, and is exactly the point of the change.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 12 * RATE, 0, 12 * RATE)))
        val raw = whisperRaw(
            listOf(0 to 50, 50 to 100, 100 to 300, 300 to 500, 500 to 1_200),
            List(5) { it * 10 to it * 10 + 10 },
        )

        assertEquals(
            listOf(
                SpeakerWindow(0, 0, 16_000),         // 0.5 s + 0.5 s, coalesced
                SpeakerWindow(0, 16_000, 48_000),    // 2 s
                SpeakerWindow(0, 48_000, 80_000),    // 2 s
                SpeakerWindow(0, 80_000, 12 * RATE), // 7 s
            ),
            SpeakerSpans.windows(raw, vad),
        )
    }

    @Test
    fun aOneSecondSentenceIsItsOwnWindowRatherThanBeingCoalescedAway() {
        // The floor is 1.0 s, INCLUSIVE, and it has to be: the sentence that goes missing at a
        // speaker change is usually a short one ("Yeah, but did it?"), and coalescing it into
        // the previous speaker's window is precisely the boundary lag the late session is about.
        // The tracker's MATCH-ONLY tier then decides what it may do — see the composed test.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 3 * RATE, 0, 3 * RATE)))
        val raw = whisperRaw(listOf(0 to 100, 100 to 300), listOf(0 to 10, 10 to 20))

        assertEquals(
            listOf(SpeakerWindow(0, 0, RATE), SpeakerWindow(0, RATE, 3 * RATE)),
            SpeakerSpans.windows(raw, vad),
        )
    }

    @Test
    fun aTrailingRemainderTooShortToStandAloneJoinsTheWindowBeforeIt() {
        // Half a second of "yeah" at the end of a long segment is not a window — the rule is
        // unchanged by the late session, only its floor moved to 1.0 s. Left alone it would be
        // an embedding under the tracker's MIN_EMBED floor, which is the cost of a decision with
        // the decision removed: the window could only ever inherit the label before it, which is
        // exactly what joining the previous window gives it for free.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 12 * RATE, 0, 12 * RATE)))
        val raw = whisperRaw(
            listOf(0 to 200, 200 to 400, 400 to 1_150, 1_150 to 1_200),
            List(4) { it * 10 to it * 10 + 10 },
        )

        assertEquals(
            listOf(
                SpeakerWindow(0, 0, 32_000),
                SpeakerWindow(0, 32_000, 64_000),
                SpeakerWindow(0, 64_000, 12 * RATE), // 7.5 s and the 0.5 s remainder with it
            ),
            SpeakerSpans.windows(raw, vad),
        )
    }

    @Test
    fun theWindowsOfASegmentPartitionItSoNoAudioIsLeftUnfingerprinted() {
        // The boundaries are whisper's starts, but the EDGES are the segment's own: the first
        // window opens where the segment opens and the last closes where it closes, so the
        // pauses between sentences — and the run-up before the first word — still reach an
        // embedder. Splitting must not quietly drop audio the un-split shape included.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(5_000, 5_000 + 12 * RATE, 0, 12 * RATE)))
        val raw = whisperRaw(listOf(20 to 550, 600 to 1_150), listOf(0 to 10, 10 to 20))

        val windows = SpeakerSpans.windows(raw, vad)
        assertEquals(2, windows.size)
        assertEquals(5_000, windows.first().origStart)
        assertEquals(5_000 + 12 * RATE, windows.last().origEnd)
        assertEquals("no gap between them", windows[0].origEnd, windows[1].origStart)
    }

    @Test
    fun noVadSegmentsMeansNoWindows_neverOneWindowForTheWholeChunk() {
        assertEquals(emptyList<SpeakerWindow>(), SpeakerSpans.windows(IntArray(0), emptyList()))
        assertEquals(
            emptyList<SpeakerWindow>(),
            SpeakerSpans.windows(whisperRaw(listOf(0 to 100), listOf(0 to 10)), emptyList()),
        )
    }

    @Test
    fun aSegmentWithNoWhisperSegmentAtAllIsStillOneWindowOverTheWholeOfIt() {
        // The VAD heard speech and whisper decoded nothing there — a hum, a cough, a marker that
        // cleaned away. There is no boundary to cut on, and the audio is still a voice.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 12 * RATE, 0, 12 * RATE)))

        assertEquals(listOf(SpeakerWindow(0, 0, 12 * RATE)), SpeakerSpans.windows(IntArray(0), vad))
    }

    // ------------------------------------------- windows cut at a WORD (4.11 Task 2)

    @Test
    fun aLongONESentenceSegmentIsCutAtTheTokenEdgeNEARESTItsMiddle() {
        // SESSION 7's FAILURE, in miniature. The Fold6's hard-cut interview handed the endpointer
        // one unbroken 9-15 s segment per chunk and whisper decoded it as ONE sentence, so both
        // terms of the sentence split failed at once — `windows = 1`, chunk after chunk, and one
        // label over a minute of two people talking. Token times give the only boundaries that
        // exist inside a pause-free sentence.
        //
        // Three tokens at 0-1.10 s, 1.10-2.90 s, 2.90-6.00 s: edges at 17_600 and 46_400 samples.
        // The candidate is the middle, 48_000, so 46_400 wins by 1_600 against 30_400 — and the
        // point of asserting the NEAR one is that "the first edge that fits" would have taken
        // 17_600 and cut a 1.1 s sliver off the front.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 6 * RATE, 0, 6 * RATE)))
        val raw = whisperRaw(listOf(0 to 600), listOf(0 to 30))
        val tokens = tokenRaw(
            listOf(0 to 110, 110 to 290, 290 to 600),
            listOf(0 to 10, 10 to 18, 18 to 30),
        )

        assertEquals(
            listOf(SpeakerWindow(0, 0, 46_400), SpeakerWindow(0, 46_400, 6 * RATE)),
            SpeakerSpans.windows(raw, vad, tokens),
        )
    }

    @Test
    fun theSameSegmentWithNOTokenTimesIsTodaysSingleWindow_unchanged() {
        // The other half of the same claim, and the one that keeps every tier that has no token
        // times — a chunk whose segment failed the native equality check, a fake in another
        // test — on exactly the behaviour it had before this array existed.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 6 * RATE, 0, 6 * RATE)))
        val raw = whisperRaw(listOf(0 to 600), listOf(0 to 30))

        assertEquals(listOf(SpeakerWindow(0, 0, 6 * RATE)), SpeakerSpans.windows(raw, vad))
        assertEquals(
            "an empty array is the same as not passing one",
            SpeakerSpans.windows(raw, vad),
            SpeakerSpans.windows(raw, vad, IntArray(0)),
        )
    }

    @Test
    fun theCutRepeatsWhileAStretchIsStillLongEnoughToHoldTwoWindows() {
        // The Fold6's actual shape: 15 s of unbroken speech in one VAD segment. One bisection
        // would leave two 7.5 s windows, which is the same failure with a bigger number, so the
        // cut recurses while a stretch is at least TOKEN_CUT_SECONDS — two windows of
        // LONG_SEGMENT_SECONDS, the length this file already treats as big enough to hide a
        // second voice. Thirty half-second tokens, so every 8_000 samples is an edge.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 15 * RATE, 0, 15 * RATE)))
        val raw = whisperRaw(listOf(0 to 1_500), listOf(0 to 300))
        val tokens = tokenRaw(
            (0 until 30).map { it * 50 to (it + 1) * 50 },
            (0 until 30).map { it * 10 to (it + 1) * 10 },
        )

        val windows = SpeakerSpans.windows(raw, vad, tokens)
        assertEquals(
            listOf(
                SpeakerWindow(0, 0, 56_000),
                SpeakerWindow(0, 56_000, 88_000),
                SpeakerWindow(0, 88_000, 120_000),
                SpeakerWindow(0, 120_000, 176_000),
                SpeakerWindow(0, 176_000, 208_000),
                SpeakerWindow(0, 208_000, 15 * RATE),
            ),
            windows,
        )
        assertEquals("the windows still partition the segment", 0, windows.first().origStart)
        assertEquals(15 * RATE, windows.last().origEnd)
        windows.zipWithNext().forEach { (a, b) -> assertEquals(a.origEnd, b.origStart) }
    }

    @Test
    fun aStretchTooShortToHoldTwoRealWindowsIsLeftWhole_soTheMedianSentenceIsUntouched() {
        // The 02:12 dump's median window was 3.0 s. If the token cut fired there it would double
        // the embedding count across the whole session to buy two halves of one sentence, and
        // spike session 4's reason for cutting — two VOICES in one window — is not what a 3 s
        // sentence is. Below TOKEN_CUT_SECONDS the answer stays exactly today's.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 3 * RATE, 0, 3 * RATE)))
        val raw = whisperRaw(listOf(0 to 300), listOf(0 to 30))
        val tokens = tokenRaw(
            (0 until 6).map { it * 50 to (it + 1) * 50 },
            (0 until 6).map { it * 5 to (it + 1) * 5 },
        )

        assertEquals(
            listOf(SpeakerWindow(0, 0, 3 * RATE)),
            SpeakerSpans.windows(raw, vad, tokens),
        )
    }

    @Test
    fun aCutIsREFUSEDWhenEveryTokenEdgeWouldLeaveAWindowUnderTheFloor() {
        // MIN_WINDOW_SECONDS is the embedder's floor as well as the tracker's lowest gate, so a
        // cut that produced a 0.2 s window would spend an embedding on audio the tracker is not
        // allowed to say anything about. One very short opening token and one long one: the only
        // interior edge sits 0.2 s in, so there is nowhere to cut and the segment stays whole.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 5 * RATE, 0, 5 * RATE)))
        val raw = whisperRaw(listOf(0 to 500), listOf(0 to 30))
        val tokens = tokenRaw(listOf(0 to 20, 20 to 500), listOf(0 to 3, 3 to 30))

        assertEquals(
            listOf(SpeakerWindow(0, 0, 5 * RATE)),
            SpeakerSpans.windows(raw, vad, tokens),
        )
    }

    @Test
    fun tokenEdgesFromANOTHERVadSegmentCannotCutThisOne() {
        // Token times are process-global for the whole chunk, on the TRIMMED timeline, exactly
        // like the segment geometry — so they must be attributed to a VAD segment and mapped
        // through ITS offset before they are edges at all. Reading them as one flat list would
        // let segment 1's words cut segment 0 at a place nobody spoke.
        val vad = SpeakerSpans.vadSegments(
            vadRaw(
                VadSeg(0, 6 * RATE, 0, 6 * RATE),
                VadSeg(200_000, 200_000 + 2 * RATE, 97_600, 97_600 + 2 * RATE),
            )
        )
        val raw = whisperRaw(listOf(0 to 600, 610 to 810), listOf(0 to 30, 30 to 40))
        // Every token belongs to segment 1 — segment 0 has no edges of its own.
        val tokens = tokenRaw(
            listOf(610 to 660, 660 to 730, 730 to 810),
            listOf(30 to 33, 33 to 36, 36 to 40),
        )

        assertEquals(
            listOf(
                SpeakerWindow(0, 0, 6 * RATE),
                SpeakerWindow(1, 200_000, 200_000 + 2 * RATE),
            ),
            SpeakerSpans.windows(raw, vad, tokens),
        )
    }

    @Test
    fun aTokenCutSplitsTheTEXTAtTheSameWord_contiguouslyAndWithNothingDropped() {
        // A window the text cannot be cut at is a window that cannot carry a label: the spans
        // mapper attributes a whole whisper segment by its MIDPOINT, so without this the 6 s
        // sentence above would become two windows, two fingerprints, and one span wearing one
        // id. The byte offsets the tokens carry are what make the second half addressable.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 6 * RATE, 0, 6 * RATE)))
        val (bytes, ranges) = encode(" Hello there and you too now")
        val raw = whisperRaw(listOf(0 to 600), ranges)
        // " Hello there" | " and you" | " too now" — byte ranges that tile the segment exactly.
        val tokens = tokenRaw(
            listOf(0 to 110, 110 to 290, 290 to 600),
            listOf(0 to 12, 12 to 20, 20 to 28),
        )

        val windows = SpeakerSpans.windows(raw, vad, tokens)
        assertEquals(2, windows.size)
        val spans = SpeakerSpans.spans(raw, bytes, vad, tokens, windows)
        assertEquals(
            listOf(SpeakerSpan(0, "Hello there and you"), SpeakerSpan(1, "too now")),
            spans,
        )
        assertEquals(
            "every word of the segment survives the cut",
            "Hello there and you too now",
            spans.joinToString(" ") { it.text },
        )
    }

    @Test
    fun withoutTokenTimesTheSpansAreExactlyTodays_evenWhenTheWindowsWereCut() {
        // The fallback the native side's per-segment equality check can hand over at any moment
        // (`token-times: segment N dropped`): the windows are then today's and so are the spans,
        // and nothing about the pair is half-new.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 6 * RATE, 0, 6 * RATE)))
        val (bytes, ranges) = encode(" Hello there and you too now")
        val raw = whisperRaw(listOf(0 to 600), ranges)

        assertEquals(
            listOf(SpeakerSpan(0, "Hello there and you too now")),
            SpeakerSpans.spans(raw, bytes, vad),
        )
    }

    // ------------------------------------------------------------------ spans

    @Test
    fun oneWhisperSegmentInsideEachVadSegment_yieldsOneSpanEach() {
        val vad = SpeakerSpans.vadSegments(
            vadRaw(
                VadSeg(0, 32_000, 0, 32_000),
                VadSeg(40_000, 72_000, 33_600, 65_600),
            )
        )
        val (bytes, ranges) = encode(" Hello", " world.")
        val raw = whisperRaw(listOf(0 to 200, 210 to 410), ranges)

        assertEquals(
            listOf(SpeakerSpan(windowIndex = 0, text = "Hello"), SpeakerSpan(1, "world.")),
            SpeakerSpans.spans(raw, bytes, vad),
        )
    }

    @Test
    fun aSegmentStraddlingTheStitchGap_goesToTheSideItOverlapsMost() {
        // seg 0 trimmed [0, 32_000) then GAP then seg 1 trimmed [33_600, 65_600).
        val vad = SpeakerSpans.vadSegments(
            vadRaw(
                VadSeg(0, 32_000, 0, 32_000),
                VadSeg(40_000, 72_000, 32_000 + GAP, 32_000 + GAP + 32_000),
            )
        )
        // [195 cs, 220 cs) = samples [31_200, 35_200): 800 samples in seg 0, 1_600 in seg 1.
        val (bytes, ranges) = encode(" straddling")
        val raw = whisperRaw(listOf(195 to 220), ranges)

        assertEquals(listOf(SpeakerSpan(1, "straddling")), SpeakerSpans.spans(raw, bytes, vad))

        // And the mirror, so the assertion above is about the ARITHMETIC and not about "the last
        // segment wins": [160 cs, 210 cs) = samples [25_600, 33_600) is 6_400 in seg 0 and 0 in
        // seg 1.
        val raw2 = whisperRaw(listOf(160 to 210), ranges)
        assertEquals(listOf(SpeakerSpan(0, "straddling")), SpeakerSpans.spans(raw2, bytes, vad))
    }

    @Test
    fun aSegmentEntirelyInTheZeroPaddedTail_belongsToTheLastVadSegment() {
        // The sub-1.1 s zero-pad is appended AFTER the stitch, so it is in no VAD segment at all.
        val vad = SpeakerSpans.vadSegments(
            vadRaw(
                VadSeg(0, 8_000, 0, 8_000),
                VadSeg(10_000, 16_000, 9_600, 15_600),
            )
        )
        val (bytes, ranges) = encode(" tail words")
        val raw = whisperRaw(listOf(120 to 140), ranges)   // samples 19_200..22_400 — past 15_600

        assertEquals(listOf(SpeakerSpan(1, "tail words")), SpeakerSpans.spans(raw, bytes, vad))
    }

    @Test
    fun adjacentSegmentsInTheSameVadSegmentMergeUnderTextJoinRules() {
        // 1.8 s: under LONG_SEGMENT_SECONDS, so the three sentences share ONE window and the
        // merge is reachable at all. Above the floor they would be three windows and three
        // spans — which is the split working, not this rule failing.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 28_800, 0, 28_800)))
        val (bytes, ranges) = encode(" one", " two", ", three")
        val raw = whisperRaw(listOf(0 to 60, 60 to 120, 120 to 180), ranges)

        // TextJoin: a space between "one" and "two"; NONE before a run that opens with closing
        // punctuation. A plain " " join would read "one two , three".
        assertEquals(
            listOf(SpeakerSpan(0, "one two, three")),
            SpeakerSpans.spans(raw, bytes, vad),
        )
    }

    @Test
    fun aSpeakerWhoComesBack_getsASeparateSpan_ratherThanMergingAcrossTheGap() {
        // Merging is ADJACENCY-only: 0,1,0 stays three spans, because the run in between is
        // somebody else's text and the panel must break a paragraph at each change.
        val vad = SpeakerSpans.vadSegments(
            vadRaw(
                VadSeg(0, 16_000, 0, 16_000),
                VadSeg(20_000, 36_000, 17_600, 33_600),
                VadSeg(40_000, 56_000, 35_200, 51_200),
            )
        )
        val (bytes, ranges) = encode(" mine", " yours", " mine again")
        val raw = whisperRaw(listOf(0 to 100, 110 to 210, 220 to 320), ranges)

        assertEquals(
            listOf(SpeakerSpan(0, "mine"), SpeakerSpan(1, "yours"), SpeakerSpan(2, "mine again")),
            SpeakerSpans.spans(raw, bytes, vad),
        )
    }

    @Test
    fun byteSlicesDecodeFourByteCodePointsIntact() {
        val vad = SpeakerSpans.vadSegments(
            vadRaw(
                VadSeg(0, 16_000, 0, 16_000),
                VadSeg(20_000, 36_000, 17_600, 33_600),
            )
        )
        // The emoji is FOUR UTF-8 bytes, so the second segment's byteStart is 4 past where a
        // character count would put it. Decoding the whole buffer and slicing by Char index
        // would cut the code point in half and yield U+FFFD.
        val (bytes, ranges) = encode(" party 🎉", " and more")
        val raw = whisperRaw(listOf(0 to 100, 110 to 210), ranges)

        val spans = SpeakerSpans.spans(raw, bytes, vad)
        assertEquals(listOf(SpeakerSpan(0, "party 🎉"), SpeakerSpan(1, "and more")), spans)
        assertTrue(
            "no replacement character may appear — that is a mid-code-point cut",
            spans.none { it.text.contains('�') },
        )
    }

    @Test
    fun aSegmentWhoseTextCleansAwayContributesNoSpan() {
        // whisper's non-speech markers are stripped by TranscriptText.clean, and a span with no
        // text would otherwise open a paragraph for a speaker who said nothing.
        val vad = SpeakerSpans.vadSegments(
            vadRaw(
                VadSeg(0, 16_000, 0, 16_000),
                VadSeg(20_000, 36_000, 17_600, 33_600),
            )
        )
        val (bytes, ranges) = encode(" [BLANK_AUDIO]", " real speech")
        val raw = whisperRaw(listOf(0 to 100, 110 to 210), ranges)

        assertEquals(listOf(SpeakerSpan(1, "real speech")), SpeakerSpans.spans(raw, bytes, vad))
    }

    @Test
    fun spansAreEmptyWhenTheVadDidNotRun_neverAllAttributedToSpeakerZero() {
        // An empty VAD array means "no VAD ran / it found no speech" and must NOT be read as one
        // speaker: with no segments there is no timeline to map text onto at all.
        val (bytes, ranges) = encode(" text with no geometry")
        val raw = whisperRaw(listOf(0 to 100), ranges)

        assertEquals(emptyList<SpeakerSpan>(), SpeakerSpans.spans(raw, bytes, emptyList()))
    }

    @Test
    fun twoSentencesInASPLITSegmentGetSEPARATESpansRatherThanMerging() {
        // Rule 6 read against rule 5: the merge is by WINDOW now, so the whole point of having
        // split a long segment survives into the text. Under the pre-session-4 rule these two
        // whisper segments shared a VAD index and were joined into one span — one paragraph and
        // one label, for what may well be two voices.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 12 * RATE, 0, 12 * RATE)))
        val (bytes, ranges) = encode(" Hello there.", " Hi, how are you?")
        val raw = whisperRaw(listOf(0 to 550, 560 to 1_200), ranges)

        assertEquals(
            listOf(SpeakerSpan(0, "Hello there."), SpeakerSpan(1, "Hi, how are you?")),
            SpeakerSpans.spans(raw, bytes, vad),
        )

        // …and the mirror: the SAME two sentences inside a 1.8 s segment are one window and
        // therefore still one span. The split is the only thing that separated them. This used
        // to be a FOUR-second segment; at the late session's 2.0 s floor four seconds is cut,
        // and 1.8 s is what "too short to cut" now means.
        val shortVad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 28_800, 0, 28_800)))
        val shortRaw = whisperRaw(listOf(0 to 80, 90 to 180), ranges)
        assertEquals(
            listOf(SpeakerSpan(0, "Hello there. Hi, how are you?")),
            SpeakerSpans.spans(shortRaw, bytes, shortVad),
        )
    }

    @Test
    fun aSpansWindowIsTheOneItsOWNMidpointFallsIn() {
        // The boundary between two windows IS a whisper segment's own start, so a segment can
        // never land on the wrong side of the cut that was made for it — asserted on the tight
        // case, where the second sentence begins exactly where the first one ends.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 12 * RATE, 0, 12 * RATE)))
        val (bytes, ranges) = encode(" first", " second")
        val raw = whisperRaw(listOf(0 to 600, 600 to 1_200), ranges)

        val windows = SpeakerSpans.windows(raw, vad)
        assertEquals(listOf(SpeakerWindow(0, 0, 96_000), SpeakerWindow(0, 96_000, 192_000)), windows)
        assertEquals(
            listOf(SpeakerSpan(0, "first"), SpeakerSpan(1, "second")),
            SpeakerSpans.spans(raw, bytes, vad, windows = windows),
        )
    }

    @Test
    fun byteRangesThatOverrunTheBufferAreClamped_notThrown() {
        // The geometry is PROCESS-GLOBAL and one call behind: an off-gate read can hand this
        // function a previous chunk's ranges against this chunk's bytes. That is a wrong answer,
        // not a crash — a StringIndexOutOfBounds here would cost the user the whole segment.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 16_000, 0, 16_000)))
        val bytes = " short".toByteArray(Charsets.UTF_8)
        val raw = intArrayOf(0, 100, 0, 9_999)

        assertEquals(listOf(SpeakerSpan(0, "short")), SpeakerSpans.spans(raw, bytes, vad))
    }

    // ------------------------------------ the splitter and the tracker, composed (late session)

    /** A unit vector at [deg]: `cos(unit(a), unit(b))` is exactly `cos(a - b)`, as in the tracker's test. */
    private fun unit(deg: Double): FloatArray {
        val r = Math.toRadians(deg)
        return floatArrayOf(cos(r).toFloat(), sin(r).toFloat())
    }

    @Test
    fun aShortSentenceWindowIsMATCHEDToAKnownSpeakerButCannotOPENOne() {
        // THE TWO HALVES OF THE LATE SESSION'S CHANGE, in one test, because neither half is
        // right alone. MIN_WINDOW_SECONDS 1.0 exists so that a 1.0-1.5 s sentence is CUT and
        // handed over at all; the tracker's MATCH-ONLY tier exists so that what is handed over
        // can be recognised without being allowed to claim a person. If this splitter refused
        // the short window itself it would be a second set of duration gates, sitting in front
        // of the measured ones and never measured against anything.
        //
        // A 3.2 s VAD segment holding sentences of 1.2 s and 2.0 s — the ordinary 2.5-5 s shape
        // that was 131 of the 02:12 dump's 218 windows, and the shape that used to be ONE
        // fingerprint wearing one speaker's label.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 51_200, 0, 51_200)))
        val raw = whisperRaw(listOf(0 to 120, 120 to 320), listOf(0 to 10, 10 to 20))
        val windows = SpeakerSpans.windows(raw, vad)

        assertEquals(
            listOf(SpeakerWindow(0, 0, 19_200), SpeakerWindow(0, 19_200, 51_200)),
            windows,
        )
        val seconds = windows.map { (it.origEnd - it.origStart) / RATE.toFloat() }
        assertEquals("the short sentence keeps its own window", 1.2f, seconds[0], 1e-4f)
        assertEquals(2.0f, seconds[1], 1e-4f)

        // A session with ONE known speaker, opened on audio long enough to be allowed to.
        val tracker = SpeakerTracker()
        assertEquals(1, tracker.assign(unit(0.0), 2.5f))

        // Window 0, 1.2 s of the SAME voice: RECOGNISED — that is the whole gain. Under the old
        // 1.5 s window floor this sentence never existed as a window at all.
        assertEquals(1, tracker.assign(unit(10.0), seconds[0]))
        assertTrue(
            "…on a similarity it actually measured",
            tracker.lastBestSimilarity >= SpeakerTracker.T_SAME,
        )
        assertEquals("and no speaker was opened by a 1.2 s window", 1, tracker.speakerCount)

        // The same 1.2 s window with a STRANGER in it: below T_SAME, so it inherits the label
        // before it and opens nobody. The splitter handed it over; the TRACKER refused it.
        assertEquals(1, tracker.assign(unit(100.0), seconds[0]))
        assertEquals("a 1.2 s window may never claim a person", 1, tracker.speakerCount)

        // Window 1, 2.0 s of that same stranger, DOES open speaker 2. The only difference
        // between the two calls is the duration, and the only thing that read it is the
        // tracker's MIN_OPEN gate.
        assertEquals(2, tracker.assign(unit(100.0), seconds[1]))
        assertEquals(2, tracker.speakerCount)
    }

    // ------------------------------------------------------------------ the settlement

    @Test
    fun theWindowConstantsAreTheONESTheMeasurementDocSettled() {
        // THE SOURCE: docs/measurements/2026-09-18-speaker-spike.md — **session 4** for the RULE
        // (a multi-sentence segment is fingerprinted per sentence, cut on whisper's own
        // boundaries, which are the only boundaries here placed by something that listened to
        // the words) and the **2026-09-18 LATE** session, beside it, for both VALUES.
        //
        // Session 4 could not demonstrate failure mode B — its one long dump was a single
        // narrator — so it bounded the cut at 5.0 s / 1.5 s and left ordinary turn-taking
        // exactly as it was. The late session measured what that cost on the owner's own
        // conversation: 218 fingerprint windows, median 3.0 s, 131 of them 2.5 s or longer and
        // only 26 over five. The cut fired on about a tenth of the audio, and the rest of it is
        // what he heard — "the boundaries is where the speaker switch is just not catching the
        // beginning of when someone starts to speak".
        //
        // Neither number may move again without a new dump, and neither is free-standing: 2.0 s
        // is exactly two windows' worth, which is what makes it the smallest segment with a cut
        // in it, and 1.0 s is the tracker's lowest gate, which is what makes a window worth
        // cutting at all.
        assertEquals("LONG_SEGMENT_SECONDS", 2.0f, SpeakerSpans.LONG_SEGMENT_SECONDS, 0f)
        assertEquals("MIN_WINDOW_SECONDS", 1.0f, SpeakerSpans.MIN_WINDOW_SECONDS, 0f)
        assertEquals(
            "the shortest window is the tracker's lowest gate, not a number of its own",
            SpeakerTracker.MIN_MATCH_SECONDS,
            SpeakerSpans.MIN_WINDOW_SECONDS,
            0f,
        )
        assertEquals(
            "…and the embedder floor too, so every window a split makes is really fingerprinted",
            SpeakerTracker.MIN_EMBED_SECONDS,
            SpeakerSpans.MIN_WINDOW_SECONDS,
            0f,
        )
        assertEquals(
            "the segment floor is two windows' worth — the smallest segment that can hold a cut",
            2f * SpeakerSpans.MIN_WINDOW_SECONDS,
            SpeakerSpans.LONG_SEGMENT_SECONDS,
            0f,
        )
        assertTrue(
            "a window may be shorter than the segment floor; that is the whole of the split",
            SpeakerSpans.MIN_WINDOW_SECONDS < SpeakerSpans.LONG_SEGMENT_SECONDS,
        )
    }

    // ------------------------------------- the NPU tier's windows (4.10, the Fold6 defect)

    /** `[start, end]` sample pairs, from seconds — what `WhisperNative.vadSegmentsOf` answers. */
    private fun pairs(vararg bounds: Pair<Float, Float>): IntArray =
        IntArray(bounds.size * 2) { i ->
            val (startSec, endSec) = bounds[i / 2]
            ((if (i % 2 == 0) startSec else endSec) * RATE).toInt()
        }

    @Test
    fun wholeChunkWindowsAreTheSegmentsThemselvesBecauseThereIsOnlyONETimeline() {
        // Nothing is stitched on this route and nothing is swapped, so a pair IS a window. The
        // `vadIndex` is the position, which is what makes a coalesced window nameable by the
        // segment it starts at.
        assertEquals(
            listOf(
                SpeakerWindow(0, 0, 2 * RATE),
                SpeakerWindow(1, 3 * RATE, 8 * RATE),
            ),
            SpeakerSpans.wholeChunkWindows(pairs(0f to 2f, 3f to 8f)),
        )
    }

    @Test
    fun aSegmentShorterThanTheWindowFloorJoinsItsPredecessorPauseIncluded() {
        // MIN_WINDOW_SECONDS is the tracker's lowest gate, so a window under it can say nothing
        // at all. Three back-channels become one window that can — and it SPANS the pauses
        // between them, exactly as the geometry route's own partition does, while carrying the
        // SPEECH it actually holds: 2.0 + 0.4 + 0.3 = 2.7 s inside a 3.1 s span. The span is
        // what the embedder slices; the sum is what every gate is decided on.
        assertEquals(
            listOf(
                SpeakerWindow(
                    vadIndex = 0,
                    origStart = 0,
                    origEnd = (3.1f * RATE).toInt(),
                    speechSamples = (2.7f * RATE).toInt(),
                ),
            ),
            SpeakerSpans.wholeChunkWindows(pairs(0f to 2f, 2.2f to 2.6f, 2.8f to 3.1f)),
        )
    }

    @Test
    fun aShortSegmentACROSSAWIDEGAPStandsAloneInsteadOfDraggingTheSilenceIn() {
        // THE BOUND (round 1 of review). The geometry route can only ever fold a pause Silero
        // judged too short to END speech — `min_silence_duration_ms`, 100 ms — into a window,
        // because it splits one segment and never joins two. Here the two sides are SEPARATE
        // segments, so the silence between them is bounded by nothing but the chunk.
        //
        // Unbounded, this input produced ONE window 0.0-4.3 s holding 1.4 s of voice, and it then
        // (a) went to the embedder as a fingerprint that was two-thirds room tone and (b) told
        // the tracker "4.3 seconds", clearing all three graded gates. It also outranked the 3.0 s
        // window beside it for the chunk's one label. Bounded, the 0.3 s segment stands alone and
        // inherits, exactly as a short LEADING segment does.
        val windows = SpeakerSpans.wholeChunkWindows(pairs(0f to 1.1f, 4f to 4.3f, 4.5f to 7.5f))
        assertEquals(
            listOf(
                SpeakerWindow(0, 0, (1.1f * RATE).toInt()),
                SpeakerWindow(1, (4f * RATE).toInt(), (4.3f * RATE).toInt()),
                SpeakerWindow(2, (4.5f * RATE).toInt(), (7.5f * RATE).toInt()),
            ),
            windows,
        )
        assertTrue(
            "no window claims speech it does not hold",
            windows.all { it.speechSamples == it.origEnd - it.origStart },
        )
    }

    @Test
    fun theOVERLAPTwoPaddedSegmentsCanHaveIsAGapOfZeroAndNotARefusal() {
        // `speech_pad_ms = 150` is applied to each segment independently, so two close segments
        // can be handed over touching or overlapping. A negative gap is zero, not a reason to
        // refuse the join — and the merged speech sum is still the sum of the two pairs, which
        // for an overlap is slightly more than the span. The gate clamp in the assigner is what
        // keeps that honest.
        val windows = SpeakerSpans.wholeChunkWindows(
            intArrayOf(0, (2f * RATE).toInt(), (1.9f * RATE).toInt(), (2.4f * RATE).toInt()),
        )
        assertEquals(1, windows.size)
        assertEquals((2.4f * RATE).toInt(), windows.single().origEnd)
        assertEquals((2.5f * RATE).toInt(), windows.single().speechSamples)
    }

    @Test
    fun aSHORTLEADERHasNoPredecessorAndStandsAloneLikeTheGeometryRoutesFirstSegment() {
        // It will inherit rather than be fingerprinted (fate 1), which is the same answer the CPU
        // route gives a short leading VAD segment. Joining it FORWARDS would be a second rule
        // nobody measured, and it would also make the first window's bounds depend on the second.
        assertEquals(
            listOf(
                SpeakerWindow(0, 0, (0.4f * RATE).toInt()),
                SpeakerWindow(1, 1 * RATE, 4 * RATE),
            ),
            SpeakerSpans.wholeChunkWindows(pairs(0f to 0.4f, 1f to 4f)),
        )
    }

    @Test
    fun anEmptyOrDegenerateSegmentationYieldsNoWindowsAtAll() {
        // Spec §2 forbids reading "no segments" as "one speaker" — there is no timeline to
        // attribute anything to, so there is nothing to publish.
        assertEquals(emptyList<SpeakerWindow>(), SpeakerSpans.wholeChunkWindows(IntArray(0)))
        // A trailing odd int is half a segment and is dropped rather than repaired.
        assertEquals(emptyList<SpeakerWindow>(), SpeakerSpans.wholeChunkWindows(intArrayOf(0)))
        // An empty or inverted pair is dropped too: it would hand the embedder a zero-length
        // slice and a NaN duration.
        assertEquals(
            listOf(SpeakerWindow(1, 0, 2 * RATE)),
            SpeakerSpans.wholeChunkWindows(intArrayOf(5 * RATE, 5 * RATE, 0, 2 * RATE)),
        )
    }

    // ------------------- the NPU tier's SENTENCE windows and spans (4.11 Task 4)

    /** `[t0cs, t1cs, byteStart, byteEnd]` * n — what `NpuSentences.of` answers. */
    private fun sentences(vararg s: IntArray): IntArray = s.flatMap { it.toList() }.toIntArray()

    @Test
    fun sentenceChunkWindowsCutTheChunksSPEECHAtEachSENTENCEStart() {
        // SESSION 7'S FAILURE, ANSWERED. A hard-cut interview has no pauses, so Silero hands over
        // ONE 15 s segment and 4.10.1's rule gave the whole minute one label. The decoder has
        // always known where each sentence began; asked, it turns that one window into four.
        val windows = SpeakerSpans.sentenceChunkWindows(
            pairs(0f to 15f),
            sentences(
                intArrayOf(0, 400, 0, 10),
                intArrayOf(400, 700, 10, 20),
                intArrayOf(700, 1100, 20, 30),
                intArrayOf(1100, 1500, 30, 40),
            ),
        )
        assertEquals(
            listOf(
                SpeakerWindow(0, 0, 4 * RATE),
                SpeakerWindow(0, 4 * RATE, 7 * RATE),
                SpeakerWindow(0, 7 * RATE, 11 * RATE),
                SpeakerWindow(0, 11 * RATE, 15 * RATE),
            ),
            windows,
        )
        assertTrue(
            "no window claims speech it does not hold",
            windows.all { it.speechSamples == it.origEnd - it.origStart },
        )
    }

    @Test
    fun oneWindowPerSENTENCEAlwaysSoTheWINDOWINDEXIsTheSENTENCEINDEX() {
        // THE INVARIANT THE WHOLE ROUTE STANDS ON. The engine cuts the TEXT by sentence on the
        // native thread and the assigner cuts the AUDIO by sentence ~60 ms later on the embed
        // thread, and the two never meet: a span's `windowIndex` is matched to an id by POSITION.
        // So a sentence the VAD heard no speech in may not be dropped — every later sentence's
        // text would then wear the previous one's label.
        val windows = SpeakerSpans.sentenceChunkWindows(
            pairs(0f to 2f, 6f to 8f),
            sentences(
                intArrayOf(0, 200, 0, 10),
                intArrayOf(200, 400, 10, 20),
                intArrayOf(400, 800, 20, 30),
            ),
        )
        assertEquals("one window per sentence, silent ones included", 3, windows.size)
        assertEquals(SpeakerWindow(0, 0, 2 * RATE), windows[0])
        assertEquals(
            "the silent sentence is a ZERO-LENGTH window: it holds its index and inherits",
            0, windows[1].speechSamples,
        )
        assertEquals(windows[1].origStart, windows[1].origEnd)
        assertEquals(SpeakerWindow(1, 6 * RATE, 8 * RATE), windows[2])
    }

    @Test
    fun aSentenceSPANNINGAPauseReportsTheSPEECHItHoldsAndNotItsSpan() {
        // The same rule `wholeChunkWindows` needed and for the same reason: every gate below
        // this file is a question about VOICE, so a sentence that straddles two speech segments
        // must not claim the silence between them as seconds it spoke.
        val windows = SpeakerSpans.sentenceChunkWindows(
            pairs(0f to 2f, 6f to 8f),
            sentences(intArrayOf(0, 800, 0, 30)),
        )
        assertEquals(1, windows.size)
        assertEquals(0, windows.single().origStart)
        assertEquals(8 * RATE, windows.single().origEnd)
        assertEquals("4 s of voice inside an 8 s span", 4 * RATE, windows.single().speechSamples)
    }

    @Test
    fun aSentenceRunningPastTheCHUNKIsClippedToTheSpeechThatExists() {
        // `NpuSentences.CHUNK_END_CS` is 30.00 s — whisper's WINDOW, deliberately not the chunk's
        // real duration, which a pure function cannot know. Clipping is this function's job.
        val windows = SpeakerSpans.sentenceChunkWindows(
            pairs(0f to 6f),
            sentences(intArrayOf(0, 300, 0, 10), intArrayOf(300, 3000, 10, 20)),
        )
        assertEquals(
            listOf(SpeakerWindow(0, 0, 3 * RATE), SpeakerWindow(0, 3 * RATE, 6 * RATE)),
            windows,
        )
    }

    @Test
    fun noSentencesOrNoSpeechYieldsNOWindowsAndTheChunkIsPublishedWithNOLabel() {
        // Empty is NOT a request to fall back. `SpeakerAssigner.assignVadRoute` forks on
        // `sentences.isEmpty()` BEFORE this call, so the return value never picks a route:
        // an empty list hits `if (windows.isEmpty()) return@runCatching` and the chunk gets no
        // `SpeakerAssignment` at all — no label, rather than a coarse one. Spec section 2 forbids
        // reading "no segments" as one speaker either way.
        assertEquals(
            emptyList<SpeakerWindow>(),
            SpeakerSpans.sentenceChunkWindows(pairs(0f to 5f), IntArray(0)),
        )
        assertEquals(
            emptyList<SpeakerWindow>(),
            SpeakerSpans.sentenceChunkWindows(IntArray(0), sentences(intArrayOf(0, 200, 0, 10))),
        )
    }

    @Test
    fun sentenceSpansCutTheTextWhereTheSENTENCEDoesAndIndexTheirOwnWindow() {
        val bytes = " Hello there. And you.".toByteArray(Charsets.UTF_8)
        val spans = SpeakerSpans.sentenceSpans(
            sentences(intArrayOf(0, 240, 0, 13), intArrayOf(240, 400, 13, bytes.size)),
            bytes,
        )
        assertEquals(listOf(SpeakerSpan(0, "Hello there."), SpeakerSpan(1, "And you.")), spans)
    }

    @Test
    fun sentenceSpansSliceBYTESSoAMultiByteCharacterSurvivesTheCut() {
        // The same reason the whole file slices bytes: a `Char` index cannot be mapped back to a
        // native byte offset, and cutting inside a UTF-8 sequence yields U+FFFD on exactly the
        // multilingual models that made the byte return necessary.
        val (bytes, ranges) = encode("  Guten Tag. ", "Schön, dich zu sehen. ", "日本語です。")
        val spans = SpeakerSpans.sentenceSpans(
            sentences(
                intArrayOf(0, 200, ranges[0].first, ranges[0].second),
                intArrayOf(200, 400, ranges[1].first, ranges[1].second),
                intArrayOf(400, 600, ranges[2].first, ranges[2].second),
            ),
            bytes,
        )
        assertEquals(listOf(0, 1, 2), spans.map { it.windowIndex })
        assertEquals("Schön, dich zu sehen.", spans[1].text)
        assertEquals("日本語です。", spans[2].text)
    }

    @Test
    fun aSentenceThatCLEANSAwayContributesNoSpanButCostsNoIndexEither() {
        // Rule 2 of [spans], unchanged: whisper's non-speech markers would otherwise open a
        // paragraph for a speaker who said nothing. The index is STATED per span rather than
        // implied by position, so dropping one cannot shift the rest out of step with the ids.
        val (bytes, ranges) = encode("Hello. ", "[BLANK_AUDIO]", " Goodbye.")
        val spans = SpeakerSpans.sentenceSpans(
            sentences(
                intArrayOf(0, 200, ranges[0].first, ranges[0].second),
                intArrayOf(200, 300, ranges[1].first, ranges[1].second),
                intArrayOf(300, 500, ranges[2].first, ranges[2].second),
            ),
            bytes,
        )
        assertEquals(listOf(SpeakerSpan(0, "Hello."), SpeakerSpan(2, "Goodbye.")), spans)
    }

    @Test
    fun sentenceSpansClampAStaleRangeRatherThanThrowingOnIt() {
        // The same rule 1 [spans] has: these bounds cross a thread boundary and one call behind
        // is reachable. A wrong label is a wrong label; an exception costs the user the sentence.
        val bytes = "Hi.".toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf(SpeakerSpan(0, "Hi.")),
            SpeakerSpans.sentenceSpans(sentences(intArrayOf(0, 200, 0, 9_000)), bytes),
        )
        assertEquals(
            emptyList<SpeakerSpan>(),
            SpeakerSpans.sentenceSpans(sentences(intArrayOf(0, 200, 0, 3)), ByteArray(0)),
        )
    }

    @Test
    fun theTwoRoutesShareTheWindowFLOORAndNothingElse() {
        // The short-window rule is about the EMBEDDER and carries over; the SPLIT is about
        // whisper's own segment boundaries and cannot, because the QNN decoder publishes none.
        // A whole-chunk window is therefore never shorter than the floor unless it is the
        // chunk's first segment.
        val windows = SpeakerSpans.wholeChunkWindows(pairs(0f to 2f, 2.1f to 2.4f))
        val seconds = (windows.single().origEnd - windows.single().origStart) / RATE.toFloat()
        assertTrue(seconds >= SpeakerSpans.MIN_WINDOW_SECONDS)
    }
}
