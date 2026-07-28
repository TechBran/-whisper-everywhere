package com.whispereverywhere.transcription

import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Runs every submitted task immediately on the calling thread so tests are synchronous. */
class SameThreadExecutorService : AbstractExecutorService() {
    @Volatile private var shutdown = false
    override fun execute(command: Runnable) { command.run() }
    override fun shutdown() { shutdown = true }
    override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
    override fun isShutdown(): Boolean = shutdown
    override fun isTerminated(): Boolean = shutdown
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

/**
 * Captures submitted tasks instead of running them, so a test can execute them in an order of its
 * choosing. Used to prove that a segment's identity travels with its PCM snapshot rather than with
 * the order the executor happens to run things in.
 */
class QueueingExecutorService : AbstractExecutorService() {
    val tasks = mutableListOf<Runnable>()
    @Volatile private var shutdown = false
    override fun execute(command: Runnable) { tasks.add(command) }
    override fun shutdown() { shutdown = true }
    override fun shutdownNow(): MutableList<Runnable> { shutdown = true; return mutableListOf() }
    override fun isShutdown(): Boolean = shutdown
    override fun isTerminated(): Boolean = shutdown
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

/** Scriptable backend: fails the first [failTimes] transcribe calls, then returns [text]. */
class FakeWhisperBackend(
    private val text: String = "hello world",
    private var failTimes: Int = 0,
    private val loadReturns: Long = 42L,
) : WhisperBackend {
    val loadCalls = mutableListOf<String>()
    val transcribeCalls = mutableListOf<Triple<Long, FloatArray, String?>>()
    var releaseCalls = 0
    override fun load(modelPath: String): Long { loadCalls.add(modelPath); return loadReturns }
    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String {
        transcribeCalls.add(Triple(ctx, samples, lang))
        if (failTimes > 0) { failTimes--; throw RuntimeException("transient transcribe failure") }
        return text
    }
    override fun release(ctx: Long) { releaseCalls++ }
}

/** Returns a text that identifies WHICH audio snapshot it was given. */
class SampleCountBackend : WhisperBackend {
    override fun load(modelPath: String): Long = 42L
    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String = "n${samples.size}"
    override fun release(ctx: Long) = Unit
}

/** One scripted outcome per transcribe call, in order. */
class ScriptedBackend(private val script: List<() -> String>) : WhisperBackend {
    private var i = 0
    override fun load(modelPath: String): Long = 42L
    override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String {
        val step = script[i.coerceAtMost(script.size - 1)]
        i++
        return step()
    }
    override fun release(ctx: Long) = Unit
}

/** Records every Listener callback for assertions. */
class RecordingListener : TranscriptionEngine.Listener {
    var opened = false
    var closed = false
    val deltas = mutableListOf<String>()
    val resolved = mutableListOf<Pair<Long, SegmentOutcome>>()
    val errors = mutableListOf<String>()
    override fun onOpen() { opened = true }
    override fun onDelta(text: String) { deltas.add(text) }
    override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) { resolved.add(seq to outcome) }
    override fun onError(message: String) { errors.add(message) }
    override fun onClosed() { closed = true }

    /** The text this listener was actually handed, in resolution order. */
    val completed: List<String>
        get() = resolved.mapNotNull { (_, outcome) -> (outcome as? SegmentOutcome.Text)?.text }
}

/** Trivial ModelPathProvider fake: returns [path] from installedModelPath(). */
class FakeModelPathProvider(private val path: String?) : ModelPathProvider {
    override fun installedModelPath(): String? = path
}

class LocalWhisperEngineTest {

