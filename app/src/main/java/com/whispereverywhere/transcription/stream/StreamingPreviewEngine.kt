package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.DeltaThrottle
import com.whispereverywhere.util.AudioMath
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * The four calls `PreviewTeeEngine` makes on a local previewer — a seam so the tee is testable
 * with a fake. [commit]'s [onFrozen] is invoked on the previewer's executor, once, with the
 * padded stream's final text ("" when nothing could be frozen).
 */
interface LocalPreview {
    fun open(onPartial: (String) -> Unit)
    fun sendAudio(pcm: ByteArray)
    fun commit(seq: Long, retainMs: Long, onFrozen: (seq: Long, text: String) -> Unit)
    fun close()
}

/**
 * The on-device word-for-word previewer (spec §3, §4): one sherpa `OnlineRecognizer` (behind
 * [PreviewRecognizer]) and one open [PreviewStream], driven on this engine's OWN single-thread
 * executor — the `TtsEngine` ownership pattern: one thread touches the native object, explicit
 * release, `@Volatile` flags for the readers on other threads.
 *
 * **The feed** (rung 1 §1.5, the probe's loop verbatim): [sendAudio] on the capture thread
 * writes the chunk into the retained-tail ring and `offer`s it to a bounded queue; a successful
 * offer schedules one [drain] on the executor; an overflow DROPS the chunk and marks the segment
 * `shed` — the capture thread never blocks and never decodes (it already carries the inline
 * Silero probe). [drain] polls the queue empty, converts, `acceptWaveform`s, decodes while
 * `isReady` (`T` frames for the first decode, then every [StreamingPack.cadenceMs] — rung 3 §7),
 * builds the strip from the result's TOKENS (never its `text`, whose spacing the AAR has already
 * rewritten — [PreviewText.strip]), and emits it when it CHANGED and the [DeltaThrottle] allows
 * (at 320 ms the 150 ms throttle never bites; it thins only the commit-time burst, and the
 * freeze bypasses it).
 *
 * **The commit hook** (spec §4.3): pad [StreamingPack.padMs] of zeros — DERIVED from the pack's
 * own `T` (500 ms here, where 450 loses the canary's `VE`; 820 ms for a `T = 77` pack, where a
 * flat 500 would drop the last word of every utterance), `inputFinished`, drain, take the result, trim it at the
 * cut when a tail is retained, hand it to [onFrozen], log `stream-timing:`, then RELEASE the
 * stream and `createStream()` — never `reset`, which cannot drop encoder state through this
 * AAR and would decode the pad into the next utterance (research §3.3). The retained tail is
 * re-fed to the fresh stream from the ring — on THAT stream's timeline ([streamBytes]), and not
 * as the new segment's audio or its first partial: the tail's echo is text the line just logged
 * already reported, and counting it makes `audio=`, `rtf=` and `firstPartialMs=` fiction on
 * every cap-cut segment — which is what a long read is almost entirely made of.
 *
 * **The canary** (spec §7.2) runs inside [warm], once per load, on the PACK's own clip, and its
 * answer has **three** consequences, not two ([CanaryVerdict]): a Fail, or a clip this row NAMED
 * that would not load, disables the previewer for THAT LANGUAGE for the life of the process; a row
 * that names **no clip yet** arms UNSCORED, because a language cannot be found guilty of a clip
 * nobody has recorded. Nothing is persisted, and no other language is affected either way.
 *
 * **Never inside `NativeComputeGate`**: that is a whole-call whisper lock; wrapping this loop in
 * it would stop it being streaming.
 */
