package com.whispereverywhere.recording

import kotlinx.serialization.Serializable

/** Lifecycle of one batch job. [Recorded] = decoded PCM is on disk and ready to chunk. */
enum class BatchStatus { Recorded, Transcribing, PartiallyDone, Done, Failed }

/** Per-chunk checkpoint state. Only [Done] chunks are never re-run on Retry. */
enum class ChunkStatus { Pending, Done, Failed }

/**
 * Which engine actually produced a chunk's text. Drives the detail-screen engine chip. Serialized
 * by constant NAME (never the ordinal), so additive members keep old manifests (LOCAL/OPENAI)
 * parsing — no migration.
 */
enum class EngineUsed {
    LOCAL, OPENAI, GEMINI, ELEVENLABS, SONIOX;
    companion object {
        fun fromProviderId(id: com.whispereverywhere.provider.ProviderId): EngineUsed = when (id) {
            com.whispereverywhere.provider.ProviderId.OPENAI -> OPENAI
            com.whispereverywhere.provider.ProviderId.GEMINI -> GEMINI
            com.whispereverywhere.provider.ProviderId.ELEVENLABS -> ELEVENLABS
            com.whispereverywhere.provider.ProviderId.SONIOX -> SONIOX
        }
    }
}

/**
 * One planned slice of [RecordingMeta.chunkPlan]. Byte offsets are into audio.pcm (raw PCM16LE),
 * always even (sample-aligned). [hardCut] = the planner had to cut mid-speech because no silence
 * fell before the ceiling; degraded but bounded, and logged length-only.
 */
@Serializable
data class ChunkEntry(
    val index: Int,
    val startByte: Int,
    val endByte: Int,
    val hardCut: Boolean,
    val status: ChunkStatus = ChunkStatus.Pending,
    val text: String = "",
)

/**
 * The manifest.json for one job's workspace. kotlinx.serialization, NOT org.json — org.json
 * ships in android.jar and returns type defaults under this project's unitTests.returnDefaultValues
 * config, so a manifest parsed with it would silently come back blank.
 *
 * There is deliberately NO capture-source field: batch transcribes user-picked FILES, never
 * captured audio, so provenance does not exist here. [displayName] is the picked file's
 * user-visible name (OpenableColumns.DISPLAY_NAME), for the progress screen. Enums serialize by
 * constant NAME (never the ordinal) — the same rule ProviderId relies on.
 */
@Serializable
data class RecordingMeta(
    val id: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val displayName: String,
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val byteLength: Int,
    val status: BatchStatus = BatchStatus.Recorded,
    val engineUsed: EngineUsed? = null,
    val modelId: String? = null,
    val language: String? = null,
    val chunkPlan: List<ChunkEntry> = emptyList(),
)
