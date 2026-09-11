package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * THE DEVICE-AUDIO LATCH (owner rule, 2026-09-02): once a transcription session is capturing
 * device audio, **the microphone never enters that session**. Not when the video is paused, not
 * when it is scrubbed, not when it ends — capture holds until the user ends the transcription,
 * and only then is the projection released (which `stopRecording` already does).
 *
 * WHAT THIS PREVENTS, in the owner's words: "we're trying to keep the microphone out of it so
 * someone could transcribe a YouTube video without their actual spoken words being dictated."
 * Before this rule, scrubbing a video made YouTube's media session report a non-playing state,
 * `onMediaPlaybackStopped` fired, and the service called `switchSource(to = MIC)` **mid-session
 * with the projection still live** — so the room was recorded into the video's transcript until
 * playback resumed, and the resume cut a second segment. Two spurious cuts and a bleed, per scrub.
 *
 * A source pin, because the defence is the ABSENCE of a call: `FloatingBubbleService` is an
 * Android service no JVM test can construct, and a deleted behaviour has no runtime surface to
 * assert against. Live lines only — a commented-out handback must not satisfy it.
 */
class DeviceAudioLatchPinTest {

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").map { it.trimStart() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) &&
                line.contains(needle)
        }

    /** A member's body: the anchor line to the first non-blank line at or left of its own indent. */
    private fun memberBody(kt: String, anchor: String): String {
        val start = kt.indexOf(anchor)
        assertTrue("anchor missing: $anchor", start >= 0)
        val lineStart = kt.lastIndexOf('\n', start - 1) + 1
        val indent = kt.substring(lineStart).substringBefore("\n").takeWhile { it == ' ' }.length
        val lines = kt.substring(start).split("\n")
        val body = StringBuilder(lines.first())
        var closed = false
        for (line in lines.drop(1)) {
            if (line.isNotBlank() && line.takeWhile { it == ' ' }.length <= indent) { closed = true; break }
            body.append("\n").append(line)
        }
        assertTrue("member never closes: $anchor", closed)
        return body.toString()
    }

    private val service: String by lazy {
        source("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt")
    }

    @Test
    fun media_stopping_never_hands_a_live_capture_session_back_to_the_microphone() {
        val body = memberBody(
            service,
            "    override fun onMediaPlaybackStopped() {",
        )
        assertEquals(
            "a scrub, a pause or the video ending must not switch the source — found: " +
                liveLines(body, "switchSource("),
            emptyList<String>(),
            liveLines(body, "switchSource("),
        )
        assertEquals(
            "and it must not start the microphone by any other route either — found: " +
                liveLines(body, "startMicSource("),
            emptyList<String>(),
            liveLines(body, "startMicSource("),
        )
    }

    @Test
    fun the_session_still_releases_the_projection_when_the_user_ends_it() {
        // The latch holds capture for the session's life; ending the session is what frees the
        // phone (the owner's 2026-08-01 decision, unchanged by this rule — a live projection
        // interferes with mic capture and the sharing indicator must not outlive the transcript).
        val body = memberBody(service, "    private fun stopRecording() {")
        assertEquals(
            "stopRecording releases the projection exactly once",
            1,
            liveLines(body, "MediaProjectionGate.clear()").size,
        )
        assertEquals(
            "and stops the capturer",
            1,
            liveLines(body, "stopPlaybackCapturer()").size,
        )
    }

    /**
     * THE LEAK 4.4.0 S2 NEWLY MAKES REACHABLE, and the amendment's condition 5: "with the recorder
     * open before the source is settled, prove the mic can never contribute to a device-audio
     * session". There was no pin for this before S2 — the three above are each scoped to some
     * other member's body — and S2 is exactly what makes it worth one: `startAudioInput()` now
     * runs at the TAP, above `connect()`, so the tempting shortcut is "open the mic while we work
     * out what the source should be", and that shortcut is this class's whole prohibition.
     *
     * The structural answer is that `startRecording` opens NO source of its own. Its single
     * audio-start call is `startAudioInput()`, and `startAudioInput()`'s first statement is
     * `AudioSourcePolicy.decide(...)` — which depends on nothing from `connect()`
     * (`docs/superpowers/research/2026-09-10-startup-cutoff-investigation.md` §2 fact 3: it reads
     * only mediaPlaying / hasProjection / sdkInt / preferDeviceAudio / consentAvailable), which is
     * why the whole call could legally move above `connect()` in the first place.
     */
    @Test
    fun starting_a_session_opens_no_source_of_its_own_now_that_the_recorder_precedes_connect() {
        val body = memberBody(service, "    private fun startRecording() {")
        assertEquals(
            "startRecording must reach the microphone only through startAudioInput() — found: " +
                liveLines(body, "audioRecorder.start("),
            emptyList<String>(),
            liveLines(body, "audioRecorder.start("),
        )
        assertEquals(
            "...and not through the source starters either — found: " + liveLines(body, "startMicSource("),
            emptyList<String>(),
            liveLines(body, "startMicSource("),
        )
        assertEquals(
            emptyList<String>(),
            liveLines(body, "startPlaybackSource("),
        )
        assertEquals(
            "exactly one audio-start call at session open, and it is the one that decides",
            1,
            liveLines(body, "startAudioInput()").size,
        )
    }

    @Test
    fun the_source_is_decided_before_any_capture_opens() {
        val body = memberBody(service, "    private fun startAudioInput(): Result<Unit> {")
        val decide = body.indexOf("com.whispereverywhere.audio.AudioSourcePolicy.decide(")
        assertTrue("startAudioInput must ask the policy", decide >= 0)
        for (starter in listOf("startMicSource()", "startPlaybackSource()")) {
            val first = body.indexOf(starter)
            assertTrue("$starter must not open a source above the decision", first > decide)
        }
        // Exactly the three routes the policy has: UseMic -> mic, UsePlayback -> playback with the
        // pre-Q mic fallback, RequestConsent -> nothing. A fourth mic route here would be a mic
        // opened for a reason the policy never gave.
        assertEquals(2, liveLines(body, "startMicSource()").size)
        assertEquals(1, liveLines(body, "startPlaybackSource()").size)
    }

    @Test
    fun waiting_for_projection_consent_opens_no_source_at_all() {
        // The branch the investigation calls out by name: the mic is NOT opened while the consent
        // sheet is up (1-3 s), because media capture must never mix room audio. S2 gives this
        // branch no ring either — there is nothing captured to buffer — and its accepted gap is a
        // separate fix, out of S2's scope.
        val branch = memberBody(
            service,
            "            com.whispereverywhere.audio.SourceDecision.RequestConsent -> {",
        )
        for (needle in listOf("startMicSource(", "startPlaybackSource(", "audioRecorder.start(")) {
            assertEquals(
                "the consent wait must open no source — found: " + liveLines(branch, needle),
                emptyList<String>(),
                liveLines(branch, needle),
            )
        }
    }

    /**
     * RE-SPECCED at 4.4.0 S2 round 1 (B3), scope moved with the code and not renamed around it: the
     * handover's body moved out of `onMediaPlaybackStarted` into `handOverMicToDeviceAudio()`,
     * because it now has TWO triggers (see the row below) and one of them is `onOpen`. The
     * invariant is the same one — the latch is ONE-WAY, and handing the MIC over TO device audio is
     * what makes it reachable — and it gains the assertion the old shape could not make: the
     * handover exists exactly once, and the media callback still reaches it.
     */
    @Test
    fun a_session_that_starts_while_media_plays_still_hands_the_mic_over_to_the_stream() {
        val body = memberBody(service, "    private fun handOverMicToDeviceAudio() {")
        assertEquals(
            "the handover to PLAYBACK is intact",
            1,
            liveLines(body, "switchSource(to = com.whispereverywhere.audio.ActiveSource.PLAYBACK)").size,
        )
        assertEquals(
            "and it is the service's only one",
            1,
            liveLines(service, "switchSource(to = com.whispereverywhere.audio.ActiveSource.PLAYBACK)").size,
        )
        val callback = memberBody(
            service,
            "    override fun onMediaPlaybackStarted(packageName: String, title: String?) {",
        )
        assertEquals(
            "playback beginning during a live session still triggers it",
            1,
            liveLines(callback, "handOverMicToDeviceAudio()").size,
        )
    }

    /**
     * S2 ROUND 1, B3 — THE HANDOVER MUST SURVIVE THE CONNECT WINDOW, WHICH IS S2's OWN CREATION.
     *
     * Before S2 the source decision ran inside `onOpen` (after the model load), so a video started
     * during the load was seen by `AudioSourcePolicy.decide(mediaPlaying = …)` itself and the
     * session opened on device audio. S2 samples that decision at the TAP, which leaves
     * `onMediaPlaybackStarted` as the only compensating trigger — and its gate is `RECORDING`,
     * false for the entire CONNECTING window (4,107 ms on npu-turbo cold, up to 11,672 ms for the
     * first ggml load in a process). `MediaSessionDetector.handlePlaybackStateChanged` is
     * EDGE-triggered (`if (!isMediaPlaying || currentMediaPackage != packageName)`), so a
     * notification the gate drops is the ONLY one that app sends while it keeps playing: the
     * session would spend its whole life on the microphone, recording the room and the speaker
     * bleed instead of the stream, with nothing in the log to say why.
     *
     * So the missed edge is latched and re-offered at readiness. Pinned because every part of it is
     * silent when it breaks: a deleted latch, a latch never consumed, a latch consumed where the
     * state gate still refuses it, or a latch that survives into the next session.
     */
    @Test
    fun a_handover_missed_during_the_connect_window_is_latched_and_re_offered_at_readiness() {
        val callback = memberBody(
            service,
            "    override fun onMediaPlaybackStarted(packageName: String, title: String?) {",
        )
        assertEquals(
            "the CONNECTING window latches instead of dropping the edge",
            1,
            liveLines(callback, "pendingDeviceAudioHandover = true").size,
        )
        assertTrue(
            "and it latches on CONNECTING, the one state where the mic is open and the gate shut",
            callback.contains("currentState == BubbleState.CONNECTING"),
        )
        // Consumed in onOpen's Main body, BELOW the RECORDING flip — the handover's own gate is
        // RECORDING, so consuming it any earlier would be a no-op that silently swallows the edge
        // a second time.
        val recording = service.indexOf("                    updateBubbleState(BubbleState.RECORDING)")
        val consume = service.indexOf("                    if (pendingDeviceAudioHandover) {")
        val handover = service.indexOf("                            handOverMicToDeviceAudio()")
        assertTrue("onOpen must flip to RECORDING", recording >= 0)
        assertTrue("onOpen must consume the latch", consume > recording)
        assertTrue("...by calling the one handover", handover > consume)
        // The level is re-read: a video that started and stopped during the load takes nothing.
        assertTrue(
            "the playing level is re-read at readiness, not trusted from the latch",
            service.substring(consume, handover).contains("mediaDetector.isCurrentlyPlaying()"),
        )
        // The field's own initialiser plus exactly two writers of false — session open, and the
        // consumption — so a latch can never outlive the session that set it, and can never fire
        // twice.
        assertEquals(3, liveLines(service, "pendingDeviceAudioHandover = false").size)
        // The session-open write is in startRecording's own body, ABOVE connect(). Scoping to the
        // member alone is not enough: the engine listener is an ANONYMOUS OBJECT declared inside
        // startRecording, so onOpen's body — and the consumption in it — belongs to startRecording
        // by indentation.
        val start = memberBody(service, "    private fun startRecording() {")
        val connect = start.indexOf("        engine.connect(lang, object : TranscriptionEngine.Listener {")
        assertTrue("startRecording must still call connect", connect > 0)
        assertEquals(
            "a new session opens owing no handover",
            1,
            liveLines(start.substring(0, connect), "pendingDeviceAudioHandover = false").size,
        )
        // Two triggers, one handover: the media callback and onOpen, and nothing else.
        assertEquals(
            "the handover has exactly two callers plus its declaration — found: " +
                liveLines(service, "handOverMicToDeviceAudio()"),
            3,
            liveLines(service, "handOverMicToDeviceAudio()").size,
        )
    }

    /**
     * S2 ROUND 2, B4 — THE ANSWER TO AN ASYNCHRONOUS SOURCE QUESTION MUST OUTLIVE `CONNECTING`,
     * AND THIS IS THE THIRD GATE S2's HOIST INVALIDATED.
     *
     * `startAudioInput()` used to run INSIDE `onOpen`, with `updateBubbleState(BubbleState.RECORDING)`
     * as the next-but-one SYNCHRONOUS statement, so the projection sheet went up and the state
     * became RECORDING inside one Main block. Every callback that asks "is there still a session
     * that wants this?" could therefore write `currentState == BubbleState.RECORDING` and be right.
     * S2 samples the source decision at the TAP, so the sheet now stands over the whole CONNECTING
     * window — 4,107 ms on a cold npu-turbo load, up to 11,672 ms for the first ggml load in a
     * process — while a person answers in 1-3 s, and `MediaProjectionGate.deliverResult` has no
     * queue: it calls the listener on the spot. With a RECORDING test, a GRANT stopped the token it
     * had just been handed (logging a session end that had not happened), a DENY did nothing at all,
     * and the session ran to the user's stop tap with no recorder and no capturer — the "listening"
     * cue firing at a shut microphone, one of two per-session asks spent for nothing.
     *
     * It is the amendment's own S2 acceptance row ("and on device audio: start a video and tap"),
     * and the ONLY door into a FIRST device-audio session: `stopRecording` releases the projection,
     * so `hasProjection()` is false at every tap and `AudioSourcePolicy.decide` can never answer
     * `UsePlayback` there — every device-audio session must pass through `RequestConsent`.
     *
     * Pinned as ONE named predicate with a counted set of call sites, because the three gates this
     * family has already broken (round 1's B1 and B3, and this one) were each silent when they
     * broke and each found by a reviewer rather than by an instrument.
     */
    @Test
    fun the_projection_answer_is_honoured_through_the_connect_window_and_not_only_at_RECORDING() {
        // The predicate names exactly the two states a LIVE session occupies. The states left out
        // are the ones that really are over (IDLE / ERROR / FINALIZING / PROCESSING), which is what
        // keeps "the sharing indicator must not outlive the transcript" true.
        assertTrue(
            "the live-session predicate must exist and name both CONNECTING and RECORDING",
            service.contains(
                "    private fun sessionStillWantsASource(): Boolean =\n" +
                    "        currentState == BubbleState.RECORDING || currentState == BubbleState.CONNECTING"
            ),
        )

        val granted = memberBody(service, "        override fun onConsentGranted(resultCode: Int, data: Intent) {")
        for (stale in listOf("currentState == BubbleState.RECORDING", "currentState != BubbleState.RECORDING")) {
            assertEquals(
                "a grant may not be judged by RECORDING alone — found: " + liveLines(granted, stale),
                emptyList<String>(),
                liveLines(granted, stale),
            )
        }
        assertEquals(
            "all three of the grant's gates ask the one question: the foreground-upgrade fallback, " +
                "the null-projection fallback, and the after-the-session stop",
            3,
            liveLines(granted, "sessionStillWantsASource()").size,
        )
        // A grant a live session wants is KEPT and started; the guard above it is what stops a
        // grant no session wants. Order, so the token can never be stored for a finished session.
        val guard = granted.indexOf("                if (!sessionStillWantsASource()) {")
        val stop = granted.indexOf("                    runCatching { projection.stop() }")
        val store = granted.indexOf("                com.whispereverywhere.audio.MediaProjectionGate.storeProjection(projection)")
        val capture = granted.indexOf("                    startPlaybackSource()")
        assertTrue("the grant must still stop a token no session wants", guard in 0 until stop)
        assertTrue("and store one a live session does want, below that guard", store > stop)
        assertTrue("...then open the capturer", capture > store)

        val denied = memberBody(service, "        override fun onConsentDenied() {")
        for (stale in listOf("currentState == BubbleState.RECORDING", "currentState != BubbleState.RECORDING")) {
            assertEquals(
                "nor may a deny — found: " + liveLines(denied, stale),
                emptyList<String>(),
                liveLines(denied, stale),
            )
        }
        assertEquals(1, liveLines(denied, "sessionStillWantsASource()").size)
        assertEquals(
            "deny/back still falls back to the microphone, which is what the ask's KDoc promises",
            1,
            liveLines(denied, "startMicSource()").size,
        )

        // THE ONE-SHOT WATCHDOG, which becomes reachable before RECORDING the moment a grant can
        // start a capturer during CONNECTING: PlaybackAudioCapturer's `silentFired` fires exactly
        // once, SilentStreamPolicy.SILENT_TIMEOUT_MS after the capturer starts (3,000 ms — inside a
        // 4,107 ms load and far inside an 11,672 ms one). A gate that refuses it there would latch
        // on a refusal that did nothing, and a Netflix or Teams session would capture digital
        // silence for its whole life with no fallback and no toast.
        val playback = memberBody(service, "    private fun startPlaybackSource(): Result<Unit> {")
        assertEquals(
            "the silent-stream gate asks the same question — found: " +
                liveLines(playback, "currentState == BubbleState.RECORDING"),
            emptyList<String>(),
            liveLines(playback, "currentState == BubbleState.RECORDING"),
        )
        assertEquals(1, liveLines(playback, "sessionStillWantsASource()").size)
        assertEquals(1, liveLines(playback, "fallBackFromSilentStreamToMic()").size)

        // ...and the handover it calls has an arm for each state. RECORDING stays switchSource's
        // (engine open: flush the ring and cut the boundary on the old source's side). CONNECTING
        // must NOT go through switchSource, which before readiness would sendAudio into a loading
        // context, commit a segment never fed a byte, and reset an endpointer no frame has reached
        // — the BUFFER route does not drive the probe at all. The captured silence is dropped
        // instead of replayed into the microphone's own segment, where it would have no boundary
        // to sit behind: SilentStreamPolicy's guarantee is that the stream NEVER carried audio.
        val fallback = memberBody(service, "    private fun fallBackFromSilentStreamToMic() {")
        assertEquals(
            "RECORDING is switchSource's case and stays switchSource's",
            1,
            liveLines(fallback, "switchSource(to = com.whispereverywhere.audio.ActiveSource.MIC)").size,
        )
        assertTrue(
            "...gated on RECORDING, so the pre-readiness arm is the other one",
            fallback.contains("        if (currentState == BubbleState.RECORDING) {"),
        )
        assertEquals("the pre-readiness arm stops the silent capturer", 1,
            liveLines(fallback, "stopPlaybackCapturer()").size)
        assertEquals("drops what it captured rather than replaying it", 1,
            liveLines(fallback, "startupRing.clear()").size)
        assertEquals("and opens the microphone", 1, liveLines(fallback, "startMicSource()").size)
        for (beforeReadiness in listOf("sendAudio(", "commitSegment(", "endpointer.reset()", "drainAll")) {
            assertEquals(
                "the pre-readiness arm must not touch the engine or the probe — found: " +
                    liveLines(fallback, beforeReadiness),
                emptyList<String>(),
                liveLines(fallback, beforeReadiness),
            )
        }

        // ONE predicate, a COUNTED set of askers: the declaration plus five call sites (three in
        // the grant, one in the deny, one in the silent-stream watchdog). A sixth is a new gate of
        // this family and should have to say so here.
        assertEquals(
            "the declaration plus exactly five askers — found: " +
                liveLines(service, "sessionStillWantsASource()"),
            6,
            liveLines(service, "sessionStillWantsASource()").size,
        )
    }
}
