package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The `preview_en` Play delivery layout, pinned to [StreamingPackCatalog] (4.4.0, the 2026-09-10
 * amendment) — `NpuPackLayoutTest`'s discipline for the third pack module: the module's build
 * file, its payload wall, the settings include, the app's `assetPacks` list, the bundle gate and
 * the build script's placement table are six committed spellings of ONE catalog, held equal.
 *
 * ### Why this pack is NOT device-targeted
 *
 * The NPU packs ship one `#group_<packGroup>` variant per census family because the payload
 * differs per SoC. These four ONNX files are the same bytes on every device, so the pack carries
 * ONE untargeted directory — and an untargeted directory is delivered to every device, which is
 * why `device_targeting_config.xml` is not touched here and must not be: adding a group for this
 * pack would be a way to accidentally deliver nothing to a device in no group. (The app module
 * already ships untargeted `assets/` under the same `deviceGroup { enableSplit = true }` bundle
 * block, which is the standing proof that untargeted content coexists with group splitting.)
 *
 * ### The directory is named after the PACK, for the reason 4.2 F8 discovered
 *
 * An AAB may not carry the same entry path in two modules with different bytes. `assets/preview_en/`
 * cannot collide with either NPU module's `assets/npu_<tier>#group_<g>` dirs by construction,
 * and Play strips a group suffix on delivery, so the delivered directory is `preview_en/` — which
 * is exactly what [StreamingPackInstall.packSourceDir] opens.
 *
 * ### The payload is a BUILD artifact
 *
 * `tools/build_asset_packs.py preview` places the four files from the pinned Hugging Face commit
 * (or from a local mirror it re-hashes), so a clean clone reproduces the pack; the module's
 * `.gitignore` keeps the bytes structurally uncommittable and `verifyPreviewPack` refuses a
 * bundle whose payload is missing or the wrong size. Every file this class reads is in the test
 * task's `sourcePinnedInputs` — without that, an edit confined to any of them would leave
 * `:app:testDebugUnitTest` UP-TO-DATE and these pins would pass against stale evidence.
 */
class PreviewPackLayoutTest {

    private val pack = StreamingPackCatalog.EN
    private val module = StreamingPackCatalog.PACK_EN

    // ------------------------------------------------------------------ source helpers
    // (NpuPackLayoutTest's own, verbatim: the same walk, the same LF normalisation.)

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

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    /** 71083163 -> "71_083_163", the underscore grouping every build-script literal uses. */
    private fun grouped(n: Long): String =
        n.toString().reversed().chunked(3).joinToString("_").reversed()

    private val appGradle: String by lazy { read("build.gradle.kts") }
    private val script: String by lazy { read("tools/build_asset_packs.py") }

    // ------------------------------------------------------------------ the module

    @Test
    fun thePreviewPackDeclaresItsExactNameAndOnDemandDelivery() {
        val moduleGradle = read("$module/build.gradle.kts")
        assertEquals(1, count(moduleGradle, "id(\"com.android.asset-pack\")"))
        assertEquals(
            "the pack name is the Play-side identity — fetch(), getPackLocation() and the " +
                "delivered directory name all key on it, and the catalog carries the same string",
            1, count(moduleGradle, "packName.set(\"$module\")"),
        )
        assertEquals(
            "on-demand: 73 MB is fetched when the user asks for live words, never pushed at " +
                "install time onto every device that will not turn the previewer on",
            1, count(moduleGradle, "deliveryType.set(\"on-demand\")"),
        )
        assertEquals(
            "and the module declares NO device targeting of its own — the four files are the " +
                "same bytes on every device (the untargeted-directory half of that rule is " +
                "asserted over the committed directory, below)",
            0, count(moduleGradle, "deviceTargetingConfig"),
        )
    }

    @Test
    fun theModuleIsIncludedInSettingsWithoutDisturbingTheNpuIncludeLine() {
        val settings = read("settings.gradle.kts")
        assertEquals(
            "the preview pack is included in its own statement",
            1, count(settings, "include(\":$module\")"),
        )
        assertEquals(
            "and the NPU pair's include line is untouched — a rewritten list is how a pack " +
                "silently stops shipping",
            1, count(settings, "include(\":npu_turbo\", \":npu_small\")"),
        )
        assertEquals(1, count(settings, "include(\":app\")"))
    }

    @Test
    fun theAppDeclaresThePreviewPackInItsOneAssetPacksStatement() {
        assertEquals(
            "the packs are declared in ONE list — a pack missing here ships no variants at all, " +
                "silently, and a second assetPacks statement would be a second list to keep " +
                "correct. (4.4.0 Task 2b appended the untargeted :tts_kokoro; this pin is scoped " +
                "to the NPU pair PLUS this pack's own position, so it still fails if :preview_en " +
                "is dropped or reordered, while TtsPackLayoutTest owns the voice pack's half — " +
                "neither can be deleted by editing the other's test.)",
            1,
            count(appGradle, "assetPacks += listOf(\":npu_turbo\", \":npu_small\", \":$module\","),
        )
        assertEquals(1, count(appGradle, "assetPacks +="))
    }

    // ------------------------------------------------------------------ the payload boundary

