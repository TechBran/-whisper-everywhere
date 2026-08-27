package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE ONE COMMIT FUNNEL, pinned structurally (3.7 Workstream F). Task F7 routed all five of this
 * service's commit sites through `commitSegment`; this file is what keeps them routed.
 *
 * **Why it exists.** F7's mutation battery restored a direct `engine.commit()` at each of the five
 * sites in turn. Three died on pins Workstream D had already left behind — `CapSeamPinTest` for the
 * wall-cap site, `EndpointerLifecyclePinTest` for `switchSource` and the stop flush — but the
 * ENDPOINT cut and the projection-consent flush had no pin at all and the whole suite stayed green,
 * as did a funnel that fed [SegmentQueueDepth] a constant instead of the seq the engine returned.
 * The endpoint cut is the site 3.7 exists to produce, and a `queue:` line reading `depth=0` for the
 * life of a session is a diagnostic that lies exactly when the backlog it reports is growing. Both
 * are silent regressions with a green suite, so both get a pin.
 *
 * `FloatingBubbleService` is an Android Service and cannot be instantiated in a JVM test, so the
 * pin is on the SOURCE — the same instrument and the same reasoning as `CapSeamPinTest` and
 * `EndpointerLifecyclePinTest`, whose KDocs carry the full argument. Task G3 extends the funnel's
 * BODY and nothing here constrains that; Task F9's own body addition — the speech-end stamp — is
 * pinned in `PerceivedStampPinTest`, deliberately not here, because half of that contract lives in
 * `onSegmentResolved`. What is pinned is narrower and permanent: there is ONE funnel, the five
 * sites reach the engine only through it, it records the seq the engine actually returned, it
 * reads the endpointer's cut record ONLY on a `cut=vad` commit, and it emits both of its lines.
 *
 * **That last one is Task F8's addition, and it is the one body constraint this class carries on
 * purpose.** `SileroEndpointer.lastCut()` is not a "current state" accessor: it holds the LAST vad
 * cut and survives until the next one, so a cap / stop / switch commit that asked for it would be
 * handed an OLDER cut's `speechMs`/`trailMs`/`p` and would print them as this segment's. Task C8's
 * report states the read contract and names the exact regression it feared — "a simplification that
 * hoists the `lastCut()` read above the branch and lets non-nullness stand in for the
 * discriminator" — and that mutant is invisible to every other test in the suite, because no test
 * can reach the funnel's body. So it is pinned here, structurally, where the branch is legible.
 *
 * **Counting discipline (the D10 lesson).** A bare count of `engine.commit()` reads TWO, not one:
 * D9's load-bearing note in the cap branch quotes the call verbatim ("commitRetainingTailMs(0) IS
 * engine.commit()"), and that comment is what states the identity the wall cap's 3.6.0-parity
 * argument rests on. Fix the TEST, never the prose. The needle below is therefore
 * `else engine.commit()`, a form only the funnel's own body can produce.
 *
 * **The source is read LF-NORMALISED.** `core.autocrlf=true` checks this repo out with CRLF, so a
 * needle written with a bare `\n` finds nothing and every assertion would pass or fail for the
 * wrong reason. The normalisation happens once, at the single read site below.
 */
class CommitFunnelPinTest {

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

    @Test
    fun thereIsExactlyOneFunnelAndNeverASecond() {
        assertEquals(
            "commitSegment is THE funnel, and a private MEMBER so it can reach segmentQueueDepth, " +
                "endpointer and serviceScope — never a top-level extension",
            1,
            count("    private fun commitSegment("),
        )
        // SIX occurrences of the call form: this one declaration plus the five call sites pinned
        // below. A seventh means a sixth commit site appeared — or that prose started quoting the
        // call form, the same hazard D9's comment created for `engine.commit()`. Either way this
        // file is where that gets decided, deliberately.
        assertEquals(6, count("commitSegment("))
    }

    @Test
    fun allFiveCommitSitesRouteThroughTheFunnel() {
        // The two CAPTURE-THREAD sites. Each names its cut kind and passes the FRAME's clock, so
        // Task F9's speech-end stamp is measured against the same instant the endpointer's
        // trailMs was rather than a wall clock re-read after a ~960 KB buffer snapshot.
        indexOfOrFail("                commitSegment(engine, EndpointDiag.VAD, nowMs = now)")
        indexOfOrFail(
            "                commitSegment(engine, EndpointDiag.CAP, retainMs = retainMs, nowMs = now)"
        )
        // The three MAIN-side sites, which take the funnel's default clock: the projection-consent
        // handover and switchSource (both SWITCH — a source handover is a source handover), and
        // the unconditional stop flush.
        assertEquals(2, count("transcriptionEngine?.let { commitSegment(it, EndpointDiag.SWITCH) }"))
        assertEquals(1, count("transcriptionEngine?.let { commitSegment(it, EndpointDiag.STOP) }"))
    }

