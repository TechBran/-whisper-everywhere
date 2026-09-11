package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.CanaryAudio
import java.util.Locale

/** One pinned file of a streaming pack: its name at the commit, its EXACT byte count, its sha256. */
data class PackFile(val name: String, val bytes: Long, val sha256: String)

/**
 * What the strip does with the case the model emitted — the decision [PreviewText.normalize]
 * branches on, and **the only place a fold locale exists**.
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
 * So the four values collapse to two ANSWERS, and the locale rides with the one that uses it:
 *
 * | table value | rows | this type |
 * |---|---|---|
 * | `false` | en fr de ru id zh-en pt(lyr) | [Fold] `(Locale.US)` — single-case vocabulary, nothing to lose |
 * | `partial` (448 lower / 34 upper) | tr | [Fold] `(tr)` — **the row the locale is for** |
 * | `TRUE` | ko et es it nl pt(Kroko) ja | [Keep] — the case means something |
 * | `must not case-fold` | zh | [Keep] — byte fallback emits Latin the census cannot see |
 *
 * A [Keep] row has no locale to get wrong, and a [Fold] row cannot fold without stating one. That
 * is the whole point: under the boolean the locale was read only on the fold branch while `tr`
 * derived `true` from its 34 uppercase pieces, so the field could not change one character on any
 * of the fifteen rows.
 *
 * **`PackTokenFacts` derives this from a `tokens.txt` for fourteen of the fifteen** (see
 * `PackTokenFacts.Facts.foldIsProvablyLossless`, which only ever suggests in the safe direction).
 * `tr` is the one row where a human overrode the suggestion — the table rules [Fold] against a
 * mixed census — and an override toward folding is the only direction that can cost a character,
 * so the row that takes it owes a written reason beside it.
 */
sealed interface CaseFold {
    /**
     * Fold the strip to lowercase in THIS locale.
     *
     * `Locale.US` is right wherever the fold is cosmetic, and `java.lang.String`'s own contract is
     * why "cosmetic" is checkable rather than a hope: lowercasing is locale-sensitive for **`tr`,
     * `az` and `lt` only**. So `tr` is the single row in the table whose fold locale changes a
     * character (`İ` → `i` under US, `i̇` under `tr`; `I` → `i` under US, the dotless `ı` under
     * `tr`), which is the hazard [PreviewText.normalize]'s old comment named about English INPUT
     * and got backwards for Turkish OUTPUT.
     */
    data class Fold(val locale: Locale) : CaseFold

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
 * @property caseFold what the strip does with the case this model emitted, and the only place a
 *   fold locale lives — see [CaseFold] for the four-values-to-two-answers table and for why a
 *   boolean over the token census returns the HARMFUL answer for `zh`. `Fold(Locale.US)` here:
 *   English's vocabulary is single-case (495 uppercase-bearing emittable pieces, and the only
 *   lowercase in the file is the three specials no decode emits), so the fold is lossless and the
 *   strip must not shout — byte-for-byte what 4.4.1 rendered. **`Keep` for ko/et/zh and every
 *   Kroko build**, where folding paints `nba` over the `NBA` the model produced and a lowercased
 *   German noun reads as WRONG rather than rough; **`Fold(tr)` for Turkish**, the one row whose
 *   locale changes a character (qualification table §4.1, §4.2). It is the one flag the strip's
 *   own rules branch on; the two below it are copy inputs.
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
 * @property canaryAsset the clip in main assets this pack's load-time canary transcribes. **The
 *   English digits clip cannot pass for a non-English model** — a French recognizer fed "one two
 *   three four five" answers something matching none of the five positions, which is
 *   indistinguishable from the SME silent-miscompute signature the canary exists to catch — so the
 *   clip is the pack's. Recorded cost: one WAV per language, **81,998 B** (the English one's size).
 * @property canaryRule what a PASS means for this pack — see [PreviewCanaryRule], including what
 *   positional matching cannot express (zh and ko collapse the clip to one token and need a
 *   different rule, not a different alias list).
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
    val canaryAsset: String,
    val canaryRule: PreviewCanaryRule,
) {
    val files: List<PackFile> get() = listOf(encoder, decoder, joiner, tokens)
    val totalBytes: Long get() = files.sumOf { it.bytes }
    fun urlOf(file: PackFile): String = baseUrl + file.name

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
 * 50 — and `StreamingPackCopy.BADGE` is still a class-init `val` over THIS row's `totalBytes`,
 * carried by seven sentences. That is a copy defect the moment a second row lands, and it is
 * Task 3's (ruling 3d, *"use the pack's OWN size, never a fixed number"*), not this object's:
 * [sizeBadge] already takes bytes, so the fix is at the call sites.
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
        // overriding it, and `Locale.US` here is cosmetic by String's own contract (tr/az/lt are
        // the only locales it folds differently in).
        caseFold = CaseFold.Fold(Locale.US),
        emitsPunctuation = false,
        emitsDigits = false,
        // The bundled digits clip and the digits rule. The alias sets, the 4-of-5 tolerance and
        // the 20-token runaway ceiling are `GpuCanaryPolicy`'s own values, RESTATED rather than
        // referenced: that object's verdict is a persisted whisper-GPU latch and must not acquire
        // a second caller who can move it. PreviewCanaryTest holds the two ANSWERS equal, so a
        // change to either side is a red test instead of a silent re-scoring of the other.
        canaryAsset = CanaryAudio.ASSET,
        canaryRule = PreviewCanaryRule(
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
