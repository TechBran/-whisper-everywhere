package com.whispereverywhere.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Streams 16 kHz mono PCM16 from the mic. For each buffer read it invokes
 * [onChunk] (off the main thread) and updates [amplitude] for the waveform.
 * No file is written — audio goes straight to the transcription engine.
 */
class StreamingAudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        .coerceAtLeast(4096)

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var recording = false

    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** @param onChunk receives a freshly read PCM16 chunk (a copy of exactly [size] bytes). */
    fun start(onChunk: (ByteArray) -> Unit): Result<Unit> {
        if (!hasPermission()) return Result.failure(SecurityException("Microphone permission not granted"))
        if (recording) return Result.failure(IllegalStateException("Already recording"))

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Result.failure(IllegalStateException("Failed to initialize AudioRecord"))
        }
        audioRecord = record
        recording = true
        record.startRecording()

        thread = Thread {
            val buffer = ByteArray(bufferSize)
            while (recording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    _amplitude.value = AudioMath.amplitude(buffer, read)
                    onChunk(buffer.copyOf(read))
                }
            }
        }.also { it.start() }

        return Result.success(Unit)
    }

    fun stop() {
        if (!recording) return
        recording = false
        _amplitude.value = 0
        try {
            thread?.join(2000)
            audioRecord?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioRecord?.release()
            audioRecord = null
            thread = null
        }
    }
}
