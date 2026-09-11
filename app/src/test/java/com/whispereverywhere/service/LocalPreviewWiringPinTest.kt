package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE PREVIEWER'S WIRING, pinned structurally (4.4.0 Task 7) — the InFlightStripWiringPinTest
 * idiom: `FloatingBubbleService` cannot be instantiated on the JVM, so the CALLS are pinned as
 * LF-normalised, symbol-scoped needles inside their declaring bodies; never line numbers.
 *
 * What this class holds: the tee is constructed at ONE site, AFTER the session language resolves
 * and BEFORE `connect`, and `transcriptionEngine` is re-pointed at it (every later reader — the
 * capture callback, the commit funnel, the stop path — reads that field); the session flag is
 * assigned the GATE's answer and never a constant; the resident previewer is released on trim and
 * on destroy and nowhere else; it is warmed beside the local prewarm; the arbiter counts
 * CONNECTING as capturing; and the service never imports the AAR — `SherpaPreviewRecognizer` is
 * the one adapter.
 */
class LocalPreviewWiringPinTest {

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

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun indexOfOrFail(haystack: String, needle: String): Int {
        val i = haystack.indexOf(needle)
        assertTrue("missing from FloatingBubbleService.kt: <<$needle>>", i >= 0)
        return i
    }

    private fun body(declaration: String, closer: String): String {
        val start = indexOfOrFail(text, declaration)
        val close = text.indexOf(closer, start)
        assertTrue("the closing brace of <<$declaration>> moved", close > start)
        return text.substring(start, close + closer.length)
    }

    private val startRecording: String by lazy { body("    private fun startRecording() {", "\n    }\n") }
    private val onTrim: String by lazy { body("    override fun onTrimMemory(level: Int) {", "\n    }\n") }
    private val onDestroy: String by lazy { body("    override fun onDestroy() {", "\n    }\n") }

    @Test
    fun theTeeIsBuiltAtOneSiteAfterTheLanguageResolvesAndBeforeConnect() {
        val lang = indexOfOrFail(startRecording, "        val lang = sessionLanguageFor(\n")
        val gate = indexOfOrFail(startRecording, "        val previewArmed = localPreviewArms(\n")
        val flag = indexOfOrFail(startRecording, "        sessionHasLocalPreview = previewArmed\n")
        val wrap = indexOfOrFail(startRecording, "PreviewTeeEngine(requireNotNull(preview), baseEngine)")
        val repoint = indexOfOrFail(startRecording, ".also { transcriptionEngine = it }")
        val connect = indexOfOrFail(startRecording, "        engine.connect(lang, object : TranscriptionEngine.Listener {")
        assertTrue("the gate reads the RESOLVED language", lang < gate)
        assertTrue("the flag is set from the gate", gate < flag)
        assertTrue("the tee is built after the flag", flag < wrap)
        assertTrue("and transcriptionEngine is re-pointed at it, so the capture callback, the funnel and the stop path all see the tee", wrap < repoint)
        assertTrue("connect runs on the wrapped engine", repoint < connect)
        assertEquals("ONE wrap site", 1, count(text, "PreviewTeeEngine(requireNotNull(preview), baseEngine)"))
        assertEquals("ONE gate call", 1, count(text, "= localPreviewArms(\n"))
        assertEquals("the base engine is resolved exactly as before, under a new name", 1, count(text, "val baseEngine: TranscriptionEngine = resolveTranscriptionEngine()"))
    }

