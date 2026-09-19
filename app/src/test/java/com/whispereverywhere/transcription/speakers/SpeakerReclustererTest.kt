package com.whispereverywhere.transcription.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * [SpeakerReclusterer] — the retrospective pass, on vectors whose similarities are arithmetic
 * (4.10, spike session 6).
 *
 * The case this file exists for is the 03:27 dump of session 6: the online matcher gave id 1 to
 * the first fourteen windows and then id 2 to all fifty that followed — *"one big run-on
 * paragraph"* — while a retrospective clustering of the very same fingerprints cut them 40/14
 * into the two voices that were really there. [twoVoicesTheGreedyTrackerMergedIntoOneId] is that
 * session in miniature, and everything else here is a way this pass could answer it wrongly.
 *
 * ### The fixture
 *
 * [voice] is a unit vector in the model's real 192 dimensions, built from an angle on a plane, so
 * `cos(voice(a), voice(b))` is exactly `cos(a - b)` and every threshold in the file is an angle.
 * Speaker A sits around 0° and speaker B around 87.13°, whose cosine is **0.05** — the
 * between-speaker figure session 6 measured (0.01-0.06 across its three dumps) — with each
 * speaker's own windows scattered ±5°, so within-speaker cosines are at worst 0.985 and
 * cross-speaker ones never exceed 0.22. `RECLUSTER_SIM` at 0.30 sits in that gap with room on
 * both sides, which is the point: a test that needed 0.299 vs 0.301 would be measuring the
 * fixture rather than the rule.
 *
 * The angle fixture is DELIBERATELY easier than the field, and that is why
 * [theThreeSeparationsSessionSixMEASUREDAreEachCutIntoTwoVoices] exists beside it: that one
 * builds sessions at the exact within-/between-speaker means the doc records (0.50/0.06,
 * 0.31/0.05, 0.35/0.01) out of orthogonal noise, which is the only shape in this file that can
 * catch a threshold sitting above a real voice's own internal similarity. The first shipped
 * [SpeakerReclusterer.RECLUSTER_SIM], 0.40, was above two of those three and split one voice
 * into many; nothing in the angle fixture could see it.
 */
class SpeakerReclustererTest {

    // ------------------------------------------------------------------ fixtures

    /** A unit vector at [deg], in the 192 dimensions TitaNet-small actually emits. */
    private fun voice(deg: Double): FloatArray {
        val r = Math.toRadians(deg)
        return FloatArray(192).also { it[0] = cos(r).toFloat(); it[1] = sin(r).toFloat() }
    }

    private var nextSeq = 1L

    /** One fingerprint, on a fresh window key — the keys only have to be distinct and ordered. */
    private fun fp(
        deg: Double,
        durSec: Float,
        onlineId: Int,
        seq: Long = nextSeq++,
        windowIndex: Int = 0,
    ) = SpeakerReclusterer.Fp(
        windowKey = WindowKey(seq, windowIndex),
        emb = voice(deg),
        durSec = durSec,
        onlineId = onlineId,
    )

    /** The labels of [fps], in the order they were given. */
    private fun labels(relabel: SpeakerReclusterer.Relabel, fps: List<SpeakerReclusterer.Fp>): List<Int> =
        fps.map { relabel.windowLabels.getValue(it.windowKey) }

    /**
     * ONE speaker's [count] windows at an EXACT pairwise cosine, out of orthogonal noise rather
     * than out of an angle — the only construction that can reproduce a real voice's measured
     * internal similarity.
     *
     * A window is `α·b + √(1-α²)·e`, where `b` is the speaker's base direction and every `e` is
     * a dimension nothing else touches. Two windows of the same speaker are then exactly `α²`
     * apart, and two windows of speakers whose bases are `β` apart are exactly `α²·β` apart. So
     * `withinMean` and `betweenMean` below are not approximations of the doc's numbers, they
     * ARE them.
     *
     * @param tilt the cosine between this speaker's base and speaker 0's — 1.0 for speaker 0.
     * @param noiseFrom the first noise dimension this speaker may use; no two speakers share one.
     */
    private fun measuredVoice(
        withinMean: Double,
        tilt: Double,
        count: Int,
        noiseFrom: Int,
        durSec: Float,
        onlineId: Int,
    ): List<SpeakerReclusterer.Fp> {
        val alpha = kotlin.math.sqrt(withinMean)
        val noise = kotlin.math.sqrt(1.0 - withinMean)
        return (0 until count).map { i ->
            val v = FloatArray(192)
            v[0] = (alpha * tilt).toFloat()
            v[1] = (alpha * kotlin.math.sqrt(1.0 - tilt * tilt)).toFloat()
            v[2 + noiseFrom + i] = noise.toFloat()
            SpeakerReclusterer.Fp(
                windowKey = WindowKey(nextSeq++, 0),
                emb = v,
                durSec = durSec,
                onlineId = onlineId,
            )
        }
    }

