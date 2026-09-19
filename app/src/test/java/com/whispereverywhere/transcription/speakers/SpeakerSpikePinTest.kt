package com.whispereverywhere.transcription.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE SPIKE IS DISARMED, pinned — and the mechanism is still here, pinned too (4.10.0/100).
 *
 * ### 1. What this class used to say, and what it says now
 *
 * While the spike was running this test asserted a PAIRING — `!(SPEAKER_SPIKE && versionName ==
 * "4.10.0")` — because the thing being guarded against was a deadline: the release that ships
 * speaker labels must not also ship the dump. **4.10.0 is now the release**, so a conditional
 * assertion has nothing left to condition on. It asserts the SHIPPING STATE instead, plainly:
 * `SPEAKER_SPIKE` is `false`, and no code path in this app can write speech audio.
 *
 * That is a stronger claim than the old one and it is asserted from four sides rather than one,
 * because "the constant is false" only disarms what actually reads the constant:
 *
 *  1. the constant itself, declared once, `const`, `false`;
 *  2. the three entry points that test it before they do anything — the writer
 *     ([SpeakerSpikeDump.write]), the assigner's lazy build (`SpeakerAssigner.spikeDump`) and the
 *     session's destination (`FloatingBubbleService`);
 *  3. the two things that could still touch a user's disk if an entry point were missed — the ONE
 *     construction of [SpeakerSpikeDump] in the whole app and the ONE call to [SpikeWav.mono16] —
 *     each pinned to sit below a guard;
 *  4. the PURGE, which is the half that must NOT be guarded, and is asserted not to be.
 *
 * ### 2. Why the mechanism is kept, and how it comes back
 *
 * It is DISARMED, not deleted. TitaNet-small was chosen by scoring five candidate models offline
 * against one session's dumped embeddings (`docs/measurements/2026-09-18-speaker-spike.md`,
 * session 2), and that is how a sixth would be chosen: the next model change wants this file. So
 * the standing warning stays in `SpeakerSpike.kt` as HISTORY — it is the condition on ever turning
 * the audio half on again — and the re-arm is one edit, named here and there: `SPEAKER_SPIKE =
 * true`, plus `adb shell touch <externalFilesDir>/speaker-spike/DUMP_AUDIO` for the audio.
 *
 * ### 3. The purge, and the one thing a disarm on its own would get wrong
 *
 * The 24 h sweep lived inside [SpeakerSpikeDump]'s `open`, on the way to writing a line. Disarming
 * the writer compiles the sweep away with it, so a phone that ran a spike build off the internal
 * track and then took 4.10.0 would keep that build's WAVs forever: nothing can write one, and
 * nothing would ever delete one. Both purge callers are therefore unconditional — every process
 * start, and the settings tap that turns detection off — and this class asserts that neither one
 * mentions the constant.
 *
 * ### 4. The writing still happens on the embed thread and nowhere else
 *
 * Kept from the armed build, and kept for the re-arm: neither `FloatingBubbleService` nor a real
 * file write on a service's threads is reachable from the JVM suite, so the confinement is pinned
 * as SOURCE, the `SpeakerWiringPinTest` way — whitespace-collapsed, symbol-scoped needles inside
 * their declaring bodies, never line numbers. The hazard is specific and it is not theoretical: a
 * flush moved into `assign` (which is called on `LocalWhisperEngine`'s native executor, the
 * whisper thread) would put file I/O inside the commit floors spec §3.3 measured without it, and
 * the only symptom would be a commit cadence that drifts on some devices.
 */
class SpeakerSpikePinTest {

    // ------------------------------------------------------------------ the house walker

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

    private fun sourceDir(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative/ from ${System.getProperty("user.dir")}")
    }

    private fun text(relative: String) = source(relative).readText().replace("\r\n", "\n")

    private fun collapsed(relative: String) = text(relative).replace(Regex("\\s+"), " ")

