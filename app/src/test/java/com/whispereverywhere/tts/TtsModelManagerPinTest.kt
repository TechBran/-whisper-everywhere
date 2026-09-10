package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The voice archive's integrity pin, pinned. The 2026-09-08 incident: k2-fsa re-uploaded the
 * archive under the ROLLING `tts-models` release tag, the size stayed inside the +-5 % band, the
 * single pinned sha256 stopped matching, and every fresh voice install on every build failed for
 * two days with nothing in this suite to notice. These pins do not catch a future re-upload either
 * (nothing offline can) — they catch the two ways the FIX could be undone: the gate collapsing back
 * to one hash, and the known-good set losing the archive that is actually served today.
 *
 * ### And, since 4.4.0's amendment (Task 2b), the PACK ROUTE's own invariants
 *
 * The archive now rides the `tts_kokoro` Play asset pack, which closes the incident structurally:
 * a voice update becomes a deliberate new AAB. The manager is an Android shell — `Context`,
 * `DownloadManager`, `AssetPackManager`, `StatFs` — so no JVM test can construct it and drive
 * either arrival route; every DECISION it makes is pure and executed by `TtsModelManagerTest`.
 * What is left is WHICH call sits where and in WHICH ORDER, and those are exactly the facts that,
 * if they moved, would be invisible to every behavioural test and expensive on a device:
 *
 *  - a SECOND verification for the pack route (the amendment's rule is one `verifyExtractInstall`
 *    for both sources: a corrupt pack must not be installable on a route a corrupt download could
 *    not survive),
 *  - Play's own copy of the archive deleted (it is the ONLY copy until `removePack`, so an early
 *    delete costs a 350 MB re-fetch — or, on a failed verify, costs it for nothing),
 *  - the give-back running before the install landed,
 *  - the storage gate running after the extract had already started writing,
 *  - a FORKED Play discriminator, so the voice and the previewer could disagree about which
 *    install Play can serve.
 *
 * `TtsModelManager.kt` is in the test task's `sourcePinnedInputs` in `app/build.gradle.kts`;
 * without that entry a comment-only edit to it would leave `:app:testDebugUnitTest` UP-TO-DATE and
 * these pins would pass against the file as it used to be.
 */
class TtsModelManagerPinTest {

    private val src =
        File("src/main/java/com/whispereverywhere/tts/TtsModelManager.kt").readText()
            .replace("\r\n", "\n")

    // ---------------------------------------------------------------- source helpers
    // (StreamingPackShellPinTest's own, verbatim: the same comment-blind live-line rule — a pin a
    // commented-out line can satisfy is not a pin — and the same offset/scope idiom for ORDER.)

    private fun liveLineCount(scope: String, needle: String): Int =
        scope.lineSequence().count { line ->
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            !commented && line.contains(needle)
        }

    private fun offsetOfLive(scope: String, needle: String): Int {
        var at = 0
        for (line in scope.split("\n")) {
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            if (!commented && line.contains(needle)) return at
            at += line.length + 1
        }
        return -1
    }

    /** [from] up to the next [to], so a count or an order pin can name ONE member's body. */
    private fun scopeOf(text: String, from: String, to: String): String {
        val a = text.indexOf(from)
        assertTrue("cannot find `$from`", a >= 0)
        val b = text.indexOf(to, a + from.length)
        return if (b < 0) text.substring(a) else text.substring(a, b)
    }

    // ---------------------------------------------------------------- the archive gate

    @Test fun the_gate_accepts_a_known_good_set_not_one_hash() {
        assertTrue(src.contains("KNOWN_GOOD_TAR_SHA256.none { it.equals(actual, ignoreCase = true) }"))
        assertTrue("the marker must record the archive actually extracted", src.contains("marker.writeText(actual.lowercase())"))
    }

    @Test fun the_known_good_set_carries_both_archives_and_the_served_one_first() {
        assertEquals(
            listOf(
                "c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298",
                "c133d26353d776da730870dac7da07dbfc9a5e3bc80cc5e8e83ab6e823be7046",
            ),
            TtsModelManager.KNOWN_GOOD_TAR_SHA256,
        )
        // Both are 64 lowercase hex chars — a typo here is a permanently failing install.
        TtsModelManager.KNOWN_GOOD_TAR_SHA256.forEach { assertTrue(it, it.matches(Regex("[0-9a-f]{64}"))) }
    }

    @Test fun the_size_band_covers_both_archives() {
        assertEquals(349_906_910L, TtsModelManager.TAR_BYTES)
        assertTrue(TtsModelManager.sizeWithinTolerance(349_906_910L))
        assertTrue("the 2026-07-18 archive must still pass the size gate", TtsModelManager.sizeWithinTolerance(349_418_188L))
    }

    // ---------------------------------------------------------------- the pack route (Task 2b)

