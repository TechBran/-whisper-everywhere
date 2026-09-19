package com.whispereverywhere.transcription.speakers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * ONE committed chunk's voices, fingerprinted and numbered — off the whisper thread (4.10 Task 3,
 * spec §3.2 steps 2-4 and §3.3).
 *
 * It is the only object in the feature that owns a thread, and the reason it exists is the
 * arithmetic in spec §3.3: the commit floors (`CommitCadencePolicy`, 6 000 / 8 000 ms) were
 * measured with the whisper thread doing whisper's work and nothing else. So the caller —
 * `LocalWhisperEngine`, on its single native executor, BELOW its own `resolve` — hands over the
 * chunk's samples and the VAD geometry and walks away. Nothing here can delay a word of text: by
 * the time [assign] is called the text has already been delivered.
 *
 * ### The per-WINDOW rule, and the three fates of a window
 *
 * For each [SpeakerWindow] of the chunk, in chunk order — since the 2026-09-18 late session that
 * is one per SENTENCE in nearly every segment, not one per VAD segment ([SpeakerSpans.windows]):
 *
 *  1. **Shorter than [SpeakerTracker.MIN_EMBED_SECONDS]** — never fingerprinted. It inherits the
 *     label of the segment before it ([SpeakerTracker.currentSpeaker], which is 0 before anything
 *     has been assigned and therefore reads as "unlabelled"). Spec §3.2 step 2: an embedder on a
 *     fraction of a second does not fail, it answers a vector with no speaker in it, which is the
 *     worst of the three outcomes because it looks like an answer.
 *  2. **Fingerprinted, and the embedder answered** — [SpeakerTracker.assign] decides, and its
 *     three-band rule crossed with its three duration gates is where every judgement the user can
 *     see is made. A segment between [SpeakerTracker.MIN_EMBED_SECONDS] and
 *     [SpeakerTracker.MIN_OPEN_SECONDS] is in the MATCH-ONLY tier (session 4): it can take an
 *     existing speaker's number on a confident match, but it can never open one, confirm one or
 *     teach one.
 *  3. **Fingerprinted, and the embedder answered null** — the model is missing, refused, or
 *     native code threw. The label is left exactly where it was. A session on a device that
 *     cannot load the model loses LABELS, never text.
 *
 * ### WHAT A CHUNK NOW COSTS, and the one thing that can be lost by it
 *
 * [SpeakerSpans.LONG_SEGMENT_SECONDS] fell to 2.0 s and [SpeakerSpans.MIN_WINDOW_SECONDS] to
 * 1.0 s on 2026-09-18 late, so the count this loop runs is no longer "VAD segments, plus a slice
 * or two on the rare long one" — it is **roughly the number of SENTENCES in the chunk**. On the
 * owner's Tab a fingerprint is 130-300 ms, so a five-sentence chunk is 0.7-1.5 s of embedding
 * where it used to be one or two. All of it is on this object's own thread, below text that has
 * already been delivered, so nothing about the commit floors or a word of transcript moves.
 *
 * The ONE place it can be felt is the stop tap. [awaitIdle] is called there with the service's
 * `SPEAKER_DRAIN_MS` (2 500 ms), so a final chunk carrying more than about **eight windows** can
 * still be embedding when the fence expires — and then, exactly as before the fence existed, that
 * chunk's ids land on a detached sink and the last thing said in the session wears the previous
 * speaker's number. That is the ACCEPTED TRADE for now: a sentence-accurate boundary everywhere
 * against a last chunk that may go unlabelled on a long uninterrupted finish. It is bounded (text
 * is never involved), it is measurable from `embedMs=` in the diag line against the window count
 * beside it, and the fence is the number to raise if the field says it bites.
 *
 * And then, ONCE per chunk after all of them, [SpeakerTracker.endChunk] runs the merge pass and
 * whatever it moved rides out on the same callback as `remaps`. That ordering is the design of
 * spike session 2 — online, then refine — and this class is the only object that knows where a
 * chunk ends.
 *
 * ### The slice is on the ORIGINAL timeline, and it is clamped
 *
 * [SpeakerWindow.origStart] / [SpeakerWindow.origEnd] index the raw buffer the engine passed to
 * the native layer, which is the audio a speaker actually spoke — the stitched buffer whisper saw
 * carries 100 ms of injected silence between segments and does not outlive the JNI call. Those
 * bounds come from a PROCESS-GLOBAL snapshot (`WhisperNative.lastVadSegments`), so a stale one can
 * name samples this buffer does not have; the range is clamped rather than trusted, because the
 * alternative is an `IndexOutOfBoundsException` on the one thread the session's labels depend on.
 *
 * ### Nothing escapes this object but ids
 *
 * [onAssigned] carries a [SpeakerAssignment]: the seq, one id per fingerprint window, the latch,
 * and the numbers the device session measures. It carries **no text** — and cannot, because
 * [SpeakerAssignment] has no field for any. That is deliberate: the callback's first consumer is
 * a diagnostic line, and the product promise is that transcript content never reaches a log.
 *
 * ### Threading, stated exactly
 *
 * One single-thread executor named `speaker-embed`, one [VoicePrints] and one [SpeakerTracker],
 * all confined to that thread — which is what makes the tracker's "not synchronised" honest. Every
 * task is wrapped: an exception escaping a task would cost the session its embed thread, for a
 * LABEL, on a path whose text is already delivered. [release] frees the model ON that thread,
 * after whatever it had queued, and then shuts the executor down.
 *
 * ### Lifetime
 *
 * ONE per session. The tracker is born with this object, so the spec's *"speaker numbers are per
 * session and restart at 1"* is a property of the object graph rather than of a reset site anyone
 * could forget. `FloatingBubbleService` builds it at session start and releases it in
 * `teardownRealtime` — the convergence point of every session exit — and re-points the cached
 * engine at it every session, including at null (`SpeakerWiringPinTest`).
 *
 * ### The spike dump (4.10 spike session 2)
 *
 * [spike] non-null adds ONE thing to the loop below: every fingerprint that produced a vector is
 * appended to a jsonl, on this thread, flushed per chunk. It exists because the spike's first
 * session settled the cost question and lost the quality one — *"no single pair of thresholds
 * separates them"* — and the five changes that answer it are to be tried offline on dumped
 * embeddings rather than on the owner, one build per threshold pair. [SpeakerSpikeDump] carries
 * every rule about the files; this class only decides WHEN a row exists, and a row exists exactly
 * when a segment was fingerprinted and the embedder answered. Null is the shipping shape.
 */
