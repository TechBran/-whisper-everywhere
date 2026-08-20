package com.whispereverywhere.transcription

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Test
    fun dataChunkIsFoundAfterAnIntermediateChunk() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertArrayEquals(payload, CanaryAudio.dataChunk(wav(payload)))
    }

    @Test
    fun aFileWithNoDataChunkYieldsNothing() {
        assertEquals(0, CanaryAudio.dataChunk("RIFF____WAVE".toByteArray()).size)
    }
}
