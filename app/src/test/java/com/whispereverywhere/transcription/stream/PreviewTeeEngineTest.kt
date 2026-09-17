package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.SpeechEvidence
import com.whispereverywhere.transcription.TranscriptionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The tee (spec §3, §4.1): `local` keeps every commit, every seq and every resolution exactly
 * as today; the previewer only paints. Pinned here: the sendAudio order (local FIRST — the
 * CapSeamPinTest order), the freeze under LOCAL's seq, the swallowed local deltas, the
 * pass-through of resolutions BEFORE the recomposed delta, the lifecycle delegation, and (4.9)
 * what happens when the previewer reports it cannot open: the owner is TOLD through the tee's
 * `onUnavailable` hook, once, with the tee (the signal the service clears its session flag on —
 * the part that closes the blank on the NPU tier, where whisper has no deltas), and whisper's
 * own deltas pass through from that moment on, one-way, never both sources on one strip.
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
        /** The real onFrozen lands the pack's pad + a drain + a decode after the cut; hold it to model that. */
        var deferFreeze = false
        private var held: (() -> Unit)? = null
        /** (4.9) The real engine's `open()` finding no recognizer: report it at open. */
        var unavailableAtOpen = false
        override fun open(onPartial: (String) -> Unit, onUnavailable: () -> Unit) {
            this.onPartial = onPartial
            order += "preview.open"
            if (unavailableAtOpen) onUnavailable()
        }
        override fun sendAudio(pcm: ByteArray) { audio += pcm; order += "preview" }
        override fun commit(seq: Long, retainMs: Long, onFrozen: (Long, String) -> Unit) {
            commits += seq to retainMs
            val text = frozenText
            if (deferFreeze) held = { onFrozen(seq, text) } else onFrozen(seq, text)
        }
        override fun close() { closed = true; order += "preview.close" }
        fun partial(text: String) = onPartial!!.invoke(text)
        fun fireHeldFreeze() { held!!.invoke(); held = null }
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
    /** (4.9) Every `onUnavailable` report this test's tee made, in order — one per failed session. */
    private val unavailable = mutableListOf<PreviewTeeEngine>()
    private val tee = PreviewTeeEngine(preview, local, onUnavailable = { unavailable += it })
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
        // (4.9) ...and over a HEALTHY previewer that stays so for the whole session: the composer
        // still owns the strip after whisper has spoken, and whisper's later deltas are still
        // swallowed — the pass-through switch never flipped.
        preview.partial("hello")
        assertEquals(listOf("hello"), owner.deltas)
        local.delta(" Hello there")
        assertEquals(listOf("hello"), owner.deltas)
        assertTrue("and the owner was never told the previewer is unavailable", unavailable.isEmpty())
        assertFalse(tee.previewUnavailable)
    }

    /**
     * (4.9) THE TEE OVER A PREVIEWER THAT CANNOT OPEN — the trade 4.8.1 recorded, closed. The
     * previewer reports at `open` that it has nothing to serve this session with (the real
     * engine's `open()` finding no recognizer after a warm that threw or a canary that failed);
     * from that moment the OWNER IS TOLD — `onUnavailable` fires once, with this tee, after the
     * switch is already made — whisper's own deltas go through to the owner and the composer is
     * silent — never both. A freeze and a resolution still pass through `local`'s seq exactly as
     * before, and neither paints: the strip is whisper's alone. One-way within the session; a new
     * session over a previewer that CAN open starts swallowing again and reports nothing.
     *
     * The hook is the load-bearing half for the service: its strip rules key on a per-session
     * flag, not on which engine emits deltas, and on the NPU tier `local` emits none at all — so
     * the forwarded deltas alone would leave that fleet's strip blank. The service clears the flag
     * on this hook (FloatingBubbleService `onPreviewUnavailable`, pinned in
     * LocalPreviewWiringPinTest).
     */
    @Test fun aTeeOverAPreviewerThatCannotOpenForwardsWhispersDeltasFromThenOnAndNeverBoth() {
        preview.unavailableAtOpen = true
        connected()
        assertEquals(listOf("preview.open", "local.connect"), order)
        assertEquals("local connected and the owner heard onOpen, untouched", 1, owner.opened)
        assertEquals("the owner was told ONCE, with this tee", listOf(tee), unavailable)
        assertTrue("and the switch was already made when it was told", tee.previewUnavailable)
        // Whisper's in-flight strip — the pre-4.8.1 shape — reaches the owner.
        local.delta(" Hello")
        local.delta(" Hello there")
        assertEquals(listOf(" Hello", " Hello there"), owner.deltas)
        // The composer is SILENT: a stray partial, a blank freeze under a commit and the composed
        // strip after a resolution all paint nothing — never both sources on one strip.
        preview.partial("hello")
        assertEquals(listOf(" Hello", " Hello there"), owner.deltas)
        preview.frozenText = ""
        assertEquals(0L, tee.commit())
        assertEquals("the freeze still ran under local's seq", listOf(0L to 0L), preview.commits)
        assertEquals("...and painted nothing", listOf(" Hello", " Hello there"), owner.deltas)
        local.resolve(0L, SegmentOutcome.Text("Hello there."))
        assertEquals(listOf(0L to SegmentOutcome.Text("Hello there.")), owner.resolved)
        assertEquals("the resolution passed through and the composer painted nothing after it", "resolved:0", owner.events.last())
        // Whisper's terminal blank after the resolution is whisper's, and it goes through too.
        local.delta("")
        assertEquals(listOf(" Hello", " Hello there", ""), owner.deltas)
        // One-way: nothing the previewer does later can take the strip back mid-session.
        preview.partial("late words")
        assertEquals(listOf(" Hello", " Hello there", ""), owner.deltas)

        // A NEW session over a previewer that can open is the ordinary tee again.
        tee.close()
        assertEquals(1, owner.closedCalls)
        preview.unavailableAtOpen = false
        val second = Owner()
        tee.connect("en", second)
        assertFalse("the switch is per session", tee.previewUnavailable)
        local.delta(" Second")
        assertTrue("swallowed again — the switch is per session", second.deltas.isEmpty())
        preview.partial("second")
        assertEquals(listOf("second"), second.deltas)
        assertEquals("no second report: the previewer opened this time", listOf(tee), unavailable)

        // And a THIRD session over a previewer that fails again reports again — once per session.
        tee.close()
        preview.unavailableAtOpen = true
        tee.connect("en", Owner())
        assertEquals(listOf(tee, tee), unavailable)
    }

    /**
     * (4.9) The same fact over the REAL engine on a held FIFO, in the exact production order the
     * 4.8.1 gate creates: a warm is posted, the tee connects behind it (open posts behind the warm),
     * chunks arrive, the load THROWS on the FIFO, `open()` finds no recognizer and reports it, and
     * whisper's deltas flow from that moment. Before the load lands the strip is blank either way
     * (whisper's burst is swallowed — the composer still owns the strip until the previewer says
     * otherwise); from the failure onward it is whisper's.
     */
    @Test fun aTeeWhosePreviewerFailsItsWarmForwardsWhispersDeltasFromTheFailureOnward() {
        var now = 0L
        val exec = ManualExecutorService()
        val previewer = StreamingPreviewEngine(
            factory = ScriptedFactory(null, throwAtLoad = true), canaryClip = { FloatArray(40_960) }, executor = exec,
            clock = { now }, nanoClock = { 0L }, log = {}, enterExecutorThread = {},
        )
        previewer.warm(File("unused"), StreamingPackCatalog.EN)      // posted — the wrap site's shape
        assertFalse("no verdict yet: the 4.8.1 gate arms over this load", previewer.isDisabled(StreamingPackCatalog.EN))
        val told = mutableListOf<PreviewTeeEngine>()
        val cold = PreviewTeeEngine(previewer, local, onUnavailable = { told += it })
        cold.connect("en", owner)                                    // open() posts BEHIND the warm
        assertEquals(1, owner.opened)
        repeat(5) { now += 32; cold.sendAudio(ByteArray(1024)) }
        local.delta(" Early")                                        // before the load lands: swallowed, as on a warm session
        assertTrue(owner.deltas.isEmpty())
        assertTrue("nothing reported while the load is merely in flight", told.isEmpty())

        exec.runAll()                                                // the load THROWS, then open finds no recognizer
        assertTrue("the pack is off for the process", previewer.isDisabled(StreamingPackCatalog.EN))
        assertTrue("nothing painted by the failure itself", owner.deltas.isEmpty())
        assertEquals("the owner was told once, with the tee, on the FIFO after the failure", listOf(cold), told)
        assertTrue(cold.previewUnavailable)
        local.delta(" Hello")                                        // whisper's in-flight strip, from here on
        assertEquals(listOf(" Hello"), owner.deltas)
        repeat(5) { now += 32; cold.sendAudio(ByteArray(1024)); exec.runAll() }
        assertEquals("audio after the verdict is refused at the previewer's door and paints nothing", listOf(" Hello"), owner.deltas)
        // The commit path: local's seq, the cold engine's blank freeze — which paints NOTHING now —
        // and whisper's resolution through untouched.
        assertEquals(0L, cold.commit())
        exec.runAll()
        assertEquals(listOf(" Hello"), owner.deltas)
        local.resolve(0L, SegmentOutcome.Text("Hello."))
        assertEquals(listOf(0L to SegmentOutcome.Text("Hello.")), owner.resolved)
        assertEquals("resolved:0", owner.events.last())
        local.delta("")
        assertEquals(listOf(" Hello", ""), owner.deltas)
        cold.close()
        exec.runAll()
        assertEquals(1, owner.closedCalls)
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

    @Test fun aFreezeThatLandsAfterItsOwnResolutionNeverReachesTheStrip() {
        // review B1 — the production order on a SKIPPED segment, not a race: local.commit on
        // evidence under EndpointerTuning.MIN_SPEECH_EVIDENCE_MS (192 ms, EndpointerTuning.kt:197)
        // resolves EmptyExpected off its executor within ~1 ms (LocalWhisperEngine.kt:375-382),
        // while onFrozen returns only after the pack's pad + drain + decode
        // (StreamingPreviewEngine.kt:166-205). The previewer's Zipformer has no VAD, so its text
        // for that segment can be real words — words whisper has declared it will never type.
        connected()
        preview.deferFreeze = true
        preview.frozenText = "yeah"
        preview.partial("yeah")
        assertEquals("yeah", owner.deltas.last())
        assertEquals(0L, tee.commit(SpeechEvidence.of(100L)))
        local.resolve(0L, SegmentOutcome.EmptyExpected)
        assertEquals("nothing is frozen yet — the live partial is all the strip has", "yeah", owner.deltas.last())
        preview.fireHeldFreeze()
        assertEquals("the late freeze must paint nothing", "", owner.deltas.last())
        // and it is not merely hidden: the next segment's partial stands alone on the strip.
        preview.partial("hello there")
        assertEquals("hello there", owner.deltas.last())
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

    @Test fun twoEmittersAreNeverInsideTheOwnersOnDeltaAtOnce() {
        // review B2 — composing under composerLock but DELIVERING outside it orders the TreeMap
        // and not the deliveries. Two threads emit in production: the preview executor (partial,
        // freeze) and local's executor (resolve, LocalWhisperEngine.kt:595-603). Interleaved, the
        // owner can receive the stale, LONGER composition last and the strip repaints a prefix
        // whisper has already typed into the document — the word on screen twice until the next
        // partial. Here: A parks inside onDelta; B must not deliver until A returns.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val depth = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val parkNext = AtomicBoolean(false)
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val gate = object : TranscriptionEngine.Listener {
            override fun onOpen() = Unit
            override fun onDelta(text: String) {
                val now = depth.incrementAndGet()
                peak.getAndUpdate { maxOf(it, now) }
                seen += text
                if (parkNext.compareAndSet(true, false)) {
                    entered.countDown()
                    release.await(5L, TimeUnit.SECONDS)
                }
                depth.decrementAndGet()
            }
            override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) = Unit
            override fun onError(message: String) = Unit
            override fun onClosed() = Unit
        }
        tee.connect("en", gate)
        preview.frozenText = "one two"
        tee.commit()                                   // seq 0 frozen: the strip is "one two"
        assertEquals(listOf("one two"), seen.toList())

        parkNext.set(true)
        val a = Thread({ preview.partial("three") }, "emitter-A-partial")
        a.start()
        assertTrue("A reached the owner", entered.await(5L, TimeUnit.SECONDS))
        val b = Thread({ local.resolve(0L, SegmentOutcome.Text("One two.")) }, "emitter-B-resolve")
        b.start()
        Thread.sleep(250L)                             // ample time for B to violate if it can
        assertEquals("B delivered while A was still inside onDelta", listOf("one two", "one two three"), seen.toList())

        release.countDown()
        a.join(5_000L)
        b.join(5_000L)
        assertFalse(a.isAlive)
        assertFalse(b.isAlive)
        assertEquals(1, peak.get())
        // B's delivery lands only after A returns, and it is the shrunken strip — never the other way.
        assertEquals(listOf("one two", "one two three", "three"), seen.toList())
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

    /**
     * (4.8.1) THE TEE OVER A PREVIEWER THAT IS STILL LOADING — the fresh-install first session.
     *
     * The gate arms on the POSTED warm now (FloatingBubbleService.startRecording), so this tee is
     * built and connected while the load sits on the previewer's executor. Pinned over the REAL
     * engine on a held FIFO executor rather than [FakePreview], because the claim is about the
     * engine's own entry points: `local` receives every connect, chunk and commit in order and
     * the owner hears `onOpen`/`onSegmentResolved` exactly as on a warm session; the strip stays
     * blank (no `onDelta` at all) until the previewer's first partial; and once the load lands
     * behind `open()` the words flow from the next chunk. Whisper's own deltas stay swallowed
     * either way — the composer is the one delta source — which is the one thing a still-loading
     * previewer costs: a blank strip, never a wrong one.
     */
    @Test fun aTeeWhosePreviewerIsStillLoadingForwardsLocalUntouchedAndTheStripStaysBlankUntilTheFirstPartial() {
        var now = 0L
        val rec = ScriptedRecognizer(listOf("HELLO", "HELLO THERE"), canaryText = "ONE TWO THREE FOUR FIVE")
        val exec = ManualExecutorService()
        val previewer = StreamingPreviewEngine(
            factory = ScriptedFactory(rec), canaryClip = { FloatArray(40_960) }, executor = exec,
            clock = { now }, nanoClock = { 0L }, log = {}, enterExecutorThread = {},
        )
        previewer.warm(File("unused"), StreamingPackCatalog.EN)      // posted — the boot prewarm's / wrap site's shape
        assertFalse("the load is in flight", previewer.isWarmFor(StreamingPackCatalog.EN))
        assertFalse("and the gate's term says arm", previewer.isDisabled(StreamingPackCatalog.EN))
        val cold = PreviewTeeEngine(previewer, local)
        val chunk = ByteArray(1024)

        cold.connect("en", owner)                                    // open() posts behind the warm; local connects NOW
        assertEquals("local connected at once — the previewer's open only posted", listOf("local.connect"), order)
        assertEquals("and the owner heard onOpen from local, untouched", 1, owner.opened)
        repeat(20) { now += 32; cold.sendAudio(chunk) }
        assertEquals("every chunk reached local, in order", 20, local.audio.size)
        assertEquals(listOf("local.connect") + List(20) { "local" }, order)
        assertTrue("no stream exists yet — nothing has run", rec.streams.isEmpty())
        assertTrue("and the strip is BLANK, not wrong: no delta of any kind", owner.deltas.isEmpty())
        local.delta(" Hello")                                        // whisper's own burst
        assertTrue("swallowed, exactly as on a warm session", owner.deltas.isEmpty())

        exec.runAll()                                                // the warm lands, THEN open, THEN the drains
        assertTrue(previewer.isWarmFor(StreamingPackCatalog.EN))
        assertEquals("canary + the session's stream", 2, rec.streams.size)
        assertEquals("the twenty chunks queued during the load were shed at open", 0, rec.streams[1].fed.size)
        assertTrue(owner.deltas.isEmpty())

        repeat(15) { now += 32; cold.sendAudio(chunk); exec.runAll() }   // 7,680 samples: the first decode
        assertEquals("words from the first chunk after the load landed", listOf("hello"), owner.deltas)
        assertEquals(35, local.audio.size)

        // And the commit path is the warm session's: local's seq, the freeze under it, whisper's
        // resolution through first and the prefix dropped after it.
        assertEquals(0L, cold.commit())
        assertEquals(listOf(0L to SpeechEvidence.UNKNOWN), local.commits)
        exec.runAll()
        assertEquals("hello there", owner.deltas.last())
        local.resolve(0L, SegmentOutcome.Text("Hello there."))
        assertEquals(listOf("resolved:0", "delta:"), owner.events.takeLast(2))
        cold.close()
        exec.runAll()
        assertTrue(local.closed)
        assertEquals(1, owner.closedCalls)
    }
}
