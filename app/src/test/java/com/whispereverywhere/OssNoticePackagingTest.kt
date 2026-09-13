package com.whispereverywhere

import com.whispereverywhere.transcription.stream.StreamingPackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHERE THE NOTICE LIVES (4.5.2 Task 4). The placement half of the owner's condition of 2026-09-13.
 *
 * [OssNoticeTest] asserts what the notices SAY. This asserts where they SIT, and it exists because
 * the obvious place to put a pack's attribution is next to the pack — which is the one place it
 * cannot survive:
 *
 *  1. `tools/build_asset_packs.py` clears a preview payload directory of everything that is neither
 *     one of the four payload files nor the `.gitkeep` anchor, so a notice placed in a pack is
 *     **deleted by the next pipeline run** and nothing says so;
 *  2. and if it survived to a bundle, `verifyPreviewPack` compares that directory's listing to
 *     exactly `payload + .gitkeep` and **fails the bundle**.
 *
 * Both mechanisms are right — a pack is a payload, and an unexpected file in one is a packaging
 * bug. So the notice belongs to the BASE module, `:app`, whose `src/main/assets/` is what becomes
 * `base/assets/` in an AAB; and these tests hold it there.
 *
 * ### What these tests establish, and what they cannot
 *
 * | they establish | they do NOT establish |
 * |---|---|
 * | the notice asset is in the base module's own assets, and in no asset-pack module's tree | that a built bundle contains it. These tests read the SOURCE TREE; only opening a built artefact can say what was packaged, and §4 of `grant-t3-impl.md` records exactly which artefact was opened |
 * | the two mechanisms that would destroy a notice placed in a pack are still in the pipeline and in the bundle gate, so the placement rule keeps its reason | that the pipeline was run, or that a pack's payload is correct — that is `verifyPreviewPack`'s job at bundle time |
 * | nothing in `app/build.gradle.kts` removes, redirects or filters the base module's assets — no `ignoreAssetsPattern`, no `aaptOptions`, no `assets.setSrcDirs`, and the `packaging` filters are META-INF and `jniLibs` only | that AGP packages assets the way its documentation says. That is upstream behaviour, and the only honest evidence for it is an inspection of an artefact |
 * | the notice asset is a declared input of the test task, so an edit confined to the page cannot leave [OssNoticeTest] UP-TO-DATE against a page that no longer says what it asserted | anything about the notices' content. That is [OssNoticeTest] |
 *
 * **And the negative that matters most: none of this is legal clearance, and none of it is a
 * permission from anybody.** It is a placement guard on a file whose content the licences ask for.
 *
 * ### Which of these pins fires on the change it guards, and which does not
 *
 * Measured in this worktree, not assumed. Both files the source-text needles read are ALREADY
 * declared inputs of the test task — `app/build.gradle.kts` as `"build.gradle.kts"` and
 * `rootProject.file("tools/build_asset_packs.py")` — and each was confirmed by editing only that
 * file from a clean `testDebugUnitTest UP-TO-DATE` and watching the task re-run and redden. So
 * removing the pipeline's clearing loop, or adding an `ignoreAssetsPattern`, fails the build on the
 * commit that does it.
 *
 * **The pack-tree walk is the exception, and it is stale-blind by construction.** A pack module's
 * payload directory is a gitignored BUILD artefact — 4.7 GB across the ten when placed — so it is
 * not a declared input and must not become one: hashing gigabytes on every JVM test run is the cost
 * `verifyNpuPacks` was deliberately written to avoid. Confirmed the same way, in the other
 * direction: planting `preview_ko/src/main/assets/preview_ko/NOTICE.html` left the task UP-TO-DATE
 * and the suite green, and the stray was only caught once the task was forced to run. So read that
 * walk as a check that runs when the suite runs, **not** as a guard that fires when a notice is
 * dropped into a pack. What catches that on the way to a release is `verifyPreviewPack`, which
 * declares no inputs at all, therefore always runs, and refuses any listing that is not exactly the
 * four catalogue files plus the anchor.
 */
class OssNoticePackagingTest {

    // ------------------------------------------------------- 1. base module, and no pack

