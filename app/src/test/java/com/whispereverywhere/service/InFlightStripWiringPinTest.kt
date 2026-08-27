package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE IN-FLIGHT STRIP'S WIRING, pinned structurally (3.7 Workstream G, Task G5).
 *
 * `InFlightStripTest` pins the four rules — [deltaOwnsPreviewStrip], [inFlightStripLabel],
 * [inFlightStripVisibility], [resolvedTextClearsStrip] — byte-for-byte, because they are pure.
 * What no test in this suite can see is everything BETWEEN those rules and the screen:
 * `FloatingBubbleService` is an Android `Service` whose `View` fields are `lateinit`, this project
 * has **no Robolectric** on its unit-test classpath (`junit:4.13.2` and `kotlinx-coroutines-test`
 * are the whole of `testImplementation`), and the render is `private`. So no JVM test can construct
 * the class, reach the render's body, or observe a single call site. Task G4's battery measured
 * exactly that: two mutations INSIDE the render survived a fully green 1322-test suite.
 *
 * The remedy is Task F8's and it is the house idiom — pin the CALL, structurally, not the thing
 * being called; the same instrument and the same argument as `CapSeamPinTest`,
 * `EndpointerLifecyclePinTest`, `CommitFunnelPinTest` and `PerceivedStampPinTest`.
 *
 * **Why a new class rather than rows in `CommitFunnelPinTest`.** That class is scoped to the ONE
 * commit funnel by its own KDoc and G4 already stretched it once to admit the funnel's repaint
 * post. Nothing here is funnel territory: `estimatedWindowSize`'s strip allowance is resize math,
 * the `onDelta` gate is a callback's first statement, and the two bare painters are on the
 * resolution path and in delivery. A killer set stays legible when the class that dies names the
 * contract it died for.
 *
 * **The two G4 survivors this class closes.**
 *  - *The window under-shoot.* `estimatedWindowSize` allows the strip its ~100dp on `!= View.GONE`
 *    rather than `== View.VISIBLE`, because Task G4's `OCCUPYING_BLANK` parks the strip at
 *    INVISIBLE — a state that occupies layout height. Reverting the read UNDER-estimates the
 *    window, which is the unsafe direction for a clamp: the window bottom can be left off-screen.
 *    G4 introduced the state; **G5 is the task that first makes it reachable** (until delivery
 *    stopped hiding the strip, `OCCUPYING_BLANK` never occurred in production at all), so the pin
 *    lands in the commit that arms the defect.
 *  - *The single-Boolean hazard.* The render asks `deltaOwnsPreviewStrip(sessionIsLive = …)` and
 *    the argument must be `sessionIsLive`, never `cloudWrapper != null`. Those two look
 *    interchangeable and are not: `cloudWrapper` is non-null for CLOUD_BATCH, which emits no deltas
 *    whatsoever, so the substitution makes the render return early on the majority cloud path and
 *    restores the "nothing on screen changes for four seconds" state Workstream G exists to remove.
 *    Both forms compile, and the whole suite stays green.
 *
 * **And the anti-churn rule's actual payload.** `if (wasHidden) bubbleView.post { reclampNow() }`
 * is the one line the entire workstream's churn argument reduces to. Nothing counted it before this
 * class, so hoisting the post out of its guard — the single most natural edit anyone will make in
 * this method — passed a green suite while restoring a `reclampNow()` on every repaint, ~16×/minute.
 *
 * **The source is read LF-NORMALISED.** `core.autocrlf=true` checks this repo out with CRLF, so a
 * needle written with a bare `\n` finds nothing and every assertion would pass or fail for the
 * wrong reason. The normalisation happens once, at the single read site below.
 *
 * **Everything here is SYMBOL-SCOPED.** `deltaOwnsPreviewStrip(sessionIsLive = sessionIsLive)`
 * occurs three times in the file — the rule's own body, the `onDelta` gate and the render — so a
 * whole-file census could not say which one moved. Line numbers are never used: every anchor this
 * workstream inherited from the plan had drifted, by up to ~155 lines.
 */
class InFlightStripWiringPinTest {

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

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun indexOfOrFail(haystack: String, needle: String): Int {
        val i = haystack.indexOf(needle)
        assertTrue("missing from FloatingBubbleService.kt: <<$needle>>", i >= 0)
        return i
    }

    /**
     * One declaration to its own closing brace, matched by INDENTATION rather than by counting
     * braces: `closer` is the first line at the declaration's own nesting depth, which no nested
     * block can produce. Members close on four spaces, the engine-listener overrides on twelve.
     */
    private fun body(declaration: String, closer: String): String {
        val start = indexOfOrFail(text, declaration)
        val close = text.indexOf(closer, start)
        assertTrue("the closing brace of <<$declaration>> moved", close > start)
        return text.substring(start, close + closer.length)
    }

