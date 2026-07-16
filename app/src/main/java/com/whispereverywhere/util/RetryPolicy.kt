package com.whispereverywhere.util

import kotlinx.coroutines.delay

/**
 * Pure, deterministic bounded-backoff retry helper.
 *
 * Applied at every fallible step (STT transcribe, model load, text generation) so the user only
 * sees an error after retries are exhausted. Determinism for tests comes from the injectable [rng].
 *
 * @param maxAttempts total number of tries including the first (>= 1).
 * @param baseDelayMs base backoff in ms; also the exclusive upper bound of the jitter window.
 * @param maxDelayMs hard cap applied to the computed delay.
 * @param rng returns a value in [0.0, 1.0); scaled by [baseDelayMs] to produce jitter.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 200,
    val maxDelayMs: Long = 3000,
    val rng: () -> Double = { Math.random() },
) {
    /**
     * Delay before the given 1-based [attempt]'s *next* retry:
     * min(baseDelayMs * 2^(attempt-1) + jitter, maxDelayMs), where jitter is in [0, baseDelayMs).
     *
     * The exponent is clamped so very large attempt numbers cannot overflow the shift; the result
     * is capped at [maxDelayMs] regardless.
     */
    fun delayForAttempt(attempt: Int): Long {
        val safeAttempt = if (attempt < 1) 1 else attempt
        // Cap the shift to avoid Long overflow; anything this large is already past the cap.
        val shift = (safeAttempt - 1).coerceAtMost(62)
        val exponential = if (baseDelayMs <= 0L) 0L else baseDelayMs shl shift
        // jitter in [0, baseDelayMs): rng() is in [0.0, 1.0), floored -> 0..baseDelayMs-1.
        val jitter = (rng() * baseDelayMs).toLong()
        val raw = if (exponential >= Long.MAX_VALUE - jitter) Long.MAX_VALUE else exponential + jitter
        return if (raw > maxDelayMs) maxDelayMs else raw
    }

    /**
     * Runs [block] with a 1-based attempt index, from 1 up to [maxAttempts]. On a thrown
     * [Throwable], if [shouldRetry] returns true and attempts remain, [delay]s for
     * [delayForAttempt] then retries; otherwise rethrows.
     */
    suspend fun <T> retry(
        shouldRetry: (Throwable) -> Boolean = { true },
        block: suspend (attempt: Int) -> T,
    ): T {
        var attempt = 1
        while (true) {
            try {
                return block(attempt)
            } catch (t: Throwable) {
                val hasMore = attempt < maxAttempts
                if (!hasMore || !shouldRetry(t)) throw t
                delay(delayForAttempt(attempt))
                attempt++
            }
        }
    }
}
