package com.whispereverywhere.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attached tab's ripple taper (owner ruling 2026-09-22: the waveform bubble joins the
 * transcript window "so it looks like one unified piece"). While attached, the upper half of the
 * rim is a flat top and must not move at all, and the lower half must fade in from ZERO at the
 * equator — any motion left there would open a step between the tab's straight sides and its
 * rippling caps.
 */
class BlobAttachTest {

    @Test
    fun theUpperHalfDoesNotMove() {
        for (ny in listOf(-1f, -0.5f, -0.0001f, 0f)) {
            assertEquals("ny=$ny", 0f, BlobAttach.taper(ny), 0f)
        }
    }

    @Test
    fun theBottomKeepsItsWholeRipple() {
        assertEquals(1f, BlobAttach.taper(1f), 0f)
        assertEquals("clamped past the bottom", 1f, BlobAttach.taper(1.5f), 0f)
    }

    @Test
    fun itRisesSmoothlyAndNeverOvershoots() {
        var last = 0f
        var ny = 0f
        while (ny <= 1f) {
            val v = BlobAttach.taper(ny)
            assertTrue("never below the previous point (ny=$ny)", v >= last)
            assertTrue("within 0..1 (ny=$ny)", v in 0f..1f)
            last = v
            ny += 0.01f
        }
        assertEquals("smoothstep midpoint", 0.5f, BlobAttach.taper(0.5f), 1e-6f)
        assertTrue("flat at the equator: a tiny ny barely moves", BlobAttach.taper(0.01f) < 0.001f)
    }
}
