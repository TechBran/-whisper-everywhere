package com.whispereverywhere.model

import com.whispereverywhere.npu.NpuModelSpec
import java.security.MessageDigest
import kotlin.math.abs

/** Language coverage of a whisper model. */
enum class ModelScope { ENGLISH, MULTILINGUAL }

/**
 * A SECOND file a tier needs on disk beside [WhisperModel.fileName]. Deliberately NOT called
 * `companion`: that word already names a Kotlin construct, and this plan uses "companion" for the
 * decoder path handed across the backend seam ([com.whispereverywhere.transcription.ModelPathProvider]).
 *
 * The 4.0 npu tier is the only user: its model is a PAIR of precompiled QAIRT context binaries
 * (encoder + decoder) that ship inside one zip, so no single [WhisperModel.fileName] can describe
 * it. [approxBytes] is this file's own size, checked with the same ±[WhisperCatalog.SIZE_TOLERANCE]
 * gate as the primary; [sha256] is the digest of the EXTRACTED file, not of the zip.
 *
 * @param url provenance of the artefact, i.e. where a human obtains it. For a [WhisperModel.gated]
 *   tier this is not a DownloadManager source — see [WhisperModel.url].
 */
data class PairedArtifact(
    val fileName: String,
    val url: String,
    val sha256: String,
    val approxBytes: Long,
)

/**
 * A downloadable whisper.cpp model tier.
 *
 * @param approxBytes the size the tier ADVERTISES — for a single-file tier this is also the file
 *   on disk, but for a paired tier it is the sum of both files, because that is what the user
 *   actually downloads and what the size badge must state. Never compare a file length to this
 *   without checking [primaryBytes] first.
 * @param sha256 lowercased hex digest of the downloaded file (see WhisperCatalog SHA256_* consts).
 * @param minRamBytes minimum device RAM to *recommend* this model (0 = recommended on any device).
 * @param url where the artefact comes from. Every ggml tier is fetched from this URL by
 *   DownloadManager; a [gated] tier is not fetched at all (npu is SAF-imported from a zip), so its
 *   URL records provenance and nothing hands it to `download()`.
 */
data class WhisperModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val approxBytes: Long,
    val sha256: String,
    val scope: ModelScope,
    /**
     * The width of this model's mel filterbank: **80 for every whisper model before `large-v3`,
     * 128 for `large-v3` and everything distilled from it** (`large-v3-turbo` included). Read off
     * each 128-bin row's own ggml header — `n_mels` is the tenth int32, 40 bytes into the file, so
     * a 48-byte range read settles it without downloading a gigabyte. The 80 default is the whole
     * pre-v3 family and needs no literal per row.
     *
     * **It exists because 4.6 adds the tier the old rule named as its own residual.**
     * [WhisperCatalog.isCpuFallbackEligible] excluded the one 128-bin tier BY NAME (`id != "ultra"`)
     * and said so: *"a future SINGLE-file 128-bin tier would qualify by both clauses, because the
     * catalog records no mel-bin count. Adding one is the real fix and it is a catalog change."*
     * The `large-v3` rung IS that tier — single-file, ungated, pickable, 128-bin — so the count is
     * recorded here and the predicate reads it instead of a name. `pcmToMel` compares the donor
     * model's `whisper_model_n_mels` against the arming tier's declared width and refuses a
     * mismatch (`whisper_jni.cpp:635`), so a 128-bin donor is not a degraded choice, it is a
     * failed load.
     *
     * The name is [com.whispereverywhere.npu.NpuModelSpec.melBins]'s on purpose — one vocabulary
     * for one fact — and the two gated rows take their value FROM that table rather than restating
     * it, so the catalog and the spec cannot disagree about a tier they both describe.
     */
    val melBins: Int = 80,
    val minRamBytes: Long,
    /**
     * **A rung the app OFFERS without ADVOCATING: selectable everywhere, recommended nowhere.**
     * 4.6, owner instruction 2026-09-13 — *"I wanna see all the models there so I can just select
     * between them and try each one."*
     *
     * The owner has six devices and has chosen to measure the CPU ladder rather than accept a
     * prediction scaled from one anchor. An instrument serves that: it appears in the chooser on
     * every device with no RAM threshold hiding it (running a heavy model on a modest phone and
     * finding where it breaks IS the experiment), it downloads like any other ggml rung, and
     * [WhisperCatalog.isRecommendedForDevice] answers **false for it at every RAM** so no card is
     * ever badged with the RAM chip (*"Fits your device"* since 4.9; *"Recommended for your
     * device"* before). It is also never [WhisperCatalog.DEFAULT_MODEL_ID]
     * and never a [ModelMigration] target.
     *
     * Orthogonal to all three flags beside it, and the distinction is the point: [retired] means
     * "we stopped offering this to anyone", [gated] means "this device decides", [unsupported]
     * means "we want you off it". An instrument means **"here it is; we are not telling you it is
     * right."**
     *
     * **Why a flag and not a huge [minRamBytes].** The research proposed the threshold (§6.3,
     * *"set above any shipping phone so no device is ever told it is recommended"*) and it is the
     * wrong instrument twice over. It is not durable — RAM climbs and the literal gets chased —
     * and it makes the card lie: `OnboardingModelScreen` renders an unrecommended RAM-gated tier
     * as *"High-end devices only — this tier needs more RAM than this device reports"*, which on a
     * 16 GB phone offered a 264 MB rung is false. **These rungs are unrecommended because nobody
     * has measured their throughput, which is not a fact about the device in the user's hand.** So
     * an instrument's [minRamBytes] stays 0 and states nothing, and the honest sentence lives in
     * [ModelTierCopy] where the user reads it.
     *
     * The precedent is this repo's own: 4.1 refused `npu-turbo` a promotion while its accuracy
     * claim was unproved and granted it only after the owner's on-device A/B (`ModelTierCopy`'s
     * turbo card). Same shape, one axis over — throughput instead of accuracy. Clearing the flag
     * is what a measured verdict earns.
     *
     * **AND THE VERDICT HAS A HOME: [TierThroughputRecord].** This flag says *"we are not
     * advocating this"*; the record says *what the rung did to the typed text*, with the device,
     * the finalize time, the date and who measured it.
     *
     * **4.7 — THE COUPLING RULE, GENERALISED.** Until 2026-09-17 the instrument set was held equal
     * to the record's UNMEASURED rungs, because "unmeasured" was the only reason a rung was ever
     * offered without advocacy. The Tab S10+ session
     * (`docs/measurements/2026-09-17-tab-cpu-ladder.md`) produced a second reason: `ultra-q8` is
     * MEASURED and kept up, but with no margin (`KeepUp.KEPT_UP_WITHOUT_MARGIN`, worst commit at
     * 0.99 of its floor on a flagship), which does not clear production. So the rule is now
     * **the instrument set equals the pickable rungs whose verdict does not clear production** —
     * unmeasured or measured-without-clearing alike — held by
     * `TierThroughputTest.the_instrument_set_is_the_pickable_rungs_whose_verdict_does_not_clear`.
     * **Clearing this flag on a rung whose verdict does not clear is a red suite**, and so is
     * keeping it on a rung whose verdict does. They are one claim and the flag is the half a
     * reader sees.
     *
     * **4.9 — THE SET IS EMPTY, AND THE FLAG STAYS.** The owner's ruling of 2026-09-17 on
     * `ultra-q8` (*"we definitely wanna keep that one … six to maybe nine second drain time,
     * which is totally manageable and doable"*) is recorded as a `ThroughputVerdict.OwnerRuling`
     * beside its measurement, and a verdict with a ruling clears production — so by the coupling
     * rule the last instrument lost the flag, and no pickable rung carries it. The flag is not
     * deleted: it is what the NEXT rung offered without a clearing verdict wears on the day it
     * enters the chooser, and `WhisperCatalogHelpersTest` still holds every rule about it over
     * a constructed row so the rules cannot rot while the set is empty.
     */
    val instrument: Boolean = false,
    /**
     * A tier that is no longer OFFERED but must remain RESOLVABLE. Removing an entry outright
     * makes [WhisperCatalog.byId] return null for anyone who selected it, which makes
     * `installedModel()` return null, which trips the app-wide gate and force-marches that user
     * into onboarding — with their model file orphaned on disk. Retire; never delete.
     *
     * Retirement alone says nothing about the tier still working. It hides the card from fresh
     * installs and nothing more; see [unsupported] for the stronger claim.
     */
    val retired: Boolean = false,
    /**
     * A retired tier the app also wants users OFF of — the only thing that raises Settings'
     * "This model is no longer supported" migration card ([ModelMigration]). 3.7 splits this out
     * of [retired]: the 60 MB tiers are retired for accuracy (owner decision 2026-08-20) but keep
     * working perfectly for everyone who has one, so prompting them to swap a working 60 MB model
     * for a 190 MB download would be both unrequested and — since pro is SLOWER than eco — a
     * false claim in the card's own copy. extreme/ultra keep both flags: their targets really are
     * faster, so that card stays true.
     */
    val unsupported: Boolean = false,
    /**
     * A tier only SOME devices may be offered — the npu-class tiers (4.0's `npu`, 4.1's
     * `npu-turbo`), which are precompiled QAIRT graphs for one HTP architecture and are
     * meaningless anywhere else. Orthogonal to [retired]: retired means "we stopped offering this
     * to anyone", gated means "this device decides".
     *
     * A gated tier is out of [WhisperCatalog.pickable] unconditionally and only enters the chooser
     * through [WhisperCatalog.pickableFor], whose argument is the caller's gate answer. That split
     * is what keeps the census/copy blast radius to the entries list alone.
     */
    val gated: Boolean = false,
    /**
     * The size of the file at [fileName] specifically. Defaults to [approxBytes], so every
     * single-file tier is untouched and the two are the same number.
     *
     * It exists because `WhisperModelManager.isInstalled` size-gates `models/<fileName>` at ±5%,
     * and npu's [approxBytes] is the PAIR (338,422,512 since the v0.63.0 refresh) while its
     * [fileName] is the encoder alone (113,123,776). Gating the encoder against the sum is 67% out
     * — `isInstalled(npu)` would have been false forever, whatever the owner imported.
     */
    val primaryBytes: Long = approxBytes,
    /** The second file this tier needs on disk, or null for the ordinary single-file tiers. */
    val pairedArtifact: PairedArtifact? = null,
)

