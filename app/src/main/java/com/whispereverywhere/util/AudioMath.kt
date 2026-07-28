package com.whispereverywhere.util

import kotlin.math.min
import kotlin.math.sqrt

/** Pure helpers for PCM16 audio. No Android dependencies. */
object AudioMath {

    /**
     * Root-mean-square amplitude of the first [length] bytes of [buffer],
     * interpreted as 16-bit little-endian mono samples, scaled to 0..32767.
     */
    fun amplitude(buffer: ByteArray, length: Int): Int {
        val end = min(length, buffer.size)
        if (end < 2) return 0
        var sumSquares = 0.0
        var count = 0
        var i = 0
        while (i + 1 < end) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sumSquares += sample.toDouble() * sample.toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0
        val rms = sqrt(sumSquares / count)
        return rms.toInt().coerceIn(0, 32767)
    }

    /**
     * Converts little-endian 16-bit PCM [pcm] to float32 samples in [-1, 1]
     * (each sample / 32768f, clamped). A trailing odd byte (incomplete sample)
     * is ignored. This is the exact format whisper.cpp's `whisper_full()` expects.
     */
    fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        val sampleCount = pcm.size / 2
        val out = FloatArray(sampleCount)
        var i = 0
        while (i < sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt() // sign-extended for the high byte
            val sample = (hi shl 8) or lo   // signed 16-bit, little-endian
            out[i] = (sample / 32768f).coerceIn(-1f, 1f)
            i++
        }
        return out
    }

    /**
     * Largest absolute sample, normalised to 0f..1f. Mirrors the native peak-energy gate in
     * whisper_jni.cpp so the same "is there anything here at all" question can be asked in Kotlin
     * before an expensive or billable operation.
     *
     * Reads whole samples only — [android.media.AudioRecord] can return an odd byte count, and a
     * stride-2 loop that ignores that runs off the end. Uses [kotlin.math.abs] on an Int, not a
     * Short: the Short -32768 has no positive counterpart and abs() of it stays negative.
     */
    fun peak(pcm: ByteArray): Float {
        var max = 0
        var i = 0
        val end = pcm.size - 1
        while (i < end) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val a = kotlin.math.abs(s)
            if (a > max) max = a
            i += 2
        }
        return (max / 32768f).coerceIn(0f, 1f)
    }
}
