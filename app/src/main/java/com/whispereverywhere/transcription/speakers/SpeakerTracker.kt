package com.whispereverywhere.transcription.speakers

import kotlin.math.sqrt

/**
 * Turns a stream of voice embeddings into speaker numbers — the whole decision of "who is this?",
 * pure and unit-tested (4.10 Task 2, spec §3.2 step 3).
 *
 * Nothing here knows about Android, sherpa, ONNX or audio. It takes a vector and a duration and
 * answers a 1-based speaker id. That is deliberate: every judgement the feature can get WRONG in
 * front of a user lives in this file, and all of it is reachable from a JUnit test with vectors
 * whose similarities are arithmetic (see `SpeakerTrackerTest`).
 *
 * ### The three bands, and why the middle one exists
 *
 * Cosine similarity against each known speaker's centroid, best match wins, and then:
 *
 *  - **>= [tSame]** — that speaker, and its centroid moves [ema] of the way toward the new
 *    embedding. A voice drifts over a session (calm to animated, close to far from the mic) and a
 *    centroid frozen at the first segment stops matching the person it was made from.
 *  - **< [tNew]** — a NEW speaker, but only if fewer than [maxSpeakers] are known AND the segment
 *    is at least [MIN_NEW_SPEAKER_SECONDS] long. Past the cap, the CLOSEST known speaker: a
 *    classifier with no "none of the above" answer left must still answer.
 *  - **in between** — the CURRENT speaker, unchanged, and no centroid moves. This band is the
 *    single most important line in the file. Without it a borderline segment flips the label, and
 *    the flip is not a one-off: the wrong new centroid sits between two voices, so every later
 *    segment of both is borderline against it and the transcript alternates speakers mid-sentence.
 *
 * ### What never happens
 *
 * **A borderline or over-cap segment never moves a centroid.** Attributing a segment is a label
 * decision; dragging a centroid 20 % toward a voice we were explicitly unsure about is how one
 * wrong label becomes a wrong speaker for the rest of the session. Only a `>= tSame` match updates.
 *
 * **A short segment never opens a speaker.** [MIN_NEW_SPEAKER_SECONDS] is the spec's answer to a
 * two-word interjection (§5): it takes the current speaker's label instead. The exception is the
 * FIRST segment of a session — there is nobody to be confused with and nobody to inherit from, so
 * any usable embedding opens speaker 1.
 *
 * **An unusable embedding never guesses.** All-zero, NaN, empty, or a different width than the
 * centroids (a model swap mid-session, which cannot happen today) answers [currentSpeaker] — which
 * is **0** before anything has been assigned. The caller reads 0 as "unlabelled" and lets the run
 * inherit; normalising a zero vector would divide by zero and label the segment with whatever came
 * out.
 *
 * ### Thresholds
 *
 * [tSame] / [tNew] ship at the spec's CAM++ starting point, 0.55 / 0.45, and are **set by the
 * device session** (plan Task 4) from the `best=` distribution the spike logs — not by this file's
 * author and not by the spec.
 *
 * ### Threading
 *
 * Not synchronised, and does not need to be: one instance per session, touched only from the
 * single-thread `speaker-embed` executor that produced the embedding (plan Task 3). The panel reads
 * [secondSpeakerConfirmed] through the callback that hops to Main, never off this object directly.
 */
