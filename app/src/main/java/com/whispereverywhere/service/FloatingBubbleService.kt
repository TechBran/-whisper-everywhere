package com.whispereverywhere.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.core.content.ContextCompat
import com.whispereverywhere.MainActivity
import com.whispereverywhere.R
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.data.api.TranscriptionResult
import com.whispereverywhere.data.api.WhisperApiService
import com.whispereverywhere.ui.components.WaveformView
import com.whispereverywhere.util.AudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FloatingBubbleService : Service(),
    WhisperAccessibilityService.OnTextFieldFocusListener,
    MediaSessionDetector.MediaPlaybackListener {

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var bubbleContainer: FrameLayout
    private lateinit var bubbleIcon: ImageView
    private lateinit var processingRing: ImageView
    private lateinit var waveformView: WaveformView
    private lateinit var processingTimeText: android.widget.TextView

    private lateinit var audioRecorder: AudioRecorder
    private lateinit var whisperApi: WhisperApiService
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

    // Track the context for bubble display
    private var currentContext: BubbleContext = BubbleContext.NONE
    private var mediaTitle: String? = null

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
        audioRecorder = AudioRecorder(this)
        whisperApi = WhisperApiService(app.preferencesManager.apiKey)
        mediaDetector = MediaSessionDetector(this)

        createBubbleView()
        startForeground(WhisperEverywhereApp.NOTIFICATION_ID, createNotification())

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
        audioRecorder.cleanup()
        try {
            windowManager.removeView(bubbleView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        app.preferencesManager.setBubbleEnabled(false)
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

        // Position bubble at bottom-right corner for media
        val targetX = screenWidth - bubbleSize - padding
        val targetY = screenHeight - bubbleSize - padding - getNavigationBarHeight()

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

        val targetX = if (rect.right + bubbleSize + padding < screenWidth) {
            rect.right + padding
        } else {
            rect.left - bubbleSize - padding
        }

        val targetY = (rect.top - statusBarHeight).coerceIn(padding, screenHeight - bubbleSize - padding)

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

        val displayMetrics = resources.displayMetrics
        params.x = (app.preferencesManager.bubblePositionX * displayMetrics.widthPixels).toInt()
        params.y = (app.preferencesManager.bubblePositionY * displayMetrics.heightPixels).toInt()

        bubbleView.setOnTouchListener { _, event ->
            handleTouch(event)
        }

        windowManager.addView(bubbleView, params)

        bubbleView.visibility = View.GONE
        bubbleView.alpha = 0f
        isBubbleVisible = false

        updateBubbleState(BubbleState.IDLE)
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
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                    isDragging = true
                }

                if (isDragging) {
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    windowManager.updateViewLayout(bubbleView, params)
                }
                lastAction = event.action
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    handleBubbleClick()
                } else {
                    val displayMetrics = resources.displayMetrics
                    app.preferencesManager.bubblePositionX = params.x.toFloat() / displayMetrics.widthPixels
                    app.preferencesManager.bubblePositionY = params.y.toFloat() / displayMetrics.heightPixels
                    snapToEdge()
                }
                lastAction = event.action
                return true
            }
        }
        return false
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
            BubbleState.PROCESSING -> { /* Ignore */ }
            BubbleState.ERROR -> updateBubbleState(BubbleState.IDLE)
        }
    }

    private fun startRecording() {
        if (!app.preferencesManager.hasApiKey()) {
            vibrateError()
            showToast("Please set your OpenAI API key in Settings")
            return
        }

        if (!audioRecorder.hasPermission()) {
            vibrateError()
            showToast("Microphone permission required")
            return
        }

        updateBubbleState(BubbleState.RECORDING)
        vibrateStart()

        recordingJob = serviceScope.launch {
            val result = audioRecorder.startRecording()
            if (result.isFailure) {
                updateBubbleState(BubbleState.ERROR)
                showToast("Recording failed: ${result.exceptionOrNull()?.message}")
            }
        }

        amplitudeJob = serviceScope.launch {
            audioRecorder.amplitude.collectLatest { amplitude ->
                if (currentState == BubbleState.RECORDING) {
                    waveformView.updateAmplitude(amplitude)
                }
            }
        }
    }

    private fun stopRecording() {
        vibrateStop()

        amplitudeJob?.cancel()
        amplitudeJob = null
        waveformView.stopAnimation()

        val durationMs = audioRecorder.stopRecording()
        val durationSeconds = (durationMs / 1000).toInt()

        if (durationSeconds < 1) {
            updateBubbleState(BubbleState.IDLE)
            showToast("Recording too short")
            return
        }

        app.usageTracker.addUsage(durationSeconds)
        app.usageTracker.addToTotalUsage(durationSeconds)
        app.usageTracker.incrementTranscriptionCount()

        updateBubbleState(BubbleState.PROCESSING)

        serviceScope.launch {
            val audioFile = audioRecorder.getRecordingFile()
            if (audioFile == null || !audioFile.exists()) {
                updateBubbleState(BubbleState.ERROR)
                showToast("Audio file not found")
                return@launch
            }

            whisperApi.updateApiKey(app.preferencesManager.apiKey)

            // Get the selected language (null means auto-detect)
            val language = app.preferencesManager.getLanguageForApi()

            when (val result = whisperApi.transcribe(audioFile, language)) {
                is TranscriptionResult.Success -> {
                    val text = result.text.trim()
                    if (text.isNotEmpty()) {
                        handleTranscriptionResult(text)
                        updateBubbleState(BubbleState.IDLE)
                        vibrateSuccess()
                    } else {
                        updateBubbleState(BubbleState.ERROR)
                        showToast("No speech detected")
                    }
                }
                is TranscriptionResult.Error -> {
                    updateBubbleState(BubbleState.ERROR)
                    showToast(result.message)
                }
            }

            audioFile.delete()
        }
    }

    /**
     * Handle the transcription result based on current context
     */
    private fun handleTranscriptionResult(text: String) {
        when (currentContext) {
            BubbleContext.TEXT_FIELD -> {
                // Inject text into the focused field
                val injected = WhisperAccessibilityService.injectText(text)
                if (!injected) {
                    // Fallback to clipboard if injection failed
                    copyToClipboard(text)
                    showToast("Text copied to clipboard")
                }
            }
            BubbleContext.MEDIA_PLAYBACK, BubbleContext.NONE -> {
                // Copy to clipboard for media transcription
                copyToClipboard(text)
                showToast("Transcription copied to clipboard")
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
                    waveformView.stopAnimation()
                    bubbleContainer.setBackgroundResource(R.drawable.bubble_background_idle)
                    processingRing.visibility = View.GONE
                    processingRing.clearAnimation()
                    processingTimeText.visibility = View.GONE
                    stopProcessingTimer()

                    if (shouldHideOnIdle) {
                        shouldHideOnIdle = false
                        // Check if media is still playing
                        if (mediaDetector.isCurrentlyPlaying()) {
                            currentContext = BubbleContext.MEDIA_PLAYBACK
                            showBubbleForMedia()
                        } else {
                            currentContext = BubbleContext.NONE
                            hideBubble()
                        }
                    }
                }
                BubbleState.RECORDING -> {
                    bubbleIcon.visibility = View.GONE
                    waveformView.visibility = View.VISIBLE
                    waveformView.startAnimation()
                    bubbleContainer.setBackgroundResource(R.drawable.bubble_background_recording)
                    processingRing.visibility = View.GONE
                    startPulseAnimation()
                }
                BubbleState.PROCESSING -> {
                    bubbleIcon.visibility = View.GONE
                    waveformView.visibility = View.GONE
                    waveformView.stopAnimation()
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
                    waveformView.stopAnimation()
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
        IDLE, RECORDING, PROCESSING, ERROR
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
