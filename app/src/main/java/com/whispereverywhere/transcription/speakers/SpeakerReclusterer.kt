package com.whispereverywhere.transcription.speakers

import kotlin.math.sqrt

/**
 * WHICH fingerprint window of the session a label belongs to — the chunk's [seq] and the window's
 * index inside that chunk (4.10, spike session 6).
 *
 * It is the join key between the three objects that never touch each other: the assigner holds
 * fingerprints under it, [SpeakerReclusterer] answers labels under it, and a [Run] carries the
 * same pair (`Run.seq` / `Run.windowIndex`) so the sink can rewrite exactly the runs a relabel
 * speaks about. A pair, and not an index into anything: the runs of a session are a list that
 * grows, the fingerprints of a session are a list that is CAPPED and drops from the front, and an
 * index into either would mean something different to the other one every time it moved.
 */
data class WindowKey(val seq: Long, val windowIndex: Int)

/**
 * THE RETROSPECTIVE HALF — "online for display, retrospective for truth" (spike session 6).
 *
 * [SpeakerTracker] decides who is speaking with the evidence it has AT THE MOMENT, which is what
 * a live panel needs and what a greedy matcher can get irrecoverably wrong. Session 6 of
 * `docs/measurements/2026-09-18-speaker-spike.md` is the clean demonstration: in the 03:27 dump
 * the tracker gave id 1 to the first fourteen windows, then id 2 to **all fifty** that followed —
 * the *"one big run-on paragraph"* the owner reported — while a retrospective clustering of the
 * very same fingerprints cut them 40/14 into the two voices that were actually there. Nothing was
 * missing from the signal. What was missing was the second look.
 *
 * So this object takes the second look. It is handed the session's fingerprints so far and it
 * answers a [Relabel]: a label for every window, computed from all of them at once, with no
 * regard for what was decided live. The panel is rewritable (`TranscriptSink.relabel`), so the
 * answer can reach text the user has already read; the tracker is re-seedable
 * ([SpeakerTracker.reseed]), so the next online decision starts from this answer instead of from
 * the state that went wrong.
 *
 * ### Why word-level timestamps are NOT this lever
 *
 * The owner's instinct after session 5 was word-level boundaries, and session 6 measured why that
 * would not have helped: word timing refines WHERE a boundary falls once two voices are already
 * being told apart, and all three of that session's failures were failures to tell them apart at
 * all. Word-level change detection stays the lever for a mid-sentence interruption — AFTER this.
 *
 * ### The algorithm, and every choice in it
 *
 *  1. **Only fingerprints of at least [MIN_CLUSTERED_SECONDS] define a cluster.** That is
 *     [SpeakerTracker.MIN_OPEN_SECONDS], the online gate for claiming a person exists, and for the
 *     same reason: session 6's noise came from 2-3 s windows, and a window under 1.5 s is thinner
 *     still. The shorter ones are attached afterwards (step 5) — they are labelled, they never
 *     vote.
 *  2. **Duration-weighted average linkage on cosine similarity.** A cluster's similarity to
 *     another is the mean cosine over all member pairs, each pair weighted by the product of its
 *     two durations — so a 6-second window counts four times a 1.5-second one, which is the
 *     honest weighting when the thing being measured is "how much of this voice was heard". It is
 *     maintained exactly rather than approximated: the numerator `S(A,B) = Σ w_a·w_b·cos(a,b)` and
 *     the mass `W(A) = Σ w_a` are both additive under a merge, so `S(A∪B, C) = S(A,C) + S(B,C)`
 *     and the similarity is `S/(W·W)` at every step, identical to recomputing from scratch.
 *     Average linkage rather than single (which chains two voices together through one ambiguous
 *     window) or complete (which splits one voice on its single worst window — exactly session
 *     1's failure).
 *  3. **Merge while the best pair is at least [RECLUSTER_SIM].** 0.40 sits below the online
 *     `T_SAME` of 0.50 deliberately: a MEAN over many pairs is a stronger claim than a maximum
 *     over five, so it is allowed to be less demanding. Session 6's dumps bracket it — the
 *     03:27 session's within-speaker mean was 0.50 and its between-speaker mean 0.06, and the
 *     03:02 and 03:05 sessions sat at 0.31/0.05 and 0.35/0.01. Every one of those separations
 *     straddles 0.40.
 *  4. **A cluster is a SPEAKER only above a mass** — [MIN_CLUSTER_SECONDS] of total duration AND
 *     at least [MIN_CLUSTER_FINGERPRINTS] windows. Everything below is absorbed into the nearest
 *     confirmed cluster, and if nothing at all clears the bar the whole session is ONE speaker.
 *     This is the same judgement the online tracker's CONFIRM_N makes and it is the one that
 *     keeps a cough, a jingle or one bad window from becoming a person in the panel.
 *  5. **Short fingerprints then attach**, to the cluster their ONLINE id mostly went to, or — if
 *     that id contributed nothing to any cluster — to the nearest cluster by the same weighted
 *     mean. A short window is labelled by the company it keeps, never by its own thin vector.
 *  6. **Cluster ids are numbered by FIRST APPEARANCE in time**, so "Speaker 1" is the person who
 *     spoke first, which is what [SpeakerLabels] compacts against and what a reader expects.
 *
 * ### The two answers, and why there are two
 *
 * [Relabel.windowLabels] is EXACT: one label per window key, and the sink rewrites precisely
 * those runs. [Relabel.map] is the id-level summary — `onlineId -> clusterId` — and it is
 * lossy by construction: **an online id whose windows straddle two clusters is sent to whichever
 * cluster holds most of its duration**, because an id-level remap has no way to say "half of
 * these". That is the honest limit of a remap, it is why `windowLabels` exists beside it, and a
 * caller that can address windows should always prefer the window map.
 *
 * ### Cost, stated as arithmetic
 *
 * The similarity matrix is O(n²) in the fingerprint count, which is why the input is capped at
 * [MAX_RECLUSTER_FINGERPRINTS]. At the cap that is 600·599/2 ≈ 180 000 pairs, each a dot product
 * of 192 floats — about 34.5 million multiply-adds.
 *
 * The merge loop is the bigger term and it is worth stating honestly: it rescans the live pairs
 * every round, so its worst case (everything collapsing into one cluster, 598 merges) is
 * Σ k²/2 ≈ n³/6 ≈ 3.6·10⁷ divide-and-compares — of the same order as the matrix, and on a
 * conversation far cheaper, because the loop stops the moment no pair reaches [RECLUSTER_SIM].
 * Together, a few hundred milliseconds at the cap on the owner's Tab, on the `speaker-embed`
 * thread, below text that was delivered long ago. 600 windows is roughly 25 minutes of speech;
 * past it the OLDEST are dropped, and the windows they name simply keep the label they were last
 * given.
 *
 * Pure: no Android, no I/O, no state, no thread of its own. Like [SpeakerTracker] and
 * [SpeakerLabels], every judgement it makes is reachable from a JUnit test with vectors whose
 * similarities are arithmetic.
 */