/**
 * Pure, Android-free catalog + decision helpers. Everything here is JVM-unit-testable.
 * The Android shell (WhisperModelManager) delegates to these.
 */
object WhisperCatalog {

    // Pinned to an immutable revision (repo sha as of 2026-07-17; content unchanged since
    // 2024-10-29). resolve/main is a MUTABLE ref — any upstream file replacement would make
    // every APK-pinned sha256 fail and brick downloads until an app update. A commit sha can
    // never change out from under us.
    private const val BASE_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/"

    /** Allowed deviation of the actual downloaded size from approxBytes before sha256 verify. */
    const val SIZE_TOLERANCE = 0.05

    // sha256 of each file, lowercased hex, pinned from the Hugging Face git-LFS pointers
    // (huggingface.co/ggerganov/whisper.cpp -> raw/main/<file> -> the "oid sha256:" line, which
    // is the sha256 of the LFS content). Now that these are valid 64-char hex, download
    // verification enforces them (streaming sha256) on top of the approxBytes size gate.
    private const val SHA256_ECO = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
    private const val SHA256_BASE = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
    private const val SHA256_PRO = "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30"
    private const val SHA256_EXTREME = "76733e26ad8fe1c7a5bf7531a9d41917b2adc0f20f2e4f5531688a8c6cd88eb0"
    private const val SHA256_MULTI = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
    private const val SHA256_ULTRA = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"

    // 4.6 — THE FIVE INSTRUMENT RUNGS. Same provenance and same method as every constant above,
    // and both halves of each were read TWICE, independently, on 2026-09-13:
    //  1. the git-LFS pointer at the commit [BASE_URL] pins — `raw/<that sha>/<file>` — whose
    //     `oid sha256:` line IS the digest of the LFS content and whose `size` line is the byte
    //     count; and
    //  2. a HEAD of the download URL itself, where the redirect carries `X-Linked-Size` and
    //     `X-Linked-ETag` equal to exactly those two values.
    // The method was validated against two rows already in this file (`ggml-small-q5_1.bin` →
    // 190,085,487 / ae85e4a9…, `ggml-large-v3-turbo-q5_0.bin` → 574,041,195 / 39422170…), which
    // is the control this had to pass before any new literal was trusted: the same two reads
    // reproduce the shipped constants exactly.
    //
    // Nothing here is rounded and nothing is copied between rows — the eco note above is why, and
    // `medium`/`medium.en` are the live trap: two different files 13,066 bytes apart, well inside
    // the ±[SIZE_TOLERANCE] gate, so a copy-paste between those two rows would be caught only by
    // the digest. NEVER download a gigabyte to check a byte count; a HEAD settles it.
    private const val SHA256_SMALL_Q8 = "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f"
    private const val SHA256_MEDIUM_Q5 = "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f"
    private const val SHA256_MEDIUM_Q8 = "42a1ffcbe4167d224232443396968db4d02d4e8e87e213d3ee2e03095dea6502"
    private const val SHA256_ULTRA_Q8 = "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1"
    private const val SHA256_LARGE_V3 = "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1"

    // The npu tier's two context binaries. MEASURED sha256s of the EXTRACTED files, not of the zip
    // that carries them — nothing here is a placeholder. 4.0 took them from the spike's staged
    // copies; since 4.15 (2026-09-24) they are the v0.63.0 8gen3 pair, re-measured by
    // `tools/build_asset_packs.py measure` under every gate. The QAIRT 2.50 rebuild changed both
    // files (the encoder 14.9% smaller), so the 4.0 digests (3e92ac26…, fda23d73…) are retired.
    private const val SHA256_NPU_ENCODER = "813d0e847bf1ba21b991a421a2f57f56884252d1ca6e780a02a55582c519bac0"
    private const val SHA256_NPU_DECODER = "bd853be4710bb0aa01dd2a5ce78c03f3e9f722cab47fad3ac24555995f21a929"

    // The npu-turbo tier's two context binaries — the same discipline: MEASURED sha256s of the
    // EXTRACTED entries, which is what lands on disk under the repacked names below. 4.1 streamed
    // them out of the local vendor zip at plan time (asset block, 2026-08-29); since 4.15 they are
    // the v0.63.0 8gen3 pair (encoder 11.6% smaller), and the 4.1 digests (f7d11c08…, c19b0677…)
    // are retired with the binaries they described.
    private const val SHA256_NPU_TURBO_ENCODER = "c9403eaa9c4b4313419d650e316be7cc1c9020cd8cd716ed909ddb0b61f0886a"
    private const val SHA256_NPU_TURBO_DECODER = "a5597486dd53a0847fa042588279d6ab58f736078ea133c513b15e5d8c39d241"

    /**
     * Provenance of the npu pair: Qualcomm AI Hub's public precompiled QNN-ONNX release for
     * whisper_small_quantized on Snapdragon 8 Gen 3 — the zip the spike measured at v0.61.0, and
     * since 4.15 the v0.63.0 zip the digests above were measured out of. BOTH context
     * binaries live inside this ONE archive, which is why both entries carry the same URL and why
     * neither is a DownloadManager source — Q8 imports the extracted files through SAF, and the
     * gate keeps the tier out of every download path (see [pickable]).
     */
    private const val NPU_ASSET_ZIP_URL =
        "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/" +
            "whisper_small_quantized/releases/v0.63.0/" +
            "whisper_small_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8gen3.zip"

