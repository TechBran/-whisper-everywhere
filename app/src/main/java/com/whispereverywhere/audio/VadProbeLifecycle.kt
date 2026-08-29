package com.whispereverywhere.audio

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns WHEN the native probe context exists (3.7, Workstream D8) — never WHAT it decides.
 *
 * The rules, all of which have in-repo precedent:
 *  - [arm] runs on MAIN at session open and only records the path. Initialising there would put
 *    a model load on the UI thread — the widest hold on the probe surface (file I/O plus tensor
 *    allocation, all of it under the one native mutex), which is what makes it an ANR rather
 *    than merely slow. This class is the Kotlin-side discharge of teardown-bill T5.
 *  - [ensureReady] runs on the CAPTURE thread from the first [Endpointer.onFrame] and performs
 *    the one-time init, so the probe context is created on the thread that will use it.
 *  - A failed or throwing init LATCHES `UNAVAILABLE` for the whole session and is never retried
 *    per frame — the same discipline `we_on_new_segment` uses for a throwing callback ("a
 *    callback that threw once will almost certainly throw on every remaining segment").
 *  - [release] runs on MAIN from stopRecording and frees only a context that was actually
 *    created. The DESIGN INTENT is that it runs after both capture sources have stopped and
 *    joined — but that intent is NOT a guarantee this class may lean on, and it does not claim
 *    one: `Thread.join(ms)` returns identically on termination and on timeout and
 *    `CaptureThreadPolicy.stopThenJoin` returns Unit, so nothing on the teardown path can tell
 *    the two apart. The warning lives in tree as the bounded-join note in
 *    `StreamingAudioRecorder.stop()`;
 *    the obligation is teardown-bill T1 + T2 as SHARPENED by Task E2.
 *
 * CONCURRENCY (Task D5). Two mechanisms, and they answer two different questions.
 *
 * **The monitor answers "how many, and in what order".** [contextLock] is held across the init
 * and across the free, so two capture threads racing the first frame build ONE context and a
 * teardown can never land inside an in-flight init. Before it, a [release] arriving mid-init read
 * `ARMED`, skipped the free and set `IDLE`, and the finishing init republished `READY` over it:
 * nothing was ever freed and the lifecycle claimed to be ready. That was an OWNERSHIP hazard, not
 * a data race — all four externs serialise on one native mutex (T4), so a late free could not
 * corrupt a concurrent init, only orphan what it created — and it was BOUNDED at exactly ONE
 * orphaned context, because `vadProbeInit` is documented idempotent ("a second call frees the
 * previous context first, so a session restart or model swap cannot leak ~2.6 MB",
 * `WhisperNative.kt:132-134`), so the next session's init reclaimed the previous orphan and they
 * could not accumulate.
 *
 * **The epoch answers "whose session is this".** The monitor alone would LEGITIMISE the one
 * hazard that is actually reachable across sessions: a session-N capture thread that outlived its
 * best-effort join (the bounded-join warning in `StreamingAudioRecorder.stop()` names this case by
 * name — "the
 * `vadProbeInit` of the NEXT session starting while the previous capture thread is still
 * unwinding") calls back in after session N+1 has [arm]ed. It sees `ARMED`, takes the monitor
 * legitimately, and creates session N+1's context; once N+1 is `READY` it would go on feeding N+1's
 * context through D8's ONE shared direct buffer, concurrently with N+1's real capture thread —
 * torn frames (T8) and cross-session LSTM contamination (T9), with no exception, no sentinel and
 * no log. Nothing in the state machine can see that, because the state is legitimately `ARMED`.
 *
 * So [arm] opens a SESSION EPOCH and hands it back, and the two capture-path routes take it:
 * [ensureReady] returns `false` and [frame] returns [VadProbe.NO_VERDICT] — probe untouched, zero
 * native calls — for any caller holding a stale one. Both gates are plain volatile reads and take
 * no lock, so the 31.25 Hz steady state is unchanged. A D10 ordering guarantee is NOT an
 * alternative: E2's finding is that the join's outcome is unobservable, so an ordering that cannot
 * be observed cannot be constructed.
 *
 * **IT IS A SESSION GENERATION, AND THAT IS A REAL LIMIT, NOT A PHRASING.** `switchSource`
 * (FloatingBubbleService) swaps capturers WITHIN one session — same bounded
 * `stopThenJoin`, same unobservable outcome — and never re-arms, so the epoch does not move. A mic
 * thread that outlives that join holds a token that is still CURRENT, passes both gates, and writes
 * D8's one shared direct buffer alongside the device-audio thread: T8 torn frames in an
 * INTRA-session form this class cannot see by construction. D8, D9 and D10 all declined to close it
 * and D10 said so in the source at `switchSource`: the remaining options are to bump the epoch at
 * `switchSource` — the natural site, which already calls `endpointer.reset()` on its own
 * (FloatingBubbleService) — or to show the switch's stop-then-join makes the survivor
 * impossible within T2-SHARPENED's best-effort bounds. The FINAL REVIEW owns that choice; it was
 * never closable here.
 *
 * **THE WIRING D8 MUST USE** — the ungated [ensureReady] overload is exactly the route a stale
 * thread would take, so the capture path must not use it. The token has to be snapshotted ONCE PER
 * SESSION on the capture side and never re-read per frame: a per-frame read hands the stale thread
 * the CURRENT epoch, and the gate then does nothing at all.
 *
 * ```
 * val armed = AtomicLong(VadProbeLifecycle.NO_SESSION)   // written on Main by probeArm
 * val mine  = ThreadLocal.withInitial { armed.get() }    // read ONCE per capture thread
 * probeArm  = { armed.set(lifecycle.arm(vadModelPath)) }
 * probe     = { frame ->
 *     val session = mine.get()                           // THIS thread's session, not the open one
 *     if (!lifecycle.ensureReady(session)) VadProbe.NO_VERDICT
 *     else { buffer.clear(); buffer.put(frame, 0, VadProbe.FRAME_BYTES)
 *            lifecycle.frame(session, buffer, VadProbe.FRAME_BYTES) }
 * }
 * ```
 *
 * THREE preconditions that wiring rests on. All three hold today; all three are worth an assertion
 * if the capture side is ever restructured — and note that (1) and (2) fail CLOSED while (3) fails
 * OPEN, which is the one that costs something.
 *
 * (1) BOTH capture sources start a FRESH `Thread` per session — `StreamingAudioRecorder` and
 * `PlaybackAudioCapturer` both build the thread in `start()` and null it in `stop()`; both
 * feed the same `onAudioChunk`. So a capture thread cannot be carrying a PREVIOUS session's
 * snapshot: a new session's thread takes its own epoch, and only a thread that outlived its join
 * holds a stale one. Were capture threads ever POOLED this inverts — a reused thread would keep
 * session N's snapshot and be refused for the whole of N+1, which is VAD silently off rather than
 * corrupt (a permanent [VadProbe.NO_VERDICT] is "keep the previous state", T10), but it is still a
 * regression and the pooling change would have to re-bind.
 *
 * (2) `probeArm` runs before the session's first frame. `SileroEndpointer` fires it from
 * `onSessionStart`, and the ordering that actually carries this is a THREAD START, not the
 * lambda's reachability: `onOpen`'s Main body (FloatingBubbleService) runs `onSessionStart` and
 * only then `startAudioInput()`, which spawns the capture thread.
 *
 * (3) A capture thread's FIRST probe call must land while its OWN session is still open. `mine.get()`
 * initialises at the first call, not at thread start, so a thread that produced NO probe frames
 * during its own session and then delivers one after the next [arm] snapshots the NEW epoch and is
 * ADMITTED — **the gate fails open here, not closed.** It needs a whole session with zero probe
 * frames (blocked in `record.read()` throughout a very short session) plus a timed-out join, so it
 * is narrower than the hazard the epoch closes; but it is the direction that costs something.
 * Closing it needs a capture-thread-ENTRY binding — `CaptureThreadPolicy.enterCaptureThread()` is
 * the natural site. It was never D8's, and D10 did not take it either: D10 closed the SESSION
 * lifecycle, and this binding belongs to the CAPTURE THREAD's entry. Still OPEN, routed to the
 * final review.
 *
 * **THE MAIN-BLOCK BUDGET, stated rather than assumed (D4 review, teardown-bill T1 RESIDUAL).**
 * The monitor makes Main's [release] wait out a whole in-flight `vadProbeInit`, and it lands on
 * `stopRecording`, the path T1's residual already budgets at ~4 s composite (2 × `CAPTURE_JOIN_MS`,
 * since `audioRecorder.stop()` is followed by `stopPlaybackCapturer()`'s own 2000 ms fenced join)
 * with roughly 1 s of headroom under the 5 s input-dispatch window. What that wait actually is:
 * the probe's model is the bundled 885 KB `ggml-silero-v5.1.2.bin`, NOT a 190 MB whisper tier —
 * so the worst case is a small model load, analytically tens of milliseconds of file I/O plus a
 * ~2.6 MB allocation, not seconds. It is qualitatively far inside the ~1 s of headroom rather than
 * a second join-sized bill. That is an ANALYTIC bound, not a measurement: the S-task on-device
 * probe is the measurement point, and it is what would revise this paragraph. [arm] deliberately
 * does NOT take the monitor, so session OPEN — also Main — can never block behind an init at all.
 */
class VadProbeLifecycle(
    private val probe: VadProbe,
    /**
     * The ggml backend registry's population — this class's own precondition (4.0, Q9b).
     *
     * **It is a parameter of THIS class because the dependency is this class's, not some other
     * component's.** The build is `GGML_BACKEND_DL`, so the registry starts empty and
     * `vadProbeInit` -> `whisper_vad_init_with_params` -> `make_buft_list` asks it for a CPU device
     * on the very first frame of every session. Until Q9b nothing here said so, and the registry
     * happened to be populated by `WhisperNativeBackend.load` — a component the probe has no
     * relationship with. That held for three minor versions because every session loaded a CPU
     * tier first; the 4.0 npu tier does not, and every npu session SIGABRTed here. **An ordering
     * assumption about another component is exactly what broke, so this class states its own.**
     *
     * Injected rather than called statically for the reason everything else in this class is
     * injected: it makes the ORDER — populate, *then* init — a thing a JVM test can execute
     * instead of a thing a source pin can only describe.
     */
    private val ensureBackends: () -> Unit = com.whispereverywhere.whisper.GgmlBackends::ensureLoaded,
) {

    companion object {
        /**
         * The epoch no session ever has. [arm] issues from 1 upward, so a caller still holding
         * this has never been armed and is refused by both gated routes.
         */
        const val NO_SESSION = 0L
    }

    enum class State { IDLE, ARMED, READY, UNAVAILABLE }

    @Volatile private var currentState = State.IDLE
    @Volatile private var modelPath: String? = null

    /**
     * The open session's epoch. Monotonic and never reused, so a token can only ever be current
     * or stale — never accidentally current again. Read on the hot path as one volatile read
     * (`AtomicLong.get()`), never under [contextLock].
     */
    private val epoch = AtomicLong(NO_SESSION)

    /**
     * Guards the two operations that touch the native context's EXISTENCE. The steady-state
     * per-frame path never takes it: [ensureReady] returns on the volatile READY read above it.
     * [release] runs on Main from the teardown path BEHIND the capture joins, so this lock is
     * expected to be uncontended in production — but "behind the joins" is a best-effort ordering
     * and NOT a fence (the class KDoc says why, and the epoch above exists because it is not one).
     * The lock is what makes a mis-ordered teardown degrade to a wait instead of a free-under-init.
     */
    private val contextLock = Any()

    fun state(): State = currentState

    /**
     * Session open (Main). A null path means "no VAD model" — unavailable, probe untouched.
     *
     * Opens a new session epoch and returns it; see the class KDoc for how D8 must carry it to
     * the capture thread. The epoch is bumped BEFORE the state is published, and that order is
     * load-bearing: publishing `ARMED` first would leave a window in which a stale capture thread
     * reads the new `ARMED` and the OLD epoch, passes the gate, and initialises the incoming
     * session's context — the exact hazard the epoch exists to refuse.
     *
     * Takes NO lock, by design: this is Main at session open and must never wait on an init.
     */
    fun arm(modelPath: String?): Long {
        val opened = epoch.incrementAndGet()
        this.modelPath = modelPath
        currentState = if (modelPath == null) State.UNAVAILABLE else State.ARMED
        return opened
    }

    /**
     * UNGATED. Sequential and Main-confined callers only — it cannot tell one session from the
     * next, so it is the route a stale capture thread would take. The capture path must use the
     * [ensureReady] overload that takes a session token.
     */
    fun ensureReady(): Boolean {
        if (currentState == State.READY) return true          // hot path: one volatile read
        synchronized(contextLock) {
            val snapshot = currentState
            if (snapshot == State.READY) return true
            if (snapshot != State.ARMED) return false
            val path = modelPath
            if (path == null) {
                currentState = State.UNAVAILABLE
                return false
            }
            val ok = try {
                // BEFORE probe.init, always (4.0, Q9b). An empty ggml registry does not make
                // vadProbeInit return false — it makes make_buft_list hand a null CPU device to
                // ggml_backend_dev_backend_reg, which trips GGML_ASSERT and aborts the process.
                // Inside the try because this runs inline on the AUDIO thread and nothing may
                // escape here; GgmlBackends.ensureLoaded cannot throw, but the seam above means a
                // future collaborator could, and a degraded session beats a lost one.
                ensureBackends()
                probe.init(path)
            } catch (t: Throwable) {
                // Must never escape: this runs inline on the audio thread.
                android.util.Log.w("WE-DIAG", "probe: init threw — amplitude fallback for this session", t)
                false
            }
            currentState = if (ok) State.READY else State.UNAVAILABLE
            if (!ok) android.util.Log.w("WE-DIAG", "probe: init failed — amplitude fallback for this session")
            return ok
        }
    }

    /**
     * Capture thread, per frame — THE CAPTURE PATH'S ROUTE. Cheap after the first call.
     *
     * @param session the token [arm] returned for THIS capture thread's session.
     * @return true when the probe is usable; false for a stale [session], with the probe untouched
     *   and no init attempted — a thread that outlived its join must never build the next
     *   session's context.
     */
    fun ensureReady(session: Long): Boolean {
        if (session != epoch.get()) return false             // stale caller: not this session's
        return ensureReady()
    }

    /**
     * Capture thread, per frame — THE CAPTURE PATH'S FRAME ROUTE, and the reason the epoch is not
     * merely an `ensureReady` concern: D8's lambda calls the probe directly, so a gate in front of
     * the init alone would leave a stale thread feeding the LIVE next-session context through the
     * one shared direct buffer (T8 torn frames, T9 cross-session recurrence).
     *
     * [pcm] is forwarded unexamined — the buffer, its `allocateDirect` byte order and the
     * no-concurrent-refill rule stay D8's alone (T7/T8), exactly as they are for
     * [NativeVadProbe.frame]. This adds two volatile reads and no lock.
     *
     * No `try`/`catch`, deliberately and asymmetrically with [ensureReady]: the extern's contract is
     * a sentinel rather than a throw, and an unpinned catch on the audio thread is worse than none.
     *
     * @return the probe's probability, or [VadProbe.NO_VERDICT] for a stale [session] or a probe
     *   that is not `READY` — with the probe untouched. NEVER 0.0f: "no verdict" is not silence
     *   (T10), and a stale frame that read as confident silence would cut a live utterance.
     */
    fun frame(session: Long, pcm: ByteBuffer, nBytes: Int): Float {
        if (session != epoch.get()) return VadProbe.NO_VERDICT
        if (currentState != State.READY) return VadProbe.NO_VERDICT
        return probe.frame(pcm, nBytes)
    }

    /**
     * One of the three service-side reset sites, or the endpointer's own post-commit reset.
     *
     * UNGATED, deliberately, and this is the one capture-path route the epoch does not watch.
     * `probeReset` is fired from BOTH Main (`onSessionStart` at `onOpen`, plus TWO of the three
     * service reset sites — `switchSource` and `stopRecording`) and the CAPTURE thread (the third
     * service site: the wall-cap cut inside `onAudioChunk`, FloatingBubbleService
     * / `plan:8349`), so no single token binding can be correct for both
     * callers: binding it to the capture thread's snapshot would cache MAIN's token forever and
     * refuse Main's resets for every later session, and binding it to the currently-armed epoch
     * gates nothing. A stale capture thread reaching the cap branch therefore clears the LIVE next
     * session's LSTM. That is strictly milder than what the epoch closes — a cleared recurrence
     * costs a few frames of re-warm, where a FED one poisons every frame after it (T9) — and it
     * rides a commit a stale thread could already trigger before 3.7. Named, not gated. How
     * capture-thread resets are finally routed WAS decided, explicitly, in D8: `EndpointerFactory`
     * gates `probeReset` factory-locally on the caller's own epoch snapshot, so a stale capture
     * thread's reset is skipped while Main's is never refused. The residue that gate does NOT
     * cover — the decision-state writes above `probeReset()` in `SileroEndpointer.reset()` — is
     * named at D9's cap-branch reset site and routed to the C-side cleanup.
     */
    fun reset() {
        if (currentState == State.READY) runCatching { probe.reset() }
    }

    /**
     * Session end (Main), on the teardown path behind the capture joins. Idempotent.
     *
     * Deliberately does NOT close the epoch: it does not need to. Every gated route out of `IDLE`
     * already refuses — [ensureReady] on the `!= ARMED` check, [frame] on the `!= READY` check —
     * so the epoch's job is only the case the state machine cannot see, which is the NEXT
     * session's legitimate `ARMED`.
     */
    fun release() {
        synchronized(contextLock) {
            if (currentState == State.READY) runCatching { probe.free() }
            currentState = State.IDLE
            modelPath = null
        }
    }
}
