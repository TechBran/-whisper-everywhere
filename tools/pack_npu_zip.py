#!/usr/bin/env python3
"""Repack a Qualcomm AI Hub vendor zip into an npu-class tier's DELIVERY zip (4.1 L8).

Why this script exists — two facts MEASURED for the 4.1 plan and absent from the research:

  1. The vendor zips carry a top-level DIRECTORY prefix
     (e.g. ``whisper_large_v3_turbo_quantized-…/encoder_qairt_context.bin``). The app's
     importer allow-list holds two exact BARE filenames — a prefixed name is structurally
     unrepresentable there, so the vendor zip as downloaded imports NOTHING (every entry is
     Ignored and the import fails with the missing-entries refusal).
  2. BOTH families' entries carry the SAME bare names (``encoder_qairt_context.bin`` /
     ``decoder_qairt_context.bin``). The catalog therefore names turbo's installed files
     ``turbo_*``; a turbo delivery zip that kept the vendor names would import straight over
     the owner's installed 358 MB npu pair — the one hand-provisioned copy on the device.

So for each paired tier this script reads the vendor zip, STRIPS the directory prefix,
RENAMES the two entries to the catalog's own filenames, writes a delivery zip whose only
entries are the two bare names — and then RE-VERIFIES ITS OWN OUTPUT by re-reading it through
the same allow-list-and-digest logic the app's importer applies (exactly two entries, both
allow-listed, each at the catalog's exact byte length, each hashing to the catalog's exact
sha256). It finishes by printing the output zip's own sha256 for the owner to publish beside
the file.

The four lengths and four digests below are the catalog's
(app/src/main/java/com/whispereverywhere/model/WhisperModel.kt) — restated here as literals
ON PURPOSE, the same both-ways-round census the importer's own test applies: the catalog
moving must be a decision somebody made, not a silent follow-on, and
NpuAssetImportTest.thePackScriptCarriesBothTiersFourFilenamesAndAllFourDigests pins this
file against them.

Usage:
    python pack_npu_zip.py <tier> <vendor_zip> <out_zip>

    tier        "npu" or "npu-turbo"
    vendor_zip  the AI Hub archive as downloaded (entries may sit under a directory prefix)
    out_zip     the delivery zip to write (written via a .part sibling, renamed only after
                the self-verification passes — a failed run leaves no plausible-looking zip)

The npu (small) tier's zip is regenerated through this same script too: the 4.0 zip predates
the prefix discovery and was never checked for it (the owner provisioned 4.0 by adb push).
"""

import hashlib
import os
import sys
import zipfile

# Vendor bare names — identical across BOTH families, which is exactly hazard (2) above.
VENDOR_ENCODER = "encoder_qairt_context.bin"
VENDOR_DECODER = "decoder_qairt_context.bin"

# tier id -> vendor bare name -> (delivery filename, exact bytes, sha256 of the file).
# Values are the catalog's; see the module docstring for why they are restated as literals.
TIERS = {
    "npu": {
        VENDOR_ENCODER: (
            "encoder_qairt_context.bin",
            132_927_488,
            "3e92ac26545b6b9d22ecfab594ae57523134006e2722b09fa10e16b193e9e5ec",
        ),
        VENDOR_DECODER: (
            "decoder_qairt_context.bin",
            225_316_864,
            "fda23d731e6b0ab7fb0a50373a49efe2d1792faa5dad456837624d8b8e44b0e4",
        ),
    },
    "npu-turbo": {
        VENDOR_ENCODER: (
            "turbo_encoder_qairt_context.bin",
            775_831_552,
            "f7d11c08a20ea671f59b3ace2f9421da00b06170ac9fe946f29092ee59be6bbe",
        ),
        VENDOR_DECODER: (
            "turbo_decoder_qairt_context.bin",
            295_854_080,
            "c19b067766180843fca6266531605bf037820c5e5ae178bd6dc03785df4c6ae4",
        ),
    },
}

CHUNK = 1 << 20  # 1 MiB


def fail(msg: str) -> "SystemExit":
    return SystemExit(f"FATAL: {msg}")


def find_vendor_entries(zf: zipfile.ZipFile) -> dict:
    """The two model entries, located by BARE name wherever the vendor nested them.

    Refuses ambiguity outright: two entries sharing a model basename means the archive is not
    the single-pair release this script exists to repack, and picking one silently would be a
    coin toss over ~GB of model weights.
    """
    found: dict = {}
    for info in zf.infolist():
        if info.is_dir():
            continue
        base = info.filename.rsplit("/", 1)[-1]
        if base in (VENDOR_ENCODER, VENDOR_DECODER):
            if base in found:
                raise fail(
                    f"vendor zip carries '{base}' twice "
                    f"('{found[base].filename}' and '{info.filename}') — not a single-pair release"
                )
            found[base] = info
    missing = [n for n in (VENDOR_ENCODER, VENDOR_DECODER) if n not in found]
    if missing:
        raise fail(f"vendor zip is missing {', '.join(missing)} — wrong archive?")
    return found


