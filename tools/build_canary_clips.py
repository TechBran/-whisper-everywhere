"""Rebuild (or just re-verify) the per-language CANARY CLIPS in app/src/main/assets.

A canary clip is a VERDICT INPUT: `PreviewCanary` decodes it on every warm and a Fail switches
that language's live words off for the process. The clips are therefore committed binaries -- but
"committed" must not mean "unreproducible", which is why this file exists next to
build_asset_packs.py and follows the same discipline: one pinned table, a download from an
immutable revision, a digest gate, and a re-verify from disk afterwards.

    python tools/build_canary_clips.py verify          # re-hash what is committed (no network)
    python tools/build_canary_clips.py build [lang..]  # regenerate from the pinned sources

`verify` is the mode that matters day to day and needs nothing installed. `build` needs:

  * ffmpeg on PATH -- and it is NOT optional. A hand-rolled linear 24 kHz -> 16 kHz resample
    aliases everything above 8 kHz into the speech band, and the French pack then answered `""`
    for perfectly good synthesis: the exact FEAT_SME signature the canary exists to catch.
  * `pip install sherpa-onnx==1.13.7` (the version the shipped AAR reports) for the French
    synthesis and for the token-boundary cut, which reads real decoder timestamps.
  * for `fr`: the kokoro-multi-lang-v1_0 directory (the archive tts_kokoro delivers), extracted.
  * for de/ru/id/ko: network, to fetch one utterance each from the pinned FLEURS revision.

Every clip's digest is pinned HERE and in PreviewCanaryClipsTest, and that test holds the two
tables equal -- so a clip regenerated differently is a red suite rather than a new verdict.
"""
import hashlib
import json
import os
import subprocess
import sys
import urllib.request
import wave

# ---------------------------------------------------------------------------- the pinned table

# The FLEURS revision the audio is served from. Pinned for the reason a model commit is pinned:
# `main` is a MUTABLE ref, and a replaced upstream item would silently change a verdict input.
FLEURS_DATASET = "google/fleurs"
FLEURS_REVISION = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
FLEURS_LICENCE = "cc-by-4.0"   # cardData.license AND the platform tag; README.md:112-113

# The kokoro voice the French clip is synthesized with: speaker id 30, `ff_siwis`
# (TtsVoices.kt:47). The model is the tts_kokoro archive's own, Apache-2.0.
KOKORO_SID = 30
KOKORO_TEXT = "un deux trois quatre cinq"
KOKORO_LANG = "fr"             # the espeak-ng voice name inside the archive's espeak-ng-data
DEFAULT_KOKORO_DIR = r"C:\Users\bastr\.androidbuild\kokoro\kokoro-multi-lang-v1_0"

# language -> what it takes to rebuild the clip, and what must come out.
#
# `cut_s` is where the truncation AIMS: the real cut lands after the last decoder token whose
# timestamp is <= cut_s, plus TAIL_MS, so it never falls inside a word. `bytes`/`sha256` are what
# the committed asset is, re-hashed by `verify`.
TAIL_MS = 250
CLIPS = {
    # fr is the one clip whose BYTES are not reproducible, and that was MEASURED rather than
    # assumed: two consecutive syntheses of the same text with the same voice, speed and thread
    # count came out the same LENGTH with different digests, and a third came out 8 bytes
    # longer. Kokoro through onnxruntime is not bit-deterministic here. So `build fr` is checked
    # on its DECODE, which IS stable -- a fresh synthesis decodes `UN DEUX TROIS QUATRE CINQ`
    # exactly, like the committed one. The committed clip is the artefact and its digest is the
    # pin; regenerating it means re-measuring the rule, not swapping the file.
    "fr": dict(
        asset="canary_fr_digits.wav", bytes=47_498,
        sha256="4fcf2d1d3553f631840cd6883121d8cd245accbf66a8f5944b692aa6ff312443",
        source="kokoro", speed=1.0, peak=0.80, reproducible_bytes=False,
        pack="preview_fr", encoder_t=39,
        text="UN DEUX TROIS QUATRE CINQ",
    ),
    "de": dict(
        asset="canary_de_fleurs.wav", bytes=107_882,
        sha256="e47f17126979f97e2e34ff88e57087ac3b98b63e5d85070005f24bc6524e3c3c",
        source="fleurs", config="de_de", split="validation", row=1528, cut_s=3.2,
        pack="preview_de", encoder_t=45,
        text="MANCHE FESTIVALS HABEN SPEZIELLE CAMPINGBEREICHE",
    ),
    "ru": dict(
        asset="canary_ru_fleurs.wav", bytes=107_882,
        sha256="03f6640621ecf97f3d510720012fb96538d37894b6da3dc3ac40033890e4f2ac",
        source="fleurs", config="ru_ru", split="validation", row=1587, cut_s=3.2,
        pack="preview_ru", encoder_t=77,
        text="о первых случаях заболевания в этом сезоне было",
    ),
    "id": dict(
        asset="canary_id_fleurs.wav", bytes=114_282,
        sha256="bfb7c32e6393eaabc2c9dbf892ad4273cbd1850a15cd7820693cf8e4c9cb3673",
        source="fleurs", config="id_id", split="validation", row=1636, cut_s=3.4,
        pack="preview_id", encoder_t=77,
        text="BANGSA SPANYOL MEMULAI PERIODE KOLONIALISASI",
    ),
    "ko": dict(
        asset="canary_ko_fleurs.wav", bytes=159_082,
        sha256="5c362a1cafb426c708f688c0cf29c02bd18f8c48bb79a02154b6196bd7b500ab",
        source="fleurs", config="ko_kr", split="validation", row=1636, cut_s=4.8,
        pack="preview_ko", encoder_t=45,
        text="스페인사람들이삼세기동안지속된시민제시대를시작했다",
    ),
}

