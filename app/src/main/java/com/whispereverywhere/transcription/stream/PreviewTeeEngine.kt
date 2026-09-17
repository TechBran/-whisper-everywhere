package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.SpeechEvidence
import com.whispereverywhere.transcription.TranscriptionEngine

/**
 * THE TEE (spec §3, §4.1) — the `FallbackTranscriptionEngine` decorator shape
 * (transcription/cloud/FallbackTranscriptionEngine.kt, whose mirrored `sendAudio` is :204-209)
 * with the previewer in the mirror's seat. [local] is the session's whisper engine (CPU or NPU)
 * and keeps EVERYTHING it has today: every seq it allocates, its EmptyExpected floor, its buffer,
 * its resolutions — the typed transcript cannot depend on the previewer.
 *
 *  - [sendAudio]: `local` FIRST (the CapSeamPinTest order), then the preview's non-blocking
 *    offer. The recorder hands a fresh copy per chunk (util/StreamingAudioRecorder.kt:97
 *    `buffer.copyOf(read)`), so holding the reference in the preview's queue is safe.
 *  - every commit form forwards to `local` and, ONLY for a real seq (`>= 0`), freezes the preview
 *    under that seq — the previewer never allocates a seq and never resolves one.
 *  - [Relay] forwards `onOpen` / `onSegmentResolved` / `onError` / `onClosed` untouched and
 *    SWALLOWS `local`'s `onDelta` (its native burst and the terminal blank at
 *    LocalWhisperEngine.kt:600 would blank the strip under the previewer's words). After a
 *    resolution is forwarded, the composer drops that seq's frozen prefix and the shrunken strip
 *    is emitted — resolution first, so delivery timing is byte-identical to today.
 *  - **(4.9) PASS-THROUGH when the previewer cannot open.** 4.8.1 arms the session on the POSTED
 *    warm, so this tee can be connected over a load that then throws or a canary that fails; the
 *    concurrency reviewer put on record that such a session showed NOTHING on the strip for its
 *    whole length — the Relay swallowed whisper's deltas and no partial ever came. Closed: the
 *    previewer's `open` reports it cannot open (`LocalPreview.open`'s `onUnavailable` — the
 *    engine calls it when `open()` finds no recognizer, which is exactly the state a failed load
 *    or canary leaves behind on its FIFO), the Relay switches to pass-through and forwards
 *    whisper's `onDelta` for the rest of the session — the ordinary pre-4.8.1 in-flight strip —
 *    and the composer is silenced, so the two never paint the same strip. ONE-WAY within a
 *    session: nothing switches it back to swallowing mid-session; [connect] resets it for the
 *    next session, which opens on its own merits.
 *  - the composer is the ONE `onDelta` source (outside pass-through); its three writers (preview
 *    executor: partial, freeze; local executor: resolve) are serialised under [composerLock] —
 *    and so is the DELIVERY, which is the part that makes the strip's order match the composer's.
 *    See [emit]. Pass-through deliveries take the same lock, so a late composition and a whisper
 *    delta can never be inside the owner's `onDelta` at once.
 *  - lifecycle (`prewarm` / `shutdown` / `awaitIdle` / `releaseContext`) is `local`'s alone: the
 *    service OWNS the resident previewer and releases it on trim/destroy; the tee borrows it.
 */