class SpeakerAssigner(
    private val voices: VoicePrints,
    private val onAssigned: (SpeakerAssignment) -> Unit,
    private val tracker: SpeakerTracker = SpeakerTracker(),
    /** Injectable so a test can make the embed cost exact arithmetic. Nanoseconds. */
    private val clockNs: () -> Long = System::nanoTime,
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, THREAD_NAME) },
    /**
     * The spike dump's destination, or null for no dump at all (4.10 spike session 2). The dump
     * itself is built on the embed thread at the first fingerprint, from THIS assigner's tracker,
     * so its header can never describe a band the session did not run under.
     */
    private val spike: SpeakerSpikeDirs? = null,
) {

    /**
     * Queues one committed chunk. Returns immediately — always.
     *
     * [samples] is the RAW chunk the native layer transcribed and [windows] its fingerprint
     * windows, in chunk order, as built by [SpeakerSpans.windows] from
     * `WhisperNative.lastVadSegments` and `lastWhisperSegments`. The caller passes the SAME list
     * it cut the chunk's spans against: the ids published below are indexed by position in it.
     *
     * An empty [windows] is dropped in silence and produces NO callback. Spec §2 forbids reading
     * "no segments" as "one speaker": there is no timeline to attribute anything to, so there is
     * also no `confirmed` verdict to publish about it.
     */
    fun assign(seq: Long, samples: FloatArray, windows: List<SpeakerWindow>) {
        if (windows.isEmpty() || samples.isEmpty()) return
        // A rejected submission is the shutdown race and nothing else: a segment resolving out of
        // the native executor after teardown already detached this assigner. Silent by design.
        runCatching {
            executor.execute {
                runCatching { fingerprint(seq, samples, windows) }
            }
        }
    }

    /**
     * Blocks the CALLING thread until everything already queued on the embed thread has run, or
     * [timeoutMs] elapses; true if it drained. MUST be called off the main thread.
     *
     * It exists for ONE moment — the stop tap — and for one reason. The final chunk's [assign] is
     * submitted at the very end of `LocalWhisperEngine.runSegment`, on the same native-executor
     * pass that lets the ENGINE's `awaitIdle` fence return; so the service's finalize, which waits
     * on that fence and nothing else, used to run flush → close → snapshot while the last chunk's
     * embedding was still ~300 ms out on this thread. Its ids then landed on a detached sink and
     * were dropped, and the last thing said in the session went out to the field, the clipboard
     * and history wearing the PREVIOUS speaker's number — and, if that chunk was also the one that
     * confirmed the second speaker, the session rendered as plain 4.9 text with no paragraph and
     * no label anywhere. This is the second fence, and it is the same barrier task the engine uses:
     * a single-thread FIFO executor is idle of prior work exactly when a task submitted behind it
     * runs.
     *
     * What it guarantees is stronger than "the executor is idle": [onAssigned] is invoked INSIDE
     * the task it belongs to, so every assignment of the session has already been *published* when
     * this returns. For the production consumer that publication is a `Dispatchers.Main` post from
     * this thread, which therefore sits in the main queue AHEAD of the continuation that resumes
     * the caller — so a caller that awaits this off Main and resumes on Main observes every
     * assignment, not merely their submission.
     *
     * Bounded, never load-bearing: on a timeout the caller loses labels for the last chunk exactly
     * as it did before this fence existed. Text is never involved.
     */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        try {
            executor.execute { latch.countDown() }
        } catch (t: RejectedExecutionException) {
            return true // already released — nothing can still be in flight
        }
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Ends this assigner. The model is freed on the embed thread — after any work already queued,
     * so a release can never run underneath an in-flight `compute` — and the executor stops.
     * Safe to call twice; safe to call with work outstanding.
     *
     * The spike dump is closed in the SAME task, on the same thread that wrote every line of it:
     * the last chunk of a session is queued behind the stop tap, so closing the file anywhere else
     * would close it under a write that has not happened yet.
     */
    fun release() {
        runCatching {
            executor.execute {
                runCatching { voices.release() }
                runCatching { dump?.close() }
                dump = null
            }
        }
        runCatching { executor.shutdown() }
    }

    // ------------------------------------------------------------------ on the embed thread

    private fun fingerprint(seq: Long, samples: FloatArray, windows: List<SpeakerWindow>) {
        val ids = ArrayList<Int>(windows.size)
        val best = ArrayList<Float>(windows.size)
        val durations = ArrayList<Float>(windows.size)
        var embedNs = 0L
        // Whether THIS chunk paid the session's one-time model load. [VoicePrints] loads lazily on
        // its first call, so the first fingerprint of a session costs 40.3 MB of asset read and an
        // ONNX session init ON TOP of one inference — hundreds of milliseconds, inside the same
        // `embedMs` number the device session reads CAM++'s per-segment cost off. Without this
        // flag chunk 1 is an unexplained outlier against a budget of "worst under 300 ms", and the
        // adapter's own load line cannot explain it: R8 strips it from the release build, which is
        // the only build the owner can install.
        val paidTheLoad = !hasEmbedded

        for ((index, window) in windows.withIndex()) {
            val from = window.origStart.coerceIn(0, samples.size)
            val to = window.origEnd.coerceIn(from, samples.size)
            val seconds = (to - from) / SAMPLE_RATE.toFloat()
            durations += seconds

            if (seconds < SpeakerTracker.MIN_EMBED_SECONDS) {
                // Fate 1: too short to carry a voice. It inherits, and reports no similarity —
                // NaN rather than 0, because 0 is a real reading (two orthogonal voices) and the
                // spike's `best=` column is read as a distribution.
                ids += tracker.currentSpeaker()
                best += Float.NaN
                continue
            }

            val slice = samples.copyOfRange(from, to)
            val startedNs = clockNs()
            val embedding = voices.embed(slice)
            val segmentNs = clockNs() - startedNs
            embedNs += segmentNs
            hasEmbedded = true

            if (embedding == null) {
                // Fate 3: no fingerprint. The label stays where it was.
                ids += tracker.currentSpeaker()
                best += Float.NaN
            } else {
                // Fate 2: the tracker decides. `lastBestSimilarity` is read immediately after,
                // on this thread, which is the only place it is defined to mean anything.
                val id = tracker.assign(embedding, seconds)
                val similarity = tracker.lastBestSimilarity
                ids += id
                best += similarity
                // The spike's row, and the ONLY place one is written: a vector exists, so the
                // offline tuner can re-decide this segment. It is written AFTER the tracker has
                // spoken, because `assigned`, `best` and `confirmed` are the shipped build's
                // verdict on this fingerprint and a candidate rule is scored against them.
                spikeDump()?.write(
                    SpikeFingerprint(
                        seq = seq,
                        seg = index,
                        durSec = seconds,
                        origStart = from,
                        origEnd = to,
                        assigned = id,
                        best = similarity,
                        confirmed = tracker.secondSpeakerConfirmed,
                        embedMs = segmentNs / 1_000_000L,
                        emb = embedding,
                    ),
                    slice,
                )
            }
        }

        // Per chunk, not per line: a crash, an OOM kill or a battery death must still leave every
        // completed chunk on disk, and those are the sessions worth reading.
        runCatching { dump?.flush() }

        // THE REFINE HALF, and it runs HERE because "after each chunk" is a thing only this object
        // knows (spike session 2). Every unconfirmed speaker that turns out to be within T_SAME of
        // a confirmed one is absorbed into it, and the ids just reported in `ids` above for an
        // absorbed speaker are now stale by exactly one map. They are not rewritten — this pass
        // cannot reach the sink — they are PUBLISHED, on the same callback, in the same chunk, so
        // that a reader which has already painted them can put them right. Empty for the
        // overwhelming majority of chunks, which is why it is a map and not a flag.
        val remaps = tracker.endChunk()

        onAssigned(
            SpeakerAssignment(
                seq = seq,
                // The VAD segments BEHIND those windows. Windows are emitted in segment order and
                // every segment produces at least one, so counting the distinct segment indices
                // is the segment count — and the diag line prints both, because `windows > segs`
                // is the only visible sign of how much of this chunk was cut per sentence.
                segs = windows.distinctBy { it.vadIndex }.size,
                ids = ids,
                remaps = remaps,
                // Read AFTER the merge pass: a chunk's verdict is the one that stands at the end
                // of it, not the one that stood mid-loop.
                confirmed = tracker.secondSpeakerConfirmed,
                stats = SpeakerAssignStats(
                    embedMs = embedNs / 1_000_000L,
                    best = best,
                    durationsSec = durations,
                    includesModelLoad = paidTheLoad && hasEmbedded,
                ),
            )
        )
    }

    /** Set by the first [VoicePrints.embed] of this assigner's life. Embed thread only. */
    private var hasEmbedded = false

    /**
     * This session's spike dump, or null when [spike] is null. Embed thread only, like everything
     * else in this half of the file.
     */
    private var dump: SpeakerSpikeDump? = null

    /**
     * The dump, built on FIRST use so that a session which never fingerprints anything never
     * touches the filesystem — and built HERE rather than at the construction site because the
     * header has to carry this tracker's own band, not the defaults a caller assumed.
     */
    private fun spikeDump(): SpeakerSpikeDump? {
        if (!SpeakerSpike.SPEAKER_SPIKE) return null
        dump?.let { return it }
        val dirs = spike ?: return null
        val built = SpeakerSpikeDump(
            dirs = dirs,
            model = SpeakerSpike.MODEL_ASSET,
            tSame = tracker.tSame,
            tNew = tracker.tNew,
            minEmbed = SpeakerTracker.MIN_EMBED_SECONDS,
            minMatch = SpeakerTracker.MIN_MATCH_SECONDS,
            minOpen = SpeakerTracker.MIN_OPEN_SECONDS,
            minUpdate = SpeakerTracker.MIN_UPDATE_SECONDS,
            recentK = tracker.recentK,
            confirmN = tracker.confirmN,
            cap = tracker.maxSpeakers,
            // Not tracker rules — the two that decide whether a ROW is a whole VAD segment or a
            // slice of one, which is what `seg`/`durSec`/`origStart`/`origEnd` mean since
            // session 4. SpikeJson.header's own KDoc carries the argument.
            longSegment = SpeakerSpans.LONG_SEGMENT_SECONDS,
            minWindow = SpeakerSpans.MIN_WINDOW_SECONDS,
        )
        dump = built
        return built
    }

    companion object {
        /**
         * The embed thread's name. It is asserted by test and read off a device trace and a
         * logcat thread column during the plan's device session, so it is a constant rather than
         * a string literal at the construction site.
         */
        const val THREAD_NAME: String = "speaker-embed"

        /** The app's one sample rate. The VAD bounds are samples; durations are seconds. */
        const val SAMPLE_RATE: Int = 16_000
    }
}