    /**
     * The notice is in `:app`'s assets, and nowhere in the ten asset-pack modules.
     *
     * The pack list is PARSED out of the `assetPacks` expression rather than typed, so an eleventh
     * pack is covered on the day it is added — which is the same reason [OssNoticeTest]'s
     * attribution loop reads the catalogue.
     */
    @Test fun theNoticeLivesInTheBaseModuleAndNotInAnyAssetPack() {
        val notice = File(repoRoot(), BASE_MODULE_NOTICE)
        assertTrue(
            "$BASE_MODULE_NOTICE does not exist. The notices Apache-2.0 §4, MIT and AI-Hub's FAQ " +
                "ask for are carried by this one file, in the BASE module, because that is the " +
                "only place in this repository that survives both the pack pipeline and the " +
                "bundle gate (see this class's other two tests)",
            notice.isFile,
        )
        assertTrue("$BASE_MODULE_NOTICE is empty", notice.length() > 0L)

        val packs = assetPackModules()
        assertEquals(
            "the assetPacks expression in app/build.gradle.kts no longer parses to the ten " +
                "on-demand modules — if a pack was added, this test now covers it and nothing " +
                "needs changing here; if the expression became conditional, PreviewPackLayoutTest " +
                "is the pin that cares",
            10,
            packs.size,
        )
        assertTrue(
            "the base module must not be an asset pack: `:app` is what becomes `base/` in an AAB",
            !packs.contains(":app"),
        )

        for (module in packs) {
            val dir = File(repoRoot(), module.removePrefix(":"))
            assertTrue("$module is in assetPacks but $dir is not a directory", dir.isDirectory)
            val strays = dir.walkTopDown()
                .filter { it.isFile }
                .filter { it.name.endsWith(".html") }
                .map { it.relativeTo(repoRoot()).path.replace('\\', '/') }
                .toList()
            assertEquals(
                "an asset pack carries an .html file. If that is a notice, it is in the ONE place " +
                    "it cannot survive: the pipeline deletes it (build_asset_packs.py) and the " +
                    "bundle gate refuses it (verifyPreviewPack). The notice's home is " +
                    "$BASE_MODULE_NOTICE",
                emptyList<String>(),
                strays,
            )
        }
    }

    // ------------------------------------------------------- 2. why it cannot live in a pack

    /**
     * The two mechanisms that make the base module the only home, pinned in the files that
     * implement them.
     *
     * This is a pin on a REASON, and reasons rot: if the pipeline stopped clearing stale files, or
     * the gate stopped demanding an exact listing, a notice in a pack would look survivable and
     * the placement rule above would read as arbitrary. It is not arbitrary today, and this test is
     * what will say so tomorrow.
     */
    @Test fun aNoticeInsideAPackWouldBeDeletedByThePipelineAndRefusedByTheBundleGate() {
        val pipeline = File(repoRoot(), "tools/build_asset_packs.py").readText()
        assertTrue(
            "tools/build_asset_packs.py no longer clears a preview payload directory of files " +
                "that are neither payload nor the .gitkeep anchor. That deletion is the first of " +
                "the two reasons the notice lives in the base module",
            pipeline.contains("keep = {name for name, _, _, _ in pack.files} | {PREVIEW_ANCHOR}") &&
                pipeline.contains("os.remove(os.path.join(out_dir, stale))"),
        )

        val build = buildScript()
        assertTrue(
            "verifyPreviewPack no longer compares a pack payload directory's listing to exactly " +
                "the four catalogue files plus .gitkeep. That refusal is the second reason: even " +
                "if a notice survived the pipeline, a bundle built with it in a pack would fail",
            build.contains("val expected = (rows.map { it[0] as String } + \".gitkeep\").sorted()") &&
                build.contains("if (listed != expected)"),
        )

        // And the invariant itself, on disk, in whatever state this checkout's payload is in:
        // empty (the gitignored dirs of a fresh worktree) or placed. Either way, nothing that is
        // not payload may be sitting there — which is the condition the two mechanisms above
        // enforce, asserted here directly rather than only through their source text.
        for (pack in StreamingPackCatalog.packs) {
            val module = pack.packName ?: continue
            val payloadDir = File(repoRoot(), "$module/src/main/assets/$module")
            if (!payloadDir.isDirectory) continue
            val allowed = pack.files.map { it.name }.toSet() + PAYLOAD_ANCHOR
            val unexpected = (payloadDir.listFiles() ?: emptyArray())
                .map { it.name }
                .filter { it !in allowed }
                .sorted()
            assertEquals(
                "$module/src/main/assets/$module carries a file that is neither one of the four " +
                    "catalogue payload files nor $PAYLOAD_ANCHOR. The next `python " +
                    "tools/build_asset_packs.py preview` deletes it without asking, and a bundle " +
                    "built before that runs fails verifyPreviewPack",
                emptyList<String>(),
                unexpected,
            )
        }
    }