    // Zero-delay retry policy so retries don't slow the suite.
    private fun fastRetry() = RetryPolicy(maxAttempts = 3, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    // 4 bytes PCM16 -> 2 samples; deterministic non-blank audio.
    private val pcm = byteArrayOf(0x10, 0x00, 0x20, 0x00)

    @Test
    fun connect_withNoInstalledModel_reportsError() {
        val backend = FakeWhisperBackend()
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider(null),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()

        engine.connect(language = "en", listener = listener)

        assertEquals(listOf("No speech model installed"), listener.errors)
        assertTrue(listener.completed.isEmpty())
        assertTrue(backend.loadCalls.isEmpty())
    }

    @Test
    fun connect_thenBufferAndCommit_emitsCompletedWithBackendText() {
        val backend = FakeWhisperBackend(text = "  hello world  ")
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()

        engine.connect(language = "en", listener = listener)
        assertTrue(listener.opened)
        assertEquals(listOf("/models/pro.bin"), backend.loadCalls)

        engine.sendAudio(pcm)
        engine.sendAudio(pcm)
        val seq = engine.commit()

        assertEquals(0L, seq)
        assertEquals(listOf(0L to SegmentOutcome.Text("hello world")), listener.resolved) // trimmed
        assertEquals(listOf("hello world"), listener.completed)
        assertTrue(listener.errors.isEmpty())
        assertTrue(listener.deltas.isEmpty())                     // never onDelta
        assertEquals(1, backend.transcribeCalls.size)
        // 8 PCM bytes buffered -> 4 float samples in one snapshot
        assertEquals(4, backend.transcribeCalls[0].second.size)
        assertEquals(42L, backend.transcribeCalls[0].first)       // cached ctx from load()
        assertEquals("en", backend.transcribeCalls[0].third)
    }

    @Test
    fun commit_withEmptyBuffer_doesNothing() {
        val backend = FakeWhisperBackend()
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)

        // No seq is allocated for a commit that cuts nothing, so nothing is owed a resolution.
        assertEquals(-1L, engine.commit())

        assertTrue(backend.transcribeCalls.isEmpty())
        assertTrue(listener.resolved.isEmpty())
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun commit_whenBackendReturnsBlank_resolvesEmptyExpected() {
        val backend = FakeWhisperBackend(text = "   ")
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        val seq = engine.commit()

        // A blank result used to emit NO callback at all. The seq now resolves — otherwise the
        // orderer's head stalls on it forever and every later segment is held with it.
        assertEquals(0L, seq)
        assertEquals(listOf(0L to SegmentOutcome.EmptyExpected), listener.resolved)
        assertTrue(listener.completed.isEmpty())   // still nothing typed for the user
        assertTrue(listener.errors.isEmpty())
        assertEquals(1, backend.transcribeCalls.size)
    }

    @Test
    fun commit_withTransientFailures_retriesThenCompletes() {
        // Fail twice, succeed on the 3rd attempt (maxAttempts = 3).
        val backend = FakeWhisperBackend(text = "recovered", failTimes = 2)
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.commit()

        assertEquals(listOf("recovered"), listener.completed)
        assertTrue(listener.errors.isEmpty())
        assertEquals(3, backend.transcribeCalls.size)   // 1 + 2 retries
    }

    @Test
    fun commit_withPermanentFailure_resolvesEmptyExpectedAndReportsError() {
        // Fail more times than maxAttempts (3) -> never succeeds.
        val backend = FakeWhisperBackend(text = "never", failTimes = 99)
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        val seq = engine.commit()

        // A terminal failure resolves EmptyExpected, NOT Lost — Lost renders as "[…]" in the
        // user's field, and onError mid-RECORDING is a no-op in the service, so this reproduces
        // pre-identity-work behaviour byte-for-byte (nothing typed) while still resolving the seq
        // exactly once. onError still fires so the failure is observable/loggable.
        assertEquals(0L, seq)
        assertEquals(
            listOf(0L to SegmentOutcome.EmptyExpected),
            listener.resolved,
        )
        assertTrue(listener.completed.isEmpty())
        assertEquals(listOf("transient transcribe failure"), listener.errors)
        assertEquals(3, backend.transcribeCalls.size)   // exactly maxAttempts, then give up
    }

    @Test
    fun close_emitsClosedAndClearsBuffer() {
        val backend = FakeWhisperBackend()
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.close()

        assertTrue(listener.closed)

        // After close, a commit with a stale buffer must not transcribe (buffer was cleared,
        // and the listener was detached) — and it must not claim a seq nobody will resolve.
        assertEquals(-1L, engine.commit())
        assertTrue(backend.transcribeCalls.isEmpty())
        assertTrue(listener.resolved.isEmpty())
    }

    // ===== Segment identity =====

    @Test
    fun commit_allocatesAMonotonicSeqPerSegment() {
        val backend = FakeWhisperBackend(text = "ok")
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)

        engine.sendAudio(pcm); val a = engine.commit()
        engine.sendAudio(pcm); val b = engine.commit()
        engine.sendAudio(pcm); val c = engine.commit()

        assertEquals(listOf(0L, 1L, 2L), listOf(a, b, c))
        assertEquals(listOf(0L, 1L, 2L), listener.resolved.map { it.first })
    }

