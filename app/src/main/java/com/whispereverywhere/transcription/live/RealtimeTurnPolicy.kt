package com.whispereverywhere.transcription.live

import com.whispereverywhere.provider.ProviderId

/**
 * The ONE predicate deciding whether the service runs its client-side VAD/commit for a session.
 * Server-driven live sessions bypass it — the SERVER cuts turns (server VAD / endpoint detection).
 * Local + batch keep it, byte-identical — and so does a Gemini LIVE session since 4.3.4, because
 * Gemini's own VAD goes deaf after the first sentence of a recording (T0 2026-09-10, finding 2:
 * 44 of 47 finals over 590 s were "Ask not.") and the only mode that transcribes everything is
 * MANUAL VAD driven by the app's endpointer. The live predicate is therefore per PROVIDER
 * ([clientCutsLiveTurns]), not per session kind. A named unit so "chunk-based paths untouched" is
 * a pinned contract ([LiveTurnPolicyTest]), not a buried `if`.
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
    /**
     * True when a LIVE session on [provider] must keep the app's endpointer as its turn cutter —
     * Gemini (manual-VAD activities, `serverDriven = false` on the engine). OpenAI, ElevenLabs and
     * Soniox segment server-side and return false.
     */
    fun clientCutsLiveTurns(provider: ProviderId): Boolean = provider == ProviderId.GEMINI

    /**
     * [liveProvider] is the session's live provider, or null for any non-live session (it is
     * ignored then). A live session with no provider named is treated as server-driven — the
     * pre-4.3.4 reading, which every existing call with one argument keeps.
     */
    fun runClientVad(sessionIsLive: Boolean, liveProvider: ProviderId? = null): Boolean =
        !sessionIsLive || (liveProvider != null && clientCutsLiveTurns(liveProvider))
}
