import os, time
from ai_edge_litert.aot import aot_compile as ac
from ai_edge_litert.aot.vendors.mediatek import target as mt
OUT = os.path.expanduser("~/mtk-whisper/out/pair/aot_mt6989"); os.makedirs(OUT, exist_ok=True)
src = os.path.expanduser("~/mtk-whisper/out/pair/turbo_decoder_mtk_f32.tflite")
t0 = time.time(); print(time.strftime("%H:%M:%S"), "compiling decoder mtk", flush=True)
res = ac.aot_compile(src, output_dir=OUT, target=mt.Target(mt.SocModel.MT6989), keep_going=False)
print(time.strftime("%H:%M:%S"), f"done {time.time()-t0:.1f} s failed={res.failed_backends}", flush=True)
from ai_edge_litert import schema_py_generated as s
import collections
p = os.path.join(OUT, "turbo_decoder_mtk_f32_MediaTek_MT6989_apply_plugin.tflite")
buf = bytearray(open(p, "rb").read()); m = s.Model.GetRootAsModel(buf, 0); sg = m.Subgraphs(0)
codes = [(m.OperatorCodes(i).BuiltinCode(), m.OperatorCodes(i).CustomCode()) for i in range(m.OperatorCodesLength())]
cnt = collections.Counter((codes[sg.Operators(j).OpcodeIndex()][1] or b"builtin_%d" % codes[sg.Operators(j).OpcodeIndex()][0]).decode() for j in range(sg.OperatorsLength()))
print("compiled decoder ops:", dict(cnt), "bytes", os.path.getsize(p))
sd = m.SignatureDefs(0)
print("SIGOUTS=" + ",".join(sd.Outputs(i).Name().decode() for i in range(sd.OutputsLength())))
print("SIGINS=" + ",".join(sd.Inputs(i).Name().decode() for i in range(sd.InputsLength())))
print("DONE")
