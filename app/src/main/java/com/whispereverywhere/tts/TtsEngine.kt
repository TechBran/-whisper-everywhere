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

    /** Kokoro speaker id (multi-lang v1_1 has 103; 0 is a solid US-English default). */
    @Volatile var speakerId: Int = 0

    @Volatile var speed: Float = 1.0f

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
        speaking = true
        fun cancelled() = generation.get() != myGen
        executor.execute {
            var myFocus: AudioFocusRequest? = null
            var at: AudioTrack? = null
            try {
                val engine = tts ?: modelManager.installedDir()?.let { d ->
                    buildTts(d).also { tts = it }
                } ?: return@execute
                if (cancelled()) return@execute

                myFocus = requestFocus()
                val localTrack = newTrack(engine.sampleRate())
                at = localTrack
                var started = false

                // MUST be an explicit Function1 object, NOT a lambda: sherpa's JNI reflectively
                // calls the specialized invoke([F)Ljava/lang/Integer; bridge, which Kotlin 2.0's
                // default invokedynamic lambdas do not generate (SIGABRT proven on-device).
                val callback = object : Function1<FloatArray, Int> {
                    override fun invoke(samples: FloatArray): Int {
                        if (cancelled()) return 0
                        val pcm = ShortArray(samples.size) { i ->
                            (samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        }
                        if (!started) {
                            localTrack.play()
                            started = true
                        }
                        // ~100 ms slices with a cancellation check between them (C2): stop()
                        // never touches the track from another thread, yet cancel latency
                        // stays ~one slice.
                        val slice = localTrack.sampleRate / 10
                        var off = 0
                        while (off < pcm.size) {
                            if (cancelled()) return 0
                            val n = localTrack.write(pcm, off, minOf(slice, pcm.size - off))
                            if (n < 0) return 0
                            off += n
                        }
                        return if (cancelled()) 0 else 1
                    }
                }
                engine.generateWithCallback(
                    text = clean, sid = speakerId, speed = speed, callback = callback,
                )
                // Let the tail drain unless cancelled (the track buffer holds ~the last slices).
                if (!cancelled() && started) {
                    try { Thread.sleep(150) } catch (_: InterruptedException) {}
                    runCatching { localTrack.stop() }
                }
            } catch (t: Throwable) {
                android.util.Log.w("WE-TTS", "speak failed", t)
            } finally {
                at?.let { runCatching { it.release() } }
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
    }
}
