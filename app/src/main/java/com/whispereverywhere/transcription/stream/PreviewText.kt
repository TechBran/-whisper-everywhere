package com.whispereverywhere.transcription.stream

/** The strip's text rules — pure, pinned by PreviewTextTest. */
object PreviewText {

    /**
     * Trim, and fold to lowercase where the pack's own vocabulary has no case to lose.
     *
     * **The fold is conditional now, and the locale is the pack's.** The shipping English model
     * emits ALL CAPS (rung 1 §1.3: 495 uppercase-bearing pieces, and the only lowercase in its
     * tokens.txt is the three specials), so folding is lossless and the strip must not shout —
     * `emitsCase = false`, and this is byte-for-byte what 4.4.1 did. A pack whose vocabulary
     * carries BOTH cases (ko, et, tr, every Kroko build) has case that means something: folding it
     * paints `nba` over the `NBA` the model produced, and a German reader reads a lowercased noun
     * as WRONG rather than as rough (qualification table §4.1).
     *
     * `Locale.US` was chosen for English INPUT on purpose — it is the one spelling that can never
     * produce a Turkish dotless `ı`. That reasoning is about the INPUT and does not transfer to
     * Turkish OUTPUT, where folding `İ` under `Locale.US` is exactly the hazard the old comment
     * named; so the locale comes off [StreamingPack.normalizeLocale] and a pack must not ship
     * until that field is its own.
     */
    fun normalize(raw: String, pack: StreamingPack): String {
        val trimmed = raw.trim()
        return if (pack.emitsCase) trimmed else trimmed.lowercase(pack.normalizeLocale)
    }

    /**
     * The tokens that END before [cutS] seconds, concatenated — the AAR's tokens carry a LEADING
     * SPACE (rung 3 §2.2: `" F", "OUR"`), so a bare concatenation reproduces the words and their
     * boundaries. Used at a cap cut with a retained tail: the tail's words re-appear from the
     * fresh stream, so they must not also stay frozen. Timestamps shorter than the tokens cannot
     * be trusted to trim; the whole text is returned instead of a silent truncation.
     */
    fun before(result: PreviewResult, cutS: Float, pack: StreamingPack): String {
        if (result.timestamps.size < result.tokens.size) return normalize(result.text, pack)
        val sb = StringBuilder()
        for (i in result.tokens.indices) {
            if (result.timestamps[i] < cutS) sb.append(result.tokens[i])
        }
        return normalize(sb.toString(), pack)
    }
}
