package com.whispereverywhere.npu

/**
 * The NPU tier's two diagnostic lines, as pure string builders.
 *
 * WHY THEY ARE FUNCTIONS AND NOT INLINE `Log.i` FORMAT STRINGS. Unit tests run with
 * `unitTests.isReturnDefaultValues = true`, so `android.util.Log` is a no-op on the JVM and **no
 * test can observe the string a call site actually produces**. Splitting the format out (the same
 * F-rule split `SegmentTiming` already uses) makes the text assertable here, and leaves the call
 * site itself to be pinned by source text — format and emission, both guarded, because either one
 * alone is decoration.
 *
 * WHY IT MATTERS ENOUGH TO GUARD. The owner has no adb. These two lines are the only evidence this
 * tier produces about itself: whether the NPU ran at all, how the ~405 ms encode and the ~4.5 ms/
 * token decode actually behaved on the device, and — the one that is not a number — **which
 * language the segment was transcribed under and how that was decided**. A wrong language is a
 * fluent, confident, wrong transcript, and `lang=` is the only place it is ever visible.
 *
 * **Never transcript content.** Lengths, counts, language codes and milliseconds only. `tokens=`
 * is `nativeDecodeSegment`'s returned count, not the decoded string's length — the count exists
 * before the text does, and reading it off the text would be one step from logging the text.
 */
object NpuDiag {

    /** The house diagnostic tag, so a grep for one tier's lines is a grep for the app's. */
    const val TAG = "WE-DIAG"

    /**
     * `npu: encode=405 decode=168 tokens=37 lang=auto->fr(detected)` — one line per segment.
     *
     * The `npu: encode=` prefix is a **single contiguous literal** in this file, and there is a
     * test that says so. Building it from parts (`"npu: " + "encode="`, a `TAG` constant spliced
     * into the middle, a `buildString`) is invisible to the compiler and invisible in review, and
     * it breaks every grep and every parser written against the shipped format.
     *
     * @param encodeMs wall time of `nativeEncode`, including the mel and the quantisation — what
     *        the user waits for, not what the graph bills.
     * @param decodeMs wall time of the language pass plus `nativeDecodeSegment`.
     * @param tokens `nativeDecodeSegment`'s returned count. `0` is a legitimate value: EOT first,
     *        i.e. silence. A failure never reaches this line — it goes to [unavailable].
     * @param langNote [NpuDecodePolicy.LangResolution.note]: `es`, `auto->fr(detected)`,
     *        `auto->de(locale)` or `auto->en(fallback)`, and never anything else.
     */
    fun line(encodeMs: Long, decodeMs: Long, tokens: Int, langNote: String): String =
        "npu: encode=$encodeMs decode=$decodeMs tokens=$tokens lang=$langNote"

    /**
     * `mel: bins=80 frames=3000 row0=1234.567 row40=890.123 row79=456.789` — one line per segment,
     * emitted after the spectrogram is computed and before it is quantised (4.0, Q9 fix round, I2).
     *
     * **`row40 == row79` IS the stride bug**, and that is the whole design of this line. whisper's
     * internal mel is bin-major with stride `mel.n_len` (6000 for a 30 s window — the loader appends
     * 30 s of zeros before framing) while the destination stride is 3000, so a flat copy reads bins
     * 0-39 at wrong offsets and leaves bins 40-79 **untouched**. Nothing downstream can detect that:
     * the encoder accepts structured noise and transcribes it fluently into different words. Two
     * equal row sums where the audio had any high-frequency content at all is the bisector, and it
     * costs 9,000 float adds against a ~405 ms encode.
     *
     * **This line was promised by three ledger entries and did not exist.** Until this fix it lived
     * only as a comment in `whisper_jni.cpp` and a KDoc in `MelExportContractTest`; the Q10a
     * run-book instructed the owner to read a line nothing emitted. It is Kotlin-side deliberately —
     * the mel crosses into Kotlin already, so no native change is needed to see it.
     *
     * **`Locale.US`, not the default.** These are decimal numbers in a machine-read log line, and on
     * a device set to a comma-decimal locale `"%.3f"` produces `row0=1234,567` — which breaks every
     * parser and, worse, is *almost* readable, so it is the kind of defect that survives review.
     *
     * The prefix is `mel: `, not `npu: `, and deliberately: this measures whisper.cpp's spectrogram,
     * which the CPU and GPU tiers use too. It is the one line here that is not about the NPU.
     */
    fun mel(row0: Double, row40: Double, row79: Double): String =
        String.format(
            java.util.Locale.US,
            "mel: bins=%d frames=%d row0=%.3f row40=%.3f row79=%.3f",
            NpuQuantize.MEL_BINS, NpuQuantize.MEL_FRAMES, row0, row40, row79,
        )

