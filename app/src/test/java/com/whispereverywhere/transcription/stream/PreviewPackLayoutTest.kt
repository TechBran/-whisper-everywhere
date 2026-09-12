package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The preview packs' Play delivery layout, pinned to [StreamingPackCatalog] (4.4.0, the 2026-09-10
 * amendment; SEVEN packs since 4.5.0 Task 2) — `NpuPackLayoutTest`'s discipline for every pack
 * module the previewer ships: each module's build file, its payload wall, its settings include, its
 * entry in the app's `assetPacks` list, the bundle gate's row and the build script's placement
 * table are six committed spellings of ONE catalog, held equal.
 *
 * **Every assertion here is driven off [StreamingPackCatalog.packs], not off a list of seven
 * names.** That is what makes an eighth language a red suite rather than a silent omission: a new
 * row with a `packName` and no module directory fails the module, wall, include, `assetPacks`, gate
 * and script pins at once, and a row that deliberately ships no pack must say so by carrying
 * `packName = null` (which [StreamingPack]'s own KDoc defines as fallback-only).
 *
 * ### Why these packs are NOT device-targeted
 *
 * The NPU packs ship one `#group_<packGroup>` variant per census family because the payload differs
 * per SoC. A preview pack's four files are the same bytes on every device, so each carries ONE
 * untargeted directory — and an untargeted directory is delivered to every device, which is why
 * `device_targeting_config.xml` is not touched here and must not be: adding a group for these packs
 * would be a way to accidentally deliver nothing to a device in no group. (The app module already
 * ships untargeted `assets/` under the same `deviceGroup { enableSplit = true }` bundle block,
 * which is the standing proof that untargeted content coexists with group splitting.)
 *
 * ### Each directory is named after its PACK, for the reason 4.2 F8 discovered
 *
 * An AAB may not carry the same entry path in two modules with different bytes. `assets/preview_en/`
 * cannot collide with either NPU module's `assets/npu_<tier>#group_<g>` dirs by construction, and
 * naming every preview pack's directory after the pack retires the clash between the seven of them
 * as a class rather than one instance — [noTwoPacksShareAnAssetPathInTheBundle] is that rule with
 * teeth. Play strips a group suffix on delivery, so the delivered directory is `<packName>/` —
 * which is exactly what [StreamingPackInstall.packSourceDir] opens.
 *
 * ### The payload is a BUILD artifact
 *
 * `tools/build_asset_packs.py preview` places every pack's four files from its pinned Hugging Face
 * commit (or from a local mirror it re-hashes), so a clean clone reproduces all seven; each module's
 * `.gitignore` keeps the bytes structurally uncommittable and `verifyPreviewPack` refuses a bundle
 * whose payload is missing or the wrong size. Every file this class reads is in the test task's
 * `sourcePinnedInputs` — without that, an edit confined to any of them would leave
 * `:app:testDebugUnitTest` UP-TO-DATE and these pins would pass against stale evidence.
 */
class PreviewPackLayoutTest {

    /**
     * Every catalogue row that ships a Play pack, paired with its pack name — which is also its
     * module directory, its asset directory and its delivered directory. A row with
     * `packName = null` ships no module and is deliberately absent from every pin below.
     */
    private val modules: List<Pair<StreamingPack, String>> =
        StreamingPackCatalog.packs.mapNotNull { pack -> pack.packName?.let { pack to it } }

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

    // ------------------------------------------------------------------ the census

    @Test
    fun everyCatalogueRowShipsItsOwnPackAndNoTwoRowsShareOne() {
        assertEquals(
            "all seven catalogue rows ship a Play pack module today, and this count is what makes " +
                "adding an eighth language a DECISION rather than an oversight: the next row must " +
                "either bring a module — and every loop below then covers it without being " +
                "edited — or say out loud that it is download-only by carrying packName = null",
            7, modules.size,
        )
        assertEquals(
            "and no two rows may share a pack name: it is the Play fetch identity, the delivered " +
                "directory AND the AAB asset path all at once, so a shared one is two languages' " +
                "bytes claiming one entry",
            modules.size, modules.map { it.second }.distinct().size,
        )
    }

    @Test
    fun noTwoPacksShareAnAssetPathInTheBundle() {
        val entries = modules.flatMap { (pack, module) ->
            pack.files.map { "assets/$module/${it.name}" }
        }
        assertEquals(
            "an AAB may not carry the same entry path in two modules with different bytes (4.2 " +
                "F8's rule, re-pinned by the 2026-09-12 brief: \"no two packs may share an asset " +
                "path\"). These are the ${entries.size} entry paths the preview packs contribute, " +
                "and the collision is a real one to guard rather than a theoretical one: the " +
                "English and Korean packs ship THREE identically named files each " +
                "(encoder/decoder/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx) with " +
                "different bytes, and every one of the seven ships a tokens.txt. Naming each " +
                "directory after its pack is what keeps all ${entries.size} distinct.",
            entries.size, entries.distinct().size,
        )
        for ((_, module) in modules) {
            assertTrue(
                "'$module' must also not collide with the NPU packs' or the voice pack's " +
                    "directories, which it cannot while every preview pack's name starts with " +
                    "preview_ and neither npu_<tier> nor tts_kokoro does",
                module.startsWith("preview_"),
            )
        }
    }

    // ------------------------------------------------------------------ the modules

    @Test
    fun everyPackModuleDeclaresItsExactNameAndOnDemandDelivery() {
        for ((pack, module) in modules) {
            val moduleGradle = read("$module/build.gradle.kts")
            assertEquals("$module is an asset pack", 1, count(moduleGradle, "id(\"com.android.asset-pack\")"))
            assertEquals(
                "the pack name is the Play-side identity — fetch(), getPackLocation() and the " +
                    "delivered directory name all key on it, and the catalog carries the same " +
                    "string for ${pack.language}",
                1, count(moduleGradle, "packName.set(\"$module\")"),
            )
            assertEquals(
                "on-demand: ${StreamingPackCatalog.sizeBadge(pack.totalBytes)} is fetched when " +
                    "the user picks ${pack.language} and asks for live words, never pushed at " +
                    "install time onto every device that will never pick that language",
                1, count(moduleGradle, "deliveryType.set(\"on-demand\")"),
            )
            assertEquals(
                "and $module declares NO device targeting of its own — its four files are the " +
                    "same bytes on every device (the untargeted-directory half of that rule is " +
                    "asserted over the committed directory, below)",
                0, count(moduleGradle, "deviceTargetingConfig"),
            )
        }
    }

    @Test
    fun everyModuleIsIncludedInSettingsWithoutDisturbingTheNpuIncludeLine() {
        val settings = read("settings.gradle.kts")
        for ((_, module) in modules) {
            assertEquals(
                "$module is included in its OWN statement — one statement per pack is what makes " +
                    "\"this language is in the build\" a claim a single line can be checked against",
                1, count(settings, "include(\":$module\")"),
            )
        }
        assertEquals(
            "and the NPU pair's include line is untouched — a rewritten list is how a pack " +
                "silently stops shipping",
            1, count(settings, "include(\":npu_turbo\", \":npu_small\")"),
        )
        assertEquals(1, count(settings, "include(\":app\")"))
    }

    @Test
    fun theAppDeclaresEveryPackInItsOneUnconditionalAssetPacksStatement() {
        assertEquals(
            "the packs are declared in ONE list — a pack missing here ships no variants at all, " +
                "silently, and a second assetPacks statement would be a second list to keep " +
                "correct. (4.4.0 Task 2b appended the untargeted :tts_kokoro and 4.5.0 Task 2 " +
                "CONCATENATED the six language packs rather than editing this prefix, precisely so " +
                "this pin, NpuPackLayoutTest's and TtsPackLayoutTest's all keep their teeth.)",
            1,
            count(appGradle, "assetPacks += listOf(\":npu_turbo\", \":npu_small\", \":preview_en\","),
        )
        assertEquals(1, count(appGradle, "assetPacks +="))
        for ((pack, module) in modules) {
            assertEquals(
                "$module is named exactly once in the app's assetPacks list; without it the " +
                    "${pack.language} pack ships no variants at all, silently, and Play answers " +
                    "every fetch for it with an error the user reads as \"live words are broken\"",
                1, count(appGradle, "\":$module\""),
            )
        }
        // THE UNCONDITIONALITY PIN. Asserted as EXACT TEXT rather than as a set of zero-counts,
        // because a zero-count has to guess at the shape of the condition it forbids and exact
        // text does not have to guess at anything.
        val statement =
            "    assetPacks += listOf(\":npu_turbo\", \":npu_small\", \":preview_en\", " +
                "\":tts_kokoro\") + listOf(\n" +
                "        \":preview_fr\", \":preview_de\", \":preview_ru\",\n" +
                "        \":preview_id\", \":preview_ko\", \":preview_zh\",\n" +
                "    )\n"
        assertEquals(
            "the assetPacks expression must stay a FLAT LITERAL of the ten pack modules: no build " +
                "type, no flavour, no gradle property, no environment read and — the one this pin " +
                "exists for — no per-language readiness term of any kind. A build-time exclusion " +
                "of a language is FORBIDDEN (owner, 2026-09-12: \"I still will need to be able to " +
                "test on internal testing track before the legal stuff\"), so every language is " +
                "present and fetchable in every bundle this repo can build, and whatever gates " +
                "PUBLICATION is a promotion decision that cannot be expressed here.",
            1, count(appGradle, statement),
        )
    }

    // ------------------------------------------------------------------ the payload boundary

    @Test
    fun everyGitignoreKeepsThePayloadUncommittableAndTheAnchorVisible() {
        for ((pack, module) in modules) {
            // WHOLE LINES, not substrings, and the difference is not pedantry: a wall narrowed to
            // `src/main/assets/preview_fr/*.tmp` still CONTAINS `src/main/assets/preview_fr/*`, so
            // a substring count cannot tell a wall from a wall with a hole in it. Measured: that
            // one-word edit leaves 128 MB of French encoder committable and the substring form of
            // this pin stays green. (preview_en's and tts_kokoro's own pins are still written the
            // substring way — noted for their owners; this loop covers all seven preview packs.)
            val lines = read("$module/.gitignore").lines().map { it.trim() }
            assertEquals(
                "$module/.gitignore must wall the payload directory's contents with the LINE " +
                    "`src/main/assets/$module/*` — its four files are a BUILD artifact placed by " +
                    "tools/build_asset_packs.py, and the root .gitignore's blob walls do not " +
                    "cover .onnx, so ${StreamingPackCatalog.sizeBadge(pack.totalBytes)} of model " +
                    "would otherwise be committable by default into a repo with a public remote",
                1, lines.count { it == "src/main/assets/$module/*" },
            )
            assertEquals(
                "and it must re-include the .gitkeep on its own LINE, the one TRACKED file there " +
                    "that proves the payload directory exists in a clean clone — a wall that " +
                    "hides it un-tracks this layout's own evidence",
                1, lines.count { it == "!src/main/assets/$module/.gitkeep" },
            )
            assertEquals(
                "and the wall re-includes NOTHING ELSE: every other `!` line would be a named " +
                    "exception carrying model bytes past the wall, which is the whole point of " +
                    "writing it as \"everything EXCEPT the anchor\"",
                1, lines.count { it.startsWith("!") },
            )
        }
    }

    @Test
    fun everyCommittedPayloadDirectoryIsTheAnchorPlusNothingButThatPacksPinnedFour() {
        for ((pack, module) in modules) {
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
                    "'$name' must carry no device-group suffix: a `#group_` variant here would be " +
                        "a way for a device in no group to receive nothing, and every device needs " +
                        "the same four files",
                    "#group_" !in name,
                )
            }
            val payload = File(assets, module)
            val names = (payload.listFiles() ?: emptyArray()).map { it.name }.sorted()
            assertTrue(
                "$module's committed anchor must be there: $names",
                ".gitkeep" in names,
            )
            val allowed = (pack.files.map { it.name } + ".gitkeep").sorted()
            for (name in names) {
                assertTrue(
                    "assets/$module/$name is not one of ${pack.language}'s four catalog files nor " +
                        "the anchor — anything else in this directory rides into the AAB and onto " +
                        "every device that fetches the pack",
                    name in allowed,
                )
            }
        }
    }

    @Test
    fun everyModulesTwoUnbuiltFilesAreDeclaredTestInputs() {
        // The list's stated rule: membership follows what the tests READ. Neither of these is an
        // input to any compile task, so without the entries an edit confined to a module's build
        // file or its wall would leave this suite UP-TO-DATE and these pins green.
        for ((_, module) in modules) {
            assertEquals(
                "$module/build.gradle.kts is a declared test input",
                1, count(appGradle, "rootProject.file(\"$module/build.gradle.kts\")"),
            )
            assertEquals(
                "$module/.gitignore is a declared test input",
                1, count(appGradle, "rootProject.file(\"$module/.gitignore\")"),
            )
        }
    }

    // ------------------------------------------------------------------ the bundle gate

    @Test
    fun verifyPreviewPackHoldsThePayloadToTheCatalogBytesAndGatesOnlyBundleBuilds() {
        val pack = StreamingPackCatalog.EN
        val module = StreamingPackCatalog.PACK_EN
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

    // ------------------------------------------------------------------ the pack builder

    @Test
    fun theBuildScriptPlacesEveryRowsFourFilesAndReVerifiesWhatLanded() {
        for ((pack, module) in modules) {
            assertEquals(
                "the script names $module as a pack it fills",
                1, count(script, "module=\"$module\","),
            )
            assertEquals(
                "and ${pack.language}'s commit-pinned base URL — resolve/<sha>/, never " +
                    "resolve/main, because a mutable ref would rebuild a DIFFERENT pack under " +
                    "the same name",
                1, count(script, pack.baseUrl),
            )
            assertEquals(
                "and the mirror subdirectory is the catalogue's own dirName, so the cache, the " +
                    "mirror and the installed directory on the device all follow ONE naming rule",
                1, count(script, "mirror_dir=\"${pack.dirName}\","),
            )
            for (f in pack.files) {
                assertEquals(
                    "the placement table carries ${f.name} with ${pack.language}'s byte count, " +
                        "sha256 AND upstream path as literals, in one contiguous tuple — the " +
                        "cross-pin that stops the committed catalog and the instrument that " +
                        "fills the pack drifting apart",
                    1,
                    count(
                        script,
                        "(\"${f.name}\", ${grouped(f.bytes)}, \"${f.sha256}\", \"${f.path}\")",
                    ),
                )
            }
        }
        assertEquals(
            "the script builds the download URL from the file's UPSTREAM path and never from its " +
                "flat local name. The two differ on de and zh, and each way round fails " +
                "differently: `name` in the URL is a 404, and `path` on disk is a subdirectory " +
                "the installer never writes and sherpa never opens. There is one expression, and " +
                "this is it.",
            1, count(script, "pack.base_url + path"),
        )
        assertEquals(
            "the placement re-verifies its own output through one function, per pack",
            1, count(script, "def verify_preview_dir("),
        )
        assertEquals(
            "and a failed self-verification is a named FATAL, not a warning",
            1, count(script, "the placed preview pack failed its own verification"),
        )
    }
}
