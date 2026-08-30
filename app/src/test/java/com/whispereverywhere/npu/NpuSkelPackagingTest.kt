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
 * which is already first on `ADSP_LIBRARY_PATH`.
 *
 * Two halves, two instruments:
 *
 *  - **The build and runtime contract is SOURCE-pinned** over `app/build.gradle.kts` and
 *    `NpuWhisperBackend.kt`, and EXECUTED against `NpuFleetCensus` where the census object can
 *    carry the claim. No JVM test can run a Gradle task or dlopen a QNN stack, but the whole
 *    mechanism is a handful of spellings that must agree — the per-family excludes, the extract
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

    /** One family's gradle table row, spelled exactly as the build script must spell it. */
    private fun tripleRowFor(family: NpuSocFamily): String =
        "Triple(\"${family.skelAsset}\", ${kotlinLongLiteral(family.skelBytes)}, \"${family.skelSha256}\")"

    // ------------------------------------------------------------------ the gradle contract

    /**
     * THE FLEET'S BUILD HALF (4.2 F2). Needles are built FROM the census object, so a family
     * added to `NpuFleetCensus.families` makes this test demand its gradle row in the same
     * breath — the build's copy and the runtime's check cannot be updated apart.
     */
    @Test
    fun everyCensusFamilysSkelIsExtractedAndPinned() {
        NpuFleetCensus.families.forEach { family ->
            assertEquals(
                "family `${family.id}`'s skel row — asset, exact bytes, exact sha256 — must " +
                    "appear exactly once in extractQnnSkel's qnnSkels table. A family whose row " +
                    "is missing ships an APK whose assets lack its skel: the gate offers the " +
                    "tier, the stage declines at arm, and every device of that family runs CPU " +
                    "under a card that promised the AI chip.",
                1,
                liveLineCount(extractTask, tripleRowFor(family)),
            )
        }
        assertEquals(
            "and the table carries exactly one row per census family — an extra Triple is a " +
                "skel no census row will ever stage (dead assets) or a family the census does " +
                "not gate (a stage no gate offers); either way the census and the build have " +
                "parted company",
            NpuFleetCensus.families.size,
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
        val census = NpuFleetCensus.families
            .map { Triple(it.skelAsset, it.skelBytes, it.skelSha256) }
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
     */
    @Test
    fun theV75GradleRowIsTheShippedFourOnePinExactly() {
        assertEquals(
            "the V75 row carries the 4.1-shipped pair VERBATIM — 17_913_608 bytes, a56519d6…. " +
                "This is the value pair the Fold6 has device-executed; a build whose V75 row " +
                "moved without a measured runtime bump is extracting a different blob than the " +
                "one 4.1 shipped.",
            1,
            liveLineCount(
                extractTask,
                "Triple(\"libQnnHtpV75Skel.so\", 17_913_608L, " +
                    "\"a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c\")",
            ),
        )
    }

    /** The three new rows, as hard literals — the plan's measured table, second reading. */
    @Test
    fun theOtherThreeGradleRowsCarryTheMeasuredAarValues() {
        listOf(
            "Triple(\"libQnnHtpV73Skel.so\", 17_909_588L, " +
                "\"7be4f8a4ec21a9d8d51f59c73094154f42d2f8fc91cfaadaef03441b77d7ddb1\")",
            "Triple(\"libQnnHtpV79Skel.so\", 17_721_548L, " +
                "\"9cad65a621d154e5282ea9d2849d0a8838932ed91dc7e2514db4e992e2d933c6\")",
            "Triple(\"libQnnHtpV81Skel.so\", 18_844_384L, " +
                "\"b3453265c4574c69bb446bcb98dda117ded531b86b2307e0f02c595050fab8b1\")",
        ).forEach { row ->
            assertEquals(
                "the row must carry the values measured out of qnn-runtime-2.49.0.aar on " +
                    "2026-08-29, verbatim: $row",
                1,
                liveLineCount(extractTask, row),
            )
        }
    }

    @Test
    fun everyCensusFamilysSkelIsExcludedAndItsStubStaysInLib() {
        assertTrue("the jniLibs block was found", jniLibs.length < gradle.length)
        NpuFleetCensus.families.forEach { family ->
            val arch = "V${family.htpVersion}"
            assertEquals(
                "family `${family.id}`'s skel (${family.skelAsset}) is excluded from jniLibs " +
                    "exactly once. Under this app's extractNativeLibs=\"false\" packaging the " +
                    "FastRPC loader — which needs a real file on disk and searches only " +
                    "ADSP_LIBRARY_PATH — could never open a lib/ copy, so a skel left in lib/ " +
                    "is ~18 MB of provably dead APK; the same bytes ship under assets/ " +
                    "(extractQnnSkel) and are staged into filesDir at first arm",
                1,
                count(jniLibs, "excludes += \"**/${family.skelAsset}\""),
            )
            assertEquals(
                "family `${family.id}`'s STUB (libQnnHtp${arch}Stub.so) is NOT excluded: it is " +
                    "the CPU-side half, dlopen()ed by libQnnHtp.so straight out of the APK, " +
                    "which works page-aligned without extraction — a family whose stub is " +
                    "excluded arms all the way to nativeInit and dies inside the QNN loader " +
                    "with nothing naming why (the V68/V69 stub excludes keep this zero honest " +
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
     */
    @Test
    fun noUncoveredArchitectureLosesItsExcludes() {
        listOf("V68", "V69").forEach { arch ->
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
            liveLineCount(gradle, "com.qualcomm.qti:qnn-runtime:2.49.0"),
        )
        NpuFleetCensus.families.forEach { family ->
            assertEquals(
                "family `${family.id}`'s byte length (${kotlinLongLiteral(family.skelBytes)}) " +
                    "appears exactly once in the build script — the qnnSkels row",
                1,
                liveLineCount(gradle, kotlinLongLiteral(family.skelBytes)),
            )
            assertEquals(
                "and its sha256 exactly once",
                1,
                liveLineCount(gradle, family.skelSha256),
            )
            // The BACKEND carries NONE of these spellings: the census row travels as an object
            // and the stage call reads its fields. Whole-file and comment-inclusive, the same
            // instrument as the WhisperNative.init( residency pin — a KDoc that re-teaches a
            // literal is how a fifth spelling comes back.
            assertEquals(
                "the backend must not spell family `${family.id}`'s sha256 anywhere — not in " +
                    "code, not in a comment",
                0,
                count(backend, family.skelSha256),
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
        }
    }

    @Test
    fun theSkelIsStagedBeforeNativeInitAndAfterTheCompanionRefusal() {
        // ORDER, not presence — the statements all survive any permutation. Before nativeInit
        // because that is the dlopen that makes FastRPC go looking for the skel; after the
        // companion refusal (and every cheaper stage) because load's whole shape is
        // cheapest-refusal-first and the first arm of this stage writes ~18 MB.
        val companion = liveOffsets(loadBody, "if (companionPath.isNullOrBlank())")
        val mel = liveOffsets(loadBody, "melCtx = WhisperNative.initMelOnly(")
        val vocab = liveOffsets(loadBody, "WhisperBpeDecoder.fromJson(")
        val skel = liveOffsets(loadBody, "NpuAssetStage.stagedPathWithMarker(")
        val init = liveOffsets(loadBody, "QnnAsrNative.nativeInit(")
        assertTrue("the skel stage must run on a live line of load()", skel.isNotEmpty())
        assertTrue("nativeInit must run on a live line", init.isNotEmpty())
        assertTrue(
            "ORDER: companion (${companion.first()}) -> mel (${mel.first()}) -> vocab " +
                "(${vocab.first()}) -> skel (${skel.first()}) -> nativeInit (${init.first()}). " +
                "The skel BELOW nativeInit is a session that dlopens the HTP with no skel to " +
                "find; the skel ABOVE the companion refusal pays an ~18 MB first-arm write on a " +
                "tier that was never installed.",
            companion.first() < mel.first() && mel.first() < vocab.first() &&
                vocab.first() < skel.first() && skel.first() < init.first(),
        )
    }

    @Test
    fun aSkelThatCannotBeStagedIsARefusalUnderItsOwnStageName() {
        // A null from the stage is a stage refusal like any other: without it the HTP backend
        // would come up and then fail somewhere far less legible — inside FastRPC, as a dlopen
        // that "succeeds" with an HTP that never arrives.
        assertEquals(
            "the null return leaves through fallBackToCpuTier under stage name `skel`",
            1,
            count(
                loadBody,
                lines(
                    "            ) ?: return@serialized fallBackToCpuTier(",
                    "                \"skel\",",
                ),
            ),
        )
        assertEquals(
            "and `skel` is a stage name exactly once — the card and the WE-DIAG line name the " +
                "stage, and two spellings would be two stories",
            1,
            liveLineCount(loadBody, "\"skel\","),
        )
    }

    /**
     * Four families can decline at this stage now, and a refusal that names neither the asset
     * nor the row reads identically on all four. The one WE-DIAG line a fleet field report
     * will hinge on must say WHICH skel and WHICH family declined.
     */
    @Test
    fun theSkelRefusalNamesTheFamilysAssetAndId() {
        assertEquals(
            "the skel refusal detail interpolates the family's own asset name and its census id",
            1,
            liveLineCount(loadBody, "\${family.skelAsset} (family \${family.id})"),
        )
    }

    @Test
    fun theSkelArmUsesTheMarkerFastPathNotAFullHashPerSession() {
        // The L3 handoff's explicit warning to this stage: stagedPath full-hashes the
        // destination on EVERY arm — free at the melbank's 103 KB, a per-session ~18 MiB flash
        // read here. The skel therefore takes the marker entry point; the mel arm keeps the
        // original.
        assertEquals(
            "the skel stages through stagedPathWithMarker",
            1,
            liveLineCount(loadBody, "NpuAssetStage.stagedPathWithMarker("),
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
        // to prevent.
        listOf("family.skelAsset,", "family.skelBytes,", "family.skelSha256,").forEach { field ->
            assertEquals(
                "the stage call reads `$field` from the family row — exactly once",
                1,
                liveLineCount(loadBody, field),
            )
        }
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
