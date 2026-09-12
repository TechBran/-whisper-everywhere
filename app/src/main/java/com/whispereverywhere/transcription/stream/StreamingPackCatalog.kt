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
 *   the two below it are copy inputs.
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
 *   not been SOURCED yet and the verdict is [CanaryVerdict.NoClip] — **no verdict, never a
 *   failure**; [PackCanary]'s docblock says why that is a third answer rather than a missing
 *   value. See also [PreviewCanaryRule] for what positional matching cannot express (zh and ko
 *   collapse the clip to one token and need a different RULE, not a different alias list).
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
     * The zeros the commit hook pads before `inputFinished`, DERIVED from [encoderT] — see
     * [StreamingPreviewTuning.padMsFor] for why a flat 500 drops the last word of every utterance
     * on a `T = 77` pack, and why the measured 500 is that derivation's floor.
     */
    val padMs: Long get() = StreamingPreviewTuning.padMsFor(encoderT)
}

/**
 * The packs the previewer can run. One row today: `streaming-zipformer-en-2023-06-26`
 * (**72,654,782 B — badged "73 MB"**, int8, Apache-2.0, LibriSpeech), the four files re-hashed on
 * the PC (rung 1 §1.2) and on the Tab (rung 3 §1.2, §7). A second language is a second row whose
 * licence cell is green first (research §2.5) — no per-language cards, no chooser change.
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

    val packs: List<StreamingPack> = listOf(EN)

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
