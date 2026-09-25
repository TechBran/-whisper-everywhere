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
 * as the exact selector strings, one selector per manufacturer spelling in THE FAMILY'S OWN
 * `manufacturers` (P2, the census reshape: it read the gate's one global
 * `NpuGate.SUPPORTED_SOC_MANUFACTURERS` until the manufacturer moved onto the row; every
 * Qualcomm row carries the same {QTI, Qualcomm} doctrine, so the rendering did not change by a
 * byte — the move's own proof. Play matches `Build.SOC_MANUFACTURER` literally too, and a
 * "Qualcomm"-spelled OEM build would otherwise pass the app gate and land in the empty default —
 * fail-safe, but exactly the lost-coverage trap the census exists to prevent). The committed file must equal the
 * rendering BYTE FOR BYTE, which is what makes maintenance rule 1 mechanical: a census edit
 * (a new suffix bin, a fifth family) fails this suite until the XML is regenerated in the
 * SAME commit, and the failure message prints the exact regenerated text to commit.
 *
 * ### Why Play's copy must be exact, not merely similar
 *
 * Two censuses is how a device passes one gate and fails the other, so the pins here are
 * exhaustive in BOTH directions: every census string under both spellings, and nothing the
 * census does not name — asserted over exact attribute values, never substrings, because
 * `SM8750-AC` contains `SM8750`. (A cautionary history: the 08-29 research sketch listed plain
 * `SM8750`/`SM8850`, and the spec "superseded" them with the `-AC`/`-AD` aliases — which no
 * device reports. The sketch was right. Both plain strings are census strings since 2026-09-22.)
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
 * ### Two layout rules since P2-5 — targeted and untargeted
 *
 * The Qualcomm pairs are `#group_` variants of the two TARGETED modules (`npu_small`,
 * `npu_turbo`), and everything above is their rule, unweakened. A MediaTek pair ships in two
 * UNTARGETED modules of its own family (`npu_turbo_mt6989_enc` / `_dec`): bundletool's
 * `DeviceGroupParityValidator` requires every group-targeted module to support the same set of
 * groups, so a MediaTek variant can never sit beside the Qualcomm modules' six. Each untargeted
 * module is one payload directory named after the pack — its part's entry and `metadata.json` in
 * part 1, and nothing else — with no group folder; its family renders into no XML group; and the
 * census gate alone decides who fetches it. The tests at the bottom hold that second rule. (P3a:
 * the tracked `.gitkeep` that anchored each payload directory is gone — the asset-pack plugin
 * zips `src/main/assets` whole, with no filter and no DSL to add one, so it shipped in the pack
 * as a zero-byte asset; [aDeliveredUntargetedPackHoldsExactlyItsPayloadFiles] holds its absence.)
 *
 * No JVM test can run a Gradle bundle build or call Play, so the build-side halves are
 * SOURCE pins (the L6 split); the census side executes. Every file this class reads is in
 * the test task's `sourcePinnedInputs`, or an edit confined to it would leave the suite
 * UP-TO-DATE and these pins would pass against stale evidence.
 */
class NpuPackLayoutTest {

    private val families = NpuFleetCensus.families

    /**
     * The families whose runtime is QNN, each with its QNN needs — the rows the build script's
     * vendor FAMILIES table describes (their HTP version is the script's pairing column). A row
     * of another vendor has no HTP version and no vendor zip, so it is no row of that table.
     */
    private val qnnFamilies: List<Pair<NpuSocFamily, NpuRuntimeNeeds.Qnn>> =
        families.mapNotNull { f -> (f.runtime as? NpuRuntimeNeeds.Qnn)?.let { f to it } }

    /** The Qualcomm rows — the families the `npu_small` / `npu_turbo` modules carry variants for. */
    private val qualcommFamilies: List<NpuSocFamily> = families.filter { it.vendor == NpuVendor.QUALCOMM }

