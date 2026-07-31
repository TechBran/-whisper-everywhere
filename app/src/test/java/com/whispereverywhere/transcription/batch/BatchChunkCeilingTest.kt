package com.whispereverywhere.transcription.batch

import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.SttProvider
import com.whispereverywhere.transcription.cloud.SttResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-provider batch chunk ceiling is READ from each adapter's maxRequestBytes (which already
 * bakes in that provider's request shape, incl. Gemini's base64 inflation), then clamped by the
 * 44-byte WAV header and the 20 MB memory bound. Batch never re-derives base64 math.
 */
class BatchChunkCeilingTest {

    /** A minimal SttProvider that only carries a maxRequestBytes — the sole input to forProvider. */
    private class FakeProvider(
        override val maxRequestBytes: Long,
        override val id: ProviderId = ProviderId.OPENAI,
    ) : SttProvider {
        override suspend fun transcribe(pcm: ByteArray, language: String?): SttResult =
            SttResult.Text("")
    }

    private val openai = FakeProvider(25L * 1024 * 1024)               // OpenAI 25 MB
    private val gemini = FakeProvider(14L * 1024 * 1024)               // Gemini 14 MB (base64-safe raw)
    private val elevenlabs = FakeProvider(5L * 1024 * 1024 * 1024)     // ElevenLabs 5 GB
    private val soniox = FakeProvider(25L * 1024 * 1024)               // Soniox 25 MB

    @Test fun openai_and_soniox_cap_at_the_20mb_memory_bound() {
        assertEquals(20 * 1024 * 1024, BatchChunkCeiling.forProvider(openai))
        assertEquals(20 * 1024 * 1024, BatchChunkCeiling.forProvider(soniox))
    }

    @Test fun gemini_is_its_base64_safe_raw_cap_minus_the_header() {
        // Gemini's adapter already pins 14 MB raw for base64 inflation; the ceiling is that minus 44.
        assertEquals(14 * 1024 * 1024 - BatchChunkCeiling.WAV_HEADER_BYTES, BatchChunkCeiling.forProvider(gemini))
    }

    @Test fun elevenlabs_5gb_is_capped_to_the_memory_bound_not_5gb() {
        assertEquals(20 * 1024 * 1024, BatchChunkCeiling.forProvider(elevenlabs))
    }

    @Test fun every_ceiling_is_even_and_within_the_memory_bound() {
        for (p in listOf(openai, gemini, elevenlabs, soniox)) {
            val c = BatchChunkCeiling.forProvider(p)
            assertEquals("even so a PCM16 sample is never sheared", 0, c % 2)
            assertTrue("within the memory bound", c <= BatchChunkCeiling.MEMORY_BOUND_BYTES)
            assertTrue("positive", c >= 2)
        }
    }

    @Test fun ceiling_plus_header_never_trips_the_adapters_own_guard() {
        // forProvider(p) + 44 must be <= p.maxRequestBytes, so a full chunk + WAV header never hits
        // the adapter's pcm.size+44 > maxRequestBytes BadSegment guard (which would silently fall local).
        for (p in listOf(openai, gemini, elevenlabs, soniox)) {
            val c = BatchChunkCeiling.forProvider(p).toLong()
            assertTrue(
                "chunk+header (${c + 44}) must fit maxRequestBytes (${p.maxRequestBytes})",
                c + BatchChunkCeiling.WAV_HEADER_BYTES <= p.maxRequestBytes,
            )
        }
    }
}
