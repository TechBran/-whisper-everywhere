package com.whispereverywhere.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The path fixture: any non-null String selects the Silero branch — the factory reads nothing else. */
private const val MODEL = "/data/vad/model.bin"

/** Every wait below is BOUNDED by this (C9): no test may hang, and none may pass on scheduling luck. */
private const val JOIN_MS = 5_000L

/**
 * Fallback tier 1 (3.7, Task D8), pinned: model missing -> AmplitudeEndpointer -> a full
 * session byte-identical to 3.6.0. This is not a new failure mode — `VadModel.path()` already
 * returns null and already logs "running without VAD".
 *
 * The SIX tests after the brief's five pin the OTHER half of this task, which no selection test can
 * see: the SESSION EPOCH binding (Task D5). D5's gates live inside `VadProbeLifecycle`, but the
 * token that drives them is snapshotted HERE, once per capture thread, and a factory that ignored
 * the token would pass all five of the tests above while leaving the gate doing nothing at all.
 * So FOUR OF THE SIX drive REAL THREADS — one per session, which is what
 * `StreamingAudioRecorder` (`:70`, `:101`, `:148`) and `PlaybackAudioCapturer` (`:64`, `:88`,
 * `:100`) both do — one pins the ONE reused direct buffer by identity (teardown-bill T7/T8), and
 * the last reads the factory's source for the one constant no behavioural test can reach.
 *
 * THREADING STANDARD (C9), stated because four tests below start threads. Every wait is bounded by
 * [JOIN_MS] and every ordering is established by a `CountDownLatch` or a `join`, never by a sleep —
 * so no assertion here can pass or fail on scheduling luck. Those same latch and join edges are what
 * publish [FakeVadProbe]'s plain `Int` counters to the asserting thread: the stale thread's
 * `countDown()` happens-before the main thread's `await()` returns, and a `join()` happens-before
 * everything the joined thread did.
 *
 * The two-token discipline D4's report recommends and D5 followed is used throughout: every epoch
 * test asserts BOTH that the stale token is refused AND that the live one still works, in the same
 * test, so a mutant that disabled the gate and a mutant that refused everything are both caught.
 */
class EndpointerFactoryTest {

    @Test
    fun aNullModelPathSelectsTheAmplitudeFallback() {
        val probe = FakeVadProbe()
        val endpointer = EndpointerFactory.create(vadModelPath = null, probe = probe)
        assertTrue(
            "a missing VAD model must yield the 3.6.0 path, not a degraded Silero one",
            endpointer is AmplitudeEndpointer,
        )
        assertEquals("the probe must not be touched at all", 0, probe.initCalls)
        assertEquals(0, probe.freeCalls)
        // ADDED beyond the brief (review m7), never in place of it: the two lines above pin two of
        // the four counters, and "untouched" deserves to be pinned literally. `calls` logs
        // init/reset/free — Task D8 deliberately left `frame` out of it so D4's call-order alphabet
        // stays exactly as `freeOnlyEverFollowsASuccessfulInit` asserts it — which is why frameCalls
        // is asserted beside it rather than folded into it.
        assertTrue(probe.calls.isEmpty())
        assertEquals(0, probe.frameCalls)
    }

    @Test
    fun theAmplitudeFallbackOffersNoCutPointSoTheCapStaysByteIdentical() {
        val endpointer = EndpointerFactory.create(vadModelPath = null, probe = FakeVadProbe())
        endpointer.onSessionStart(nowMs = 0L, minCommitIntervalMs = 6_000L)
        val chunk = ByteArray(1024)
        repeat(600) { i -> endpointer.onFrame(chunk, 5_000, i * 32L) }   // 19.2 s of loud audio
        assertEquals(Endpointer.NO_CUT_POINT, endpointer.pendingCutPointMs())
        endpointer.onSessionEnd()
    }

