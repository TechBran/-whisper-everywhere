package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/** Scriptable probe: counts every native call and can fail or throw on init. */
class FakeVadProbe(
    private val initReturns: Boolean = true,
    private val initThrows: Boolean = false,
) : VadProbe {
    val calls = mutableListOf<String>()
    var initCalls = 0
    var resetCalls = 0
    var freeCalls = 0
    var lastPath: String? = null

    override fun init(modelPath: String): Boolean {
        initCalls++; lastPath = modelPath; calls += "init"
        if (initThrows) throw RuntimeException("probe init blew up")
        return initReturns
    }
    override fun frame(pcm: ByteBuffer, nBytes: Int): Float = 0.9f
    override fun reset() { resetCalls++; calls += "reset" }
    override fun free() { freeCalls++; calls += "free" }
}

/**
 * The probe's init/free lifecycle on the CAPTURE path (3.7, Workstream D). The rules this pins:
 * init exactly once, lazily, off Main; a failed init latches for the whole session and is never
 * retried per frame; free only ever follows a successful init; a fresh arm() re-initialises.
 *
 * SEQUENTIAL, deliberately. Every test below drives one thread, because that is the shape of the
 * contract this task ships: `VadProbeLifecycle` at D4 is correct for a lifecycle whose calls do not
 * overlap. The cross-thread cases — two capture threads racing the first frame, and a Main-thread
 * release() landing inside an in-flight init — are Task D5's, with real background executors and a
 * monitor in the class under test.
 */
class VadProbeLifecycleTest {

    @Test
    fun aNullModelPathIsUnavailableAndNeverTouchesTheProbe() {
        val probe = FakeVadProbe()
        val life = VadProbeLifecycle(probe)
        life.arm(null)
        assertEquals(VadProbeLifecycle.State.UNAVAILABLE, life.state())
        assertFalse(life.ensureReady())
        assertEquals(0, probe.initCalls)
    }

    @Test
    fun initHappensOnTheFirstFrame_exactlyOnce() {
        val probe = FakeVadProbe()
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/ggml-silero-v5.1.2.bin")
        // Arming alone must NOT initialise: arm() runs on Main, init must run on the capture thread.
        assertEquals(VadProbeLifecycle.State.ARMED, life.state())
        assertEquals(0, probe.initCalls)

        assertTrue(life.ensureReady())
        assertEquals(VadProbeLifecycle.State.READY, life.state())
        assertEquals(1, probe.initCalls)
        assertEquals("/data/vad/ggml-silero-v5.1.2.bin", probe.lastPath)

        // 31.25 calls/second for the rest of the session — all of them free.
        repeat(100) { assertTrue(life.ensureReady()) }
        assertEquals(1, probe.initCalls)
    }

    @Test
    fun aFailedInitLatchesForTheSessionAndIsNeverRetried() {
        val probe = FakeVadProbe(initReturns = false)
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin")
        assertFalse(life.ensureReady())
        assertEquals(VadProbeLifecycle.State.UNAVAILABLE, life.state())
        repeat(500) { assertFalse(life.ensureReady()) }
        assertEquals("a failed init must never be retried per frame", 1, probe.initCalls)
    }

    @Test
    fun aThrowingInitLatchesAndDoesNotEscapeToTheCaptureThread() {
        val probe = FakeVadProbe(initThrows = true)
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin")
        assertFalse(life.ensureReady())      // must not throw: the audio thread must not die
        assertEquals(VadProbeLifecycle.State.UNAVAILABLE, life.state())
        repeat(10) { assertFalse(life.ensureReady()) }
        assertEquals(1, probe.initCalls)
    }

    @Test
    fun resetOnlyReachesTheProbeOnceItIsReady() {
        val probe = FakeVadProbe()
        val life = VadProbeLifecycle(probe)
        life.reset()                          // IDLE
        life.arm("/data/vad/model.bin")
        life.reset()                          // ARMED but not initialised
        assertEquals(0, probe.resetCalls)
        life.ensureReady()
        life.reset(); life.reset()
        assertEquals(2, probe.resetCalls)
    }

