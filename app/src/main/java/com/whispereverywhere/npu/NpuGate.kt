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
 * **Full tier visibility is this AND the runtime's own check** — see
 * [com.whispereverywhere.transcription.NpuWhisperBackend.isTierAvailable], which composes
 * `isSocSupported(...) && runtimeAvailable(...)`. This half is deliberately first: it is free,
 * and it means a Tensor or an Exynos never asks any NPU runtime to be told no. Since P2 the second
 * half is dispatched on the row's vendor ([runtimeAvailable]): a Qualcomm row asks the QNN probe,
 * a MediaTek row reads the driver check's STORED verdict — so a MediaTek device never dlopens a
 * Qualcomm backend, and never walks its own adapter on a chooser's path either.
 *
 * WHAT GREW IN 4.2: the gate stopped being an owner-device allowlist and became the reader of the
 * fleet census. [familyFor] resolves a device to its [NpuSocFamily] row — the row everything
 * per-family downstream consumes — and [isSocSupported] is DERIVED from it, so the offer gate and
 * the family resolution can never disagree about a device. The strings themselves have one home,
 * [NpuFleetCensus]; nothing in this file spells one out — since P2 not the manufacturer spellings
 * either, which moved onto the rows ([NpuSocFamily.manufacturers]).
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
     * Every `Build.SOC_MANUFACTURER` spelling some census row admits — DERIVED, the union of the
     * rows' own [NpuSocFamily.manufacturers], never a hand-typed list (P2). It was the Qualcomm
     * pair `{QTI, Qualcomm}` typed here until the manufacturer moved onto the row, and it
     * survives only for the one reader that needs the fleet-wide set: the device-group XML's
     * equality pin, which holds every spelling the XML names equal to this. [familyFor] never
     * reads it — it asks the ROW, because a spelling one vendor ships says nothing about another
     * vendor's silicon.
     */
    val SUPPORTED_SOC_MANUFACTURERS: Set<String> =
        NpuFleetCensus.families.flatMap { it.manufacturers }.toSet()

    /**
     * The census row whose [NpuSocFamily.socModels] carries this exact string, under a
     * manufacturer spelling THAT ROW admits ([NpuSocFamily.manufacturers]) — or null, which is
     * the deny. (Since P2 the row decides its manufacturer: `SM8650` under `Mediatek` and
     * `MT6989` under `QTI` both deny, where one global Qualcomm set could only ask "Qualcomm?".)
     *
     * **Matching is EXACT, and every "helpful" relaxation of that is a way to ship the wrong
     * binary to the wrong silicon.** This is now a FLEET rule, not an owner-device rule — six
     * families wide since 2026-09-24 (SM8450 was the latest string in), and wider only by census
     * edit. Not a prefix match — that also accepts every
     * future superstring part nobody has run. Not `equals(ignoreCase = true)` — `Build.SOC_MODEL`
     * is a vendor field, and a device whose OEM spells a part differently is a device we have not
     * seen, which is the whole population this function exists to keep out. Exact matching also
     * means the census must name what devices REPORT: it once named `SM8750-AC` and `SM8850-AD`,
     * catalog aliases no phone reports, and this function then correctly — exactly — denied the
     * whole Galaxy S25 and S26 generations (maintenance rule 1, [NpuFleetCensus]).
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
        return NpuFleetCensus.families.firstOrNull { model in it.socModels && manufacturer in it.manufacturers }
    }

    /**
     * True exactly when [familyFor] resolves a census row — DERIVED, one live expression, so the
     * boolean the offer gate consults and the row the backend stages from can never part company
     * about a device. Everything the 4.0 KDoc promised of this function (exact match, null/unknown
     * deny, manufacturer checked independently) now holds because [familyFor] holds it.
     */
    fun isSocSupported(socModel: String?, socManufacturer: String?): Boolean =
        familyFor(socModel, socManufacturer) != null

    /**
     * THE CAPABILITY HALF, dispatched on the row's vendor (P2; design §2.2) — a pure function of
     * what each vendor's check answered, so the dispatch is a truth table a JVM test executes:
     *
     *  - **no family** — false, and nothing is asked: a device off the census never runs a probe;
     *  - **Qualcomm** — [qnnProbePasses], the QNN dlopen probe, exactly as before P2. The lambda
     *    is invoked on this arm and no other, which is what keeps a MediaTek device from ever
     *    dlopening `libQnnHtp.so`;
     *  - **MediaTek** — [apuVerdict], the driver check's STORED verdict ([NpuApuDriverCheck]);
     *    never a dlopen here, because this is forced on the onboarding and chooser paths and the
     *    adapter walk holds bionic's loader lock. Null is "unknown — not probed yet" and answers
     *    false: not-yet-capable, never an optimistic yes that a later probe has to take back.
     *
     * @param family [familyFor]'s answer for this device.
     * @param qnnProbePasses the QNN probe (`QnnAsrEngine().probe(libDir)` is `""`), deferred so
     *        only the Qualcomm arm pays it.
     * @param apuVerdict [NpuApuDriverCheck.verdict]'s current value.
     */
    fun runtimeAvailable(
        family: NpuSocFamily?,
        qnnProbePasses: () -> Boolean,
        apuVerdict: NpuApuVerdict?,
    ): Boolean = when (family?.vendor) {
        null -> false
        NpuVendor.QUALCOMM -> qnnProbePasses()
        NpuVendor.MEDIATEK -> apuVerdict?.passed == true
    }
}
