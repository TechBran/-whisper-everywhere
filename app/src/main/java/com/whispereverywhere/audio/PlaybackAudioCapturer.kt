package com.whispereverywhere.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.whispereverywhere.util.AudioMath

/**
 * Captures the DEVICE's audio output (media playback) via AudioPlaybackCapture and delivers
 * it in the exact chunk contract of StreamingAudioRecorder: PCM16 mono 16 kHz, ~32 ms chunks,
 * (chunk, rmsAmplitude) callback. The microphone is never touched.
 *
 * Format: 16 kHz mono is requested directly (the platform mixer resamples for us on most
 * devices); if that AudioRecord refuses to initialize, we capture 48 kHz mono and decimate.
 *
 * Silent-stream watchdog: DRM-protected apps (Netflix etc.) opt out of capture — the stream
 * then arrives as digital silence. After [SILENT_TIMEOUT_MS] with no energy, [onSilentStream]
 * fires exactly once so the service can fall back to the microphone.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackAudioCapturer(
    private val projection: MediaProjection,
    /** NOTE: invoked from the CAPTURE thread — post to the main thread before touching UI/service state. */
    private val onSilentStream: () -> Unit,
) {

    @Volatile private var recording = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var decimator: Pcm48kTo16kDecimator? = null

    fun start(onChunk: (ByteArray, Int) -> Unit): Result<Unit> {
        check(!recording) { "start() called while already capturing" }
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        var sampleRate = 16000
        var rec = buildRecord(config, sampleRate)
        if (rec == null) {
            sampleRate = 48000
            rec = buildRecord(config, sampleRate)
            decimator = Pcm48kTo16kDecimator()
        }
        if (rec == null) {
            return Result.failure(IllegalStateException("Playback-capture AudioRecord failed to initialize"))
        }
        record = rec
        Log.i("WE-DIAG", "PlaybackAudioCapturer: capturing at ${sampleRate}Hz (decimate=${decimator != null})")

        recording = true
        rec.startRecording()

        // 32 ms per read: 1024 bytes @16k, 3072 bytes @48k (decimates to ~1024).
        val readSize = if (sampleRate == 16000) 1024 else 3072
        thread = Thread {
            val buffer = ByteArray(readSize)
            var lastLoudMs = System.currentTimeMillis()
            var silentFired = false
            while (recording) {
                val read = record?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val out = decimator?.process(buffer, read) ?: buffer.copyOf(read)
                    if (out.isEmpty()) continue
                    val amp = AudioMath.amplitude(out, out.size)
                    val now = System.currentTimeMillis()
                    if (amp > 200) lastLoudMs = now
                    if (!silentFired && now - lastLoudMs > SILENT_TIMEOUT_MS) {
                        silentFired = true
                        Log.w("WE-DIAG", "PlaybackAudioCapturer: stream silent ${SILENT_TIMEOUT_MS}ms (DRM opt-out?)")
                        onSilentStream()
                    }
                    onChunk(out, amp)
                }
            }
            Log.i("WE-DIAG", "PlaybackAudioCapturer: capture thread stopped")
        }.also { it.start() }

        return Result.success(Unit)
    }

    fun stop() {
        recording = false
        // Stop the AudioRecord BEFORE joining: read() blocks until a buffer fills, and
        // halting the record is what unblocks it immediately (join alone can wait a full
        // read period).
        record?.let { runCatching { it.stop() } }
        thread?.join(2000)
        thread = null
        record?.let { runCatching { it.release() } }
        record = null
        decimator = null
    }

    @SuppressLint("MissingPermission") // capture config replaces the mic permission path
    private fun buildRecord(config: AudioPlaybackCaptureConfiguration, sampleRate: Int): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val rec = runCatching {
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf)
                .build()
        }.getOrNull() ?: return null
        return if (rec.state == AudioRecord.STATE_INITIALIZED) rec else {
            runCatching { rec.release() }
            null
        }
    }

    private companion object {
        const val SILENT_TIMEOUT_MS = 3000L
    }
}
