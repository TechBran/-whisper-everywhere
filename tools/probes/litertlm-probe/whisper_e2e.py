#!/usr/bin/env python3
"""PC half of the E6 end-to-end (encode + greedy decode) measurement of the litert-community whisper .tflite graphs.

  python whisper_e2e.py mel       <clip.wav> <out.bin> --n-mels 128          # whisper log-mel, f32 [n_mels*3000], for the probe app
  python whisper_e2e.py melbank   <melbank-128.bin>                          # cross-check the numpy filterbank against the app's ggml one
  python whisper_e2e.py pc-run    <model.tflite> <mel.bin> --tokenizer tok.json [--mask additive|binary] [--max-tokens 64] [--threads 8]
  python whisper_e2e.py detok     <tokenizer.json> 50258,50259,...            # ids -> text (what the device logged)
  python whisper_e2e.py summarize [tag ...]                                   # e2e result JSONs -> markdown tables (+ detok)

The mel is OpenAI whisper's `log_mel_spectrogram` (n_fft 400, hop 160, periodic Hann, reflect-centred STFT, last frame
dropped -> 3000 frames, slaney/librosa mel filters, log10, clamp to max-8, (x+4)/4) written row-major as the graph's
`args_0` f32[1, n_mels, 3000]. `pc-run` is the same greedy loop the probe app runs, on ai_edge_litert's interpreter, so the
mel + prompt + mask convention is proven on the PC before the tablet session spends thermal budget on it.
"""
import argparse
import glob
import json
import os
import struct
import sys
import time
import wave

import numpy as np

SR = 16000
N_FFT = 400
HOP = 160
N_SAMPLES = 30 * SR
N_FRAMES = 3000
DECODE_WINDOW = 128


# ----------------------------------------------------------------------------------------------- mel

def hz_to_mel(f):
    # librosa.hz_to_mel, htk=False (slaney)
    f = np.asarray(f, dtype=np.float64)
    f_sp = 200.0 / 3
    mels = f / f_sp
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = np.log(6.4) / 27.0
    if mels.ndim:
        log_t = f >= min_log_hz
        mels[log_t] = min_log_mel + np.log(f[log_t] / min_log_hz) / logstep
    elif f >= min_log_hz:
        mels = min_log_mel + np.log(f / min_log_hz) / logstep
    return mels


def mel_to_hz(m):
    m = np.asarray(m, dtype=np.float64)
    f_sp = 200.0 / 3
    freqs = f_sp * m
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = np.log(6.4) / 27.0
    log_t = m >= min_log_mel
    freqs[log_t] = min_log_hz * np.exp(logstep * (m[log_t] - min_log_mel))
    return freqs


