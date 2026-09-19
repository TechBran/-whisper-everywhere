package com.whispereverywhere.transcription.speakers

import kotlin.math.sqrt

/**
 * Turns a stream of voice embeddings into speaker numbers — the whole decision of "who is this?",
 * pure and unit-tested (4.10 Task 2; the rules are session 2 of
 * `docs/measurements/2026-09-18-speaker-spike.md`).
 *
 * Nothing here knows about Android, sherpa, ONNX or audio. It takes a vector and a duration and
 * answers a 1-based speaker id. That is deliberate: every judgement the feature can get WRONG in
 * front of a user lives in this file, and all of it is reachable from a JUnit test with vectors
 * whose similarities are arithmetic (see `SpeakerTrackerTest`).
 *
 * ### Every number in this file was MEASURED, and none of them was chosen here
 *
 * The first version of this class shipped the spec's guesses — a running EMA centroid, 0.55/0.45,
 * a 1.5 s floor — and the owner's first device session took them apart: one voice became five
 * speakers, and *"no single pair of thresholds separates them"*. Session 2 dumped 132 segments'
 * fingerprints and audio to the PC, scored five embedding models on them and simulated tracker
 * rules offline. What survived is below, and it is the whole of what survived:
 *
 *  - [MIN_MATCH_SECONDS] **1.0 s** / [MIN_OPEN_SECONDS] **1.5 s** / [MIN_UPDATE_SECONDS] **2.0 s**
 *    — the THREE graded duration gates (session 4; see below). Session 2 had one gate at 2.0 s
 *    doing all three jobs, and session 4 showed what that costs on conversational audio.
 *  - [RECENT_K] **5** — matching is the MAXIMUM similarity over a speaker's last five
 *    fingerprints. The running-mean centroid is gone as the match basis.
 *  - [T_SAME] **0.50** / [T_NEW] **0.30** — the centre of the working region, twelve of whose
 *    bands gave exactly 1/2/3 on the three clips.
 *  - [CONFIRM_N] **2** — qualifying segments before a speaker is CONFIRMED.
 *  - [MAX_SPEAKERS] **8** — unchanged, the spec's cap.
 *
 * `SpeakerTrackerTest.theConstantsAreTheONESTheMeasurementDocSettled` pins all eight against that
 * doc, because the one way this file can be silently wrong again is a number nudged by somebody
 * reasoning rather than measuring.
 *
 * ### The match: a MAXIMUM over recent fingerprints, not a mean
 *
 * Each speaker keeps its last [RECENT_K] fingerprints. A new embedding's score against a speaker
 * is the **largest** cosine against any of them, and the best-scoring speaker wins. This is the
 * single change that made the numbers work, and the reason is in session 1's failure: a mean
 * absorbs everything it is fed, so one embedding taken over music or laughter dragged the centroid
 * off the voice and made every later genuine segment of that same voice look foreign. A maximum
 * over five recent vectors cannot be dragged — a bad fingerprint sits in the set as one more
 * candidate and is simply never the best match for anyone.
 *
 * A mean is still computed, in exactly one place and for exactly one purpose: the merge step below
 * compares WHOLE SPEAKERS to each other, and there the average is the right summary.
 *
 * ### The three bands, and why the middle one exists
 *
 *  - **>= [tSame]** — that speaker, and the fingerprint joins its recent set.
 *  - **< [tNew]** — a NEW speaker, if fewer than [maxSpeakers] are live. Past the cap, the CLOSEST
 *    live speaker: a classifier with no "none of the above" answer left must still answer, and it
 *    does not learn from a segment it was told to guess about.
 *  - **in between** — the CURRENT speaker, unchanged, and nothing is updated. Without this band a
 *    borderline segment flips the label, and the flip is not a one-off: the wrong new speaker sits
 *    between two voices, so every later segment of both is borderline against it and the
 *    transcript alternates speakers mid-sentence.
 *
 * ### THE THREE DURATION GATES — what a segment's length buys it (session 4)
 *
 * Session 2 shipped ONE gate at 2.0 s that governed three separate rights at once, and session 4
 * put that in the owner's hands: the 20:39 dump held *"clearly distinct voices"* and **eleven of
 * fifteen segments under 2.0 s**, so nothing could open, nothing could confirm, and the whole
 * session rendered with no labels at all — *"I couldn't get any different speakers"*. The gate is
 * now graded, because the three rights need different amounts of evidence:
 *
 * | length | recognised? | may open / confirm? | joins the recent set? |
 * |---|---|---|---|
 * | < 1.0 s ([MIN_MATCH_SECONDS]) | no — inherits | no | no |
 * | 1.0-1.5 s | **yes**, at >= [tSame] | no | no |
 * | 1.5-2.0 s ([MIN_OPEN_SECONDS]) | yes | **yes** | no (except the opener itself) |
 * | >= 2.0 s ([MIN_UPDATE_SECONDS]) | yes | yes | **yes** |
 *
 * The order of the three is the argument. RECOGNISING a person who already exists is the cheapest
 * claim — the speaker's recent set is doing the work and a one-second fingerprint only has to beat
 * [tSame] against it — so it is allowed first. CLAIMING a new person costs a whole id and every
 * later segment is matched against the fingerprint that opened it, so it waits for 1.5 s.
 * TEACHING is the most expensive of all and stays at session 2's 2.0 s for session 1's reason: a
 * short fingerprint taken over music, laughter or applause that enters a speaker's recent set
 * drags the whole speaker off the voice, and the recent set is the only thing ever matched
 * against.
 *
 * A segment in the MATCH-ONLY tier that does not reach [tSame] inherits [currentSpeaker] — there
 * is no band to fall into and no new speaker to open, so the two outcomes are "that one" and "the
 * one before".
 *
 * The similarity is still MEASURED and published on [lastBestSimilarity] at every tier: the spike
 * reads that column as a distribution, and a reading that was taken is a reading worth having even
 * when the decision ignored it.
 *
 * **The accepted limit, in the owner's own words** (*"if we can detect that, great; if not, we'll
 * live with it"*): an interruption shorter than a second is labelled as the current speaker. That
 * is not a bug to be fixed later, it is the trade this rule makes on purpose — session 4 only
 * moved where it sits.
 *
 * The one place a short segment still decides something is the very first one of a session: there
 * is nobody to inherit from, so [assign] answers **0** — "unlabelled" — and the caller lets the
 * run inherit. It does NOT open speaker 1 off a half-second of audio the way the first version
 * did.
 *
 * ### CONFIRMED, and the merge that follows from it
 *
 * A speaker becomes CONFIRMED on its [confirmN]'th qualifying segment — a segment of at least
 * [MIN_OPEN_SECONDS] that either opened it or matched it at >= [tSame]. A band assignment, an
 * over-cap guess and everything in the MATCH-ONLY tier are not qualifying: they are the three
 * cases the tracker was explicitly unsure about, and counting them as evidence of a person is how
 * one wrong label becomes a wrong speaker for the rest of a session.
 *
 * Then, after every chunk ([endChunk]), every UNCONFIRMED speaker whose centroid is within
 * [tSame] of a CONFIRMED speaker's centroid is **merged into it**: its fingerprints move across and
 * its id is retired. This is the online-then-refine half of the design, and it is what the panel's
 * rewritability buys — *"labels can appear a chunk late but must not flicker"*. The ids already
 * emitted for a merged speaker are not rewritten in place (this object cannot reach them); they
 * are reported through [remap] so a later relabel can fix them.
 *
 * Only unconfirmed speakers are ever absorbed and only confirmed ones ever absorb, so a merge
 * chain is at most one link long and [remap] never needs resolving through itself.
 *
 * ### AND THE SECOND LOOK: this object can be told it was wrong
 *
 * Everything above is decided with the evidence that existed at the moment, which is what a live
 * panel needs and what session 6 showed a greedy matcher can get irrecoverably wrong — one id
 * swallowing a whole conversation. [SpeakerReclusterer] looks at the session's fingerprints all
 * at once and answers who was really speaking; [reseed] is how that answer becomes this object's
 * state, so the NEXT online decision starts from the retrospective truth rather than from the
 * recent set that locked on. The latch is the one thing a reseed can only raise.
 *
 * ### Ids are monotone, and the cap counts LIVE speakers
 *
 * An id is a slot, issued in order, and it is never reused inside a session even after the slot is
 * merged away — so a remap entry can only ever point backwards, and a label already on screen can
 * never come to mean a different person. The cap is on LIVE speakers, which is what makes the
 * merge worth doing: absorbing a spurious singleton gives a genuine later voice its room back.
 * The price, stated plainly, is that a long session with several merges can issue an id above
 * [maxSpeakers] while fewer than [maxSpeakers] voices are live; the renderer is free to compact
 * ids at paint time, and [remap] is the map it needs to do it.
 *
 * ### Threading
 *
 * Not synchronised, and does not need to be: one instance per session, touched only from the
 * single-thread `speaker-embed` executor that produced the embedding (plan Task 3). The panel reads
 * [secondSpeakerConfirmed] through the callback that hops to Main, never off this object directly.
 */
