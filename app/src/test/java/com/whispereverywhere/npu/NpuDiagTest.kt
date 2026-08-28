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
}