    @Test
    fun freeOnlyEverFollowsASuccessfulInit() {
        val never = FakeVadProbe()
        val neverLife = VadProbeLifecycle(never)
        neverLife.arm("/data/vad/model.bin")
        neverLife.release()
        assertEquals("never initialised, so nothing to free", 0, never.freeCalls)
        assertEquals(VadProbeLifecycle.State.IDLE, neverLife.state())

        val probe = FakeVadProbe()
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin")
        life.ensureReady()
        life.release()
        assertEquals(listOf("init", "free"), probe.calls)
        life.release()
        assertEquals("release is idempotent", 1, probe.freeCalls)
    }

    @Test
    fun aFreshSessionReArmsAndReInitialises() {
        val probe = FakeVadProbe()
        val life = VadProbeLifecycle(probe)
        life.arm("/data/vad/model.bin"); life.ensureReady(); life.release()
        life.arm("/data/vad/model.bin"); assertTrue(life.ensureReady()); life.release()
        assertEquals(listOf("init", "free", "init", "free"), probe.calls)
    }

    @Test
    fun theFrameContractConstantsAreTheNativeOnes() {
        // 512 samples PCM16 mono @16 kHz = 1024 bytes = exactly one Silero window; anything else
        // returns "no verdict", never "silence" — a zero-padded short frame still advances the
        // LSTM and poisons the recurrence.
        assertEquals(1024, VadProbe.FRAME_BYTES)
        assertEquals(-1.0f, VadProbe.NO_VERDICT, 0.0f)
    }

    /**
     * The two constants above are ALIASES of [EndpointerTuning]'s, never second literals.
     *
     * The value test cannot see this. `const val FRAME_BYTES = 1024` in `VadProbe.kt` passes it
     * byte for byte, and yet it is the whole thing the companion's KDoc — and
     * [EndpointerTuning.FRAME_BYTES]'s own SINGLE OWNER sentence, which `EndpointerTuningTest`
     * pins — exists to forbid: `EndpointerFactory` (Task D8) sizes its one direct buffer from one
     * of these spellings and fills it from the other, so a divergence is a
     * `BufferOverflowException` on the capture thread, or a native sentinel silently readable as a
     * probability. A behaviourally invisible edit is exactly the edit prose does not survive, so
     * this reads the source.
     *
     * Same shape as `SileroEndpointerTest.the_wall_clock_sentinel_is_never_re_literalised_as_a_bare_zero`
     * (Task C5), including its lesson: line endings are normalised to LF at the single read site,
     * so a CRLF checkout cannot defeat the match.
     */
    @Test
    fun theFrameContractConstantsAreAliasesNotSecondLiterals() {
        listOf("FRAME_BYTES", "NO_VERDICT").forEach { name ->
            assertEquals(
                "VadProbe.kt declares $name with its own literal instead of aliasing " +
                    "EndpointerTuning.$name. That object owns the JVM side of the native frame " +
                    "contract in ONE place and says so in a sentence EndpointerTuningTest " +
                    "enforces; a second literal here is a second owner, and the next edit to the " +
                    "real one would move the frame size — or turn the -1.0f sentinel into a " +
                    "legitimate probability — on one side of the D8 buffer seam only.",
                "EndpointerTuning.$name",
                declarationOf(name).substringAfter("=").trim(),
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Source-reading helpers. Same shape as SileroEndpointerTest's, same reasons.
    // ---------------------------------------------------------------------------------------

    /**
     * The single CODE line declaring `const val [name]`, with a failure that names the file rather
     * than a vacuous pass: a scan that finds nothing must fail, not succeed quietly.
     */
    private fun declarationOf(name: String): String {
        val hits = probeCode().filter { it.contains("const val $name") }
        assertEquals(
            "VadProbe.kt must declare `const val $name` exactly once — this guard is reading the " +
                "wrong file, or the companion moved: $hits",
            1,
            hits.size,
        )
        return hits.single()
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

    /**
     * Line endings are normalised to LF at the single read site, so no anchor above can be
     * defeated by a CRLF checkout (the N1/N2 lesson: `readText()` does not normalize).
     */
    private val src: String by lazy {
        repoFile("src/main/java/com/whispereverywhere/audio/VadProbe.kt")
            .readText().replace("\r\n", "\n")
    }

    /** The file's CODE lines: KDoc bodies and whole-line `//` comments dropped, trailing `//` cut. */
    private fun probeCode(): List<String> =
        src.lines()
            .map { it.trim() }
            .filterNot {
                it.startsWith("*") || it.startsWith("/**") || it.startsWith("//") ||
                    it.startsWith("*/")
            }
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
}
