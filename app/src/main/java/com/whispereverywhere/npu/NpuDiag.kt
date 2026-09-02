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
     * @param noSpeechProb raw `p(<|nospeech|>)` at the SOT step, or `-1` when the logits' scale
     *        was unreadable and the probability could not be computed.
     * @param avgLogprob the returned rung's masked mean log-probability, or `NaN` when the scale
     *        was unreadable or nothing was scored.
     * @param entropy histogram entropy of the last [NpuDecodePolicy.ENTROPY_WINDOW] ids, or `NaN`
     *        when that window was never reached on the returned rung.
     * @param rung index into [NpuDecodePolicy.TEMPERATURES] of the fallback-ladder rung actually
     *        returned.
     * @param terminator [NpuDecodeStats.terminatorName]'s answer: `eot`, `budget`, `cap` or `cut`.
     */
    fun line(
        encodeMs: Long,
        decodeMs: Long,
        tokens: Int,
        langNote: String,
        noSpeechProb: Float,
        avgLogprob: Float,
        entropy: Float,
        rung: Int,
        terminator: String,
    ): String =
        "npu: encode=$encodeMs decode=$decodeMs tokens=$tokens lang=$langNote " +
            "nsp=${f2(noSpeechProb)} lp=${f2(avgLogprob)} ent=${f2(entropy)} rung=$rung term=$terminator"

    /** Two decimals, US locale, `NaN` printed as the literal `NaN` — never a locale comma. */
    private fun f2(v: Float): String =
        if (v.isNaN()) "NaN" else String.format(java.util.Locale.US, "%.2f", v)

    /**
     * `mel: bins=80 frames=3000 row0=1234.567 rowMid=890.123 rowLast=456.789` — one line per
     * segment, emitted after the spectrogram is computed and before it is quantised (4.0, Q9 fix
     * round, I2).
     *
     * **`rowMid == rowLast` IS the stride bug**, and that is the whole design of this line.
     * whisper's internal mel is bin-major with stride `mel.n_len` (6000 for a 30 s window — the
     * loader appends 30 s of zeros before framing) while the destination stride is 3000, so a flat
     * copy reads the lower half of the bins at wrong offsets and leaves the upper half
     * **untouched**. Nothing downstream can detect that: the encoder accepts structured noise and
     * transcribes it fluently into different words. Two equal row sums where the audio had any
     * high-frequency content at all is the bisector, and it costs `3 * melFrames` float adds
     * against a ~405 ms encode.
     *
     * **The field names are `rowMid`/`rowLast` rather than `row40`/`row79` (4.1 L2).** The rows are
     * `0`, `melBins/2` and `melBins-1`, so on an 80-bin tier they are still rows 40 and 79 and the
     * numbers are unchanged — but with a second bin count in the lineup a fixed `row79` would name
     * a row that does not exist on one of the two tiers, and the whole value of this line is that
     * the reader can tell at a glance which halves of the spectrogram were written. `bins=` is a
     * parameter for the same reason.
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
     *
     * @param bins the tier's [NpuModelSpec.melBins]. `frames` is not a parameter beside it because
     *        3000 is universal across every published whisper asset — it is the one of the two the
     *        model lab cannot vary, and a parameter carrying it would be a number a caller can get
     *        wrong. Same reasoning, and the same three constants, as `nativeInit`'s scalar list.
     */
    fun mel(bins: Int, row0: Double, rowMid: Double, rowLast: Double): String =
        String.format(
            java.util.Locale.US,
            "mel: bins=%d frames=%d row0=%.3f rowMid=%.3f rowLast=%.3f",
            bins, NpuModelSpec.MEL_FRAMES, row0, rowMid, rowLast,
        )

    /**
     * `npu-debug: melprobe qRow0=… qCol0=… cell[0]=… cell[1500]=… cell[121500]=… scale=… zp=…` —
     * the **Kotlin half of the Q10a-D2 encoder read**, emitted once per segment behind
     * `BuildConfig.DEBUG` and never in a release build.
     *
     * **It exists to be compared, line to line, with what native prints next.** Native scans the
     * `uint16` block from the pointer the DSP is bound to and reports the same two sums
     * (`npu-debug: layout sumFirstRow= sumColStride=`) and the same three cells dequantised back to
     * floats (`npu-debug: dequant`). These are computed from the float mel by a separate route. One
     * reading describes a buffer; the pair decides between the three live hypotheses — a faithful
     * copy, a transposed one, and a byte-swapped or misaddressed one — which is the whole reason
     * this round exists.
     *
     * **Why the mel floats are not transcript content.** Three values out of 240,000, plus two
     * aggregates over 3,000 and 80 of them. A spectrogram cell is a band energy at one 10 ms frame;
     * nothing about a word survives three of them, and the shipped `mel:` line above already prints
     * three whole row sums unconditionally. This one is additionally DEBUG-gated.
     *
     * **`Locale.US`**, for the reason [mel] states: a comma-decimal device turns `qRow0=1.234` into
     * something every parser and every eye reads wrongly but almost correctly.
     *
     * @param spec the tier's shape — the two cell indices below are derived from it, never written
     *        as 1500 and 121500, because on a 128-bin tier those two numbers address a different
     *        part of the spectrogram than the ones native reports and the pair would be compared
     *        anyway.
     * @param qRow0 [NpuQuantize.quantisedRowSum] of row 0 — compare with native `sumFirstRow`.
     * @param qCol0 [NpuQuantize.quantisedColumnSum] of column 0 — compare with native `sumColStride`.
     * @param cells the float mel at indices `0`, `melFrames/2` and `melFrames*(melBins/2) +
     *        melFrames/2` — the same three native dequantises. Exactly three, and the caller is
     *        held to it: a line whose cell count drifted from native's would be compared anyway.
     */
    fun melProbe(
        spec: NpuModelSpec,
        qRow0: Long,
        qCol0: Long,
        cells: FloatArray,
        scale: Float,
        zeroPoint: Int,
    ): String {
        require(cells.size == 3) {
            "melProbe takes exactly the 3 cells native dequantises, got ${cells.size}"
        }
        val i1 = spec.melFrames / 2
        val i2 = spec.melFrames * (spec.melBins / 2) + i1
        return String.format(
            java.util.Locale.US,
            "npu-debug: melprobe qRow0=%d qCol0=%d cell[0]=%.6f cell[%d]=%.6f cell[%d]=%.6f " +
                "scale=%.9g zp=%d",
            qRow0, qCol0, cells[0], i1, cells[1], i2, cells[2], scale, zeroPoint,
        )
    }

    /**
     * `npu: unavailable stage=encode detail=encode: graphExecute failed at 0` — emitted once, on
     * the path where the tier declines and the session falls back to the CPU model.
     *
     * **This line is the doctrine.** A silent fallback that quietly runs on the CPU while the tier
     * card still says "AI chip" is the failure this project has already paid for once, so the
     * fallback is not allowed to be quiet: the stage is named, the native detail is carried
     * verbatim from `nativeLastError()`, and Q8's card reads the same fact.
     *
     * @param stage which step declined. One word, greppable, never a sentence. The full
     *        enumeration, in decline-site order, RE-DERIVED from the backend's own
     *        `fallBackToCpuTier`/`fallBackAndRun` call sites (4.2 F5, folding 4.1 L1 m5 — the
     *        previous list here was stale by six stages and still named a retired one, and
     *        `NpuDiagTest` now derives this list from the source so it cannot rot again):
     *        `companion`, `mel-donor`, `mel-asset`, `mel-init`, `vocab`, `skel`, `init`,
     *        `epoch`, `session`, `mel`, `quant`, `encode`, `lang`, `decode`.
     * @param detail `QnnAsrNative.nativeLastError()` or an equivalent one-line reason. Never
     *        transcript content: every producer of this string is a stage name and a native error.
     */
    fun unavailable(stage: String, detail: String): String =
        "npu: unavailable stage=$stage detail=$detail"

    /**
     * `npu: fallback rebuild stage=encode (the cached local engine is rebuilt on the CPU tier)` —
     * emitted **once per session**, by the service, when it drops the engine it built on the NPU
     * backend and builds the CPU one in its place (4.0, Q9). **Only then (L8 review I2):** the
     * service's partition also requires the replacement to actually route to the CPU tier, so
     * this line's parenthetical is true whenever it prints. A decline followed by a switch to
     * the OTHER npu tier is narrated by [tierRebuild] instead, naming the real target — under
     * the old decline-only partition this line would have claimed a CPU rebuild while the
     * replacement routed to the other NPU tier.
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
     * `npu: tier rebuild from=npu to=npu-turbo (the cached local engine is rebuilt for the
     * selected tier)` — emitted **once per rebuild**, by the service, for every rebuild whose
     * target is not the decline-forced CPU fallback: the user switched tiers — **including a
     * switch away from a tier that had declined** (4.0 Q9 M3, folded at 4.1 L8; the
     * decline-then-switch arm re-specced here by the L8 review's I2, because the line must name
     * the ACTUAL target and the fallback line's "CPU tier" claim would be false on that path).
     *
     * **The silent rebuild was correct behaviour with missing narration, and the A/B makes it
     * the ordinary path.** The owner's session is a sequence of exactly these switches — multi ->
     * npu -> npu-turbo and back — and until L8 the only rebuild line was [fallbackRebuild], whose
     * absence a reader could take as "no rebuild happened". Now every rebuild narrates itself:
     * a decline keeps the stage-carrying line above, and a switch names both sides.
     *
     * @param fromNpuTierId the npu-class tier the cached engine was built on, or null for the
     *        shared CPU backend — reported as `cpu`, because every CPU tier is one backend and
     *        the engine genuinely cannot tell them apart (that is the null's meaning).
     * @param toNpuTierId the npu-class tier the replacement routes to, or null likewise.
     */
    fun tierRebuild(fromNpuTierId: String?, toNpuTierId: String?): String =
        "npu: tier rebuild from=${fromNpuTierId ?: "cpu"} to=${toNpuTierId ?: "cpu"} " +
            "(the cached local engine is rebuilt for the selected tier)"

    /**
     * `npu: offer soc=SM8650:pass probe=pass installed=npu,npu-turbo offered=npu,npu-turbo` —
     * emitted **once per install epoch** (once per process per `ModelInstallSignal` generation —
     * re-armed by an install, never by a chooser open; 4.1 L8, L5 review I1), at the gate's
     * first evaluation of each epoch.
     *
     * **This line is the run-book's first read.** The gate composes three predicates and its
     * answer is one set, so "the card never showed" collapses three very different next actions
     * into one symptom: *unsupported SoC* (nothing to do — wrong phone), *probe failed* (the QNN
     * stack did not load — an `ADSP_LIBRARY_PATH` / `libqnnasr.so` question), and *nothing
     * installed* (import a pair). The memo makes it worse, not better: a probe failure is latched
     * for the life of the process and `runCatching{…}.getOrDefault(false)` discards the reason on
     * the way through, so without this line the evidence does not exist anywhere. Since 4.1 two
     * gated tiers can be independently installed, so `installed=` names the tier ids — sorted,
     * comma-joined, `none` for the empty set — rather than one Boolean that cannot say which.
     *
     * **`probe=skipped` is not `probe=fail`, and the distinction is the whole point.** It now has
     * two causes, told apart by the fields beside it. The SoC table is evaluated first precisely
     * so a non-Qualcomm device never dlopens a Qualcomm backend (`soc=…:fail`); and since L5
     * flipped the conjunction, a device with **no gated pair on disk** returns before the dlopen
     * too (`soc=…:pass installed=none`). In both states the probe genuinely did not run, and
     * reporting `fail` would invent a measurement that was never taken.
     *
     * **Never transcript content**: two hardware identifiers, two verdicts and a set of tier ids.
     *
     * @param socModel `Build.SOC_MODEL`, or null below API 31 — reported as `unknown`, which is
     *        also what the platform substitutes when an OEM leaves the field unset.
     * @param socSupported [NpuGate.isSocSupported]'s answer. Reporting only; the DECISION is the
     *        caller's `capable`.
     * @param capable the memoised gate — `isSocSupported && probe` — or **null when the gate
     *        returned before evaluating it** because nothing was installed. Null is reported as
     *        `probe=skipped`, never as `fail`.
     * @param installedTierIds the gated tiers whose files were on disk **at this emission**.
     *        The SoC and probe verdicts are process-permanent; this one is a snapshot — and
     *        since the L8 re-arm, an import refreshes it: the next evaluation emits a fresh
     *        line for the new epoch. Only the signal-less `adb push` route leaves a stale
     *        snapshot until restart, and the run-book says so where it prescribes that route.
     */
    fun offer(
        socModel: String?,
        socSupported: Boolean,
        capable: Boolean?,
        installedTierIds: Set<String>,
    ): String {
        val probe = when {
            !socSupported -> "skipped"
            capable == null -> "skipped"
            capable -> "pass"
            else -> "fail"
        }
        val soc = "${socModel ?: "unknown"}:${if (socSupported) "pass" else "fail"}"
        // Sorted so the line is ONE greppable spelling, not one per set-iteration order.
        val installed =
            if (installedTierIds.isEmpty()) "none"
            else installedTierIds.sorted().joinToString(",")
        val offered = if (capable == true) installed else "none"
        return "npu: offer soc=$soc probe=$probe installed=$installed offered=$offered"
    }

    // ------------------------------------------------------------------ the pack lifecycle (4.2 F5)

    /**
     * `pack: fetch tier=npu-turbo pack=npu_turbo status=downloading soFar=105906176
     * total=901775360` — the Play fetch flow's narration, emitted by `NpuPackController` once
     * per STATUS TRANSITION plus at most one per 10% of progress (the throttle is
     * `NpuPackFetch.shouldLogProgress`, a pure decision with its own tests).
     *
     * The `pack: fetch ` prefix is a **single contiguous literal** here, same F-rule as every
     * line in this file: format asserted on the builder, emission pinned at the shell's source,
     * because either alone is decoration. **The `npu: offer` line is untouched by this family**
     * — fetch state is PACK lifecycle, not offer state, and the two must stay separately
     * greppable.
     *
     * Never transcript content: a tier id, a pack name, one status word, two byte counts.
     * The integers render through Kotlin string interpolation, which is locale-free — no
     * grouping separators on any device.
     *
     * @param status one of `NpuPackFetch.statusWord`'s tokens, never free text.
     */
    fun packLine(tierId: String, packName: String, status: String, soFar: Long, total: Long): String =
        "pack: fetch tier=$tierId pack=$packName status=$status soFar=$soFar total=$total"

    /**
     * `pack: ok tier=npu-turbo entries=2 bytes=1071685632` — the fetch flow's success landmark,
     * deliberately shaped like the import's `npu: import ok` line so the run-book's reader
     * greps two spellings of one fact: a verified pair landed, this many files, this many
     * bytes. Emitted exactly once per successful pack install, AFTER the shared parking
     * transaction has committed and never before.
     */
    fun packOk(tierId: String, entries: Int, bytes: Long): String =
        "pack: ok tier=$tierId entries=$entries bytes=$bytes"

    /**
     * `pack: refused tier=npu-turbo reason=…` — the fetch flow's refusal line, one per refused
     * install. [reason] is the same user-facing sentence the card renders through
     * `FetchState.Failed` (already bounded by its builders — the cross-check and refusal
     * vocabulary never carries paths or content), so the log and the screen state one fact.
     */
    fun packRefused(tierId: String, reason: String): String =
        "pack: refused tier=$tierId reason=$reason"
}
