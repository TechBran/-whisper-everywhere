"""A KV-cached whisper-large-v3-turbo DECODER step written for the MediaTek compiler: plain multi-head attention
with rank-4 matmuls (the form the encoder compiled in), int32 everywhere (MediaTek legalizes no int64), and
Qualcomm's HfWhisper IO at the boundary so the app's NpuModelSpec describes it unchanged:

  decode(input_ids i32[1,1], attention_mask f32[1,1,1,200],
         k_cache_self_i_in f32[20,1,64,199], v_cache_self_i_in f32[20,1,199,64]   (i = 0..3),
         k_cache_cross_i f32[20,1,64,1500],  v_cache_cross_i f32[20,1,1500,64]    (i = 0..3),
         position_ids i32[1])
    -> logits f32[1,51866,1,1], k_cache_self_i_out f32[20,1,64,199], v_cache_self_i_out f32[20,1,199,64]

Cache semantics (Qualcomm's): the new key/value is appended on the right, the window is 200 wide, the mask's
last column is the current token, and the returned cache is the window minus its oldest column. Pass zeros and
an all-masked-but-last mask at step 0.

Checked on the host against Qualcomm's own adapted decoder (vendored model_adaptation.py) on the same inputs.
Runs on the MS-02 in ~/mtk-whisper/venv-exp with PYTHONPATH=~/mtk-whisper/qcwrap. Writes
out/pair/turbo_decoder_mtk_f32.tflite.
"""
import json, os, time

import torch
import torch.nn.functional as F

MODEL_DIR = os.path.expanduser("~/mtk-whisper/models/whisper-large-v3-turbo")
OUT_DIR = os.path.expanduser("~/mtk-whisper/out/pair")
WINDOW = 200
AUDIO = 1500


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


import litert_torch as lt
from transformers import WhisperForConditionalGeneration

log("loading", MODEL_DIR)
model = WhisperForConditionalGeneration.from_pretrained(MODEL_DIR, torch_dtype=torch.float32).eval()
cfg = model.config
L, H, D = cfg.decoder_layers, cfg.decoder_attention_heads, cfg.d_model
HD = D // H
SCALE = HD ** -0.5
dec = model.model.decoder


class MtkDecoderStep(torch.nn.Module):
    def __init__(self):
        super().__init__()
        self.embed_tokens = dec.embed_tokens                      # [51866, 1280], tied with proj_out
        self.embed_positions = dec.embed_positions.weight         # [448, 1280]
        self.layers = dec.layers
        self.layer_norm = dec.layer_norm

    def attn(self, x, q_proj, out_proj, K, V, mask):
        """x [1,1,D]; K [H,1,HD,T]; V [H,1,T,HD]; mask [1,1,1,T] or None -> [1,1,D]."""
        q = (q_proj(x) * SCALE).view(1, 1, H, HD).permute(2, 0, 1, 3)        # [H,1,1,HD]
        s = torch.matmul(q, K)                                               # [H,1,1,T]
        if mask is not None:
            s = s + mask
        p = torch.softmax(s, dim=-1)
        o = torch.matmul(p, V)                                               # [H,1,1,HD]
        o = o.permute(1, 2, 0, 3).reshape(1, 1, D)
        return out_proj(o)

    def forward(self, input_ids, attention_mask,
                k_cache_self_0_in, v_cache_self_0_in, k_cache_self_1_in, v_cache_self_1_in,
                k_cache_self_2_in, v_cache_self_2_in, k_cache_self_3_in, v_cache_self_3_in,
                k_cache_cross_0, v_cache_cross_0, k_cache_cross_1, v_cache_cross_1,
                k_cache_cross_2, v_cache_cross_2, k_cache_cross_3, v_cache_cross_3, position_ids):
        ks = [k_cache_self_0_in, k_cache_self_1_in, k_cache_self_2_in, k_cache_self_3_in]
        vs = [v_cache_self_0_in, v_cache_self_1_in, v_cache_self_2_in, v_cache_self_3_in]
        kc = [k_cache_cross_0, k_cache_cross_1, k_cache_cross_2, k_cache_cross_3]
        vc = [v_cache_cross_0, v_cache_cross_1, v_cache_cross_2, v_cache_cross_3]
        tok = torch.index_select(self.embed_tokens.weight, 0, input_ids.reshape(1))        # [1,D]
        pos = torch.index_select(self.embed_positions, 0, position_ids.reshape(1))         # [1,D]
        x = (tok + pos).view(1, 1, D)
        outs = []
        for i, layer in enumerate(self.layers):
            # self-attention with the sliding cache
            h = layer.self_attn_layer_norm(x)
            k_new = layer.self_attn.k_proj(h).view(1, 1, H, HD).permute(2, 0, 3, 1)      # [H,1,HD,1]
            v_new = layer.self_attn.v_proj(h).view(1, 1, H, HD).permute(2, 0, 1, 3)      # [H,1,1,HD]
            K = torch.cat([ks[i], k_new], dim=3)                                         # [H,1,HD,200]
            V = torch.cat([vs[i], v_new], dim=2)                                         # [H,1,200,HD]
            x = x + self.attn(h, layer.self_attn.q_proj, layer.self_attn.out_proj, K, V, attention_mask)
            outs.append(K[:, :, :, 1:])
            outs.append(V[:, :, 1:, :])
            # cross-attention against the encoder's KV
            h = layer.encoder_attn_layer_norm(x)
            x = x + self.attn(h, layer.encoder_attn.q_proj, layer.encoder_attn.out_proj, kc[i], vc[i], None)
            # FFN
            h = layer.final_layer_norm(x)
            x = x + layer.fc2(F.gelu(layer.fc1(h)))
        x = self.layer_norm(x)
        logits = F.linear(x.view(1, D), self.embed_tokens.weight)                         # [1, vocab]
        return (logits.view(1, cfg.vocab_size, 1, 1),) + tuple(outs)


