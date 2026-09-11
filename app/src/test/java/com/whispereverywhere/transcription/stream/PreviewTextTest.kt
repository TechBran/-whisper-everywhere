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

    // ------------------------------------------------------------- tokens, never text

    @Test fun theStripIsBuiltFromTokensAndForEnglishThatChangesNothing() {
        // The no-op half of the fix, pinned because it is the half a reviewer must be able to
        // check: for pure-ASCII English `RemoveSpaceBetweenCjk` never fires, so `text` and the
        // token concatenation differ by at most the leading space `normalize` trims anyway.
        val r = PreviewResult(
            "ONE TWO THREE",
            listOf(" ONE", " TWO", " THREE"),
            floatArrayOf(0.96f, 1.28f, 1.48f),
        )
        assertEquals("one two three", PreviewText.strip(r, en))
        assertEquals(PreviewText.normalize(r.text, en), PreviewText.strip(r, en))
    }

    @Test fun theStripKeepsKoreanWordSpacesThatTheAarsTextHasDeleted() {
        // `IsCJK` at 1.13.7 covers 0xA840-0xD7AF, which CONTAINS Hangul Syllables U+AC00-U+D7A3,
        // and `RemoveSpaceBetweenCjk` is called with NO language guard — so every Korean word
        // space is deleted from `result.text`. The tokens still carry them: SymbolTable rewrites a
        // leading ▁ to a space on the way out, and Korean's vocabulary is single characters with
        // ▁ standing alone as its own piece (id 3), so the space arrives as a token of its own.
        val ko = en.copy(
            language = "ko", emitsCase = true, emitsPunctuation = true, emitsDigits = true,
            normalizeLocale = Locale.forLanguageTag("ko"),
        )
        val r = PreviewResult(
            "안녕하세요저는",   // what Convert() returns: the space is gone
            listOf("안", "녕", "하", "세", "요", " ", "저", "는"),
            FloatArray(8) { 0.32f * (it + 1) },
        )
        assertEquals("안녕하세요 저는", PreviewText.strip(r, ko))
        assertEquals("and the text path is what it used to render", "안녕하세요저는", PreviewText.normalize(r.text, ko))
    }

    @Test fun theStripIsSpacedTheSameWayBeforeAndAfterADigit() {
        // The worse half of §6(6): the space SURVIVES before an ASCII digit, because 0x33 is
        // neither CJK nor punctuation. So `text` is not even consistently unspaced — it is
        // arbitrarily spaced, which is harder to defend in copy than either extreme.
        val ko = en.copy(language = "ko", emitsCase = true, emitsDigits = true)
        val r = PreviewResult(
            "회의시간을오후 3시로옮겨주세요",
            listOf("회", "의", " ", "시", "간", "을", " ", "오", "후", " ", "3", "시", "로", " ", "옮", "겨", " ", "주", "세", "요"),
            FloatArray(20) { 0.16f * (it + 1) },
        )
        assertEquals("회의 시간을 오후 3시로 옮겨 주세요", PreviewText.strip(r, ko))
    }

    @Test fun aResultWithNoTokensFallsBackToItsTextRatherThanBlankingTheStrip() {
        // Never observed on this AAR — the adapter fills tokens on every getResult() — but a blank
        // strip is worse than an inconsistently spaced one, and PreviewResult.EMPTY is a real
        // value the engine returns before the first decode.
        assertEquals("hello", PreviewText.strip(PreviewResult("HELLO", emptyList(), FloatArray(0)), en))
        assertEquals("", PreviewText.strip(PreviewResult.EMPTY, en))
    }

    @Test fun beforeFallsBackThroughTheStripSoTheTrimAndItsFallbackAgreeOnSpacing() {
        // A short-timestamp result cannot be trimmed honestly. The whole result is returned — and
        // through `strip`, so a CJK pack does not get a token-spaced trim on one commit and a
        // space-deleted whole text on the next.
        val ko = en.copy(language = "ko", emitsCase = true)
        val r = PreviewResult("안녕저는", listOf("안", "녕", " ", "저", "는"), floatArrayOf(0.5f))
        assertEquals("안녕 저는", PreviewText.before(r, 0.1f, ko))
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
