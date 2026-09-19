package com.whispereverywhere.transcription.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.sin

/**
 * [SpeakerAssigner] — the chunk's voices turned into speaker ids, OFF the whisper thread (4.10
 * Task 3, spec §3.2 steps 2-4 and §3.3).
 *
 * The embedder itself cannot be named by any test (sherpa's `SpeakerEmbeddingExtractor` loads
 * `libsherpa-onnx-jni.so` in its companion initialiser, and the adapter's own pin test asserts
 * that no test names it), so the assigner takes the [VoicePrints] SEAM and this class
 * drives a fake through it. That is not a compromise: everything the assigner decides is about
 * WHICH samples reach an embedder and WHAT is done with the answer, and both are visible from
 * here — including the two answers a real device will actually produce, `null` and a vector.
 *
 * ### What is asserted, and why each one is a real hazard
 *
 *  - **The work is off the caller's thread, on a thread named `speaker-embed`.** The caller is
 *    `LocalWhisperEngine`'s single native executor; a synchronous embed there would sit inside the
 *    commit path and move the floors spec §3.3 says are untouched. The thread NAME is asserted
 *    because it is what a device log and a trace are read by.
 *  - **A short segment is never fingerprinted** ([SpeakerTracker.MIN_EMBED_SECONDS]) and inherits
 *    the label before it — spec §3.2 step 2. Embedding 0.4 s of audio does not fail, it answers a
 *    vector with no speaker in it, which is the worst of the three outcomes.
 *  - **The merge pass runs once per chunk, after the loop**, and what it moved leaves on the same
 *    callback. This class is the only object that knows where a chunk ends, which is why the
 *    "after each chunk" of spike session 2 is a line HERE and a method on the tracker.
 *  - **A null embedding changes nothing.** The model may be missing, refused or corrupt; the
 *    session must then lose LABELS, never text and never a speaker id it already had.
 *  - **The slice comes from the ORIGINAL timeline and is clamped.** `origStart/origEnd` index the
 *    raw chunk; the geometry is a process-global snapshot (`WhisperNative.lastVadSegments`), so a
 *    stale one can name samples past the end of this buffer and must not throw on the executor.
 *  - **One stats entry per segment, whatever happened to it.** The spike's diag line is read as a
 *    table (plan Task 4 sets the thresholds off `best=`); a row that silently skips its segment
 *    misaligns every column against `ids=`.
 */
class SpeakerAssignerTest {

    // ------------------------------------------------------------------ fixtures

    /** A unit vector at [deg]: `cos(unit(a), unit(b))` is exactly `cos(a - b)`, as in the tracker's test. */
    private fun unit(deg: Double): FloatArray {
        val r = Math.toRadians(deg)
        return floatArrayOf(cos(r).toFloat(), sin(r).toFloat())
    }

    /**
     * The tracker test's MERGE fixture, so the two files exercise the pass on the same geometry:
     * five vectors at 75° from axis 0 and 60° apart around it, pairwise 0.533 (they chain into one
     * speaker) but only 0.259 from the axis itself, whose centroid they sit 0.801 from.
     */
    private fun cone(phi: Double): FloatArray {
        val t = Math.toRadians(75.0)
        val p = Math.toRadians(phi)
        return FloatArray(8).also {
            it[0] = cos(t).toFloat()
            it[1] = (sin(t) * cos(p)).toFloat()
            it[2] = (sin(t) * sin(p)).toFloat()
        }
    }

    private fun coneAxis(): FloatArray = FloatArray(8).also { it[0] = 1f }

    /** Samples enough for every segment any test here asks for. */
    private fun buffer(seconds: Float) = FloatArray((RATE * seconds).toInt()) { 0.1f }

    /** A VAD segment on the original timeline; the trimmed halves are irrelevant to this class. */
    private fun seg(startSec: Float, endSec: Float) = VadSeg(
        origStart = (startSec * RATE).toInt(),
        origEnd = (endSec * RATE).toInt(),
        trimmedStart = 0,
        trimmedEnd = 0,
    )

    /**
     * The seam's fake. Records the thread it was called on and the slice length it was handed —
     * the two things the assigner is responsible for and the embedder is not.
     */
    private class FakeVoices(private val answer: (Int) -> FloatArray?) : VoicePrints {
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val lengths: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val released = CountDownLatch(1)
        @Volatile var releasedOn: String? = null

        override fun embed(pcm: FloatArray, sampleRate: Int): FloatArray? {
            threads += Thread.currentThread().name
            lengths += pcm.size
            return answer(lengths.size - 1)
        }