object SpeakerReclusterer {

    /**
     * **0.40** — merge two clusters while their duration-weighted mean cosine is at least this.
     *
     * Session 6 of `docs/measurements/2026-09-18-speaker-spike.md`: the three dumps' within- /
     * between-speaker means were 0.50/0.06, 0.31/0.05 and 0.35/0.01, and 0.40 is the only round
     * number inside all three gaps. It is BELOW the online [SpeakerTracker.T_SAME] on purpose — a
     * mean over every member pair is a stronger claim than a maximum over five recent vectors, so
     * it can afford to ask for less.
     */
    const val RECLUSTER_SIM: Float = 0.40f

    /**
     * **6.0 s** — the total speech a cluster must hold before it is a SPEAKER rather than a
     * smudge. With [MIN_CLUSTER_FINGERPRINTS] it is the retrospective form of the tracker's
     * CONFIRM_N: two windows of three seconds, or four of 1.5, is the least evidence this file
     * will call a person.
     */
    const val MIN_CLUSTER_SECONDS: Float = 6.0f

    /** **2** — and never one: a single window is the shape every spurious speaker in the spike took. */
    const val MIN_CLUSTER_FINGERPRINTS: Int = 2

    /**
     * **1.5 s** — the shortest fingerprint that may DEFINE a cluster; shorter ones are attached
     * afterwards. It is [SpeakerTracker.MIN_OPEN_SECONDS], the online gate for claiming a person,
     * and the two are the same number for the same reason: the right to say a voice EXISTS costs
     * more evidence than the right to be recognised as one that already does.
     */
    const val MIN_CLUSTERED_SECONDS: Float = 1.5f

