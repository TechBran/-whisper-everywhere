package com.whispereverywhere.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * AudioRecorder using AudioRecord for direct PCM capture.
 * Records audio and saves as WAV format compatible with Whisper API.
 */
class AudioRecorder(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    private var outputFile: File? = null
    private var recordingStartTime: Long = 0L
    private var recordingDurationMs: Long = 0L

    // Audio configuration for Whisper API compatibility
    companion object {
        private const val SAMPLE_RATE = 16000 // 16kHz recommended for Whisper
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BITS_PER_SAMPLE = 16
        private const val CHANNELS = 1
    }

    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ).coerceAtLeast(4096)

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun startRecording(): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (!hasPermission()) {
                return@withContext Result.failure(SecurityException("Microphone permission not granted"))
            }

            if (isRecording) {
                return@withContext Result.failure(IllegalStateException("Already recording"))
            }

            // Create output file
            val cacheDir = context.cacheDir
            outputFile = File(cacheDir, "whisper_recording_${System.currentTimeMillis()}.wav")

            // Initialize AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext Result.failure(IllegalStateException("Failed to initialize AudioRecord"))
            }

            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            _state.value = RecordingState.Recording

            // Start recording in background thread
            val outputStream = ByteArrayOutputStream()
            audioRecord?.startRecording()

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)

                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)

                        // Calculate amplitude for visualization
                        var sum = 0
                        for (i in 0 until read step 2) {
                            if (i + 1 < read) {
                                val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                                sum += kotlin.math.abs(sample)
                            }
                        }
                        _amplitude.value = (sum / (read / 2)).coerceIn(0, 32767)
                    }
                }

                // Recording stopped, write WAV file
                recordingDurationMs = System.currentTimeMillis() - recordingStartTime
                val audioData = outputStream.toByteArray()
                writeWavFile(outputFile!!, audioData)

                outputStream.close()
            }

            recordingThread?.start()

            Result.success(outputFile!!)
        } catch (e: Exception) {
            _state.value = RecordingState.Error(e.message ?: "Recording failed")
            Result.failure(e)
        }
    }

    fun stopRecording(): Long {
        if (!isRecording) return 0L

        isRecording = false
        _amplitude.value = 0

        try {
            recordingThread?.join(2000) // Wait max 2 seconds
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordingThread = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        _state.value = RecordingState.Stopped(outputFile)
        return recordingDurationMs
    }

    fun getRecordingFile(): File? = outputFile

    fun getRecordingDurationSeconds(): Int {
        return (recordingDurationMs / 1000).toInt()
    }

    fun cleanup() {
        stopRecording()
        outputFile?.delete()
        outputFile = null
    }

    private fun writeWavFile(file: File, audioData: ByteArray) {
        val totalDataLen = audioData.size + 36
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

        FileOutputStream(file).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(intToByteArray(totalDataLen))
            fos.write("WAVE".toByteArray())

            // fmt subchunk
            fos.write("fmt ".toByteArray())
            fos.write(intToByteArray(16)) // Subchunk1Size for PCM
            fos.write(shortToByteArray(1)) // AudioFormat: PCM = 1
            fos.write(shortToByteArray(CHANNELS.toShort()))
            fos.write(intToByteArray(SAMPLE_RATE))
            fos.write(intToByteArray(byteRate))
            fos.write(shortToByteArray((CHANNELS * BITS_PER_SAMPLE / 8).toShort())) // BlockAlign
            fos.write(shortToByteArray(BITS_PER_SAMPLE.toShort()))

            // data subchunk
            fos.write("data".toByteArray())
            fos.write(intToByteArray(audioData.size))
            fos.write(audioData)
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }

    sealed class RecordingState {
        object Idle : RecordingState()
        object Recording : RecordingState()
        data class Stopped(val file: File?) : RecordingState()
        data class Error(val message: String) : RecordingState()
    }
}