    @Test
    fun connect_restartsSeqNumberingForTheNewSession() {
        val backend = FakeWhisperBackend(text = "ok")
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val first = RecordingListener()
        engine.connect(language = "en", listener = first)
        engine.sendAudio(pcm)
        assertEquals(0L, engine.commit())
        engine.close()

        // A fresh SegmentOrderer starts at head 0, so the engine must restart at 0 with it —
        // otherwise the new session's first segment looks like a late duplicate and is dropped.
        val second = RecordingListener()
        engine.connect(language = "en", listener = second)
        engine.sendAudio(pcm)
        assertEquals(0L, engine.commit())
        assertEquals(listOf(0L), second.resolved.map { it.first })
    }

    @Test
    fun seqTravelsWithTheAudioSnapshot_notWithExecutionOrder() {
        // commit() previously snapshotted the PCM under bufferLock but enqueued OUTSIDE it, while
        // being callable from the audio thread AND the main thread — so two callers could snapshot
        // A-then-B and enqueue B-then-A. Allocating seq inside the same lock makes ordering a
        // function of audio order. Here the tasks are deliberately RUN in reverse.
        val executor = QueueingExecutorService()
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = SampleCountBackend(),
            executor = executor,
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        executor.tasks[0].run()                 // the model-load task
        assertTrue(listener.opened)

        engine.sendAudio(byteArrayOf(0x10, 0x00))                             // 1 sample
        val first = engine.commit()
        engine.sendAudio(byteArrayOf(0x10, 0x00, 0x20, 0x00))                 // 2 samples
        val second = engine.commit()

        assertEquals(0L, first)
        assertEquals(1L, second)

        executor.tasks[2].run()   // run the SECOND segment first
        executor.tasks[1].run()

        // Each result still carries the seq of the audio it came from, so an orderer can put them
        // back in audio order no matter what order the executor delivered them in.
        assertEquals(
            listOf(1L to SegmentOutcome.Text("n2"), 0L to SegmentOutcome.Text("n1")),
            listener.resolved,
        )
    }

    // ===== The terminal-callback contract =====

    @Test
    fun everyCommittedSeqResolvesExactlyOnce_acrossMixedOutcomes() {
        val backend = ScriptedBackend(
            listOf(
                { "one" },                                        // text
                { "   " },                                        // blank
                { throw RuntimeException("boom") },               // terminal failure
            ),
        )
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            // maxAttempts = 1 so one commit consumes exactly one scripted step.
            retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)

        val seqs = (0 until 3).map { engine.sendAudio(pcm); engine.commit() }