    /**
     * **600** — the most fingerprints one pass looks at, oldest dropped. It bounds an O(n²) pass
     * on a phone (see the class KDoc's arithmetic) at roughly 25 minutes of speech. A window
     * older than the cap keeps whatever label it was last given: it is off the bottom of the
     * panel and out of the tracker's state, so nothing it could be renamed to is visible.
     */
    const val MAX_RECLUSTER_FINGERPRINTS: Int = 600

    /**
     * **5** — how many committed chunks pass between reclusters while a session runs (the
     * assigner owns the counter; the constant lives here so the spike header can name it beside
     * the two thresholds). Roughly every 30-40 s of audio: often enough that a locked-on matcher
     * is corrected within a paragraph, rare enough that the O(n²) pass is a small fraction of the
     * embedding the same thread is already doing. Finalize runs one more, whatever the counter says.
     */
    const val RECLUSTER_EVERY_CHUNKS: Int = 5

    /**
     * ONE fingerprint of the session, as this pass needs it.
     *
     * @param windowKey which window it came from — the key the answer is published under.
     * @param emb the voice vector. Expected unit-normalised; normalised defensively here anyway,
     *        because every similarity below is a bare dot product and a caller that forgot would
     *        not fail, it would quietly cluster by loudness.
     * @param durSec the window's length in seconds — the WEIGHT, and the gate in steps 1 and 4.
     * @param onlineId what [SpeakerTracker] called it live, which is what [Relabel.map] is keyed
     *        on and what a short window inherits its cluster from.
     */
    data class Fp(
        val windowKey: WindowKey,
        val emb: FloatArray,
        val durSec: Float,
        val onlineId: Int,
    )

    /**
     * One retrospective speaker.
     *
     * @param id 1-based, numbered by first appearance in time.
     * @param confirmed whether it cleared [MIN_CLUSTER_SECONDS] and [MIN_CLUSTER_FINGERPRINTS] on
     *        its own, BEFORE anything was absorbed into it and before the short windows attached.
     *        Every cluster that survives a pass is confirmed except in the one degenerate case —
     *        no cluster cleared the bar at all — where the whole session becomes one unconfirmed
     *        speaker and the panel therefore stays unlabelled.
     * @param totalSec the total speech attributed to it, short windows included.
     * @param fingerprints how many windows it holds.
     * @param longest up to [SpeakerTracker.RECENT_K] of its longest fingerprints, unit-normalised
     *        and in TIME order — exactly the shape [SpeakerTracker.reseed] wants for a speaker's
     *        recent set, because the longest windows are the least ambiguous evidence this
     *        cluster has about the voice.
     */
    data class Cluster(
        val id: Int,
        val confirmed: Boolean,
        val totalSec: Float,
        val fingerprints: Int,
        val longest: List<FloatArray>,
    )

    /**
     * What one pass concluded.
     *
     * @param map `onlineId -> clusterId`, for every online id in the input. LOSSY where an id's
     *        windows straddle two clusters — see the class KDoc.
     * @param windowLabels `windowKey -> clusterId`, for every fingerprint the pass looked at.
     *        EXACT, and the one a caller that can address windows should use.
     * @param clusters the speakers, in id order (so `clusters[i].id == i + 1`).
     * @param clusterCount how many speakers the session has, retrospectively.
     * @param confirmedCount how many of them cleared the mass bar — the number the panel's latch
     *        is a threshold on ([SpeakerLabels.MIN_CONFIRMED_SPEAKERS]).
     */
    data class Relabel(
        val map: Map<Int, Int>,
        val windowLabels: Map<WindowKey, Int>,
        val clusters: List<Cluster>,
        val clusterCount: Int,
        val confirmedCount: Int,
    ) {
        companion object {
            /** Nothing to say: no fingerprints at all. A caller publishes no relabel for it. */
            val NOTHING: Relabel = Relabel(emptyMap(), emptyMap(), emptyList(), 0, 0)
        }
    }

