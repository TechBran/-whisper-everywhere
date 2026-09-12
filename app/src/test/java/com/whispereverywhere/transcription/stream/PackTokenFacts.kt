package com.whispereverywhere.transcription.stream

import java.io.File

/**
 * The copy flags on [StreamingPack], DERIVED from a pack's own `tokens.txt` rather than read off a
 * report — so that adding a language is a mechanical read and not a judgement call made once and
 * then copied forward wrong. (4.4.0's pinned comment claimed *"497 uppercase pieces, no lowercase,
 * no digits"*; the file says 495 uppercase-bearing, 3 lowercase-bearing and 2 digit-bearing, and
 * 497 is the count of EMITTABLE pieces — a third number entirely. French has identical counts, so
 * that wording was one row away from being inherited.)
 *
 * **[StreamingPack.emitsPunctuation] and [StreamingPack.emitsDigits] are derived; [CaseFold] is
 * only SUGGESTED.** A `tokens.txt` cannot compute the case decision — English is 495 uppercase / 0
 * lowercase and must fold, `zh` is 0 / 0 and must not — so what this object offers there is a
 * one-directional sufficiency test ([Facts.foldIsProvablyLossless]) and the count it used to
 * mistake for an answer, renamed to [Facts.vocabularyIsMixedCase].
 *
 * ### What "emittable" excludes, and the FLAGS each exclusion is a fact for
 *
 * An exclusion is a fact about a flag, not about the file, so each one is scoped to the flags it
 * answers. Getting that wrong is how a census returns a harmful value: see
 * [Facts.foldIsProvablyLossless].
 *
 *  - **The three specials** `<blk>`, `<sos/eos>`, `<unk>` — a fact for **all** of them. They are in
 *    every vocabulary and no decode emits them. They are also the ONLY lowercase in the shipping
 *    English file, so counting them would make the English pack "cased" and switch off a fold that
 *    is correct.
 *  - **Byte-fallback pieces** `<0xNN>` — a fact for **digits and punctuation only**, and the WRONG
 *    answer for case. They carry a hex digit each, which would make every such pack "emit digits"
 *    on a technicality, and the qualification table's zh row marks both those flags false. But
 *    byte fallback is how that model writes Latin at all: its own published hypotheses carry **31
 *    uppercase acronyms in 25,394 characters** (NBA/PPT/TV), and the table marks that row *"must
 *    not case-fold"*. So the case decision reads [Facts.hasByteFallback] rather than the emittable
 *    case counts, which for zh are 0 and 0.
 *  - **The `#N` placeholders** — a fact for **digits**. icefall appends `#0` and `#1` after the 500
 *    BPE pieces; they are the only digit-bearing pieces in English and in French, and nothing
 *    decodes to them.
 *  - **The word marker U+2581 is stripped before classifying**, never counted — a fact for
 *    **punctuation and case**. sherpa's `SymbolTable` rewrites a leading `▁` to a SPACE on the way
 *    out (`symbol-table.cc:191-200`), so it is a word boundary, not a character the model emits —
 *    and English's vocabulary contains a bare `▁` piece, which would otherwise classify as
 *    "punctuation-only".
 *
 * ### The one judgement, stated so it can be argued with
 *
 * **A word-INTERNAL joiner does not count as punctuation.** English has exactly one
 * punctuation-only piece and it is `'` (id 45); French has one too (id 7); **Russian has one and it
 * is a standalone `-`** (`какой-то`, `по-русски`). They are joiners — `DON'T` is a word — the
 * shipping install sentence already lives with the apostrophe, and calling either punctuation would
 * set `emitsPunctuation` on three rows the qualification table's own flag matrix marks false
 * (§4.1). The set is [JOINERS], with the reason for each member beside it. Every other punctuation
 * piece counts, and a joiner sitting next to a real mark counts too: ko's 58 pieces, et's 23,
 * tr's 18.
 *
 * A `tokens.txt` is `<piece><separator><id>` per line. The separator is a SPACE in every file this
 * repo ships or has audited, except the lyr Japanese/Portuguese exports, which use a TAB — both are
 * accepted here, because a row that silently parsed its whole vocabulary as one piece would report
 * every flag false and look fine.
 */
object PackTokenFacts {