def repack(tier: str, vendor_path: str, out_path: str) -> None:
    required = TIERS[tier]
    part_path = out_path + ".part"

    with zipfile.ZipFile(vendor_path, "r") as vendor:
        entries = find_vendor_entries(vendor)
        with zipfile.ZipFile(part_path, "w", compression=zipfile.ZIP_DEFLATED) as out:
            for vendor_name in (VENDOR_ENCODER, VENDOR_DECODER):
                info = entries[vendor_name]
                delivery_name, want_bytes, want_sha = required[vendor_name]
                digest = hashlib.sha256()
                copied = 0
                # Stream copy: prefix stripped and entry renamed AT THE WRITE — the only
                # step on this route that names a destination, same as the run-book's cp.
                with vendor.open(info, "r") as src, out.open(delivery_name, "w") as dst:
                    while True:
                        chunk = src.read(CHUNK)
                        if not chunk:
                            break
                        digest.update(chunk)
                        dst.write(chunk)
                        copied += len(chunk)
                if copied != want_bytes:
                    raise fail(
                        f"{info.filename}: {copied} bytes extracted, catalog says {want_bytes} — "
                        f"this vendor zip is not the release the catalog pins"
                    )
                if digest.hexdigest() != want_sha:
                    raise fail(
                        f"{info.filename}: sha256 {digest.hexdigest()} != catalog's {want_sha} — "
                        f"this vendor zip is not the release the catalog pins"
                    )
                print(f"packed {info.filename} -> {delivery_name} ({copied} B, sha256 ok)")

    verify_like_the_importer(part_path, {name: (b, s) for (name, b, s) in required.values()})

    # Verified: promote the .part. replace() so a re-run overwrites a previous delivery zip.
    os.replace(part_path, out_path)

    whole = hashlib.sha256()
    with open(out_path, "rb") as f:
        for chunk in iter(lambda: f.read(CHUNK), b""):
            whole.update(chunk)
    print(f"OK {out_path}")
    print(f"zip sha256 (publish this beside the file): {whole.hexdigest()}")


def verify_like_the_importer(zip_path: str, allow: dict) -> None:
    """Re-read the OUTPUT through the importer's own decision sequence.

    Mirrors NpuAssetImport: an allow-list of exact bare names (classifyEntry), a duplicate is
    a repack fault (duplicateEntryRefusal), the declared size must match when stated
    (wrongSizeRefusal), the streamed digest must match exactly (wrongDigestRefusal), and both
    entries must have arrived (missingEntriesRefusal). One extra rule the importer does NOT
    have: an unexpected entry FAILS here — the importer Ignores riders for forward
    compatibility, but this script's own output containing one means the repack is wrong.
    """
    accepted = set()
    with zipfile.ZipFile(zip_path, "r") as zf:
        for info in zf.infolist():
            name = info.filename
            if name not in allow:
                raise fail(f"self-verify: unexpected entry '{name}' in own output")
            if name in accepted:
                raise fail(f"self-verify: duplicate entry '{name}' — a repack fault")
            want_bytes, want_sha = allow[name]
            if info.file_size != want_bytes:
                raise fail(
                    f"self-verify: {name} declares {info.file_size} B, expected {want_bytes}"
                )
            digest = hashlib.sha256()
            got = 0
            with zf.open(info, "r") as src:
                while True:
                    chunk = src.read(CHUNK)
                    if not chunk:
                        break
                    digest.update(chunk)
                    got += len(chunk)
            if got != want_bytes:
                raise fail(f"self-verify: {name} produced {got} B, expected {want_bytes}")
            if digest.hexdigest() != want_sha:
                raise fail(
                    f"self-verify: {name} sha256 {digest.hexdigest()}, expected {want_sha}"
                )
            accepted.add(name)
    missing = set(allow) - accepted
    if missing:
        raise fail(f"self-verify: missing entries {sorted(missing)}")
    print(f"self-verify ok: 2 entries, both allow-listed, exact lengths, exact digests")


def main(argv: list) -> None:
    if len(argv) != 4 or argv[1] not in TIERS:
        tiers = ", ".join(sorted(TIERS))
        raise SystemExit(
            f"usage: python {os.path.basename(argv[0])} <tier> <vendor_zip> <out_zip>\n"
            f"       tier: one of {tiers}"
        )
    tier, vendor_path, out_path = argv[1], argv[2], argv[3]
    if not os.path.isfile(vendor_path):
        raise fail(f"vendor zip not found: {vendor_path}")
    repack(tier, vendor_path, out_path)


if __name__ == "__main__":
    main(sys.argv)