# The English clip is NOT in the table above: it is the owner's own recording (3.6.0 Workstream C,
# commit 5484e3d), there is nothing to regenerate it from, and the bilingual zh-en row shares it.
# `verify` still re-hashes it, because it is a verdict input like the rest.
OWNER_CLIP = ("canary_digits.wav", 81_998,
              "a3079109f735d4acea2756ce5398c67119ab36fa832571f5a5b45b616a7a5cd4")

SAMPLE_RATE = 16_000
CHUNK = 512                    # StreamingPreviewTuning.CHUNK_SAMPLES
FRAME_SHIFT_MS = 10            # StreamingPreviewTuning.FRAME_SHIFT_MS
MEASURED_PAD_MS = 500          # StreamingPreviewTuning.MEASURED_PAD_MS
PAD_MARGIN_MS = 50             # StreamingPreviewTuning.PAD_MARGIN_MS

# PCM16 full scale. `read_wav` divides by 32768, so this is what makes the round trip exact;
# see [write_wav] for the two digests that measured it.
FLOAT_TO_PCM16 = 32768.0


def repo_root() -> str:
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def assets_dir() -> str:
    return os.path.join(repo_root(), "app", "src", "main", "assets")


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def pad_samples_for(encoder_t: int) -> int:
    """StreamingPreviewTuning.padSamplesFor -- the pad the canary really feeds, per pack."""
    ms = max(MEASURED_PAD_MS, FRAME_SHIFT_MS * encoder_t + PAD_MARGIN_MS)
    return ms * SAMPLE_RATE // 1000


# ---------------------------------------------------------------------------- verify

def verify() -> int:
    """Re-hash every committed clip against this table. No network, nothing installed."""
    problems = []
    rows = [(lang, c["asset"], c["bytes"], c["sha256"]) for lang, c in CLIPS.items()]
    rows.append(("en/zh", *OWNER_CLIP))
    for lang, asset, want_bytes, want_sha in rows:
        path = os.path.join(assets_dir(), asset)
        if not os.path.isfile(path):
            problems.append(f"{asset} is missing")
            continue
        got = os.path.getsize(path)
        if got != want_bytes:
            problems.append(f"{asset} is {got} B, this table says {want_bytes}")
            continue
        got_sha = sha256_file(path)
        if got_sha != want_sha:
            problems.append(f"{asset} sha256 {got_sha} != {want_sha}")
            continue
        with wave.open(path, "rb") as w:
            fmt = (w.getnchannels(), w.getsampwidth(), w.getframerate())
            frames = w.getnframes()
        if fmt != (1, 2, SAMPLE_RATE):
            problems.append(f"{asset} is {fmt}, not mono/PCM16/16 kHz -- the loader would "
                            f"refuse it, and a refused NAMED clip DISABLES that language")
            continue
        print(f"  {lang:5} {asset:22} {got:7} B  {frames / SAMPLE_RATE:5.3f} s  OK")
    if problems:
        for p in problems:
            print(f"  FATAL: {p}")
        return 1
    print(f"verify OK: {len(rows)} canary clips match their pinned bytes, digests and format.")
    return 0


# ---------------------------------------------------------------------------- build

def _np():
    import numpy
    return numpy


def read_wav(path):
    np = _np()
    with wave.open(path, "rb") as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, SAMPLE_RATE), path
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype="<i2").astype("float32") / 32768.0