    /**
     * Provenance of the npu-turbo pair (4.1): Qualcomm AI Hub's public precompiled QNN-ONNX
     * release for whisper_large_v3_turbo_quantized on Snapdragon 8 Gen 3 — the zip the plan's
     * asset work downloaded, CRC-verified and hashed (v0.61.0), and since 4.15 the v0.63.0 zip.
     * Same shape as [NPU_ASSET_ZIP_URL]: both context binaries live inside this ONE archive, so
     * both entries carry the same URL and neither is a DownloadManager source. Note the vendor
     * zip is NOT the delivery zip — its entries sit under a directory prefix and carry the SAME
     * bare names as the 4.0 npu tier's installed files, so the delivery repack (L8) strips the
     * prefix and renames turbo's entries to the `turbo_*` filenames the catalog states below;
     * importing the vendor names as-is would overwrite the owner's 338 MB npu pair.
     */
    private const val NPU_TURBO_ASSET_ZIP_URL =
        "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/" +
            "whisper_large_v3_turbo_quantized/releases/v0.63.0/" +
            "whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8gen3.zip"

    private fun urlFor(fileName: String): String = BASE_URL + fileName

    /**
     * `medium-q8`'s RAM floor — THE OWNER'S NUMBER (4.8.0, ruling 2026-09-17: *"I'd say we do
     * four point five gigs minimum"*). It is the same constant the first-run gate reads
     * (`OnboardingLogic.FIRST_RUN_RAM_GATE_BYTES`, asserted equal in OnboardingLogicTest), so the
     * card's "Fits your device" badge and the card's presence in the first-run lineup
     * answer one question. `ActivityManager.totalMem` under-reports physical RAM: a nominal 4 GB
     * phone reports ~3.7e9 and a 6 GB one ~5.6e9, so 4.5e9 separates exactly the two classes he
     * named.
     */
    const val MEDIUM_Q8_MIN_RAM_BYTES: Long = 4_500_000_000L

    /**
     * `ultra-q8`'s RAM floor (4.9.0) — ONE CONSTANT OF ITS OWN, equal to [MEDIUM_Q8_MIN_RAM_BYTES]
     * today, so the owner can raise turbo's alone without touching medium's.
     *
     * A CONTROLLER RULING on the number, PENDING THE OWNER'S WORD: he gave no separate figure
     * for turbo — his lineup rule was *"if you can see v3 turbo, of course, you should see all
     * three tiers"*, i.e. the rung whose floor a device meets and every rung under it — and
     * turbo's resident set is comparable to medium's, not smaller: the file is 51 MB LARGER
     * (874 MB against 823), while the decoder's cross-attention cache is smaller (≈ 31 MB
     * against ≈ 151 MB — 4 text layers against 24). Neither figure puts turbo clearly above or
     * below medium in the app's own memory model, so the two floors are one number until the
     * owner rules otherwise — and two constants, because "one number today" is not a rule that
     * says they must stay one.
     */
    const val ULTRA_Q8_MIN_RAM_BYTES: Long = 4_500_000_000L

