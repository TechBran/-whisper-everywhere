package com.whispereverywhere.npu

/**
 * One family of Qualcomm silicon with a published w8a16 package: the strings that identify it, the
 * HTP architecture its context binaries are compiled for, and the skel that architecture needs.
 * One row of [NpuFleetCensus.families] — and the one object everything per-family downstream reads:
 * the gate resolves a device to a row, the backend stages the row's skel, the pack build and the
 * device-group XML are generated from the row's group and strings.
 *
 * @property id the census key (`8gen3`, `8elite_galaxy`, `8elite5_galaxy`, `7gen4`). The vendor's
 *   release manifest indexes the same silicon by a different string (`8-elite-for-galaxy`); that
 *   mapping belongs to the artifact rows (F3), never to this field.
 * @property packGroup the Play device-group name this family's pack variant ships under. The
 *   device-group XML must carry exactly [socModels] under exactly this name — F4's layout pin
 *   holds the two files equal, which is what gives maintenance rule 1 its teeth.
 * @property htpVersion the Hexagon Tensor Processor architecture version the family's context
 *   binaries are compiled for. Not ordinal across rows in any useful sense: v73 is both the oldest
 *   architecture in the census and its newest part (the 7 Gen 4).
 * @property socModels every `Build.SOC_MODEL` string known to name this silicon — exact, complete,
 *   suffix bins written out (maintenance rule 1 in the [NpuFleetCensus] KDoc).
 * @property skelAsset the packaged asset name of the skel this family stages at arm time (F2).
 * @property skelBytes exact byte length of [skelAsset], measured out of `qnn-runtime-2.49.0.aar`.
 * @property skelSha256 sha256 of [skelAsset], from the same measurement.
 * @property evidence when and how this row was last verified — a recorded date, never a vibe. A
 *   row whose evidence cannot name a date is a row nobody checked, and the tests refuse it.
 */
data class NpuSocFamily(
    val id: String,
    val packGroup: String,
    val htpVersion: Int,
    val socModels: Set<String>,
    val skelAsset: String,
    val skelBytes: Long,
    val skelSha256: String,
    val evidence: String,
)

/**
 * The fleet census: every SoC family with a published w8a16 package — and, just as deliberately,
 * the parts WITHOUT one ([CPU_BY_CENSUS]). This object is the ONE home of the fleet's soc strings;
 * `NpuGate.SUPPORTED_SOCS` is derived from these rows and the device-group XML is regenerated from
 * them, so there is exactly one census for a device to pass or fail.
 *
 * TWO MAINTENANCE RULES, WITH TEETH.
 *
 * **1. The -AC/-AD trap.** Play device targeting and `NpuGate` both match literal
 * `Build.SOC_MODEL` strings. Samsung bins are DIFFERENT strings for the same silicon — the
 * 8 Elite for Galaxy reports `SM8750-AC`, never plain `SM8750` — and a suffix missing from a row
 * lands a capable device in the empty default variant: fail-safe, but lost coverage that nothing
 * anywhere reports. THIS census is where a suffix gets added, with the measurement recorded in the
 * row's [NpuSocFamily.evidence], and the device-group XML must be regenerated in the SAME commit —
 * F4's layout pin enforces the agreement, so a census edit that forgets the XML fails loudly
 * instead of shipping a gate and a store that disagree about a device.
 *
 * **2. Widening is a measurement, never a guess.** The 4.0 rule, unchanged by the census growing
 * fourfold: a QAIRT context binary handed to the wrong HTP architecture does not degrade — it
 * fails to deserialise, or worse, it does not. A string joins a row's [NpuSocFamily.socModels]
 * when evidence says that exact string names silicon the family's binaries were compiled for, and
 * a fifth family joins [families] when the vendor publishes a package for it — never because a
 * part number looks close.
 */
object NpuFleetCensus {

