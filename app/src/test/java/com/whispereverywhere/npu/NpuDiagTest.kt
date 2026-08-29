package com.whispereverywhere.npu

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
     * bins 40-79. Nothing downstream can see it: the encoder accepts structured noise and
     * transcribes it fluently into different words. `row40 == row79` is the whole diagnosis.
     */
    @Test
    fun theMelLineIsOneLiteralAndItsRowsAreTheStrideBisector() {
        val diag = source("src/main/java/com/whispereverywhere/npu/NpuDiag.kt")
        assertEquals(
            "mel: bins=80 frames=3000 row0=1234.568 row40=0.000 row79=0.000",
            NpuDiag.mel(1234.5678, 0.0, 0.0),
        )
        assertEquals(
            "negatives are ordinary here — a log-mel is mostly negative, and a formatter that " +
                "mangled the sign would make every row look alike",
            "mel: bins=80 frames=3000 row0=-91234.500 row40=-88.250 row79=-0.001",
            NpuDiag.mel(-91234.5, -88.25, -0.001),
        )
        assertEquals(
            "the `mel: bins=` prefix is ONE contiguous literal on one live line. Assembling it " +
                "produces identical output and breaks every grep written against the format.",
            1,
            liveLineCount(diag, "\"mel: bins="),
        )
        assertTrue(
            "and the whole field sequence lives in that one literal, in order — row0, row40, row79",
            diag.contains("\"mel: bins=%d frames=%d row0=%.3f row40=%.3f row79=%.3f\""),
        )
        assertEquals(
            "Locale.US, not the default. On a comma-decimal device `%.3f` yields `row0=1234,567`, " +
                "which breaks every parser and is *almost* readable — the kind of defect that " +
                "survives review.",
            1,
            liveLineCount(diag, "java.util.Locale.US,"),
        )

        // EMISSION, and its POSITION, which is the invariant on both sides.
        val backend =
            source("src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt")
        assertEquals(
            "the backend emits the mel line exactly once per segment",
            1,
            liveLineCount(backend, "NpuDiag.mel("),
        )
        assertEquals(
            "row 79 is taken from MEL_BINS - 1, not a bare 79: the last row IS the claim, and a " +
                "future 128-bin tier would leave a literal 79 measuring the middle of the band",
            1,
            liveLineCount(backend, "NpuQuantize.melRowSum(melView, NpuQuantize.MEL_BINS - 1),"),
        )
        assertEquals(
            "the view is taken fresh and read absolutely, so the shared direct buffer handed on to " +
                "melToU16 and then nativeEncode keeps its position untouched",
            1,
            liveLineCount(backend, "val melView = mel.asFloatBuffer()"),
        )
        val pcmToMel = offsetOfLive(backend, "if (!WhisperNative.pcmToMel(melCtx, samples, mel)) {")
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
        // The gate is a conjunction whose answer is one Boolean, so this line exists to un-collapse
        // it. Q10a's first read.
        assertEquals(
            "npu: offer soc=SM8650:pass probe=pass installed=false offered=false",
            NpuDiag.offer("SM8650", socSupported = true, capable = true, installed = false),
        )
        assertEquals(
            "npu: offer soc=SM8650:pass probe=pass installed=true offered=true",
            NpuDiag.offer("SM8650", socSupported = true, capable = true, installed = true),
        )
        // The right silicon, but the QNN stack did not load — an ADSP_LIBRARY_PATH / libqnnasr.so
        // question, and a completely different next action from the two below.
        assertEquals(
            "npu: offer soc=SM8650-AC:pass probe=fail installed=true offered=false",
            NpuDiag.offer("SM8650-AC", socSupported = true, capable = false, installed = true),
        )
        // SKIPPED, not failed. The SoC table is checked first precisely so a non-Qualcomm device
        // never dlopens a Qualcomm backend, so on these devices the probe genuinely did not run —
        // reporting `fail` would invent a measurement nobody took.
        assertEquals(
            "npu: offer soc=Tensor G3:fail probe=skipped installed=false offered=false",
            NpuDiag.offer("Tensor G3", socSupported = false, capable = false, installed = false),
        )
        // Below API 31 the caller hands us null by design (NpuGate's null -> deny). The line must
        // still be readable rather than printing "null".
        assertEquals(
            "npu: offer soc=unknown:fail probe=skipped installed=false offered=false",
            NpuDiag.offer(null, socSupported = false, capable = false, installed = false),
        )
    }

    @Test
    fun theOfferLineIsGreppableWithTheOtherTwoAndCarriesNoTranscriptContent() {
        val line = NpuDiag.offer("SM8650", socSupported = true, capable = true, installed = true)
        assertTrue("the offer line must share the tier's `npu: ` prefix", line.startsWith("npu: "))
        assertTrue("one line, never two", !line.contains("\n"))
        // Same shape as the other two: a word, then k=v pairs a parser can split on.
        listOf("soc=", "probe=", "installed=", "offered=").forEach {
            assertEquals("the offer line states `$it` exactly once", 1, line.split(it).size - 1)
        }
    }

    @Test
    fun theOfferLineIsEmittedExactlyOncePerProcessAtTheGatesFirstEvaluation() {
        // Format and emission, both guarded — either alone is decoration (this class's KDoc). The
        // emitter is `WhisperEverywhereApp`, which no JVM test can construct, so the call is
        // pinned as source; the ONE-SHOT is the AtomicBoolean, because a line re-emitted on every
        // chooser open stops being a landmark and becomes noise in a logcat with no adb behind it.
        val app = source("src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt")
        assertEquals(
            "the offer line has exactly one emitter",
            1,
            liveLineCount(app, "NpuDiag.offer("),
        )
        assertEquals(
            "the emitter is behind a compareAndSet, so it runs once per process and not once per " +
                "chooser open",
            1,
            liveLineCount(app, "if (npuOfferLogged.compareAndSet(false, true)) {"),
        )
        // Battery row V10, a measured survivor: diverting the line to a private tag
        // (`Log.i("NpuOffer", …)`) leaves the format correct, the emission once-per-process and
        // every other assertion in this class green — while making the line invisible to the ONE
        // grep the Q10a run-book tells an owner with no adb to run. The tag is the whole
        // distribution mechanism, so it is pinned as the symbol and not merely as "some tag".
        assertEquals(
            "the line goes to the house tag BY NAME, so one grep finds every tier line",
            1,
            liveLineCount(app, "NpuDiag.TAG,"),
        )
        assertEquals(
            "and it is emitted at Log.i — a diagnostic the owner is asked to read must not sit " +
                "below the default logcat filter",
            1,
            liveLineCount(app, "Log.i("),
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
        NpuTierStatus.publish("$stage: $detail")

        assertEquals(
            "the card reads what the backend published",
            "$stage: $detail",
            NpuTierStatus.unavailableReason.value,
        )
        assertEquals(
            "and it recovers the same STAGE word the log line carries — the part a screenshot can " +
                "usefully report",
            stage,
            NpuTierStatus.stageOf(NpuTierStatus.unavailableReason.value),
        )
        assertTrue("which is the word the log line leads with too: $logLine", logLine.contains("stage=$stage"))

        val note = NpuTierStatus.cardNote(NpuTierStatus.unavailableReason.value)!!
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
    }

    @Test
    fun aTierThatNeverDeclinedShowsNoCardAtAll() {
        NpuTierStatus.publish(null)
        assertEquals(null, NpuTierStatus.unavailableReason.value)
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
            "the reason's setter publishes to the process-scoped mirror the card subscribes to",
            1,
            liveLineCount(backend, "NpuTierStatus.publish(value)"),
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
}
