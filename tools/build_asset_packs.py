#!/usr/bin/env python3
"""Measure -- and, from F4, build -- the per-SoC NPU asset packs (4.2 F3/F4).

``measure`` is the instrument behind ``NpuFleetCensus.artifacts``: nobody had ever downloaded
the v79/v81/v73 packages, so their digests existed nowhere, and a census row nobody measured
is the one unforgivable output. For each of the two w8a16 models it fetches the pinned Hugging
Face ``release_assets.json`` (the pinned RELEASE asserted -- a different release string is a hard
failure naming both), resolves the ``precompiled_qnn_onnx`` zip URL for each census family's
chipset key, and holds every zip to the same gates:

  1. HEAD first: HTTP 200; ``Last-Modified`` on the pinned hash-stable re-upload event the
     research pinned (a bucket rewrite fails loudly rather than silently measuring new bytes);
     ``Content-Length`` asserted against EXPECTED_ZIP_BYTES where a measurement already existed
     (the four turbo zips + the 8gen3 small zip) and recorded where not (the other three small
     zips).
  2. Download to the workspace (skipped when the local copy already matches the exact length --
     the 4.1 turbo zip and workspace re-runs cost nothing), then ``zipfile.testzip()``: every
     entry CRC-clean.
  3. The vendor ``metadata.json``: ``chipset_attributes.htp_version`` must equal the census
     family's HTP version, the chipset must self-describe as the key we asked for, and the
     encoder/decoder IO census (input count, output count, input bytes, output bytes -- shape
     product times dtype width) must equal ``NpuModelSpec``'s row for the tier. This is the
     executed form of "per-SoC packs carry the SAME model": same graphs, same shapes, same
     byte totals, only the Hexagon target differs.
  4. Stream-extract the two context binaries (sha256 during the copy, never a second read) and
     print one census row.

The 8gen3 rows must reproduce the four digests the catalog already pins -- the run's
self-check: if the instrument cannot re-measure the two pairs a device has executed, none of
its other rows deserve belief. (At a vendor REBUILD that check cannot hold by construction --
v0.63.0 replaced every digest -- so the catalog moves with the census from one measurement, and
the self-check becomes the second run: every row re-measured from the gated zips and matched to
the literals just pasted in. The pair a device executed is then a device check still owed.)

CENSUS below embeds every row's two digests as literals. ``NpuFleetCensusTest`` reads this file
and asserts every ``NpuFleetCensus.artifacts`` digest appears here -- the ``pack_npu_zip.py``
pattern, so the committed census and the instrument that fills the packs cannot drift apart.
Nothing binary is ever committed: the workspace lives outside the repo, and the census output
is DATA (digests, sizes, dates).

``build`` (F4) assembles every census row's pack variant into the two asset-pack modules'
``src/main/assets/<module>#group_<packGroup>/`` dirs -- RAW bins under the census's delivery
names (turbo's renamed ``turbo_*`` -- every vendor zip carries the same two bare names, so
an unrenamed turbo pack could overwrite the npu pair) plus OUR ``metadata.json`` written from
the census. It runs ``measure`` first (the F3 handoff: packs are always built from
gate-verified bytes; idempotent and cheap on a warm workspace), streams each binary with
sha256 riding the copy, asserts the census literals, then RE-VERIFIES what landed through the
importer's own logic: exactly three files, both bins re-read and re-hashed to the census, the
metadata parsed strictly and cross-checked equal to the census row. The default
``<module>#group_other/`` dirs must carry nothing but ``.gitkeep`` -- the empty-default rule
the ``verifyNpuPacks`` Gradle gate re-proves before every bundle build. (F8: the fallback
names the implicit ``other`` group instead of being an unsuffixed sibling, which bundletool
refuses -- "must have exactly one device group, but found []".)

``delivery-zip <familyId> <tierId>`` (F4) writes the per-family SAF sideload zip: OUR
``metadata.json`` FIRST -- and with its size DECLARED in the local header (``writestr``), plus
a data-descriptor refusal on every entry, because the app's import peek triggers on
``ZipEntry.getSize()`` and a streamed entry declaring -1 silently skips it -- then the two
binaries under the census's delivery names. Same verification, then the zip's own sha256 for
publishing beside the file. ``tools/pack_npu_zip.py`` is untouched: its pins stand, and it
remains the 8gen3 recipe the 4.1 acceptance used.

``preview`` (4.4.0, owner ruling 2026-09-10; ALL SEVEN packs since the 2026-09-12 ruling "let's
set up all 6 languages") places the STREAMING PREVIEWER's pack payloads: four raw files per pack
into ``preview_<lang>/src/main/assets/preview_<lang>/``, each streamed with sha256 riding the copy
and ASSERTED against that row's own ``StreamingPackCatalog`` byte count and digest (restated here
as literals; ``PreviewPackLayoutTest`` pins the two tables equal, row by row), then each directory
re-verified from disk before the next pack is touched. Files are taken from that pack's local
mirror when it has them at the right size and hash, and otherwise downloaded from the
COMMIT-PINNED Hugging Face base — ``resolve/<sha>/``, never ``resolve/main`` — so a clean clone
reproduces every pack. A file's UPSTREAM path is not always its flat name: German's four files and
the bilingual zh-en row's live in subdirectories (German's with commas in the ONNX filenames), so
the download reads ``path`` and the placement writes ``name``. Unlike the NPU packs these are NOT
device-targeted: one untargeted directory each, every device, no ``#group_`` variants and no empty
default to keep empty.

Usage:
    python build_asset_packs.py measure [workspace]
    python build_asset_packs.py build [workspace]
    python build_asset_packs.py delivery-zip <familyId> <tierId> [workspace]
    python build_asset_packs.py preview [mirror-root]

    workspace   defaults to C:\\Users\\bastr\\.androidbuild\\fleet-packs
    mirror-root defaults to C:\\Users\\bastr\\.androidbuild\\streaming-models, with one
                subdirectory per pack named after that row's own catalogue `dirName`
                (en-2023-06-26, fr-2023-04-14, de-cv17-epoch-30, ru-vosk-2025-08-16,
                id-iter-100000, ko-72m-chunk-16, zh-en-t-chunk-32), each holding the FLAT
                file names. Neither the root nor any subdirectory need exist: a missing
                file is fetched from that pack's pinned commit instead
"""

import collections
import hashlib
import os
import sys
import urllib.request
import zipfile

RELEASE = "0.63.0"

# The hash-stable re-upload event. Substring-matched against the RFC 1123 Last-Modified header,
# so a bucket rewrite on any later date fails the HEAD gate by name.
#
# 2026-09-22: was "25 Aug 2026" (release v0.61.0, research doc
# 2026-08-29-pad-soc-delivery.md section 7). Qualcomm rebuilt every pack with QAIRT 2.45.0 for
# v0.62.2 and re-uploaded on 11 Sep 2026 — small at 20:36, turbo at 20:34, so the DAY covers
# both models. This gate was the THIRD independent guard to refuse the new bytes, after the
# release string and the zip-length pins. All three named the same fact, which is the point of
# having three: no single edit can wave a vendor rebuild through.
#
# 2026-09-24: was "11 Sep 2026". v0.63.0 is a real rebuild with QAIRT 2.50.0.260828221209 (every
# w8a16 asset's tool_versions.qairt says so), and the bucket serves all twelve objects dated
# Wed, 23 Sep 2026 — small 21:58:18-19 GMT, turbo 21:56:00-02 GMT — read off a HEAD of each
# before this pin moved. Each of the three guards refuses these bytes on its own: the manifest
# says 0.63.0, the day is 23 Sep, and every one of the ten pinned lengths moved.
LAST_MODIFIED_DAY = "23 Sep 2026"

# Portable since the 2026-09-22 Linux port: the workspace holds multi-GB vendor zips and
# lives outside the repo on whatever machine is measuring. An explicit argument still wins.
DEFAULT_WORKSPACE = os.path.join(os.path.expanduser("~"), ".androidbuild", "fleet-packs")

# Vendor bare entry names -- identical across BOTH models and ALL families (the 4.1 L8
# measurement); located by bare name wherever the vendor nested them, ambiguity refused.
VENDOR_ENCODER = "encoder_qairt_context.bin"
VENDOR_DECODER = "decoder_qairt_context.bin"
VENDOR_METADATA = "metadata.json"

DTYPE_BYTES = {"uint8": 1, "uint16": 2, "int32": 4}

CHUNK = 1 << 20  # 1 MiB