    // ------------------------------------------------------------------ the session-6 case

    @Test
    fun twoVoicesTheGreedyTrackerMergedIntoOneId() {
        // Every window was called speaker 1 live — the lock-on. A: four windows of 3 s around 0°;
        // B: three of 2.5 s around 87.13°, interleaved the way a conversation actually goes.
        val a = listOf(fp(0.0, 3f, 1), fp(4.0, 3f, 1), fp(-3.0, 3f, 1), fp(2.0, 3f, 1))
        val b = listOf(fp(87.13, 2.5f, 1), fp(90.0, 2.5f, 1), fp(84.0, 2.5f, 1))
        val session = listOf(a[0], b[0], a[1], b[1], a[2], b[2], a[3])

        val relabel = SpeakerReclusterer.recluster(session)

        assertEquals("the two voices are found", 2, relabel.clusterCount)
        assertEquals("…and both hold enough speech to be people", 2, relabel.confirmedCount)
        // Numbered by first appearance: A spoke first.
        assertEquals(listOf(1, 1, 1, 1), labels(relabel, a))
        assertEquals(listOf(2, 2, 2), labels(relabel, b))
        // THE HONEST LIMIT, asserted rather than described: the single online id cannot be sent
        // to both clusters, so it goes to the one holding most of its duration (A: 12 s vs 7.5).
        // That is exactly why `windowLabels` exists beside `map` and why the sink uses the map of
        // windows.
        assertEquals(mapOf(1 to 1), relabel.map)
    }

    @Test
    fun oneVoiceWithNoisyShortWindowsStaysOneSpeaker() {
        // Session 6's other shape: per-sentence windows are noisy, and a pass that split on noise
        // would put paragraph breaks through a monologue. The long windows are one voice; the
        // short ones are scattered across the sphere and must not be allowed to vote.
        val long = listOf(fp(0.0, 3f, 1), fp(5.0, 2.5f, 1), fp(-4.0, 4f, 1), fp(3.0, 2f, 1))
        val short = listOf(fp(120.0, 1.2f, 1), fp(-95.0, 1.0f, 1), fp(60.0, 1.4f, 1))
        val session = long + short

        val relabel = SpeakerReclusterer.recluster(session)

        assertEquals(1, relabel.clusterCount)
        assertEquals(1, relabel.confirmedCount)
        assertTrue("every window, noisy ones included", relabel.windowLabels.values.all { it == 1 })
        assertEquals(session.size, relabel.windowLabels.size)
        // The short ones followed their online id rather than their own thin vectors.
        assertEquals(listOf(1, 1, 1), labels(relabel, short))
    }

    @Test
    fun aShortWindowWithNoOnlineHistoryTakesTheNearestCluster() {
        val a = listOf(fp(0.0, 3f, 1), fp(3.0, 3f, 1), fp(-2.0, 3f, 1))
        val b = listOf(fp(87.13, 3f, 2), fp(90.0, 3f, 2), fp(85.0, 3f, 2))
        // Online id 9 was never seen on a clusterable window, so there is nothing to inherit: it
        // is labelled by its own vector, which sits on top of B.
        val orphan = fp(88.0, 1.1f, 9)

        val relabel = SpeakerReclusterer.recluster(a + b + listOf(orphan))

        assertEquals(2, relabel.clusterCount)
        assertEquals(2, relabel.windowLabels.getValue(orphan.windowKey))
    }

    // ------------------------------------------------------------------ the mass bar