        override fun release() {
            releasedOn = Thread.currentThread().name
            released.countDown()
        }
    }

    /**
     * Runs ONE chunk through a real assigner (real executor, real tracker unless one is passed)
     * and returns the assignment its callback carried, or null if none arrived inside [timeoutMs].
     */
    private fun assignOneChunk(
        voices: VoicePrints,
        samples: FloatArray,
        vad: List<VadSeg>,
        tracker: SpeakerTracker = SpeakerTracker(),
        seq: Long = 7L,
        clockNs: () -> Long = System::nanoTime,
        timeoutMs: Long = 5_000,
    ): SpeakerAssignment? {
        val arrived = CountDownLatch(1)
        val seen = AtomicReference<SpeakerAssignment?>(null)
        val assigner = SpeakerAssigner(
            voices = voices,
            onAssigned = { seen.set(it); arrived.countDown() },
            tracker = tracker,
            clockNs = clockNs,
        )
        try {
            assigner.assign(seq, samples, vad)
            arrived.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            assigner.release()
        }
        return seen.get()
    }

    // ------------------------------------------------------------------ the thread

    @Test
    fun theFingerprintingRunsOffTheCallersThreadOnOneNamedSpeakerEmbed() {
        val voices = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val caller = Thread.currentThread().name
        val assignment = assignOneChunk(
            voices = voices,
            samples = buffer(6f),
            vad = listOf(seg(0f, 2f), seg(3f, 5f)),
        )
        assertEquals(listOf(1, 2), assignment?.ids)
        assertEquals(2, voices.threads.size)
        for (thread in voices.threads) {
            assertEquals("the embed runs on the assigner's own thread", "speaker-embed", thread)
            assertFalse("never on the caller's (whisper's) thread", thread == caller)
        }
    }

    @Test
    fun releaseFreesTheModelOnThatSameThreadAfterTheQueuedWork() {
        val voices = FakeVoices { unit(0.0) }
        val assigner = SpeakerAssigner(voices = voices, onAssigned = {})
        assigner.assign(1L, buffer(3f), listOf(seg(0f, 2f)))
        assigner.release()
        assertTrue("the model is freed", voices.released.await(5, TimeUnit.SECONDS))
        assertEquals("…on the thread that loaded it", "speaker-embed", voices.releasedOn)
        assertEquals("…and after the work it had queued", 1, voices.lengths.size)
    }

    // ------------------------------------------------------------------ the stop-tap fence

    @Test
    fun awaitIdleReturnsOnlyAfterTheQueuedChunkHasBeenFingerprintedAndPublished() {
        // The defect this fence closes: the service's finalize waits on the ENGINE's drain, which
        // returns on the same pass that SUBMITS the last chunk here — so the snapshot of the runs
        // used by the delivery, the clipboard and history was taken while the final chunk's ids
        // were still in flight, and the last thing said in a session inherited the previous
        // speaker's number. "Published", not merely "computed": onAssigned runs inside the task,
        // so a caller that has cleared this fence has already seen every assignment.
        val entered = CountDownLatch(1)
        val voices = object : VoicePrints {
            override fun embed(pcm: FloatArray, sampleRate: Int): FloatArray? {
                entered.countDown()
                Thread.sleep(300) // the ~300 ms the real embedder costs
                return unit(0.0)
            }
            override fun release() = Unit
        }
        val published = AtomicReference<SpeakerAssignment?>(null)
        val assigner = SpeakerAssigner(voices = voices, onAssigned = { published.set(it) })
        try {
            assigner.assign(9L, buffer(4f), listOf(seg(0f, 2f)))
            assertTrue("the embed actually started", entered.await(5, TimeUnit.SECONDS))
            assertNull("…and is still running, so nothing is published yet", published.get())
            assertTrue("the fence drained", assigner.awaitIdle(5_000))
            assertEquals("the assignment is published by the time the fence clears", 9L, published.get()?.seq)
            assertEquals(listOf(1), published.get()?.ids)
        } finally {
            assigner.release()
        }
    }

    @Test
    fun awaitIdleAnswersFalseOnTheTimeoutAndCostsNothingWhenThereIsNoWork() {
        val blocked = CountDownLatch(1)
        val voices = object : VoicePrints {
            override fun embed(pcm: FloatArray, sampleRate: Int): FloatArray? {
                blocked.await(5, TimeUnit.SECONDS) // a hung embedder
                return null
            }
            override fun release() = Unit
        }
        val assigner = SpeakerAssigner(voices = voices, onAssigned = {})
        try {
            // Idle: the fence is a barrier task, so an assigner with nothing queued clears at once.
            assertTrue(assigner.awaitIdle(2_000))
            assigner.assign(1L, buffer(4f), listOf(seg(0f, 2f)))
            // Bounded: a hung embed costs the caller the timeout and its labels, never its text.
            assertFalse("the fence does not wait forever", assigner.awaitIdle(150))
        } finally {
            blocked.countDown()
            assigner.release()
        }
    }

