package com.whispereverywhere.npu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The NPU tier's SoC gate, as a truth table.
 *
 * WHY THIS IS A JVM TEST AT ALL — which is the whole reason [NpuGate.isSocSupported] takes two
 * strings instead of reading `Build`. A gate that read `Build.SOC_MODEL` directly would be
 * unevaluable off a device, and the one thing this project has already paid for is a
 * capability decision nothing could assert against (the 3.6 GPU trap). Here the decision is a pure
 * function and every row of it is checked on a machine with no Hexagon in it.
 *
 * WHAT A WRONG ANSWER COSTS, in both directions:
 *  - **a false ALLOW** hands a QAIRT context binary compiled for one family's HTP architecture to
 *    some other Hexagon. The tier is offered, a multi-hundred-MB download is asked for, and the
 *    failure lands at `nativeInit` on the user's device;
 *  - **a false DENY** hides the tier from silicon that can run it, which is invisible: the user
 *    simply never sees the card and nothing anywhere reports why.
 *
 * 4.2: the gate reads the fleet census, so this table has two halves — every census family's
 * strings pass (and resolve to their own rows; six families since 2026-09-24), and the census's
 * own CPU ledger all denies. The 4.0
 * owner-device rows are unchanged below; they became census rows without moving.
 *
 * P2 (the gate on the row): the manufacturer is the ROW's now — a string passes only under a
 * spelling its own family admits, so the table is per vendor rather than "every family passes
 * under QTI" — and the capability half is dispatched on the row's vendor
 * ([NpuGate.runtimeAvailable]): the QNN probe on a Qualcomm row, the driver check's stored verdict
 * on a MediaTek one, executed here with fakes that fail the test if the wrong vendor's check runs.
 */
class NpuGateTest {

    @After
    fun forgetTheDriverVerdict() {
        // The driver check's flow is process state; a verdict one test publishes must not leak
        // into the next one's "unknown" (the NpuTierStatusTest reset, for the same reason).
        NpuApuDriverCheck.resetToUnknownForTest()
    }

    /** Every manufacturer spelling this table asks about — real ones and near-misses alike. */
    private val spellings: List<String> =
        listOf("QTI", "Qualcomm", "QUALCOMM", "Mediatek", "MediaTek", "MEDIATEK", "unknown", "")

    /**
     * A MediaTek row for the capability truth table, constructed here: the gate's dispatch is a
     * property of a row's VENDOR, and this pins it whether or not the census carries such a row.
     */
    private val mediatekRow = NpuSocFamily(
        id = "test_mtk",
        packGroup = "soc_test_mtk",
        socModels = setOf("MTTEST"),
        manufacturers = setOf("Mediatek"),
        runtime = NpuRuntimeNeeds.LiteRtMediatek(neuronMajor = 8, socStamp = "mttest"),
        tiers = setOf("npu-turbo"),
        evidence = "constructed in NpuGateTest 2026-09-24",
    )

    private fun verdict(refusal: String?) = NpuApuVerdict(
        fingerprint = "test/fingerprint",
        appBuild = 112,
        appUpdatedAtMs = 1L,
        wantMajor = 8,
        refusal = refusal,
        probedAtMs = 1L,
    )