    @Test
    fun aThreeSecondSingletonIsAbsorbedRatherThanBecomingASpeaker() {
        // Every spurious speaker in the whole spike was a singleton or near-singleton. Three
        // seconds is real audio and a genuinely different vector — and it is still not enough to
        // claim a person: MIN_CLUSTER_SECONDS is 6 s AND two windows.
        val real = listOf(fp(0.0, 4f, 1), fp(3.0, 4f, 1), fp(-3.0, 4f, 1))
        val singleton = fp(87.13, 3f, 2)

        val relabel = SpeakerReclusterer.recluster(real + listOf(singleton))

        assertEquals("one speaker, not two", 1, relabel.clusterCount)
        assertEquals(1, relabel.confirmedCount)
        assertEquals("the singleton joined the only confirmed speaker", 1, relabel.windowLabels.getValue(singleton.windowKey))
        assertEquals("…and its online id says so too", 1, relabel.map.getValue(2))
        assertEquals(4, relabel.clusters.single().fingerprints)
        assertEquals(15f, relabel.clusters.single().totalSec, 0.001f)
    }

    @Test
    fun twoWindowsOfThreeSecondsDoClearTheBarBecauseSixIsInclusive() {
        // The boundary of the previous test, from the other side: 3 + 3 is exactly
        // MIN_CLUSTER_SECONDS, and exactly MIN_CLUSTER_FINGERPRINTS windows.
        val a = listOf(fp(0.0, 4f, 1), fp(3.0, 4f, 1), fp(-3.0, 4f, 1))
        val b = listOf(fp(87.13, 3f, 2), fp(89.0, 3f, 2))

        val relabel = SpeakerReclusterer.recluster(a + b)

        assertEquals(2, relabel.clusterCount)
        assertEquals(2, relabel.confirmedCount)
        assertEquals(listOf(2, 2), labels(relabel, b))
    }

    @Test
    fun aSessionWhereNothingClearsTheBarIsOneUNCONFIRMEDSpeaker() {
        // Two short-ish voices, neither with six seconds to its name. The answer is not "two
        // maybes": it is one speaker and NO confirmation, so `SpeakerLabels` keeps the panel
        // exactly as 4.9 rendered it. A relabel that confirmed here would put a paragraph break
        // and a `Speaker N:` on a session that never earned one.
        val relabel = SpeakerReclusterer.recluster(
            listOf(fp(0.0, 2f, 1), fp(87.13, 2f, 2), fp(4.0, 1.6f, 1)),
        )

        assertEquals(1, relabel.clusterCount)
        assertEquals(0, relabel.confirmedCount)
        assertFalse(relabel.clusters.single().confirmed)
        assertTrue(relabel.windowLabels.values.all { it == 1 })
        assertEquals(mapOf(1 to 1, 2 to 1), relabel.map)
    }

    @Test
    fun aSessionOfNothingButShortWindowsIsOneUnconfirmedSpeakerAndNeverACrash() {
        val relabel = SpeakerReclusterer.recluster(
            listOf(fp(0.0, 1.0f, 1), fp(90.0, 1.2f, 2), fp(180.0, 1.4f, 3)),
        )
        assertEquals(1, relabel.clusterCount)
        assertEquals(0, relabel.confirmedCount)
        assertEquals(3, relabel.windowLabels.size)
    }

    @Test
    fun noFingerprintsAtAllIsNothingRatherThanASpeaker() {
        val relabel = SpeakerReclusterer.recluster(emptyList())
        assertEquals(SpeakerReclusterer.Relabel.NOTHING, relabel)
        assertEquals(0, relabel.clusterCount)
        assertTrue(relabel.windowLabels.isEmpty())
    }

    // ------------------------------------------------------------------ numbering, the cap, determinism

    @Test
    fun clustersAreNumberedByFirstAppearanceInTime() {
        // The same two voices as the session-6 case, with B speaking first. "Speaker 1" is the
        // person who spoke first — the compaction in SpeakerLabels assumes it and so does a reader.
        val b = listOf(fp(87.13, 3f, 1), fp(90.0, 3f, 1), fp(85.0, 3f, 1))
        val a = listOf(fp(0.0, 3f, 2), fp(4.0, 3f, 2), fp(-3.0, 3f, 2))

        val relabel = SpeakerReclusterer.recluster(b + a)

        assertEquals(listOf(1, 1, 1), labels(relabel, b))
        assertEquals(listOf(2, 2, 2), labels(relabel, a))
        assertEquals(listOf(1, 2), relabel.clusters.map { it.id })
    }

