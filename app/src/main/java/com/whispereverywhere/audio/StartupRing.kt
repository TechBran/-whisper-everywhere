package com.whispereverywhere.audio

/**
 * THE STARTUP RING (4.4.0 startup amendment, Task S2) — the audio captured between the tap and
 * the engine being ready to receive it.
 *
 * ## Why it exists
 *
 * Owner report, 2026-09-10: *"if I tap the transcribe button and start speaking right away,
 * sometimes the first chunks, like, first maybe couple seconds of what I say gets cut off."*
 *
 * `docs/superpowers/research/2026-09-10-startup-cutoff-investigation.md` measured it and found no
 * guard dropping that audio: it was **never captured**. `startAudioInput()` — the call that
 * constructs `AudioRecord` and spawns the capture thread — was nested inside the engine's
 * `onOpen()`, and `LocalWhisperEngine.onOpen` waits for the native model context. Cold that wait
 * is **4,107 ms** on the shipped npu-turbo tier (§3a, `capture-yt-84-flatline-0903-1936.txt`,
 * 19:29:48.854 -> 52.961) and 237 ms-11,672 ms on CPU small, and the ready haptic fired at the
 * tap. So the app buzzed "listening" and kept the microphone shut for the whole load.
 *
 * S2 opens the recorder FIRST and runs `connect()` beside it. Everything the capture thread reads
 * before the engine is ready lands here; at `onOpen` the ring drains through the SAME `sendAudio`
 * path live audio uses, and live audio then continues straight through. Nothing about any engine
 * changes — `sendAudio` was already unconditional and `transcriptionEngine` was already assigned
 * before `connect()` (investigation §2, facts 1 and 2).
 *
 * The precedent is in tree, one layer too low: Gemini's 2 s pre-setup ring
 * (`transcription/live/GeminiRealtimeProtocol.kt`) is this exact mechanism behind the transport's
 * `bootstrapped` gate, so it covers 0.13 s of one provider's setup and nothing of the tap window.
 * This is the same ring at the capture seam, where it covers every tier and both sources.
 *
 * ## The cap, and which end overflow eats
 *
 * [CAPACITY_MS] is 6 s — the measured 4,107 ms worst case with margin — which at 16 kHz mono
 * PCM16 is [CAPACITY_BYTES] = 192 KB. Negligible beside `LocalWhisperEngine`'s own
 * `MAX_BUFFER_BYTES` (960,000 B), which the engine already tolerates.
 *
 * On overflow the **OLDEST** audio goes (the amendment's ruling, against the investigation's
 * initial suggestion of "stop buffering"): once the ring is full the user is still talking, and
 * the words they are saying now are the ones the engine is about to receive. A chunk that could
 * not fit even in an empty ring is refused WHOLE rather than emptying the ring for audio that
 * still would not fit.
 *
 * ## Threading
 *
 * Every mutator is `synchronized` on the instance. In production the contention is nil — the
 * capture thread appends and drains, Main only [clear]s at session start and [drainAll]s at stop
 * BEHIND the capture joins — and at 31.25 Hz an uncontended monitor is free. It is a monitor and
 * not a volatile-field dance because the byte tally and the queue must move together: a torn pair
 * would let the cap drift, and the cap is the only thing bounding this object's RAM.
 *
 * **The sink runs OUTSIDE the monitor.** [drainSlice] removes its slice under the lock and only
 * then calls the sink, because the sink is `sendAudio` plus a Silero probe call per chunk — tens
 * of milliseconds — and holding the monitor across it would make Main's [clear] wait on it.
 *
 * **The drain must run on the CAPTURE thread.** Not this class's rule to enforce, but the reason
 * it hands chunks back through a lambda instead of owning a thread: `VadProbeLifecycle` keeps ONE
 * shared direct buffer for the probe, and a second thread feeding it concurrently with the real
 * capture thread is teardown-bill T8 (torn frames) and T9 (cross-session LSTM contamination). See
 * `VadProbeLifecycle`'s KDoc and `StartupRingWiringPinTest`.
 *
 * ## The replayed stamps are the ORIGINAL ones
 *
 * [append] records the chunk's own capture `nowMs` and [drainSlice] hands it back unchanged. That
 * is the amendment's condition 3 and it is the conservative choice, not the convenient one: every
 * wall-clock term in `SileroEndpointer` is `nowMs - <a previously stamped nowMs>` (the hangover
 * against `tempEndMs`, the micro-pause promotion, the cadence floor against `lastCommitMs`), so
 * replaying the audio's true clock keeps that arithmetic about real time. `SegmentCapPolicy`
 * likewise measures against an anchor the service now sets at the TAP. The flatline trigger is a
 * frame COUNT, not a wall-clock age (`SileroEndpointer.flatRun`, machine.py DECISION 5), and the
 * speech-evidence gate is `evidenceFrames * FRAME_MS` — both indifferent. Re-stamping the replay
 * with `System.currentTimeMillis()` at drain time is what would break it: 6 s of audio would
 * arrive stamped inside ~2 s, and every dip in it would read as shorter than it was.
 *
 * Pure: no Android types, no logging, no clock. The diag LINES are built here as pure functions so
 * `StartupRingTest` can pin their bytes; the service emits them.
 */
