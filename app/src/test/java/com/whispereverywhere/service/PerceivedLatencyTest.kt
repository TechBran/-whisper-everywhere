package com.whispereverywhere.service

import com.whispereverywhere.audio.EndpointCut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * speech-end -> text-visible, the quantity the 3.7 mandate is actually about (owner acceptance:
 * pro ~1.3-1.8 s constant, multi ~2.8 s at the paced boundary; the headline is the VARIANCE, so
 * the stamp has to be per-segment, not an average).
 *
 * Deliberately keyed by seq rather than a FIFO pop: segments that resolve to silence release no
 * text at all, so a positional queue would drift one entry per silent segment and start
 * attributing one utterance's wait to the next one.
 *
 * The last three tests pin the OTHER half of the metric — [speechEndMs], the funnel's derivation of
 * the speech-end instant from the frame clock and the endpointer's trail. It is a top-level
 * `internal fun` in `FloatingBubbleService.kt` for exactly the reason `capCutRetainWindowMs` is
 * (Task F8): the service cannot be instantiated in a JVM test, so an inline `nowMs - ec.trailMs`
 * would have had a source needle over it and nothing else, and a reversed subtraction reports a
 * ~54-year wait with the whole suite green.
 */
class PerceivedLatencyTest {

    @Test
    fun reportsTheWaitFromSpeechEndToTheMomentTextBecomesVisible() {
        val p = PerceivedLatency()
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        assertEquals(1_500L, p.onVisible(seq = 0L, nowMs = 2_500L))
    }

    @Test
    fun aSeqWithNoSpeechEndStampReportsNothing() {
        // Cap/stop/switch cuts have no speech-end instant, so there is no honest number to report.
        val p = PerceivedLatency()
        assertNull(p.onVisible(seq = 0L, nowMs = 2_500L))
    }

    @Test
    fun aStampIsConsumedExactlyOnce() {
        val p = PerceivedLatency()
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        assertEquals(1_500L, p.onVisible(seq = 0L, nowMs = 2_500L))
        assertNull(p.onVisible(seq = 0L, nowMs = 3_000L))
    }

    @Test
    fun aSilentSegmentDoesNotShiftTheNextUtterancesNumber() {
        // seq 0 resolves to silence and is never delivered; seq 1 is real. Under a positional
        // queue seq 1 would be handed seq 0's stamp and report a wait that never happened.
        val p = PerceivedLatency()
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        p.onCommitted(seq = 1L, speechEndMs = 5_000L)
        assertEquals(1_400L, p.onVisible(seq = 1L, nowMs = 6_400L))
    }

    @Test
    fun visibilityPrunesEveryEarlierStamp_soSilentSegmentsCannotAccumulate() {
        val p = PerceivedLatency()
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        p.onCommitted(seq = 1L, speechEndMs = 2_000L)
        p.onCommitted(seq = 2L, speechEndMs = 3_000L)
        assertEquals(1_000L, p.onVisible(seq = 2L, nowMs = 4_000L))
        // Delivery is strictly in seq order, so 0 and 1 can never become visible after 2.
        assertNull(p.onVisible(seq = 0L, nowMs = 5_000L))
        assertNull(p.onVisible(seq = 1L, nowMs = 5_000L))
    }

    @Test
    fun trackingIsBounded_theOldestStampIsDroppedFirst() {
        val p = PerceivedLatency(maxTracked = 2)
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        p.onCommitted(seq = 1L, speechEndMs = 2_000L)
        p.onCommitted(seq = 2L, speechEndMs = 3_000L)   // evicts seq 0
        assertNull(p.onVisible(seq = 0L, nowMs = 4_000L))
        assertEquals(2_000L, p.onVisible(seq = 1L, nowMs = 4_000L))
    }

