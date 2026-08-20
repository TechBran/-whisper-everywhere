package com.whispereverywhere.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingTimerPolicyTest {

    @Test
    fun tickerRunsInFinalizingAndProcessing() {
        // FINALIZING added in 3.6.0 (Workstream E4): the stop-tap drain counts up visibly
        // alongside the "Finishing…" status line. PROCESSING keeps the legacy branch.
        assertTrue(processingTimerRunsIn(FloatingBubbleService.BubbleState.FINALIZING))
        assertTrue(processingTimerRunsIn(FloatingBubbleService.BubbleState.PROCESSING))
    }

    @Test
    fun tickerStopsEverywhereElse() {
        // Both FINALIZING exits (IDLE, ERROR) must terminate the while-loop — and the ticker
        // must never run over recording or connecting chrome.
        assertFalse(processingTimerRunsIn(FloatingBubbleService.BubbleState.IDLE))
        assertFalse(processingTimerRunsIn(FloatingBubbleService.BubbleState.CONNECTING))
        assertFalse(processingTimerRunsIn(FloatingBubbleService.BubbleState.RECORDING))
        assertFalse(processingTimerRunsIn(FloatingBubbleService.BubbleState.ERROR))
    }
}