    val entries: List<WhisperModel> = listOf(
        WhisperModel(
            id = "eco",
            displayName = "Eco (base.en)",
            fileName = "ggml-base.en-q5_1.bin",
            url = urlFor("ggml-base.en-q5_1.bin"),
            // Exact LFS byte sizes (from the HF LFS pointers) — the old rounded values put a
            // correct download only ~129 KB inside the ±5% gate on this tier.
            approxBytes = 59_721_011L,
            sha256 = SHA256_ECO,
            scope = ModelScope.ENGLISH,
            minRamBytes = 0L,
            // 3.7 Workstream H: retired, NOT unsupported — installed users keep it, untouched.
            retired = true,
        ),
        WhisperModel(
            id = "base",
            displayName = "Base multilingual",
            fileName = "ggml-base-q5_1.bin",
            url = urlFor("ggml-base-q5_1.bin"),
            // Exact LFS byte size at the pinned commit — do NOT round (see the eco note above).
            approxBytes = 59_707_625L,
            sha256 = SHA256_BASE,
            scope = ModelScope.MULTILINGUAL,
            minRamBytes = 0L,
            // 3.7 Workstream H: retired, NOT unsupported — installed users keep it, untouched.
            retired = true,
        ),
        WhisperModel(
            id = "pro",
            displayName = "Pro (small.en)",
            fileName = "ggml-small.en-q5_1.bin",
            url = urlFor("ggml-small.en-q5_1.bin"),
            approxBytes = 190_098_681L,
            sha256 = SHA256_PRO,
            scope = ModelScope.ENGLISH,
            minRamBytes = 0L,
            // 4.6 — RETIRED, and with it the last English-only rung the app offered. Owner ruling
            // 2026-09-13: *"we should really only be showing only multi language models, period.
            // We shouldn't show English only at all."* `eco` and `extreme` were already retired;
            // this completes it, and `ModelScope.ENGLISH` now describes only rows nobody is
            // offered.
            //
            // **`retired` and NOT `unsupported`, which is the whole care in this change.** A
            // retired tier is hidden from the chooser and otherwise left completely alone:
            // [ModelMigration.decide] gates on `unsupported`, so its installed users are *"not
            // prompted, not migrated, and never asked to re-download"*. Someone dictating happily
            // on small.en must not be told to fetch a download they never asked for — and the
            // replacement (since 4.7 the 264 MB `small-q8`) is the same whisper-small weights
            // with a multilingual vocab head, at Q8_0, so the migration card's implied promise
            // ("this is better") would not even be true for an English-only user. They keep the
            // tier, it keeps working, and
            // `isCpuFallbackEligible` still admits it as an 80-bin donor.
            //
            // It is also why `sessionLanguageFor`'s ENGLISH-scope Auto pin STAYS
            // (`FloatingBubbleService.kt`): retiring hides a tier, it does not uninstall it, so
            // users on `pro` and `eco` persist and that pin is still correct for them.
            retired = true,
        ),
        WhisperModel(
            id = "extreme",
            displayName = "Extreme (medium.en)",
            fileName = "ggml-medium.en-q5_0.bin",
            url = urlFor("ggml-medium.en-q5_0.bin"),
            approxBytes = 539_225_533L,
            sha256 = SHA256_EXTREME,
            scope = ModelScope.ENGLISH,
            // ActivityManager.totalMem under-reports physical RAM (kernel/carveouts) — a 6 GB
            // gate mislabels genuine 6 GB devices. 5.5e9 keeps the intent (6 GB-class hardware).
            minRamBytes = 5_500_000_000L,
            retired = true,
            unsupported = true,
        ),
        WhisperModel(
            id = "multi",
            // 4.6: the quantisation joined the name. `multi` and the `small-q8` rung below are the
            // SAME whisper-small weights at two different quantisations, and that pair is the
            // cheapest decisive experiment in the owner's session — so a card that does not state
            // which quantisation it is makes the session uninterpretable. Read from this file's
            // own ggml header: ftype 1009 = Q5_1 (2026-09-13).
            displayName = "Multilingual (small, Q5_1)",
            fileName = "ggml-small-q5_1.bin",
            url = urlFor("ggml-small-q5_1.bin"),
            approxBytes = 190_085_487L,
            sha256 = SHA256_MULTI,
            scope = ModelScope.MULTILINGUAL,
            minRamBytes = 0L,
            // 4.7 — RETIRED by the Q8 ruling of 2026-09-17 (*"Q8 for everything." — "Q5 is
            // definitely off the table."*), on the measurement in
            // `docs/measurements/2026-09-17-tab-cpu-ladder.md`: the same weights at Q8_0
            // (`small-q8`, directly below) finalize a chunk in a median 1,217 ms against this
            // rung's 2,618 ms on the same tablet, same talk, same session. This was the shipped
            // default from 4.6.0 and the only measured rung before that session; its Fold6 row in
            // `TierThroughputRecord` is kept, because evidence is never deleted.
            //
            // `retired` and NOT `unsupported`, exactly as `pro` was in 4.6: every user on this
            // tier keeps it, it keeps working, it is still a legal 80-bin mel donor and CPU
            // fallback, and nobody is shown a migration card for a model that works.
            retired = true,
        ),
        // ================================================= 4.6 — THE INSTRUMENT LADDER
        //                                                  4.7 — MEASURED, AND RULED ON
        //
        // 4.6 put five new rungs here, plus `ultra` un-retired, every one a
        // [WhisperModel.instrument]: selectable on every device, recommended on none, so the owner
        // could measure the CPU ladder rather than accept a prediction scaled from one Fold6 anchor
        // — *"I wanna see all the models there so I can just select between them and try each
        // one."* The block that stood here said of the quantisation axis: *"That is a prediction,
        // not a measurement, and no row states it as one."*
        //
        // **On 2026-09-17 it was measured** (`docs/measurements/2026-09-17-tab-cpu-ladder.md`:
        // Galaxy Tab S10+, one TEDx talk as device audio, threads=4, previewer armed, versionCode
        // 95 + b54fc1b, timed by Claude Fable 5.1 over remote adb at the owner's instruction).
        // Median finalize wall time per VAD chunk: `small-q8` 1,217 ms against `multi`'s 2,618;
        // `medium-q8` 1,341 ms against `medium-q5`'s 9,294 (which NEVER caught up — the queue
        // grew and the chunks lengthened to 9-14 s); `ultra-q8` 4,849 ms with a worst commit of
        // 7,930 against its 8,000 ms floor. The prediction held, and by more than it predicted.
        //
        // **The owner's ruling, same day:** *"Q8 for everything." — "Q5 is definitely off the
        // table."* — *"We're only testing on models that we're actually gonna use."* So the ladder
        // is now THREE pickable rungs, every one Q8_0: `small-q8` is the floor for every device
        // and the default; `medium-q8` is the medium tier, steered to above a RAM threshold;
        // `ultra-q8` WAS, from 4.7 through 4.8, an OPTIONAL top rung — *"if people really want
        // that higher quality accuracy … it's doable, it's actually workable"* — offered, never
        // advocated, because it kept up on a flagship with no margin (4.9 makes it an ordinary
        // rung offered by RAM; see below). The four Q5 rows (`multi`, `medium-q5`, `ultra`,
        // `large-v3`) are RETIRED — hidden from the chooser, untouched for anyone who has one —
        // and the two Q5_0 rungs the session never timed (`ultra`, `large-v3`) were retired
        // unmeasured by the same ruling.
        //
        // **Production authorisation was NOT granted by this.** The owner's next step was an
        // accuracy pass on small and medium — *"the rest of the testing now will be to prove the
        // accuracy of the small and medium model … before we actually give it a go"* — so
        // `TierThroughputRecord.PRODUCTION_PROMOTABLE` stayed EMPTY through 4.7 and 4.8, and
        // those builds went to the internal track and a sideloaded tablet only.
        //
        // **4.9 — THE LADDER SHIPS, on the owner's word (2026-09-17, after his own dictation on
        // the Tab S10+):** *"all three actually work very well"*, and of turbo: *"we definitely
        // wanna keep that one … six to maybe nine second drain time, which is totally manageable
        // and doable. And users would definitely like to select between these."* So the three
        // Q8 rungs are one ladder, every rung an ordinary tier: `ultra-q8` is no longer an
        // instrument (the ruling is recorded beside its measurement in `TierThroughputRecord`,
        // where the gate reads it), it carries a RAM floor like medium's ([ULTRA_Q8_MIN_RAM_BYTES]),
        // and `PRODUCTION_PROMOTABLE` names all three. The lineup a device is offered is
        // CUMULATIVE by RAM (`OnboardingLogic.firstRunLineup`): every rung whose floor the device
        // meets — *"if you can fit the medium model, you should also be able to see the small
        // model"* — so under the floor a fresh install sees small alone, and at or over it all
        // three, in ladder order.
        //
        // **Why the naive metric is the wrong one, still.** This app clamps `audio_ctx` to
        // `max(samples/320 + 64, 512)`, and that 512 floor binds for every chunk under 8.96 s —
        // which is every VAD-cut chunk in ordinary dictation. So the encoder cost per COMMIT is
        // CONSTANT, the ceiling is COMMITS PER SECOND, and the queue grows iff finalize wall time
        // exceeds the commit floor. Sparse speech buys no relief. That is the metric the doc uses
        // and the only one these rows are read in.
        //
        // **The ORDER is unchanged** ([entries] order; `ModelTierCopy.orderedForLanguageTagFor`
        // sorts stably over it): grouped BY MODEL FAMILY with each quantisation twin beside its
        // retired sibling — small, medium, turbo, large-v3 — so the retired rows still read as
        // the twins they are and the measurement doc's pairs are the catalogue's pairs.
        WhisperModel(
            id = "small-q8",
            displayName = "Multilingual (small, Q8_0)",
            fileName = "ggml-small-q8_0.bin",
            url = urlFor("ggml-small-q8_0.bin"),
            // 264,464,607 — LFS pointer size AND X-Linked-Size, 2026-09-13.
            approxBytes = 264_464_607L,
            sha256 = SHA256_SMALL_Q8,
            // n_vocab 51865, from this file's own ggml header — the multilingual vocabulary. The
            // `.en` rows are 51864, so the scope is READ here rather than inferred from the name.
            scope = ModelScope.MULTILINGUAL,
            // 80, from the header (n_mels at offset 40). Same 12 encoder layers at 768 dims as
            // `multi` — literally the same model, 40% larger, at a different quantisation.
            //
            // 4.7 — THE FLOOR FOR EVERY DEVICE, and [DEFAULT_MODEL_ID]. Recommended everywhere
            // (`minRamBytes = 0`), measured to keep up with margin on the Tab S10+ (median
            // 1,217 ms per commit, worst 1,993 ms; `TierThroughputRecord.SMALL_Q8`), and no longer
            // an instrument: the flag meant "no verdict that clears", and it has one.
            minRamBytes = 0L,
        ),
        WhisperModel(
            id = "medium-q5",
            displayName = "Multilingual (medium, Q5_0)",
            fileName = "ggml-medium-q5_0.bin",
            url = urlFor("ggml-medium-q5_0.bin"),
            // 539,212,467 — NOT `extreme`'s 539,225,533. Two different files (medium vs
            // medium.en), 13,066 bytes apart, both inside a ±5% gate of each other: each row must
            // state its own or the size gate is being asked to cover a copy-paste.
            approxBytes = 539_212_467L,
            sha256 = SHA256_MEDIUM_Q5,
            // n_vocab 51865 — the multilingual medium this app has never had. Its only medium is
            // `extreme` (medium.en), which spends the entire bill on the one language with the
            // smallest prize.
            scope = ModelScope.MULTILINGUAL,
            // 80 from the header; 24 encoder layers at 1024 dims.
            minRamBytes = 0L,
            // 4.7 — RETIRED by the Q8 ruling of 2026-09-17, and this is the row the ruling was
            // most about: on the Tab S10+ it NEVER caught up (median 9,294 ms per commit against
            // an 8,000 ms floor, worst 11,782; `TierThroughputRecord.MEDIUM_Q5`), while the same
            // weights at Q8_0 (`medium-q8`, below) finalized in 1,341 ms. Its measured row is
            // kept as evidence. Retired, not unsupported: an installed one keeps working, at the
            // cadence it always had, for anyone who chose it on the internal track.
            retired = true,
        ),
        WhisperModel(
            id = "medium-q8",
            displayName = "Multilingual (medium, Q8_0)",
            fileName = "ggml-medium-q8_0.bin",
            url = urlFor("ggml-medium-q8_0.bin"),
            // 823,369,779 — LFS pointer size AND X-Linked-Size, 2026-09-13.
            approxBytes = 823_369_779L,
            sha256 = SHA256_MEDIUM_Q8,
            scope = ModelScope.MULTILINGUAL,
            // Byte-for-byte the same hyperparameters as `medium-q5` off the header — n_vocab
            // 51865, 24 encoder layers at 1024, 80 mel bins — differing ONLY in ftype (2007 = Q8_0
            // against 1008 = Q5_0). That is what makes this pair a controlled comparison.
            //
            // 4.7 — THE MEDIUM TIER. Measured to keep up with margin on the Tab S10+ (median
            // 1,341 ms per commit, worst 2,508 against an 8,000 ms floor;
            // `TierThroughputRecord.MEDIUM_Q8`), so it is no longer an instrument.
            //
            // **The RAM threshold is THE OWNER'S NUMBER, no longer provisional** (4.8.0, ruling
            // 2026-09-17: *"I'd say we do four point five gigs minimum. If you have under that,
            // then you get pushed to the smallest model; anything above, then you're gonna
            // choose from the medium or v3 turbo."*). 4.7 carried 5.5e9 over from the `extreme`
            // (medium.en) precedent pending exactly this call. 4.5e9 sits between what a nominal
            // 4 GB phone reports to `ActivityManager.totalMem` (~3.7e9) and what a 6 GB one
            // reports (~5.6e9), so it separates the two classes the owner named. It is the
            // same constant the first-run gate reads (`OnboardingLogic.FIRST_RUN_RAM_GATE_BYTES`,
            // asserted equal in OnboardingLogicTest), so the badge on this card and the card's
            // presence in the first-run lineup answer one question. Below it the rung stays
            // selectable from Settings and the chooser says "High-end devices only", which is a
            // statement about RAM and is true. 4.9: the number lives on [MEDIUM_Q8_MIN_RAM_BYTES],
            // beside turbo's own constant, so the two floors can be moved apart deliberately.
            minRamBytes = MEDIUM_Q8_MIN_RAM_BYTES,
        ),
        WhisperModel(
            id = "ultra",
            // 4.6: the quantisation joined the name, for the same reason `multi`'s did — the
            // `ultra-q8` rung below is this same file at a different quantisation. ftype 2008 =
            // Q5_0, from the header.
            displayName = "Ultra (large-v3-turbo, Q5_0)",
            fileName = "ggml-large-v3-turbo-q5_0.bin",
            url = urlFor("ggml-large-v3-turbo-q5_0.bin"),
            approxBytes = 574_041_195L,
            sha256 = SHA256_ULTRA,
            scope = ModelScope.MULTILINGUAL,
            // large-v3-turbo is large-v3's own encoder, so it carries large-v3's 128-bin
            // filterbank — the fact `isCpuFallbackEligible` used to spell as `id != "ultra"`.
            // Read from this file's ggml header (n_mels, offset 40), not assumed.
            melBins = 128,
            // 4.6 UN-RETIRED it as an INSTRUMENT (owner ruling 2026-09-13) so it could be
            // measured on the CPU for the first time since VAD chunking landed; `unsupported` was
            // dropped with `retired` (a tier the app offers cannot also be one it migrates people
            // off) and `minRamBytes` went from 7.0e9 to 0 (an instrument makes no RAM claim).
            //
            // 4.7 — RETIRED AGAIN, UNMEASURED, by the Q8 ruling of 2026-09-17 (*"Q5 is
            // definitely off the table"*). The session never timed it: its Q8_0 twin (`ultra-q8`,
            // below) kept up on the tablet with no margin, and `medium-q5` — the only Q5_0 rung
            // that was timed — never caught up, so a Q5_0 rung with a heavier encoder was not
            // worth a run. `unsupported` STAYS FALSE, which is the difference from 3.7: nobody who
            // picked it on the internal track is shown a migration card, and it is not the
            // migration source it was — retired means hidden, not wanted-off. `minRamBytes` stays
            // 0 because a retired row's threshold gates nothing.
            minRamBytes = 0L,
            retired = true,
        ),
        WhisperModel(
            id = "ultra-q8",
            displayName = "Ultra (large-v3-turbo, Q8_0)",
            fileName = "ggml-large-v3-turbo-q8_0.bin",
            url = urlFor("ggml-large-v3-turbo-q8_0.bin"),
            // 874,188,075 — LFS pointer size AND X-Linked-Size, 2026-09-13.
            approxBytes = 874_188_075L,
            sha256 = SHA256_ULTRA_Q8,
            scope = ModelScope.MULTILINGUAL,
            // 128, read off THIS file's header — the same n_vocab 51866 / 32 encoder layers at
            // 1280 / 4 text layers as `ultra`, differing only in ftype (2007 = Q8_0 against
            // 2008 = Q5_0). So it is refused as a mel donor and as a CPU fallback for exactly the
            // reason `ultra` is ([isCpuFallbackEligible]), by its recorded width and not by name.
            melBins = 128,
            // 4.7 — THE OPTIONAL TOP RUNG, and the ONE instrument left: MEASURED
            // (`TierThroughputRecord.ULTRA_Q8`: median 4,849 ms per commit on the Tab S10+, worst
            // 7,930 against an 8,000 ms floor — `KeepUp.KEPT_UP_WITHOUT_MARGIN`), offered for its
            // accuracy and not advocated, because the margin that would survive a device slower
            // than a Dimensity 9300+ flagship, or thermal drift on that one, was not there.
            //
            // 4.9 — AN ORDINARY RUNG, ON THE OWNER'S RULING (2026-09-17, after his own dictation
            // on the Tab S10+): *"For v3 Turbo Q8, we definitely wanna keep that one. And all
            // three as well, because all three actually work very well. V3 Turbo, I'm noticing
            // only about a six to maybe nine second drain time, which is totally manageable and
            // doable. And users would definitely like to select between these."* The MEASUREMENT
            // is unchanged — still KEPT_UP_WITHOUT_MARGIN, still 0.99 of its floor at the worst
            // commit — and the ruling is recorded beside it as a `ThroughputVerdict.OwnerRuling`,
            // named and dated, which is what clears the rung for production without touching the
            // number. So the `instrument` flag comes off (the coupling rule: the set is the
            // pickable rungs whose verdict does not clear, and this one now clears), and the rung
            // carries a RAM floor like medium's ([ULTRA_Q8_MIN_RAM_BYTES], its own constant):
            // `isRecommendedForDevice` answers by RAM, so on a device that meets the floor it is
            // badged "Fits your device" like its two siblings — a RAM fit, not a recommendation,
            // which is why the chip does not say "Recommended": this card's own body tells a
            // less-capable device to expect the typed text to fall behind — and only the steer
            // carries "Our pick". Under the floor the Settings picker shows the RAM note and the
            // guided flow does not show the card at all (`OnboardingLogic.firstRunLineup`). Still
            // never the default and never a migration target — those name the rung with the
            // margin, not the one the owner likes best.
            minRamBytes = ULTRA_Q8_MIN_RAM_BYTES,
        ),
        WhisperModel(
            id = "large-v3",
            displayName = "Multilingual (large-v3, Q5_0)",
            fileName = "ggml-large-v3-q5_0.bin",
            url = urlFor("ggml-large-v3-q5_0.bin"),
            // 1,081,140,203 — LFS pointer size AND X-Linked-Size, 2026-09-13. The largest single
            // file this app can be asked to download.
            approxBytes = 1_081_140_203L,
            sha256 = SHA256_LARGE_V3,
            scope = ModelScope.MULTILINGUAL,
            // 128, read off this file's own header — the change large-v3 made to the filterbank,
            // which `ultra` and `ultra-q8` inherit by being distilled from it. **THIS is the row
            // that made the mel width a catalog field**: the first single-file, ungated, PICKABLE
            // 128-bin tier, which is precisely the residual the old `id != "ultra"` clause stated
            // about itself. Its 32 text layers against turbo's 4 are the whole difference between
            // the two — same encoder, eight times the decoder.
            melBins = 128,
            minRamBytes = 0L,
            // 4.7 — RETIRED, UNMEASURED, by the Q8 ruling of 2026-09-17: a Q5_0 rung, and the
            // heaviest file on the ladder, on the day the owner took every Q5 rung off the table.
            // The session never timed it. Retired, not unsupported — an internal-track user who
            // downloaded the gigabyte keeps it. The mel-width field it forced into the catalogue
            // stays load-bearing: `ultra-q8` is a pickable 128-bin rung and reads it.
            retired = true,
        ),
        WhisperModel(
            id = "npu",
            displayName = "Multilingual on NPU (small)",
            // The ENCODER context binary. The decoder is the pairedArtifact below; both come out
            // of the same zip and both must be on disk before the tier is installed.
            fileName = "encoder_qairt_context.bin",
            url = NPU_ASSET_ZIP_URL,
            // The PAIR — 113,123,776 + 225,298,736 (v0.63.0; 358,244,352 before 4.15). This is the
            // number the size badge states, because it is what the owner downloads and what the
            // storage costs.
            approxBytes = 338_422_512L,
            sha256 = SHA256_NPU_ENCODER,
            // Same whisper-small weights as `multi`, quantised for the Hexagon: 90+ languages,
            // not an English-only tier.
            scope = ModelScope.MULTILINGUAL,
            // FROM the spec table, not restated: this tier is the one that needs an 80-bin mel
            // DONOR, and `pcmToMel` compares the donor's n_mels against exactly this number.
            melBins = NpuModelSpec.SMALL.melBins,
            // No RAM gate: the SoC gate (NpuGate) already restricts this tier to 8 Gen 3-class
            // hardware, which is never RAM-poor, and a second gate would only raise the chooser's
            // "high-end devices only" note on devices that had already passed the real test.
            minRamBytes = 0L,
            gated = true,
            primaryBytes = 113_123_776L,
            pairedArtifact = PairedArtifact(
                fileName = "decoder_qairt_context.bin",
                url = NPU_ASSET_ZIP_URL,
                sha256 = SHA256_NPU_DECODER,
                approxBytes = 225_298_736L,
            ),
        ),
        WhisperModel(
            // The id resolves through the spec row's OWN field (4.1 L4 handoff): "npu-turbo" has
            // one home, NpuModelSpec.TURBO.tierId, and everything keyed on it — forTier, the
            // mel-donor auto-exclusion, the L8 routing re-spec — reads the same string this
            // entry carries, by construction rather than by two literals agreeing.
            id = NpuModelSpec.TURBO.tierId,
            displayName = "Multilingual on NPU (large-v3-turbo)",
            // The REPACKED name. The vendor zip's entry is `encoder_qairt_context.bin` —
            // byte-identical to the npu tier's installed encoder, in the same models directory —
            // so the delivery zip renames turbo's entries and npu keeps its 4.0 names untouched.
            fileName = "turbo_encoder_qairt_context.bin",
            url = NPU_TURBO_ASSET_ZIP_URL,
            // The PAIR — 686,112,520 + 295,856,032 (v0.63.0; 1,071,685,632 before 4.15). What the
            // badge states, what the user stores.
            approxBytes = 981_968_552L,
            sha256 = SHA256_NPU_TURBO_ENCODER,
            // large-v3-turbo: 100 languages, the same multilingual promise as `multi` and `npu`.
            scope = ModelScope.MULTILINGUAL,
            // FROM the spec table, for the same reason `npu`'s is: this row and that one describe
            // one tier, and 128 is why it carries a BUNDLED filterbank instead of asking for a
            // donor (NpuModelSpec.TURBO.melAsset).
            melBins = NpuModelSpec.TURBO.melBins,
            // No RAM gate, same reasoning as `npu`: the SoC gate already restricts this tier to
            // 8 Gen 3-class hardware.
            minRamBytes = 0L,
            gated = true,
            // The encoder alone — what isInstalled gates models/<fileName> against.
            primaryBytes = 686_112_520L,
            pairedArtifact = PairedArtifact(
                fileName = "turbo_decoder_qairt_context.bin",
                url = NPU_TURBO_ASSET_ZIP_URL,
                sha256 = SHA256_NPU_TURBO_DECODER,
                approxBytes = 295_856_032L,
            ),
        ),
    )

