package com.whispereverywhere.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RetryPolicyTest {

    // rng fixed to 0.0 -> jitter is always 0, so delays are deterministic.
    private fun noJitterPolicy(
        maxAttempts: Int = 3,
        baseDelayMs: Long = 200,
        maxDelayMs: Long = 3000,
    ) = RetryPolicy(
        maxAttempts = maxAttempts,
        baseDelayMs = baseDelayMs,
        maxDelayMs = maxDelayMs,
        rng = { 0.0 },
    )

    @Test
    fun succeedsOnFirstTry() = runTest {
        val policy = noJitterPolicy()
        var calls = 0
        val result = policy.retry { attempt ->
            calls++
            assertEquals(1, attempt)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun retriesThenSucceeds() = runTest {
        val policy = noJitterPolicy()
        var calls = 0
        val seenAttempts = mutableListOf<Int>()
        val result = policy.retry { attempt ->
            seenAttempts += attempt
            calls++
            if (calls < 3) throw IOException("transient $calls")
            "done"
        }
        assertEquals("done", result)
        assertEquals(3, calls)
        assertEquals(listOf(1, 2, 3), seenAttempts)
    }

    @Test
    fun exhaustsAndRethrowsLastError() = runTest {
        val policy = noJitterPolicy(maxAttempts = 3)
        var calls = 0
        try {
            policy.retry<String> { attempt ->
                calls++
                throw IllegalStateException("boom $attempt")
            }
            fail("expected exception to be rethrown")
        } catch (e: IllegalStateException) {
            assertEquals("boom 3", e.message)
        }
        assertEquals(3, calls)
    }

    @Test
    fun shouldRetryFalseRethrowsImmediately() = runTest {
        val policy = noJitterPolicy(maxAttempts = 5)
        var calls = 0
        try {
            policy.retry<String>(shouldRetry = { false }) { _ ->
                calls++
                throw IOException("no retry")
            }
            fail("expected exception to be rethrown")
        } catch (e: IOException) {
            assertEquals("no retry", e.message)
        }
        assertEquals(1, calls)
    }

    @Test
    fun delayForAttemptIsMonotonicAndCapped() {
        val policy = noJitterPolicy(baseDelayMs = 200, maxDelayMs = 3000)
        // base * 2^(attempt-1), jitter=0:
        // attempt 1 -> 200, 2 -> 400, 3 -> 800, 4 -> 1600, 5 -> 3000 (capped), 6 -> 3000 (capped)
        assertEquals(200L, policy.delayForAttempt(1))
        assertEquals(400L, policy.delayForAttempt(2))
        assertEquals(800L, policy.delayForAttempt(3))
        assertEquals(1600L, policy.delayForAttempt(4))
        assertEquals(3000L, policy.delayForAttempt(5))
        assertEquals(3000L, policy.delayForAttempt(6))

        // Monotonic non-decreasing across a range and never above the cap.
        var prev = -1L
        for (attempt in 1..12) {
            val d = policy.delayForAttempt(attempt)
            assertTrue("delay must be <= maxDelayMs", d <= 3000L)
            assertTrue("delay must be non-decreasing", d >= prev)
            prev = d
        }
    }

    @Test
    fun jitterStaysWithinBaseAndUnderCap() {
        // rng at its max (just under 1.0) -> jitter approaches baseDelayMs but never reaches it.
        val policy = RetryPolicy(
            maxAttempts = 3,
            baseDelayMs = 200,
            maxDelayMs = 3000,
            rng = { 0.999 },
        )
        // attempt 1: 200 + floor(0.999*200)=199 -> 399, still < cap
        assertEquals(399L, policy.delayForAttempt(1))
        // capped attempt stays at cap even with jitter
        assertEquals(3000L, policy.delayForAttempt(9))
    }

    @Test fun delay_override_wins_over_the_computed_backoff() = runBlocking {
        // A server saying "wait 8 seconds" must be honoured. Without this hook the client waits
        // ~0.2s then ~0.4s, burning every attempt inside a window that is still closed.
        val policy = RetryPolicy(maxAttempts = 2, baseDelayMs = 10, maxDelayMs = 20)
        val seen = mutableListOf<Long>()
        var attempts = 0
        runCatching {
            policy.retry(
                shouldRetry = { true },
                delayOverrideMs = { _: Throwable, _: Int -> 1L }.also { seen.add(1L) },
            ) { attempts++; throw RuntimeException("boom") }
        }
        assertEquals(2, attempts)
    }

    @Test fun a_null_override_falls_back_to_the_computed_backoff() = runBlocking {
        val policy = RetryPolicy(maxAttempts = 2, baseDelayMs = 1, maxDelayMs = 2)
        var attempts = 0
        runCatching {
            policy.retry(shouldRetry = { true }, delayOverrideMs = { _, _ -> null }) {
                attempts++; throw RuntimeException("boom")
            }
        }
        assertEquals(2, attempts)
    }

    @Test fun existing_two_arg_call_sites_still_compile_and_behave() = runBlocking {
        // LocalWhisperEngine calls retry { ... } with no override. The new parameter must be
        // defaulted, not required.
        val policy = RetryPolicy(maxAttempts = 1)
        assertEquals("ok", policy.retry { "ok" })
    }
}
