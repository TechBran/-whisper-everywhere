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
 * payload directory is a gitignored BUILD artefact — 4.7 GB across the ten when placed, and 1.9 GB more
 * in the two MediaTek modules since P2-5 — so it is
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
     * The notice is in `:app`'s assets, and nowhere in the twelve asset-pack modules.
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
            "the assetPacks expression in app/build.gradle.kts no longer parses to the twelve " +
                "on-demand modules (ten until P2-5 appended the mt6989 pair's two) — if a pack " +
                "was added, this test now covers it and only this count moves; if the expression " +
                "became conditional, PreviewPackLayoutTest is the pin that cares",
            12,
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

    // ------------------------------------------------------- 4. the observation, and its gap

    /**
     * The promotion checklist records what was actually opened, and the **notice page's own length
     * and digest are derived from the file** — which is the whole point of this test.
     *
     * A bundle inspection is a statement about a file as it was on the day it was opened. Edit the
     * page afterwards and the recorded observation quietly stops describing what ships, with
     * nothing to say so: every content assertion in [OssNoticeTest] still passes, because those
     * read the page, and the doc still reads confidently, because prose does not know its subject
     * changed. So the doc must cite this page's exact byte count and `sha256`, and an edit to the
     * page reddens THIS test until somebody opens a bundle again and re-records what they saw.
     *
     * The two release-side constants are literals because they are history: the 4.5.1/93 bundle the
     * controller built and verified, and the notice entry inside its `base/`. They are the evidence
     * that the release path carries this asset unmodified, and a record that loses them loses the
     * only observation of `bundleRelease` there is.
     *
     * **And the gap has to stay stated.** No release bundle of 4.5.2 exists yet, so the doc must
     * keep saying so. The forbidden scan below catches the three sentences a careless edit would
     * write; it cannot catch every rewording, which is why the positive sentence is required too.
     */
    @Test fun theChecklistRecordsTheBundleObservationAndTheGapThatRemains() {
        val checklist = File(repoRoot(), CLEARANCE_DOC).readText().replace("\r\n", "\n")
        val notice = noticeInItsPackagedForm()
        val length = String.format(java.util.Locale.ROOT, "%,d", notice.size)
        val digest = sha256(notice)

        assertTrue(
            "the checklist does not cite this page's own byte count ($length B) and sha256 " +
                "($digest). Those are DERIVED from the file here, so if the page was edited after " +
                "the bundle inspection was recorded, this is the assertion that says so: the " +
                "recorded observation now describes a page that no longer exists. Re-open a " +
                "bundle, read the base/ entry, and record what you saw — do not retype the " +
                "numbers from the file",
            checklist.contains("$length B") && checklist.contains(digest),
        )
        assertTrue(
            "the checklist no longer carries the 4.5.1/93 release bundle's identity " +
                "($RELEASE_451_AAB_SHA256) and the digest of the notice entry inside its base/ " +
                "($RELEASE_451_NOTICE_SHA256). That pair is the ONLY observation this project has " +
                "of a bundleRelease artefact carrying this asset, and a record without it has " +
                "nothing but a debug bundle behind the claim that the notices ship",
            checklist.contains(RELEASE_451_AAB_SHA256) &&
                checklist.contains(RELEASE_451_NOTICE_SHA256),
        )
        assertTrue(
            "the checklist must keep saying that no RELEASE bundle of 4.5.2 has been opened, and " +
                "that a debug bundle is not one. The two halves observed — the release path " +
                "carries this asset unmodified, and this branch's page lands in base/ — are two " +
                "facts, and putting them together is an INFERENCE about an artefact nobody has " +
                "built yet. A promotion is decided from this document, so the inference must not " +
                "be readable as an observation",
            checklist.contains("no release bundle of 4.5.2") &&
                checklist.contains("bundleRelease") &&
                checklist.contains("not legal clearance"),
        )
        for (claim in listOf(
            "observed in the release bundle",
            "the 4.5.2 release bundle was opened",
            "verified in the artefact that ships",
        )) {
            assertTrue(
                "the checklist now says \"$claim\". Nobody has built a 4.5.2 release bundle. " +
                    "Relabelling the debug-bundle observation as the release one is the same " +
                    "class of error as inventing an approval, and this is the document a " +
                    "promotion is read from",
                !checklist.contains(claim),
            )
        }
    }

    // ------------------------------------------------------- 5. the LiteRT libraries (P3a)

    /**
     * THE TWO LITERT LIBRARIES SHIP IN EVERY APK, SO THE PAGE NAMES THEM (P3a, the MediaTek APU
     * tier). Since P2-6 every build packages LiteRT 2.1.1's `libLiteRt.so` into `lib/arm64-v8a/`
     * (extracted from the litert AAR by the `litertRuntime` configuration into a generated jniLibs
     * source) and its `libLiteRtDispatch_MediaTek.so` into the BASE module's assets
     * (`extractLiteRtDispatch`, a generated assets source) — whatever chip the device has. Both are
     * Google's, under Apache-2.0, whose full text is at the foot of the page (held by
     * [OssNoticeTest]). This holds the two entries — each names its file, the release, the licence,
     * the licensor and that it ships unmodified in every copy — and the one further notice
     * LiteRT's own licence file carries: Caffe's BSD 2-Clause notice, for code TensorFlow derives
     * from Caffe, whose second condition asks a BINARY redistribution to reproduce it. Byte for
     * byte as that file gives it: the `LICENSE` entry of `com.google.ai.edge.litert:litert:2.1.1`
     * (13,575 B, sha256 `71c6915d04265772…`), from its line `COPYRIGHT` to its end — 2,112 bytes,
     * LF, pure ASCII (no `&`, `<` or `>`, so the bytes between the tags ARE the notice's bytes).
     * That file's Apache-2.0 half is the ASF text already on the page, but for a leading and a
     * trailing newline.
     *
     * And the placement reason is the build's own: the needles at the end are how
     * `app/build.gradle.kts` puts both files in every APK.
     *
     * TODO(owner): the NeuroPilot Express notice for the compiled MediaTek pair waits on the
     * owner's reading of that licence. The page carries a TODO(owner) beside the MediaTek entry,
     * and this pins that it is there — the question cannot be lost by an edit to the page.
     */
    @Test fun theLiteRtLibrariesEveryApkShipsAreNamedWithTheNoticeTheirLicenceFileCarries() {
        val page = File(repoRoot(), BASE_MODULE_NOTICE).readText().replace("\r\n", "\n")
        val runtime = licenseEntry(page, "litert-runtime")
        val dispatch = licenseEntry(page, "litert-dispatch-mediatek")
        for ((entry, file) in listOf(runtime to "libLiteRt.so", dispatch to "libLiteRtDispatch_MediaTek.so")) {
            for (needle in listOf(
                "<code>$file</code>", "LiteRT 2.1.1", "Apache License 2.0", "(Google)", "unmodified",
                "every copy of this app",
            )) {
                assertTrue("the $file entry does not carry <<$needle>>", entry.contains(needle))
            }
        }
        val opening = "<pre class=\"licence-text\" id=\"litert-caffe\">"
        assertTrue("the Caffe notice belongs to the runtime's entry — libLiteRt.so is what its licence file ships with", runtime.contains(opening))
        val caffe = page.substringAfter(opening).substringBefore("</pre>")
        assertEquals("the Caffe notice is not LiteRT's own, byte for byte (length)", 2_112, caffe.length)
        assertEquals(
            "the Caffe notice is not the one LiteRT 2.1.1's LICENSE carries, byte for byte. BSD " +
                "2-Clause asks a binary redistribution to REPRODUCE the notice, its conditions and " +
                "its disclaimer — a paraphrase is not a reproduction",
            LITERT_CAFFE_NOTICE_SHA256,
            sha256(caffe.toByteArray(Charsets.UTF_8)),
        )
        for (line in listOf(
            "Copyright (c) 2014, The Regents of the University of California (Regents)",
            "2. Redistributions in binary form must reproduce the above copyright notice,",
            "BVLC/caffe",
        )) {
            assertTrue("the Caffe notice lost <<$line>>", caffe.contains(line))
        }
        // TODO(owner) — beside the MediaTek entry, before the next section begins.
        val afterDispatch = page.substringAfter("id=\"litert-dispatch-mediatek\"")
            .substringAfter("</div>")
            .substringBefore("<h2")
        assertTrue(
            "the TODO(owner) for the NeuroPilot Express notice is no longer beside the MediaTek " +
                "entry — the licence the owner is reading may ask for one, and the page is where it goes",
            afterDispatch.contains("<!-- TODO(owner):") && afterDispatch.contains("NeuroPilot Express"),
        )
        // WHY both are on the page: every APK carries them — the build packages them this way.
        val build = buildScript()
        for (needle in listOf(
            "val entry = zip.getEntry(\"jni/arm64-v8a/libLiteRt.so\")",
            "getByName(\"main\") { jniLibs.srcDir(litertJniLibDir) }",
            "\"mediatek_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_MediaTek.so\"",
            "getByName(\"main\") { assets.srcDir(litertDispatchAssetDir) }",
        )) {
            assertTrue(
                "app/build.gradle.kts no longer packages the LiteRT libraries by <<$needle>>. If " +
                    "they stopped shipping, their entries go too; if they ship another way, re-point " +
                    "this needle — the page must name what every APK carries",
                build.contains(needle),
            )
        }
    }

    /**
     * The `<div class="license" id="[id]">` entry's own text, up to its closing tag, with its
     * whitespace COLLAPSED — the house rule for a prose needle, so a re-wrapped line in the page
     * cannot fail a pin that is about the words.
     */
    private fun licenseEntry(page: String, id: String): String {
        val opening = "<div class=\"license\" id=\"$id\">"
        assertTrue("the licences page has no entry <<$opening>>", page.contains(opening))
        return page.substringAfter(opening).substringBefore("</div>").replace(Regex("\\s+"), " ")
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

    /**
     * The notice page in the form a bundle from this repository's release machine PACKAGES — CRLF,
     * because the file is committed with LF and checked out through `core.autocrlf=true`, and the
     * asset merge copies whatever the working tree holds.
     *
     * Canonicalised rather than read raw, and that is the difference between a pin and a trap. A
     * checkout without `autocrlf` (CI, a Linux clone) holds the same 559 lines with 559 fewer
     * bytes and a different digest, so deriving straight off disk would redden this test on a
     * machine where nothing is wrong — a portability failure, in the one test whose job is to keep
     * a legal record honest. Normalising to LF and back makes the derivation say the same thing
     * everywhere while still naming the bytes that actually ship.
     */
    private fun noticeInItsPackagedForm(): ByteArray =
        File(repoRoot(), BASE_MODULE_NOTICE).readText()
            .replace("\r\n", "\n")
            .replace("\n", "\r\n")
            .toByteArray(Charsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

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

        /** The document a promotion is decided from. A declared input of the test task. */
        const val CLEARANCE_DOC = "docs/LANGUAGE-CLEARANCE.md"

        /**
         * The 4.5.1/93 release bundle the controller built and verified — 5,438,505,736 B — and
         * the digest of `base/assets/oss_licenses.html` inside it, which is byte-identical to that
         * release's own committed page. History, so literals: this is the project's one observation
         * of the RELEASE path carrying this asset into `base/` unmodified.
         */
        const val RELEASE_451_AAB_SHA256 =
            "4a168f0a1794cb4fc2a13501ca382fd59d4b848516cd8d7a9c1f705782e2cd65"
        const val RELEASE_451_NOTICE_SHA256 =
            "7901187a688a45c0478e1b165b67d1e59ed5af86bc1e519bbb5172b89913e524"

        /**
         * Caffe's BSD 2-Clause notice as LiteRT 2.1.1's own `LICENSE` gives it (the litert AAR's
         * `LICENSE` entry, 13,575 B, sha256 `71c6915d04265772a0339bed47276942c678b45cc01534210ebe6984fd1aec65`,
         * read 2026-09-25): from its line `COPYRIGHT` to the end of the file, 2,112 bytes, LF, no
         * trailing newline. History of a third party's file, so a literal.
         */
        const val LITERT_CAFFE_NOTICE_SHA256 =
            "20b940720cbcfa7d6c1400b74794737062c2476bd89c6463cc263c966038ec32"
    }
}