    @Test
    fun aResolvedModelPathSelectsTheSileroEndpointer() {
        val probe = FakeVadProbe()
        val endpointer = EndpointerFactory.create(vadModelPath = "/data/vad/model.bin", probe = probe)
        assertTrue(endpointer is SileroEndpointer)
    }

    @Test
    fun constructionNeverInitialisesTheProbe() {
        // Construction runs on MAIN at service construction; init belongs on the capture thread,
        // at the first frame (VadProbeLifecycle's contract).
        val probe = FakeVadProbe()
        EndpointerFactory.create(vadModelPath = "/data/vad/model.bin", probe = probe)
        assertEquals(0, probe.initCalls)
    }

    @Test
    fun theSileroPathArmsAtSessionStartAndFreesAtSessionEnd() {
        // The binding this factory exists for: C's endpointer knows nothing about JNI, so the
        // native context's arm/init/free lifecycle is wired here, through lambdas. arm() only
        // records the path — the one-time init still happens on the CAPTURE thread at the first
        // frame — and free() must follow a session, not a construction.
        val probe = FakeVadProbe()
        val endpointer = EndpointerFactory.create(vadModelPath = "/data/vad/model.bin", probe = probe)
        endpointer.onSessionStart(nowMs = 1_000L, minCommitIntervalMs = 1_200L)
        assertEquals("arming must not load the model", 0, probe.initCalls)

        // One whole frame on the capture path: NOW the context is created, exactly once.
        endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 1_032L)
        assertEquals(1, probe.initCalls)
        assertEquals("the probe is fed the reused DIRECT buffer, one frame at a time", 0, probe.freeCalls)

        endpointer.onSessionEnd()
        assertEquals(1, probe.freeCalls)

