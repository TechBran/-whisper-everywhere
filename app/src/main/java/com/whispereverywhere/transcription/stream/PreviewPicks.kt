package com.whispereverywhere.transcription.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * THE LANGUAGES THIS PROCESS HAS WATCHED THE USER PICK — the one fact
 * [PreviewAutoFetch.decide] cannot read off a preference.
 *
 * `PreferencesManager.selectedLanguage` answers *"which language is selected"*. It cannot answer
 * *"did the user just choose it"*: a `StateFlow` replays its current value to every new collector,
 * so a language chosen ten seconds ago and one chosen before the app was last updated arrive at
 * the decision identically. That difference is what the UNASKED path's two cautions hang on — a
 * top-up waits for a network that works and defers to the 24 h back-off, and a pick does
 * neither — so it has to be recorded when it happens, by the one writer that sees it happen.
 *
 * **It is no longer a SPENDING distinction** (4.5.0 pass 2, Fix 1). This object was written for
 * ruling 3b's *"a SELECTION downloads on ANY connection, at once"*, whose other half was a card
 * with a tap for the unasked top-up on cellular. The owner settled that both paths simply
 * download — *"Yes. I wanted to silently download on cellular and Wi Fi"* — so the metered test
 * is gone and the register's remaining decision role is the narrower one above. It also feeds
 * `PreviewWork.starter`, which is Task 1's observable answering *"who started this"*, so it is
 * read by the copy as well as by the decision.
 *
 * ### Why it is PROCESS-scoped and not a preference
 *
 * A pick made yesterday is a standing SELECTION, not a gesture this process watched. Persisting
 * this set would make every launch a pick, which would retire the unasked path's two cautions —
 * and, worse for the copy, would make a progress line for a transfer nobody asked for read as
 * one the user started.
 *
 * ### Why a StateFlow and not a plain set
 *
 * The decision is computed in composition (`HomeScreen`'s live-words card) and must re-ask *on
 * the spot* when a pick happens — ruling 2's *"right there on the spot"*. A plain set read in
 * composition would not recompose anything, so the pick would sit unnoticed until some unrelated
 * recomposition; the flow makes the arrival of a pick the same kind of event the selection itself
 * already is.
 *
 * It also answers the one case the selection flow cannot, because a `StateFlow` conflates equal
 * values: a user who re-picks the language ALREADY selected writes no new selection, but does add
 * to this set the first time — so tapping your own language in the picker starts a pack a
 * backed-off top-up would have stayed quiet about. Tapping it a second time changes neither and
 * does nothing, which is the honest answer to a gesture that changed nothing.
 *
 * ### Why nothing is ever REMOVED from it
 *
 * The pick's consent is spent by the once-per-launch latch
 * ([PreviewAutoFetchController.attemptedThisLaunch], via [PreviewTrigger.latchedForTheLaunch]),
 * not by this set. That keeps one owner for *"has this been acted on?"*: a set that cleared
 * itself on actuation would be a second answer to it, and the two would disagree the first time
 * an attempt was refused after it started (`start` returns false on `busy()`), leaving a pick
 * both spent and never tried. So the set only grows, and the meaning of membership is the durable
 * one — *"the user chose this language while this process was running"*.
 *
 * `"auto"` can be in it, and harmlessly: no pack's language is `"auto"`, so `decide`'s first
 * refusal answers it before any of this is read.
 */
object PreviewPicks {

    private val _picked = MutableStateFlow<Set<String>>(emptySet())

    /** Every language code the user has picked in this process. */
    val picked: StateFlow<Set<String>> = _picked.asStateFlow()

    /**
     * Record that the user picked [language]. Called from `PreferencesManager.setSelectedLanguage`
     * — the ONE writer of the selection, reached from the in-app dropdown and from onboarding's
     * Continue and from nowhere else — so no selection site can forget to, and none of them needs
     * to know this object exists.
     *
     * It records a FACT about a gesture. It starts nothing: *"a SharedPreferences writer called
     * from Compose click handlers has no business owning a download"* (the 4.4.1 amendment's own
     * rule) still holds, and the decision that turns this into an arrival is where it always was.
     */
    fun note(language: String) {
        _picked.update { it + language }
    }

    /** Whether [language] was picked in this process — [PreviewTrigger.SELECTION]'s test. */
    fun wasPicked(language: String): Boolean = language in _picked.value

    /** Test-only: this object is process-scoped, so a JVM test must be able to start from empty. */
    fun forgetAll() {
        _picked.value = emptySet()
    }
}
