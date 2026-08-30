package com.whispereverywhere.npu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The NPU tier's diagnostic lines: the exact rendered text, and the fact that the backend emits one
 * of them per segment.
 *
 * **Format AND emission, because either alone is decoration** (the F-rule). A format test with no
 * emission pin passes while nothing calls it; an emission pin with no format test passes while the
 * line says something else. Neither can be observed at runtime from a JVM test — `android.util.Log`
 * is stubbed to a no-op by `unitTests.isReturnDefaultValues = true` — so the format is asserted on
 * the pure builder and the emission is asserted on the call site's SOURCE TEXT.
 *
 * The owner has no adb. These lines are the entire observable surface of a tier whose first
 * execution is Q10a.
 */
class NpuDiagTest {

    /** The `npu` tier's shape — the numbers these lines used to compile in (4.1 L2). */
    private val spec = NpuModelSpec.SMALL

    /**
     * [NpuTierStatus] is a process singleton and the card tests below publish into it (Q8 M5,
     * landed with L8's per-tier re-spec): without this reset a published reason leaks into every
     * later test in the same JVM — including `NpuTierStatusTest`'s independence claims.
     */
    @After
    fun resetTierStatus() {
        NpuTierStatus.declinedTiers.forEach { NpuTierStatus.publish(it, null) }
    }

    @Test
    fun lineMatchesTheGreppableFormatExactly() {
        assertEquals(
            "npu: encode=405 decode=168 tokens=37 lang=en",
            NpuDiag.line(encodeMs = 405L, decodeMs = 168L, tokens = 37, langNote = "en"),
        )
        assertEquals(
            "zero tokens is a real reading — EOT first, i.e. silence — and must render as a " +
                "normal line rather than being suppressed as \"nothing happened\"",
            "npu: encode=402 decode=5 tokens=0 lang=en",
            NpuDiag.line(encodeMs = 402L, decodeMs = 5L, tokens = 0, langNote = "en"),
        )
    }

    /**
     * One rendered line per row of the language truth table, so the four notes are pinned where a
     * reader of the LOG sees them and not only where the policy decides them.
     *
     * The `(fallback)` row is the one that matters: it is the only path that reaches English
     * without the user or the model having said English, and the parenthesis is the only thing
     * that distinguishes it in a log from a user who chose `en` on purpose.
     */
    @Test
    fun theLineRendersEachOfTheFourLanguageNotes() {
        assertEquals(
            "explicit selection renders as the bare code",
            "npu: encode=405 decode=168 tokens=37 lang=es",
            NpuDiag.line(405L, 168L, 37, "es"),
        )
        assertEquals(
            "a successful detection names the language AND says it was detected",
            "npu: encode=405 decode=168 tokens=37 lang=auto->fr(detected)",
            NpuDiag.line(405L, 168L, 37, "auto->fr(detected)"),
        )
        assertEquals(
            "a locale fallback says so, so a user transcribed by their phone's locale can see it",
            "npu: encode=405 decode=168 tokens=37 lang=auto->de(locale)",
            NpuDiag.line(405L, 168L, 37, "auto->de(locale)"),
        )
        assertEquals(
            "and the English fallback is never silent — this line IS the \"why\"",
            "npu: encode=405 decode=168 tokens=37 lang=auto->en(fallback)",
            NpuDiag.line(405L, 168L, 37, "auto->en(fallback)"),
        )
    }

    /** The stage-failure line: one word for the stage, native's own text for the detail. */
    @Test
    fun theUnavailableLineNamesTheStageAndCarriesNativesDetailVerbatim() {
        assertEquals(
            "npu: unavailable stage=encode detail=encode: graphExecute failed at position 0",
            NpuDiag.unavailable("encode", "encode: graphExecute failed at position 0"),
        )
        assertEquals(
            "the mel donor's absence is a stage like any other — it is the tier declining before " +
                "358 MB of NPU assets have been touched, which is the cheapest possible refusal",
            "npu: unavailable stage=mel-donor detail=no 80-bin model installed",
            NpuDiag.unavailable("mel-donor", "no 80-bin model installed"),
        )
    }

    // ---------------------------------------------------------------- source-anchored pins

    /**
     * Reads a repo file from the test's working directory — the locator `NpuNativeContractTest`,
     * `SegmentTimingTest` and `NativeVadSourceContractTest` share. Line endings normalised at this
     * single read site (`readText()` does not normalise, and a CRLF checkout silently defeats
     * anything anchored on a newline).
     */
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

    /** How many LIVE (non-comment) lines of [scope] contain [needle]. */
    private fun liveLineCount(scope: String, needle: String): Int =
        scope.lineSequence().count { line ->
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            !commented && line.contains(needle)
        }