    /**
     * Tiers offered to users. Retired tiers stay in [entries] so byId() keeps resolving them;
     * [WhisperModel.gated] tiers are excluded here **unconditionally** and reach the chooser only
     * through [pickableFor], so every caller that cannot answer the gate question keeps the
     * device-independent lineup it has always had.
     */
    val pickable: List<WhisperModel> = entries.filter { !it.retired && !it.gated }

    /**
     * The [WhisperModel.instrument] rungs, in catalog order — one home for "which rungs are
     * offered without being advocated" (4.6).
     *
     * Derived, never a second list: a row carries the flag and this finds it, so a rung cannot be
     * an instrument in one place and a recommendation in another. Read by the tests that prove no
     * instrument is recommended, default or a migration target, and it is the handle the
     * throughput-verdict gate keys on. **Empty since 4.9** (the owner's ruling on `ultra-q8`
     * clears it); the list stays because the next unmeasured rung lands in it.
     */
    val instruments: List<WhisperModel> = entries.filter { it.instrument }

    /**
     * The tier a device powerful enough to run it is offered, and the ONLY one (4.3).
     *
     * Owner ruling 2026-08-30: *"If a phone is powerful enough with the NPU, we should only
     * support the multilingual v3 turbo... They should just go straight to the one gig version."*
     * One home for the subject of that rule, resolved through [NpuModelSpec.TURBO]'s own `tierId`
     * for the same reason the catalog entry below is — the string has one owner, not three
     * literals that agree today.
     */
    val ONE_TIER_ID: String = NpuModelSpec.TURBO.tierId