class StartupRing(private val capacityBytes: Int = CAPACITY_BYTES) {

    private class Entry(val pcm: ByteArray, val amp: Int, val nowMs: Long)

    /**
     * The chunks, oldest first. References, not copies: `StreamingAudioRecorder` already hands out
     * a fresh `buffer.copyOf(read)` per chunk and `PlaybackAudioCapturer` the same, so the array
     * is the ring's to keep and a second copy would buy nothing (the same reasoning
     * `PreviewTeeEngine.sendAudio` records for holding the reference in its queue). The
     * investigation's seam sketch said "preallocated"; the allocation the ring actually adds is
     * one deque node per chunk, and removing that would mean a copy per chunk instead.
     */
    private val queue = ArrayDeque<Entry>()

    private var bytes = 0
    private var droppedBytesTotal = 0

    /**
     * Capture thread. Appends one chunk, dropping the oldest as needed to stay under the cap.
     *
     * @return the bytes dropped by THIS call — 0 in the normal case. The caller owns the diag
     *   line, and emits [overflowLine] once per session rather than once per dropped chunk.
     */
    @Synchronized
    fun append(pcm: ByteArray, amp: Int, nowMs: Long): Int {
        if (pcm.isEmpty()) return 0
        if (pcm.size > capacityBytes) {
            // Refused whole. Emptying the ring for a chunk that still would not fit would trade
            // every buffered word for nothing.
            droppedBytesTotal += pcm.size
            return pcm.size
        }
        var dropped = 0
        while (bytes + pcm.size > capacityBytes && queue.isNotEmpty()) {
            val oldest = queue.removeFirst()
            bytes -= oldest.pcm.size
            dropped += oldest.pcm.size
        }
        queue.addLast(Entry(pcm, amp, nowMs))
        bytes += pcm.size
        droppedBytesTotal += dropped
        return dropped
    }

    @Synchronized
    fun isEmpty(): Boolean = queue.isEmpty()

    @Synchronized
    fun chunkCount(): Int = queue.size

    @Synchronized
    fun byteSize(): Int = bytes

    /** Bytes discarded at the cap since the last [clear] — the session's total. */
    @Synchronized
    fun droppedBytes(): Int = droppedBytesTotal

    /**
     * Capture thread, once per live chunk. Hands the oldest [maxChunks] to [sink] in capture order
     * with their ORIGINAL amplitudes and stamps, and returns how many it handed over.
     *
     * PACED, and that pacing is the whole point: see [DRAIN_CHUNKS_PER_TICK].
     */
    fun drainSlice(maxChunks: Int, sink: (ByteArray, Int, Long) -> Unit): Int {
        val slice = take(maxChunks)
        for (entry in slice) sink(entry.pcm, entry.amp, entry.nowMs)
        return slice.size
    }

    /**
     * Everything that is left, in order. The stop path's flush, and Main's only drain — it runs
     * BEHIND the capture joins, so there is no live drain to interleave with by then.
     */
    fun drainAll(sink: (ByteArray, Int, Long) -> Unit): Int = drainSlice(Int.MAX_VALUE, sink)

    @Synchronized
    private fun take(maxChunks: Int): List<Entry> {
        if (maxChunks <= 0 || queue.isEmpty()) return emptyList()
        val n = if (maxChunks < queue.size) maxChunks else queue.size
        val out = ArrayList<Entry>(n)
        repeat(n) {
            val entry = queue.removeFirst()
            bytes -= entry.pcm.size
            out.add(entry)
        }
        return out
    }

    /** Session start and every session-exit path: nothing may replay into the next session. */
    @Synchronized
    fun clear() {
        queue.clear()
        bytes = 0
        droppedBytesTotal = 0
    }

