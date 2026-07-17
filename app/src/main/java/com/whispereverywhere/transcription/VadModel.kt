package com.whispereverywhere.transcription

import android.util.Log
import com.whispereverywhere.WhisperEverywhereApp
import java.io.File

/**
 * Provides the on-disk path of the bundled Silero VAD model (ggml-silero-v5.1.2.bin, ~0.9 MB).
 *
 * whisper.cpp's built-in VAD (whisper_full_params.vad) needs a real file path, so the asset is
 * extracted once into filesDir/vad/. Returns null on any failure — the native layer then simply
 * runs without VAD (previous behavior), never an error.
 */
object VadModel {
    private const val TAG = "WE-DIAG"
    private const val ASSET = "ggml-silero-v5.1.2.bin"

    @Volatile private var cachedPath: String? = null

    fun path(): String? {
        cachedPath?.let { return it }
        return runCatching {
            val app = WhisperEverywhereApp.getInstance()
            val dir = File(app.filesDir, "vad").apply { mkdirs() }
            val out = File(dir, ASSET)
            val expected = app.assets.open(ASSET).use { it.available().toLong() }
            if (!out.exists() || out.length() != expected) {
                app.assets.open(ASSET).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "VadModel: extracted $ASSET (${out.length()} bytes)")
            }
            out.absolutePath.also { cachedPath = it }
        }.onFailure {
            Log.w(TAG, "VadModel: extraction failed (running without VAD)", it)
        }.getOrNull()
    }
}
