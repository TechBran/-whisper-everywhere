package com.whispereverywhere.transcription.speakers

import com.whispereverywhere.whisper.WhisperNative
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
 * All three are REMEMBERED for the retrospective pass, fates 1 and 3 with a null vector. They
 * cannot vote in a clustering and they are not meant to; they are there so the pass can rename
 * them, because it renumbers the id space and a window it never hears about keeps an id that
 * afterwards means somebody else. See [remember].
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
 * `SPEAKER_DRAIN_MS` (3 000 ms since spike session 6, because that fence now also contains the
 * final retrospective pass), so a final chunk carrying more than about **eight windows** can
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
 * ### THE SECOND LOOK: online for display, retrospective for truth (spike session 6)
 *
 * EVERY window is KEPT, under its [WindowKey] — all three fates, the two that produced no vector
 * included — and every [SpeakerReclusterer.RECLUSTER_EVERY_CHUNKS] chunks, plus once inside the
 * [awaitIdle] fence at finalize, all of them are clustered at once by [SpeakerReclusterer].
 * Session 6 is why: the online matcher is greedy and can lock onto a single id for a whole
 * conversation (*"one big run-on paragraph"*) while a retrospective clustering of the very same
 * fingerprints finds the speakers it merged. Three things then happen, in this order and on this
 * thread — and only when the pass found [SpeakerLabels.MIN_CONFIRMED_SPEAKERS] confirmed
 * speakers, because a pass that concluded less than the panel already stands on must not be
 * allowed to take it back:
 *
 *  1. **[SpeakerTracker.reseed]** — the tracker's speakers become the clusters, so the NEXT chunk
 *     is decided from the retrospective truth rather than from the state that went wrong;
 *  2. the kept fingerprints are rewritten into the cluster id space, so the next pass's
 *     `onlineId -> cluster` map cannot mix two numberings;
 *  3. **[onRelabel]** carries a [SpeakerRelabel] out — a label per window, which the sink applies
 *     exactly (`TranscriptSink.relabel`) and which raises the panel's latch when two speakers are
 *     confirmed. The latch is never lowered by a pass.
 *
 * The cost is O(k²) in the kept VECTORS, capped at
 * [SpeakerReclusterer.MAX_RECLUSTER_FINGERPRINTS] — a few hundred milliseconds at the cap, on
 * this thread, below text that was delivered long ago, exactly like the embedding beside it.
 * Past the cap the oldest vector is dropped and its window stays: it is the WINDOW that must
 * still be nameable, because step 1 renumbers the id space and a run the relabel misses keeps an
 * id that now means somebody else. [MAX_RETAINED_WINDOWS] is where even that stops.
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
    /**
     * THE SECOND LOOK's way out (spike session 6). Called on the embed thread, after a
     * retrospective pass, with a label for every fingerprint window of the session so far — the
     * production consumer hops to Main and hands it to `TranscriptSink.relabel`. Defaulted to
     * nothing so a test that is about the per-chunk pass does not have to care.
     */
    private val onRelabel: (SpeakerRelabel) -> Unit = {},
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
    /**
     * THE NPU TIER'S VAD, and the only native call this class makes besides the embedder
     * ([assignWholeChunk]). A LAMBDA rather than a direct `WhisperNative` call so that every test
     * in this file stays on a plain JVM: `WhisperNative`'s initialiser is
     * `System.loadLibrary("whisper_jni")`, which throws `UnsatisfiedLinkError` off a device — and
     * merely CONSTRUCTING this default does not touch that object, because a lambda's body runs
     * only when it is invoked.
     */
    private val segmenter: (FloatArray, String) -> IntArray = { pcm, path ->
        WhisperNative.vadSegmentsOf(pcm, path)
    },
) {

    /**
     * WHERE A CHUNK'S WINDOWS CAME FROM — and therefore whether the chunk's TEXT can be split
     * between two speakers (4.10, the Fold6 defect).
     *
     * [GEOMETRY] is the CPU and GPU tiers: whisper.cpp ran its VAD filter and its decoder, so the
     * chunk arrives with both a speech partition and per-segment text offsets, and each window
     * gets its own stretch of text. Nothing about it has changed.
     *
     * [WHOLE_CHUNK] is the NPU tier. The QNN decoder exposes no token or sentence timestamps, so
     * there is no way to say which words belong to which window — the audio can still be
     * fingerprinted per window, and is, but the chunk's committed text can only wear ONE label.
     * Speaker changes therefore land on chunk boundaries (6-8 s) instead of sentence boundaries,
     * which is the owner's ruling of 2026-09-19: *"at the chunk level … at least that would be
     * good enough."*
     */
    private enum class Route { GEOMETRY, WHOLE_CHUNK }

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
                runCatching {
                    // The VAD SEGMENTS behind those windows. Windows are emitted in segment order
                    // and every segment produces at least one, so counting the distinct segment
                    // indices is the segment count.
                    val segs = windows.distinctBy { it.vadIndex }.size
                    fingerprint(seq, samples, windows, Route.GEOMETRY, segs)
                }
            }
        }
    }

    /**
     * Queues one committed chunk that arrived with NO GEOMETRY — the NPU tier (4.10, the Fold6
     * defect). Returns immediately, like [assign], and runs everything below on the same
     * `speaker-embed` thread.
     *
     * `NpuWhisperBackend.lastGeometry` answers null while the NPU arm is live, and correctly: that
     * path runs its own encoder and decoder on the HTP and never calls whisper.cpp's VAD filter,
     * so there are no speech bounds to publish. The engine therefore skipped the assigner
     * entirely, and on an NPU-capable device — where the 4.3 one-tier rule offers no CPU rung —
     * the owner got no speaker changes at all. This route recovers what it can.
     *
     * What it does, in order:
     *  1. **A SECOND VAD RUN**, `WhisperNative.vadSegmentsOf(samples, vadModelPath)`, on this
     *     thread. It costs what the `VAD: … wallMs=` lines already report for the same work —
     *     about 60 ms for a 6-8 s chunk — and it is paid here, below delivered text, rather than
     *     on the whisper thread whose cadence floors were measured without it.
     *  2. **Windows** from those bounds ([SpeakerSpans.wholeChunkWindows]). There is ONE timeline
     *     on this route — [samples]' own — because nothing is stitched and nothing is swapped, so
     *     the "original" and "trimmed" offsets the CPU route carries separately are here the same
     *     number and are not pretended to be two.
     *  3. **The same fingerprinting, the same tracker, the same gates** as [assign]. The tracker
     *     is not forked and does not know which route fed it — and it is fed SPEECH seconds here
     *     exactly as it is there, because this route's coalescing is the one thing that can make
     *     a window span more time than it holds voice. See [SpeakerWindow.speechSamples] and
     *     [SpeakerSpans.MAX_COALESCE_GAP_SECONDS], which between them keep a pause from opening
     *     a speaker.
     *  4. **ONE id for the whole chunk** — the id of the window holding the most speech, ties to
     *     the earliest — published as [SpeakerAssignment.wholeChunkWindow]. The decoder gives no
     *     text offsets, so a chunk's text cannot be split between two speakers on this tier and
     *     the honest answer is the dominant voice in it.
     *
     * NO SPEECH PUBLISHES NOTHING. An empty segmentation — no speech, a missing or unloadable VAD
     * model, a failed pass — produces no callback at all, exactly as an empty `windows` does in
     * [assign]: spec §2 forbids reading "no segments" as "one speaker".
     *
     * [vadModelPath] must be the `VadModel.path()` the backend seam already uses. There is no
     * fallback path to invent here: a caller without one must not call this.
     */
    fun assignWholeChunk(seq: Long, samples: FloatArray, vadModelPath: String) {
        if (samples.isEmpty() || vadModelPath.isEmpty()) return
        runCatching {
            executor.execute {
                runCatching {
                    val raw = segmenter(samples, vadModelPath)
                    val windows = SpeakerSpans.wholeChunkWindows(raw)
                    if (windows.isEmpty()) return@runCatching
                    // `raw.size / 2` is the SPEECH SEGMENT count, which coalescing can make
                    // larger than the window count — the opposite of the geometry route, where
                    // splitting makes windows the larger of the two. Both are printed.
                    fingerprint(seq, samples, windows, Route.WHOLE_CHUNK, raw.size / 2)
                }
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
     * Since spike session 6 the barrier task is not empty: it runs the session's LAST
     * retrospective pass before it counts down, so what the caller is waiting for is every
     * assignment AND the final relabel. That is the whole reason the pass is there — the sink is
     * detached the instant this returns.
     *
     * What it guarantees is stronger than "the executor is idle": [onAssigned] and [onRelabel]
     * are invoked INSIDE the task they belong to, so every assignment of the session, and the
     * final relabel, have already been *published* when this returns. For the production consumer that publication is a `Dispatchers.Main` post from
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
            executor.execute {
                // THE FINAL RECLUSTER, and it belongs inside the fence rather than beside it
                // (spike session 6). The session's last words are also the ones the retrospective
                // pass has the most evidence about, and the sink is detached the moment this
                // fence returns — so a recluster published after it would land on nothing and the
                // delivery, the clipboard and history would ship the online labels while the
                // panel showed the corrected ones. Running it HERE, on the same FIFO thread,
                // behind the last chunk's embedding, is what makes those four agree.
                runCatching { recluster() }
                latch.countDown()
            }
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

    private fun fingerprint(
        seq: Long,
        samples: FloatArray,
        windows: List<SpeakerWindow>,
        route: Route,
        segs: Int,
    ) {
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
            // SPEECH seconds, never the wall span, and the two differ on exactly one route: the
            // NPU one, where a coalesced window spans the pause between the segments it joined
            // ([SpeakerWindow.speechSamples]). Everything below is a question about voice — is
            // there enough of it to fingerprint, may it match / open / teach a speaker, and which
            // window speaks for the chunk — so a pause must not be allowed to answer any of them.
            // On the geometry route `speechSamples` IS the span by construction, so the CPU and
            // GPU tiers read the same number they read in 4.10. The clamp is the same one `from`
            // and `to` get: a window naming samples this chunk does not have cannot claim seconds
            // it does not have either.
            val seconds = window.speechSamples.coerceIn(0, to - from) / SAMPLE_RATE.toFloat()
            durations += seconds

            if (seconds < SpeakerTracker.MIN_EMBED_SECONDS) {
                // Fate 1: too short to carry a voice. It inherits, and reports no similarity —
                // NaN rather than 0, because 0 is a real reading (two orthogonal voices) and the
                // spike's `best=` column is read as a distribution.
                val inherited = tracker.currentSpeaker()
                ids += inherited
                best += Float.NaN
                // It is REMEMBERED all the same, with no vector. See [remember]: a window the
                // retrospective pass never hears about keeps an id from before the next reseed
                // renumbers the id space, and a stale id in a renumbered space is somebody else.
                remember(WindowKey(seq, index), NO_VECTOR, seconds, inherited)
                continue
            }

            val slice = samples.copyOfRange(from, to)
            val startedNs = clockNs()
            val embedding = voices.embed(slice)
            val segmentNs = clockNs() - startedNs
            embedNs += segmentNs
            hasEmbedded = true

            if (embedding == null) {
                // Fate 3: no fingerprint. The label stays where it was — and, like fate 1, the
                // window is still remembered so the pass can rename it into the new id space.
                val inherited = tracker.currentSpeaker()
                ids += inherited
                best += Float.NaN
                remember(WindowKey(seq, index), NO_VECTOR, seconds, inherited)
            } else {
                // Fate 2: the tracker decides. `lastBestSimilarity` is read immediately after,
                // on this thread, which is the only place it is defined to mean anything.
                val id = tracker.assign(embedding, seconds)
                val similarity = tracker.lastBestSimilarity
                ids += id
                best += similarity
                // …and the SESSION keeps the fingerprint, because the retrospective pass is a
                // function of all of them at once (spike session 6).
                remember(WindowKey(seq, index), embedding, seconds, id)
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
                // The VAD segments behind those windows — the diag line prints both, because
                // `windows > segs` is the only visible sign of how much of this chunk was cut
                // per sentence (and `windows < segs`, on the NPU route, of how much of it was
                // coalesced).
                segs = segs,
                ids = ids,
                // THE CHUNK'S ONE ID, on the NPU route only. The window holding the most SPEECH
                // wins it — `durations` is speech seconds, not spans, which on this route is the
                // difference between the voice that spoke longest and the voice that happened to
                // sit next to the longest pause. A tie goes to the EARLIEST, which is
                // `maxByOrNull`'s own rule and is stated rather than inherited because it is the
                // difference between two labels on a chunk that alternates evenly. Null on the
                // geometry route, where the ids above each own their own stretch of text and
                // nothing has to be picked.
                wholeChunkWindow = when (route) {
                    Route.GEOMETRY -> null
                    Route.WHOLE_CHUNK -> dominant(durations)
                },
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

        // THE SECOND LOOK, every [SpeakerReclusterer.RECLUSTER_EVERY_CHUNKS] chunks and AFTER the
        // chunk's own ids have been published: online first, then the correction. Spike session 6.
        if (++chunksSinceRecluster >= SpeakerReclusterer.RECLUSTER_EVERY_CHUNKS) recluster()
    }

    /**
     * WHICH WINDOW SPEAKS FOR THE WHOLE CHUNK: the one holding the most speech, ties to the
     * EARLIEST (4.10, the NPU route).
     *
     * [durations] is `stats.durationsSec`, one entry per window in chunk order, so the answer is
     * an index into [SpeakerAssignment.ids] and into the chunk's remembered [WindowKey]s alike —
     * which is what lets the retrospective pass reach the run this index put on screen.
     *
     * Those entries are SPEECH seconds, not the windows' wall spans, and on this route the two
     * are different numbers: a coalesced window spans the pause it joined across. Ranking on the
     * span would hand the chunk to whichever voice happened to sit next to a silence — a window
     * holding 1.4 s of voice either side of a pause outranking one holding 3.0 s — which is the
     * opposite of the rule this function is named for. See [SpeakerWindow.speechSamples].
     *
     * Strictly `>`, so an even split between two windows names the FIRST. That is the arbitrary
     * half of the rule and it is arbitrary on purpose: what matters is that it is fixed, because
     * the alternative is a chunk whose label flips between two builds on the same audio.
     *
     * Null only for a chunk with no windows at all, which the callers already refuse.
     */
    private fun dominant(durations: List<Float>): Int? {
        if (durations.isEmpty()) return null
        var best = 0
        for (i in durations.indices) if (durations[i] > durations[best]) best = i
        return best
    }

    // ------------------------------------------------------------------ the retrospective pass

    /**
     * EVERY window of the session, newest last, whether or not it produced a vector. Embed
     * thread only.
     *
     * The three fates all land here, and that is the fix to a real defect rather than tidiness.
     * The pass renumbers the id space ([SpeakerTracker.reseed]) and the sink rewrites the runs it
     * names ([SpeakerRuns.applyWindowLabels]); a window the pass never hears about therefore
     * keeps an id issued BEFORE the renumbering, which after it usually denotes a different
     * person — a ghost `Speaker 3:` in the panel, the field, the clipboard and the history
     * sidecar. Keeping the short and the failed windows here costs a null vector each and closes
     * it: they never seed and never vote, they are labelled by the company they keep.
     */
    private val session = ArrayList<SpeakerReclusterer.Fp>(SpeakerReclusterer.MAX_RECLUSTER_FINGERPRINTS)

    /** How many entries of [session] still carry a vector — what the O(k²) cap is actually on. */
    private var vectorsHeld = 0

    /** Where in [session] to look for the oldest entry that still has a vector. Embed thread only. */
    private var oldestVector = 0

    /** Chunks since the last retrospective pass. Embed thread only. */
    private var chunksSinceRecluster = 0

    /** How many windows this session has taken, ever — the "is there anything new" test. */
    private var fingerprintsTaken = 0L

    /** [fingerprintsTaken] as it stood at the last pass, so a second fence publishes nothing. */
    private var reclusteredAt = -1L

    /**
     * The label each window is currently WEARING, so a pass can say how many it changed. Seeded
     * with the online id at the moment the window was assigned and rewritten by every pass, which
     * makes `changed=` in the diag line mean "windows whose label on screen just moved" rather
     * than "windows this pass had an opinion about".
     */
    private val shown = HashMap<WindowKey, Int>()

    /**
     * Keeps ONE window for the retrospective pass — [embedding] is [NO_VECTOR] for the two fates
     * that produced none.
     *
     * TWO bounds, and they are different bounds because the two costs are different:
     *
     *  - **[SpeakerReclusterer.MAX_RECLUSTER_FINGERPRINTS] vectors.** The clustering is O(k²) in
     *    the windows that can seed it, so past 600 the OLDEST VECTOR is dropped — the window
     *    itself stays, keyed and labelled, it just stops voting. Dropping the window instead
     *    (which is what this did) is what left a 25-minute-old run wearing an id from a
     *    numbering two reseeds ago.
     *  - **[MAX_RETAINED_WINDOWS] windows.** Keys are cheap but not free, and a session has no
     *    documented length limit. Past the bound the oldest are dropped outright and go back to
     *    keeping whatever label they last had — hours of text above the top of any panel, and
     *    the honest place to stop paying.
     */
    private fun remember(key: WindowKey, embedding: FloatArray, seconds: Float, id: Int) {
        session += SpeakerReclusterer.Fp(
            windowKey = key,
            emb = embedding,
            durSec = seconds,
            onlineId = id,
        )
        if (embedding.isNotEmpty()) vectorsHeld++
        fingerprintsTaken++
        shown[key] = id
        while (vectorsHeld > SpeakerReclusterer.MAX_RECLUSTER_FINGERPRINTS) {
            while (oldestVector < session.size && session[oldestVector].emb.isEmpty()) oldestVector++
            if (oldestVector >= session.size) break // unreachable: the counter says one is there
            session[oldestVector] = session[oldestVector].copy(emb = NO_VECTOR)
            oldestVector++
            vectorsHeld--
        }
        if (session.size > MAX_RETAINED_WINDOWS) {
            val excess = session.size - MAX_RETAINED_WINDOWS
            for (i in 0 until excess) {
                shown.remove(session[i].windowKey)
                if (session[i].emb.isNotEmpty()) vectorsHeld--
            }
            session.subList(0, excess).clear()
            oldestVector = (oldestVector - excess).coerceAtLeast(0)
        }
    }

    /**
     * ONE retrospective pass over the session's fingerprints: cluster, publish the window labels,
     * re-seed the tracker.
     *
     * Runs on the embed thread and nowhere else — it is O(n²) in the fingerprint count (see
     * [SpeakerReclusterer]'s own arithmetic), which is a few hundred milliseconds at the cap and
     * therefore exactly the kind of work that belongs below delivered text on a thread of its own.
     *
     * Two callers — the chunk counter and the finalize fence — and the counter is reset by both,
     * so a pass at the fence does not leave the next session's first chunks paying for this one.
     * A pass with nothing new to look at publishes NOTHING: the stop tap can bring two fences
     * through here, and a second identical relabel would repaint the panel and print a second
     * diag line for no change at all.
     *
     * ### A pass that concluded nothing publishes nothing, and re-seeds nothing
     *
     * Below [SpeakerLabels.MIN_CONFIRMED_SPEAKERS] confirmed clusters, this returns before the
     * tracker and the sink hear anything about it. The pass is still RUN — it costs its
     * milliseconds and resets the counter — because "did the second look find two voices yet" is
     * the question, and the answer "not yet" is a real one.
     *
     * Both halves of publishing it would be wrong, and the degenerate answer
     * ([SpeakerReclusterer.oneSpeaker], `confirmedCount = 0`) shows it plainly. Its window labels
     * are all `1`. Before the panel's latch rises that is invisible, so nothing is gained; AFTER
     * it rises — and the latch is one-way — it prints `Speaker 1:` across a session whose two
     * speakers the online tracker had already got right, which is neither 4.9's output nor
     * correct. And [SpeakerTracker.reseed] would replace two live voices with one unconfirmed
     * cluster that then has to re-open and re-earn the second. Session 4's 20:39 dump is the
     * shape that reaches it: a 1.8 s median with 11 of 15 segments under 2.0 s clears
     * [SpeakerReclusterer.MIN_CLUSTER_SECONDS] for nobody.
     *
     * The rule generalises past the degenerate case by the same argument: a pass finding ONE
     * confirmed speaker cannot raise the latch either, and where the online tracker has already
     * raised it the two disagree — so the live labels, which the user is reading, stand.
     */
    private fun recluster() {
        chunksSinceRecluster = 0
        // Fewer VECTORS than the smallest cluster the pass will call a speaker: there is
        // nothing for a clustering to say that the online ids have not already said.
        if (vectorsHeld < SpeakerReclusterer.MIN_CLUSTER_FINGERPRINTS) return
        if (fingerprintsTaken == reclusteredAt) return
        reclusteredAt = fingerprintsTaken

        val startedNs = clockNs()
        val relabel = SpeakerReclusterer.recluster(session)
        val costMs = (clockNs() - startedNs) / 1_000_000L
        if (relabel.windowLabels.isEmpty()) return
        if (relabel.confirmedCount < SpeakerLabels.MIN_CONFIRMED_SPEAKERS) return

        var changed = 0
        for ((key, label) in relabel.windowLabels) {
            if (shown.put(key, label) != label) changed++
        }

        // The tracker's live state becomes the retrospective truth BEFORE the labels leave, so
        // the next chunk decided on this thread can never be decided by the state this pass just
        // overruled. Its ids are the cluster ids from here on, which is why the stored
        // fingerprints are rewritten into the same id space below — otherwise the NEXT pass's
        // `onlineId -> cluster` map would mix two different numberings.
        tracker.reseed(relabel)
        for (i in session.indices) {
            val fp = session[i]
            val label = relabel.windowLabels[fp.windowKey] ?: continue
            if (label != fp.onlineId) session[i] = fp.copy(onlineId = label)
        }

        onRelabel(
            SpeakerRelabel(
                windowLabels = relabel.windowLabels,
                confirmedCount = relabel.confirmedCount,
                fingerprints = vectorsHeld,
                clusterCount = relabel.clusterCount,
                changed = changed,
                costMs = costMs,
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
            // And the THIRD group (session 6): the rules of the retrospective pass. A dump taken
            // under a reclustering build and one taken under the online-only build describe two
            // different label histories for the same audio — `assigned` is the ONLINE id in both,
            // but in one of them it was re-seeded from a clustering partway through the session.
            reclusterSim = SpeakerReclusterer.RECLUSTER_SIM,
            minClusterSeconds = SpeakerReclusterer.MIN_CLUSTER_SECONDS,
            reclusterEvery = SpeakerReclusterer.RECLUSTER_EVERY_CHUNKS,
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

        /**
         * **6 000** — the most windows the retrospective pass is kept able to NAME, oldest
         * dropped. Ten times [SpeakerReclusterer.MAX_RECLUSTER_FINGERPRINTS], which at the
         * session-6 median of ~2.5 s per window is roughly four hours of speech, for a key, a
         * duration and an int apiece. It is not the cost bound — that is the vector cap, and it
         * is unchanged — it is the bound on remembering that a window EXISTS, which is what a
         * reseed needs so no run is left holding an id from a superseded numbering.
         */
        const val MAX_RETAINED_WINDOWS: Int = 6_000

        /**
         * What a window with no fingerprint carries: a window under
         * [SpeakerTracker.MIN_EMBED_SECONDS], one the embedder refused, or one whose vector has
         * been dropped past the cap. Shared and empty — `SpeakerReclusterer.unitOrNull` answers
         * null for it, so it never seeds, never votes and is labelled by the company it keeps.
         */
        private val NO_VECTOR: FloatArray = FloatArray(0)
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
 * @param wholeChunkWindow **null on the geometry route and non-null on the NPU one** — the index
 *        in [ids] whose id the WHOLE chunk takes (4.10, the Fold6 defect).
 *
 *        It is the one field that changes what a reader DOES with [ids] rather than adding to
 *        them, and both meanings are needed because both tiers are live in one session after a
 *        fallback. Null: the chunk's text is cut per window and each id owns its own stretch —
 *        the CPU and GPU behaviour, unchanged. Non-null: the QNN decoder gave no text offsets, so
 *        there is no cut to make and the chunk's committed text wears `ids[wholeChunkWindow]`
 *        alone. The other ids are still real — they were fingerprinted, they taught the tracker,
 *        and they are in the diag line — they simply have no text to sit on.
 *
 *        It doubles as the whole chunk's WINDOW INDEX, and that is the load-bearing half:
 *        `TranscriptSink.assign` stamps it onto the chunk's single run, so the retrospective
 *        pass's `windowLabels` — which is keyed by `WindowKey(seq, windowIndex)` — reaches that
 *        run unchanged and corrects it like any other.
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
    val wholeChunkWindow: Int? = null,
)

/**
 * WHAT THE SECOND LOOK CONCLUDED — one of these per retrospective pass (spike session 6).
 *
 * It is a [SpeakerAssignment]'s opposite number in every way that matters. An assignment is about
 * ONE chunk and arrives a moment after that chunk's text; this is about the WHOLE session and
 * arrives every [SpeakerReclusterer.RECLUSTER_EVERY_CHUNKS] chunks and once at finalize, carrying
 * a label for every window the pass looked at. The sink applies it per window, which is exact —
 * `SpeakerReclusterer.Relabel.map` exists for callers that can only address ids, and this
 * message deliberately does not carry it.
 *
 * @param windowLabels `windowKey -> clusterId` for EVERY window of the session the assigner
 *        still holds — the ones that produced no vector included, because a window this map
 *        misses keeps an id from before the pass renumbered the id space. Only a window past
 *        [SpeakerAssigner.MAX_RETAINED_WINDOWS] is absent, and it keeps the label it has.
 * @param confirmedCount how many retrospective speakers cleared the mass bar. Never below
 *        [SpeakerLabels.MIN_CONFIRMED_SPEAKERS], because a pass that concluded less than that is
 *        not published at all. The panel's latch follows it — and only ever UPWARDS: a later
 *        pass that sees one speaker never takes the labels back off a panel that has them, which
 *        would be the only thing on screen that moved backwards.
 * @param fingerprints how many VECTORS the pass weighed — the diag line's `n=`. NOT the number
 *        of windows it labelled (`windowLabels.size`), which is larger by every window that was
 *        never fingerprinted.
 * @param clusterCount how many speakers it found, confirmed or not.
 * @param changed how many windows' labels actually MOVED. Zero is the ordinary reading on a
 *        stable session and is the number that says a pass cost nothing but its milliseconds.
 * @param costMs what the pass cost on the embed thread, milliseconds.
 *
 * Numbers and ids only, like every other message out of this file: there is no text field, and a
 * window key is a chunk sequence number and an index.
 */
data class SpeakerRelabel(
    val windowLabels: Map<WindowKey, Int>,
    val confirmedCount: Int,
    val fingerprints: Int,
    val clusterCount: Int,
    val changed: Int,
    val costMs: Long,
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
 * @param durationsSec per window, the SPEECH it holds, in seconds on the original timeline —
 *        [SpeakerWindow.speechSamples], which is the window's span everywhere except a coalesced
 *        window on the NPU route. It is the number the tracker's gates were actually decided on,
 *        which is why it is this one and not the span: the `dur=` column of the diag line and the
 *        spike dump's `durSec` have to answer for the verdict beside them.
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
