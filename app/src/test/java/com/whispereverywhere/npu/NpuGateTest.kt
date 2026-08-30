package com.whispereverywhere.npu

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
 * 4.2: the gate reads the fleet census, so this table has two halves — the four families' strings
 * all pass (and resolve to their own rows), and the census's own CPU ledger all denies. The 4.0
 * owner-device rows are unchanged below; they became census rows without moving.
 */
class NpuGateTest {

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
            "SM8550 is the 8 Gen 2 — a Qualcomm part, a real Hexagon, and a part with NO " +
                "published w8a16 package (CPU_BY_CENSUS carries its evidence line, dated). It is " +
                "the single most likely false allow, because everything about it looks right — " +
                "and its absence is a checked fact, not an oversight.",
            NpuGate.isSocSupported("SM8550", "QTI")
        )
        assertFalse(
            "SM8750 (the non-Galaxy 8 Elite) denies too — the vendor publishes for the Galaxy " +
                "bin only, so the plain string stays CPU by census. Widening is a census edit " +
                "with evidence, and this row is what makes a guess fail.",
            NpuGate.isSocSupported("SM8750", "QTI")
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
        assertFalse(
            "SM8750 PLAIN still denies: the non-Galaxy 8 Elite has no published w8a16 package " +
                "(CPU_BY_CENSUS carries its evidence line). The suffix is the difference between " +
                "a covered Galaxy bin and an uncovered plain bin — which is why the census " +
                "writes suffix variants out and why nothing here matches by prefix.",
            NpuGate.isSocSupported("SM8750", "QTI")
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
        assertFalse(
            "SM8850 plain denies — the non-Galaxy 8 Elite Gen 5 has no published w8a16 package " +
                "as of 2026-08-29; same suffix trap as SM8750, one generation on",
            NpuGate.isSocSupported("SM8850", "QTI")
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
            "the gate's set is exactly the census's five strings — the four families' socModels, " +
                "suffix bins written out. If this ever grows, it grows by a census edit with " +
                "evidence (and the device-group XML regenerated in the same commit), not because " +
                "a part looked close.",
            setOf("SM8650", "SM8650-AC", "SM8750-AC", "SM8850-AD", "SM7750"),
            NpuGate.SUPPORTED_SOCS
        )
        assertEquals(
            "and exactly the two Qualcomm spellings the platform ships",
            setOf("QTI", "Qualcomm"),
            NpuGate.SUPPORTED_SOC_MANUFACTURERS
        )
    }

    @Test
    fun everyCensusStringPassesUnderBothManufacturerSpellings() {
        for (family in NpuFleetCensus.families) {
            for (soc in family.socModels) {
                for (mfr in listOf("QTI", "Qualcomm")) {
                    assertTrue(
                        "$soc + $mfr is census-covered silicon (family ${family.id}) and must " +
                            "pass — a false deny here is invisible on the device: the card " +
                            "simply never appears and nothing reports why",
                        NpuGate.isSocSupported(soc, mfr)
                    )
                    assertSame(
                        "and familyFor must resolve $soc to its OWN row — F2 stages " +
                            "family.skelAsset off this answer, so 'some row' is not enough",
                        family,
                        NpuGate.familyFor(soc, mfr)
                    )
                }
            }
        }
    }

    @Test
    fun everyCpuByCensusKeyDenies() {
        for ((soc, evidence) in NpuFleetCensus.CPU_BY_CENSUS) {
            for (mfr in listOf("QTI", "Qualcomm")) {
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
        // The gate's answer is not merely a boolean. F2 stages family.skelAsset, F3 verifies
        // against the family's artifact rows, F4 regenerates family.packGroup's XML — all off the
        // row THIS resolves. Identity, not equality: the object handed onward IS the census's row.
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
            "SM8750-AC resolves to the v79 family — NOT to anything 8gen3-shaped",
            elite, NpuGate.familyFor("SM8750-AC", "QTI")
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
        val mfrs: List<String?> = listOf("QTI", "Qualcomm", "QUALCOMM", "MediaTek", "unknown", "", null)
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
        assertEquals(
            "and the executed set equals the derivation (the needles prove provenance; " +
                "this proves value)",
            NpuFleetCensus.families.flatMap { it.socModels }.toSet(),
            NpuGate.SUPPORTED_SOCS
        )
    }
}
