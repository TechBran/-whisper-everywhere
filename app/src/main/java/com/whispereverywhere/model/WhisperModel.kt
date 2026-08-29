package com.whispereverywhere.model

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
    val minRamBytes: Long,
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
     * A tier only SOME devices may be offered — 4.0's npu, which is a precompiled QAIRT graph for
     * one HTP architecture and is meaningless anywhere else. Orthogonal to [retired]: retired means
     * "we stopped offering this to anyone", gated means "this device decides".
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
     * and npu's [approxBytes] is the PAIR (358,244,352) while its [fileName] is the encoder alone
     * (132,927,488). Gating the encoder against the sum is 63% out — `isInstalled(npu)` would have
     * been false forever, whatever the owner imported.
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

    // The 4.0 npu tier's two context binaries. MEASURED sha256s of the EXTRACTED files (the spike
    // staged and hashed both), not of the zip that carries them — nothing here is a placeholder.
    private const val SHA256_NPU_ENCODER = "3e92ac26545b6b9d22ecfab594ae57523134006e2722b09fa10e16b193e9e5ec"
    private const val SHA256_NPU_DECODER = "fda23d731e6b0ab7fb0a50373a49efe2d1792faa5dad456837624d8b8e44b0e4"

    /**
     * Provenance of the npu pair: Qualcomm AI Hub's public precompiled QNN-ONNX release for
     * whisper_small_quantized on Snapdragon 8 Gen 3, the zip the spike measured. BOTH context
     * binaries live inside this ONE archive, which is why both entries carry the same URL and why
     * neither is a DownloadManager source — Q8 imports the extracted files through SAF, and the
     * gate keeps the tier out of every download path (see [pickable]).
     */
    private const val NPU_ASSET_ZIP_URL =
        "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/" +
            "whisper_small_quantized/releases/v0.61.0/" +
            "whisper_small_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8gen3.zip"

    private fun urlFor(fileName: String): String = BASE_URL + fileName

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
            displayName = "Multilingual (small)",
            fileName = "ggml-small-q5_1.bin",
            url = urlFor("ggml-small-q5_1.bin"),
            approxBytes = 190_085_487L,
            sha256 = SHA256_MULTI,
            scope = ModelScope.MULTILINGUAL,
            minRamBytes = 0L,
        ),
        WhisperModel(
            id = "ultra",
            displayName = "Ultra (large-v3-turbo)",
            fileName = "ggml-large-v3-turbo-q5_0.bin",
            url = urlFor("ggml-large-v3-turbo-q5_0.bin"),
            approxBytes = 574_041_195L,
            sha256 = SHA256_ULTRA,
            scope = ModelScope.MULTILINGUAL,
            // See extreme tier note: 7.0e9 = genuine 8 GB-class hardware after totalMem slack.
            minRamBytes = 7_000_000_000L,
            retired = true,
            unsupported = true,
        ),
        WhisperModel(
            id = "npu",
            displayName = "Multilingual on NPU (small)",
            // The ENCODER context binary. The decoder is the pairedArtifact below; both come out
            // of the same zip and both must be on disk before the tier is installed.
            fileName = "encoder_qairt_context.bin",
            url = NPU_ASSET_ZIP_URL,
            // The PAIR — 132,927,488 + 225,316,864. This is the number the size badge states,
            // because it is what the owner downloads and what the storage costs.
            approxBytes = 358_244_352L,
            sha256 = SHA256_NPU_ENCODER,
            // Same whisper-small weights as `multi`, quantised for the Hexagon: 90+ languages,
            // not an English-only tier.
            scope = ModelScope.MULTILINGUAL,
            // No RAM gate: the SoC gate (NpuGate) already restricts this tier to 8 Gen 3-class
            // hardware, which is never RAM-poor, and a second gate would only raise the chooser's
            // "high-end devices only" note on devices that had already passed the real test.
            minRamBytes = 0L,
            gated = true,
            primaryBytes = 132_927_488L,
            pairedArtifact = PairedArtifact(
                fileName = "decoder_qairt_context.bin",
                url = NPU_ASSET_ZIP_URL,
                sha256 = SHA256_NPU_DECODER,
                approxBytes = 225_316_864L,
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
     * [pickable] plus the npu tier when [npuAvailable] — the caller's answer to
     * `NpuWhisperBackend.isTierAvailable(soc, mfr, libDir) && WhisperModelManager.isInstalled(npu)`
     * (Q6 handoff §9.1: capability AND the 358 MB actually on disk). Computed once per process,
     * never in a recomposition — the capability half dlopens two libraries on its first call.
     *
     * Only `npu` is named: a future gated tier stays hidden until someone decides what its own gate
     * is, rather than inheriting this one by accident.
     */
    fun pickableFor(npuAvailable: Boolean): List<WhisperModel> =
        if (npuAvailable) entries.filter { !it.retired && (!it.gated || it.id == "npu") } else pickable

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
     * Default tier on first run. **pro (small.en) since 2026-08-20 (3.7 Workstream H):** eco and
     * base are retired for accuracy, leaving pro as the English flagship and multi as the
     * international tier. The chooser offers [pickable], steers a fresh install toward one of them
     * by locale ([ModelTierCopy.steerIdForLanguageTag]), and the user picks explicitly; this
     * constant is the fallback for every path with no pick on record — the auto-setup re-entry in
     * OnboardingSetupViewModel, the download-phase re-resolve in OnboardingFlowScreen, and
     * ModelMigration's ENGLISH target.
     */
    const val DEFAULT_MODEL_ID = "pro"

    fun byId(id: String?): WhisperModel? = entries.firstOrNull { it.id == id }

    /** A model is recommended when the device has at least its minimum RAM. */
    fun isRecommendedForDevice(model: WhisperModel, totalRamBytes: Long): Boolean =
        totalRamBytes >= model.minRamBytes

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
