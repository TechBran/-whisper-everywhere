package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.SameThreadExecutorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * The previewer's loop over the scripted recognizer, with the measurements as fixtures: the
 * canary's four partials (rung 3 §5.3 — `ONE`, `ONE TWO THREE`, `… FOUR`, `… FIVE`, the last
 * from the pad), the T=45 / 320 ms decode cadence, the leading-space tokens, the 500 ms pad.
 * Executor: SameThreadExecutorService (LocalWhisperEngineTest's), so every posted task runs
 * inline and the assertions read a settled engine.
 */
class StreamingPreviewEngineTest {

    private class ManualExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private companion object {
        const val CANARY = "ONE TWO THREE FOUR FIVE"
        val CANARY_PARTIALS = listOf("ONE", "ONE TWO THREE", "ONE TWO THREE FOUR", "ONE TWO THREE FOUR FIVE")
    }

    private var now = 0L
    private val logs = mutableListOf<String>()
    private val emitted = mutableListOf<String>()
    private val dir = File("unused")
    private val pack = StreamingPackCatalog.EN

    private fun engine(
        rec: PreviewRecognizer?,
        executor: ExecutorService = SameThreadExecutorService(),
        clip: FloatArray? = FloatArray(40_960),
        factory: PreviewRecognizerFactory = ScriptedFactory(rec),
        capacity: Int = StreamingPreviewTuning.QUEUE_CAPACITY,
        onLoadFailure: (StreamingPack) -> Unit = {},
        onDisabled: (StreamingPack) -> Unit = {},
    ) = StreamingPreviewEngine(
        factory = factory, canaryClip = { clip }, onLoadFailure = onLoadFailure,
        onDisabled = onDisabled, executor = executor,
        clock = { now }, nanoClock = { 0L }, queueCapacity = capacity, log = { logs += it }, enterExecutorThread = {},
    )

    private fun warmOpen(rec: PreviewRecognizer, executor: ExecutorService = SameThreadExecutorService(), capacity: Int = StreamingPreviewTuning.QUEUE_CAPACITY): StreamingPreviewEngine {
        val e = engine(rec, executor, capacity = capacity)
        e.warm(dir, pack)
        e.open { emitted += it }
        return e
    }

    /** 32 ms of PCM per chunk, the clock advanced BEFORE the send (the frame's own instant). */
    private fun feedMs(e: StreamingPreviewEngine, ms: Int) {
        repeat(ms / 32) { now += 32; e.sendAudio(ByteArray(1024)) }
    }

    // ------------------------------------------------------------- warm + canary

    @Test fun warmLoadsWithTwoThreadsRunsTheCanaryAndLogsTheOpenLine() {
        val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY)
        val factory = ScriptedFactory(rec)
        val e = engine(rec, factory = factory)
        e.warm(dir, pack)
        assertEquals(2, factory.lastThreads)
        assertTrue(e.isWarm())
        assertFalse(e.isDisabled(pack))
        assertEquals(
            "stream-open: sherpa=1.13.7 ort=1.27.1 threads=2 provider=cpu loadMs=0 canary=pass canaryMs=0 outLen=23 load=ok warm=1",
            logs.single(),
        )
        assertTrue("the canary's throwaway stream is released", rec.streams.single().released)
        assertFalse(rec.released)
        e.warm(dir, pack)
        assertEquals("warm is idempotent", 1, factory.loads)
    }

    @Test fun aFailedCanaryDisablesForTheProcessAndReleasesTheRecognizer() {
        val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = "")
        val factory = ScriptedFactory(rec)
        val e = engine(rec, factory = factory)
        e.warm(dir, pack)
        assertTrue(e.isDisabled(pack))
        assertEquals(setOf("en"), e.disabledLanguages)
        assertFalse(e.isWarm())
        assertTrue(rec.released)
        assertTrue(logs.single().contains(" canary=fail canaryMs=0 outLen=0 load=ok warm=0"))
        e.warm(dir, pack)
        assertEquals("a disabled previewer never reloads in this process", 1, factory.loads)
    }

    @Test fun aMissingClipIsNoVerdictAndTheProcessStaysOff() {
        // The pack NAMES a clip (`EN.canary` is non-null) and the named asset would not load. That
        // is a build defect, not a sourcing gap, and it keeps switching the language off — the
        // distinction B1 turns on. `warm=0` is now on the line, so the disable is named.
        val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY)
        val disabled = mutableListOf<StreamingPack>()
        val e = engine(rec, clip = null, onDisabled = { disabled += it })
        e.warm(dir, pack)
        assertTrue(e.isDisabled(pack))
        assertTrue("the recognizer is freed, not left resident", rec.released)
        assertEquals(listOf(pack), disabled)
        assertTrue(logs.single().contains(" canary=none canaryMs=0 outLen=0 load=ok warm=0"))
    }

    @Test fun aRowWithNoCLIPSOURCEDYetARMSUnscoredRatherThanGoingOff() {
        // (4.5.0 languages T1 review r1, B1) THE CONSEQUENCE, which is what the scorer-level test
        // could not see. `canary = null` means T3 has not recorded this language's clip — it is not
        // a verdict about the model and not a defect in the build, so it must not take the branch
        // built for a Fail. It used to: `canary = null` scored NoClip, NoClip's only consumer was
        // `if (verdict is Pass) … else disable(pack)`, and so every one of the six new languages
        // loaded 169 MB, scored, released the recognizer and switched itself off for the process —
        // behind a line reading `canary=none … load=ok`, which says the load was fine.
        //
        // The trade is real and priced ([PackCanary]'s docblock): arming unscored leaves the
        // FEAT_SME silent-miscompute guard unpaid for this language until its clip lands. It is
        // affordable because the previewer is additive and never types, and the alternative is six
        // languages the owner cannot hear work on his own devices before paying for a lawyer.
        val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY)
        val factory = ScriptedFactory(rec)
        val unsourced = pack.copy(language = "xx", dirName = "xx-test", packName = "preview_xx", canary = null)
        val disabled = mutableListOf<StreamingPack>()
        // The service's own shape: no canary ⇒ no clip (FloatingBubbleService.kt:3209).
        val e = engine(rec, clip = null, factory = factory, onDisabled = { disabled += it })
        e.warm(dir, unsourced)
        assertFalse("the language is NOT disabled", e.isDisabled(unsourced))
        assertEquals("and nothing is published to the selection surfaces", emptySet<String>(), e.disabledLanguages)
        assertEquals("onDisabled is never called", emptyList<StreamingPack>(), disabled)
        assertTrue("the strip can arm for THIS pack", e.isWarmFor(unsourced))
        assertFalse("the recognizer is NOT released — it is the resident one", rec.released)
        assertTrue("no canary stream was opened at all", rec.streams.isEmpty())
        assertTrue(logs.single().contains(" canary=unscored canaryMs=0 outLen=0 load=ok warm=1"))
        // And it stays armed: a second warm is the idempotent no-op, not a reload.
        e.warm(dir, unsourced)
        assertEquals("warm is still idempotent on the pack", 1, factory.loads)
        assertTrue(e.isWarmFor(unsourced))
    }

    @Test fun ANYRowWithNoClipSourcedYetArmsRatherThanDisablingItself() {
        // **The day this case named has arrived.** It used to loop over the catalogue's rows with
        // `canary == null` — all six new languages — and its own comment said the non-vacuity line
        // was "what to relax on the day every row has a clip". 4.5.0 T3 gave the last of them one,
        // so the census flips and the CONSEQUENCE is tested on synthetic rows instead.
        //
        // The consequence still matters: `canary = null` remains a legal state (PackCanary's
        // docblock is the argument, and the brief explicitly permits a row to be left unset), and
        // an eighth language would arrive in exactly this shape — a real row with no clip. What
        // must never happen is the B1 regression, where such a row disabled itself on first warm
        // and the owner could not hear ANY language work on his own device.
        assertTrue(
            "every catalogue row now carries a clip; if one goes null again, this test still " +
                "covers it (the loop below is over copies), but say so deliberately",
            StreamingPackCatalog.packs.none { it.canary == null },
        )
        for (p in StreamingPackCatalog.packs.map { it.copy(canary = null) }) {
            logs.clear()
            val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY)
            val e = engine(rec, clip = null)
            e.warm(dir, p)
            assertFalse("${p.language} must not disable itself on first warm", e.isDisabled(p))
            assertTrue("${p.language} must arm", e.isWarmFor(p))
            assertFalse("${p.language}'s recognizer must stay resident", rec.released)
            assertTrue("${p.language}'s open line says it came up", logs.single().endsWith(" canary=unscored canaryMs=0 outLen=0 load=ok warm=1"))
        }
    }

    @Test fun aLoadFailureDisablesAndReportsTheCorruptionAgainstThePackThatFAILED() {
        val corrupt = mutableListOf<StreamingPack>()
        val other = pack.copy(language = "xx", dirName = "xx-test", packName = "preview_xx")
        val e = engine(null, factory = ScriptedFactory(null, throwAtLoad = true), onLoadFailure = { corrupt += it })
        e.warm(dir, other)
        assertTrue(e.isDisabled(other))
        assertFalse("and English, which was never asked for, is untouched", e.isDisabled(pack))
        // The pack this call was made with — not the engine's last one, and not a field some other
        // thread may have moved since. 4.4.0's hook took no argument and the service read
        // `streamingPreviewPack`, which Main writes when the SELECTION moves: a switch to B during
        // A's load marked B corrupt for A's failure, deleting a healthy marker and leaving the
        // broken pack installed.
        assertEquals(listOf(other), corrupt)
        assertTrue(logs.single().endsWith(" canary=skipped canaryMs=0 outLen=0 load=fail warm=0"))
    }

    // ------------------------------------------------------------- pack identity (T2, defect 1)

    @Test fun warmingTheSamePackTwiceLoadsOnceAndWarmingADIFFERENTPackSwapsTheModel() {
        // 4.4.0 returned early on `recognizer != null` and never looked at WHICH pack was
        // resident, so this second warm loaded nothing and left English's model decoding the new
        // language's speech — behind a gate logging preview=1 and a diag saying "warm". A
        // wrong-language strip with every honest signal green.
        val first = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY)
        val second = ScriptedRecognizer(listOf("BONJOUR"), canaryText = CANARY)
        val factory = ScriptedFactory(first, next = second)
        val fr = pack.copy(language = "fr", dirName = "fr-2023-04-14", packName = "preview_fr", modelType = "zipformer", encoderT = 39)
        val e = engine(first, factory = factory)

        e.warm(dir, pack)
        e.warm(dir, pack)
        assertEquals("the same pack twice is ONE load", 1, factory.loads)
        assertFalse("and the first recognizer is still the resident one", first.released)

        e.warm(dir, fr)
        assertEquals("a different pack loads", 2, factory.loads)
        assertEquals("in the order asked", listOf(pack, fr), factory.packs)
        assertTrue("and the old model is freed before the new one allocates", first.released)
        assertFalse(second.released)
        assertTrue(e.isWarm())

        // And the swap moved the per-pack rules with it: fr is T = 39, whose pad is the measured
        // floor rather than 500-by-coincidence — the point being that the LOADED pack decides.
        e.open { emitted += it }
        feedMs(e, 1_024)
        e.commit(0L, 0L) { _, _ -> }
        assertEquals("bonjour", emitted.last())
        assertTrue(logs.last().contains(" padMs=500 "))
    }

    // ------------------------------------------------------- per-pack verdicts (T2, defect 4)

    @Test fun aFailedEnglishCanaryDoesNotRefuseAFrenchLoadInTheSameProcess() {
        // The defect: `disabled` was one flag for the process, and the canary CLIP is English's.
        // A non-English model fed "one two three four five" matches none of the five positions —
        // which is exactly the SME signature the canary exists to catch — so the first French user
        // lost live words in EVERY language until they restarted the app, behind a sentence the
        // 4.4.0 sheet records as rendered nowhere.
        val enRec = ScriptedRecognizer(listOf("HELLO"), canaryText = "")            // English fails
        val frRec = ScriptedRecognizer(listOf("BONJOUR"), canaryText = CANARY)      // French passes
        val factory = ScriptedFactory(enRec, next = frRec)
        val fr = pack.copy(language = "fr", dirName = "fr-2023-04-14", packName = "preview_fr", modelType = "zipformer", encoderT = 39)
        val e = engine(enRec, factory = factory)

        e.warm(dir, pack)
        assertTrue(e.isDisabled(pack))
        assertFalse(e.isWarm())

        e.warm(dir, fr)
        assertEquals("French loads anyway", 2, factory.loads)
        assertFalse(e.isDisabled(fr))
        assertTrue("and it is warm — for French", e.isWarm())
        assertTrue(e.isWarmFor(fr))
        assertFalse("but never for the language whose verdict went against it", e.isWarmFor(pack))

        e.warm(dir, pack)
        assertEquals("and English stays off for the rest of the process", 2, factory.loads)
        assertEquals(setOf("en"), e.disabledLanguages)
    }

    @Test fun theCanaryIsFedTHISPacksClipAndNotOneSharedClip() {
        // The other half of the same defect: one clip for every pack is a verdict on English
        // rendered against a French model. The engine asks per pack; nothing here decides WHICH
        // clip (the pack names it), only that the pack is the one asked.
        val asked = mutableListOf<String>()
        val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY)
        val e = StreamingPreviewEngine(
            factory = ScriptedFactory(rec),
            canaryClip = { p -> p.canary?.let { asked += it.asset }; FloatArray(40_960) },
            executor = SameThreadExecutorService(), clock = { now }, nanoClock = { 0L },
            log = { logs += it }, enterExecutorThread = {},
        )
        val fr = pack.copy(language = "fr", canary = pack.canary!!.copy(asset = "canary_fr.wav"))
        e.warm(dir, fr)
        assertEquals(listOf("canary_fr.wav"), asked)
    }

    @Test fun aReleasedEngineReloadsTheSamePackRatherThanShortCircuitingOnIt() {
        // The identity is "what is RESIDENT", not "what was last asked for": onTrimMemory frees
        // the recognizer, and re-picking that same language must load again. (This is the exact
        // path the release collector leaves behind — it deliberately does not clear Main's cache
        // of the pack, so the engine's own answer is the one that has to be right.)
        val rec = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY)
        val factory = ScriptedFactory(rec, next = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY))
        val e = engine(rec, factory = factory)
        e.warm(dir, pack)
        e.release()
        assertFalse(e.isWarm())
        e.warm(dir, pack)
        assertEquals("released is not resident", 2, factory.loads)
        assertTrue(e.isWarm())
    }

    // ------------------------------------------------------------- the feed and the partials

    @Test fun partialsArriveLowercasedDedupedAndPrefixGrowingAtTheDecodeCadence() {
        val rec = ScriptedRecognizer(CANARY_PARTIALS, canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 2_560)
        // First decode at 7,200 samples (chunk 15), then every 5,120 (chunks 25, 35, 45, …): the
        // text changes on the first four decodes and repeats on the rest — repeats are not partials.
        assertEquals(listOf("one", "one two three", "one two three four", "one two three four five"), emitted)
        val s = rec.streams[1]
        assertEquals("one accept per 32 ms chunk", 80, s.fed.size)
        assertEquals("decode only while ready: 1 + (40,960 − 7,200) / 5,120", 7, s.decodes)
    }

    @Test fun commitPadsFinishesDrainsFreezesTheFinalTextAndRecreatesTheStream() {
        val rec = ScriptedRecognizer(CANARY_PARTIALS, canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 2_560)
        var frozen: Pair<Long, String>? = null
        e.commit(seq = 0L, retainMs = 0L) { seq, text -> frozen = seq to text }
        assertEquals(0L to "one two three four five", frozen)
        val s1 = rec.streams[1]
        assertEquals("the pad is exactly the PACK's padMs of zeros", 8_000, s1.fed.last())
        assertTrue(s1.finished)
        assertTrue("the padded stream is released, never reset", s1.released)
        assertEquals("canary + session + the fresh stream", 3, rec.streams.size)
        assertFalse(rec.streams[2].released)
        assertEquals("the pad's decodes: 1 + (48,960 − 7,200) / 5,120", 9, s1.decodes)
        assertEquals(
            "stream-timing: seq=0 audio=2560 decodes=9 decodeMs=0 p50us=0 p99us=0 rtf=0.000 partials=4 firstPartialMs=480 padMs=500 shed=0 retract=0",
            logs.last(),
        )
        assertEquals("the freeze itself emits no partial — the composer speaks for it", 4, emitted.size)
    }

    @Test fun theCommitPadAndTheLoggedPadBothComeFromTheLOADEDPack() {
        // The route for a second language, proven without one: warm a 640 ms row (T = 77) and the
        // freeze feeds 13,120 zeros instead of 8,000 AND the timing line says padMs=820. A flat
        // 500 there is 320 ms short of one forward pass, so the utterance-final word would never
        // emit — and the line would report a pad the stream never received (qualification E1).
        val sixForty = StreamingPackCatalog.EN.copy(language = "xx", decodeChunkLen = 64, encoderT = 77)
        val rec = ScriptedRecognizer(CANARY_PARTIALS, canaryText = CANARY)
        val e = engine(rec)
        e.warm(dir, sixForty)
        e.open { emitted += it }
        feedMs(e, 2_560)
        e.commit(seq = 0L, retainMs = 0L) { _, _ -> }
        assertEquals("the pack's own pad, not the measured constant", 13_120, rec.streams[1].fed.last())
        assertTrue("and the line reports the pad the stream got", logs.last().contains(" padMs=820 "))
    }

    @Test fun aRetainedTailIsTrimmedFromTheFrozenTextAndRefedToTheFreshStream() {
        val rec = ScriptedRecognizer(
            listOf("HELLO THERE FRIEND"),
            timestampsOf = { floatArrayOf(0.5f, 1.0f, 1.5f) },
            canaryText = CANARY,
        )
        val e = warmOpen(rec)
        feedMs(e, 2_048)
        var frozen = ""
        e.commit(seq = 3L, retainMs = 800L) { _, text -> frozen = text }
        // cut = (2,048 − 800) / 1000 = 1.248 s: HELLO (0.5) and THERE (1.0) stay, FRIEND (1.5) goes.
        assertEquals("hello there", frozen)
        val fresh = rec.streams[2]
        assertEquals("the ring's last 800 ms (12,800 samples) re-fed in one accept", listOf(12_800), fresh.fed)
        assertEquals("hello there friend", emitted.last())
    }

    @Test fun theRefedTailIsTheStreamsAudioNotTheNextSegmentsAudioOrFirstPartial() {
        // (review B1) The tail has to be in the CUT's timeline — cutSeconds measures against it —
        // but it is audio the line just logged already reported, so it is not this segment's.
        val rec = ScriptedRecognizer(listOf("A", "A B", "A B C", "A B C D"), canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 2_048)
        e.commit(seq = 1L, retainMs = 800L) { _, _ -> }
        assertEquals(
            "stream-timing: seq=1 audio=2048 decodes=7 decodeMs=0 p50us=0 p99us=0 rtf=0.000 partials=4 firstPartialMs=480 padMs=500 shed=0 retract=0",
            logs.last(),
        )
        assertEquals("the re-fed tail's echo still reaches the strip", "a b", emitted.last())
        feedMs(e, 1_024)
        e.commit(seq = 2L, retainMs = 0L) { _, _ -> }
        // 1,024 ms of NEW audio (not 1,824), two partials of its own (not three), and the lag of
        // the first of those (not the 0 the echo would have written) — the sheet's rtf and Z1 row.
        assertEquals(
            "stream-timing: seq=2 audio=1024 decodes=6 decodeMs=0 p50us=0 p99us=0 rtf=0.000 partials=2 firstPartialMs=320 padMs=500 shed=0 retract=0",
            logs.last(),
        )
    }

    @Test fun queueOverflowShedsTheChunkAndSaysSoOnTheTimingLine() {
        val rec = ScriptedRecognizer(listOf("A"), canaryText = CANARY)
        val exec = ManualExecutor()
        val e = warmOpen(rec, executor = exec, capacity = 2)
        exec.runAll()
        repeat(3) { e.sendAudio(ByteArray(1024)) }   // the executor is starved: the third chunk has nowhere to go
        exec.runAll()
        assertEquals(2 * 512L, rec.streams[1].samples)
        e.commit(0L, 0L) { _, _ -> }
        exec.runAll()
        assertTrue(logs.last().contains(" shed=1 "))
    }

    @Test fun aRetractionIsCountedNeverCorrected() {
        val rec = ScriptedRecognizer(listOf("ONE TWO", "ONE THREE"), canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 1_024)
        assertEquals("the strip shows what the model now says", listOf("one two", "one three"), emitted)
        e.commit(0L, 0L) { _, _ -> }
        assertTrue(logs.last().endsWith(" retract=1"))
    }

    @Test fun theThrottleNeverBitesAtTheDecodeCadenceButThinsABurst() {
        // 320 ms between partials is over the 150 ms floor, so steady state emits every change.
        // Two changes inside one burst (the clock does not move) emit once and the second lands
        // on the next drain — nothing is lost, only thinned.
        val rec = ScriptedRecognizer(listOf("A", "A B", "A B C"), canaryText = CANARY)
        val e = warmOpen(rec)
        repeat(25) { e.sendAudio(ByteArray(1024)) }           // clock frozen: decodes 1 and 2 inside "one instant"
        assertEquals(listOf("a"), emitted)
        now += 200
        e.sendAudio(ByteArray(1024))
        assertEquals(listOf("a", "a b"), emitted)
    }

    // ------------------------------------------------------------- failure and lifecycle

    @Test fun everyWayALanguageGoesOffHandsThatPackToTheSurfacesThatPromisedItsWords() {
        // (4.5.0 Task 3 fix round 2, review r2's N2) The verdict lived on this object and nothing
        // outside it could read it — the engine is a private field of the service — while the
        // strip above the language selector promises *"words appear on the bubble whenever you
        // pick it"* off a terminal board record. So `disable`, the ONE writer of the set, hands
        // the pack over; the service publishes it into `PreviewDisabled`.
        //
        // It is NOT `onLoadFailure`: three of the four causes below leave the bytes on disk valid
        // and `state()` answering `Installed`, so `markCorrupt` would be wrong for them — and
        // there is no other signal at all.
        val loadThrew = mutableListOf<StreamingPack>()
        engine(null, factory = ScriptedFactory(null, throwAtLoad = true), onDisabled = { loadThrew += it })
            .warm(dir, pack)
        assertEquals("a load that threw", listOf(pack), loadThrew)

        val canaryFailed = mutableListOf<StreamingPack>()
        engine(ScriptedRecognizer(listOf("HELLO"), canaryText = ""), onDisabled = { canaryFailed += it })
            .warm(dir, pack)
        assertEquals("a canary that failed, with the bytes intact", listOf(pack), canaryFailed)

        val noClip = mutableListOf<StreamingPack>()
        engine(ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY), clip = null, onDisabled = { noClip += it })
            .warm(dir, pack)
        assertEquals("a missing canary clip", listOf(pack), noClip)

        val struckOut = mutableListOf<StreamingPack>()
        val rec = ScriptedRecognizer(listOf("A"), failDecodesFrom = 10, canaryText = CANARY)
        val e = engine(rec, onDisabled = { struckOut += it })
        e.warm(dir, pack)
        e.open { emitted += it }
        feedMs(e, 1_600)
        assertEquals(
            "and three decode throws in one session — the pack named BEFORE the identity is " +
                "cleared, so the language is known",
            listOf(pack), struckOut,
        )

        val untouched = mutableListOf<StreamingPack>()
        val fine = ScriptedRecognizer(listOf("HELLO"), canaryText = CANARY)
        engine(fine, onDisabled = { untouched += it }).warm(dir, pack)
        assertEquals("a pack that loads and passes hands over nothing", emptyList<StreamingPack>(), untouched)
    }

    @Test fun threeConsecutiveDecodeFailuresDisableThePreviewerAndBlankTheStrip() {
        val rec = ScriptedRecognizer(listOf("A"), failDecodesFrom = 10, canaryText = CANARY)   // the canary's 9 decodes pass
        val e = warmOpen(rec)
        feedMs(e, 1_600)   // three fresh streams × 15 chunks to reach their first (throwing) decode
        assertTrue(e.isDisabled(pack))
        assertFalse(e.isWarm())
        assertTrue(rec.released)
        assertEquals("", emitted.last())
        assertEquals(3, logs.count { it.startsWith("stream-preview: decode threw") })
    }

    @Test fun threeThrowsSpreadOverThreeSessionsDoNotDisableThePreviewer() {
        // (review B2) The count carries across the SEGMENTS of one session — a model throwing once
        // per segment must still disable — but a new session is a new verdict. Any session whose
        // last decode threw ends at a non-zero count (a stop mid-sentence is that shape), so
        // without the boundary three ordinary sessions would switch the feature off for the process.
        val rec = ScriptedRecognizer(listOf("A"), failDecodesFrom = 10, canaryText = CANARY)   // the canary's 9 pass
        val e = engine(rec)
        e.warm(dir, pack)
        repeat(3) {
            e.open { emitted += it }
            feedMs(e, 480)   // 15 chunks = 7,680 samples: exactly one decode in this session, and it throws
            e.close()
        }
        assertEquals(3, logs.count { it.startsWith("stream-preview: decode threw") })
        assertFalse("a session boundary clears the three-strike count (spec §7.1)", e.isDisabled(pack))
        assertTrue("and the previewer is still warm for the next session", e.isWarm())
    }

    @Test fun closeReleasesTheStreamButNotTheRecognizer_releaseReleasesBoth() {
        val rec = ScriptedRecognizer(listOf("A"), canaryText = CANARY)
        val e = warmOpen(rec)
        feedMs(e, 64)
        e.close()
        assertTrue(rec.streams[1].released)
        assertFalse(rec.released)
        assertTrue("still warm across sessions", e.isWarm())
        e.sendAudio(ByteArray(1024))
        assertEquals("audio after close is dropped", 2, rec.streams.size)
        e.release()
        assertTrue(rec.released)
        assertFalse(e.isWarm())
        assertFalse("release is not a verdict", e.isDisabled(pack))
    }

    @Test fun aCommitBeforeWarmFreezesBlank() {
        val e = engine(ScriptedRecognizer(listOf("A")))
        var frozen: String? = null
        e.commit(7L, 0L) { _, text -> frozen = text }
        assertEquals("", frozen)
        assertNull(logs.lastOrNull())
    }
}
