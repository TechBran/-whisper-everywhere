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
            // --- TTSDIAG session state (Release 0 instrumentation; no behaviour change) ---
            // Declared here (not inside the try below) for the same reason as playbackThread
            // above: the outer finally block emits the end-of-utterance summary and needs these
            // in scope, and a try-block-local val is not visible from its own finally.
            val diagRtfs = java.util.Collections.synchronizedList(ArrayList<Double>())
            val diagSentSeq = java.util.concurrent.atomic.AtomicInteger(0)
            // Callback EXIT -> next ENTRY. A one-element array, not @Volatile: Kotlin does
            // not permit @Volatile on a local, and the sherpa callback is this value's only
            // reader and only writer (it runs on the executor thread, one call at a time).
            val diagLastCallbackExitMs = longArrayOf(0L)
            // Declared (not initialised) here: 0L means "this session never reached the
            // measurement anchor below" — used by the finally block to skip emitting an `end`
            // record for a run that bailed before anything was actually spoken (findings 1/2).
            var diagT0 = 0L
            var diagRate = 24_000
            val diagTtfwMs = java.util.concurrent.atomic.AtomicLong(-1)
            val diagUnderN = java.util.concurrent.atomic.AtomicInteger(0)
            val diagUnderMs = java.util.concurrent.atomic.AtomicLong(0)
            val diagMaxGapMs = java.util.concurrent.atomic.AtomicLong(0)
            val diagHwUnder = java.util.concurrent.atomic.AtomicInteger(0)
            // (I2) Duration-weighted RTF aggregate: Σ synthMs / Σ audMs across callbacks, under
            // the same seq > 0 exclusion as diagRtfs below. An unweighted percentile of the
            // per-callback RTFs treats a 2.5 s callback the same as a 23.6 s one and can read
            // misleadingly high; this aggregate is what the spec's RTF conclusion should rest on.
            val diagTotalSynthMs = java.util.concurrent.atomic.AtomicLong(0)
            val diagTotalAudMs = java.util.concurrent.atomic.AtomicLong(0)
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

                // Measurement anchor (findings 1/2): zeroed HERE, after the cold model load and
                // the cancellation check above, not before — so ttfwMs/wallMs measure the actual
                // utterance, not a cache-miss model load or a task that never spoke anything.
                diagT0 = System.currentTimeMillis()
                diagRate = engine.sampleRate()

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
                    var stallStartMs = 0L
                    var stallHeadStart = 0
                    var stallUnderStart = 0
                    var lastProgressMs = 0L
                    try {
                        // Moved inside the try (finding 5): these read off localTrack right
                        // after construction, and belong under the same finally-release guard
                        // as everything else that touches the track — a throw here must not
                        // leak the AudioTrack or kill this thread uncaught.
                        android.util.Log.i(
                            TtsDiag.TAG,
                            TtsDiag.open(
                                gen = myGen,
                                bufFrames = localTrack.bufferSizeInFrames,
                                perfMode = localTrack.performanceMode,
                                chars = clean.length,
                                sampleRate = localTrack.sampleRate,
                            ),
                        )
                        loop@ while (!cancelled()) {
                            // Consume a pending seek between slices; flush queued audio HERE
                            // (sole track owner) so the jump is instant.
                            val seek = seekRequest.getAndSet(-1)
                            if (seek >= 0) {
                                cursor = seek.coerceIn(0L, availableSamples)
                                if (started) {
                                    runCatching { localTrack.pause(); localTrack.flush(); localTrack.play() }
                                    stallHeadStart = 0   // flush() zeroed the head; re-baseline
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
                                    stallStartMs = System.currentTimeMillis()
                                    stallHeadStart = localTrack.playbackHeadPosition
                                    stallUnderStart = localTrack.underrunCount
                                    onBuffering?.invoke(true)
                                }
                                try { Thread.sleep(50) } catch (_: InterruptedException) {}
                                continue@loop
                            }
                            if (stalled) {
                                stalled = false
                                val wallMs = System.currentTimeMillis() - stallStartMs
                                // playbackHeadPosition is an UNSIGNED 32-bit frame count in an
                                // Int; mask before subtracting or a wrap reads as a huge
                                // negative and audibleMs silently clamps to 0.
                                val framesRendered =
                                    ((localTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL) -
                                        (stallHeadStart.toLong() and 0xFFFFFFFFL)).coerceAtLeast(0L)
                                val renderMs = TtsDiagMath.audioMs(
                                    framesRendered.toInt(), localTrack.sampleRate,
                                )
                                val hwD = localTrack.underrunCount - stallUnderStart
                                val audible = TtsDiagMath.audibleSilenceMs(wallMs, renderMs)
                                diagUnderN.incrementAndGet()
                                diagUnderMs.addAndGet(audible)
                                // (I3) diagHwUnder is NOT accumulated here. A per-stall sum only
                                // sees underruns that coincide with an observed producer stall
                                // (readAt() returning null) — the HAL can starve while readAt()
                                // still returns data (playback-thread descheduling, perfMode=0,
                                // a small buffer), and that gap would never touch this branch.
                                // hwD is still reported per-stall on the `under` record below;
                                // the session TOTAL is read once at loop exit, in the finally.
                                if (audible > diagMaxGapMs.get()) diagMaxGapMs.set(audible)
                                android.util.Log.i(
                                    TtsDiag.TAG,
                                    TtsDiag.under(
                                        gen = myGen,
                                        seq = diagSentSeq.get(),
                                        atMs = System.currentTimeMillis() - diagT0,
                                        wallMs = wallMs,
                                        renderMs = renderMs,
                                        hwUnderD = hwD,
                                    ),
                                )
                                onBuffering?.invoke(false)
                            }
                            if (!started) {
                                localTrack.play()
                                started = true
                                diagTtfwMs.set(System.currentTimeMillis() - diagT0)
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
                                val leadMs = TtsDiagMath.audioMs(
                                    (availableSamples - cursor).toInt().coerceAtLeast(0),
                                    localTrack.sampleRate,
                                )
                                android.util.Log.i(
                                    TtsDiag.TAG,
                                    TtsDiag.play(myGen, diagSentSeq.get(), leadMs),
                                )
                            }
                        }
                        if (!cancelled() && !stalled) {
                            try { Thread.sleep(150) } catch (_: InterruptedException) {}
                            if (started) runCatching { localTrack.stop() }
                            onProgress?.invoke(playedSamples, availableSamples, true)
                        }
                    } finally {
                        if (stalled) onBuffering?.invoke(false)
                        // (I3) Session total, read ONCE here rather than accumulated per stall:
                        // underrunCount counts everything since track creation and is unaffected
                        // by flush(), so this is the true total on every exit path (normal
                        // completion, stop(), or a write()/exception break above).
                        diagHwUnder.set(localTrack.underrunCount)
                        runCatching { localTrack.release() }
                    }
                }, "tts-playback")
                playbackThread.start()

                // MUST be an explicit Function1 object, NOT a lambda: sherpa's JNI reflectively
                // calls the specialized invoke([F)Ljava/lang/Integer; bridge, which Kotlin 2.0's
                // default invokedynamic lambdas do not generate (SIGABRT proven on-device).
                val callback = object : Function1<FloatArray, Int> {
                    override fun invoke(samples: FloatArray): Int {
                        val entryMs = System.currentTimeMillis()
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
                        // Measure callback EXIT -> ENTRY so the AHEAD_CAP backpressure hold below
                        // never reads as slow synthesis (spec 6A.3). The FIRST burst of an
                        // utterance is still logged but is excluded from the summary percentiles
                        // by the seq==0 check, because it carries whole-text espeak phonemisation
                        // whose cost scales with SELECTION length, not sentence length.
                        val prevExit = diagLastCallbackExitMs[0]
                        val synthMs = if (prevExit == 0L) entryMs - diagT0 else entryMs - prevExit
                        val seq = diagSentSeq.getAndIncrement()
                        val audMs = TtsDiagMath.audioMs(pcm.size, engine.sampleRate())
                        android.util.Log.i(TtsDiag.TAG, TtsDiag.sent(myGen, seq, pcm.size, audMs, synthMs))
                        if (seq > 0) {
                            diagRtfs.add(TtsDiagMath.rtf(synthMs, audMs))
                            diagTotalSynthMs.addAndGet(synthMs)
                            diagTotalAudMs.addAndGet(audMs)
                        }
                        diagLastCallbackExitMs[0] = System.currentTimeMillis()
                        return 1
                    }
                }
                try {
                    // Bound each synthesis unit so sherpa's whole-utterance float[] stays small
                    // (OOM bound, 6A.5) and no unit outruns the banked audio (underrun law, 6A.1).
                    // A selection within the cap yields a single unit equal to `clean`, so short
                    // text takes the exact prior path; only long sentences become multiple units.
                    // Feed order is preserved and cancellation is re-checked between units so
                    // stop() still lands within one sherpa call (C1).
                    for (unit in ClauseSplitter.plan(clean)) {
                        if (cancelled()) break
                        engine.generateWithCallback(
                            text = unit, sid = speakerId, speed = speed, callback = callback,
                        )
                    }
                } finally {
                    // MUST be in a finally. If generateWithCallback throws (OOM on sherpa's
                    // whole-utterance float[], or a native error), skipping this leaves the
                    // playback loop's readAt() returning null forever: the thread never exits,
                    // the AudioTrack leaks, and onDone tears down the stop button while banked
                    // audio keeps playing with focus already abandoned.
                    doneFlag.set(true)
                }
                // Playback outlives synthesis by up to the whole retained read; a newer speak()
                // or stop() bumps the generation and this join returns within a slice.
                // On the throw path we still reach the outer catch AFTER banked audio drains,
                // which is deliberate: the user keeps the words already synthesized.
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
                // Gate: diagT0 == 0L means this task returned before the measurement anchor
                // (no model dir, or cancelled pre-anchor) — availableSamples etc. are still the
                // PREVIOUS utterance's values at that point, so emitting here would fabricate a
                // summary for an utterance that was never spoken (findings 1/2).
                if (diagT0 != 0L) {
                    android.util.Log.i(
                        TtsDiag.TAG,
                        TtsDiag.end(
                            gen = myGen,
                            ttfwMs = diagTtfwMs.get().coerceAtLeast(0),
                            underN = diagUnderN.get(),
                            underMs = diagUnderMs.get(),
                            maxGapMs = diagMaxGapMs.get(),
                            audioMs = TtsDiagMath.audioMs(availableSamples.toInt(), diagRate),
                            // (I1) SYNTHESIZED total (above) can exceed wall clock when playback
                            // never catches up; PLAYED total is what the user actually heard —
                            // derived the same way audioMs is, just off playedSamples instead.
                            playedMs = TtsDiagMath.audioMs(playedSamples.toInt(), diagRate),
                            wallMs = System.currentTimeMillis() - diagT0,
                            // (I2) Duration-weighted, not the unweighted per-callback median.
                            rtfAgg = TtsDiagMath.rtf(diagTotalSynthMs.get(), diagTotalAudMs.get()),
                            rtfs = ArrayList(diagRtfs),
                            hwUnderTotal = diagHwUnder.get(),
                        ),
                    )
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