    /**
     * Two censuses, deliberately not one. The WHOLE-FILE counts are what a reader grepping
     * `tokens.txt` will see and are what 4.4.0's pinned comment claimed (wrongly); the EMITTABLE
     * counts are what the flags derive from, and they differ precisely because the three specials
     * are the only lowercase in the English file and the two `#N` placeholders its only digits.
     * Asserting only one of the two would leave the other free to be re-stated wrong.
     */
    data class Facts(
        val lines: Int,
        val emittable: Int,
        val uppercaseInFile: Int,
        val lowercaseInFile: Int,
        val digitsInFile: Int,
        val uppercaseEmittable: Int,
        val lowercaseEmittable: Int,
        val digitsEmittable: Int,
        val punctuationOnly: List<String>,
        val hasByteFallback: Boolean,
        val wordMarkedEmittable: Int,
        val bareWordMarker: Boolean,
        val unmarkedSingleCharEmittable: Int,
    ) {
        /**
         * Both cases present among the emittable pieces. **This is EVIDENCE, not the decision** —
         * it is named for what it counts because the value it used to be called (`emitsCase`) is a
         * different question, and reading one as the other is how `tr` lost its locale and `zh`
         * got folded. See [foldIsProvablyLossless].
         */
        val vocabularyIsMixedCase: Boolean get() = uppercaseEmittable > 0 && lowercaseEmittable > 0

        /**
         * Whether a `tokens.txt` alone PROVES that folding this pack loses nothing — the most a
         * census can honestly say, and it says it **only in the safe direction**: `false` means
         * "this file cannot prove it", never "folding is wrong".
         *
         * A fold is provably lossless when the vocabulary is single-case **and** carries no byte
         * fallback. Both conjuncts earn their place on a shipped row:
         *
         *  - Single-case alone is not enough. **`zh` is 0 emittable uppercase / 0 lowercase** and
         *    must not fold, because byte fallback writes its Latin acronyms and the census cannot
         *    see them. A rule that read only the counts would return "safe to fold" for exactly the
         *    row whose harm — `nba` where whisper types `NBA` — is the reason a flag exists.
         *  - Mixed case alone is not the answer either, in the other direction: **English is 495
         *    uppercase-bearing / 0 lowercase and MUST fold.** Its ALL CAPS is a property of the
         *    LibriSpeech BPE, not a case distinction, which is why the answer is a fold decision on
         *    the row rather than a count.
         *
         * Against the qualification table §4.1 this reproduces the ruling for **fifteen of the
         * sixteen rows** (the table has sixteen: en fr de ru id zh zh-en ko et es it nl pt·lyr
         * pt·Kroko tr ja, and the three groups below enumerate 7 + 1 + 7 of them):
         * `Fold` for en/fr/de/ru/id/zh-en/pt(lyr), `Keep` for zh (byte fallback)
         * and for ko/et/es/it/nl/pt(Kroko)/ja (mixed). **`tr` is the one row it does not** — 448
         * lower / 34 upper is mixed, so this suggests `Keep`, and the table rules `Fold` on that
         * row anyway — folding Turkish, because the row's language is what the fold asks. That override is the only direction that can cost a character, so the row taking
         * it carries the reason in writing and this property is deliberately not the thing the
         * catalogue asserts equal.
         */
        val foldIsProvablyLossless: Boolean get() = !vocabularyIsMixedCase && !hasByteFallback

        val emitsDigits: Boolean get() = digitsEmittable > 0
        /** Every punctuation-only piece except a word-internal [JOINERS] — see this object's docblock. */
        val emitsPunctuation: Boolean get() = punctuationOnly.any { it !in JOINERS }

        /**
         * Whether a `tokens.txt` alone PROVES the strip's unit is [StripUnit.WORDS] — and, like
         * [foldIsProvablyLossless], it says so **only in the safe direction**: `false` means "this
         * file cannot prove it", never "words do not appear".
         *
         * A space reaches the strip exactly where a `▁` does (`SymbolTable::operator[]` rewrites a
         * leading marker to a space, and [PreviewText.strip] builds from the tokens), so the
         * question is which pieces carry one. Two conjuncts, and each excludes a real row:
         *
         *  - **[wordMarkedEmittable] > 0.** Without a marked piece the only space the vocabulary
         *    can produce is the bare marker, and whether a decode emits it is a fact about the
         *    DECODE. **`ko` is that row**: zero marked pieces, a bare `▁` at id 3, and its live
         *    words are real — measured, nine of them (`PreviewCanaryClipsTest`'s ko row) — which
         *    is exactly why this property answers "cannot prove" rather than "no".
         *  - **characters are not the dominant unit.** **`zh-en` is that row**: 327 marked pieces,
         *    all of them Latin, against 5,755 Han-bearing pieces of which **not one carries a
         *    marker** and **every one is a single character**. Its Chinese arrives as characters
         *    with no boundary and its English as marked words, which is one row and two units.
         *
         * The five word-marked rows clear both by a distance: 228-358 marked pieces each, and
         * unmarked single characters are 5-9% of their emittable vocabulary against ko's 100% and
         * zh-en's 92.5%.
         */
        val wordsAreProvablyTheUnit: Boolean
            get() = wordMarkedEmittable > 0 && unmarkedSingleCharEmittable * 2 < emittable
    }

