package com.whispereverywhere.transcription

import com.whispereverywhere.audio.ActiveSource
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.CloudTranscriptionEngine
import com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngine
import com.whispereverywhere.transcription.cloud.SttProvider
import com.whispereverywhere.transcription.cloud.SttResult
import com.whispereverywhere.util.RetryPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE DEVICE-AUDIO PRIVACY GATE.
 *
 * MediaProjection playback capture records OTHER APPS' audio — the person on the other end of a
 * call, a podcast host, whoever is in the video. None of them consented to anything, and the user
 * granted a screen-capture permission to have that audio transcribed ON THIS DEVICE. Four shipped
 * statements say so verbatim (privacy_policy.html and docs/privacy.html §6-§7,
 * docs/PLAY-DECLARATIONS.md §3 and §5).
 *
 * Before this class the constraint existed ONLY in that prose: FloatingBubbleService picked its
 * engine from (provider, key, network) with `activeSource` not an input at all, and handed every
 * chunk to it — so a user with a cloud provider configured uploaded other people's audio. These
 * tests are the enforcement: the flagship one asserts ZERO SttProvider.transcribe calls for a
 * playback session with a fully configured, valid cloud provider.
 *
 * The rest exist because the swap is the dangerous part: both engines restart seq at 0 on connect
 * and SegmentOrderer drops any seq below its head, so a naive swap deletes a whole era of the
 * user's transcript, and any seq that never resolves stalls the orderer head forever.
 */
class SourceRoutedTranscriptionEngineTest {

    private val scopes = Collections.synchronizedList(mutableListOf<CoroutineScope>())

    @After fun cancelScopes() {
        scopes.forEach { it.cancel() }
    }

