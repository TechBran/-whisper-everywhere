"""AOT-compile the f32 turbo encoder for the Tab S10+'s MT6989 with LiteRT's MediaTek backend
(default compiler options first; the #6462 option-forwarding question is answered by what this emits).
Run on the MS-02 inside ~/mtk-whisper/venv:  python aot_mt6989.py [extra json config]
"""
import json, os, sys, time

from ai_edge_litert.aot import aot_compile as ac
from ai_edge_litert.aot.vendors.mediatek import target as mt

SRC = os.path.expanduser("~/mtk-whisper/out/turbo_encoder_f32.tflite")
OUT = os.path.expanduser("~/mtk-whisper/out/aot_mt6989")
os.makedirs(OUT, exist_ok=True)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


log("aot_compile", SRC, "->", OUT, "target MT6989 (np", mt.map_soc_to_np_version(mt.SocModel.MT6989), ")")
t0 = time.time()
target = mt.Target(mt.SocModel.MT6989)
res = ac.aot_compile(SRC, output_dir=OUT, target=target, keep_going=False)
log(f"done in {time.time() - t0:.1f} s")
log("result:", res)
try:
    for m in res.models_with_backend if hasattr(res, "models_with_backend") else []:
        log("  model:", m)
except Exception as e:
    log("  (result introspection failed)", repr(e))
for root, _, files in os.walk(OUT):
    for f in files:
        p = os.path.join(root, f)
        log(f"  {os.path.getsize(p):>14,d}  {p}")
log("DONE")
