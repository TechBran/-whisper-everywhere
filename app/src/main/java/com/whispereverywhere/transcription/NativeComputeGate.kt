package com.whispereverywhere.transcription

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-global serialization for native whisper work.
 *
 * The app runs whisper on TWO independent native contexts on TWO single-thread executors, each
 * inside its own foreground service: the bubble/dictation path ([LocalWhisperEngine]) and the
 * batch file path ([com.whispereverywhere.transcription.batch.BatchTranscriber]). Nothing above
 * this gate serialized them across the service boundary — a user who starts a file job and then
 * taps the bubble to dictate could drive two `whisper_full` calls concurrently. That is unsafe:
 *
 *  - **GPU (ggml OpenCL / Adreno):** concurrent submits on the shared command queue are not
 *    thread-safe, and concurrent risky-first computes race the [GpuPolicy] crash sentinel — thread
 *    A can clear the compute sentinel and write validated=true while thread B is still mid-compute,
 *    so a crash from B's compute leaves no sentinel and validated=true, and the next launch
 *    re-trials GPU and crashes again: the exact crash LOOP the sentinel exists to prevent.
 *  - **CPU:** two full contexts double the KV/compute buffers inside a foreground service (OOM
 *    risk on large model tiers).
 *
 * Both native services construct the SAME [WhisperNativeBackend] singleton, so holding this one
 * lock around every load/transcribe/release in that backend serializes ALL native whisper calls
 * process-wide, across both executors — exactly one native call in flight at a time. The lock is
 * released BETWEEN calls, so a bubble segment still interleaves between a batch job's per-chunk
 * transcribes; it only forbids genuine overlap.
 *
 * Fair ordering ([ReentrantLock] fairness) so a steady stream of batch chunks cannot starve a
 * waiting bubble segment indefinitely. Reentrant so a single native call that re-enters the backend
 * (it does not today, but the contract stays safe) cannot self-deadlock.
 */
object NativeComputeGate {
    private val lock = ReentrantLock(/* fair = */ true)

    /** Runs [block] with the process-global native lock held. */
    fun <T> serialized(block: () -> T): T = lock.withLock(block)
}
