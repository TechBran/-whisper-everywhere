package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.provider.ProviderId

/** Why a cloud transcription attempt failed. The distinctions drive very different handling. */
sealed interface SttError {
    /** No usable network. Do not retry in a tight loop; do not blame the key. */
    data object Offline : SttError
    /** The account or key is the problem. Retrying cannot help — stop using this provider. */
    data class Fatal(val kind: FatalKind, val message: String) : SttError
    /** Worth retrying. [retryAfterMs] is the server's own stated wait, when it gave one. */
    data class Transient(val retryAfterMs: Long?) : SttError
    /** THIS segment is unacceptable (too large, malformed). Does not disable the provider. */
    data object BadSegment : SttError
    /**
     * A SUCCESS response (HTTP 200) whose body could not be decoded — billed but unusable. Distinct
     * from [Transient] precisely because it must NOT be retried: the request already succeeded and
     * was charged, so re-POSTing the same segment re-bills for the same undecodable answer. The
     * batch retry loop treats this as a one-way fall to local for that chunk; live handling still
     * falls back exactly as it does for any other failure. Not an empty transcript — returning "" for
     * an unreadable body would look like silence and suppress the fallback.
     */
    data object Undecodable : SttError
}

enum class FatalKind { INVALID_KEY, OUT_OF_CREDIT, FORBIDDEN, MODEL_UNAVAILABLE }

sealed interface SttResult {
    data class Text(val text: String) : SttResult
    data class Failed(val error: SttError) : SttResult
}

/**
 * Turns one committed PCM16 segment into text. Deliberately narrow: no session, no streaming, no
 * state. Everything above it (ordering, fallback, retry policy) is provider-agnostic.
 */
interface SttProvider {
    val id: ProviderId
    /** Hard upper bound on one request's audio payload, enforced BEFORE any upload. */
    val maxRequestBytes: Long
    suspend fun transcribe(pcm: ByteArray, language: String?): SttResult
}
