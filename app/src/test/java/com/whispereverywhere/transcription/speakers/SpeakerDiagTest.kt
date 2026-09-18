package com.whispereverywhere.transcription.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [SpeakerDiag] — the ONE line per committed chunk that the 4.10 device session reads (plan
 * Task 3's spike, plan Task 4's thresholds).
 *
 * This is a formatter with a test because the line is an INSTRUMENT, not a log: Task 4 sets
 * `T_SAME` / `T_NEW` from the `best=` column and passes or fails CAM++ on the `embedMs=` one. A
 * column that shifts, loses a row, or renders `0.31` as `0,31` on a German device is a
 * measurement session that has to be run again.
 *
 * Two properties are asserted harder than the rest:
 *
 *  - **The columns are parallel.** `ids=`, `best=` and `dur=` carry one entry per VAD segment, in
 *    chunk order, always — including the segments that were never fingerprinted, which is where a
 *    skipping implementation would silently misalign the table.
 *  - **Not one character of transcript.** The whole feature is about text, and this is the only
 *    thing it prints. The line is numbers, brackets and its own field names; the assertion below
 *    is over the *inputs* a chunk carries, because the type makes text unreachable — there is no
 *    text in [SpeakerAssignment] at all, and that is the design.
 */
class SpeakerDiagTest {

    private fun assignment(
        seq: Long = 12L,
        ids: List<Int> = listOf(1, 1, 2),
        confirmed: Boolean = true,
        embedMs: Long = 214L,
        best: List<Float> = listOf(Float.NaN, 0.881f, 0.313f),
        durationsSec: List<Float> = listOf(3.24f, 1.06f, 2.4f),
        includesModelLoad: Boolean = false,
        remaps: Map<Int, Int> = emptyMap(),
    ) = SpeakerAssignment(
        seq = seq,
        ids = ids,
        confirmed = confirmed,
        stats = SpeakerAssignStats(
            embedMs = embedMs,
            best = best,
            durationsSec = durationsSec,
            includesModelLoad = includesModelLoad,
        ),
        remaps = remaps,
    )

    @Test
    fun theLineIsTheSpikesOwnShapeFieldForField() {
        assertEquals(
            "speaker: seq=12 segs=3 embedMs=214 ids=[1,1,2] best=[-,0.88,0.31] dur=[3.2,1.1,2.4] confirmed=1 load=0",
            SpeakerDiag.line(assignment()),
        )
    }

    @Test
    fun itIsGREPPABLEByOnePrefixAndTheDeviceSessionGrepsForThat() {
        assertTrue(SpeakerDiag.line(assignment()).startsWith(SpeakerDiag.PREFIX))
        assertEquals("speaker:", SpeakerDiag.PREFIX)
    }

    @Test
    fun segsIsTheSEGMENTCountAndTheThreeColumnsAreParallelToIt() {
        val line = SpeakerDiag.line(
            assignment(ids = listOf(1), best = listOf(0.9f), durationsSec = listOf(1.5f)),
        )
        assertTrue(line, "segs=1" in line)
        assertTrue(line, "ids=[1]" in line)
        assertTrue(line, "best=[0.90]" in line)
        assertTrue(line, "dur=[1.5]" in line)
    }

    @Test
    fun aChunkWithNoSegmentsRendersEmptyColumnsRatherThanADash() {
        val line = SpeakerDiag.line(
            assignment(ids = emptyList(), best = emptyList(), durationsSec = emptyList()),
        )
        assertTrue(line, "segs=0" in line)
        assertTrue(line, "ids=[]" in line)
        assertTrue(line, "best=[]" in line)
        assertTrue(line, "dur=[]" in line)
    }

    @Test
    fun anUnfingerprintedSegmentRendersADashSoItCannotBeReadAsASimilarityOfZero() {
        // 0.00 is a real, meaningful similarity (two orthogonal voices); "never measured" is not
        // the same reading and must not borrow its glyph.
        val line = SpeakerDiag.line(assignment(best = listOf(Float.NaN, 0f, Float.NEGATIVE_INFINITY)))
        assertTrue(line, "best=[-,0.00,-]" in line)
    }

    @Test
    fun theOneChunkThatPaidTheModelLoadSaysSoSoItIsNotReadAsCamPlusBeingSlow() {
        // The flag is after `confirmed=`, so anything reading the plan's eight fields positionally
        // is unaffected by its existence. It is last on every chunk that merged nothing, which is
        // almost all of them; `remaps=` goes after it on the rest.
        assertTrue(SpeakerDiag.line(assignment(includesModelLoad = true)).endsWith(" load=1"))
        assertTrue(SpeakerDiag.line(assignment(includesModelLoad = false)).endsWith(" load=0"))
        assertTrue("confirmed= stays the eighth field", "confirmed=1 load=" in SpeakerDiag.line(assignment()))
    }

    @Test
    fun theLatchIsONEOrZERONeverTrueOrFalse() {
        assertTrue("confirmed=1" in SpeakerDiag.line(assignment(confirmed = true)))
        assertTrue("confirmed=0" in SpeakerDiag.line(assignment(confirmed = false)))
        assertFalse("true" in SpeakerDiag.line(assignment(confirmed = true)))
    }

    @Test
    fun theNumbersAreRENDEREDWithTheROOTLocaleWhateverTheDeviceIs() {
        // The Tab and the Fold6 are the owner's devices and could be set to any locale; a
        // comma decimal separator would make every `best=` and `dur=` column unparseable, and
        // `String.format` without an explicit Locale takes the DEFAULT one.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val line = SpeakerDiag.line(assignment())
            assertTrue(line, "best=[-,0.88,0.31]" in line)
            assertTrue(line, "dur=[3.2,1.1,2.4]" in line)
            assertFalse("no comma decimals: $line", "0,88" in line)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun thereIsNoSPACEInsideAColumnSoOneLineIsOneRecordForAWhitespaceSplit() {
        val fields = SpeakerDiag.line(assignment()).split(" ")
        assertEquals(9, fields.size)
        for (field in fields.drop(1)) {
            assertTrue("every field is key=value: $field", field.count { it == '=' } == 1)
        }
        // …and ten on a chunk that merged, with the same rule holding for the new column.
        val merged = SpeakerDiag.line(assignment(remaps = mapOf(3 to 1, 5 to 2))).split(" ")
        assertEquals(10, merged.size)
        for (field in merged.drop(1)) {
            assertTrue("every field is key=value: $field", field.count { it == '=' } == 1)
        }
    }

    @Test
    fun aMergeIsRenderedAsArrowsLASTAndOnlyOnTheChunksThatHadOne() {
        // The refine half of spike session 2. `3>1` reads "everything already labelled speaker 3
        // was speaker 1", which is the correction a reader has to apply to `ids=` on this line AND
        // to earlier lines — so it has to be greppable and it has to be unambiguous about the
        // direction. `>` rather than `->` because no column here contains a space or a dash, and
        // `-` already means "not measured" in the `best=` column beside it.
        val merged = SpeakerDiag.line(assignment(remaps = mapOf(3 to 1, 5 to 2)))
        assertTrue(merged, merged.endsWith(" remaps=[3>1,5>2]"))
        assertTrue("…and it comes after load=, so the plan's eight fields stay positional", "load=0 remaps=" in merged)

        // Absent, not empty, on the overwhelming majority of chunks: eighteen `remaps=[]` per
        // session would bury the one line worth reading.
        val quiet = SpeakerDiag.line(assignment())
        assertFalse(quiet, "remaps" in quiet)
        assertTrue(quiet, quiet.endsWith(" load=0"))
    }
}