    @Test
    fun bothArrivalRoutesLandThroughTheONEVerification() {
        assertEquals(
            "there is exactly one verifyExtractInstall, and the pack route calls it rather " +
                "than carrying a second size gate, a second hash set or a second swap — one " +
                "verification for both sources is the amendment's rule",
            1, liveLineCount(src, "private fun verifyExtractInstall("),
        )
        assertEquals(
            "the size gate is spelled once, inside it",
            1, liveLineCount(src, "if (!sizeWithinTolerance(tar.length()))"),
        )
        assertEquals(
            "the known-good hash gate is spelled once, inside it",
            1, liveLineCount(src, "KNOWN_GOOD_TAR_SHA256.none"),
        )
        assertEquals(
            "and so is the extract — no route unpacks the archive on its own terms",
            1, liveLineCount(src, "extractTarBz2(tar, tmp, stripLeadingComponent = true)"),
        )
        val fromPack = scopeOf(src, "suspend fun installFromPack(", "suspend fun download(")
        assertEquals(
            "the pack route's whole install IS that one call",
            1, liveLineCount(fromPack, "verifyExtractInstall("),
        )
    }

    @Test
    fun theDeliveredArchiveIsNeverDeletedByTheInstallerAndIsGivenBackOnlyAfterTheLand() {
        assertEquals(
            "ownsSource is what keeps the installer's `tar.delete()` off Play's only copy: a " +
                "delivered pack's bytes are Play's until removePack, so deleting them on a " +
                "FAILED verify would cost a 350 MB re-fetch for nothing, and on a successful " +
                "one would race the give-back",
            1, liveLineCount(src, "verifyExtractInstall(tar, ownsSource = false)"),
        )
        assertEquals(
            "EVERY tar.delete() in this file is guarded by ownsSource — an unguarded one is the " +
                "whole defect back again",
            liveLineCount(src, "tar.delete()"),
            liveLineCount(src, "if (ownsSource) tar.delete()"),
        )
        assertEquals(
            "and there are two of them: the successful install's sweep of its own download, and " +
                "the catch's — a failed verify must not leave a 350 MB tar behind either",
            2, liveLineCount(src, "if (ownsSource) tar.delete()"),
        )
        assertEquals(
            "the give-back is spelled exactly once",
            1, liveLineCount(src, "PlayPacks.remove("),
        )
        val fromPack = scopeOf(src, "suspend fun installFromPack(", "suspend fun download(")
        val landed = offsetOfLive(fromPack, "verifyExtractInstall(")
        val gaveBack = offsetOfLive(fromPack, "PlayPacks.remove(")
        assertTrue("the install must be IN installFromPack", landed >= 0)
        assertTrue("the give-back must be IN installFromPack", gaveBack >= 0)
        assertTrue(
            "REMOVE-AFTER-LAND (the NPU fetch flow's rule): the pack is handed back STRICTLY " +
                "after the marker was written and the temp dir renamed into place. A give-back " +
                "that runs first deletes the source mid-extract.",
            landed < gaveBack,
        )
    }

    @Test
    fun theStorageGateRunsBeforeEitherRouteWritesAByte() {
        val fromPack = scopeOf(src, "suspend fun installFromPack(", "suspend fun download(")
        val gate = offsetOfLive(fromPack, "hasRoomToExtract(")
        val install = offsetOfLive(fromPack, "verifyExtractInstall(")
        assertTrue("the pack route must gate on free space at all", gate >= 0)
        assertTrue(
            "and it must gate BEFORE it hashes 350 MB and unpacks ~490 MB: out of space, the " +
                "extract fails partway with Play's own 350 MB still on the device beside a " +
                "half-written .tmp. A refusal that costs nothing is the point of gating first.",
            gate < install,
        )
        val download = scopeOf(src, "suspend fun download(", "/** sha256-gate the tar")
        assertEquals(
            "and the download route asks the SAME two functions for its two volumes instead of " +
                "re-deriving the headroom, so the routes cannot disagree about what enough " +
                "space means",
            1, liveLineCount(download, "!hasRoomToStageTar(extFree) || !hasRoomToExtract(intFree)"),
        )
        assertEquals(
            "and the 1.1 x / 1.4 x rule is spelled ONCE, in the companion",
            1, liveLineCount(src, "(TAR_BYTES * 1.1).toLong()"),
        )
        assertEquals(1, liveLineCount(src, "(TAR_BYTES * 1.4).toLong()"))
    }

    @Test
    fun thePlayDiscriminatorAndItsLatchAreThePreviewersOwn() {
        assertEquals(
            "the SAME function the previewer's pack-first manager reads — the amendment's " +
                "\"behind the same Play-install discriminator\". A second spelling is how one " +
                "feature ends up downloading from a third party on a build the other fetches on.",
            1, liveLineCount(src, "StreamingPackInstall.playCanDeliver("),
        )
        assertEquals(
            "and the debug-build half of it is read in exactly that one place",
            1, liveLineCount(src, "BuildConfig.DEBUG"),
        )
        assertEquals(
            "the fallback latch's classifier is NpuPackFetch's own family, applied once",
            1, liveLineCount(src, "StreamingPackInstall.playRefusedThisInstall("),
        )
        assertEquals(
            "keyed by Play's ERROR CODE through the one table that turns a code into words — a " +
                "caller that handed over its own sentence would silently never latch",
            1, liveLineCount(src, "NpuPackFetch.failureReason(errorCode)"),
        )
        assertEquals(
            "and the row's state comes from the previewer's own four-way machine, not a second " +
                "copy of it",
            1, liveLineCount(src, "StreamingPackInstall.resolve("),
        )
    }
}
