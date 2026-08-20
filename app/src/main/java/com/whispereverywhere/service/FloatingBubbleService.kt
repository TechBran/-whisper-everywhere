package com.whispereverywhere.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
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
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.text.TextJoin
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
 * Decision table (brief: Release C2a Task 6; C4 added the live-streaming leaf; the realtime
 * all-providers wave widened it from OpenAI-only to every streaming-capable provider):
 *  - no provider selected                       -> local only (unchanged, pre-cloud path)
 *  - provider selected, no key                  -> local, one-time toast
 *  - provider selected, key, offline             -> local, one-time toast
 *  - provider selected, key, online              -> cloud batch POST, wrapped in the local fallback
 *  - + live flag on AND provider is realtime-capable -> cloud LIVE stream, wrapped in the SAME fallback
 *
 * That last leaf is now the ORDINARY path, not the exception: `sttLiveMode` defaults to true, so a
 * user who picks OpenAI, ElevenLabs or Soniox and pastes a key streams word-for-word immediately.
 * Batch remains one toggle away and stays the only path for Gemini.
 *
 * The live leaf sits AFTER the local guards on purpose: the one-way valve is untouched, so no key
 * or no network still resolves to on-device — live never opens a socket the batch path would have
 * refused. That ordering is what makes the true default safe, and EngineSelectionTest now passes
 * `liveMode = true` through every local-guard case to pin it. Live is realtime-capable-provider-only (OpenAI, ElevenLabs, Soniox) because only those
 * providers have a native BYOK realtime WebSocket behind a [com.whispereverywhere.transcription.live.RealtimeProtocol];
 * Gemini has no client-usable realtime path (its Live API wants ephemeral backend-minted tokens
 * this app has no server for), so the flag is inert for it and its batch path is byte-unchanged.
 */
