package com.whispereverywhere.tts

import com.whispereverywhere.tts.cloud.TtsError
import com.whispereverywhere.tts.cloud.TtsResult
import com.whispereverywhere.transcription.cloud.FatalKind
import org.junit.Assert.assertEquals
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
}
