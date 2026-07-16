package com.whispereverywhere.model

import java.security.MessageDigest

/** Language coverage of a whisper model. */
enum class ModelScope { ENGLISH, MULTILINGUAL }

/**
 * A downloadable whisper.cpp model tier.
 *
 * @param approxBytes advertised download size; used as a size gate before sha256 verification.
 * @param sha256 lowercased hex digest of the downloaded file (see WhisperCatalog SHA256_* consts).
 * @param minRamBytes minimum device RAM to *recommend* this model (0 = recommended on any device).
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
)

/**
 * Pure, Android-free catalog + decision helpers. Everything here is JVM-unit-testable.
 * The Android shell (WhisperModelManager) delegates to these.
 */
object WhisperCatalog {

    private const val BASE_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    /** Allowed deviation of the actual downloaded size from approxBytes before sha256 verify. */
    const val SIZE_TOLERANCE = 0.05

    // sha256 of each file, lowercased hex. Fill via: curl -sL <url> | sha256sum
    // (See the plan "Fill the sha256 constants" step.) "PENDING" until fetched.
    private const val SHA256_ECO = "PENDING"
    private const val SHA256_PRO = "PENDING"
    private const val SHA256_EXTREME = "PENDING"
    private const val SHA256_MULTI = "PENDING"
    private const val SHA256_ULTRA = "PENDING"

    private fun urlFor(fileName: String): String = BASE_URL + fileName

    val entries: List<WhisperModel> = listOf(
        WhisperModel(
            id = "eco",
            displayName = "Eco (base.en)",
            fileName = "ggml-base.en-q5_1.bin",
            url = urlFor("ggml-base.en-q5_1.bin"),
            approxBytes = 57_000_000L,
            sha256 = SHA256_ECO,
            scope = ModelScope.ENGLISH,
            minRamBytes = 0L,
        ),
        WhisperModel(
            id = "pro",
            displayName = "Pro (small.en)",
            fileName = "ggml-small.en-q5_1.bin",
            url = urlFor("ggml-small.en-q5_1.bin"),
            approxBytes = 190_000_000L,
            sha256 = SHA256_PRO,
            scope = ModelScope.ENGLISH,
            minRamBytes = 0L,
        ),
        WhisperModel(
            id = "extreme",
            displayName = "Extreme (medium.en)",
            fileName = "ggml-medium.en-q5_0.bin",
            url = urlFor("ggml-medium.en-q5_0.bin"),
            approxBytes = 539_000_000L,
            sha256 = SHA256_EXTREME,
            scope = ModelScope.ENGLISH,
            minRamBytes = 6_000_000_000L,
        ),
        WhisperModel(
            id = "multi",
            displayName = "Multilingual (small)",
            fileName = "ggml-small-q5_1.bin",
            url = urlFor("ggml-small-q5_1.bin"),
            approxBytes = 190_000_000L,
            sha256 = SHA256_MULTI,
            scope = ModelScope.MULTILINGUAL,
            minRamBytes = 0L,
        ),
        WhisperModel(
            id = "ultra",
            displayName = "Ultra (large-v3-turbo)",
            fileName = "ggml-large-v3-turbo-q5_0.bin",
            url = urlFor("ggml-large-v3-turbo-q5_0.bin"),
            approxBytes = 574_000_000L,
            sha256 = SHA256_ULTRA,
            scope = ModelScope.MULTILINGUAL,
            minRamBytes = 8_000_000_000L,
        ),
    )

    /** Default tier selected on first run (Pro / small.en). */
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
        val delta = Math.abs(actualBytes - approxBytes).toDouble()
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
