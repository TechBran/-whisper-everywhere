package com.whispereverywhere.transcription

import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 3.7 Workstream F: the engine must read the native cost counters for the segment it just ran,
 * exactly once, AFTER the transcribe that produced them.
 *
 * REAL single-thread executor throughout — the ordering property lives on the engine's background
 * thread, and a same-thread stub would prove nothing about it.
 *
 * WHAT NO TEST IN THIS CLASS CAN SEE: the emit is `android.util.Log.i`, which this build stubs to
 * a no-op (`unitTests.isReturnDefaultValues = true`), so the STRING the call site produces is
 * invisible here. Everything below is about WHICH backend calls happened and IN WHAT ORDER. The
 * complementary half — that the value read is the value printed, rather than read and discarded —
 * is source-anchored in `SegmentTimingTest.theStatsTheEngineReadsAreTheStatsItPrints_notDiscarded`.
 * Neither half is sufficient alone; a reviewer must read them as one pin.
 */
class LocalWhisperEngineStatsTest {

    private val pcm = byteArrayOf(0x10, 0x00, 0x20, 0x00)

    private fun fastRetry() = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    /** Records the ORDER of native touches so "stats after transcribe" is provable, not assumed. */
    private class OrderRecordingBackend(private val transcribeThrows: Boolean = false) : WhisperBackend {
        val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        override fun load(modelPath: String): Long = 42L

        // DEPENDS ON A DEFAULT: the engine calls transcribeStreaming, which this fake does NOT
        // override — WhisperBackend.transcribeStreaming's interface default delegates to
        // transcribe(...) (TranscriptionEngine.kt, "= transcribe(ctx, samples, lang, useVad)").
        // Named so that if that default ever stops delegating, this fake fails loudly here
        // instead of silently recording zero transcribes and turning the order assertions green
        // for the wrong reason.
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
            events += "transcribe(ctx=$ctx)"
            if (transcribeThrows) throw RuntimeException("native transcribe failed")
            return " hello"
        }

        override fun lastSegmentStats(ctx: Long): NativeSegmentStats? {
            events += "stats(ctx=$ctx)"
            return NativeSegmentStats(ctxFrames = 512, vadInSamples = 38_400, vadOutSamples = 32_000)
        }

