# NPU Tier Decision Brief — Qualcomm Whisper on Hexagon
**Synthesis of 5 research agents + independent live verification, 2026-08-27**

---

## 0. What I re-verified myself (and what changed)

Five agents disagreed on load-bearing numbers. I re-fetched the primary sources today and ran HTTP HEAD / zip-central-directory reads against the live S3 assets. Resolutions:

| Contested point | Verdict |
|---|---|
| Does the w8a16 turbo have published benchmarks? | **Yes.** `baseline` was wrong. Full 8 Gen 3 / 8 Elite / 8 Elite Gen 5 / 7 Gen 4 tables are live on the card. The "not supported on any Mobile chipset" banner on aihub.qualcomm.com is a **UI bug** — the same page then renders a "Supported Mobile Chipsets" list. It appears on *both* Whisper models. Not a deprecation signal. |
| Is the asset alive? | **Yes.** v0.61.0 zips were re-uploaded **2026-08-25 — two days ago** (verified `Last-Modified` header). |
| Download sizes (3 agents guessed, 1 measured, 1 got a 403) | **Measured today.** Turbo w8a16 8 Gen 3 zip = **859,783,378 B (820.0 MiB)**, unpacked 1,071,685,632 B (**1.02 GiB**). Small w8a16 = **293,591,763 B (280.0 MiB)**, unpacked 358,287,999 B (**341.7 MiB**: encoder.bin 132,927,488 + decoder.bin 225,316,864). `model` agent was exactly right. |
| Float-vs-quantized encoder inversion | **Confirmed, and it is systematic across both models.** Turbo: float 471.256 ms vs w8a16 1421.004 ms = **3.02x**. Small: float 98.06 ms vs w8a16 270.196 ms = **2.76x** (same runtime, same chip, same day). Two independent models showing the same ratio makes "one stale row" unlikely — this is the w8a16 export path, not a typo. |
| Maven versions (three agents gave three answers) | **Authoritative, from repo1 maven-metadata.xml:** `com.microsoft.onnxruntime:onnxruntime-android-qnn` **1.29.0**; `onnxruntime-android` **1.29.0**; `com.qualcomm.qti:qnn-runtime` **2.49.0**; `com.qualcomm.qti:onnxruntime-android-qnn` **2.5.0**. Assets were built with QAIRT 2.45.0.260326154327 / ORT 1.27.1, so 2.49.0 ≥ 2.45.0 satisfies the forward-compat rule. |
| "Supported chipset" ≠ shippable | **Confirmed in one place:** Whisper-Small-Quantized's README publishes 8 Gen 1 perf rows, but its `release_assets.json` has **no 8gen1 key**. Same 12 keys as turbo; 8 Gen 1 / 8 Gen 2 / 888 have **no prebuilt binary**. |
| Argmax archived | **Confirmed via GitHub API:** `archived: true`, `pushed_at 2026-01-24T05:12:18Z`, MIT, 214 stars. |
| `disable_cpu_ep_fallback` | **Confirmed** in ORT docs: session config entry `"session.disable_cpu_ep_fallback" = "1"`. |
| **NEW — nobody found this** | Google Play **device targeting supports `<config:system-on-chip manufacturer=… model=…/>` selectors**, applies to Play Asset Delivery asset packs and conditional module delivery, and requires **API 31+** — the exact same floor as `Build.SOC_MODEL`. This changes the distribution and licensing answer (§6). Feature is in **beta**. |

**One framing correction the owner needs.** Three agents (`model`, `runtime`, `field`) concluded turbo is a *latency regression*, comparing 1.34 s against the owner's phrase "almost instantaneous." The `gating` agent has the actual measured number: **multi on Fold6 is F ≈ 2.3 s** (3.6.0 device session, 2026-08-20). Turbo-on-NPU at ~1.37–1.57 s is therefore a **1.5x improvement, not a regression**. The case against turbo is real, but it is *opportunity cost*, not regression — and the brief should not be built on a wrong premise.

---

## 1. Recommendation: **GO-WITH-CONDITIONS — on a different model**

**NO-GO on Whisper-Large-V3-Turbo-Quantized as the entry tier.
GO on a gated ORT+QNN PoC targeting Whisper-Small-Quantized, with turbo kept as a phase-2 accuracy option behind measured evidence.**

