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
import com.whispereverywhere.transcription.LocalWhisperEngine
import com.whispereverywhere.transcription.TranscriptionEngine
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

class FloatingBubbleService : Service(),
    WhisperAccessibilityService.OnTextFieldFocusListener,
    MediaSessionDetector.MediaPlaybackListener {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var bubbleContainer: FrameLayout
    private lateinit var bubbleIcon: ImageView
    private lateinit var processingRing: ImageView
    private lateinit var waveformView: BarWaveformView
    private lateinit var processingTimeText: android.widget.TextView
    private lateinit var transcriptionPreviewContainer: View
    private lateinit var transcriptionEditText: android.widget.TextView
    private lateinit var transcriptionDeltaText: android.widget.TextView

    private lateinit var audioRecorder: StreamingAudioRecorder
    private var transcriptionEngine: TranscriptionEngine? = null
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

    // Max time finalize waits for the last utterance's transcribe to finish before force-ending the
    // session. Generous so a slow large-model segment (<=15s of audio) always completes and its text
    // is delivered; bounded so a pathological transcribe can't hang the bubble in FINALIZING forever.
    private val FINALIZE_TIMEOUT_MS = 120_000L

    // Pin icon view reference (lateinit; populated in createBubbleView)
    private lateinit var pinIcon: ImageView

    // Track the context for bubble display
    private var currentContext: BubbleContext = BubbleContext.NONE
    private var mediaTitle: String? = null

    // Bounded-memory sink for non-text-field sessions (Task 7)
    private var transcriptSink: com.whispereverywhere.transcription.TranscriptSink? = null

    // Tracks the preview-collector coroutine so a second recording doesn't leave two collectors running (Fix I1)
    private var previewJob: kotlinx.coroutines.Job? = null

    private lateinit var params: WindowManager.LayoutParams

    private val app by lazy { WhisperEverywhereApp.getInstance() }
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

        createBubbleView()
        
        // Start foreground service with correct type for Android 14
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, WhisperEverywhereApp.NOTIFICATION_ID, notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(WhisperEverywhereApp.NOTIFICATION_ID, notification)
        }

        // Register for text field focus events
        registerFocusListener()

        // Start media playback detection
        mediaDetector.setListener(this)
        mediaDetector.startMonitoring()

        // Start monitoring accessibility service connection
        startConnectionMonitor()
    }

    /**
     * Register this service as the focus listener.
     */
    private fun registerFocusListener() {
        WhisperAccessibilityService.setFocusListener(this)
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
                stopSelf()
            }
        }
        registerFocusListener()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        WhisperAccessibilityService.setFocusListener(null)
        mediaDetector.setListener(null)
        mediaDetector.stopMonitoring()
        connectionMonitorJob?.cancel()
        serviceScope.cancel()
        pulseAnimator?.cancel()
        showAnimator?.cancel()
        hideAnimator?.cancel()
        audioRecorder.stop()
        teardownRealtime()
        // Fully release the reused engine on service end: free the native context and stop its
        // worker thread (teardownRealtime only detaches the session listener, for reuse).
        (transcriptionEngine as? LocalWhisperEngine)?.shutdown()
        transcriptionEngine = null
        try {
            windowManager.removeView(bubbleView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        app.preferencesManager.setBubbleEnabled(false)
    }

    // ========== Configuration changes (rotation / fold — drift hardening) ==========

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-clamp bubble position to the updated screen bounds after a rotation or fold event.
        // Post to the next layout pass so displayMetrics already reflects the new orientation.
        bubbleView.post { reclampAfterConfigChange() }
    }

    // ========== Text Field Focus Listener ==========

    override fun onTextFieldFocused(rect: Rect) {
        serviceScope.launch(Dispatchers.Main) {
            currentContext = BubbleContext.TEXT_FIELD
            shouldHideOnIdle = false
            showBubbleNearTextField(rect)
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
                            showBubbleForMedia()
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
                showBubbleForMedia()
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
        processingTimeText = bubbleView.findViewById(R.id.processing_time_text)
        transcriptionPreviewContainer = bubbleView.findViewById(R.id.transcription_preview_container)
        transcriptionEditText = bubbleView.findViewById(R.id.transcription_edit_text)
        transcriptionDeltaText = bubbleView.findViewById(R.id.transcription_delta_text)
        pinIcon = bubbleView.findViewById(R.id.pin_icon)

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
        pinIcon.setOnClickListener {
            togglePin()
        }

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
        showToast(if (isOverlayPinned) "Bubble pinned" else "Bubble unpinned")
    }

    /** Update the pin icon alpha to reflect current pinned state. */
    private fun applyPinIndicator() {
        // Full opacity when pinned, subtle hint when unpinned
        pinIcon.alpha = if (isOverlayPinned) 1.0f else 0.35f
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
                    val displayMetrics = resources.displayMetrics
                    app.preferencesManager.bubblePositionX = params.x.toFloat() / displayMetrics.widthPixels
                    app.preferencesManager.bubblePositionY = params.y.toFloat() / displayMetrics.heightPixels
                    snapToEdge()
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
        ValueAnimator.ofInt(lp.width, target).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                lp.width = it.animatedValue as Int
                bubbleContainer.layoutParams = lp
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
            BubbleState.IDLE -> startRecording()
            BubbleState.RECORDING -> stopRecording()
            BubbleState.CONNECTING, BubbleState.FINALIZING, BubbleState.PROCESSING -> { /* ignore */ }
            BubbleState.ERROR -> updateBubbleState(BubbleState.IDLE)
        }
    }

    private fun startRecording() {
        if (!audioRecorder.hasPermission()) {
            vibrateError(); showToast("Microphone permission required"); return
        }

        updateBubbleState(BubbleState.CONNECTING)
        vibrateStart()

        // On-device engine. connect() resolves the installed model and loads the
        // native context off-thread; CONNECTING covers that model-load wait and
        // onOpen() fires only once the context is ready.
        // Reuse a single engine across sessions so the native model context is loaded once and
        // reused (spec: "loaded once and reused"); it is released only on memory pressure
        // (onTrimMemory) or on service destroy (onDestroy), not at the end of each recording.
        val engine: TranscriptionEngine = transcriptionEngine
            ?: LocalWhisperEngine(app.whisperModelManager).also { transcriptionEngine = it }

        engine.connect(app.preferencesManager.getLanguageForApi(), object : TranscriptionEngine.Listener {
            override fun onOpen() {
                serviceScope.launch(Dispatchers.Main) {
                    if (currentState != BubbleState.CONNECTING) return@launch
                    val started = audioRecorder.start { chunk -> engine.sendAudio(chunk) }
                    if (started.isFailure) {
                        showToast("Recording failed: ${started.exceptionOrNull()?.message}")
                        teardownRealtime(); updateBubbleState(BubbleState.ERROR)
                        return@launch
                    }
                    speechSegmenter.reset()

                    // Show preview text bubble if we are not injecting into a text field
                    if (currentContext != BubbleContext.TEXT_FIELD) {
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
                    } else {
                        transcriptionPreviewContainer.visibility = View.GONE
                    }

                    updateBubbleState(BubbleState.RECORDING)
                    amplitudeJob = serviceScope.launch {
                        audioRecorder.amplitude.collectLatest { amp ->
                            if (currentState != BubbleState.RECORDING) return@collectLatest
                            waveformView.updateAmplitude(amp)
                            // Client VAD: commit on a natural pause (or max segment) so each
                            // utterance is transcribed on-device and injected per segment.
                            if (speechSegmenter.onAmplitude(amp, System.currentTimeMillis())) {
                                transcriptionEngine?.commit()
                            }
                        }
                    }
                }
            }
            override fun onDelta(text: String) {
                // On-device engine emits no intra-segment deltas; kept for interface parity.
                if (currentContext != BubbleContext.TEXT_FIELD) {
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
            override fun onCompleted(text: String) {
                val trimmed = text.trim()
                if (trimmed.isNotEmpty()) {
                    serviceScope.launch(Dispatchers.Main) {
                        if (currentContext != BubbleContext.TEXT_FIELD) {
                            transcriptionDeltaText.visibility = View.GONE
                            // Route through the bounded-memory sink; the preview StateFlow
                            // drives transcriptionEditText via collectLatest above (Task 7).
                            transcriptSink?.append(trimmed)
                        }
                        handleTranscriptionResult(trimmed)
                    }
                }
            }
            override fun onError(message: String) {
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
        vibrateStop()
        amplitudeJob?.cancel(); amplitudeJob = null
        waveformView.stop()
        audioRecorder.stop()

        updateBubbleState(BubbleState.FINALIZING)
        
        // Hide the preview bubble immediately so it doesn't linger during the finalizing delay
        if (currentContext != BubbleContext.TEXT_FIELD) {
            transcriptionPreviewContainer.visibility = View.GONE
        }
        
        // Flush any speech captured since the last pause-commit.
        val hadPendingSpeech = speechSegmenter.hasPendingSpeech()
        if (hadPendingSpeech) {
            transcriptionEngine?.commit()
        }
        speechSegmenter.reset()

        // Wait for the queued final transcribe to actually finish before detaching the listener, so
        // the last utterance is delivered (large models take many seconds; a fixed delay would drop
        // it via the identity guard). Bounded by FINALIZE_TIMEOUT_MS so the bubble can't hang in
        // FINALIZING forever. The blocking await runs on IO; all UI work stays on Main.
        serviceScope.launch(Dispatchers.Main) {
            if (hadPendingSpeech) {
                withContext(Dispatchers.IO) {
                    (transcriptionEngine as? LocalWhisperEngine)?.awaitIdle(FINALIZE_TIMEOUT_MS)
                }
            }
            // Capture the sink before teardown nulls it, so the full transcript can still be read.
            val finalizingSink = transcriptSink
            teardownRealtime()
            if (currentState == BubbleState.FINALIZING) {
                // If we were transcribing without injecting, read the full text from the sink's
                // session file (bounded memory; Task 7) and copy it to the clipboard.
                if (currentContext != BubbleContext.TEXT_FIELD) {
                    previewJob?.cancel(); previewJob = null
                    finalizingSink?.let { sink ->
                        // teardownRealtime already closed the sink; just read its flushed file.
                        val full = withContext(Dispatchers.IO) { sink.fullTextFile().readText().trim() }
                        if (full.isNotEmpty()) {
                            val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
                            showToast("Transcription copied to clipboard")
                        }
                    }
                }

                vibrateSuccess()
                updateBubbleState(BubbleState.IDLE)
            }
        }
    }

    private fun teardownRealtime() {
        previewJob?.cancel(); previewJob = null
        transcriptSink?.close(); transcriptSink = null
        // Detach the session listener but KEEP the engine + its loaded native context so the next
        // recording reuses it (no multi-hundred-MB reload per session). Full release (context +
        // worker thread) happens in onDestroy; the context is also freed under memory pressure
        // in onTrimMemory, reloading lazily on the next connect().
        transcriptionEngine?.close()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && currentState != BubbleState.RECORDING && currentState != BubbleState.FINALIZING) {
            (transcriptionEngine as? LocalWhisperEngine)?.releaseContext()
        }
    }

    /**
     * Handle the transcription result based on current context
     */
    private fun handleTranscriptionResult(text: String) {
        when (currentContext) {
            BubbleContext.TEXT_FIELD -> {
                // Use the new injection method with detailed result
                val result = WhisperAccessibilityService.injectTextWithResult(text)
                when (result) {
                    WhisperAccessibilityService.InjectionResult.SUCCESS -> {
                        // Text was successfully injected - no toast needed
                    }
                    WhisperAccessibilityService.InjectionResult.CLIPBOARD_ONLY -> {
                        // Text is in clipboard but couldn't be pasted automatically
                        showToast("Text copied - long press to paste")
                    }
                    WhisperAccessibilityService.InjectionResult.FAILED -> {
                        // Complete failure - try one more time to copy
                        copyToClipboard(text)
                        showToast("Text copied to clipboard")
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

            when (newState) {
                BubbleState.IDLE -> {
                    bubbleIcon.visibility = View.VISIBLE
                    bubbleIcon.setImageResource(R.drawable.ic_mic)
                    waveformView.visibility = View.GONE
                    waveformView.stop()
                    setBubbleWidth(56)
                    bubbleContainer.setBackgroundResource(R.drawable.bubble_background_idle)
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
                    bubbleContainer.setBackgroundResource(R.drawable.bubble_background_processing)
                    startRotationAnimation()
                }
                BubbleState.RECORDING -> {
                    bubbleIcon.visibility = View.GONE
                    processingRing.visibility = View.GONE
                    processingRing.clearAnimation()
                    setBubbleWidth(160)
                    waveformView.visibility = View.VISIBLE
                    waveformView.start()
                    bubbleContainer.setBackgroundResource(R.drawable.bubble_background_recording)
                    startPulseAnimation()
                }
                BubbleState.FINALIZING -> {
                    pulseAnimator?.cancel()
                    waveformView.stop()
                    waveformView.visibility = View.GONE
                    setBubbleWidth(56)
                    processingRing.visibility = View.VISIBLE
                    bubbleContainer.setBackgroundResource(R.drawable.bubble_background_processing)
                    startRotationAnimation()
                }
                BubbleState.PROCESSING -> {
                    bubbleIcon.visibility = View.GONE
                    waveformView.visibility = View.GONE
                    waveformView.stop()
                    bubbleContainer.setBackgroundResource(R.drawable.bubble_background_processing)
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
                    bubbleContainer.setBackgroundResource(R.drawable.bubble_background_error)
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
