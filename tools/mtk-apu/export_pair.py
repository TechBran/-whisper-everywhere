"""The PRODUCT-SHAPED turbo pair for the MediaTek APU, in the IO the app's NpuModelSpec / native engine
already describe (Qualcomm's HfWhisper layout):

  encode:  input_features f32[1,128,3000]
           -> k_cache_cross_i f32[20,1,64,1500], v_cache_cross_i f32[20,1,1500,64]   for i in 0..3
  decode:  input_ids i32[1,1], attention_mask f32[1,1,1,200],
           k_cache_self_i_in f32[20,1,64,199], v_cache_self_i_in f32[20,1,199,64]  (i in 0..3),
           k_cache_cross_i, v_cache_cross_i (as above), position_ids i32[1]
           -> logits f32[1,51866,1,1], k_cache_self_i_out f32[20,1,64,199], v_cache_self_i_out f32[20,1,199,64]

The encoder body is the plain HF encoder (the one measured at 1.71 s on the APU) with a cross-KV tail: each
decoder layer's encoder_attn k_proj / v_proj applied to the hidden states, laid out per head. The decoder is
Qualcomm's monkey-patched QcWhisperDecoder (vendored model_adaptation.py): its cache update is concat + slice,
no DynamicUpdateSlice (MediaTek has none). Output order is documented above; litert-torch names them output_N.

Runs on the MS-02 in ~/mtk-whisper/venv-exp with PYTHONPATH=~/mtk-whisper/qcwrap (transformers 4.56.2).
Writes out/pair/turbo_encoder_qcio_f32.tflite, out/pair/turbo_decoder_qcio_f32.tflite, out/pair/pair.json.
"""
import json, os, sys, time

import numpy as np
import torch

MODEL_DIR = os.path.expanduser("~/mtk-whisper/models/whisper-large-v3-turbo")
OUT_DIR = os.path.expanduser("~/mtk-whisper/out/pair")
os.makedirs(OUT_DIR, exist_ok=True)
MEAN_DECODE_LEN = 200
AUDIO_EMB_LEN = 1500


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


import litert_torch as lt
from transformers import WhisperForConditionalGeneration
from qai_hub_models.models.templates.hf_whisper.model_adaptation import monkey_patch_model

log("loading", MODEL_DIR)
model = WhisperForConditionalGeneration.from_pretrained(MODEL_DIR, torch_dtype=torch.float32).eval()
cfg = model.config
L, H, D = cfg.decoder_layers, cfg.decoder_attention_heads, cfg.d_model
HD = D // H
log(f"turbo: dec layers={L} heads={H} d_model={D} head_dim={HD} vocab={cfg.vocab_size} mels={cfg.num_mel_bins}")

# ---------------- encoder: plain HF body + cross-KV tail (Qualcomm layout) ----------------
plain_encoder = model.model.encoder
cross_k = [layer.encoder_attn.k_proj for layer in model.model.decoder.layers]   # Linear(D, D), no bias in whisper k_proj
cross_v = [layer.encoder_attn.v_proj for layer in model.model.decoder.layers]   # Linear(D, D) with bias