    /**
     * [pickable] plus every gated tier whose id is in [offeredGatedIds] — the caller's gate
     * answer, i.e. `WhisperEverywhereApp.offeredNpuTierIds()` (routing) or that UNION
     * `fetchableNpuTierIds()` (the two chooser surfaces): hardware capability AND that tier's own
     * files on disk, or a census-deliverable pack, per tier.
     *
     * **The Boolean became a set in 4.1 (L5)** because two gated tiers can be independently
     * installed and one bit cannot say which — and because the old `it.id == "npu"` was a literal
     * a second gated tier would have had to be remembered into. A gated tier is offered iff its
     * id is in the set, which is what the parameter now means; `!it.retired` still applies first,
     * so an id in the set never resurrects a retired tier, and an id the catalog cannot resolve
     * admits nothing.
     *
     * ### 4.3 — one tier per device
     *
     * **When [ONE_TIER_ID] is in the set, the lineup IS that tier**, plus whatever the caller
     * names in [alsoOfferedIds]. Everything else — the CPU tiers (since 4.7 `small-q8` 264 MB,
     * `medium-q8` 823 MB and `ultra-q8` 874 MB), the 338 MB `npu` — is
     * not offered, because on this hardware the answer is not a menu. `npu` STAYS CATALOGUED
     * (the streaming arc needs it; hiding is not retiring) and its census/pack/import machinery
     * is untouched — see `WhisperCatalogHelpersTest`'s catalogued-but-unoffered pin.
     *
     * **THE GATE-FAIL PATH IS UNTOUCHED, BY CONSTRUCTION.** Every device that cannot be offered
     * turbo — the whole non-capable fleet, whose two producers both answer `emptySet()` — returns
     * from the line above, which is this function's entire pre-4.3 body, verbatim.
     * `WhisperCatalogHelpersTest` executes that equivalence over the whole non-turbo input space
     * rather than asserting it in prose.
     *
     * `emptySet()` is the every-other-device answer and reproduces [pickable] exactly.
     *
     * ### 4.6 — AND THEREFORE A DEVICE OFFERED THE ONE TIER IS OFFERED NO INSTRUMENT
     *
     * **A recorded consequence, not an oversight.** 4.6 added six [WhisperModel.instrument] rungs
     * to [pickable] so the owner could measure them, and the narrowing above takes every CPU rung
     * back on any device whose gate set names [ONE_TIER_ID] — which is the whole 8 Gen 3-class
     * fleet, the Fold6 included. So on a capable device whose turbo delivery works, the chooser
     * renders `npu-turbo` plus what [alsoOfferedIds] names — whatever was already installed, until
     * the owner's ruling of 2026-09-25 narrowed that to the installed gated tiers and the
     * selection (producer 1, below) — and the CPU ladder — since 4.7 the three Q8 rungs
     * `small-q8`, `medium-q8` and `ultra-q8`; since 4.9 none of them an instrument — renders no
     * card at all: neither selectable nor downloadable there. The owner re-ruled the same on
     * 2026-09-17: *"NPU tier detection still stays the same: if they have that chip and we have
     * a pack available for them, they should absolutely get the NPU tier."*
     * `WhisperCatalogHelpersTest`'s `a_device_offered_the_one_tier_is_offered_no_cpu_rung`
     * (named `…_no_instrument` until 4.9 emptied the set) executes that sentence, deliberately beside
     * `every_instrument_is_pickable_ungated_and_installable_by_download` — the two halves of the
     * same fact, so a later reader finds the case that does NOT work next to the case that does
     * instead of inferring it from a silence.
     *
     * It cost the 4.6 session something specific, which is why it was written down rather than
     * merely true: the Fold6 carried the ladder's only measured anchor (`multi`, F = 2.3 s), so
     * `multi` vs `small-q8` could not be run on the device its own baseline was measured on. The
     * comparison was run on the Tab S10+ instead (2026-09-17, both arms on one device: 2,618 ms
     * against 1,217 ms), which is how the ruling that retired `multi` was made.
     *
     * **Reversing it is not ours to do.** The one-tier rule is the owner's ruling of 2026-08-30,
     * quoted at [ONE_TIER_ID]; widening it so a measurement rung slips through would be the same
     * defect as quietly promoting one, one axis over. And it needs no new rule if he rules the
     * other way: [alsoOfferedIds] is exactly this door — it admits any non-retired id, and
     * `OnboardingLogic.chooserAlsoOfferedIds` already pushes the whole [pickable] ladder through it
     * on the delivery-failure path (pinned in `OnboardingLogicTest`). The change would be at the
     * two producers of that argument, and nothing in this function would move. (The owner's
     * ruling of 2026-09-25 proved that shape from the other side: it asked for FEWER CPU cards,
     * and it landed at the producers — `OnboardingLogic.chooserAlsoOfferedIds`, since then the one
     * rule both chooser surfaces ask — with this body unmoved.)
     *
     * @param alsoOfferedIds the ids that join the one-card lineup ANYWAY. Two producers, and both
     *        exist because the narrowing has two ways of being wrong:
     *
     *        1. **What is already on disk** — the non-disturbance rule. A capable device is never
     *           silently switched or deleted from, and keeps transcribing on what it runs;
     *           deleting a gigabyte the user paid bandwidth for is not ours to do. Its DISPLAY half
     *           — every installed tier keeps its card — held from 4.3 until **the owner's ruling
     *           of 2026-09-25**, made on the Tab S10+ ship session of 4.16.0/113, whose chooser
     *           showed three installed Q8 rungs beside the AI-chip card: *"if the NPU multilingual
     *           is here, then we hide all of the other CPU models so users don't get confused
     *           about which model to download."* So on a device whose set names [ONE_TIER_ID],
     *           this producer names the installed GATED tiers — an installed `npu` keeps its card,
     *           exactly as since 4.3 — and **ONE EXCEPTION, by controller ruling: the CURRENTLY
     *           SELECTED model is never hidden.** A user whose selection is an installed CPU rung
     *           still sees the card they are running on (an active selection with no card is the
     *           same confusion from the other side); once they pick the AI-chip tier, that card
     *           goes. The files stay and routing keeps reading the selection. It is also how the
     *           decline recovery resolves: the CPU tier is absent until a decline downloads it,
     *           and the recovery then makes it the selection, so its card is back. The rule is
     *           `OnboardingLogic.chooserAlsoOfferedIds`, one for both chooser surfaces; every
     *           device NOT offered the one tier gets the pre-ruling answer, which this function
     *           never reads there anyway.
     *        2. **The CPU tiers when the one tier could not be DELIVERED** —
     *           `OnboardingLogic.chooserAlsoOfferedIds`, the no-wedge escape (4.2 F6 fix round 1,
     *           I-1) carried into 4.3. A sideloaded capable device is offered turbo (the census
     *           says the family HAS a pack; it cannot know Play will refuse this install), and
     *           onboarding's model step is mandatory — so a chooser narrowed to one undeliverable
     *           card would wedge setup with no completable path. The suspension is the existing
     *           mechanism, not a new rule: the ids simply join the lineup through the door
     *           producer 1 uses. The 2026-09-25 ruling leaves this producer untouched — once the
     *           one tier could not be delivered, the CPU ladder joins, installed rungs included.
     *
     *        `!it.retired` still runs FIRST either way, so an installed retired tier (eco, base) —
     *        even a selected one — cannot re-enter a lineup through this door. Defaulted to empty
     *        so the ungated callers and the gate-fail path stay one argument shorter and one rule
     *        simpler.
     */
    fun pickableFor(
        offeredGatedIds: Set<String>,
        alsoOfferedIds: Set<String> = emptySet(),
    ): List<WhisperModel> {
        val offered = entries.filter { !it.retired && (!it.gated || it.id in offeredGatedIds) }
        if (ONE_TIER_ID !in offeredGatedIds) return offered
        return offered.filter { it.id == ONE_TIER_ID || it.id in alsoOfferedIds }
    }

