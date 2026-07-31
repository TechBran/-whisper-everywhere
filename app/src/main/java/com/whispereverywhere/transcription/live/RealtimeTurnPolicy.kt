package com.whispereverywhere.transcription.live

/**
 * The ONE predicate deciding whether the service runs its client-side VAD/commit for a session.
 * Live (server-driven) sessions bypass it — the SERVER cuts turns (server VAD / endpoint detection).
 * Local + Gemini segment + batch keep it, byte-identical. A named unit so "chunk-based paths
 * untouched" is a pinned contract ([LiveTurnPolicyTest]), not a buried `if`.
 */
object LiveTurnPolicy {
    fun runClientVad(sessionIsLive: Boolean): Boolean = !sessionIsLive
}
