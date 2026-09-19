package com.whispereverywhere.transcription.speakers

import java.util.Locale

/**
 * THE ONE LINE PER COMMITTED CHUNK that the 4.10 speaker spike is measured from (plan Task 3; plan
 * Task 4 reads it).
 *
 * ```
 * speaker: seq=12 segs=3 windows=3 embedMs=214 ids=[1,1,2] best=[-,0.88,0.31] dur=[3.2,1.1,2.4] confirmed=1 load=0 route=geom
 * speaker: seq=13 segs=2 windows=2 embedMs=98 ids=[3,1] best=[0.41,0.92] dur=[2.6,3.1] confirmed=1 load=0 route=geom remaps=[3>1]
 * speaker: seq=14 segs=2 windows=5 embedMs=712 ids=[1,2,1,2,1] best=[-,0.21,0.84,0.90,0.79] dur=[4.1,2.0,2.3,3.0,2.6] confirmed=1 load=0 route=geom
 * speaker: seq=15 segs=4 windows=3 embedMs=286 ids=[2,1,1] best=[0.31,0.88,0.91] dur=[1.4,2.9,1.2] confirmed=1 load=0 route=vad pick=1
 * ```
 *
 * The second line is the shape a MERGE takes (spike session 2): the chunk reported speaker 3 and
 * then the end-of-chunk pass decided speaker 3 was speaker 1 all along. `remaps=` appears only on
 * the chunks where something moved — see the field's own comment below for why it is not always
 * printed.
 *
 * The fourth is the NPU TIER (4.10, the Fold6 defect): `route=vad`, the windows came from a
 * standalone VAD run rather than from whisper's geometry, and `windows < segs` because two short
 * segments were coalesced rather than one long one split. `pick=1` says the whole chunk's text
 * wears `ids[1]` — that decoder publishes no token timestamps, so the text cannot be cut between
 * the three voices the audio was fingerprinted for.
 *
 * The third is the shape the SPLIT takes: two VAD segments, five windows, because a segment
 * reached `SpeakerSpans.LONG_SEGMENT_SECONDS` with several whisper segments inside it and was
 * fingerprinted per sentence instead of per pause. Since the 2026-09-18 late session that floor
 * is 2.0 s, so `windows > segs` is the NORMAL shape of a conversational chunk rather than a rare
 * event — which makes this line the measurement of two different things at once: how much
 * sentence-level cutting a session does, and (read against `embedMs=`) how close its chunks come
 * to the finalize fence now that a chunk costs one fingerprint per sentence.
 *
 * It is a formatter with a test because the line is an INSTRUMENT, not a log. `T_SAME` / `T_NEW`
 * are set from the `best=` column of a real session (spec §3.2 step 3 defers them to the spike
 * deliberately), the embedding model passes or fails on `embedMs=`, and the false-split count comes from reading
 * `ids=` against what the owner heard. A column that shifts, drops a row for the segments that
 * were never fingerprinted, or renders `0.31` as `0,31` on a device set to German is a device
 * session that has to be run again — and the owner's device sessions are the scarce resource in
 * this project.
 *
 * ### The rules the shape follows
 *
 *  - **`seq=` first after the tag**, so the line joins `segment-timing:`, `queue:` and
 *    `perceived:` on the key those already share.
 *  - **Three parallel columns**, one entry each per FINGERPRINT WINDOW, in chunk order, ALWAYS —
 *    including the windows that inherited a label without being fingerprinted. `windows=` is that
 *    length, stated once so a truncated line is detectable; `segs=` is the number of VAD segments
 *    those windows came out of. On `route=geom` the two are equal on every chunk that was not
 *    split and `windows` is the larger otherwise; on `route=vad` there is no splitting at all, so
 *    `segs` is the larger wherever short segments were coalesced.
 *  - **`-` for a similarity that was never measured**, never `0.00`. Zero is a real and
 *    meaningful reading — two orthogonal voices — and "not measured" must not borrow its glyph in
 *    a column that is about to become a threshold.
 *  - **`Locale.ROOT`**, because `String.format` without one takes the device's default and a
 *    comma decimal separator makes every number here unparseable.
 *  - **No spaces inside a column**, so one line splits into the `speaker:` tag and ten
 *    `key=value` fields — eleven on an NPU-route chunk, which adds `pick=`, and one more again on
 *    the rare chunk that carries a `remaps=`.
 *  - **Not one character of transcript.** The type makes it unreachable: [SpeakerAssignment] has
 *    no text in it at all.
 *
 * It is emitted through `WhisperNative.diag` — native `__android_log_print` — and not through
 * `android.util.Log`, because R8 strips every Kotlin `Log` call from the release build
 * (`proguard-rules.pro`, "Release log hygiene") and the release build is the one the owner runs
 * the device session on.
 */
