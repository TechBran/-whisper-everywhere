package com.whispereverywhere.service

import com.whispereverywhere.audio.EndpointCut
import java.util.Locale

/**
 * The 3.7 endpoint diagnostic family (Workstream F). One greppable set of lines, all under the
 * WE-DIAG tag and all joinable on `seq=` with `segment-timing:`, so a single logcat capture
 * answers "why was this segment cut, how long did the user wait, and was the queue growing".
 *
 *     endpoint:       seq=N cut=vad|cap|stop|switch speechMs=… trailMs=… p=…
 *     segment-timing: seq=N audio=… transcribe=… rtf=… vadIn=… vadOut=… ctxFrames=…
 *     queue:          depth=N
 *     perceived:      seq=N speechEndToVisible=…ms
 *     probe:          frames=N p50=…µs p99=…µs overruns=N
 *
 * Pure so every format is JVM-pinned: the owner's acceptance sheet greps these exact strings, and
 * a silent format drift would break every report that parses them. Content discipline is the same
 * as SegmentTiming's — numbers and fixed vocabulary only, NEVER transcript text.
 */
object EndpointDiag {

    /** The endpointer found a real pause: the cadence 3.7 exists to produce. */
    const val VAD = "vad"

    /** The wall-clock backstop fired. Under 3.7 this is a VAD-FAILURE signature, not the norm. */
    const val CAP = "cap"

    /** The unconditional stop flush. */
    const val STOP = "stop"

    /** A mic <-> device-audio source swap cut the segment at the boundary. */
    const val SWITCH = "switch"

    /**
     * Why this seq was cut, and on what evidence. Locale.US: the point is always a point.
     *
     * [ec] is the endpointer's own record of the cut ([com.whispereverywhere.audio.SileroEndpointer.lastCut]),
     * and it is null for every cut that had no probe behind it — the cap, stop and switch sites, and
     * the whole amplitude fallback. A null renders `speechMs=0 trailMs=0 p=-1.00`: the UNKNOWN
     * shape, matching the native frame contract where -1 is "no verdict" and never "silence".
     * `p=0.00` is never emitted for an unknown cut, because it would read as "the probe was certain
     * there was no speech" — a different and much stronger claim.
     */
    fun endpointLine(seq: Long, cut: String, ec: EndpointCut?): String =
        "endpoint: seq=$seq cut=$cut speechMs=${ec?.speechMs ?: 0L} trailMs=${ec?.trailMs ?: 0L} p=" +
            String.format(Locale.US, "%.2f", ec?.prob ?: -1.0f)

    /** The committed-but-unresolved backlog, from [SegmentQueueDepth]. */
    fun queueLine(depth: Int): String = "queue: depth=$depth"

    /**
     * The wall-clock backstop line, REWORDED for 3.7 as the failure signature it becomes.
     *
     * Before 3.7 this was the normal path (the amplitude segmenter's dead band meant most cuts
     * were cap cuts). With a real endpointer it means the endpointer did not fire for a whole cap
     * window — worth investigating every time.
     *
     * Two substrings are load-bearing and are preserved BYTE FOR BYTE, which is why the marker is
     * appended rather than the line rewritten: `wall-clock cap -> commit` is the existing grep,
     * and `cap=<n>ms` is the documented regression signature — `cap=4000ms` appearing in a CLOUD
     * session means the LOCAL-only first-cap suppression broke: the
     * `if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionOpenMs)` line in the live
     * session's `onOpen` inside `FloatingBubbleService.startRecording`. Cited by SYMBOL, not by
     * line number — the number this comment shipped with (`:2238`) had already drifted into an
     * unrelated function by the time anyone read it.
     */
    fun capCommitLine(capMs: Long): String =
        "wall-clock cap -> commit (cap=${capMs}ms) VAD-MISS: no endpoint in this window"

    /**
     * The headline metric: how long the user waited between finishing a sentence and seeing it.
     * Emitted only for endpoint cuts — see [PerceivedLatency] for why cap/stop/switch cuts have
     * no honest number here.
     *
     * INTEGER milliseconds, deliberately, and therefore the one line in this family with no
     * `Locale` on it: Kotlin renders a `Long` through `Long.toString`, which has no locale at all,
     * so the comma-decimal hazard `endpointLine`'s `p=` field carries (and pins) does not exist
     * here. If this ever grows a fractional field it needs `String.format(Locale.US, …)` and a
     * locale-driven test, exactly as `endpointLine` has.
     */
    fun perceivedLine(seq: Long, speechEndToVisibleMs: Long): String =
        "perceived: seq=$seq speechEndToVisible=${speechEndToVisibleMs}ms"
}