    // ------------------------------------------------------- 3. nothing filters it out

    /**
     * No build rule removes, redirects or filters the base module's assets.
     *
     * Each needle below is a real way to ship a release whose licences screen opens a blank page
     * while every content assertion in [OssNoticeTest] stays green, because every one of them reads
     * the source tree:
     *
     *  - `androidResources { ignoreAssetsPattern }` / the older `aaptOptions` equivalent excludes
     *    asset paths from the merge by glob;
     *  - `assets.setSrcDirs(...)` REPLACES the source-set's asset directories, where this build's
     *    one `assets.srcDir(...)` only adds to them;
     *  - a `packaging { resources { excludes } }` entry that reached beyond META-INF could drop an
     *    asset path.
     *
     * And the last assertion is the one that keeps the others honest over time: the page is a
     * declared input of the test task, so editing it re-runs this suite instead of leaving it
     * UP-TO-DATE.
     */
    @Test fun nothingInTheBuildCanDropTheNoticeFromTheBaseModulesAssets() {
        val build = buildScript()

        for (needle in listOf("ignoreAssetsPattern", "aaptOptions", "assets.setSrcDirs")) {
            assertTrue(
                "app/build.gradle.kts now mentions `$needle`. Every one of those can remove an " +
                    "asset from the base module's merge while leaving every assertion in " +
                    "OssNoticeTest green, because those read the source tree. If this is " +
                    "deliberate, prove the notice still lands by opening a built artefact and " +
                    "record what was opened — do not relax this needle",
                !build.contains(needle),
            )
        }

        val resourceFilters = build.substringAfter("    packaging {\n        resources {\n")
            .substringBefore("\n        }")
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("excludes") }
        assertEquals(
            "the base module's packaging.resources filters are no longer the single META-INF " +
                "entry. An `excludes` that reaches an asset path ships a licences screen that " +
                "opens nothing",
            listOf("excludes += \"/META-INF/{AL2.0,LGPL2.1}\""),
            resourceFilters,
        )

        val declaredInputs = build.substringAfter("tasks.withType<Test>().configureEach {\n")
            .substringBefore("\n}")
        assertTrue(
            "src/main/assets/oss_licenses.html is no longer a declared input of the test task. An " +
                "HTML asset is an input to no compile task, so an edit confined to the notices " +
                "would leave :app:testDebugUnitTest UP-TO-DATE and every assertion in " +
                "OssNoticeTest would pass against the page as it used to be",
            declaredInputs.contains("\"src/main/assets/oss_licenses.html\","),
        )
    }

    // ------------------------------------------------------- the house source walker

    /** `app/build.gradle.kts`, LF-normalised so the needles above are written once. */
    private fun buildScript(): String =
        File(repoRoot(), "app/build.gradle.kts").readText().replace("\r\n", "\n")

    /**
     * The module names in `:app`'s ONE `assetPacks` expression — parsed, never typed, so a pack
     * added tomorrow is covered without an edit here.
     */
    private fun assetPackModules(): List<String> {
        val build = buildScript()
        val marker = "assetPacks += "
        assertTrue(
            "app/build.gradle.kts has no `assetPacks += ` statement — either the packs left the " +
                "bundle or the statement was rewritten, and PreviewPackLayoutTest is the pin that " +
                "cares about which",
            build.contains(marker),
        )
        val expression = build.substringAfter(marker).substringBefore("\n\n")
        return Regex("\":([A-Za-z0-9_]+)\"").findAll(expression).map { ":" + it.groupValues[1] }.toList()
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate the repository root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        /** The notice's one home: the BASE module's own assets, which become `base/assets/`. */
        const val BASE_MODULE_NOTICE = "app/src/main/assets/oss_licenses.html"

        /** The only non-payload file a pack payload directory may carry (`PREVIEW_ANCHOR`). */
        const val PAYLOAD_ANCHOR = ".gitkeep"
    }
}