# tier id -> the model's manifest URL, its DELIVERY filenames (the catalog's, which is what
# lands on a device -- turbo's are renamed so a turbo import can never overwrite the npu
# pair), and the tier's IO census: (inputs, outputs, input bytes, output bytes) per graph,
# NpuModelSpec's derived row restated as literals so a spec drift is a decision, not a
# follow-on.
MODELS = {
    "npu": {
        "manifest_url": "https://huggingface.co/qualcomm/Whisper-Small-Quantized/"
                        "resolve/main/release_assets.json",
        "delivery_encoder": "encoder_qairt_context.bin",
        "delivery_decoder": "decoder_qairt_context.bin",
        "encoder_census": (1, 24, 480_000, 27_648_000),
        "decoder_census": (51, 25, 31_316_376, 3_771_698),
    },
    "npu-turbo": {
        "manifest_url": "https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized/"
                        "resolve/main/release_assets.json",
        "delivery_encoder": "turbo_encoder_qairt_context.bin",
        "delivery_decoder": "turbo_decoder_qairt_context.bin",
        "encoder_census": (1, 8, 768_000, 15_360_000),
        "decoder_census": (19, 9, 17_398_168, 2_141_492),
    },
}

# census family id -> (the vendor manifest's chipset key, HTP version, Play device group).
# THE CHIPSET MAPPING LIVES HERE, deliberately not in NpuFleetCensus: the app never talks to
# the vendor bucket, and a runtime field nothing at runtime reads would be one more string to
# keep true (F1 handoff). The pack group IS a census field (NpuSocFamily.packGroup) restated
# for the build side -- NpuPackLayoutTest pins each htp/packGroup pairing here equal to the
# census, so the payload dirs cannot drift from the device-group XML.
FAMILIES = {
    "8gen3": ("qualcomm-snapdragon-8gen3", 75, "soc_8gen3"),
    "8elite_galaxy": ("qualcomm-snapdragon-8-elite-for-galaxy", 79, "soc_8elite_galaxy"),
    "8elite5_galaxy": ("qualcomm-snapdragon-8-elite-gen5-for-galaxy", 81, "soc_8elite5_galaxy"),
    "7gen4": ("qualcomm-snapdragon-7gen4", 73, "soc_7gen4"),
    # 8 Gen 2 (SM8550). Added 2026-09-22 after the qcs8550-proxy pack was DEVICE-EXECUTED on an
    # S23 Ultra: soc_model 43 / htp_version 73, encode p50 2,472 ms, 37% of the 8 s commit floor.
    # The vendor key says "proxy" because AI Hub benchmarks the embedded QCS8550; the binary is
    # compiled for soc_model 43, which is the SM8550's own number — that is why this is a family
    # and not the cross-load CPU_BY_CENSUS rejected (7 Gen 4's, compiled for soc_model 86).
    "qcs8550": ("qualcomm-qcs8550-proxy", 73, "soc_qcs8550"),
    # 8 Gen 1 (SM8450) — the Galaxy S22, S22+ and S22 Ultra (Snapdragon), the Galaxy Tab S8,
    # S8+ and S8 Ultra, and the S23 FE's Snapdragon build. Added 2026-09-24 at v0.63.0, the first
    # release to publish this key; it was CPU_BY_CENSUS as "no published w8a16 package" before.
    # HTP v69, the oldest architecture in the census and the only one no other family shares.
    # The 8+ Gen 1 (SM8475) is NOT this family: a different die with its own soc_model, and no
    # package for it exists under any key.
    "8gen1": ("qualcomm-snapdragon-8gen1", 69, "soc_8gen1"),
}


# tier id -> the asset-pack MODULE that ships it. The delivery names above are per-TIER; the
# module split is what lets Play deliver small without turbo (and price the fetch decision per
# tier in the app's UI).
PACK_MODULE_BY_TIER = {"npu": "npu_small", "npu-turbo": "npu_turbo"}

# Vendor zip Content-Length, asserted at HEAD for every row the census has measured. HISTORY: the
# 0.61.0 table carried only five (the four turbo zips from research section 7 and the 8gen3 small
# zip) because three small zips had never been measured; the 0.62.2 re-measurement pinned all
# ten rows it then had.
#
# RE-MEASUREMENT 2026-09-24: emptied for the v0.63.0 pass. Every length here described 0.62.2
# bytes, and Qualcomm rebuilt every pack with QAIRT 2.50.0 — the ten HEADs read before this edit
# are all SMALLER than their old pins, by 8.0 to 9.7 MB (small) and 36.0 to 43.1 MB (turbo).
# head_gate RECORDS a length it has no pin for, which is exactly the mode a re-measurement wants;
# the measured lengths return here.
# Every one of the twelve is measured now (2026-09-24), so every one is pinned.
EXPECTED_ZIP_BYTES = {
    ("npu", "8gen3"): 285_197_039,
    ("npu", "8elite_galaxy"): 285_116_926,
    ("npu", "8elite5_galaxy"): 285_450_230,
    ("npu", "7gen4"): 285_697_544,
    ("npu", "qcs8550"): 285_198_646,
    ("npu", "8gen1"): 284_581_383,
    ("npu-turbo", "8gen3"): 823_721_812,
    ("npu-turbo", "8elite_galaxy"): 823_685_860,
    ("npu-turbo", "8elite5_galaxy"): 824_020_866,
    ("npu-turbo", "7gen4"): 828_034_458,
    ("npu-turbo", "qcs8550"): 823_697_212,
    ("npu-turbo", "8gen1"): 821_903_663,
}

# ---------------------------------------------------------------------------- the census
# (tier, family) -> (zip bytes, encoder bytes, encoder sha256, decoder bytes, decoder sha256)
#
# The verification table: every measured value must reproduce these literals exactly, and a
# (tier, family) with None here is a pair this script has not yet measured (printed loudly,
# never invented). The 8gen3 rows are the catalog's own four digests -- the self-check.
# NpuFleetCensus.artifacts carries the same digests, row for row; NpuFleetCensusTest pins the two
# tables together.
CENSUS = {
    # Measured 2026-09-24 against manifest v0.63.0 (Last-Modified 23 Sep 2026), every row by
    # the instrument and pasted from its own printed line. THE FINDING: nothing reproduces.
    # v0.63.0 is a QAIRT 2.50 REBUILD, not a re-release: none of the twenty 0.62.2 digests
    # appears below, every ENCODER shrank (small 14.5-22.1%, turbo 11.6-16.8%; 7gen4 the most)
    # while every decoder moved by under 0.06%, and the graph IO census is EQUAL to
    # NpuModelSpec on all twelve packs, 8gen1 included. Same graphs, same shapes, same tensor
    # byte totals; different compiled code inside the context binaries.
    ("npu", "8gen3"): (
        285_197_039,
        113_123_776, "813d0e847bf1ba21b991a421a2f57f56884252d1ca6e780a02a55582c519bac0",
        225_298_736, "bd853be4710bb0aa01dd2a5ce78c03f3e9f722cab47fad3ac24555995f21a929",
    ),
    ("npu", "8elite_galaxy"): (
        285_116_926,
        113_091_008, "e4b24b7b6b5ba333926660f213836fe199160eb0e6d4e9a0d0153c6d0f0c9abf",
        225_151_280, "076cd7b4a5dc0c3b9958dd839d104d02e247e1ae36b7aaa7f53cf1adae891b10",
    ),
    ("npu", "8elite5_galaxy"): (
        285_450_230,
        113_770_944, "8ee815ece1b4a3a72b67c6bb8753efe7076568e364ef2cb4bafd6764f5267757",
        225_290_544, "c117a5cf414986b7bb3b725c676020203430757437dd885b749958be4146fa6c",
    ),
    ("npu", "7gen4"): (
        285_697_544,
        115_028_408, "32e1715cb6abd92d6f3f2a770d56ea246b2ca61b073da964268ed0d4bd5a43d7",
        225_397_032, "4ff5870ef2317d00935eea05ef3b86f0008c816e175ab8bff9dabd034b91efd7",
    ),
    ("npu", "qcs8550"): (
        285_198_646,
        113_127_872, "1ff1c6aa917aa3865ed101635cd1c37222c8d480244c051572e6e07ab7b00bb2",
        225_298_736, "856707dc6e78da42480d45c61432c35fae8c47184873ca40ac45b4bd4b95f7c2",
    ),
    # The sixth family, HTP v69: metadata htp 69, chipset 'qualcomm-snapdragon-8gen1', and
    # the io-census equal to the spec row for BOTH tiers — the gate it had to pass to join.
    ("npu", "8gen1"): (
        284_581_383,
        111_915_456, "fb60f44b26b9fd918fbde33b3c4cee30ca79e06e959e496fb63eda8d805709ec",
        223_562_032, "810557e909a44a1f7ea3889421fbd8c1385a29110f49b962b29b50a48070169f",
    ),
    ("npu-turbo", "8gen3"): (
        823_721_812,
        686_112_520, "c9403eaa9c4b4313419d650e316be7cc1c9020cd8cd716ed909ddb0b61f0886a",
        295_856_032, "a5597486dd53a0847fa042588279d6ab58f736078ea133c513b15e5d8c39d241",
    ),
    ("npu-turbo", "8elite_galaxy"): (
        823_685_860,
        685_997_832, "a72593f052fa9a4fb589bdc3dfe520860ea5684369afde6444198385f0e60ef5",
        295_765_920, "2f9aafff7a15d779aef799fd5a25ac02a17b6455b7a4d293964cbed7e25fe7c8",
    ),
    ("npu-turbo", "8elite5_galaxy"): (
        824_020_866,
        687_283_976, "5f5ff7cf77932ddb56654f2c1083e03cdb88c2b3fa0bd8d0910caceaec775235",
        295_847_840, "b6be63f758903403105efc764be5cf807c36f4d0f81e31b4f3a50c71fc249138",
    ),
    ("npu-turbo", "7gen4"): (
        828_034_458,
        703_946_504, "6489b59b08c9a62c796ecec371def7850e1207b0a54299d81f0344021afba87f",
        295_917_472, "26abee5f364552a0431beabd1f8c2104bfc71a8b7cfcce63189df5efae5d56be",
    ),
    ("npu-turbo", "qcs8550"): (
        823_697_212,
        686_108_424, "16eeb01fcedf147fc55ad2c4cde6b7c7b98c87f1d70ad63d3c11e0245ea567b7",
        295_847_840, "ec6889bdca25b27c1758136c70292cd3274825c6798896507279cd9b37bfbdfe",
    ),
    ("npu-turbo", "8gen1"): (
        821_903_663,
        681_574_152, "2005e39cd6d94c7b66f63832b9d0ba182b0b322876d3f579a826e8edcc74168e",
        294_692_768, "c5bb0775b19afb1f7b115c231aaaf78d003479228b3e905181fe0436c87b5fc2",
    ),
}




