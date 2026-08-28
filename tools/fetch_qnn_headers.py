#!/usr/bin/env python3
"""Populate app/src/main/cpp/include/QNN with the QAIRT (QNN) C API headers.

Pulls ONLY the ~580 KB of `include/QNN/**` out of Qualcomm's ~2.25 GB public SDK archive using
HTTP range requests, so no login and no full download are needed.

PROPRIETARY — READ BEFORE TOUCHING: every header fetched here carries
"Confidential and Proprietary - Qualcomm Technologies, Inc." Compiling against them is what they
are shipped for; REDISTRIBUTING them is not. This repo has a public remote, so the whole tree is
gitignored (`app/src/main/cpp/include/QNN/`) and re-fetched on demand instead of vendored. Never
commit the output of this script.

THREE THINGS THIS SCRIPT DOES THAT THE SPIKE'S VERSION DID NOT, each because the failure it
prevents is silent:

1. THE VERSION IS A PINNED LITERAL, never argv. The spike took the version from `sys.argv[1]` and
   defaulted to 2.45. A forgotten argument therefore fetched 2.45 headers to compile against the
   2.49 runtime the APK bundles (`com.qualcomm.qti:qnn-runtime:2.49.0`) — a struct-layout mismatch
   that COMPILES CLEAN and only shows up as garbage tensor metadata on device. Nothing about this
   build should be able to depend on how someone typed a command.
2. THE FETCHED BUILD ID IS ASSERTED. After extraction, QnnSdkBuildId.h must contain the pinned
   version or the script fails loudly and leaves nothing half-written. Same discipline as
   `fetchSherpaAar` in app/build.gradle.kts, which verifies a hardcoded sha256 for the same reason.
3. THERE IS AN OFFLINE FALLBACK. Every build of every tier would otherwise depend on a vendor
   download portal staying up and keeping its URL scheme. If the fetch fails and the proven spike
   copy exists locally, it is copied with a warning; if it does not, the error names that path as
   the manual source rather than leaving the reader to guess.

Usage:  python fetch_qnn_headers.py [OUT_DIR]
        OUT_DIR defaults to <repo>/app/src/main/cpp/include; headers land in OUT_DIR/QNN/**.
"""
import os
import re
import shutil
import struct
import sys
import urllib.request
import zlib

# ---------------------------------------------------------------------------- the pin

# PINNED LITERALS. Do NOT make either configurable, and do NOT take them from argv (point 1 above).
# They match the qnn-runtime AAR coordinate in app/build.gradle.kts EXACTLY; all of it moves
# together or not at all. The blobs the app deserialises were produced by QAIRT 2.45 and are read
# by this 2.49 runtime — that pairing is the known risk R7, verified on device at Q10a, and it is
# only arguable at all because the HEADERS and the RUNTIME match each other.
#
# TWO STRINGS, NOT ONE, and confusing them is a 404 rather than anything subtle:
#   VER_URL  — the portal's path segment. SHORT form: version + build DATE only.
#   BUILD_ID — what QnnSdkBuildId.h actually contains. LONG form: date + TIME.
# Verified against the live portal on 2026-08-28: `.../All/2.49.0.260730/v2.49.0.260730.zip`
# serves a 2,414,977,444-byte archive, while the long form 404s. The spike's script had only the
# short form (its 2.45 default) and never needed the distinction, because it never asserted the
# build id it fetched — which is precisely the check being added here.
VER_URL = "2.49.0.260730"
BUILD_ID_NEEDLE = "v2.49.0.260730134355"

URL = ("https://softwarecenter.qualcomm.com/api/download/software/sdks/"
       f"Qualcomm_AI_Runtime_Community/All/{VER_URL}/v{VER_URL}.zip")

# The proven spike tree — the port source for this whole task, and the offline fallback here.
SPIKE_HEADERS = r"C:\Users\bastr\.androidbuild\npu-spike\app\src\main\cpp\include\QNN"

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_OUT = os.path.join(REPO_ROOT, "app", "src", "main", "cpp", "include")

