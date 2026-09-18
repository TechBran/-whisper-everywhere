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
        assertEquals(1, count(service, "WhisperNative.diag("))
        at(startRecording, "WhisperNative.diag(SpeakerDiag.line(assignment))", "startRecording")
        // And that is ALL the callback does in Task 3 — nothing is rendered yet.
        assertEquals("no panel write from the speaker callback yet", 0, count(service, "SpeakerLabels"))
    }

    // ------------------------------------------------------------------ the engine seam

    @Test
    fun theEngineRunsThePassBelowTheDeliveryAndNeverWaitsForIt() {
        at(engine, "var speakerAssigner: SpeakerAssigner? = null", "LocalWhisperEngine.kt")
        val resolve = at(engine, "resolve(seq, outcome, clearPreview = streamedPreview, myListener)", "LocalWhisperEngine.kt")
        val pass = at(engine, "assigner.assign(seq, samples, vad)", "LocalWhisperEngine.kt")
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
        // `vad` is null for every backend with no native VAD filter (cloud, the NPU tier while it
        // is live) and for a chunk the VAD found no speech in; both must produce today's session.
        at(engine, "(outcome as? SegmentOutcome.Text)?.vad", "LocalWhisperEngine.kt")
        // The stale-session guard is the same identity check the resolution path uses: a dead
        // session's late segment must not feed the NEW session's tracker.
        at(engine, "if (assigner != null && listener === myListener) {", "LocalWhisperEngine.kt")
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
    }
}
