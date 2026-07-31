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
        return true
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