    /** tier id -> the pack MODULE that ships it (the brief's two names; F5's PACK_BY_TIER
     *  will spell the same mapping through the tier-id homes). */
    private val moduleByTier = mapOf("npu" to "npu_small", "npu-turbo" to "npu_turbo")

    /**
     * The families the device-group XML RENDERS (P2-5): those whose every pair ships as a
     * `#group_` variant of the tier's device-targeted module — one part, `npu_small`/`npu_turbo`,
     * both entries. DERIVED from the census parts, not typed: a family whose pair ships in
     * untargeted modules of its own (the MediaTek rows, since bundletool's
     * `DeviceGroupParityValidator` refuses group-targeted modules whose group sets differ) is in no
     * group Play resolves, so its `packGroup` names the family in the census and its pack metadata
     * and renders into no XML. [theTargetedFamiliesAreTheQualcommRowsAndTheMediatekRowsShipUntargeted]
     * holds this set equal to the Qualcomm rows.
     */
    private val targetedFamilies: List<NpuSocFamily> = families.filter { f ->
        val pairs = NpuFleetCensus.artifacts.filter { it.familyId == f.id }
        pairs.isNotEmpty() && pairs.all { a ->
            a.parts == listOf(PackPart(moduleByTier.getValue(a.tierId), listOf(a.encoder, a.decoder)))
        }
    }

