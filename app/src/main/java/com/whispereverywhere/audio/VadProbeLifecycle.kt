package com.whispereverywhere.audio

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
 *    the two apart. The warning lives in tree at `StreamingAudioRecorder.kt:131-138`;
 *    the obligation is teardown-bill T1 + T2 as SHARPENED by Task E2.
 *
 * SCOPE, stated once and honestly: every guarantee above is stated for the SEQUENTIAL lifecycle —
 * arm, then frames, then release, one call at a time — and cross-thread exclusion between
 * [ensureReady] and [release] arrives in Task D5 (teardown-bill T2), which takes a monitor around
 * exactly the two operations that touch the native context's EXISTENCE. Until it lands, a
 * [release] landing inside an in-flight init reads `ARMED`, skips the free and sets `IDLE`, and
 * the finishing init then republishes `READY` over it — the ~2.6 MB context is orphaned and the
 * lifecycle claims to be ready. That is an OWNERSHIP hazard, not a data race: all four externs
 * serialise on one native mutex (T4), so a late free cannot corrupt a concurrent init, only leak
 * what that init created.
 */
class VadProbeLifecycle(private val probe: VadProbe) {

    enum class State { IDLE, ARMED, READY, UNAVAILABLE }

    @Volatile private var currentState = State.IDLE
    @Volatile private var modelPath: String? = null

    fun state(): State = currentState

    /** Session open (Main). A null path means "no VAD model" — unavailable, probe untouched. */
    fun arm(modelPath: String?) {
        this.modelPath = modelPath
        currentState = if (modelPath == null) State.UNAVAILABLE else State.ARMED
    }

    /** Capture thread, per frame. Cheap after the first call. @return true when the probe is usable. */
    fun ensureReady(): Boolean {
        val snapshot = currentState
        if (snapshot == State.READY) return true
        if (snapshot != State.ARMED) return false
        val path = modelPath
        if (path == null) {
            currentState = State.UNAVAILABLE
            return false
        }
        val ok = try {
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

    /** One of the five reset sites, or the endpointer's own post-commit reset. */
    fun reset() {
        if (currentState == State.READY) runCatching { probe.reset() }
    }

    /** Session end (Main), on the teardown path behind the capture joins. Idempotent. */
    fun release() {
        if (currentState == State.READY) runCatching { probe.free() }
        currentState = State.IDLE
        modelPath = null
    }
}
