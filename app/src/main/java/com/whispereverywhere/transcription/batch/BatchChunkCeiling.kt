package com.whispereverywhere.transcription.batch

import com.whispereverywhere.transcription.cloud.SttProvider

/**
 * The per-chunk byte ceiling for a batch cloud job, derived from the RESOLVED adapter's
 * maxRequestBytes (which already accounts for that provider's request shape, incl. Gemini's
 * base64 inflation). Two clamps on top:
 *   - subtract the 44-byte WAV header the dispatch path adds, so pcm+header never trips the
 *     adapter's own BadSegment guard;
 *   - cap at MEMORY_BOUND: a chunk's PCM is read whole into a ByteArray then pcm16ToFloat'd, so an
 *     ElevenLabs 5 GB cap must NOT become a 5 GB allocation. 20 MB (~10.5 min) matches the prior
 *     global bound and the long-feed OOM budget the local ceiling was chosen against.
 * A null provider (on-device job) never calls this — the transcriber uses LOCAL_CHUNK_BYTES.
 */
object BatchChunkCeiling {
    const val WAV_HEADER_BYTES = 44
    /** Chosen batch bound; caps ElevenLabs' 5 GB adapter cap to a sane per-chunk allocation. */
    const val MEMORY_BOUND_BYTES = 20 * 1024 * 1024

    fun forProvider(provider: SttProvider): Int {
        val raw = (provider.maxRequestBytes - WAV_HEADER_BYTES).coerceAtMost(MEMORY_BOUND_BYTES.toLong())
        val bounded = raw.coerceIn(2L, MEMORY_BOUND_BYTES.toLong())
        return (bounded - (bounded % 2)).toInt()   // even -> never shears a PCM16 sample
    }
}
