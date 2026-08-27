package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHERE the `perceived:` stamp is taken, pinned structurally (3.7 Workstream F, Task F9).
 *
 * `PerceivedLatencyTest` pins the arithmetic and the bytes; those are pure and testable. What that
 * class cannot see is the half of this metric that is pure PLACEMENT, and placement is the entire
 * correctness argument here:
 *
 *  - **The commit side must not re-read the clock.** `trailMs` was measured against the capture
 *    chunk's `nowMs`, taken before `endpointer.onFrame`. Between that instant and the funnel's
 *    stamp sit `engine.commit()` — a full buffer snapshot under `bufferLock`, up to ~960 KB — and
 *    two `Log.i` calls. A `System.currentTimeMillis()` inside the funnel would therefore subtract
 *    all of that from every reported wait: the metric would be biased LOW, silently, everywhere,
 *    and it is the metric S3 Check 2's p50/p95 grid and S4's release-notes latency claim read.
 *  - **The visible side must be read AFTER delivery returns.** The claim is "you can read your
 *    sentence", not "an utterance is in flight" (that second one is Workstream G's strip, which
 *    renders earlier and on purpose). `deliverReleasedText` writes the view synchronously on Main
 *    and early-returns on blank, so "returned having delivered non-blank text" is exactly the
 *    visible instant. Reading the stamp before the call would report the wait to the START of
 *    delivery instead.
 *  - **The stamp must exist at all.** Task F8's review found that every byte of the sibling
 *    `endpoint:` line was pinned while nothing said the funnel ever CALLED the formatter — the
 *    emission was deleted and 1276 tests stayed green. The same attack is run against both of this
 *    task's emission sites, and each dies on its own test below.
 *
 * `FloatingBubbleService` is an Android Service and cannot be instantiated in a JVM test, so the
 * pin is on the SOURCE — the same instrument, and the same argument, as `CapSeamPinTest`,
 * `EndpointerLifecyclePinTest` and `CommitFunnelPinTest`. It lives in its own class rather than as
 * rows in `CommitFunnelPinTest` because half of what it pins is not in the funnel at all: the
 * visible stamp is inside `onSegmentResolved`'s Main coroutine, and a killer set stays legible when
 * the class that dies names the contract it died for.
 *
 * **The source is read LF-NORMALISED.** `core.autocrlf=true` checks this repo out with CRLF, so a
 * needle written with a bare `\n` finds nothing and every assertion would pass or fail for the
 * wrong reason. The normalisation happens once, at the single read site below.
 */
class PerceivedStampPinTest {

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

    private fun count(needle: String) = text.split(needle).size - 1

    private fun indexOfOrFail(needle: String): Int {
        val i = text.indexOf(needle)
        assertTrue("missing from FloatingBubbleService.kt: <<$needle>>", i >= 0)
        return i
    }

    /**
     * The funnel, from its declaration to its `return seq`. Scoped by SYMBOL, never by line
     * number — every line anchor this workstream inherited from the plan had drifted by the time
     * it was read.
     */
    private val funnel: String by lazy {
        val start = indexOfOrFail("    private fun commitSegment(")
        val close = text.indexOf("        return seq\n    }", start)
        assertTrue("the funnel's closing `return seq` moved", close > start)
        text.substring(start, close)
    }

    @Test
    fun theFunnelStampsSpeechEndWithoutEverReReadingTheClock() {
        // The stamp is DERIVED: the caller's frame clock minus the trail the endpoint: line one
        // statement above already reported. Not a fresh clock, not a second endpointer read.
        indexOfOrFail(
            "            perceivedLatency.onCommitted(seq, speechEndMs(nowMs = nowMs, ec = ec))\n"
        )
        assertEquals(
            "the speech-end stamp is written from exactly one place, the funnel",
            1,
            count("perceivedLatency.onCommitted("),
        )
        // THE NO-REREAD PROOF. Exactly one wall-clock read exists anywhere in the funnel, and it
        // is the DEFAULT for the three Main-side call sites that have no frame clock to pass. A
        // second occurrence means someone read the clock inside the body, after the commit's
        // buffer snapshot — the bias this whole design exists to avoid.
        assertEquals(
            "the funnel reads the wall clock ONCE, as a default parameter, and never in its body",
            1,
            funnel.split("System.currentTimeMillis()").size - 1,
        )
        assertTrue(
            "the one clock read is the nowMs default, not a body statement",
            funnel.contains("        nowMs: Long = System.currentTimeMillis(),\n"),
        )
    }

    @Test
    fun onlyAVadCutIsStamped() {
        // A cap/stop/switch cut has no speech-end instant. Dropping this gate would stamp them
        // with whatever `ec` happened to survive — and `ec` is itself already gated, so the
        // damage would only appear if BOTH gates went, which is exactly why this one is asserted
        // separately from CommitFunnelPinTest's.
        indexOfOrFail("        if (cut == EndpointDiag.VAD && ec != null) {\n")
    }

    @Test
    fun theResolutionPathActuallyEmitsThePerceivedLine() {
        // Task F8's lesson, applied to this task's own deliverable: pinning every byte of a
        // formatter says nothing about anyone calling it. Deleting this Log.i loses the headline
        // metric entirely, and before this assertion the whole suite stayed green while it did.
        assertEquals(
            "the resolution path EMITS the perceived: line — the formatter being pinned is not " +
                "the same claim as anything calling it",
            1,
            count("android.util.Log.i(\"WE-DIAG\", EndpointDiag.perceivedLine(seq, waited))"),
        )
        // ...and only for a segment that actually rendered text. A silent segment consumes its
        // stamp (the map prunes) but has nothing visible to time, so reporting one would be an
        // invented number in the middle of the acceptance grid.
        indexOfOrFail("                    if (waited != null && release.text.isNotBlank()) {\n")
    }

    @Test
    fun theVisibleStampIsTakenAfterTheTextHasBeenDelivered() {
        // WORDS VISIBLE is the claim. deliverReleasedText writes the view synchronously on Main,
        // so its return is the instant; reading the stamp above it would time the start of
        // delivery instead and quietly under-report the metric.
        val delivered = indexOfOrFail("                    deliverReleasedText(release.text)\n")
        val stamped =
            indexOfOrFail("                    val waited = perceivedLatency.onVisible(seq, System.currentTimeMillis())\n")
        assertTrue("the visible stamp is read AFTER delivery returns", stamped > delivered)
        assertEquals(
            "the visible stamp is read from exactly one place",
            1,
            count("perceivedLatency.onVisible("),
        )
        // The Release is captured, not inlined, and the SegmentOrderer's release rules are
        // untouched by that: this only names its result so the blank check below can read it.
        indexOfOrFail("                    val release = segmentOrderer.onResolved(seq, outcome)\n")
    }

    @Test
    fun theStampsAreClearedAtSessionStartBesideTheBacklog() {
        val backlog = indexOfOrFail("        segmentQueueDepth.reset()")
        val stamps = indexOfOrFail("        perceivedLatency.reset()")
        assertTrue("the stamps reset at session START, beside the backlog", stamps > backlog)
        assertEquals(
            "a second reset — at stop — would drop every stamp for the segments still in flight " +
                "when the user taps stop, which are systematically the SLOWEST samples",
            1,
            count("perceivedLatency.reset()"),
        )
    }
}
