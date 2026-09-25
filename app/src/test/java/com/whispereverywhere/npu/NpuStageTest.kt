package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [NpuStage] — the closed set of stages an NPU-class tier declines at — held to the decline sites
 * it names (P1a, the engine seam).
 *
 * **The derivation moved here with the enum.** Until P1a, `NpuDiagTest` re-derived the stage list
 * from the backend's own `fallBackToCpuTier("…")` / `fallBackAndRun("…")` call sites and held
 * `NpuDiag.unavailable`'s KDoc equal to it (4.1 L1 m5, closed at 4.2 F5 — the stale list it
 * replaced was off by seven). The enum is now the stage names' one home, so the same derivation
 * holds the ENUM equal to the sites here, and `NpuDiagTest` holds the KDoc equal to the enum: the
 * KDoc, the enum and the source cannot drift apart pairwise.
 *
 * **Across the seam.** Since the QNN-shaped stages moved into `QnnAsrEngine`, five of them decline
 * there, as [Refusal] values the backend routes through its one funnel. The derivation follows
 * them: the backend's literal sites as before, and at each engine call the stages that member can
 * refuse with, in order — so the ORDER pin still means "the order a session reaches them", which
 * is how it caught `quant` moving to arm time.
 *
 * **Two engines since P2-7.** `LiteRtAsrEngine` answers the same three members for MediaTek rows,
 * so the derivation runs once per session shape — a Qualcomm session through the QNN engine's
 * refusals, a MediaTek one through the LiteRT engine's — and `dispatch`, reserved at P1a for an
 * engine that did not exist yet, is that engine's prepare now.
 *
 * Source-anchored for the house reason: the backend and both engines reach native code, so no
 * JVM test may name them; the enum itself is plain Kotlin and is executed.
 */
class NpuStageTest {

    /** The house locator — walk up from the test's working directory; line endings normalised. */
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

    private fun isComment(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
    }

    /** [scope] without its comment lines: a decline site in a comment is not a decline site. */
    private fun live(scope: String): String =
        scope.split("\n").filterNot { isComment(it) }.joinToString("\n")

    /** How many LIVE lines of [scope] contain [needle]. */
    private fun liveLineCount(scope: String, needle: String): Int =
        scope.split("\n").count { !isComment(it) && it.contains(needle) }

