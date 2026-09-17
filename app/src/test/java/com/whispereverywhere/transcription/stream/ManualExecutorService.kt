package com.whispereverywhere.transcription.stream

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * An executor that HOLDS what is posted until the test says run — the shape a test needs to put a
 * warm, an open and a handful of audio chunks on the previewer's queue in the order the service
 * posts them and only then let the single thread take them in that order. `SameThreadExecutorService`
 * runs everything inline, which settles the engine at every call and cannot show the window
 * between a posted load and a landed one; this one shows exactly that window.
 *
 * Strictly FIFO, like the real `stream-preview` executor (StreamingPreviewEngine.kt:113-115,
 * `Executors.newSingleThreadExecutor`): [runAll] drains from the head, and tasks a task posts
 * while running join the tail.
 */
class ManualExecutorService : AbstractExecutorService() {
    val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { tasks.addLast(command) }

    /** Runs every queued task in FIFO order, including any a running task posts. */
    fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }

    /** Runs exactly one task — the head — so a test can stop between a warm and an open. */
    fun runOne() { tasks.removeFirst().run() }

    override fun shutdown() = Unit
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
