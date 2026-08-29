#!/usr/bin/env python3
"""Extract the 128-bin mel filterbank from a whisper ggml model (Whisper Everywhere 4.1, Task L3).

WHAT THIS PRODUCES, AND WHY IT IS NOT A NEW FILE FORMAT
------------------------------------------------------
A ggml whisper model's layout is::

    magic (uint32)  hparams (11 x int32)  filters.n_mel  filters.n_fft  coefficients  vocab  tensors

...in that order, so everything a mel computation needs is a contiguous PREFIX of the file. The
Whisper Everywhere fork's ``whisper_init_from_file_mel_only`` reads exactly that prefix and stops --
magic, hparams, filterbank, done -- which means a model truncated at the end of its filterbank is
not a damaged file. It *is* a valid mel-only ggml, and the loader accepts it unchanged.

So the app's 128-bin tier ships 102,968 bytes instead of a 574 MB model: ``56 + 128 * 201 * 4``.
There is no donor model to borrow from (every 80-bin whisper carries a byte-identical 80x201 matrix,
which is what the 80-bin tier uses, but the only 128-bin model in the catalog is ``ultra`` and it
need not be installed), and there is no second mel implementation -- the spec allows exactly one mel
in this app, ever.

WHY BOTH DIGESTS ARE ASSERTED
-----------------------------
The SOURCE digest is the provenance claim: these bytes came out of the ``ultra`` tier's own ggml and
not out of some other 128-bin file. It is deliberately ``SHA256_ULTRA`` from
``app/src/main/java/com/whispereverywhere/model/WhisperModel.kt`` -- a value this app already ships
and verifies every download against -- rather than a number this script decided for itself.

The OUTPUT digest is the reproducibility claim, and it is the one ``NpuModelSpec.MELBANK_128_SHA256``
and ``MelbankAssetTest`` also carry. Three readings of one value: if this script and the test each
had their own literal, a wrong regeneration would simply make the two agree about the wrong thing.

The two header checks below (``hparams.n_mels == filters.n_mel == 128`` and ``n_fft == 201``) are the
fork loader's own, run here BEFORE the file is written rather than on a device after it ships.

RUN IT WITH THE ABSOLUTE INTERPRETER
------------------------------------
    C:/Users/bastr/AppData/Local/Programs/Python/Python313/python.exe tools/extract_melbank.py

Bare ``python`` on this machine is the Windows-Store alias stub, which resolves first and does
nothing. ``tools/fetch_qnn_headers.py`` and CMake's ``Python3_EXECUTABLE`` are pinned to the same
absolute path for the same reason.

Optional positional arguments: SOURCE_GGML and OUTPUT_PATH. Both default below.
"""

import hashlib
import os
import struct
import sys

# ---------------------------------------------------------------------------- the pinned values

# The `ultra` tier's ggml, as the catalog records it: WhisperModel.kt's SHA256_ULTRA.
SOURCE_DEFAULT = r"C:\Users\bastr\.androidbuild\WhisperEverywhere\ggml-large-v3-turbo-q5_0.bin"
SOURCE_SHA256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"

# The asset, as NpuModelSpec records it: MELBANK_128_ASSET / _BYTES / _SHA256.
OUTPUT_DEFAULT = os.path.join("app", "src", "main", "assets", "melbank-128.bin")
OUTPUT_SHA256 = "72814246f9837a7afb189ed3850c20cac8a5736e42993b749f86e96370a5157c"
OUTPUT_BYTES = 102968

GGML_FILE_MAGIC = 0x67676D6C

# 4 bytes of magic + eleven int32 hparams + filters.n_mel + filters.n_fft.
HEADER_BYTES = 4 + 11 * 4 + 2 * 4  # 56

EXPECT_N_MEL = 128
EXPECT_N_FFT = 201

HPARAM_NAMES = (
    "n_vocab", "n_audio_ctx", "n_audio_state", "n_audio_head", "n_audio_layer",
    "n_text_ctx", "n_text_state", "n_text_head", "n_text_layer", "n_mels", "ftype",
)


class ExtractionError(RuntimeError):
    """Every refusal below. Named so the failure is a message, never a traceback to decode."""


def sha256_of_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def require(condition, message):
    if not condition:
        raise ExtractionError(message)


