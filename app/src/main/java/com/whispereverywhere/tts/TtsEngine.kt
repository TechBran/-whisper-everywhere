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
import com.whispereverywhere.transcription.cloud.FatalKind
import com.whispereverywhere.tts.cloud.TtsError
import com.whispereverywhere.tts.cloud.TtsProvider
import com.whispereverywhere.tts.cloud.TtsResult
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

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

    // The in-flight cloud synthesis coroutine (async child of the per-unit runBlocking), published
    // while a fetch is outstanding so stop() and a superseding speak() can CANCEL it — which aborts
    // the underlying OkHttp Call (transport's invokeOnCancellation) and frees the single synthesis
    // thread immediately, instead of leaving it blocked in the fetch for up to the 45 s TTS timeout
    // while a queued next read cannot start. null whenever no cloud fetch is outstanding.
    @Volatile private var inFlightSynth: Job? = null

    // (C2) The AudioTrack is a LOCAL of its executor task — no other thread ever touches it.
    // Cancellation latency stays low because writes happen in ~100 ms slices with a generation
    // check between slices, instead of one blocking whole-sentence write.
    @Volatile private var focusRequest: AudioFocusRequest? = null
    private var unloadRunnable: Runnable? = null

    /** Kokoro speaker id (multi-lang v1_0, see TtsVoices; default af_heart is set by callers). This
     *  is the LOCAL voice — the default AND the one-way cloud fallback both read through it. */
    @Volatile var speakerId: Int = 0

    @Volatile var speed: Float = 1.0f

    /**
     * Cloud read-aloud selection, resolved by [TtsController] from prefs before each [speak].
     * null (the default and the shipped path) ⇒ pure on-device Kokoro, byte-identical to before.
     * When both this and [cloudVoiceId] are set, each clause unit is synthesized through this
     * provider with a ONE-WAY fall back to the local voice ([speakerId]) on failure — the local
     * voice stays the default and the fallback, and reading never stops.
     */
    @Volatile var cloudProvider: TtsProvider? = null
    @Volatile var cloudVoiceId: String? = null

    /**
     * Invoked (on the executor thread) when a cloud unit has ALREADY fallen back to local. Carries
     * the failure so [TtsController] can toast at most once per latched fatal; non-fatal fall-backs
     * pass through here too but stay silent. Never blocks — the read has already continued.
     */
    @Volatile var onCloudFallback: ((TtsError) -> Unit)? = null

    /**
     * Invoked (on the executor thread) AT MOST ONCE per read, the moment cloud soft-latches to local
     * after [CLOUD_SOFT_LATCH_THRESHOLD] consecutive non-fatal fall-backs — the circuit breaker that
     * stops a persistent transient (e.g. a Gemini safety refusal, or a plain 429) silently re-billing
     * every remaining clause. [TtsController] toasts once so the bleed is never invisible. The read
     * has already continued on the local voice; this only informs.
     */
    @Volatile var onCloudSoftLatch: (() -> Unit)? = null

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
        // A newer read supersedes any in-flight cloud fetch. The generation bump above already
        // cancels it LOGICALLY, but this speak() is queued behind the old task on the single-thread
        // executor and cannot start until it returns — so cancel the fetch to free the thread now,
        // rather than waiting out the transport timeout (finding: stop()/next-read responsiveness).
        inFlightSynth?.cancel()
        cancelIdleUnload()
        paused = false
        speaking = true
        fun cancelled() = generation.get() != myGen
        // Snapshot the cloud selection for THIS read on the calling thread, so a concurrent newer
        // speak() cannot swap the provider mid-read. null (the shipped default) ⇒ pure local path.
        val cloudSnap = cloudProvider
        val voiceSnap = cloudVoiceId
        val fallbackSnap = onCloudFallback
        val softLatchSnap = onCloudSoftLatch
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

                // ONE bank-append path shared by sherpa's callback and the cloud-synth seam below
                // (C2): producers only APPEND here; the playback thread remains the AudioTrack's sole
                // owner. Cloud PCM (24 kHz PCM16 mono, same ShortArray shape) drops in identically.
                fun appendToBank(pcm: ShortArray) {
                    synchronized(store) {
                        store.add(pcm)
                        storeTotal += pcm.size
                        availableSamples = storeTotal
                    }
                }

                // Cap-enforcing append shared by BOTH producers (finding #4). The sherpa callback
                // already honored AHEAD_CAP backpressure and the RETAIN_CAP ceiling, but the cloud
                // seam appended RAW — a slow-draining cloud read (playback paused/scrubbed while
                // fetches keep landing) could blow past the ~86 MB retention bound the caps exist to
                // hold. Both paths go through here now. Returns false when the retention cap is hit
                // (caller stops synthesizing this unit) or the read was cancelled mid-hold.
                fun appendWithCaps(pcm: ShortArray): Boolean {
                    while (!cancelled() && bankTooFarAhead(availableSamples, playedSamples, AHEAD_CAP_SAMPLES)) {
                        try { Thread.sleep(100) } catch (_: InterruptedException) {}
                    }
                    if (cancelled()) return false
                    if (bankRetentionExceeded(availableSamples, pcm.size, RETAIN_CAP_SAMPLES)) {
                        android.util.Log.w("WE-TTS", "retention cap hit — truncating synthesis")
                        return false
                    }
                    appendToBank(pcm)
                    return true
                }

                // The §6A.3 prebuffer gate (built 2026-08-01). dMax is the duration estimate of
                // the LONGEST unit this read can plan — the underrun law's d in rtf*d — from the
                // same chars-to-ms constant the splitter's own caps were derived with. Cloud reads
                // plan bigger units, so they gate on a bigger (ceiling-clamped) watermark.
                val bufferPolicy = TtsBufferPolicy(
                    dMaxMs = ((if (cloudSnap != null) ClauseSplitter.CLOUD_SPLIT_MAX_CHARS
                    else ClauseSplitter.SPLIT_MAX_CHARS) * ClauseSplitter.MS_PER_CHAR).toInt(),
                )

                playbackThread = Thread({
                    // Spec §6A.3: the render thread must outrank ordinary background threads or a
                    // busy device deschedules exactly the writer the 400 ms track buffer insures.
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                    val localTrack = newTrack(engine.sampleRate())
                    val slice = localTrack.sampleRate / 10
                    var cursor = 0L
                    var started = false
                    var stalled = false
                    // The gate clock: reset whenever gating begins (thread start; each stall), so
                    // CAP_MS bounds each individual hold, not the whole read.
                    var gateSinceMs = System.currentTimeMillis()
                    var gateLastAvail = -1L
                    var gateRingShown = false
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
                            // THE prebuffer gate (§6A.3, owner's hold-until-smooth directive):
                            // both START and RESUME wait for the watermark, not the first slice.
                            // A finished producer bypasses it entirely — the bank is all there
                            // will ever be, and the doneFlag+null exit below must stay reachable.
                            if ((!started || stalled) && !doneFlag.get()) {
                                val bufferedMs = TtsDiagMath.audioMs(
                                    (availableSamples - cursor).toInt().coerceAtLeast(0),
                                    localTrack.sampleRate,
                                )
                                // GROWTH-AWARE cap clock (on-device 2026-08-01, TTSDIAG gen=3):
                                // a wall-clock cap fired at 2.53 s with only 1260 ms banked
                                // against a 3375 ms watermark — overriding the healthy producer
                                // it was never meant for, straight into a 2568 ms audible stall.
                                // The cap exists to escape a STUCK producer, so its clock counts
                                // only no-growth time: any bank growth resets it, and a producer
                                // that is merely still working gets to reach the watermark.
                                if (availableSamples != gateLastAvail) {
                                    gateLastAvail = availableSamples
                                    gateSinceMs = System.currentTimeMillis()
                                }
                                val waitedMs = System.currentTimeMillis() - gateSinceMs
                                if (!bufferPolicy.shouldProceed(bufferedMs.toInt(), waitedMs, done = false)) {
                                    // The start hold is BUFFERING and must say so — without this
                                    // the speaking pill sits silent with a motionless aurora and
                                    // reads as the bubble vanishing (owner report 2026-08-01).
                                    if (!started && !gateRingShown) {
                                        gateRingShown = true
                                        onBuffering?.invoke(true)
                                    }
                                    // 20 ms tick (spec WAIT_TICK_MS): fine-grained enough for
                                    // cloud packet cadences, cheap enough to poll.
                                    try { Thread.sleep(20) } catch (_: InterruptedException) {}
                                    continue@loop
                                }
                                if (gateRingShown) {
                                    gateRingShown = false
                                    onBuffering?.invoke(false)
                                }
                            }
                            val pcm = readAt(cursor, slice)
                            if (pcm == null) {
                                if (doneFlag.get()) break@loop
                                if (started && !stalled) {
                                    stalled = true
                                    stallStartMs = System.currentTimeMillis()
                                    gateSinceMs = stallStartMs
                                    // Hysteresis: every stall raises the resume watermark, so the
                                    // read rebuilds a BIGGER lead instead of resuming on the first
                                    // slice and re-stalling at the next long unit.
                                    bufferPolicy.onStall()
                                    stallHeadStart = localTrack.playbackHeadPosition
                                    stallUnderStart = localTrack.underrunCount
                                    onBuffering?.invoke(true)
                                }
                                try { Thread.sleep(20) } catch (_: InterruptedException) {}
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
                        // AHEAD_CAP backpressure + RETAIN_CAP ceiling, shared with the cloud seam.
                        if (!appendWithCaps(pcm)) return 0
                        // Measure callback EXIT -> ENTRY so the AHEAD_CAP backpressure hold above
                        // never reads as slow synthesis (spec 6A.3). The FIRST burst (seq==0) is
                        // still logged but excluded from the summary percentiles: it carries the
                        // one-time cold-start cost (model warm-up, first espeak phonemisation) plus
                        // the ttfw-critical startup, so it is not representative of steady-state RTF.
                        // (Historical note: this once phonemised the WHOLE selection; post-splitter
                        // the first unit is clause-bounded at ClauseSplitter.SPLIT_MAX_CHARS (<=80
                        // chars), so seq==0's cost now scales with that first unit, not the selection.)
                        val prevExit = diagLastCallbackExitMs[0]
                        val synthMs = if (prevExit == 0L) entryMs - diagT0 else entryMs - prevExit
                        val seq = diagSentSeq.getAndIncrement()
                        val audMs = TtsDiagMath.audioMs(pcm.size, engine.sampleRate())
                        android.util.Log.i(TtsDiag.TAG, TtsDiag.sent(myGen, seq, pcm.size, audMs, synthMs))
                        if (seq > 0) {
                            diagRtfs.add(TtsDiagMath.rtf(synthMs, audMs))
                            diagTotalSynthMs.addAndGet(synthMs)
                            diagTotalAudMs.addAndGet(audMs)
                            // Same exclusions the diag already enforces (first burst skipped via
                            // seq > 0; synthMs measured exit->entry) — exactly the two RTF
                            // contaminants §6A.3 requires the gate's EWMA to avoid.
                            bufferPolicy.recordRtf(synthMs, audMs.toInt())
                        }
                        diagLastCallbackExitMs[0] = System.currentTimeMillis()
                        return 1
                    }
                }
                // Cloud read-aloud (this read only): a fatal latches the REST of the read to local.
                // hasCloud is false unless BOTH a provider and a voice were resolved (defence in
                // depth over TtsController, which sets them together) — so null ⇒ the exact prior
                // path and the regression contract holds byte-for-byte.
                // The cloud provider streams PCM straight into the SAME bank the on-device voice
                // fills, played by an AudioTrack built at engine.sampleRate(). A provider whose
                // sampleRate does not match the track rate would play at the wrong pitch/speed, so
                // consume TtsProvider.sampleRate here as a hard contract: a mismatch disables cloud
                // for this read (the local voice takes over) rather than trusting an unread field.
                val rateMatches = cloudSnap == null ||
                    cloudTrackRateMatches(cloudSnap.sampleRate, engine.sampleRate())
                if (cloudSnap != null && !rateMatches) {
                    android.util.Log.w(
                        "WE-TTS",
                        "cloud provider rate ${cloudSnap.sampleRate} != track ${engine.sampleRate()} — using local voice",
                    )
                }
                val hasCloud = cloudSnap != null && !voiceSnap.isNullOrBlank() && rateMatches
                var latchedFatal: FatalKind? = null
                // Circuit breaker (finding): a persistent NON-FATAL condition — a Gemini 200 with no
                // audio (safety refusal / preview quirk, which still bills input tokens), or a plain
                // 429 — otherwise re-bills every remaining clause silently, because planUnitOutcome
                // only latches on Fatal. Count consecutive non-fatal fall-backs; after
                // CLOUD_SOFT_LATCH_THRESHOLD in a row, soft-latch cloud to local for the REST of this
                // read (recoverable next read, unlike a Fatal) and fire onCloudSoftLatch ONCE so the
                // bleed is never invisible. A delivered unit resets the streak.
                var consecutiveSoft = 0
                var softLatched = false
                try {
                    // Bound each synthesis unit so no unit outruns the banked audio (underrun law,
                    // 6A.1) and — for the LOCAL path — sherpa's whole-utterance float[] stays small
                    // (OOM bound, 6A.5). A selection within the cap yields a single unit equal to
                    // `clean`, so short text takes the exact prior path; only long sentences become
                    // multiple units. Feed order is preserved and cancellation is re-checked between
                    // units so stop() still lands within one sherpa call (C1).
                    //
                    // The SEAM: each clause-bounded unit is one cloud synthesis when cloud is active
                    // and unlatched; on ANY cloud failure the SAME unit is re-synthesized locally
                    // (re-split to the local cap by localResplit) and the read continues (one-way
                    // valve, mirroring STT). Only a Fatal latches the rest of the read to local; a
                    // Transient/BadUnit falls just this unit back and cloud stays eligible for the
                    // next. The bank, callback, and playback thread are untouched — cloud PCM appends
                    // through the same appendToBank as sherpa.
                    fun local(unit: String) = engine.generateWithCallback(
                        text = unit, sid = speakerId, speed = speed, callback = callback,
                    )
                    // A cloud unit may be up to CLOUD_SPLIT_MAX_CHARS (banking enough audio to hide
                    // the next fetch's RTT). That is safe for cloud — its PCM never becomes a sherpa
                    // whole-utterance float[] — but a FALLBACK re-synthesizes the unit on-device, so
                    // it must be re-split back to the local cap first or the local OOM/underrun bound
                    // is violated. localResplit is used on every path that reaches sherpa with a unit
                    // that may exceed the local cap (cloud fall-back, and a latched-to-local unit that
                    // was planned at the cloud cap). The pure-local read plans at the local cap, so
                    // its units are already <= cap and take the byte-identical `local(unit)` path.
                    fun localResplit(unit: String) {
                        for (sub in ClauseSplitter.plan(unit, ClauseSplitter.SPLIT_MAX_CHARS)) {
                            if (cancelled()) return
                            local(sub)
                        }
                    }
                    val unitCap =
                        if (hasCloud) ClauseSplitter.CLOUD_SPLIT_MAX_CHARS else ClauseSplitter.SPLIT_MAX_CHARS
                    for (unit in ClauseSplitter.plan(clean, unitCap)) {
                        if (cancelled()) break
                        val latched = latchedFatal != null || softLatched
                        when (planUnitOutcome(hasCloud, latched, synthResult = null)) {
                            UnitAction.Cloud -> {
                                // runBlocking on the synthesis executor is deliberate: playback runs
                                // on its own thread and drains banked audio while this unit is in
                                // flight. The synth runs in an async child whose Job is published to
                                // [inFlightSynth]; stop() (and a superseding speak()) CANCEL it, which
                                // — via the transport's Call.await() invokeOnCancellation — aborts the
                                // in-flight OkHttp Call and unwinds await() with a CancellationException
                                // in ~one round of coroutine dispatch, instead of blocking this single
                                // synthesis thread for up to DEFAULT_TTS_TIMEOUT_MS (45 s) while a
                                // queued next read waits behind it. cloudSnap/voiceSnap are non-null.
                                val res = runCatching {
                                    runBlocking {
                                        val deferred = async {
                                            cloudSnap!!.synth(unit, voiceSnap!!, speed) { pcm ->
                                                // Same AHEAD_CAP/RETAIN_CAP ceiling as sherpa (finding
                                                // #4): a false return stops the provider streaming
                                                // this unit, exactly as onPcm's cancel contract says.
                                                appendWithCaps(pcm) && !cancelled()
                                            }
                                        }
                                        inFlightSynth = deferred
                                        try { deferred.await() } finally { inFlightSynth = null }
                                    }
                                }.getOrElse {
                                    // Threw, or was CANCELLED by stop()/a newer speak(): treat as a
                                    // non-fatal fall-back for THIS unit, exactly like a Transient. The
                                    // cancelled() checks below unwind the loop promptly; reading never
                                    // stops (and on cancellation, localResplit's own cancelled() guard
                                    // makes the fall-back a no-op).
                                    TtsResult.Failed(TtsError.Transient(null))
                                }
                                // A stop()/supersede that cancelled the fetch surfaces here as the
                                // synthetic Transient above — break BEFORE it is counted as a soft
                                // failure, so a user stop can never spuriously trip the breaker toast.
                                if (cancelled()) break
                                when (val post = planUnitOutcome(hasCloud, latched, res)) {
                                    UnitAction.Cancel -> break
                                    is UnitAction.LocalFallback -> {
                                        val err = (res as? TtsResult.Failed)?.error
                                        if (post.latchNow && err is TtsError.Fatal) {
                                            latchedFatal = err.kind
                                        } else {
                                            // Non-fatal fall-back: count it toward the breaker. Trip
                                            // the soft-latch (and toast once) at the threshold so a
                                            // persistent transient cannot bleed the whole article.
                                            consecutiveSoft++
                                            if (!softLatched && shouldSoftLatchCloud(consecutiveSoft)) {
                                                softLatched = true
                                                softLatchSnap?.invoke()
                                            }
                                        }
                                        if (err != null) fallbackSnap?.invoke(err)
                                        // The failed unit is at the cloud cap — re-split to the local
                                        // cap before it reaches sherpa.
                                        localResplit(unit)
                                    }
                                    else -> consecutiveSoft = 0 // Cloud delivered: reset the streak.
                                }
                            }
                            // UnitAction.Local: on-device voice. A latched cloud read left this unit
                            // at the cloud cap, so re-split; a pure-local read is already <= cap.
                            else -> if (hasCloud) localResplit(unit) else local(unit)
                        }
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
     * (~100 ms). Safe from any thread — never touches the AudioTrack (C2). Also cancels any
     * in-flight cloud fetch so a stop() during a slow POST frees the single synthesis thread at
     * once (aborting the OkHttp Call) rather than leaving it blocked for up to the 45 s TTS timeout
     * — otherwise a subsequent speak(), queued behind it, would appear to "do nothing".
     */
    fun stop() {
        generation.incrementAndGet()
        inFlightSynth?.cancel()
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
        // Sized in MILLISECONDS (spec §6A.2): `minBuf * 4` silently ranged 161-403 ms across
        // devices. 400 ms of mono PCM16 = rate * 2 bytes * 0.4 s; minBuf stays the floor. This
        // buffer is writer-deschedule insurance only — producer stalls are the gate's job.
        val bufferBytes = maxOf(minBuf, sampleRate * 2 * TRACK_BUFFER_MS / 1000)
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
            .setBufferSizeInBytes(bufferBytes)
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
        /** AudioTrack buffer, in ms (spec §6A.2): writer-deschedule insurance, not stall cover. */
        private const val TRACK_BUFFER_MS = 400
        private const val IDLE_UNLOAD_MS = 5 * 60_000L

        // Scrubber pipeline (24 kHz mono): synthesis holds when > 5 min is buffered ahead of
        // playback (~14 MB); total retention caps at 30 min (~86 MB) — enough for any real
        // read-aloud while bounding worst-case memory.
        private const val AHEAD_CAP_SAMPLES = 5L * 60 * 24_000
        private const val RETAIN_CAP_SAMPLES = 30L * 60 * 24_000
        private const val RETAIN_CAP_JOIN_MS = 35L * 60_000
    }
}

/**
 * The per-unit seam decision, extracted PURE so it is JVM-unit-testable without the AudioTrack /
 * sherpa / coroutine machinery around it (that is the whole point of the split — see
 * TtsEngineSeamTest). It carries the entire one-way valve in one function.
 *
 * [synthResult] is null on the PRE-synth call (decide whether this unit even attempts cloud) and
 * non-null on the POST-synth call (decide what to do with the cloud attempt's result):
 *  - no cloud, or already latched to local  → [UnitAction.Local]  (the byte-identical prior path)
 *  - cloud eligible (pre-synth)             → [UnitAction.Cloud]
 *  - cloud Done (post-synth)                → [UnitAction.Cloud]  (delivered; keep reading on cloud)
 *  - cloud Cancelled                        → [UnitAction.Cancel] (stop() landed; break the loop)
 *  - cloud Failed(Fatal)                    → [UnitAction.LocalFallback] latchNow = true
 *  - cloud Failed(non-fatal)                → [UnitAction.LocalFallback] latchNow = false
 */
fun planUnitOutcome(hasCloud: Boolean, latched: Boolean, synthResult: TtsResult?): UnitAction {
    if (synthResult == null) {
        return if (hasCloud && !latched) UnitAction.Cloud else UnitAction.Local
    }
    return when (synthResult) {
        is TtsResult.Done -> UnitAction.Cloud
        is TtsResult.Cancelled -> UnitAction.Cancel
        is TtsResult.Failed -> UnitAction.LocalFallback(latchNow = synthResult.error is TtsError.Fatal)
    }
}

/**
 * Consecutive NON-FATAL cloud fall-backs (Transient / BadUnit / Offline) after which cloud
 * soft-latches to local for the REST of this read. A persistent transient condition otherwise
 * re-bills EVERY unit of an article silently: [planUnitOutcome] only latches on Fatal, so a
 * Transient falls just its own unit to local and leaves cloud eligible for the next — with no
 * backoff and no toast. The concrete money case is a Gemini 200-with-no-audio (safety refusal or a
 * preview-model modality quirk), classified Transient but STILL billing input tokens: a 40-clause
 * article makes 40 billed no-audio POSTs, the user hears the whole piece in Kokoro, is billed 40
 * times, and sees nothing. A plain 429 behaves the same (unbilled, but hammers the key).
 *
 * 2 (in [2,3]) trips fast enough that a per-unit bleed cannot run a whole article, while tolerating
 * a single isolated hiccup. Unlike a Fatal latch this is RECOVERABLE next read — it only latches
 * THIS read.
 */
const val CLOUD_SOFT_LATCH_THRESHOLD = 2

/** True when [consecutiveSoftFailures] non-fatal cloud fall-backs in a row should soft-latch cloud
 *  to local for the rest of the read (a circuit breaker over the silent per-unit re-bill). */
fun shouldSoftLatchCloud(consecutiveSoftFailures: Int): Boolean =
    consecutiveSoftFailures >= CLOUD_SOFT_LATCH_THRESHOLD

/**
 * True when a cloud [TtsProvider.sampleRate] is compatible with the engine's AudioTrack rate. The
 * cloud PCM is appended into the SAME bank the on-device voice fills and played at the track rate,
 * so the two rates MUST be equal or the cloud audio plays at the wrong pitch/speed. This is where
 * [TtsProvider.sampleRate] is consumed — an interface field that would otherwise be dead: a
 * provider that lied about its rate is caught here and its read falls to the local voice.
 */
fun cloudTrackRateMatches(providerRate: Int, trackRate: Int): Boolean = providerRate == trackRate

/**
 * True when appending [incoming] samples would push the retained bank past [retainCap] — the
 * ceiling that bounds worst-case read-aloud memory (~86 MB at 24 kHz). Enforced on BOTH the sherpa
 * callback and the cloud append path so neither producer can grow the bank without bound.
 */
fun bankRetentionExceeded(available: Long, incoming: Int, retainCap: Long): Boolean =
    available + incoming > retainCap

/**
 * True when the synthesized frontier is more than [aheadCap] ahead of playback — the backpressure
 * signal that holds synthesis until the playback cursor (or a seek) drains the lead.
 */
fun bankTooFarAhead(available: Long, played: Long, aheadCap: Long): Boolean =
    available - played > aheadCap

/** What the producer loop does with one clause unit. See [planUnitOutcome]. */
sealed interface UnitAction {
    /** Synthesize this unit through the cloud provider (pre-synth); or it was delivered (post-synth). */
    data object Cloud : UnitAction

    /** Synthesize this unit through the local sherpa voice — no cloud attempt is made. */
    data object Local : UnitAction

    /**
     * The cloud attempt failed: re-synthesize THIS unit locally (reading never stops). [latchNow]
     * true (a Fatal) disables cloud for the REST of this read; false keeps cloud eligible next unit.
     */
    data class LocalFallback(val latchNow: Boolean) : UnitAction

    /** onPcm returned false — stop() cancelled the read; break the loop. */
    data object Cancel : UnitAction
}
