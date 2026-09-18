package com.whispereverywhere.transcription.speakers

import com.whispereverywhere.text.TextJoin

/**
 * THE SPEC'S §2 TABLE, as a pure formatter (plan Task 5; spec §2).
 *
 * ```
 *  surface                      one speaker              two or more CONFIRMED
 *  the bubble's panel           plain, as today          a paragraph per change, each "Speaker N: "
 *  a field (typed at stop)      as today                 a paragraph per change, NO labels
 *  clipboard + saved text       as today                 paragraphs always, labels behind a switch
 * ```
 *
 * Three rules do all the work, and the first is the one the whole feature is judged on:
 *
 *  1. **Fewer than two CONFIRMED speakers ⇒ today's output, byte for byte.** Not "almost": the
 *     `confirmedCount < MIN_CONFIRMED_SPEAKERS` branch returns `TextJoin.assemble` of the runs
 *     and cannot reach a break or a label in ANY mode. Since `SpeakerRuns.of` guarantees that a
 *     chunk's runs join back to exactly the text the engine committed, that makes "a
 *     one-speaker session is 4.9's output" a theorem rather than a hope — and it holds for a
 *     session whose speakers were never confirmed, for a device that cannot load the embedder,
 *     and for detection switched off, all by the same line of code.
 *  2. **A speaker change starts a new paragraph**, `"\n\n"`, and the paragraph never begins with
 *     a space. Inside a paragraph the runs are joined by [TextJoin], the app's one melt policy, so
 *     "Hello" + "." is "Hello." and a CJK boundary grows no stray space.
 *  3. **Numbers are what the USER can count.** The tracker's ids survive merges as gaps (1, 3, 7
 *     after two absorptions), so the ids are COMPACTED here, in order of first appearance across
 *     the whole run list: the user sees 1, 2, 3. Compaction lives in the formatter and not in the
 *     tracker because it is a presentation fact — the tracker's ids are stable keys and the
 *     sidecar keeps them.
 *
 * Unassigned runs (the ~300 ms window before an embedding lands, and every run that can never be
 * assigned) INHERIT the previous run's speaker, or speaker 1 when there is no previous. Inheriting
 * is what keeps a late assignment from opening a paragraph for a speaker who has not been
 * identified yet, and it is the same rule the tracker applies to a segment too short to fingerprint.
 *
 * Pure: no Android, no state, no clock. Every cell of the table above is a JVM unit test.
 */
object SpeakerLabels {

    /**
     * The spec's threshold, and the only number in this file. "Two or more heard" is the whole
     * of the §2 table's second column, and the latch that certifies it is
     * `SpeakerTracker.secondSpeakerConfirmed`.
     */
    const val MIN_CONFIRMED_SPEAKERS: Int = 2

    /** A speaker change is a PARAGRAPH, not a line — the owner's word for it ("then paragraph"). */
    const val PARAGRAPH_BREAK: String = "\n\n"

    /** `Speaker 1: ` — spec §7 item 2, taken as proposed (the alternative was `Speaker 1 —`). */
    fun label(displayNumber: Int): String = "Speaker $displayNumber: "

    /**
     * WHERE the text is going, which is the only thing that changes about how it is rendered.
     *
     * [Field] carries no `labels` flag on purpose rather than by omission: the owner's ruling is
     * that a text field is not a transcript ("no need for that"), so there is no setting and no
     * caller that can put a label into another app's field. The other two modes take the flag
     * because each has a real switch behind it — the panel's is the confirmation latch, the
     * export's is the user's "Speaker labels in copied and saved text".
     */
    sealed class Mode {

        /** The bubble's transcript panel: labels once a second voice is confirmed. */
        data class Panel(val labels: Boolean) : Mode()

        /** Typed into another app at stop: paragraph breaks, never labels. */
        object Field : Mode()

        /** The clipboard and the saved transcript: breaks always, labels behind the switch. */
        data class Export(val labels: Boolean) : Mode()
    }

    /**
     * The §2 table applied to a whole session's [runs].
     *
     * [confirmedCount] is how many speakers the tracker has CONFIRMED this session — under
     * [MIN_CONFIRMED_SPEAKERS] the result is today's plain joining in every mode (rule 1).
     */
    fun render(runs: List<Run>, mode: Mode, confirmedCount: Int): String {
        if (confirmedCount < MIN_CONFIRMED_SPEAKERS) return plain(runs)
        return build(
            runs = runs,
            numbers = displayNumbers(runs),
            labels = labelsVisible(mode),
            from = 0,
            dropLeadingChars = 0,
        )
    }

