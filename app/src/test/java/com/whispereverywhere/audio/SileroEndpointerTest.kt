package com.whispereverywhere.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val B = EndpointerTuning.FRAME_BYTES

/** Non-zero: 0L is the endpointer's "no micro-pause remembered" sentinel. */
private const val BASE = 1_000_000L

/**
 * The injected probe. Records a COPY of every frame it is handed — the endpointer REUSES one
 * 1024-byte array, so retaining the reference would alias every "frame" onto the latest one.
 * `the_probe_is_handed_ONE_reused_array_which_is_why_the_fake_copies` proves that is a real hazard
 * and not a defensive habit.
 */
private class FakeProbe(var next: Float = 0f) : (ByteArray) -> Float {
    val frames = mutableListOf<ByteArray>()
    override fun invoke(frame: ByteArray): Float {
        frames += frame.copyOf()
        return next
    }
}

/**
 * Drives one endpointer at the real 32 ms frame cadence, holding the clock between stretches.
 *
 * `t` IS this section's clock: there is no separate fake-clock type, because the endpointer never
 * reads a clock of its own — every time it knows comes in as `onFrame`'s `nowMs`, which is the
 * whole point of the "ONE clock" ruling in [SileroEndpointer]'s KDoc. Advancing `t` here is
 * therefore the only way time passes anywhere in this class.
 */
private class Pump(
    val ep: SileroEndpointer,
    val probe: FakeProbe,
    var t: Long = BASE,
) {
    var commits = 0
    var lastCommitMs = -1L

    /** Feeds [frames] complete frames of probability [p]. @return true if any of them committed. */
    fun run(p: Float, frames: Int): Boolean {
        probe.next = p
        var fired = false
        repeat(frames) {
            if (ep.onFrame(ByteArray(B), 0, t)) {
                fired = true
                commits++
                lastCommitMs = t
            }
            t += EndpointerTuning.FRAME_MS
        }
        return fired
    }
}

class SileroEndpointerTest {

