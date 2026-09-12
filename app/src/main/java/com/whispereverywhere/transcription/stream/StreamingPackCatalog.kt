package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.CanaryAudio

/**
 * One pinned file of a streaming pack: the flat name it takes ON DISK, its EXACT byte count, its
 * sha256, and — where upstream does not keep it flat — the [path] it lives at in the pinned commit.
 *
 * @property name the ONE flat name this file has everywhere the app touches it: the entry in the
 *   pack module's asset directory, the file `StreamingPackInstall` writes under `filesDir`, the
 *   name `SherpaPreviewRecognizer` opens, and the second column of the `.installed` marker. It is
 *   held to `[A-Za-z0-9._-]` by `StreamingPackCatalogTest`.
 * @property path where the same bytes live in the pinned upstream commit, RELATIVE to
 *   [StreamingPack.baseUrl] — defaulting to [name], because every file of the shipping English
 *   pack is flat at its repo root and the two spellings coincide there.
 *
 *   **Why this is a second field and not a cleverer `name`.** Two of the qualification table's six
 *   new rows are not flat upstream: German's four files are under `exp/epoch-30/` and
 *   `lang_bpe_500/` **with commas in the ONNX filenames**, and the bilingual zh-en row's are under
 *   `exp/32/` and `data/lang_char_bpe/`. `urlOf` used to be `baseUrl + name`, so such a row could
 *   either download (put the path in `name`, and the installer then writes a subdirectory that
 *   does not exist) or load (keep `name` flat, and the download 404s) — never both. The split also
 *   keeps the comma where it is harmless: in a URL, never in an AAB asset entry, which is the one
 *   of the three spellings this repo cannot test without a 5.5 GB `bundleRelease`.
 */
data class PackFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
    val path: String = name,
)

/**
 * What the strip does with the case the model emitted — the decision [PreviewText.normalize]
 * branches on. **It carries no locale**: a fold locale is not a decision anyone gets to write, it
 * is the row's own language, and [Fold] says why.
 *
 * ### Why this is a type and not the boolean it replaced
 *
 * The qualification table's `emitsCase` column (§4.1) carries FOUR values — `false`, `TRUE`,
 * `partial` and `must not case-fold` — and none of them is "the emittable vocabulary has both
 * cases". That census does not answer the question the strip asks, and two shipped rows prove it in
 * opposite directions: **English is 495 uppercase-bearing / 0 lowercase and MUST fold** (its ALL
 * CAPS is a property of the LibriSpeech BPE, not a case distinction anyone typed), while **`zh` is
 * 0 / 0 and must NOT** — its acronyms arrive through byte fallback, and its own published
 * hypotheses carry 31 uppercase Latin acronyms in 25,394 characters, so a fold paints `nba` where
 * whisper types `NBA`. A boolean over the census returns the harmful answer for exactly the row
 * whose harm motivated having a flag at all.
 *
 * So the four values collapse to two ANSWERS:
 *
 * | table value | rows | this type |
 * |---|---|---|
 * | `false` | en fr de ru id zh-en pt(lyr) | [Fold] — single-case vocabulary, nothing to lose |
 * | `partial` (448 lower / 34 upper) | tr | [Fold] — **and it folds Turkish because the ROW is `tr`** |
 * | `TRUE` | ko et es it nl pt(Kroko) ja | [Keep] — the case means something |
 * | `must not case-fold` | zh | [Keep] — byte fallback emits Latin the census cannot see |
 *
 * A [Keep] row has no locale to get wrong, and a [Fold] row has none to get wrong either, because
 * it states none: the fold asks [StreamingPack.language]. Under the boolean this type replaced, the
 * locale was read only on the fold branch while `tr` derived `true` from its 34 uppercase pieces,
 * so the field could not change one character on any of the sixteen rows.
 *
 * **`PackTokenFacts` derives this DECISION from a `tokens.txt` for fifteen of the sixteen** (see
 * `PackTokenFacts.Facts.foldIsProvablyLossless`, which only ever suggests in the safe direction).
 * `tr` is the one row where a human overrode the suggestion — the table rules [Fold] against a
 * mixed census — and an override toward folding is the only direction that can cost a character,
 * so the row that takes it owes a written reason beside it. **That one override is the whole of
 * what is hand-authored here; the locale is mechanical on all sixteen.**
 */
sealed interface CaseFold {
    /**
     * Fold the strip to lowercase **in the row's own language** — `Locale.forLanguageTag(language)`,
     * applied by [PreviewText.normalize] and written on no row.
     *
     * ### Why the locale is derived rather than a field on this answer
     *
     * It was a field for one round, and a field is a value that can be WRONG. Nothing in the suite
     * could tell `Fold(Locale.US)` on a `tr` row from a deliberate choice, and such a row renders
     * `i̇stanbul` — `i` + U+0307, a stray mark on the strip — for every capital `İ` it hears. The
     * controller brief's sentence is *"it is wrong for Turkish OUTPUT, and Turkish must not ship
     * until this lands"*, and a locale a row can spell wrong cashes half of it: carrying the locale
     * on the folding branch made the RIGHT spelling possible, and deriving it makes the wrong one
     * unspellable.
     *
     * **And the row's language is all the locale ever was.** `java.lang.String` folds
     * locale-sensitively for **`tr`, `az` and `lt` only**, so on fifteen of the table's sixteen
     * rows every locale produces the same characters, and on `tr` — the sixteenth — the only right
     * answer IS the row's own language. The table's `normalizeLocale` column (§4.1) says that, and
     * says it sixteen times out of sixteen: every row is either its own language spelled out
     * (`id ko et es it nl pt tr ja`) or `Locale.US` on a row that folds identically to it. So this
     * is the mechanical half of the case question, against a decision that needs a human on one row
     * in sixteen — and `en` still renders 4.4.1's characters exactly, because
     * `Locale.forLanguageTag("en")` and `Locale.US` fold the same.
     *
     * The one thing this removes, stated because it is a real language and not a hypothetical: a
     * row whose orthography wants Turkish folding while its own tag does not get it from the JDK
     * (Crimean Tatar `crh` writes `İ`/`ı` and folds like ROOT) can no longer be fixed by a value on
     * the row. It needs a third answer on this type, carrying its locale and its reason — the right
     * price, because that is a decision and not a spelling. None of the table's sixteen rows needs
     * one.
     */
    data object Fold : CaseFold

    /** Emit the case the model produced. Folding it would destroy output the user wanted. */
    data object Keep : CaseFold
}

/**
 * WHAT THE STRIP IS MADE OF, for one pack — the NOUN every sentence about that pack has to get
 * right. *"Words appear on the bubble"* is the app's central promise
 * ([StreamingPackCopy.ADDITIVE]), and it is a claim about the pack rather than about the feature:
 * a model whose vocabulary is single Han characters puts characters there.
 *
 * ### How it is read, and where the file stops being able to answer
 *
 * A space reaches the strip exactly where a `▁` does — `SymbolTable::operator[]` rewrites a
 * leading marker to a SPACE as it hands each piece back, and [PreviewText.strip] builds from those
 * tokens — so the question is which pieces carry one. `PackTokenFacts` counts that
 * (`wordsAreProvablyTheUnit`), and like the case decision it answers **only in the safe
 * direction**: five of the seven rows it settles, and two it cannot.
 *
 * | row | marked pieces | unmarked single chars | answer, and what decided it |
 * |---|---|---|---|
 * | en fr de ru id | 338 238 228 261 358 | 5-9% of emittable | [WORDS] — the FILE proves it |
 * | ko | **0** | **100%** | [WORDS] — the MEASURED decode: the bare `▁` at id 3 comes back nine times, once per word |
 * | zh-en | 327, every one Latin | **92.5%**, and not one of the 5,755 Han-bearing pieces is marked | [CHARACTERS_AND_WORDS] — both halves off the file |
 *
 * **Korean is the row that makes this a decision and not a census.** Zero pieces carry the marker,
 * so the only space its vocabulary can produce is the bare marker — and whether a decode emits one
 * is a fact about the DECODE, which no token file can see. A space-less script's vocabulary looks
 * identical. What settles it is the measurement recorded in `PreviewCanaryClipsTest`: nine bare
 * markers in one utterance, one per word, `[" ", "스", "페", "인", " ", "사", …]`. (That same
 * measurement is why the strip is built from tokens at all: `RemoveSpaceBetweenCjk` deletes every
 * one of those spaces from `result.text`, whose Hangul comes back as a single run.)
 *
 * **There is deliberately no third value**, though the qualification table prices two rows that
 * would need one: the monolingual `zh` (single Han pieces, no Latin in the vocabulary at all — its
 * acronyms arrive through byte fallback) and `ja` (a character vocabulary with zero `▁`). Neither
 * is a row here, and a value no row takes is a branch no test exercises — the same speculative
 * generality a second canary rule shape was reset and thrown away for (4.5.0 T3). A `Characters`
 * value is what those rows cost when one of them lands.
 */
enum class StripUnit {
    /**
     * Space-delimited words, because pieces that start a word carry the marker (or, on `ko`, the
     * boundary arrives as its own token). *"Words appear"* is true verbatim.
     */
    WORDS,

    /**
     * **One row, two units** — the bilingual `zh-en` export. Its 5,755 Han-bearing pieces are
     * every one a single character and not one of them is marked, so Chinese arrives as a run of
     * characters; its 327 marked pieces are every one Latin, so English in the same utterance
     * arrives as words. No single noun is true of both halves, which is why the sentence for this
     * row names both ([StreamingPackCopy.stripNote]).
     */
    CHARACTERS_AND_WORDS,
}

