package com.whispereverywhere.npu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NPU tier's sentence bounds, parsed out of the timestamp tokens the decoder is allowed to
 * emit again from 4.11 Task 3.
 *
 * WHY THIS IS A JVM TEST AND NOT A DEVICE TEST, in the same spirit as [NpuDecodePolicyTest]: the
 * parse is pure data in and pure data out, and the way it goes wrong is not a crash. A byte range
 * one token off cuts a word in half and hangs the second speaker's label on it; a centisecond
 * bound read off the wrong base puts every sentence of a 6 s chunk past the end of the audio and
 * the window splitter quietly gives the chunk back as one window — which is the 4.10.1 behaviour
 * this whole task exists to end. Neither shows up in an acceptance run as anything but "the labels
 * still look a bit wrong".
 *
 * [NpuSentences.of] takes its detokeniser as a lambda so this file never names
 * [WhisperBpeDecoder] — which would need the half-megabyte asset — and, more usefully, so the
 * fake below can RENDER anything it is handed. Every id that is not text comes back as `<id>`, so
 * a timestamp reaching the decoder is visible in the text rather than silently dropped the way
 * the real decoder drops it.
 */
class NpuSentencesTest {

    private val family = WhisperTokens.SMALL

    /** `<|x.xx|>` for a time in centiseconds. Whisper's slots are 0.02 s apart. */
    private fun ts(centiseconds: Int): Int = family.timestampBegin + centiseconds / 2

    /**
     * The fake detokeniser: the real one's CONTRACT (join the pieces, drop nothing silently) with
     * a visible marker for every id this test did not declare as text.
     */
    private val words = mapOf(
        1000 to "Hello",
        1001 to " there",
        1002 to " And",
        1003 to " you",
        1004 to " Grüße",   // 8 bytes, 6 chars — the UTF-8/char distinction, in one token
        1005 to " 日本",     // 7 bytes, 3 chars
    )

    private val decode: (IntArray) -> String = { ids ->
        ids.joinToString("") { words[it] ?: "<$it>" }
    }

    // ---------------------------------------------------------------- the ordinary stream

    /**
     * `<|0.00|> Hello there <|2.40|> <|2.40|> And you <|4.00|>` — whisper's own shape, where the
     * timestamp that closes a sentence is emitted again to open the next.
     */
    @Test
    fun aPairedStreamBecomesOneQuadPerSentence() {
        val tokens = intArrayOf(
            ts(0), 1000, 1001, ts(240),
            ts(240), 1002, 1003, ts(400),
        )
        val text = decode(intArrayOf(1000, 1001, 1002, 1003))
        assertEquals("the fixture's own text, so the byte ranges below are readable", "Hello there And you", text)

        val got = NpuSentences.of(tokens, family, decode)
        assertArrayEquals(
            "two sentences: [t0cs, t1cs, byteStart, byteEnd] each, byte offsets into the text the " +
                "same decode call produces. Got ${got.toList()}",
            intArrayOf(
                0, 240, 0, 11,
                240, 400, 11, 19,
            ),
            got,
        )
        // …and the ranges SLICE that text, which is the property the consumer actually depends on.
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals("Hello there", String(bytes, got[2], got[3] - got[2], Charsets.UTF_8))
        assertEquals(" And you", String(bytes, got[6], got[7] - got[6], Charsets.UTF_8))
        assertEquals("and they tile it exactly", bytes.size, got[got.size - 1])
    }

    /**
     * A single timestamp between sentences, rather than the repeated pair. Whisper emits the pair;
     * a greedy loop with no pair rule in it (this tier's — the stateful mask at
     * `whisper.cpp:6556-6575` is not implemented on the HTP side) can emit either, so the parse
     * has to read both and must not turn the second shape into one sentence.
     */
    @Test
    fun anUnrepeatedSeparatorOpensTheNextSentenceAtTheSameTime() {
        val tokens = intArrayOf(ts(0), 1000, ts(240), 1002, ts(400))
        assertArrayEquals(
            "the 2.40 closes the first sentence AND starts the second",
            intArrayOf(
                0, 240, 0, 5,
                240, 400, 5, 9,
            ),
            NpuSentences.of(tokens, family, decode),
        )
    }

    // ---------------------------------------------------------------- the ragged ends

