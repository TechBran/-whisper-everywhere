package com.whispereverywhere.npu

import com.whispereverywhere.model.ModelTierCopy
import com.whispereverywhere.model.WhisperCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The census AS DATA: every value pinned to the measured table the 2026-08-29 plan bound
 * (research-verified soc strings and HTP versions; skel rows measured out of
 * `qnn-runtime-2.49.0.aar`; the 8gen3 row equal to the 4.1-shipped pins). A mismatch here is a
 * census edit nobody measured — exactly the drift these pins exist to make loud.
 *
 * WHY EVERY ROW IS RESTATED: the census is the ONE home downstream tasks read (the gate's derived
 * set, F2's skel stage, F3's artifact verify, F4's device-group XML). A wrong value would flow to
 * all of them consistently — consistent, and consistently wrong on real silicon, where a skel or
 * context binary on the wrong HTP fails at FastRPC depth. The JVM cannot execute that failure, so
 * the values are held here, against the measurement record, the way the 4.1 catalog pins are.
 */
class NpuFleetCensusTest {

    private val families = NpuFleetCensus.families
    private val artifacts = NpuFleetCensus.artifacts

    private fun byId(id: String): NpuSocFamily =
        requireNotNull(NpuFleetCensus.familyById(id)) { "census must carry family $id" }

    private fun artifact(familyId: String, tierId: String): PackArtifact =
        requireNotNull(NpuFleetCensus.artifactFor(familyId, tierId)) {
            "census must carry the $familyId/$tierId artifact"
        }

    /** The house locator, so the script cross-pin finds the repo root from any test cwd. */
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

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun lines(vararg text: String) = text.joinToString("\n")

    /** 293598974 -> "293_598_974", the underscore grouping every literal in the script uses. */
    private fun grouped(n: Long): String =
        n.toString().reversed().chunked(3).joinToString("_").reversed()

    @Test
    fun theCensusHasExactlyTheFourPublishedFamiliesInTableOrder() {
        assertEquals(
            "four families have published w8a16 packages (release manifests re-fetched " +
                "2026-08-29) — a fifth row is a vendor event with evidence, a dropped row is " +
                "lost coverage nothing reports",
            listOf("8gen3", "8elite_galaxy", "8elite5_galaxy", "7gen4"),
            families.map { it.id }
        )
        assertEquals(
            "and each family's Play device group carries the census id under the soc_ prefix — " +
                "F4 regenerates the device-group XML from THESE strings, so a drift here is a " +
                "store/gate disagreement",
            listOf("soc_8gen3", "soc_8elite_galaxy", "soc_8elite5_galaxy", "soc_7gen4"),
            families.map { it.packGroup }
        )
    }

    @Test
    fun familyIdsPackGroupsAndSkelAssetsAreAllDistinct() {
        assertEquals(
            "duplicate family ids would make familyById ambiguous",
            families.size, families.map { it.id }.toSet().size
        )
        assertEquals(
            "duplicate pack groups would collapse two pack variants into one bundle directory",
            families.size, families.map { it.packGroup }.toSet().size
        )
        assertEquals(
            "duplicate skel assets would stage one family's skel under another family's silicon",
            families.size, families.map { it.skelAsset }.toSet().size
        )
    }

    @Test
    fun theHtpVersionsAreTheFourMeasuredArchitecturesOnTheRightRows() {
        assertEquals(
            "the four published architectures, nothing else",
            setOf(73, 75, 79, 81),
            families.map { it.htpVersion }.toSet()
        )
        // Per-row as well, because two rows SWAPPING versions keeps the set equal while every
        // context binary lands on the wrong Hexagon:
        assertEquals("the 8 Gen 3 is HTP v75", 75, byId("8gen3").htpVersion)
        assertEquals("the 8 Elite for Galaxy is HTP v79", 79, byId("8elite_galaxy").htpVersion)
        assertEquals("the 8 Elite Gen 5 for Galaxy is HTP v81", 81, byId("8elite5_galaxy").htpVersion)
        assertEquals(
            "the 7 Gen 4 is HTP v73 — the oldest arch on the newest part, which is why nothing " +
                "orders these",
            73, byId("7gen4").htpVersion
        )
    }

    @Test
    fun theSocModelSetsAreExactAndPairwiseDisjoint() {
        assertEquals(
            "the 8 Gen 3's two measured strings — the 4.0 allowlist, absorbed as a census row",
            setOf("SM8650", "SM8650-AC"), byId("8gen3").socModels
        )
        assertEquals(
            "the 8 Elite row is the Galaxy bin ONLY — plain SM8750 belongs to CPU_BY_CENSUS, " +
                "and the suffix is the entire difference",
            setOf("SM8750-AC"), byId("8elite_galaxy").socModels
        )
        assertEquals(
            "the 8 Elite Gen 5 row is the Galaxy bin ONLY, same shape one generation on",
            setOf("SM8850-AD"), byId("8elite5_galaxy").socModels
        )
        assertEquals(
            "the 7 Gen 4 ships suffix-free — one string until a device proves another",
            setOf("SM7750"), byId("7gen4").socModels
        )
        for (a in families) {
            for (b in families) {
                if (a !== b) {
                    assertTrue(
                        "families ${a.id} and ${b.id} must not share a soc string — familyFor's " +
                            "first match would silently shadow the later row and stage the wrong " +
                            "family's skel",
                        (a.socModels intersect b.socModels).isEmpty()
                    )
                }
            }
        }
    }

    @Test
    fun everySkelSha256IsSixtyFourLowercaseHexAndAllFourDistinct() {
        val hex = Regex("^[0-9a-f]{64}$")
        for (f in families) {
            assertTrue(
                "family ${f.id}'s skelSha256 must be 64 lowercase hex characters — got " +
                    "\"${f.skelSha256}\"; anything else is a placeholder that would refuse " +
                    "every stage",
                hex.matches(f.skelSha256)
            )
        }
        assertEquals(
            "four architectures, four DISTINCT digests — a duplicate is a copy-paste, " +
                "not a measurement",
            4, families.map { it.skelSha256 }.toSet().size
        )
    }

    @Test
    fun theEightGenThreeSkelRowIsTheShippedFourOnePinExactly() {
        // The continuity pin: 4.1 L6 shipped exactly these two values (build task assert + arm
        // stage assert + drift pin). If the census's copy moved, the fleet table is not a second
        // reading of the shipped mechanism — it is a new source, unmeasured.
        val row = byId("8gen3")
        assertEquals("libQnnHtpV75Skel.so", row.skelAsset)
        assertEquals(17_913_608L, row.skelBytes)
        assertEquals(
            "a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c",
            row.skelSha256
        )
    }

    @Test
    fun theOtherThreeSkelRowsCarryTheMeasuredAarValues() {
        // Measured out of qnn-runtime-2.49.0.aar (jni/arm64-v8a/) on 2026-08-29 — the plan's
        // table. F2's extract task asserts the same pairs at build time; these are the census's
        // copies, and the two spellings meeting IS the check.
        val v73 = byId("7gen4")
        assertEquals("libQnnHtpV73Skel.so", v73.skelAsset)
        assertEquals(17_909_588L, v73.skelBytes)
        assertEquals(
            "7be4f8a4ec21a9d8d51f59c73094154f42d2f8fc91cfaadaef03441b77d7ddb1",
            v73.skelSha256
        )
        val v79 = byId("8elite_galaxy")
        assertEquals("libQnnHtpV79Skel.so", v79.skelAsset)
        assertEquals(17_721_548L, v79.skelBytes)
        assertEquals(
            "9cad65a621d154e5282ea9d2849d0a8838932ed91dc7e2514db4e992e2d933c6",
            v79.skelSha256
        )
        val v81 = byId("8elite5_galaxy")
        assertEquals("libQnnHtpV81Skel.so", v81.skelAsset)
        assertEquals(18_844_384L, v81.skelBytes)
        assertEquals(
            "b3453265c4574c69bb446bcb98dda117ded531b86b2307e0f02c595050fab8b1",
            v81.skelSha256
        )
    }

    @Test
    fun everyEvidenceLineCarriesARecordedDate() {
        for (f in families) {
            assertTrue(
                "family ${f.id}'s evidence must carry a recorded 2026-08-2x date — a date was " +
                    "recorded, not a vibe. Got: \"${f.evidence}\"",
                f.evidence.contains("2026-08-2")
            )
        }
        for ((soc, line) in NpuFleetCensus.CPU_BY_CENSUS) {
            assertTrue(
                "the CPU ledger's $soc line must carry the re-fetch date — an absence claim " +
                    "without a date is an absence nobody checked. Got: \"$line\"",
                line.contains("2026-08-29")
            )
        }
    }

    @Test
    fun theCpuLedgerNamesTheSixAbsentPartsAndStaysDisjointFromTheCensus() {
        assertEquals(
            "seven checked-absent strings for six absent parts (the 8 Gen 2 has two bins) — " +
                "8 Gen 2, 8+ Gen 1, 8 Gen 1, 888, and the two non-Galaxy Elite plains",
            setOf("SM8550", "SM8550-AC", "SM8475", "SM8450", "SM8350", "SM8750", "SM8850"),
            NpuFleetCensus.CPU_BY_CENSUS.keys
        )
        assertEquals(
            "the named example line, verbatim — the ledger's format contract: part, the absence, " +
                "the date, the method",
            "8 Gen 2 — no published w8a16 package as of 2026-08-29 " +
                "(both release manifests re-fetched)",
            NpuFleetCensus.CPU_BY_CENSUS["SM8550"]
        )
        val censusStrings = families.flatMap { it.socModels }.toSet()
        for (key in NpuFleetCensus.CPU_BY_CENSUS.keys) {
            assertTrue(
                "$key sits in the CPU ledger AND in a family's socModels — the census's two " +
                    "halves contradict each other about a device",
                key !in censusStrings
            )
        }
    }

    @Test
    fun familyByIdResolvesEveryRowAndAnswersNullOffTheCensus() {
        for (f in families) {
            assertSame(
                "familyById(${f.id}) must answer the row object itself — downstream holds " +
                    "row identity, not row copies",
                f, NpuFleetCensus.familyById(f.id)
            )
        }
        assertNull("8gen2 is not a census family", NpuFleetCensus.familyById("8gen2"))
        assertNull("soc strings are not family ids", NpuFleetCensus.familyById("SM8650"))
        assertNull("the empty string is not a family id", NpuFleetCensus.familyById(""))
        assertNull("exact matching here too — no case folding", NpuFleetCensus.familyById("8GEN3"))
    }

    @Test
    fun everySkelAssetNamesItsOwnFamilysHtpArchitecture() {
        for (f in families) {
            assertEquals(
                "family ${f.id} (HTP v${f.htpVersion}) must stage the skel of its OWN " +
                    "architecture — a mismatch stages a skel FastRPC cannot pair with the " +
                    "family's context binaries, and that failure is a device mystery, not a " +
                    "compile error",
                "libQnnHtpV${f.htpVersion}Skel.so",
                f.skelAsset
            )
        }
    }

    // ------------------------------------------------------------------ the artifact census (F3)

    @Test
    fun theArtifactCensusHasEightRowsFamilyMajorInTableOrderUnderTheCatalogsNames() {
        assertEquals(
            "eight measured pairs: 4 families x 2 tiers, family-major in families order, " +
                "npu before npu-turbo — a missing row is a family that cannot verify an " +
                "arrival, a surplus row is a measurement nobody made",
            families.flatMap { f -> listOf(f.id to "npu", f.id to "npu-turbo") },
            artifacts.map { it.familyId to it.tierId }
        )
        assertEquals(
            "and the artifact tier ids ARE the catalog's paired tiers — the census spells " +
                "them as literals (forcing the catalog's <clinit> from the census's would " +
                "re-open the initialization-order caution), so this equality is the pin " +
                "that keeps the two spellings one fact",
            NpuAssetImport.PAIRED_TIER_IDS.toSet(),
            artifacts.map { it.tierId }.toSet()
        )
        for (a in artifacts) {
            val model = requireNotNull(WhisperCatalog.byId(a.tierId))
            assertEquals(
                "every family's ${a.tierId} encoder lands under the catalog's fileName — " +
                    "delivery names are per-TIER (turbo's renamed so it can never overwrite " +
                    "the npu pair), never per-family",
                model.fileName,
                a.encoder.fileName
            )
            assertEquals(
                "and the decoder under the catalog's paired fileName (${a.familyId})",
                requireNotNull(model.pairedArtifact).fileName,
                a.decoder.fileName
            )
        }
    }

    @Test
    fun allSixteenArtifactDigestsAreSixtyFourHexAndPairwiseDistinct() {
        val hex = Regex("^[0-9a-f]{64}$")
        val digests = artifacts.flatMap { listOf(it.encoder.sha256, it.decoder.sha256) }
        assertEquals("eight pairs carry sixteen digests", 16, digests.size)
        for (d in digests) {
            assertTrue(
                "every artifact digest is 64 lowercase hex — got \"$d\"; anything else is a " +
                    "placeholder that would refuse every import",
                hex.matches(d)
            )
        }
        assertEquals(
            "sixteen DISTINCT digests — a copy-paste between rows would install one " +
                "family's binary under another family's verification with a passing " +
                "metadata check",
            16, digests.toSet().size
        )
        assertEquals(
            "and none of them collides with a skel digest — twenty distinct measurements " +
                "across the two censuses",
            20, (digests + families.map { it.skelSha256 }).toSet().size
        )
    }

    @Test
    fun theEightGenThreeArtifactRowsEqualTheCatalogsRecordValueForValue() {
        // The catalog cross-pin: WhisperCatalog keeps its constants as the REFERENCE family's
        // record (provenance + the published delivery zips), and this equality is what makes
        // the two records one record — the measure run's own 8gen3 self-check, re-executed
        // against the committed tables on every suite run.
        for (tierId in listOf("npu", "npu-turbo")) {
            val model = requireNotNull(WhisperCatalog.byId(tierId))
            val row = artifact("8gen3", tierId)
            assertEquals("$tierId encoder bytes are the catalog's primaryBytes",
                model.primaryBytes, row.encoder.bytes)
            assertEquals("$tierId encoder digest is the catalog's own",
                model.sha256, row.encoder.sha256)
            val paired = requireNotNull(model.pairedArtifact)
            assertEquals("$tierId decoder bytes are the pairedArtifact's own",
                paired.approxBytes, row.decoder.bytes)
            assertEquals("$tierId decoder digest is the pairedArtifact's own",
                paired.sha256, row.decoder.sha256)
            assertEquals(
                "and the pair sums to the size the tier card advertises",
                model.approxBytes, row.encoder.bytes + row.decoder.bytes
            )
        }
    }

    @Test
    fun everyVendorZipByteCountIsTheMeasuredExactValue() {
        for (a in artifacts) {
            assertTrue(
                "${a.familyId}/${a.tierId}: vendorZipBytes must be positive, measured, real",
                a.vendorZipBytes > 0L
            )
        }
        // The four turbo zips: the research's HEAD-measured table, byte for byte (the values
        // the measure run ASSERTS at HEAD rather than records).
        assertEquals(859_786_903L, artifact("8gen3", "npu-turbo").vendorZipBytes)
        assertEquals(859_689_781L, artifact("8elite_galaxy", "npu-turbo").vendorZipBytes)
        assertEquals(860_709_426L, artifact("8elite5_galaxy", "npu-turbo").vendorZipBytes)
        assertEquals(871_118_306L, artifact("7gen4", "npu-turbo").vendorZipBytes)
        // The small zips: 8gen3 was known (the spike's download); the other three were
        // RECORDED by the 2026-08-30 measure run and are exact values from here on.
        assertEquals(293_598_974L, artifact("8gen3", "npu").vendorZipBytes)
        assertEquals(293_117_989L, artifact("8elite_galaxy", "npu").vendorZipBytes)
        assertEquals(293_798_379L, artifact("8elite5_galaxy", "npu").vendorZipBytes)
        assertEquals(295_361_549L, artifact("7gen4", "npu").vendorZipBytes)
    }

    @Test
    fun theSevenGenFourEncodersSitOutsideTheReferenceToleranceAndTheFamilyAwareGateAcceptsThem() {
        // MEASURED 2026-08-30, and stated rather than blurred: HTP v73 packs weights less
        // densely, so BOTH 7gen4 encoders exceed the ±5% band around the CATALOG's reference
        // record (+11.0% small, +9.1% turbo). Under the 4.0 gate that meant a CORRECT 7gen4
        // install verified its copy exactly and then failed the finalise's isInstalled check
        // and rolled itself back. THE FIX LANDED IN 4.2 F5 — this statement re-made with it,
        // as its own message demanded: NpuAssetImport.installedGateBytes reads THIS census's
        // per-family bytes, and the gate walk below holds every family GREEN through the fixed
        // gate. The reference-band half stays the fact's tripwire in BOTH directions: if a
        // vendor re-release brings the encoders inside the reference band, or pushes any other
        // row outside it, the census was re-measured and this statement must be re-made again,
        // not inherited.
        for (a in artifacts) {
            val model = requireNotNull(WhisperCatalog.byId(a.tierId))
            val encoderInsideReference =
                WhisperCatalog.sizeWithinTolerance(a.encoder.bytes, model.primaryBytes)
            if (a.familyId == "7gen4") {
                assertFalse(
                    "${a.familyId}/${a.tierId}: the v73 encoder (${a.encoder.bytes} B) is " +
                        "OUTSIDE the ±5% band around the reference ${model.primaryBytes} B — " +
                        "the measured fact the F5 gate fix exists for",
                    encoderInsideReference
                )
            } else {
                assertTrue(
                    "${a.familyId}/${a.tierId}: encoder ${a.encoder.bytes} B within ±5% of " +
                        "the reference ${model.primaryBytes} B — every other family sits " +
                        "inside the reference band today",
                    encoderInsideReference
                )
            }
            assertTrue(
                "${a.familyId}/${a.tierId}: every family's decoder sits within ±5% of the " +
                    "reference record",
                WhisperCatalog.sizeWithinTolerance(
                    a.decoder.bytes, requireNotNull(model.pairedArtifact).approxBytes
                )
            )
            // GREEN-BY-FIX: the family-aware gate accepts every family's own pair — 7gen4
            // included, the row that used to verify-then-roll-back.
            val gate = NpuAssetImport.installedGateBytes(model, a)
            assertTrue(
                "${a.familyId}/${a.tierId}: the F5 gate reads this row's own encoder bytes, " +
                    "so a correct install of this family's pair now reads as installed",
                WhisperCatalog.sizeWithinTolerance(a.encoder.bytes, gate.primaryBytes)
            )
            assertTrue(
                "${a.familyId}/${a.tierId}: and this row's own decoder bytes",
                WhisperCatalog.sizeWithinTolerance(
                    a.decoder.bytes, requireNotNull(gate.paired).bytes
                )
            )
        }
    }

    @Test
    fun theMeasureScriptCarriesEveryCensusRowAsOnePairedLiteralBlock() {
        // The pack_npu_zip.py pattern, one instrument further out: build_asset_packs.py is the
        // script that measured these values and will FILL the packs (F4), so its embedded
        // CENSUS table must carry every row's zip bytes, entry bytes and digests as literals —
        // and PAIRED, as one block per row, because a table with two digests swapped between
        // rows still contains all sixteen (the L8 battery's finding, applied here on day one).
        val script = read("tools/build_asset_packs.py")
        for (a in artifacts) {
            assertEquals(
                "the script's CENSUS pairs ${a.familyId}/${a.tierId}'s five values in one " +
                    "block, exactly once",
                1,
                count(
                    script,
                    lines(
                        "    (\"${a.tierId}\", \"${a.familyId}\"): (",
                        "        ${grouped(a.vendorZipBytes)},",
                        "        ${grouped(a.encoder.bytes)}, \"${a.encoder.sha256}\",",
                        "        ${grouped(a.decoder.bytes)}, \"${a.decoder.sha256}\",",
                    ),
                )
            )
        }
        assertEquals(
            "the script pins the release the census describes",
            1, count(script, "RELEASE = \"0.61.0\"")
        )
        assertEquals(
            "and the hash-stable Last-Modified day every HEAD must reproduce",
            1, count(script, "LAST_MODIFIED_DAY = \"25 Aug 2026\"")
        )
    }

    @Test
    fun artifactForResolvesEveryRowAndAnswersNullOffTheCensus() {
        for (a in artifacts) {
            assertSame(
                "artifactFor(${a.familyId}, ${a.tierId}) must answer the row object itself — " +
                    "downstream holds row identity, not row copies",
                a, NpuFleetCensus.artifactFor(a.familyId, a.tierId)
            )
            assertSame(
                "and every artifact's familyId resolves in the family census — an orphan row " +
                    "is a pack no device can ever receive",
                byId(a.familyId), NpuFleetCensus.familyById(a.familyId)
            )
        }
        assertNull("an unknown family has no artifact", NpuFleetCensus.artifactFor("8gen2", "npu"))
        assertNull("an unknown tier has no artifact", NpuFleetCensus.artifactFor("8gen3", "cpu"))
        assertNull("exact matching — no case folding on the family",
            NpuFleetCensus.artifactFor("8GEN3", "npu"))
        assertNull("nor on the tier", NpuFleetCensus.artifactFor("8gen3", "NPU-TURBO"))
        assertNull("soc strings are not family ids here either",
            NpuFleetCensus.artifactFor("SM8650", "npu"))
    }

    @Test
    fun everyArtifactEvidenceLineCarriesTheMeasurementRecord() {
        for (a in artifacts) {
            assertTrue(
                "${a.familyId}/${a.tierId}: evidence must carry the measure date — got " +
                    "\"${a.evidence}\"",
                a.evidence.contains("2026-08-30")
            )
            assertTrue(
                "and the pinned Last-Modified event the gates held it to",
                a.evidence.contains("Last-Modified 2026-08-25")
            )
            assertTrue(
                "and the instrument, by name — a row nobody can re-measure is a row nobody " +
                    "can defend",
                a.evidence.contains("build_asset_packs.py measure")
            )
        }
        for (tierId in listOf("npu", "npu-turbo")) {
            assertTrue(
                "the 8gen3 $tierId row records that it reproduced the catalog's pins " +
                    "(the run's self-check)",
                artifact("8gen3", tierId).evidence.contains("self-check")
            )
        }
    }

    // -------------------------------------------------------------- fetchableTierIds (4.2 F6)

    /** The catalog's own gated set — the exact argument WhisperEverywhereApp's binding passes. */
    private val gatedTierIds = WhisperCatalog.entries.filter { it.gated }.map { it.id }.toSet()

    @Test
    fun fetchableTierIdsOffersEveryMeasuredTierOnACapableFamilyMinusInstalled() {
        assertEquals(
            "the truth table's gated input is the two npu-class tiers",
            setOf("npu", "npu-turbo"), gatedTierIds
        )
        for (family in families) {
            assertEquals(
                "${family.id}: capable with nothing installed -> both measured tiers fetchable",
                setOf("npu", "npu-turbo"),
                NpuFleetCensus.fetchableTierIds(family, true, gatedTierIds, emptySet())
            )
            assertEquals(
                "${family.id}: an installed tier is OFFERED, never fetchable",
                setOf("npu-turbo"),
                NpuFleetCensus.fetchableTierIds(family, true, gatedTierIds, setOf("npu"))
            )
            assertEquals(
                setOf("npu"),
                NpuFleetCensus.fetchableTierIds(family, true, gatedTierIds, setOf("npu-turbo"))
            )
            assertEquals(
                "${family.id}: everything installed leaves nothing to fetch",
                emptySet<String>(),
                NpuFleetCensus.fetchableTierIds(family, true, gatedTierIds, gatedTierIds)
            )
        }
    }

    @Test
    fun fetchableTierIdsIsEmptyOffTheCensusOrWhenTheProbeFails() {
        // This emptiness IS F6's non-capable byte-identity proof: the chooser's set is offered
        // UNION fetchable, union with the empty set is the identity, and every device this
        // function answers empty for therefore keeps today's model step exactly.
        assertEquals(
            "no resolved family -> nothing fetchable, whatever the probe said",
            emptySet<String>(),
            NpuFleetCensus.fetchableTierIds(null, true, gatedTierIds, emptySet())
        )
        for (family in families) {
            assertEquals(
                "${family.id}: a failed probe means no NPU class at all",
                emptySet<String>(),
                NpuFleetCensus.fetchableTierIds(family, false, gatedTierIds, emptySet())
            )
        }
        assertEquals(
            emptySet<String>(),
            NpuFleetCensus.fetchableTierIds(null, false, gatedTierIds, emptySet())
        )
    }

    @Test
    fun fetchableTierIdsNeverNamesATierTheFamilyHasNoMeasuredRowFor() {
        // The artifactFor gate: a gated id without a measured pair for THIS family must not
        // grow a fetch affordance — offering a pack the census cannot verify would be the F3
        // disease with a storefront. "npu-max" stands in for the next gated tier added to the
        // catalog before anyone measures its pairs.
        for (family in families) {
            assertEquals(
                "${family.id}: an unmeasured gated id is absent, the measured two remain",
                setOf("npu", "npu-turbo"),
                NpuFleetCensus.fetchableTierIds(family, true, gatedTierIds + "npu-max", emptySet())
            )
        }
        assertEquals(
            "no gated tiers, nothing to fetch",
            emptySet<String>(),
            NpuFleetCensus.fetchableTierIds(byId("8gen3"), true, emptySet(), emptySet())
        )
    }

    @Test
    fun aCapableFreshPlayInstallSteersToTurboThroughTheUnionWithZeroNewRules() {
        // F6's headline, executed end to end in the pure layer: on a capable FRESH install the
        // offered half is empty (nothing on disk) and the fetchable half names both tiers, so
        // the union hands ModelTierCopy exactly the set L9's ordering was already written for.
        // npu-turbo heads the steer and the lineup for EVERY locale (the owner's measured pick)
        // — and not one ordering rule was added or changed in F6, nor in 4.3.
        //
        // RE-SPECCED at 4.3: the lineup was `[npu-turbo, npu]` and is now `[npu-turbo]` alone —
        // the owner's ruling, applied at `WhisperCatalog.pickableFor`. The STEER assertion is
        // untouched, which is the point: 4.3 narrowed the offer set and left the steering rules
        // exactly as L9 measured them.
        val union = emptySet<String>() +
            NpuFleetCensus.fetchableTierIds(byId("8gen3"), true, gatedTierIds, emptySet())
        for (tag in listOf("en-US", "bn-BD", "es-MX")) {
            assertEquals(
                "$tag steers to turbo", "npu-turbo",
                ModelTierCopy.steerIdForLanguageTagFor(tag, union)
            )
            assertEquals(
                "$tag's lineup is turbo and nothing else", listOf("npu-turbo"),
                ModelTierCopy.orderedForLanguageTagFor(tag, union)
            )
        }
    }

    /**
     * 4.3 — **the census is what makes "capable" mean "offered turbo"**, and that equivalence is
     * the whole branch's load-bearing assumption.
     *
     * `WhisperCatalog.pickableFor` narrows the lineup exactly when `npu-turbo` is in the offer
     * set. A family carrying an `npu` row but NO `npu-turbo` row would be capable hardware that
     * the rule leaves on the full pre-4.3 menu with a 358 MB card at the top — the one shape the
     * owner ruled out ("they should just go straight to the one gig version"), reachable purely
     * by adding half a census row. Nothing else in the suite would notice: the offer rule would
     * be correct, the ordering correct, and the device wrong.
     *
     * So the census asserts the implication it is the sole source of: every family that can
     * deliver the small pair can deliver turbo.
     */
    @Test
    fun everyFamilyThatCanDeliverTheSmallPairCanAlsoDeliverTurbo() {
        families.forEach { family ->
            if (NpuFleetCensus.artifactFor(family.id, "npu") != null) {
                assertNotNull(
                    "${family.id} has an npu pack but no npu-turbo pack — a capable device on " +
                        "this family would be offered the 358 MB tier at the head of a full " +
                        "menu, which is exactly what the 4.3 ruling removes. Either measure the " +
                        "turbo pack or take the npu row out.",
                    NpuFleetCensus.artifactFor(family.id, "npu-turbo"),
                )
            }
        }
        // And the composition, on every family: a capable fresh install is offered ONE card.
        families.forEach { family ->
            val union = NpuFleetCensus.fetchableTierIds(family, true, gatedTierIds, emptySet())
            assertEquals(
                "${family.id}: a capable fresh install must see exactly one model card",
                listOf("npu-turbo"),
                ModelTierCopy.orderedForLanguageTagFor("bn-BD", union),
            )
        }
    }
}
