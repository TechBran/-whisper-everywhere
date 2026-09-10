package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class StreamingPackInstallTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A tiny pack with real sizes and hashes for four synthetic files. */
    private val enc = "ENCODER".toByteArray()
    private val dec = "DECODER-BYTES".toByteArray()
    private val joi = "J".toByteArray()
    private val tok = "<blk>\n<sos/eos>\n<unk>\n".toByteArray()
    private val pack = StreamingPack(
        language = "en", dirName = "en-test", packName = "preview_test",
        baseUrl = "https://example.invalid/resolve/abc/",
        encoder = PackFile("encoder.onnx", enc.size.toLong(), sha(enc)),
        decoder = PackFile("decoder.onnx", dec.size.toLong(), sha(dec)),
        joiner = PackFile("joiner.onnx", joi.size.toLong(), sha(joi)),
        tokens = PackFile("tokens.txt", tok.size.toLong(), sha(tok)),
    )

    private fun stage(vararg overrides: Pair<String, ByteArray>): File {
        val staged = tmp.newFolder("staged-" + System.nanoTime())
        val contents = mutableMapOf("encoder.onnx" to enc, "decoder.onnx" to dec, "joiner.onnx" to joi, "tokens.txt" to tok)
        overrides.forEach { (n, b) -> contents[n] = b }
        contents.forEach { (n, b) -> File(staged, n).writeBytes(b) }
        return staged
    }

    @Test fun verifyPassesTheExactFiles() {
        assertEquals(PackVerdict.Ok, StreamingPackInstall.verify(stage(), pack))
    }

    @Test fun verifyRefusesAShortFileBeforeHashingAnything() {
        // The size gate is EXACT: these are pinned files at an immutable commit, so a ±5 % band
        // would only admit a wrong file. A mismatch names the file (a filename is not content).
        val v = StreamingPackInstall.verify(stage("decoder.onnx" to "DECODER".toByteArray()), pack)
        assertEquals(PackVerdict.SizeMismatch("decoder.onnx", 7L, dec.size.toLong()), v)
    }

    @Test fun verifyRefusesASameSizeFileWhoseHashDiffers() {
        val v = StreamingPackInstall.verify(stage("encoder.onnx" to "ENCODEX".toByteArray()), pack)
        assertEquals(PackVerdict.HashMismatch("encoder.onnx"), v)
    }

    @Test fun verifyNamesAMissingFile() {
        val staged = stage()
        File(staged, "tokens.txt").delete()
        assertEquals(PackVerdict.Missing("tokens.txt"), StreamingPackInstall.verify(staged, pack))
    }

    @Test fun installMovesTheFilesWritesTheMarkerAndIsInstalledAfterwards() {
        val root = tmp.newFolder("root")
        val staged = stage()
        StreamingPackInstall.install(staged, root, pack)
        val dir = StreamingPackInstall.installDir(root, pack)
        assertTrue(StreamingPackInstall.isInstalled(dir, pack))
        assertEquals(StreamingPackCatalog.markerText(pack), StreamingPackInstall.marker(dir).readText())
        assertEquals("ENCODER", File(dir, "encoder.onnx").readText())
        assertFalse("the staged copies are moved, not duplicated", File(staged, "encoder.onnx").exists())
        assertFalse("no .tmp dir survives a completed install", StreamingPackInstall.tmpDir(root, pack).exists())
    }

    @Test fun installReplacesAPreviousInstallAtomically() {
        val root = tmp.newFolder("root")
        StreamingPackInstall.install(stage(), root, pack)
        val dir = StreamingPackInstall.installDir(root, pack)
        File(dir, "stale.bin").writeText("old")
        StreamingPackInstall.install(stage(), root, pack)
        assertTrue(StreamingPackInstall.isInstalled(dir, pack))
        assertFalse("the old directory is REPLACED, not merged", File(dir, "stale.bin").exists())
    }

    @Test fun isInstalledIsAMarkerPlusFourExactLengths_neverAHash() {
        val root = tmp.newFolder("root")
        StreamingPackInstall.install(stage(), root, pack)
        val dir = StreamingPackInstall.installDir(root, pack)
        // Same length, different bytes: isInstalled still says yes — it is the session-start
        // read and must be a length read (a 71 MB hash on every session start is not free).
        // The load-time canary and the recognizer's own refusal cover the rest.
        File(dir, "encoder.onnx").writeBytes("ENCODEX".toByteArray())
        assertTrue(StreamingPackInstall.isInstalled(dir, pack))
        // A short file, or a missing marker, says no.
        File(dir, "encoder.onnx").writeBytes("ENC".toByteArray())
        assertFalse(StreamingPackInstall.isInstalled(dir, pack))
        File(dir, "encoder.onnx").writeBytes(enc)
        assertTrue(StreamingPackInstall.isInstalled(dir, pack))
        StreamingPackInstall.marker(dir).delete()
        assertFalse(StreamingPackInstall.isInstalled(dir, pack))
    }

    @Test fun markCorruptRemovesOnlyTheMarker() {
        val root = tmp.newFolder("root")
        StreamingPackInstall.install(stage(), root, pack)
        val dir = StreamingPackInstall.installDir(root, pack)
        StreamingPackInstall.markCorrupt(root, pack)
        assertFalse(StreamingPackInstall.isInstalled(dir, pack))
        assertTrue("the bytes stay; only the verdict is withdrawn", File(dir, "encoder.onnx").exists())
    }

    @Test fun deleteRemovesTheInstallAndAnyTmp() {
        val root = tmp.newFolder("root")
        StreamingPackInstall.install(stage(), root, pack)
        StreamingPackInstall.tmpDir(root, pack).mkdirs()
        StreamingPackInstall.delete(root, pack)
        assertFalse(StreamingPackInstall.installDir(root, pack).exists())
        assertFalse(StreamingPackInstall.tmpDir(root, pack).exists())
    }

    @Test fun sha256HexMatchesTheJdk() {
        val f = tmp.newFile("x")
        f.writeBytes(enc)
        assertEquals(sha(enc), StreamingPackInstall.sha256Hex(f))
    }

    // ------------------------------------------------ the Play pack as a SOURCE (AMENDMENT)

    @Test fun theDeliveredPackDirectoryIsNamedAfterThePackUnderPlaysAssetsRoot() {
        // `AssetPackLocation.assetsPath()` is the pack's assets ROOT; the four files sit in a
        // directory named after the pack, because Play strips a `#group_<g>` suffix on delivery
        // and this pack has none (WhisperModelManager.installFromPack reads the NPU packs the
        // same way). A row with no Play pack has no such directory at all.
        val assetsRoot = tmp.newFolder("play-assets")
        assertEquals(
            File(assetsRoot, "preview_test"),
            StreamingPackInstall.packSourceDir(assetsRoot, pack),
        )
        val fallbackOnly = pack.copy(packName = null)
        assertNull(StreamingPackInstall.packSourceDir(assetsRoot, fallbackOnly))
    }

    @Test fun installFromAPackCOPIESitsSource_becausePlayOwnsThoseBytes() {
        // THE ONE THING THE TWO SOURCES DO NOT SHARE. A download's staging dir is ours to empty,
        // so install MOVES out of it; the delivered pack's directory is PLAY'S, and the only
        // copy of those bytes until `removePack` — a rename that failed across filesystems and
        // fell back to copy-then-delete would delete Play's file mid-install.
        val root = tmp.newFolder("root")
        val delivered = stage()
        StreamingPackInstall.install(delivered, root, pack, moveSource = false)
        val dir = StreamingPackInstall.installDir(root, pack)
        assertTrue(StreamingPackInstall.isInstalled(dir, pack))
        assertEquals("ENCODER", File(dir, "encoder.onnx").readText())
        for (f in pack.files) {
            assertTrue(
                "${f.name} must survive in the delivered pack — Play owns it",
                File(delivered, f.name).exists(),
            )
        }
    }

    @Test fun oneVerificationServesBothSources() {
        // The pack's copy and the download's copy go through the SAME function, exact size then
        // sha256, so a corrupt pack cannot be installed on a route a corrupt download could not.
        val delivered = stage("joiner.onnx" to "X".toByteArray())
        assertEquals(PackVerdict.HashMismatch("joiner.onnx"), StreamingPackInstall.verify(delivered, pack))
        // …and the pack's own extra files (the module's committed .gitkeep) are simply not read.
        val clean = stage()
        File(clean, ".gitkeep").writeText("")
        assertEquals(PackVerdict.Ok, StreamingPackInstall.verify(clean, pack))
        StreamingPackInstall.install(clean, tmp.newFolder("root2"), pack, moveSource = false)
    }

    @Test fun packCompleteIsTheSameLengthReadAsIsInstalled_withoutTheMarker() {
        // A delivered pack carries no `.installed` marker (the installer writes that, LAST, at
        // the destination), so "is the pack usable as a source" is the four exact lengths alone.
        val delivered = stage()
        assertTrue(StreamingPackInstall.isPackComplete(delivered, pack))
        assertFalse(
            "and a marker is NOT required of a pack directory",
            StreamingPackInstall.marker(delivered).isFile,
        )
        File(delivered, "tokens.txt").writeBytes("short".toByteArray())
        assertFalse(StreamingPackInstall.isPackComplete(delivered, pack))
        File(delivered, "joiner.onnx").delete()
        assertFalse(StreamingPackInstall.isPackComplete(delivered, pack))
        assertFalse("a null directory is not a complete pack", StreamingPackInstall.isPackComplete(null, pack))
    }

    // ------------------------------------------------ the state machine over the four cases

    @Test fun packPresentOffersALocalInstallWithNoNetwork() {
        assertEquals(
            StreamingPackState.PackDelivered,
            StreamingPackInstall.resolve(
                installed = false, installDirPresent = false,
                packComplete = true, playCanDeliver = true,
            ),
        )
        // Even on a build Play would refuse: a pack already on disk needs no Play at all.
        assertEquals(
            StreamingPackState.PackDelivered,
            StreamingPackInstall.resolve(
                installed = false, installDirPresent = false,
                packComplete = true, playCanDeliver = false,
            ),
        )
    }

    @Test fun packAbsentOnAPlayInstallOffersTheFetch_neverTheThirdPartyDownload() {
        assertEquals(
            StreamingPackState.PackFetchable,
            StreamingPackInstall.resolve(
                installed = false, installDirPresent = false,
                packComplete = false, playCanDeliver = true,
            ),
        )
    }

    @Test fun theFallbackIsOfferedExactlyWherePlayCannotDeliver() {
        // The single discriminator: a debug build, a sideload, or an install Play has already
        // refused by name. Everywhere else the model is "included with the app" and fetched.
        assertEquals(
            StreamingPackState.Downloadable,
            StreamingPackInstall.resolve(
                installed = false, installDirPresent = false,
                packComplete = false, playCanDeliver = false,
            ),
        )
    }

    @Test fun anInstalledPackIsInstalledWhateverTheSourceOrTheStoreSays() {
        for (packComplete in listOf(true, false)) {
            for (play in listOf(true, false)) {
                assertEquals(
                    StreamingPackState.Installed,
                    StreamingPackInstall.resolve(
                        installed = true, installDirPresent = true,
                        packComplete = packComplete, playCanDeliver = play,
                    ),
                )
            }
        }
    }

    @Test fun corruptBytesReadRepair_andRepairNamesTheSourceItWouldRepairFrom() {
        // `markCorrupt` withdrew the marker, or a file went short: the row reads Repair rather
        // than Download, and the source it would repair from is the same triage as a first
        // install — a delivered pack repairs with no network at all.
        assertEquals(
            StreamingPackState.Repair(StreamingPackState.PackDelivered),
            StreamingPackInstall.resolve(
                installed = false, installDirPresent = true,
                packComplete = true, playCanDeliver = true,
            ),
        )
        assertEquals(
            StreamingPackState.Repair(StreamingPackState.PackFetchable),
            StreamingPackInstall.resolve(
                installed = false, installDirPresent = true,
                packComplete = false, playCanDeliver = true,
            ),
        )
        assertEquals(
            StreamingPackState.Repair(StreamingPackState.Downloadable),
            StreamingPackInstall.resolve(
                installed = false, installDirPresent = true,
                packComplete = false, playCanDeliver = false,
            ),
        )
    }

    @Test fun onlyInstalledEverLetsTheRecognizerLoad() {
        // The previewer's gate reads exactly one Boolean off the state, and Repair is NOT it:
        // a half-installed pack must never be opened (spec §6, "corrupt or missing at load").
        val states = listOf(
            StreamingPackState.Installed to true,
            StreamingPackState.PackDelivered to false,
            StreamingPackState.PackFetchable to false,
            StreamingPackState.Downloadable to false,
            StreamingPackState.Repair(StreamingPackState.PackFetchable) to false,
        )
        for ((state, loadable) in states) {
            assertEquals("$state", loadable, state.isInstalled)
        }
    }

    @Test fun theFallbackDiscriminatorIsOneFunctionOverTwoFacts() {
        // A release install Play has never refused is the ONLY case that asks Play.
        assertTrue(StreamingPackInstall.playCanDeliver(isDebugBuild = false, playRefused = false))
        assertFalse(
            "a debug build carries no asset packs at all — the fallback IS its install path",
            StreamingPackInstall.playCanDeliver(isDebugBuild = true, playRefused = false),
        )
        assertFalse(StreamingPackInstall.playCanDeliver(isDebugBuild = false, playRefused = true))
        assertFalse(StreamingPackInstall.playCanDeliver(isDebugBuild = true, playRefused = true))
    }

    @Test fun theSingleFlightPredicateIsTotalOverTheFetchMachinesStates() {
        // The fetch shell's ONE guard against a second `fetch` on top of a live one. Total over
        // the machine on purpose: a state added to NpuPackFetch later must be classified here
        // rather than fall through a wildcard into "not busy". NeedsConfirmation counts as in
        // flight — Play is waiting for the user's answer to Play's OWN dialog, and a second fetch
        // would ask it twice.
        val busy = listOf<com.whispereverywhere.npu.NpuPackFetch.FetchState>(
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Pending,
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Downloading(1L, 2L),
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Transferring,
            com.whispereverywhere.npu.NpuPackFetch.FetchState.NeedsConfirmation,
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Verifying(0L, 2L),
        )
        val free = listOf<com.whispereverywhere.npu.NpuPackFetch.FetchState>(
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Idle,
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Installed,
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Failed("any reason at all"),
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Cancelled,
        )
        for (s in busy) assertTrue("$s is in flight", StreamingPackInstall.fetchInFlight(s))
        for (s in free) {
            assertFalse(
                "$s is terminal — retry is the same entry point called again",
                StreamingPackInstall.fetchInFlight(s),
            )
        }
    }

    @Test fun aRefusalPlayNamedAsThisInstallsOwnFaultTurnsOffTheFetchOffer() {
        // REUSED, not forked: the sideload family is `NpuPackFetch`'s own — the four codes whose
        // reason carries `OnboardingLogic.SIDELOAD_MARKER` — so the previewer and the NPU tiers
        // cannot disagree about which failures mean "Play will never serve this install".
        val sideload = com.whispereverywhere.npu.NpuPackFetch.failureReason(
            com.whispereverywhere.npu.NpuPackFetch.ERROR_APP_NOT_OWNED
        )
        assertTrue(StreamingPackInstall.playRefusedThisInstall(sideload))
        val transient = com.whispereverywhere.npu.NpuPackFetch.failureReason(
            com.whispereverywhere.npu.NpuPackFetch.ERROR_NETWORK_ERROR
        )
        assertFalse(
            "a network failure is not an install failure — the fetch stays the offer",
            StreamingPackInstall.playRefusedThisInstall(transient),
        )
        for (code in listOf(
            com.whispereverywhere.npu.NpuPackFetch.ERROR_API_NOT_AVAILABLE,
            com.whispereverywhere.npu.NpuPackFetch.ERROR_PLAY_STORE_NOT_FOUND,
            com.whispereverywhere.npu.NpuPackFetch.ERROR_APP_NOT_OWNED,
            com.whispereverywhere.npu.NpuPackFetch.ERROR_UNRECOGNIZED_INSTALLATION,
        )) {
            assertTrue(
                "code $code is the install's own fault and must flip the offer to the fallback",
                StreamingPackInstall.playRefusedThisInstall(
                    com.whispereverywhere.npu.NpuPackFetch.failureReason(code)
                ),
            )
        }
    }
}
