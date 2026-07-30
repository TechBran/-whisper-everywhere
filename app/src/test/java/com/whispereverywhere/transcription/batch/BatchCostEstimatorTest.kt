package com.whispereverywhere.transcription.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.5's "never a surprise charge" as pinned math. Estimates derive from the recording's
 * byteLength at the PCM16/16 kHz rate (32,000 bytes/s) and the published gpt-transcribe batch
 * price ($0.0045/min) — an estimate shown to the user, never a promise.
 */
class BatchCostEstimatorTest {

    private fun bytesForMinutes(min: Double): Long =
        (min * 60 * BatchCostEstimator.BYTES_PER_SECOND).toLong()

    @Test fun minutes_math_matches_the_pcm_rate() {
        assertEquals(1.0, BatchCostEstimator.minutes(bytesForMinutes(1.0)), 1e-9)
    }

    @Test fun estimated_cents_uses_the_published_batch_price() {
        // 10 minutes at $0.0045/min = 4.5 cents.
        assertEquals(4.5, BatchCostEstimator.estimatedCents(bytesForMinutes(10.0)), 1e-6)
    }

    @Test fun a_five_minute_clip_needs_no_confirmation() {
        assertFalse(BatchCostEstimator.needsConfirmation(bytesForMinutes(5.0)))
    }

    @Test fun a_ten_minute_clip_needs_confirmation() {
        // The minutes threshold binds first with today's price (10¢ ≈ 22 min); both are OR-ed so a
        // future price rise cannot silently widen the unconfirmed window.
        assertTrue(BatchCostEstimator.needsConfirmation(bytesForMinutes(10.0)))
    }

    @Test fun zero_bytes_is_free_and_unconfirmed() {
        assertEquals(0.0, BatchCostEstimator.estimatedCents(0L), 0.0)
        assertFalse(BatchCostEstimator.needsConfirmation(0L))
    }

    @Test fun duration_preflight_agrees_with_the_byte_math() {
        // The UI estimates from MediaMetadataRetriever duration BEFORE any decode exists; the
        // service re-checks on decoded bytes. Both must land on the same answer for clean audio.
        val tenMinutesMs = 10L * 60 * 1000
        assertEquals(bytesForMinutes(10.0), BatchCostEstimator.bytesForDuration(tenMinutesMs))
        assertTrue(BatchCostEstimator.needsConfirmation(BatchCostEstimator.bytesForDuration(tenMinutesMs)))
    }
}
