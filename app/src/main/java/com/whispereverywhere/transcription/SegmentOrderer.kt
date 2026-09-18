package com.whispereverywhere.transcription

import com.whispereverywhere.text.TextJoin

/**
 * Releases segment outcomes into the user's text field in STRICT seq order, never
 * speculate-then-correct.
 *
 * This is forced by the injection mechanism, not chosen for tidiness. Injection is a full-field
 * read-modify-write (`ACTION_SET_TEXT` with the entire rebuilt string), so two out-of-order
 * injections do not merely scramble word order — the second reads pre-first text and writes it
 * back, SILENTLY DELETING the first segment. Retroactive repair is impossible: nothing records
 * where a segment landed, `ACTION_SET_SELECTION` is never used, and two of three delivery paths
 * are clipboard+PASTE with no position control at all.
 *
 * Pure and Android-free so every ordering case is unit-testable. Main-thread confined in
 * production — callers marshal through the service's existing Dispatchers.Main hop.
 *
 * At local's permanent maxInFlight = 1 this is a provable pass-through: results always arrive with
 * seq == head, so local delivery timing is unchanged by its presence.
 */
class SegmentOrderer(private val lostMarker: String = LOST_MARKER) {

    private var head = 0L
    private val resolved = HashMap<Long, SegmentOutcome>()
    /** True when the immediately preceding released segment was a loss, so runs collapse. */
    private var lastReleasedWasLost = false
    /**
     * True once any real (non-blank) text has ever been released, across calls. Together with
     * [lastReleasedWasLost] this lets a text<->marker transition get a separating space even when
     * the two sides are released by different [drain] invocations — e.g. "before" released by one
     * onResolved() call, "[…]" by the next, "after" by a third must still read as one spaced
     * sentence, not "before[…]after". Plain text-to-text across calls is deliberately left
     * unspaced: callers own word-to-word spacing for ordinary dictation.
     */
    private var hasEmittedText = false

    /**
     * What one drain produced.
     *
     * [text] and [lostSegments] are the whole of the ordering contract and are untouched by 4.10.
     * [seq] and [spans] are PASSENGERS on it, for the speaker labels: the sink needs to know which
     * chunk the text came from, because its speaker ids arrive ~300 ms later keyed by that seq,
     * and which stretches of it were which VAD segment.
     *
     * Both ride only when the drain released EXACTLY ONE non-blank text segment and no loss
     * marker, and that condition is the honest one rather than a cautious one. A drain that
     * concatenated two chunks, or spliced a "[…]" between them, produced a string that no single
     * chunk's spans describe — and labelling it from one of them would put a speaker's name on
     * another chunk's sentence. The condition is also never false where it matters: spans exist
     * only for the on-device engine, whose `maxInFlight = 1` makes this a provable pass-through
     * (results arrive with `seq == head`, so every release is one segment).
     */
    data class Release(
        val text: String,
        val lostSegments: Int,
        val seq: Long = NO_SEQ,
        val spans: List<com.whispereverywhere.transcription.speakers.SpeakerSpan>? = null,
    )

    fun onResolved(seq: Long, outcome: SegmentOutcome): Release {
        // Late duplicate (a retry that timed out client-side but succeeded server-side) — dropping
        // it is the whole benefit of having identity.
        if (seq < head || resolved.containsKey(seq)) return EMPTY
        resolved[seq] = outcome
        return drain()
    }

    /** Unblock a seq that will never resolve — merged away, or from a superseded generation. */
    fun skip(seq: Long): Release {
        if (seq < head) return EMPTY
        resolved[seq] = SKIPPED
        return drain()
    }

    /**
     * Release everything held, skipping the holes. MUST be called on every session exit path —
     * stop, generation bump, teardown, drain timeout. Held text is uniquely fragile: unlike
     * per-segment injection it accumulates finished work in RAM whose only exit is this call, and
     * the pile is largest exactly when the user is most likely to end the session.
     */
    fun flush(): Release {
        if (resolved.isEmpty()) return EMPTY
        val maxSeq = resolved.keys.max()
        var s = head
        while (s <= maxSeq) { resolved.putIfAbsent(s, SKIPPED); s++ }
        return drain()
    }

    fun pendingCount(): Int = resolved.size

    private fun drain(): Release {
        val sb = StringBuilder()
        var lost = 0
        // The 4.10 passengers: which chunk this release's text came from, and how many chunks
        // contributed to it at all. See [Release] for why one is the only number that can carry
        // spans.
        var textSegments = 0
        var spanSeq = NO_SEQ
        var spans: List<com.whispereverywhere.transcription.speakers.SpeakerSpan>? = null
        while (true) {
            val releasing = head
            val outcome = resolved.remove(head) ?: break
            head++
            when (outcome) {
                is SegmentOutcome.Text -> {
                    if (outcome.text.isNotBlank()) {
                        textSegments++
                        spanSeq = releasing
                        spans = outcome.spans
                        val tok = outcome.text.trim()
                        // Within-burst spacing is melt-proofed (punctuation attaches); the
                        // cross-call gate (a preceding loss marker from an earlier call) stays an
                        // unconditional space — text must not glue onto "[…]".
                        when {
                            sb.isNotEmpty() -> if (TextJoin.needsSpace(sb, tok)) sb.append(' ')
                            lastReleasedWasLost -> sb.append(' ')
                        }
                        sb.append(tok)
                        lastReleasedWasLost = false
                        hasEmittedText = true
                    }
                }
                SegmentOutcome.EmptyExpected -> Unit          // silence: contributes nothing
                SKIPPED -> Unit                               // never existed for the user
                SegmentOutcome.EmptyUnexpected, is SegmentOutcome.Lost -> {
                    lost++
                    // Collapse consecutive losses: a 90-second outage must produce ONE marker,
                    // not thirty, or the user is left deleting a wall of ellipses.
                    if (!lastReleasedWasLost) {
                        // Same rule for the marker: melt-proof within-burst, unconditional space
                        // across calls when real text was already emitted earlier.
                        when {
                            sb.isNotEmpty() -> if (TextJoin.needsSpace(sb, lostMarker)) sb.append(' ')
                            hasEmittedText -> sb.append(' ')
                        }
                        sb.append(lostMarker)
                        lastReleasedWasLost = true
                    }
                }
            }
        }
        if (sb.isEmpty() && lost == 0) return EMPTY
        val oneChunk = textSegments == 1 && lost == 0
        return Release(
            text = sb.toString(),
            lostSegments = lost,
            seq = if (oneChunk) spanSeq else NO_SEQ,
            spans = if (oneChunk) spans else null,
        )
    }

    companion object {
        /** [Release.seq] for a release no single chunk owns. Never a real seq (those start at 0). */
        const val NO_SEQ: Long = -1L

        private const val LOST_MARKER = "[…]"
        private val EMPTY = Release("", 0)
        /** Internal marker for a seq that was skipped; never surfaced to callers. */
        private val SKIPPED = SegmentOutcome.Text("")
    }
}
