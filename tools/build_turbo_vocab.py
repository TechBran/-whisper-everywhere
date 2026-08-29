#!/usr/bin/env python3
"""Build the large-v3/turbo vocabulary asset from the shipped base (Whisper Everywhere 4.1, L4).

WHAT THIS PRODUCES
------------------
``app/src/main/assets/whisper_vocab_turbo.json`` -- 51,866 token strings, a compact JSON array
whose INDEX is the id, exactly like the shipped ``whisper_vocab.json`` (51,865). The two published
whisper families share one 50,257-token base BPE; everything above it is a fixed special layout
derived from the language count, and ``large-v3`` has one more language (``<|yue|>``, Cantonese)
than ``whisper-small``. So the turbo file is the small file with ONE entry inserted after
``<|su|>`` (id 50357)::

    turbo = small[0:50358] + ["<|yue|>"] + small[50358:]

Every spelling therefore matches the shipped small asset's EXACTLY -- including ``<|nocaptions|>``,
which is what that asset uses where Hugging Face writes ``<|nospeech|>`` -- so the two files stay
diffable: one insertion apart, nothing respelled.

THE VERIFICATION IS THE POINT, AND IT IS A REAL CROSS-CHECK
-----------------------------------------------------------
The base half is verified against **turbo's own published tokenizer table**: the AI Hub package's
``voice_ai/vocab.bin`` (357,313 B, Apache-2.0, NUL-terminated raw-byte tokens in id order). Each
raw token is re-encoded through GPT-2's byte-level table -- the same construction
``WhisperBpeDecoder.UNICODE_TO_BYTE`` reverses -- and compared against the base this script just
copied. The check requires **exactly EXPECT_BASE_TOKENS tokens and exactly one mismatch, at id
EXPECT_MISMATCH_ID (188)**, and fails otherwise.

Id 188 is the ``0x00`` byte token, stored as U+0100 in the shipped asset. A NUL-terminated table
cannot represent it -- the byte IS the terminator -- so ``vocab.bin`` necessarily lacks it, and the
shipped side is the CORRECT side of that single difference. That is the research doc's blocker #3,
closed with a measurement. Id 50256 is legitimately the empty string in both, so it is not a second
casualty. A *second* mismatch anywhere would mean the two families' base vocabularies are not the
same vocabulary -- the assumption the whole family design rests on -- hence the exact count and the
exact id, never a threshold.

``vocab.bin`` is the VERIFIER, not the source: sourcing from it would ship a base with a hole at
id 188.

RUN IT WITH THE ABSOLUTE INTERPRETER
------------------------------------
    C:/Users/bastr/AppData/Local/Programs/Python/Python313/python.exe tools/build_turbo_vocab.py

Bare ``python`` on this machine is the Windows-Store alias stub, which resolves first and does
nothing. ``tools/extract_melbank.py`` and CMake's ``Python3_EXECUTABLE`` are pinned to the same
absolute path for the same reason.

Optional positional arguments: BASE_ASSET, VOCAB_BIN and OUTPUT_PATH. All default below.
"""

import hashlib
import json
import os
import sys

# ---------------------------------------------------------------------------- the pinned values

BASE_DEFAULT = os.path.join("app", "src", "main", "assets", "whisper_vocab.json")
BASE_SHA256 = "96ef2e976694971bb50127a449803b9350aaa037f3e640bd55628856ac7965ba"
BASE_ENTRIES = 51865

# Turbo's own published tokenizer table, out of the AI Hub `voice_ai` package (Apache-2.0).
VOCAB_BIN_DEFAULT = r"C:\Users\bastr\.androidbuild\npu-model-lab\voice_ai_extras\vocab.bin"
VOCAB_BIN_SHA256 = "0ba87984671b92e03b56b84ce9b217020663f6a269b5a9800901391430b79c4b"

OUTPUT_DEFAULT = os.path.join("app", "src", "main", "assets", "whisper_vocab_turbo.json")
OUTPUT_SHA256 = "9977eaba032c191dc0da8514078626fba6c49b93fe66387774c52760226fa415"
OUTPUT_ENTRIES = 51866