/**
 * What one committed chunk's voices came to — the whole of what crosses back out of the embed
 * thread (4.10 Task 3).
 *
 * @param seq the chunk's segment sequence number, so this joins `segment-timing:` and `queue:` on
 *        the one key every 3.7 diagnostic already shares.
 * @param segs how many VAD SEGMENTS produced the windows below. Equal to `ids.size` only for a
 *        chunk nothing was cut in; since the 2026-09-18 late session it is normally SMALLER than
 *        `ids.size`, because a multi-sentence segment of 2.0 s or more is fingerprinted per
 *        sentence. `windows - segs` is therefore no longer a rare event to be spotted but the
 *        per-chunk measure of how much sentence-level cutting this session is doing — and, read
 *        against `embedMs`, the column that says whether a chunk is near the finalize fence.
 * @param ids ONE id per FINGERPRINT WINDOW, in chunk order: 1-based, or **0** for a window that
 *        could not be attributed at all (no fingerprint and no previous speaker to inherit from).
 *        A reader must treat 0 as "unlabelled", never as speaker 1 — spec §2 forbids a label on a
 *        session that has not earned one.
 * @param remaps what the end-of-chunk merge pass changed, `merged id -> survivor id`, and empty
 *        for almost every chunk. An entry says that an id in [ids] — possibly in an EARLIER
 *        chunk's ids, which is the whole point — belongs to the survivor instead: the tracker
 *        decided that speaker was never a separate person. The pass runs after the loop, so ids
 *        and remaps in the SAME assignment can disagree; that is not a race, it is the
 *        online-then-refine design, and the reader's job is to apply the map.
 * @param confirmed the tracker's per-session latch (spike session 2): **two CONFIRMED speakers**,
 *        each having held the floor for [SpeakerTracker.CONFIRM_N] segments of at least
 *        [SpeakerTracker.MIN_OPEN_SECONDS]. Until it is true the panel shows no label at all and a
 *        one-speaker session is byte-for-byte today's output.
 * @param stats the numbers the device session measures, one row per segment.
 *
 * There is NO text field, by design: see [SpeakerAssigner]'s KDoc.
 */