def fail(msg: str) -> "SystemExit":
    return SystemExit(f"FATAL: {msg}")


def fetch_manifest(tier: str) -> dict:
    """The pinned HF release manifest, release string asserted before anything else is."""
    import json

    url = MODELS[tier]["manifest_url"]
    with urllib.request.urlopen(url) as resp:
        if resp.status != 200:
            raise fail(f"{tier}: manifest fetch returned HTTP {resp.status} for {url}")
        manifest = json.loads(resp.read().decode("utf-8"))
    version = manifest.get("version")
    if version != RELEASE:
        raise fail(
            f"{tier}: release manifest says version '{version}' but this census was measured "
            f"against '{RELEASE}'. A new vendor release is a RE-MEASUREMENT event, not a "
            f"silent re-resolve -- every digest below describes v{RELEASE}'s bytes."
        )
    return manifest


def resolve_zip_url(tier: str, manifest: dict, family: str) -> str:
    chipset_key, _, _ = FAMILIES[family]
    try:
        assets = manifest["precisions"]["w8a16"]["chipset_assets"]
    except KeyError as e:
        raise fail(f"{tier}: manifest carries no w8a16 chipset_assets ({e})")
    if chipset_key not in assets:
        raise fail(
            f"{tier}/{family}: chipset key '{chipset_key}' is not in the manifest "
            f"(keys: {sorted(assets)}). The vendor dropped or renamed the family's package."
        )
    try:
        return assets[chipset_key]["precompiled_qnn_onnx"]["download_url"]
    except KeyError:
        raise fail(f"{tier}/{family}: no precompiled_qnn_onnx download_url under '{chipset_key}'")


def head_gate(tier: str, family: str, url: str) -> int:
    """HTTP 200 + the pinned Last-Modified day + the exact size where one exists."""
    req = urllib.request.Request(url, method="HEAD")
    with urllib.request.urlopen(req) as resp:
        status = resp.status
        length = int(resp.headers.get("Content-Length", "-1"))
        modified = resp.headers.get("Last-Modified", "")
    if status != 200:
        raise fail(f"{tier}/{family}: HEAD returned HTTP {status} for {url}")
    if LAST_MODIFIED_DAY not in modified:
        raise fail(
            f"{tier}/{family}: Last-Modified is '{modified}', not the pinned "
            f"'{LAST_MODIFIED_DAY}' re-upload event. The bucket rewrote this object; every "
            f"digest in CENSUS describes the {LAST_MODIFIED_DAY} bytes, so STOP and "
            f"re-measure deliberately rather than silently hashing new content."
        )
    expected = EXPECTED_ZIP_BYTES.get((tier, family))
    if expected is not None and length != expected:
        raise fail(
            f"{tier}/{family}: Content-Length {length} != the measured {expected}. "
            f"Same Last-Modified but a different size is a contradiction worth a human look."
        )
    if length <= 0:
        raise fail(f"{tier}/{family}: no usable Content-Length ('{length}')")
    print(f"  head: 200, {length} B, last-modified '{modified}'"
          + ("" if expected is None else " (size asserted)"))
    return length


def ensure_local_zip(tier: str, family: str, url: str, length: int, workspace: str) -> str:
    """The workspace copy at the exact HEAD length -- downloaded only when absent or wrong."""
    path = os.path.join(workspace, url.rsplit("/", 1)[-1])
    if os.path.isfile(path) and os.path.getsize(path) == length:
        print(f"  cached: {path} matches the exact length, download skipped")
        return path
    part = path + ".part"
    print(f"  downloading {length} B -> {path}")
    done = 0
    with urllib.request.urlopen(url) as resp, open(part, "wb") as out:
        while True:
            chunk = resp.read(CHUNK)
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
            if done % (256 * CHUNK) < CHUNK:
                print(f"    ... {done}/{length} B")
    if done != length:
        raise fail(f"{tier}/{family}: downloaded {done} B, HEAD said {length}")
    os.replace(part, path)
    return path


def find_entry(zf: zipfile.ZipFile, bare: str) -> zipfile.ZipInfo:
    """The one entry with this bare name, wherever the vendor nested it. Ambiguity refused."""
    found = None
    for info in zf.infolist():
        if info.is_dir():
            continue
        if info.filename.rsplit("/", 1)[-1] == bare:
            if found is not None:
                raise fail(
                    f"vendor zip carries '{bare}' twice ('{found.filename}' and "
                    f"'{info.filename}') -- not a single-pair release"
                )
            found = info
    if found is None:
        raise fail(f"vendor zip is missing '{bare}' -- wrong archive?")
    return found


def graph_census(graph: dict) -> tuple:
    """(inputs, outputs, input bytes, output bytes) -- shape product times dtype width."""
    def total(tensors: dict) -> int:
        s = 0
        for name, t in tensors.items():
            dtype = t.get("dtype")
            if dtype not in DTYPE_BYTES:
                raise fail(f"tensor '{name}' has unknown dtype '{dtype}' -- widen DTYPE_BYTES "
                           f"only after checking what byte width it really is")
            n = 1
            for dim in t["shape"]:
                n *= dim
            s += n * DTYPE_BYTES[dtype]
        return s
    return (len(graph["inputs"]), len(graph["outputs"]),
            total(graph["inputs"]), total(graph["outputs"]))


