package com.whispereverywhere.transcription.batch

/**
 * §6.5's "cloud is never a surprise charge" as math the UI and the service both call.
 *
 * Estimates derive from the recording's byteLength (retained in the manifest) at the PCM16/16 kHz
 * byte rate, priced at gpt-transcribe's published batch rate. They are ESTIMATES shown to the
 * user, never a promise — copy must say "about".
 *
 * needsConfirmation is an OR of a cents threshold and a minutes threshold: with today's price the
 * minutes bound binds first (10¢ ≈ 22 min), but OR-ing both means a future price change cannot
 * silently widen the unconfirmed window.
 */
object BatchCostEstimator {
    /** 16 kHz × 2 bytes, mono PCM16. */
    const val BYTES_PER_SECOND = 32_000

    /** gpt-transcribe batch: $0.0045/min (verified against live docs 2026-07-29). */
    const val CENTS_PER_MINUTE = 0.45

    const val CONFIRM_CENTS = 10.0
    const val CONFIRM_MINUTES = 10.0

    fun minutes(byteLength: Long): Double = byteLength / (BYTES_PER_SECOND * 60.0)

    fun estimatedCents(byteLength: Long): Double = minutes(byteLength) * CENTS_PER_MINUTE

    fun needsConfirmation(byteLength: Long): Boolean =
        estimatedCents(byteLength) >= CONFIRM_CENTS || minutes(byteLength) >= CONFIRM_MINUTES

    /**
     * Pre-flight bridge: the UI knows only MediaMetadataRetriever's duration before any decode
     * exists. Decoded PCM16 at 16 kHz mono is exactly 32 bytes per millisecond.
     */
    fun bytesForDuration(durationMs: Long): Long = durationMs * (BYTES_PER_SECOND / 1000L)
}
