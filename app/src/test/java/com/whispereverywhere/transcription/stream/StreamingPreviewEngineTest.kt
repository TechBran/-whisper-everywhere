package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.SameThreadExecutorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The previewer's loop over the scripted recognizer, with the measurements as fixtures: the
 * canary's four partials (rung 3 §5.3 — `ONE`, `ONE TWO THREE`, `… FOUR`, `… FIVE`, the last
 * from the pad), the T=45 / 320 ms decode cadence, the leading-space tokens, the 500 ms pad.
 * Executor: SameThreadExecutorService (LocalWhisperEngineTest's), so every posted task runs
 * inline and the assertions read a settled engine.
 */
class StreamingPreviewEngineTest {

    private class ManualExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private companion object {
        const val CANARY = "ONE TWO THREE FOUR FIVE"
        val CANARY_PARTIALS = listOf("ONE", "ONE TWO THREE", "ONE TWO THREE FOUR", "ONE TWO THREE FOUR FIVE")
    }

    private var now = 0L
    private val logs = mutableListOf<String>()
    private val emitted = mutableListOf<String>()
    private val dir = File("unused")
    private val pack = StreamingPackCatalog.EN

    private fun engine(
        rec: PreviewRecognizer?,
        executor: ExecutorService = SameThreadExecutorService(),
        clip: FloatArray? = FloatArray(40_960),
        factory: PreviewRecognizerFactory = ScriptedFactory(rec),
        capacity: Int = StreamingPreviewTuning.QUEUE_CAPACITY,
        onLoadFailure: () -> Unit = {},
    ) = StreamingPreviewEngine(
        factory = factory, canaryClip = { clip }, onLoadFailure = onLoadFailure, executor = executor,
        clock = { now }, nanoClock = { 0L }, queueCapacity = capacity, log = { logs += it }, enterExecutorThread = {},
    )

    private fun warmOpen(rec: PreviewRecognizer, executor: ExecutorService = SameThreadExecutorService(), capacity: Int = StreamingPreviewTuning.QUEUE_CAPACITY): StreamingPreviewEngine {
        val e = engine(rec, executor, capacity = capacity)
        e.warm(dir, pack)
        e.open { emitted += it }
        return e
    }

    /** 32 ms of PCM per chunk, the clock advanced BEFORE the send (the frame's own instant). */
    private fun feedMs(e: StreamingPreviewEngine, ms: Int) {
        repeat(ms / 32) { now += 32; e.sendAudio(ByteArray(1024)) }
    }

    // ------------------------------------------------------------- warm + canary

    @Test fun warmLoadsWithTwoThreadsRunsTheCanaryAndLogsTheOpenLine() {
        val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY)
        val factory = ScriptedFactory(rec)
        val e = engine(rec, factory = factory)
        e.warm(dir, pack)
        assertEquals(2, factory.lastThreads)
        assertTrue(e.isWarm())
        assertFalse(e.disabled)
        assertEquals(
            "stream-open: sherpa=1.13.7 ort=1.27.1 threads=2 provider=cpu loadMs=0 canary=pass canaryMs=0 outLen=23 load=ok",
            logs.single(),
        )
        assertTrue("the canary's throwaway stream is released", rec.streams.single().released)
        assertFalse(rec.released)
        e.warm(dir, pack)
        assertEquals("warm is idempotent", 1, factory.loads)
    }

    @Test fun aFailedCanaryDisablesForTheProcessAndReleasesTheRecognizer() {
        val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = "")
        val factory = ScriptedFactory(rec)
        val e = engine(rec, factory = factory)
        e.warm(dir, pack)
        assertTrue(e.disabled)
        assertFalse(e.isWarm())
        assertTrue(rec.released)
        assertTrue(logs.single().contains(" canary=fail canaryMs=0 outLen=0 load=ok"))
        e.warm(dir, pack)
        assertEquals("a disabled previewer never reloads in this process", 1, factory.loads)
    }

    @Test fun aMissingClipIsNoVerdictAndTheProcessStaysOff() {
        val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY)
        val e = engine(rec, clip = null)
        e.warm(dir, pack)
        assertTrue(e.disabled)
        assertTrue(logs.single().contains(" canary=none canaryMs=0 outLen=0 load=ok"))
    }

    @Test fun aLoadFailureDisablesAndReportsTheCorruption() {
        var corrupt = 0
        val e = engine(null, factory = ScriptedFactory(null, throwAtLoad = true), onLoadFailure = { corrupt++ })
        e.warm(dir, pack)
        assertTrue(e.disabled)
        assertEquals(1, corrupt)
        assertTrue(logs.single().endsWith(" canary=skipped canaryMs=0 outLen=0 load=fail"))
    }

    // ------------------------------------------------------------- the feed and the partials

    @Test fun partialsArriveLowercasedDedupedAndPrefixGrowingAtTheDecodeCadence() {
        val rec = ScriptedRecognizer(CANARY_PARTIALS, canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 2_560)
        // First decode at 7,200 samples (chunk 15), then every 5,120 (chunks 25, 35, 45, …): the
        // text changes on the first four decodes and repeats on the rest — repeats are not partials.
        assertEquals(listOf("one", "one two three", "one two three four", "one two three four five"), emitted)
        val s = rec.streams[1]
        assertEquals("one accept per 32 ms chunk", 80, s.fed.size)
        assertEquals("decode only while ready: 1 + (40,960 − 7,200) / 5,120", 7, s.decodes)
    }

    @Test fun commitPadsFinishesDrainsFreezesTheFinalTextAndRecreatesTheStream() {
        val rec = ScriptedRecognizer(CANARY_PARTIALS, canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 2_560)
        var frozen: Pair<Long, String>? = null
        e.commit(seq = 0L, retainMs = 0L) { seq, text -> frozen = seq to text }
        assertEquals(0L to "one two three four five", frozen)
        val s1 = rec.streams[1]
        assertEquals("the pad is exactly PAD_MS of zeros", 8_000, s1.fed.last())
        assertTrue(s1.finished)
        assertTrue("the padded stream is released, never reset", s1.released)
        assertEquals("canary + session + the fresh stream", 3, rec.streams.size)
        assertFalse(rec.streams[2].released)
        assertEquals("the pad's decodes: 1 + (48,960 − 7,200) / 5,120", 9, s1.decodes)
        assertEquals(
            "stream-timing: seq=0 audio=2560 decodes=9 decodeMs=0 p50us=0 p99us=0 rtf=0.000 partials=4 firstPartialMs=480 padMs=500 shed=0 retract=0",
            logs.last(),
        )
        assertEquals("the freeze itself emits no partial — the composer speaks for it", 4, emitted.size)
    }

    @Test fun aRetainedTailIsTrimmedFromTheFrozenTextAndRefedToTheFreshStream() {
        val rec = ScriptedRecognizer(
            listOf("HELLO THERE FRIEND"),
            timestampsOf = { floatArrayOf(0.5f, 1.0f, 1.5f) },
            canaryText = CANARY,
        )
        val e = warmOpen(rec)
        feedMs(e, 2_048)
        var frozen = ""
        e.commit(seq = 3L, retainMs = 800L) { _, text -> frozen = text }
        // cut = (2,048 − 800) / 1000 = 1.248 s: HELLO (0.5) and THERE (1.0) stay, FRIEND (1.5) goes.
        assertEquals("hello there", frozen)
        val fresh = rec.streams[2]
        assertEquals("the ring's last 800 ms (12,800 samples) re-fed in one accept", listOf(12_800), fresh.fed)
        assertEquals("hello there friend", emitted.last())
    }

    @Test fun theRefedTailIsTheStreamsAudioNotTheNextSegmentsAudioOrFirstPartial() {
        // (review B1) The tail has to be in the CUT's timeline — cutSeconds measures against it —
        // but it is audio the line just logged already reported, so it is not this segment's.
        val rec = ScriptedRecognizer(listOf("A", "A B", "A B C", "A B C D"), canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 2_048)
        e.commit(seq = 1L, retainMs = 800L) { _, _ -> }
        assertEquals(
            "stream-timing: seq=1 audio=2048 decodes=7 decodeMs=0 p50us=0 p99us=0 rtf=0.000 partials=4 firstPartialMs=480 padMs=500 shed=0 retract=0",
            logs.last(),
        )
        assertEquals("the re-fed tail's echo still reaches the strip", "a b", emitted.last())
        feedMs(e, 1_024)
        e.commit(seq = 2L, retainMs = 0L) { _, _ -> }
        // 1,024 ms of NEW audio (not 1,824), two partials of its own (not three), and the lag of
        // the first of those (not the 0 the echo would have written) — the sheet's rtf and Z1 row.
        assertEquals(
            "stream-timing: seq=2 audio=1024 decodes=6 decodeMs=0 p50us=0 p99us=0 rtf=0.000 partials=2 firstPartialMs=320 padMs=500 shed=0 retract=0",
            logs.last(),
        )
    }

    @Test fun queueOverflowShedsTheChunkAndSaysSoOnTheTimingLine() {
        val rec = ScriptedRecognizer(listOf("A"), canaryText = CANARY)
        val exec = ManualExecutor()
        val e = warmOpen(rec, executor = exec, capacity = 2)
        exec.runAll()
        repeat(3) { e.sendAudio(ByteArray(1024)) }   // the executor is starved: the third chunk has nowhere to go
        exec.runAll()
        assertEquals(2 * 512L, rec.streams[1].samples)
        e.commit(0L, 0L) { _, _ -> }
        exec.runAll()
        assertTrue(logs.last().contains(" shed=1 "))
    }

    @Test fun aRetractionIsCountedNeverCorrected() {
        val rec = ScriptedRecognizer(listOf("ONE TWO", "ONE THREE"), canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 1_024)
        assertEquals("the strip shows what the model now says", listOf("one two", "one three"), emitted)
        e.commit(0L, 0L) { _, _ -> }
        assertTrue(logs.last().endsWith(" retract=1"))
    }

    @Test fun theThrottleNeverBitesAtTheDecodeCadenceButThinsABurst() {
        // 320 ms between partials is over the 150 ms floor, so steady state emits every change.
        // Two changes inside one burst (the clock does not move) emit once and the second lands
        // on the next drain — nothing is lost, only thinned.
        val rec = ScriptedRecognizer(listOf("A", "A B", "A B C"), canaryText = CANARY)
        val e = warmOpen(rec)
        repeat(25) { e.sendAudio(ByteArray(1024)) }           // clock frozen: decodes 1 and 2 inside "one instant"
        assertEquals(listOf("a"), emitted)
        now += 200
        e.sendAudio(ByteArray(1024))
        assertEquals(listOf("a", "a b"), emitted)
    }

    // ------------------------------------------------------------- failure and lifecycle

    @Test fun threeConsecutiveDecodeFailuresDisableThePreviewerAndBlankTheStrip() {
        val rec = ScriptedRecognizer(listOf("A"), failDecodesFrom = 10, canaryText = CANARY)   // the canary's 9 decodes pass
        val e = warmOpen(rec)
        feedMs(e, 1_600)   // three fresh streams × 15 chunks to reach their first (throwing) decode
        assertTrue(e.disabled)
        assertFalse(e.isWarm())
        assertTrue(rec.released)
        assertEquals("", emitted.last())
        assertEquals(3, logs.count { it.startsWith("stream-preview: decode threw") })
    }

    @Test fun threeThrowsSpreadOverThreeSessionsDoNotDisableThePreviewer() {
        // (review B2) The count carries across the SEGMENTS of one session — a model throwing once
        // per segment must still disable — but a new session is a new verdict. Any session whose
        // last decode threw ends at a non-zero count (a stop mid-sentence is that shape), so
        // without the boundary three ordinary sessions would switch the feature off for the process.
        val rec = ScriptedRecognizer(listOf("A"), failDecodesFrom = 10, canaryText = CANARY)   // the canary's 9 pass
        val e = engine(rec)
        e.warm(dir, pack)
        repeat(3) {
            e.open { emitted += it }
            feedMs(e, 480)   // 15 chunks = 7,680 samples: exactly one decode in this session, and it throws
            e.close()
        }
        assertEquals(3, logs.count { it.startsWith("stream-preview: decode threw") })
        assertFalse("a session boundary clears the three-strike count (spec §7.1)", e.disabled)
        assertTrue("and the previewer is still warm for the next session", e.isWarm())
    }

    @Test fun closeReleasesTheStreamButNotTheRecognizer_releaseReleasesBoth() {
        val rec = ScriptedRecognizer(listOf("A"), canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 64)
        e.close()
        assertTrue(rec.streams[1].released)
        assertFalse(rec.released)
        assertTrue("still warm across sessions", e.isWarm())
        e.sendAudio(ByteArray(1024))
        assertEquals("audio after close is dropped", 2, rec.streams.size)
        e.release()
        assertTrue(rec.released)
        assertFalse(e.isWarm())
        assertFalse("release is not a verdict", e.disabled)
    }

    @Test fun aCommitBeforeWarmFreezesBlank() {
        val e = engine(ScriptedRecognizer(listOf("A")))
        var frozen: String? = null
        e.commit(7L, 0L) { _, text -> frozen = text }
        assertEquals("", frozen)
        assertNull(logs.lastOrNull())
    }
}
