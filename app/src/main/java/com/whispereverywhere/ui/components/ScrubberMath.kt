package com.whispereverywhere.ui.components

/** Pure geometry for [TtsScrubberView] (4.3.1 C), so the bar's model is testable without a View. */
object ScrubberMath {
    /** The bar represents the whole read: the estimate, or the bank if the estimate ran short. */
    fun span(available: Long, estimatedTotal: Long): Long = maxOf(available, estimatedTotal, 0L)

    fun frac(part: Long, span: Long): Float =
        if (span <= 0L) 0f else (part.toDouble() / span).toFloat().coerceIn(0f, 1f)

    /**
     * A drag lands on the BAR; the engine seeks within SYNTHESIZED audio (`seekToFraction`'s
     * contract). Re-base, clamping to the bank's end: you can scrub into what exists, not into
     * what is still being generated.
     */
    fun seekFracOfSynthesized(barFrac: Float, available: Long, span: Long): Float {
        if (available <= 0L || span <= 0L) return 0f
        return (barFrac.toDouble() * span / available).toFloat().coerceIn(0f, 1f)
    }
}
