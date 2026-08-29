package com.whispereverywhere.model

/**
 * The single source of truth for tier descriptions — consumed by BOTH the guided onboarding's
 * model-choice cards and the Settings manual picker (OnboardingModelScreen), so the two surfaces
 * can never drift apart. Pattern: HowToGuide — pure, Compose- and Android-free, every string a
 * JVM test subject.
 *
 * Copy discipline, pinned by [ModelTierCopyTest] (the test that would have prevented the Bengali
 * review): every offered tier states a size, a speed-vs-accuracy position, and its language
 * coverage as a badge — "English only" on every ENGLISH-scope tier, "90+ languages" on every
 * MULTILINGUAL tier. Since 3.7 the lineup is two tiers, so the copy positions each one directly
 * instead of by comparison to a retired card
 * ([ModelTierCopyTest.no_offered_tier_names_a_retired_one]); the app-wide no-speed-claims rule
 * constrains CLOUD claims, which this copy never makes.
 */
object ModelTierCopy {

    /** One tier's card copy: a positioning headline, badge chips, and one honest sentence. */
    data class TierCopy(val headline: String, val badges: List<String>, val body: String)

    private val copyById: Map<String, TierCopy> = mapOf(
        "pro" to TierCopy(
            headline = "Best English accuracy",
            badges = listOf("English only", "190 MB"),
            // 3.7 Workstream H: the old body read "Noticeably slower than Eco, noticeably
            // sharper" — a comparison to a tier the user can no longer see. pro is now the
            // English flagship, so the copy positions it directly.
            body = "The sharpest on-device English dictation this app ships.",
        ),
        "multi" to TierCopy(
            headline = "Best multilingual accuracy",
            badges = listOf("90+ languages", "190 MB"),
            body = "The pick for non-English dictation.",
        ),
        // 4.0: the gated tier. Only devices that pass the SoC gate AND have both context binaries
        // installed ever see this card, so the copy may speak about "this device" in the present
        // tense. The comparison is OUR OWN before/after on THAT device — same model as Multilingual,
        // different processor — never a claim about another app and never an absolute ("instant",
        // "real-time"), which the app-wide no-speed-claims rule forbids everywhere.
        "npu" to TierCopy(
            headline = "Fastest multilingual",
            // 358 MB = the PAIR (encoder 132,927,488 + decoder 225,316,864). The badge states what
            // the user downloads and stores, not the one file WhisperModel.fileName names.
            badges = listOf("90+ languages", "358 MB"),
            body = "Runs on your phone's AI chip. Same model as Multilingual, much faster on this device.",
        ),
        // 4.1: the second gated tier. "Best quality" is the spec's owner-approved framing
        // (decision 8) and ", slower" is the disclosure the house rules require beside it. The
        // body states the trade and then declines to make a speed claim at all: no WER has been
        // measured for any w8a16 Whisper variant, so "the reason to pick it is the words" is the
        // most this copy is entitled to say — the owner's A/B is what measures the rest. The
        // comparison is OUR OWN other NPU card, never another app, never an absolute.
        "npu-turbo" to TierCopy(
            headline = "Best quality, slower",
            // 1072 MB = the PAIR (encoder 775,831,552 + decoder 295,854,080), same rule as npu's
            // badge: what the user installs, not the one file WhisperModel.fileName names.
            badges = listOf("90+ languages", "1072 MB"),
            body = "Large-v3's own encoder, on your phone's AI chip. Bigger and slower than " +
                "Multilingual on NPU — the reason to pick it is the words, not the speed.",
        ),
    )

    /**
     * Copy for an offered tier id; null for retired or unknown ids (callers fall back).
     *
     * "Offered" includes the gated `npu` tier, which is out of [WhisperCatalog.pickable] by design
     * — copy exists for every tier a chooser can render, and the gate decides whether it renders.
     */
    fun forId(id: String): TierCopy? = copyById[id]

    /**
     * The tier a fresh install is steered to, from the device's primary language tag (3.7,
     * Workstream H). English-locale users get "pro" — the English flagship; everyone else gets
     * "multi", the international tier. It is a STEER, never a lock: both cards stay tappable and
     * [com.whispereverywhere.ui.onboarding.OnboardingLogic.TIER_SWITCH_HINT] still promises the
     * switch. Accepts either separator ("en-US", "en_GB") and any case, because callers pass
     * whatever `Locale.toLanguageTag()` / `Locale.getLanguage()` handed them.
     */
    fun steerIdForLanguageTag(languageTag: String): String =
        if (languageTag.substringBefore('-').substringBefore('_').lowercase() == "en") "pro"
        else "multi"

