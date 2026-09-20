package com.whispereverywhere.transcription

import com.whispereverywhere.transcription.speakers.SpeakerAssigner
import com.whispereverywhere.transcription.speakers.SpeakerAssignment
import com.whispereverywhere.transcription.speakers.SpeakerRuns
import com.whispereverywhere.transcription.speakers.VoicePrints
import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.sin

/**
 * WHICH SPEAKER ROUTE A COMMITTED CHUNK TAKES (4.10, the Fold6 defect) — the behavioural twin of
 * `SpeakerWiringPinTest`'s source pins.
 *
 * The defect: the speaker pipeline hangs off whisper.cpp's segment geometry, and the NPU tier
 * publishes none — that path runs its own encoder and decoder on the HTP and never reaches the
 * whisper.cpp VAD filter, so `NpuWhisperBackend.lastGeometry` answers null and the engine skipped
 * the assigner entirely. On an NPU-capable device, where the 4.3 one-tier rule offers no CPU rung,
 * that is a session with no speaker changes anywhere in it.
 *
 * THREE OUTCOMES, and this class asserts each one against a real [SpeakerAssigner] rather than
 * against the shape of the call:
 *
 *  - **geometry published → the per-window route**, byte for byte what 4.10 shipped. This is the
 *    one that must not change, and the assertion that would catch it changing is
 *    [theCpuTiersChunkStillGetsOneIdPerWindowAndNoWholeChunkPick]: the ids are per window and
 *    `wholeChunkWindow` is null, which is the field every downstream reader branches on.
 *  - **a backend that publishes no geometry at all → the whole-chunk route**, one label for the
 *    chunk, named by the window holding the most speech. The test is
 *    `WhisperBackend.publishesGeometry`, a property of the BACKEND, and not "was this chunk's
 *    geometry null" — [aCpuChunkWhoseGeometrySNAPSHOTWasLostKeepsTodaysAnswerRatherThanTheVadRoute]
 *    is the difference, and it is the case round 1 of review corrected.
 *  - **no assigner (detection off, or a cloud session, whose local engine is only the fallback) →
 *    neither**, and no VAD is run at all.
 *
 * The two natives involved cannot be called on a JVM — `WhisperNative`'s initialiser is
 * `System.loadLibrary` — so both are driven through their existing seams: the backend's
 * `lastGeometry`, the assigner's `segmenter`, and the engine's injected `vadModelPath`.
 */
class LocalWhisperEngineSpeakerRouteTest {

    private fun fastRetry() = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    /**
     * 8 s of PCM16 @ 16 kHz. The length is not arbitrary: every window below has to clear
     * [com.whispereverywhere.transcription.speakers.SpeakerTracker.MIN_OPEN_SECONDS] (1.5 s) or
     * the tracker answers 0 — "could not attribute this" — for reasons that have nothing to do
     * with the route being tested.
     */
    private val pcm = ByteArray(8 * 16_000 * 2) { if (it % 2 == 0) 0x10 else 0x00 }

    /** Answers a different voice per call, so two windows cannot be mistaken for one. */
    private class TwoVoices : VoicePrints {
        val calls = AtomicInteger(0)
        override fun embed(pcm: FloatArray, sampleRate: Int): FloatArray? {
            val index = calls.getAndIncrement()
            val deg = if (index == 0) 0.0 else 90.0
            val r = Math.toRadians(deg)
            return floatArrayOf(cos(r).toFloat(), sin(r).toFloat())
        }
        override fun release() = Unit
    }

    /**
     * [publishes] is the BACKEND's own declaration — "do I publish geometry at all" — and it is
     * separate from [geometry], which is what this chunk's read happens to answer. The two come
     * apart in exactly one case and it is a real one: a whisper.cpp backend
     * (`publishes = true`) whose per-chunk snapshot was LOST, so `lastGeometry` answers null for
     * a chunk it transcribed perfectly well. Defaulted to "publishes iff it has geometry" so the
     * fake reads as a real backend in the ordinary tests.
     */
    private class GeometryBackend(
        private val text: String,
        private val geometry: SegmentGeometry?,
        private val publishes: Boolean = geometry != null,
        /**
         * The NPU tier's own answer (4.11 Task 4): `[t0cs, t1cs, byteStart, byteEnd]` per
         * SENTENCE, offsets into the string [transcribe] returns. Empty is 4.10.1's shape and
         * the default, so every test written before this one keeps its answer.
         */
        private val sentences: IntArray = IntArray(0),
    ) : WhisperBackend {
        override fun load(modelPath: String): Long = 42L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String = text
        override fun lastGeometry(ctx: Long): SegmentGeometry? = geometry
        override fun lastSentences(ctx: Long): IntArray = sentences
        override val publishesGeometry: Boolean get() = publishes
        override fun release(ctx: Long) = Unit
    }