def metadata_gate(tier: str, family: str, zf: zipfile.ZipFile) -> None:
    """The 'same model' proof: census HTP + the spec row's IO census, out of the vendor's own
    metadata. A pack that disagrees is not a variant of our model -- it is another model."""
    import json

    chipset_key, htp, _ = FAMILIES[family]
    info = find_entry(zf, VENDOR_METADATA)
    with zf.open(info) as f:
        md = json.load(f)
    attrs = md.get("chipset_attributes", {})
    got_htp = attrs.get("htp_version")
    if got_htp != htp:
        raise fail(
            f"{tier}/{family}: vendor metadata says htp_version {got_htp}, census says {htp}. "
            f"A context binary on the wrong Hexagon fails to deserialise, or worse, does not."
        )
    got_name = attrs.get("name")
    if got_name != chipset_key:
        raise fail(
            f"{tier}/{family}: vendor metadata self-describes as '{got_name}', not the "
            f"'{chipset_key}' package the manifest resolved. Wrong-family content under a "
            f"right-family URL is exactly what this gate exists to catch."
        )
    files = md["model_files"]
    enc = graph_census(files["encoder.onnx"])
    dec = graph_census(files["decoder.onnx"])
    want_enc = MODELS[tier]["encoder_census"]
    want_dec = MODELS[tier]["decoder_census"]
    if enc != want_enc or dec != want_dec:
        raise fail(
            f"{tier}/{family}: IO census mismatch -- encoder {enc} vs spec {want_enc}, "
            f"decoder {dec} vs spec {want_dec}. This package does not carry the SAME model "
            f"as the reference family's, and no digest can make it importable."
        )
    print(f"  metadata: htp={got_htp} chipset='{got_name}' io-census EQUAL to the "
          f"{tier} spec row (encoder {enc}, decoder {dec})")


def extract_and_hash(tier: str, family: str, zf: zipfile.ZipFile, workspace: str) -> dict:
    """Stream both context binaries out, sha256 riding the copy -- never a second read."""
    out_dir = os.path.join(workspace, "extracted", f"{tier}-{family}")
    os.makedirs(out_dir, exist_ok=True)
    measured = {}
    for bare, delivery in (
        (VENDOR_ENCODER, MODELS[tier]["delivery_encoder"]),
        (VENDOR_DECODER, MODELS[tier]["delivery_decoder"]),
    ):
        info = find_entry(zf, bare)
        digest = hashlib.sha256()
        copied = 0
        dest = os.path.join(out_dir, delivery)
        with zf.open(info) as src, open(dest, "wb") as dst:
            while True:
                chunk = src.read(CHUNK)
                if not chunk:
                    break
                digest.update(chunk)
                dst.write(chunk)
                copied += len(chunk)
        if copied != info.file_size:
            raise fail(f"{tier}/{family}: '{info.filename}' produced {copied} B, central "
                       f"directory says {info.file_size}")
        measured[delivery] = (copied, digest.hexdigest())
        print(f"  {delivery}: {copied} B sha256={digest.hexdigest()}")
    return measured


def measure(workspace: str) -> dict:
    os.makedirs(workspace, exist_ok=True)
    unmeasured = []
    paths = {}
    for tier in MODELS:
        manifest = fetch_manifest(tier)
        print(f"manifest ok: {tier} release v{RELEASE}")
        for family in FAMILIES:
            print(f"ROW tier={tier} family={family}")
            url = resolve_zip_url(tier, manifest, family)
            print(f"  url: {url}")
            length = head_gate(tier, family, url)
            path = ensure_local_zip(tier, family, url, length, workspace)
            paths[(tier, family)] = path
            with zipfile.ZipFile(path, "r") as zf:
                bad = zf.testzip()
                if bad is not None:
                    raise fail(f"{tier}/{family}: CRC check failed at entry '{bad}'")
                print("  crc: every entry clean")
                metadata_gate(tier, family, zf)
                measured = extract_and_hash(tier, family, zf, workspace)
            enc_name = MODELS[tier]["delivery_encoder"]
            dec_name = MODELS[tier]["delivery_decoder"]
            row = (length,
                   measured[enc_name][0], measured[enc_name][1],
                   measured[dec_name][0], measured[dec_name][1])
            expected = CENSUS[(tier, family)]
            if expected is None:
                unmeasured.append((tier, family, row))
                print("  census: UNMEASURED -- fill CENSUS from the line below, never invent")
            elif row != expected:
                raise fail(
                    f"{tier}/{family}: measured row {row} != the CENSUS literals {expected}. "
                    f"Either the bucket changed under an unchanged Last-Modified (worth a "
                    f"human look) or the table was edited without a measurement."
                )
            else:
                print("  census: MATCHES the embedded table"
                      + (" (the 8gen3 SELF-CHECK: the catalog's own digests reproduced)"
                         if family == "8gen3" else ""))
            print(f"  CENSUS[(\"{tier}\", \"{family}\")] = ({row[0]:_}, "
                  f"{row[1]:_}, \"{row[2]}\", {row[3]:_}, \"{row[4]}\")")
    if unmeasured:
        print(f"measure: {len(unmeasured)} row(s) not yet in CENSUS -- fill the table from "
              f"the printed rows and RE-RUN so every literal is a reproduced measurement:")
        for tier, family, _ in unmeasured:
            print(f"  ({tier}, {family})")
        raise SystemExit(2)
    # Counted, not spelled: the row and digest totals moved when the fifth family arrived
    # (2026-09-22), and a hardcoded "8 rows / 16 digests" would have gone quietly stale in the
    # one line a reader trusts to tell them the run was complete.
    print(f"measure OK: all {len(CENSUS)} rows reproduce the embedded "
          f"{len(CENSUS) * 2}-digest census exactly")
    return paths


# ---------------------------------------------------------------------------- build (F4)

def repo_root() -> str:
    """This repo's root -- the script lives in tools/, one level down."""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(CHUNK)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def expected_metadata(tier: str, family: str) -> dict:
    """OUR metadata.json for one variant, values FROM the census -- the exact document
    NpuPackMetadata.parse reads strictly (version 1; entries encoder then decoder) and
    crossCheckRefusal answers null for. One builder for the writer AND the verifier, so the
    two cannot disagree about what a variant's metadata says."""
    _, htp, pack_group = FAMILIES[family]
    _, enc_bytes, enc_sha, dec_bytes, dec_sha = CENSUS[(tier, family)]
    return {
        "version": 1,
        "tierId": tier,
        "familyId": family,
        "htpVersion": htp,
        "packGroup": pack_group,
        "entries": [
            {"fileName": MODELS[tier]["delivery_encoder"], "bytes": enc_bytes,
             "sha256": enc_sha},
            {"fileName": MODELS[tier]["delivery_decoder"], "bytes": dec_bytes,
             "sha256": dec_sha},
        ],
    }


def pack_metadata_text(tier: str, family: str) -> str:
    import json

    return json.dumps(expected_metadata(tier, family), indent=2) + "\n"


def verify_variant_dir(tier: str, family: str, out_dir: str) -> "str | None":
    """The importer's own logic re-applied to what LANDED: exactly three files, both bins
    re-read and re-hashed to the census literals, the metadata parsed and compared EQUAL to
    the census document (stricter than the app's parse-then-cross-check -- this script wrote
    the file, so any difference at all is a build fault). None when green, else the first
    problem as one sentence."""
    import json

    _, enc_bytes, enc_sha, dec_bytes, dec_sha = CENSUS[(tier, family)]
    enc_name = MODELS[tier]["delivery_encoder"]
    dec_name = MODELS[tier]["delivery_decoder"]
    if not os.path.isdir(out_dir):
        return f"{out_dir} does not exist"
    names = sorted(os.listdir(out_dir))
    want = sorted([VENDOR_METADATA, enc_name, dec_name])
    if names != want:
        return f"carries {names}; a pack variant is exactly {want}"
    for name, want_bytes, want_sha in ((enc_name, enc_bytes, enc_sha),
                                       (dec_name, dec_bytes, dec_sha)):
        path = os.path.join(out_dir, name)
        got = os.path.getsize(path)
        if got != want_bytes:
            return f"{name} is {got} B, the census says {want_bytes}"
        got_sha = sha256_file(path)
        if got_sha != want_sha:
            return f"{name} sha256 {got_sha} != the census {want_sha}"
    try:
        with open(os.path.join(out_dir, VENDOR_METADATA), "r", encoding="utf-8") as f:
            md = json.load(f)
    except ValueError as bad:
        return f"metadata.json is not valid JSON ({bad})"
    if md != expected_metadata(tier, family):
        return f"metadata.json disagrees with the census: {md}"
    return None


