package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE STARTUP SEAM's wiring into the service (4.4.0 startup amendment, Task S2), pinned
 * structurally on the source — `FloatingBubbleService` is an Android Service and cannot be
 * instantiated on the JVM, so the BEHAVIOURAL half lives in `StartupRingTest` (the ring, the route
 * function, the exactly-once-in-order drain) and the ORDER-IN-SOURCE half lives here. The
 * instrument and its discipline are `CapSeamPinTest`'s.
 *
 * Five things decide whether this task is correct, and **four of the five fail silently** — no
 * exception, no log, no visible symptom beyond worse transcripts. That is why they are pinned
 * here rather than described in a comment:
 *
 *  1. **`probeArm()` must precede the capture-thread start.** `endpointer.onSessionStart` fires
 *     `probeArm`, and `audio/VadProbeLifecycle.kt`'s precondition (2) says the ordering that
 *     actually carries it is a THREAD START, not reachability: the capture thread snapshots its
 *     epoch at its first probe call (`EndpointerFactory`'s `mine`), so a thread started before
 *     `arm()` snapshots `NO_SESSION`, `ensureReady(session)` is false forever, and the Silero VAD
 *     is OFF for the whole session — the pre-3.7 amplitude machine, with no error anywhere. Until
 *     S2 the ordering was carried by the nesting (`onOpen` ran `onSessionStart` and only then
 *     `startAudioInput()`); the nesting is gone, so the ordering is now explicit and pinned.
 *  2. **`endpointer.onSessionStart` must stay above `setActiveSource`.** `onSessionStart` writes
 *     `flatlineArmed = false` and `setActiveSource` writes `armFlatline(source == PLAYBACK)`.
 *     Reversed, the 4.4 flatline cut is permanently disarmed for every device-audio session —
 *     visible only on device-audio capture of edited media. `setActiveSource` is reached ONLY
 *     through `startAudioInput()`'s two source starters, so "`startAudioInput()` stays below
 *     `onSessionStart`" is what preserves it, and that is what row 2 asserts.
 *  3. **The drain is PACED and runs on the CAPTURE THREAD.** A full ring is ~430 ms of Silero
 *     probe work against an `AudioRecord` ring that overflows in >=128 ms, so a dumped drain would
 *     lose audio mid-session — a stutter, strictly worse than the truncation being fixed. And it
 *     must be the capture thread that replays: `VadProbeLifecycle` keeps ONE shared direct buffer,
 *     so a second thread feeding the probe concurrently is teardown-bill T8/T9.
 *  4. **Replayed chunks keep their ORIGINAL stamps.** The drain's sink forwards the ring's own
 *     `(pcm, amp, nowMs)` triple; re-stamping at drain time would compress 6 s of dips into ~2 s.
 *  5. **The ring is discarded on every exit path**, so a failed connect never replays into a dead
 *     engine and a new session never opens with the previous one's audio.
 *
 * Plus the honesty patch folded into S2: the "listening" haptic fires at TRUE readiness, in
 * `onOpen`, and the tap gets its own distinct shorter acknowledgement.
 *
 * **The source is read LF-NORMALISED** (`core.autocrlf=true` checks this repo out with CRLF), once,
 * at the single read site below — the same defence `CapSeamPinTest` uses.
 */
class StartupRingWiringPinTest {

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

    /** A member's body: the anchor line to the first non-blank line at or left of its own indent. */
    private fun memberBody(anchor: String): String {
        val start = indexOfOrFail(anchor)
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val indent = text.substring(lineStart).substringBefore("\n").takeWhile { it == ' ' }.length
        val lines = text.substring(start).split("\n")
        val body = StringBuilder(lines.first())
        var closed = false
        for (line in lines.drop(1)) {
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= indent) { closed = true; break }
            body.append("\n").append(line)
        }
        assertTrue("member never closes: $anchor", closed)
        return body.toString()
    }

    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").map { it.trimStart() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) &&
                line.contains(needle)
        }

    /**
     * `startRecording`'s body ABOVE `engine.connect(...)`.
     *
     * Scoping to the whole member is wrong for this task's questions: the engine listener is an
     * ANONYMOUS OBJECT declared inside `startRecording`, so `onOpen`'s 20-space body is part of
     * `startRecording`'s body by indentation and a member-scoped `liveLines` would find the
     * readiness cue "at the tap" when it is in fact at `onOpen`. Everything S2 moved, it moved
     * across exactly this boundary.
     */
    private fun startRecordingAboveConnect(): String {
        val body = memberBody("    private fun startRecording() {")
        val connect = body.indexOf("        engine.connect(lang, object : TranscriptionEngine.Listener {")
        assertTrue("startRecording must still call connect", connect > 0)
        return body.substring(0, connect)
    }

    // ---------------------------------------------------------------- row 1 + row 2: the ordering

    @Test
    fun theRecorderAndTheCaptureThreadStartAboveConnectNotInsideOnOpen() {
        val body = memberBody("    private fun startRecording() {")
        assertEquals(
            "startAudioInput() is called from startRecording, once, above connect()",
            1,
            liveLines(body, "val started = startAudioInput()").size,
        )
        val startInput = body.indexOf("        val started = startAudioInput()")
        val connect = body.indexOf("        engine.connect(lang, object : TranscriptionEngine.Listener {")
        assertTrue("startRecording must still call connect", connect >= 0)
        assertTrue("the recorder opens BEFORE connect(), which is the whole task", startInput < connect)
        // ...and it is no longer nested inside onOpen: the old 20-space site must not come back.
        assertEquals(
            "startAudioInput() must not be re-nested inside onOpen's Main body",
            0,
            count("                    val started = startAudioInput()"),
        )
        assertEquals("exactly one startAudioInput() call in the whole service", 1, count("startAudioInput()\n"))
    }

    @Test
    fun theEndpointerIsArmedBeforeTheCaptureThreadExistsAndBeforeTheSourceIsSettled() {
        // ROW 1 and ROW 2 are the same two offsets, because setActiveSource is reachable only
        // through startAudioInput()'s source starters.
        val cadence = indexOfOrFail("        endpointer.onSessionStart(")
        val startInput = indexOfOrFail("        val started = startAudioInput()")
        assertTrue(
            "probeArm (fired by onSessionStart) must precede the capture-thread start, or the " +
                "Silero VAD is silently off for the session",
            cadence < startInput,
        )
        // setActiveSource may not be called from startRecording directly — if it were, it could
        // sit above onSessionStart and disarm the flatline cut for every device-audio session.
        val body = memberBody("    private fun startRecording() {")
        assertEquals(
            "the source is settled by startAudioInput() alone, below the endpointer's arm",
            emptyList<String>(),
            liveLines(body, "setActiveSource("),
        )
        assertEquals(1, count("endpointer.onSessionStart("))
    }

    @Test
    fun bothSessionClocksAreAnchoredAtTheTapAndTheCloudSuppressionMovedWithThem() {
        val capAnchor = indexOfOrFail("        segmentCapPolicy.onSessionStart(sessionStartMs)")
        val suppression = indexOfOrFail("        if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionStartMs)")
        val cadence = indexOfOrFail("        endpointer.onSessionStart(\n            nowMs = sessionStartMs,")
        assertTrue("the cap window opens at the tap, then the cloud suppression closes it", capAnchor < suppression)
        assertTrue("and the cadence handover follows both", suppression < cadence)
        // The anchor is the tap's stamp, and there is exactly one of each — a second site would
        // mean one clock anchored at the tap and the other at engine-ready.
        assertEquals(1, count("segmentCapPolicy.onSessionStart(sessionStartMs)"))
        assertEquals(1, count("if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionStartMs)"))
        assertEquals(
            "nothing may re-anchor either clock at engine-ready",
            0,
            count("sessionOpenMs"),
        )
    }

    // ------------------------------------------------------------------- row 3 + row 4: the drain

    @Test
    fun theDrainIsPacedAndRunsOnTheCaptureThreadFromOnAudioChunkAlone() {
        val body = memberBody("    private fun onAudioChunk(chunk: ByteArray, amp: Int) {")
        assertEquals(
            "the ONE paced drain, in the capture callback",
            1,
            liveLines(body, "startupRing.drainSlice(StartupRing.DRAIN_CHUNKS_PER_TICK)").size,
        )
        assertEquals(
            "no second drainSlice anywhere: a drain off the capture thread would feed the probe's " +
                "one shared direct buffer concurrently with the real capture thread (T8/T9)",
            1,
            count("drainSlice("),
        )
        assertEquals(
            "and no unbounded drain in the capture callback — a dumped ring starves record.read()",
            emptyList<String>(),
            liveLines(body, "drainAll"),
        )
        // The head routes through the PURE decision StartupRingTest executes.
        assertEquals(1, count("StartupSeam.route("))
        for (route in listOf("StartupSeam.Route.BUFFER ->", "StartupSeam.Route.DRAIN ->", "StartupSeam.Route.LIVE ->")) {
            assertTrue("onAudioChunk must handle $route", body.contains(route))
        }
    }

    @Test
    fun theReplaySinkForwardsTheRingsOwnStampsAndNotTheDrainTimeClock() {
        indexOfOrFail(
            "                startupRing.drainSlice(StartupRing.DRAIN_CHUNKS_PER_TICK) { pcm, pcmAmp, pcmNowMs ->\n" +
                "                    feedEngine(engine, pcm, pcmAmp, pcmNowMs)\n" +
                "                }"
        )
    }

    @Test
    fun aLiveChunkArrivingMidDrainQueuesBehindTheBacklogAndTheSeamRetiresItself() {
        // Exactly-once AND in-order: the live chunk may not overtake the backlog, so it is
        // appended rather than fed — unless the slice emptied the ring, in which case it IS the
        // oldest outstanding audio and goes straight through, which is what lets the session
        // return to the LIVE route instead of running one chunk behind for the rest of it.
        indexOfOrFail(
            "                if (startupRing.isEmpty()) feedEngine(engine, chunk, amp, nowMs)\n" +
                "                else startupRing.append(chunk, amp, nowMs)"
        )
    }

    @Test
    fun bothLiveAndReplayedAudioReachTheEngineThroughTheOneFeedFunction() {
        // "the drain is the same sendAudio path live audio uses" — one function, three callers
        // (BUFFER has none, DRAIN has two, LIVE has one), and no bare sendAudio beside it.
        val feed = indexOfOrFail("    private fun feedEngine(")
        val body = memberBody("    private fun feedEngine(")
        assertEquals(
            "sendAudio is the first thing feedEngine does, for live and replayed audio alike",
            1,
            liveLines(body, "engine.sendAudio(chunk)").size,
        )
        val send = body.indexOf("        engine.sendAudio(chunk)")
        val gate = body.indexOf("LiveTurnPolicy.runClientVad(")
        assertTrue("sendAudio must precede the client-VAD gate", send in 0 until gate)
        assertTrue("feedEngine is declared in the service", feed > 0)
        // The declaration plus exactly three calls: the replay sink, the live chunk the slice
        // made due, and the LIVE route. Any fourth is a second way into the engine.
        assertEquals(4, liveLines(text, "feedEngine(").size)
    }

    // ------------------------------------------------------------ row 5: the ring's three clears

    @Test
    fun theRingOpensEmptyAndIsDiscardedOnEveryExitPath() {
        val start = memberBody("    private fun startRecording() {")
        assertEquals(
            "a new session opens with an empty ring and an unready engine",
            1,
            liveLines(start, "startupRing.clear()").size,
        )
        assertEquals(1, liveLines(start, "engineReady = false").size)

        val teardown = memberBody("    private fun teardownRealtime() {")
        assertEquals(
            "teardownRealtime is the convergence point of every exit — normal drain end, " +
                "recorder-start failure, connect-time fatal, onDestroy — and discards the ring",
            1,
            liveLines(teardown, "startupRing.clear()").size,
        )
        assertEquals(1, liveLines(teardown, "engineReady = false").size)
        assertEquals(
            "and closes the sources, because from S2 the recorder can be open on a path that " +
                "never reached RECORDING",
            1,
            liveLines(teardown, "audioRecorder.stop()").size,
        )
        assertEquals(1, liveLines(teardown, "stopPlaybackCapturer()").size)

        assertEquals("exactly two clears: session open and session exit", 2, count("startupRing.clear()"))
    }

    @Test
    fun theStopPathFlushesWhateverTheDrainHadNotCaughtUpOnYet() {
        val body = memberBody("    private fun stopRecording() {")
        val flush = body.indexOf("startupRing.drainAll { pcm, _, _ -> sessionEngine.sendAudio(pcm) }")
        assertTrue("stopRecording must flush the ring's remainder", flush >= 0)
        val recorderStop = body.indexOf("        audioRecorder.stop()")
        val playbackStop = body.indexOf("        stopPlaybackCapturer()")
        val stopCommit = body.indexOf("        transcriptionEngine?.let { commitSegment(it, EndpointDiag.STOP) }")
        assertTrue("both capture sources have been stopped and joined first", recorderStop in 0 until flush)
        assertTrue(playbackStop in 0 until flush)
        assertTrue("and the flush precedes the unconditional stop commit that cuts it", flush < stopCommit)
        assertEquals(
            "two drainAll sites and only two — the stop path, and the ONE handover flush both " +
                "handover sites share; each on Main, each above the commit that cuts what it flushed",
            2,
            count("startupRing.drainAll"),
        )
    }

    @Test
    fun aSourceSwitchMidCatchUpFlushesTheRingIntoTheSegmentTheAudioBELONGSTo() {
        // THE ONE HAZARD S2 WOULD OTHERWISE ADD TO THE DEVICE-AUDIO LATCH. `switchSource` requires
        // RECORDING, which the paced catch-up now spends ~1.4-2.0 s inside, so media starting in
        // that window commits the boundary and swaps to playback capture WHILE THE RING STILL
        // HOLDS MICROPHONE AUDIO — and the new capturer's thread would then replay that microphone
        // pre-roll through the DRAIN route, on the far side of the boundary, into the device-audio
        // segment. That is the owner's headline rule ("keep the microphone out of it so someone
        // could transcribe a YouTube video without their actual spoken words being dictated")
        // broken by a 6 s buffer.
        //
        // The flush above the boundary commit is the fix that keeps the audio AND the rule: the
        // pre-roll is mic audio, so it belongs to the segment the mic was recording, and the
        // commit immediately below cuts it there.
        //
        // ROUND 1, B1: the flush is a FUNCTION, because `switchSource` is not the only handover —
        // `onMediaPlaybackStarted`'s consent-ask branch is its sibling for the no-token case and
        // needs the identical three steps. Both call sites are pinned, here and in the row below.
        //
        // ROUND 1, B2: AND IT RUNS BEHIND THE OLD SOURCE'S STOP+JOIN. Ahead of it, the old capture
        // thread is still live and `drainSlice` releases the ring's monitor before calling its sink
        // — so it holds a 4-chunk slice in flight and interleaves with this drainAll. The monitor
        // gives exactly-once and nothing about ORDER, so that window put old-source PCM behind the
        // boundary commit and behind the endpointer.reset() (up to 128 ms of microphone audio in
        // the device-audio segment) and put PCM into the engine out of capture order. Stop+join ->
        // flush -> cut is the order `stopRecording` has always had, and the order asserted here.
        val body = memberBody("    private fun switchSource(to: com.whispereverywhere.audio.ActiveSource) {")
        val sourceStop = body.indexOf("        when (activeSource) {")
        val flush = body.indexOf("        flushStartupRingAtSourceHandover()")
        val boundary = body.indexOf("        transcriptionEngine?.let { commitSegment(it, EndpointDiag.SWITCH) }")
        val reset = body.indexOf("        endpointer.reset()")
        assertTrue("switchSource must stop the OLD source", sourceStop >= 0)
        assertTrue("switchSource must flush the ring", flush >= 0)
        assertTrue("switchSource must still cut the boundary", boundary >= 0)
        assertTrue("and still reset the endpointer across the acoustic-source change", reset >= 0)
        assertTrue(
            "the old source is stopped and joined BEFORE the flush, or its capture thread " +
                "interleaves with the flush and lands PCM past the cut, out of order",
            sourceStop < flush,
        )
        assertTrue(
            "the microphone's pre-roll is committed on the MIC side of the boundary, never " +
                "replayed on the device-audio side",
            flush < boundary,
        )
        assertTrue("and the D9/D10 reset follows the cut it belongs to", boundary < reset)
        // The stop is the OLD source's, whichever it is — both arms, above the flush.
        for (arm in listOf(
            "            com.whispereverywhere.audio.ActiveSource.MIC -> audioRecorder.stop()",
            "            com.whispereverywhere.audio.ActiveSource.PLAYBACK -> stopPlaybackCapturer()",
        )) {
            val at = body.indexOf(arm)
            assertTrue("missing from switchSource: <<$arm>>", at in 0 until flush)
        }
        // The flush says so under its own name, so a log can tell the two flushes apart — and it
        // lives in ONE place: the declaration plus exactly the two handover call sites.
        assertEquals(1, count("StartupRing.switchFlushLine("))
        assertEquals(
            "the handover flush is one function with two callers — found: " +
                liveLines(text, "flushStartupRingAtSourceHandover("),
            3,
            liveLines(text, "flushStartupRingAtSourceHandover(").size,
        )
    }

    @Test
    fun theConsentAskHandoverIsSwitchSourcesSiblingAndFlushesTheRingTheSameWay() {
        // ROUND 1, B1. `onMediaPlaybackStarted`'s consent-ask branch is the "media transcription
        // cuts the mic" handover for the case where there is no projection token yet: same gate as
        // `switchSource`, so it is reachable through the whole paced catch-up, and it STOPS THE
        // MICROPHONE. After that stop nothing drains the ring — the paced drain is driven by live
        // chunks alone — so a ring left standing here survives the 1-3 s consent wait, and
        // `startPlaybackSource()`'s FIRST chunk then takes the DRAIN route and replays up to 6 s of
        // room audio into the device-audio segment. Straight-line, not a race.
        //
        // The three steps, in this order, are what makes it safe, and all three are pinned:
        // stop+join the mic, flush the ring it filled, cut the boundary.
        val branch = memberBody("                } else if (consentBudget.mayAsk()) {")
        val micStop = branch.indexOf("                    audioRecorder.stop()")
        val flush = branch.indexOf("                    flushStartupRingAtSourceHandover()")
        val boundary = branch.indexOf(
            "                    transcriptionEngine?.let { commitSegment(it, EndpointDiag.SWITCH) }"
        )
        assertTrue("the consent-ask handover must stop the microphone", micStop >= 0)
        assertTrue("...flush the ring the microphone filled", flush >= 0)
        assertTrue("...and cut the boundary", boundary >= 0)
        assertTrue("the flush runs behind the mic's stop+join, never ahead of it", micStop < flush)
        assertTrue(
            "and the microphone's pre-roll is cut on the MIC side of the boundary, never replayed " +
                "on the device-audio side once consent lands",
            flush < boundary,
        )
        // And the branch still asks: the flush may not have displaced the consent request.
        assertEquals(1, liveLines(branch, "MediaProjectionGate.requestConsent(").size)
    }

    // ------------------------------------------------------------------------------- the haptic

    @Test
    fun theListeningCueFiresAtTrueReadinessAndTheTapGetsItsOwnAcknowledgement() {
        val start = startRecordingAboveConnect()
        assertEquals(
            "the tap's own short acknowledgement, not the listening cue",
            1,
            liveLines(start, "vibrateTap()").size,
        )
        assertEquals(
            "the 'listening' cue must NOT fire at the tap any more — on a cold local tier it " +
                "preceded the first captured sample by up to 4,107 ms",
            emptyList<String>(),
            liveLines(start, "vibrateStart()"),
        )
        // It fires from onOpen's Main body, past the readiness flag.
        val ready = indexOfOrFail("                    engineReady = true")
        val recording = text.indexOf("                    updateBubbleState(BubbleState.RECORDING)", ready)
        val cue = text.indexOf("                    vibrateStart()", ready)
        assertTrue("the cue is in onOpen's Main body", cue > 0)
        assertTrue("and it follows the readiness flag and the RECORDING flip", cue > recording)
        // LIVE lines only, whole file: the declaration plus the single call, for each cue. Both
        // names are also quoted in load-bearing comments at the two sites, and those are prose —
        // fix the test, never the comments (EndpointerLifecyclePinTest states the rule).
        assertEquals(2, liveLines(text, "vibrateStart()").size)
        assertEquals(2, liveLines(text, "vibrateTap()").size)
    }

    @Test
    fun theReadinessFlagIsSetOnceInOnOpenAndTheDrainLineIsEmittedBesideIt() {
        assertEquals("one writer for true, one for false-at-open, one for false-at-teardown", 1, count("engineReady = true"))
        // The field's own initialiser plus the two writes: a session may not open ready, and a
        // torn-down session may not leave the flag standing for the next one's capture thread.
        assertEquals(3, liveLines(text, "engineReady = false").size)
        val drainLine = indexOfOrFail("StartupRing.drainLine(")
        val ready = indexOfOrFail("                    engineReady = true")
        assertTrue("the line names the backlog the flag is about to release", drainLine < ready)
        assertEquals(1, count("StartupRing.drainLine("))
        assertEquals(1, count("StartupRing.overflowLine("))
        assertEquals(1, count("StartupRing.stopFlushLine("))
        assertEquals(1, count("StartupRing.switchFlushLine("))
    }
}
