package com.whispereverywhere.tts

import com.whispereverywhere.npu.NpuPackFetch
import com.whispereverywhere.transcription.stream.StreamingPackCatalog
import com.whispereverywhere.transcription.stream.StreamingPackInstall
import com.whispereverywhere.transcription.stream.StreamingPackState
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TtsModelManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeTarBz2(dest: File, entries: Map<String, String>) {
        TarArchiveOutputStream(BZip2CompressorOutputStream(dest.outputStream())).use { tos ->
            for ((name, content) in entries) {
                if (name.endsWith("/")) {
                    tos.putArchiveEntry(TarArchiveEntry(name))
                    tos.closeArchiveEntry()
                } else {
                    val bytes = content.toByteArray()
                    val e = TarArchiveEntry(name).apply { size = bytes.size.toLong() }
                    tos.putArchiveEntry(e)
                    tos.write(bytes)
                    tos.closeArchiveEntry()
                }
            }
        }
    }

    @Test
    fun extract_stripsLeadingComponent_andPreservesTree() {
        val tar = tmp.newFile("m.tar.bz2")
        makeTarBz2(
            tar,
            mapOf(
                "kokoro-multi-lang-v1_1/" to "",
                "kokoro-multi-lang-v1_1/model.onnx" to "MODELBYTES",
                "kokoro-multi-lang-v1_1/espeak-ng-data/" to "",
                "kokoro-multi-lang-v1_1/espeak-ng-data/en_dict" to "DICT",
            ),
        )
        val dest = File(tmp.root, "out")
        TtsModelManager.extractTarBz2(tar, dest, stripLeadingComponent = true)

        assertEquals("MODELBYTES", File(dest, "model.onnx").readText())
        assertEquals("DICT", File(dest, "espeak-ng-data/en_dict").readText())
        // The leading archive dir itself must NOT appear inside dest.
        assertFalse(File(dest, "kokoro-multi-lang-v1_1").exists())
    }

    @Test
    fun extract_replacesExistingDestDir() {
        val tar = tmp.newFile("m.tar.bz2")
        makeTarBz2(tar, mapOf("root/fresh.txt" to "NEW"))
        val dest = File(tmp.root, "out").apply { mkdirs() }
        File(dest, "stale.txt").writeText("OLD")

        TtsModelManager.extractTarBz2(tar, dest, stripLeadingComponent = true)

        assertTrue(File(dest, "fresh.txt").exists())
        assertFalse("pre-existing content must be wiped", File(dest, "stale.txt").exists())
    }

    @Test(expected = TtsModelManager.TtsDownloadException::class)
    fun extract_rejectsPathTraversal() {
        val tar = tmp.newFile("evil.tar.bz2")
        makeTarBz2(tar, mapOf("root/../../evil.txt" to "PWNED"))
        TtsModelManager.extractTarBz2(tar, File(tmp.root, "out"), stripLeadingComponent = true)
    }

    @Test
    fun sizeTolerance_fivePercentBand() {
        val expected = 1_000_000L
        assertTrue(TtsModelManager.sizeWithinTolerance(1_000_000L, expected))
        assertTrue(TtsModelManager.sizeWithinTolerance(950_000L, expected))
        assertTrue(TtsModelManager.sizeWithinTolerance(1_050_000L, expected))
        assertFalse(TtsModelManager.sizeWithinTolerance(949_999L, expected))
        assertFalse(TtsModelManager.sizeWithinTolerance(1_050_001L, expected))
    }

    @Test
    fun sha256_matchesKnownVector() {
        val f = tmp.newFile("v.txt").apply { writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TtsModelManager.sha256HexFile(f),
        )
    }

    // ------------------------------------------------------------------ the Play pack source
    // (4.4.0, the 2026-09-10 amendment, Task 2b: the voice archive rides `tts_kokoro`, and the
    // GitHub download becomes the fallback for builds with no Play. These are the decisions that
    // route — the Android shell around them is `AssetPackManager`-bound and pinned as source.)

    @Test
    fun theDeliveredArchiveIsReadFromTheDirectoryNamedAfterThePack() {
        val assetsRoot = File(tmp.root, "assetpacks/1")
        assertEquals(
            "Play strips a #group_ suffix on delivery and this pack carries none, so the " +
                "archive is at <assetsPath>/tts_kokoro/kokoro-multi-lang-v1_0.tar.bz2 — the 4.2 " +
                "F8 rule is why the directory is named after the pack",
            File(File(assetsRoot, "tts_kokoro"), "kokoro-multi-lang-v1_0.tar.bz2"),
            TtsModelManager.packTarIn(assetsRoot),
        )
    }

    @Test
    fun anIncompleteDeliveryIsNeverAnInstallSource() {
        val assetsRoot = File(tmp.root, "assetpacks/1")
        assertFalse(
            "no Play, no pack module, or getPackLocation() answering null — all one answer",
            TtsModelManager.isPackComplete(null),
        )
        assertFalse(
            "the pack directory exists but the archive has not arrived",
            TtsModelManager.isPackComplete(assetsRoot),
        )
        val tar = TtsModelManager.packTarIn(assetsRoot).apply {
            parentFile!!.mkdirs()
            writeText("0123456789")
        }
        assertFalse(
            "a short archive is refused before anything is hashed — the same +-5 % band the " +
                "download route has always used, and the reason a truncated delivery cannot " +
                "reach the extractor",
            TtsModelManager.isPackComplete(assetsRoot),
        )
        assertTrue(
            "and an archive whose length matches is an install source (the expected size is a " +
                "parameter only so this row can be cheap: production passes TAR_BYTES)",
            TtsModelManager.isPackComplete(assetsRoot, expectedBytes = tar.length()),
        )
    }

    @Test
    fun theHeadroomRulesAreSpelledOnceSoBothRoutesGateAtTheSameNumbers() {
        // Truncation is why these are pinned as LITERALS rather than as the formula restated:
        // 349,906,910 x 1.4 is 489,869,673.99999994 in IEEE 754, so the rule lands on ...673.
        assertEquals(384_897_601L, TtsModelManager.stagedTarRequiredBytes())
        assertEquals(489_869_673L, TtsModelManager.extractRequiredBytes())
        assertTrue(
            "unpacking needs more room than staging the archive — the extracted tree is bigger " +
                "than the .bz2 it came from, which is why the pack route (no staging at all) " +
                "still has to gate",
            TtsModelManager.extractRequiredBytes() > TtsModelManager.stagedTarRequiredBytes(),
        )
        assertTrue(TtsModelManager.hasRoomToExtract(489_869_673L))
        assertFalse(TtsModelManager.hasRoomToExtract(489_869_672L))
        assertTrue(TtsModelManager.hasRoomToStageTar(384_897_601L))
        assertFalse(TtsModelManager.hasRoomToStageTar(384_897_600L))
    }

    @Test
    fun theInstallRouteTakesTheDeliveredPackAheadOfTheDownload() {
        assertEquals(
            "bytes already on the device: verify + extract, no network at any point",
            VoiceInstallRoute.FromPack,
            TtsModelManager.installRoute(StreamingPackState.PackDelivered),
        )
        assertEquals(
            "not delivered yet, but Play can serve this install: ASK PLAY — never the third-" +
                "party download, which is what the 2026-09-08 incident rode in on",
            VoiceInstallRoute.Fetch,
            TtsModelManager.installRoute(StreamingPackState.PackFetchable),
        )
        assertEquals(
            "the ONE place the GitHub download is still offered: an install Play cannot serve",
            VoiceInstallRoute.Download,
            TtsModelManager.installRoute(StreamingPackState.Downloadable),
        )
        assertEquals(
            "and an installed voice has nothing to install",
            VoiceInstallRoute.None,
            TtsModelManager.installRoute(StreamingPackState.Installed),
        )
    }

    // ------------------------------------------------------------------ the voice's own copy
    // `NpuPackFetch.FetchState.Failed.reason` is rendered VERBATIM by a card (its own KDoc says
    // so, and both NPU surfaces do it), so publishing that table's words on the read-aloud row
    // would tell users to tap 'Import model pair…' — the NPU chooser's SAF importer for whisper
    // GGML pairs, which is not on this row and could not read a Kokoro archive. The CLASSIFIER
    // stays that family's own (playRefusedThisInstall); only the words are ours.

    /** Every error code this build declares, plus one it has never heard of. */
    private val everyPlayErrorCode: List<Int> = listOf(
        NpuPackFetch.ERROR_NO_ERROR,
        NpuPackFetch.ERROR_APP_UNAVAILABLE,
        NpuPackFetch.ERROR_PACK_UNAVAILABLE,
        NpuPackFetch.ERROR_INVALID_REQUEST,
        NpuPackFetch.ERROR_DOWNLOAD_NOT_FOUND,
        NpuPackFetch.ERROR_API_NOT_AVAILABLE,
        NpuPackFetch.ERROR_NETWORK_ERROR,
        NpuPackFetch.ERROR_ACCESS_DENIED,
        NpuPackFetch.ERROR_INSUFFICIENT_STORAGE,
        NpuPackFetch.ERROR_PLAY_STORE_NOT_FOUND,
        NpuPackFetch.ERROR_APP_NOT_OWNED,
        NpuPackFetch.ERROR_CONFIRMATION_NOT_REQUIRED,
        NpuPackFetch.ERROR_UNRECOGNIZED_INSTALLATION,
        NpuPackFetch.ERROR_INTERNAL_ERROR,
        -12_345,
    )

    @Test
    fun noPlayRefusalTheVoiceRowCanShowNamesAControlItDoesNotHave() {
        for (code in everyPlayErrorCode) {
            val text = TtsModelManager.packRefusal(code)
            assertFalse(
                "code $code must not name the NPU chooser's importer: $text",
                text.contains("Import model pair"),
            )
            assertFalse(
                "code $code must not call a voice archive a 'model pair': $text",
                text.contains("model pair"),
            )
            assertFalse(
                "and must not borrow the previewer's noun either: $text",
                text.contains("preview model"),
            )
            assertTrue("code $code must say something", text.isNotBlank())
            assertTrue("code $code must end its sentence: $text", text.endsWith("."))
        }
        // The six the NPU table gets wrong for this feature are the six that must DIFFER.
        for (code in listOf(
            NpuPackFetch.ERROR_API_NOT_AVAILABLE,
            NpuPackFetch.ERROR_PLAY_STORE_NOT_FOUND,
            NpuPackFetch.ERROR_APP_NOT_OWNED,
            NpuPackFetch.ERROR_UNRECOGNIZED_INSTALLATION,
            NpuPackFetch.ERROR_APP_UNAVAILABLE,
            NpuPackFetch.ERROR_PACK_UNAVAILABLE,
        )) {
            assertNotEquals(
                "code $code is one of the six whose NPU sentence names the chooser's importer",
                NpuPackFetch.failureReason(code),
                TtsModelManager.packRefusal(code),
            )
        }
    }

    @Test
    fun theVoicePromisesTheDirectDownloadExactlyWhereTheLatchFlips() {
        // THE invariant that makes the sideload sentence honest rather than hopeful: the only
        // codes whose copy says "downloaded directly" are the codes playRefusedThisInstall
        // latches on — the ones after which state() really does offer the GitHub download.
        // Promise it anywhere else and the card names a route the row will not offer; withhold it
        // on the sideload family and the release sideloader reads a dead end.
        for (code in everyPlayErrorCode) {
            val latches = StreamingPackInstall.playRefusedThisInstall(
                NpuPackFetch.failureReason(code)
            )
            val promises = TtsModelManager.packRefusal(code).contains("downloaded directly")
            assertEquals("code $code: the sentence and the fallback latch must agree", latches, promises)
        }
    }

    @Test
    fun theStorageRefusalNamesPlaysRealSizeAndInventsNoNumber() {
        val named = TtsModelManager.packRefusal(
            NpuPackFetch.ERROR_INSUFFICIENT_STORAGE,
            TtsModelManager.TAR_BYTES,
        )
        assertTrue(
            "Play told us the size, so the refusal spends it — in the house badge, not raw bytes",
            named.contains(StreamingPackCatalog.sizeBadge(TtsModelManager.TAR_BYTES)),
        )
        val unnamed = TtsModelManager.packRefusal(NpuPackFetch.ERROR_INSUFFICIENT_STORAGE)
        assertFalse("Play never said, so no number is invented: $unnamed", unnamed.contains("MB"))
    }

    @Test
    fun aFailurePlayGaveNoErrorCodeForIsNamedByItsStatusNumber() {
        // `advance` maps THREE things to Failed: the FAILED status (which carries a code), the
        // UNKNOWN status, and any status the library adds later. The last two carry no code at
        // all, so naming them by whatever errorCode() returned beside them would be a guess
        // dressed as a fact.
        assertEquals(
            TtsModelManager.packRefusal(NpuPackFetch.ERROR_NETWORK_ERROR, 10L),
            TtsModelManager.deliveryRefusal(NpuPackFetch.STATUS_FAILED, NpuPackFetch.ERROR_NETWORK_ERROR, 10L),
        )
        val unknown = TtsModelManager.deliveryRefusal(NpuPackFetch.STATUS_UNKNOWN, 0, 0L)
        assertTrue("the status NUMBER is named: $unknown", unknown.contains("(0)"))
        assertFalse(
            "and it is NOT told as 'a failure without naming a reason', which is code 0's line",
            unknown == TtsModelManager.packRefusal(NpuPackFetch.ERROR_NO_ERROR),
        )
    }

    @Test
    fun theInstallRowNamesTheSourceItWillActuallyUse() {
        val fetch = TtsModelManager.installRowSubtitle(VoiceInstallRoute.Fetch)
        assertTrue("the Play route says where the bytes come from: $fetch", fetch.contains("Google Play"))
        assertFalse(
            "and must not promise a download from anywhere else — on a Play install there is " +
                "no third-party transfer at all, which is the whole point of Task 2b: $fetch",
            fetch.contains("download"),
        )
        assertTrue(
            "the badge is the archive's real size (the row said 365 MB for a 350 MB archive " +
                "before this task), and it comes from the one house formatter",
            fetch.contains(StreamingPackCatalog.sizeBadge(TtsModelManager.TAR_BYTES)),
        )
        val download = TtsModelManager.installRowSubtitle(VoiceInstallRoute.Download)
        assertTrue("the fallback route is honest about being a download: $download", download.contains("download"))
        val fromPack = TtsModelManager.installRowSubtitle(VoiceInstallRoute.FromPack)
        assertFalse("delivered bytes are not downloaded again: $fromPack", fromPack.contains("download"))
        // Total, and no two routes read the same — a row that cannot tell the user which of the
        // four things is about to happen is a row that will surprise them.
        val titles = VoiceInstallRoute.entries.map { TtsModelManager.installRowTitle(it) }
        assertEquals(VoiceInstallRoute.entries.size, titles.distinct().size)
        for (t in titles) assertTrue("every route has a title", t.isNotBlank())
        for (r in VoiceInstallRoute.entries) {
            assertTrue("every route has a subtitle", TtsModelManager.installRowSubtitle(r).isNotBlank())
        }
    }

    @Test
    fun theRowsFetchLineIsTotalOverTheFetchMachineAndSilentOnlyAtRest() {
        val silent = listOf(
            NpuPackFetch.FetchState.Idle,
            NpuPackFetch.FetchState.Installed,
            NpuPackFetch.FetchState.Cancelled,
        )
        for (state in silent) {
            assertEquals(
                "at rest the row goes back to its own offer — a stale 'fetching…' line under an " +
                    "installed voice is a lie the user cannot dismiss",
                null,
                TtsModelManager.fetchLine(state),
            )
        }
        val speaking = listOf(
            NpuPackFetch.FetchState.Pending,
            NpuPackFetch.FetchState.Downloading(1_000_000L, 349_906_910L),
            NpuPackFetch.FetchState.Downloading(0L, 0L),
            NpuPackFetch.FetchState.Transferring,
            NpuPackFetch.FetchState.Verifying(0L, 0L),
            NpuPackFetch.FetchState.NeedsConfirmation,
            NpuPackFetch.FetchState.Failed("the card's own words."),
        )
        for (state in speaking) {
            val line = TtsModelManager.fetchLine(state)
            assertTrue("$state must narrate itself", !line.isNullOrBlank())
        }
        assertEquals(
            "a Failed shows the refusal VERBATIM — the shell has already re-told it in this " +
                "feature's words, so re-wording it here would be a second copy of the copy",
            "the card's own words.",
            TtsModelManager.fetchLine(NpuPackFetch.FetchState.Failed("the card's own words.")),
        )
        assertFalse(
            "and an unknown total invents no denominator",
            TtsModelManager.fetchLine(NpuPackFetch.FetchState.Downloading(0L, 0L))!!.contains("of 0"),
        )
    }

    @Test
    fun aRepairTakesTheSameRouteAFirstInstallWouldHaveTaken() {
        for (via in listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            assertEquals(
                "a half or disowned install repairs from the source it would have installed " +
                    "from — so a delivered pack repairs without touching the network",
                TtsModelManager.installRoute(via),
                TtsModelManager.installRoute(StreamingPackState.Repair(via)),
            )
        }
    }
}
