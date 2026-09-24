package com.whispereverywhere.npu

/**
 * One family of Qualcomm silicon with a published w8a16 package: the strings that identify it, the
 * HTP architecture its context binaries are compiled for, and the skel that architecture needs.
 * One row of [NpuFleetCensus.families] — and the one object everything per-family downstream reads:
 * the gate resolves a device to a row, the backend stages the row's skel, the pack build and the
 * device-group XML are generated from the row's group and strings.
 *
 * @property id the census key (`8gen3`, `8elite_galaxy`, `8elite5_galaxy`, `7gen4`, `qcs8550`,
 *   `8gen1`). The vendor's release manifest indexes the same silicon by a different string
 *   (`8-elite-for-galaxy`); that mapping belongs to the artifact rows (F3), never to this field.
 * @property packGroup the Play device-group name this family's pack variant ships under. The
 *   device-group XML must carry exactly [socModels] under exactly this name — F4's layout pin
 *   holds the two files equal, which is what gives maintenance rule 1 its teeth.
 * @property htpVersion the Hexagon Tensor Processor architecture version the family's context
 *   binaries are compiled for. Not ordinal across rows in any useful sense: v73 serves both the
 *   8 Gen 2 (2023) and the 7 Gen 4 (2025), and the oldest architecture, v69, arrived last.
 * @property socModels every `Build.SOC_MODEL` string known to name this silicon — exact, complete,
 *   and taken from what DEVICES REPORT, never from a spec sheet or a vendor catalog's alias
 *   (maintenance rule 1 in the [NpuFleetCensus] KDoc).
 * @property skelAsset the packaged asset name of the skel this family stages at arm time (F2).
 * @property skelBytes exact byte length of [skelAsset], measured out of `qnn-runtime-2.50.0.aar`.
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
 * zip through the pinned release manifest under four gates (HTTP 200; the pinned Last-Modified
 * re-upload event, 2026-09-23 at v0.63.0; the exact zip length; `testzip()` CRC) and asserts the
 * vendor's own `metadata.json` carries this family's HTP version AND the exact IO census of
 * [NpuModelSpec]'s row for the tier — the executed proof that per-SoC packages carry the SAME
 * model. The script embeds every row's digests as literals in its own verification table and
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
 * sixfold: a QAIRT context binary handed to the wrong HTP architecture does not degrade — it
 * fails to deserialise, or worse, it does not. A string joins a row's [NpuSocFamily.socModels]
 * when evidence says that exact string names silicon the family's binaries were compiled for, and
 * a new family joins [families] when the vendor publishes a package for it — never because a
 * part number looks close. The sixth, `8gen1`, joined that way on 2026-09-24: AI Hub v0.63.0 was
 * the first release to publish its chipset key, and the vendor metadata names the SM8450's own
 * `soc_model` 36.
 */
object NpuFleetCensus {

