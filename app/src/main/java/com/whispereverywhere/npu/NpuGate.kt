package com.whispereverywhere.npu

/**
 * The SoC half of the NPU tier's availability gate: **a pure predicate over two strings**.
 *
 * WHY STRINGS RATHER THAN `Build`. Reading `Build.SOC_MODEL` in here would make this a truth table
 * no JVM test could ever evaluate — the exact shape the 3.6 GPU trap was made of, where the decision
 * that mattered lived in a place nothing could assert against. Taking the two values as parameters
 * makes the whole gate a table, and leaves the caller with the one Android-specific line it cannot
 * avoid.
 *
 * **The API-31 guard lives in the CALLER, not here (I7).** `minSdk` is 26 and `Build.SOC_MODEL` was
 * added in API 31, so reading it below that throws and lint flags `NewApi`. The caller does
 *
 * ```kotlin
 * val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
 * val mfr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null
 * ```
 *
 * and this gate's `null` → deny handles the whole pre-S population in one branch.
 *
 * **Full tier visibility is this AND the probe** — see
 * [com.whispereverywhere.transcription.NpuWhisperBackend.isTierAvailable], which composes
 * `isSocSupported(...) && QnnAsrNative.nativeProbe(libDir).isEmpty()`. This half is deliberately
 * first: it is free, and it means a Tensor / Exynos / MediaTek device never dlopens a Qualcomm
 * backend to be told no.
 */
object NpuGate {

    /**
     * The Snapdragon 8 Gen 3 part numbers, and nothing else.
     *
     * `SM8650` is the 8 Gen 3; `SM8650-AC` is the "for Galaxy" bin, a different string for the same
     * silicon. Both were measured. **Nothing else is on this list on purpose**: the tier ships a
     * precompiled QAIRT context binary built for one HTP architecture, and a context binary handed
     * to the wrong HTP does not degrade — it fails to deserialise, or worse, it does not.
     *
     * Widening this set is a measurement, never a guess. `SM8750` (8 Elite) is the obvious next
     * entry and it is not here because nobody has run the asset on one.
     */
    val SUPPORTED_SOCS: Set<String> = setOf("SM8650", "SM8650-AC")

    /**
     * `Build.SOC_MANUFACTURER` spells Qualcomm two ways depending on the OEM's build: `QTI`
     * (Qualcomm Technologies, Inc. — the common one) and `Qualcomm`. Both are accepted; nothing
     * else is.
     */
    val SUPPORTED_SOC_MANUFACTURERS: Set<String> = setOf("QTI", "Qualcomm")

    /**
     * True only for a measured Snapdragon 8 Gen 3 from a Qualcomm-spelled manufacturer.
     *
     * **Matching is EXACT, and every "helpful" relaxation of that is a way to ship the wrong
     * binary to the wrong silicon.** Not `startsWith("SM8650")` — that also accepts a future
     * `SM8650X` nobody has run. Not `equals(ignoreCase = true)` — `Build.SOC_MODEL` is a vendor
     * field, and a device whose OEM writes `sm8650` is a device we have not seen, which is the
     * whole population this function exists to keep out.
     *
     * **`null` denies, and so does `"unknown"`/`"UNKNOWN"`** — by falling out of the set rather
     * than by a special case. `null` is the below-API-31 device (see the class KDoc);
     * `Build.UNKNOWN` is what the platform substitutes when the OEM left the field unset. Neither
     * is evidence FOR the silicon, and "we cannot tell, let the probe decide" is not available
     * here: `nativeProbe` answers whether the HTP *stack* is present, which a Snapdragon 7-series
     * or 6-series also answers yes to. The probe cannot tell one Hexagon apart from another, so a
     * gate that defers to it on an unknown part ships a context binary compiled for v75 to
     * whatever is there.
     */
    fun isSocSupported(socModel: String?, socManufacturer: String?): Boolean {
        val model = socModel ?: return false
        val manufacturer = socManufacturer ?: return false
        return model in SUPPORTED_SOCS && manufacturer in SUPPORTED_SOC_MANUFACTURERS
    }
}
