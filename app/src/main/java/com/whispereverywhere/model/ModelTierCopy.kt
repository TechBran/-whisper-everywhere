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
    )

    /** Copy for an offered tier id; null for retired or unknown ids (callers fall back). */
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
     * The chip marking the steered card. Names the REASON — "Default" alone never explained why
     * this card and not the other one, and for a non-English user the catalog default and the
     * right answer are different tiers.
     */
    const val STEER_BADGE = "Best match for your language"
}
