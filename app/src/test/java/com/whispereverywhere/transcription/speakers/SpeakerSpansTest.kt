package com.whispereverywhere.transcription.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.10 Task 1: the pure half of the speaker pipeline — the native segment geometry
 * ([com.whispereverywhere.whisper.WhisperNative.lastVadSegments] /
 * `lastWhisperSegments`) turned into FINGERPRINT WINDOWS and `(windowIndex, text)` spans.
 *
 * THE WINDOWS ARE SPIKE SESSION 4's answer to failure mode B — the endpointer cuts on silence,
 * so when nobody pauses it can hand over six to fourteen seconds in one segment, and two voices
 * sharing those seconds would share one fingerprint. A segment past `LONG_SEGMENT_SECONDS` with
 * two or more whisper segments in it is cut along whisper's own boundaries, which are the only
 * boundaries in this pipeline placed by something that listened to the words.
 *
 * That failure was NOT demonstrated by session 4's dumps — its one long session turned out to be
 * a single narrator — so the tests below are about the split being CORRECT and BOUNDED rather
 * than about a bug being fixed: the offsets, the coalescing, the partition, and the two guards
 * that keep every ordinary chunk fingerprinted exactly as it was.
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
    fun aVadSegmentOfFiveSecondsOrLessIsOneWindowHoweverManySentencesAreInIt() {
        // The long-segment floor is on the SEGMENT. Four seconds with two whisper segments in it
        // is the ordinary shape of every dump that WORKED (20:23, 20:27: medians 2.7-3.1 s), and
        // cutting it would buy another 130-300 ms of embedding and no separation.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 4 * RATE, 0, 4 * RATE)))
        val raw = whisperRaw(listOf(0 to 180, 190 to 400), listOf(0 to 10, 10 to 20))

        assertEquals(listOf(SpeakerWindow(0, 0, 4 * RATE)), SpeakerSpans.windows(raw, vad))
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
        // MIN_WINDOW_SECONDS is MIN_OPEN_SECONDS: a window under it could never open a speaker or
        // confirm one, so cutting it costs an embedding and buys a window that can only inherit.
        // Two one-second sentences therefore share the first window; the two-second ones do not.
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 12 * RATE, 0, 12 * RATE)))
        val raw = whisperRaw(
            listOf(0 to 100, 100 to 200, 200 to 400, 400 to 600, 600 to 1_200),
            List(5) { it * 10 to it * 10 + 10 },
        )

        assertEquals(
            listOf(
                SpeakerWindow(0, 0, 32_000),         // 1 s + 1 s, coalesced
                SpeakerWindow(0, 32_000, 64_000),    // 2 s
                SpeakerWindow(0, 64_000, 96_000),    // 2 s
                SpeakerWindow(0, 96_000, 12 * RATE), // 6 s
            ),
            SpeakerSpans.windows(raw, vad),
        )
    }

    @Test
    fun aTrailingRemainderTooShortToStandAloneJoinsTheWindowBeforeIt() {
        // Half a second of "yeah" at the end of a long segment is not a window. Left alone it
        // would be an embedding the tracker's MIN_OPEN gate then refuses to act on — the cost of
        // a decision with the decision removed.
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
        val vad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 48_000, 0, 48_000)))
        val (bytes, ranges) = encode(" one", " two", ", three")
        val raw = whisperRaw(listOf(0 to 100, 100 to 200, 200 to 300), ranges)

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

        // …and the mirror: the SAME two sentences inside a four-second segment are one window and
        // therefore still one span. The split is the only thing that separated them.
        val shortVad = SpeakerSpans.vadSegments(vadRaw(VadSeg(0, 4 * RATE, 0, 4 * RATE)))
        val shortRaw = whisperRaw(listOf(0 to 180, 190 to 400), ranges)
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
            SpeakerSpans.spans(raw, bytes, vad, windows),
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
}
