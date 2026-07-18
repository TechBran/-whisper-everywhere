package com.whispereverywhere.tts

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
}
