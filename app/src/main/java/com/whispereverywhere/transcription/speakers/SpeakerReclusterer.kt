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
 *  3. **Merge while the best pair is at least [RECLUSTER_SIM].** 0.30, and the number is an
 *     INTERSECTION rather than a preference: session 6's three dumps measured within- /
 *     between-speaker means of 0.50/0.06, 0.31/0.05 and 0.35/0.01, so a threshold that neither
 *     splits a voice nor merges two people has to sit above every `between` and at or below
 *     every `within` — inside [0.06, 0.31], and 0.30 is the top of that window. See
 *     [RECLUSTER_SIM] for what the first shipped value (0.40, outside two of the three gaps)
 *     did to the 03:02 and 03:05 sessions.
 *  4. **A cluster is a SPEAKER only above a mass, and only within the cap** —
 *     [MIN_CLUSTER_SECONDS] of total duration AND at least [MIN_CLUSTER_FINGERPRINTS] windows,
 *     and at most `maxSpeakers` of them — the tracker's own number, longest-speaking first.
 *     Everything below the bar, and everything past the cap, is absorbed into the nearest
 *     confirmed cluster, and if nothing at all clears the bar the whole session is ONE speaker —
 *     a [Relabel] with `confirmedCount = 0`, which is a pass saying it concluded NOTHING and
 *     which a caller must therefore not publish (see [oneSpeaker] and `SpeakerAssigner`).
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
 * ### Cost, stated as arithmetic — and WHAT the cap is on
 *
 * The similarity matrix is O(k²) in the number of SEEDS, which is why the seeds are capped at
 * [MAX_RECLUSTER_FINGERPRINTS] (the newest ones). At the cap that is 600·599/2 ≈ 180 000 pairs,
 * each a dot product of 192 floats — about 34.5 million multiply-adds.
 *
 * The merge loop is the bigger term and it is worth stating honestly: it rescans the live pairs
 * every round, so its worst case (everything collapsing into one cluster, 598 merges) is
 * Σ k²/2 ≈ k³/6 ≈ 3.6·10⁷ divide-and-compares — of the same order as the matrix, and on a
 * conversation far cheaper, because the loop stops the moment no pair reaches [RECLUSTER_SIM].
 * Together, a few hundred milliseconds at the cap on the owner's Tab, on the `speaker-embed`
 * thread, below text that was delivered long ago. 600 seeds is roughly 25 minutes of speech.
 *
 * THAT cap — [MAX_RECLUSTER_FINGERPRINTS] — is on the seeds and NOT on the answer; the SPEAKER
 * cap, `maxSpeakers`, is the one that bounds the answer (step 4 above). Every window handed to
 * [recluster] is labelled, whatever the seed cap did, and the extra terms that costs are linear — one normalisation, one
 * `argmax` lookup and one map entry per window. That distinction is load-bearing rather than
 * tidy. A window this pass does not name keeps the id it was last given, and after the very
 * next [SpeakerTracker.reseed] renumbers the id space that id can denote a DIFFERENT person; so
 * the only windows allowed to go unnamed are the ones the caller has itself stopped holding.
 *
 * Pure: no Android, no I/O, no state, no thread of its own. Like [SpeakerTracker] and
 * [SpeakerLabels], every judgement it makes is reachable from a JUnit test with vectors whose
 * similarities are arithmetic.
 */
object SpeakerReclusterer {

