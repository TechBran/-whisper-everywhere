package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE PARTS, WIRED (P2-4; design §2.7) — the two Android-bound halves of a multi-pack fetch,
 * pinned as source because neither can be constructed off-device: `NpuPackController` is
 * `AssetPackManager`-bound and `WhisperModelManager.installFromPack` is `Context`/`StatFs`-bound.
 * Everything they DECIDE is `NpuPackFetch`'s and is executed in `NpuPackFetchTest` (the machine
 * over every combination of two part states, `packsFor`, the delivered-directory mapping); what is
 * left here is that the shells ask those functions and nothing else — every part fetched,
 * cancelled and given back, every reading folded, each entry read out of its own part.
 *
 * `NpuDiagTest`'s older pins over the same shell stand beside these unchanged — one emitter per
 * `pack:` line, ONE `NpuPackFetch.advance(` call, `removePack` strictly after the Installed
 * publication and never on a refusal — and the one-part path those pins were written for is,
 * part for part, what these functions answer for a Qualcomm row. Both files are in the test
 * task's `sourcePinnedInputs`.
 */
class NpuPackPartsWiringPinTest {

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

    private fun liveLineCount(scope: String, needle: String): Int =
        scope.lineSequence().count { line ->
            val t = line.trimStart()
            !(t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) && line.contains(needle)
        }

    private fun liveOffset(scope: String, needle: String): Int {
        var at = 0
        for (line in scope.split("\n")) {
            val t = line.trimStart()
            if (!(t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) && line.contains(needle)) return at
            at += line.length + 1
        }
        return -1
    }

    /** One member, from its declaration to its first column-4 closing brace. */
    private fun body(haystack: String, declaration: String): String {
        val start = haystack.indexOf(declaration)
        assertTrue("missing: <<$declaration>>", start >= 0)
        val close = haystack.indexOf("\n    }\n", start)
        assertTrue("the closing brace of <<$declaration>> moved", close > start)
        return haystack.substring(start, close + "\n    }\n".length)
    }

    private val controller: String by lazy { read("src/main/java/com/whispereverywhere/npu/NpuPackController.kt") }
    private val manager: String by lazy { read("src/main/java/com/whispereverywhere/model/WhisperModelManager.kt") }
    private val start: String by lazy { body(controller, "    fun start(context: Context, tierId: String): Boolean") }
    private val cancel: String by lazy { body(controller, "    fun cancel() {") }
    private val onPackState: String by lazy { body(controller, "    private fun onPackState(packState: AssetPackState) {") }
    private val runInstall: String by lazy { body(controller, "    private suspend fun runInstall(tierId: String, packName: String) {") }
    private val installFromPack: String by lazy { body(manager, "    suspend fun installFromPack(") }

    @Test
    fun theControllerFetchesTheFamilysOwnPartsEveryOneOfThem() {
        assertEquals(
            "the packs are asked of packsFor — the device family's census parts — at exactly one " +
                "site, start, with the Main-safe family memo",
            1,
            liveLineCount(controller, "NpuPackFetch.packsFor(tierId, (appCtx as? WhisperEverywhereApp)?.npuSocFamily)"),
        )
        assertEquals(
            "and nothing routes through the old single-pack map any more",
            0,
            liveLineCount(controller, "PACK_BY_TIER"),
        )
        assertEquals("the parts are the fetch's, written once, at start", 1, liveLineCount(controller, "activeParts = parts"))
        assertEquals(
            "EVERY part is fetched in one request — after process death that is what re-queries each one",
            1,
            liveLineCount(start, "mgr.fetch(parts.map { it.packName })"),
        )
        val clear = liveOffset(start, "readings.clear()")
        val fresh = liveOffset(start, "repeat(parts.size) { readings += null }")
        val fetch = liveOffset(start, "mgr.fetch(parts.map { it.packName })")
        assertTrue(
            "ORDER: the readings are reset ($clear), every part unanswered ($fresh), BEFORE the " +
                "fetch ($fetch) — a replay can only ever land on this fetch's slots",
            clear in 0 until fresh && fresh < fetch,
        )
        val named = liveOffset(start, "_activeTier.value = tierId")
        val asked = liveOffset(start, "NpuPackFetch.packsFor(")
        assertTrue(
            "the requested tier is still named before the no-pack refusal can return (F7's rule), " +
                "and the parts are asked after it",
            named in 0 until asked,
        )
    }

    @Test
    fun everyUpdateFoldsEveryPartsLatestReadingThroughTheOneMachine() {
        assertEquals(
            "each AssetPackState lands in ITS part's slot, found by the pack's name",
            1,
            liveLineCount(onPackState, "val part = parts.indexOfFirst { it.packName == packState.name() }"),
        )
        assertEquals(
            "…as the four numbers the per-part mapping has always taken",
            1,
            liveLineCount(onPackState, "readings[part] = NpuPackFetch.PartReading("),
        )
        assertEquals(
            "and the pair's state is the fold of EVERY part's latest reading — the list overload, " +
                "the one pure mapping applied to all of them",
            1,
            liveLineCount(onPackState, "NpuPackFetch.advance(readings.toList())"),
        )
        assertEquals(
            "an update about a pack that is not one of this fetch's parts is ignored",
            1,
            liveLineCount(onPackState, "if (part < 0 || part >= readings.size) return"),
        )
        assertEquals(
            "the install is launched on the fold's Verifying — which it answers only when EVERY " +
                "part is delivered",
            1,
            liveLineCount(onPackState, "if (next is NpuPackFetch.FetchState.Verifying) beginInstall(tierId, packName)"),
        )
    }

