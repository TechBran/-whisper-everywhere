package com.whispereverywhere.text

/**
 * The single melt-proof joining policy for the whole app. Given text runs, it decides the ONE
 * question every assembly site keeps re-answering ad hoc: does a space belong at this boundary?
 *
 * Rules (the melt invariant): two runs are joined so that
 *  - two alphanumeric characters never touch across the boundary,
 *  - a single space is inserted only when needed — never a double,
 *  - a run beginning with closing punctuation [CLOSING] attaches with NO leading space,
 *  - an existing space on either side is respected (the join is a no-op there).
 *
 * Pure and Android-free; every case is a JVM unit test.
 */
object TextJoin {

    /** A run starting with one of these attaches to the previous run WITHOUT a leading space. */
    const val CLOSING: String = ".,!?;:)]}"

    /** Trim a single chunk to the shape the join rules assume (no leading/trailing whitespace). */
    fun normalize(chunk: String): String = chunk.trim()

    /** True iff a single space belongs between [left]'s last char and [right]'s first char. */
    fun needsSpace(left: CharSequence, right: CharSequence): Boolean {
        if (left.isEmpty() || right.isEmpty()) return false
        val lc = left[left.length - 1]
        val rc = right[0]
        if (lc.isWhitespace() || rc.isWhitespace()) return false
        if (rc in CLOSING) return false
        // Non-spacing scripts (Han/Kana/Thai …) are NOT space-delimited: inserting a space between
        // two of their characters corrupts the text ('你好'+'世界' must stay '你好世界'). The melt
        // invariant only applies to space-delimited runs, so suppress the space when BOTH boundary
        // chars are non-spacing. A mixed boundary (Latin↔CJK) still gets the space.
        if (isNonSpacing(lc) && isNonSpacing(rc)) return false
        return true
    }

    /** A character from a script that is written WITHOUT inter-word spaces. */
    private fun isNonSpacing(c: Char): Boolean {
        val u = c.code
        return u in 0x4E00..0x9FFF ||   // CJK Unified Ideographs (Han)
            u in 0x3400..0x4DBF ||      // CJK Unified Ideographs Extension A
            u in 0xF900..0xFAFF ||      // CJK Compatibility Ideographs
            u in 0x3040..0x309F ||      // Hiragana
            u in 0x30A0..0x30FF ||      // Katakana
            u in 0x0E00..0x0E7F         // Thai
    }

    /**
     * Pad [seg] with a single space on each side ONLY where the melt invariant requires it against
     * the surrounding field context ([left] = text before the insertion point, [right] = text after).
     * Used by the clipboard/paste delivery paths, which insert a raw segment at the caret and would
     * otherwise melt it against existing field text ('wordword'). Pure — mirrors the anchor's own
     * both-boundary guard so every surface shares one policy.
     */
    fun pad(left: CharSequence, seg: String, right: CharSequence): String {
        if (seg.isEmpty()) return seg
        val lead = if (needsSpace(left, seg)) " " else ""
        val trail = if (needsSpace(seg, right)) " " else ""
        return lead + seg + trail
    }

    /** Join two runs verbatim, inserting one space only when [needsSpace]. */
    fun join(left: String, right: String): String =
        if (needsSpace(left, right)) "$left $right" else left + right

    /** Normalize, drop blank chunks, and pairwise-join a whole list under the melt invariant. */
    fun assemble(chunks: List<String>): String {
        val sb = StringBuilder()
        for (c in chunks) {
            val n = normalize(c)
            if (n.isEmpty()) continue
            if (sb.isNotEmpty() && needsSpace(sb, n)) sb.append(' ')
            sb.append(n)
        }
        return sb.toString()
    }
}
