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
 * The census AS DATA: every value pinned to a measured table — first the one the 2026-08-29 plan
 * bound, and since 2026-09-24 the v0.63.0 / QNN 2.50 re-measurement (research-verified soc strings
 * and HTP versions; skel rows measured out of `qnn-runtime-2.50.0.aar`; the 8gen3 artifact rows
 * equal to the catalog's record). A mismatch here is a census edit nobody measured — exactly the
 * drift these pins exist to make loud.
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

    /**
     * A row's QNN needs, or a named failure (P2 — the census reshape moved the HTP version and the
     * skel off the row into its sealed `runtime`). The hard cast is the point: every pin that
     * reads a skel value through it is a claim that the row IS a QNN row.
     */
    private fun qnn(f: NpuSocFamily): NpuRuntimeNeeds.Qnn =
        f.runtime as? NpuRuntimeNeeds.Qnn ?: throw AssertionError("${f.id} is not a QNN row: ${f.runtime}")

    /** The rows whose runtime is QNN — exactly the Qualcomm rows, since a row's vendor IS its runtime's. */
    private val qnnFamilies: List<NpuSocFamily>
        get() = families.filter { it.runtime is NpuRuntimeNeeds.Qnn }

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
    fun theCensusHasExactlyTheSixPublishedFamiliesInTableOrder() {
        assertEquals(
            "six families have published w8a16 packages (manifests re-fetched 2026-09-24, " +
                "v0.63.0) — a seventh row is a vendor event with evidence, a dropped row is lost " +
                "coverage nothing reports. qcs8550 is the 8 Gen 2, appended 2026-09-22 on DEVICE " +
                "evidence (S23 Ultra); 8gen1 is the 8 Gen 1, appended 2026-09-24 when v0.63.0 " +
                "first published its key — on the metadata and IO-census gates, with no " +
                "execution yet",
            listOf("8gen3", "8elite_galaxy", "8elite5_galaxy", "7gen4", "qcs8550", "8gen1"),
            families.map { it.id }
        )
        assertEquals(
            "and each family's Play device group carries the census id under the soc_ prefix — " +
                "F4 regenerates the device-group XML from THESE strings, so a drift here is a " +
                "store/gate disagreement",
            listOf("soc_8gen3", "soc_8elite_galaxy", "soc_8elite5_galaxy", "soc_7gen4",
                "soc_qcs8550", "soc_8gen1"),
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
        // THE SKEL IS A FUNCTION OF THE HTP VERSION, NOT OF THE FAMILY, and until 2026-09-22
        // nothing had to say so: there were four families with four distinct HTP versions, so
        // "all skels distinct" and "one skel per architecture" were the same assertion. qcs8550
        // (8 Gen 2) is HTP v73 exactly as 7gen4 is, and shares libQnnHtpV73Skel.so with it byte
        // for byte — which is correct, and would have failed a distinctness count.
        //
        // What must hold is the BIJECTION: two families share a skel if and only if they share
        // an architecture. Either half failing is the defect the old count was reaching for —
        // one family's skel staged under another family's silicon.
        val skelByHtp = qnnFamilies.map { qnn(it) }.groupBy { it.htpVersion }
        for ((htp, group) in skelByHtp) {
            assertEquals(
                "every family on HTP v$htp must name the SAME skel: the blob is the " +
                    "architecture's, and two names for one architecture would stage the wrong " +
                    "one somewhere",
                1, group.map { it.skelAsset }.toSet().size
            )
            assertEquals(
                "...and the same bytes and digest with it",
                1, group.map { it.skelBytes to it.skelSha256 }.toSet().size
            )
        }
        assertEquals(
            "and no skel may appear under two ARCHITECTURES — that is the original hazard, a " +
                "v73 blob staged for a v75 device",
            skelByHtp.size, qnnFamilies.map { qnn(it).skelAsset }.toSet().size
        )
    }

    @Test
    fun theHtpVersionsAreTheFiveMeasuredArchitecturesOnTheRightRows() {
        assertEquals(
            "the five published architectures, nothing else — v69 joined on 2026-09-24 with the " +
                "8gen1 family, read out of the vendor's own metadata by the measure gate",
            setOf(69, 73, 75, 79, 81),
            qnnFamilies.map { qnn(it).htpVersion }.toSet()
        )
        // Per-row as well, because two rows SWAPPING versions keeps the set equal while every
        // context binary lands on the wrong Hexagon:
        assertEquals("the 8 Gen 3 is HTP v75", 75, qnn(byId("8gen3")).htpVersion)
        assertEquals("the 8 Elite for Galaxy is HTP v79", 79, qnn(byId("8elite_galaxy")).htpVersion)
        assertEquals("the 8 Elite Gen 5 for Galaxy is HTP v81", 81, qnn(byId("8elite5_galaxy")).htpVersion)
        assertEquals(
            "the 7 Gen 4 is HTP v73 — the oldest arch on the newest part, which is why nothing " +
                "orders these",
            73, qnn(byId("7gen4")).htpVersion
        )
        assertEquals(
            "the 8 Gen 1 is HTP v69 — the oldest architecture in the census, and the only " +
                "family on it",
            69, qnn(byId("8gen1")).htpVersion
        )
        assertEquals("the 8 Gen 2 (qcs8550) is HTP v73, 7gen4's architecture", 73, qnn(byId("qcs8550")).htpVersion)
    }

    // ------------------------------------------------------------------ the vendor per row (P2)

    /**
     * P2 — THE CENSUS RESHAPE (design §2.1): a row carries its vendor, the manufacturer spellings
     * it admits, its sealed runtime needs and the tiers it offers. Hard literals per row, for the
     * reason every value in this class is one: a row whose vendor or spellings drifted would flow,
     * consistently, to the gate, the XML and the engine that stages its runtime.
     */
    @Test
    fun everyQualcommRowIsAQnnRowAdmittingBothQualcommSpellingsAndOfferingBothTiers() {
        val qualcomm = listOf("8gen3", "8elite_galaxy", "8elite5_galaxy", "7gen4", "qcs8550", "8gen1")
        for (id in qualcomm) {
            val f = byId(id)
            assertEquals("$id is a Qualcomm row", NpuVendor.QUALCOMM, f.vendor)
            assertTrue("$id's runtime is QNN — its HTP version and skel live there", f.runtime is NpuRuntimeNeeds.Qnn)
            assertEquals(
                "$id admits exactly the two spellings Qualcomm ships in Build.SOC_MANUFACTURER — " +
                    "QTI (the common one) and Qualcomm — and nothing else (the doctrine the gate " +
                    "held as one global set until P2 moved it onto the row)",
                setOf("QTI", "Qualcomm"), f.manufacturers
            )
            assertEquals(
                "$id offers both gated tiers — every Qualcomm family has a published small AND " +
                    "turbo package (the 4.3 ruling hides small; it does not take its pack away)",
                setOf("npu", "npu-turbo"), f.tiers
            )
        }
        assertEquals(
            "and those six ARE the Qualcomm rows — in census order, every one of them",
            qualcomm,
            families.filter { it.vendor == NpuVendor.QUALCOMM }.map { it.id }
        )
    }

    @Test
    fun aRowsVendorIsItsRuntimesAndNeverAFieldOfItsOwn() {
        // The variant IS the vendor: a row cannot say QUALCOMM and carry LiteRT's needs, because
        // NpuSocFamily.vendor has no field to disagree with — it reads runtime.vendor.
        assertEquals(
            NpuVendor.QUALCOMM,
            NpuRuntimeNeeds.Qnn(htpVersion = 75, skelAsset = "s", skelBytes = 1L, skelSha256 = "h").vendor
        )
        assertEquals(
            NpuVendor.MEDIATEK,
            NpuRuntimeNeeds.LiteRtMediatek(neuronMajor = 8, socStamp = "mt6989").vendor
        )
        for (f in families) {
            assertSame("${f.id}'s vendor is read off its runtime", f.runtime.vendor, f.vendor)
        }
    }

    @Test
    fun everyRowsTiersAreExactlyTheTiersItHasMeasuredPairsFor() {
        // One fact, two spellings held equal: `tiers` says what a family OFFERS, the artifact rows
        // say what it can VERIFY. A tier offered with no measured pair is a storefront for a pack
        // nobody can check (the F3 disease); a measured pair for a tier not offered is coverage
        // nothing reaches.
        for (f in families) {
            assertEquals(
                "${f.id}: tiers == the tier ids of its measured artifact rows",
                artifacts.filter { it.familyId == f.id }.map { it.tierId }.toSet(),
                f.tiers
            )
        }
    }

    @Test
    fun theSocModelSetsAreExactAndPairwiseDisjoint() {
        assertEquals(
            "the 8 Gen 3's two measured strings — the 4.0 allowlist, absorbed as a census row",
            setOf("SM8650", "SM8650-AC"), byId("8gen3").socModels
        )
        assertEquals(
            "the 8 Elite row leads with PLAIN SM8750 — what every S25-family phone reports; " +
                "the suffixed alias stays beside it. Until 2026-09-22 this row was the alias " +
                "alone and matched no device",
            setOf("SM8750", "SM8750-AC"), byId("8elite_galaxy").socModels
        )
        assertEquals(
            "the 8 Elite Gen 5 row, same shape one generation on: plain SM8850 first",
            setOf("SM8850", "SM8850-AD"), byId("8elite5_galaxy").socModels
        )
        assertEquals(
            "the 7 Gen 4 ships suffix-free — one string until a device proves another",
            setOf("SM7750"), byId("7gen4").socModels
        )
        assertEquals(
            "the 8 Gen 1 carries ONE string, the plain one — what the S22s and Tab S8s report, " +
                "and the only spelling Play's catalog holds for the part (67 rows, all " +
                "'QTI SM8450'). No bin suffix exists to write out",
            setOf("SM8450"), byId("8gen1").socModels
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
    fun everySkelSha256IsSixtyFourLowercaseHexAndAllFiveDistinct() {
        val hex = Regex("^[0-9a-f]{64}$")
        for (f in qnnFamilies) {
            assertTrue(
                "family ${f.id}'s skelSha256 must be 64 lowercase hex characters — got " +
                    "\"${qnn(f).skelSha256}\"; anything else is a placeholder that would refuse " +
                    "every stage",
                hex.matches(qnn(f).skelSha256)
            )
        }
        assertEquals(
            "five architectures, five DISTINCT digests — a duplicate is a copy-paste, " +
                "not a measurement (qcs8550 and 7gen4 share V73's, which is one architecture)",
            5, qnnFamilies.map { qnn(it).skelSha256 }.toSet().size
        )
    }

    @Test
    fun theEightGenThreeSkelRowIsTheMeasuredTwoFiftyPinExactly() {
        // The continuity pin, re-made at the runtime bump rather than inherited. 4.1 L6 shipped
        // 17,913,608 B / a56519d6… and 4.2-4.14 carried that pair unchanged; QNN 2.50 is a new
        // blob for every architecture, so on 2026-09-24 this pin moved to the value pair
        // measured out of qnn-runtime-2.50.0.aar (Maven Central, sha256 b507656e…). The Fold6
        // executed the OLD pair; this one is owed a device run. If the census's copy moves
        // again without a runtime bump, the fleet table is a new source, unmeasured.
        val row = qnn(byId("8gen3"))
        assertEquals("libQnnHtpV75Skel.so", row.skelAsset)
        assertEquals(18_693_300L, row.skelBytes)
        assertEquals(
            "3e9774b74769915b4f54364f8fc25887b3439561a970dca57c9f4dc9612b38af",
            row.skelSha256
        )
    }

    @Test
    fun theOtherSkelRowsCarryTheMeasuredAarValues() {
        // Measured out of qnn-runtime-2.50.0.aar (jni/arm64-v8a/) on 2026-09-24 — every row
        // moved from the 2026-08-29 qnn-runtime-2.49.0 table, and V69 is new with the 8gen1
        // family. F2's extract task asserts the same pairs at build time; these are the census's
        // copies, and the two spellings meeting IS the check.
        val v69 = qnn(byId("8gen1"))
        assertEquals("libQnnHtpV69Skel.so", v69.skelAsset)
        assertEquals(12_529_660L, v69.skelBytes)
        assertEquals(
            "262f3e8807ea969cfc446ea8717500475ea1ea1be6205201486a5431ffcb490e",
            v69.skelSha256
        )
        val v73 = qnn(byId("7gen4"))
        assertEquals("libQnnHtpV73Skel.so", v73.skelAsset)
        assertEquals(18_709_712L, v73.skelBytes)
        assertEquals(
            "024a0aea3d8d44fc5b59ffab20bde4348d07d05ad7d23f27c8bd06aa3d240d8a",
            v73.skelSha256
        )
        val v79 = qnn(byId("8elite_galaxy"))
        assertEquals("libQnnHtpV79Skel.so", v79.skelAsset)
        assertEquals(18_513_604L, v79.skelBytes)
        assertEquals(
            "860c9d2e7c937c9fb8f8f18daa9a79cab6c566066a2d36f235f6c8708fdc75bd",
            v79.skelSha256
        )
        val v81 = qnn(byId("8elite5_galaxy"))
        assertEquals("libQnnHtpV81Skel.so", v81.skelAsset)
        assertEquals(19_708_192L, v81.skelBytes)
        assertEquals(
            "02047c9fef8a22801c0eefaa79188e87b600372c9813dea3f621ba256d1ddce0",
            v81.skelSha256
        )
    }

    @Test
    fun everyEvidenceLineCarriesARecordedDate() {
        for (f in families) {
            // 2026-09-22: was "2026-08-2" (the v0.61.0 measurement). The re-measurement at
            // v0.62.2 recorded 2026-09-22, so the assertion is that a date is PRESENT and is
            // the one the current census was measured on — not that it is August's. The
            // v0.63.0 re-measurement recorded 2026-09-24, which the same needle still covers.
            assertTrue(
                "family ${f.id}'s evidence must carry a recorded 2026-09-2x date — a date was " +
                    "recorded, not a vibe. Got: \"${f.evidence}\"",
                f.evidence.contains("2026-09-2")
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
    fun theCpuLedgerNamesTheAbsentPartsAndStaysDisjointFromTheCensus() {
        assertEquals(
            "two checked-absent strings for two absent parts — 8+ Gen 1 and 888, both " +
                "re-checked against the v0.63.0 manifests on 2026-09-24. Five strings have LEFT " +
                "this ledger, which is the only way out of it, a measurement with a date: the " +
                "8 Gen 2's two for the qcs8550 family (2026-09-22, device-executed), plain SM8750 " +
                "and SM8850 the same day (never 'non-Galaxy' strings at all), and SM8450 on " +
                "2026-09-24 for the 8gen1 family, when v0.63.0 first published its package",
            setOf("SM8475", "SM8350"),
            NpuFleetCensus.CPU_BY_CENSUS.keys
        )
        assertEquals(
            "the named example line, verbatim — the ledger's format contract: part, the " +
                "absence, the date, the method. It was the 8 Gen 2's until that part earned a " +
                "family; the 8+ Gen 1 carries the identical shape",
            "8+ Gen 1 — no published w8a16 package as of 2026-08-29 " +
                "(both release manifests re-fetched)",
            NpuFleetCensus.CPU_BY_CENSUS["SM8475"]
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
        for (f in qnnFamilies) {
            assertEquals(
                "family ${f.id} (HTP v${qnn(f).htpVersion}) must stage the skel of its OWN " +
                    "architecture — a mismatch stages a skel FastRPC cannot pair with the " +
                    "family's context binaries, and that failure is a device mystery, not a " +
                    "compile error",
                "libQnnHtpV${qnn(f).htpVersion}Skel.so",
                qnn(f).skelAsset
            )
        }
    }

    // ------------------------------------------------------------------ the artifact census (F3)

    @Test
    fun theArtifactCensusHasTwelveRowsFamilyMajorInTableOrderUnderTheCatalogsNames() {
        assertEquals(
            "twelve measured pairs: 6 families x 2 tiers, family-major in families order, " +
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
    fun allTwentyFourArtifactDigestsAreSixtyFourHexAndPairwiseDistinct() {
        val hex = Regex("^[0-9a-f]{64}$")
        val digests = artifacts.flatMap { listOf(it.encoder.sha256, it.decoder.sha256) }
        assertEquals("twelve pairs carry twenty-four digests", 24, digests.size)
        for (d in digests) {
            assertTrue(
                "every artifact digest is 64 lowercase hex — got \"$d\"; anything else is a " +
                    "placeholder that would refuse every import",
                hex.matches(d)
            )
        }
        assertEquals(
            "twenty-four DISTINCT digests — a copy-paste between rows would install one " +
                "family's binary under another family's verification with a passing " +
                "metadata check",
            24, digests.toSet().size
        )
        // Twenty-four artifact digests plus FIVE skels, not six: qcs8550 and 7gen4 are both HTP
        // v73 and name the same blob, so the union is 29 rather than 30. Derived from the
        // census rather than spelled, because the two counts move independently — 8gen1 added
        // two artifact digests AND a skel, because it brought a new architecture (v69).
        val skels = qnnFamilies.map { qnn(it).skelSha256 }.toSet()
        assertEquals(
            "and none of them collides with a skel digest — every artifact digest and every " +
                "architecture's skel digest is its own measurement",
            digests.toSet().size + skels.size,
            (digests + skels).toSet().size
        )
    }

    @Test
    fun theEightGenThreeArtifactRowsEqualTheCatalogsRecordValueForValue() {
        // The catalog cross-pin: WhisperCatalog keeps its constants as the REFERENCE family's
        // record (provenance + the published delivery zips), and this equality is what makes
        // the two records one record — the measure run's own 8gen3 self-check, re-executed
        // against the committed tables on every suite run. At v0.63.0 both records moved
        // together, from one measurement (2026-09-24): a rebuild replaces the reference pair,
        // so this pin is what proved the catalog edit and the census edit were the same edit.
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
        // (P2) The field is `sourceBytes` now — for a vendor row the same number under a truer
        // name, and null only for a LOCAL row, which has no vendor zip. Every Qualcomm row is a
        // vendor row, so every Qualcomm artifact carries its zip's exact length.
        val qualcommIds = families.filter { it.vendor == NpuVendor.QUALCOMM }.map { it.id }.toSet()
        for (a in artifacts.filter { it.familyId in qualcommIds }) {
            val zip = a.sourceBytes
            assertTrue(
                "${a.familyId}/${a.tierId}: a vendor row's sourceBytes is its zip's length — " +
                    "positive, measured, real; got $zip",
                zip != null && zip > 0L
            )
        }
        // All twelve zips, byte for byte (the values the measure run ASSERTS at HEAD).
        //
        // HISTORY, because each move was a different kind of event: at v0.62.2 the four turbo
        // zips lost exactly one byte each (903->902, 781->780, 426->425, 306->305) — the
        // archive wrapper, a re-release. At v0.63.0 (2026-09-24) EVERY zip moved by megabytes,
        // all smaller (small -2.7 to -3.3%, turbo -4.2 to -4.9%): a QAIRT 2.50 rebuild, and the
        // binary digests moved with them. The 8gen1 pair is new at this release.
        assertEquals(823_721_812L, artifact("8gen3", "npu-turbo").sourceBytes)
        assertEquals(823_685_860L, artifact("8elite_galaxy", "npu-turbo").sourceBytes)
        assertEquals(824_020_866L, artifact("8elite5_galaxy", "npu-turbo").sourceBytes)
        assertEquals(828_034_458L, artifact("7gen4", "npu-turbo").sourceBytes)
        assertEquals(823_697_212L, artifact("qcs8550", "npu-turbo").sourceBytes)
        assertEquals(821_903_663L, artifact("8gen1", "npu-turbo").sourceBytes)
        assertEquals(285_197_039L, artifact("8gen3", "npu").sourceBytes)
        assertEquals(285_116_926L, artifact("8elite_galaxy", "npu").sourceBytes)
        assertEquals(285_450_230L, artifact("8elite5_galaxy", "npu").sourceBytes)
        assertEquals(285_697_544L, artifact("7gen4", "npu").sourceBytes)
        assertEquals(285_198_646L, artifact("qcs8550", "npu").sourceBytes)
        assertEquals(284_581_383L, artifact("8gen1", "npu").sourceBytes)
    }

    @Test
    fun everyEncoderSitsInsideTheReferenceBandAtV063AndTheOldPairsNoLongerReadAsInstalled() {
        // RE-MADE 2026-09-24, as the previous statement's own message demanded ("if a vendor
        // re-release brings the encoders inside the reference band, or pushes any other row
        // outside it, the census was re-measured and this statement must be re-made again, not
        // inherited"). It was: `theSevenGenFourEncodersSitOutsideTheReferenceTolerance…` held,
        // from the 2026-08-30 measurement, that both 7gen4 encoders sat OUTSIDE the ±5% band
        // around the catalog's reference (+11.0% small, +9.1% turbo), which is why 4.2 F5 made
        // the installed-size gate read each family's own census bytes. At v0.63.0 that fact is
        // gone: every family's encoder sits inside the band — 7gen4 turbo is the widest at +2.6%
        // — and 8gen1 sits just under the reference (-1.1% small, -0.7% turbo).
        for (a in artifacts) {
            val model = requireNotNull(WhisperCatalog.byId(a.tierId))
            assertTrue(
                "${a.familyId}/${a.tierId}: encoder ${a.encoder.bytes} B within ±5% of the " +
                    "reference ${model.primaryBytes} B — every family sits inside the reference " +
                    "band at v0.63.0, 7gen4 included",
                WhisperCatalog.sizeWithinTolerance(a.encoder.bytes, model.primaryBytes)
            )
            assertTrue(
                "${a.familyId}/${a.tierId}: every family's decoder sits within ±5% of the " +
                    "reference record",
                WhisperCatalog.sizeWithinTolerance(
                    a.decoder.bytes, requireNotNull(model.pairedArtifact).approxBytes
                )
            )
            // The family-aware gate still accepts every family's own pair — it reads the row's
            // bytes, so this holds whatever the reference band says.
            val gate = NpuAssetImport.installedGateBytes(model, a)
            assertTrue(
                "${a.familyId}/${a.tierId}: the F5 gate reads this row's own encoder bytes, " +
                    "so a correct install of this family's pair reads as installed",
                WhisperCatalog.sizeWithinTolerance(a.encoder.bytes, gate.primaryBytes)
            )
            assertTrue(
                "${a.familyId}/${a.tierId}: and this row's own decoder bytes",
                WhisperCatalog.sizeWithinTolerance(
                    a.decoder.bytes, requireNotNull(gate.paired).bytes
                )
            )
        }
        // THE NEW FACT THE REFRESH BRINGS, pinned so nobody meets it on a device first: the
        // pre-refresh encoders on an installed phone fall OUTSIDE the band of their own family's
        // v0.63.0 row, so `isInstalled` answers false for them after the 4.15 update and the NPU
        // tier is fetchable again rather than silently running the old binaries. The 0.62.2
        // 8gen3 turbo encoder (775,831,552 B, the Fold6's) is +13.1% over 686,112,520; the
        // qcs8550 one (775,843,840 B, the S23 Ultra's) the same; the small encoder
        // (132,927,488 B) is +17.5%. A future vendor release that lands inside ±5% of the old
        // bytes would silently keep old pairs installed, and this is where that shows.
        for ((family, tier, oldEncoderBytes) in listOf(
            Triple("8gen3", "npu-turbo", 775_831_552L),
            Triple("qcs8550", "npu-turbo", 775_843_840L),
            Triple("8gen3", "npu", 132_927_488L),
        )) {
            val model = requireNotNull(WhisperCatalog.byId(tier))
            val gate = NpuAssetImport.installedGateBytes(model, artifact(family, tier))
            assertFalse(
                "$family/$tier: the 0.62.2 encoder ($oldEncoderBytes B) must NOT pass the v0.63.0 " +
                    "installed gate (${gate.primaryBytes} B ±5%) — a pass would keep the slow " +
                    "binaries installed across the refresh with nothing offering the new ones",
                WhisperCatalog.sizeWithinTolerance(oldEncoderBytes, gate.primaryBytes)
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
                        // (P2) sourceBytes — the vendor zip's length, the field's old value.
                        "        ${grouped(requireNotNull(a.sourceBytes) { "${a.familyId}/${a.tierId} has no vendor zip" })},",
                        "        ${grouped(a.encoder.bytes)}, \"${a.encoder.sha256}\",",
                        "        ${grouped(a.decoder.bytes)}, \"${a.decoder.sha256}\",",
                    ),
                )
            )
        }
        // Both moved on 2026-09-22 with the re-measurement (was 0.61.0 / "25 Aug 2026"), and
        // again on 2026-09-24 with the v0.63.0 one (was 0.62.2 / "11 Sep 2026"; the new day read
        // off a HEAD of every object). They are pinned HERE as well as in the script so the two
        // tables cannot drift: a release bump in the instrument without a re-measured census
        // fails this line.
        assertEquals(
            "the script pins the release the census describes",
            1, count(script, "RELEASE = \"0.63.0\"")
        )
        assertEquals(
            "and the hash-stable Last-Modified day every HEAD must reproduce",
            1, count(script, "LAST_MODIFIED_DAY = \"23 Sep 2026\"")
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
                a.evidence.contains("2026-09-24")
            )
            assertTrue(
                "and the pinned Last-Modified event the gates held it to",
                a.evidence.contains("Last-Modified 2026-09-23")
            )
            assertTrue(
                "and the instrument, by name — a row nobody can re-measure is a row nobody " +
                    "can defend",
                a.evidence.contains("build_asset_packs.py measure")
            )
        }
        // The 8gen3 rows used to record that they reproduced the catalog's pins, which was the
        // run's self-check. At a vendor rebuild that cannot hold (v0.63.0 replaced every digest),
        // so what each row must record now is the opposite fact and the check that replaced it:
        for ((tierId, release) in listOf("npu" to "4.0", "npu-turbo" to "4.1")) {
            val evidence = artifact("8gen3", tierId).evidence
            assertTrue(
                "the 8gen3 $tierId row records that the rebuild cannot reproduce the $release " +
                    "pins it replaced — got \"$evidence\"",
                evidence.contains("A rebuild cannot reproduce the $release pins it replaced")
            )
            assertTrue(
                "and that the self-check is now the second measure run reproducing every row " +
                    "from the pasted literals — got \"$evidence\"",
                evidence.contains("the self-check is the second measure run reproducing every row")
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
    fun fetchableTierIdsOffersOnlyTheTiersTheFamilyOffers() {
        // P2 — the chooser's fetch set consults the family's `tiers`, not only the artifact
        // rows: the MediaTek rows offer turbo alone (the owner's ruling for the tablets), and a
        // tier outside a family's `tiers` must grow no Get button even where a measured pair
        // exists. A real row with its tiers narrowed is the whole proof: the npu artifact is
        // still in the census, and the answer drops it anyway.
        val narrowed = byId("8gen3").copy(tiers = setOf("npu-turbo"))
        assertNotNull("the npu pair IS measured for 8gen3", NpuFleetCensus.artifactFor("8gen3", "npu"))
        assertEquals(
            "a family that does not offer npu is never fetched npu",
            setOf("npu-turbo"),
            NpuFleetCensus.fetchableTierIds(narrowed, true, gatedTierIds, emptySet())
        )
        assertEquals(
            "and a family that offers nothing fetches nothing",
            emptySet<String>(),
            NpuFleetCensus.fetchableTierIds(byId("8gen3").copy(tiers = emptySet()), true, gatedTierIds, emptySet())
        )
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