    /**
     * The four families with published w8a16 packages, in the spec table's order. Verified against
     * the live release manifests and the live asset bucket on 2026-08-29 (research doc
     * `2026-08-29-pad-soc-delivery.md` §7); skel rows measured out of `qnn-runtime-2.49.0.aar` the
     * same day, and the 8gen3 row reproduces the 4.1-shipped pins exactly.
     */
    val families: List<NpuSocFamily> = listOf(
        NpuSocFamily(
            id = "8gen3",
            packGroup = "soc_8gen3",
            htpVersion = 75,
            socModels = setOf("SM8650", "SM8650-AC"),
            skelAsset = "libQnnHtpV75Skel.so",
            skelBytes = 17_913_608L,
            skelSha256 = "a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c",
            evidence = "AI Hub v0.61.0 HEAD-verified 2026-08-29; Last-Modified 2026-08-25; " +
                "device-executed (Fold6) 2026-08-29",
        ),
        NpuSocFamily(
            id = "8elite_galaxy",
            packGroup = "soc_8elite_galaxy",
            htpVersion = 79,
            socModels = setOf("SM8750-AC"),
            skelAsset = "libQnnHtpV79Skel.so",
            skelBytes = 17_721_548L,
            skelSha256 = "9cad65a621d154e5282ea9d2849d0a8838932ed91dc7e2514db4e992e2d933c6",
            evidence = "AI Hub v0.61.0 HEAD-verified 2026-08-29; Last-Modified 2026-08-25; " +
                "no device evidence",
        ),
        NpuSocFamily(
            id = "8elite5_galaxy",
            packGroup = "soc_8elite5_galaxy",
            htpVersion = 81,
            socModels = setOf("SM8850-AD"),
            skelAsset = "libQnnHtpV81Skel.so",
            skelBytes = 18_844_384L,
            skelSha256 = "b3453265c4574c69bb446bcb98dda117ded531b86b2307e0f02c595050fab8b1",
            evidence = "AI Hub v0.61.0 HEAD-verified 2026-08-29; Last-Modified 2026-08-25; " +
                "no device evidence",
        ),
        NpuSocFamily(
            id = "7gen4",
            packGroup = "soc_7gen4",
            htpVersion = 73,
            socModels = setOf("SM7750"),
            skelAsset = "libQnnHtpV73Skel.so",
            skelBytes = 17_909_588L,
            skelSha256 = "7be4f8a4ec21a9d8d51f59c73094154f42d2f8fc91cfaadaef03441b77d7ddb1",
            evidence = "AI Hub v0.61.0 HEAD-verified 2026-08-29; Last-Modified 2026-08-25; " +
                "no device evidence",
        ),
    )

    /** The row named [id], or null — exact match, the same doctrine as every string in here. */
    fun familyById(id: String): NpuSocFamily? = families.firstOrNull { it.id == id }

    /**
     * The parts WITHOUT a published package, each mapped to its evidence line — documentation with
     * an assertion attached. A reader who wonders why the 8 Gen 2 is not in [families] gets the
     * answer HERE, with a date: the absence was CHECKED, not overlooked. The tests hold this map
     * disjoint from every family's [NpuSocFamily.socModels] and prove `NpuGate.familyFor` answers
     * null for every key, so the ledger can never quietly contradict the census it annotates.
     *
     * These devices answer CPU — no NPU UI, no pack fetch, the CPU tiers exactly as shipped.
     *
     * **The cross-load curiosity is OUT.** The 7 Gen 4's binaries are compiled for HTP v73 — the
     * same architecture as the 8 Gen 2 — but for the vendor's `soc_model` 86, and whether they
     * load on an SM8550 is unverified (`QNN_COMMON_ERROR_INCOMPATIBLE_BINARIES` risk; research §7
     * names it a curiosity, not a claim). Its trigger, so the next reader knows what would reopen
     * it: an 8 Gen 2 device materialising for the 30-minute experiment the research describes.
     * Until that experiment runs, the 8 Gen 2 keys stay in this map, and an entry moving OUT of it
     * is a measurement with a date — maintenance rule 2, in the object KDoc above.
     */
    val CPU_BY_CENSUS: Map<String, String> = mapOf(
        "SM8550" to "8 Gen 2 — no published w8a16 package as of 2026-08-29 " +
            "(both release manifests re-fetched)",
        "SM8550-AC" to "8 Gen 2 for Galaxy bin — no published w8a16 package as of 2026-08-29 " +
            "(both release manifests re-fetched)",
        "SM8475" to "8+ Gen 1 — no published w8a16 package as of 2026-08-29 " +
            "(both release manifests re-fetched)",
        "SM8450" to "8 Gen 1 — no published w8a16 package as of 2026-08-29 " +
            "(both release manifests re-fetched)",
        "SM8350" to "888 — no published w8a16 package as of 2026-08-29 " +
            "(both release manifests re-fetched)",
        "SM8750" to "non-Galaxy 8 Elite — no published w8a16 package as of 2026-08-29 " +
            "(both release manifests re-fetched)",
        "SM8850" to "non-Galaxy 8 Elite Gen 5 — no published w8a16 package as of 2026-08-29 " +
            "(both release manifests re-fetched)",
    )
}
