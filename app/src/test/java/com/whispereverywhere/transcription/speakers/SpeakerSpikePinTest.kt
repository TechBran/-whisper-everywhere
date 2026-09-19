package com.whispereverywhere.transcription.speakers

import com.whispereverywhere.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE SPIKE's TWO PROMISES, pinned (4.10 spike session 2).
 *
 * ### 1. The dump cannot reach the release
 *
 * The audio half of this dump stores SPEECH AUDIO on app-private external storage, which is the
 * one thing this product has always refused to do: audio is deliberately never retained. It exists
 * because item 5 of `docs/measurements/2026-09-18-speaker-spike.md` needs it — *"if CAM++ still
 * splits one voice after 1-4, try WeSpeaker ResNet34 / ERes2Net on the same dumped audio"* — and
 * there is no way to get a second model's embeddings out of the first model's vectors.
 *
 * So it is guarded by a compile-time constant, [SpeakerSpike.SPEAKER_SPIKE], and this class asserts
 * the pairing the `ReleaseIdentityTest` idiom exists for: **`!(SPEAKER_SPIKE && versionName ==
 * "4.10.0")`**. 4.10.0 is the release that ships speaker labels, so the build that ships them
 * cannot also ship the dump. The failure is a one-line fix — flip the constant to `false`, which
 * lets the compiler strip every call below it — and there is no other detector: a diagnostic that
 * works perfectly is exactly the kind of thing that ships.
 *
 * ### 2. The writing happens on the embed thread and nowhere else
 *
 * Neither `FloatingBubbleService` nor a real file write on a service's threads is reachable from
 * the JVM suite, so the confinement is pinned as SOURCE, the `SpeakerWiringPinTest` way:
 * whitespace-collapsed, symbol-scoped needles inside their declaring bodies, never line numbers.
 * The hazard is specific and it is not theoretical — a flush moved into `assign` (which is called
 * on `LocalWhisperEngine`'s native executor, the whisper thread) would put file I/O inside the
 * commit floors spec §3.3 measured without it, and the only symptom would be a commit cadence
 * that drifts on some devices.
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

    // ------------------------------------------------------------------ 1. the release pin

    @Test
    fun theDumpMechanismCannotStillExistInTheReleaseThatShipsSpeakerLabels() {
        assertFalse(
            "SpeakerSpike.SPEAKER_SPIKE is still true at versionName ${BuildConfig.VERSION_NAME}. " +
                "4.10.0 is the release that ships speaker labels, and the fingerprint/audio dump " +
                "is a SPIKE-ONLY DIAGNOSTIC: the audio half stores speech audio under " +
                "getExternalFilesDir, and this app's rule is that audio is deliberately never " +
                "retained. Before 4.10.0 ships, either delete the mechanism or put it behind " +
                "explicit user consent, and set SPEAKER_SPIKE = false — which lets the compiler " +
                "strip every guarded call rather than leaving a reachable file writer in the APK.",
            SpeakerSpike.SPEAKER_SPIKE && BuildConfig.VERSION_NAME == SHIPPING_VERSION,
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
            "SPEAKER_SPIKE must be declared exactly once, in one object, so that turning the " +
                "spike off is one edit with no second home to forget. Found: $declarers",
            listOf("SpeakerSpike.kt"),
            declarers,
        )
        assertTrue("it lives in `object SpeakerSpike`", spike.contains("object SpeakerSpike { /**"))
        assertTrue(
            "…and it is a `const`, not a `val`: a const is what lets the compiler remove the " +
                "guarded branches instead of merely not taking them",
            spike.contains("const val SPEAKER_SPIKE: Boolean = true") ||
                spike.contains("const val SPEAKER_SPIKE: Boolean = false"),
        )
    }

    @Test
    fun everyEntryIntoTheDumpIsBehindThatConstant() {
        // The four ways in, and each one checks the constant before it does anything at all.
        assertTrue("the writer itself", spike.contains("if (!SpeakerSpike.SPEAKER_SPIKE) return"))
        assertTrue(
            "the assigner's lazy build",
            assigner.contains("if (!SpeakerSpike.SPEAKER_SPIKE) return null"),
        )
        assertTrue(
            "the session's destination",
            service.contains("spike = if (SpeakerSpike.SPEAKER_SPIKE) {"),
        )
        assertTrue(
            "and the purge on the settings tap",
            prefs.contains("if (!value && SpeakerSpike.SPEAKER_SPIKE) SpeakerSpikeStore.purgeAsync(context)"),
        )
    }

    @Test
    fun theStandingWarningAboutStoringSpeechAudioIsWrittenWhereTheMechanismIs() {
        // The reason this is a pin and not a comment somebody trusts: the next round to touch this
        // file is the one that deletes the spike, and it has to be told why in the file itself.
        for (phrase in listOf(
            "SPIKE-ONLY DIAGNOSTIC",
            "off by default",
            "STORES SPEECH AUDIO",
            "audio is deliberately never retained",
            "must be removed or put behind explicit user consent before any production release",
        )) {
            assertTrue("SpeakerSpike.kt must state: <<$phrase>>", spike.contains(phrase))
        }
    }

    @Test
    fun theAudioFlagIsAFileUnderExternalFilesDirAndNotAPreferenceAUserCouldTurnOn() {
        // No preference, no Settings row, no receiver: the switch is a file only `adb` creates.
        // getExternalFilesDir is the one app-private directory a shell can write WITHOUT `run-as`,
        // which is what makes it usable at all on the non-debuggable internal-track builds the
        // owner installs.
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
            "* Blocks the CALLING thread",
            ASSIGNER,
        )
        assertEquals("assign() never touches the dump", 0, count(queueOnly, "dump"))
        assertEquals("…it only hands the chunk over", 1, count(queueOnly, "executor.execute {"))

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
        // The one exception is the purge, which is explicitly its own daemon thread because its
        // caller is a Settings tap on Main; it is in the store, not in the writer.
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
        // path whose text has already been delivered. Every file operation is wrapped.
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
        for (path in listOf(SPIKE, STORE, ASSIGNER, SERVICE, PREFS)) {
            assertTrue("app/build.gradle.kts must list \"$path\" in sourcePinnedInputs", buildFile.contains("\"$path\""))
        }
    }

    private companion object {
        const val SPIKE = "src/main/java/com/whispereverywhere/transcription/speakers/SpeakerSpike.kt"
        const val STORE = "src/main/java/com/whispereverywhere/transcription/speakers/SpeakerSpikeStore.kt"
        const val ASSIGNER = "src/main/java/com/whispereverywhere/transcription/speakers/SpeakerAssigner.kt"
        const val SERVICE = "src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt"
        const val PREFS = "src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt"
        const val SETTINGS = "src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt"

        /** The release that ships speaker labels, and the one the dump may not reach. */
        const val SHIPPING_VERSION = "4.10.0"
    }
}
