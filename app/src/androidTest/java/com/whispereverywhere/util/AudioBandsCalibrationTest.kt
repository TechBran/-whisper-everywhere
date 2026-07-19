package com.whispereverywhere.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AGC calibration measurement (user request 2026-07-18): plays the bundled jfk.wav through
 * THIS device's speaker while recording through THIS device's mic — a real acoustic loop of
 * real speech on the real hardware — and logs every 32 ms chunk's per-band raw energy, AGC
 * reference, and normalized output. The WE-CAL logcat lines are the calibration dataset:
 * where speech actually sits per band on this phone, and where the reference settles.
 *
 * Run with media volume UP (the driver script sets it via adb).
 */
@RunWith(AndroidJUnit4::class)
class AudioBandsCalibrationTest {

    @Test
    fun measure_agc_curve_over_acoustic_speech() {
        val inst = InstrumentationRegistry.getInstrumentation()

        // Decode jfk.wav (16 kHz mono PCM16; locate the data chunk).
        val bytes = inst.context.assets.open("jfk.wav").use { it.readBytes() }
        var i = 12
        var dataOff = 44
        var dataLen = bytes.size - 44
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val sz = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") { dataOff = i + 8; dataLen = sz; break }
            i += 8 + sz + (sz and 1)
        }

        AudioBands.resetForCalibration()

        // Speaker: play the clip at 16 kHz.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(dataLen)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(bytes, dataOff, dataLen)

        // Mic: 32 ms chunks (512 samples), same shape the app's recorder feeds AudioBands.
        val minBuf = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        @Suppress("MissingPermission")
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4,
        )
        assertTrue("mic init failed", rec.state == AudioRecord.STATE_INITIALIZED)

        val raw = FloatArray(4)
        val chunk = ByteArray(1024)
        val t0 = System.currentTimeMillis()

        rec.startRecording()
        track.play()
        try {
            while (System.currentTimeMillis() - t0 < 13_000) {
                var off = 0
                while (off < chunk.size) {
                    val n = rec.read(chunk, off, chunk.size - off)
                    if (n <= 0) break
                    off += n
                }
                val out = AudioBands.analyze(chunk, off, raw)
                // RMS amp for the noise-gate context the app applies (amp > 350).
                var sum = 0.0
                for (s in 0 until off / 2) {
                    val lo = chunk[2 * s].toInt() and 0xFF
                    val hi = chunk[2 * s + 1].toInt()
                    val v = (hi shl 8) or lo
                    sum += (v * v).toDouble()
                }
                val amp = kotlin.math.sqrt(sum / (off / 2).coerceAtLeast(1)).toInt()
                android.util.Log.i(
                    "WE-CAL",
                    "t=%d amp=%d raw=%.4f,%.4f,%.4f,%.4f out=%.2f,%.2f,%.2f,%.2f".format(
                        System.currentTimeMillis() - t0, amp,
                        raw[0], raw[1], raw[2], raw[3],
                        out[0], out[1], out[2], out[3],
                    ),
                )
            }
        } finally {
            runCatching { rec.stop(); rec.release() }
            runCatching { track.stop(); track.release() }
        }
        assertTrue(true)
    }
}
