package com.whispereverywhere.transcription

import android.util.Log
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
 *
 * IMPORTANT: [executor] MUST be single-threaded. All native whisper context ([ctxPtr]) reads,
 * writes, loads, and frees happen exclusively on that thread, which is what serializes them
 * safely. The default [Executors.newSingleThreadExecutor] satisfies this contract — callers
 * must NOT pass a multi-threaded executor.
 */
class LocalWhisperEngine(
    private val modelPathProvider: ModelPathProvider,
    private val retry: RetryPolicy = RetryPolicy(maxAttempts = 3),
    private val backend: WhisperBackend = WhisperNativeBackend,
    /**
     * MUST be single-threaded. All native whisper context ([ctxPtr]) reads, writes, loads,
     * and frees are serialized by executing exclusively on this thread. Passing a
     * multi-threaded executor will cause data races on the native context pointer.
     */
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
            // Route through the executor for thread consistency (all callbacks on executor thread).
            val captured = listener
            executor.execute {
                if (this.listener === captured) captured.onError("No speech model installed")
            }
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
        val myListener = this.listener ?: return
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
                    // Guard: only fire if the listener hasn't been replaced/nulled since commit().
                    if (listener === myListener) myListener.onError("Speech model not loaded")
                    return@execute
                }
                val samples = AudioMath.pcm16ToFloat(pcm)
                val text = runBlocking {
                    retry.retry { backend.transcribe(ctx, samples, lang) }
                }
                val trimmed = text.trim()
                if (trimmed.isNotBlank()) {
                    // Guard: only fire if the listener hasn't been replaced/nulled since commit().
                    if (listener === myListener) myListener.onCompleted(trimmed)
                }
            } catch (t: Throwable) {
                // Guard: only fire if the listener hasn't been replaced/nulled since commit().
                if (listener === myListener) myListener.onError(t.message ?: "Transcription failed")
            }
        }
    }

    /**
     * Ends the current session. Detaches the listener (any already-queued transcriptions become
     * no-ops via the identity guard) and clears the audio buffer. Delivers [Listener.onClosed]
     * synchronously to the caller before returning.
     *
     * NOTE: this does NOT forcibly cancel native work that is already executing on the executor
     * thread; it only prevents stale callbacks from being delivered once that work eventually
     * completes.
     */
    override fun close() {
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
                } catch (t: Throwable) {
                    Log.w("LocalWhisperEngine", "releaseContext failed", t)
                }
                ctxPtr = 0L
            }
        }
    }

    /**
     * Full teardown for service/process end. Frees the native context (submitted on the executor,
     * so it never races in-flight work) and THEN shuts the executor down so its single worker
     * thread does not leak for the lifetime of a long-running foreground service. shutdown() lets
     * the already-queued release task finish before the thread terminates. After this call the
     * engine must not be reused.
     */
    fun shutdown() {
        executor.execute {
            val ctx = ctxPtr
            if (ctx != 0L) {
                try {
                    backend.release(ctx)
                } catch (t: Throwable) {
                    Log.w("LocalWhisperEngine", "shutdown release failed", t)
                }
                ctxPtr = 0L
            }
        }
        executor.shutdown()
    }
}