    /**
     * An unterminated final sentence. Both halves of the rule, in one fixture each:
     *
     *  - its START is the last timestamp emitted before it — here the one that closed its
     *    predecessor, which is the only timestamp a sentence opened this way ever has;
     *  - its END is the CHUNK END, because there is no later timestamp to take: a timestamp
     *    arriving after the text would have closed the sentence rather than left it open.
     *
     * Reachable rather than hypothetical: the repetition cut at `qnn_asr.cpp:3353` shortens the
     * token array mid-sentence, and so does the token budget.
     */
    @Test
    fun anUnterminatedFinalSentenceStartsAtTheLastTimestampAndEndsAtTheChunkEnd() {
        val trailingText = intArrayOf(ts(0), 1000, ts(240), 1002, 1003)
        assertArrayEquals(
            "the trailing ' And you' takes 2.40 as its start and the chunk end as its end",
            intArrayOf(
                0, 240, 0, 5,
                240, NpuSentences.CHUNK_END_CS, 5, 13,
            ),
            NpuSentences.of(trailingText, family, decode),
        )

        val reopened = intArrayOf(ts(0), 1000, ts(240), ts(240), 1002, 1003)
        assertArrayEquals(
            "…and the same when the separator was repeated in whisper's own shape",
            intArrayOf(
                0, 240, 0, 5,
                240, NpuSentences.CHUNK_END_CS, 5, 13,
            ),
            NpuSentences.of(reopened, family, decode),
        )
    }

    /**
     * Text before any timestamp at all opens a sentence at 0.00 — "there is none" for the START
     * fallback. It is the mirror of the case above and it is reachable the same way: the
     * repetition cut and the entropy cut both keep a PREFIX, but the language-detect pass and a
     * rung retry can both leave the first emitted token a text one.
     */
    @Test
    fun textBeforeTheFirstTimestampStartsAtZero() {
        assertArrayEquals(
            intArrayOf(0, 240, 0, 5),
            NpuSentences.of(intArrayOf(1000, ts(240)), family, decode),
        )
    }

    /** An empty sentence — two timestamps with nothing between them — is not a sentence. */
    @Test
    fun aTimestampPairWithNoTextBetweenThemProducesNothing() {
        assertArrayEquals(
            "…and the text that follows still gets the LATER of the two as its start",
            intArrayOf(240, 400, 0, 5),
            NpuSentences.of(intArrayOf(ts(0), ts(240), 1000, ts(400)), family, decode),
        )
        assertArrayEquals(
            "a stream of nothing but timestamps is no sentences at all",
            IntArray(0),
            NpuSentences.of(intArrayOf(ts(0), ts(240), ts(400)), family, decode),
        )
    }

    // ---------------------------------------------------------------- the old shape

    /**
     * A stream with no timestamp tokens answers EMPTY — the shape the tier produced for its whole
     * life before this task, and the one `SpeakerAssigner`'s VAD route reads as "label the chunk
     * as a whole". It must stay reachable: a decode that terminated on EOT before its first
     * timestamp, an older `.so`, or a device where the un-suppression did not take.
     */
    @Test
    fun aStreamWithNoTimestampsAnswersEmptyRatherThanOneSentenceOverEverything() {
        assertArrayEquals(
            IntArray(0),
            NpuSentences.of(intArrayOf(1000, 1001, 1002, 1003), family, decode),
        )
        assertArrayEquals("and an empty decode is empty too", IntArray(0), NpuSentences.of(IntArray(0), family, decode))
    }

    /**
     * THE DECODER IS FED TEXT TOKENS ONLY. The real one drops every id at or above EOT, so a
     * timestamp handed to it costs nothing visible — which is exactly why this is asserted against
     * a fake that renders the id instead.
     */
    @Test
    fun noTimestampAndNoSpecialEverReachesTheDecoder() {
        val seen = ArrayList<Int>()
        val spy: (IntArray) -> String = { ids -> seen += ids.toList(); decode(ids) }
        val tokens = intArrayOf(
            ts(0), 1000, family.noTimestamps, 1001, family.langToken("en"), ts(240),
            ts(240), 1002, family.sot, 1003, ts(400),
        )
        val got = NpuSentences.of(tokens, family, spy)

        assertTrue("the decoder was actually exercised", seen.isNotEmpty())
        assertTrue(
            "every id the decoder saw must be a TEXT id below EOT (${family.eot}); saw " +
                seen.filter { it >= family.eot },
            seen.all { it < family.eot },
        )
        assertFalse(
            "…so no marker for a special can appear in the text the offsets index into",
            decode(intArrayOf(1000, 1001, 1002, 1003)).contains('<'),
        )
        assertArrayEquals(
            "and the specials are invisible to the bounds as well as to the text",
            intArrayOf(
                0, 240, 0, 11,
                240, 400, 11, 19,
            ),
            got,
        )
    }

    // ---------------------------------------------------------------- bytes, not characters

