package com.whispereverywhere.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A monotonically increasing counter that changes every time a model's files land on disk — the
 * key Compose surfaces re-read device state on (4.0, Q7b fix round, finding I1).
 *
 * ### Why a counter and not a Boolean, an id, or a `SharedFlow`
 *
 * `PreferencesManager.modelInstalled` already exists and is the right shape for its consumer: a
 * `SharedFlow<Unit>` that the bubble collects to schedule a prewarm. It is the wrong shape for a
 * Compose **key**, because a key is compared by value and `Unit` never differs from `Unit`.
 *
 * A `StateFlow<Boolean>` ("installed") or `StateFlow<String>` ("the installed id") would conflate,
 * and the conflation is not hypothetical — `WhisperModelManager.verifyDest` already carries the
 * note that *"onboarding writes the id BEFORE the file exists and rewrites the SAME id after, which
 * a StateFlow conflates away entirely."* Re-importing the same tier, or replacing a corrupt file
 * with a good one, produces the same value twice and no recomposition. A counter cannot conflate:
 * every install is a distinct value, forever.
 *
 * ### Why it is an object with no `Context`
 *
 * So that it can be **executed by a JVM test**. This project has no Robolectric and no mocking
 * framework, so anything reachable only through `PreferencesManager(Context)` or
 * `WhisperModelManager(Context, …)` can be pinned as source text but never run — which is exactly
 * how the Q7b battery's two survivors happened. The signal is the half of this mechanism that
 * *can* be proved by execution, so it is kept free of Android; the wiring around it
 * ([PreferencesManager.notifyModelInstalled] bumping it, and the two chooser producers keying on
 * it) is pinned by `ChooserSteerWiringPinTest`.
 *
 * ### Who bumps it
 *
 * Exactly one place: [com.whispereverywhere.data.local.PreferencesManager.notifyModelInstalled],
 * which `WhisperModelManager.verifyDest` calls after the size and sha256 gates have both passed —
 * so the signal is never sent for a file that gets deleted a line later. **Q8's SAF importer must
 * call the same function**; without it the `npu` card stays hidden in the very composition that
 * just imported its assets, which is the "I imported the models but the tier never appeared"
 * failure this exists to prevent.
 */
object ModelInstallSignal {

    private val _generation = MutableStateFlow(0)

    /**
     * Changes on every install. Read as a Compose key
     * (`produceState(initialValue = false, key1 = installGeneration)`), never for its magnitude —
     * the value is an opaque generation, not a count of anything a user would recognise.
     */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    /**
     * One install landed. Atomic via [update] because the callers are `Dispatchers.IO` download
     * and import coroutines, and two finishing together must not lose one another's bump.
     */
    fun bump() {
        _generation.update { it + 1 }
    }
}
