package com.whispereverywhere.transcription.speakers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * THE SPIKE DUMP's FORMATS AND ITS FILES (4.10 spike session 2) — the jsonl line, the WAV header,
 * the flag, the sweep.
 *
 * These are instruments, and the reader is a Python tuning loop on the PC that will be handed
 * three sessions' worth of embeddings and asked to answer a question the owner cannot be asked
 * again cheaply: which of the five changes in `docs/measurements/2026-09-18-speaker-spike.md`
 * separates the same-speaker tail (0.04-0.45) from the cross-speaker range (0.33-0.42). Every
 * assertion here guards one way a dump can be silently unusable rather than obviously broken:
 *
 *  - **A decimal comma.** `String.format` without a [Locale] takes the device's default, and the
 *    owner's own devices are not guaranteed to be `en`. `0,1234` is not a JSON number, it is two,
 *    and a file of 192-float rows fails to parse at line 2 with a message about line 2.
 *  - **`NaN` in a JSON file.** `best` is genuinely absent for the first speaker of a session
 *    (nobody to be compared with) and NaN is not in the strict grammar. `null` is.
 *  - **A wrong WAV header.** A wrong `dataSize` truncates the tail, a wrong `byteRate` shifts the
 *    pitch: either makes WeSpeaker's embeddings on this audio (spike plan item 5) meaningless
 *    without looking wrong.
 *  - **A transcript.** There is no text field and there cannot be one — the assertion is on the
 *    RENDERED line, not only on the type, because a future field would be added to both.
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

    private fun le32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun ascii(bytes: ByteArray, at: Int, length: Int) =
        String(bytes, at, length, Charsets.US_ASCII)

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
                    session = 1L, model = "m", dim = 192,
                    tSame = 0.55f, tNew = 0.45f,
                    minEmbed = 1.0f, minMatch = 1.0f, minOpen = 2.0f, minUpdate = 2.0f,
                    recentK = 5, confirmN = 2, cap = 8,
                    longSegment = 5.0f, minWindow = 1.5f,
                    reclusterSim = 0.4f, minClusterSeconds = 6.0f, reclusterEvery = 5,
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
        // whole 192-float row unparseable.
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
    fun aOneHundredAndNinetyTwoFloatRowIsOneLineOfTenKeysAndOneHundredAndNinetyTwoNumbers() {
        // 192 is TitaNet-small's width, read off the graph's own `embs` output and re-derived from
        // the asset bytes by the adapter's own pin test. It was 512 while CAM++ was bundled.
        val line = SpikeJson.line(record(emb = FloatArray(192) { it / 1_000f }))
        assertFalse("one object per line, and no line breaks inside it", line.contains("\n"))
        val vector = line.substringAfter("\"emb\":[").substringBefore("]")
        assertEquals(192, vector.split(",").size)
        assertEquals("0.0000", vector.split(",").first())
        assertEquals("0.1910", vector.split(",").last())
    }

    @Test
    fun theHeaderNamesTheModelTheWidthAndTheBandTheSessionActuallyRanUnder() {
        // A tuning run against a jsonl whose RULES nobody recorded is a measurement of an unknown
        // build, which is the one thing a spike cannot afford twice — and session 2 changed four
        // rules at once, so the band alone no longer identifies a build.
        //
        // `longSegment` and `minWindow` are the session-4 pair, and they are not decoration: a row
        // is one fingerprint WINDOW now, so those two rules are what decide whether `seg`/`durSec`
        // /`origStart`/`origEnd` describe a whole VAD segment or a slice of one. A dump without
        // them is header-identical to a session-3 dump whose columns meant something else.
        //
        // They read the constants, so this assertion moves with them — and it MUST, because the
        // 2026-09-18 late session moved them (5.0/1.5 -> 2.0/1.0) on the strength of the 02:12
        // dump's own window-length distribution. A jsonl from before that change and one from
        // after it describe two different partitions of the same audio, and the header is the
        // only place a later reader can tell which one it is holding.
        //
        // `reclusterSim` / `minClusterSeconds` / `reclusterEvery` are session 6's three, and they
        // change what the `assigned` COLUMN means: under a reclustering build the tracker is
        // re-seeded from a retrospective pass partway through the session, so the online id a row
        // carries was decided by a tracker that had already been corrected. A dump with no record
        // of that is indistinguishable from an online-only dump whose ids drifted on their own,
        // which is exactly the comparison session 6 exists to make.
        assertEquals(
            "{\"session\":1737000000000,\"model\":\"speaker_titanet_small_16k.onnx\",\"dim\":192," +
                "\"tSame\":0.5000,\"tNew\":0.3000,\"minEmbed\":1.0000,\"minMatch\":1.0000," +
                "\"minOpen\":1.5000,\"minUpdate\":2.0000," +
                "\"recentK\":5,\"confirmN\":2,\"cap\":8," +
                "\"longSegment\":2.0000,\"minWindow\":1.0000," +
                "\"reclusterSim\":0.3000,\"minClusterSeconds\":6.0000,\"reclusterEvery\":5}",
            SpikeJson.header(
                session = 1_737_000_000_000L,
                model = SpeakerSpike.MODEL_ASSET,
                dim = 192,
                tSame = SpeakerTracker.T_SAME,
                tNew = SpeakerTracker.T_NEW,
                minEmbed = SpeakerTracker.MIN_EMBED_SECONDS,
                minMatch = SpeakerTracker.MIN_MATCH_SECONDS,
                minOpen = SpeakerTracker.MIN_OPEN_SECONDS,
                minUpdate = SpeakerTracker.MIN_UPDATE_SECONDS,
                recentK = SpeakerTracker.RECENT_K,
                confirmN = SpeakerTracker.CONFIRM_N,
                cap = SpeakerTracker.MAX_SPEAKERS,
                longSegment = SpeakerSpans.LONG_SEGMENT_SECONDS,
                minWindow = SpeakerSpans.MIN_WINDOW_SECONDS,
                reclusterSim = SpeakerReclusterer.RECLUSTER_SIM,
                minClusterSeconds = SpeakerReclusterer.MIN_CLUSTER_SECONDS,
                reclusterEvery = SpeakerReclusterer.RECLUSTER_EVERY_CHUNKS,
            ),
        )
    }

    // ------------------------------------------------------------------ the WAV

    @Test
    fun theWavHeaderIsSixteenKilohertzMonoSixteenBitAndItsLengthsAgree() {
        val pcm = floatArrayOf(0f, 0.5f, -0.5f, 1f)
        val wav = SpikeWav.mono16(pcm)
        assertEquals("44 bytes of header plus 2 per sample", SpikeWav.HEADER_BYTES + 8, wav.size)
        assertEquals("RIFF", ascii(wav, 0, 4))
        assertEquals("everything after this field", wav.size - 8, le32(wav, 4))
        assertEquals("WAVE", ascii(wav, 8, 4))
        assertEquals("fmt ", ascii(wav, 12, 4))
        assertEquals("a PCM fmt body is 16 bytes", 16, le32(wav, 16))
        assertEquals("audioFormat 1 = uncompressed PCM", 1, le16(wav, 20))
        assertEquals("mono", 1, le16(wav, 22))
        assertEquals(16_000, le32(wav, 24))
        assertEquals("byteRate = rate * channels * 2", 32_000, le32(wav, 28))
        assertEquals("blockAlign = channels * 2", 2, le16(wav, 32))
        assertEquals(16, le16(wav, 34))
        assertEquals("data", ascii(wav, 36, 4))
        assertEquals("…and the data size is the one that follows it", 8, le32(wav, 40))
    }

    @Test
    fun theSamplesAreSignedSixteenBitLittleEndianAndAPeakPastOneIsClampedNotWrapped() {
        // whisper's buffers can carry a sample a hair past 1.0. An unclamped toInt() on 1.00003
        // wraps a positive peak to a large NEGATIVE sample: an audible click a listener would
        // blame on the recording rather than on this function.
        val wav = SpikeWav.mono16(floatArrayOf(1f, -1f, 2f, -2f, 0f))
        fun sample(i: Int) = le16(wav, SpikeWav.HEADER_BYTES + i * 2).toShort().toInt()
        assertEquals(32_767, sample(0))
        assertEquals(-32_767, sample(1))
        assertEquals("clamped, not wrapped", 32_767, sample(2))
        assertEquals("clamped, not wrapped", -32_767, sample(3))
        assertEquals(0, sample(4))
        // A non-finite sample is silence rather than a garbage peak.
        assertEquals(0, le16(SpikeWav.mono16(floatArrayOf(Float.NaN)), SpikeWav.HEADER_BYTES).toShort().toInt())
    }

    @Test
    fun anEmptySliceIsAValidHeaderWithNoData() {
        val wav = SpikeWav.mono16(FloatArray(0))
        assertEquals(SpikeWav.HEADER_BYTES, wav.size)
        assertEquals(0, le32(wav, 40))
        assertEquals("RIFF", ascii(wav, 0, 4))
    }

    // ------------------------------------------------------------------ the files

    @Test
    fun theFirstWriteOpensOneJsonlWithAHeaderLineAndMirrorsItWhereAdbCanReachIt() {
        val internal = tempDir("internal")
        val external = tempDir("external")
        val dump = dumpInto(internal, external, session = 555L)

        assertFalse("nothing is created before the first fingerprint", File(internal, "555.jsonl").exists())
        dump.write(record(seq = 1, seg = 0), audio = floatArrayOf(0.1f, 0.2f))
        dump.write(record(seq = 1, seg = 1, best = Float.NaN), audio = null)
        dump.flush()
        dump.close()

        for (dir in listOf(internal, external)) {
            val lines = File(dir, "555.jsonl").readLines()
            assertEquals("a header and two rows in ${dir.name}", 3, lines.size)
            assertTrue(lines[0].startsWith("{\"session\":555,"))
            assertTrue(lines[1].startsWith("{\"seq\":1,\"seg\":0,"))
            assertTrue(lines[2].contains("\"best\":null"))
        }
        assertFalse("no audio without the flag", external.listFiles()!!.any { it.extension == "wav" })
    }

    @Test
    fun theWavsAppearOnlyWhenTheFlagFileIsThereAndAreNamedSessionSeqSeg() {
        val internal = tempDir("internal")
        val external = tempDir("external")
        File(external, SpeakerSpike.AUDIO_FLAG).writeText("")
        val dump = dumpInto(internal, external, session = 777L)
        dump.write(record(seq = 4, seg = 2), audio = floatArrayOf(0.25f, -0.25f))
        dump.close()

        assertTrue("the flag arms the session", dump.audioArmed)
        val wav = File(external, "777-4-2.wav")
        assertTrue("the slice is beside the jsonl the tuner pulls", wav.isFile)
        assertArrayEquals(SpikeWav.mono16(floatArrayOf(0.25f, -0.25f)), wav.readBytes())
        assertFalse("audio never goes to the internal dir", File(internal, "777-4-2.wav").exists())
    }

    @Test
    fun theFlagIsReadONCEPerSessionSoAudioNeverStartsAppearingAtChunkNine() {
        val internal = tempDir("internal")
        val external = tempDir("external")
        val dump = dumpInto(internal, external, session = 888L)
        dump.write(record(seq = 1, seg = 0), audio = floatArrayOf(0.5f))
        // The controller touches the flag mid-session: nothing changes until the NEXT session.
        File(external, SpeakerSpike.AUDIO_FLAG).writeText("")
        dump.write(record(seq = 2, seg = 0), audio = floatArrayOf(0.5f))
        dump.close()
        assertFalse(dump.audioArmed)
        assertEquals(0, external.listFiles()!!.count { it.extension == "wav" })
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
        val oldWav = File(external, "1-0-0.wav").apply { writeText("x"); setLastModified(stale) }
        val flag = File(external, SpeakerSpike.AUDIO_FLAG).apply { writeText(""); setLastModified(stale) }

        dumpInto(internal, external, session = 999L, now = { now })
            .write(record(), audio = null)

        assertFalse("a stale jsonl goes", old.exists())
        assertFalse("a stale wav goes", oldWav.exists())
        assertTrue("one inside the window stays", recent.exists())
        assertTrue("the controller's switch is not a dump and is never swept", flag.exists())
        assertTrue("…and this session's own file was written", File(internal, "999.jsonl").isFile)
    }

    @Test
    fun turningDetectionOffPurgesEveryDumpInBothDirsAndLeavesTheFlagAndAnythingElseAlone() {
        val internal = tempDir("internal")
        val external = tempDir("external")
        File(internal, "1.jsonl").writeText("x")
        File(external, "1.jsonl").writeText("x")
        File(external, "1-0-0.wav").writeText("x")
        val flag = File(external, SpeakerSpike.AUDIO_FLAG).apply { writeText("") }
        val stranger = File(external, "notes.txt").apply { writeText("x") }

        assertEquals(3, SpeakerSpike.purge(internal, external))
        assertEquals(0, internal.listFiles()!!.size)
        assertTrue(flag.exists())
        assertTrue("a purge deletes dumps, not a directory", stranger.exists())
        assertEquals("…and it is idempotent", 0, SpeakerSpike.purge(internal, external))
        assertEquals("…and survives a null external dir", 0, SpeakerSpike.purge(internal, null))
    }

    @Test
    fun aSessionWithNoExternalDirStillWritesItsJsonlAndNoAudioAtAll() {
        // getExternalFilesDir returns null on an unmounted volume, which is an ordinary state.
        val internal = tempDir("internal")
        val dump = dumpInto(internal, null, session = 1_234L)
        dump.write(record(), audio = floatArrayOf(0.5f, 0.5f))
        dump.close()
        assertFalse("the flag cannot be found, so audio is off", dump.audioArmed)
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
        tSame = SpeakerTracker.T_SAME,
        tNew = SpeakerTracker.T_NEW,
        minEmbed = SpeakerTracker.MIN_EMBED_SECONDS,
        minMatch = SpeakerTracker.MIN_MATCH_SECONDS,
        minOpen = SpeakerTracker.MIN_OPEN_SECONDS,
        minUpdate = SpeakerTracker.MIN_UPDATE_SECONDS,
        recentK = SpeakerTracker.RECENT_K,
        confirmN = SpeakerTracker.CONFIRM_N,
        cap = SpeakerTracker.MAX_SPEAKERS,
        longSegment = SpeakerSpans.LONG_SEGMENT_SECONDS,
        minWindow = SpeakerSpans.MIN_WINDOW_SECONDS,
        reclusterSim = SpeakerReclusterer.RECLUSTER_SIM,
        minClusterSeconds = SpeakerReclusterer.MIN_CLUSTER_SECONDS,
        reclusterEvery = SpeakerReclusterer.RECLUSTER_EVERY_CHUNKS,
        nowMs = now,
    )
}
