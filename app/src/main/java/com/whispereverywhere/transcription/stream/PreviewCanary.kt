package com.whispereverywhere.transcription.stream

/** The canary's answer; `code` is what the `stream-open:` line prints. */
sealed class CanaryVerdict {
    abstract val code: String
    data class Pass(val outLen: Int, val decodes: Int) : CanaryVerdict() { override val code: String get() = "pass" }
    data class Fail(val outLen: Int, val decodes: Int) : CanaryVerdict() { override val code: String get() = "fail" }
    object NoClip : CanaryVerdict() { override val code: String get() = "none" }
}

/**
 * A pack's canary verdict rule: which spoken POSITIONS the clip contains, in order, each as the set
 * of renderings that count, and the two tolerances.
 *
 * ### Why the previewer has its own rule object at all
 *
 * 4.4.0 scored with `GpuCanaryPolicy.canaryPasses` directly. That function is the whisper-GPU
 * canary's rule, and **its verdict is a PERSISTED per-(app version, model, device) CPU latch** —
 * so it is the one policy in the app that must not be edited to suit a second caller. A per-pack
 * rule therefore needs a sibling, not a parameter added to that object: this is the sibling, it
 * carries its own values and its own scoring, and `PreviewCanaryTest` holds the two ANSWERS equal
 * on the English clip so neither can drift without a red test.
 *
 * ### What it cannot express, named so nobody assumes it can
 *
 * Positional alias matching needs the model to emit separable pieces per spoken position. **zh and
 * ko collapse the whole clip to ONE token** — `RemoveSpaceBetweenCjk` joins the characters and any
 * word-splitting normaliser then sees a single run — so per-position matching is structurally
 * impossible there and [maxTokens] is unreachable rather than protective. Those packs need a
 * different RULE (character-set overlap plus a length band: 一二三四五 / 일이삼사오), which is a
 * second implementation of this seam and not a different [expected] list. The qualification table
 * books that as shared work for both languages (§6(3)); this build ships the route, not the rule.
 *
 * @property expected one alias set per spoken position, in the clip's order. A position counts as
 *   matched when ANY of its renderings appears.
 * @property minMatches how many positions must appear. One dropped item on a short clip is
 *   ordinary ASR slack; two is a signal.
 * @property maxTokens beyond this many tokens the output is a repetition runaway rather than a
 *   transcription — a shape that can CONTAIN every expected position and still be garbage.
 */
data class PreviewCanaryRule(
    val expected: List<Set<String>>,
    val minMatches: Int,
    val maxTokens: Int,
)

/**
 * A pack's canary: the clip in main assets and the rule that scores it, which are ONE thing —
 * [StreamingPack.canary], nullable, so that "this language's clip has not been sourced yet" is a
 * state the catalogue can SAY rather than fake.
 *
 * ### Why nullable, and why one field rather than two
 *
 * 4.5.0 adds six languages. Two of them have a canary that is free today: the bilingual zh-en row
 * runs the **bundled English clip unchanged** (its vocabulary carries `▁ONE ▁TWO ▁THREE ▁FOUR` as
 * whole pieces and splits `FIVE` into `▁FI`+`VE` exactly as the English pack does — verified
 * against its own `tokens.txt`), and French is synthesizable in-repo from Kokoro's `ff_siwis`
 * voice. The other four have **no Kokoro voice at all** (`TtsVoices.kt` covers es fr hi it ja pt
 * zh), so their clips come from FLEURS and are a sourcing job with a licence to record per clip.
 *
 * A catalogue that cannot express "not yet" would have to invent one of two lies instead:
 *
 *  - **a clip filename that does not exist** — which reads as a promise, and is only harmless by
 *    accident (`CanaryAudio.samples` happens to return null for a missing asset); or
 *  - **a sentinel rule** — `expected = emptyList()`, which either passes every text (`minMatches`
 *    0) or fails every text (`minMatches` 1). Both are verdicts about a model nobody has listened
 *    to, and a Fail switches live words off for that language for the process.
 *
 * `null` is the third answer and the true one: **no verdict**, which is exactly what
 * [CanaryVerdict.NoClip] already means for a clip that will not load. One field rather than two
 * because an asset without a rule (or a rule without an asset) is a state nothing could act on.
 *
 * @property asset the WAV in main assets — PCM16 mono 16 kHz, read by `CanaryAudio.samples`.
 * @property rule what a PASS means for that clip.
 */
