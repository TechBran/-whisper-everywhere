package com.whispereverywhere.transcription.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Release-audit Minor A: [ExecutorReconnectScheduler] owns a daemon executor but exposed no
 * shutdown, so FloatingBubbleService.onDestroy only nulled the field and leaked the
 * "realtime-reconnect" thread. shutdown() must now stop the executor; schedule() must still delegate.
 */
class ExecutorReconnectSchedulerTest {

    /** Records the two calls under test; every other method is unused by the scheduler. */
    private class FakeScheduledExecutor : ScheduledExecutorService {
        var shutdownNowCalls = 0
        var lastScheduledDelayMs: Long? = null
        var lastScheduledUnit: TimeUnit? = null
        var lastTask: Runnable? = null

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            lastTask = command
            lastScheduledDelayMs = delay
            lastScheduledUnit = unit
            return NoopFuture
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdownNowCalls++
            return mutableListOf()
        }

        // --- unused by ExecutorReconnectScheduler ---
        override fun shutdown() = throw UnsupportedOperationException()
        override fun isShutdown() = throw UnsupportedOperationException()
        override fun isTerminated() = throw UnsupportedOperationException()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = throw UnsupportedOperationException()
        override fun <T : Any?> schedule(callable: Callable<T>, delay: Long, unit: TimeUnit): ScheduledFuture<T> =
            throw UnsupportedOperationException()
        override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()
        override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()
        override fun execute(command: Runnable) = throw UnsupportedOperationException()
        override fun <T : Any?> submit(task: Callable<T>) = throw UnsupportedOperationException()
        override fun <T : Any?> submit(task: Runnable, result: T) = throw UnsupportedOperationException()
        override fun submit(task: Runnable) = throw UnsupportedOperationException()
        override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>) = throw UnsupportedOperationException()
        override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit) =
            throw UnsupportedOperationException()
        override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>) = throw UnsupportedOperationException()
        override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit) =
            throw UnsupportedOperationException()

        private object NoopFuture : ScheduledFuture<Any?> {
            override fun getDelay(unit: TimeUnit) = 0L
            override fun compareTo(other: Delayed?) = 0
            override fun cancel(mayInterruptIfRunning: Boolean) = false
            override fun isCancelled() = false
            override fun isDone() = false
            override fun get(): Any? = null
            override fun get(timeout: Long, unit: TimeUnit): Any? = null
        }
    }

    @Test fun schedule_delegates_to_the_executor() {
        val exec = FakeScheduledExecutor()
        val scheduler = ExecutorReconnectScheduler(exec)
        var ran = false
        scheduler.schedule(1500L) { ran = true }
        assertEquals("the delay is passed through", 1500L, exec.lastScheduledDelayMs)
        assertEquals("scheduled in milliseconds", TimeUnit.MILLISECONDS, exec.lastScheduledUnit)
        exec.lastTask?.run()
        assertTrue("the scheduled runnable invokes the delegated task", ran)
    }

    @Test fun shutdown_calls_shutdownNow_exactly_once() {
        val exec = FakeScheduledExecutor()
        val scheduler = ExecutorReconnectScheduler(exec)
        scheduler.shutdown()
        assertEquals("onDestroy must stop the daemon executor", 1, exec.shutdownNowCalls)
    }
}
