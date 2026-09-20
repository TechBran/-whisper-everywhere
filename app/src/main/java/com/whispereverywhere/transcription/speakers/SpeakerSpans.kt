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
 * ONE stretch of a chunk's ORIGINAL audio that gets ONE voice fingerprint (4.10, spike session 4;
 * widened from the rare long segment to nearly every one by the 2026-09-18 LATE session).
 *
 * Until session 4 this was always a whole [VadSeg] and the type did not exist. The RISK it exists
 * for is failure mode B: the endpointer cuts on silence, so when nobody pauses it can hand over
 * six to fourteen seconds in one segment — and if two voices share those seconds they share one
 * fingerprint, the dominant one wins it, and the tracker is asked who is speaking in a recording
 * of two people at once. The owner's own reading of his 20:31 session was *"the VAD doesn't seem
 * to chunk on the boundaries"*, and this is that cut, made where whisper already found one.
 *
 * **Session 4 bounded the cut to a shape it could not demonstrate; the 2026-09-18 LATE session
 * measured what that bound cost.** 20:31 turned out to be the ONE-VOICE clip (the owner, later
 * the same evening: a long narration with a single narrator), so its 0.84 pairwise median was the
 * tracker being RIGHT and failure mode B was **not demonstrated** by that night's dumps. The
 * split was therefore set to fire only past five seconds — design for a podcast guest's
 * two-minute answer, deliberately conservative. Then the graded-gates build went out and the
 * owner reported the residue: *"it is working a lot better for quick back and forths … the
 * boundaries is where the speaker switch is just not catching the beginning of when someone
 * starts to speak … the bulk of it, yes, is correct."* His 02:12 dump says why in one number: 218
 * fingerprint windows, **median 3.0 s**, and only 26 of them over five seconds. The per-sentence
 * cut applied to about a tenth of the audio, so across the 2.5-5 s majority a new speaker's FIRST
 * sentence sat inside the previous speaker's window and wore the previous speaker's label. That
 * is the whole of the lag he heard, and it is why [SpeakerSpans.LONG_SEGMENT_SECONDS] is now
 * 2.0 s: a window is a SENTENCE in nearly every segment, and a sentence boundary is where a new
 * speaker usually begins.
 *
 * What is left over is a genuine interruption MID-sentence, which no boundary whisper drew can
 * catch — that would need word-level change-point detection, and it is the next lever rather than
 * a defect in this one.
 *
 * A window is therefore a VAD segment, or a slice of one along its whisper-segment boundaries.
 * [origStart] / [origEnd] index the RAW chunk, like [VadSeg.origStart] and for the
 * same reason: the trimmed buffer whisper saw does not outlive the JNI call.
 *
 * [vadIndex] is kept because a window is still *of* a segment — the diag line counts both, and
 * the geometry that produced a window is the only thing that can explain it.
 */
