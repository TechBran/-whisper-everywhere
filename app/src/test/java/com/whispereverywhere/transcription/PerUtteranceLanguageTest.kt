package com.whispereverywhere.transcription

import com.whispereverywhere.npu.NpuDecodePolicy
import com.whispereverywhere.npu.WhisperTokens
import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 4.1 L7 — per-utterance language on the NPU tiers, the owner's feature: an auto session may
 * start in one language and finish in another. Explicit selection stays absolute; the CPU tiers
 * keep the 3.7 latch untouched.
 *
 * The mechanism under test is one interface member with a default body
 * ([WhisperBackend.detectsPerUtterance]) and two one-line engine reads of it, both of the LIVE
 * backend:
 *
 *  - `runSegment`'s effective-language conditional: a per-utterance backend receives the SESSION
 *    language unchanged (null stays null, so auto re-detects every segment); a latching backend
 *    rides [LanguagePin] exactly as 3.7 shipped it;
 *  - the pin-feed guard: a per-utterance backend never feeds the latch, so THE FALLBACK EDGE —
 *    a session whose NPU declined mid-life — finds the pin EMPTY and re-acquires the whole 3.7
 *    behaviour from that point, fed by the first post-decline segment's own detection.
 *
 * Behaviour is executed through [LocalWhisperEngine] with fakes (the shared ones from
 * `LocalWhisperEngineTest.kt` plus [PerUtteranceProbeBackend]); the NPU backend's own half is
 * pinned as SOURCE TEXT, because no JVM test may name `NpuWhisperBackend` — it touches
 * `QnnAsrNative`, whose `init` block dlopens a library this classpath will never have.
 */
class PerUtteranceLanguageTest {

    private fun fastRetry() =
        RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    private val pcm = byteArrayOf(0x10, 0x00, 0x20, 0x00)

    private fun engineWith(backend: WhisperBackend) = LocalWhisperEngine(
        modelPathProvider = FakeModelPathProvider("/models/multi.bin"),
        retry = fastRetry(),
        backend = backend,
        executor = SameThreadExecutorService(),
    )

