package com.whispereverywhere.tts

/**
 * Pure, JVM-tested. Bounds every string handed to sherpa's generateWithCallback so no single
 * synthesis unit can outrun the audio already banked (the underrun law, spec 6A.1) and so
 * sherpa's discarded whole-utterance float[] stays small (the OOM bound, spec 6A.5).
 *
 * Constants are DERIVED from docs/measurements/2026-07-27-tts-baseline-fold6.log:
 *   character rate = 2198 chars / 96.357 s = 22.81 chars/s -> 43.84 ms/char, rounded UP to 45
 *                    (a longer estimate cuts earlier -> conservative).
 *   first-burst bank (sent seq=0 audMs) = 3262 ms.
 *   worst per-unit RTF (end rtfMax)      = 0.73.
 *   Underrun-free at the tightest (first) boundary  <=>  RTF * dur(next) <= bank:
 *       dur(next) <= 3262 / 0.73 = 4468 ms.
 *   SPLIT_MAX_CHARS = 80  -> est 3600 ms -> 0.73 * 3600 = 2628 ms <= 3262 ms (19% headroom).
 *
 * The measured rate overturns spec 6A's nominal "300 chars ~ 3.5 s" (that assumes 85.7 chars/s;
 * the device shows 22.8, so 300 chars is ~13.2 s and would still starve). The starving unit was
 * ~524 chars, which at this cap becomes ~7 chunks -- reproducing 6A's own "~7 chunks" prose.
 */
object ClauseSplitter {

    const val MS_PER_CHAR = 45L
    const val SPLIT_MAX_CHARS = 80
    const val SPLIT_TARGET_CHARS = 60

    /** Below this, a candidate boundary is ignored so we never emit a tiny sliver at a seam. */
    private const val MIN_CHARS = 20

    /** Estimated audio duration of [chars] characters at the measured device rate. */
    fun estimateAudioMs(chars: Int): Long = chars.toLong() * MS_PER_CHAR

    /**
     * Split [clean] into ordered submission units, each <= SPLIT_MAX_CHARS, text preserved.
     * A selection already within the cap is returned unchanged as a single element -- this is the
     * bit-identical fast path the caller feeds through the exact current code line. Longer input
     * is cut greedily at the strongest boundary in each window: sentence terminator, then clause
     * punctuation, then a conjunction, then any word boundary, then (last resort) a hard cut that
     * never lands inside a surrogate pair.
     */
    fun plan(clean: String): List<String> {
        if (clean.length <= SPLIT_MAX_CHARS) return listOf(clean)
        val units = ArrayList<String>()
        var i = 0
        val n = clean.length
        while (i < n) {
            if (n - i <= SPLIT_MAX_CHARS) {
                units.add(clean.substring(i))
                break
            }
            val cut = chooseCut(clean, i, i + SPLIT_MAX_CHARS)
            units.add(clean.substring(i, cut))
            i = cut
        }
        // Trim seams (sherpa trims anyway); drop nothing that carried text.
        val trimmed = units.map { it.trim() }.filter { it.isNotEmpty() }
        return if (trimmed.isEmpty()) listOf(clean) else trimmed
    }

    /** Index in (start, end] at which to end the current unit, by descending boundary priority. */
    private fun chooseCut(s: String, start: Int, end: Int): Int {
        bestTerminator(s, start, end)?.let { return it }
        bestClausePunct(s, start, end)?.let { return it }
        bestConjunction(s, start, end)?.let { return it }
        bestSpace(s, start, end)?.let { return it }
        return hardCut(s, start, end)
    }

    // A '.'/'?'/'!' that genuinely ends a sentence: followed by whitespace/end, not a decimal,
    // not a known abbreviation or single-letter initial. Cut AFTER it (keep the terminator).
    private fun isTerminatorAt(s: String, p: Int): Boolean {
        val c = s[p]
        if (c != '.' && c != '?' && c != '!') return false
        if (p + 1 < s.length && !s[p + 1].isWhitespace()) return false // ".5", ".”" etc. excluded
        if (c == '.') {
            if (p > 0 && s[p - 1].isDigit()) return false // decimal left side
            var j = p - 1
            while (j >= 0 && s[j].isLetter()) j--
            val token = s.substring(j + 1, p).lowercase()
            if (token.length == 1 || token in ABBREV) return false
        }
        return true
    }

    private val ABBREV = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "vs", "etc", "eg", "ie",
        "no", "fig", "dept", "inc", "ltd", "co", "jr", "sr",
    )

    private val CLAUSE = setOf(',', ';', ':', '—', '–') // comma semicolon colon em/en dash

    private val CONJ = setOf(
        "and", "but", "or", "nor", "so", "yet", "for", "because", "which", "that",
        "while", "when", "where", "if", "though", "although", "however", "therefore",
    )

    private fun bestTerminator(s: String, start: Int, end: Int): Int? {
        var p = end - 1
        while (p >= start) {
            if (isTerminatorAt(s, p) && p + 1 - start >= MIN_CHARS) return p + 1
            p--
        }
        return null
    }

    private fun bestClausePunct(s: String, start: Int, end: Int): Int? {
        var p = end - 1
        while (p >= start) {
            val c = s[p]
            // A hyphen counts only when spaced (punctuation dash), never inside "state-of-the-art".
            val isDash = c == '-' && p + 1 < s.length && s[p + 1].isWhitespace()
            // A ',' or ':' flanked by digits is a thousands separator ("1,234,567") or a
            // time/ratio colon ("3:30"), not a clause boundary. The isTerminatorAt decimal guard
            // protects only '.'; without this, a number is voiced split across two utterances.
            val insideNumber = (c == ',' || c == ':') &&
                p > 0 && s[p - 1].isDigit() && p + 1 < s.length && s[p + 1].isDigit()
            if (!insideNumber && (c in CLAUSE || isDash) && p + 1 - start >= MIN_CHARS) return p + 1
            p--
        }
        return null
    }

    private fun bestConjunction(s: String, start: Int, end: Int): Int? {
        var p = end - 1
        while (p > start) {
            if (s[p].isWhitespace() && p - start >= MIN_CHARS) {
                var e = p + 1
                while (e < s.length && s[e].isLetter()) e++
                if (s.substring(p + 1, e).lowercase() in CONJ) return p // cut before the conjunction
            }
            p--
        }
        return null
    }

    private fun bestSpace(s: String, start: Int, end: Int): Int? {
        var p = end - 1
        while (p > start) {
            if (s[p].isWhitespace() && p - start >= MIN_CHARS) return p
            p--
        }
        return null
    }

    private fun hardCut(s: String, start: Int, end: Int): Int {
        var e = end
        if (e in 1 until s.length && s[e - 1].isHighSurrogate()) e-- // never split a surrogate pair
        return e.coerceAtLeast(start + 1)
    }
}
