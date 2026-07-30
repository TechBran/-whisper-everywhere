package com.whispereverywhere.transcription

import com.whispereverywhere.audio.ActiveSource
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WHICH ENGINE MAY LEGALLY RECEIVE AUDIO CAPTURED FROM [source]. Pure, total, and the single
 * place this project's device-audio privacy rule is written down as CODE rather than as prose.
 *
 * [ActiveSource.PLAYBACK] audio is captured through MediaProjection: it is the audio of OTHER
 * APPS — the person on the other end of a call, a podcast host, whoever is in the video. Those
 * people never consented to anything, and the user granted a screen-capture permission to have it
 * transcribed ON THIS DEVICE, not to have it redistributed. `privacy_policy.html` / `docs/privacy.html`
 * §6-§7 and `docs/PLAY-DECLARATIONS.md` §3/§5 all state, verbatim, that device audio never leaves
 * the device. This function is what makes those four statements true.
 *
 * So the rule is a routing rule, not a settings rule: it does not consult the selected provider,
 * the key, or the network, because none of those can make third-party audio shippable.
 * Microphone audio, by contrast, follows the user's own choice — [micEngine] may be a cloud engine.
 */
internal fun <T : Any> engineForSource(source: ActiveSource, micEngine: T, deviceEngine: T): T =
    when (source) {
        ActiveSource.PLAYBACK -> deviceEngine
        ActiveSource.MIC -> micEngine
    }

/**
 * Routes a session's audio to the engine that [engineForSource] says owns the CURRENT capture
 * source, and hides the swap from everything upstream.
 *
 * The service used to hand every chunk to one engine chosen at session start from
 * (provider, key, network) — `activeSource` was not an input at all — so with a cloud provider
 * configured, MediaProjection audio from other apps was uploaded. This class is the gate: while
 * the source is [ActiveSource.PLAYBACK] the cloud engine is CLOSED and holds no buffer, so it
 * cannot observe a single byte of device audio even by accident.
 *
 * Three things make that swap safe, and each is a bug that is easy to write instead:
 *
 *  1. **One seq space.** Both children restart seq at 0 on connect(), and
 *     [SegmentOrderer] drops any seq below its head — so forwarding a child's seq after a swap
 *     would silently delete every segment of the second era. This engine allocates its OWN
 *     monotonic seq and keeps a per-era child-seq -> our-seq map.
 *
 *  2. **Every seq [commit] hands out resolves EXACTLY ONCE**, including across a swap. Closing
 *     the outgoing engine is what makes that deterministic: [FallbackTranscriptionEngine] and
 *     [com.whispereverywhere.transcription.cloud.CloudTranscriptionEngine] resolve everything they
 *     owe inside close(), while [LocalWhisperEngine] merely detaches (its queued transcribes become
 *     no-ops) — so whatever the outgoing engine did not resolve is swept here as
 *     [SegmentOutcome.Lost], under an atomic claim so a racing real resolution cannot double it.
 *
 *  3. **The boundary audio goes somewhere legal.** Uncommitted mic audio may follow the user into
 *     the on-device engine, so it is carried over and transcribed rather than dropped. Uncommitted
 *     DEVICE audio may never follow into the cloud engine, so it is cut into the outgoing on-device
 *     engine instead — never carried.
 *
 * [sendAudio] runs on the AUDIO CAPTURE THREAD every ~32 ms: it takes one lock, appends to two
 * buffers, returns. No I/O, no waiting.
 *
 * @param micEngine the user's actual choice for microphone audio (may be a cloud engine). Lifecycle
 *   calls (prewarm/releaseContext/shutdown) are forwarded ONLY here: [deviceEngine] must either BE
 *   this engine or be reachable from it (in production it is the local engine that
 *   [FallbackTranscriptionEngine] already owns), so forwarding to both would shut the on-device
 *   engine's executor down twice.
 * @param deviceEngine the ON-DEVICE engine. Must never be, or wrap, a cloud engine.
 */
