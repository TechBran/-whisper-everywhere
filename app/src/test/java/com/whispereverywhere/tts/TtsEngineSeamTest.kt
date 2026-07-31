package com.whispereverywhere.tts

import com.whispereverywhere.tts.cloud.TtsError
import com.whispereverywhere.tts.cloud.TtsResult
import com.whispereverywhere.transcription.cloud.FatalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The producer-loop seam decision, tested PURE. [planUnitOutcome] carries the entire one-way valve:
 * cloud is used only while a provider is selected AND no fatal has latched; a cloud failure re-runs
 * THAT unit locally and only a Fatal latches the rest of the read to local. No AudioTrack, no
 * sherpa, no coroutine — exactly what makes this the pin the plan asks for.
 */
class TtsEngineSeamTest {

    @Test fun null_provider_always_local() {
        // The shipped default: no cloud provider => the exact prior on-device path, every unit.
        assertEquals(UnitAction.Local, planUnitOutcome(hasCloud = false, latched = false, synthResult = null))
        assertEquals(UnitAction.Local, planUnitOutcome(hasCloud = false, latched = true, synthResult = null))
    }

    @Test fun cloud_success_stays_cloud_next_unit() {
        // A delivered unit keeps cloud eligible: the post-synth Done stays Cloud, and because it did
        // not latch, the NEXT unit's pre-synth decision is Cloud again.
        assertEquals(UnitAction.Cloud, planUnitOutcome(true, latched = false, synthResult = TtsResult.Done))
        assertEquals(UnitAction.Cloud, planUnitOutcome(true, latched = false, synthResult = null))
    }

    @Test fun transient_failure_falls_this_unit_local_but_does_not_latch() {
        val outcome = planUnitOutcome(true, latched = false, synthResult = TtsResult.Failed(TtsError.Transient(null)))
        assertEquals(UnitAction.LocalFallback(latchNow = false), outcome)
        // BadUnit and Offline are non-fatal too: fall this unit back, do not latch the read.
        assertEquals(
            UnitAction.LocalFallback(latchNow = false),
            planUnitOutcome(true, false, TtsResult.Failed(TtsError.BadUnit)),
        )
        assertEquals(
            UnitAction.LocalFallback(latchNow = false),
            planUnitOutcome(true, false, TtsResult.Failed(TtsError.Offline)),
        )
    }

    @Test fun fatal_failure_falls_local_and_latches_rest_of_read() {
        val outcome = planUnitOutcome(
            true, latched = false,
            synthResult = TtsResult.Failed(TtsError.Fatal(FatalKind.INVALID_KEY, "Key rejected")),
        )
        assertEquals(UnitAction.LocalFallback(latchNow = true), outcome)
    }

    @Test fun after_latch_all_remaining_units_are_local() {
        // Once a fatal has latched, every remaining unit's pre-synth decision is Local — no more
        // cloud attempts for the rest of THIS read, even though a provider is still configured.
        assertEquals(UnitAction.Local, planUnitOutcome(hasCloud = true, latched = true, synthResult = null))
    }

    @Test fun cancel_stops_the_loop() {
        // onPcm returned false (stop() landed): the cloud attempt reports Cancelled and the loop
        // must break rather than fall back or keep reading.
        assertEquals(UnitAction.Cancel, planUnitOutcome(true, latched = false, synthResult = TtsResult.Cancelled))
    }

    // ---- shouldSoftLatchCloud: the circuit breaker over the silent per-unit re-bill. A persistent
    // NON-FATAL condition (Gemini 200-no-audio refusal, plain 429) is not a Fatal, so planUnitOutcome
    // never latches it — without a breaker every remaining clause re-POSTs (and Gemini bills each).
    // After N consecutive non-fatal fall-backs cloud soft-latches to local for the rest of the read. ----

    @Test fun one_isolated_soft_failure_does_not_latch() {
        // A single hiccup must be tolerated — the next unit is still allowed to try cloud.
        assertFalse(shouldSoftLatchCloud(0))
        assertFalse(shouldSoftLatchCloud(1))
    }

    @Test fun the_threshold_of_consecutive_soft_failures_trips_the_breaker() {
        assertTrue(shouldSoftLatchCloud(CLOUD_SOFT_LATCH_THRESHOLD))
        assertTrue(shouldSoftLatchCloud(CLOUD_SOFT_LATCH_THRESHOLD + 5))
    }

    @Test fun the_threshold_is_small_enough_to_stop_an_article_wide_bleed() {
        // 2..3 per the finding: low enough that a per-unit bleed cannot run a whole article, high
        // enough to tolerate one isolated failure. Pin the band so it cannot silently drift large.
        assertTrue(CLOUD_SOFT_LATCH_THRESHOLD in 2..3)
    }

    // ---- cloudTrackRateMatches: TtsProvider.sampleRate is consumed here (no lying interface). The
    // cloud PCM drops into the SAME bank the on-device voice fills and plays at the AudioTrack rate,
    // so a provider whose rate != the track rate would play at the wrong pitch and its read must
    // fall to the local voice instead. ----

    @Test fun a_provider_at_the_track_rate_is_accepted() {
        assertTrue(cloudTrackRateMatches(providerRate = 24_000, trackRate = 24_000))
    }

    @Test fun a_provider_at_the_wrong_rate_is_rejected_so_the_read_falls_local() {
        // A 16 kHz provider against a 24 kHz Kokoro track would play ~1.5x too fast: disqualify it.
        assertFalse(cloudTrackRateMatches(providerRate = 16_000, trackRate = 24_000))
        assertFalse(cloudTrackRateMatches(providerRate = 48_000, trackRate = 24_000))
    }

    // ---- bank caps: the cloud append path now honors the SAME RETAIN_CAP/AHEAD_CAP ceiling the
    // sherpa callback always did (finding #4). Both producers route through these predicates, so a
    // slow-draining cloud read can no longer grow the bank without bound. ----

    @Test fun retention_cap_blocks_an_append_that_would_cross_the_ceiling() {
        val cap = 100L
        assertFalse(bankRetentionExceeded(available = 90, incoming = 10, retainCap = cap)) // lands exactly on cap
        assertTrue(bankRetentionExceeded(available = 91, incoming = 10, retainCap = cap))  // one over
        assertFalse(bankRetentionExceeded(available = 0, incoming = 0, retainCap = cap))
    }

    @Test fun backpressure_trips_only_once_the_lead_exceeds_the_ahead_cap() {
        val cap = 50L
        assertFalse(bankTooFarAhead(available = 150, played = 100, aheadCap = cap)) // lead == cap, not over
        assertTrue(bankTooFarAhead(available = 151, played = 100, aheadCap = cap))  // lead one over cap
        assertFalse(bankTooFarAhead(available = 100, played = 100, aheadCap = cap)) // caught up
    }

    @Test fun all_three_cloud_providers_declare_the_24k_bank_rate() {
        // The contract the seam enforces: every shipped TTS adapter emits 24 kHz, so none is ever
        // disqualified against a 24 kHz track. Guards a future adapter that forgets the contract.
        val t = com.whispereverywhere.net.FakeHttpTransport()
        listOf(
            com.whispereverywhere.tts.cloud.OpenAiTts(t, "k").sampleRate,
            com.whispereverywhere.tts.cloud.GeminiTts(t, "k").sampleRate,
            com.whispereverywhere.tts.cloud.ElevenLabsTts(t, "k", context = null).sampleRate,
        ).forEach { assertTrue(cloudTrackRateMatches(it, trackRate = 24_000)) }
    }
}
