package com.whispereverywhere.npu

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Turns whisper token ids into text: drop the specials, join the token strings' **bytes**, decode
 * the result as UTF-8 **once**.
 *
 * ## The one thing to get right
 *
 * A whisper token string is not text. It is a sequence of BYTES re-encoded through GPT-2's
 * byte-level table into printable unicode so that it can live in a JSON key — byte `0x20` becomes
 * `Ġ` (U+0120), byte `0x0A` becomes `Ċ` (U+010A), and the other 186 non-printable bytes get their
 * own placeholder glyphs. Individual tokens routinely stop in the MIDDLE of a multi-byte character:
 * `안녕하세요` arrives as `[49200, 12831, 15377]`, whose first token ends on `EB` — the lead byte of
 * `녕` — and whose second token carries `85 95`, the other two. Two of those three tokens are not
 * valid UTF-8 standing alone.
 *
 * So the order is **join, then decode**. Decoding each token to text and concatenating the strings
 * is the obvious implementation, it is right for English, and it renders `안녕하세요` as `안���하세요`.
 * `WhisperBpeDecoderTest.decodesMultiByteUtf8AcrossTokenBoundary` was written red against exactly
 * that mistake.
 *
 * ## NOTICE — provenance and licence of the vocabulary assets
 *
 * ```
 * Source     openai/whisper-small on Hugging Face. Licence APACHE-2.0 (`license: apache-2.0`,
 *            model-card front matter). Fetched 2026-08-28.
 *
 *   https://huggingface.co/openai/whisper-small/resolve/main/vocab.json
 *     sha256 8f680bba319e01a653d2e8a5dbc17a9157179e0576e6ce74ce0c06356c6e24f9   835,550 B
 *     50,258 entries covering ids 0..50257. (50257 is `<|endoftext|>`; 50256 is whisper's
 *     empty-string slot — a real, readable id that contributes no bytes.)
 *
 *   https://huggingface.co/openai/whisper-small/resolve/main/added_tokens.json
 *     sha256 9715fd2243b6f06a5858b5e32950d2853f73dd5bc201aafcf76f5082a2d8acd1    34,604 B
 *     1,607 entries covering ids 50258..51864.
 *
 * Recipe     both objects inverted to id -> token-string and merged, refusing any id claimed
 *            twice; emitted as ONE compact JSON array whose INDEX is the id
 *            (`ensure_ascii=False`, separators `,` and `:`, no newline anywhere in the file).
 *            Verified at generation: 51,865 entries, no gap in 0..51864, no id claimed twice,
 *            no token string at two ids.
 *
 * Asset      app/src/main/assets/whisper_vocab.json
 *            sha256 96ef2e976694971bb50127a449803b9350aaa037f3e640bd55628856ac7965ba  563,643 B
 *
 * Asset      app/src/main/assets/whisper_vocab_turbo.json                          (4.1 L4)
 *            sha256 9977eaba032c191dc0da8514078626fba6c49b93fe66387774c52760226fa415  563,653 B
 *            51,866 entries — the `large-v3`/turbo family. BUILT by tools/build_turbo_vocab.py
 *            from the asset above (the shared 50,257-token base plus the LARGE_V3 special
 *            layout: <|yue|> inserted after <|su|>, every control id above it one higher) and
 *            VERIFIED against the AI Hub voice_ai package's own vocab.bin (357,313 B, sha256
 *            0ba87984671b92e03b56b84ce9b217020663f6a269b5a9800901391430b79c4b, APACHE-2.0):
 *            exactly 50,257 NUL-terminated tokens, exactly one mismatch, at id 188 — the 0x00
 *            byte token a NUL-terminated table cannot represent. The shipped side carries it.
 *
 * NOT ship   merges.txt (sha256 2df2990a395e35e8dfbc7511e08c12d56018d8d04691e0133e5d63b21e154dc6)
 *            was fetched once to generate the test's golden vectors with the real BPE and has no
 *            runtime role. This tier detokenises; it never tokenises.
 * ```
 *
 * There is no Hugging Face runtime dependency — the spec forbids one. The only library used is
 * `kotlinx-serialization-json`, already a project dependency, and only to parse an array of
 * strings.
 *
 * ## This class is family-agnostic, and that is enforced rather than described
 *
 * Which vocabulary to open and how many ids it must resolve are the SPEC's facts
 * ([NpuModelSpec.vocabAsset] and the family's `vocab`), threaded in by the one call site in
 * `NpuWhisperBackend.load`. The only id this class knows on its own is [WhisperTokenFamily.EOT] —
 * 50257 in every published family, below the language table — which is what makes the drop rule
 * below family-safe. `WhisperTokens` (whisper-small's literal table) appears on no live line of
 * this file, and `WhisperBpeDecoderTest` pins that absence: a constant remembered here was
 * correct for exactly as long as one vocabulary existed, and 4.1 L4 shipped the second.
 *
 * ## Why an array and not an object
 *
 * `{"0":"!","1":"\"",…}` is the same map and costs ~360 KB more in an asset that rides in every
 * APK. More usefully, an array makes completeness STRUCTURAL: a dense `0..51864` cannot have a hole
 * without the length changing, so the one check in [WhisperBpeDecoder]'s constructor covers what
 * would otherwise be 51,865 lookups.
 *
 * ## Error contract — two exception types, two distinct faults
 *
 *  - **[IllegalStateException] — the asset is wrong.** Thrown from [fromJson] (not JSON, or not an
 *    array of strings) and from the constructor (an entry count other than the `expectedSize` the
 *    caller's spec demands, or a token containing a character the byte-level table has no byte
 *    for). Every asset fault is raised at construction, which is why [decode] has no failure mode
 *    that depends on the asset. Q6 catches this one type at load and disables the tier.
 *  - **[IllegalArgumentException] — the CALLER is wrong.** Thrown from [decode] for a token id
 *    outside `0 until size`. `nativeDecodeSegment` signals failure with a NEGATIVE return and
 *    writes ids only into `out[0 until n]`; a `-1` reaching here means the sign check was skipped,
 *    and silently dropping it would render an error as a plausible transcript.
 *
 * A missing asset never reaches this class at all — `AssetManager.open` throws `IOException` first.
 */
class WhisperBpeDecoder(vocabulary: List<String>, expectedSize: Int) {

    /**
     * A **defensive copy**, and it is what makes the immutability this class advertises true.
     *
     * `List<String>` is a read-only *interface*, not an immutable type: the caller may still hold
     * the `ArrayList` behind it. Storing that reference would leave every guarantee below — the
     * entry count, the byte-level alphabet, "no asset-dependent failure mode after construction" —
     * checked once at a moment the caller can undo afterwards, and the symptom would be the same
     * fluent wrong text as a wrong asset, arriving later and from further away. The copy is taken
     * BEFORE the checks run, so what is validated and what is read are the same list.
     */
    private val vocab: List<String> = vocabulary.toList()

    init {
        // The expected size is the CALLER's, never a constant remembered here (4.1 L4). The two
        // shipped vocabularies differ by exactly one entry, and this check is the only thing
        // standing between a spec and the other family's file.
        check(vocab.size == expectedSize) {
            "this vocabulary resolves ${vocab.size} ids but the caller's spec requires exactly " +
                "$expectedSize — the tier's logits width. 51865 is whisper-small and 51866 is " +
                "large-v3/turbo, so a mismatch here is the WRONG FILE for this tier; a " +
                "vocabulary of the wrong size still binds, still decodes, and produces fluent " +
                "wrong text."
        }
        // Every asset fault becomes a load-time refusal, so decode() cannot fail on the asset and
        // is free to walk token strings a Char at a time (no codepoint above U+0143 exists in a
        // valid vocabulary, so no surrogate pair can appear on the ENCODED side).
        for (id in vocab.indices) {
            for (ch in vocab[id]) {
                val code = ch.code
                check(code < UNICODE_TO_BYTE.size && UNICODE_TO_BYTE[code] >= 0) {
                    "vocabulary id $id ('${vocab[id]}') contains U+" +
                        Integer.toHexString(code).uppercase().padStart(4, '0') +
                        ", which is not in GPT-2's byte-level alphabet. Token strings are bytes " +
                        "re-encoded as printable unicode; a character outside that alphabet means " +
                        "this is not a byte-level BPE vocabulary."
                }
            }
        }
    }

    /** Entry count. Always the `expectedSize` handed in — the constructor refused anything else. */
    val size: Int get() = vocab.size

    /**
     * The raw token string at [id], byte-level encoding and all — `"Ġworld"`, `"<|fr|>"`.
     *
     * For diagnostics and for the tests that pin the asset's special ids against [WhisperTokens].
     * Not part of the transcription path: [decode] is.
     */
    fun tokenAt(id: Int): String = vocab[id]

    /**
     * Ids in, text out.
     *
     * Every id at or above [WhisperTokenFamily.EOT] is dropped **wherever it appears**, not just at
     * the ends. That is required, not defensive: Q4's decode loop deliberately leaves the language
     * ids and `<|notimestamps|>` unsuppressed (`nativeDetectLanguage` argmaxes over the language
     * block, so masking it would break detection), so one CAN surface mid-transcript, and this is
     * the only place it is removed. The cutoff needs no family: EOT is 50257 in every published
     * family — it sits below the language table, which is where the two layouts diverge — so one
     * constant drops each family's own specials when this decoder holds that family's vocabulary.
     *
     * A truncated multi-byte tail — reachable, since the token budget and the position cap can both
     * cut mid-character — becomes U+FFFD. The user gets one bad glyph; they do not get an exception
     * out of a transcription that otherwise succeeded.
     */
    fun decode(tokens: IntArray): String {
        val bytes = java.io.ByteArrayOutputStream(tokens.size * 4)
        for (i in tokens.indices) {
            val id = tokens[i]
            require(id >= 0 && id < vocab.size) {
                "token id $id at position $i is outside 0 until ${vocab.size}. " +
                    "nativeDecodeSegment returns a NEGATIVE value on failure and writes ids only " +
                    "into out[0 until n]; dropping this id silently would turn an error into a " +
                    "plausible transcript."
            }
            if (id >= WhisperTokenFamily.EOT) continue
            val token = vocab[id]
            for (ch in token) bytes.write(UNICODE_TO_BYTE[ch.code])
        }
        // ONE decode, over the JOINED bytes. See the class KDoc.
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    companion object {

        // NO ASSET_NAME (4.1 L4). Which file to open is the spec's decision — NpuModelSpec's
        // `vocabAsset`, read at the one call site in NpuWhisperBackend.load — and a name constant
        // here would be that decision re-made locally, exactly the shape the deleted
        // `WhisperTokens.VOCAB` size check had: correct for as long as one vocabulary existed.

        private val JSON = Json {}

        /**
         * Parses a vocabulary asset. See the error contract in the class KDoc: everything that can
         * be wrong with the asset surfaces here or in the constructor as [IllegalStateException].
         * The message names the material rather than a file, because since 4.1 L4 this class does
         * not know which asset it was handed — the caller does, and prefixes its stage detail.
         *
         * Q6 calls this **off the main thread** — half a megabyte of JSON and ~52k strings — and
         * holds the result for the life of the session. It is immutable and thread-safe once built.
         *
         * @param expectedSize the entry count the caller's spec demands — the tier's logits width,
         *        `spec.tokens.vocab`. Required, never defaulted: a default here is whisper-small's
         *        table leaking back in as a convenience.
         */
        fun fromJson(json: String, expectedSize: Int): WhisperBpeDecoder {
            val vocab = try {
                JSON.decodeFromString(ListSerializer(String.serializer()), json)
            } catch (cause: Exception) {
                throw IllegalStateException(
                    "the vocabulary asset is not a JSON array of token strings " +
                        "(${cause.javaClass.simpleName})",
                    cause,
                )
            }
            return WhisperBpeDecoder(vocab, expectedSize)
        }

        /**
         * GPT-2's byte-level alphabet, reversed: `UNICODE_TO_BYTE[codepoint]` is the byte, or `-1`.
         *
         * Built exactly as `bytes_to_unicode()` does, and the construction IS the specification —
         * bytes `33..126`, `161..172` and `174..255` stand for themselves (188 of them), and the
         * remaining 68 are re-encoded in ascending byte order as `256, 257, … 323`. So `Ġ` is byte
         * 32 (the 33rd unmapped byte, `256 + 32 = 288`) and `Ċ` is byte 10 (`256 + 10 = 266`).
         *
         * 324 entries is exact, not padding: the largest codepoint any token can contain is
         * U+0143 = 323, which is byte 173 — and the shipped asset does use it (257 tokens contain
         * it), so the table's top end is exercised rather than assumed.
         */
        private val UNICODE_TO_BYTE: IntArray = run {
            val standsForItself = BooleanArray(256)
            for (b in 33..126) standsForItself[b] = true
            for (b in 161..172) standsForItself[b] = true
            for (b in 174..255) standsForItself[b] = true

            val table = IntArray(324) { -1 }
            for (b in 0..255) if (standsForItself[b]) table[b] = b
            var next = 256
            for (b in 0..255) if (!standsForItself[b]) table[next++] = b
            table
        }
    }
}