def write_wav(samples, path, rate=SAMPLE_RATE):
    """float -> PCM16 at [FLOAT_TO_PCM16], which is part of the artefact's IDENTITY.

    Measured, because it cost two digests: `read_wav` divides by 32768 and a writer that
    multiplies by 32767 rounds back to the same integer only while |sample| < 16384 — so the
    quieter German and Russian clips round-tripped byte-identically while the louder Indonesian
    and Korean ones came back the same LENGTH with a different digest. The scale factor is not a
    style choice in a file whose output is digest-pinned.
    """
    np = _np()
    pcm = np.clip(np.round(samples * FLOAT_TO_PCM16), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())


def ffmpeg_to_16k(src, dst):
    """Container normalisation ONLY -- mono, 16 kHz, PCM16, through ffmpeg's swresample. Never a
    hand-rolled interpolation; see this file's docstring for what that cost."""
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", src,
                    "-ac", "1", "-ar", str(SAMPLE_RATE), "-c:a", "pcm_s16le", dst], check=True)


def recognizer_for(pack):
    """SherpaPreviewRecognizerFactory.load's exact configuration, over a placed pack payload."""
    import sherpa_onnx
    d = os.path.join(repo_root(), pack, "src", "main", "assets", pack)
    names = sorted(n for n in os.listdir(d) if n.endswith(".onnx"))
    enc = next(n for n in names if "encoder" in n)
    dec = next(n for n in names if "decoder" in n)
    joi = next(n for n in names if "joiner" in n)
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=os.path.join(d, "tokens.txt"),
        encoder=os.path.join(d, enc), decoder=os.path.join(d, dec), joiner=os.path.join(d, joi),
        num_threads=2, sample_rate=SAMPLE_RATE, feature_dim=80, dither=0.0,
        enable_endpoint_detection=False, decoding_method="greedy_search",
        model_type="", provider="cpu", debug=False)


def decode(rec, samples, encoder_t):
    """PreviewCanary.run's procedure: 512-sample chunks, decode while ready, ONE pad of the
    pack's own derived length, inputFinished, drain."""
    np = _np()
    s = rec.create_stream()
    i = 0
    while i < len(samples):
        n = min(CHUNK, len(samples) - i)
        s.accept_waveform(SAMPLE_RATE, samples[i:i + n])
        while rec.is_ready(s):
            rec.decode_stream(s)
        i += n
    s.accept_waveform(SAMPLE_RATE, np.zeros(pad_samples_for(encoder_t), dtype="float32"))
    s.input_finished()
    while rec.is_ready(s):
        rec.decode_stream(s)
    return rec.get_result_all(s)


def fleurs_row(config, split, row_id):
    """One utterance from the pinned revision, via the datasets-server's `first-rows` (which
    carries `num_samples`, the published transcript and a direct 16 kHz wav per row)."""
    url = ("https://datasets-server.huggingface.co/first-rows"
           f"?dataset={FLEURS_DATASET.replace('/', '%2F')}&config={config}&split={split}")
    with urllib.request.urlopen(url, timeout=180) as r:
        rows = json.load(r)["rows"]
    matches = [x["row"] for x in rows if x["row"]["id"] == row_id]
    if not matches:
        raise SystemExit(f"FLEURS {config}/{split}: row id {row_id} is not in the first 100 rows; "
                         f"the pinned item must be re-found before this clip can be rebuilt")
    # The shortest recording of that sentence, which is what was picked originally.
    return min(matches, key=lambda r: r["num_samples"])


def build_fleurs(lang, spec, out_dir):
    row = fleurs_row(spec["config"], spec["split"], spec["row"])
    src = os.path.join(out_dir, f"{lang}-fleurs.src")
    full = os.path.join(out_dir, f"{lang}-fleurs-16k.wav")
    req = urllib.request.Request(row["audio"][0]["src"], headers={"User-Agent": "we-canary/1.0"})
    with urllib.request.urlopen(req, timeout=300) as r:
        open(src, "wb").write(r.read())
    ffmpeg_to_16k(src, full)
    rec = recognizer_for(spec["pack"])
    x = read_wav(full)
    result = decode(rec, x, spec["encoder_t"])
    kept = [t for t in result.timestamps if t <= spec["cut_s"]]
    if not kept:
        raise SystemExit(f"{lang}: no decoder token lands at or before {spec['cut_s']} s")
    end = min(len(x), int((kept[-1] + TAIL_MS / 1000.0) * SAMPLE_RATE))
    out = os.path.join(out_dir, spec["asset"])
    write_wav(x[:end], out)
    # What the clip SAYS is re-read from the cut file, never carried over from the full one.
    cut = decode(rec, read_wav(out), spec["encoder_t"])
    print(f"  {lang}: {spec['config']}/{spec['split']} id {spec['row']} "
          f"({row['num_samples'] / SAMPLE_RATE:.2f} s) -> {end / SAMPLE_RATE:.3f} s")
    print(f"     reference: {row['transcription']}")
    print(f"     tokens   : {list(cut.tokens)}")
    print(f"     text     : {cut.text}")
    return out