    /**
     * **0.30** — merge two clusters while their duration-weighted mean cosine is at least this.
     *
     * Session 6 of `docs/measurements/2026-09-18-speaker-spike.md` measured three dumps' within- /
     * between-speaker means: **0.50/0.06, 0.31/0.05 and 0.35/0.01**. A threshold that must not
     * split one voice has to be at or below every `within`; one that must not merge two people
     * has to be above every `between`. That intersection is **[0.06, 0.31]**, and 0.30 is the top
     * of it.
     *
     * **0.40 shipped first and was outside two of the three gaps.** It is written down because it
     * is the exact shape of mistake this constant invites. Average linkage stops when the best
     * inter-cluster weighted mean falls below the threshold, so a voice whose own internal mean is
     * 0.31 or 0.35 cannot be assembled at all by merges gated at 0.40: on the 03:02 and 03:05
     * sessions the pass provably over-split one speaker into many — a reconstruction at the
     * documented means left 19 live clusters (six over the mass bar) at within-mean 0.35 and 32
     * (three over the bar) at 0.31, where 0.30 gives the correct 1-2. The KDoc that justified 0.40
     * said "the only round number inside all three gaps", which was false on the numbers in its
     * own sentence.
     *
     * It is BELOW the online [SpeakerTracker.T_SAME] because a mean over every member pair is a
     * stronger claim than a maximum over five recent vectors, so it can afford to ask for less;
     * that it lands on [SpeakerTracker.T_NEW] is a coincidence with a readable meaning — the
     * retrospective pass keeps merging down to exactly the point where the online pass would have
     * declared a new person.
     *
     * **Which way there is room to move.** Downwards. The margin above the largest measured
     * `between` (0.06) is 0.24; the margin below the smallest measured `within` (0.31) is 0.01.
     * If the field still reports one voice broken into several, the next value is inside the same
     * interval and lower — not higher.
     */
    const val RECLUSTER_SIM: Float = 0.30f

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
     * **600** — the most fingerprints one pass lets VOTE, the newest kept. It bounds an O(k²)
     * pass on a phone (see the class KDoc's arithmetic) at roughly 25 minutes of speech.
     *
     * It caps the SEEDS and not the answer — the SPEAKER count is bounded separately, by
     * `maxSpeakers` in [recluster]. A window past this cap stops defining clusters and is
     * labelled like any other window too thin to vote — by the company it keeps (step 5) — so
     * every window the caller still holds is named by every pass. It used to cap the input, and
     * that was a defect: an unnamed window keeps an id from BEFORE the next
     * [SpeakerTracker.reseed] renumbers the id space, and a stale id in a renumbered space is not
     * a missing label, it is somebody else's.
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
     * @param emb the voice vector, or an EMPTY array for a window that has none — one under the
     *        embed floor, one the embedder refused, or one whose vector the caller has dropped
     *        past its own retention bound. Such a window never seeds and never votes; it is
     *        labelled in step 5 by the company it keeps, and it is here rather than absent
     *        because a window nobody labels keeps an id from a superseded numbering.
     *        A vector is expected unit-normalised; normalised defensively here anyway,
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
     * @param windowLabels `windowKey -> clusterId`, for EVERY window the pass was given —
     *        including the ones with no usable vector. EXACT, and the one a caller that can
     *        address windows should use.
     * @param clusters the speakers, in id order (so `clusters[i].id == i + 1`).
     * @param clusterCount how many speakers the session has, retrospectively — at most
     *        `maxSpeakers`, the cap [recluster] was given.
     * @param confirmedCount how many of them cleared the mass bar — the number the panel's latch
     *        is a threshold on ([SpeakerLabels.MIN_CONFIRMED_SPEAKERS]), and the number a caller
     *        decides whether to publish this pass at all on. It is 0 for the degenerate answer
     *        ([oneSpeaker]) and equal to [clusterCount] otherwise, because every cluster that
     *        survives the absorption step cleared the bar.
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
     * The whole pass. [fingerprints] in TIME order (chunk order, window order within a chunk).
     *
     * EVERY one of them is labelled. The cost cap ([MAX_RECLUSTER_FINGERPRINTS]) applies to the
     * windows allowed to DEFINE a cluster — the newest that clear [MIN_CLUSTERED_SECONDS] — and
     * an [Fp] with an unusable vector (an empty array is the canonical one) costs a null check
     * and a map entry, which is what lets a caller hand over the windows it never fingerprinted
     * and still get a label for them.
     *
     * Deterministic: the same list always yields the same answer, ties everywhere broken towards
     * the earlier fingerprint and the lower cluster index, because a relabel that flickered
     * between two equally good answers would rewrite the panel for nothing.
     */
    fun recluster(fingerprints: List<Fp>, maxSpeakers: Int = SpeakerTracker.MAX_SPEAKERS): Relabel {
        val kept = fingerprints
        val n = kept.size
        if (n == 0) return Relabel.NOTHING

        val unit = Array(n) { unitOrNull(kept[it].emb) }
        // Step 1: the fingerprints that may DEFINE a cluster — scanned NEWEST first and stopped
        // at the cap, because the O(k²) term of this pass is the seed count and nothing else. A
        // NaN duration fails this test, as it should — every comparison with NaN is false.
        val seeds = ArrayList<Int>(minOf(n, MAX_RECLUSTER_FINGERPRINTS))
        for (i in n - 1 downTo 0) {
            if (seeds.size >= MAX_RECLUSTER_FINGERPRINTS) break
            if (kept[i].durSec >= MIN_CLUSTERED_SECONDS && unit[i] != null) seeds += i
        }
        // …and back into TIME order, which every tie-break and the first-appearance numbering
        // are defined against.
        seeds.reverse()
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
        val qualified = liveIndices.filter {
            mass[it] >= MIN_CLUSTER_SECONDS && members[it].size >= MIN_CLUSTER_FINGERPRINTS
        }
        if (qualified.isEmpty()) return oneSpeaker(kept, unit)
        // THE CAP BINDS THE ANSWER, not only the seeds, and it is [SpeakerTracker]'s number
        // rather than a second copy of it. [SpeakerTracker.reseed] installs one LIVE voice per
        // cluster returned here, so a pass answering with more clusters than the tracker may
        // hold leaves it holding more live voices than [SpeakerTracker.maxSpeakers] — which
        // breaks the bound [SpeakerTracker.speakerCount] documents about itself. A 24-minute
        // session on the Tab S10+ (4.11.0, 2026-09-20 01:35) reached `clusters=11 confirmed=11`
        // against a cap of 8 and stayed over it to the end.
        //
        // WHAT THIS EARNS, stated exactly, because the neighbouring claim is easy to make and
        // wrong. The online path opens a new speaker only while `liveCount < maxSpeakers`, and
        // AT the cap that guard is false too — capping to 8 does NOT give the tracker back the
        // ability to open a ninth voice, and nothing here should be read as saying it does. What
        // it does give: the tracker's live count honours its own documented bound; the label
        // space handed to the panel stops drifting upward past the cap; and because every pass
        // re-decides WHICH speakers survive, a person who out-speaks the weakest survivor takes
        // that slot at the next pass rather than being locked out for the session.
        //
        // AND WHAT IT COSTS, on material the cap is genuinely too small for: nine real people
        // used to come back as nine clusters, all separated, with only the online path jammed.
        // Now the ninth is merged into whoever they most resemble. That is the cap's price, not
        // this trim's — [SpeakerTracker.MAX_SPEAKERS] is the single place to change if sessions
        // routinely hold more people than it allows. The session that prompted this held one or
        // two real voices and answered eleven, which is the over-split the other way.
        //
        // Over the cap the speakers who SPOKE LONGEST keep their identity and the rest become
        // leftovers, which is not a new disposal rule: they fall into the absorption loop below
        // that every sub-bar cluster already goes through, so each is merged into the confirmed
        // speaker it most resembles and no window is dropped. Speech time decides because it is
        // the same quantity [MIN_CLUSTER_SECONDS] already uses to decide that a cluster is a
        // person at all; the index breaks a tie, so the answer never depends on hash order.
        //
        // A NaN duration cannot reach this sort — `mass[it] >= MIN_CLUSTER_SECONDS` is false for
        // NaN, and a seed needed `durSec >= MIN_CLUSTERED_SECONDS` before that — which is worth
        // writing down because the failure would be the bad one: boxed `Double.compareTo`
        // total-orders NaN as the LARGEST value, so a NaN-mass cluster would sort FIRST and take
        // a cap slot ahead of a real speaker. Two gates stand between that and here.
        val cap = maxOf(1, maxSpeakers)
        val confirmed =
            if (qualified.size <= cap) {
                qualified
            } else {
                qualified.sortedWith(compareByDescending<Int> { mass[it] }.thenBy { it })
                    .take(cap)
                    .sorted()
            }
        val isConfirmed = confirmed.toHashSet()
        // A SURVIVOR'S IDENTITY IS ITS OWN WINDOWS, snapshotted HERE — before anything is
        // absorbed into it. [Cluster.longest] is the seed set [SpeakerTracker.reseed] rebuilds a
        // live voice from, and absorption is by construction a merge of things this pass just
        // proved are NOT one voice: step 3 already merged every pair that reached
        // [RECLUSTER_SIM], so whatever the loop below absorbs sits UNDER 0.30 of its anchor.
        // Letting those windows into the seed set hands the tracker a survivor whose recent set
        // answers ~1.0 to the WRONG person — and `credit` would then push that person's next
        // vector in as well, evicting the survivor from its own id.
        //
        // The absorbed windows still take the survivor's LABEL; that disposal rule is unchanged.
        // They just do not become its VOICE.
        //
        // The speaker cap made this urgent rather than introducing it: a sub-bar leftover fails
        // `mass >= MIN_CLUSTER_SECONDS` or holds a single window, so it rarely owned a window
        // long enough to win [longest]'s duration sort, while every cluster the cap trims
        // cleared that bar and lost only on RELATIVE mass — its windows are long and would
        // routinely win. A snapshot taken here also holds SEEDS only, each at least
        // [MIN_CLUSTERED_SECONDS], so step 5's thin attachments cannot seed a voice either: a
        // 1.0 s window is labelled, never a voter.
        val ownMembers = HashMap<Int, List<Int>>(confirmed.size * 2)
        for (c in confirmed) ownMembers[c] = members[c].toList()
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
                // NOT `held`: identity is the cluster's own PRE-absorption windows.
                longest = longest(ownMembers.getValue(c), kept, unit),
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
     * a confirmed one. `confirmedCount = 0` is the whole of what it says, and what it says is
     * *"this pass concluded nothing"* — NOT *"this session has one speaker"*.
     *
     * A caller must therefore not publish it, and `SpeakerAssigner.recluster` does not: a pass
     * below [SpeakerLabels.MIN_CONFIRMED_SPEAKERS] confirmed speakers reaches neither the sink
     * nor [SpeakerTracker.reseed]. This used to be described here as *"exactly what the panel
     * needs to keep showing no labels at all"*, which is true only while the panel has none —
     * the latch is one-way, so once it has risen, publishing this would print `Speaker 1:` over
     * a whole correctly-labelled session and collapse the live tracker to a single voice.
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
