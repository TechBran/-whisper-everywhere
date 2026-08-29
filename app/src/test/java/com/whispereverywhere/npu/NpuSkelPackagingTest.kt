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
 * THE SKEL'S PACKAGING ANSWER (4.1 L6, spec decision 7 / I5): `libQnnHtpV75Skel.so` leaves
 * `jniLibs` — where `extractNativeLibs="false"` made it provably unopenable by the FastRPC
 * loader — and is re-materialised from the RESOLVED `qnn-runtime` AAR into generated assets at
 * build time, then staged into `filesDir` on first arm, which is already first on
 * `ADSP_LIBRARY_PATH`.
 *
 * Two halves, two instruments:
 *
 *  - **The build and runtime contract is SOURCE-pinned** over `app/build.gradle.kts` and
 *    `NpuWhisperBackend.kt`. No JVM test can run a Gradle task or dlopen a QNN stack, but the
 *    whole mechanism is four spellings that must agree — the exclude, the extract task's two
 *    asserted values, the srcDir registration, and the staging constants — and any one drifting
 *    silently is a tier that dies on a device a month later with nothing naming why.
 *  - **The marker fast path is EXECUTED** against a temp dir, because it is the one piece of new
 *    `NpuAssetStage` behaviour: L3's `stagedPath` full-hashes the destination on EVERY arm, which
 *    is free at the melbank's 103 KB and a per-session flash read at the skel's 17.9 MiB — the
 *    L3 handoff's explicit warning to this task, with "a stored marker, not a weaker check" as
 *    its prescribed answer.
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

    // The two values this whole mechanism agrees on — MEASURED from qnn-runtime-2.49.0.aar's
    // jni/arm64-v8a entry (the plan's asset block; re-measured from the Gradle cache at L6).
    private val skelBytesLiteral = "17_913_608L"
    private val skelSha = "a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c"

    // ------------------------------------------------------------------ the gradle contract

    @Test
    fun theSkelIsExcludedFromJniLibsExactlyOnceAndTheStubStays() {
        val jniLibs = gradle.substringAfter("jniLibs {").substringBefore("\n        }")
        assertTrue("the jniLibs block was found", jniLibs.length < gradle.length)
        assertEquals(
            "libQnnHtpV75Skel.so is excluded from jniLibs exactly once. Under this app's " +
                "extractNativeLibs=\"false\" packaging the FastRPC loader — which needs a real " +
                "file and searches only ADSP_LIBRARY_PATH — could never open a lib/ copy, so " +
                "keeping one is 17.9 MB of provably dead APK; the same bytes ship under assets/ " +
                "instead (extractQnnSkel) and are staged into filesDir on first arm",
            1,
            count(jniLibs, "excludes += \"**/libQnnHtpV75Skel.so\""),
        )
        assertEquals(
            "the V75 STUB is NOT excluded: it is the CPU-side half, dlopen()ed by libQnnHtp.so " +
                "straight out of the APK, which works page-aligned without extraction — " +
                "excluding it kills the tier outright",
            0,
            count(jniLibs, "excludes += \"**/libQnnHtpV75Stub.so\""),
        )
        assertEquals(
            "and no WILDCARD sweeps the V75 pair — `**/libQnnHtpV75*` would take the stub with " +
                "the skel and read identically in review",
            0,
            count(jniLibs, "V75*"),
        )
    }

    @Test
    fun theExtractTaskReadsTheExactAarEntryAndAssertsBothPinnedValues() {
        val task = gradleBlock("val extractQnnSkel = tasks.register(\"extractQnnSkel\")")
        assertEquals(
            "the task reads the AAR's own arm64 entry, by its exact path — a layout change in a " +
                "future runtime AAR must fail HERE by name, not surface as an empty asset",
            1,
            liveLineCount(task, "zip.getEntry(\"jni/arm64-v8a/libQnnHtpV75Skel.so\")"),
        )
        assertEquals(
            "the task asserts the exact byte length, as a check( that names the remedy — the " +
                "same size-assert discipline fetchSherpaAar already applies",
            1,
            liveLineCount(task, "check(skel.length() == $skelBytesLiteral)"),
        )
        assertEquals(
            "and the exact sha256, computed over what it just wrote — the second of the two " +
                "values the runtime staging checks again at arm time",
            1,
            liveLineCount(task, "check(digest == \"$skelSha\")"),
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
        // ONE fact, four spellings: the AAR coordinate twice in the build script (the F2
        // dependency and the extract task's own resolution config), and the two measured values
        // once in the build script and once in the backend's staging constants. This test is the
        // third reading that ties them; change any one alone and it goes red asking for the rest.
        assertEquals(
            "the qnn-runtime coordinate appears exactly twice — the F2 dependency (unchanged) " +
                "and extractQnnSkel's resolution config — and the two must stay the same version, " +
                "or the build extracts one runtime's skel while the app dlopens another's stack",
            2,
            liveLineCount(gradle, "com.qualcomm.qti:qnn-runtime:2.49.0"),
        )
        assertTrue(
            "the byte length appears in the build script (the extract assert)…",
            liveLineCount(gradle, skelBytesLiteral) >= 1,
        )
        assertEquals(
            "…and exactly once in the backend, as the staging constant",
            1,
            liveLineCount(backend, "const val SKEL_BYTES: Long = $skelBytesLiteral"),
        )
        assertTrue(
            "the digest appears in the build script (the extract assert)…",
            liveLineCount(gradle, skelSha) >= 1,
        )
        assertEquals(
            "…and exactly once in the backend, as the staging constant — a runtime version bump " +
                "therefore produces a NAMED build failure and a NAMED stage refusal, never a " +
                "mystery on a device",
            1,
            liveLineCount(backend, "\"$skelSha\""),
        )
    }

    // ------------------------------------------------------------------ the backend stage

    @Test
    fun theSkelIsStagedBeforeNativeInitAndAfterTheCompanionRefusal() {
        // ORDER, not presence — the statements all survive any permutation. Before nativeInit
        // because that is the dlopen that makes FastRPC go looking for the skel; after the
        // companion refusal (and every cheaper stage) because load's whole shape is
        // cheapest-refusal-first and the first arm of this stage writes 17.9 MB.
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
                "find; the skel ABOVE the companion refusal pays a 17.9 MB first-arm write on a " +
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

    @Test
    fun theSkelArmUsesTheMarkerFastPathNotAFullHashPerSession() {
        // The L3 handoff's explicit warning to this task: stagedPath full-hashes the destination
        // on EVERY arm — free at the melbank's 103 KB, a per-session 17.9 MiB flash read here.
        // The skel therefore takes the marker entry point; the mel arm keeps the original.
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
        assertEquals(
            "the asset is named by its real file name at the call",
            1,
            liveLineCount(loadBody, "\"libQnnHtpV75Skel.so\","),
        )
        assertTrue(
            "and the call is driven by the two named constants — the same two values the " +
                "extract task asserts at build time",
            liveLineCount(loadBody, "SKEL_BYTES,") == 1 && liveLineCount(loadBody, "SKEL_SHA256,") == 1,
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
