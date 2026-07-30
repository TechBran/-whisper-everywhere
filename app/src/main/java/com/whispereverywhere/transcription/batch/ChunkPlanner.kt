package com.whispereverywhere.transcription.batch

import com.whispereverywhere.recording.ChunkEntry
import com.whispereverywhere.util.AudioMath

/**
 * Coarse cut-planning by an energy scan, NOT the Silero VAD.
 *
 * The design named the Silero VAD seam for boundaries, but it is native-internal to whisper_full
 * (no JNI export returns boundaries) and running it over a whole 30-minute clip would need the
 * entire recording as a FloatArray — the OOM chunking exists to avoid. So the COARSE cut uses this
 * memory-bounded Kotlin scan; the Silero VAD still runs unchanged inside whisper_full per chunk, so
 * speech is still VAD-trimmed before the encoder. Quality is unaffected; bounds are guaranteed by
 * ChunkPlanner's hard-cut fallback. (Deviation from decision log #8, reported to the owner.)
 */
object SilenceScanner {
    private const val FRAME_BYTES = 960          // 30 ms @16 kHz PCM16 (480 samples)
    private const val SILENCE_RMS = 500          // matches the recorder's voiceThr
    private const val MIN_GAP_FRAMES = 8         // ~240 ms of continuous quiet = a real pause

    /** Even byte offsets at the midpoint of each silence gap that is FOLLOWED by more speech. */
    fun scan(pcm: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var pos = 0
        var gapStart = -1
        while (pos + 1 < pcm.size) {
            val len = minOf(FRAME_BYTES, pcm.size - pos)
            val rms = AudioMath.amplitude(pcm.copyOfRange(pos, pos + len), len)
            if (rms < SILENCE_RMS) {
                if (gapStart < 0) gapStart = pos
            } else {
                if (gapStart >= 0) {
                    // A gap that ended because speech resumed. Long enough? Emit its midpoint.
                    if (pos - gapStart >= MIN_GAP_FRAMES * FRAME_BYTES) {
                        var mid = gapStart + (pos - gapStart) / 2
                        mid -= mid % 2
                        out.add(mid)
                    }
                    gapStart = -1
                }
            }
            pos += len
        }
        // A gap still open at end-of-file is trailing silence — never a useful cut. Dropped.
        return out
    }
}

/**
 * Packs an audio length into chunks that each fit a byte ceiling, cutting on the last silence
 * boundary at or before the ceiling and hard-cutting when speech is continuous.
 *
 * Correctness does NOT depend on boundary quality: with no boundaries at all the plan is a run of
 * ceiling-sized hard cuts, which is bounded and can never exceed the ceiling or OOM. All offsets
 * are forced even so a cut never splits a PCM16 sample (which would shift every later sample by one
 * byte — white noise from that point on).
 */
object ChunkPlanner {
    /** 20 MB minus the 44-byte WAV header added at dispatch — a safety margin under OpenAI's 25 MB cap. */
    const val CLOUD_CEILING_BYTES = 20 * 1024 * 1024 - 44
    /** ~90 s of 16 kHz PCM16 — bounds native memory to ~2.9 MB/chunk, avoiding the long-feed OOM. */
    const val LOCAL_CHUNK_BYTES = 90 * 16_000 * 2

    fun plan(
        totalBytes: Int,
        maxChunkBytes: Int,
        minChunkBytes: Int,
        boundaries: List<Int>,
    ): List<ChunkEntry> {
        val total = totalBytes - (totalBytes % 2)        // whole samples only
        if (total <= 0) return emptyList()
        val maxChunk = (maxChunkBytes - (maxChunkBytes % 2)).coerceAtLeast(2)
        val minChunk = (minChunkBytes - (minChunkBytes % 2)).coerceAtLeast(0)

        val cuts = boundaries.asSequence()
            .map { it - (it % 2) }
            .filter { it in 2 until total }
            .distinct()
            .sorted()
            .toList()

        val out = ArrayList<ChunkEntry>()
        var start = 0
        var index = 0
        while (start < total) {
            if (total - start <= maxChunk) {
                out.add(ChunkEntry(index, start, total, hardCut = false))  // tail fits: natural end
                break
            }
            val ceil = start + maxChunk
            val cut = cuts.lastOrNull { it > start && it <= ceil && (it - start) >= minChunk }
            if (cut != null) {
                out.add(ChunkEntry(index, start, cut, hardCut = false))
                start = cut
            } else {
                out.add(ChunkEntry(index, start, ceil, hardCut = true))   // no silence: bounded hard cut
                start = ceil
            }
            index++
        }
        return out
    }
}
