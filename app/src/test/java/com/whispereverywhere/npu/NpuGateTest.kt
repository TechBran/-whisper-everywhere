package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
 *  - **a false ALLOW** hands a QAIRT context binary compiled for HTP v75 to some other Hexagon.
 *    The tier is offered, the 358 MB import is asked for, and the failure lands at `nativeInit` on
 *    the user's device;
 *  - **a false DENY** hides the tier from silicon that can run it, which is invisible: the user
 *    simply never sees the card and nothing anywhere reports why.
 */
class NpuGateTest {

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
            "SM8550 is the 8 Gen 2 — a Qualcomm part, a real Hexagon, and NOT the HTP architecture " +
                "the shipped context binaries were compiled for. It is the single most likely false " +
                "allow, because everything about it looks right.",
            NpuGate.isSocSupported("SM8550", "QTI")
        )
        assertFalse(
            "SM8750 (8 Elite) denies too — not because it cannot run this, but because nobody has " +
                "run it. Widening the set is a measurement, and this row is what makes a guess fail.",
            NpuGate.isSocSupported("SM8750", "QTI")
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
            "the allow-list is exactly the two measured 8 Gen 3 strings. If this ever grows, it " +
                "grows because someone ran the asset on the new part, not because it looked close.",
            setOf("SM8650", "SM8650-AC"),
            NpuGate.SUPPORTED_SOCS
        )
        assertEquals(
            "and exactly the two Qualcomm spellings the platform ships",
            setOf("QTI", "Qualcomm"),
            NpuGate.SUPPORTED_SOC_MANUFACTURERS
        )
    }
}
