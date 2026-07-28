package com.whispereverywhere.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavWriterTest {

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun ascii(b: ByteArray, off: Int, len: Int) = String(b, off, len, Charsets.US_ASCII)

    @Test fun header_is_exactly_44_bytes_before_the_samples() {
        val pcm = ByteArray(100)
        assertEquals(144, WavWriter.wrap(pcm).size)
    }

    @Test fun riff_and_wave_magic_are_present() {
        val w = WavWriter.wrap(ByteArray(4))
        assertEquals("RIFF", ascii(w, 0, 4))
        assertEquals("WAVE", ascii(w, 8, 4))
        assertEquals("fmt ", ascii(w, 12, 4))
        assertEquals("data", ascii(w, 36, 4))
    }

    @Test fun riff_size_is_total_minus_eight() {
        // The RIFF chunk size counts everything AFTER the first 8 bytes. Getting this wrong is the
        // classic WAV bug: some decoders accept it, OpenAI's does not.
        val w = WavWriter.wrap(ByteArray(100))
        assertEquals(w.size - 8, le32(w, 4))
    }

    @Test fun fmt_chunk_declares_pcm16_mono_16k() {
        val w = WavWriter.wrap(ByteArray(2))
        assertEquals(16, le32(w, 16))        // fmt chunk size for PCM
        assertEquals(1, le16(w, 20))         // audioFormat 1 = PCM
        assertEquals(1, le16(w, 22))         // channels
        assertEquals(16_000, le32(w, 24))    // sample rate
        assertEquals(16, le16(w, 34))        // bits per sample
    }

    @Test fun byte_rate_and_block_align_are_derived_not_guessed() {
        // byteRate = rate * channels * bytesPerSample; blockAlign = channels * bytesPerSample.
        // A wrong byteRate makes a decoder compute the wrong duration.
        val w = WavWriter.wrap(ByteArray(2))
        assertEquals(16_000 * 1 * 2, le32(w, 28))
        assertEquals(1 * 2, le16(w, 32))
    }

    @Test fun data_size_equals_the_pcm_length() {
        val w = WavWriter.wrap(ByteArray(640))
        assertEquals(640, le32(w, 40))
    }

    @Test fun the_pcm_payload_is_copied_verbatim() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val w = WavWriter.wrap(pcm)
        assertArrayEquals(pcm, w.copyOfRange(44, 44 + pcm.size))
    }

    @Test fun empty_pcm_still_produces_a_valid_header() {
        // A zero-length segment should never reach the wire, but producing a malformed file here
        // would turn a guard failure into a confusing 400 from the provider.
        val w = WavWriter.wrap(ByteArray(0))
        assertEquals(44, w.size)
        assertEquals(0, le32(w, 40))
        assertEquals(36, le32(w, 4))
    }

    @Test fun a_different_sample_rate_is_honoured() {
        val w = WavWriter.wrap(ByteArray(2), sampleRate = 24_000)
        assertEquals(24_000, le32(w, 24))
        assertEquals(24_000 * 2, le32(w, 28))
    }
}
