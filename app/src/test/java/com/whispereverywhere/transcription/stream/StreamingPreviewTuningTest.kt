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

    @Test fun theMeasuredPadIsFiveHundredMillisecondsAndIsWhatTheShippingPackGets() {
        // rung 1 §4: the canary loses `VE` at 450 ms and keeps `FIVE` at 500, on the PC and on the
        // Tab (rung 3 §5.3); 800 buys nothing. 8,000 zero samples at 16 kHz.
        assertEquals(500L, StreamingPreviewTuning.MEASURED_PAD_MS)
        // And the derivation REPRODUCES it for the pack it was measured on — T = 45, the shipping
        // English encoder's own metadata. If this line ever disagrees with the one above, the
        // 4.4.1 behaviour the owner validated on device has silently changed.
        assertEquals(500L, StreamingPreviewTuning.padMsFor(45))
        assertEquals(500L, StreamingPackCatalog.EN.padMs)
        assertEquals(8_000, StreamingPreviewTuning.padSamplesFor(45))
    }

    @Test fun thePadIsDerivedFromTheEncodersOwnFrameRequirement() {
        // T frames at a 10 ms shift, plus the margin the measured cliff bought: 10·T + 50.
        assertEquals(10L, StreamingPreviewTuning.FRAME_SHIFT_MS)
        assertEquals(50L, StreamingPreviewTuning.PAD_MARGIN_MS)
        // T = 77 (ru, id, tr, et, pt — every 640 ms pack): one pass needs 770 ms of feature, so
        // the flat 500 is 320 ms short and the last word of every utterance never emits. 820 is
        // the number the qualification table derives independently (§6(4), E1).
        assertEquals(820L, StreamingPreviewTuning.padMsFor(77))
        assertEquals(13_120, StreamingPreviewTuning.padSamplesFor(77))
        // T = 141 (the Kroko 128-shift builds), for the same arithmetic two cadences further out.
        assertEquals(1_460L, StreamingPreviewTuning.padMsFor(141))
    }

    @Test fun theMeasuredPadIsAFloorTheDerivationNeverGoesUnder() {
        // T = 39 — the `zipformer` v1 exports (fr, zh-en). The arithmetic asks 440; the pack gets
        // 500, because the two errors are not symmetric: an over-long pad costs zeros nobody
        // hears (800 ms measured harmless) and a short one silently drops the utterance's last
        // word, which is the defect the derivation exists to close.
        assertEquals(500L, StreamingPreviewTuning.padMsFor(39))
        // The floor binds all the way down, including the degenerate values a bad metadata read
        // could hand it — a pad is never shorter than the one that has been measured.
        assertEquals(500L, StreamingPreviewTuning.padMsFor(1))
        assertEquals(500L, StreamingPreviewTuning.padMsFor(0))
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
