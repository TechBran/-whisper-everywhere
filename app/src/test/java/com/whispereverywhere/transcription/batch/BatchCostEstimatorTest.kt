package com.whispereverywhere.transcription.batch

import com.whispereverywhere.provider.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.5's "never a surprise charge" as pinned math, now PER PROVIDER. Estimates derive from the
 * recording's byteLength at the PCM16/16 kHz rate (32,000 bytes/s). An UNKNOWN provider price
 * (Gemini audio) does not block the provider — its shown ¢/min is only an illustrative floor
 * (the most-expensive-KNOWN rate) AND any real job on it always forces the confirm gate, because
 * an unpublished price is not bounded by the known rates. null provider = on-device = free.
 */
class BatchCostEstimatorTest {

    private fun bytesForMinutes(min: Double): Long =
        (min * 60 * BatchCostEstimator.BYTES_PER_SECOND).toLong()

    @Test fun minutes_math_matches_the_pcm_rate() {
        assertEquals(1.0, BatchCostEstimator.minutes(bytesForMinutes(1.0)), 1e-9)
    }

    @Test fun each_known_provider_rate_is_pinned() {
        assertEquals(0.60, BatchCostEstimator.OPENAI_CENTS_PER_MIN, 0.0)
        assertEquals(0.37, BatchCostEstimator.ELEVENLABS_CENTS_PER_MIN, 0.0)
        assertEquals(0.17, BatchCostEstimator.SONIOX_CENTS_PER_MIN, 0.0)
        assertEquals(
            BatchCostEstimator.OPENAI_CENTS_PER_MIN,
            BatchCostEstimator.MOST_EXPENSIVE_KNOWN_CENTS_PER_MIN,
            0.0,
        )
    }

    @Test fun centsPerMinute_maps_each_provider_to_its_rate() {
        assertEquals(0.60, BatchCostEstimator.centsPerMinute(ProviderId.OPENAI), 0.0)
        assertEquals(0.37, BatchCostEstimator.centsPerMinute(ProviderId.ELEVENLABS), 0.0)
        assertEquals(0.17, BatchCostEstimator.centsPerMinute(ProviderId.SONIOX), 0.0)
    }

    @Test fun gemini_unknown_price_is_the_most_expensive_known_rate() {
        // UNKNOWN audio-input price does NOT block Gemini; its shown ¢/min is the dearest known rate
        // (== OpenAI) as an illustrative floor only.
        assertEquals(
            BatchCostEstimator.centsPerMinute(ProviderId.OPENAI),
            BatchCostEstimator.centsPerMinute(ProviderId.GEMINI),
            0.0,
        )
        assertEquals(
            BatchCostEstimator.MOST_EXPENSIVE_KNOWN_CENTS_PER_MIN,
            BatchCostEstimator.centsPerMinute(ProviderId.GEMINI),
            0.0,
        )
    }

    @Test fun price_is_known_for_every_provider_except_gemini() {
        // Finding #2: only Gemini's audio price is unpublished. isPriceKnown drives both the UI's
        // "price not published" copy and the force-confirm below.
        assertTrue(BatchCostEstimator.isPriceKnown(ProviderId.OPENAI))
        assertTrue(BatchCostEstimator.isPriceKnown(ProviderId.ELEVENLABS))
        assertTrue(BatchCostEstimator.isPriceKnown(ProviderId.SONIOX))
        assertTrue("on-device is free — a known price of 0", BatchCostEstimator.isPriceKnown(null))
        assertFalse("Gemini audio input price is unpublished", BatchCostEstimator.isPriceKnown(ProviderId.GEMINI))
    }