    /**
     * `npu: encode=` must be ONE contiguous literal in the source.
     *
     * Splicing it — `"npu: " + "encode="`, a `PREFIX` constant, a `buildString` — changes nothing
     * the compiler or a reviewer can see, and breaks every grep and every parser written against
     * the shipped format. The whole value of this line is that a support reply can say "search your
     * log for `npu: encode=`".
     */
    @Test
    fun thePrefixIsASingleContiguousLiteralInSource() {
        val diag = source("src/main/java/com/whispereverywhere/npu/NpuDiag.kt")
        assertEquals(
            "NpuDiag.kt must build `npu: encode=` as one contiguous literal on exactly one live " +
                "line — the format string itself. Found " +
                "${liveLineCount(diag, "\"npu: encode=")} live lines opening a literal with it.",
            1,
            liveLineCount(diag, "\"npu: encode="),
        )
        assertTrue(
            "and the whole field sequence must be in that one literal, in order: a reordering is " +
                "invisible to every test that only checks the prefix",
            diag.contains("\"npu: encode=\$encodeMs decode=\$decodeMs tokens=\$tokens lang=\$langNote\""),
        )
    }

    /**
     * EMISSION. The backend must call [NpuDiag.line] exactly once — one line per segment.
     *
     * Zero call sites is a tier that reports nothing about itself on a device with no adb attached.
     * Two is a per-segment line that double-reports, which is worse than none: it makes the encode
     * and decode figures look like they came from twice as many segments as actually ran, and Q10a
     * reads those figures as the measurement that decides the tier.
     *
     * Source-anchored because it cannot be otherwise: `NpuWhisperBackend` touches `QnnAsrNative`,
     * whose `init` block runs `System.loadLibrary("qnnasr")`, so naming the class from a JVM test
     * kills the test outright.
     */
    @Test
    fun theBackendEmitsTheSegmentLineExactlyOncePerSegment() {
        val backend =
            source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
        assertEquals(
            "NpuWhisperBackend must call NpuDiag.line exactly once on a live line. Zero means a " +
                "tier that says nothing about itself to an owner with no adb; two means every " +
                "segment is reported twice and Q10a's timings are read off a doubled population.",
            1,
            liveLineCount(backend, "NpuDiag.line("),
        )
        assertEquals(
            "and the stage-failure line must have exactly one emitter too — it lives in " +
                "fallBackToCpuTier, which is the single funnel every declining stage goes through",
            1,
            liveLineCount(backend, "NpuDiag.unavailable("),
        )
        assertEquals(
            "the segment line must carry nativeDecodeSegment's RETURNED count, never the decoded " +
                "string's length. `written` is that count; reading it off the text would be one " +
                "step from logging the text, which this tier never does.",
            1,
            liveLineCount(backend, "NpuDiag.line(encodeMs, decodeMs, written, resolution.note)"),
        )
    }

    // ---------------------------------------- 4.0 Q9 fix round (I2): the mel stride bisector

    /**
     * THE `mel:` LINE — format and emission, the two halves the F-rule always guards separately.
     *
     * **It was promised by three ledger entries and did not exist.** Until this fix `mel: bins=`
     * occurred exactly twice in the repository: a comment in `whisper_jni.cpp` and a KDoc in
     * `MelExportContractTest`. Nothing emitted it, and the Q10a run-book told the owner to grep for
     * it. That is the failure this test exists to make impossible to repeat.
     *
     * **What it detects.** whisper's internal mel is bin-major with stride `mel.n_len` — 6000 for a
     * 30 s window, because `log_mel_spectrogram` appends 30 s of zeros before framing — while the
     * destination stride is 3000. A flat copy reads bins 0-39 at wrong offsets and never touches
     * the upper half of the bins. Nothing downstream can see it: the encoder accepts structured
     * noise and transcribes it fluently into different words. `rowMid == rowLast` is the whole
     * diagnosis.
     *
     * The fields are `rowMid`/`rowLast` and `bins` is a parameter since 4.1 L2, because the rows
     * are `0`, `melBins/2` and `melBins-1`: on this tier they are still 40 and 79 and every number
     * below is unchanged, but a fixed `row79` names a row that does not exist on a 128-bin asset.
     */
    @Test
    fun theMelLineIsOneLiteralAndItsRowsAreTheStrideBisector() {
        val diag = source("src/main/java/com/whispereverywhere/npu/NpuDiag.kt")
        assertEquals(
            "mel: bins=80 frames=3000 row0=1234.568 rowMid=0.000 rowLast=0.000",
            NpuDiag.mel(spec.melBins, 1234.5678, 0.0, 0.0),
        )
        assertEquals(
            "negatives are ordinary here — a log-mel is mostly negative, and a formatter that " +
                "mangled the sign would make every row look alike",
            "mel: bins=80 frames=3000 row0=-91234.500 rowMid=-88.250 rowLast=-0.001",
            NpuDiag.mel(spec.melBins, -91234.5, -88.25, -0.001),
        )
        assertEquals(
            "the bin count is the SPEC's, printed rather than assumed — this is the line a model " +
                "lab reads to tell which asset produced a spectrogram, and `frames` stays fixed " +
                "because 3000 is universal across every published whisper asset",
            "mel: bins=128 frames=3000 row0=1.000 rowMid=2.000 rowLast=3.000",
            NpuDiag.mel(128, 1.0, 2.0, 3.0),
        )
        assertEquals(
            "the `mel: bins=` prefix is ONE contiguous literal on one live line. Assembling it " +
                "produces identical output and breaks every grep written against the format.",
            1,
            liveLineCount(diag, "\"mel: bins="),
        )
        assertTrue(
            "and the whole field sequence lives in that one literal, in order — row0, rowMid, rowLast",
            diag.contains("\"mel: bins=%d frames=%d row0=%.3f rowMid=%.3f rowLast=%.3f\""),
        )
        // Locale.US, not the default — ASKED AS A BEHAVIOUR, not counted as a source line.
        //
        // This used to be `liveLineCount(diag, "java.util.Locale.US,") == 1`, and it broke the
        // moment a second builder in this file needed the same formatter: a count over a whole file
        // is a claim about the file's population, not about this line's locale, and its only
        // possible repairs are to bump the number (weaker every time) or to keep the file at one
        // formatter forever. The 7a73ea0 lesson, arriving a second time. Asked directly instead —
        // render it under a comma-decimal default and require a dot — the assertion cannot drift,
        // and it fails for exactly the reason it names.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals(
                "on a comma-decimal device `%.3f` yields `row0=1234,567`, which breaks every " +
                    "parser and is *almost* readable — the kind of defect that survives review",
                "mel: bins=80 frames=3000 row0=1234.568 rowMid=0.000 rowLast=0.000",
                NpuDiag.mel(spec.melBins, 1234.5678, 0.0, 0.0),
            )
        } finally {
            java.util.Locale.setDefault(previous)
        }

        // EMISSION, and its POSITION, which is the invariant on both sides.
        val backend =
            source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
        assertEquals(
            "the backend emits the mel line exactly once per segment",
            1,
            liveLineCount(backend, "NpuDiag.mel("),
        )
        assertEquals(
            "the last row is taken from `spec.melBins - 1`, not a bare 79: the last row IS the claim, and a " +
                "future 128-bin tier would leave a literal 79 measuring the middle of the band",
            1,
            liveLineCount(backend, "NpuQuantize.melRowSum(spec, melView, spec.melBins - 1),"),
        )
        assertEquals(
            "the view is taken fresh and read absolutely, so the shared direct buffer handed on to " +
                "melToU16 and then nativeEncode keeps its position untouched",
            1,
            liveLineCount(backend, "val melView = mel.asFloatBuffer()"),
        )
        // RE-SPELLED at 4.1 L3, which is where this needle went red BY CONSTRUCTION: pcmToMel
        // gained a `melBins` argument, so the three-argument call this used to name no longer
        // exists. The claim is unchanged and is about the ORDER, not the arity — re-spell it with
        // whatever the call currently is and keep the `if (!` prefix, because the guard is the
        // subject. THE NEXT TRIGGER, named so nobody has to rediscover it: any further parameter
        // on pcmToMel (a window length, an offset) breaks this line again in exactly the same way.
        val pcmToMel =
            offsetOfLive(backend, "if (!WhisperNative.pcmToMel(melCtx, samples, mel, spec.melBins)) {")
        val melLine = offsetOfLive(backend, "NpuDiag.mel(")
        val quantise = offsetOfLive(backend, "NpuQuantize.melToU16(")
        assertTrue(
            "the mel line ($melLine) must be emitted AFTER pcmToMel's success guard ($pcmToMel). " +
                "The mel buffer is reused across segments, so a line above that guard prints the " +
                "PREVIOUS segment's spectrogram and attributes it to one whose mel never ran.",
            pcmToMel in 0 until melLine,
        )
        assertTrue(
            "and BEFORE melToU16 ($quantise): a bisector that cannot separate the spectrogram from " +
                "the quantisation is not a bisector",
            melLine < quantise,
        )
    }

    /**
     * Q10a-D2's `melprobe` line: the Kotlin half of the encoder read.
     *
     * **This line only has value as one half of a comparison.** Native prints the same two sums from
     * the `uint16` block the DSP is bound to and the same three cells dequantised back to floats;
     * these are computed from the float mel by an independent route. So the format is pinned exactly
     * — a field that silently renamed itself, or a cell index that drifted away from the one native
     * dequantises, turns a decisive comparison into two numbers that look comparable and are not.
     *
     * The cell indices are DERIVED here from the same constants native derives them from
     * (`frames/2` and `frames*(bins/2) + frames/2`), so a future 128-bin tier moves both sides
     * together rather than silently comparing different cells.
     */
    @Test
    fun theMelProbeLineIsOneLiteralAndItsCellsAreTheOnesNativeDequantises() {
        val diag = source("src/main/java/com/whispereverywhere/npu/NpuDiag.kt")
        assertEquals(
            "npu-debug: melprobe qRow0=98304000 qCol0=2621440 cell[0]=-1.500000 " +
                "cell[1500]=0.000000 cell[121500]=1.234568 scale=4.67700702e-05 zp=32072",
            NpuDiag.melProbe(
                spec = spec,
                qRow0 = 98_304_000L,
                qCol0 = 2_621_440L,
                cells = floatArrayOf(-1.5f, 0.0f, 1.2345678f),
                scale = 4.677007018472068e-05f,
                zeroPoint = 32072,
            ),
        )
        assertEquals(
            "the `npu-debug: melprobe ` prefix is ONE contiguous literal — the whole D2 read is " +
                "found by one grep, and half a format assembled from parts breaks it invisibly",
            1,
            liveLineCount(diag, "\"npu-debug: melprobe qRow0="),
        )
        assertTrue(
            "the cell indices must be DERIVED from the SPEC's melFrames/melBins, never written as " +
                "1500 and 121500. Native derives its three cells the same way, and a literal here " +
                "would keep pointing at the old cells the day the geometry changes — while still " +
                "printing a number beside native's, ready to be compared. Since 4.1 L2 the day the " +
                "geometry changes is a second TIER rather than a re-export, so the same literal " +
                "would be wrong for one of two assets in the same build.",
            diag.contains("spec.melFrames / 2") &&
                diag.contains("spec.melFrames * (spec.melBins / 2) + i1"),
        )
        assertTrue(
            "and the line answers per spec: a 128-bin tier's middle cell is at a different index, " +
                "which is the whole reason the indices are printed rather than assumed",
            NpuDiag.melProbe(
                NpuModelSpec.SMALL.copy(melBins = 128),
                1L, 2L, floatArrayOf(0f, 0f, 0f), 1.0f, 0,
            ).contains("cell[193500]="),
        )
        // Locale.US, asked as a behaviour for the reason the mel line's own assertion now states.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertTrue(
                "under a comma-decimal default the cells must still render with a dot, or the " +
                    "float this line exists to be compared with native's is unparseable",
                NpuDiag.melProbe(spec, 1L, 2L, floatArrayOf(-1.5f, 0f, 1.25f), 1.0f, 0)
                    .contains("cell[0]=-1.500000"),
            )
        } finally {
            java.util.Locale.setDefault(previous)
        }

        // Exactly three cells, and the builder says so rather than formatting whatever it is given:
        // a four-cell caller would otherwise render a line that reads as if it were comparable.
        listOf(floatArrayOf(), floatArrayOf(1f, 2f), floatArrayOf(1f, 2f, 3f, 4f)).forEach { bad ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                NpuDiag.melProbe(spec, 0L, 0L, bad, 1.0f, 0)
            }
        }

        // EMISSION, and its gate.
        val backend =
            source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
        assertEquals(
            "the backend emits the melprobe line exactly once per segment",
            1,
            liveLineCount(backend, "NpuDiag.melProbe("),
        )
        val quantise = offsetOfLive(backend, "NpuQuantize.melToU16(")
        val probe = offsetOfLive(backend, "NpuDiag.melProbe(")
        val encode = offsetOfLive(backend, "QnnAsrNative.nativeEncode(quantised)")
        assertTrue(
            "the melprobe line ($probe) must be emitted BEFORE nativeEncode ($encode), so the two " +
                "halves of the comparison land adjacent in one capture instead of straddling a " +
                "405 ms encode and everything else that logs during it",
            probe in (quantise + 1) until encode,
        )
    }

    /** First LIVE offset of [needle] in [scope], or -1. Comments never satisfy an ordering claim. */
    private fun offsetOfLive(scope: String, needle: String): Int {
        var at = 0
        for (line in scope.split("\n")) {
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            if (!commented && line.contains(needle)) return at
            at += line.length + 1
        }
        return -1
    }

    // ---------------------------------------- 4.0 Q7b fix round (I3): the offer line

    @Test
    fun theOfferLineNamesEveryPredicateSeparatelyAndDistinguishesSkippedFromFailed() {
        // The gate composes three predicates into one answer, so this line exists to un-collapse
        // it. The run-book's first read. (4.1 L5: `installed`/`offered` carry TIER IDS — two
        // gated tiers can be independently installed, and a Boolean cannot say which.)
        // The 4.0 line's first case read `probe=pass installed=false` — a state the flipped gate
        // can no longer produce, because the probe only runs once something is installed. Its
        // honest respelling is the skipped/none line probeSkippedSeparatesItsTwoCauses... pins.
        assertEquals(
            "npu: offer soc=SM8650:pass probe=pass installed=npu offered=npu",
            NpuDiag.offer("SM8650", socSupported = true, capable = true, installedTierIds = setOf("npu")),
        )
        // The right silicon, but the QNN stack did not load — an ADSP_LIBRARY_PATH / libqnnasr.so
        // question, and a completely different next action from the two below.
        assertEquals(
            "npu: offer soc=SM8650-AC:pass probe=fail installed=npu offered=none",
            NpuDiag.offer("SM8650-AC", socSupported = true, capable = false, installedTierIds = setOf("npu")),
        )
        // SKIPPED, not failed. The SoC table is checked first precisely so a non-Qualcomm device
        // never dlopens a Qualcomm backend, so on these devices the probe genuinely did not run —
        // reporting `fail` would invent a measurement nobody took.
        assertEquals(
            "npu: offer soc=Tensor G3:fail probe=skipped installed=npu offered=none",
            NpuDiag.offer("Tensor G3", socSupported = false, capable = false, installedTierIds = setOf("npu")),
        )
        // Below API 31 the caller hands us null by design (NpuGate's null -> deny). The line must
        // still be readable rather than printing "null".
        assertEquals(
            "npu: offer soc=unknown:fail probe=skipped installed=none offered=none",
            NpuDiag.offer(null, socSupported = false, capable = null, installedTierIds = emptySet()),
        )
    }

    @Test
    fun theOfferLineListsEveryInstalledGatedTierByIdSorted() {
        // 4.1 L5: the gate's Boolean became a set, and the line follows — a "the card never
        // showed" report must say WHICH pair is on disk now that two can be. Sorted, so the line
        // is ONE greppable spelling rather than one per set-iteration order; the input here is
        // deliberately in REVERSED insertion order to pin that promise.
        assertEquals(
            "npu: offer soc=SM8650:pass probe=pass installed=npu,npu-turbo offered=npu,npu-turbo",
            NpuDiag.offer(
                "SM8650",
                socSupported = true,
                capable = true,
                installedTierIds = linkedSetOf("npu-turbo", "npu"),
            ),
        )
        assertEquals(
            "npu: offer soc=SM8650:pass probe=pass installed=npu-turbo offered=npu-turbo",
            NpuDiag.offer("SM8650", socSupported = true, capable = true, installedTierIds = setOf("npu-turbo")),
        )
    }

    @Test
    fun probeSkippedSeparatesItsTwoCausesByTheFieldsBesideIt() {
        // The conjunction flipped in L5: with no gated pair on disk the gate returns BEFORE the
        // dlopen, so the probe genuinely did not run — `capable = null` — and reporting `fail`
        // would invent a measurement nobody took, the same rule the SoC skip has always followed.
        // The three "card never showed" causes stay separable:
        //   wrong SoC            -> soc=…:fail probe=skipped
        //   nothing installed    -> soc=…:pass probe=skipped installed=none
        //   stack did not load   -> soc=…:pass probe=fail    installed=<ids>
        assertEquals(
            "npu: offer soc=SM8650:pass probe=skipped installed=none offered=none",
            NpuDiag.offer("SM8650", socSupported = true, capable = null, installedTierIds = emptySet()),
        )
        assertEquals(
            "npu: offer soc=Tensor G3:fail probe=skipped installed=none offered=none",
            NpuDiag.offer("Tensor G3", socSupported = false, capable = null, installedTierIds = emptySet()),
        )
        assertEquals(
            "npu: offer soc=SM8650:pass probe=fail installed=npu,npu-turbo offered=none",
            NpuDiag.offer(
                "SM8650",
                socSupported = true,
                capable = false,
                installedTierIds = setOf("npu", "npu-turbo"),
            ),
        )
    }

    @Test
    fun theOfferLineIsGreppableWithTheOtherTwoAndCarriesNoTranscriptContent() {
        val line = NpuDiag.offer(
            "SM8650",
            socSupported = true,
            capable = true,
            installedTierIds = setOf("npu", "npu-turbo"),
        )
        assertTrue("the offer line must share the tier's `npu: ` prefix", line.startsWith("npu: "))
        assertTrue("one line, never two", !line.contains("\n"))
        // Same shape as the other two: a word, then k=v pairs a parser can split on — even with
        // the two-tier set in both values.
        listOf("soc=", "probe=", "installed=", "offered=").forEach {
            assertEquals("the offer line states `$it` exactly once", 1, line.split(it).size - 1)
        }
    }

    @Test
    fun theOfferLineIsEmittedOncePerInstallEpochAtTheGatesFirstEvaluation() {
        // Format and emission, both guarded — either alone is decoration (this class's KDoc). The
        // emitter is `WhisperEverywhereApp`, which no JVM test can construct, so the call is
        // pinned as source.
        //
        // THE ONE-SHOT WAS DELIBERATELY RE-SPECIFIED AT 4.1 L8 (L5 review I1): the 4.0 latch was
        // a plain AtomicBoolean, once per PROCESS — and the flipped gate spends that first
        // evaluation in its least informative state (`probe=skipped installed=none` at bubble
        // start on a fresh device), after which a mid-process import + probe FAILURE logged
        // nowhere until restart. The latch is now the ModelInstallSignal GENERATION the line was
        // last emitted at: one line per install epoch, re-armed by exactly the event that changes
        // the line's truth, still never one per chooser open — so the landmark stays a landmark
        // and stays TRUE in the first-import process, which is the process the L8 device session
        // actually runs.
        val app = source("src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt")
        // Q7b NEW-5, resolved in 4.1 L5: the Log.i / TAG / one-shot pins are scoped to the GATE'S
        // OWN BODY, because the whole-file counts failed for reasons unrelated to their invariant
        // the moment any other Log.i joined this file — and an over-broad pin trains people to
        // weaken pins. The body closer is the first line at the declaration's own indentation,
        // which no nested block in this function can produce.
        val gate = app
            .substringAfter("fun offeredNpuTierIds(): Set<String> {")
            .substringBefore("\n    }")
        assertTrue(
            "the offer gate body was found and is smaller than the file",
            gate.isNotEmpty() && gate.length < app.length,
        )
        assertEquals(
            "the offer line has exactly one emitter in the whole file",
            1,
            liveLineCount(app, "NpuDiag.offer("),
        )
        assertEquals(
            "and that emitter is inside the gate itself",
            1,
            liveLineCount(gate, "NpuDiag.offer("),
        )
        assertEquals(
            "the emitter is behind the generation compare-and-swap, so it runs once per install " +
                "epoch and not once per chooser open — getAndSet keeps concurrent evaluations of " +
                "the same generation to one line",
            1,
            liveLineCount(gate, "if (npuOfferLoggedGeneration.getAndSet(generation) != generation) {"),
        )
        assertEquals(
            "and the epoch it keys on is the install signal's OWN generation — the one value " +
                "that moves exactly when the line's installed= snapshot goes stale. (The adb-push " +
                "dev route does not bump it; the run-book states the restart rule there.)",
            1,
            liveLineCount(gate, "val generation = ModelInstallSignal.generation.value"),
        )
        // Battery row V10, a measured survivor: diverting the line to a private tag
        // (`Log.i("NpuOffer", …)`) leaves the format correct, the emission once-per-process and
        // every other assertion in this class green — while making the line invisible to the ONE
        // grep the Q10a run-book tells an owner with no adb to run. The tag is the whole
        // distribution mechanism, so it is pinned as the symbol and not merely as "some tag".
        assertEquals(
            "the line goes to the house tag BY NAME, so one grep finds every tier line",
            1,
            liveLineCount(gate, "NpuDiag.TAG,"),
        )
        assertEquals(
            "and it is emitted at Log.i — a diagnostic the owner is asked to read must not sit " +
                "below the default logcat filter",
            1,
            liveLineCount(gate, "Log.i("),
        )
    }

    // ------------------------------------------------------------------ the card half (4.0, Q8)

    /**
     * `NpuDiag.unavailable` is the logcat half of a decline; [NpuTierStatus] is the CARD half of
     * the same fact, and Q8 is where that fact acquires a reader at all. Same subject, same class:
     * a tier that told logcat one thing and the user another would be the failure both exist to
     * prevent, wearing a disguise.
     */
    @Test
    fun theUnavailableCardStatesTheSameDeclineTheLogLineDoes() {
        val stage = "init"
        val detail = "init: nativeInit failed at 0"
        val logLine = NpuDiag.unavailable(stage, detail)
        NpuTierStatus.publish("npu", "$stage: $detail")

        assertEquals(
            "the card reads what the backend published — under the tier's OWN id (per-tier " +
                "since 4.1 L8)",
            "$stage: $detail",
            NpuTierStatus.reasonFor("npu"),
        )
        assertEquals(
            "and it recovers the same STAGE word the log line carries — the part a screenshot can " +
                "usefully report",
            stage,
            NpuTierStatus.stageOf(NpuTierStatus.reasonFor("npu")),
        )
        assertTrue("which is the word the log line leads with too: $logLine", logLine.contains("stage=$stage"))

        val note = NpuTierStatus.cardNote(NpuTierStatus.reasonFor("npu"))!!
        assertTrue("the note names the stage: $note", note.contains(stage))
        assertTrue(
            "AND it says what is running instead. A card that only says \"unavailable\" leaves " +
                "the user believing speech is broken, and one that says nothing at all is the " +
                "silent fallback this tier is forbidden to have: $note",
            note.contains("CPU model"),
        )
        assertTrue(
            "and it does not claim accuracy was lost, because it was not — the fallback is the " +
                "same whisper weights on a different processor: $note",
            note.contains("Accuracy is unchanged"),
        )

        // THE LIFETIME THE NOTE CLAIMS (final review F3 / I4) — a MEASURED survivor of the F-round
        // battery, which is worth stating: F3's entire subject is the truth of this sentence, and
        // nothing asserted it, so reverting the copy was green.
        //
        // The decline is PROCESS state, not session state: the reason feeds routesToNpu's
        // `declinedThisSession`, so the backend is never constructed again, so `load()` — the only
        // writer of null — never runs again. The card therefore rendered "for this session" on
        // every later visit to the chooser, including sessions deliberately run on another tier.
        assertTrue(
            "the note must NOT scope itself to a session: the decline outlives every session and " +
                "there is no in-app route back. Saying \"for this session\" is the fifth truth-of-" +
                "the-note defect this branch would have paid for: $note",
            !note.contains("for this session"),
        )
        assertTrue(
            "and it must name the way back, because there is exactly one and it is not obvious — " +
                "a note that states an indefinite condition without its remedy is a dead end: $note",
            note.contains("Restart the app"),
        )
    }

    @Test
    fun aTierThatNeverDeclinedShowsNoCardAtAll() {
        NpuTierStatus.publish("npu", null)
        assertEquals(null, NpuTierStatus.reasonFor("npu"))
        assertEquals("no decline, no stage", null, NpuTierStatus.stageOf(null))
        assertEquals("no decline, no note", null, NpuTierStatus.cardNote(null))
        assertEquals("nor for an empty reason", null, NpuTierStatus.cardNote("   "))
        // A malformed reason degrades to the whole string rather than to an empty label: a card
        // reading "unavailable (stage: )" is worse than one repeating something odd.
        assertEquals("truncated", NpuTierStatus.stageOf("truncated"))
        assertEquals(": leading", NpuTierStatus.stageOf(": leading"))
    }

    @Test
    fun theBackendAnnouncesEveryWriteOfItsReasonThroughOneFunnel() {
        // The wiring, pinned as source because no unit test may NAME NpuWhisperBackend (its
        // QnnAsrNative reference runs System.loadLibrary at class-init). The publication lives in
        // the property's SETTER and not at the assignment sites, which is the same "one funnel"
        // rule as PreferencesManager.notifyModelInstalled: a stage that declines cannot set the
        // reason and forget to announce it — including a stage nobody has written yet.
        val backend = source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
        assertEquals(
            "the reason's setter publishes to the process-scoped mirror the card subscribes to — " +
                "under the instance's OWN tier id (per-tier since 4.1 L8), so a turbo decline " +
                "lands on turbo's record and never bans npu's routing or wears npu's card",
            1,
            liveLineCount(backend, "NpuTierStatus.publish(spec.tierId, value)"),
        )
        assertEquals(
            "and that is the ONLY publication site: a second one at a call site is a second thing " +
                "to remember, and the one that is forgotten is the one that matters",
            1,
            liveLineCount(backend, "NpuTierStatus.publish("),
        )
        assertEquals(
            "the funnel is the setter itself, so both existing writes go through it",
            1,
            liveLineCount(backend, "private set(value) {"),
        )
        assertEquals(
            "the arm path still CLEARS the reason, so a decline cannot outlive the session that " +
                "produced it and haunt the card of a tier that is now running fine",
            1,
            liveLineCount(backend, "unavailableReason = null"),
        )
        assertEquals(
            "and the decline path still sets it, stage first",
            1,
            liveLineCount(backend, "unavailableReason = \"\$stage: \$detail\""),
        )
    }

    // ------------------------------------------------------------------ the pack lifecycle (4.2 F5)

    @Test
    fun thePackFetchLineIsExactAndItsPrefixContiguous() {
        // The Play fetch flow's narration. Format asserted here, emission pinned over the
        // controller's source below — the F-rule, applied to its fourth line family.
        assertEquals(
            "pack: fetch tier=npu-turbo pack=npu_turbo status=downloading " +
                "soFar=105906176 total=901775360",
            NpuDiag.packLine("npu-turbo", "npu_turbo", "downloading", 105_906_176L, 901_775_360L),
        )
        assertEquals(
            "states without progress carry honest zeros rather than omitting the fields — one " +
                "shape, one parser",
            "pack: fetch tier=npu pack=npu_small status=pending soFar=0 total=0",
            NpuDiag.packLine("npu", "npu_small", "pending", 0L, 0L),
        )
        // Locale-free integer rendering, asked as a behaviour (the mel line's own lesson): a
        // comma-grouping device must not turn the byte counts into 105.906.176.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals(
                "pack: fetch tier=npu-turbo pack=npu_turbo status=downloading " +
                    "soFar=105906176 total=901775360",
                NpuDiag.packLine("npu-turbo", "npu_turbo", "downloading", 105_906_176L, 901_775_360L),
            )
        } finally {
            java.util.Locale.setDefault(previous)
        }
        val diag = source("src/main/java/com/whispereverywhere/npu/NpuDiag.kt")
        assertEquals(
            "the `pack: fetch tier=` prefix is ONE contiguous literal on one live line",
            1,
            liveLineCount(diag, "\"pack: fetch tier="),
        )
        assertTrue(
            "and the whole field sequence lives in that one literal, in order",
            diag.contains(
                "\"pack: fetch tier=\$tierId pack=\$packName status=\$status " +
                    "soFar=\$soFar total=\$total\""
            ),
        )
    }

    @Test
    fun thePackOkLineMirrorsTheImportsSuccessLandmark() {
        val line = NpuDiag.packOk("npu-turbo", 2, 1_071_685_632L)
        assertEquals("pack: ok tier=npu-turbo entries=2 bytes=1071685632", line)
        // Deliberately SHAPED like the import's landmark, so the run-book reader greps two
        // spellings of one fact — asserted against the import's own builder rather than
        // restated, because a drift between the two shapes is exactly what this claim forbids.
        val importLandmark = NpuAssetImport.okLine(2, 1_071_685_632L)
        assertTrue(
            "one suffix shape across both landmarks: '$line' vs '$importLandmark'",
            line.endsWith("entries=2 bytes=1071685632") &&
                importLandmark.endsWith("entries=2 bytes=1071685632"),
        )
        val diag = source("src/main/java/com/whispereverywhere/npu/NpuDiag.kt")
        assertEquals(
            "the `pack: ok tier=` prefix is ONE contiguous literal on one live line",
            1,
            liveLineCount(diag, "\"pack: ok tier="),
        )
    }

    @Test
    fun thePackRefusedLineIsExact() {
        assertEquals(
            "pack: refused tier=npu-turbo reason=Wrong family variant: this pack is the " +
                "8gen3 variant and this device is 7gen4.",
            NpuDiag.packRefused(
                "npu-turbo",
                "Wrong family variant: this pack is the 8gen3 variant and this device is 7gen4.",
            ),
        )
        val diag = source("src/main/java/com/whispereverywhere/npu/NpuDiag.kt")
        assertEquals(
            "the `pack: refused tier=` prefix is ONE contiguous literal on one live line",
            1,
            liveLineCount(diag, "\"pack: refused tier="),
        )
    }

    /**
     * FOLDED 4.1 ITEM (L1 m5), closed the way the finding demanded: [NpuDiag.unavailable]'s
     * KDoc stage enumeration had rotted — eight stages listed, six missing, one RETIRED stage
     * (`assets`) still named — so the list is now RE-DERIVED from the backend's own decline
     * sites and this test holds the KDoc equal to the derivation. A new stage, a renamed
     * stage or a retired one now fails here by name instead of rotting silently. (The
     * re-derivation itself caught the rot's true size: the stale list was off by SEVEN — the
     * brief's six plus the unlisted `session` — which is exactly why the rule is "derive from
     * source, never retype from memory".)
     */
    @Test
    fun theUnavailableStageEnumerationIsReDerivedFromTheBackendsOwnDeclineSites() {
        val backend =
            source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
        // Every decline funnels through fallBackToCpuTier/fallBackAndRun with a literal stage
        // as its first argument (the one-funnel pin above proves the funnel); collect them in
        // source order, first occurrence wins.
        val declineSite = Regex("fallBack(?:ToCpuTier|AndRun)\\(\\s*\"([a-z-]+)\"")
        val derived = declineSite.findAll(backend).map { it.groupValues[1] }.distinct().toList()
        assertTrue(
            "the derivation found a real population (got $derived)",
            derived.size >= 10 && "encode" in derived && "decode" in derived,
        )
        // The KDoc's own enumeration: the backticked lowercase tokens between @param stage and
        // @param detail. (CamelCase references in the same block don't match [a-z-]+.)
        val diag = source("src/main/java/com/whispereverywhere/npu/NpuDiag.kt")
        val stageBlock = diag.substringAfter("@param stage").substringBefore("@param detail")
        val documented = Regex("`([a-z-]+)`").findAll(stageBlock)
            .map { it.groupValues[1] }.toList()
        assertEquals(
            "the KDoc enumerates exactly the stages the backend can produce, in decline-site " +
                "order — re-derived, never retyped",
            derived,
            documented,
        )
    }

    /**
     * EMISSION for the `pack:` family — the controller is `AssetPackManager`-bound, so the
     * call sites are pinned as source, the same instrument as every emission pin in this
     * class. Each builder has exactly its one site; the progress throttle is consulted at
     * exactly one place (transition lines bypass it, tick lines cannot); and the `npu: offer`
     * line is UNTOUCHED — fetch state is pack lifecycle, not offer state.
     */
    @Test
    fun thePackFamilyIsEmittedByTheControllerUnderTheHouseTag() {
        val controller = source("src/main/java/com/whispereverywhere/npu/NpuPackController.kt")
        assertEquals(
            "the fetch line has exactly one emitter — the publish funnel — under the house tag",
            1,
            liveLineCount(controller, "Log.i(NpuDiag.TAG, NpuDiag.packLine("),
        )
        assertEquals(
            "and no second packLine call anywhere in the shell",
            1,
            liveLineCount(controller, "NpuDiag.packLine("),
        )
        assertEquals(
            "the success landmark is emitted exactly once, under the house tag",
            1,
            liveLineCount(controller, "Log.i(NpuDiag.TAG, NpuDiag.packOk("),
        )
        assertEquals(
            "the refusal line likewise — at Log.w, the same level the import's refusal takes",
            1,
            liveLineCount(controller, "Log.w(NpuDiag.TAG, NpuDiag.packRefused("),
        )
        assertEquals(
            "the throttle decision has exactly ONE call site — the tick path; a second one " +
                "would be a second opinion about when a line may print",
            1,
            liveLineCount(controller, "NpuPackFetch.shouldLogProgress("),
        )
        assertEquals(
            "every AssetPackState goes through the ONE pure mapping — the shell interprets " +
                "no status on its own",
            1,
            liveLineCount(controller, "NpuPackFetch.advance("),
        )
        assertEquals(
            "and the offer line is untouched by the pack family: zero offer emissions here",
            0,
            liveLineCount(controller, "NpuDiag.offer("),
        )
    }

    /**
     * THE ORDER INVARIANT the fetch flow owns (4.2 F5 — the remove-after-land rule's newest
     * instance, following the finalise's restore-after-remove, the announce-after-verify and
     * the rest of the family): `removePack` runs STRICTLY AFTER `installFromPack` has
     * verified and renamed the staged pair into place. The delivered pack is the ONLY copy of
     * those bytes until the finalise commits — a remove that runs early deletes the source
     * mid-verify, and no ordering of two async completions carries that invariant; the state
     * machine does, and this pin holds its source shape.
     */
    @Test
    fun removePackRunsStrictlyAfterTheStagedPairIsRenamedIntoPlace() {
        val controller = source("src/main/java/com/whispereverywhere/npu/NpuPackController.kt")
        assertEquals(
            "exactly one remove site in the shell",
            1,
            liveLineCount(controller, ".removePack("),
        )
        val install = offsetOfLive(controller, ".installFromPack(")
        val installedPublish =
            offsetOfLive(controller, "publish(tierId, packName, NpuPackFetch.FetchState.Installed)")
        val remove = offsetOfLive(controller, ".removePack(")
        assertTrue(
            "install ($install) -> Installed published ($installedPublish) -> removePack " +
                "($remove): the remove sits below the success branch's own publication",
            install in 0 until installedPublish && installedPublish < remove,
        )
        // And the refusal arm keeps the pack: a failed verify must leave the delivered bytes
        // in place for a costless retry, so its branch contains NO remove.
        val refusedArm = controller.substringAfter("is NpuAssetImport.ImportState.Refused ->")
        assertTrue("the refusal arm was found", refusedArm.length < controller.length)
        assertEquals(
            "no removePack on the refusal path — the pack stays for the retry",
            0,
            liveLineCount(refusedArm, ".removePack("),
        )
    }
}
