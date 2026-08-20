package com.whispereverywhere.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The WAV data-chunk walk is the only testable half of the canary loader (the asset read needs
 * a Context). Pinned because a wrong offset would feed whisper header bytes as audio and fail
 * the canary for a reason that has nothing to do with the GPU.
 */
class CanaryAudioTest {

    /** Minimal RIFF/WAVE with a LIST chunk before data, so a fixed 44-byte offset would break. */
    private fun wav(payload: ByteArray): ByteArray {
        val list = ByteArray(4) { 0x7F }
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        out.write("RIFF".toByteArray()); out.write(le(0)); out.write("WAVE".toByteArray())
        out.write("LIST".toByteArray()); out.write(le(list.size)); out.write(list)
        out.write("data".toByteArray()); out.write(le(payload.size)); out.write(payload)
        return out.toByteArray()
    }

    /**
     * Build a valid RIFF/WAVE with fmt chunk and data chunk. fmt encodes channels, sample rate,
     * and bits per sample so format validation can be tested.
     */
    private fun wavWithFormat(
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        payload: ByteArray
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

        // RIFF header
        out.write("RIFF".toByteArray())
        out.write(le(0)) // placeholder for file size
        out.write("WAVE".toByteArray())

        // fmt chunk: 16 bytes of minimal format
        out.write("fmt ".toByteArray())
        out.write(le(16)) // fmt chunk size
        out.write(le16(1)) // PCM format
        out.write(le16(channels))
        out.write(le(sampleRate))
        out.write(le(sampleRate * channels * bitsPerSample / 8)) // byte rate
        out.write(le16(channels * bitsPerSample / 8)) // block align
        out.write(le16(bitsPerSample))

        // data chunk
        out.write("data".toByteArray())
        out.write(le(payload.size))
        out.write(payload)

        return out.toByteArray()
    }

    @Test
    fun dataChunkIsFoundAfterAnIntermediateChunk() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(payload, CanaryAudio.dataChunk(wav(payload)))
    }

    @Test
    fun aFileWithNoDataChunkYieldsNothing() {
        assertEquals(0, CanaryAudio.dataChunk("RIFF____WAVE".toByteArray()).size)
    }

    @Test
    fun anOverDeclaredDataChunkSizeIsClampedNotThrown() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        out.write("RIFF".toByteArray()); out.write(le(0)); out.write("WAVE".toByteArray())
        out.write("LIST".toByteArray()); out.write(le(4)); out.write(ByteArray(4))
        out.write("data".toByteArray()); out.write(le(1000)) // declares 1000 bytes but only 6 follow
        out.write(payload)
        val result = CanaryAudio.dataChunk(out.toByteArray())
        // Should return the truncated payload, not throw and not pad
        assertArrayEquals(payload, result)
    }

    @Test
    fun anOddSizedChunkAdvancesPastItsPadByte() {
        val payload = byteArrayOf(7, 8, 9)
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        // LIST chunk with odd size (5 bytes) requires a 1-byte pad before next chunk
        out.write("RIFF".toByteArray()); out.write(le(0)); out.write("WAVE".toByteArray())
        out.write("LIST".toByteArray()); out.write(le(5)); out.write(ByteArray(5)); out.write(byteArrayOf(0)) // pad byte
        out.write("data".toByteArray()); out.write(le(payload.size)); out.write(payload)
        val result = CanaryAudio.dataChunk(out.toByteArray())
        assertArrayEquals(payload, result)
    }

    @Test
    fun chunksPresentButNoDataYieldsNothing() {
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        // Multiple non-data chunks, loop iterates but never finds data
        out.write("RIFF".toByteArray()); out.write(le(0)); out.write("WAVE".toByteArray())
        out.write("LIST".toByteArray()); out.write(le(4)); out.write(ByteArray(4))
        out.write("junk".toByteArray()); out.write(le(4)); out.write(ByteArray(4))
        out.write("fact".toByteArray()); out.write(le(4)); out.write(ByteArray(4))
        assertEquals(0, CanaryAudio.dataChunk(out.toByteArray()).size)
    }

    @Test
    fun aWrongFormatAssetYieldsNull() {
        val payload = byteArrayOf(1, 2, 3, 4)
        // Stereo instead of mono — formatIsValid should reject
        val stereo = wavWithFormat(2, 16000, 16, payload)
        assertEquals(false, CanaryAudio.formatIsValid(stereo))
        // 44100 Hz instead of 16000 — formatIsValid should reject
        val hires = wavWithFormat(1, 44100, 16, payload)
        assertEquals(false, CanaryAudio.formatIsValid(hires))
        // 24-bit instead of 16-bit — formatIsValid should reject
        val bits24 = wavWithFormat(1, 16000, 24, payload)
        assertEquals(false, CanaryAudio.formatIsValid(bits24))
        // Valid format should be accepted, and dataChunk should still extract data
        val valid = wavWithFormat(1, 16000, 16, payload)
        assertEquals(true, CanaryAudio.formatIsValid(valid))
        assertArrayEquals(payload, CanaryAudio.dataChunk(valid))
    }

    @Test
    fun aNegativeChunkSizeReturnsNothing() {
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        out.write("RIFF".toByteArray()); out.write(le(0)); out.write("WAVE".toByteArray())
        out.write("LIST".toByteArray()); out.write(le(-8)) // negative chunk size
        out.write("data".toByteArray()); out.write(le(4)); out.write(byteArrayOf(1, 2, 3, 4))
        assertEquals(0, CanaryAudio.dataChunk(out.toByteArray()).size)
    }
}