    @Test fun a_gemini_job_always_confirms_even_far_under_both_thresholds() {
        // Finding #2: an unpublished price is not bounded by the dearest KNOWN rate, so the confirm
        // gate must fire for ANY real Gemini job — even a tiny one an equivalent OpenAI job skips —
        // so a possibly-too-low estimate can never silently exceed the threshold.
        val tiny = bytesForMinutes(1.0) // 1 min: under the 10-min and 10¢ bounds for a known rate
        assertFalse("a known-price 1-min job skips confirm", BatchCostEstimator.needsConfirmation(tiny, ProviderId.OPENAI))
        assertTrue("a Gemini 1-min job still confirms", BatchCostEstimator.needsConfirmation(tiny, ProviderId.GEMINI))
        // Degenerate zero-byte job is still free/unconfirmed even for Gemini (no audio -> no charge).
        assertFalse(BatchCostEstimator.needsConfirmation(0L, ProviderId.GEMINI))
    }

    @Test fun a_null_provider_is_free() {
        assertEquals(0.0, BatchCostEstimator.centsPerMinute(null), 0.0)
        assertEquals(0.0, BatchCostEstimator.estimatedCents(bytesForMinutes(30.0), null), 0.0)
    }

    @Test fun estimated_cents_uses_the_providers_rate() {
        // 10 minutes at OpenAI's 0.60 ¢/min = 6.0 cents.
        assertEquals(6.0, BatchCostEstimator.estimatedCents(bytesForMinutes(10.0), ProviderId.OPENAI), 1e-6)
        // 10 minutes at ElevenLabs' 0.37 ¢/min = 3.7 cents.
        assertEquals(3.7, BatchCostEstimator.estimatedCents(bytesForMinutes(10.0), ProviderId.ELEVENLABS), 1e-6)
    }

    @Test fun the_minutes_bound_binds_first_at_todays_rates() {
        // 10¢ at 0.37 ¢/min ≈ 27 min, but the 10-minute bound binds earlier: a sub-10-min ElevenLabs
        // or Soniox job never trips the cents threshold.
        assertFalse(BatchCostEstimator.needsConfirmation(bytesForMinutes(9.0), ProviderId.ELEVENLABS))
        assertFalse(BatchCostEstimator.needsConfirmation(bytesForMinutes(9.0), ProviderId.SONIOX))
        // Cheap providers still stay under the cents bound right up to the minutes bound.
        assertTrue(BatchCostEstimator.estimatedCents(bytesForMinutes(9.9), ProviderId.ELEVENLABS) < BatchCostEstimator.CONFIRM_CENTS)
    }

    @Test fun a_job_over_ten_minutes_needs_confirmation_for_every_provider_including_local() {
        for (p in listOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS, ProviderId.SONIOX, null)) {
            assertTrue(
                "10-min job must need confirmation for $p (minutes bound)",
                BatchCostEstimator.needsConfirmation(bytesForMinutes(10.0), p),
            )
        }
    }

    @Test fun a_five_minute_clip_needs_no_confirmation_for_any_known_price_provider() {
        // Gemini is EXCLUDED: its unpublished price force-confirms even a 5-min job (see
        // a_gemini_job_always_confirms_even_far_under_both_thresholds).
        for (p in listOf(ProviderId.OPENAI, ProviderId.ELEVENLABS, ProviderId.SONIOX, null)) {
            assertFalse(BatchCostEstimator.needsConfirmation(bytesForMinutes(5.0), p))
        }
        assertTrue("Gemini's unpublished price confirms even at 5 min",
            BatchCostEstimator.needsConfirmation(bytesForMinutes(5.0), ProviderId.GEMINI))
    }

    @Test fun zero_bytes_is_free_and_unconfirmed() {
        assertEquals(0.0, BatchCostEstimator.estimatedCents(0L, ProviderId.OPENAI), 0.0)
        assertFalse(BatchCostEstimator.needsConfirmation(0L, ProviderId.OPENAI))
    }

    @Test fun duration_preflight_agrees_with_the_byte_math() {
        val tenMinutesMs = 10L * 60 * 1000
        assertEquals(bytesForMinutes(10.0), BatchCostEstimator.bytesForDuration(tenMinutesMs))
        assertTrue(BatchCostEstimator.needsConfirmation(BatchCostEstimator.bytesForDuration(tenMinutesMs), ProviderId.OPENAI))
    }
}