/**
 * WHAT A SENTENCE ABOUT ONE PACK'S STRIP MAY ASSERT — and the whole of what it can see
 * ([StreamingPackCopy.stripNote] takes this and a language word, never a [StreamingPack]).
 *
 * The rule this type exists to keep is the one three review rounds cost on the tier axis: *a
 * sentence selected by a decision over N facts may assert only the NECESSITY of those N facts*
 * ([StreamingPackCopy.NO_TIER_SUBTITLE]'s KDoc). A function handed the pack could reach its WER,
 * its corpus, its licence cell or its cadence — the 0.401 s measurement is a 320 ms number and
 * **two rows emit at half that rate** — so what the sentence is handed is exactly the four facts
 * its clauses are about, and nothing a later edit could be tempted by.
 *
 * Four of the five fields are the row's own, read off its `tokens.txt` by `PackTokenFacts`. The
 * fifth is not a token fact at all, and says so.
 *
 * @property unit whether the strip carries words, or characters, or both — [StripUnit].
 * @property keepsCase [CaseFold.Keep]: the strip is not folded, so the case is the model's own.
 * @property punctuation [StreamingPack.emitsPunctuation] — the strip can carry `.` `?` `,` `!`.
 * @property digits [StreamingPack.emitsDigits] — a numeral can appear on the strip.
 * @property capitalisesEveryNoun **an orthography fact about the LANGUAGE, not about the pack**,
 *   and the one clause in the sentence no token file can supply. German writes every noun with a
 *   capital, so a German reader meets `festivals` and `campingbereiche` on the strip and reads
 *   them as WRONG rather than as rough — which is a different reaction from the one *"no
 *   capitals"* prepares an English or a French reader for (qualification table §4.1: *"one row of
 *   its own … the sentence must say 'no capitals, **including nouns**'"*). It is derived from
 *   [StreamingPack.language] against [CAPITALISES_EVERY_NOUN] rather than passed in by a caller,
 *   because a call site that chooses a clause is how two surfaces come to describe one pack
 *   differently — the defect `StreamingPackCopy` is one table for.
 */
data class StripShape(
    val unit: StripUnit,
    val keepsCase: Boolean,
    val punctuation: Boolean,
    val digits: Boolean,
    val capitalisesEveryNoun: Boolean,
) {
    companion object {
        /**
         * The languages that capitalise EVERY noun, so that a lowercase strip reads as an error
         * in the orthography rather than as a missing flourish.
         *
         * **`de` is the only member**, and the only one this catalogue can reach: German (with
         * Luxembourgish, which no row uses) is the last standard orthography to capitalise common
         * nouns. It is not "languages with capitals" — French, Indonesian and English all have
         * capitals, and their readers are told *"no capitals"* without needing a rider, because a
         * lowercase French noun is not a spelling mistake. Russian and Korean take no rider for a
         * stronger reason: `ru`'s vocabulary has zero uppercase pieces, so the fold is a no-op on
         * its characters, and `ko` does not fold at all.
         */
        val CAPITALISES_EVERY_NOUN = setOf("de")
    }
}

/**
 * A streaming-previewer model pack: four raw files, delivered EITHER by a Play asset pack or —
 * where there is no Play to talk to — from ONE immutable Hugging Face commit (spec §6; the
 * 2026-09-10 amendment). Never the release tarball (310 MB of fp32 + int8 + wavs under a 73 MB
 * badge), never a `WhisperModel` row, never a `pairedArtifact` — the pack is a sibling of the TTS
 * voice, keyed by LANGUAGE, with no tier identity (spec §6, "what a non-whisper pack must NOT
 * touch").
 *
 * @property packName the Play asset pack that carries these four files, and therefore also the
 *   name of the directory they arrive in: Play strips a `#group_<g>` suffix on delivery and this
 *   pack has none (one untargeted variant, every device), so the delivered path is
 *   `<AssetPackLocation.assetsPath()>/<packName>/` — exactly how `installFromPack` reads the NPU
 *   packs. `null` for a row that ships no pack module: a language whose licence cell went green
 *   before its module exists is fallback-only, which the state machine can say out loud.
 * @property baseUrl the commit-pinned fallback source. It is NOT dead code and never becomes it:
 *   a debug build, a sideload and every install Play refuses by name have no pack to fetch, and
 *   the previewer must still be installable there (the NPU tiers' SAF import is the same idea).
 * @property modelType the encoder's own `model_type` metadata value — `zipformer2` for this pack,
 *   de, zh, ko and every Kroko build; **`zipformer` (v1)** for French and for both bilingual
 *   zh-en rows. **It is recorded here and deliberately NOT passed to sherpa** (the loader passes
 *   `""`, and `SherpaPreviewLoaderPinTest` holds it empty): a config string that disagrees with
 *   the file forces `OnlineZipformer2TransducerModel`, which reads `query_head_dims` through a
 *   macro whose miss path is `_Exit(-1)` — an **uncatchable process kill** that reaches neither
 *   `warm()`'s catch, nor `onLoadFailure`, nor `markCorrupt`, nor any disabled flag
 *   (qualification table §6(2)). An empty string lets the encoder's own metadata decide, which is
 *   correct for every candidate in that table. What this field is FOR is saying, in the
 *   catalogue, which decision the file is going to make — and refusing a row whose family the
 *   shipped AAR cannot construct at all.
 * @property decodeChunkLen the encoder's own `decode_chunk_len` metadata value — the pack's
 *   CADENCE in frames, and therefore [cadenceMs] of audio per forward pass after the first. It is
 *   **32** for this pack and for de/fr/zh/zh-en/ko, **64** for ru/id/tr/et/pt and **128** for
 *   every Kroko build, so it is per-pack and not a constant (qualification table, correction 8:
 *   the survey's own table had no cadence column, and the omission hid the property that carries
 *   [padMs], the test double's shift and three KDoc lines with it).
 * @property encoderT the encoder's own `T` metadata value — the frames ONE forward pass consumes,
 *   which is what [padMs] is derived from. Not a function of [decodeChunkLen]: `zipformer2`
 *   exports write `T = decodeChunkLen + 13` (32 → 45, 64 → 77, 128 → 141) and the `zipformer` v1
 *   exports write `T = decodeChunkLen + 7` (fr and zh-en are 32 → **39**), so it is READ off the
 *   file and asserted against it, never inferred.
 * @property caseFold what the strip does with the case this model emitted — see [CaseFold] for the
 *   four-values-to-two-answers table and for why a boolean over the token census returns the
 *   HARMFUL answer for `zh`. **There is no fold locale on a row**: a [CaseFold.Fold] row folds in
 *   `Locale.forLanguageTag(`[language]`)`, so a row cannot carry a locale that disagrees with its
 *   own language. `Fold` here: English's vocabulary is single-case (495 uppercase-bearing emittable
 *   pieces, and the only lowercase in the file is the three specials no decode emits), so the fold
 *   is lossless and the strip must not shout — byte-for-byte what 4.4.1 rendered, because
 *   `forLanguageTag("en")` folds exactly as the `Locale.US` this row used to name. **`Keep` for
 *   ko/et/zh and every Kroko build**, where folding paints `nba` over the `NBA` the model produced
 *   and a lowercased German noun reads as WRONG rather than rough; **`Fold` for Turkish too**, and
 *   it is Turkish folding it gets, because [language] is `tr` — the one row where that changes a
 *   character (qualification table §4.1, §4.2). It is the one flag the strip's own rules branch on;
 *   the three below it are copy inputs.
 * @property stripUnit what the strip is MADE OF for this pack — the noun *"Words appear on the
 *   bubble"* asserts, and a claim about the PACK rather than about the feature. [StripUnit.WORDS]
 *   here and on five other rows; **[StripUnit.CHARACTERS_AND_WORDS] on the bilingual `zh-en` row**,
 *   whose 5,755 Han-bearing pieces are single characters and carry no word boundary at all. See
 *   [StripUnit] for the per-row census, and for why `ko`'s answer is the one a token file cannot
 *   give and a measurement had to.
 * @property emitsPunctuation whether the strip can carry `.` `?` `,` `!`. **False here**, and the
 *   judgement is deliberate: English has exactly ONE punctuation piece, the apostrophe at id 45,
 *   which is a word-internal joiner (`DON'T`) rather than punctuation, and the shipping sentence
 *   already lives with it. **True for ko (32 pieces), et (23) and tr (18)** — where the strip will
 *   show marks the English strip never does, which is a fact about what the user SEES and so a
 *   sentence has to say it.
 * @property emitsDigits whether a numeral can appear on the strip. **False here**: the only
 *   digit-bearing pieces are `#0` and `#1`, the two placeholder slots icefall appends after the
 *   500 BPE pieces, which no decode emits. **True for ko (10 standalone digits), et (9), nl (all
 *   ten) and — by exactly one token — the bilingual zh-en row (`2` at id 4883).**
 * @property canary the clip in main assets this pack's load-time canary transcribes AND the rule
 *   that scores it — one thing, and **nullable**. **The English digits clip cannot pass for a
 *   non-English model** — a French recognizer fed "one two three four five" answers something
 *   matching none of the five positions, which is indistinguishable from the SME
 *   silent-miscompute signature the canary exists to catch — so the clip is the pack's. Recorded
 *   cost: one WAV per language, **81,998 B** (the English one's size). `null` means the clip has
 *   not been SOURCED yet: the verdict is [CanaryVerdict.Unscored] — **no verdict, never a
 *   failure** — and the language **arms UNSCORED**, which is a priced trade and not a free one
 *   (the FEAT_SME guard goes unpaid for that language until its clip lands; [PackCanary]'s
 *   docblock prices it, and [CanaryVerdict] tabulates the three consequences). `null` is NOT
 *   [CanaryVerdict.NoClip]: that verdict is a clip this row NAMED that would not load, a build
 *   defect, and it still takes the language off. See also [PreviewCanaryRule] for what positional
 *   matching cannot express (zh and ko collapse the clip to one token and need a different RULE,
 *   not a different alias list).
 */