WANT = re.compile(rb"/include/QNN/.*\.(h|hpp)$")


def die(msg):
    print("FATAL: " + msg, file=sys.stderr)
    sys.exit(2)


def build_id_of(qnn_dir):
    """The QNN_SDK_BUILD_ID string in an extracted tree, or None if it is not readable."""
    path = os.path.join(qnn_dir, "QnnSdkBuildId.h")
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        text = f.read()
    m = re.search(r'#define\s+QNN_SDK_BUILD_ID\s+"([^"]+)"', text)
    return m.group(1) if m else None


def assert_pinned(qnn_dir, provenance):
    """Fail loudly unless the extracted headers really are the pinned version."""
    got = build_id_of(qnn_dir)
    if got is None:
        die("no readable QnnSdkBuildId.h under %s after fetching from %s. The extraction did not "
            "produce a usable header tree; delete the directory and re-run." % (qnn_dir, provenance))
    if got != BUILD_ID_NEEDLE:
        die("QNN header version mismatch. Expected %s (the pinned literal, matching "
            "com.qualcomm.qti:qnn-runtime:2.49.0) but %s supplied %s.\n"
            "        This is exactly the failure the pin exists to catch: a header/runtime skew "
            "COMPILES CLEAN and then misreads every versioned struct on device.\n"
            "        Delete %s and re-run; do not 'fix' this by relaxing the check."
            % (BUILD_ID_NEEDLE, provenance, got, qnn_dir))
    print("build id verified: %s (from %s)" % (got, provenance))


# ---------------------------------------------------------------------------- portal fetch

