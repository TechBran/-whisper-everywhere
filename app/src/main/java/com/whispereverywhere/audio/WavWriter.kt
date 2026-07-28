package com.whispereverywhere.audio

/**
 * Wraps raw PCM16 in a canonical 44-byte WAV container.
 *
 * Required because OpenAI's transcription endpoint REJECTS raw PCM — it accepts containers only
 * (mp3, mp4, mpeg, mpga, m4a, wav, webm) and infers the format from the multipart FILENAME, so the
 * part must additionally be named "audio.wav".
 *
 * Pure and Android-free. Every field is little-endian; the RIFF size counts everything after the
 * first 8 bytes, which is the classic place to get this wrong.
 */
object WavWriter {

    private const val HEADER_BYTES = 44
    private const val BITS_PER_SAMPLE = 16
    private const val PCM_FORMAT: Short = 1

    fun wrap(pcm: ByteArray, sampleRate: Int = 16_000, channels: Int = 1): ByteArray {
        val bytesPerSample = BITS_PER_SAMPLE / 8
        val byteRate = sampleRate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample
        val out = ByteArray(HEADER_BYTES + pcm.size)

        fun ascii(off: Int, s: String) {
            for (i in s.indices) out[off + i] = s[i].code.toByte()
        }
        fun le32(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v ushr 8) and 0xFF).toByte()
            out[off + 2] = ((v ushr 16) and 0xFF).toByte()
            out[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun le16(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        ascii(0, "RIFF")
        le32(4, 36 + pcm.size)          // everything after these first 8 bytes
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)                    // PCM fmt chunk size
        le16(20, PCM_FORMAT.toInt())
        le16(22, channels)
        le32(24, sampleRate)
        le32(28, byteRate)
        le16(32, blockAlign)
        le16(34, BITS_PER_SAMPLE)
        ascii(36, "data")
        le32(40, pcm.size)
        pcm.copyInto(out, HEADER_BYTES)
        return out
    }
}
