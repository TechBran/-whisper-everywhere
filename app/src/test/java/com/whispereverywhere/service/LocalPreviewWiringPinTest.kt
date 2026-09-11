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
 * capture callback, the commit funnel, the stop path — reads that field); the gate is handed the
 * user's SELECTION and never whisper's `.en` pin of it (4.4.1, owner ruling 1); the session flag is
 * assigned the GATE's answer and never a constant; the resident previewer is released on trim and
 * on destroy and nowhere else; it is warmed beside the local prewarm, and for the SELECTED
 * language's pack or not at all (4.4.1, CHANGE 5); the arbiter counts
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
        assertTrue("whisper's language resolves above the gate", lang < gate)
        assertTrue("the flag is set from the gate", gate < flag)
        assertTrue("the tee is built after the flag", flag < wrap)
        assertTrue("and transcriptionEngine is re-pointed at it, so the capture callback, the funnel and the stop path all see the tee", wrap < repoint)
        assertTrue("connect runs on the wrapped engine", repoint < connect)
        assertEquals("ONE wrap site", 1, count(text, "PreviewTeeEngine(requireNotNull(preview), baseEngine)"))
        assertEquals("ONE gate call", 1, count(text, "= localPreviewArms(\n"))
        assertEquals("the base engine is resolved exactly as before, under a new name", 1, count(text, "val baseEngine: TranscriptionEngine = resolveTranscriptionEngine()"))
    }

    @Test
    fun theGateReadsTheUsersSELECTIONAndNeverWhispersEnglishPin() {
        // (4.4.1, owner ruling 1 — the fix for review B1.) `sessionLanguageFor` pins an
        // ENGLISH-scope tier to "en" whatever the user picked, so feeding the gate `lang` armed
        // live words for every Auto user on eco/pro — while the card, the picker, Settings and
        // onboarding all say Auto shows none at all, and while the acquisition side (which reads
        // the selection) never fetches that pack for them. ONE read of the pick, spent twice:
        // resolved for whisper, raw for the previewer.
        assertEquals(
            "the pick is read once, in this function",
            1,
            count(startRecording, "        val selection = app.preferencesManager.getLanguageForApi()\n"),
        )
        assertEquals(
            "read in exactly three places in the service: here, the boot warm's own lookup " +
                "(CHANGE 5), and the release-on-selection-change collector's (pass 3, ITEM 2)",
            3,
            count(text, "app.preferencesManager.getLanguageForApi()"),
        )
        assertEquals(
            "whisper resolves from that same read",
            1,
            count(startRecording, "            selection = selection,\n"),
        )
        assertEquals("the previewer's language IS the pick", 1, count(startRecording, "        val previewLanguage = selection\n"))
        assertEquals(
            "the gate's language argument is that pick",
            1,
            count(startRecording, "            sessionLanguage = previewLanguage,\n"),
        )
        assertEquals("never whisper's resolved pin", 0, count(text, "sessionLanguage = lang"))
        assertEquals(
            "and the gate line logs the gate's own input, so a refusal is one grep",
            1,
            count(startRecording, "                previewLanguage, packInstalled,"),
        )
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
    fun theResidentPreviewerIsReleasedOnTrimOnDestroyAndOnASelectionWithNoPack_andNowhereElse() {
        // The service OWNS the previewer (spec §4.1 step 10, §7.4): the tee borrows it per session.
        val trimLocal = indexOfOrFail(onTrim, "localEngine?.releaseContext()")
        val trimPreview = indexOfOrFail(onTrim, "streamingPreview?.release()")
        assertTrue("released under the same three-state guard, after the local context", trimLocal < trimPreview)
        indexOfOrFail(onDestroy, "        streamingPreview?.release()\n        streamingPreview = null\n")
        assertEquals(
            "THREE release sites: trim, destroy, and the selection moving away from the resident " +
                "pack's language (4.4.1 pass 3, ITEM 2). A fourth is a recognizer freed under a " +
                "session that borrowed it",
            3, count(text, "streamingPreview?.release()"),
        )
    }

    @Test
    fun theResidentPreviewerIsHandedBackWhenTheSelectionMovesAwayFromItsPack() {
        // (4.4.1 pass 3, ITEM 2 — review r2's nit 2.) CHANGE 5 stopped the boot WARM for a user
        // on Auto, but nothing released a RESIDENT engine when the selection moved away from an
        // installed pack language: a user who dictated in English and then switched to Auto kept
        // the recognizer and its +169 MB until onTrimMemory or onDestroy. One site, one
        // condition, and the condition is the warm gate's own answer — LocalPreviewGateTest
        // holds the two together, so this pins only the wiring.
        val collector = indexOfOrFail(
            text,
            "            app.preferencesManager.selectedLanguage.drop(1).collect {\n",
        )
        assertEquals(
            "ONE collector on the selection in the service, and it is this one",
            1, count(text, "app.preferencesManager.selectedLanguage"),
        )
        assertEquals(
            "drop(1): the prewarm above has just asked the same question of the value already " +
                "in place and warmed nothing for it",
            1, count(text, "selectedLanguage.drop(1)"),
        )
        val skip =
            "                if (currentState != BubbleState.IDLE && currentState != BubbleState.ERROR) return@collect\n"
        val resident = "                if (streamingPreview == null) return@collect\n"
        // (fix round 1, H-B1) The skip is read TWICE, and the second read is the one that makes
        // the release safe: the first was taken above `withContext`, i.e. before the collector's
        // only suspension point, and across that window a session can have started and BORROWED
        // this recognizer. Same defect, same fix, as the model-switch collector's "THE GATE,
        // RE-READ BELOW THE SUSPENSION" 50 lines below.
        assertEquals("the mid-session skip is read twice, not once", 2, count(text, skip))
        assertEquals("and so is the resident check it travels with", 2, count(text, resident))
        val guard = indexOfOrFail(text, skip)
        val condition = indexOfOrFail(text, "                val keep = withContext(Dispatchers.IO) {\n")
        val reRead = text.indexOf(skip, condition)
        val reReadResident = text.indexOf(resident, condition)
        val keepCheck = indexOfOrFail(text, "                if (keep != null) return@collect\n")
        val release = text.indexOf("                streamingPreview?.release()\n")
        assertTrue("the mid-session skip comes FIRST: the tee BORROWS this recognizer", collector < guard)
        assertTrue("then the condition", guard < condition)
        assertTrue("the skip is RE-READ below the suspension", condition < reRead)
        assertTrue("with the resident check", reRead < reReadResident)
        assertTrue(
            "and nothing between that re-read and the release suspends — the keep test is " +
                "already in hand, Log.i and release() are ordinary calls",
            reReadResident < keepCheck && keepCheck in 0 until release,
        )
        assertEquals(
            "the condition is previewPackToWarm's own answer, never a second language " +
                "comparison — a release that disagreed with the warm would thrash the load",
            1, count(text, "                if (keep != null) return@collect\n"),
        )
        assertEquals(
            "the census is off Main: installedLanguages() is a marker read plus four byte " +
                "counts per catalogue row",
            1, count(text, "val keep = withContext(Dispatchers.IO) {"),
        )
        assertEquals(
            "and `streamingPreviewPack` is deliberately NOT cleared — that is the onTrimMemory " +
                "shape, so re-picking the language reloads through warmStreamingPreview's `==` " +
                "branch instead of releasing an already-released engine first",
            1, count(text, "streamingPreviewPack = "),
        )
        assertEquals(
            "the pick is read through the one seam, now in three places: the wrap site, the boot " +
                "warm's lookup, and this collector's",
            3,
            count(text, "app.preferencesManager.getLanguageForApi()"),
        )
    }

    @Test
    fun thePreviewerIsWarmedBesideTheLocalPrewarm() {
        // Off the session's critical path: the ~0.8 s load + the canary run in the same delayed
        // coroutine as the whisper prewarm, for the pack previewPackToWarm names and no other.
        val prewarm = indexOfOrFail(text, "            warmLocalEngine().prewarm()\n")
        val ours = indexOfOrFail(text, "            previewPackToWarm(\n")
        assertTrue("directly beside the local prewarm", ours > prewarm && ours - prewarm < 700)
        assertEquals(1, count(text, "    private fun warmStreamingPreview(\n"))
    }

    @Test
    fun bothWarmSitesTakeTheSameLanguageLookupAsTheArmSite() {
        // (4.4.1, CONTROLLER RULING CHANGE 5 — the fix for review B2.) Neither warm site may ask
        // "is the ENGLISH pack on disk" any more: both go through previewPackToWarm, which
        // LocalPreviewGateTest pins EQUAL to the gate's own language terms, and
        // warmStreamingPreview cannot be called without a pack because it takes one. What this
        // buys is measured: 802-860 ms and +169 MB RSS never spent on a recognizer the gate
        // refuses on its first conjunct.
        assertEquals("the decision is declared once, beside the gate", 1, count(text, "internal fun previewPackToWarm(\n"))
        // Anchored on the newline, so the indent is EXACT: the release collector (pass 3, ITEM 2)
        // asks the same function from deeper inside a coroutine, and an unanchored needle would
        // count that too and turn this pin into "at least one warm site".
        assertEquals("the boot prewarm asks it", 1, count(text, "\n            previewPackToWarm(\n"))
        assertEquals("and it hands the answer straight over", 1, count(text, "            )?.let { warmStreamingPreview(it) }\n"))
        val wrapSite = indexOfOrFail(startRecording, "        val packToWarm = previewPackToWarm(\n")
        val warmCall = indexOfOrFail(
            startRecording,
            "        val preview = if (packToWarm != null) warmStreamingPreview(packToWarm) else streamingPreview\n",
        )
        assertTrue("the wrap site asks it too, before it warms", wrapSite < warmCall)
        assertEquals("the wrap site's lookup reads the SELECTION and the session's own set", 1, count(startRecording, "            previewLanguage = previewLanguage,\n"))
        assertEquals(
            "one disk read of the installed set, handed to BOTH gates — the warm's and the arm's",
            2,
            count(startRecording, "            installedPackLanguages = installedPreviewLanguages,\n"),
        )
        assertEquals("read once", 1, count(startRecording, "        val installedPreviewLanguages = app.streamingPackManager.installedLanguages()\n"))
        assertEquals("no English literal is left anywhere in the service", 0, count(text, "StreamingPackCatalog.EN"))
        assertEquals("two warm calls, both handed a pack", 2, count(text, "warmStreamingPreview(it)") + count(text, "warmStreamingPreview(packToWarm)"))
    }

    @Test
    fun onePackPerProcess_aLanguageChangeReleasesBeforeItWarms() {
        // CHANGE 5's second bullet, as the stated invariant the multilingual build inherits:
        // StreamingPreviewEngine.warm is idempotent on the ENGINE (`recognizer != null`), not on
        // the pack, so warming a DIFFERENT pack must release first or the old language's model
        // decodes the new language's speech behind a gate that says yes. And the corrupt marker
        // follows the pack whose load FAILED, read from the field — a closure over the pack this
        // call was made with would mark English corrupt for a Spanish failure.
        assertEquals(1, count(text, "if (resident != null && streamingPreviewPack != pack) resident.release()"))
        assertEquals(1, count(text, "        streamingPreviewPack = pack\n"))
        assertEquals(
            1,
            count(text, "onLoadFailure = { streamingPreviewPack?.let { failed -> app.streamingPackManager.markCorrupt(failed) } },"),
        )
        assertEquals("and the field is written in exactly that one place", 1, count(text, "streamingPreviewPack = "))
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