object SpeakerDiag {

    /** What the device session greps for. */
    const val PREFIX: String = "speaker:"

    /** Renders [assignment] as the line above. Pure; safe on any thread. */
    fun line(assignment: SpeakerAssignment): String {
        val stats = assignment.stats
        return buildString {
            append(PREFIX)
            append(" seq=").append(assignment.seq)
            append(" segs=").append(assignment.segs)
            // The COLUMN LENGTH, and the only field that says how many fingerprints this chunk
            // actually paid for. It sits beside `segs=` rather than replacing it because their
            // DIFFERENCE is the measurement session 4 asks for: how often the endpointer hands
            // over a segment long enough to hold two voices.
            append(" windows=").append(assignment.ids.size)
            append(" embedMs=").append(stats.embedMs)
            append(" ids=").append(column(assignment.ids) { it.toString() })
            append(" best=").append(column(stats.best) { similarity(it) })
            append(" dur=").append(column(stats.durationsSec) { seconds(it) })
            append(" confirmed=").append(if (assignment.confirmed) 1 else 0)
            // LAST, and additive to the plan's stated eight fields, so a reader (or a script)
            // that takes the first eight positionally is unaffected. `load=1` marks the one
            // chunk per session whose `embedMs` also paid the lazy model load; without it that
            // chunk is an unexplained outlier against the budget CAM++ is judged on, and the
            // adapter's own load line is stripped from the release build by R8.
            append(" load=").append(if (stats.includesModelLoad) 1 else 0)
            // WHICH ROUTE FED THIS CHUNK (4.10, the Fold6 defect), and it is ALWAYS printed —
            // `route=geom` on the CPU and GPU tiers, `route=vad` on the NPU one. Always, because
            // the two tiers can both appear in ONE session (a mid-session NPU decline falls back
            // to whisper.cpp, which publishes geometry again), so "no route field" would have to
            // mean "geom" and a truncated line would then read as a CPU chunk. A field that is
            // sometimes absent is not a measurement.
            //
            // On `route=vad` the chunk's TEXT wears one label — the decoder exposes no token
            // timestamps — so `pick=` names which of the `ids=` above it that is. It is printed
            // only on that route, because on the other one nothing is picked and a `pick=-`
            // column would invite the reader to look for one.
            append(" route=").append(if (assignment.wholeChunkWindow == null) "geom" else "vad")
            assignment.wholeChunkWindow?.let { append(" pick=").append(it) }
            // CONDITIONAL, and last of all. A merge is rare — most chunks move nothing — and a
            // `remaps=[]` on every line would make the one thing worth grepping for invisible in
            // eighteen lines of noise. When it IS there it is the correction to the `ids=` column
            // printed beside it, and to earlier chunks' ids too, which is exactly the sequence a
            // reader of these lines has to be able to reconstruct: `3>1` says the segments already
            // labelled 3 were speaker 1 all along.
            if (assignment.remaps.isNotEmpty()) {
                append(" remaps=").append(
                    assignment.remaps.entries.joinToString(separator = ",", prefix = "[", postfix = "]") {
                        "${it.key}>${it.value}"
                    }
                )
            }
        }
    }