    @Test
    fun theGateReadsTheSessionKindFromTheWrapperAndTheBatchControllerAndLogsItsInputs() {
        // `cloudWrapper != null` IS the right predicate HERE (unlike the strip render): a cloud
        // batch or live session must keep today's strip, and cloudWrapper is non-null for exactly
        // those. The gate line is logged AFTER the gate and BEFORE the flag, with the same inputs.
        //
        // The first needle spans the PAIR on purpose. `            isCloudSession = cloudWrapper
        // != null,` alone occurs TWICE in this function at this indent — connectingStatusLabel's
        // own argument sits a few lines above the gate — so a count of the single line pins
        // nothing. The pair occurs only inside localPreviewArms(...).
        assertEquals(
            1,
            count(
                startRecording,
                "            isCloudSession = cloudWrapper != null,\n" +
                    "            batchJobActive = BatchJobController.active != null,\n",
            ),
        )
        assertEquals(1, count(startRecording, "            batchJobActive = BatchJobController.active != null,\n"))
        val gate = indexOfOrFail(startRecording, "        val previewArmed = localPreviewArms(\n")
        val line = indexOfOrFail(startRecording, "StreamDiag.gateLine(")
        val flag = indexOfOrFail(startRecording, "        sessionHasLocalPreview = previewArmed\n")
        assertTrue(gate < line && line < flag)
        assertEquals(1, count(text, "StreamDiag.gateLine("))
    }

    @Test
    fun theFlagIsAssignedTheGatesAnswerAndNeverAConstant() {
        assertEquals(1, count(text, "sessionHasLocalPreview = previewArmed"))
        assertEquals("never a literal true", 0, count(text, "sessionHasLocalPreview = true"))
        assertEquals("the one reset (Task 1's), 8-space indented — the declaration's `= false` is not this", 1, count(text, "        sessionHasLocalPreview = false\n"))
    }

    @Test
    fun theResidentPreviewerIsReleasedOnTrimAndOnDestroyAndNowhereElse() {
        // The service OWNS the previewer (spec §4.1 step 10, §7.4): the tee borrows it per session.
        val trimLocal = indexOfOrFail(onTrim, "localEngine?.releaseContext()")
        val trimPreview = indexOfOrFail(onTrim, "streamingPreview?.release()")
        assertTrue("released under the same three-state guard, after the local context", trimLocal < trimPreview)
        indexOfOrFail(onDestroy, "        streamingPreview?.release()\n        streamingPreview = null\n")
        assertEquals("two release sites: trim and destroy", 2, count(text, "streamingPreview?.release()"))
    }

    @Test
    fun thePreviewerIsWarmedBesideTheLocalPrewarm() {
        // Off the session's critical path: the ~0.8 s load + the canary run in the same delayed
        // coroutine as the whisper prewarm, gated on the switch (R3) — the pack check is inside.
        val prewarm = indexOfOrFail(text, "            warmLocalEngine().prewarm()\n")
        val ours = indexOfOrFail(text, "            if (app.preferencesManager.localPreviewEnabled) warmStreamingPreview()\n")
        assertTrue("directly beside the local prewarm", ours > prewarm && ours - prewarm < 400)
        assertEquals(1, count(text, "    private fun warmStreamingPreview(): "))
    }

    @Test
    fun theArbiterCountsConnectingAsCapturing() {
        // Research §3.9 / spec §7.1: `requestCapture()` runs while CONNECTING, but `isCapturing` used
        // to answer RECORDING || FINALIZING only, so a read-aloud requested during CONNECTING started
        // Kokoro at 4 threads beside the session. With a second CPU consumer that overlap is worse.
        val start = indexOfOrFail(text, "com.whispereverywhere.audio.AudioArbiter.isCapturing = {\n")
        val end = text.indexOf("\n        }\n", start)
        assertTrue(end > start)
        val lambda = text.substring(start, end)
        listOf("BubbleState.RECORDING", "BubbleState.FINALIZING", "BubbleState.CONNECTING").forEach {
            assertTrue("isCapturing names $it", lambda.contains("currentState == $it"))
        }
    }

    @Test
    fun theServiceNeverImportsTheAarDirectly() {
        // SherpaPreviewRecognizer is the ONE adapter; everything else sees the seam.
        assertEquals(0, count(text, "com.k2fsa"))
        assertEquals(1, count(text, "SherpaPreviewRecognizerFactory()"))
    }
}