    /**
     * The whole pass. [fingerprints] in TIME order (chunk order, window order within a chunk);
     * only the last [MAX_RECLUSTER_FINGERPRINTS] are looked at.
     *
     * Deterministic: the same list always yields the same answer, ties everywhere broken towards
     * the earlier fingerprint and the lower cluster index, because a relabel that flickered
     * between two equally good answers would rewrite the panel for nothing.
     */
    fun recluster(fingerprints: List<Fp>): Relabel {
        val kept = if (fingerprints.size <= MAX_RECLUSTER_FINGERPRINTS) {
            fingerprints
        } else {
            fingerprints.subList(fingerprints.size - MAX_RECLUSTER_FINGERPRINTS, fingerprints.size)
        }
        val n = kept.size
        if (n == 0) return Relabel.NOTHING

        val unit = Array(n) { unitOrNull(kept[it].emb) }
        // Step 1: the fingerprints that may DEFINE a cluster. A NaN duration fails this test, as
        // it should — every comparison with NaN is false.
        val seeds = ArrayList<Int>(n)
        for (i in 0 until n) if (kept[i].durSec >= MIN_CLUSTERED_SECONDS && unit[i] != null) seeds += i
        if (seeds.isEmpty()) return oneSpeaker(kept, unit)

        // Steps 2 and 3: duration-weighted average-linkage agglomeration.
        val k = seeds.size
        val members = ArrayList<MutableList<Int>>(k)
        for (seed in seeds) members += mutableListOf(seed)
        val live = BooleanArray(k) { true }
        val mass = DoubleArray(k) { kept[seeds[it]].durSec.toDouble() }
        val sums = Array(k) { DoubleArray(k) }
        for (i in 0 until k) {
            for (j in i + 1 until k) {
                val pair = mass[i] * mass[j] * dot(unit[seeds[i]]!!, unit[seeds[j]]!!)
                sums[i][j] = pair
                sums[j][i] = pair
            }
        }
        var liveCount = k
        while (liveCount > 1) {
            var bestI = -1
            var bestJ = -1
            var best = Double.NEGATIVE_INFINITY
            for (i in 0 until k) {
                if (!live[i]) continue
                for (j in i + 1 until k) {
                    if (!live[j]) continue
                    // Strictly greater: the FIRST pair at a tie wins, which is what makes the
                    // whole pass reproducible on a list with repeated vectors in it.
                    val similarity = sums[i][j] / (mass[i] * mass[j])
                    if (similarity > best) {
                        best = similarity
                        bestI = i
                        bestJ = j
                    }
                }
            }
            if (bestI < 0 || best < RECLUSTER_SIM) break
            members[bestI].addAll(members[bestJ])
            members[bestJ].clear()
            live[bestJ] = false
            liveCount--
            mass[bestI] += mass[bestJ]
            for (c in 0 until k) {
                if (!live[c] || c == bestI) continue
                val merged = sums[bestI][c] + sums[bestJ][c]
                sums[bestI][c] = merged
                sums[c][bestI] = merged
            }
        }

        // Step 4: the mass bar, then absorption. The confirmed clusters are SNAPSHOTTED before
        // anything moves — like SpeakerTracker.endChunk, and for the same reason: otherwise the
        // answer would depend on the order the leftovers happen to sit in.
        val liveIndices = (0 until k).filter { live[it] }
        val confirmed = liveIndices.filter {
            mass[it] >= MIN_CLUSTER_SECONDS && members[it].size >= MIN_CLUSTER_FINGERPRINTS
        }
        if (confirmed.isEmpty()) return oneSpeaker(kept, unit)
        val isConfirmed = confirmed.toHashSet()
        for (c in liveIndices) {
            if (c in isConfirmed) continue
            var into = confirmed.first()
            var best = Double.NEGATIVE_INFINITY
            for (anchor in confirmed) {
                val similarity = sums[c][anchor] / (mass[c] * mass[anchor])
                if (similarity > best) {
                    best = similarity
                    into = anchor
                }
            }
            members[into].addAll(members[c])
            members[c].clear()
            live[c] = false
            // `mass` and `sums` are deliberately NOT updated: every leftover is weighed against
            // the speakers as they stood at the end of the clustering.
        }

        val clusterOf = IntArray(n) { -1 }
        val survivors = (0 until k).filter { live[it] }
        for (c in survivors) for (member in members[c]) clusterOf[member] = c

        // Step 5: the short fingerprints. The online id's own cluster first — that is the live
        // pass's opinion, and for a window too thin to have one of its own it is the best
        // evidence available — then the nearest cluster by the same weighted mean.
        val byOnlineId = argmaxByDuration(kept, clusterOf)
        val largest = survivors.maxByOrNull { members[it].sumOf { m -> kept[m].durSec.toDouble() } } ?: survivors.first()
        // SNAPSHOTTED before the first attachment, so one short window cannot become part of the
        // evidence the next short window is measured against — the answer must not depend on
        // where in the list a thin fingerprint happens to sit.
        val anchors: List<Pair<Int, List<Int>>> = survivors.map { it to members[it].toList() }
        for (i in 0 until n) {
            if (clusterOf[i] >= 0) continue
            val inherited = byOnlineId[kept[i].onlineId]
            clusterOf[i] = when {
                inherited != null -> inherited
                unit[i] != null -> nearest(anchors, kept, unit, unit[i]!!) ?: largest
                // No vector and no id to inherit from: the biggest speaker, which is the least
                // surprising thing a window of unknown origin can be called.
                else -> largest
            }
            members[clusterOf[i]].add(i)
        }

        // Step 6: numbering by first appearance in time.
        val number = HashMap<Int, Int>(survivors.size * 2)
        for (i in 0 until n) number.getOrPut(clusterOf[i]) { number.size + 1 }

        val windowLabels = LinkedHashMap<WindowKey, Int>(n * 2)
        for (i in 0 until n) windowLabels[kept[i].windowKey] = number.getValue(clusterOf[i])

        val clusters = ArrayList<Cluster>(survivors.size)
        for (c in survivors.sortedBy { number.getValue(it) }) {
            val held = members[c]
            clusters += Cluster(
                id = number.getValue(c),
                confirmed = c in isConfirmed,
                totalSec = held.sumOf { kept[it].durSec.toDouble() }.toFloat(),
                fingerprints = held.size,
                longest = longest(held, kept, unit),
            )
        }
        return Relabel(
            map = idMap(kept, clusterOf, number),
            windowLabels = windowLabels,
            clusters = clusters,
            clusterCount = clusters.size,
            confirmedCount = clusters.count { it.confirmed },
        )
    }

