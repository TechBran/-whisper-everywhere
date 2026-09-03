package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE FLATLINE CUT's arming rule, pinned on the source (4.4): ARMED IFF THE ACTIVE SOURCE IS
 * CAPTURED PLAYBACK, applied at the ONE place the service changes its source.
 *
 * `FloatingBubbleService` is an Android Service and cannot be instantiated in a JVM test, so the
 * behavioural half of this contract lives in `SileroEndpointerFlatlineTest` (what an armed and a
 * disarmed endpointer do on the same trace) and the STRUCTURAL half lives here — the same
 * instrument, and the same argument, as `CapSeamPinTest` and `EndpointerLifecyclePinTest`. What is
 * pinned is narrow and permanent: the arm is derived from the source at `setActiveSource`, it is
 * derived nowhere else, and the seam and the funnel are untouched — a flat close reaches the engine
 * through the VAD site exactly as a hangover close does.
 *
 * **The source is read LF-NORMALISED.** `core.autocrlf=true` checks this repo out with CRLF, so a
 * needle written with a bare `\n` finds nothing and every assertion would pass or fail for the
 * wrong reason. The normalisation happens once, at the single read site below.
 */
class FlatlineArmPinTest {

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
    fun theTriggerIsArmedFromTheSourceAtTheOneSourceChangeSite() {
        // Route first, arm second: the arm is DERIVED from the source just written, so the two can
        // never disagree, and `activeSource` is the route the very next chunk takes.
        indexOfOrFail(
            "    private fun setActiveSource(source: com.whispereverywhere.audio.ActiveSource) {\n" +
                "        activeSource = source\n" +
                "        val armed = source == com.whispereverywhere.audio.ActiveSource.PLAYBACK\n" +
                "        endpointer.armFlatline(armed)\n"
        )
        assertEquals(
            "the trigger is armed from exactly one place — a second armFlatline site is a second " +
                "opinion about which source is live",
            1,
            count("endpointer.armFlatline("),
        )
        // The rule's one literal: PLAYBACK arms. Not a preference, not a media-detector state, not
        // the presence of a projection — the SOURCE, because that is what decides whether digital
        // silence can reach the endpointer at all.
        assertEquals(1, count("val armed = source == com.whispereverywhere.audio.ActiveSource.PLAYBACK"))
    }

    @Test
    fun theArmIsLoggedOncePerSourceChangeAndCarriesNoTranscript() {
        indexOfOrFail(
            "        android.util.Log.i(\"WE-DIAG\", \"flatline: \${if (armed) \"armed\" else \"disarmed\"} source=\$source\")"
        )
        assertEquals("one flatline: line, at the arm site", 1, count("\"flatline: "))
    }

    @Test
    fun theSeamAndTheFunnelAreUntouched_aFlatCloseIsAVadPathCommit() {
        // A flat close returns true from the SAME onFrame the hangover does and is committed by the
        // SAME site with the SAME bookkeeping; only the endpoint: line's label differs, and that
        // label is derived inside EndpointDiag.endpointLine from the record the funnel already
        // reads once (CommitFunnelPinTest pins the single read). Nothing here may grow a second
        // branch for it.
        indexOfOrFail("            if (endpointer.onFrame(chunk, amp, now)) {")
        indexOfOrFail("                commitSegment(engine, EndpointDiag.VAD, nowMs = now)")
        assertEquals("the seam names no FLAT cut of its own", 0, count("EndpointDiag.FLAT"))
        assertEquals("the funnel's single cut-record read stands", 1, count(".lastCut()"))
    }

    @Test
    fun aSessionOpensBeforeItsSourceIsPicked_soTheArmFollowsOnSessionStart() {
        // onSessionStart opens the session DISARMED; the source pick that follows (startAudioInput
        // -> startMicSource / startPlaybackSource -> setActiveSource) is what arms it, and it runs
        // before the capturer can deliver a frame. Order in source is the half a JVM test can see.
        val cadence = indexOfOrFail("                    endpointer.onSessionStart(")
        val startInput = text.indexOf("                    val started = startAudioInput()", cadence)
        assertTrue("the source is picked AFTER the session opens", startInput > cadence)
        // Both capturer starts route through the arm site.
        val mic = indexOfOrFail("    private fun startMicSource(): Result<Unit> {\n        setActiveSource(com.whispereverywhere.audio.ActiveSource.MIC)")
        val playback = indexOfOrFail("        setActiveSource(com.whispereverywhere.audio.ActiveSource.PLAYBACK)\n        val started = capturer.start(::onAudioChunk)")
        assertTrue(mic > 0 && playback > 0)
        // While a session is live the source is written only through setActiveSource, so the arm
        // cannot be skipped by a raw write. stopRecording's raw `= MIC` is the one documented
        // exception — a session that is ENDING, whose next onSessionStart disarms anyway.
        assertEquals(
            "a raw activeSource write appeared outside stopRecording: it bypasses the arm",
            1,
            count("        activeSource = com.whispereverywhere.audio.ActiveSource.MIC"),
        )
        assertEquals("...and setActiveSource is the only other writer", 1, count("        activeSource = source\n"))
    }
}