    /**
     * THE P2b REVIEW'S FIX-NOW, wired: Play's state listener fires on CHANGES, so a part Play
     * already holds (COMPLETED before this fetch) reports only in the fetch Task's own result. The
     * shell now reads that result — the success listener on `fetch()`'s Task — and fills, through
     * the ONE fold ([onPackState]), only the parts the listener has not spoken for in THIS fetch
     * (`NpuPackFetch.unansweredParts`, executed in `NpuPackFetchTest`), under the monitor, keyed on
     * a generation bumped at every start so an older fetch's answer never lands on a newer one.
     * `NpuDiagTest`'s one-`NpuPackFetch.advance(` pin still holds: the Task's answer goes through
     * `onPackState`, never beside it.
     */
    @Test
    fun theFetchTasksOwnAnswerFillsTheUnansweredPartsThroughTheOneFold() {
        assertEquals(
            "the fetch's Task answers through a success listener, handed this fetch's generation",
            1,
            liveLineCount(start, ".addOnSuccessListener { states -> onFetchAnswered(generation, states.packStates()) }"),
        )
        val fresh = liveOffset(start, "repeat(parts.size) { readings += null }")
        val generation = liveOffset(start, "val generation = ++fetchGeneration")
        val fetch = liveOffset(start, "mgr.fetch(parts.map { it.packName })")
        assertTrue(
            "ORDER: the readings are reset ($fresh), the generation bumped ($generation), BEFORE the " +
                "fetch ($fetch) — its answer can only be this fetch's",
            fresh in 0 until generation && generation < fetch,
        )
        val answered = body(controller, "    private fun onFetchAnswered(generation: Int, states: Map<String, AssetPackState>) {")
        assertEquals(
            "under the monitor, an older fetch's answer is dropped",
            listOf(1, 1),
            listOf(
                liveLineCount(answered, "synchronized(this) {"),
                liveLineCount(answered, "if (generation != fetchGeneration) return"),
            ),
        )
        assertEquals(
            "only the parts the listener has not spoken for, through the pure rule",
            1,
            liveLineCount(answered, "for (name in NpuPackFetch.unansweredParts(activeParts, readings, states.keys)) {"),
        )
        assertEquals(
            "…each through onPackState, the one fold — re-folded, published, installed on Verifying",
            1,
            liveLineCount(answered, "states[name]?.let { onPackState(it) }"),
        )
        assertEquals(
            "and no fold of its own: the controller still maps statuses at ONE site",
            1,
            liveLineCount(controller, "NpuPackFetch.advance("),
        )
    }

    @Test
    fun cancelAndTheGiveBackApplyToEveryPart() {
        assertEquals(
            "cancel cancels EVERY part's download",
            1,
            liveLineCount(cancel, "manager?.cancel(parts.map { it.packName })"),
        )
        assertEquals(
            "and after the finalise EVERY part is given back — one call site, one loop",
            1,
            liveLineCount(runInstall, "parts.forEach { mgr.removePack(it.packName) }"),
        )
        assertEquals(
            "…inside the Installed arm, so the refusal arm (NpuDiagTest holds it free of removePack) " +
                "leaves every delivered part in place for the retry",
            1,
            liveLineCount(
                runInstall.substringAfter("is NpuAssetImport.ImportState.Installed ->")
                    .substringBefore("is NpuAssetImport.ImportState.Refused ->"),
                "mgr.removePack(",
            ),
        )
    }

    @Test
    fun theInstallIsHandedEveryPartsLocationAndAMissingOneIsTheEmptyDelivery() {
        assertEquals(
            "every part is located through the one shared helper, with the listener's own manager",
            1,
            liveLineCount(runInstall, "PlayPacks.assetsPath(mgr, part.packName)?.let { part.packName to it }"),
        )
        assertEquals(
            "a part Play gives no location for is the empty delivery, refused by name before any copy",
            1,
            liveLineCount(runInstall, "if (assetsPaths.size != parts.size) {"),
        )
        assertEquals(
            "and the install receives every part's location, by pack name",
            1,
            liveLineCount(runInstall, "tierId, family, assetsPaths,"),
        )
    }

    @Test
    fun theInstallReadsEachEntryOutOfThePartThatCarriesItAndTheMetadataOutOfPartOne() {
        assertEquals(
            "installFromPack takes every delivered part's location, by pack name",
            1,
            liveLineCount(installFromPack, "        packAssetsPaths: Map<String, String>,"),
        )
        assertEquals(
            "its parts are packsFor's — the answer the controller fetched — asked once",
            1,
            liveLineCount(installFromPack, "val packParts = NpuPackFetch.packsFor(tierId, family)"),
        )
        assertEquals(
            "a tier with no parts joins the catalog guard (no second empty-delivery site)",
            1,
            liveLineCount(installFromPack, "required.isEmpty() || packParts.isEmpty()"),
        )
        assertEquals(
            "each entry's directory is its own part's, through the one executed mapping",
            1,
            liveLineCount(installFromPack, "val entryDirs = NpuPackFetch.deliveredEntryDirs(packParts, packAssetsPaths)"),
        )
        assertEquals(
            "…and the copy loop reads every entry from there",
            1,
            liveLineCount(installFromPack, "val src = File(entryDirs[name] ?: continue, name)"),
        )
        val meta = liveOffset(installFromPack, "NpuPackFetch.deliveredMetadataDir(packParts, packAssetsPaths)")
        val parse = liveOffset(installFromPack, "NpuPackMetadata.parse(")
        assertTrue(
            "metadata.json comes out of PART 1 ($meta) and is read before anything else ($parse)",
            meta in 0 until parse,
        )
        assertEquals(
            "and the manager no longer routes through the old single-pack map anywhere",
            0,
            liveLineCount(manager, "PACK_BY_TIER"),
        )
    }
}
