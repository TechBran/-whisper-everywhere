package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure geometry behind the transcript-window resize handle (W3). The handle sits at the
 * preview's TOP-RIGHT and the overlay window is TOP-LEFT anchored: width follows the finger
 * horizontally; height grows when dragging UP; windowDyPx moves params.y up by exactly the
 * window's pixel growth, so the TOP edge follows the finger and the mic pill below stays put.
 */
class ResizeMathTest {

    // Realistic device: 1080x2400 @ density 2.625 (Pixel-class).
    private val density = 2.625f
    private val screenW = 1080
    private val screenH = 2400

    private fun resize(dxPx: Float, dyPx: Float, startW: Float = 280f, startH: Float = 120f) =
        ResizeMath.resize(
            startWidthDp = startW,
            startHeightDp = startH,
            dragDxPx = dxPx,
            dragDyPx = dyPx,
            density = density,
            screenWidthPx = screenW,
            screenHeightPx = screenH,
        )

    @Test fun max_width_is_95_percent_of_screen_or_the_560dp_cap_whichever_is_smaller() {
        // 0.95 * 1080 / 2.625 = 390.857dp — the screen term wins over the 560dp cap here.
        assertEquals(390.857f, ResizeMath.maxWidthDp(screenW, density), 0.01f)
        // 1600px-wide density-2.0 tablet: 0.95 * 1600 / 2.0 = 760dp -> capped at 560.
        assertEquals(560f, ResizeMath.maxWidthDp(1600, 2.0f), 0f)
    }

    @Test fun max_height_is_60_percent_of_screen() {
        assertEquals(548.571f, ResizeMath.maxHeightDp(screenH, density), 0.01f)
    }

    @Test fun zero_drag_is_identity() {
        val r = resize(0f, 0f)
        assertEquals(280f, r.widthDp, 0f)
        assertEquals(120f, r.heightDp, 0f)
        assertEquals(0, r.windowDyPx)
    }

    @Test fun width_follows_the_finger_right() {
        // +105px at 2.625 density = +40dp.
        assertEquals(320f, resize(105f, 0f).widthDp, 0.001f)
    }

    @Test fun width_clamps_at_min_when_dragged_far_left() {
        assertEquals(ResizeMath.MIN_WIDTH_DP, resize(-1000f, 0f).widthDp, 0f)
    }

    @Test fun width_clamps_at_the_screen_derived_max_when_dragged_far_right() {
        assertEquals(ResizeMath.maxWidthDp(screenW, density), resize(5000f, 0f).widthDp, 0.001f)
    }

    @Test fun height_grows_when_dragging_UP() {
        // Finger moves up = negative dy. -105px = +40dp of height.
        assertEquals(160f, resize(0f, -105f).heightDp, 0.001f)
    }

    @Test fun height_clamps_at_min_when_dragged_far_down() {
        assertEquals(ResizeMath.MIN_HEIGHT_DP, resize(0f, 2000f).heightDp, 0f)
    }

    @Test fun height_clamps_at_the_screen_derived_max_when_dragged_far_up() {
        assertEquals(ResizeMath.maxHeightDp(screenH, density), resize(0f, -5000f).heightDp, 0.001f)
    }

    @Test fun growing_taller_moves_the_window_UP_by_exactly_the_pixel_growth() {
        // +40dp height at 2.625 = +105px of window growth -> y compensates by -105, the same
        // distance the finger travelled up: the top edge tracks the finger.
        assertEquals(-105, resize(0f, -105f).windowDyPx)
    }

    @Test fun shrinking_moves_the_window_DOWN_and_compensation_tracks_the_CLAMPED_height() {
        // Drag down 2000px: raw height would be far below zero but clamps at 80dp — a -40dp
        // change. Compensation must follow the CLAMPED delta: -(-40 * 2.625) = +105.
        val r = resize(0f, 2000f)
        assertEquals(ResizeMath.MIN_HEIGHT_DP, r.heightDp, 0f)
        assertEquals(105, r.windowDyPx)
    }

