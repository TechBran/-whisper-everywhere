package com.whispereverywhere.transcription.live

/**
 * The ONE predicate deciding whether the service runs its client-side VAD/commit for a session.
 * Live (server-driven) sessions bypass it — the SERVER cuts turns (server VAD / endpoint
 * detection). Local + Gemini segment + batch keep it, byte-identical. A named unit so
 * "chunk-based paths untouched" is a pinned contract ([LiveTurnPolicyTest]), not a buried `if`.
 *
 * History (2026-08-01, same day, both owner-driven): this briefly took a `playbackSource`
 * override, because device-capture audio used to be force-routed to the on-device engine where no
 * server VAD could ever cut it — bypassing the client VAD starved it of commits entirely and every
 * screen-capture segment arrived as one 30 s backstop block. Hours later the owner retired that
 * routing rule itself: device audio now follows the provider selection onto the SAME engine as mic
 * audio, so in a live session the server VAD cuts it and the override became wrong rather than
 * merely unnecessary. If a source-local engine split ever returns, the audio that stays local
 * needs its committer back — see the 6dbc342 commit.
 */
object LiveTurnPolicy {
    fun runClientVad(sessionIsLive: Boolean): Boolean = !sessionIsLive
}
