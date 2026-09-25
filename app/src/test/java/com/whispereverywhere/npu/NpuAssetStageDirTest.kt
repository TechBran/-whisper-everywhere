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
 * THE DIRECTORY STAGE (P2-6, the MediaTek APU tier; design §2.6) — `NpuAssetStage.stageIntoDir`,
 * executed against a temp dir: the MediaTek dispatch staged into a directory that LiteRT SCANS
 * and the Neuron adapter walk probes (`<dir>/libneuron_adapter.so`), so the directory must hold
 * exactly the one file — and the stage's own working files, the `.part` in flight and the
 * `.staged` marker, live OUTSIDE it, in `<dir>.staged/`.
 *
 * Every other decision is the marker stage's (`NpuSkelPackagingTest` executes that one), and the
 * cases below re-prove the ones that matter with the markers moved: the fast arm does not re-read,
 * a new expectation re-stages, a refusal leaves nothing behind anywhere. The `Context` half and the
 * dispatch's one caller-to-be (`LiteRtRuntime.stagedDispatchDir`) are pinned as source at the end;
 * both files are in `sourcePinnedInputs`.
 */
class NpuAssetStageDirTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val name = LiteRtRuntime.DISPATCH_ASSET

    private val payload: ByteArray = ByteArray(8192) { (it * 53 % 251).toByte() }

    private fun sha256(of: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(of).joinToString("") { "%02x".format(it) }

    private val digest: String by lazy { sha256(payload) }

    private fun dir(): File = File(temp.root, NpuApuDriverCheck.DISPATCH_DIR_NAME)

    private fun markerDir(): File = NpuAssetStage.markerDirFor(dir())

    private fun stageDir(
        expectedBytes: Long = payload.size.toLong(),
        expectedSha256: String = digest,
        open: () -> InputStream = { ByteArrayInputStream(payload) },
    ): NpuAssetStage.StageResult =
        NpuAssetStage.stageIntoDir(dir(), markerDir(), name, expectedBytes, expectedSha256, open)

    private fun listing(d: File): List<String> = (d.listFiles() ?: emptyArray()).map { it.name }.sorted()

    @Test
    fun theWorkingFilesLiveBesideTheScannedDirectoryNeverInIt() {
        assertEquals(
            "filesDir/litert_dispatch keeps its working files in filesDir/litert_dispatch.staged — " +
                "a sibling, never a child",
            File(temp.root, "litert_dispatch.staged"),
            markerDir(),
        )
        val result = stageDir()
        assertTrue("a matching asset stages: $result", result is NpuAssetStage.StageResult.Staged)
        assertEquals(
            "the scanned directory holds EXACTLY the dispatch — no .part, no .staged, nothing LiteRT " +
                "or the adapter walk could read",
            listOf(name),
            listing(dir()),
        )
        assertTrue("…the staged bytes are the asset's", File(dir(), name).readBytes().contentEquals(payload))
        assertEquals(
            "the marker is beside it, in the staged directory, and nothing else is left there",
            listOf(name + NpuAssetStage.MARKER_SUFFIX),
            listing(markerDir()),
        )
    }

    @Test
    fun aSecondStageVouchesByMarkerWithoutReadingTheSource() {
        assertTrue(stageDir() is NpuAssetStage.StageResult.Staged)
        var opened = false
        val again = stageDir(open = { opened = true; ByteArrayInputStream(payload) })
        assertTrue("the second arm answers Staged", again is NpuAssetStage.StageResult.Staged)
        assertFalse(
            "and never opened the asset — every arm after the first costs a few stats and one " +
                "listing of the directory",
            opened,
        )
        assertEquals(listOf(name), listing(dir()))
    }

    @Test
    fun anythingElseInTheScannedDirectoryIsRemovedBeforeTheStage() {
        dir().mkdirs()
        // A planted adapter (the walk's fourth candidate), a stale fragment of an older layout,
        // and a directory: all three are things the runtime would scan or dlopen.
        File(dir(), "libneuron_adapter.so").writeText("not ours")
        File(dir(), "$name${NpuAssetStage.PART_SUFFIX}").writeBytes(payload.copyOf(100))
        File(dir(), "nested").mkdirs()
        File(dir(), "nested/x.so").writeText("x")
        assertTrue(stageDir() is NpuAssetStage.StageResult.Staged)
        assertEquals("only the dispatch survives in the scanned directory", listOf(name), listing(dir()))
        // …and the rule holds on the FAST arm too: a stray arriving after a vouched stage goes.
        File(dir(), "libneuron_adapter.so").writeText("planted later")
        var opened = false
        assertTrue(stageDir(open = { opened = true; ByteArrayInputStream(payload) }) is NpuAssetStage.StageResult.Staged)
        assertEquals(listOf(name), listing(dir()))
        assertFalse("the vouched dispatch itself was not re-read to clear a stray", opened)
    }

    @Test
    fun aRefusedStageLeavesNoDispatchNoPartAndNoMarkerAnywhere() {
        val short = payload.copyOf(payload.size - 1)
        val refused = stageDir(open = { ByteArrayInputStream(short) })
        assertTrue("a short asset refuses", refused is NpuAssetStage.StageResult.Refused)
        assertEquals("the scanned directory is empty — never half a dispatch", emptyList<String>(), listing(dir()))
        assertEquals("and the staged directory holds no fragment and no marker", emptyList<String>(), listing(markerDir()))
        val missing = stageDir(open = { throw IOException("asset missing from this build") })
        assertTrue(missing is NpuAssetStage.StageResult.Refused)
        assertTrue(
            "the refusal names the asset and the cause: $missing",
            (missing as NpuAssetStage.StageResult.Refused).reason.contains(name) &&
                missing.reason.contains("asset missing from this build"),
        )
        assertEquals(emptyList<String>(), listing(dir()))
        assertEquals(emptyList<String>(), listing(markerDir()))
    }

    @Test
    fun aMarkerForAnotherExpectationForcesTheFullRestage() {
        assertTrue(stageDir() is NpuAssetStage.StageResult.Staged)
        val next = ByteArray(payload.size) { (it * 7 % 251).toByte() }
        var opened = false
        val result = stageDir(expectedSha256 = sha256(next), open = { opened = true; ByteArrayInputStream(next) })
        assertTrue("an APK carrying a new dispatch re-stages: $result", result is NpuAssetStage.StageResult.Staged)
        assertTrue("…through the full path: the old marker cannot vouch for a new digest", opened)
        assertTrue("the new bytes are in place", File(dir(), name).readBytes().contentEquals(next))
        assertEquals(listOf(name), listing(dir()))
        assertEquals(listOf(name + NpuAssetStage.MARKER_SUFFIX), listing(markerDir()))
    }

    @Test
    fun aFileWhereTheDirectoryShouldBeIsARefusalNeverAThrow() {
        dir().writeText("a stray file under the directory's name")
        val result = stageDir()
        assertTrue(
            "the stage refuses, naming the path, and throws nothing: $result",
            result is NpuAssetStage.StageResult.Refused &&
                result.reason.contains(dir().absolutePath),
        )
    }

    // ------------------------------------------------------------------ the Context half, as source

    private fun read(relative: String): String {
        var d: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (d != null) {
            for (candidate in listOf(File(d, relative), File(d, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            d = d.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun liveLineCount(scope: String, needle: String): Int =
        scope.lineSequence().count { line ->
            val t = line.trimStart()
            !(t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) && line.contains(needle)
        }

    @Test
    fun theContextEntryPointIsSerialisedAndStagesTheAssetIntoItsOwnMarkerDirectory() {
        val stage = read("src/main/java/com/whispereverywhere/npu/NpuAssetStage.kt")
        val entry = stage.substringAfter("    fun stagedDirWithMarker(").substringBefore("\n    }\n")
        assertTrue("the entry point was found", entry.length < stage.length)
        assertEquals(
            "one lock across every directory stage — staging never interleaves with itself",
            1, liveLineCount(entry, "= synchronized(dirStageLock) {"),
        )
        assertEquals(
            "the working files go to the scanned directory's own sibling",
            1, liveLineCount(entry, "stageIntoDir(dir, markerDirFor(dir), assetName, expectedBytes, expectedSha256) {"),
        )
        assertEquals("the bytes come out of the APK's assets", 1, liveLineCount(entry, "context.assets.open(assetName)"))
        assertEquals(
            "and it answers the DIRECTORY — what LiteRT's dispatch-dir option takes — or null",
            1, liveLineCount(entry, "pathOrRefusal(outcome)?.let { dir.absolutePath }"),
        )
    }

    @Test
    fun theDispatchIsStagedIntoTheOneDispatchDirectoryUnderItsPinnedIdentity() {
        val runtime = read("src/main/java/com/whispereverywhere/npu/LiteRtRuntime.kt")
        assertEquals(
            "the directory is NpuApuDriverCheck.dispatchDir's — the one the probe and the engine " +
                "take — never a second spelling",
            1, liveLineCount(runtime, "NpuApuDriverCheck.dispatchDir(context.filesDir),"),
        )
        assertEquals("…so the name appears nowhere in this file", 0, liveLineCount(runtime, "litert_dispatch"))
        assertEquals(
            "through the directory stage, with the constants below",
            1, liveLineCount(runtime, "NpuAssetStage.stagedDirWithMarker("),
        )
        assertEquals("the dispatch's file name", "libLiteRtDispatch_MediaTek.so", LiteRtRuntime.DISPATCH_ASSET)
        assertEquals("its exact length, the v2.1.1 zip member's", 409_728L, LiteRtRuntime.DISPATCH_BYTES)
        assertEquals(
            "its sha256 AS THE ZIP MEMBER — the design's pin, never AGP's stripped f47bd9c0…",
            "9e963c56a65b6146b0e94aed82dd0f73dbaee6805fc6ae090580565b57680706",
            LiteRtRuntime.DISPATCH_SHA256,
        )
    }
}
