package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE STARTUP RING and its seam (4.4.0 startup amendment, Task S2).
 *
 * `FloatingBubbleService` is an Android Service no JVM test can construct, so this file carries
 * the BEHAVIOURAL half of the startup seam and `StartupRingWiringPinTest` carries the
 * ORDER-IN-SOURCE half — the same split `CapSeamPinTest` states for the cap seam.
 *
 * The rows that matter, and why each is here rather than only described:
 *
 *  - **The capacity is the measured worst case with margin.** `docs/superpowers/research/
 *    2026-09-10-startup-cutoff-investigation.md` §3a measures a 4,107 ms cold npu-turbo load
 *    (`capture-yt-84-flatline-0903-1936.txt`, 19:29:48.854 -> 52.961); the amendment sizes the
 *    ring at 6 s. The two constants must keep agreeing with 16 kHz mono PCM16, or the diag line's
 *    millisecond figures become fiction.
 *  - **Overflow drops the OLDEST.** The newest speech matters more once the ring is full: the user
 *    is still talking, and the words they are saying NOW are the ones the engine is about to get.
 *  - **THE DRAIN IS EXACTLY-ONCE AND IN ORDER, under live audio.** The last row is the amendment's
 *    condition 4 executed rather than argued: a ring filled past its cap by a 9.6 s connect, then
 *    a paced drain interleaved with 200 live chunks, and every chunk the ring still held reaches
 *    the sink exactly once, in capture order, with the live chunks behind them.
 */
class StartupRingTest {

    /** One 32 ms capture chunk at 16 kHz mono PCM16, exactly as `StreamingAudioRecorder` reads. */
    private val chunkBytes = 1024

    /** A chunk whose first four bytes encode [seq], so the sink can name what it received. */
    private fun chunk(seq: Int, size: Int = chunkBytes): ByteArray {
        val out = ByteArray(size)
        out[0] = (seq ushr 24).toByte()
        out[1] = (seq ushr 16).toByte()
        out[2] = (seq ushr 8).toByte()
        out[3] = seq.toByte()
        return out
    }

    private fun seqOf(pcm: ByteArray): Int =
        ((pcm[0].toInt() and 0xFF) shl 24) or
            ((pcm[1].toInt() and 0xFF) shl 16) or
            ((pcm[2].toInt() and 0xFF) shl 8) or
            (pcm[3].toInt() and 0xFF)

    @Test
    fun theCapacityConstantsDescribeSixSecondsOf16kHzMonoPcm16() {
        assertEquals(32_000, StartupRing.BYTES_PER_SECOND)
        assertEquals(6_000L, StartupRing.CAPACITY_MS)
        assertEquals(192_000, StartupRing.CAPACITY_BYTES)
        assertEquals(
            "the byte cap and the millisecond cap must describe the same audio",
            StartupRing.CAPACITY_BYTES.toLong(),
            StartupRing.CAPACITY_MS * StartupRing.BYTES_PER_SECOND / 1000L,
        )
        assertEquals(6_000L, StartupRing.msOf(StartupRing.CAPACITY_BYTES))
        assertEquals(32L, StartupRing.msOf(1024))
    }

    @Test
    fun aFreshRingIsEmptyAndHasDroppedNothing() {
        val ring = StartupRing()
        assertTrue(ring.isEmpty())
        assertEquals(0, ring.chunkCount())
        assertEquals(0, ring.byteSize())
        assertEquals(0, ring.droppedBytes())
    }

    @Test
    fun chunksDrainInCaptureOrderCarryingTheirOwnAmplitudeAndStamp() {
        val ring = StartupRing()
        ring.append(chunk(1), amp = 111, nowMs = 1_000L)
        ring.append(chunk(2), amp = 222, nowMs = 1_032L)
        ring.append(chunk(3), amp = 333, nowMs = 1_064L)
        assertEquals(3, ring.chunkCount())
        assertEquals(3 * chunkBytes, ring.byteSize())

        val seen = ArrayList<Triple<Int, Int, Long>>()
        val drained = ring.drainAll { pcm, amp, nowMs -> seen += Triple(seqOf(pcm), amp, nowMs) }

        assertEquals(3, drained)
        assertEquals(
            listOf(Triple(1, 111, 1_000L), Triple(2, 222, 1_032L), Triple(3, 333, 1_064L)),
            seen,
        )
        assertTrue("a full drain empties the ring", ring.isEmpty())
        assertEquals(0, ring.byteSize())
    }

