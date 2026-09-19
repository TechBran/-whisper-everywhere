package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHERE THE §2 TABLE IS APPLIED, pinned structurally (4.10 Task 5).
 *
 * `SpeakerLabelsTest` proves the formatter; `TranscriptSinkTest` and `TranscriptStoreTest` prove
 * the two objects that call it. What no JVM test in this project can reach is the service that
 * decides WHICH surface gets WHICH render — `FloatingBubbleService` is an Android Service, there
 * is no Robolectric here — and every mistake available at that seam is silent with a green suite:
 *
 *  - rendering EXPORT for the injection would type `Speaker 1:` into somebody's message;
 *  - rendering FIELD for the clipboard would silently disable the user's export switch;
 *  - saving the LABELLED text to history would burn the switch into the file and make it
 *    permanent, which is the opposite of what the setting's KDoc promises;
 *  - flipping the latch on every chunk instead of once would re-emit the relabel line forever;
 *  - taking the runs snapshot BEFORE `close()` would save a history that is missing the last
 *    chunk of every session;
 *  - taking it without WAITING for the speaker pass would leave the last chunk of every session
 *    unassigned — the engine's drain returns on the pass that only SUBMITS that chunk's
 *    fingerprinting — so the final paragraph break would go missing from the field, the clipboard
 *    and the saved file, and a second speaker first confirmed on that chunk would never light the
 *    labels at all;
 *  - passing the spans without asking whether detection is on would make "detection off changes
 *    nothing" an accident of the assigner being null rather than a decision.
 *
 * So the pin is on the SOURCE, the house instrument for this file — the same reasoning
 * `CommitFunnelPinTest`, `PerceivedStampPinTest` and `AccessibilityOptionalDeliveryPinTest` carry
 * in full. Read LF-normalised, because `core.autocrlf=true` checks this repo out with CRLF and a
 * `\n` needle would otherwise find nothing and pass every assertion for the wrong reason.
 */
class SpeakerLabelsWiringPinTest {

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

