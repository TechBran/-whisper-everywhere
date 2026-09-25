package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * THE SKELS' PACKAGING ANSWER (4.1 L6, spec decision 7 / I5; fleet-wide at 4.2 F2): every census
 * family's DSP-side skel leaves `jniLibs` — where `extractNativeLibs="false"` made a `lib/` copy
 * provably unopenable by the FastRPC loader — and is re-materialised from the RESOLVED
 * `qnn-runtime` AAR into generated assets at build time. At arm, `NpuWhisperBackend` stages
 * exactly ONE of them — the row `NpuGate.familyFor` resolved this device to — into `filesDir`,
 * which is already first on `ADSP_LIBRARY_PATH`; since P1a it does so through its engine, whose
 * QNN implementation (`QnnAsrEngine.prepare`) is the stage, moved verbatim.
 *
 * Two halves, two instruments:
 *
 *  - **The build and runtime contract is SOURCE-pinned** over `app/build.gradle.kts`,
 *    `NpuWhisperBackend.kt` and `QnnAsrEngine.kt`, and EXECUTED against `NpuFleetCensus` where
 *    the census object can carry the claim. No JVM test can run a Gradle task or dlopen a QNN
 *    stack, but the whole mechanism is a handful of spellings that must agree — the per-family
 *    excludes, the extract
 *    task's per-row (bytes, sha256) asserts, the srcDir registration, and the family-row staging
 *    call — and any one drifting silently is a tier that dies on a device a month later with
 *    nothing naming why.
 *  - **The marker fast path is EXECUTED** against a temp dir, because it is the one piece of
 *    `NpuAssetStage` behaviour L6 added: L3's `stagedPath` full-hashes the destination on EVERY
 *    arm, which is free at the melbank's 103 KB and a per-session flash read at a skel's
 *    ~17-19 MiB — the L3 handoff's explicit warning, with "a stored marker, not a weaker check"
 *    as its prescribed answer.
 *
 * `app/build.gradle.kts` joins the test task's `sourcePinnedInputs` with this class: an edit
 * confined to the build script would otherwise leave `:app:testDebugUnitTest` UP-TO-DATE and
 * these pins would pass against stale evidence — the exact rule that list is built on.
 */
class NpuSkelPackagingTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    // ------------------------------------------------------------------ source helpers

    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private val gradle: String by lazy { read("build.gradle.kts") }

    private val backend: String by lazy {
        read("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
    }

    /**
     * The QNN engine (P1a, the engine seam), which the skel stage moved into VERBATIM — the
     * staging call, its three family fields and its refusal text — as `QnnAsrEngine.prepare`.
     * Every pin below that used to scope to the backend's `load` now scopes to that member, and
     * the backend is held to calling it exactly where the stage used to sit.
     */
    private val engine: String by lazy {
        read("src/main/java/com/whispereverywhere/transcription/QnnAsrEngine.kt")
    }

    private val prepareBody: String by lazy {
        kotlinMemberBody(
            engine, "override fun prepare(appContext: Context, family: NpuSocFamily): Refusal? {"
        )
    }

    private val engineInitBody: String by lazy {
        kotlinMemberBody(
            engine,
            "override fun init(spec: NpuModelSpec, files: NpuEngineFiles, dirs: NpuEngineDirs): Refusal? {"
        )
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun lines(vararg text: String) = text.joinToString("\n")

    private fun liveLineCount(haystack: String, needle: String): Int =
        haystack.lineSequence().count { line ->
            val trimmed = line.trimStart()
            val commented = trimmed.startsWith("//") || trimmed.startsWith("/*") ||
                trimmed.startsWith("*")
            !commented && line.contains(needle)
        }

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

    /** One Kotlin member, bounded by the anchor's own indent — the L1 helper, verbatim. */
    private fun kotlinMemberBody(kt: String, anchor: String): String {
        val start = kt.indexOf(anchor)
        assertTrue(
            "anchor \"$anchor\" is missing. indexOf() returns -1 when the anchor is absent, so " +
                "substring(start) would silently rebase the scope to the top of the file.",
            start >= 0
        )
        val lineStart = kt.lastIndexOf('\n', start - 1) + 1
        val indent = kt.substring(lineStart, start).takeWhile { it == ' ' }.length
        val body = StringBuilder(kt.substring(start).split("\n").first())
        var closed = false
        for (line in kt.substring(start).split("\n").drop(1)) {
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= indent) {
                body.append("\n").append(line)
                closed = true
                break
            }
            body.append("\n").append(line)
        }
        assertTrue("no line at the anchor's own indent follows \"$anchor\"", closed)
        return body.toString()
    }

    /** One top-level Gradle block: anchor to the first column-0 closing brace. */
    private fun gradleBlock(anchor: String): String {
        val start = gradle.indexOf(anchor)
        assertTrue("anchor \"$anchor\" is missing from app/build.gradle.kts", start >= 0)
        val body = gradle.substring(start)
        assertTrue("no column-0 \"\\n}\\n\" follows \"$anchor\"", body.contains("\n}\n"))
        return body.substringBefore("\n}\n")
    }

    private val loadBody: String by lazy {
        kotlinMemberBody(
            backend, "override fun load(modelPath: String, companionPath: String?): Long ="
        )
    }

    private val jniLibs: String by lazy {
        gradle.substringAfter("jniLibs {").substringBefore("\n        }")
    }

    private val extractTask: String by lazy {
        gradleBlock("val extractQnnSkel = tasks.register(\"extractQnnSkel\")")
    }

    /** `17913608` -> `"17_913_608L"` — the Kotlin literal spelling the gradle table uses. */
    private fun kotlinLongLiteral(value: Long): String =
        value.toString().reversed().chunked(3).joinToString("_").reversed() + "L"

    /**
     * Every census family whose runtime is QNN, with its QNN needs (P2 — the census reshape moved
     * the HTP version and the skel off the row and into its sealed `runtime`). These are exactly
     * the Qualcomm rows, by construction: a row's vendor IS its runtime's
     * (`NpuSocFamily.vendor` reads `runtime.vendor`), so no Qualcomm row can drop out of the pins
     * below by being spelled another way, and a row of another vendor has no skel to pin.
     */
    private val qnnFamilies: List<Pair<NpuSocFamily, NpuRuntimeNeeds.Qnn>> =
        NpuFleetCensus.families.mapNotNull { f -> (f.runtime as? NpuRuntimeNeeds.Qnn)?.let { f to it } }

    /** One QNN row's gradle table row, spelled exactly as the build script must spell it. */
    private fun tripleRowFor(qnn: NpuRuntimeNeeds.Qnn): String =
        "Triple(\"${qnn.skelAsset}\", ${kotlinLongLiteral(qnn.skelBytes)}, \"${qnn.skelSha256}\")"

    // ------------------------------------------------------------------ the gradle contract

    /**
     * THE FLEET'S BUILD HALF (4.2 F2). Needles are built FROM the census object, so a family
     * added to `NpuFleetCensus.families` makes this test demand its gradle row in the same
     * breath — the build's copy and the runtime's check cannot be updated apart.
     */
    @Test
    fun everyCensusFamilysSkelIsExtractedAndPinned() {
        assertTrue("the census has QNN rows to stage skels for", qnnFamilies.isNotEmpty())
        qnnFamilies.forEach { (family, qnn) ->
            assertEquals(
                "family `${family.id}`'s skel row — asset, exact bytes, exact sha256 — must " +
                    "appear exactly once in extractQnnSkel's qnnSkels table. A family whose row " +
                    "is missing ships an APK whose assets lack its skel: the gate offers the " +
                    "tier, the stage declines at arm, and every device of that family runs CPU " +
                    "under a card that promised the AI chip.",
                1,
                liveLineCount(extractTask, tripleRowFor(qnn)),
            )
        }
        // ONE ROW PER ARCHITECTURE, not per family — and until 2026-09-22 those were the same
        // number, so this line could say "family" and be right by coincidence. The skel is the
        // HTP version's blob: qcs8550 (8 Gen 2) is v73 exactly as 7gen4 is and names the same
        // libQnnHtpV73Skel.so, so six families stage five skels (8gen1 brought V69, the fifth
        // architecture, on 2026-09-24). Both halves of the original intent still hold and are
        // still asserted — the loop above proves every family's skel IS in the table (no stage a
        // gate offers but the APK lacks), and this count proves no Triple is unnamed by any
        // family (no dead asset).
        assertEquals(
            "the table carries exactly one row per census ARCHITECTURE — an extra Triple is a " +
                "skel no census row will ever stage (dead assets); a missing one is a family " +
                "whose stage declines at arm. Either way the census and the build have parted " +
                "company",
            qnnFamilies.map { (_, qnn) -> qnn.skelAsset }.toSet().size,
            liveLineCount(extractTask, "Triple(\""),
        )
    }

    /**
     * The executed set-equality, BOTH directions: every gradle row parses back to a census row
     * and every census row has a gradle row. [everyCensusFamilysSkelIsExtractedAndPinned] builds
     * needles census->gradle; this one parses gradle->census, which is what catches a rogue row
     * whose values are census-shaped but census-false.
     */
    @Test
    fun theGradleSkelTableEqualsTheCensusRowForRow() {
        val rows = Regex("Triple\\(\"([^\"]+)\", ([0-9_]+)L, \"([a-f0-9]{64})\"\\)")
            .findAll(extractTask)
            .map {
                Triple(
                    it.groupValues[1],
                    it.groupValues[2].replace("_", "").toLong(),
                    it.groupValues[3],
                )
            }
            .toSet()
        val census = qnnFamilies
            .map { (_, qnn) -> Triple(qnn.skelAsset, qnn.skelBytes, qnn.skelSha256) }
            .toSet()
        assertEquals(
            "the build script's qnnSkels table and NpuFleetCensus.families must carry EXACTLY " +
                "the same (asset, bytes, sha256) rows — executed equality, so the build-time " +
                "extraction assert and the arm-time staging check are two readings of one " +
                "census. The gradle copy exists only because a build script cannot read the " +
                "app's classes; this equality is what keeps it a copy rather than a fork.",
            census,
            rows,
        )
    }

    /**
     * The continuity pin's GRADLE half — hard literals on purpose, NOT derived from the census
     * object (`NpuFleetCensusTest` holds the census half with the same two literals). A
     * co-mutation that drifts the census and the build script together still dies here.
     *
     * RE-MADE AT THE 2.50 BUMP (2026-09-24), not inherited: the 4.1-shipped pair (17_913_608 /
     * a56519d6…) was the V75 blob of qnn-runtime 2.49.0, which the Fold6 device-executed. 2.50
     * ships a different V75 blob, so the pin now names the value pair measured out of
     * qnn-runtime-2.50.0.aar — the one the next Fold6 run on the internal track will execute.
     */
    @Test
    fun theV75GradleRowIsTheMeasuredTwoFiftyPinExactly() {
        assertEquals(
            "the V75 row carries the 2.50 pair VERBATIM — 18_693_300 bytes, 3e9774b7…. A build " +
                "whose V75 row moves without a measured runtime bump is extracting a different " +
                "blob than the one this census was measured against.",
            1,
            liveLineCount(
                extractTask,
                "Triple(\"libQnnHtpV75Skel.so\", 18_693_300L, " +
                    "\"3e9774b74769915b4f54364f8fc25887b3439561a970dca57c9f4dc9612b38af\")",
            ),
        )
    }

    /** The other four rows, as hard literals — the 2.50 measured table, second reading. */
    @Test
    fun theOtherGradleRowsCarryTheMeasuredAarValues() {
        listOf(
            "Triple(\"libQnnHtpV69Skel.so\", 12_529_660L, " +
                "\"262f3e8807ea969cfc446ea8717500475ea1ea1be6205201486a5431ffcb490e\")",
            "Triple(\"libQnnHtpV73Skel.so\", 18_709_712L, " +
                "\"024a0aea3d8d44fc5b59ffab20bde4348d07d05ad7d23f27c8bd06aa3d240d8a\")",
            "Triple(\"libQnnHtpV79Skel.so\", 18_513_604L, " +
                "\"860c9d2e7c937c9fb8f8f18daa9a79cab6c566066a2d36f235f6c8708fdc75bd\")",
            "Triple(\"libQnnHtpV81Skel.so\", 19_708_192L, " +
                "\"02047c9fef8a22801c0eefaa79188e87b600372c9813dea3f621ba256d1ddce0\")",
        ).forEach { row ->
            assertEquals(
                "the row must carry the values measured out of qnn-runtime-2.50.0.aar on " +
                    "2026-09-24, verbatim: $row",
                1,
                liveLineCount(extractTask, row),
            )
        }
    }

    @Test
    fun everyCensusFamilysSkelIsExcludedAndItsStubStaysInLib() {
        assertTrue("the jniLibs block was found", jniLibs.length < gradle.length)
        qnnFamilies.forEach { (family, qnn) ->
            val arch = "V${qnn.htpVersion}"
            assertEquals(
                "family `${family.id}`'s skel (${qnn.skelAsset}) is excluded from jniLibs " +
                    "exactly once. Under this app's extractNativeLibs=\"false\" packaging the " +
                    "FastRPC loader — which needs a real file on disk and searches only " +
                    "ADSP_LIBRARY_PATH — could never open a lib/ copy, so a skel left in lib/ " +
                    "is ~18 MB of provably dead APK; the same bytes ship under assets/ " +
                    "(extractQnnSkel) and are staged into filesDir at first arm",
                1,
                count(jniLibs, "excludes += \"**/${qnn.skelAsset}\""),
            )
            assertEquals(
                "family `${family.id}`'s STUB (libQnnHtp${arch}Stub.so) is NOT excluded: it is " +
                    "the CPU-side half, dlopen()ed by libQnnHtp.so straight out of the APK, " +
                    "which works page-aligned without extraction — a family whose stub is " +
                    "excluded arms all the way to nativeInit and dies inside the QNN loader " +
                    "with nothing naming why (the V68 stub exclude keeps this zero honest " +
                    "— see noUncoveredArchitectureLosesItsExcludes)",
                0,
                count(jniLibs, "excludes += \"**/libQnnHtp${arch}Stub.so\""),
            )
            assertEquals(
                "and no WILDCARD sweeps the $arch pair — `**/libQnnHtp$arch*` would take the " +
                    "stub with the skel and read identically in review",
                0,
                count(jniLibs, "$arch*"),
            )
        }
    }

    /**
     * The honesty half of the stub live-zeros above: a zero is satisfied by deleting the whole
     * exclude mechanism, so the architectures with NO covered family must still be PRESENT as
     * excludes — skel and stub both — along with the never-used backends.
     *
     * V69 LEFT THIS LIST on 2026-09-24: the 8gen1 family made it a census architecture, so its
     * stub now ships in lib/ (the live-zero above covers it) and its skel's exclude moved to the
     * census-skel list (the exactly-once above covers that). V68 is the one uncovered
     * architecture left, and the list is derived-checked below so the next family that brings
     * one cannot leave it here by accident.
     */
    @Test
    fun noUncoveredArchitectureLosesItsExcludes() {
        val covered = qnnFamilies.map { (_, qnn) -> "V${qnn.htpVersion}" }.toSet()
        assertFalse(
            "an architecture listed as UNCOVERED here must not be a census architecture — its " +
                "stub would be excluded from lib/ while a family stages its skel",
            "V68" in covered,
        )
        listOf("V68").forEach { arch ->
            listOf("Skel", "Stub").forEach { half ->
                assertEquals(
                    "libQnnHtp$arch$half.so stays excluded — no census family runs $arch, so " +
                        "both halves are dead weight, and this presence is what keeps the " +
                        "census families' stub live-zeros an assertion rather than a vacuity",
                    1,
                    count(jniLibs, "excludes += \"**/libQnnHtp$arch$half.so\""),
                )
            }
        }
        listOf(
            "**/libQnnHtpPrepare.so", "**/libQnnDsp.so", "**/libQnnDspV66Skel.so",
            "**/libQnnDspV66Stub.so", "**/libQnnGpu.so",
        ).forEach { dead ->
            assertEquals(
                "$dead stays excluded — unused whatever the census says",
                1,
                count(jniLibs, "excludes += \"$dead\""),
            )
        }
    }

    @Test
    fun theExtractTaskReadsTheExactAarEntryAndAssertsBothPinnedValues() {
        assertEquals(
            "the loop reads each family's AAR entry by its exact interpolated path — a layout " +
                "change in a future runtime AAR must fail HERE by name, not surface as an " +
                "empty asset",
            1,
            liveLineCount(extractTask, "zip.getEntry(\"jni/arm64-v8a/\$name\")"),
        )
        assertEquals(
            "the rows live in ONE local `qnnSkels` table the loop iterates, so a family's " +
                "entry name, size and digest cannot be updated apart",
            1,
            liveLineCount(extractTask, "val qnnSkels = listOf("),
        )
        assertEquals(
            "each entry's byte length is asserted, as a check( that names the remedy — the " +
                "same size-assert discipline fetchSherpaAar already applies",
            1,
            liveLineCount(extractTask, "check(skel.length() == bytes)"),
        )
        assertEquals(
            "and each entry's sha256, computed over what it just wrote",
            1,
            liveLineCount(extractTask, "check(digest == sha256)"),
        )
        assertEquals(
            "every failure message names NpuFleetCensus as the co-updated reader — the " +
                "missing-entry throw and both check messages — so a runtime bump cannot update " +
                "the build half and forget the half the runtime staging checks at arm time",
            3,
            liveLineCount(extractTask, "NpuFleetCensus"),
        )
    }

    @Test
    fun theGeneratedAssetsDirIsRegisteredOnTheMainSourceSet() {
        assertEquals(
            "the generated dir is declared once, in the BUILD directory — outside the repo, so " +
                "the proprietary blob structurally cannot be committed",
            1,
            liveLineCount(
                gradle,
                "val qnnSkelAssetDir = layout.buildDirectory.dir(\"generated/qnnSkel/assets\")",
            ),
        )
        assertEquals(
            "and registered as a main-source-set assets srcDir, or the merge never sees it and " +
                "the APK ships without the skel — a build that LOOKS green and a tier that dies " +
                "at stage=skel on every device",
            1,
            liveLineCount(gradle, "getByName(\"main\") { assets.srcDir(qnnSkelAssetDir) }"),
        )
    }

    @Test
    fun theExtractTaskIsOrderedBeforeMergeAssetsNotMerelyPreBuild() {
        assertEquals(
            "the task is ordered against merge*Assets — the task that actually NEEDS the asset " +
                "on disk. preBuild gates the compile* tasks and does NOT gate AGP's asset " +
                "merging: the exact lesson fetchQnnHeaders already paid for one asset class over",
            1,
            count(
                gradle,
                lines(
                    "tasks.matching { it.name.startsWith(\"merge\") && it.name.endsWith(\"Assets\") }",
                    "    .configureEach { dependsOn(extractQnnSkel) }",
                ),
            ),
        )
        assertEquals(
            "preBuild is wired too — a build that never reaches the merge still materialises " +
                "the blob, same belt-and-braces as the header fetch",
            1,
            liveLineCount(gradle, "tasks.named(\"preBuild\") { dependsOn(extractQnnSkel) }"),
        )
    }

    @Test
    fun theBuildTaskAndTheRuntimeStageCannotDriftApart() {
        // ONE fact per family, spelled in the two places that structurally cannot read each
        // other: the build script's qnnSkels table (asserted at extraction) and the census the
        // runtime stages from (checked at arm). Plus the AAR coordinate twice in the build
        // script. This test is the reading that ties them.
        assertEquals(
            "the qnn-runtime coordinate appears exactly twice — the dependency (unchanged) and " +
                "extractQnnSkel's resolution config — and the two must stay the same version, " +
                "or the build extracts one runtime's skels while the app dlopens another's stack",
            2,
            liveLineCount(gradle, "com.qualcomm.qti:qnn-runtime:2.50.0"),
        )
        qnnFamilies.forEach { (family, qnn) ->
            assertEquals(
                "family `${family.id}`'s byte length (${kotlinLongLiteral(qnn.skelBytes)}) " +
                    "appears exactly once in the build script — the qnnSkels row",
                1,
                liveLineCount(gradle, kotlinLongLiteral(qnn.skelBytes)),
            )
            assertEquals(
                "and its sha256 exactly once",
                1,
                liveLineCount(gradle, qnn.skelSha256),
            )
            // The BACKEND carries NONE of these spellings: the census row travels as an object
            // and the stage call reads its fields. Whole-file and comment-inclusive, the same
            // instrument as the WhisperNative.init( residency pin — a KDoc that re-teaches a
            // literal is how a fifth spelling comes back. (P1a) And NEITHER DOES THE ENGINE the
            // stage moved into: the row still travels as an object, into prepare.
            assertEquals(
                "the backend must not spell family `${family.id}`'s sha256 anywhere — not in " +
                    "code, not in a comment",
                0,
                count(backend, qnn.skelSha256),
            )
            assertEquals(
                "nor must QnnAsrEngine, where the skel stage lives now — not in code, not in a " +
                    "comment",
                0,
                count(engine, qnn.skelSha256),
            )
        }
    }

    // ------------------------------------------------------------------ the backend stage

    /**
     * The L2 doctrine's third application (spec at L2, melAsset at L3, family here): the absence
     * of a default is a property of the DECLARATION — no call can observe it and no executed
     * test can cover it, because a call that omitted the argument would not compile. A
     * one-character `=` on this line is the whole hazard: a defaulted family stages the
     * default's DSP-side skel under another family's silicon, and the failure is a FastRPC
     * mystery on a device, not a compile error. The backend file is a declared test input, so
     * this line cannot change without re-running this pin.
     */
    @Test
    fun theBackendsFamilyParameterIsRequiredWithNoDefault() {
        assertEquals(
            "the constructor takes `private val family: NpuSocFamily,` — exactly once, no " +
                "default value",
            1,
            liveLineCount(backend, "private val family: NpuSocFamily,"),
        )
        assertEquals(
            "no spelling of a defaulted family parameter exists on any live line",
            0,
            liveLineCount(backend, "family: NpuSocFamily ="),
        )
    }

    /**
     * 4.1's `SKEL_BYTES`/`SKEL_SHA256` companions were the single-family home of the pair; the
     * census row is the one home now. Whole-file and comment-inclusive, the same instrument as
     * the `WhisperNative.init(` residency pin and for the same reason: a KDoc that re-teaches
     * the deleted constants is exactly how they come back — and with four families, a constant
     * named SKEL_BYTES no longer even has a referent.
     */
    @Test
    fun theDeletedSkelCompanionsDoNotComeBack() {
        listOf("SKEL_BYTES", "SKEL_SHA256", "17_913_608").forEach { ghost ->
            assertEquals(
                "`$ghost` must appear NOWHERE in NpuWhisperBackend.kt — the family row is the " +
                    "only spelling of a skel's identity the backend may hold",
                0,
                count(backend, ghost),
            )
            assertEquals(
                "…nor in QnnAsrEngine.kt, where the stage lives since P1a — the row is the only " +
                    "spelling of a skel's identity the ENGINE may hold, too",
                0,
                count(engine, ghost),
            )
        }
    }

    /**
     * ORDER, not presence — the statements all survive any permutation. RE-POINTED AT P1a
     * across the two files the order now spans: in the backend's `load`, the engine's prepare
     * (the skel stage) sits after the companion refusal and every cheaper stage and before the
     * engine's init; in the engine, prepare IS the staging call and init IS nativeInit. So the
     * skel is still staged after the companion refusal and before the dlopen that makes FastRPC
     * go looking for it.
     */
    @Test
    fun theSkelIsStagedBeforeNativeInitAndAfterTheCompanionRefusal() {
        // Before nativeInit because that is the dlopen that makes FastRPC go looking for the
        // skel; after the companion refusal (and every cheaper stage) because load's whole
        // shape is cheapest-refusal-first and the first arm of this stage writes ~18 MB.
        val companion = liveOffsets(loadBody, "if (companionPath.isNullOrBlank())")
        val mel = liveOffsets(loadBody, "melCtx = WhisperNative.initMelOnly(")
        val vocab = liveOffsets(loadBody, "WhisperBpeDecoder.fromJson(")
        val skel = liveOffsets(loadBody, "engine.prepare(appContext, family)")
        val init = liveOffsets(loadBody, "engine.init(")
        assertTrue("the skel stage (the engine's prepare) must run on a live line of load()", skel.isNotEmpty())
        assertTrue("the engine's init must run on a live line", init.isNotEmpty())
        assertTrue(
            "ORDER: companion (${companion.first()}) -> mel (${mel.first()}) -> vocab " +
                "(${vocab.first()}) -> skel (${skel.first()}) -> init (${init.first()}). " +
                "The skel BELOW nativeInit is a session that dlopens the HTP with no skel to " +
                "find; the skel ABOVE the companion refusal pays an ~18 MB first-arm write on a " +
                "tier that was never installed.",
            companion.first() < mel.first() && mel.first() < vocab.first() &&
                vocab.first() < skel.first() && skel.first() < init.first(),
        )
        assertEquals(
            "exactly one prepare call in load — one staging per arm",
            1,
            skel.size,
        )
        assertTrue(
            "and in the engine the two members are what those calls stand for: prepare stages " +
                "the skel and init runs nativeInit — so the backend's order is the skel's order",
            liveLineCount(prepareBody, "NpuAssetStage.stagedPathWithMarker(") == 1 &&
                liveLineCount(engineInitBody, "QnnAsrNative.nativeInit(") == 1,
        )
    }

    @Test
    fun aSkelThatCannotBeStagedIsARefusalUnderItsOwnStageName() {
        // A null from the stage is a stage refusal like any other: without it the HTP backend
        // would come up and then fail somewhere far less legible — inside FastRPC, as a dlopen
        // that "succeeds" with an HTP that never arrives. (P1a) The refusal is now a value —
        // Refusal(NpuStage.SKEL, …) — which the backend routes through fallBackToCpuTier under
        // the stage's wire word, `skel`, exactly the word the funnel printed before the seam.
        assertEquals(
            "the null return leaves prepare as a SKEL refusal, on the staging call's own elvis",
            1,
            count(
                prepareBody,
                lines(
                    "        ) ?: return Refusal(",
                    "            NpuStage.SKEL,",
                ),
            ),
        )
        // RE-SPECCED AT P2 (the census reshape), from "exactly once" to "exactly twice, both
        // inside prepare". The second site is a DIFFERENT failure, not a second story of this
        // one: a row whose sealed runtime is not QNN (a MediaTek row) carries no skel, and prepare
        // refuses it by name before anything is staged. What the old count guarded still holds
        // exactly — the stage is spelled by its constant and by nothing else, and no member but
        // the engine's own staging can produce it — and the count is still exact, so a third
        // site anywhere in the engine fails here.
        assertEquals(
            "and SKEL is the stage at exactly two live sites in the engine — the staging call's " +
                "elvis and the row with no skel to stage — the card and the WE-DIAG line name the " +
                "stage, and a third spelling would be a third story",
            2,
            liveLineCount(engine, "NpuStage.SKEL"),
        )
        assertEquals(
            "…and both are prepare's: no other member of the engine refuses at the skel stage",
            2,
            liveLineCount(prepareBody, "NpuStage.SKEL"),
        )
        assertEquals(
            "the row with no skel is refused on the runtime's own `when` arm, naming its stage",
            1,
            count(
                prepareBody,
                lines(
                    "            is NpuRuntimeNeeds.LiteRtMediatek -> return Refusal(",
                    "                NpuStage.SKEL,",
                ),
            ),
        )
        val noSkelArm = liveOffsets(prepareBody, "is NpuRuntimeNeeds.LiteRtMediatek -> return Refusal(")
        val stage = liveOffsets(prepareBody, "NpuAssetStage.stagedPathWithMarker(")
        assertTrue(
            "ORDER: that refusal (${noSkelArm.firstOrNull()}) comes BEFORE the staging call " +
                "(${stage.firstOrNull()}) — a row of another vendor writes no skel into filesDir",
            noSkelArm.isNotEmpty() && stage.isNotEmpty() && noSkelArm.first() < stage.first(),
        )
        // RESTORED AND EXTENDED (P1a review): at 4a7c126 this pin held `"skel",` to exactly one
        // live line of load — the whole of the stage's spelling. The count of NpuStage.SKEL above
        // is the engine's half only, and NpuStageTest's derivation merges duplicates, so a
        // `fallBackToCpuTier("skel", …)` added back to the backend passed both. The word's one
        // home is NpuStage.kt's `SKEL("skel"),` (NpuStageTest pins that line); a quoted `"skel"`
        // anywhere in the backend or the engine is the second story.
        assertEquals(
            "the quoted stage word `\"skel\"` appears on NO live line of the backend — the stage is " +
                "the engine's now, spelled NpuStage.SKEL",
            0,
            liveLineCount(backend, "\"skel\""),
        )
        assertEquals(
            "…nor of the engine, which names the stage by its constant",
            0,
            liveLineCount(engine, "\"skel\""),
        )
        assertEquals(
            "the backend routes the prepare refusal through the one funnel, printing its wire word",
            1,
            count(
                loadBody,
                lines(
                    "            engine.prepare(appContext, family)?.let { refusal ->",
                    "                return@serialized fallBackToCpuTier(refusal.stage.wire, refusal.detail)",
                ),
            ),
        )
        assertEquals("NpuStage.SKEL's wire word is the one the funnel always printed", "skel", NpuStage.SKEL.wire)
    }

    /**
     * Four families can decline at this stage now, and a refusal that names neither the asset
     * nor the row reads identically on all four. The one WE-DIAG line a fleet field report
     * will hinge on must say WHICH skel and WHICH family declined.
     */
    @Test
    fun theSkelRefusalNamesTheFamilysAssetAndId() {
        // RE-POINTED AT P2 (the census reshape): the asset name is read off the row's QNN needs
        // (`qnn`, bound from `family.runtime` — pinned in the marker test below), and the text a
        // device prints is the same text, character for character: `qnn.skelAsset` IS the value
        // `family.skelAsset` was.
        assertEquals(
            "the skel refusal detail interpolates the family's own asset name and its census id",
            1,
            liveLineCount(prepareBody, "\${qnn.skelAsset} (family \${family.id})"),
        )
        assertEquals(
            "…in the moved text, verbatim — the detail a device prints did not change by a " +
                "character when the stage moved into the engine, nor when the skel moved into the " +
                "row's runtime",
            1,
            count(
                prepareBody,
                lines(
                    "            \"\${qnn.skelAsset} (family \${family.id}) could not be staged from the APK \" +",
                    "                \"into filesDir — the FastRPC loader would find no DSP-side skel to open\"",
                ),
            ),
        )
        assertEquals(
            "and the no-skel refusal names the family too — the one line a wiring fault would print",
            1,
            liveLineCount(prepareBody, "\"family \${family.id} is a \${family.vendor} row (LiteRT, Neuron \" +"),
        )
    }

    @Test
    fun theSkelArmUsesTheMarkerFastPathNotAFullHashPerSession() {
        // The L3 handoff's explicit warning to this stage: stagedPath full-hashes the
        // destination on EVERY arm — free at the melbank's 103 KB, a per-session ~18 MiB flash
        // read here. The skel therefore takes the marker entry point; the mel arm keeps the
        // original. (P1a) The two arms now sit in two files, and each is held in its own.
        assertEquals(
            "the skel stages through stagedPathWithMarker, in the engine's prepare",
            1,
            liveLineCount(prepareBody, "NpuAssetStage.stagedPathWithMarker("),
        )
        assertEquals(
            "…exactly once in the whole engine, and never in the backend: one staging site",
            listOf(1, 0),
            listOf(
                liveLineCount(engine, "NpuAssetStage.stagedPathWithMarker("),
                liveLineCount(backend, "NpuAssetStage.stagedPathWithMarker("),
            ),
        )
        assertEquals(
            "the mel arm keeps the plain stagedPath — 103 KB per arm is free and its full hash " +
                "is strictly stronger, so it has no reason to change",
            1,
            liveLineCount(loadBody, "NpuAssetStage.stagedPath("),
        )
        // THE FAMILY ROW DRIVES THE CALL (4.2 F2): asset name, bytes and digest are all read
        // off the census row this device resolved to — the same three values extractQnnSkel
        // asserted into assets at build time. A literal here is a fifth spelling, and a
        // DIFFERENT family's fields here is the wrong-skel stage the required parameter exists
        // to prevent. (P1a) The row reaches the call as prepare's own `family` parameter, which
        // the backend hands its constructor's required one. (P2) The three values live in the
        // row's sealed runtime now, so the pin is two halves: `qnn` is bound from THIS family's
        // own `runtime`, exactly once, and the call reads each field off `qnn`, exactly once.
        assertEquals(
            "the stage's values are this family row's own QNN needs — `qnn` is bound from " +
                "`family.runtime` and from nothing else",
            1,
            count(
                prepareBody,
                lines(
                    "        val qnn = when (val needs = family.runtime) {",
                    "            is NpuRuntimeNeeds.Qnn -> needs",
                ),
            ),
        )
        assertEquals("…and bound exactly once", 1, liveLineCount(prepareBody, "val qnn ="))
        listOf("qnn.skelAsset,", "qnn.skelBytes,", "qnn.skelSha256,").forEach { field ->
            assertEquals(
                "the stage call reads `$field` from the family row's QNN needs — exactly once",
                1,
                liveLineCount(prepareBody, field),
            )
        }
        assertEquals(
            "the family prepare reads is the backend's own required row, passed by name",
            1,
            liveLineCount(loadBody, "engine.prepare(appContext, family)"),
        )
    }

    /**
     * The seam's half of the no-default doctrine: the engine's prepare takes the family as a
     * REQUIRED parameter too. A default there would stage the default row's skel under another
     * family's silicon exactly as a defaulted constructor parameter would, one call further in.
     */
    @Test
    fun theEnginesPrepareTakesTheFamilyWithNoDefault() {
        val seam = read("src/main/java/com/whispereverywhere/transcription/NpuAsrEngine.kt")
        assertEquals(
            "the interface declares `fun prepare(appContext: Context, family: NpuSocFamily): Refusal?`",
            1,
            liveLineCount(seam, "fun prepare(appContext: Context, family: NpuSocFamily): Refusal?"),
        )
        assertEquals(
            "and no live line of the seam or the QNN engine spells a defaulted family",
            0,
            liveLineCount(seam, "family: NpuSocFamily =") + liveLineCount(engine, "family: NpuSocFamily ="),
        )
    }

    // ------------------------------------------------------------------ the marker, executed

    private val payload: ByteArray = ByteArray(8192) { (it * 37 % 251).toByte() }

    private fun sha256(of: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(of).joinToString("") { "%02x".format(it) }

    private val digest: String by lazy { sha256(payload) }

    private fun dest(): File = File(temp.root, "libQnnHtpV75Skel.so")

    private fun marker(): File = NpuAssetStage.markerFile(dest())

    private fun part(): File = File(temp.root, "libQnnHtpV75Skel.so" + NpuAssetStage.PART_SUFFIX)

    private fun stageMarked(
        expectedBytes: Long = payload.size.toLong(),
        expectedSha256: String = digest,
        open: () -> InputStream = { ByteArrayInputStream(payload) },
    ): NpuAssetStage.StageResult =
        NpuAssetStage.stageWithMarker(dest(), "libQnnHtpV75Skel.so", expectedBytes, expectedSha256, open)

    @Test
    fun aMatchingMarkerVouchesWithoutRehashingTheContentAndThatIsTheStatedTrade() {
        // The trade IS the observable: content corrupted in place at the recorded length and
        // mtime passes the fast arm. If a "fix" quietly reinstates the full per-arm hash, this
        // corruption gets detected and re-staged — and this test goes red asking whether the
        // 17.9 MiB-per-session cost was really meant to come back.
        val corrupt = payload.copyOf()
        corrupt[corrupt.size / 2] = (corrupt[corrupt.size / 2] + 1).toByte()
        dest().writeBytes(corrupt)
        val stamp = 1_600_000_000_000L
        assertTrue("the test needs a settable mtime", dest().setLastModified(stamp))
        marker().writeText(NpuAssetStage.markerLine(digest, payload.size.toLong(), stamp))

        var opened = false
        val result = stageMarked(open = { opened = true; ByteArrayInputStream(payload) })
        assertTrue("a vouched-for destination is Staged: got $result",
            result is NpuAssetStage.StageResult.Staged)
        assertFalse(
            "the fast arm must not have touched the source — the whole point is that the " +
                "second-and-every-later arm costs a handful of stats, not a 17.9 MiB read",
            opened,
        )
        assertTrue(
            "and must NOT have detected the in-place corruption — the STATED trade: a marker " +
                "that matches vouches by stat alone, exactly the trust every mtime-based build " +
                "system extends",
            dest().readBytes().contentEquals(corrupt),
        )
    }

    @Test
    fun aMarkerForADifferentExpectationForcesTheFullVerification() {
        // The APK-upgrade case: the expected digest changes while an old verified copy and its
        // old marker sit in filesDir. The marker records WHICH digest it verified, so a new
        // expectation falls through to the full path and re-stages.
        assertTrue(stageMarked() is NpuAssetStage.StageResult.Staged)
        assertTrue("the first stage leaves a marker", marker().isFile)

        val newPayload = ByteArray(payload.size) { (it * 41 % 251).toByte() }
        val newDigest = sha256(newPayload)
        var opened = false
        val result = stageMarked(
            expectedSha256 = newDigest,
            open = { opened = true; ByteArrayInputStream(newPayload) },
        )
        assertTrue("the new expectation stages: got $result", result is NpuAssetStage.StageResult.Staged)
        assertTrue("the source WAS re-read — the old marker cannot vouch for a new digest", opened)
        assertTrue("the destination now holds the new bytes", dest().readBytes().contentEquals(newPayload))
        assertTrue(
            "and the marker was rewritten to vouch for the NEW expectation",
            NpuAssetStage.markerVouches(dest(), newPayload.size.toLong(), newDigest),
        )
        assertFalse(
            "…which the old one could not",
            NpuAssetStage.markerVouches(dest(), payload.size.toLong(), digest),
        )
    }

    @Test
    fun aRewrittenDestinationInvalidatesTheMarker() {
        // A rewritten file has a new mtime, and the marker records the verified copy's. The fast
        // arm must fall through to the full verification, which catches the content and re-stages.
        assertTrue(stageMarked() is NpuAssetStage.StageResult.Staged)
        val corrupt = payload.copyOf()
        corrupt[0] = (corrupt[0] + 1).toByte()
        dest().writeBytes(corrupt)
        assertTrue(dest().setLastModified(1_700_000_000_000L))

        var opened = false
        val result = stageMarked(open = { opened = true; ByteArrayInputStream(payload) })
        assertTrue("the arm still answers Staged", result is NpuAssetStage.StageResult.Staged)
        assertTrue("but through the FULL path — the mtime mismatch un-vouched the marker", opened)
        assertTrue("and the corruption was repaired", dest().readBytes().contentEquals(payload))
        assertTrue(
            "with a fresh marker vouching for the repaired copy",
            NpuAssetStage.markerVouches(dest(), payload.size.toLong(), digest),
        )
    }

    @Test
    fun aRefusedStageLeavesNoMarkerNoPartAndNoDestination() {
        // Refusals must not strand a marker that could vouch for a file a later stage writes:
        // the stale marker is deleted BEFORE the re-stage, and one is written only after a full
        // verification has passed.
        val short = payload.copyOf(payload.size - 1)
        val refused = stageMarked(open = { ByteArrayInputStream(short) })
        assertTrue("a short source refuses", refused is NpuAssetStage.StageResult.Refused)
        assertFalse("no destination", dest().exists())
        assertFalse("no .part", part().exists())
        assertFalse("and no marker", marker().exists())

        // And a PRE-EXISTING bogus marker does not survive a refusal to vouch later.
        marker().writeText(NpuAssetStage.markerLine(digest, payload.size.toLong(), 12345L))
        val refusedAgain = stageMarked(open = { throw IOException("asset missing from this build") })
        assertTrue(refusedAgain is NpuAssetStage.StageResult.Refused)
        assertFalse(
            "the stale marker is gone — it must not vouch for whatever a later stage writes",
            marker().exists(),
        )
    }
}
