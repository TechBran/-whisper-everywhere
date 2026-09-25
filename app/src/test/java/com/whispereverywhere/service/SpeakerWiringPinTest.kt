package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE SPEAKER PASS's WIRING, pinned structurally (4.10 Task 3) — the `LocalPreviewWiringPinTest`
 * idiom: neither `FloatingBubbleService` nor the sherpa adapter can be instantiated on the JVM, so
 * the CALLS are pinned as whitespace-collapsed, symbol-scoped needles inside their declaring
 * bodies; never line numbers.
 *
 * What this class holds, and why each one is a hazard no behavioural test can reach:
 *
 *  - **The assigner is built at ONE site, per session, only for a LOCAL session with the setting
 *    on**, and the cached engine is re-pointed at it every time. `warmLocalEngine` keeps ONE
 *    `LocalWhisperEngine` alive across sessions (it owns the native context), so an engine left
 *    holding the previous session's assigner would fingerprint a new session's voices into last
 *    session's numbering — the one way the spec's *"speaker numbers restart at 1 each session"*
 *    can break while every unit test passes.
 *  - **Teardown detaches the ENGINE before it releases the assigner**, in that order, exactly
 *    once each. Reversed, a segment resolving out of the native executor after the release — the
 *    engine can still be inside a transcribe when the user taps stop — hands work to a
 *    `speaker-embed` executor that is shutting down.
 *  - **`onSegmentResolved` never mentions the speaker pass at all.** Text delivery is that
 *    coroutine's whole job; the embedding is the engine's, below its own `resolve`, on another
 *    thread (spec §3.3: the commit floors are untouched).
 *  - **The engine runs the pass BELOW the delivery and never waits for it.**
 *  - **The line goes out through native logging.** R8 strips every `android.util.Log` call from the
 *    release build (`proguard-rules.pro`, "Release log hygiene"), and the release build is the one
 *    the owner runs the device session on — so a Kotlin `Log.i` here would make plan Task 4
 *    unrunnable, silently, with the debug build still printing perfectly.
 *  - **Nothing under `transcription/stream/` names any of it.** The live preview strip is
 *    untouched by ruling (spec §2, §4), and `PreviewTeeEngine` forwards the outcome unread.
 */
class SpeakerWiringPinTest {

    private companion object {
        /**
         * The sherpa adapter's class name, ASSEMBLED rather than written out — and that is not
         * cosmetic. The adapter's own pin test asserts that NO test file under `src/test/java`
         * names it at all (its companion initialiser calls `System.loadLibrary` for a `.so` the
         * JVM suite does not have), and a census needle is still a name. The two halves keep this
         * file inside that rule while still being able to assert the adapter's absence from the
         * assigner and from the whole preview package.
         */
        val ADAPTER = "Speaker" + "Embedder"
    }

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

    /**
     * Collapsed: line endings normalised and every run of whitespace reduced to one space. The
     * pins below are about WHICH call is made and in WHAT ORDER, never about how it is wrapped, so
     * a reformat must not be able to break them.
     */
    private fun collapsed(relative: String): String = source(relative)
        .readText()
        .replace("\r\n", "\n")
        .replace(Regex("\\s+"), " ")

    private val service by lazy { collapsed("src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt") }
    private val engine by lazy { collapsed("src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt") }
    private val assigner by lazy { collapsed("src/main/java/com/whispereverywhere/transcription/speakers/SpeakerAssigner.kt") }
    private val reclusterer by lazy { collapsed("src/main/java/com/whispereverywhere/transcription/speakers/SpeakerReclusterer.kt") }
    private val tee by lazy { collapsed("src/main/java/com/whispereverywhere/transcription/stream/PreviewTeeEngine.kt") }
    private val native by lazy { collapsed("src/main/java/com/whispereverywhere/whisper/WhisperNative.kt") }
    private val cpp by lazy { collapsed("src/main/cpp/whisper_jni.cpp") }
    private val buildFile by lazy { collapsed("build.gradle.kts") }

