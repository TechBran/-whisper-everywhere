package com.whispereverywhere.npu

/**
 * The NPU tier's SENTENCE BOUNDS, read out of the timestamp tokens the decoder emits (4.11
 * Task 3): a flat `[t0cs, t1cs, byteStart, byteEnd]` per sentence, the same stride-4 flattening
 * every other piece of geometry in this app uses.
 *
 * ### Why the tier needs this at all
 *
 * Until 4.10.1 this tier published no timing whatsoever — `NpuWhisperBackend.lastGeometry` is
 * null while the NPU arm is live, because that arm runs its own encoder and decoder on the HTP
 * and never touches whisper.cpp's VAD filter. 4.10.1's stopgap re-ran a standalone VAD on the
 * speaker thread and gave the whole chunk ONE speaker. On the owner's Fold6 that stopgap met
 * hard-cut media with no pauses in it, the VAD returned one 9-15 s segment per chunk, and every
 * chunk collapsed to a single label about seventy seconds in
 * (`docs/measurements/2026-09-18-speaker-spike.md` §Session 7). The fix is not a better VAD: it
 * is asking the decoder when it said each sentence, which it has always been able to answer and
 * was being told not to. See `docs/superpowers/specs/2026-09-19-speaker-boundaries-design.md` §2.
 *
 * ### Pure, and deliberately not a method on the backend
 *
 * Nothing here names [QnnAsrNative] — that object carries `System.loadLibrary("qnnasr")` and
 * would kill any JVM test that touched it — and nothing here names [WhisperBpeDecoder] either.
 * The detokeniser arrives as a lambda, which is what lets `NpuSentencesTest` hand in a fake that
 * RENDERS every id it is given: the real decoder drops every id at or above EOT silently, so a
 * timestamp leaking into it would cost nothing visible, and the one thing this file must never do
 * is put a timestamp in the text its own byte offsets index into.
 *
 * ### What this parse does NOT assume
 *
 * Whisper's reference decoder constrains timestamps with STATEFUL logit masks: they must come in
 * pairs, they must increase, and `<|notimestamps|>` is masked outright
 * (`whisper.cpp/src/whisper.cpp:6483-6594`). This tier's decode loop applies one STATIC mask per
 * segment — the rules that depend on what was already emitted cannot travel as data — so the
 * token stream reaching this parse may be shapes whisper's own never is. Every one of them is
 * read rather than assumed:
 *
 *  - a separator emitted ONCE instead of twice still opens the next sentence;
 *  - text before any timestamp starts at 0.00;
 *  - a sentence left open at the end of the array — the repetition cut at `qnn_asr.cpp:3353` and
 *    the token budget both truncate mid-sentence — ends at [CHUNK_END_CS];
 *  - a pair with nothing between it is not a sentence;
 *  - and a stream whose bounds go BACKWARDS is refused outright, which is the one case this file
 *    answers with nothing rather than with a best effort. See [of].
 */
object NpuSentences {

    /**
     * Whisper's timestamp slots are 0.02 s apart, so one slot is two centiseconds. The unit is
     * centiseconds because that is the unit every other geometry array in this app is in —
     * `lastVadSegments`, `lastWhisperSegments`, `lastTokenTimes` — and `SpeakerSpans` converts
     * exactly one way.
     */
    const val CENTISECONDS_PER_SLOT: Int = 2

    /**
     * `[t0cs, t1cs, byteStart, byteEnd]`. Named here as well as in `SpeakerSpans` because the two
     * files agree on it by convention rather than by a shared type, and a stride that drifts is a
     * silent re-interpretation of every number in the array.
     */
    const val STRIDE: Int = 4

    /**
     * 3,000 centiseconds — 30.00 s, whisper's window and the last time its timestamp table can
     * name ([WhisperTokenFamily.TIMESTAMP_SLOTS] slots, the first at 0.00).
     *
     * It is the end assigned to a sentence the token array left open, and it is deliberately the
     * WINDOW's end rather than the chunk's real duration, which this pure function does not know:
     * a chunk is 6-8 s of audio padded to 30 s before the encoder sees it. The consumer clips —
     * `SpeakerSpans` intersects every sentence with the VAD spans it already has — so an
     * over-long final bound costs nothing, while an under-long one would drop the tail of the
     * chunk out of every window and hand those words no speaker at all.
     */
    const val CHUNK_END_CS: Int = (WhisperTokenFamily.TIMESTAMP_SLOTS - 1) * CENTISECONDS_PER_SLOT