data class SpeakerWindow(
    val vadIndex: Int,
    val origStart: Int,
    val origEnd: Int,
    /**
     * HOW MANY OF THIS WINDOW'S SAMPLES ARE SPEECH, as opposed to how many it SPANS — and the
     * difference is exactly the silence [wholeChunkWindows] folded in when it coalesced a short
     * segment into its predecessor (4.10, round 1 of review).
     *
     * It exists because three separate decisions are made on "how long is this window", and all
     * three are questions about SPEECH, never about wall time: [SpeakerTracker.MIN_EMBED_SECONDS]
     * (is there enough voice here to fingerprint at all), [SpeakerTracker.assign]'s three graded
     * gates (may this window match / open / teach a speaker), and the NPU route's dominant-window
     * pick (which voice speaks for the chunk's one label). Handing any of them a span that
     * includes a pause lets a window with 1.4 s of voice in it open a speaker and outvote one
     * with 3.0 s.
     *
     * **The default IS the right answer on the geometry route**, and that is not a convenience:
     * [windows] only ever SPLITS a VAD segment, never joins two, so every pause inside one of its
     * windows is a pause Silero itself judged too short to end speech — at most
     * `min_silence_duration_ms` (100 ms, whisper.cpp's default, which this app does not change).
     * Span and speech are the same measurement there to within that, and writing the default as
     * `origEnd - origStart` keeps the CPU and GPU tiers byte-for-byte what they were.
     *
     * [copy] does NOT recompute it — which is the point: the one caller that changes [origEnd]
     * without adding speech is the coalescing merge, and it states the new sum itself.
     */
    val speechSamples: Int = origEnd - origStart,
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
     * **2.0 s** — at or past this, a VAD segment holding two or more whisper segments is
     * fingerprinted per whisper segment instead of once (spike session 4, failure mode B; this
     * VALUE from the 2026-09-18 late session).
     *
     * It is a floor on the SEGMENT, not on the window, and it is INCLUSIVE: a segment of exactly
     * two seconds with two sentences in it is cut. Below it the single fingerprint is kept byte
     * for byte, and it has to be — a segment under 2.0 s cannot hold two windows at
     * [MIN_WINDOW_SECONDS], so there is nothing to cut it into.
     *
     * Where the number comes from, and why it moved. Session 4 read its five dumps as two
     * populations by segment length — three ordinary conversational ones at 2.7-3.1 s median,
     * one long one at 6.3 s median and 14.3 s p90 — and put the floor at 5 s, BETWEEN them, so
     * the cut fired only on the shape that can hide a second voice and ordinary turn-taking was
     * left exactly as it was. That was the conservative call for a failure mode the data had not
     * demonstrated. The 2026-09-18 late session then measured the cost of leaving turn-taking
     * alone: 218 windows, median 3.0 s, 131 of them at 2.5 s or longer and only 26 over five, so
     * the cut applied to about a tenth of the audio and the label change lagged a new speaker's
     * first sentence almost everywhere else. 2.0 s is not a new population boundary; it is the
     * SMALLEST segment that can hold two windows, which is the honest place for a floor whose
     * only job now is to say "there is a second window to cut here".
     */
    const val LONG_SEGMENT_SECONDS: Float = 2.0f

    /**
     * **1.0 s** — the shortest window a split may produce. Adjacent whisper segments are
     * coalesced until the window reaches it, and a trailing short remainder joins the window
     * before it rather than standing alone.
     *
     * It is [SpeakerTracker.MIN_MATCH_SECONDS], the LOWEST of the tracker's three graded gates,
     * and that is the whole rule: a window is worth cutting exactly when the tracker is allowed
     * to say something about it. It is also [SpeakerTracker.MIN_EMBED_SECONDS], so every window a
     * split produces is actually fingerprinted rather than silently inheriting.
     *
     * It was [SpeakerTracker.MIN_OPEN_SECONDS] until the 2026-09-18 late session, on the argument
     * that a window which could never OPEN a speaker was not worth an embedding. The graded gates
     * make that argument wrong: a 1.0-1.5 s window is in the MATCH-ONLY tier, so it CAN take a
     * known speaker's number on a confident match — and that is exactly the case the late session
     * is about, a short opening sentence from the other person. Such a window still cannot open a
     * speaker, confirm one or teach one, and that refusal belongs to [SpeakerTracker.assign] and
     * not to this splitter. The splitter's job is to hand the sentence over; judging it is the
     * tracker's, and a splitter that pre-judged would be a second set of gates nobody measured.
     */
    const val MIN_WINDOW_SECONDS: Float = 1.0f

    /**
     * **0.30 s** — the widest gap [wholeChunkWindows] will coalesce a short segment ACROSS
     * (4.10, round 1 of review). Beyond it the short segment stands alone and inherits, exactly
     * as a short leading segment does.
     *
     * The NPU route's merge is the only place in this file that joins two SEPARATE speech
     * segments, and that is what makes a bound necessary here and unnecessary in [windows].
     * [windows] splits one segment and never joins two, so the longest pause it can fold into a
     * window is `min_silence_duration_ms` — 100 ms, whisper.cpp's default, which this app does
     * not change — because a longer silence is what ENDED the segment. The gap between two
     * segments has no such ceiling: it is bounded only by the 6-8 s chunk. Unbounded, a 0.3 s
     * back-channel three seconds after the previous sentence produced a "window" that was
     * two-thirds room tone, and that window was then handed to the embedder as a fingerprint and
     * to the tracker as its duration — enough to clear all three graded gates on 1.4 s of voice.
     *
     * Where 0.30 comes from: the bounds this route is given are Silero's PADDED ones
     * (`speech_pad_ms = 150`, applied at both ends), so the gap that survives between two
     * segments is the real silence MINUS 300 ms. A padded gap of 0.30 s is therefore about
     * 0.60 s of actual silence — already six times the longest pause the geometry route can fold
     * into one window, so the bound is generous towards merging rather than against it. A pause
     * longer than that is a turn boundary, not a breath.
     *
     * It is not a tuned constant and there is no measurement behind the exact figure; it is a
     * ceiling chosen to keep this route's windows comparable to the other route's. The device
     * session can move it, and [speechSamples] means the dominant-window pick is right either
     * way.
     */
    const val MAX_COALESCE_GAP_SECONDS: Float = 0.30f

    /**
     * **4.0 s** — the shortest stretch [windows] will cut at a WORD, when it has token times to
     * cut at (4.11 Task 2; the Fold6's session 7).
     *
     * It is not a new number: it is `2 * `[LONG_SEGMENT_SECONDS], written that way in the source
     * so it cannot drift from the floor it is derived from. The derivation is the same sentence
     * twice over — [LONG_SEGMENT_SECONDS] is the length at which this file already believes a
     * stretch is big enough to hide a second voice, so the smallest stretch worth bisecting is
     * the one whose two halves each clear it. Below 4.0 s a word-level cut would buy two halves
     * of ONE sentence, which is not what the split is for: spike session 4's failure mode is two
     * VOICES sharing a window, and the 02:12 dump's median window was 3.0 s, so firing there
     * would roughly double the session's embedding count to answer a question nobody asked.
     *
     * **It is INCLUSIVE and it RECURSES**, and both follow from the derivation rather than from
     * taste: a stretch of exactly 4.0 s is exactly the one that yields two windows of
     * [LONG_SEGMENT_SECONDS], and a 15 s pause-free segment — the Fold6's actual shape, one
     * label over a minute of two people — would still be two 7.5 s windows after a single cut,
     * which is the same defect with a smaller number. Each half is therefore offered the same
     * test until it is too short to take it.
     *
     * WHAT IT DOES NOT DO: it does not find a speaker change. Nothing in layer 1 does. It makes
     * a long pause-free stretch ADDRESSABLE at word grain so the tracker gets more than one
     * fingerprint out of it, and so that layer 2's change points — when they exist — have
     * somewhere legal to land. A wrong cut costs a paragraph break in the middle of one
     * person's sentence; the cut it replaces cost the other person's whole turn.
     */
    const val TOKEN_CUT_SECONDS: Float = 2 * LONG_SEGMENT_SECONDS

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
     * THE CHUNK'S FINGERPRINT WINDOWS, in chunk order — [sentenceWindows], and then, where the
     * tier told us when it said each word, a stretch still long enough to hide a second voice is
     * cut at the nearest WORD (4.11 Task 2).
     *
     * **`tokenTimes` EMPTY IS TODAY'S ANSWER, byte for byte**, and that is the contract every
     * other caller in the app relies on: the two-argument overload passes an empty array, the
     * NPU tier has no token times at all, and `lastTokenTimes` is documented to be empty — or to
     * miss a stretch of the text — whenever whisper's own per-segment check refused to publish.
     * Timing is additive here exactly as it is in the native layer.
     *
     * **WHY A SECOND KIND OF CUT AT ALL.** [sentenceWindows] needs two or more whisper segments
     * inside a VAD segment to have anywhere to cut. Session 7's Fold6 dump is the case where it
     * has neither: a hard-cut interview has no pauses, so Silero returns ONE 9-15 s segment per
     * chunk, whisper decodes it as one run-on sentence, and `windows = 1` chunk after chunk —
     * one speaker label over a minute of two people. A token edge is the only boundary that
     * exists inside a pause-free sentence, and this is the machinery that uses it.
     *
     * The rule, exactly: each window [sentenceWindows] produced is bisected while it spans at
     * least [TOKEN_CUT_SECONDS]. The candidate is the stretch's own midpoint; the cut goes to
     * the token edge minimising `|edge - candidate|` **among the edges of that window's OWN VAD
     * segment that leave both sides at least [MIN_WINDOW_SECONDS]**, and ties go to the earlier
     * edge, as everywhere else in this file. No qualifying edge means no cut — the window stands
     * exactly as [sentenceWindows] built it, which is the fail-downhill rule the spec asks for.
     *
     * Token times arrive on the TRIMMED timeline with byte offsets into the returned buffer,
     * like `lastWhisperSegments` and for the same reason, so an edge is attributed to a VAD
     * segment by the same overlap rule and mapped through THAT segment's own offset before it is
     * an edge at all. Flattening them into one list would let one segment's words cut another
     * at a place nobody spoke.
     *
     * @param tokenTimes `[t0cs, t1cs, byteStart, byteEnd]` * n from
     *        `WhisperNative.lastTokenTimes`, or empty.
     */
    fun windows(raw: IntArray, vad: List<VadSeg>, tokenTimes: IntArray): List<SpeakerWindow> {
        val base = sentenceWindows(raw, vad)
        if (tokenTimes.isEmpty() || base.isEmpty()) return base
        val edges = tokenEdges(tokenTimes, vad)
        val minWindowSamples = (MIN_WINDOW_SECONDS * SAMPLE_RATE).toInt()
        val cutSamples = (TOKEN_CUT_SECONDS * SAMPLE_RATE).toInt()
        val out = ArrayList<SpeakerWindow>(base.size)
        for (w in base) {
            val own = edges[w.vadIndex]
            if (own.isEmpty()) {
                out += w
            } else {
                bisect(w.vadIndex, w.origStart, w.origEnd, own, minWindowSamples, cutSamples, out)
            }
        }
        return out
    }

    /**
     * [windows] with no token times — the pre-4.11 signature, kept so that no call site outside
     * the timing work has to know this array exists, and so that every tier and every test
     * without it keeps exactly the behaviour it had.
     */
    fun windows(raw: IntArray, vad: List<VadSeg>): List<SpeakerWindow> =
        windows(raw, vad, IntArray(0))

    /** Every token edge of the chunk, on the ORIGINAL timeline, bucketed by VAD segment. */
    private fun tokenEdges(tokenTimes: IntArray, vad: List<VadSeg>): Array<IntArray> {
        val n = tokenTimes.size / STRIDE
        // Sorted and de-duplicated: a token's end is its neighbour's start, so a flat list would
        // be half duplicates, and the nearest-edge search below reads best-first on ties only
        // because the edges ascend.
        val acc = Array(vad.size) { sortedSetOf<Int>() }
        for (i in 0 until n) {
            val o = i * STRIDE
            val t0 = tokenTimes[o] * SAMPLES_PER_CENTISECOND
            val t1 = tokenTimes[o + 1] * SAMPLES_PER_CENTISECOND
            val index = vadIndexFor(t0, t1, vad)
            val seg = vad[index]
            acc[index] += toOriginal(seg, t0)
            acc[index] += toOriginal(seg, t1)
        }
        return Array(vad.size) { acc[it].toIntArray() }
    }

    /**
     * `[from, to)` as one window, or as two halves cut at the token edge nearest its middle, and
     * then the same question asked of each half.
     *
     * The recursion terminates on the span, not on a depth counter: every cut leaves both sides
     * at least [MIN_WINDOW_SECONDS] and a side is only cut again when it still spans
     * [TOKEN_CUT_SECONDS], so the depth is bounded by `chunkSeconds / MIN_WINDOW_SECONDS` — a
     * few dozen frames on a 30 s chunk, at a leaf count bounded by the same ratio.
     */
    private fun bisect(
        vadIndex: Int,
        from: Int,
        to: Int,
        edges: IntArray,
        minWindowSamples: Int,
        cutSamples: Int,
        out: MutableList<SpeakerWindow>,
    ) {
        if (to - from < cutSamples) {
            out += SpeakerWindow(vadIndex = vadIndex, origStart = from, origEnd = to)
            return
        }
        val candidate = from + (to - from) / 2
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (e in edges) {
            // Both halves must be worth fingerprinting: MIN_WINDOW_SECONDS is the embedder's
            // floor as well as the tracker's lowest gate, so a shorter half would cost an
            // embedding to produce a window the tracker may say nothing about.
            if (e - from < minWindowSamples || to - e < minWindowSamples) continue
            val distance = if (e > candidate) e - candidate else candidate - e
            // Strictly less, over ascending edges: ties go to the EARLIER edge, the same
            // convention `spans` rule 3 uses.
            if (distance < bestDistance) {
                bestDistance = distance
                best = e
            }
        }
        if (best < 0) {
            out += SpeakerWindow(vadIndex = vadIndex, origStart = from, origEnd = to)
            return
        }
        bisect(vadIndex, from, best, edges, minWindowSamples, cutSamples, out)
        bisect(vadIndex, best, to, edges, minWindowSamples, cutSamples, out)
    }

    /**
     * THE SENTENCE SPLIT — one window per SENTENCE in nearly every segment since the 2026-09-18
     * late session, one per VAD segment only where there is nothing to cut.
     *
     * A VAD segment is split when BOTH are true: it is at least [LONG_SEGMENT_SECONDS] long, and
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
     *     not reach it joins the window before it rather than standing alone. At 1.0 s that
     *     coalescing is rare — most sentences clear it on their own — which is the point: one
     *     window per sentence is the shape a speaker change can be seen in.
     *  3. The windows then PARTITION the VAD segment with no audio left out: the first starts at
     *     `origStart`, the last ends at `origEnd`, and each interior boundary is the mapped start
     *     of the first whisper segment of the window after it. The coalescing measured SPEECH;
     *     the boundaries hand the embedder everything between, pauses included.
     *
     * A segment that is not split yields exactly `SpeakerWindow(i, origStart, origEnd)` — the
     * pre-session-4 shape, which is still the answer for a short segment, a one-sentence segment
     * and a segment whisper decoded nothing in.
     *
     * @param raw `[t0cs, t1cs, byteStart, byteEnd]` * n, in TEXT order, from `lastWhisperSegments`.
     * @param vad the same chunk's [vadSegments]. EMPTY yields no windows: there is no timeline.
     */
    private fun sentenceWindows(raw: IntArray, vad: List<VadSeg>): List<SpeakerWindow> {
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
            // The floor is INCLUSIVE: 2.0 s is the smallest segment that can hold two windows at
            // MIN_WINDOW_SECONDS, so a segment exactly that long with two sentences in it is
            // exactly the case the late session asked to be cut, not the case to exclude.
            if (seg.origEnd - seg.origStart < longSegmentSamples || ws.size < 2) {
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
     * THE NPU TIER'S WINDOWS — built from `WhisperNative.vadSegmentsOf`'s `[start, end]` sample
     * pairs alone, with no whisper geometry anywhere in the answer (4.10, the Fold6 defect).
     *
     * [windows] above needs two things this tier cannot give it: VAD segments on BOTH timelines,
     * and whisper's own segment boundaries to cut the long ones along. The QNN decoder exposes no
     * token or sentence timestamps at all, so there is nothing to cut a segment along and no
     * second timeline to map through — **[raw] is already on the caller's original timeline**,
     * which is why a [SpeakerWindow] comes straight out of a pair. Saying it plainly here is
     * better than a [VadSeg] whose trimmed half is a copy of its original half: that would look
     * like two measurements agreeing when it is one measurement written twice.
     *
     * The ONE rule that carries over from [windows] is the short-window one, and it carries over
     * because it is about the EMBEDDER rather than about whisper: a segment shorter than
     * [MIN_WINDOW_SECONDS] joins its predecessor, so a burst of half-second back-channels becomes
     * one fingerprintable window instead of three that each inherit a label without being heard.
     * The FIRST segment has no predecessor, so a short opener stands alone and takes fate 1 —
     * the same answer the CPU route gives a short leading VAD segment.
     *
     * **The merge is BOUNDED, and that is the one place this route may not simply copy [windows]**
     * (round 1 of review). There, rule 3 hands the embedder everything between two sentences it
     * coalesced and the pause is harmless, because both sentences live inside ONE VAD segment and
     * the pause is therefore under `min_silence_duration_ms`. Here the two sides of the join are
     * separate segments and the silence between them is, by construction, silence Silero judged
     * long enough to END speech — bounded by nothing but the chunk. So a join happens only across
     * at most [MAX_COALESCE_GAP_SECONDS]; past that the short segment stands alone and inherits.
     * Without the bound a 0.3 s back-channel could drag three seconds of room tone into a
     * "window" that was then fingerprinted as a voice and counted as three seconds of it.
     *
     * The merged window spans from its predecessor's start to the joining segment's end, pause
     * included — the embedder still gets one contiguous slice — but it carries the SUM of the
     * speech it actually holds in [SpeakerWindow.speechSamples], and that sum, never the span, is
     * what the gates and the dominant-window pick are decided on. The silence BETWEEN separate
     * windows is left out, exactly as in [windows].
     *
     * [SpeakerWindow.vadIndex] is the index of the speech segment a window STARTS at, so it is
     * strictly increasing and a coalesced window is named by its first segment. The chunk's raw
     * segment count is `raw.size / 2` and is the assigner's `segs=`; a degenerate pair is dropped
     * rather than repaired.
     *
     * A trailing odd int is DROPPED: half a segment is not a segment.
     */
    fun wholeChunkWindows(raw: IntArray): List<SpeakerWindow> {
        val n = raw.size / 2
        if (n == 0) return emptyList()
        val minWindowSamples = (MIN_WINDOW_SECONDS * SAMPLE_RATE).toInt()
        val maxGapSamples = (MAX_COALESCE_GAP_SECONDS * SAMPLE_RATE).toInt()
        val out = ArrayList<SpeakerWindow>(n)
        for (i in 0 until n) {
            val start = raw[i * 2]
            val end = raw[i * 2 + 1]
            if (end <= start) continue
            val previous = out.lastOrNull()
            // `start - previous.origEnd` can be NEGATIVE: the 150 ms pads at each end are applied
            // per segment, so two close segments can be handed over overlapping. That is a gap of
            // zero for this test and not a reason to refuse the join.
            val gap = (start - (previous?.origEnd ?: start)).coerceAtLeast(0)
            if (previous != null && end - start < minWindowSamples && gap <= maxGapSamples) {
                out[out.size - 1] = previous.copy(
                    // `maxOf` for the same reason `gap` has a floor: padded bounds can overlap,
                    // and a joining segment that ends inside its predecessor must not SHORTEN
                    // the window it joined. Defensive — the VAD's ends are monotonic — but an
                    // inverted window would reach the embedder as a zero-length slice.
                    origEnd = maxOf(previous.origEnd, end),
                    // The SPEECH it now holds — its own plus this segment's, and not the pause
                    // between them. This is the number every gate downstream is decided on. An
                    // overlap makes it a slight OVER-count; the assigner clamps it to the span
                    // it is given, which is the same clamp the window's own bounds get.
                    speechSamples = previous.speechSamples + (end - start),
                )
                continue
            }
            out += SpeakerWindow(vadIndex = i, origStart = start, origEnd = end)
        }
        return out
    }

    /**
     * Cuts [bytes] — the UTF-8 `transcribeRaw` returned — into spans, one per run of decoded
     * segments that share a fingerprint WINDOW.
     *
     * @param raw `[t0cs, t1cs, byteStart, byteEnd]` * n, in TEXT order, from `lastWhisperSegments`.
     * @param vad the same chunk's [vadSegments]. EMPTY yields no spans, by the rule above.
     * @param tokenTimes the same chunk's `WhisperNative.lastTokenTimes`, or empty. EMPTY IS
     *        TODAY'S ANSWER, byte for byte, exactly as in [windows] — and the two arguments have
     *        to agree, which is why one caller passes the same array to both.
     * @param windows the same chunk's [windows]. The default IS the right answer and the
     *        parameter exists for ONE caller: `LocalWhisperEngine` computes the list once and
     *        hands the same object to the assigner, because two independently computed window
     *        lists that disagree would put the ids of one window on another's text.
     *
     * **WHY THE TOKENS ARE NEEDED HERE TOO, and not only in [windows]** (4.11 Task 2). Rule 5
     * below attributes a WHOLE decoded segment by its midpoint. That is exactly right while
     * every window boundary is also a segment boundary — which was true until a window could be
     * cut at a word. After it, a 15 s run-on sentence cut into four windows would still be ONE
     * span wearing ONE id: four fingerprints, four tracker decisions, and no way for any of them
     * to reach the text. The token byte offsets are what make the second half of a sentence
     * addressable, and without this half of the change the window cut buys nothing a user sees.
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
     *  7. **A segment whose tokens straddle a window is cut between them** (4.11), at the byte
     *     offset the next token starts at, each piece taking the window its OWN midpoint falls
     *     in. The pieces TILE the segment — the first opens where the segment opens and the last
     *     closes where it closes — so no byte of the transcript is dropped by being between two
     *     tokens, and rules 1, 2 and 6 then apply to each piece unchanged. A segment no token
     *     covers (empty [tokenTimes], or the stretch the native side's per-segment check
     *     refused) is one piece, which IS rule 5.
     */
    fun spans(
        raw: IntArray,
        bytes: ByteArray,
        vad: List<VadSeg>,
        tokenTimes: IntArray = IntArray(0),
        windows: List<SpeakerWindow> = windows(raw, vad, tokenTimes),
    ): List<SpeakerSpan> {
        val n = raw.size / STRIDE
        if (n == 0 || vad.isEmpty() || bytes.isEmpty() || windows.isEmpty()) return emptyList()
        val out = ArrayList<SpeakerSpan>(n)
        for (i in 0 until n) {
            val o = i * STRIDE
            val start = raw[o + 2].coerceIn(0, bytes.size)
            val end = raw[o + 3].coerceIn(start, bytes.size)
            if (end == start) continue
            val t0 = raw[o] * SAMPLES_PER_CENTISECOND
            val t1 = raw[o + 1] * SAMPLES_PER_CENTISECOND
            for (piece in pieces(start, end, t0, t1, tokenTimes, vad, windows)) {
                val from = piece[1]
                val to = piece[2]
                if (to <= from) continue
                val text = TranscriptText.clean(String(bytes, from, to - from, Charsets.UTF_8))
                if (text.isEmpty()) continue
                val index = piece[0]
                val previous = out.lastOrNull()
                if (previous != null && previous.windowIndex == index) {
                    out[out.size - 1] = previous.copy(
                        text = TextJoin.assemble(listOf(previous.text, text)),
                    )
                } else {
                    out.add(SpeakerSpan(windowIndex = index, text = text))
                }
            }
        }
        return out
    }

    /**
     * Rule 7: one decoded segment as `[windowIndex, byteFrom, byteTo]` pieces that TILE
     * `[byteStart, byteEnd)`, in text order.
     *
     * One piece is the common answer and the only possible one without token times — rule 5,
     * unchanged. More than one happens where a window boundary was cut at a word INSIDE this
     * segment, and the boundary between two pieces is the next token's own `byteStart`, never a
     * computed offset: a cut in the middle of a UTF-8 sequence would decode to U+FFFD on exactly
     * the multilingual models that made the byte return necessary.
     */
    private fun pieces(
        byteStart: Int,
        byteEnd: Int,
        t0Samples: Int,
        t1Samples: Int,
        tokenTimes: IntArray,
        vad: List<VadSeg>,
        windows: List<SpeakerWindow>,
    ): List<IntArray> {
        val vadIndex = vadIndexFor(t0Samples, t1Samples, vad)
        val whole = listOf(
            intArrayOf(windowIndexAt(midOf(vad[vadIndex], t0Samples, t1Samples), vadIndex, windows),
                byteStart, byteEnd),
        )
        if (tokenTimes.isEmpty()) return whole
        val n = tokenTimes.size / STRIDE
        val out = ArrayList<IntArray>(2)
        var from = byteStart
        var current = -1
        for (k in 0 until n) {
            val o = k * STRIDE
            val tokenStart = tokenTimes[o + 2]
            val tokenEnd = tokenTimes[o + 3]
            // INSIDE this segment's bytes, in text order. The tokens of a segment were pushed by
            // the same native loop that pushed the segment, so they are already ordered and
            // already inside it; the test is a containment check rather than a search because a
            // stale process-global array can name bytes of another chunk entirely.
            if (tokenStart < byteStart || tokenEnd > byteEnd || tokenEnd <= tokenStart) continue
            val seg = vad[vadIndex]
            val at = midOf(seg, tokenTimes[o] * SAMPLES_PER_CENTISECOND,
                tokenTimes[o + 1] * SAMPLES_PER_CENTISECOND)
            val index = windowIndexAt(at, vadIndex, windows)
            if (current < 0) {
                current = index
            } else if (index != current && tokenStart > from) {
                out += intArrayOf(current, from, tokenStart)
                from = tokenStart
                current = index
            }
        }
        // No token covered this segment: rule 5, which is also the whole of the pre-4.11 answer.
        if (current < 0) return whole
        out += intArrayOf(current, from, byteEnd)
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

    /**
     * A `[t0, t1)` on the TRIMMED timeline as ONE original sample inside [seg] — the point rule 5
     * attributes by, for a decoded segment and (since 4.11) for a single token alike.
     *
     * The last sample this segment could be attributed to, so a decoded segment sitting entirely
     * in the zero-padded tail lands INSIDE the final window rather than past it.
     */
    private fun midOf(seg: VadSeg, t0Samples: Int, t1Samples: Int): Int =
        toOriginal(seg, (t0Samples + t1Samples) / 2).coerceAtMost(seg.origEnd - 1)

    /** Rule 5: the window of [vadIndex] whose bounds hold [mid], else that segment's last. */
    private fun windowIndexAt(mid: Int, vadIndex: Int, windows: List<SpeakerWindow>): Int {
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