    // ------------------------------------------------------------------ internals

    /**
     * The degenerate answer: nothing cleared the bar, so the session is ONE speaker and it is not
     * a confirmed one — which is exactly what the panel needs to keep showing no labels at all
     * (`SpeakerLabels.MIN_CONFIRMED_SPEAKERS`).
     */
    private fun oneSpeaker(kept: List<Fp>, unit: Array<FloatArray?>): Relabel {
        val all = kept.indices.toMutableList()
        return Relabel(
            map = kept.associate { it.onlineId to 1 },
            windowLabels = LinkedHashMap<WindowKey, Int>(kept.size * 2).also { out ->
                for (fp in kept) out[fp.windowKey] = 1
            },
            clusters = listOf(
                Cluster(
                    id = 1,
                    confirmed = false,
                    totalSec = kept.sumOf { it.durSec.toDouble() }.toFloat(),
                    fingerprints = kept.size,
                    longest = longest(all, kept, unit),
                ),
            ),
            clusterCount = 1,
            confirmedCount = 0,
        )
    }

    /**
     * `onlineId -> clusterId` by MOST DURATION, ties to the lower cluster id. The lossy half of
     * the answer, and the class KDoc says so in the one place a caller will read.
     */
    private fun idMap(kept: List<Fp>, clusterOf: IntArray, number: Map<Int, Int>): Map<Int, Int> {
        val perId = LinkedHashMap<Int, HashMap<Int, Double>>()
        for (i in kept.indices) {
            val label = number[clusterOf[i]] ?: continue
            perId.getOrPut(kept[i].onlineId) { HashMap() }
                .merge(label, kept[i].durSec.toDouble(), Double::plus)
        }
        val out = LinkedHashMap<Int, Int>(perId.size * 2)
        for ((onlineId, weights) in perId) {
            var bestLabel = Int.MAX_VALUE
            var bestWeight = Double.NEGATIVE_INFINITY
            for ((label, weight) in weights.entries.sortedBy { it.key }) {
                if (weight > bestWeight) {
                    bestWeight = weight
                    bestLabel = label
                }
            }
            if (bestLabel != Int.MAX_VALUE) out[onlineId] = bestLabel
        }
        return out
    }

