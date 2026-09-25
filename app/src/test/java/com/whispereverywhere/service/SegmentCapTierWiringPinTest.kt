package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE LATER WALL's per-session handover (4.16.1, owner ruling 2026-09-25), pinned structurally —
 * `CapSeamPinTest`'s instrument, for its reason: `FloatingBubbleService` cannot be instantiated on
 * the JVM, so `SegmentCapPolicyTest` executes the rule and the policy, and this file holds the
 * three facts about the CALL that no behavioural test can reach:
 *
 *  1. **The service passes the tier's value.** The one policy is built with no tier in it (the
 *     service outlives every session and every model switch), and the session's wall comes from
 *     ONE ask of `SegmentCapPolicy.laterWallMsFor`, fed the same `installedModel?.id` the cadence
 *     floors are fed and the same `cloudWrapper != null` the first-cap suppression reads.
 *  2. **It is re-read at every session open** — inside `startRecording`, below that session's
 *     `installedModel` read — which is what makes a model switch while the service lives a new
 *     wall at the next session rather than the old one kept. A handover anywhere else (the field
 *     initialiser, `onCreate`, the switch collector) would be read once and go stale, or race a
 *     live session's capture thread.
 *  3. **It is in place before the capture thread starts**, so the session's first
 *     `capExceeded()` already reads it, and above `connect()` like every session-open handover
 *     since 4.4.0 S2.
 *
 * The source is read LF-NORMALISED (`core.autocrlf=true`), once, and only LIVE lines are counted:
 * the service's comments quote these calls, and a census that a comment can satisfy pins nothing.
 */
class SegmentCapTierWiringPinTest {

    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    /** The ONE read site, LF-normalised so every `\n` needle below is checkout-independent. */
    private val text: String by lazy {
        source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun indexOfOrFail(needle: String, from: Int = 0): Int {
        val i = text.indexOf(needle, from)
        assertTrue("missing from FloatingBubbleService.kt: <<$needle>>", i >= 0)
        return i
    }

    /** Lines that are code, not comment, containing [needle]. */
    private fun liveLines(needle: String): List<String> =
        text.split("\n").map { it.trimStart() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) && line.contains(needle)
        }

    private val handover =
        "        segmentCapPolicy.onSessionTier(\n" +
            "            SegmentCapPolicy.laterWallMsFor(tierId = installedModel?.id, isCloudSession = cloudWrapper != null),\n" +
            "        )\n"

    @Test
    fun thePolicyIsBuiltOnceAndWithNoTierInIt() {
        assertEquals(
            "one policy for the service's life, and no tier-specific wall baked into its construction",
            listOf("private val segmentCapPolicy = SegmentCapPolicy()"),
            liveLines("SegmentCapPolicy("),
        )
    }

    @Test
    fun theSessionsWallIsAskedOfTheRuleOnceWithTheSessionsTwoFacts() {
        indexOfOrFail(handover)
        assertEquals("ONE handover of the later wall", 1, liveLines("segmentCapPolicy.onSessionTier(").size)
        assertEquals("ONE ask of the per-tier rule", 1, liveLines("SegmentCapPolicy.laterWallMsFor(").size)
        // The wall and the two cadence floors are read off ONE tier: the rule's `tierId` is the
        // floors' `tierId`, so the "never below the floor" pin in SegmentCapPolicyTest describes
        // the numbers a real session gets.
        assertEquals(
            "the later wall, the fast floor and the slow floor, all from installedModel?.id",
            3,
            liveLines("tierId = installedModel?.id").size,
        )
    }

    @Test
    fun theWallIsReReadAtEverySessionOpenBelowTheTierReadAndAboveTheCaptureThread() {
        val start = indexOfOrFail("    private fun startRecording() {")
        val end = indexOfOrFail("    private fun stopRecording() {", start)
        val tierRead = indexOfOrFail("        val installedModel = app.whisperModelManager.installedModel()", start)
        val suppression = indexOfOrFail("        if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionStartMs)", start)
        val wall = indexOfOrFail(handover, start)
        val cadence = indexOfOrFail("        endpointer.onSessionStart(", start)
        val startInput = indexOfOrFail("        val started = startAudioInput()", start)
        val connect = indexOfOrFail("        engine.connect(lang, object : TranscriptionEngine.Listener {", start)
        assertTrue("the handover is startRecording's, i.e. once per session", wall in start until end)
        assertTrue("…and reads THIS session's tier, resolved just above it", tierRead < wall)
        assertTrue("it sits with the cap window's other session-open writes", suppression < wall && wall < cadence)
        assertTrue(
            "in place before the capture thread starts, so the first capExceeded() reads it",
            wall < startInput,
        )
        assertTrue("…and above connect(), like every session-open handover since S2", startInput < connect)
        assertEquals(
            "the tier is read per session, never cached in a field the next session would inherit",
            1,
            liveLines("val installedModel = app.whisperModelManager.installedModel()").size,
        )
    }
}
