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
}

enum class FatalKind { INVALID_KEY, OUT_OF_CREDIT, FORBIDDEN }

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
