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
 * ONE stretch of a chunk's ORIGINAL audio that gets ONE voice fingerprint (4.10, spike session 4).
 *
 * Until session 4 this was always a whole [VadSeg] and the type did not exist. The RISK it exists
 * for is failure mode B: the endpointer cuts on silence, so when nobody pauses it can hand over
 * six to fourteen seconds in one segment — and if two voices share those seconds they share one
 * fingerprint, the dominant one wins it, and the tracker is asked who is speaking in a recording
 * of two people at once. The owner's own reading of his 20:31 session was *"the VAD doesn't seem
 * to chunk on the boundaries"*, and this is that cut, made where whisper already found one.
 *
 * **What the data does and does not say.** 20:31 turned out to be the ONE-VOICE clip (the owner,
 * later the same evening: a long narration with a single narrator), so its 0.84 pairwise median
 * was the tracker being RIGHT and failure mode B is **not demonstrated** by that night's dumps.
 * This split is therefore DESIGN for a shape the endpointer can obviously produce — a podcast
 * guest's two-minute answer would otherwise be one fingerprint — and it is bounded to exactly
 * that shape for the same reason: over five seconds, two or more whisper segments, and nothing
 * else changes. Failure mode A, the short-turn starvation the graded gates answer, is the
 * demonstrated one.
 *
 * A window is therefore a VAD segment, or a slice of a long one along its whisper-segment
 * boundaries. [origStart] / [origEnd] index the RAW chunk, like [VadSeg.origStart] and for the
 * same reason: the trimmed buffer whisper saw does not outlive the JNI call.
 *
 * [vadIndex] is kept because a window is still *of* a segment — the diag line counts both, and
 * the geometry that produced a window is the only thing that can explain it.
 */
data class SpeakerWindow(
    val vadIndex: Int,
    val origStart: Int,
    val origEnd: Int,
)

/**
 * A stretch of committed text attributed to ONE fingerprint window of its chunk.
 *
 * [windowIndex] indexes the chunk's [SpeakerWindow] list, NOT a speaker: turning indices into
 * speaker ids is the tracker's job (Task 2), and it needs the index to find the audio that was
 * fingerprinted. [text] is already [TranscriptText.clean]ed, so it is exactly what the user would
 * see.
 *
 * It indexed the [VadSeg] list until spike session 4. For every chunk with no long segment in it
 * the two lists are the same list — one window per segment, in the same order — which is exactly
 * why the rename matters: the day they differ is the day a silent off-by-one would put one
 * speaker's name on another's sentence.
 */
data class SpeakerSpan(val windowIndex: Int, val text: String)

/**
 * Turns the native segment geometry into WINDOWS and SPANS — the pure half of the speaker
 * pipeline (4.10 Task 1; the split is spike session 4). No Android, no JNI, no state: everything
 * here is a function of the two `IntArray`s `WhisperNative.lastVadSegments()` /
 * `lastWhisperSegments()` published and the UTF-8 bytes `transcribeRaw` returned.
 *
 * [windows] answers *what audio gets a fingerprint*, [spans] answers *which text belongs to
 * which one of them*, and they are the same partition seen from the two timelines — which is why
 * they live in one file and why [spans] defaults its `windows` argument to [windows]'s own answer
 * rather than letting a caller invent a second one.
 *
 * WHY BYTES AND NOT CHARACTERS: `transcribeRaw` returns raw UTF-8 because `NewStringUTF` aborts
 * the process on 4-byte sequences, and the native side therefore reports BYTE offsets. A Kotlin
 * `Char` index computed after the decode cannot be mapped back to one — a code point spans one to
 * four bytes and a surrogate pair is two `Char`s for one code point — so this class slices the
 * bytes and decodes each slice. Decoding first and slicing by `Char` index cuts code points in
 * half and yields U+FFFD, on exactly the multilingual models that made the byte return necessary.
 */
object SpeakerSpans {

    /**
     * **5.0 s** — past this, a VAD segment holding two or more whisper segments is fingerprinted
     * per whisper segment instead of once (spike session 4, failure mode B).
     *
     * It is a floor on the SEGMENT, not on the window: below it the old single fingerprint is
     * kept byte for byte, because splitting a four-second segment buys nothing — a single speaker
     * rarely alternates inside one — and costs another 130-300 ms of embedding.
     *
     * Where the number comes from, honestly: session 4's five dumps split into two populations by
     * segment length. The three ordinary conversational ones ran 2.7-3.1 s median with p90s
     * around 7 s; the long one ran 6.3 s median, 14.3 s p90. 5 s sits between them rather than
     * inside either, so the split fires on the shape that can hide a second voice and leaves
     * ordinary turn-taking exactly as it was. It is NOT a threshold fitted to a failure — the
     * long dump turned out to be one narrator — which is why it is deliberately conservative.
     */
    const val LONG_SEGMENT_SECONDS: Float = 5.0f

