package com.whispereverywhere.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * ACTION_PROCESS_TEXT trampoline — the "Speak" entry in the system text-selection toolbar
 * (the universal read-aloud trigger; works in Chrome/WebView/Gmail where accessibility
 * selection events never arrive).
 *
 * Theme.NoDisplay contract: MUST finish() inside onCreate — completing onResume without a
 * visible window throws on API 23+. Zero async work happens here; the text is handed to
 * TtsController and the activity is gone before synthesis starts.
 */
class SpeakTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!text.isNullOrBlank()) {
            // Cap pathological selections; spans are dropped by toString above.
            TtsController.speakFromTrigger(this, text.take(MAX_CHARS))
        }
        finish()
    }

    private companion object {
        // Whole-page reads ("Select all" -> Speak in Chrome) arrive here; the engine chunks
        // synthesis per sentence, so large text streams — the cap only guards pathology.
        const val MAX_CHARS = 100_000
    }
}
