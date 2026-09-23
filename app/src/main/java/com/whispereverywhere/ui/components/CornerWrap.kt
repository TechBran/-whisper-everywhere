package com.whispereverywhere.ui.components

import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.LeadingMarginSpan
import android.widget.TextView

/**
 * THE COMMITTED TEXT FLOWS AROUND THE TWO CORNER DISCS (owner ruling 2026-09-22, on 108: the mute
 * mic and the resize arrow "should just hold their own little bubble and behind it that's the only
 * space that it takes, like say a little circle, for both, and the text should still just flow
 * around it to the top without the header").
 *
 * Each control sits in a small disc at a top corner of the transcript, and the text starts at the
 * very top: the lines the discs reach down across run BETWEEN them — right of the mute disc, left
 * of the resize disc — and every line below uses the full width.
 *
 * ### How, given that TextView cannot indent one side of one line
 *
 * A TextView offers only a LEADING margin per paragraph ([LeadingMarginSpan]); a per-line RIGHT
 * indent exists only on [StaticLayout.Builder.setIndents]. So the head of the text is laid out
 * once here ([probe]), with both indents on its first lines, to learn where each of those lines
 * ENDS; a hard break goes in at each such end, which makes every wrapped line its own paragraph
 * and so a line that already fits between the discs — and a leading margin on those paragraphs
 * moves them clear of the left disc ([render]). The TextView then lays the text out by itself,
 * with no knowledge of any of this. The break point is the only thing taken from this layout, so
 * a small difference between it and the TextView's own (a font fallback, a spacing rounding) can
 * move a word to the next line but can never push a line under a disc: whatever fits between the
 * discs here fits in the wider space the TextView gives it. The probe never hyphenates, so a
 * forced break always falls between words (or inside a word too long for the line anyway) — a
 * hyphenated break would lose its hyphen, since the TextView has no reason to draw one there.
 *
 * Each break carries a zero-width direction mark matching the paragraph it split: a TextView
 * resolves direction per paragraph from its first strong character, so without the mark an
 * Arabic or Hebrew line that happened to start with a Latin word would turn the rest of the
 * transcript left-to-right.
 *
 * ### What it cannot do
 *
 * Wrapping is a layout of the TEXT, and the discs are fixed to the PANEL. Once the transcript
 * scrolls, other lines pass under the corners and each disc covers the text beneath its own small
 * circle — which is exactly the owner's other half of the ruling ("that's the only space that it
 * takes"). The wrap is what keeps a session's opening words out from under the mute disc.
 *
 * The display copy only: the breaks never reach the transcript file or the text a session
 * delivers, which are the sink's, not this view's.
 */
object CornerWrap {

    /** Text laid out to find the wrapped lines: many times two lines at any panel width. */
    const val HEAD_CHARS = 600

    /** Most lines a disc can reach across: a 24dp disc against a 14sp line reaches two. */
    const val MAX_WRAPPED_LINES = 4

    /** What each break inserts: a newline, then the split paragraph's direction mark. */
    const val BREAK_LENGTH = 2

    private const val LRM = '\u200E'
    private const val RLM = '\u200F'

    /**
     * Where to break, in RAW offsets, and where the leading margin ends, in WRAPPED offsets —
     * always a paragraph boundary: just past the newline that ends the last wrapped line (NOT past
     * the direction mark after it, which is the first character of the next, unwrapped
     * paragraph), or the end of the text.
     */
    data class Plan(val insertAt: List<Int>, val marginEnd: Int)

    /** A [Plan] and, per break, the direction mark that opens the paragraph it splits off. */
    data class Wrapping(val plan: Plan, val marks: List<Char>)

