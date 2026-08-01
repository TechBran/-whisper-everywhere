package com.whispereverywhere.data.local

import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.batch.BatchCostEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCostMathTest {

    @Test fun batch_seconds_price_at_the_batch_estimators_pinned_rates() {
        // 10 minutes of OpenAI batch at 0.60 ¢/min = 6 ¢. Single source of batch truth: the same
        // BatchCostEstimator the cost-confirm dialog uses.
        assertEquals(6.0, CloudCostMath.cents(ProviderId.OPENAI, live = false, seconds = 600), 1e-9)
        assertEquals(
            BatchCostEstimator.centsPerMinute(ProviderId.SONIOX),
            CloudCostMath.cents(ProviderId.SONIOX, live = false, seconds = 60),
            1e-9,
        )
    }

    @Test fun live_seconds_price_at_the_selector_rows_pinned_rates() {
        // The same rates liveModeLabel shows: OpenAI $0.0045/min, ElevenLabs $0.007/min,
        // Soniox $0.002/min — as cents.
        assertEquals(0.45, CloudCostMath.cents(ProviderId.OPENAI, live = true, seconds = 60), 1e-9)
        assertEquals(0.70, CloudCostMath.cents(ProviderId.ELEVENLABS, live = true, seconds = 60), 1e-9)
        assertEquals(0.20, CloudCostMath.cents(ProviderId.SONIOX, live = true, seconds = 60), 1e-9)
    }

    @Test fun an_all_on_device_month_shows_no_footer_at_all() {
        // The clean panel stays clean: a $0 line would just be noise for local-only users.
        assertNull(CloudCostMath.monthCostFooter(0.0))
    }

    @Test fun the_footer_says_estimate_and_never_reads_as_a_bill() {
        val line = CloudCostMath.monthCostFooter(42.0)!!
        assertEquals(
            "about \$0.42 in cloud transcription this month — our estimate, not your provider's bill",
            line,
        )
        // The honesty words are load-bearing: this number is computed from our own measured
        // seconds and pinned prices, never fetched from any provider.
        assertTrue(line.contains("estimate"))
        assertTrue(line.contains("not your provider's bill"))
    }

    @Test fun sub_cent_months_round_to_a_floor_phrase_not_to_zero_dollars() {
        // 20 s of Soniox live is a fraction of a cent; "$0.00" would read as free, which it isn't.
        val line = CloudCostMath.monthCostFooter(0.07)!!
        assertTrue(line, line.startsWith("less than \$0.01"))
    }
}