    @Test
    fun theCapBoundsWhatMaySEEDAClusterAndNeverWhatIsLABELLED() {
        // The cap is what keeps an O(k²) pass bounded on a phone, and it is on the SEEDS. Every
        // window is still named, because the pass renumbers the id space and a window it leaves
        // unnamed keeps an id that afterwards denotes a different person — not a stale label,
        // somebody else's. Online id 9 appears on nothing that can vote, so the hundred windows
        // past the cap are labelled by their own vectors instead.
        val old = (0 until 100).map { fp(0.0, 2f, 9) }
        val recent = (0 until SpeakerReclusterer.MAX_RECLUSTER_FINGERPRINTS).map {
            fp(if (it % 2 == 0) 0.0 else 87.13, 2f, 1)
        }

        val relabel = SpeakerReclusterer.recluster(old + recent)

        assertEquals("every window is named", old.size + recent.size, relabel.windowLabels.size)
        for (fp in old) assertTrue("an over-cap window is still relabelled", fp.windowKey in relabel.windowLabels)
        for (fp in recent) assertTrue(fp.windowKey in relabel.windowLabels)
        assertEquals("…and only the newest 600 defined the clusters", 2, relabel.clusterCount)
        assertEquals("…the old ones sitting on the voice they actually are", listOf(1), labels(relabel, old).distinct())
    }

    // ------------------------------------------------------------------ the measured separations

    @Test
    fun theThreeSeparationsSessionSixMEASUREDAreEachCutIntoTwoVoices() {
        // THE REGRESSION THIS FILE EXISTS FOR SINCE ROUND 1. Session 6 of the spike doc records
        // three dumps' within- / between-speaker means: 0.50/0.06, 0.31/0.05 and 0.35/0.01. A
        // merge threshold must sit above every `between` and at or below every `within`, and
        // RECLUSTER_SIM shipped at 0.40 — above two of the three `within` means. Average linkage
        // stops when the best inter-cluster mean falls under the threshold, so at 0.40 a voice
        // whose own internal mean is 0.31 or 0.35 cannot be assembled at all: the 03:02 and
        // 03:05 sessions over-split into many clusters, which is the owner's "one big run-on
        // paragraph" traded for ghost speakers. These are not angles scattered ±5°; they are the
        // documented means exactly.
        val measured = listOf(0.50 to 0.06, 0.31 to 0.05, 0.35 to 0.01)
        for ((within, between) in measured) {
            nextSeq = 1L
            val a = measuredVoice(within, tilt = 1.0, count = 8, noiseFrom = 0, durSec = 2.5f, onlineId = 1)
            val b = measuredVoice(
                within,
                tilt = between / within,
                count = 8,
                noiseFrom = 8,
                durSec = 2.5f,
                onlineId = 1,
            )
            // Interleaved, the way a conversation goes and the way the online matcher saw it:
            // one id for both voices.
            val session = a.indices.flatMap { listOf(a[it], b[it]) }

            val relabel = SpeakerReclusterer.recluster(session)

            val where = "within $within / between $between"
            assertEquals("$where: two voices", 2, relabel.clusterCount)
            assertEquals("$where: both over the mass bar", 2, relabel.confirmedCount)
            assertEquals("$where: speaker A", List(8) { 1 }, labels(relabel, a))
            assertEquals("$where: speaker B", List(8) { 2 }, labels(relabel, b))
        }
    }

    @Test
    fun aSingleVoiceAtItsMeasuredInternalSIMILARITYIsNeverSplitIntoSeveral() {
        // The other half, and the half 0.40 actually failed: ONE speaker, 16 windows, pairwise
        // 0.31 — the tightest within-speaker mean session 6 measured. A threshold above it
        // merges nothing at all and the session shatters into sixteen singletons, none of which
        // clears the mass bar, so the pass answers the degenerate "one UNCONFIRMED speaker" and
        // the panel loses its labels. Below it, the voice assembles.
        val one = measuredVoice(0.31, tilt = 1.0, count = 16, noiseFrom = 0, durSec = 2.5f, onlineId = 1)

        val relabel = SpeakerReclusterer.recluster(one)

        assertEquals(1, relabel.clusterCount)
        assertEquals("…and it is a CONFIRMED speaker, not the degenerate answer", 1, relabel.confirmedCount)
        assertEquals(16, relabel.clusters.single().fingerprints)
    }