    @Test
    fun awaitIdleAfterReleaseIsTrueBecauseNothingCanStillBeInFlight() {
        // The teardown order is release-then-nothing, but onDestroy and the fatal drain can reach
        // the finalize block in any order; a rejected barrier must read as "drained", never hang
        // and never throw on the caller's thread.
        val voices = FakeVoices { unit(0.0) }
        val assigner = SpeakerAssigner(voices = voices, onAssigned = {})
        assigner.assign(1L, buffer(3f), listOf(seg(0f, 2f)))
        assigner.release()
        assertTrue(voices.released.await(5, TimeUnit.SECONDS))
        assertTrue(assigner.awaitIdle(2_000))
    }

    // ------------------------------------------------------------------ the three per-segment fates

    @Test
    fun aSegmentUnderTheEmbedFloorIsNeverFingerprintedAndInheritsTheLabelBeforeIt() {
        // 2.0 s (opens speaker 1), 0.5 s (under MIN_EMBED_SECONDS), 2.0 s of a second voice.
        val voices = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val assignment = assignOneChunk(
            voices = voices,
            samples = buffer(10f),
            vad = listOf(seg(0f, 2f), seg(2.5f, 3f), seg(4f, 6f)),
        )
        assertEquals("two embeds for three segments", 2, voices.lengths.size)
        assertEquals(listOf(1, 1, 2), assignment?.ids)
        // The skipped segment has no similarity to report, and says so rather than reporting 0.
        assertTrue("the skipped segment's best is not a number", assignment!!.stats.best[1].isNaN())
        assertEquals(0.5f, assignment.stats.durationsSec[1], 0.01f)
    }

    @Test
    fun anEmbedderThatAnswersNullLeavesTheLabelExactlyWhereItWas() {
        val voices = FakeVoices { index -> if (index == 1) null else unit(0.0) }
        val assignment = assignOneChunk(
            voices = voices,
            samples = buffer(10f),
            vad = listOf(seg(0f, 2f), seg(3f, 5f), seg(6f, 8f)),
        )
        assertEquals("it was asked all three times", 3, voices.lengths.size)
        assertEquals(listOf(1, 1, 1), assignment?.ids)
        assertTrue("a refused embedding has no similarity", assignment!!.stats.best[1].isNaN())
    }

    @Test
    fun theFirstSegmentOfASessionWithNoUsableEmbeddingIsUnlabelledRatherThanSpeakerOne() {
        // currentSpeaker() is 0 before anything is assigned, and 0 is the caller's "no label".
        // Inventing speaker 1 here would put a label on a session that never earned one (spec §2).
        val voices = FakeVoices { null }
        val assignment = assignOneChunk(
            voices = voices,
            samples = buffer(4f),
            vad = listOf(seg(0f, 2f)),
        )
        assertEquals(listOf(0), assignment?.ids)
        assertFalse(assignment!!.confirmed)
    }

    // ------------------------------------------------------------------ the slice

    @Test
    fun theSliceIsTheOriginalTimelinesSamplesAndAStaleGeometryCannotRunOffTheBuffer() {
        val voices = FakeVoices { unit(0.0) }
        // 4 s of audio; the second segment claims samples the buffer does not have (the shape a
        // process-global geometry snapshot from a LATER, longer chunk takes), and the third is
        // entirely past the end.
        val assignment = assignOneChunk(
            voices = voices,
            samples = buffer(4f),
            vad = listOf(
                seg(1f, 3f),
                seg(3f, 9f),
                seg(10f, 12f),
            ),
        )
        assertEquals(2 * RATE, voices.lengths[0])
        assertEquals("clamped to what the buffer holds", 1 * RATE, voices.lengths[1])
        // The third has no samples at all: no embed, and the label it inherits.
        assertEquals("a segment past the end is never fingerprinted", 2, voices.lengths.size)
        assertEquals(3, assignment?.ids?.size)
        assertEquals(0f, assignment!!.stats.durationsSec[2], 0.001f)
    }