def extract_pair_to(tier: str, family: str, zip_path: str, out_dir: str) -> None:
    """Stream the two context binaries out of the measured vendor zip into out_dir under the
    census's delivery names -- the directory prefix stripped, turbo's entries renamed -- with
    sha256 riding the copy and ASSERTED against the census before the .part is promoted."""
    _, enc_bytes, enc_sha, dec_bytes, dec_sha = CENSUS[(tier, family)]
    os.makedirs(out_dir, exist_ok=True)
    with zipfile.ZipFile(zip_path, "r") as zf:
        for bare, delivery, want_bytes, want_sha in (
            (VENDOR_ENCODER, MODELS[tier]["delivery_encoder"], enc_bytes, enc_sha),
            (VENDOR_DECODER, MODELS[tier]["delivery_decoder"], dec_bytes, dec_sha),
        ):
            info = find_entry(zf, bare)
            digest = hashlib.sha256()
            copied = 0
            dest = os.path.join(out_dir, delivery)
            part = dest + ".part"
            with zf.open(info) as src, open(part, "wb") as dst:
                while True:
                    chunk = src.read(CHUNK)
                    if not chunk:
                        break
                    digest.update(chunk)
                    dst.write(chunk)
                    copied += len(chunk)
            if copied != want_bytes:
                raise fail(f"{tier}/{family}: {delivery} produced {copied} B, the census "
                           f"says {want_bytes}")
            if digest.hexdigest() != want_sha:
                raise fail(f"{tier}/{family}: {delivery} sha256 {digest.hexdigest()} != the "
                           f"census {want_sha}")
            os.replace(part, dest)
            print(f"  {delivery}: {copied} B, census digest reproduced")


def build_packs(workspace: str) -> None:
    """Assemble all eight pack variants into the two module trees. Measure runs FIRST (the F3
    handoff: packs are always built from gate-verified bytes; idempotent and cheap on a warm
    workspace), so every zip this reads has just passed the HEAD, length, CRC and vendor
    metadata gates."""
    paths = measure(workspace)
    root = repo_root()
    built = 0
    current = 0
    for tier in MODELS:
        module = PACK_MODULE_BY_TIER[tier]
        for family in FAMILIES:
            _, _, pack_group = FAMILIES[family]
            out_dir = os.path.join(root, module, "src", "main", "assets",
                                   f"{module}#group_{pack_group}")
            print(f"BUILD tier={tier} family={family} -> "
                  f"{module}/src/main/assets/{module}#group_{pack_group}")
            if verify_variant_dir(tier, family, out_dir) is None:
                print("  already the census (re-hashed from disk), rewrite skipped")
                current += 1
                continue
            if os.path.isdir(out_dir):
                for stale in os.listdir(out_dir):
                    os.remove(os.path.join(out_dir, stale))
            extract_pair_to(tier, family, paths[(tier, family)], out_dir)
            with open(os.path.join(out_dir, VENDOR_METADATA), "w", encoding="utf-8",
                      newline="\n") as f:
                f.write(pack_metadata_text(tier, family))
            problem = verify_variant_dir(tier, family, out_dir)
            if problem is not None:
                raise fail(f"{tier}/{family}: built variant failed its own verification: "
                           f"{problem}")
            print("  verified: three files, census bytes, census digests, metadata equal "
                  "to the census")
            built += 1
    # The empty-default rule, checked at build time too so the fault is caught where it was
    # made rather than at the next bundle's verifyNpuPacks run.
    for module in PACK_MODULE_BY_TIER.values():
        default_dir = os.path.join(root, module, "src", "main", "assets",
                                   f"{module}#group_other")
        extras = [n for n in os.listdir(default_dir) if n != ".gitkeep"]
        if extras:
            raise fail(f"{module}: the DEFAULT variant (assets/{module}#group_other/) must "
                       f"stay EMPTY -- an unmatched device can never be prevented from "
                       f"receiving it -- but it carries {extras}")
    print(f"build OK: {built} variant(s) written+verified, {current} already current; both "
          f"default variants are empty")


# ---------------------------------------------------------------------------- delivery-zip (F4)