data class SpeakerAssignment(
    val seq: Long,
    val segs: Int,
    val ids: List<Int>,
    val confirmed: Boolean,
    val stats: SpeakerAssignStats,
    val remaps: Map<Int, Int> = emptyMap(),
)

/**
 * The measurement half of a [SpeakerAssignment] — the three columns plan Task 4 sets the tracker's
 * thresholds from.
 *
 * @param embedMs the chunk's TOTAL embedding cost, milliseconds, excluding the windows that were
 *        never fingerprinted. The budget it is read against: median under 100 ms per window,
 *        worst under 300 ms. The split raises the COUNT per chunk, never the per-window cost, and
 *        all of it stays on the embed thread — which is why the finalize fence rose to 2.5 s and
 *        the commit floors did not move. Since the 2026-09-18 late session that count is roughly
 *        the chunk's SENTENCE count, so this is also the number that says how close the last
 *        chunk of a session came to the fence: see [SpeakerAssigner]'s KDoc for the trade.
 * @param best per window, the cosine similarity of its fingerprint against the CLOSEST known
 *        speaker at the moment it was assigned, or `NaN` when no similarity was measured — a
 *        window under the embed floor, a refused embedding, or the first speaker of a session,
 *        who has nobody to be compared with. This is the column `T_SAME` / `T_NEW` come from.
 * @param durationsSec per window, its length in seconds on the original timeline.
 * @param includesModelLoad true for the ONE chunk of a session whose [embedMs] also paid the
 *        lazy model load — 40.3 MB of asset read plus an ONNX session init, hundreds of
 *        milliseconds on top of one inference. It is here so that chunk is not read as CAM++
 *        being slow: it is the difference between a go and a no-go on the model.
 *
 * All three lists carry one entry per fingerprint window, always, so the columns are parallel to
 * `ids`.
 */
data class SpeakerAssignStats(
    val embedMs: Long,
    val best: List<Float>,
    val durationsSec: List<Float>,
    val includesModelLoad: Boolean = false,
)
