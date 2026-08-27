package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UNTOUCHABLE #1, pinned structurally (3.7): the wall caps live in the `else if`, OUTSIDE the
 * verdict, so an endpointer that never fires leaves cap behaviour byte-identical to 3.6.0.
 *
 * FloatingBubbleService is an Android Service and cannot be instantiated in a JVM test, so the
 * behavioural half of this contract is pinned by the pure units the seam calls
 * (CapCutBookkeepingTest, CommitCadencePolicyTest, LocalWhisperEngineCapSplitTest,
 * AmplitudeEndpointerTest) and the STRUCTURAL half is pinned here, on the source itself. A
 * refactor that nests the cap check inside the verdict, or that gates `sendAudio`, fails this
 * test loudly instead of shipping.
 *
 * **The needles are EXACT-MATCH, and that is the instrument, not an accident.** Two of the seam's
 * calls take two same-typed parameters — `capCutConsumesWindow(hasPendingSpeech, isCloudSession)`
 * (two `Boolean`s) and `CommitCadencePolicy.capCutRetainMs(nowMs, cutPointMs)` (two `Long`s) — and
 * a swapped `capCutRetainMs` returns `0L` for EVERY input, which silently reverts the cap-cut
 * split to 3.6.0 with the whole suite green (D2's M22 swapped them and survived). Both sites
 * therefore ship with NAMED arguments, and the needles quote those names. A positional swap of
 * named arguments is inert by construction; a mis-bound *value* is not, and the exact-match needle
 * is what catches it as a textual change.
 *
 * **The source is read LF-NORMALISED.** `core.autocrlf=true` on this repo checks the file out with
 * CRLF, so a needle written with bare `\n` finds nothing on a fresh clone and every assertion here
 * would pass or fail for the wrong reason. The normalisation happens once, at the single read site
 * below (the N1/N2 lesson, and the same defence `EndpointerFactoryTest`'s source scan uses).
 */
class CapSeamPinTest {

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

    private fun indexOfOrFail(needle: String): Int {
        val i = text.indexOf(needle)
        assertTrue("missing from FloatingBubbleService.kt: <<$needle>>", i >= 0)
        return i
    }

    @Test
    fun sendAudioIsUnconditionalAndFirst() {
        val send = indexOfOrFail("        engine.sendAudio(chunk)\n")
        val gate = indexOfOrFail("LiveTurnPolicy.runClientVad(sessionIsLive)")
        assertTrue("sendAudio must precede the client-VAD gate", send < gate)
        assertEquals("sendAudio must appear exactly once in onAudioChunk", 1, text.split("engine.sendAudio(chunk)").size - 1)
    }

    @Test
    fun theEndpointerIsTheVerdictInsideTheIf() {
        indexOfOrFail("            if (endpointer.onFrame(chunk, amp, now)) {")
    }

    @Test
    fun theWallCapIsTheElseIfAndThereIsExactlyOneOfThem() {
        indexOfOrFail("            } else if (segmentCapPolicy.capExceeded(now)) {")
        assertEquals(
            "the cap check must exist exactly once, and as the else-if",
            1,
            text.split("segmentCapPolicy.capExceeded(").size - 1,
        )
    }

    @Test
    fun theCapBranchKeepsItsBookkeepingAndItsUnconditionalCommit() {
        val cap = indexOfOrFail("            } else if (segmentCapPolicy.capExceeded(now)) {")
        val bookkeeping = indexOfOrFail(
            "                if (capCutConsumesWindow(\n" +
                "                        hasPendingSpeech = endpointer.hasPendingSpeech() ||\n" +
                "                            (endpointer as? SileroEndpointer)?.isProbeCutout() == true,\n" +
                "                        isCloudSession = cloudWrapper != null,\n" +
                "                    )\n" +
                "                ) {"
        )
        val commit = indexOfOrFail("                commitSegment(engine, EndpointDiag.CAP, retainMs = retainMs, nowMs = now)")
        val reset = text.indexOf("                endpointer.reset()", commit)
        assertTrue("bookkeeping stays inside the cap branch", bookkeeping > cap)
        assertTrue("the commit follows the bookkeeping", commit > bookkeeping)
        assertTrue("the endpointer is reset after the cap commit", reset > commit)
    }

    @Test
    fun theCapCutAsksTheCadencePolicyForItsRetainWindow() {
        indexOfOrFail(
            "                val retainMs = CommitCadencePolicy.capCutRetainMs(nowMs = now, cutPointMs = endpointer.pendingCutPointMs())"
        )
    }

    @Test
    fun theAmplitudeSegmenterIsNoLongerCalledDirectly() {
        assertEquals(
            "the service must reach the segmenter only through AmplitudeEndpointer",
            0,
            text.split("speechSegmenter").size - 1,
        )
    }

    @Test
    fun theEndpointerIsChosenExactlyOnce() {
        assertEquals(1, text.split("EndpointerFactory.create(").size - 1)
    }
}