class SpeakerTracker(
    val tSame: Float = T_SAME,
    val tNew: Float = T_NEW,
    val maxSpeakers: Int = MAX_SPEAKERS,
    val recentK: Int = RECENT_K,
    val confirmN: Int = CONFIRM_N,
) {

    /**
     * ONE speaker's whole state: its recent fingerprints, how much it has earned, and — if it was
     * absorbed — who took it over.
     */
    private class Voice(first: FloatArray) {

        /** The last [RECENT_K] L2-normalised fingerprints, oldest first. Never empty while live. */
        val recent: ArrayDeque<FloatArray> = ArrayDeque<FloatArray>().also { it.addLast(first) }

        /** Segments of at least [MIN_OPEN_SECONDS] that opened this speaker or matched it. */
        var qualifying: Int = 1

        var confirmed: Boolean = false

        /** 0 while live; otherwise the 1-based id of the confirmed speaker that absorbed this one. */
        var absorbedBy: Int = 0

        val live: Boolean get() = absorbedBy == 0
    }

    /** Every slot this session has issued, in id order. Index `i` is speaker `i + 1`. */
    private val voices = ArrayList<Voice>(MAX_SPEAKERS)

    /** The fingerprint width this session works in, from the first embedding it accepted. */
    private var dim = 0

    private var current = 0
    private var latched = false
    private var lastBest = Float.NaN

    /** merged id -> survivor id, accumulated for the whole session. Insertion-ordered. */
    private val merges = LinkedHashMap<Int, Int>()

    /**
     * The cosine similarity the LAST [assign] weighed its decision against — the best live
     * speaker's best recent fingerprint — or `NaN` when no similarity was measured: an unusable
     * embedding, or the first speaker of a session, who has nobody to be compared with.
     *
     * It exists for ONE reader: the spike's `best=` column, read as a distribution over a real
     * conversation, which is where [tSame] and [tNew] came from in the first place. `NaN` rather
     * than 0 because 0 is a real reading — two orthogonal voices — and the column is about a
     * threshold.
     *
     * A segment under [MIN_OPEN_SECONDS] publishes its similarity too, even though the decision
     * ignored it: a reading that was taken is worth having.
     *
     * Valid only immediately after an [assign] on the same thread, like every one-slot diagnostic
     * in this app. [reset] clears it.
     */
    val lastBestSimilarity: Float get() = lastBest

    /** How many speakers are LIVE. Merged slots do not count; never above [maxSpeakers]. */
    val speakerCount: Int get() = voices.count { it.live }

    /** How many LIVE speakers are CONFIRMED — the number the latch is a threshold on. */
    val confirmedCount: Int get() = voices.count { it.live && it.confirmed }

    /**
     * The per-session latch, restated by session 2: **two CONFIRMED speakers exist**. Until it is
     * true the panel shows no label at all and a one-speaker session is byte-for-byte today's
     * output; once true it never goes back, because the panel has already been rewritten with
     * labels and un-rewriting it is the only thing on screen that would move backwards.
     *
     * The old rule was "two speakers, each with one segment >= 1.5 s". This one is strictly
     * harder: two segments each, and the merge pass has had its say first — which is exactly the
     * evidence session 1 showed was missing when one voice latched a label onto itself.
     */
    val secondSpeakerConfirmed: Boolean get() = latched

    /** The last speaker assigned, or 0 before the first assignment of a session. */
    fun currentSpeaker(): Int = current

    /**
     * Every merge this session has made: **merged id -> survivor id**.
     *
     * The ids in it were already emitted — handed to the sink, printed in a diag line, possibly
     * painted — before the merge pass decided the speaker was not a separate person. This map is
     * how they get fixed: a relabel applies it to whatever it holds, and because only unconfirmed
     * speakers are absorbed and only confirmed ones absorb, no entry's value is ever itself a key.
     *
     * Accumulated for the session and cleared by [reset]. A copy, so a caller cannot edit it.
     */
    fun remap(): Map<Int, Int> = LinkedHashMap(merges)

    /**
     * Assigns [embedding] — one fingerprint window's voice — to a 1-based speaker id.
     * [durationSec] is that window's length in seconds, and it is a GRADED gate: the three floors
     * [MIN_MATCH_SECONDS], [MIN_OPEN_SECONDS] and [MIN_UPDATE_SECONDS] buy, in turn, the right to
     * be recognised, the right to claim a new person, and the right to teach.
     *
     * Returns 0 only when nothing has been assigned yet and this segment cannot start a session —
     * an unusable embedding, or one shorter than [MIN_OPEN_SECONDS].
     */
    fun assign(embedding: FloatArray, durationSec: Float): Int {
        // Cleared FIRST, so [lastBestSimilarity] can never describe a previous call: every early
        // return below is a decision taken WITHOUT measuring a similarity, and the spike's
        // `best=` column has to say so rather than repeat the last real number it saw.
        lastBest = Float.NaN
        val v = normalised(embedding) ?: return current
        val mayMatch = durationSec >= MIN_MATCH_SECONDS
        val mayOpen = durationSec >= MIN_OPEN_SECONDS
        val mayUpdate = durationSec >= MIN_UPDATE_SECONDS

        // Nobody yet. A 1.5 s segment starts the session; anything shorter leaves it unlabelled,
        // because there is no current speaker to inherit from and half a second of audio is not
        // evidence of a person. The caller reads 0 as "unlabelled" and lets the run inherit.
        if (voices.isEmpty()) return if (mayOpen) open(v) else current
        if (v.size != dim) return current

        var bestIndex = -1
        var best = Float.NEGATIVE_INFINITY
        for (i in voices.indices) {
            val voice = voices[i]
            if (!voice.live) continue
            val similarity = bestRecent(voice, v)
            if (similarity > best) {
                best = similarity
                bestIndex = i
            }
        }
        // Every live speaker holds at least one fingerprint, so a non-empty slot list always
        // produces a match. Defensive only: a 0 must never leave this function once a speaker
        // exists.
        if (bestIndex < 0) return current
        lastBest = best

        val liveCount = speakerCount
        val id: Int = when {
            // Under 1.0 s: inherit, decide nothing. FIRST, so it outranks even a perfect match.
            !mayMatch -> inherit(bestIndex)
            // 1.0-1.5 s — the MATCH-ONLY tier (session 4). A confident match takes that
            // speaker's number and NOTHING else happens: no open, no update, no credit toward a
            // confirmation. Anything less confident inherits, band or not, because the two
            // decisions this tier is barred from making are exactly the two the lower bands ask
            // for. This is the tier that makes rapid turn-taking labellable at all: the 20:39
            // dump had eleven of fifteen segments here.
            !mayOpen -> if (best >= tSame) bestIndex + 1 else inherit(bestIndex)
            best >= tSame -> {
                credit(bestIndex, v, learn = mayUpdate)
                bestIndex + 1
            }
            best < tNew && liveCount < maxSpeakers -> return open(v)
            // At the cap the closest live speaker takes it, and learns nothing from it.
            best < tNew -> bestIndex + 1
            // The hysteresis band: the current speaker keeps the floor, and no set moves.
            else -> inherit(bestIndex)
        }
        current = id
        return id
    }

    /**
     * END OF CHUNK — the refine half. Every unconfirmed live speaker whose centroid is within
     * [tSame] of a confirmed speaker's centroid is merged into the nearest such speaker.
     *
     * Returns the merges made by THIS call, `merged id -> survivor id`, empty when nothing moved;
     * [remap] is the session-long accumulation of the same thing. The caller publishes this so a
     * chunk's diagnostic can say what changed under the ids it just reported.
     *
     * The confirmed centroids are snapshotted BEFORE any merging, so the pass does not depend on
     * the order the unconfirmed speakers happen to sit in: every candidate is measured against the
     * speakers as they stood at the end of the chunk.
     */
    fun endChunk(): Map<Int, Int> {
        val moved = LinkedHashMap<Int, Int>()
        val anchors = ArrayList<Pair<Int, FloatArray>>(voices.size)
        for (i in voices.indices) {
            val voice = voices[i]
            if (voice.live && voice.confirmed) centroid(voice)?.let { anchors += i to it }
        }
        if (anchors.isEmpty()) return moved

        for (i in voices.indices) {
            val voice = voices[i]
            if (!voice.live || voice.confirmed) continue
            val c = centroid(voice) ?: continue
            var into = -1
            var best = Float.NEGATIVE_INFINITY
            for ((index, anchor) in anchors) {
                val similarity = dot(c, anchor)
                if (similarity > best) {
                    best = similarity
                    into = index
                }
            }
            if (into < 0 || best < tSame) continue

            // The fingerprints MOVE. The survivor is the speaker that will be matched against from
            // now on, and the evidence that these two are one voice belongs in its recent set —
            // otherwise the very segments that proved the merge are thrown away by it.
            val survivor = voices[into]
            for (fingerprint in voice.recent) push(survivor, fingerprint)
            voice.recent.clear()
            voice.absorbedBy = into + 1
            // The qualifying count does not travel: the survivor is already confirmed, so there is
            // nothing left for it to earn, and a merged speaker's segments were never evidence of
            // a separate person in the first place.
            moved[i + 1] = into + 1
            merges[i + 1] = into + 1
            if (current == i + 1) current = into + 1
        }
        relatch()
        return moved
    }

    /**
     * RE-SEEDS this tracker from a retrospective clustering — the other half of "online for
     * display, retrospective for truth" (spike session 6).
     *
     * [SpeakerReclusterer] has just looked at the whole session at once and answered who was
     * really speaking. Relabelling the panel fixes the PAST; this fixes the FUTURE, and without
     * it the next chunk would be decided by exactly the state that went wrong — session 6's 03:27
     * dump locked onto one id and never let the other speaker back, because a single similar
     * fingerprint among five recent ones is enough to match.
     *
     * After this call the tracker's speakers ARE the clusters, numbered the same way: slot `i` is
     * cluster `i + 1`, its recent set is the cluster's [SpeakerReclusterer.Cluster.longest]
     * fingerprints (the longest windows are the least ambiguous evidence the cluster has), and it
     * is confirmed exactly when the cluster cleared the mass bar. So the ids this tracker emits
     * from here on are in the SAME id space as the labels the panel was just rewritten with,
     * which is what keeps the next chunk's online ids from contradicting the relabel above them.
     *
     * Three things are deliberately NOT reset:
     *  - **the latch.** [secondSpeakerConfirmed] never goes back, here least of all: the panel has
     *    already been rewritten with labels and un-rewriting it is the one thing on screen that
     *    would move backwards. A reseed can only ever RAISE it (via [relatch]).
     *  - **[lastBestSimilarity]**, which describes the last [assign] and is read immediately after
     *    one, on this thread.
     *  - the fingerprint width, which cannot change inside a session.
     *
     * The session's accumulated [remap] IS cleared, and must be: its keys are ids from before the
     * renumbering, and an old key that collides with a new id would relabel a live speaker into
     * somebody else. The caller has already published those merges chunk by chunk, and the window
     * map it is about to apply supersedes every one of them.
     *
     * A [relabel] this tracker cannot honour — no clusters, ids that are not 1..n in order, or a
     * cluster with no usable seed — is IGNORED entirely rather than half-applied: a tracker left
     * holding half a reseed would answer ids that belong to neither id space.
     */
    fun reseed(relabel: SpeakerReclusterer.Relabel) {
        val clusters = relabel.clusters
        if (clusters.isEmpty()) return
        val rebuilt = ArrayList<Voice>(clusters.size)
        for ((index, cluster) in clusters.withIndex()) {
            if (cluster.id != index + 1) return
            val seeds = cluster.longest.mapNotNull { normalised(it) }
            if (seeds.isEmpty()) return
            val voice = Voice(seeds.first())
            for (i in 1 until seeds.size) push(voice, seeds[i])
            // The evidence the cluster stands on, carried across: a cluster the reclusterer
            // confirmed is a speaker this tracker has no more to learn about, and one it did not
            // (the degenerate "nothing cleared the bar" answer) still has to earn its confirmation
            // the online way, on its next two qualifying segments.
            voice.qualifying = cluster.fingerprints
            voice.confirmed = cluster.confirmed
            rebuilt += voice
        }
        voices.clear()
        voices.addAll(rebuilt)
        dim = rebuilt.first().recent.first().size
        merges.clear()
        // The floor follows its owner. An id with no entry in the map belonged to a window the
        // cap dropped or to nobody at all; 0 reads as "unlabelled" and the next segment inherits
        // from whoever it matches, which is the honest answer for a speaker this pass did not see.
        current = relabel.map[current]?.takeIf { it in 1..rebuilt.size } ?: 0
        relatch()
    }

    /** Forgets every speaker, the numbering, the merges and the latch — one call per session start. */
    fun reset() {
        voices.clear()
        merges.clear()
        dim = 0
        current = 0
        latched = false
        lastBest = Float.NaN
    }

    // ------------------------------------------------------------------ internals

    /** The current speaker, or — defensively, since a non-empty slot list always has one — the best. */
    private fun inherit(bestIndex: Int): Int = current.takeIf { it > 0 } ?: (bestIndex + 1)

    /**
     * Opens a speaker on [v], which becomes its FIRST recent fingerprint — the one exception to
     * [MIN_UPDATE_SECONDS], and a necessary one: a speaker whose recent set is empty can never be
     * matched by anybody and has no centroid for the merge pass to weigh. A 1.5-2.0 s opener is
     * therefore defined by a fingerprint it would not have been allowed to ADD, and that is the
     * price of letting a short turn claim a person at all.
     */
    private fun open(v: FloatArray): Int {
        if (voices.isEmpty()) dim = v.size
        voices += Voice(v)
        current = voices.size
        confirmIfEarned(voices.last())
        return current
    }

    /**
     * A confident match on a segment long enough to OPEN: it earns credit toward a confirmation
     * always, and joins the speaker's recent set only when [learn] — i.e. only at
     * [MIN_UPDATE_SECONDS] and above. The two rights were one until session 4 split them.
     */
    private fun credit(index: Int, v: FloatArray, learn: Boolean) {
        val voice = voices[index]
        if (learn) push(voice, v)
        voice.qualifying++
        confirmIfEarned(voice)
    }

    private fun confirmIfEarned(voice: Voice) {
        if (voice.confirmed || voice.qualifying < confirmN) return
        voice.confirmed = true
        relatch()
    }

    private fun relatch() {
        if (!latched && confirmedCount >= 2) latched = true
    }

    /** Appends [v], dropping the oldest once the set is [recentK] deep. */
    private fun push(voice: Voice, v: FloatArray) {
        voice.recent.addLast(v)
        while (voice.recent.size > recentK) voice.recent.removeFirst()
    }

    /** The MAXIMUM cosine over [voice]'s recent fingerprints — the match basis (session 2). */
    private fun bestRecent(voice: Voice, v: FloatArray): Float {
        var best = Float.NEGATIVE_INFINITY
        for (fingerprint in voice.recent) {
            val similarity = dot(v, fingerprint)
            if (similarity > best) best = similarity
        }
        return best
    }

    /**
     * The normalised MEAN of [voice]'s recent fingerprints — used by [endChunk] and nowhere else.
     *
     * The merge step asks whether two whole speakers are one person, and an average is the right
     * summary of a speaker for that question; a maximum is the right summary for "is this segment
     * his?", which is why the two live apart. Null when the mean is not normalisable, which for
     * unit inputs means a set that cancelled itself out.
     */
    private fun centroid(voice: Voice): FloatArray? {
        val first = voice.recent.firstOrNull() ?: return null
        val sum = DoubleArray(first.size)
        for (fingerprint in voice.recent) {
            for (i in sum.indices) sum[i] += fingerprint[i].toDouble()
        }
        return normalised(FloatArray(sum.size) { i -> (sum[i] / voice.recent.size).toFloat() })
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
         * inherits the label of the segment before it and never reaches [assign] — an embedding of
         * a fraction of a second is a vector with no speaker in it.
         *
         * It is the same number as [MIN_MATCH_SECONDS], and since session 4 that is no longer a
         * coincidence to be explained away: every segment this floor pays an embedder for can now
         * do something with the answer.
         */
        const val MIN_EMBED_SECONDS: Float = 1.0f

        /**
         * **1.0 s** — the shortest segment that may be MATCHED to an existing speaker: assigned
         * the best-scoring one when that score is at least [T_SAME], and the current speaker
         * otherwise. It opens nobody, updates nobody and earns no confirmation.
         *
         * Session 4 of `docs/measurements/2026-09-18-speaker-spike.md`, the 20:39 dump: eleven of
         * fifteen segments were under the old single 2.0 s gate, on *"clearly distinct voices"*,
         * and nothing could be labelled at all. A one-second fingerprint is too thin to CLAIM a
         * person exists; it is thick enough to recognise one who already does.
         *
         * Since the 2026-09-18 late session it is also `SpeakerSpans.MIN_WINDOW_SECONDS`, the
         * shortest window the sentence splitter will cut. That is not a coincidence either: the
         * splitter cuts a window exactly when this tier can do something with it, and the refusal
         * to open, confirm or teach off one stays HERE, where it was measured.
         */
        const val MIN_MATCH_SECONDS: Float = 1.0f

        /**
         * **1.5 s** — the shortest segment that may OPEN a new speaker (when its best similarity
         * is below [T_NEW]) or count toward a [CONFIRM_N]. Session 4 of
         * `docs/measurements/2026-09-18-speaker-spike.md`.
         *
         * It was 2.0 s from session 2 until session 4 starved on rapid turn-taking. Claiming a new
         * person still needs more evidence than recognising a known one, which is why this sits
         * above [MIN_MATCH_SECONDS] rather than joining it.
         */
        const val MIN_OPEN_SECONDS: Float = 1.5f

        /**
         * **2.0 s** — the shortest segment whose fingerprint is pushed into an existing speaker's
         * recent set. Session 2's floor, kept at session 2's value and for session 1's reason: a
         * short fingerprint taken over music or laughter that JOINS a speaker's state makes every
         * later genuine segment of that voice look foreign, and the recent set is the only thing
         * that is matched against.
         *
         * The one fingerprint that enters a set without clearing this bar is the one that OPENED
         * the speaker — see [assign].
         */
        const val MIN_UPDATE_SECONDS: Float = 2.0f

        /**
         * **5** — how many of a speaker's most recent fingerprints a match is the maximum over.
         * Session 2 of `docs/measurements/2026-09-18-speaker-spike.md`.
         */
        const val RECENT_K: Int = 5

        /**
         * **0.50** — at or above this, the same speaker, and the fingerprint is learned. The centre
         * of the working region in session 2 of
         * `docs/measurements/2026-09-18-speaker-spike.md`.
         */
        const val T_SAME: Float = 0.50f

        /**
         * **0.30** — below this against every live speaker, a new one. Session 2 of
         * `docs/measurements/2026-09-18-speaker-spike.md`.
         */
        const val T_NEW: Float = 0.30f

        /**
         * **2** — qualifying segments before a speaker is CONFIRMED, and so before it can hold a
         * label or anchor a merge. Session 2 of
         * `docs/measurements/2026-09-18-speaker-spike.md`.
         */
        const val CONFIRM_N: Int = 2

        /** Live speakers per session, spec §3.2. Past this the closest live speaker takes the segment. */
        const val MAX_SPEAKERS: Int = 8
    }
}
