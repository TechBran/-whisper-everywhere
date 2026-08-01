package com.whispereverywhere.transcription.live

/**
 * The ONE predicate deciding whether the service runs its client-side VAD/commit for the audio it
 * is capturing right now. Live (server-driven) sessions bypass it for MIC audio — the SERVER cuts
 * turns (server VAD / endpoint detection). Local + Gemini segment + batch keep it, byte-identical.
 * A named unit so "chunk-based paths untouched" is a pinned contract ([LiveTurnPolicyTest]), not a
 * buried `if`.
 *
 * [playbackSource] overrides the live bypass, and must: device-audio capture NEVER reaches the
 * cloud (the SourceRoutedTranscriptionEngine routes it to the on-device engine), so no server VAD
 * ever sees it — the client VAD is the ONLY thing that can cut its turns. Gating on the session
 * alone starved the local engine of commits for the whole capture, so every device-audio segment
 * in a live session arrived as one 30 s backstop block (owner-reported 2026-08-01: "generating in
 * big box").
 */
object LiveTurnPolicy {
    fun runClientVad(sessionIsLive: Boolean, playbackSource: Boolean): Boolean =
        !sessionIsLive || playbackSource
}