    /**
     * Commits ONE chunk through a real engine and a real assigner and returns what the assigner
     * published — or null if it published nothing, which is itself an answer this class asserts.
     *
     * [segments] is what `WhisperNative.vadSegmentsOf` would have said: `[start, end]` sample
     * pairs on the chunk's own timeline.
     */
    private fun commitOne(
        geometry: SegmentGeometry?,
        segments: IntArray = intArrayOf(0, 48_000, 64_000, 112_000),
        vadPath: String? = "/data/vad/silero.bin",
        attachAssigner: Boolean = true,
        text: String = " Hello world.",
        publishesGeometry: Boolean = geometry != null,
        sentences: IntArray = IntArray(0),
        onSegmenterCall: () -> Unit = {},
        onResolved: (SegmentOutcome) -> Unit = {},
    ): SpeakerAssignment? {
        val seen = AtomicReference<SpeakerAssignment?>(null)
        val assigner = SpeakerAssigner(
            voices = TwoVoices(),
            onAssigned = { seen.set(it) },
            segmenter = { _, _ -> onSegmenterCall(); segments },
        )
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/small-q8.bin"),
            retry = fastRetry(),
            backend = GeometryBackend(text, geometry, publishesGeometry, sentences),
            executor = SameThreadExecutorService(),
            vadModelPath = { vadPath },
        )
        if (attachAssigner) engine.speakerAssigner = assigner
        val listener = RecordingListener()
        try {
            engine.connect(language = "en", listener = listener)
            engine.sendAudio(pcm)
            engine.commit()
            // The assigner's own thread is the point of the design; wait for it, never for text.
            assertTrue("the embed thread drained", assigner.awaitIdle(5_000))
        } finally {
            assigner.release()
        }
        listener.resolved.forEach { (_, outcome) -> onResolved(outcome) }
        return seen.get()
    }

    /**
     * Two VAD segments of 3 s, one decoded segment in each — the CPU tier's ordinary chunk.
     * Neither is split (a split needs two whisper segments inside one VAD segment), so there is
     * one window per segment and each is long enough to open a speaker.
     *
     * The byte ranges cut " Hello world." into "Hello" and "world.".
     */
    private fun cpuGeometry() = SegmentGeometry(
        vadSegments = intArrayOf(0, 48_000, 0, 48_000, 64_000, 112_000, 48_000, 96_000),
        whisperSegments = intArrayOf(0, 300, 0, 6, 300, 600, 6, 13),
    )

    @Test
    fun theCpuTiersChunkStillGetsOneIdPerWindowAndNoWholeChunkPick() {
        // THE REGRESSION GUARD. Every downstream reader branches on `wholeChunkWindow`: non-null
        // makes the chunk's whole text wear ONE label. If the VAD route ever claimed a chunk that
        // has geometry, a sentence-labelled chunk would collapse to a single speaker — and every
        // id in it would still be a real id, so nothing else would report it.
        val assignment = commitOne(geometry = cpuGeometry())
        assertNotNull(assignment)
        assertEquals("one id per window, as it has been since 4.10", 2, assignment!!.ids.size)
        assertEquals(listOf(1, 2), assignment.ids)
        assertNull("…and the geometry route picks no single window", assignment.wholeChunkWindow)
        assertEquals(2, assignment.segs)
    }

    @Test
    fun theCpuTiersChunkNeverRunsASecondVad() {
        // The VAD route costs ~60 ms per chunk. A chunk that already has geometry must not pay
        // it — and the cheapest proof that the routes are exclusive is that the segmenter is
        // never reached at all.
        val calls = AtomicInteger(0)
        commitOne(geometry = cpuGeometry(), onSegmenterCall = { calls.incrementAndGet() })
        assertEquals("no second VAD on a chunk that has geometry", 0, calls.get())
    }

    @Test
    fun aBackendWithNoGeometryTakesTheWholeChunkRouteAndLabelsTheChunkOnce() {
        // The NPU tier. Two windows are still fingerprinted — the tracker learns both voices —
        // but the chunk's TEXT can only wear one label, because that decoder publishes no token
        // timestamps to cut it by.
        val assignment = commitOne(
            geometry = null,
            segments = intArrayOf(0, 32_000, 48_000, 112_000),
        )
        assertNotNull(assignment)
        assertEquals(listOf(1, 2), assignment!!.ids)
        assertEquals("the 4 s window beats the 2 s one", 1, assignment.wholeChunkWindow)
    }

    @Test
    fun aChunkWithGeometryThatProducedNoSpansKeepsTodaysAnswerInsteadOfTheVadRoute() {
        // THE DISTINCTION THE ROUTE TEST TURNS ON. An empty VAD array is real geometry saying
        // "no speech found" — the spec forbids reading it as one speaker, and it is a CPU-tier
        // chunk. Gating the new route on "the outcome has no windows" instead of "the backend
        // published no geometry" would drag this chunk, and every stale-snapshot chunk, into a
        // second VAD pass and a whole-chunk label where today they correctly get nothing.
        val calls = AtomicInteger(0)
        val assignment = commitOne(
            geometry = SegmentGeometry(
                vadSegments = IntArray(0),
                whisperSegments = intArrayOf(0, 200, 0, 12),
            ),
            onSegmenterCall = { calls.incrementAndGet() },
        )
        assertNull("nothing is published for this chunk, exactly as before", assignment)
        assertEquals("and no second VAD is run for it", 0, calls.get())
    }

    @Test
    fun aCpuChunkWhoseGeometrySNAPSHOTWasLostKeepsTodaysAnswerRatherThanTheVadRoute() {
        // THE CASE THAT CORRECTED THE GATE (round 1 of review). `WhisperNativeBackend.lastGeometry`
        // answers null for a chunk it transcribed perfectly well in two ordinary ways:
        // `captureGeometry`'s `runCatching` swallowing an allocation failure, and the
        // process-global slot being re-tagged by an interleaved BatchTranscriber hold between the
        // transcribe and the read — `transcribeInternal` clears `lastGeom`/`lastGeomCtx` at the
        // top of every gate hold, and the engine's own comment names the race.
        //
        // The chunk is `Text`, non-blank, with samples — so a gate that read "was this chunk's
        // geometry null" fired, and the chunk paid a ~60 ms second VAD pass and came back wearing
        // ONE label for text that whisper had per-sentence timestamps for. The gate is a BACKEND
        // CAPABILITY instead: this backend publishes geometry, this read just missed it, and the
        // chunk keeps 4.9's answer of nothing at all.
        val calls = AtomicInteger(0)
        val assignment = commitOne(
            geometry = null,
            publishesGeometry = true,
            onSegmenterCall = { calls.incrementAndGet() },
        )
        assertNull("no labels for the chunk that lost its snapshot, exactly as in 4.10", assignment)
        assertEquals("and no second VAD pass was paid for it", 0, calls.get())
    }

    @Test
    fun aBackendThatSaysNothingAboutGeometryIsReadAsPublishingIt() {
        // The interface default is TRUE, and it is deliberately not paired with `lastGeometry`'s
        // null default: a backend that forgets to declare loses its tier's labels, which is a
        // missing feature, where the other default's worst case is a wrong label on text the user
        // has already read. `GeometryBackend` overrides the flag, so the default is asserted on a
        // fake that overrides neither member.
        val bare = object : WhisperBackend {
            override fun load(modelPath: String): Long = 42L
            override fun transcribe(
                ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean,
            ): String = " Hello world."
            override fun release(ctx: Long) = Unit
        }
        assertTrue("the default is the conservative one", bare.publishesGeometry)
        assertNull("…and it answers no geometry all the same", bare.lastGeometry(42L))

        val calls = AtomicInteger(0)
        val seen = AtomicReference<SpeakerAssignment?>(null)
        val assigner = SpeakerAssigner(
            voices = TwoVoices(),
            onAssigned = { seen.set(it) },
            segmenter = { _, _ -> calls.incrementAndGet(); intArrayOf(0, 48_000, 64_000, 112_000) },
        )
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/small-q8.bin"),
            retry = fastRetry(),
            backend = bare,
            executor = SameThreadExecutorService(),
            vadModelPath = { "/data/vad/silero.bin" },
        )
        engine.speakerAssigner = assigner
        try {
            engine.connect(language = "en", listener = RecordingListener())
            engine.sendAudio(pcm)
            engine.commit()
            assertTrue(assigner.awaitIdle(5_000))
        } finally {
            assigner.release()
        }
        assertNull(seen.get())
        assertEquals(0, calls.get())
    }

    @Test
    fun noAssignerMeansNeitherRoute_whichIsDetectionOffAndEverySessionWithACloudEngine() {
        // The assigner is built only for a local session with the setting on
        // (`FloatingBubbleService.startRecording`), so its presence IS the "not cloud, detection
        // on" gate — and a cloud session's local engine, which is only its fallback, must do no
        // fingerprinting and run no VAD of its own.
        val calls = AtomicInteger(0)
        val assignment = commitOne(
            geometry = null,
            attachAssigner = false,
            onSegmenterCall = { calls.incrementAndGet() },
        )
        assertNull(assignment)
        assertEquals(0, calls.get())
    }

    @Test
    fun noVadModelOnTheDeviceSkipsTheRouteRatherThanInventingAPath() {
        val calls = AtomicInteger(0)
        val assignment = commitOne(
            geometry = null,
            vadPath = null,
            onSegmenterCall = { calls.incrementAndGet() },
        )
        assertNull("no model, no labels — and no text lost either", assignment)
        assertEquals(0, calls.get())
    }

    @Test
    fun aBlankChunkTakesNeitherRoute_becauseThereIsNoTextToLabel() {
        // A blank result is `EmptyExpected` (the native VAD proved silence), never `Text`, so
        // there is no committed text for a whole-chunk label to sit on. Publishing one would put
        // a speaker on a chunk the user never sees.
        val calls = AtomicInteger(0)
        val assignment = commitOne(
            geometry = null,
            text = "   ",
            onSegmenterCall = { calls.incrementAndGet() },
        )
        assertNull(assignment)
        assertEquals(0, calls.get())
    }

    @Test
    fun theWholeChunkAnswerReachesARunTheRetrospectivePassCanStillCorrect() {
        // End to end through the sink's own patchers: an NPU chunk arrives with no spans, becomes
        // one run at NO_WINDOW_INDEX, and the assignment gives it BOTH the dominant window's
        // index and its id — which is what keeps it inside the retrospective pass's reach.
        val assignment = commitOne(
            geometry = null,
            segments = intArrayOf(0, 32_000, 48_000, 112_000),
        )!!
        val runs = SpeakerRuns.of(seq = 0L, text = "Hello world.", spans = null)
        assertEquals(1, runs.size)
        assertEquals(SpeakerRuns.NO_WINDOW_INDEX, runs.single().windowIndex)

        val pick = assignment.wholeChunkWindow!!
        SpeakerRuns.applyAssignment(runs, seq = assignment.seq, ids = assignment.ids)
        assertNull("the per-window stamp finds nothing to do here", runs.single().speakerId)
        SpeakerRuns.applyWholeChunk(
            runs, seq = assignment.seq, windowIndex = pick, id = assignment.ids[pick],
        )
        assertEquals(pick, runs.single().windowIndex)
        assertEquals(assignment.ids[pick], runs.single().speakerId)
    }

    // ------------------------ the VAD route WITH sentence bounds (4.11 Task 4)

    /** " Hello there. And you. Who is this? Nobody." — four sentences that TILE the bytes. */
    private val fourSentenceText = " Hello there. And you. Who is this? Nobody."

    /** `[t0cs, t1cs, byteStart, byteEnd]` * 4 over [fourSentenceText], 2 s per sentence. */
    private fun fourSentences(): IntArray {
        val b = fourSentenceText.toByteArray(Charsets.UTF_8)
        val edges = intArrayOf(0, 13, 22, 35, b.size)
        return IntArray(16) { i ->
            val s = i / 4
            when (i % 4) {
                0 -> s * 200
                1 -> (s + 1) * 200
                2 -> edges[s]
                else -> edges[s + 1]
            }
        }
    }

    @Test
    fun anNpuChunkWithSENTENCEBoundsBecomesOneWindowAndOneRunPerSentence() {
        // SESSION 7'S FAILURE, END TO END. One pause-free VAD segment over the whole chunk —
        // exactly what hard-cut media gives Silero — used to be ONE window and ONE label. With
        // the decoder's own bounds it is four of each, and the text splits where the sentences do.
        val outcomes = ArrayList<SegmentOutcome>()
        val assignment = commitOne(
            geometry = null,
            segments = intArrayOf(0, 128_000),
            text = fourSentenceText,
            sentences = fourSentences(),
            onResolved = { outcomes += it },
        )
        assertNotNull(assignment)
        assertEquals("four windows, one per sentence", 4, assignment!!.ids.size)

        val committed = outcomes.filterIsInstance<SegmentOutcome.Text>().single()
        assertNull("the VAD route publishes no WINDOW list — that is what picks the route", committed.windows)
        assertEquals(
            listOf("Hello there.", "And you.", "Who is this?", "Nobody."),
            committed.spans?.map { it.text },
        )
        assertEquals(listOf(0, 1, 2, 3), committed.spans?.map { it.windowIndex })

        val runs = SpeakerRuns.of(seq = assignment.seq, text = committed.text, spans = committed.spans)
        assertEquals("one run per sentence, not one per chunk", 4, runs.size)
        SpeakerRuns.applyAssignment(runs, seq = assignment.seq, ids = assignment.ids)
        assertEquals(
            "…and each run wears its OWN window's id",
            assignment.ids.filter { it > 0 }.size,
            runs.count { it.speakerId != null },
        )
    }

    @Test
    fun theSpansAndTheWindowsAreCutFromTheSAMEsentenceArray() {
        // The engine cuts the TEXT on the native thread and the assigner cuts the AUDIO ~60 ms
        // later on the embed thread; the two are matched by POSITION and never meet. So a span's
        // index must be a real index into `ids` — one array read once, handed to both halves.
        val outcomes = ArrayList<SegmentOutcome>()
        val assignment = commitOne(
            geometry = null,
            segments = intArrayOf(0, 128_000),
            text = fourSentenceText,
            sentences = fourSentences(),
            onResolved = { outcomes += it },
        )!!
        val spans = outcomes.filterIsInstance<SegmentOutcome.Text>().single().spans!!
        assertTrue(
            "every span indexes a window the assigner actually fingerprinted",
            spans.all { it.windowIndex in assignment.ids.indices },
        )
    }

    @Test
    fun anNpuChunkWithNoSentenceBoundsStillGetsTheWholeChunkAnswer() {
        // The empty array is 4.10.1's behaviour and it has to stay reachable: a decode that
        // emitted no timestamps, a parse that refused a backwards stream, or a blanked segment.
        val outcomes = ArrayList<SegmentOutcome>()
        val assignment = commitOne(
            geometry = null,
            segments = intArrayOf(0, 32_000, 48_000, 112_000),
            onResolved = { outcomes += it },
        )
        assertNotNull(assignment)
        assertEquals(listOf(1, 2), assignment!!.ids)
        assertEquals("the 4 s window beats the 2 s one", 1, assignment.wholeChunkWindow)
        val committed = outcomes.filterIsInstance<SegmentOutcome.Text>().single()
        assertNull("no spans, exactly as in 4.10.1", committed.spans)
        assertNull(committed.windows)
    }

    @Test
    fun aChunkThatHasGEOMETRYIgnoresAnySentenceBoundsBesideIt() {
        // The CPU tier's own timing is strictly finer — per TOKEN since Task 1 — so a backend
        // that published both must answer from the geometry. Reading the coarser array would
        // hand a sentence-cut chunk the sentence's label for words the tokens had already
        // separated.
        val outcomes = ArrayList<SegmentOutcome>()
        val assignment = commitOne(
            geometry = cpuGeometry(),
            sentences = fourSentences(),
            onResolved = { outcomes += it },
        )
        assertNotNull(assignment)
        assertNull("still the geometry route", assignment!!.wholeChunkWindow)
        val committed = outcomes.filterIsInstance<SegmentOutcome.Text>().single()
        assertNotNull("…and its WINDOW list, which the VAD route never publishes", committed.windows)
        assertEquals(listOf("Hello", "world."), committed.spans?.map { it.text })
    }

    @Test
    fun theWindowsTheVadRouteBuildsAreTheOnesItFingerprints() {
        // The coalescing rule is the assigner's, not the engine's, and the engine must not
        // second-guess it: two short segments become one window, one embedding, one id.
        val voices = TwoVoices()
        val seen = AtomicReference<SpeakerAssignment?>(null)
        val assigner = SpeakerAssigner(
            voices = voices,
            onAssigned = { seen.set(it) },
            segmenter = { _, _ -> intArrayOf(0, 32_000, 32_500, 36_000) },
        )
        val engine = LocalWhisperEngine(
            modelPathProvider = FakeModelPathProvider("/models/small-q8.bin"),
            retry = fastRetry(),
            backend = GeometryBackend(" Hello world.", null),
            executor = SameThreadExecutorService(),
            vadModelPath = { "/data/vad/silero.bin" },
        )
        engine.speakerAssigner = assigner
        try {
            engine.connect(language = "en", listener = RecordingListener())
            engine.sendAudio(pcm)
            engine.commit()
            assertTrue(assigner.awaitIdle(5_000))
        } finally {
            assigner.release()
        }
        val assignment = seen.get()!!
        assertEquals("two speech segments", 2, assignment.segs)
        assertEquals("…one window", 1, assignment.ids.size)
        assertEquals("…one embedding", 1, voices.calls.get())
        assertEquals(0, assignment.wholeChunkWindow)
    }
}
