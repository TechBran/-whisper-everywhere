package com.whispereverywhere.transcription.cloud

import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.LocalWhisperEngine
import com.whispereverywhere.transcription.ModelPathProvider
import com.whispereverywhere.transcription.SameThreadExecutorService
import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.TranscriptionEngine
import com.whispereverywhere.transcription.WhisperBackend
import com.whispereverywhere.util.RetryPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The decorator's job is narrow and its failure modes are all silent, so these tests are written
 * against the three that actually bite:
 *
 *  * the audio is gone — the cloud engine discards each segment's PCM on commit(), so a fallback
 *    that did not keep its own copy has nothing to retry;
 *  * the seq is wrong — the local engine owns its own counter, so a retry's resolution has to be
 *    re-labelled with the ORIGINAL seq or the orderer never releases;
 *  * the seq never arrives — every give-up path (local refuses, local throws, the session closes
 *    or restarts mid-retry) still owes exactly one resolution.
 */
class FallbackTranscriptionEngineTest {

    private val scopes = Collections.synchronizedList(mutableListOf<CoroutineScope>())

    @After fun cancelScopes() {
        scopes.forEach { it.cancel() }
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes += it }

    // ---------------------------------------------------------------- fakes

    /**
     * A scriptable [TranscriptionEngine] that buffers audio, hands out seqs and — by default —
     * defers every resolution until the test asks for it. [seqBase] starts its counter somewhere
     * other than zero so a test can prove the decorator re-labels rather than forwards.
     */
    private class FakeEngine(private val seqBase: Long = 0L) : TranscriptionEngine {
        @Volatile var owner: TranscriptionEngine.Listener? = null
        @Volatile private var attached: TranscriptionEngine.Listener? = null
        private val buffer = ByteArrayOutputStream()
        private var nextSeq = seqBase

        val commits = Collections.synchronizedList(mutableListOf<ByteArray>())
        val languages = Collections.synchronizedList(mutableListOf<String?>())
        var connects = 0; var closes = 0; var shutdowns = 0
        var prewarms = 0; var contextReleases = 0
        val awaitIdleCalls = Collections.synchronizedList(mutableListOf<Long>())
        var commitsWhenAwaitIdleCalled = -1

        /** When set, commit() returns this instead of cutting a segment. */
        var commitOverride: Long? = null
        /** When set, commit() throws it. */
        var commitThrow: Throwable? = null
        /** When set, commit() resolves the segment SYNCHRONOUSLY, before it returns a seq. */
        var resolveInline: ((ByteArray) -> SegmentOutcome)? = null

        override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
            connects++
            languages += language
            owner = listener
            attached = listener
            buffer.reset()
            nextSeq = seqBase
            listener.onOpen()
        }

        override fun sendAudio(pcm: ByteArray) { synchronized(buffer) { buffer.write(pcm) } }

        override fun commit(): Long {
            commitThrow?.let { throw it }
            commitOverride?.let { return it }
            val l = attached ?: return -1L
            val pcm = synchronized(buffer) {
                val snapshot = buffer.toByteArray()
                if (snapshot.isEmpty()) return -1L
                buffer.reset()
                snapshot
            }
            val seq = nextSeq++
            commits += pcm
            resolveInline?.let { f ->
                l.onSegmentResolved(seq, f(pcm))
                return seq
            }
            return seq
        }

        /** Resolves as the engine would, to the listener that cut the segment. */
        fun resolve(seq: Long, outcome: SegmentOutcome) {
            owner?.onSegmentResolved(seq, outcome)
        }

        fun emitError(message: String) { owner?.onError(message) }
        fun emitDelta(text: String) { owner?.onDelta(text) }

        override fun close() {
            closes++
            val l = attached
            attached = null
            l?.onClosed()
        }