    /**
     * The word-INTERNAL marks: a piece that joins one word rather than ending a sentence, and
     * therefore does not make [Facts.emitsPunctuation] true on its own. Two members, one per
     * language family that has one, and each is a whole vocabulary's only mark:
     *
     *  - `'` — English (id 45) and French (id 7), where `DON'T` and `L'EAU` are words. The
     *    shipping install sentence already lives with it.
     *  - `-` — **Russian (4.5.0 T1)**, whose real `tokens.txt` at commit `31fa603e` carries
     *    exactly one punctuation-only piece and it is a standalone hyphen. `какой-то`,
     *    `по-русски` and `что-нибудь` are words; the mark is inside them. The qualification
     *    table's flag matrix (§4.1) marks that row `emitsPunctuation` **false** and names the
     *    reason: *"its one joiner is `-` (какой-то) where English has `'`"*.
     *
     * The exemption is per PIECE, never a subtraction from the row: a vocabulary carrying a
     * joiner **and** a full stop still emits punctuation (`ko`'s 58 pieces, `et`'s 23, `tr`'s
     * 18), because the strip will really carry those marks and a sentence has to say so.
     */
    private val JOINERS = setOf("'", "-")

    private val SPECIALS = setOf("<blk>", "<sos/eos>", "<unk>")
    private val BYTE_FALLBACK = Regex("<0x[0-9A-Fa-f]{2}>")
    private val PLACEHOLDER = Regex("#\\d+")
    private const val WORD_MARKER = '▁'

    /**
     * The `<piece>` column of every non-blank line, in file order — what [of] censuses and what
     * [spellable] walks. Split out (4.5.0 T3) so the canary's clip gate reads the vocabulary
     * through the same parser as the copy flags: one place that knows a `tokens.txt` line is
     * `<piece><SPACE-or-TAB><id>`, so a second reader cannot disagree with the first about where
     * a piece ends.
     */
    fun piecesOf(tokens: File): List<String> =
        tokens.readText(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { line ->
            val trimmed = line.trimEnd('\r')
            val cut = maxOf(trimmed.lastIndexOf(' '), trimmed.lastIndexOf('\t'))
            if (cut <= 0) trimmed else trimmed.substring(0, cut)
        }

    /**
     * Can this vocabulary spell [target] EXACTLY, as some concatenation of its own pieces?
     *
     * This is the mechanical half of *"a canary clip's expected output must be checkable against
     * `tokens.txt`, never by ear"*. A rule may expect a rendering only if the model can emit it,
     * and a 500-piece BPE emits a word as one piece or as several — `▁HABEN` is whole in the
     * German vocabulary while `▁CAMPINGBEREICHE` is not, and both arrive on the strip as one
     * space-delimited word, so *"is it a whole piece"* is the wrong question and *"can this
     * vocabulary spell it"* is the right one.
     *
     * A DP over the piece set rather than a greedy walk: BPE segmentation is not greedy-decidable
     * (a longest-first match can consume a prefix that leaves an unspellable tail), and a greedy
     * reader would answer "no" for words the model demonstrably produced.
     */
    fun spellable(pieces: Set<String>, target: String): Boolean {
        if (target.isEmpty()) return false
        val longest = pieces.maxOfOrNull { it.length } ?: return false
        val reach = BooleanArray(target.length + 1)
        reach[0] = true
        for (end in 1..target.length) {
            for (start in maxOf(0, end - longest) until end) {
                if (reach[start] && target.substring(start, end) in pieces) {
                    reach[end] = true
                    break
                }
            }
        }
        return reach[target.length]
    }

    fun of(tokens: File): Facts {
        val pieces = piecesOf(tokens)
        val emittable = pieces.filter {
            it !in SPECIALS && !BYTE_FALLBACK.matches(it) && !PLACEHOLDER.matches(it)
        }
        fun bodies(of: List<String>) = of.map { it.replace(WORD_MARKER.toString(), "") }
        val all = bodies(pieces)
        val emit = bodies(emittable)
        return Facts(
            lines = pieces.size,
            emittable = emittable.size,
            uppercaseInFile = all.count { b -> b.any { it.isUpperCase() } },
            lowercaseInFile = all.count { b -> b.any { it.isLowerCase() } },
            digitsInFile = all.count { b -> b.any { it in '0'..'9' } },
            uppercaseEmittable = emit.count { b -> b.any { it.isUpperCase() } },
            lowercaseEmittable = emit.count { b -> b.any { it.isLowerCase() } },
            digitsEmittable = emit.count { b -> b.any { it in '0'..'9' } },
            punctuationOnly = emit.filter { b -> b.isNotEmpty() && b.none { it.isLetterOrDigit() } },
            // The word-boundary census, read off the PIECES rather than the bodies — this is the
            // one question the marker is the answer to rather than noise, so it is the one place
            // it must not be stripped. A piece that STARTS a word carries the marker and is not
            // the bare marker itself; the bare marker is the boundary, not a word.
            wordMarkedEmittable = emittable.count {
                it.contains(WORD_MARKER) && it != WORD_MARKER.toString()
            },
            bareWordMarker = pieces.any { it == WORD_MARKER.toString() },
            unmarkedSingleCharEmittable = emittable.count {
                !it.contains(WORD_MARKER) && it.length == 1
            },
            // Read off the WHOLE file, not the emittable set — byte fallback is excluded from
            // `emittable` precisely so it cannot inflate the digit count, so the case decision has
            // to ask the file directly or it would be asking a set defined to hide the answer.
            hasByteFallback = pieces.any { BYTE_FALLBACK.matches(it) },
        )
    }
}
