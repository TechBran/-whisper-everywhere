package com.whispereverywhere.transcription.speakers

import com.whispereverywhere.text.TextJoin
import com.whispereverywhere.transcription.TranscriptText

/**
 * One VAD speech segment of a committed chunk, on BOTH timelines (4.10 speaker labels).
 *
 * [origStart] / [origEnd] index the RAW chunk the engine handed to the native layer — the audio a
 * speaker embedder must fingerprint, because the stitched buffer whisper saw has 100 ms of
 * injected silence between segments and does not outlive the JNI call.
 *
 * [trimmedStart] / [trimmedEnd] index that stitched buffer, which is the timeline whisper's own
 * centisecond timestamps are measured on. Both pairs are SAMPLES at 16 kHz.
 *
 * The two pairs are equal only for a chunk whose first segment starts at sample 0 and has no
 * segment after it, which is why a mix-up here is so quiet: it is right for the simplest chunk
 * and wrong for every chunk that opens with silence.
 */
data class VadSeg(
    val origStart: Int,
    val origEnd: Int,
    val trimmedStart: Int,
    val trimmedEnd: Int,
)

/**
 * A stretch of committed text attributed to ONE VAD segment of its chunk.
 *
 * [vadIndex] indexes the chunk's [VadSeg] list, NOT a speaker: turning indices into speaker ids is
 * the tracker's job (Task 2), and it needs the index to find the audio to fingerprint. [text] is
 * already [TranscriptText.clean]ed, so it is exactly what the user would see.
 */
data class SpeakerSpan(val vadIndex: Int, val text: String)

/**
 * Turns the native segment geometry into spans — the pure half of the speaker pipeline (4.10
 * Task 1). No Android, no JNI, no state: everything here is a function of the two `IntArray`s
 * `WhisperNative.lastVadSegments()` / `lastWhisperSegments()` published and the UTF-8 bytes
 * `transcribeRaw` returned.
 *
 * WHY BYTES AND NOT CHARACTERS: `transcribeRaw` returns raw UTF-8 because `NewStringUTF` aborts
 * the process on 4-byte sequences, and the native side therefore reports BYTE offsets. A Kotlin
 * `Char` index computed after the decode cannot be mapped back to one — a code point spans one to
 * four bytes and a surrogate pair is two `Char`s for one code point — so this class slices the
 * bytes and decodes each slice. Decoding first and slicing by `Char` index cuts code points in
 * half and yields U+FFFD, on exactly the multilingual models that made the byte return necessary.
 */
object SpeakerSpans {

    /** 16 kHz: whisper reports centiseconds, the VAD bounds are samples. The one conversion. */
    private const val SAMPLES_PER_CENTISECOND = 160

    /** Four ints per segment, in both arrays — the native side's flattening. */
    private const val STRIDE = 4

    /**
     * Unpacks `[origStart, origEnd, trimmedStart, trimmedEnd]` * n, in chunk order.
     *
     * An EMPTY result means the VAD did not run (no model path, an init failure, a segmentation
     * failure) or found no speech at all. That is not an error and — per the spec — must never be
     * read as "one speaker": with no segments there is no timeline to attribute text to.
     *
     * A trailing partial group is DROPPED rather than padded. Half a segment is not a segment, and
     * inventing three of its four bounds would place fabricated audio in front of an embedder.
     */
    fun vadSegments(raw: IntArray): List<VadSeg> {
        val n = raw.size / STRIDE
        if (n == 0) return emptyList()
        return (0 until n).map { i ->
            val o = i * STRIDE
            VadSeg(
                origStart = raw[o],
                origEnd = raw[o + 1],
                trimmedStart = raw[o + 2],
                trimmedEnd = raw[o + 3],
            )
        }
    }

    /**
     * Cuts [bytes] — the UTF-8 `transcribeRaw` returned — into spans, one per run of decoded
     * segments that share a VAD segment.
     *
     * @param raw `[t0cs, t1cs, byteStart, byteEnd]` * n, in TEXT order, from `lastWhisperSegments`.
     * @param vad the same chunk's [vadSegments]. EMPTY yields no spans, by the rule above.
     *
     * The rules, in the order they are applied per decoded segment:
     *  1. **Slice, then clean.** The byte range is clamped into [bytes] first: the geometry is
     *     process-global and one call behind, so a stale pair can name bytes this chunk does not
     *     have. A wrong label is a wrong label; an exception here would cost the user the whole
     *     segment for the sake of a label.
     *  2. **A segment that cleans away contributes nothing.** whisper's non-speech markers would
     *     otherwise open a paragraph for a speaker who said nothing.
     *  3. **Most overlap wins**, measured in SAMPLES on the TRIMMED timeline. Ties go to the
     *     earlier segment.
     *  4. **No overlap at all → the LAST VAD segment.** The sub-1.1 s zero-pad is appended after
     *     the stitch and belongs to no segment, so a decoded segment can legitimately sit past
     *     every bound; the audio it was decoded from is the tail of the last one.
     *  5. **Adjacent spans with the same index merge**, joined by [TextJoin] so the merge cannot
     *     melt two words together or push a space in front of a comma. Merging is ADJACENCY-only:
     *     a speaker who comes back after someone else keeps a separate span, because the panel
     *     breaks a paragraph at every change.
     */
    fun spans(raw: IntArray, bytes: ByteArray, vad: List<VadSeg>): List<SpeakerSpan> {
        val n = raw.size / STRIDE
        if (n == 0 || vad.isEmpty() || bytes.isEmpty()) return emptyList()
        val out = ArrayList<SpeakerSpan>(n)
        for (i in 0 until n) {
            val o = i * STRIDE
            val start = raw[o + 2].coerceIn(0, bytes.size)
            val end = raw[o + 3].coerceIn(start, bytes.size)
            if (end == start) continue
            val text = TranscriptText.clean(String(bytes, start, end - start, Charsets.UTF_8))
            if (text.isEmpty()) continue
            val index = vadIndexFor(
                t0Samples = raw[o] * SAMPLES_PER_CENTISECOND,
                t1Samples = raw[o + 1] * SAMPLES_PER_CENTISECOND,
                vad = vad,
            )
            val previous = out.lastOrNull()
            if (previous != null && previous.vadIndex == index) {
                out[out.size - 1] = previous.copy(
                    text = TextJoin.assemble(listOf(previous.text, text)),
                )
            } else {
                out.add(SpeakerSpan(vadIndex = index, text = text))
            }
        }
        return out
    }

    /** Rules 3 and 4 above: most overlap on the trimmed timeline, else the last segment. */
    private fun vadIndexFor(t0Samples: Int, t1Samples: Int, vad: List<VadSeg>): Int {
        var bestIndex = vad.size - 1
        var bestOverlap = 0
        for (i in vad.indices) {
            val seg = vad[i]
            val overlap = minOf(t1Samples, seg.trimmedEnd) - maxOf(t0Samples, seg.trimmedStart)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestIndex = i
            }
        }
        return bestIndex
    }
}
