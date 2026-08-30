package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Play delivery layout, pinned to the census (4.2 F4): the device-group XML, the two
 * asset-pack modules, the app's `assetPacks`/`bundle{}` wiring, the `verifyNpuPacks` gate and
 * the build script's pack modes — five committed spellings of ONE census, held equal.
 *
 * ### The XML GENERATES from the census — never hand-typed
 *
 * [renderedDeviceTargetingXml] is the generator: it renders `app/device_targeting_config.xml`
 * from `NpuFleetCensus.families` in table order — `packGroup` as the group name, `socModels`
 * as the exact selector strings, one selector per manufacturer spelling in
 * `NpuGate.SUPPORTED_SOC_MANUFACTURERS` (the gate's own {QTI, Qualcomm} doctrine, mirrored:
 * Play matches `Build.SOC_MANUFACTURER` literally too, and a "Qualcomm"-spelled OEM build
 * would otherwise pass the app gate and land in the empty default — fail-safe, but exactly
 * the lost-coverage trap the census exists to prevent). The committed file must equal the
 * rendering BYTE FOR BYTE, which is what makes maintenance rule 1 mechanical: a census edit
 * (a new suffix bin, a fifth family) fails this suite until the XML is regenerated in the
 * SAME commit, and the failure message prints the exact regenerated text to commit.
 *
 * ### Why Play's copy must be exact, not merely similar
 *
 * The research-sketch groups listed plain `SM8750`/`SM8850`; the spec's census superseded
 * them (widening is a measurement, never a guess), and two censuses is how a device passes
 * one gate and fails the other. So the pins here are exhaustive in BOTH directions: every
 * census string under both spellings, and nothing the census does not name — asserted over
 * exact attribute values, never substrings, because `SM8750-AC` contains `SM8750`.
 *
 * ### The EMPTY default is a correctness feature
 *
 * Play cannot be told to deliver nothing: an unmatched device can never be *prevented* from
 * receiving the default variant, so the default must contain nothing worth receiving. That
 * rule is held three times — here (the committed `model/` dirs carry exactly `.gitkeep`,
 * executed), by `verifyNpuPacks` before every bundle packaging task (the build gate), and by
 * each module's `.gitignore` (the payload dirs are structurally uncommittable, so the
 * committed tree cannot even carry content to leak into a variant).
 *
 * No JVM test can run a Gradle bundle build or call Play, so the build-side halves are
 * SOURCE pins (the L6 split); the census side executes. Every file this class reads is in
 * the test task's `sourcePinnedInputs`, or an edit confined to it would leave the suite
 * UP-TO-DATE and these pins would pass against stale evidence.
 */
class NpuPackLayoutTest {

    private val families = NpuFleetCensus.families
    private val manufacturers = NpuGate.SUPPORTED_SOC_MANUFACTURERS.toList()

    /** tier id -> the pack MODULE that ships it (the brief's two names; F5's PACK_BY_TIER
     *  will spell the same mapping through the tier-id homes). */
    private val moduleByTier = mapOf("npu" to "npu_small", "npu-turbo" to "npu_turbo")

    // ------------------------------------------------------------------ source helpers

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

    /** 132927488 -> "132_927_488", the underscore grouping every build-script literal uses. */
    private fun grouped(n: Long): String =
        n.toString().reversed().chunked(3).joinToString("_").reversed()

    private val xml: String by lazy { read("app/device_targeting_config.xml") }
    private val appGradle: String by lazy { read("build.gradle.kts") }
    private val script: String by lazy { read("tools/build_asset_packs.py") }

    // ------------------------------------------------------------------ the generator

    /**
     * THE GENERATOR. The committed XML is this function's output for the current census —
     * regenerating after a census edit means making the file equal this rendering again
     * (`theXmlIsByteForByteTheRenderingOfTheCensusNeverHandEdited` prints it on mismatch).
     */
    private fun renderedDeviceTargetingXml(): String {
        val sb = StringBuilder()
        sb.append("<config:device-targeting-config xmlns:config=\"http://schemas.android.com/apk/config\">\n")
        for (family in families) {
            sb.append("  <config:device-group name=\"${family.packGroup}\">\n")
            for (manufacturer in manufacturers) {
                sb.append("    <config:device-selector>\n")
                for (model in family.socModels) {
                    sb.append(
                        "      <config:system-on-chip manufacturer=\"$manufacturer\" " +
                            "model=\"$model\"/>\n"
                    )
                }
                sb.append("    </config:device-selector>\n")
            }
            sb.append("  </config:device-group>\n")
        }
        sb.append(
            "  <!-- everything else → default group \"other\" → the EMPTY variant. " +
                "Groups are pairwise\n"
        )
        sb.append("       disjoint by exact string, so XML order carries no priority weight here. -->\n")
        sb.append("</config:device-targeting-config>\n")
        return sb.toString()
    }

