package com.whispereverywhere.audio

import com.whispereverywhere.util.ProbeStats

/**
 * THE SPEECH EVIDENCE's "no frame of this buffer has been scored" sentinel (4.3.2) — the value
 * `SileroEndpointer.evidenceFrames` holds from a re-base until the probe's next verdict, and the
 * one it reports as [Endpointer.UNKNOWN_SPEECH_EVIDENCE_MS]. Negative so a real count, 0
 * included, can never collide with it; a FRAME count, where the interface's sentinel is
 * milliseconds, so the two are converted at exactly one place (`speechEvidenceMs`).
 */
private const val NO_EVIDENCE_YET = -1

/**
 * The 3.7 real-VAD endpointer: streaming Silero probabilities in, "commit now" out.
 *
 * It replaces the amplitude DECISION only. Everything structural around it is unchanged: the wall
 * caps stay in the `else if` of `onAudioChunk` (FloatingBubbleService) as backstops, `sendAudio`
 * stays unconditional and first (the first statement of that function), and the stop flush stays
 * unconditional. With this endpointer stubbed to never fire, session behaviour is byte-identical
 * to 3.6.0 — that is the whole de-risking argument.
 *
 * ## The state machine
 * Ported from `whisper.cpp:5271-5347` — the state variables and the per-frame loop of the vendored
 * Silero post-processor `whisper_vad_segments_from_probs` (`whisper.cpp:5217-5451`) — including two
 * details that are easy to lose:
 *  - **The dead band.** A frame with `RELEASE <= p < ONSET` neither clears the pending end nor
 *    counts as silence. The native onset guard at `whisper.cpp:5283` and the silence guard at
 *    `whisper.cpp:5322` test DIFFERENT thresholds, and the gap between them falls through both.
 *    Only a frame at or above `ONSET` resets the hangover clock. That is what makes the hangover a
 *    HARD TIMER rather than a decaying one.
 *  - **The micro-pause memory.** The most recent dip below `RELEASE` that outlived
 *    [EndpointerTuning.MICRO_PAUSE_MS] is remembered (native `prev_end = temp_end`,
 *    `whisper.cpp:5329`, guarded by the strict comparison at `:5328` that
 *    [EndpointerTuning.MICRO_PAUSE_MS] cites), so the 15 s wall cap can cut at a real boundary
 *    instead of an arbitrary millisecond. `no_context = true` makes mid-word cuts unrepairable, so
 *    a better boundary is free quality at the same latency bound.
 *
 * ## Clock
 * ONE clock: the caller's `nowMs`, stamped on the chunk the frames came from. The native reference
 * counts sample indices; wall clock is equivalent here because capture is real time, and it is what
 * [com.whispereverywhere.service.SegmentCapPolicy] and the log lines already use. A burst
 * delivery (the AudioRecord ring holds >=128 ms) makes the hangover fire slightly LATE, never
 * early — the conservative direction.
 *
 * ## Threading
 * [onFrame] runs on the capture thread. [reset] is also called from Main (`switchSource` and
 * `stopRecording`, both FloatingBubbleService) and [onSessionStart] is Main-ONLY (`onOpen`, the
 * site that used to be a fourth [reset]); [onSessionEnd] is Main-only too,
 * from stopRecording after both capture threads have joined. Two of those Main entry points can now
 * BLOCK, briefly: [onSessionStart] and [onSessionEnd] take [probeStats]' instance monitor, which the
 * capture thread holds inside `record()` for a handful of int ops — see [timedProbe] for why that is
 * within [Endpointer]'s "lock-light on the [onFrame] path" obligation. Every mutable field is
 * therefore @Volatile, with the same tolerance [com.whispereverywhere.service.SegmentCapPolicy]
 * documents: the writes are not atomic together, but a torn observation costs at most one 32 ms
 * chunk of slack. The annotation is not decoration — without it a Main-thread [reset] shares no
 * happens-before edge with the capture thread, so the cleared state may never become visible at
 * all. `SileroEndpointerTest` fails the build if a later task adds a `var` without it.
 *
 * The flatline cut (4.4) added three fields to that census, and each crosses the boundary in its
 * own way. [flatlineArmed] is the one field of this class whose ONLY writer is Main — `setActiveSource`
 * in FloatingBubbleService, through [armFlatline] — and whose only reader is the capture thread,
 * once per completed frame; without the annotation a device-audio session could run to its end
 * with the trigger armed on Main and never armed on the audio thread, which is a silent "the cut
 * never fires" rather than a torn value. [flatRun] is incremented on the capture thread alone and
 * zeroed by [closeGate], which Main reaches through [reset]; a Main-side zero that loses the race
 * with a capture-side increment leaves the count at its PRE-RESET value — anything up to
 * [EndpointerTuning.FLATLINE_CHUNKS] `- 1` high, not one — for one frame. The bound on what that
 * can cost comes from the gate, not from the size of the count: the same [closeGate] that lost the
 * zero also shut the gate, so DECISION 3 clears the stale run on the very next frame unless a new
 * ONSET re-opened it first, and past such an onset every frame the run survives is by definition
 * flat. The early fire therefore lands within [EndpointerTuning.FLATLINE_CHUNKS] `- 1` frames of
 * that onset, where the pending end is either the stale run start (older than the onset: `speechMs`
 * negative) or Silero's own stamp from those same few frames (at most four frames of speech) —
 * under `MIN_SPEECH_MS` either way. So the ONLY outcome a lost zero can produce is a DISCARD on a
 * flat onset frame, at most four frames earlier than the un-torn path would have reached the same
 * verdict; never a commit, and never a cut where no flat run existed. [flatRunStartMs] is stamped
 * once per run on the capture thread and read by that same thread at fire time. It is @Volatile so
 * that Main's clear becomes visible at all (and because the census requires every `var` to be), NOT
 * to order it against [flatRun]'s clear: seeing one volatile write says nothing about whether a
 * LATER one is visible yet, so the annotation on its own would still permit a fresh count beside a
 * stale start. What forbids that pairing is the re-stamp inside [onFlat] itself — `if (flatRun == 0)
 * flatRunStartMs = nowMs`, on the same thread, one line before the increment — which rewrites the
 * start on every frame that begins a run, so a count of 1 carries THIS frame's stamp whatever Main
 * did or did not manage to publish.
 *
 * THE BACKPRESSURE GOVERNOR (build 85) added three more, and the census names them in its own
 * words. [queueDepth] is the one field of this class with TWO writers: the service publishes the
 * segment backlog through [onQueueDepth] from whichever thread committed (the capture thread for
 * an endpoint or cap cut, Main for switchSource / stopRecording / the consent flush) and from
 * Main on every resolution; the capture thread reads it at each real endpoint and nowhere else.
 * A depth published between two endpoints is therefore acted on at the next one — never
 * retroactively — and a torn pair of publishes can cost at most one endpoint judged under the
 * other floor, which the next endpoint corrects. [slowFloorActive] is the MODE, and it is written
 * on the CAPTURE thread only — inside [currentFloorMs], the one place the floor is consulted —
 * and cleared by [onSessionStart] from Main; that asymmetry is deliberate, so the mode can never
 * be stepped from a thread that is not also about to use it. [slowCommitIntervalMs] is written by
 * Main at [onSessionStart] and read on the capture thread, exactly as [minCommitIntervalMs]
 * beside it, and carries the same hazard: a session floor the capture thread never sees is a
 * session paced at the previous tier's number.
 *
 * THE SPEECH EVIDENCE (4.3.2) added two more, both BUFFER knowledge the capture thread writes and the
 * commit funnel reads. [evidenceFrames] is incremented on the capture thread at every onset frame and
 * re-based by [onBufferCommitted] — on the capture thread for an endpoint or cap cut, on Main for
 * switchSource / stopRecording / the consent flush — and by [onSessionStart]. That race is NOT one
 * frame each way (nit N2). The UNDER-count is bounded by the frames scored DURING the Main-side
 * commit: the two paths that flush a LIVE source (switchSource, the consent flush) stop it AFTER
 * committing. The OVER-count is not one frame at all — `evidenceFrames++` is a read-modify-write, so
 * an increment that read before Main's re-base writes the WHOLE previous count back over it, harmless
 * only because no over-count can produce a skip. That is [evidenceFramesAtOffer]'s defence too:
 * written beside [prevEndMs], read by Main in [onBufferCommitted], it can misplace one onset frame
 * into the retained tail, and the committed segment keeps the WHOLE count anyway.
 *
 * What @Volatile buys here is VISIBILITY, not atomicity: `fill += n` is a non-atomic
 * read-modify-write, so a Main-thread [reset] racing the capture thread can be LOST, leaving at
 * most one frame of pre-reset audio in the accumulator — inside the same one-chunk tolerance. No
 * ordering of the volatile reads can produce an out-of-bounds copy or a short frame, because every
 * length in [onFrame] is recomputed from the field it just read.
 *
 * @param probe hands a frame of exactly [EndpointerTuning.FRAME_BYTES] PCM16 bytes to the native
 *        Silero probe and returns its probability, or [EndpointerTuning.NO_VERDICT]. **The array is
 *        REUSED between calls — the probe must copy anything it retains.** The real binding to
 *        `WhisperNative.vadProbeFrame` is made by `EndpointerFactory` (Workstream D).
 * @param probeReset resets the native probe's LSTM state; fired on every commit, every [reset] and
 *        every [onSessionStart]. NOT on a merge: the merged audio is still in the caller's buffer
 *        and still the same utterance stream, so the recurrence must run on through it.
 * @param nanoClock monotonic ns source for the probe budget; injected only so the cutout is
 *        testable on the JVM. It is NOT this class's wall clock — see the "ONE clock" ruling above,
 *        which stands: this one never leaves [timedProbe], where it measures the duration of a
 *        single native call and nothing about the audio stream.
 * @param probeStats the session's probe cost/overrun accounting. ONE instance per endpointer: it is
 *        recorded on every probe call, emitted as the `probe:` line when its interval is due, reset
 *        at session start and emitted once more at session end. Defaulted so the state-machine tests
 *        need not supply one; `EndpointerFactory` (Workstream D) passes the real one — and MUST
 *        construct it with [EndpointerTuning.PROBE_BUDGET_US], see [budgetUs].
 *        Two threads reach it: `record()` from the CAPTURE thread, `reset()` (onSessionStart) and
 *        `line()` (onSessionEnd) from MAIN — which is why every method on it is `@Synchronized`.
 * @param probeArm arms the native probe's lifecycle for a new session (Main). The context itself is
 *        still created lazily on the capture thread, at the first frame. It must not THROW — see
 *        [onSessionStart].
 * @param probeTeardown frees the native probe context at session end, after the capture threads have
 *        joined. Injected rather than called directly so this class needs no JNI on the classpath.
 * @param diag where the two `probe:` lines go. Injected for the same reason the other three lambdas
 *        are, and for one more: `android.util.Log` is a no-op under `unitTests.isReturnDefaultValues`
 *        and `ProbeStats` is final, so with the emission written inline NOTHING could observe that a
 *        line was emitted at all — deleting it, or emitting it after the native context had already
 *        been freed, were both mutations the suite could not see. Defaulted to the real `Log.i`, so
 *        production behaviour is unchanged and only the tests supply anything.
 *
 * The per-tier cost governor is NOT a constructor parameter: it is handed over per session through
 * [Endpointer.onSessionStart], because it depends on the installed tier AND on whether
 * every commit becomes a provider request — both of which change between sessions of one service.
 */
