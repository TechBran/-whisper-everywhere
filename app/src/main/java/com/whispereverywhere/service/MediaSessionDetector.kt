package com.whispereverywhere.service

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Detects active media playback (audio/video) across all apps.
 * Uses MediaSessionManager to monitor playback state changes.
 *
 * When media is playing, notifies the FloatingBubbleService to show the bubble
 * so users can easily transcribe audio/video content.
 */
class MediaSessionDetector(private val context: Context) {

    interface MediaPlaybackListener {
        fun onMediaPlaybackStarted(packageName: String, title: String?)
        fun onMediaPlaybackStopped()
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private var listener: MediaPlaybackListener? = null
    private var isMediaPlaying = false
    private var currentMediaPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())

    // Track active controllers and their callbacks
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        handleActiveSessionsChanged(controllers)
    }

    fun setListener(listener: MediaPlaybackListener?) {
        this.listener = listener
    }

    fun startMonitoring() {
        try {
            mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

            // Get the notification listener component (required for media session access)
            val componentName = ComponentName(context, MediaNotificationListener::class.java)

            try {
                mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)

                // Check current active sessions
                val activeSessions = mediaSessionManager?.getActiveSessions(componentName)
                handleActiveSessionsChanged(activeSessions)

                // Also start audio polling as backup for apps that don't create media sessions
                // (like YouTube Shorts, some social media apps)
                startAudioPolling()
            } catch (e: SecurityException) {
                // Notification listener permission not granted - try without component
                // This will have limited functionality but won't crash
                tryMonitoringWithoutNotificationListener()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun tryMonitoringWithoutNotificationListener() {
        // Poll for audio focus changes as a fallback
        // This is less reliable but doesn't require notification listener permission
        startAudioPolling()
    }

    private var audioPollingRunnable: Runnable? = null
    private var isAudioPollingActive = false

    /**
     * Start polling AudioManager for audio playback.
     * This catches apps that don't create MediaSessions (like YouTube Shorts).
     */
    private fun startAudioPolling() {
        if (isAudioPollingActive) return
        isAudioPollingActive = true

        audioPollingRunnable = object : Runnable {
            override fun run() {
                checkAudioPlaybackState()
                if (listener != null && isAudioPollingActive) {
                    handler.postDelayed(this, 1000) // Check every second for responsiveness
                }
            }
        }
        handler.post(audioPollingRunnable!!)
    }

    private fun stopAudioPolling() {
        isAudioPollingActive = false
        audioPollingRunnable?.let { handler.removeCallbacks(it) }
        audioPollingRunnable = null
    }

    private fun checkAudioPlaybackState() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return

            // Check multiple audio sources to catch all scenarios:
            // 1. isMusicActive - catches music, videos, most media playback
            // 2. Mode checks - catches voice/video calls (Zoom, Meet, Teams, phone calls)
            // 3. Speaker/Bluetooth - additional indicators of active audio

            val isMusicActive = audioManager.isMusicActive

            // Check if device is in a call mode (voice call, video call, etc.)
            val audioMode = audioManager.mode
            val isInCallMode = audioMode == AudioManager.MODE_IN_CALL ||
                    audioMode == AudioManager.MODE_IN_COMMUNICATION ||
                    audioMode == AudioManager.MODE_CALL_SCREENING

            // Check if speakerphone is on (common during video calls)
            val isSpeakerOn = audioManager.isSpeakerphoneOn

            // Check if Bluetooth audio is connected and potentially active
            val isBluetoothActive = audioManager.isBluetoothScoOn ||
                    audioManager.isBluetoothA2dpOn

            // Determine if any audio is likely playing
            val isAudioActive = isMusicActive || isInCallMode

            // Only use audio polling if MediaSession hasn't detected anything
            // This prevents duplicate notifications
            if (isAudioActive && !isMediaPlaying) {
                // Double-check no media session is active
                val hasActiveSession = controllerCallbacks.keys.any { controller ->
                    val state = controller.playbackState?.state
                    state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_BUFFERING
                }

                if (!hasActiveSession) {
                    isMediaPlaying = true
                    currentMediaPackage = "audio"

                    // Provide more context about what type of audio
                    val audioType = when {
                        isInCallMode -> "Voice/Video Call"
                        isMusicActive -> "Audio Playing"
                        else -> "Audio Active"
                    }
                    listener?.onMediaPlaybackStarted("unknown", audioType)
                }
            } else if (!isAudioActive && isMediaPlaying && currentMediaPackage == "audio") {
                // Only stop if we were the ones who triggered it via audio polling
                isMediaPlaying = false
                currentMediaPackage = null
                listener?.onMediaPlaybackStopped()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopMonitoring() {
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)

            // Remove all controller callbacks
            controllerCallbacks.forEach { (controller, callback) ->
                try {
                    controller.unregisterCallback(callback)
                } catch (e: Exception) {
                    // Ignore
                }
            }
            controllerCallbacks.clear()

            // Stop audio polling
            stopAudioPolling()

            handler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        isMediaPlaying = false
        currentMediaPackage = null
    }

    private fun handleActiveSessionsChanged(controllers: List<MediaController>?) {
        // Remove old callbacks
        controllerCallbacks.forEach { (controller, callback) ->
            try {
                controller.unregisterCallback(callback)
            } catch (e: Exception) {
                // Ignore
            }
        }
        controllerCallbacks.clear()

        if (controllers.isNullOrEmpty()) {
            if (isMediaPlaying) {
                isMediaPlaying = false
                currentMediaPackage = null
                listener?.onMediaPlaybackStopped()
            }
            return
        }

        // Register callbacks for all active controllers
        controllers.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    handlePlaybackStateChanged(controller, state)
                }
            }

            try {
                controller.registerCallback(callback)
                controllerCallbacks[controller] = callback

                // Check initial state
                handlePlaybackStateChanged(controller, controller.playbackState)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handlePlaybackStateChanged(controller: MediaController, state: PlaybackState?) {
        // Check for various "playing" states - Shorts and short-form video often use different states
        val playingStates = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,  // Often used during Shorts playback
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING
        )
        val isPlaying = state?.state in playingStates
        val packageName = controller.packageName
        val title = controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)

        handler.post {
            if (isPlaying) {
                if (!isMediaPlaying || currentMediaPackage != packageName) {
                    isMediaPlaying = true
                    currentMediaPackage = packageName
                    listener?.onMediaPlaybackStarted(packageName, title)
                }
            } else if (currentMediaPackage == packageName && isMediaPlaying) {
                // Check if any other controller is still playing
                val anyPlaying = controllerCallbacks.keys.any {
                    it.playbackState?.state in playingStates
                }

                if (!anyPlaying) {
                    isMediaPlaying = false
                    currentMediaPackage = null
                    listener?.onMediaPlaybackStopped()
                }
            }
        }
    }

    fun isCurrentlyPlaying(): Boolean = isMediaPlaying

    companion object {
        // Common media/video app packages for reference
        val KNOWN_MEDIA_APPS = setOf(
            // Video streaming
            "com.google.android.youtube",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.hulu.plus",
            "com.disney.disneyplus",
            "com.hbo.hbonow",
            "com.hbo.hbomax",
            "com.peacocktv.peacockandroid",
            "com.cbs.app",
            "com.plexapp.android",

            // Music streaming
            "com.spotify.music",
            "com.apple.android.music",
            "com.pandora.android",
            "com.soundcloud.android",
            "com.google.android.apps.youtube.music",
            "com.tiktok.music",
            "com.amazon.mp3",
            "deezer.android.app",
            "com.aspiro.tidal",

            // Video players
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",

            // Social media
            "com.twitter.android",
            "com.instagram.android",
            "com.facebook.katana",
            "com.zhiliaoapp.musically", // TikTok
            "com.snapchat.android",
            "com.linkedin.android",

            // Video conferencing / Calls
            "us.zoom.videomeetings",
            "com.google.android.apps.meetings", // Google Meet
            "com.microsoft.teams",
            "com.skype.raider",
            "com.cisco.webex.meetings",
            "com.ringcentral.android",
            "com.slack",
            "com.discord",
            "com.facebook.orca", // Messenger
            "com.whatsapp",
            "org.telegram.messenger",
            "com.viber.voip",
            "com.google.android.apps.tachyon", // Google Duo
            "com.linecorp.linelite",

            // Phone / Dialer
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.phone",

            // Podcast / Audiobook
            "com.google.android.apps.podcasts",
            "com.audible.application",
            "au.com.shiftyjelly.pocketcasts",
            "fm.castbox.audiobook.radio.podcast",

            // Live streaming
            "tv.twitch.android.app",
            "com.kick.android"
        )
    }
}

/**
 * NotificationListenerService required to access MediaSessionManager.
 * This service needs to be enabled in Settings > Apps > Special app access > Notification access
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    companion object {
        @Volatile
        private var instance: MediaNotificationListener? = null

        fun isEnabled(): Boolean = instance != null
    }
}
