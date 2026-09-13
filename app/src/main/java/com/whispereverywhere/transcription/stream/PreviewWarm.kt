package com.whispereverywhere.transcription.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * THE LANGUAGE WHOSE PREVIEWER IS LOADED AND USABLE RIGHT NOW — [StreamingPreviewEngine]'s own
 * `isWarm()` answer, published where a SURFACE can read it (4.5.1 Task 1).
 *
 * The engine is a private field of `FloatingBubbleService`, so the answer the session gate itself
 * reads (`isWarmFor(pack)`) had no reader anywhere else — while the strip above the language
 * selector renders a present-tense PROMISE off a terminal board record: *"English is ready: words
 * appear on the bubble whenever you pick it"*. Until this build that receipt meant only *the files
 * landed*, and the owner met the gap between the two facts as the feature not working:
 *
 * > *"Once we get the preview model downloaded, why can't we just refresh things to where the very
 * > next transcribe is already on? People are going to think that it doesn't work."*
 *
 * So READY means WARM now. It is the state the user actually cares about, it is knowable the moment
 * the engine says so, and it closes the loop visibly: the download finishes, the install warms the
 * pack (the third trigger, `warmOnPackInstalled`), the strip says ready, and the next tap shows
 * words. The window between the two is the load — 802-860 ms measured behind a 73-128 MB
 * transfer — so a user would have to tap within a second of the download finishing to see the
 * silence.
 *
 * ### Why ONE language, where [PreviewDisabled] is a set
 *
 * The engine holds ONE recognizer: `warm` is idempotent on the PACK and a different pack releases
 * the resident one inside the engine's own task before the new one allocates (the
 * one-pack-per-process invariant, T2 defect 1). A set here would describe something the engine
 * cannot do, and a surface reading it would promise words for two languages at once.
 * [PreviewDisabled] is a set for the opposite reason: those verdicts accumulate and never come
 * back within a process.
 *
 * ### Why it is WITHDRAWN, where a verdict never is
 *
 * Warm is not a verdict. A trim frees the recognizer, a language change frees it, a failed canary
 * or three decode throws take the language off — and every one of those is reachable while a
 * selection surface is composed. So [note] takes a nullable language and null is a real value: it
 * says nothing is usable, which is the honest state to render no promise from.
 *
 * ### One writer, and it is the ENGINE's own answer
 *
 * Written from exactly one site — the engine's `publishWarm`, handed over through the `onWarm` hook
 * the service wires (`LocalPreviewWiringPinTest`) — and the value is always
 * `if (isWarm()) loadedPack else null`, read off the engine itself. Never Main writing the pack it
 * BELIEVES it asked for: that is the stale-pack defect both `onLoadFailure` and `onDisabled` were
 * re-shaped to avoid, and it is worse here, because Main's belief is exactly the thing that is
 * 802-860 ms ahead of the truth.
 *
 * ### Why a StateFlow
 *
 * The value moves on the previewer's executor thread while the two selection surfaces may be
 * composed. A plain field would leave a stale receipt standing above the selector until something
 * unrelated recomposed — [PreviewDisabled]'s own reason, and the reason this fix is visible at all.
 */
object PreviewWarm {

    private val _language = MutableStateFlow<String?>(null)

    /** The language whose previewer is warm, or null when nothing usable is resident. */
    val language: StateFlow<String?> = _language.asStateFlow()

    /**
     * Record the engine's own answer: the resident, usable recognizer's [language], or null. One
     * writer — see the KDoc above for why Main may not write its own belief here.
     */
    fun note(language: String?) {
        _language.value = language
    }

    /** Whether [language]'s previewer is warm right now. */
    fun isWarm(language: String): Boolean = _language.value == language

    /** Test-only: this object is process-scoped, so a JVM test must be able to start from empty. */
    fun forgetAll() {
        _language.value = null
    }
}
