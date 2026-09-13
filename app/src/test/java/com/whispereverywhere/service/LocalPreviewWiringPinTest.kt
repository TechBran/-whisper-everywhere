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
 *
 * **And since 4.5.0 T4 fix round 1: a MODELLESS session dies at connect, which is the fact the
 * whole device axis's copy rests on.** The gate has no tier term and arms on such a phone when no
 * provider is configured, so *"live words cannot appear here"* is true because the local engine
 * refuses at connect and the service treats that as fatal — not because the session is a cloud
 * one. Both halves are pinned below, over `LocalWhisperEngine.kt` as well as this service.
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

    private val engineText: String by lazy {
        source("src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    private val startRecording: String by lazy { body("    private fun startRecording() {", "\n    }\n") }
    private val onTrim: String by lazy { body("    override fun onTrimMemory(level: Int) {", "\n    }\n") }
    private val onDestroy: String by lazy { body("    override fun onDestroy() {", "\n    }\n") }

    /**
     * WHAT MAKES THE DEVICE AXIS'S SENTENCES TRUE (4.5.0 T4 fix round 1, review r1's B1) — and it
     * is NOT this gate.
     *
     * `NO_TIER_SUBTITLE`, `deleteSubtitle(OFF_TIER, …)` and `cardWorking(lang, false)` all say
     * that live words cannot appear on a device with no speech model. `localPreviewArms` has no
     * tier term, and on such a device with no provider configured it ARMS
     * ([LocalPreviewGateTest.theGateItselfArmsWithNoTierAndNoProviderConfigured]). The reason no
     * word is rendered is the two lines pinned here: the local engine refuses at CONNECT, and the
     * service treats a connect-time error as FATAL because the bubble is not RECORDING yet
     * (RECORDING is set only from `onOpen`).
     *
     * The previewer is a second engine beside whisper and needs no tier of its own, so a change
     * that let a modelless session survive to RECORDING would put real words on the bubble and
     * make all three of those sentences false. That change would be invisible to every other test
     * in this repo — which is what this pin is for.
     */
    @Test
    fun aModellessSessionDiesAtConnectAndThatIsWhatMakesTheseSentencesTrue() {
        // 1. No model on disk => the local engine answers onError and returns, before any load.
        val path = indexOfOrFail(engineText, "val modelPath = modelPathProvider.installedModelPath()")
        val refusal = engineText.indexOf("listener.onError(\"No speech model installed\")")
        assertTrue(
            "LocalWhisperEngine must still refuse a null model path at connect",
            refusal > path,
        )
        assertTrue(
            "and the refusal must be guarded by the null path",
            engineText.indexOf("if (modelPath == null) {", path) in (path + 1) until refusal,
        )

        // 2. The service treats that error as FATAL, because the bubble is still CONNECTING: the
        //    "keep recording" arm is guarded on RECORDING, which only onOpen sets.
        val onError = indexOfOrFail(startRecording, "override fun onError(message: String) {")
        val keepRecording =
            indexOfOrFail(startRecording, "if (currentState == BubbleState.RECORDING) {")
        val teardown = startRecording.indexOf("teardownRealtime()", keepRecording)
        assertTrue("the mid-session arm must still be RECORDING-only", keepRecording > onError)
        assertTrue("and the fatal arm must still tear the session down", teardown > keepRecording)
        assertEquals(
            "RECORDING is entered at exactly one site, and it is inside onOpen",
            1,
            count(startRecording, "updateBubbleState(BubbleState.RECORDING)"),
        )
        val onOpen = indexOfOrFail(startRecording, "override fun onOpen() {")
        assertTrue(
            "so a session that never opens is never RECORDING, and its connect error is fatal",
            startRecording.indexOf("updateBubbleState(BubbleState.RECORDING)") > onOpen,
        )
    }

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
            "read in exactly four places in the service: here, the boot warm's own lookup " +
                "(CHANGE 5), the ONE body every event-shaped member of the set runs (4.5.1 pass " +
                "2), and the warm-on-install collector's (4.5.1 Task 1) — every one of them the " +
                "same seam, because a previewer decision taken on any other reading of the pick " +
                "is the defect owner ruling 1 retired",
            4,
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
        assertEquals(
            "TWO at this indent since 4.5.1 pass 2: the gate's own, and SESSION_START's — the " +
                "wrap site is a member of the event set now and passes the set's terms, even " +
                "though that member's arm is exempt from the refusal (it warms for the NEXT " +
                "session, which is the whole reason it runs during this one)",
            2, count(startRecording, "            batchJobActive = BatchJobController.active != null,\n"),
        )
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
            "THREE release sites: trim, destroy, and the ONE body the event set's members run — " +
                "which takes it on SELECTION_CHANGED's null answer, i.e. the selection moving " +
                "away from the resident pack's language (4.4.1 pass 3, ITEM 2). A fourth is a " +
                "recognizer freed under a session that borrowed it",
            3, count(text, "streamingPreview?.release()"),
        )
    }

    /**
     * **RE-SPECIFIED IN 4.5.1 PASS 2 (ITEM 1), AND WHAT IT PROTECTS IS STILL TRUE.**
     *
     * This pin held the release-on-selection-change collector's exact source shape — the
     * mid-session skip read twice, the engine-exists check beside it, the census off Main, the
     * release taken on `previewPackToWarm`'s own null answer, and nothing suspending between the
     * re-read and the release. The *ordering* was earned over three review rounds (pass 3 fix
     * round 1, H-B1) and none of it is given up here; what changed is that those five properties
     * now live in the ONE body every event-shaped member of the set runs
     * ([askPreviewResidency]) instead of in this collector's own inlined copy, because the
     * collector had to grow a second arm (warm on a non-null answer) and a fourth copy of the
     * ordering argument is how three moments came to disagree in the first place.
     *
     * Two assertions were deliberately retired rather than moved:
     *
     *  - `if (keep != null) return@collect` — the FALL-THROUGH, which was ITEM 1's defect. The
     *    property it stood for (*the condition is `previewPackToWarm`'s own answer, never a second
     *    language comparison*) is asserted below in its new form: the body hands that answer to
     *    `previewResidency` as `packToWarm` and compares no languages of its own.
     *  - `if (streamingPreview == null) return@collect`, read twice. Correct for a release and
     *    WRONG for a warm: `warmStreamingPreview` BUILDS the engine when the field is null, which
     *    is exactly the state a user who has only ever been on Auto is in. A release of a null
     *    field is a no-op, so nothing it protected is lost.
     */
    @Test
    fun theResidentPreviewerIsHandedBackWhenTheSelectionMovesAwayFromItsPack() {
        val ask = indexOfOrFail(text, "    private suspend fun askPreviewResidency(event: PreviewResidencyEvent) {\n")
        assertEquals(
            "ONE body for every event-shaped member: the release and the warm can no longer be " +
                "argued differently at two sites",
            1, count(text, "private suspend fun askPreviewResidency("),
        )
        // THE CENSUS IS OFF MAIN — installedLanguages() is a marker read plus four exact byte
        // counts per catalogue row — and the SELECTION is read INSIDE the same hop, which is the
        // hazard the board collector was carrying separately (pass 2, ITEM 3).
        val census = indexOfOrFail(text, "        val packToWarm = withContext(Dispatchers.IO) {\n")
        // Searched FROM this body: the boot prewarm asks the same question with the same lines at
        // the same indent, so a whole-file `indexOf` would measure SERVICE_START's ordering here.
        val pick = text.indexOf(
            "                previewLanguage = app.preferencesManager.getLanguageForApi(),\n",
            census,
        )
        val decision = text.indexOf("\n        val residency = previewResidency(\n", census)
        assertTrue("the census is the first thing the body does", census > ask && census < decision)
        assertTrue("with the pick inside it", pick > census && pick < decision)
        // (pass 3 fix round 1, H-B1) THE TERMS THAT MOVE ARE READ BELOW THE SUSPENSION: across the
        // census hop a session can have started and BORROWED this recognizer, and a stale body
        // would then free it under that live session — or post a 169 MB load beside it.
        val session = text.indexOf(
            "            sessionActive = currentState != BubbleState.IDLE && currentState != BubbleState.ERROR,\n",
            decision,
        )
        val resident = text.indexOf("            residentWarmPack = residentWarmPreviewPack,\n", decision)
        assertTrue("the session term is read below the hop", session > decision)
        assertTrue("and so is what the engine is warm for", resident > session)
        // ...and nothing between that read and the act suspends: the `when` is ordinary, Log.i is
        // ordinary, and release()/warmStreamingPreview() only post to the engine's own executor.
        val release = text.indexOf("                streamingPreview?.release()\n", decision)
        val warm = text.indexOf("                warmStreamingPreview(residency.pack)\n", decision)
        assertTrue("both arms are inside this body", release > resident && warm > resident)
        assertEquals(
            "the release is the answer's own, never a second language comparison — a release that " +
                "disagreed with the warm would thrash the 802-860 ms load between them",
            1, count(text, "            PreviewResidency.Release -> {\n"),
        )
        assertEquals(
            "and the answer handed in is previewPackToWarm's, so this body decides nothing about " +
                "WHICH pack — twice at this indent, because the wrap site hands its own lookup " +
                "over the same way",
            2, count(text, "            packToWarm = packToWarm,\n"),
        )
        assertEquals(
            "and `streamingPreviewPack` is deliberately NOT cleared — that is the onTrimMemory " +
                "shape, so re-picking the language reloads through warmStreamingPreview's `==` " +
                "branch instead of releasing an already-released engine first",
            1, count(text, "streamingPreviewPack = "),
        )
        assertEquals(
            "the pick is read through the one seam, still in four places: the wrap site, the boot " +
                "warm's lookup, this shared body's, and the warm-on-install collector's",
            4,
            count(text, "app.preferencesManager.getLanguageForApi()"),
        )
    }

    @Test
    fun aFreshlyInstalledPackIsWarmedTheMOMENTTHEINSTALLCOMPLETES() {
        // (4.5.1 Task 1.) THE THIRD WARM TRIGGER, and the only one that is an EVENT. Without it
        // `warmStreamingPreview` is reached only from a boot and from a session START — and the
        // wrap site's own KDoc says it "arms NEXT session, not this one" — so an install completing
        // mid-process armed nothing until a session had been and gone. The owner met that as
        // "having to transcribe a second time to get the live to work".
        val collector = indexOfOrFail(
            text,
            "            com.whispereverywhere.transcription.stream.PreviewWorkboard.work\n",
        )
        assertEquals(
            "ONE collector on the board in the service: a second would be a second warm policy",
            1, count(text, "PreviewWorkboard.work\n"),
        )
        // The FLOW is narrowed before the body runs, and that narrowing carries no rule: the board
        // ticks on every progress callback of a 73-128 MB transfer, and the census in the body is a
        // marker read plus four exact byte counts per catalogue row. Every TERM of the decision is
        // in the pure function.
        val narrow = indexOfOrFail(text, "                .map { board -> board.values.associate { it.language to it.phase } }\n")
        val dedupe = indexOfOrFail(text, "                .distinctUntilChanged()\n")
        assertTrue("phases first", collector < narrow && narrow < dedupe)
        // The REPLAY belongs to the boot prewarm, which does the same work for the same pack
        // through the same function after a deliberate `delay(1500)`. The board is process-scoped
        // and outlives this service, so without this a second service start would pay the
        // 802-860 ms load during view inflation and call it an install event.
        val replay = indexOfOrFail(text, "                .drop(1)\n")
        assertTrue("and the value already in place is dropped, after the de-duplication", replay > dedupe)
        assertEquals(
            "FOUR drops in the service now — the selection, the live-words SWITCH (fix round 1, " +
                "review r1's B1), the model switch, and this one: every collector whose trigger " +
                "is a CHANGE rather than a state, because the state that was already there is the " +
                "boot prewarm's",
            4, count(text, ".drop(1)"),
        )
        // THE SERVICE DECIDES NOTHING: one call to the pure gate, and the warm is its answer.
        val decision = indexOfOrFail(text, "                    val pack = warmOnPackInstalled(\n")
        assertEquals("the decision is declared once, beside its two twins", 1, count(text, "internal fun warmOnPackInstalled(\n"))
        assertEquals("and asked once", 1, count(text, "val pack = warmOnPackInstalled(\n"))
        assertEquals(
            "the record is the SELECTED language's, looked up in the one observable — and looked " +
                "up from the pick read in the SAME hop (4.5.1 pass 2, ITEM 3)",
            1,
            count(text, "                            com.whispereverywhere.transcription.stream.PreviewWorkboard.of(pick),\n"),
        )
        // THE TERMS THAT MOVE ARE READ BELOW THE SUSPENSION — the release collector's own H-B1
        // lesson, for the identical shape: across the census hop a session can have started, and a
        // load posted under it would run beside the recognizer `PreviewTeeEngine` has borrowed.
        val census = indexOfOrFail(
            text,
            "                    val (selection, record, installed) = withContext(Dispatchers.IO) {\n",
        )
        assertTrue("the census is off Main, and above the decision", dedupe < census && census < decision)
        val session = indexOfOrFail(
            text,
            "                        sessionActive = currentState != BubbleState.IDLE && currentState != BubbleState.ERROR,\n",
        )
        assertTrue("the session term is read after the suspension, inside the decision", session > census)
        // The resident term is the ENGINE's answer, never the field alone: after an onTrimMemory
        // that field still names the pack while the recognizer is freed, and refusing on it would
        // leave the previewer cold behind a receipt promising words.
        assertEquals(
            "written in ONE place (4.5.1 pass 2) — every member of the event set reads the same " +
                "getter, so the discipline cannot be kept at one site and lost at the next",
            1,
            count(text, "        get() = streamingPreviewPack?.takeIf { streamingPreview?.isWarmFor(it) == true }\n"),
        )
        assertEquals(
            "and this collector reads it",
            1,
            count(text, "                        residentWarmPack = residentWarmPreviewPack,\n"),
        )
        // ...and the answer goes straight to the EXISTING warm path. No restart of the service:
        // that was the owner's other option and it would tear down the overlay he is looking at.
        val warm = indexOfOrFail(text, "                    warmStreamingPreview(pack)\n")
        assertTrue("the warm is the last thing the collector does", warm > decision)
        assertEquals(
            "FOUR warm calls in the service now, every one of them handed a pack and every one of " +
                "them a member of the event set's answer: this collector's, and the three that " +
                "take the pack out of `previewResidency`'s Warm",
            4,
            count(text, "warmStreamingPreview(pack)\n") +
                count(text, "warmStreamingPreview(residency.pack)"),
        )
        assertEquals(
            "and the pack is armed by WARMING and never by restarting the service — the owner " +
                "offered that as one option and it is the heavier hammer: it would tear down the " +
                "overlay he is looking at. The three stopSelf() calls in this file are the " +
                "permission and shutdown paths that were always there, none of them inside this " +
                "collector",
            0,
            count(text.substring(collector, warm), "stopSelf()"),
        )
    }

    @Test
    fun thePreviewerIsWarmedBesideTheLocalPrewarm() {
        // Off the session's critical path: the ~0.8 s load + the canary run in the same delayed
        // coroutine as the whisper prewarm, for the pack previewPackToWarm names and no other.
        val prewarm = indexOfOrFail(text, "            warmLocalEngine().prewarm()\n")
        val ours = indexOfOrFail(text, "                packToWarm = previewPackToWarm(\n")
        assertTrue("directly beside the local prewarm", ours > prewarm && ours - prewarm < 1200)
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
        assertEquals(
            "the boot prewarm asks it — inside SERVICE_START's own decision now (4.5.1 pass 2), " +
                "which is what makes the boot a MEMBER of the event set rather than a fourth " +
                "opinion beside it",
            1, count(text, "\n                packToWarm = previewPackToWarm(\n"),
        )
        assertEquals(
            "and it hands the answer straight over (the leading `)` anchors this to the boot's " +
                "own statement — the wrap site's identically-indented line is the initialiser " +
                "below, and an unanchored needle would count both)",
            1,
            count(
                text,
                "            )\n" +
                    "            if (residency is PreviewResidency.Warm) warmStreamingPreview(residency.pack)\n",
            ),
        )
        val wrapSite = indexOfOrFail(startRecording, "        val packToWarm = previewPackToWarm(\n")
        val warmCall = indexOfOrFail(
            startRecording,
            "        val preview =\n" +
                "            if (residency is PreviewResidency.Warm) warmStreamingPreview(residency.pack)\n" +
                "            else streamingPreview\n",
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
        assertEquals(
            "four warm calls, all handed a pack and all of them a member of the event set's " +
                "answer (theSetOfMomentsThatChangeWhichPackIsResidentIsWrittenDownAndEveryMemberIsWired)",
            4,
            count(text, "warmStreamingPreview(pack)\n") +
                count(text, "warmStreamingPreview(residency.pack)"),
        )
    }

    @Test
    fun onePackPerProcess_aLanguageChangeReleasesBeforeItWarms() {
        // (4.5.0 T2, defect 1) The invariant is the ENGINE's now — `warm` is idempotent on the
        // PACK and releases a resident recognizer whose pack is not the one being warmed, pinned
        // behaviourally in StreamingPreviewEngineTest. What is pinned HERE is that Main's copy of
        // the decision still agrees with it and is still written in exactly one place: two
        // disagreeing answers to "which pack is resident" is the shape Task 1 spent three rounds
        // retiring, and this field is a cache of the engine's answer, never a second one.
        //
        // The corrupt marker follows the pack whose load FAILED, and the engine HANDS IT OVER.
        // 4.4.1 read `streamingPreviewPack` for it — a field Main writes when the selection moves,
        // so a switch to B during A's load marked B corrupt for A's failure, deleting a healthy
        // marker and leaving the broken pack installed. A closure over the pack this — a closure over the pack this
        // call was made with would mark English corrupt for a Spanish failure.
        assertEquals(1, count(text, "if (resident != null && streamingPreviewPack != pack) resident.release()"))
        assertEquals(1, count(text, "        streamingPreviewPack = pack\n"))
        assertEquals(
            "the hook marks the pack the ENGINE named, and nothing else",
            1,
            count(text, "onLoadFailure = { failed -> app.streamingPackManager.markCorrupt(failed) },"),
        )
        assertEquals(
            "and the field is no longer read by the hook — a read there is the stale-pack defect " +
                "coming back, and it compiles clean",
            0,
            count(text, "onLoadFailure = { streamingPreviewPack"),
        )
        // (4.5.0 Task 3 fix round 2, review r2's N2) THE SECOND HOOK, and it is a different
        // event: `onLoadFailure` is a load that threw, `onDisabled` is the language going OFF for
        // the rest of the process — a failed canary, a missing clip or three decode throws leave
        // the bytes valid and `state()` answering `Installed`, so `markCorrupt` is wrong for them
        // and this is the only signal that exists. Without this line the verdict stays on a
        // private field of this class and the strip above the language selector goes on promising
        // *"words appear on the bubble whenever you pick it"* for a language no word can come
        // from until the app restarts.
        assertEquals(
            "the verdict is published for the pack the ENGINE named, exactly as the hook above is",
            1,
            count(text, "onDisabled = { wentOff ->"),
        )
        assertEquals(
            "into the one register both selection surfaces read",
            1,
            count(text, "PreviewDisabled.note(wentOff.language)"),
        )
        assertEquals(
            "and never off the field Main moves when the SELECTION changes — the stale-pack " +
                "defect, one hook over",
            0,
            count(text, "onDisabled = { streamingPreviewPack"),
        )
        assertEquals("the field is written in exactly that one place", 1, count(text, "streamingPreviewPack = "))
        // (4.5.1 Task 1) THE THIRD HOOK, and a third event again: not a failure of any kind, but
        // what is RESIDENT AND USABLE now — which changes when a load arms, when a trim frees the
        // recognizer, when the language changes and when a verdict lands. `isWarmFor(pack)` is the
        // answer the session gate itself reads and it had no reader outside this class, so the
        // strip above the language selector could only promise words off *the files landed*; this
        // is what makes READY mean the next tap works.
        assertEquals(
            "the engine names what is resident, exactly as the two hooks above name their packs",
            1,
            count(text, "onWarm = { resident ->"),
        )
        assertEquals(
            "into the one register both selection surfaces read",
            1,
            count(text, "PreviewWarm.note(resident?.language)"),
        )
        assertEquals(
            "and never off the field Main moves — Main's belief is the thing that is an 802-860 ms " +
                "load ahead of the truth, which is worse here than in either hook above",
            0,
            count(text, "onWarm = { streamingPreviewPack"),
        )
        // (fix round 1, review r1's B1) ...AND THE ONE HALF OF THAT FACT NO HOOK CAN CARRY: that an
        // engine EXISTS. This class owns it — an object cannot report its own absence — and it must
        // be stated, because reading "nobody was asked" as "no" took the READY receipt, and with it
        // the whole strip, off Home the moment a 73-128 MB install finished: the bubble service is
        // not started when the app launches, so that is where most users read the surface.
        assertEquals(
            "claimed at the ONE site an engine is ever constructed, so it cannot be claimed for " +
                "an engine that was not built",
            1,
            count(text, "PreviewWarm.engineBuilt()"),
        )
        assertEquals(
            "and un-claimed where the service drops it",
            1,
            count(text, "PreviewWarm.engineGone()"),
        )
        // ...and in that ORDER, with the un-claim AFTER the field is nulled: `release()` posts its
        // withdrawal to the engine's own executor, so a dying engine's last word lands after
        // onDestroy and would otherwise overwrite *no engine* with *not warm* for the rest of the
        // process. The register drops an unclaimed note; this is the line that un-claims.
        val destroyRelease = indexOfOrFail(text, "        streamingPreview?.release()\n        streamingPreview = null\n")
        val gone = indexOfOrFail(text, "PreviewWarm.engineGone()")
        assertTrue(
            "the un-claim comes after release() and after the field is nulled",
            gone > destroyRelease,
        )
        val built = indexOfOrFail(text, "PreviewWarm.engineBuilt()")
        assertTrue(
            "and the claim comes before the warm that follows construction, or an arming answer " +
                "would be dropped as unclaimed",
            built < indexOfOrFail(text, "engine.warm(dir, pack)"),
        )
    }

    /**
     * **THE SET OF MOMENTS AT WHICH THE RESIDENT PREVIEWER CAN DISAGREE WITH `previewPackToWarm`,
     * WRITTEN DOWN** (4.5.1 pass 2, the controller's framing ruling).
     *
     * Task 1 added a THIRD warm trigger and its own report then named two more gestures that still
     * missed, while its reviewer named a third defect of the same family. That is the state-model
     * shape that burned three rounds in 4.4.1: *the bug is not any one missing trigger, it is that
     * "the set of moments that change which pack should be resident" was never enumerated.* So the
     * set is an ENUM, every member is wired to the one decision, and this pin is what makes a
     * SIXTH gesture fail loudly instead of silently missing:
     *
     *  - a member added to the enum makes `previewResidency`'s `when` non-exhaustive — a COMPILE
     *    error, the loudest failure available;
     *  - a member that is given an arm but no site fails the per-member count below;
     *  - a warm or a release that does not come from a member's answer fails the call-site counts.
     */
    @Test
    fun theSetOfMomentsThatChangeWhichPackIsResidentIsWrittenDownAndEveryMemberIsWired() {
        val events = listOf(
            "SERVICE_START", "SELECTION_CHANGED", "SWITCH_CHANGED", "PACK_INSTALLED",
            "SESSION_START", "SESSION_END", "MEMORY_TRIM",
        )
        assertEquals(
            "the set is declared exactly once",
            1, count(text, "internal enum class PreviewResidencyEvent {"),
        )
        val set = body("internal enum class PreviewResidencyEvent {", "\n}\n")
        events.forEach { event ->
            assertEquals("$event is a member of the set", 1, count(set, "\n    $event,\n"))
        }
        assertEquals(
            "...and the set is exactly these members: a member added here has to be wired below, " +
                "which is the whole point of writing the set down",
            events.size,
            Regex("(?m)^    [A-Z][A-Z_]+,$").findAll(set).count(),
        )
        events.forEach { event ->
            assertEquals(
                "$event names itself at exactly one site — the site is what makes it an event " +
                    "rather than a comment",
                1, count(text, "event = PreviewResidencyEvent.$event"),
            )
        }
        assertEquals(
            "ONE decision for the whole set, so a member cannot acquire a policy of its own",
            1, count(text, "internal fun previewResidency(\n"),
        )
        assertEquals(
            "and it is asked at three sites: ONE shared body for the event-shaped members " +
                "(askPreviewResidency) plus the two establishing moments, which ask it where they " +
                "stand so that their call sites keep the shape they had before the set existed",
            3,
            // Anchored on the newline, so each indent is EXACT: the shallower needle is a
            // substring of the deeper line, and an unanchored count would triple-count.
            count(text, "\n        val residency = previewResidency(\n") +
                count(text, "\n            val residency = previewResidency(\n"),
        )
        assertEquals(
            "...and a fourth time inside warmOnPackInstalled, which is PACK_INSTALLED's adapter " +
                "rather than a second decision: it adds the phase and the record's language and " +
                "delegates every shared term",
            1, count(text, "\n    val residency = previewResidency(\n"),
        )
        // EVERY warm of the previewer is an event's answer, and every release is too.
        assertEquals(
            "the warm path is entered from the event's answer or from the install gate's, and " +
                "from nowhere else",
            3, count(text, "warmStreamingPreview(residency.pack)"),
        )
        assertEquals("plus the install gate's own answer", 1, count(text, "warmStreamingPreview(pack)\n"))
        assertEquals(
            "THREE release sites: the trim, the destroy, and the one event-shaped re-ask",
            3, count(text, "streamingPreview?.release()"),
        )
    }

    /**
     * **ITEM 1 — re-picking an ALREADY-INSTALLED language must not miss its first session.**
     *
     * The selection-change collector acted on `previewPackToWarm`'s NULL answer only (release) and
     * let a non-null answer fall through, so `pick English → switch to Auto → switch back` warmed
     * nothing until the NEXT session's wrap site, which arms the session after that. That is the
     * owner's own complaint — *"having to transcribe a second time to get the live to work"* —
     * reached by the gesture he performs most, because he tests on Auto deliberately and moves
     * between languages to compare them.
     *
     * The collector's `streamingPreview == null` guard went with it: correct for a release (there
     * is nothing to hand back), wrong for a warm (`warmStreamingPreview` BUILDS the engine when
     * the field is null, which is exactly the state a user who has only ever been on Auto is in).
     */
    @Test
    fun rePickingAnAlreadyInstalledLanguageWarmsItsPackInsteadOfFallingThrough() {
        val collector = indexOfOrFail(
            text,
            "            app.preferencesManager.selectedLanguage.drop(1).collect {\n",
        )
        assertEquals(
            "ONE collector on the selection in the service, and it is this one",
            1, count(text, "app.preferencesManager.selectedLanguage"),
        )
        assertEquals(
            "drop(1): the value already in place is the boot prewarm's — SERVICE_START is its own " +
                "member of the set",
            1, count(text, "selectedLanguage.drop(1)"),
        )
        val ask = indexOfOrFail(
            text,
            "                askPreviewResidency(event = PreviewResidencyEvent.SELECTION_CHANGED)\n",
        )
        assertTrue("the collector's whole body is the re-ask", ask > collector && ask - collector < 200)
        assertEquals(
            "the FALL-THROUGH IS GONE: a non-null answer was a `return@collect` and is now a warm",
            0, count(text, "if (keep != null) return@collect"),
        )
        assertEquals(
            "and the engine-exists guard with it — it was right for the release and wrong for the " +
                "warm, and a release of a null field is a no-op anyway",
            0, count(text, "if (streamingPreview == null) return@collect"),
        )
    }

    /**
     * **B1 — THE "SHOW LIVE WORDS" SWITCH IS A MEMBER OF THE SET, NOT AN UNWATCHED TERM** (fix
     * round 1, review r1's B1).
     *
     * `previewPackToWarm` takes three inputs. Two of them had members — the pick (the collector
     * pinned above) and the installed set (the board collector) — and the switch had none, in
     * either direction, while being a `StateFlow` with a single writer three lines below the
     * language rows on the same Settings screen.
     *
     *  - OFF → ON was the owner's complaint reached in ONE TAP: nothing warmed, the next tap
     *    posted the 802-860 ms load and read `isWarmFor` in the same breath, so session one showed
     *    no live words and session two worked;
     *  - ON → OFF left +169 MB resident for a feature just switched off, which is precisely the
     *    allocation 4.4.1 pass 3 ITEM 2 exists to hand back.
     *
     * Pinned as SOURCE because a collector that does not exist is invisible to every other test —
     * the defect was an absence, and an absence can only be pinned where the code is read.
     */
    @Test
    fun theLIVEWORDSSwitchIsAMemberOfTheSetAndNotAnUnwatchedTerm() {
        assertEquals(
            "ONE collector on the switch in the service, and it is this one",
            1, count(text, "app.preferencesManager.localPreviewEnabledFlow"),
        )
        val collector = indexOfOrFail(
            text,
            "            app.preferencesManager.localPreviewEnabledFlow.drop(1).collect {\n",
        )
        assertEquals(
            "drop(1), for the selection collector's own reason: the value already in place is " +
                "SERVICE_START's, which has just asked the same question of it",
            1, count(text, "localPreviewEnabledFlow.drop(1)"),
        )
        val ask = indexOfOrFail(
            text,
            "                askPreviewResidency(event = PreviewResidencyEvent.SWITCH_CHANGED)\n",
        )
        assertTrue(
            "the collector's whole body is the re-ask — the SAME shared body and the SAME arm as " +
                "the selection collector, so the two neighbours cannot disagree",
            ask > collector && ask - collector < 200,
        )
        // ...and the term itself is still read where `previewPackToWarm` is asked, so the member
        // and the term cannot drift: the switch is an argument of the one owner, not a guard of
        // the collector's own.
        assertEquals(
            "the switch is read as an ARGUMENT of the one owner, never as a guard at the member's " +
                "site",
            0, count(text, "if (!app.preferencesManager.localPreviewEnabled)"),
        )
    }

    /**
     * **ITEM 2 — an install that completes DURING a session must not cost the session AFTER it
     * too.**
     *
     * The refusals correctly decline to warm while a session or a batch job is in flight, and
     * nothing re-asked when it ended. The existing "session wrap" warm does NOT cover this: it
     * sits at session START (`startRecording`, before `connect`) and its own KDoc says it *"arms
     * NEXT session, not this one, because warm() is asynchronous and the gate reads isWarmFor()
     * now"*. So an install landing mid-session costs that session AND the next one — session two
     * posts the load and reads `isWarmFor` in the same breath — and session three is the first with
     * live words.
     *
     * SESSION_END is the member that closes it, at the ONE site a session ends: the single writer
     * of `currentState`. That is deliberately not `teardownRealtime()`, which runs BEFORE the state
     * moves and would therefore be refused by its own `sessionActive` term — the moment the refusal
     * stops applying IS this transition, which is why the two are paired here rather than argued.
     */
    @Test
    fun anInstallThatLandedDuringASessionIsReAskedTheMomentThatSessionENDS() {
        val write = indexOfOrFail(text, "    private fun updateBubbleState(newState: BubbleState) {\n")
        val body = body("    private fun updateBubbleState(newState: BubbleState) {", "\n    }\n")
        assertEquals(
            "ONE writer of the state, so ONE site where a session can end",
            1, count(text, "        currentState = newState\n"),
        )
        assertEquals(
            "the PREVIOUS state is read before the write — an end is a transition, and the write " +
                "destroys the half that says a session was running",
            1, count(body, "        val previous = currentState\n"),
        )
        val previous = indexOfOrFail(body, "        val previous = currentState\n")
        val assign = indexOfOrFail(body, "        currentState = newState\n")
        val ask = indexOfOrFail(body, "event = PreviewResidencyEvent.SESSION_END")
        assertTrue("the read comes first", previous < assign)
        assertTrue("and the re-ask after the write, so the refusal no longer applies", assign < ask)
        assertEquals(
            "the transition itself is a pure predicate, asked once — never a conjunction inlined " +
                "here, which is how the refusal and the re-ask would drift apart",
            1, count(body, "if (previewSessionEnded(previous, newState))"),
        )
        assertEquals("declared once", 1, count(text, "internal fun previewSessionEnded(\n"))
        assertTrue(
            "and the re-ask is POSTED on Main: updateBubbleState is called from both threads, and " +
                "the body it runs reads currentState",
            body.indexOf("serviceScope.launch(Dispatchers.Main) {", ask - 200) in (assign + 1) until ask,
        )
        assertTrue("this is all inside updateBubbleState", write >= 0)
    }

    /**
     * **ITEM 3 — ONE SHAPE FOR BOTH COLLECTORS: the selection is read INSIDE the census hop, never
     * above it** (the reviewer's N1, carried from round 1).
     *
     * The board collector read the pick on Main, suspended for the disk census, and then passed
     * that now-stale pick as `previewLanguage` — while its twin fifty lines above read its own pick
     * *inside* the same `withContext(Dispatchers.IO)` block for exactly this reason. Two collectors
     * disagreeing about a hazard one of them had already solved is the shape this pass exists to
     * retire.
     *
     * Worst case, and it is why this is not cosmetic: across the hop the user moves the selection
     * off the language whose install just landed; the event-shaped re-ask frees the recognizer for
     * the new (pack-less) selection, and this collector then loads the pack the selection has left
     * — re-taking the +169 MB that 4.4.1 pass 3 ITEM 2 exists to hand back, and breaking *"never
     * warm a language that is not the selection"* in the letter.
     */
    @Test
    fun neitherCollectorReadsTheSELECTIONAboveItsCensusHop() {
        assertEquals(
            "the pick is read through the one seam, four times",
            4, count(text, "app.preferencesManager.getLanguageForApi()"),
        )
        // 1. The shared body every event-shaped member runs: the pick is an argument of the
        //    lookup, inside the hop.
        val sharedHop = indexOfOrFail(text, "        val packToWarm = withContext(Dispatchers.IO) {\n")
        val sharedPick = text.indexOf(
            "                previewLanguage = app.preferencesManager.getLanguageForApi(),\n",
            sharedHop,
        )
        val sharedClose = text.indexOf("\n        }\n", sharedHop)
        assertTrue("the shared body reads the pick inside its hop", sharedPick in (sharedHop + 1) until sharedClose)
        // 2. The board collector: the pick is the first line of its hop, and the record is looked
        //    up FROM it in the same block, so the phase and the language cannot come from
        //    different instants either.
        val boardHop = indexOfOrFail(
            text,
            "                    val (selection, record, installed) = withContext(Dispatchers.IO) {\n",
        )
        val boardPick = text.indexOf(
            "                        val pick = app.preferencesManager.getLanguageForApi()\n",
            boardHop,
        )
        val boardClose = text.indexOf("\n                    }\n", boardHop)
        assertTrue("the board collector reads the pick inside its hop", boardPick in (boardHop + 1) until boardClose)
        val boardRecord = text.indexOf(
            "                            com.whispereverywhere.transcription.stream.PreviewWorkboard.of(pick),\n",
            boardHop,
        )
        assertTrue("and looks the record up from it, in the same block", boardRecord in (boardPick + 1) until boardClose)
        // 3. ...and the terms that move are still read BELOW both hops.
        val boardSession = text.indexOf(
            "                        sessionActive = currentState != BubbleState.IDLE && currentState != BubbleState.ERROR,\n",
            boardClose,
        )
        assertTrue("the session term is read after the board's suspension", boardSession > boardClose)
        assertEquals(
            "and the pick is used under ONE name in that collector, so a second read cannot creep " +
                "back above the hop",
            1, count(text, "                        previewLanguage = selection,\n"),
        )
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