    @Test fun diagonal_drag_resizes_both_axes_independently() {
        val r = resize(105f, -105f)
        assertEquals(320f, r.widthDp, 0.001f)
        assertEquals(160f, r.heightDp, 0.001f)
        assertEquals(-105, r.windowDyPx)
    }

    // ---- The axis lock (4.9.1, owner 2026-09-17: "moving up should resize and lock the window
    // vertically, and the same horizontally, and moving in combination should of course also
    // work"). Evaluated against the TOTAL drag from the start point on every call.

    @Test fun a_clearly_vertical_drag_holds_the_width_at_its_start() {
        // 105px up with a 20px wobble sideways: 105 >= 2.5 * 20, so the width does not move.
        val r = resize(20f, -105f)
        assertEquals(280f, r.widthDp, 0f)
        assertEquals(160f, r.heightDp, 0.001f)
        assertEquals(-105, r.windowDyPx)
    }

    @Test fun a_clearly_horizontal_drag_holds_the_height_and_the_window_does_NOT_walk() {
        // 105px right with a 20px wobble up: 105 >= 2.5 * 20, so the height holds — and with it
        // windowDyPx is exactly 0. This is the bug's other half: with the height unchanged there
        // is no compensation, so a width-only resize can never drag the window up the screen.
        val r = resize(105f, -20f)
        assertEquals(320f, r.widthDp, 0.001f)
        assertEquals(120f, r.heightDp, 0f)
        assertEquals(0, r.windowDyPx)
    }

    @Test fun a_drag_between_the_locks_is_a_diagonal_and_both_axes_move() {
        // 105px right, 60px up: 105 < 2.5 * 60 and 60 < 2.5 * 105 — neither axis dominates.
        val r = resize(105f, -60f)
        assertEquals(320f, r.widthDp, 0.001f)
        assertEquals(120f + 60f / density, r.heightDp, 0.001f)
        assertEquals(-60, r.windowDyPx)
    }

    @Test fun the_ratio_boundary_is_inclusive_on_the_locking_side() {
        // Exactly 2.5x: |dx| = 100, |dy| = 40. The lock engages AT the ratio, so this is
        // width-only; one pixel more of dy and it is a diagonal.
        val atRatio = resize(100f, -40f)
        assertEquals(120f, atRatio.heightDp, 0f)
        assertEquals(0, atRatio.windowDyPx)
        val justPast = resize(100f, -41f)
        assertTrue("41px of dy must register once |dx| < 2.5 * |dy|", justPast.heightDp > 120f)
        assertEquals(-41, justPast.windowDyPx)
    }

    @Test fun the_lock_is_symmetric_for_the_vertical_side_of_the_boundary() {
        val atRatio = resize(40f, -100f)
        assertEquals(280f, atRatio.widthDp, 0f)
        val justPast = resize(41f, -100f)
        assertTrue("41px of dx must register once |dy| < 2.5 * |dx|", justPast.widthDp > 280f)
    }

    @Test fun the_ratio_is_the_documented_two_and_a_half() {
        assertEquals(2.5f, ResizeMath.AXIS_LOCK_RATIO, 0f)
    }

    @Test fun a_tiny_screen_whose_max_is_below_MIN_never_throws_and_pins_to_MIN() {
        // 0.95 * 400 / 2.625 = 144.8dp < MIN_WIDTH_DP (200): Kotlin's coerceIn THROWS when
        // max < min, so the implementation must floor the screen-derived max at MIN first.
        val r = ResizeMath.resize(280f, 120f, 0f, 0f, 2.625f, 400, 2400)
        assertEquals(ResizeMath.MIN_WIDTH_DP, r.widthDp, 0f)
    }

    @Test fun defaults_are_the_shipped_280_by_120() {
        assertEquals(280f, ResizeMath.DEFAULT_WIDTH_DP, 0f)
        assertEquals(120f, ResizeMath.DEFAULT_HEIGHT_DP, 0f)
    }
}
