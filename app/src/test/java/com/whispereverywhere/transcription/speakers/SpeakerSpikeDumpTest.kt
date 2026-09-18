package com.whispereverywhere.transcription.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * THE SPIKE DUMP's FORMAT AND ITS FILES (4.10 spike session 2) — the jsonl line, the mirror,
 * the sweep and the purge.
 *
 * These are instruments, and the reader is a Python tuning loop on the PC that will be handed
 * three sessions' worth of embeddings and asked to answer a question the owner cannot be asked
 * again cheaply: which of the five changes in `docs/measurements/2026-09-18-speaker-spike.md`
 * separates the same-speaker tail (0.04-0.45) from the cross-speaker range (0.33-0.42). Every
 * assertion here guards one way a dump can be silently unusable rather than obviously broken:
 *
 *  - **A decimal comma.** `String.format` without a [Locale] takes the device's default, and the
 *    owner's own devices are not guaranteed to be `en`. `0,1234` is not a JSON number, it is two,
 *    and a file of 512-float rows fails to parse at line 2 with a message about line 2.
 *  - **`NaN` in a JSON file.** `best` is genuinely absent for the first speaker of a session
 *    (nobody to be compared with) and NaN is not in the strict grammar. `null` is.
 *  - **A transcript.** There is no text field and there cannot be one — the assertion is on the
 *    RENDERED line, not only on the type, because a future field would be added to both.
 *
 * The last section is a SOURCE pin, the `SpeakerWiringPinTest` way (whitespace-collapsed,
 * symbol-scoped needles inside their declaring bodies, never line numbers), because the two claims
 * it holds cannot be executed: that every line of this machinery sits behind the `SPEAKER_SPIKE`
 * compile-time constant, and that the writing happens on the `speaker-embed` executor and nowhere
 * else. A flush moved into `SpeakerAssigner.assign` — which is called on `LocalWhisperEngine`'s
 * native executor, the whisper thread — would put file I/O inside the commit floors spec §3.3
 * measured without it, and the only symptom would be a commit cadence that drifts on some devices.
 */
class SpeakerSpikeDumpTest {

    // ------------------------------------------------------------------ fixtures

    private fun record(
        seq: Long = 12L,
        seg: Int = 1,
        durSec: Float = 2.5f,
        origStart: Int = 1_600,
        origEnd: Int = 41_600,
        assigned: Int = 2,
        best: Float = 0.31234f,
        confirmed: Boolean = true,
        embedMs: Long = 214L,
        emb: FloatArray = floatArrayOf(0.1f, -0.25f, 0.00004f),
    ) = SpikeFingerprint(
        seq = seq,
        seg = seg,
        durSec = durSec,
        origStart = origStart,
        origEnd = origEnd,
        assigned = assigned,
        best = best,
        confirmed = confirmed,
        embedMs = embedMs,
        emb = emb,
    )

    private fun tempDir(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "we-spike-$name-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    // ------------------------------------------------------------------ the jsonl line

    @Test
    fun oneFingerprintRendersAsExactlyThisJsonObject() {
        assertEquals(
            "{\"seq\":12,\"seg\":1,\"durSec\":2.5000,\"origStart\":1600,\"origEnd\":41600," +
                "\"assigned\":2,\"best\":0.3123,\"confirmed\":1," +
                "\"emb\":[0.1000,-0.2500,0.0000],\"embedMs\":214}",
            SpikeJson.line(record()),
        )
    }

    @Test
    fun everyFloatCarriesFourDecimalsUnderLocaleROOTWhateverTheDeviceIsSetTo() {
        // A German device is the concrete hazard: `%.4f` under its default writes `0,1000`.
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val line = SpikeJson.line(record())
            assertFalse("no decimal comma anywhere in the row", line.contains(','.toString() + "5000"))
            assertTrue(line.contains("\"durSec\":2.5000"))
            assertTrue(line.contains("[0.1000,-0.2500,0.0000]"))
            assertTrue(
                "…and the header is written by the same formatter",
                SpikeJson.header(
                    session = 1L, model = "m", dim = 512,
                    tSame = 0.55f, tNew = 0.45f, minEmbed = 1.0f, minNew = 1.5f, cap = 8,
                ).contains("\"tSame\":0.5500"),
            )
        } finally {
            Locale.setDefault(was)
        }
    }