    @Test
    fun theSameSessionAlwaysGivesTheSameAnswer() {
        // A relabel rewrites text the user has already read. One that flickered between two
        // equally good answers would rewrite it for nothing, so ties are broken towards the
        // earlier fingerprint and the lower cluster everywhere.
        val session = (0 until 40).map {
            fp(deg = if (it % 3 == 0) 87.13 else 0.0, durSec = 2f, onlineId = 1 + it % 2)
        }

        val first = SpeakerReclusterer.recluster(session)
        val second = SpeakerReclusterer.recluster(session)

        assertEquals(first.windowLabels, second.windowLabels)
        assertEquals(first.map, second.map)
        assertEquals(first.clusterCount, second.clusterCount)
        assertEquals(first.confirmedCount, second.confirmedCount)
        assertEquals(first.clusters.map { it.id to it.fingerprints }, second.clusters.map { it.id to it.fingerprints })
        for (i in first.clusters.indices) {
            val a = first.clusters[i].longest
            val b = second.clusters[i].longest
            assertEquals(a.size, b.size)
            for (j in a.indices) assertTrue(a[j].contentEquals(b[j]))
        }
    }

    // ------------------------------------------------------------------ the seeds for the tracker

    @Test
    fun aClusterCarriesItsFiveLongestFingerprintsInTimeOrderForTheReseed() {
        // The tracker matches by MAXIMUM cosine over a speaker's recent set, so what it is seeded
        // with decides every later online call. The longest windows are the least ambiguous
        // evidence the cluster has; RECENT_K of them is exactly one recent set.
        val session = listOf(
            fp(0.0, 2.0f, 1), fp(1.0, 5.0f, 1), fp(-1.0, 3.0f, 1),
            fp(2.0, 6.0f, 1), fp(-2.0, 1.6f, 1), fp(3.0, 4.0f, 1), fp(-3.0, 2.5f, 1),
        )

        val cluster = SpeakerReclusterer.recluster(session).clusters.single()

        assertEquals(7, cluster.fingerprints)
        assertEquals(SpeakerTracker.RECENT_K, cluster.longest.size)
        // The five longest are 6.0, 5.0, 4.0, 3.0 and 2.5 s — at 2°, 1°, 3°, -1° and -3°, and
        // they come back in the order they were SPOKEN, not in the order of their lengths.
        val degrees = listOf(1.0, -1.0, 2.0, 3.0, -3.0)
        for (i in degrees.indices) {
            assertEquals(
                "seed $i is the window at ${degrees[i]}°",
                cos(Math.toRadians(degrees[i])).toFloat(),
                cluster.longest[i][0],
                1e-5f,
            )
        }
    }

    @Test
    fun theSeedsAreUnitVectorsWhateverScaleTheEmbedderAnswersIn() {
        // The class contract says unit-normalised; the defence is here because a caller that
        // forgot would not fail, it would cluster by loudness.
        val loud = SpeakerReclusterer.Fp(
            windowKey = WindowKey(1L, 0),
            emb = FloatArray(192).also { it[0] = 40f },
            durSec = 4f,
            onlineId = 1,
        )
        val quiet = SpeakerReclusterer.Fp(
            windowKey = WindowKey(2L, 0),
            emb = FloatArray(192).also { it[0] = 0.02f },
            durSec = 4f,
            onlineId = 1,
        )

        val relabel = SpeakerReclusterer.recluster(listOf(loud, quiet))

        assertEquals("the same direction is the same voice at any volume", 1, relabel.clusterCount)
        assertEquals(1f, relabel.clusters.single().longest.first()[0], 1e-5f)
    }