    private val render: String by lazy { body("    private fun renderInFlightStrip() {", "\n    }\n") }

    private val estimate: String by lazy {
        body(
            "    private fun estimatedWindowSize(widthDp: Float, heightDp: Float): Pair<Int, Int> {",
            "\n    }\n",
        )
    }

    private val delivery: String by lazy {
        body("    private fun deliverReleasedText(text: String) {", "\n    }\n")
    }

    private val onDelta: String by lazy {
        body("            override fun onDelta(text: String) {", "\n            }\n")
    }

    private val onResolved: String by lazy {
        body(
            "            override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {",
            "\n            }\n",
        )
    }

    @Test
    fun theRenderAsksTheSessionKindAndNeverTheWrapper() {
        // Task G4's M8, killed here. See the class KDoc: `cloudWrapper != null` is TRUE for
        // CLOUD_BATCH, which emits no deltas at all, so the substituted form makes the render
        // return early on the majority cloud path — silently, with the whole suite green.
        assertEquals(
            "the render asks who owns the strip with the SESSION KIND, exactly once",
            1,
            count(render, "deltaOwnsPreviewStrip(sessionIsLive = sessionIsLive)"),
        )
        assertFalse(
            "the render never reads cloudWrapper: it is non-null for CLOUD_BATCH, which streams " +
                "no deltas, so it is not a synonym for `this session is live`",
            render.contains("cloudWrapper"),
        )
    }

    @Test
    fun theWindowEstimateCountsAParkedStripAsPresent() {
        // Task G4's M7, killed here — and a SEPARATE test from the render's own contract above,
        // per this project's standing rule that different defects must name different tests. The
        // allowance is resize math, not render logic, and it fails for a different reason: an
        // under-estimated window clamps too close to the edge and can leave its bottom off-screen.
        assertEquals(
            "the strip's ~100dp allowance is granted on != GONE, so an INVISIBLE strip — the " +
                "state OCCUPYING_BLANK parks it in between utterances — still counts",
            1,
            count(
                estimate,
                "if (transcriptionDeltaText.visibility != View.GONE) (100 * density).toInt() else 0",
            ),
        )
        assertEquals(
            "the allowance is never re-narrowed to == VISIBLE: that is the UNDER-shoot, and " +
                "under-shooting is the unsafe direction for a clamp",
            0,
            count(estimate, "transcriptionDeltaText.visibility =="),
        )
    }

    @Test
    fun theRevealPostsItsReclampOnlyOnTheReveal() {
        // THE anti-churn rule's actual payload, and the only line the whole workstream's churn
        // argument reduces to. `inFlightStripVisibility` is pinned across all four of its input
        // combinations in InFlightStripTest, but until this assertion nothing said the reclamp was
        // GUARDED by the reveal. Hoisted out of the `if (wasHidden)`, every repaint posts a
        // reclampNow() again — ~16x/minute at utterance cadence, which is the exact cost G exists
        // to remove — and the suite stays green because no JVM test can reach this body.
        assertEquals(
            "the reclamp is posted ONLY on the reveal, the one time the window actually grew",
            1,
            count(text, "if (wasHidden) bubbleView.post { reclampNow() }"),
        )
        assertTrue(
            "the guarded post lives inside the render, not hoisted to a caller",
            render.contains("if (wasHidden) bubbleView.post { reclampNow() }"),
        )
    }

    @Test
    fun localDeltasAreTurnedAwayAtTheTopOfOnDelta() {
        // Task G5's headline: the native new_segment stream stops driving the strip. The gate is
        // written on the SESSION KIND — every non-live session — so it matches the render's own
        // guard, though in practice only the local engine emits deltas for it to turn away (cloud
        // batch emits none). The plumbing below is deliberately left running, since CLOUD_LIVE
        // still renders from it, so the ONLY thing standing between a local session and the old
        // per-frame set-and-clear burst is this one early return. Deleted, the commit's whole
        // premise is gone with a green suite.
        val gate = "                if (!deltaOwnsPreviewStrip(sessionIsLive = sessionIsLive)) return\n"
        val gateAt = indexOfOrFail(onDelta, gate)
        // FIRST statement, ahead of the Main hop: gating inside the coroutine would still schedule
        // a dispatch per delta — at whisper.cpp's burst rate, for nothing.
        assertTrue(
            "the gate is onDelta's first statement, above the coroutine hop",
            gateAt < indexOfOrFail(onDelta, "serviceScope.launch(Dispatchers.Main) {"),
        )
    }

