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
 *
 * WHAT GREW IN 4.2: the gate stopped being an owner-device allowlist and became the reader of the
 * fleet census. [familyFor] resolves a device to its [NpuSocFamily] row — the row everything
 * per-family downstream consumes — and [isSocSupported] is DERIVED from it, so the offer gate and
 * the family resolution can never disagree about a device. The strings themselves have one home,
 * [NpuFleetCensus]; nothing in this file spells one out.
 */
object NpuGate {

    /**
     * Every `Build.SOC_MODEL` string in the census — DERIVED from [NpuFleetCensus.families], never
     * a second hand-typed list. Two lists agreeing by discipline is the exact
     * pass-one-gate-fail-the-other hazard a single census exists to kill: a fifth family joins by
     * editing the census, and this set follows mechanically. (The derivation is source-pinned by
     * `NpuGateTest`, because a hand-typed copy would pass every equality test right up until the
     * first drift.)
     *
     * Widening the census is a measurement, never a guess — maintenance rule 2 in
     * [NpuFleetCensus]'s KDoc, where the rows live.
     */
    val SUPPORTED_SOCS: Set<String> = NpuFleetCensus.families.flatMap { it.socModels }.toSet()

    /**
     * `Build.SOC_MANUFACTURER` spells Qualcomm two ways depending on the OEM's build: `QTI`
     * (Qualcomm Technologies, Inc. — the common one) and `Qualcomm`. Both are accepted; nothing
     * else is.
     */
    val SUPPORTED_SOC_MANUFACTURERS: Set<String> = setOf("QTI", "Qualcomm")

    /**
     * The census row whose [NpuSocFamily.socModels] carries this exact string, from a
     * Qualcomm-spelled manufacturer — or null, which is the deny.
     *
     * **Matching is EXACT, and every "helpful" relaxation of that is a way to ship the wrong
     * binary to the wrong silicon.** This is now a FLEET rule, not an owner-device rule — four
     * families wide, and wider only by census edit. Not a prefix match — that also accepts every
     * future superstring part nobody has run. Not `equals(ignoreCase = true)` — `Build.SOC_MODEL`
     * is a vendor field, and a device whose OEM spells a part differently is a device we have not
     * seen, which is the whole population this function exists to keep out. The Samsung suffix
     * bins are the sharpest case: the census writes every variant out as its own string, because a
     * covered `-AC` bin and an uncovered plain bin differ by exactly the characters a prefix match
     * would ignore.
     *
     * **`null` denies, and so does `unknown`/`UNKNOWN`** — by falling out of every row rather than
     * by a special case. `null` is the below-API-31 device (see the class KDoc); `Build.UNKNOWN`
     * is what the platform substitutes when the OEM left the field unset. Neither is evidence FOR
     * the silicon, and "we cannot tell, let the probe decide" is not available here: `nativeProbe`
     * answers whether the HTP *stack* is present, which a Snapdragon the census never measured
     * also answers yes to. The probe cannot tell one Hexagon apart from another, so a gate that
     * defers to it on an unknown part ships a context binary compiled for one architecture to
     * whatever is actually there.
     */
    fun familyFor(socModel: String?, socManufacturer: String?): NpuSocFamily? {
        val model = socModel ?: return null
        val manufacturer = socManufacturer ?: return null
        if (manufacturer !in SUPPORTED_SOC_MANUFACTURERS) return null
        return NpuFleetCensus.families.firstOrNull { model in it.socModels }
    }

    /**
     * True exactly when [familyFor] resolves a census row — DERIVED, one live expression, so the
     * boolean the offer gate consults and the row the backend stages from can never part company
     * about a device. Everything the 4.0 KDoc promised of this function (exact match, null/unknown
     * deny, manufacturer checked independently) now holds because [familyFor] holds it.
     */
    fun isSocSupported(socModel: String?, socManufacturer: String?): Boolean =
        familyFor(socModel, socManufacturer) != null
}