data class PackCanary(
    val asset: String,
    val rule: PreviewCanaryRule,
)

/**
 * The load-time canary (spec §7.2) — RULING ASSUMED (R1): the ONLY guard against the FEAT_SME
 * silent-miscompute class. sherpa-onnx #3845 (SM8850, ORT 1.27.0): EMPTY text for the whole
 * stream, no crash, no NaN; #3791 (M4): `"MY WOMAN"` for a five-word clip. Neither test device
 * has SME (rung 3 §1.1), so on the devices we own this always passes — the rule exists for the
 * `8elite5_galaxy` census family (NpuFleetCensus.kt:142-145) we cannot test.
 *
 * Feeds the PACK's own clip ([StreamingPack.canaryAsset] — `canary_digits.wav` for English, read
 * by `CanaryAudio`: 2.560 s of "one two three four five") in the app's 512-sample chunks, pads the
 * pack's own [StreamingPack.padMs] — the same derived pad the commit hook uses, because a canary
 * padded shorter than the stream is a verdict on a configuration the feature never runs —
 * finishes, drains, and scores against the pack's own [StreamingPack.canaryRule].
 *
 * **The clip is per-pack because the English one cannot pass for a non-English model**: a French
 * or Russian recognizer fed "one two three four five" answers something that matches none of the
 * five positions, which is indistinguishable here from the SME signature it exists to catch. The
 * recorded cost is one WAV in main assets per language — **81,998 B**, the size of the English one.
 * A null clip is NO VERDICT, not a failure.
 *
 * The verdict is never persisted (no preference, no per-(versionCode, pack) latch) and — since
 * 4.5.0 T2, defect 4 — it is scoped to the PACK rather than to the process: the tee is additive,
 * so a false negative costs one process of blank strips for ONE language and nothing typed.
 */
object PreviewCanary {

    fun run(recognizer: PreviewRecognizer, clip: FloatArray?, pack: StreamingPack): CanaryVerdict {
        // Two ways to have nothing to score, and both are NO VERDICT rather than a failure: the
        // clip would not load, or the row has no canary sourced yet ([PackCanary]'s docblock).
        val rule = pack.canary?.rule ?: return CanaryVerdict.NoClip
        if (clip == null || clip.isEmpty()) return CanaryVerdict.NoClip
        val stream = recognizer.createStream()
        var decodes = 0
        try {
            var i = 0
            while (i < clip.size) {
                val n = minOf(StreamingPreviewTuning.CHUNK_SAMPLES, clip.size - i)
                stream.acceptWaveform(clip.copyOfRange(i, i + n))
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                    decodes++
                }
                i += n
            }
            stream.acceptWaveform(FloatArray(StreamingPreviewTuning.padSamplesFor(pack.encoderT)))
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                decodes++
            }
            val text = recognizer.result(stream).text
            return if (passes(text, rule)) CanaryVerdict.Pass(text.length, decodes)
            else CanaryVerdict.Fail(text.length, decodes)
        } finally {
            stream.release()
        }
    }

    /**
     * `GpuCanaryPolicy.canaryPasses`' algorithm, re-stated here over [rule]'s own values —
     * tolerant of ordinary ASR slack (casing, punctuation, one dropped item) and intolerant of
     * every observed corruption shape: empty, fewer than [PreviewCanaryRule.minMatches] positions,
     * a runaway token count.
     *
     * The digit decomposition is the GPU canary's too and is not optional: a model that renders
     * "one two three four five" as `12345` normalises to ONE token, and scoring that perfect
     * transcription zero would switch a working previewer off on a formatting coin-flip.
     */
    fun passes(text: String, rule: PreviewCanaryRule): Boolean {
        val tokens = normalize(text)
        if (tokens.isEmpty()) return false
        if (tokens.size > rule.maxTokens) return false
        val seen = tokens.toMutableSet()
        for (token in tokens) {
            if (token.length >= 2 && token.all { it.isDigit() }) {
                for (char in token) seen.add(char.toString())
            }
        }
        return rule.expected.count { aliases -> aliases.any { it in seen } } >= rule.minMatches
    }

    /** Lowercased word tokens, punctuation stripped — the GPU canary's normaliser, independently spelled. */
    fun normalize(text: String): List<String> =
        text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

    private val NON_WORD = Regex("[^\\p{L}\\p{Nd}]+")
}
