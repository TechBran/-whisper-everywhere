package com.whispereverywhere.transcription.speakers

import com.whispereverywhere.text.TextJoin

/**
 * ONE stretch of committed text with ONE speaker — the unit every 4.10 surface is rendered from
 * (plan Task 5).
 *
 * A [Run] is what a [SpeakerSpan] becomes once it has left the engine: the span's [windowIndex]
 * is kept, because the speaker ids arrive LATER and arrive indexed by it, and the chunk's [seq]
 * is kept, because that is the key the assignment arrives under. Text is already
 * `TextJoin.normalize`d, so a run is exactly what the user would see.
 *
 * [speakerId] is the one mutable field in the feature, and it is mutable for a reason that is
 * timing, not style: the embedding costs ~300 ms and the orderer releases text IMMEDIATELY, so a
 * chunk's runs are on screen before anyone knows whose voice they were. `null` means "not yet
 * assigned" — the renderer lets such a run inherit the previous run's speaker rather than opening
 * a paragraph for nobody — and it is `0` that never appears here: the tracker's "unlabelled" 0 is
 * dropped at the door by [SpeakerRuns.applyAssignment], because 0 is not a speaker.
 *
 * @param seq the committed chunk's segment sequence number.
 * @param windowIndex the chunk's FINGERPRINT WINDOW this text was attributed to, or
 *        [SpeakerRuns.NO_WINDOW_INDEX] for a run that carries a whole chunk with no geometry
 *        behind it (cloud, the NPU tier, detection off, or a chunk whose spans could not
 *        reproduce its text — see [SpeakerRuns.of]). Such a run can never be assigned, and that
 *        is the point: it renders as today's plain text forever.
 *
 *        It was the VAD segment's index until spike session 4 split long segments into several
 *        windows; the two agree for every chunk that has no long segment in it.
 */
data class Run(
    val seq: Long,
    val windowIndex: Int,
    val text: String,
    var speakerId: Int? = null,
)

/**
 * Spans in, runs out — plus the three patches a session's runs are corrected by: one chunk's ids,
 * the tracker's merges, and the retrospective pass's per-window labels. Pure: no
 * Android, no I/O, no state of its own (plan Task 5 step 1).
 */
object SpeakerRuns {

    /** [Run.windowIndex] for a run that belongs to no window and therefore to no speaker. */
    const val NO_WINDOW_INDEX: Int = -1

    /**
     * The runs of ONE committed chunk.
     *
     * [text] is the chunk's committed text and is AUTHORITATIVE: [spans] are only ever used when
     * they reproduce it exactly. Three of the four branches below therefore return a single
     * plain run holding [text] itself, and every one of them is a normal shape rather than an
     * error:
     *  - no spans at all — cloud, the NPU tier, a chunk the VAD found no speech in, or speaker
     *    detection switched off;
     *  - spans that all cleaned away to nothing;
     *  - **spans whose `TextJoin` join is not byte-for-byte [text]** — the one branch worth
     *    arguing. The spans are cleaned PER WHISPER SEGMENT (`SpeakerSpans.spans`) while [text] is
     *    cleaned as one string, so a non-speech marker straddling a segment boundary, or a segment
     *    that begins with closing punctuation, can make the two disagree by a character. Text is
     *    the product and a label is a garnish: where they disagree this chunk keeps its text and
     *    loses its labels, which also makes the spec's "one speaker all session = today's output
     *    byte for byte" a THEOREM about [SpeakerLabels.render] rather than a hope about cleaning.
     *
     * Blank text yields no runs at all: a blank chunk contributes nothing to any surface, exactly
     * as `TranscriptSink.append` and `handleTranscriptionResult` already treat it.
     */
    fun of(seq: Long, text: String, spans: List<SpeakerSpan>?): List<Run> {
        val whole = TextJoin.normalize(text)
        if (whole.isEmpty()) return emptyList()
        val plain = listOf(Run(seq = seq, windowIndex = NO_WINDOW_INDEX, text = whole))
        if (spans.isNullOrEmpty()) return plain
        val pieces = ArrayList<Run>(spans.size)
        for (span in spans) {
            val piece = TextJoin.normalize(span.text)
            if (piece.isEmpty()) continue
            pieces += Run(seq = seq, windowIndex = span.windowIndex, text = piece)
        }
        if (pieces.isEmpty()) return plain
        if (TextJoin.assemble(pieces.map { it.text }) != whole) return plain
        return pieces
    }

