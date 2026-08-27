package com.whispereverywhere.service

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

    /** The committed-but-unresolved backlog, from [SegmentQueueDepth]. */
    fun queueLine(depth: Int): String = "queue: depth=$depth"
}
