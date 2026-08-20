package com.whispereverywhere.transcription

import android.util.Log
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.util.AudioMath
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bundled GPU-canary clip (3.6.0 Workstream C): ~1 s of spoken digits, PCM16 mono 16 kHz,
 * shipped in main assets so PRODUCTION code can transcribe it during a cold GPU load.
 *
 * Kept in memory only for the duration of one canary run — the samples are re-read each time
 * (once per device+model, ever) rather than cached, because the whole point is that this path
 * runs at most once per app version and must cost nothing afterwards.
 */
object CanaryAudio {
    private const val TAG = "WE-DIAG"
    const val ASSET = "canary_digits.wav"

    /**
     * Float samples for the canary clip, or null when the asset is missing/unreadable.
     *
     * null means **"no verdict is possible"**, NOT "the canary failed". The caller must fall back
     * to CPU for this load and record NOTHING: a canary FAILURE is a permanent per-(versionCode,
     * model) CPU latch, and letting a packaging slip (asset omitted, wrong format, asset shrink)
     * write that latch would ban a perfectly good GPU for the entire app version with no in-app
     * recovery. Leaving the verdict unrecorded means the next launch simply retries.
     */
    fun samples(): FloatArray? = runCatching {
        val bytes = WhisperEverywhereApp.getInstance().assets.open(ASSET).use { it.readBytes() }
        val pcm = dataChunk(bytes)
        if (pcm.isEmpty()) null else AudioMath.pcm16ToFloat(pcm)
    }.onFailure {
        Log.w(TAG, "CanaryAudio: $ASSET unreadable — no canary verdict can be recorded", it)
    }.getOrNull()

    /**
     * Raw bytes of the WAV "data" chunk, walking the chunk list rather than assuming a 44-byte
     * header (real encoders emit LIST/fact chunks first). Mirrors WhisperBenchTest.wavDataChunk.
     * Pure — JVM-pinned in CanaryAudioTest.
     */
    fun dataChunk(bytes: ByteArray): ByteArray {
        var i = 12 // skip "RIFF"<size>"WAVE"
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val end = minOf(i + 8 + chunkSize, bytes.size)
                return bytes.copyOfRange(i + 8, end)
            }
            i += 8 + chunkSize + (chunkSize and 1)
        }
        return ByteArray(0)
    }
}
