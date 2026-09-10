#!/usr/bin/env python3
"""Measure -- and, from F4, build -- the per-SoC NPU asset packs (4.2 F3/F4).

``measure`` is the instrument behind ``NpuFleetCensus.artifacts``: nobody had ever downloaded
the v79/v81/v73 packages, so their digests existed nowhere, and a census row nobody measured
is the one unforgivable output. For each of the two w8a16 models it fetches the pinned Hugging
Face ``release_assets.json`` (release v0.61.0 asserted -- a different release string is a hard
failure naming both), resolves the ``precompiled_qnn_onnx`` zip URL for each census family's
chipset key, and holds every zip to the same gates:

  1. HEAD first: HTTP 200; ``Last-Modified`` on the 2026-08-25 hash-stable re-upload event the
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
its other rows deserve belief.

CENSUS below embeds all sixteen digests as literals. ``NpuFleetCensusTest`` reads this file
and asserts every ``NpuFleetCensus.artifacts`` digest appears here -- the ``pack_npu_zip.py``
pattern, so the committed census and the instrument that fills the packs cannot drift apart.
Nothing binary is ever committed: the workspace lives outside the repo, and the census output
is DATA (digests, sizes, dates).

``build`` (F4) assembles the eight pack variants into the two asset-pack modules'
``src/main/assets/<module>#group_<packGroup>/`` dirs -- RAW bins under the census's delivery
names (turbo's renamed ``turbo_*`` -- all eight vendor zips share the same two bare names, so
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

``preview`` (4.4.0, owner ruling 2026-09-10) places the STREAMING PREVIEWER's pack payload: the
four ``streaming-zipformer-en-2023-06-26`` files into ``preview_en/src/main/assets/preview_en/``,
each streamed with sha256 riding the copy and ASSERTED against ``StreamingPackCatalog.EN``'s own
byte counts and digests (restated here as literals; ``PreviewPackLayoutTest`` pins the two tables
equal), then the whole directory re-verified from disk. Files are taken from a local mirror when
one has them at the right size and hash, and otherwise downloaded from the COMMIT-PINNED Hugging
Face base — ``resolve/<sha>/``, never ``resolve/main`` — so a clean clone reproduces the pack.
Unlike the NPU packs this one is NOT device-targeted: one untargeted directory, every device, no
``#group_`` variants and no empty default to keep empty.

Usage:
    python build_asset_packs.py measure [workspace]
    python build_asset_packs.py build [workspace]
    python build_asset_packs.py delivery-zip <familyId> <tierId> [workspace]
    python build_asset_packs.py preview [mirror]

    workspace   defaults to C:\\Users\\bastr\\.androidbuild\\fleet-packs
    mirror      defaults to C:\\Users\\bastr\\.androidbuild\\streaming-models\\en-2023-06-26
                (a directory that need not exist: missing files are fetched from the pinned
                commit instead)
"""

import hashlib
import os
import sys
import urllib.request
import zipfile

RELEASE = "0.61.0"

# The hash-stable re-upload event (research doc 2026-08-29-pad-soc-delivery.md section 7).
# Substring-matched against the RFC 1123 Last-Modified header, so a bucket rewrite on any
# later date fails the HEAD gate by name.
LAST_MODIFIED_DAY = "25 Aug 2026"

DEFAULT_WORKSPACE = r"C:\Users\bastr\.androidbuild\fleet-packs"

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
}

# tier id -> the asset-pack MODULE that ships it. The delivery names above are per-TIER; the
# module split is what lets Play deliver small without turbo (and price the fetch decision per
# tier in the app's UI).
PACK_MODULE_BY_TIER = {"npu": "npu_small", "npu-turbo": "npu_turbo"}

# Vendor zip Content-Length, asserted at HEAD where a measurement already existed BEFORE this
# script first ran: the four turbo zips (research section 7) and the 8gen3 small zip. The
# other three small zips were RECORDED by the first measure run and then promoted into CENSUS.
EXPECTED_ZIP_BYTES = {
    ("npu", "8gen3"): 293_598_974,
    ("npu-turbo", "8gen3"): 859_786_903,
    ("npu-turbo", "8elite_galaxy"): 859_689_781,
    ("npu-turbo", "8elite5_galaxy"): 860_709_426,
    ("npu-turbo", "7gen4"): 871_118_306,
}

