import os, time
from ai_edge_litert.aot import aot_compile as ac
from ai_edge_litert.aot.vendors.mediatek import target as mt
OUT = os.path.expanduser("~/mtk-whisper/out/pair/aot_mt6989"); os.makedirs(OUT, exist_ok=True)
src = os.path.expanduser("~/mtk-whisper/out/pair/turbo_encoder_qcio_f32.tflite")
t0 = time.time(); print(time.strftime("%H:%M:%S"), "compiling encoder qcio", flush=True)
res = ac.aot_compile(src, output_dir=OUT, target=mt.Target(mt.SocModel.MT6989), keep_going=False)
print(time.strftime("%H:%M:%S"), f"done {time.time()-t0:.1f} s failed={res.failed_backends}", flush=True)
for f in sorted(os.listdir(OUT)):
    p = os.path.join(OUT, f); print(f"  {os.path.getsize(p):>14,d}  {f}")
print("DONE")
