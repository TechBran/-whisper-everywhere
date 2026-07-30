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

    /**
     * The unit cap for CLOUD synthesis. Cloud inverts the local economics [SPLIT_MAX_CHARS] was
     * derived for: a unit's fetch cost is a network round-trip INDEPENDENT of its audio length, so
     * the smaller the unit the worse the ratio — an 80-char unit banks ~3.6 s of audio but still
     * costs a full POST (commonly 1-4 s, worse on cellular), and whenever RTT(N+1) > audio_dur(N)
     * the bank drains and playback stutters at every clause boundary. The producer loop is only ever
     * ONE unit ahead (runBlocking per unit), so smaller units cannot be hidden by pipelining.
     *
     * The local OOM/underrun rationale for 80 does NOT bind cloud: cloud PCM never becomes a sherpa
     * whole-utterance float[] (only its decoded PCM appends to the bank), so the cap can be raised
     * to bank enough audio to hide the next fetch's RTT. 220 chars ~ 9.9 s of audio at MS_PER_CHAR,
     * comfortably over a 1-4 s RTT. On cloud FALLBACK the failed unit is re-split back down to
     * [SPLIT_MAX_CHARS] before it reaches sherpa, so the local bound is preserved for on-device.
     *
     * Trade-off: a larger FIRST unit (TTFW = its full fetch). Accepted as the simplest correct fix;
     * a progressive first-small-then-large cap is a possible later refinement. Real per-provider RTT
     * is unmeasured here and should be validated on device.
     */
    const val CLOUD_SPLIT_MAX_CHARS = 220

    /**
     * The comfortable aim point (0.73 * 60 * 45 = 1971 ms << 3262 ms bank). A long sentence is cut
     * as LATE as the cap allows, not down to this target, because fewer/larger fragments mean fewer
     * synthesized seam boundaries (findings on per-unit overhead and seam prosody). The target is
     * used only to place a balanced cut when [rebalanceTail] re-splits a merged tail.
     */
    const val SPLIT_TARGET_CHARS = 60

    /** Below this, a candidate boundary is ignored so we never emit a tiny sliver at a seam. */
    private const val MIN_CHARS = 20

    /** Estimated audio duration of [chars] characters at the measured device rate. */
    fun estimateAudioMs(chars: Int): Long = chars.toLong() * MS_PER_CHAR

    /**
     * Split [clean] into ordered submission units, each <= SPLIT_MAX_CHARS, text preserved.
     *
     * The split is SENTENCE-SCOPED, not window-scoped. A selection within the cap is returned
     * unchanged (the bit-identical fast path). Otherwise the text is first cut into sentences at
     * genuine terminators, then EACH sentence within the cap is fed WHOLE and only a sentence that
     * alone exceeds the cap is sub-split. This matters because sherpa phonemises per sentence
     * (batch_size=1, sentence-scoped espeak): feeding whole sub-cap sentences yields exactly the
     * per-sentence audio sherpa would produce internally from the joined text, so common prose
     * stays bit-identical. Only a genuinely long sentence -- the one shape that starves the bank
     * (baseline seq=1: 524 chars, synthMs 12347 > 3262 ms first burst -> the measured 9 s gap) --
     * is cut mid-clause, and that mid-clause prosody delta is the accepted, underrun-mandated cost.
     *
     * Feeding a >cap sentence whole is NOT a safe alternative: an early one would starve the bank
     * (200 chars -> 0.73*200*45 = 6570 ms synth > 3262 ms bank), exactly the seq=1 failure. So the
     * whole-feed threshold stays at the bank-safe cap, not a larger char count.
     *
     * A long sentence is cut greedily at the strongest boundary in each window: clause punctuation,
     * then a conjunction, then any word boundary, then (last resort) a hard cut that never lands
     * inside a surrogate pair; a short trailing remainder is rebalanced rather than left a sliver.
     */
    fun plan(clean: String, maxChars: Int = SPLIT_MAX_CHARS): List<String> {
        if (clean.length <= maxChars) return listOf(clean)
        val out = ArrayList<String>()
        for (sentence in splitIntoSentences(clean)) {
            if (sentence.length <= maxChars) out.add(sentence) else out.addAll(splitLongSentence(sentence, maxChars))
        }
        // Trim seams (sherpa trims anyway); drop nothing that carried text.
        val trimmed = out.map { it.trim() }.filter { it.isNotEmpty() }
        return if (trimmed.isEmpty()) listOf(clean) else trimmed
    }

    /** Cut [s] after every genuine terminator; the remainder (no trailing terminator) is the tail. */
    private fun splitIntoSentences(s: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var p = 0
        while (p < s.length) {
            if (isTerminatorAt(s, p)) {
                out.add(s.substring(start, p + 1))
                start = p + 1
            }
            p++
        }
        if (start < s.length) out.add(s.substring(start))
        return out
    }

    /** Greedily bound a single over-cap sentence into <= [maxChars] units, no sliver tail. */
    private fun splitLongSentence(s: String, maxChars: Int): List<String> {
        val units = ArrayList<String>()
        var i = 0
        val n = s.length
        while (i < n) {
            if (n - i <= maxChars) {
                units.add(s.substring(i))
                break
            }
            val cut = chooseCut(s, i, i + maxChars)
            units.add(s.substring(i, cut))
            i = cut
        }
        rebalanceTail(units, maxChars)
        return units
    }

    /**
     * Greedy cutting dumps the leftover of the last window as the final unit, which can be a few
     * chars — a sliver clause synthesised as its own utterance gets isolated falling-pitch prosody.
     * If the final unit is below [MIN_CHARS], fold it back into its predecessor: re-split the pair
     * at the [SPLIT_TARGET_CHARS] aim point so neither piece is a sliver, both stay within the cap
     * and land on a real boundary (surrogate-safe). A lone unbroken token is the one case a piece
     * may still be short.
     */
    private fun rebalanceTail(units: MutableList<String>, maxChars: Int) {
        if (units.size < 2) return
        val last = units.last()
        if (last.trim().length >= MIN_CHARS) return
        val prev = units[units.size - 2]
        val merged = prev + last // exact content; seams are trimmed by the caller
        units.removeAt(units.size - 1)
        units.removeAt(units.size - 1)
        if (merged.length <= maxChars) {
            units.add(merged)
        } else {
            // Aim the cut at the target; clamp so the second piece is still >= MIN. Since merged is
            // in (cap, cap+MIN), merged.length - MIN > SPLIT_TARGET_CHARS, so the target wins and
            // both pieces satisfy MIN_CHARS <= piece <= SPLIT_MAX_CHARS. chooseCut never returns
            // below MIN except via hardCut, which here returns exactly `end` (>= MIN).
            val end = minOf(SPLIT_TARGET_CHARS, merged.length - MIN_CHARS)
            val cut = chooseCut(merged, 0, end)
            units.add(merged.substring(0, cut))
            units.add(merged.substring(cut))
        }
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
