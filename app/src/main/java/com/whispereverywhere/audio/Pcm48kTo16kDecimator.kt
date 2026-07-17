package com.whispereverywhere.audio

import java.io.ByteArrayOutputStream

/**
 * 3:1 decimator: 48 kHz mono PCM16 -> 16 kHz mono PCM16 by averaging sample triplets
 * (the average — integer division, truncating toward zero — is a crude but sufficient
 * anti-alias for speech-band transcription).
 * Stateful: a trailing partial triplet is carried into the next call. NOT thread-safe —
 * call from a single capture thread only.
 */
class Pcm48kTo16kDecimator {

    private val carry = ByteArrayOutputStream()

    fun process(input: ByteArray, len: Int): ByteArray {
        require(len in 0..input.size) { "len=$len out of bounds for input of ${input.size} bytes" }
        carry.write(input, 0, len)
        val data = carry.toByteArray()
        val groups = (data.size / 2) / 3
        val out = ByteArray(groups * 2)
        for (g in 0 until groups) {
            var acc = 0
            for (k in 0 until 3) {
                val i = (g * 3 + k) * 2
                val sample = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort()
                acc += sample.toInt()
            }
            val avg = acc / 3
            out[g * 2] = (avg and 0xFF).toByte()
            out[g * 2 + 1] = ((avg shr 8) and 0xFF).toByte()
        }
        carry.reset()
        val consumed = groups * 3 * 2
        if (consumed < data.size) carry.write(data, consumed, data.size - consumed)
        return out
    }
}