# The GPT-2 base both families share, and the ONE token NUL-termination cannot carry.
EXPECT_BASE_TOKENS = 50257
EXPECT_MISMATCH_ID = 188   # the 0x00 byte token, U+0100 byte-level-encoded; see the module KDoc

# Where the small layout's specials sit, asserted before the insertion so a wrong base fails here
# rather than shipping. <|su|> is whisper-small's LAST language; <|yue|> is inserted after it.
INSERT_AT = 50358          # small[50358] == "<|translate|>"; turbo[50358] == "<|yue|>"
EXPECT_SMALL_SPECIALS = {
    50257: "<|endoftext|>",
    50258: "<|startoftranscript|>",
    50259: "<|en|>",
    50357: "<|su|>",
    50358: "<|translate|>",
    50359: "<|transcribe|>",
    50360: "<|startoflm|>",
    50361: "<|startofprev|>",
    50362: "<|nocaptions|>",   # the shipped asset's spelling; HF writes <|nospeech|>
    50363: "<|notimestamps|>",
    50364: "<|0.00|>",
    51864: "<|30.00|>",
}


class BuildError(RuntimeError):
    """Every refusal below. Named so the failure is a message, never a traceback to decode."""


def require(condition, message):
    if not condition:
        raise BuildError(message)


def sha256_of_bytes(data):
    return hashlib.sha256(data).hexdigest()


def byte_to_unicode():
    """GPT-2's ``bytes_to_unicode()``, byte -> printable character.

    Bytes 33..126, 161..172 and 174..255 stand for themselves (188 of them); the remaining 68 are
    re-encoded in ascending byte order as 256, 257, ... 323. The same construction, in reverse, is
    ``WhisperBpeDecoder.UNICODE_TO_BYTE`` -- two independent transcriptions of one table.
    """
    stands = list(range(33, 127)) + list(range(161, 173)) + list(range(174, 256))
    table = {}
    next_code = 256
    for b in range(256):
        if b in stands:
            table[b] = chr(b)
        else:
            table[b] = chr(next_code)
            next_code += 1
    return table


