package com.whispereverywhere.transcription

import com.whispereverywhere.text.TextJoin
import com.whispereverywhere.transcription.speakers.Run
import com.whispereverywhere.transcription.speakers.SpeakerLabels
import com.whispereverywhere.transcription.speakers.SpeakerRuns
import com.whispereverywhere.transcription.speakers.SpeakerSpan
import com.whispereverywhere.transcription.speakers.WindowKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * The session's transcript, as RUNS — one stretch of text per speaker turn (4.10 Task 5).
 *
 * Every finalized segment is appended to [sessionFile] as it arrives, so a session that ends in a
 * process kill still leaves its words on disk, and the last [previewCapChars] characters or so are
 * rendered into [preview] for the bubble's panel. What 4.10 adds is that the sink now keeps the
 * RUNS beside that text, because four things happen after a chunk has already been delivered:
 *
 *  1. the speaker ids land (~300 ms later — the embedder is deliberately behind the delivery);
 *  2. the tracker's merge pass decides two of its speakers were one person;
 *  3. a SECOND speaker is confirmed, and the panel is rewritten from the session's start with
 *     labels on — the one thing in this app that edits text the user has already read;
 *  4. a RETROSPECTIVE clustering of the session's fingerprints (spike session 6) decides that a
 *     whole stretch of windows was the wrong speaker, and [relabel] rewrites them per window.
 *
 * None of the four can be served by a string that has already been concatenated, which is why
 * this class holds a list and renders, rather than holding the render.
 *
 * ### What it costs, stated honestly
 *
 * The run list holds the session's whole text in memory — so this class is no longer
 * bounded-memory, and the KDoc that used to claim it was has been removed rather than quietly
 * left standing. It costs nothing NEW: the service's `sessionTranscript` StringBuilder held the
 * same text for history's sake and is gone, replaced by these runs. A three-hour session is a few
 * hundred KB of text either way.
 *
 * Appends stay O(window): the panel's tail is rendered from the last runs that fit
 * ([SpeakerLabels.renderTail]) and, while no second speaker has been confirmed, from the same
 * incremental [tail] buffer 4.9 used — byte for byte, which is what makes "a one-speaker session
 * looks exactly like 4.9" true of the panel and not just of the delivered text.
 *
 * ### Threading
 *
 * Every method is `@Synchronized`, as before. In production the appends, the assignments and the
 * latch flip all arrive on Main (the service's one Dispatchers.Main hop), and [close] too; the
 * lock is what keeps a background-executor append from a test or a future caller honest.
 */
class TranscriptSink(
    private val sessionFile: File,
    private val previewCapChars: Int = 4000,
) {
    private val _preview = MutableStateFlow("")
    val preview: StateFlow<String> = _preview

    private val writer: BufferedWriter = BufferedWriter(FileWriter(sessionFile, /* append = */ true))
    private val tail = StringBuilder()

    /** The session's runs, in delivery order. Guarded by this object's lock. */
    private val runs = ArrayList<Run>()

    /**
     * THE LATCH: a second speaker has been CONFIRMED this session. It drives both halves of the
     * spec's second column — the panel's labels and the paragraph breaks every surface gets — and
     * it never goes back, because the panel has already been rewritten with labels and
     * un-rewriting it is the only thing on screen that would move backwards.
     */
    private var labelsVisible = false

    private var closed = false

    /**
     * How many speakers the session has confirmed, as the formatter's rule reads it.
     *
     * The tracker publishes a LATCH, not a census (`SpeakerAssignment.confirmed`), and the latch
     * certifies exactly "two or more" — so that is the number reported. The spec's rule is
     * "fewer than two", so the distinction between two and five never changes a character of
     * output; what does change is the [SpeakerLabels.Mode] and the ids in the runs, both of which
     * are carried separately.
     */
    val confirmedSpeakers: Int
        get() = if (labelsVisible) SpeakerLabels.MIN_CONFIRMED_SPEAKERS else 0

    /** How many runs this session has accumulated — the diag line's `runs=` and nothing else. */
    val runCount: Int
        @Synchronized get() = runs.size

    /**
     * One committed chunk.
     *
     * [spans] is the chunk's speaker geometry or null for a chunk that has none — cloud, the NPU
     * tier, a chunk the VAD found no speech in, or speaker detection switched off. Null keeps
     * 4.9's behaviour exactly: one unassignable run carrying the whole chunk.
     *
     * The FILE is written with the same [TextJoin] policy as before, and with the same text: a
     * chunk's runs are guaranteed by [SpeakerRuns.of] to join back to exactly its committed text,
     * so the bytes appended here are the bytes 4.9 appended. A write failure (a post-close append,
     * a full disk) leaves the runs untouched as well, so what is in memory and what is on disk
     * never disagree about which chunks arrived.
     */
    @Synchronized
    fun append(seq: Long, spans: List<SpeakerSpan>?, text: String) {
        val arrived = SpeakerRuns.of(seq = seq, text = text, spans = spans)
        if (arrived.isEmpty()) return
        val s = TextJoin.assemble(arrived.map { it.text })
        // TextJoin governs the join (W2 final-only commit): this file IS the transcript the
        // final delivery ships, so 'Hello'+'.' must read 'Hello.' — exactly what sequential
        // per-segment injection used to produce — and a CJK boundary must not grow a stray
        // space. [tail]'s last char is always the last char written to the file (truncation
        // only eats the FRONT), so it is the left side of every boundary decision.
        val needsSpace = tail.isNotEmpty() && TextJoin.needsSpace(tail, s)
        try {
            if (needsSpace) writer.write(" ")
            writer.write(s)
            writer.flush()
        } catch (e: Exception) {
            // Post-close appends land here by design (benign); real I/O failures (disk full)
            // do too — log so a delivered-file-vs-history divergence is diagnosable.
            android.util.Log.w("WE-DIAG", "sink append dropped (${s.length} chars): $e")
            return
        }
        if (needsSpace) tail.append(' ')
        tail.append(s)
        if (tail.length > previewCapChars) {
            tail.delete(0, tail.length - previewCapChars)
        }
        runs += arrived
        repaint()
    }

    /**
     * One chunk's speaker ids, arriving after its text has already been delivered and painted.
     *
     * [ids] carries one id per FINGERPRINT WINDOW in chunk order and [remap] the tracker's accumulated
     * merges, which reach BACKWARDS across the whole session — that is the design of the
     * online-then-refine pass, not a race (see `SpeakerRuns.applyRemap`). The remap is applied
     * after the stamp so a chunk whose own ids were just superseded is corrected in the same call.
     *
     * Accepted after [close] and deliberately so: the last chunk of a session resolves behind the
     * stop tap, and its ids are worth having for the history render even though the delivered file
     * has already been written.
     */
    @Synchronized
    fun assign(seq: Long, ids: List<Int>, remap: Map<Int, Int>) {
        SpeakerRuns.applyAssignment(runs, seq = seq, ids = ids)
        SpeakerRuns.applyRemap(runs, remap)
        repaint()
    }

    /**
     * THE SECOND LOOK's patch — a label per fingerprint WINDOW, for the whole session at once
     * (spike session 6).
     *
     * [assign] is one chunk's opinion, formed live, and [SpeakerRuns.applyRemap] can only say
     * "speaker 3 was speaker 1 all along". This is the retrospective pass, and it is EXACT: it
     * rewrites each named run's speaker directly, which is the only thing that can undo session
     * 6's 03:27 failure, where the online matcher locked onto one id and gave fifty windows of
     * two different voices the same number. A window it does not name keeps what it has.
     *
     * Accepted after [close] for the same reason [assign] is: the session's LAST pass runs inside
     * the finalize fence, and its labels are what the export and the history sidecar are rendered
     * from.
     */
    @Synchronized
    fun relabel(windowLabels: Map<WindowKey, Int>) {
        if (windowLabels.isEmpty()) return
        SpeakerRuns.applyWindowLabels(runs, windowLabels)
        repaint()
    }

    /**
     * Flips the latch. Returns true only when it actually MOVED, which is what makes the relabel
     * diag line one-per-session rather than one-per-chunk: the assigner republishes
     * `confirmed = true` on every chunk after the first, and the retrospective pass republishes
     * its own verdict on every pass.
     *
     * It is only ever called with `true` (`SpeakerLabelsWiringPinTest`): the panel has already
     * been rewritten with labels by then, and un-rewriting it is the one thing on screen that
     * would move backwards.
     */
    @Synchronized
    fun setLabelsVisible(on: Boolean): Boolean {
        if (labelsVisible == on) return false
        labelsVisible = on
        repaint()
        return true
    }

    /**
     * A snapshot of the session's runs — COPIES, so the history render and its sidecar describe
     * the same moment even if a straggler's ids land while they are being written.
     */
    @Synchronized
    fun runs(): List<Run> = runs.map { it.copy() }

    fun fullTextFile(): File = sessionFile

    /**
     * Ends the session's file, and this is where it becomes the FIELD render: paragraph breaks at
     * every speaker change, never a label (spec §2 — "a text field is not a transcript").
     *
     * The rewrite happens ONLY when the latch is on, and that is an exact optimisation rather than
     * a shortcut: with fewer than two confirmed speakers `render(runs, Field, …)` is
     * `TextJoin.assemble` of the runs, which is byte for byte what the appends above already
     * wrote. So a one-speaker session — the overwhelming majority — closes exactly as 4.9 closed,
     * and the one session that pays for a rewrite is the one whose content must change.
     *
     * Idempotent: a second close (teardown following a finalize, by design) rewrites nothing.
     */
    @Synchronized
    fun close() {
        try {
            writer.flush()
            writer.close()
        } catch (_: Exception) {
        }
        if (closed) return
        closed = true
        if (!labelsVisible) return
        try {
            sessionFile.writeText(
                SpeakerLabels.render(runs, SpeakerLabels.Mode.Field, confirmedSpeakers),
            )
        } catch (e: Exception) {
            // The appended text is still there and still complete — it just has no paragraphs.
            // Losing the breaks beats losing the transcript.
            android.util.Log.w("WE-DIAG", "sink field render dropped (${runs.size} runs): $e")
        }
    }

    /**
     * The panel's window. While no second speaker is confirmed this is 4.9's incremental tail,
     * character for character; once the latch flips it is the runs re-rendered with labels, which
     * IS the relabel the owner asked for ("then when speaker one comes back … we want to be able
     * to switch that").
     */
    private fun repaint() {
        _preview.value = if (labelsVisible) {
            SpeakerLabels.renderTail(
                runs = runs,
                mode = SpeakerLabels.Mode.Panel(labels = true),
                confirmedCount = confirmedSpeakers,
                maxChars = previewCapChars,
            )
        } else {
            tail.toString()
        }
    }
}