    /**
     * `npu: unavailable stage=encode detail=encode: graphExecute failed at 0` — emitted once, on
     * the path where the tier declines and the session falls back to the CPU model.
     *
     * **This line is the doctrine.** A silent fallback that quietly runs on the CPU while the tier
     * card still says "AI chip" is the failure this project has already paid for once, so the
     * fallback is not allowed to be quiet: the stage is named, the native detail is carried
     * verbatim from `nativeLastError()`, and Q8's card reads the same fact.
     *
     * @param stage which step declined — `mel-donor`, `mel-init`, `mel`, `assets`, `init`,
     *        `quant`, `encode`, `decode`. One word, greppable, never a sentence.
     * @param detail `QnnAsrNative.nativeLastError()` or an equivalent one-line reason. Never
     *        transcript content: every producer of this string is a stage name and a native error.
     */
    fun unavailable(stage: String, detail: String): String =
        "npu: unavailable stage=$stage detail=$detail"

    /**
     * `npu: fallback rebuild stage=encode (the cached local engine is rebuilt on the CPU tier)` —
     * emitted **once per session**, by the service, when it drops the engine it built on the NPU
     * backend and builds the CPU one in its place (4.0, Q9).
     *
     * **It is a second line about the same event, and that is deliberate.** [unavailable] is the
     * BACKEND saying "this stage declined"; this is the ENGINE LAYER saying "and here is what I did
     * about it". They are emitted from different objects at different times — the decline happens
     * mid-session, the rebuild at the next warm — and without the second line a Q10a log shows a
     * tier that declined and then, silently, a session that no longer even tries. One line per
     * rebuild, never one per segment: a per-segment version would bury the `npu: encode=` and
     * `segment-timing:` pair it sits between.
     *
     * @param stage `NpuTierStatus.stageOf(reason)` — the same one word [unavailable] printed, so
     *        the two lines join by eye. `unknown` when the reason was null or unparseable, which is
     *        a state the caller should not be able to reach and is reported rather than hidden.
     */
    fun fallbackRebuild(stage: String?): String =
        "npu: fallback rebuild stage=${stage ?: "unknown"} " +
            "(the cached local engine is rebuilt on the CPU tier)"

    /**
     * `npu: offer soc=SM8650:pass probe=pass installed=false offered=false` — emitted **once per
     * process**, at the first evaluation of the memoised offer gate.
     *
     * **This line is the Q10a run-book's first read.** The gate is a conjunction of three
     * predicates and its answer is one Boolean, so "the card never showed" collapses three very
     * different next actions into one symptom: *unsupported SoC* (nothing to do — wrong phone),
     * *probe failed* (the QNN stack did not load — an `ADSP_LIBRARY_PATH` / `libqnnasr.so`
     * question), and *not installed* (import the pair). The memo makes it worse, not better: a
     * probe failure is latched for the life of the process and `runCatching{…}.getOrDefault(false)`
     * discards the reason on the way through, so without this line the evidence does not exist
     * anywhere.
     *
     * **`probe=skipped` is not `probe=fail`, and the distinction is the whole point.** The SoC
     * table is evaluated first precisely so a non-Qualcomm device never dlopens a Qualcomm backend;
     * on those devices the probe genuinely did not run, and reporting `fail` would invent a
     * measurement that was never taken.
     *
     * **Never transcript content**: two hardware identifiers and three verdicts.
     *
     * @param socModel `Build.SOC_MODEL`, or null below API 31 — reported as `unknown`, which is
     *        also what the platform substitutes when an OEM leaves the field unset.
     * @param socSupported [NpuGate.isSocSupported]'s answer. Reporting only; the DECISION is the
     *        caller's `capable`.
     * @param capable the memoised gate — `isSocSupported && probe`, so `capable` with
     *        `socSupported` true means the probe passed.
     * @param installed whether both context binaries were on disk **at this first evaluation**.
     *        The other two verdicts are process-permanent; this one is a snapshot, because the
     *        line is emitted once and an import can land afterwards.
     */
    fun offer(
        socModel: String?,
        socSupported: Boolean,
        capable: Boolean,
        installed: Boolean,
    ): String {
        val probe = when {
            !socSupported -> "skipped"
            capable -> "pass"
            else -> "fail"
        }
        val soc = "${socModel ?: "unknown"}:${if (socSupported) "pass" else "fail"}"
        return "npu: offer soc=$soc probe=$probe installed=$installed offered=${capable && installed}"
    }
}
