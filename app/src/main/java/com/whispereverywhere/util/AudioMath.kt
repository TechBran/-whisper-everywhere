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
}