    /**
     * Pure: from the ends of the lines that must wrap (each the offset just past that line, as a
     * layout reports it), the breaks to insert and the span of the paragraphs the margin covers.
     * A line that already ends a paragraph needs no break; a wrapped line that reaches the end of
     * the text ends the plan there. [insertLength] is how many characters each break inserts.
     */
    fun plan(text: CharSequence, lineEnds: List<Int>, insertLength: Int = 1): Plan {
        val inserts = ArrayList<Int>(lineEnds.size)
        var last = 0
        var lastWasInserted = false
        for (end in lineEnds) {
            if (end <= last) continue
            if (end >= text.length) return Plan(inserts, text.length + insertLength * inserts.size)
            lastWasInserted = text[end - 1] != '\n'
            if (lastWasInserted) inserts += end
            last = end
        }
        if (inserts.isEmpty() && last == 0) return Plan(inserts, 0)
        val shift = insertLength * inserts.size
        // An inserted break's newline is its FIRST character: the margin stops right after it.
        return Plan(inserts, last + shift - if (lastWasInserted) insertLength - 1 else 0)
    }

    /**
     * Lay out the head of [text] for [tv] at [widthPx], [leftPx] and [rightPx] clearing the discs
     * on every line whose top is above [clearPx], and return where it must break — or null when
     * nothing wraps. Cheap (a few hundred characters), so a resize can ask it on every move and
     * re-set the text only when the answer changed.
     */
    fun probe(
        text: CharSequence,
        tv: TextView,
        widthPx: Int,
        leftPx: Int,
        rightPx: Int,
        clearPx: Int,
    ): Wrapping? {
        if (text.isEmpty() || widthPx <= leftPx + rightPx) return null
        val head = text.subSequence(0, minOf(text.length, HEAD_CHARS))
        // Indent arrays repeat their LAST value past their end, so the trailing 0 frees every
        // line below the wrapped ones.
        val lefts = IntArray(MAX_WRAPPED_LINES + 1) { if (it < MAX_WRAPPED_LINES) leftPx else 0 }
        val rights = IntArray(MAX_WRAPPED_LINES + 1) { if (it < MAX_WRAPPED_LINES) rightPx else 0 }
        val layout = StaticLayout.Builder.obtain(head, 0, head.length, tv.paint, widthPx)
            .setIncludePad(tv.includeFontPadding)
            .setLineSpacing(tv.lineSpacingExtra, tv.lineSpacingMultiplier)
            .setBreakStrategy(tv.breakStrategy)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIndents(lefts, rights)
            .build()
        val ends = ArrayList<Int>(MAX_WRAPPED_LINES)
        for (i in 0 until minOf(layout.lineCount, MAX_WRAPPED_LINES)) {
            if (layout.getLineTop(i) >= clearPx) break
            val end = layout.getLineEnd(i)
            // A head cut short of the text cannot say where its own last line really ends.
            if (head.length < text.length && end >= head.length) break
            ends += end
        }
        if (ends.isEmpty()) return null
        val plan = plan(text, ends, BREAK_LENGTH)
        if (plan.insertAt.isEmpty() && plan.marginEnd == 0) return null
        val marks = plan.insertAt.map { at ->
            val direction = layout.getParagraphDirection(layout.getLineForOffset(at - 1))
            if (direction == Layout.DIR_RIGHT_TO_LEFT) RLM else LRM
        }
        return Wrapping(plan, marks)
    }

    /** The display copy of [text] with [w]'s breaks in; its wrapped paragraphs clear [leftPx]. */
    fun render(text: CharSequence, w: Wrapping, leftPx: Int): CharSequence {
        val out = SpannableStringBuilder(text)
        for (k in w.plan.insertAt.indices.reversed()) {
            out.insert(w.plan.insertAt[k], "\n" + w.marks[k])
        }
        out.setSpan(
            LeadingMarginSpan.Standard(leftPx), 0, w.plan.marginEnd,
            Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
        )
        return out
    }

    /** [probe] then [render]; [text] itself when nothing wraps. */
    fun wrap(
        text: CharSequence,
        tv: TextView,
        widthPx: Int,
        leftPx: Int,
        rightPx: Int,
        clearPx: Int,
    ): CharSequence =
        probe(text, tv, widthPx, leftPx, rightPx, clearPx)?.let { render(text, it, leftPx) } ?: text

    /** The empty panel's hint, clear of the mute disc the same way. */
    fun hint(text: CharSequence, leftPx: Int): CharSequence =
        SpannableStringBuilder(text).apply {
            setSpan(LeadingMarginSpan.Standard(leftPx), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
}
