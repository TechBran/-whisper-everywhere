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
 *  - the composer is the ONE `onDelta` source; its three writers (preview executor: partial,
 *    freeze; local executor: resolve) are serialised under [composerLock] — and so is the
 *    DELIVERY, which is the part that makes the strip's order match the composer's. See [emit].
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

    override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
        this.listener = listener
        synchronized(composerLock) { composer.reset() }
        preview.open { partial -> emit { composer.onPartial(partial) } }
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
     */
    private fun emit(compose: () -> String) {
        val l = listener ?: return
        synchronized(composerLock) { l.onDelta(compose()) }
    }

    private inner class Relay(private val owner: TranscriptionEngine.Listener) : TranscriptionEngine.Listener {
        override fun onOpen() = owner.onOpen()
        override fun onDelta(text: String) = Unit   // swallowed: the composer is the one delta source
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            owner.onSegmentResolved(seq, outcome)
            emit { composer.resolve(seq) }
        }
        override fun onError(message: String) = owner.onError(message)
        override fun onClosed() = owner.onClosed()
    }
}
