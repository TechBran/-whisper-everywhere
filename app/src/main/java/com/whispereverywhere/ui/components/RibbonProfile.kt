package com.whispereverywhere.ui.components

/**
 * Per-frame silhouette of the aurora ribbons (user design 2026-07-18: "the ribbons dictate how
 * the blob moves — big spikes and mountains inside become spikes and mountains on the blob").
 *
 * BarWaveformView PUBLISHES the composite envelope of all sheets at N stations across the pill
 * — top-crest excursion and bottom-trough excursion, each normalized 0..1 against the local
 * stadium bound. BlobView SAMPLES it at each rim point's horizontal position, so the body's
 * skin deforms in lockstep with the ribbons inside it.
 *
 * Publish swaps whole arrays (copies) into @Volatile refs — readers never see a half-written
 * frame; staleness is bounded by [fresh].
 */
object RibbonProfile {
    const val N = 24

    @Volatile var top = FloatArray(N)
        private set
    @Volatile var bottom = FloatArray(N)
        private set
    @Volatile private var stampNs = 0L

    fun publish(topProfile: FloatArray, bottomProfile: FloatArray) {
        top = topProfile.copyOf()
        bottom = bottomProfile.copyOf()
        stampNs = System.nanoTime()
    }

    /** True while the waveform is live-publishing (guards against a stale last frame). */
    fun fresh(): Boolean = System.nanoTime() - stampNs < 150_000_000L

    fun sample(arr: FloatArray, hx: Float): Float {
        val p = hx.coerceIn(0f, 1f) * (N - 1)
        val i = p.toInt().coerceAtMost(N - 2)
        val f = p - i
        return arr[i] + (arr[i + 1] - arr[i]) * f
    }
}