class SileroEndpointer(
    private val probe: (ByteArray) -> Float,
    private val probeReset: () -> Unit = {},
    private val nanoClock: () -> Long = { System.nanoTime() },
    private val probeStats: ProbeStats =
        ProbeStats(budgetUs = EndpointerTuning.PROBE_BUDGET_US),
    private val probeArm: () -> Unit = {},
    private val probeTeardown: () -> Unit = {},
    private val diag: (String) -> Unit = { android.util.Log.i("WE-DIAG", it) },
) : Endpointer {
    /** The accumulator. One array for the life of the endpointer: no per-frame allocation. */
    private val frame = ByteArray(EndpointerTuning.FRAME_BYTES)

    /**
     * The overrun boundary [timedProbe] compares against. A plain `val`, so no `@Volatile`: the
     * budget is a constant for this endpointer's life, and the census in
     * `SileroEndpointerConcurrencyTest` covers `var`s only for exactly that reason.
     *
     * The CONVERSION is owned by [EndpointerTuning.PROBE_BUDGET_US], not by this line, and that is
     * load-bearing rather than tidy. Task C10 retuned the latch to microseconds so that it and
     * [probeStats] could not hold two opinions about what an overrun is; two sites each spelling
     * `PROBE_BUDGET_MS * 1_000L` would agree only by coincidence of two identical expressions, and
     * a Kotlin constructor default cannot read this property, so the default above could never have
     * referred to it.
     *
     * **The third site is the one this class cannot reach.** A [probeStats] supplied by the caller
     * MUST be constructed with [EndpointerTuning.PROBE_BUDGET_US]. Nothing here checks it —
     * `ProbeStats` keeps its budget private, so no `require` is even available — and a mismatch
     * reinstates exactly the two-opinion split C10's retune removed: the `probe:` line would report
     * overruns on frames this latch scored comfortable, or stay silent on frames that latched it.
     */
    private val budgetUs = EndpointerTuning.PROBE_BUDGET_US

    @Volatile private var fill = 0
    @Volatile private var lastFrameMs = 0L

    /** The Schmitt gate: open from the first frame at or above ONSET until a hangover ends it. */
    @Volatile private var speaking = false

    /** `nowMs` of the frame that opened the gate — the MIN_SPEECH_MS clock's zero. */
    @Volatile private var speechStartMs = 0L

    /** The uncommitted-buffer latch [hasPendingSpeech] returns. Cleared only by commit/reset. */
    @Volatile private var pendingSpeech = false

    /**
     * The PENDING END: `nowMs` of the FIRST sub-RELEASE frame of the current dip, 0 when the gate
     * is not in one. Native `temp_end` (`whisper.cpp:5323-5324`).
     *
     * Stamped ONCE per dip and cleared by exactly one thing — a frame back at or above ONSET. That
     * is the whole hard-timer mechanism: the hangover is `nowMs - tempEndMs`, wall time, so neither
     * a dead-band mumble nor a run of no-verdict frames can push it back.
     *
     * The clock only advances when a frame arrives; a session that stops producing frames is ended
     * by the unconditional stop flush, not by this timer or by the wall cap — both live inside
     * `onAudioChunk`.
     */
    @Volatile private var tempEndMs = 0L

    /**
     * The MICRO-PAUSE MEMORY: the `nowMs` at which the most recent dip below
     * [EndpointerTuning.RELEASE_THRESHOLD] that OUTLIVED [EndpointerTuning.MICRO_PAUSE_MS] began,
     * or [Endpointer.NO_CUT_POINT] when none has been seen since the last commit or [reset].
     * Native `prev_end` (`whisper.cpp:5273`, assigned at `:5329`).
     *
     * SENTINEL, not arithmetic zero — and that is why the initialiser above and the clear in
     * [clearForNextSegment] both spell [Endpointer.NO_CUT_POINT] instead of a bare `0L`. This
     * field is the only one in this class whose zero LEAVES the class, and what it means outside
     * is fixed by the companion's own warning: 0 is a legal wall-clock reading, merely unreachable
     * in practice, so a move to a monotonic or session-relative clock must change the sentinel
     * first. [tempEndMs] above keeps its bare `0L` deliberately — that zero is private
     * "not in a dip" bookkeeping, published to nobody, and conflating the two would put this
     * class's internals under the interface's clock ruling.
     *
     * BUFFER knowledge, not gate state: it therefore dies in [clearForNextSegment] beside
     * [pendingSpeech] and NOT in [closeGate]. That placement IS the divergence from
     * `whisper.cpp:5341` — see [pendingCutPointMs].
     */
    @Volatile private var prevEndMs = Endpointer.NO_CUT_POINT

    /**
     * THE COST GOVERNOR's bookkeeping: the instant it paces FROM, and whether this session has
     * committed at all yet.
     *
     * [lastCommitMs] is the last commit — or, until one happens, this session's open
     * ([onSessionStart]) or the last frame before an external commit ([reset]). [hasCommitted] is
     * what makes the session's FIRST endpoint free, and it is a FLAG rather than an arithmetic
     * test against [lastCommitMs] deliberately: "no commit yet" must not be spelled as a zero on a
     * clock the CALLER owns. That is the hazard [Endpointer.NO_CUT_POINT]'s own KDoc names for the
     * sibling field, arriving at the governor — with a zero parked in [lastCommitMs],
     * `nowMs - lastCommitMs` is astronomically larger than any floor under a wall clock, so the
     * flag would be load-bearing on paper and dead in the machine, and a session start that forgot
     * to re-arm it would be indistinguishable from one that did.
     *
     * [onSessionStart] therefore ANCHORS [lastCommitMs] on the session instead of zeroing it: the
     * field then holds a sane instant from the first session start onward, and the flag alone
     * decides.
     */
    @Volatile private var lastCommitMs = 0L

    @Volatile private var hasCommitted = false

    /**
     * This session's cadence floor, handed over at [onSessionStart]. Before the first session start
     * it is the conservative 8000 — the same "UNMEASURED means assume the expensive end" rule that
     * gives extreme/ultra their row in
     * [com.whispereverywhere.service.CommitCadencePolicy] — so a frame arriving before onOpen has
     * run can never commit at a rate the tier cannot pay for.
     */
    @Volatile private var minCommitIntervalMs = 8_000L

    /**
     * THE BACKPRESSURE GOVERNOR's second floor (build 85): the interval to pace at while
     * [slowFloorActive]. Handed over beside [minCommitIntervalMs] at [onSessionStart] and
     * defaulted to it there, so a two-argument session start — every existing caller — is the
     * inert governor. Before the first session start it is the same conservative 8000 as the fast
     * floor, for the same reason: two equal floors are one floor.
     */
    @Volatile private var slowCommitIntervalMs = 8_000L

    /**
     * THE SIGNAL: the committed-but-unresolved segment backlog as the service last published it
     * through [onQueueDepth] — from EITHER thread (see the class KDoc). Read on the capture thread
     * inside [currentFloorMs] at each real endpoint; 0 at every [onSessionStart]; untouched by
     * [reset], which is a commit and not a change in what is queued.
     */
    @Volatile private var queueDepth = 0

    /**
     * THE MODE: true while the governor paces at [slowCommitIntervalMs]. Stepped from
     * [queueDepth] by [BackpressureRule.slowActive] inside [currentFloorMs] — on the CAPTURE
     * thread, at the endpoints that consult the floor, and nowhere else — so it enters at
     * [BackpressureRule.ENTER_DEPTH] and leaves at [BackpressureRule.LEAVE_DEPTH] with the
     * hysteresis that rule carries. Cleared by [onSessionStart]; kept across [reset], exactly as
     * the depth is.
     */
    @Volatile private var slowFloorActive = false

    /**
     * THE SLOW-PROBE LATCH. [slowRun] counts CONSECUTIVE frames whose probe call overran
     * [EndpointerTuning.PROBE_BUDGET_MS] — one frame inside the budget puts it back to zero — and
     * [probeCutout] is the latch it throws at [EndpointerTuning.PROBE_CUTOUT_FRAMES], after which
     * [onFrame] returns false for the rest of the session and the amplitude fallback and the wall
     * caps own it. The third and last fallback tier, under "no model at all" and over "the wall
     * caps always".
     *
     * The pair sits AFTER the governor's fields (three in 3.7, six since build 85) rather than
     * inside them, where the plan drew
     * it: those three are one mechanism under one joint KDoc and this is a different mechanism. The
     * resulting order is also the one `SileroEndpointerConcurrencyTest` pins (Task C9) and the
     * position Task C8 hangs `lastCutRecord` off.
     *
     * @Volatile for the reason the class KDoc gives, and this pair states it more sharply than
     * most: the capture thread writes both on every frame while [onSessionStart] clears both from
     * Main, and the latch is PERMANENT within a session — a re-arm the capture thread never sees is
     * a probe that stays off for the rest of the recording.
     */
    @Volatile private var slowRun = 0

    @Volatile private var probeCutout = false

    /**
     * THE CUT RECORD: what the most recent VAD-decided commit of this session actually cut, or null
     * before the first one. Written on the commit path of [onProb] and read by [lastCut].
     *
     * It is written where it is because the three numbers cease to exist one line later: [commitAt]
     * runs [clearForNextSegment], which wipes [tempEndMs] and [speechStartMs], so the record must be
     * taken BEFORE the state machine re-arms and not reconstructed after it.
     *
     * Cleared by [onSessionStart] and by NOTHING else — not [clearForNextSegment], not [reset]. The
     * record describes a CUT, and the two events that look like one are not: a merged endpoint is a
     * deliberate non-commit, and a discarded short burst never was an utterance. Either one erasing
     * the record would leave the funnel with no numbers for the cut that really did happen.
     *
     * @Volatile for the reason the class KDoc gives: the capture thread writes it and Main clears it
     * at [onSessionStart]. The reader is the capture thread's own funnel, immediately after
     * [onFrame] returned true, so the only cross-thread edge that matters is that session clear.
     */
    @Volatile private var lastCutRecord: EndpointCut? = null

    /**
     * THE FLATLINE CUT's arming (4.4): true while the active capture source is captured PLAYBACK,
     * false on the microphone — armed IFF the source is device audio, [Endpointer.armFlatline]'s
     * rule, applied by the service at the one place its source changes. Written on MAIN, read on the
     * capture thread once per completed frame. Opened FALSE by [onSessionStart] (every session opens
     * on the microphone by construction; the source pick that follows re-arms it) and left exactly
     * as it is by [reset], which is an external commit and not a source change.
     *
     * The simulator's `Tuning.flatline_enabled` (`machine.py`), which is OFF by default there for the
     * same reason this is false by default here: with it off the flat path returns on its first line
     * and the machine is behaviour-identical to the one without it (DECISION 1).
     */
    @Volatile private var flatlineArmed = false

    /**
     * THE FLAT RUN: how many consecutive completed frames have carried a chunk RMS at or below
     * [EndpointerTuning.FLATLINE_RMS_MAX] while the gate was open. The simulator's `flat_run_frames`.
     * A COUNT, not a wall-clock age, because the device stamps one bursty `currentTimeMillis()` per
     * chunk and a hold on a band edge fires a chunk early or late as often as on time
     * (`machine.py` DECISION 5, `Tuning.flatline_fire_chunks`). Incremented on the capture thread
     * only; zeroed by any non-flat frame, by a frame with the gate shut, and by [closeGate] — so a
     * commit, a merge, a discard, a [reset] and an [onSessionStart] all end it (DECISION 3/7).
     */
    @Volatile private var flatRun = 0

    /**
     * `nowMs` of the run's FIRST flat frame — stamped once when [flatRun] goes 0 -> 1 and never
     * moved (the simulator's `flat_run_start_ms`). It becomes the pending end at fire time IF, and
     * only if, Silero has not stamped [tempEndMs] itself (DECISION 6): `speechMs` is measured to it,
     * `trailMs` from it. Cleared with [flatRun], in [clearFlatRun].
     */
    @Volatile private var flatRunStartMs = 0L

    /**
     * THE SPEECH EVIDENCE (4.3.2): how many frames of the UNCOMMITTED buffer the probe scored at or
     * above [EndpointerTuning.ONSET_THRESHOLD], or [NO_EVIDENCE_YET] while no frame of this buffer
     * has been scored at all. Incremented in [onProb]'s onset branch on the capture thread;
     * reported through [speechEvidenceMs]; re-based by [onBufferCommitted] and [onSessionStart].
     *
     * EVIDENCE ONLY. No branch of [onProb] or [onFlat] reads it, and none may: it gates the
     * ENCODE at the commit funnel, never the endpoint. That is the whole difference from the merge
     * memory the 4.4 review rejected (the block above [EndpointerTuning.HANGOVER_MS]) — that bank
     * fed the CUT decision and made [EndpointerTuning.MIN_SPEECH_MS] unenforceable; this count
     * changes no cut on any trace, which `SileroEndpointerEvidenceTest` shows by running the
     * grid fixtures with the count read and never consulted.
     *
     * BUFFER knowledge, like [pendingSpeech] and [prevEndMs]: a discarded burst's audio is still in
     * the buffer, so [closeGate] leaves this alone and its frames still count; a merged endpoint
     * likewise. Unlike those two it does NOT die in [clearForNextSegment], and the reason is the
     * ORDER of the VAD-cut path: [onFrame] runs [commitAt] -> [clearForNextSegment] and only THEN
     * returns true to the service, whose funnel reads this count next. Cleared there, every real
     * utterance would report zero evidence and be skipped. So the funnel re-bases it itself,
     * through [onBufferCommitted], once it has read it — on every commit site, not only the VAD
     * one — and [reset] leaves it standing for the reason [Endpointer.onBufferCommitted] gives.
     *
     * The sentinel is what keeps UNKNOWN honest: a probe that never answers (an init that failed,
     * a stale capture thread refused by the epoch gate) leaves this at [NO_EVIDENCE_YET] for the
     * whole buffer, and the engine then transcribes exactly as it did before this count existed.
     * A fully scored buffer of pure silence is 0 — KNOWN, and skippable — which is the case this
     * count exists for.
     */
    @Volatile private var evidenceFrames = NO_EVIDENCE_YET

    /**
     * [evidenceFrames] as it stood when [prevEndMs] was last promoted — the onset frames BEFORE
     * the offered cut point, so that `evidenceFrames - evidenceFramesAtOffer` is exactly the onset
     * frames inside the tail a retaining wall-cap cut keeps (`[prevEndMs, now]`). Written beside
     * the promotion in [onProb] (a dip contains no onset frame, so re-writing it on every
     * qualifying dip frame is idempotent), read by [onBufferCommitted], and zeroed wherever the
     * offer dies or the buffer re-bases: [clearForNextSegment] and [onBufferCommitted].
     */
    @Volatile private var evidenceFramesAtOffer = 0

    /**
     * @param chunk PCM16 mono 16 kHz, ANY length (short reads are normal).
     * @param amp the chunk's RMS (0..32767, `AudioMath.amplitude`), computed once per capture chunk
     *        by the capture thread — `StreamingAudioRecorder.kt:87` over the bytes read,
     *        `PlaybackAudioCapturer.kt:81` over the DECIMATED 16 kHz buffer. Ignored by the Silero
     *        state machine; read by THE FLATLINE CUT ([onFlat]) for every frame this chunk completes,
     *        so a frame inherits the RMS of the chunk that completed it (the simulator's
     *        `frame_rms` mapping, `machine.py` `Tuning.chunk_ms`), and a chunk that completes no
     *        frame contributes nothing to the flat run.
     * @param nowMs the capture wall clock for this chunk.
     * @return true when the caller should commit the buffer NOW.
     *
     * `AudioRecord.read` returns UP TO the buffer size (`StreamingAudioRecorder.kt:80` reads into a
     * 1024-byte array and forwards `buffer.copyOf(read)`) and the 48 kHz decimator documents
     * "~1024" (`PlaybackAudioCapturer.kt:62`), so one chunk = one frame is the common case and
     * never the contract: accumulating to exact frame boundaries is the CONTRACT, not an
     * optimisation. A short frame zero-padded into the model still advances the LSTM one step and
     * poisons the recurrence for every frame after it — silent, gradual accuracy loss with no
     * symptom at the call site, which is why the native side refuses the frame outright rather
     * than padding it.
     */
    override fun onFrame(chunk: ByteArray, amp: Int, nowMs: Long): Boolean {
        // LATCHED OFF: the probe blew its budget PROBE_CUTOUT_FRAMES frames running, so from here
        // this session belongs to the amplitude fallback and the wall caps. Above `lastFrameMs`
        // deliberately — a cut-out endpointer does NO work on the audio thread, not even
        // bookkeeping. Nothing past this line can commit again this session, so the one reader of
        // that stamp (reset()'s governor anchor) has nothing left to anchor, and onSessionStart
        // stamps it itself.
        if (probeCutout) return false
        lastFrameMs = nowMs
        var src = 0
        while (src < chunk.size) {
            val n = minOf(EndpointerTuning.FRAME_BYTES - fill, chunk.size - src)
            System.arraycopy(chunk, src, frame, fill, n)
            fill += n
            src += n
            if (fill < EndpointerTuning.FRAME_BYTES) break
            // The accumulator is cleared BEFORE the probe runs, so the commit return below leaves
            // it clean: on a commit the rest of the in-flight chunk is dropped deliberately. Those
            // bytes belong to audio the caller has just handed off, and carrying them forward
            // would prepend the previous utterance's tail to the next segment.
            fill = 0
            val p = timedProbe(nowMs)
            // The frame that TRIPPED the latch has its verdict DISCARDED, cut and all: the reason
            // the latch fires is that this probe's output has stopped being trustworthy, and a cut
            // is the one verdict the caller cannot take back (`no_context = true` makes a mid-word
            // cut unrepairable). From here the amplitude fallback owns the session.
            if (probeCutout) return false
            if (onProb(p, nowMs)) return true
            // SILERO WINS, ALWAYS. The flat trigger is evaluated only once onProb has declined this
            // frame, so a hangover close and a flat hold that come due on the same frame produce
            // exactly ONE commit and it is Silero's; the flat run is then cleared by commitAt ->
            // clearForNextSegment -> closeGate. The same `if` / `else if` precedence the service
            // gives the VAD cut over the wall cap (`machine.py` on_frame, DECISION 2).
            if (onFlat(p, amp, nowMs)) return true
        }
        return false
    }

    /**
     * True when speech has been seen since the last commit/reset: at least
     * [EndpointerTuning.MIN_SPEECH_MS] has elapsed since a frame at or above
     * [EndpointerTuning.ONSET_THRESHOLD] opened the gate.
     *
     * It describes the UNCOMMITTED BUFFER, not the gate: a merged or discarded utterance leaves it
     * true, because that audio really is still sitting there. Only a commit or a [reset] clears it
     * — a closing gate does not, and neither does a silent frame. The two are easy to conflate and
     * the cost of conflating them is specific: the LOCAL-silence re-arm in the wall-cap branch of
     * `onAudioChunk` (FloatingBubbleService) re-arms the 4 s first-cap window whenever this reads
     * false, so a predicate that meant "speech in the current frame" would re-arm on every cap cut
     * that happened to land in a pause.
     *
     * This is the semantics upgrade that re-arm has been waiting for: the soft talker in a noisy
     * room, whose RMS never clears 500, flips from permanently-false to true. The branch above it
     * is unchanged — only the predicate gets honest.
     */
    override fun hasPendingSpeech(): Boolean = pendingSpeech

    /**
     * The wall-clock ms at which the most recent qualifying micro-pause BEGAN, or
     * [Endpointer.NO_CUT_POINT] when none has been seen since the last commit/reset. Offered to
     * the wall-cap cut path: when the 15 s cap fires with the gate open, this is a real speech
     * boundary to cut at instead of the arbitrary millisecond the cap happened to land on.
     *
     * The offer SURVIVES a discarded short burst. That is a deliberate divergence from
     * `whisper.cpp:5341`, which clears `prev_end` on the discard path too as bookkeeping for its
     * own max-speech split; here the field exists ONLY to give the wall cap a real cut point, and
     * a 200 ms cough arriving after a good pause must not erase the pause. Mechanically the
     * divergence is one line's ADDRESS: [clearForNextSegment] runs on the commit path only, while
     * [closeGate] runs on both.
     *
     * 0L doubles as "no offer" rather than a nullable Long because the ONE consumer,
     * [com.whispereverywhere.service.CommitCadencePolicy.capCutRetainMs], already treats every
     * non-positive value as "commit everything, exactly as 3.6.0 did".
     */
    override fun pendingCutPointMs(): Long = prevEndMs

    /**
     * THE SPEECH EVIDENCE (4.3.2) of the uncommitted buffer, in milliseconds of onset frames, or
     * [Endpointer.UNKNOWN_SPEECH_EVIDENCE_MS] on two honest grounds: no frame of this buffer has
     * been scored ([evidenceFrames] still [NO_EVIDENCE_YET] — a probe that never answers), or the
     * slow-probe latch has silenced the probe ([probeCutout]: frames after the latch are in the
     * buffer and were never scored, so a count taken before it describes only part of the audio).
     * Either way the engine transcribes as it did before this count existed. Two volatile reads,
     * no lock, no allocation: the funnel calls it on the capture thread.
     */
    override fun speechEvidenceMs(): Long =
        if (probeCutout || evidenceFrames < 0) Endpointer.UNKNOWN_SPEECH_EVIDENCE_MS
        else evidenceFrames * EndpointerTuning.FRAME_MS

    /**
     * The funnel has committed the buffer whose evidence it just read; re-base for the next one
     * (4.3.2). With [tailRetained] the wall cap kept the audio after the offered cut point, and
     * the onset frames inside that tail — `evidenceFrames - evidenceFramesAtOffer`, exact because
     * a dip holds no onset frame — open the next count; the committed segment was credited with
     * the WHOLE count, so the split can only over-count it, never skip it. Without a tail the
     * next buffer has no scored frame yet: [NO_EVIDENCE_YET], not 0, so a stop flush that lands
     * before the next verdict is UNKNOWN and transcribed rather than "no evidence" and skipped.
     *
     * The offer's count is zeroed on both arms: the frames of the next buffer all follow whatever
     * offer survives (the consent flush commits without a [reset], so [prevEndMs] can outlive a
     * re-base), and a later retain against that offer must carry the whole buffer, not a
     * difference against a count that belonged to the previous one.
     *
     * [reset] does NOT touch [evidenceFrames]; see [Endpointer.onBufferCommitted].
     */
    override fun onBufferCommitted(tailRetained: Boolean) {
        val frames = evidenceFrames
        evidenceFrames =
            if (tailRetained && frames >= 0) maxOf(0, frames - evidenceFramesAtOffer)
            else NO_EVIDENCE_YET
        evidenceFramesAtOffer = 0
    }

    /** The most recent VAD cut of this session, or null. A MERGED endpoint is not a cut. */
    fun lastCut(): EndpointCut? = lastCutRecord

    /**
     * True once the probe has been latched off for this session (amplitude fallback in force).
     *
     * It stays on the CONCRETE class rather than joining [Endpointer]'s members: an amplitude
     * endpointer has no probe to latch, and the one caller that cares — the diagnostic funnel —
     * reaches it with an `as?`. Putting it on the interface would oblige every implementor to fake
     * a probe it does not have.
     *
     * **REPORTING the cutout is not the same as HANDLING it.** Once this reads true every predicate
     * on this class is frozen at its last value, and [hasPendingSpeech] frozen FALSE is the
     * expensive one: `capCutConsumesWindow(hasPendingSpeech = false, isCloudSession = false)` is
     * false, so every later cap cut in a LOCAL session re-arms the 4 s first-cap window instead of
     * consuming it — perpetual 4 s cuts on the device that could not afford the probe. **DISCHARGED
     * in 3.7 Task D9 at the CALL SITE:** the wall-cap branch ORs this into `capCutConsumesWindow`'s
     * `hasPendingSpeech`, so a latched session consumes the window; the predicate stays SYMMETRIC.
     */
    fun isProbeCutout(): Boolean = probeCutout

    /**
     * A commit happened elsewhere — the wall-cap cut in `onAudioChunk`, `switchSource`,
     * `stopRecording` (all FloatingBubbleService). Fires the native probe reset, which
     * `switchSource` in particular MUST have: carrying LSTM state across a mic <-> device-audio
     * swap is a correctness bug, not merely suboptimal.
     *
     * [reset] carries no clock — the interface's three abstract members are the capture path's,
     * and widening this one for the governor would push a timestamp through four call sites that
     * do not have one — so the governor re-anchors on the last frame seen: within one 32 ms chunk
     * of the true commit instant, the same tolerance
     * [com.whispereverywhere.service.SegmentCapPolicy] documents for its own cross-thread writes.
     * [onSessionStart] stamps [lastFrameMs] itself, so a reset arriving before a session's first
     * frame anchors on the session open rather than on the previous session's last frame.
     *
     * THE SPEECH EVIDENCE (4.3.2) is left standing here, deliberately: every service-side reset
     * follows a funnel commit that has already re-based [evidenceFrames] through
     * [onBufferCommitted], and on the cap site that re-base CARRIED the retained tail's onset
     * frames — a clear here would erase exactly that carry and skip the tail at the stop flush.
     */
    override fun reset() {
        lastCommitMs = lastFrameMs
        hasCommitted = true
        clearForNextSegment()
    }

    /**
     * A new RECORDING session (the `onOpen` handler in FloatingBubbleService, beside
     * `SegmentCapPolicy.onSessionStart`). Everything [reset] clears, plus the governor's
     * first-cut-is-free arming, THIS session's cadence floor, the slow-probe latch and the CUT
     * RECORD — the last two being the state [reset] deliberately leaves standing.
     *
     * [minCommitIntervalMs] comes from
     * [com.whispereverywhere.service.CommitCadencePolicy.minCommitIntervalMs] at the call site,
     * which is the only place that knows both the installed tier and whether this session posts
     * every commit to a provider, and [slowCommitIntervalMs] from its `slowCommitIntervalMs`
     * beside it — THE BACKPRESSURE GOVERNOR's floor once the queue reaches two (build 85), equal
     * to the fast one on every tier but npu-turbo. Three same-typed `Long`s: call it with NAMED
     * arguments. The governor's depth and mode are cleared HERE and only here; [reset] keeps both,
     * because a cap cut, a source switch or a stop flush is a commit and not a change in what the
     * engine still has queued.
     *
     * It also ends the last state that outlived a session boundary. [clearForNextSegment] takes
     * the micro-pause memory with it, so `pendingCutPointMs()` cannot offer a cut point measured
     * on the previous session's audio — no separate clear is needed here, and adding one would
     * duplicate a line whose ADDRESS is load-bearing.
     *
     * The slow-probe latch is RE-ARMED here and nowhere else. [reset] deliberately leaves it
     * standing: re-arming on every commit IS the per-frame retry the latch exists to forbid, only
     * spaced a commit apart, and it would hand the audio thread back to a probe that has already
     * missed its budget [EndpointerTuning.PROBE_CUTOUT_FRAMES] frames running. A NEW recording is
     * a new machine state — a different route, a different thermal state, a finished neighbour
     * app — and is the one event that earns the probe another chance.
     *
     * [lastCutRecord] is cleared here and nowhere else on the same terms, for a different reason.
     * [reset] is an EXTERNAL commit — the wall-cap cut, `switchSource`, `stopRecording` — and none
     * of those is a VAD cut, so none has a speechMs/trailMs/p of its own to leave behind; clearing
     * the record there would blank the funnel's numbers for the VAD cut that really did happen. The
     * merge path and the discarded short burst leave it standing for exactly the same reason.
     *
     * [probeStats] is reset and [probeArm] fired here for the same reason and in the same place: a
     * session is the unit the `probe:` line accounts for ("overruns=0 over this recording"), and
     * arming is the session-open half of the native probe's lifecycle whose other half is
     * [onSessionEnd]'s teardown. Both sit with the re-arms ABOVE [clearForNextSegment], so the
     * whole invalidation happens at the top and the shared per-segment clear stays the last word.
     * [probeArm] must be MAIN-SAFE and must not initialise anything: the native context is created
     * lazily on the CAPTURE thread, at this session's first frame. And it must NOT THROW. It runs
     * ABOVE [clearForNextSegment], so an arm that throws leaves the cutout re-armed and the probe
     * LIVE while the previous session's accumulator residue, pending-speech latch, micro-pause
     * memory and native LSTM state all survive into the new recording — exactly the carry-over
     * [clearForNextSegment] exists to prevent. Before this task the only throwing statement here
     * was `probeReset()`, the LAST line of that clear, so a throw left every other re-arm applied.
     * Swallow inside the bound lambda; this class does not.
     *
     * THE FLATLINE CUT opens every session DISARMED (4.4). A session opens on the microphone by
     * construction — `stopRecording` puts the service's source back to MIC and `startAudioInput`
     * picks a capturer only after this has run — and the pick reaches [armFlatline] before the first
     * frame can arrive, so the flag is re-derived from the real source on every session rather than
     * inherited from the last one. The flat run itself dies in [clearForNextSegment] below, with the
     * rest of the gate state.
     *
     * THE SPEECH EVIDENCE (4.3.2) opens every session UNKNOWN — [evidenceFrames] back to
     * [NO_EVIDENCE_YET] here, the one re-base site besides [onBufferCommitted] — so the previous
     * session's last count can never vouch for, or condemn, this session's first buffer.
     */
    override fun onSessionStart(nowMs: Long, minCommitIntervalMs: Long, slowCommitIntervalMs: Long) {
        this.minCommitIntervalMs = minCommitIntervalMs
        this.slowCommitIntervalMs = slowCommitIntervalMs
        // THE BACKPRESSURE GOVERNOR opens every session at depth 0 on the fast floor: the service
        // resets its own counter at the same moment (`segmentQueueDepth.reset()` in onOpen), so a
        // backlog the last session left in flight is never inherited by this one's first commits.
        queueDepth = 0
        slowFloorActive = false
        evidenceFrames = NO_EVIDENCE_YET
        lastFrameMs = nowMs
        lastCommitMs = nowMs
        hasCommitted = false
        slowRun = 0
        probeCutout = false
        lastCutRecord = null
        flatlineArmed = false
        probeStats.reset()
        probeArm()
        clearForNextSegment()
    }

    /**
     * ARMED IFF THE ACTIVE SOURCE IS CAPTURED PLAYBACK — the service applies that rule at
     * `setActiveSource`, the one place its source changes, so this is reached at session start once
     * the source is picked, at every `switchSource`, and at the DRM handover back to the microphone.
     * Main-only; the capture thread reads the flag. It touches NOTHING else: not the flat run (that
     * is capture-thread state, and every arming in the service follows a [reset] that has already
     * cleared it) and not the gate. Disarmed, [onFlat] returns on its first line and the machine is
     * the one that shipped before this trigger existed (`machine.py` DECISION 1).
     */
    override fun armFlatline(armed: Boolean) {
        flatlineArmed = armed
    }

    /**
     * THE BACKPRESSURE GOVERNOR's signal (build 85), from EITHER thread — the class KDoc's
     * @Volatile paragraph says which and why. One volatile write; the mode is NOT stepped here.
     * Stepping it where the depth arrives would put the mode's writer on whichever thread
     * committed or resolved, and the transition line with it; stepping it in [currentFloorMs]
     * keeps both on the capture thread, at the endpoint that is about to use the floor — which is
     * also what makes "a depth change mid-interval takes effect at the next endpoint" the exact
     * rule rather than a tolerance.
     */
    override fun onQueueDepth(depth: Int) {
        queueDepth = depth
    }

    /**
     * Session end, **on MAIN**, from stopRecording AFTER both capture sources have stopped and
     * JOINED their threads and after the unconditional stop flush. [probeStats] is read from this
     * thread while the capture thread may still be writing it (E1's join is timed), which is what
     * its instance synchronisation is for. Emits the session's final `probe:` line
     * — unconditionally, because a session that never reached the 10 s interval would otherwise
     * report nothing at all, and "overruns=0 over 40 frames" is exactly the acceptance evidence —
     * then frees the native context.
     *
     * The ORDER of the two statements is load-bearing and is pinned by
     * `the_final_probe_line_is_emitted_from_the_live_session_before_the_context_is_freed`: the line
     * must be read off a LIVE session, before anything native is released, because a teardown that
     * throws or blocks must not be able to swallow the session's only accounting.
     *
     * **NOT idempotent — every call frees.** Two `onSessionEnd`s for one session is a double free.
     * As wired by Task D10, `stopRecording` is the ONLY call site (`count == 1`, pinned by
     * `EndpointerLifecyclePinTest`); `onDestroy` frees nothing, so a destroy-terminated session
     * orphans one context until the next `vadProbeInit` reclaims it — bounded at one orphan, and
     * carried as a section-close item rather than a leak. The double-free guard stays in
     * `VadProbeLifecycle` (Tasks D4/D5) rather than here because the second site is a live design
     * option, not because it is currently reachable. Nor
     * may [probeTeardown] lean on the capture join as a fence — T2 SHARPENED: `Thread.join(ms)`
     * returns identically on termination and on timeout and `stopThenJoin` returns `Unit`, so a
     * late free can land after the NEXT session's `vadProbeInit`. The bound lambda must be safe
     * against a subsequent init.
     *
     * The endpointer's DECISION state — the governor's anchor, the cutout latch, the cut record —
     * is deliberately left standing here. [onSessionStart] is the sole re-arm point; this method
     * only ACCOUNTS and FREES. A session end that also cleared the machine would put two re-arm
     * sites in the class and make "which one ran last" load-bearing, and it would take the cut
     * record away from the funnel line that is emitted after it.
     */
    override fun onSessionEnd() {
        diag(probeStats.line())
        probeTeardown()
    }

    /**
     * One native probe call, timed. Two independent consumers of the same measurement:
     *  - [probeStats] accumulates the session's cost distribution and overrun total, and says when a
     *    `probe: frames=… p50=…µs p99=…µs overruns=…` line is due (at most one per 10 s);
     *  - [slowRun] is the LATCH's consecutive-overrun counter. After
     *    [EndpointerTuning.PROBE_CUTOUT_FRAMES] consecutive frames over
     *    [EndpointerTuning.PROBE_BUDGET_MS] the probe is latched off for the rest of the session and
     *    the caller falls back to amplitude. Latched, never retried per frame: the same discipline
     *    the new-segment callback uses for a throwing callback — something that failed 16 frames
     *    running will fail on the next one too, and retrying costs the audio thread every time.
     *
     * CONSECUTIVE is the whole question the LATCH asks — "is this device failing to keep up RIGHT
     * NOW" — and one frame inside the budget answers it, which is why a fast frame zeroes the run
     * rather than merely pausing it. [probeStats] asks the SESSION's question instead ("did this
     * session ever miss the budget") and only a new session resets it. Two counters, ONE overrun
     * DEFINITION: both read the same microsecond cost against the same [budgetUs], which is what
     * keeps the `probe:` line from reporting overruns this latch never counted.
     *
     * The budget is a floor on what counts as an overrun, not a ceiling on what a frame may cost:
     * the session's worst-case PRE-LATCH exposure is [EndpointerTuning.PROBE_CUTOUT_FRAMES] probe
     * calls of unbounded duration, after which it is exactly zero. Whether ONE catastrophic frame —
     * a single probe call longer than the whole 32 ms frame period — should latch immediately
     * rather than wait for sixteen is DEFERRED, not declined: such a rule needs a THRESHOLD, and
     * the `probe:` line's p99 is the evidence that would set it. The S-task's on-device
     * measurements produce that number; nothing here may invent one ahead of them.
     *
     * This is where [Endpointer]'s "allocation-free and lock-light on the [onFrame] path"
     * obligation is discharged, and it is discharged with a cost rather than with a zero:
     * [probeStats]' `record()` takes the instance monitor on every frame — uncontended, a thin
     * lock over a handful of int ops — and once per 10 s the path also formats one `String` and
     * walks a 1025-int histogram twice, ~2050 int ops amortised over ~312 frames. `ProbeStats`' own
     * threading paragraph argues that trade; the reason it is restated HERE is that the interface's
     * obligation is discharged at this call site and nowhere else.
     *
     * The comparison is MICROSECONDS, strictly above [budgetUs], so the boundary is exact: a probe
     * costing 8.000 ms is NOT an overrun and one costing 8.5 ms IS. That is Task C10's deliberate
     * RETUNE of Task C7's truncated-millisecond compare, taken so this latch and [probeStats] can
     * never hold two opinions about what an overrun is;
     * `the_budget_is_compared_in_MICROSECONDS_and_the_boundary_is_strict` holds both halves of it.
     */
    private fun timedProbe(nowMs: Long): Float {
        val t0 = nanoClock()
        val p = probe(frame)
        val elapsedUs = (nanoClock() - t0) / 1_000L
        if (probeStats.record(elapsedUs, nowMs)) {
            diag(probeStats.line())
        }
        if (elapsedUs > budgetUs) {
            slowRun++
            if (slowRun >= EndpointerTuning.PROBE_CUTOUT_FRAMES) probeCutout = true
        } else {
            slowRun = 0
        }
        return p
    }

    /**
     * THE BACKPRESSURE GOVERNOR's floor for THIS endpoint (build 85) — the ONE helper both
     * governor tests call, [onProb]'s and [onFlat]'s, so the two paths cannot pace at different
     * numbers. Steps [BackpressureRule.slowActive] from the depth the service last published,
     * stores the mode, and selects the floor with [BackpressureRule.floorMs]: allocation-free, no
     * lock, two volatile reads and at most one volatile write.
     *
     * It runs ONLY where the floor is consulted — inside the `hasCommitted &&` guard — so the
     * session's free first cut never steps it, and a depth published between two endpoints is
     * acted on at the next one and never retroactively (a merged endpoint is gone; nothing here
     * can commit it later).
     *
     * ONE diag line per mode transition, and only while the governor is ARMED (the two floors
     * differ): `backpressure: depth=2 -> slow floor 3200` / `backpressure: depth=1 -> fast floor
     * 2000`. Unarmed, the mode still steps — the state stays honest — but there is no floor change
     * to announce, and a "slow floor 1200" line on eco would be a lie in a log that exists to be
     * believed. Depth and floor only, never transcript content; stripped in release like every
     * Kotlin diag line, which is known.
     */
    private fun currentFloorMs(): Long {
        val depth = queueDepth
        val slow = BackpressureRule.slowActive(depth, slowFloorActive)
        if (slow != slowFloorActive) {
            slowFloorActive = slow
            if (slowCommitIntervalMs != minCommitIntervalMs) {
                diag(
                    if (slow) "backpressure: depth=$depth -> slow floor $slowCommitIntervalMs"
                    else "backpressure: depth=$depth -> fast floor $minCommitIntervalMs",
                )
            }
        }
        return BackpressureRule.floorMs(slow, fastMs = minCommitIntervalMs, slowMs = slowCommitIntervalMs)
    }

    /**
     * One frame's verdict.
     *
     * [EndpointerTuning.NO_VERDICT] (any negative) means "no verdict": the previous state is kept
     * exactly — it can neither open nor close the gate. It does not stall the hangover either,
     * because that clock is wall time from the pending end, so the next real verdict sees the full
     * elapsed silence.
     *
     * The guard is `p < 0f` and not an equality test against the sentinel: a probability is by
     * definition in [0, 1], so any negative is a refusal, and an exact float comparison on a value
     * that crossed a JNI boundary is the wrong instrument. `EndpointerTuningTest` pins the sentinel
     * to -1.0f absolutely and `SileroEndpointerTest` pins the premise that it is negative, so the
     * two forms cannot drift apart silently.
     */
    private fun onProb(p: Float, nowMs: Long): Boolean {
        if (p < 0f) return false

        // THE SPEECH EVIDENCE (4.3.2): this frame has a verdict, so the buffer's count is KNOWN
        // from here — a scored buffer of pure silence reports 0, not UNKNOWN. Above every branch,
        // so a dead-band or silent first frame makes it known exactly as an onset frame does.
        if (evidenceFrames < 0) evidenceFrames = 0

        // whisper.cpp:5283-5296 — the two native blocks this one branch merges. A frame at or
        // above ONSET clears the pending end (`:5283`) — the HARD reset that makes the hangover a
        // timer rather than a decay — and opens the gate if it is closed (`:5291`).
        if (p >= EndpointerTuning.ONSET_THRESHOLD) {
            // THE SPEECH EVIDENCE is counted and NOT consulted: the only write in the state
            // machine, and no branch below reads it back.
            evidenceFrames++
            tempEndMs = 0L
            if (!speaking) {
                speaking = true
                speechStartMs = nowMs
            }
            if (nowMs - speechStartMs >= EndpointerTuning.MIN_SPEECH_MS) pendingSpeech = true
            return false
        }

        // THE DEAD BAND (RELEASE <= p < ONSET) is deliberately inert: it is neither an onset nor a
        // silence. The native guards at :5283 and :5322 use DIFFERENT thresholds and the gap
        // between them falls through both — that is the Schmitt hysteresis, and its absence is
        // exactly what strands today's amplitude segmenter in the 251-499 RMS band. And because it
        // does not clear the pending end either, the hangover below counts straight THROUGH a
        // mumble — a dead-band frame is not silence, but it does not buy the speaker any time.
        if (p >= EndpointerTuning.RELEASE_THRESHOLD) return false

        // Below RELEASE is SILENCE, and only after speech: with the gate shut there is no
        // utterance to end (`whisper.cpp:5322`, whose `&& is_speech_segment` this guard is).
        if (!speaking) return false

        // The pending end is stamped ONCE, at the FIRST frame of the dip (`:5323-5324`), and the
        // hangover then elapses on wall time from it (`:5333`). Nothing in this branch re-stamps
        // it: that is the difference between a hard timer and a decaying one, and it is what lets
        // the hangover survive a talker whose pauses are full of breath and paper noise.
        if (tempEndMs == 0L) tempEndMs = nowMs

        // THE MICRO-PAUSE MEMORY (`whisper.cpp:5328-5330`). Once THIS dip has outlived the native
        // floor, remember where it STARTED. Silero's own answer to "speech forever" never cuts
        // blind — it cuts at the last such point — and with `no_context = true` that is a strictly
        // better boundary than an arbitrary millisecond, at the same latency bound. STRICT, as the
        // native comparison is: at the 32 ms cadence the FIFTH frame of a dip is the first to
        // qualify (128 > 98), because the fourth is only 96 ms old.
        //
        // It sits ABOVE the hangover check, as native does, so the frame that ends an utterance
        // promotes before it decides — which is what leaves a good boundary standing when that
        // decision turns out to be a DISCARD.
        if (nowMs - tempEndMs > EndpointerTuning.MICRO_PAUSE_MS) {
            prevEndMs = tempEndMs
            // THE SPEECH EVIDENCE (4.3.2): the onset frames BEFORE this offer. A dip holds no
            // onset frame, so every qualifying frame of this dip writes the same number.
            evidenceFramesAtOffer = evidenceFrames
        }

        if (nowMs - tempEndMs < EndpointerTuning.HANGOVER_MS) return false

        // The utterance is measured to the PENDING END, not to now: the hangover's own trailing
        // window is silence, not speech (native `temp_end - curr_speech_start`, `:5337`).
        val speechMs = tempEndMs - speechStartMs
        if (speechMs <= EndpointerTuning.MIN_SPEECH_MS) {
            // whisper.cpp:5337 — too short to be an utterance. Drop it and re-arm; the native VAD
            // filter would drop it before whisper_full anyway, so committing would buy an
            // EmptyExpected round trip and nothing else. STRICT, as the native comparison is:
            // exactly MIN_SPEECH_MS is discarded. `pendingSpeech` is NOT cleared — the audio is
            // still in the engine's buffer, and the cap-policy branch above needs to know that.
            // The inclusive latch in the onset branch is the same asymmetry seen from the other
            // side, and it is deliberate in both directions: err toward "there IS speech".
            closeGate()
            return false
        }

        if (hasCommitted && nowMs - lastCommitMs < currentFloorMs()) {
            // THE COST GOVERNOR. A real endpoint, but committing it now would outrun the tier's
            // measured per-commit cost (F*N + m*S <= 0.70*60 s). MERGE: close the gate so the next
            // pause is judged afresh, and keep `pendingSpeech` — that audio really is still
            // uncommitted. The session's FIRST cut is never merged: first text fast on every tier,
            // and the governor bounds only the steady state. The floor is currentFloorMs(): the
            // tier's fast row, or THE BACKPRESSURE GOVERNOR's slow row while the segment queue is
            // two deep (build 85) — the same helper the flat path consults, so both paths pace at
            // one number.
            //
            // Nothing writes `prevEndMs` here, deliberately, and the plan's merge line
            // `prevEndMs = tempEndMs` was DROPPED rather than kept. It is dead: this branch is
            // reachable only past `nowMs - tempEndMs >= HANGOVER_MS`, and the micro-pause
            // promotion above fires at `> MICRO_PAUSE_MS`, so it has ALREADY written exactly this
            // value in this same call — for every value in HANGOVER_MS's 350-800 owner range,
            // whose floor `EndpointerGridTest.the_fixture_grid_is_valid_for_this_hangover` now
            // asserts against MICRO_PAUSE_MS rather than leaving as prose. And
            // it is worse than redundant: one tidy-up reorder past `closeGate()` and it would
            // store 0 instead, because closeGate() zeroes `tempEndMs` — with the promotion above
            // silently covering for it on every reachable path, so neither the suite nor a
            // mutation battery would see the wall cap quietly stop being offered cut points.
            closeGate()
            return false
        }
        // A REAL CUT, taken. Record it BEFORE the commit — the order is load-bearing, not tidy:
        // commitAt() runs clearForNextSegment(), which zeroes `tempEndMs` and `speechStartMs`, so
        // `nowMs - tempEndMs` computed one line later is `nowMs` itself. Every number here dies
        // with this frame, which is the whole reason [EndpointCut] exists.
        lastCutRecord = EndpointCut(speechMs = speechMs, trailMs = nowMs - tempEndMs, prob = p)
        commitAt(nowMs)
        return true
    }

    /**
     * THE FLATLINE CUT (4.4): "chunk RMS at or below the floor, held for
     * [EndpointerTuning.FLATLINE_CHUNKS] consecutive frames" closes the utterance the way a hangover
     * close does. The reference twin is `tools/vadsim/vadsim/machine.py`'s
     * `SileroEndpointerSim._on_flat`; its eight numbered DESIGN DECISIONS are this method's
     * semantics, and each is taken here as the simulator took it:
     *
     *  1. **OFF UNLESS ARMED.** The first line returns unless [flatlineArmed]; there is no other
     *     guard, so a mic session is behaviour-identical to the machine without this method.
     *  2. **AFTER SILERO, NEVER INSTEAD OF IT.** [onFrame] calls this only once [onProb] has returned
     *     false for the frame — one commit per frame at most, and Silero's when both come due. A
     *     frame that merged or discarded in [onProb] arrives with the gate already shut and cannot
     *     also fire here: one verdict per pause.
     *  3. **ONLY WHILE SPEAKING**, and the run is CLEARED while the gate is shut rather than merely
     *     blocked: counted through a digitally silent lead-in, the first word's opening frame could
     *     fire a cut whose `speechMs` is negative, which the MIN_SPEECH test would then "discard" — a
     *     real word thrown away by bookkeeping. A run therefore always starts at or after the gate
     *     opened, so `speechMs >= 0` by construction.
     *  4. **CONSECUTIVE, AND PURELY AMPLITUDE-DRIVEN.** Any frame whose chunk RMS is above the floor
     *     resets the count; `p` cannot — not an ONSET frame (Silero's `p` failing to see an editor's
     *     gap is the premise, and a `p` veto would restore exactly that blindness) and not a
     *     [EndpointerTuning.NO_VERDICT] frame either. This is the decision that makes a mid-word cut
     *     possible at all; the amplitude floor is the only thing bounding it, which is why
     *     [EndpointerTuning.FLATLINE_RMS_MAX] sits under all room tone.
     *  5. **THE HOLD, AS A COUNT.** The simulator measures `nowMs - flatRunStartMs >= hold` on its
     *     exact 32 ms grid and fires on the fifth flat frame; on the device, whose chunk stamps are
     *     bursty, the same hold on a band edge fires on the fourth or sixth as often as the fifth, so
     *     the Kotlin counts frames — `flatline_fire_chunks()`, the simulator's own port note.
     *     Identical on every grid trace; deterministic on the phone.
     *  6. **THE FIRING FRAME BEHAVES EXACTLY LIKE A HANGOVER CLOSE.** The pending end is whatever
     *     [tempEndMs] holds — Silero's own stamp if it has one, EARLIER than the run (room tone,
     *     then digital zero: a longer trail) or LATER (a dead-band frame of inertia before `p` fell
     *     under RELEASE: `speechMs` includes the flat frames before it, a shorter trail) — and the
     *     run's first flat frame only when it is 0. Nothing here moves an existing stamp, which is
     *     what lets `speechMs`, `trailMs` and the buffer bookkeeping be SHARED with [onProb]'s
     *     `:583-623` instead of duplicated. Then the same MIN_SPEECH discard ([closeGate], buffer
     *     untouched), the same governor merge ([closeGate], `pendingSpeech` kept), the same
     *     [commitAt]. Only [EndpointCut.kind] differs.
     *  7. **A RUN THAT ENDS EARLY DISTURBS NOTHING.** The two run fields are the only state a
     *     non-firing flat frame touches; [tempEndMs] is written at FIRE time and only when unset, so
     *     a run that dies before the hold cannot move a pending end Silero stamped, nor shorten or
     *     lengthen the hangover.
     *  8. **NO MICRO-PAUSE PROMOTION OF ITS OWN.** The promotion at [onProb]'s `:577` runs on
     *     sub-RELEASE frames only, and this frame need not be one, so the flat path does not write
     *     [prevEndMs]: a flat close that MERGES leaves the wall cap whatever offer Silero's own frames
     *     had already promoted, nothing more. The simulator records the symmetric alternative as an
     *     OPEN QUESTION for the owner (`machine.py` DECISION 8, `vadsim-flatline-build.md` UNSURE #3);
     *     the Kotlin takes the simulator's choice, not the alternative, until that ruling lands.
     *
     * Placement: inside [onFrame]'s frame loop, after [onProb] — so the `probeCutout` latch that
     * silences the probe silences this too. Deliberate: with the probe latched off the gate never
     * opens, and DECISION 3 leaves this nothing to close.
     */
    private fun onFlat(p: Float, amp: Int, nowMs: Long): Boolean {
        if (!flatlineArmed) return false                                        // DECISION 1

        if (!speaking) {                                                        // DECISION 3
            clearFlatRun()
            return false
        }

        if (amp > EndpointerTuning.FLATLINE_RMS_MAX) {                          // DECISION 4
            clearFlatRun()
            return false
        }

        if (flatRun == 0) flatRunStartMs = nowMs                                // DECISION 5 — stamped ONCE
        flatRun++
        if (flatRun < EndpointerTuning.FLATLINE_CHUNKS) return false

        // ---- the flat close, from here identical in shape to onProb's :583-623 ----
        if (tempEndMs == 0L) tempEndMs = flatRunStartMs                         // DECISION 6/7
        val speechMs = tempEndMs - speechStartMs

        if (speechMs <= EndpointerTuning.MIN_SPEECH_MS) {                       // :584 — the same discard
            closeGate()
            return false
        }

        if (hasCommitted && nowMs - lastCommitMs < currentFloorMs()) {          // :596 — the same merge, the SAME floor
            closeGate()
            return false
        }

        // :621 — recorded BEFORE the commit, for the reason onProb gives: commitAt wipes the fields.
        lastCutRecord = EndpointCut(
            speechMs = speechMs,
            trailMs = nowMs - tempEndMs,
            prob = p,
            kind = EndpointCutKind.FLAT,
        )
        commitAt(nowMs)
        return true
    }

    private fun clearFlatRun() {
        flatRun = 0
        flatRunStartMs = 0L
    }

    /**
     * The utterance gate only — the pending buffer's bookkeeping survives.
     *
     * The discarded-burst path comes through here, and a discard is NOT a commit: `pendingSpeech`
     * is left exactly as it was, because that audio really is still sitting in the caller's
     * buffer. Only [clearForNextSegment] speaks for the buffer.
     *
     * The flat run is GATE state and dies here with [tempEndMs], for the same reason: a run measured
     * across a closed gate would fire on the first flat frame after the next onset. Clearing it in
     * [clearForNextSegment] alone would leave a discard's run standing (a discard reaches only this
     * method), and clearing it nowhere would let a count survive a commit (`machine.py`
     * `_close_gate`, DECISION 3/7).
     */
    private fun closeGate() {
        speaking = false
        speechStartMs = 0L
        tempEndMs = 0L
        clearFlatRun()
    }

    /**
     * A real endpoint, taken: the caller is about to send the buffer, so everything about it goes.
     *
     * Separate from [clearForNextSegment] because the per-tier cadence governor's bookkeeping
     * hangs here — [nowMs] is the instant the next interval is measured from — while
     * [clearForNextSegment] speaks only for the buffer and is shared with [onSessionStart], which
     * is not a commit and must not pretend to be one. The governor's OTHER non-commit, the merge
     * inside [onProb], reaches neither: it closes the gate and leaves the buffer alone.
     */
    private fun commitAt(nowMs: Long) {
        lastCommitMs = nowMs
        hasCommitted = true
        clearForNextSegment()
    }

    private fun clearForNextSegment() {
        closeGate()
        pendingSpeech = false
        // SENTINEL, not arithmetic zero (see [prevEndMs]). This line's ADDRESS is load-bearing: the
        // micro-pause is buffer knowledge, so it dies HERE beside pendingSpeech and not inside
        // closeGate(). closeGate() runs on the discard path as well, so moving this line into it
        // would let a 200 ms cough erase a good boundary — and would silently re-converge on
        // whisper.cpp:5341, which we diverge from on purpose.
        prevEndMs = Endpointer.NO_CUT_POINT
        // THE SPEECH EVIDENCE (4.3.2): the offer's count dies with the offer. `evidenceFrames`
        // itself is deliberately NOT here — see its KDoc: on the VAD-cut path this method runs
        // BEFORE the funnel reads the count, so a clear here would report every real utterance as
        // zero evidence. The funnel re-bases it through onBufferCommitted once it has read it.
        evidenceFramesAtOffer = 0
        fill = 0
        probeReset()
    }
}