    private val backend: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
    }

    private val stages: String by lazy {
        source("src/main/java/com/whispereverywhere/npu/NpuStage.kt")
    }

    /** The QNN engine — the other side of the seam, where five of the stages decline now. */
    private val qnnEngine: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/QnnAsrEngine.kt")
    }

    /**
     * The LiteRT engine (P2-7) — the seam's second engine, and the one whose prepare is the
     * `dispatch` stage the enum reserved for it until it landed. A MediaTek session reaches the
     * backend's stages through the same funnel, with THIS engine's refusals spliced in.
     */
    private val liteRtEngine: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/LiteRtAsrEngine.kt")
    }

    /**
     * One Kotlin member's body, bounded by the anchor's own indent (the house `kotlinMemberBody`
     * rule, 4.1 L1). Loud when the anchor is missing: `indexOf() == -1` would rebase the scope.
     */
    private fun memberBody(kt: String, anchor: String): String {
        val start = kt.indexOf(anchor)
        assertTrue("anchor \"$anchor\" is missing", start >= 0)
        val lineStart = kt.lastIndexOf('\n', start - 1) + 1
        val indent = kt.substring(lineStart, start).takeWhile { it == ' ' }.length
        val lines = kt.substring(start).split("\n")
        val body = StringBuilder(lines.first())
        var closed = false
        for (line in lines.drop(1)) {
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= indent) {
                closed = true
                break
            }
            body.append("\n").append(line)
        }
        assertTrue("nothing at or left of \"$anchor\"'s own indent follows it", closed)
        return body.toString()
    }

    /** The engine members whose refusals the backend routes, keyed by the backend's call name. */
    private val routedEngineMembers = mapOf(
        "prepare" to "override fun prepare(appContext: Context, family: NpuSocFamily): Refusal? {",
        "init" to "override fun init(spec: NpuModelSpec, files: NpuEngineFiles, dirs: NpuEngineDirs): Refusal? {",
        "encode" to "override fun encode(melF32: ByteBuffer): Refusal? {",
    )

    private val refusalSite = Regex("Refusal\\(\\s*NpuStage\\.([A-Z_]+)")

    /**
     * The wire words one engine member can refuse with, in its own source order — of the QNN
     * engine unless [engine] names the other one (P2-7: both engines answer the same three
     * members, under the same signatures, so one anchor table serves both).
     */
    private fun engineStages(member: String, engine: String = qnnEngine): List<String> =
        refusalSite.findAll(memberBody(live(engine), routedEngineMembers.getValue(member)))
            .map { NpuStage.valueOf(it.groupValues[1]).wire }
            .toList()

    /**
     * The stages a session can decline at, in the order it reaches them — `NpuDiagTest`'s 4.2 F5
     * derivation, carried across the engine seam (P1a).
     *
     * Every decline still funnels through `fallBackToCpuTier` / `fallBackAndRun` (the one-funnel
     * pins in `NpuDiagTest` prove the funnel). The backend's own stages reach it with a literal
     * first argument, exactly as before — the 4.2 F5 regex, verbatim, over live lines. The
     * engine's reach it as a [Refusal] from `engine.prepare(`, `engine.init(` or `engine.encode(`,
     * so at each of those call sites, in the backend's source order, the stages that engine member
     * can refuse with are spliced in, in ITS source order. First occurrence wins.
     *
     * [engine] is the session's engine — the QNN one by default, the Qualcomm session this
     * derivation was written for; the LiteRT one for a MediaTek session (P2-7).
     */
    private fun derivedDeclineOrder(engine: String = qnnEngine): List<String> {
        val site = Regex(
            "fallBack(?:ToCpuTier|AndRun)\\(\\s*\"([a-z-]+)\"|engine\\.(prepare|init|encode)\\("
        )
        val out = mutableListOf<String>()
        site.findAll(live(backend)).forEach { match ->
            val literal = match.groups[1]?.value
            if (literal != null) out += literal else out += engineStages(match.groupValues[2], engine)
        }
        return out.distinct()
    }

    @Test
    fun theEnumIsTheDeclineSitesReDerivedInDeclineOrderPlusTheReservedDispatch() {
        val derived = derivedDeclineOrder()
        assertTrue(
            "the derivation found a real population (got $derived)",
            derived.size >= 10 && "encode" in derived && "decode" in derived,
        )
        assertEquals(
            "NpuStage, less DISPATCH (the LiteRT engine's prepare since P2-7 — a Qualcomm session " +
                "never reaches it), is EXACTLY the stages a QUALCOMM session's decline sites " +
                "produce, in the order it reaches them — re-derived, never retyped. A stage added " +
                "to the backend and not to the enum, one renamed on either side, or one retired " +
                "from the backend and left in the enum all fail here by name.",
            derived,
            NpuStage.entries.filter { it != NpuStage.DISPATCH }.map { it.wire },
        )
        assertEquals(
            "DISPATCH sits directly after SKEL: the two are ONE stage of load for two vendors — the " +
                "runtime's own staging, after every cheap refusal and before the expensive init",
            NpuStage.entries.indexOf(NpuStage.SKEL) + 1,
            NpuStage.entries.indexOf(NpuStage.DISPATCH),
        )
    }

    /**
     * RESERVED UNTIL P2-7, AND RE-SPELLED WHEN ITS TRIGGER FIRED, not deleted. `dispatch` is the
     * LiteRT engine's prepare stage — `LiteRtAsrEngine.prepare`, staging the MediaTek dispatch
     * through P2-6's directory stage — and the named trigger was exactly this: "when an engine
     * that stages a dispatch lands, the derivation above must learn that engine's file and this
     * exemption goes". The derivation learned it ([theLiteRtSessionDeclinesAtTheEnumLessTheQnnStagesAndTheTwoSessionsTogetherAreTheWholeEnum]),
     * and what this test holds now is the claim that survives the exemption: no QUALCOMM site
     * produces it (a live `NpuStage.DISPATCH` on the Qualcomm path would be a refusal naming a
     * runtime the device does not have), and the LiteRT engine produces it in prepare and
     * nowhere else.
     */
    @Test
    fun dispatchIsReservedForTheLiteRtEngineAndNoQualcommSiteProducesIt() {
        assertFalse(
            "no decline site on the Qualcomm path produces `dispatch`",
            "dispatch" in derivedDeclineOrder(),
        )
        assertEquals(
            "and neither the backend nor the QNN engine names NpuStage.DISPATCH on a live line",
            0,
            liveLineCount(backend, "NpuStage.DISPATCH") + liveLineCount(qnnEngine, "NpuStage.DISPATCH"),
        )
        assertTrue(
            "the LiteRT engine's session DOES reach it — the trigger this test named has fired",
            "dispatch" in derivedDeclineOrder(liteRtEngine),
        )
        assertEquals(
            "…from prepare alone: every NpuStage.DISPATCH in LiteRtAsrEngine.kt is one of " +
                "prepare's refusals (the row of another vendor, the row it was not built for, the " +
                "stage that could not stage)",
            listOf("dispatch", "dispatch", "dispatch"),
            engineStages("prepare", liteRtEngine),
        )
        assertEquals(
            "and no other member of the LiteRT engine spells it",
            3,
            liveLineCount(liteRtEngine, "NpuStage.DISPATCH"),
        )
    }

    /**
     * THE SECOND SESSION'S DECLINE ORDER (P2-7). A MediaTek session runs the same backend — the
     * same literal stages, in the same order — with the LiteRT engine's refusals spliced in at the
     * three engine calls: `dispatch` where the QNN session has `skel`, `init` alone where it has
     * `init` then `quant`, and `encode`. So its derivation is the enum less the two QNN-only
     * stages, in the enum's order — and the two sessions together are exactly the enum, which is
     * what "closed" means now that nothing in it is reserved.
     */
    @Test
    fun theLiteRtSessionDeclinesAtTheEnumLessTheQnnStagesAndTheTwoSessionsTogetherAreTheWholeEnum() {
        val liteRt = derivedDeclineOrder(liteRtEngine)
        assertEquals(
            "a MediaTek session reaches every stage but the QNN engine's own two (skel, quant), " +
                "in the enum's declaration order — which is decline order",
            NpuStage.entries.filter { it != NpuStage.SKEL && it != NpuStage.QUANT }.map { it.wire },
            liteRt,
        )
        val qnn = derivedDeclineOrder()
        assertEquals(
            "and the two sessions' stages, together, are EXACTLY the enum — no member is reserved " +
                "for an engine that does not exist, and none is produced by nothing",
            NpuStage.entries.map { it.wire }.toSet(),
            (qnn + liteRt).toSet(),
        )
        assertEquals(
            "the LiteRT engine refuses at exactly one stage per member it answers: prepare at " +
                "dispatch, init at init (no quant on this vendor — the pair is float at its " +
                "boundary), encode at encode",
            listOf(listOf("dispatch"), listOf("init"), listOf("encode")),
            listOf("prepare", "init", "encode").map { engineStages(it, liteRtEngine).distinct() },
        )
    }

    /**
     * ONE HOME PER STAGE WORD, across the seam (P1a review). The derivation above ends in
     * `.distinct()` — first occurrence wins — so on its own it cannot see a stage produced on BOTH
     * sides: a `fallBackToCpuTier("skel", …)` added to the backend's load would merge with the
     * engine's SKEL refusal and pass. At 4a7c126 the skel pin held `"skel",` to exactly one live
     * line ("two spellings would be two stories"), and this is that guarantee for every stage the
     * seam split: the words the backend spells as literals and the words any engine member refuses
     * with are DISJOINT — `skel`, `init`, `quant` and `encode` live on the engine side only, and
     * the rest on the backend's.
     */
    @Test
    fun theBackendsLiteralStagesAndTheEnginesRefusalStagesAreDisjoint() {
        val literals = Regex("fallBack(?:ToCpuTier|AndRun)\\(\\s*\"([a-z-]+)\"")
            .findAll(live(backend)).map { it.groupValues[1] }.toSet()
        val engineWords = refusalSite.findAll(live(qnnEngine))
            .map { NpuStage.valueOf(it.groupValues[1]).wire }.toSet()
        assertTrue(
            "both sides decline at something (backend $literals, engine $engineWords)",
            literals.isNotEmpty() && engineWords.isNotEmpty(),
        )
        assertEquals(
            "no stage word is produced on both sides of the seam — a word spelled as a literal in " +
                "the backend AND refused with by the engine is one stage telling two stories, and " +
                "the derivation's distinct() would merge them silently. Shared: " +
                "${literals intersect engineWords}",
            emptySet<String>(),
            literals intersect engineWords,
        )
        assertEquals(
            "the engine's side is exactly the four stages the seam moved there",
            setOf("skel", "init", "quant", "encode"),
            engineWords,
        )
        // P2-7 — THE RULE HOLDS WITH THE SECOND ENGINE. The LiteRT engine's refusal words are
        // disjoint from the backend's literals too, so a MediaTek session's `init` or `encode`
        // tells one story exactly as a Qualcomm one does; the two ENGINES share words (both arm
        // at `init`, both encode at `encode`) because those are one stage of the one policy
        // body, answered by whichever runtime the row names.
        val liteRtWords = refusalSite.findAll(live(liteRtEngine))
            .map { NpuStage.valueOf(it.groupValues[1]).wire }.toSet()
        assertEquals(
            "no stage word is produced both by the backend and by the LiteRT engine. Shared: " +
                "${literals intersect liteRtWords}",
            emptySet<String>(),
            literals intersect liteRtWords,
        )
        assertEquals(
            "the LiteRT engine's side is exactly its three: its own staging, its arm, its encode",
            setOf("dispatch", "init", "encode"),
            liteRtWords,
        )
    }

    /**
     * `"${refusal.stage}"` IS THE WIRE WORD (P1a review). `Enum.toString()` defaults to `name`, so
     * a template that interpolated the stage itself — or a [Refusal] logged whole — would print
     * `SKEL` beside every `stage=skel` a device has ever printed. The override closes that; `.name`
     * stays the one spelling of the identifier, and the funnel pin below keeps it off the backend.
     */
    @Test
    fun aStageRendersAsItsWireWordWhereverItIsInterpolated() {
        NpuStage.entries.forEach { stage ->
            assertEquals("`\$stage` for ${stage.name} is its wire word", stage.wire, "$stage")
        }
        assertEquals(
            "and a Refusal rendered whole carries the word too",
            "Refusal(stage=skel, detail=libQnnHtpV75Skel.so could not be staged)",
            Refusal(NpuStage.SKEL, "libQnnHtpV75Skel.so could not be staged").toString(),
        )
        assertEquals(
            "the override is declared once, as the wire word itself",
            1,
            liveLineCount(stages, "override fun toString(): String = wire"),
        )
    }

    /**
     * THE DERIVATION'S OWN COMPLETENESS (P1a): it can only see the engine refusals it reads, so
     * every `Refusal(` in the QNN engine must sit inside one of the three members the backend
     * routes — a refusal built in a helper, or in a member the backend never checks, would be a
     * stage the derivation cannot see and the funnel never prints.
     */
    @Test
    fun everyEngineRefusalIsBuiltInAMemberTheBackendRoutes() {
        val all = refusalSite.findAll(live(qnnEngine)).count()
        val routed = routedEngineMembers.keys.sumOf { engineStages(it).size }
        assertTrue("the engine refuses at all (got $all)", all > 0)
        assertEquals(
            "every Refusal(NpuStage.…) in QnnAsrEngine.kt is inside prepare, init or encode",
            all,
            routed,
        )
        assertEquals(
            "and every `Refusal(` the engine builds names its stage from the closed set — none " +
                "is built from a value the derivation cannot read",
            all,
            Regex("Refusal\\(").findAll(live(qnnEngine)).count(),
        )
        // P2-7 — and the same completeness for the LiteRT engine: its probe answers native's
        // string, never a Refusal, so every Refusal it builds is one the backend routes.
        val liteRtAll = refusalSite.findAll(live(liteRtEngine)).count()
        assertTrue("the LiteRT engine refuses at all (got $liteRtAll)", liteRtAll > 0)
        assertEquals(
            "every Refusal(NpuStage.…) in LiteRtAsrEngine.kt is inside prepare, init or encode",
            liteRtAll,
            routedEngineMembers.keys.sumOf { engineStages(it, liteRtEngine).size },
        )
        assertEquals(
            "and every `Refusal(` it builds names its stage from the closed set",
            liteRtAll,
            Regex("Refusal\\(").findAll(live(liteRtEngine)).count(),
        )
    }

    /**
     * THE FUNNEL'S LITERAL SHAPE (P1a): each engine refusal reaches `fallBackToCpuTier` /
     * `fallBackAndRun` as `refusal.stage.wire` and `refusal.detail`, so the diag readers — the
     * `npu: unavailable stage=… detail=…` line, the card's `"stage: detail"`, `stageOf` — see
     * exactly the text they saw before the seam. `stage.name` is the one spelling that would
     * compile and print `stage=SKEL`.
     */
    @Test
    fun theBackendRoutesEveryEngineRefusalThroughTheOneFunnelByItsWireWord() {
        val flat = live(backend).replace(Regex("\\s+"), " ")
        listOf(
            "engine.prepare(appContext, family)?.let { refusal -> " +
                "return@serialized fallBackToCpuTier(refusal.stage.wire, refusal.detail) }",
            "engine.init( spec, NpuEngineFiles(encoderPath = modelPath, decoderPath = companionPath), " +
                "NpuEngineDirs(libDir = libDir(), filesDir = appContext.filesDir.absolutePath), " +
                ")?.let { refusal -> return@serialized fallBackToCpuTier(refusal.stage.wire, refusal.detail) }",
            "engine.encode(mel)?.let { refusal -> " +
                "return@serialized fallBackAndRun(refusal.stage.wire, refusal.detail, samples, lang, useVad) }",
        ).forEach { needle ->
            assertEquals(
                "the backend routes `${needle.substringBefore("(")}`'s refusal through the one " +
                    "funnel by its wire word, exactly once",
                1,
                flat.split(needle).size - 1,
            )
        }
        listOf("engine.prepare(", "engine.init(", "engine.encode(").forEach { call ->
            assertEquals("one live `$call` site in the backend", 1, liveLineCount(backend, call))
        }
        assertEquals(
            "no live line of the backend prints an enum's NAME — `stage.name` is `SKEL`, and the " +
                "line has always said `stage=skel`",
            0,
            liveLineCount(backend, ".stage.name") + liveLineCount(backend, "stage.name)"),
        )
        assertEquals(
            "the funnel keeps its (String, String) shape, which every diag pin anchors on",
            1,
            liveLineCount(backend, "private fun fallBackToCpuTier(stage: String, detail: String): Long {"),
        )
    }

    /**
     * THE WORDS A DEVICE PRINTS, as hard literals on purpose — NOT derived from the enum or the
     * source. The derivation above proves the enum and the source agree; only this proves that
     * both still say what every device said before the seam, which is the property the owner's
     * Qualcomm regression gate compares (`npu: unavailable stage=…`, character for character). A
     * co-mutation that renamed a stage in the backend and the enum together passes the derivation
     * and dies here.
     */
    @Test
    fun everyWireWordIsTheWordADeviceHasAlwaysPrinted() {
        assertEquals(
            "the fourteen stage words the 4.2 F5 derivation produced before the seam, plus " +
                "`dispatch` (reserved at P1a, the LiteRT engine's prepare since P2-7) — and " +
                "nothing else",
            setOf(
                "companion", "mel-donor", "mel-asset", "mel-init", "vocab", "skel", "init",
                "epoch", "session", "mel", "quant", "encode", "lang", "decode", "dispatch",
            ),
            NpuStage.entries.map { it.wire }.toSet(),
        )
        assertEquals(
            "no two constants share a word — the card and the log join on it",
            NpuStage.entries.size,
            NpuStage.entries.map { it.wire }.toSet().size,
        )
    }

    /**
     * The word is spelled where it is defined, and by one rule. A `wire` computed from `name`
     * would make `"mel-donor"` appear nowhere in the source — the grep a support reply starts
     * from — and `name` itself (`MEL_DONOR`) is not the word at all: a funnel handed
     * `stage.name` prints `stage=MEL_DONOR` on every device.
     */
    @Test
    fun eachWireWordIsALiteralBesideItsConstantAndIsItsNameLowerCased() {
        NpuStage.entries.forEach { stage ->
            assertEquals(
                "`${stage.name}(\"${stage.wire}\"),` on exactly one live line of NpuStage.kt",
                1,
                liveLineCount(stages, "${stage.name}(\"${stage.wire}\"),"),
            )
            assertEquals(
                "`${stage.name}`'s word is its name lower-cased, '_' to '-' — one spelling rule, so " +
                    "no constant can carry a neighbour's word",
                stage.name.lowercase().replace('_', '-'),
                stage.wire,
            )
        }
        assertEquals(
            "Refusal is the design's shape exactly (§2.4) — a stage from the closed set and a " +
                "non-null one-line detail; a String stage would reopen the set at every call site",
            1,
            liveLineCount(stages, "data class Refusal(val stage: NpuStage, val detail: String)"),
        )
    }

    /** The rendering the diag readers see: a refusal's word is the line's word, unchanged. */
    @Test
    fun aRefusalRendersTheUnavailableLineAndTheCardStageExactlyAsBeforeTheSeam() {
        assertEquals(
            "npu: unavailable stage=mel-donor detail=no installed 80-bin whisper model to take the " +
                "mel filterbank from",
            NpuDiag.unavailable(
                NpuStage.MEL_DONOR.wire,
                "no installed 80-bin whisper model to take the mel filterbank from",
            ),
        )
        val refusal = Refusal(NpuStage.SKEL, "libQnnHtpV75Skel.so (family 8gen3) could not be staged")
        assertEquals(
            "npu: unavailable stage=skel detail=libQnnHtpV75Skel.so (family 8gen3) could not be staged",
            NpuDiag.unavailable(refusal.stage.wire, refusal.detail),
        )
        assertEquals(
            "and the card recovers the same one word from the published reason",
            "skel",
            NpuTierStatus.stageOf("${refusal.stage.wire}: ${refusal.detail}"),
        )
    }

    /**
     * The seam's sources are inputs of the test task — the list's own rule, membership follows
     * what the tests READ. Each is read as text here or beside its pins, and a comment-shaped
     * edit compiles to identical bytes, so without its entry the one edit a pin exists to catch
     * is the one that leaves `:app:testDebugUnitTest` UP-TO-DATE.
     */
    @Test
    fun theSeamsSourcesAreInputsOfTheTestTask() {
        val gradle = source("build.gradle.kts")
        listOf(
            "\"src/main/java/com/whispereverywhere/npu/NpuStage.kt\",",
            "\"src/main/java/com/whispereverywhere/transcription/NpuAsrEngine.kt\",",
            "\"src/main/java/com/whispereverywhere/transcription/QnnAsrEngine.kt\",",
            "\"src/main/java/com/whispereverywhere/transcription/LiteRtAsrEngine.kt\",",
        ).forEach {
            assertEquals(
                "app/build.gradle.kts must list $it among sourcePinnedInputs",
                1,
                liveLineCount(gradle, it),
            )
        }
    }
}
