package com.whispereverywhere.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.whispereverywhere.MainActivity
import com.whispereverywhere.R
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.net.ConnectivityMonitor
import com.whispereverywhere.net.OkHttpTransport
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.LocalWhisperEngine
import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.TranscriptionEngine
import com.whispereverywhere.transcription.cloud.CloudTranscriptionEngine
import com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngine
import com.whispereverywhere.transcription.cloud.SttProviderFactory
import com.whispereverywhere.ui.components.BarWaveformView
import com.whispereverywhere.util.SpeechSegmenter
import com.whispereverywhere.util.StreamingAudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which engine a session should use, decided from three inputs that are otherwise entangled with
 * Android services (SharedPreferences, SecureStore, ConnectivityManager). Kept pure and free of
 * Context so the branch table itself is unit-testable without Robolectric — see
 * `EngineSelectionTest`.
 *
 * On-device is the default AND the only reachable outcome when [decideEngineChoice] is given a
 * null `sttProviderId` — that is the one-way valve: no combination of `hasKey` /
 * `hasValidatedNetwork` can select cloud without a provider having been explicitly chosen first,
 * and there is no path back from [CLOUD_WITH_FALLBACK] to itself once local has taken over — the
 * fallback happens INSIDE [FallbackTranscriptionEngine], never by re-selecting here.
 */
internal enum class EngineChoice { LOCAL_ONLY, LOCAL_NO_KEY, LOCAL_OFFLINE, CLOUD_WITH_FALLBACK, CLOUD_LIVE }

/**
 * Decision table (brief: Release C2a Task 6; C4 adds the live-streaming leaf):
 *  - no provider selected                    -> local only (unchanged, pre-cloud path)
 *  - provider selected, no key               -> local, one-time toast
 *  - provider selected, key, offline         -> local, one-time toast
 *  - provider selected, key, online          -> cloud batch POST, wrapped in the local fallback
 *  - + live flag on AND provider is OpenAI   -> cloud LIVE stream, wrapped in the SAME fallback
 *
 * The live leaf sits AFTER the local guards on purpose: the one-way valve is untouched, so no key
 * or no network still resolves to on-device — live never opens a socket the batch path would have
 * refused. Live is OpenAI-only because only OpenAI's BYOK Realtime WebSocket can stream; the flag
 * is inert for every other provider, whose batch path is byte-unchanged.
 */
internal fun decideEngineChoice(
    sttProviderId: String?,
    hasKey: Boolean,
    hasValidatedNetwork: Boolean,
    liveMode: Boolean = false,
): EngineChoice = when {
    sttProviderId == null -> EngineChoice.LOCAL_ONLY
    !hasKey -> EngineChoice.LOCAL_NO_KEY
    !hasValidatedNetwork -> EngineChoice.LOCAL_OFFLINE
    liveMode && sttProviderId == ProviderId.OPENAI.name -> EngineChoice.CLOUD_LIVE
    else -> EngineChoice.CLOUD_WITH_FALLBACK
}

/**
 * The stored preference names a usable provider only when it matches one of the STT adapters this
 * release ships — OpenAI, Gemini, ElevenLabs, or Soniox (C2b widened the set from OpenAI-only;
 * Soniox adds a 4th). Anything else — null, a foreign provider name, a stale/corrupt value —
 * resolves to null, which
 * [decideEngineChoice] then treats exactly like "no key": a build that predates a provider's
 * adapter can never attempt a cloud call for it. [SttProviderFactory] has a mapping for every id in
 * [STT_PROVIDERS], so a non-null result is always constructible.
 */
internal fun resolveSttProvider(raw: String?): ProviderId? =
    raw?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() }
        ?.takeIf { it in STT_PROVIDERS }

private val STT_PROVIDERS =
    setOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS, ProviderId.SONIOX)