    @Test
    fun overflowDropsTheOldestAudioAndReportsHowMuchItDropped() {
        val ring = StartupRing()
        val fits = StartupRing.CAPACITY_BYTES / chunkBytes          // 187 chunks of 32 ms
        for (seq in 1..fits) {
            assertEquals("nothing may drop while the ring still fits", 0, ring.append(chunk(seq), 0, seq.toLong()))
        }
        assertEquals(fits, ring.chunkCount())

        // The one that does not fit: the OLDEST goes, not the newest.
        assertEquals(chunkBytes, ring.append(chunk(fits + 1), 0, (fits + 1).toLong()))
        assertEquals(fits, ring.chunkCount())
        assertEquals(chunkBytes, ring.droppedBytes())

        val seen = ArrayList<Int>()
        ring.drainAll { pcm, _, _ -> seen += seqOf(pcm) }
        assertEquals((2..fits + 1).toList(), seen)
    }

    @Test
    fun theDroppedTotalAccumulatesAcrossEveryOverflow() {
        val ring = StartupRing()
        val fits = StartupRing.CAPACITY_BYTES / chunkBytes
        for (seq in 1..fits + 10) ring.append(chunk(seq), 0, seq.toLong())
        assertEquals(10 * chunkBytes, ring.droppedBytes())
        assertEquals(320L, StartupRing.msOf(ring.droppedBytes()))
    }

    @Test
    fun aChunkLargerThanTheWholeRingIsRefusedWholeRatherThanEmptyingIt() {
        val ring = StartupRing()
        ring.append(chunk(1), 0, 1L)
        val huge = chunk(99, size = StartupRing.CAPACITY_BYTES + 1)
        assertEquals(huge.size, ring.append(huge, 0, 2L))
        // The chunk it could never hold did not cost it the audio it already had.
        val seen = ArrayList<Int>()
        ring.drainAll { pcm, _, _ -> seen += seqOf(pcm) }
        assertEquals(listOf(1), seen)
    }

    @Test
    fun anEmptyChunkIsIgnoredEntirely() {
        val ring = StartupRing()
        assertEquals(0, ring.append(ByteArray(0), 0, 1L))
        assertTrue(ring.isEmpty())
        assertEquals(0, ring.droppedBytes())
    }

    @Test
    fun aDrainSliceIsBoundedByItsChunkCount() {
        val ring = StartupRing()
        for (seq in 1..10) ring.append(chunk(seq), 0, seq.toLong())

        val first = ArrayList<Int>()
        assertEquals(4, ring.drainSlice(4) { pcm, _, _ -> first += seqOf(pcm) })
        assertEquals(listOf(1, 2, 3, 4), first)
        assertEquals(6, ring.chunkCount())
        assertEquals(6 * chunkBytes, ring.byteSize())

        val rest = ArrayList<Int>()
        assertEquals(6, ring.drainSlice(100) { pcm, _, _ -> rest += seqOf(pcm) })
        assertEquals((5..10).toList(), rest)
        assertTrue(ring.isEmpty())
    }

    @Test
    fun aNonPositiveSliceDrainsNothing() {
        val ring = StartupRing()
        ring.append(chunk(1), 0, 1L)
        assertEquals(0, ring.drainSlice(0) { _, _, _ -> throw AssertionError("must not drain") })
        assertEquals(0, ring.drainSlice(-1) { _, _, _ -> throw AssertionError("must not drain") })
        assertEquals(1, ring.chunkCount())
    }

    @Test
    fun clearDiscardsEverythingIncludingTheDropTally() {
        val ring = StartupRing()
        for (seq in 1..StartupRing.CAPACITY_BYTES / chunkBytes + 5) ring.append(chunk(seq), 0, seq.toLong())
        assertTrue(ring.droppedBytes() > 0)
        ring.clear()
        assertTrue(ring.isEmpty())
        assertEquals(0, ring.byteSize())
        assertEquals(0, ring.chunkCount())
        assertEquals("a discarded session may not report the previous one's drops", 0, ring.droppedBytes())
    }

    @Test
    fun theSeamBuffersUntilTheEngineIsReadyThenDrainsThenRunsLive() {
        assertEquals(
            StartupSeam.Route.BUFFER,
            StartupSeam.route(engineReady = false, ringEmpty = true),
        )
        assertEquals(
            "an unready engine buffers whatever the ring holds",
            StartupSeam.Route.BUFFER,
            StartupSeam.route(engineReady = false, ringEmpty = false),
        )
        assertEquals(
            StartupSeam.Route.DRAIN,
            StartupSeam.route(engineReady = true, ringEmpty = false),
        )
        assertEquals(
            StartupSeam.Route.LIVE,
            StartupSeam.route(engineReady = true, ringEmpty = true),
        )
    }

