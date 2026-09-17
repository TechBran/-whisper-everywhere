package com.whispereverywhere.transcription.stream

import java.util.Locale

/**
 * The previewer's three greppable lines (spec §8), pure so the exact format is JVM-pinned
 * (StreamDiagTest — the SegmentTiming discipline). Numbers, codes, a language code, never text:
 * `outLen` is a length. `stream-timing:` joins to the funnel's `endpoint:` / `queue:` /
 * `perceived:` lines on `seq`.
 */
object StreamDiag {

    /**
     * `warm` is the OUTCOME, and it is here because the line without it could not report one
     * (4.5.0 languages T1 review r1, B1): `canary=none … load=ok` was printed by a language that
     * had just switched itself off for the process, which is how that class of defect survives a
     * device session. `load=` still answers only "did the model load"; `warm=` answers "is this
     * language's previewer up", so the disable is always named where it happens.
     */
    fun openLine(sherpa: String, ort: String, threads: Int, loadMs: Long, canary: String, canaryMs: Long, outLen: Int, load: String, warm: Boolean): String =
        "stream-open: sherpa=$sherpa ort=$ort threads=$threads provider=cpu loadMs=$loadMs canary=$canary canaryMs=$canaryMs outLen=$outLen load=$load warm=${bit(warm)}"

    fun timingLine(
        seq: Long, audioMs: Long, decodes: Int, decodeMs: Long, p50Us: Long, p99Us: Long, rtf: Double,
        partials: Int, firstPartialMs: Long, padMs: Long, shed: Boolean, retractions: Int,
    ): String =
        "stream-timing: seq=$seq audio=$audioMs decodes=$decodes decodeMs=$decodeMs p50us=$p50Us p99us=$p99Us rtf=" +
            String.format(Locale.US, "%.3f", rtf) +
            " partials=$partials firstPartialMs=$firstPartialMs padMs=$padMs shed=${bit(shed)} retract=$retractions"

    /**
     * `ready=` is the gate's readiness TERM — since 4.8.1 *an engine exists and this pack is not
     * disabled*, which is true while the load is still in flight — and `warm_now=` (4.8.1) is the
     * landed-warm SNAPSHOT the gate used to arm on, kept as a diagnostic: `ready=1 warm_now=0 ->
     * preview=1` says the tap beat the load and the session armed anyway (the fresh-install
     * first session, AF6-fresh); `warm_now=1` says the load had landed before the tap. Every
     * pre-4.8.1 term keeps its name and its place; the new one sits between the term and the
     * outcome so a grep on either end of the line still lands.
     */
    fun gateLine(
        lang: String?, packInstalled: Boolean, isCloudSession: Boolean, batchJobActive: Boolean,
        userEnabled: Boolean, previewReady: Boolean, warmNow: Boolean, armed: Boolean,
    ): String =
        "stream-gate: lang=${lang ?: "auto"} pack=${bit(packInstalled)} cloud=${bit(isCloudSession)} batch=${bit(batchJobActive)} " +
            "enabled=${bit(userEnabled)} ready=${bit(previewReady)} warm_now=${bit(warmNow)} -> preview=${bit(armed)}"

    /** Σ decode / audio; a zero audio length (a degenerate commit) reports 0.0 rather than dividing by zero. */
    fun rtf(decodeMs: Long, audioMs: Long): Double = if (audioMs <= 0L) 0.0 else decodeMs.toDouble() / audioMs.toDouble()

    /** Nearest-rank percentile over a sorted copy; 0 for no samples. */
    fun percentileUs(samples: List<Long>, p: Double): Long {
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val rank = Math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun bit(v: Boolean): Int = if (v) 1 else 0
}