    /**
     * The six families with published w8a16 packages: the spec table's four in its order, then
     * `qcs8550` (2026-09-22) and `8gen1` (2026-09-24) in the order they joined. First verified
     * against the live release manifests and the live asset bucket on 2026-08-29 (research doc
     * `2026-08-29-pad-soc-delivery.md` §7); all six re-verified at AI Hub v0.63.0 on 2026-09-24,
     * and every skel row re-measured out of `qnn-runtime-2.50.0.aar` the same day. The 4.1-shipped
     * V75 pin (17,913,608 B, `a56519d6…`) did NOT survive that bump: every skel is a different blob
     * at 2.50, which is what a runtime version bump means.
     *
     * WHAT THE EVIDENCE LINES CAN AND CANNOT SAY SINCE 2026-09-24. Each device or AI Hub execution
     * below ran the PREVIOUS bytes — v0.61.0/v0.62.2 binaries built with QAIRT 2.45, under the 2.49
     * runtime in the app. The v0.63.0 binaries are QAIRT 2.50 builds under a 2.50 runtime, and
     * none of them has run in this program yet, in an AI Hub job of ours or in the app (the
     * vendor's own AI Hub profiles are the vendor's), which is why every line now says so rather
     * than letting an old execution vouch for new files.
     */
    val families: List<NpuSocFamily> = listOf(
        NpuSocFamily(
            id = "8gen3",
            packGroup = "soc_8gen3",
            htpVersion = 75,
            socModels = setOf("SM8650", "SM8650-AC"),
            skelAsset = "libQnnHtpV75Skel.so",
            skelBytes = 18_693_300L,
            skelSha256 = "3e9774b74769915b4f54364f8fc25887b3439561a970dca57c9f4dc9612b38af",
            evidence = "AI Hub v0.63.0 HEAD-verified 2026-09-24; Last-Modified 2026-09-23; " +
                "device-executed (Fold6) 2026-08-29 on the v0.61.0 pair under QNN 2.49; the " +
                "v0.63.0 pair under QNN 2.50 not yet executed",
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
        // QRD". The turbo binaries of that day, v0.62.2's, hashed first against the digests the
        // census then carried, loaded and ran on all four
        // (docs/measurements/2026-09-22-aihub-hosted-device-matrix.md). The v0.63.0 binaries
        // this file's digests name now have run on none of them: that matrix run is re-planned
        // in tools/aihub_matrix.py and still pending. And neither run is the app. Delivery,
        // capture, mel and the decode loop have run on no such phone.
        NpuSocFamily(
            id = "8elite_galaxy",
            packGroup = "soc_8elite_galaxy",
            htpVersion = 79,
            socModels = setOf("SM8750", "SM8750-AC"),
            skelAsset = "libQnnHtpV79Skel.so",
            skelBytes = 18_513_604L,
            skelSha256 = "860c9d2e7c937c9fb8f8f18daa9a79cab6c566066a2d36f235f6c8708fdc75bd",
            evidence = "AI Hub v0.63.0 HEAD-verified 2026-09-24; Last-Modified 2026-09-23; " +
                "SOC_MODEL read plain SM8750 on S25/S25+/S25 Ultra/S25 Edge/Z Fold7, Play " +
                "catalog agrees (2026-09-22 census, " +
                "docs/measurements/2026-09-22-npu-device-census.md); the v0.62.2 turbo " +
                "AI-Hub-executed 2026-09-22 on Galaxy S25 + plain 8 Elite QRD, both PASS " +
                "(docs/measurements/2026-09-22-aihub-hosted-device-matrix.md), the v0.63.0 " +
                "pair not yet; no in-app device run",
        ),
        NpuSocFamily(
            id = "8elite5_galaxy",
            packGroup = "soc_8elite5_galaxy",
            htpVersion = 81,
            socModels = setOf("SM8850", "SM8850-AD"),
            skelAsset = "libQnnHtpV81Skel.so",
            skelBytes = 19_708_192L,
            skelSha256 = "02047c9fef8a22801c0eefaa79188e87b600372c9813dea3f621ba256d1ddce0",
            evidence = "AI Hub v0.63.0 HEAD-verified 2026-09-24; Last-Modified 2026-09-23; " +
                "SOC_MODEL read plain SM8850 on S26/S26 Ultra/Z Fold8, Play catalog agrees " +
                "(2026-09-22 census, docs/measurements/2026-09-22-npu-device-census.md); " +
                "the v0.62.2 turbo AI-Hub-executed 2026-09-22 on Galaxy S26 + plain 8 Elite " +
                "Gen 5 QRD, both PASS (docs/measurements/2026-09-22-aihub-hosted-device-matrix.md), " +
                "the v0.63.0 pair not yet; no in-app device run",
        ),
        NpuSocFamily(
            id = "7gen4",
            packGroup = "soc_7gen4",
            htpVersion = 73,
            socModels = setOf("SM7750"),
            skelAsset = "libQnnHtpV73Skel.so",
            skelBytes = 18_709_712L,
            skelSha256 = "024a0aea3d8d44fc5b59ffab20bde4348d07d05ad7d23f27c8bd06aa3d240d8a",
            evidence = "AI Hub v0.63.0 HEAD-verified 2026-09-24; Last-Modified 2026-09-23; " +
                "the v0.62.2 turbo AI-Hub-executed 2026-09-22 on the Snapdragon 7 Gen 4 QRD, " +
                "PASS, the tightest family (~48% of the 8 s floor projected) " +
                "(docs/measurements/2026-09-22-aihub-hosted-device-matrix.md), the v0.63.0 " +
                "pair not yet; no in-app device run",
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
        // (Those numbers are the v0.62.2 pair's, QAIRT 2.45 under QNN 2.49. The v0.63.0 pair is a
        // QAIRT 2.50 rebuild and has not run on the S23 yet.)
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
            skelBytes = 18_709_712L,
            skelSha256 = "024a0aea3d8d44fc5b59ffab20bde4348d07d05ad7d23f27c8bd06aa3d240d8a",
            evidence = "AI Hub v0.63.0 HEAD-verified 2026-09-24; Last-Modified 2026-09-23; " +
                "device-executed (S23 Ultra, SM8550) 2026-09-22 on the v0.62.2 pair under QNN " +
                "2.49; the v0.63.0 pair under QNN 2.50 not yet executed",
        ),
        // THE 8 GEN 1 (SM8450) — the Galaxy S22, S22+ and S22 Ultra (Snapdragon builds), the
        // Galaxy Tab S8, S8+ and S8 Ultra, and the S23 FE's Snapdragon build. The sixth family,
        // and the only one on HTP v69.
        //
        // CPU_BY_CENSUS carried SM8450 as "no published w8a16 package" from 2026-08-29, and that
        // was true until AI Hub v0.63.0: the first release to publish a `qualcomm-snapdragon-8gen1`
        // key for either Whisper model. The vendor metadata of both packs reads htp_version 69 and
        // soc_model 36, with "sm8450" among the chipset's aliases and "Samsung Galaxy S22 (Family)"
        // as its reference device, and the measure run held both to the same gates as every other
        // family — the graph-IO census equal to NpuModelSpec's rows is what says it is our model
        // compiled for this Hexagon, not another model.
        //
        // THE STRING IS THE PLAIN ONE, and it is the only one: SM8450 is what these phones report
        // (maintenance rule 1). Play's device catalog export (the 25,016-row snapshot the
        // 2026-09-22 census read) has 67 rows for it, every one spelled "QTI SM8450": 15 Samsung
        // (the S22s, the Tab S8s, the S23 FE) and 52 from Sony, nubia, vivo, Motorola, Xiaomi,
        // OnePlus and others. The string is the die's own, so the row admits all 67 — the same
        // footing as the 2026-09-22 ruling for the plain 8 Elite strings: one die, one soc_model,
        // and the loud CPU fallback behind any context that still refuses. The 8+ Gen 1 (SM8475)
        // is a different die with its own soc_model and no published package under any key, so it
        // stays in the CPU ledger — sharing an HTP version is not sharing a binary. Nor is the
        // Galaxy XR's v69 (SXR2230P, soc_model 53): it reports its own part string and matches
        // nothing here.
        //
        // What this row does NOT have yet: any execution at all. No v69 binary has run in this
        // program, on AI Hub or on a device; the owner's Tab S8 is the first in-app check.
        NpuSocFamily(
            id = "8gen1",
            packGroup = "soc_8gen1",
            htpVersion = 69,
            socModels = setOf("SM8450"),
            skelAsset = "libQnnHtpV69Skel.so",
            skelBytes = 12_529_660L,
            skelSha256 = "262f3e8807ea969cfc446ea8717500475ea1ea1be6205201486a5431ffcb490e",
            evidence = "AI Hub v0.63.0 HEAD-verified 2026-09-24 (release_assets.json at 0.63.0, " +
                "qualcomm-snapdragon-8gen1 w8a16, tool_versions.qairt 2.50.0.260828221209; " +
                "vendor metadata htp 69 / soc_model 36); Last-Modified 2026-09-23; Play catalog " +
                "67 rows 'QTI SM8450' (15 Samsung); not yet executed in this program, in an " +
                "AI Hub job of ours or on any device",
        ),
    )

    /** The row named [id], or null — exact match, the same doctrine as every string in here. */
    fun familyById(id: String): NpuSocFamily? = families.firstOrNull { it.id == id }

    /** Every artifact row's shared measurement record — see [PackArtifact.evidence]. */
    private const val MEASURED = "re-measured 2026-09-24 by build_asset_packs.py measure " +
        "(manifest v0.63.0, QAIRT 2.50.0.260828221209; Last-Modified 2026-09-23; CRC-clean; " +
        "vendor metadata htp + IO census verified). A REBUILD, not a re-release: no 0.62.2 " +
        "digest reproduces, every encoder is 11.5-22.1% smaller and every decoder within 0.06%, " +
        "and the graph IO census is unchanged on every pack"

    /**
     * The 8gen1 rows' measurement record. Not [MEASURED]: that string compares against 0.62.2,
     * and this family had no 0.62.2 package, so "no 0.62.2 digest reproduces" would be vacuous
     * and "every encoder is smaller" a comparison with nothing.
     */
    private const val MEASURED_8GEN1 = "measured 2026-09-24 by build_asset_packs.py measure " +
        "(manifest v0.63.0, QAIRT 2.50.0.260828221209; Last-Modified 2026-09-23; CRC-clean). " +
        "First published at v0.63.0, so there is no earlier package to compare; the gate read " +
        "vendor metadata htp 69 and an IO census equal to NpuModelSpec, and soc_model 36 was " +
        "read by hand from the same metadata.json. Not device-executed in this program: no " +
        "v69 binary has run in an AI Hub job of ours or in the app"

    /**
     * The artifact census: twelve measured pairs — 6 families x 2 tiers, family-major in
     * [families] order, `npu` before `npu-turbo` within a family. The 8gen3 rows ARE the
     * catalog's record (`WhisperCatalog` keeps its constants as the reference family's record —
     * provenance and the published delivery zips — and the census test pins the two records
     * equal, which is what makes them one record).
     *
     * NOTE, measured 2026-08-30 and carried to F5 BY NAME: both 7gen4 ENCODERS sat outside the
     * ±5% tolerance `WhisperModelManager.isInstalled` applies around the catalog's reference
     * record (small 147,595,264 B = +11.0% over 132,927,488; turbo 846,360,576 B = +9.1% over
     * 775,831,552). F5 fixed the gate rather than the census: it reads THIS census's per-family
     * bytes (`NpuAssetImport.installedGateBytes`), so a correct import of any family's pair reads
     * as installed whatever the reference says.
     *
     * AT v0.63.0 (2026-09-24) THAT FACT DISSOLVED, and a new one replaced it. Every family's
     * encoders now sit inside the band around the new reference — the widest is 7gen4 turbo at
     * +2.6% — so the per-family gate is no longer what rescues a family. What it does instead is
     * refuse the OLD pairs: an 0.62.2 encoder on disk (775,831,552 B on an 8 Gen 3) is 13% over
     * this census's 686,112,520 and fails `isInstalled`, so a phone that updates to 4.15 sees its
     * NPU tier as not installed until the v0.63.0 pack is fetched. That is the refresh arriving,
     * not a defect — the old context binaries would still load under 2.50, but they are the slow
     * ones.
     */
    val artifacts: List<PackArtifact> = listOf(
        PackArtifact(
            familyId = "8gen3",
            tierId = "npu",
            vendorZipBytes = 285_197_039L,
            encoder = PackEntry(
                "encoder_qairt_context.bin", 113_123_776L,
                "813d0e847bf1ba21b991a421a2f57f56884252d1ca6e780a02a55582c519bac0",
            ),
            decoder = PackEntry(
                "decoder_qairt_context.bin", 225_298_736L,
                "bd853be4710bb0aa01dd2a5ce78c03f3e9f722cab47fad3ac24555995f21a929",
            ),
            evidence = "$MEASURED; the catalog's reference record since 4.15, moved to " +
                "these values from this measurement. A rebuild cannot reproduce the 4.0 " +
                "pins it replaced, so the self-check is the second measure run " +
                "reproducing every row",
        ),
        PackArtifact(
            familyId = "8gen3",
            tierId = "npu-turbo",
            vendorZipBytes = 823_721_812L,
            encoder = PackEntry(
                "turbo_encoder_qairt_context.bin", 686_112_520L,
                "c9403eaa9c4b4313419d650e316be7cc1c9020cd8cd716ed909ddb0b61f0886a",
            ),
            decoder = PackEntry(
                "turbo_decoder_qairt_context.bin", 295_856_032L,
                "a5597486dd53a0847fa042588279d6ab58f736078ea133c513b15e5d8c39d241",
            ),
            evidence = "$MEASURED; the catalog's reference record since 4.15, moved to " +
                "these values from this measurement. A rebuild cannot reproduce the 4.1 " +
                "pins it replaced, so the self-check is the second measure run " +
                "reproducing every row",
        ),
        PackArtifact(
            familyId = "8elite_galaxy",
            tierId = "npu",
            vendorZipBytes = 285_116_926L,
            encoder = PackEntry(
                "encoder_qairt_context.bin", 113_091_008L,
                "e4b24b7b6b5ba333926660f213836fe199160eb0e6d4e9a0d0153c6d0f0c9abf",
            ),
            decoder = PackEntry(
                "decoder_qairt_context.bin", 225_151_280L,
                "076cd7b4a5dc0c3b9958dd839d104d02e247e1ae36b7aaa7f53cf1adae891b10",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "8elite_galaxy",
            tierId = "npu-turbo",
            vendorZipBytes = 823_685_860L,
            encoder = PackEntry(
                "turbo_encoder_qairt_context.bin", 685_997_832L,
                "a72593f052fa9a4fb589bdc3dfe520860ea5684369afde6444198385f0e60ef5",
            ),
            decoder = PackEntry(
                "turbo_decoder_qairt_context.bin", 295_765_920L,
                "2f9aafff7a15d779aef799fd5a25ac02a17b6455b7a4d293964cbed7e25fe7c8",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "8elite5_galaxy",
            tierId = "npu",
            vendorZipBytes = 285_450_230L,
            encoder = PackEntry(
                "encoder_qairt_context.bin", 113_770_944L,
                "8ee815ece1b4a3a72b67c6bb8753efe7076568e364ef2cb4bafd6764f5267757",
            ),
            decoder = PackEntry(
                "decoder_qairt_context.bin", 225_290_544L,
                "c117a5cf414986b7bb3b725c676020203430757437dd885b749958be4146fa6c",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "8elite5_galaxy",
            tierId = "npu-turbo",
            vendorZipBytes = 824_020_866L,
            encoder = PackEntry(
                "turbo_encoder_qairt_context.bin", 687_283_976L,
                "5f5ff7cf77932ddb56654f2c1083e03cdb88c2b3fa0bd8d0910caceaec775235",
            ),
            decoder = PackEntry(
                "turbo_decoder_qairt_context.bin", 295_847_840L,
                "b6be63f758903403105efc764be5cf807c36f4d0f81e31b4f3a50c71fc249138",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "7gen4",
            tierId = "npu",
            vendorZipBytes = 285_697_544L,
            encoder = PackEntry(
                "encoder_qairt_context.bin", 115_028_408L,
                "32e1715cb6abd92d6f3f2a770d56ea246b2ca61b073da964268ed0d4bd5a43d7",
            ),
            decoder = PackEntry(
                "decoder_qairt_context.bin", 225_397_032L,
                "4ff5870ef2317d00935eea05ef3b86f0008c816e175ab8bff9dabd034b91efd7",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "7gen4",
            tierId = "npu-turbo",
            vendorZipBytes = 828_034_458L,
            encoder = PackEntry(
                "turbo_encoder_qairt_context.bin", 703_946_504L,
                "6489b59b08c9a62c796ecec371def7850e1207b0a54299d81f0344021afba87f",
            ),
            decoder = PackEntry(
                "turbo_decoder_qairt_context.bin", 295_917_472L,
                "26abee5f364552a0431beabd1f8c2104bfc71a8b7cfcce63189df5efae5d56be",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "qcs8550",
            tierId = "npu",
            vendorZipBytes = 285_198_646L,
            encoder = PackEntry(
                "encoder_qairt_context.bin", 113_127_872L,
                "1ff1c6aa917aa3865ed101635cd1c37222c8d480244c051572e6e07ab7b00bb2",
            ),
            decoder = PackEntry(
                "decoder_qairt_context.bin", 225_298_736L,
                "856707dc6e78da42480d45c61432c35fae8c47184873ca40ac45b4bd4b95f7c2",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "qcs8550",
            tierId = "npu-turbo",
            vendorZipBytes = 823_697_212L,
            encoder = PackEntry(
                "turbo_encoder_qairt_context.bin", 686_108_424L,
                "16eeb01fcedf147fc55ad2c4cde6b7c7b98c87f1d70ad63d3c11e0245ea567b7",
            ),
            decoder = PackEntry(
                "turbo_decoder_qairt_context.bin", 295_847_840L,
                "ec6889bdca25b27c1758136c70292cd3274825c6798896507279cd9b37bfbdfe",
            ),
            evidence = MEASURED,
        ),
        PackArtifact(
            familyId = "8gen1",
            tierId = "npu",
            vendorZipBytes = 284_581_383L,
            encoder = PackEntry(
                "encoder_qairt_context.bin", 111_915_456L,
                "fb60f44b26b9fd918fbde33b3c4cee30ca79e06e959e496fb63eda8d805709ec",
            ),
            decoder = PackEntry(
                "decoder_qairt_context.bin", 223_562_032L,
                "810557e909a44a1f7ea3889421fbd8c1385a29110f49b962b29b50a48070169f",
            ),
            evidence = MEASURED_8GEN1,
        ),
        PackArtifact(
            familyId = "8gen1",
            tierId = "npu-turbo",
            vendorZipBytes = 821_903_663L,
            encoder = PackEntry(
                "turbo_encoder_qairt_context.bin", 681_574_152L,
                "2005e39cd6d94c7b66f63832b9d0ba182b0b322876d3f579a826e8edcc74168e",
            ),
            decoder = PackEntry(
                "turbo_decoder_qairt_context.bin", 294_692_768L,
                "c5bb0775b19afb1f7b115c231aaaf78d003479228b3e905181fe0436c87b5fc2",
            ),
            evidence = MEASURED_8GEN1,
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
     * an assertion attached. A reader who wonders why the 8+ Gen 1 is not in [families] gets the
     * answer HERE, with a date: the absence was CHECKED, not overlooked. The tests hold this map
     * disjoint from every family's [NpuSocFamily.socModels] and prove `NpuGate.familyFor` answers
     * null for every key, so the ledger can never quietly contradict the census it annotates.
     *
     * These devices answer CPU — no NPU UI, no pack fetch, the CPU tiers exactly as shipped.
     *
     * **The cross-load curiosity stayed OUT, and was never needed.** The 7 Gen 4's binaries are
     * compiled for HTP v73 — the same architecture as the 8 Gen 2 — but for the vendor's
     * `soc_model` 86, and whether they load on an SM8550 is unverified
     * (`QNN_COMMON_ERROR_INCOMPATIBLE_BINARIES` risk; research §7 names it a curiosity, not a
     * claim). The 8 Gen 2 left this map on 2026-09-22 by a different route — a package compiled
     * for its own soc_model 43 — and the 8 Gen 1 on 2026-09-24 the same way (soc_model 36). That
     * is the pattern: an entry moves OUT of here when the vendor publishes for the part's OWN
     * soc_model and the measurement says so, with a date — maintenance rule 2, in the object KDoc
     * above. Same-architecture cross-loads stay out.
     */
    val CPU_BY_CENSUS: Map<String, String> = mapOf(
        // SM8550 / SM8550-AC LEFT THIS LEDGER on 2026-09-22 for the qcs8550 family. Their lines
        // read "no published w8a16 package as of 2026-08-29", which was true and stopped being
        // true at AI Hub v0.62.2. An entry moving OUT of here is a measurement with a date —
        // maintenance rule 2 — and this one is device-executed, not just HEAD-verified.
        // RE-CHECKED 2026-09-24 against both v0.63.0 manifests: they publish fifteen w8a16
        // chipset keys and none of them is the 8+ Gen 1 or the 888, so both lines below stand as
        // written. (The lines keep their 2026-08-29 dates because that is when the absence was
        // first recorded; this comment is the re-check.)
        "SM8475" to "8+ Gen 1 — no published w8a16 package as of 2026-08-29 " +
            "(both release manifests re-fetched)",
        // SM8450 LEFT THIS LEDGER on 2026-09-24 for the 8gen1 family. Its line read "8 Gen 1 —
        // no published w8a16 package as of 2026-08-29", which was true through v0.62.2 and
        // stopped being true at AI Hub v0.63.0, the first release with a
        // `qualcomm-snapdragon-8gen1` key. Measured, not inferred, by two different hands: the
        // measure run's metadata gate read htp 69 and the IO census equal to both spec rows, and
        // soc_model 36 was read by hand out of the same vendor metadata.json (2026-09-24). The
        // gate did not check soc_model on that run. It does since, from FAMILIES' soc_model
        // column in tools/build_asset_packs.py, and no run has executed that check yet.
        "SM8350" to "888 — no published w8a16 package as of 2026-08-29 " +
            "(both release manifests re-fetched)",
        // SM8750 / SM8850 LEFT THIS LEDGER on 2026-09-22 for the 8elite_galaxy / 8elite5_galaxy
        // rows. Their lines read "non-Galaxy 8 Elite — no published w8a16 package", which
        // mistook a plain string for a plain BIN: the plain string is what the Galaxy phones
        // report too (see the rows' comment), so it was never evidence of an uncovered part.
    )
}
