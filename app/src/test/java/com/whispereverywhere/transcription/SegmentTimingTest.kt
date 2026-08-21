package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun nativeStatsAreAppendedAfterRtf_neverInterleaved() {
        assertEquals(
            "segment-timing: seq=4 audio=2400 transcribe=1100 rtf=0.46 " +
                "vadIn=38400 vadOut=32000 ctxFrames=512",
            SegmentTiming.line(
                seq = 4L,
                audioMs = 2_400L,
                transcribeMs = 1_100L,
                stats = NativeSegmentStats(ctxFrames = 512, vadInSamples = 38_400, vadOutSamples = 32_000),
            ),
        )
    }

    @Test
    fun withoutNativeStatsTheLineIsByteIdenticalToTheSeqOnlyForm() {
        // The 6 pinned assertions above pass `stats` implicitly as null. This states WHY that is
        // safe: a backend with no counters must produce the exact same bytes as before. To be
        // precise about the division of labour: the six EXPECTED-STRING LITERALS above are what
        // actually pin the bytes; this test pins only that the DEFAULT is null, i.e. that they
        // keep exercising the no-counters form after `stats` gained a default value.
        assertEquals(
            SegmentTiming.line(seq = 4L, audioMs = 2_400L, transcribeMs = 1_100L),
            SegmentTiming.line(seq = 4L, audioMs = 2_400L, transcribeMs = 1_100L, stats = null),
        )
    }

    @Test
    fun aVadThatFoundNoSpeechReportsVadOutZeroAndCtxFramesZero() {
        // The probe-vs-batch-filter disagreement signature: the endpointer said "utterance ended,
        // commit" and we_vad_filter found nothing, so whisper_full never ran.
        assertEquals(
            "segment-timing: seq=9 audio=1000 transcribe=40 rtf=0.04 " +
                "vadIn=16000 vadOut=0 ctxFrames=0",
            SegmentTiming.line(
                seq = 9L,
                audioMs = 1_000L,
                transcribeMs = 40L,
                stats = NativeSegmentStats(ctxFrames = 0, vadInSamples = 16_000, vadOutSamples = 0),
            ),
        )
    }

    @Test
    fun anAllZeroStatsStillPrintsTheWholeSuffix_becauseZerosAreAMeasurement() {
        // THE tidy-omit regression target. The ONLY thing that may drop the suffix is a null
        // (`lastSegmentStats` said "no transcribe ran on this ctx"). An all-zero reading is the
        // opposite fact — a transcribe ran and cost nothing, i.e. the energy gate / VAD found no
        // speech at all — and it is the single most diagnostic shape in this workstream. A reader
        // that "helpfully" omits a zeroed suffix makes that shape indistinguishable from a fake
        // backend, so the two forms are asserted to be DIFFERENT strings, not merely correct ones.
        val measured = SegmentTiming.line(
            seq = 5L,
            audioMs = 1_000L,
            transcribeMs = 20L,
            stats = NativeSegmentStats(ctxFrames = 0, vadInSamples = 0, vadOutSamples = 0),
        )
        assertEquals(
            "segment-timing: seq=5 audio=1000 transcribe=20 rtf=0.02 vadIn=0 vadOut=0 ctxFrames=0",
            measured,
        )
        assertNotEquals(
            "an all-zero measurement must NOT collapse to the no-counters form",
            SegmentTiming.line(seq = 5L, audioMs = 1_000L, transcribeMs = 20L, stats = null),
            measured,
        )
    }

    @Test
    fun theStatsTheEngineReadsAreTheStatsItPrints_notDiscarded() {
        // Source-anchored for the same reason as the seq pin above, and it closes a strictly
        // NASTIER hole. `LocalWhisperEngineStatsTest` proves the engine CALLS
        // backend.lastSegmentStats(ctx) in the right order — but the emit is `android.util.Log.i`,
        // a JVM no-op here, so a mutant that performs that call and then passes `stats = null`
        // (or drops the argument, falling back to the null default) satisfies every behavioural
        // assertion in that class while EVERY segment-timing line on a real device silently loses
        // its vadIn/vadOut/ctxFrames suffix. The read would be paid for and thrown away, and no
        // test in the suite would notice. Verified by mutation: exactly that edit is green
        // everywhere except here.
        val engine = source("src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt")
        assertEquals(
            "the timing line must be handed the stats the engine JUST READ (`stats = nativeStats`) " +
                "- reading the counters and then printing without them is invisible to every JVM " +
                "test and costs every device line its suffix",
            1,
            liveLineCount(engine, "stats = nativeStats"),
        )
        assertEquals(
            "and there must be exactly one read to hand over: `val nativeStats = " +
                "backend.lastSegmentStats(ctx)`. A second read is a second answer - the slot can " +
                "be re-tagged by an interleaved batch chunk between them, so the two calls can " +
                "legitimately disagree and the line would report whichever one won",
            1,
            liveLineCount(engine, "val nativeStats = backend.lastSegmentStats(ctx)"),
        )
    }

    @Test
    fun transcribeMsIsMeasuredBeforeTheCountersAreRead() {
        // Also invisible to every JVM test, and self-referential in a way that makes it hard to
        // spot by eye: with the two lines swapped the timing line reports a transcribe duration
        // that INCLUDES the diagnostic query annotating it. Two volatile reads is a small lie
        // today, but `lastSegmentStats` is a SEAM — any future backend may answer it over JNI or
        // behind a lock, and the number that goes stale is the one the tier-consolidation and GPU
        // decision gates read. Regex-anchored on BOTH sides (the N5 lesson): a raw indexOf would
        // happily match either name inside the comment block above them.
        val engine = source("src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt")
        val measure = Regex("""(?m)^[ \t]*val transcribeMs = """).find(engine)
        val read = Regex("""(?m)^[ \t]*val nativeStats = """).find(engine)
        assertTrue("the emit block must declare `val transcribeMs = ...` on a live line", measure != null)
        assertTrue("the emit block must declare `val nativeStats = ...` on a live line", read != null)
        assertTrue(
            "the elapsed time must be taken BEFORE the counters are read, so the diagnostic can " +
                "never inflate the number it is annotating",
            measure!!.range.first < read!!.range.first,
        )
    }

    @Test
    fun theKDocSampleLineShowsTheSuffixFields() {
        // The sample at the top of SegmentTiming.kt is what a reader greps for before writing a
        // parser, so it is documentation with a consumer. Drifting behind the emitter teaches the
        // wrong field list; F5 updated it in lockstep and this keeps it there.
        val timing = source("src/main/java/com/whispereverywhere/transcription/SegmentTiming.kt")
        val samples = timing.lineSequence().filter { it.contains("segment-timing: seq=<n>") }.toList()
        assertEquals(
            "SegmentTiming.kt must carry exactly one placeholder sample line (`segment-timing: " +
                "seq=<n> ...`) in its KDoc. Zero means the assertion below would pass vacuously; " +
                "two means one of them is drifting unnoticed. Found: $samples",
            1,
            samples.size,
        )
        assertEquals(
            "the KDoc sample must show the full 3.7 line, suffix included",
            " *     segment-timing: seq=<n> audio=<ms> transcribe=<ms> rtf=<x.xx> " +
                "vadIn=<n> vadOut=<n> ctxFrames=<n>",
            samples.single(),
        )
    }
}