    /** The untargeted pack modules (P2-5) — every census part that is not a targeted module's. */
    private val untargetedParts: List<Pair<PackArtifact, PackPart>> =
        NpuFleetCensus.artifacts.flatMap { a -> a.parts.map { a to it } }
            .filter { (_, part) -> part.packName !in moduleByTier.values }

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
        // (P2-5) The device-TARGETED families only — see [targetedFamilies].
        for (family in targetedFamilies) {
            sb.append("  <config:device-group name=\"${family.packGroup}\">\n")
            for (manufacturer in family.manufacturers) {
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
            "the XML's group names are the device-targeted census packGroups exactly, " +
                "exhaustively, in families order — this file GENERATES from the census, and a " +
                "census edit that forgets it ships a store and a gate that disagree about a device",
            targetedFamilies.map { it.packGroup },
            (0 until groups.length).map {
                (groups.item(it) as org.w3c.dom.Element).getAttribute("name")
            }
        )
        for ((index, family) in targetedFamilies.withIndex()) {
            val group = groups.item(index) as org.w3c.dom.Element
            val selectors = group.getElementsByTagNameNS(ns, "device-selector")
            assertEquals(
                "${family.packGroup} carries one selector per spelling in ITS OWN manufacturers " +
                    "(selectors OR together; two is well under Play's 5-selector cap)",
                family.manufacturers.size, selectors.length
            )
            for ((mIndex, manufacturer) in family.manufacturers.withIndex()) {
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

    /**
     * RE-POINTED AT P2-5 (was `theMt6989GroupIsOneMediatekSelectorAndEveryQualcommGroupKeepsItsTwo`,
     * P2-3's pin on the soc_mt6989 group): THE MEDIATEK GROUP IS ABSENT, and must stay so. The
     * mt6989 pair ships in untargeted modules of its own — bundletool's DeviceGroupParityValidator
     * requires every module with device-group targeting to support the same set of groups, so the
     * MediaTek pair cannot be a #group_ variant beside the Qualcomm modules' six — and a group
     * nothing targets is one more unknown for the upload (Play's acceptance of a declared-but-unused
     * group is undocumented). So the XML names no MediaTek group, spelling or chip at all, and every
     * Qualcomm group keeps exactly its two selectors. Hard literals, for the reason the old pin had
     * them: a renderer drifting back to every family would pass every derived assertion above.
     */
    @Test
    fun theXmlCarriesNoMediatekGroupAndEveryQualcommGroupKeepsItsTwo() {
        assertEquals(
            "no soc_mt6989 group — the mt6989 family's packs are untargeted, so Play resolves no " +
                "group for it and the census gate alone decides who fetches them",
            0, count(xml, "soc_mt6989")
        )
        for (absent in listOf("manufacturer=\"Mediatek\"", "manufacturer=\"MediaTek\"", "MT6989")) {
            assertEquals("the XML names no <<$absent>>", 0, count(xml, absent))
        }
        assertEquals(
            "and the mt6989 row is still the census's, with its packGroup on the row (census and " +
                "pack metadata read it; the XML does not)",
            "soc_mt6989", requireNotNull(NpuFleetCensus.familyById("mt6989")).packGroup
        )
        for (family in qualcommFamilies) {
            val s = xml.indexOf("name=\"${family.packGroup}\"")
            val group = xml.substring(s, xml.indexOf("</config:device-group>", s))
            assertEquals("${family.packGroup} keeps exactly its two selectors", 2, count(group, "<config:device-selector>"))
            assertEquals(
                "${family.packGroup} names no MediaTek spelling",
                0, count(group, "manufacturer=\"Mediatek\"") + count(group, "manufacturer=\"MediaTek\"")
            )
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
    fun everyCensusStringAppearsUnderEachOfItsFamilysManufacturerSpellingsInItsOwnGroup() {
        // (P2-5) Every device-TARGETED family's strings — an untargeted family has no group.
        for (family in targetedFamilies) {
            val start = xml.indexOf("name=\"${family.packGroup}\"")
            assertTrue("group ${family.packGroup} exists", start >= 0)
            val end = xml.indexOf("</config:device-group>", start)
            assertTrue("group ${family.packGroup} is closed", end > start)
            val block = xml.substring(start, end)
            for (model in family.socModels) {
                for (manufacturer in family.manufacturers) {
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
        // Each string once per spelling ITS family admits (P2: per row, where it was "twice"
        // against one global Qualcomm pair — the same count on every Qualcomm row). (P2-5) Over
        // the device-targeted families: an untargeted family's strings belong in no group.
        val censusStrings = targetedFamilies.flatMap { f -> f.socModels.flatMap { s -> f.manufacturers.map { s } } }
        val modelValues = Regex("model=\"([^\"]*)\"").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(
            "every census string appears exactly once per manufacturer spelling its family admits " +
                "and the XML names NOTHING else — exact attribute values, never substrings, " +
                "because SM8750-AC contains SM8750",
            censusStrings.sorted(),
            modelValues.sorted()
        )
        // The research sketch's one string still outside the census, live-zero BY EXACT VALUE:
        // SM7750-AB is a part number, not a string any device reports. (The sketch's plain
        // SM8750/SM8850 were on this list until 2026-09-22 — pinned OUT, and they were the
        // strings the Galaxy S25/S26 report. They are census strings now; see the KDoc.)
        for (sketch in listOf("SM7750-AB")) {
            assertEquals(
                "'$sketch' is not a census string and must not be a Play string — widening " +
                    "is a census edit with evidence (which regenerates this file), never an " +
                    "XML edit",
                0, modelValues.count { it == sketch }
            )
        }
        // RE-POINTED AT P2-5: this compared against NpuGate.SUPPORTED_SOC_MANUFACTURERS, the
        // gate's union of EVERY row's spellings — which since the mt6989 row carries Mediatek, a
        // spelling the XML must not name now its family is untargeted. The XML's spellings are
        // the rendered families' own, and nothing else.
        assertEquals(
            "and the manufacturer spellings are the device-targeted families' own, nothing else",
            targetedFamilies.flatMap { it.manufacturers }.toSet(),
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
    fun theAppListsBothNpuPackModulesFirstInItsOnePackList() {
        assertEquals(
            "the app declares both NPU packs, in one spelling, at the head of the ONE pack " +
                "list — a pack missing here ships no variants at all, silently. (4.4.0 added " +
                "the untargeted :preview_en behind them; PreviewPackLayoutTest owns that half " +
                "and this pin owns the NPU pair's, so neither can be dropped by editing the " +
                "other's test.)",
            1, count(appGradle, "assetPacks += listOf(\":npu_turbo\", \":npu_small\",")
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
            // (P2-3) VENDOR-SCOPED: these two modules are the QUALCOMM packs (design §2.7 —
            // `npu_turbo` stays Qualcomm-only; a MediaTek family's pair ships in its own two
            // UNTARGETED modules, npu_turbo_mt6989_enc/_dec since P2-5, held by the untargeted
            // tests below). So the variants here are the Qualcomm rows' groups, and soc_mt6989 is
            // in neither module.
            assertEquals(
                "$module must carry the SIX Qualcomm census variants plus the empty #group_other " +
                    "— four until 2026-09-22, when the 8 Gen 2 became a family on device evidence; " +
                    "five until 2026-09-24, when v0.63.0 published the 8 Gen 1's package",
                qualcommFamilies.size + 1, names.size
            )
            assertEquals(
                "...and they are exactly the Qualcomm rows' groups under this module's prefix, " +
                    "plus #group_other — a count alone would pass with one family's dir renamed",
                (qualcommFamilies.map { "$module#group_${it.packGroup}" } + "$module#group_other").sorted(),
                names
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
        // (the extractQnnSkel fleet-table discipline, one gate over). (P2-3) Over the pairs this
        // gate verifies — the Qualcomm ones, one part each in npu_small/npu_turbo; the mt6989
        // pair's two parts join verifyNpuPacks with their modules at P2-5.
        val qualcommIds = qualcommFamilies.map { it.id }.toSet()
        for (artifact in NpuFleetCensus.artifacts.filter { it.familyId in qualcommIds }) {
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
        // variants land under the census's own group dirs. Since 2026-09-24 each tuple carries
        // a fourth column after the group, the QNN soc_model metadata_gate holds the vendor's
        // chipset_attributes to. The census has no soc_model field to compare it with, so what
        // is pinned here is the pairing plus the column's SHAPE: one integer, on the pairing's
        // own line, closing the tuple. (P2: the HTP version is the row's QNN needs now, so the
        // loop is over the QNN rows — the vendor families that table exists to describe. The
        // census test holds every Qualcomm row to QNN needs, so no Qualcomm row can leave it.)
        assertTrue("the QNN rows were found", qnnFamilies.isNotEmpty())
        for ((family, qnn) in qnnFamilies) {
            assertEquals(
                "build_asset_packs.py pairs ${family.id}'s HTP with its packGroup, then its " +
                    "soc_model integer",
                1,
                Regex("""\b${qnn.htpVersion}, "${family.packGroup}", \d+\),""")
                    .findAll(script).count()
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

    // ------------------------------------------------------------------ the untargeted modules (P2-5)

    @Test
    fun theTargetedFamiliesAreTheQualcommRowsAndTheMediatekRowsShipUntargeted() {
        assertEquals(
            "the families the XML renders are exactly the Qualcomm rows — the ones whose pairs are " +
                "#group_ variants of npu_small/npu_turbo",
            qualcommFamilies.map { it.id },
            targetedFamilies.map { it.id }
        )
        for (f in families.filter { it.vendor == NpuVendor.MEDIATEK }) {
            for (a in NpuFleetCensus.artifacts.filter { it.familyId == f.id }) {
                for (part in a.parts) {
                    assertTrue(
                        "${f.id}/${a.tierId}: part ${part.packName} is none of the device-targeted " +
                            "modules — bundletool's DeviceGroupParityValidator would refuse a " +
                            "MediaTek variant beside the Qualcomm modules' groups",
                        part.packName !in moduleByTier.values
                    )
                    assertTrue(
                        "${f.id}/${a.tierId}: and it is named for its FAMILY — an untargeted module " +
                            "cannot hold a per-family variant, so each family has modules of its own",
                        part.packName.contains(f.id)
                    )
                }
            }
        }
    }

    @Test
    fun theMt6989PartsAreTheTwoUntargetedModulesInPartOrder() {
        assertEquals(
            "the census's untargeted parts are the mt6989 pair's two, encoder first",
            listOf("npu_turbo_mt6989_enc", "npu_turbo_mt6989_dec"),
            untargetedParts.map { (_, part) -> part.packName }
        )
    }

    @Test
    fun eachUntargetedModuleDeclaresItsExactNameAndOnDemandDelivery() {
        for ((_, part) in untargetedParts) {
            val module = part.packName
            val pack = read("$module/build.gradle.kts")
            assertEquals("$module is an asset pack", 1, count(pack, "id(\"com.android.asset-pack\")"))
            assertEquals(
                "$module's pack name is the census part's — fetch(), getPackLocation() and the " +
                    "delivered directory all key on it",
                1, count(pack, "packName.set(\"$module\")")
            )
            assertEquals(
                "$module is on-demand: 1.3 GB and 585 MB move only after the gates pass and the " +
                    "user opts in",
                1, count(pack, "deliveryType.set(\"on-demand\")")
            )
            assertEquals(
                "and it carries no device-group anything — the build file never mentions #group_",
                0, count(pack.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n"), "#group_")
            )
        }
    }

    @Test
    fun settingsIncludesTheUntargetedModulesInTheirOwnStatementAndTheAppListsThem() {
        val settings = read("settings.gradle.kts")
        assertEquals(
            "the MediaTek pair's modules are included in ONE statement of their own — the " +
                "Qualcomm pair's line (pinned above) stays exactly what it was",
            1,
            count(settings, "include(\":npu_turbo_mt6989_enc\", \":npu_turbo_mt6989_dec\")")
        )
        for ((_, part) in untargetedParts) {
            assertEquals(
                "${part.packName} is named exactly once in the app's ONE assetPacks list — a pack " +
                    "missing there ships nothing at all, silently",
                1, count(appGradle, "\":${part.packName}\"")
            )
        }
        assertEquals(
            "…by a concatenated list of their own, so the prefix pins keep their teeth",
            1, count(appGradle, "+ listOf(\":npu_turbo_mt6989_enc\", \":npu_turbo_mt6989_dec\")")
        )
        assertEquals("and still one assetPacks statement", 1, count(appGradle, "assetPacks +="))
    }

    @Test
    fun eachUntargetedModuleIsOnePayloadDirectoryWithTheAnchorAndNoGroupFolder() {
        // THE UNTARGETED LAYOUT RULE: src/main/assets/ holds ONE directory, named after the pack
        // (the 4.2 F8 rule — no entry path can clash across modules — and exactly what
        // deliveredEntryDirs reads on the device). Since P3a it is a build artifact end to end —
        // placed by build-local, with no tracked anchor in it (see below). No #group_ folder of
        // any kind: that is what keeps bundletool's DeviceGroupParityValidator from counting
        // these modules at all.
        for ((_, part) in untargetedParts) {
            val module = part.packName
            val assets = repoFile("$module/src/main/assets")
            assertEquals(
                "$module/src/main/assets holds exactly its one payload directory",
                listOf(module),
                (assets.listFiles() ?: emptyArray()).map { it.name }.sorted()
            )
            val names = (File(assets, module).listFiles() ?: emptyArray()).map { it.name }
            // RE-SPECCED AT P3a (the brief's "small"): this asserted the tracked .gitkeep anchor
            // PRESENT. The asset-pack plugin zips src/main/assets whole — no filter, no DSL to add
            // one — so that anchor shipped in the AAB as a zero-byte asset (the packaging probe of
            // 2026-09-25, sheet §8). It left the packaged tree; this now guards its ABSENCE, and
            // aDeliveredUntargetedPackHoldsExactlyItsPayloadFiles holds the whole listing.
            assertTrue(
                "the payload directory carries no .gitkeep anchor — everything in src/main/assets " +
                    "ships in the pack: $names",
                ".gitkeep" !in names
            )
            assertTrue(
                "and nothing in it looks like a group variant: $names",
                names.none { it.contains("#group_") }
            )
        }
    }

    @Test
    fun eachUntargetedGitignoreWallsThePayloadAndKeepsTheAnchor() {
        for ((_, part) in untargetedParts) {
            val module = part.packName
            val lines = read("$module/.gitignore").lines().map { it.trim() }
            // RE-SPECCED AT P3a: this pinned `src/main/assets/$module/*` plus the anchor's
            // re-include, `!src/main/assets/$module/.gitkeep`. The anchor left the packaged tree
            // (the asset-pack plugin zips src/main/assets whole, so it shipped as a zero-byte
            // asset — sheet §8), so the wall is the whole tree and nothing in it is re-included.
            assertEquals(
                "$module/.gitignore walls the WHOLE packaged tree with the LINE `src/main/assets/` — " +
                    "NeuroPilot bytecode must never enter a repo with a public remote, the root " +
                    "walls do not cover .bin, and every file under that tree ships in the pack",
                1, lines.count { it == "src/main/assets/" }
            )
            assertEquals(
                "…and re-includes NOTHING: a `!` line would make a file in the packaged tree " +
                    "committable, and whatever is committed there is delivered to every device " +
                    "that fetches the pack",
                0, lines.count { it.startsWith("!") }
            )
        }
    }

    @Test
    fun verifyNpuPacksHoldsEveryUntargetedPartToTheCensusAsASecondRule() {
        for ((artifact, part) in untargetedParts) {
            val entry = part.entries.single()
            val first = artifact.parts.first() == part
            assertEquals(
                "verifyNpuPacks carries ${part.packName}'s part row — module, family, the one entry, " +
                    "its census bytes, and whether metadata.json rides in it (part 1 only)",
                1,
                count(
                    appGradle,
                    "listOf(\"${part.packName}\", \"${artifact.familyId}\", \"${entry.fileName}\", " +
                        "${grouped(entry.bytes)}L, $first),"
                )
            )
        }
        assertEquals("one parts table", 1, count(appGradle, "val npuPackPartRows = listOf("))
        assertEquals(
            "and the gate walks it — the untargeted rule runs in the same task as the targeted one, " +
                "before every bundle packaging task",
            1, count(appGradle, "for (row in npuPackPartRows) {")
        )
        // RE-SPECCED AT P3a: the expected listing carried the anchor (`listOf(name, ".gitkeep")`).
        // The anchor left the packaged tree, so the gate's listing is the delivered pack's.
        assertEquals(
            "the untargeted part is exactly its entry (+ metadata.json in part 1) — no anchor, " +
                "because whatever the payload directory holds ships in the pack",
            1,
            count(
                appGradle,
                "val expected = (listOf(name) +\n" +
                    "                if (carriesMetadata) listOf(\"metadata.json\") else emptyList()).sorted()"
            )
        )
        assertEquals(
            "…and the anchor's spelling is gone from the untargeted rule",
            0, count(appGradle, "listOf(name, \".gitkeep\")")
        )
        assertEquals(
            "…in the ONE payload directory, with nothing beside it",
            1, count(appGradle, "an untargeted module carries assets/\$module/ and nothing")
        )
        // The Qualcomm rule is not weakened: its table, its three-file check and its empty-default
        // rule are pinned above by the tests that always held them, and the untargeted rows are
        // not in npuPackCensusRows at all.
        assertEquals(
            "no untargeted module is a row of the targeted table",
            0,
            untargetedParts.sumOf { (_, part) -> count(appGradle, "listOf(\"${part.packName}\", \"soc_") }
        )
    }

    /**
     * A DELIVERED UNTARGETED PACK HOLDS EXACTLY ITS PAYLOAD FILES (P3a).
     *
     * The packaging probe of 2026-09-25 (`docs/measurements/2026-09-24-tab-apu-turbo-encoder.md`
     * §8) opened the bundle and found each mt6989 module carrying `.gitkeep` beside its payload —
     * a zero-byte asset, harmless to the installer (which reads its entries by name), and a file
     * nobody asked Play to deliver. The cause is AGP's, and it is why the fix is a MOVE rather
     * than a filter: the asset-pack plugin publishes `src/main/assets` whole (`AssetPackPlugin`
     * adds that directory as the pack's artifact, and `AssetPackPreBundleTaskRunnable` zips it with
     * no filter), and `assetPack { }` has no `androidResources`/`aaptOptions` to add one —
     * `AssetPackExtension` is `packName` + `dynamicDelivery` (AGP 8.13.2, read out of the jars).
     * So the tree the plugin zips IS the delivered pack, and this walks all of it: every file
     * under `<module>/src/main/assets/`, recursively, is one of the part's payload files at
     * `<module>/<name>` — the part's entry, and `metadata.json` in part 1 — and every payload file
     * is there. The tree is a build artifact (build-local places it; the module's `.gitignore`
     * walls it whole), read from this checkout's placed payload like the targeted rule above.
     */
    @Test
    fun aDeliveredUntargetedPackHoldsExactlyItsPayloadFiles() {
        for ((artifact, part) in untargetedParts) {
            val module = part.packName
            val tree = File(repoFile(module), "src/main/assets")
            assertTrue(
                "$module/src/main/assets is not placed. Since P3a it is a build artifact end to end " +
                    "(no tracked anchor): place it with `python tools/build_asset_packs.py build-local`",
                tree.isDirectory
            )
            val shipped = tree.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(tree).path.replace('\\', '/') }
                .sorted()
                .toList()
            val payload = (part.entries.map { "$module/${it.fileName}" } +
                if (artifact.parts.first() == part) listOf("$module/${NpuPackMetadata.ENTRY_NAME}") else emptyList())
                .sorted()
            assertEquals(
                "$module's packaged tree — what the asset-pack plugin zips into the pack, whole — is " +
                    "exactly the part's payload files: no anchor, no stray",
                payload, shipped
            )
        }
    }

    // ------------------------------------------------------------------ the LOCAL source (P2-5)

    /** The census's MediaTek (LOCAL) rows with their LiteRT needs. */
    private val localFamilies: List<Pair<NpuSocFamily, NpuRuntimeNeeds.LiteRtMediatek>> =
        families.mapNotNull { f -> (f.runtime as? NpuRuntimeNeeds.LiteRtMediatek)?.let { f to it } }

    @Test
    fun theScriptsLocalTableIsTheCensusRowAndItsPartsInOrder() {
        assertTrue("the census has LOCAL rows for the script to fill", localFamilies.isNotEmpty())
        for ((family, needs) in localFamilies) {
            assertEquals(
                "LOCAL_FAMILIES carries ${family.id}'s pack group — the one its metadata names",
                1, count(script, "\"pack_group\": \"${family.packGroup}\",")
            )
            assertEquals(
                "…the stamp its files' own LiteRtStamp must carry — the vendor LiteRT writes and " +
                    "the row's socStamp, the chip the engine's init checks",
                1, count(script, "\"stamp\": (\"MediaTek\", \"${needs.socStamp}\"),")
            )
            assertEquals(
                "…and the Neuron major the bytecode's compiler must be, the row's own",
                1, count(script, "\"neuron_major\": ${needs.neuronMajor},")
            )
            for (a in NpuFleetCensus.artifacts.filter { it.familyId == family.id }) {
                val tuples = a.parts.map { part ->
                    "(\"${part.packName}\", \"${part.entries.single().fileName}\", \""
                }
                for (tuple in tuples) {
                    assertEquals("the script's part row <<$tuple>>, exactly once", 1, count(script, tuple))
                }
                assertTrue(
                    "…in the census's part order — part 1 (metadata.json's) first",
                    tuples.map { script.indexOf(it) }.zipWithNext().all { (x, y) -> x < y }
                )
                // The source names the script copies from are the compile's own, the ones the
                // row's provenance record names.
                for (source in Regex("\"(turbo_[a-z]+_[a-z0-9_]+_MediaTek_MT6989_apply_plugin\\.tflite)\"")
                    .findAll(script).map { it.groupValues[1] }.toSet()) {
                    assertTrue("the provenance record names $source", a.evidence.contains(source))
                }
            }
        }
        // The compiler string is READ out of each file (the bytecode's own {"Compiler": ...}
        // trailer) and held to this literal before a byte is copied; its major is the family's.
        assertEquals(1, count(script, "\"compiler\": \"adapter 8.2.30\","))
        assertEquals(
            "…and 'adapter 8.2.30' is Neuron major 8, the mt6989 row's",
            8, requireNotNull(localFamilies.firstOrNull { it.first.id == "mt6989" }).second.neuronMajor
        )
    }

    @Test
    fun theScriptsLocalIoSpecIsNpuModelSpecTurbo() {
        val turbo = NpuModelSpec.TURBO
        for ((key, value) in listOf(
            "mel_bins" to turbo.melBins, "mel_frames" to turbo.melFrames,
            "dec_layers" to turbo.decLayers, "heads" to turbo.heads, "head_dim" to turbo.headDim,
            "audio_ctx" to turbo.audioCtx, "vocab" to turbo.tokens.vocab,
            "max_positions" to turbo.maxPositions,
        )) {
            assertEquals(
                "LOCAL_IO_SPEC's $key is NpuModelSpec.TURBO's ($value) — the IO gate refuses at " +
                    "build time exactly what derivePairCensus would refuse at init",
                1, count(script, "\"$key\": $value")
            )
        }
        assertEquals("one spec row, turbo's", 1, count(script, "LOCAL_IO_SPEC = {\n    \"npu-turbo\": {"))
    }

    @Test
    fun theLocalSourceGatesEveryFileWritesVersionTwoAndSharesTheOneVerification() {
        for (gate in listOf(
            "sums = local_sums(src_dir)", "stamp = head.litert_stamp()",
            "compiler = head.bytecode_compiler()", "ins, outs = io_gate(tier, head, key)",
        )) {
            assertEquals("local_gate runs <<$gate>> before any copy", 1, count(script, gate))
        }
        assertTrue(
            "…and every gate runs BEFORE the copy",
            script.indexOf("ins, outs = io_gate(tier, head, key)") <
                script.indexOf("stream_pinned(open(source, \"rb\")")
        )
        assertEquals("the copy streams through the pinned-digest helper", 1, count(script, "stream_pinned(open(source, \"rb\")"))
        assertEquals(
            "into the UNTARGETED module's one payload directory",
            1, count(script, "out_dir = untargeted_payload_dir(module)")
        )
        assertEquals(
            "version 2 is written for LOCAL rows, version 1 stays the vendor rows' — one of each",
            listOf(1, 1), listOf(count(script, "\"version\": 2,"), count(script, "\"version\": 1,"))
        )
        for (field in listOf(
            "\"neuronMajor\": local[\"neuron_major\"],", "\"socStamp\": local[\"stamp\"][1],",
            "\"compiler\": local[\"compiler\"],",
        )) {
            assertEquals("the version-2 writer's <<$field>>", 1, count(script, field))
        }
        assertEquals(
            "both builds end in the ONE shared finish (metadata into part 0, then the verification " +
                "whose failure is the one named FATAL)",
            listOf(1, 1),
            listOf(
                count(script, "finish_variant(tier, family, out_dir)\n"),
                count(script, "finish_variant(tier, family, out_dir, index)"),
            )
        )
        assertEquals("build fills the LOCAL parts too", 1, count(script, "build_local(local_root)"))
        assertEquals(
            "and build-local has its dry run: every head-and-tail gate, a listing, nothing written",
            1, count(script, "dry_run = \"--dry-run\" in rest")
        )
    }
}