    // ------------------------------------------------------------------ the XML

    @Test
    fun theDeviceGroupsAreTheCensusSpelledForPlay() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(org.xml.sax.InputSource(StringReader(xml)))
        val ns = "http://schemas.android.com/apk/config"
        assertEquals(
            "the root element is the device-targeting config",
            "device-targeting-config", doc.documentElement.localName
        )
        val groups = doc.getElementsByTagNameNS(ns, "device-group")
        assertEquals(
            "the XML's group names are the census packGroups exactly, exhaustively, in " +
                "families order — this file GENERATES from the census, and a census edit " +
                "that forgets it ships a store and a gate that disagree about a device",
            families.map { it.packGroup },
            (0 until groups.length).map {
                (groups.item(it) as org.w3c.dom.Element).getAttribute("name")
            }
        )
        for ((index, family) in families.withIndex()) {
            val group = groups.item(index) as org.w3c.dom.Element
            val selectors = group.getElementsByTagNameNS(ns, "device-selector")
            assertEquals(
                "${family.packGroup} carries one selector per manufacturer spelling " +
                    "(selectors OR together; two is well under Play's 5-selector cap)",
                manufacturers.size, selectors.length
            )
            for ((mIndex, manufacturer) in manufacturers.withIndex()) {
                val selector = selectors.item(mIndex) as org.w3c.dom.Element
                val chips = selector.getElementsByTagNameNS(ns, "system-on-chip")
                assertEquals(
                    "${family.packGroup} selector $mIndex is the whole family under the " +
                        "'$manufacturer' spelling",
                    family.socModels.map { manufacturer to it },
                    (0 until chips.length).map {
                        val chip = chips.item(it) as org.w3c.dom.Element
                        chip.getAttribute("manufacturer") to chip.getAttribute("model")
                    }
                )
            }
        }
    }

    @Test
    fun theXmlIsByteForByteTheRenderingOfTheCensusNeverHandEdited() {
        assertEquals(
            "app/device_targeting_config.xml must equal the census rendering EXACTLY — it is " +
                "generated, never hand-edited. If this failed after a census edit, that is " +
                "maintenance rule 1 working: replace the file's content with the expected " +
                "text below, in the same commit as the census change",
            renderedDeviceTargetingXml(),
            xml
        )
    }

    @Test
    fun everyCensusStringAppearsUnderBothManufacturerSpellingsInItsOwnGroup() {
        for (family in families) {
            val start = xml.indexOf("name=\"${family.packGroup}\"")
            assertTrue("group ${family.packGroup} exists", start >= 0)
            val end = xml.indexOf("</config:device-group>", start)
            assertTrue("group ${family.packGroup} is closed", end > start)
            val block = xml.substring(start, end)
            for (model in family.socModels) {
                for (manufacturer in manufacturers) {
                    assertEquals(
                        "$model must appear exactly once under the '$manufacturer' spelling " +
                            "inside ${family.packGroup} — Play matches BOTH Build fields " +
                            "literally, and a missing spelling silently lands a capable " +
                            "device in the empty default",
                        1,
                        count(block, "manufacturer=\"$manufacturer\" model=\"$model\"/>")
                    )
                }
            }
        }
    }

    @Test
    fun nothingTheCensusDoesNotNameAppearsInTheXml() {
        val censusStrings = families.flatMap { it.socModels }
        val modelValues = Regex("model=\"([^\"]*)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(
            "every census string appears exactly twice (once per manufacturer spelling) and " +
                "the XML names NOTHING else — exact attribute values, never substrings, " +
                "because SM8750-AC contains SM8750",
            censusStrings.flatMap { s -> listOf(s, s) }.sorted(),
            modelValues.sorted()
        )
        // The superseded research-sketch strings, live-zero BY EXACT VALUE: the sketch's wider
        // groups listed the plain bins and SM7750-AB; the spec's census superseded it, and the
        // wider census must not creep back in through the store's copy.
        for (sketch in listOf("SM8750", "SM8850", "SM7750-AB")) {
            assertEquals(
                "'$sketch' is not a census string and must not be a Play string — widening " +
                    "is a census edit with evidence (which regenerates this file), never an " +
                    "XML edit",
                0, modelValues.count { it == sketch }
            )
        }
        assertEquals(
            "and the manufacturer spellings are the gate's own set, nothing else",
            NpuGate.SUPPORTED_SOC_MANUFACTURERS,
            Regex("manufacturer=\"([^\"]*)\"").findAll(xml).map { it.groupValues[1] }.toSet()
        )
    }

    // ------------------------------------------------------------------ the app wiring

    @Test
    fun theBundleBlockWiresTheXmlWithSplitEnabledAndTheOtherDefault() {
        assertEquals(
            "the bundle block carries the committed device-group XML into the AAB",
            1, count(appGradle, "deviceTargetingConfig = file(\"device_targeting_config.xml\")")
        )
        assertEquals(
            "per-group splits are on — one #group_ variant per device, not the union",
            1, count(appGradle, "enableSplit = true")
        )
        assertEquals(
            "unmatched devices land in the 'other' group and receive the EMPTY default " +
                "variant — the fallback the empty-default rule exists for",
            1, count(appGradle, "defaultGroup = \"other\"")
        )
    }

    @Test
    fun theAppListsExactlyTheTwoPackModules() {
        assertEquals(
            "the app declares exactly the two NPU packs, in one spelling — a pack missing " +
                "here ships no variants at all, silently",
            1, count(appGradle, "assetPacks += listOf(\":npu_turbo\", \":npu_small\")")
        )
        assertEquals(
            "and no second assetPacks statement exists to widen or shadow the list",
            1, count(appGradle, "assetPacks +=")
        )
    }

    @Test
    fun theTurboPackDeclaresItsExactNameAndOnDemandDelivery() {
        val pack = read("npu_turbo/build.gradle.kts")
        assertEquals(1, count(pack, "id(\"com.android.asset-pack\")"))
        assertEquals(
            "the pack name is the Play-side identity — fetch(), getPackLocation() and the " +
                "#group_ split names all key on it",
            1, count(pack, "packName.set(\"npu_turbo\")")
        )
        assertEquals(
            "on-demand is the spec's mode decision: ~860 MB downloads only after the gates " +
                "pass and the user opts in — install-time would force it on every matched " +
                "device and fast-follow would pull it behind their back",
            1, count(pack, "deliveryType.set(\"on-demand\")")
        )
    }

    @Test
    fun theSmallPackDeclaresItsExactNameAndOnDemandDelivery() {
        val pack = read("npu_small/build.gradle.kts")
        assertEquals(1, count(pack, "id(\"com.android.asset-pack\")"))
        assertEquals(1, count(pack, "packName.set(\"npu_small\")"))
        assertEquals(1, count(pack, "deliveryType.set(\"on-demand\")"))
    }

    @Test
    fun settingsIncludesBothPackModulesBesideTheApp() {
        val settings = read("settings.gradle.kts")
        assertEquals(
            "both pack modules are included in one statement",
            1, count(settings, "include(\":npu_turbo\", \":npu_small\")")
        )
        assertEquals("and the app is still there", 1, count(settings, "include(\":app\")"))
    }

    @Test
    fun theDeviceTargetingConfigApiFlagIsOn() {
        assertEquals(
            "the experimental flag device targeting requires on AGP 8.13 — without it the " +
                "bundle block's deviceTargetingConfig property does not exist and the XML " +
                "never enters the AAB",
            1, count(read("gradle.properties"), "android.experimental.enableDeviceTargetingConfigApi=true")
        )
    }

    // ------------------------------------------------------------------ the proprietary boundary

    @Test
    fun bothPackGitignoresKeepThePayloadStructurallyUncommittable() {
        for (module in moduleByTier.values) {
            assertEquals(
                "$module/.gitignore must ignore every payload variant dir — the vendor bins " +
                    "are BUILD artifacts assembled from the measured workspace, and the root " +
                    ".gitignore's blob walls (*.so, *.dlc) do not cover .bin, so this pattern " +
                    "IS the wall",
                1, count(read("$module/.gitignore"), "src/main/assets/$module#group_soc_*/")
            )
            // (4.2 F8) And it must stop at `soc_`. A `#group_*` wall would also swallow the
            // #group_other directory, whose .gitkeep is the one TRACKED file that proves the
            // empty default exists at all — the wall would quietly un-track the empty-default
            // rule's own evidence, and every test below it reads that directory from the repo.
            assertEquals(
                "$module/.gitignore must not carry a wall wide enough to hide #group_other",
                0, count(read("$module/.gitignore"), "src/main/assets/$module#group_*/")
            )
        }
    }

    @Test
    fun theDefaultVariantsCarryNothingButTheGitkeep() {
        for (module in moduleByTier.values) {
            // (4.2 F8) The default variant is the EXPLICIT `#group_other` directory. An
            // unsuffixed sibling of `#group_` dirs is not a fallback to bundletool, it is an
            // error: it assigns such a directory an empty DeviceGroupTargeting and refuses the
            // bundle by name ("Directory 'assets/npu_small' must have exactly one device group,
            // but found []"). `other` is bundletool's implicit group — never declared in
            // device_targeting_config.xml, named as defaultGroup in the bundle DSL — so naming
            // it here changes the spelling and not one device's delivery.
            val defaultDir = repoFile("$module/src/main/assets/$module#group_other")
            assertEquals(
                "$module's DEFAULT variant (assets/$module#group_other/) must contain exactly " +
                    ".gitkeep — an unmatched device can never be prevented from receiving the " +
                    "default, so the default must contain nothing worth receiving. " +
                    "verifyNpuPacks holds the same rule before every bundle build",
                listOf(".gitkeep"),
                (defaultDir.listFiles() ?: emptyArray()).map { it.name }.sorted()
            )
        }
    }

    @Test
    fun eachPacksVariantDirsAreNamedAfterThePackSoNoEntryPathCanClashAcrossModules() {
        // (4.2 F8) THE RULE THIS PINS, and the reason it is a rule: an AAB may not carry the
        // same entry path in two modules with different bytes. Both packs used to write
        // assets/model#group_<g>/metadata.json — one path, two documents — and the first
        // bundleRelease ever attempted died on exactly that, naming the 7gen4 metadata.json.
        // The two BINARIES were safe only by the turbo_ rename; a third shared filename would
        // have reintroduced the fault. Naming each pack's directory after the pack retires the
        // clash class instead of the instance, so this test pins the RULE (per-pack prefix,
        // and no two modules sharing a variant dir name) rather than the one file that broke.
        val dirNames = moduleByTier.values.map { module ->
            val assets = repoFile("$module/src/main/assets")
            val names = (assets.listFiles() ?: emptyArray()).map { it.name }.sorted()
            for (n in names) {
                assertTrue(
                    "$module's variant dir '$n' must be named after the pack — an entry path " +
                        "shared with the sibling module is refused at bundle time",
                    n.startsWith("$module#group_")
                )
            }
            assertEquals(
                "$module must carry the four census variants plus the empty #group_other",
                5, names.size
            )
            names.toSet()
        }
        assertEquals(
            "the two modules must share no variant directory name at all",
            emptySet<String>(), dirNames[0].intersect(dirNames[1])
        )
    }

    // ------------------------------------------------------------------ the bundle gate

    @Test
    fun verifyNpuPacksHoldsEveryVariantToTheCensusBytesAndGatesOnlyBundleBuilds() {
        assertEquals(1, count(appGradle, "tasks.register(\"verifyNpuPacks\")"))
        // The pack table: one row per variant, byte literals the census's own — restated in
        // the build script because it cannot read the app's classes, and pinned equal here
        // (the extractQnnSkel fleet-table discipline, one gate over).
        for (artifact in NpuFleetCensus.artifacts) {
            val module = moduleByTier.getValue(artifact.tierId)
            val group = requireNotNull(NpuFleetCensus.familyById(artifact.familyId)).packGroup
            assertEquals(
                "verifyNpuPacks carries the ${artifact.familyId}/${artifact.tierId} row's " +
                    "census byte counts, paired in one literal row",
                1,
                count(
                    appGradle,
                    "listOf(\"$module\", \"$group\", ${grouped(artifact.encoder.bytes)}L, " +
                        "${grouped(artifact.decoder.bytes)}L),"
                )
            )
        }
        // The delivery names, spelled once per module — the same catalog names the census
        // entries carry (turbo's renamed so no family's turbo pack can overwrite the npu pair).
        for (tierId in listOf("npu", "npu-turbo")) {
            val row = requireNotNull(NpuFleetCensus.artifactFor("8gen3", tierId))
            assertEquals(
                1,
                count(
                    appGradle,
                    "\"${moduleByTier.getValue(tierId)}\" to " +
                        "listOf(\"${row.encoder.fileName}\", \"${row.decoder.fileName}\"),"
                )
            )
        }
        // The empty-default check is part of the gate itself:
        assertEquals(
            "the gate refuses a default variant carrying anything beyond .gitkeep",
            1, count(appGradle, ".filter { it != \".gitkeep\" }")
        )
        // Wired before bundle PACKAGING only. assembleDebug must NOT demand 4.3 GB of payload
        // (an APK build carries no packs at all), so the one and only dependsOn is the
        // package*Bundle matching clause:
        assertEquals(
            1,
            count(
                appGradle,
                "tasks.matching { it.name.startsWith(\"package\") && " +
                    "it.name.endsWith(\"Bundle\") }\n" +
                    "    .configureEach { dependsOn(verifyNpuPacks) }"
            )
        )
        assertEquals(
            "and that clause is the ONLY wiring — preBuild/assemble never depend on the gate",
            1, count(appGradle, "dependsOn(verifyNpuPacks)")
        )
    }

    // ------------------------------------------------------------------ the pack builder

    @Test
    fun theBuildAndDeliveryModesWriteMetadataFirstWithDeclaredSizesAndReverifyTheirOutput() {
        // The script's family table pairs each HTP version with its Play group, so the pack
        // variants land under the census's own group dirs:
        for (family in families) {
            assertEquals(
                "build_asset_packs.py pairs ${family.id}'s HTP with its packGroup",
                1, count(script, "${family.htpVersion}, \"${family.packGroup}\"),")
            )
        }
        // The tier-to-module mapping, once each:
        assertEquals(1, count(script, "\"npu\": \"npu_small\""))
        assertEquals(1, count(script, "\"npu-turbo\": \"npu_turbo\""))
        // The payload path is constructed from the MODULE and the group, never hand-spelled per
        // family — and the module half is what keeps the two packs' entry paths disjoint (F8).
        assertEquals(1, count(script, "f\"{module}#group_{pack_group}\""))
        assertEquals(1, count(script, "f\"{module}#group_other\""))
        // The delivery zip writes OUR metadata.json FIRST — the import peek refuses a
        // wrong-family zip from its own declaration before a GB inflates — and the bins
        // AFTER it (source order pin over the two write calls):
        val metadataWrite = script.indexOf("zf.writestr(info, meta_text)")
        val binaryWrite = script.indexOf("zf.open(binfo, \"w\")")
        assertTrue("the delivery-zip writer writes metadata via writestr", metadataWrite >= 0)
        assertTrue("and streams the binaries through open(w)", binaryWrite >= 0)
        assertTrue(
            "metadata.json is written BEFORE either binary — ZipInputStream surfaces entries " +
                "in file order, so first-written is first-peeked",
            metadataWrite < binaryWrite
        )
        // The declared-size proof (the F3 review's M2 carry): the writer re-opens its own
        // output and refuses any entry written with a data descriptor — a local header
        // without sizes reports -1 through ZipEntry.getSize(), which silently skips the
        // peek and disarms classifyEntry's declared-size refusal:
        assertEquals(1, count(script, "flag_bits & 0x08"))
        // And both modes re-verify their own output through the importer's logic — exactly
        // three files, census bytes AND digests re-hashed from what LANDED, metadata
        // cross-checked against the census:
        assertEquals(
            "the variant self-verification exists and build calls it after writing",
            1, count(script, "def verify_variant_dir(")
        )
        assertTrue(
            "a failed self-verification is a named FATAL, not a warning",
            count(script, "built variant failed its own verification") == 1
        )
    }
}