    private val spike by lazy { collapsed(SPIKE) }
    private val store by lazy { collapsed(STORE) }
    private val assigner by lazy { collapsed(ASSIGNER) }
    private val service by lazy { collapsed(SERVICE) }
    private val prefs by lazy { collapsed(PREFS) }
    private val app by lazy { collapsed(APP) }
    private val buildFile by lazy { collapsed("build.gradle.kts") }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun at(haystack: String, needle: String, what: String): Int {
        val i = haystack.indexOf(needle)
        assertTrue("missing from $what: <<$needle>>", i >= 0)
        return i
    }

    private fun between(haystack: String, from: String, to: String, what: String): String {
        val start = at(haystack, from, what)
        val end = haystack.indexOf(to, start)
        assertTrue("the end of <<$from>> moved in $what", end > start)
        return haystack.substring(start, end)
    }

    /** Every `.kt` under main, collapsed, so a claim about the WHOLE app can be made. */
    private val mainSources: List<Pair<String, String>> by lazy {
        sourceDir("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText().replace("\r\n", "\n").replace(Regex("\\s+"), " ") }
            .toList()
    }

    // --------------------------------------------------- 1. the spike is off in the shipped app

    @Test
    fun theSpikeIsDISARMEDAndTheConstantSaysSoInBothTheCodeAndTheSource() {
        assertFalse(
            "SpeakerSpike.SPEAKER_SPIKE is TRUE. 4.10.0 is the release that ships speaker labels " +
                "and the fingerprint/audio dump is a SPIKE-ONLY DIAGNOSTIC: the audio half stores " +
                "speech audio under getExternalFilesDir, and this app's rule is that audio is " +
                "deliberately never retained. The fix is one line — SPEAKER_SPIKE = false — which " +
                "lets the compiler strip every guarded call rather than leaving a reachable file " +
                "writer in the APK. Do NOT delete the mechanism to make this green: the next " +
                "model change needs it, and SpeakerSpike.kt says how to re-arm it.",
            SpeakerSpike.SPEAKER_SPIKE,
        )
        assertTrue(
            "…and it must be FALSE AS A LITERAL in the source, not computed: a const folded by " +
                "the compiler is what removes the guarded bodies from the APK, and anything the " +
                "compiler cannot fold leaves them in it",
            spike.contains("const val SPEAKER_SPIKE: Boolean = false"),
        )
    }

    @Test
    fun theGuardIsONECompileTimeConstantInONEObject() {
        val declarers = sourceDir("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("const val SPEAKER_SPIKE") }
            .map { it.name }
            .sorted()
            .toList()
        assertEquals(
            "SPEAKER_SPIKE must be declared exactly once, in one object, so that re-arming the " +
                "spike is one edit with no second home to forget. Found: $declarers",
            listOf("SpeakerSpike.kt"),
            declarers,
        )
        assertTrue("it lives in `object SpeakerSpike`", spike.contains("object SpeakerSpike { /**"))
    }

    @Test
    fun everyEntryIntoTheDumpIsBehindThatConstant() {
        // The three ways in, and each one checks the constant before it does anything at all.
        // (The fourth site — the purge — is the deletion, and it is asserted UNGUARDED below.)
        assertTrue("the writer itself", spike.contains("if (!SpeakerSpike.SPEAKER_SPIKE) return"))
        assertTrue(
            "the assigner's lazy build",
            assigner.contains("if (!SpeakerSpike.SPEAKER_SPIKE) return null"),
        )
        assertTrue(
            "the session's destination",
            service.contains("spike = if (SpeakerSpike.SPEAKER_SPIKE) {"),
        )
        // The writer's guard is its FIRST statement. A guard below a line that touches the disk
        // is not a guard, and `write` is the one function here that is handed speech audio.
        val write = between(spike, "fun write(record: SpikeFingerprint, audio: FloatArray?) {", "fun flush()", SPIKE)
        assertEquals(
            "the constant must be tested before anything else in write(): the parameter it " +
                "returns before touching is the original audio slice",
            0,
            write.indexOf("{ if (!SpeakerSpike.SPEAKER_SPIKE) return") - write.indexOf("{"),
        )
    }

    @Test
    fun noCodePathInTheWholeAppCanWriteSpeechAudio() {
        // A constant disarms only what reads it, so the two operations that could still reach a
        // user's disk are counted across EVERY main source and pinned to sit below a guard.
        //
        // (a) The dump object is CONSTRUCTED exactly once in the app, inside the assigner's
        //     spikeDump(), below that function's guard. A second construction anywhere — a
        //     debug menu, a second assigner — would open files with no constant in front of it.
        val builders = mainSources.filter { (_, body) -> body.contains("= SpeakerSpikeDump(") }
        assertEquals(
            "SpeakerSpikeDump must be constructed in exactly one place in the app. Found: " +
                builders.map { it.first },
            listOf("SpeakerAssigner.kt"),
            builders.map { it.first },
        )
        val lazyBuild = between(assigner, "private fun spikeDump(): SpeakerSpikeDump? {", "companion object", ASSIGNER)
        assertTrue(
            "…and the one construction is BELOW the guard, not beside it",
            lazyBuild.indexOf("if (!SpeakerSpike.SPEAKER_SPIKE) return null") <
                lazyBuild.indexOf("SpeakerSpikeDump("),
        )

        // (b) The WAV encoder has exactly one caller in the app: writeWav, private to the dump,
        //     called only from write() — which is the function whose first statement is the
        //     guard. Nothing else in this app turns samples into a file.
        val wavCallers = mainSources.filter { (_, body) -> body.contains("SpikeWav.mono16(") }
        assertEquals(
            "SpikeWav.mono16 must have exactly one caller in the app. Found: " +
                wavCallers.map { it.first },
            listOf("SpeakerSpike.kt"),
            wavCallers.map { it.first },
        )
        assertEquals("…one call site inside that file", 1, count(spike, "SpikeWav.mono16("))
        assertTrue("…and it is inside writeWav", spike.contains("private fun writeWav(record: SpikeFingerprint, audio: FloatArray) { val dir"))
        assertEquals(
            "…which write() is the only caller of. A second caller is a second path to a WAV, " +
                "and this one would not be behind write()'s guard",
            1,
            count(spike, "writeWav(record, audio)"),
        )

        // (c) And there is ONE dump directory, named once. A second file writing to a path of
        //     its own — a second mirror, a debug export — would be outside every pin above, so
        //     the literal is declared in SpeakerSpike.kt and resolved only by the store.
        val namers = mainSources
            .filter { (_, body) -> body.contains("= \"${SpeakerSpike.DIR_NAME}\"") }
            .map { it.first }
            .sorted()
        assertEquals("the dump directory's name is a literal in one file. Found: $namers", listOf("SpeakerSpike.kt"), namers)
        val resolvers = mainSources
            .filter { (_, body) -> body.contains("SpeakerSpike.DIR_NAME") }
            .map { it.first }
            .sorted()
        assertEquals("…and only the store turns it into a File. Found: $resolvers", listOf("SpeakerSpikeStore.kt"), resolvers)
    }

    @Test
    fun thePurgeIsTheONEHalfThatIsNOTBehindTheConstantAndRunsAtEveryLaunch() {
        // The disarm has one gap and this closes it: the 24 h sweep lived inside the dump's
        // open(), on the way to writing a line, so turning the writer off turned the deleter off
        // with it. A phone that ran a spike build off the internal track would keep its WAVs for
        // good. A deletion may not be gated on the switch that produced the thing deleted.
        assertTrue(
            "the settings tap must purge with no mention of the constant",
            prefs.contains("if (!value) SpeakerSpikeStore.purgeAsync(context)"),
        )
        assertEquals(
            "…and PreferencesManager must not read SPEAKER_SPIKE at all any more — the old line " +
                "was `!value && SpeakerSpike.SPEAKER_SPIKE`, which is exactly the bug",
            0,
            count(prefs, "SPEAKER_SPIKE"),
        )
        // The launch call is what reaches a user who never taps that switch — which is every
        // user, since detection defaults ON and the dump only ever existed on the owner's own
        // devices. Application.onCreate: once per process, wrapped, off Main.
        assertTrue(
            "WhisperEverywhereApp.onCreate must purge the dump at every process start",
            app.contains("runCatching { SpeakerSpikeStore.purgeAsync(this) }"),
        )
        assertEquals("…and it must not read the constant either", 0, count(app, "SPEAKER_SPIKE"))
        val onCreate = between(app, "override fun onCreate() {", "private fun configureFastRpcLibraryPath", APP)
        assertTrue("…and the call is inside onCreate, not merely in the file", onCreate.contains("SpeakerSpikeStore.purgeAsync(this)"))
        // The store's own half, likewise unguarded, and the dirs resolved ON the thread:
        // getExternalFilesDir is not a getter — it touches the volume and creates the directory —
        // and both callers are on Main, one of them inside cold start.
        assertEquals("the store may not gate a deletion either", 0, count(store, "SPEAKER_SPIKE"))
        val purgeAsync = between(store, "fun purgeAsync(context: Context) {", "thread.isDaemon", STORE)
        assertTrue(
            "the directories must be resolved inside the thread, not on the caller's",
            purgeAsync.indexOf("Thread(") < purgeAsync.indexOf("internalDir("),
        )
    }

    @Test
    fun theStandingWarningIsKEPTAsHistoryAndTheFileSaysHowToReArmTheMechanism() {
        // The warning is not deleted with the arming. It is the CONDITION on ever turning the
        // audio half on again, and the next round to open this file — the one that re-arms it for
        // a sixth candidate model — has to be told that in the file itself.
        for (phrase in listOf(
            "SPIKE-ONLY DIAGNOSTIC",
            "off by default",
            "STORES SPEECH AUDIO",
            "audio is deliberately never retained",
            "must be removed or put behind explicit user consent before any production release",
        )) {
            assertTrue("SpeakerSpike.kt must keep the standing warning: <<$phrase>>", spike.contains(phrase))
        }
        // …and beside it, the state it is now in and the one edit that undoes it. Without this
        // the file reads as an outstanding debt rather than a paid one, and the next reader
        // deletes the mechanism the next model change needs.
        for (phrase in listOf(
            "THE MECHANISM IS DISARMED",
            "DISARMED rather than deleted",
            "set [SPEAKER_SPIKE] back to `true` and rebuild",
            "DUMP_AUDIO",
        )) {
            assertTrue("SpeakerSpike.kt must state: <<$phrase>>", spike.contains(phrase))
        }
    }

    @Test
    fun theAudioFlagIsAFileUnderExternalFilesDirAndNotAPreferenceAUserCouldTurnOn() {
        // No preference, no Settings row, no receiver: the switch is a file only `adb` creates.
        // getExternalFilesDir is the one app-private directory a shell can write WITHOUT `run-as`,
        // which is what makes it usable at all on the non-debuggable internal-track builds the
        // owner installs. Kept asserted with the spike disarmed, because the re-arm must not be
        // able to bring a user-reachable switch back with it.
        assertTrue(spike.contains("const val AUDIO_FLAG: String = \"DUMP_AUDIO\""))
        assertTrue(store.contains("context.getExternalFilesDir(null)"))
        assertEquals(
            "there is no dumpSpeakerAudio preference, and there must not be one",
            0,
            count(prefs, "dumpSpeakerAudio"),
        )
        assertEquals(0, count(collapsed(SETTINGS), "DUMP_AUDIO"))
        assertEquals(0, count(collapsed(SETTINGS), "SpeakerSpike"))
        // A manifest component would be a second, permanent way in — the flag file is the whole
        // mechanism and it disappears with the directory.
        assertEquals(0, count(collapsed("src/main/AndroidManifest.xml"), "Spike"))
    }

    // ------------------------------------------------------------------ 2. the thread pin

    @Test
    fun theWriterRunsOnTheSpeakerEmbedExecutorAndNowhereElse() {
        // `fingerprint` is the body the executor runs (the file's own "on the embed thread"
        // section), and both the per-row write and the per-chunk flush are inside it.
        val onTheEmbedThread = between(assigner, "private fun fingerprint(", "private var hasEmbedded", ASSIGNER)
        assertTrue("the row is written there", onTheEmbedThread.contains("spikeDump()?.write("))
        assertTrue("…and the chunk is flushed there", onTheEmbedThread.contains("dump?.flush()"))
        assertEquals("ONE write site in the whole assigner", 1, count(assigner, "spikeDump()?.write("))
        assertEquals("ONE flush site", 1, count(assigner, "dump?.flush()"))

        // `assign` is called on LocalWhisperEngine's native executor — the whisper thread. It may
        // only QUEUE: a write or a flush there would sit inside the commit floors of spec §3.3.
        val queueOnly = between(
            assigner,
            "fun assign(seq: Long, samples: FloatArray, windows: List<SpeakerWindow>) {",
            "Queues one committed chunk that arrived with NO GEOMETRY",
            ASSIGNER,
        )
        assertEquals("assign() never touches the dump", 0, count(queueOnly, "dump"))
        assertEquals("…it only hands the chunk over", 1, count(queueOnly, "executor.execute {"))

        // …and so is the NPU route's own entry point (4.10, the Fold6 defect), which is reached
        // from the SAME whisper thread. It does MORE work than `assign` — a second ~60 ms VAD
        // pass and the window building — and every bit of it is inside the queued task, which is
        // the only reason the commit floors are untouched by a tier that has no geometry.
        val npuQueueOnly = between(
            assigner,
            "fun assignWholeChunk(seq: Long, samples: FloatArray, vadModelPath: String) {",
            "* Blocks the CALLING thread",
            ASSIGNER,
        )
        assertEquals("assignWholeChunk() never touches the dump", 0, count(npuQueueOnly, "dump"))
        assertEquals("…it only hands the chunk over", 1, count(npuQueueOnly, "executor.execute {"))
        assertTrue(
            "the VAD runs INSIDE the queued task, never on the caller's thread",
            npuQueueOnly.indexOf("executor.execute {") < npuQueueOnly.indexOf("segmenter("),
        )

        // The stop-tap fence is the THIRD caller of this executor (4.10 — the service waits for
        // the last chunk's ids before it snapshots the runs), and it is held to the same rule:
        // a barrier task and nothing else. A write or a flush here would run the dump from the
        // finalize coroutine's IO thread instead of the embed thread this section is about.
        val fence = between(
            assigner,
            "fun awaitIdle(timeoutMs: Long): Boolean {",
            "* Ends this assigner.",
            ASSIGNER,
        )
        assertEquals("the fence never touches the dump", 0, count(fence, "dump"))
        assertEquals("…it only queues a barrier", 1, count(fence, "executor.execute {"))

        // The close is queued on the SAME executor, so it can never run underneath a write: the
        // last chunk of a session is already queued behind the user's stop tap.
        val release = between(assigner, "fun release() {", "// ---", ASSIGNER)
        val queued = at(release, "executor.execute {", "release()")
        val closed = at(release, "dump?.close()", "release()")
        assertTrue("the close is INSIDE the queued task", queued < closed)
        assertTrue("…and the executor is shut down after it is queued", release.indexOf("executor.shutdown()") > closed)
    }

    @Test
    fun nothingInTheDumpEverHopsToMainOrSpawnsAThreadOfItsOwn() {
        // The one exception is the purge, which is explicitly its own daemon thread because both
        // its callers are on Main — a Settings tap, and Application.onCreate; it is in the store,
        // not in the writer.
        for (symbol in listOf("Handler", "Looper", "Dispatchers", "runOnUiThread", "Thread(")) {
            assertEquals("SpeakerSpike.kt must not mention $symbol", 0, count(spike, symbol))
        }
        assertEquals("the purge's own thread, and only that one", 1, count(store, "Thread("))
        assertTrue(store.contains("\"speaker-spike-purge\""))
    }

    @Test
    fun theWriterHalfIsPureJavaIoSoTheJvmSuiteCanTestEveryRuleInIt() {
        // Android lives in SpeakerSpikeStore alone: the dirs, and nothing with a rule in it.
        assertEquals(0, count(spike, "import android."))
        assertEquals(0, count(spike, "Context"))
        assertEquals("…and the assigner stays as pure as its own pin test says", 0, count(assigner, "import android."))
        assertTrue(store.contains("import android.content.Context"))
    }

    @Test
    fun aWriteCanNeverThrowOnTheSessionsOnlyEmbedThread() {
        // An exception escaping a task there costs the session its embed thread, for a LABEL, on a
        // path whose text has already been delivered. Every file operation is wrapped. Asserted
        // with the spike disarmed because these are the lines a re-arm switches back on, and a
        // re-arm is one edit that re-reads none of them.
        for (call in listOf(
            "runCatching { primary?.write(line); primary?.write(\"\\n\") }",
            "runCatching { file.writeBytes(SpikeWav.mono16(audio)) }",
            "runCatching { file.delete() }",
        )) {
            assertTrue("unwrapped file call: <<$call>>", spike.contains(call))
        }
        assertTrue(assigner.contains("runCatching { dump?.flush() }"))
        assertTrue(assigner.contains("runCatching { dump?.close() }"))
    }

    // ------------------------------------------------------------------ 3. the storage rules

    @Test
    fun aDumpAgesOutInTwentyFourHoursAndGoesEntirelyWithTheSwitch() {
        assertTrue(spike.contains("const val MAX_AGE_MS: Long = 24L * 60L * 60L * 1_000L"))
        // The sweep is at session start, inside the open — the one moment already paying for I/O.
        // It is also the reason the purge above had to become unconditional: this sweep is behind
        // the writer's guard, so the disarm took it with it.
        val open = between(spike, "private fun open(dim: Int) {", "private fun writer(", SPIKE)
        assertTrue("the sweep runs before the first line is written", open.indexOf("sweep()") < open.indexOf("primary = writer("))
        // And a purge may only ever delete the two dump extensions: the flag file is the
        // controller's switch, and a purge that disarmed it would look like a dumper bug.
        assertTrue(spike.contains("val DUMP_EXTENSIONS: List<String> = listOf(\"jsonl\", \"wav\")"))
        assertEquals(
            "one declaration and its two readers — the purge and the sweep. A third reader is a " +
                "third rule about what may be deleted.",
            3,
            count(spike, "DUMP_EXTENSIONS"),
        )
    }

    @Test
    fun theDumpsFilesAreDeclaredInputsOfTheTestTask() {
        // Every pin above is an ORDER, ZERO-count, literal or KDoc-phrase claim — the shape that
        // compiles to a byte-identical class, so without these entries the one edit each pin
        // exists to catch is the one that leaves :app:testDebugUnitTest UP-TO-DATE.
        for (path in listOf(SPIKE, STORE, ASSIGNER, SERVICE, PREFS, APP)) {
            assertTrue("app/build.gradle.kts must list \"$path\" in sourcePinnedInputs", buildFile.contains("\"$path\""))
        }
    }

    private companion object {
        const val SPIKE = "src/main/java/com/whispereverywhere/transcription/speakers/SpeakerSpike.kt"
        const val STORE = "src/main/java/com/whispereverywhere/transcription/speakers/SpeakerSpikeStore.kt"
        const val ASSIGNER = "src/main/java/com/whispereverywhere/transcription/speakers/SpeakerAssigner.kt"
        const val SERVICE = "src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt"
        const val PREFS = "src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt"
        const val APP = "src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt"
        const val SETTINGS = "src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt"
    }
}
