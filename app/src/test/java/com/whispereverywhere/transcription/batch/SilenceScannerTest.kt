package com.whispereverywhere.transcription.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * Pins the STREAMING silence scan (review Important: the plan phase used to readBytes() the whole
 * decoded PCM into one ByteArray, an unbounded ~hundreds-of-MB allocation that OOM'd the exact
 * hour-long file the feature exists to serve). The scan now reads fixed windows over a
 * RandomAccessFile; these tests pin its boundary semantics AND that a gap straddling a window seam
 * is still found (the streaming refactor's one real hazard).
 */
class SilenceScannerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun loudFrame(): ByteArray = ByteArray(960).also { b ->
        var i = 0
        while (i < 960) { b[i] = (5000 and 0xFF).toByte(); b[i + 1] = ((5000 shr 8) and 0xFF).toByte(); i += 2 }
    }
    private fun silentFrame(): ByteArray = ByteArray(960) // zeros → RMS 0, below the silence floor

    private fun writePcm(vararg segments: Pair<ByteArray, Int>): File {
        val f = File(tmp.newFolder(), "audio.pcm")
        f.outputStream().use { os -> for ((frame, count) in segments) repeat(count) { os.write(frame) } }
        return f
    }

    private fun scan(f: File): List<Int> =
        RandomAccessFile(f, "r").use { SilenceScanner.scan(it, f.length()) }

    @Test fun finds_the_even_midpoint_of_a_gap_between_two_speech_runs() {
        // 10 loud + 10 silent + 10 loud frames. gapStart = 10*960 = 9600; resumes at 20*960 = 19200;
        // gap = 9600 ≥ MIN_GAP (8*960); mid = 9600 + 4800 = 14400.
        val f = writePcm(loudFrame() to 10, silentFrame() to 10, loudFrame() to 10)
        assertEquals(listOf(14400), scan(f))
    }

    @Test fun continuous_speech_yields_no_cut() {
        assertTrue(scan(writePcm(loudFrame() to 20)).isEmpty())
    }

    @Test fun trailing_silence_is_never_a_cut() {
        assertTrue(scan(writePcm(loudFrame() to 10, silentFrame() to 20)).isEmpty())
    }

    @Test fun a_gap_shorter_than_the_minimum_is_ignored() {
        // 4 silent frames (3840 bytes) < MIN_GAP (7680) → no cut.
        assertTrue(scan(writePcm(loudFrame() to 10, silentFrame() to 4, loudFrame() to 10)).isEmpty())
    }

    @Test fun a_gap_straddling_a_window_seam_is_still_found() {
        // 1024 loud frames = exactly one read window, then a gap opening on the seam, then speech.
        // The streaming scan must carry the open gap across the read boundary.
        // gapStart = 1024*960 = 983040; resumes at 1034*960 = 992640; mid = 983040 + 4800 = 987840.
        val cuts = scan(writePcm(loudFrame() to 1024, silentFrame() to 10, loudFrame() to 10))
        assertEquals(listOf(987840), cuts)
    }
}