    /**
     * Sentence bounds for one decoded segment, or an EMPTY array when there are none.
     *
     * Empty is the ordinary, meaningful answer, not a failure code: it is what a stream carrying
     * no timestamp tokens produces, which is every decode this tier ran before 4.11 and every
     * decode that terminates on EOT before its first timestamp. `SpeakerAssigner`'s VAD route
     * reads it as "label this chunk as a whole" — 4.10.1's behaviour exactly.
     *
     * @param tokens the decoder's output slice, `out.copyOf(written)`. Ids at or above
     *        [WhisperTokenFamily.timestampBegin] are read as times; ids below [
     *        WhisperTokenFamily.eot] are text; everything between the two — SOT, the language
     *        block, the control tokens — is skipped, because the real detokeniser drops exactly
     *        those and the byte offsets here must index into the text it produces.
     * @param family the vocabulary these ids belong to. Required and never defaulted, for
     *        [NpuDecodePolicy]'s reason: 50364 is `<|0.00|>` under `whisper-small` and
     *        `<|notimestamps|>` under `large-v3`, so a fixed base shifts every sentence in the
     *        chunk by one slot under the other family and nothing anywhere can see it.
     * @param decode the detokeniser — in production `WhisperBpeDecoder::decode`. Called once for
     *        the whole text and once per sentence; a few hundred ids either way.
     * @return `[t0cs, t1cs, byteStart, byteEnd]` per sentence, ascending, tiling the decoded
     *         text's bytes with no gap and no overlap. EMPTY when the stream carries no
     *         timestamps, when no sentence carried text, or when the parse could not be trusted
     *         (see below) — in every case the caller keeps the behaviour it had without this.
     */
    fun of(tokens: IntArray, family: WhisperTokenFamily, decode: (IntArray) -> String): IntArray {
        val text = ArrayList<Int>(tokens.size)   // the text ids, in order, specials removed
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val from = ArrayList<Int>()              // index into `text`, inclusive
        val until = ArrayList<Int>()             // index into `text`, exclusive

        var open = NONE      // the centisecond a sentence is open at, or NONE
        var openAt = 0       // where in `text` the open sentence's words begin
        var lastTs = NONE    // the most recent timestamp seen anywhere in the stream
        var sawTimestamp = false

        for (id in tokens) {
            if (id >= family.timestampBegin) {
                val cs = (id - family.timestampBegin) * CENTISECONDS_PER_SLOT
                sawTimestamp = true
                lastTs = cs
                if (open != NONE && text.size > openAt) {
                    // It CLOSES the open sentence. Whisper then emits the same id again to open
                    // the next one; a loop with no pair rule may not, which the `open == NONE`
                    // branch below covers by opening at `lastTs` when the words arrive.
                    starts += open
                    ends += if (cs > open) cs else open
                    from += openAt
                    until += text.size
                    open = NONE
                } else {
                    // It OPENS one — or re-opens an empty one, which is a pair with nothing
                    // between it and is not a sentence.
                    open = cs
                    openAt = text.size
                }
            } else if (id < family.eot) {
                if (open == NONE) {
                    // Words with no timestamp in front of them: they belong to the stretch that
                    // began at the last time the decoder named, or at 0.00 if it has named none.
                    open = if (lastTs != NONE) lastTs else 0
                    openAt = text.size
                }
                if (id >= 0) text += id
            }
            // Otherwise: EOT, SOT, a language id or a control token. No text, no time — and it
            // must NOT close a sentence, because the detokeniser drops it and the words either
            // side of it are one stretch of text.
        }
        if (open != NONE && text.size > openAt) {
            // The array ended mid-sentence: the repetition cut, the token budget, or the position
            // cap. There is no later timestamp to take — one arriving after these words would
            // have closed the sentence — so the window's end stands in. See [CHUNK_END_CS].
            starts += open
            ends += CHUNK_END_CS
            from += openAt
            until += text.size
        }
        if (!sawTimestamp || starts.isEmpty()) return IntArray(0)

        // ASCENDING, OR NOTHING. Nothing on this tier enforces whisper's increasing-timestamp
        // mask (whisper.cpp:6588-6594) — it is stateful and this loop's mask is static — so a
        // stream that runs backwards is reachable. Answering EMPTY gives the chunk 4.10.1's one
        // coarse label; answering with overlapping bounds would hand the window splitter ranges
        // it cannot order and cut the text at a place no sentence ends. Spec §3: fail downhill.
        for (i in 1 until starts.size) {
            if (starts[i] < ends[i - 1]) return IntArray(0)
        }

        val all = text.toIntArray()
        val whole = decode(all)
        val out = ArrayList<Int>(starts.size * STRIDE)
        val rebuilt = StringBuilder(whole.length)
        var byteCursor = 0
        for (i in starts.indices) {
            val piece = decode(all.copyOfRange(from[i], until[i]))
            rebuilt.append(piece)
            val length = piece.toByteArray(Charsets.UTF_8).size
            // A sentence whose words decode to no bytes at all (whisper's empty-string slot is a
            // real, readable id) is dropped rather than published as a zero-width span: it has a
            // time and nothing to hang on it, and a consumer that cut the text there would emit
            // an empty speaker run.
            if (length > 0) {
                out += starts[i]
                out += ends[i]
                out += byteCursor
                out += byteCursor + length
            }
            byteCursor += length
        }

        // THE SAME EQUALITY TASK 1 MADE STRUCTURAL ON THE CPU SIDE, for the same reason: these
        // offsets are only meaningful if the pieces ARE the text. They can fail to be — a token
        // is a sequence of BYTES and a multi-byte character can straddle a sentence boundary, at
        // which point each side decodes its half to U+FFFD and the joined string is longer than
        // the whole. Checked rather than argued away, and a mismatch drops the whole chunk's
        // bounds instead of shifting every offset after it.
        if (rebuilt.toString() != whole || byteCursor != whole.toByteArray(Charsets.UTF_8).size) {
            return IntArray(0)
        }
        return out.toIntArray()
    }

    /**
     * "No timestamp here". A sentinel rather than a nullable Int because this is the inner loop of
     * a per-segment parse and 0 is a legal centisecond — `<|0.00|>` is the commonest first token
     * whisper emits.
     */
    private const val NONE: Int = -1
}