class StreamingPreviewEngine(
    private val factory: PreviewRecognizerFactory,
    /**
     * The clip THIS pack's canary transcribes ([StreamingPack.canary]). Per-pack because the
     * English digits clip cannot pass for a non-English model, and a non-pass is indistinguishable
     * from the SME signature the canary exists to catch — so a shared clip would refuse every
     * non-English pack for a reason the log would report as corruption.
     */
    private val canaryClip: (StreamingPack) -> FloatArray?,
    /**
     * The pack whose LOAD threw, handed over rather than looked up. 4.4.0's hook took no argument
     * and the service read a field for it — a field Main writes when the SELECTION moves, so with
     * two packs a switch to B while A was still loading marked **B** corrupt for **A**'s failure,
     * deleting a healthy pack's marker and leaving the broken one installed. The engine's own task
     * closes over the pack it was asked for; nothing else can be stale.
     */
    private val onLoadFailure: (StreamingPack) -> Unit = {},
    /**
     * The pack whose previewer has just been taken OFF for the rest of this process — handed over
     * for the same reason [onLoadFailure] is, and it is NOT the same event: a failed canary and a
     * three-strike session disable a language while the load never threw and the bytes on disk
     * stay valid, so `markCorrupt` is wrong for them and this is the only signal there is.
     *
     * It exists because the verdict had no reader outside this class (4.5.0 Task 3 review r2's
     * N2): this engine is a private field of the service, and the strip above the language
     * selector promises *"words appear on the bubble whenever you pick it"* off a terminal board
     * record. The service publishes it into [PreviewDisabled], which both selection surfaces
     * read. Called from [disable], the ONE writer of the set, so no failure path can forget it.
     */
    private val onDisabled: (StreamingPack) -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "stream-preview").apply { isDaemon = true }
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoClock: () -> Long = System::nanoTime,
    private val queueCapacity: Int = StreamingPreviewTuning.QUEUE_CAPACITY,
    private val log: (String) -> Unit = { android.util.Log.i("WE-DIAG", it) },
    private val enterExecutorThread: () -> Unit = {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
    },
) : LocalPreview {

    /**
     * The languages whose previewer is OFF for the life of this process — a load that threw, a
     * canary that failed, a clip the row NAMED that would not load, or three consecutive decode
     * throws in one session. **Not** a row that has no canary sourced yet: that language arms
     * unscored ([CanaryVerdict.Unscored]), because "T3 has not recorded the clip" is not a verdict
     * about the model and six languages that disable themselves on first warm are six languages
     * the owner cannot hear work.
     *
     * **Per-pack, not per-process, and that was a real defect rather than a tidiness.** A single
     * flag meant a failed ENGLISH canary refused a later FRENCH load in the same process: the
     * clip is the English one, it cannot pass for a French model, so with a shared flag the first
     * non-English user would lose live words in every language until they restarted the app —
     * behind `SETTINGS_DISABLED_ON_DEVICE`, a sentence the 4.4.0 acceptance sheet records as
     * rendered NOWHERE (qualification table §6(3)).
     *
     * Keyed by LANGUAGE because that is the question every other surface asks — "can live words
     * run for the language the user picked" — and because a pack row changing within a language
     * (the recorded Russian upgrade, say) only happens across an app update, which is a new
     * process anyway. Nothing is persisted; it self-heals on restart.
     *
     * Copy-on-write under the executor, `@Volatile` for [isDisabled]'s callers on Main.
     */
    @Volatile private var disabledLangs: Set<String> = emptySet()

    /** [disabledLangs], for a surface that wants to say which language went off. Never mutated by a reader. */
    val disabledLanguages: Set<String> get() = disabledLangs

    /**
     * The RESIDENT pack's verdict, hoisted out of [disabledLangs] for the capture thread: the
     * 32 ms `sendAudio` path must not walk a set, and the commit path wants one answer that cannot
     * change between its two reads. Maintained by [disable] and cleared when a load begins for a
     * pack that is not disabled — the only two events that can change it.
     */
    @Volatile private var off = false

    @Volatile private var warm = false

    /**
     * The pack the resident [recognizer] was loaded for, or null when none is resident. **The
     * engine's identity**: [warm] is idempotent on THIS, and everything per-pack that the loop
     * applies is read off it — the commit pad ([StreamingPack.padMs], derived from the pack's own
     * `T`) and the strip's text rules (its fold, its locale).
     *
     * It is assigned only where the canary ARMS the language — a Pass, or a row with no clip
     * sourced yet ([CanaryVerdict.Unscored]) — beside [recognizer], and cleared only by
     * [releaseResident], beside it. A pad or a fold read from a process-wide constant is the
     * silent defect; one read from "the pack Main happens to be holding right now" is the same
     * defect wearing the fix's clothes, which is why the engine keeps its own answer.
     *
     * `@Volatile` because the pad and the timing line run on the executor while `warm`'s caller
     * may read the engine from Main.
     */
    @Volatile private var loadedPack: StreamingPack? = null

    // Executor-confined from here down.
    private var recognizer: PreviewRecognizer? = null
    private var stream: PreviewStream? = null
    private val queue = LinkedBlockingQueue<ByteArray>(queueCapacity)
    private val ring = PcmRing(StreamingPreviewTuning.ringBytes())
    @Volatile private var onPartial: ((String) -> Unit)? = null
    private val throttle = DeltaThrottle(now = clock)
    private var lastEmitted = ""
    private var pendingEmit: String? = null
    private var segment = SegmentStats(0L)

    /**
     * Bytes the CURRENT stream has consumed — the timeline [cutSeconds] measures a retained-tail
     * cut against, and deliberately NOT [SegmentStats.audioBytes]: a re-fed tail is on this
     * stream's timeline but is not new audio for the segment that receives it, and a stream
     * re-created after a throw starts at zero while the segment's own audio count carries on.
     */
    private var streamBytes = 0L

    /**
     * True only while a retained tail is being re-fed to a fresh stream. The echo of that tail is
     * text the PREVIOUS `stream-timing:` line already reported, so it is not a partial this
     * segment earned and must not set [SegmentStats.firstPartialMs] — it still reaches the strip.
     */
    private var priming = false

    @Volatile private var shedThisSegment = false
    private var consecutiveFailures = 0
    private var priorityApplied = false

    /** Is the RESIDENT recognizer usable? The question every caller that holds no pack can ask. */
    fun isWarm(): Boolean = warm && !off

    /**
     * Is the resident recognizer usable AND is it [pack]'s?
     *
     * The gate's question, and it needs both halves now that the engine can hold a different
     * language than the one being asked about: [isWarm] alone would answer "yes, ready" for a
     * French recognizer during an English session and let the tee borrow it.
     */
    fun isWarmFor(pack: StreamingPack): Boolean = isWarm() && loadedPack == pack

    /** Is this pack's previewer off for the rest of this process? */
    fun isDisabled(pack: StreamingPack): Boolean = pack.language in disabledLangs

    /**
     * Take a language off for the rest of this process, and hoist the verdict if it is the one the
     * loop is running on. The ONE writer of both, so the set and the fast-path flag cannot
     * disagree — the shape three review rounds of Task 1 retired one layer up.
     *
     * A null pack means "nothing is resident and something still failed": the flag goes up (there
     * is nothing usable to feed) and no language is recorded, because none can be named. Reachable
     * only from the three-strike path after a release has already cleared the identity.
     */
    private fun disable(pack: StreamingPack?) {
        if (pack != null) {
            disabledLangs = disabledLangs + pack.language
            // ...and the one hand-over, from the one writer: a surface cannot read this object
            // (4.5.0 Task 3 review r2's N2). Last, so the set and the flag are already settled if
            // the hook reads anything back.
            onDisabled(pack)
        }
        off = true
    }

    /**
     * Load + canary on the executor, for THIS pack. A no-op once THIS pack is disabled — another
     * pack's verdict is not this one's.
     *
     * **Idempotent on the PACK, not on the engine — that is the fix.** 4.4.0 returned early on
     * `recognizer != null` and never looked at which pack the resident recognizer was for, so
     * `warm(dirB, packB)` over a resident pack A loaded NOTHING and left A's model decoding B's
     * speech behind a gate that logged `preview=1` and a diag that said the previewer was warm.
     * That is a wrong-language strip with every honest signal reading green, and with one
     * catalogue row it was unreachable and invisible.
     *
     * A DIFFERENT pack now releases the resident recognizer first and loads the new one. Both
     * halves run in this one task on the single executor thread, so no caller has to sequence them
     * and no window exists where the engine holds A while claiming B.
     */
    fun warm(packDir: File, pack: StreamingPack) {
        if (isDisabled(pack)) return
        executor.execute {
            applyPriorityOnce()
            if (isDisabled(pack)) return@execute
            // The resident recognizer already IS this pack's: nothing to do, and nothing to free.
            if (recognizer != null && loadedPack == pack) return@execute
            // A different pack. Free the old model before the new one allocates — 802-860 ms and
            // ~169 MB per load, and two resident recognizers is a shape nothing here allows.
            if (recognizer != null) releaseResident()
            // This pack is not disabled (checked twice above, on both sides of the post), so the
            // hoisted verdict belongs to it now — a previous pack's `off` must not follow it in.
            off = false
            val t0 = nanoClock()
            val rec = try {
                factory.load(packDir, pack, StreamingPreviewTuning.NUM_THREADS)
            } catch (t: Throwable) {
                disable(pack)
                log(StreamDiag.openLine(factory.sherpaVersion(), factory.ortVersion(), StreamingPreviewTuning.NUM_THREADS, msSince(t0), "skipped", 0L, 0, "fail", warm = false))
                onLoadFailure(pack)
                return@execute
            }
            val loadMs = msSince(t0)
            val t1 = nanoClock()
            val verdict = try {
                PreviewCanary.run(rec, canaryClip(pack), pack)
            } catch (t: Throwable) {
                CanaryVerdict.Fail(0, 0)
            }
            val outLen = when (verdict) {
                is CanaryVerdict.Pass -> verdict.outLen
                is CanaryVerdict.Fail -> verdict.outLen
                CanaryVerdict.NoClip -> 0
                CanaryVerdict.Unscored -> 0
            }
            // THREE consequences for four answers, and the `when` is exhaustive on purpose so a
            // fifth verdict cannot inherit a branch by default (B1 is what happens when one does):
            //   Pass      ⇒ arm, scored.
            //   Unscored  ⇒ arm, UNSCORED — the row names no clip yet, so there is nothing to be
            //               guilty of. The SME guard is unpaid for this language until T3's clip
            //               lands; that trade is priced in [PackCanary]'s docblock and is the one
            //               the brief already made: the previewer is additive and never types.
            //   Fail      ⇒ off. A verdict was rendered and the model failed it.
            //   NoClip    ⇒ off. This row NAMED a clip and the named asset would not load — a
            //               build defect, and the one thing main's
            //               `aMissingClipIsNoVerdictAndTheProcessStaysOff` has always asserted.
            val armed = when (verdict) {
                is CanaryVerdict.Pass -> true
                CanaryVerdict.Unscored -> true
                is CanaryVerdict.Fail -> false
                CanaryVerdict.NoClip -> false
            }
            log(StreamDiag.openLine(factory.sherpaVersion(), factory.ortVersion(), StreamingPreviewTuning.NUM_THREADS, loadMs, verdict.code, msSince(t1), outLen, "ok", warm = armed))
            if (armed) {
                recognizer = rec
                loadedPack = pack
                warm = true
            } else {
                runCatching { rec.release() }
                // Takes THIS language off, and no other: the clip is the pack's, so a verdict
                // rendered on it says nothing about a different model.
                disable(pack)
            }
        }
    }

    override fun open(onPartial: (String) -> Unit) {
        this.onPartial = onPartial
        executor.execute {
            val rec = recognizer ?: return@execute
            stream?.let { runCatching { it.release() } }
            stream = rec.createStream()
            queue.clear()
            ring.clear()
            consecutiveFailures = 0   // a new session is a new verdict (spec §7.1)
            resetSegment()
        }
    }

    /** CAPTURE THREAD, every 32 ms: a ring write and an offer, then return. Never blocks, never decodes. */
    override fun sendAudio(pcm: ByteArray) {
        if (off || onPartial == null) return
        ring.write(pcm)
        if (queue.offer(pcm)) executor.execute(::drain) else shedThisSegment = true
    }

    override fun commit(seq: Long, retainMs: Long, onFrozen: (seq: Long, text: String) -> Unit) {
        executor.execute {
            val rec = recognizer
            if (rec == null || stream == null) {
                onFrozen(seq, "")
                return@execute
            }
            drain()   // everything that arrived before the cut is fed first
            val open = stream
            // Read BEFORE the freeze: the freeze's own third strike drops the resident pack, and
            // the line must report the pad the stream actually received, not the fallback.
            val padMs = padMs()
            val frozen = if (open == null || off) "" else freeze(rec, open, retainMs)
            // audio= is the NEW audio this segment carried, never the tail the previous commit
            // re-fed (that is [streamBytes]) — otherwise rtf='s denominator is inflated and
            // firstPartialMs reads 0 on every cap-cut segment, the shape a long read is made of.
            // The prime's decodes DO stay in decodes=/decodeMs=: re-decoding a tail is real work
            // this segment pays for, so rtf reads compute per second of speech delivered.
            val audioMs = segment.audioBytes / StreamingPreviewTuning.BYTES_PER_MS
            log(
                StreamDiag.timingLine(
                    seq, audioMs, segment.decodes, segment.decodeUs / 1000L, segment.p50Us(), segment.p99Us(),
                    StreamDiag.rtf(segment.decodeUs / 1000L, audioMs), segment.partials, segment.firstPartialMs,
                    padMs, shedThisSegment, segment.retractions,
                ),
            )
            onFrozen(seq, frozen)
            open?.let { runCatching { it.release() } }
            stream = if (off) null else rec.createStream()
            resetSegment()
            val fresh = stream ?: return@execute
            if (retainMs > 0L) {
                val tail = ring.last((retainMs * StreamingPreviewTuning.BYTES_PER_MS).toInt())
                if (tail.isNotEmpty()) {
                    streamBytes += tail.size
                    priming = true
                    try {
                        if (feedAndDecode(rec, fresh, AudioMath.pcm16ToFloat(tail))) emitIfChanged(rec, fresh)
                    } finally {
                        priming = false
                    }
                }
            }
        }
    }

    /** Ends the session's stream; the recognizer stays resident for the next session. */
    override fun close() {
        onPartial = null
        executor.execute {
            stream?.let { runCatching { it.release() } }
            stream = null
            queue.clear()
            ring.clear()
            resetSegment()
        }
    }

    /** Frees the recognizer (onTrimMemory / onDestroy). Not a verdict: a later [warm] reloads. */
    fun release() {
        onPartial = null
        executor.execute { releaseResident() }
    }

    /**
     * Frees the resident recognizer and everything hanging off it, and forgets WHICH pack it was.
     *
     * The one body behind [release] and behind [warm]'s swap, so a language change and a trim free
     * exactly the same things. Clearing [loadedPack] is what makes re-warming the SAME pack after
     * a trim take the load path rather than the identity short-circuit: the pack is no longer
     * resident, so it is no longer the pack `warm` can skip for.
     *
     * NOT a verdict — the disabled set is untouched, by the same rule [release] has always followed.
     */
    private fun releaseResident() {
        stream?.let { runCatching { it.release() } }
        stream = null
        recognizer?.let { runCatching { it.release() } }
        recognizer = null
        loadedPack = null
        warm = false
        queue.clear()
        ring.clear()
        resetSegment()
    }

    // ------------------------------------------------------------------ executor-side internals

    private fun drain() {
        val rec = recognizer
        val s = stream
        if (rec == null || s == null) {
            queue.clear()
            return
        }
        var total = 0
        val chunks = ArrayList<ByteArray>(4)
        while (true) {
            val c = queue.poll() ?: break
            chunks += c
            total += c.size
        }
        if (total == 0) return
        val pcm = if (chunks.size == 1) chunks[0] else ByteArray(total).also { out ->
            var o = 0
            for (c in chunks) {
                System.arraycopy(c, 0, out, o, c.size)
                o += c.size
            }
        }
        segment.audioBytes += pcm.size
        streamBytes += pcm.size
        if (!feedAndDecode(rec, s, AudioMath.pcm16ToFloat(pcm))) return
        emitIfChanged(rec, s)
    }

    /**
     * Feed + decode-while-ready, every decode timed. False when the stream was lost to a failure.
     *
     * Only a decode that RETURNED clears [consecutiveFailures]: 9 of every 10 bursts decode
     * nothing (T = 45, then every 320 ms), and a fresh stream after a throw needs 14 silent
     * accepts before its first decode — so clearing the count on a burst that never decoded
     * would make three failures unreachable and `MAX_CONSECUTIVE_FAILURES` dead.
     *
     * The count therefore carries across the SEGMENTS of one session (a model that throws once
     * per segment must still disable), and [open] clears it — the carry stops at the SESSION
     * boundary, so three throws spread over three sessions do not switch the previewer off for
     * the process. Any session whose last decode threw ends at a non-zero count (a stop
     * mid-sentence is exactly that shape), which is why the boundary has to clear it.
     */
    private fun feedAndDecode(rec: PreviewRecognizer, s: PreviewStream, samples: FloatArray): Boolean = try {
        s.acceptWaveform(samples)
        var decoded = false
        while (rec.isReady(s)) {
            timedDecode(rec, s)
            decoded = true
        }
        if (decoded) consecutiveFailures = 0
        true
    } catch (t: Throwable) {
        onDecodeFailure(rec, t)
        false
    }

    private fun timedDecode(rec: PreviewRecognizer, s: PreviewStream) {
        val t = nanoClock()
        rec.decode(s)
        segment.recordDecode((nanoClock() - t) / 1_000L)
    }

    private fun emitIfChanged(rec: PreviewRecognizer, s: PreviewStream) {
        val text = try {
            PreviewText.strip(rec.result(s), pack())
        } catch (t: Throwable) {
            onDecodeFailure(rec, t)
            return
        }
        if (text != lastEmitted && text != pendingEmit) {
            if (lastEmitted.isNotEmpty() && !text.startsWith(lastEmitted)) segment.retractions++
            pendingEmit = text
        }
        val pending = pendingEmit ?: return
        if (!throttle.shouldEmit()) return
        pendingEmit = null
        emit(pending)
    }

    private fun emit(text: String) {
        lastEmitted = text
        if (!priming) {
            segment.partials++
            if (segment.firstPartialMs < 0L) segment.firstPartialMs = clock() - segment.startMs
        }
        onPartial?.invoke(text)
    }

    /**
     * The resident pack — the source of every per-pack rule the loop applies: the commit pad and
     * the strip's own text rules (its fold, its locale).
     *
     * Non-null wherever it is read: [loadedPack] and [recognizer] are assigned together under the
     * canary's Pass and cleared together, and every reader here runs under a non-null recognizer.
     * The fallback is the shipping row rather than a throw because an English text rule on a state
     * that cannot occur is cheaper than a blank strip — but it is a fallback, not a default, and
     * nothing should ever observe it.
     */
    private fun pack(): StreamingPack = loadedPack ?: StreamingPackCatalog.EN

    /**
     * The resident pack's pad, and the ONE spelling of it: the zeros [freeze] feeds and the number
     * the `stream-timing:` line prints must be the same value, or the line reports a pad the
     * stream never received.
     */
    private fun padMs(): Long = pack().padMs

    /** Derived from [padMs] and never from the pack a second time: one number, two uses. */
    private fun padSamples(): Int = (padMs() * StreamingPreviewTuning.SAMPLE_RATE / 1000L).toInt()

    private fun freeze(rec: PreviewRecognizer, s: PreviewStream, retainMs: Long): String = try {
        s.acceptWaveform(FloatArray(padSamples()))
        s.inputFinished()
        while (rec.isReady(s)) timedDecode(rec, s)
        val r = rec.result(s)
        // Both arms build from TOKENS: the trim always did, and the untrimmed arm used to render
        // `r.text`, whose spacing the AAR has already rewritten. One pack, one spacing.
        if (retainMs > 0L) PreviewText.before(r, cutSeconds(retainMs), pack()) else PreviewText.strip(r, pack())
    } catch (t: Throwable) {
        noteFailure(rec, t)
        ""
    }

    /** Where to trim the frozen text: the cut is on the CURRENT stream's timeline, not the segment's. */
    private fun cutSeconds(retainMs: Long): Float =
        (streamBytes / StreamingPreviewTuning.BYTES_PER_MS - retainMs) / 1000f

    private fun onDecodeFailure(rec: PreviewRecognizer, t: Throwable) {
        stream?.let { runCatching { it.release() } }
        stream = null
        noteFailure(rec, t)
        if (!off) {
            stream = rec.createStream()
            streamBytes = 0L   // the fresh stream's timeline starts here; the dead one's cut is not ours
            lastEmitted = ""
            pendingEmit = null
        }
    }

    private fun noteFailure(rec: PreviewRecognizer, t: Throwable) {
        consecutiveFailures++
        log("stream-preview: decode threw (${t.javaClass.simpleName}) failures=$consecutiveFailures")
        if (consecutiveFailures >= StreamingPreviewTuning.MAX_CONSECUTIVE_FAILURES) {
            // Three throws in one session take the RESIDENT pack off, and the resident pack is
            // the only one this loop has been decoding.
            disable(loadedPack)
            warm = false
            stream?.let { runCatching { it.release() } }
            stream = null
            runCatching { rec.release() }
            recognizer = null
            // The identity goes with the recognizer — a later warm of this same pack must take the
            // load path, not the "already resident" short-circuit, and find the verdict instead.
            loadedPack = null
            queue.clear()
            emit("")
        }
    }

    /**
     * Every call site of this is also a STREAM boundary (open and commit create one, close and
     * release drop one), so the per-stream byte count is seated here too; the one stream boundary
     * that is not a segment boundary is [onDecodeFailure]'s re-create, which seats it itself.
     */
    private fun resetSegment() {
        segment = SegmentStats(clock())
        streamBytes = 0L
        lastEmitted = ""
        pendingEmit = null
        shedThisSegment = false
        throttle.reset()
    }

    private fun applyPriorityOnce() {
        if (priorityApplied) return
        priorityApplied = true
        runCatching { enterExecutorThread() }
    }

    private fun msSince(t0: Long): Long = (nanoClock() - t0) / 1_000_000L

    private class SegmentStats(val startMs: Long) {
        /** NEW audio only — a re-fed tail belongs to the stream ([streamBytes]), not to this segment. */
        var audioBytes = 0L
        var decodes = 0
        var decodeUs = 0L
        val samplesUs = ArrayList<Long>(64)
        var partials = 0
        var firstPartialMs = -1L
        var retractions = 0
        fun recordDecode(us: Long) {
            decodes++
            decodeUs += us
            samplesUs += us
        }
        fun p50Us(): Long = StreamDiag.percentileUs(samplesUs, 0.50)
        fun p99Us(): Long = StreamDiag.percentileUs(samplesUs, 0.99)
    }
}