        override fun prewarm() { prewarms++ }
        override fun shutdown() { shutdowns++ }
        override fun releaseContext() { contextReleases++ }
        override fun awaitIdle(timeoutMs: Long): Boolean {
            awaitIdleCalls += timeoutMs
            commitsWhenAwaitIdleCalled = commits.size
            return true
        }
    }

    private class Rec : TranscriptionEngine.Listener {
        val opens = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val deltas = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val all = Collections.synchronizedList(mutableListOf<Pair<Long, SegmentOutcome>>())
        private val queue = LinkedBlockingQueue<Pair<Long, SegmentOutcome>>()

        override fun onOpen() { opens.incrementAndGet() }
        override fun onDelta(text: String) { deltas += text }
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
            all += seq to outcome
            queue.put(seq to outcome)
        }
        override fun onError(message: String) { errors += message }
        override fun onClosed() { closes.incrementAndGet() }

        fun next(timeoutMs: Long = 5_000): Pair<Long, SegmentOutcome> {
            val e = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            assertNotNull("expected a resolution within ${timeoutMs}ms", e)
            return e!!
        }
    }

    /** Minimal provider for the tests that need the REAL cloud engine underneath. */
    private class FakeStt(
        private val gate: (suspend (ByteArray) -> Unit)? = null,
        private val respond: (ByteArray) -> SttResult = { SttResult.Failed(SttError.Offline) },
    ) : SttProvider {
        override val id = ProviderId.OPENAI
        override val maxRequestBytes = 25L * 1024 * 1024
        val payloads = Collections.synchronizedList(mutableListOf<ByteArray>())
        override suspend fun transcribe(pcm: ByteArray, language: String?): SttResult {
            payloads += pcm
            gate?.invoke(pcm)
            return respond(pcm)
        }
    }

    private fun engine(cloud: TranscriptionEngine, local: TranscriptionEngine) =
        FallbackTranscriptionEngine(cloud, local, scope())

    // ---------------------------------------------------------------- lifecycle pass-through

    @Test fun connect_opens_through_and_close_closes_once() {
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect("de", l)
        assertEquals(1, l.opens.get())
        assertEquals("both engines join the session", 1, cloud.connects)
        assertEquals(1, local.connects)
        assertEquals("the language hint reaches both", listOf<String?>("de"), cloud.languages.toList())
        assertEquals(listOf<String?>("de"), local.languages.toList())

        e.close()
        assertEquals("the safety net's onClosed must not double-fire the session", 1, l.closes.get())
        assertEquals(1, cloud.closes)
        assertEquals(1, local.closes)
    }

    // ---------------------------------------------------------------- the policy, plumbed

    @Test fun a_cloud_loss_is_retried_on_local_under_the_original_seq() {
        // local's counter starts at 700, so forwarding local's own seq would be visible.
        val cloud = FakeEngine(); val local = FakeEngine(seqBase = 700L)
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1, 2, 3))
        assertEquals(0L, e.commit())

        cloud.resolve(0L, SegmentOutcome.Lost("offline"))
        assertEquals("the retry reached local", 1, local.commits.size)
        assertEquals("nothing is resolved until the retry answers", 0, l.all.size)

        local.resolve(700L, SegmentOutcome.Text("recovered"))
        assertEquals(1, l.all.size)
        assertEquals("re-labelled with the ORIGINAL seq", 0L, l.all.single().first)
        assertEquals(SegmentOutcome.Text("recovered"), l.all.single().second)
    }

    @Test fun successful_cloud_text_never_reaches_local() {
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        cloud.resolve(0L, SegmentOutcome.Text("hello"))
        assertEquals(0, local.commits.size)
        assertEquals(0L to SegmentOutcome.Text("hello"), l.all.single())
    }

    @Test fun an_empty_expected_never_reaches_local() {
        // The user said nothing. Re-running silence burns seconds to produce the same empty string.
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        cloud.resolve(0L, SegmentOutcome.EmptyExpected)
        assertEquals("silence must not be transcribed twice", 0, local.commits.size)
        assertEquals(0L to SegmentOutcome.EmptyExpected, l.all.single())
    }

    @Test fun an_unexpected_empty_is_retried_on_local() {
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(9)); e.commit()
        cloud.resolve(0L, SegmentOutcome.EmptyUnexpected)
        assertEquals(1, local.commits.size)
        local.resolve(0L, SegmentOutcome.Text("actually there was speech"))
        assertEquals(SegmentOutcome.Text("actually there was speech"), l.all.single().second)
    }

    // ---------------------------------------------------------------- the retained PCM

    @Test fun the_retry_replays_exactly_that_segments_pcm_not_the_whole_session() {
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        e.connect(null, Rec())
        e.sendAudio(byteArrayOf(1, 1)); assertEquals(0L, e.commit())
        e.sendAudio(byteArrayOf(2, 2)); assertEquals(1L, e.commit())

        cloud.resolve(1L, SegmentOutcome.Lost("offline"))
        assertArrayEquals("only the second segment's audio", byteArrayOf(2, 2), local.commits.single())
    }

    @Test fun the_decorator_keeps_its_own_pcm_because_the_cloud_engine_discards_it() {
        // The flagship case: a REAL CloudTranscriptionEngine consumes and resets its buffer inside
        // commit(), so by the time it reports the loss the audio only exists in our mirror.
        val provider = FakeStt(respond = { SttResult.Failed(SttError.Offline) })
        val cloud = CloudTranscriptionEngine(provider, scope())
        val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(7, 7, 7))
        e.sendAudio(byteArrayOf(8, 8))
        assertEquals(0L, e.commit())
        assertTrue(e.awaitIdle(5_000))

        assertEquals("cloud tried first", 1, provider.payloads.size)
        assertEquals("and the mirror survived cloud consuming its buffer", 1, local.commits.size)
        assertArrayEquals(byteArrayOf(7, 7, 7, 8, 8), local.commits.single())

        local.resolve(0L, SegmentOutcome.Text("saved"))
        assertEquals(0L to SegmentOutcome.Text("saved"), l.all.single())
    }

    @Test fun a_commit_the_cloud_refuses_keeps_the_mirrored_audio_for_the_next_cut() {
        // cloud.commit() returning -1 means nothing was cut; throwing the mirror away there would
        // silently delete audio the cloud engine is still holding.
        val cloud = FakeEngine(); val local = FakeEngine()
        cloud.commitOverride = -1L
        val e = engine(cloud, local)
        e.connect(null, Rec())
        e.sendAudio(byteArrayOf(4, 4))
        assertEquals(-1L, e.commit())

        cloud.commitOverride = null
        e.sendAudio(byteArrayOf(5, 5))
        assertEquals(0L, e.commit())
        cloud.resolve(0L, SegmentOutcome.Lost("offline"))
        assertArrayEquals(byteArrayOf(4, 4, 5, 5), local.commits.single())
    }

    @Test fun commit_with_nothing_buffered_allocates_no_seq_and_arms_nothing() {
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        assertEquals(-1L, e.commit())
        assertEquals(0, cloud.commits.size)
        assertEquals(0, local.commits.size)
        assertEquals(0, l.all.size)
    }

    // ---------------------------------------------------------------- the seq re-labelling

    @Test fun a_local_engine_that_resolves_inside_commit_still_resolves_the_original_seq() {
        // The dangerous ordering: local resolves BEFORE commit() has returned a seq to map it
        // against. LocalWhisperEngine on a same-thread executor does exactly this, and so would
        // any inline executor. Losing the resolution here stalls the orderer head forever.
        val cloud = FakeEngine()
        val local = FakeEngine(seqBase = 42L)
        local.resolveInline = { SegmentOutcome.Text("inline recovery") }
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()

        cloud.resolve(0L, SegmentOutcome.Lost("offline"))
        assertEquals("the seq must not be swallowed", 1, l.all.size)
        assertEquals(0L, l.all.single().first)
        assertEquals(SegmentOutcome.Text("inline recovery"), l.all.single().second)
    }

    @Test fun local_refusing_the_segment_surfaces_the_cloud_outcome_rather_than_stalling() {
        val cloud = FakeEngine(); val local = FakeEngine()
        local.commitOverride = -1L
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        cloud.resolve(0L, SegmentOutcome.Lost("offline"))
        assertEquals(0L to SegmentOutcome.Lost("offline"), l.all.single())
    }

    @Test fun local_commit_throwing_surfaces_the_cloud_outcome_rather_than_stalling() {
        val cloud = FakeEngine(); val local = FakeEngine()
        local.commitThrow = IllegalStateException("executor already shut down")
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        cloud.resolve(0L, SegmentOutcome.Lost("no network"))
        assertEquals(0L to SegmentOutcome.Lost("no network"), l.all.single())
    }

    @Test fun whispers_silence_verdict_reaches_the_user_as_silence_not_a_loss_marker() {
        // End to end through the decorator: the cloud lost the segment, the mirror handed its PCM
        // to the local engine, and whisper reported EmptyExpected — "I ran on this and there was
        // no speech in it". The user must see nothing, not a "[…]" marker claiming a sentence
        // disappeared. This is the tail turn of every live session (owner-reported 2026-07-31).
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        cloud.resolve(0L, SegmentOutcome.Lost("session ended"))
        local.resolve(0L, SegmentOutcome.EmptyExpected)
        assertEquals(0L to SegmentOutcome.EmptyExpected, l.all.single())
    }

    @Test fun a_local_engine_that_never_ran_does_not_erase_the_clouds_visible_loss() {
        // The safety net has no model installed, so LocalWhisperEngine resolves Lost rather than
        // claiming a silence verdict it never reached. The cloud's visible loss must survive —
        // otherwise a real sentence vanishes with nothing to show for it on exactly the devices
        // that have no rescue.
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        cloud.resolve(0L, SegmentOutcome.Lost("offline"))
        local.resolve(0L, SegmentOutcome.Lost("speech model not loaded"))
        assertEquals(0L to SegmentOutcome.Lost("offline"), l.all.single())
    }

    // ---------------------------------------------------------------- the one-way valve

    @Test fun the_valve_is_one_way_a_local_failure_never_ships_audio_to_the_cloud() {
        // A user who chose on-device must never have audio uploaded because a local load failed.
        // Here the LOCAL retry also fails; the cloud engine must see no second attempt.
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        assertEquals(1, cloud.commits.size)

        cloud.resolve(0L, SegmentOutcome.Lost("offline"))
        local.resolve(0L, SegmentOutcome.Lost("model missing"))

        assertEquals("no re-upload after a local failure", 1, cloud.commits.size)
        assertEquals("and no retry loop", 1, local.commits.size)
        assertEquals(0L to SegmentOutcome.Lost("offline"), l.all.single())
    }

    @Test fun local_lifecycle_callbacks_never_reach_the_user() {
        // "No speech model installed" is not an error for a user who chose cloud, and local emits
        // no deltas. Only the cloud path owns the session's visible lifecycle.
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        assertEquals("only the cloud engine's onOpen is the session's", 1, l.opens.get())

        local.emitError("No speech model installed")
        local.emitDelta("partial")
        assertEquals(0, l.errors.size)
        assertEquals(0, l.deltas.size)

        cloud.emitError("rate limited")
        assertEquals(listOf("rate limited"), l.errors.toList())
    }

    // ---------------------------------------------------------------- session boundaries

    @Test fun a_retry_outstanding_at_close_still_resolves_its_original_seq() {
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        cloud.resolve(0L, SegmentOutcome.Lost("offline"))
        assertEquals("the retry is in flight", 0, l.all.size)

        e.close()
        assertEquals("close must not leave the seq dangling", 1, l.all.size)
        assertEquals(0L to SegmentOutcome.Lost("offline"), l.all.single())

        // The local engine finishing later must not resolve it a second time.
        local.resolve(0L, SegmentOutcome.Text("too late"))
        assertEquals(1, l.all.size)
    }

    @Test fun a_cloud_loss_arriving_during_close_is_forwarded_without_arming_a_retry() {
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()

        e.close()
        cloud.resolve(0L, SegmentOutcome.Lost("cancelled"))
        assertEquals("a torn-down session must not start local work", 0, local.commits.size)
        assertEquals(0L to SegmentOutcome.Lost("cancelled"), l.all.single())
    }

    @Test fun a_retry_outstanding_at_connect_resolves_to_the_session_that_cut_it() {
        // seq restarts at 0 per session, so handing an old seq to the NEW listener would look like
        // the new session's first segment and corrupt its transcript.
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val first = Rec()
        e.connect(null, first)
        e.sendAudio(byteArrayOf(1)); e.commit()
        cloud.resolve(0L, SegmentOutcome.Lost("offline"))
        assertEquals(0, first.all.size)

        val second = Rec()
        e.connect(null, second)
        assertEquals("the old session is owed its resolution", 1, first.all.size)
        assertEquals(0L to SegmentOutcome.Lost("offline"), first.all.single())
        assertEquals("and the new session never sees it", 0, second.all.size)
    }

    @Test fun a_cloud_straggler_after_reconnect_never_reaches_the_new_listener() {
        // Needs the REAL cloud engine: it addresses each seq to the listener that cut it, and the
        // relay has to preserve that. A relay holding one mutable "current downstream" field would
        // deliver the old session's seq 0 to the new listener, where it reads as the new session's
        // first segment and corrupts its transcript.
        val release = CompletableDeferred<Unit>()
        val started = CountDownLatch(1)
        val provider = FakeStt(
            gate = { started.countDown(); release.await() },
            respond = { SttResult.Text("late") },
        )
        val local = FakeEngine()
        val e = engine(CloudTranscriptionEngine(provider, scope()), local)
        val first = Rec()
        e.connect(null, first)
        e.sendAudio(byteArrayOf(1)); assertEquals(0L, e.commit())
        assertTrue(started.await(5_000, TimeUnit.MILLISECONDS))

        val second = Rec()
        e.connect(null, second)
        release.complete(Unit)
        Thread.sleep(200)

        assertEquals("the old session is owed its resolution", 1, first.all.size)
        assertEquals(0L, first.all.single().first)
        assertEquals("the new session must not inherit an old seq", 0, second.all.size)
        assertEquals("and a superseded segment's audio is never retried", 0, local.commits.size)
    }

    // ---------------------------------------------------------------- exactly-once, at volume

    @Test fun every_seq_resolves_exactly_once_across_a_mixed_workload() {
        val cloud = FakeEngine(); val local = FakeEngine(seqBase = 500L)
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        val seqs = (0 until 40).map { i -> e.sendAudio(byteArrayOf(i.toByte())); e.commit() }
        assertEquals((0L until 40L).toList(), seqs)

        var nextLocal = 500L
        val expectRetry = mutableListOf<Long>()
        seqs.forEach { seq ->
            when ((seq % 5).toInt()) {
                0 -> cloud.resolve(seq, SegmentOutcome.Text("t$seq"))
                1 -> cloud.resolve(seq, SegmentOutcome.EmptyExpected)
                2 -> { cloud.resolve(seq, SegmentOutcome.Lost("offline")); expectRetry += nextLocal++ }
                3 -> { cloud.resolve(seq, SegmentOutcome.EmptyUnexpected); expectRetry += nextLocal++ }
                else -> { cloud.resolve(seq, SegmentOutcome.Lost("boom")); expectRetry += nextLocal++ }
            }
        }
        // Residues 2, 3 and 4 arm a retry (24 segments); residues 0 and 1 resolve straight through.
        assertEquals("24 of the 40 armed a retry", 24, local.commits.size)
        assertEquals("and 16 needed no second attempt", 16, l.all.size)

        expectRetry.forEachIndexed { i, localSeq ->
            local.resolve(localSeq, if (i % 2 == 0) SegmentOutcome.Text("r$i") else SegmentOutcome.Lost("gone"))
        }
        val delivered = l.all.map { it.first }
        assertEquals("no seq resolved twice", delivered.size, delivered.toSet().size)
        assertEquals((0L until 40L).toList(), delivered.sorted())
    }

    // ---------------------------------------------------------------- the audio thread

    @Test fun sendAudio_does_not_block_while_every_cloud_slot_is_saturated() {
        // sendAudio runs on the AudioRecord thread every ~32 ms and drives the waveform. The
        // decorator adds a mirror write; it must add no waiting.
        val release = CompletableDeferred<Unit>()
        val inFlight = CountDownLatch(3)
        val provider = FakeStt(gate = { inFlight.countDown(); release.await() })
        val e = engine(CloudTranscriptionEngine(provider, scope()), FakeEngine())
        e.connect(null, Rec())
        repeat(3) { e.sendAudio(ByteArray(1024)); e.commit() }
        assertTrue(inFlight.await(5_000, TimeUnit.MILLISECONDS))

        val chunk = ByteArray(1024)
        val startNs = System.nanoTime()
        repeat(500) { e.sendAudio(chunk) }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        assertTrue("sendAudio blocked for ${elapsedMs}ms with the network saturated", elapsedMs < 500)
        release.complete(Unit)
    }

    // ---------------------------------------------------------------- draining and teardown

    @Test fun awaitIdle_drains_cloud_before_local_so_retries_are_covered() {
        // A retry is submitted to local synchronously inside cloud's own resolution callback, so
        // draining local FIRST would race the work it is meant to wait for.
        val provider = FakeStt(respond = { SttResult.Failed(SttError.Offline) })
        val local = FakeEngine()
        val e = engine(CloudTranscriptionEngine(provider, scope()), local)
        e.connect(null, Rec())
        e.sendAudio(byteArrayOf(1)); e.commit()

        assertTrue(e.awaitIdle(5_000))
        assertEquals("the retry was already queued when local was drained", 1, local.commitsWhenAwaitIdleCalled)
        assertEquals(1, local.awaitIdleCalls.size)
    }

    @Test fun awaitIdle_does_not_wait_on_local_when_no_retry_is_outstanding() {
        // THE FINALIZE HOLD (owner report, spec 2026-08-18 C2): on the happy path every cloud
        // segment resolved Text/EmptyExpected, so local's queue holds NO retry — only the
        // safety-net model load connect() enqueued. Fencing behind that load is waiting on
        // work whose result nothing pending needs.
        val provider = FakeStt(respond = { SttResult.Text("all good") })
        val local = FakeEngine()
        val e = engine(CloudTranscriptionEngine(provider, scope()), local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(2)); e.commit()

        assertTrue(e.awaitIdle(5_000))
        assertEquals(0L to SegmentOutcome.Text("all good"), l.next())
        assertEquals("local owes nothing — the fence must be skipped", 0, local.awaitIdleCalls.size)
    }

    @Test fun a_happy_path_stop_does_not_pay_for_the_safety_nets_model_load() {
        // The convicted hold, reproduced: a REAL LocalWhisperEngine on a REAL single-thread
        // executor whose model load is still running at stop — exactly the first cloud session
        // after service start / a memory trim / a model switch. The load must keep running
        // (the net stays warm) but the stop must not wait for it.
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val releaseLoad = CountDownLatch(1)
        try {
            val loadStarted = CountDownLatch(1)
            val backend = object : WhisperBackend {
                override fun load(modelPath: String): Long {
                    loadStarted.countDown()
                    releaseLoad.await(10, TimeUnit.SECONDS) // the ~7 s Adreno load, held by the test
                    return 42L
                }
                override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean) = ""
                override fun release(ctx: Long) = Unit
            }
            val local = LocalWhisperEngine(
                modelPathProvider = object : ModelPathProvider {
                    override fun installedModelPath() = "/models/tiny.bin"
                },
                retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
                backend = backend,
                executor = executor,
            )
            val provider = FakeStt(respond = { SttResult.Text("all good") })
            val e = engine(CloudTranscriptionEngine(provider, scope()), local)
            val l = Rec()
            e.connect(null, l)
            assertTrue(loadStarted.await(5_000, TimeUnit.MILLISECONDS))
            e.sendAudio(ByteArray(3200) { 1 })
            assertEquals(0L, e.commit())

            val startNs = System.nanoTime()
            assertTrue(e.awaitIdle(20_000))
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            assertEquals(0L to SegmentOutcome.Text("all good"), l.next())
            assertTrue(
                "stop waited ${elapsedMs}ms on a model load no pending segment needs",
                elapsedMs < 2_000,
            )
        } finally {
            releaseLoad.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun the_skip_cannot_fire_while_a_rescues_delivery_is_still_in_flight() {
        // The remove-before-resolve window: LocalRelay removed the ledger entry BEFORE delivering
        // the resolution (resolve runs outside the lock). A stop landing in that window saw an
        // empty ledger, skipped the fence, and the finalize path then flushed the orderer and
        // detached the sink while the rescued text was still mid-delivery — silent segment loss.
        // Held open deterministically here: the listener blocks INSIDE the delivery callback,
        // which runs on local's real executor thread.
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val deliveryEntered = CountDownLatch(1)
        val releaseDelivery = CountDownLatch(1)
        try {
            val backend = object : WhisperBackend {
                override fun load(modelPath: String) = 42L
                override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean) = "rescued locally"
                override fun release(ctx: Long) = Unit
            }
            val local = LocalWhisperEngine(
                modelPathProvider = object : ModelPathProvider {
                    override fun installedModelPath() = "/models/tiny.bin"
                },
                retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
                backend = backend,
                executor = executor,
            )
            // The one segment fails on cloud, so it is rescued locally — the session's only
            // delivery goes through LocalRelay.
            val provider = FakeStt(respond = { SttResult.Failed(SttError.Offline) })
            val e = engine(CloudTranscriptionEngine(provider, scope()), local)
            val delivered = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, SegmentOutcome>>()
            val l = object : TranscriptionEngine.Listener {
                override fun onOpen() = Unit
                override fun onDelta(text: String) = Unit
                override fun onError(message: String) = Unit
                override fun onClosed() = Unit
                override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
                    deliveryEntered.countDown()
                    releaseDelivery.await(10, TimeUnit.SECONDS)
                    delivered.add(seq to outcome)
                }
            }
            e.connect(null, l)
            e.sendAudio(ByteArray(3200) { 1 })
            assertEquals(0L, e.commit())
            // The rescue has entered delivery and is now HELD there, mid-flight.
            assertTrue(deliveryEntered.await(5, TimeUnit.SECONDS))

            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            val drained = java.util.concurrent.atomic.AtomicBoolean(false)
            val waiter = Thread {
                drained.set(e.awaitIdle(20_000))
                done.set(true)
            }
            waiter.start()
            waiter.join(1_500)
            assertTrue(
                "awaitIdle returned while a rescue's delivery was still in flight",
                !done.get(),
            )

            releaseDelivery.countDown()
            waiter.join(20_000)
            assertTrue(done.get())
            assertTrue(drained.get())
            assertEquals(listOf(0L to SegmentOutcome.Text("rescued locally")), delivered.toList())
        } finally {
            releaseDelivery.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun awaitIdle_still_drains_every_outstanding_retry_before_returning() {
        // The C3 skip's boundary: it must NEVER fire while a retry is outstanding. Real
        // background executor on purpose (house rule): the retries resolve on local's executor
        // thread a beat after cloud's callbacks armed them — the interleaving a same-thread
        // executor hides. Even seqs succeed on cloud; odd seqs are lost and must be rescued
        // locally, with every resolution delivered by the time awaitIdle returns true.
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val backend = object : WhisperBackend {
                override fun load(modelPath: String) = 42L
                override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
                    Thread.sleep(100) // a real transcribe takes time; the drain must cover it
                    return "rescued locally"
                }
                override fun release(ctx: Long) = Unit
            }
            val local = LocalWhisperEngine(
                modelPathProvider = object : ModelPathProvider {
                    override fun installedModelPath() = "/models/tiny.bin"
                },
                retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
                backend = backend,
                executor = executor,
            )
            val provider = FakeStt(respond = { pcm ->
                if (pcm[0].toInt() % 2 == 0) SttResult.Text("cloud ${pcm[0]}")
                else SttResult.Failed(SttError.Offline)
            })
            val e = engine(CloudTranscriptionEngine(provider, scope()), local)
            val l = Rec()
            e.connect(null, l)
            val total = 8
            repeat(total) { i ->
                e.sendAudio(ByteArray(3200) { i.toByte() })
                assertEquals(i.toLong(), e.commit())
            }

            assertTrue(e.awaitIdle(20_000))
            // Asserted IMMEDIATELY, no polling: the drain contract is that everything committed
            // has been DELIVERED, not merely scheduled, when awaitIdle returns.
            assertEquals("every seq resolved before awaitIdle returned", total, l.all.size)
            val bySeq = l.all.sortedBy { it.first }
            assertEquals("no seq lost, none duplicated", (0L until total.toLong()).toList(), bySeq.map { it.first })
            bySeq.forEach { (seq, outcome) ->
                if (seq % 2 == 0L) assertEquals(SegmentOutcome.Text("cloud $seq"), outcome)
                else assertEquals("seq $seq must be rescued, not lost", SegmentOutcome.Text("rescued locally"), outcome)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun prewarm_and_releaseContext_and_shutdown_reach_both_engines() {
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        e.connect(null, Rec())
        e.prewarm()
        assertEquals("the safety net must be warm before it is needed", 1, local.prewarms)
        assertEquals(1, cloud.prewarms)
        e.releaseContext()
        assertEquals(1, local.contextReleases)
        assertEquals(1, cloud.contextReleases)
        e.shutdown()
        assertEquals(1, cloud.shutdowns)
        assertEquals(1, local.shutdowns)
    }

    @Test fun a_retry_outstanding_at_shutdown_still_resolves_its_original_seq() {
        val cloud = FakeEngine(); val local = FakeEngine()
        val e = engine(cloud, local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(byteArrayOf(1)); e.commit()
        cloud.resolve(0L, SegmentOutcome.Lost("offline"))
        assertEquals(0, l.all.size)
        e.shutdown()
        assertEquals(0L to SegmentOutcome.Lost("offline"), l.all.single())
    }

    // ---------------------------------------------------------------- end to end

    @Test fun a_real_local_engine_completes_the_fallback_end_to_end() {
        // Real CloudTranscriptionEngine (offline) + real LocalWhisperEngine on a same-thread
        // executor, i.e. the engine resolves re-entrantly inside commit().
        val provider = FakeStt(respond = { SttResult.Failed(SttError.Offline) })
        val backend = object : WhisperBackend {
            override fun load(modelPath: String) = 42L
            override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean) = "rescued locally"
            override fun release(ctx: Long) = Unit
        }
        val local = LocalWhisperEngine(
            modelPathProvider = object : ModelPathProvider {
                override fun installedModelPath() = "/models/tiny.bin"
            },
            retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val e = engine(CloudTranscriptionEngine(provider, scope()), local)
        val l = Rec()
        e.connect(null, l)
        e.sendAudio(ByteArray(3200) { 1 })
        assertEquals(0L, e.commit())
        assertTrue(e.awaitIdle(5_000))

        val (seq, outcome) = l.next()
        assertEquals(0L, seq)
        assertEquals(SegmentOutcome.Text("rescued locally"), outcome)
        assertNull("no session error is shown for a fallback that worked", l.errors.firstOrNull())
    }

    @Test fun a_rescue_longer_than_the_local_buffer_cap_still_reaches_the_user() {
        // THE 2026-07-31 DEVICE BUG (owner-reported on Soniox live, twice on the wire). A provider
        // whose endpoint detection never fires during continuous speech builds one enormous turn —
        // 56 s and 82 s were measured — and when that turn is finally lost, the whole thing goes to
        // the local rescue in a single sendAudio. That trips LocalWhisperEngine's 30 s backstop,
        // which cuts the audio itself, so the commit() immediately after found an empty buffer and
        // returned NO_SEGMENT. localRetry read that as "local refused" and gave up, stamping the
        // cloud's loss over 586 characters whisper went on to transcribe perfectly.
        //
        // A REAL executor is the whole point of this test. On the same-thread executor every other
        // test here uses, the backstop's cut resolves re-entrantly inside sendAudio and `claimed`
        // covers the give-up — the bug is invisible. It only appears when the transcribe lands on
        // another thread a few milliseconds later, i.e. only on a device.
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val provider = FakeStt(respond = { SttResult.Failed(SttError.Offline) })
            val backend = object : WhisperBackend {
                override fun load(modelPath: String) = 42L
                override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean) =
                    "the whole minute of speech"
                override fun release(ctx: Long) = Unit
            }
            val local = LocalWhisperEngine(
                modelPathProvider = object : ModelPathProvider {
                    override fun installedModelPath() = "/models/tiny.bin"
                },
                retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
                backend = backend,
                executor = executor,
            )
            val e = engine(CloudTranscriptionEngine(provider, scope()), local)
            val l = Rec()
            e.connect(null, l)
            // One turn past the 30 s cap, as a single commit — exactly what the fallback hands the
            // local engine when a long cloud turn is lost.
            e.sendAudio(ByteArray(30 * 16000 * 2 + 3200) { 1 })
            assertEquals(0L, e.commit())
            assertTrue(e.awaitIdle(10_000))

            val (seq, outcome) = l.next()
            assertEquals(0L, seq)
            assertEquals(
                "the rescue transcribed this audio; the user must get the words, not a marker",
                SegmentOutcome.Text("the whole minute of speech"),
                outcome,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun segments_committed_after_a_fatal_latch_are_still_rescued_locally() {
        // THE 2026-07-29 DEVICE BUG. Once the cloud engine latches a fatal, every later commit is
        // resolved near-INSTANTLY (no permit, no network) on a dispatcher thread — fast enough to
        // beat commit()'s retained-snapshot insertion. Unsynchronized, the relay's lookup found
        // nothing, skipped the local retry, and the user got a loss marker for every latched
        // segment; on the Fold this lost the race 100% of the time. The fix serializes the lookup
        // against commit() under mirrorLock. This test hammers the window: with the lock the
        // rescue count must be perfect, without it this fails almost every run.
        val provider = FakeStt(respond = {
            SttResult.Failed(SttError.Fatal(FatalKind.INVALID_KEY, "Key rejected"))
        })
        val backend = object : WhisperBackend {
            override fun load(modelPath: String) = 42L
            override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean) = "rescued locally"
            override fun release(ctx: Long) = Unit
        }
        val local = LocalWhisperEngine(
            modelPathProvider = object : ModelPathProvider {
                override fun installedModelPath() = "/models/tiny.bin"
            },
            retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val e = engine(CloudTranscriptionEngine(provider, scope()), local)
        val l = Rec()
        e.connect(null, l)

        // Segment 0 takes the real 401 and latches. Everything after it resolves instantly.
        val total = 21
        repeat(total) {
            e.sendAudio(ByteArray(3200) { 1 })
            e.commit()
            assertTrue("segment $it drained", e.awaitIdle(5_000))
        }

        val outcomes = (0 until total).map { l.next() }.sortedBy { it.first }
        assertEquals("every seq resolves exactly once", total, outcomes.map { it.first }.distinct().size)
        outcomes.forEach { (seq, outcome) ->
            assertEquals(
                "seq $seq must be rescued by the local model, not lost to the race",
                SegmentOutcome.Text("rescued locally"),
                outcome,
            )
        }
        assertEquals("one fatal costs ONE request — the latch held", 1, provider.payloads.size)
    }
}
