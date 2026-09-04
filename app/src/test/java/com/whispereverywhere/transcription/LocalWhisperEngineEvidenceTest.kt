package com.whispereverywhere.transcription

import com.whispereverywhere.audio.Endpointer
import com.whispereverywhere.audio.EndpointerTuning
import com.whispereverywhere.service.SegmentQueueDepth
import com.whispereverywhere.util.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE SPEECH EVIDENCE at the engine (4.3.2): a commit whose evidence is KNOWN and under
 * [EndpointerTuning.MIN_SPEECH_EVIDENCE_MS] is resolved `EmptyExpected` WITHOUT a backend call —
 * still with a seq, still through the one resolution path, still in commit order — and every
 * other commit (KNOWN at or over the floor, UNKNOWN, the no-argument form, the overflow backstop,
 * the cloud fallback's rescue) runs exactly as it did before.
 *
 * The seq contract is the load-bearing half: a skipped seq that never resolved would stall the
 * `SegmentOrderer` head for the session and strand `SegmentQueueDepth` above zero — which is the
 * backpressure governor's slow floor latched for good (the 85 review named it). Both are wired
 * here as integrations, not asserted from the engine's word.
 */
class LocalWhisperEngineEvidenceTest {

    private val floor = EndpointerTuning.MIN_SPEECH_EVIDENCE_MS

    private fun fastRetry() = RetryPolicy(maxAttempts = 3, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 })

    /** Fifteen seconds of PCM16 @ 16 kHz — the owner's silent cap window. */
    private val fifteenSeconds = ByteArray(15_000 * 32)

    private fun engineWith(
        backend: WhisperBackend,
        executor: java.util.concurrent.ExecutorService = SameThreadExecutorService(),
    ) = LocalWhisperEngine(
        modelPathProvider = FakeModelPathProvider("/models/pro.bin"),
        retry = fastRetry(),
        backend = backend,
        executor = executor,
    )

    private fun known(ms: Long) = SpeechEvidence.of(ms)

    /** Runs connect()'s queued model load so the session is open, then starts a clean queue. */
    private fun open(executor: QueueingExecutorService) {
        executor.tasks.forEach { it.run() }
        executor.tasks.clear()
    }

    // ---------------------------------------------------------------- the skip

    @Test
    fun knownEvidenceUnderTheFloorResolvesEmptyExpectedWithASeqAndNoBackendCall() {
        val backend = FakeWhisperBackend(text = "Thank you for watching")
        val engine = engineWith(backend)
        val listener = RecordingListener()
        engine.connect(language = null, listener = listener)
        engine.sendAudio(fifteenSeconds)

        assertEquals("the seq is allocated like any other", 0L, engine.commit(known(96L)))

        assertTrue("no encode, no decode: the backend never saw the audio", backend.transcribeCalls.isEmpty())
        assertEquals(listOf(0L to SegmentOutcome.EmptyExpected), listener.resolved)
        assertTrue("a skipped segment never streamed, so it never clears the strip", listener.deltas.isEmpty())
        assertTrue("a skip is not an error", listener.errors.isEmpty())
    }

    @Test
    fun theFloorIsStrictExactlyTheFloorIsEncoded() {
        val backend = FakeWhisperBackend(text = "yes")
        val engine = engineWith(backend)
        val listener = RecordingListener()
        engine.connect(language = null, listener = listener)

        engine.sendAudio(fifteenSeconds)
        engine.commit(known(floor))
        assertEquals("$floor ms of evidence — exactly the floor — is encoded", 1, backend.transcribeCalls.size)

        engine.sendAudio(fifteenSeconds)
        engine.commit(known(floor - 1))
        assertEquals("${floor - 1} is not", 1, backend.transcribeCalls.size)

        assertEquals(
            listOf(0L to SegmentOutcome.Text("yes"), 1L to SegmentOutcome.EmptyExpected),
            listener.resolved,
        )
    }

    @Test
    fun knownEvidenceAtOrOverTheFloorAndUnknownEvidenceBothTranscribeAsToday() {
        val backend = FakeWhisperBackend(text = "hello world")
        val engine = engineWith(backend)
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)

        engine.sendAudio(fifteenSeconds)
        engine.commit(known(640L))
        engine.sendAudio(fifteenSeconds)
        engine.commit(SpeechEvidence.UNKNOWN)
        engine.sendAudio(fifteenSeconds)
        engine.commit()                                   // the no-argument form IS unknown
        engine.sendAudio(fifteenSeconds)
        engine.commit(SpeechEvidence.of(Endpointer.UNKNOWN_SPEECH_EVIDENCE_MS))

        assertEquals(4, backend.transcribeCalls.size)
        assertEquals((0L..3L).map { it to SegmentOutcome.Text("hello world") }, listener.resolved)
    }

    @Test
    fun aScoredBufferOfPureSilenceIsZeroAndSkipped() {
        // The owner's report, exactly: the endpointer scored every frame and none was speech.
        val backend = FakeWhisperBackend(text = "字幕由Amara.org社区提供")
        val engine = engineWith(backend)
        val listener = RecordingListener()
        engine.connect(language = null, listener = listener)
        engine.sendAudio(fifteenSeconds)
        engine.commit(known(0L))
        assertTrue(backend.transcribeCalls.isEmpty())
        assertEquals(listOf(0L to SegmentOutcome.EmptyExpected), listener.resolved)
    }

    // ---------------------------------------------------------------- the paths that never skip

    @Test
    fun theOverflowBackstopsForcedCommitIsNeverSkipped() {
        // The 30 s backstop commits from inside sendAudio with no endpointer in sight: UNKNOWN.
        val backend = FakeWhisperBackend(text = "capped")
        val engine = engineWith(backend)
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        val chunk = ByteArray(3200)
        repeat(30 * 16000 * 2 / chunk.size + 1) { engine.sendAudio(chunk) }
        assertEquals("the backstop fired", 1, backend.transcribeCalls.size)
        assertEquals(listOf(0L to SegmentOutcome.Text("capped")), listener.resolved)
    }

    @Test
    fun theStopFlushObeysTheSameRule() {
        // stopRecording's unconditional flush is a funnel commit like any other: the trailing
        // room tone between the last word and the stop tap, scored silent, is skipped; a drain
        // then finds the skip already resolved.
        val backend = FakeWhisperBackend(text = "last words")
        val engine = engineWith(backend)
        val listener = RecordingListener()
        engine.connect(language = null, listener = listener)

        engine.sendAudio(fifteenSeconds)
        engine.commit(known(2_000L))                       // the last utterance, encoded
        engine.sendAudio(ByteArray(800 * 32))
        assertEquals(1L, engine.commit(known(0L)))         // the stop flush: 800 ms of room tone
        assertTrue(engine.awaitIdle(1_000L))
        engine.close()

        assertEquals(1, backend.transcribeCalls.size)
        assertEquals(
            listOf(0L to SegmentOutcome.Text("last words"), 1L to SegmentOutcome.EmptyExpected),
            listener.resolved,
        )
        assertTrue(listener.closed)
    }

    @Test
    fun aRetainingCapCutUnderTheFloorSkipsTheCutAndStillRetainsTheTail() {
        val backend = SampleCountBackend()
        val engine = engineWith(backend)
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)

        engine.sendAudio(ByteArray(2_000 * 32))                     // 2 s
        assertEquals(0L, engine.commitRetainingTailMs(100L, known(64L)))
        assertEquals(listOf(0L to SegmentOutcome.EmptyExpected), listener.resolved)

        // The tail is still there and opens the next segment: 100 ms = 1 600 samples.
        assertEquals(1L, engine.commit(SpeechEvidence.UNKNOWN))
        assertEquals(listOf(0L to SegmentOutcome.EmptyExpected, 1L to SegmentOutcome.Text("n1600")), listener.resolved)
    }

    @Test
    fun aRetainOfZeroWithEvidenceIsExactlyTheEvidenceCommit() {
        val backend = FakeWhisperBackend(text = "x")
        val engine = engineWith(backend)
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        engine.sendAudio(fifteenSeconds)
        assertEquals(0L, engine.commitRetainingTailMs(0L, known(0L)))
        assertTrue(backend.transcribeCalls.isEmpty())
        assertEquals(listOf(0L to SegmentOutcome.EmptyExpected), listener.resolved)
    }

    @Test
    fun theNoListenerAndEmptyBufferAnswersAreUnchangedByEvidence() {
        val backend = FakeWhisperBackend()
        val engine = engineWith(backend)
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        assertEquals("empty buffer: nothing to cut, no seq owed", -1L, engine.commit(known(0L)))
        engine.close()
        engine.sendAudio(fifteenSeconds)
        assertEquals("no listener: nothing owed", -1L, engine.commit(known(0L)))
        assertTrue(listener.resolved.isEmpty())
    }

    @Test
    fun theCloudEnginesDropTheEvidenceOnTheInterfaceDefault() {
        // Every `override fun commit(): Long` in the three cloud engines keeps overriding, and the
        // evidence-carrying member lands on it: an engine that never heard of evidence commits.
        var commits = 0
        val engine = object : TranscriptionEngine {
            override fun connect(language: String?, listener: TranscriptionEngine.Listener) = Unit
            override fun sendAudio(pcm: ByteArray) = Unit
            override fun commit(): Long = (commits++).toLong()
            override fun close() = Unit
        }
        assertEquals(0L, engine.commit(known(0L)))
        assertEquals(1L, engine.commitRetainingTailMs(500L, known(0L)))
        assertEquals("both defaults reached the one real commit", 2, commits)
    }

    // ---------------------------------------------------------------- the seq contract

    @Test
    fun aSkippedSeqResolvesExactlyOnceInCommitOrderOnTheExecutor() {
        // Real ordering, made visible: the skip is queued behind the segment ahead of it and
        // resolves after it, never from the committing thread.
        val executor = QueueingExecutorService()
        val backend = FakeWhisperBackend(text = "one")
        val engine = engineWith(backend, executor)
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        open(executor)

        engine.sendAudio(fifteenSeconds)
        assertEquals(0L, engine.commit(known(640L)))
        engine.sendAudio(fifteenSeconds)
        assertEquals(1L, engine.commit(known(0L)))
        assertTrue("nothing resolves on the committing thread", listener.resolved.isEmpty())
        assertEquals("two tasks, one per seq", 2, executor.tasks.size)

        executor.tasks[0].run()
        assertEquals(listOf(0L to SegmentOutcome.Text("one")), listener.resolved)
        executor.tasks[1].run()
        assertEquals(
            listOf(0L to SegmentOutcome.Text("one"), 1L to SegmentOutcome.EmptyExpected),
            listener.resolved,
        )
        assertEquals("exactly once", 1, listener.resolved.count { it.first == 1L })
    }

    @Test
    fun aSkippedSeqAfterCloseIsDroppedByTheSameIdentityGuardAsARunOne() {
        val executor = QueueingExecutorService()
        val engine = engineWith(FakeWhisperBackend(), executor)
        val listener = RecordingListener()
        engine.connect(language = "en", listener = listener)
        open(executor)
        engine.sendAudio(fifteenSeconds)
        engine.commit(known(0L))
        engine.close()
        executor.tasks.forEach { it.run() }
        assertTrue("a dead session's late skip resolves nothing", listener.resolved.isEmpty())
    }

    @Test
    fun segmentQueueDepthDecrementsOnASkippedSeq() {
        // THE BACKPRESSURE WIRING (85): the depth is a SET of in-flight seqs keyed on
        // onSegmentResolved arriving; a seq that never arrives strands it. Published depths must
        // therefore go 1 -> 0 across a skipped seq, exactly as across an encoded one.
        val published = mutableListOf<Int>()
        val depth = SegmentQueueDepth(onDepth = { published += it })
        val executor = QueueingExecutorService()
        val engine = engineWith(FakeWhisperBackend(text = "t"), executor)
        val listener = object : TranscriptionEngine.Listener {
            override fun onOpen() = Unit
            override fun onDelta(text: String) = Unit
            override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) { depth.onResolved(seq) }
            override fun onError(message: String) = Unit
            override fun onClosed() = Unit
        }
        engine.connect(language = "en", listener = listener)
        open(executor)

        engine.sendAudio(fifteenSeconds)
        depth.onCommitted(engine.commit(known(640L)))
        engine.sendAudio(fifteenSeconds)
        depth.onCommitted(engine.commit(known(32L)))
        assertEquals(2, depth.depth())
        executor.tasks.forEach { it.run() }
        assertEquals("the skipped seq resolved and was removed", 0, depth.depth())
        assertEquals(listOf(1, 2, 1, 0), published)
    }

    @Test
    fun segmentOrdererReleasesInOrderAcrossASkippedSeq() {
        // Resolutions arriving OUT of order (the cloud shape, and the shape a future parallel
        // executor would produce): the skipped seq neither blocks the head nor contributes text,
        // and the two real segments release in seq order in one string.
        val orderer = SegmentOrderer()
        val executor = QueueingExecutorService()
        // The backend answers in CALL order, and the tasks below run seq 2 first, then seq 0
        // (the skipped seq 1 never calls it): so "three" is scripted first and "one" second.
        val engine = engineWith(ScriptedBackend(listOf({ "three" }, { "one" })), executor)
        var released = ""
        val listener = object : TranscriptionEngine.Listener {
            override fun onOpen() = Unit
            override fun onDelta(text: String) = Unit
            override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
                released += orderer.onResolved(seq, outcome).text
            }
            override fun onError(message: String) = Unit
            override fun onClosed() = Unit
        }
        engine.connect(language = "en", listener = listener)
        open(executor)

        engine.sendAudio(fifteenSeconds); engine.commit(known(640L))   // seq 0: "one"
        engine.sendAudio(fifteenSeconds); engine.commit(known(0L))     // seq 1: skipped
        engine.sendAudio(fifteenSeconds); engine.commit(known(640L))   // seq 2: "three"
        assertEquals(3, executor.tasks.size)

        executor.tasks[2].run()
        assertEquals("held: seq 0 has not landed", "", released)
        assertEquals(1, orderer.pendingCount())
        executor.tasks[1].run()
        assertEquals("the skip is held too, contributes nothing, blocks nothing", "", released)
        executor.tasks[0].run()
        assertEquals("one three", released)
        assertEquals(0, orderer.pendingCount())
    }

    // ---------------------------------------------------------------- source pins

    /**
     * What `android.util.Log` cannot show on the JVM (it is a no-op under
     * `unitTests.isReturnDefaultValues`), pinned on the source: the skip line exists and carries
     * the numbers the brief names, the floor is read from its ONE owner, and the resolution path
     * has ONE `onSegmentResolved` site that both the run and the skip reach.
     */
    @Test
    fun theSkipLineTheFloorReadAndTheSingleResolutionSiteArePinnedInTheSource() {
        val src = source("src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt")
        assertEquals(
            "the skip line is emitted from exactly one place and names seq, the reason, the " +
                "evidence and the audio length — numbers only, never content",
            1,
            liveLineCount(src, "\"commit: seq=\$seq skipped=no-speech-evidence speechMs=\${evidence.speechMs} \""),
        )
        assertEquals(1, liveLineCount(src, "\"pcmMs=\${pcm.size / BYTES_PER_MS}\""))
        assertEquals(
            "the floor has one owner (EndpointerTuning) and one reader (dispatch)",
            1,
            liveLineCount(src, "EndpointerTuning.MIN_SPEECH_EVIDENCE_MS"),
        )
        assertEquals(
            "ONE onSegmentResolved site: the run and the skip both end in resolve()",
            1,
            liveLineCount(src, "myListener.onSegmentResolved(seq, outcome)"),
        )
        assertEquals(
            "the skip resolves on the executor, in commit order, never on the committing thread",
            1,
            liveLineCount(src, "executor.execute { resolve(seq, SegmentOutcome.EmptyExpected, clearPreview = false, myListener) }"),
        )
        assertEquals("the backend is reached from runSegment alone", 1, liveLineCount(src, "backend.transcribeStreaming("))
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun liveLineCount(scope: String, needle: String): Int =
        scope.lineSequence().count { line ->
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            !commented && line.contains(needle)
        }
}
