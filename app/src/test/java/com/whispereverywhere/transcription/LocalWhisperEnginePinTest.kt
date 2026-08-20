package com.whispereverywhere.transcription

import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workstream B (3.6.0): session-scoped language pinning through LocalWhisperEngine.
 * Auto sessions (connect(null)) detect on the first speech segment and pass the pinned code
 * to every later transcribe; explicit sessions pass through and never pin; the pin dies with
 * the session. Reuses the shared fakes from LocalWhisperEngineTest.kt (same package).
 */
class LocalWhisperEnginePinTest {

    private fun fastRetry() = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    private val pcm = byteArrayOf(0x10, 0x00, 0x20, 0x00)

    /** Records the lang of every transcribe; scripted texts; scripted detection. */
    private class PinProbeBackend(
        private val script: List<String>,
        private val detected: String? = "de",
    ) : WhisperBackend {
        val langs = mutableListOf<String?>()
        var detectQueries = 0
        private var i = 0
        override fun load(modelPath: String): Long = 42L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
            langs += lang
            val text = script[i.coerceAtMost(script.size - 1)]
            i++
            return text
        }
        override fun detectedLanguage(ctx: Long): String? { detectQueries++; return detected }
        override fun release(ctx: Long) = Unit
    }

    private fun engineWith(
        backend: WhisperBackend,
        executor: java.util.concurrent.ExecutorService = SameThreadExecutorService(),
    ) = LocalWhisperEngine(
        modelPathProvider = FakeModelPathProvider("/models/multi.bin"),
        retry = fastRetry(),
        backend = backend,
        executor = executor,
    )

    @Test
    fun autoSession_firstSpeechSegmentDetects_laterSegmentsPassThePin() {
        val backend = PinProbeBackend(script = listOf("hallo", "welt", "drei"))
        val engine = engineWith(backend)
        val listener = RecordingListener()
        engine.connect(language = null, listener = listener)

        repeat(3) { engine.sendAudio(pcm); engine.commit() }

        // Segment 0 paid the native detect pass (lang=null); 1 and 2 ride the pin.
        assertEquals(listOf(null, "de", "de"), backend.langs)
        // Queried exactly once — once pinned, no more per-segment JNI reads.
        assertEquals(1, backend.detectQueries)
        assertEquals(listOf("hallo", "welt", "drei"), listener.completed)
    }

    @Test
    fun explicitLanguage_neverPins_andNeverQueriesDetection() {
        val backend = PinProbeBackend(script = listOf("hello", "world"))
        val engine = engineWith(backend)
        engine.connect(language = "en", listener = RecordingListener())

        repeat(2) { engine.sendAudio(pcm); engine.commit() }

        assertEquals(listOf("en", "en"), backend.langs)
        assertEquals(0, backend.detectQueries)
    }

    @Test
    fun connect_clearsThePin_theNextSessionReDetects() {
        val backend = PinProbeBackend(script = listOf("eins", "zwei", "drei"))
        val engine = engineWith(backend)
        engine.connect(language = null, listener = RecordingListener())
        engine.sendAudio(pcm); engine.commit()          // pins "de"
        engine.close()

        engine.connect(language = null, listener = RecordingListener())
        engine.sendAudio(pcm); engine.commit()          // must re-detect: lang=null again
        engine.sendAudio(pcm); engine.commit()          // then ride the fresh pin

        assertEquals(listOf(null, null, "de"), backend.langs)
        assertEquals(2, backend.detectQueries)          // one detection per session
    }

    @Test
    fun blankFirstSegment_doesNotPin_theNextSpeechSegmentDetects() {
        // Native early-returns (VAD-empty, energy gate) surface as blanks, and
        // whisper_full_lang_id would be STALE for them — the engine must not read a verdict
        // whisper never produced on this audio.
        val backend = PinProbeBackend(script = listOf("   ", "hallo", "welt"))
        val engine = engineWith(backend)
        engine.connect(language = null, listener = RecordingListener())

        repeat(3) { engine.sendAudio(pcm); engine.commit() }

        assertEquals(listOf(null, null, "de"), backend.langs)
        assertEquals(1, backend.detectQueries)          // never queried for the blank
    }

    @Test
    fun unavailableDetection_neverPins_soAutoKeepsDetectingNatively() {
        val backend = PinProbeBackend(script = listOf("uno", "dos"), detected = null)
        val engine = engineWith(backend)
        engine.connect(language = null, listener = RecordingListener())

        repeat(2) { engine.sendAudio(pcm); engine.commit() }

        assertEquals(listOf(null, null), backend.langs)
        assertEquals(2, backend.detectQueries)          // re-queried until something usable lands
    }

    @Test
    fun aStaleSessionsLateSegment_cannotPinTheNewSession() {
        val executor = QueueingExecutorService()
        val backend = PinProbeBackend(script = listOf("alt", "neu", "neuer"))
        val engine = engineWith(backend, executor = executor)
        val first = RecordingListener()
        engine.connect(language = null, listener = first)
        executor.tasks[0].run()                          // the model-load task
        assertTrue(first.opened)

        engine.sendAudio(pcm)
        engine.commit()                                  // queued as tasks[1], NOT yet run
        engine.close()                                   // detaches the first listener

        val second = RecordingListener()
        engine.connect(language = null, listener = second)   // ctx loaded: onOpen via controlExecutor

        executor.tasks[1].run()                          // stale segment finishes AFTER the new connect

        // The stale segment transcribed (wasted work, result dropped by the existing guard)
        // but must NOT have pinned — its detection belongs to the dead session:
        assertEquals(0, backend.detectQueries)
        assertTrue(first.resolved.isEmpty())             // pre-existing stale-guard behavior

        engine.sendAudio(pcm); engine.commit()
        executor.tasks[2].run()
        // The new session's first segment still ran native auto-detect (lang=null), then pinned.
        assertEquals(listOf(null, null), backend.langs)
        assertEquals(1, backend.detectQueries)
        engine.sendAudio(pcm); engine.commit()
        executor.tasks[3].run()
        assertEquals(listOf(null, null, "de"), backend.langs)
    }
}
