package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [SileroEndpointer] is a TWO-THREAD object, and this class is where that is stated as a property
 * rather than as prose.
 *
 * [SileroEndpointer.onFrame] runs on the CAPTURE thread. Main mutates the same fields from three
 * sites in `FloatingBubbleService` — [SileroEndpointer.reset] at `switchSource` and `stopRecording`,
 * and [SileroEndpointer.onSessionStart] at `onOpen` (the site that used to be a fourth `reset`).
 * (Symbols, not line numbers: Workstream D moves this file's call sites, and a pin that cited
 * `:1819` would start lying the day it did.)
 *
 * What `@Volatile` buys here is VISIBILITY, not atomicity: the writes are not atomic TOGETHER, so a
 * Main-thread clear racing the capture thread can be lost, and a torn observation costs at most one
 * 32 ms chunk of slack — the same tolerance [com.whispereverywhere.service.SegmentCapPolicy]
 * documents for exactly these two callers. Without the annotation there is no happens-before edge at
 * all between the Main-thread clear and the capture thread, so the cleared state may never become
 * visible: a bug with no symptom on the desk and a stuck gate on the device.
 *
 * Two tests, deliberately of two different kinds:
 *  - [every_cross_thread_field_stays_volatile] is the STRUCTURAL pin. It is reflective on purpose,
 *    so dropping a `@Volatile` in a future refactor fails the build instead of producing a stale
 *    read on a phone.
 *  - [main_thread_resets_never_corrupt_the_capture_thread_pump] is the BEHAVIOURAL one. Real
 *    threads throughout — a same-thread stub would prove nothing here — and its load-bearing
 *    assertion is not the storm but what happens AFTER it: the machine has to still cut correctly.
 *
 * `SileroEndpointerTest.every_mutable_field_is_volatile_because_reset_arrives_from_Main` scans the
 * SOURCE for the same property; this one interrogates the compiled CLASS. The redundancy is
 * deliberate and cheap: the scan catches a `var` written without the annotation, this catches an
 * annotation that did not survive to a real JVM field, and neither subsumes the other.
 */
class SileroEndpointerConcurrencyTest {

    /**
     * THE CENSUS: the complete set of mutable instance fields, enumerated here, every one of them
     * `@Volatile`.
     *
     * SET EQUALITY, not containment — and that is the half of this test that earns its keep. A pin
     * that merely checked the thirteen listed names were present and volatile would go on passing
     * the day someone adds a fourteenth `var` and never enrols it, which is precisely the field
     * whose cross-thread behaviour nobody has thought about yet. Making the set EQUAL turns "a new
     * mutable field appeared" into a build failure that hands the author the question.
     */
    @Test fun every_cross_thread_field_stays_volatile() {
        val required = listOf(
            "fill", "lastFrameMs", "speaking", "speechStartMs", "pendingSpeech", "tempEndMs",
            "prevEndMs", "lastCommitMs", "hasCommitted", "minCommitIntervalMs", "slowRun",
            "probeCutout", "lastCutRecord",
        )
        val declared = SileroEndpointer::class.java.declaredFields.associateBy { it.name }

        // Kotlin `val`s — `frame` and the three constructor parameters — compile to FINAL backing
        // fields, which final-field semantics already publish safely; JaCoCo's `$jacocoData` is
        // static and synthetic. Strip those three categories and what is left is exactly the
        // mutable state this class carries across the capture/Main boundary.
        val mutable = SileroEndpointer::class.java.declaredFields
            .filter {
                !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) &&
                    !it.isSynthetic
            }
            .map { it.name }
            .toSet()
        assertEquals(
            "SileroEndpointer's mutable-field census is out of date. Every `var` in this class is " +
                "written on one thread and read on another, so a new one is a new cross-thread " +
                "hazard — not a detail. Add it to `required` above, mark it @Volatile, and say in " +
                "its KDoc which thread writes it and which reads it. If it genuinely is NOT " +
                "cross-thread, say why there instead; this list is the record of that decision, " +
                "and an unenrolled field is the same as no decision at all.",
            required.toSet(),
            mutable,
        )

