package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE LITERT ENGINE'S CONTRACT (P2-7 — P1b's Kotlin half; design §2.4–2.6), pinned as source in
 * the `NpuNativeContractTest` style.
 *
 * WHY SOURCE: `LiteRtAsrEngine` touches `LiteRtAsrNative`, whose `init` block runs
 * `System.loadLibrary("litertasr")` — there is no `liblitertasr.so` on the unit-test classpath, so
 * a JVM test that NAMED the engine would not fail, it would die. What the engine must do is a
 * handful of constructs a device would otherwise be the first to check: which entry points it
 * reaches and with what, the one dispatch directory, the stage each member refuses at, the teardown
 * it may make. Every pin is anchored to content and scoped to LIVE lines (a commented-out call
 * satisfies `contains()` as happily as the call); the one whole-file, comment-inclusive pin is the
 * digest's, because a comment that re-teaches a digest is how a second copy comes back.
 *
 * `LiteRtAsrEngine.kt` is in `sourcePinnedInputs` (NpuStageTest pins the entry); the other sources
 * read here already were.
 */
class LiteRtAsrEngineContractTest {

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

    /** The LIVE lines of [scope] containing [needle], trimmed. */
    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").filterNot { isComment(it) }.map { it.trim() }.filter { it.contains(needle) }

    /** Character offsets of the LIVE lines of [scope] containing [needle]. */
    private fun liveOffsets(scope: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var at = 0
        for (line in scope.split("\n")) {
            if (!isComment(line) && line.contains(needle)) out += at
            at += line.length + 1
        }
        return out
    }

    /** [scope]'s live lines, whitespace collapsed: the pins are about text, not wrapping. */
    private fun collapsed(scope: String): String =
        scope.split("\n").filterNot { isComment(it) }.joinToString(" ").replace(Regex("\\s+"), " ")

    /**
     * One member's body, bounded by the anchor's own indent — the house `kotlinMemberBody` rule
     * (4.1 L1): it ends at the first following non-blank line indented no further than the
     * anchor's. Loud when the anchor is missing or never closes.
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

    private val engine: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/LiteRtAsrEngine.kt")
    }

    private val native: String by lazy {
        source("src/main/java/com/whispereverywhere/npu/LiteRtAsrNative.kt")
    }

    private val qnnEngine: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/QnnAsrEngine.kt")
    }

    /** `LiteRtAsrNative`'s externals, in declaration order, KDoc lines excluded. */
    private val externals: List<String> by lazy {
        Regex("external fun (\\w+)\\(")
            .findAll(native.split("\n").filterNot { isComment(it) }.joinToString("\n"))
            .map { it.groupValues[1] }
            .toList()
    }

    private val prepare: String by lazy {
        memberBody(engine, "override fun prepare(appContext: Context, family: NpuSocFamily): Refusal? {")
    }

    private val init: String by lazy {
        memberBody(engine, "override fun init(spec: NpuModelSpec, files: NpuEngineFiles, dirs: NpuEngineDirs): Refusal? {")
    }

    private val encode: String by lazy {
        memberBody(engine, "override fun encode(melF32: ByteBuffer): Refusal? {")
    }

