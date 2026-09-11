package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.SpeechEvidence
import com.whispereverywhere.transcription.TranscriptionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tee (spec §3, §4.1): `local` keeps every commit, every seq and every resolution exactly
 * as today; the previewer only paints. Pinned here: the sendAudio order (local FIRST — the
 * CapSeamPinTest order), the freeze under LOCAL's seq, the swallowed local deltas, the
 * pass-through of resolutions BEFORE the recomposed delta, and the lifecycle delegation.
 */
class PreviewTeeEngineTest {

    private val order = mutableListOf<String>()

    private inner class FakeLocal : TranscriptionEngine {
        var listener: TranscriptionEngine.Listener? = null
        var language: String? = "unset"
        val audio = mutableListOf<ByteArray>()
        val commits = mutableListOf<Pair<Long, SpeechEvidence>>()
        val retains = mutableListOf<Long>()
        var nextSeq = 0L
        var refuse = false
        var closed = false
        var shut = false
        var prewarmed = false
        var idleCalls = 0
        var releasedCtx = false
        override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
            this.language = language
            this.listener = listener
            order += "local.connect"
            listener.onOpen()
        }
        override fun sendAudio(pcm: ByteArray) { audio += pcm; order += "local" }
        override fun commit(): Long = commit(SpeechEvidence.UNKNOWN)
        override fun commit(evidence: SpeechEvidence): Long {
            if (refuse) return -1L
            val seq = nextSeq++
            commits += seq to evidence
            return seq
        }
        override fun commitRetainingTailMs(retainMs: Long): Long = commitRetainingTailMs(retainMs, SpeechEvidence.UNKNOWN)
        override fun commitRetainingTailMs(retainMs: Long, evidence: SpeechEvidence): Long {
            retains += retainMs
            return commit(evidence)
        }
        override fun close() { closed = true; order += "local.close"; listener?.onClosed(); listener = null }
        override fun prewarm() { prewarmed = true }
        override fun shutdown() { shut = true }
        override fun awaitIdle(timeoutMs: Long): Boolean { idleCalls++; return true }
        override fun releaseContext() { releasedCtx = true }
        fun resolve(seq: Long, outcome: SegmentOutcome) = listener!!.onSegmentResolved(seq, outcome)
        fun delta(text: String) = listener!!.onDelta(text)
    }

    private inner class FakePreview : LocalPreview {
        var onPartial: ((String) -> Unit)? = null
        val audio = mutableListOf<ByteArray>()
        val commits = mutableListOf<Pair<Long, Long>>()
        var frozenText = "frozen"
        var closed = false
        override fun open(onPartial: (String) -> Unit) { this.onPartial = onPartial; order += "preview.open" }
        override fun sendAudio(pcm: ByteArray) { audio += pcm; order += "preview" }
        override fun commit(seq: Long, retainMs: Long, onFrozen: (Long, String) -> Unit) {
            commits += seq to retainMs
            onFrozen(seq, frozenText)
        }
        override fun close() { closed = true; order += "preview.close" }
        fun partial(text: String) = onPartial!!.invoke(text)
    }

    private class Owner : TranscriptionEngine.Listener {
        val events = mutableListOf<String>()
        val deltas = mutableListOf<String>()
        val resolved = mutableListOf<Pair<Long, SegmentOutcome>>()
        var opened = 0
        var closedCalls = 0
        override fun onOpen() { opened++ }
        override fun onDelta(text: String) { deltas += text; events += "delta:$text" }
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) { resolved += seq to outcome; events += "resolved:$seq" }
        override fun onError(message: String) { events += "error" }
        override fun onClosed() { closedCalls++ }
    }

    private val local = FakeLocal()
    private val preview = FakePreview()
    private val owner = Owner()
    private val tee = PreviewTeeEngine(preview, local)
    private val pcm = byteArrayOf(1, 0, 2, 0)

    private fun connected(): PreviewTeeEngine { tee.connect("en", owner); return tee }

    @Test fun connectOpensThePreviewThenLocalWithTheSameLanguageAndTheOwnerHearsOnOpen() {
        connected()
        assertEquals(listOf("preview.open", "local.connect"), order)
        assertEquals("en", local.language)
        assertEquals(1, owner.opened)
    }

    @Test fun audioGoesToLocalFirstThenThePreview() {
        connected()
        tee.sendAudio(pcm)
        tee.sendAudio(pcm)
        assertEquals(listOf("preview.open", "local.connect", "local", "preview", "local", "preview"), order)
        assertEquals(2, local.audio.size)
        assertEquals(2, preview.audio.size)
    }

    @Test fun aCommitForwardsTheEvidenceAndFreezesThePreviewUnderLocalsSeq() {
        connected()
        val ev = SpeechEvidence.of(1_000L)
        assertEquals(0L, tee.commit(ev))
        assertEquals(1L, tee.commit())
        assertEquals(listOf(0L to ev, 1L to SpeechEvidence.UNKNOWN), local.commits)
        assertEquals(listOf(0L to 0L, 1L to 0L), preview.commits)
    }

    @Test fun aRetainingCommitHandsTheRetainToBoth() {
        connected()
        val ev = SpeechEvidence.of(2_000L)
        assertEquals(0L, tee.commitRetainingTailMs(800L, ev))
        assertEquals(1L, tee.commitRetainingTailMs(300L))
        assertEquals(listOf(800L, 300L), local.retains)
        assertEquals(listOf(0L to ev, 1L to SpeechEvidence.UNKNOWN), local.commits)
        assertEquals(listOf(0L to 800L, 1L to 300L), preview.commits)
    }

    @Test fun aCommitThatCutNothingNeverFreezes() {
        connected()
        local.refuse = true
        assertEquals(-1L, tee.commit())
        assertEquals(-1L, tee.commitRetainingTailMs(500L))
        assertTrue(preview.commits.isEmpty())
        assertTrue("no delta for a cut that was not one", owner.deltas.isEmpty())
    }

    @Test fun localsOwnDeltasAreSwallowed() {
        // LocalWhisperEngine's native burst and its terminal onDelta("") (LocalWhisperEngine.kt:600)
        // would blank the strip under the previewer's words; only the composer speaks here.
        connected()
        local.delta(" Hello")
        local.delta("")
        assertTrue(owner.deltas.isEmpty())
    }

    @Test fun theStripIsFrozenPrefixPlusPartialAndAResolutionDropsItsPrefixAfterPassingThrough() {
        connected()
        preview.partial("hello")
        assertEquals(listOf("hello"), owner.deltas)
        preview.frozenText = "hello there"
        tee.commit()
        assertEquals("hello there", owner.deltas.last())
        preview.partial("how")
        assertEquals("hello there how", owner.deltas.last())
        local.resolve(0L, SegmentOutcome.Text("Hello there."))
        // The resolution reaches the owner FIRST (delivery timing byte-identical), THEN the strip shrinks.
        assertEquals(listOf("resolved:0", "delta:how"), owner.events.takeLast(2))
        assertEquals(listOf(0L to SegmentOutcome.Text("Hello there.")), owner.resolved)
        preview.frozenText = "how are you"
        tee.commit()
        assertEquals("how are you", owner.deltas.last())
        local.resolve(1L, SegmentOutcome.EmptyExpected)
        assertEquals("an EmptyExpected drops its frozen prefix too — whisper's verdict is the truth", "", owner.deltas.last())
    }

    @Test fun everySeqReachesTheOwnerExactlyOnceInOrder() {
        connected()
        tee.commit(); tee.commit(); tee.commit()
        local.resolve(0L, SegmentOutcome.Text("a"))
        local.resolve(1L, SegmentOutcome.EmptyExpected)
        local.resolve(2L, SegmentOutcome.Text("c"))
        assertEquals(listOf(0L, 1L, 2L), owner.resolved.map { it.first })
    }

    @Test fun closeClosesThePreviewThenLocalAndTheOwnerHearsOnClosedOnce() {
        connected()
        tee.close()
        assertEquals(listOf("preview.close", "local.close"), order.takeLast(2))
        assertTrue(preview.closed)
        assertTrue(local.closed)
        assertEquals(1, owner.closedCalls)
        preview.partial("late")
        assertTrue("a partial after close reaches nobody", owner.deltas.isEmpty())
    }

    @Test fun lifecycleDelegatesToLocalAndNeverTouchesThePreviewsRecognizer() {
        // The service OWNS the resident previewer (release on trim/destroy, Task 7); the tee only
        // borrows it. prewarm/shutdown/awaitIdle/releaseContext are local's alone.
        tee.prewarm()
        assertTrue(local.prewarmed)
        assertTrue(tee.awaitIdle(1_000L))
        assertEquals(1, local.idleCalls)
        tee.releaseContext()
        assertTrue(local.releasedCtx)
        tee.shutdown()
        assertTrue(local.shut)
        assertFalse(preview.closed)
    }
}
