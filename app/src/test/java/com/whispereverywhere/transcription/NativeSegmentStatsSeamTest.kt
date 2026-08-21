package com.whispereverywhere.transcription

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The PRODUCTION half of the 3.7 Workstream F stats seam: [WhisperNativeBackend]'s one-slot,
 * ctx-tagged snapshot of the process-global native counters that [WhisperNative.lastSegmentStats]
 * publishes.
 *
 * WHY A SEPARATE CLASS FROM `WhisperBackendSeamTest`: that class pins the INTERFACE DEFAULTS every
 * fake inherits — pure, stateless, constructed per test. This one drives a process-wide SINGLETON
 * whose whole subject is mutable state and its invalidation, so it needs an `@After` and a
 * per-test ctx tag. Mixing the two would let a leaked slot from one make the other's `assertNull`
 * pass for the wrong reason.
 *
 * WHY SOME CLAIMS ARE ANCHORED TO SOURCE TEXT: `WhisperNative`'s static initialiser calls
 * `System.loadLibrary`, so on a JVM every native touch throws and no unit test can drive a
 * transcribe to completion. The ORDERING facts that make this seam correct — the invalidation at
 * the top of the hold, stats-before-tag, capture-after-the-success-point — are therefore invisible
 * to any behavioural test, and each is a silent regression: the numbers keep printing, they just
 * start belonging to a different segment. Every source assertion below is anchored to CONTENT,
 * never to a line number.
 */
class NativeSegmentStatsSeamTest {

    private companion object {
        /** Arbitrary non-zero fake ctx handles. Distinct so a leak between tests is visible. */
        const val CTX_A = 0x7F00_0011L
        const val CTX_B = 0x7F00_0022L
        const val CTX_CLEANUP = 0x7F00_00FFL
    }

    /**
     * Drives the REAL production transcribe path until it reaches its native call, which cannot
     * link on a JVM, and returns whatever it threw. This is not a mock of a failure: it is the
     * genuine "a transcribe entered the gate hold and then threw" sequence, which is the exact
     * case the invalidation exists for.
     */
    private fun transcribeThatCannotLink(ctx: Long): Throwable =
        runCatching {
            WhisperNativeBackend.transcribe(ctx, FloatArray(0), lang = null, useVad = false)
        }.exceptionOrNull() ?: throw AssertionError(
            "WhisperNativeBackend.transcribe RETURNED on a plain JVM. Every test in this class " +
                "assumes libwhisper_jni cannot link here; if it somehow can, the assertions below " +
                "would be measuring a real native transcribe's counters instead of the seam."
        )

    /**
     * Clears the singleton's slot through PRODUCTION code — the same invalidation a real hold
     * performs — so no test can be answered by a previous test's snapshot. `useVad = false` keeps
     * this off `VadModel`, which needs an Android context.
     */
    @After
    fun clearTheSlotThroughProductionCode() {
        transcribeThatCannotLink(CTX_CLEANUP)
    }

    // ---------------------------------------------------------------------------------------
    // The payload rules
    // ---------------------------------------------------------------------------------------

    @Test
    fun anAllZeroPayloadIsAMeasurement_notAnAbsence() {
        WhisperNativeBackend.captureStatsFrom(CTX_A, intArrayOf(0, 0, 0))
        val s = WhisperNativeBackend.lastSegmentStats(CTX_A)
        assertNotNull(
            "an all-zero payload must round-trip as a NON-NULL NativeSegmentStats. null and " +
                "zeros are DIFFERENT FACTS: null means \"no transcribe has run on this ctx\", " +
                "zeros mean \"a transcribe ran and cost zero\". The two readings that produce " +
                "zeros — the VAD found no speech, and the energy gate fired — are the most " +
                "interesting diagnostics in this workstream, and a `if (v.all { it == 0 }) " +
                "return` guard added here as defensive tidying would silently retire both, " +
                "reporting them as \"no data\" and indistinguishable from a ctx that never ran.",
            s
        )
        assertEquals("ctxFrames must survive as a real 0", 0, s!!.ctxFrames)
        assertEquals("vadInSamples must survive as a real 0", 0, s.vadInSamples)
        assertEquals("vadOutSamples must survive as a real 0", 0, s.vadOutSamples)
    }

