package com.whispereverywhere.tts

/**
 * Chars still to synthesise → estimated audio, by the same 45 ms/char the clause splitter's caps
 * were derived with (`ClauseSplitter.MS_PER_CHAR`). One home for the conversion so the start gate
 * and the scrubber's estimated total cannot disagree.
 */
object TtsRemainingEstimate {
    fun ms(chars: Int): Long = chars.coerceAtLeast(0) * ClauseSplitter.MS_PER_CHAR
    fun samples(chars: Int, sampleRate: Int): Long = ms(chars) * sampleRate / 1000
}
