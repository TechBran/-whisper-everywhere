package com.whispereverywhere.audio

import com.whispereverywhere.util.ProbeStats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * The ONE place 3.7 decides which endpointer the session runs, and the ONE place the pure state
 * machine is bound to the native probe (Tasks D2/D8).
 *
 * Chosen once, at FloatingBubbleService construction, on nothing more than whether the bundled
 * Silero model resolved on disk. `VadModel.path()` already returns null and already logs "running
 * without VAD" when extraction fails, so the null branch is the shipped 3.6.0 path — the fallback
 * costs no new failure mode and no new code path.
 *
 * **The CALLER resolves the path, ONCE, in the service's field initialiser** —
 * `EndpointerFactory.create(VadModel.path())` — and [create] closes over the already-resolved
 * `String?`. That is deliberate twice over: the ~885 KB asset copy `VadModel.path()` may do on its
 * first call per process attaches to the construction site where it can be warmed or accepted
 * (D10's decision), and `arm` keeps its contract of "record the PROCESS-CONSTANT path" instead of
 * silently re-resolving the model on every session open.
 *
 * THE BINDING. [SileroEndpointer] takes a `(ByteArray) -> Float` lambda rather than a [VadProbe],
 * so the whole state machine is JVM-testable with no JNI on the classpath. Everything JNI-shaped
 * therefore lives here: the [VadProbeLifecycle] that owns WHEN the native context exists, the ONE
 * `ByteBuffer.allocateDirect(FRAME_BYTES)` in `nativeOrder()` the frame contract requires (the
 * native side reads it through `GetDirectBufferAddress`, so a heap buffer would not work at all,
 * and one buffer refilled forever is what keeps the capture thread allocation-free at 31.25 Hz),
 * and the four lambdas the endpointer calls.
 *
 * ## The session epoch, and the one line that must not be softened
 *
 * [VadProbeLifecycle.arm] opens a SESSION EPOCH and hands it back, and both capture-path routes
 * refuse a stale token: `ensureReady(session)` returns false and `frame(session, …)` returns
 * [VadProbe.NO_VERDICT], probe untouched. That gate exists for the one cross-session hazard the
 * state machine cannot see — a session-N capture thread that outlived
 * `CaptureThreadPolicy.stopThenJoin`'s BEST-EFFORT join (`Thread.join(ms)` returns identically on
 * termination and on timeout) calling back in while session N+1 is legitimately `ARMED`, building
 * N+1's context and then writing N's audio into the one shared buffer below.
 *
 * **The token is snapshotted ONCE PER CAPTURE THREAD and never re-read per frame.** The [probe]
 * lambda is built once and shared by every session, so a per-frame read of `armed` would hand the
 * stale thread the CURRENT epoch and the gate would do nothing at all. `mine` is what makes the
 * token per-session, and it works because BOTH capture sources start a fresh `Thread` per session
 * (`StreamingAudioRecorder.kt:70/:101/:148`, `PlaybackAudioCapturer.kt:64/:88/:100`): a new
 * session's thread has no binding and takes its own epoch, so only a thread that outlived its join
 * can be holding a stale one. Were capture threads ever POOLED this inverts — a reused thread
 * would keep session N's snapshot and be refused for all of N+1, which is VAD silently OFF rather
 * than corrupt, but still a regression that would have to re-bind the thread-local.
 *
 * **`probeArm` also refreshes MAIN's snapshot, and that is not decoration.** `probeArm` runs on
 * Main at session open, so `mine.set(opened)` binds MAIN's thread-local — never a capture thread's
 * — and keeps it CURRENT for every session. That is exactly what makes the reset gate below safe
 * for Main's callers, and it is why this line is `set` and not the `remove()` an earlier draft of
 * D5's handoff carried.
 *
 * **Capture-originated resets are gated on the same token** (D5 review I2). `probeReset` is one
 * lambda fired from Main (`onSessionStart`, `switchSource`, `stopRecording`) AND from the capture
 * thread (every commit through `clearForNextSegment`, and the wall-cap cut in `onAudioChunk`,
 * FloatingBubbleService), and [VadProbeLifecycle.reset] is deliberately ungated because
 * the lifecycle cannot bind one token to two callers. Comparing THIS thread's snapshot against the
 * open epoch resolves it without a second lifecycle API: Main's snapshot is refreshed at every arm
 * so Main is never refused, a live capture thread matches, and a stale one is skipped. Milder than
 * the frame hazard — a cleared recurrence costs a few frames of re-warm where a FED one poisons
 * every frame after it (T9) — but reachable: a stale thread can never COMMIT (its verdicts are
 * [VadProbe.NO_VERDICT], which can neither open nor close the Schmitt gate), yet it CAN reach the
 * cap branch's `endpointer.reset()` — and note the gate protects only the native probe: the seven
 * decision-state writes above `probeReset()` inside `SileroEndpointer.reset()` are outside this
 * factory's reach and are D9's to weigh.
 *
 * **One precondition is carried forward UNCLOSED, and it fails OPEN** (D5's precondition 3): a
 * capture thread that produced ZERO probe frames during its own session and then delivers one
 * after the next arm snapshots the NEW epoch and is ADMITTED. It needs a whole session with no
 * probe frames plus a timed-out join, and closing it needs a capture-thread-ENTRY binding
 * (`CaptureThreadPolicy.enterCaptureThread()`), which is D10's change and not reachable from
 * inside a lambda here.
 *
 * ## What is NOT decided here
 *
 * - **Teardown-bill T7/T8 are this file's alone, and T8 is only half-closed.** T7 is discharged by
 *   the fill being `put(ByteArray)` — a byte copy, insensitive to the buffer's order; the
 *   `nativeOrder()` above is belt and braces against a future `putShort`/`asShortBuffer` edit. T8
 *   ("fill, then call, same thread") is discharged CROSS-session by the epoch, which keeps a stale
 *   thread off the buffer entirely. It is NOT discharged INTRA-session: `switchSource`
 *   (FloatingBubbleService) swaps capturers without re-arming, so a mic thread outliving
 *   that bounded join holds a token that is still CURRENT and can refill this buffer alongside the
 *   device-audio thread. D9/D10 must either bump the epoch at that boundary or show the survivor is
 *   impossible — and note the tension: a bump alone fails CLOSED, because `armed` is published by
 *   `probeArm`, which `switchSource` does not call, so every post-switch frame would be refused for
 *   the rest of the session. Pairing the bump with a re-publish means re-invoking the arm path.
 * - **The CUTOUT FREEZE is D9's.** This factory returns a [SileroEndpointer], and
 *   [SileroEndpointer.isProbeCutout] carries the charge: once it latches, every predicate on that
 *   endpointer freezes, `hasPendingSpeech()` freezes FALSE, and each later LOCAL cap cut re-arms the
 *   4 s window instead of consuming it. D9's cap branch must consult it through the established
 *   `(endpointer as? SileroEndpointer)` downcast — a log line is not a fallback.
 * - **Cadence.** Per session, per tier: `CommitCadencePolicy.minCommitIntervalMs` reaches the
 *   endpointer through [Endpointer.onSessionStart], wired by D10, and never through a constructor.
 * - **Probe initialisation.** Per session, on the capture thread: [VadProbeLifecycle.ensureReady]
 *   runs at the first frame. Construction must stay cheap and Main-safe, and nothing below touches
 *   the probe.
 */
internal object EndpointerFactory {

    fun create(vadModelPath: String?, probe: VadProbe = NativeVadProbe): Endpointer {
        if (vadModelPath == null) {
            android.util.Log.i("WE-DIAG", "endpointer: amplitude (no VAD model — 3.6.0 behaviour)")
            return AmplitudeEndpointer()
        }
        android.util.Log.i("WE-DIAG", "endpointer: silero (streaming probe)")
        val lifecycle = VadProbeLifecycle(probe)
        // ONE direct buffer for the endpointer's life. Position/limit/mark are ignored by the
        // native side (it reads [0, nBytes) from the base address), so it is refilled forever.
        val buffer = ByteBuffer.allocateDirect(VadProbe.FRAME_BYTES).order(ByteOrder.nativeOrder())

        // The session epoch (Task D5). `armed` is written on MAIN by probeArm; `mine` is read ONCE
        // per capture thread, at that thread's first probe call, and is what a stale thread carries.
        val armed = AtomicLong(VadProbeLifecycle.NO_SESSION)
        val mine = ThreadLocal.withInitial { armed.get() }

        return SileroEndpointer(
            probe = { frame ->
                val session = mine.get()          // THIS thread's session, not the open one
                // ensureReady() is the ONE-TIME init, and it happens HERE: on the capture thread,
                // at the first frame. A failed or throwing init latches UNAVAILABLE for the whole
                // session, and NO_VERDICT is never read as silence.
                if (!lifecycle.ensureReady(session)) {
                    VadProbe.NO_VERDICT
                } else {
                    buffer.clear()
                    // put(ByteArray) is the byte-order-INSENSITIVE fill that discharges T7, and
                    // fill-then-call on this one thread is what discharges T8.
                    buffer.put(frame, 0, VadProbe.FRAME_BYTES)
                    lifecycle.frame(session, buffer, VadProbe.FRAME_BYTES)
                }
            },
            probeReset = { if (mine.get() == armed.get()) lifecycle.reset() },
            probeStats = ProbeStats(budgetUs = EndpointerTuning.PROBE_BUDGET_US),
            probeArm = {
                val opened = lifecycle.arm(vadModelPath)
                armed.set(opened)
                // MAIN-ONLY, and the whole reset gate rests on it: `Endpointer.onSessionStart`'s
                // contract confines this lambda to Main, so the line below binds MAIN's
                // thread-local and no other thread's. It is also the first ALLOCATING statement
                // here (a boxed Long, possibly materialising this thread's ThreadLocalMap) in a
                // lambda SileroEndpointer.kt:403-409 requires not to throw — which is why it sits
                // AFTER `armed.set`, so the epoch is published before anything can fail.
                mine.set(opened)                  // MAIN's own snapshot, kept current for probeReset
            },
            probeTeardown = { lifecycle.release() },
        )
    }
}