    /**
     * **The engine is an `NpuAsrEngine`, built from the row and the lib dir, and it reaches every
     * one of `LiteRtAsrNative`'s nine entry points.** An entry point no Kotlin reaches is either a
     * dead native symbol or a call the engine DROPPED — `nativeSetDiag` gone would silence every
     * `npu-debug:` line of the MediaTek tier with everything else still green (the QNN pin's
     * reasoning, `NpuNativeContractTest`).
     */
    @Test
    fun theEngineIsAnNpuAsrEngineAndReachesEveryLiteRtEntryPoint() {
        assertEquals(
            "LiteRtAsrNative declares the nine entry points the seam was cut against",
            listOf(
                "nativeProbe", "nativeInit", "nativeEncode", "nativeDecodeSegment", "nativeDetectLanguage",
                "nativeSetDiag", "nativeLastError", "nativeEpoch", "nativeRelease",
            ),
            externals,
        )
        externals.forEach { name ->
            assertTrue(
                "LiteRtAsrEngine must call LiteRtAsrNative.$name on a live line",
                liveOffsets(engine, "LiteRtAsrNative.$name(").isNotEmpty(),
            )
        }
        assertTrue(
            "the engine is built from what the seam's members do not carry — the census row and " +
                "the lib dir, both required (no default: the spec/family doctrine)",
            collapsed(engine).contains(
                "class LiteRtAsrEngine( private val family: NpuSocFamily, private val libDir: String, ) : NpuAsrEngine {"
            ),
        )
        assertEquals(
            "no parameter of the constructor has a default",
            emptyList<String>(),
            liveLines(engine, "family: NpuSocFamily =") + liveLines(engine, "libDir: String ="),
        )
        assertEquals(
            "the row's LiteRT needs are read by an exhaustive `when` on its sealed runtime, never " +
                "a cast — a Qualcomm row is refused by name at each member that needs them",
            listOf(0, 1, 1),
            listOf(
                liveLines(engine, " as NpuRuntimeNeeds").size,
                liveLines(engine, "private val needs: NpuRuntimeNeeds.LiteRtMediatek? = when (val runtime = family.runtime) {").size,
                liveLines(engine, "is NpuRuntimeNeeds.Qnn -> null").size,
            ),
        )
    }

    /**
     * **…and from nowhere else in main** (P2-7). `LiteRtAsrNative` is a process-global session
     * behind one mutex: a second Kotlin caller is a second, unguarded path to it — around the
     * engine's refusals, its dispatch stage, the backend's arming epoch. The app's driver check
     * reaches the probe THROUGH the engine (`LiteRtAsrEngine(family, lib).probe(dispatchDir)`), so
     * this file is the ONE main-source file with a live `LiteRtAsrNative.` call. (The probe app in
     * `tools/probes/` calls it directly by design: it is the device gate's harness, outside main.)
     */
    @Test
    fun liteRtAsrNativeIsCalledFromTheEngineAndFromNowhereElseInMain() {
        val mainRoot = run {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            var found: File? = null
            while (dir != null && found == null) {
                found = listOf(File(dir, "src/main/java"), File(dir, "app/src/main/java"))
                    .firstOrNull { File(it, "com/whispereverywhere/npu/LiteRtAsrNative.kt").isFile }
                dir = dir.parentFile
            }
            requireNotNull(found) { "cannot locate src/main/java from ${System.getProperty("user.dir")}" }
        }
        val callers = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { liveOffsets(it.readText().replace("\r\n", "\n"), "LiteRtAsrNative.").isNotEmpty() }
            .map { it.name }
            .sorted()
            .toList()
        assertEquals(
            "LiteRtAsrEngine.kt is the ONE main-source file with a live LiteRtAsrNative call. Found: $callers",
            listOf("LiteRtAsrEngine.kt"),
            callers,
        )
        val app = source("src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt")
        assertEquals(
            "the app's driver check reaches the probe through the engine, once",
            1,
            liveLines(app, "LiteRtAsrEngine(family, lib).probe(dispatchDir)").size,
        )
    }