    @Test
    fun atExactlyTheBoundNothingIsEvictedYet() {
        // The other side of the off-by-one. `size >= maxTracked` instead of `>` would throw away
        // the OLDEST in-flight utterance the moment the bound is reached — and the oldest in-flight
        // utterance is systematically the slowest sample, the exact tail S3 Check 2's p95 measures.
        val p = PerceivedLatency(maxTracked = 2)
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        p.onCommitted(seq = 1L, speechEndMs = 2_000L)
        assertEquals(3_000L, p.onVisible(seq = 0L, nowMs = 4_000L))
    }

    @Test
    fun theShippedBoundIsSixtyFourInFlightSeqs() {
        // The default, driven rather than asserted as a bare constant: 0..63 fills the map, and the
        // 65th commit is the first one that evicts anything.
        assertEquals(64, PerceivedLatency.MAX_TRACKED)
        val p = PerceivedLatency()
        for (seq in 0L until 64L) p.onCommitted(seq = seq, speechEndMs = 1_000L)
        p.onCommitted(seq = 64L, speechEndMs = 1_000L)
        assertNull(p.onVisible(seq = 0L, nowMs = 2_000L))
        assertEquals(1_000L, p.onVisible(seq = 1L, nowMs = 2_000L))
    }

    @Test
    fun negativeSeqIsNeverStamped() {
        // commit() returned -1: nothing was cut, and nothing will ever resolve.
        val p = PerceivedLatency()
        p.onCommitted(seq = -1L, speechEndMs = 1_000L)
        assertNull(p.onVisible(seq = -1L, nowMs = 2_000L))
    }

    @Test
    fun resetClearsEveryStampForTheNextSession() {
        val p = PerceivedLatency()
        p.onCommitted(seq = 0L, speechEndMs = 1_000L)
        p.reset()
        assertNull(p.onVisible(seq = 0L, nowMs = 2_000L))
    }

    @Test
    fun perceivedLineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "perceived: seq=4 speechEndToVisible=1500ms",
            EndpointDiag.perceivedLine(seq = 4L, speechEndToVisibleMs = 1_500L),
        )
    }

    @Test
    fun theSpeechEndInstantIsTheFrameClockMinusTheTrail() {
        // EndpointCut.trailMs is `nowMs - tempEndMs` on the frame that FIRED the cut, so the speech
        // ended exactly trailMs before that frame's clock. The reversed subtraction is what a
        // mis-bound derivation looks like from the inside, and it is what this row kills.
        assertEquals(
            1_700_000_009_500L,
            speechEndMs(
                nowMs = 1_700_000_010_000L,
                ec = EndpointCut(speechMs = 2_400L, trailMs = 500L, prob = 0.42f),
            ),
        )
    }

    @Test
    fun aLongerTrailPutsTheSpeechEndFurtherBack_notTheSamePlace() {
        // Second row so the derivation cannot be satisfied by ignoring the trail (or by a constant
        // HANGOVER_MS): the same frame clock with a longer trail must answer earlier.
        assertEquals(
            1_700_000_009_200L,
            speechEndMs(
                nowMs = 1_700_000_010_000L,
                ec = EndpointCut(speechMs = 900L, trailMs = 800L, prob = 0.77f),
            ),
        )
    }

    @Test
    fun theWholeMetricComposesFromTheFrameClockToTheRender() {
        // The headline number, end to end and in the units the acceptance sheet reads. The user
        // stopped speaking 500 ms before the frame that cut; the words rendered 1.2 s after that
        // frame; the reported wait is 1.7 s. This is the composition the funnel and
        // onSegmentResolved perform between them, and the only place both halves are checked at
        // once.
        val frameNow = 1_700_000_010_000L
        val p = PerceivedLatency()
        p.onCommitted(
            seq = 7L,
            speechEndMs = speechEndMs(
                nowMs = frameNow,
                ec = EndpointCut(speechMs = 2_400L, trailMs = 500L, prob = 0.42f),
            ),
        )
        assertEquals(1_700L, p.onVisible(seq = 7L, nowMs = frameNow + 1_200L))
    }
}
