package com.whispereverywhere.ui

/**
 * Home's dismissible cloud-key note (3.5.0, Workstream B) — ONE source of truth for both the
 * card copy and the visibility rule, kept Compose- and Android-free (pattern: [HowToGuide]) so
 * [CloudKeyNoteTest] can pin the copy discipline on the JVM: the accuracy/language pitch is
 * allowed, a speed claim is not (owner decision), and the visibility truth table is exhaustive.
 *
 * Visibility: shown only while NO cloud provider is configured/selected AND the user has not
 * dismissed it. Configuring a key hides it permanently regardless of dismissal. Dismissal is the
 * card's X, persisted as `PreferencesManager.cloudNoteDismissed` and never unset.
 */
object CloudKeyNote {

    const val HEADLINE = "Want top accuracy or more languages?"

    const val BODY =
        "Add your own API key — large cloud models from OpenAI, Gemini, ElevenLabs, " +
            "or Soniox, billed to your own account."

    const val BUTTON = "Open Engines & voices"

    /** The whole visibility rule: both gates must be open. */
    fun shouldShow(cloudProviderConfigured: Boolean, dismissed: Boolean): Boolean =
        !cloudProviderConfigured && !dismissed
}