def build_kokoro(lang, spec, out_dir, kokoro_dir):
    import sherpa_onnx
    k = kokoro_dir
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                model=os.path.join(k, "model.onnx"),
                voices=os.path.join(k, "voices.bin"),
                tokens=os.path.join(k, "tokens.txt"),
                data_dir=os.path.join(k, "espeak-ng-data"),
                dict_dir=os.path.join(k, "dict"),
                lexicon=",".join([os.path.join(k, "lexicon-us-en.txt"),
                                  os.path.join(k, "lexicon-zh.txt")]),
                lang=KOKORO_LANG,
            ),
            num_threads=2, provider="cpu", debug=False,
        ),
        max_num_sentences=1,
    )
    np = _np()
    audio = sherpa_onnx.OfflineTts(cfg).generate(KOKORO_TEXT, sid=KOKORO_SID, speed=spec["speed"])
    x = np.asarray(audio.samples, dtype="float32")
    x = x * (spec["peak"] / max(1e-9, float(np.abs(x).max())))
    raw = os.path.join(out_dir, "fr-kokoro-native.wav")
    write_wav(x, raw, rate=audio.sample_rate)
    out = os.path.join(out_dir, spec["asset"])
    ffmpeg_to_16k(raw, out)
    print(f"  fr: kokoro sid {KOKORO_SID} '{KOKORO_TEXT}' speed {spec['speed']} "
          f"-> {os.path.getsize(out)} B")
    return out


def build(langs, kokoro_dir):
    """Regenerate, then check each clip against the thing it is CHECKABLE on.

    Two different checks, because the two sources are not equally reproducible:

      * a FLEURS clip must come back byte-identical — same revision, same item, same ffmpeg, same
        token-boundary cut — so its sha256 is the check;
      * the kokoro clip cannot (see the fr row's note: the synthesis is not bit-deterministic), so
        its check is the DECODE, which is stable.

    Every clip gets the decode check regardless, because that is the property the canary depends
    on and a byte match without it would only prove the file copied.
    """
    out_dir = os.path.join(repo_root(), "build", "canary-clips")
    os.makedirs(out_dir, exist_ok=True)
    results = []
    for lang in langs:
        spec = CLIPS[lang]
        out = (build_kokoro(lang, spec, out_dir, kokoro_dir) if spec["source"] == "kokoro"
               else build_fleurs(lang, spec, out_dir))
        got = sha256_file(out)
        wants_bytes = spec.get("reproducible_bytes", True)
        bytes_ok = (got == spec["sha256"]) if wants_bytes else True
        if wants_bytes:
            flag = "MATCHES the committed clip" if bytes_ok else f"DIFFERS (got {got})"
        else:
            flag = ("byte-identical to the committed clip (not required, and not expected)"
                    if got == spec["sha256"] else
                    "different bytes, which is EXPECTED for this source — checked on the decode")
        print(f"     {os.path.basename(out)}: {os.path.getsize(out)} B, {flag}")
        text = decode(recognizer_for(spec["pack"]), read_wav(out), spec["encoder_t"]).text
        text_ok = text == spec["text"]
        print(f"     decode: {'as pinned' if text_ok else 'CHANGED'} -> {text!r}")
        results.append((lang, bytes_ok, text_ok))
    bad = [lang for lang, b, t in results if not (b and t)]
    print(f"\nbuilt {len(results)} clip(s) under {out_dir}. Nothing was copied into main assets: a "
          f"clip is a VERDICT INPUT, so replacing one is a deliberate edit plus a re-measured "
          f"rule, never a side effect of running this script.")
    if bad:
        print(f"  FATAL: {bad} did not reproduce")
    return 1 if bad else 0


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("verify", "build"):
        raise SystemExit(__doc__)
    if sys.argv[1] == "verify":
        raise SystemExit(verify())
    langs = sys.argv[2:] or list(CLIPS)
    unknown = [l for l in langs if l not in CLIPS]
    if unknown:
        raise SystemExit(f"unknown language(s) {unknown}; this table has {list(CLIPS)}")
    raise SystemExit(build(langs, os.environ.get("KOKORO_DIR", DEFAULT_KOKORO_DIR)))


if __name__ == "__main__":
    main()
