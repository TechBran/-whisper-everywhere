package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class PreviewTextTest {

    private val en = StreamingPackCatalog.EN

    /** A pack whose vocabulary carries both cases — ko, et, tr and every Kroko build. */
    private val cased = en.copy(language = "xx", emitsCase = true)

    @Test fun normalizeTrimsAndLowercasesForASINGLECASEVocabulary() {
        // The shipping model emits ALL CAPS: its tokens.txt is 502 lines carrying 495
        // uppercase-bearing pieces, 3 lowercase-bearing (and all three are the specials <blk>,
        // <sos/eos>, <unk>, which no decode emits) and 2 digit-bearing (the `#0`/`#1` placeholders
        // icefall appends after the 500 BPE pieces). So there is no case to lose and the strip
        // must not shout. (4.4.0's comment here read "497 uppercase pieces, no lowercase, no
        // digits" — wrong in all three counts, and French has the SAME counts, so the wording
        // would have been copied forward: qualification table §4.)
        assertEquals("one two three four five", PreviewText.normalize(" ONE TWO THREE FOUR FIVE", en))
        assertEquals("", PreviewText.normalize("   ", en))
    }

    @Test fun normalizeKeepsCaseForAPackWhoseVocabularyCarriesIt() {
        // ko emits 26 uppercase + 25 lowercase Latin pieces; et 106 uppercase-bearing among 908
        // lowercase-bearing, and its cased pieces are high-frequency words (`▁Ja ▁Et ▁Aga ▁Ma`),
        // not rare proper nouns. Folding those paints `nba` over the `NBA` the model produced —
        // and a lowercased German noun reads as WRONG, not as rough.
        assertEquals("Seoul NBA", PreviewText.normalize(" Seoul NBA", cased))
        // The trim is unconditional; only the fold is the flag's.
        assertEquals("Ja", PreviewText.normalize("  Ja  ", cased))
    }

    @Test fun theFoldUsesThePacksOwnLocaleAndNotAlwaysUS() {
        // The one row where this is load-bearing rather than cosmetic. Turkish `İ` (U+0130) folds
        // to `i` under Locale.US and to a dotted `i̇` under `tr` — and `I` folds to `i` under US
        // and to the dotless `ı` under `tr`. `Locale.US` was chosen for English INPUT on purpose
        // ("never a Turkish dotless i"); that argument is about the input and says nothing about
        // Turkish OUTPUT, where US is the hazard rather than the guard.
        val tr = en.copy(language = "tr", normalizeLocale = Locale.forLanguageTag("tr"))
        assertEquals("ısparta", PreviewText.normalize("ISPARTA", tr))
        assertEquals("isparta", PreviewText.normalize("ISPARTA", en))
        assertEquals(Locale.US, en.normalizeLocale)
    }

    @Test fun beforeKeepsTheTokensThatEndBeforeTheCutAndJoinsByConcatenation() {
        // The AAR's tokens carry a LEADING SPACE (" F", "OUR" — rung 3 §2.2): a word is the run of
        // pieces from one leading-space piece to the next, so the join is a bare concatenation.
        val r = PreviewResult(
            "ONE TWO THREE FOUR FIVE",
            listOf(" ONE", " TWO", " THREE", " F", "OUR", " FI", "VE"),
            floatArrayOf(0.96f, 1.28f, 1.48f, 2.04f, 2.20f, 2.40f, 2.68f),   // rung 3 §5.3's word ends
        )
        assertEquals("one two three four", PreviewText.before(r, 2.30f, en))
        assertEquals("one two three f", PreviewText.before(r, 2.10f, en))
        assertEquals("", PreviewText.before(r, 0.5f, en))
        assertEquals("one two three four five", PreviewText.before(r, 9f, en))
    }

    @Test fun beforeFallsBackToTheWholeTextWhenTimestampsAreShort() {
        // A result whose timestamps do not cover its tokens cannot be trimmed honestly; the whole
        // (normalized) text is better than a silently truncated one.
        val r = PreviewResult("A B", listOf(" A", " B"), floatArrayOf(0.5f))
        assertEquals("a b", PreviewText.before(r, 0.1f, en))
    }
}
