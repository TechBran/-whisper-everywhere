package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        modelType = "zipformer2", decodeChunkLen = 32, encoderT = 45,
        caseFold = CaseFold.Fold,
        emitsPunctuation = false, emitsDigits = false,
        canaryAsset = StreamingPackCatalog.EN.canaryAsset,
        canaryRule = StreamingPackCatalog.EN.canaryRule,
    )

    private fun stage(vararg overrides: Pair<String, ByteArray>): File {
        val staged = tmp.newFolder("staged-" + System.nanoTime())
        val contents = mutableMapOf("encoder.onnx" to enc, "decoder.onnx" to dec, "joiner.onnx" to joi, "tokens.txt" to tok)
        overrides.forEach { (n, b) -> contents[n] = b }
        contents.forEach { (n, b) -> File(staged, n).writeBytes(b) }
        return staged
    }

    /**
     * A SECOND language's pack, distinguishable from [pack] in every field the installer keys on:
     * a different `dirName` (the install directory), a different `packName` (Play's delivery
     * directory) and a different `language` (the catalogue's and the flag's key).
     */
    private val second = StreamingPack(
        language = "es", dirName = "es-test", packName = "preview_test_es",
        baseUrl = "https://example.invalid/resolve/def/",
        encoder = PackFile("encoder.onnx", enc.size.toLong(), sha(enc)),
        decoder = PackFile("decoder.onnx", dec.size.toLong(), sha(dec)),
        joiner = PackFile("joiner.onnx", joi.size.toLong(), sha(joi)),
        tokens = PackFile("tokens.txt", tok.size.toLong(), sha(tok)),
        modelType = "zipformer2", decodeChunkLen = 32, encoderT = 45,
        caseFold = CaseFold.Fold,
        emitsPunctuation = false, emitsDigits = false,
        canaryAsset = StreamingPackCatalog.EN.canaryAsset,
        canaryRule = StreamingPackCatalog.EN.canaryRule,
    )

    // ------------------------------------------------------------- the store is a SET, not a slot

    @Test fun installingASecondLanguageLeavesTheFirstInstalledAndLocatable() {
        // Owner ruling 2026-09-11, consequence 3: *"So a user can have multiple languages loaded
        // onto their app. Since they're so small, you know, maybe a person uses three different
        // languages sometimes for transcriptions."* Installing Spanish must not remove English.
        //
        // Nothing in the install path may assume ONE previewer model exists at a time, and this
        // is the property that says so: every path is keyed by the pack (`installDir`, `tmpDir`,
        // `marker`, `isInstalled`), so two rows land in two directories under one root.
        val root = tmp.newFolder("root")
        StreamingPackInstall.install(stage(), root, pack)
        assertTrue(StreamingPackInstall.isInstalled(StreamingPackInstall.installDir(root, pack), pack))

        StreamingPackInstall.install(stage(), root, second)

        assertTrue(
            "the first language is still installed after the second lands",
            StreamingPackInstall.isInstalled(StreamingPackInstall.installDir(root, pack), pack),
        )
        assertTrue(
            "and so is the second",
            StreamingPackInstall.isInstalled(StreamingPackInstall.installDir(root, second), second),
        )
        assertNotEquals(
            "in directories of their own — one install directory for two packs is the slot this " +
                "ruling forbids",
            StreamingPackInstall.installDir(root, pack).absolutePath,
            StreamingPackInstall.installDir(root, second).absolutePath,
        )
        assertNotEquals(
            "with staging directories of their own too, so a second install cannot half-write " +
                "the first",
            StreamingPackInstall.tmpDir(root, pack).absolutePath,
            StreamingPackInstall.tmpDir(root, second).absolutePath,
        )
        // ...and each one's marker is its OWN census, so a cross-verified install is impossible.
        assertEquals(
            StreamingPackCatalog.markerText(pack),
            StreamingPackInstall.marker(StreamingPackInstall.installDir(root, pack)).readText(),
        )
        assertEquals(
            StreamingPackCatalog.markerText(second),
            StreamingPackInstall.marker(StreamingPackInstall.installDir(root, second)).readText(),
        )
    }

    @Test fun removingOneLanguageLeavesTheOtherUntouched() {
        // AF8: *"Delete one language's pack and reopen: that one stays gone; another installed
        // language is untouched and its live words still work."*
        val root = tmp.newFolder("root")
        StreamingPackInstall.install(stage(), root, pack)
        StreamingPackInstall.install(stage(), root, second)

        StreamingPackInstall.delete(root, second)

        assertFalse(
            StreamingPackInstall.isInstalled(StreamingPackInstall.installDir(root, second), second),
        )
        assertTrue(
            "a delete is per pack, like every other operation in this object",
            StreamingPackInstall.isInstalled(StreamingPackInstall.installDir(root, pack), pack),
        )
        // And a withdrawn verdict is per pack as well: markCorrupt on one must not send the other
        // to the Repair row.
        StreamingPackInstall.install(stage(), root, second)
        StreamingPackInstall.markCorrupt(root, second)
        assertFalse(
            StreamingPackInstall.isInstalled(StreamingPackInstall.installDir(root, second), second),
        )
        assertTrue(
            StreamingPackInstall.isInstalled(StreamingPackInstall.installDir(root, pack), pack),
        )
    }

    @Test fun aPackLocatedInPlaysDeliveredAssetsIsLocatedByItsOwnName() {
        // The delivered-pack source directory is the PACK'S name, so two delivered packs are two
        // directories and `isPackComplete` cannot read one language's bytes as another's.
        val assetsRoot = tmp.newFolder("assets")
        val first = StreamingPackInstall.packSourceDir(assetsRoot, pack)
        val other = StreamingPackInstall.packSourceDir(assetsRoot, second)
        assertNotEquals(first!!.absolutePath, other!!.absolutePath)
        assertEquals("preview_test", first.name)
        assertEquals("preview_test_es", other.name)
        // Only the one whose bytes are actually there is complete.
        stage().copyRecursively(first)
        assertTrue(StreamingPackInstall.isPackComplete(first, pack))
        assertFalse(StreamingPackInstall.isPackComplete(other, second))
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

    @Test fun bothArrivalRoutesGateOnTheOneTenPercentHeadroomRule() {
        // The TtsModelManager.kt:66-76 number, spelled once. The PACK route needs it as much as
        // the download does — more, since the amendment made it the primary route on the
        // shipping build: it copies the pack's bytes into filesDir, and out of space that copy
        // fails partway with Play's own copy still on the device.
        val total = pack.totalBytes
        assertEquals((total * 1.1).toLong(), StreamingPackInstall.requiredFreeBytes(pack))
        assertTrue(
            "exactly the requirement is enough — the gate refuses BELOW it, not at it",
            StreamingPackInstall.hasRoomFor(pack, StreamingPackInstall.requiredFreeBytes(pack)),
        )
        assertFalse(
            "one byte short is short",
            StreamingPackInstall.hasRoomFor(pack, StreamingPackInstall.requiredFreeBytes(pack) - 1),
        )
        assertFalse(
            "the pack's own size is NOT enough: the headroom is the point",
            StreamingPackInstall.hasRoomFor(pack, total),
        )
        assertFalse(StreamingPackInstall.hasRoomFor(pack, 0L))
        // And against the real pack, so a drift in the rule reads as a real number here.
        assertEquals(79_920_260L, StreamingPackInstall.requiredFreeBytes(StreamingPackCatalog.EN))
    }

    @Test fun aFailedCopyLeavesNoTempDirectoryAndThrowsTheTypeTheContractNames() {
        // Out of space mid-copy is the case this exists for: without the sweep, up to 73 MB of
        // dead bytes stay under filesDir in a directory `state()` never reads, on a device that
        // was already full; without the wrap, a raw java.io.IOException walks straight past the
        // `catch (e: StreamingPackException)` every caller's KDoc promises is enough.
        val root = tmp.newFolder("root")
        val staged = stage()
        File(staged, "joiner.onnx").delete()
        val thrown = try {
            StreamingPackInstall.install(staged, root, pack, moveSource = false)
            null
        } catch (t: Throwable) {
            t
        }
        assertTrue("a StreamingPackException, not a raw IOException: $thrown", thrown is StreamingPackException)
        assertFalse(
            "no .tmp survives a failed install — those are up to 73 MB nothing else clears",
            StreamingPackInstall.tmpDir(root, pack).exists(),
        )
        assertFalse(StreamingPackInstall.installDir(root, pack).exists())
    }

    @Test fun aFailedInstallLeavesAPreviousInstallExactlyAsItWas() {
        // The marker is the last write inside the try and the old directory is removed only
        // after it: a repair that runs out of space must not cost the user the install they had.
        val root = tmp.newFolder("root")
        StreamingPackInstall.install(stage(), root, pack)
        val dir = StreamingPackInstall.installDir(root, pack)
        val broken = stage()
        File(broken, "encoder.onnx").delete()
        try {
            StreamingPackInstall.install(broken, root, pack, moveSource = false)
        } catch (expected: StreamingPackException) {
            // the point of the test
        }
        assertTrue("the previous install survives a failed one", StreamingPackInstall.isInstalled(dir, pack))
        assertEquals("ENCODER", File(dir, "encoder.onnx").readText())
        assertFalse(StreamingPackInstall.tmpDir(root, pack).exists())
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
        // refused by name. Everywhere else the model is the app's own pack, delivered or fetched.
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

    @Test fun theRowsOneActionRoutesOnTheSOURCE_soARepairCostsWhatAFirstInstallWouldHave() {
        // (Task 6) The Settings row has ONE action and four actuators; `sourceOf` is the
        // reduction that picks which, and it is here rather than in the Compose tree for the
        // reason `TtsModelManager.installRoute` is: a `when` over a sealed state inside a
        // composable is a decision no JVM test can reach, and the one that falls through goes
        // to the third-party download.
        for (source in listOf(
            StreamingPackState.Installed,
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            assertEquals("a source state is its own source", source, StreamingPackInstall.sourceOf(source))
            assertEquals(
                "and a repair takes the route a first install would have taken — a delivered " +
                    "pack repairs without touching the network",
                source,
                StreamingPackInstall.sourceOf(StreamingPackState.Repair(source)),
            )
        }
        assertEquals(
            "nested repairs cannot outlive the reduction either",
            StreamingPackState.Downloadable,
            StreamingPackInstall.sourceOf(
                StreamingPackState.Repair(StreamingPackState.Repair(StreamingPackState.Downloadable)),
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

    /**
     * THE ABANDONED LATCH'S RELEASE CONDITION, total over the machine (4.5.0 Task 1, fix round 1
     * — review r1's B1).
     *
     * A cancel publishes a terminal `Cancelled` at once, which is right for the user and wrong
     * for Play: at 99 % of DOWNLOADING Play can complete the delivery before it processes the
     * cancel. So the shell latches the pack as abandoned and asks this of every state that still
     * arrives — refusing a second `fetch`, and refusing to install or narrate, while the answer
     * is true. It differs from [StreamingPackInstall.fetchInFlight] in exactly one cell, and that
     * cell is the point of the function.
     */
    @Test fun playStillHoldsTheDeliveryEverywhereItIsStillWorkingAndNowhereElse() {
        val playsWork = listOf<com.whispereverywhere.npu.NpuPackFetch.FetchState>(
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Pending,
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Downloading(1L, 2L),
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Transferring,
            com.whispereverywhere.npu.NpuPackFetch.FetchState.NeedsConfirmation,
        )
        val playIsDone = listOf<com.whispereverywhere.npu.NpuPackFetch.FetchState>(
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Verifying(0L, 2L),
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Idle,
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Installed,
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Failed("any reason at all"),
            com.whispereverywhere.npu.NpuPackFetch.FetchState.Cancelled,
        )
        for (s in playsWork) {
            assertTrue(
                "$s: Play is still making this delivery, so a cancelled pack stays busy — a " +
                    "second fetch over it is H3-B2 reopened through the cancel path",
                StreamingPackInstall.playStillHoldsTheDelivery(s),
            )
        }
        for (s in playIsDone) {
            assertFalse(
                "$s: Play has finished with the pack, so the latch releases and the feature is " +
                    "usable again",
                StreamingPackInstall.playStillHoldsTheDelivery(s),
            )
        }
        val verifying = com.whispereverywhere.npu.NpuPackFetch.FetchState.Verifying(0L, 2L)
        assertTrue(
            "the ONE cell the two predicates differ in: COMPLETED (mapped to Verifying) is OUR " +
                "copy, so no second fetch may be issued — but Play's delivery is over, and an " +
                "abandoned delivered pack is simply never installed",
            StreamingPackInstall.fetchInFlight(verifying) &&
                !StreamingPackInstall.playStillHoldsTheDelivery(verifying),
        )
        for (s in playsWork + playIsDone) {
            if (s is com.whispereverywhere.npu.NpuPackFetch.FetchState.Verifying) continue
            assertEquals(
                "$s: the two predicates agree everywhere else",
                StreamingPackInstall.fetchInFlight(s),
                StreamingPackInstall.playStillHoldsTheDelivery(s),
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

    // ---------------------------------------------------------------- the previewer's own copy

    /** Every error code this build declares, plus one it has never heard of. */
    private val everyPlayErrorCode: List<Int> = listOf(
        com.whispereverywhere.npu.NpuPackFetch.ERROR_NO_ERROR,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_APP_UNAVAILABLE,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_PACK_UNAVAILABLE,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_INVALID_REQUEST,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_DOWNLOAD_NOT_FOUND,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_API_NOT_AVAILABLE,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_NETWORK_ERROR,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_ACCESS_DENIED,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_INSUFFICIENT_STORAGE,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_PLAY_STORE_NOT_FOUND,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_APP_NOT_OWNED,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_CONFIRMATION_NOT_REQUIRED,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_UNRECOGNIZED_INSTALLATION,
        com.whispereverywhere.npu.NpuPackFetch.ERROR_INTERNAL_ERROR,
        -12_345,
    )

    @Test fun noPlayRefusalTheCardCanShowNamesAControlThePreviewerDoesNotHave() {
        // `NpuPackFetch.FetchState.Failed.reason` is rendered VERBATIM by a card (its own KDoc
        // says so, and both NPU surfaces do it), so the previewer publishing that table's words
        // would tell users to tap 'Import model pair…' — the NPU chooser's SAF importer for
        // whisper GGML PAIRS, which is not on a Settings previewer row and could not read these
        // four ONNX files. Total over Int: an unheard-of code is named, never silent.
        for (code in everyPlayErrorCode) {
            val text = StreamingPackInstall.fetchRefusal(code)
            assertFalse(
                "code $code must not name the NPU chooser's importer: $text",
                text.contains("Import model pair"),
            )
            assertFalse(
                "code $code must not call a four-file pack a 'model pair': $text",
                text.contains("model pair"),
            )
            assertTrue("code $code must say something", text.isNotBlank())
            assertTrue("code $code must end its sentence: $text", text.endsWith("."))
        }
        // The six the NPU table gets wrong for this feature are the six that must DIFFER.
        for (code in listOf(
            com.whispereverywhere.npu.NpuPackFetch.ERROR_API_NOT_AVAILABLE,
            com.whispereverywhere.npu.NpuPackFetch.ERROR_PLAY_STORE_NOT_FOUND,
            com.whispereverywhere.npu.NpuPackFetch.ERROR_APP_NOT_OWNED,
            com.whispereverywhere.npu.NpuPackFetch.ERROR_UNRECOGNIZED_INSTALLATION,
            com.whispereverywhere.npu.NpuPackFetch.ERROR_APP_UNAVAILABLE,
            com.whispereverywhere.npu.NpuPackFetch.ERROR_PACK_UNAVAILABLE,
        )) {
            assertNotEquals(
                "code $code is one of the six whose NPU sentence names the chooser's importer",
                com.whispereverywhere.npu.NpuPackFetch.failureReason(code),
                StreamingPackInstall.fetchRefusal(code),
            )
        }
    }

    @Test fun thePreviewerPromisesTheDirectDownloadExactlyWhereTheLatchFlips() {
        // THE invariant that makes the sideload sentence honest rather than hopeful: the only
        // codes whose copy says "downloaded directly" are the codes `playRefusedThisInstall`
        // latches on — the ones after which `state()` really does offer the commit-pinned
        // download. Promise it anywhere else and the card names a route the row will not offer;
        // withhold it on the sideload family and the release sideloader reads a dead end.
        for (code in everyPlayErrorCode) {
            val latches = StreamingPackInstall.playRefusedThisInstall(
                com.whispereverywhere.npu.NpuPackFetch.failureReason(code)
            )
            val promises = StreamingPackInstall.fetchRefusal(code).contains("downloaded directly")
            assertEquals(
                "code $code: the sentence and the fallback latch must agree",
                latches,
                promises,
            )
        }
    }

    @Test fun noPreviewerRefusalTripsTheBannedSpeedWords() {
        // Spec §9's list, the union of HowToGuideTest / InFlightStripTest / ModelTierCopyTest /
        // CloudProvidersScreenLogicTest: this feature never promises a latency, and the refusal
        // sentences are as user-facing as the row's own.
        val banned = listOf(
            "faster", "fastest", "quicker", "quickest", "instant", "real-time", "blazing",
            "lightning", "speed",
        )
        val sentences = everyPlayErrorCode.map { StreamingPackInstall.fetchRefusal(it) } +
            StreamingPackInstall.fetchRefusal(
                com.whispereverywhere.npu.NpuPackFetch.ERROR_INSUFFICIENT_STORAGE,
                72_654_782L,
            ) +
            StreamingPackInstall.deliveryRefusal(
                com.whispereverywhere.npu.NpuPackFetch.STATUS_UNKNOWN, 0, 0L
            )
        for (text in sentences) {
            for (word in banned) {
                assertFalse("'$word' in: $text", text.lowercase().contains(word))
            }
        }
    }

    @Test fun theStorageRefusalNamesTheDownloadsRealSizeAndInventsNoNumber() {
        val named = StreamingPackInstall.fetchRefusal(
            com.whispereverywhere.npu.NpuPackFetch.ERROR_INSUFFICIENT_STORAGE,
            72_654_782L,
        )
        assertTrue(
            "Play told us the size, so the refusal spends it — in the house badge, not raw bytes",
            named.contains(StreamingPackCatalog.sizeBadge(72_654_782L)),
        )
        val unnamed = StreamingPackInstall.fetchRefusal(
            com.whispereverywhere.npu.NpuPackFetch.ERROR_INSUFFICIENT_STORAGE
        )
        assertFalse("Play never said, so no number is invented: $unnamed", unnamed.contains("MB"))
        assertTrue(unnamed.contains("preview model"))
    }

    @Test fun aFailurePlayGaveNoErrorCodeForIsNamedByItsStatusNumber() {
        // `advance` maps THREE things to Failed: the FAILED status (which carries a code), the
        // UNKNOWN status, and any status the library adds later. Reading `errorCode()` on the
        // last two would render "a failure without naming a reason" — a guess dressed as a fact.
        val failed = StreamingPackInstall.deliveryRefusal(
            com.whispereverywhere.npu.NpuPackFetch.STATUS_FAILED,
            com.whispereverywhere.npu.NpuPackFetch.ERROR_NETWORK_ERROR,
            0L,
        )
        assertEquals(
            "a FAILED status is its error code's sentence, and nothing else",
            StreamingPackInstall.fetchRefusal(
                com.whispereverywhere.npu.NpuPackFetch.ERROR_NETWORK_ERROR
            ),
            failed,
        )
        val unknown = StreamingPackInstall.deliveryRefusal(
            com.whispereverywhere.npu.NpuPackFetch.STATUS_UNKNOWN,
            com.whispereverywhere.npu.NpuPackFetch.ERROR_NO_ERROR,
            0L,
        )
        assertTrue("the status number is what the user is told: $unknown", unknown.contains("(0)"))
        val future = StreamingPackInstall.deliveryRefusal(99, 0, 0L)
        assertTrue("and so is a status this build has never seen: $future", future.contains("(99)"))
    }
}