internal fun decideEngineChoice(
    sttProviderId: String?,
    hasKey: Boolean,
    hasValidatedNetwork: Boolean,
    /**
     * No default, deliberately. It used to default to false, which stopped being truthful when
     * `sttLiveMode` began defaulting to TRUE: a caller that omitted the axis would silently decide
     * batch for a user whose preference says stream. Every caller must state it.
     */
    liveMode: Boolean,
): EngineChoice = when {
    sttProviderId == null -> EngineChoice.LOCAL_ONLY
    !hasKey -> EngineChoice.LOCAL_NO_KEY
    !hasValidatedNetwork -> EngineChoice.LOCAL_OFFLINE
    liveMode && isRealtimeStt(sttProviderId) -> EngineChoice.CLOUD_LIVE
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

/**
 * STT providers with a shipped BYOK realtime adapter — the [EngineChoice.CLOUD_LIVE] gate. Derived
 * from [ProviderCatalog.supportsStreaming] rather than hand-listed, so it can never drift from the
 * catalog. Gemini is absent (its Live API needs backend-minted ephemeral tokens this app has no
 * server for). Kept in lockstep with `ModeDashboard.dictationLiveActive` and the CLOUD_LIVE
 * construction in [FloatingBubbleService.resolveTranscriptionEngine], which selects the matching
 * [com.whispereverywhere.transcription.live.RealtimeProtocol] for whichever id is in this set.
 */
internal val REALTIME_STT_PROVIDERS: Set<ProviderId> =
    ProviderCatalog.all.filter { it.supportsStreaming }.map { it.id }.toSet()

/** True when [sttProviderIdName] both resolves to a shipped adapter AND streams in real time. */
internal fun isRealtimeStt(sttProviderIdName: String?): Boolean =
    resolveSttProvider(sttProviderIdName)?.let { it in REALTIME_STT_PROVIDERS } == true

/**
 * The CONNECTING status label (3.6.0, Workstream E3). A LOCAL session whose engine still has to
 * load the model gets the honest "Loading speech model…" — naming the ~7 s cold wait — instead
 * of a bare spinner. A warm local engine, or ANY cloud session (whose CONNECTING wait is the
 * socket/handshake, not a model load), gets null: spinner only, exactly as before. Pure so the
 * branch is JVM-pinned (ConnectingLabelTest); the warm flag comes from LocalWhisperEngine.isWarm.
 */
internal fun connectingStatusLabel(isCloudSession: Boolean, localEngineWarm: Boolean): String? =
    if (!isCloudSession && !localEngineWarm) "Loading speech model…" else null

class FloatingBubbleService : Service(),
    WhisperAccessibilityService.OnTextFieldFocusListener,
    WhisperAccessibilityService.OnClipboardChangedListener,
    MediaSessionDetector.MediaPlaybackListener {

    // Read-aloud (Track F): true while OUR read-aloud audio is playing (speaker lobe, clipboard
    // copy, or PROCESS_TEXT toolbar). The idle bubble is ALWAYS a mic (owner rule 2026-08-08);
    // speaker behavior never arrives via text selection.
    @Volatile private var isSpeakingNow = false
    private lateinit var speechStopIcon: ImageView
    private lateinit var speakClipIcon: ImageView
    private lateinit var lockLobe: View
    private lateinit var keyboardLobe: View
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
    // provider choice produced.
    private var localEngine: LocalWhisperEngine? = null

    // The cloud wrapper (FallbackTranscriptionEngine) of the CURRENT/previous session, held so it
    // can be close()d — resolving everything it still owes — without shutting down the local engine
    // it wraps. Non-null only for cloud sessions; on-device-only users keep the pre-cloud path.
    private var cloudWrapper: FallbackTranscriptionEngine? = null

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

    // Resize-handle gesture state (W3). Deliberately separate from the root-drag fields
    // (initialX/initialTouchX/...): the handle consumes its own gesture stream so the two never
    // interleave, but sharing fields would make that invariant load-bearing and invisible.
    private lateinit var resizeHandle: ImageView
    private var resizeStartWidthDp = 0f
    private var resizeStartHeightDp = 0f
    private var resizeStartTouchX = 0f
    private var resizeStartTouchY = 0f
    private var resizeStartWindowY = 0
    private var isResizing = false
    private var lastResizeResult: ResizeMath.Result? = null
    private var resizeLongPressJob: kotlinx.coroutines.Job? = null

    // Max time finalize waits for the transcription backlog to drain before force-ending the
    // session. Generous because a slow model (e.g. the large tier) can lag several segments behind
    // real time; bounded so a pathological run can't hang the bubble in FINALIZING forever.
    private val FINALIZE_TIMEOUT_MS = 300_000L

    // Wall-clock cap per uncommitted stretch. Continuous loud audio (media playback, music) never
    // dips below the segmenter's silence floor, so its pause-based commit never fires — without
    // this cap the engine buffer grows unbounded and produces one giant end-of-session segment.
    // 3.6.0 (Workstream A): the cap is first-commit-aware — the session's FIRST stretch cuts at
    // 4 s so first visible text lands fast under continuous speech; every later stretch keeps the
    // old 15 s. The 800 ms pause cut is untouched and still wins when a real pause happens.
    // First-vs-later rule and per-session reset are JVM-pinned in SegmentCapPolicyTest.
    private val segmentCapPolicy = SegmentCapPolicy()

    // Set true when any transcription text is produced during a recording session; drives the
    // "No speech detected" feedback on stop so the user is not left with silent nothing.
    @Volatile private var sessionProducedText = false

    // The cloud provider serving THIS session, or null for on-device sessions. Set by
    // resolveTranscriptionEngine's cloud branches, read once at finalize to credit the month's
    // cloud-cost estimate. An estimate, deliberately: segments the fallback rescued locally are
    // still credited (the audio was sent), and that over-count is within "about".
    @Volatile private var sessionCloudProviderId: ProviderId? = null

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

    // TEXT_FIELD session whose FINAL write degraded to clipboard (document apps, dead targets):
    // set by deliverFinalTranscript when the one stop-time write can't type. Records the
    // OUTCOME (for the degraded toast wording); the decide() degraded row it could feed is
    // future-proofing only — finalDelivered makes a second decide() impossible in production.
    @Volatile private var sessionClipboardFallback = false

    // The final write fires EXACTLY once per session — stopRecording's finalize normally, or
    // onDestroy's best-effort if the service dies mid-session. This flag closes the race
    // between those two paths (destroy can cancel the finalize coroutine at any point).
    @Volatile private var finalDelivered = false

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
        // Our read-aloud plays on the music stream; the detector must never count it as media.
        // TtsController is the ONE authority: it covers every trigger, including
        // SpeakTextActivity's toolbar reads, which the service-local isSpeakingNow never saw —
        // those were classified as transcribable media over our own voice.
        mediaDetector.selfAudioActive = { com.whispereverywhere.tts.TtsController.isSpeechActive() }

        // The dictation-first toggle applies to the LIVE bubble (owner report 2026-08-01: the
        // keyboard lobe only appeared after disabling/re-enabling the bubble — its visibility was
        // evaluated solely in updateBubbleState's IDLE branch, which nothing re-ran on a pref
        // flip). Re-render the idle chrome whenever the pref changes; other states re-evaluate on
        // their natural next transition to IDLE.
        serviceScope.launch(Dispatchers.Main) {
            app.preferencesManager.dictationFirstKeyboard.collect {
                if (currentState == BubbleState.IDLE) updateBubbleState(BubbleState.IDLE)
            }
        }

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

        // Re-prewarm on model switch OR first install (3.6.0, Workstream E1). TWO triggers, ONE
        // collector, both ending in the same prewarmModelSwitch():
        //
        //  (a) every selectedModelId write — onboarding pick, Home's missing-engine row,
        //      Settings' migration/switch/delete — through the ONE prefs mirror, zero per-writer
        //      wiring. Debounced 750 ms: rapid successive writes (the migration's swap,
        //      onboarding's double write) restart the wait and only the final selection loads;
        //      the StateFlow additionally conflates same-value rewrites into no emission at all.
        //      drop(1) skips the replay of the current value at collect time — the prewarm above
        //      already covers service start.
        //  (b) modelInstalled — emitted once per VERIFIED download. This is the case (a) cannot
        //      see: onboarding writes the id before the file exists (a re-prewarm then finds no
        //      installed path and no-ops) and rewrites the same id after, which the StateFlow
        //      conflates away.
        //
        // Both arms carry the SAME 750 ms debounce, because the install signal LEADS the id write
        // on the download-then-select path: WhisperModelManager emits from verifyDest, and only
        // once download() has returned does ModelDownloadViewModel persist the new id. Firing the
        // install arm immediately would therefore prewarm the tier being switched AWAY from —
        // wasting a full ~7 s load on a cold slot (fresh start, or after onTrimMemory released
        // the context) and delaying the switch that actually matters. Debounced, collectLatest
        // coalesces the install signal into the id write that lands milliseconds later, so the
        // switch path does exactly one load, of the right tier. Onboarding is unaffected: there
        // the post-download write is the SAME id, the StateFlow conflates it away, and the
        // install signal is the sole trigger — it simply waits 750 ms first.
        //
        // Mid-session triggers are skipped, never deferred: releasing a context a live session is
        // using would Lost every later segment, and connect() at the next session start reloads
        // exactly as it always has. A null id (model deleted) no-ops inside prewarmModelSwitch.
        // Main-dispatched, so the currentState read is main-confined.
        serviceScope.launch {
            merge(
                app.preferencesManager.selectedModelIdFlow.drop(1)
                    .map { id -> 750L to "selectedModelId -> $id" },
                app.preferencesManager.modelInstalled.map { 750L to "model installed" },
            ).collectLatest { (debounceMs, reason) ->
                delay(debounceMs)
                if (currentState != BubbleState.IDLE && currentState != BubbleState.ERROR) {
                    android.util.Log.i("WE-DIAG", "$reason mid-session — connect() will reload")
                    return@collectLatest
                }
                android.util.Log.i("WE-DIAG", "$reason: re-prewarming engine")
                // prewarmModelSwitch queues onto the engine's executor, which onDestroy shuts
                // down — a rejection escaping here would kill this collector for good. Belt and
                // braces only: onDestroy cancels serviceScope BEFORE it touches the engine, so
                // this body cannot run against a shut-down executor. Caught narrowly, never as a
                // blanket Throwable, so a CancellationException is always free to propagate.
                try {
                    warmLocalEngine().prewarmModelSwitch()
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    android.util.Log.i("WE-DIAG", "$reason: re-prewarm skipped (engine shut down)")
                }
            }
        }
    }

    /**
     * Register this service as the focus listener.
     */
    private fun registerFocusListener() {
        WhisperAccessibilityService.setFocusListener(this)
        WhisperAccessibilityService.setClipboardListener(this)
    }

    // Attention pulse on the speaker lobe when something lands on the clipboard (user design
    // 2026-07-18): alternating red/blue with a breathing scale — "tap me to hear this".
    private var clipPulseAnimator: android.animation.ValueAnimator? = null

    /** The auto-hide for a clipboard-summoned bubble; re-copying restarts it. */
    private var clipboardAutoHideJob: kotlinx.coroutines.Job? = null

    override fun onClipboardChanged() {
        serviceScope.launch(Dispatchers.Main) {
            if (currentState != BubbleState.IDLE || isSpeakingNow ||
                com.whispereverywhere.tts.TtsController.isSpeechActive()
            ) return@launch
            if (!com.whispereverywhere.tts.TtsController.isVoiceInstalled(this@FloatingBubbleService)) return@launch
            // Warm the ~2 s voice-model load inside the copy -> lobe-tap think-time (this
            // preload used to ride the deleted selection morph; the copy that pulses the
            // speaker lobe below is the new warm signal). No-op if already loaded.
            com.whispereverywhere.tts.TtsController.preload(this@FloatingBubbleService)
            // Auto pop-up mode with the bubble hidden: a copy is a read-aloud OPPORTUNITY the user
            // cannot take on an invisible bubble (owner gap report 2026-08-01). Summon it at the
            // remembered spot for long enough to tap the pulsing speaker lobe, then leave. The
            // Rect is context-only — positioning is the user's own remembered spot.
            val summoned = !alwaysOnMode() && !isBubbleVisible
            if (summoned) showBubbleNearTextField(Rect())
            pulseSpeakerLobe()
            if (summoned) {
                clipboardAutoHideJob?.cancel()
                clipboardAutoHideJob = serviceScope.launch(Dispatchers.Main) {
                    // The pulse runs ~7 s; give the full window plus a beat to decide.
                    delay(8_000)
                    // A read the user DID start keeps the bubble through the whole job.
                    while (isSpeakingNow) delay(500)
                    // Leave quietly — unless the user meanwhile put the bubble to real use
                    // (recording, a focused field, playing media), in which case it is theirs now.
                    if (currentState == BubbleState.IDLE &&
                        currentContext == BubbleContext.NONE &&
                        !alwaysOnMode()
                    ) {
                        stopClipPulse()
                        hideBubble()
                    }
                }
            }
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

    // ========== Read-aloud (Track F): speaker lobe / clipboard copy -> speak/stop ==========

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
        keyboardLobe.visibility = View.GONE
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
        // The IDLE branch restores icon/width/blob for the current speaking state.
        if (currentState == BubbleState.IDLE) updateBubbleState(BubbleState.IDLE)
    }

    private fun startSpeaking(text: String) {
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
        // W2 best-effort: a service destroyed mid-session (system kill, mode toggle) still
        // delivers what it accumulated, through the SAME single-delivery policy as a normal
        // stop — and it must run BEFORE teardownRealtime, which ends the injection-session
        // binding the SESSION_BOUND write needs. Deliberately synchronous (serviceScope is
        // already cancelled, so there is nothing to post to; toasts inside no-op harmlessly)
        // and failure-swallowed: destroy must never block or throw. finalDelivered (set by a
        // finalize that already delivered, then IDLE) keeps the write once-only.
        if (currentState == BubbleState.RECORDING || currentState == BubbleState.FINALIZING) {
            runCatching {
                deliverReleasedText(segmentOrderer.flush().text)
                transcriptSink?.close()
                val full = transcriptSink?.fullTextFile()?.readText()?.trim() ?: ""
                // Empty ⇒ either nothing was said or the finalize coroutine already detached the
                // sink mid-read — either way, never claim finalDelivered with nothing delivered.
                if (full.isNotEmpty()) deliverFinalTranscript(full)
            }
        }
        teardownRealtime()
        // Fully release on service end: free the native context and stop its worker thread
        // (teardownRealtime only detaches the session listener, for reuse).
        //
        // Order and target both matter. The wrapper is CLOSED (resolving anything it still owes,
        // and cleaning up the cloud engine, whose shutdown() is close()), then the local engine —
        // the sole owner of the native context — is SHUT DOWN exactly once. Going through
        // `transcriptionEngine` instead would leak the context whenever the service is destroyed
        // after prewarm but before any recording, because that field is still null at that point.
        cloudWrapper?.close()
        cloudWrapper = null
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
        bubbleView.post {
            // Re-apply the panel size FIRST: the size clamps re-derive from the new
            // orientation's metrics (a landscape-max panel must shrink for portrait,
            // or its top-right handle lands off-screen with no way to grab it).
            if (transcriptionPreviewContainer.visibility == View.VISIBLE) {
                applyPreviewSize()
            }
            reclampAfterConfigChange()
        }
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
        // The REMEMBERED spot, exactly like the text-field pop-up (owner 2026-08-01: the bubble
        // appears "right where I placed it last" — the old bottom-right media default was the
        // final teleporter left after the drag snap was removed). Pinned still wins. Clamped
        // through THE clamp against the window's REAL size (W3), not the old 56dp pill guess.
        val (winW, winH) = currentWindowSize()
        val restored = clampToBounds(
            (app.preferencesManager.bubblePositionX * screenWidth).toInt(),
            (app.preferencesManager.bubblePositionY * screenHeight).toInt(),
            winW,
            winH,
        )
        var targetX = restored.first
        var targetY = restored.second
        if (isOverlayPinned) {
            val pinned = savedPinnedPosition()
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
        // The bubble pops up WHERE IT WAS LAST — the user's dragged/remembered spot — never at a
        // field-derived position (owner decision 2026-08-01: "it should just pop up where it
        // popped up last time, or where the user moved the bubble to... a new location can be
        // annoying — users won't know where their bubble's gonna be"). The rect parameter is now
        // context-only (what focused, not where to go); bubblePositionX/Y is already written by
        // every drag, so the spot tracks the user for free. Pinned keeps its own stronger spot.
        // Clamped through THE clamp against the window's REAL size (W3) — this site used to skip
        // the navbar term the media site subtracted; the shared clamp ends that disagreement.
        val (winW, winH) = currentWindowSize()
        val restored = clampToBounds(
            (app.preferencesManager.bubblePositionX * screenWidth).toInt(),
            (app.preferencesManager.bubblePositionY * screenHeight).toInt(),
            winW,
            winH,
        )
        var targetX = restored.first
        var targetY = restored.second
        if (isOverlayPinned) {
            val pinned = savedPinnedPosition()
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

        val pos = savedPinnedPosition()
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
        resizeHandle = bubbleView.findViewById(R.id.resize_handle)
        resizeHandle.setOnTouchListener { _, event -> handleResizeTouch(event) }
        pinIcon = bubbleView.findViewById(R.id.pin_icon)
        speechStopIcon = bubbleView.findViewById(R.id.speech_stop_icon)
        speechStopIcon.setOnClickListener { com.whispereverywhere.tts.TtsController.stop() }
        speakClipIcon = bubbleView.findViewById(R.id.speak_clip_icon)
        lockLobe = bubbleView.findViewById(R.id.lock_lobe)
        keyboardLobe = bubbleView.findViewById(R.id.keyboard_lobe)
        speakerLobe = bubbleView.findViewById(R.id.speaker_lobe)
        lockLobe.setOnClickListener { togglePin() }
        // Dictation-first: summon (or re-hide) the system keyboard for the current field.
        keyboardLobe.setOnClickListener {
            val shown = WhisperAccessibilityService.toggleSummonedKeyboard()
            android.util.Log.i("WE-DIAG", "keyboard lobe: summoned=$shown")
        }
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
        // Size-aware estimate for clamping before the view is measured (the preview is GONE at
        // inflate, so this resolves to the same 64dp pill estimate as before); the measured size
        // takes over at the next reclamp (config change / preview show).
        val (estW, estH) = currentWindowSize()
        val clamped = clampToBounds(rawX, rawY, estW, estH)
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
        applyPreviewSize()

        windowManager.addView(bubbleView, params)

        bubbleView.visibility = View.GONE
        bubbleView.alpha = 0f
        isBubbleVisible = false

        updateBubbleState(BubbleState.IDLE)
    }

    // ========== Transcript preview sizing (W3) ==========

    /**
     * Apply the persisted (or, mid-resize, the in-flight) preview panel size to both transcript
     * views. dp -> px against the CURRENT displayMetrics and re-clamped to the CURRENT screen on
     * every call, so a stale/corrupt pref or a rotation can never produce an off-screen or
     * zero-size panel. Runtime owns the panel's width and max height; the XML 280dp width is only
     * the pre-first-apply default and android:maxHeight was removed from the layout entirely.
     */
    private fun applyPreviewSize(
        widthDp: Float = app.preferencesManager.bubbleTextWidthDp,
        heightDp: Float = app.preferencesManager.bubbleTextHeightDp,
    ) {
        val dm = resources.displayMetrics
        val maxW = ResizeMath.maxWidthDp(dm.widthPixels, dm.density)
            .coerceAtLeast(ResizeMath.MIN_WIDTH_DP)
        val maxH = ResizeMath.maxHeightDp(dm.heightPixels, dm.density)
            .coerceAtLeast(ResizeMath.MIN_HEIGHT_DP)
        val widthPx = (widthDp.coerceIn(ResizeMath.MIN_WIDTH_DP, maxW) * dm.density).toInt()
        val heightPx = (heightDp.coerceIn(ResizeMath.MIN_HEIGHT_DP, maxH) * dm.density).toInt()
        transcriptionEditText.layoutParams = transcriptionEditText.layoutParams.apply { width = widthPx }
        transcriptionEditText.maxHeight = heightPx
        transcriptionDeltaText.layoutParams = transcriptionDeltaText.layoutParams.apply { width = widthPx }
    }

    /**
     * Estimated full-window dims (px) for a GIVEN panel size — used when the view isn't laid out
     * yet or mid-resize when the measured size lags a frame. Base: the 64dp pill estimate the old
     * clamps used. When the preview is visible: width = panel + 48dp chrome (16dp container
     * padding per side + 8dp root padding per side); height stacks panel + 48dp chrome (12dp
     * container padding top/bottom + 8dp bottom margin + 8dp root padding top/bottom) on the
     * pill; the live-mode delta strip, when visible, gets its own ~100dp allowance (it is not
     * part of heightDp). A slight OVER-estimate by design: clamping with a too-big window only
     * keeps it further from the screen edge — the safe direction.
     */
    private fun estimatedWindowSize(widthDp: Float, heightDp: Float): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val pillEstimate = (64 * density).toInt()
        if (transcriptionPreviewContainer.visibility != View.VISIBLE) {
            return Pair(pillEstimate, pillEstimate)
        }
        val panelW = ((widthDp + 48f) * density).toInt()
        // Live sessions stack the delta strip (maxLines=5, italic) below the main panel —
        // without this allowance the estimate UNDER-shoots in live mode and a mid-resize
        // clamp could leave the window bottom off-screen.
        val stripAllowance =
            if (transcriptionDeltaText.visibility == View.VISIBLE) (100 * density).toInt() else 0
        val panelH = ((heightDp + 48f) * density).toInt() + stripAllowance
        return Pair(maxOf(pillEstimate, panelW), pillEstimate + panelH)
    }

    /** The window's REAL current dims for clamping: measured when laid out, estimated otherwise. */
    private fun currentWindowSize(): Pair<Int, Int> {
        if (bubbleView.width > 0 && bubbleView.height > 0) {
            return Pair(bubbleView.width, bubbleView.height)
        }
        return estimatedWindowSize(
            app.preferencesManager.bubbleTextWidthDp,
            app.preferencesManager.bubbleTextHeightDp,
        )
    }

    /** Re-clamp params to bounds for the window's CURRENT size (preview shown/hidden, resize,
     *  reset). Post from a geometry-changing site so the measure pass has run first. */
    private fun reclampNow() {
        val (winW, winH) = currentWindowSize()
        val clamped = clampToBounds(params.x, params.y, winW, winH)
        if (clamped.first == params.x && clamped.second == params.y) return
        params.x = clamped.first
        params.y = clamped.second
        try {
            windowManager.updateViewLayout(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * The resize-handle gesture (W3). The handle CONSUMES its stream (child-first dispatch), so
     * the root drag / long-press-to-pin listener never sees these events. Width follows the
     * finger; height grows when dragging UP, and params.y compensates by the clamped growth so
     * the TOP edge follows the finger while the mic pill below stays put. Live-apply per move;
     * persist on ACTION_UP (size AND the moved y, exactly like the root drag-end persists
     * position); long-press without a drag resets to the 280x120dp defaults. Pin locks POSITION,
     * not size — resizing while pinned is allowed, and its y-compensation is part of resizing.
     */
    private fun handleResizeTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                resizeStartWidthDp = app.preferencesManager.bubbleTextWidthDp
                resizeStartHeightDp = app.preferencesManager.bubbleTextHeightDp
                resizeStartTouchX = event.rawX
                resizeStartTouchY = event.rawY
                resizeStartWindowY = params.y
                isResizing = false
                lastResizeResult = null
                // Same 500 ms threshold as the pin gesture; cancelled the moment a drag starts.
                resizeLongPressJob?.cancel()
                resizeLongPressJob = serviceScope.launch {
                    delay(LONG_PRESS_MS)
                    if (!isResizing) resetPreviewSize()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - resizeStartTouchX
                val dy = event.rawY - resizeStartTouchY
                if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                    isResizing = true
                    resizeLongPressJob?.cancel()
                }
                if (!isResizing) return true
                val dm = resources.displayMetrics
                val result = ResizeMath.resize(
                    startWidthDp = resizeStartWidthDp,
                    startHeightDp = resizeStartHeightDp,
                    dragDxPx = dx,
                    dragDyPx = dy,
                    density = dm.density,
                    screenWidthPx = dm.widthPixels,
                    screenHeightPx = dm.heightPixels,
                )
                // Live-apply through the SAME code path the persisted size uses, then move the
                // window's top edge with the finger and re-clamp against the NEW estimated dims.
                applyPreviewSize(result.widthDp, result.heightDp)
                val est = estimatedWindowSize(result.widthDp, result.heightDp)
                val clamped = clampToBounds(
                    params.x, resizeStartWindowY + result.windowDyPx, est.first, est.second,
                )
                params.x = clamped.first
                params.y = clamped.second
                try {
                    windowManager.updateViewLayout(bubbleView, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                lastResizeResult = result
                return true
            }
            MotionEvent.ACTION_UP -> {
                resizeLongPressJob?.cancel(); resizeLongPressJob = null
                val result = lastResizeResult
                if (isResizing && result != null) {
                    app.preferencesManager.bubbleTextWidthDp = result.widthDp
                    app.preferencesManager.bubbleTextHeightDp = result.heightDp
                    // Persist the compensated spot exactly like the root drag-end does: the
                    // window's y moved with the resize, and pop-up restore reads these fractions.
                    val dm = resources.displayMetrics
                    if (dm.widthPixels > 0) {
                        app.preferencesManager.bubblePositionX = params.x.toFloat() / dm.widthPixels
                    }
                    if (dm.heightPixels > 0) {
                        app.preferencesManager.bubblePositionY = params.y.toFloat() / dm.heightPixels
                    }
                }
                isResizing = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                resizeLongPressJob?.cancel(); resizeLongPressJob = null
                isResizing = false
                return true
            }
        }
        return false
    }

    /** Long-press on the handle: back to the stock 280x120dp panel, persisted immediately —
     *  insurance against wedging the window tiny. */
    private fun resetPreviewSize() {
        app.preferencesManager.bubbleTextWidthDp = ResizeMath.DEFAULT_WIDTH_DP
        app.preferencesManager.bubbleTextHeightDp = ResizeMath.DEFAULT_HEIGHT_DP
        applyPreviewSize()
        bubbleView.post { reclampNow() }
        showToast("Preview size reset")
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
        flashLockLobe()
        showToast(if (isOverlayPinned) "Bubble locked in place" else "Bubble unlocked")
    }

    /** The transient pin confirmation: the lock lobe appears for a beat, then leaves. */
    private var lockFlashJob: kotlinx.coroutines.Job? = null

    private fun flashLockLobe() {
        lockFlashJob?.cancel()
        lockLobe.visibility = View.VISIBLE
        lockFlashJob = serviceScope.launch(Dispatchers.Main) {
            delay(1_500)
            lockLobe.visibility = View.GONE
        }
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
     * THE clamp: (x, y) coerced so a viewW x viewH window stays fully on the USABLE screen.
     * The navbar term lives HERE now, so every caller agrees on the bottom edge — the two
     * show-site y-clamps used to disagree on it. Falls back gracefully (0..0) when the screen
     * size is not yet determined.
     */
    private fun clampToBounds(x: Int, y: Int, viewW: Int, viewH: Int): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - viewW).coerceAtLeast(0)
        val maxY = (dm.heightPixels - viewH - getNavigationBarHeight()).coerceAtLeast(0)
        return Pair(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    /** The user's pinned bubble position from prefs (stored as screen fractions), in px, clamped
     *  against the window's REAL current size — not the old 56dp pill guess. */
    private fun savedPinnedPosition(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val x = (app.preferencesManager.bubblePositionX * dm.widthPixels).toInt()
        val y = (app.preferencesManager.bubblePositionY * dm.heightPixels).toInt()
        val (winW, winH) = currentWindowSize()
        return clampToBounds(x, y, winW, winH)
    }

    /**
     * Re-clamp after a configuration change (rotation / fold). Deliberately does NOT persist:
     * the saved fractions are written only by user drag-end, resize-end, and pin — a rotation
     * mid-session must never rewrite the user's spot using transient preview-inflated
     * dimensions. Restores clamp anyway, so nothing is lost by not writing here.
     */
    private fun reclampAfterConfigChange() {
        val (viewW, viewH) = currentWindowSize()
        val clamped = clampToBounds(params.x, params.y, viewW, viewH)
        params.x = clamped.first
        params.y = clamped.second
        try {
            windowManager.updateViewLayout(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
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

                // When pinned, suppress all drag movement; only taps (and long-press) register.
                // Clamped per step against the REAL window size, so a drag can no longer park
                // the preview-widened window partly off-screen (drag-end persists params as-is).
                if (!isOverlayPinned && isDragging) {
                    val (winW, winH) = currentWindowSize()
                    val clamped = clampToBounds((initialX + dx).toInt(), (initialY + dy).toInt(), winW, winH)
                    params.x = clamped.first
                    params.y = clamped.second
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
                    // FREE placement in EVERY mode (owner 2026-08-01: the edge snap felt like the
                    // bubble "wants to lock to certain areas"). The snap also had a real bug: it
                    // ANIMATED to the edge over 200 ms while the position below saved the
                    // PRE-animation spot, so the remembered position and the visible bubble
                    // disagreed — every later hide/show "teleported" it. Where you drop it is
                    // where it stays, and exactly what pop-up restores.
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
        // ONE engine serves the whole session, whatever the capture source. Device audio follows
        // the user's provider selection exactly as mic audio does (owner decision 2026-08-01):
        // selecting a cloud engine IS the consent that audio you transcribe goes to that provider,
        // and the disclosure + privacy policy say so for both sources in the same sentence. The
        // per-session MediaProjection consent sheet marks each capture. Cost is the user's own key,
        // knowingly. The old source-router (which forced device audio on-device regardless of
        // settings) was retired with this decision — see the 2026-08-01 commit for its rationale.
        val engine = transcriptionEngine ?: return
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
        // the amplitude never dips (continuous media). Live (server-driven) sessions bypass this
        // ENTIRELY — the SERVER cuts turns via its own VAD for every capture source (device audio
        // rides the same socket as mic audio since 2026-08-01), and the stop button / session end
        // is the outer bound. `sendAudio` above stays UNCONDITIONAL, so the engine is always fed;
        // only the turn CUT moves to the server. Local + Gemini + batch keep this block
        // byte-identical — including device-audio capture in those sessions, whose segments this
        // VAD is what cuts.
        if (com.whispereverywhere.transcription.live.LiveTurnPolicy.runClientVad(sessionIsLive)) {
            val now = System.currentTimeMillis()
            if (speechSegmenter.onAmplitude(amp, now)) {
                android.util.Log.i("WE-DIAG", "VAD -> commit (rms=$amp)")
                segmentCapPolicy.onCommit(now)
                engine.commit()
            } else if (segmentCapPolicy.capExceeded(now)) {
                // currentCapMs() is read BEFORE onCommit flips first->later, so the line names
                // the cap that actually fired (4000ms for the session's first LOCAL stretch;
                // cloud sessions closed the first-cap window at onOpen and always read 15000ms).
                android.util.Log.i(
                    "WE-DIAG",
                    "wall-clock cap -> commit (cap=${segmentCapPolicy.currentCapMs()}ms)",
                )
                // A cap cut on SILENCE-ONLY audio still commits the buffer (bounded, and whisper's
                // VAD returns empty fast); only the policy bookkeeping below is conditional.
                // hasPendingSpeech() is read BEFORE speechSegmenter.reset(), which clears the flag.
                // The SegmentCapPolicy contract is unchanged (any onCommit consumes the window);
                // the silence exception lives here at the call site.
                //
                // Cap-cut policy bookkeeping:
                //  - real speech (any session): consume the first-cap window and restart the clock;
                //  - CLOUD silence: also consume/restart — the 4s window must NEVER re-open on cloud
                //    (a 4s cloud cut = an extra billable provider request; cap=4000ms in a cloud
                //    session is the bug signature);
                //  - LOCAL silence: re-arm the window so a user who pauses to think still gets the
                //    4s first cut on their first real speech (3.5.0 parity guarantee).
                if (speechSegmenter.hasPendingSpeech() || cloudWrapper != null) {
                    segmentCapPolicy.onCommit(now)
                } else {
                    segmentCapPolicy.onSessionStart(now)
                }
                engine.commit()
                speechSegmenter.reset()
            }
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
        // One engine serves both sources, so this commit is what keeps mic and device audio in
        // SEPARATE segments at the boundary. In a live (server-driven) session it cuts a client
        // turn mid-stream — the same mechanism stopRecording's tail commit uses.
        transcriptionEngine?.commit()
        speechSegmenter.reset()
        segmentCapPolicy.onCommit(System.currentTimeMillis())
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
                // A grant that arrives AFTER the session ended must not be kept: the session-end
                // policy is "screen share ends with the session", and a stored token here would
                // light the system's sharing indicator with nothing capturing — exactly the lie
                // that policy exists to prevent. Stop it on the spot; the next media session asks
                // again.
                if (currentState != BubbleState.RECORDING) {
                    android.util.Log.i("WE-DIAG", "consent arrived after session end -> stopping projection")
                    runCatching { projection.stop() }
                    return@launch
                }
                com.whispereverywhere.audio.MediaProjectionGate.storeProjection(projection)
                if (activeSource != com.whispereverywhere.audio.ActiveSource.PLAYBACK) {
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
        cloudWrapper?.close()
        cloudWrapper = null
        // A stale latch must not outlive its engine: after switching to on-device, finalize must
        // not toast about a fatal from a previous session's cloud engine. Both cloud references are
        // cleared here; the chosen branch below re-sets exactly one (or neither, for local).
        lastCloudEngine = null
        lastLiveEngine = null
        sessionCloudProviderId = null
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
                sessionCloudProviderId = provider
                // ONE engine for the whole session, both capture sources. Device (playback) audio
                // follows the user's provider selection exactly as mic audio does — owner decision
                // 2026-08-01, and the four statements that used to say device audio never leaves
                // the phone (assets/privacy_policy.html, docs/privacy.html §6,
                // docs/PLAY-DECLARATIONS.md §3/§5) were rewritten IN THE SAME COMMIT to disclose
                // it. Consent is the same triad as mic audio (key + selection + disclosure) plus
                // the per-session MediaProjection sheet, and the local fallback rescues failures
                // for both sources alike.
                FallbackTranscriptionEngine(cloud, local, serviceScope).also { cloudWrapper = it }
            }
            EngineChoice.CLOUD_LIVE -> {
                // Realtime-capable-and-key-present, both guaranteed by decideEngineChoice: the live
                // leaf is reachable ONLY when the selected provider is in REALTIME_STT_PROVIDERS and
                // hasKey was true. Same mic audio, same provider, same v3 disclosure as batch — this
                // swaps the transport (a per-provider Realtime WebSocket) and the cost tier, nothing
                // about what data leaves. `protocol` is the ONE place a provider selects its
                // RealtimeProtocol; OpenAI/ElevenLabs authenticate via the upgrade header (the key
                // passed below), Soniox rides its key in the first config message instead — see
                // SonioxRealtimeProtocol's no-log discipline.
                val liveProviderId = requireNotNull(provider) { "CLOUD_LIVE reached with a null provider" }
                val protocol: com.whispereverywhere.transcription.live.RealtimeProtocol = when (liveProviderId) {
                    ProviderId.OPENAI -> com.whispereverywhere.transcription.live.OpenAiRealtimeProtocol()
                    ProviderId.ELEVENLABS ->
                        com.whispereverywhere.transcription.live.ElevenLabsRealtimeProtocol()
                    ProviderId.SONIOX ->
                        com.whispereverywhere.transcription.live.SonioxRealtimeProtocol()
                    ProviderId.GEMINI -> error("Gemini is not realtime-capable; decideEngineChoice forbids CLOUD_LIVE for it")
                }
                val cloud = com.whispereverywhere.transcription.live.LiveTranscriptionEngine(
                    apiKey = requireNotNull(key),
                    scope = serviceScope,
                    // Open socket, server VAD: the SERVER cuts turns. The client VAD/commit + wall-cap
                    // are disabled for this session (see onAudioChunk's LiveTurnPolicy gate); the engine
                    // allocates seqs from server turn events via the rotation callback wired below.
                    serverDriven = true,
                    makeTransport = { transportListener ->
                        com.whispereverywhere.transcription.live.LiveTranscriptionEngine.realTransport(
                            com.whispereverywhere.transcription.live.RealtimeTransport(
                                factory = sharedLiveWsFactory(),
                                scheduler = sharedLiveReconnectScheduler(),
                                listener = transportListener,
                                protocol = protocol,
                            ),
                        )
                    },
                )
                lastLiveEngine = cloud
                sessionIsLive = true
                sessionCloudProviderId = liveProviderId
                // Wired IDENTICALLY to batch: one FallbackTranscriptionEngine serves the whole
                // session, both capture sources (owner decision 2026-08-01 — device audio follows
                // the provider selection; see the batch branch above for the consent/docs story).
                // A dropped socket resolves its turn Lost and the fallback rescues it locally from
                // the mirrored PCM, mic and device audio alike. In live mode the SERVER cuts
                // device-audio turns exactly as it cuts mic turns — same socket, same VAD.
                val fallback = FallbackTranscriptionEngine(cloud, local, serviceScope)
                // Server turns rotate the SAME fallback that mirrors this engine's PCM — the seq the
                // callback returns is the paired seq the mirror just retained. Wired here, where both
                // objects exist, so FallbackTranscriptionEngine stays provider-agnostic and
                // byte-identical (it never knows about server-driven turns).
                cloud.attachServerTurnRotation { fallback.commit() }
                fallback.also { cloudWrapper = it }
            }
        }
        notifiedChoice = choice
        transcriptionEngine = engine
        return engine
    }

    /**
     * The ONE preview pipeline (W2 unified preview): EVERY session — TEXT_FIELD, MEDIA_PLAYBACK,
     * NONE, live or batch — shows the accumulating transcript window and gets a bounded-memory
     * TranscriptSink. Mid-session text goes here and NOWHERE else; the single external write
     * happens at stop (deliverFinalTranscript). For a TEXT_FIELD session this window is the
     * user's only live feedback now, since segments no longer inject as they resolve.
     * [live] sessions additionally stream the in-flight utterance onto the delta strip via
     * onDelta; here the strip is only reset so the last session's text never flashes back.
     */
    private fun showSessionPreview(live: Boolean) {
        applyPreviewSize()
        // The preview appearing is a geometry change: the window just grew upward/rightward.
        // Posted so the measure pass has run and currentWindowSize() sees the REAL new dims.
        bubbleView.post { reclampNow() }
        android.util.Log.i("WE-DIAG", "showSessionPreview: live=$live context=$sessionContext")
        transcriptionEditText.visibility = View.VISIBLE
        transcriptionEditText.text = ""
        transcriptionDeltaText.text = ""
        transcriptionDeltaText.visibility = View.GONE
        transcriptionPreviewContainer.visibility = View.VISIBLE

        // Bounded-memory sink for the session; the file on disk is the full transcript.
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
    }

    private fun startRecording() {
        if (!audioRecorder.hasPermission()) {
            vibrateError(); showToast("Microphone permission required"); return
        }

        updateBubbleState(BubbleState.CONNECTING)
        vibrateStart()
        // Capture wins instantly over read-aloud (Track F exclusivity rule).
        com.whispereverywhere.audio.AudioArbiter.requestCapture()
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
        finalDelivered = false
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
                    // Per-session reset: the FIRST-segment 4 s cap applies again from here.
                    val sessionOpenMs = System.currentTimeMillis()
                    segmentCapPolicy.onSessionStart(sessionOpenMs)
                    // 3.6.0 (Workstream A) — the 4 s first cap is LOCAL-ONLY. This VAD/cap path
                    // also runs for CLOUD_WITH_FALLBACK (runClientVad is true for it; only
                    // CLOUD_LIVE sets sessionIsLive), and there an extra first segment means an
                    // extra provider round-trip, an extra fallback mirror and an extra BILLABLE
                    // request — while the user's first-text wait is the network, not inference.
                    // Closing the first-cap window immediately (the same "any commit ends it"
                    // rule SegmentCapPolicyTest pins) leaves cloud sessions on the pre-existing
                    // 15 s cap for every segment: byte-identical to 3.5.0. cloudWrapper is
                    // already resolved here — resolveTranscriptionEngine() ran in startRecording,
                    // and it is the same cloud predicate stopRecording uses.
                    if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionOpenMs)
                    val started = startAudioInput()
                    android.util.Log.i("WE-DIAG", "recorder start success=${started.isSuccess}")
                    if (started.isFailure) {
                        showToast("Recording failed: ${started.exceptionOrNull()?.message}")
                        teardownRealtime(); updateBubbleState(BubbleState.ERROR)
                        return@launch
                    }

                    // One preview pipeline for every session context (W2): the accumulating
                    // window + sink, always. Live sessions additionally stream onto the strip.
                    showSessionPreview(live = sessionIsLive)

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
                // Only the live engine emits intra-segment deltas. The unified preview (W2)
                // keeps the container up for EVERY session context, so the strip renders
                // wherever deltas exist — no context gate. Resolved turns accumulate into the
                // window below it.
                serviceScope.launch(Dispatchers.Main) {
                    if (text.isNotBlank()) {
                        val wasGone = transcriptionDeltaText.visibility != View.VISIBLE
                        transcriptionDeltaText.visibility = View.VISIBLE
                        if (wasGone) {
                            // the strip just grew the window downward — keep it on-screen
                            bubbleView.post { reclampNow() }
                        }
                        transcriptionDeltaText.text = text
                        // Keep the newest words in view. The panel grows to maxLines then
                        // scrolls; without this it would hold the TOP of a long utterance and
                        // the live words would stream out of sight — the opposite of the point.
                        // Posted so the scroll runs after layout has measured the new text.
                        transcriptionDeltaText.post {
                            val overflow = transcriptionDeltaText.layout?.let { l ->
                                l.getLineBottom(l.lineCount - 1) - transcriptionDeltaText.height
                            } ?: 0
                            transcriptionDeltaText.scrollTo(0, overflow.coerceAtLeast(0))
                        }
                    } else {
                        transcriptionDeltaText.visibility = View.GONE
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
                    // W2: deliver best-effort BEFORE teardown nulls the sink and ends the
                    // session binding — a fatal error mid-FINALIZING must not eat the
                    // transcript. Connect-time fatals have an empty/absent sink, so the
                    // isNotEmpty gate keeps them delivery-free. finalDelivered keeps this
                    // once-only against the finalize coroutine, which skips its own delivery
                    // when it wakes in ERROR state (its FINALIZING guard fails).
                    runCatching {
                        deliverReleasedText(segmentOrderer.flush().text)
                        transcriptSink?.close()
                        val full = transcriptSink?.fullTextFile()?.readText()?.trim() ?: ""
                        if (full.isNotEmpty()) deliverFinalTranscript(full)
                    }
                    teardownRealtime()
                }
            }
            override fun onClosed() { /* expected on manual stop */ }
        })
    }

    private fun stopRecording() {
        // C1 finalize-timing: every phase of the stop path is measured from this instant.
        // Permanent diagnosis capability (spec 2026-08-18 C1) — grep "finalize-timing:".
        val stopTapNs = System.nanoTime()
        android.util.Log.i("WE-DIAG", "stopRecording: state=$currentState")
        vibrateStop()
        amplitudeJob?.cancel(); amplitudeJob = null
        waveformView.stop()
        audioRecorder.stop()

        stopPlaybackCapturer()
        // A finished session ENDS the screen share (owner decision 2026-08-01). The token used to
        // be retained so the next media session could skip re-consent — but that kept Android's
        // "sharing screen" indicator alive indefinitely after the capture the user actually wanted,
        // and a later mic tap looked like it was still screen-sharing. Releasing here is safe for
        // the drain below: the capturer above has already stopped reading, and transcription runs
        // on PCM already buffered. Cost: the next device-audio session shows the consent sheet
        // again — one tap, and the honest trade for the indicator never lying.
        // clear() is also what the system-side stop path calls, so both exits converge.
        if (com.whispereverywhere.audio.MediaProjectionGate.hasProjection()) {
            android.util.Log.i("WE-DIAG", "stopRecording: releasing screen-capture projection")
            com.whispereverywhere.audio.MediaProjectionGate.clear()
        }
        // Deliberately the raw field: the session is ending and the final commit + drain below
        // still belong to this session's engine. Every new session starts on the mic — connect()
        // runs before startAudioInput() picks a capturer.
        activeSource = com.whispereverywhere.audio.ActiveSource.MIC

        updateBubbleState(BubbleState.FINALIZING)

        // Keep the preview VISIBLE through the drain: the backlog of committed segments keeps
        // streaming into it, which is the honest "still working" signal. Hiding it here left
        // only the spinner, and after a long capture users read that as a hang (user feedback
        // 2026-07-18, 25-min video). The status line below names what's happening; the preview
        // and the line come down together when the drain completes.
        // Every session shows the closing status now (W2 unified preview) — the accumulating
        // window is up for TEXT_FIELD sessions too, and this line is its "still working" signal.
        // Cloud/live sessions name the actual wait (the tail segment's provider round-trip) so an
        // honest two-second drain never reads as a hang; cloudWrapper is non-null exactly for
        // CLOUD_WITH_FALLBACK / CLOUD_LIVE sessions and is not retired until the next session.
        transcriptionDeltaText.text = if (cloudWrapper != null) {
            "Finishing… (waiting on provider)"
        } else {
            "Finishing transcript…"
        }
        transcriptionDeltaText.visibility = View.VISIBLE
        
        // Flush whatever is buffered, UNCONDITIONALLY. The amplitude segmenter misses quiet
        // speech below its fixed thresholds — gating this flush on hasPendingSpeech() silently
        // discarded whole sessions for soft talkers ("No speech detected" despite real speech).
        // The native Silero VAD inside whisper_full now makes the unconditional flush safe: a
        // silence-only tail is trimmed to nothing and returns empty, fast, with no junk tokens.
        transcriptionEngine?.commit()
        android.util.Log.i(
            "WE-DIAG",
            "finalize-timing: commit-dispatch=${(System.nanoTime() - stopTapNs) / 1_000_000}ms",
        )
        speechSegmenter.reset()

        // Server-driven live only: the stop commit above cut the final open utterance under a tail
        // seq, but in server-driven mode NO server VAD endpoint can ever resolve it now — the mic is
        // closed, so no silence frame / `<end>` / committed_transcript will arrive. Resolve that tail
        // (and any still-in-flight committed turn) HERE, while the fallback's retained PCM is valid,
        // so the drain below rescues each on-device instead of looping the whole FINALIZE_TIMEOUT_MS
        // on a pending that can never empty and then dropping the tail as a bare marker at teardown.
        // No-op for batch/local (finishServerTurns guards on serverDriven, and lastLiveEngine is only
        // non-null for a CLOUD_LIVE session).
        if (sessionIsLive) lastLiveEngine?.finishServerTurns()

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
            // Release anything the orderer is still holding, BEFORE the final delivery reads
            // the sink — held text's only exit is flush(), and the pile is largest exactly
            // here, at the end of a session. (Provably empty for the on-device engine, which
            // resolves in order.)
            val flushStartNs = System.nanoTime()
            deliverReleasedText(segmentOrderer.flush().text)
            android.util.Log.i(
                "WE-DIAG",
                "finalize-timing: orderer-flush=${(System.nanoTime() - flushStartNs) / 1_000_000}ms",
            )

            // ---- W2 single delivery: the ONE external write of the session. Runs BEFORE
            // teardownRealtime, because teardown ends the injection-session binding captured
            // at beginInjectionSession and the SESSION_BOUND write must resolve against it.
            // The sink is closed first (full flush; teardown's later close is a swallowed
            // no-op), then its file is read back as the one transcript source every session
            // kind shares. Preview hide / sink close / endInjectionSession all FOLLOW delivery.
            // Detach the sink FIRST so a late segment's append no-ops on null (the pre-reorder
            // contract: close+null were one statement). Then close (full flush) and read back.
            val deliveryStartNs = System.nanoTime()
            val finishedSink = transcriptSink
            transcriptSink = null
            finishedSink?.close()
            val fullTranscript = finishedSink?.let { sink ->
                withContext(Dispatchers.IO) { sink.fullTextFile().readText().trim() }
            } ?: ""
            if (currentState == BubbleState.FINALIZING) {
                // Guarded like its two sibling callers (onDestroy, fatal onError): a delivery
                // failure must never wedge the session in FINALIZING — teardown always runs.
                runCatching { deliverFinalTranscript(fullTranscript) }
                    .onFailure { android.util.Log.e("WE-DIAG", "final delivery threw", it) }
            }
            android.util.Log.i(
                "WE-DIAG",
                "finalize-timing: delivery=${(System.nanoTime() - deliveryStartNs) / 1_000_000}ms",
            )
            teardownRealtime()
            android.util.Log.i("WE-DIAG", "finalize: state=$currentState producedText=$sessionProducedText")

            // Feed the stats the Home screen has been showing since 1.x. The tracker itself has
            // existed all along, but NOTHING ever called its record methods — the panel sat at
            // zero forever (owner report 2026-08-01, screenshot: 0:00 / 0 sec / 0 after weeks of
            // real use). Session seconds are wall-clock from startRecording to here (what a user
            // means by "time transcribing"), counted once per session at finalize; a session
            // that produced text counts as one transcription.
            // Capture the session stamp BEFORE the stats block zeroes it — the history persist
            // below names its file by this value, and saving with 0 meant "0.txt", which the
            // sweep on the next line deleted as decades stale (the vanishing-history bug:
            // every transcription silently erased at finalize since the stats fix landed).
            val sessionStamp = sessionStartMs
            if (sessionStartMs > 0L) {
                val sessionSeconds = ((System.currentTimeMillis() - sessionStartMs) / 1000L).toInt()
                if (sessionSeconds > 0) {
                    app.usageTracker.addUsage(sessionSeconds)
                    app.usageTracker.addToTotalUsage(sessionSeconds)
                    // Month cost estimate: only cloud sessions cost anything; the rate follows the
                    // transport the session actually used (live WS vs batch POST).
                    sessionCloudProviderId?.let {
                        app.cloudCostTracker.recordCloudSeconds(it, live = sessionIsLive, seconds = sessionSeconds)
                    }
                }
                sessionStartMs = 0L // exactly once per session, even if finalize re-enters
            }
            if (sessionProducedText) app.usageTracker.incrementTranscriptionCount()

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
                    transcriptStore.save(sessionStamp, sessionTranscript.toString())
                    transcriptStore.sweep()
                }
            }
            // C1 finalize-timing: total spans stop-tap → teardown/stats/history done. Logged
            // OUTSIDE the FINALIZING guard so every exit of the finalize coroutine reports it;
            // total minus (commit-dispatch + drains + orderer-flush + delivery) exposes the
            // teardown/stats/history span without needing its own phase name.
            android.util.Log.i(
                "WE-DIAG",
                "finalize-timing: total=${(System.nanoTime() - stopTapNs) / 1_000_000}ms",
            )
            if (currentState == BubbleState.FINALIZING) {
                // Delivery already happened above, pre-teardown, through FinalDeliveryPolicy.
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
        // Geometry change in the other direction (window shrinks back to the pill) — posted so
        // the re-measure has run. Harmless when nothing moved: reclampNow() no-ops on equality.
        bubbleView.post { reclampNow() }
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
     * in-order segment (preview sink + history) rather than a subset of that. Main
     * thread only.
     */
    private fun deliverReleasedText(text: String) {
        if (text.isBlank()) return
        sessionProducedText = true
        // The strip carried this utterance while it was in flight (live deltas); its resolved
        // text moves into the accumulating window via the sink below, so reset the strip for
        // the next one — or the finished words linger UNDER the next utterance as it streams
        // in, reading as duplicated text. Held during FINALIZING, where the strip carries the
        // "finishing transcript" status instead. Scroll reset too, so the next turn starts at
        // the top of the panel rather than wherever the last one left it parked.
        if (currentState != BubbleState.FINALIZING) {
            transcriptionDeltaText.text = ""
            transcriptionDeltaText.scrollTo(0, 0)
            transcriptionDeltaText.visibility = View.GONE
        }
        handleTranscriptionResult(text)
    }

    /**
     * Mid-session accumulation — and ONLY accumulation (W2 final-only commit). Every resolved
     * segment lands in exactly two places, for EVERY session context: the bounded-memory sink
     * (whose file is the transcript the final delivery reads) and sessionTranscript (history's
     * source, persisted at finalize). NOTHING leaves the app until stopRecording's
     * deliverFinalTranscript — no injection, no clipboard write, no caret pinning. FINALIZING
     * counts as in-session: drain-released segments and orderer flushes land here too.
     */
    private fun handleTranscriptionResult(text: String) {
        android.util.Log.i("WE-DIAG", "handleResult: session=$sessionContext live=$currentContext len=${text.length}")
        val historyTok = TextJoin.normalize(text)
        if (historyTok.isEmpty()) return
        if (sessionTranscript.isNotEmpty() && TextJoin.needsSpace(sessionTranscript, historyTok)) {
            sessionTranscript.append(' ')
        }
        sessionTranscript.append(historyTok)
        transcriptSink?.append(text)
        if (transcriptSink == null) {
            android.util.Log.i("WE-DIAG", "late segment after final read — kept in history only (${text.length} chars)")
        }
    }

    /**
     * The ONE external write of the session (W2 final-only commit). Called from stopRecording's
     * finalize block BEFORE teardownRealtime — teardown ends the injection-session binding, and
     * the SESSION_BOUND write must resolve the field captured at beginInjectionSession — or
     * best-effort from onDestroy. [finalDelivered] makes once-only hold even if destroy races
     * the finalize coroutine. Main thread.
     */
    private fun deliverFinalTranscript(full: String) {
        if (finalDelivered) return
        finalDelivered = true
        val plan = com.whispereverywhere.transcription.FinalDeliveryPolicy.decide(
            isTextFieldSession = sessionContext == BubbleContext.TEXT_FIELD,
            degradedToClipboard = sessionClipboardFallback,
            hasLiveInputTarget = WhisperAccessibilityService.hasLiveInputTarget(),
            transcriptBlank = full.isEmpty(),
        )
        android.util.Log.i(
            "WE-DIAG",
            "finalDelivery: inject=${plan.inject} copy=${plan.copyWholeToClipboard} len=${full.length}",
        )
        when (plan.inject) {
            com.whispereverywhere.transcription.InjectTarget.SESSION_BOUND -> {
                // Field session: one write through the session-bound target. Document/social
                // apps run their paste strategy exactly once; a dead node falls back to the
                // focused field INSIDE injectTextWithResult. Result handling mirrors the old
                // per-segment handler, adapted to stop-time copy.
                when (WhisperAccessibilityService.injectTextWithResult(full)) {
                    WhisperAccessibilityService.InjectionResult.SUCCESS -> {
                        // Typed where the user aimed it — no toast needed.
                    }
                    WhisperAccessibilityService.InjectionResult.CLIPBOARD_ONLY -> {
                        // The strategy already left the FULL transcript on the clipboard.
                        sessionClipboardFallback = true
                        showToast("Can't type here — full transcript copied to clipboard")
                    }
                    WhisperAccessibilityService.InjectionResult.FAILED -> {
                        sessionClipboardFallback = true
                        runCatching {
                            val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
                        }
                        showToast("Can't type here — full transcript copied to clipboard")
                    }
                }
            }
            com.whispereverywhere.transcription.InjectTarget.FINALIZE_FOCUS -> {
                // Preview session that ends with a live field focused: clipboard once, plus the
                // opportunistic finalize-time inject (targeting the CURRENT focus is BY DESIGN
                // here — covers capture-video-then-tap-into-prompt end to end; clipboard stays
                // set either way). Delivery now runs BEFORE teardown, so the record-start
                // session binding is still alive and would win inside resolveInjectionTarget —
                // end it first (idempotent; teardown's later call no-ops) so this write
                // resolves the field focused NOW, not the one focused when recording began.
                WhisperAccessibilityService.endInjectionSession()
                runCatching {
                    val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
                }
                val injected = WhisperAccessibilityService.injectTextWithResult(full) ==
                    WhisperAccessibilityService.InjectionResult.SUCCESS
                showToast(
                    if (injected) "Transcription inserted — also on your clipboard"
                    else "Transcription copied to clipboard",
                )
            }
            null -> if (plan.copyWholeToClipboard) {
                // Target-less preview session: ONE consolidated copy — genuinely the only
                // clipboard write of the session now. (The TEXT_FIELD wording below is
                // future-proofing: the degraded decide() row can't fire before delivery today,
                // because sessionClipboardFallback is only ever set BY deliverFinalTranscript.)
                runCatching {
                    val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
                }
                showToast(
                    if (sessionContext == BubbleContext.TEXT_FIELD) "Full transcription copied to clipboard"
                    else "Transcription copied to clipboard",
                )
            }
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
            keyboardLobe.visibility = View.GONE
            speakerLobe.visibility = View.GONE

            when (newState) {
                BubbleState.IDLE -> {
                    bubbleIcon.visibility = View.VISIBLE
                    bubbleIcon.setImageResource(
                        if (isSpeakingNow) R.drawable.ic_stop_speech else R.drawable.ic_mic,
                    )
                    // Lock lobe is TRANSIENT (owner 2026-08-01): long-press already pins, so a
                    // permanent lock hanging off the blob was redundant chrome. It flashes for
                    // ~1.5 s as confirmation whenever the pin state changes — see togglePin.
                    lockLobe.visibility = View.GONE
                    keyboardLobe.visibility =
                        if (app.preferencesManager.isDictationFirstKeyboard()) View.VISIBLE
                        else View.GONE
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
