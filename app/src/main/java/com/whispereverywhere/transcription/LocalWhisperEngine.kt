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

    private companion object {
        /** 30 s of PCM16 @ 16 kHz — hard ceiling on audio buffered between commits. */
        const val MAX_BUFFER_BYTES = 30 * 16000 * 2
    }

    /**
     * Lightweight control executor used ONLY to deliver connect() readiness callbacks
     * (onOpen/onError). It NEVER touches the native context. Keeping these off the native
     * [executor] means CONNECTING is not blocked behind a slow in-flight transcribe when the
     * engine is reused across sessions (a large-model transcribe can take many seconds).
     */
    private val controlExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var listener: TranscriptionEngine.Listener? = null
    @Volatile private var language: String? = null

    // Process-lifetime cached native context (0 = not loaded).
    @Volatile private var ctxPtr: Long = 0L

    // Absolute path of the model currently loaded into [ctxPtr]; used to detect a model switch so
    // the reused engine reloads the newly-selected model instead of silently reusing the old one.
    @Volatile private var loadedModelPath: String? = null

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        this.listener = listener
        this.language = language

        val modelPath = modelPathProvider.installedModelPath()
        android.util.Log.i("WE-DIAG", "connect: modelPath=$modelPath ctxPtr=$ctxPtr loaded=$loadedModelPath")
        if (modelPath == null) {
            // No native work involved; route through the native executor (keeps callback ordering
            // consistent and deterministic for tests using a same-thread executor).
            executor.execute {
                if (this.listener === listener) listener.onError("No speech model installed")
            }
            return
        }

        // Fast path: the SAME model is already loaded (reused engine). Signal readiness on the
        // lightweight control executor so onOpen() is NOT queued behind a slow in-flight transcribe
        // on the native executor — otherwise a prior session's transcribe would stall CONNECTING.
        if (ctxPtr != 0L && modelPath == loadedModelPath) {
            controlExecutor.execute {
                android.util.Log.i("WE-DIAG", "onOpen (ctx already loaded)")
                if (this.listener === listener) listener.onOpen()
            }
            return
        }

        // Nothing loaded yet, OR the installed model CHANGED since we loaded (user switched models).
        // (Re)load on the native executor. If a stale context for a DIFFERENT model is present, free
        // it first so we never transcribe with the wrong (or a heavier-than-selected) model.
        executor.execute {
            try {
                if (ctxPtr != 0L && modelPath != loadedModelPath) {
                    android.util.Log.i("WE-DIAG", "model changed ($loadedModelPath -> $modelPath); releasing old ctx")
                    try {
                        backend.release(ctxPtr)
                    } catch (t: Throwable) {
                        Log.w("LocalWhisperEngine", "release on model switch failed", t)
                    }
                    ctxPtr = 0L
                    loadedModelPath = null
                }
                if (ctxPtr == 0L) {
                    // Retry a transient load failure once before giving up.
                    android.util.Log.i("WE-DIAG", "loading model from $modelPath")
                    val loaded = runBlocking { loadRetry.retry { backend.load(modelPath) } }
                    android.util.Log.i("WE-DIAG", "model load returned ctx=$loaded")
                    if (loaded == 0L) {
                        if (this.listener === listener) listener.onError("Failed to load speech model (may be corrupt - re-download)")
                        return@execute
                    }
                    ctxPtr = loaded
                    loadedModelPath = modelPath
                }
                android.util.Log.i("WE-DIAG", "onOpen (ctx loaded)")
                if (this.listener === listener) listener.onOpen()
            } catch (t: Throwable) {
                android.util.Log.w("WE-DIAG", "model load threw", t)
                if (this.listener === listener) listener.onError(t.message ?: "Model load failed")
            }
        }
    }

    override fun sendAudio(pcm: ByteArray) {
        val overflow = synchronized(bufferLock) {
            buffer.write(pcm)
            buffer.size() >= MAX_BUFFER_BYTES
        }
        if (overflow) {
            // Backstop against unbounded growth if no caller-side commit fires (~32 KB/s of
            // PCM16@16kHz). Self-commit keeps memory bounded and results incremental.
            android.util.Log.i("WE-DIAG", "sendAudio: buffer cap reached -> forced commit")
            commit()
        }
    }

    override fun commit() {
        val myListener = this.listener
        if (myListener == null) {
            android.util.Log.i("WE-DIAG", "commit: no listener (session ended), skipped")
            return
        }
        val lang = this.language

        // Atomically snapshot + clear the buffer.
        val pcm: ByteArray = synchronized(bufferLock) {
            val snapshot = buffer.toByteArray()
            buffer.reset()
            snapshot
        }
        android.util.Log.i("WE-DIAG", "commit: pcmBytes=${pcm.size} samples=${pcm.size / 2}")
        if (pcm.isEmpty()) return

        executor.execute {
            try {
                val ctx = ctxPtr
                if (ctx == 0L) {
                    android.util.Log.w("WE-DIAG", "commit: ctx==0 (model not loaded)")
                    // Guard: only fire if the listener hasn't been replaced/nulled since commit().
                    if (listener === myListener) myListener.onError("Speech model not loaded")
                    return@execute
                }
                val samples = AudioMath.pcm16ToFloat(pcm)
                android.util.Log.i("WE-DIAG", "transcribe START samples=${samples.size} lang=$lang")
                val text = runBlocking {
                    retry.retry { backend.transcribe(ctx, samples, lang) }
                }
                // Strip whisper's non-speech markers ([BLANK_AUDIO], [ Silence ], (music), …) so
                // they are never typed into the user's field.
                val cleaned = TranscriptText.clean(text)
                // Never log transcript content — logcat is readable by adb/other tooling and the
                // product promise is that transcriptions stay on-device. Lengths only.
                android.util.Log.i("WE-DIAG", "transcribe DONE rawLen=${text.length} cleanLen=${cleaned.length}")
                if (cleaned.isNotBlank()) {
                    // Guard: only fire if the listener hasn't been replaced/nulled since commit().
                    if (listener === myListener) myListener.onCompleted(cleaned)
                } else {
                    android.util.Log.i("WE-DIAG", "transcribe result blank/non-speech -> dropped")
                }
            } catch (t: Throwable) {
                android.util.Log.w("WE-DIAG", "transcribe THREW", t)
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
     * Loads the native context ahead of the first session so the first recording doesn't pay
     * the model load + GPU kernel compile (~7 s on Adreno OpenCL) inside CONNECTING. Silent on
     * failure: connect() retries the load with full error reporting. A model switch between
     * prewarm and connect is also connect()'s job — this only fills an EMPTY context slot.
     */
    fun prewarm() {
        val modelPath = modelPathProvider.installedModelPath() ?: return
        if (ctxPtr != 0L) return
        executor.execute {
            if (ctxPtr != 0L) return@execute
            try {
                val loaded = runBlocking { loadRetry.retry { backend.load(modelPath) } }
                if (loaded != 0L) {
                    ctxPtr = loaded
                    loadedModelPath = modelPath
                    android.util.Log.i("WE-DIAG", "prewarm: ctx loaded")
                }
            } catch (t: Throwable) {
                Log.w("LocalWhisperEngine", "prewarm load failed (connect() will retry)", t)
            }
        }
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
                loadedModelPath = null
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
    /**
     * Blocks the CALLING thread until all work already queued on the native [executor] (notably a
     * final commit()'s transcribe) has finished, or [timeoutMs] elapses. The caller uses this to
     * ensure the final segment's onCompleted has been delivered — while the listener is still
     * attached — BEFORE close() detaches it. MUST be called off the main thread. Submitting an
     * empty fence task preserves the single-thread native-access contract (it never touches ctxPtr).
     */
    fun awaitIdle(timeoutMs: Long) {
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            executor.execute { latch.countDown() }
        } catch (t: java.util.concurrent.RejectedExecutionException) {
            return  // executor already shut down — nothing is in flight
        }
        try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

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
                loadedModelPath = null
            }
        }
        executor.shutdown()
        controlExecutor.shutdown()
    }
}