    /**
     * **`nativeInit` is handed its thirteen arguments in native's order — the five varying scalars
     * off the spec, the row's stamp and major — and the two defaults as LITERALS at the call**:
     * `performanceMode = -1` (LiteRT's default, inert on 2.1.1 AOT) and `selfKvStrategy = 1` (one
     * self-KV set copied back, P1's device gate's choice). Four adjacent `Int` scalars and two
     * adjacent trailing `Int`s: a transposition compiles, and native's census would refuse only
     * some of them.
     */
    @Test
    fun nativeInitIsHandedTheFiveScalarsInNativesOrderAndTheTwoDefaultsAsLiterals() {
        val declared = collapsed(native).substringAfter("external fun nativeInit(").substringBefore("): String")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.substringBefore(":").trim() }
        assertEquals(
            "native's own parameter order",
            listOf(
                "encoderPath", "decoderPath", "dispatchDir", "libDir", "melBins", "decLayers", "heads",
                "vocab", "maxPositions", "socStamp", "wantMajor", "performanceMode", "selfKvStrategy",
            ),
            declared,
        )
        assertEquals(
            "exactly one nativeInit call in the engine",
            1,
            liveLines(engine, "LiteRtAsrNative.nativeInit(").size,
        )
        val passed = collapsed(init).substringAfter("LiteRtAsrNative.nativeInit(").substringBefore(")").trim()
        assertEquals(
            "…handed, in that order: the two files by name, the one dispatch directory, the " +
                "backend's lib dir, the spec's five, the row's stamp and major, and the two literals",
            "files.encoderPath, files.decoderPath, dispatchDir, dirs.libDir, spec.melBins, spec.decLayers, " +
                "spec.heads, spec.tokens.vocab, spec.maxPositions, needs.socStamp, needs.neuronMajor, " +
                "performanceMode = -1, selfKvStrategy = 1,",
            passed,
        )
        assertTrue(
            "and the five scalars are the QNN engine's five, in the same order — one spec, read one " +
                "way by both engines",
            collapsed(qnnEngine).contains(
                "spec.melBins, spec.decLayers, spec.heads, spec.tokens.vocab, spec.maxPositions, )"
            ),
        )
        assertEquals(
            "the two defaults are spelled once each, at the call — never a constant a caller could " +
                "move, and never read back from anywhere",
            listOf(1, 1),
            listOf(
                liveLines(engine, "performanceMode = -1,").size,
                liveLines(engine, "selfKvStrategy = 1,").size,
            ),
        )
        assertTrue(
            "a throw from the first touch (liblitertasr.so absent) is an init refusal, not a crash",
            collapsed(init).contains(
                "}.getOrElse { cause -> \"init: \${cause.javaClass.simpleName}: \${cause.message}\" }"
            ),
        )
    }

    /**
     * **The dispatch directory has one home and the dispatch one stage** — the review's finding
     * that it would otherwise be derived in three places, and the launch rule that prepare use
     * P2-6's stage exactly: `prepare` stages through [com.whispereverywhere.npu.LiteRtRuntime.stagedDispatchDir]
     * (`NpuAssetStage`'s directory overload — markers outside the scanned directory — with
     * `LiteRtRuntime`'s asset name, length and digest), and `init` derives the SAME directory from
     * the same `filesDir` through `NpuApuDriverCheck.dispatchDir`, the function the app's driver
     * probe takes too. No second spelling of the path, and no copy of the digest, anywhere in the
     * engine.
     */
    @Test
    fun theDispatchIsStagedByP26sStageIntoTheOneDirectoryInitAlsoNames() {
        assertEquals(
            "prepare stages through LiteRtRuntime's one Context entry point, exactly once",
            listOf(1, 1),
            listOf(
                liveLines(prepare, "LiteRtRuntime.stagedDispatchDir(appContext) ?: return Refusal(").size,
                liveLines(engine, "LiteRtRuntime.stagedDispatchDir(").size,
            ),
        )
        assertEquals(
            "…and never reaches the stage around it: no NpuAssetStage call of its own, so the " +
                "markers can only be where P2-6's overload keeps them",
            0,
            liveLines(engine, "NpuAssetStage.").size,
        )
        assertEquals(
            "init derives the directory through its one home, from the backend's filesDir",
            1,
            liveLines(init, "val dispatchDir = NpuApuDriverCheck.dispatchDir(File(dirs.filesDir)).absolutePath").size,
        )
        assertEquals(
            "the engine never spells the directory's name on a live line",
            0,
            liveLines(engine, "\"litert_dispatch").size,
        )
        listOf("9e963c56", "409_728", "409728", "f47bd9c0").forEach { copy ->
            assertEquals(
                "no copy of the dispatch's identity (`$copy`) anywhere in the engine — not in code, " +
                    "not in a comment: LiteRtRuntime holds it once",
                0,
                engine.split(copy).size - 1,
            )
        }
    }

    /**
     * **Each member refuses at its own stage, and the refusals come before the work** — `dispatch`
     * in prepare (the stage word reserved for this engine at P1a), `init` in init (the chip-stamp
     * mismatch included: native's own detail, under the arm's word — there is no quant stage on
     * this vendor), `encode` in encode. In prepare the two wiring refusals — a row of another
     * vendor, a row the engine was not built for — sit ABOVE the staging call, so neither writes a
     * byte into `filesDir`.
     */
    @Test
    fun eachMemberRefusesAtItsOwnStageAndPrepareRefusesBeforeItStages() {
        assertEquals(
            "prepare's refusals are DISPATCH, three of them",
            3,
            liveLines(prepare, "NpuStage.DISPATCH").size,
        )
        assertEquals(
            "init's are INIT, two of them — the row of another vendor, and nativeInit's own answer",
            listOf(1, 1),
            listOf(
                liveLines(init, "val needs = needs ?: return Refusal(NpuStage.INIT, \"init: \${notThisVendor()}\")").size,
                liveLines(init, "return Refusal(NpuStage.INIT, initError)").size,
            ),
        )
        assertEquals(
            "encode's is ENCODE, carrying native's text",
            1,
            liveLines(encode, "return Refusal(NpuStage.ENCODE, encodeError)").size,
        )
        assertEquals(
            "and no stage of the other engine is spelled here",
            0,
            liveLines(engine, "NpuStage.SKEL").size + liveLines(engine, "NpuStage.QUANT").size,
        )
        val otherVendor = liveOffsets(prepare, "is NpuRuntimeNeeds.Qnn -> return Refusal(")
        val otherRow = liveOffsets(prepare, "if (family !== this.family) {")
        val stage = liveOffsets(prepare, "LiteRtRuntime.stagedDispatchDir(appContext)")
        assertTrue(
            "ORDER: the other vendor's row ($otherVendor) and the other family's row ($otherRow) are " +
                "refused before the stage ($stage)",
            otherVendor.size == 1 && otherRow.size == 1 && stage.size == 1 &&
                otherVendor[0] < otherRow[0] && otherRow[0] < stage[0],
        )
        assertTrue(
            "the Qualcomm arm refuses on the runtime's own exhaustive `when`, as the QNN engine's " +
                "prepare refuses a MediaTek row",
            collapsed(prepare).contains(
                "val liteRt = when (val runtime = family.runtime) { is NpuRuntimeNeeds.LiteRtMediatek -> runtime " +
                    "is NpuRuntimeNeeds.Qnn -> return Refusal( NpuStage.DISPATCH,"
            ),
        )
    }

    /**
     * **The encoder is fed the float mel as it is**: the buffer the backend's `pcmToMel` wrote is
     * handed straight to `nativeEncode` — no `NpuQuantize`, no second buffer, no melprobe. The QNN
     * engine's `melToU16` is that engine's own; on this vendor the pair is float at its boundary.
     */
    @Test
    fun theEncoderIsHandedTheFloatMelAndNothingIsQuantised() {
        assertEquals(
            "encode hands its argument to nativeEncode, unchanged",
            1,
            liveLines(encode, "val encodeError = LiteRtAsrNative.nativeEncode(melF32)").size,
        )
        listOf("NpuQuantize", "melToU16", "asShortBuffer", "allocateDirect", "melProbe").forEach {
            assertEquals("no `$it` anywhere on a live line of the engine", 0, liveLines(engine, it).size)
        }
        val backend = source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
        assertTrue(
            "and what the backend hands the seam is its one float mel buffer — melFloatBytes, direct, " +
                "native order — the one pcmToMel fills",
            liveLines(backend, "engine.encode(mel)?.let { refusal ->").size == 1 &&
                liveLines(backend, "melBuffer = NpuQuantize.newMelFloatBuffer(spec)").size == 1 &&
                liveLines(backend, "val mel = melBuffer").size == 1,
        )
    }

    /** decodeSegment's twelve, handed on in native's order by name — the QNN engine's pin, twinned. */
    @Test
    fun decodeSegmentDelegatesItsTwelveArgumentsInNativesOrder() {
        val delegate = engine.substringAfter("override fun decodeSegment(").substringBefore("override fun detectLanguage(")
        val passed = delegate.substringAfter("LiteRtAsrNative.nativeDecodeSegment(").replace(Regex("\\s+"), " ").trim()
        assertTrue(
            "LiteRtAsrEngine.decodeSegment passes its twelve parameters through in native's order, " +
                "by name. Found: ${passed.take(200)}",
            passed.startsWith(
                "prompt, suppress, beginSuppress, maxTokens, out, temperatures, entropyThold, " +
                    "logprobThold, noSpeechThold, noSpeechToken, cycleMaxDistinct, stats, )"
            ),
        )
        listOf(
            "override fun detectLanguage(): Int = LiteRtAsrNative.nativeDetectLanguage()",
            "override fun epoch(): Long = LiteRtAsrNative.nativeEpoch()",
            "override fun lastError(): String = LiteRtAsrNative.nativeLastError()",
            "override fun setDiag(on: Boolean) = LiteRtAsrNative.nativeSetDiag(on)",
        ).forEach { assertEquals("one-to-one: `$it`", 1, liveLines(engine, it).size) }
        assertEquals(
            "and the probe is nativeProbe over the handed directory, this engine's lib dir and the " +
                "row's major — native's refusal text returned as it is",
            1,
            liveLines(engine, "return LiteRtAsrNative.nativeProbe(dispatchOrLibDir, libDir, needs.neuronMajor)").size,
        )
    }

    /**
     * **Lifecycle (design §2.6): `release` frees the compiled models and their buffers, and
     * nothing on this side of the seam can destroy the environment.** The LiteRT environment, the
     * dispatch and the adapter handles are process state — a re-arm after a trim pays the two
     * bytecode restores and never the walk. `litert_asr.cpp`'s half is `LiteRtNativeContractTest`'s
     * (`LiteRtDestroyEnvironment` not even resolved, no `dlclose`); this is the Kotlin half: no
     * entry point that could tear the environment down exists, and the engine's ONE teardown is
     * `nativeRelease` of the epoch it is handed — counted over every `release(` in the file, the
     * QNN engine's teardown census.
     */
    @Test
    fun releaseFreesTheSessionOnlyAndNothingOnTheKotlinSideCanDestroyTheEnvironment() {
        externals.forEach { name ->
            assertTrue(
                "no LiteRtAsrNative entry point is a teardown of process state: `$name`",
                !Regex("(?i)destroy|close|shutdown|teardown|environment|unload").containsMatchIn(name),
            )
        }
        assertEquals(
            "exactly one live `release(` line in LiteRtAsrEngine.kt — the override, delegating the " +
                "epoch it was handed. Found: ${liveLines(engine, "release(")}",
            listOf("override fun release(epoch: Long) = LiteRtAsrNative.nativeRelease(epoch)"),
            liveLines(engine, "release("),
        )
        assertEquals(
            "…and one nativeRelease in the engine at all",
            1,
            liveLines(engine, "LiteRtAsrNative.nativeRelease(").size,
        )
        listOf("Destroy", "destroy", "dlclose", "releaseEnvironment").forEach {
            assertEquals("no `$it` on a live line of the engine", 0, liveLines(engine, it).size)
        }
        assertTrue(
            "and native's KDoc says what the release leaves: the environment, libLiteRt.so and the " +
                "adapter are NOT released",
            native.contains("The environment, `libLiteRt.so` and the Neuron adapter are NOT released"),
        )
    }

    /**
     * **Diag discipline, the QNN engine's: the engine emits no line of its own.** The `apu:`
     * driver line is native's (the probe prints it), native prints the init, encode and decode
     * lines on `WE-DIAG`, and every policy line is the backend's.
     */
    @Test
    fun theEngineEmitsNoDiagLineOfItsOwn() {
        listOf("Log.", "NpuDiag.", "WhisperNative.", "println(").forEach {
            assertEquals("no `$it` on a live line of LiteRtAsrEngine.kt", 0, liveLines(engine, it).size)
        }
    }
}
