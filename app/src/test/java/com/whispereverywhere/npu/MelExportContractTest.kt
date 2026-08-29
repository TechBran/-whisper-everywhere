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

    /** The LIVE (non-comment) lines of [scope] containing [needle], trimmed — for failure text. */
    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").map { it.trimStart() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) &&
                line.contains(needle)
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
                "sourcePinnedInputs inputs.files(...) block. This test is the only reader of " +
                "that header, and a header-only edit that is not a declared test input leaves the " +
                "test task UP-TO-DATE - the guard then passes against stale evidence instead of " +
                "re-running. Same hazard the .cpp entries beside it already close.",
            liveOffsets(gradle, "\"src/main/cpp/whisper.cpp/include/whisper.h\"").isNotEmpty()
        )
        // 4.1 L3, folding Q2 M12. The header is the contract the fork PUBLISHES, and it is the one
        // artefact an upstream rebase forces someone to re-read; a return line narrower than the
        // guard is a promise the code does not keep. The runtime refusal names all three band
        // counts by field - mel.n_mel, hparams.n_mels, filters.n_mel - because those are three
        // different numbers from three different places and "the band counts disagree" does not
        // tell a caller which pair to look at.
        //
        // NOT live-scoped, deliberately, and this is the one place in this file where that is
        // correct: the subject IS a comment. Every line of the header's contract block starts with
        // `//`, so liveOffsets would return an empty list for the true state of the world.
        assertTrue(
            "include/whisper.h's return line for whisper_get_mel_segment must name all THREE band " +
                "counts the runtime refusal names - mel.n_mel, hparams.n_mels and filters.n_mel. " +
                "The guard compares mel.n_mel against BOTH of the others, so a contract that says " +
                "only \"the band counts disagree\" is narrower than the code and leaves the caller " +
                "guessing which of the two comparisons fired.",
            forkHdr.contains("hparams.n_mels") &&
                forkHdr.contains("filters.n_mel") &&
                forkHdr.contains("mel.n_mel")
        )
        // SCOPED to this function's own contract block, and the scope is mandatory: `whisper.h`
        // carries six "Returns 0 on success" lines, so an unscoped needle is answered by
        // whisper_encode's. The anchor is the fork's own block opener, which is unique.
        val blockAnchor = "// Whisper Everywhere fork (4.0 NPU tier). Reads the log mel spectrogram"
        assertTrue(
            "the fork's contract block for whisper_get_mel_segment must still open with its own " +
                "marker - without it the scope below silently rebases and the claim is answered " +
                "by an unrelated declaration",
            forkHdr.contains(blockAnchor)
        )
        val block = forkHdr.substringAfter(blockAnchor)
            .substringBefore("WHISPER_API int whisper_get_mel_segment(")
        val returns = block.substringAfter("Returns 0 on success")
        assertTrue(
            "…and it must name them IN the return line itself, not merely somewhere in the block: " +
                "the sizing-rule paragraph above already mentions two of the three, so a " +
                "block-wide needle would be answered by a neighbour. Found after \"Returns 0 on " +
                "success\": " + returns.trim(),
            returns.contains("hparams.n_mels") &&
                returns.contains("filters.n_mel") &&
                returns.contains("mel.n_mel")
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
    fun theExportRefusesAModelWhoseBandCountsDisagree() {
        // The destination is sized by the CALLER on whisper_model_n_mels(ctx) - hparams.n_mels -
        // while this loop writes mel.n_mel * n_frames_out, and mel.n_mel comes from filters.n_mel.
        // Two different fields, and nothing on a whisper_init_from_file* context compares them:
        // upstream's only check is an assert() in whisper_encode_internal, compiled out in release.
        // A model declaring hparams.n_mels = 80 over a 128-band filterbank therefore satisfies
        // pcmToMel's bin-count gate and then memsets 1,536,000 bytes into a 960,000-byte direct
        // ByteBuffer - a 576 KB out-of-bounds heap write, not a wrong transcript. Guarding only
        // against filters.n_mel (which this function did until the Q2 review) is near-tautological
        // on the pcm_to_mel path and catches none of it.
        assertTrue(
            "the export must compare the mel's band count against hparams.n_mels - the field " +
                "whisper_model_n_mels() reports and the caller sizes its destination from. " +
                "Without it the header's sizing contract is advisory rather than enforced, and a " +
                "self-contradicting model header becomes an out-of-bounds heap write.",
            liveOffsets(exportBody, "mel.n_mel != ctx->model.hparams.n_mels").isNotEmpty()
        )
        assertTrue(
            "the export must ALSO keep comparing against filters.n_mel, so both directions of a " +
                "header/filterbank skew are refused by the same guard.",
            liveOffsets(exportBody, "mel.n_mel != ctx->model.filters.n_mel").isNotEmpty()
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

    /**
     * `pcmToMel` takes the bin count and **checks it twice** (4.1 L3, folding Q2 M8).
     *
     * `kNpuMelBins = 80` was a file-scope constant, which is a property of `whisper-small` in
     * particular. `npu-turbo` is 128-bin, so the constant would refuse a perfectly correct donor
     * and the only available repair would be to widen or delete the check. The bin count is
     * therefore an argument, read off the caller's `NpuModelSpec`.
     *
     * **Two checks, and they must be two.** `whisper_model_n_mels(ctx) != melBins` catches the wrong
     * DONOR — an 80-bin ggml handed to a 128-bin tier's session. `GetDirectBufferCapacity(out) !=
     * melBins * kNpuMelFrames * 4` catches the wrong BUFFER — a destination sized from the other
     * tier's spec. They fail in different places for different reasons, and a single combined check
     * names the wrong one half the time, on a seam whose whole value is that its refusals say what
     * to fix.
     *
     * The capacity is pinned as the EXPRESSION rather than as a literal, which is the point of the
     * fold: 960,000 is right for exactly one tier, and a literal here would be a third home for a
     * number that already has two.
     *
     * `kNpuMelFrames` and `kNpuMelSamples` stay constants, because they do NOT vary: both families
     * use whisper's 30 s window, so 3000 frames and 480,000 samples are the same on either. That
     * was the one assumption the 4.0 review flagged as *"the turbo asset has a different window
     * shape"* — it does not, and the measured answer is pinned here rather than left as a comment.
     */
    @Test
    fun theJniAsksForThreeThousandFramesAndRefusesABinCountTheCallerDidNotAskFor() {
        val body = functionBody(
            jni,
            "Java_com_whispereverywhere_whisper_WhisperNative_pcmToMel(",
            "whisper_jni.cpp",
        )
        assertTrue(
            "pcmToMel must call whisper_get_mel_segment with the NPU frame count and offset 0 - " +
                "`whisper_get_mel_segment(ctx, out, kNpuMelFrames, 0)`. The encoder's " +
                "input_features tensor is ufixed16 [1,melBins,3000]; any other frame count is a " +
                "shape mismatch the runtime reports far from here.",
            liveOffsets(body, "whisper_get_mel_segment(ctx, out, kNpuMelFrames, 0)").isNotEmpty()
        )
        assertTrue(
            "kNpuMelFrames must be 3000 - the destination stride the assertion above passes, and " +
                "one of the two window constants that are genuinely the same on BOTH families.",
            liveOffsets(jni, "constexpr int   kNpuMelFrames  = 3000;").isNotEmpty()
        )
        assertTrue(
            "kNpuMelSamples must be 480000 - 30 s at 16 kHz, the window pcmToMel zero-pads or " +
                "truncates every segment to. Unpinned until now (Q2 M8), and it is what makes the " +
                "frame count above true: 480,000 samples at whisper's 160-sample hop IS 3000 " +
                "frames, so the two constants are one fact stated twice.",
            liveOffsets(jni, "constexpr int   kNpuMelSamples = 480000;").isNotEmpty()
        )
        assertTrue(
            "`kNpuMelBins` must be GONE from every live line. It is 80 - a property of " +
                "whisper-small - and left standing it would refuse npu-turbo's perfectly correct " +
                "128-bin donor, at which point the only repair anybody reaches for is deleting the " +
                "check. Found: " + liveLines(jni, "kNpuMelBins"),
            liveOffsets(jni, "kNpuMelBins").isEmpty()
        )
        assertTrue(
            "pcmToMel must refuse a donor whose band count is not the one the CALLER asked for - " +
                "`whisper_model_n_mels(ctx) != melBins`. This is the wrong-DONOR check: an 80-bin " +
                "ggml under a 128-bin tier would otherwise overrun a destination sized for 128.",
            liveOffsets(body, "whisper_model_n_mels(ctx) != melBins").isNotEmpty()
        )
        assertTrue(
            "…and it must refuse a destination whose capacity is not `melBins * kNpuMelFrames * 4` " +
                "- the wrong-BUFFER check, and a SEPARATE one. Two checks, not one: the first " +
                "catches the model, the second catches the allocation, and a single combined " +
                "condition names the wrong one half the time. Live capacity lines: " +
                liveLines(body, "GetDirectBufferCapacity"),
            liveOffsets(body, "melBins * kNpuMelFrames * 4").isNotEmpty() ||
                liveOffsets(body, "melBins * (jlong) kNpuMelFrames * 4").isNotEmpty() ||
                liveOffsets(body, "(jlong) melBins * kNpuMelFrames * 4").isNotEmpty()
        )
        assertTrue(
            "and the capacity must NOT be a literal 960000 anywhere in this function - that is " +
                "the 80-bin answer, and it is the shape of constant this whole task exists to " +
                "remove. Found: " + liveLines(body, "960000"),
            liveOffsets(body, "960000").isEmpty()
        )
        // THE DISTINCTNESS IS PINNED ON THE COMPLETE CONDITION, not on two offsets being unequal,
        // and the difference was measured rather than reasoned about. Battery row D2 merges the two
        // into `if (A ||\n B) {` — which spans TWO LINES, so an offset-inequality assertion is
        // satisfied by it and the row SURVIVES. Requiring each condition to CLOSE on its own line
        // (`) {`) is the claim that each is a complete refusal with its own message and its own
        // `return JNI_FALSE`, which is what "two checks, not one" actually means.
        val nMels = liveOffsets(body, "if (whisper_model_n_mels(ctx) != melBins) {")
        val capacity = liveOffsets(body, "if (env->GetDirectBufferCapacity(melBuf) != melBytes) {")
        assertTrue(
            "the donor check must be a COMPLETE `if` of its own — `if (whisper_model_n_mels(ctx) " +
                "!= melBins) {` — not one arm of a combined condition. Live lines: " +
                liveLines(body, "whisper_model_n_mels"),
            nMels.isNotEmpty()
        )
        assertTrue(
            "…and so must the capacity check. Merged into one `if` they compile, they refuse the " +
                "same inputs, and the message names whichever of the two the author wrote first — " +
                "which is the wrong one half the time, on the seam whose whole value is that its " +
                "refusals say what to fix. Live lines: " + liveLines(body, "GetDirectBufferCapacity"),
            capacity.isNotEmpty()
        )
        assertTrue(
            "ORDER: the donor check (${nMels.first()}) must precede the capacity check " +
                "(${capacity.first()}). The capacity is DERIVED from melBins, so a buffer refusal " +
                "taken first would report a size disagreement on a session whose real problem is " +
                "that the model has the wrong number of bands.",
            nMels.first() < capacity.first()
        )
    }

    @Test
    fun theMelPathLoadsTheFilterbankOnlyNeverTheWholeModel() {
        assertTrue(
            "the fork's include/whisper.h must declare whisper_init_from_file_mel_only. Without " +
                "it the only way to compute a mel is whisper_init_from_file, which holds a full " +
                "set of weights - 60-190 MB for the tiers this app ships - resident purely to " +
                "use an 80x201 filterbank.",
            liveOffsets(forkHdr, "whisper_init_from_file_mel_only(").isNotEmpty()
        )
        val body = functionBody(
            jni,
            "Java_com_whispereverywhere_whisper_WhisperNative_initMelOnly(",
            "whisper_jni.cpp",
        )
        assertTrue(
            "initMelOnly must call whisper_init_from_file_mel_only.",
            liveOffsets(body, "whisper_init_from_file_mel_only(").isNotEmpty()
        )
        // THE ANTI-REINTRODUCTION ASSERTION, and the residency of the whole NPU tier rests on it.
        // Swapping this one call for whisper_init_from_file_with_params compiles, runs, returns a
        // handle that pcmToMel accepts, and produces a byte-identical mel - while silently putting
        // ~190 MB of CPU weights beside the NPU's own ~376 MiB, on the exact path whose design
        // (I11) is that the two are never co-resident. Nothing downstream would report it; the
        // first symptom would be an LMK kill on a mid-range device.
        assertTrue(
            "the mel path must NEVER take a full-weight load. whisper_init_from_file / " +
                "whisper_init_from_file_with_params inside initMelOnly would hold the entire " +
                "model resident to reach a 64 KB filterbank, which is the one thing this entry " +
                "point exists to avoid, and it would do so invisibly - the mel would be correct. " +
                "Found: " + liveOffsets(body, "whisper_init_from_file_with_params"),
            liveOffsets(body, "whisper_init_from_file_with_params").isEmpty()
        )
        val kt = source("src/main/java/com/whispereverywhere/whisper/WhisperNative.kt")
        assertTrue(
            "WhisperNative must declare `external fun initMelOnly(`.",
            liveOffsets(kt, "external fun initMelOnly(").isNotEmpty()
        )
    }

    @Test
    fun theMelOnlyLoaderRefusesAFileItCannotVouchFor() {
        val load = functionBody(
            forkSrc,
            "static bool whisper_model_load_mel_only(",
            "the whisper.cpp fork's src/whisper.cpp",
        )
        assertTrue(
            "the mel-only loader must reject a bad magic - it is the only thing standing between " +
                "an arbitrary file and 64 KB of it being read as float32 filter coefficients.",
            liveOffsets(load, "GGML_FILE_MAGIC").isNotEmpty()
        )
        assertTrue(
            "the mel-only loader must bound the filterbank dimensions. The FULL loader can trust " +
                "them because a corrupt header trips over the tensor pass moments later; a " +
                "mel-only load has no tensor pass, so a garbage n_mel*n_fft would be allocated " +
                "before anything else noticed.",
            liveOffsets(load, "filters.n_mel > 1024").isNotEmpty()
        )
        assertTrue(
            "the mel-only loader must check hparams.n_mels against filters.n_mel. Nothing in the " +
                "full loader compares them, because nothing in the full path depends on them " +
                "agreeing - but a mel-only context is handed to exactly the two functions that " +
                "read one each (whisper_model_n_mels gates the caller, log_mel_spectrogram " +
                "indexes with the other), so a disagreement becomes a wrong mel with nothing to " +
                "attribute it to.",
            liveOffsets(load, "hparams.n_mels != filters.n_mel").isNotEmpty()
        )
        val init = functionBody(
            forkSrc,
            "struct whisper_context * whisper_init_from_file_mel_only(",
            "the whisper.cpp fork's src/whisper.cpp",
        )
        assertTrue(
            "whisper_init_from_file_mel_only must detect a file that ended early. The loader's " +
                "read callback returns read_size without ever consulting gcount, so a truncated " +
                "file yields a filterbank of zeros and, later, a mel of pure silence - a wrong " +
                "transcript with no error anywhere. The stream state is the only witness left.",
            liveOffsets(init, "loaded && !fin").isNotEmpty()
        )
        assertTrue(
            "whisper_init_from_file_mel_only must zero the state's batch. whisper_batch has no " +
                "default member initialisers, so its five pointers are indeterminate in a fresh " +
                "state and whisper_free_state's whisper_batch_free would free() them. This one " +
                "line is what lets a mel-only context be torn down by the ordinary whisper_free.",
            liveOffsets(init, "state->batch = {};").isNotEmpty()
        )
        // 4.1 L3, folding Q2 M2 — the same class of defect as `state->batch = {}` beside it, and
        // it lands in THIS task because the melbank asset is a new caller of this loader.
        //
        // `whisper_context::params` is a plain C struct with no default member initialisers, so on
        // a context this function allocates it holds whatever the allocation held. Nothing on the
        // mel path reads it today - which is precisely the shape of assumption this branch has
        // been defeated by twice ("nobody calls that"). `whisper_init_state(ctx)` DOES read it,
        // and a mel-only context is an ordinary `whisper_context` that any future code may hand to
        // any whisper API. One line makes it inert-safe by construction instead.
        assertTrue(
            "whisper_init_from_file_mel_only must set ctx->params from " +
                "whisper_context_default_params(). The struct has no default member initialisers " +
                "and whisper_init_state reads it; leaving it indeterminate makes the mel-only " +
                "context safe only by the property that nobody calls that yet.",
            liveOffsets(init, "ctx->params = whisper_context_default_params();").isNotEmpty()
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
        // LIVE-SCOPED (4.1 L3, folding Q2 M7). This used to be a raw `lineSequence().firstOrNull`,
        // so a commented-out declaration answered every claim below exactly as happily as the real
        // one - and the file's own KDoc legitimately discusses this signature at length. It is one
        // word, and it is the exact hole class that let M8 survive its first battery pass: an
        // assertion answered by something other than the thing it is about.
        val decl = liveLines(kt, "external fun pcmToMel(").firstOrNull()
        assertTrue(
            "WhisperNative must declare `external fun pcmToMel(` on a single LIVE line - the " +
                "whole declaration is the scope every assertion below is answered by.",
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
        assertTrue(
            "and it must take `melBins: Int` (4.1 L3). The bin count was a native constant - 80, " +
                "i.e. whisper-small's - and a 128-bin tier makes it wrong in the direction that " +
                "does not throw: an 80-bin mel written into a 128-bin buffer fills the first " +
                "240,000 of 384,000 floats and the encoder transcribes the remainder as whatever " +
                "the buffer held. The count now travels with the call, off the caller's " +
                "NpuModelSpec. Declaration found: ${decl.trim()}",
            decl.contains("melBins: Int")
        )
    }
}