    /**
     * Reads a repo file from the test's working directory — the locator the other source-reading
     * suites share. Line endings normalized at this single read site (the 3.7 N1 lesson).
     * `NpuGate.kt` is declared in `sourcePinnedInputs` because this class READS it — the list's
     * own rule; today every needle below is live-line-scoped, so the entry is not yet
     * load-bearing, and the next assertion added here is not required to remember the
     * distinction.
     */
    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /** The LIVE (non-comment) lines of [scope] containing [needle] — a commented-out mention must
     * never satisfy a positive pin, nor trip a negative one. */
    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").map { it.trimStart() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) &&
                line.contains(needle)
        }

    @Test
    fun theMeasuredSnapdragonEightGenThreeAllows() {
        assertTrue(
            "SM8650 + QTI is the measured configuration — the 8 Gen 3 with the common " +
                "Build.SOC_MANUFACTURER spelling. If this row is false the tier is dead on the " +
                "only silicon it was ever built for.",
            NpuGate.isSocSupported("SM8650", "QTI")
        )
        assertTrue(
            "Qualcomm is the other spelling OEMs ship in Build.SOC_MANUFACTURER, and it names the " +
                "same company",
            NpuGate.isSocSupported("SM8650", "Qualcomm")
        )
    }

    @Test
    fun theForGalaxyBinAllows() {
        assertTrue(
            "SM8650-AC is the \"for Galaxy\" bin: a different Build.SOC_MODEL string for the same " +
                "8 Gen 3 silicon, and it is a large slice of the population this tier targets. " +
                "Dropping it would hide the tier from every Galaxy S24 the asset actually runs on.",
            NpuGate.isSocSupported("SM8650-AC", "QTI")
        )
    }

    @Test
    fun aDifferentSnapdragonDenies() {
        assertFalse(
            "SM8475 is the 8+ Gen 1 — a Qualcomm part, a real Hexagon, and a part with NO " +
                "published w8a16 package (CPU_BY_CENSUS carries its evidence line, dated). It is " +
                "the single most likely false allow, because everything about it looks right — " +
                "and its absence is a checked fact, not an oversight.",
            NpuGate.isSocSupported("SM8475", "QTI")
        )
        // THE 8 GEN 2 USED TO BE THIS TEST'S EXAMPLE, and on 2026-09-22 it stopped being one:
        // AI Hub v0.62.2 published a qcs8550-proxy pack compiled for soc_model 43, it was
        // device-executed on an S23 Ultra, and SM8550 became a census family. So the part that
        // was named here as the most likely FALSE allow is now a TRUE one — asserted as such,
        // beside its former role, because a reader of this file should not have to wonder
        // whether the old line was deleted or forgotten.
        assertTrue(
            "SM8550 (8 Gen 2) ALLOWS since 2026-09-22 — the qcs8550 family, on device evidence",
            NpuGate.isSocSupported("SM8550", "QTI")
        )
        assertTrue(
            "and its Galaxy bin with it",
            NpuGate.isSocSupported("SM8550-AC", "QTI")
        )
        // SM8750 STOOD HERE AS A DENY until 2026-09-22, labelled "the non-Galaxy 8 Elite". It
        // is the string the Galaxy S25 itself reports, so the deny was turning away the very
        // phones the 8elite_galaxy pack was built for. It allows now, on device reads; the
        // 8+ Gen 1 above keeps this test's role.
        assertTrue(
            "SM8750 ALLOWS since 2026-09-22 — it is what every Galaxy S25-family phone reports",
            NpuGate.isSocSupported("SM8750", "QTI")
        )
        // SM8450 SAT BESIDE SM8475 IN THE CPU LEDGER until 2026-09-24 — "8 Gen 1, no published
        // w8a16 package" — and it left for the same reason the 8 Gen 2 did: AI Hub v0.63.0
        // published a package compiled for the part's own soc_model (36). The 8+ Gen 1 is a
        // different die with no package at all, so it keeps this test's role as the canonical
        // false allow; the 8 Gen 1 beside it now allows, and the two lines together are the
        // point — one HTP generation apart, one Qualcomm naming step apart, and only one has a
        // binary compiled for it.
        assertTrue(
            "SM8450 (8 Gen 1) ALLOWS since 2026-09-24 — the 8gen1 family, on the v0.63.0 package",
            NpuGate.isSocSupported("SM8450", "QTI")
        )
        assertEquals(
            "and it resolves to the 8gen1 row, whose HTP v69 is what stages the V69 skel",
            "8gen1", NpuGate.familyFor("SM8450", "QTI")?.id
        )
    }

    @Test
    fun aGalaxyEightEliteSm8750AcPassesTheGate() {
        assertTrue(
            "SM8750-AC is the 8 Elite for Galaxy — HTP v79 has a published w8a16 package " +
                "(census family 8elite_galaxy), so the Galaxy S25 family is covered silicon. " +
                "If this row is false the fleet ladder never grew past the owner's 8 Gen 3.",
            NpuGate.isSocSupported("SM8750-AC", "QTI")
        )
        assertTrue(
            "and under the other Qualcomm spelling — the manufacturer check is independent of " +
                "which census row answers",
            NpuGate.isSocSupported("SM8750-AC", "Qualcomm")
        )
        assertTrue(
            "SM8750 PLAIN passes, and it is the string that matters: Build.SOC_MODEL carries no " +
                "bin suffix, so an S25 reports SM8750 (device reads and Play's catalog, " +
                "2026-09-22). Until then this line asserted the opposite and the whole S25 " +
                "generation fell to CPU",
            NpuGate.isSocSupported("SM8750", "QTI")
        )
        assertTrue(
            "and under the other Qualcomm spelling",
            NpuGate.isSocSupported("SM8750", "Qualcomm")
        )
    }

    @Test
    fun aGalaxyEightEliteGenFiveSm8850AdPassesTheGate() {
        assertTrue(
            "SM8850-AD is the 8 Elite Gen 5 for Galaxy — HTP v81 has a published w8a16 package " +
                "(census family 8elite5_galaxy, the Galaxy S26 family)",
            NpuGate.isSocSupported("SM8850-AD", "QTI")
        )
        assertTrue(
            "and under the other Qualcomm spelling",
            NpuGate.isSocSupported("SM8850-AD", "Qualcomm")
        )
        assertTrue(
            "SM8850 PLAIN passes — what every Galaxy S26-family phone reports (S26, S26 Ultra, " +
                "Z Fold8 reads, 2026-09-22); same lesson as SM8750, one generation on",
            NpuGate.isSocSupported("SM8850", "QTI")
        )
        assertTrue(
            "and under the other Qualcomm spelling",
            NpuGate.isSocSupported("SM8850", "Qualcomm")
        )
    }

    @Test
    fun theSevenGenFourSm7750PassesTheGate() {
        assertTrue(
            "SM7750 is the 7 Gen 4 — HTP v73 has a published w8a16 package (census family " +
                "7gen4), the one covered family whose soc string ships suffix-free",
            NpuGate.isSocSupported("SM7750", "QTI")
        )
        assertTrue(
            "and under the other Qualcomm spelling",
            NpuGate.isSocSupported("SM7750", "Qualcomm")
        )
        assertFalse(
            "SM7750-AB denies: the research sketch imagined that bin, the spec's census does " +
                "not carry it — no evidence any device reports the string. If one surfaces it " +
                "is added to the CENSUS row with evidence (and the device-group XML in the same " +
                "commit), never here by prefix.",
            NpuGate.isSocSupported("SM7750-AB", "QTI")
        )
    }

    @Test
    fun nullSocDeniesBecauseThatIsEveryDeviceBelowApiThirtyOne() {
        assertFalse(
            "null is not an edge case — it is the ENTIRE pre-S population. minSdk is 26 and " +
                "Build.SOC_MODEL arrived in API 31, so the caller passes null for every device " +
                "below it and this branch is what keeps the tier off all of them.",
            NpuGate.isSocSupported(null, "QTI")
        )
        assertFalse(
            "a null manufacturer denies for the same reason — the caller nulls BOTH fields below " +
                "API 31, and a gate that only guarded one of them would allow on the other's value",
            NpuGate.isSocSupported("SM8650", null)
        )
        assertFalse("both null denies", NpuGate.isSocSupported(null, null))
    }

    @Test
    fun unknownSocDenies() {
        // Build.UNKNOWN is what the platform substitutes when the OEM never populated the field.
        // The tempting reading is "we cannot tell, so let nativeProbe decide" — and it is wrong:
        // the probe answers whether the HTP STACK is present, which a 7-series and a 6-series
        // Snapdragon also answer yes to. It cannot tell one Hexagon apart from another. Deferring
        // to it on an unknown part is how a v75 context binary reaches whatever is actually there.
        assertFalse(
            "Build.UNKNOWN (\"unknown\") is the absence of evidence, not evidence — it must deny",
            NpuGate.isSocSupported("unknown", "QTI")
        )
        assertFalse(
            "and the upper-case spelling denies identically; neither is a part number",
            NpuGate.isSocSupported("UNKNOWN", "QTI")
        )
        assertFalse(
            "an unknown MANUFACTURER denies as well",
            NpuGate.isSocSupported("SM8650", "unknown")
        )
        assertFalse("the empty string is not a part number either", NpuGate.isSocSupported("", ""))
    }

    @Test
    fun theRightSocWithTheWrongManufacturerDenies() {
        assertFalse(
            "the manufacturer is checked independently: a device reporting the right model string " +
                "under a manufacturer we have never seen is a device we have never seen",
            NpuGate.isSocSupported("SM8650", "MediaTek")
        )
        assertFalse(
            "including the empty manufacturer, which is what a stripped build reports",
            NpuGate.isSocSupported("SM8650", "")
        )
        assertFalse(
            "\"QUALCOMM\" is not one of the two spellings the platform ships — exact match only, " +
                "the same rule the model string is held to",
            NpuGate.isSocSupported("SM8650", "QUALCOMM")
        )
    }

    @Test
    fun matchingIsExactSoCaseAndPrefixVariantsDeny() {
        assertFalse(
            "lowercase sm8650 denies. Build.SOC_MODEL is a VENDOR field, so a device that spells " +
                "it differently is a build we have not seen, which is exactly the population this " +
                "gate exists to keep out — ignoreCase would silently admit it.",
            NpuGate.isSocSupported("sm8650", "QTI")
        )
        assertFalse(
            "SM8650X denies. startsWith(\"SM8650\") would accept both known bins with one elegant " +
                "rule AND every future part that shares the prefix; the set is the rule instead.",
            NpuGate.isSocSupported("SM8650X", "QTI")
        )
        assertFalse(
            "a surrounding-whitespace variant denies — no trimming, no normalisation, no guessing",
            NpuGate.isSocSupported(" SM8650", "QTI")
        )
        assertEquals(
            "the gate's set is exactly the census's ten strings — the six families' " +
                "socModels. It grew twice on 2026-09-22: by the 8 Gen 2's two strings, then by " +
                "plain SM8750 and SM8850, the strings the S25 and S26 generations actually " +
                "report; and once on 2026-09-24, by SM8450, the 8 Gen 1's only string. That is " +
                "how it is allowed to grow: a census edit with evidence, and the device-group " +
                "XML regenerated in the same commit. Never because a part looked close.",
            setOf("SM8650", "SM8650-AC", "SM8750", "SM8750-AC", "SM8850", "SM8850-AD", "SM7750",
                "SM8550", "SM8550-AC", "SM8450", "MT6989"),
            NpuGate.SUPPORTED_SOCS
        )
        // (P2) The fleet-wide set is DERIVED now — the union of the rows' own spellings — and it
        // survives only for the device-group XML's equality pin; familyFor asks the row.
        assertEquals(
            "and the fleet-wide spellings are exactly the union of the rows' own: the two " +
                "Qualcomm spellings the platform ships, on every Qualcomm row, and the one the " +
                "Tab S10+ reports, on the mt6989 row",
            setOf("QTI", "Qualcomm", "Mediatek"),
            NpuGate.SUPPORTED_SOC_MANUFACTURERS
        )
        assertEquals(
            "…derived, never retyped — the same set as the census's own union",
            NpuFleetCensus.families.flatMap { it.manufacturers }.toSet(),
            NpuGate.SUPPORTED_SOC_MANUFACTURERS
        )
    }

    /**
     * RE-SPECCED AT P2 (the gate on the row) from "every census string passes under both
     * Qualcomm spellings" — which was true only because every row was Qualcomm's — to what the
     * gate now IS: a string passes exactly under the spellings ITS OWN ROW admits, and under
     * every other spelling it denies. On a Qualcomm row that is still both Qualcomm spellings and
     * nothing else, so the old table is this one's Qualcomm half, unchanged; what grew is the
     * deny half, which now includes the other vendor's spellings.
     */
    @Test
    fun everyCensusStringPassesUnderExactlyItsOwnRowsManufacturers() {
        for (family in NpuFleetCensus.families) {
            for (soc in family.socModels) {
                for (mfr in family.manufacturers) {
                    assertTrue(
                        "$soc + $mfr is census-covered silicon (family ${family.id}) and must " +
                            "pass — a false deny here is invisible on the device: the card " +
                            "simply never appears and nothing reports why",
                        NpuGate.isSocSupported(soc, mfr)
                    )
                    assertSame(
                        "and familyFor must resolve $soc to its OWN row — the engine stages " +
                            "that row's runtime off this answer, so 'some row' is not enough",
                        family,
                        NpuGate.familyFor(soc, mfr)
                    )
                }
                for (mfr in spellings - family.manufacturers) {
                    assertNull(
                        "$soc under '$mfr' — a spelling its row (${family.id}) does not admit — " +
                            "must resolve NO row: the row decides its manufacturer",
                        NpuGate.familyFor(soc, mfr)
                    )
                }
            }
        }
    }

    @Test
    fun theQualcommRowsPassUnderTheQualcommSpellingsAndNeverUnderMediateks() {
        // The Qualcomm half of the per-vendor table as hard literals — the 4.0-4.15 behaviour,
        // held byte for byte through P2: both Qualcomm spellings pass, and a MediaTek spelling
        // denies a Snapdragon string exactly as an unknown one always did.
        val qualcomm = NpuFleetCensus.families.filter { it.vendor == NpuVendor.QUALCOMM }
        assertEquals("six Qualcomm rows", 6, qualcomm.size)
        for (family in qualcomm) {
            for (soc in family.socModels) {
                assertSame(family, NpuGate.familyFor(soc, "QTI"))
                assertSame(family, NpuGate.familyFor(soc, "Qualcomm"))
                assertNull("$soc under Mediatek denies", NpuGate.familyFor(soc, "Mediatek"))
                assertNull("$soc under MediaTek denies", NpuGate.familyFor(soc, "MediaTek"))
            }
        }
    }

    /**
     * P2-3 — THE TABLET'S ROW, resolved exactly as the tablet reports itself: `MT6989` under
     * `Mediatek` (`ro.soc.manufacturer`, and the Play catalog's spelling). LiteRT's own enum
     * spelling `MediaTek` denies — the row admits only what a device reports (design §7 q1) — and
     * so does every Qualcomm spelling: the manufacturer check is the row's, and one vendor's
     * spelling says nothing about the other vendor's silicon.
     */
    @Test
    fun theMt6989RowResolvesUnderTheSpellingTheTabletReportsAndNoOther() {
        val mt6989 = requireNotNull(NpuFleetCensus.familyById("mt6989"))
        assertSame("MT6989 + Mediatek is the Tab S10+'s own read", mt6989, NpuGate.familyFor("MT6989", "Mediatek"))
        assertTrue(NpuGate.isSocSupported("MT6989", "Mediatek"))
        for (mfr in listOf("MediaTek", "MEDIATEK", "mediatek", "QTI", "Qualcomm", "", "unknown")) {
            assertNull("MT6989 under '$mfr' denies — not a spelling the row admits", NpuGate.familyFor("MT6989", mfr))
        }
        for (soc in listOf("mt6989", "MT6989X", " MT6989", "MT6991", "MT6985")) {
            assertNull("'$soc' under Mediatek denies — exact matching, and MT6991 is a later row", NpuGate.familyFor(soc, "Mediatek"))
        }
        assertNull("a null model denies whatever the spelling", NpuGate.familyFor(null, "Mediatek"))
    }

    // ------------------------------------------------------------------ the capability half (P2)

    /**
     * THE CAPABILITY HALF, DISPATCHED ON THE VENDOR — executed. A Qualcomm row asks the QNN probe
     * and ignores any driver verdict; a MediaTek row reads the stored verdict and NEVER invokes
     * the QNN probe (the fake throws if it does — the executed form of "a MediaTek device never
     * dlopens a Qualcomm backend"); no family asks nothing.
     */
    @Test
    fun runtimeAvailableAsksTheQnnProbeOnQualcommRowsAndOnlyThere() {
        for (family in NpuFleetCensus.families.filter { it.vendor == NpuVendor.QUALCOMM }) {
            var probes = 0
            assertTrue(
                "${family.id}: a passing QNN probe is capability",
                NpuGate.runtimeAvailable(family, { probes++; true }, null)
            )
            assertFalse(
                "${family.id}: a failing QNN probe is none — whatever a driver verdict says",
                NpuGate.runtimeAvailable(family, { probes++; false }, verdict(refusal = null))
            )
            assertEquals("${family.id}: the probe ran once per question", 2, probes)
        }
        assertFalse(
            "no family: nothing is capable, and nothing is asked",
            NpuGate.runtimeAvailable(null, { throw AssertionError("the probe ran for no family") }, verdict(null))
        )
    }

    @Test
    fun runtimeAvailableReadsTheStoredVerdictOnMediatekRowsAndNeverTheQnnProbe() {
        val neverQnn: () -> Boolean = { throw AssertionError("the QNN probe ran for a MediaTek row") }
        assertFalse(
            "UNKNOWN — no verdict yet — is not-yet-capable, never an optimistic yes",
            NpuGate.runtimeAvailable(mediatekRow, neverQnn, null)
        )
        assertFalse(
            "a refused driver check is not capable",
            NpuGate.runtimeAvailable(mediatekRow, neverQnn, verdict(refusal = "adapter-missing"))
        )
        assertFalse(
            "…whatever the refusal",
            NpuGate.runtimeAvailable(mediatekRow, neverQnn, verdict(refusal = "driver-major-9-want-8"))
        )
        assertTrue(
            "a passed driver check is capable",
            NpuGate.runtimeAvailable(mediatekRow, neverQnn, verdict(refusal = null))
        )
        // And the census's own MediaTek row answers identically: the dispatch is the vendor's.
        val mt6989 = requireNotNull(NpuFleetCensus.familyById("mt6989"))
        assertFalse(NpuGate.runtimeAvailable(mt6989, neverQnn, null))
        assertFalse(NpuGate.runtimeAvailable(mt6989, neverQnn, verdict(refusal = "adapter-missing")))
        assertTrue(NpuGate.runtimeAvailable(mt6989, neverQnn, verdict(refusal = null)))
    }

    @Test
    fun unknownUntilProbedThenTheVerdictTheCheckPublished() {
        // The chooser's premise, at the layer a JVM can run: what isTierAvailable reads on a
        // MediaTek row is NpuApuDriverCheck.verdict's CURRENT value — unknown before the check
        // lands, the verdict after. (The producers that re-read on it are source-pinned in
        // ChooserSteerWiringPinTest; this is the answer they re-read.)
        val neverQnn: () -> Boolean = { throw AssertionError("the QNN probe ran for a MediaTek row") }
        assertNull("a process starts with no verdict", NpuApuDriverCheck.verdict.value)
        assertFalse(
            "and reads not-yet-capable until the check answers",
            NpuGate.runtimeAvailable(mediatekRow, neverQnn, NpuApuDriverCheck.verdict.value)
        )
        NpuApuDriverCheck.publish(verdict(refusal = null))
        assertTrue(
            "the check passed: the same read is now capable",
            NpuGate.runtimeAvailable(mediatekRow, neverQnn, NpuApuDriverCheck.verdict.value)
        )
    }

    @Test
    fun everyCpuByCensusKeyDenies() {
        for ((soc, evidence) in NpuFleetCensus.CPU_BY_CENSUS) {
            for (mfr in spellings) {
                assertFalse(
                    "$soc must deny ($evidence) — a pass here means the gate and the census's " +
                        "own CPU ledger contradict each other about a device",
                    NpuGate.isSocSupported(soc, mfr)
                )
                assertNull(
                    "and familyFor answers no row for $soc, for the same reason",
                    NpuGate.familyFor(soc, mfr)
                )
            }
        }
    }

    @Test
    fun familyForResolvesTheFamilyWhoseRowCarriesTheString() {
        // The gate's answer is not merely a boolean. The engine stages the row's runtime (F2's
        // skel, in the row's Qnn needs since P2), F3 verifies against the family's artifact rows,
        // F4 regenerates family.packGroup's XML — all off the row THIS resolves. Identity, not
        // equality: the object handed onward IS the census's row.
        val gen3 = requireNotNull(NpuFleetCensus.familyById("8gen3"))
        val elite = requireNotNull(NpuFleetCensus.familyById("8elite_galaxy"))
        val elite5 = requireNotNull(NpuFleetCensus.familyById("8elite5_galaxy"))
        val gen4 = requireNotNull(NpuFleetCensus.familyById("7gen4"))
        assertSame("SM8650 is the 8 Gen 3's plain bin", gen3, NpuGate.familyFor("SM8650", "QTI"))
        assertSame(
            "SM8650-AC is the 8 Gen 3's Galaxy bin — same row, different string",
            gen3, NpuGate.familyFor("SM8650-AC", "QTI")
        )
        assertSame(
            "SM8750 — the string an S25 reports — resolves to the v79 family",
            elite, NpuGate.familyFor("SM8750", "QTI")
        )
        assertSame(
            "SM8750-AC resolves to the v79 family — NOT to anything 8gen3-shaped",
            elite, NpuGate.familyFor("SM8750-AC", "QTI")
        )
        assertSame(
            "SM8850 — the string an S26 reports — resolves to the v81 family",
            elite5, NpuGate.familyFor("SM8850", "QTI")
        )
        assertSame(
            "SM8850-AD resolves to the v81 family",
            elite5, NpuGate.familyFor("SM8850-AD", "QTI")
        )
        assertSame(
            "SM7750 resolves to the v73 family",
            gen4, NpuGate.familyFor("SM7750", "QTI")
        )
    }

    @Test
    fun familyForDeniesTheWholeNonEvidencePopulation() {
        val denials = listOf<Pair<String?, String?>>(
            null to "QTI", // the entire pre-API-31 population — the caller nulls the field
            "SM8650" to null, // stripped manufacturer, same population
            null to null,
            "unknown" to "QTI", // Build.UNKNOWN — the OEM left the field unset
            "UNKNOWN" to "QTI",
            "sm8650" to "QTI", // vendor-case variant = a build nobody has seen
            "SM8650X" to "QTI", // superstring = a future part nobody has run
            " SM8650" to "QTI", // no trimming, no normalisation, no guessing
            "" to "",
            "SM8650" to "QUALCOMM", // not one of the two shipped spellings
            "SM8650" to "MediaTek",
            "SM8650" to "Mediatek", // a real row's spelling, the wrong vendor's silicon (P2)
            "MT6989" to "QTI", // and the other way round: the row decides its manufacturer
            "SM8750-AC" to "unknown", // a covered model under an unknown manufacturer: still no row
        )
        for ((soc, mfr) in denials) {
            assertNull(
                "familyFor($soc, $mfr) must resolve NO census row — null is the deny, " +
                    "the same branch for every kind of non-evidence",
                NpuGate.familyFor(soc, mfr)
            )
        }
    }

    @Test
    fun familyForAndIsSocSupportedAgreeOnEveryRow() {
        // Executed equivalence, not a source pin: both answers walked over the full row set —
        // every census string, every CPU_BY_CENSUS key, the whole denial population — under every
        // manufacturer answer. If isSocSupported ever grows logic of its own, some row here splits
        // the two and this fails naming it.
        val socs: List<String?> = NpuFleetCensus.families.flatMap { it.socModels } +
            NpuFleetCensus.CPU_BY_CENSUS.keys +
            listOf("sm8650", "SM8650X", " SM8650", "unknown", "UNKNOWN", "", null)
        val mfrs: List<String?> = spellings + null
        for (soc in socs) {
            for (mfr in mfrs) {
                assertEquals(
                    "familyFor and isSocSupported must agree on ($soc, $mfr) — the offer gate " +
                        "and the family resolution can never disagree about a device",
                    NpuGate.familyFor(soc, mfr) != null,
                    NpuGate.isSocSupported(soc, mfr)
                )
            }
        }
    }

    @Test
    fun theGateIsDerivedFromTheCensusOnItsFaceNotRetyped() {
        // Executed equality cannot tell "derived from the census" from "a hand-typed copy that
        // happens to match today" — and the copy is the exact pass-one-gate-fail-the-other hazard
        // the single census exists to kill. So the derivation is pinned on the SOURCE, live lines
        // only.
        val gate = source("src/main/java/com/whispereverywhere/npu/NpuGate.kt")
        assertEquals(
            "SUPPORTED_SOCS must be spelled as the census derivation on exactly one live line — " +
                "a fifth family joins by editing the census, nowhere else",
            1,
            liveLines(gate, "NpuFleetCensus.families.flatMap { it.socModels }.toSet()").size
        )
        assertEquals(
            "isSocSupported must be familyFor != null on exactly one live line — a re-typed " +
                "body is how the boolean and the row resolution start drifting",
            1,
            liveLines(gate, "familyFor(socModel, socManufacturer) != null").size
        )
        assertEquals(
            "no live line of NpuGate.kt may carry a hand-typed soc literal — the strings have " +
                "ONE home, the census, and a literal here is the second list growing back",
            0,
            liveLines(gate, "\"SM").size
        )
        // (P2) The manufacturer moved onto the row, with the same doctrine as the soc strings:
        // one home, the census rows; the gate derives and asks, and types nothing.
        assertEquals(
            "SUPPORTED_SOC_MANUFACTURERS is the census derivation on exactly one live line",
            1,
            liveLines(gate, "NpuFleetCensus.families.flatMap { it.manufacturers }.toSet()").size
        )
        assertEquals(
            "familyFor asks the ROW for both strings, on one live line — the model in its " +
                "socModels AND the manufacturer in its OWN manufacturers",
            1,
            liveLines(gate, "model in it.socModels && manufacturer in it.manufacturers").size
        )
        listOf("\"QTI\"", "\"Qualcomm\"", "\"Mediatek\"", "\"MediaTek\"").forEach { spelling ->
            assertEquals(
                "no live line of NpuGate.kt spells the manufacturer $spelling — the spellings' " +
                    "one home is the rows",
                0,
                liveLines(gate, spelling).size
            )
        }
        val familyFor = gate.substringAfter("fun familyFor(").substringBefore("fun isSocSupported(")
        assertEquals(
            "and familyFor never consults the fleet-wide union — a spelling one vendor ships says " +
                "nothing about another vendor's silicon",
            0,
            liveLines(familyFor, "SUPPORTED_SOC_MANUFACTURERS").size
        )
        assertEquals(
            "the union is exactly the census's own",
            NpuFleetCensus.families.flatMap { it.manufacturers }.toSet(),
            NpuGate.SUPPORTED_SOC_MANUFACTURERS
        )
        // And the capability half's dispatch is on the VENDOR, one arm each, with the QNN probe
        // on the Qualcomm arm alone (the truth table above executes it; this is its shape).
        assertEquals(
            "runtimeAvailable dispatches on the row's vendor",
            1,
            liveLines(gate, "): Boolean = when (family?.vendor) {").size
        )
        assertEquals(
            "…the Qualcomm arm is the QNN probe",
            1,
            liveLines(gate, "NpuVendor.QUALCOMM -> qnnProbePasses()").size
        )
        assertEquals(
            "…the MediaTek arm is the stored verdict, unknown reading false",
            1,
            liveLines(gate, "NpuVendor.MEDIATEK -> apuVerdict?.passed == true").size
        )
        assertEquals(
            "…and the QNN probe is invoked nowhere else in the gate",
            1,
            liveLines(gate, "qnnProbePasses()").size
        )
        assertEquals(
            "and the executed set equals the derivation (the needles prove provenance; " +
                "this proves value)",
            NpuFleetCensus.families.flatMap { it.socModels }.toSet(),
            NpuGate.SUPPORTED_SOCS
        )
    }
}
