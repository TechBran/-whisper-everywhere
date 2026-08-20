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
     * Float samples for the canary clip, or null when the asset is missing/unreadable/wrong format.
     *
     * null means **"no verdict is possible"**, NOT "the canary failed". The caller must fall back
     * to CPU for this load and record NOTHING: a canary FAILURE is a permanent per-(versionCode,
     * model) CPU latch, and letting a packaging slip (asset omitted, wrong format, asset shrink)
     * write that latch would ban a perfectly good GPU for the entire app version with no in-app
     * recovery. Leaving the verdict unrecorded means the next launch simply retries.
     *
     * Returns null also when the WAV format is not 16kHz mono PCM16 — a mismatched format feeds
     * garbage to whisper and would trigger a false CPU latch, so any deviation in channels,
     * sample rate, or bit depth returns null with no verdict recorded.
     */
    fun samples(): FloatArray? = runCatching {
        val bytes = WhisperEverywhereApp.getInstance().assets.open(ASSET).use { it.readBytes() }
        if (!formatIsValid(bytes)) {
            Log.w(TAG, "gpu-canary: asset unreadable or wrong format — no verdict possible")
            return null
        }
        val pcm = dataChunk(bytes)
        if (pcm.isEmpty()) {
            Log.w(TAG, "gpu-canary: asset unreadable or wrong format — no verdict possible")
            null
        } else {
            AudioMath.pcm16ToFloat(pcm)
        }
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
            if (chunkSize < 0) return ByteArray(0)
            if (id == "data") {
                val end = minOf(i + 8 + chunkSize, bytes.size)
                return bytes.copyOfRange(i + 8, end)
            }
            i += 8 + chunkSize + (chunkSize and 1)
        }
        return ByteArray(0)
    }

    /**
     * Validates that the WAV format is 16kHz mono PCM16 by parsing the fmt chunk.
     * Returns false if the format deviates or no fmt chunk is found — either case means
     * the samples cannot be trusted and must not be fed to whisper.
     * Pure — JVM-pinned in CanaryAudioTest.
     */
    fun formatIsValid(bytes: ByteArray): Boolean {
        var i = 12 // skip "RIFF"<size>"WAVE"
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkSize < 0) return false
            if (id == "fmt ") {
                // fmt chunk: id(4) size(4) audioFormat(2) channels(2) sampleRate(4) byteRate(4) blockAlign(2) bitsPerSample(2)
                // Offsets from i: channels @ 10, sampleRate @ 12, bitsPerSample @ 22
                if (i + 8 + 16 > bytes.size) return false
                val channels = ByteBuffer.wrap(bytes, i + 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val sampleRate = ByteBuffer.wrap(bytes, i + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val bitsPerSample = ByteBuffer.wrap(bytes, i + 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                return channels == 1 && sampleRate == 16000 && bitsPerSample == 16
            }
            i += 8 + chunkSize + (chunkSize and 1)
        }
        return false
    }
}