    @Test
    fun aChunkWithNoVadSegmentsProducesNoEmbeddingAndNoCallbackAtAll() {
        // Spec §2: "no segments" is not "one speaker". There is nothing to attribute, so there is
        // nothing to say — and in particular no `confirmed=` verdict to publish.
        val voices = FakeVoices { unit(0.0) }
        assertNull(
            assignOneChunk(voices = voices, samples = buffer(4f), vad = emptyList(), timeoutMs = 300),
        )
        assertEquals(0, voices.lengths.size)
    }

    // ------------------------------------------------------------------ the stats the spike reads

    @Test
    fun theStatsCarryOneRowPerSegmentAndTheChunksWholeEmbedCost() {
        val ticks = AtomicLong(0L)
        val voices = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val assignment = assignOneChunk(
            voices = voices,
            samples = buffer(12f),
            vad = listOf(seg(0f, 2f), seg(2.2f, 2.6f), seg(4f, 7f)),
            // Every read advances 5 ms, so each embedded segment costs exactly 5 ms and the
            // skipped one costs nothing — the arithmetic the budget in plan Task 3 is read with.
            clockNs = { ticks.getAndAdd(5_000_000L) },
        )
        assertEquals(7L, assignment?.seq)
        assertEquals(3, assignment?.ids?.size)
        assertEquals(3, assignment?.stats?.best?.size)
        assertEquals(3, assignment?.stats?.durationsSec?.size)
        assertEquals("only the fingerprinted segments cost anything", 10L, assignment?.stats?.embedMs)
        assertEquals(2f, assignment!!.stats.durationsSec[0], 0.01f)
        assertEquals(3f, assignment.stats.durationsSec[2], 0.01f)
    }

    @Test
    fun theConfirmedFlagIsTheTrackersLatchAndNothingElse() {
        // A 1.4 s second voice does NOT confirm — MIN_OPEN_SECONDS is 1.5 since spike session 4,
        // so this one is in the MATCH-ONLY tier: it cannot open a speaker and, being nowhere near
        // the one voice known so far, takes that speaker's label. The owner's accepted limit,
        // moved to where session 4 put it: an interruption shorter than a second and a half
        // cannot claim a person.
        val shy = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val first = assignOneChunk(
            voices = shy,
            samples = buffer(8f),
            vad = listOf(seg(0f, 2f), seg(3f, 4.4f)),
        )
        assertEquals(listOf(1, 1), first?.ids)
        assertFalse("a 1.4 s interjection opens nobody", first!!.confirmed)

        // Two 2 s segments EACH: the latch is two CONFIRMED speakers now, not two speakers.
        val bold = FakeVoices { index -> if (index % 2 == 0) unit(0.0) else unit(90.0) }
        val second = assignOneChunk(
            voices = bold,
            samples = buffer(14f),
            vad = listOf(seg(0f, 2f), seg(3f, 5f), seg(6f, 8f), seg(9f, 11f)),
        )
        assertEquals(listOf(1, 2, 1, 2), second?.ids)
        assertTrue("both have held the floor twice", second!!.confirmed)

        // …and one qualifying segment each is NOT enough, which is the half that changed.
        val half = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val third = assignOneChunk(
            voices = half,
            samples = buffer(8f),
            vad = listOf(seg(0f, 2f), seg(3f, 5f)),
        )
        assertEquals(listOf(1, 2), third?.ids)
        assertFalse("two speakers, one segment each: no label yet", third!!.confirmed)
    }

    // ------------------------------------------------------------------ the end-of-chunk merge

    @Test
    fun theEndOfChunkMergeRunsONCEPerChunkAndItsResultRidesOutOnTheSameCallback() {
        // The refine half of spike session 2, and the only place in the app that knows where a
        // chunk ENDS. The five cone segments chain into one confirmed speaker; the sixth — the
        // cone's axis, 0.259 from every one of their fingerprints — opens speaker 2 online, which
        // is the correct answer with the evidence a single segment carries. The pass then compares
        // WHOLE speakers: speaker 2's centroid is 0.801 from speaker 1's, so it was never a
        // separate person.
        //
        // The ids reported for THIS chunk still say 2, and that is deliberate: they were already
        // emitted by the time the pass ran. `remaps` is how a reader puts them right.
        val voices = FakeVoices { index -> if (index < CONE_PHI.size) cone(CONE_PHI[index]) else coneAxis() }
        val assignment = assignOneChunk(
            voices = voices,
            samples = buffer(20f),
            vad = listOf(seg(0f, 2f), seg(3f, 5f), seg(6f, 8f), seg(9f, 11f), seg(12f, 14f), seg(15f, 17f)),
        )
        assertEquals(6, voices.lengths.size)
        assertEquals("online, the sixth segment was a new voice", listOf(1, 1, 1, 1, 1, 2), assignment?.ids)
        assertEquals("…and the merge pass says it was not", mapOf(2 to 1), assignment?.remaps)
        assertFalse("one voice is not two, whatever it was briefly called", assignment!!.confirmed)
        // The diag line the device session reads carries it, once, at the end.
        assertTrue(SpeakerDiag.line(assignment), SpeakerDiag.line(assignment).endsWith(" remaps=[2>1]"))
    }

