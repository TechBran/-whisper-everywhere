package com.whispereverywhere.transcription.speakers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
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

    /** The same, but each sample's VALUE is its index — so a slice reports where it came from. */
    private fun ramp(seconds: Float) = FloatArray((RATE * seconds).toInt()) { it.toFloat() }

    /**
     * One fingerprint window per VAD segment, on the original timeline — the UN-SPLIT shape, and
     * the shape of every chunk in this file except [aLongSegmentIsFingerprintedPerWhisperSegment].
     * The `vadIndex` is the position, which is what makes `segs` equal `windows` here.
     */
    private fun segs(vararg bounds: Pair<Float, Float>): List<SpeakerWindow> =
        bounds.mapIndexed { index, (startSec, endSec) ->
            SpeakerWindow(
                vadIndex = index,
                origStart = (startSec * RATE).toInt(),
                origEnd = (endSec * RATE).toInt(),
            )
        }

    /** Several windows cut out of ONE VAD segment — what spike session 4 does to a long one. */
    private fun split(vadIndex: Int, vararg bounds: Pair<Float, Float>): List<SpeakerWindow> =
        bounds.map { (startSec, endSec) ->
            SpeakerWindow(
                vadIndex = vadIndex,
                origStart = (startSec * RATE).toInt(),
                origEnd = (endSec * RATE).toInt(),
            )
        }

    /**
     * The seam's fake. Records the thread it was called on and the slice length it was handed —
     * the two things the assigner is responsible for and the embedder is not.
     */
    private class FakeVoices(private val answer: (Int) -> FloatArray?) : VoicePrints {
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())
        val lengths: MutableList<Int> = Collections.synchronizedList(ArrayList())

        /**
         * Each slice's FIRST sample value. Against a [ramp] buffer — where a sample's value is
         * its index — that is the slice's offset into the chunk, which is the half of "the right
         * sample range" that a length alone cannot show.
         */
        val starts: MutableList<Int> = Collections.synchronizedList(ArrayList())
        val released = CountDownLatch(1)
        @Volatile var releasedOn: String? = null

        override fun embed(pcm: FloatArray, sampleRate: Int): FloatArray? {
            threads += Thread.currentThread().name
            lengths += pcm.size
            starts += pcm.firstOrNull()?.toInt() ?: -1
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
        windows: List<SpeakerWindow>,
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
            assigner.assign(seq, samples, windows)
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
            windows = segs(0f to 2f, 3f to 5f),
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
        assigner.assign(1L, buffer(3f), segs(0f to 2f))
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
            assigner.assign(9L, buffer(4f), segs(0f to 2f))
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
            assigner.assign(1L, buffer(4f), segs(0f to 2f))
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
        assigner.assign(1L, buffer(3f), segs(0f to 2f))
        assigner.release()
        assertTrue(voices.released.await(5, TimeUnit.SECONDS))
        assertTrue(assigner.awaitIdle(2_000))
    }

    // ------------------------------------------------------------------ the second look (session 6)

    /**
     * THE LOCK-ON, reproduced exactly — session 6's 03:27 dump in five chunks.
     *
     * A 5 s window of voice A at 0°, then a 2 s window at 40° that A matches (cos 0.766) and
     * which therefore ENTERS A's recent set, and then three 4 s windows of voice B at 95-99°. B
     * is -0.087 from A's own fingerprint and would open its own speaker — but the matcher takes
     * the MAXIMUM over the recent set, and against the 40° vector B scores 0.574. So every one
     * of the five is called speaker 1: one id, two voices, one run-on paragraph. That is the
     * mechanism, not an analogy — a single ambiguous window in a recent set is enough.
     *
     * The retrospective pass weighs the same five at once, and the duration weighting is what
     * saves it: the 40° window is 0.766 from A and only 0.545 from B, so average linkage puts it
     * with A, and {0°, 40°} at 7 s then sits 0.069 from {95°, 97°, 99°} at 12 s — far under
     * [SpeakerReclusterer.RECLUSTER_SIM], with both clusters over the mass bar.
     */
    private fun lockOn() = FakeVoices { index ->
        when (index) {
            0 -> unit(0.0)
            1 -> unit(40.0)
            2 -> unit(95.0)
            3 -> unit(97.0)
            else -> unit(99.0)
        }
    }

    /** The five windows of [lockOn], in order: `seq to (startSec to endSec)`. */
    private val lockOnChunks = listOf(
        1L to (0f to 5f),
        2L to (0f to 2f),
        3L to (0f to 4f),
        4L to (0f to 4f),
        5L to (0f to 4f),
    )

    /** Drives [chunks] through [assigner], one window each, waiting for each assignment. */
    private fun drive(
        assigner: SpeakerAssigner,
        chunks: List<Pair<Long, Pair<Float, Float>>>,
        arrivals: BlockingQueue<SpeakerAssignment>,
    ): List<Int> {
        val ids = ArrayList<Int>(chunks.size)
        for ((seq, bounds) in chunks) {
            assigner.assign(seq, buffer(bounds.second + 1f), segs(bounds))
            val assignment = arrivals.poll(5, TimeUnit.SECONDS)
            assertNotNull("chunk " + seq + " was assigned", assignment)
            ids += assignment!!.ids.single()
        }
        return ids
    }

    @Test
    fun theSecondLookIsPublishedEveryFiveChunksAndFindsTheVoiceTheMatcherSwallowed() {
        val arrivals = LinkedBlockingQueue<SpeakerAssignment>()
        val relabels = LinkedBlockingQueue<SpeakerRelabel>()
        val assigner = SpeakerAssigner(
            voices = lockOn(),
            onAssigned = { arrivals.put(it) },
            onRelabel = { relabels.put(it) },
        )
        try {
            val ids = drive(assigner, lockOnChunks.take(4), arrivals)
            assertEquals("the matcher locked onto one id", listOf(1, 1, 1, 1), ids)
            assertTrue("…and nothing has been re-clustered yet", relabels.isEmpty())

            drive(assigner, lockOnChunks.drop(4), arrivals)
            val relabel = relabels.poll(5, TimeUnit.SECONDS)
            assertNotNull("the fifth chunk brings the second look", relabel)
            assertEquals(5, relabel!!.fingerprints)
            assertEquals("two voices, where the online pass saw one", 2, relabel.clusterCount)
            assertEquals(2, relabel.confirmedCount)
            // The labels are PER WINDOW, which is the only correction that can split an id that
            // swallowed two voices: the first two windows are one speaker, the last three another.
            assertEquals(
                listOf(1, 1, 2, 2, 2),
                lockOnChunks.map { relabel.windowLabels.getValue(WindowKey(it.first, 0)) },
            )
            assertEquals("three windows changed hands", 3, relabel.changed)
            assertTrue("…and the pass is timed", relabel.costMs >= 0)
            assertEquals("one pass, not one per chunk", 0, relabels.size)
        } finally {
            assigner.release()
        }
    }

    @Test
    fun theSecondLookReseedsTheTrackerSoTheNextChunkFollowsTheTruth() {
        // Relabelling the panel fixes the past. This is the other half: without the reseed the
        // sixth chunk would be decided by the very recent set that locked on, and the session
        // would go straight back to one id — the panel rewritten, then immediately wrong again.
        val arrivals = LinkedBlockingQueue<SpeakerAssignment>()
        val relabels = LinkedBlockingQueue<SpeakerRelabel>()
        val tracker = SpeakerTracker()
        val voices = FakeVoices { index ->
            when (index) {
                0 -> unit(0.0)
                1 -> unit(40.0)
                2 -> unit(95.0)
                3 -> unit(97.0)
                4 -> unit(99.0)
                else -> unit(96.0) // the sixth chunk: voice B again
            }
        }
        val assigner = SpeakerAssigner(
            voices = voices,
            onAssigned = { arrivals.put(it) },
            onRelabel = { relabels.put(it) },
            tracker = tracker,
        )
        try {
            assertEquals(listOf(1, 1, 1, 1, 1), drive(assigner, lockOnChunks, arrivals))
            assertNotNull(relabels.poll(5, TimeUnit.SECONDS))

            val sixth = drive(assigner, listOf(6L to (0f to 4f)), arrivals).single()

            assertEquals("the tracker's speakers ARE the clusters now", 2, sixth)
            assertEquals(2, tracker.speakerCount)
            assertTrue("…and the latch followed the retrospective truth", tracker.secondSpeakerConfirmed)
        } finally {
            assigner.release()
        }
    }

    @Test
    fun theFinalSecondLookRunsInsideTheFenceAndIsPublishedBeforeItReturns() {
        // The sink is detached the instant the fence returns, so a pass published after it would
        // land on nothing and the delivery, the clipboard and history would ship the online
        // labels while the panel showed the corrected ones.
        val arrivals = LinkedBlockingQueue<SpeakerAssignment>()
        val published = AtomicReference<SpeakerRelabel?>(null)
        val assigner = SpeakerAssigner(
            voices = lockOn(),
            onAssigned = { arrivals.put(it) },
            onRelabel = { published.set(it) },
        )
        try {
            // Four chunks — one short of the cadence, so nothing has been re-clustered yet.
            drive(assigner, lockOnChunks.take(4), arrivals)
            assertNull(published.get())

            assertTrue("the fence drained", assigner.awaitIdle(5_000))

            val relabel = published.get()
            assertNotNull("the final pass is published by the time the fence clears", relabel)
            assertEquals(4, relabel!!.fingerprints)
            assertEquals(2, relabel.clusterCount)
            assertEquals(2, relabel.confirmedCount)
            assertEquals(
                listOf(1, 1, 2, 2),
                lockOnChunks.take(4).map { relabel.windowLabels.getValue(WindowKey(it.first, 0)) },
            )
        } finally {
            assigner.release()
        }
    }

    @Test
    fun aFenceWithNothingNewToLookAtPublishesNothingAtAll() {
        // onDestroy and the fatal drain can both reach the finalize block, so two fences in one
        // session is a normal shape. A second identical relabel would repaint the panel and
        // print a second diag line for a change that did not happen.
        val arrivals = LinkedBlockingQueue<SpeakerAssignment>()
        val relabels = LinkedBlockingQueue<SpeakerRelabel>()
        val assigner = SpeakerAssigner(
            voices = lockOn(),
            onAssigned = { arrivals.put(it) },
            onRelabel = { relabels.put(it) },
        )
        try {
            drive(assigner, lockOnChunks.take(4), arrivals)
            assertTrue(assigner.awaitIdle(5_000))
            assertEquals(1, relabels.size)

            assertTrue(assigner.awaitIdle(5_000))

            assertEquals("still one", 1, relabels.size)
        } finally {
            assigner.release()
        }
    }

    @Test
    fun EVERYWindowIsRelabelledIncludingTheOnesThatProducedNoVector() {
        // A window under the embed floor and one the embedder refused have no fingerprint, and
        // for a whole round they were also absent from the relabel — which looked harmless and
        // was not. `SpeakerTracker.reseed` RENUMBERS the id space, so a run the relabel does not
        // name keeps an id from the old numbering; after the pass that id usually belongs to a
        // different person, `SpeakerLabels.displayNumbers` compacts by raw id, and the panel,
        // the field, the clipboard and the history sidecar all grow a ghost `Speaker N:`. So
        // they are carried with a null vector: they never vote, they are always named.
        val arrivals = LinkedBlockingQueue<SpeakerAssignment>()
        val published = AtomicReference<SpeakerRelabel?>(null)
        // Two voices, 0-2° and 95-97°, and one refusal in the middle of the first.
        val voices = FakeVoices { index ->
            when (index) {
                0 -> unit(0.0)
                1 -> unit(2.0)
                2 -> null
                3 -> unit(95.0)
                else -> unit(97.0)
            }
        }
        val assigner = SpeakerAssigner(
            voices = voices,
            onAssigned = { arrivals.put(it) },
            onRelabel = { published.set(it) },
        )
        try {
            // Windows 0, 1: 4 s of voice A. Window 2: 4 s, the embedder refuses. Window 3: 0.5 s,
            // never fingerprinted at all. Windows 4, 5: 4 s of voice B.
            assigner.assign(
                1L,
                buffer(26f),
                segs(0f to 4f, 5f to 9f, 10f to 14f, 15f to 15.5f, 16f to 20f, 21f to 25f),
            )
            assertNotNull(arrivals.poll(5, TimeUnit.SECONDS))
            assertTrue(assigner.awaitIdle(5_000))

            val relabel = published.get()
            assertNotNull("two confirmed voices, so the pass is published", relabel)
            assertEquals(
                "every window of the chunk, not only the four with a vector",
                (0..5).map { WindowKey(1L, it) }.toSet(),
                relabel!!.windowLabels.keys,
            )
            assertEquals(
                "…and the two with none went to the voice they inherited",
                listOf(1, 1, 1, 1, 2, 2),
                (0..5).map { relabel.windowLabels.getValue(WindowKey(1L, it)) },
            )
            assertEquals("the diag's n= is still the VECTORS weighed", 4, relabel.fingerprints)
        } finally {
            assigner.release()
        }
    }

    @Test
    fun aPassThatCONFIRMSNobodyIsNotPublishedAndCannotCollapseTheTracker() {
        // The degenerate answer — nothing cleared MIN_CLUSTER_SECONDS — labels EVERY window 1
        // and reports confirmedCount 0. Published, it would print "Speaker 1:" over a session
        // the online tracker had already got right (the panel's latch is one-way) and reseed the
        // tracker down to a single unconfirmed voice that has to re-earn the second. The window
        // shape is session 4's 20:39 dump: short turns, clearly distinct voices, nothing with
        // six seconds to its name.
        val arrivals = LinkedBlockingQueue<SpeakerAssignment>()
        val published = AtomicReference<SpeakerRelabel?>(null)
        val tracker = SpeakerTracker()
        val voices = FakeVoices { index ->
            when (index) {
                0 -> unit(0.0)
                1 -> unit(1.0)
                2 -> unit(95.0)
                else -> unit(96.0)
            }
        }
        val assigner = SpeakerAssigner(
            voices = voices,
            onAssigned = { arrivals.put(it) },
            onRelabel = { published.set(it) },
            tracker = tracker,
        )
        try {
            for (seq in 1L..4L) {
                assigner.assign(seq, buffer(3f), segs(0f to 2f))
                assertNotNull(arrivals.poll(5, TimeUnit.SECONDS))
            }
            assertEquals("the ONLINE pass found both voices", 2, tracker.speakerCount)
            assertEquals(2, tracker.confirmedCount)

            assertTrue(assigner.awaitIdle(5_000))

            assertNull("a pass that confirmed nobody says nothing", published.get())
            assertEquals("…and the live tracker is untouched", 2, tracker.speakerCount)
            assertEquals(2, tracker.confirmedCount)
            assertTrue(tracker.secondSpeakerConfirmed)
        } finally {
            assigner.release()
        }
    }

    // ------------------------------------------------------------------ the three per-segment fates

    @Test
    fun aSegmentUnderTheEmbedFloorIsNeverFingerprintedAndInheritsTheLabelBeforeIt() {
        // 2.0 s (opens speaker 1), 0.5 s (under MIN_EMBED_SECONDS), 2.0 s of a second voice.
        val voices = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val assignment = assignOneChunk(
            voices = voices,
            samples = buffer(10f),
            windows = segs(0f to 2f, 2.5f to 3f, 4f to 6f),
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
            windows = segs(0f to 2f, 3f to 5f, 6f to 8f),
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
            windows = segs(0f to 2f),
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
            windows = segs(
                1f to 3f,
                3f to 9f,
                10f to 12f,
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
            assignOneChunk(voices = voices, samples = buffer(4f), windows = emptyList(), timeoutMs = 300),
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
            windows = segs(0f to 2f, 2.2f to 2.6f, 4f to 7f),
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
            windows = segs(0f to 2f, 3f to 4.4f),
        )
        assertEquals(listOf(1, 1), first?.ids)
        assertFalse("a 1.4 s interjection opens nobody", first!!.confirmed)

        // Two 2 s segments EACH: the latch is two CONFIRMED speakers now, not two speakers.
        val bold = FakeVoices { index -> if (index % 2 == 0) unit(0.0) else unit(90.0) }
        val second = assignOneChunk(
            voices = bold,
            samples = buffer(14f),
            windows = segs(0f to 2f, 3f to 5f, 6f to 8f, 9f to 11f),
        )
        assertEquals(listOf(1, 2, 1, 2), second?.ids)
        assertTrue("both have held the floor twice", second!!.confirmed)

        // …and one qualifying segment each is NOT enough, which is the half that changed.
        val half = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val third = assignOneChunk(
            voices = half,
            samples = buffer(8f),
            windows = segs(0f to 2f, 3f to 5f),
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
            windows = segs(0f to 2f, 3f to 5f, 6f to 8f, 9f to 11f, 12f to 14f, 15f to 17f),
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
            windows = segs(0f to 2f, 3f to 5f),
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
            assigner.assign(1L, buffer(8f), segs(0f to 2f, 3f to 5f))
            assigner.assign(2L, buffer(4f), segs(0f to 2f))
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
            windows = segs(0f to 0.4f, 1f to 1.5f, 2f to 2.9f),
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
            assigner.assign(1L, buffer(4f), segs(0f to 2f))
            assertFalse("a throwing chunk publishes nothing", arrived.await(300, TimeUnit.MILLISECONDS))
            // …and the NEXT chunk is still served by a live thread.
            assigner.assign(2L, buffer(4f), segs(0f to 2f))
            Thread.sleep(200)
            assertEquals(2, boom.calls)
        } finally {
            assigner.release()
        }
    }

    // ------------------------------------------ the long-segment split (spike session 4)

    @Test
    fun aLongSegmentIsFingerprintedPerWhisperSegment() {
        // FAILURE MODE B's fix, end to end through the real geometry. Session 4's long dump ran
        // to 14.3 s per segment, and a segment that long can hold a whole exchange; whether it
        // DID was not shown (that dump was one narrator), so what is asserted here is that the
        // cut lands where whisper put the sentence boundary and hands the embedder the right two
        // ranges — not that a bug was caught.
        //
        // ONE VAD segment of twelve seconds holding TWO whisper segments: 0.00-5.50 s and
        // 5.60-12.00 s on the trimmed timeline, which here is the original one. Both clear
        // MIN_WINDOW_SECONDS several times over, so the split is two windows, cut at the
        // second sentence's own
        // start — 89 600 samples — with the first window taking the segment's start and the
        // second its end, so no audio is dropped between them.
        val vad = SpeakerSpans.vadSegments(intArrayOf(0, 12 * RATE, 0, 12 * RATE))
        val whisper = intArrayOf(0, 550, 0, 10, 560, 1_200, 10, 20)
        val windows = SpeakerSpans.windows(whisper, vad)
        assertEquals(
            listOf(
                SpeakerWindow(vadIndex = 0, origStart = 0, origEnd = 89_600),
                SpeakerWindow(vadIndex = 0, origStart = 89_600, origEnd = 12 * RATE),
            ),
            windows,
        )

        // Two voices, one per window — which the single-fingerprint shape could not have found.
        val voices = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val assignment = assignOneChunk(voices = voices, samples = ramp(12f), windows = windows)

        assertEquals("TWO fingerprints out of one VAD segment", 2, voices.lengths.size)
        assertEquals(listOf(0, 89_600), voices.starts)
        assertEquals(listOf(89_600, 12 * RATE - 89_600), voices.lengths)
        assertEquals(listOf(1, 2), assignment?.ids)
        assertEquals("…and the line still says there was ONE segment behind them", 1, assignment?.segs)
    }

    @Test
    fun aShortSegmentOrALoneWhisperSegmentIsStillFingerprintedExactlyOnce() {
        // The two halves of the guard, which the 2026-09-18 late session narrowed but did not
        // remove. A 1.8 s segment cannot hold two windows at MIN_WINDOW_SECONDS, so there is
        // nothing to cut it into; a 12 s one with a single whisper segment has nowhere
        // defensible to cut. (This used to be a 4 s segment — at the late session's 2.0 s floor
        // four seconds with two sentences in it is cut, which is the change.)
        val shortSegment = SpeakerSpans.vadSegments(intArrayOf(0, 28_800, 0, 28_800))
        assertEquals(
            listOf(SpeakerWindow(0, 0, 28_800)),
            SpeakerSpans.windows(intArrayOf(0, 80, 0, 10, 90, 180, 10, 20), shortSegment),
        )

        val oneSentence = SpeakerSpans.vadSegments(intArrayOf(0, 12 * RATE, 0, 12 * RATE))
        assertEquals(
            listOf(SpeakerWindow(0, 0, 12 * RATE)),
            SpeakerSpans.windows(intArrayOf(0, 1_200, 0, 10), oneSentence),
        )
    }

    @Test
    fun theSegmentCountIsTheSEGMENTSBehindTheWindowsNotTheWindows() {
        // `segs=` and `windows=` are two numbers in the diag line and their DIFFERENCE is the
        // measurement session 4 asks for. Three windows cut out of one segment must not be read
        // as three segments: the endpointer produced one.
        val voices = FakeVoices { unit(0.0) }
        val assignment = assignOneChunk(
            voices = voices,
            samples = buffer(12f),
            windows = split(0, 0f to 4f, 4f to 8f, 8f to 12f),
        )
        assertEquals(1, assignment?.segs)
        assertEquals(3, assignment?.ids?.size)
    }

    // ------------------------------------------------- the NPU route (4.10, the Fold6 defect)

    /**
     * Runs ONE chunk through [SpeakerAssigner.assignWholeChunk] with a FAKE segmenter standing in
     * for `WhisperNative.vadSegmentsOf`, which cannot be called off a device.
     *
     * [segments] is what that native call would have answered: `[start, end]` SAMPLE PAIRS on the
     * chunk's own timeline — two ints per segment, because there is no stitched buffer on this
     * route and therefore no second timeline.
     */
    private fun assignWholeChunkOnce(
        voices: VoicePrints,
        samples: FloatArray,
        segments: IntArray,
        tracker: SpeakerTracker = SpeakerTracker(),
        seq: Long = 7L,
        vadModelPath: String = "/data/vad/silero.bin",
        timeoutMs: Long = 5_000,
    ): SpeakerAssignment? {
        val arrived = CountDownLatch(1)
        val seen = AtomicReference<SpeakerAssignment?>(null)
        val assigner = SpeakerAssigner(
            voices = voices,
            onAssigned = { seen.set(it); arrived.countDown() },
            tracker = tracker,
            segmenter = { _, _ -> segments },
        )
        try {
            assigner.assignWholeChunk(seq, samples, vadModelPath)
            arrived.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            assigner.release()
        }
        return seen.get()
    }

    /** `[start, end]` sample pairs, from seconds — the fake native segmenter's answer. */
    private fun pairs(vararg bounds: Pair<Float, Float>): IntArray =
        IntArray(bounds.size * 2) { i ->
            val (startSec, endSec) = bounds[i / 2]
            (((if (i % 2 == 0) startSec else endSec)) * RATE).toInt()
        }

    @Test
    fun theWholeChunkTakesTheIdOfTheWindowHOLDINGTHEMOSTSPEECH() {
        // The QNN decoder publishes no token timestamps, so this chunk's TEXT cannot be split.
        // Two windows, two voices, and the LONGER one speaks for the chunk — that is the whole of
        // the owner's "at the chunk level … at least that would be good enough".
        val voices = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val assignment = assignWholeChunkOnce(
            voices = voices,
            samples = buffer(10f),
            segments = pairs(0f to 2f, 3f to 8f),
        )
        assertEquals("both windows are still fingerprinted and still teach the tracker",
            listOf(1, 2), assignment?.ids)
        assertEquals("the 5 s window wins over the 2 s one", 1, assignment?.wholeChunkWindow)
        assertEquals("two speech segments behind two windows", 2, assignment?.segs)
    }

    @Test
    fun anEvenSplitNamesTheEARLIESTWindowSoOneAudioCannotProduceTwoAnswers() {
        val voices = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val assignment = assignWholeChunkOnce(
            voices = voices,
            samples = buffer(10f),
            segments = pairs(0f to 3f, 4f to 7f),
        )
        assertEquals(listOf(1, 2), assignment?.ids)
        assertEquals("a tie goes to the earliest, always", 0, assignment?.wholeChunkWindow)
    }

    @Test
    fun aShortSegmentJoinsItsPredecessorRatherThanInheritingAlone() {
        // The coalescing rule is the one thing that carries over from the geometry route, and it
        // carries over because it is about the EMBEDDER: three half-second back-channels are one
        // fingerprintable window, not three that inherit a label without being heard.
        val voices = FakeVoices { unit(0.0) }
        val assignment = assignWholeChunkOnce(
            voices = voices,
            samples = buffer(10f),
            segments = pairs(0f to 2f, 2.2f to 2.6f, 2.8f to 3.1f),
        )
        assertEquals("three speech segments…", 3, assignment?.segs)
        assertEquals("…coalesced into one window", 1, assignment?.ids?.size)
        assertEquals(0, assignment?.wholeChunkWindow)
        assertEquals("one embed, over the merged span including its pauses", 1, voices.lengths.size)
        assertEquals((3.1f * RATE).toInt(), voices.lengths[0])
    }

    @Test
    fun theDominantWindowIsTheONEWITHTHEMOSTSPEECHAndNotTheOneWithTheWIDESTSPAN() {
        // THE DEFECT ROUND 1 FOUND. `dominant` ranks `stats.durationsSec`, and until this round
        // that list held wall SPANS — which stop being the same number as "how much speech" the
        // moment a window coalesces a short segment across a pause.
        //
        // Window 0 is 0.0-2.45 s, of which 2.2 s is voice (2.0 + a 0.2 s back-channel across a
        // 0.25 s gap). Window 1 is 3.0-5.3 s, all 2.3 s of it voice. The wider window is 0; the
        // one that did more of the talking is 1, and it is 1 that must speak for the chunk —
        // otherwise the whole chunk's text wears the label of the quieter voice and the louder
        // one gets nothing.
        val voices = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val assignment = assignWholeChunkOnce(
            voices = voices,
            samples = buffer(6f),
            segments = pairs(0f to 2f, 2.25f to 2.45f, 3f to 5.3f),
        )
        assertEquals("three speech segments, two windows", 3, assignment?.segs)
        assertEquals(listOf(1, 2), assignment?.ids)
        assertEquals(
            "2.3 s of speech beats 2.2 s of speech inside a 2.45 s span",
            1, assignment?.wholeChunkWindow,
        )
        val durations = assignment!!.stats.durationsSec
        assertEquals("the coalesced window reports its SPEECH, not its span", 2.2f, durations[0], 0.01f)
        assertEquals(2.3f, durations[1], 0.01f)
    }

    @Test
    fun aCoalescedWindowCANNOTOPENASpeakerOnSecondsItSpentInSilence() {
        // The other half of the same defect: `tracker.assign(embedding, seconds)` was told the
        // SPAN, so a window holding 1.4 s of voice either side of a pause cleared MIN_OPEN
        // (1.5 s) — and on the geometry route all three graded gates are gates on SPEECH. Told
        // the truth, this window is in the MATCH-ONLY tier with nobody to match, so it is
        // unlabelled (0) and opens nothing, exactly as a 1.4 s window elsewhere would be.
        val voices = FakeVoices { unit(0.0) }
        val assignment = assignWholeChunkOnce(
            voices = voices,
            samples = buffer(4f),
            segments = pairs(0f to 1.2f, 1.45f to 1.65f),
        )
        assertEquals("one coalesced window", 1, assignment?.ids?.size)
        assertEquals(
            "…and it is still EMBEDDED — 1.4 s clears MIN_EMBED — over its whole span",
            1, voices.lengths.size,
        )
        assertEquals((1.65f * RATE).toInt(), voices.lengths[0])
        assertEquals(
            "…but it may not open a speaker on 1.65 s of wall clock holding 1.4 s of voice",
            listOf(0), assignment?.ids,
        )
        assertEquals(1.4f, assignment!!.stats.durationsSec[0], 0.01f)
    }

    @Test
    fun aChunkWithNoSpeechPublishesNOTHING() {
        // Spec §2: "no segments" is not "one speaker". An empty segmentation is also what a
        // missing or unloadable VAD model answers, and neither may invent a speaker.
        val voices = FakeVoices { unit(0.0) }
        val assignment = assignWholeChunkOnce(
            voices = voices,
            samples = buffer(10f),
            segments = IntArray(0),
            timeoutMs = 400,
        )
        assertNull("no callback at all for a chunk with no speech", assignment)
        assertEquals("and nothing was fingerprinted", 0, voices.lengths.size)
    }

    @Test
    fun theDominantWindowsKEYIsWhatTheRetrospectivePassLaterRewrites() {
        // The published index is an index into `ids`, and therefore into the chunk's WindowKeys:
        // `TranscriptSink.assign` stamps it onto the chunk's one run, so a later `windowLabels`
        // relabel — keyed on WindowKey(seq, windowIndex) — reaches that run like any other. If
        // the index published here were not a real window index, the pass would silently miss
        // every NPU chunk and those runs would keep ids from a superseded numbering.
        val voices = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val assignment = assignWholeChunkOnce(
            voices = voices,
            samples = buffer(10f),
            segments = pairs(0f to 2f, 3f to 8f),
            seq = 42L,
        )
        assertNotNull(assignment)
        val pick = assignment!!.wholeChunkWindow
        assertNotNull(pick)
        assertTrue("the pick indexes the ids it was chosen from", pick!! in assignment.ids.indices)

        val runs = listOf(Run(seq = 42L, windowIndex = SpeakerRuns.NO_WINDOW_INDEX, text = "hi"))
        SpeakerRuns.applyWholeChunk(
            runs, seq = 42L, windowIndex = pick, id = assignment.ids[pick],
        )
        assertEquals(2, runs[0].speakerId)
        SpeakerRuns.applyWindowLabels(runs, mapOf(WindowKey(42L, pick) to 5))
        assertEquals("the pass reaches the NPU chunk's run", 5, runs[0].speakerId)
    }

    @Test
    fun theWholeChunkRouteRunsOffTheCallersThreadAndAsksForThePathItWasGiven() {
        val voices = FakeVoices { unit(0.0) }
        val caller = Thread.currentThread().name
        val arrived = CountDownLatch(1)
        val askedPath = AtomicReference<String?>(null)
        val askedThread = AtomicReference<String?>(null)
        val assigner = SpeakerAssigner(
            voices = voices,
            onAssigned = { arrived.countDown() },
            segmenter = { _, path ->
                askedPath.set(path)
                askedThread.set(Thread.currentThread().name)
                pairs(0f to 3f)
            },
        )
        try {
            assigner.assignWholeChunk(9L, buffer(5f), "/data/vad/silero.bin")
            assertTrue(arrived.await(5_000, TimeUnit.MILLISECONDS))
        } finally {
            assigner.release()
        }
        assertEquals("/data/vad/silero.bin", askedPath.get())
        assertEquals(
            "the ~60 ms VAD is paid on the embed thread, never the whisper one",
            "speaker-embed", askedThread.get(),
        )
        assertFalse("speaker-embed" == caller)
    }

    @Test
    fun anAbsentVadPathIsSkippedRatherThanInvented() {
        val voices = FakeVoices { unit(0.0) }
        val called = AtomicLong(0)
        val assigner = SpeakerAssigner(
            voices = voices,
            onAssigned = {},
            segmenter = { _, _ -> called.incrementAndGet(); pairs(0f to 3f) },
        )
        try {
            assigner.assignWholeChunk(9L, buffer(5f), "")
            assigner.assignWholeChunk(10L, FloatArray(0), "/data/vad/silero.bin")
            assertTrue(assigner.awaitIdle(5_000))
        } finally {
            assigner.release()
        }
        assertEquals("neither an empty path nor an empty buffer reaches the segmenter", 0L, called.get())
    }

    @Test
    fun theGeometryRouteNEVERPublishesAWholeChunkPick() {
        // THE CPU TIER MUST NOT CHANGE. `wholeChunkWindow` is the one field that alters what a
        // reader does with `ids`, and a non-null one on the geometry route would collapse a
        // sentence-labelled chunk to a single speaker — silently, because every id in it is real.
        val voices = FakeVoices { index -> if (index == 0) unit(0.0) else unit(90.0) }
        val assignment = assignOneChunk(
            voices = voices,
            samples = buffer(10f),
            windows = segs(0f to 2f, 3f to 8f),
        )
        assertEquals(listOf(1, 2), assignment?.ids)
        assertNull("the geometry route picks nothing", assignment?.wholeChunkWindow)
    }

    private companion object {
        const val RATE = 16_000

        /** The five positions of the [cone] fixture. */
        val CONE_PHI = listOf(0.0, 60.0, 120.0, 180.0, 240.0)
    }
}
