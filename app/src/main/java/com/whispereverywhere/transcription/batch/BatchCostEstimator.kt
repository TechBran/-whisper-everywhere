package com.whispereverywhere.transcription.batch

import com.whispereverywhere.provider.ProviderId

/**
 * §6.5's "cloud is never a surprise charge" as math the UI and the service both call. Estimates
 * derive from byteLength at the PCM16/16 kHz byte rate, priced PER PROVIDER. They are ESTIMATES —
 * copy always says "about".
 *
 * KNOWN vs UNKNOWN price. For a provider whose published rate we could pin, the estimate "asks
 * sooner, never under" holds: the shown ¢/min IS that rate. For a provider whose price is NOT
 * published (Gemini audio input), that guarantee is UNPROVABLE — a truly-unknown price is not
 * bounded by the max of the KNOWN rates, so pricing it at the most-expensive-KNOWN rate could still
 * under-quote and under-warn. So an UNKNOWN price is handled two ways, NOT silently trusted:
 *   (1) [centsPerMinute] returns the most-expensive-KNOWN rate only as an ILLUSTRATIVE floor — the
 *       UI must label it "price not published", never present it as Gemini's actual rate; and
 *   (2) [needsConfirmation] ALWAYS returns true for a real (byteLength > 0) job on such a provider,
 *       so the cost confirm can never be silently skipped on an estimate that might be too low.
 * Thus the honest guarantee is: KNOWN price -> never under the true rate; UNKNOWN price -> the
 * confirm gate always fires. [isPriceKnown] exposes the distinction for the UI copy.
 *
 * Rates verified against live docs 2026-07-31 (¢/min):
 *   OpenAI gpt-transcribe 0.60 ($0.006/min) · ElevenLabs scribe_v2 0.37 ($0.22/hr)
 *   Soniox stt-async-v5   0.17 ($0.10/hr)   · Gemini gemini-3.6-flash audio input NOT published -> UNKNOWN.
 */
object BatchCostEstimator {
    /** 16 kHz x 2 bytes, mono PCM16. */
    const val BYTES_PER_SECOND = 32_000

    const val OPENAI_CENTS_PER_MIN = 0.60
    const val ELEVENLABS_CENTS_PER_MIN = 0.37
    const val SONIOX_CENTS_PER_MIN = 0.17
    /** The dearest KNOWN rate; governs any provider whose price we could not pin (Gemini audio). */
    const val MOST_EXPENSIVE_KNOWN_CENTS_PER_MIN = OPENAI_CENTS_PER_MIN

    const val CONFIRM_CENTS = 10.0
    const val CONFIRM_MINUTES = 10.0

    /**
     * True when [providerId]'s per-minute cost is a PUBLISHED, pinned rate (so the estimate is a real
     * bound). False for a provider whose price we could not pin (Gemini audio input) — its
     * [centsPerMinute] is only an illustrative floor and its jobs always force the confirm gate.
     * null (on-device) is free, i.e. a known price of 0.
     */
    fun isPriceKnown(providerId: ProviderId?): Boolean = providerId != ProviderId.GEMINI

    /** ¢/min for [providerId]; null (on-device) is free; an unpriced provider uses the dearest known rate. */
    fun centsPerMinute(providerId: ProviderId?): Double = when (providerId) {
        ProviderId.OPENAI -> OPENAI_CENTS_PER_MIN
        ProviderId.ELEVENLABS -> ELEVENLABS_CENTS_PER_MIN
        ProviderId.SONIOX -> SONIOX_CENTS_PER_MIN
        ProviderId.GEMINI -> MOST_EXPENSIVE_KNOWN_CENTS_PER_MIN // UNKNOWN audio-input price -> illustrative floor
        null -> 0.0
    }

    fun minutes(byteLength: Long): Double = byteLength / (BYTES_PER_SECOND * 60.0)

    fun estimatedCents(byteLength: Long, providerId: ProviderId?): Double =
        minutes(byteLength) * centsPerMinute(providerId)

    fun needsConfirmation(byteLength: Long, providerId: ProviderId?): Boolean =
        // An unpublished price is not bounded by the dearest KNOWN rate, so a real job on such a
        // provider ALWAYS confirms — the gate can never be silently skipped on a possibly-low estimate.
        (byteLength > 0 && !isPriceKnown(providerId)) ||
            estimatedCents(byteLength, providerId) >= CONFIRM_CENTS || minutes(byteLength) >= CONFIRM_MINUTES

    /** Pre-flight bridge: decoded PCM16 @16 kHz mono is exactly 32 bytes/ms. */
    fun bytesForDuration(durationMs: Long): Long = durationMs * (BYTES_PER_SECOND / 1000L)
}
