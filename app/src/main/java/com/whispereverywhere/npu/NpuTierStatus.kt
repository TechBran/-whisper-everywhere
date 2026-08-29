package com.whispereverywhere.npu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The process-scoped half of `NpuWhisperBackend.unavailableReason` — the bridge that gives that
 * property the consumer Q6 wrote it for (4.0, Q8, step 5).
 *
 * ### Why a bridge exists at all
 *
 * `unavailableReason` is an INSTANCE property of `NpuWhisperBackend`, and by design nothing in this
 * app holds that instance: the backend is constructed by the engine/service layer (Q9), lives for
 * the length of a transcription session, and is not reachable from a Compose screen. The card that
 * must say *"the NPU declined this session, and here is the stage"* therefore cannot read the
 * property directly, and the alternative — handing the chooser a backend reference — would put a
 * 376 MiB native session behind a UI dependency.
 *
 * So the backend PUBLISHES here, from the setter of that same property, and the card SUBSCRIBES.
 * Publishing from the setter rather than from each assignment site is deliberate and is the same
 * "one funnel" rule as `PreferencesManager.notifyModelInstalled`: there is no way to set the reason
 * and forget to announce it, including from a stage nobody has written yet.
 *
 * ### Why an object with no `Context`
 *
 * So it can be **executed** by a JVM test — the same argument, and the same shape, as
 * `ModelInstallSignal`. `NpuWhisperBackend` itself may not even be *named* by a unit test (its
 * `QnnAsrNative` reference runs `System.loadLibrary("qnnasr")` at class-init), so the wiring into
 * this object is pinned as source text and everything downstream of it is tested for real.
 *
 * ### What the value means
 *
 * `"<stage>: <detail>"` once the tier has declined, null while it is live or has never run. It is
 * **PROCESS state, not device state**, and that second distinction is the one that still holds:
 * a device that has never armed the tier shows no note at all, because "unavailable on this device"
 * is a claim about a measurement someone took, and until the tier runs, nobody took one.
 *
 * ### Why "process", corrected (4.0, final review F3 / I4)
 *
 * This said **session** state, on the reasoning that `load()` clears the value on every arm. True
 * of this class in isolation; **false in the shipped composition.** The reason feeds
 * `NpuBackendSelector.routesToNpu`'s `declinedThisSession`, which then returns false, so
 * `FloatingBubbleService` builds the CPU engine — so `NpuWhisperBackend` is never constructed
 * again, so `load()`, the only writer of `null`, never runs again. `releaseEverything()` does not
 * clear it either. **One decline therefore persists until process death**, and the card said "for
 * this session" about it on every later visit to the chooser, whatever tier was selected.
 *
 * **The wording was corrected rather than the lifetime, and that is a decision with a reason.** The
 * obvious alternative — clear it in `releaseEverything()` — does not deliver what its name promises
 * and is worse on two counts. (1) `releaseEverything()` does not run at session end: the engine
 * caches its native context across sessions and frees it only on `onTrimMemory` or service destroy,
 * so the lifetime would become *"until memory pressure"* — less predictable than process scope, not
 * more. (2) Every clear re-opens the retry loop that process scope exists to close: a device where
 * the tier reliably declines would pay a 342 MiB load and a guaranteed failure again on the next
 * trigger. Q6's at-most-once funnel keeps the FIRST stage's reason precisely because that is the
 * true one; carrying the same discipline out to the process is consistent with it. So the fix is to
 * say what is true — here, in [cardNote], and in `NpuBackendSelector.routesToNpu`'s parameter doc.
 */
object NpuTierStatus {

    private val _unavailableReason = MutableStateFlow<String?>(null)

    /**
     * The stage that declined, `"<stage>: <detail>"`, or null. Read as Compose state by the tier
     * card; written only by `NpuWhisperBackend.unavailableReason`'s setter.
     */
    val unavailableReason: StateFlow<String?> = _unavailableReason.asStateFlow()

    /** Called from the backend's setter — every arm and every decline, no exceptions. */
    fun publish(reason: String?) {
        _unavailableReason.value = reason
    }

    /**
     * The stage alone: `"init: nativeInit failed at 0"` -> `"init"`. Null in, null out.
     *
     * The stage is the greppable word `NpuDiag.unavailable` prints, and it is the part a user's
     * screenshot can usefully carry; the detail behind it is a native error string that means
     * nothing outside a bug report. Blank or malformed input degrades to the whole trimmed string
     * rather than to an empty label, because a card that says "unavailable ()" is worse than one
     * that repeats something odd.
     */
    fun stageOf(reason: String?): String? {
        val trimmed = reason?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        val head = trimmed.substringBefore(':').trim()
        return if (head.isEmpty()) trimmed else head
    }

    /**
     * The sentence the tier card shows, or null when there is nothing to say.
     *
     * It states four things because leaving any one out is how a silent fallback happens: that the
     * NPU is not being used, that the multilingual CPU model is running instead **so the app still
     * works**, which stage declined so the report is actionable, and — since the final review's F3
     * — **how to get back**. A fallback that quietly ran on the CPU while the card still promised
     * the AI chip is the failure this project has already paid for once.
     *
     * **"for this session" is gone, and the last sentence replaces it.** The decline outlives every
     * session (see the class KDoc), so the old copy told the user something false on every later
     * visit to this screen, including sessions they deliberately ran on another tier. The retry is
     * an app restart and nothing else — there is no in-app route back — so the note now says that
     * rather than leaving the user to discover it.
     */
    fun cardNote(reason: String?): String? {
        val stage = stageOf(reason) ?: return null
        return "The AI chip is unavailable on this device right now (stage: $stage), so speech is " +
            "running on the multilingual CPU model. Accuracy is unchanged; it is slower. " +
            "Restart the app to try the AI chip again."
    }
}
