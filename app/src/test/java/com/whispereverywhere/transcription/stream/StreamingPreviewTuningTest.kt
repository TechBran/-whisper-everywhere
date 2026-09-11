package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The measured constants (spec §2), as literals with their provenance. Written as literals
 * here, not derived, so a re-tune in either direction is a red test and not a quiet drift.
 */
class StreamingPreviewTuningTest {

    @Test fun twoThreadsIsTheNumber() {
        // rung 3 §5.4: max-pace RTF 0.054 @ 2, 0.072 @ 4 (SLOWER — ORT hand-offs on 16 ms bursts),
        // 0.051 @ 1. Two.
        assertEquals(2, StreamingPreviewTuning.NUM_THREADS)
    }

    @Test fun thePadIsFiveHundredMilliseconds() {
        // rung 1 §4: the canary loses `VE` at 450 ms and keeps `FIVE` at 500, on the PC and on the
        // Tab (rung 3 §5.3); 800 buys nothing. 8,000 zero samples at 16 kHz.
        assertEquals(500L, StreamingPreviewTuning.PAD_MS)
        assertEquals(8_000, StreamingPreviewTuning.padSamples())
    }

    @Test fun theFeedIsTheAppsOwnChunking() {
        // util/StreamingAudioRecorder.kt:80 — 1,024-byte reads = 512 samples = 32 ms.
        assertEquals(16_000, StreamingPreviewTuning.SAMPLE_RATE)
        assertEquals(512, StreamingPreviewTuning.CHUNK_SAMPLES)
        assertEquals(32, StreamingPreviewTuning.BYTES_PER_MS)
    }

    @Test fun theQueueAndTheRingAreBounded() {
        // 128 chunks ≈ 4.1 s (LiveTranscriptionEngine.DEFAULT_MAX_BACKLOG); the ring holds
        // CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS of PCM16 = 96,000 bytes.
        assertEquals(128, StreamingPreviewTuning.QUEUE_CAPACITY)
        assertEquals(3_000L, StreamingPreviewTuning.RETAIN_RING_MS)
        assertEquals(96_000, StreamingPreviewTuning.ringBytes())
        assertEquals(3, StreamingPreviewTuning.MAX_CONSECUTIVE_FAILURES)
    }
}