    /**
     * May [model]'s file serve as the 80-bin mel donor and the NPU decline's CPU fallback? (4.3 —
     * lifted out of `WhisperModelManager.isMelDonorEligible`, which now delegates here.)
     *
     * It moved because 4.3 gave it a SECOND reader that must never disagree with the first: the
     * decline card asks *"is there anything to fall back to?"* and the backend asks *"what do I
     * fall back to?"*, and a card that promises a fallback the backend cannot find is precisely
     * the silent-failure shape the loud-fallback doctrine exists to prevent. One predicate, in the
     * pure layer, executable — rather than two copies of it, one of which needs a `Context`.
     *
     * The clauses are the manager's own, unchanged and for its own reasons:
     *  - `NpuModelSpec.forTier(id) == null` — STRUCTURAL, not a literal (4.1 L3): an npu-class
     *    tier's file is a QAIRT context binary, not a ggml, and asking the table that knows which
     *    tiers those are means the next row is excluded by the clause that excludes this one.
     *  - `melBins == NpuModelSpec.SMALL.melBins` — **4.6: THE FIX THIS CLAUSE'S OWN KDOC ASKED
     *    FOR.** It used to read `id != "ultra"`, by name, above a stated residual: *"a future
     *    SINGLE-file 128-bin tier would qualify by both clauses, because the catalog records no
     *    mel-bin count. Adding one is the real fix and it is a catalog change."* The `large-v3`
     *    rung is that tier, so the count is in the catalog ([WhisperModel.melBins]) and this reads
     *    it. Both 128-bin rungs are now excluded by the property that disqualifies them, and the
     *    NEXT one is excluded before anyone remembers this comment. The width compared against is
     *    the `npu` tier's own, because that is the tier that needs a donor and `pcmToMel` refuses
     *    any donor whose `whisper_model_n_mels` is not exactly it (`whisper_jni.cpp:635`) — a
     *    128-bin donor is a failed load, not a worse one. `ultra` and `large-v3` remain perfectly
     *    real whisper models; they are refused for their filterbank and nothing else.
     *  - `pairedArtifact == null` — excludes the npu class a second time, structurally, and would
     *    catch a future two-artefact tier nobody thought to name.
     *
     * Retired tiers are ELIGIBLE on purpose: eco, base and (since 4.6) pro are ordinary 80-bin
     * whisper models, and an installed one is a real fallback.
     */
    fun isCpuFallbackEligible(model: WhisperModel): Boolean =
        NpuModelSpec.forTier(model.id) == null &&
            model.melBins == NpuModelSpec.SMALL.melBins && model.pairedArtifact == null