    /**
     * Records the lang of every transcribe; scripted texts; scripted detection — the
     * `PinProbeBackend` shape, plus the L7 member as LIVE state so a test can flip it exactly
     * where `NpuWhisperBackend`'s `fallbackBackend` flips: between segments, or DURING a
     * transcribe (the mid-segment `fallBackAndRun` shape).
     */
    private class PerUtteranceProbeBackend(
        private val script: List<String>,
        private val detected: String? = "de",
    ) : WhisperBackend {
        /** LIVE, like the real backend's `fallbackBackend == null` — mutable mid-session. */
        @Volatile var perUtterance: Boolean = true

        /** 0-based transcribe index that "declines": flips [perUtterance] false MID-CALL. */
        var declineDuringCall: Int = -1

        val langs = mutableListOf<String?>()
        var detectQueries = 0
        private var i = 0

        override val detectsPerUtterance: Boolean get() = perUtterance
        override fun load(modelPath: String): Long = 42L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
            langs += lang
            if (i == declineDuringCall) perUtterance = false
            val text = script[i.coerceAtMost(script.size - 1)]
            i++
            return text
        }
        override fun detectedLanguage(ctx: Long): String? { detectQueries++; return detected }
        override fun release(ctx: Long) = Unit
    }

    // ------------------------------------------------------------------ the default, inherited

    @Test
    fun theDefaultIsFalse_andWhisperNativeBackendInheritsIt() {
        val minimal = object : WhisperBackend {
            override fun load(modelPath: String): Long = 1L
            override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean) = ""
            override fun release(ctx: Long) = Unit
        }
        assertFalse(
            "a backend that says nothing must keep the 3.7 latch — the default is the latch",
            minimal.detectsPerUtterance,
        )
        assertFalse(
            "WhisperNativeBackend must INHERIT the default — asserted against the real " +
                "production object, not a fake. whisper.cpp's per-segment detect pass is roughly " +
                "HALF of multi's steady-state native cost, which is the entire reason the 3.7 " +
                "latch exists: an override answering true here re-imposes that cost on every " +
                "auto segment of every CPU-tier session, forever.",
            WhisperNativeBackend.detectsPerUtterance,
        )
    }

    // ------------------------------------------------------------------ the two arms, executed

    @Test
    fun anNpuClassBackendSeesTheSessionLanguageOnEverySegment() {
        val backend = PerUtteranceProbeBackend(script = listOf("hola", "bonjour", "hallo"))
        val engine = engineWith(backend)
        engine.connect(language = null, listener = RecordingListener())

        repeat(3) { engine.sendAudio(pcm); engine.commit() }

        assertEquals(
            "an auto session on a per-utterance backend must hand EVERY segment the session " +
                "language unchanged — including null — so the backend's own ~5 ms detect pass " +
                "re-answers per utterance and the session can start in one language and finish " +
                "in another",
            listOf(null, null, null),
            backend.langs,
        )
        assertEquals(
            "and the engine must never feed the session pin from it: detectedLanguage is not " +
                "queried at all. ONE query here is the latch reinstated — its first answer " +
                "would freeze every later segment's language",
            0,
            backend.detectQueries,
        )
    }

    @Test
    fun aLatchingBackendKeepsTheExactThreeSevenPinBehaviour() {
        val backend = PerUtteranceProbeBackend(script = listOf("hallo", "welt", "drei"))
            .apply { perUtterance = false }
        val engine = engineWith(backend)
        engine.connect(language = null, listener = RecordingListener())

        repeat(3) { engine.sendAudio(pcm); engine.commit() }

        assertEquals(
            "a latching backend rides the 3.7 pin byte-for-byte: segment 1 pays the detect " +
                "pass (lang=null), segments 2+ pass the pinned code",
            listOf(null, "de", "de"),
            backend.langs,
        )
        assertEquals("queried exactly once — the 3.7 contract", 1, backend.detectQueries)
    }

    @Test
    fun anExplicitSelectionPassesUntouchedAndNeverPinsUnderEitherAnswer() {
        for (answer in listOf(true, false)) {
            val backend = PerUtteranceProbeBackend(script = listOf("hello", "world"))
                .apply { perUtterance = answer }
            val engine = engineWith(backend)
            engine.connect(language = "en", listener = RecordingListener())

            repeat(2) { engine.sendAudio(pcm); engine.commit() }

            assertEquals(
                "the ruling's absolute half: an explicit selection is passed through untouched " +
                    "under detectsPerUtterance=$answer",
                listOf("en", "en"),
                backend.langs,
            )
            assertEquals(
                "and never pins under detectsPerUtterance=$answer — zero detection queries",
                0,
                backend.detectQueries,
            )
        }
    }

    // ------------------------------------------------------------------ the fallback edge

    @Test
    fun aDeclineBetweenSegmentsReacquiresTheCpuLatchFromThatPoint() {
        val backend = PerUtteranceProbeBackend(script = listOf("uno", "dos", "drei", "vier"))
        val engine = engineWith(backend)
        engine.connect(language = null, listener = RecordingListener())

        repeat(2) { engine.sendAudio(pcm); engine.commit() }   // NPU phase: per-utterance
        backend.perUtterance = false                           // the tier declined mid-life
        repeat(2) { engine.sendAudio(pcm); engine.commit() }   // CPU phase

        assertEquals(
            "segments 1-2 (NPU): lang unchanged, no latch. Segment 3 — the first on the CPU " +
                "tier — must find the pin EMPTY (the per-utterance phase never fed it; a pin " +
                "fed there would hand this segment a STALE code as a false latch) and " +
                "re-detect fresh. Segment 4 rides the newly-acquired pin",
            listOf(null, null, null, "de"),
            backend.langs,
        )
        assertEquals(
            "exactly one detection query: segment 3's — the re-acquisition, at the edge and " +
                "not before it",
            1,
            backend.detectQueries,
        )
    }

    @Test
    fun aMidSegmentDeclineFeedsTheLatchFromTheVerySegmentThatFellBack() {
        val backend = PerUtteranceProbeBackend(script = listOf("uno", "zwei", "drei"))
            .apply { declineDuringCall = 1 }
        val engine = engineWith(backend)
        engine.connect(language = null, listener = RecordingListener())

        repeat(3) { engine.sendAudio(pcm); engine.commit() }

        assertEquals(
            "segment 2's own transcribe declined (detectsPerUtterance flipped false DURING the " +
                "call — the fallBackAndRun shape, where the CPU tier already ran this very " +
                "segment). It was handed lang=null; the pin-feed check runs AFTER the " +
                "transcribe returns and must consult the LIVE backend, so segment 2's detection " +
                "feeds the latch and segment 3 rides it. A session-start (or pre-transcribe) " +
                "snapshot would still answer true, skip the feed, and leave the latch unarmed " +
                "on whisper.cpp — a detect pass paid on every remaining segment",
            listOf(null, null, "de"),
            backend.langs,
        )
        assertEquals(
            "the very segment that fell back is the one that re-acquires the latch",
            1,
            backend.detectQueries,
        )
    }

    /**
     * THE FALSE->TRUE FLIP — the executed E1 killer (L7 review IMP-1, added at L8 triage).
     *
     * E1 (the effective-language conditional deleted, `effectiveLang =
     * languagePin.languageFor(lang)` unconditionally) is observationally identical in every state
     * where the pin is EMPTY under a per-utterance answer — which is every state the monotonic
     * true->false backend can reach, so until this test its only killer was the source pin. The
     * one state that executes the difference is a pin FED while latching and then a backend that
     * answers true again: the interface KDoc licenses it ("LIVE, read off the ACTIVE backend per
     * segment" — nothing forbids false->true), and the L5 I1 offer-line re-arm makes a
     * recovering backend a contemplated future. The source pin is KEPT regardless — it remains
     * the only possible killer for E6, a dead unconditional consult being execution-invisible in
     * principle.
     */
    @Test
    fun aBackendThatRecoversPerUtteranceDetectionEscapesTheLatchItLeftBehind() {
        val backend = PerUtteranceProbeBackend(script = listOf("hallo", "welt", "hola"))
            .apply { perUtterance = false }
        val engine = engineWith(backend)
        engine.connect(language = null, listener = RecordingListener())

        repeat(2) { engine.sendAudio(pcm); engine.commit() }   // latching: detect once, pin "de"
        backend.perUtterance = true                            // the backend recovers
        engine.sendAudio(pcm); engine.commit()                 // per-utterance again

        assertEquals(
            "segment 3 — the first after the backend answers per-utterance again — must receive " +
                "the SESSION language (null), not the \"de\" the latch pinned while the backend " +
                "was latching. An unconditional pin consult (E1) hands it \"de\": the tier's own " +
                "per-utterance detect pass is silenced by a stale latch and a mid-session " +
                "language switch transcribes under the old language, fluently and wrongly.",
            listOf(null, "de", null),
            backend.langs,
        )
        assertEquals(
            "exactly one detection query — segment 1's latch feed; the recovered phase neither " +
                "queries nor re-feeds",
            1,
            backend.detectQueries,
        )
    }

    @Test
    fun theReacquiredLatchKeepsTheThreeSevenReDetectRule() {
        val backend = PerUtteranceProbeBackend(
            script = listOf("uno", "dos", "tres"),
            detected = null,   // the CPU tier's detection is unusable
        )
        val engine = engineWith(backend)
        engine.connect(language = null, listener = RecordingListener())

        engine.sendAudio(pcm); engine.commit()   // NPU phase: no query
        backend.perUtterance = false             // decline
        engine.sendAudio(pcm); engine.commit()   // CPU: queries; unusable -> must NOT pin
        engine.sendAudio(pcm); engine.commit()   // CPU: re-queries — the 3.7 rule, whole

        assertEquals(listOf(null, null, null), backend.langs)
        assertEquals(
            "the re-acquired latch is the WHOLE 3.7 behaviour, including its re-detect rule: " +
                "an unusable detection never pins, so every later CPU segment re-attempts — " +
                "zero queries while per-utterance, then one per CPU segment",
            2,
            backend.detectQueries,
        )
    }

    // ------------------------------------------------------------------ the diag surface

    @Test
    fun aMidSessionLanguageSwitchUnderAutoIsTwoDetectedNotes_neverALatchNote() {
        // The owner's feature, on its diag surface — through the pure policy the NPU backend
        // calls per segment (the backend itself is unnameable from a JVM test; its half is
        // source-pinned below). With no latch, EVERY segment resolves fresh and prints its own
        // provenance note, so a mid-session switch is visible in the log as detected->detected
        // with different codes — checkable, per the F-rule, from the same value the token rides
        // in rather than from a re-spelled diag string.
        val family = WhisperTokens.SMALL
        val es = NpuDecodePolicy.resolveLangToken(family, null, family.langToken("es"), "en-US")
        val fr = NpuDecodePolicy.resolveLangToken(family, null, family.langToken("fr"), "en-US")
        assertEquals("auto->es(detected)", es.note)
        assertEquals("auto->fr(detected)", fr.note)
        // Both cross the seam as real detections — under L7 that answer is per-segment truth,
        // not a latch feed, because the engine no longer queries a per-utterance backend at all.
        assertEquals("es", es.reportable)
        assertEquals("fr", fr.reportable)
        // And the policy itself carries no memory: re-resolving the FIRST language after the
        // switch answers it again. A latch smuggled into the policy would answer es here — the
        // false latch note this test's name forbids.
        assertEquals(
            "auto->es(detected)",
            NpuDecodePolicy.resolveLangToken(family, null, family.langToken("es"), "en-US").note,
        )
    }

    // ------------------------------------------------------------------ source access

    /** The house locator: walk up from the test's working directory; normalize CRLF once. */
    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /**
     * Character offsets of every LIVE (non-comment) line of [scope] containing [needle] — the
     * house helper, same reason as `NpuNativeContractTest.liveOffsets`: ordering and exactly-once
     * claims must never be built on `indexOf`, which measures a commented-out mention exactly as
     * happily as the code.
     */
    private fun liveOffsets(scope: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var at = 0
        for (line in scope.split("\n")) {
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            if (!commented && line.contains(needle)) out += at
            at += line.length + 1
        }
        return out
    }

    /** The LIVE (non-comment) lines of [scope] containing [needle], trimmed. */
    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").map { it.trim() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) &&
                line.contains(needle)
        }

    /**
     * One member's text: from [anchor] to the next sibling declaration at the four-space member
     * indent. The house indent-rule scoper (`NpuNativeContractTest.kotlinMemberBody`) ends a
     * member at the first line indented no deeper than its anchor, which mis-scopes a MULTI-LINE
     * signature — the `): String = …` continuation sits at the anchor's own indent, so the
     * "body" would end before it began. Both `runSegment` and `transcribeStreaming` carry
     * multi-line signatures, hence this variant; the loud missing-anchor failure is kept for the
     * house reason.
     */
    private fun memberText(kt: String, anchor: String): String {
        val start = kt.indexOf(anchor)
        assertTrue(
            "anchor \"$anchor\" is missing. indexOf() returns -1 when the anchor is absent, so " +
                "substring(start) would silently rebase the scope to the top of the file and " +
                "every claim below would be answered by unrelated code instead of failing.",
            start >= 0,
        )
        val lines = kt.substring(start).split("\n")
        val sibling =
            Regex("^    (override |private |internal |public |fun |val |var |companion )")
        val body = StringBuilder(lines.first())
        var closed = false
        for (line in lines.drop(1)) {
            if (sibling.containsMatchIn(line)) { closed = true; break }
            body.append("\n").append(line)
        }
        assertTrue(
            "no sibling declaration follows \"$anchor\" — the scope ran to the end of the file, " +
                "and every assertion below would be answered by unrelated code.",
            closed,
        )
        return body.toString()
    }

    // ------------------------------------------------------------------ source contracts

    /**
     * The engine's half, as ORDER and exactly-once claims — not presence. A second,
     * unconditional `languagePin.languageFor(` call (before, after, or beside the conditional)
     * satisfies any presence assertion while quietly reinstating the latch under a per-utterance
     * backend, so the count is the pin.
     */
    @Test
    fun sourceContract_theEnginesOnlyPinConsultIsInsideThePerUtteranceConditional() {
        val engine = source("src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt")
        assertEquals(
            "runSegment must route the effective language through the per-utterance " +
                "conditional, on exactly one live line",
            1,
            liveOffsets(
                engine,
                "if (backend.detectsPerUtterance) lang else languagePin.languageFor(lang)",
            ).size,
        )
        assertEquals(
            "`languagePin.languageFor(` must appear on exactly ONE live line of the whole file " +
                "— the conditional's else arm, which the assertion above just located. One live " +
                "occurrence total is what makes 'inside the conditional' provable at all: a " +
                "second, unconditional consult would re-route a per-utterance session through " +
                "the pin while every presence assertion stayed green.",
            1,
            liveOffsets(engine, "languagePin.languageFor(").size,
        )
        val body = memberText(engine, "private fun runSegment(")
        val conditional =
            liveOffsets(body, "if (backend.detectsPerUtterance) lang else")
        val feed = liveOffsets(body, "!backend.detectsPerUtterance")
        assertEquals(
            "the pin-feed block must be guarded by `!backend.detectsPerUtterance` on exactly " +
                "one live line of runSegment: a per-utterance backend's resolutions are " +
                "per-segment answers, and feeding the latch from them hands a STALE code to " +
                "the first post-fallback segment",
            1,
            feed.size,
        )
        assertTrue(
            "ORDER: the feed guard (${feed.first()}) must sit BELOW the effective-language " +
                "conditional (${conditional.first()}) — after the transcribe — so the answer it " +
                "reads is the LIVE backend's at feed time. A backend that fell back DURING this " +
                "very segment already answers false there, and that segment's detection is the " +
                "one that re-acquires the CPU latch.",
            conditional.first() < feed.first(),
        )
        assertEquals(
            "the feed guard shares the feed's own condition with the stale-listener guard — " +
                "one condition, not a second `if` that could drift from it",
            1,
            liveOffsets(body, "listener === myListener && !backend.detectsPerUtterance").size,
        )
    }

    @Test
    fun sourceContract_detectsPerUtteranceIsALiveGetOverTheGuard_notAStoredVal() {
        val backend = source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
        val lines = liveLines(backend, "detectsPerUtterance")
        assertEquals(
            "NpuWhisperBackend must mention detectsPerUtterance on exactly ONE live line — its " +
                "declaration. Found: $lines",
            1,
            lines.size,
        )
        assertEquals(
            "and that line is a get() over the routing GUARD, re-read on every call. A stored " +
                "`val … = fallbackBackend == null` is initialised at construction — when " +
                "fallbackBackend is ALWAYS null — so it freezes the answer at true: a session " +
                "that declines mid-life keeps claiming per-utterance detection, the engine " +
                "never re-acquires the CPU latch, and whisper.cpp pays its detect pass on every " +
                "remaining segment of the session.",
            "override val detectsPerUtterance: Boolean get() = fallbackBackend == null",
            lines.single(),
        )
    }

    /**
     * Q6 M4, folded here — the first delegating override. `NpuWhisperBackend` never overrode
     * `transcribeStreaming`, so after any decline the session silently lost live previews for
     * the rest of its life even though `WhisperNativeBackend` supplies them.
     */
    @Test
    fun sourceContract_transcribeStreamingDelegatesThroughTheFallbackAfterADecline() {
        val backend = source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
        val body = memberText(backend, "override fun transcribeStreaming(")
        val gate = liveOffsets(body, "NativeComputeGate.serialized {")
        val read = liveOffsets(body, "fallbackBackend?.let")
        assertTrue("the override must take the gate on a live line", gate.isNotEmpty())
        assertTrue("the override must consult the routing state on a live line", read.isNotEmpty())
        assertTrue(
            "the gate (${gate.first()}) must be taken BEFORE the routing state is read " +
                "(${read.first()}) — hoisting the short-circuit in front of the hold reinstates " +
                "the stale-null read the routing pair's declaration forbids for ACTING readers, " +
                "and this member runs native compute.",
            gate.first() < read.first(),
        )
        assertTrue(
            "the fallen-back arm must delegate the STREAMING form, with the CALLER's closure " +
                "and the FALLBACK handle — anything less (a plain transcribe, the NPU handle, a " +
                "dropped closure) keeps the previews lost",
            liveOffsets(
                body,
                "it.transcribeStreaming(fallbackCtx, samples, lang, useVad, onNewSegment)",
            ).isNotEmpty(),
        )
        assertTrue(
            "the live-NPU arm must keep the interface default's exact behaviour — a plain " +
                "transcribe, zero deltas: the NPU decode loop has no per-segment native " +
                "callback, and forging deltas would be worse than omitting them",
            liveOffsets(body, "transcribe(ctx, samples, lang, useVad)").isNotEmpty(),
        )
    }

    /**
     * Q6 M4's other half. Without this override a declined session's timing lines lost their
     * vadIn/vadOut/ctxFrames suffix for the rest of the session — `SegmentTiming.line` is built
     * to omit the fields on null, and null is what the inherited default answered forever.
     */
    @Test
    fun sourceContract_lastSegmentStatsDelegatesAfterADeclineAndForgesNothingBeforeIt() {
        val backend = source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
        val body = memberText(backend, "override fun lastSegmentStats(")
        assertTrue(
            "the override must route the diagnostic through the guard to the CPU tier's " +
                "counters — `fallbackBackend?.lastSegmentStats(fallbackCtx)`: the guard vouches " +
                "for the handle (written last, cleared first), and the safe-call IS the " +
                "null-while-live arm",
            liveOffsets(body, "fallbackBackend?.lastSegmentStats(fallbackCtx)").isNotEmpty(),
        )
        assertEquals(
            "NpuWhisperBackend must construct NativeSegmentStats NOWHERE. Null and all-zero " +
                "are DIFFERENT ANSWERS (the type's own KDoc): the NPU path has no native " +
                "counters, and forging zeros would collapse 'no counters exist' into the " +
                "all-zero MEASUREMENT — 'a transcribe ran and cost nothing' — that the timing " +
                "seam singles out as its most diagnostic shape.",
            0,
            liveOffsets(backend, "NativeSegmentStats(").size,
        )
    }
}