    /**
     * **1.5 s** — the shortest window a split may produce. Adjacent whisper segments are
     * coalesced until the window reaches it, and a trailing short remainder joins the window
     * before it rather than standing alone.
     *
     * It is [SpeakerTracker.MIN_OPEN_SECONDS] on purpose: a window shorter than that could never
     * open a speaker or confirm one, so cutting one costs an embedding and buys a segment that
     * can only ever inherit. Splitting is a way of ASKING WHO IS SPEAKING, and a window that is
     * not allowed to answer is not worth cutting.
     */
    const val MIN_WINDOW_SECONDS: Float = 1.5f

    /** 16 kHz: whisper reports centiseconds, the VAD bounds are samples. The one conversion. */
    private const val SAMPLES_PER_CENTISECOND = 160

    /** The app's one sample rate — the durations above are seconds, the bounds are samples. */
    private const val SAMPLE_RATE = 16_000

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
     * THE CHUNK'S FINGERPRINT WINDOWS, in chunk order — one per VAD segment, except for the long
     * ones (spike session 4, failure mode B).
     *
     * A VAD segment is split when BOTH are true: it is longer than [LONG_SEGMENT_SECONDS], and
     * whisper found two or more segments inside it. Both terms matter. Length alone is no reason
     * to cut — a single speaker's eight-second sentence is one voice and one fingerprint is the
     * right answer for it. And a cut has to be made SOMEWHERE defensible: whisper's own segment
     * boundaries are the only boundaries in this pipeline that were placed by something that
     * listened to the words, which is precisely what the endpointer's silence gate did not do.
     *
     * The split, exactly:
     *  1. Every whisper segment is attributed to a VAD segment by the same overlap rule [spans]
     *     uses, and its `[t0, t1)` is mapped back from the TRIMMED timeline to ORIGINAL samples
     *     through that segment's own offset — `orig = origStart + (trimmed - trimmedStart)`,
     *     clamped into the segment. That offset is the whole of why the 100 ms stitch gaps
     *     between segments do not accumulate into the answer.
     *  2. Whisper segments are COALESCED in time order until the speech they span reaches
     *     [MIN_WINDOW_SECONDS]; then the next one starts a window. A trailing remainder that did
     *     not reach it joins the window before it rather than standing alone.
     *  3. The windows then PARTITION the VAD segment with no audio left out: the first starts at
     *     `origStart`, the last ends at `origEnd`, and each interior boundary is the mapped start
     *     of the first whisper segment of the window after it. The coalescing measured SPEECH;
     *     the boundaries hand the embedder everything between, pauses included.
     *
     * A segment that is not split yields exactly `SpeakerWindow(i, origStart, origEnd)` — the
     * pre-session-4 shape, so a chunk with no long segment in it is byte-for-byte what it was.
     *
     * @param raw `[t0cs, t1cs, byteStart, byteEnd]` * n, in TEXT order, from `lastWhisperSegments`.
     * @param vad the same chunk's [vadSegments]. EMPTY yields no windows: there is no timeline.
     */
    fun windows(raw: IntArray, vad: List<VadSeg>): List<SpeakerWindow> {
        if (vad.isEmpty()) return emptyList()
        val n = raw.size / STRIDE
        val minWindowSamples = (MIN_WINDOW_SECONDS * SAMPLE_RATE).toInt()
        val longSegmentSamples = (LONG_SEGMENT_SECONDS * SAMPLE_RATE).toInt()

        // Each VAD segment's whisper segments, as ORIGINAL sample ranges, in time order.
        val speech = Array(vad.size) { ArrayList<IntArray>() }
        for (i in 0 until n) {
            val o = i * STRIDE
            val t0 = raw[o] * SAMPLES_PER_CENTISECOND
            val t1 = raw[o + 1] * SAMPLES_PER_CENTISECOND
            val index = vadIndexFor(t0, t1, vad)
            val seg = vad[index]
            val from = toOriginal(seg, t0)
            val to = toOriginal(seg, t1).coerceAtLeast(from)
            speech[index] += intArrayOf(from, to)
        }

        val out = ArrayList<SpeakerWindow>(vad.size)
        for (i in vad.indices) {
            val seg = vad[i]
            val whole = SpeakerWindow(vadIndex = i, origStart = seg.origStart, origEnd = seg.origEnd)
            val ws = speech[i]
            if (seg.origEnd - seg.origStart <= longSegmentSamples || ws.size < 2) {
                out += whole
                continue
            }
            // Rule 4 of [spans] can hand a straggler to the LAST segment out of order; the
            // coalescing below is a walk along a timeline and has to be given one.
            ws.sortBy { it[0] }

            // Rule 2: the index in `ws` where each window begins.
            val begins = ArrayList<Int>(ws.size)
            begins += 0
            var first = 0
            for (j in ws.indices) {
                if (ws[j][1] - ws[first][0] >= minWindowSamples && j + 1 < ws.size) {
                    first = j + 1
                    begins += first
                }
            }
            if (begins.size > 1) {
                val last = begins.last()
                if (ws.last()[1] - ws[last][0] < minWindowSamples) begins.removeAt(begins.size - 1)
            }
            if (begins.size < 2) {
                out += whole
                continue
            }

            // Rule 3: the partition.
            for (g in begins.indices) {
                val from = if (g == 0) seg.origStart else ws[begins[g]][0]
                val to = if (g == begins.lastIndex) seg.origEnd else ws[begins[g + 1]][0]
                out += SpeakerWindow(vadIndex = i, origStart = from, origEnd = to.coerceAtLeast(from))
            }
        }
        return out
    }