# ---------------------------------------------------------------------------- the census
# (tier, family) -> (zip bytes, encoder bytes, encoder sha256, decoder bytes, decoder sha256)
#
# The verification table: every measured value must reproduce these literals exactly, and a
# (tier, family) with None here is a pair this script has not yet measured (printed loudly,
# never invented). The 8gen3 rows are the catalog's own four digests -- the self-check.
# NpuFleetCensus.artifacts carries the same sixteen digests; NpuFleetCensusTest pins the two
# tables together.
CENSUS = {
    ("npu", "8gen3"): (
        293_598_974,
        132_927_488, "3e92ac26545b6b9d22ecfab594ae57523134006e2722b09fa10e16b193e9e5ec",
        225_316_864, "fda23d731e6b0ab7fb0a50373a49efe2d1792faa5dad456837624d8b8e44b0e4",
    ),
    ("npu", "8elite_galaxy"): (
        293_117_989,
        132_333_568, "3001e590274f3377af7f18d33b3f41ab1d573f3e447045bb7a10b516755b9f99",
        225_234_944, "57aff15b592f1afc2d29d16fb78e6c7b3e80a861a0ecee3838a00884ef040d43",
    ),
    ("npu", "8elite5_galaxy"): (
        293_798_379,
        133_554_176, "3c63c40b09374773903855f587bc0530f199a3aa74136fdd4e395c94d258eda5",
        225_411_072, "a5f6c090a4df6f987e3b47dce04d999fc941f7ef87c5960db8fdf447edc82ab8",
    ),
    ("npu", "7gen4"): (
        295_361_549,
        147_595_264, "83a678810bad8b06f3dfab369c2bb87a4ae8aef14cb1886ba3b7a58f7acf2c13",
        225_382_400, "81c0d683753cd13d98a3a744377e60d180e832f0fd128fe1ecaa8c94890e8069",
    ),
    ("npu-turbo", "8gen3"): (
        859_786_903,
        775_831_552, "f7d11c08a20ea671f59b3ace2f9421da00b06170ac9fe946f29092ee59be6bbe",
        295_854_080, "c19b067766180843fca6266531605bf037820c5e5ae178bd6dc03785df4c6ae4",
    ),
    ("npu-turbo", "8elite_galaxy"): (
        859_689_781,
        775_544_832, "4776799f89514e2e96bd2ccb9a2fb9bdca246bdbeba8c7df84d671e2a6ca024c",
        295_821_312, "04f5fe2b77b3bc12f20944401106ba4f878b5275113cba5fbea3ec60d481efaa",
    ),
    ("npu-turbo", "8elite5_galaxy"): (
        860_709_426,
        777_441_280, "841cecfeade064bed27956401c298a2df86eeaac5c33270a284c34d11619c7a2",
        295_911_424, "ceca18cf506f14d8eaf141c69cf7674aca210b825316f0f4c481289cca457430",
    ),
    ("npu-turbo", "7gen4"): (
        871_118_306,
        846_360_576, "c482288d5899590a87cfea3faea3e39df30242095b8c93e0e02e7d1f1c79a813",
        295_895_040, "ce8ad981b89999f4eb9dace8dfb9b64129322e976ac89188a719e59842baacc5",
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
    print("measure OK: all 8 rows reproduce the embedded 16-digest census exactly")
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


# ---------------------------------------------------------------------------- preview_en (4.4.0)
# The streaming previewer's pack (owner ruling 2026-09-10, the amendment pad): four raw files at
# ONE immutable Hugging Face commit, placed into the pack module's single UNTARGETED directory.
#
# Why the table is here as literals, again: this script cannot read the app's classes, so the
# byte counts and digests are restated -- and PreviewPackLayoutTest pins each row equal to
# StreamingPackCatalog.EN, so a re-pin on either side is a red test rather than a silent drift.
# The names are the upstream file names at the commit; verifyPreviewPack (in app/build.gradle.kts)
# re-checks the placed sizes before every bundle build, and StreamingPackInstall re-hashes on the
# device before a byte is installed. Three gates, one census.
PREVIEW_MODULE = "preview_en"

# resolve/<commit sha>/, the catalog's own rule (WhisperModel.kt:103-108): resolve/main is a
# MUTABLE ref, and a replaced upstream file would rebuild a DIFFERENT pack under the same name.
PREVIEW_BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24/"

# (name, bytes, sha256) -- re-hashed on the PC (rung 1 section 1.2) and on the Tab (rung 3).
PREVIEW_FILES = (
    ("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 71_083_163, "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1"),
    ("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_307_236, "98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02"),
    ("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 259_335, "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297"),
    ("tokens.txt", 5_048, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"),
)

DEFAULT_PREVIEW_MIRROR = r"C:\Users\bastr\.androidbuild\streaming-models\en-2023-06-26"

# The one file in the payload directory that is NOT payload: the tracked anchor that proves the
# directory exists in a clean clone (the module's .gitignore re-includes it by name).
PREVIEW_ANCHOR = ".gitkeep"


def preview_payload_dir() -> str:
    """The pack module's single untargeted asset directory, named after the PACK (4.2 F8: no two
    modules may ship the same entry path, and Play strips a group suffix on delivery, so the
    device sees assets/preview_en/ -- which is what StreamingPackInstall.packSourceDir opens)."""
    return os.path.join(repo_root(), PREVIEW_MODULE, "src", "main", "assets", PREVIEW_MODULE)


def verify_preview_dir(out_dir: str) -> "str | None":
    """What LANDED, re-read from disk: exactly the four files plus the anchor, every byte count
    the catalog's, every digest re-hashed to the catalog's. None when green, else the first
    problem as one sentence. Everything in this directory rides into the AAB and onto every
    device (the pack is untargeted), so 'nothing else is in here' is part of the verdict."""
    if not os.path.isdir(out_dir):
        return f"{out_dir} does not exist"
    names = sorted(os.listdir(out_dir))
    want = sorted([name for name, _, _ in PREVIEW_FILES] + [PREVIEW_ANCHOR])
    if names != want:
        return f"carries {names}; the preview pack is exactly {want}"
    for name, want_bytes, want_sha in PREVIEW_FILES:
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
    mistake for the real thing. Shared by the two untargeted packs -- preview_en's four files and
    tts_kokoro's one archive -- so "the bytes are the ones we pinned" is decided in ONE place."""
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


def place_preview_file(name: str, want_bytes: int, want_sha: str, out_dir: str,
                       mirror: str) -> str:
    """Place ONE pinned file into out_dir, from the local mirror when it already holds those exact
    bytes and from the commit-pinned URL otherwise. The length/digest gate is stream_pinned's."""
    local = os.path.join(mirror, name)
    source = "mirror"
    if os.path.isfile(local) and os.path.getsize(local) == want_bytes:
        reader = open(local, "rb")
    else:
        source = "pinned commit"
        reader = urllib.request.urlopen(PREVIEW_BASE_URL + name, timeout=120)
    stream_pinned(reader, os.path.join(out_dir, name), name, want_bytes, want_sha, source)
    return source


def place_preview_pack(mirror: str) -> None:
    """Assemble the preview_en payload, then re-verify the whole directory from disk."""
    out_dir = preview_payload_dir()
    os.makedirs(out_dir, exist_ok=True)
    anchor = os.path.join(out_dir, PREVIEW_ANCHOR)
    if not os.path.isfile(anchor):
        with open(anchor, "w", encoding="utf-8"):
            pass
    print(f"PREVIEW module={PREVIEW_MODULE} -> "
          f"{PREVIEW_MODULE}/src/main/assets/{PREVIEW_MODULE}")
    if verify_preview_dir(out_dir) is None:
        print("  already the catalog (re-hashed from disk), rewrite skipped")
        return
    # Anything that is neither payload nor the anchor would ride into the AAB: cleared, not kept.
    keep = {name for name, _, _ in PREVIEW_FILES} | {PREVIEW_ANCHOR}
    for stale in os.listdir(out_dir):
        if stale not in keep:
            os.remove(os.path.join(out_dir, stale))
    placed = 0
    for name, want_bytes, want_sha in PREVIEW_FILES:
        path = os.path.join(out_dir, name)
        if os.path.isfile(path) and os.path.getsize(path) == want_bytes and \
                sha256_file(path) == want_sha:
            print(f"  {name}: already the catalog, kept")
            continue
        source = place_preview_file(name, want_bytes, want_sha, out_dir, mirror)
        print(f"  {name}: {want_bytes} B from the {source}, catalog digest reproduced")
        placed += 1
    problem = verify_preview_dir(out_dir)
    if problem is not None:
        raise fail(f"the placed preview pack failed its own verification: {problem}")
    total = sum(b for _, b, _ in PREVIEW_FILES)
    print(f"preview OK: {placed} file(s) placed, four files verified from disk, {total} B total")


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
        f"       python {os.path.basename(argv[0])} preview [mirror]\n"
        f"       python {os.path.basename(argv[0])} tts [mirror]"
    )
    if len(argv) < 2 or argv[1] not in ("measure", "build", "delivery-zip", "preview", "tts"):
        raise SystemExit(usage)
    if argv[1] == "preview":
        place_preview_pack(argv[2] if len(argv) > 2 else DEFAULT_PREVIEW_MIRROR)
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
