package com.whispereverywhere.transcription

import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstream D (3.6.0): native partial streaming through LocalWhisperEngine. Deltas are
 * PREVIEW-ONLY — the final-only commit contract is pinned here: committed text comes
 * exclusively from segment resolution regardless of what streamed. Reuses the shared fakes
 * from LocalWhisperEngineTest.kt (same package).
 */
class LocalWhisperEngineStreamingTest {

    private fun fastRetry() = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    private val pcm = byteArrayOf(0x10, 0x00, 0x20, 0x00)

    /** Streams [deltas] through onNewSegment (running [advance] between them), then returns [finalText]. */
    private class StreamingScriptBackend(
        private val deltas: List<String>,
        private val finalText: String,
        private val advance: () -> Unit = {},
    ) : WhisperBackend {
        override fun load(modelPath: String): Long = 42L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String = finalText
        override fun transcribeStreaming(
            ctx: Long,
            samples: FloatArray,
            lang: String?,
            useVad: Boolean,
            onNewSegment: (String) -> Unit,
        ): String {
            for (d in deltas) { onNewSegment(d); advance() }
            return finalText
        }
        override fun release(ctx: Long) = Unit
    }

    private fun engineWith(
        backend: WhisperBackend,
        clock: () -> Long,
        executor: java.util.concurrent.ExecutorService = SameThreadExecutorService(),
    ) = LocalWhisperEngine(
        modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
        retry = fastRetry(),
        backend = backend,
        executor = executor,
        deltaClock = clock,
    )

    @Test
    fun streamingDeltas_renderDuringInference_andStayPreviewOnly() {
        var now = 0L
        val backend = StreamingScriptBackend(
            deltas = listOf(" Hello", " Hello world"),
            finalText = " Hello world.",
            advance = { now += 200 },              // clear of the 150 ms throttle window
        )
        val engine = engineWith(backend, clock = { now })
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.commit()

        // Both deltas forwarded UNTRIMMED (preview), then the terminal blank clears the strip.
        assertEquals(listOf(" Hello", " Hello world", ""), listener.deltas)
        // FINAL-ONLY COMMIT, PINNED: committed text comes exclusively from the returned String
        // via segment resolution — exactly one Text outcome, cleaned; no delta was committed.
        assertEquals(listOf(0L to SegmentOutcome.Text("Hello world.")), listener.resolved)
    }

    @Test
    fun deltasInsideTheThrottleWindow_areSuppressed() {
        var now = 0L
        val backend = StreamingScriptBackend(
            deltas = listOf(" a", " ab", " abc"),
            finalText = " abc",
            advance = { now += 50 },               // 50 ms apart: only the first passes
        )
        val engine = engineWith(backend, clock = { now })
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.commit()

        assertEquals(listOf(" a", ""), listener.deltas)
        assertEquals(listOf("abc"), listener.completed)   // nothing lost: the final supersedes
    }

    @Test
    fun theThrottleResetsPerSegment_firstDeltaOfEachSegmentIsImmediate() {
        val backend = StreamingScriptBackend(
            deltas = listOf(" x"),
            finalText = " x",
            advance = {},                           // the clock NEVER advances
        )
        val engine = engineWith(backend, clock = { 0L })
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)

        engine.sendAudio(pcm); engine.commit()
        engine.sendAudio(pcm); engine.commit()

        // Without the per-segment reset the second segment's only delta would be swallowed.
        assertEquals(listOf(" x", "", " x", ""), listener.deltas)
    }

    @Test
    fun staleSessionDeltas_areDropped_byTheListenerIdentityGuard() {
        var now = 0L
        val executor = QueueingExecutorService()
        val backend = StreamingScriptBackend(
            deltas = listOf(" too late"),
            finalText = " too late",
            advance = { now += 200 },
        )
        val engine = engineWith(backend, clock = { now }, executor = executor)
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        executor.tasks[0].run()                     // the model-load task
        assertTrue(listener.opened)

        engine.sendAudio(pcm)
        engine.commit()                             // queued as tasks[1], NOT yet run
        engine.close()                              // session over: listener detached

        executor.tasks[1].run()                     // the transcribe outlives its session

        // The exact guard: `listener === myListener` in runSegment — deltas, the terminal
        // clear, and the resolution are ALL dropped for a detached session.
        assertTrue(listener.deltas.isEmpty())
        assertTrue(listener.resolved.isEmpty())
    }

    @Test
    fun aNonStreamingBackend_emitsNoDeltasAndNoClear() {
        // Default transcribeStreaming delegates to transcribe: byte-for-byte 3.5.0 callback
        // sequence — in particular no spurious "" clear for backends/fakes that never stream.
        val engine = engineWith(FakeWhisperBackend(text = "plain"), clock = { 0L })
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.commit()

        assertTrue(listener.deltas.isEmpty())
        assertEquals(listOf("plain"), listener.completed)
    }
}
