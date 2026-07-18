package com.whispereverywhere.tts

import android.content.Context
import android.widget.Toast
import com.whispereverywhere.audio.AudioArbiter

/**
 * Process-wide mediator for read-aloud: owns the single TtsEngine, registers the speech side
 * of the AudioArbiter, and gives triggers (PROCESS_TEXT toolbar, bubble tap) one entry point.
 */
object TtsController {

    @Volatile private var engine: TtsEngine? = null

    fun engine(context: Context): TtsEngine {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val app = context.applicationContext
            val e = TtsEngine(app, TtsModelManager(app))
            AudioArbiter.isSpeaking = { e.isSpeaking() }
            AudioArbiter.stopSpeech = { e.stop() }
            engine = e
            return e
        }
    }

    fun isVoiceInstalled(context: Context): Boolean =
        TtsModelManager(context.applicationContext).isInstalled()

    /** Warm the model (e.g. when the bubble morphs to a speaker). No-op if not installed. */
    fun preload(context: Context) {
        engine(context).preload()
    }

    /**
     * Speak [text] from any trigger. Handles the not-installed and capture-in-progress cases
     * with user-visible feedback instead of silence.
     */
    fun speakFromTrigger(context: Context, text: String, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        if (!isVoiceInstalled(app)) {
            Toast.makeText(
                app,
                "Download the read-aloud voice in Whisper Everywhere settings first.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (!AudioArbiter.requestSpeak()) {
            Toast.makeText(
                app,
                "Finishing your transcription — tap again in a moment.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val e = engine(app)
        runCatching {
            val prefs = (app as com.whispereverywhere.WhisperEverywhereApp).preferencesManager
            e.speed = prefs.ttsSpeed
            e.speakerId = prefs.ttsVoiceId
        }
        e.speak(text, onDone)
    }

    fun stop() {
        engine?.stop()
    }
}