    @Test
    fun thePayloadMapsInContractOrder_ctxFramesVadInVadOut() {
        WhisperNativeBackend.captureStatsFrom(CTX_A, intArrayOf(512, 48_000, 32_000))
        assertEquals(
            "the native array is [ctxFrames, vadIn, vadOut] and the ORDER is the whole contract: " +
                "three bare ints carry no names across JNI, so a permutation here is not a " +
                "compile error, not a runtime error and not visible in any log — it just " +
                "relabels the encoder's cost as a sample count, and the diagnostic then reads as " +
                "a surprising measurement rather than as a bug.",
            NativeSegmentStats(ctxFrames = 512, vadInSamples = 48_000, vadOutSamples = 32_000),
            WhisperNativeBackend.lastSegmentStats(CTX_A)
        )
    }

    @Test
    fun aLongerPayloadIsAccepted_soANativeSideAdditionCannotBlindTheSeam() {
        // `v.size < 3`, never `v.size != 3`: a fourth counter added natively (the obvious next
        // step in this workstream) must not silently turn every reading into "unknown" on an
        // app build that predates it.
        WhisperNativeBackend.captureStatsFrom(CTX_A, intArrayOf(9, 8, 7, 6))
        assertEquals(
            NativeSegmentStats(ctxFrames = 9, vadInSamples = 8, vadOutSamples = 7),
            WhisperNativeBackend.lastSegmentStats(CTX_A)
        )
    }

