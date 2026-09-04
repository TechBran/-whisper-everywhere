package com.whispereverywhere.transcription

import com.whispereverywhere.audio.Endpointer

/**
 * THE SPEECH EVIDENCE a commit carries (4.3.2): how many milliseconds of the buffer being cut the
 * endpointer's probe scored at or above ONSET — or [UNKNOWN], when nobody counted.
 *
 * It travels with the commit and nowhere else: the service's commit funnel reads it off
 * [Endpointer.speechEvidenceMs] once, immediately before it hands the buffer to the engine, and
 * passes it through [TranscriptionEngine.commit]. `LocalWhisperEngine` is the ONE consumer that
 * acts on it — a KNOWN value under [com.whispereverywhere.audio.EndpointerTuning.MIN_SPEECH_EVIDENCE_MS]
 * resolves the segment `EmptyExpected` without an encode. Every cloud engine and the fallback
 * wrapper drop it on the interface's default body.
 *
 * UNKNOWN means "never skipped". It is what the amplitude fallback endpointer answers, what a
 * Silero endpointer answers before its probe has scored a frame of this buffer or after the
 * slow-probe cutout, what every engine-internal commit carries (the 30 s overflow backstop, the
 * cloud fallback's local rescue), and what [of] makes of ANY negative number — so a future
 * sentinel on the endpointer side still lands here as "transcribe", not as a count.
 *
 * A plain data class rather than an inline value class, deliberately: it is allocated once per
 * commit — at most a few times a second — and a readable `toString` in a test failure is worth
 * more than the boxing it saves.
 *
 * @param speechMs the count, or a negative number for UNKNOWN. Prefer [of] and [UNKNOWN] to the
 *        constructor so the sentinel is spelled in one place.
 */
data class SpeechEvidence(val speechMs: Long) {

    /** A count was taken: the endpointer scored every frame of this buffer. */
    val isKnown: Boolean get() = speechMs >= 0L

    /**
     * THE SKIP RULE: KNOWN and strictly under [floorMs]. UNKNOWN is never under any floor, and
     * exactly the floor is not under it — 256 ms of evidence is encoded.
     */
    fun isUnder(floorMs: Long): Boolean = isKnown && speechMs < floorMs

    companion object {
        /** Nobody counted: transcribe as every version before 4.3.2 did. */
        val UNKNOWN = SpeechEvidence(Endpointer.UNKNOWN_SPEECH_EVIDENCE_MS)

        /** [Endpointer.speechEvidenceMs]'s answer as evidence: any negative is [UNKNOWN]. */
        fun of(speechMs: Long): SpeechEvidence =
            if (speechMs < 0L) UNKNOWN else SpeechEvidence(speechMs)
    }
}