    /**
     * [steerIdForLanguageTag] with the gated tiers folded in: on a device that can actually run
     * `npu`, a non-English locale is steered THERE instead of to `multi`.
     *
     * **`npu` substitutes for the MULTILINGUAL steer and nothing else.** It carries `multi`'s
     * weights on faster silicon, so for the user `multi` was already the right answer for, it is a
     * strictly better one. An English locale keeps `pro`: steering an English speaker onto a
     * multilingual tier is the Bengali-review defect mirrored, and "the device is fast" is not a
     * reason to hand someone the less accurate model for their language.
     *
     * **`npu-turbo` NEVER steers** (4.1, decision 8). The steer is a promotion, and turbo's claim
     * is unproved in the one way that would justify one: no WER has been measured for any w8a16
     * Whisper variant, and its card says "slower" out loud. It joins the lineup below the steer
     * and waits to be picked — the body of this function names `"npu"` and nothing else, so a
     * turbo-only offer set changes NOTHING about steering.
     *
     * **This is a STEER, not a selection.** Nothing here writes `prefs.selectedModelId`; both
     * chooser surfaces still require a tap, `WhisperCatalog.DEFAULT_MODEL_ID` stays `pro` and
     * `ModelMigration`'s multilingual target stays `multi`. A gated tier that could become the
     * default by locale alone would be selected on devices whose assets are absent.
     *
     * @param offeredGatedIds the caller's gate answer — the ids of gated tiers this device may
     *        offer (`WhisperEverywhereApp.offeredNpuTierIds()`: the SoC gate, the QNN probe AND
     *        that tier's own files on disk). `emptySet()` reproduces [steerIdForLanguageTag]
     *        exactly, and so does any set without `"npu"` in it.
     */
    fun steerIdForLanguageTagFor(languageTag: String, offeredGatedIds: Set<String>): String {
        val cpuSteer = steerIdForLanguageTag(languageTag)
        return if ("npu" in offeredGatedIds && cpuSteer == "multi") "npu" else cpuSteer
    }

    /**
     * Every offered tier id with the [steerIdForLanguageTag] one FIRST (3.7, Workstream H). Both
     * chooser surfaces render this list, so the steer is one rule rather than two. It is a
     * permutation of [WhisperCatalog.pickable] by construction — a tier this object has never
     * heard of still reaches the user, just not at the top.
     *
     * The ungated contract, unchanged: this is [orderedForLanguageTagFor] with the gate answered
     * with the empty set, which is the answer for every device that cannot run a gated tier.
     * Delegating rather than duplicating is deliberate — two copies of an ordering rule drift, and
     * the one that drifts is always the one nobody is reading.
     */
    fun orderedForLanguageTag(languageTag: String): List<String> =
        orderedForLanguageTagFor(languageTag, emptySet())

    /**
     * [orderedForLanguageTag] over the tiers THIS device can pick — `WhisperCatalog.pickableFor`,
     * so each gated tier is in the lineup exactly where the caller's gate says yes for it.
     *
     * **Two ordering keys, and the second one is the point.** First the steer
     * ([steerIdForLanguageTagFor]); then the tier the locale would have been steered to WITHOUT
     * the gate; then catalog order. Without that middle key a Bengali user on a capable device
     * would read `npu, pro, multi` — the English-only tier promoted above the multilingual one it
     * was demoted below in 3.7, by a change that was supposed to be about silicon. The sort is
     * stable, so every tier neither key names keeps the order the catalog declares it in —
     * `npu-turbo`, which never steers, simply joins the lineup below the steer, last in catalog
     * order.
     *
     * The result is a permutation of `pickableFor(offeredGatedIds)` — of the caller's OWN input
     * list, not of [WhisperCatalog.pickable] — so a gate-passing device never loses a card to a
     * rule written for the ungated lineup.
     */
    fun orderedForLanguageTagFor(languageTag: String, offeredGatedIds: Set<String>): List<String> {
        val ids = WhisperCatalog.pickableFor(offeredGatedIds).map { it.id }
        val steer = steerIdForLanguageTagFor(languageTag, offeredGatedIds)
        val languageSteer = steerIdForLanguageTag(languageTag)
        return ids.sortedBy {
            when (it) {
                steer -> 0
                languageSteer -> 1
                else -> 2
            }
        }
    }

    /**
     * The chip marking the steered card. Names the REASON — "Default" alone never explained why
     * this card and not the other one, and for a non-English user the catalog default and the
     * right answer are different tiers.
     */
    const val STEER_BADGE = "Best match for your language"
}
