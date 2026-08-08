package com.whispereverywhere.service

import org.junit.Assert.assertEquals
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