    @Test fun an_exact_frame_reaches_the_probe_untouched() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        assertFalse(ep.onFrame(ByteArray(B) { 3 }, 0, BASE))
        assertEquals(1, probe.frames.size)
        assertEquals(B, probe.frames[0].size)
        assertTrue(probe.frames[0].all { it == 3.toByte() })
    }

    @Test fun short_reads_are_reassembled_into_exact_512_sample_frames() {
        // AudioRecord.read() returns UP TO the buffer size and StreamingAudioRecorder forwards
        // buffer.copyOf(read) (StreamingAudioRecorder.kt:80,97); the 48 kHz decimator documents
        // "~1024" (PlaybackAudioCapturer.kt:62). One chunk = one frame is the common case, never
        // the contract — and a zero-padded short frame would poison the LSTM.
        //
        // The second chunk is sized RELATIVE to B so the pair always sums to exactly one frame:
        // 700 + 324 today, and still one whole frame if the Silero window ever changes. The
        // frame size itself is pinned absolutely, once, by EndpointerTuningTest.
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        assertFalse(ep.onFrame(ByteArray(700) { 1 }, 0, BASE))
        assertEquals("a partial frame must never reach the probe", 0, probe.frames.size)
        assertFalse(ep.onFrame(ByteArray(B - 700) { 2 }, 0, BASE + EndpointerTuning.FRAME_MS))
        assertEquals(1, probe.frames.size)
        val f = probe.frames[0]
        assertEquals(B, f.size)
        assertTrue("bytes 0..699 come from chunk 1", (0 until 700).all { f[it] == 1.toByte() })
        assertTrue("bytes 700..1023 come from chunk 2", (700 until B).all { f[it] == 2.toByte() })
    }

    @Test fun one_chunk_carrying_several_frames_probes_each_of_them() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        assertFalse(ep.onFrame(ByteArray(2 * B + 452), 0, BASE))
        assertEquals("2048 of 2500 bytes are two whole frames; 452 are retained", 2, probe.frames.size)
        assertFalse(ep.onFrame(ByteArray(B - 452), 0, BASE + EndpointerTuning.FRAME_MS))
        assertEquals("the leftover 452 carried, so 572 more bytes complete a third frame", 3, probe.frames.size)
        assertTrue(probe.frames.all { it.size == B })
    }

    @Test fun the_minus_one_sentinel_never_opens_the_gate() {
        // The guard in onProb is `p < 0f`, not `p == NO_VERDICT`: a probability is by definition
        // in [0, 1], so ANY negative is "no verdict", and an exact float comparison against a
        // value that crossed a JNI boundary is the wrong instrument. This assertion is what makes
        // the two equivalent — it fails if the sentinel is ever redefined to a non-negative value,
        // which would leave the guard silently unreachable.
        assertTrue(
            "the `p < 0f` guard in SileroEndpointer.onProb only covers NO_VERDICT while the " +
                "sentinel is negative",
            EndpointerTuning.NO_VERDICT < 0f,
        )
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        val pump = Pump(ep, probe)
        assertFalse(pump.run(EndpointerTuning.NO_VERDICT, 200))
        assertFalse("a no-verdict frame is never silence and never speech", ep.hasPendingSpeech())
        assertEquals("the frames are still consumed and probed", 200, probe.frames.size)
    }

    @Test fun reset_drops_the_partial_frame_and_resets_the_native_probe() {
        // reset() is one of the five vadProbeReset sites (cap cut FloatingBubbleService.kt:1722,
        // switchSource :1819, onOpen :2224, stopRecording :2393 — all four verified against the
        // tree today — and this internal one). The partial frame goes with it: the next probe
        // frame must start on a boundary aligned with the fresh LSTM.
        var resets = 0
        val probe = FakeProbe()
        val ep = SileroEndpointer(
            probe = probe,
            probeReset = { resets++ },
        )
        assertFalse(ep.onFrame(ByteArray(900) { 5 }, 0, BASE))
        assertEquals(0, probe.frames.size)
        ep.reset()
        assertEquals(1, resets)
        assertFalse(ep.onFrame(ByteArray(B) { 6 }, 0, BASE + EndpointerTuning.FRAME_MS))
        assertEquals(1, probe.frames.size)
        assertTrue(
            "the 900 dropped bytes must not survive into the next frame",
            probe.frames[0].all { it == 6.toByte() },
        )
    }

    @Test fun an_empty_chunk_is_a_no_op() {
        val probe = FakeProbe()
        val ep = SileroEndpointer(probe = probe)
        assertFalse(ep.onFrame(ByteArray(0), 0, BASE))
        assertEquals(0, probe.frames.size)
    }

    /**
     * The supertype is declared FROM BIRTH, and this is the only test that checks it: every other
     * test in this class binds the concrete type and would survive `: Endpointer` being dropped
     * without noticing. The seam only ever sees the interface — `FloatingBubbleService` holds an
     * [Endpointer] and `EndpointerFactory` (Task D8) returns one — so a class that conforms only
     * at the end of a ten-task workstream has nine tasks' worth of code checked by nothing.
     */
    @Test fun it_wears_the_Endpointer_supertype_from_birth() {
        val probe = FakeProbe()
        val ep: Endpointer = SileroEndpointer(probe = probe)
        assertFalse("onFrame is reachable through the interface", ep.onFrame(ByteArray(B), 0, BASE))
        assertFalse("hasPendingSpeech is reachable through the interface", ep.hasPendingSpeech())
        ep.reset()
        assertEquals("and the frame really went through the accumulator", 1, probe.frames.size)
    }

    /**
     * The endpointer hands the SAME array to the probe every time.
     *
     * That is not an implementation detail a test may ignore, it is the reason [FakeProbe] copies:
     * a fake that retained the reference would alias every recorded "frame" onto the newest one,
     * and every content assertion in this class would silently become an assertion about the last
     * frame only. It is also the contract [Endpointer] states for the real probe — allocation-free
     * on the `onFrame` path, and "the probe must copy anything it retains".
     */
    @Test fun the_probe_is_handed_ONE_reused_array_which_is_why_the_fake_copies() {
        val kept = mutableListOf<ByteArray>() // references, deliberately NOT copies
        val aliasing = SileroEndpointer(probe = { f -> kept += f; 0f })
        aliasing.onFrame(ByteArray(B) { 1 }, 0, BASE)
        aliasing.onFrame(ByteArray(B) { 2 }, 0, BASE + EndpointerTuning.FRAME_MS)
        assertEquals(2, kept.size)
        assertSame(
            "one array for the life of the endpointer: no per-frame allocation on the audio thread",
            kept[0],
            kept[1],
        )
        assertTrue(
            "the reference kept from the FIRST frame now reads as the SECOND frame's bytes — " +
                "this is the aliasing FakeProbe.copyOf() exists to defeat",
            kept[0].all { it == 2.toByte() },
        )

        val probe = FakeProbe()
        val copying = SileroEndpointer(probe = probe)
        copying.onFrame(ByteArray(B) { 1 }, 0, BASE)
        copying.onFrame(ByteArray(B) { 2 }, 0, BASE + EndpointerTuning.FRAME_MS)
        assertTrue("frame 1 survives in the copying fake", probe.frames[0].all { it == 1.toByte() })
        assertTrue("frame 2 is its own array", probe.frames[1].all { it == 2.toByte() })
    }

    /**
     * The obligations this file places on LATER tasks and on other files are pinned as WHOLE
     * SENTENCES, each scoped to the member that states it.
     *
     * Same instrument and same reason as `EndpointerTuningTest`: a stub whose semantics live only
     * in a comment is a stub whose semantics can be deleted by the task that fills it in. Whole
     * sentences rather than distinctive words, per the N6 K10 lesson.
     */
    @Test fun the_binding_obligations_are_pinned_in_the_source() {
        val pins = listOf(
            Pin(
                "C3 must make hasPendingSpeech honest, and the KDoc says what honest MEANS",
                kdocFor("override fun hasPendingSpeech()"),
                "Task C3 replaces this stub and MUST implement the predicate the LOCAL-silence " +
                    "re-arm at `FloatingBubbleService.kt:1716` reads",
            ),
            Pin(
                "and what a constant `false` costs until it does",
                kdocFor("override fun hasPendingSpeech()"),
                "a constant `false` re-arms the 4 s first-cap window on EVERY local wall-cap cut",
            ),
            Pin(
                "the probe must copy anything it retains — the array is reused",
                classKdoc(),
                "The array is REUSED between calls — the probe must copy anything it retains.",
            ),
            Pin(
                "-1.0f keeps the previous state; it can neither open nor close the gate (T10)",
                kdocFor("private fun onProb("),
                "the previous state is kept exactly — it can neither open nor close the gate",
            ),
            Pin(
                "the accumulator is the CONTRACT, not an optimisation (T6)",
                kdocFor("override fun onFrame("),
                "accumulating to exact frame boundaries is the CONTRACT, not an optimisation",
            ),
            Pin(
                "the cadence floor is not a constructor parameter, and why",
                classKdoc(),
                "The per-tier cost governor is NOT a constructor parameter",
            ),
        )

        pins.forEach { (item, scope, sentence) ->
            assertTrue(
                "SileroEndpointer.kt no longer states: \"$sentence\"\n" +
                    "That sentence is the written obligation for: $item.\n" +
                    "This class is built across ten tasks that each replace a stub someone else " +
                    "wrote. The sentence beside a stub is the only thing that tells the next " +
                    "implementer what the stub was standing in FOR — delete it and the stub " +
                    "becomes indistinguishable from finished code. Restore the sentence, or, if " +
                    "the obligation really changed, change the code, the sentence and this pin " +
                    "together.",
                prose(scope).contains(sentence),
            )
        }
    }

    /**
     * The class KDoc promises "@Volatile", and `reset()` really is called from Main while the
     * capture thread is inside `onFrame` (`Endpointer`'s own threading note; the four service
     * sites at FloatingBubbleService.kt:1722/:1819/:2224/:2393). Without the annotation there is
     * no happens-before edge at all between the Main-thread clear and the capture thread, so the
     * cleared state may never become visible — a promise no prose can keep on its own.
     *
     * CARRY, deliberately enforced on later tasks: Tasks C3-C10 add fields to this class. The
     * plan's C3 snippet declares its three (`speaking`, `speechStartMs`, `pendingSpeech`) without
     * the annotation; this test is what tells that implementer to add it, in the same breath as
     * the reason. `val` fields are exempt — final-field semantics already publish them safely.
     */
    @Test fun every_mutable_field_is_volatile_because_reset_arrives_from_Main() {
        val lines = src.lines()
        // Leading annotations are part of the match, not a reason to miss the declaration: a
        // pattern that only saw BARE `private var` would see no fields at all once they are
        // annotated, and would then pass this test for the wrong reason forever.
        val decl = Regex(
            """^ {4}(?:@\w+\s+)*(?:(?:private|internal|protected|lateinit)\s+)*var\s+(\w+)""",
        )
        val offenders = lines.withIndex().mapNotNull { (i, line) ->
            val name = decl.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val annotatedHere = line.contains("@Volatile")
            val annotatedAbove = i > 0 && lines[i - 1].trim() == "@Volatile"
            if (annotatedHere || annotatedAbove) null else name
        }
        assertEquals(
            "SileroEndpointer declares a mutable field without @Volatile: $offenders. onFrame " +
                "runs on the capture thread and reset() is called from Main at switchSource, " +
                "onOpen and stopRecording, so every var in this class is read on one thread and " +
                "written on another. @Volatile is the tolerance this class documents (and the one " +
                "SegmentCapPolicy already ships): the writes are not atomic together, but a torn " +
                "observation costs at most one 32 ms chunk of slack — whereas an unannotated " +
                "field may never publish the Main-thread clear at all.",
            emptyList<String>(),
            offenders,
        )
        assertTrue(
            "SileroEndpointer declares no mutable fields at all — this guard is reading the " +
                "wrong file or the field block moved out of the class body",
            lines.any { decl.containsMatchIn(it) },
        )
    }

    /**
     * The frame contract is IMPORTED, never re-literalised.
     *
     * `EndpointerTuning` states, in a sentence that suite already pins, that it is the SINGLE OWNER
     * of the JVM side of the native frame contract — `FRAME_BYTES` and `NO_VERDICT`. A fresh
     * `1024` or `-1.0f` written into this file is not a style question: it is a second owner, and
     * the next edit to the real one would move the probe's frame size on one side of the seam only.
     *
     * CODE only, deliberately. The KDoc above `onFrame` cites the two OTHER 1024s in the capture
     * path (`StreamingAudioRecorder.kt:80`, the 48 kHz decimator's "~1024") precisely to say they
     * are AudioRecord read sizes and not the frame contract — a scan that could not tell prose from
     * code would forbid the sentence that prevents the confusion.
     */
    @Test fun the_frame_contract_is_imported_never_re_literalised() {
        val forbidden = mapOf(
            Regex("""(?<![\w.])1024(?![\w.])""") to "EndpointerTuning.FRAME_BYTES",
            Regex("""(?<![\w.])-1\.0f""") to "EndpointerTuning.NO_VERDICT",
        )
        forbidden.forEach { (literal, constant) ->
            val hits = code().filter { literal.containsMatchIn(it) }
            assertEquals(
                "SileroEndpointer.kt writes the frame contract as a literal instead of importing " +
                    "$constant: $hits. EndpointerTuning owns the JVM side of that contract in one " +
                    "place, and says so in a sentence EndpointerTuningTest enforces; a second " +
                    "literal here would let a future edit move the frame size or turn the native " +
                    "sentinel into a legitimate probability on one side of the seam only.",
                emptyList<String>(),
                hits,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Source-reading helpers. Same shape as EndpointerTuningTest's, same reasons.
    // ---------------------------------------------------------------------------------------

    private data class Pin(val item: String, val scope: String, val sentence: String)

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
     * Line endings are normalized to LF at the single read site, so no anchor below can be
     * defeated by a CRLF checkout (the N1/N2 lesson: `readText()` does not normalize).
     */
    private val src: String by lazy {
        repoFile("src/main/java/com/whispereverywhere/audio/SileroEndpointer.kt")
            .readText().replace("\r\n", "\n")
    }

    /**
     * The file's CODE lines: KDoc bodies, block openers/closers and whole-line `//` comments
     * dropped, trailing `// …` cut. Approximate by design — it exists so a literal scan cannot be
     * tripped by a comment that DISCUSSES a literal, which is the only prose this file contains
     * about numbers.
     */
    private fun code(): List<String> =
        src.lines()
            .map { it.trim() }
            .filterNot {
                it.startsWith("*") || it.startsWith("/**") || it.startsWith("//") ||
                    it.startsWith("*/")
            }
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }

    /** The class's own KDoc: the block that ends at the `class SileroEndpointer` declaration. */
    private fun classKdoc(): String {
        val at = src.indexOf("class SileroEndpointer(")
        assertTrue("SileroEndpointer.kt no longer declares `class SileroEndpointer(`", at >= 0)
        val head = src.substring(0, at)
        val open = head.lastIndexOf("/**")
        assertTrue(
            "no KDoc block opens above `class SileroEndpointer(`. lastIndexOf returns -1 when the " +
                "block is gone, so this assert names the real cause — the class's state-machine, " +
                "clock, threading and constructor-scope rulings were deleted — instead of failing " +
                "the sentence pins for what reads like drift.",
            open >= 0,
        )
        return head.substring(open)
    }

    /**
     * The KDoc block immediately above one declaration — from its opening marker down to the
     * declaration. (Spelling that marker out here is not possible: Kotlin NESTS block comments, so
     * it would open one that never closes.)
     */
    private fun kdocFor(declaration: String): String {
        val at = src.indexOf(declaration)
        assertTrue("SileroEndpointer.kt no longer declares `$declaration`", at >= 0)
        val head = src.substring(0, at)
        val open = head.lastIndexOf("/**")
        assertTrue("no KDoc block opens above `$declaration`", open >= 0)
        val block = head.substring(open)
        assertTrue(
            "the KDoc scope for `$declaration` widened past a previous member: lastIndexOf finds " +
                "the NEAREST block above the declaration, so deleting THIS member's KDoc outright " +
                "silently borrows the previous member's (or the class's) — and an obligation pin " +
                "could then be satisfied by a sentence written about something else.",
            block.lineSequence().drop(1).none {
                val t = it.trimStart()
                t.startsWith("override fun ") || t.startsWith("private fun ") ||
                    t.startsWith("class ") || t.startsWith("private val ") ||
                    t.startsWith("@Volatile")
            },
        )
        return block
    }

    /**
     * KDoc/comment prose as a single normalized line: leading decoration stripped and runs of
     * whitespace collapsed, so a pin can anchor on a WHOLE SENTENCE without being defeated by
     * wherever the 100-column limit happened to wrap it.
     */
    private fun prose(scope: String): String =
        scope.lineSequence()
            .map { line ->
                var t = line.trim()
                if (t.startsWith("/**")) t = t.removePrefix("/**")
                if (t.startsWith("//")) t = t.removePrefix("//")
                if (t.endsWith("*/")) t = t.removeSuffix("*/")
                if (t.startsWith("*")) t = t.removePrefix("*")
                t.trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
}