    @Test
    fun theResolvePathCountsDownAndRepaintsBeforeItDelivers() {
        // The first of Task G5's two bare painters, plus the ORDER that makes it mean anything.
        // Deleting it leaves a blank release with no repaint at all: an EmptyExpected or a Lost
        // segment resolves without text, delivery's painter sits below its blank guard, and the
        // queue counts down in logcat while the user watches "Transcribing… (3 in queue)".
        //
        // NOTE, corrected at the G5 gate: the DECREMENT was never the fragile part. It lives
        // inside the log call below and `deliverReleasedText`'s `if (text.isBlank()) return`
        // returns from that METHOD, not from this coroutine, so it always ran — before G5 and
        // after. What the ordering actually protects is the PAINTERS: neither may read a
        // pre-decrement depth. The test's name is kept because what it names — count down, repaint,
        // then deliver — is exactly what is asserted and is true for the corrected reason.
        val counted =
            indexOfOrFail(onResolved, "EndpointDiag.queueLine(segmentQueueDepth.onResolved(seq)),")
        val painted = indexOfOrFail(onResolved, "                    renderInFlightStrip()\n")
        val delivered = indexOfOrFail(onResolved, "                    deliverReleasedText(release.text)\n")
        assertTrue("the repaint reads the depth AFTER this seq has been taken out of it", counted < painted)
        // Delivery PAINTS TOO (its non-live branch calls the render), so the decrement has to
        // precede it as well or that paint shows a backlog one deeper than it is.
        assertTrue("delivery paints too, so the decrement precedes delivery as well", counted < delivered)
        // And the resolve painter reports the countdown before the words land, rather than after.
        assertTrue("the backlog signal updates ahead of the text it is counting", painted < delivered)
        assertEquals(
            "the resolve path paints once — a second call here would repaint the same depth twice",
            1,
            count(onResolved, "renderInFlightStrip()"),
        )
    }

    @Test
    fun deliveryRepaintsTheStripInsteadOfHidingIt() {
        // The second bare painter, and the completion of Task G4's churn kill. The pre-3.7 body
        // hid the strip on EVERY non-FINALIZING release; that GONE is what `inFlightStripVisibility`
        // reads as `currentlyHidden`, so the next commit paid the reveal — and its reclampNow() —
        // all over again, once per utterance. Reverting this branch restores that cost invisibly.
        //
        // NOT-hiding is this branch's real contribution: on the onSegmentResolved path the resolve
        // painter has usually already painted the same depth, so the repaint here is a second
        // identical paint. It earns its place on the FOUR flush() sites that never reach
        // onSegmentResolved at all — onDestroy, the fatal-onError drain, the finalize flush and
        // teardown — where it is the only painter there is.
        indexOfOrFail(
            delivery,
            "        if (resolvedTextClearsStrip(sessionIsLive = sessionIsLive, " +
                "isFinalizing = finalizing)) {\n",
        )
        assertEquals(
            "delivery repaints the in-flight line rather than hiding the strip under it",
            1,
            count(delivery, "renderInFlightStrip()"),
        )
        // The strip is still hidden on the LIVE path — unchanged 3.6.0 behaviour, since there the
        // strip really was carrying this utterance's words — and hidden NOWHERE else in this
        // method. The ordering says which branch owns it.
        assertEquals(1, count(delivery, "transcriptionDeltaText.visibility = View.GONE"))
        assertTrue(
            "the hide belongs to the clearing branch; the repaint is the branch below it",
            indexOfOrFail(delivery, "transcriptionDeltaText.visibility = View.GONE") <
                indexOfOrFail(delivery, "renderInFlightStrip()"),
        )
    }

    @Test
    fun thereIsOneRenderBodyOnePostedCallerAndTwoBarePainters() {
        // ONE WRITER, MANY PAINTERS — the census, and the decision point for a fifth occurrence
        // (the D10 discipline: when a count is load-bearing, changing it must be a decision rather
        // than a diff). FOUR: the declaration, the funnel's launch-wrapped post, and the two bare
        // Main-side painters this task added. `commitSegment` is callable from the CAPTURE thread
        // so its repaint MUST hop; both painters here are already on Main and must NOT, which
        // CommitFunnelPinTest pins from the other side by holding the launch-wrapped form at 1.
        assertEquals(
            "one declaration + one posted caller + two bare painters; prose must not quote the " +
                "call form (fix the TEST, never the prose — but decide it here)",
            4,
            count(text, "renderInFlightStrip()"),
        )
        assertEquals(1, count(text, "    private fun renderInFlightStrip() {"))
    }
}