    /**
     * The same table, applied to the NEWEST [maxChars] characters or so — the bubble panel's
     * bounded window (`TranscriptSink`'s preview).
     *
     * Two properties make this more than `render(...).takeLast(maxChars)`:
     *  - **the cut is at a RUN boundary**, so a `Speaker N: ` prefix is never separated from the
     *     paragraph it introduces and no paragraph ever starts mid-word. The one exception is a
     *     single run longer than the whole window, which is truncated from the FRONT and keeps
     *     its label — the label belongs to the speaker, not to the missing words;
     *  - **the numbering is computed over ALL of [runs]**, not over the visible tail, so the
     *     third speaker of a session reads `Speaker 3:` even when the first two have scrolled out
     *     of the window. Numbering touches ids only; no string is built outside the tail, which
     *     is what keeps an append O(window) rather than O(session).
     */
    fun renderTail(runs: List<Run>, mode: Mode, confirmedCount: Int, maxChars: Int): String {
        if (maxChars <= 0 || runs.isEmpty()) return ""
        if (confirmedCount < MIN_CONFIRMED_SPEAKERS) {
            val whole = plain(runs)
            return if (whole.length <= maxChars) whole else whole.substring(whole.length - maxChars)
        }
        val numbers = displayNumbers(runs)
        val labels = labelsVisible(mode)
        // The widest suffix whose UPPER BOUND fits, and never fewer than one run. `cost` is an
        // over-estimate of a run's contribution (its text, the widest separator, its label), so a
        // bound within the cap proves the rendered string is too.
        var from = runs.size - 1
        var bound = cost(runs[from], numbers[from], labels)
        while (from > 0) {
            val next = cost(runs[from - 1], numbers[from - 1], labels)
            if (bound + next > maxChars) break
            bound += next
            from--
        }
        var text = build(runs, numbers, labels, from, dropLeadingChars = 0)
        if (text.length > maxChars) {
            // Only reachable for the single oversized run: its text is cut from the front by
            // exactly the overflow, which lands the result ON the cap with its label intact.
            text = build(runs, numbers, labels, from, dropLeadingChars = text.length - maxChars)
        }
        // Degenerate cap (smaller than one label plus one word — reachable only from a test):
        // give back the newest characters with no label at all rather than half of one.
        if (text.length > maxChars) text = runs.last().text.takeLast(maxChars)
        return text
    }

    // ------------------------------------------------------------------ the three rules

    /** Rule 1: today's output. The ONE place the no-speakers shape is produced. */
    private fun plain(runs: List<Run>): String = TextJoin.assemble(runs.map { it.text })

    private fun labelsVisible(mode: Mode): Boolean = when (mode) {
        is Mode.Panel -> mode.labels
        Mode.Field -> false
        is Mode.Export -> mode.labels
    }

    /** An upper bound on what one run adds to the rendered string. */
    private fun cost(run: Run, number: Int, labels: Boolean): Int =
        run.text.length + PARAGRAPH_BREAK.length + if (labels) label(number).length else 0

    /**
     * Rule 3: the tracker's ids, compacted to 1..n in order of first appearance, with every
     * unassigned run inheriting the previous run's speaker (or speaker 1 at the session's head).
     * Returns one display number per run, parallel to [runs].
     */
    private fun displayNumbers(runs: List<Run>): IntArray {
        val compacted = LinkedHashMap<Int, Int>()
        val out = IntArray(runs.size)
        var previous = 0
        for (i in runs.indices) {
            val raw = runs[i].speakerId?.takeIf { it > 0 } ?: previous
            val resolved = if (raw > 0) raw else FIRST_SPEAKER
            previous = resolved
            out[i] = compacted.getOrPut(resolved) { compacted.size + 1 }
        }
        return out
    }

    /**
     * Rule 2: the runs, grouped into paragraphs by display number.
     *
     * [from] is the first run to render and [dropLeadingChars] how many characters to cut off the
     * FRONT of that run's text (the tail's one truncation); both are 0 for a whole-session render.
     */
    private fun build(
        runs: List<Run>,
        numbers: IntArray,
        labels: Boolean,
        from: Int,
        dropLeadingChars: Int,
    ): String {
        val sb = StringBuilder()
        var current = 0
        for (i in from until runs.size) {
            var piece = runs[i].text
            if (i == from && dropLeadingChars > 0) {
                piece = piece.substring(dropLeadingChars.coerceAtMost(piece.length))
            }
            if (piece.isEmpty()) continue
            val number = numbers[i]
            if (number != current) {
                // A new paragraph: the break, then the label, then the text — and never a space,
                // which is what would otherwise indent every paragraph after a run ending in a word.
                if (sb.isNotEmpty()) sb.append(PARAGRAPH_BREAK)
                if (labels) sb.append(label(number))
                current = number
            } else if (sb.isNotEmpty() && TextJoin.needsSpace(sb, piece)) {
                sb.append(' ')
            }
            sb.append(piece)
        }
        return sb.toString()
    }

    /** What an unassigned run at the very head of a session inherits. */
    private const val FIRST_SPEAKER: Int = 1
}