    private val text: String by lazy {
        source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun count(needle: String) = text.split(needle).size - 1

    /** [count] over LIVE lines only — prose may name a symbol without wiring it. */
    private fun liveCount(needle: String) = text.split("\n").count { line ->
        val trimmed = line.trimStart()
        !(trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) &&
            needle in line
    }

    /**
     * The finalize coroutine's delivery-and-history span: from the stopwatch that opens the
     * single-delivery block to the line that closes the whole coroutine. Scoped because the other
     * two delivery callers (onDestroy, the fatal-onError drain) sit ABOVE it in the file, so a
     * whole-file `indexOf` of a shared needle finds one of them instead.
     */
    private val finalize: String by lazy {
        val from = indexOfOrFail("            val deliveryStartNs = System.nanoTime()\n")
        val to = text.indexOf("finalize-timing: total=", from)
        assertTrue("the finalize coroutine's closing timing line moved", to > from)
        text.substring(from, to)
    }

    private fun atIn(scope: String, needle: String): Int {
        val i = scope.indexOf(needle)
        assertTrue("missing from the finalize block: <<$needle>>", i >= 0)
        return i
    }

    private fun indexOfOrFail(needle: String): Int {
        val i = text.indexOf(needle)
        assertTrue("missing from FloatingBubbleService.kt: <<$needle>>", i >= 0)
        return i
    }

    // ------------------------------------------------------------------ the render sites

    @Test
    fun theServiceRendersTwiceAndBothAreExports() {
        // The census, and the decision point for a third (the D10 discipline: when a count is
        // load-bearing, changing it must be a decision rather than a diff). TWO renders live
        // here — what leaves on the clipboard, and what is written to history — and the mode is
        // EXPORT in both, because those are the only two of the spec's three surfaces this file
        // owns.
        assertEquals(
            "two render sites in the service: the clipboard's and history's",
            2,
            count("SpeakerLabels.render("),
        )
        assertEquals(2, count("SpeakerLabels.Mode.Export("))
    }

    @Test
    fun theFieldRenderIsNeverComputedHereBecauseTheDeliveredTextIsTheSinksFile() {
        // W2's invariant, kept: the one external write ships the SINK'S FILE, which `close()`
        // renders in FIELD mode. A `Mode.Field` appearing in this file would mean the service had
        // started deriving the delivered text a second way — and two ways to compute what the user
        // gets typed is exactly how the file and the injection come to disagree.
        assertEquals(0, count("SpeakerLabels.Mode.Field"))
        assertEquals(
            "the delivered text is read back from the sink's file, from the three delivery sites",
            3,
            count("fullTextFile()"),
        )
    }

    @Test
    fun theClipboardsRenderReadsTheUsersSwitchAtDeliveryAndFromOnePlace() {
        // ONE export-render body, three callers (finalize, the fatal-onError drain, onDestroy) —
        // so the switch cannot be read one way on one exit path and another way on the next.
        assertEquals(1, count("    private fun exportTranscript("))
        assertEquals("one declaration plus three call sites", 4, count("exportTranscript("))
        indexOfOrFail("            labels = app.preferencesManager.speakerLabelsInExport,")
        assertEquals(
            "the switch is read at delivery, in the render, and nowhere else in this file",
            1,
            liveCount("speakerLabelsInExport"),
        )
    }

    @Test
    fun historyIsSavedWithoutLabelsSoTheSwitchStaysAUserChoice() {
        // `labels = false` is the load-bearing literal: with the user's flag here instead, a
        // session recorded while the switch was on would carry `Speaker N:` in its file forever
        // and a session recorded while it was off could never gain them — the setting would
        // become a property of the past rather than of the export.
        indexOfOrFail(
            "                mode = com.whispereverywhere.transcription.speakers.SpeakerLabels" +
                ".Mode.Export(labels = false),"
        )
        assertEquals(1, count("SpeakerLabels.Mode.Export(labels = false)"))
    }

    // ------------------------------------------------------------------ the latch

    @Test
    fun theLatchIsFlippedOnceFromTheAssignersCallbackAndTheRelabelLineRidesOnItsAnswer() {
        // The `&&` is the whole test. `setLabelsVisible` answers whether the latch MOVED, so the
        // diag line is once per session; splitting these into two statements, or ignoring the
        // answer, re-emits it for every chunk of the rest of the session.
        indexOfOrFail("                        if (assignment.confirmed && sink.setLabelsVisible(true)) {")
        assertEquals("ONE latch-flip site", 1, count("setLabelsVisible("))
        assertEquals("ONE assign site", 1, count("sink.assign("))
        val assign = indexOfOrFail("sink.assign(assignment.seq, assignment.ids, assignment.remaps)")
        val flip = indexOfOrFail("sink.setLabelsVisible(true)")
        assertTrue("the ids are stamped before the panel is told to show them", assign < flip)
    }

    @Test
    fun theSpansAreGatedOnTheAssignerSoDetectionOffIsAConstructionNotAnAccident() {
        indexOfOrFail("            spans = if (speakerAssigner != null) release.spans else null,")
        assertEquals(1, count("release.spans"))
        // The sink is fed from exactly one place — the one accumulation point every session kind
        // shares (W2). A second append site would be a second transcript.
        assertEquals(1, count("sink.append("))
    }

    // ------------------------------------------------------------------ history and the sidecar

    @Test
    fun thereIsOneAccumulatorAndTheOldOneIsGone() {
        // `sessionTranscript` held history's copy of the text in parallel with the sink. Two
        // accumulators would let the panel and the saved file disagree about where a speaker
        // changed — a disagreement the export switch then makes visible. Named in prose only.
        for (line in text.split("\n")) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
            assertTrue(
                "a second transcript accumulator is back: <<$trimmed>>",
                "sessionTranscript" !in line,
            )
        }
    }

