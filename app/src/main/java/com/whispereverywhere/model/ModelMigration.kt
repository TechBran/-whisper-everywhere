package com.whispereverywhere.model

/**
 * Decides how to move a user off a retired model tier. Pure and Android-free so every state is
 * unit-testable — this logic decides whether a shipped user can still dictate, so it must not be
 * reachable only through a device.
 *
 * ORDERING IS THE WHOLE POINT: download the replacement, verify it on disk, THEN switch the
 * selection, THEN delete the old file. Any other order can leave a user with no usable model,
 * because `installedModel()` requires the selected tier's file to exist and the entire app is
 * gated on that being non-null — with onboarding offering no way back.
 */
object ModelMigration {

    sealed interface Action {
        /** Nothing to do: current tier, no selection, or an id this build does not know. */
        data object None : Action
        /** On a retired tier, online, replacement not yet downloaded. */
        data object OfferDownload : Action
        /** On a retired tier but offline. KEEP the old model working and retry later. */
        data object WaitForNetwork : Action
        /** Replacement verified on disk. Safe to switch and reclaim the old file. */
        data class SwapAndDelete(val fromId: String, val toId: String) : Action
    }

    /**
     * The pickable tier an unsupported model's users should land on. It must never be WORSE for
     * the language the user actually speaks — moving a MULTILINGUAL user to an ENGLISH-only
     * default silently breaks dictation in every other language with no warning, which was the MF3
     * bug and is the reason this function takes a scope at all.
     *
     * **4.6 — BOTH ARMS ANSWER THE SAME MULTILINGUAL RUNG, and that is the correct answer to
     * both.** The owner's ruling of 2026-09-13 retired the last English-only rung (`pro`), so
     * [WhisperCatalog.DEFAULT_MODEL_ID] is multilingual and the ENGLISH arm resolves there too. The
     * collapse is safe in exactly one direction and this is that direction: an ENGLISH-scope user
     * landing on a MULTILINGUAL rung loses nothing — whisper-small with a multilingual vocab head
     * transcribes English perfectly — whereas the reverse is MF3. The scope parameter therefore
     * still earns its place: it is what makes the unsafe direction impossible to reach, and the
     * next time an English-only rung is offered the two arms separate again without anyone
     * rediscovering why.
     *
     * **4.7 — the rung both arms answer is `small-q8`.** `multi` was retired by the Q8 ruling of
     * 2026-09-17, and a migration target must be pickable, not retired and not an instrument —
     * `decide` moves a user who did not ask to be moved, so it may only move them onto a rung with
     * a card in the picker and a throughput verdict that clears. `small-q8` is the same
     * whisper-small weights as `multi` at Q8_0, measured to keep up with margin on the owner's
     * tablet (`docs/measurements/2026-09-17-tab-cpu-ladder.md`; `TierThroughputRecord.SMALL_Q8`),
     * and 264 MB against `extreme`'s 539 — so the Settings card's two promises ("much faster",
     * "free up the space") stay true for the one source that can reach it.
     *
     * **The two constants stay SEPARATE even though they are equal today.** Folding
     * [MULTILINGUAL_TARGET_ID] into `DEFAULT_MODEL_ID` would mean a future English default
     * silently becomes the multilingual target as well — which is MF3 reintroduced by a
     * refactor, not by a decision.
     *
     * Pinned exhaustively by `ModelMigrationTest`: over every [ModelScope], the target must be
     * pickable, must not be retired, must not be a [WhisperModel.instrument], and must be able to
     * transcribe the scope it is a target for.
     */
    fun targetIdFor(scope: ModelScope): String =
        if (scope == ModelScope.MULTILINGUAL) MULTILINGUAL_TARGET_ID else WhisperCatalog.DEFAULT_MODEL_ID

    private const val MULTILINGUAL_TARGET_ID = "small-q8"

    fun decide(
        selectedId: String?,
        selectedInstalled: Boolean,
        targetInstalled: Boolean,
        online: Boolean,
    ): Action {
        val selected = selectedId?.let { WhisperCatalog.byId(it) } ?: return Action.None
        // `unsupported`, not `retired` (3.7 Workstream H): a merely retired tier is hidden from
        // the chooser and otherwise left completely alone — its installed users are not prompted,
        // not migrated, and never asked to re-download.
        //
        // 4.6 leans on this line harder than any release has: `pro` joins the retired set and it
        // is the tier the LARGEST number of English users are on. Every one of them must fall
        // through here to Action.None — nobody dictating happily on small.en gets told to fetch
        // 190 MB they never asked for, for a model whose only difference is its vocab head.
        //
        // 4.7 leans on it again, and harder: `multi` — the shipped default of 4.6.0, in
        // PRODUCTION — joins the retired set with the Q8 ruling of 2026-09-17, and so do
        // `medium-q5`, `ultra` and `large-v3`. Every one of those users falls through here to
        // Action.None: their model works, nothing is downloaded for them, no card is raised.
        if (!selected.unsupported) return Action.None
        val target = targetIdFor(selected.scope)
        // Target on disk wins regardless of connectivity — nothing left to download.
        if (targetInstalled) return Action.SwapAndDelete(selected.id, target)
        return if (online) Action.OfferDownload else Action.WaitForNetwork
    }
}
