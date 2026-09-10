package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The `tts_kokoro` Play delivery layout, pinned to [TtsModelManager]'s own archive census (4.4.0,
 * the 2026-09-10 amendment, Task 2b) — `PreviewPackLayoutTest`'s discipline for the FOURTH pack
 * module: the module's build file, its payload wall, the settings include, the app's `assetPacks`
 * list, the bundle gate and the build script's placement row are six committed spellings of ONE
 * archive, held equal.
 *
 * ### The pin that closes the 2026-09-08 incident
 *
 * The voice archive rode a ROLLING GitHub release tag (`tts-models`). k2-fsa re-uploaded it, the
 * size stayed inside the ±5 % band, the single pinned sha256 stopped matching, and every fresh
 * voice install on every build — production included — failed "integrity verification" for two
 * days. Delivering the archive as an asset pack closes that structurally: the bytes ride the AAB,
 * so a voice update becomes a deliberate new release. What makes it airtight is
 * [theBuildScriptPlacesTheArchiveTheAppsOwnGateAccepts]: the digest the script places must be the
 * FIRST entry of [TtsModelManager.KNOWN_GOOD_TAR_SHA256] — the archive the device's own gate
 * accepts — so a pack built from some other upload is a red test here rather than a pack that
 * ships and cannot install.
 *
 * ### Why this pack is NOT device-targeted
 *
 * Same reason as `preview_en`'s: one archive, the same bytes on every device, so the pack carries
 * ONE untargeted directory and `device_targeting_config.xml` (the NPU packs' SoC targeting) is not
 * touched and must not be — a device group here would only add a way for a device in no group to
 * receive nothing.
 *
 * ### The directory is named after the PACK, for the reason 4.2 F8 discovered
 *
 * An AAB may not carry the same entry path in two modules with different bytes. Play strips a
 * group suffix on delivery, so the delivered directory is `tts_kokoro/` — which is exactly what
 * [TtsModelManager.packTarIn] opens.
 *
 * Every file this class reads is in the test task's `sourcePinnedInputs` in `app/build.gradle.kts`;
 * without those entries an edit confined to any of them would leave `:app:testDebugUnitTest`
 * UP-TO-DATE and these pins would pass against stale evidence.
 */
class TtsPackLayoutTest {

    private val module = TtsModelManager.PACK_NAME

    // ------------------------------------------------------------------ source helpers
    // (PreviewPackLayoutTest's own, verbatim: the same walk, the same LF normalisation.)

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

    /** 349906910 -> "349_906_910", the underscore grouping every build-script literal uses. */
    private fun grouped(n: Long): String =
        n.toString().reversed().chunked(3).joinToString("_").reversed()

    private val appGradle: String by lazy { read("build.gradle.kts") }
    private val script: String by lazy { read("tools/build_asset_packs.py") }

    // ------------------------------------------------------------------ the module

    @Test
    fun theVoicePackDeclaresItsExactNameAndOnDemandDelivery() {
        val moduleGradle = read("$module/build.gradle.kts")
        assertEquals(1, count(moduleGradle, "id(\"com.android.asset-pack\")"))
        assertEquals(
            "the pack name is the Play-side identity — fetch(), getPackLocation() and the " +
                "delivered directory name all key on it, and TtsModelManager carries the same " +
                "string",
            1, count(moduleGradle, "packName.set(\"$module\")"),
        )
        assertEquals(
            "on-demand: 350 MB is fetched when the user asks for read-aloud, never pushed at " +
                "install time onto every device that will never speak a word",
            1, count(moduleGradle, "deliveryType.set(\"on-demand\")"),
        )
        assertEquals(
            "and the module declares NO device targeting of its own — one archive, the same " +
                "bytes on every device (the untargeted-directory half of that rule is asserted " +
                "over the committed directory, below)",
            0, count(moduleGradle, "deviceTargetingConfig"),
        )
    }

    @Test
    fun theModuleIsIncludedInSettingsWithoutDisturbingTheOtherThree() {
        val settings = read("settings.gradle.kts")
        assertEquals(
            "the voice pack is included in its own statement",
            1, count(settings, "include(\":$module\")"),
        )
        assertEquals(
            "the NPU pair's include line is untouched — a rewritten list is how a pack " +
                "silently stops shipping",
            1, count(settings, "include(\":npu_turbo\", \":npu_small\")"),
        )
        assertEquals(
            "and the previewer's own statement is untouched too",
            1, count(settings, "include(\":preview_en\")"),
        )
        assertEquals(1, count(settings, "include(\":app\")"))
    }

    @Test
    fun theAppDeclaresTheVoicePackInItsOneAssetPacksStatement() {
        assertEquals(
            "the FOUR packs are declared in ONE list — a pack missing here ships no variants " +
                "at all, silently, and a second assetPacks statement would be a second list to " +
                "keep correct",
            1,
            count(
                appGradle,
                "assetPacks += listOf(\":npu_turbo\", \":npu_small\", \":preview_en\", \":$module\")",
            ),
        )
        assertEquals(1, count(appGradle, "assetPacks +="))
    }

    // ------------------------------------------------------------------ the payload boundary

    @Test
    fun theGitignoreKeepsThePayloadUncommittableAndTheAnchorVisible() {
        val ignore = read("$module/.gitignore")
        assertEquals(
            "$module/.gitignore must wall the payload directory's contents — the 350 MB archive " +
                "is a BUILD artifact placed by tools/build_asset_packs.py, and the root " +
                ".gitignore's blob walls do not cover .tar.bz2",
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
    fun theCommittedPayloadDirectoryIsTheAnchorPlusNothingButThePinnedArchive() {
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
                    "way for a device in no group to receive nothing, and every device that " +
                    "speaks needs this same archive",
                "#group_" !in name,
            )
        }
        val payload = File(assets, module)
        val names = (payload.listFiles() ?: emptyArray()).map { it.name }.sorted()
        assertTrue("the committed anchor must be there: $names", ".gitkeep" in names)
        val allowed = listOf(TtsModelManager.TAR_NAME, ".gitkeep").sorted()
        for (name in names) {
            assertTrue(
                "assets/$module/$name is neither the pinned archive nor the anchor — anything " +
                    "else in this directory rides into the AAB and onto every device that " +
                    "fetches the voice",
                name in allowed,
            )
        }
    }

    // ------------------------------------------------------------------ the bundle gate

    @Test
    fun verifyTtsPackHoldsThePayloadToTheArchiveBytesAndGatesOnlyBundleBuilds() {
        assertEquals(1, count(appGradle, "tasks.register(\"verifyTtsPack\")"))
        assertEquals(
            "the gate carries the archive's name and TtsModelManager.TAR_BYTES in one literal " +
                "row — restated in the build file because it cannot read the app's classes, and " +
                "pinned equal here (the verifyNpuPacks discipline, one pack over)",
            1,
            count(
                appGradle,
                "listOf(\"${TtsModelManager.TAR_NAME}\", ${grouped(TtsModelManager.TAR_BYTES)}L),",
            ),
        )
        assertEquals(
            "the gate reads the pack module's one untargeted payload directory",
            1, count(appGradle, "\"$module/src/main/assets/$module\""),
        )
        // Wired before bundle PACKAGING only, in its OWN clause: assembleDebug must never demand
        // 350 MB of payload (an APK build carries no packs at all), and neither of the two
        // existing gates' single wiring lines may change.
        assertEquals(1, count(appGradle, "dependsOn(verifyTtsPack)"))
        assertEquals(
            "the previewer's gate is still wired exactly once, by its own clause",
            1, count(appGradle, "dependsOn(verifyPreviewPack)"),
        )
        assertEquals(
            "and the NPU gate too",
            1, count(appGradle, "dependsOn(verifyNpuPacks)"),
        )
        assertEquals(
            "three gates, three clauses, and none of them hangs off preBuild or assemble",
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
    fun theBuildScriptPlacesTheArchiveTheAppsOwnGateAccepts() {
        assertEquals(
            "the script names the module it fills",
            1, count(script, "TTS_MODULE = \"$module\""),
        )
        assertEquals(
            "the placement row carries the archive's name, TtsModelManager.TAR_BYTES and the " +
                "digest the device's own gate accepts FIRST — the 2026-09-08 incident's own " +
                "cross-pin: a pack built from a different upload would ship an AAB whose voice " +
                "can never install, and nothing else offline would notice",
            1,
            count(
                script,
                "(\"${TtsModelManager.TAR_NAME}\", ${grouped(TtsModelManager.TAR_BYTES)}, " +
                    "\"${TtsModelManager.KNOWN_GOOD_TAR_SHA256.first()}\")",
            ),
        )
        assertEquals(
            "and the upstream URL it falls back to when the local mirror has nothing",
            1,
            count(
                script,
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                    TtsModelManager.TAR_NAME,
            ),
        )
        assertEquals(
            "the placement re-verifies its own output through one function",
            1, count(script, "def verify_tts_dir("),
        )
        assertEquals(
            "and a failed self-verification is a named FATAL, not a warning",
            1, count(script, "the placed voice pack failed its own verification"),
        )
    }
}
