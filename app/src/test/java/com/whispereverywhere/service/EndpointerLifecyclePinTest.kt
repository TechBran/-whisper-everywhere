package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The endpointer's SERVICE-SIDE lifecycle, pinned on the source: every reset site, the per-session
 * cadence handover at `onOpen`, and the probe teardown at `stopRecording`
 * (see `CapSeamPinTest` for why the pin is structural — `FloatingBubbleService` is an Android
 * Service and cannot be instantiated in a JVM test, so the behavioural half of each contract lives
 * in the pure units it calls and the ORDER-IN-SOURCE half lives here).
 *
 * **`onOpen` is a REPLACE, not a JOIN, and that is the one decision this file encodes.** The plan
 * inserted `onSessionStart` BESIDE `onOpen`'s `reset()` and pinned FOUR service-side reset sites.
 * `SileroEndpointer.onSessionStart`'s own KDoc makes it a documented strict superset of `reset()`
 * ("Everything [reset] clears, plus…"), so the pair would have shipped a redundant clear and an
 * ordering question about the doubled `probeReset`. Three documents in the tree already describe
 * the REPLACE — `SileroEndpointer`'s Threading section ("the site that used to be a fourth
 * [reset]"), `SileroEndpointerTest`'s volatility pin ("of which `onOpen`'s becomes
 * `onSessionStart`") and `SileroEndpointerConcurrencyTest`'s class KDoc, which carries the COUNT
 * ("Main mutates the same fields from three sites"). This file is the fourth, and the executable
 * one.
 *
 * **The source is read LF-NORMALISED.** `core.autocrlf=true` checks this repo out with CRLF, so a
 * needle written with a bare `\n` finds nothing and every assertion would pass or fail for the
 * wrong reason. The normalisation happens once, at the single read site below — the same defence
 * `CapSeamPinTest` and `EndpointerFactoryTest`'s source scan use.
 */
class EndpointerLifecyclePinTest {

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
     * THREE service-side resets, and exactly three: the wall-cap cut in `onAudioChunk`,
     * `switchSource`, and `stopRecording`. A fourth reset lives INSIDE the endpointer — the
     * post-commit reset required by `Endpointer.onFrame`'s contract — and is pinned in Workstream C,
     * not here. `onOpen` used to be the fourth service-side site; it is now
     * `endpointer.onSessionStart(...)`, a documented strict superset of `reset()`, so re-adding a
     * reset there would be redundant AND would double the `probeReset` inside one session open.
     *
     * The count is taken on the LF-form `"endpointer.reset()\n"`, deliberately. TWO comments in
     * FloatingBubbleService quote the call verbatim — D9's mandatory read-before-reset note in the
     * cap branch ("…are both read BEFORE endpointer.reset(), which clears them.") and this task's
     * REPLACE note at onOpen ("This REPLACES the endpointer.reset() that used to open…") — and
     * neither copy ends its line, so the BARE needle reads TWO high.
     *
     * That is not an off-by-one worth patching with a bigger constant. The bare count was 5 before
     * this task (4 calls + 1 comment) and is 5 after it (3 calls + 2 comments), so a bare-count
     * census would have stayed GREEN straight through the REPLACE while an entire reset site was
     * deleted — pinning nothing, which is the one thing a census must not do. Fix the TEST, never
     * the comments: they are load-bearing prose, and D9's in particular is what states the
     * read-before-reset ordering the cap branch depends on. Indentation-anchoring does NOT work as
     * an alternative either — that comment shares the cap cut's 16-space indent, and the three real
     * calls sit at 16 / 8 / 8.
     */
    @Test
    fun thereAreExactlyThreeServiceSideResetSites() {
        assertEquals(3, count("endpointer.reset()\n"))
        // onOpen's old 20-space site, superseded by onSessionStart. Nothing may re-add it.
        assertEquals(
            "onOpen's reset is superseded by onSessionStart and must not come back",
            0,
            count("                    endpointer.reset()"),
        )
    }