def _rng(a, b):
    req = urllib.request.Request(URL, headers={"Range": "bytes=%d-%d" % (a, b),
                                               "User-Agent": "curl/8"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def _total():
    req = urllib.request.Request(URL, headers={"Range": "bytes=0-0", "User-Agent": "curl/8"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return int(r.headers["Content-Range"].split("/")[1])


def fetch_from_portal(out):
    """Range-fetch include/QNN/** out of the SDK zip. Raises on any failure."""
    size = _total()
    print("archive: {:,} bytes".format(size))

    tail = _rng(max(0, size - 65600), size - 1)
    i = tail.rfind(b"PK\x05\x06")
    if i < 0:
        raise RuntimeError("no EOCD in the archive tail")
    cd_size, cd_off = struct.unpack("<II", tail[i + 12:i + 20])
    n_ent = struct.unpack("<H", tail[i + 10:i + 12])[0]

    # ZIP64 if any field is saturated.
    j = tail.rfind(b"PK\x06\x07")
    if j >= 0 and (cd_off == 0xFFFFFFFF or cd_size == 0xFFFFFFFF or n_ent == 0xFFFF):
        z64_off = struct.unpack("<Q", tail[j + 8:j + 16])[0]
        z64 = _rng(z64_off, z64_off + 55)
        if z64[:4] == b"PK\x06\x06":
            n_ent = struct.unpack("<Q", z64[32:40])[0]
            cd_size, cd_off = struct.unpack("<QQ", z64[40:56])
    print("central dir: offset={:,} size={:,} entries={:,}".format(cd_off, cd_size, n_ent))

    cd = _rng(cd_off, cd_off + cd_size - 1)

    hits, p = [], 0
    while p < len(cd) - 4 and cd[p:p + 4] == b"PK\x01\x02":
        method, = struct.unpack("<H", cd[p + 10:p + 12])
        csize, usize = struct.unpack("<II", cd[p + 20:p + 28])
        nlen, elen, clen = struct.unpack("<HHH", cd[p + 28:p + 34])
        lho, = struct.unpack("<I", cd[p + 42:p + 46])
        name = cd[p + 46:p + 46 + nlen]
        extra = cd[p + 46 + nlen:p + 46 + nlen + elen]
        if 0xFFFFFFFF in (csize, usize, lho):          # ZIP64 extra field
            q = 0
            while q < len(extra) - 4:
                tag, ln = struct.unpack("<HH", extra[q:q + 4])
                if tag == 0x0001:
                    v, off = extra[q + 4:q + 4 + ln], 0
                    if usize == 0xFFFFFFFF:
                        usize = struct.unpack("<Q", v[off:off + 8])[0]
                        off += 8
                    if csize == 0xFFFFFFFF:
                        csize = struct.unpack("<Q", v[off:off + 8])[0]
                        off += 8
                    if lho == 0xFFFFFFFF:
                        lho = struct.unpack("<Q", v[off:off + 8])[0]
                    break
                q += 4 + ln
        if WANT.search(name):
            hits.append((name.decode(), method, csize, usize, lho))
        p += 46 + nlen + elen + clen

    print("matched %d header files" % len(hits))
    if not hits:
        raise RuntimeError("no include/QNN headers matched -- the archive layout changed")

    # One contiguous fetch over the header region: they are adjacent in the archive.
    lo = min(h[4] for h in hits)
    hi = max(h[4] + 46 + 4096 + h[2] for h in hits)
    print("fetching header region: {:,} bytes ({:.4f}% of archive)".format(
        hi - lo, (hi - lo) / size * 100))
    blob = _rng(lo, min(hi, size - 1))

    written = 0
    for name, method, csize, usize, lho in hits:
        b = lho - lo
        if blob[b:b + 4] != b"PK\x03\x04":
            print("  !! bad local header for %s" % name)
            continue
        nlen, elen = struct.unpack("<HH", blob[b + 26:b + 30])
        ds = b + 30 + nlen + elen
        raw = blob[ds:ds + csize]
        data = zlib.decompress(raw, -15) if method == 8 else raw
        if len(data) != usize:
            print("  !! size mismatch %s" % name)
            continue
        rel = name.split("/include/", 1)[1]
        dst = os.path.join(out, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "wb") as f:
            f.write(data)
        written += 1
    print("wrote %d headers to %s" % (written, out))
    if written == 0:
        raise RuntimeError("every matched header failed to extract")


def copy_from_spike(qnn_dir):
    """Offline fallback: the proven spike tree, which is the pinned version by construction."""
    shutil.copytree(SPIKE_HEADERS, qnn_dir, dirs_exist_ok=True)
    n = sum(len(files) for _, _, files in os.walk(qnn_dir))
    print("copied %d files from the local spike tree" % n)


# ---------------------------------------------------------------------------- main

def main():
    out = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUT
    qnn_dir = os.path.join(out, "QNN")

    existing = build_id_of(qnn_dir)
    if existing == BUILD_ID_NEEDLE:
        print("headers already present and pinned: %s (%s)" % (existing, qnn_dir))
        return
    if existing is not None:
        print("WARNING: %s holds %s, wanted %s -- re-fetching" % (qnn_dir, existing,
                                                                 BUILD_ID_NEEDLE))

    os.makedirs(out, exist_ok=True)
    provenance = "the Qualcomm SDK portal"
    try:
        print("fetching QAIRT %s headers from %s" % (BUILD_ID_NEEDLE, URL))
        fetch_from_portal(out)
    except Exception as exc:                                    # noqa: BLE001 - any failure falls back
        print("WARNING: portal fetch failed (%s: %s)" % (type(exc).__name__, exc), file=sys.stderr)
        if not os.path.isdir(SPIKE_HEADERS):
            die("could not fetch the QNN headers and no local copy exists.\n"
                "        Manual source: %s\n"
                "        Copy that directory to %s (it is QAIRT %s), or restore network access "
                "to %s." % (SPIKE_HEADERS, qnn_dir, BUILD_ID_NEEDLE, URL))
        print("WARNING: falling back to the local spike copy at %s" % SPIKE_HEADERS,
              file=sys.stderr)
        copy_from_spike(qnn_dir)
        provenance = "the offline fallback " + SPIKE_HEADERS

    assert_pinned(qnn_dir, provenance)


if __name__ == "__main__":
    main()
