package com.whispereverywhere.transcription.stream

/**
 * The canary's answer; `code` is what the `stream-open:` line prints.
 *
 * **Four answers, THREE consequences, and the split between the last two is the point.** Only
 * [StreamingPreviewEngine.warm] consumes these, and it must not treat them as two buckets:
 *
 * | verdict | means | consequence |
 * |---|---|---|
 * | [Pass] | the clip transcribed | arm, SCORED |
 * | [Fail] | the clip did not transcribe | **disable** the language for the process |
 * | [NoClip] | the row NAMES a clip and that named asset would not load — a build defect | **disable** |
 * | [Unscored] | the row names no clip yet ([StreamingPack.canary] is null) — nothing to score | arm, UNSCORED |
 *
 * [NoClip] and [Unscored] were one state until 4.5.0 languages T1 review r1 (B1), and collapsing
 * them is why six healthy languages switched themselves off on first warm: a row whose clip is
 * merely *unsourced* took the branch built for a row whose clip is *broken*. They are now
 * distinguishable at the source — `pack.canary == null` versus `pack.canary != null && clip
 * null/empty` — so they are two verdicts. Arming [Unscored] leaves the FEAT_SME silent-miscompute
 * guard **unpaid for that language until its clip lands**; that is the priced trade, argued in
 * [PackCanary]'s docblock, and it is affordable only because the previewer is additive and never
 * types.
 */
sealed class CanaryVerdict {
    abstract val code: String
    data class Pass(val outLen: Int, val decodes: Int) : CanaryVerdict() { override val code: String get() = "pass" }
    data class Fail(val outLen: Int, val decodes: Int) : CanaryVerdict() { override val code: String get() = "fail" }
    /** A clip this row NAMED would not load. A build defect, and it keeps disabling the language. */
    object NoClip : CanaryVerdict() { override val code: String get() = "none" }
    /** This row names no clip yet. No verdict, no defect, and the language arms unscored. */
    object Unscored : CanaryVerdict() { override val code: String get() = "unscored" }
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
 * ### What it needs from the string it scores, and why that string is the STRIP (4.5.0 T3)
 *
 * Positional alias matching needs word boundaries, and those live in the TOKENS — not always in
 * the text. `SymbolTable::operator[]` rewrites a leading `▁` to a SPACE per piece, and the
 * recognizer's `Convert()` then runs `RemoveSpaceBetweenCjk` over the ASSEMBLED text
 * (`online-recognizer-transducer-impl.h:69`), whose `IsCJK` range `0xA840-0xD7AF` **contains
 * Hangul** `U+AC00-U+D7A3`.
 *
 * Measured on the real Korean pack (sherpa-onnx 1.13.7, its own placed payload): `result.tokens`
 * comes back `[" ", "스", "페", "인", " ", "사", "람", "들", "이", " ", …]` — the word marker, a
 * standalone piece at id 3, **is** emitted, once per word — while `result.text` is
 * `스페인사람들이삼세기…`, ONE run with every word space gone. Scoring the text would therefore give
 * Korean a single token, leaving [expected] unable to name anything smaller than the whole
 * utterance and [maxTokens] unable to see a runaway at all.
 *
 * So [PreviewCanary.run] scores [PreviewText.strip] — the tokens-not-text rendering the STRIP
 * paints — and every row's positions are words. That is also the more faithful guard: the strip is
 * what the user sees, so a canary reading `result.text` was scoring a string this app renders
 * nowhere. The verdict does not move for any other row, because tokens-joined equals text wherever
 * no CJK-adjacent space was removed, and [passes] lowercases before it matches.
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
 * `null` is the third answer and the true one: **no verdict** — [CanaryVerdict.Unscored], which is
 * NOT [CanaryVerdict.NoClip]. One field rather than two because an asset without a rule (or a rule
 * without an asset) is a state nothing could act on.
 *
 * ### What `null` costs, priced rather than implied
 *
 * A row with no clip **arms UNSCORED**: the model loads, the strip runs, and the FEAT_SME
 * silent-miscompute guard is **unpaid for that language until its clip lands**. On an SME device
 * (none we own — `8elite5_galaxy`, NpuFleetCensus.kt:142-145) that language could paint empty or
 * wrong words with no guard to catch it.
 *
 * That is affordable, and it is the same trade the feature itself is built on: the previewer is
 * **additive and never types**, whisper's final replaces the preview, so a wrong preview costs
 * throwaway words on a replace-only strip. The alternative is what B1 was — six healthy languages
 * that disable themselves on first warm, so the owner cannot hear ANY of them work on his own
 * devices, which is the one thing this build exists to let him do. An unscored strip is a smaller
 * loss than a dead language, and the row's own comment says which languages are carrying it.
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
 * Feeds the PACK's own clip ([PackCanary.asset] — `canary_digits.wav` for English, read by
 * `CanaryAudio`: 2.560 s of "one two three four five") in the app's 512-sample chunks, pads the
 * pack's own [StreamingPack.padMs] — the same derived pad the commit hook uses, because a canary
 * padded shorter than the stream is a verdict on a configuration the feature never runs —
 * finishes, drains, and scores the STRIP ([PreviewText.strip], not `result.text`) against the
 * pack's own [PackCanary.rule].
 *
 * **The clip is per-pack because the English one cannot pass for a non-English model**, and
 * 4.5.0 T3 measured it rather than assuming it: fed `canary_digits.wav`, the French pack answers
 * `TH TREE FORFACE`, the German one `VORFALL` and the Indonesian one `TWI FOR` — none of which
 * matches a single English position, so a shared clip would refuse three healthy packs as corrupt.
 * The recorded cost is one WAV in main assets per language: 47,498 B (fr) to 159,082 B (ko),
 * **536,626 B for the five new ones**, against the English clip's 81,998 B. The bilingual zh-en
 * row pays nothing — it runs the English clip, and its decode of it is exact.
 *
 * **A clip that will not load is [CanaryVerdict.NoClip] and still disables; a row that names no
 * clip yet is [CanaryVerdict.Unscored] and arms.** Neither is a Fail, but only one of them is a
 * defect: the first says a named asset is missing from the build, the second says T3 has not
 * finished. [CanaryVerdict]'s table is where the three consequences are stated.
 *
 * The verdict is never persisted (no preference, no per-(versionCode, pack) latch) and — since
 * 4.5.0 T2, defect 4 — it is scoped to the PACK rather than to the process: the tee is additive,
 * so a false negative costs one process of blank strips for ONE language and nothing typed.
 */
object PreviewCanary {

    fun run(recognizer: PreviewRecognizer, clip: FloatArray?, pack: StreamingPack): CanaryVerdict {
        // Two ways to have nothing to score, and they are DIFFERENT answers because they have
        // different causes and so different consequences (B1): a row with no canary sourced yet is
        // Unscored and arms, a NAMED clip that would not load is NoClip and disables. Order
        // matters — the `canary == null` rows are asked first, because for them the clip argument
        // is null by construction (FloatingBubbleService.kt:3209 maps a null canary to a null
        // clip) and the second check would otherwise answer for the first.
        val rule = pack.canary?.rule ?: return CanaryVerdict.Unscored
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
            // The STRIP, not `result.text` — the tokens-not-text rendering this app actually
            // paints. On a CJK-classified pack the two are different strings and only this one
            // has word boundaries; see [PreviewCanaryRule]'s docblock for the measurement.
            val shown = PreviewText.strip(recognizer.result(stream), pack)
            return if (passes(shown, rule)) CanaryVerdict.Pass(shown.length, decodes)
            else CanaryVerdict.Fail(shown.length, decodes)
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
