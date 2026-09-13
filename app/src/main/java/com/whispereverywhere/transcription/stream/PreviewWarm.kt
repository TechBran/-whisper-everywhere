package com.whispereverywhere.transcription.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WHETHER THE PREVIEWER'S ENGINE CAN BE ASKED AT ALL, AND WHAT IT ANSWERED — three states, because
 * there really are three (4.5.1 Task 1, fix round 1, review r1's B1).
 *
 * The first version of this register had two: a language, or null. That collapsed **"no engine
 * exists in this process to ask"** and **"the engine exists and says no"** into one value, and the
 * READY receipt above the language selector read both of them as *not ready* — which took the
 * receipt away in the one flow the surface was ruled in for.
 *
 * ### The flow that proves the third state is not a nicety
 *
 * [StreamingPreviewEngine] is a private field of `FloatingBubbleService`, and that service is NOT
 * started when the app launches — it is started by the Home toggle, the Settings toggle, the boot
 * notification and `BootReceiver`. So on the path AF5 describes (onboarding picks a language, the
 * fetch's progress shows on HOME when the user lands there, before they have tapped to start the
 * bubble) the 73-128 MB install completes with **no service** — hence no install collector, hence
 * no warm, hence no engine that ever answered anything. With two states that is *not warm*, the
 * `INSTALLED` arm goes silent, the strip's row is dropped and `LivePreviewSelectorStrip`'s
 * `if (rows.isEmpty()) return` takes the whole strip off screen: the user watches a bar count to
 * 128 MB and then watches the surface that was narrating it go blank. That is the complaint this
 * task exists to fix, reproduced one surface over.
 *
 * ### So the receipt is read against the engine's answer only where an engine can be asked
 *
 * - [NoEngine] — nothing in this process can be asked. The receipt stands on 4.5.0's reading, and
 *   in this state that sentence is TRUE: *"words appear on the bubble whenever you pick it"* is a
 *   promise about the pack being ready to SELECT, and starting the bubble arms it through the boot
 *   prewarm. Ruling 3c put the strip there so users *"know that their language is ready for
 *   selection"*, and this is the state most users read it in.
 * - [Cold] — an engine exists, it was asked, and nothing usable is resident (the 802-860 ms load
 *   window, a trim that freed the recognizer, another language resident). No receipt: this is the
 *   half of the task that made *ready* mean the next tap works rather than the files landing.
 * - [Warm] — that language's recognizer is loaded and usable, so the very next session arms.
 *
 * The two readings of one sentence are deliberate and they do not conflict: with no engine it
 * promises a language ready to pick, with an engine it promises the next tap. What is never
 * printed is the 4.5.0 defect — *ready* while an engine that could have answered was cold.
 */
sealed interface PreviewWarmth {

    /**
     * No engine exists in this process, so there is nothing to ask. Not *"not warm"*: an answer
     * nobody gave is not a no, and reading it as one is review r1's B1.
     */
    data object NoEngine : PreviewWarmth

    /** An engine exists, and nothing usable is resident right now. */
    data object Cold : PreviewWarmth

    /** [language]'s previewer is loaded and usable right now — the engine's own `isWarm()` answer. */
    data class Warm(val language: String) : PreviewWarmth
}

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
 * So READY means WARM now, wherever warm is a question that has an owner to answer it. It is the
 * state the user actually cares about, it is knowable the moment the engine says so, and it closes
 * the loop visibly: the download finishes, the install warms the pack (the third trigger,
 * `warmOnPackInstalled`), the strip says ready, and the next tap shows words. The window between
 * the two is the load — 802-860 ms measured behind a 73-128 MB transfer — so a user would have to
 * tap within a second of the download finishing to see the silence.
 *
 * ### TWO facts, TWO owners, and that split is the register's whole shape
 *
 * [PreviewWarmth] has three states because the question has two owners:
 *
 *  - **whether an engine exists** is the SERVICE's fact, and only the service can state it — the
 *    engine cannot report its own absence. [engineBuilt] and [engineGone] are that fact, called on
 *    Main from the one construction site (`warmStreamingPreview`) and from `onDestroy`.
 *  - **whether the existing engine is warm, and for what** is the ENGINE's fact, handed over
 *    through its `onWarm` hook and recomputed from `if (isWarm()) loadedPack else null` at every
 *    site that moves either half. [note] is that fact.
 *
 * **A dropped engine's last word is therefore ignored, and that is load-bearing rather than
 * tidiness.** `release()` POSTS its withdrawal to the engine's own executor, so `onDestroy`'s
 * `release(); streamingPreview = null` is followed, milliseconds later, by a [note] from an engine
 * that no longer exists. Without the claim below that late null would write [PreviewWarmth.Cold]
 * over [PreviewWarmth.NoEngine] and suppress the receipt for the rest of the process — B1 again,
 * one ordering down. So [note] is a no-op while no engine is claimed.
 *
 * The reverse ordering is narrower and is NOT guarded here (review r1's N3): a *next* service's
 * boot prewarm builds a new engine after a deliberate `delay(1500)`, so an old engine's sub-100 ms
 * posted release has ~1.5 s of margin to land before the new engine's claim. If it ever landed
 * after the new arm it would show [PreviewWarmth.Cold] until the next warm change — a missing
 * receipt, never a false one.
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
 * ### Why warmth is WITHDRAWN, where a verdict never is
 *
 * Warm is not a verdict. A trim frees the recognizer, a language change frees it, a failed canary
 * or three decode throws take the language off — and every one of those is reachable while a
 * selection surface is composed. So [note] takes a nullable language and null is a real value: it
 * says nothing is usable, which is the honest state to render no promise from.
 *
 * ### Why a StateFlow
 *
 * The value moves on the previewer's executor thread while the two selection surfaces may be
 * composed. A plain field would leave a stale receipt standing above the selector until something
 * unrelated recomposed — [PreviewDisabled]'s own reason, and the reason this fix is visible at all.
 */
object PreviewWarm {

    private val _warmth = MutableStateFlow<PreviewWarmth>(PreviewWarmth.NoEngine)

    /** What can be asked of the previewer's engine, and what it answered. */
    val warmth: StateFlow<PreviewWarmth> = _warmth.asStateFlow()

    /**
     * Is an engine claimed — i.e. does one exist to ask? Written on Main by the service at the two
     * sites that build and drop it, read on the engine's executor by [note], hence `@Volatile`.
     */
    @Volatile private var claimed = false

    /**
     * The service built an engine: from here on, *not warm* is an ANSWER rather than an absence.
     * The engine has not loaded anything yet at this point, so the state is [PreviewWarmth.Cold] —
     * which is exactly right, because the 802-860 ms load is what the receipt is now waiting for.
     */
    fun engineBuilt() {
        claimed = true
        _warmth.value = PreviewWarmth.Cold
    }

    /**
     * The service dropped its engine (`onDestroy`): there is nothing left to ask, so the receipt
     * goes back to standing on the pack being ready to SELECT. Un-claims first, so the withdrawal
     * the dying engine already posted cannot land as a no — see the KDoc above.
     */
    fun engineGone() {
        claimed = false
        _warmth.value = PreviewWarmth.NoEngine
    }

    /**
     * Record the engine's own answer: the resident, usable recognizer's [language], or null. One
     * writer — see the KDoc above for why Main may not write its own belief here, and why a note
     * from an engine nobody claims is dropped rather than believed.
     */
    fun note(language: String?) {
        if (!claimed) return
        _warmth.value = language?.let { PreviewWarmth.Warm(it) } ?: PreviewWarmth.Cold
    }

    /** Whether [language]'s previewer is warm right now. */
    fun isWarm(language: String): Boolean =
        (_warmth.value as? PreviewWarmth.Warm)?.language == language

    /** Test-only: this object is process-scoped, so a JVM test must be able to start from empty. */
    fun forgetAll() {
        claimed = false
        _warmth.value = PreviewWarmth.NoEngine
    }
}