    /**
     * Does this device hold anything the NPU tier could decline INTO? (4.3)
     *
     * The pure mirror of `WhisperModelManager.cpuTierModelPath() != null` — the same
     * [isCpuFallbackEligible] predicate over the same installed set, so the card's claim and the
     * backend's fallback are one question asked twice rather than two questions. False is the
     * state 4.3 creates and must answer for: a capable device that only ever installed turbo has
     * nothing to fall back to, and a decline there must say so plainly instead of failing mute
     * (see [com.whispereverywhere.npu.NpuTierStatus.cardNote]).
     */
    fun hasCpuFallback(installedIds: Set<String>): Boolean =
        entries.any { it.id in installedIds && isCpuFallbackEligible(it) }

    /**
     * Whether `WhisperModelManager.download` can install this tier **at all**.
     *
     * **This is a STRUCTURAL fact about `download()`, not a policy about which tiers we like.**
     * That function enqueues exactly one `DownloadManager.Request` for `model.url` and writes
     * exactly one file, `model.fileName`; it never reads [WhisperModel.pairedArtifact]. So a tier
     * made of two artefacts cannot be installed by it however correct its URL is — and calling it
     * anyway is not a harmless no-op: `download()`'s first act is `if (dest.exists()) dest.delete()`
     * on `fileName`, which for `npu` **destroys the hand-imported encoder** before a byte is
     * fetched, and `verifyDest` then size-gates whatever arrived against `approxBytes` — the SUM of
     * both files — and deletes that too.
     *
     * **`pairedArtifact == null` rather than `!gated`**, deliberately. `gated` means "this device
     * decides", which is a different question with a different answer: a future gated tier could be
     * a single downloadable file, and a future ungated tier could ship as a pair. The predicate
     * tracks the thing that actually breaks, so it stays correct when the next two-artifact tier
     * arrives without anyone remembering this comment.
     */
    fun isInstallableByDownload(model: WhisperModel): Boolean = model.pairedArtifact == null

    /**
     * The refusal line for a tier [isInstallableByDownload] rejects — a pure builder so the text is
     * assertable, since `download()` itself needs a `Context` and no JVM test can reach it (the
     * same F-rule split [com.whispereverywhere.npu.NpuDiag] uses, and for the same reason).
     *
     * Names the tier and both files, because the reader of this line is trying to work out what to
     * do instead, and "import these two" is the answer.
     */
    fun notInstallableByDownloadReason(model: WhisperModel): String =
        "download refused for '${model.id}': installs by import, not download " +
            "(needs ${model.fileName} AND ${model.pairedArtifact?.fileName}; " +
            "url is provenance, not a source)"

    /**
     * Default tier on first run. **`small-q8` (small Q8_0) since 4.7**; was `multi` (small Q5_1)
     * in 4.6, and `pro` (small.en) from 3.7 Workstream H.
     *
     * The chooser offers [pickable], steers a fresh install by locale
     * ([ModelTierCopy.steerIdForLanguageTag]), and the user picks explicitly; this constant is the
     * fallback for every path with no pick on record — the auto-setup re-entry in
     * OnboardingSetupViewModel, the download-phase re-resolve in OnboardingFlowScreen, and
     * [ModelMigration]'s ENGLISH target.
     *
     * **The default is the rung the app can stand behind on evidence**, and since 2026-09-17
     * that is `small-q8`. It is measured (`docs/measurements/2026-09-17-tab-cpu-ladder.md`: Galaxy
     * Tab S10+, device audio from one TEDx talk, previewer armed — median 1,217 ms per commit,
     * worst 1,993 ms, KEPT_UP with margin; `TierThroughputRecord.SMALL_Q8`), and it is the SAME
     * whisper-small weights as the `multi` it replaces — 12 encoder layers at 768 dims, the
     * multilingual vocabulary — stored at Q8_0 instead of Q5_1: 2.2x faster per commit on that
     * tablet (2,618 ms against 1,217, both arms in one session) for +74 MB of download. Same
     * model, on ggml's ARM i8mm repack path that Q5_1 is absent from.
     *
     * **It moved because `multi` is retired** (the owner's Q8 ruling of 2026-09-17 — *"Q5 is
     * definitely off the table"*), and a retired default is an unshippable state: it is
     * unreachable from the picker, so a user who lands on it by fallback cannot see the card for
     * the tier they are on. The 4.6 rule that put `multi` here is unchanged in shape — a default
     * has to clear the app's own eligibility rule on a measurement, not a derivation, because the
     * streaming previewer would hide an untimed finalizer (words appear on the strip 0.4 s behind
     * the voice whatever the finalizer is doing) — and `small-q8` now clears it by more than
     * `multi` did. `TierThroughputTest.the_default_and_every_migration_target_carry_a_clearing_verdict`
     * holds that.
     *
     * One device, one talk, previewer armed: the caveats are on the record's own row, not hidden
     * here.
     */
    const val DEFAULT_MODEL_ID = "small-q8"

    fun byId(id: String?): WhisperModel? = entries.firstOrNull { it.id == id }

    /**
     * A model is recommended when the device has at least its minimum RAM — **and is never
     * recommended at all if it is a [WhisperModel.instrument]** (4.6).
     *
     * The instrument clause runs first and answers on the rung, not on the device: an instrument
     * is offered without a throughput verdict that clears, and a card badged *"Recommended for
     * your device"* is the app claiming one. The RAM comparison is untouched for every other row,
     * boundary included (`>=`) — since 4.7 `medium-q8` is a live subject of that boundary
     * (4.5e9 since 4.8.0, the owner's number), since 4.9 `ultra-q8` is the other (its own
     * constant, the same number today), and `small-q8` at 0 is recommended everywhere. So on a
     * device at or over the floor all three CPU cards are badged, and only the steer wears
     * "Our pick" — the badge says the device can carry the rung, the chip says which one the app
     * would start with.
     */
    fun isRecommendedForDevice(model: WhisperModel, totalRamBytes: Long): Boolean =
        !model.instrument && totalRamBytes >= model.minRamBytes

    /**
     * Size gate: is [actualBytes] within +/-SIZE_TOLERANCE of [approxBytes]?
     * Inclusive at both +/-5% edges. Runs before the sha256 compare in verify().
     */
    fun sizeWithinTolerance(actualBytes: Long, approxBytes: Long): Boolean {
        val delta = abs(actualBytes - approxBytes).toDouble()
        val allowed = approxBytes.toDouble() * SIZE_TOLERANCE
        return delta <= allowed
    }

    /** Lowercased hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Streaming SHA-256 of [file], reading in 64 KB chunks so large model files
     * (e.g. ~574 MB) are never loaded fully into memory.
     * Returns a lowercase 64-char hex string.
     */
    fun sha256HexFile(file: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        file.inputStream().use { stream ->
            var read: Int
            while (stream.read(buf).also { read = it } != -1) {
                md.update(buf, 0, read)
            }
        }
        val digest = md.digest()
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * verify(): size-gate first (cheap, avoids hashing a truncated file), THEN sha256 compare.
     * Returns true only when both pass. [expectedSha256] is compared case-insensitively.
     */
    fun verify(actualBytes: Long, approxBytes: Long, fileBytes: ByteArray, expectedSha256: String): Boolean {
        if (!sizeWithinTolerance(actualBytes, approxBytes)) return false
        // The sha256 gate is enforced only once the real digest is pinned (a Plan 4 production
        // step fills these). Until then the constant is "PENDING" and we accept on the size gate,
        // so Plan-1 downloads are functional now; sha enforcement switches on automatically.
        val expected = expectedSha256.trim().lowercase()
        val isRealDigest = expected.length == 64 && expected.all { it in "0123456789abcdef" }
        if (!isRealDigest) return true
        return sha256Hex(fileBytes).equals(expected, ignoreCase = true)
    }
}