    @Test
    fun anUnusableVectorIsStillLabelledAndNeverSeedsAnything() {
        // A fingerprint of all zeros (or NaNs) is what a failed embedding looks like when it
        // arrives as data rather than as the embedder's null. It must not become a speaker and it
        // must not throw on the embed thread.
        val real = listOf(fp(0.0, 4f, 1), fp(3.0, 4f, 1))
        val dead = SpeakerReclusterer.Fp(
            windowKey = WindowKey(99L, 0),
            emb = FloatArray(192),
            durSec = 4f,
            onlineId = 7,
        )

        val relabel = SpeakerReclusterer.recluster(real + listOf(dead))

        assertEquals(1, relabel.clusterCount)
        assertEquals(1, relabel.windowLabels.getValue(dead.windowKey))
    }

    // ------------------------------------------------------------------ the constants

    /** Session 6's three dumps, `within to between`, as the doc records them. */
    private val measuredSeparations = listOf(0.50f to 0.06f, 0.31f to 0.05f, 0.35f to 0.01f)

    @Test
    fun theMergeThresholdIsInsideEVERYSeparationTheDocMeasured() {
        // The pin that 0.40 would have failed, stated as the property rather than as a value: a
        // threshold ABOVE a within-speaker mean cannot assemble that voice (average linkage
        // stops below the threshold), and one BELOW a between-speaker mean merges two people.
        // So RECLUSTER_SIM has to sit in the intersection of all three gaps — (0.06, 0.31] here.
        for ((within, between) in measuredSeparations) {
            assertTrue(
                "RECLUSTER_SIM ${SpeakerReclusterer.RECLUSTER_SIM} must be above the between-speaker mean $between",
                SpeakerReclusterer.RECLUSTER_SIM > between,
            )
            assertTrue(
                "RECLUSTER_SIM ${SpeakerReclusterer.RECLUSTER_SIM} must not exceed the within-speaker mean $within",
                SpeakerReclusterer.RECLUSTER_SIM <= within,
            )
        }
    }

    @Test
    fun theConstantsAreTheONESSessionSixSettled() {
        // THE SOURCE: docs/measurements/2026-09-18-speaker-spike.md, session 6. The within- and
        // between-speaker means of its three dumps were 0.50/0.06, 0.31/0.05 and 0.35/0.01, so
        // the only thresholds that neither split a voice nor merge two people are (0.06, 0.31] —
        // and RECLUSTER_SIM is the top of that interval. It is deliberately BELOW the online
        // T_SAME because a mean over every member pair is a stronger claim than a maximum over
        // five recent vectors. It shipped at 0.40 for one round, which was outside two of the
        // three gaps; see theThreeSeparationsSessionSixMEASUREDAreEachCutIntoTwoVoices.
        assertEquals("RECLUSTER_SIM", 0.30f, SpeakerReclusterer.RECLUSTER_SIM, 0f)
        assertEquals("MIN_CLUSTER_SECONDS", 6.0f, SpeakerReclusterer.MIN_CLUSTER_SECONDS, 0f)
        assertEquals("MIN_CLUSTER_FINGERPRINTS", 2, SpeakerReclusterer.MIN_CLUSTER_FINGERPRINTS)
        assertEquals("MIN_CLUSTERED_SECONDS", 1.5f, SpeakerReclusterer.MIN_CLUSTERED_SECONDS, 0f)
        assertEquals("MAX_RECLUSTER_FINGERPRINTS", 600, SpeakerReclusterer.MAX_RECLUSTER_FINGERPRINTS)
        assertEquals("RECLUSTER_EVERY_CHUNKS", 5, SpeakerReclusterer.RECLUSTER_EVERY_CHUNKS)
        assertTrue(
            "the retrospective bar is lower than the online one, by argument",
            SpeakerReclusterer.RECLUSTER_SIM < SpeakerTracker.T_SAME,
        )
        assertEquals(
            "the clustering floor is the online open gate — the same claim costs the same evidence",
            SpeakerTracker.MIN_OPEN_SECONDS,
            SpeakerReclusterer.MIN_CLUSTERED_SECONDS,
            0f,
        )
        assertTrue(
            "…and it is NOT the embed floor: a 1.0 s window is labelled, never a voter",
            SpeakerReclusterer.MIN_CLUSTERED_SECONDS > SpeakerTracker.MIN_EMBED_SECONDS,
        )
    }
}