/**
 * What a VAD-decided commit actually cut, for the `endpoint:` diagnostic line. These three numbers
 * exist nowhere else: by the time the service sees the verdict, the state machine has already
 * re-armed. Read immediately after [SileroEndpointer.onFrame] returns true.
 *
 * BOTH durations are WALL-CLOCK milliseconds on `onFrame`'s own `nowMs` — the caller's chunk clock,
 * the only clock this class has (see the "ONE clock" ruling in [SileroEndpointer]'s KDoc). [trailMs]
 * is `nowMs - tempEndMs` evaluated on the frame that FIRED the cut, so the speech ended exactly
 * [trailMs] before that frame's `nowMs`, and Task F9's `speechEnd = nowMs - trailMs` depends on that
 * anchor and on nothing else. A move to a monotonic or session-relative clock changes what these two
 * numbers mean at every reader.
 *
 * It is declared BELOW the class rather than above it: `SileroEndpointerTest`'s class-KDoc scope
 * guard resolves the class's own documentation by the nearest block above the declaration, so a
 * top-level member wedged in between would hand the class's two scoped obligation pins a KDoc
 * written about something else.
 *
 * @param speechMs speech from the gate opening to the frame that began the trailing silence
 * @param trailMs trailing silence at the moment of the cut. For a [EndpointCutKind.VAD] cut it is
 *        always >= HANGOVER_MS. For a [EndpointCutKind.FLAT] cut it is `nowMs - tempEndMs` on the
 *        frame that fired, and `tempEndMs` is whatever Silero had stamped: exactly
 *        `(FLATLINE_CHUNKS - 1) * FRAME_MS` when Silero's stamp coincides with the flat run's first
 *        frame (the measured case on real digital silence), SHORTER when Silero stamped later
 *        (dead-band inertia before `p` fell under RELEASE) and LONGER when it stamped earlier (room
 *        tone, then digital zero) — `machine.py` DECISION 6 and `test_flatline_verify.py`'s first
 *        section. The speech-end anchor `speechEnd = nowMs - trailMs` holds for both kinds.
 * @param prob the Silero probability of the frame that fired the cut. A flat cut can fire on a
 *        [EndpointerTuning.NO_VERDICT] frame (the trigger is purely amplitude-driven), and then
 *        this is -1.0 honestly: that frame had no verdict.
 * @param kind which mechanism fired the cut. Defaulted so every existing construction and every
 *        equality against a hangover cut still reads as it did.
 */
data class EndpointCut(
    val speechMs: Long,
    val trailMs: Long,
    val prob: Float,
    val kind: EndpointCutKind = EndpointCutKind.VAD,
)

/**
 * Which mechanism inside [SileroEndpointer] fired a cut — the simulator's `EndpointCut.kind`
 * (`machine.py`, `'vad'` | `'flat'`). Read by `EndpointDiag.endpointLine` to label the `endpoint:`
 * line `cut=flat` for a flatline close; everything else about a flat cut (the service's
 * `SegmentCapPolicy.onCommit`, retain-nothing, the perceived-latency stamp) is the VAD path's,
 * because a flat close IS a hangover close that arrived by the other door.
 */
enum class EndpointCutKind {
    /** Silero's own hangover ended the utterance. */
    VAD,

    /** The flatline trigger ended it: [EndpointerTuning.FLATLINE_CHUNKS] consecutive flat chunks. */
    FLAT,
}
