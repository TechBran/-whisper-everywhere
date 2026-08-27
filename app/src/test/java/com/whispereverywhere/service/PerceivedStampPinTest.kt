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
 *  - **The visible side must be read AFTER delivery returns, and only on a NON-BLANK release.**
 *    The claim is "you can read your sentence", not "an utterance is in flight" (that second one
 *    is Workstream G's strip, which renders earlier and on purpose). `deliverReleasedText`
 *    early-returns on blank and otherwise hands the resolved text to the preview sink; the TextView
 *    write follows on the next Main dispatch (`TranscriptSink`'s `MutableStateFlow` ->
 *    `previewJob`'s `collectLatest`), so the metric excludes one Main hop and one frame —
 *    single-digit to ~20 ms against a 1.3-2.8 s number. That is a real and acknowledged bias, and
 *    it is small; reading the stamp ABOVE the call would instead time the START of delivery, which
 *    is not small. **This class's first version claimed the write was synchronous. It is not, and
 *    the pin below is right for the corrected reason, not the original one.** The blank gate is
 *    load-bearing too: resolutions arrive out of order on cloud, and consuming a held seq's stamp
 *    would prune its predecessor's as well.
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

    /**
     * The next three tests are DELIBERATELY three, and the battery is why. A deleted stamp, a
     * re-read clock and a mis-bound derivation are three different defects; folding them into one
     * method made the first two name the same test and destroyed the sole-killer reading this
     * project's battery discipline runs on (the fix round of Task F8 split for exactly this and
     * gave the reason). The counts below are chosen so the first two are each SOLE: a re-read
     * keeps `onCommitted(` at one occurrence, and a deletion leaves the funnel's one clock read
     * (its default parameter) standing.
     */
    @Test
    fun theFunnelActuallyStampsTheSpeechEnd() {
        // EXISTENCE, and nothing else. Task F8's review deleted the sibling `endpoint:` emission
        // and 1276 tests stayed green: every byte of a formatter can be pinned while nothing says
        // anyone calls it. The same attack on this stamp loses the headline metric silently.
        assertEquals(
            "the speech-end stamp is written from exactly one place, the funnel — and IS written",
            1,
            count("perceivedLatency.onCommitted("),
        )
    }

    @Test
    fun theFunnelReadsTheWallClockOnceAndOnlyAsADefault() {
        // THE NO-REREAD PROOF, and the load-bearing one. Exactly one wall-clock read exists
        // anywhere in the funnel and it is the DEFAULT for the three Main-side call sites that
        // have no frame clock to pass. A second occurrence means someone read the clock inside the
        // body — after `engine.commit()`'s ~960 KB buffer snapshot under bufferLock and two Log.i
        // calls — and every reported wait is then smaller than the truth by that delta, silently,
        // on exactly the metric S3 Check 2 and S4's release-notes contingency read.
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
    fun theStampIsDerivedFromTheFunnelsOwnFrameClockAndCutRecord() {
        // PROVENANCE. The two inputs are the caller's frame clock and the very `ec` the endpoint:
        // line one statement above already reported — no second read of the endpointer, no third
        // source of "now". This is the needle that kills a wrong-clock binding (sessionStartMs,
        // sessionOpenMs) which the census above cannot see, because those are not clock READS.
        indexOfOrFail(
            "            perceivedLatency.onCommitted(seq, speechEndMs(nowMs = nowMs, ec = ec))\n"
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
    }

    @Test
    fun theStampIsConsumedOnlyOnANonBlankRelease() {
        // The F9 review's I1, pinned structurally. The gate is on the RELEASE, and it wraps the
        // onVisible call rather than filtering its result: a blank release means the orderer is
        // still holding this seq (cloud resolves out of order under Semaphore(3)), so consuming
        // the stamp there loses this seq's number AND prunes its predecessor's — both lines gone.
        // The earlier `if (waited != null && release.text.isNotBlank())` form read almost the same
        // and had exactly that defect, which is why the shape is pinned and not just the presence.
        indexOfOrFail("                    if (release.text.isNotBlank()) {\n")
        indexOfOrFail(
            "                        perceivedLatency.onVisible(seq, System.currentTimeMillis())" +
                "?.let { waited ->\n"
        )
        assertEquals(
            "the stamp is never consumed outside that gate",
            0,
            count("val waited = perceivedLatency.onVisible("),
        )
    }

    @Test
    fun theVisibleStampIsTakenAfterTheTextHasBeenDelivered() {
        // WORDS ON THEIR WAY is the claim. deliverReleasedText hands the resolved text to the
        // preview sink and returns; the TextView write lands one Main dispatch later (StateFlow ->
        // previewJob), so this stamp excludes one hop and one frame. Reading it ABOVE the call
        // would instead time the START of delivery — a much larger and much less honest error.
        // M11 is the mutant: the hoisted form compiles, runs, and emits a line for every segment.
        val delivered = indexOfOrFail("                    deliverReleasedText(release.text)\n")
        val stamped =
            indexOfOrFail("                        perceivedLatency.onVisible(seq, System.currentTimeMillis())")
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