data class StreamingPack(
    val language: String,
    val dirName: String,
    val packName: String?,
    val baseUrl: String,
    val encoder: PackFile,
    val decoder: PackFile,
    val joiner: PackFile,
    val tokens: PackFile,
    val modelType: String,
    val decodeChunkLen: Int,
    val encoderT: Int,
    val caseFold: CaseFold,
    val stripUnit: StripUnit,
    val emitsPunctuation: Boolean,
    val emitsDigits: Boolean,
    val canary: PackCanary?,
) {
    val files: List<PackFile> get() = listOf(encoder, decoder, joiner, tokens)
    val totalBytes: Long get() = files.sumOf { it.bytes }
    /** The commit-pinned download URL: the base plus the file's UPSTREAM path, never its flat local name. */
    fun urlOf(file: PackFile): String = baseUrl + file.path

    /**
     * The pack's cadence in milliseconds — how often the strip can repaint once a stream is
     * running. 320 ms here; 640 for the `T = 77` packs and 1,280 for a Kroko build.
     *
     * **The measured 0.4 s word lag is a 320 ms number and cannot be reused in copy for a pack
     * whose cadence is not 320** (qualification table §4.2, and owner ruling O7 is open on
     * whether 640 ms clears the bar at all). This property is where a sentence that wants to
     * quote a lag has to start.
     */
    val cadenceMs: Long get() = decodeChunkLen * StreamingPreviewTuning.FRAME_SHIFT_MS

    /**
     * The four facts a sentence about this pack's STRIP may assert, plus the one orthography fact
     * that is about the language — see [StripShape], and [StreamingPackCopy.stripNote] for the
     * sentence they select. Derived here so no call site assembles it, and so a row cannot carry
     * a shape that disagrees with its own flags.
     */
    val stripShape: StripShape get() = StripShape(
        unit = stripUnit,
        keepsCase = caseFold is CaseFold.Keep,
        punctuation = emitsPunctuation,
        digits = emitsDigits,
        capitalisesEveryNoun = language in StripShape.CAPITALISES_EVERY_NOUN,
    )

    /**
     * The zeros the commit hook pads before `inputFinished`, DERIVED from [encoderT] — see
     * [StreamingPreviewTuning.padMsFor] for why a flat 500 drops the last word of every utterance
     * on a `T = 77` pack, and why the measured 500 is that derivation's floor.
     */
    val padMs: Long get() = StreamingPreviewTuning.padMsFor(encoderT)
}

/**
 * The packs the previewer can run. The first row is `streaming-zipformer-en-2023-06-26`
 * (**72,654,782 B — badged "73 MB"**, int8, Apache-2.0, LibriSpeech), the four files re-hashed on
 * the PC (rung 1 §1.2) and on the Tab (rung 3 §1.2, §7). A second language is a second row whose
 * licence cell is green first (research §2.5) — no per-language cards, no chooser change.
 *
 * ### Where the numbers on the six 4.5.0 rows come from
 *
 * Every byte count, digest and metadata value below was READ from source on **2026-09-12**, not
 * copied from the qualification table that recommended the rows — a digest typed from a document
 * is a digest nobody checked. The method, per row:
 *
 *  - **bytes and digests** — `api/models/<repo>/tree/main?recursive=true&expand=true`, taking the
 *    `size` and the **LFS oid** (which IS the file's sha256) of each of the four files. Never a
 *    release tar: the shipped English pack is 72,654,782 B on disk against a 310,414,022 B tar.
 *    A non-LFS file has no oid, so every `tokens.txt` was DOWNLOADED and hashed here; the method
 *    is proved by the English one, which came back `49e3c264…` at 5,048 B — this catalogue's own
 *    pinned literals. Korean corroborates independently: the repo ships its own `SHA256SUMS`, and
 *    all four of its digests match the tree API and the local hash.
 *  - **`modelType`, `decodeChunkLen`, `encoderT`** — an HTTP **Range read of the last 512 KiB** of
 *    each int8 encoder, with `metadata_props` PARSED as protobuf (`OnnxMetadata`'s algorithm,
 *    field 14, `StringStringEntryProto`), never string-scanned: a naive 4-character scan aligns
 *    `decode_chunk_len` to `77`, because the real value `64` is two characters and the key `T` is
 *    one. Proved the same way — the English encoder's tail returns exactly the `zipformer2` / 32 /
 *    45 this file already pins.
 *  - **the copy flags** — `PackTokenFacts`' own derivation, run over each downloaded `tokens.txt`.
 *
 * One thing the table did not check and this catalogue does: the **decoder's** metadata. The v1
 * reader takes nine keys, not seven — seven off the encoder and `vocab_size` + `context_size` off
 * the decoder, each through the same `_Exit(-1)` macro — so all six decoders were Range-read too
 * (fr 500, de 500, ru 500, id 500, ko 2460, zh-en 6254; `context_size = 2` on every one).
 *
 * (The "66 M" this docblock used to claim was the research doc's estimate and is wrong by 7 MB;
 * the verified sum of the four `PackFile` byte counts is 72,654,782 and every sentence the user
 * reads derives its badge from that sum through [sizeBadge]. A release tarball is a third number
 * again — 310,414,022 B of fp32 + int8 + wavs — and is never any pack's size.)
 *
 * **A row is not the same size as another row** — fr is 128 MB, de 71, ru 29, the bilingual zh-en
 * 50. `StreamingPackCopy` used to carry that number in one class-init `val` over THIS row's
 * `totalBytes`, across six sentences, which would have described most future rows wrongly. Task 3
 * closed it at the call sites (ruling 3d, *"use the pack's OWN size, never a fixed number"*):
 * every sentence that names a size now takes the pack's `totalBytes` and rounds it through
 * [sizeBadge] itself, so there is no shared figure left for a second row to inherit.
 *
 * The byte counts and digests here are the ONE census: `tools/build_asset_packs.py preview`
 * places the pack payload against the same literals, `verifyPreviewPack` gates every bundle
 * build on them, and `PreviewPackLayoutTest` holds all three spellings equal.
 */
object StreamingPackCatalog {
    const val ROOT_DIR = "zf-stream"
    const val MARKER = ".installed"
    const val TMP_SUFFIX = ".tmp"

    /** The English pack module's Play name — also the module directory and the delivered dir. */
    const val PACK_EN = "preview_en"

    /**
     * The 4.5.0 language packs' Play names. `preview_<language>` on every row, which
     * `StreamingPackLanguagesTest` holds as a rule rather than a coincidence: the string is the
     * Play-side identity (`fetch()`, `getPackLocation()`) AND the delivered directory name AND the
     * module directory, and a row whose pack name did not follow its language would be four
     * separate places to get one language wrong.
     *
     * `preview_zh` carries the **bilingual zh-en** export, because the row it serves is the
     * picker's Chinese (#10) and it is the only row in the catalogue that puts anything on the
     * strip when an English speaker talks mid-Chinese. The pack is named after the language it is
     * SELECTED by, not after the corpora it was trained on.
     */
    const val PACK_FR = "preview_fr"
    const val PACK_DE = "preview_de"
    const val PACK_RU = "preview_ru"
    const val PACK_ID = "preview_id"
    const val PACK_KO = "preview_ko"
    const val PACK_ZH = "preview_zh"

