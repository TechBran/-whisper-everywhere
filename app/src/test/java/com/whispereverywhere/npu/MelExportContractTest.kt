package com.whispereverywhere.npu

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level contract guards for the 4.0 mel export: the whisper.cpp fork's
 * `whisper_get_mel_segment`, the `pcmToMel` JNI wrapper, and the Kotlin extern.
 *
 * WHY THE FORK EDIT EXISTS AT ALL: `log_mel_spectrogram` is `static` in `src/whisper.cpp` and both
 * `whisper_mel` and `whisper_state` are private to that translation unit, so there is **no public
 * read-back of the computed mel**. Reimplementing the filterbank app-side is spec-forbidden ("one
 * mel implementation in the app, ever") and would decouple NPU accuracy from CPU accuracy.
 *
 * WHY SOURCE-CONTRACT AND NOT VALUE CHECKS, stated honestly: no JVM test can execute JNI, and
 * `libwhisper_jni.so` is not on the unit-test classpath. The first NUMERICAL confirmation of this
 * mel is Q10a's `mel: bins=80 frames=3000 row0=<sum> row40=<sum> row79=<sum>` line, whose whole
 * design is to make the stride defect unmistakable (`row40 == row79` IS the stride bug). These
 * assertions therefore target the exact MUTATIONS that reintroduce it, rather than merely asserting
 * the symbol exists:
 *
 * **THE STRIDE TRAP.** `whisper_mel` is bin-major with stride `n_len`. For a 480,000-sample (30 s)
 * input `mel.n_len` is **6000**, not 3000 — `log_mel_spectrogram` appends 30 s of zeros before
 * framing. The destination the NPU encoder wants is `[1,80,3000]`, stride **3000**. The two strides
 * are different and that difference is the entire point of the exported function: a flat copy of
 * the first `n_mel * 3000` floats reads bins 0-39 at wrong offsets and **never touches bins 40-79**,
 * which produces structured noise rather than an error. Two mutants are pinned dead below — the
 * source stride replaced by the destination stride, and the bound replaced by `n_len_org`.
 *
 * `src/main/cpp/whisper.cpp/src/whisper.cpp` and `src/main/cpp/whisper.cpp/include/whisper.h` are
 * both declared inputs of the test task in `app/build.gradle.kts` — without that, an edit confined
 * to either (a header-only change is exactly the shape of this task) leaves
 * `:app:testDebugUnitTest` UP-TO-DATE and every guard here passes against stale evidence.
 */
class MelExportContractTest {

    /**
     * Reads a repo file from the test's working directory — the locator `NpuNativeContractTest`,
     * `SegmentTimingTest` and `NativeVadSourceContractTest` share. Line endings are normalized at
     * this single read site (the 3.7 N1 lesson: `readText()` does not normalize, and a CRLF
     * checkout silently defeats anything anchored on a newline).
     */
    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "cannot locate $relative from ${System.getProperty("user.dir")}"
        )
    }

    /**
     * Character offsets of every LIVE (non-comment) line of [scope] containing [needle]. Both the
     * positive and the negative pins below are scoped this way: a commented-out mention satisfies
     * `contains()` exactly as happily as the code, and the prose in this task legitimately DISCUSSES
     * the very tokens the negative pins forbid.
     */
    private fun liveOffsets(scope: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var at = 0
        for (line in scope.split("\n")) {
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            if (!commented && line.contains(needle)) out += at
            at += line.length + 1
        }
        return out
    }

    /**
     * One free function's body. Every free function in `whisper.cpp` and `whisper_jni.cpp` closes at
     * column 0, which is what makes this scoping possible — and scoping is mandatory here, not a
     * nicety: `src/whisper.cpp` legitimately contains `n_len_org` at five call sites of its own
     * (`:3208`, `:3914`, `:4048`, `:4184`, `:4188`), so a whole-file negative assertion would fail
     * on arrival and tell the reader nothing.
     */
    private fun functionBody(cpp: String, anchor: String, file: String): String {
        val start = cpp.indexOf(anchor)
        assertTrue(
            "anchor \"$anchor\" is missing from $file. indexOf() returns -1 when the anchor is " +
                "absent, so substring(start) would silently rebase the scope to the top of the " +
                "file and every claim below would be answered by unrelated code instead of failing.",
            start >= 0
        )
        val body = cpp.substring(start)
        assertTrue(
            "no column-0 \"\\n}\\n\" follows \"$anchor\" in $file. substringBefore() returns its " +
                "RECEIVER when the delimiter is absent, so a re-indented closing brace would " +
                "silently widen the scope into the FOLLOWING function and the assertions would " +
                "pass on a neighbour's code.",
            body.contains("\n}\n")
        )
        return body.substringBefore("\n}\n")
    }

    private val forkSrc: String by lazy { source("src/main/cpp/whisper.cpp/src/whisper.cpp") }
    private val forkHdr: String by lazy { source("src/main/cpp/whisper.cpp/include/whisper.h") }
    private val jni: String by lazy { source("src/main/cpp/whisper_jni.cpp") }

    /** The exported copy loop — the scope every stride assertion below is answered by. */
    private val exportBody: String by lazy {
        functionBody(
            forkSrc,
            "int whisper_get_mel_segment_with_state(",
            "the whisper.cpp fork's src/whisper.cpp",
        )
    }

    @Test
    fun forkExportsWhisperGetMelSegment() {
        assertTrue(
            "the whisper.cpp fork's include/whisper.h must DECLARE whisper_get_mel_segment. " +
                "Without a public declaration whisper_jni.cpp cannot call it, and the mel the NPU " +
                "encoder consumes has no source at all: log_mel_spectrogram is static and " +
                "whisper_mel is private to that translation unit, so there is no other read-back.",
            liveOffsets(forkHdr, "whisper_get_mel_segment(").isNotEmpty()
        )
        assertTrue(
            "include/whisper.h must also declare the _with_state form, mirroring every other mel " +
                "entry point in this header (whisper_pcm_to_mel/_with_state, " +
                "whisper_set_mel/_with_state). The state-bearing form is where the copy lives.",
            liveOffsets(forkHdr, "whisper_get_mel_segment_with_state(").isNotEmpty()
        )
        // D5, the stale-evidence hazard, and the reason this assertion sits in a test rather than
        // in a comment: the header is the ONLY file the two assertions above read. If it is not a
        // declared input of the test task, deleting the declaration leaves :app:testDebugUnitTest
        // UP-TO-DATE and this test "passes" without ever being re-run.
        val gradle = source("build.gradle.kts")
        assertTrue(
            "app/build.gradle.kts must list \"src/main/cpp/whisper.cpp/include/whisper.h\" in the " +
                "nativeSourceContract inputs.files(...) block. This test is the only reader of " +
                "that header, and a header-only edit that is not a declared test input leaves the " +
                "test task UP-TO-DATE - the guard then passes against stale evidence instead of " +
                "re-running. Same hazard the .cpp entries beside it already close.",
            liveOffsets(gradle, "\"src/main/cpp/whisper.cpp/include/whisper.h\"").isNotEmpty()
        )
    }

    @Test
    fun theSourceStrideIsMelNLenAndTheDestinationStrideIsNFramesOut() {
        // MUTANT 1: `mel.data[j*n_frames_out + i]`. Compiles, runs, returns 0, and produces a mel
        // that is structured noise - bins 0-39 read at wrong offsets, bins 40-79 never touched.
        // This needle is the whole defence: the SOURCE index must carry mel.n_len (6000), and only
        // mel.n_len.
        assertTrue(
            "the copy must read the source with stride mel.n_len - `mel.data[j*mel.n_len + i]`. " +
                "whisper_mel is bin-major with stride n_len, and for a 30s window n_len is 6000 " +
                "(log_mel_spectrogram appends 30s of zeros before framing), NOT the 3000 the NPU " +
                "encoder wants. Substituting the destination stride here is the C1 defect: it " +
                "reads bins 0-39 at wrong offsets and never touches bins 40-79, which is a wrong " +
                "transcript rather than an error. Q10a's row40/row79 line is the first place a " +
                "human would otherwise see it.",
            liveOffsets(exportBody, "mel.data[j*mel.n_len + i]").isNotEmpty()
        )
        assertTrue(
            "the copy must WRITE with stride n_frames_out - `out[j*n_frames_out + (i - i0)]`. The " +
                "two strides are different and that difference is the entire point of this " +
                "function; writing with mel.n_len would overrun a destination sized " +
                "n_mel*n_frames_out.",
            liveOffsets(exportBody, "out[j*n_frames_out + (i - i0)]").isNotEmpty()
        )
        assertTrue(
            "the copy bound must be min(mel_offset + n_frames_out, mel.n_len), mirroring the " +
                "in-tree extraction at src/whisper.cpp:2382-2402. The bound is the SOURCE length, " +
                "so at mel_offset 0 all 3000 destination columns are populated from real frames " +
                "0-2999 - frame 2999 is a genuine FFT frame and must not be padded away.",
            liveOffsets(exportBody, "std::min(mel_offset + n_frames_out, mel.n_len)").isNotEmpty()
        )
    }

    @Test
    fun theCopyLoopNeverBoundsAgainstNLenOrg() {
        // MUTANT 2: i1 = min(mel_offset + n_frames_out, mel.n_len_org). n_len_org is 2999 for a 30s
        // input (1 + (480000 + 200 - 400)/160), so this silently drops the last real frame and
        // leaves destination column 2999 zeroed - a small, permanent CPU/NPU divergence that no
        // test downstream of here would attribute to the mel.
        assertTrue(
            "the exported copy loop must not mention n_len_org on any live line. It is 2999 for a " +
                "30s window, it is NOT the bound to copy against (mel.n_len is), and using it " +
                "would zero destination column 2999 - which corresponds to a genuine FFT frame, " +
                "not padding. Found live occurrences at offsets " +
                liveOffsets(exportBody, "n_len_org"),
            liveOffsets(exportBody, "n_len_org").isEmpty()
        )
    }

    @Test
    fun theDestinationIsZeroFilledBeforeTheCopy() {
        assertTrue(
            "the export must memset the destination before copying, mirroring the in-tree " +
                "extraction (src/whisper.cpp:2390). At mel_offset 0 nothing is left unwritten, so " +
                "this is defensive - but it is what makes a non-zero mel_offset read as silence " +
                "rather than as whatever the caller's buffer happened to hold.",
            liveOffsets(exportBody, "memset(").isNotEmpty()
        )
    }

    @Test
    fun theJniAsksForThreeThousandFramesAndRefusesAnyBinCountButEighty() {
        val body = functionBody(
            jni,
            "Java_com_whispereverywhere_whisper_WhisperNative_pcmToMel(",
            "whisper_jni.cpp",
        )
        assertTrue(
            "pcmToMel must call whisper_get_mel_segment with the NPU frame count and offset 0 - " +
                "`whisper_get_mel_segment(ctx, out, kNpuMelFrames, 0)`. The encoder's " +
                "input_features tensor is ufixed16 [1,80,3000]; any other frame count is a shape " +
                "mismatch the runtime reports far from here.",
            liveOffsets(body, "whisper_get_mel_segment(ctx, out, kNpuMelFrames, 0)").isNotEmpty()
        )
        assertTrue(
            "kNpuMelFrames must be 3000 - the destination stride the assertion above passes.",
            liveOffsets(jni, "constexpr int   kNpuMelFrames  = 3000;").isNotEmpty()
        )
        assertTrue(
            "pcmToMel must refuse any model whose mel bin count is not 80 - " +
                "`whisper_model_n_mels(ctx) != kNpuMelBins`. There is no WHISPER_N_MEL macro in " +
                "this version to catch it at compile time, and a large-v3 model (128 bins) would " +
                "otherwise overrun the 80*3000*4 destination silently.",
            liveOffsets(body, "whisper_model_n_mels(ctx) != kNpuMelBins").isNotEmpty()
        )
        assertTrue(
            "kNpuMelBins must be 80.",
            liveOffsets(jni, "constexpr int   kNpuMelBins    = 80;").isNotEmpty()
        )
    }

    @Test
    fun theKotlinExternTakesFloatArrayNotPcm16() {
        val kt = source("src/main/java/com/whispereverywhere/whisper/WhisperNative.kt")
        // SCOPED TO THE DECLARATION LINE, and that is not a stylistic preference: `transcribeRaw`
        // also takes `samples: FloatArray`, so a whole-file needle for it is satisfied by a
        // NEIGHBOUR's signature. Verified by mutation - rewriting pcmToMel's parameter to
        // ShortArray left a whole-file assertion green, which is the "answered by unrelated code"
        // shape functionBody() above exists to prevent, reached through a different door.
        val decl = kt.lineSequence().firstOrNull { it.contains("external fun pcmToMel(") }
        assertTrue(
            "WhisperNative must declare `external fun pcmToMel(` on a single line - the whole " +
                "declaration is the scope every assertion below is answered by.",
            decl != null
        )
        assertTrue(
            "pcmToMel must take `samples: FloatArray`. The backend already holds float32 in " +
                "[-1,1]; routing it through ShortArray/PCM16 would be a lossy round trip for no " +
                "reason (C3), and whisper_pcm_to_mel takes const float * anyway. Declaration " +
                "found: ${decl?.trim()}",
            decl!!.contains("samples: FloatArray")
        )
        assertTrue(
            "pcmToMel must write into a direct `out: ByteBuffer` - the same buffer Q3's quantizer " +
                "reads as a FloatBuffer and Q6 hands to nativeEncode, with no intermediate copy. " +
                "Declaration found: ${decl.trim()}",
            decl.contains("out: ByteBuffer")
        )
        assertTrue(
            "pcmToMel must return Boolean: false means `out` holds nothing to trust, and the " +
                "caller must not quantise it. Declaration found: ${decl.trim()}",
            decl.contains("): Boolean")
        )
    }
}