def delivery_zip(workspace: str, family: str, tier: str) -> None:
    """The per-family SAF sideload zip -- the fleet's non-Play story, importable through the
    exact same WhisperModelManager.importNpuAssetPair flow as the published 8gen3 zips.

    OUR metadata.json goes FIRST and with its size DECLARED in the local header: the import
    peek triggers on ``entry.size in 0..MAX_BYTES``, and a streamed entry (data descriptor,
    size -1) silently skips the peek -- the wrong-family refusal would then arrive after a GB
    of hashing instead of before it (the F3 review's M2 carry, made a checked property here).
    The two binaries follow under the census's delivery names, each streamed with sha256
    riding the copy; the finished zip is re-opened and held to entry order, no data
    descriptors, declared sizes, census digests and a census-equal metadata document before
    the .part is promoted. Prints the zip's own sha256 for publishing beside the file."""
    import json

    if family not in FAMILIES:
        raise fail(f"unknown family '{family}' (census families: {', '.join(FAMILIES)})")
    if tier not in MODELS:
        raise fail(f"unknown tier '{tier}' (tiers: {', '.join(MODELS)})")
    _, enc_bytes, enc_sha, dec_bytes, dec_sha = CENSUS[(tier, family)]
    enc_name = MODELS[tier]["delivery_encoder"]
    dec_name = MODELS[tier]["delivery_decoder"]
    src_dir = os.path.join(workspace, "extracted", f"{tier}-{family}")
    for name, want_bytes in ((enc_name, enc_bytes), (dec_name, dec_bytes)):
        p = os.path.join(src_dir, name)
        if not os.path.isfile(p) or os.path.getsize(p) != want_bytes:
            raise fail(f"{tier}/{family}: {p} is absent or not the census length -- run "
                       f"'measure' first to populate the workspace")
    out = os.path.join(workspace, f"whisper-{tier}-{family}-delivery.zip")
    part = out + ".part"
    meta_text = pack_metadata_text(tier, family)
    # The fixed date is the vendor bucket's hash-stable re-upload day: zip bytes stay
    # reproducible across runs, so the printed sha256 is a stable identity for the release.
    stamp = (2026, 8, 25, 0, 0, 0)
    with zipfile.ZipFile(part, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        info = zipfile.ZipInfo(VENDOR_METADATA, date_time=stamp)
        info.compress_type = zipfile.ZIP_DEFLATED
        # writestr knows the payload up front, so the local header carries the exact sizes
        # and CRC -- no data descriptor, which is what makes the peek's declared-size gate
        # see this entry at all.
        zf.writestr(info, meta_text)
        for name, want_bytes, want_sha in ((enc_name, enc_bytes, enc_sha),
                                           (dec_name, dec_bytes, dec_sha)):
            src = os.path.join(src_dir, name)
            digest = hashlib.sha256()
            binfo = zipfile.ZipInfo(name, date_time=stamp)
            binfo.compress_type = zipfile.ZIP_DEFLATED
            copied = 0
            # Streaming through open(w) on a SEEKABLE output: CPython writes the local header
            # up front and seeks back at entry close to patch the real sizes and CRC in --
            # declared sizes without buffering a GB, and the reopen below REFUSES the output
            # if that mechanic ever stops holding.
            with open(src, "rb") as fsrc, zf.open(binfo, "w") as dst:
                while True:
                    chunk = fsrc.read(CHUNK)
                    if not chunk:
                        break
                    digest.update(chunk)
                    dst.write(chunk)
                    copied += len(chunk)
            if copied != want_bytes:
                raise fail(f"{tier}/{family}: {name} wrote {copied} B, the census says "
                           f"{want_bytes}")
            if digest.hexdigest() != want_sha:
                raise fail(f"{tier}/{family}: {name} sha256 {digest.hexdigest()} != the "
                           f"census {want_sha}")
    # The self-verification, from the finished zip's own headers and bytes:
    with zipfile.ZipFile(part, "r") as zf:
        infos = zf.infolist()
        if [i.filename for i in infos] != [VENDOR_METADATA, enc_name, dec_name]:
            raise fail(f"delivery zip entry order is {[i.filename for i in infos]} -- "
                       f"metadata.json must be FIRST so the peek refuses a wrong-family zip "
                       f"before a byte of binary inflates")
        for i in infos:
            if i.flag_bits & 0x08:
                raise fail(f"'{i.filename}' was written with a data descriptor -- its local "
                           f"header declares no size, ZipEntry.getSize() answers -1, and the "
                           f"import peek would silently skip it")
        sizes = {i.filename: i.file_size for i in infos}
        if sizes[VENDOR_METADATA] > 65_536:
            raise fail(f"metadata.json is {sizes[VENDOR_METADATA]} B -- past the peek's "
                       f"65536 B buffer bound, so the app would never read it")
        if sizes[enc_name] != enc_bytes or sizes[dec_name] != dec_bytes:
            raise fail(f"declared sizes {sizes} disagree with the census")
        for name, want_sha in ((enc_name, enc_sha), (dec_name, dec_sha)):
            digest = hashlib.sha256()
            with zf.open(name) as f:
                while True:
                    chunk = f.read(CHUNK)
                    if not chunk:
                        break
                    digest.update(chunk)
            if digest.hexdigest() != want_sha:
                raise fail(f"{name} re-inflated to sha256 {digest.hexdigest()} != the census "
                           f"{want_sha}")
        md = json.loads(zf.read(VENDOR_METADATA).decode("utf-8"))
        if md != expected_metadata(tier, family):
            raise fail(f"the zip's metadata.json disagrees with the census: {md}")
    os.replace(part, out)
    print(f"delivery zip OK: {out} ({os.path.getsize(out)} B)")
    print(f"  metadata.json first, declared sizes, no data descriptors, census digests")
    print(f"  sha256 {sha256_file(out)}")


# --------------------------------------------------------------- the preview packs (4.4.0, 4.5.0)
# The streaming previewer's model packs: FOUR raw files per pack at ONE immutable Hugging Face
# commit each, placed into that pack module's single UNTARGETED directory. `preview_en` landed with
# the owner's 2026-09-10 ruling (the amendment pad); the six LANGUAGE packs land with his
# 2026-09-12 one -- "let's set up all 6 languages" -- on identical terms.
#
# Why the table is here as literals, again: this script cannot read the app's classes, so the byte
# counts and digests are restated -- and PreviewPackLayoutTest pins every row equal to
# StreamingPackCatalog, so a re-pin on either side is a red test rather than a silent drift.
# verifyPreviewPack (in app/build.gradle.kts) re-checks the placed sizes of all seven packs before
# every bundle build, and StreamingPackInstall re-hashes on the device before a byte is installed.
# Three gates, one census.
#
# THE TWO SPELLINGS OF A FILE, and why every row carries both. `name` is the flat name the file has
# everywhere the app touches it: the AAB asset entry, the file the installer writes, the name sherpa
# opens, the second column of the `.installed` marker. `path` is where the same bytes live in the
# pinned commit. They coincide for five packs and they do NOT for two -- German's four files are
# under `exp/epoch-30/` and `lang_bpe_500/` with COMMAS in the ONNX filenames, and the bilingual
# zh-en row's are under `exp/32/` and `data/lang_char_bpe/`. So the DOWNLOAD reads `path` and the
# PLACEMENT writes `name`, which is the only arrangement in which such a pack can both download and
# load. (StreamingPackCatalog.PackFile's KDoc owns that argument; this is the half of it a script
# has to act on.)
#
# AND WHY THE DIGEST IS THE AUTHORITY, NOT THE NAME. Two of these repos publish sibling exports
# whose files are nearly indistinguishable by name or size: the Korean repo's chunk-32 and chunk-64
# exports carry decoder and joiner files BYTE-IDENTICAL to the chunk-16 ones we place, and the
# bilingual repo's `exp/64/` and `exp/96/` encoders are ELEVEN and TWELVE bytes larger than
# `exp/32/`'s. A size check alone would accept the wrong export; the sha256 is what refuses it.

# One row per pack. `mirror_dir` is the catalogue's own `dirName`, so a mirror laid out as
# <root>/<dirName>/<flat name> is the same shape the installer writes under filesDir -- one naming
# rule for the cache, the mirror and the device.
PreviewPack = collections.namedtuple("PreviewPack", "module base_url mirror_dir files")

# resolve/<commit sha>/ on every row, the catalog's own rule (WhisperModel.kt:103-108):
# resolve/main is a MUTABLE ref, and a replaced upstream file would rebuild a DIFFERENT pack under
# the same name -- which is not a hypothetical here, it is the 2026-09-08 voice incident one
# directory over.
#
# (name, bytes, sha256, path) -- each tuple on ONE line, deliberately: PreviewPackLayoutTest pins
# them as contiguous text, and a wrapped tuple is a pin that a re-indent can silently retire.
PREVIEW_PACKS = (
    PreviewPack(
        module="preview_en",
        base_url="https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/",
        mirror_dir="en-2023-06-26",
        files=(
            ("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 71_083_163, "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1", "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
            ("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_307_236, "98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02", "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
            ("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 259_335, "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297", "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
            ("tokens.txt", 5_048, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb", "tokens.txt"),
        ),
    ),
    PreviewPack(
        module="preview_fr",
        base_url="https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/resolve/3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73/",
        mirror_dir="fr-2023-04-14",
        files=(
            ("encoder-epoch-29-avg-9-with-averaged-model.int8.onnx", 126_655_903, "47a94a7fdc8dff63d708be4ea0535747640224467f91e238311f1ddbdd09327e", "encoder-epoch-29-avg-9-with-averaged-model.int8.onnx"),
            ("decoder-epoch-29-avg-9-with-averaged-model.int8.onnx", 1_307_157, "e72b2b9ed36355bd0dd43433f7dd258e7226ab54c9ef42b28c73ebb785805623", "decoder-epoch-29-avg-9-with-averaged-model.int8.onnx"),
            ("joiner-epoch-29-avg-9-with-averaged-model.int8.onnx", 259_572, "fc2f3bb851a15a532c6f2422d53eecd1ca949f12b0897e07a852021c30481711", "joiner-epoch-29-avg-9-with-averaged-model.int8.onnx"),
            ("tokens.txt", 4_819, "37fb3f2a7bcb85e5fff3f1f66be04e6fbb05077a22f56d177fe85704e945fb31", "tokens.txt"),
        ),
    ),
    PreviewPack(
        module="preview_de",
        base_url="https://huggingface.co/daniel-dona/icefall-asr-commonvoice-zipformer-streaming-de/resolve/322557b0f88fc5a9823bc71027d4160f0c7612cc/",
        mirror_dir="de-cv17-epoch-30",
        files=(
            ("encoder-epoch-30-avg-5.int8.onnx", 70_133_342, "e0163b48f89a81fafc4eb1804a77cdd33646970160f5d954a82774dc86e93fa5", "exp/epoch-30/encoder-epoch-30-avg-5-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx"),
            ("decoder-epoch-30-avg-5.int8.onnx", 540_689, "8e787f64f765d2d4d1e17315879bc3cc1c2f517532799d78be034a03a9bcacda", "exp/epoch-30/decoder-epoch-30-avg-5-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx"),
            ("joiner-epoch-30-avg-5.int8.onnx", 259_417, "d58379fa169af64034c127558230af63d0c08cda97a4a310b04af4b6ceb68956", "exp/epoch-30/joiner-epoch-30-avg-5-chunk-16,32,64,-1-left-64,128,256,-1.int8.onnx"),
            ("tokens.txt", 5_086, "ad2da0c993128b66cead1d78adddc359bef69c50fca890bb5ca0500c97b6d23d", "lang_bpe_500/tokens.txt"),
        ),
    ),
    PreviewPack(
        module="preview_ru",
        base_url="https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/31fa603e4f31279c6e1f7600fed13dc4312663ab/",
        mirror_dir="ru-vosk-2025-08-16",
        files=(
            ("encoder.int8.onnx", 26_214_060, "e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d", "encoder.int8.onnx"),
            ("decoder.onnx", 2_093_080, "89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0", "decoder.onnx"),
            ("joiner.int8.onnx", 259_417, "b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5", "joiner.int8.onnx"),
            ("tokens.txt", 6_388, "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6", "tokens.txt"),
        ),
    ),
    PreviewPack(
        module="preview_id",
        base_url="https://huggingface.co/spacewave/sherpa-onnx-streaming-zipformer2-id/resolve/4e5a13cbe3e9cd4e3775447d86178ef51759096f/",
        mirror_dir="id-iter-100000",
        files=(
            ("encoder-iter-100000-avg-15-chunk-32-left-256.int8.onnx", 70_103_186, "3a6f85f5d199ad0d495562988af017a75d4ae81b51126db240d959b065d6eaad", "encoder-iter-100000-avg-15-chunk-32-left-256.int8.onnx"),
            ("decoder-iter-100000-avg-15-chunk-32-left-256.int8.onnx", 540_688, "6544848ca80b557ec3c8569169522dc6243b5bc9b296ea73791728510caacb4a", "decoder-iter-100000-avg-15-chunk-32-left-256.int8.onnx"),
            ("joiner-iter-100000-avg-15-chunk-32-left-256.int8.onnx", 259_417, "4b89d96292460a92dedcb39e0b17904f10dbad335a8ab11015e567b4864102e0", "joiner-iter-100000-avg-15-chunk-32-left-256.int8.onnx"),
            ("tokens.txt", 5_403, "f0b6f5bf602d96f60d79bb17192c51eeffb6189250f132b1dfdf72b130d66968", "tokens.txt"),
        ),
    ),
    PreviewPack(
        module="preview_ko",
        base_url="https://huggingface.co/kangkyu/icefall-asr-ko-streaming-zipformer-72m/resolve/db24b58d22736349eaeb34cc181ad0f3debf9903/",
        mirror_dir="ko-72m-chunk-16",
        files=(
            ("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 70_133_869, "5f2b6e5e92834849cfdbda3aaa355e6f39ff993f067794e4ab9d5cb993b15311", "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
            ("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_544_210, "40c3c57ad27b45b59e27bec8bfc02f04d27c060aeb8e964ae2f87bd9f356bf7d", "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
            ("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_270_777, "7d3bd9c1e9cf60efa5d5fed728fbd52d0f08139775f9e9002fd088b4d78f3e73", "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
            ("tokens.txt", 20_844, "435dfb9e0a2b6a79124f1a4d8f0f33a951b25384726e2e0d854f081533e6ec9d", "tokens.txt"),
        ),
    ),
    PreviewPack(
        module="preview_zh",
        base_url="https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t/resolve/e2382758de9a0219b4efe682b95af30b399db3b8/",
        mirror_dir="zh-en-t-chunk-32",
        files=(
            ("encoder-epoch-99-avg-1.int8.onnx", 42_980_793, "db6f51551762e40e549166fe041ea3e45464370b595e9ad23f06478ec3794fbb", "exp/32/encoder-epoch-99-avg-1.int8.onnx"),
            ("decoder-epoch-99-avg-1.int8.onnx", 3_486_740, "4b618d383af304cfae281dbf0a53e8bf442c2f0502256cd5694bd6567ebdd834", "exp/32/decoder-epoch-99-avg-1.int8.onnx"),
            ("joiner-epoch-99-avg-1.int8.onnx", 3_228_485, "bdda356d6f9b8c2d7cee9ee0e26075fa537490f7fd06520be408d287073667b9", "exp/32/joiner-epoch-99-avg-1.int8.onnx"),
            ("tokens.txt", 56_317, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3", "data/lang_char_bpe/tokens.txt"),
        ),
    ),
)

# The mirror ROOT. Each pack's mirror is <root>/<mirror_dir>/, holding the FLAT names -- a directory
# that need not exist: a missing file is fetched from that pack's pinned commit instead.
DEFAULT_PREVIEW_MIRROR_ROOT = r"C:\Users\bastr\.androidbuild\streaming-models"

# The one file in a payload directory that is NOT payload: the tracked anchor that proves the
# directory exists in a clean clone (each module's .gitignore re-includes it by name).
PREVIEW_ANCHOR = ".gitkeep"


def preview_payload_dir(module: str) -> str:
    """A pack module's single untargeted asset directory, named after the PACK (4.2 F8: no two
    modules may ship the same entry path, and Play strips a group suffix on delivery, so the device
    sees assets/<module>/ -- which is what StreamingPackInstall.packSourceDir opens)."""
    return os.path.join(repo_root(), module, "src", "main", "assets", module)


def verify_preview_dir(pack: "PreviewPack", out_dir: str) -> "str | None":
    """What LANDED for ONE pack, re-read from disk: exactly its four files plus the anchor, every
    byte count the catalog's, every digest re-hashed to the catalog's. None when green, else the
    first problem as one sentence. Everything in this directory rides into the AAB and onto every
    device that fetches the pack (it is untargeted), so 'nothing else is in here' is part of the
    verdict."""
    if not os.path.isdir(out_dir):
        return f"{out_dir} does not exist"
    names = sorted(os.listdir(out_dir))
    want = sorted([name for name, _, _, _ in pack.files] + [PREVIEW_ANCHOR])
    if names != want:
        return f"carries {names}; {pack.module} is exactly {want}"
    for name, want_bytes, want_sha, _ in pack.files:
        path = os.path.join(out_dir, name)
        got = os.path.getsize(path)
        if got != want_bytes:
            return f"{name} is {got} B, the catalog says {want_bytes}"
        got_sha = sha256_file(path)
        if got_sha != want_sha:
            return f"{name} sha256 {got_sha} != the catalog {want_sha}"
    return None


def stream_pinned(reader, dest: str, name: str, want_bytes: int, want_sha: str,
                  source: str) -> None:
    """Stream ONE pinned artefact from an open reader into dest with sha256 riding the copy. The
    .part is promoted only after BOTH the length and the digest match the caller's literals, so a
    truncated transfer or a replaced upstream file leaves nothing behind that a later run could
    mistake for the real thing. Shared by the untargeted packs -- the seven preview packs' four
    files each and tts_kokoro's one archive -- so "the bytes are the ones we pinned" is decided in
    ONE place."""
    part = dest + ".part"
    digest = hashlib.sha256()
    copied = 0
    try:
        with open(part, "wb") as dst:
            while True:
                chunk = reader.read(CHUNK)
                if not chunk:
                    break
                digest.update(chunk)
                dst.write(chunk)
                copied += len(chunk)
    finally:
        reader.close()
    if copied != want_bytes:
        os.remove(part)
        raise fail(f"{name} produced {copied} B from the {source}, the catalog says {want_bytes}")
    if digest.hexdigest() != want_sha:
        os.remove(part)
        raise fail(f"{name} sha256 {digest.hexdigest()} from the {source} != the catalog "
                   f"{want_sha}")
    os.replace(part, dest)


def place_preview_file(pack: "PreviewPack", name: str, want_bytes: int, want_sha: str, path: str,
                       out_dir: str, mirror_root: str) -> str:
    """Place ONE pinned file into out_dir under its FLAT name, from this pack's local mirror when it
    already holds those exact bytes and from the pack's commit-pinned URL otherwise. The URL is
    built from `path`, which is not always `name` (German and the bilingual zh-en row keep theirs in
    subdirectories, German's with commas in the filename); the length/digest gate is
    stream_pinned's."""
    local = os.path.join(mirror_root, pack.mirror_dir, name)
    source = "mirror"
    if os.path.isfile(local) and os.path.getsize(local) == want_bytes:
        reader = open(local, "rb")
    else:
        source = "pinned commit"
        reader = urllib.request.urlopen(pack.base_url + path, timeout=120)
    stream_pinned(reader, os.path.join(out_dir, name), name, want_bytes, want_sha, source)
    return source


def place_preview_pack(pack: "PreviewPack", mirror_root: str) -> int:
    """Assemble ONE pack's payload, then re-verify its whole directory from disk. Returns how many
    files this call actually transferred."""
    out_dir = preview_payload_dir(pack.module)
    os.makedirs(out_dir, exist_ok=True)
    anchor = os.path.join(out_dir, PREVIEW_ANCHOR)
    if not os.path.isfile(anchor):
        with open(anchor, "w", encoding="utf-8"):
            pass
    print(f"PREVIEW module={pack.module} -> {pack.module}/src/main/assets/{pack.module}")
    if verify_preview_dir(pack, out_dir) is None:
        print("  already the catalog (re-hashed from disk), rewrite skipped")
        return 0
    # Anything that is neither payload nor the anchor would ride into the AAB: cleared, not kept.
    keep = {name for name, _, _, _ in pack.files} | {PREVIEW_ANCHOR}
    for stale in os.listdir(out_dir):
        if stale not in keep:
            os.remove(os.path.join(out_dir, stale))
    placed = 0
    for name, want_bytes, want_sha, path in pack.files:
        dest = os.path.join(out_dir, name)
        if os.path.isfile(dest) and os.path.getsize(dest) == want_bytes and \
                sha256_file(dest) == want_sha:
            print(f"  {name}: already the catalog, kept")
            continue
        source = place_preview_file(pack, name, want_bytes, want_sha, path, out_dir, mirror_root)
        note = "" if path == name else f" (upstream {path})"
        print(f"  {name}: {want_bytes} B from the {source}{note}, catalog digest reproduced")
        placed += 1
    problem = verify_preview_dir(pack, out_dir)
    if problem is not None:
        raise fail(f"the placed preview pack failed its own verification: {pack.module}: {problem}")
    total = sum(b for _, b, _, _ in pack.files)
    print(f"  {pack.module} OK: {placed} file(s) placed, four files verified from disk, {total} B")
    return placed


def place_preview_packs(mirror_root: str) -> None:
    """ALL SEVEN preview packs, each re-verified from disk before the next is touched. Fails on the
    FIRST pack that cannot be reproduced rather than reporting six successes and a footnote -- a
    bundle is only shippable when every pack in it is the catalog, so a partial run has no
    meaningful success to report. Re-running is cheap: a pack that already verifies is skipped."""
    placed = 0
    total = 0
    for pack in PREVIEW_PACKS:
        placed += place_preview_pack(pack, mirror_root)
        total += sum(b for _, b, _, _ in pack.files)
    files = sum(len(p.files) for p in PREVIEW_PACKS)
    print(f"preview OK: {len(PREVIEW_PACKS)} packs, {placed} file(s) transferred, {files} files "
          f"verified from disk, {total} B total")


# ---------------------------------------------------------------------------- tts_kokoro (4.4.0)
# The read-aloud voice's pack (owner ruling 2026-09-10, the amendment pad, Task 2b): ONE archive
# placed AS-IS into the pack module's single UNTARGETED directory, so
# TtsModelManager.verifyExtractInstall runs unchanged on the pack's copy -- same size gate, same
# known-good digest set, same extract + atomic swap. Both arrival routes hand over the same
# artefact, which is the only way ONE verification can serve both.
#
# THE INCIDENT THIS ROW CLOSES (2026-09-08 to 2026-09-10). The URL below is a ROLLING release tag:
# k2-fsa re-uploaded this archive under it, the size stayed inside the app's +-5 % band, the pinned
# sha256 stopped matching, and every fresh voice install on every build failed for two days,
# production included. So the digest here is not merely "a" digest of the archive: it is the FIRST
# entry of TtsModelManager.KNOWN_GOOD_TAR_SHA256 -- the archive the device's own gate accepts --
# and TtsPackLayoutTest holds the two equal. A pack built from a third upload fails HERE, loudly,
# instead of shipping an AAB whose voice can never install.
TTS_MODULE = "tts_kokoro"

# (name, bytes, sha256) -- the 2026-09-08 archive, verified compatible on 2026-09-10 (53 voice
# slots byte-identical, em_santa appended at id 53). Restated as literals because this script
# cannot read the app's classes; pinned equal to TtsModelManager by TtsPackLayoutTest.
#
# Both literals are spelled on ONE line each, deliberately: TtsPackLayoutTest pins them as
# contiguous text, and a wrapped tuple is a pin that a re-indent can silently retire.
TTS_ARCHIVE = ("kokoro-multi-lang-v1_0.tar.bz2", 349_906_910, "c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298")

TTS_TAR_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"

DEFAULT_TTS_MIRROR = r"C:\Users\bastr\.androidbuild\tts-tar"

# The mirror's spellings, in preference order. `.NEW.tar.bz2` is the 2026-09-08 download archived
# during the incident: it is the verified evidence file, so it is preferred over re-pulling 350 MB
# through a tag that has already moved once. Either way the digest gate below is the authority.
TTS_MIRROR_NAMES = ("kokoro-multi-lang-v1_0.tar.bz2", "kokoro-multi-lang-v1_0.NEW.tar.bz2")

# The one file in the payload directory that is NOT payload: the tracked anchor that proves the
# directory exists in a clean clone (the module's .gitignore re-includes it by name).
TTS_ANCHOR = ".gitkeep"


def tts_payload_dir() -> str:
    """The pack module's single untargeted asset directory, named after the PACK (4.2 F8: no two
    modules may ship the same entry path, and Play strips a group suffix on delivery, so the device
    sees assets/tts_kokoro/ -- which is what TtsModelManager.packTarIn opens)."""
    return os.path.join(repo_root(), TTS_MODULE, "src", "main", "assets", TTS_MODULE)


def verify_tts_dir(out_dir: str) -> "str | None":
    """What LANDED, re-read from disk: exactly the archive plus the anchor, the byte count
    TtsModelManager's and the digest re-hashed to the one the device's gate accepts. None when
    green, else the first problem as one sentence. Everything in this directory rides into the AAB
    and onto every device that fetches the voice, so 'nothing else is in here' is part of the
    verdict."""
    name, want_bytes, want_sha = TTS_ARCHIVE
    if not os.path.isdir(out_dir):
        return f"{out_dir} does not exist"
    names = sorted(os.listdir(out_dir))
    want = sorted([name, TTS_ANCHOR])
    if names != want:
        return f"carries {names}; the voice pack is exactly {want}"
    path = os.path.join(out_dir, name)
    got = os.path.getsize(path)
    if got != want_bytes:
        return f"{name} is {got} B, the catalog says {want_bytes}"
    got_sha = sha256_file(path)
    if got_sha != want_sha:
        return f"{name} sha256 {got_sha} != the catalog {want_sha}"
    return None


def place_tts_pack(mirror: str) -> None:
    """Assemble the tts_kokoro payload, then re-verify the whole directory from disk."""
    name, want_bytes, want_sha = TTS_ARCHIVE
    out_dir = tts_payload_dir()
    os.makedirs(out_dir, exist_ok=True)
    anchor = os.path.join(out_dir, TTS_ANCHOR)
    if not os.path.isfile(anchor):
        with open(anchor, "w", encoding="utf-8"):
            pass
    print(f"TTS module={TTS_MODULE} -> {TTS_MODULE}/src/main/assets/{TTS_MODULE}")
    if verify_tts_dir(out_dir) is None:
        print("  already the catalog (re-hashed from disk), rewrite skipped")
        return
    # Anything that is neither payload nor the anchor would ride into the AAB: cleared, not kept.
    for stale in os.listdir(out_dir):
        if stale not in (name, TTS_ANCHOR):
            os.remove(os.path.join(out_dir, stale))
    dest = os.path.join(out_dir, name)
    if os.path.isfile(dest) and os.path.getsize(dest) == want_bytes and \
            sha256_file(dest) == want_sha:
        print(f"  {name}: already the catalog, kept")
    else:
        source = "mirror"
        reader = None
        for candidate in TTS_MIRROR_NAMES:
            local = os.path.join(mirror, candidate)
            if os.path.isfile(local) and os.path.getsize(local) == want_bytes:
                reader = open(local, "rb")
                source = f"mirror ({candidate})"
                break
        if reader is None:
            # The ROLLING tag, and the reason the digest gate below is not optional.
            source = "upstream release (ROLLING tag)"
            reader = urllib.request.urlopen(TTS_TAR_URL, timeout=600)
        stream_pinned(reader, dest, name, want_bytes, want_sha, source)
        print(f"  {name}: {want_bytes} B from the {source}, catalog digest reproduced")
    problem = verify_tts_dir(out_dir)
    if problem is not None:
        raise fail(f"the placed voice pack failed its own verification: {problem}")
    print(f"tts OK: the archive verified from disk, {want_bytes} B total")


def main(argv: list) -> None:
    usage = (
        f"usage: python {os.path.basename(argv[0])} measure [workspace]\n"
        f"       python {os.path.basename(argv[0])} build [workspace]\n"
        f"       python {os.path.basename(argv[0])} delivery-zip <familyId> <tierId> [workspace]\n"
        f"       python {os.path.basename(argv[0])} preview [mirror-root]\n"
        f"       python {os.path.basename(argv[0])} tts [mirror]"
    )
    if len(argv) < 2 or argv[1] not in ("measure", "build", "delivery-zip", "preview", "tts"):
        raise SystemExit(usage)
    if argv[1] == "preview":
        place_preview_packs(argv[2] if len(argv) > 2 else DEFAULT_PREVIEW_MIRROR_ROOT)
        return
    if argv[1] == "tts":
        place_tts_pack(argv[2] if len(argv) > 2 else DEFAULT_TTS_MIRROR)
        return
    if argv[1] == "measure":
        workspace = argv[2] if len(argv) > 2 else DEFAULT_WORKSPACE
        measure(workspace)
    elif argv[1] == "build":
        workspace = argv[2] if len(argv) > 2 else DEFAULT_WORKSPACE
        build_packs(workspace)
    else:
        if len(argv) < 4:
            raise SystemExit(usage)
        family, tier = argv[2], argv[3]
        workspace = argv[4] if len(argv) > 4 else DEFAULT_WORKSPACE
        delivery_zip(workspace, family, tier)


if __name__ == "__main__":
    main(sys.argv)