    @Test
    fun aShortOrMissingPayloadLeavesTheAnswerUnknown_withoutThrowing() {
        listOf<IntArray?>(null, intArrayOf(), intArrayOf(1), intArrayOf(1, 2)).forEach { v ->
            // A fresh hold, exactly as production would: invalidate, then capture.
            transcribeThatCannotLink(CTX_A)
            WhisperNativeBackend.captureStatsFrom(CTX_A, v)
            assertNull(
                "a payload too short to be [ctxFrames, vadIn, vadOut] is UNKNOWN, not a reading " +
                    "— and the size check must come BEFORE the three subscripts. Deleting it " +
                    "does not fail a compile; it throws ArrayIndexOutOfBounds from inside a " +
                    "diagnostic, on a transcribe that had already succeeded. Payload size: " +
                    (v?.size?.toString() ?: "null"),
                WhisperNativeBackend.lastSegmentStats(CTX_A)
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // The ctx tag and its invalidation
    // ---------------------------------------------------------------------------------------

    @Test
    fun theSnapshotAnswersOnlyForTheCtxThatRan() {
        WhisperNativeBackend.captureStatsFrom(CTX_A, intArrayOf(512, 48_000, 32_000))
        assertNotNull(WhisperNativeBackend.lastSegmentStats(CTX_A))
        assertNull(
            "the counters are PROCESS-GLOBAL, so the tag is the only thing standing between a " +
                "batch chunk's numbers and a bubble segment's timing line. A backend that " +
                "answered for any ctx would hand the caller a different ctx's cost with nothing " +
                "about the values looking wrong.",
            WhisperNativeBackend.lastSegmentStats(CTX_B)
        )
    }

    @Test
    fun ctxZeroNeverMatches_becauseZeroIsInitsFailureValue() {
        WhisperNativeBackend.captureStatsFrom(0L, intArrayOf(512, 48_000, 32_000))
        assertNull(
            "0L is WhisperNative.init's FAILURE return, never a real handle. Without the " +
                "`ctx != 0L` guard the untouched initial tag (0L) matches a caller that passes " +
                "the failure value, so a backend that has never transcribed answers with " +
                "whatever the slot happens to hold.",
            WhisperNativeBackend.lastSegmentStats(0L)
        )
    }

    @Test
    fun aTranscribeThatThrewLeavesNoStatsBehind() {
        WhisperNativeBackend.captureStatsFrom(CTX_A, intArrayOf(512, 48_000, 32_000))
        assertNotNull("precondition: the slot holds CTX_A's numbers", WhisperNativeBackend.lastSegmentStats(CTX_A))

        val thrown = transcribeThatCannotLink(CTX_A)
        assertTrue(
            "this test only proves the INVALIDATION if the throw came from the native call " +
                "INSIDE the gate hold — a throw from the gate itself would never have reached " +
                "the invalidation and the assertion below would pass for the wrong reason. Got: " +
                thrown::class.java.name,
            thrown is ExceptionInInitializerError ||
                thrown is NoClassDefFoundError ||
                thrown is UnsatisfiedLinkError
        )
        assertNull(
            "a stats read may only ever be answered by the transcribe that completed in THIS " +
                "hold. Without the invalidation at the top of the hold, a transcribe that THREW " +
                "leaves the PREVIOUS segment's numbers tagged with a still-matching ctx — and a " +
                "ctx freed-then-reallocated at the same address (the GPU canary's failure " +
                "branches free and re-init) inherits the canary's numbers.",
            WhisperNativeBackend.lastSegmentStats(CTX_A)
        )
    }

    // ---------------------------------------------------------------------------------------
    // Source-anchored: the orderings no JVM test can observe
    // ---------------------------------------------------------------------------------------

    /**
     * Reads a repo source file from the test's working directory — the locator
     * `SegmentTimingTest`, `CaptureThreadPolicyTest`, `ProbeStatsTest` and
     * `NativeSegmentStatsContractTest` each already carry their own copy of. Line endings are
     * normalized at this single read site: TranscriptionEngine.kt is CRLF in this repo and
     * `readText()` does not normalize, so anything anchored on "\n" silently misses.
     */
    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun sourceDir(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate directory $relative from ${System.getProperty("user.dir")}")
    }

    private val engine: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt")
    }

    /**
     * One member of `object WhisperNativeBackend`, whose closing brace sits at four spaces.
     * Both asserts exist because `indexOf` returning -1 and `substringBefore` returning its
     * RECEIVER both FAIL OPEN: the first rebases the scope to the top of the file, the second
     * widens it to the rest of it, and every ordering claim below would then be measured across
     * unrelated code and pass on borrowed text.
     */
    private fun memberBody(anchor: String): String {
        val start = engine.indexOf(anchor)
        assertTrue("anchor \"$anchor\" is missing from TranscriptionEngine.kt", start >= 0)
        val body = engine.substring(start)
        assertTrue("no four-space-indented closing brace follows \"$anchor\"", body.contains("\n    }\n"))
        return body.substringBefore("\n    }\n")
    }

    /** True when [line] is live code rather than a comment. */
    private fun isLive(line: String): Boolean {
        val t = line.trimStart()
        return t.isNotEmpty() && !t.startsWith("//") && !t.startsWith("/*") && !t.startsWith("*")
    }

    /**
     * The position of a LIVE line matching [pattern] in [scope]. Every index used in an ordering
     * assertion here comes from this and never from `indexOf`: a literal search matches a
     * COMMENTED-OUT mention exactly as happily as the statement, so prose that drifts above the
     * code it describes would silently satisfy "X comes first".
     */
    private fun live(scope: String, pattern: String, what: String): MatchResult {
        val m = Regex("(?m)^[ \\t]*$pattern").find(scope)
        assertTrue("$what must appear on a LIVE line of code (pattern: $pattern)", m != null)
        return m!!
    }

    private fun liveLines(scope: String, needle: String): List<String> =
        scope.lineSequence().filter { isLive(it) && it.contains(needle) }.toList()

    @Test
    fun theSlotIsInvalidatedAsTheFirstThingInEveryHold() {
        val body = memberBody("private fun transcribeInternal(")
        val hold = live(body, """\): String = NativeComputeGate\.serialized \{""", "the gate hold")
        val statements = body.substring(hold.range.last)
            .lineSequence().drop(1).filter { isLive(it) }.map { it.trim() }.toList()
        assertTrue("transcribeInternal's hold has no live statements at all", statements.size >= 2)
        assertEquals(
            "the FIRST statement inside the hold must be `lastStats = null`. This mirrors " +
                "whisper_jni's own reset-at-the-top-of-transcribeRaw: a stats read may only ever " +
                "be answered by the transcribe that completed in THIS hold. Placed lower — or " +
                "deleted as redundant because the capture overwrites it anyway — a transcribe " +
                "that THREW leaves the previous segment's numbers tagged with a still-matching " +
                "ctx, and nothing about those numbers looks wrong. Found instead: " +
                statements.first(),
            "lastStats = null",
            statements[0]
        )
        assertEquals(
            "the SECOND statement must clear the tag. The tag is the guard, so a slot with " +
                "stats=null but a live tag is a half-invalidated slot.",
            "lastStatsCtx = 0L",
            statements[1]
        )
    }

    @Test
    fun theWriteOrderIsStatsThenTag_andBothFieldsAreVolatile() {
        val body = memberBody("internal fun captureStatsFrom(")
        val stats = live(body, """lastStats = NativeSegmentStats\(""", "the stats write")
        val tag = live(body, """lastStatsCtx = ctx""", "the tag write")
        assertTrue(
            "the stats must be written BEFORE the tag. The tag is what a reader checks, so with " +
                "the two swapped a reader that sees a matching tag can still see the PREVIOUS " +
                "snapshot's stats — a race that reproduces only under real interleaving and " +
                "reports plausible numbers when it does.",
            stats.range.first < tag.range.first
        )
        listOf(
            """@Volatile private var lastStats: NativeSegmentStats\? = null""",
            """@Volatile private var lastStatsCtx: Long = 0L"""
        ).forEach { decl ->
            assertTrue(
                "both slot fields must be @Volatile: they are written on whichever native " +
                    "executor held the gate and read on the engine's, and without it the " +
                    "stats-before-tag write order buys nothing. Missing: $decl",
                Regex(decl).containsMatchIn(engine)
            )
        }
    }

    @Test
    fun theOnlyRejectionInTheCapturePathIsPayloadSize() {
        val body = memberBody("internal fun captureStatsFrom(")
        val returns = liveLines(body, "return")
        assertEquals(
            "captureStatsFrom must contain EXACTLY ONE early return, the payload-size check. A " +
                "second one is almost certainly `if (v.all { it == 0 }) return` — the defensive " +
                "line that reads as tidying and silently converts the VAD-found-zero-speech and " +
                "energy-gate readings into \"no data\". Found: $returns",
            1,
            returns.size
        )
        assertTrue(
            "the one return must be the size guard. Found: ${returns.first().trim()}",
            returns.first().contains("v.size < 3")
        )
        assertTrue(
            "the size guard must be `< 3`, not `!= 3`, so a fourth native counter cannot blind " +
                "this seam on an app build that predates it",
            !body.contains("v.size != 3")
        )
    }

    @Test
    fun theCaptureHappensAfterTheSuccessPointOnBothBranches() {
        val body = memberBody("private fun transcribeInternal(")
        val captures = Regex("(?m)^[ \\t]*captureStats\\(ctx\\)").findAll(body).toList()
        assertEquals(
            "transcribeInternal has exactly two exits that ran whisper_full — the " +
                "non-validating fast path and the GPU-sentinel path — and each must capture. A " +
                "single shared capture is not possible without moving it into the finally, " +
                "where it would also run for a transcribe that THREW.",
            2,
            captures.size
        )
        val transcribe = live(body, """val text = WhisperNative\.transcribe\(""", "the fast-path transcribe")
        assertTrue(
            "the fast path must capture AFTER its transcribe returned: the counters describe a " +
                "transcribe that has finished writing them",
            captures[0].range.first > transcribe.range.first
        )
        val okTrue = live(body, """ok = true""", "the GPU sentinel's success point")
        val finallyAt = live(body, """\} finally \{""", "the GPU sentinel's finally")
        assertTrue(
            "the sentinel path must capture AFTER `ok = true`: the sentinel's verdict is about " +
                "the transcribe, and a diagnostic read must not sit between the success and the " +
                "flag that records it",
            captures[1].range.first > okTrue.range.first
        )
        assertTrue(
            "the capture must stay INSIDE the try, above the finally — a capture in the finally " +
                "runs for a THROWN transcribe too and re-tags the slot the invalidation just " +
                "cleared",
            captures[1].range.first < finallyAt.range.first
        )
    }

    @Test
    fun theBackendIsTheOnlyProductionReaderOfTheProcessGlobalCounters() {
        val callers = sourceDir("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                f.readText().replace("\r\n", "\n").lineSequence()
                    .any { isLive(it) && it.contains("WhisperNative.lastSegmentStats(") }
            }
            .map { it.name }
            .toList()
        assertEquals(
            "WhisperNative.lastSegmentStats is PROCESS-GLOBAL and is only meaningful on the " +
                "thread that just ran the transcribe, while it still holds NativeComputeGate. " +
                "WhisperNativeBackend's capture is the one legitimate caller; a second one " +
                "anywhere reads whatever the last transcribe in the process left behind and " +
                "cannot tell that it did. Callers found: $callers",
            listOf("TranscriptionEngine.kt"),
            callers
        )
    }

    // ---------------------------------------------------------------------------------------
    // The prose F5/F6 actually read
    // ---------------------------------------------------------------------------------------

    /**
     * Strips comment markers and collapses a doc/comment block to one whitespace-normalized line,
     * so the phrase assertions below are about the PROSE and not about where an editor happened to
     * wrap it. Without this, re-flowing a paragraph — which changes no meaning at all — fails the
     * assertions, and the usual repair is to weaken the phrases until they fit inside one line.
     */
    private fun flatten(block: String): String = block.lineSequence()
        .map { raw ->
            val t = raw.trim()
            when {
                t.startsWith("/**") -> t.removePrefix("/**")
                t.startsWith("//") -> t.removePrefix("//")
                t.startsWith("*/") -> t.removePrefix("*/")
                t.startsWith("*") -> t.removePrefix("*")
                else -> t
            }.trim()
        }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")

    /**
     * The KDoc block immediately above [declaration]. (The opening marker cannot be spelled out
     * in this comment: Kotlin NESTS block comments, so it would open one that never closes.)
     */
    private fun kdocFor(declaration: String): String {
        val at = engine.indexOf(declaration)
        assertTrue("TranscriptionEngine.kt does not declare \"$declaration\"", at >= 0)
        val head = engine.substring(0, at)
        val open = head.lastIndexOf("/**")
        assertTrue("no KDoc block opens above \"$declaration\"", open >= 0)
        val block = head.substring(open)
        assertTrue(
            "the KDoc scope for \"$declaration\" widened past a previous member: lastIndexOf " +
                "finds the NEAREST KDoc above the declaration, so deleting this one's doc " +
                "outright silently borrows the previous member's — and a phrase assertion could " +
                "then be satisfied by documentation written about something else. The tell is " +
                "that the text between that KDoc and this declaration is no longer just the " +
                "KDoc: nothing but whitespace may separate them.",
            block.trimEnd().endsWith("*/") && block.indexOf("*/") == block.lastIndexOf("*/")
        )
        return flatten(block)
    }

    /** The line-comment block immediately above [declaration], back to the previous blank line. */
    private fun commentAbove(declaration: String): String {
        val at = engine.indexOf(declaration)
        assertTrue("TranscriptionEngine.kt does not declare \"$declaration\"", at >= 0)
        val head = engine.substring(0, at)
        val blank = head.lastIndexOf("\n\n")
        assertTrue("no blank line precedes \"$declaration\"", blank >= 0)
        val block = head.substring(blank)
        assertTrue(
            "no comment block sits immediately above \"$declaration\". The scope is \"back up to " +
                "the previous blank line\", so DELETING the prose does not fail the phrase " +
                "assertions — it silently rebases onto whatever precedes it.",
            block.trimStart('\n').lineSequence().first().trimStart().startsWith("//")
        )
        return flatten(block)
    }

    @Test
    fun theDataClassDocumentsBothHonestyRules_theOnesF5AndF6Read() {
        val doc = kdocFor("data class NativeSegmentStats(")
        listOf(
            "\"unknown\" is a NULL, never zeros — the rule the whole type exists to protect" to
                "never by zeros",
            "zeros are a real READING (whisper_full never ran), not a missing one" to
                "is a real reading, not a missing one",
            "the two cases that produce zeros, named so they are recognisable in a capture" to
                "the energy gate fired",
            "NON-ZERO IS NOT SUCCESS: ctxFrames > 0 means the encoder was CONFIGURED, not that " +
                "the decode worked — the mirror of the same ruling on WhisperNative's own KDoc" to
                "CONFIGURED",
            "and what ctxFrames > 0 alongside EMPTY text actually means" to
                "failed whisper_full",
        ).forEach { (item, phrase) ->
            assertTrue(
                "NativeSegmentStats' KDoc must state $item. Expected the phrase \"$phrase\". " +
                    "This is the doc F5 and F6 read when they decide how to print these numbers; " +
                    "the rule is unenforceable by any signature and a reader who has not been " +
                    "told it will reasonably \"tidy up\" the zeros.",
                doc.contains(phrase)
            )
        }
    }

    @Test
    fun theSeamCommentNamesBothHalvesOfTheGuarantee() {
        val comment = commentAbove("@Volatile private var lastStats:")
        listOf(
            "COHERENCE comes from the gate HOLD, and that the single JNI round trip is necessary " +
                "but NOT sufficient — three relaxed native loads read outside a hold can " +
                "straddle another transcribe's writes" to "NOT sufficient",
            "the loads it depends on are RELAXED, which is why only the hold orders them" to
                "RELAXED",
            "READ CORRECTNESS comes from the ctx TAG" to "ctx TAG",
            "...PLUS the invalidation — the tag alone cannot survive a transcribe that threw" to
                "INVALIDATION",
            "the tag says which ctx last RAN, never which SEGMENT" to "which ctx last RAN",
            "the GPU canary IS a real transcribe on the ctx it just loaded, so its numbers are " +
                "legitimately tagged with that ctx until a real segment overwrites them" to
                "canary IS a real transcribe",
        ).forEach { (item, phrase) ->
            assertTrue(
                "the seam comment above the slot fields must state that $item. Expected the " +
                    "phrase \"$phrase\". Found:\n$comment",
                comment.contains(phrase)
            )
        }
    }
}
