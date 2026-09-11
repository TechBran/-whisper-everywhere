package com.whispereverywhere.transcription.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * THE LANGUAGES THIS PROCESS HAS WATCHED THE USER PICK — the one fact
 * [PreviewAutoFetch.decide] cannot read off a preference, and the whole of ruling 3b's *"a
 * SELECTION downloads on ANY connection, at once"* (owner, 2026-09-11).
 *
 * `PreferencesManager.selectedLanguage` answers *"which language is selected"*. It cannot answer
 * *"did the user just choose it"*: a `StateFlow` replays its current value to every new collector,
 * so a language chosen ten seconds ago and one chosen before the app was last updated arrive at
 * the decision identically. That difference is exactly what ruling 3a and ruling 3b divide on —
 * an unasked top-up waits for wifi, a pick spends the connection at once — so it has to be
 * recorded when it happens, by the one writer that sees it happen.
 *
 * ### Why it is PROCESS-scoped and not a preference
 *
 * A pick made yesterday is a standing SELECTION, not a live consent to spend today's data. The
 * brief states the consequence it is chosen for: *"a cellular user who already had that language
 * selected still gets the 4.4.1 card and has to tap it"*. Persisting this set would turn every
 * launch into a pick and delete ruling 3a's unasked half.
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
 * to this set the first time — so tapping your own language in the picker, on cellular, starts
 * the pack the top-up was waiting for wifi to fetch. Tapping it a second time changes neither and
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
