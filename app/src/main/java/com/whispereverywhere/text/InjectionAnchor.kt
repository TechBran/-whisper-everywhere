package com.whispereverywhere.text

/**
 * Per-dictation-session cursor state machine. The bug it kills: every segment used to re-read the
 * LIVE app cursor and insert there, so any editor that moves the caret after our SET_TEXT
 * (Facebook mentions, drafts moving it to 0 or mid-text) corrupted the next segment.
 *
 * This machine remembers where WE left the cursor ([anchor]) and exactly what WE last wrote
 * ([expectedText]). It ignores the live cursor entirely. If the field still equals what we wrote,
 * it appends at our anchor. If the user edited mid-session it RE-SYNCs against the diff and NEVER
 * deletes. The one legal deletion is a selection range captured at [start] (user selected text
 * then dictated = replace intent), honored at most once.
 *
 * [plan] is pure (no mutation) so a failed SET_TEXT is retry-safe; [commit] is the only mutator
 * and is called ONLY after the write lands. Spacing guards BOTH boundaries via [TextJoin].
 *
 * Pure and Android-free; every case is a JVM unit test.
 */
class InjectionAnchor {

    var anchor: Int = 0
        private set
    var expectedText: String = ""
        private set
    private var pendingReplace: IntRange? = null

    /** What to write for one segment: the full new field string and the cursor to pin. */
    data class Placement(val newFieldText: String, val newSelection: Int)

    /**
     * Begin a session. [fieldText] is the field's current text (hint text already stripped by the
     * caller). A real selection RANGE ([selEnd] > [selStart]) is recorded as the one legal replace.
     */
    fun start(fieldText: String, selStart: Int, selEnd: Int) {
        anchor = if (selStart in 0..fieldText.length) selStart else fieldText.length
        expectedText = fieldText
        pendingReplace =
            if (selStart in 0..fieldText.length && selEnd in (selStart + 1)..fieldText.length) {
                selStart until selEnd
            } else null
    }

    /** Compute the write for [segment] against the field's current [fieldText]. Pure. */
    fun plan(fieldText: String, segment: String): Placement {
        val seg = TextJoin.normalize(segment)

        // Locate where our text goes. Replace range wins (start-only, unchanged field); else the
        // anchor if the field is still ours; else a non-deleting re-sync.
        val replace = pendingReplace?.takeIf { fieldText == expectedText }
        val removeStart: Int
        val removeEnd: Int
        val insertAt: Int
        if (replace != null) {
            removeStart = replace.first
            removeEnd = replace.last + 1
            insertAt = replace.first
        } else {
            removeStart = -1
            removeEnd = -1
            insertAt = if (fieldText == expectedText) {
                anchor.coerceIn(0, fieldText.length)
            } else {
                resync(fieldText)
            }
        }

        if (seg.isEmpty()) {
            // No-op: keep the field, hold the anchor at the resolved point.
            return Placement(fieldText, insertAt.coerceIn(0, fieldText.length))
        }

        val head = if (removeStart >= 0) fieldText.substring(0, removeStart) else fieldText.substring(0, insertAt)
        val tail = if (removeStart >= 0) fieldText.substring(removeEnd) else fieldText.substring(insertAt)

        val leftSpace = if (TextJoin.needsSpace(head, seg)) " " else ""
        val rightSpace = if (TextJoin.needsSpace(seg, tail)) " " else ""

        val newText = head + leftSpace + seg + rightSpace + tail
        val cursor = head.length + leftSpace.length + seg.length   // end of OUR text, before the right space
        return Placement(newText, cursor)
    }

    /** Adopt [placement] after a successful SET_TEXT: advance the anchor, clear the one-shot replace. */
    fun commit(placement: Placement) {
        anchor = placement.newSelection
        expectedText = placement.newFieldText
        pendingReplace = null
    }

    /**
     * The field no longer matches what we wrote — the user edited mid-session. Find where our
     * anchor moved WITHOUT ever deleting: keep it if the edit was after it, shift it if the edit
     * was entirely before it, else fall back to the field end (append). Never returns a delete.
     */
    private fun resync(fieldText: String): Int {
        val old = expectedText
        val maxLen = minOf(old.length, fieldText.length)
        var p = 0
        while (p < maxLen && old[p] == fieldText[p]) p++
        var s = 0
        while (s < maxLen - p &&
            old[old.length - 1 - s] == fieldText[fieldText.length - 1 - s]) s++
        return when {
            anchor <= p -> anchor                                   // edit after the anchor: unchanged
            anchor >= old.length - s -> fieldText.length - (old.length - anchor) // edit before it: shift
            else -> fieldText.length                                // edit straddles it: append (never delete)
        }.coerceIn(0, fieldText.length)
    }
}