def mel_filters(n_mels, sr=SR, n_fft=N_FFT):
    """librosa.filters.mel(sr, n_fft, n_mels, fmin=0, fmax=sr/2, htk=False, norm='slaney') -- whisper's mel_filters.npz."""
    fftfreqs = np.linspace(0, sr / 2, 1 + n_fft // 2)
    mel_f = mel_to_hz(np.linspace(hz_to_mel(0.0), hz_to_mel(sr / 2.0), n_mels + 2))
    fdiff = np.diff(mel_f)
    ramps = np.subtract.outer(mel_f, fftfreqs)
    weights = np.zeros((n_mels, 1 + n_fft // 2))
    for i in range(n_mels):
        lower = -ramps[i] / fdiff[i]
        upper = ramps[i + 2] / fdiff[i + 1]
        weights[i] = np.maximum(0, np.minimum(lower, upper))
    enorm = 2.0 / (mel_f[2:n_mels + 2] - mel_f[:n_mels])
    weights *= enorm[:, None]
    return weights.astype(np.float32)


def read_wav(path):
    w = wave.open(path)
    assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, (w.getframerate(), w.getnchannels(), w.getsampwidth())
    pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    return pcm


def log_mel(pcm, n_mels):
    audio = np.zeros(N_SAMPLES, dtype=np.float32)
    audio[:min(len(pcm), N_SAMPLES)] = pcm[:N_SAMPLES]
    # torch.stft(center=True, pad_mode='reflect'), periodic Hann
    padded = np.pad(audio, (N_FFT // 2, N_FFT // 2), mode="reflect")
    window = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(N_FFT) / N_FFT)).astype(np.float32)
    n_frames = 1 + (len(padded) - N_FFT) // HOP           # 3001
    idx = np.arange(N_FFT)[None, :] + HOP * np.arange(n_frames)[:, None]
    frames = padded[idx] * window[None, :]
    spec = np.fft.rfft(frames, axis=1)
    mag = (np.abs(spec) ** 2).T[:, :-1]                   # [201, 3000]
    mel = mel_filters(n_mels) @ mag
    log_spec = np.log10(np.maximum(mel, 1e-10))
    log_spec = np.maximum(log_spec, log_spec.max() - 8.0)
    log_spec = (log_spec + 4.0) / 4.0
    return log_spec.astype(np.float32)                   # [n_mels, 3000]


def cmd_mel(a):
    pcm = read_wav(a.wav)
    m = log_mel(pcm, a.n_mels)
    m.astype("<f4").tofile(a.out)
    print("%s: %d samples (%.2f s) -> %s %s f32 (%d B) mean=%.4f min=%.4f max=%.4f" % (
        a.wav, len(pcm), len(pcm) / SR, a.out, list(m.shape), m.size * 4, m.mean(), m.min(), m.max()))


def cmd_melbank(a):
    """The app's melbank-128.bin is a ggml prefix: 4 B magic + 11 int32 hparams + n_mel + n_fft (56 B), then n_mel*n_fft f32."""
    b = open(a.melbank, "rb").read()
    n_mel, n_fft = struct.unpack_from("<ii", b, 48)
    f = np.frombuffer(b[56:56 + n_mel * n_fft * 4], dtype="<f4").reshape(n_mel, n_fft)
    mine = mel_filters(n_mel)
    d = np.abs(f - mine)
    print("ggml melbank: n_mel=%d n_fft=%d; numpy slaney filters: max|diff|=%.3e mean|diff|=%.3e (ggml max %.4f)" % (
        n_mel, n_fft, d.max(), d.mean(), f.max()))


# ----------------------------------------------------------------------------------------------- decode conventions

def causal_mask(mode, n=DECODE_WINDOW):
    m = np.zeros((1, 1, n, n), dtype=np.float32)
    if mode == "additive":
        m[0, 0][np.triu_indices(n, 1)] = -1e9
    elif mode == "additive-min":
        m[0, 0][np.triu_indices(n, 1)] = np.finfo(np.float32).min
    elif mode == "binary":
        m[0, 0] = np.tril(np.ones((n, n), dtype=np.float32))
    elif mode == "zeros":
        pass
    else:
        raise SystemExit("mask must be additive|additive-min|binary|zeros")
    return m


def special_ids(tok):
    return {
        "sot": tok.token_to_id("<|startoftranscript|>"),
        "en": tok.token_to_id("<|en|>"),
        "transcribe": tok.token_to_id("<|transcribe|>"),
        "notimestamps": tok.token_to_id("<|notimestamps|>"),
        "eot": tok.token_to_id("<|endoftext|>"),
    }


def cmd_pc_run(a):
    from ai_edge_litert.interpreter import Interpreter
    from tokenizers import Tokenizer
    tok = Tokenizer.from_file(a.tokenizer)
    sp = special_ids(tok)
    prompt = [sp["sot"], sp["en"], sp["transcribe"], sp["notimestamps"]]
    it = Interpreter(model_path=a.model, num_threads=a.threads)
    enc = it.get_signature_runner("encode")
    dec = it.get_signature_runner("decode")
    ein = enc.get_input_details()["args_0"]
    n_mels = int(ein["shape"][1])
    mel = np.fromfile(a.mel, dtype="<f4").reshape(1, n_mels, N_FRAMES)
    t0 = time.perf_counter()
    enc_out = enc(args_0=mel)["output_0"]
    t_enc = time.perf_counter() - t0
    print("encode: %s in %.0f ms; fingerprint mean=%.5f mean_abs=%.5f min=%.4f max=%.4f" % (
        list(enc_out.shape), t_enc * 1e3, enc_out.mean(), np.abs(enc_out).mean(), enc_out.min(), enc_out.max()))
    ids = np.full((1, DECODE_WINDOW), sp["eot"], dtype=np.int32)
    ids[0, :len(prompt)] = prompt
    mask = causal_mask(a.mask)
    out = []
    step_ms = []
    pos = len(prompt) - 1
    while pos < DECODE_WINDOW - 1 and len(out) < a.max_tokens:
        t1 = time.perf_counter()
        logits = dec(args_0=enc_out, args_1=ids, args_2=mask)["output_0"]   # [1, 128, V]
        step_ms.append((time.perf_counter() - t1) * 1e3)
        nxt = int(np.argmax(logits[0, pos]))
        if nxt == sp["eot"]:
            break
        out.append(nxt)
        pos += 1
        ids[0, pos] = nxt
    text = tok.decode(out, skip_special_tokens=True)
    print("decode: %d tokens, %.1f ms/step (min %.1f max %.1f), mask=%s" % (
        len(out), float(np.mean(step_ms)) if step_ms else 0, min(step_ms) if step_ms else 0, max(step_ms) if step_ms else 0, a.mask))
    print("ids: " + ",".join(map(str, out)))
    print("text: " + repr(text))


def cmd_detok(a):
    from tokenizers import Tokenizer
    tok = Tokenizer.from_file(a.tokenizer)
    ids = [int(x) for x in a.ids.split(",") if x.strip()]
    print(repr(tok.decode(ids, skip_special_tokens=True)))


# ----------------------------------------------------------------------------------------------- summarize e2e JSONs

def f1(x):
    return "-" if x is None else ("%.1f" % x)


def mem(o, k):
    m = o.get(k)
    if not m:
        return "-"
    return "%d / %d" % (m.get("rss_kb", -1) // 1024, m.get("pss_kb", -1) // 1024)


def therm(o, k):
    m = o.get(k)
    if not m:
        return "-"
    return "%.1f C / %d" % (m.get("batt_temp_tenths_c", -1) / 10.0, m.get("thermal_status", -1))


E2E_HDR = (
    "| tag | artefact | backend | clip | utt | encode ms | tokens | decode ms/token (run+read) | run-only ms/token | "
    "readback ms/token | decode total ms | utterance total ms | text | RSS/PSS MB | batt C / thermal |\n"
    "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
)


def cmd_summarize(a):
    out = os.path.join(os.path.expanduser("~"), ".androidbuild", "probe-logs")
    tokd = {}
    try:
        from tokenizers import Tokenizer
    except Exception:
        Tokenizer = None
    rows = []
    for f in sorted(glob.glob(os.path.join(out, "*.json"))):
        tag = os.path.basename(f)[:-5]
        if a.tags and tag not in a.tags:
            continue
        try:
            o = json.load(open(f, encoding="utf-8"))
        except Exception as e:
            print("skip", f, e, file=sys.stderr)
            continue
        if o.get("mode") == "e2e":
            rows.append(o)
    print(E2E_HDR)
    for o in rows:
        art = os.path.basename(o.get("model") or "-")
        backend = o.get("accel") + ("" if o.get("no_fallback") else " (+CPU fallback)") + (" %d thr" % o["threads"] if o.get("accel") == "cpu" else "")
        tokp = o.get("tokenizer")
        tk = None
        if Tokenizer and tokp and os.path.exists(tokp):
            tk = tokd.setdefault(tokp, Tokenizer.from_file(tokp))
        for u in o.get("utterances", []):
            ids = u.get("ids", [])
            text = tk.decode(ids, skip_special_tokens=True) if tk else u.get("text", "-")
            print("| %s | %s | %s | %s | %d | %s | %d | %s | %s | %s | %s | %s | `%s` | %s | %s |" % (
                o.get("tag"), art, backend, os.path.basename(u.get("mel", "-")), u.get("index", -1),
                f1(u.get("encode_ms")), len(ids), f1(u.get("decode_ms_per_token")), f1(u.get("decode_run_only_ms_per_token")),
                f1(u.get("readback_ms_per_token")), f1(u.get("decode_ms")), f1(u.get("total_ms")),
                text.replace("|", "/").replace("`", "'"), mem(u, "mem"), therm(u, "mem")))
        if not o.get("ok"):
            print("| %s | %s | %s | FAIL `%s` |" % (o.get("tag"), art, backend, (o.get("error") or "")[:200].replace("|", "/")))
    print()
    for o in rows:
        print("- `%s`: create %s ms, mem after create %s, start %s, end %s; %s" % (
            o["tag"], f1(o.get("create_ms")), mem(o, "mem_after_create"), therm(o, "mem_start"), therm(o, "mem_end"),
            "ok" if o.get("ok") else "FAIL " + (o.get("error") or "")))


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("mel"); p.add_argument("wav"); p.add_argument("out"); p.add_argument("--n-mels", type=int, default=128); p.set_defaults(fn=cmd_mel)
    p = sub.add_parser("melbank"); p.add_argument("melbank"); p.set_defaults(fn=cmd_melbank)
    p = sub.add_parser("pc-run"); p.add_argument("model"); p.add_argument("mel"); p.add_argument("--tokenizer", required=True)
    p.add_argument("--mask", default="additive"); p.add_argument("--max-tokens", type=int, default=64); p.add_argument("--threads", type=int, default=8); p.set_defaults(fn=cmd_pc_run)
    p = sub.add_parser("detok"); p.add_argument("tokenizer"); p.add_argument("ids"); p.set_defaults(fn=cmd_detok)
    p = sub.add_parser("summarize"); p.add_argument("tags", nargs="*"); p.set_defaults(fn=cmd_summarize)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