    /**
     * Stamps one chunk's speaker ids onto its runs — the late half of the pipeline.
     *
     * [ids] is `SpeakerAssignment.ids`: one id per FINGERPRINT WINDOW, in chunk order, so a
     * run's [Run.windowIndex] is its index into it. Everything that could be out of step is
     * ignored rather than guessed:
     *  - a run of another chunk (this is called with one seq at a time, on a whole session's runs);
     *  - [NO_WINDOW_INDEX], or an index past the end of [ids] — a stale geometry snapshot;
     *  - an id of `0`, the tracker's "could not attribute this segment at all". Spec §2 forbids
     *    reading that as speaker 1, so the run keeps whatever it had (usually `null`) and inherits
     *    at render time.
     */
    fun applyAssignment(runs: List<Run>, seq: Long, ids: List<Int>) {
        if (ids.isEmpty()) return
        for (run in runs) {
            if (run.seq != seq) continue
            val index = run.windowIndex
            if (index < 0 || index >= ids.size) continue
            val id = ids[index]
            if (id > 0) run.speakerId = id
        }
    }

    /**
     * THE SECOND LOOK's patch: a label per fingerprint WINDOW, applied to every run of the
     * session it names (spike session 6).
     *
     * [SpeakerRuns.applyRemap] is the id-level correction — "speaker 3 was speaker 1 all along" —
     * and it is all the tracker's merge pass can express. This one is exact. The retrospective
     * clustering answers per window, so a run whose speaker the online pass got wrong is
     * rewritten directly, even when the id it was given is still a live speaker of somebody
     * else's: that is precisely session 6's 03:27 failure, where one id had swallowed fifty
     * windows of two different voices and no id-level map could have separated them.
     *
     * ### WHICH runs this map is expected to name, and why the list matters
     *
     * All of them that have a window. `SpeakerAssigner` hands the pass EVERY window of the
     * session — including the ones it never fingerprinted, which carry a null vector — precisely
     * so that this method names every run the session can still address. That is not
     * thoroughness for its own sake: the same pass re-numbers the tracker's id space
     * ([SpeakerTracker.reseed]), so a run left holding a pre-renumbering id is not carrying a
     * stale label, it is carrying **somebody else's** — and [SpeakerLabels.displayNumbers]
     * compacts by raw id, so one such orphan becomes an extra `Speaker N:` and an extra
     * paragraph break in a session the pass says has two voices.
     *
     * So exactly three kinds of run go unnamed, and each is a run nothing could name:
     *  - a run with [NO_WINDOW_INDEX] (cloud, the NPU tier, a chunk whose spans could not
     *    reproduce its text) belongs to no window and can never be labelled;
     *  - a window past the assigner's retention bound (`SpeakerAssigner.MAX_RETAINED_WINDOWS`,
     *    roughly four hours of speech) keeps the label it has, which is where the accounting
     *    honestly stops;
     *  - a window whose chunk never reached the assigner at all — an empty sample buffer, or a
     *    submission rejected after teardown.
     *
     * And one label is dropped at the door rather than applied: a label of 0, exactly as in
     * [applyAssignment]. 0 is "unattributed", never speaker 1.
     *
     * Idempotent, and order-free with respect to [applyAssignment] and [applyRemap] for the runs
     * it names: this map is the newest and most complete opinion about those windows, so it wins
     * wherever it speaks.
     */
    fun applyWindowLabels(runs: List<Run>, windowLabels: Map<WindowKey, Int>) {
        if (windowLabels.isEmpty()) return
        for (run in runs) {
            if (run.windowIndex == NO_WINDOW_INDEX) continue
            val id = windowLabels[WindowKey(run.seq, run.windowIndex)] ?: continue
            if (id > 0) run.speakerId = id
        }
    }

    /**
     * Applies the tracker's accumulated merges — `merged id -> survivor id` — to EVERY run of the
     * session, including the ones already on screen. That reach is the whole point: the merge pass
     * decides, at the end of a chunk, that a speaker it opened chunks ago was never a separate
     * person.
     *
     * ONE pass is enough, and that is a property of the tracker rather than of this loop: only
     * UNCONFIRMED speakers are absorbed and only CONFIRMED ones absorb, so no value in the map is
     * ever also a key (`SpeakerTracker.remap`). Idempotent for the same reason — applying the same
     * map twice cannot move an id twice.
     */
    fun applyRemap(runs: List<Run>, remap: Map<Int, Int>) {
        if (remap.isEmpty()) return
        for (run in runs) {
            val id = run.speakerId ?: continue
            val survivor = remap[id] ?: continue
            run.speakerId = survivor
        }
    }
}
