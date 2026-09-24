"""Export whisper-large-v3-turbo's ENCODER to a plain f32 .tflite with the probe's graph interface
(signature 'encode', input args_0 f32[1,128,3000], output output_0 f32[1,1500,1280]), then check the
export on the host against PyTorch on the probe's exact input (java.util.Random(42) floats in [-1,1]).

Run on the MS-02 inside ~/mtk-whisper/venv:  python convert_encoder.py
Writes: out/turbo_encoder_f32.tflite, out/reference.json (fingerprints), convert.log via nohup.
"""
import json, os, sys, time

import numpy as np
import torch

MODEL_DIR = os.path.expanduser("~/mtk-whisper/models/whisper-large-v3-turbo")
OUT_DIR = os.path.expanduser("~/mtk-whisper/out")
os.makedirs(OUT_DIR, exist_ok=True)
OUT = os.path.join(OUT_DIR, "turbo_encoder_f32.tflite")


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


class JavaRandom:
    """java.util.Random, bit-exact, so the host reference and the tablet see the same input."""
    MASK = (1 << 48) - 1

    def __init__(self, seed):
        self.seed = (seed ^ 0x5DEECE66D) & self.MASK

    def next(self, bits):
        self.seed = (self.seed * 0x5DEECE66D + 0xB) & self.MASK
        return self.seed >> (48 - bits)

    def next_float(self):
        return self.next(24) / float(1 << 24)


def probe_input(n):
    r = JavaRandom(42)
    x = np.empty(n, dtype=np.float32)
    for i in range(n):
        x[i] = np.float32(np.float32(r.next_float()) * np.float32(2.0) - np.float32(1.0))
    return x


def fingerprint(out):
    o = out.astype(np.float64).ravel()
    return {"count": int(o.size), "mean": float(o.mean()), "mean_abs": float(np.abs(o).mean()),
            "min": float(o.min()), "max": float(o.max()), "head": [float(v) for v in o[:8]]}


try:
    import litert_torch as lt
    log("litert_torch", getattr(lt, "__version__", "?"))
except ImportError:
    import ai_edge_torch as lt
    log("ai_edge_torch", getattr(lt, "__version__", "?"))

from transformers import WhisperForConditionalGeneration

log("loading", MODEL_DIR)
model = WhisperForConditionalGeneration.from_pretrained(MODEL_DIR, torch_dtype=torch.float32)
enc = model.model.encoder.eval()
cfg = model.config
log(f"encoder: layers={cfg.encoder_layers} d_model={cfg.d_model} heads={cfg.encoder_attention_heads} mels={cfg.num_mel_bins} act={cfg.activation_function}")


class Encode(torch.nn.Module):
    def __init__(self, e):
        super().__init__()
        self.e = e

    def forward(self, args_0):
        return self.e(input_features=args_0).last_hidden_state


wrapper = Encode(enc).eval()
x = probe_input(1 * cfg.num_mel_bins * 3000).reshape(1, cfg.num_mel_bins, 3000)
log("reference forward (PyTorch, fp32, CPU)")
with torch.no_grad():
    t0 = time.time()
    ref = wrapper(torch.from_numpy(x)).numpy()
    log(f"pytorch encode {time.time() - t0:.1f} s, out {ref.shape}")
ref_fp = fingerprint(ref)
log("pytorch fingerprint", json.dumps(ref_fp))
np.save(os.path.join(OUT_DIR, "probe_input.npy"), x)
np.save(os.path.join(OUT_DIR, "reference_encoder_out.npy"), ref)

log("converting with litert-torch (f32, static [1,%d,3000])" % cfg.num_mel_bins)
t0 = time.time()
sample = (torch.zeros(1, cfg.num_mel_bins, 3000, dtype=torch.float32),)
edge = lt.signature("encode", wrapper, sample).convert() if hasattr(lt, "signature") else lt.convert(wrapper, sample)
log(f"convert {time.time() - t0:.1f} s; exporting to {OUT}")
t0 = time.time()
edge.export(OUT)
log(f"export {time.time() - t0:.1f} s; size {os.path.getsize(OUT):,} B")

log("host check with the LiteRT interpreter (CPU)")
try:
    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(model_path=OUT, num_threads=16)
    sigs = it.get_signature_list()
    log("signatures", json.dumps({k: {"inputs": v["inputs"], "outputs": v["outputs"]} for k, v in sigs.items()}))
    name = "encode" if "encode" in sigs else list(sigs)[0]
    runner = it.get_signature_runner(name)
    in_name = list(sigs[name]["inputs"])[0]
    t0 = time.time()
    out = runner(**{in_name: x})
    out = list(out.values())[0]
    log(f"tflite encode {time.time() - t0:.1f} s, out {out.shape}")
    tf_fp = fingerprint(out)
    log("tflite fingerprint", json.dumps(tf_fp))
    diff = np.abs(out.astype(np.float64) - ref.astype(np.float64))
    log(f"max|diff| {diff.max():.6f}  mean|diff| {diff.mean():.6f}")
    result = {"pytorch": ref_fp, "tflite_host": tf_fp, "max_abs_diff": float(diff.max()), "mean_abs_diff": float(diff.mean()),
              "signature": name, "input": in_name, "outputs": sigs[name]["outputs"], "file": OUT, "bytes": os.path.getsize(OUT)}
except Exception as e:  # keep the export even if the host check fails
    log("host check FAILED:", repr(e))
    result = {"pytorch": ref_fp, "host_check_error": repr(e), "file": OUT, "bytes": os.path.getsize(OUT)}

with open(os.path.join(OUT_DIR, "reference.json"), "w") as f:
    json.dump(result, f, indent=1)
log("DONE")
