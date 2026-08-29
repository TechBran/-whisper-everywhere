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
 * `"<stage>: <detail>"` while a session has fallen back, null while the tier is live or has never
 * run. It is **session state, not device state**: `load()` clears it on every arm, so a decline
 * does not outlive the session that produced it, and a device that has never armed the tier shows
 * no note at all. That is the honest reading — "unavailable on this device" is a claim about a
 * measurement someone took, and until the tier runs, nobody took one.
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
     * It states three things because leaving any one out is how a silent fallback happens: that the
     * NPU is not being used, that the multilingual CPU model is running instead **so the app still
     * works**, and which stage declined so the report is actionable. A fallback that quietly ran on
     * the CPU while the card still promised the AI chip is the failure this project has already
     * paid for once.
     */
    fun cardNote(reason: String?): String? {
        val stage = stageOf(reason) ?: return null
        return "The AI chip is unavailable on this device right now (stage: $stage), so speech is " +
            "running on the multilingual CPU model for this session. Accuracy is unchanged; " +
            "it is slower."
    }
}
