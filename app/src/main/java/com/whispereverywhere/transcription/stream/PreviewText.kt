package com.whispereverywhere.transcription.stream

import java.util.Locale

/** The strip's text rules — pure, pinned by PreviewTextTest. */
object PreviewText {

    /** The model emits ALL CAPS (rung 1 §1.3); the strip shows lowercase. Locale.US: never a Turkish dotless i. */
    fun normalize(raw: String): String = raw.trim().lowercase(Locale.US)

    /**
     * The tokens that END before [cutS] seconds, concatenated — the AAR's tokens carry a LEADING
     * SPACE (rung 3 §2.2: `" F", "OUR"`), so a bare concatenation reproduces the words and their
     * boundaries. Used at a cap cut with a retained tail: the tail's words re-appear from the
     * fresh stream, so they must not also stay frozen. Timestamps shorter than the tokens cannot
     * be trusted to trim; the whole text is returned instead of a silent truncation.
     */
    fun before(result: PreviewResult, cutS: Float): String {
        if (result.timestamps.size < result.tokens.size) return normalize(result.text)
        val sb = StringBuilder()
        for (i in result.tokens.indices) {
            if (result.timestamps[i] < cutS) sb.append(result.tokens[i])
        }
        return normalize(sb.toString())
    }
}