class SpeakerTracker(
    val tSame: Float = 0.55f,
    val tNew: Float = 0.45f,
    val maxSpeakers: Int = MAX_SPEAKERS,
    val ema: Float = 0.2f,
) {

    /** Per speaker, in id order: the L2-normalised running centroid of its confident matches. */
    private val centroids = ArrayList<FloatArray>(MAX_SPEAKERS)

    /** Per speaker: has it ever been given a segment of at least [MIN_NEW_SPEAKER_SECONDS]? */
    private val heldTheFloor = ArrayList<Boolean>(MAX_SPEAKERS)

    private var current = 0
    private var latched = false
    private var lastBest = Float.NaN

    /**
     * The cosine similarity the LAST [assign] weighed its decision against — the closest known
     * speaker's — or `NaN` when no similarity was measured: an unusable embedding, or the first
     * speaker of a session, who has nobody to be compared with.
     *
     * It exists for ONE reader, and that reader is why it is a property and not a return value:
     * the 4.10 device session sets [tSame] and [tNew] from the distribution of this number over a
     * real conversation (spec §3.2 step 3 defers both thresholds to the spike deliberately), so
     * the value has to leave this object without changing [assign]'s signature under the tests
     * that already pin it. `NaN` rather than 0 because 0 is a real reading — two orthogonal
     * voices — and the column this feeds is about to become a threshold.
     *
     * Valid only immediately after an [assign] on the same thread, like every one-slot diagnostic
     * in this app. [reset] clears it.
     */
    val lastBestSimilarity: Float get() = lastBest

    /** How many speakers this session has opened. Never above [maxSpeakers]. */
    val speakerCount: Int get() = centroids.size

    /**
     * The per-session latch of spec §3.2 step 5: two speakers, each with at least one segment of
     * [MIN_NEW_SPEAKER_SECONDS]. Until it is true the panel shows no label at all and a
     * one-speaker session is byte-for-byte today's output; once true it never goes back, because
     * the panel has already been rewritten with labels and un-rewriting it is the only thing on
     * screen that would move backwards.
     */
    val secondSpeakerConfirmed: Boolean get() = latched

    /** The last speaker assigned, or 0 before the first assignment of a session. */
    fun currentSpeaker(): Int = current

    /**
     * Assigns [embedding] — a voice fingerprint of one VAD segment — to a 1-based speaker id, per
     * the three bands above. [durationSec] is that segment's length in seconds; it gates only the
     * opening of a NEW speaker and the latch, never a match.
     *
     * Returns 0 only when the embedding is unusable and no speaker has been assigned yet.
     */
    fun assign(embedding: FloatArray, durationSec: Float): Int {
        // Cleared FIRST, so [lastBestSimilarity] can never describe a previous call: every early
        // return below is a decision taken WITHOUT measuring a similarity, and the spike's
        // `best=` column has to say so rather than repeat the last real number it saw.
        lastBest = Float.NaN
        val v = normalised(embedding) ?: return current
        if (centroids.isEmpty()) return open(v, durationSec)
        if (v.size != centroids[0].size) return current

        var best = 0
        var bestSimilarity = Float.NEGATIVE_INFINITY
        for (i in centroids.indices) {
            val similarity = dot(v, centroids[i])
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                best = i
            }
        }
        lastBest = bestSimilarity

        val id: Int = when {
            bestSimilarity >= tSame -> {
                moveToward(best, v)
                best + 1
            }
            bestSimilarity < tNew &&
                centroids.size < maxSpeakers &&
                durationSec >= MIN_NEW_SPEAKER_SECONDS -> return open(v, durationSec)
            bestSimilarity < tNew && centroids.size >= maxSpeakers -> best + 1
            // The hysteresis band, and the too-short-to-open case: the current speaker keeps the
            // floor. `best + 1` is unreachable in practice (a non-empty centroid list implies a
            // previous assignment) and is here so that a 0 can never leave this function.
            else -> if (current > 0) current else best + 1
        }
        noteFloor(id, durationSec)
        current = id
        return id
    }

    /** Forgets every speaker, the numbering and the latch — one call per session start. */
    fun reset() {
        centroids.clear()
        heldTheFloor.clear()
        current = 0
        latched = false
        lastBest = Float.NaN
    }

    // ------------------------------------------------------------------ internals

    private fun open(v: FloatArray, durationSec: Float): Int {
        centroids.add(v)
        heldTheFloor.add(durationSec >= MIN_NEW_SPEAKER_SECONDS)
        current = centroids.size
        relatch()
        return current
    }

    private fun noteFloor(id: Int, durationSec: Float) {
        if (durationSec < MIN_NEW_SPEAKER_SECONDS) return
        val index = id - 1
        if (index !in heldTheFloor.indices || heldTheFloor[index]) return
        heldTheFloor[index] = true
        relatch()
    }

    private fun relatch() {
        if (!latched && heldTheFloor.count { it } >= 2) latched = true
    }

    /** The EMA of spec §3.2: `(1 - ema) * centroid + ema * v`, renormalised so cosine stays a dot. */
    private fun moveToward(index: Int, v: FloatArray) {
        val centroid = centroids[index]
        val moved = FloatArray(centroid.size) { i -> centroid[i] * (1f - ema) + v[i] * ema }
        centroids[index] = normalised(moved) ?: centroid
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /**
     * L2-normalises on entry so that every later comparison is one dot product. Null for anything
     * that cannot be normalised — empty, non-finite, or a norm too small to divide by (which is a
     * failed embedding arriving as data rather than as the embedder's null).
     */
    private fun normalised(v: FloatArray): FloatArray? {
        if (v.isEmpty()) return null
        var sum = 0.0
        for (x in v) {
            if (!x.isFinite()) return null
            sum += x.toDouble() * x.toDouble()
        }
        val norm = sqrt(sum)
        if (norm < 1e-6) return null
        return FloatArray(v.size) { i -> (v[i] / norm).toFloat() }
    }

    companion object {
        /**
         * The shortest VAD segment worth fingerprinting (spec §3.2 step 2). Anything shorter
         * inherits the label of the segment before it and never reaches [assign] — CAM++ on a
         * fraction of a second is a vector with no speaker in it.
         */
        const val MIN_EMBED_SECONDS: Float = 1.0f

        /**
         * The shortest segment that may OPEN a speaker, or confirm the latch. A new voice saying
         * two words is not a new paragraph with a label (spec §5); it is the current speaker's
         * paragraph with a fingerprint we decline to act on.
         */
        const val MIN_NEW_SPEAKER_SECONDS: Float = 1.5f

        /** Speakers per session, spec §3.2. Past this the closest known speaker takes the segment. */
        const val MAX_SPEAKERS: Int = 8
    }
}