    /** Which cluster each online id mostly went to, over the SEEDED fingerprints only. */
    private fun argmaxByDuration(kept: List<Fp>, clusterOf: IntArray): Map<Int, Int> {
        val perId = HashMap<Int, HashMap<Int, Double>>()
        for (i in kept.indices) {
            val c = clusterOf[i]
            if (c < 0) continue
            perId.getOrPut(kept[i].onlineId) { HashMap() }.merge(c, kept[i].durSec.toDouble(), Double::plus)
        }
        val out = HashMap<Int, Int>(perId.size * 2)
        for ((onlineId, weights) in perId) {
            var bestCluster = -1
            var bestWeight = Double.NEGATIVE_INFINITY
            for ((cluster, weight) in weights.entries.sortedBy { it.key }) {
                if (weight > bestWeight) {
                    bestWeight = weight
                    bestCluster = cluster
                }
            }
            if (bestCluster >= 0) out[onlineId] = bestCluster
        }
        return out
    }

    /** The nearest surviving cluster to [v] by the duration-weighted mean cosine, or null if none. */
    private fun nearest(
        anchors: List<Pair<Int, List<Int>>>,
        kept: List<Fp>,
        unit: Array<FloatArray?>,
        v: FloatArray,
    ): Int? {
        var into: Int? = null
        var best = Double.NEGATIVE_INFINITY
        for ((c, held) in anchors) {
            var weighted = 0.0
            var total = 0.0
            for (member in held) {
                val other = unit[member] ?: continue
                val w = kept[member].durSec.toDouble()
                weighted += w * dot(v, other)
                total += w
            }
            if (total <= 0.0) continue
            val similarity = weighted / total
            if (similarity > best) {
                best = similarity
                into = c
            }
        }
        return into
    }

    /**
     * Up to [SpeakerTracker.RECENT_K] of a cluster's LONGEST fingerprints, returned in TIME order.
     *
     * Longest, because the tracker matches by maximum cosine against this set and a long window is
     * the least ambiguous thing the cluster knows about the voice; in time order, because that is
     * the order a recent set is meant to be read in and it makes the seeding reproducible.
     */
    private fun longest(held: List<Int>, kept: List<Fp>, unit: Array<FloatArray?>): List<FloatArray> =
        held.filter { unit[it] != null }
            .sortedWith(compareByDescending<Int> { kept[it].durSec }.thenBy { it })
            .take(SpeakerTracker.RECENT_K)
            .sorted()
            .map { unit[it]!! }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var sum = 0.0
        for (i in a.indices) sum += a[i].toDouble() * b[i].toDouble()
        return sum
    }

    /**
     * L2-normalises, or null for a vector no similarity can be computed from — empty, non-finite,
     * or a norm too small to divide by. A null vector never seeds a cluster; its window is still
     * labelled, by the company it keeps.
     */
    private fun unitOrNull(v: FloatArray): FloatArray? {
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
}
