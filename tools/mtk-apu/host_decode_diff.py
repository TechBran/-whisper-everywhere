#!/usr/bin/env python3
"""The host differential test for liblitertasr.so's float decode loop (P1b task 7).

The same loop `litert_asr.cpp`'s nativeDecodeSegment runs, written a second time in Python and driven over the
UNCOMPILED f32 pair in the LiteRT interpreter on the MS-02:

  encoder `encode`(input_features f32[1,128,3000]) -> output_0..7 = k/v_cache_cross_0..3
  decoder `decode`(input_ids, attention_mask, k/v_cache_self_i_in, k/v_cache_cross_i, position_ids)
          -> output_0 = logits f32[1,51866,1,1], output_1..8 = k/v_cache_self_i_out

with the app's decode mode (NpuDecodePolicy): the prompt [SOT, <|en|>, TRANSCRIBE], the always-on mask
(WhisperTokens.BASE_SUPPRESS + the six large-v3 control ids + <|notimestamps|>) applied to the float logits as
-inf at every generated step, the begin mask [220, EOT] at the first generated step only, greedy argmax with
ties to the first index, timestamps emitted, the 199-slot right-aligned window (the mask's LAST t+1 columns
open at step t, -1e4 elsewhere), and the returned self caches fed back as the next step's inputs. The ladder,
the text-only repetition window, avg_logprob (text + EOT, never timestamps) and p(nospeech) at the SOT step are
computed the same way native computes them, so the stats line can be read beside the device's `decode:` line.

Then it compares the host's per-step top-8 (after masking, exactly what the probe wrote) against the tablet's
trace from `mode=e2eqc topk=8` (t8b_appmode_topk_e2eqc.steps.jsonl): argmax equality, top-8 set overlap and
order, and the largest |logit difference| over the ids both top-8s share. The tablet computes in fp16 (the
MediaTek compiler relaxes f32 to fp16), the host in f32, so the expectation is argmax equal at every step, the
top-8 sets equal or nearly, and logit differences at fp16 scale — anything else is a finding.

Run on the MS-02 (ai_edge_litert + transformers are both in venv-exp):

  ~/mtk-whisper/venv-exp/bin/python host_decode_diff.py
  ~/mtk-whisper/venv-exp/bin/python host_decode_diff.py --mel ~/mtk-whisper/canary_mel128.bin --trace none

  ~/mtk-whisper/venv-exp/bin/python host_decode_diff.py --fp16-io     # round every boundary tensor to fp16

Exit code: 0 when the host's ids equal the tablet's and every compared step's argmax agrees; 2 when the two agree
up to a first divergence at which the host's own margin between its choice and the tablet's is under
--fp16-tolerance (a near-tie that precision decides, reported as such); 1 for anything else (a finding). Only
the first divergence is judged: after it the two decoders are fed different tokens, so later steps are printed
and never scored.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import random
import re
import sys
import time

import numpy as np

HOME = os.path.expanduser("~")
ENC = f"{HOME}/mtk-whisper/out/pair/turbo_encoder_qcio_f32.tflite"
DEC = f"{HOME}/mtk-whisper/out/pair/turbo_decoder_mtk_f32.tflite"
MEL = f"{HOME}/mtk-whisper/jfk_mel128.bin"
TRACE = f"{HOME}/.androidbuild/probe-logs/t8b_appmode_topk_e2eqc.steps.jsonl"
TOKENIZER = f"{HOME}/mtk-whisper/models/whisper-large-v3-turbo"

# The tablet's ids in the app's decode mode (t8 / t8b / t11, identical across all three runs).
REFERENCE_IDS = {
    "jfk_mel128.bin": [50365, 400, 370, 11, 452, 7177, 6280, 11, 1029, 406, 437, 428, 1941, 393, 360, 337, 291,
                       11, 1029, 437, 291, 393, 360, 337, 428, 1941, 13, 50915],
    "canary_mel128.bin": [50365, 1485, 11, 732, 11, 1045, 11, 1451, 11, 1732, 13, 50493],
}

# ---- the token facts native and Kotlin hold (large-v3 / turbo, 100 languages) ----------------------------
EOT = 50257
SOT = 50258
LANG_FIRST = 50259
LANG_COUNT = 100
TRANSLATE = LANG_FIRST + LANG_COUNT          # 50359
TRANSCRIBE = TRANSLATE + 1                   # 50360
NO_SPEECH = TRANSLATE + 4                    # 50363
NO_TIMESTAMPS = TRANSLATE + 5                # 50364
TIMESTAMP_SLOTS = 1501
LEADING_SPACE = 220

# WhisperTokens.BASE_SUPPRESS, verbatim (app/src/main/java/com/whispereverywhere/npu/WhisperTokens.kt). When
# this script runs from a checkout the Kotlin literal is read and compared, so the two copies cannot drift.
BASE_SUPPRESS = [
    1, 2, 7, 8, 9, 10, 14, 25, 26, 27,
    28, 29, 31, 58, 59, 60, 61, 62, 63, 90,
    91, 92, 93, 359, 503, 522, 542, 873, 893, 902,
    918, 922, 931, 1350, 1853, 1982, 2460, 2627, 3246, 3253,
    3268, 3536, 3846, 3961, 4183, 4667, 6585, 6647, 7273, 9061,
    9383, 10428, 10929, 11938, 12033, 12331, 12562, 13793, 14157, 14635,
    15265, 15618, 16553, 16604, 18362, 18956, 20075, 21675, 22520, 26130,
    26161, 26435, 28279, 29464, 31650, 32302, 32470, 36865, 42863, 47425,
    49870, 50254,
]
# WhisperTokenFamily.suppress = BASE_SUPPRESS + the six control ids (SOT, translate, transcribe, startoflm,
# startofprev, nospeech); NpuDecodePolicy.suppressList adds <|notimestamps|>: 89 ids for large-v3.
SUPPRESS = sorted(set(BASE_SUPPRESS + [SOT, TRANSLATE, TRANSCRIBE, TRANSLATE + 2, TRANSLATE + 3, NO_SPEECH,
                                       NO_TIMESTAMPS]))
BEGIN_SUPPRESS = [LEADING_SPACE, EOT]
PROMPT = [SOT, LANG_FIRST, TRANSCRIBE]       # <|en|>

# NpuDecodePolicy's guard constants.
TEMPERATURES = [0.0, 0.2, 0.4, 0.6, 0.8, 1.0]
ENTROPY_THOLD = 2.4
LOGPROB_THOLD = -1.0
NO_SPEECH_THOLD = 0.6
CYCLE_MAX_DISTINCT = 8
ENTROPY_WINDOW = 32

MASK_BLOCKED = -1e4       # fp16-safe; the value the pair was exported and validated with
N_FRAMES = 3000


def check_kotlin_suppress():
    here = os.path.dirname(os.path.abspath(__file__))
    kt = os.path.join(here, "..", "..", "app", "src", "main", "java", "com", "whispereverywhere", "npu",
                      "WhisperTokens.kt")
    if not os.path.exists(kt):
        return "not checked (no checkout beside this script)"
    src = open(kt, encoding="utf-8").read()
    m = re.search(r"val BASE_SUPPRESS: IntArray = intArrayOf\(([^)]*)\)", src)
    if not m:
        raise SystemExit("WhisperTokens.kt: BASE_SUPPRESS literal not found")
    ids = [int(x) for x in re.findall(r"\d+", m.group(1))]
    if ids != BASE_SUPPRESS:
        raise SystemExit("BASE_SUPPRESS here differs from WhisperTokens.kt")
    return "equal to WhisperTokens.kt"


# ---- the float loop's pure helpers, one per native helper ---------------------------------------------

def suppress_then_argmax(logits, suppress, begin, apply_begin):
    """litert_asr.cpp suppressThenArgmaxF: the masks write -inf, then the scan; ties to the first index."""
    logits[suppress] = -np.inf
    if apply_begin:
        logits[begin] = -np.inf
    if not np.isfinite(logits).any():
        return -1
    return int(np.argmax(logits))    # numpy's argmax also resolves ties to the first index


def log_sum_exp(logits, s, exclude_floor):
    v = logits.astype(np.float64)
    if exclude_floor:
        v = v[np.isfinite(v)]
    if v.size == 0:
        return None
    v = s * v
    mx = v.max()
    return float(mx + math.log(np.exp(v - mx).sum()))


def no_speech_probability(raw, token):
    lz = log_sum_exp(raw, 1.0, exclude_floor=False)
    return -1.0 if lz is None else math.exp(float(raw[token]) - lz)


def masked_logprob(logits, temperature, tok):
    s = 1.0 / temperature if temperature > 0.0 else 1.0
    lz = log_sum_exp(logits, s, exclude_floor=True)
    return 0.0 if lz is None else s * float(logits[tok]) - lz


def suppress_then_sample(logits, suppress, begin, apply_begin, temperature, rng):
    """The T > 0 rungs. The same distribution native draws from, but NOT the same stream: native seeds
    std::mt19937 with 0x5EED ^ rung and draws through uniform_real_distribution, which Python's Random does
    not reproduce. Only rung 0 is compared step by step; a segment that climbs the ladder is compared by its
    stats line, not its ids."""
    logits[suppress] = -np.inf
    if apply_begin:
        logits[begin] = -np.inf
    live = np.isfinite(logits)
    if not live.any():
        return -1
    v = logits.astype(np.float64)
    mx = v[live].max()
    w = np.where(live, np.exp((v - mx) / temperature), 0.0)
    target = rng.random() * w.sum()
    c = np.cumsum(w)
    i = int(np.searchsorted(c, target, side="left"))
    return min(i, int(np.flatnonzero(live)[-1]))


def trailing_entropy(ids, count):
    hist, n, start = {}, 0, count
    i = count - 1
    while i >= 0 and n < ENTROPY_WINDOW:
        if ids[i] < EOT:
            hist[ids[i]] = hist.get(ids[i], 0) + 1
            n += 1
            start = i
        i -= 1
    if n <= 0:
        return 0.0, 0, start
    h = -sum((c / n) * math.log(c / n) for c in hist.values())
    return h, len(hist), start


# ---- the models -----------------------------------------------------------------------------------

class Pair:
    def __init__(self, enc_path, dec_path, threads):
        from ai_edge_litert.interpreter import Interpreter
        t0 = time.time()
        self.enc_i = Interpreter(model_path=enc_path, num_threads=threads)
        self.enc = self.enc_i.get_signature_runner("encode")
        t1 = time.time()
        self.dec_i = Interpreter(model_path=dec_path, num_threads=threads)
        self.dec = self.dec_i.get_signature_runner("decode")
        t2 = time.time()
        print(f"load: encoder {t1 - t0:.1f} s, decoder {t2 - t1:.1f} s", flush=True)
        dsig = self.dec_i.get_signature_list()["decode"]
        self.layers = sum(1 for n in dsig["inputs"] if n.startswith("k_cache_self_"))
        self.self_k_shape = tuple(self.dec.get_input_details()["k_cache_self_0_in"]["shape"])
        self.self_v_shape = tuple(self.dec.get_input_details()["v_cache_self_0_in"]["shape"])
        self.mask_len = int(self.dec.get_input_details()["attention_mask"]["shape"][-1])
        self.vocab = int(np.prod(self.dec.get_output_details()["output_0"]["shape"]))
        print(f"decoder: {self.layers} layers, window {self.mask_len}, vocab {self.vocab}, "
              f"self k {self.self_k_shape} v {self.self_v_shape}", flush=True)
        self.cross = None
        self.self_in = None

    fp16_io = False

    @staticmethod
    def _round(a):
        """--fp16-io: store a boundary tensor the way the APU does (every value fp16-representable)."""
        return a.astype(np.float16).astype(np.float32)

    def encode(self, mel):
        t0 = time.time()
        out = self.enc(input_features=mel.reshape(1, -1, N_FRAMES).astype(np.float32))
        self.cross = {}
        for i in range(self.layers):
            self.cross[f"k_cache_cross_{i}"] = out[f"output_{2 * i}"]
            self.cross[f"v_cache_cross_{i}"] = out[f"output_{2 * i + 1}"]
        if self.fp16_io:
            self.cross = {k: self._round(v) for k, v in self.cross.items()}
        print(f"encode: {time.time() - t0:.1f} s; k_cache_cross_0 {self.cross['k_cache_cross_0'].shape} "
              f"mean_abs {float(np.abs(self.cross['k_cache_cross_0']).mean()):.5f}", flush=True)

    def zero_self(self):
        self.self_in = {}
        for i in range(self.layers):
            self.self_in[f"k_cache_self_{i}_in"] = np.zeros(self.self_k_shape, np.float32)
            self.self_in[f"v_cache_self_{i}_in"] = np.zeros(self.self_v_shape, np.float32)

    def step(self, token, position):
        """One decoder step: the right-aligned window, LAST position+1 mask columns open."""
        mask = np.full((1, 1, 1, self.mask_len), MASK_BLOCKED, np.float32)
        first_live = self.mask_len - 1 - position
        mask[..., first_live:] = 0.0
        kw = dict(self.cross)
        kw.update(self.self_in)
        kw["input_ids"] = np.array([[token]], np.int32)
        kw["position_ids"] = np.array([position], np.int32)
        kw["attention_mask"] = mask
        out = self.dec(**kw)
        # the returned caches (the window minus its oldest column) are the next step's inputs
        for i in range(self.layers):
            self.self_in[f"k_cache_self_{i}_in"] = out[f"output_{1 + 2 * i}"]
            self.self_in[f"v_cache_self_{i}_in"] = out[f"output_{2 + 2 * i}"]
            if self.fp16_io:
                for kind in ("k", "v"):
                    key = f"{kind}_cache_self_{i}_in"
                    self.self_in[key] = self._round(self.self_in[key])
        logits = out["output_0"].reshape(-1).astype(np.float32).copy()
        return self._round(logits) if self.fp16_io else logits


def decode_segment(pair, prompt, max_tokens, trace_sink):
    """nativeDecodeSegment's contract in Python. trace_sink(t, token_in, logits_after_mask) sees every step
    of rung 0, exactly the array the probe's topk line was cut from (raw on prompt steps)."""
    vocab, mask_len = pair.vocab, pair.mask_len
    last_position = mask_len - 2
    prompt_len = len(prompt)
    timestamp_begin = vocab - TIMESTAMP_SLOTS
    no_speech_prob = -1.0
    steps_run = 0
    result = None
    for rung, temperature in enumerate(TEMPERATURES):
        rng = random.Random(0x5EED ^ rung)
        pair.zero_self()
        out = []
        text_count = 0
        hit_eot = False
        sum_lp, scored = 0.0, 0
        failed_entropy, cut_to = False, 0
        entropy_last, distinct_last, entropy_measured = 0.0, 0, False
        nxt = prompt[0]
        for position in range(0, last_position + 1):
            token_in = prompt[position] if position < prompt_len else nxt
            logits = pair.step(token_in, position)
            steps_run += 1
            if not np.isfinite(logits).all():
                raise SystemExit(f"non-finite logits at position {position}")
            if rung == 0 and position == 0:
                no_speech_prob = no_speech_probability(logits, NO_SPEECH)
            if position + 1 < prompt_len:
                if rung == 0 and trace_sink:
                    trace_sink(position, token_in, logits)
                continue
            apply_begin = position == prompt_len - 1
            if temperature == 0.0:
                tok = suppress_then_argmax(logits, SUPPRESS, BEGIN_SUPPRESS, apply_begin)
            else:
                tok = suppress_then_sample(logits, SUPPRESS, BEGIN_SUPPRESS, apply_begin, temperature, rng)
            if rung == 0 and trace_sink:
                trace_sink(position, token_in, logits)
            if tok < 0:
                raise SystemExit(f"every logit is -inf at position {position}")
            if tok < timestamp_begin:
                sum_lp += masked_logprob(logits, temperature, tok)
                scored += 1
            if tok == EOT:
                hit_eot = True
                break
            out.append(tok)
            if tok < EOT:
                text_count += 1
            if text_count > ENTROPY_WINDOW:
                entropy_last, distinct_last, window_start = trailing_entropy(out, len(out))
                entropy_measured = True
                if entropy_last < ENTROPY_THOLD and distinct_last <= CYCLE_MAX_DISTINCT:
                    failed_entropy, cut_to = True, window_start
                    break
            if len(out) >= max_tokens:
                break
            nxt = tok
        avg_lp = sum_lp / scored if scored > 0 else float("nan")
        low_conf = scored > 0 and avg_lp < LOGPROB_THOLD and no_speech_prob < NO_SPEECH_THOLD
        last_rung = rung + 1 == len(TEMPERATURES)
        terminator = "eot" if hit_eot else ("budget" if len(out) >= max_tokens else "cap")
        result = dict(ids=out, nsp=no_speech_prob, lp=avg_lp, rung=rung, terminator=terminator,
                      steps=steps_run, ent=entropy_last if entropy_measured else float("nan"))
        if not failed_entropy and not low_conf:
            break
        if last_rung:
            if failed_entropy:
                result["ids"] = out[:cut_to]
                result["terminator"] = "cut"
            break
    return result


