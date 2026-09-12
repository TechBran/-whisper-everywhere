package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/** The three greppable lines, byte-exact (SegmentTimingTest's discipline). Numbers and codes only — never text. */
class StreamDiagTest {

    @Test fun openLineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "stream-open: sherpa=1.13.7 ort=1.27.1 threads=2 provider=cpu loadMs=811 canary=pass canaryMs=212 outLen=23 load=ok warm=1",
            StreamDiag.openLine("1.13.7", "1.27.1", 2, 811L, "pass", 212L, 23, "ok", warm = true),
        )
    }

    @Test fun theLineSAYSWhenALanguageWentOffRatherThanLeavingItToBeInferred() {
        // (4.5.0 languages T1 review r1, B1) `load=ok` answers only "did the model load", and the
        // disable used to be invisible on it: a language that had just switched itself off for the
        // process printed a line ending `load=ok`, which is how the defect survived a device
        // session. `warm=` is the outcome, so a reader greps one field to tell a language that came
        // up from one that went off, whatever the canary code says.
        assertEquals(
            "stream-open: sherpa=1.13.7 ort=1.27.1 threads=2 provider=cpu loadMs=811 canary=none canaryMs=4 outLen=0 load=ok warm=0",
            StreamDiag.openLine("1.13.7", "1.27.1", 2, 811L, "none", 4L, 0, "ok", warm = false),
        )
        // And the row that B1 created: no verdict, no defect, the language IS up.
        assertEquals(
            "stream-open: sherpa=1.13.7 ort=1.27.1 threads=2 provider=cpu loadMs=811 canary=unscored canaryMs=0 outLen=0 load=ok warm=1",
            StreamDiag.openLine("1.13.7", "1.27.1", 2, 811L, "unscored", 0L, 0, "ok", warm = true),
        )
    }

    @Test fun timingLineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "stream-timing: seq=4 audio=2560 decodes=9 decodeMs=310 p50us=34000 p99us=46000 rtf=0.121 partials=4 firstPartialMs=480 padMs=500 shed=0 retract=0",
            StreamDiag.timingLine(4L, 2560L, 9, 310L, 34_000L, 46_000L, 0.1211, 4, 480L, 500L, false, 0),
        )
        assertEquals(
            "stream-timing: seq=0 audio=0 decodes=0 decodeMs=0 p50us=0 p99us=0 rtf=0.000 partials=0 firstPartialMs=-1 padMs=500 shed=1 retract=2",
            StreamDiag.timingLine(0L, 0L, 0, 0L, 0L, 0L, 0.0, 0, -1L, 500L, true, 2),
        )
    }

    @Test fun gateLineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "stream-gate: lang=en pack=1 cloud=0 batch=0 enabled=1 ready=1 -> preview=1",
            StreamDiag.gateLine("en", true, false, false, true, true, true),
        )
        assertEquals(
            "stream-gate: lang=auto pack=1 cloud=0 batch=0 enabled=1 ready=1 -> preview=0",
            StreamDiag.gateLine(null, true, false, false, true, true, false),
        )
    }

    @Test fun rtfIsZeroSafe() {
        assertEquals(0.0, StreamDiag.rtf(100L, 0L), 0.0)
        assertEquals(0.125, StreamDiag.rtf(320L, 2560L), 1e-9)
    }

    @Test fun percentileIsNearestRankOnASortedCopy() {
        val s = listOf(50L, 10L, 40L, 20L, 30L)
        assertEquals(30L, StreamDiag.percentileUs(s, 0.50))
        assertEquals(50L, StreamDiag.percentileUs(s, 0.99))
        assertEquals(10L, StreamDiag.percentileUs(s, 0.0))
        assertEquals(0L, StreamDiag.percentileUs(emptyList(), 0.5))
    }
}