    @Test
    fun switchSourceResetsBeforeSwappingTheAcousticSource() {
        val commit = indexOfOrFail(
            "        transcriptionEngine?.let { commitSegment(it, EndpointDiag.SWITCH) }\n        endpointer.reset()"
        )
        val stopOld = text.indexOf("            com.whispereverywhere.audio.ActiveSource.MIC -> audioRecorder.stop()", commit)
        assertTrue("the reset must precede the source swap", stopOld > commit)
    }

    @Test
    fun onOpenHandsOverThisSessionsCadenceBeforeTheFirstFrame() {
        val cadence = indexOfOrFail("                    endpointer.onSessionStart(")
        val anchor = indexOfOrFail("                        nowMs = sessionOpenMs,")
        val tier = indexOfOrFail("                            tierId = installedModel?.id,")
        val cloud = indexOfOrFail("                            isCloudBatch = cloudWrapper != null,")
        val startInput = text.indexOf("                    val started = startAudioInput()", cadence)
        assertTrue(cadence < anchor && anchor < tier && tier < cloud)
        assertTrue("the endpointer must be armed BEFORE the first frame can arrive", cloud < startInput)
        assertEquals(1, count("endpointer.onSessionStart("))
        assertEquals(1, count("CommitCadencePolicy.minCommitIntervalMs("))
    }

    @Test
    fun theCloudFirstCapSuppressionIsUntouchedAndStillPrecedesTheCadence() {
        val suppression = indexOfOrFail("                    if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionOpenMs)")
        val cadence = indexOfOrFail("                    endpointer.onSessionStart(")
        assertTrue("the 4 s cloud suppression must stay where it is", suppression < cadence)
        assertEquals(1, count("if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionOpenMs)"))
    }

    @Test
    fun stopRecordingFlushesUnconditionallyThenResetsThenFreesTheProbe() {
        val flush = indexOfOrFail(
            "        transcriptionEngine?.let { commitSegment(it, EndpointDiag.STOP) }\n        android.util.Log.i("
        )
        val reset = text.indexOf("        endpointer.reset()", flush)
        val end = text.indexOf("        endpointer.onSessionEnd()", reset)
        assertTrue("the flush stays unconditional and first", reset > flush)
        assertTrue("the probe is freed after the reset", end > reset)
        assertEquals(1, count("endpointer.onSessionEnd()"))
    }

    @Test
    fun theProbeIsFreedOnlyAfterBothCaptureSourcesHaveJoined() {
        // SCOPED TO stopRecording, deliberately. Both capture-stop anchors also occur, at the same
        // 8-space indentation, inside onDestroy far above. An unscoped indexOf() would therefore
        // compare onDestroy's offsets against stopRecording's teardown and pass unconditionally —
        // pinning nothing, while the hazard it claims to pin (vadProbeFree running with a frame
        // still inside vadProbeFrame) stayed wide open. Symbols, not line numbers: this file's
        // offsets have already moved three times inside Workstream D.
        //
        // What this pins is ORDER IN SOURCE. It does NOT claim the join GUARANTEES exclusion —
        // T2-SHARPENED: Thread.join(ms) returns identically on termination and on timeout, so the
        // safety of a late free is the bound lambda's obligation (VadProbeLifecycle), not this
        // ordering's.
        val stopFn = indexOfOrFail("    private fun stopRecording() {")
        val recorderStop = text.indexOf("        audioRecorder.stop()", stopFn)
        val playbackStop = text.indexOf("        stopPlaybackCapturer()", stopFn)
        val end = text.indexOf("        endpointer.onSessionEnd()", stopFn)
        assertTrue("stopRecording must stop the mic recorder", recorderStop >= 0)
        assertTrue("stopRecording must stop the playback capturer", playbackStop >= 0)
        assertTrue("stopRecording must end the endpointer session", end >= 0)
        assertTrue(recorderStop < end)
        assertTrue(playbackStop < end)
    }

    @Test
    fun theOldSegmenterFieldIsGoneEverywhere() {
        assertEquals(0, count("speechSegmenter"))
        assertEquals(0, count("import com.whispereverywhere.util.SpeechSegmenter"))
    }
}