    private fun scope(): CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob()).also { scopes += it }

    // ---------------------------------------------------------------- fakes

    /**
     * A cloud provider that is FULLY CONFIGURED AND WORKING — a valid (obviously fake) key, online,
     * answering with real text. That is the whole point: the gate must hold when nothing is wrong
     * with the cloud path, not only when it happens to fail.
     */
    private class CountingStt(
        private val gate: (suspend () -> Unit)? = null,
        private val text: String = "cloud text",
    ) : SttProvider {
        override val id = ProviderId.OPENAI
        override val maxRequestBytes = 25L * 1024 * 1024
        val calls = AtomicInteger(0)
        val payloads = Collections.synchronizedList(mutableListOf<ByteArray>())

        override suspend fun transcribe(pcm: ByteArray, language: String?): SttResult {
            calls.incrementAndGet()
            payloads += pcm
            gate?.invoke()
            return SttResult.Text(text)
        }
    }

    /** Records every byte the on-device backend was asked to transcribe. */
    private class RecordingBackend(private val text: String = "on-device text") : WhisperBackend {
        val transcribes = AtomicInteger(0)
        override fun load(modelPath: String) = 7L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String {
            transcribes.incrementAndGet()
            return text
        }
        override fun release(ctx: Long) = Unit
    }

    private fun localEngine(backend: WhisperBackend) = LocalWhisperEngine(
        modelPathProvider = object : ModelPathProvider {
            override fun installedModelPath() = "/models/tiny.bin"
        },
        retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
        backend = backend,
        executor = SameThreadExecutorService(),
    )

    private class Rec : TranscriptionEngine.Listener {
        val opens = AtomicInteger(0)
        val closes = AtomicInteger(0)
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val all = Collections.synchronizedList(mutableListOf<Pair<Long, SegmentOutcome>>())

        override fun onOpen() { opens.incrementAndGet() }
        override fun onDelta(text: String) = Unit
        override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) { all += seq to outcome }
        override fun onError(message: String) { errors += message }
        override fun onClosed() { closes.incrementAndGet() }

        fun outcomeOf(seq: Long): SegmentOutcome? = all.firstOrNull { it.first == seq }?.second
        fun texts(): List<String> = all.mapNotNull { (it.second as? SegmentOutcome.Text)?.text }
    }

    /** A minimal scriptable engine, for the structural tests that need no real transcription. */
    private class FakeEngine : TranscriptionEngine {
        @Volatile var attached: TranscriptionEngine.Listener? = null
        private val buffer = ByteArrayOutputStream()
        private var nextSeq = 0L
        val commits = Collections.synchronizedList(mutableListOf<ByteArray>())
        var connects = 0; var closes = 0; var shutdowns = 0; var prewarms = 0

        override fun connect(language: String?, listener: TranscriptionEngine.Listener) {
            connects++
            attached = listener
            synchronized(buffer) { buffer.reset() }
            nextSeq = 0L
            listener.onOpen()
        }
        override fun sendAudio(pcm: ByteArray) { synchronized(buffer) { buffer.write(pcm) } }
        override fun commit(): Long {
            attached ?: return -1L
            val pcm = synchronized(buffer) {
                val snapshot = buffer.toByteArray()
                if (snapshot.isEmpty()) return -1L
                buffer.reset()
                snapshot
            }
            commits += pcm
            return nextSeq++
        }
        fun resolve(seq: Long, outcome: SegmentOutcome) { attached?.onSegmentResolved(seq, outcome) }
        override fun close() { closes++; val l = attached; attached = null; l?.onClosed() }
        override fun shutdown() { shutdowns++ }
        override fun prewarm() { prewarms++ }
    }

    /** The production shape: cloud wrapped in the local fallback for the mic, local for the device. */
    private class Rig(provider: CountingStt, backend: RecordingBackend, scope: CoroutineScope) {
        val local = LocalWhisperEngine(
            modelPathProvider = object : ModelPathProvider {
                override fun installedModelPath() = "/models/tiny.bin"
            },
            retry = RetryPolicy(maxAttempts = 1, baseDelayMs = 0, maxDelayMs = 0, rng = { 0.0 }),
            backend = backend,
            executor = SameThreadExecutorService(),
        )
        val cloud = CloudTranscriptionEngine(provider, scope)
        val engine = SourceRoutedTranscriptionEngine(
            micEngine = FallbackTranscriptionEngine(cloud, local, scope),
            deviceEngine = local,
        )
    }

    private fun rig(provider: CountingStt, backend: RecordingBackend = RecordingBackend()) =
        Rig(provider, backend, scope())

    /** One segment's worth of audio: enough bytes that no engine treats the buffer as empty. */
    private fun audio(fill: Byte) = ByteArray(3200) { fill }

    // ---------------------------------------------------------------- the pure rule

    @Test fun device_audio_routes_to_the_on_device_engine_and_mic_audio_to_the_users_choice() {
        val mic = FakeEngine(); val device = FakeEngine()
        assertSame(device, engineForSource(ActiveSource.PLAYBACK, mic, device))
        assertSame(mic, engineForSource(ActiveSource.MIC, mic, device))
    }

    // ---------------------------------------------------------------- the flagship

    @Test fun a_playback_session_makes_zero_transcribe_calls_with_a_valid_cloud_provider() {
        // Fully configured cloud: valid key, online, provider answering with real text. The route
        // is decided by the SOURCE alone, so none of that can put device audio on the wire.
        val provider = CountingStt()
        val backend = RecordingBackend()
        val r = rig(provider, backend)
        val l = Rec()
        r.engine.connect("en", l)

        r.engine.onSourceChanged(ActiveSource.PLAYBACK)
        repeat(4) { i ->
            r.engine.sendAudio(audio(i.toByte()))
            r.engine.commit()
        }
        assertTrue(r.engine.awaitIdle(5_000))

        assertEquals("device audio must never reach the cloud provider", 0, provider.calls.get())
        assertEquals("no request payload may exist at all", 0, provider.payloads.size)
        assertEquals("and it was transcribed on-device instead", 4, backend.transcribes.get())
        assertEquals(4, l.all.size)
        assertEquals(listOf(0L, 1L, 2L, 3L), l.all.map { it.first })
        assertTrue(l.texts().all { it == "on-device text" })
    }

    @Test fun microphone_audio_still_goes_to_the_users_chosen_provider() {
        // The other half of the rule: this is the user's own voice and the user's own decision.
        val provider = CountingStt()
        val r = rig(provider)
        val l = Rec()
        r.engine.connect("en", l)

        r.engine.sendAudio(audio(1))
        assertEquals(0L, r.engine.commit())
        assertTrue(r.engine.awaitIdle(5_000))

        assertEquals(1, provider.calls.get())
        assertEquals(SegmentOutcome.Text("cloud text"), l.outcomeOf(0L))
    }

    // ---------------------------------------------------------------- both directions

    @Test fun switching_to_playback_mid_session_stops_the_cloud_immediately() {
        val provider = CountingStt()
        val backend = RecordingBackend()
        val r = rig(provider, backend)
        val l = Rec()
        r.engine.connect("en", l)

        r.engine.sendAudio(audio(1)); r.engine.commit()
        assertTrue(r.engine.awaitIdle(5_000))
        assertEquals("the mic segment is the user's own choice", 1, provider.calls.get())

        r.engine.onSourceChanged(ActiveSource.PLAYBACK)
        repeat(3) { r.engine.sendAudio(audio(9)); r.engine.commit() }
        assertTrue(r.engine.awaitIdle(5_000))

        assertEquals("not one more upload after the source changed", 1, provider.calls.get())
        assertEquals(3, backend.transcribes.get())
    }

    @Test fun switching_back_to_the_microphone_restores_the_users_actual_choice() {
        val provider = CountingStt()
        val r = rig(provider)
        val l = Rec()
        r.engine.connect("en", l)

        r.engine.onSourceChanged(ActiveSource.PLAYBACK)
        r.engine.sendAudio(audio(9)); r.engine.commit()
        assertTrue(r.engine.awaitIdle(5_000))
        assertEquals(0, provider.calls.get())

        r.engine.onSourceChanged(ActiveSource.MIC)
        r.engine.sendAudio(audio(1)); r.engine.commit()
        assertTrue(r.engine.awaitIdle(5_000))

        assertEquals("the cloud engine is the user's choice again", 1, provider.calls.get())
        assertTrue("and only mic audio ever reached it", l.texts().contains("cloud text"))
    }

    @Test fun an_uncommitted_device_audio_tail_is_never_carried_into_the_cloud_engine() {
        // The tail at a switch is the subtle leak: audio captured under PLAYBACK that has not been
        // cut yet must not become the head of the first microphone segment.
        val provider = CountingStt()
        val r = rig(provider)
        val l = Rec()
        r.engine.connect("en", l)

        r.engine.onSourceChanged(ActiveSource.PLAYBACK)
        r.engine.sendAudio(ByteArray(3200) { 0x7F })      // never committed
        r.engine.onSourceChanged(ActiveSource.MIC)
        r.engine.sendAudio(ByteArray(3200) { 0x01 })
        r.engine.commit()
        assertTrue(r.engine.awaitIdle(5_000))

        assertEquals(1, provider.payloads.size)
        val uploaded = provider.payloads.single()
        assertFalse("a device-audio byte reached the wire", uploaded.any { it == 0x7F.toByte() })
        assertTrue(uploaded.all { it == 0x01.toByte() })
    }

    @Test fun an_uncommitted_microphone_tail_follows_the_user_into_the_on_device_engine() {
        // The legal direction: mic audio may go anywhere, so the tail is carried rather than lost.
        val provider = CountingStt()
        val backend = RecordingBackend()
        val r = rig(provider, backend)
        val l = Rec()
        r.engine.connect("en", l)

        r.engine.sendAudio(audio(1))                      // never committed on the mic engine
        r.engine.onSourceChanged(ActiveSource.PLAYBACK)
        r.engine.sendAudio(audio(9))
        r.engine.commit()
        assertTrue(r.engine.awaitIdle(5_000))

        assertEquals("the tail was not uploaded on the way out", 0, provider.calls.get())
        assertEquals("and it was not dropped either", 1, backend.transcribes.get())
        assertEquals(SegmentOutcome.Text("on-device text"), l.outcomeOf(0L))
    }

    // ---------------------------------------------------------------- the seq space

    @Test fun seq_keeps_climbing_across_a_switch_so_the_orderer_cannot_drop_an_era() {
        // Both children restart at 0 on connect and SegmentOrderer drops any seq below its head:
        // forwarding a child's seq after a swap would silently delete the whole second era.
        val provider = CountingStt()
        val r = rig(provider)
        val l = Rec()
        r.engine.connect("en", l)

        r.engine.sendAudio(audio(1)); assertEquals(0L, r.engine.commit())
        assertTrue(r.engine.awaitIdle(5_000))
        r.engine.onSourceChanged(ActiveSource.PLAYBACK)
        r.engine.sendAudio(audio(9)); assertEquals(1L, r.engine.commit())
        r.engine.sendAudio(audio(9)); assertEquals(2L, r.engine.commit())
        r.engine.onSourceChanged(ActiveSource.MIC)
        r.engine.sendAudio(audio(1)); assertEquals(3L, r.engine.commit())
        assertTrue(r.engine.awaitIdle(5_000))

        assertEquals(listOf(0L, 1L, 2L, 3L), l.all.map { it.first }.sorted())
    }

    @Test fun every_seq_resolves_exactly_once_across_a_workload_with_switches() {
        val provider = CountingStt()
        val r = rig(provider)
        val l = Rec()
        r.engine.connect("en", l)

        val seqs = mutableListOf<Long>()
        repeat(12) { i ->
            if (i == 3) r.engine.onSourceChanged(ActiveSource.PLAYBACK)
            if (i == 7) r.engine.onSourceChanged(ActiveSource.MIC)
            r.engine.sendAudio(audio(i.toByte()))
            seqs += r.engine.commit()
        }
        assertTrue(r.engine.awaitIdle(5_000))
        r.engine.close()

        val delivered = l.all.map { it.first }
        assertEquals("no seq resolved twice", delivered.size, delivered.toSet().size)
        assertEquals("and none went missing", seqs.sorted(), delivered.sorted())
        assertEquals((0L until 12L).toList(), seqs)
    }

    @Test fun a_segment_still_in_flight_when_the_source_changes_is_never_left_unresolved() {
        // An unresolved seq stalls the SegmentOrderer head forever and silently halts all further
        // dictation, so the cutover has to sweep whatever the outgoing engine could not finish.
        val release = CompletableDeferred<Unit>()
        val started = CountDownLatch(1)
        val provider = CountingStt(gate = { started.countDown(); release.await() })
        val r = rig(provider)
        val l = Rec()
        r.engine.connect("en", l)

        r.engine.sendAudio(audio(1))
        assertEquals(0L, r.engine.commit())
        assertTrue(started.await(5_000, TimeUnit.MILLISECONDS))

        r.engine.onSourceChanged(ActiveSource.PLAYBACK)
        assertEquals("the stranded seq is owed a resolution", 1, l.all.size)
        assertEquals(0L, l.all.single().first)

        // The upload finishing afterwards must not resolve it a second time.
        release.complete(Unit)
        Thread.sleep(200)
        assertEquals(1, l.all.size)
    }

    @Test fun a_cut_the_on_device_engine_abandons_at_a_switch_still_resolves() {
        // LocalWhisperEngine.close() only detaches its listener — queued transcribes become no-ops —
        // so the sweep is the only thing standing between a switch and a stalled orderer head.
        val mic = FakeEngine(); val device = FakeEngine()
        val e = SourceRoutedTranscriptionEngine(mic, device)
        val l = Rec()
        e.connect("en", l)
        e.onSourceChanged(ActiveSource.PLAYBACK)
        e.sendAudio(byteArrayOf(1, 2)); assertEquals(0L, e.commit())
        assertEquals("nothing resolved yet", 0, l.all.size)

        e.onSourceChanged(ActiveSource.MIC)
        assertEquals(1, l.all.size)
        assertEquals(0L, l.all.single().first)
        assertTrue(l.all.single().second is SegmentOutcome.Lost)

        // A late answer from the abandoned engine cannot double-resolve it.
        device.resolve(0L, SegmentOutcome.Text("too late"))
        assertEquals(1, l.all.size)
    }

    // ---------------------------------------------------------------- session lifecycle

    @Test fun a_cutover_is_invisible_upstream_no_second_open_and_no_stray_close() {
        // A second onOpen would read as a second session in the service (it starts the recorder in
        // it); a stray onClosed would tear the live session down.
        val mic = FakeEngine(); val device = FakeEngine()
        val e = SourceRoutedTranscriptionEngine(mic, device)
        val l = Rec()
        e.connect("en", l)
        assertEquals(1, l.opens.get())

        e.onSourceChanged(ActiveSource.PLAYBACK)
        e.onSourceChanged(ActiveSource.MIC)
        assertEquals("one open per session", 1, l.opens.get())
        assertEquals("and no close until the session ends", 0, l.closes.get())

        e.close()
        assertEquals(1, l.closes.get())
    }

    @Test fun a_new_session_starts_on_the_microphone_and_renumbers_from_zero() {
        val mic = FakeEngine(); val device = FakeEngine()
        val e = SourceRoutedTranscriptionEngine(mic, device)
        val first = Rec()
        e.connect("en", first)
        e.onSourceChanged(ActiveSource.PLAYBACK)
        e.sendAudio(byteArrayOf(1)); e.commit()
        e.close()

        val second = Rec()
        e.connect("en", second)
        e.sendAudio(byteArrayOf(2))
        assertEquals("seq restarts with the fresh SegmentOrderer", 0L, e.commit())
        assertEquals("and the mic engine serves it", 1, mic.commits.size)
        assertEquals("the previous session's seq was never handed to the new listener", 0, second.all.size)
    }

    @Test fun an_outstanding_cut_at_close_is_resolved_to_the_session_that_cut_it() {
        val mic = FakeEngine(); val device = FakeEngine()
        val e = SourceRoutedTranscriptionEngine(mic, device)
        val l = Rec()
        e.connect("en", l)
        e.sendAudio(byteArrayOf(1)); assertEquals(0L, e.commit())

        e.close()
        assertEquals("close must not leave the seq dangling", 1, l.all.size)
        assertTrue(l.all.single().second is SegmentOutcome.Lost)
    }

    @Test fun commit_with_nothing_buffered_allocates_no_seq() {
        // A seq handed out for a segment that does not exist can never resolve.
        val mic = FakeEngine(); val device = FakeEngine()
        val e = SourceRoutedTranscriptionEngine(mic, device)
        val l = Rec()
        e.connect("en", l)
        assertEquals(-1L, e.commit())
        e.sendAudio(byteArrayOf(1))
        assertEquals("the next real cut still starts at 0", 0L, e.commit())
        assertEquals(0, l.all.size)
    }

    @Test fun lifecycle_calls_reach_the_engine_tree_exactly_once() {
        // deviceEngine is reachable THROUGH micEngine in production (the fallback owns it), so
        // forwarding to both would shut the on-device executor down twice.
        val mic = FakeEngine(); val device = FakeEngine()
        val e = SourceRoutedTranscriptionEngine(mic, device)
        e.connect("en", Rec())
        e.prewarm()
        e.shutdown()
        assertEquals(1, mic.prewarms)
        assertEquals(1, mic.shutdowns)
        assertEquals(0, device.prewarms)
        assertEquals(0, device.shutdowns)
    }

    // ---------------------------------------------------------------- the on-device-only user

    @Test fun one_engine_serving_both_sources_is_never_cut_over() {
        // The pre-cloud path must be untouched: no reconnect, no close, no re-numbering, and the
        // service's own commit-at-switch keeps the two sources in separate segments.
        val only = FakeEngine()
        val e = SourceRoutedTranscriptionEngine(micEngine = only, deviceEngine = only)
        val l = Rec()
        e.connect("en", l)
        e.sendAudio(byteArrayOf(1)); assertEquals(0L, e.commit())
        e.onSourceChanged(ActiveSource.PLAYBACK)
        e.sendAudio(byteArrayOf(2)); assertEquals(1L, e.commit())

        assertEquals("one connect for the session", 1, only.connects)
        assertEquals("and nothing was closed mid-session", 0, only.closes)
        only.resolve(0L, SegmentOutcome.Text("a"))
        only.resolve(1L, SegmentOutcome.Text("b"))
        assertEquals(listOf("a", "b"), l.texts())
    }

    // ---------------------------------------------------------------- the audio thread

    @Test fun sendAudio_does_not_block_while_the_cloud_is_saturated() {
        // sendAudio runs on the capture thread every ~32 ms and also drives the waveform.
        val release = CompletableDeferred<Unit>()
        val inFlight = CountDownLatch(3)
        val provider = CountingStt(gate = { inFlight.countDown(); release.await() })
        val r = rig(provider)
        r.engine.connect("en", Rec())
        repeat(3) { r.engine.sendAudio(audio(1)); r.engine.commit() }
        assertTrue(inFlight.await(5_000, TimeUnit.MILLISECONDS))

        val chunk = ByteArray(1024)
        val startNs = System.nanoTime()
        repeat(500) { r.engine.sendAudio(chunk) }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        assertTrue("sendAudio blocked for ${elapsedMs}ms", elapsedMs < 500)
        release.complete(Unit)
    }
}