*Confidence: high on the retarget; medium on the absolute go, which is gated on the G1 spike in §7.*

**Three strongest reasons:**

**1. Same runtime, same gate, same engineering — 4x better payoff for 1/3 the bytes.** Whisper-Small-Quantized is w8a16 `openai/whisper-small` — literally the checkpoint behind today's `multi` tier — with a **270.8 ms** encoder vs turbo's **1340.6 ms** on 8 Gen 3, at 280 MB vs 820 MB, behind an *identical* 12-key asset manifest. Every rand of QNN plumbing, gating, canary and licensing work is shared. Against the measured 2.3 s baseline: turbo buys **~1.5x**, small buys **~4–7x**. ([turbo card](https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized), [small card](https://huggingface.co/qualcomm/Whisper-Small-Quantized))

**2. Every number that would justify turbo is unmeasured or self-contradictory.** There is **no published WER for w8a16 at all** — not for turbo, not for small, not on any card, not in `perf.yaml`. And Qualcomm's own cards say the *float* encoder is 2.8–3.0x **faster** than the quantized one, in both models. So the two quantities that decide turbo's value — its accuracy gain and its actual speed — are both currently unknown. Committing 820 MB/SoC and a permanent second inference stack to that is spending real money on two blank cells.

**3. The fixed 30 s window converts per-segment latency into a flat floor, and the floor size is the entire decision.** Both models hard-compile `input_features [1,128,3000]` / `[1,80,3000]` — 3000 mel frames = 30.0 s, no dynamic axis ([hf_whisper/model.py](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/_shared/hf_whisper/model.py)). A 0.5 s "yes" costs the same encoder pass as a 15 s sentence. Turbo's floor is 1.34 s; small's is 0.27 s; today's CPU floor (audio_ctx=512) sits somewhere under 2.3 s. Turbo asks you to trade a lever you own for a floor barely below where you already are.

---

## 2. Recommended runtime path

**ONNX Runtime + QNN Execution Provider, consuming AI Hub `precompiled_qnn_onnx` (EPContext) assets.** *Confidence: high.*

- **Maven-only, no QPM, no SDK install:** `com.microsoft.onnxruntime:onnxruntime-android` 1.29.0 + `com.qualcomm.qti:onnxruntime-android-qnn` 2.5.0 + `com.qualcomm.qti:qnn-runtime` 2.49.0 (all verified live on repo1 today).
- **Precompiled means no on-device graph compile**, which drops `libQnnHtpPrepare.so` (**87.7 MB**) from the APK and removes the JIT first-run penalty Argmax cited as a reason for abandoning their Qualcomm path ([argmaxinc.com](https://www.argmaxinc.com/blog/argmax-pro-sdk-for-android)).
- **It is the only path with living first-party Qualcomm Whisper reference code** (`apps/whisper_windows_py` — ORT-based, Windows/Python, but real).
- **Mandatory session config:** `session.disable_cpu_ep_fallback = "1"`. Without it ORT silently partitions unsupported ops to CPU and you ship a "NPU tier" that is slower than what you have — the software twin of the 3.6.0 GPU trap. Plus `htp_performance_mode=burst`, `htp_graph_finalization_optimization_mode=3`, `offload_graph_io_quantization=1` ([ORT QNN EP docs](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)).

**Rejected:** LiteRT/TFLite — AI Hub publishes **no `.tflite` for Whisper** in any precision, and the Android TFLite Whisper reference (WhisperKitAndroid) is archived. Raw QNN context binary — buys nothing over EPContext, costs you the whole ORT layer. Genie — LLM causal-decoder framework, not encoder-decoder ASR. **Voice AI ASR SDK** — the one path that would hand you mel + tokenizer + decode loop instead of you writing them; QPM-gated, IoT/Dragonwing-oriented, redistribution rights for a paid Play app unverified. Worth **one email**, not a plan.

**APK cost:** ~55.4 MB native libs for HTP v75 only; ~89.3 MB to also cover v79 + v81. Plus `android:extractNativeLibs="true"` app-wide for the DSP skel loader — *or* ship skels inside the asset pack and point `ADSP_LIBRARY_PATH` there, avoiding the global manifest flip. **(That workaround is proposed, not validated — put it in the spike.)**

---

## 3. Gating design

*Confidence: high on rungs, medium on fleet estimate.*

**R−1 · Distribution gate (new).** Play Asset Delivery on-demand asset pack + `device_targeting_configuration.xml` with `<config:system-on-chip manufacturer="QTI" model="SM8650"/>` groups. Play does the SoC match — **no CDN asset matrix, no client-side variant selection, and the binary never exists as a standalone file on your server** (see §6). Beta; API 31+ only.

**R0 · `Build.VERSION.SDK_INT >= 31`** — `SOC_MODEL`/`SOC_MANUFACTURER` do not exist below 31. `SOC_MANUFACTURER` ∈ {QTI, Qualcomm}; **`UNKNOWN` denies**.

**R1 · SOC_MODEL → HTP arch allowlist, gate on arch not marketing name.** SM8650 / SM8650-AC → v75 (Fold6; `metadata.json` lists aliases `[qualcomm-snapdragon-8gen3, sm8650]`, reference device Galaxy S24 — a direct hit). SM8750 → v79. SM8850 → v81 **[inferred, verify before shipping]**. **v75+ only**, plus 7 Gen 4. Deny by default.

**R2 · Asset present and arch-matched.** Context binaries are not portable across HTP arch or QAIRT version (`QNN_COMMON_ERROR_INCOMPATIBLE_BINARIES`); there is no on-device recompile.

**R3 · RAM gate.** `totalMem` + `!isLowRamDevice()`. **Do not use AI Hub's "peak memory" column** — I verified the identical Whisper-Small-Quantized encoder reports **63–75 MB** under PRECOMPILED_QNN_ONNX and **1–8 MB** under QNN_CONTEXT_BINARY, a 10x spread for the same weights at the same latency. Budget from bytes instead: small ≈ 342 MB mapped, turbo ≈ 1.02 GB mapped. Tear down the whisper.cpp context *before* the QNN session loads; never co-resident.

**R4 · Guarded probe with the GpuPolicy crash sentinel, three phases** — (1) backend init / context-binary deserialize, (2) first encode, (3) first decode. Commit-to-disk before each risky call, clear in `finally`, stale flag on next launch = permanent latch keyed on **(versionCode, model, htpArch, socModel)**. Mirror `onCanaryUnavailable` verbatim (the C5b fix).

**R5 · Canary WITH A SPEED TERM.** This is the lesson you already paid for: `GpuCanaryPolicy` is correctness-only, and on 2026-08-20 the Fold6 returned `GPU-VERDICT: BAN reason=slower` — **wer 0.000, 9x slower**. A correctness-only canary would have latched ALLOW. Reuse `assets/canary_digits.wav` and `canaryPasses` for correctness; add (a) a **warm wall-clock term that must beat multi's measured F on the same clip by a real margin** and (b) a **separate cold-load budget** (context-binary deserialize can take seconds — cap it, hide behind prewarm). Run twice to split cold from steady state. Bonus: because the encoder window is fixed, **one canary latency number predicts every segment length** — unlike CPU.

**R6 · Session-scoped soft demotion for thermals** (never the permanent latch). All AI Hub figures are single cold runs; the S24 Ultra — same SM8650 — degrades ~15% over 20 iterations into a governor plateau ([arXiv 2603.23640](https://arxiv.org/html/2603.23640v2)).

**Fleet coverage.** Prebuilt assets exist for only **4 phone SoCs** (8 Gen 3, 8 Elite, 8 Elite Gen 5, 7 Gen 4) — **no 8 Gen 1, no 8 Gen 2, no 888**. Estimate ≈ **4–6% of global Android active base** (inferred from Counterpoint's H1 2026 premium-share chain), lifted maybe 2–4x by the $4.99 paid skew, and **anti-correlated with your stated base** (mostly outside the US, multilingual, mid-range MediaTek / Snapdragon 6-7). Snapdragon-only, permanently: NNAPI was deprecated in Android 15 and there is no third-party cross-vendor NPU API. **Do not spend a day on market inference — pull the Play Console device catalog, which can break installs down by SoC. That single number beats every estimate in this brief.**

---

## 4. Expected user-visible win

Cost model = fixed encoder + per-token decoder (Qualcomm, verbatim: *"Time to the first token is the encoder's latency, while time to each additional token is decoder's latency"*). Tokens at ~150 wpm × 1.35 tok/word ≈ 3.4 tok/s. Baseline = **Fold6 measured F ≈ 2.3 s**.

| Segment | multi CPU today | **Small w8a16 NPU** | Turbo w8a16 NPU | *(Small float NPU)* |
|---|---|---|---|---|
| 0.5 s (~2 tok) | ~2.3 s | **0.28 s** | 1.35 s | *0.12 s* |
| 2 s (~7 tok) | ~2.3 s | **0.31 s** | 1.37 s | *0.17 s* |
| 5 s (~17 tok) | ~2.3 s | **0.37 s** | 1.42 s | *0.27 s* |
| 15 s (~51 tok) | ~2.3 s | **0.58 s** | 1.57 s | *0.60 s* |
| **vs baseline** | 1.0x | **4.0–8.2x** | **1.5–1.7x** | *3.8–19x* |

*(Float small crosses over with quantized small at ~15 s — its faster encoder is eaten by its slower decoder. It only wins in your short-segment regime, and costs 924 MB. Worth measuring in the same spike since the harness is identical.)*

**Accuracy.** Turbo's case is real and it is the *only* case for turbo: FLEURS small → large-v3-turbo shows **~50% median WER reduction** (ES 6.5→3.3, FR 14.3→6.7, DE 12.8→6.0, HI 42.4→22.3, AR-EG 32.7→16.1, JA CER 16.4→5.8, ZH CER 19.5→8.0). *Confidence: low-medium* — third party, 50 utterances/language, clean read speech, not your noisy dictation reality ([vocova](https://vocova.app/blog/ai-transcription-accuracy-benchmark-2026)). **Hard carve-outs if turbo ever ships: Cantonese CER 10.5% → 43.3% (4x worse than large-v3), Thai degraded, and turbo was fine-tuned without translation data — no speech-to-English-translation claims.** Small-quantized's accuracy story is the opposite and better: it *is* your current model, so the only delta is the unmeasured w8a16 quantization step.

**Battery.** Directionally strong, quantitatively soft. NPUsper (Monsoon, Galaxy S25) measures an optimized NPU path at **0.46 W / 0.92 J per inference vs 2.94–4.05 W / 6.13–13.95 J** for baselines, and NPU vs GPU 1.14 W vs 1.99 W ([arXiv 2607.01108](https://arxiv.org/html/2607.01108v1)). **Caveat that matters: their savings come from eliminating 30 s padding — the exact design AI Hub ships.** Expect a fraction of the headline. The reliable win is second-order and real for a continuous-dictation app: inference leaves the CPU, freeing it for the 31.25 Hz audio pipeline and Silero VAD that currently contend with it.

---

## 5. Risk register

| # | Risk | Sev | Mitigation |
|---|---|---|---|
| **1** | **Wrong target locked in.** Turbo costs 3x the bytes of small for 1/4 the speed win, with both of its justifying numbers unmeasured. | **High** | Retarget the PoC to Whisper-Small-Quantized. Turbo becomes a phase-2 accuracy option, admitted only on measured WER from your own dictation corpus. |
| **2** | **Published numbers are untrustworthy.** float 2.8–3.0x faster than w8a16 in *both* models; zero WER anywhere; AI Hub UI contradicts its own card. | **High** | G1 spike measures small-w8a16, small-float and turbo-w8a16 **in one Fold6 session**. Plan nothing around a Qualcomm row until that runs. |
| **3** | **Permanent second inference stack.** whisper.cpp will never reach Hexagon — the merged `ggml-hexagon` backend has no convolution ops and structurally cannot run a Whisper encoder ([PR #16547](https://github.com/ggml-org/llama.cpp/pull/16547)). You hand-write mel + 51866-BPE tokenizer + decode loop, losing whisper.cpp's temperature fallback, suppress-tokens, compression-ratio and logprob failure detection. | **High** | Reuse existing native mel (you already compute 128-bin for large-v3; small needs 80-bin) and the 51866 vocab from the retired turbo GGML tier. Greedy-only v1 with hard CPU fallback on any anomaly. Keep the NPU path permanently behind the canary. Ask Qualcomm whether Voice AI SDK supplies this glue on Android phones. |
| **4** | **Distribution shape vs the "standalone basis" clause**, plus per-SoC asset matrix and separate QNN runtime SDK terms. | **Med-High** | Deliver via **Play Asset Delivery + SoC device targeting** — Play distributes it as part of your app, which is squarely inside the grant. Get the license question answered in writing (§6). Note 4 × 820 MB turbo variants ≈ 3.3 GB of bundle, uncomfortably near AAB ceilings; 4 × 280 MB small ≈ 1.1 GB is comfortable. |
| **5** | **Coverage anti-correlated with your users.** ~4–6% globally, no 8 Gen 1/2/888, Snapdragon-only forever. | **Med** | Check Play Console device catalog *before* spending. Frame as a device-specific speedup ("Faster on this device"), never a headline tier. Do not let it delay anything that helps the other 95%. |

*Honorable mentions:* sustained thermal throttling; `extractNativeLibs=true` app-wide; native crash inside QNN backend init (ORT #22288) — hence the three-phase sentinel; retail One UI possibly not exposing HTP to third-party apps with bundled skels.

---

## 6. Licensing verdict — **NOT a veto. Three conditions.**

*Confidence: medium-high. This was flagged as potentially fatal; it isn't. But it does shape distribution.*

Three layers, three licenses:

1. **Sample/harness code** — `qualcomm/ai-hub-apps` is **BSD-3-Clause** (verified LICENSE file, "Copyright 2025 Qualcomm Technologies, Inc."). Clean for a commercial app.

2. **Model binaries** — both cards declare **Apache-2.0** (I re-verified both today), `manifest.yaml` sets `license_type: apache-2.0`. And even under the stricter **Qualcomm AI Hub Proprietary License** (text decoded from the [PDF in the same S3 bucket](https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/Qualcomm+AI+Hub+Proprietary+License.pdf)), §1(iv) grants the right to *"distribute and sublicense the Software solely in object code format and **as incorporated in Your software application**"*, and §8 explicitly contemplates *"Your posting of any software applications on one (1) or more application download websites or stores."* **Shipping inside com.whispereverywhere is permitted.** The one clause that bites: *"nothing herein grants You a license to distribute or sublicense the Software on a standalone basis"* — which is precisely what serving `encoder.bin` from your own CDN looks like. **Play Asset Delivery dissolves this**: the binary is delivered by Play as part of your app bundle, never as a standalone file you host.

3. **QNN/QAIRT runtime .so** — the genuine unknown, and the layer nobody's Apache-2.0 label covers. Strong mitigating evidence found today: these ship as **public Maven Central AARs** (`com.qualcomm.qti:qnn-runtime` 2.49.0, `com.qualcomm.qti:onnxruntime-android-qnn` 2.5.0), which is hard to read as anything other than intended app redistribution. Still read the bundled LICENSE in the AAR before shipping.

**Conditions:** (a) one email to **ai-hub-support@qti.qualcomm.com** asking which license governs the precompiled S3 context binaries, and whether on-demand delivery from a developer-operated CDN is permitted; (b) read the Maven AAR license files; (c) if either answer is soft, PAD delivery removes the question entirely. Also observe §2(a) (no reverse-engineering) and §2(d) (prohibited use cases — none of yours). Non-legal but listing-relevant: **no translation claims, Cantonese/Thai excluded**, if turbo ever ships.

---

## 7. Scope sketch — what the 4.0-scale PoC proves, in kill-switch order

Your existing ticket reads *"ORT+QNN PoC with Qualcomm AI Hub Whisper-Small-En on Fold6."* Two edits: use **Whisper-Small-Quantized (multilingual)**, not Small-En, so it matches `multi`; and **the first thing the PoC must prove is not speed** — it's that the Hexagon HTP is reachable at all from a third-party app on a retail One UI Fold6 with app-bundled skel libraries, with CPU fallback disabled. Everything else is downstream of that.

- **G0 — 1 day, zero code.** Pull the Play Console device catalog: what share of *paying* installs are SM8650/SM8750/SM8850? Send the licensing email. **If coverage is under ~10%, stop here** and spend the quarter on the other 95%.
- **G1 — 1–2 days, throwaway APK.** Load small-quantized `precompiled_qnn_onnx` via ORT+QNN EP, `disable_cpu_ep_fallback=1`, feed a padded 30 s mel of `canary_digits.wav`, time cold session-create + warm encoder + one decoder step. **Pass:** warm encoder ≤ 400 ms, cold create ≤ 8 s, no fallback exception. **In the same session, also run turbo-w8a16 and small-float** — that adjudicates the 2.8–3.0x float/quantized inversion, the single most contradictory number in this dossier, for about one extra day.
- **G2 — 3–5 days.** Full segment path: reuse native 80-bin mel + the 51866 vocab from the retired turbo GGML tier, greedy decode loop, 200-token cap. Run head-to-head against multi CPU on the same clips, same device, back to back. Capture the **per-segment cost curve at 0.5 / 2 / 8 / 15 s for both paths** — you need multi's curve, not just its 2.3 s point, to set the canary threshold honestly.
- **G3.** 10-minute continuous dictation thermal run; RAM co-residency check with the whisper.cpp context torn down first; Android LMK behaviour on 8 GB and 12 GB devices.
- **G4 — framing decision.** If small-NPU wins ≥3x on real segments at WER parity, ship it as **"Faster on this device" for multi — not a new tier above it**. Turbo is admitted only if G1 changes the speed picture *and* G2-class WER measurement on your own noisy dictation justifies 820 MB.

**Deferred research spike, not integration:** a short-context export (e.g. 128×1000 = 10 s) via `submit_compile_job` would attack the encoder floor directly — the QNN analogue of `audio_ctx=512`. It requires slicing Whisper's learned positional embeddings, which also drive the cross-attention KV shapes, then full AIMET re-quantization and per-SoC re-export (`is_aimet: true`, `requires_aot_prepare: true` — not a config flag). Highest leverage, entirely unvalidated, and only worth it after G2 proves the plumbing.

---

## 8. Open questions only a device test can answer

1. **Is the float-vs-w8a16 encoder inversion real on silicon?** Verified as systematic across both models (2.76x and 3.02x), but nobody has run either on a Fold6. This is the one number that could flip the recommendation.
2. **Does a retail One UI Fold6 expose the HTP to a third-party app** with app-bundled skels and `ADSP_LIBRARY_PATH` set from `Application.onCreate`? No positive public confirmation exists; the unresolved failures (ORT #21214 `Failed to create device 14001`) suggest this is where integration time actually goes.
3. **Real end-to-end per-commit latency** including mel, buffer copies and the 2.04 MB-in / 2.04 MB-out per-token self-KV round-trip through ORT. All published figures are AI Hub harness numbers, not app numbers.
4. **Cold session-create time** for a 342 MB (small) / 1.02 GB (turbo) context binary, and whether encoder + decoder stay co-resident or must swap per segment.
5. **Sustained behaviour over 10+ minutes** of continuous dictation — every AI Hub figure is a single cold run.
6. **Actual w8a16 WER on your noisy dictation**, top-5 shipped languages, vs multi CPU. Unpublished for every Qualcomm Whisper variant; `evaluate.py` ships in the repo, so it's your measurement to make.
7. **multi's true per-segment cost curve** at 0.5 / 2 / 8 / 15 s on the Fold6. With `audio_ctx` floored at 512, the CPU path has a floor too — the comparison is floor-vs-floor, and you currently only know one point on your own curve.
8. **Does a `for Galaxy` v79 binary load on a plain SM8750?** Determines whether you host 3 variants or 4+. Untested.

---

### One-line answer
Build the NPU path — but point it at **Whisper-Small-Quantized (280 MB, 271 ms encoder, the model you already ship)**, not Large-V3-Turbo (820 MB, 1341 ms, no published WER, and a float sibling its own vendor benchmarks say is 3x faster). Same runtime, same gate, same license, same week of work — four times the win. Spend two days on the Fold6 spike and the Play Console device mix before spending anything else.