    companion object {
        /** 16 kHz mono PCM16 — `StreamingAudioRecorder.SAMPLE_RATE` x 2 bytes. */
        const val BYTES_PER_SECOND = 32_000

        /**
         * The measured 4,107 ms cold npu-turbo load with margin (investigation §3a). Not larger:
         * the ring is also what the paced drain has to catch up on, and every extra second is
         * another ~330 ms of replay before the transcript is level with the voice.
         */
        const val CAPACITY_MS = 6_000L

        /** [CAPACITY_MS] of [BYTES_PER_SECOND] = 192 KB. Pinned against each other by the test. */
        const val CAPACITY_BYTES = 192_000

        /**
         * Replayed chunks per LIVE chunk — the amendment's condition 3, and the one number that
         * keeps the drain from being strictly worse than the bug.
         *
         * A full 6 s ring is 187 frames of Silero probe work at a measured p50 of 2.3-2.4 ms
         * (`docs/PLAY-LISTING.md:316`) — ~430 ms of solid capture-thread work — on the same thread
         * that must keep calling `record.read()` against an `AudioRecord` ring that overflows in
         * >=128 ms (`util/StreamingAudioRecorder.kt`: `max(getMinBufferSize, 4096)` bytes).
         * Dumping the ring in one pass would lose audio in the MIDDLE of the session opening: a
         * stutter, worse than the truncation being fixed.
         *
         * At 4 the capture thread spends 5 probe calls per 32 ms read period — ~11.5 ms at p50,
         * under 31 ms even at the measured p99 of 6.1 ms — and the backlog shrinks by 3 chunks
         * (96 ms of audio) per 32 ms tick, so the measured 4,107 ms worst case is level again
         * ~1.4 s after the cue and a full 6 s ring ~2.0 s after it.
         */
        const val DRAIN_CHUNKS_PER_TICK = 4

        /** Bytes of 16 kHz mono PCM16 as milliseconds. */
        fun msOf(bytes: Int): Long = bytes.toLong() * 1000L / BYTES_PER_SECOND

        /**
         * ONE line per session, at the FIRST drop — not one per dropped chunk, which at 31.25 Hz
         * would be a log flood that buries the fact it is reporting. Numbers only, like every
         * diag line in this app.
         */
        fun overflowLine(firstDropMs: Long): String =
            "startup ring: FULL at ${CAPACITY_MS}ms - dropping the oldest audio (first drop ${firstDropMs}ms)"

        /** ONE line at `onOpen`, naming what the ring held and the session's TOTAL dropped audio. */
        fun drainLine(chunks: Int, bufferedMs: Long, droppedMs: Long): String =
            "startup ring: drain chunks=$chunks buffered=${bufferedMs}ms dropped=${droppedMs}ms " +
                "pace=$DRAIN_CHUNKS_PER_TICK"

        /** The stop path's flush of a backlog the paced drain had not caught up on yet. */
        fun stopFlushLine(chunks: Int, ms: Long): String =
            "startup ring: stop flush chunks=$chunks ms=$ms"

        /**
         * The same flush at a SOURCE SWITCH, and a separate line because it answers a different
         * question in the log: this audio is the OLD source's, committed on the old source's side
         * of the boundary, and a reader chasing "why is there mic audio in a device-audio
         * transcript?" needs to see that it was not.
         */
        fun switchFlushLine(chunks: Int, ms: Long): String =
            "startup ring: switch flush chunks=$chunks ms=$ms"
    }
}

/**
 * THE STARTUP SEAM's routing decision (4.4.0 startup amendment, Task S2) — pure, so
 * `onAudioChunk`'s new three-way head is executable in a JVM test instead of only describable in
 * a source pin.
 *
 * The service calls exactly this function, and `StartupRingTest`'s
 * `aFullRingDrainingUnderLiveAudioDeliversEveryChunkExactlyOnceInOrder` drives it with the real
 * [StartupRing] to show the exactly-once, in-order property the amendment requires.
 */
internal object StartupSeam {

    enum class Route {
        /** The engine is not ready: the chunk goes into the ring and the visuals still paint. */
        BUFFER,

        /** Ready, with a backlog: drain a paced slice, then queue this chunk behind what is left. */
        DRAIN,

        /** Ready and level: the pre-S2 path, byte for byte. */
        LIVE,
    }

    fun route(engineReady: Boolean, ringEmpty: Boolean): Route = when {
        !engineReady -> Route.BUFFER
        !ringEmpty -> Route.DRAIN
        else -> Route.LIVE
    }
}