def extract(source_path, output_path):
    require(os.path.isfile(source_path), "source ggml not found: %s" % source_path)

    # (1) PROVENANCE, before anything is read as a header. Checked against the catalog's own
    #     SHA256_ULTRA so this script has no opinion of its own about which file is the right one.
    actual_source = sha256_of_file(source_path)
    require(
        actual_source == SOURCE_SHA256,
        "source ggml is not the `ultra` tier's file.\n"
        "  path:     %s\n"
        "  expected: %s  (WhisperModel.kt SHA256_ULTRA)\n"
        "  actual:   %s" % (source_path, SOURCE_SHA256, actual_source),
    )

    with open(source_path, "rb") as handle:
        header = handle.read(HEADER_BYTES)
    require(
        len(header) == HEADER_BYTES,
        "source ggml is shorter than a ggml header (%d of %d bytes)" % (len(header), HEADER_BYTES),
    )

    magic = struct.unpack_from("<I", header, 0)[0]
    require(
        magic == GGML_FILE_MAGIC,
        "bad magic: expected 0x%08x (GGML_FILE_MAGIC), got 0x%08x. This file is not a whisper "
        "ggml model." % (GGML_FILE_MAGIC, magic),
    )

    hparams = dict(zip(HPARAM_NAMES, struct.unpack_from("<11i", header, 4)))
    n_mel, n_fft = struct.unpack_from("<2i", header, 4 + 11 * 4)

    # (2) THE FORK LOADER'S OWN TWO CHECKS, run before the file is written rather than after it
    #     ships. hparams.n_mels is what whisper_model_n_mels() reports and what pcmToMel gates the
    #     caller on; filters.n_mel is what log_mel_spectrogram indexes the coefficients with.
    #     Nothing in the FULL loader compares them, because nothing in the full path depends on
    #     them agreeing -- and a mel-only context is handed to exactly the two functions that read
    #     one each, so a disagreement would be a wrong mel with nothing to attribute it to.
    require(
        hparams["n_mels"] == EXPECT_N_MEL,
        "hparams.n_mels is %d, expected %d" % (hparams["n_mels"], EXPECT_N_MEL),
    )
    require(
        n_mel == EXPECT_N_MEL,
        "filters.n_mel is %d, expected %d" % (n_mel, EXPECT_N_MEL),
    )
    require(
        hparams["n_mels"] == n_mel,
        "the model header disagrees with itself: hparams.n_mels = %d but the filterbank declares "
        "%d bands" % (hparams["n_mels"], n_mel),
    )
    require(
        n_fft == EXPECT_N_FFT,
        "filters.n_fft is %d, expected %d (400/2 + 1 positive frequency bins)" % (n_fft, EXPECT_N_FFT),
    )

    prefix_bytes = HEADER_BYTES + n_mel * n_fft * 4
    require(
        prefix_bytes == OUTPUT_BYTES,
        "the prefix computes to %d bytes but this script is pinned to %d. One of the two is wrong "
        "and it is not safe to guess which." % (prefix_bytes, OUTPUT_BYTES),
    )

    parent = os.path.dirname(os.path.abspath(output_path))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent)

    with open(source_path, "rb") as src:
        payload = src.read(prefix_bytes)
    require(
        len(payload) == prefix_bytes,
        "read %d of %d bytes from the source" % (len(payload), prefix_bytes),
    )
    with open(output_path, "wb") as out:
        out.write(payload)

    # (3) WHAT WAS ACTUALLY WRITTEN, re-read from disk. Hashing `payload` would hash what this
    #     process intended; hashing the file hashes what the next reader will get.
    written = os.path.getsize(output_path)
    require(
        written == OUTPUT_BYTES,
        "wrote %d bytes, expected %d" % (written, OUTPUT_BYTES),
    )
    actual_output = sha256_of_file(output_path)
    require(
        actual_output == OUTPUT_SHA256,
        "the extracted filterbank does not have the pinned digest.\n"
        "  path:     %s\n"
        "  expected: %s  (NpuModelSpec.MELBANK_128_SHA256)\n"
        "  actual:   %s" % (output_path, OUTPUT_SHA256, actual_output),
    )

    print("melbank-128.bin: %d bytes, sha256 %s" % (written, actual_output))
    print("  source:  %s" % source_path)
    print("  header:  n_mels=%d n_fft=%d n_vocab=%d n_text_layer=%d n_text_head=%d"
          % (n_mel, n_fft, hparams["n_vocab"], hparams["n_text_layer"], hparams["n_text_head"]))
    print("  layout:  %d B header + %d x %d x 4 B coefficients" % (HEADER_BYTES, n_mel, n_fft))


def main(argv):
    source_path = argv[1] if len(argv) > 1 else SOURCE_DEFAULT
    output_path = argv[2] if len(argv) > 2 else OUTPUT_DEFAULT
    try:
        extract(source_path, output_path)
    except ExtractionError as failure:
        sys.stderr.write("extract_melbank: %s\n" % failure)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