    @Test
    fun aSimilarityThatWasNeverMeasuredIsNullAndNeverNaNAndNeverZero() {
        // The first speaker of a session has nobody to be compared with, so `best` is NaN in the
        // assigner and must not borrow 0's glyph: 0 is a real reading (two orthogonal voices).
        val line = SpikeJson.line(record(best = Float.NaN))
        assertTrue(line.contains("\"best\":null"))
        assertFalse("NaN is not JSON", line.contains("NaN"))
        assertFalse(line.contains("\"best\":0.0000"))
        // The same rule applies inside the vector, so one non-finite component cannot make a
        // whole 512-float row unparseable.
        assertTrue(
            SpikeJson.line(record(emb = floatArrayOf(1f, Float.NaN, Float.NEGATIVE_INFINITY)))
                .contains("\"emb\":[1.0000,null,null]"),
        )
    }

    @Test
    fun theRowCarriesNotOneCharacterOfTranscript() {
        // The type has no text field (SpikeFingerprint), and the RENDERED line is asserted too: a
        // field added to the type in some later round would otherwise be added to both at once.
        val line = SpikeJson.line(record())
        assertFalse(line.contains("text"))
        val keys = Regex("\"([a-zA-Z]+)\"").findAll(line).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("seq", "seg", "durSec", "origStart", "origEnd", "assigned", "best", "confirmed", "emb", "embedMs"),
            keys,
        )
    }

    @Test
    fun aFiveHundredAndTwelveFloatRowIsOneLineOfTenKeysAndFiveHundredAndTwelveNumbers() {
        val line = SpikeJson.line(record(emb = FloatArray(512) { it / 1_000f }))
        assertFalse("one object per line, and no line breaks inside it", line.contains("\n"))
        val vector = line.substringAfter("\"emb\":[").substringBefore("]")
        assertEquals(512, vector.split(",").size)
        assertEquals("0.0000", vector.split(",").first())
        assertEquals("0.5110", vector.split(",").last())
    }

    @Test
    fun theHeaderNamesTheModelTheWidthAndTheBandTheSessionActuallyRanUnder() {
        // A tuning run against a jsonl whose band nobody recorded is a measurement of an unknown
        // build, which is the one thing a spike cannot afford twice.
        assertEquals(
            "{\"session\":1737000000000,\"model\":\"speaker_campplus_en_16k.onnx\",\"dim\":512," +
                "\"tSame\":0.5500,\"tNew\":0.4500,\"minEmbed\":1.0000,\"minNew\":1.5000,\"cap\":8}",
            SpikeJson.header(
                session = 1_737_000_000_000L,
                model = SpeakerSpike.MODEL_ASSET,
                dim = 512,
                tSame = 0.55f,
                tNew = 0.45f,
                minEmbed = SpeakerTracker.MIN_EMBED_SECONDS,
                minNew = SpeakerTracker.MIN_NEW_SPEAKER_SECONDS,
                cap = SpeakerTracker.MAX_SPEAKERS,
            ),
        )
    }

    // ------------------------------------------------------------------ the files

    @Test
    fun theFirstWriteOpensOneJsonlWithAHeaderLineAndMirrorsItWhereAdbCanReachIt() {
        val internal = tempDir("internal")
        val external = tempDir("external")
        val dump = dumpInto(internal, external, session = 555L)

        assertFalse("nothing is created before the first fingerprint", File(internal, "555.jsonl").exists())
        dump.write(record(seq = 1, seg = 0))
        dump.write(record(seq = 1, seg = 1, best = Float.NaN))
        dump.flush()
        dump.close()

        for (dir in listOf(internal, external)) {
            val lines = File(dir, "555.jsonl").readLines()
            assertEquals("a header and two rows in ${dir.name}", 3, lines.size)
            assertTrue(lines[0].startsWith("{\"session\":555,"))
            assertTrue(lines[1].startsWith("{\"seq\":1,\"seg\":0,"))
            assertTrue(lines[2].contains("\"best\":null"))
        }
    }

    @Test
    fun aSessionStartSweepsDumpsOlderThanTwentyFourHoursAndNeverTheFlag() {
        val internal = tempDir("internal")
        val external = tempDir("external")
        val now = 10_000_000_000L
        val stale = now - SpeakerSpike.MAX_AGE_MS - 1
        val fresh = now - SpeakerSpike.MAX_AGE_MS + 60_000

        val old = File(internal, "1.jsonl").apply { writeText("x"); setLastModified(stale) }
        val recent = File(internal, "2.jsonl").apply { writeText("x"); setLastModified(fresh) }
        val oldMirror = File(external, "1.jsonl").apply { writeText("x"); setLastModified(stale) }
        val stranger = File(external, "notes.txt").apply { writeText("x"); setLastModified(stale) }

        dumpInto(internal, external, session = 999L, now = { now })
            .write(record())

        assertFalse("a stale jsonl goes", old.exists())
        assertFalse("…in the mirror too", oldMirror.exists())
        assertTrue("one inside the window stays", recent.exists())
        assertTrue("a file that is not a dump is never swept, whatever its age", stranger.exists())
        assertTrue("…and this session's own file was written", File(internal, "999.jsonl").isFile)
    }

    @Test
    fun turningDetectionOffPurgesEveryDumpInBothDirsAndLeavesAnythingElseAlone() {
        val internal = tempDir("internal")
        val external = tempDir("external")
        File(internal, "1.jsonl").writeText("x")
        File(external, "1.jsonl").writeText("x")
        File(external, "2.jsonl").writeText("x")
        val stranger = File(external, "notes.txt").apply { writeText("x") }

        assertEquals(3, SpeakerSpike.purge(internal, external))
        assertEquals(0, internal.listFiles()!!.size)
        assertTrue("a purge deletes dumps, and only dumps", stranger.exists())
        assertEquals("…and it is idempotent", 0, SpeakerSpike.purge(internal, external))
        assertEquals("…and survives a null external dir", 0, SpeakerSpike.purge(internal, null))
    }

    @Test
    fun aSessionWithNoExternalDirStillWritesItsJsonlToTheDumpsHome() {
        // getExternalFilesDir returns null on an unmounted volume, which is an ordinary state: the
        // mirror is simply absent and the primary carries the session on its own.
        val internal = tempDir("internal")
        val dump = dumpInto(internal, null, session = 1_234L)
        dump.write(record())
        dump.close()
        assertEquals(2, File(internal, "1234.jsonl").readLines().size)
        assertEquals(1, internal.listFiles()!!.size)
    }

    @Test
    fun closeIsSafeTwiceAndWithNothingEverWritten() {
        val internal = tempDir("internal")
        val dump = dumpInto(internal, null, session = 1L)
        dump.flush()
        dump.close()
        dump.close()
        assertEquals("a session that fingerprinted nothing leaves no file", 0, internal.listFiles()!!.size)
    }

    private fun dumpInto(
        internal: File,
        external: File?,
        session: Long,
        now: () -> Long = System::currentTimeMillis,
    ) = SpeakerSpikeDump(
        dirs = SpeakerSpikeDirs(sessionStartMs = session, internalDir = internal, externalDir = external),
        model = SpeakerSpike.MODEL_ASSET,
        tSame = 0.55f,
        tNew = 0.45f,
        minEmbed = SpeakerTracker.MIN_EMBED_SECONDS,
        minNew = SpeakerTracker.MIN_NEW_SPEAKER_SECONDS,
        cap = SpeakerTracker.MAX_SPEAKERS,
        nowMs = now,
    )

    // ------------------------------------------------------------------ the source pins

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

    private fun collapsed(relative: String) =
        source(relative).readText().replace("\r\n", "\n").replace(Regex("\\s+"), " ")

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

    private val spike by lazy { collapsed(SPIKE) }
    private val store by lazy { collapsed(STORE) }
    private val assigner by lazy { collapsed(ASSIGNER) }

    @Test
    fun theWriterRunsOnTheSpeakerEmbedExecutorAndNowhereElse() {
        // `fingerprint` is the body the executor runs (the assigner's own "on the embed thread"
        // section), and both the per-row write and the per-chunk flush are inside it.
        val onTheEmbedThread = between(assigner, "private fun fingerprint(", "private var hasEmbedded", ASSIGNER)
        assertTrue("the row is written there", onTheEmbedThread.contains("spikeDump()?.write("))
        assertTrue("\u2026and the chunk is flushed there", onTheEmbedThread.contains("dump?.flush()"))
        assertEquals("ONE write site in the whole assigner", 1, count(assigner, "spikeDump()?.write("))
        assertEquals("ONE flush site", 1, count(assigner, "dump?.flush()"))

        // `assign` may only QUEUE: it is called on LocalWhisperEngine's native executor, which is
        // the whisper thread, and spec 3.3's commit floors were measured with no file I/O on it.
        val queueOnly = between(
            assigner,
            "fun assign(seq: Long, samples: FloatArray, vad: List<VadSeg>) {",
            "* Ends this assigner.",
            ASSIGNER,
        )
        assertEquals("assign() never touches the dump", 0, count(queueOnly, "dump"))
        assertEquals("\u2026it only hands the chunk over", 1, count(queueOnly, "executor.execute {"))

        // The close is queued on the SAME executor, so it can never run underneath a write: the
        // last chunk of a session is already queued behind the user's stop tap.
        val release = between(assigner, "fun release() {", "// ---", ASSIGNER)
        val queued = at(release, "executor.execute {", "release()")
        val closed = at(release, "dump?.close()", "release()")
        assertTrue("the close is INSIDE the queued task", queued < closed)
        assertTrue("\u2026and the executor is shut down after it is queued", release.indexOf("executor.shutdown()") > closed)
    }

    @Test
    fun nothingInTheWriterEverHopsToMainOrSpawnsAThreadOfItsOwn() {
        // The one exception is the purge, whose caller is a Settings tap on Main; it lives in the
        // store, not in the writer, and says so by naming its thread.
        for (symbol in listOf("Handler", "Looper", "Dispatchers", "runOnUiThread", "Thread(")) {
            assertEquals("SpeakerSpike.kt must not mention $symbol", 0, count(spike, symbol))
        }
        assertEquals("the purge's own thread, and only that one", 1, count(store, "Thread("))
        assertTrue(store.contains("\"speaker-spike-purge\""))
    }

    @Test
    fun theWriterHalfIsPureJavaIoSoTheJvmSuiteCanTestEveryRuleInIt() {
        // Android lives in SpeakerSpikeStore alone: the two directories, and nothing with a rule.
        assertEquals(0, count(spike, "import android."))
        assertEquals(0, count(spike, "Context"))
        assertEquals("\u2026and the assigner stays as pure as its own pin test says", 0, count(assigner, "import android."))
        assertTrue(store.contains("import android.content.Context"))
    }

    @Test
    fun everyEntryIntoTheDumpIsBehindOneCompileTimeConstant() {
        assertTrue("the writer itself", spike.contains("if (!SpeakerSpike.SPEAKER_SPIKE) return"))
        assertTrue("the assigner's lazy build", assigner.contains("if (!SpeakerSpike.SPEAKER_SPIKE) return null"))
        assertTrue("the session's destination", collapsed(SERVICE).contains("spike = if (SpeakerSpike.SPEAKER_SPIKE) {"))
        assertTrue(
            "and the purge on the settings tap",
            collapsed(PREFS).contains("if (!value && SpeakerSpike.SPEAKER_SPIKE) SpeakerSpikeStore.purgeAsync(context)"),
        )
        assertTrue(
            "it is a `const`, which is what lets the compiler remove the guarded branches rather " +
                "than merely not take them",
            spike.contains("const val SPEAKER_SPIKE: Boolean = "),
        )
    }

    @Test
    fun aWriteCanNeverThrowOnTheSessionsOnlyEmbedThread() {
        // An exception escaping a task there costs the session its embed thread, for a LABEL, on a
        // path whose text has already been delivered. Every file operation is wrapped.
        assertTrue(spike.contains("runCatching { primary?.write(line); primary?.write(\"\\n\") }"))
        assertTrue(spike.contains("runCatching { file.delete() }"))
        assertTrue(assigner.contains("runCatching { dump?.flush() }"))
        assertTrue(assigner.contains("runCatching { dump?.close() }"))
    }

    @Test
    fun theDumpsFilesAreDeclaredInputsOfTheTestTask() {
        // Every pin above is an ORDER, ZERO-count or literal claim \u2014 the shape that compiles to a
        // byte-identical class, so without these entries the one edit each pin exists to catch is
        // the one that leaves :app:testDebugUnitTest UP-TO-DATE.
        val buildFile = collapsed("build.gradle.kts")
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
    }
}
