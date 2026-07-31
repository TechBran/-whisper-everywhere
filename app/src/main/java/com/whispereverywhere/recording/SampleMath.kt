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
    const val TTS_RATE = 24_000

    fun to16k(input: ShortArray, srcRate: Int): ShortArray {
        require(srcRate > 0) { "srcRate must be positive" }
        if (srcRate == TARGET_RATE || input.isEmpty()) return input
        return linear(input, srcRate, TARGET_RATE)
    }

    /**
     * Mono PCM16 16 kHz -> 24 kHz, by the same linear interpolation as [to16k] run the other way.
     *
     * The single implementation shared by the live transcription engine (which upsamples the 16 kHz
     * capture to the 24 kHz the Realtime API expects) and — historically — the ElevenLabs mp3 bridge.
     * Keeping one body means the cross-impl golden ([0,66,133,200,266,300]) cannot silently diverge.
     * Output length is exactly 3/2 the input; endpoints are preserved.
     */
    fun upsample16kTo24k(input: ShortArray): ShortArray = resampleTo24k(input, 16_000)

    /**
     * Mono PCM16 at ANY source rate -> 24 kHz, by the same linear interpolation as [to16k].
     *
     * Generalizes the old 16k->24k-only upsample so the ElevenLabs mp3 fallback can decode the mp3
     * at its NATIVE rate (e.g. 44_100 for mp3_44100_128) and resample straight to the 24 kHz bank —
     * instead of the old band-limited path that went through the shared 16 kHz STT decoder first and
     * threw away everything above ~8 kHz before upsampling. srcRate == 24_000 is a pass-through.
     */
    fun resampleTo24k(input: ShortArray, srcRate: Int): ShortArray {
        require(srcRate > 0) { "srcRate must be positive" }
        if (srcRate == TTS_RATE || input.isEmpty()) return input
        return linear(input, srcRate, TTS_RATE)
    }

    /** Linear-interpolation resample of mono PCM16 from [srcRate] to [dstRate]. Endpoints preserved,
     *  output length truncated to floor(size * dstRate / srcRate); O(n). */
    private fun linear(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        val outLen = ((input.size.toLong() * dstRate) / srcRate).toInt()
        val out = ShortArray(outLen)
        val step = srcRate.toDouble() / dstRate
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
