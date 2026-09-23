package com.whispereverywhere.service

import java.util.concurrent.atomic.AtomicLong

/**
 * THE MUTE, and everything it decides (owner ruling 2026-09-22: *"if someone walks into a room
 * while you're transcribing and talking ... you mute the microphone and mute everything so
 * another person walking in, audio won't enter your window"*).
 *
 * ### What muting IS: silence, at the one seam every source passes through
 *
 * While muted, [gate] overwrites each captured chunk with zeros and reports its level as 0, and
 * the service calls it FIRST in `onAudioChunk` — the shared downstream of both the microphone
 * and device-audio capture, ahead of the startup ring, the engine, the endpointer and the
 * visuals. So muted audio reaches nothing: not whisper, not the live-words previewer, not the
 * speaker fingerprints, not a cloud provider, not the waveform.
 *
 * Zeros rather than dropping the chunk or stopping the recorder, because silence is the state
 * every part of the pipeline already handles best:
 *  - **What you said before the tap still lands.** The endpointer hears silence and commits the
 *    utterance in progress (Silero's hangover on the mic; the flatline cut on device audio, which
 *    exists to see exactly this). A dropped chunk would leave that utterance open and glue it to
 *    whatever came after the unmute.
 *  - **Audio time stays wall time.** The cap cut's retain window, the endpointer's stamps and the
 *    startup ring's replay all count on it; a gap would make the next cap cut keep the wrong bytes.
 *  - **Nothing restarts.** Stopping capture would re-run the device-audio consent and the DRM
 *    watchdog, and an unmute over a quiet video would then fall back to the MICROPHONE — the
 *    opposite of mute.
 *
 * The trade, stated: the recorder stays open, so Android's microphone indicator stays on while
 * muted, and a cloud session keeps streaming (silent) audio, which the provider may bill.
 *
 * ### Scope: one session
 *
 * Every session starts unmuted ([beginSession] runs at session start, [endSession] at teardown).
 * A mute remembered into the next session would record nothing and look like a broken app.
 * [mutedThisSession] outlives the teardown on purpose: the stop path decides its "nothing was
 * transcribed" message AFTER teardown has unmuted, and a session that heard nothing because the
 * user muted it must not be told to speak up.
 *
 * ### Threads
 *
 * [set], [beginSession] and [endSession] run on Main (the toggle and the session lifecycle);
 * [gate] runs on the capture thread. [muted] is `@Volatile` with Main as its only writer, and
 * [gate] reads it ONCE per chunk,
 * so a chunk is either wholly muted or wholly not. The byte counter is atomic because a capture
 * thread outliving its timed join can briefly overlap its successor; it feeds diagnostics only.
 */
class CaptureMute {

    @Volatile
    var muted: Boolean = false
        private set

    /** Was this session muted at any point? Cleared only by [beginSession] (see "Scope"). */
    var mutedThisSession: Boolean = false
        private set

    private val zeroedBytes = AtomicLong(0)
    private var mutedAtMs = 0L

    /**
     * The capture-thread seam: when muted, [chunk] is zeroed IN PLACE and the returned level is 0;
     * otherwise [chunk] is untouched and [amp] is returned. In place is safe and allocation-free
     * because both sources hand `onAudioChunk` a fresh array per chunk (`copyOf` on the mic, the
     * decimator's own output on device audio), and it is zeroed before anything else holds it.
     */
    fun gate(chunk: ByteArray, amp: Int): Int {
        if (!muted) return amp
        java.util.Arrays.fill(chunk, 0.toByte())
        zeroedBytes.addAndGet(chunk.size.toLong())
        return 0
    }

    /**
     * Main: turn the mute on or off. Returns the diagnostic line for the edge, or null when [on]
     * is already the state (a double tap delivered twice must not log twice).
     */
    fun set(on: Boolean, nowMs: Long, source: String, state: String): String? {
        if (on == muted) return null
        muted = on
        return if (on) {
            mutedThisSession = true
            mutedAtMs = nowMs
            zeroedBytes.set(0)
            "mute: on source=$source state=$state"
        } else {
            "mute: off heldMs=${nowMs - mutedAtMs} zeroedMs=${zeroedMs()}"
        }
    }

    /** Main: a session starts, unmuted, with no mute in its history. */
    fun beginSession(nowMs: Long): String? {
        mutedThisSession = false
        return endSession(nowMs)
    }

    /**
     * Main: the session ends. Unmutes, and returns a line only if it was still muted when it
     * ended — the one case worth a record, since nothing else would show it. [mutedThisSession]
     * is kept for the stop path's message (see "Scope").
     */
    fun endSession(nowMs: Long): String? {
        if (!muted) return null
        muted = false
        return "mute: session ended muted heldMs=${nowMs - mutedAtMs} zeroedMs=${zeroedMs()}"
    }

    /** Milliseconds of capture zeroed since the last mute: 16 kHz mono 16-bit is 32 bytes/ms. */
    fun zeroedMs(): Long = zeroedBytes.get() / BYTES_PER_MS

    companion object {
        /** Both capture sources deliver 16 kHz mono PCM16 to `onAudioChunk`. */
        const val BYTES_PER_MS = 32L
    }
}
