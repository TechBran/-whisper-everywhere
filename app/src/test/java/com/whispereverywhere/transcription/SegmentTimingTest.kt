package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentTimingTest {

    @Test
    fun lineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "segment-timing: seq=0 audio=4000 transcribe=6000 rtf=1.50",
            SegmentTiming.line(seq = 0L, audioMs = 4_000L, transcribeMs = 6_000L),
        )
    }

    @Test
    fun rtfBelowOneMeansFasterThanRealTime() {
        assertEquals(
            "segment-timing: seq=3 audio=15000 transcribe=3000 rtf=0.20",
            SegmentTiming.line(seq = 3L, audioMs = 15_000L, transcribeMs = 3_000L),
        )
    }

    @Test
    fun rtfRoundsToTwoDecimals() {
        assertEquals(
            "segment-timing: seq=1 audio=3000 transcribe=1000 rtf=0.33",
            SegmentTiming.line(seq = 1L, audioMs = 3_000L, transcribeMs = 1_000L),
        )
    }

    @Test
    fun zeroAudioNeverDividesByZero() {
        // A degenerate commit (near-zero samples) must report a parseable line, not NaN/crash.
        assertEquals(
            "segment-timing: seq=7 audio=0 transcribe=500 rtf=0.00",
            SegmentTiming.line(seq = 7L, audioMs = 0L, transcribeMs = 500L),
        )
    }

    @Test
    fun audioMsConvertsSampleCountAt16kHz() {
        assertEquals(4_000L, SegmentTiming.audioMs(sampleCount = 64_000))
        assertEquals(1_000L, SegmentTiming.audioMs(sampleCount = 16_000))
        assertEquals(0L, SegmentTiming.audioMs(sampleCount = 0))
    }

    @Test fun formatIsLocaleIndependent() {
        val prior = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // comma decimal separator
            assertEquals(
                "segment-timing: seq=0 audio=4000 transcribe=6000 rtf=1.50",
                SegmentTiming.line(seq = 0L, audioMs = 4_000L, transcribeMs = 6_000L),
            )
        } finally {
            java.util.Locale.setDefault(prior)
        }
    }

    @Test fun rtfRoundsHalfUpNotTruncated() {
        // 2000/3000 = 0.666... -> 0.67 under HALF_UP; 0.66 under truncation.
        assertEquals(
            "segment-timing: seq=2 audio=3000 transcribe=2000 rtf=0.67",
            SegmentTiming.line(seq = 2L, audioMs = 3_000L, transcribeMs = 2_000L),
        )
    }

    @Test
    fun seqIsPrependedSoTheOldFieldOrderSurvivesVerbatim() {
        // The whole point of PREPENDING: the pre-3.7 substring is still present, unmodified, so
        // `findstr segment-timing` and every field-order parser written against 3.6.0 keep working.
        val line = SegmentTiming.line(seq = 12L, audioMs = 2_400L, transcribeMs = 1_100L)
        assertEquals(true, line.startsWith("segment-timing: seq=12 "))
        assertEquals(true, line.contains("audio=2400 transcribe=1100 rtf=0.46"))
    }

    /**
     * Reads a repo source file from the test's working directory — the locator
     * `CaptureThreadPolicyTest`, `NativeVadSourceContractTest` and `ProbeStatsTest` share,
     * replicated here in its minimal form. Line endings are normalized at this single read site
     * (the N1 lesson: `readText()` does not normalize, and a CRLF checkout silently defeats
     * anything anchored on a newline).
     */
    private fun source(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(java.io.File(dir, relative), java.io.File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /** How many LIVE (non-comment) lines of [scope] contain [needle]. */
    private fun liveLineCount(scope: String, needle: String): Int =
        scope.lineSequence().count { line ->
            val trimmed = line.trimStart()
            val commented = trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            !commented && line.contains(needle)
        }

    @Test
    fun theEngineForwardsItsOwnSegmentSeq_notALiteral() {
        // Source-anchored deliberately: the emit is `android.util.Log.i`, which the unit-test
        // build stubs to a no-op via `isReturnDefaultValues = true`, so NO JVM test can observe
        // the string this call site actually produces. Without this pin, replacing `seq = seq`
        // with `seq = 0L` keeps the whole suite green while every segment-timing line in a real
        // capture reports segment 0 — which silently destroys the join key that is the entire
        // point of Workstream F (endpoint:/perceived:/queue: all key on this seq). Verified by
        // mutation: that exact edit passed 480/480 in the transcription package before this test.
        val engine = source("src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt")
        assertEquals(
            "LocalWhisperEngine must keep exactly one SegmentTiming.line call site - a second " +
                "emitter would double-report the segment and break per-seq joins",
            1,
            liveLineCount(engine, "SegmentTiming.line("),
        )
        assertEquals(
            "the timing line must carry runSegment's OWN seq (`seq = seq`), never a literal: it " +
                "is the key endpoint:/perceived:/queue: join on",
            1,
            liveLineCount(engine, "seq = seq,"),
        )
    }
}