        assertEquals(listOf(0L, 1L, 2L), seqs)
        assertEquals(
            listOf(
                0L to SegmentOutcome.Text("one"),
                1L to SegmentOutcome.EmptyExpected,
                // A terminal failure resolves EmptyExpected too (never Lost, which would type a
                // "[…]" marker into the user's field) — see runSegment's KDoc.
                2L to SegmentOutcome.EmptyExpected,
            ),
            listener.resolved,
        )
        // The terminal failure is still reported via onError, distinct from the ordinary blank.
        assertEquals(listOf("boom"), listener.errors)
        // Exactly once: no seq appears twice, none is missing.
        assertEquals(seqs.toSet(), listener.resolved.map { it.first }.toSet())
        assertEquals(seqs.size, listener.resolved.size)
    }

    @Test
    fun commit_whenContextIsNotLoaded_resolvesEmptyExpectedAndReportsError() {
        // load() returning 0 leaves ctxPtr == 0 with the listener still attached.
        val backend = FakeWhisperBackend(loadReturns = 0L)
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        assertFalse(listener.opened)
        assertEquals(1, listener.errors.size)   // connect-time failure still uses onError

        engine.sendAudio(pcm)
        val seq = engine.commit()

        // Not Lost (which renders "[…]" in the user's field) — EmptyExpected, matching
        // pre-identity-work behaviour (onError mid-RECORDING is a no-op in the service, so
        // nothing was ever typed for this failure), while still resolving the seq exactly once.
        assertEquals(0L, seq)
        assertEquals(listOf(0L to SegmentOutcome.EmptyExpected), listener.resolved)
        assertEquals(
            listOf("Failed to load speech model (may be corrupt - re-download)", "Speech model not loaded"),
            listener.errors,
        )
        assertTrue(backend.transcribeCalls.isEmpty())
    }

    @Test
    fun commit_withADegenerateRepetitionLoop_stillResolvesAsText_becauseTheQualityGateIsNotWired() {
        // SegmentQuality exists but is deliberately NOT wired into runSegment — see its KDoc: the
        // measured threshold rejects ordinary emphatic speech ("no no no ..." scores above the
        // gate) and must be recalibrated before it can be wired back in. Until then, even a
        // degenerate repetition loop must pass through untouched, same as any other text.
        val loop = "Thank you for watching. ".repeat(20)
        val backend = FakeWhisperBackend(text = loop)
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        val seq = engine.commit()

        assertEquals(0L, seq)
        assertEquals(listOf(0L to SegmentOutcome.Text(loop.trim())), listener.resolved)
        assertEquals(listOf(loop.trim()), listener.completed)
    }

    // ===== Lifecycle on the interface =====

    @Test
    fun awaitIdle_returnsTrueOnceQueuedWorkHasDrained() {
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
            retry = fastRetry(),
            backend = FakeWhisperBackend(),
            executor = SameThreadExecutorService(),
        )
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(pcm)
        engine.commit()

        assertTrue(engine.awaitIdle(1_000L))
    }

    @Test
    fun lifecycleMethodsAreReachableThroughTheInterface_withNoOpDefaults() {
        // The four lifecycle calls used to be reached via `(x as? LocalWhisperEngine)?` downcasts,
        // which silently no-op for any other implementation. They are now on the interface, and a
        // second implementation that does not care inherits harmless defaults.
        val minimal: TranscriptionEngine = object : TranscriptionEngine {
            override fun connect(language: String?, listener: TranscriptionEngine.Listener) = Unit
            override fun sendAudio(pcm: ByteArray) = Unit
            override fun commit(): Long = -1L
            override fun close() = Unit
        }

        minimal.prewarm()
        minimal.shutdown()
        minimal.releaseContext()
        assertTrue(minimal.awaitIdle(0L))   // nothing outstanding -> drained

        // And the real engine is reachable through the same interface type.
        val local: TranscriptionEngine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider(null),
            retry = fastRetry(),
            backend = FakeWhisperBackend(),
            executor = SameThreadExecutorService(),
        )
        local.prewarm()
        assertTrue(local.awaitIdle(1_000L))
        local.releaseContext()
        local.shutdown()
    }
}
