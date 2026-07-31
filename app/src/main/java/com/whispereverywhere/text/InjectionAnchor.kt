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
    // The field value we held BEFORE our last committed write. A cross-process SET_TEXT applies
    // asynchronously, so a rapid next segment can still read this pre-write value; the staleness
    // guard in [plan] uses it to avoid resyncing (and discarding text) against an un-propagated write.
    private var previousExpected: String? = null

    /** What to write for one segment: the full new field string and the cursor to pin. */
    data class Placement(val newFieldText: String, val newSelection: Int)

    /**
     * Begin a session. [fieldText] is the field's current text (hint text already stripped by the
     * caller). A real selection RANGE ([selEnd] > [selStart]) is recorded as the one legal replace.
     */
    fun start(fieldText: String, selStart: Int, selEnd: Int) {
        anchor = if (selStart in 0..fieldText.length) selStart else fieldText.length
        expectedText = fieldText
        previousExpected = null
        // A range is the one legal replace ONLY if it is a strict SUBSET of the field. A range that
        // spans the WHOLE field (selStart == 0 && selEnd == length) is not user intent — many fields
        // (browser URL bars, search boxes) auto-select-all the instant they gain focus, and treating
        // that as replace would silently wipe the user's existing URL/query on the first segment.
        // Excluding it means the worst case is a harmless prepend, never a deletion.
        pendingReplace =
            if (selStart in 0..fieldText.length && selEnd in (selStart + 1)..fieldText.length &&
                !(selStart == 0 && selEnd == fieldText.length)) {
                selStart until selEnd
            } else null
    }

    /** Compute the write for [segment] against the field's current [fieldText]. Pure. */
    fun plan(fieldText: String, segment: String): Placement {
        val seg = TextJoin.normalize(segment)

        // Staleness guard: our last SET_TEXT is applied by the target app's process ASYNCHRONOUSLY.
        // If the field still reads the value we held BEFORE that write (previousExpected) rather than
        // what we wrote (expectedText), the write simply has not propagated yet. Planning against the
        // stale read would resync — computing an insert of 0 and overwriting the just-written text,
        // losing a whole segment. Instead plan against what we wrote, so the next SET_TEXT carries
        // BOTH segments and the un-propagated write is superseded harmlessly.
        val basis = if (fieldText != expectedText && previousExpected != null &&
            fieldText == previousExpected && previousExpected != expectedText) {
            expectedText
        } else {
            fieldText
        }

        // Locate where our text goes. Replace range wins (start-only, unchanged field); else the
        // anchor if the field is still ours; else a non-deleting re-sync.
        val replace = pendingReplace?.takeIf { basis == expectedText }
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
            insertAt = if (basis == expectedText) {
                anchor.coerceIn(0, basis.length)
            } else {
                resync(basis)
            }
        }

        if (seg.isEmpty()) {
            // No-op: keep the field, hold the anchor at the resolved point.
            return Placement(basis, insertAt.coerceIn(0, basis.length))
        }

        val head = if (removeStart >= 0) basis.substring(0, removeStart) else basis.substring(0, insertAt)
        val tail = if (removeStart >= 0) basis.substring(removeEnd) else basis.substring(insertAt)

        val leftSpace = if (TextJoin.needsSpace(head, seg)) " " else ""
        val rightSpace = if (TextJoin.needsSpace(seg, tail)) " " else ""

        val newText = head + leftSpace + seg + rightSpace + tail
        val cursor = head.length + leftSpace.length + seg.length   // end of OUR text, before the right space
        return Placement(newText, cursor)
    }

    /** Adopt [placement] after a successful SET_TEXT: advance the anchor, clear the one-shot replace. */
    fun commit(placement: Placement) {
        previousExpected = expectedText     // remember the pre-write value for the staleness guard
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
