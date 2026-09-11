package com.whispereverywhere.transcription.stream

import java.util.Locale

/** The strip's text rules — pure, pinned by PreviewTextTest. */
object PreviewText {

    /**
     * Trim, and then do whatever [StreamingPack.caseFold] says — a two-answer decision, never a
     * census of the vocabulary, and the fold's locale is the pack's own language rather than a
     * value the row carries.
     *
     * The shipping English model emits ALL CAPS (rung 1 §1.3: 495 uppercase-bearing pieces, and
     * the only lowercase in its tokens.txt is the three specials no decode emits), so folding is
     * lossless and the strip must not shout — [CaseFold.Fold], and this is byte-for-byte what
     * 4.4.1 rendered. A pack whose case MEANS something is [CaseFold.Keep]: folding ko, et or a
     * Kroko build paints `nba` over the `NBA` the model produced, a German reader reads a
     * lowercased noun as WRONG rather than rough, and **`zh` must be `Keep` even though its
     * vocabulary has no cased piece at all** — its acronyms arrive via byte fallback, which no
     * count of the token file can see (qualification table §4.1).
     *
     * **The locale is load-bearing on exactly one row, and it is not a value any row can type.**
     * `Locale.US` was chosen for English INPUT on purpose — the one spelling that can never
     * produce a Turkish dotless `ı` — and that reasoning is about the input and says nothing about
     * Turkish OUTPUT, where `İ` under `Locale.US` is the hazard rather than the guard. So the fold
     * asks the pack's LANGUAGE: Turkish folds Turkish because the row is `tr`, and English folds
     * exactly the characters 4.4.1 rendered because `forLanguageTag("en")` and `Locale.US` fold
     * identically — `String`'s own contract is locale-sensitive for `tr`, `az` and `lt` only, which
     * is what makes every other row's locale unobservable and this derivation safe. A row cannot
     * carry a fold locale that disagrees with its own language, because it carries none at all
     * ([CaseFold.Fold]); under the boolean this replaced, `tr` derived "cased" from its 34
     * uppercase pieces, the fold never ran on it, and the locale could not change one character on
     * any row in the table.
     *
     * The tag is parsed per call rather than cached on the row: it is one `LanguageTag.parse` in
     * front of the JDK's own `Locale` cache, on a path that repaints at the decode cadence (320 ms
     * apart at the fastest) on the previewer's executor, and a second copy of the answer is a
     * second thing that can disagree with [StreamingPack.language].
     */
    fun normalize(raw: String, pack: StreamingPack): String {
        val trimmed = raw.trim()
        return when (pack.caseFold) {
            CaseFold.Fold -> trimmed.lowercase(Locale.forLanguageTag(pack.language))
            CaseFold.Keep -> trimmed
        }
    }

    /**
     * The whole cumulative result as the strip should show it — built from [PreviewResult.tokens],
     * never from [PreviewResult.text].
     *
     * **They are not the same string, and the app used to render both.**
     * `SymbolTable::operator[]` rewrites a leading `▁` to a SPACE as it hands each piece back
     * (`symbol-table.cc:191-200`), while the recognizer's `Convert()` additionally runs
     * `RemoveSpaceBetweenCjk` over the assembled TEXT (`online-recognizer-transducer-impl.h:69`).
     * So spaces are PRESENT in the tokens and can be ABSENT from the text — and the live path
     * rendered `result.text` while a cap cut rendered [before]'s token concatenation. On a
     * CJK-classified pack that made the strip change its own spacing MID-UTTERANCE at every cap
     * cut, and on Korean it left the frozen prefix spaced while the live tail was not.
     *
     * **And `RemoveSpaceBetweenCjk` is not a Chinese-only rule.** At 1.13.7 `IsCJK` covers
     * `0xA840-0xD7AF`, which CONTAINS Hangul Syllables `U+AC00-U+D7A3`, and the call carries no
     * language guard — so every Korean word space is deleted from `text`. Except before an ASCII
     * digit (`0x33` is neither CJK nor punctuation), which leaves ARBITRARY spacing rather than a
     * convention anyone could defend in copy. Verified in the shipped binary:
     * `libsherpa-onnx-jni.so` carries the `1.13.7`, `query_head_dims` and U+3002 literals, so the
     * range read in source is the range that runs on device (qualification table §6(5), §6(6)).
     *
     * Building from tokens closes both, keeps `StreamingPackCopy.ADDITIVE`'s *"Words appear on the
     * bubble"* true verbatim, and is a NO-OP for the shipping pack: for pure-ASCII English
     * `RemoveSpaceBetweenCjk` never fires, so the two strings differ by at most the leading space
     * [normalize] trims anyway.
     *
     * A result with text but NO tokens has never been observed on this AAR — `SherpaPreviewRecognizer`
     * fills `tokens` from `getResult()` on every call — and were one to arrive, a correctly spaced
     * string beats a blank strip, so the text is used.
     */
    fun strip(result: PreviewResult, pack: StreamingPack): String =
        if (result.tokens.isEmpty()) normalize(result.text, pack)
        else normalize(result.tokens.joinToString(""), pack)

    /**
     * The tokens that END before [cutS] seconds, concatenated — the AAR's tokens carry a LEADING
     * SPACE (rung 3 §2.2: `" F", "OUR"`), so a bare concatenation reproduces the words and their
     * boundaries. Used at a cap cut with a retained tail: the tail's words re-appear from the
     * fresh stream, so they must not also stay frozen. Timestamps shorter than the tokens cannot
     * be trusted to trim; the whole result is returned instead of a silent truncation — through
     * [strip], so the fallback is spaced the same way as the trim it stands in for.
     */
    fun before(result: PreviewResult, cutS: Float, pack: StreamingPack): String {
        if (result.timestamps.size < result.tokens.size) return strip(result, pack)
        val sb = StringBuilder()
        for (i in result.tokens.indices) {
            if (result.timestamps[i] < cutS) sb.append(result.tokens[i])
        }
        return normalize(sb.toString(), pack)
    }
}