    /**
     * Cuts [bytes] — the UTF-8 `transcribeRaw` returned — into spans, one per run of decoded
     * segments that share a fingerprint WINDOW.
     *
     * @param raw `[t0cs, t1cs, byteStart, byteEnd]` * n, in TEXT order, from `lastWhisperSegments`.
     * @param vad the same chunk's [vadSegments]. EMPTY yields no spans, by the rule above.
     * @param windows the same chunk's [windows]. The default IS the right answer and the
     *        parameter exists for ONE caller: `LocalWhisperEngine` computes the list once and
     *        hands the same object to the assigner, because two independently computed window
     *        lists that disagree would put the ids of one window on another's text.
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
     *  5. **Then the WINDOW inside it**, by where the segment's midpoint lands on the original
     *     timeline. The windows partition their segment, so exactly one contains it — and since
     *     an interior boundary IS a whisper segment's own start, no segment can fall on the wrong
     *     side of the cut that was made for it.
     *  6. **Adjacent spans with the same window merge**, joined by [TextJoin] so the merge cannot
     *     melt two words together or push a space in front of a comma. Merging is ADJACENCY-only:
     *     a speaker who comes back after someone else keeps a separate span, because the panel
     *     breaks a paragraph at every change. Two whisper segments that a split put in DIFFERENT
     *     windows no longer merge, which is the point of having split them.
     */
    fun spans(
        raw: IntArray,
        bytes: ByteArray,
        vad: List<VadSeg>,
        windows: List<SpeakerWindow> = windows(raw, vad),
    ): List<SpeakerSpan> {
        val n = raw.size / STRIDE
        if (n == 0 || vad.isEmpty() || bytes.isEmpty() || windows.isEmpty()) return emptyList()
        val out = ArrayList<SpeakerSpan>(n)
        for (i in 0 until n) {
            val o = i * STRIDE
            val start = raw[o + 2].coerceIn(0, bytes.size)
            val end = raw[o + 3].coerceIn(start, bytes.size)
            if (end == start) continue
            val text = TranscriptText.clean(String(bytes, start, end - start, Charsets.UTF_8))
            if (text.isEmpty()) continue
            val t0 = raw[o] * SAMPLES_PER_CENTISECOND
            val t1 = raw[o + 1] * SAMPLES_PER_CENTISECOND
            val index = windowIndexFor(t0, t1, vad, windows)
            val previous = out.lastOrNull()
            if (previous != null && previous.windowIndex == index) {
                out[out.size - 1] = previous.copy(
                    text = TextJoin.assemble(listOf(previous.text, text)),
                )
            } else {
                out.add(SpeakerSpan(windowIndex = index, text = text))
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

    /** Rules 3, 4 and 5: the VAD segment by overlap, then the window by the midpoint inside it. */
    private fun windowIndexFor(
        t0Samples: Int,
        t1Samples: Int,
        vad: List<VadSeg>,
        windows: List<SpeakerWindow>,
    ): Int {
        val vadIndex = vadIndexFor(t0Samples, t1Samples, vad)
        val seg = vad[vadIndex]
        // The last sample this segment could be attributed to, so a decoded segment sitting
        // entirely in the zero-padded tail lands INSIDE the final window rather than past it.
        val mid = toOriginal(seg, (t0Samples + t1Samples) / 2).coerceAtMost(seg.origEnd - 1)
        var fallback = -1
        for (w in windows.indices) {
            val window = windows[w]
            if (window.vadIndex != vadIndex) continue
            fallback = w
            if (mid >= window.origStart && mid < window.origEnd) return w
        }
        // A segment whose midpoint is outside every window of its own segment can only come from
        // a degenerate bound (origEnd <= origStart); the last window of that segment is still the
        // honest answer, and a -1 must never reach a caller that indexes with it.
        return if (fallback >= 0) fallback else windows.size - 1
    }

    /** TRIMMED samples to ORIGINAL samples, through one segment's offset, clamped into it. */
    private fun toOriginal(seg: VadSeg, trimmedSamples: Int): Int =
        (seg.origStart + (trimmedSamples - seg.trimmedStart))
            .coerceIn(seg.origStart, maxOf(seg.origStart, seg.origEnd))
}