class SourceRoutedTranscriptionEngine(
    private val micEngine: TranscriptionEngine,
    private val deviceEngine: TranscriptionEngine,
) : TranscriptionEngine {

    /**
     * Guards the era, the seq counter, the pending mirror and every per-era map. Also the OUTER
     * lock whenever a child's own lock is taken underneath it (sendAudio/commit): never the reverse.
     */
    private val lock = Any()

    /** Audio of the current era that has not been cut yet — the carry-over at a source switch. */
    private val pending = ByteArrayOutputStream()

    /** OUR seq space: monotonic across every era of one session. Reset per [connect]. */
    private var nextSeq = 0L

    /** The capture source the session is routed to right now. */
    private var source = ActiveSource.MIC

    private var era: Era? = null

    /** One onOpen per SESSION: later eras are internal cutovers, not new sessions. */
    private var opened = false

    @Volatile private var language: String? = null
    @Volatile private var owner: TranscriptionEngine.Listener? = null

    /**
     * One committed segment in OUR seq space. [claimed] is what makes the resolution exactly-once
     * across the three callers that legitimately race for it: the child engine's callback, the
     * cutover sweep, and [close].
     */
    private class Cut(val seq: Long, private val owner: TranscriptionEngine.Listener) {
        /** The child's own seq for this cut, or [UNMAPPED] until commit() has returned it. */
        var childSeq: Long = UNMAPPED
        private val claimed = AtomicBoolean(false)

        fun isClaimed(): Boolean = claimed.get()

        fun resolve(outcome: SegmentOutcome) {
            if (!claimed.compareAndSet(false, true)) return
            owner.onSegmentResolved(seq, outcome)
        }
    }

    /**
     * One stretch of the session served by ONE child engine. It is also that child's listener, so
     * a straggler from an era that has ended can be told apart from a live resolution by identity
     * rather than by seq — the two eras' seq spaces overlap by construction.
     */
    private inner class Era(val engine: TranscriptionEngine) : TranscriptionEngine.Listener {

        /** child seq -> our cut. Guarded by [lock]. */
        val cuts = HashMap<Long, Cut>()

        /** The cut whose `engine.commit()` has not returned yet. Guarded by [lock]. */
        var submitting: Cut? = null

        @Volatile var live = true

        override fun onOpen() {
            val first = synchronized(lock) { if (opened) false else { opened = true; true } }
            // A second onOpen would read as a second session upstream (the service starts the
            // recorder in it). Cutovers are invisible; only the first era opens the session.
            if (first) owner?.onOpen()
        }

        override fun onDelta(text: String) {
            if (live) owner?.onDelta(text)
        }

        override fun onError(message: String) {
            // An error from an engine we have already switched away from is about a session stretch
            // the user has left; surfacing it could tear down the live one.
            if (live) owner?.onError(message)
        }

        /** Swallowed: a cutover closes a child, but only [close] ends the session. */
        override fun onClosed() = Unit

        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            val cut = synchronized(lock) {
                val mapped = cuts.remove(seq)
                    // Resolved re-entrantly INSIDE commit(), before there was a child seq to map it
                    // against. Not contrived: LocalWhisperEngine on a same-thread executor lands
                    // here on the submitting thread, which still holds this lock.
                    ?: submitting?.takeIf { it.childSeq == UNMAPPED || it.childSeq == seq }
                // A cut WE never made: the on-device engine self-commits at its 30 s buffer cap.
                // Unreachable from the service (its wall-clock cap cuts at 15 s), but dropping it
                // would silently delete real speech, so it is adopted with a seq of its own.
                val adopter = owner
                when {
                    mapped != null -> mapped
                    live && era === this@Era && adopter != null -> Cut(nextSeq++, adopter)
                    // A straggler from an era that has already been swept: it was resolved at
                    // sweep time, and `claimed` would make a second delivery a no-op anyway.
                    else -> null
                }
            }
            // Outside any decision about the map: this callback runs the orderer and, through it,
            // the service's typing.
            cut?.resolve(outcome)
        }
    }

    // ------------------------------------------------------------------ TranscriptionEngine

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        // Owed to the PREVIOUS listener, and owed before anything of this session exists.
        synchronized(lock) { era.also { era = null } }
            ?.let { endEra(it, SegmentOutcome.Lost(SESSION_RESTARTED)) }
        val incoming: Era
        synchronized(lock) {
            this.language = language
            this.owner = listener
            opened = false
            nextSeq = 0L
            pending.reset()
            // Every session starts on the microphone: the service resets activeSource to MIC when a
            // session ends, and startAudioInput() picks the device stream only AFTER connect, via
            // onSourceChanged. Anything else would route the first chunks by a stale source.
            source = ActiveSource.MIC
            incoming = Era(engineForSource(source, micEngine, deviceEngine))
            era = incoming
        }
        incoming.engine.connect(language, incoming)
    }

    /**
     * AUDIO CAPTURE THREAD, every ~32 ms. One lock, two buffer appends, return.
     *
     * The mirror is what lets a source switch carry uncommitted audio across instead of dropping
     * it; it is reset by every cut, so it holds at most one segment.
     */
    override fun sendAudio(pcm: ByteArray) {
        synchronized(lock) {
            val current = era ?: return
            pending.write(pcm)
            current.engine.sendAudio(pcm)
        }
    }

    /**
     * Cuts a segment on the active engine and returns OUR seq for it.
     *
     * The child's seq is never returned upstream: the two children number independently and both
     * restart at 0, so a swap would produce duplicate seqs and the orderer would drop a whole era.
     */
    override fun commit(): Long {
        synchronized(lock) {
            val current = era ?: return NO_SEGMENT
            val listener = owner ?: return NO_SEGMENT
            val cut = Cut(nextSeq, listener)
            current.submitting = cut
            val childSeq = try {
                current.engine.commit()
            } catch (t: Throwable) {
                current.submitting = null
                // A throwing engine must not burn a seq the service is then owed forever.
                android.util.Log.w(TAG, "active engine refused the commit", t)
                return NO_SEGMENT
            } finally {
                // Cleared inside the lock, so no other thread can ever see it stale.
                current.submitting = null
            }
            if (childSeq < 0) {
                // Nothing was cut, so nothing is owed — unless the engine somehow resolved inline,
                // in which case the seq has already been delivered and must be kept.
                return if (cut.isClaimed()) { nextSeq++; cut.seq } else NO_SEGMENT
            }
            nextSeq++
            pending.reset()
            cut.childSeq = childSeq
            // Already delivered re-entrantly: mapping it now would leave an entry the child will
            // never resolve again, which the cutover sweep would then re-deliver as a loss.
            if (!cut.isClaimed()) current.cuts[childSeq] = cut
            return cut.seq
        }
    }

    /**
     * The capture source changed. MUST be called BEFORE the new capturer delivers its first chunk —
     * the whole point is that no cloud engine is attached while device audio is flowing.
     *
     * Idempotent: calling it with the source already in effect does nothing, so the service can call
     * it from every site that sets `activeSource` without reasoning about which of them is a change.
     */
    fun onSourceChanged(source: ActiveSource) {
        val outgoing: Era
        val incoming: Era
        var carried: ByteArray? = null
        synchronized(lock) {
            if (this.source == source) return
            val previous = this.source
            this.source = source
            val current = era ?: return                 // no live session: the source is all we owe
            val listener = owner ?: return
            val next = engineForSource(source, micEngine, deviceEngine)
            // One engine serving both sources (the on-device-only user): nothing to cut over, and
            // the service's own commit-at-switch keeps the two sources in separate segments exactly
            // as it did before this class existed.
            if (next === current.engine) return
            if (previous == ActiveSource.MIC) {
                // Microphone audio may go to ANY engine, and the incoming engine here is always the
                // on-device one, so carrying the tail over loses nothing and marks nothing lost.
                carried = pending.toByteArray()
            } else {
                // DEVICE audio: never carried, because the incoming engine is the cloud one. Cut it
                // into the outgoing on-device engine, which is the only engine allowed to see it.
                commit()
            }
            pending.reset()
            outgoing = current
            incoming = Era(next)
            era = incoming
        }
        // Outside the lock: close() makes the outgoing engine resolve everything it owes, and those
        // resolutions run the orderer and the service's typing.
        endEra(outgoing, SegmentOutcome.Lost(SOURCE_SWITCHED))
        incoming.engine.connect(language, incoming)
        carried?.takeIf { it.isNotEmpty() }?.let { tail ->
            synchronized(lock) {
                if (era === incoming) {
                    pending.write(tail)
                    incoming.engine.sendAudio(tail)
                }
            }
        }
    }

    /**
     * Ends one era: the child resolves what it can inside close(), and everything still mapped
     * afterwards is owed by us. [LocalWhisperEngine.close] only detaches its listener, so without
     * this sweep a swap away from the on-device engine would leave the orderer head stalled forever
     * on a seq that can no longer arrive.
     */
    private fun endEra(ending: Era, outcome: SegmentOutcome) {
        ending.live = false
        ending.engine.close()
        val orphans = synchronized(lock) {
            val all = ArrayList(ending.cuts.values)
            ending.cuts.clear()
            ending.submitting = null
            all
        }
        orphans.forEach { it.resolve(outcome) }
    }

    override fun close() {
        val ending: Era?
        val listener: TranscriptionEngine.Listener?
        synchronized(lock) {
            ending = era
            listener = owner
            era = null
            pending.reset()
        }
        ending?.let { endEra(it, SegmentOutcome.Lost(CLOSED)) }
        synchronized(lock) {
            owner = null
            opened = false
        }
        listener?.onClosed()
    }

    /** Only the live era can have work outstanding: every earlier one was closed and swept. */
    override fun awaitIdle(timeoutMs: Long): Boolean =
        synchronized(lock) { era }?.engine?.awaitIdle(timeoutMs) ?: true

    // Lifecycle goes to micEngine ONLY — see the class doc: deviceEngine is reachable from it, and
    // a second shutdown() would hit an already-terminated executor.
    override fun prewarm() = micEngine.prewarm()

    override fun releaseContext() = micEngine.releaseContext()

    override fun shutdown() {
        val ending = synchronized(lock) { era.also { era = null } }
        ending?.let { endEra(it, SegmentOutcome.Lost(CLOSED)) }
        micEngine.shutdown()
    }

    private companion object {
        const val TAG = "WE-DIAG"

        /** commit() cut nothing, so no seq was allocated and nothing is owed a resolution. */
        const val NO_SEGMENT = -1L

        /** A cut whose child seq is not known yet (commit() has not returned). */
        const val UNMAPPED = -1L

        const val SOURCE_SWITCHED = "audio source switched"
        const val SESSION_RESTARTED = "session restarted"
        const val CLOSED = "cancelled"
    }
}
