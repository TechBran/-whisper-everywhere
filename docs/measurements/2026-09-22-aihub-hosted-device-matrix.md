# The shipped NPU binaries on Qualcomm AI Hub's hosted phones: 14 of 14 PASS

**Result.** Every census family's `npu-turbo` encoder and decoder loaded and executed on real
hosted silicon for its chip. That includes the three families no phone in hand has run (the
Galaxy S25 generation, the S26 generation, the 7 Gen 4). It also includes the **plain-bin
reference phones for both 8 Elite chips**, which stand in for the non-Samsung phones 4.13.0
admits. The Galaxy-compiled `8elite_galaxy` binary runs on a plain SM8750, and the
`8elite5_galaxy` binary runs on a plain SM8850.

**What was tested.** The exact files Play delivers: each one was hashed against
`NpuFleetCensus`'s digests before upload, and all 10 matched. They were uploaded as
`QNN_CONTEXT_BINARY` and profiled with `--max_profiler_iterations 10`, qai-hub 0.55.0, on
2026-09-22. Tool: `tools/aihub_matrix.py`. Model and job ids:
`raw/2026-09-22-aihub-hosted-device-matrix.json`. The jobs are viewable under the owner's AI Hub
account at `https://workbench.aihub.qualcomm.com/jobs/<id>/`.

**What was NOT tested.** The app. Play delivery, onboarding, capture, mel, our decode loop and the
speaker pipeline run only in the real app on a real phone. These are model-level runs.

## 1. The matrix

| family | hosted device | encoder run | encoder first load | decoder step | job ids (enc / dec) |
|---|---|---|---|---|---|
| `8elite_galaxy` | Samsung Galaxy S25 | 1,188.5 ms | 1,038.5 ms | 3.7 ms | jgjrqe6ep / j5qlv3xop |
| `8elite_galaxy` | **Snapdragon 8 Elite QRD (plain SM8750)** | 970.5 ms | 960.2 ms | 3.9 ms | jpe7yk0v5 / jglyl3dm5 |
| `8elite5_galaxy` | Samsung Galaxy S26 | 1,142.4 ms | 1,100.3 ms | 3.3 ms | jgjrqe9ep / jgzlnr6x5 |
| `8elite5_galaxy` | **Snapdragon 8 Elite Gen 5 QRD (plain SM8850)** | 763.2 ms | 935.8 ms | 3.3 ms | jpe7ykqv5 / j5wl4qkmp |
| `7gen4` | Snapdragon 7 Gen 4 QRD | 2,220.7 ms | 1,926.0 ms | 8.5 ms | j5wl4qk4p / jp1n6e9ng |
| `qcs8550` (control) | Samsung Galaxy S23 | 1,734.7 ms | 2,817.7 ms | 5.9 ms | jprlwerkp / jp2rel16g |
| `8gen3` (control) | Samsung Galaxy S24 | 1,399.6 ms | 1,889.1 ms | 4.5 ms | jgk283jvg / jglyl3j25 |

Peak memory per graph ranged from 104 to 186 MB.

## 2. What that means inside the app (projected, not measured)

The two controls are families the app already runs on the owner's phones, and they calibrate the
rest.

- **Encoder:** in-app encode p50 ÷ AI Hub encoder = **1.425** on the S23 Ultra
  (2,472 / 1,734.7) and 1.339 on the Fold6 against the S24 (1,874 / 1,399.6). The larger was used.
- **Decoder:** in-app decode per chunk ÷ AI Hub decoder step = **76.6** on the S23 Ultra
  (452 / 5.9) and 46.4 on the Fold6. The larger was used.
- **Worst chunk:** the S23 Ultra's max/p50 = **1.237**.

| phone | projected per chunk, p50 | share of the 8 s floor | projected worst |
|---|---|---|---|
| Galaxy S25 | ~1,980 ms | 25% | ~2,450 ms (31%) |
| plain 8 Elite (OnePlus 13, Xiaomi 15, …) | ~1,680 ms | 21% | ~2,080 ms (26%) |
| Galaxy S26 | ~1,880 ms | 24% | ~2,330 ms (29%) |
| plain 8 Elite Gen 5 (OnePlus 15, Xiaomi 17, …) | ~1,340 ms | 17% | ~1,660 ms (21%) |
| 7 Gen 4 phones | ~3,820 ms | 48% | ~4,720 ms (59%) |
| *check:* S23 Ultra | 2,924 ms (by construction) | 37% | 3,617 ms (45%), measured |
| *check:* Fold6 (via S24) | ~2,340 ms projected vs **~2,083 measured** | 29% vs 26% | overshoots by ~12%, i.e. conservative |

Every family clears the budget with room. **The 7 Gen 4 is the tightest**, at about half the
budget typically and about 60% worst case. It is the one family worth a real-phone session before
anyone leans on it, because a hot mid-range phone in a long session is where a projection is most
likely to be optimistic.

## 3. Caveats

- AI Hub devices are provisioned fresh and cool, and run the model alone. A phone in a pocket
  throttles and shares the chip with our capture and speaker pipeline. The calibration above
  absorbs the pipeline cost measured on two phones, not thermal behaviour over an hour.
- A QRD is Qualcomm's reference phone. Retail phones on the same chip can clock and cool
  differently, although the context binary is the same.
- Ten profiler iterations per job, so the medians are from small samples.
- The `npu` (small) tier was not run; it is hidden from the chooser on every capable device.
