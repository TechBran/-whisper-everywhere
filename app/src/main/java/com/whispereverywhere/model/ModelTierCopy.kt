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
 * MULTILINGUAL tier. Relative speed words BETWEEN on-device tiers ("Fastest", "slower") are
 * factual and allowed; the app-wide no-speed-claims rule constrains CLOUD claims, which this
 * copy never makes.
 */
object ModelTierCopy {

    /** One tier's card copy: a positioning headline, badge chips, and one honest sentence. */
    data class TierCopy(val headline: String, val badges: List<String>, val body: String)

    private val copyById: Map<String, TierCopy> = mapOf(
        "eco" to TierCopy(
            headline = "Fastest",
            badges = listOf("English only", "60 MB"),
            body = "Real-time dictation on any phone; the lightest download.",
        ),
        "base" to TierCopy(
            headline = "Fast",
            badges = listOf("90+ languages", "60 MB"),
            body = "Quick everyday dictation in most languages; lighter accuracy than the big " +
                "multilingual tier.",
        ),
        "pro" to TierCopy(
            headline = "Best English accuracy",
            badges = listOf("English only", "190 MB"),
            body = "Noticeably slower than Eco, noticeably sharper.",
        ),
        "multi" to TierCopy(
            headline = "Best multilingual accuracy",
            badges = listOf("90+ languages", "190 MB"),
            body = "The pick for non-English dictation.",
        ),
    )

    /** Copy for an offered tier id; null for retired or unknown ids (callers fall back). */
    fun forId(id: String): TierCopy? = copyById[id]
}