    /**
     * The offsets are UTF-8 BYTE offsets, like every other pair in this app's geometry
     * (`WhisperNative.lastWhisperSegments`, `lastTokenTimes`). A char-offset parse agrees with a
     * byte-offset one for the whole of English and splits a German or Japanese sentence inside a
     * character.
     */
    @Test
    fun theOffsetsAreUtf8BytesRatherThanCharacters() {
        val tokens = intArrayOf(ts(0), 1004, ts(240), ts(240), 1005, ts(400))
        val text = decode(intArrayOf(1004, 1005))
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertTrue("the fixture must actually be multi-byte", bytes.size > text.length)

        val got = NpuSentences.of(tokens, family, decode)
        assertArrayEquals(
            "' Grüße' is 8 bytes (6 chars) and ' 日本' is 7 more (3 chars)",
            intArrayOf(
                0, 240, 0, 8,
                240, 400, 8, 15,
            ),
            got,
        )
        assertEquals(" Grüße", String(bytes, got[2], got[3] - got[2], Charsets.UTF_8))
        assertEquals(" 日本", String(bytes, got[6], got[7] - got[6], Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- the family, and the refusals

    /**
     * The timestamp BASE is the family's, exactly as every other id in this tier is. 50364 is
     * `<|0.00|>` under whisper-small and `<|notimestamps|>` under large-v3 — one id, two
     * meanings, both legal — so a parse wired to a fixed base reads large-v3's `<|0.00|>` as
     * 0.02 s and shifts every sentence in the chunk by one slot.
     */
    @Test
    fun theTimestampBaseIsTheFamilysRatherThanAConstant() {
        val largeV3 = WhisperTokens.LARGE_V3
        val tokens = intArrayOf(largeV3.timestampBegin, 1000, largeV3.timestampBegin + 120)
        assertArrayEquals(
            "under large-v3 the base is ${largeV3.timestampBegin}, so these are 0.00 and 2.40",
            intArrayOf(0, 240, 0, 5),
            NpuSentences.of(tokens, largeV3, decode),
        )
        assertArrayEquals(
            "…and under whisper-small the SAME array is one slot along, which is the whole point",
            intArrayOf(2, 242, 0, 5),
            NpuSentences.of(tokens, family, decode),
        )
    }

    /**
     * Timestamps that go BACKWARDS are a malformed stream, and the answer is EMPTY rather than a
     * best effort. Spec §3's rule is that everything fails downhill: no sentences means the chunk
     * keeps 4.10.1's whole-chunk label, which is wrong-but-coarse. Overlapping sentence bounds
     * instead would hand the window splitter ranges it cannot order, and the text would be cut at
     * a place no sentence ends.
     *
     * Reachable because nothing on this tier enforces whisper's increasing-timestamp mask
     * (`whisper.cpp:6588-6594`): that rule is stateful and the HTP loop's mask is static.
     */
    @Test
    fun aStreamWhoseTimestampsGoBackwardsAnswersEmpty() {
        assertArrayEquals(
            IntArray(0),
            NpuSentences.of(
                intArrayOf(ts(400), 1000, ts(600), ts(100), 1002, ts(200)),
                family,
                decode,
            ),
        )
    }

    /**
     * The quads are a flat stride-4 array — the same flattening as `lastVadSegments`,
     * `lastWhisperSegments` and `lastTokenTimes`, so `SpeakerSpans` parses one shape and not four.
     */
    @Test
    fun theAnswerIsAFlatStrideFourArrayLikeEveryOtherGeometryInTheApp() {
        assertEquals(4, NpuSentences.STRIDE)
        val got = NpuSentences.of(intArrayOf(ts(0), 1000, ts(240), ts(240), 1002, ts(400)), family, decode)
        assertEquals("two sentences, four ints each", 0, got.size % NpuSentences.STRIDE)
        assertEquals(8, got.size)
        for (i in 0 until got.size / NpuSentences.STRIDE) {
            val o = i * NpuSentences.STRIDE
            assertTrue("sentence $i: t1 >= t0", got[o + 1] >= got[o])
            assertTrue("sentence $i: byteEnd > byteStart", got[o + 3] > got[o + 2])
        }
    }

    /** 30.00 s — whisper's window, and the last slot its timestamp table can name. */
    @Test
    fun theChunkEndIsTheLastSlotTheTimestampTableCanName() {
        assertEquals(
            "1501 slots 0.02 s apart: <|0.00|> .. <|30.00|>, so the end is 3000 centiseconds",
            3000,
            NpuSentences.CHUNK_END_CS,
        )
        assertEquals(
            "…which is the same number the family's own table gives",
            (WhisperTokenFamily.TIMESTAMP_SLOTS - 1) * 2,
            NpuSentences.CHUNK_END_CS,
        )
    }
}
