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

Usage:
    python build_asset_packs.py measure [workspace]

    workspace   defaults to C:\\Users\\bastr\\.androidbuild\\fleet-packs

``build`` and ``delivery-zip`` land in F4.
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

# census family id -> (the vendor manifest's chipset key, HTP version). THE MAPPING LIVES
# HERE, deliberately not in NpuFleetCensus: the app never talks to the vendor bucket, and a
# runtime field nothing at runtime reads would be one more string to keep true (F1 handoff).
FAMILIES = {
    "8gen3": ("qualcomm-snapdragon-8gen3", 75),
    "8elite_galaxy": ("qualcomm-snapdragon-8-elite-for-galaxy", 79),
    "8elite5_galaxy": ("qualcomm-snapdragon-8-elite-gen5-for-galaxy", 81),
    "7gen4": ("qualcomm-snapdragon-7gen4", 73),
}

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
    chipset_key, _ = FAMILIES[family]
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

    chipset_key, htp = FAMILIES[family]
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


def measure(workspace: str) -> None:
    os.makedirs(workspace, exist_ok=True)
    unmeasured = []
    for tier in MODELS:
        manifest = fetch_manifest(tier)
        print(f"manifest ok: {tier} release v{RELEASE}")
        for family in FAMILIES:
            print(f"ROW tier={tier} family={family}")
            url = resolve_zip_url(tier, manifest, family)
            print(f"  url: {url}")
            length = head_gate(tier, family, url)
            path = ensure_local_zip(tier, family, url, length, workspace)
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


def main(argv: list) -> None:
    if len(argv) < 2 or argv[1] not in ("measure", "build", "delivery-zip"):
        raise SystemExit(
            f"usage: python {os.path.basename(argv[0])} measure [workspace]\n"
            f"       (build and delivery-zip land in F4)"
        )
    if argv[1] != "measure":
        raise SystemExit(f"'{argv[1]}' lands in F4; this task ships measure only")
    workspace = argv[2] if len(argv) > 2 else DEFAULT_WORKSPACE
    measure(workspace)


if __name__ == "__main__":
    main(sys.argv)