class FloatingBubbleService : Service(),
    WhisperAccessibilityService.OnTextFieldFocusListener,
    WhisperAccessibilityService.OnTextSelectionListener,
    WhisperAccessibilityService.OnClipboardChangedListener,
    MediaSessionDetector.MediaPlaybackListener {

    // Read-aloud (Track F): the text captured by the live selection watcher. Non-null = the
    // idle bubble shows a speaker and a tap SPEAKS instead of recording. Cleared when the
    // selection collapses; never set while a session is active.
    @Volatile private var speakModeText: String? = null
    @Volatile private var isSpeakingNow = false
    private var morphRevertJob: Job? = null
    private lateinit var speechStopIcon: ImageView
    private lateinit var speakClipIcon: ImageView
    private lateinit var lockLobe: View
    private lateinit var speakerLobe: View
    private lateinit var ttsScrubber: com.whispereverywhere.ui.components.TtsScrubberView

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var bubbleContainer: FrameLayout
    private lateinit var bubbleIcon: ImageView
    private lateinit var processingRing: ImageView
    private lateinit var waveformView: BarWaveformView
    private lateinit var blobView: com.whispereverywhere.ui.components.BlobView
    private lateinit var recordingTimerText: android.widget.TextView
    private var recordingTimerJob: Job? = null
    private lateinit var processingTimeText: android.widget.TextView
    private lateinit var transcriptionPreviewContainer: View
    private lateinit var transcriptionEditText: android.widget.TextView
    private lateinit var transcriptionDeltaText: android.widget.TextView

    private lateinit var audioRecorder: StreamingAudioRecorder

    // Device-audio (playback) capture source — active INSTEAD of the mic during media sessions.
    private var playbackCapturer: com.whispereverywhere.audio.PlaybackAudioCapturer? = null
    @Volatile private var activeSource = com.whispereverywhere.audio.ActiveSource.MIC
    private var transcriptionEngine: TranscriptionEngine? = null

    // The on-device engine, ALWAYS reachable independently of whatever composite engine the user's
    // provider choice produced. MediaProjection audio belongs to third parties who never consented
    // to anything, so it is transcribed here and nowhere else — see SourceRoutedTranscriptionEngine.
    private var localEngine: LocalWhisperEngine? = null

    // Non-null ONLY when this service's engine can reach the network, i.e. exactly when the routing
    // gate has something to gate. On-device-only users keep the pre-cloud path untouched.
    private var sourceRouter: com.whispereverywhere.transcription.SourceRoutedTranscriptionEngine? = null

    // ONE HTTP client for the service's whole life. OkHttpTransport's default constructor builds a
    // fresh OkHttpClient — each with its own dispatcher threads and connection pool — so building a
    // transport per session would leak both on a bubble that stays up for days.
    private var httpTransport: com.whispereverywhere.net.OkHttpTransport? = null

    // The engine shape the user was last TOLD about, so a degraded mode is announced when it
    // changes rather than on every single recording.
    private var notifiedChoice: EngineChoice? = null

    // This session-family's cloud engine, kept ONLY so finalize can read its latched fatal — the
    // composite engine deliberately hides which member answered. Rebuilt with the wrapper each
    // session; nulled with it in onDestroy.
    private var lastCloudEngine: com.whispereverywhere.transcription.cloud.CloudTranscriptionEngine? = null

    // The C4 live engine, kept for the SAME reason as lastCloudEngine — finalize's latch-toast —
    // but a separate field because LiveTranscriptionEngine is not a CloudTranscriptionEngine (its
    // lastFatal() returns a FatalKind, not an SttError.Fatal). Exactly one of the two is non-null
    // per session: resolveTranscriptionEngine nulls both, then sets whichever branch built.
    private var lastLiveEngine: com.whispereverywhere.transcription.live.LiveTranscriptionEngine? = null

    // Frozen per session in resolveTranscriptionEngine: true only for a CLOUD_LIVE session. Read by
    // the delta surface to lift the preview strip into a TEXT_FIELD session (deltas render, never
    // inject). Batch/on-device sessions leave it false, so their behavior is byte-unchanged.
    @Volatile private var sessionIsLive = false

    // The live WS transport + reconnect executor, held for the service's life for the SAME reason
    // httpTransport is: a fresh OkHttpClient (dispatcher threads + connection pool) or a fresh
    // daemon scheduler thread per session would leak on a bubble that stays up for days. Built
    // lazily so on-device-only and batch-only users never allocate the streaming client.
    private var liveWsFactory: com.whispereverywhere.transcription.live.WebSocketFactory? = null
    private var liveReconnectScheduler: com.whispereverywhere.transcription.live.ReconnectScheduler? = null

    // The latched-fatal kind the user was last TOLD about — once per latch, not once per session:
    // dictating ten times against a dead key should produce one toast, not ten.
    private var notifiedFatalKind: com.whispereverywhere.transcription.cloud.FatalKind? = null
    private val speechSegmenter = SpeechSegmenter()
    private lateinit var mediaDetector: MediaSessionDetector

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recordingJob: Job? = null
    private var amplitudeJob: Job? = null
    private var pulseAnimator: ValueAnimator? = null
    private var connectionMonitorJob: Job? = null
    private var processingTimerJob: Job? = null

    private var currentState: BubbleState = BubbleState.IDLE
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastAction = 0
    private var isDragging = false
    private var isBubbleVisible = false
    private var shouldHideOnIdle = false
    private var showAnimator: ValueAnimator? = null
    private var hideAnimator: ValueAnimator? = null

    // Pin/lock state — kept in sync with PreferencesManager.overlayPinned
    private var isOverlayPinned = false

    // Long-press detection for pin toggle (500 ms threshold)
    private var longPressJob: kotlinx.coroutines.Job? = null
    private val LONG_PRESS_MS = 500L

    // Max time finalize waits for the transcription backlog to drain before force-ending the
    // session. Generous because a slow model (e.g. the large tier) can lag several segments behind
    // real time; bounded so a pathological run can't hang the bubble in FINALIZING forever.
    private val FINALIZE_TIMEOUT_MS = 300_000L

    // Untapped speaker morph reverts to the mic after this window (Track F).
    private val MORPH_REVERT_MS = 20_000L

    // Wall-clock cap per uncommitted stretch. Continuous loud audio (media playback, music) never
    // dips below the segmenter's silence floor, so its pause-based commit never fires — without
    // this cap the engine buffer grows unbounded and produces one giant end-of-session segment.
    private val MAX_SEGMENT_WALL_MS = 15_000L
    private var lastCommitWallMs = 0L

    // Set true when any transcription text is produced during a recording session; drives the
    // "No speech detected" feedback on stop so the user is not left with silent nothing.
    @Volatile private var sessionProducedText = false

    // Releases segment outcomes into the user's text in STRICT seq order. Recreated per session in
    // startRecording, because it starts at head 0 and the engine restarts seq numbering at 0 too.
    // Main-thread confined: every touch below is inside a Dispatchers.Main block.
    //
    // With the on-device engine this is a PROVABLE PASS-THROUGH — LocalWhisperEngine's executor is
    // single-threaded, so results always arrive with seq == head, each drain releases exactly the
    // segment that just resolved, and flush() below is always a no-op. Local delivery timing is
    // therefore unchanged by its presence; it earns its keep only when a second engine can have
    // more than one segment in flight.
    private var segmentOrderer = com.whispereverywhere.transcription.SegmentOrderer()

    // Transcription history (text only — user decision 2026-07-17): per-session accumulator,
    // persisted via TranscriptStore at finalize with rolling 14-day/10MB retention.
    private val transcriptStore by lazy {
        com.whispereverywhere.transcription.TranscriptStore(java.io.File(filesDir, "transcripts"))
    }
    private val sessionTranscript = StringBuilder()
    private var sessionStartMs = 0L

    // Pin icon view reference (lateinit; populated in createBubbleView)
    private lateinit var pinIcon: ImageView

    // Track the context for bubble display
    private var currentContext: BubbleContext = BubbleContext.NONE
    private var mediaTitle: String? = null

    // The session's ROUTING mode (inject into a field vs preview+clipboard+history), frozen at
    // the moment recording starts. currentContext keeps tracking focus/media live for the idle
    // bubble, but mid-session clicks (e.g. tapping a prompt field while a YouTube transcription
    // runs) must NOT reroute later segments — that split one transcript across two destinations.
    // Every routing decision between startRecording and finalize reads THIS, never currentContext.
    // Corollary (intended): a session STARTED on a focused field keeps typing into that field
    // even if media begins mid-session and the source hands over mic→stream — the session types
    // where the user aimed it; history accumulates via sessionTranscript in both modes.
    private var sessionContext: BubbleContext = BubbleContext.NONE

    // TEXT_FIELD session whose delivery degraded to clipboard (document apps, dead targets):
    // per-segment toasts are suppressed and the FULL transcript is copied once at finalize.
    @Volatile private var sessionClipboardFallback = false

    // Bounded-memory sink for non-text-field sessions (Task 7)
    private var transcriptSink: com.whispereverywhere.transcription.TranscriptSink? = null

    // Tracks the preview-collector coroutine so a second recording doesn't leave two collectors running (Fix I1)
    private var previewJob: kotlinx.coroutines.Job? = null

    private lateinit var params: WindowManager.LayoutParams

    private val app by lazy { WhisperEverywhereApp.getInstance() }
    private val connectivityMonitor by lazy { ConnectivityMonitor(this) }
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioRecorder = StreamingAudioRecorder(this)
        mediaDetector = MediaSessionDetector(this)

        // Foreground FIRST (satisfies the startForegroundService contract), and guarded: on
        // Android 12+/14+ this throws when the start context is disallowed or RECORD_AUDIO was
        // revoked (mic-type FGS). Without the guard a START_STICKY service crash-loops forever
        // after a permission revocation. Fail soft: log + stop, never crash.
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, WhisperEverywhereApp.NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(WhisperEverywhereApp.NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            android.util.Log.w("WE-DIAG",
                "startForeground rejected (${t.javaClass.simpleName}: ${t.message}) — stopping instead of crash-looping")
            stopSelf()
            return
        }

        // Overlay permission can be revoked while the service is not running; addView without it
        // throws. Same fail-soft treatment.
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.util.Log.w("WE-DIAG", "overlay permission missing — stopping bubble service")
            stopSelf()
            return
        }
        createBubbleView()

        // Always-on mode: the bubble appears immediately at the user's spot and lives there.
        // (Auto mode leaves it hidden until a text field / media event summons it.)
        if (alwaysOnMode()) {
            bubbleView.post { showBubbleAtRest() }
        }

        // Register for text field focus events
        registerFocusListener()

        // Start media playback detection
        mediaDetector.setListener(this)
        mediaDetector.startMonitoring()

        // Start monitoring accessibility service connection
        startConnectionMonitor()

        // Read-aloud exclusivity (Track F): the arbiter queries live session state and can ask
        // a capture session to finish gracefully (same path as a stop tap). The speech side is
        // registered by TtsController when its engine is first created.
        com.whispereverywhere.audio.AudioArbiter.isCapturing = {
            currentState == BubbleState.RECORDING || currentState == BubbleState.FINALIZING
        }
        com.whispereverywhere.audio.AudioArbiter.stopCaptureGracefully = {
            serviceScope.launch(Dispatchers.Main) {
                if (currentState == BubbleState.RECORDING) stopRecording()
            }
        }

        // Pre-warm the whisper context (model mmap + Adreno OpenCL kernel compile, ~7 s on the
        // GPU path) so the FIRST recording connects instantly instead of paying it inside
        // CONNECTING. Slightly delayed to keep service startup/view inflation snappy; queued on
        // the engine's own single-thread executor, so it never races a session.
        // Deliberately the LOCAL engine, not the session engine: the native context is the only
        // expensive thing to warm, and resolving the session engine here would (a) decide the
        // cloud/local question ~1.5 s after boot and then cache that answer for the service's
        // whole life, and (b) toast about a degraded mode before the user has asked for anything.
        serviceScope.launch {
            delay(1500)
            warmLocalEngine().prewarm()
        }
    }

    /**
     * Register this service as the focus listener.
     */
    private fun registerFocusListener() {
        WhisperAccessibilityService.setFocusListener(this)
        WhisperAccessibilityService.setSelectionListener(this)
        WhisperAccessibilityService.setClipboardListener(this)
    }

    // Attention pulse on the speaker lobe when something lands on the clipboard (user design
    // 2026-07-18): alternating red/blue with a breathing scale — "tap me to hear this".
    private var clipPulseAnimator: android.animation.ValueAnimator? = null

    override fun onClipboardChanged() {
        serviceScope.launch(Dispatchers.Main) {
            if (currentState != BubbleState.IDLE || isSpeakingNow) return@launch
            if (!com.whispereverywhere.tts.TtsController.isVoiceInstalled(this@FloatingBubbleService)) return@launch
            pulseSpeakerLobe()
        }
    }

    private fun pulseSpeakerLobe() {
        clipPulseAnimator?.cancel()
        speakerLobe.visibility = View.VISIBLE
        var flip = false
        clipPulseAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            repeatCount = 15 // ~7 s of attention, then settle
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                val scale = 1f + 0.18f * kotlin.math.sin(v * Math.PI.toFloat())
                speakerLobe.scaleX = scale
                speakerLobe.scaleY = scale
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: android.animation.Animator) {
                    flip = !flip
                    speakClipIcon.setColorFilter(
                        if (flip) 0xFFFF3B30.toInt() else 0xFF4FC3F7.toInt(),
                    )
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    speakerLobe.scaleX = 1f
                    speakerLobe.scaleY = 1f
                    speakClipIcon.setColorFilter(0xFF4FC3F7.toInt())
                }
            })
            start()
        }
    }

    private fun stopClipPulse() {
        clipPulseAnimator?.cancel()
        clipPulseAnimator = null
        speakerLobe.scaleX = 1f
        speakerLobe.scaleY = 1f
        speakClipIcon.setColorFilter(0xFF4FC3F7.toInt())
    }

    // ========== Read-aloud (Track F): selection -> speaker morph -> speak/stop ==========

    override fun onTextSelected(text: String) {
        serviceScope.launch(Dispatchers.Main) {
            // Never morph mid-session; selection during capture follows the session rules.
            val installed = com.whispereverywhere.tts.TtsController.isVoiceInstalled(this@FloatingBubbleService)
            android.util.Log.i(
                "WE-TTS",
                "morph check: state=$currentState speaking=$isSpeakingNow installed=$installed",
            )
            if (currentState != BubbleState.IDLE || isSpeakingNow) return@launch
            if (!installed) return@launch
            speakModeText = text
            bubbleIcon.setImageResource(R.drawable.ic_speaker)
            scheduleMorphRevert()
            // Hide the ~2 s model load inside the user's think-time between select and tap.
            com.whispereverywhere.tts.TtsController.preload(this@FloatingBubbleService)
        }
    }

    override fun onSelectionCleared() {
        serviceScope.launch(Dispatchers.Main) {
            if (isSpeakingNow) return@launch // keep the speaking pill while audio plays
            morphRevertJob?.cancel()
            speakModeText = null
            if (currentState == BubbleState.IDLE) bubbleIcon.setImageResource(R.drawable.ic_mic)
        }
    }

    /**
     * The morph EXPIRES (user decision 2026-07-18): an untapped speaker reverts to the mic
     * after a grace window, so the bubble never sticks in speak mode from an old selection.
     */
    private fun scheduleMorphRevert() {
        morphRevertJob?.cancel()
        morphRevertJob = serviceScope.launch(Dispatchers.Main) {
            delay(MORPH_REVERT_MS)
            if (!isSpeakingNow && currentState == BubbleState.IDLE) {
                speakModeText = null
                bubbleIcon.setImageResource(R.drawable.ic_mic)
            }
        }
    }

    /**
     * Read whatever is on the clipboard aloud. Android 10+ blocks background clipboard reads,
     * but the FOCUSED window is allowed — so the overlay grabs input focus for one beat, reads
     * the clip, and releases. (Steals focus momentarily; acceptable for an explicit tap.)
     */
    private fun readClipboardAndSpeak() {
        if (isSpeakingNow || currentState != BubbleState.IDLE) return
        stopClipPulse()
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        runCatching { windowManager.updateViewLayout(bubbleView, params) }
        bubbleView.postDelayed({
            val clip = runCatching {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            }.getOrNull()
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { windowManager.updateViewLayout(bubbleView, params) }
            if (!clip.isNullOrBlank()) {
                startSpeaking(clip.take(100_000))
            } else {
                showToast("Nothing readable on the clipboard yet — copy some text first")
            }
        }, 300)
    }

    /** Speaking pill: aurora waveform driven by the SYNTHESIZED audio + stop control. */
    private fun enterSpeakingVisuals() {
        bubbleIcon.visibility = View.GONE
        processingRing.visibility = View.GONE
        lockLobe.visibility = View.GONE
        speakerLobe.visibility = View.GONE
        setBubbleWidth(160)
        waveformView.visibility = View.VISIBLE
        waveformView.start()
        blobView.fillColor = android.graphics.Color.parseColor("#000000")
        blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.RECORDING)
        speechStopIcon.visibility = View.VISIBLE
        ttsScrubber.setProgress(0, 0, false)
        ttsScrubber.visibility = View.VISIBLE
    }

    private fun exitSpeakingVisuals() {
        speechStopIcon.visibility = View.GONE
        ttsScrubber.visibility = View.GONE
        processingRing.visibility = View.GONE
        processingRing.clearAnimation()
        waveformView.stop()
        // The IDLE branch restores icon/width/blob for the current morph state.
        if (currentState == BubbleState.IDLE) updateBubbleState(BubbleState.IDLE)
    }

    private fun startSpeaking(text: String) {
        morphRevertJob?.cancel()
        isSpeakingNow = true
        val engine = com.whispereverywhere.tts.TtsController.engine(this)
        // Feed the pill's aurora from the synthesized slices (same contract as mic chunks:
        // thread-safe field writes only — redraw is ticker-driven on main).
        engine.onPcmChunk = { bytes, amp ->
            val bands = if (amp > 350) {
                com.whispereverywhere.util.AudioBands.analyze(bytes, bytes.size)
            } else {
                com.whispereverywhere.util.AudioBands.ZERO
            }
            waveformView.updateBands(bands)
            blobView.updateBands(bands)
            waveformView.updateAmplitude(amp)
            blobView.updateAmplitude(amp)
        }
        // "Not frozen, still working": when synthesis ever falls behind playback, the pill
        // shows the processing ring until audio flows again (user feedback 2026-07-18).
        engine.onBuffering = { buffering ->
            serviceScope.launch(Dispatchers.Main) {
                if (isSpeakingNow) {
                    processingRing.visibility = if (buffering) View.VISIBLE else View.GONE
                    if (buffering) startRotationAnimation() else processingRing.clearAnimation()
                }
            }
        }
        // Scrubber feed: played/available/done at ~10 Hz from the playback thread.
        engine.onProgress = { played, available, done ->
            ttsScrubber.setProgress(played, available, done)
        }
        enterSpeakingVisuals()
        com.whispereverywhere.tts.TtsController.speakFromTrigger(this, text) {
            // onDone (main thread): tear down the pill; selection may still be live.
            isSpeakingNow = false
            engine.onPcmChunk = null
            engine.onBuffering = null
            engine.onProgress = null
            exitSpeakingVisuals()
            if (speakModeText != null) scheduleMorphRevert()
        }
    }

    /**
     * Monitor the accessibility service connection and re-register if needed.
     */
    private fun startConnectionMonitor() {
        connectionMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(5000)
                registerFocusListener()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Explicit user stop (notification action): record the intent here — onDestroy
                // must NOT do it, or programmatic restarts (mode toggle) clobber the preference.
                app.preferencesManager.setBubbleEnabled(false)
                stopSelf()
            }
        }
        registerFocusListener()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Order matters (final-review fix C3): kill the 5-second re-register monitor BEFORE
        // nulling the listeners, or it can re-install this dying service as the listener.
        connectionMonitorJob?.cancel()
        WhisperAccessibilityService.setFocusListener(null)
        WhisperAccessibilityService.setSelectionListener(null)
        WhisperAccessibilityService.setClipboardListener(null)
        // Detach the arbiter's capture side — its lambdas close over this dying instance.
        com.whispereverywhere.audio.AudioArbiter.isCapturing = { false }
        com.whispereverywhere.audio.AudioArbiter.stopCaptureGracefully = {}
        com.whispereverywhere.tts.TtsController.stop()
        mediaDetector.setListener(null)
        mediaDetector.stopMonitoring()
        serviceScope.cancel()
        pulseAnimator?.cancel()
        showAnimator?.cancel()
        hideAnimator?.cancel()
        audioRecorder.stop()
        stopPlaybackCapturer()
        com.whispereverywhere.audio.MediaProjectionGate.listener = null
        com.whispereverywhere.audio.MediaProjectionGate.clear()
        teardownRealtime()
        // Fully release on service end: free the native context and stop its worker thread
        // (teardownRealtime only detaches the session listener, for reuse).
        //
        // Order and target both matter. The wrapper is CLOSED (resolving anything it still owes,
        // and cleaning up the cloud engine, whose shutdown() is close()), then the local engine —
        // the sole owner of the native context — is SHUT DOWN exactly once. Going through
        // `transcriptionEngine` instead would leak the context whenever the service is destroyed
        // after prewarm but before any recording, because that field is still null at that point.
        sourceRouter?.close()
        sourceRouter = null
        transcriptionEngine = null
        localEngine?.shutdown()
        localEngine = null
        httpTransport = null
        liveWsFactory = null
        // Shut the reconnect scheduler's daemon executor down BEFORE nulling the field, or the
        // "realtime-reconnect" thread leaks for the life of the process (release-audit Minor A).
        (liveReconnectScheduler as? com.whispereverywhere.transcription.live.ExecutorReconnectScheduler)?.shutdown()
        liveReconnectScheduler = null
        notifiedChoice = null
        lastCloudEngine = null
        lastLiveEngine = null
        notifiedFatalKind = null
        try {
            windowManager.removeView(bubbleView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // NOTE: deliberately NOT setBubbleEnabled(false) here. onDestroy also runs for
        // programmatic restarts (bubble-mode toggle) and system kills — only explicit user
        // stops (HomeScreen toggle, notification Stop action) record disabled intent.
    }

    // ========== Configuration changes (rotation / fold — drift hardening) ==========

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-clamp bubble position to the updated screen bounds after a rotation or fold event.
        // Post to the next layout pass so displayMetrics already reflects the new orientation.
        bubbleView.post { reclampAfterConfigChange() }
    }

    // ========== Bubble display mode ==========

    /**
     * True = "always on screen": the bubble lives wherever the user placed/pinned it and NEVER
     * auto-moves or auto-hides; focus/media events only change what a tap dictates into.
     * False = "auto pop-up": classic behavior (appear near focused fields / during media, hide
     * when idle). User-facing toggle in Settings -> Preferences.
     */
    private fun alwaysOnMode(): Boolean = app.preferencesManager.isBubbleAlwaysOn()

    // ========== Text Field Focus Listener ==========

    override fun onTextFieldFocused(rect: Rect) {
        serviceScope.launch(Dispatchers.Main) {
            // Device-audio capture is its own activity (user decision 2026-07-18): focusing a
            // text field while capturing media FINISHES that session gracefully (full single
            // transcript -> clipboard + history). Dictation into the field then starts fresh on
            // the next bubble tap. Mic sessions are untouched — only the playback source
            // auto-stops — and focus events from our own app/overlay never trigger it.
            if (currentState == BubbleState.RECORDING &&
                activeSource == com.whispereverywhere.audio.ActiveSource.PLAYBACK &&
                WhisperAccessibilityService.lastFocusedFieldPackage() != packageName
            ) {
                android.util.Log.i("WE-DIAG", "field focused during media capture -> finishing session")
                stopRecording()
            }
            currentContext = BubbleContext.TEXT_FIELD
            shouldHideOnIdle = false
            // Always-on: the bubble is already where the user wants it — do not reposition.
            if (!alwaysOnMode()) showBubbleNearTextField(rect)
        }
    }

    override fun onTextFieldUnfocused() {
        serviceScope.launch(Dispatchers.Main) {
            if (currentContext == BubbleContext.TEXT_FIELD) {
                if (currentState == BubbleState.IDLE) {
                    delay(200)
                    if (currentState == BubbleState.IDLE && !WhisperAccessibilityService.hasActiveFocusedField()) {
                        // Check if media is playing - if so, switch to media context
                        if (mediaDetector.isCurrentlyPlaying()) {
                            currentContext = BubbleContext.MEDIA_PLAYBACK
                            if (!alwaysOnMode()) showBubbleForMedia()
                        } else {
                            currentContext = BubbleContext.NONE
                            hideBubble()
                        }
                    }
                } else {
                    shouldHideOnIdle = true
                }
            }
        }
    }

    // ========== Media Playback Listener ==========

    override fun onMediaPlaybackStarted(packageName: String, title: String?) {
        serviceScope.launch(Dispatchers.Main) {
            mediaTitle = title ?: getAppNameFromPackage(packageName)

            // Only show for media if no text field is focused
            if (currentContext != BubbleContext.TEXT_FIELD) {
                currentContext = BubbleContext.MEDIA_PLAYBACK
                if (!alwaysOnMode()) showBubbleForMedia()
            }

            // User decision: media transcription cuts the mic. If a mic recording is live when
            // playback begins (mic-button-first flow), hand over to the device stream.
            if (currentState == BubbleState.RECORDING &&
                activeSource == com.whispereverywhere.audio.ActiveSource.MIC &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                app.preferencesManager.isPreferDeviceAudio()
            ) {
                if (com.whispereverywhere.audio.MediaProjectionGate.hasProjection()) {
                    switchSource(to = com.whispereverywhere.audio.ActiveSource.PLAYBACK)
                } else {
                    // Flush + stop the mic NOW (never mix room audio into a media session),
                    // then ask; capture starts when consent lands.
                    transcriptionEngine?.commit()
                    audioRecorder.stop()
                    com.whispereverywhere.audio.MediaProjectionGate.listener = projectionListener
                    com.whispereverywhere.audio.MediaProjectionGate.requestConsent(this@FloatingBubbleService)
                    showToast("Allow screen capture to transcribe device audio")
                }
            }
        }
    }

    override fun onMediaPlaybackStopped() {
        serviceScope.launch(Dispatchers.Main) {
            mediaTitle = null

            // Only hide if we were showing for media
            if (currentContext == BubbleContext.MEDIA_PLAYBACK) {
                if (currentState == BubbleState.IDLE) {
                    currentContext = BubbleContext.NONE
                    hideBubble()
                } else {
                    // Recording in progress - will hide when done
                    shouldHideOnIdle = true
                }
            }

            // Media ended while capturing the stream: hand back to the microphone seamlessly.
            if (currentState == BubbleState.RECORDING &&
                activeSource == com.whispereverywhere.audio.ActiveSource.PLAYBACK
            ) {
                switchSource(to = com.whispereverywhere.audio.ActiveSource.MIC)
            }
        }
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            "Media"
        }
    }

    // ========== Bubble Display ==========

    private fun showBubbleForMedia() {
        if (isBubbleVisible && currentState != BubbleState.IDLE) {
            return
        }

        hideAnimator?.cancel()

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val bubbleSize = (56 * displayMetrics.density).toInt()
        val padding = (16 * displayMetrics.density).toInt()

        // Position bubble at bottom-right corner for media — unless pinned, in which case honor the
        // user's pinned spot so it does not jump to the default on re-show.
        var targetX = screenWidth - bubbleSize - padding
        var targetY = screenHeight - bubbleSize - padding - getNavigationBarHeight()
        if (isOverlayPinned) {
            val pinned = savedPinnedPosition(bubbleSize)
            targetX = pinned.first
            targetY = pinned.second
        }

        if (!isBubbleVisible) {
            params.x = targetX
            params.y = targetY
            bubbleView.alpha = 0f
            bubbleView.scaleX = 0.5f
            bubbleView.scaleY = 0.5f
            bubbleView.visibility = View.VISIBLE

            try {
                windowManager.updateViewLayout(bubbleView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            showAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = OvershootInterpolator(1.2f)
                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    bubbleView.alpha = value
                    bubbleView.scaleX = 0.5f + (0.5f * value)
                    bubbleView.scaleY = 0.5f + (0.5f * value)
                }
                start()
            }

            isBubbleVisible = true

            // Show toast to inform user
            showToast("Tap bubble to transcribe audio")
        } else {
            // Animate to position
            animateBubbleTo(targetX, targetY)
        }
    }

    private fun showBubbleNearTextField(rect: Rect) {
        if (isBubbleVisible && currentState != BubbleState.IDLE) {
            return
        }

        hideAnimator?.cancel()

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val bubbleSize = (56 * displayMetrics.density).toInt()
        val padding = (16 * displayMetrics.density).toInt()
        val statusBarHeight = getStatusBarHeight()

        var targetX = if (rect.right + bubbleSize + padding < screenWidth) {
            rect.right + padding
        } else {
            rect.left - bubbleSize - padding
        }

        var targetY = (rect.top - statusBarHeight).coerceIn(padding, screenHeight - bubbleSize - padding)

        // When pinned, honor the user's pinned spot instead of jumping to the text field.
        if (isOverlayPinned) {
            val pinned = savedPinnedPosition(bubbleSize)
            targetX = pinned.first
            targetY = pinned.second
        }

        if (!isBubbleVisible) {
            params.x = targetX
            params.y = targetY
            bubbleView.alpha = 0f
            bubbleView.scaleX = 0.5f
            bubbleView.scaleY = 0.5f
            bubbleView.visibility = View.VISIBLE

            try {
                windowManager.updateViewLayout(bubbleView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            showAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = OvershootInterpolator(1.2f)
                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    bubbleView.alpha = value
                    bubbleView.scaleX = 0.5f + (0.5f * value)
                    bubbleView.scaleY = 0.5f + (0.5f * value)
                }
                start()
            }

            isBubbleVisible = true
        } else {
            animateBubbleTo(targetX, targetY)
        }
    }

    /**
     * Show the bubble at its resting spot: the saved/pinned position (prefs-persisted; defaults
     * to bottom-right for fresh installs). Used by always-on mode, where the bubble never moves
     * itself — this is the only placement it ever gets.
     */
    private fun showBubbleAtRest() {
        if (isBubbleVisible) return
        hideAnimator?.cancel()

        val bubbleSize = (56 * resources.displayMetrics.density).toInt()
        val pos = savedPinnedPosition(bubbleSize)
        params.x = pos.first
        params.y = pos.second
        bubbleView.alpha = 0f
        bubbleView.scaleX = 0.5f
        bubbleView.scaleY = 0.5f
        bubbleView.visibility = View.VISIBLE

        try {
            windowManager.updateViewLayout(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        showAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                bubbleView.alpha = value
                bubbleView.scaleX = 0.5f + (0.5f * value)
                bubbleView.scaleY = 0.5f + (0.5f * value)
            }
            start()
        }

        isBubbleVisible = true
    }

    private fun animateBubbleTo(targetX: Int, targetY: Int) {
        val startX = params.x
        val startY = params.y

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                params.x = (startX + (targetX - startX) * progress).toInt()
                params.y = (startY + (targetY - startY) * progress).toInt()
                try {
                    windowManager.updateViewLayout(bubbleView, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            start()
        }
    }

    private fun hideBubble() {
        // Always-on mode: the bubble never auto-hides. (Service stop removes the window itself.)
        if (alwaysOnMode()) return
        if (!isBubbleVisible) return

        showAnimator?.cancel()

        hideAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                bubbleView.alpha = value
                bubbleView.scaleX = 0.5f + (0.5f * value)
                bubbleView.scaleY = 0.5f + (0.5f * value)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    bubbleView.visibility = View.GONE
                    isBubbleVisible = false
                }
            })
            start()
        }
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    private fun getNavigationBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    private fun createBubbleView() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        bubbleContainer = bubbleView.findViewById(R.id.bubble_container)
        bubbleIcon = bubbleView.findViewById(R.id.bubble_icon)
        processingRing = bubbleView.findViewById(R.id.processing_ring)
        waveformView = bubbleView.findViewById(R.id.waveform_view)
        blobView = bubbleView.findViewById(R.id.blob_view)
        recordingTimerText = bubbleView.findViewById(R.id.recording_timer_text)
        blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_background)
        processingTimeText = bubbleView.findViewById(R.id.processing_time_text)
        transcriptionPreviewContainer = bubbleView.findViewById(R.id.transcription_preview_container)
        transcriptionEditText = bubbleView.findViewById(R.id.transcription_edit_text)
        transcriptionDeltaText = bubbleView.findViewById(R.id.transcription_delta_text)
        pinIcon = bubbleView.findViewById(R.id.pin_icon)
        speechStopIcon = bubbleView.findViewById(R.id.speech_stop_icon)
        speechStopIcon.setOnClickListener { com.whispereverywhere.tts.TtsController.stop() }
        speakClipIcon = bubbleView.findViewById(R.id.speak_clip_icon)
        lockLobe = bubbleView.findViewById(R.id.lock_lobe)
        speakerLobe = bubbleView.findViewById(R.id.speaker_lobe)
        lockLobe.setOnClickListener { togglePin() }
        speakerLobe.setOnClickListener { readClipboardAndSpeak() }
        ttsScrubber = bubbleView.findViewById(R.id.tts_scrubber)
        ttsScrubber.onSeek = { fraction ->
            com.whispereverywhere.tts.TtsController.engine(this).seekToFraction(fraction)
        }

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        // Restore position and clamp to current screen bounds (drift hardening)
        val displayMetrics = resources.displayMetrics
        val rawX = (app.preferencesManager.bubblePositionX * displayMetrics.widthPixels).toInt()
        val rawY = (app.preferencesManager.bubblePositionY * displayMetrics.heightPixels).toInt()
        // Use a reasonable bubble size estimate for clamping before the view is measured.
        // The actual measured size is used in onConfigurationChanged after layout.
        val estimatedSize = (64 * displayMetrics.density).toInt()
        val clamped = clampToBounds(rawX, rawY, estimatedSize, estimatedSize)
        params.x = clamped.first
        params.y = clamped.second

        // Restore pinned state
        isOverlayPinned = app.preferencesManager.overlayPinned
        applyPinIndicator()

        // Wire up the pin icon tap — toggles pin state without triggering the bubble click

        bubbleView.setOnTouchListener { _, event ->
            handleTouch(event)
        }

        transcriptionEditText.movementMethod = android.text.method.ScrollingMovementMethod()

        windowManager.addView(bubbleView, params)

        bubbleView.visibility = View.GONE
        bubbleView.alpha = 0f
        isBubbleVisible = false

        updateBubbleState(BubbleState.IDLE)
    }

    // ========== Pin / Lock ==========

    /** Toggle pinned state, persist, show feedback. */
    private fun togglePin() {
        isOverlayPinned = !isOverlayPinned
        app.preferencesManager.overlayPinned = isOverlayPinned
        if (isOverlayPinned) {
            // Persist the current spot so the pinned position survives hide/show and app restarts.
            val dm = resources.displayMetrics
            if (dm.widthPixels > 0) app.preferencesManager.bubblePositionX = params.x.toFloat() / dm.widthPixels
            if (dm.heightPixels > 0) app.preferencesManager.bubblePositionY = params.y.toFloat() / dm.heightPixels
        }
        applyPinIndicator()
        showToast(if (isOverlayPinned) "Bubble locked in place" else "Bubble unlocked")
    }

    /**
     * Lock metaphor (user decisions 2026-07-18): closed RED lock = position locked,
     * open white/gray lock = free placement.
     */
    private fun applyPinIndicator() {
        pinIcon.setImageResource(
            if (isOverlayPinned) R.drawable.ic_lock_closed else R.drawable.ic_lock_open,
        )
        if (isOverlayPinned) {
            pinIcon.setColorFilter(0xFFFF3B30.toInt())
            pinIcon.alpha = 1.0f
        } else {
            pinIcon.clearColorFilter()
            pinIcon.alpha = 0.6f
        }
    }

    // ========== Drift hardening helpers ==========

    /**
     * Clamp (x, y) so the bubble view (viewW x viewH) stays fully on screen.
     * Falls back gracefully when screen size is not yet determined.
     */
    private fun clampToBounds(x: Int, y: Int, viewW: Int, viewH: Int): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - viewW).coerceAtLeast(0)
        val maxY = (dm.heightPixels - viewH).coerceAtLeast(0)
        return Pair(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    /** The user's pinned bubble position from prefs (stored as screen fractions), in px, clamped. */
    private fun savedPinnedPosition(size: Int): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val x = (app.preferencesManager.bubblePositionX * dm.widthPixels).toInt()
        val y = (app.preferencesManager.bubblePositionY * dm.heightPixels).toInt()
        return clampToBounds(x, y, size, size)
    }

    /** Re-clamp and persist after a configuration change (rotation / fold). */
    private fun reclampAfterConfigChange() {
        val viewW = if (bubbleView.width > 0) bubbleView.width else (64 * resources.displayMetrics.density).toInt()
        val viewH = if (bubbleView.height > 0) bubbleView.height else viewW
        val clamped = clampToBounds(params.x, params.y, viewW, viewH)
        params.x = clamped.first
        params.y = clamped.second
        try {
            windowManager.updateViewLayout(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val dm = resources.displayMetrics
        if (dm.widthPixels > 0) {
            app.preferencesManager.bubblePositionX = params.x.toFloat() / dm.widthPixels
        }
        if (dm.heightPixels > 0) {
            app.preferencesManager.bubblePositionY = params.y.toFloat() / dm.heightPixels
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                lastAction = event.action
                isDragging = false

                // Long-press fires after LONG_PRESS_MS to toggle pin, but only if we haven't
                // started dragging in the meantime (cancelled in ACTION_MOVE / ACTION_UP).
                longPressJob?.cancel()
                longPressJob = serviceScope.launch {
                    delay(LONG_PRESS_MS)
                    // Only fire if still holding down and not dragging
                    if (!isDragging) {
                        togglePin()
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                    isDragging = true
                    longPressJob?.cancel()   // Real drag — cancel long-press
                }

                // When pinned, suppress all drag movement; only taps (and long-press) register
                if (!isOverlayPinned && isDragging) {
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(bubbleView, params)
                }
                lastAction = event.action
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressJob?.cancel()
                longPressJob = null
                if (!isDragging) {
                    handleBubbleClick()
                } else if (!isOverlayPinned) {
                    // Always-on mode: FREE placement — the bubble stays exactly where the user
                    // drops it (middle of the screen included). Classic auto mode keeps the
                    // chat-head edge snap.
                    if (!alwaysOnMode()) snapToEdge()
                    val displayMetrics = resources.displayMetrics
                    app.preferencesManager.bubblePositionX = params.x.toFloat() / displayMetrics.widthPixels
                    app.preferencesManager.bubblePositionY = params.y.toFloat() / displayMetrics.heightPixels
                }
                lastAction = event.action
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressJob?.cancel()
                longPressJob = null
                lastAction = event.action
                return true
            }
        }
        return false
    }

    private fun setBubbleWidth(dp: Int) {
        val target = (dp * resources.displayMetrics.density).toInt()
        val lp = bubbleContainer.layoutParams
        if (lp.width == target) return
        // The blob body tracks the container with its 24dp ripple headroom (12dp per side).
        val blobLp = blobView.layoutParams
        val blobExtra = (24 * resources.displayMetrics.density).toInt()
        ValueAnimator.ofInt(lp.width, target).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                lp.width = it.animatedValue as Int
                bubbleContainer.layoutParams = lp
                blobLp.width = lp.width + blobExtra
                blobView.layoutParams = blobLp
            }
            start()
        }
    }

    private fun snapToEdge() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val targetX = if (params.x < screenWidth / 2) 0 else screenWidth - bubbleView.width

        val animator = ValueAnimator.ofInt(params.x, targetX)
        animator.duration = 200
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.addUpdateListener { animation ->
            params.x = animation.animatedValue as Int
            try {
                windowManager.updateViewLayout(bubbleView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        animator.start()
    }

    private fun handleBubbleClick() {
        when (currentState) {
            BubbleState.IDLE -> when {
                // Tap while speaking = pause/resume (the small ✕ stops); user decision.
                isSpeakingNow -> {
                    val nowPaused = com.whispereverywhere.tts.TtsController
                        .engine(this).togglePause()
                    bubbleIcon.visibility = if (nowPaused) View.VISIBLE else View.GONE
                    if (nowPaused) bubbleIcon.setImageResource(R.drawable.ic_speaker)
                }
                speakModeText != null -> startSpeaking(speakModeText!!)
                else -> startRecording()
            }
            BubbleState.RECORDING -> stopRecording()
            BubbleState.CONNECTING, BubbleState.FINALIZING, BubbleState.PROCESSING -> { /* ignore */ }
            BubbleState.ERROR -> updateBubbleState(BubbleState.IDLE)
        }
    }

    // ========== Audio source state machine (mic <-> device-audio playback capture) ==========

    /** Shared downstream for BOTH audio sources (mic and playback capture), ROUTED BY SOURCE.
     *  Runs on the capture/recorder THREAD: the View calls below are thread-safe field writes
     *  only (redraw is ticker-driven on main) — do not add invalidate() here. */
    private fun onAudioChunk(chunk: ByteArray, amp: Int) {
        // Route by SOURCE, never by preference. Playback capture is OTHER APPS' audio — the person
        // on the other end of the call, the podcast host — so the only engines allowed to receive
        // it are the source-aware router (which hands it to the on-device engine and closes the
        // cloud one for the duration) and the on-device engine itself. Microphone audio, which is
        // the user's own voice, follows the user's own provider choice.
        val engine = if (activeSource == com.whispereverywhere.audio.ActiveSource.PLAYBACK) {
            sourceRouter ?: localEngine ?: return
        } else {
            transcriptionEngine ?: return
        }
        engine.sendAudio(chunk)
        // 4-band spectral drive for the visuals (microseconds per 32ms frame): each aurora
        // sheet + rim region follows its own slice of the audio. Silence gate: below the noise
        // floor send explicit zeros so the AGC never normalizes room noise into fake motion.
        val bands = if (amp > 350) {
            com.whispereverywhere.util.AudioBands.analyze(chunk, chunk.size)
        } else {
            com.whispereverywhere.util.AudioBands.ZERO
        }
        waveformView.updateBands(bands)
        blobView.updateBands(bands)
        waveformView.updateAmplitude(amp)
        blobView.updateAmplitude(amp)
        // Client VAD per audio chunk. Commit on a natural pause, or on the wall-clock cap when
        // the amplitude never dips (continuous media).
        val now = System.currentTimeMillis()
        if (speechSegmenter.onAmplitude(amp, now)) {
            android.util.Log.i("WE-DIAG", "VAD -> commit (rms=$amp)")
            lastCommitWallMs = now
            engine.commit()
        } else if (now - lastCommitWallMs >= MAX_SEGMENT_WALL_MS) {
            android.util.Log.i("WE-DIAG", "wall-clock cap -> commit")
            lastCommitWallMs = now
            engine.commit()
            speechSegmenter.reset()
        }
    }

    /**
     * Stop and drop the playback capturer. The capturer class is @RequiresApi(Q); instances
     * only ever exist on Q+ (startPlaybackSource is version-gated), so the check here is for
     * the linter's benefit — it can't prove the field is null below Q.
     */
    private fun stopPlaybackCapturer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) playbackCapturer?.stop()
        playbackCapturer = null
    }

    /** Start the correct source for the current world (mic vs device-audio). */
    private fun startAudioInput(): Result<Unit> {
        val decision = com.whispereverywhere.audio.AudioSourcePolicy.decide(
            mediaPlaying = mediaDetector.isCurrentlyPlaying(),
            hasProjection = com.whispereverywhere.audio.MediaProjectionGate.hasProjection(),
            sdkInt = Build.VERSION.SDK_INT,
            preferDeviceAudio = app.preferencesManager.isPreferDeviceAudio(),
        )
        android.util.Log.i("WE-DIAG", "startAudioInput: decision=$decision")
        return when (decision) {
            com.whispereverywhere.audio.SourceDecision.UseMic -> startMicSource()
            com.whispereverywhere.audio.SourceDecision.UsePlayback ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startPlaybackSource() else startMicSource()
            com.whispereverywhere.audio.SourceDecision.RequestConsent -> {
                // Ask once; capture begins when consent arrives (projectionListener). The mic
                // is NOT opened meanwhile — media capture must never mix room audio.
                // KNOWN GAP (accepted): until the user answers (typically 1-3s), the session
                // shows RECORDING with no live source; grant starts capture, deny/back falls
                // back to mic. NOTE: MediaProjectionGate.listener is a single process-global
                // slot — safe because only one FloatingBubbleService instance is ever live.
                com.whispereverywhere.audio.MediaProjectionGate.listener = projectionListener
                com.whispereverywhere.audio.MediaProjectionGate.requestConsent(this)
                showToast("Allow screen capture to transcribe device audio")
                Result.success(Unit)
            }
        }
    }

    /**
     * The ONE place `activeSource` changes while a session is live, so the engine route can never
     * drift from the physical source. Routing is switched BEFORE the new capturer is started: a
     * capturer delivers its first chunk from its own thread the moment it starts, and a chunk that
     * arrives while the route still names the previous source is exactly the leak this guards.
     */
    private fun setActiveSource(source: com.whispereverywhere.audio.ActiveSource) {
        activeSource = source
        sourceRouter?.onSourceChanged(source)
    }

    private fun startMicSource(): Result<Unit> {
        setActiveSource(com.whispereverywhere.audio.ActiveSource.MIC)
        return audioRecorder.start(::onAudioChunk)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun startPlaybackSource(): Result<Unit> {
        val projection = com.whispereverywhere.audio.MediaProjectionGate.projectionOrNull()
            ?: return startMicSource()
        val capturer = com.whispereverywhere.audio.PlaybackAudioCapturer(projection) {
            // DRM opt-out / silent stream: fall back to the microphone, on the main thread.
            serviceScope.launch(Dispatchers.Main) {
                if (activeSource == com.whispereverywhere.audio.ActiveSource.PLAYBACK &&
                    currentState == BubbleState.RECORDING
                ) {
                    showToast("This app blocks audio capture — using microphone")
                    switchSource(to = com.whispereverywhere.audio.ActiveSource.MIC)
                }
            }
        }
        // Route FIRST, capture second. capturer.start() spawns its own thread and can deliver a
        // chunk before the next statement runs, so flipping the source afterwards would hand the
        // first frames of other people's audio to whatever engine the microphone was using.
        setActiveSource(com.whispereverywhere.audio.ActiveSource.PLAYBACK)
        val started = capturer.start(::onAudioChunk)
        return if (started.isSuccess) {
            playbackCapturer = capturer
            showToast("Capturing device audio")
            started
        } else {
            android.util.Log.w("WE-DIAG", "playback capture failed to start -> mic fallback")
            // Restores the microphone route as well: nothing was captured, so nothing is lost.
            startMicSource()
        }
    }

    /** Commit the pending segment, stop the current source, start the other. Main thread only. */
    private fun switchSource(to: com.whispereverywhere.audio.ActiveSource) {
        if (activeSource == to || currentState != BubbleState.RECORDING) return
        android.util.Log.i("WE-DIAG", "switchSource: $activeSource -> $to")
        // On-device-only: one engine serves both sources, so this commit is what keeps mic and
        // device audio in SEPARATE segments. Unchanged from before the routing gate existed.
        // With a router the boundary belongs to it: it decides per direction whether the
        // uncommitted tail may follow the user into the next engine (mic audio may; device audio
        // never may), and committing here would instead cut that tail into the engine we are about
        // to close. See SourceRoutedTranscriptionEngine.onSourceChanged.
        if (sourceRouter == null) transcriptionEngine?.commit()
        speechSegmenter.reset()
        lastCommitWallMs = System.currentTimeMillis()
        when (activeSource) {
            com.whispereverywhere.audio.ActiveSource.MIC -> audioRecorder.stop()
            com.whispereverywhere.audio.ActiveSource.PLAYBACK -> stopPlaybackCapturer()
        }
        val started = when (to) {
            com.whispereverywhere.audio.ActiveSource.MIC -> startMicSource()
            com.whispereverywhere.audio.ActiveSource.PLAYBACK ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startPlaybackSource() else startMicSource()
        }
        if (started.isFailure) {
            android.util.Log.w("WE-DIAG",
                "switchSource: $to failed to start (${started.exceptionOrNull()?.message})")
        }
    }

    private val projectionListener = object : com.whispereverywhere.audio.MediaProjectionGate.Listener {
        override fun onConsentGranted(resultCode: Int, data: Intent) {
            serviceScope.launch(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@launch
                // ANDROID 14+ ORDERING: foreground with mediaProjection type BEFORE
                // getMediaProjection. Guarded like onCreate's startForeground — this call can
                // throw on 14+ (restricted start / revoked permission) and an uncaught throw
                // would crash-loop the START_STICKY service. On failure: mic fallback.
                try {
                    ServiceCompat.startForeground(
                        this@FloatingBubbleService,
                        WhisperEverywhereApp.NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                    )
                } catch (t: Throwable) {
                    android.util.Log.w("WE-DIAG",
                        "projection foreground upgrade rejected (${t.javaClass.simpleName}) -> mic")
                    if (currentState == BubbleState.RECORDING) startMicSource()
                    return@launch
                }
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                val projection = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
                if (projection == null) {
                    android.util.Log.w("WE-DIAG", "getMediaProjection returned null -> mic")
                    if (currentState == BubbleState.RECORDING) startMicSource()
                    return@launch
                }
                projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        com.whispereverywhere.audio.MediaProjectionGate.clear()
                    }
                }, null)
                com.whispereverywhere.audio.MediaProjectionGate.storeProjection(projection)
                if (currentState == BubbleState.RECORDING &&
                    activeSource != com.whispereverywhere.audio.ActiveSource.PLAYBACK
                ) {
                    startPlaybackSource()
                }
            }
        }

        override fun onConsentDenied() {
            serviceScope.launch(Dispatchers.Main) {
                if (currentState == BubbleState.RECORDING) {
                    showToast("Using microphone (capture permission declined)")
                    startMicSource()
                }
            }
        }
    }

    /**
     * The warm on-device engine. Built once and kept for the service's whole life, because loading
     * the native context costs seconds (model mmap + ~7 s Adreno OpenCL kernel compile). It
     * OUTLIVES every cloud wrapper built around it, and it is the only object here that owns a
     * native resource — so it is also the only one that may be `shutdown()`.
     */
    private fun warmLocalEngine(): LocalWhisperEngine =
        localEngine ?: LocalWhisperEngine(app.whisperModelManager).also { localEngine = it }

    /** One shared client for the service's life — see the [httpTransport] field comment. */
    private fun sharedTransport(): com.whispereverywhere.net.OkHttpTransport =
        httpTransport ?: com.whispereverywhere.net.OkHttpTransport().also { httpTransport = it }

    /**
     * One WebSocket factory for the service's life — see the [liveWsFactory] field comment. Reuses
     * the standard client CONFIG via [com.whispereverywhere.net.OkHttpTransport.defaultClient]
     * (OkHttpTransport keeps its own client private, and HttpTransport.kt is outside this task's
     * files); [com.whispereverywhere.transcription.live.OkHttpWebSocketFactory] then derives the
     * no-timeout, ping-kept streaming variant the long-lived socket needs.
     */
    private fun sharedLiveWsFactory(): com.whispereverywhere.transcription.live.WebSocketFactory =
        liveWsFactory ?: com.whispereverywhere.transcription.live.OkHttpWebSocketFactory(
            com.whispereverywhere.net.OkHttpTransport.defaultClient(),
        ).also { liveWsFactory = it }

    /** One reconnect scheduler (a single daemon thread) for the service's life — see the field comment. */
    private fun sharedLiveReconnectScheduler(): com.whispereverywhere.transcription.live.ReconnectScheduler =
        liveReconnectScheduler
            ?: com.whispereverywhere.transcription.live.ExecutorReconnectScheduler().also { liveReconnectScheduler = it }

    /**
     * User-facing copy for a live-session [com.whispereverywhere.transcription.cloud.FatalKind],
     * kept word-for-word identical to the batch providers' `SttError.Fatal` messages so the
     * latch-toast reads the same whichever transport failed (batch carries its own message string;
     * the live WS carries only a kind — the handshake body is off-limits for credential safety).
     */
    private fun liveFatalMessage(kind: com.whispereverywhere.transcription.cloud.FatalKind): String =
        when (kind) {
            com.whispereverywhere.transcription.cloud.FatalKind.INVALID_KEY -> "Key rejected"
            com.whispereverywhere.transcription.cloud.FatalKind.FORBIDDEN -> "Access denied for this key"
            com.whispereverywhere.transcription.cloud.FatalKind.OUT_OF_CREDIT -> "Account has no remaining credit"
            com.whispereverywhere.transcription.cloud.FatalKind.MODEL_UNAVAILABLE -> "Transcription model unavailable"
        }

    /**
     * Resolves this session's transcription engine, RE-DECIDING the cloud/local question at every
     * session start.
     *
     * It used to decide once and cache for the service's lifetime, which made three user actions
     * silently ineffective on a bubble that stays up for days: selecting "On-device" did not stop
     * uploads, deleting the API key did not stop uploads (OpenAiStt captures the key by value at
     * construction), and selecting cloud did nothing until the service was restarted. The privacy
     * policy ships the sentence "switching back to on-device or removing the key stops all
     * transmission to that provider immediately" — this function is what makes that true. It also
     * re-samples connectivity, so a bubble started in a tunnel is no longer pinned to on-device
     * for the rest of the day.
     *
     * Rebuilding is cheap BECAUSE the expensive part is deliberately not rebuilt: [warmLocalEngine]
     * and [sharedTransport] persist, and only the thin wrappers that carry the user's choice (and
     * the key) are recreated.
     *
     * Cloud is opt-in per provider and ALWAYS wrapped in the local fallback: [decideEngineChoice]
     * has exactly one path to [EngineChoice.CLOUD_WITH_FALLBACK] and it requires a provider to
     * have been selected first (`prefs.sttProviderId != null`) — on-device stays reachable with
     * zero configuration and cloud can never be chosen implicitly. No key or no validated network
     * is never presented as a failure: the on-device model still answers.
     */
    private fun resolveTranscriptionEngine(): TranscriptionEngine {
        val providerId = app.preferencesManager.sttProviderId
        val provider = resolveSttProvider(providerId)
        val key = provider?.let { app.preferencesManager.providerAccounts.key(it) }

        val choice = decideEngineChoice(
            sttProviderId = providerId,
            hasKey = !key.isNullOrBlank(),
            hasValidatedNetwork = connectivityMonitor.hasValidatedNetwork(),
            liveMode = app.preferencesManager.sttLiveMode,
        )
        android.util.Log.i("WE-DIAG", "resolveTranscriptionEngine: providerId=$providerId choice=$choice")

        // Retire the previous session's wrapper. close(), NEVER shutdown(): close() resolves
        // everything the wrapper still owes and detaches it, whereas shutdown() cascades to
        // LocalWhisperEngine.shutdown() and releases the native context — the one thing that must
        // survive between sessions.
        sourceRouter?.close()
        sourceRouter = null
        // A stale latch must not outlive its engine: after switching to on-device, finalize must
        // not toast about a fatal from a previous session's cloud engine. Both cloud references are
        // cleared here; the chosen branch below re-sets exactly one (or neither, for local).
        lastCloudEngine = null
        lastLiveEngine = null
        // Frozen fresh each session and read by the delta surface; default off so batch/on-device
        // sessions keep their exact behavior. Only the CLOUD_LIVE branch flips it on.
        sessionIsLive = false

        val local = warmLocalEngine()
        // Announced when the SHAPE CHANGES, not once per service and not once per session: a user
        // who loses signal at noon should be told, and a user who dictates forty times should not
        // be told forty times.
        fun announceIfNew(message: String) {
            if (notifiedChoice != choice) showToast(message)
        }
        val engine: TranscriptionEngine = when (choice) {
            EngineChoice.LOCAL_ONLY -> local
            EngineChoice.LOCAL_NO_KEY -> {
                announceIfNew("No key saved — using the on-device model")
                local
            }
            EngineChoice.LOCAL_OFFLINE -> {
                announceIfNew("Offline — using the on-device model")
                local
            }
            EngineChoice.CLOUD_WITH_FALLBACK -> {
                // Non-null/non-blank is guaranteed here: CLOUD_WITH_FALLBACK is reachable only
                // when hasKey was true above, which in turn required `provider` to be non-null.
                // Construction goes through the ONE factory both services share — the only line
                // that widened for C2b; the wrap below is provider-agnostic and unchanged.
                // requireNotNull, not `?: OPENAI`: decideEngineChoice only returns
                // CLOUD_WITH_FALLBACK when hasKey was true, and hasKey is false whenever `provider`
                // failed to resolve (an unresolvable id yields a null key). The Elvis default hid a
                // future gate-loosening bug — it would silently hand OPENAI another provider's key.
                val stt = SttProviderFactory.create(
                    requireNotNull(provider) { "CLOUD_WITH_FALLBACK reached with a null provider" },
                    sharedTransport(), requireNotNull(key),
                )
                val cloud = CloudTranscriptionEngine(stt, serviceScope)
                lastCloudEngine = cloud
                // The cloud engine may serve the MICROPHONE, which is the user's own voice and the
                // user's own choice. It may NEVER serve MediaProjection playback capture: that is
                // other people's audio, and the four shipped statements that say device audio stays
                // on the device (privacy_policy.html / docs/privacy.html §6-§7,
                // docs/PLAY-DECLARATIONS.md §3 and §5) are only true because of this router. The
                // ROUTE is decided by the capture source alone — never by the provider, the key or
                // the network, none of which can make third-party audio shippable.
                com.whispereverywhere.transcription.SourceRoutedTranscriptionEngine(
                    micEngine = FallbackTranscriptionEngine(cloud, local, serviceScope),
                    deviceEngine = local,
                ).also { sourceRouter = it }
            }
            EngineChoice.CLOUD_LIVE -> {
                // OpenAI-only and key-present, both guaranteed by decideEngineChoice: the live leaf
                // is reachable ONLY when the selected provider is OPENAI and hasKey was true. Same
                // mic audio, same provider, same v3 disclosure as batch — this swaps the transport
                // (Realtime WebSocket) and the cost tier, nothing about what data leaves.
                val cloud = com.whispereverywhere.transcription.live.LiveTranscriptionEngine(
                    apiKey = requireNotNull(key),
                    scope = serviceScope,
                    makeTransport = { transportListener ->
                        com.whispereverywhere.transcription.live.LiveTranscriptionEngine.realTransport(
                            com.whispereverywhere.transcription.live.RealtimeTransport(
                                factory = sharedLiveWsFactory(),
                                scheduler = sharedLiveReconnectScheduler(),
                                listener = transportListener,
                            ),
                        )
                    },
                )
                lastLiveEngine = cloud
                sessionIsLive = true
                // Wired IDENTICALLY to batch: the live engine is the `cloud` member of the SAME
                // FallbackTranscriptionEngine behind the SAME SourceRoutedTranscriptionEngine. A
                // dropped socket resolves its turn Lost and the untouched fallback rescues it
                // locally from the mirrored PCM. The router still decides the ROUTE by capture
                // source alone, so device (playback) audio remains physically unreachable from this
                // engine — the same privacy guarantee the batch branch above documents.
                com.whispereverywhere.transcription.SourceRoutedTranscriptionEngine(
                    micEngine = FallbackTranscriptionEngine(cloud, local, serviceScope),
                    deviceEngine = local,
                ).also { sourceRouter = it }
            }
        }
        notifiedChoice = choice
        transcriptionEngine = engine
        return engine
    }

    private fun startRecording() {
        if (!audioRecorder.hasPermission()) {
            vibrateError(); showToast("Microphone permission required"); return
        }

        updateBubbleState(BubbleState.CONNECTING)
        vibrateStart()
        // Capture wins instantly over read-aloud (Track F exclusivity rule); any selection
        // made before dictation is stale by definition once a session starts (review fix I2).
        com.whispereverywhere.audio.AudioArbiter.requestCapture()
        speakModeText = null
        isSpeakingNow = false
        sessionProducedText = false
        sessionTranscript.setLength(0)
        // Per-session ordering state. MUST be recreated: the orderer drops any seq below its head,
        // and the engine restarts seq numbering at 0 in connect() — a reused orderer sitting at
        // head N would silently discard the whole next session.
        segmentOrderer = com.whispereverywhere.transcription.SegmentOrderer()
        sessionStartMs = System.currentTimeMillis()
        // Freeze this session's routing mode and injection target at the tap. Segments finish
        // seconds later and the user may click anywhere in between; the session must not follow.
        // Both released/re-captured per session (injection session in teardownRealtime).
        sessionContext = currentContext
        // A typing session needs a REAL input target at the tap: keyboard up, or a focused
        // editable in ANY window (the cached-node-only check demoted working fields — user
        // regression report 2026-07-18). No target -> preview session: window + ONE clipboard
        // save at the end.
        if (sessionContext == BubbleContext.TEXT_FIELD &&
            !WhisperAccessibilityService.hasLiveInputTarget()
        ) {
            android.util.Log.i("WE-DIAG", "no live input target at tap -> preview session")
            sessionContext = BubbleContext.NONE
        }
        sessionClipboardFallback = false
        WhisperAccessibilityService.beginInjectionSession()
        android.util.Log.i("WE-DIAG", "startRecording: sessionContext=$sessionContext")

        // On-device engine. connect() resolves the installed model and loads the
        // native context off-thread; CONNECTING covers that model-load wait and
        // onOpen() fires only once the context is ready.
        // Reuse a single engine across sessions so the native model context is loaded once and
        // reused (spec: "loaded once and reused"); it is released only on memory pressure
        // (onTrimMemory) or on service destroy (onDestroy), not at the end of each recording.
        val engine: TranscriptionEngine = resolveTranscriptionEngine()

        // Resolve the transcription language. English-only (.en) models must NOT use auto-detect:
        // whisper's language auto-detect is unreliable on non-multilingual models. Force "en" for
        // ENGLISH-scope tiers; honor the user's setting (auto / specific) only for multilingual.
        val installedModel = app.whisperModelManager.installedModel()
        val lang = if (installedModel?.scope == com.whispereverywhere.model.ModelScope.ENGLISH) {
            "en"
        } else {
            app.preferencesManager.getLanguageForApi()
        }
        android.util.Log.i("WE-DIAG", "connect lang resolved=$lang (modelScope=${installedModel?.scope})")

        engine.connect(lang, object : TranscriptionEngine.Listener {
            override fun onOpen() {
                android.util.Log.i("WE-DIAG", "onOpen handler: state=$currentState")
                serviceScope.launch(Dispatchers.Main) {
                    if (currentState != BubbleState.CONNECTING) return@launch
                    speechSegmenter.reset()
                    lastCommitWallMs = System.currentTimeMillis()
                    val started = startAudioInput()
                    android.util.Log.i("WE-DIAG", "recorder start success=${started.isSuccess}")
                    if (started.isFailure) {
                        showToast("Recording failed: ${started.exceptionOrNull()?.message}")
                        teardownRealtime(); updateBubbleState(BubbleState.ERROR)
                        return@launch
                    }

                    // Show preview text bubble if we are not injecting into a text field
                    if (sessionContext != BubbleContext.TEXT_FIELD) {
                        // Restore the full-transcript view a prior live session may have hidden.
                        transcriptionEditText.visibility = View.VISIBLE
                        transcriptionEditText.text = ""
                        transcriptionDeltaText.text = ""
                        transcriptionDeltaText.visibility = View.GONE
                        transcriptionPreviewContainer.visibility = View.VISIBLE

                        // Create a bounded-memory sink for this session (Task 7)
                        val sessionFile = java.io.File(filesDir, "transcript_session.txt").apply { if (exists()) delete() }
                        val sink = com.whispereverywhere.transcription.TranscriptSink(sessionFile)
                        transcriptSink = sink
                        previewJob?.cancel()
                        previewJob = serviceScope.launch(Dispatchers.Main) {
                            sink.preview.collectLatest { text ->
                                transcriptionEditText.text = text
                                // TextView has no setSelection; scroll to reveal the newest text.
                                transcriptionEditText.post {
                                    val lc = transcriptionEditText.lineCount
                                    val layout = transcriptionEditText.layout
                                    if (lc > 0 && layout != null) {
                                        val dy = layout.getLineBottom(lc - 1) - transcriptionEditText.height
                                        transcriptionEditText.scrollTo(0, dy.coerceAtLeast(0))
                                    }
                                }
                            }
                        }
                    } else if (sessionIsLive) {
                        // A live TEXT_FIELD session injects each finished turn into the real field,
                        // but the field has no affordance for the word-for-word partials — so lift
                        // the preview STRIP (the delta line only) for live mode. The full-transcript
                        // editText stays hidden: deltas render on this strip and NEVER inject.
                        // Completions still flow through the unchanged orderer -> full-field RMW path.
                        transcriptionEditText.visibility = View.GONE
                        transcriptionDeltaText.text = ""
                        transcriptionDeltaText.visibility = View.GONE
                        transcriptionPreviewContainer.visibility = View.VISIBLE
                    } else {
                        transcriptionPreviewContainer.visibility = View.GONE
                    }

                    updateBubbleState(BubbleState.RECORDING)
                    amplitudeJob = serviceScope.launch {
                        audioRecorder.amplitude.collectLatest { amp ->
                            if (currentState != BubbleState.RECORDING) return@collectLatest
                            // Waveform only; the VAD/commit runs per-chunk in the recorder callback.
                            waveformView.updateAmplitude(amp)
                            blobView.updateAmplitude(amp)
                        }
                    }
                }
            }
            override fun onDelta(text: String) {
                // On-device and batch engines emit no intra-segment deltas; only the live engine
                // does. The strip already renders for NONE/MEDIA_PLAYBACK preview sessions; the
                // `|| sessionIsLive` clause lifts it into a live TEXT_FIELD session too, where the
                // onOpen branch above made the container (delta line only) visible for exactly this.
                if (sessionContext != BubbleContext.TEXT_FIELD || sessionIsLive) {
                    serviceScope.launch(Dispatchers.Main) {
                        if (text.isNotBlank()) {
                            transcriptionDeltaText.visibility = View.VISIBLE
                            transcriptionDeltaText.text = text
                        } else {
                            transcriptionDeltaText.visibility = View.GONE
                        }
                    }
                }
            }
            override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
                android.util.Log.i("WE-DIAG", "onSegmentResolved: seq=$seq outcome=${outcome.javaClass.simpleName}")
                // Hop to Main FIRST: the orderer is main-thread confined, and the engine calls
                // this from its executor thread. The hop is the same one the old onCompleted did,
                // so delivery timing is unchanged.
                serviceScope.launch(Dispatchers.Main) {
                    deliverReleasedText(segmentOrderer.onResolved(seq, outcome).text)
                }
            }
            override fun onError(message: String) {
                android.util.Log.w("WE-DIAG", "onError: state=$currentState msg=$message")
                if (currentState == BubbleState.RECORDING) {
                    // mid-session segment failure -> log and keep recording; do NOT tear down
                    android.util.Log.w("FloatingBubble", "Transcription segment failed (continuing): $message")
                    return
                }
                // connect-time / fatal (e.g. no model installed)
                serviceScope.launch(Dispatchers.Main) {
                    updateBubbleState(BubbleState.ERROR)
                    teardownRealtime()
                }
            }
            override fun onClosed() { /* expected on manual stop */ }
        })
    }

    private fun stopRecording() {
        android.util.Log.i("WE-DIAG", "stopRecording: state=$currentState")
        vibrateStop()
        amplitudeJob?.cancel(); amplitudeJob = null
        waveformView.stop()
        audioRecorder.stop()

        stopPlaybackCapturer()
        // Deliberately the raw field, NOT setActiveSource: the session is ending and the final
        // commit + drain below still belong to the engine that captured it. Re-routing here would
        // close that engine mid-drain and turn the last segment of every device-audio session into
        // a lost-segment marker. The router starts every new session on the mic by construction.
        activeSource = com.whispereverywhere.audio.ActiveSource.MIC

        updateBubbleState(BubbleState.FINALIZING)

        // Keep the preview VISIBLE through the drain: the backlog of committed segments keeps
        // streaming into it, which is the honest "still working" signal. Hiding it here left
        // only the spinner, and after a long capture users read that as a hang (user feedback
        // 2026-07-18, 25-min video). The status line below names what's happening; the preview
        // and the line come down together when the drain completes.
        if (sessionContext != BubbleContext.TEXT_FIELD) {
            transcriptionDeltaText.text = "Finishing transcript — last segments coming in…"
            transcriptionDeltaText.visibility = View.VISIBLE
        }
        
        // Flush whatever is buffered, UNCONDITIONALLY. The amplitude segmenter misses quiet
        // speech below its fixed thresholds — gating this flush on hasPendingSpeech() silently
        // discarded whole sessions for soft talkers ("No speech detected" despite real speech).
        // The native Silero VAD inside whisper_full now makes the unconditional flush safe: a
        // silence-only tail is trimmed to nothing and returns empty, fast, with no junk tokens.
        transcriptionEngine?.commit()
        speechSegmenter.reset()

        // Drain the ENTIRE transcription backlog before detaching the listener. A slow model (e.g.
        // the large tier) lags several segments behind real time; those queued transcribes finish
        // after the last utterance, and without waiting they'd complete post-teardown and be dropped
        // by the stale-listener guard (the user saw "No speech detected" despite valid audio). Each
        // result injects live as it finishes, so the earlier chunks keep appearing during the
        // finalize wait. Bounded by FINALIZE_TIMEOUT_MS. The blocking await runs on IO; UI on Main.
        serviceScope.launch(Dispatchers.Main) {
            val drained = withContext(Dispatchers.IO) {
                transcriptionEngine?.awaitIdle(FINALIZE_TIMEOUT_MS) ?: true
            }
            if (!drained) {
                android.util.Log.w("WE-DIAG", "finalize: drain timed out after ${FINALIZE_TIMEOUT_MS}ms")
            }
            // Release anything the orderer is still holding, BEFORE teardown closes the sink —
            // held text's only exit is flush(), and the pile is largest exactly here, at the end
            // of a session. (Provably empty for the on-device engine, which resolves in order.)
            deliverReleasedText(segmentOrderer.flush().text)
            // Capture the sink before teardown nulls it, so the full transcript can still be read.
            val finalizingSink = transcriptSink
            teardownRealtime()
            android.util.Log.i("WE-DIAG", "finalize: state=$currentState producedText=$sessionProducedText")

            // If the cloud provider latched a fatal this session, SAY SO — once per latch, not per
            // session. Without this the local fallback masks the failure completely: the
            // 2026-07-29 device test ran an entire "cloud" session on latched fatals and the
            // owner had no way to know. Read at finalize, after the drain, so the latch state is
            // final. (Pulled forward from C2c's degradation UX for exactly this reason.)
            lastCloudEngine?.lastFatal()?.let { fatal ->
                if (notifiedFatalKind != fatal.kind) {
                    notifiedFatalKind = fatal.kind
                    showToast("${fatal.message} — used the on-device model instead")
                }
            }
            // Same latch-toast for a live session: the live engine reports a bare FatalKind (its
            // WS handshake carries no safe body to build a message from — credential safety), so the
            // kind is mapped to the same copy the batch providers use. Exactly one of the two
            // engines is ever non-null per session, and the once-per-latch dedup is shared.
            lastLiveEngine?.lastFatal()?.let { kind ->
                if (notifiedFatalKind != kind) {
                    notifiedFatalKind = kind
                    showToast("${liveFatalMessage(kind)} — used the on-device model instead")
                }
            }

            // History: persist the session (text only) + apply rolling retention.
            // NOTE: history inherits the FINALIZE_TIMEOUT_MS bound above — a segment still
            // transcribing when the 300s drain times out is dropped from injection AND history
            // (pre-existing late-result semantics; the awaitIdle fence guarantees everything
            // that completes in time IS in sessionTranscript before this persist).
            if (sessionTranscript.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    transcriptStore.save(sessionStartMs, sessionTranscript.toString())
                    transcriptStore.sweep()
                }
            }
            if (currentState == BubbleState.FINALIZING) {
                // If we were transcribing without injecting, read the full text from the sink's
                // session file (bounded memory; Task 7) and copy it to the clipboard.
                if (sessionContext != BubbleContext.TEXT_FIELD) {
                    previewJob?.cancel(); previewJob = null
                    finalizingSink?.let { sink ->
                        // teardownRealtime already closed the sink; just read its flushed file.
                        val full = withContext(Dispatchers.IO) { sink.fullTextFile().readText().trim() }
                        if (full.isNotEmpty()) {
                            val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
                            // User decision 2026-07-18: a preview session whose user has moved
                            // into a text field by the END delivers there too — the whole
                            // transcript in one block (clipboard stays set either way). Covers
                            // the capture-video-then-tap-into-prompt flow end to end.
                            val injected = WhisperAccessibilityService.hasLiveInputTarget() &&
                                WhisperAccessibilityService.injectTextWithResult(full) ==
                                WhisperAccessibilityService.InjectionResult.SUCCESS
                            showToast(
                                if (injected) "Transcription inserted — also on your clipboard"
                                else "Transcription copied to clipboard",
                            )
                        }
                    }
                } else if (sessionClipboardFallback && sessionTranscript.isNotBlank()) {
                    // Field session that degraded to clipboard delivery: replace the per-segment
                    // scraps with ONE copy of the whole transcript (user decision 2026-07-18).
                    val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(
                        android.content.ClipData.newPlainText("Transcript", sessionTranscript.toString()),
                    )
                    showToast("Full transcription copied to clipboard")
                }

                if (!sessionProducedText) {
                    showToast("No speech detected — try again a bit louder or closer to the mic.")
                }
                vibrateSuccess()
                updateBubbleState(BubbleState.IDLE)
            }
        }
    }

    private fun teardownRealtime() {
        // Backstop flush: teardown is the LAST thing every session-exit path runs — normal drain
        // end (already flushed above, so this returns empty), recorder start failure, fatal
        // onError, and onDestroy. Held text is uniquely fragile: unlike per-segment injection it
        // accumulates finished work in RAM whose only exit is this call. Must run before the sink
        // is closed and before the injection session ends, or the released text has nowhere to go.
        deliverReleasedText(segmentOrderer.flush().text)
        previewJob?.cancel(); previewJob = null
        // The preview stays up through FINALIZING (live "still working" signal); EVERY teardown
        // path — normal drain end, error, start-failure, destroy — brings it down here.
        transcriptionDeltaText.visibility = View.GONE
        transcriptionPreviewContainer.visibility = View.GONE
        transcriptSink?.close(); transcriptSink = null
        WhisperAccessibilityService.endInjectionSession()
        // Detach the session listener but KEEP the engine + its loaded native context so the next
        // recording reuses it (no multi-hundred-MB reload per session). Full release (context +
        // worker thread) happens in onDestroy; the context is also freed under memory pressure
        // in onTrimMemory, reloading lazily on the next connect().
        transcriptionEngine?.close()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // CONNECTING is excluded alongside RECORDING/FINALIZING: a trim here can free ctxPtr
        // before the very first segment of the session runs, making the ctx==0 branch in
        // LocalWhisperEngine.runSegment reachable on segment #1 instead of only mid-teardown.
        if (level >= TRIM_MEMORY_RUNNING_LOW &&
            currentState != BubbleState.RECORDING &&
            currentState != BubbleState.FINALIZING &&
            currentState != BubbleState.CONNECTING
        ) {
            // The local engine directly: it owns the native context, and after a prewarm with no
            // recording yet `transcriptionEngine` is still null — so releasing through that field
            // would ignore memory pressure in exactly the idle state where it matters most.
            localEngine?.releaseContext()
        }
    }

    /**
     * The ONE routing point for text the [segmentOrderer] releases — used by onSegmentResolved and
     * by every flush() site, so text that was held and released late is delivered exactly like an
     * in-order segment (preview sink + history + injection) rather than a subset of that. Main
     * thread only.
     */
    private fun deliverReleasedText(text: String) {
        if (text.isBlank()) return
        sessionProducedText = true
        if (sessionContext != BubbleContext.TEXT_FIELD) {
            // During FINALIZING the delta line carries the "finishing…" status — keep it up
            // between drain segments instead of blanking it.
            if (currentState != BubbleState.FINALIZING) {
                transcriptionDeltaText.visibility = View.GONE
            }
            // Route through the bounded-memory sink; the preview StateFlow drives
            // transcriptionEditText via collectLatest in onOpen (Task 7).
            transcriptSink?.append(text)
        }
        handleTranscriptionResult(text)
    }

    /**
     * Handle the transcription result based on current context
     */
    private fun handleTranscriptionResult(text: String) {
        android.util.Log.i("WE-DIAG", "handleResult: session=$sessionContext live=$currentContext len=${text.length}")
        // History: accumulate every completed segment (both injection and preview contexts);
        // the session persists to TranscriptStore at finalize.
        if (text.isNotBlank()) {
            if (sessionTranscript.isNotEmpty()) sessionTranscript.append(' ')
            sessionTranscript.append(text.trim())
        }
        when (sessionContext) {
            BubbleContext.TEXT_FIELD -> {
                // Use the new injection method with detailed result
                val result = WhisperAccessibilityService.injectTextWithResult(text)
                android.util.Log.i("WE-DIAG", "inject result=$result")
                when (result) {
                    WhisperAccessibilityService.InjectionResult.SUCCESS -> {
                        // Text was successfully injected - no toast needed
                    }
                    WhisperAccessibilityService.InjectionResult.CLIPBOARD_ONLY -> {
                        // Delivery degraded to clipboard: suppress the per-utterance toast
                        // spam — finalize copies the WHOLE transcript once (user feedback
                        // 2026-07-18). The first occurrence gets one heads-up toast.
                        if (!sessionClipboardFallback) {
                            sessionClipboardFallback = true
                            showToast("Can't type here — full transcript will be copied when you stop")
                        }
                    }
                    WhisperAccessibilityService.InjectionResult.FAILED -> {
                        if (!sessionClipboardFallback) {
                            sessionClipboardFallback = true
                            showToast("Can't type here — full transcript will be copied when you stop")
                        }
                    }
                }
            }
            BubbleContext.MEDIA_PLAYBACK, BubbleContext.NONE -> {
                // Finalized segments flow exclusively through transcriptSink?.append() in onCompleted;
                // no unbounded accumulation here.
            }
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Whisper Transcription", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateBubbleState(newState: BubbleState) {
        currentState = newState

        serviceScope.launch(Dispatchers.Main) {
            pulseAnimator?.cancel()
            bubbleContainer.scaleX = 1f
            bubbleContainer.scaleY = 1f

            // Recording timer defaults off; the RECORDING branch turns it back on.
            recordingTimerJob?.cancel(); recordingTimerJob = null
            recordingTimerText.visibility = View.GONE
            // Satellite lobes are idle-only; the IDLE branch turns them back on.
            stopClipPulse()
            lockLobe.visibility = View.GONE
            speakerLobe.visibility = View.GONE

            when (newState) {
                BubbleState.IDLE -> {
                    bubbleIcon.visibility = View.VISIBLE
                    bubbleIcon.setImageResource(
                        when {
                            isSpeakingNow -> R.drawable.ic_stop_speech
                            speakModeText != null -> R.drawable.ic_speaker
                            else -> R.drawable.ic_mic
                        },
                    )
                    lockLobe.visibility = View.VISIBLE
                    speakerLobe.visibility = if (!isSpeakingNow &&
                        com.whispereverywhere.tts.TtsController.isVoiceInstalled(this@FloatingBubbleService)
                    ) View.VISIBLE else View.GONE
                    waveformView.visibility = View.GONE
                    waveformView.stop()
                    setBubbleWidth(56)
                    blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_background)
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.IDLE)
                    processingRing.visibility = View.GONE
                    processingRing.clearAnimation()
                    processingTimeText.visibility = View.GONE
                    stopProcessingTimer()
                    if (shouldHideOnIdle) {
                        shouldHideOnIdle = false
                        if (mediaDetector.isCurrentlyPlaying()) {
                            currentContext = BubbleContext.MEDIA_PLAYBACK; showBubbleForMedia()
                        } else {
                            currentContext = BubbleContext.NONE; hideBubble()
                        }
                    }
                }
                BubbleState.CONNECTING -> {
                    bubbleIcon.visibility = View.GONE
                    waveformView.visibility = View.GONE
                    processingRing.visibility = View.VISIBLE
                    blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_processing)
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.PROCESSING)
                    startRotationAnimation()
                }
                BubbleState.RECORDING -> {
                    bubbleIcon.visibility = View.GONE
                    processingRing.visibility = View.GONE
                    processingRing.clearAnimation()
                    setBubbleWidth(160)
                    waveformView.visibility = View.VISIBLE
                    waveformView.start()
                    // Deep black pill per the design reference — the aurora waves carry all the
                    // color; the red recording accent lives in the timer dot.
                    blobView.fillColor = android.graphics.Color.parseColor("#000000")
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.RECORDING)
                    // Live recording timer: red dot + mm:ss, bottom-left in the pill.
                    recordingTimerText.visibility = View.VISIBLE
                    recordingTimerJob = serviceScope.launch(Dispatchers.Main) {
                        val startMs = System.currentTimeMillis()
                        while (true) {
                            val s = ((System.currentTimeMillis() - startMs) / 1000).toInt()
                            val label = android.text.SpannableString(
                                String.format("● %02d:%02d", s / 60, s % 60)
                            )
                            label.setSpan(
                                android.text.style.ForegroundColorSpan(0xFFFF3B30.toInt()),
                                0, 1, 0
                            )
                            recordingTimerText.text = label
                            delay(1000)
                        }
                    }
                    // NO container pulse: the old x1.15 scale loop inflated the ribbon past the
                    // blob's rim (the "ribbon won't stay inside" bug). The blob's voice-driven
                    // swell IS the recording pulse now. Reset any leftover scale defensively.
                    pulseAnimator?.cancel(); pulseAnimator = null
                    bubbleContainer.scaleX = 1f
                    bubbleContainer.scaleY = 1f
                }
                BubbleState.FINALIZING -> {
                    pulseAnimator?.cancel()
                    waveformView.stop()
                    waveformView.visibility = View.GONE
                    setBubbleWidth(56)
                    processingRing.visibility = View.VISIBLE
                    blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_processing)
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.PROCESSING)
                    startRotationAnimation()
                }
                BubbleState.PROCESSING -> {
                    bubbleIcon.visibility = View.GONE
                    waveformView.visibility = View.GONE
                    waveformView.stop()
                    blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_processing)
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.PROCESSING)
                    processingRing.visibility = View.VISIBLE
                    processingTimeText.visibility = View.VISIBLE
                    startRotationAnimation()
                    startProcessingTimer()
                }
                BubbleState.ERROR -> {
                    bubbleIcon.visibility = View.VISIBLE
                    bubbleIcon.setImageResource(R.drawable.ic_error)
                    waveformView.visibility = View.GONE
                    waveformView.stop()
                    blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.error)
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.ERROR)
                    processingRing.visibility = View.GONE
                    processingRing.clearAnimation()
                    processingTimeText.visibility = View.GONE
                    stopProcessingTimer()

                    serviceScope.launch {
                        delay(2000)
                        if (currentState == BubbleState.ERROR) {
                            updateBubbleState(BubbleState.IDLE)
                        }
                    }
                }
            }
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val scale = animation.animatedValue as Float
                bubbleContainer.scaleX = scale
                bubbleContainer.scaleY = scale
            }
            start()
        }
    }

    private fun startRotationAnimation() {
        val rotateAnimation = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        rotateAnimation.duration = 1000
        rotateAnimation.repeatCount = Animation.INFINITE
        rotateAnimation.interpolator = LinearInterpolator()
        processingRing.startAnimation(rotateAnimation)
    }

    private var processingStartTime: Long = 0L

    private fun startProcessingTimer() {
        processingStartTime = System.currentTimeMillis()
        processingTimeText.text = "0s"

        processingTimerJob?.cancel()
        processingTimerJob = serviceScope.launch {
            while (isActive && currentState == BubbleState.PROCESSING) {
                val elapsed = (System.currentTimeMillis() - processingStartTime) / 1000
                processingTimeText.text = "${elapsed}s"
                delay(100) // Update every 100ms for smooth display
            }
        }
    }

    private fun stopProcessingTimer() {
        processingTimerJob?.cancel()
        processingTimerJob = null
    }

    private fun vibrateStart() {
        if (app.preferencesManager.isVibrationEnabled()) {
            vibrate(longArrayOf(0, 50))
        }
    }

    private fun vibrateStop() {
        if (app.preferencesManager.isVibrationEnabled()) {
            vibrate(longArrayOf(0, 30, 50, 30))
        }
    }

    private fun vibrateSuccess() {
        if (app.preferencesManager.isVibrationEnabled()) {
            vibrate(longArrayOf(0, 50, 50, 100))
        }
    }

    private fun vibrateError() {
        if (app.preferencesManager.isVibrationEnabled()) {
            vibrate(longArrayOf(0, 100, 50, 100, 50, 100))
        }
    }

    private fun vibrate(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun showToast(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, WhisperEverywhereApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_close, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    enum class BubbleState {
        IDLE, CONNECTING, RECORDING, FINALIZING, PROCESSING, ERROR
    }

    enum class BubbleContext {
        NONE,           // No specific context
        TEXT_FIELD,     // User is focused on a text field
        MEDIA_PLAYBACK  // Media is playing
    }

    companion object {
        const val ACTION_STOP = "com.whispereverywhere.STOP_BUBBLE"

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.stopService(intent)
        }
    }
}
