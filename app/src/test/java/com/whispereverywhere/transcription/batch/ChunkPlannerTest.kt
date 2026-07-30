package com.whispereverywhere.transcription.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class ChunkPlannerTest {

    // The scan now STREAMS a RandomAccessFile (never readBytes() the whole PCM — the OOM fix). These
    // pause-detection tests keep their in-memory PCM patterns and route them through the file API.
    private fun scan(pcm: ByteArray): List<Int> {
        val f = File.createTempFile("scan", ".pcm").apply { deleteOnExit() }
        f.writeBytes(pcm)
        return RandomAccessFile(f, "r").use { SilenceScanner.scan(it, f.length()) }
    }

    // ---------- ChunkPlanner.plan: the packing math ----------

    @Test fun audio_that_fits_the_ceiling_is_one_natural_chunk() {
        val p = ChunkPlanner.plan(totalBytes = 1000, maxChunkBytes = 2000, minChunkBytes = 0, boundaries = emptyList())
        assertEquals(1, p.size)
        assertEquals(0, p[0].startByte); assertEquals(1000, p[0].endByte)
        assertFalse("a whole-fit tail is never a hard cut", p[0].hardCut)
    }

    @Test fun zero_length_audio_plans_nothing() {
        assertTrue(ChunkPlanner.plan(0, 2000, 0, emptyList()).isEmpty())
    }

    @Test fun the_plan_covers_every_byte_with_no_gaps_or_overlaps() {
        val p = ChunkPlanner.plan(10_000, 3000, 0, boundaries = listOf(2500, 6000))
        assertEquals(0, p.first().startByte)
        assertEquals(10_000, p.last().endByte)
        for (i in 1 until p.size) assertEquals("chunk $i must start where ${i - 1} ended", p[i - 1].endByte, p[i].startByte)
    }

    @Test fun it_cuts_on_the_last_silence_at_or_before_the_ceiling() {
        // Ceiling from 0 is 3000; boundaries 900 and 2800 are candidates, 3200 is past the ceiling.
        val p = ChunkPlanner.plan(6000, maxChunkBytes = 3000, minChunkBytes = 0, boundaries = listOf(900, 2800, 3200))
        assertEquals(2800, p[0].endByte)          // the LAST boundary <= 3000, not 900
        assertFalse(p[0].hardCut)
    }

    @Test fun a_boundary_exactly_on_the_ceiling_is_usable() {
        val p = ChunkPlanner.plan(6000, 3000, 0, boundaries = listOf(3000))
        assertEquals(3000, p[0].endByte)
        assertFalse(p[0].hardCut)
    }

    @Test fun continuous_speech_with_no_silence_is_hard_cut_at_the_ceiling() {
        val p = ChunkPlanner.plan(7000, maxChunkBytes = 3000, minChunkBytes = 0, boundaries = emptyList())
        assertEquals(listOf(3000, 6000, 7000), p.map { it.endByte })
        assertTrue("first over-ceiling cut is hard", p[0].hardCut)
        assertTrue(p[1].hardCut)
        assertFalse("the final tail fits, so it is natural", p[2].hardCut)
    }

    @Test fun no_chunk_ever_exceeds_the_ceiling() {
        val p = ChunkPlanner.plan(50_000, maxChunkBytes = 4096, minChunkBytes = 0,
            boundaries = (1000..49_000 step 1500).toList())
        p.forEach { assertTrue("chunk ${it.index} = ${it.endByte - it.startByte}", it.endByte - it.startByte <= 4096) }
    }

    @Test fun every_offset_is_even_so_a_sample_is_never_split() {
        // Odd total, odd max, odd boundaries — all must be forced even.
        val p = ChunkPlanner.plan(9999, maxChunkBytes = 3001, minChunkBytes = 0, boundaries = listOf(1501, 2999))
        p.forEach {
            assertEquals("start even", 0, it.startByte % 2)
            assertEquals("end even", 0, it.endByte % 2)
        }
    }

    @Test fun a_boundary_too_close_to_the_start_is_skipped_for_a_hard_cut() {
        // minChunkBytes = 2000: the only boundary (500) is too close, so hard-cut at the ceiling.
        val p = ChunkPlanner.plan(6000, maxChunkBytes = 3000, minChunkBytes = 2000, boundaries = listOf(500))
        assertEquals(3000, p[0].endByte)
        assertTrue(p[0].hardCut)
    }

    @Test fun boundaries_are_deduped_sorted_and_bounds_filtered() {
        // Unsorted, duplicated, one at 0 and one at/after total — the planner must not choke.
        val p = ChunkPlanner.plan(6000, 3000, 0, boundaries = listOf(2800, 2800, 0, 6000, 900))
        assertEquals(2800, p[0].endByte)
        assertEquals(0, p.first().startByte)
        assertEquals(6000, p.last().endByte)
    }

    @Test fun cloud_and_local_ceilings_shape_the_same_audio_differently() {
        val fifteenMin = 15 * 60 * 16_000 * 2   // 28.8 MB
        val cloud = ChunkPlanner.plan(fifteenMin, ChunkPlanner.CLOUD_CEILING_BYTES, 0, emptyList())
        val local = ChunkPlanner.plan(fifteenMin, ChunkPlanner.LOCAL_CHUNK_BYTES, 0, emptyList())
        cloud.forEach { assertTrue(it.endByte - it.startByte <= ChunkPlanner.CLOUD_CEILING_BYTES) }
        assertTrue("local's 90 s chunks are many more than cloud's 20 MB chunks", local.size > cloud.size)
    }

    @Test fun the_cloud_ceiling_stays_under_the_openai_hard_cap_even_after_the_wav_header() {
        val hardCap = 25L * 1024 * 1024
        assertTrue((ChunkPlanner.CLOUD_CEILING_BYTES.toLong() + 44) < hardCap)
    }

    // ---------- SilenceScanner.scan: pause detection ----------

    private fun loud(nFrames: Int): ByteArray {
        // Frames of 960 bytes full of a large-amplitude square wave (well above the 500 threshold).
        val one = ByteArray(960) { if (it % 2 == 0) 0x00 else 0x40 } // ~0x4000 samples
        return ByteArray(nFrames * 960).also { for (f in 0 until nFrames) one.copyInto(it, f * 960) }
    }
    private fun quiet(nFrames: Int) = ByteArray(nFrames * 960) // all zero -> RMS 0

    @Test fun a_long_pause_between_two_utterances_yields_one_boundary() {
        val pcm = loud(20) + quiet(12) + loud(20)  // 12 frames ~= 360 ms of silence
        val cuts = scan(pcm)
        assertEquals(1, cuts.size)
        // Boundary sits inside the gap, i.e. between the two loud runs.
        assertTrue(cuts[0] > 20 * 960 && cuts[0] < 32 * 960)
        assertEquals("even", 0, cuts[0] % 2)
    }

    @Test fun a_gap_shorter_than_the_minimum_is_not_a_boundary() {
        val pcm = loud(20) + quiet(3) + loud(20)   // ~90 ms — a within-speech micro-pause
        assertTrue(scan(pcm).isEmpty())
    }

    @Test fun trailing_silence_at_the_end_is_not_a_boundary() {
        // A cut at the very end is useless; only gaps followed by more speech count.
        assertTrue(scan(loud(20) + quiet(30)).isEmpty())
    }
}