    @Test
    fun theRunsAreSnapshottedAfterTheSinkClosesAndBeforeAnythingIsDeliveredOrSaved() {
        // ORDER, which no count can express. close() is what writes the field render and flushes
        // the last chunk; a snapshot taken above it saves a history missing the end of every
        // session, and one taken after the delivery could be moved under by a straggler's ids.
        val close = atIn(finalize, "            finishedSink?.close()\n")
        val snapshot = atIn(finalize, "            val sessionRuns = finishedSink?.runs() ?: emptyList()\n")
        val confirmed =
            atIn(finalize, "            val sessionConfirmedSpeakers = finishedSink?.confirmedSpeakers ?: 0\n")
        val delivery = atIn(finalize, "                    deliverFinalTranscript(\n")
        val save = atIn(finalize, "                    transcriptStore.save(\n")
        assertTrue("the snapshot is taken after the sink has closed", close < snapshot)
        assertTrue(snapshot < confirmed)
        assertTrue("…and before the one external write", confirmed < delivery)
        assertTrue("…and before history is persisted", delivery < save)
    }

    @Test
    fun theSpeakerPassIsFencedBetweenTheOrdererFlushAndTheSinkBeingDetached() {
        // The two neighbours are the whole test. AFTER the flush, so every run of the session is
        // in the sink and a late id has something to land on; BEFORE the detach, so it still can
        // — `transcriptSink = null` makes the assigner's callback a `return@launch`, which is how
        // the last chunk's ids were being dropped on the floor of every session.
        val flush = indexOfOrFail("            deliverReleasedText(segmentOrderer.flush())\n")
        val fence = indexOfOrFail("assigner.awaitIdle(SPEAKER_DRAIN_MS)")
        val detach = indexOfOrFail("            val finishedSink = transcriptSink\n")
        assertTrue("the fence follows the orderer's flush", flush < fence)
        assertTrue("…and precedes the detach, close and snapshot", fence < detach)
        assertEquals("ONE speaker fence", 1, liveCount("awaitIdle(SPEAKER_DRAIN_MS)"))
        assertEquals("…with its bound named once", 1, liveCount("private val SPEAKER_DRAIN_MS"))
        // 2 500 ms since spike session 4. The old 1 500 was sized on ONE fingerprint per VAD
        // segment; the long-segment split can put several windows in the last chunk of a session
        // — which is exactly the chunk this fence exists for — and five at the measured 130-300 ms
        // each is 0.7-1.5 s of embedding that must land before the snapshot is taken. Nobody
        // waits the extra second unless the work is genuinely outstanding.
        indexOfOrFail("private val SPEAKER_DRAIN_MS = 2_500L")
        // Off Main: it blocks, and Main is where the whole finalize continuation runs.
        indexOfOrFail("withContext(Dispatchers.IO) { assigner.awaitIdle(SPEAKER_DRAIN_MS) }")
        // And it is skipped entirely when the session has no speaker pass (cloud, detection off).
        indexOfOrFail("            speakerAssigner?.let { assigner ->\n")
    }

    @Test
    fun historySavesTheRunsBesideTheTextSoTheSidecarExists() {
        // The sidecar is what makes the export switch apply to transcripts the user already has.
        // Dropping these two arguments leaves save() on its default (no sidecar) and compiles.
        indexOfOrFail("                        runs = sessionRuns,\n")
        indexOfOrFail("                        confirmedSpeakers = sessionConfirmedSpeakers,\n")
        assertEquals("ONE history save site", 1, count("transcriptStore.save("))
        // The text saved is the render, not a StringBuilder's contents.
        indexOfOrFail("                        text = historyText,\n")
        assertEquals(
            "history's blank gate is on the rendered text",
            1,
            count("if (historyText.isNotBlank()) {"),
        )
    }
}