class PreviewTeeEngine(
    private val preview: LocalPreview,
    private val local: TranscriptionEngine,
    private val composer: PreviewComposer = PreviewComposer(),
) : TranscriptionEngine {

    @Volatile private var listener: TranscriptionEngine.Listener? = null
    private val composerLock = Any()

    /**
     * THE ONE-WAY SWITCH (4.9): false while the previewer owns the strip, true from the moment it
     * reported it cannot open for this session until [connect] starts the next one. Written on
     * the previewer's executor (from `open`'s `onUnavailable`), read on local's executor
     * (whisper's deltas) and on the previewer's (the composer's), hence `@Volatile`.
     */
    @Volatile private var passThrough = false

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        this.listener = listener
        passThrough = false
        synchronized(composerLock) { composer.reset() }
        preview.open(
            onPartial = { partial -> emit { composer.onPartial(partial) } },
            onUnavailable = { passThrough = true },
        )
        local.connect(language, Relay(listener))
    }

    /** AUDIO CAPTURE THREAD, every ~32 ms: two appends and return. */
    override fun sendAudio(pcm: ByteArray) {
        local.sendAudio(pcm)
        preview.sendAudio(pcm)
    }

    override fun commit(): Long = commit(SpeechEvidence.UNKNOWN)

    override fun commit(evidence: SpeechEvidence): Long = freezeAfter(local.commit(evidence), retainMs = 0L)

    override fun commitRetainingTailMs(retainMs: Long): Long = commitRetainingTailMs(retainMs, SpeechEvidence.UNKNOWN)

    override fun commitRetainingTailMs(retainMs: Long, evidence: SpeechEvidence): Long =
        freezeAfter(local.commitRetainingTailMs(retainMs, evidence), retainMs)

    private fun freezeAfter(seq: Long, retainMs: Long): Long {
        if (seq >= 0L) preview.commit(seq, retainMs) { s, text -> emit { composer.freeze(s, text) } }
        return seq
    }

    override fun close() {
        preview.close()
        local.close()
        listener = null
    }

    override fun prewarm() = local.prewarm()
    override fun shutdown() = local.shutdown()
    override fun awaitIdle(timeoutMs: Long): Boolean = local.awaitIdle(timeoutMs)
    override fun releaseContext() = local.releaseContext()

    /**
     * The ONE `onDelta` source. The composition AND its delivery happen under [composerLock]
     * (review B2): composing under the lock and delivering outside it would order the `TreeMap`
     * and not the deliveries, so an interleaved `partial` → "one two three" and `resolve(0)` →
     * "two three" could reach the owner inverted and leave the strip repainting a prefix whisper
     * has already typed into the document — the word on screen twice until the next partial
     * (≤ 320 ms) or the next resolution.
     *
     * Deadlock surface: none. No path takes a second lock while holding [composerLock], and
     * nothing holds another monitor on the way in — `LocalWhisperEngine.resolve` calls
     * `onSegmentResolved` outside its `bufferLock` (LocalWhisperEngine.kt:595-603), `onFrozen`
     * runs on the previewer's executor holding nothing (StreamingPreviewEngine.kt:166-205), and
     * the service's `onDelta` reads two `@Volatile` gates and `launch`es
     * (FloatingBubbleService.kt:3070-3084). A null listener (after [close]) short-circuits before
     * the lock, so a late partial never even composes.
     *
     * (4.9) In pass-through the composer is SILENT: a blank freeze for a stream that never existed
     * (`onFrozen(seq, "")`, the cold engine's answer) and the composed strip after a resolution
     * would otherwise blank whisper's own in-flight words between his deltas. Whisper is the one
     * source then, through [Relay].
     */
    private fun emit(compose: () -> String) {
        if (passThrough) return
        val l = listener ?: return
        synchronized(composerLock) { l.onDelta(compose()) }
    }

    private inner class Relay(private val owner: TranscriptionEngine.Listener) : TranscriptionEngine.Listener {
        override fun onOpen() = owner.onOpen()

        /**
         * Swallowed — the composer is the one delta source — EXCEPT in pass-through (4.9), where
         * whisper's deltas are the strip. Delivered under [composerLock] for the same reason the
         * composer's are: the owner's `onDelta` must never be entered by two emitters at once.
         */
        override fun onDelta(text: String) {
            if (!passThrough) return
            synchronized(composerLock) { owner.onDelta(text) }
        }

        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            owner.onSegmentResolved(seq, outcome)
            emit { composer.resolve(seq) }
        }
        override fun onError(message: String) = owner.onError(message)
        override fun onClosed() = owner.onClosed()
    }
}
