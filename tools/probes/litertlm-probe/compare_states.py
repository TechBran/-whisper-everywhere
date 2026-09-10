#!/usr/bin/env python3
"""Compare encoder states the tablet dumped (`--ez dumpstates true`, pulled to probe-logs/<tag>.states.bin)
with the PC interpreter's CPU encode of the same mel: element-wise error, and whether a layout permutation
(transpose, channel blocks) rather than precision explains the difference. Aggregate statistics
(mean/min/max) are permutation-invariant, which is exactly why the E5 encoder fingerprints could not see this.

  python compare_states.py <model.tflite> <mel.bin> <states.bin>
"""
import sys

import numpy as np
from ai_edge_litert.interpreter import Interpreter


def main():
    model, mel_path, states_path = sys.argv[1:4]
    it = Interpreter(model_path=model, num_threads=8)
    enc = it.get_signature_runner("encode")
    d = enc.get_input_details()["args_0"]
    n_mels = int(d["shape"][1])
    mel = np.fromfile(mel_path, dtype="<f4").reshape(1, n_mels, 3000)
    ref = enc(args_0=mel)["output_0"][0]                     # [1500, d]
    dev = np.fromfile(states_path, dtype="<f4")
    T, D = ref.shape
    assert dev.size == T * D, (dev.size, T * D)
    dev = dev.reshape(T, D)
    print("ref  mean=%.5f mean_abs=%.5f min=%.4f max=%.4f" % (ref.mean(), np.abs(ref).mean(), ref.min(), ref.max()))
    print("dev  mean=%.5f mean_abs=%.5f min=%.4f max=%.4f" % (dev.mean(), np.abs(dev).mean(), dev.min(), dev.max()))
    err = np.abs(dev - ref)
    print("as-is [T,D]: max|err|=%.4f mean|err|=%.5f rel=%.4f corr=%.5f" % (
        err.max(), err.mean(), err.mean() / np.abs(ref).mean(), np.corrcoef(dev.ravel(), ref.ravel())[0, 1]))
    # candidate permutations
    cands = {
        "transpose [D,T]": dev.ravel().reshape(D, T).T,
    }
    for blk in (4, 8, 16, 32):
        if D % blk == 0:
            # channel-blocked: [D/blk, T, blk] -> [T, D]
            cands["chan-block %d [D/b,T,b]" % blk] = dev.ravel().reshape(D // blk, T, blk).transpose(1, 0, 2).reshape(T, D)
    for name, c in cands.items():
        e = np.abs(c - ref)
        print("%-28s max|err|=%.4f mean|err|=%.5f corr=%.5f" % (name, e.max(), e.mean(), np.corrcoef(c.ravel(), ref.ravel())[0, 1]))
    # per-row correlation for the first rows (is it a few rows or everything?)
    rows = [np.corrcoef(dev[t], ref[t])[0, 1] for t in range(0, T, T // 10)]
    print("row corr (every %d-th frame): %s" % (T // 10, " ".join("%.3f" % r for r in rows)))
    # per-channel error
    ch = err.mean(axis=0)
    worst = np.argsort(-ch)[:8]
    print("worst channels: %s" % " ".join("%d(%.3f)" % (c, ch[c]) for c in worst))


if __name__ == "__main__":
    main()