        // A second session re-arms and re-initialises rather than staying dead.
        endpointer.onSessionStart(nowMs = 5_000L, minCommitIntervalMs = 1_200L)
        endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 5_032L)
        assertEquals(2, probe.initCalls)
    }

    // ---------------------------------------------------------------------------------------
    // The epoch binding (Task D5). Everything below drives the wiring the five tests above
    // cannot distinguish from a factory that never took a token at all.
    // ---------------------------------------------------------------------------------------

    /**
     * The production shape of the test above: ONE FRESH THREAD PER SESSION.
     *
     * The token is snapshotted once per capture thread, at that thread's first probe call, and
     * never re-read per frame — so "does session 2 still work" is a question about a thread that
     * has never seen an epoch before, and the single-threaded version above cannot ask it. A wiring
     * that snapshotted the token per ENDPOINTER instead of per thread leaves session 2's capture
     * thread holding session 1's token and refused for its whole life: VAD silently off, with no
     * exception, no sentinel and no log.
     */
    @Test
    fun everySessionsCaptureThreadTakesItsOwnEpochAndReInitialisesTheProbe() {
        val probe = FakeVadProbe()
        val endpointer = EndpointerFactory.create(vadModelPath = MODEL, probe = probe)

        endpointer.onSessionStart(nowMs = 1_000L, minCommitIntervalMs = 1_200L)
        captureThread("capture-1") { endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 1_032L) }
        assertEquals(1, probe.initCalls)
        assertEquals(1, probe.frameCalls)
        endpointer.onSessionEnd()
        assertEquals(1, probe.freeCalls)

        endpointer.onSessionStart(nowMs = 5_000L, minCommitIntervalMs = 1_200L)
        captureThread("capture-2") { endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 5_032L) }
        assertEquals(
            "session 2's own capture thread must take the NEW epoch, not inherit a stale one",
            2,
            probe.initCalls,
        )
        assertEquals("and its frames must reach the probe", 2, probe.frameCalls)
        endpointer.onSessionEnd()
        assertEquals(2, probe.freeCalls)
    }

    /**
     * THE HAZARD THE EPOCH EXISTS FOR (teardown-bill T2-SHARPENED, T8, T9). A session-N capture
     * thread outlives `CaptureThreadPolicy.stopThenJoin`'s BEST-EFFORT join — unobservable by
     * construction, since `Thread.join(ms)` returns identically on termination and on timeout —
     * and calls back in after session N+1 has armed. It sees a legitimately `ARMED` lifecycle, so
     * nothing in the state machine can refuse it; only its stale token can.
     *
     * Without the gate it would build N+1's context and then write N's audio into the ONE shared
     * direct buffer alongside N+1's real capture thread: torn frames and cross-session LSTM
     * contamination, with no exception and no log. The assertion is that the stale arrival is
     * OBSERVABLY INERT — zero further `init`, zero further `frame` — while the live session, which
     * initialised between the two halves of the stale thread's life, is untouched.
     */
    @Test
    fun aStaleCaptureThreadsFrameGoesInertOnceTheNextSessionHasArmed() {
        val probe = FakeVadProbe()
        val endpointer = EndpointerFactory.create(vadModelPath = MODEL, probe = probe)

        val tookItsEpoch = CountDownLatch(1)
        val nextSessionLive = CountDownLatch(1)
        val staleDone = CountDownLatch(1)

        endpointer.onSessionStart(nowMs = 1_000L, minCommitIntervalMs = 1_200L)
        val stale = Thread({
            endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 1_032L)
            tookItsEpoch.countDown()
            nextSessionLive.await(JOIN_MS, TimeUnit.MILLISECONDS)
            // The thread that outlived its join, arriving inside somebody else's session.
            endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 9_000L)
            staleDone.countDown()
        }, "capture-stale")
        stale.start()
        assertTrue("the stale thread never took its epoch", tookItsEpoch.await(JOIN_MS, TimeUnit.MILLISECONDS))
        assertEquals(1, probe.initCalls)
        assertEquals(1, probe.frameCalls)

        // Session 1 ends; session 2 opens and initialises on ITS OWN capture thread.
        endpointer.onSessionEnd()
        assertEquals(1, probe.freeCalls)
        endpointer.onSessionStart(nowMs = 5_000L, minCommitIntervalMs = 1_200L)
        captureThread("capture-2") { endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 5_032L) }
        assertEquals(2, probe.initCalls)
        assertEquals(2, probe.frameCalls)

        nextSessionLive.countDown()
        assertTrue("the stale thread never finished", staleDone.await(JOIN_MS, TimeUnit.MILLISECONDS))
        stale.join(JOIN_MS)
        assertFalse(stale.isAlive)

        assertEquals(
            "a stale capture thread must not build the LIVE session's native context",
            2,
            probe.initCalls,
        )
        assertEquals(
            "and must not feed it either: a stale frame is NO_VERDICT with the probe untouched",
            2,
            probe.frameCalls,
        )
        assertEquals("and it must not have freed anything", 1, probe.freeCalls)
    }

    /**
     * THE THIRD CAPTURE-PATH ROUTE (D5 review I2). `probeReset` is one lambda fired from BOTH Main
     * (`onSessionStart`, `switchSource`, `stopRecording`) and the CAPTURE thread (the wall-cap cut
     * at `FloatingBubbleService.kt:1754`, POST-D7 numbering, and every commit through
     * `clearForNextSegment`), and
     * `VadProbeLifecycle.reset()` is deliberately ungated — the lifecycle cannot bind one token to
     * two callers. So the routing decision is D8's, and this is the pin on it: a session-N thread's
     * reset must not clear session N+1's LIVE LSTM recurrence.
     *
     * Milder than the frame hazard — a cleared recurrence costs a few frames of re-warm where a FED
     * one poisons every frame after it (T9) — and gated here rather than argued away, because the
     * unreachability argument runs out at exactly one site: a stale thread cannot COMMIT (its
     * verdicts are NO_VERDICT, which can neither open nor close the Schmitt gate), but it CAN reach
     * the cap branch's `endpointer.reset()`, which is a real call site D9 is about to wire.
     */
    @Test
    fun aStaleCaptureThreadsResetCannotClearTheLiveSessionsRecurrence() {
        val probe = FakeVadProbe()
        val endpointer = EndpointerFactory.create(vadModelPath = MODEL, probe = probe)

        val tookItsEpoch = CountDownLatch(1)
        val nextSessionLive = CountDownLatch(1)
        val staleDone = CountDownLatch(1)

        endpointer.onSessionStart(nowMs = 1_000L, minCommitIntervalMs = 1_200L)
        val stale = Thread({
            endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 1_032L)
            tookItsEpoch.countDown()
            nextSessionLive.await(JOIN_MS, TimeUnit.MILLISECONDS)
            // D9's cap-cut branch, on the capture thread: the ONE reset route a stale thread reaches.
            endpointer.reset()
            staleDone.countDown()
        }, "capture-stale")
        stale.start()
        assertTrue(tookItsEpoch.await(JOIN_MS, TimeUnit.MILLISECONDS))

        endpointer.onSessionEnd()
        endpointer.onSessionStart(nowMs = 5_000L, minCommitIntervalMs = 1_200L)
        assertEquals("no reset has reached a READY probe yet", 0, probe.resetCalls)

        // The LIVE token still works — the other half of the two-token discipline.
        captureThread("capture-2") {
            endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 5_032L)
            endpointer.reset()
        }
        assertEquals(2, probe.initCalls)
        assertEquals("the LIVE capture thread's reset must still reach the probe", 1, probe.resetCalls)

        nextSessionLive.countDown()
        assertTrue(staleDone.await(JOIN_MS, TimeUnit.MILLISECONDS))
        stale.join(JOIN_MS)
        assertFalse(stale.isAlive)

        assertEquals(
            "a session-N thread's reset cleared session N+1's live LSTM recurrence (D5 review I2)",
            1,
            probe.resetCalls,
        )
    }

    /**
     * THE OTHER HALF OF THE RESET GATE — the failure a gate must never cause.
     *
     * Three of the four service reset sites are MAIN's (`onSessionStart`, `switchSource`
     * `FloatingBubbleService.kt:1851`, `stopRecording` `:2425`), and `switchSource`'s is the
     * correctness-critical one: carrying LSTM recurrence across a mic <-> device-audio swap is a
     * bug, not merely suboptimal (teardown-bill T9). `probeArm` refreshes MAIN's snapshot at every
     * session open precisely so the gate can never refuse them. A gate bound to a snapshot Main
     * took ONCE would cache session 1's token forever and drop every Main reset from session 2
     * onward — silently, with no exception and no log, across the exact boundary T9 names.
     */
    @Test
    fun aMainThreadResetIsNeverRefusedByTheEpochGate() {
        val probe = FakeVadProbe()
        val endpointer = EndpointerFactory.create(vadModelPath = MODEL, probe = probe)

        endpointer.onSessionStart(nowMs = 1_000L, minCommitIntervalMs = 1_200L)
        captureThread("capture-1") { endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 1_032L) }
        endpointer.reset()                      // switchSource / stopRecording, on MAIN
        assertEquals(1, probe.resetCalls)

        endpointer.onSessionEnd()
        endpointer.onSessionStart(nowMs = 5_000L, minCommitIntervalMs = 1_200L)
        captureThread("capture-2") { endpointer.onFrame(ByteArray(VadProbe.FRAME_BYTES), 0, 5_032L) }
        endpointer.reset()                      // MAIN again, one whole session later
        assertEquals(
            "the epoch gate refused a MAIN-thread reset: switchSource would then carry the LSTM " +
                "recurrence across an acoustic-source change (T9)",
            2,
            probe.resetCalls,
        )
    }

    /**
     * TEARDOWN-BILL T7 AND T8, which are D8's ALONE, pinned at the only place the JVM can see them.
     *
     * T7 — "allocateDirect returns BIG_ENDIAN on every platform": what actually discharges it is
     * that the fill is `put(ByteArray)`, which copies bytes and is byte-order-INSENSITIVE. The
     * `order(nativeOrder())` beside it is belt and braces for a future `putShort`/`asShortBuffer`
     * edit. This test cannot see the `order` call — it can see the CONSEQUENCE, which is the one
     * that matters: the bytes the native side would read at the base address are the accumulator's
     * bytes, in the accumulator's order, and a byte-order-sensitive fill would swap every pair.
     *
     * T8 — "no concurrent refill; fill, then call, same thread": the fill and the call are two
     * adjacent statements inside one lambda, so the only way to interleave them is two threads in
     * that lambda at once. The two stale-thread tests above are the CROSS-session half of that (a
     * refused thread never reaches the buffer at all). The INTRA-session half — `switchSource`
     * running two capturers inside one epoch — is NOT closed here and is D9/D10's, per the class
     * KDoc and D5's review finding I3.
     *
     * And ONE buffer for the endpointer's life, asserted by IDENTITY rather than by content: a
     * per-frame `allocateDirect` would be 32 KB/s of garbage on the audio thread and would still
     * pass every content assertion in this test.
     */
    @Test
    fun theOneDirectBufferCarriesTheFrameVerbatimAndIsReusedForever() {
        val probe = FakeVadProbe()
        val endpointer = EndpointerFactory.create(vadModelPath = MODEL, probe = probe)
        endpointer.onSessionStart(nowMs = 1_000L, minCommitIntervalMs = 1_200L)

        // A distinctive pattern: neither an unfilled buffer nor a byte-swapped one reproduces it.
        val first = ByteArray(VadProbe.FRAME_BYTES) { ((it * 7) and 0xFF).toByte() }
        endpointer.onFrame(first, 0, 1_032L)
        assertEquals("nBytes must be the exact native frame size (T6)", VadProbe.FRAME_BYTES, probe.lastNBytes)
        assertArrayEquals(
            "the probe was handed something other than the accumulated frame: an unfilled buffer, " +
                "a stale one, or a byte-order-sensitive fill (teardown-bill T7)",
            first,
            probe.lastFrame,
        )

        // Refilled, not reallocated — and the SECOND frame must not read as the first.
        val second = ByteArray(VadProbe.FRAME_BYTES) { ((0xA5 - it) and 0xFF).toByte() }
        endpointer.onFrame(second, 0, 1_064L)
        assertArrayEquals("the one buffer must be REFILLED for every frame", second, probe.lastFrame)
        repeat(8) { i -> endpointer.onFrame(first, 0, 1_096L + i * 32L) }

        assertEquals(10, probe.frameCalls)
        val buffer = probe.buffersSeen.single()
        assertTrue(
            "the native side reads the frame through GetDirectBufferAddress, so a heap buffer " +
                "would not work at all",
            buffer.isDirect,
        )
        assertEquals(VadProbe.FRAME_BYTES, buffer.capacity())
        endpointer.onSessionEnd()
    }

    /**
     * THE FORBIDDEN SECOND CONVERSION SITE, and the only instrument that can see it.
     *
     * `EndpointerTuning.PROBE_BUDGET_US` exists as "ONE conversion for the whole seam", and its
     * KDoc names the three consumers that must agree — the third being "the `ProbeStats`
     * `EndpointerFactory` (Task D8) passes in". `SileroEndpointer` calls that same site "the third
     * site is the one this class cannot reach", and three things together are what put it out of
     * behavioural reach — privacy alone does NOT, since `overruns()` reflects the budget on a
     * directly-constructed instance (review m5). They are: `ProbeStats` keeps `budgetUs` private
     * with no getter; **the instance this factory builds is reachable from nothing** —
     * `SileroEndpointer` holds it as a `private val` and surfaces it only through `diag`, which the
     * factory leaves defaulted to `android.util.Log.i`, a no-op under `returnDefaultValues`; and
     * both spellings are the SAME compile-time constant (`PROBE_BUDGET_US` is literally
     * `PROBE_BUDGET_MS * 1_000L`), so the emitted bytecode is identical. A wrong budget here would
     * therefore be latent, invisible and green, right up until a retune of the conversion split the
     * cutout latch from the `probe:` line in a commit that looked like a tidy-up.
     *
     * Prose was the whole enforcement until this test (C10 pinned the SENTENCE in
     * `SileroEndpointer.kt`, which is an obligation ON this file, not a guard over it). So this
     * reads the source, in the same shape and for the same reason as
     * `VadProbeLifecycleTest.theFrameContractConstantsAreAliasesNotSecondLiterals` — including its
     * lesson: line endings are normalised to LF at the single read site, so a CRLF checkout cannot
     * defeat the match, and including its INSTRUMENT: exact equality on the extracted
     * right-hand side, never a `contains`. A substring test passes
     * `EndpointerTuning.PROBE_BUDGET_US * 2L` — the reviewer demonstrated exactly that escape
     * surviving 11/0/0 — because a scaled budget still carries the alias.
     */
    @Test
    fun theProbeStatsBudgetIsTheSingleOwnedConversionAndNeverASecondOne() {
        val code = factoryCode()
        val constructions = code.filter { it.contains("ProbeStats(") }
        assertEquals(
            "EndpointerFactory.kt must construct exactly ONE ProbeStats — this guard is reading " +
                "the wrong file, or the binding moved: $constructions",
            1,
            constructions.size,
        )
        assertEquals(
            "EndpointerFactory.kt constructs ProbeStats with something other than " +
                "EndpointerTuning.PROBE_BUDGET_US — or the construction was wrapped across lines, " +
                "which this guard is line-based by design and so fails CLOSED on: " +
                "${constructions.single()}",
            "EndpointerTuning.PROBE_BUDGET_US",
            constructions.single().substringAfter("budgetUs =").substringBefore(")").trim(),
        )
        assertEquals(
            "EndpointerFactory.kt spells the millisecond budget in CODE. That is the second " +
                "conversion site EndpointerTuning.PROBE_BUDGET_US exists to prevent: the endpointer's " +
                "cutout latch and the ProbeStats behind the `probe:` line would then hold two " +
                "opinions about what an overrun is, and they would agree today and diverge on the " +
                "next retune, silently. Pass PROBE_BUDGET_US.",
            emptyList<String>(),
            code.filter { it.contains("PROBE_BUDGET_MS") },
        )
    }

    // ---------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------

    /**
     * One session's capture thread, run to completion: a FRESH `Thread` per session is what both
     * capture sources do, and it is precondition (1) of the factory's once-per-thread snapshot.
     * The join is BOUNDED and the death is ASSERTED rather than assumed (C9).
     */
    private fun captureThread(name: String, body: () -> Unit) {
        val t = Thread(body, name)
        t.start()
        t.join(JOIN_MS)
        assertFalse("capture thread $name did not finish within $JOIN_MS ms", t.isAlive)
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, relative).let { if (it.isFile) return it }
            File(dir, "app/$relative").let { if (it.isFile) return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $relative from ${System.getProperty("user.dir")}")
    }

    /** Line endings normalised to LF at the single read site (the N1/N2 lesson). */
    private val src: String by lazy {
        repoFile("src/main/java/com/whispereverywhere/audio/EndpointerFactory.kt")
            .readText().replace("\r\n", "\n")
    }

    /** The file's CODE lines: KDoc bodies and whole-line `//` comments dropped, trailing `//` cut. */
    private fun factoryCode(): List<String> =
        src.lines()
            .map { it.trim() }
            .filterNot {
                it.startsWith("*") || it.startsWith("/**") || it.startsWith("//") ||
                    it.startsWith("*/")
            }
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
}