        for (name in required) {
            val field = declared[name]
            assertTrue("field $name has been renamed or removed — update this pin", field != null)
            assertTrue(
                "$name is written on the capture thread and read/written from Main (reset at " +
                    "switchSource / stopRecording, onSessionStart at onOpen — " +
                    "FloatingBubbleService): it must stay @Volatile",
                Modifier.isVolatile(field!!.modifiers),
            )
        }
    }

    /**
     * A real capture thread pumping frames while Main hammers 5 000 resets, and then — the part
     * that carries this test — a check that the machine came out of it FUNCTIONALLY INTACT.
     *
     * The storm alone proves less than it looks. "No exception" is nearly unfalsifiable here, and
     * "the probe only ever saw whole frames" is vacuous BY CONSTRUCTION: `timedProbe` always hands
     * over the internal fixed-size accumulator, so no interleaving whatsoever could produce a short
     * frame. Both are kept as harness sanity checks — they would catch a bounds bug or a pump that
     * silently did nothing — but the substance is the COHERENCE phase below: on the same instance,
     * after the storm, a scripted canonical utterance must still produce the exact cut C8 pins.
     * Its two assertions split the claim. The marker-byte check answers "no torn `fill`" on
     * CONTENT — every frame the probe sees must be this session's audio, not a mix of it and the
     * storm's. The exact [EndpointCut] answers "no stuck gate, no corrupted governor" — speechMs
     * comes from the Schmitt gate's start stamp, trailMs from the hangover's pending end, and the
     * cut happening at all means the cadence governor re-armed with the session.
     */
    @Test fun main_thread_resets_never_corrupt_the_capture_thread_pump() {
        val badFrameSize = AtomicInteger(0)
        val probed = AtomicInteger(0)

        // The probe is fixed at construction, so a test with two phases needs one indirection: the
        // constructor lambda delegates through this reference, and the coherence phase swaps in a
        // scripted probe rather than fighting the storm's own frame counter for control.
        val stormProbe: (ByteArray) -> Float = { frame ->
            if (frame.size != EndpointerTuning.FRAME_BYTES) badFrameSize.incrementAndGet()
            // Alternating speech/silence so the state machine is doing real work throughout.
            if ((probed.incrementAndGet() / 20) % 2 == 0) 0.9f else 0.05f
        }
        val scripted = AtomicReference(0f)
        val delegate = AtomicReference(stormProbe)
        val ep = SileroEndpointer(probe = { frame -> delegate.get().invoke(frame) })
        ep.onSessionStart(nowMs = STORM_BASE, minCommitIntervalMs = 1_200L)

        val capture = Executors.newSingleThreadExecutor()
        val stop = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val thrown = AtomicReference<Throwable?>(null)

        capture.execute {
            try {
                var t = STORM_BASE
                var i = 0
                // Short reads interleaved with whole frames, as AudioRecord really delivers them.
                while (!stop.get() && i < 200_000) {
                    val size = if (i % 3 == 0) 640 else EndpointerTuning.FRAME_BYTES
                    ep.onFrame(ByteArray(size), 0, t)
                    t += EndpointerTuning.FRAME_MS
                    i++
                    if (i == 10) started.countDown()
                }
            } catch (th: Throwable) {
                thrown.set(th)
            } finally {
                started.countDown()
                done.countDown()
            }
        }

        // The whole point is OVERLAP, and 5 000 resets take about a millisecond: without this the
        // storm can finish before the executor has scheduled its thread at all, and the test passes
        // having never run the two threads at once. Waiting for the pump to be hot first is what
        // makes the reset loop concurrent with it rather than merely before it.
        assertTrue("the capture thread never started", started.await(10, TimeUnit.SECONDS))
        repeat(5_000) { ep.reset() }        // Main hammering the external reset sites
        stop.set(true)
        assertTrue("the capture pump did not finish", done.await(30, TimeUnit.SECONDS))
        capture.shutdownNow()

        assertNull("capture thread threw: ${thrown.get()}", thrown.get())
        assertEquals("the probe must only ever see whole frames", 0, badFrameSize.get())
        assertTrue("the pump did no work", probed.get() > 0)

        // ---- Coherence. Same instance, single-threaded from here, deterministic clock. ----
        // Every byte of this phase's audio is MARK, and the storm's was all zero, so a frame that
        // is not uniformly MARK is a frame the accumulator carried storm bytes into: the "no torn
        // fill" half of the claim, checked on content rather than assumed.
        val dirty = AtomicInteger(0)
        delegate.set { f ->
            if (!f.all { it == MARK }) dirty.incrementAndGet()
            scripted.get()
        }
        ep.reset()
        ep.onSessionStart(nowMs = COHERENCE_BASE, minCommitIntervalMs = 1_200L)

        var t = COHERENCE_BASE
        scripted.set(0.9f)
        repeat(20) {
            assertFalse(
                "speech itself never commits",
                ep.onFrame(ByteArray(EndpointerTuning.FRAME_BYTES) { MARK }, 0, t),
            )
            t += EndpointerTuning.FRAME_MS
        }
        scripted.set(0.1f)
        var silent = 0
        var cutOn = -1
        while (cutOn < 0 && silent < 40) {
            silent++
            if (ep.onFrame(ByteArray(EndpointerTuning.FRAME_BYTES) { MARK }, 0, t)) cutOn = silent
            t += EndpointerTuning.FRAME_MS
        }
        assertEquals(
            "the storm left bytes in the accumulator: a fresh session's first frame was part " +
                "storm audio, so reset()/onSessionStart no longer empty it",
            0,
            dirty.get(),
        )

        // The canonical sequence C8 pins (`a_vad_cut_records_what_it_cut`): the gate opens on the
        // first 0.9 frame, the dip's first frame stamps the pending end 640 ms later, and the 17th
        // silent frame is the first past the 500 ms hangover — 512 ms of trail. Every one of those
        // three numbers comes out of a different field the storm was hammering.
        assertEquals("the hangover must still fire on the 17th silent frame", 17, cutOn)
        assertEquals(
            "5 000 concurrent resets left the state machine cutting differently — the accumulator, " +
                "the Schmitt gate or the cadence governor did not survive the storm",
            EndpointCut(speechMs = 640L, trailMs = 512L, prob = 0.1f),
            ep.lastCut(),
        )
    }

    private companion object {
        /** Non-zero: 0L is the endpointer's "no micro-pause remembered" sentinel. */
        const val STORM_BASE = 1_000_000L

        /**
         * Comfortably past the storm's last frame clock, so the coherence phase reads as the fresh
         * session it is even when a stale timestamp somehow survives [SileroEndpointer.reset].
         */
        const val COHERENCE_BASE = 9_000_000L

        /** The coherence phase's audio. Any byte but the storm's zeroes would do. */
        const val MARK: Byte = 7
    }
}