        override fun release(ctx: Long) = Unit
    }

    private class LatchListener(resolutions: Int) : TranscriptionEngine.Listener {
        val done = CountDownLatch(resolutions)
        val opened = CountDownLatch(1)
        val resolved: MutableList<Long> = java.util.Collections.synchronizedList(mutableListOf())
        val outcomes: MutableList<SegmentOutcome> = java.util.Collections.synchronizedList(mutableListOf())
        override fun onOpen() { opened.countDown() }
        override fun onDelta(text: String) = Unit
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            resolved += seq
            outcomes += outcome
            done.countDown()
        }
        override fun onError(message: String) = Unit
        override fun onClosed() = Unit
    }

    private fun engineOn(
        backend: WhisperBackend,
        executor: java.util.concurrent.ExecutorService,
    ) = LocalWhisperEngine(
        modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
        retry = fastRetry(),
        backend = backend,
        executor = executor,
    )

    @Test
    fun statsAreQueriedOncePerSegment_afterTheTranscribeThatProducedThem() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val backend = OrderRecordingBackend()
            val engine = engineOn(backend, executor)
            val listener = LatchListener(resolutions = 1)
            engine.connect(language = "en", listener = listener)
            assertTrue("engine never opened", listener.opened.await(5, TimeUnit.SECONDS))

            engine.sendAudio(pcm)
            engine.commit()
            assertTrue("segment never resolved", listener.done.await(5, TimeUnit.SECONDS))

            // The ORDER is the contract: the counters describe the call that just finished, so a
            // read before it would report the PREVIOUS segment's encoder cost. The ctx in the
            // recorded event is the one the engine passed, so a read against any other handle
            // (0L, a literal) shows up here as a different string rather than as silence.
            assertEquals(listOf("transcribe(ctx=42)", "stats(ctx=42)"), backend.events)
            assertEquals(listOf(0L), listener.resolved)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun everySegmentGetsItsOwnStatsQuery_neverOneForTheWholeSession() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val backend = OrderRecordingBackend()
            val engine = engineOn(backend, executor)
            val listener = LatchListener(resolutions = 3)
            engine.connect(language = "en", listener = listener)
            assertTrue(listener.opened.await(5, TimeUnit.SECONDS))

            repeat(3) { engine.sendAudio(pcm); engine.commit() }
            assertTrue("segments never drained", listener.done.await(5, TimeUnit.SECONDS))

            assertEquals(
                listOf(
                    "transcribe(ctx=42)", "stats(ctx=42)",
                    "transcribe(ctx=42)", "stats(ctx=42)",
                    "transcribe(ctx=42)", "stats(ctx=42)",
                ),
                backend.events,
            )
            assertEquals(listOf(0L, 1L, 2L), listener.resolved)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aThrownTranscribeNeverQueriesTheCounters() {
        // The capture follows the SUCCESSFUL return path only, and must NOT be hoisted into a
        // finally. On a throw the counters are, correctly, whatever the previous transcribe left —
        // the backend seam invalidates them, so the honest answer is null and the honest line is
        // no line at all. A capture in a finally would run here, and would ALSO pair a
        // segment-timing line with a segment that produced no transcript, which is exactly the
        // "why does this segment have a timing line but no text" confusion the workstream removes.
        // Nothing else in the suite covers this: the emit is a JVM no-op, so the extra line is
        // invisible and only the extra BACKEND CALL recorded below gives the mutation away.
        val executor = Executors.newSingleThreadExecutor()
        try {
            val backend = OrderRecordingBackend(transcribeThrows = true)
            val engine = engineOn(backend, executor)
            val listener = LatchListener(resolutions = 1)
            engine.connect(language = "en", listener = listener)
            assertTrue(listener.opened.await(5, TimeUnit.SECONDS))

            engine.sendAudio(pcm)
            engine.commit()
            assertTrue("segment never resolved", listener.done.await(5, TimeUnit.SECONDS))

            assertEquals(
                "a transcribe that THREW must leave no stats query behind it",
                listOf("transcribe(ctx=42)"),
                backend.events,
            )
            // And the throw still resolves the seq exactly once, as Lost — unchanged from 3.6.0.
            assertEquals(listOf(0L), listener.resolved)
            assertTrue(
                "a thrown transcribe is a LOST segment, not an empty one: whisper reached no " +
                    "verdict about this audio. Got: ${listener.outcomes}",
                listener.outcomes.single() is SegmentOutcome.Lost,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aBackendWithNoCountersStillResolvesNormally() {
        // The default lastSegmentStats returns null; the engine must log a stats-free line and
        // resolve exactly as it did in 3.6.0. This is also the null-after-success shape in
        // production (an interleaved batch chunk re-tagged the slot between transcribe and read):
        // omit the fields, never warn, never assert, never retry the read.
        val executor = Executors.newSingleThreadExecutor()
        try {
            val backend = object : WhisperBackend {
                override fun load(modelPath: String): Long = 7L
                override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean) = " plain"
                override fun release(ctx: Long) = Unit
            }
            val engine = engineOn(backend, executor)
            val listener = LatchListener(resolutions = 1)
            engine.connect(language = "en", listener = listener)
            assertTrue(listener.opened.await(5, TimeUnit.SECONDS))

            engine.sendAudio(pcm)
            engine.commit()
            assertTrue(listener.done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(0L), listener.resolved)
            assertEquals(listOf(SegmentOutcome.Text("plain")), listener.outcomes)
        } finally {
            executor.shutdownNow()
        }
    }
}
