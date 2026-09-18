package com.whispereverywhere.transcription.speakers

import java.util.Locale

/**
 * THE ONE LINE PER COMMITTED CHUNK that the 4.10 speaker spike is measured from (plan Task 3; plan
 * Task 4 reads it).
 *
 * ```
 * speaker: seq=12 segs=3 embedMs=214 ids=[1,1,2] best=[-,0.88,0.31] dur=[3.2,1.1,2.4] confirmed=1 load=0
 * speaker: seq=13 segs=2 embedMs=98 ids=[3,1] best=[0.41,0.92] dur=[2.6,3.1] confirmed=1 load=0 remaps=[3>1]
 * ```
 *
 * The second line is the shape a MERGE takes (spike session 2): the chunk reported speaker 3 and
 * then the end-of-chunk pass decided speaker 3 was speaker 1 all along. `remaps=` appears only on
 * the chunks where something moved — see the field's own comment below for why it is not always
 * printed.
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
 *  - **Three parallel columns**, one entry each per VAD segment, in chunk order, ALWAYS —
 *    including the segments that inherited a label without being fingerprinted. `segs=` is that
 *    length, stated once so a truncated line is detectable.
 *  - **`-` for a similarity that was never measured**, never `0.00`. Zero is a real and
 *    meaningful reading — two orthogonal voices — and "not measured" must not borrow its glyph in
 *    a column that is about to become a threshold.
 *  - **`Locale.ROOT`**, because `String.format` without one takes the device's default and a
 *    comma decimal separator makes every number here unparseable.
 *  - **No spaces inside a column**, so one line splits into nine `key=value` fields — ten on
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
            append(" segs=").append(assignment.ids.size)
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