def build(base_path, vocab_bin_path, output_path):
    require(os.path.isfile(base_path), "base asset not found: %s" % base_path)
    require(os.path.isfile(vocab_bin_path), "vocab.bin not found: %s" % vocab_bin_path)

    # (1) THE BASE, provenance-checked against the digest WhisperBpeDecoder's NOTICE block records
    #     for the shipped asset, so this script has no opinion of its own about which file is the
    #     right one.
    base_raw = open(base_path, "rb").read()
    actual_base = sha256_of_bytes(base_raw)
    require(
        actual_base == BASE_SHA256,
        "the base asset is not the shipped whisper_vocab.json.\n"
        "  path:     %s\n"
        "  expected: %s\n"
        "  actual:   %s" % (base_path, BASE_SHA256, actual_base),
    )
    small = json.loads(base_raw.decode("utf-8"))
    require(
        len(small) == BASE_ENTRIES,
        "the base asset resolves %d ids, expected %d" % (len(small), BASE_ENTRIES),
    )
    for token_id, token in sorted(EXPECT_SMALL_SPECIALS.items()):
        require(
            small[token_id] == token,
            "base id %d is %r, expected %r -- the small special layout is not where the "
            "insertion arithmetic assumes it is" % (token_id, small[token_id], token),
        )

    # (2) THE CROSS-CHECK, against turbo's own table. Split on the terminator; a trailing NUL
    #     yields one trailing empty element, which is the terminator's, not a token.
    bin_raw = open(vocab_bin_path, "rb").read()
    actual_bin = sha256_of_bytes(bin_raw)
    require(
        actual_bin == VOCAB_BIN_SHA256,
        "vocab.bin is not the measured AI Hub table.\n"
        "  path:     %s\n"
        "  expected: %s\n"
        "  actual:   %s" % (vocab_bin_path, VOCAB_BIN_SHA256, actual_bin),
    )
    raw_tokens = bin_raw.split(b"\x00")
    if raw_tokens and raw_tokens[-1] == b"":
        raw_tokens.pop()
    require(
        len(raw_tokens) == EXPECT_BASE_TOKENS,
        "vocab.bin splits into %d NUL-terminated tokens, expected exactly %d -- the GPT-2 base "
        "both families share. Any other count means this is not that table."
        % (len(raw_tokens), EXPECT_BASE_TOKENS),
    )
    encode = byte_to_unicode()
    mismatches = [
        i for i, raw in enumerate(raw_tokens)
        if "".join(encode[b] for b in raw) != small[i]
    ]
    require(
        mismatches == [EXPECT_MISMATCH_ID],
        "the byte-level-encoded vocab.bin must differ from the shipped base at EXACTLY one id, "
        "%d -- the 0x00 byte token a NUL-terminated table cannot represent. Got %d mismatch(es): "
        "%s. A second mismatch anywhere means the two families do not share a base vocabulary, "
        "which is the assumption this whole asset rests on."
        % (EXPECT_MISMATCH_ID, len(mismatches), mismatches[:10]),
    )
    require(
        small[EXPECT_MISMATCH_ID] == "\u0100" and raw_tokens[EXPECT_MISMATCH_ID] == b"",
        "the one mismatch is not the known NUL casualty: shipped id %d is %r and vocab.bin's is "
        "%r" % (EXPECT_MISMATCH_ID, small[EXPECT_MISMATCH_ID], raw_tokens[EXPECT_MISMATCH_ID]),
    )

    # (3) THE INSERTION. One entry, after <|su|>; everything else is the small file verbatim.
    turbo = small[:INSERT_AT] + ["<|yue|>"] + small[INSERT_AT:]
    require(
        len(turbo) == OUTPUT_ENTRIES,
        "the built vocabulary resolves %d ids, expected %d" % (len(turbo), OUTPUT_ENTRIES),
    )
    require(turbo[50358] == "<|yue|>", "turbo id 50358 is %r, expected <|yue|>" % turbo[50358])
    require(
        turbo[50359] == "<|translate|>" and turbo[50364] == "<|notimestamps|>"
        and turbo[50365] == "<|0.00|>" and turbo[51865] == "<|30.00|>",
        "the shifted special layout is not where the derivation puts it",
    )

    payload = json.dumps(turbo, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    parent = os.path.dirname(os.path.abspath(output_path))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent)
    with open(output_path, "wb") as out:
        out.write(payload)

    # (4) WHAT WAS ACTUALLY WRITTEN, re-read from disk. Hashing `payload` would hash what this
    #     process intended; hashing the file hashes what the next reader will get.
    written_raw = open(output_path, "rb").read()
    actual_output = sha256_of_bytes(written_raw)
    require(
        actual_output == OUTPUT_SHA256,
        "the built vocabulary does not have the pinned digest.\n"
        "  path:     %s\n"
        "  expected: %s  (TurboVocabAssetTest.TURBO_SHA256)\n"
        "  actual:   %s" % (output_path, OUTPUT_SHA256, actual_output),
    )
    reread = json.loads(written_raw.decode("utf-8"))
    require(
        len(reread) == OUTPUT_ENTRIES and reread == turbo,
        "the file on disk does not round-trip to the list this process built",
    )

    print("whisper_vocab_turbo.json: %d entries, %d bytes, sha256 %s"
          % (len(reread), len(written_raw), actual_output))
    print("  base:      %s (%d entries, verified)" % (base_path, len(small)))
    print("  verifier:  %s (%d tokens, 1 known mismatch at id %d)"
          % (vocab_bin_path, len(raw_tokens), EXPECT_MISMATCH_ID))


def main(argv):
    base_path = argv[1] if len(argv) > 1 else BASE_DEFAULT
    vocab_bin_path = argv[2] if len(argv) > 2 else VOCAB_BIN_DEFAULT
    output_path = argv[3] if len(argv) > 3 else OUTPUT_DEFAULT
    try:
        build(base_path, vocab_bin_path, output_path)
    except BuildError as failure:
        sys.stderr.write("build_turbo_vocab: %s\n" % failure)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
