package com.whispereverywhere.tts

import com.whispereverywhere.transcription.stream.StreamingPackState
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
