package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.SpeechEvidence
import com.whispereverywhere.transcription.TranscriptionEngine

/**
 * THE TEE (spec §3, §4.1) — the `FallbackTranscriptionEngine` decorator shape
 * (transcription/cloud/FallbackTranscriptionEngine.kt:195-210) with the previewer in the mirror's
 * seat. [local] is the session's whisper engine (CPU or NPU) and keeps EVERYTHING it has today:
 * every seq it allocates, its EmptyExpected floor, its buffer, its resolutions — the typed
 * transcript cannot depend on the previewer.
 *
 *  - [sendAudio]: `local` FIRST (the CapSeamPinTest order), then the preview's non-blocking
 *    offer. The recorder hands a fresh copy per chunk (StreamingAudioRecorder.kt:96
 *    `buffer.copyOf(read)`), so holding the reference in the preview's queue is safe.
 *  - every commit form forwards to `local` and, ONLY for a real seq (`>= 0`), freezes the preview
 *    under that seq — the previewer never allocates a seq and never resolves one.
 *  - [Relay] forwards `onOpen` / `onSegmentResolved` / `onError` / `onClosed` untouched and
 *    SWALLOWS `local`'s `onDelta` (its native burst and the terminal blank at
 *    LocalWhisperEngine.kt:600 would blank the strip under the previewer's words). After a
 *    resolution is forwarded, the composer drops that seq's frozen prefix and the shrunken strip
 *    is emitted — resolution first, so delivery timing is byte-identical to today.
 *  - the composer is the ONE `onDelta` source; its three writers (preview executor: partial,
 *    freeze; local executor: resolve) are serialised under [composerLock].
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

    private fun emit(compose: () -> String) {
        val l = listener ?: return
        val text = synchronized(composerLock) { compose() }
        l.onDelta(text)
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
