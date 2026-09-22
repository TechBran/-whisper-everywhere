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
 *   and taken from what DEVICES REPORT, never from a spec sheet or a vendor catalog's alias
 *   (maintenance rule 1 in the [NpuFleetCensus] KDoc).
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
 * One published file inside one family's model-pair package: the DELIVERY name it lands on a
 * device under (the catalog's, never the vendor's shared bare name), its exact measured byte
 * length, and the sha256 the copy must hash to. Half of a [PackArtifact].
 */
data class PackEntry(
    val fileName: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * One family's published pair for one tier — the artifact half of the census (4.2 F3). Every
 * arrival route (the SAF import today, the F5 pack install) verifies an incoming pair against
 * THE DEVICE FAMILY'S row, never the reference family's: the same model compiled for a different
 * Hexagon is a different file, and before these rows existed the importer verified every family
 * against the 8gen3 digests — a TRUE refusal for the WRONG stated reason ("corrupted download")
 * on every non-reference device.
 *
 * Every value is MEASURED, by `tools/build_asset_packs.py measure`, which downloads each vendor
 * zip through the pinned release manifest under four gates (HTTP 200; the 2026-08-25
 * Last-Modified re-upload event; the exact zip length; `testzip()` CRC) and asserts the vendor's
 * own `metadata.json` carries this family's HTP version AND the exact IO census of
 * [NpuModelSpec]'s row for the tier — the executed proof that per-SoC packages carry the SAME
 * model. The script embeds these sixteen digests as literals in its own verification table and
 * `NpuFleetCensusTest` pins the two tables together, so the census and the instrument that fills
 * the packs cannot drift apart.
 *
 * @property familyId the [NpuSocFamily.id] this pair is compiled for.
 * @property tierId the catalog tier (`npu`, `npu-turbo`) — written as literals here on purpose:
 *   forcing `WhisperCatalog`'s or `NpuModelSpec`'s `<clinit>` from this object's would re-open
 *   the initialization-order caution `PAIRED_TIER_IDS` documents, and the census test holds the
 *   set equal to the catalog derivation instead.
 * @property vendorZipBytes exact `Content-Length` of the vendor zip, asserted at HEAD on every
 *   measure run — the earliest gate a bucket rewrite can trip.
 * @property encoder the primary context binary, under the tier's catalog `fileName`.
 * @property decoder the paired context binary, under the tier's catalog paired `fileName`.
 * @property evidence when and how this row was measured — a recorded date, never a vibe.
 */
data class PackArtifact(
    val familyId: String,
    val tierId: String,
    val vendorZipBytes: Long,
    val encoder: PackEntry,
    val decoder: PackEntry,
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
 * **1. A string is what a DEVICE REPORTS — and devices report no bin suffix.** Play device
 * targeting and `NpuGate` both match literal `Build.SOC_MODEL` strings, so a row that names a
 * string no device reports lands every capable device in the empty default variant: fail-safe,
 * but lost coverage that nothing anywhere reports. This rule used to read the other way round —
 * "the 8 Elite for Galaxy reports `SM8750-AC`, never plain `SM8750`" — and it was wrong, and it
 * cost the whole Galaxy S25 and S26 generations from 4.2 to 4.12. `SM8750-AC` and `SM8850-AD` are
 * Qualcomm AI Hub CHIPSET ALIASES; `ro.soc.model` is copied from the part's fused chip id, which
 * carries no bin, so an S25 reports `SM8750` exactly as a OnePlus 13 does. Measured 2026-09-22
 * (`docs/measurements/2026-09-22-npu-device-census.md`): every S25/S26-family read is plain, and
 * Play's own device catalog — 25,016 rows, the list Play targets against — holds ZERO suffixed
 * strings. The suffixed spellings stay in their rows because they cost nothing and a firmware
 * could still expose one; the PLAIN string is the one that does the work, in every row.
 * **How to apply:** a new string comes from a real `getprop ro.soc.model` or from the Play
 * catalog's System on Chip column, recorded in the row's [NpuSocFamily.evidence], and the
 * device-group XML is regenerated in the SAME commit — F4's layout pin enforces the agreement, so
 * a census edit that forgets the XML fails loudly instead of shipping a gate and a store that
 * disagree about a device.
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
     * The five families with published w8a16 packages, in the spec table's order. Verified against
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
            evidence = "AI Hub v0.62.2 HEAD-verified 2026-09-22; Last-Modified 2026-09-11; " +
                "device-executed (Fold6) 2026-08-29",
        ),
        // THE GALAXY S25 AND S26 GENERATIONS, reachable at last (4.13.0, 2026-09-22). These two
        // rows used to carry ONLY "SM8750-AC" / "SM8850-AD" — AI Hub's chipset aliases — and no
        // device has ever reported either: every S25, S25+, S25 Ultra, S25 Edge and Z Fold7 read
        // is plain SM8750, every S26, S26 Ultra and Z Fold8 read is plain SM8850, and Play's
        // device catalog lists all of them that way. So from 4.2 to 4.12 both rows matched
        // NOTHING: the gate denied every one of the phones these packs were built for, and Play
        // delivered their variants to no one.
        //
        // THE PLAIN STRING ADMITS EVERY BIN, AND THAT WAS RULED ON. The string cannot tell a
        // Galaxy part from a OnePlus 13's or a Xiaomi 17's, because it is the die's own chip id.
        // Owner ruling 2026-09-22: admit them all. What that rests on: the same die answers to the
        // same QNN soc_model (69 for SM8750, 87 for SM8850) at the same HTP version, the 8gen3 and
        // qcs8550 rows already serve plain and Galaxy bins from one binary (both device-executed),
        // and a context that still refuses to load takes NpuWhisperBackend's loud CPU fallback —
        // `npu: unavailable stage=init` and the card note — never a wrong answer.
        //
        // AND IT WAS THEN EXECUTED, the same day, on real silicon nobody here owns: Qualcomm AI
        // Hub's hosted Galaxy S25 and S26 and, the question the ruling actually turned on, its
        // PLAIN-bin reference phones, "Snapdragon 8 Elite QRD" and "Snapdragon 8 Elite Gen 5
        // QRD". The exact shipped turbo binaries (hashed against this file's digests first)
        // loaded and ran on all four (docs/measurements/2026-09-22-aihub-hosted-device-matrix.md).
        // What that still is not: the app. Delivery, capture, mel and the decode loop have run on
        // no such phone.
        NpuSocFamily(
            id = "8elite_galaxy",
            packGroup = "soc_8elite_galaxy",
            htpVersion = 79,
            socModels = setOf("SM8750", "SM8750-AC"),
            skelAsset = "libQnnHtpV79Skel.so",
            skelBytes = 17_721_548L,
            skelSha256 = "9cad65a621d154e5282ea9d2849d0a8838932ed91dc7e2514db4e992e2d933c6",
            evidence = "AI Hub v0.62.2 HEAD-verified 2026-09-22; Last-Modified 2026-09-11; " +
                "SOC_MODEL read plain SM8750 on S25/S25+/S25 Ultra/S25 Edge/Z Fold7, Play " +
                "catalog agrees (2026-09-22 census, " +
                "docs/measurements/2026-09-22-npu-device-census.md); turbo AI-Hub-executed " +
                "2026-09-22 on Galaxy S25 + plain 8 Elite QRD, both PASS " +
                "(docs/measurements/2026-09-22-aihub-hosted-device-matrix.md); " +
                "no in-app device run",
        ),
        NpuSocFamily(
            id = "8elite5_galaxy",
            packGroup = "soc_8elite5_galaxy",
            htpVersion = 81,
            socModels = setOf("SM8850", "SM8850-AD"),
            skelAsset = "libQnnHtpV81Skel.so",
            skelBytes = 18_844_384L,
            skelSha256 = "b3453265c4574c69bb446bcb98dda117ded531b86b2307e0f02c595050fab8b1",
            evidence = "AI Hub v0.62.2 HEAD-verified 2026-09-22; Last-Modified 2026-09-11; " +
                "SOC_MODEL read plain SM8850 on S26/S26 Ultra/Z Fold8, Play catalog agrees " +
                "(2026-09-22 census, docs/measurements/2026-09-22-npu-device-census.md); " +
                "turbo AI-Hub-executed 2026-09-22 on Galaxy S26 + plain 8 Elite Gen 5 QRD, both " +
                "PASS (docs/measurements/2026-09-22-aihub-hosted-device-matrix.md); " +
                "no in-app device run",
        ),
        NpuSocFamily(
            id = "7gen4",
            packGroup = "soc_7gen4",
            htpVersion = 73,
            socModels = setOf("SM7750"),
            skelAsset = "libQnnHtpV73Skel.so",
            skelBytes = 17_909_588L,
            skelSha256 = "7be4f8a4ec21a9d8d51f59c73094154f42d2f8fc91cfaadaef03441b77d7ddb1",
            evidence = "AI Hub v0.62.2 HEAD-verified 2026-09-22; Last-Modified 2026-09-11; " +
                "turbo AI-Hub-executed 2026-09-22 on the Snapdragon 7 Gen 4 QRD, PASS, the " +
                "tightest family (~48% of the 8 s floor projected) " +
                "(docs/measurements/2026-09-22-aihub-hosted-device-matrix.md); " +
                "no in-app device run",
        ),
        // THE 8 GEN 2 (SM8550) — the S23, S23+ and S23 Ultra, and every other 8 Gen 2 phone.
        //
        // CPU_BY_CENSUS carried this silicon as "no published w8a16 package" from 2026-08-29 and
        // named its own reopening condition: an 8 Gen 2 device turning up for the experiment. Two
        // things then changed. The device turned up, and the question improved: AI Hub v0.62.2
        // publishes a `qcs8550-proxy` package whose metadata reads soc_model 43 / htp_version 73.
        // 43 is the SM8550's OWN number, where the cross-load that ledger rejected was the
        // 7 Gen 4's, compiled for 86. So this is not the curiosity being waved through — it is a
        // different artifact, and the objection does not apply to it.
        //
        // DEVICE-EXECUTED before it was written down (docs/measurements/
        // 2026-09-22-s23-8gen2-qcs8550-spike.md): `npu: offer soc=SM8550:pass probe=pass`, then
        // encode p50 2,472 ms and decode p50 452 over 12 chunks — 37% of the 8 s commit floor,
        // 1.40x the Fold6's cost one HTP generation back. Sentence windows, a speaker change
        // inside a chunk, and live words all worked. It shares 7gen4's V73 skel byte for byte.
        //
        // TURBO ALONE IS WHAT A USER SEES, and it needs nothing from this row. Owner ruling the
        // same day: "we want the Q8 V3 Turbo only. All of the other models should stay hidden,
        // just like we do on the CPU tier models." HIDDEN, and the CPU tier is the precedent he
        // named: 4.8's retired Q5 rungs stayed catalogued and downloadable for anyone who had
        // one, and simply left the chooser. `WhisperCatalog.ONE_TIER_ID` already narrows a
        // capable device's chooser to npu-turbo alone, so this family inherits that by existing.
        //
        // So BOTH tiers are catalogued here, as they are for every other family. An earlier cut
        // of this row omitted the npu artifact to make the small tier undeliverable rather than
        // hidden; WhisperCatalogHelpersTest refused it in so many words — "the pack machinery is
        // untouched by 4.3, which hides a tier from the chooser and nothing else" — and it was
        // right. Deleting coverage is not the same act as hiding a card.
        NpuSocFamily(
            id = "qcs8550",
            packGroup = "soc_qcs8550",
            htpVersion = 73,
            socModels = setOf("SM8550", "SM8550-AC"),
            skelAsset = "libQnnHtpV73Skel.so",
            skelBytes = 17_909_588L,
            skelSha256 = "7be4f8a4ec21a9d8d51f59c73094154f42d2f8fc91cfaadaef03441b77d7ddb1",
            evidence = "AI Hub v0.62.2 HEAD-verified 2026-09-22; Last-Modified 2026-09-11; " +
                "device-executed (S23 Ultra, SM8550) 2026-09-22",
        ),
    )

    /** The row named [id], or null — exact match, the same doctrine as every string in here. */
    fun familyById(id: String): NpuSocFamily? = families.firstOrNull { it.id == id }

    /** Every artifact row's shared measurement record — see [PackArtifact.evidence]. */
    private const val MEASURED = "re-measured 2026-09-22 by build_asset_packs.py measure " +
        "(manifest v0.62.2; Last-Modified 2026-09-11; CRC-clean; vendor metadata htp + IO " +
        "census verified). Every binary digest below REPRODUCES the 2026-08-30 v0.61.0 " +
        "measurement exactly: 0.62.2 is a re-release, not a rebuild, and only the four turbo " +
        "ZIP lengths moved (one byte each, archive wrapper only)"

    /**
     * The artifact census: eight measured pairs — 4 families x 2 tiers, family-major in
     * [families] order, `npu` before `npu-turbo` within a family. The 8gen3 rows ARE the
     * catalog's record (`WhisperCatalog` keeps its constants as the reference family's record —
     * provenance and the published delivery zips — and the census test pins the two records
     * equal, which is what makes them one record).
     *
     * NOTE, measured 2026-08-30 and carried to F5 BY NAME: both 7gen4 ENCODERS sit outside the
     * ±5% tolerance `WhisperModelManager.isInstalled` applies around the catalog's reference
     * record (small 147,595,264 B = +11.0% over 132,927,488; turbo 846,360,576 B = +9.1% over
     * 775,831,552 — HTP v73 packs weights less densely). Until the installed-size gate reads
     * THIS census's per-family bytes, a correct 7gen4 import verifies its copy and then fails
     * the finalise's isInstalled check. No 7gen4 device exists in this program yet; the row is
     * measured truth, and the gate is F5's install-flow work, not a reason to blur the census.
     */
    val artifacts: List<PackArtifact> = listOf(
        PackArtifact(
            familyId = "8gen3",
            tierId = "npu",
            vendorZipBytes = 293_598_974L,
            encoder = PackEntry(
                "encoder_qairt_context.bin", 132_927_488L,
                "3e92ac26545b6b9d22ecfab594ae57523134006e2722b09fa10e16b193e9e5ec",
            ),
            decoder = PackEntry(
                "decoder_qairt_context.bin", 225_316_864L,
                "fda23d731e6b0ab7fb0a50373a49efe2d1792faa5dad456837624d8b8e44b0e4",
            ),
            evidence = "$MEASURED; reproduces the catalog's 4.0 pins exactly (self-check)",
        ),
        PackArtifact(
            familyId = "8gen3",
            tierId = "npu-turbo",
            vendorZipBytes = 859_786_902L,
            encoder = PackEntry(
                "turbo_encoder_qairt_context.bin", 775_831_552L,
                "f7d11c08a20ea671f59b3ace2f9421da00b06170ac9fe946f29092ee59be6bbe",
            ),
            decoder = PackEntry(
                "turbo_decoder_qairt_context.bin", 295_854_080L,
                "c19b067766180843fca6266531605bf037820c5e5ae178bd6dc03785df4c6ae4",
            ),
            evidence = "$MEASURED; reproduces the catalog's 4.1 pins exactly (self-check)",
        ),
        PackArtifact(
            familyId = "8elite_galaxy",
            tierId = "npu",
            vendorZipBytes = 293_117_989L,
            encoder = PackEntry(
                "encoder_qairt_context.bin", 132_333_568L,
                "3001e590274f3377af7f18d33b3f41ab1d573f3e447045bb7a10b516755b9f99",
            ),
            decoder = PackEntry(
                "decoder_qairt_context.bin", 225_234_944L,
                "57aff15b592f1afc2d29d16fb78e6c7b3e80a861a0ecee3838a00884ef040d43",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "8elite_galaxy",
            tierId = "npu-turbo",
            vendorZipBytes = 859_689_780L,
            encoder = PackEntry(
                "turbo_encoder_qairt_context.bin", 775_544_832L,
                "4776799f89514e2e96bd2ccb9a2fb9bdca246bdbeba8c7df84d671e2a6ca024c",
            ),
            decoder = PackEntry(
                "turbo_decoder_qairt_context.bin", 295_821_312L,
                "04f5fe2b77b3bc12f20944401106ba4f878b5275113cba5fbea3ec60d481efaa",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "8elite5_galaxy",
            tierId = "npu",
            vendorZipBytes = 293_798_379L,
            encoder = PackEntry(
                "encoder_qairt_context.bin", 133_554_176L,
                "3c63c40b09374773903855f587bc0530f199a3aa74136fdd4e395c94d258eda5",
            ),
            decoder = PackEntry(
                "decoder_qairt_context.bin", 225_411_072L,
                "a5f6c090a4df6f987e3b47dce04d999fc941f7ef87c5960db8fdf447edc82ab8",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "8elite5_galaxy",
            tierId = "npu-turbo",
            vendorZipBytes = 860_709_425L,
            encoder = PackEntry(
                "turbo_encoder_qairt_context.bin", 777_441_280L,
                "841cecfeade064bed27956401c298a2df86eeaac5c33270a284c34d11619c7a2",
            ),
            decoder = PackEntry(
                "turbo_decoder_qairt_context.bin", 295_911_424L,
                "ceca18cf506f14d8eaf141c69cf7674aca210b825316f0f4c481289cca457430",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "7gen4",
            tierId = "npu",
            vendorZipBytes = 295_361_549L,
            encoder = PackEntry(
                "encoder_qairt_context.bin", 147_595_264L,
                "83a678810bad8b06f3dfab369c2bb87a4ae8aef14cb1886ba3b7a58f7acf2c13",
            ),
            decoder = PackEntry(
                "decoder_qairt_context.bin", 225_382_400L,
                "81c0d683753cd13d98a3a744377e60d180e832f0fd128fe1ecaa8c94890e8069",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "7gen4",
            tierId = "npu-turbo",
            vendorZipBytes = 871_118_305L,
            encoder = PackEntry(
                "turbo_encoder_qairt_context.bin", 846_360_576L,
                "c482288d5899590a87cfea3faea3e39df30242095b8c93e0e02e7d1f1c79a813",
            ),
            decoder = PackEntry(
                "turbo_decoder_qairt_context.bin", 295_895_040L,
                "ce8ad981b89999f4eb9dace8dfb9b64129322e976ac89188a719e59842baacc5",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "qcs8550",
            tierId = "npu",
            vendorZipBytes = 293_600_815L,
            encoder = PackEntry(
                "encoder_qairt_context.bin", 132931584L,
                "b9416b7200e69c715f197e7c5169760483ea39fa30bcae89f9ff6045691523bf",
            ),
            decoder = PackEntry(
                "decoder_qairt_context.bin", 225312768L,
                "0349446e32462ca2923fa8244b32c1272167f1cc1d692153b6efa54777d385ec",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "qcs8550",
            tierId = "npu-turbo",
            vendorZipBytes = 859_787_787L,
            encoder = PackEntry(
                "turbo_encoder_qairt_context.bin", 775_843_840L,
                "785043fbef7a17f80404f17423f97ae0ef1e2a8f4a5d446d413346445d6b9e6d",
            ),
            decoder = PackEntry(
                "turbo_decoder_qairt_context.bin", 295_854_080L,
                "ca70b66c3035a35af78ed43b488ff201d30413edde59f5832208925da080a4b2",
            ),
            evidence = MEASURED,
        ),
    )

    /**
     * The measured pair for this family and tier, or **null** — and null MUST refuse: a device
     * whose family is unknown (or whose family has no measured row for the tier) cannot verify
     * NPU bytes, and `requiredEntriesFor`'s empty map is how that refusal fires.
     */
    fun artifactFor(familyId: String, tierId: String): PackArtifact? =
        artifacts.firstOrNull { it.familyId == familyId && it.tierId == tierId }

    /**
     * The gated tiers a CHOOSER may offer to fetch from Play for this device (4.2 F6) — pure,
     * so the truth table is executable: with a resolved [family] and a passing capability probe,
     * every id in [gatedTierIds] the family has a measured [artifactFor] row for, minus
     * [installedGatedIds] (an installed tier is OFFERED, never fetchable); anything less than
     * that — no family, probe failed — answers empty. That emptiness is the whole non-capable
     * fleet's answer, and it is why this function cannot change their chooser by a byte: the
     * chooser's set is offered UNION fetchable, and union with the empty set is the identity.
     *
     * DISPLAY/STEER ONLY, never routing: a fetchable tier has nothing on disk to run. The
     * routing gate stays `WhisperEverywhereApp.offeredNpuTierIds` (installed AND capable), and
     * nothing that routes a session reads this set — pinned by name in
     * `ChooserSteerWiringPinTest`.
     */
    fun fetchableTierIds(
        family: NpuSocFamily?,
        capable: Boolean,
        gatedTierIds: Set<String>,
        installedGatedIds: Set<String>,
    ): Set<String> {
        if (family == null || !capable) return emptySet()
        val deliverable = gatedTierIds.filterTo(mutableSetOf()) { artifactFor(family.id, it) != null }
        return deliverable - installedGatedIds
    }

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
        // SM8550 / SM8550-AC LEFT THIS LEDGER on 2026-09-22 for the qcs8550 family. Their lines
        // read "no published w8a16 package as of 2026-08-29", which was true and stopped being
        // true at AI Hub v0.62.2. An entry moving OUT of here is a measurement with a date —
        // maintenance rule 2 — and this one is device-executed, not just HEAD-verified.
        "SM8475" to "8+ Gen 1 — no published w8a16 package as of 2026-08-29 " +
            "(both release manifests re-fetched)",
        "SM8450" to "8 Gen 1 — no published w8a16 package as of 2026-08-29 " +
            "(both release manifests re-fetched)",
        "SM8350" to "888 — no published w8a16 package as of 2026-08-29 " +
            "(both release manifests re-fetched)",
        // SM8750 / SM8850 LEFT THIS LEDGER on 2026-09-22 for the 8elite_galaxy / 8elite5_galaxy
        // rows. Their lines read "non-Galaxy 8 Elite — no published w8a16 package", which
        // mistook a plain string for a plain BIN: the plain string is what the Galaxy phones
        // report too (see the rows' comment), so it was never evidence of an uncovered part.
    )
}
