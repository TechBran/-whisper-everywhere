package com.whispereverywhere.transcription.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * THE LANGUAGES WHOSE PREVIEWER THIS PROCESS HAS TAKEN OFF — [StreamingPreviewEngine]'s own
 * verdict, published where a SURFACE can read it (4.5.0 Task 3 review r2's N2).
 *
 * The engine disables a language for the rest of the process on a load that threw, a canary that
 * failed, a missing clip, or three consecutive decode throws in one session
 * (`StreamingPreviewEngine.disable`). Its set has been readable on the engine since T2
 * (`isDisabled(pack)`, `disabledLanguages`, whose KDoc says *"for a surface that wants to say
 * which language went off"*) — but the engine instance is a private field of
 * `FloatingBubbleService`, so no surface could read it at all, and `SETTINGS_DISABLED_ON_DEVICE`
 * is a sentence the 4.4.0 acceptance sheet records as rendered NOWHERE. The strip above the
 * language selector is the first surface that makes a PROMISE about the future — *"English is
 * ready: words appear on the bubble whenever you pick it"* — so it is the first that has to be
 * able to stop making it.
 *
 * ### Why the fact goes here rather than the promise being re-derived from disk
 *
 * The verdict is not a disk fact and cannot be read back from one: a failed canary leaves 73 MB
 * installed and intact, `state()` still answers `Installed`, and the Settings row is still right
 * to call it installed. What is false is only the promise that words will appear. So this is a
 * TERM in that sentence ([StreamingPackCopy.selectorLine]) and not a reason to retire the board's
 * record of the arrival — the arrival really did happen. The axis is stated once, in
 * [PreviewWorkboard.retire].
 *
 * ### Why process-scoped, and why nothing is ever removed
 *
 * The engine's own verdict is process-scoped and self-heals on restart: *"nothing is persisted"*.
 * Removing a language here would therefore be a second opinion about a decision only the engine
 * can reverse, and it cannot reverse it — `warm` is a no-op once THIS pack is disabled, so even a
 * repair install cannot bring the language back before the next process. A set that grows only,
 * exactly like the engine's, is the same answer read from a second place rather than a second
 * answer.
 *
 * ### Why a StateFlow
 *
 * The verdict is written from the previewer's executor thread, during a dictation, while the two
 * selection surfaces may be composed. A plain set would leave the READY receipt standing above
 * the selector until something unrelated recomposed; the flow makes the language going off the
 * same kind of event the board's own steps are. Both selection sites collect it, and neither
 * judges it: which sentence that makes true is the pure function's answer.
 *
 * Written by ONE site — the engine's own `disable`, handed over through the `onDisabled` hook the
 * service wires (`LocalPreviewWiringPinTest`), the same shape and for the same reason as
 * `onLoadFailure`: the engine's load task is the only place that knows WHICH pack failed, and a
 * closure over a field Main has moved marks the wrong language.
 */
object PreviewDisabled {

    private val _languages = MutableStateFlow<Set<String>>(emptySet())

    /** Every language whose previewer is off for the rest of this process. */
    val languages: StateFlow<Set<String>> = _languages.asStateFlow()

    /**
     * Record that [language]'s previewer went off. Idempotent, and never un-said: see the KDoc
     * above for why removal would be a second opinion about the engine's own verdict.
     */
    fun note(language: String) {
        _languages.update { it + language }
    }

    /** Whether [language]'s previewer is off in this process. */
    fun isOff(language: String): Boolean = language in _languages.value

    /** Test-only: this object is process-scoped, so a JVM test must be able to start from empty. */
    fun forgetAll() {
        _languages.value = emptySet()
    }
}