    @Test
    fun thePaceIsBoundedSoTheDrainCannotStarveTheAudioRecordReader() {
        // The budget the amendment sets: a 6 s ring is ~430 ms of solid Silero probe work (187
        // frames at the measured p50 2.3-2.4 ms, docs/PLAY-LISTING.md:316) on the same thread that
        // must service an AudioRecord ring which overflows in >=128 ms
        // (util/StreamingAudioRecorder.kt, `max(getMinBufferSize, 4096)` bytes). Dumping it is the
        // one regression strictly worse than the bug. Paced at this many replayed frames per live
        // chunk, the capture thread spends (1 + pace) probe calls per 32 ms read period.
        assertEquals(4, StartupRing.DRAIN_CHUNKS_PER_TICK)
        val worstCaseProbeUs = (1 + StartupRing.DRAIN_CHUNKS_PER_TICK) * 6_100L  // p99 6.1 ms/frame
        assertTrue(
            "even at the measured p99 the tick must fit inside one 32 ms read period",
            worstCaseProbeUs < 32_000L,
        )
    }

    @Test
    fun aFullRingDrainingUnderLiveAudioDeliversEveryChunkExactlyOnceInOrder() {
        // THE AMENDMENT'S CONDITION 4, executed. The simulator below is the service's
        // `onAudioChunk` head statement for statement — the REAL `StartupSeam.route` and the REAL
        // ring — with the engine replaced by a list. `StartupRingWiringPinTest` is what holds the
        // service to this shape.
        val ring = StartupRing()
        val delivered = ArrayList<Int>()
        var engineReady = false

        fun tick(seq: Int) {
            when (StartupSeam.route(engineReady = engineReady, ringEmpty = ring.isEmpty())) {
                StartupSeam.Route.BUFFER -> ring.append(chunk(seq), 0, seq.toLong())
                StartupSeam.Route.DRAIN -> {
                    ring.drainSlice(StartupRing.DRAIN_CHUNKS_PER_TICK) { pcm, _, _ -> delivered += seqOf(pcm) }
                    if (ring.isEmpty()) delivered += seq else ring.append(chunk(seq), 0, seq.toLong())
                }
                StartupSeam.Route.LIVE -> delivered += seq
            }
        }

        // A cold connect far longer than the ring: 300 chunks x 32 ms = 9.6 s against a 6 s cap.
        for (seq in 1..300) tick(seq)
        val fits = StartupRing.CAPACITY_BYTES / chunkBytes
        assertEquals(fits, ring.chunkCount())

        // onOpen.
        engineReady = true
        for (seq in 301..500) tick(seq)

        // The backlog is gone well before the live audio stops, and the seam retired itself: the
        // last chunks took the LIVE route, so the ring is not running one chunk behind forever.
        assertTrue("the paced drain must catch up inside the live audio", ring.isEmpty())
        assertEquals(
            "every chunk the ring held, then every live chunk, each exactly once, in order",
            ((300 - fits + 1)..500).toList(),
            delivered,
        )
        assertEquals(
            "and the only audio missing is the oldest, dropped at the cap",
            (300 - fits) * chunkBytes,
            ring.droppedBytes(),
        )
    }

    @Test
    fun aDrainThatOutlivesTheSessionIsFlushedWholeByTheStopPath() {
        // stopRecording's flush: the user taps stop while the backlog is still catching up, and
        // the audio still in the ring is handed to the engine before the unconditional stop
        // commit — without it, exactly the words this feature exists to save are lost to a short
        // session (tap, two words, stop, on a 4 s cold load).
        val ring = StartupRing()
        for (seq in 1..50) ring.append(chunk(seq), 0, seq.toLong())
        val delivered = ArrayList<Int>()
        ring.drainSlice(StartupRing.DRAIN_CHUNKS_PER_TICK) { pcm, _, _ -> delivered += seqOf(pcm) }
        assertFalse(ring.isEmpty())

        val flushed = ring.drainAll { pcm, _, _ -> delivered += seqOf(pcm) }
        assertEquals(46, flushed)
        assertEquals((1..50).toList(), delivered)
        assertTrue(ring.isEmpty())
    }

    @Test
    fun theDiagLinesCarryNumbersOnlyAndNameTheDurationsTheyClaim() {
        assertEquals(
            "startup ring: FULL at 6000ms - dropping the oldest audio (first drop 32ms)",
            StartupRing.overflowLine(firstDropMs = 32L),
        )
        assertEquals(
            "startup ring: drain chunks=128 buffered=4096ms dropped=0ms pace=4",
            StartupRing.drainLine(chunks = 128, bufferedMs = 4_096L, droppedMs = 0L),
        )
        assertEquals(
            "startup ring: stop flush chunks=46 ms=1472",
            StartupRing.stopFlushLine(chunks = 46, ms = 1_472L),
        )
        assertEquals(
            "startup ring: switch flush chunks=46 ms=1472",
            StartupRing.switchFlushLine(chunks = 46, ms = 1_472L),
        )
        assertNotEquals(
            "the two flush sites must be distinguishable in a log: a reader chasing mic audio in " +
                "a device-audio transcript needs to see WHICH flush it was",
            StartupRing.stopFlushLine(chunks = 1, ms = 32L),
            StartupRing.switchFlushLine(chunks = 1, ms = 32L),
        )
    }
}