    private fun at(haystack: String, needle: String, what: String): Int {
        val i = haystack.indexOf(needle)
        assertTrue("missing from $what: <<$needle>>", i >= 0)
        return i
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    /** The text between two needles, so a pin cannot be satisfied by a match in another method. */
    private fun between(haystack: String, from: String, to: String, what: String): String {
        val start = at(haystack, from, what)
        val end = haystack.indexOf(to, start)
        assertTrue("the end of <<$from>> moved in $what", end > start)
        return haystack.substring(start, end)
    }

    private val startRecording by lazy {
        between(service, "private fun startRecording() {", "private fun stopRecording() {", "FloatingBubbleService.kt")
    }

    private val teardown by lazy {
        between(service, "private fun teardownRealtime() {", "override fun onTrimMemory(level: Int) {", "FloatingBubbleService.kt")
    }

    private val onSegmentResolved by lazy {
        between(
            service,
            "override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {",
            "override fun onError(message: String) {",
            "FloatingBubbleService.kt",
        )
    }

    // ------------------------------------------------------------------ the session

    @Test
    fun theAssignerIsBuiltOncePerSessionForALocalSessionWithTheSettingOn() {
        assertEquals("ONE construction site in the whole service", 1, count(service, "SpeakerAssigner("))
        at(startRecording, "SpeakerAssigner(", "startRecording")
        // Both terms of the gate: the user's switch, and "this is not a cloud session" — a cloud
        // session's local engine is its FALLBACK and spec §2/§4 leave cloud sessions as they are.
        at(startRecording, "app.preferencesManager.detectSpeakers", "startRecording")
        at(startRecording, "cloudWrapper == null", "startRecording")
    }

    @Test
    fun theCachedEngineIsRePointedEverySessionIncludingAtNothing() {
        // The assignment is UNCONDITIONAL — one statement for both arms of the gate — because the
        // engine outlives the session. A re-point placed inside the `if` would leave last
        // session's assigner attached for every session that turns the setting off or goes cloud.
        at(startRecording, "speakerAssigner = speakers", "startRecording")
        at(startRecording, "localEngine?.speakerAssigner = speakers", "startRecording")
        assertEquals(
            "one re-point at session start and one detach at teardown, and nowhere else",
            2,
            count(service, "localEngine?.speakerAssigner = "),
        )
    }

    @Test
    fun teardownDetachesTheEngineBeforeItReleasesTheAssignerAndDoesBothOnce() {
        val detach = at(teardown, "localEngine?.speakerAssigner = null", "teardownRealtime")
        val release = at(teardown, "speakerAssigner?.release()", "teardownRealtime")
        assertTrue("the engine is detached FIRST", detach < release)
        assertEquals("ONE release site", 1, count(service, "speakerAssigner?.release()"))
        // teardownRealtime is the convergence point of every session exit — normal drain end,
        // recorder-start failure, connect-time fatal, onDestroy — so one site there is every path.
        assertTrue("…and it is the teardown's", teardown.contains("speakerAssigner?.release()"))
    }

    @Test
    fun theDeliveryCoroutineNeverTouchesTheSpeakerPass() {
        assertEquals(0, count(onSegmentResolved, "speakerAssigner"))
        assertEquals(0, count(onSegmentResolved, "assign("))
        assertEquals(0, count(onSegmentResolved, "SpeakerDiag"))
    }

    @Test
    fun theOneDiagLinePerChunkGoesOutThroughNativeLoggingSoR8CannotStripIt() {
        // SEVEN native lines, and no more: the per-chunk `speaker:` line, the once-per-session
        // relabel (Task 5), the per-pass `speaker-recluster:` line spike session 6 added,
        // — since 4.11.3 removed the panel's character ceiling — the `panel:` line that reports
        // what rendering the WHOLE session costs, so that decision stays a measurement, and —
        // since the 2026-09-22 mute toggle — the ONE `logMute` site every `mute:` edge goes out
        // through (on, off, session ended muted), so a Play build shows when capture was
        // silenced; and — since 4.16.1 — the wall-cap line (`wall-clock cap -> commit
        // (cap=…ms)`), one per cap cut, which the owner's 4.16.1 session counts per minute to
        // judge the AI-chip tiers' 5 s wall (CapSeamPinTest pins it at its call site); and — also
        // since 4.16.1 — the ONE `logRingLine` site the startup ring's four evidence lines
        // (drain, overflow, switch flush, stop flush) go out through, off Main, so a Play build
        // shows what the ring held and dropped during the arm (StartupRingWiringPinTest). The
        // relabel's is counted here rather than left to drift because it is
        // guarded by the latch's own "did it move" answer and must stay once per session however
        // many callers ask for the latch. All seven go out through WhisperNative.diag because R8
        // strips every android.util.Log call from the release build, and the release build is
        // the only one the owner can install.
        assertEquals(7, count(service, "WhisperNative.diag("))
        assertEquals("the mute's lines share one site", 1, count(service, "runCatching { WhisperNative.diag(line) }"))
        assertEquals(
            "the panel's cost line is emitted from exactly one place, and only past the old cap",
            1,
            count(service, "panel: chars="),
        )
        at(startRecording, "WhisperNative.diag(SpeakerDiag.line(assignment))", "startRecording")
        at(startRecording, "WhisperNative.diag(SpeakerDiag.reclusterLine(relabel))", "startRecording")
        assertEquals("the recluster line is emitted from exactly one place", 1, count(service, "SpeakerDiag.reclusterLine("))
        assertEquals("the relabel line is emitted from exactly one place", 1, count(service, "SpeakerDiag.relabelLine("))
    }

    // ------------------------------------------------------------------ the engine seam

    @Test
    fun theEngineRunsThePassBelowTheDeliveryAndNeverWaitsForIt() {
        at(engine, "var speakerAssigner: SpeakerAssigner? = null", "LocalWhisperEngine.kt")
        val resolve = at(engine, "resolve(seq, outcome, clearPreview = streamedPreview, myListener)", "LocalWhisperEngine.kt")
        val pass = at(engine, "assigner.assign(seq, samples, windows)", "LocalWhisperEngine.kt")
        assertTrue("the text is delivered before a single sample is fingerprinted", resolve < pass)
        assertEquals("ONE call site", 1, count(engine, "assigner.assign("))
        // Nothing in this engine ever blocks on the embed executor: the whole point of the seam is
        // that the whisper thread hands over samples and walks away (spec §3.3).
        assertEquals(0, count(engine, "awaitTermination"))
        assertEquals(0, count(engine, "Future"))
        assertEquals(0, count(engine, ".get()"))
    }

    @Test
    fun theAssignerIsHandedTheORIGINALSamplesAndOnlyForAnOutcomeThatCarriesGeometry() {
        // `windows` is null for every backend with no native VAD filter (cloud, the NPU tier
        // while it is live) and for a chunk the VAD found no speech in. It is the list the SPANS
        // were cut against, built once in `textOutcome` — a second one computed here could
        // disagree with it and put one window's id on another's text (spike session 4).
        at(engine, "val committed = outcome as? SegmentOutcome.Text", "LocalWhisperEngine.kt")
        at(engine, "val windows = committed?.windows", "LocalWhisperEngine.kt")
        assertEquals(
            "the window list is built at ONE place and shared",
            1,
            count(engine, "SpeakerSpans.windows("),
        )
        // The stale-session guard is the same identity check the resolution path uses: a dead
        // session's late segment must not feed the NEW session's tracker.
        at(engine, "if (assigner != null && listener === myListener) {", "LocalWhisperEngine.kt")
    }

    // ------------------------------------- the NPU route (4.10, the Fold6 defect)

    @Test
    fun theCpuTiersRouteIsFIRSTAndIsTHEONEThatRunsWheneverThereIsGeometry() {
        // THE REGRESSION THIS EXISTS FOR. The NPU tier's route labels a WHOLE CHUNK with one
        // speaker, because its decoder publishes no text offsets. If it ever ran for a chunk that
        // HAS geometry, a sentence-labelled chunk would silently collapse to a single speaker —
        // and every id involved would still be a real id, so nothing downstream would report it.
        //
        // Two structural facts keep that impossible, and both are asserted here rather than
        // inferred: the per-window call is the `if`, and the whole-chunk call is inside the
        // `else if` behind `!backendPublishesGeometry`.
        val perWindow = at(engine, "assigner.assign(seq, samples, windows)", "LocalWhisperEngine.kt")
        val wholeChunk = at(
            engine,
            "assigner.assignVadRoute(seq, samples, it, backendSentences)",
            "LocalWhisperEngine.kt",
        )
        assertTrue("the geometry route is decided first", perWindow < wholeChunk)
        assertEquals("ONE per-window call site", 1, count(engine, "assigner.assign(seq"))
        assertEquals("ONE VAD-route call site", 1, count(engine, "assigner.assignVadRoute("))

        val fork = engine.substring(perWindow, wholeChunk)
        assertTrue(
            "the whole-chunk route is reachable ONLY through an else-if: a second independent " +
                "`if` would let a chunk take both routes and publish two contradictory answers " +
                "for the same seq",
            fork.contains("} else if ("),
        )
        assertTrue(
            "…and that else-if is gated on `!backendPublishesGeometry`, which is a BACKEND " +
                "CAPABILITY and not a per-chunk reading. The two per-chunk tests that look like " +
                "it are both wrong, and each was in this position at some point: `windows == " +
                "null` drags in every CPU chunk whose geometry produced no windows or no spans, " +
                "and `it != null` off the chunk's own `lastGeometry` drags in every CPU chunk " +
                "whose SNAPSHOT WAS LOST — `captureGeometry` swallowing an allocation failure, " +
                "or an interleaved batch chunk re-tagging the process-global slot between the " +
                "transcribe and the read. Neither says the tier cannot split a chunk's text, " +
                "and both must keep 4.9's answer of no labels rather than pay a second VAD pass " +
                "and wear one label for the whole chunk.",
            fork.contains("!backendPublishesGeometry"),
        )
        assertEquals(
            "`backendPublishesGeometry` is written at exactly ONE place, off the LIVE backend's " +
                "own declaration and in the same breath as the geometry read, so a mid-segment " +
                "NPU decline cannot be answered by a flag that has since flipped",
            1, count(engine, "backendPublishesGeometry = backend.publishesGeometry"),
        )
        assertEquals(
            "…and the geometry read itself no longer decides the route: it is a plain read",
            1, count(engine, "val geometry = backend.lastGeometry(ctx)"),
        )
        assertEquals(
            "the per-chunk null is never read as the tier's answer anywhere in this file",
            0, count(engine, "hadGeometry"),
        )
    }

    @Test
    fun theCapabilityTheGateReadsIsDeclaredByBOTHProductionBackendsAndDefaultsToTheSafeAnswer() {
        // The gate above is only as good as the flag behind it, and the flag has exactly three
        // declarations in main: the interface default, and one per production backend.
        val iface = collapsed("src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt")
        val npu = collapsed("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
        assertTrue(
            "the interface default is TRUE — 'a null from me is a per-chunk accident'. It is " +
                "deliberately NOT paired with `lastGeometry`'s null default: a forgotten " +
                "override here costs a tier its labels, which is a missing feature, while the " +
                "other way round costs a wrong label on text the user has already read.",
            iface.contains("val publishesGeometry: Boolean get() = true"),
        )
        assertTrue(
            "`WhisperNativeBackend` states it rather than inheriting it: it is the backend whose " +
                "per-chunk nulls the distinction exists for",
            iface.contains("override val publishesGeometry: Boolean get() = true"),
        )
        assertTrue(
            "`NpuWhisperBackend` answers off the SAME guard `lastGeometry` delegates through, " +
                "so the two can never disagree — and it is the exact inverse of " +
                "`detectsPerUtterance`'s `fallbackBackend == null`",
            npu.contains("override val publishesGeometry: Boolean get() = fallbackBackend != null"),
        )
    }

    @Test
    fun theWholeChunkRouteUsesTheSEAMSOwnVadPathAndSkipsWhenThereIsNone() {
        // The same VAD model path the backend seam hands `transcribeRaw`, and a null one means
        // the device has no VAD model at all — the route is SKIPPED rather than handed an
        // invented path, which native would answer with an init failure and a log line per chunk.
        at(
            engine,
            "vadModelPath()?.let { assigner.assignVadRoute(seq, samples, it, backendSentences) }",
            "LocalWhisperEngine.kt",
        )
        assertEquals(
            "the seam is `VadModel.path()` and it is named in exactly ONE place — the default of " +
                "the injected provider. A second, direct read anywhere else would be a second " +
                "answer to the same question, and only one of them would be the one a test can " +
                "drive.",
            1, count(engine, "VadModel.path()"),
        )
    }

    @Test
    fun theWholeChunkAnswerReachesTheSinkWithoutBeingInterpretedOnTheWay() {
        // The callback's job is to hop to Main and hand over; deciding what a whole-chunk pick
        // MEANS belongs to SpeakerRuns, which is the only object that knows what a run is.
        at(startRecording, "assignment.wholeChunkWindow,", "startRecording")
        assertEquals(
            "the service reads the field once and never branches on it",
            1, count(service, "assignment.wholeChunkWindow"),
        )
        assertEquals(0, count(service, "SpeakerRuns"))
    }

    @Test
    fun theAssignerItselfKnowsNothingOfAndroidOrSherpa() {
        // It is the pure half of the wiring: an executor, a tracker and the VoicePrints seam. The
        // ONE file that may name sherpa is the adapter (its own pin test holds that), and an
        // `android.` import here would put the assigner out of reach of every test above.
        assertTrue("the thread is named for the log and the trace", assigner.contains("\"speaker-embed\""))
        assertEquals(0, count(assigner, "import android."))
        assertEquals(0, count(assigner, "k2fsa"))
        assertEquals(0, count(assigner, ADAPTER))
    }


    // ------------------------------------------------------------------ the second look (session 6)

    @Test
    fun theRetrospectivePassIsPureAndOwnsNoThreadOfItsOwn() {
        // It is O(n²) over up to 600 fingerprints — the one piece of the feature that could
        // plausibly be "helped" onto a thread pool or a coroutine, and the one place that would
        // be wrong: it re-seeds the tracker, and the tracker is confined to the embed thread by
        // the argument that makes its "not synchronised" honest. A thread in this file would put
        // two writers on that state with every unit test still green.
        assertEquals(0, count(reclusterer, "import android."))
        assertEquals(0, count(reclusterer, "Executor"))
        assertEquals(0, count(reclusterer, "Thread"))
        assertEquals(0, count(reclusterer, "Dispatchers"))
        assertEquals(0, count(reclusterer, "suspend "))
        assertEquals(0, count(reclusterer, "k2fsa"))
        assertEquals(0, count(reclusterer, ADAPTER))
    }

    @Test
    fun theRetrospectivePassIsCALLEDFromTheEmbedThreadAndNowhereElse() {
        // ONE caller in the whole app, and it is the object that owns the `speaker-embed`
        // executor. On Main the pass would block the panel it is about to repaint; on the
        // whisper thread it would sit inside the commit floors spec §3.3 measured without it.
        assertEquals("the assigner is the only caller", 1, count(assigner, "SpeakerReclusterer.recluster("))
        // …AND IT PASSES THE TRACKER'S OWN CAP. `recluster`'s `maxSpeakers` defaults to
        // SpeakerTracker.MAX_SPEAKERS, so dropping the argument would compile, pass every other
        // test and be identical today — and then diverge the moment a tracker is built with a
        // different cap, leaving the pass free to answer with more live voices than the tracker
        // it reseeds may hold. The argument is the whole point of the parameter; pin it.
        at(assigner, "SpeakerReclusterer.recluster(session, tracker.maxSpeakers)", "SpeakerAssigner.kt")
        assertEquals(0, count(service, "SpeakerReclusterer.recluster("))
        assertEquals(0, count(engine, "SpeakerReclusterer.recluster("))
        // The service receives the ANSWER and never runs the pass: a relabel arrives as a
        // message on the same Main hop an assignment does.
        at(startRecording, "onRelabel = { relabel ->", "startRecording")
        // Its own private entry point is declared once and called twice — the chunk counter and
        // the finalize fence — and both are inside a body that runs on the embed executor.
        assertEquals("one declaration plus two calls", 3, count(assigner, "recluster()"))
        at(assigner, "if (++chunksSinceRecluster >= SpeakerReclusterer.RECLUSTER_EVERY_CHUNKS) recluster()", "SpeakerAssigner.kt")
    }

    @Test
    fun theFinalizeFenceRunsTheLastPassBEFOREItCountsDown() {
        // The sink is detached the instant the fence returns (`SpeakerLabelsWiringPinTest`), so
        // a pass published after the countdown would land on a `return@launch` and the delivery,
        // the clipboard and history would ship the online labels while the panel showed the
        // corrected ones. Order inside the barrier task is the whole guarantee.
        val fence = between(assigner, "fun awaitIdle(timeoutMs: Long): Boolean {", "fun release() {", "SpeakerAssigner.kt")
        val submit = at(fence, "executor.execute {", "awaitIdle")
        val pass = at(fence, "runCatching { recluster() }", "awaitIdle")
        val countdown = at(fence, "latch.countDown()", "awaitIdle")
        assertTrue("the pass is inside the barrier task", submit < pass)
        assertTrue("…and completes before the fence is released", pass < countdown)
    }

    @Test
    fun theKeptFingerprintsAreCappedSoASessionCannotGrowWithoutBound() {
        // A three-hour session is thousands of windows at 192 floats each, and the pass is
        // O(k²) besides. The VECTOR cap is the reclusterer's, read here rather than restated, so
        // the memory bound and the cost bound can never drift apart; the WINDOW bound is the
        // assigner's own and much larger, because a key is not a vector.
        at(assigner, "SpeakerReclusterer.MAX_RECLUSTER_FINGERPRINTS", "SpeakerAssigner.kt")
        at(assigner, "session.subList(0, excess).clear()", "SpeakerAssigner.kt")
        at(assigner, "MAX_RETAINED_WINDOWS", "SpeakerAssigner.kt")
    }

    @Test
    fun ALLTHREEFatesAreRememberedAndAPassThatConfirmedNobodyIsNotPublished() {
        // Two rules that only look unrelated. The pass RENUMBERS the id space
        // (`SpeakerTracker.reseed`), so every window it does not name is left holding an id that
        // afterwards means a different person — hence all three fates call `remember`, the two
        // without a vector included. And a pass concluding less than the panel already stands on
        // may not be published at all: the degenerate answer labels every window 1, the latch is
        // one-way, and reseeding on it collapses two live voices into one unconfirmed cluster.
        assertEquals("one declaration plus all three fates", 4, count(assigner, "remember("))
        at(assigner, "if (relabel.confirmedCount < SpeakerLabels.MIN_CONFIRMED_SPEAKERS) return", "SpeakerAssigner.kt")
        // The gate stands BEFORE the reseed and before the publication, not beside them.
        val pass = between(assigner, "private fun recluster() {", "/** Set by the first", "SpeakerAssigner.kt")
        val gate = at(pass, "if (relabel.confirmedCount < SpeakerLabels.MIN_CONFIRMED_SPEAKERS) return", "recluster()")
        assertTrue("nothing is re-seeded by a pass that concluded nothing", gate < at(pass, "tracker.reseed(relabel)", "recluster()"))
        assertTrue("…and nothing is published by one", gate < at(pass, "onRelabel(", "recluster()"))
    }

    @Test
    fun theDumpHeaderCarriesTheRulesOfThePassItRanUnder() {
        // A jsonl from a reclustering build and one from the online-only build describe two
        // different label histories for the same audio: `assigned` in the first was decided by a
        // tracker that had already been re-seeded. The header is the only place a later reader
        // can tell which one it is holding, and these three are read from the constants so the
        // record moves with them.
        at(assigner, "reclusterSim = SpeakerReclusterer.RECLUSTER_SIM", "SpeakerAssigner.kt")
        at(assigner, "minClusterSeconds = SpeakerReclusterer.MIN_CLUSTER_SECONDS", "SpeakerAssigner.kt")
        at(assigner, "reclusterEvery = SpeakerReclusterer.RECLUSTER_EVERY_CHUNKS", "SpeakerAssigner.kt")
    }

    // ------------------------------------------------------------------ the untouched preview

    @Test
    fun nothingUnderStreamNamesTheSpeakerPipelineAndTheTeeStillForwardsUntouched() {
        val dir = source("src/main/java/com/whispereverywhere/transcription/stream/PreviewTeeEngine.kt").parentFile!!
        val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }!!
        assertTrue("the stream package was found", files.size > 10)
        for (file in files) {
            val text = file.readText()
            for (symbol in listOf("SpeakerTracker", ADAPTER, "SpeakerAssigner", "VoicePrints")) {
                assertFalse("${file.name} names $symbol — the live preview path is untouched", symbol in text)
            }
        }
        assertTrue("the tee forwards the outcome unread", tee.contains("owner.onSegmentResolved(seq, outcome)"))
    }

    // ------------------------------------------------------------------ the native line

    @Test
    fun theDiagExportExistsOnBothSidesOfTheJniBoundary() {
        at(native, "external fun diag(line: String)", "WhisperNative.kt")
        at(cpp, "Java_com_whispereverywhere_whisper_WhisperNative_diag", "whisper_jni.cpp")
        val body = between(
            cpp,
            "Java_com_whispereverywhere_whisper_WhisperNative_diag",
            "// ---",
            "whisper_jni.cpp",
        )
        assertTrue("it logs under the house tag", body.contains("LOGDIAG("))
        assertTrue("…and gives the chars back", body.contains("ReleaseStringUTFChars"))
        // A null or failed GetStringUTFChars must not be dereferenced: this is reachable from Main.
        assertTrue(body.contains("nullptr"))
    }

    @Test
    fun theNewSourcePinnedFilesAreDeclaredInputsOfTheTestTask() {
        // Membership on that list follows what the tests READ. Both files below are read as TEXT
        // here, and both carry pins that are ORDER, ZERO-count or literal claims — the shape that
        // compiles to a byte-identical class, so without these entries the one edit each pin
        // exists to catch is the one that leaves :app:testDebugUnitTest UP-TO-DATE.
        assertTrue(
            buildFile.contains("\"src/main/java/com/whispereverywhere/transcription/speakers/SpeakerAssigner.kt\""),
        )
        assertTrue(
            buildFile.contains("\"src/main/java/com/whispereverywhere/transcription/stream/PreviewTeeEngine.kt\""),
        )
        // And the RECLUSTERER, by the same rule: this file now reads it as text for its purity
        // zero-counts (no thread, no Android, no sherpa), and a zero-count over comments is
        // satisfied by a comment — the comment-shaped mutation that compiles to a byte-identical
        // class and would otherwise leave :app:testDebugUnitTest UP-TO-DATE.
        assertTrue(
            buildFile.contains("\"src/main/java/com/whispereverywhere/transcription/speakers/SpeakerReclusterer.kt\""),
        )
    }
}
