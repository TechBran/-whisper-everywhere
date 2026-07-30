package com.whispereverywhere.recording

/**
 * Interleaved multi-channel PCM16 -> mono, by per-frame average. Averaged in Int space so
 * full-scale inputs cannot overflow Short arithmetic.
 */
object Downmix {
    fun toMono(input: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return input
        val frames = input.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += input[f * channels + c].toInt()
            out[f] = (acc / channels).toShort()
        }
        return out
    }
}

/**
 * Mono PCM16 at any source rate -> 16 kHz, by linear interpolation.
 *
 * Speech-adequate on purpose: whisper VAD-trims and mel-bins its input, so a windowed-sinc
 * resampler buys nothing audible here and costs a dependency. Linear interpolation reproduces
 * lines exactly (pinned by test) and is O(n).
 */
object Resampler {
    const val TARGET_RATE = 16_000

    fun to16k(input: ShortArray, srcRate: Int): ShortArray {
        require(srcRate > 0) { "srcRate must be positive" }
        if (srcRate == TARGET_RATE || input.isEmpty()) return input
        val outLen = ((input.size.toLong() * TARGET_RATE) / srcRate).toInt()
        val out = ShortArray(outLen)
        val step = srcRate.toDouble() / TARGET_RATE
        for (k in 0 until outLen) {
            val pos = k * step
            val i = pos.toInt()
            val frac = pos - i
            val a = input[i].toInt()
            val b = input[minOf(i + 1, input.size - 1)].toInt()
            out[k] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }

    /**
     * Mono PCM16 16 kHz -> 24 kHz, by the same linear interpolation as [to16k] run the other way.
     *
     * The single implementation shared by the ElevenLabs mp3 bridge (which decodes to 16 kHz then
     * upsamples for playback) and the live transcription engine (which upsamples the 16 kHz capture
     * to the 24 kHz the Realtime API expects). Both had a byte-identical private copy before this
     * lift; keeping one body means the cross-impl golden ([0,66,133,200,266,300]) cannot silently
     * diverge. Output length is exactly 3/2 the input; endpoints are preserved.
     */
    fun upsample16kTo24k(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input
        val src = 16_000
        val dst = 24_000
        val outLen = ((input.size.toLong() * dst) / src).toInt()
        val out = ShortArray(outLen)
        val step = src.toDouble() / dst
        for (k in 0 until outLen) {
            val pos = k * step
            val i = pos.toInt()
            val frac = pos - i
            val a = input[i].toInt()
            val b = input[minOf(i + 1, input.size - 1)].toInt()
            out[k] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }
}
