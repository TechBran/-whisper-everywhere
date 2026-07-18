package com.whispereverywhere.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * On-device read-aloud engine (Track F): Kokoro-82M fp32 via sherpa-onnx, CPU, 4 threads
 * (bench 2026-07-18: RTF 0.577, first words ~1.9 s — see the Track F plan).
 *
 * Synthesis runs sentence-by-sentence on a single-thread executor; each chunk streams into an
 * AudioTrack (MODE_STREAM) so playback starts after the FIRST sentence while the rest is still
 * synthesizing. stop() is instant: cancel flag (sherpa callback returns 0) + pause/flush.
 *
 * Audio focus: TRANSIENT_MAY_DUCK while speaking (music ducks, podcasts pause), abandoned the
 * moment speech ends. Focus loss stops speech.
 *
 * The ~0.8 GB native context is preload()-able (hide the 2 s load inside think-time) and
 * auto-unloads after [IDLE_UNLOAD_MS] of silence.
 */
class TtsEngine(
    private val context: Context,
    private val modelManager: TtsModelManager,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var speaking = false

    // Cancellation model (final-review fix C1): a monotonically increasing generation. Each
    // speak() task captures its generation; stop() and any NEWER speak() bump the counter,
    // which cancels every older task the moment it next checks — nothing can ever "un-cancel"
    // an in-flight task, unlike a shared boolean the next request resets.
    private val generation = java.util.concurrent.atomic.AtomicLong(0)

    // (C2) The AudioTrack is a LOCAL of its executor task — no other thread ever touches it.
    // Cancellation latency stays low because writes happen in ~100 ms slices with a generation
    // check between slices, instead of one blocking whole-sentence write.
    @Volatile private var focusRequest: AudioFocusRequest? = null
    private var unloadRunnable: Runnable? = null

    /** Kokoro speaker id (multi-lang v1_0, see TtsVoices; default af_heart is set by callers). */
    @Volatile var speakerId: Int = 0

    @Volatile var speed: Float = 1.0f

    /**
     * Per-slice visual tap: (pcm16 bytes, rms amplitude 0..32767) for every ~100 ms slice as
     * it is written to the AudioTrack — drives the bubble's waveform/aurora exactly like mic
     * chunks do. Called on the playback thread; keep it to thread-safe field writes.
     */
    @Volatile var onPcmChunk: ((ByteArray, Int) -> Unit)? = null

    /**
     * Fires true when playback is STALLED waiting for synthesis (mid-utterance buffer
     * underrun), false when audio flows again — the bubble shows "still working, not frozen"
     * (user feedback 2026-07-18). Called from the playback thread.
     */
    @Volatile var onBuffering: ((Boolean) -> Unit)? = null

    /**
     * Scrubber feed (~10 Hz from the playback thread): samples played, samples synthesized so
     * far, and whether synthesis has finished (= the bar's right edge is final).
     */
    @Volatile var onProgress: ((played: Long, available: Long, done: Boolean) -> Unit)? = null

    // Seek request in absolute samples; -1 = none. The playback thread consumes it between
    // slices (it is the AudioTrack's sole owner, so the flush happens there too).
    private val seekRequest = java.util.concurrent.atomic.AtomicLong(-1)
    @Volatile private var playedSamples = 0L
    @Volatile private var availableSamples = 0L

    /** Scrub to [fraction] of the SYNTHESIZED audio (0..1). Forward is clamped to it. */
    fun seekToFraction(fraction: Float) {
        val target = (availableSamples * fraction.coerceIn(0f, 1f)).toLong()
        seekRequest.set(target)
        paused = false // dragging the line while paused implies "play from here"
    }

    /** True while playback is paused mid-utterance (synthesis holds between slices). */
    @Volatile var paused: Boolean = false
        private set

    /** Toggle pause/resume; returns the NEW paused state. No-op when not speaking. */
    fun togglePause(): Boolean {
        paused = !paused && speaking
        return paused
    }

    fun isReady(): Boolean = tts != null

    fun isSpeaking(): Boolean = speaking

    /** Load the model off-thread if installed; no-op otherwise. Safe to call repeatedly. */
    fun preload() {
        cancelIdleUnload()
        if (tts != null) return
        val dir = modelManager.installedDir() ?: return
        executor.execute {
            if (tts != null) return@execute
            runCatching { tts = buildTts(dir) }
                .onFailure { android.util.Log.w("WE-TTS", "preload failed", it) }
        }
    }

    /**
     * Speak [text]. Returns false if the voice model is not installed. Any in-flight speech is
     * cancelled first. [onDone] fires on the main thread when playback finishes or is stopped.
     */
    fun speak(text: String, onDone: () -> Unit = {}): Boolean {
        if (modelManager.installedDir() == null) return false
        val clean = text.trim()
        if (clean.isEmpty()) return false
        // Bumping the generation IS the cancellation of any older task (C1) — nothing resets
        // a shared flag, so an old task can never be "un-cancelled" by a new request.
        val myGen = generation.incrementAndGet()
        cancelIdleUnload()
        paused = false
        speaking = true
        fun cancelled() = generation.get() != myGen
        executor.execute {
            var myFocus: AudioFocusRequest? = null
            var playbackThread: Thread? = null
            try {
                val engine = tts ?: modelManager.installedDir()?.let { d ->
                    buildTts(d).also { tts = it }
                } ?: return@execute
                if (cancelled()) return@execute

                myFocus = requestFocus()

                // Retained-store pipeline (scrubber, user design 2026-07-18): synthesis appends
                // sentences to an in-memory PCM store as fast as the model produces them (RTF
                // ~0.58 keeps it ahead of real time), while the playback thread — the
                // AudioTrack's SOLE owner (C2) — walks a CURSOR through the store. Keeping the
                // audio behind the cursor is what makes scrub-BACK possible; scrub-forward
                // clamps to the synthesized frontier. Backpressure: synthesis holds when more
                // than AHEAD_CAP is buffered ahead of playback (~14 MB); total retention capped
                // at RETAIN_CAP (~30 min, ~86 MB) — beyond that synthesis stops with a log.
                val store = ArrayList<ShortArray>() // guarded by synchronized(store)
                var storeTotal = 0L                 // written under the same lock
                playedSamples = 0L
                availableSamples = 0L
                seekRequest.set(-1)
                val doneFlag = java.util.concurrent.atomic.AtomicBoolean(false)

                fun readAt(cursor: Long, maxLen: Int): ShortArray? {
                    synchronized(store) {
                        if (cursor >= storeTotal) return null
                        var base = 0L
                        for (chunk in store) {
                            if (cursor < base + chunk.size) {
                                val off = (cursor - base).toInt()
                                val len = minOf(maxLen, chunk.size - off)
                                return chunk.copyOfRange(off, off + len)
                            }
                            base += chunk.size
                        }
                        return null
                    }
                }

                playbackThread = Thread({
                    val localTrack = newTrack(engine.sampleRate())
                    val slice = localTrack.sampleRate / 10
                    var cursor = 0L
                    var started = false
                    var stalled = false
                    var lastProgressMs = 0L
                    try {
                        loop@ while (!cancelled()) {
                            // Consume a pending seek between slices; flush queued audio HERE
                            // (sole track owner) so the jump is instant.
                            val seek = seekRequest.getAndSet(-1)
                            if (seek >= 0) {
                                cursor = seek.coerceIn(0L, availableSamples)
                                if (started) {
                                    runCatching { localTrack.pause(); localTrack.flush(); localTrack.play() }
                                }
                            }
                            if (paused) {
                                if (started) runCatching { localTrack.pause() }
                                while (paused && !cancelled() && seekRequest.get() < 0) {
                                    try { Thread.sleep(50) } catch (_: InterruptedException) {}
                                }
                                if (cancelled()) break@loop
                                if (started) runCatching { localTrack.play() }
                                continue@loop
                            }
                            val pcm = readAt(cursor, slice)
                            if (pcm == null) {
                                if (doneFlag.get()) break@loop
                                if (started && !stalled) {
                                    stalled = true
                                    onBuffering?.invoke(true)
                                }
                                try { Thread.sleep(50) } catch (_: InterruptedException) {}
                                continue@loop
                            }
                            if (stalled) {
                                stalled = false
                                onBuffering?.invoke(false)
                            }
                            if (!started) {
                                localTrack.play()
                                started = true
                            }
                            onPcmChunk?.let { tap ->
                                var sum = 0.0
                                val bytes = ByteArray(pcm.size * 2)
                                for (i in pcm.indices) {
                                    val s = pcm[i]
                                    sum += (s.toInt() * s.toInt()).toDouble()
                                    bytes[i * 2] = (s.toInt() and 0xFF).toByte()
                                    bytes[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                                }
                                val rms = kotlin.math.sqrt(sum / pcm.size).toInt().coerceIn(0, 32767)
                                tap(bytes, rms)
                            }
                            var off = 0
                            while (off < pcm.size) {
                                if (cancelled() || seekRequest.get() >= 0) break
                                val n = localTrack.write(pcm, off, pcm.size - off)
                                if (n < 0) break@loop
                                off += n
                            }
                            cursor += off
                            playedSamples = cursor
                            val now = System.currentTimeMillis()
                            if (now - lastProgressMs >= 100) {
                                lastProgressMs = now
                                onProgress?.invoke(cursor, availableSamples, doneFlag.get())
                            }
                        }
                        if (!cancelled() && !stalled) {
                            try { Thread.sleep(150) } catch (_: InterruptedException) {}
                            if (started) runCatching { localTrack.stop() }
                            onProgress?.invoke(playedSamples, availableSamples, true)
                        }
                    } finally {
                        if (stalled) onBuffering?.invoke(false)
                        runCatching { localTrack.release() }
                    }
                }, "tts-playback")
                playbackThread.start()

                // MUST be an explicit Function1 object, NOT a lambda: sherpa's JNI reflectively
                // calls the specialized invoke([F)Ljava/lang/Integer; bridge, which Kotlin 2.0's
                // default invokedynamic lambdas do not generate (SIGABRT proven on-device).
                val callback = object : Function1<FloatArray, Int> {
                    override fun invoke(samples: FloatArray): Int {
                        if (cancelled()) return 0
                        val pcm = ShortArray(samples.size) { i ->
                            (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                        // Backpressure: hold while far ahead of playback (seek-forward drains it).
                        while (!cancelled() &&
                            availableSamples - playedSamples > AHEAD_CAP_SAMPLES
                        ) {
                            try { Thread.sleep(100) } catch (_: InterruptedException) {}
                        }
                        if (cancelled()) return 0
                        if (availableSamples + pcm.size > RETAIN_CAP_SAMPLES) {
                            android.util.Log.w("WE-TTS", "retention cap hit — truncating synthesis")
                            return 0
                        }
                        synchronized(store) {
                            store.add(pcm)
                            storeTotal += pcm.size
                            availableSamples = storeTotal
                        }
                        return 1
                    }
                }
                engine.generateWithCallback(
                    text = clean, sid = speakerId, speed = speed, callback = callback,
                )
                doneFlag.set(true)
                // Playback outlives synthesis by up to the whole retained read; a newer speak()
                // or stop() bumps the generation and this join returns within a slice.
                playbackThread.join(RETAIN_CAP_JOIN_MS)
            } catch (t: Throwable) {
                android.util.Log.w("WE-TTS", "speak failed", t)
            } finally {
                playbackThread?.let { pt ->
                    if (pt.isAlive) runCatching { pt.join(2_000) }
                }
                // Abandon focus only if OUR request is still the active one — a newer task may
                // already hold its own (review fix I3).
                if (myFocus != null && focusRequest === myFocus) abandonFocus()
                // Shared flags belong to the newest task only.
                if (!cancelled()) {
                    speaking = false
                    scheduleIdleUnload()
                }
                main.post(onDone)
            }
        }
        return true
    }

    /**
     * Instant stop: bumps the generation; the in-flight task cancels at its next slice check
     * (~100 ms). Safe from any thread — never touches the AudioTrack (C2).
     */
    fun stop() {
        generation.incrementAndGet()
        speaking = false
        paused = false // a paused task must wake to observe the cancellation
        // The superseded task won't schedule the unload (it lost ownership) — do it here so a
        // stopped engine still frees its ~0.8 GB context after the idle window.
        scheduleIdleUnload()
    }

    /** Full release (service destroy). */
    fun shutdown() {
        stop()
        executor.execute {
            tts?.release(); tts = null
        }
        executor.shutdown()
    }

    private fun buildTts(dir: File): OfflineTts {
        val lexicons = listOf("lexicon-us-en.txt", "lexicon-gb-en.txt")
            .map { File(dir, it) }.filter { it.exists() }
            .joinToString(",") { it.absolutePath }
        return OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = File(dir, TtsModelManager.MODEL_FILE).absolutePath,
                        voices = File(dir, "voices.bin").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = File(dir, "espeak-ng-data").absolutePath,
                        dictDir = "",
                        lexicon = lexicons,
                    ),
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                ),
            ),
        )
    }

    private fun newTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun requestFocus(): AudioFocusRequest? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    stop() // call/assistant took the output — stop speaking immediately
                }
            }
            .build()
        focusRequest = req
        val granted = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) android.util.Log.w("WE-TTS", "audio focus denied")
        return req
    }

    private fun abandonFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun scheduleIdleUnload() {
        cancelIdleUnload()
        val r = Runnable {
            if (!speaking) {
                executor.execute {
                    if (!speaking) {
                        tts?.release(); tts = null
                        android.util.Log.i("WE-TTS", "idle unload: voice context freed")
                    }
                }
            }
        }
        unloadRunnable = r
        main.postDelayed(r, IDLE_UNLOAD_MS)
    }

    private fun cancelIdleUnload() {
        unloadRunnable?.let { main.removeCallbacks(it) }
        unloadRunnable = null
    }

    companion object {
        private const val IDLE_UNLOAD_MS = 5 * 60_000L

        // Scrubber pipeline (24 kHz mono): synthesis holds when > 5 min is buffered ahead of
        // playback (~14 MB); total retention caps at 30 min (~86 MB) — enough for any real
        // read-aloud while bounding worst-case memory.
        private const val AHEAD_CAP_SAMPLES = 5L * 60 * 24_000
        private const val RETAIN_CAP_SAMPLES = 30L * 60 * 24_000
        private const val RETAIN_CAP_JOIN_MS = 35L * 60_000
    }
}
