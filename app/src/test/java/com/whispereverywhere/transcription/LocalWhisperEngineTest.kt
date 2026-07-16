package com.whispereverywhere.transcription

import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

/** Records every Listener callback for assertions. */
class RecordingListener : TranscriptionEngine.Listener {
    var opened = false
    var closed = false
    val deltas = mutableListOf<String>()
    val completed = mutableListOf<String>()
    val errors = mutableListOf<String>()
    override fun onOpen() { opened = true }
    override fun onDelta(text: String) { deltas.add(text) }
    override fun onCompleted(text: String) { completed.add(text) }
    override fun onError(message: String) { errors.add(message) }
    override fun onClosed() { closed = true }
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
        engine.commit()

        assertEquals(listOf("hello world"), listener.completed)   // trimmed
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

        engine.commit()

        assertTrue(backend.transcribeCalls.isEmpty())
        assertTrue(listener.completed.isEmpty())
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun commit_whenBackendReturnsBlank_skipsCompleted() {
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
        engine.commit()

        assertTrue(listener.completed.isEmpty())   // blank result suppressed
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
    fun commit_withPermanentFailure_reportsErrorAfterRetriesExhausted() {
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
        engine.commit()

        assertTrue(listener.completed.isEmpty())
        assertEquals(1, listener.errors.size)
        assertEquals("transient transcribe failure", listener.errors[0])
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
        // and the listener was detached).
        engine.commit()
        assertTrue(backend.transcribeCalls.isEmpty())
    }
}
