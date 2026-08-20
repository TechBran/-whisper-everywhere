package com.whispereverywhere.transcription

/**
 * Session-scoped language pin for auto-language local sessions (3.6.0 Workstream B).
 *
 * Multilingual whisper pays a throwaway language-detect encoder pass on EVERY segment when
 * language = auto — roughly half of multi's steady-state native cost. This object remembers the
 * language whisper detected on the session's first segment that actually produced speech, so
 * every later segment passes the concrete code and skips the detect pass entirely.
 *
 * Rules (spec 2026-08-19, Workstream B):
 *  - Only auto sessions participate: an explicit session language passes through unchanged
 *    and NEVER pins. PreferencesManager.getLanguageForApi() already maps "auto" -> null, and
 *    FloatingBubbleService forces "en" for ENGLISH-scope tiers before connect(), so
 *    sessionLanguage == null here is exactly "the user chose auto on a multilingual tier".
 *  - The pin is per-session: [reset] runs from LocalWhisperEngine.connect(). A user switching
 *    spoken language MID-session keeps the first detection until the next session — accepted
 *    trade, recorded in the spec.
 *  - First detection wins; unusable detections (null / blank / "auto") never pin.
 *
 * Threading: [onDetected] runs on the engine's single native-executor thread; [reset] on the
 * connect() caller thread; [languageFor] on the executor thread. A single @Volatile reference
 * suffices — there is no compound invariant across fields, and the engine only calls
 * [onDetected] behind its stale-listener guard, so a dead session's late segment never writes.
 */
class LanguagePin {
    @Volatile private var pinned: String? = null

    /**
     * The language to pass to the next transcribe. An explicit [sessionLanguage] always wins;
     * an auto session gets the pinned code once detected — null (= native auto-detect) before.
     */
    fun languageFor(sessionLanguage: String?): String? = sessionLanguage ?: pinned

    /**
     * Records a detection. No-ops unless this is an auto session ([sessionLanguage] == null),
     * nothing is pinned yet, and [detected] is a usable code.
     */
    fun onDetected(sessionLanguage: String?, detected: String?) {
        if (sessionLanguage != null) return
        if (pinned != null) return
        if (detected.isNullOrBlank() || detected == "auto") return
        pinned = detected
    }

    /** Clears the pin. Called at session start (LocalWhisperEngine.connect). */
    fun reset() {
        pinned = null
    }
}