def top_k(logits, k):
    # stable descending sort, ties to the lower id (the Kotlin sortedByDescending is stable too)
    idx = np.argsort(-logits, kind="stable")[:k]
    return [(int(i), float(logits[i])) for i in idx]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--enc", default=ENC)
    ap.add_argument("--dec", default=DEC)
    ap.add_argument("--mel", default=MEL)
    ap.add_argument("--trace", default=TRACE, help="the tablet's steps.jsonl, or 'none'")
    ap.add_argument("--tokenizer", default=TOKENIZER)
    ap.add_argument("--threads", type=int, default=os.cpu_count() or 8)
    ap.add_argument("--topk", type=int, default=8)
    ap.add_argument("--fp16-io", action="store_true",
                    help="round the cross-KV, the self-KV and the logits to fp16 at every boundary, as the APU "
                         "stores them (the compute inside each graph stays f32)")
    ap.add_argument("--fp16-tolerance", type=float, default=1.0,
                    help="a first divergence whose HOST margin (host choice minus the tablet's choice, on the "
                         "host's own logits) is below this many logits is a near-tie precision decides (exit 2); "
                         "at or above it, a finding (exit 1)")
    a = ap.parse_args()
    Pair.fp16_io = a.fp16_io

    print("BASE_SUPPRESS:", check_kotlin_suppress(), f"| suppress list {len(SUPPRESS)} ids | begin {BEGIN_SUPPRESS} "
          f"| prompt {PROMPT}", flush=True)
    mel = np.fromfile(os.path.expanduser(a.mel), dtype="<f4")
    print(f"mel {a.mel}: {mel.size} values, mean {float(mel.mean()):.5f}", flush=True)

    pair = Pair(os.path.expanduser(a.enc), os.path.expanduser(a.dec), a.threads)
    assert mel.size % N_FRAMES == 0
    pair.encode(mel)

    host_steps = []

    def sink(t, token_in, logits):
        host_steps.append(dict(t=t, tin=token_in, top=top_k(logits, a.topk),
                               argmax=int(np.argmax(logits)), logits=logits.copy()))

    t0 = time.time()
    max_tokens = pair.mask_len - len(PROMPT)
    res = decode_segment(pair, PROMPT, max_tokens, sink)
    print(f"decode: {len(res['ids'])} ids in {time.time() - t0:.1f} s, terminated by {res['terminator']} "
          f"nsp={res['nsp']:.4f} lp={res['lp']:.4f} ent={res['ent']:.2f} rung={res['rung']} steps={res['steps']}",
          flush=True)

    # THE VERDICT. Only the FIRST disagreement can be judged: after it the two decoders are fed different
    # tokens and nothing downstream compares the same computation. It is classified by the HOST's own margin
    # between its choice and the tablet's: under --fp16-tolerance it is a near-tie that precision decides
    # (exit 2, reported as such); at or above it, f32 was confident about something fp16 disagreed with, which
    # no rounding explains - a finding (exit 1).
    findings, precision_flips = [], []

    def classify(t, host_id, tablet_id, host_logits, tablet_margin=None):
        host_margin = float(host_logits[host_id]) - float(host_logits[tablet_id])
        kind = "timestamp" if host_id >= timestamp_begin and tablet_id >= timestamp_begin else \
            ("text" if host_id < EOT and tablet_id < EOT else "mixed")
        note = (f"t={t}: tablet {tablet_id}, host {host_id} ({kind}); host margin {host_margin:.4f}"
                + (f", tablet margin {tablet_margin:.4f}" if tablet_margin is not None else ""))
        (precision_flips if host_margin < a.fp16_tolerance else findings).append(note)
    trace_path = os.path.expanduser(a.trace) if a.trace != "none" else None
    timestamp_begin = pair.vocab - TIMESTAMP_SLOTS
    if trace_path:
        tab = [json.loads(l) for l in open(trace_path) if l.strip()]
        print(f"\nper-step against {os.path.basename(trace_path)} ({len(tab)} tablet steps, "
              f"{len(host_steps)} host steps):")
        print(" t | in    | tablet argmax | host argmax | equal | top-8 overlap | same order | max|dlogit| shared | "
              "host - tablet at argmax")
        worst, agreed, compared = 0.0, 0, 0
        for i, ts in enumerate(tab):
            if i >= len(host_steps):
                print(f"{ts['t']:2d} | host has no step here (it stopped at t={len(host_steps) - 1})")
                if not precision_flips:
                    findings.append(f"t={ts['t']}: host has no step")
                continue
            hs = host_steps[i]
            if ts["in"] != hs["tin"]:
                # Downstream of a disagreement: the two decoders are fed different tokens, so the step is not
                # a comparison of the same computation. Printed, never scored.
                print(f"{ts['t']:2d} | inputs differ (tablet {ts['in']}, host {hs['tin']}) - diverged, not compared")
                continue
            compared += 1
            t_ids = [p[0] for p in ts["top"]]
            h_ids = [p[0] for p in hs["top"]]
            shared = set(t_ids) & set(h_ids)
            diffs = [abs(float(hs["logits"][tid]) - tv) for tid, tv in ts["top"] if tid in shared]
            md = max(diffs) if diffs else float("nan")
            worst = max(worst, md) if diffs else worst
            eq = t_ids[0] == hs["argmax"]
            agreed += int(eq)
            d_arg = float(hs["logits"][t_ids[0]]) - ts["top"][0][1]
            print(f"{ts['t']:2d} | {ts['in']:5d} | {t_ids[0]:13d} | {hs['argmax']:11d} | {'yes' if eq else 'NO '}   | "
                  f"{len(shared)}/{a.topk}           | {'yes' if t_ids == h_ids else 'no '}        | {md:.4f}             | "
                  f"{d_arg:+.4f}")
            if t_ids != h_ids:
                print(f"     tablet {ts['top']}\n     host   {[(p[0], round(p[1], 4)) for p in hs['top']]}")
            if not eq and not precision_flips and not findings:
                tab_margin = ts["top"][0][1] - dict(ts["top"]).get(hs["argmax"], float("nan"))
                classify(ts["t"], hs["argmax"], t_ids[0], hs["logits"], tab_margin)
        print(f"argmax agreed at {agreed} of {compared} compared steps; largest |logit difference| over shared "
              f"top-8 ids: {worst:.4f}")
    else:
        # No tablet trace for this clip: print the host's own top-4 and margin per step, so a divergence
        # against the reference ids can be read as a close call (precision) or a clear one (a finding).
        print("\nhost per-step top-4 (after masking; raw on prompt steps):")
        for hs in host_steps:
            top = hs["top"][:4]
            margin = top[0][1] - top[1][1] if len(top) > 1 else float("nan")
            print(f"{hs['t']:2d} | in {hs['tin']:5d} | margin {margin:7.4f} | " +
                  ", ".join(f"{i}:{v:.4f}" for i, v in top))

    ref = REFERENCE_IDS.get(os.path.basename(a.mel))
    print("\nhost ids:     ", res["ids"])
    if ref is not None:
        same = res["ids"] == ref
        text_same = [i for i in res["ids"] if i < EOT] == [i for i in ref if i < EOT]
        print("tablet ids:   ", ref, "->", "IDENTICAL" if same else
              ("text identical, timestamps differ" if text_same else "TEXT DIFFERS"))
        if not same and not precision_flips and not findings:
            # No trace judged it (or the trace ran out): find the first differing id and read the host's own
            # margin at the step that chose it. out[k] was chosen at position promptLen - 1 + k.
            h, r = res["ids"] + [EOT], ref + [EOT]
            k = next(i for i in range(min(len(h), len(r))) if h[i] != r[i])
            s = len(PROMPT) - 1 + k
            classify(host_steps[s]["t"], h[k], r[k], host_steps[s]["logits"])
    for f in precision_flips:
        print(f"first divergence is a near-tie (< {a.fp16_tolerance} logit on the host), precision decides it:", f)
    for f in findings:
        print("FINDING:", f)
    ok = not findings
    try:
        from transformers import WhisperTokenizer
        tok = WhisperTokenizer.from_pretrained(os.path.expanduser(a.tokenizer))
        print("host text:    ", repr(tok.decode(res["ids"], skip_special_tokens=True)))
        print("with stamps:  ", tok.decode(res["ids"], skip_special_tokens=False, decode_with_timestamps=True))
    except Exception as e:  # the ids are the verdict; the text is for the reader
        print("detokenise failed:", e)
    if not ok:
        print("RESULT: FAIL")
        return 1
    if precision_flips:
        print("RESULT: AGREE UP TO A NEAR-TIE THAT PRECISION DECIDES (exit 2)")
        return 2
    print("RESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