    val EN = StreamingPack(
        language = "en",
        dirName = "en-2023-06-26",
        packName = PACK_EN,
        // resolve/<commit sha>/, the catalog's own rule (WhisperModel.kt:103-108): resolve/main is
        // a MUTABLE ref and a replaced upstream file would brick every download until an update.
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/",
        encoder = PackFile("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 71_083_163L, "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1"),
        decoder = PackFile("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_307_236L, "98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02"),
        joiner = PackFile("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 259_335L, "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297"),
        tokens = PackFile("tokens.txt", 5_048L, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"),
        // Read off this encoder's own `metadata_props` (PreviewPackMetadataTest re-reads the file
        // and holds these equal wherever the payload is placed): model_type=zipformer2,
        // decode_chunk_len=32 ⇒ 320 ms, T=45 ⇒ a 500 ms pad, which is exactly the pad measured.
        modelType = "zipformer2",
        decodeChunkLen = 32,
        encoderT = 45,
        // Read line by line off this pack's own tokens.txt (PreviewPackMetadataTest re-derives all
        // three wherever the payload is placed): 502 lines, 495 uppercase-bearing pieces, the only
        // lowercase in the file is the three specials, ZERO byte-fallback pieces, one punctuation
        // piece and it is the apostrophe at id 45, and the only digit-bearing pieces are the
        // `#0`/`#1` placeholders. Single-case with no byte fallback is the one shape a fold is
        // PROVABLY lossless on, so this row takes the derivation's own suggestion rather than
        // overriding it. No locale is written here: the fold is `forLanguageTag("en")`, derived
        // from this row's `language`, and for English that is byte-identical to the `Locale.US`
        // 4.4.1 folded in, because String folds locale-sensitively for tr/az/lt only.
        caseFold = CaseFold.Fold,
        // 338 pieces carry the word marker and 27 unmarked pieces are single characters (5.4% of
        // the emittable vocabulary), so the file itself proves the strip's unit — see [StripUnit].
        stripUnit = StripUnit.WORDS,
        emitsPunctuation = false,
        emitsDigits = false,
        // The bundled digits clip and the digits rule. The alias sets, the 4-of-5 tolerance and
        // the 20-token runaway ceiling are `GpuCanaryPolicy`'s own values, RESTATED rather than
        // referenced: that object's verdict is a persisted whisper-GPU latch and must not acquire
        // a second caller who can move it. PreviewCanaryTest holds the two ANSWERS equal, so a
        // change to either side is a red test instead of a silent re-scoring of the other.
        canary = PackCanary(
            asset = CanaryAudio.ASSET,
            rule = PreviewCanaryRule(
                expected = listOf(
                    setOf("one", "1"),
                    setOf("two", "2"),
                    setOf("three", "3"),
                    setOf("four", "4"),
                    setOf("five", "5"),
                ),
                minMatches = 4,
                maxTokens = 20,
            ),
        ),
    )

    /**
     * **French** — `shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14` at `3db9565d`.
     * 128,227,451 B, badged "128 MB". The heaviest row in the catalogue and the one that pays for
     * the seam's crash fix.
     *
     * **`zipformer` V1, and that is the whole reason the loader passes `modelType = ""`.** Read
     * off its encoder tail: `model_type = zipformer`, `version = 1`, `decode_chunk_len = 32`,
     * `T = 39`, and **no `comment`, no `query_head_dims`, no `value_head_dims`, no `num_heads`**.
     * A non-empty config string forces `OnlineZipformer2TransducerModel`, which reads
     * `query_head_dims` through a macro whose miss path is `_Exit(-1)` — an uncatchable process
     * kill. Empty hands the choice to the file, and `OnlineZipformerTransducerModel` (v1) reads
     * exactly seven encoder keys: `encoder_dims`, `attention_dims`, `num_encoder_layers`,
     * `cnn_module_kernels`, `left_context_len`, `T`, `decode_chunk_len` — **all seven present**
     * (`2,4,3,2,4` / `384×5` / `192×5` / `31×5` / `64,32,16,8,32`). Plus the two the same reader
     * takes off the DECODER, also Range-read here: `vocab_size = 500`, `context_size = 2`.
     *
     * `T = 39` is not `decodeChunkLen + 13`: the v1 exporter writes `+ 7`. Arithmetically that
     * asks for 440 ms of pad, and `padMsFor` returns **500** anyway, because the one pad a device
     * has ever confirmed is a floor and never a default.
     *
     * **Licence — apache-2.0, READ twice.** The repo's own 204-byte non-LFS `README.md` front
     * matter says `license: apache-2.0`, and the platform surfaces it through `cardData.license`
     * and a `license:apache-2.0` tag — note the API's **top-level `license` key is absent**, so a
     * sweep reading `model["license"]` reports "Not specified" for a perfectly readable grant.
     * Corpus: CommonVoice 12.0 fr (CC0) on a LibriSpeech (CC BY 4.0) pretrain.
     *
     * **THE NUMBER THIS ROW MUST NOT CARRY FORWARD: its WER is 10.57, not the 9.95 icefall's
     * `RESULTS.md` advertises.** 9.95 is the **epoch-30** checkpoint, trained with **GigaSpeech**,
     * whose gated terms are non-commercial-only. This export is **epoch 29** — the repo's own
     * `export-onnx-stateless7-streaming.sh`, read here, runs `--epoch 29 --avg 1
     * --use-averaged-model 0` against `icefall-asr-commonvoice-fr-pruned-transducer-stateless7-streaming-2023-04-02`
     * — so citing 9.95 would import an encumbrance these bytes do not carry. 10.57 is the
     * qualification table's read of that checkpoint's own greedy `wer-summary`; this mirror ships
     * no decode log of its own, so the number is the table's and not this file's.
     */
    val FR = StreamingPack(
        language = "fr",
        dirName = "fr-2023-04-14",
        packName = PACK_FR,
        baseUrl = "https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73/",
        encoder = PackFile("encoder-epoch-29-avg-9-with-averaged-model.int8.onnx", 126_655_903L, "47a94a7fdc8dff63d708be4ea0535747640224467f91e238311f1ddbdd09327e"),
        decoder = PackFile("decoder-epoch-29-avg-9-with-averaged-model.int8.onnx", 1_307_157L, "e72b2b9ed36355bd0dd43433f7dd258e7226ab54c9ef42b28c73ebb785805623"),
        joiner = PackFile("joiner-epoch-29-avg-9-with-averaged-model.int8.onnx", 259_572L, "fc2f3bb851a15a532c6f2422d53eecd1ca949f12b0897e07a852021c30481711"),
        tokens = PackFile("tokens.txt", 4_819L, "37fb3f2a7bcb85e5fff3f1f66be04e6fbb05077a22f56d177fe85704e945fb31"),
        modelType = "zipformer",
        decodeChunkLen = 32,
        encoderT = 39,
        // The census, run over this pack's own downloaded tokens.txt: 502 lines, 497 emittable,
        // **495 uppercase-bearing / 0 lowercase / 0 digit-bearing among them** — identical counts
        // to the shipping English file, which is exactly why 4.4.0's wrong pinned comment was one
        // row away from being inherited. 239 pieces carry the word marker, a bare `▁` sits at id
        // 4, no byte fallback, and the one punctuation-only piece is the apostrophe (`L'EAU` is a
        // word). Single-case with no byte fallback ⇒ the fold is PROVABLY lossless, so this row
        // takes the derivation's suggestion; `É→é`, `Ç→ç`, `Œ→œ` are correct French.
        caseFold = CaseFold.Fold,
        // 238 marked pieces against 42 unmarked single characters (8.5%) — the file's own
        // proof, and `UN DEUX TROIS QUATRE CINQ` came back as five spaced tokens.
        stripUnit = StripUnit.WORDS,
        emitsPunctuation = false,
        emitsDigits = false,
        // **The clip is SYNTHESIZED IN-REPO, from the voice model this app already ships**
        // (4.5.0 T3). `canary_fr_digits.wav`, 47,498 B, 23,710 samples = 1.482 s — the smallest
        // canary in the catalogue, against the English clip's 81,998 B.
        //
        // How, exactly, so it can be regenerated: `kokoro-multi-lang-v1_0` (the archive
        // `tts_kokoro` delivers, Apache-2.0, digest-gated by TtsModelManager's own known-good
        // set), speaker id **30 = `ff_siwis`** (TtsVoices.kt:47, "Siwis — French female"), text
        // `un deux trois quatre cinq`, speed 1.0, phonemized by the archive's OWN espeak-ng-data
        // under the voice name `fr`, then 24 kHz → **ffmpeg** → 16 kHz PCM16 mono, peak 0.80.
        //
        // **Use a real resampler or the clip lies.** A hand-rolled linear 24 → 16 kHz interpolation
        // aliases everything above 8 kHz into the speech band, and the French pack answered `""`
        // for the same words at speeds 0.85 and 0.75 and dropped `TROIS` at 1.0 — i.e. it produced
        // the FEAT_SME signature from perfectly good audio. Through ffmpeg's swresample the same
        // synthesis decodes exactly. A clip chosen by ear would have shipped the aliased one and
        // disabled French on every device.
        //
        // Re-verified against this pack's own placed tokens.txt rather than copied from T1:
        // `▁UN 50`, `▁DEUX 156`, `▁TROIS 304`, `▁QUATRE 353`, `▁CINQ 386` — all five WHOLE
        // pieces, so every position is checkable by a non-speaker. And the decode is exact:
        // `UN DEUX TROIS QUATRE CINQ`, five tokens, 5 of 5 positions in all nine
        // (threads × gain) cells. `minMatches = 4` is therefore one drop of slack, the English
        // row's own tolerance, and not the operating point.
        canary = PackCanary(
            asset = "canary_fr_digits.wav",
            rule = PreviewCanaryRule(
                expected = listOf(
                    setOf("un"),
                    setOf("deux"),
                    setOf("trois"),
                    setOf("quatre"),
                    setOf("cinq"),
                ),
                minMatches = 4,
                // Four times the five tokens measured — the English row's own ratio (5 → 20).
                maxTokens = 20,
            ),
        ),
    )

    /**
     * **German** — `daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de` at `322557b0`.
     * 70,938,534 B, badged "71 MB". The cheapest row in the survey and the one with the awkward
     * upstream layout.
     *
     * `zipformer2`, `decode_chunk_len = 32`, `T = 45`, `comment = "streaming zipformer2"`, both
     * head-dims keys and `num_heads` present — architecturally the shipping English encoder's twin
     * (`2,2,3,4,3,2` / `192,256,384,512,384,256`), so **the measured 320 ms cadence and 500 ms pad
     * transfer unchanged**. Decoder: `vocab_size = 500`, `context_size = 2`.
     *
     * **Its four files are NOT flat upstream, and three of them have COMMAS in the filename** —
     * `exp/epoch-30/…-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx` and
     * `lang_bpe_500/tokens.txt`. That is what [PackFile.path] is for: the comma stays in the URL,
     * where it is a legal path character, and the flat `name` this pack writes to disk and ships
     * as an AAB asset entry stays in `[A-Za-z0-9._-]`. The commas are dropped from the local name
     * rather than translated, because the graph supports all four chunk sizes and a name claiming
     * one of them would be a claim the file does not make — the checkpoint (`epoch-30-avg-5`)
     * identifies the bytes, the metadata identifies the cadence, and the sha256 identifies both.
     *
     * **Licence — apache-2.0, author-declared in a file the platform does NOT parse, so this row
     * needs ONE EMAIL and is NOT CLEARED here.** Read directly at `resolve/main`: a 180-byte
     * README whose front matter says `license: apache-2.0` and
     * `datasets: mozilla-foundation/common_voice_17_0`. Read the other way too: the API reports
     * `license: null` AND `cardData: null`, because that README is an LFS/Xet blob (it has an LFS
     * oid, which a plain README does not). Corpus CommonVoice 17.0, declared on the card, with no
     * third-party agreement — so the outstanding item is a confirmation of a grant that is already
     * declared, not a request for one.
     *
     * Accuracy, READ from the repo's own summary files rather than a card: `wer-summary-test-…`
     * says `greedy_search 10.58` and `wer-summary-dev-…` says `8.58`, both at
     * `chunk-32-left-context-128`, both greedy — the app's own decode mode.
     */
    val DE = StreamingPack(
        language = "de",
        dirName = "de-cv17-epoch-30",
        packName = PACK_DE,
        baseUrl = "https://huggingface.co/daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de/resolve/322557b0f88fc5a9823bc71027d4160f0c7612cc/",
        encoder = PackFile(
            name = "encoder-epoch-30-avg-5.int8.onnx",
            bytes = 70_133_342L,
            sha256 = "e0163b48f89a81fafc4eb1804a77cdd33646970160f5d954a82774dc86e93fa5",
            path = "exp/epoch-30/encoder-epoch-30-avg-5-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx",
        ),
        decoder = PackFile(
            name = "decoder-epoch-30-avg-5.int8.onnx",
            bytes = 540_689L,
            sha256 = "8e787f64f765d2d4d1e17315879bc3cc1c2f517532799d78be034a03a9bcacda",
            path = "exp/epoch-30/decoder-epoch-30-avg-5-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx",
        ),
        joiner = PackFile(
            name = "joiner-epoch-30-avg-5.int8.onnx",
            bytes = 259_417L,
            sha256 = "d58379fa169af64034c127558230af63d0c08cda97a4a310b04af4b6ceb68956",
            path = "exp/epoch-30/joiner-epoch-30-avg-5-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx",
        ),
        tokens = PackFile(
            name = "tokens.txt",
            bytes = 5_086L,
            sha256 = "ad2da0c993128b66cead1d78adddc359bef69c50fca890bb5ca0500c97b6d23d",
            path = "lang_bpe_500/tokens.txt",
        ),
        modelType = "zipformer2",
        decodeChunkLen = 32,
        encoderT = 45,
        // The census over the downloaded file: 502 lines, 497 emittable, **496 uppercase-bearing /
        // 0 lowercase / 0 digit-bearing** among them, 229 word-marker pieces, a bare `▁` at id 3,
        // no byte fallback, and **ZERO punctuation-only pieces at all** — a cleaner file than
        // English's on that axis, which has one. So the fold is provably lossless and both copy
        // flags are false.
        //
        // The COPY consequence is T4's and is the reason this row cannot share English's
        // sentence: folding is what makes the strip lowercase, and a lowercased German noun reads
        // to a German reader as WRONG rather than rough. The sentence must say "no capitals,
        // INCLUDING nouns". That is a fact about this language's orthography, not about this file.
        caseFold = CaseFold.Fold,
        // 228 marked pieces against 30 unmarked single characters (6.0%). The measured clip
        // is the same proof from the other end: `MANCHE FESTIVALS HABEN SPEZIELLE
        // CAMPINGBEREICHE` arrives as five spaced words out of 22 pieces.
        stripUnit = StripUnit.WORDS,
        emitsPunctuation = false,
        emitsDigits = false,
        // **The clip is FLEURS** (4.5.0 T3) — German has no Kokoro voice (TtsVoices.kt covers es
        // fr hi it ja pt zh), so it comes from a corpus that publishes a reference transcript
        // beside its audio, which is what makes the expected output checkable by a non-speaker.
        //
        // `canary_de_fleurs.wav`, 107,882 B, 53,919 samples = 3.370 s.
        // **`google/fleurs`, licence `cc-by-4.0`** — read in the dataset card's own front matter
        // (`README.md:112-113`) AND platform-surfaced (`cardData.license`, plus the tag
        // `license:cc-by-4.0`); note the API's top-level `license` key is absent here too, the
        // same trap this row's and the Indonesian row's model cards carry. Config `de_de`, split
        // `validation`, row **id 1528**, dataset revision `70bb2e84`. Attribution is on the
        // licence page (`oss_licenses.html`, "Bundled audio"), which CC BY 4.0 requires and which
        // `PreviewCanaryClipsTest` pins.
        //
        // **MODIFIED, and CC BY 4.0 requires saying so**: container normalised to 16 kHz PCM16
        // mono by ffmpeg, then TRUNCATED after a decoder token boundary at 3.2 s + 250 ms. The
        // full utterance is 5.28 s / 169,038 B and decodes all ten reference words; the cut is
        // paid for twice over, in the APK and on every warm, and the truncated clip was RE-DECODED
        // rather than assumed — `MANCHE FESTIVALS HABEN SPEZIELLE CAMPINGBEREICHE`, the first five
        // reference words, exact, 5 of 5 positions in all nine (threads × gain) cells.
        //
        // The positions are the words the decode PRODUCED, not the ones the transcript promises:
        // only `▁HABEN 263` is a whole piece here, and `▁MANCHE`, `▁FESTIVALS`, `▁SPEZIELLE`,
        // `▁CAMPINGBEREICHE` are each spellable from this vocabulary's pieces (`PackTokenFacts`
        // .spellable, pinned) and arrive as one space-delimited word on the strip either way.
        canary = PackCanary(
            asset = "canary_de_fleurs.wav",
            rule = PreviewCanaryRule(
                expected = listOf(
                    setOf("manche"),
                    setOf("festivals"),
                    setOf("haben"),
                    setOf("spezielle"),
                    setOf("campingbereiche"),
                ),
                minMatches = 4,
                maxTokens = 20,
            ),
        ),
    )

    /**
     * **Russian** — `csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16` at
     * `31fa603e`. **28,572,945 B, badged "29 MB": the cheapest row in the catalogue**, and the one
     * that proves the multi-pack machinery at the lowest byte cost anyone could ask for.
     *
     * **It is also the first `T = 77` row, and that is not polish.** `zipformer2`,
     * `decode_chunk_len = 64`, `T = 77`, `comment = "streaming zipformer2"`, head-dims and
     * `num_heads` present, a genuinely small encoder (`2,2,2,2,2,2` / `192,256,256,256,256,256`).
     * One forward pass needs 77 frames = **770 ms** of feature, so the flat 500 ms pad 4.4.0
     * measured on English is **270 ms short**: `isReady` stays false, the tail chunk is never
     * decoded and **the last word of every utterance silently never emits** — and the canary Fails
     * into a verdict behind a sentence the 4.4.0 acceptance sheet records as rendered nowhere.
     * [StreamingPreviewTuning.padMsFor] returns **820 ms** here. Upstream agrees on the direction:
     * Vosk's own Russian `decode.py` pads 600 ms, and 2.0 s for its 128-shift variant — nobody
     * pads 500 for a Russian model. Decoder: `vocab_size = 500`, `context_size = 2`.
     *
     * **The cadence is 640 ms, so no copy on this row may quote the measured 0.401 s word lag.**
     * That figure is a property of `decode_chunk_len = 32` and owner ruling O7 — *"is sub-second
     * the bar, or same-as-English?"* — is open on whether 640 ms clears the bar at all. INFERRED
     * ~0.56-0.72 s; UNMEASURED.
     *
     * **Licence — apache-2.0 at upstream, and the mirror is untagged, which is discharged
     * CRYPTOGRAPHICALLY rather than argued.** This mirror's README is 111 bytes and says only
     * *"Models in this directory are from
     * https://huggingface.co/alphacep/vosk-model-small-streaming-ru"* — no front matter, and the
     * API reports `license: null` with `cardData: null`, read both ways here. The grant lives on
     * that upstream repo, platform-surfaced, and all four pack files are byte-identical to it
     * (three LFS oids plus a `sha256` computed on `tokens.txt`) — a STRONGER position than
     * German's, whose grant the platform cannot read at all. **What survives is the corpus: the
     * card names none.** *"Trained with k2-fsa/icefall on Russian data"* is the whole of it ⇒ one
     * optional email, not a gate, and NOT CLEARED here.
     *
     * Recorded so a disappointing rung-1 number costs 0.25 d and not a re-audit:
     * `alphacep/vosk-model-streaming-ru` v0.56 is the zero-seam accuracy upgrade — apache-2.0
     * platform-surfaced, four int8 files at 71,694,239 B, **byte-identical `tokens.txt`**, same
     * 640 ms cadence, +43 MB.
     */
    val RU = StreamingPack(
        language = "ru",
        dirName = "ru-vosk-2025-08-16",
        packName = PACK_RU,
        baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/31fa603e4f31279c6e1f7600fed13dc4312663ab/",
        encoder = PackFile("encoder.int8.onnx", 26_214_060L, "e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d"),
        // The one pack in the catalogue whose decoder is NOT quantized — upstream ships no int8
        // decoder, and `decoder.onnx` at 2,093,080 B is 1.6 MB more than an int8 one would be. It
        // is still the smallest pack here by 42 MB.
        decoder = PackFile("decoder.onnx", 2_093_080L, "89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0"),
        joiner = PackFile("joiner.int8.onnx", 259_417L, "b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5"),
        tokens = PackFile("tokens.txt", 6_388L, "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6"),
        modelType = "zipformer2",
        decodeChunkLen = 64,
        encoderT = 77,
        // The census over the downloaded file: 502 lines, 497 emittable, **ZERO uppercase / 495
        // lowercase-bearing / 0 digit-bearing** among them — already-lowercase Cyrillic, so the
        // fold is a no-op on the characters and provably lossless on the census. 262 word-marker
        // pieces, a bare `▁` at id 7, no byte fallback, all 33 Russian letters as single pieces.
        //
        // Its ONE punctuation-only piece is a standalone `-`, which does NOT set
        // `emitsPunctuation`: какой-то and по-русски are words and the mark is inside them,
        // exactly as `DON'T` is. `PackTokenFacts.JOINERS` is where that judgement is written.
        caseFold = CaseFold.Fold,
        // 261 marked pieces against 34 unmarked single characters (6.8%) — and Cyrillic is
        // space-delimited, which the measured clip's eight spaced words show.
        stripUnit = StripUnit.WORDS,
        emitsPunctuation = false,
        emitsDigits = false,
        // **The clip is FLEURS, and this row is where the 820 ms pad got its first measurement**
        // (4.5.0 T3). `canary_ru_fleurs.wav`, 107,882 B, 53,919 samples = 3.370 s.
        // `google/fleurs` `cc-by-4.0` (see the German row for where that licence was read),
        // config `ru_ru`, split `validation`, row **id 1587**, revision `70bb2e84`; attributed on
        // the licence page. MODIFIED: 16 kHz PCM16 mono via ffmpeg, truncated after a token
        // boundary at 3.2 s + 250 ms from a 4.80 s original.
        //
        // **THE ORDER MATTERED, AND THIS CLIP IS THE FIRST MEASUREMENT OF IT.** The derived pad
        // had to be right before any canary could run here, and a pad sweep on the SHIPPED clip
        // shows why, on the real payload:
        //
        //     pad   0 / 300 / 500 ms → `… в этом сезоне`          ← the last word never emits
        //     pad 700 / 820 ms       → `… в этом сезоне было`     ← `padMsFor(77)` = 820
        //
        // Before this the 820 ms was an arithmetic derivation (`T = 77` frames = 770 ms of
        // feature) corroborated only by Vosk's own `decode.py` padding 600. It is now a decode:
        // **a flat 500 ms pad silently drops the last word of this utterance on this pack**, which
        // is the defect `padMsFor` exists to close, and the derivation recovers it. (The pad's own
        // value stays pinned by `PreviewCanaryTest.thePadIsThePacksOwnAndNotAConstant` — the
        // canary is not the regression test for it. With `minMatches = 5` a short pad would COST
        // this row's slack rather than fail it, which is the graceful direction.)
        //
        // The full 4.80 s utterance decodes all eleven words at either pad, because it carries its
        // own trailing silence — so the untruncated clip would NOT have shown this. The cut is
        // what put the last word against the pad boundary.
        //
        // The truncated clip decodes `о первых случаях заболевания в этом сезоне было` — eight
        // tokens, the first eight reference words, exact in all nine (threads × gain) cells.
        // **The two single-letter words are NOT positions**: `о` and `в` are one Cyrillic
        // character each, which is a rendering a corruption could produce by accident, so the six
        // positions are the multi-character words and `minMatches = 5` is one drop of slack.
        // Only `▁было 253` is a whole piece; the other five are spellable from this vocabulary
        // (pinned) and reach the strip as whole words regardless.
        canary = PackCanary(
            asset = "canary_ru_fleurs.wav",
            rule = PreviewCanaryRule(
                expected = listOf(
                    setOf("первых"),
                    setOf("случаях"),
                    setOf("заболевания"),
                    setOf("этом"),
                    setOf("сезоне"),
                    setOf("было"),
                ),
                minMatches = 5,
                // Four times the eight tokens measured — the English row's ratio on a longer clip.
                maxTokens = 32,
            ),
        ),
    )

    /**
     * **Indonesian** — `spacewave/sherpa-onnx-streaming-zipformer2-id` at `4e5a13cb`.
     * 70,908,694 B, badged "71 MB". The cleanest vocabulary in the survey and the strongest
     * canary story of any non-English row.
     *
     * `zipformer2`, `decode_chunk_len = 64`, `T = 77` — the second 640 ms row, so the **820 ms**
     * derived pad and the ban on quoting the 0.401 s lag both apply here too. `comment =
     * "streaming zipformer2"`, head-dims and `num_heads` present, the English encoder's own shape
     * (`2,2,3,4,3,2` / `192,256,384,512,384,256`) with a wider left context
     * (`256,128,64,32,64,128`). Decoder: `vocab_size = 500`, `context_size = 2`.
     *
     * **Licence — MIT, READ in a 902-byte non-LFS front matter AND platform-surfaced** through
     * `cardData.license` plus a `license:mit` tag. The API's **top-level `license` key is absent**
     * here as well: the German trap in a second costume, by key naming rather than by Xet storage,
     * so a sweep reading `model["license"]` reports "Not specified" for a perfectly readable MIT
     * grant.
     *
     * **NOT CLEARED — the corpus is the counsel question, and the card names it.** Its front
     * matter declares four datasets: `espnet/yodas2`, `mozilla-foundation/common_voice_17_0`,
     * `google/fleurs` and `indonesian-nlp/librivox-indonesia`. YODAS2 is CC BY 3.0 and
     * YouTube-derived, **85.6% of the Indonesian label hours are auto-captions** (8,463.61 of
     * 9,883.70 h), and its `video_id` is deliberately not YouTube's, so attribution is
     * structurally unsatisfiable at the item level; `librivox-indonesia` is tagged `cc` with no
     * version. The licence CLASS is commercially permissive — no NC, no SA — and the question is
     * reach and attribution, which is counsel's, not engineering's.
     *
     * Accuracy: the card publishes CV 11.58, FLEURS 8.96, YODAS 11.90, LibriVox-ID 6.79 and
     * states *"all results are used with greedy-search decoding"* — the app's own mode. It ships
     * **no decode log, no `test_wavs`, no eval script and no `exp/`**, so the decode MODE is
     * stated and the numbers are UNVERIFIED.
     */
    val ID = StreamingPack(
        language = "id",
        dirName = "id-iter-100000",
        packName = PACK_ID,
        baseUrl = "https://huggingface.co/spacewave/sherpa-onnx-streaming-zipformer2-id/resolve/4e5a13cbe3e9cd4e3775447d86178ef51759096f/",
        encoder = PackFile("encoder-iter-100000-avg-15-chunk-32-left-256.int8.onnx", 70_103_186L, "3a6f85f5d199ad0d495562988af017a75d4ae81b51126db240d959b065d6eaad"),
        decoder = PackFile("decoder-iter-100000-avg-15-chunk-32-left-256.int8.onnx", 540_688L, "6544848ca80b557ec3c8569169522dc6243b5bc9b296ea73791728510caacb4a"),
        joiner = PackFile("joiner-iter-100000-avg-15-chunk-32-left-256.int8.onnx", 259_417L, "4b89d96292460a92dedcb39e0b17904f10dbad335a8ab11015e567b4864102e0"),
        tokens = PackFile("tokens.txt", 5_403L, "f0b6f5bf602d96f60d79bb17192c51eeffb6189250f132b1dfdf72b130d66968"),
        modelType = "zipformer2",
        decodeChunkLen = 64,
        encoderT = 77,
        // The census over the downloaded file, and it is the cleanest in the catalogue: **500
        // lines** — three fewer than every other `lang_bpe_500` row, because this file carries NO
        // `#0`/`#1` placeholders at all — 497 emittable, **496 uppercase-bearing / 0 lowercase /
        // 0 digit-bearing in the WHOLE FILE** (cleaner than English, whose placeholders are two
        // digit-bearing pieces), 359 word-marker pieces, a bare `▁` at id 7, no byte fallback,
        // and its only punctuation-shaped piece is `<sos/eos>`, a special no decode emits. So
        // there is not one emittable mark or numeral anywhere in this vocabulary.
        caseFold = CaseFold.Fold,
        // 358 marked pieces — the most of any row — against 26 unmarked single characters
        // (5.2%).
        stripUnit = StripUnit.WORDS,
        emitsPunctuation = false,
        emitsDigits = false,
        // **The clip is FLEURS** (4.5.0 T3). `canary_id_fleurs.wav`, 114,282 B, 57,119 samples =
        // 3.570 s. `google/fleurs` `cc-by-4.0` (see the German row for where that licence was
        // read), config `id_id`, split `validation`, row **id 1636**, revision `70bb2e84`;
        // attributed on the licence page. MODIFIED: 16 kHz PCM16 mono via ffmpeg, truncated after
        // a token boundary at 3.4 s + 250 ms from a 6.84 s original.
        //
        // **Why not the counting clip this row planned.** The row recorded that `▁SATU 134`,
        // `▁DUA 164`, `▁TIGA 231`, `▁EMPAT 324`, `▁LIMA 320` are whole pieces and called that the
        // easiest canary in the catalogue — and it would be, if anything could SAY them. Kokoro
        // has no Indonesian voice, FLEURS' sentences are FLoRes translations and none of them
        // counts to five, and a clip nobody can produce is not a rule. What a published corpus
        // gives instead is a reference transcript beside its audio, which is the same property
        // ("checkable by a non-speaker") arrived at from the other side.
        //
        // The truncated clip decodes `BANGSA SPANYOL MEMULAI PERIODE KOLONIALISASI` — the first
        // five reference words, exact, 5 of 5 positions in all nine (threads × gain) cells. No
        // digit aliases are needed: this vocabulary has no emittable numeral at all (500 lines, no
        // `#N` placeholders), which is the cleanest census in the catalogue.
        //
        // This is the second `T = 77` row, and its pad sweep agrees with Russian's: the clip's
        // last word arrives as `KOLONIA` at a 300 ms pad and whole at 500 and above, so the
        // derived 820 ms is above the cliff here rather than on it.
        canary = PackCanary(
            asset = "canary_id_fleurs.wav",
            rule = PreviewCanaryRule(
                expected = listOf(
                    setOf("bangsa"),
                    setOf("spanyol"),
                    setOf("memulai"),
                    setOf("periode"),
                    setOf("kolonialisasi"),
                ),
                minMatches = 4,
                maxTokens = 20,
            ),
        ),
    )

    /**
     * **Korean** — `kangkyu/icefall-asr-ko-streaming-zipformer-72m` at `db24b58d`, the **chunk-16**
     * export. 72,969,700 B, badged "73 MB".
     *
     * `zipformer2`, `decode_chunk_len = 32`, `T = 45`, `comment = "streaming zipformer2"`,
     * head-dims and `num_heads` present, and the English encoder's exact layer/dim vectors — so
     * the 320 ms cadence and the 500 ms pad are the shipping row's. The repo also publishes
     * chunk-32 and chunk-64 encoders whose decoder and joiner are **byte-identical** to these
     * (one LFS oid each), which is the trap the export choice avoids: the 8.25 CER / RTF 0.038
     * pair everyone quotes is the **chunk-64** export = 1.28 s cadence, a different model at 4×
     * the word lag. Ours is the chunk-16 one. Decoder: `vocab_size = 2460`, `context_size = 2`.
     *
     * **Its digests are corroborated twice.** The repo ships its own `SHA256SUMS`, and its
     * entries for these four files match both the tree API's LFS oids and the sha256 computed
     * locally on the downloaded `tokens.txt` — the only row in the catalogue with an independent
     * second source for its digests.
     *
     * **THE ROW WHOSE COPY CLAIM IS FALSE, and the reason the flags are not decoration.** The
     * census over its real `tokens.txt`: 2,460 lines, 2,457 emittable, of which **2,456 are
     * SINGLE characters** and 2,301 bear a Hangul syllable. Then: **10 standalone ASCII digits**,
     * so `emitsDigits` is **TRUE** and *"no numerals"* is a false sentence for Korean; **58
     * punctuation-only pieces** under this repo's own derivation — 31 of them pure Unicode-P
     * (`. ? , ! ) ( % - : '` plus `‘ ’ “ ” – ·` and fullwidth forms), the rest symbols
     * (`℃ ° ± × ÷ ㎠ ＋ －`) — so `emitsPunctuation` is **TRUE** and the Korean strip will carry
     * marks the English strip never does. And the case counts are genuinely MIXED (27
     * uppercase-bearing / 34 lowercase-bearing emittable pieces), so the fold is **not** provably
     * lossless and this row is [CaseFold.Keep]: folding would paint `nba` over the `NBA` the model
     * produced.
     *
     * `▁` appears **ONCE, as its own token at id 3, and never as a prefix** — so a strip built
     * from `result.text` would lose every Korean word space to `RemoveSpaceBetweenCjk` (whose
     * `IsCJK` range contains Hangul), and the route's tokens-not-text `strip` is what keeps
     * *"Words appear"* true here verbatim.
     *
     * **7 unrenderable tokens** are recorded but NOT filtered by this row: U+FFFD, U+007F and five
     * PUA code points (U+F188, U+F190, U+F191, U+F194, U+F19C). A deny-list in
     * `PreviewText.normalize` keyed on `Cc`/`Cf`/`Co`/`Cn`/`Cs` + U+FFFD benefits every pack and
     * belongs there, not on a row.
     *
     * **Licence — apache-2.0, READ in a 7,926-byte plain-blob front matter AND platform-surfaced**
     * (`cardData.license` + a `license:apache-2.0` tag). **NOT CLEARED, and the question is not
     * the licence.** KsponSpeech is **AI-Hub dataset 123 (NIA)**, whose policy reads
     * *"※ 내국인만 데이터 신청이 가능합니다"*, requires a separate agreement for a party outside
     * Korea and another for export, scopes use to *"training AI learning models"*, bars transfer
     * or sale, and makes **attribution to NIA mandatory and extending to derivative works** —
     * against an Apache-2.0 that disclaims warranty of title (§7), so the risk sits with the
     * licensee. Two counsel questions. One of them (does the NIA attribution reach the app
     * through the weights?) has a 0.1 d answer with no downside — the acknowledgement on the
     * licences screen — and that is not this task's to write.
     */
    val KO = StreamingPack(
        language = "ko",
        dirName = "ko-72m-chunk-16",
        packName = PACK_KO,
        baseUrl = "https://huggingface.co/kangkyu/icefall-asr-ko-streaming-zipformer-72m/resolve/db24b58d22736349eaeb34cc181ad0f3debf9903/",
        encoder = PackFile("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 70_133_869L, "5f2b6e5e92834849cfdbda3aaa355e6f39ff993f067794e4ab9d5cb993b15311"),
        decoder = PackFile("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_544_210L, "40c3c57ad27b45b59e27bec8bfc02f04d27c060aeb8e964ae2f87bd9f356bf7d"),
        joiner = PackFile("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_270_777L, "7d3bd9c1e9cf60efa5d5fed728fbd52d0f08139775f9e9002fd088b4d78f3e73"),
        tokens = PackFile("tokens.txt", 20_844L, "435dfb9e0a2b6a79124f1a4d8f0f33a951b25384726e2e0d854f081533e6ec9d"),
        modelType = "zipformer2",
        decodeChunkLen = 32,
        encoderT = 45,
        // The only row in the catalogue that Keeps its case and sets both copy flags — see the
        // docblock's census. Nothing here is a judgement: all three follow the derivation over
        // this pack's own tokens.txt.
        caseFold = CaseFold.Keep,
        // **THE ONE ROW THIS VALUE IS A DECISION ON AND NOT A CENSUS.** Not one piece in
        // this vocabulary carries the word marker and 2,456 of its 2,457 emittable pieces
        // are single characters, so the file cannot tell Korean from a space-less script:
        // the only boundary it has is the bare `▁` at id 3, and whether a decode emits one
        // is a fact about the decode. The measurement settles it — nine bare markers in one
        // utterance, one per word (`PreviewCanaryClipsTest`'s ko row, whose `text` has lost
        // every one of them to `RemoveSpaceBetweenCjk` while its `tokens` keep them). So
        // *"Words appear"* is true here, and it is true because of the tokens-not-text strip.
        stripUnit = StripUnit.WORDS,
        emitsPunctuation = true,
        emitsDigits = true,
        // **The clip is FLEURS, and it needed NO new rule shape** (4.5.0 T3).
        // `canary_ko_fleurs.wav`, 159,082 B, 79,519 samples = 4.970 s — the longest clip in the
        // catalogue, and the reason is stated below. `google/fleurs` `cc-by-4.0` (see the German
        // row for where that licence was read), config `ko_kr`, split `validation`, row
        // **id 1636**, revision `70bb2e84`; attributed on the licence page. MODIFIED: 16 kHz
        // PCM16 mono via ffmpeg, truncated after a token boundary at 4.8 s + 250 ms from a 6.30 s
        // original. **Not the k2-fsa mirror's `test_wavs`, which is AI-Hub audio.**
        //
        // **THE RULE-SHAPE PROBLEM THIS ROW RECORDED IS REAL AND IS NOT THIS ROW'S.** It said
        // positional matching cannot reach Korean because the clip collapses to one token. The
        // collapse is real and measured — `result.text` is `스페인사람들이삼세기동안지속된시민제시대를시작했다`,
        // one run — but it is a property of the TEXT, which `RemoveSpaceBetweenCjk` strips of every
        // CJK-adjacent space. The TOKENS keep the spaces: `[" ", "스", "페", "인", " ", "사", …]`,
        // the bare `▁` at id 3 emitted once per word. The canary now scores
        // [PreviewText.strip] — the same tokens-not-text rendering the strip paints — so this row
        // gets nine word tokens and ordinary positional matching. The second rule shape the
        // qualification table booked (§6(3)) is unnecessary HERE; a monolingual `zh` row, whose
        // characters carry no word marker at all, would still need it.
        //
        // The six positions are reference words the decode reproduced WHOLE: `스페인 사람들이 동안
        // 지속된 시대를 시작했다`, 6 of 6 in all nine (threads × gain) cells, the 34 tokens
        // byte-identical in every cell. The two it does not reproduce are excluded and say why the
        // clip is honest: `3세기` comes back as the Hangul words `삼 세기` — this model can spell a
        // spoken numeral out as a word instead of writing the digit, which is why `3세기` is not
        // one of the positions — and `식민지` comes back `시민제`, a genuine mis-decode, at 8.25 CER
        // territory for this pack. Expecting either would be expecting something the model did not
        // say.
        //
        // **Neither of those two says anything about `emitsDigits`, and this clip is not what that
        // flag rests on.** Its basis is the census over this pack's own tokens.txt — 10 standalone
        // ASCII digit pieces, the docblock above — held equal to the flag by
        // `PreviewPackMetadataTest.everyRowsCopyFlagsAreWhatItsOwnTokensFileSays`. What the clip
        // shows is the one numeral in THIS utterance reaching the strip as words rather than as a
        // digit, which is neutral evidence for "a digit character can appear" at best.
        //
        // **Why 4.970 s and not 3.8.** At a 3.6 s cut this clip yields four positions, not six;
        // 37 KB bought two more, and with `minMatches = 5` that is the difference between one drop
        // of slack and none. Korean is also the row whose clearance is furthest out (AI-Hub/NIA is
        // a counsel question), so if the bytes are ever wanted back, this is the clip to re-cut.
        canary = PackCanary(
            asset = "canary_ko_fleurs.wav",
            rule = PreviewCanaryRule(
                expected = listOf(
                    setOf("스페인"),
                    setOf("사람들이"),
                    setOf("동안"),
                    setOf("지속된"),
                    setOf("시대를"),
                    setOf("시작했다"),
                ),
                minMatches = 5,
                // Four times the nine strip tokens measured — the English row's own ratio.
                maxTokens = 36,
            ),
        ),
    )

    /**
     * **Chinese** — the **bilingual zh-en** export, `csukuangfj/k2fsa-zipformer-bilingual-zh-en-t`
     * at `e2382758`, `exp/32/`. **49,752,335 B, badged "50 MB"** — a quarter of the 198 MB
     * sibling's bytes for a **byte-identical `tokens.txt`** (`a8e0e4ec…`, verified by hashing both
     * rows' file), and an encoder **0.60× the English pack's**.
     *
     * **It serves the picker's Chinese (#10) and it is the only row in the catalogue that puts
     * anything on the strip when an English speaker talks mid-Chinese.** A Chinese-only pack shows
     * a blank strip at exactly that moment. Verified code-switch output on the shipped
     * configuration is in the qualification table:
     * `这是第一种第二种叫呃与 ALWAYS ALWAYS什么意思啊`.
     *
     * **A `zipformer` V1 export, which the table marked "verify" and this read settles.** Its
     * encoder tail: `model_type = zipformer`, `version = 1`, `decode_chunk_len = 32`, `T = 39`,
     * `num_encoder_layers = 2,2,2,2,2`, `encoder_dims = 256×5`, `attention_dims = 192×5` — and
     * **no `comment`, no `query_head_dims`, no `value_head_dims`, no `num_heads`**, exactly the
     * French shape. So the head-dims question is answered: they are ABSENT, the 198 MB sibling's
     * v1 export is not the odd one out, and the empty `modelType` is what makes this row loadable
     * at all. All seven v1 encoder keys are present; the decoder carries `vocab_size = 6254` and
     * `context_size = 2`.
     *
     * `vocab_size = 6254` against **6,257** lines in `tokens.txt` is HARMLESS on this path: the
     * literal *"number of lines in tokens.txt %d != %d (vocab_size)"* is in the shipped
     * `libsherpa-onnx-jni.so`, but it appears only in NeMo/Canary/Parakeet sources at 1.13.7 and
     * `online-recognizer-transducer-impl.h` has zero hits. The three extra lines are the `#0`,
     * `#1`, `#2` placeholders.
     *
     * **Its four files are under `exp/32/` and `data/lang_char_bpe/`**, so this is the second row
     * [PackFile.path] exists for — and the export directory matters: the repo also ships `exp/64/`
     * and `exp/96/` encoders whose bytes differ by 11 and 12 bytes respectively, which is exactly
     * the kind of neighbour a flat name cannot tell apart.
     *
     * **Licence — apache-2.0, READ in front matter AND platform-surfaced on every link of the
     * chain. NOT CLEARED, and the reason is a NAMED restriction rather than an unknown.** This
     * mirror's own 4,115-byte card says *"Forked from
     * https://huggingface.co/pfluo/k2fsa-zipformer-chinese-english-mixed"* and its env dump reads
     * `'training_subset': 'mix'`; the **fork parent's** card is where `'training_subset':
     * '12k_hour'` appears — WenetSpeech's own name for its L subset, 12,000 h, *"available to
     * download for non-commercial purposes"*, whose publisher **disclaims the audio copyright**
     * and mails a `PASSWORD` through a Google Form. So the corpus attribution is real and is one
     * hop upstream of the bytes we would ship, which is a fact the clearance record has to state
     * precisely: it was read at `pfluo`, not on this row's card.
     *
     * Accuracy, from this row's own card (**UNVERIFIED**, `modified-beam-search` at
     * `decode_chunk_len 64`, neither our decode mode nor our cadence): AiShell-1 4.79,
     * TEST_NET 11.6, TEST_MEETING 12.64. The **English half is UNMEASURED anywhere**, and it is
     * the number the owner's stated workflow turns on.
     */
    val ZH = StreamingPack(
        language = "zh",
        dirName = "zh-en-t-chunk-32",
        packName = PACK_ZH,
        baseUrl = "https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t/resolve/e2382758de9a0219b4efe682b95af30b399db3b8/",
        encoder = PackFile(
            name = "encoder-epoch-99-avg-1.int8.onnx",
            bytes = 42_980_793L,
            sha256 = "db6f51551762e40e549166fe041ea3e45464370b595e9ad23f06478ec3794fbb",
            path = "exp/32/encoder-epoch-99-avg-1.int8.onnx",
        ),
        decoder = PackFile(
            name = "decoder-epoch-99-avg-1.int8.onnx",
            bytes = 3_486_740L,
            sha256 = "4b618d383af304cfae281dbf0a53e8bf442c2f0502256cd5694bd6567ebdd834",
            path = "exp/32/decoder-epoch-99-avg-1.int8.onnx",
        ),
        joiner = PackFile(
            name = "joiner-epoch-99-avg-1.int8.onnx",
            bytes = 3_228_485L,
            sha256 = "bdda356d6f9b8c2d7cee9ee0e26075fa537490f7fd06520be408d287073667b9",
            path = "exp/32/joiner-epoch-99-avg-1.int8.onnx",
        ),
        tokens = PackFile(
            name = "tokens.txt",
            bytes = 56_317L,
            sha256 = "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3",
            path = "data/lang_char_bpe/tokens.txt",
        ),
        modelType = "zipformer",
        decodeChunkLen = 32,
        encoderT = 39,
        // The census over the downloaded file: 6,257 lines, 6,251 emittable, **5,755 pieces bear a
        // single Han character and 5,794 are single characters**, 494 are all-caps Latin BPE at
        // ids 3-496 (`▁AS ▁ONE ▁OF ▁A ▁COMP AN Y` …), **ZERO lowercase**, **ZERO
        // punctuation-only pieces at all**, and NO byte fallback — which is what separates this
        // row from the monolingual `zh` one the table also priced. That row is 0 uppercase / 0
        // lowercase WITH byte fallback and must therefore Keep its case, because its Latin
        // acronyms arrive through `<0xNN>` where no census can see them. This row's Latin is in
        // the vocabulary and single-case, so the fold is PROVABLY lossless and it takes the
        // derivation's suggestion — the strip renders `always`, exactly as the English pack does.
        caseFold = CaseFold.Fold,
        // **THE ROW WITH TWO UNITS AT ONCE**, and both halves are read off this file:
        // **not one of the 5,755 Han-bearing pieces carries the word marker and every
        // one of them is a single character**, so Chinese arrives as a run of characters;
        // all **327** marked pieces are Latin (`▁AS ▁ONE ▁OF ▁A ▁COMP` …), so English in
        // the same utterance arrives as words — which the measured English clip shows
        // spaced and lowercased. No single noun is true of both halves, so the sentence
        // for this row names both ([StreamingPackCopy.stripNote]).
        stripUnit = StripUnit.CHARACTERS_AND_WORDS,
        emitsPunctuation = false,
        // **TRUE by exactly one token: `2` at id 4883.** The shipping pack has zero emittable
        // numerals and this pack breaks that property by one piece, which is a fact about what the
        // user can SEE and so a sentence has to carry it.
        emitsDigits = true,
        // **The one canary that cost nothing: the BUNDLED ENGLISH CLIP, unchanged — and now RUN
        // rather than reasoned about** (4.5.0 T3). Fed `canary_digits.wav`, this pack answers
        // `ONE TWO THREE FOUR FIVE` — exact, five tokens, 5 of 5 positions in all nine
        // (threads × gain) cells. So the row adds **0 B** of asset while paying the same FEAT_SME
        // guard every other language pays, which is why the rule below is the English one's values
        // restated.
        //
        // **One correction to the prediction this row carried.** The vocabulary facts were right —
        // `▁ONE 4`, `▁TWO 376`, `▁THREE 377`, `▁FOUR 478` are whole pieces (re-read here) — but
        // the claim that `FIVE` splits `▁FI 13` + `VE 200` "exactly as the English pack does" is
        // what greedy search did NOT do: it emitted `▁F 273` + `IVE 361`. Both decompositions
        // exist in this vocabulary and both render `FIVE`, so the conclusion held; the prediction
        // of WHICH one a decode takes did not, and that is the difference between a fact read off
        // a file and a fact read off a decode.
        //
        // `GpuCanaryPolicy`'s position-two alias set contains `"2"`, so this row's single numeral
        // (`2` at id 4883, the token that makes `emitsDigits` true) SCORES rather than fails.
        // One residual risk, stated: an English-only clip exercises only the English half of a
        // bilingual model. The Chinese half is unguarded, and closing that needs a Chinese clip —
        // whose decode collapses to one token (see [PreviewCanaryRule] for the Korean measurement
        // of the same mechanism) and so needs a rule shape this build does not have.
        canary = PackCanary(
            asset = CanaryAudio.ASSET,
            rule = PreviewCanaryRule(
                expected = listOf(
                    setOf("one", "1"),
                    setOf("two", "2"),
                    setOf("three", "3"),
                    setOf("four", "4"),
                    setOf("five", "5"),
                ),
                minMatches = 4,
                maxTokens = 20,
            ),
        ),
    )

    val packs: List<StreamingPack> = listOf(EN, FR, DE, RU, ID, KO, ZH)

    /** The pack for a RESOLVED session language; null for auto (null) and for every language without a row. */
    fun forLanguage(code: String?): StreamingPack? = packs.firstOrNull { it.language == code }

    /** "73 MB" for 72,654,782 B — the house decimal convention (ModelTierCopy: "190 MB" for 190,085,487 B). */
    fun sizeBadge(bytes: Long): String = "${megabytes(bytes)} MB"

    /**
     * [sizeBadge]'s rounding, without the unit — for the one place that renders TWO of these in
     * one breath ("12 of 73 MB"). Spelled once so a progress line can never round differently
     * from the badge above it and end at "72 of 72 MB" under a row that says 73.
     */
    fun megabytes(bytes: Long): Long = (bytes + 500_000L) / 1_000_000L

    /** The `.installed` marker's content: the four `sha256␠␠name` lines, written LAST by the installer. */
    fun markerText(pack: StreamingPack): String = pack.files.joinToString("") { "${it.sha256}  ${it.name}\n" }
}