    @Test
    fun noCommitSiteReachesTheEngineDirectlyAnyMore() {
        assertEquals(
            "a bare transcriptionEngine?.commit() is a site throwing its seq away again",
            0,
            count("transcriptionEngine?.commit()"),
        )
        // The funnel's body is the ONLY place either engine entry point is called. See the class
        // KDoc for why this needle carries its `else`.
        assertEquals(1, count("else engine.commit()"))
        assertEquals(1, count("engine.commitRetainingTailMs("))
    }

    @Test
    fun theFunnelRecordsTheSeqTheEngineActuallyReturned() {
        // The mutant this kills fed the backlog a constant: the suite stayed green while every
        // `queue:` line read depth=0 for the whole session.
        assertEquals(1, count("segmentQueueDepth.onCommitted(seq)"))
        // ...and the resolution side is fed the seq that actually resolved, from the one place it
        // lands. Depth is a SET difference, so a constant on either side strands or inverts it.
        assertEquals(1, count("segmentQueueDepth.onResolved(seq)"))
    }

    @Test
    fun theEndpointerCutRecordIsReadOnlyOnAVadCommit() {
        // The gate is on the CUT KIND. Dropping the `if (cut == EndpointDiag.VAD)` — the
        // "simplification" Task C8's read contract names — makes every cap/stop/switch line report
        // the PREVIOUS vad cut's numbers as this segment's, with the whole suite green.
        indexOfOrFail(
            "        val ec = if (cut == EndpointDiag.VAD) " +
                "(endpointer as? SileroEndpointer)?.lastCut() else null\n"
        )
        assertEquals(
            "the endpointer's cut record is read from exactly one place, the funnel's vad branch",
            1,
            count(".lastCut()"),
        )
    }

    @Test
    fun theFunnelActuallyEmitsTheEndpointLine() {
        // A SEPARATE test from the gate above, deliberately: the two contracts fail for different
        // reasons and a shared test would make the battery's killer sets unreadable — dropping the
        // gate and deleting the emission are different defects and must name different tests.
        //
        // This is the pin the F8 review's fourth mutant demanded. Every BYTE of the line is pinned
        // in EndpointDiagTest, and the gate that computes `ec` is pinned above, but until this
        // assertion nothing said the funnel ever CALLS the formatter: the reviewer deleted the
        // Log.i and the whole 1276-test suite passed. The `queue:` sibling survives the same
        // attack only by accident — `segmentQueueDepth.onCommitted(seq)` happens to live INSIDE
        // its log call, so deleting that line drops its count to 0. `endpointLine`'s arguments
        // carry no such incidental anchor, so it gets an explicit one.
        assertEquals(
            "the funnel EMITS the endpoint: line — the formatter being pinned is not the same " +
                "claim as the funnel calling it",
            1,
            count("EndpointDiag.endpointLine(seq, cut, ec)"),
        )
    }

    @Test
    fun theFunnelActuallyEmitsTheQueueLine() {
        // The accident named above, closed. `queue:` survived the emission-deletion attack only
        // because `segmentQueueDepth.onCommitted(seq)` happens to sit INSIDE its log call, so the
        // census in theFunnelRecordsTheSeqTheEngineActuallyReturned dropped to 0 along with it —
        // an incidental kill that a refactor moving the bookkeeping onto its own line would end,
        // silently. Task F9 inserted the speech-end stamp directly above this line, so per F8's
        // handoff the accident is replaced with an explicit pin rather than left standing.
        assertEquals(
            "the funnel EMITS the commit-side queue: line",
            1,
            count("EndpointDiag.queueLine(segmentQueueDepth.onCommitted(seq))"),
        )
        // Two emissions in the file and no more: the commit side here and the resolve side in
        // onSegmentResolved. A third would mean the depth is being reported from somewhere that
        // did not change it.
        assertEquals(2, count("EndpointDiag.queueLine("))
    }

    @Test
    fun theBacklogIsResetAtSessionStartBesideTheOrderer() {
        val orderer =
            indexOfOrFail("        segmentOrderer = com.whispereverywhere.transcription.SegmentOrderer()")
        val reset = indexOfOrFail("        segmentQueueDepth.reset()")
        assertTrue("the backlog resets at session START, beside the orderer", reset > orderer)
        assertEquals(
            "a second reset — at stop — would blank the diagnostic for the whole drain, the part " +
                "of the session it exists to show",
            1,
            count("segmentQueueDepth.reset()"),
        )
    }
}
