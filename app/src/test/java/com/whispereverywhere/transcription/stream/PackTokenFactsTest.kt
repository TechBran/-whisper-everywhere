package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [PackTokenFacts] over the vocabulary SHAPES the qualification table §4.1 records — synthetic
 * files, because the route ships no second payload and a `tokens.txt` shape is all this derivation
 * reads.
 *
 * **Why these four shapes and not the shipping file.** `PreviewPackMetadataTest` already holds the
 * derivation against the real English `tokens.txt`; what it cannot reach is the row where the
 * derivation used to return the harmful answer. `zh` is *"1,426 single Han, ZERO multi-char Han · 2
 * digit-bearing · **0 lowercase, 0 uppercase** · byte-fallback `<0x00>`…`<0xFF>`"* and the table
 * marks its case column **"must not case-fold"**, because its own published hypotheses carry **31
 * uppercase Latin acronyms in 25,394 characters** that arrive THROUGH the byte fallback. A census
 * of the emittable case counts returns `0 > 0 && 0 > 0` = false for that file — and a fold gated on
 * that boolean paints `nba` where whisper types `NBA`, which is the exact harm the flag exists to
 * prevent. So the byte-fallback exclusion has to be scoped: it is a fact for digits and
 * punctuation, and the wrong answer for case.
 */
class PackTokenFactsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun facts(vararg pieces: String): PackTokenFacts.Facts {
        val file = File(tmp.newFolder("vocab-" + System.nanoTime()), "tokens.txt")
        file.writeText(pieces.mapIndexed { i, p -> "$p $i" }.joinToString("\n", postfix = "\n"))
        return PackTokenFacts.of(file)
    }

    /** The three specials every vocabulary opens with, and the only lowercase in the English file. */
    private val specials = arrayOf("<blk>", "<sos/eos>", "<unk>")

    // ------------------------------------------------------- the row a census gets wrong: zh

    @Test fun aByteFallbackVocabularyIsNeverProvablySafeToFoldEvenWithZeroCasedPieces() {
        // zh's shape: single Han pieces, no cased piece at all, and byte fallback.
        val zh = facts(
            *specials, "▁", "的", "是", "我", "会", "议",
            "<0x41>", "<0x42>", "<0xE4>", "<0xFF>",
        )
        // The census the old boolean read: ZERO and ZERO. `upper > 0 && lower > 0` is false, which
        // the gate spelled as "no case to lose" and folded.
        assertEquals(0, zh.uppercaseEmittable)
        assertEquals(0, zh.lowercaseEmittable)
        assertFalse("the census sees no case in this file", zh.vocabularyIsMixedCase)
        // And the fact the census cannot see, which is why the decision is not the census.
        assertTrue("byte fallback is present and is how this model writes Latin", zh.hasByteFallback)
        assertFalse(
            "so this file can NOT prove a fold is lossless — NBA arrives through <0x4E><0x42><0x41>",
            zh.foldIsProvablyLossless,
        )
        // The exclusion stays a fact for the flags it IS a fact for: the hex digits in the
        // byte-fallback pieces must not make this pack "emit digits".
        assertEquals(4, zh.digitsInFile)
        assertEquals(0, zh.digitsEmittable)
        assertFalse(zh.emitsDigits)
        assertFalse(zh.emitsPunctuation)
    }

    // ------------------------------------------------------- the row a census gets right: en/fr

    @Test fun aSingleCaseVocabularyWithNoByteFallbackIsProvablySafeToFold() {
        // en and fr: ALL CAPS BPE, the three specials, the apostrophe, the two `#N` placeholders.
        val en = facts(*specials, "▁", "▁THE", "▁QUICK", "OWN", "'", "#0", "#1")
        assertEquals("uppercase-bearing emittable pieces", 3, en.uppercaseEmittable)
        assertEquals("the only lowercase is the three specials", 0, en.lowercaseEmittable)
        assertFalse(en.vocabularyIsMixedCase)
        assertFalse(en.hasByteFallback)
        assertTrue("495 upper / 0 lower MUST fold, and the file proves it is free", en.foldIsProvablyLossless)
        // The three lowercase-bearing pieces in the WHOLE file are the specials, and the two
        // digit-bearing ones the placeholders — the whole reason English's flags are false.
        assertEquals(3, en.lowercaseInFile)
        assertEquals(2, en.digitsInFile)
        assertEquals(0, en.digitsEmittable)
        assertEquals("the apostrophe is the one punctuation piece", listOf("'"), en.punctuationOnly)
        assertFalse("and it is a word-internal joiner, not punctuation", en.emitsPunctuation)
    }

    // ------------------------------------------------------- the second joiner: ru

    @Test fun aVocabularyWhoseONLYMarkIsTheRussianHyphenDoesNotEmitPunctuation() {
        // (4.5.0 T1) ru's real `tokens.txt`, read at commit 31fa603e: 502 lines, ZERO uppercase,
        // 498 lowercase-bearing, the two `#N` placeholders, and **exactly one punctuation-only
        // piece — a standalone `-`**. The qualification table's flag matrix marks that row
        // `emitsPunctuation` FALSE, and for the same reason the apostrophe does not count:
        // `какой-то`, `по-русски` and `что-нибудь` are WORDS, and their hyphen is inside them.
        //
        // The judgement this object already states is "the apostrophe is a word-internal joiner",
        // and this is that judgement's other half rather than a new one. It costs the shipping
        // English row nothing — `en`'s only punctuation-only piece is `'`, so `en` derives false
        // either way — and it is scoped to rows whose ONLY mark is a joiner: `ko`'s 58 pieces and
        // `et`'s `.`/`?` still set the flag, because the strip will really carry those.
        val ru = facts(*specials, "▁какой", "то", "▁по", "русски", "-", "#0", "#1")
        assertEquals("the hyphen is the one punctuation-only piece", listOf("-"), ru.punctuationOnly)
        assertFalse("and it is a word-internal joiner, exactly like the apostrophe", ru.emitsPunctuation)
        assertEquals(0, ru.uppercaseEmittable)
        assertFalse("already-lowercase Cyrillic: nothing is mixed", ru.vocabularyIsMixedCase)
        assertTrue("so the fold is provably free — it is a no-op on this vocabulary", ru.foldIsProvablyLossless)
        assertFalse(ru.emitsDigits)
    }

    @Test fun aJoinerNextToARealMarkStillSetsTheFlag() {
        // The exemption is for a vocabulary whose ONLY marks are joiners, never a blanket
        // subtraction: a file carrying `-` AND a full stop emits punctuation, and the sentence
        // has to say so.
        val both = facts(*specials, "▁word", "-", ".")
        assertEquals(listOf("-", "."), both.punctuationOnly)
        assertTrue(both.emitsPunctuation)
    }

    // ------------------------------------------------------- the rows that must Keep: ko/et

    @Test fun aMixedCaseVocabularyIsNeverProvablySafeToFold() {
        // et's shape: ordinary high-frequency words carrying case (`▁Ja ▁Et ▁Aga`), 9 standalone
        // digits and 23 punctuation marks — the first row in the table to set both booleans true.
        val et = facts(*specials, "▁Ja", "▁Et", "▁aga", "▁ma", "7", ".", "?", "-")
        assertTrue(et.vocabularyIsMixedCase)
        assertFalse(et.hasByteFallback)
        assertFalse("case that means something is never folded", et.foldIsProvablyLossless)
        assertTrue(et.emitsDigits)
        assertTrue(et.emitsPunctuation)
    }

    // ------------------------------------------------------- the one row a human overrides: tr

    @Test fun theTurkishRowIsTheOneWhereTheSuggestionIsOverriddenAndTheOverrideIsStated() {
        // 448 lower / 34 upper is MIXED, so the sufficiency test suggests Keep — and the
        // qualification table rules `Fold` on that row anyway, calling tr the one row where the
        // locale is load-bearing. That override is the only direction that can cost a character,
        // which is
        // why the catalogue asserts the DECISION on the row and never this property.
        val tr = facts(*specials, "▁bir", "▁iki", "▁İzmir", "▁Ankara", ".", "?")
        assertTrue(tr.vocabularyIsMixedCase)
        assertFalse("the file cannot prove the fold is free, and it is not", tr.foldIsProvablyLossless)
        // But the row folds, and in its own language — the decision is hand-authored against this
        // census, the locale is derived from `language` and is not a value the row can get wrong.
        val row = StreamingPackCatalog.EN.copy(
            language = "tr",
            caseFold = CaseFold.Fold,
            emitsPunctuation = true,
        )
        assertEquals("ısparta", PreviewText.normalize("ISPARTA", row))
        assertTrue(tr.emitsPunctuation)
        assertFalse("the Turkish vocabulary has no digit, so its canary must be word-form", tr.emitsDigits)
    }

    // ------------------------------------------------------- the parse itself

    @Test fun aTabSeparatedVocabularyParsesRatherThanCollapsingToOnePiece() {
        // The lyr ja/pt exports separate piece from id with a TAB. A row that silently parsed its
        // whole vocabulary as one piece would report every flag false and look fine.
        val file = File(tmp.newFolder("tabbed"), "tokens.txt")
        file.writeText("<blk>\t0\n▁Ja\t1\n▁aga\t2\n.\t3\n")
        val f = PackTokenFacts.of(file)
        assertEquals(4, f.lines)
        assertEquals(3, f.emittable)
        assertTrue(f.vocabularyIsMixedCase)
        assertEquals(listOf("."), f.punctuationOnly)
    }

    // ------------------------------------------- the NOUN: words, or characters (4.5.0 Task 4)

    @Test fun aWordMarkedVocabularyPROVESItsStripsUnitIsWords() {
        // The five word-marked rows' shape: pieces that START a word carry the marker, the
        // unmarked ones are BPE tails, and a space reaches the strip exactly where a marker does.
        val de = facts(*specials, "▁", "▁HABEN", "▁MAN", "CH", "E", "ST")
        assertTrue(de.wordMarkedEmittable > 0)
        assertTrue(de.wordsAreProvablyTheUnit)
    }

    @Test fun aVocabularyWhoseONLYBoundaryIsTheBareMarkerCannotPROVEItsUnit() {
        // ko's shape: every content piece a single character, NOT ONE of them marked, and a bare
        // marker in the file. Its live words are real — nine of them, measured — but the space
        // comes from a token the DECODE emits once per word, and no census can see a decode. So
        // the answer here is "cannot prove", which is not the same answer as "no words".
        val ko = facts(*specials, "▁", "스", "페", "인", "1", ".", "?")
        assertEquals(0, ko.wordMarkedEmittable)
        assertTrue(ko.bareWordMarker)
        assertEquals(6, ko.unmarkedSingleCharEmittable)
        assertFalse(ko.wordsAreProvablyTheUnit)
    }

    @Test fun aVocabularyOfUnmarkedSingleCharactersBESIDEMarkedWordsIsTwoUnitsAtOnce() {
        // zh-en's shape, and the reason the noun is a THREE-row question rather than a boolean:
        // marked Latin word-starts sit beside unmarked single characters, so one row puts
        // characters AND words on the strip and no single noun is true of both halves.
        val zh = facts(*specials, "▁", "▁ALWAYS", "▁ONE", "的", "是", "我", "会", "议", "2")
        assertTrue("the Latin half is marked", zh.wordMarkedEmittable > 0)
        assertTrue("and the character half dominates", zh.unmarkedSingleCharEmittable * 2 > zh.emittable)
        assertFalse(zh.wordsAreProvablyTheUnit)
    }

    @Test fun theWordMarkerIsStrippedBeforeClassifyingSoABareMarkerIsNotPunctuation() {
        // English's vocabulary contains a bare `▁` piece. `SymbolTable` rewrites a leading one to a
        // SPACE on the way out, so it is a word boundary and not a character the model emits — left
        // in, it classifies as "punctuation-only" and sets `emitsPunctuation` on the shipping row.
        val f = facts(*specials, "▁", "▁THE")
        assertEquals("a bare word marker is not a punctuation piece", emptyList<String>(), f.punctuationOnly)
        assertFalse(f.emitsPunctuation)
    }
}