    @Test
    fun aChunkThatMERGEDNOTHINGCarriesAnEmptyMapAndNoRemapsFieldAtAll() {
        // Almost every chunk. The field exists so the rare one can be read; a `remaps=[]` on all
        // eighteen lines of a session would bury the one line worth grepping for.
        val voices = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val assignment = assignOneChunk(
            voices = voices,
            samples = buffer(8f),
            vad = listOf(seg(0f, 2f), seg(3f, 5f)),
        )
        assertEquals(emptyMap<Int, Int>(), assignment?.remaps)
        assertFalse(SpeakerDiag.line(assignment!!), "remaps" in SpeakerDiag.line(assignment))
    }

    @Test
    fun theTrackerIsCarriedAcrossChunksSoTheNumberingIsPerSession() {
        // Two chunks through ONE assigner: speaker 1 returns in the second chunk and keeps its
        // number (spec §2, "a speaker who returns keeps their number").
        val voices = FakeVoices { index -> if (index == 1) unit(90.0) else unit(0.0) }
        val seen = Collections.synchronizedList(ArrayList<SpeakerAssignment>())
        val arrived = CountDownLatch(2)
        val assigner = SpeakerAssigner(
            voices = voices,
            onAssigned = { seen += it; arrived.countDown() },
        )
        try {
            assigner.assign(1L, buffer(8f), listOf(seg(0f, 2f), seg(3f, 5f)))
            assigner.assign(2L, buffer(4f), listOf(seg(0f, 2f)))
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
        } finally {
            assigner.release()
        }
        assertEquals(listOf(1, 2), seen[0].ids)
        assertEquals(listOf(1), seen[1].ids)
        assertEquals(listOf(1L, 2L), seen.map { it.seq })
        // …and only the FIRST chunk paid the model load, which is the one number in the spike that
        // would otherwise look like CAM++ being slow (VoicePrints loads lazily on its first call).
        assertTrue("chunk 1 paid the load", seen[0].stats.includesModelLoad)
        assertFalse("chunk 2 did not", seen[1].stats.includesModelLoad)
    }

    @Test
    fun aChunkThatFingerprintedNothingNeverClaimsToHavePaidTheLoad() {
        // All three segments are under the embed floor, so the model was never asked for and this
        // chunk's `embedMs` of 0 carries no load. Claiming it would hand the NEXT chunk — the one
        // that really does pay — a `load=0` and put the outlier back where it cannot be explained.
        val voices = FakeVoices { unit(0.0) }
        val assignment = assignOneChunk(
            voices = voices,
            samples = buffer(6f),
            vad = listOf(seg(0f, 0.4f), seg(1f, 1.5f), seg(2f, 2.9f)),
        )
        assertEquals(0, voices.lengths.size)
        assertEquals(0L, assignment?.stats?.embedMs)
        assertFalse(assignment!!.stats.includesModelLoad)
    }

    @Test
    fun anEmbedderThatTHROWSKillsTheLabelForThatChunkAndNotTheThread() {
        // The executor is the session's only embed thread; an exception escaping a task there
        // would take it down for a LABEL, on a path whose text has already been delivered.
        val boom = object : VoicePrints {
            var calls = 0
            override fun embed(pcm: FloatArray, sampleRate: Int): FloatArray? {
                calls++
                throw IllegalStateException("native refused")
            }
            override fun release() = Unit
        }
        val arrived = CountDownLatch(1)
        val assigner = SpeakerAssigner(voices = boom, onAssigned = { arrived.countDown() })
        try {
            assigner.assign(1L, buffer(4f), listOf(seg(0f, 2f)))
            assertFalse("a throwing chunk publishes nothing", arrived.await(300, TimeUnit.MILLISECONDS))
            // …and the NEXT chunk is still served by a live thread.
            assigner.assign(2L, buffer(4f), listOf(seg(0f, 2f)))
            Thread.sleep(200)
            assertEquals(2, boom.calls)
        } finally {
            assigner.release()
        }
    }

    private companion object {
        const val RATE = 16_000

        /** The five positions of the [cone] fixture. */
        val CONE_PHI = listOf(0.0, 60.0, 120.0, 180.0, 240.0)
    }
}
