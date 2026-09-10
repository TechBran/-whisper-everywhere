package com.whispereverywhere.transcription.stream

/** One pinned file of a streaming pack: its name at the commit, its EXACT byte count, its sha256. */
data class PackFile(val name: String, val bytes: Long, val sha256: String)

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
) {
    val files: List<PackFile> get() = listOf(encoder, decoder, joiner, tokens)
    val totalBytes: Long get() = files.sumOf { it.bytes }
    fun urlOf(file: PackFile): String = baseUrl + file.name
}

/**
 * The packs the previewer can run. One row today: `streaming-zipformer-en-2023-06-26` (66 M,
 * int8, Apache-2.0, LibriSpeech), the four files re-hashed on the PC (rung 1 §1.2) and on the
 * Tab (rung 3 §1.2, §7). A second language is a second row whose licence cell is green first
 * (research §2.5) — no per-language cards, no chooser change.
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
    )

    val packs: List<StreamingPack> = listOf(EN)

    /** The pack for a RESOLVED session language; null for auto (null) and for every language without a row. */
    fun forLanguage(code: String?): StreamingPack? = packs.firstOrNull { it.language == code }

    /** "73 MB" for 72,654,782 B — the house decimal convention (ModelTierCopy: "190 MB" for 190,085,487 B). */
    fun sizeBadge(bytes: Long): String = "${(bytes + 500_000L) / 1_000_000L} MB"

    /** The `.installed` marker's content: the four `sha256␠␠name` lines, written LAST by the installer. */
    fun markerText(pack: StreamingPack): String = pack.files.joinToString("") { "${it.sha256}  ${it.name}\n" }
}