class EncodeQcIO(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.enc = plain_encoder
        self.k = torch.nn.ModuleList(cross_k)
        self.v = torch.nn.ModuleList(cross_v)

    def forward(self, input_features):
        h = self.enc(input_features=input_features).last_hidden_state          # [1,1500,1280]
        outs = []
        for i in range(L):
            k = self.k[i](h).view(1, AUDIO_EMB_LEN, H, HD).permute(2, 0, 3, 1)  # [20,1,64,1500]
            v = self.v[i](h).view(1, AUDIO_EMB_LEN, H, HD).permute(2, 0, 1, 3)  # [20,1,1500,64]
            outs.append(k)
            outs.append(v)
        return tuple(outs)


# ---------------- decoder: Qualcomm's adapted decoder (static KV cache, concat+slice) ----------------
# The patch is applied AFTER the encoder is exported, so the plain encoder body is exported untouched.


class DecodeQcIO(torch.nn.Module):
    def __init__(self, qc_decoder):
        super().__init__()
        self.dec = qc_decoder

    def forward(self, input_ids, attention_mask, k_cache_self_0_in, v_cache_self_0_in, k_cache_self_1_in, v_cache_self_1_in,
                k_cache_self_2_in, v_cache_self_2_in, k_cache_self_3_in, v_cache_self_3_in,
                k_cache_cross_0, v_cache_cross_0, k_cache_cross_1, v_cache_cross_1, k_cache_cross_2, v_cache_cross_2,
                k_cache_cross_3, v_cache_cross_3, position_ids):
        kv_self = [(k_cache_self_0_in, v_cache_self_0_in), (k_cache_self_1_in, v_cache_self_1_in),
                   (k_cache_self_2_in, v_cache_self_2_in), (k_cache_self_3_in, v_cache_self_3_in)]
        kv_cross = [(k_cache_cross_0, v_cache_cross_0), (k_cache_cross_1, v_cache_cross_1),
                    (k_cache_cross_2, v_cache_cross_2), (k_cache_cross_3, v_cache_cross_3)]
        logits, new_self = self.dec(
            input_ids=input_ids, attention_mask=attention_mask, past_key_values=kv_self,
            cross_attn_past_key_value=kv_cross, position_ids=position_ids.to(torch.int64))
        outs = [logits]
        for kv in new_self:
            outs.append(kv[0])
            outs.append(kv[1])
        return tuple(outs)


assert L == 4, "the decode signature above is written for turbo's 4 decoder layers"

enc_in = {"input_features": torch.zeros(1, cfg.num_mel_bins, 3000)}
dec_in = {"input_ids": torch.zeros(1, 1, dtype=torch.int32), "attention_mask": torch.zeros(1, 1, 1, MEAN_DECODE_LEN)}
for i in range(L):
    dec_in[f"k_cache_self_{i}_in"] = torch.zeros(H, 1, HD, MEAN_DECODE_LEN - 1)
    dec_in[f"v_cache_self_{i}_in"] = torch.zeros(H, 1, MEAN_DECODE_LEN - 1, HD)
for i in range(L):
    dec_in[f"k_cache_cross_{i}"] = torch.zeros(H, 1, HD, AUDIO_EMB_LEN)
    dec_in[f"v_cache_cross_{i}"] = torch.zeros(H, 1, AUDIO_EMB_LEN, HD)
dec_in["position_ids"] = torch.zeros(1, dtype=torch.int32)

enc = EncodeQcIO().eval()
with torch.no_grad():
    t0 = time.time()
    eo = enc(**enc_in)
    log(f"encoder torch {time.time() - t0:.1f} s ->", [tuple(t.shape) for t in eo])

log("converting encoder")
t0 = time.time()
p = os.path.join(OUT_DIR, "turbo_encoder_qcio_f32.tflite")
lt.signature("encode", enc, sample_kwargs=enc_in).convert().export(p)
log(f"encoder {time.time() - t0:.1f} s -> {p} {os.path.getsize(p):,} B")

monkey_patch_model(model.model)       # replaces encoder/decoder layers with the Qc* forms (in place)
dec = DecodeQcIO(model.model.decoder).eval()
with torch.no_grad():
    # feed the real cross KV into the decoder sample so the trace sees realistic values
    for i in range(L):
        dec_in[f"k_cache_cross_{i}"] = eo[2 * i]
        dec_in[f"v_cache_cross_{i}"] = eo[2 * i + 1]
    dec_in["input_ids"] = torch.tensor([[50258]], dtype=torch.int32)   # <|startoftranscript|>
    mask = torch.full((1, 1, 1, MEAN_DECODE_LEN), -1e4); mask[..., -1] = 0.0   # only the current key is visible at step 0
    dec_in["attention_mask"] = mask
    t0 = time.time()
    do = dec(**dec_in)
    log(f"decoder torch {time.time() - t0:.2f} s ->", [tuple(t.shape) for t in do])
    top = torch.topk(do[0].flatten(), 5)
    log("step-0 top tokens", top.indices.tolist(), [round(x, 2) for x in top.values.tolist()])

meta = {"encoder_inputs": {k: list(v.shape) for k, v in enc_in.items()},
        "encoder_outputs": [f"{p}_cache_cross_{i}" for i in range(L) for p in ("k", "v")],
        "encoder_output_shapes": [list(t.shape) for t in eo],
        "decoder_inputs": {k: list(v.shape) for k, v in dec_in.items()},
        "decoder_outputs": ["logits"] + [f"{p}_cache_self_{i}_out" for i in range(L) for p in ("k", "v")],
        "decoder_output_shapes": [list(t.shape) for t in do]}
json.dump(meta, open(os.path.join(OUT_DIR, "pair.json"), "w"), indent=1)

log("converting decoder")
t0 = time.time()
p = os.path.join(OUT_DIR, "turbo_decoder_qcio_f32.tflite")
lt.signature("decode", dec, sample_kwargs=dec_in).convert().export(p)
log(f"decoder {time.time() - t0:.1f} s -> {p} {os.path.getsize(p):,} B")
log("DONE")