step = MtkDecoderStep().eval()

# ---- inputs: real cross KV from the plain encoder on the probe's random mel, step 0
enc = model.model.encoder
x_mel = torch.randn(1, cfg.num_mel_bins, 3000, generator=torch.Generator().manual_seed(7)) * 0.5
with torch.no_grad():
    hs = enc(input_features=x_mel).last_hidden_state
    dec_in = {"input_ids": torch.tensor([[50258]], dtype=torch.int32)}
    mask = torch.full((1, 1, 1, WINDOW), -1e4); mask[..., -1] = 0.0
    dec_in["attention_mask"] = mask
    for i in range(L):
        dec_in[f"k_cache_self_{i}_in"] = torch.zeros(H, 1, HD, WINDOW - 1)
        dec_in[f"v_cache_self_{i}_in"] = torch.zeros(H, 1, WINDOW - 1, HD)
    for i in range(L):
        layer = dec.layers[i]
        dec_in[f"k_cache_cross_{i}"] = layer.encoder_attn.k_proj(hs).view(1, AUDIO, H, HD).permute(2, 0, 3, 1).contiguous()
        dec_in[f"v_cache_cross_{i}"] = layer.encoder_attn.v_proj(hs).view(1, AUDIO, H, HD).permute(2, 0, 1, 3).contiguous()
    dec_in["position_ids"] = torch.zeros(1, dtype=torch.int32)

    mine = step(**dec_in)
    top = torch.topk(mine[0].flatten(), 5)
    log("my decoder step-0 top tokens", top.indices.tolist(), [round(v, 3) for v in top.values.tolist()])

    # ---- reference 1: HF's own decoder with use_cache (step 0 + step 1) — exact semantics
    ref0 = model(input_features=x_mel, decoder_input_ids=torch.tensor([[50258]]), use_cache=True)
    ref_logits0 = ref0.logits[0, -1]
    log("HF step-0 top tokens", torch.topk(ref_logits0, 5).indices.tolist())
    d0 = (mine[0].flatten() - ref_logits0).abs()
    log(f"step-0 logits vs HF: max|diff| {d0.max():.5f} mean|diff| {d0.mean():.6f}")
    # step 1: feed the returned caches back in, position 1, mask opens the last two columns
    dec_in1 = dict(dec_in)
    nxt = int(torch.argmax(ref_logits0))
    dec_in1["input_ids"] = torch.tensor([[nxt]], dtype=torch.int32)
    dec_in1["position_ids"] = torch.ones(1, dtype=torch.int32)
    m1 = torch.full((1, 1, 1, WINDOW), -1e4); m1[..., -2:] = 0.0
    dec_in1["attention_mask"] = m1
    for i in range(L):
        dec_in1[f"k_cache_self_{i}_in"] = mine[1 + 2 * i]
        dec_in1[f"v_cache_self_{i}_in"] = mine[2 + 2 * i]
    mine1 = step(**dec_in1)
    ref1 = model(input_features=x_mel, decoder_input_ids=torch.tensor([[50258, nxt]]), use_cache=True)
    d1 = (mine1[0].flatten() - ref1.logits[0, -1]).abs()
    log(f"step-1 logits vs HF: max|diff| {d1.max():.5f} mean|diff| {d1.mean():.6f}; top {torch.topk(mine1[0].flatten(), 3).indices.tolist()} vs {torch.topk(ref1.logits[0, -1], 3).indices.tolist()}")

json.dump({"decoder_inputs": {k: list(v.shape) for k, v in dec_in.items()},
           "decoder_outputs": ["logits"] + [f"{p}_cache_self_{i}_out" for i in range(L) for p in ("k", "v")],
           "decoder_output_shapes": [list(t.shape) for t in mine]},
          open(os.path.join(OUT_DIR, "decoder_mtk.json"), "w"), indent=1)

log("converting decoder (f32, int32 ids)")
t0 = time.time()
p = os.path.join(OUT_DIR, "turbo_decoder_mtk_f32.tflite")
lt.signature("decode", step, sample_kwargs=dec_in).convert().export(p)
log(f"decoder {time.time() - t0:.1f} s -> {p} {os.path.getsize(p):,} B")

# element types present (MediaTek: no int64 allowed)
from ai_edge_litert import schema_py_generated as s
import collections
buf = bytearray(open(p, "rb").read())
m = s.Model.GetRootAsModel(buf, 0)
sg = m.Subgraphs(0)
types = collections.Counter(sg.Tensors(i).Type() for i in range(sg.TensorsLength()))
log("tensor element types (0=f32 2=i32 4=i64 6=bool):", dict(types), "ops:", sg.OperatorsLength())
log("DONE")
