package com.whispereverywhere.service

import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.npu.NpuModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentCapPolicyTest {

    @Test
    fun shippedCapsAre4And15Seconds() {
        assertEquals(4_000L, SegmentCapPolicy.FIRST_SEGMENT_WALL_MS)
        assertEquals(15_000L, SegmentCapPolicy.MAX_SEGMENT_WALL_MS)
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        assertEquals(4_000L, policy.currentCapMs())
    }

    @Test
    fun firstUncommittedStretchCapsAt4000ms() {
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 10_000L)
        assertFalse("one ms under the first cap must not cut", policy.capExceeded(nowMs = 13_999L))
        assertTrue("the first cap fires at exactly 4 000 ms", policy.capExceeded(nowMs = 14_000L))
    }

    @Test
    fun laterStretchesKeepThe15000msCap() {
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 4_000L)          // the first wall-cap commit
        assertEquals(15_000L, policy.currentCapMs())
        assertFalse(policy.capExceeded(nowMs = 18_999L))
        assertTrue(policy.capExceeded(nowMs = 19_000L))
    }

    @Test
    fun aPauseCommitAlsoEndsTheFirstCapWindow() {
        // The 800 ms pause cut still wins when a real pause happens (untouched semantics);
        // once ANY commit has cut the first segment, the later cap governs the next stretch.
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 1_200L)          // VAD pause commit, well before 4 s
        assertEquals(15_000L, policy.currentCapMs())
        assertFalse("the 4 s cap must NOT fire on the second stretch", policy.capExceeded(nowMs = 5_000L))
        assertTrue(policy.capExceeded(nowMs = 16_200L))
    }

    @Test
    fun everyCommitRestartsTheClock() {
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 4_000L)
        policy.onCommit(nowMs = 19_000L)
        assertFalse(policy.capExceeded(nowMs = 33_999L))
        assertTrue(policy.capExceeded(nowMs = 34_000L))
    }

    @Test
    fun aNewSessionResetsToTheFirstCap() {
        // Per-session reset (the RECORDING anchor at FloatingBubbleService onOpen): session 2's
        // first segment gets the 4 s cap again, measured from ITS start.
        val policy = SegmentCapPolicy()
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 4_000L)
        policy.onCommit(nowMs = 19_000L)
        policy.onSessionStart(nowMs = 60_000L)
        assertEquals(4_000L, policy.currentCapMs())
        assertFalse(policy.capExceeded(nowMs = 63_999L))
        assertTrue(policy.capExceeded(nowMs = 64_000L))
    }

    @Test
    fun capsAreInjectableForTests() {
        val policy = SegmentCapPolicy(firstSegmentCapMs = 100L, laterSegmentCapMs = 200L)
        policy.onSessionStart(nowMs = 0L)
        assertTrue(policy.capExceeded(nowMs = 100L))
        policy.onCommit(nowMs = 100L)
        assertFalse(policy.capExceeded(nowMs = 299L))
        assertTrue(policy.capExceeded(nowMs = 300L))
    }

    // ------------------------------------------ 4.16.1: the later wall is per tier (owner ruling)

    /** The rule's session kinds, as the service passes them: `cloudWrapper != null`. */
    private val sessionKinds = listOf(false, true)

    /** Every catalog id, plus the two ids the catalog cannot resolve. */
    private val everyTierId: List<String?> = WhisperCatalog.entries.map { it.id } + listOf(null, "smallish")

    @Test
    fun theAiChipSustainedWallIs5Seconds() {
        assertEquals(5_000L, SegmentCapPolicy.NPU_SUSTAINED_WALL_MS)
    }

    /**
     * THE OWNER'S RULING OF 2026-09-25, executed for EVERY catalog tier: 5 s on the two AI-chip
     * tiers, 15 s on every other row — the CPU tiers are not in the ruling and may not move. The
     * key-set line is the alarm `CommitCadencePolicyTest.everyCatalogTierIsNamedExplicitly` is for
     * the floors: a new NPU tier would take 5 s from its spec row and a new CPU rung 15 s by
     * default, and either way somebody should have decided it.
     */
    @Test
    fun theLaterWallIs5sOnTheAiChipTiersAnd15sOnEveryOtherCatalogTier() {
        val expected = mapOf(
            "eco" to 15_000L, "base" to 15_000L, "pro" to 15_000L,
            "multi" to 15_000L, "extreme" to 15_000L, "ultra" to 15_000L,
            "small-q8" to 15_000L, "medium-q5" to 15_000L, "medium-q8" to 15_000L,
            "ultra-q8" to 15_000L, "large-v3" to 15_000L,
            "npu" to 5_000L, "npu-turbo" to 5_000L,
        )
        assertEquals(
            "a catalog tier gained or lost an entry — decide its sustained wall",
            expected.keys,
            WhisperCatalog.entries.map { it.id }.toSet(),
        )
        for ((id, wall) in expected) {
            assertEquals(id, wall, SegmentCapPolicy.laterWallMsFor(id, isCloudSession = false))
        }
        // An id the catalog cannot resolve is not an AI-chip tier.
        assertEquals(15_000L, SegmentCapPolicy.laterWallMsFor(null, isCloudSession = false))
        assertEquals(15_000L, SegmentCapPolicy.laterWallMsFor("smallish", isCloudSession = false))
    }

    /**
     * "The gated AI-chip tiers", read two ways that must agree today: the rule keys on the NPU
     * spec table (the router's membership test, one home per id), and every row it answers 5 s
     * for is a gated catalog row. The catalog's gated set is exactly those two rows.
     */
    @Test
    fun theFiveSecondTiersAreTheNpuSpecRowsAndBothAreGatedCatalogRows() {
        val fiveSecond = WhisperCatalog.entries.filter {
            SegmentCapPolicy.laterWallMsFor(it.id, isCloudSession = false) == SegmentCapPolicy.NPU_SUSTAINED_WALL_MS
        }
        assertEquals(
            setOf(NpuModelSpec.SMALL.tierId, NpuModelSpec.TURBO.tierId),
            fiveSecond.map { it.id }.toSet(),
        )
        assertTrue("every 5 s tier is a gated (device-decides) catalog row", fiveSecond.all { it.gated })
        assertEquals(
            "the catalog's gated rows and the NPU spec rows are the same two tiers today",
            WhisperCatalog.entries.filter { it.gated }.map { it.id }.toSet(),
            fiveSecond.map { it.id }.toSet(),
        )
    }

    @Test
    fun aCloudSessionKeeps15sOnEveryTierAiChipIncluded() {
        // The cloud engine transcribes there; the local tier is the rescue mirror. A 5 s wall
        // would triple the billable requests under unbroken speech, a decision nobody made.
        for (id in everyTierId) {
            assertEquals(id ?: "null", 15_000L, SegmentCapPolicy.laterWallMsFor(id, isCloudSession = true))
        }
    }

    /**
     * THE WALL MAY NEVER FALL BELOW THE FLOOR. The cap fires on the wall alone — the governor
     * never paces it — so a sustained wall under a tier's commit floor would commit faster than
     * the floor allows. Executed over every catalog tier (and the two unresolvable ids) for the
     * three session kinds the service opens: local; cloud batch (`cloudWrapper != null`); cloud
     * live (`cloudWrapper != null` AND `sessionIsLive`) — against BOTH floors, fast and slow.
     *
     * The FIRST wall is exempt and that is design, not a gap: 4 000 sits under the 6 000/8 000
     * CPU floors on purpose (3.6.0 A1, first text fast), and a session's first cut is free of the
     * governor too (`SileroEndpointer.hasCommitted`). It happens once per session.
     */
    @Test
    fun theSustainedWallIsNeverBelowTheTiersCommitFloorInAnySessionKind() {
        val kinds = listOf(
            Triple("local", false, false),
            Triple("cloud batch", true, false),
            Triple("cloud live", true, true),
        )
        for (id in everyTierId) {
            for ((kind, cloud, live) in kinds) {
                val wall = SegmentCapPolicy.laterWallMsFor(id, isCloudSession = cloud)
                val fast = CommitCadencePolicy.minCommitIntervalMs(id, isCloudBatch = cloud, isCloudLive = live)
                val slow = CommitCadencePolicy.slowCommitIntervalMs(id, isCloudBatch = cloud, isCloudLive = live)
                assertTrue("$id $kind: wall $wall ms is under the fast floor $fast ms", wall >= fast)
                assertTrue("$id $kind: wall $wall ms is under the slow floor $slow ms", wall >= slow)
            }
        }
        // The tightest pair, named: turbo's slow row is the highest NPU floor.
        assertTrue(
            SegmentCapPolicy.NPU_SUSTAINED_WALL_MS >= CommitCadencePolicy.MIN_COMMIT_INTERVAL_TURBO_SLOW_MS,
        )
    }

    @Test
    fun theFirstSegmentWallStays4sOnEveryTier() {
        for (id in everyTierId) {
            for (cloud in sessionKinds) {
                val policy = SegmentCapPolicy()
                policy.onSessionTier(SegmentCapPolicy.laterWallMsFor(id, isCloudSession = cloud))
                policy.onSessionStart(nowMs = 0L)
                assertEquals("$id cloud=$cloud", 4_000L, policy.currentCapMs())
                assertFalse("$id cloud=$cloud", policy.capExceeded(nowMs = 3_999L))
                assertTrue("$id cloud=$cloud", policy.capExceeded(nowMs = 4_000L))
            }
        }
    }

    @Test
    fun onAnAiChipTierEveryLaterStretchCapsAt5000ms() {
        val policy = SegmentCapPolicy()
        policy.onSessionTier(SegmentCapPolicy.laterWallMsFor(NpuModelSpec.TURBO.tierId, isCloudSession = false))
        policy.onSessionStart(nowMs = 0L)
        assertTrue("the first stretch still cuts at 4 s", policy.capExceeded(nowMs = 4_000L))
        policy.onCommit(nowMs = 4_000L)
        assertEquals(5_000L, policy.currentCapMs())
        assertFalse(policy.capExceeded(nowMs = 8_999L))
        assertTrue(policy.capExceeded(nowMs = 9_000L))
        policy.onCommit(nowMs = 9_000L)
        assertFalse(policy.capExceeded(nowMs = 13_999L))
        assertTrue(policy.capExceeded(nowMs = 14_000L))
    }

    /**
     * ONE policy lives as long as the service, and the service outlives model switches: the wall
     * is handed over at every session open, so a tier change is the NEXT session's wall and the
     * old one is never kept. (Where the service hands it over is pinned in
     * `SegmentCapTierWiringPinTest`.)
     */
    @Test
    fun aTierChangeBetweenSessionsTakesTheNewWallAndNeverKeepsTheOld() {
        val policy = SegmentCapPolicy()
        policy.onSessionTier(SegmentCapPolicy.laterWallMsFor("npu-turbo", isCloudSession = false))
        policy.onSessionStart(nowMs = 0L)
        policy.onCommit(nowMs = 1_000L)
        assertEquals(5_000L, policy.currentCapMs())

        // The user switches to a CPU rung; the next session opens on it.
        policy.onSessionTier(SegmentCapPolicy.laterWallMsFor("small-q8", isCloudSession = false))
        policy.onSessionStart(nowMs = 60_000L)
        policy.onCommit(nowMs = 61_000L)
        assertEquals(15_000L, policy.currentCapMs())
        assertFalse("the old 5 s wall must not fire on the CPU rung", policy.capExceeded(nowMs = 66_000L))
        assertTrue(policy.capExceeded(nowMs = 76_000L))

        // ...and back to an AI-chip tier.
        policy.onSessionTier(SegmentCapPolicy.laterWallMsFor("npu", isCloudSession = false))
        policy.onSessionStart(nowMs = 120_000L)
        policy.onCommit(nowMs = 121_000L)
        assertEquals(5_000L, policy.currentCapMs())
        assertTrue(policy.capExceeded(nowMs = 126_000L))
    }

    @Test
    fun theLocalSilenceReArmReopensTheFirstWindowAndKeepsTheSessionsWall() {
        // The cap branch's LOCAL-silence arm calls onSessionStart(now) mid-session to re-open the
        // 4 s window for the user's first real speech (3.5.0 parity). It may not forget the tier.
        val policy = SegmentCapPolicy()
        policy.onSessionTier(SegmentCapPolicy.NPU_SUSTAINED_WALL_MS)
        policy.onSessionStart(nowMs = 0L)
        policy.onSessionStart(nowMs = 4_000L)     // a silent 4 s cut re-arms the window
        assertEquals(4_000L, policy.currentCapMs())
        policy.onCommit(nowMs = 8_000L)           // real speech consumes it
        assertEquals(5_000L, policy.currentCapMs())
    }

    /**
     * THE RETAIN AGAINST THE WALL (the 4.16.1 question). A cap cut at a remembered micro-pause
     * keeps up to [CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS] of tail for the next segment. That
     * could only thrash if the tail could swallow a whole window, so every wall in force must be
     * longer than the retain: then each cap cut commits at least `wall - retain` of its own window,
     * and because the offer is cleared on every commit (it always lies inside the current window)
     * no audio is deferred twice. On the 5 s wall: at least 2 s committed per cut, and no audio
     * waits longer than 5 + 3 = 8 s for its commit (18 s at the 15 s wall).
     */
    @Test
    fun theRetainWindowIsShorterThanEveryWallInForce() {
        val retain = CommitCadencePolicy.CAP_CUT_MAX_RETAIN_MS
        assertTrue("the first wall must outlast the retain", retain < SegmentCapPolicy.FIRST_SEGMENT_WALL_MS)
        for (id in everyTierId) {
            for (cloud in sessionKinds) {
                val wall = SegmentCapPolicy.laterWallMsFor(id, isCloudSession = cloud)
                assertTrue("$id cloud=$cloud: wall $wall ms must outlast the $retain ms retain", retain < wall)
            }
        }
        assertEquals("least committed per 5 s cap cut", 2_000L, SegmentCapPolicy.NPU_SUSTAINED_WALL_MS - retain)
        assertEquals("worst wait for a commit on the 5 s wall", 8_000L, SegmentCapPolicy.NPU_SUSTAINED_WALL_MS + retain)
    }
}