    @Test
    fun theGitignoreKeepsThePayloadUncommittableAndTheAnchorVisible() {
        val ignore = read("$module/.gitignore")
        assertEquals(
            "$module/.gitignore must wall the payload directory's contents — the four ONNX " +
                "files are a BUILD artifact placed by tools/build_asset_packs.py, and the root " +
                ".gitignore's blob walls do not cover .onnx",
            1, count(ignore, "src/main/assets/$module/*"),
        )
        assertEquals(
            "and it must re-include the .gitkeep, the one TRACKED file that proves the payload " +
                "directory exists in a clean clone — a wall that hides it un-tracks this " +
                "layout's own evidence",
            1, count(ignore, "!src/main/assets/$module/.gitkeep"),
        )
    }

    @Test
    fun theCommittedPayloadDirectoryIsTheAnchorPlusNothingButThePinnedFour() {
        val assets = repoFile("$module/src/main/assets")
        val dirs = (assets.listFiles() ?: emptyArray()).map { it.name }.sorted()
        assertEquals(
            "$module ships exactly ONE asset directory, named after the pack: no two modules " +
                "may carry the same entry path (4.2 F8), and an untargeted directory reaches " +
                "every device",
            listOf(module), dirs,
        )
        for (name in dirs) {
            assertTrue(
                "'$name' must carry no device-group suffix: a `#group_` variant here would be a " +
                    "way for a device in no group to receive nothing, and every device needs " +
                    "these same four files",
                "#group_" !in name,
            )
        }
        val payload = File(assets, module)
        val names = (payload.listFiles() ?: emptyArray()).map { it.name }.sorted()
        assertTrue(
            "the committed anchor must be there: $names",
            ".gitkeep" in names,
        )
        val allowed = (pack.files.map { it.name } + ".gitkeep").sorted()
        for (name in names) {
            assertTrue(
                "assets/$module/$name is not one of the catalog's four files nor the anchor — " +
                    "anything else in this directory rides into the AAB and onto every device",
                name in allowed,
            )
        }
    }

    // ------------------------------------------------------------------ the bundle gate

    @Test
    fun verifyPreviewPackHoldsThePayloadToTheCatalogBytesAndGatesOnlyBundleBuilds() {
        assertEquals(1, count(appGradle, "tasks.register(\"verifyPreviewPack\")"))
        // The placement table: one row per pinned file, byte literals the catalog's own —
        // restated in the build script because it cannot read the app's classes, and pinned
        // equal here (the verifyNpuPacks discipline, one pack over).
        for (f in pack.files) {
            assertEquals(
                "verifyPreviewPack carries ${f.name}'s catalog byte count in one literal row",
                1, count(appGradle, "listOf(\"${f.name}\", ${grouped(f.bytes)}L),"),
            )
        }
        assertEquals(
            "the gate reads the pack module's one untargeted payload directory",
            1, count(appGradle, "\"$module/src/main/assets/$module\""),
        )
        // Wired before bundle PACKAGING only, in its OWN clause: assembleDebug must never
        // demand 73 MB of payload (an APK build carries no packs at all), and the NPU gate's
        // single wiring line stays exactly as it was.
        assertEquals(1, count(appGradle, "dependsOn(verifyPreviewPack)"))
        assertEquals(
            "the NPU gate is still wired exactly once, by its own clause",
            1, count(appGradle, "dependsOn(verifyNpuPacks)"),
        )
        assertEquals(
            "and no gate hangs off preBuild or assemble — three packs' worth of payload " +
                "(4.2 F4's two, this one, and Task 2b's voice archive) must never be demanded " +
                "by the everyday APK build, which carries no packs at all",
            3, count(appGradle, "it.name.startsWith(\"package\") && it.name.endsWith(\"Bundle\")"),
        )
    }

    @Test
    fun theModulesTwoUnbuiltFilesAreDeclaredTestInputs() {
        // The list's stated rule: membership follows what the tests READ. Neither of these is an
        // input to any compile task, so without the entries an edit confined to the module's
        // build file or its wall would leave this suite UP-TO-DATE and these pins green.
        assertEquals(1, count(appGradle, "rootProject.file(\"$module/build.gradle.kts\")"))
        assertEquals(1, count(appGradle, "rootProject.file(\"$module/.gitignore\")"))
    }

    // ------------------------------------------------------------------ the pack builder

    @Test
    fun theBuildScriptPlacesTheCatalogsOwnFourFilesAndReVerifiesWhatLanded() {
        assertEquals(
            "the script names the module it fills",
            1, count(script, "PREVIEW_MODULE = \"$module\""),
        )
        assertEquals(
            "and the commit-pinned base URL — resolve/<sha>/, never resolve/main",
            1, count(script, pack.baseUrl),
        )
        for (f in pack.files) {
            assertEquals(
                "the placement table carries ${f.name} with the catalog's byte count and " +
                    "sha256 as literals — the cross-pin that stops the committed catalog and " +
                    "the instrument that fills the pack drifting apart",
                1,
                count(script, "(\"${f.name}\", ${grouped(f.bytes)}, \"${f.sha256}\")"),
            )
        }
        assertEquals(
            "the placement re-verifies its own output through one function",
            1, count(script, "def verify_preview_dir("),
        )
        assertTrue(
            "and a failed self-verification is a named FATAL, not a warning",
            count(script, "the placed preview pack failed its own verification") == 1,
        )
    }
}
