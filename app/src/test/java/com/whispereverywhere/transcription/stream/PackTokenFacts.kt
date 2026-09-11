package com.whispereverywhere.transcription.stream

import java.io.File

/**
 * The four copy flags on [StreamingPack], DERIVED from a pack's own `tokens.txt` rather than read
 * off a report — so that adding a language is a mechanical read and not a judgement call made once
 * and then copied forward wrong. (4.4.0's pinned comment claimed *"497 uppercase pieces, no
 * lowercase, no digits"*; the file says 495 uppercase-bearing, 3 lowercase-bearing and 2
 * digit-bearing, and 497 is the count of EMITTABLE pieces — a third number entirely. French has
 * identical counts, so that wording was one row away from being inherited.)
 *
 * ### What "emittable" excludes, and why each exclusion is a fact and not a preference
 *
 *  - **The three specials** `<blk>`, `<sos/eos>`, `<unk>`. They are in every vocabulary and no
 *    decode emits them. They are also the ONLY lowercase in the shipping English file, so counting
 *    them would make the English pack "cased" and switch off a fold that is correct.
 *  - **Byte-fallback pieces** `<0xNN>`. Present in the Chinese vocabularies; they carry a hex
 *    digit each, which would make every such pack "emit digits" on a technicality.
 *  - **The `#N` placeholders.** icefall appends `#0` and `#1` after the 500 BPE pieces; they are
 *    the only digit-bearing pieces in English and in French, and nothing decodes to them.
 *  - **The word marker U+2581 is stripped before classifying**, never counted. sherpa's
 *    `SymbolTable` rewrites a leading `▁` to a SPACE on the way out (`symbol-table.cc:191-200`),
 *    so it is a word boundary, not a character the model emits — and English's vocabulary contains
 *    a bare `▁` piece, which would otherwise classify as "punctuation-only".
 *
 * ### The one judgement, stated so it can be argued with
 *
 * **The apostrophe does not count as punctuation.** English has exactly one punctuation-only piece
 * and it is `'` (id 45); French has one too (id 7). It is a word-internal joiner — `DON'T` is a
 * word — the shipping install sentence already lives with it, and calling it punctuation would set
 * `emitsPunctuation` on the two rows whose copy the qualification table's own flag matrix marks
 * false (§4.1). Every other punctuation piece counts: ko's 32, et's 23, tr's 18.
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
    ) {
        /** Both cases present among the emittable pieces ⇒ folding would destroy meaning. */
        val emitsCase: Boolean get() = uppercaseEmittable > 0 && lowercaseEmittable > 0
        val emitsDigits: Boolean get() = digitsEmittable > 0
        /** Every punctuation-only piece except the apostrophe — see this object's docblock. */
        val emitsPunctuation: Boolean get() = punctuationOnly.any { it != "'" }
    }

    private val SPECIALS = setOf("<blk>", "<sos/eos>", "<unk>")
    private val BYTE_FALLBACK = Regex("<0x[0-9A-Fa-f]{2}>")
    private val PLACEHOLDER = Regex("#\\d+")
    private const val WORD_MARKER = '▁'

    fun of(tokens: File): Facts {
        val lines = tokens.readText(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        val pieces = lines.map { line ->
            val trimmed = line.trimEnd('\r')
            val cut = maxOf(trimmed.lastIndexOf(' '), trimmed.lastIndexOf('\t'))
            if (cut <= 0) trimmed else trimmed.substring(0, cut)
        }
        val emittable = pieces.filter {
            it !in SPECIALS && !BYTE_FALLBACK.matches(it) && !PLACEHOLDER.matches(it)
        }
        fun bodies(of: List<String>) = of.map { it.replace(WORD_MARKER.toString(), "") }
        val all = bodies(pieces)
        val emit = bodies(emittable)
        return Facts(
            lines = lines.size,
            emittable = emittable.size,
            uppercaseInFile = all.count { b -> b.any { it.isUpperCase() } },
            lowercaseInFile = all.count { b -> b.any { it.isLowerCase() } },
            digitsInFile = all.count { b -> b.any { it in '0'..'9' } },
            uppercaseEmittable = emit.count { b -> b.any { it.isUpperCase() } },
            lowercaseEmittable = emit.count { b -> b.any { it.isLowerCase() } },
            digitsEmittable = emit.count { b -> b.any { it in '0'..'9' } },
            punctuationOnly = emit.filter { b -> b.isNotEmpty() && b.none { it.isLetterOrDigit() } },
        )
    }
}