    /** What the relabel line is grepped for. */
    const val RELABEL_PREFIX: String = "speaker-labels:"

    /**
     * THE RELABEL — one line per SESSION, not per chunk (plan Task 5):
     *
     * ```
     * speaker-labels: confirmed=2 runs=14 relabelled=1
     * ```
     *
     * It is emitted the moment the panel is rewritten with labels from the session's start, which
     * happens exactly once (the latch never goes back), so a second one of these in a session's
     * log is a bug and is meant to be visible as one. `relabelled=1` is therefore a constant and
     * is here on purpose: it makes the line's meaning legible on its own, beside the
     * `speaker: … confirmed=1` chunk lines that lead up to it.
     *
     * [confirmed] is the number the tracker's LATCH certifies — "two or more" — not a census; the
     * per-segment ids are in the `speaker:` lines above it. [runs] is how many stretches of text
     * the rewrite covered. **Numbers only**, like every line in this file: a run's text is user
     * speech and never reaches a log.
     */
    fun relabelLine(confirmed: Int, runs: Int): String =
        "$RELABEL_PREFIX confirmed=$confirmed runs=$runs relabelled=1"

    /** What the retrospective pass is grepped for. */
    const val RECLUSTER_PREFIX: String = "speaker-recluster:"

    /**
     * THE SECOND LOOK — one line per retrospective pass (spike session 6):
     *
     * ```
     * speaker-recluster: n=48 clusters=2 confirmed=2 changed=11 ms=63
     * ```
     *
     * Every column answers one question the device session has to be able to ask of a build that
     * rewrites labels behind the user's back:
     *
     *  - **`n=`** how many fingerprints were weighed — the cost driver, since the pass is O(n²),
     *    and the number that says whether a long session is sitting on the
     *    [SpeakerReclusterer.MAX_RECLUSTER_FINGERPRINTS] cap;
     *  - **`clusters=` / `confirmed=`** what it concluded, against the `ids=` of the `speaker:`
     *    lines above it. A session where the online pass opened five ids and this line says
     *    `clusters=2 confirmed=2` is exactly the 03:27 failure being corrected;
     *  - **`changed=`** how many windows' labels actually MOVED. Zero is the ordinary reading and
     *    is what says a pass cost nothing but its milliseconds; a large number on every pass says
     *    the online and retrospective halves disagree systematically, which is a finding;
     *  - **`ms=`** what it cost on the embed thread, read against the `embedMs=` beside it — the
     *    two together are what the finalize fence has to cover.
     *
     * Numbers only, like every line in this file. A window key is a sequence number and an index,
     * and not one of them reaches this string.
     */
    fun reclusterLine(relabel: SpeakerRelabel): String = buildString {
        append(RECLUSTER_PREFIX)
        append(" n=").append(relabel.fingerprints)
        append(" clusters=").append(relabel.clusterCount)
        append(" confirmed=").append(relabel.confirmedCount)
        append(" changed=").append(relabel.changed)
        append(" ms=").append(relabel.costMs)
    }

    /** `[a,b,c]`, and `[]` for nothing — never a placeholder row for a chunk with no segments. */
    private fun <T> column(values: List<T>, render: (T) -> String): String =
        values.joinToString(separator = ",", prefix = "[", postfix = "]") { render(it) }

    /** Two decimals, or `-` for anything that is not a real measured similarity. */
    private fun similarity(value: Float): String =
        if (value.isFinite()) String.format(Locale.ROOT, "%.2f", value) else "-"

    /** One decimal: the durations are read as "about how long did this person speak". */
    private fun seconds(value: Float): String =
        if (value.isFinite()) String.format(Locale.ROOT, "%.1f", value) else "-"
}
