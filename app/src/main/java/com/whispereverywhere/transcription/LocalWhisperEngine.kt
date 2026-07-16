package com.whispereverywhere.transcription

import com.whispereverywhere.util.AudioMath
import com.whispereverywhere.util.RetryPolicy
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * On-device whisper.cpp engine. Buffers PCM16 audio, and on commit runs one batch
 * transcription of the buffered segment on a single-thread executor (segments serialize).
 * No intra-segment deltas are emitted — one onCompleted per committed segment.
 */
class LocalWhisperEngine(
    private val modelPathProvider: ModelPathProvider,
    private val retry: RetryPolicy = RetryPolicy(maxAttempts = 3),
    private val backend: WhisperBackend = WhisperNativeBackend,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : TranscriptionEngine {

    // Model load is retried once (transient FS/mmap) using the injected policy's timing.
    private val loadRetry = RetryPolicy(
        maxAttempts = 2,
        baseDelayMs = retry.baseDelayMs,
        maxDelayMs = retry.maxDelayMs,
        rng = retry.rng,
    )

    private val bufferLock = Any()
    private val buffer = ByteArrayOutputStream()

    @Volatile private var listener: TranscriptionEngine.Listener? = null
    @Volatile private var language: String? = null

    // Process-lifetime cached native context (0 = not loaded).
    @Volatile private var ctxPtr: Long = 0L

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        this.listener = listener
        this.language = language

        val modelPath = modelPathProvider.installedModelPath()
        if (modelPath == null) {
            listener.onError("No speech model installed")
            return
        }

        executor.execute {
            try {
                if (ctxPtr == 0L) {
                    // Retry a transient load failure once before giving up.
                    val loaded = runBlocking { loadRetry.retry { backend.load(modelPath) } }
                    if (loaded == 0L) {
                        listener.onError("Failed to load speech model (may be corrupt - re-download)")
                        return@execute
                    }
                    ctxPtr = loaded
                }
                listener.onOpen()
            } catch (t: Throwable) {
                listener.onError(t.message ?: "Model load failed")
            }
        }
    }

    override fun sendAudio(pcm: ByteArray) {
        synchronized(bufferLock) { buffer.write(pcm) }
    }

    override fun commit() {
        val listener = this.listener ?: return
        val lang = this.language

        // Atomically snapshot + clear the buffer.
        val pcm: ByteArray = synchronized(bufferLock) {
            val snapshot = buffer.toByteArray()
            buffer.reset()
            snapshot
        }
        if (pcm.isEmpty()) return

        executor.execute {
            try {
                val ctx = ctxPtr
                if (ctx == 0L) {
                    listener.onError("Speech model not loaded")
                    return@execute
                }
                val samples = AudioMath.pcm16ToFloat(pcm)
                val text = runBlocking {
                    retry.retry { backend.transcribe(ctx, samples, lang) }
                }
                val trimmed = text.trim()
                if (trimmed.isNotBlank()) {
                    listener.onCompleted(trimmed)
                }
            } catch (t: Throwable) {
                listener.onError(t.message ?: "Transcription failed")
            }
        }
    }

    override fun close() {
        // Cancel pending work but keep the cached context for the next session.
        val listener = this.listener
        synchronized(bufferLock) { buffer.reset() }
        this.listener = null
        listener?.onClosed()
    }

    /**
     * Frees the cached native context (e.g. from onTrimMemory under memory pressure).
     * The context reloads lazily on the next connect(). Runs on the executor so it never
     * races an in-flight transcription.
     */
    fun releaseContext() {
        executor.execute {
            val ctx = ctxPtr
            if (ctx != 0L) {
                try {
                    backend.release(ctx)
                } catch (_: Throwable) {
                }
                ctxPtr = 0L
            }
        }
    }
}
