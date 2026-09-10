# Turbo on the Tab S10+, the Gemma "scribe" tier, and Gemini Live — research synthesis

Date: 2026-09-09. Repo state read: local `main` = `e4e9627` ("chore(release): 4.3.3 at versionCode 87",
`app/build.gradle.kts:53-54`), 14 ahead of `origin/main`, AAB 87 built and unuploaded; production is
86 / 4.3.2 (memory note `whisper-everywhere-ship-track.md`, entries 2026-09-05 and 2026-09-09). Vendored
whisper.cpp 1.9.1 at `8772322f`. Nothing in this document was run on a device; nothing was built,
installed or adb'd. Inputs: four maps (`tg-map-{engine,mediatek,gemma,gemini}.md`), four analyses
(`tg-analysis-{tab,gemma,gemini,plan}.md`) and their four adversarial refutations, all in the session
scratchpad; the repo; the device inventory `C:/Users/bastr/.androidbuild/tab-s10-npu-inventory.txt`
(2026-09-09); the raw baseline capture `C:/Users/bastr/.androidbuild/capture-tab-s10-baseline-0904-2132.txt`
(2026-09-04); and the primary web sources cited inline, each with the page's own date or the 2026-09-09
fetch date.

Conventions: paths are relative to `C:/Users/bastr/OneDrive/Desktop/whisper Everywhere/`;
`src/whisper.cpp`, `ggml/…`, `examples/…` are under `app/src/main/cpp/whisper.cpp/`. **DOCUMENTED** = a
repo line or a primary source says it. **INFERRED** = a derivation over documented inputs, labelled where
it occurs. Where the brief, a memory note, the 3.8 spec, a map or an analysis disagreed with the code or
a primary source, the primary won and §6 records the ruling. Refuted claims were dropped; weakened claims
are carried with their caveats stated.

---

## 0. THE THREE ANSWERS IN ONE SCREEN

| | **(1) turbo on the Galaxy Tab S10+** | **(2) the Gemma audio tier** | **(3) Gemini Live transcribe** |
|---|---|---|---|
| **Verdict** | **No route reaches a turbo-class (~2 s) commit on this tablet without a project the size of the 4.0/4.1 Qualcomm tier.** CPU is arithmetically dead on the tablet's own documented datum (§1.3) and the owner already dropped CPU turbo on 2026-09-04. The one live APU route with a public, un-gated toolchain — LiteRT NeuroPilot for MT6989 — is unproven for any Whisper-shaped model on any MediaTek phone SoC, its public AOT compiler has two acknowledged, unfixed gaps (~153× slower bytecode on MT6993), INT8 Whisper encoders produced garbage on MDLA, and whether a Play app can reach this tablet's APU at all rests on two unread device files. **Research direction with three cheap kill-gates; no product code until all three pass.** | **The owner means Gemma 4 E2B/E4B under LiteRT-LM** (the model behind "Audio Scribe" in Google's AI Edge Gallery; no "Gemma Scribe" exists; the brief's Gemma 3n + MediaPipe is one generation stale). Real, Apache-2.0, 30-second-clip, FLEURS WER 0.090 — ahead of Whisper small on paper in 11 of 12 languages and **behind on English** — but **2.59 GB (13.6× `multi`, 2.4× the npu-turbo pair)**, 8–12 GB-class in RAM, no MediaTek NPU build, **no streamed-audio API on any Google surface**, three unanswered non-Adreno GPU failure reports. **As "the tier for phones that cannot run turbo": refuted.** As a 12 GB-class premium multilingual tier: defensible, unmeasured, and never word-for-word — a sentence that rewrites itself every 1.5–4 s and lands 1–3 s after the pause. The on-device shape that does stream word-for-word is a different model class (§2.7). | **Build it** — one new `GeminiRealtimeProtocol` (+ codec + tests), one flag flip, one `when`-arm, one default-body seam member — **after the cloud-"en" leak fix and after eight PC probes**; ≈ 7–8 working days (INFERRED). `gemini-3.5-transcribe-live` is GA (2026-08-26); its wire is the ElevenLabs shape the seam already carries; auth needs no seam change (Google's own Python SDK opens the Live WebSocket with an `x-goog-api-key` upgrade header); reply suppression is unnecessary and rejected; the operative cap is ~10 minutes **per connection**. Three code/field facts decide the design: a post-upgrade **close frame** — Gemini's error channel — reaches `RealtimeTransport.onClosed`, which never reconnects, so today a bad key would end the cloud half **silently, with zero retries and no toast**; **silent stalls** on this exact model are staff-acknowledged and unresolved; **GoAway is not a reliable rotation trigger**. $0.009/min paid (2.0× OpenAI, 1.4× ElevenLabs, 4.5× Soniox); free tier $0 with audio used to improve Google's products. Flipping the flag moves every existing Gemini user from batch to live by default. |
| **First step** | **E0** (minutes, owner): `adb shell cat /vendor/etc/public.libraries.txt`; `adb shell ls -Z /dev/apusys /dev/apusys_apummu /dev/apusys_sapu`; `adb shell getprop ro.soc.manufacturer ro.soc.model`. Then **E3**: a throwaway `litertlm-probe` app (its own `applicationId`) running Google's own MT6989 Gemma3-1B `.litertlm` on `Backend.NPU` — the APU go/no-go. Then **E4**: whisper-base AOT-compiled for MT6989, `encode` timed on the APU (a direction-finder: ≲ 75 ms ⇒ turbo-class; ~145 ms ⇒ `multi`-class; ≳ 240–260 ms ⇒ close). §1.4. | **No code.** A zero-code experiment on the Tab (AI Edge Gallery → Gemma 4 E2B → Audio Scribe; canary + dense-speech + 1 s/3 s clips + 15 s room tone; GPU then CPU; `dumpsys meminfo`), then the same throwaway probe app as (1) with kill lines: a 17.6 s chunk slower than 6.1 s loses to `multi`; numeral/contraction corruption on GPU ⇒ CPU-only; > ~3 GB resident ⇒ 12 GB-class only; any prose on room tone ⇒ a refusal detector before the first resolved segment. §2.6. | **T0**: the PC probes P1–P9 against `gemini-3.5-transcribe-live` with a free-tier key (no device, no build) — header-vs-`?key=` auth, bad-key surface, interim cumulative-vs-fragment, `audioStreamEnd`, resumption, the 10-minute close, bare-vs-region codes, a 10-minute stall run. **T1**: the leak fix at `FloatingBubbleService.kt:2838-2843`. §3.5. |
| **Owner need** | Minutes of adb (E0); an **Ubuntu 22.04 host + Bazel 7.4.1** for the AOT flow (WSL2 is undocumented — INFERRED); `pip install ai-edge-litert ai-edge-litert-sdk-mediatek` (Apache-2.0, no NDA, no MediaTek account; **NeuroPilot SDK proper is NOT needed**); one Tab session. The probe app needs no Play-copy uninstall (own `applicationId`); the owner already approved one on the Tab for the in-app bench (memory note, 2026-09-04). AGP is already 8.13.2 (`build.gradle.kts:3`), above PODAI's 8.10 floor. §1.6. | **Six rulings before code** (§2.8): what the tier is for; whether word-for-word streaming is a requirement (if yes, Gemma is out); auto-language on a Gemma tier; hosting a 2.6 GB download; strip ownership for local partials; whether Gemma is offered beside turbo. Plus ~1 h of Tab time for §2.6. | A Gemini key from AI Studio (free tier works; restrict it); **two rulings**: the live default for existing Gemini users (§3.3.8) and the 88 name (§4.3); one device sheet from the internal track (§3.6, ~1 h); **87 ships first** (§4.2). |

**Two facts that cut across all three.**

1. **The only on-device word-for-word shape costs zero new native payload.** The sherpa-onnx AAR the app
   ships for TTS (`app/build.gradle.kts:167-172`, `:809`) carries a full streaming recognizer —
   `OnlineRecognizer`, `OnlineStream`, `OnlineTransducerModelConfig`, `Vad`, `SpokenLanguageIdentification*`
   in `classes.jar`, with the JNI halves present in the shipped `libsherpa-onnx-jni.so` (§2.7). It is
   per-language (en / zh / zh-en / ko / bn …), not "one model, 100 languages". Recorded for an owner
   ruling; not scheduled by this document.
2. **The streaming contract is shared plumbing only for local tiers.** The bubble's strip is replace-only
   and live-only (`FloatingBubbleService.kt:277`, `:2941`, `:2961`); the local seam already speaks
   cumulative (`TranscriptionEngine.kt:228-244`). Direction (3) owns the strip as a live session and
   direction (1)'s accelerator arm emits no deltas, so neither changes it; direction (2) and the 4.4
   fast-partials shape both need `deltaOwnsPreviewStrip` to admit a local session plus a ruling on the
   queue-depth label it displaces (§4.5).

---

## 1. DIRECTION 1 — large-v3-turbo on the Galaxy Tab S10+ (MT6989)

### 1.1 Device ground truth (DOCUMENTED — the owner's adb read, 2026-09-09, overrides every web claim)

| Fact | Value | Where |
|---|---|---|
| Model / SoC | Galaxy Tab S10+ SM-X828U; MediaTek MT6989 = Dimensity 9300 (the prime clock read from the device, X4 @ 3.4 GHz, matches MediaTek's **9300+** sheet — https://www.mediatek.com/products/smartphones/mediatek-dimensity-9300-plus, fetched 2026-09-09; both are MT6989 and every gate, compiler table and projection keys on `MT6989`) | brief; inventory header |
| CPU | 1×X4 3.4 GHz + 3×X4 2.85 + 4×A720 2.0, **no little cores** | brief |
| RAM / GPU / OS | 12 GB; Mali-G720 Immortalis MC12 (ggml OpenCL drops it as unsupported — memory note 2026-09-04); Android 16 / API 36, `first_api_level` 34 | brief; inventory |
| MediaTek Neuron stack in `/vendor/lib64` | `libneuron_runtime.so` → `mt6989/` (v7), `libneuron_adapter_mgvi.so` → `mt6989/`, `libneuron_graph_delegate.mtk.so`, `libneuron_platform.so`, `libneuron_wrapper.so`, `libtflite_mtk.so`, `libnir_neon_driver*.so`, `libmvpuop*_mtk_nn.so`, APUWare AIDL servers, `vendor.mediatek.hardware.neuropilot.agent-V1-ndk.so`, `…apuware.apusys-V3-ndk.so` | inventory `:6-30` |
| NNAPI | HAL shim `init.svc.neuralnetworks_hal_service_shim_mtk = running`; feature level 7; `debug.mtk_tflite.target_nnapi = 29` | inventory `:32-36` |
| APU nodes | `/dev/apuext`, `/dev/apusys`, `/dev/apusys_apummu`, `/dev/apusys_sapu` | inventory |
| **Absent from the inventory, and load-bearing** | the contents of `/vendor/etc/public.libraries.txt`; the SELinux labels on `/dev/apusys*`; `ro.soc.manufacturer` / `ro.soc.model`; any `libneuronusdk_adapter*.so` | E0 reads them (§1.4) |
| Measured, Play build 86, CPU `small-q5_1` | a 17.6 s chunk transcribed in **≈ 6.05 s service time** — the capture shows `VAD: 281088 -> 281088 samples (1 segments)` at 21:30:00.029 (**zero VAD trim**), the recorder stop at 21:30:00.916, and the next chunk's `VAD:` line at 21:30:06.081, queued behind the 17.6 s transcribe on the single executor (capture `:44-47`); no `auto-detected language` line anywhere in the log, so **one encoder pass** (the JNI forwards every ggml log level, `whisper_jni.cpp:31-36`) | capture; brief's "≤ 6.1 s" |
| Qualcomm QNN path | can **never** run here (no Hexagon); `NpuGate.SUPPORTED_SOC_MANUFACTURERS = setOf("QTI", "Qualcomm")` refuses it before any dlopen (`NpuGate.kt:54`, `:82`) | code |

### 1.2 The route table, with verdicts

Columns: (a) can a Play app use it; (b) has anyone run a Whisper-class encoder on it; (c) per-commit cost
basis; (d) integration cost in this app; (e) risk. "Demonstrated" means a published result with a URL.
No row says "should work".

| # | Route | (a) Play app | (b) Whisper demonstrated | (c) Cost basis | (d) Integration here | Verdict |
|---|---|---|---|---|---|---|
| **R1** | **LiteRT NeuroPilot Accelerator, AOT-compiled for MT6989** (`CompiledModel` + `Accelerator.NPU`) | Mostly yes. Google names "Dimensity 9300 (MT6989)"; dev host Ubuntu 22.04 + Bazel 7.4.1, SDK 34, NDK 28 (https://developers.google.com/edge/litert/next/mediatek, updated 2026-05-28); LiteRT's own table spells the row `Mediatek,MT6989,v8,"v8, v9"` (https://raw.githubusercontent.com/google-ai-edge/LiteRT/main/litert/vendors/mediatek/supported_soc.csv, 2026-09-09); NPU needs API 31+, runtime libs ship as `litert_npu_runtime_libraries.zip` / `…_jit.zip` with a `mediatek_runtime/` dir, models via Play AI Packs, runtimes via Feature Delivery (https://developers.google.com/edge/litert/next/npu, updated 2026-08-26); compiler SDK `ai-edge-litert-sdk-mediatek` is Apache-2.0 on PyPI (https://libraries.io/pypi/ai-edge-litert-sdk-mediatek, per map, 2026-09-09). **The open half of (a):** LiteRT's MediaTek adapter dlopens, in order, `libneuron_adapter_mgvi.so` → one of `libneuronusdk_adapter.{10,9,}.mtk.so` (chosen by a magic-number threshold) → `libneuronusdk_adapter.so` → `libneuron_adapter.so` → `{shared_library_dir}/libneuron_adapter.so`, first success wins (https://raw.githubusercontent.com/google-ai-edge/LiteRT/main/litert/vendors/mediatek/neuron_adapter_api.cc, 2026-09-09); the dispatcher "does not statically embed the NeuroPilot SDK" (…/dispatch/MediaTek_Neuro_Dispatcher.md). The first name on that list is exactly what this tablet has (`inventory :14`), and AOSP lets an app load a `/vendor` library only if `/vendor/etc/public.libraries.txt` lists it, with a mandatory `<uses-native-library>` for targetSdk ≥ 31 (https://source.android.com/docs/core/permissions/namespaces_libraries, 2026-09-09) — the rule this app already pays three times (`AndroidManifest.xml:66-82`). A bundled adapter may instead reach the APU through the vendor AIDL HALs on the device (`inventory :24-30`), whose `untrusted_app` sepolicy is a third unread gate. **Decided by E0.** | **No.** Google's MT6989 catalogue is Gemma3-1B 4-bit only, 985 MB (https://developers.google.com/edge/litert/next/litert_lm_npu, updated 2026-09-02); LiteRT v2.2.0's MediaTek note is "channelwise quantization support for classical vision models, such as resnet18 and mobilenet" (https://github.com/google-ai-edge/LiteRT/releases/tag/v2.2.0); every published MediaTek-APU Whisper attempt is on Genio IoT boards via NeuroPilot proper, **encoder-only**, INT8 → garbage, fp32 + `--relax-fp32` → correct (https://genio-community.mediatek.com/t/garbage-transcription-when-using-whisper-encoder-converted-via-neuropilot-sdk-dla-with-pytorch-decoder/1461, 2026-03-31→04-28); MediaTek staff: "Whisper is currently not yet supported" on Genio (https://genio-community.mediatek.com/t/about-openais-whisper-model/2361, Dec 2025). **No latency number for any ASR model on any MediaTek APU exists anywhere reachable** (two searches, ProventusNova's 2026-08-02 post gives no number). | None on MT6989. The only turbo-on-NPU number in the project is Hexagon's: encode 1,778.9 ms + 44.4 ms + 10.08 ms/token = F 1.89 s on the Fold6 (`docs/superpowers/plans/2026-09-02-vad-hangover-retune.md:65-69`). **Headline risk:** the public AOT path emitted `--relax-fp32` only where Google's artefact carries `--opt 3 --num-mdla 4 --l1-size-kb 7168 --mdla-mlo` + more, giving ~2,900 vs ~19 ms/token (**~153×**) on MT6993 and `NeuronModel_restoreFromCompiledNetwork - Failed to load compiled network` with a silent JIT fallback — opened 2026-03-18, **acknowledged the same day by the LiteRT side as two toolchain gaps** (options not forwarded to the compiler driver; a parser that silently falls back to `--relax-fp32`), still open and unfixed at 2026-08-27, labels `type:bug` + `status:awaiting LiteRTer` (https://github.com/google-ai-edge/LiteRT/issues/6462 and its comments, 2026-09-09). Note the collision: `--relax-fp32` is the only flag that produced a correct Whisper encoder on MDLA and the only flag the public API emits. | Large — §1.5. | **The only route worth a spike, and only after E0 + E3 say the APU is reachable from a normal app on this device.** Effort class: the 4.0/4.1 QNN tier minus its portable Kotlin. |
| **R2** | LiteRT NeuroPilot, **JIT** (on-device compile via the same `CompiledModel`) | as R1 minus the AOT step (`…_jit.zip`) | none | none; compiling a 32-layer d=1280 encoder on-device at every cold start is the failure ProventusNova traced to a whole-board reboot on Genio and fixed by going AOT (https://proventusnova.com/blog/whisper-mediatek-genio-npu, 2026-08-02); #6462's public bytecode already degrades into JIT silently | as R1 | **A live route on paper (the LiteRT page documents both), a diagnostic arm of E4 in practice** — what R1 degrades into, not a separate bet. |
| **R3** | LiteRT GPU accelerator on Mali-G720 MC12 (the brief's "home turf") | yes (no vendor libs beyond the GPU driver) | **Counter-evidence on Adreno, nothing on Mali:** "LiteRT Next GPU delegate fails for Whisper encoder" — the large-v3-turbo encoder `[1,128,3000]` runs on the CPU delegate and crashes on the GPU delegate (S23U), opened 2025-08-09, **closed as stale with no maintainer fix** (https://github.com/google-ai-edge/LiteRT/issues/3188, 2026-09-09). No published Whisper number on any Mali. | none; the published turbo `.tflite` keeps fp32 activations, so a GPU run is the full 2,313 GFLOP window per commit (no `audio_ctx` lever) | if it worked: a `WhisperBackend` over `CompiledModel` — the smallest integration after CPU | **Run E5 (an afternoon, no conversion); do not plan on it.** "Home turf" survives only as "untested on Mali". |
| **R4** | ggml Vulkan on Mali (`-DGGML_VULKAN=ON`, vendored `ggml-vulkan`) | yes | no Whisper on Mali-G720 by anyone. llama.cpp on an unnamed Android Mali via Vulkan: CPU 1,500–1,700 ms vs Vulkan 24,000–25,000 ms, 2024-09-13, replies to 2026-02-21, no root cause (https://github.com/ggml-org/llama.cpp/discussions/9464); Mali-G57, 2025-01-12, Llama-2-7B Q4_0: ~3× loss (https://github.com/ggml-org/llama.cpp/discussions/10879); a 2026 Orion O6 Mali-G720 report claiming a Vulkan **win** returned HTTP 403 — unverified | none; the repo's only GPU datum is `multi` on Adreno 750 at 8–9× slower than CPU (`docs/superpowers/research/2026-09-04-turbo-cpu-gpu-research.md:39`) | one flag: `app/build.gradle.kts:96-98` reserves it "only for Mali/Xclipse experiments". **But the vendored tree lacks the Mali-G720 fix:** llama.cpp PR #25245 (merged 2026-07-06) changed `CEIL_DIV` to `(((M) / (N)) + (((M) % (N)) != 0))` to fix a G720 `GGML_ASSERT(descriptor_set_idx < …)` (https://github.com/ggml-org/llama.cpp/issues/23057); `ggml/src/ggml-vulkan/ggml-vulkan.cpp:132` still carries the old `(((M) + (N)-1) / (N))` (read 2026-09-09). | **A side experiment for a free hour — after a one-line `CEIL_DIV` patch; expected to lose; even a win is a GPU win for `multi`, not a turbo route.** |
| **R5** | ONNX Runtime **NNAPI EP** through the bundled sherpa-onnx | **yes, already in the APK:** `libonnxruntime.so` (21,688,920 B) exports `OrtSessionOptionsAppendExecutionProvider_Nnapi@@VERS_1.27.0`, version `1.27.0`, and also carries the string-registered **XNNPACK** EP (`llvm-strings`: `XnnpackExecutionProvider`; https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html, 2026-09-09); sherpa's `session.cc` selects `nnapi` (flags 0, `__ANDROID_API__ >= 27`) or `xnnpack` by provider string (https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/csrc/session.cc); Kotlin `OfflineModelConfig.provider` + `OfflineWhisperModelConfig(encoder, decoder, language, task, tailPaddings, …)` (javap on the AAR). Pre-exported turbo: `csukuangfj/sherpa-onnx-whisper-turbo` int8 encoder 674,716,297 B + decoder 361,080,764 B (https://huggingface.co/api/models/csukuangfj/sherpa-onnx-whisper-turbo/tree/main). HAL shim running (inventory `:33`). | no | **bad by construction:** sherpa pads to `max_num_frames = 3000` and warns "Only waves less than 30 seconds are supported" — **2,313 GFLOP every commit, no 512 floor** (https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/sherpa-onnx/csrc/offline-recognizer-whisper-impl.h); ORT's NNAPI EP lists MatMul/Softmax but no LayerNorm/GELU/Attention and "might fall back to its CPU implementation" (https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html); Google: "NNAPI is deprecated … we expect the majority of devices in the future to use the CPU backend" (deprecated in Android 15; https://developer.android.com/ndk/guides/neuralnetworks) | as a tier: a second decoder + 1.04 GB of weights for no floor lever. As a probe: one config object in an androidTest. | **Dead as a tier; worth one run as the cheapest question anyone can ask MediaTek's NNAPI HAL ("do you offload a transformer encoder at all?"), read from timing; the CPU arm (`cpu` = MLAS, `xnnpack`) doubles as an ORT-int8-vs-ggml-q5 per-GFLOP point.** |
| **R6** | ExecuTorch MediaTek backend | partly: supported hardware lists "Dimensity 9300 (D9300), Dimensity 9400 (D9400)"; needs `mtk_neuron-8.2.23…whl` + `mtk_converter-8.13.0+public…whl` from NeuroPilot Express, a Linux host, and pushing `libneuron_backend.so`, `libneuronusdk_adapter.mtk.so`, `libneuron_buffer_allocator.so` (https://docs.pytorch.org/executorch/stable/backends-mediatek.html, 2026-09-09); licence text **UNVERIFIED** (the Express page rendered empty for the map author) | no — `mobilenetv3` is the only example | none | a fifth native runtime plus everything in R1(d); the app-supplied USDK adapter faces the same `/dev/apusys*` question as R1 | **A live route, high-risk, not before R1 — everything it could prove, R1 proves with a runtime Google maintains.** |
| **R7** | plain CPU, whisper.cpp, turbo q5_0 / q8_0 (un-retire `ultra`) | yes — the path exists; turbo ran **correctly** on CPU once in the 2026-07-17 A/B (`GpuPolicy.kt:143-148`); zero `tier=ultra` timings anywhere (`…turbo-cpu-gpu-research.md:37`) | yes (CPU) | **§1.3: 13.8–19.4 s per commit at the 512 floor, sub-linear floor 9.2 s, vs a 1.9 s bar** | trivial (catalog flag + the 128-bin mel fix `WhisperModel.kt:391-394`) | **Dead for streaming by ≥ 4.8×; owner-dropped 2026-09-04 (memory note); alive only as the datum that closes the question for the record (E1/E2).** |
| **R8** | CPU turbo through the bundled ORT (sherpa int8) | yes | no | same silicon, 2,313 GFLOP per call with no floor lever | a second decoder, 1.04 GB | **Never a tier; only the CPU arm of the R5 probe.** |
| **R9** | NeuroPilot SDK proper / linking `/vendor` Neuron libs from a sibling JNI | compiler account-gated: "A MediaTek Developer account only provides access to the Genio Developer Center and does not grant access to NeuroPilot SDK downloads" (https://genio-community.mediatek.com/t/how-to-access-mediatek-neuropilot-sdk-information-and-tools/504); MediaTek staff call the SDK "NDA-only" (https://genio-community.mediatek.com/t/d9300-gemma-4-e2b-35-layer-single-shard-compile-fails-neuron-unmappable-multi-shard-litert-lm-not-supported-needed-compile-flags-missing-from-public-api/2379, staff reply 2026-06-09); runtime access = the same `public.libraries.txt` question as R1 | nobody on a retail device | n/a | n/a | **Dead for a Play app unless MediaTek engages; offers nothing R1 does not.** |

Two artefact facts the maps got wrong, corrected here: `litert-community` **does** publish
`whisper-large-v3-turbo` (created 2026-07-23, MIT; `whisper_large_v3_turbo_30s_i8.tflite` 1,088,340,944 B
with dynamic-range int8 weights and **fp32 activations**, `…_i4.tflite` 755,273,648 B; two signatures, a
fixed 30 s window, "no KV cache" full-sequence decoder re-runs; validated on x86 XNNPACK and a
Snapdragon-865-class arm64 device; **no vendor/NPU variant** —
https://huggingface.co/litert-community/whisper-large-v3-turbo and
https://huggingface.co/api/models/litert-community/whisper-large-v3-turbo/tree/main, 2026-09-09), plus
`whisper-large-v3`, `whisper-medium`, `whisper-base` (Apache-2.0, `encode: f32[1,80,3000] → f32[1,1500,512]`,
`decode: f32[1,1500,512], i32[1,128], f32[1,1,128,128] → f32[1,128,51865]` —
https://huggingface.co/litert-community/whisper-base) and `whisper-tiny`. So a turbo `.tflite` exists as a
free CPU/GPU experiment (E5); a static-shape turbo the MediaTek AOT compiler accepts is still work nobody
has done.

### 1.3 The CPU baseline question — closed by arithmetic on a documented datum

**Inputs (all DOCUMENTED).**
- `audio_ctx = clamp(samples/320 + 64, 512, 1500)` and `n_threads = min(hardware_concurrency, 4)` with
  per-op barriers (`app/src/main/cpp/whisper_jni.cpp:857-863`, `:897-908`); kernels built
  `armv8-a+dotprod+i8mm`, runtime-dispatched (`app/build.gradle.kts:80`).
- FLOP table: small / turbo = **113.3 / 706.6 GFLOP at 512 frames**, 189.2 / 1,163.8 at 814, **386.7 /
  2,313 at 1500** (`docs/superpowers/research/2026-09-04-turbo-cpu-gpu-research.md:76-86`; formula
  24·d²·L·T + 4·T²·d·L + 4·d²·n_text·T + conv, re-derived by the refuter: turbo at 1500 = 1,887 + 368.6 +
  39.3 + 17.6 = 2,312.5).
- Fold6 (SM8650-AC: 1×X4 3.39 + 3×A720 3.1 + 2×A720 2.9 + 2×A520 2.2,
  https://www.gsmarena.com/samsung_galaxy_z_fold6-13147.php) calibration on `multi-q5_1`: 1,316 / 1,822 /
  2,323 / 3,674 ms at 256 / 384 / 512 / 768 frames (`docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md:100-114`)
  → **19.22 ms/GFLOP + 227 ms**, residuals ≤ 3.5 % (`…research.md:93-95`).
- The tablet datum (§1.1): 281,088 samples, no trim, one encode → `281088/320 + 64` = **942 frames**;
  small at 942 frames by the formula = **223.4 GFLOP**; service time ≈ **6.05 s**; decoder share
  ≈ 58 tokens × 11.4 ms ≈ 0.66 s (`vad-hangover-retune.md:68`; `…research.md:120`).
- The bars: the rule `F/floor + m ≤ 0.70` (`…research.md:415-417`, m ≈ 0.04 at `:125`) gives **1.32 s** for
  the 2,000 ms row and **5.28 s** (the doc's own §4.4: 4.8–5.2 s) for the 8,000 ms row; the **shipped**
  2,000 ms row sits on the owner's ruling at F = 1.89 s, "~98 % saturated duty", stepping to 3,200 ms at
  queue depth 2 (`CommitCadencePolicy.kt:196-207`, `:112-131`) — so the practical bar is **≈ 1.9 s**.

**Derivation (INFERRED from the above).**

| Quantity | Fold6 rate (19.22) | tablet, encoder-only (≤ 24.1) | tablet, all-in (27.1) | optimistic sub-linear × 0.67* |
|---|---|---|---|---|
| tablet effective rate from the datum | — | (6,050 − 660) / 223.4 = **≤ 24.1 ms/GFLOP** = **1.25× the Fold6's cost** | 6,050 / 223.4 = 27.1 | — |
| Fold6 model's prediction for the same chunk | 223.4 × 19.22 + 227 + 660 = **5.18 s** (observed ≈ 6.05) | | | |
| **turbo F at 512 frames** (every commit ≤ 8.96 s of speech) | 706.6 × 19.22 + 227 = **13.8 s** | **17.3 s** | **19.4 s** | **9.2 s** |
| turbo F at 814 frames (the 15 s wall cap) | **22.6 s** | 28.3 s | 31.8 s | 15.1 s |
| turbo F at 942 frames (the measured chunk) | 1,381 × 19.22 + 227 = **26.8 s** | 33.5 s | 37.6 s | 18.0 s |
| small, same chunk, **measured** | — | — | **≈ 6.05 s** | — |

\* the repo's own base→small pair scaled 2.8–3.0× where arithmetic said 4.18× (`…research.md:101`); if
turbo's larger matmuls repeat that, × 0.67–0.72.

**Reading it.** The most optimistic cell (9.2 s) is 4.8× the shipped 2,000 ms bar and 1.7× over the
8,000 ms row's ceiling; even granting an unmeasured 1.5× from eight threads on top of the sub-linear
floor gives 6.2 s > 5.28 s. Under continuous speech the never-shed single-thread queue grows without
bound (`…research.md:25-30`, reproduced here on the tablet's own datum). q8_0 is a kernel-efficiency lever
the research doc already bounded at `F_q8 ≤ 0.85 × F_q5` as the *success* criterion (`:424`) — 0.85 rescues
nothing. RAM is not the constraint: 1.0–1.3 GB for q5_0 on a 12 GB tablet.

**The "four X4 cores" question.** The datum does not show an X4 advantage: three adversarial readings
(a double encode, a VAD trim, unlogged temperature-fallback re-decodes) are excluded by the log or move
the bound to parity at best (~18 ms/GFLOP; an advantage would need ≲ 13). ggml sets no thread affinity, so
under EAS a helper thread can land on an A720 @ 2.0 GHz, and the 6.05 s already contains whatever
placement happened; per-clock NEON throughput X4 vs A720 is **not established from any primary source**
(WikiChip: the X4's FP "pipes and units themselves remain unchanged" —
https://fuse.wikichip.org/news/7531/arm-introduces-the-cortex-x4-its-newest-flagship-performance-core/).
The remaining confound is field-vs-bench (a live device-audio session vs the Fold6's isolated pinned
bench, `WhisperBenchTest.kt:290`); only a pinned `taskset` run separates silicon from scheduling. What the
design needs is not 1.5×: the 2,000 ms row needs **10.3×** the Fold6 rate and the 8,000 ms row **2.6×**;
four X4s at helper clocks *lower* than the Fold6's A720s do not buy 2.6×.

**Verdict.** CPU turbo cannot be a streaming tier on this tablet — and the owner already ruled it out on
2026-09-04 ("not worth stuffing turbo on any CPU", memory note). The question is worth **closing with a
recorded number** because `CommitCadencePolicy.kt:47` still says "UNMEASURED" and `ultra`'s retirement
never had one: E1/E2 in §1.4, optional and for the record.

### 1.4 The experiments, cheapest first, each with its decision rule

| # | Experiment | Cost | Decides | Rule |
|---|---|---|---|---|
| **E0** | Three adb reads on the Tab: `getprop ro.soc.manufacturer ro.soc.model` (the strings a MediaTek gate and a Play `<config:system-on-chip>` would pin — LiteRT's CSV expects `Mediatek` / `MT6989`); `cat /vendor/etc/public.libraries.txt` (is `libneuron_adapter_mgvi.so` or any `libneuron*` whitelisted); `ls -Z /dev/apusys /dev/apusys_apummu /dev/apusys_sapu` and `ls /vendor/lib64 \| grep -i "usdk\|sys_util"` | minutes, owner | R1's (a): whether the runtime's first dlopen candidate is app-loadable, whether a bundled adapter could open the nodes, the gate strings | absent from the whitelist ⇒ not a kill, a fork (LiteRT v2.1.3: "Expanded MediaTek NPU support to all applicable Android versions … by supporting bundling MediaTek libraries in application binary", https://github.com/google-ai-edge/LiteRT/releases/tag/v2.1.3, 2025-03-17) |
| **E3** | The throwaway **`litertlm-probe`** app (its own `applicationId`, debuggable, sideloaded beside the Play copy): `com.google.ai.edge.litertlm:litertlm-android`, load Google's published **Gemma3-1B for MT6989** `.litertlm` (985 MB, 4-bit; catalogue page 2026-09-02) on `Backend.NPU(nativeLibraryDir = …)` (https://developers.google.com/edge/litert-lm/android, updated 2026-09-04) | half a day PC + one Tab session; shared with §2.6's G2.1 | **the APU go/no-go on this exact SoC from a normal app** — the only artefact anyone has documented running on an MT6989 APU | does not come up ⇒ **direction (1) is closed on the only live route**; comes up ⇒ R1's (a) is answered whichever branch it took |
| **E4** | AOT-compile `litert-community/whisper-base` (`whisper_base_30s_f32.tflite`) for MT6989 with `ai-edge-litert` + `ai-edge-litert-sdk-mediatek` on the Ubuntu host; run `encode` on the Tab in the probe app via `CompiledModel` + `Accelerator.NPU`; mean of ≥ 20 warm runs + two cold; **also record whether the runtime logged `restoreFromCompiledNetwork` failure + JIT fallback** (the #6462 signature) and run a **JIT control** | 1–3 days after E3 | the first ASR-on-MediaTek-APU number in existence | **A direction-finder, not a kill gate (INFERRED, low-medium):** turbo/base ≈ 24× by FLOPs at 1500 frames (2,312.5 / 96.7; Hexagon cross-check 1,779 / 23.9 = 74 ms). The repo's own Hexagon pair scales **sub-linearly** — small 404.6 ms vs turbo 1,778.9 ms = 4.40× against a 5.98× FLOP ratio (`docs/superpowers/research/2026-08-28-npu-spike-g1-results.md:15, :27`; AI Hub 270.8 vs 1,340.6 = 4.95×, `2026-08-27-npu-whisper-turbo-research.md:35`) — so the calibrated multiplier is ~20–22×. Thresholds: **≲ 75 ms ⇒ turbo-class (2,000 ms row) plausible; ~145 ms ⇒ `multi`-class (≈ 3.9 s); ≳ 240–260 ms ⇒ turbo above the 8 s row's honest ceiling — close.** Caveats: encoder-only (APU decode unmeasured; QNN's decode adds ~0.33 s at 23 tokens), fp32-relaxed base vs a w8a16-class turbo, and a slow number may be the #6462 toolchain rather than the silicon — hence the JIT control. |
| **E5** | Load `whisper_large_v3_turbo_30s_i8.tflite` (1.09 GB, MIT) in the probe app; time `encode` on (i) LiteRT CPU/XNNPACK at 4 threads and (ii) the LiteRT GPU accelerator on Mali-G720 | an afternoon, no conversion; parallel | (ii) closes R3 (a crash reproduces #3188 on Mali); (i) gives int8-XNNPACK vs ggml-q5_0 per GFLOP at 1500 frames | GPU crash or slower-than-CPU ⇒ R3 closed |
| **E1** (optional, for the record) | NDK cross-build of the vendored tree's `whisper-cli` + `whisper-bench` **targets only** (`--target whisper-cli whisper-bench`; `WHISPER_BUILD_EXAMPLES=ON` otherwise pulls in server/quantize/vad/parakeet — `examples/CMakeLists.txt`), app flags (`-DGGML_CPU_ARM_ARCH=armv8-a+dotprod+i8mm -DGGML_OPENMP=OFF -DGGML_BACKEND_DL=OFF -DGGML_OPENCL=OFF`), pushed to `/data/local/tmp` with `LD_LIBRARY_PATH=lib` (llama.cpp's documented pattern, https://raw.githubusercontent.com/ggml-org/llama.cpp/master/docs/android.md); `whisper-cli -m … -f <≤ 8.9 s clip> -l en -t 4 -ac 512` (`examples/cli/cli.cpp:159`, `:168`, `:1223`), read `encode time` / `decode time` (`src/whisper.cpp:4541-4542`); repeat with `-ac 814` on a 15 s clip and pinned with `taskset` to the X4s. The three ggml files are already on the PC (`C:/Users/bastr/.androidbuild/WhisperEverywhere/ggml-large-v3-turbo-{q5_0,q8_0}.bin`, `ggml-small-q5_1.bin`). **Nobody has built this tree with the NDK yet — until a build log exists this row is a plan, not a result.** | an afternoon; no gradle, no app install | the tablet's own ms/GFLOP on `small`, the turbo number, the pinned-X4 number | F(512) ≤ 5.3 s ⇒ the 8,000 ms row becomes honest (contrary to §1.3); ≤ 1.3 s ⇒ the 2,000 ms row; anything above closes CPU turbo with a recorded number and fills `CommitCadencePolicy.kt:47` |
| **E2** (alternative to E1) | The in-app `WhisperBenchTest` (`WhisperBenchTest.kt:30-35`, `:88-100`; slices 1/3/8/15 s) on a debug sideload of `main` — **the owner approved uninstalling the Play copy on the Tab for exactly this on 2026-09-04** (memory note: "we can do whatever we like"; only the 190 MB `multi` is at stake there; **never on the Fold6**, `INSTALL_FAILED_UPDATE_INCOMPATIBLE` + models die with an uninstall, build-env note); push `ggml-large-v3-turbo-q5_0.bin` under `files/models/` (its size equals `ultra`'s `approxBytes`, `WhisperModel.kt:232`) | a Tab session; no gradle edit | the production seam's number (VAD + `GpuPolicy` + `WhisperNativeBackend`) that E1 does not measure; the same session reads `multi`'s F at the floor for §4.6's cadence note | as E1 |
| **E6** (optional) | `-DGGML_VULKAN=ON` on a debug build **after patching `ggml-vulkan.cpp:132`** with PR #25245's `CEIL_DIV`; E2's harness on `multi` | an hour + the Tab sideload | R4 | Vulkan F ≤ 0.8 × CPU F on two cold runs ⇒ a GPU experiment for `multi` worth a design; else closed |

### 1.5 Integration cost if R1 survives E0 + E3 + E4 (DOCUMENTED seams; INFERRED sizing)

A MediaTek turbo tier is **a parallel gate and a parallel pack family, not a fifth census row**, and it is
large:

- **The autoregressive decode loop must be written again.** The existing one is C++ behind
  `QnnAsrNative` (`app/src/main/cpp/qnn_asr.cpp:3023`
  `Java_com_whispereverywhere_npu_QnnAsrNative_nativeDecodeSegment`) — KV bookkeeping, temperature
  ladder, in-loop entropy/cycle guard, the six out-stats. What is portable unchanged: the mel
  (`WhisperNative.initMelOnly` / `pcmToMel`, `WhisperNative.kt:288`, `:341`, plus the bundled 128-bin
  `melbank-128.bin`, `NpuModelSpec.kt:288-311`), `WhisperBpeDecoder`, `WhisperTokens.LARGE_V3`,
  `NpuDecodePolicy`, `NpuQuantize`, `HallucinationPolicy`, `NpuDiag`, and `NpuModelSpec.TURBO`'s tensor
  arithmetic (`NpuModelSpec.kt:351-368`: 128 mel bins, 3000 frames, 4 decoder layers, 20 heads, 51,866
  vocab).
- **A second capability predicate.** `NpuGate.SUPPORTED_SOC_MANUFACTURERS = setOf("QTI", "Qualcomm")`
  (`NpuGate.kt:54`) is enforced before the model lookup (`:82`); `npuCapableDevice` is the QNN probe
  (`NpuWhisperBackend.kt:973-975`, `WhisperEverywhereApp.kt:75-81`). **Do not widen the allowlist** — an
  `MtkGate` + its own probe, composed into `offeredNpuTierIds()` (`WhisperEverywhereApp.kt:160-166`).
- **A third spec row and a new catalog row.** `NpuModelSpec.forTier` is a two-row table (`NpuModelSpec.kt:382-386`)
  that is also the routing membership test (`NpuBackendSelector.kt:87`, `backendFor` `:136-148`); the
  `npu`/`npu-turbo` rows pin Qualcomm sha256s and byte counts (`WhisperModel.kt:240-296`);
  `ONE_TIER_ID` is literally `npu-turbo` (`:316`) and `pickableFor` narrows the lineup around it
  (`:368-375`) — "the turbo card has two vendors" is a product decision.
- **The seam that already fits:** `WhisperBackend.load(modelPath, companionPath)` (`TranscriptionEngine.kt:179`)
  takes an encoder/decoder pair; every new member takes a default **body**, never a defaulted parameter
  (`:162-179` — 23 overrides across 10 files would silently un-override otherwise). The decline path is
  built: `NpuTierStatus.cardNote` already speaks to the MediaTek tablet declining correctly onto the CPU
  model (`NpuTierStatus.kt:219-224`).
- **A fourth native runtime in the APK** (LiteRT + MediaTek dispatch + neuron adapter) beside ggml (dlopen
  modules, `app/build.gradle.kts:88-92`), QNN (`libqnnasr.so`) and ORT (the sherpa AAR).
- **Delivery.** AI packs "only contain models. Java/Kotlin and native libraries are not allowed", ≤ 1.5 GB
  each, under Play for On-device AI — **beta**, `ai-delivery:0.1.1-alpha01`, AGP 8.8+ / 8.10+ for device
  targeting, `<config:system-on-chip manufacturer= model=>` mapped to `Build.SOC_MANUFACTURER/SOC_MODEL`
  (https://developer.android.com/google/play/on-device-ai, updated 2026-09-01). The runtime `.so`s go in
  the base module (under Play's 500 MB base cap, https://support.google.com/googleplay/android-developer/answer/9859372)
  or a feature module — Google's documented distribution is feature modules keyed by
  `device_targeting_configuration.xml` (LiteRT NPU page, 2026-08-26), but base-module bundling is the
  documented alternative (PODAI page; LiteRT v2.1.3). A single-variant `mtk_turbo` on-demand pack fetched
  by app logic through the `AssetPackManager` path the app already uses (`NpuPackController.kt`) needs no
  new device group until a second MediaTek SoC variant ships (INFERRED from `2026-08-29-pad-soc-delivery.md:88-95`);
  `verifyNpuPacks` (`app/build.gradle.kts:795-804`) and the stub/skel census rule (`:174-200`) need a
  parallel concept. The app is on AGP **8.13.2** (`build.gradle.kts:3`) — no bump.
- **The conversion nobody has done**, and the quantisation problem underneath it: a static-shape turbo
  encoder + decoder `.tflite` the MediaTek AOT compiler accepts (the published turbo `.tflite` is
  dynamic-range int8 with fp32 activations and no KV cache; the published base has a 128-token static
  decode window, the trick `NpuModelSpec.kt:25-52` already documents for QNN). An fp16 turbo is
  ≈ 1.6 GB-class (INFERRED from the w8a16 pair being 1.07 GB with 8-bit weights) — over the pack cap —
  while INT8 is the precision MediaTek's own forum shows producing garbage for a Whisper encoder.
- **The precedent's worst failure shape** is not a crash: "ANOTHER MODEL'S TRANSCRIPT: fluent, confident,
  and wrong, with nothing failing anywhere" (`NpuWhisperBackend.kt:490-498`).

**What R1 would deliver if it worked (INFERRED):** a `TURBO`-row tier (2,000 ms cadence, 3,200 under
backpressure) on the one Android SoC family Qualcomm does not cover — if E4 lands ≲ 75 ms; at ~300 ms a
~7 s `MULTI`-class tier that buys turbo's accuracy at the cadence the tablet already has. What it cannot
deliver regardless: streaming deltas (the NPU arm is a plain `transcribe`, `NpuWhisperBackend.kt:714-741`)
and the 512-frame floor lever (fixed 1500-frame encoder).

### 1.6 What the owner must obtain for direction 1

- **E0, E1/E2, E5:** nothing new — adb, NDK 27.0.12077973, cmake, the three ggml files and the sherpa AAR
  are on the PC; the LiteRT turbo `.tflite` and the sherpa turbo ONNX are public (MIT).
- **E3/E4 (R1):** an **Ubuntu 22.04 host** with **Bazel 7.4.1** (Google's stated host; WSL2 is plausible but
  undocumented — INFERRED); `pip install ai-edge-litert ai-edge-litert-sdk-mediatek` (Apache-2.0 on PyPI;
  **no MediaTek account, no NDA**); `litert_npu_runtime_libraries.zip` from the LiteRT release; a Play
  Console with PODAI (beta; whether enrolment is needed is not stated — UNKNOWN) only if the tier ships.
- **Not needed:** NeuroPilot SDK proper (rep-gated, thread 504; "NDA-only", thread 2379); NeuroPilot
  Express wheels (licence unverified) — R6/R9 are not recommended.
- **Device time:** one Tab session for E3 + E4 + E5 (and G2.1, §2.6); the approved Play-copy uninstall on
  the Tab only if E2/E6 are run.

---

## 2. DIRECTION 2 — the Gemma audio tier ("the Gemma local model scribe")

### 2.1 Which model the owner means (DOCUMENTED ladder; INFERRED ruling)

| Candidate | What it is | Status 2026-09-09 | Fits the owner's phrase? |
|---|---|---|---|
| **"Audio Scribe" in the Google AI Edge Gallery** | The Gallery feature that transcribes/translates voice on-device. Launched 2025-09-09 on **Gemma 3n** via the MediaPipe LLM Inference API, "audio batch inference for clips up to 30 seconds long", with "**Streaming audio support is next on our roadmap**" (https://developers.googleblog.com/google-ai-edge-gallery-now-with-audio-and-on-google-play/, 2025-09-09). | The Gallery README now headlines Gemma 4 and describes Audio Scribe as "in real-time" (https://github.com/google-ai-edge/gallery, 2026-09-09) — but its own dependency file pins `com.google.ai.edge.litertlm:litertlm-android:0.11.0` with no `tasks-genai` (https://github.com/google-ai-edge/gallery/blob/main/Android/src/gradle/libs.versions.toml, 2026-09-09), and its recorder accumulates `ENCODING_PCM_16BIT` and calls `onSendAudioClip` only on stop, `MAX_AUDIO_CLIP_DURATION_SEC = 30`, `SAMPLE_RATE = 16000` (`…/ui/common/chat/AudioRecorderPanel.kt`, `…/data/Consts.kt:66, :69`, 2026-09-09) — **a 30 s batch recorder in code**. | **Yes — this is what the owner means.** "Gemma", "local", "scribe", "native audio-in" all match. |
| **Gemma 4 E2B / E4B** | The models under that feature now. Native audio encoder; **Apache 2.0**; released 2026-04-02 (https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/). | `.litertlm` for LiteRT-LM: E2B 2.58 GB, E4B 3.65 GB; the model page lists exactly these two (https://developers.google.com/edge/litert-lm/models/gemma-4, updated 2026-09-04). | Yes — the model under the feature. |
| Gemma 3n E2B / E4B | The previous generation. | Superseded: not on LiteRT-LM's model page; MediaPipe LLM Inference is "in maintenance-only mode" with migration to LiteRT-LM recommended (https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android, updated 2026-06-12); worse audio (FLEURS 0.108 vs 0.090). | Only if the owner saw the 2025 launch. **Do not build on it** — the brief's "Gemma 3n … via LiteRT-LM / MediaPipe" is one generation stale on both model and runtime. |
| ML Kit GenAI Speech Recognition | Google's on-device *streaming* ASR API; Advanced = Gemini Nano (Pixel 10/11 only), Basic = the traditional on-device recognizer. | `genai-speech-recognition:1.0.0-alpha1`, alpha, "not subject to any SLA or deprecation policy"; Basic "Generally available on most Android devices with API level 31 and higher" (https://developers.google.com/ml-kit/genai/speech-recognition/android, updated 2026-09-01). | Partly — it streams word-for-word, but it is not a model the app ships. §2.7. |
| "Gemma Scribe" | — | **Does not exist** (searched 2026-09-09). The only "Scribe" in this repo is ElevenLabs' cloud model: `const val DEFAULT_MODEL = "scribe_v2"` (`ElevenLabsStt.kt:98`). | Name conflation. |
| Gemini 3.5 Transcribe / Transcribe-Live | Google's dedicated **cloud** STT, GA 2026-08-26. | Direction (3). | No — not local. |

**Ruling:** the owner means *Audio Scribe*, i.e. **Gemma 4 E2B (2.3B effective / ~5.1B total) or E4B
(4.5B / 8B) under LiteRT-LM** (tech report https://arxiv.org/html/2607.02770v1, 2026-07-02), and the
"streaming, word for word" half of the sentence is a wish imported from the cloud providers, not a
property any Gemma surface has.

### 2.2 What it can and cannot do (DOCUMENTED unless marked)

| Capability | Fact | Source |
|---|---|---|
| Input contract | 16 kHz, 32-bit float, mono, normalised to [-1, 1]; **"Audio supports a maximum length of 30 seconds"**; **25 tokens per second of audio** (6.25 for 3n) | https://ai.google.dev/gemma/docs/capabilities/audio (updated 2026-06-05) |
| Prompts Google publishes | ASR: `Transcribe the following speech segment in {LANGUAGE} into {LANGUAGE} text.`; AST: `… in {SOURCE_LANGUAGE}, then translate it into {TARGET_LANGUAGE}.` | same |
| Audio encoder | 305M params (−55 % vs 3n's 680M), USM lineage, 2 downsampling convs + 12 Conformer layers, 40 ms frames, **87 MB quantised on disk**, frozen in pre-training; **Gemma 4 12B is encoder-free** and not an Android candidate | tech report |
| Quality | FLEURS ASR WER avg **0.090 (E2B)**, **0.075 (E4B)** over 12 languages; §2.4 has the per-language rows | tech report Table 7 |
| Languages | 140+ pre-training; "35+ out of the box"; **audio evaluated on 12** | https://huggingface.co/google/gemma-4-E2B |
| Licence | **Apache 2.0** weights; LiteRT-LM runtime Apache-2.0 | blog.google (2026-04-02); https://github.com/google-ai-edge/LiteRT-LM |
| **Streaming audio in** | **Not documented on any Google surface.** The Kotlin API takes whole clips — `Content.AudioBytes(ByteArray)` / `Content.AudioFile` — with no incremental append and no format, duration or streaming statement; releases v0.12.0 → v0.17.0 (2026-09-09) carry no streaming-audio item (v0.14.0: "NPU and GPU audio acceleration"; v0.15.0: "multimodal (vision/audio) execution"); the 2025 roadmap line has no follow-up | https://developers.google.com/edge/litert-lm/android (2026-09-04); https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md; https://github.com/google-ai-edge/LiteRT-LM/releases |
| Streaming text out | Yes: `sendMessageAsync(contents): Flow<Message>` | LiteRT-LM Android page |
| Timestamps / diarization / language-ID | **None documented** (audio doc, E2B card, tech report searched) | documented absence |
| Byte format at the Kotlin seam | Undocumented by Google; the model doc says float32; the Gallery hands LiteRT-LM **PCM16 @ 16 kHz** (a code fact about the Gallery, §2.1) — try float32 first, PCM16 second, record which the runtime accepts | see §2.6 |
| Thinking / system instruction | `ConversationConfig` exposes `thinkingConfig` (`enableThinking`, `thinkingTokenBudget`), `systemInstruction`, `samplerConfig` | LiteRT-LM Android page |
| Engine init | "can take a significant amount of time (e.g., up to 10 seconds)" | same |
| Audio encoder loading | "Vision and Audio models are loaded on demand to further reduce memory consumption" — an increment on top of the text footprint | https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm |
| NPU builds | E2B: `_Google_Tensor_G5` (3,113,545,589 B), `_Google_Tensor_G6`, `_qualcomm_sm8750` (3,016,294,400 B), `_qualcomm_qcs8275`, `_intel_LNL`, `_intel_PTL` — **no mediatek/mt6989 file**; E4B: no NPU build of any vendor (https://huggingface.co/api/models/litert-community/gemma-4-E2B-it-litert-lm/tree/main, lastModified 2026-08-31; …/gemma-4-E4B-it-litert-lm/tree/main). Google's catalogue (2026-09-02) lags the tree (lists Gemma4-E2B for Tensor_G6 only) and lists **MediaTek → Gemma3-1B text-only for MT6989/MT6991**. MediaTek staff (2026-06-09): a single-shard Gemma 4 E2B compile for D9300 fails `NEURON_UNMAPPABLE`, multi-shard is incompatible with LiteRT-LM, "Gemma-4 support timeline: concrete plans by end-Q3 2026" (Genio thread 2379, §1.2 R9 link). A Gemma **3n** MediaTek build exists only for **MT6993** (Dimensity 9500). **On the Tab, Gemma runs on GPU or CPU, not the APU.** | HF API; catalogue page; https://github.com/google-ai-edge/LiteRT-LM/issues/2507 (open since 2026-06-08, no maintainer reply) |
| GPU on Mali / Xclipse | LiteRT-LM's Android GPU backend is OpenCL (https://developers.googleblog.com/blazing-fast-on-device-genai-with-litert-lm/, 2026-05-19). **Three non-Adreno failure reports, none answered:** #1850 (2026-04-03, Pixel 8, Mali-G715, 0.10.0 crashes in GPU decode "clEnqueueNDRangeKernel - Invalid command queue", self-patched), #2202 (2026-05-07, Pixel 10a, Mali-G715, 0.10.x: "GPU numerical precision corruption", CPU correct but 2–3× slower), #2611 (2026-06-19, Galaxy A55, Xclipse 530, 0.13.x: GPU "garbage repeating output", CPU correct). **Nothing exists for Mali-G720.** ggml's Mali refusal is its own Adreno-only regex (`GpuPolicy.kt:140`) — a different mechanism; "different path" ≠ "known good". | https://github.com/google-ai-edge/LiteRT-LM/issues/1850, /2202, /2611 |

### 2.3 The streaming loop design — what "live" can honestly mean on a 30-second-clip audio LLM

**The three shapes (INFERRED design over the documented API):**

- **A. Cumulative re-transcription of the open utterance** — the BlackBox loop the owner rated "instant and
  very accurate" on an RTX 2000 Ada (memory note, 2026-09-01). Every tick, send `audio[start..now]` with the
  ASR prompt, regenerate the whole transcript, render it as a replacement. Cost per tick = prefill(25·t) +
  decode(all text so far); no documented KV/prefix reuse across calls (README, Android page, Kotlin guide;
  #2202 reports a history-rebuilt cache "isn't bit-equivalent to incrementally-grown cache"), and even with
  reuse the re-decode makes the loop **O(U²)** per utterance. **This is text while the user speaks, delayed
  by one tick (1.5 s best case, 3–5 s on the Tab), inside the 30 s cap — not word-for-word.**
- **B. Token streaming within a closed chunk** — at ~52 tok/s ≈ 40 words/s the final sentence types out
  ~15× faster than it was spoken, **after** the endpoint. A cosmetic typewriter; the words were already decided.
- **C. Overlapping windows stitched by longest-common-prefix** — the only way past the 30 s wall for one
  long utterance; ~2× compute; a stitcher the app does not have. Future work.

**The loop to build if the tier is ruled in** (shape A + B; constants from the repo and §2.4):

1. **Window = the open utterance**, bounded before the model sees it: the 15 s wall cap with
   `CAP_CUT_MAX_RETAIN_MS = 3_000` (`CommitCadencePolicy.kt:170`) and the engine's hard ceiling
   `MAX_BUFFER_BYTES = 30 * 16000 * 2` (`LocalWhisperEngine.kt:78-79`) — ≤ ~18 s in practice, ≤ 30 s by
   construction, under Gemma's cap.
2. **Adaptive partial ticks**: the next tick starts no sooner than `max(τ_min, 1.0 × last tick duration)`
   after the previous tick ends (≤ 50 % duty from partials), `τ_min = 1,000 ms`. S26-GPU-class ≈ 1–1.5 s
   refreshes; the Tab ≈ 3–4 s; on a mid-range CPU no tick completes before the utterance closes and
   partials are simply absent — correct, not a failure.
3. **Partial render**: each tick's full text replaces the strip — exactly the existing contract:
   `transcribeStreaming`'s `onNewSegment` "receives the FULL text decoded so far in THIS call"
   (`TranscriptionEngine.kt:228-244`), the service renders replace-only (`transcriptionDeltaText.text = text`,
   `FloatingBubbleService.kt:2961`) throttled at `minIntervalMs = 150L` (`DeltaThrottle.kt:18`). **A Gemma
   tier produces the app's preferred cumulative-replace partial natively — the one clean architectural fit
   in this direction.**
4. **Final at VAD close** (350 ms hangover, `EndpointerTuning.kt:87`; the flatline cut on device audio,
   `:235`, `:259`): one full call, its `Flow<Message>` forwarded as cumulative text through the same
   callback (the typewriter), then the blank delta and the resolved text (`LocalWhisperEngine.kt:582-603`;
   `deliverReleasedText`, `FloatingBubbleService.kt:3008`).
5. **The speech-evidence skip stays load-bearing**: a buffer with `speechMs < MIN_SPEECH_EVIDENCE_MS`
   (192, `EndpointerTuning.kt:197`) resolves `EmptyExpected` without running the backend
   (`SpeechEvidence.kt:29-34`); partial ticks must be evidence-gated too (an open utterance's trailing
   350 ms is silence by definition).
6. **The 25 s finalizer** (not built; comments only at `CommitCadencePolicy.kt:80-81`, `:103-104`) is
   compatible (25 + 3 s retained = 28 s < 30 s, 700 audio tokens) but every second added to the window
   costs the partial loop quadratically — on a Gemma tier the finalizer wants ~15 s, not 25.

**Where it plugs in — two options, one structural gap in both (DOCUMENTED code):**

- **(b1) a `WhisperBackend` inside `LocalWhisperEngine`**: `load(modelPath)` (one `.litertlm`, no
  companion, `TranscriptionEngine.kt:156`); `transcribe(ctx, samples: FloatArray, lang, useVad)` (`:188`;
  the FloatArray is already 16 kHz mono float); `transcribeStreaming` (`:238-244`) for the typewriter;
  `lastSegmentStats` → `null` (`:258`); `release`. Frictions: the callback must run on the invoking thread
  while it holds the process-global `NativeComputeGate` (`:228-237`) — bridge via `runBlocking { flow.collect { … } }`
  on the executor thread (legal; it pins the single native executor for the decode, which is what whisper
  does today); `load` runs inside `NativeComputeGate.serialized {}` and `Engine.initialize()` is "up to
  10 seconds" — a 10 s hold blocking TTS and every other native user, so init belongs in `prewarm()`
  (`TranscriptionEngine.kt:83`).
- **(b2) its own `TranscriptionEngine`** (`FloatingBubbleService.kt:2612-2701` `when (choice)`; wrap in
  `FallbackTranscriptionEngine` for `Lost` rescue): full control of the tick loop, `onDelta` partials,
  one `SegmentOutcome.Text` per seq; the seq-exactly-once contract (`TranscriptionEngine.kt:117-120`) and
  a real `awaitIdle` (`:76-82`) become this engine's to keep.
- **The gap:** `LocalWhisperEngine` calls the backend only from `commit()` → `dispatch()` → `runSegment()`,
  which snapshots and clears the buffer under `bufferLock` (`LocalWhisperEngine.kt:249-275`, `:413-581`;
  `backend.transcribeStreaming` at `:469` and nowhere else); the `TranscriptionEngine` interface (`:11-97`)
  has no peek/preview member and `commitRetainingTailMs` still commits (`:289-300`). The partial loop of
  step 2 needs **a new engine member with a default body** (e.g. `previewTick(): Boolean = false`) driven
  by a service-owned timer, plus the rule that a preview in flight never delays a real commit by more than
  one tick.
- **Partials are dropped today for every non-live session**: `deltaOwnsPreviewStrip(sessionIsLive) = sessionIsLive`
  (`FloatingBubbleService.kt:277`), checked first thing at `:2941`; the in-flight "Transcribing… (N in queue)"
  line owns the strip for local sessions (`:3427-3445`, labels `:288-292`), pinned in
  `InFlightStripTest.kt:16, :25, :128-144`. §4.5.

**What it would honestly feel like (INFERRED from §2.4's cost model):**

| Device class | While you talk | When you pause | Net impression |
|---|---|---|---|
| S26-Ultra-class Adreno GPU | the strip rewrites the whole sentence every ~1–1.5 s; words 1–1.5 s stale; earlier words can change on a rewrite | final ~0.7 s after the hangover for a 10 s sentence, typed at ~40 words/s | "a sentence that keeps redrawing itself, then snaps final" — slower and jumpier than OpenAI/Soniox live, livelier than `multi`'s 6 s floor |
| Tab S10+ (CPU; GPU only if Mali proves correct) | rewrites every ~3–4 s | final ~1.2–2.4 s after the pause | "bursts of text every few seconds" — better than today's `multi` on the same tablet, nowhere near cloud-live |
| Mid-range 6–8 GB phone | **nothing** (no tick completes before the utterance closes) | final 2–6 s after the pause | indistinguishable from a slow batch tier; worse than `multi` on download, RAM and cadence |

The owner should hear this sentence before anything is built: **it is "word for word after you stop",
not "word for word as you speak".**

### 2.4 Quality, size, RAM, cost, licence

**Quality (DOCUMENTED — both papers publish FLEURS; the normaliser caveat is real).** Whisper small from
the Whisper paper's Appendix D.2.4 Table 13 (https://arxiv.org/pdf/2212.04356; read from the PDF text —
the ar5iv rendering returned wrong numbers and was discarded) against Gemma 4 E2B/E4B (tech report Table 7,
https://arxiv.org/html/2607.02770v1, 2026-07-02), on Gemma's 12 languages:

| lang | Whisper small | Gemma 4 E2B | Gemma 4 E4B |
|---|---|---|---|
| **en** | **6.1** | 8.0 | 6.5 |
| ko | 19.6 (WER) | 6.6 (CER) | 5.3 (CER) — not metric-comparable |
| ja (CER) | 12.0 | 10.7 | 7.8 |
| de | 10.2 | 7.6 | 6.1 |
| fr | 15.0 | 10.1 | 8.0 |
| hi | 38.4 | 10.1 | 8.6 |
| es | 5.6 | 4.2 | 3.5 |
| it | 9.8 | 4.1 | 3.2 |
| pt | 7.3 | 5.6 | 4.6 |
| ru | 11.4 | 8.4 | 6.8 |
| ar | 30.6 | 14.3 | 16.2 |
| zh (CER) | 20.8 | 18.7 | 13.6 |

On paper E2B leads on 11 of 12 (Korean excluded: Whisper's Appendix C scores CER only for zh/ja/th/lo/my)
and is **behind on English (8.0 vs 6.1) — the language this app defaults to** (`WhisperModel.kt:459` `DEFAULT_MODEL_ID = "pro"`,
an English tier). Caveats that keep this from being a verdict: Whisper's numbers use the Whisper text
normaliser and Gemma's normalisation is unstated; Whisper's row is fp16 `small`, not the shipped `q5_1`;
no number exists anywhere for `small-q5_1`. **A "better than the 190 MB model" claim must be measured on
the canary WAV plus a dense-speech clip before it ships.** What Gemma structurally lacks regardless:
whisper.cpp's per-utterance language detection (`WhisperBackend.detectedLanguage`, `TranscriptionEngine.kt:201`),
word timing, and a decoder that cannot refuse, moralise or answer in prose (§2.5).

**Size (DOCUMENTED).**

| Tier | Download | ÷ `multi` | ÷ npu-turbo pair |
|---|---|---|---|
| `multi` = ggml-small-q5_1 | 190,085,487 B (`WhisperModel.kt:222`) | 1.0 | 0.18 |
| `npu-turbo` pair | 1,071,685,632 B (`:280`; 775,831,552 + 295,854,080) | 5.6 | 1.0 |
| **Gemma 4 E2B `gemma-4-E2B-it.litertlm`** | **2,588,147,712 B** (card: 2,583 MB) | **13.6** | **2.4** |
| Gemma 4 E2B `-gpu.litertlm` | 2,008,432,640 B (purpose unstated on the card) | 10.6 | 1.9 |
| Gemma 4 E4B | 3.65 GB | 19.2 | 3.4 |

(The gemma map's "342 MB turbo pair" is the `npu` whisper-small pair, `WhisperModel.kt:249`; the plan's
1,072 MB is the code's number.) **Play delivery:** individual asset packs and AI packs are capped at
**1.5 GB compressed** (https://support.google.com/googleplay/android-developer/answer/9859372;
https://developer.android.com/google/play/on-device-ai, 2026-09-01); at the repo's measured ~23 % deflate
on quantised blobs (`docs/superpowers/research/2026-08-29-pad-soc-delivery.md:60`; INFERRED for mixed
2/4/8-bit weights) the file is ≈ 1,993 MB compressed, and it would need a 38–42 % deflate to fit — so it
**cannot ship as one pack**. The routes that exist: the single-file `DownloadManager` path `multi` uses
(`WhisperModelManager.kt:241-341`; `isInstallableByDownload = pairedArtifact == null`, `WhisperModel.kt:433`)
from Hugging Face (the CPU tiers' host, `WhisperModel.kt:107-108` — not self-hosting); the `AssetPackManager`
route (`NpuPackController.kt`) cannot carry it. An N-pack split with on-device concatenation under Play's
30 GB on-demand cumulative cap is buildable but unbuilt. Play requires user confirmation over 200 MB on
cellular either way.

**RAM (DOCUMENTED numbers; INFERRED gate).** Google's published peaks are **text-only** (1024 prefill /
256 decode): E2B **676 MB GPU / 1,733 MB CPU** on an S26 Ultra; E4B 710 / 3,283 MB
(https://developers.google.com/edge/litert-lm/models/gemma-4, 2026-09-04). The often-quoted **~2.86 GB
(2.18 GB GPU + 0.68 GB CPU)** from #2202 was measured on a **text-only tool-calling suite — "No audio input
involved"** (Pixel 10a, Mali-G715, 0.10.x); **no audio-inclusive Android RAM figure exists in any source**,
and since the audio encoder loads on demand the audio-resident number can only be higher. The app's
convention compares `minRamBytes` against `totalMem` with `7_000_000_000` meaning "8 GB-class"
(`WhisperModel.kt:51`, `:236`, `:464-465`; `WhisperModelManager.kt:228-233`). A Gemma row needs
`minRamBytes ≥ 7e9` at minimum and **12 GB-class is the honest gate**, because the app holds the model
inside a foreground service with a floating bubble while *another* app is in the foreground — the worst
LMK position an app can occupy; its largest resident today is turbo's ~1 GB pair.

**Cost per second of audio (INFERRED; every slope a LOWER bound).** `T(t) = (25·t + p)/P + (w·k·t)/D + c`
with 25 audio tokens/s (audio doc), p ≈ 25 prompt tokens, w·k ≈ 3.3 text tokens per speech-second
(150 wpm × 1.3 — assumption), P/D from Google's **text** benchmark (S26 Ultra GPU 3,808 / 52.1, CPU 557 /
46.9; HF card Linux "Arm 2.3 & 2.8 GHz" 260 / 35 — but Google's page labels the same row "Linux (RTX 4090)",
so its CPU identity is contested; Raspberry Pi 5 133 / 7.6). The 305M audio encoder's cost is in no
published number, so every slope is a lower bound:

| Device / backend | slope (s compute per s audio) | finals-only T(10 s) | partial loop feasible? |
|---|---|---|---|
| S26 Ultra GPU | 0.070 | 0.71 s | yes — 34 % duty at τ 1.5 s |
| S26 Ultra CPU | 0.115 | 1.20 s | thin — 59 % |
| Tab S10+ CPU (assumed 0.5–1.0× S26 CPU) | 0.115–0.23 | 1.2–2.4 s | τ must grow to ~4 s (65 %) |
| mid-range 6–8 GB phone (band) | 0.19–0.62 | 1.9–6.4 s | **no** — finals only, 2–6 s after the pause |

**The structural point survives any constant:** whisper's cost is a fixed encoder window (`multi` F ≈ 2.3 s
at the 512 floor, `CommitCadencePolicy.kt:134`); Gemma's is decode, linear in transcript length — fastest
where whisper is wasteful (short sparse utterances), slowest where whisper is efficient (dense continuous
dictation, this app's case). **No measured Gemma-audio latency on any Android device exists in any source
found.** A cadence row for a Gemma tier cannot be one number for the fleet (S26-GPU-class ≈ 1.0–1.2 s,
Tab-CPU-class 1.7–3.4 s, mid-range 2.7–9.1 s under the 0.70 rule); until a row exists an unknown id falls
to 8,000 ms (`CommitCadencePolicy.kt:230-231`).

**Licence (DOCUMENTED).** Apache 2.0 on weights and runtime — no Gemma Terms of Use overhang (the 3n
generation carried it); attribution only. Note the churn cost: the HF card records a 2026-05-05
re-download requirement for speculative decoding — `.litertlm` files are runtime-versioned artefacts, and
a LiteRT-LM bump can invalidate a 2.6 GB download the user already paid for.

### 2.5 Integration — what a Gemma row hits in this codebase (DOCUMENTED)

| Seam | Today | For a Gemma tier |
|---|---|---|
| `WhisperModel` row | 8 rows (`WhisperModel.kt:165-297`) | new id, `fileName = gemma-4-E2B-it.litertlm`, `approxBytes ≈ 2.59e9`, `sha256` pinned, `scope = MULTILINGUAL`, `minRamBytes ≥ 7e9` (12 GB honest), `gated = false` (a RAM + runtime gate, not a SoC gate), `pairedArtifact = null` |
| **`isCpuFallbackEligible`** | `NpuModelSpec.forTier(id) == null && id != "ultra" && pairedArtifact == null` (`:401-402`); `hasCpuFallback` (`:414-415`); the KDoc already names a mel-bin count as the real fix (`:391-394`) | **Bug-in-waiting:** a Gemma row passes all three clauses and would be offered as the 80-bin mel donor and as the NPU tier's CPU fallback — and `cpuTierModelPath()` tries the **selected** tier first (`WhisperModelManager.kt:195-201`), so a selected-and-installed Gemma tier would be the first donor candidate; `NpuWhisperBackend.kt:328` → `:352 WhisperNative.initMelOnly(melSourcePath)` would read 64 KB of a `.litertlm` head as a ggml, and `:904` would try to load it as the CPU fallback. The catalog needs a `runtime` field (`GGML` / `QNN` / `LITERT_LM`) **before** such a row exists. |
| `pickableFor` / `ONE_TIER_ID` | turbo-capable devices see only turbo + `alsoOfferedIds` (`:316`, `:368-375`); on a MediaTek tablet the plain lineup returns (`:373-374`) | owner ruling: beside turbo via `alsoOfferedIds`, or only where turbo is not; a 2.6 GB size badge and chooser copy need a design pass |
| `NpuBackendSelector.backendFor` | answers `WhisperNativeBackend` for non-Qualcomm (`:143-144`); "there is no backend factory in this app" (`:12-19`) | a third arm keyed on the catalog `runtime` — a re-spec, not a patch |
| Gate | Qualcomm-only census | untouched; the Gemma gate is `totalMem ≥ minRamBytes` **and** a runtime probe (`Engine.initialize()` on `Backend.GPU()` with a canary utterance, falling to `Backend.CPU()` — the `GpuPolicy` canary pattern, `TranscriptionEngine.kt:264-268`) |
| Cadence | tier-keyed table (`CommitCadencePolicy.kt:186-233`) | a per-device measured floor from the tier's own `SegmentTiming` line (`LocalWhisperEngine.kt:504-514`) — one row cannot serve the fleet (§2.4) |
| Language | `effectiveLang = if (backend.detectsPerUtterance) lang else languagePin.languageFor(lang)` (`LocalWhisperEngine.kt:446-447`); the pin is fed from `backend.detectedLanguage(ctx)` only when `!detectsPerUtterance` (`:539-546`); defaults `null` / `false` (`TranscriptionEngine.kt:201`, `:226`); `LanguagePin.languageFor = sessionLanguage ?: pinned` (`LanguagePin.kt:35`) | **Undocumented, not impossible:** Google's template names `{LANGUAGE}` and no LID output is documented, but a hands-on (E2B under MLX, secondary) transcribed near-verbatim with "Transcribe this audio" and no language named (https://simonwillison.net/2026/Apr/12/mlx-audio/, 2026-04-12). Options: omit the language clause and let the model decide (undocumented reliability); pass the device locale (contradicts "auto"); a two-turn identify-then-transcribe (doubles decode); explicit-language-only. **Owner ruling.** Per-utterance language labels — the product goal — are exactly what Gemini Live gives for free (§3). |
| Guards | `TranscriptText.clean` strips `[…]`, listed `(…)` sound words and code fences only (`TranscriptText.kt:36-40`); `HallucinationPolicy.shouldBlank` is an exact-match stock-phrase gate under an nsp vote (`HallucinationPolicy.kt:141-142`) | **A new class of guard.** An instruction-tuned decoder can refuse or answer in prose — `gemma4:e4b` audio under Ollama auto-entered thinking mode and returned empty or "I'm sorry, I could not transcribe that audio." (https://github.com/ollama/ollama/issues/16584, 2026-06-06, open; fix PR #16879 unmerged at fetch; Ollama's default, but the failure class transfers) — can loop and fabricate on a 9.9 s clip (https://note.com/bhrtaym/n/n13e7d880d7c6, 2026-04-13, secondary), and 3n's processor padded short clips by **repetition** to a fixed 188-token = 30.1 s window, producing phantom repeats (Google staff, https://huggingface.co/google/gemma-3n-E4B-it/discussions/42, 2026-01-08; whether LiteRT-LM pads Gemma 4 the same way is undocumented — and `MIN_SPEECH_MS = 300`, `EndpointerTuning.kt:152`, admits 0.3 s utterances). Needed: `thinkingConfig` off, a `systemInstruction` pinning verbatim-only output, a refusal/meta-answer detector before `SegmentOutcome.Text`, a minimum-clip rule, and the Silero pre-filter + evidence gate kept mandatory. whisper.cpp can produce none of these outputs. |
| Strip ownership | live-only (`FloatingBubbleService.kt:277`) | §4.5 |
| Delivery | `download()` single file (`WhisperModelManager.kt:252`) | §2.4 |
| Dependency | — | `com.google.ai.edge.litertlm:litertlm-android` (Google Maven; a Kotlin dependency, no JNI of our own; AAR size unverified); GPU needs `uses-native-library` entries for `libvndksupport.so` + `libOpenCL.so` (LiteRT-LM Android page) — the app already declares `libOpenCL.so` (`AndroidManifest.xml:66-68`) |

### 2.6 First experiment — zero code, then one probe app; the kill lines

**Step 1 (owner, ~30 min, nothing built):** install the Google AI Edge Gallery on the Tab (Android 12+),
download Gemma 4 E2B, open Audio Scribe. Four inputs, recorded once: (a) the existing canary WAV; (b) a
**dense-speech** 25 s clip with numerals and contractions ("it's 1,029.3 dollars, don't you think"); (c) a
1 s and a 3 s clip; (d) 15 s of room tone. Run each on the GPU and CPU settings if exposed. Stopwatch
tap-to-first-token and tap-to-last-token (TTFT and T(t) — the two numbers that turn §2.4 from INFERRED
to DOCUMENTED on the actual device). Watch for mangled numerals on GPU, repetition on (c), any text on (d),
any sentence *about* the audio rather than *from* it. Then
`adb shell dumpsys meminfo com.google.ai.edge.gallery` after a transcription — read `summary.private-other`,
not `nativePss` (Android 14+ reports native memory there; #2202 finding 8). **Control, same session:** the
ML Kit Basic sample or Android's own live-caption/on-device recognizer, so the owner sees word-for-word
next to Gemma's burst feel on the same hardware before ruling.

**Step 2 (engineer, 1 day PC + 1 Tab session — the same `litertlm-probe` app as §1.4 E3), G2.1:** load
`gemma-4-E2B-it.litertlm` on `Backend.GPU()` then `Backend.CPU()`; feed 16 kHz mono audio (float32 first,
PCM16 second — record which the runtime accepts) with Google's ASR prompt; measure per backend:

| Measure | Clip | Kill line |
|---|---|---|
| correctness | canary + dense clip | numeral/contraction corruption on GPU ⇒ CPU-only |
| per-chunk latency | the Tab's own 17.6 s chunk | **> 6.1 s ⇒ it loses to the tier it was meant to replace** (memory note, 2026-09-04) |
| TTFT + decode tok/s | same | for the record (the card's S26 GPU figures: 0.3 s / 52.1 tok/s) |
| peak RSS with audio loaded | after the first audio turn | > ~3 GB ⇒ 12 GB-class only, `minRamBytes ≥ 8e9` (INFERRED) |
| short utterances | 0.3 s, 1 s, 2 s words | phantom repetition ⇒ a minimum-clip rule the endpointer lacks |
| silence / room tone | 15 s of the E11 bed | any prose or stock phrase ⇒ the refusal detector is mandatory |
| language | a Spanish clip with `{LANGUAGE}` = English, and one with no language named | decides the auto-language ruling |

Only if the 17.6 s chunk lands under ~2 s on either backend, the guards are clean and RSS is under ~3 GB
does a 40-line Kotlin spike inside the app follow — its first jobs are the byte format and the 10 s init on
the compute gate.

### 2.7 The models that actually stream word-for-word (for the owner's ruling; DOCUMENTED)

| Model | Size | Languages | Streaming shape | Runtime on Android | Licence |
|---|---|---|---|---|---|
| **sherpa-onnx streaming Zipformer** | en int8 encoder 68 MB (fp32 250 MB) + 1.3 MB decoder + 254 KB joiner; zh-en bilingual int8 174 MB; zh int8 67–154 MB; ko 121 MB (https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html, 2026-09-09) | per model — en, zh, zh-en, ko, bn; **no streaming model covers three or more languages** | true streaming transducer, sub-second partials, RTF 0.06–0.12 on the docs' CPU | **already in the APK**: `OnlineRecognizer`, `OnlineRecognizerConfig`, `OnlineStream`, `OnlineTransducerModelConfig`, `OnlineZipformer2CtcModelConfig`, `Vad`, `SileroVadModelConfig`, `SpokenLanguageIdentification*` in `classes.jar`, with `Java_com_k2fsa_sherpa_onnx_OnlineRecognizer_{newFromFile,createStream,decode,decodeStreams,isReady,isEndpoint,getResult,reset,delete}`, `OnlineStream_{acceptWaveform,inputFinished,…}`, `Vad_*`, `SpokenLanguageIdentification_compute` present in the shipped `libsherpa-onnx-jni.so` (4,710,728 B) + `libonnxruntime.so` (21,688,920 B), kept by `app/build.gradle.kts:167-172`, `:809`; sole consumer today `tts/TtsEngine.kt:11-14`. **Zero new native payload.** A new `TranscriptionEngine` (shape b2). | Apache-2.0 |
| ML Kit GenAI Speech Recognition, **Basic** | 0 bytes shipped | 15 locales (en-US GA, 14 beta) | streaming partial → final, "Raw, headerless 16-bit PCM" 16 kHz mono | "most Android devices with API level 31 and higher"; **alpha**, no SLA (page updated 2026-09-01) | Google terms |
| ML Kit **Advanced** (Gemini Nano) | 0 bytes | 21 locales | same | **Pixel 10/11 only**; "more devices in development"; Nano 4 on MediaTek/Qualcomm accelerators "later this year" (https://android-developers.googleblog.com/2026/04/AI-Core-Developer-Preview.html, 2026-04-02) | alpha |
| Kyutai STT 1B en_fr | ~1B params | English + French only | delayed-streams, "0.5 second delay", 24 kHz | PyTorch / transformers only — **no Android or ONNX path** (https://huggingface.co/kyutai/stt-1b-en_fr) | CC-BY 4.0 |
| Moonshine streaming-tiny | 34M / 44 MB f32 | English only | streaming, 80 ms lookahead | per the gemma map (https://huggingface.co/moonshine-ai/moonshine-streaming-tiny; not re-verified by a refuter) | MIT |

None of these is Gemma. All of them do what the owner's sentence asks for. Gemma buys quality and breadth
after each pause; Zipformer buys words-as-you-speak in one language at a time. **This document does not
schedule a Zipformer tier**; it records the trade so the ruling is made with it in view.

### 2.8 Owner needs — six rulings before code, and the measurement

1. **What the tier is for.** "Premium 12 GB-class multilingual tier" is defensible; "the tier for phones
   that cannot run turbo" is refuted (§2.4: 2.4× turbo's disk, ≥ 8 GB-class RAM, finals-only on mid-range).
2. **Is word-for-word streaming a requirement?** If yes, Gemma is out and §2.7 is the shortlist.
3. **Auto-language on a Gemma tier** (§2.5): omit the clause, device locale, two-turn detect, or
   explicit-only.
4. **The 2.6 GB download:** HF single-file (the existing route) or an N-pack split; and whether a 2.6 GB
   cellular download belongs in a paid app's onboarding.
5. **The preview strip** on a local session: partials or the queue-depth line (§4.5).
6. **Turbo devices:** Gemma in `alsoOfferedIds` beside turbo, or only where turbo is not.
7. The §2.6 measurements — the only thing that moves §2.4 from INFERRED to DOCUMENTED.

---

## 3. DIRECTION 3 — Gemini Live: wiring `gemini-3.5-transcribe-live` into the live stack

### 3.1 What the primary sources say (DOCUMENTED; all fetched 2026-09-09, page dates as shown)

| Fact | Value | Source |
|---|---|---|
| Model / status | `gemini-3.5-transcribe-live` — **GA 2026-08-26**: "Gemini 3.5 Transcribe generally available (GA): Released two dedicated speech-to-text models" (`gemini-3.5-transcribe` non-streaming, `-live` streaming) | https://ai.google.dev/gemini-api/docs/changelog |
| Nature | "a dedicated, low-latency speech recognition pipeline rather than a conversational agent"; with `response_modalities=["TEXT"]` + `input_audio_transcription` "the model returns only text transcriptions, not audio or model-generated dialogue" | https://ai.google.dev/gemini-api/docs/live-api/live-transcribe (updated 2026-08-26) |
| Endpoint | `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent` (`?key=${API_KEY}` on the doc page) | live-transcribe; https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket (2026-09-04: "Authentication is handled by including your API key as a query parameter in the WebSocket URL") |
| **Auth — the header route** | Google's own Python SDK builds the URI with **no query string** and connects with `additional_headers` carrying **`x-goog-api-key`** (`google/genai/live.py:970-996`, `:1117-1119`; `_api_client.py:842-846`); the JS SDK uses `?key=` because browsers cannot set upgrade headers (`src/live.ts:169-185`) | https://raw.githubusercontent.com/googleapis/python-genai/main/google/genai/live.py; https://github.com/googleapis/js-genai/blob/main/src/live.ts |
| Ephemeral tokens | recommended for client-to-server ("You should use them when accessing the Live API directly from client-side applications"); minted by `POST /v1beta/auth_tokens` **with the real key**; "only compatible with Live API" | https://ai.google.dev/gemini-api/docs/ephemeral-tokens (2026-07-30). **INFERRED:** irrelevant to BYOK — the user *is* the key owner and the app has no backend; same exposure class as the Soniox config frame (`SonioxRealtimeProtocol.kt:173`) |
| Setup | first and only first message: `{"setup":{"model":"models/gemini-3.5-transcribe-live","generationConfig":{"responseModalities":["TEXT"]},"inputAudioTranscription":{"languageCodes":[],"customVocabulary":[],"mode":"VERBATIM"},"realtimeInputConfig":{"automaticActivityDetection":{"disabled":false}}}}`; **"Clients should wait for a `BidiGenerateContentSetupComplete` message before sending any additional messages"** — Google's SDK awaits `ws.recv()` after sending setup before yielding the session (`live.py:1120-1125`) | live-transcribe; https://ai.google.dev/api/live |
| `AudioTranscriptionConfig` | `languageCodes[]` ("If omitted or empty, defaults to automatic language detection"; "handles code-switching"), `customVocabulary[]` (up to 1,000 terms; best ≤ 100), `mode` (`VERBATIM` default \| `SMART`: "strips conversational filler words, stuttering, false starts", formats lists/numbers/dates), `wordTimestamp`, `diarization` — **the last two unsupported on Live** | live-transcribe; reference |
| Audio | "Raw 16-bit PCM at 16kHz (mono, little-endian)", `audio/pcm;rate=16000`, `{"realtimeInput":{"audio":{"data":"<base64>","mimeType":"audio/pcm;rate=16000"}}}`; chunk guidance conflicts: "chunks of 100ms (1,024 to 2,048 frames)" (live-transcribe; 100 ms at 16 kHz is 1,600 frames) vs "Send audio in chunks of 20ms to 40ms" and "Send small chunks (20ms - 100ms)" (best-practices, 2026-09-04) | live-transcribe; https://ai.google.dev/gemini-api/docs/live-api/best-practices |
| `realtimeInput` extras | `audioStreamEnd` (bool, "e.g. because the microphone was turned off"), `activityStart` / `activityEnd` (only with automatic VAD disabled), `text`, `video`, deprecated `mediaChunks` | reference |
| Inbound | `BidiGenerateContentServerMessage` = optional `usageMetadata` **plus** exactly one of `setupComplete` \| `serverContent` \| `toolCall` \| `toolCallCancellation` \| `goAway` \| `sessionResumptionUpdate` — `usageMetadata` can co-occur with `serverContent`. `serverContent` fields: `inputTranscription`, `interimInputTranscription`, `outputTranscription`, `modelTurn`, `turnComplete`, `generationComplete`, `interrupted`, `waitingForInput`, `interactionStatus`, `groundingMetadata`, `urlContextMetadata`, deprecated `speechState`. Each transcription is `{ text, languageCode }` and **nothing else** | reference |
| Interim vs final | `interim_input_transcription`: "low-latency, speculative partial hypotheses updated while the speaker is actively talking"; `input_transcription`: "the finalized transcript emitted when the speaker pauses, the turn completes, or speech is finalized … the model's authoritative transcription of that speech segment"; reference: `inputTranscription` is "sent independently of the other server messages and there is no guaranteed ordering", `interimInputTranscription` "subject to frequent updates". **Cumulative vs fragment is never stated** (P3). Google's cookbook run against this model (SMART, `en-US`) produced **two** `input_transcription` finals for one ~10 s file, the second after `audio_stream_end=True` and **not repeating the first** — finals are per-segment, several per stream, and `audioStreamEnd` flushes one immediately | live-transcribe; reference; Google cookbook `Get_started_transcribe.ipynb` cell 28 output |
| Timestamps / diarization | "Word-level timestamps are not supported over the Live API. The Live API emits utterance-level timestamps (interim_input_transcription and input_transcription)" — the parenthetical names the two **events**; there is **no time field** on any Live server message (`audioTimestamp` appears only in the setup's *unsupported* list); "Speaker diarization is not supported in live streaming sessions" | live-transcribe; reference; model card https://ai.google.dev/gemini-api/docs/models/gemini-3.5-transcribe ("file processing only") |
| VAD | Automatic (default, server start/stop); Hybrid (server start "with prefix audio padding", client `audio_stream_end` "as an immediate turn finalization prompt", server VAD "as an automatic fallback"); Manual (`disabled: true` + `activityStart/End`). Tunables `startOfSpeechSensitivity`, `endOfSpeechSensitivity`, `prefixPaddingMs`, `silenceDurationMs` ("recommended 500-800ms"; server default ≈ 800 ms) | live-transcribe; https://ai.google.dev/gemini-api/docs/live-api/capabilities (2026-09-04) |
| **Caps** | **"Live transcription sessions support continuous streaming for up to 10 minutes"** (live-transcribe); "The lifetime of a connection is limited as well, to around 10 minutes. When the connection terminates, the session terminates as well" and "Without compression, audio-only sessions are limited to 15 minutes" (session-management, 2026-09-04); "reconnect the call every 10 minutes" (ephemeral-tokens); "10 minutes per session" (model card, August 2026). **The operative number is 10 minutes per connection**; the 15-minute figure is the general Live API's session cap and is not stated to apply to this model at all. `GoAway { timeLeft }` precedes termination "as ABORTED"; `sessionResumption` handles are "valid for 2 hr"; whether this model honours resumption or `contextWindowCompression` is **UNKNOWN** (P5) | https://ai.google.dev/gemini-api/docs/live-api/session-management; reference |
| Error channel | Not documented. Community reports agree on the shape "upgrade succeeds, then a close frame": 1008 "Your API key was reported as leaked" (https://github.com/googleapis/python-genai/issues/1710, 2025-11-17); 1008 "Operation is not implemented, or supported, or enabled" (forum 114644); 1007 on a malformed first message (forum 108994); mid-session 1007 "CONTENT_TYPE_AUDIO … not supported for this model configuration", staff 2026-08-28 (forum 179817). Google's SDK converts a `ConnectionClosed` on the setup reply into `APIError(code, reason)` (`live.py:1126-1133`). **A plain wrong-key example was not found** (P2) | as cited |
| **Silent stalls** | on **this exact model**: "sessions stay connected but silently stop returning transcripts" — socket ESTABLISHED, sends succeed, no `GoAway`, no 429/5xx; posted 2026-08-31; Google staff 2026-09-02 "We are actively looking into this."; unresolved | https://discuss.ai.google.dev/t/gemini-3-5-transcribe-live-sessions-stay-connected-but-silently-stop-returning-transcripts/180230 |
| GoAway regression (sibling model) | on `gemini-3.1-flash-live-preview`: GoAway at ~540.3 s with `timeLeft "50s"` until 2026-08-03; from 2026-08-04 hard **1006** closes at **523.1 s and 533.1 s with no GoAway**; partial recovery 08-14/16; from 08-17 aborts at ~151 s with close reason "The operation was aborted."; stale resumption handles accepted then failing; no staff reply; last post 2026-09-07 | https://discuss.ai.google.dev/t/regression-since-aug-4-goaway-no-longer-sent-before-live-api-connection-close-gemini-3-1-flash-live-preview-connections-now-die-silently/177402 |
| Freezes / 409 (sibling) | 8–51 s output freezes then a burst; undocumented handshake 409s after abrupt exits + quick restarts | https://discuss.ai.google.dev/t/gemini-3-5-live-translate-preview-in-production-paid-tier-measured-concurrency-dashboard-409s-vs-real-409s-silent-stalls-and-session-birth-degradation-data-6-questions/180489 (2026-09-02, no replies) |
| Languages | model card and changelog: "85+ languages", utterance-based auto-detection; the live-transcribe table lists **81** unique region-qualified BCP-47 codes (e.g. `cmn-Hans-CN`, `yue-Hant-HK`); every example is region-qualified — bare `"de"` vs `"de-DE"` is **UNKNOWN** (P7) | live-transcribe; model card |
| Pricing | `gemini-3.5-transcribe-live` paid: "$3.50 or $0.005/min (audio)" in + "$21.00 or $0.004/min (text)" out; footnote: "Estimated pricing is based on 25 audio tokens per second for input and 175 text tokens per minute for output, for an effective blended rate of ~$0.009 per min"; free tier "Free of charge", "Used to improve our products": **Yes** (free) / No (paid). Batch `gemini-3.5-transcribe`: $0.003 + $0.002 ≈ $0.005/min | https://ai.google.dev/gemini-api/docs/pricing (2026-09-08) |
| Batch path (not this direction) | non-streaming is `POST /v1beta/interactions` with `transcription_config.language_codes` and a Files-API URI, not `:generateContent` + inline base64 (which `GeminiStt` does today, `GeminiStt.kt:51-66`, `:119-124`); a forum report of HTTP 200 with **empty output on all three REST paths** for the dedicated model, unresolved | https://ai.google.dev/gemini-api/docs/transcribe; https://discuss.ai.google.dev/t/gemini-3-5-transcribe-returns-empty-transcription-http-200-zero-output-tokens-on-all-documented-rest-paths/179937 (2026-08-28) |

### 3.2 The protocol mapping, member by member

The seam (`app/src/main/java/com/whispereverywhere/transcription/live/RealtimeProtocol.kt`): `endpoint :40`,
`upgradeHeaders :44`, `tolerant4xxRetry :48`, `bind :51`, `bootstrap :55`, `onAppend :59`, `onCommit :64`,
`onText :67`, `classifyFatal :70`, `reset :73`; `SessionControl` = `send :24` + `rotate :26`. The transport
builds the request from `protocol.endpoint` and adds only `protocol.upgradeHeaders(apiKey)`
(`RealtimeTransport.kt:244-245`), opens the audio gate the moment the bootstrap frames are enqueued
(`:291-292`), hands inbound TEXT to `protocol.onText` (`:297`), declines inbound BINARY (`:300`), logs
status codes only (`:305`). New files mirror the Soniox pair: `transcription/live/GeminiRealtimeProtocol.kt`
(`object GeminiLiveEvents` codec + `class GeminiRealtimeProtocol`) and its test; codec rules carried over —
kotlinx-serialization only, `java.util.Base64`, nothing from `android.*` (`RealtimeEvents.kt:12-17`;
`unitTests.isReturnDefaultValues = true`, `app/build.gradle.kts:227`), `explicitNulls = false`
(`SonioxRealtimeProtocol.kt:34`).

| Member | Gemini's answer |
|---|---|
| `endpoint` | the constant BidiGenerateContent URL, **no key** |
| `upgradeHeaders(apiKey)` | `listOf("x-goog-api-key" to apiKey)` — `ProviderCatalog.kt:52-53` already holds `authHeaderName = "x-goog-api-key"`, `authHeaderValue = { it }`; the ElevenLabs/OpenAI pattern (`ElevenLabsRealtimeProtocol.kt:89-92`, `OpenAiRealtimeProtocol.kt:37-40`); the key never becomes a field; `SonioxNoLogDisciplineTest`'s reflection sweep passes trivially. **Gated on P1** (strong prior: it is what Google's SDK does). Fallback if the gateway refuses it from OkHttp: a **default-body** member `fun endpointFor(apiKey: String): String = endpoint` on the interface, consulted by the transport at `:244`, plus a redaction rule — never a constructor-injected key (the discipline `SonioxRealtimeProtocol.kt:107-114` attacks by reflection) |
| `tolerant4xxRetry` | `false` (the OpenAI beta-header hack only) |
| `bootstrap(apiKey, language)` | one `Frame.Text` = the setup frame, pinned byte-exact (the `RealtimeEventParserTest.outbound_..._shape_is_exact` pattern, `RealtimeEvents.kt:64-65`): auto ⇒ `"languageCodes":[]` (the page's own form); `"de"` ⇒ `["de"]` (or the region form P7 chooses); `responseModalities:["TEXT"]`; `automaticActivityDetection.silenceDurationMs: 500` (the documented floor and the value the OpenAI row already uses, `RealtimeEvents.kt:113`, so the two providers' cadence is comparable; the ~800 ms default would land finals ~300 ms later); `mode` `VERBATIM` (matches whisper and `TranscriptText.clean`; `SMART` is a real dictation differentiator — owner call, later, per-provider); `customVocabulary` omitted; `sessionResumption.handle` only if P5 says yes (phase 2). Also resets the ready gate, the pre-setup ring, the pending fold buffer and `openedAtNanos` |
| `onAppend(pcm16k)` | **no resample** (16 kHz PCM16 is native; unlike OpenAI's 16→24 k, `OpenAiRealtimeProtocol.kt:52-57`); **pair two 512-sample capture frames into one 1,024-sample / 64 ms message** — satisfies both Google pages (§3.3.6); base64 in JSON ≈ 43.8 kB/s (2,732 chars + 73 B envelope at 15.6 msg/s), so `MAX_OUTBOUND_BYTES` = 2 MiB (`RealtimeTransport.kt:368`) is ≈ 48 s of un-drained audio before a shed; **ready gate**: until `setupComplete`, append to a ring capped at 2 s (64,000 B) and return `true`, flush in order on `setupComplete` outside the lock, return `false` only on overflow (§3.3.5); stamp `lastAppendNanos` and a cheap energy figure (max \|sample\|) for the watchdog and the quiet-frame rotation; `nowNanos: () -> Long = System::nanoTime` injected for tests (the `ReconnectScheduler` move, `RealtimeTransport.kt:31-33`) — no thread, no timer |
| `onCommit()` | `true`, a no-op: live sessions run no client endpointer (`LiveTurnPolicy.runClientVad(sessionIsLive) = !sessionIsLive`, `RealtimeTurnPolicy.kt:19`) and the engine never enqueues a client commit in server mode (`LiveTranscriptionEngine.kt:273`) — identical to the three shipped providers. Hybrid VAD would make it `{"realtimeInput":{"audioStreamEnd":true}}` (phase 2) |
| `onText(text)` | the dispatch table below |
| `classifyFatal(code)` | handshake status → kind: `400 → MODEL_UNAVAILABLE` (the body is off-limits on a handshake, `RealtimeTransport.kt:304`, so 400 is ambiguous between bad key and bad setup; the key is pre-validated over REST by `KeyValidator.kt:64-68`, which already maps Gemini's `400 + API_KEY_INVALID`, so "our setup/URL is wrong" is the likelier reading and must latch visibly — the Soniox rule at `SonioxRealtimeProtocol.kt:259-261`); `401 → INVALID_KEY`; `403 → FORBIDDEN`; `409 → null` (the orphaned-session handshake 409 — retry, never latch); `429 → OUT_OF_CREDIT` (copy: "rate limit or credit" — the rate-limits page lists no Live/transcribe rows); `5xx / null → null` |
| **`classifyClose(code, reason)` — new default-body member** | `null` on the interface (the other three inherit it). Gemini: `1008` + "api key" → `INVALID_KEY`; `1008` + "not found" \| "not supported" \| "not implemented" \| "not enabled" → `MODEL_UNAVAILABLE`; `1008` + "quota" \| "RESOURCE_EXHAUSTED" → `OUT_OF_CREDIT`; `1007` → `MODEL_UNAVAILABLE` (our payload; cannot self-heal); `1000/1001/1006/1011/1012/1013` → `null` (INFERRED mapping from the reports in §3.1; the strings are matched case-insensitively and the rule set is pinned by tests; the reason text never crosses the seam or a log) — §3.3.9 |
| `reset()` | clear the fold buffer, the pre-setup ring, the last preview, the resumption handle, the per-session rotation counters, `ready` (per-open turn state is also cleared in `bootstrap` — the Soniox split, `:185-192` vs `:283-287`) |

**Inbound dispatch (`onText`):**

| inbound | adapter action | listener call |
|---|---|---|
| `{"setupComplete":{}}` | `ready = true`; flush the pre-setup ring outside the lock | — (`onConnected` already fired, `RealtimeTransport.kt:294`) |
| `serverContent.interimInputTranscription.text` | replace mode: preview = text; fold mode: `pending.append(text)`, preview = `pending` | `sink.onDelta("", preview)` — preview only, never binds (`LiveTranscriptionEngine.kt:337-341`) |
| `serverContent.inputTranscription.text` | `id = ids.incrementAndGet()`; final = text (replace) or `pending + text` (fold); clear `pending`; stamp `lastInbound` | `sink.onCommitted(id)` **then** `sink.onCompleted(id, final)` — the ElevenLabs shape (`ElevenLabsRealtimeProtocol.kt:111-124`): the engine rotates the mirror and allocates the seq on the first (`LiveTranscriptionEngine.kt:343-356`, wired to `fallback.commit()` at `FloatingBubbleService.kt:2698`), resolves it exactly once on the second (`:358-364`); empty text → `EmptyExpected`, silent (`:413-432`) |
| `serverContent.inputTranscription.languageCode` | **discarded in the first cut** — no listener slot (`RealtimeTransport.kt:74-111`), `SegmentOutcome.Text` carries text only | — |
| `serverContent.modelTurn` | **ignore, always** — so a model-id change can never type a chatbot reply into the user's field | — |
| `turnComplete` / `generationComplete` / `interrupted` / `waitingForInput` / `interactionStatus` / `speechState` | ignore; stamp `lastInbound` | — |
| `{"goAway":{"timeLeft":…}}` | `control.rotate()` once per open | — |
| `{"sessionResumptionUpdate":{"newHandle","resumable"}}` | retain iff `resumable` (phase 2) | — |
| `usageMetadata` (alone or alongside) | ignore, but never let its presence short-circuit `serverContent` parsing | — |
| anything else / malformed / an `error` object | `null` → ignore (the forward-compatible `RealtimeEventParser` rule, `RealtimeEvents.kt:144-171`; in-band JSON errors are undocumented, so `onErrorEvent` / `mapErrorCode` (`LiveTranscriptionEngine.kt:373-377`) will essentially never fire) | — |

Engine, transport (except the one new member's wiring), fallback wrapper, bubble and key store are
provider-agnostic and unchanged: `REALTIME_STT_PROVIDERS` is derived from `supportsStreaming`
(`FloatingBubbleService.kt:147-148`), the CLOUD_LIVE arm builds `LiveTranscriptionEngine` / `RealtimeTransport`
/ `FallbackTranscriptionEngine` around `protocol` (`:2666-2699`), and `LiveServerDrivenTurnTest.kt:85-106`
already proves the `onDelta → onCommitted → onCompleted` contract provider-agnostically.

### 3.3 The design decisions

1. **Reply suppression: do not build it.** The dedicated model emits only transcription text; the general
   native-audio models "only support `AUDIO` response modality" (capabilities page), so the "silence trick"
   would mean a system instruction, a spoken reply discarded from `modelTurn`, and `inputTranscription`
   mined out of the exchange — INFERRED ≈ $0.037/min ≈ 4× the dedicated model (Gemini 2.5 Flash Native
   Audio rates $3.00/1M in, $12.00/1M out; 32 audio tokens/s per https://ai.google.dev/gemini-api/docs/tokens,
   2026-09-04; ~5 s replies at ~16 turns/min — the multiplier is illustrative, the modality sentence is
   the reason), plus reply latency, refusals and dialogue leaking into the field. Keep one rule from that
   world: ignore `serverContent.modelTurn` unconditionally.
2. **VAD: automatic first, hybrid as phase 2.** Automatic is the default, needs no client cooperation, and
   is exactly the server-driven contract the 2026-07-31 inversion built (`LiveTranscriptionEngine.kt:74-86`,
   `:343-356`; `FloatingBubbleService.kt:2669-2672`). Consequence the owner will see: chunk cadence is
   Gemini's (speech end + `silenceDurationMs` 500 + RTT + finalisation), not the local path's 350 ms
   hangover — the same trade the OpenAI row makes. **Hybrid** (`audioStreamEnd` at our own hangover, "zero-
   latency turn finalization", boundaries we control, and a way to enforce the not-yet-built 25 s
   finaliser) costs: a per-provider client-VAD rule in `LiveTurnPolicy` (`RealtimeTurnPolicy.kt:18-20`),
   a new default-body member that forwards the endpoint as a *hint* without allocating a seq (the seq
   stays at `inputTranscription`), and the resurrection of client-commit machinery the providers had
   deleted (`OpenAiRealtimeProtocol.kt:59-64`). Whether audio keeps transcribing after `audioStreamEnd`
   on the same socket is **UNKNOWN (P4)** — the cookbook shows it flushes a final immediately; the field
   description implies the stream resumes. Stop path stays the tested local rescue
   (`lastLiveEngine?.finishServerTurns()`, `FloatingBubbleService.kt:3142`; `LiveTranscriptionEngine.kt:549-555`;
   `LiveStopTailRescueTest.kt:117, :145`) — an `audioStreamEnd`-and-wait drain is phase 2.
3. **Language.** Selection reaches `bootstrap` as bare ISO-639-1 or `null` for auto
   (`PreferencesManager.kt:178-181`); wire it as Soniox wires `language_hints` (`SonioxRealtimeProtocol.kt:50, :99`):
   present ⇒ `["<code>"]`, null ⇒ `[]`. Auto works mid-session and across code-switching — the one thing
   the local path cannot do (`LanguagePin` latches per session, `LocalWhisperEngine.kt:446-447`, `:539-546`).
   Bare vs region form: **P7**; if bare codes are ignored, the spec's "minimal exceptions map" (spec `:82-84`),
   not a table. **Per-utterance `languageCode` is free and homeless** — surfacing it needs a new
   default-body listener callback plus a `SegmentOutcome` side channel touching all four protocols and
   `RealtimeProtocolContractTest`; discard it in the first cut and scope "show or route by detected
   language" as its own change once transcripts have been seen. It is the cheapest route to the
   per-chunk-language product goal among all three directions. **Prerequisite in fact, not only in
   politics:** the cloud-"en" leak — the session language is computed from the *local* installed model's
   scope and forced to `"en"` for any ENGLISH-scope tier regardless of which engine transcribes
   (`FloatingBubbleService.kt:2838-2843`, `engine.connect(lang, …)` at `:2846`); a Gemini live user with
   `pro` installed would send `languageCodes:["en"]` forever. Spec §1 and owner ruling #3 order it first.
4. **Auth: header, zero seam change, P1 to confirm** (§3.2). Logging discipline holds either way:
   `RealtimeTransport` logs `"realtime http $code"` / drop counts only (`:256`, `:305`); the shared OkHttp
   client has no interceptors (`net/HttpTransport.kt:332-336`); the live client adds only timeouts and a
   20 s ping (`:387-396`). If the `endpointFor` fallback is ever taken, that discipline becomes load-bearing
   in a way it never was for the other three, and the release audit must say so.
5. **Wait for `setupComplete`.** The reference requires it and Google's SDK enforces it; the transport
   opens the audio gate on *send* with no inbound-ack mechanism (`RealtimeTransport.kt:291-292`). Protocol-
   local fix: the ready gate with a 2 s ring (§3.2). Returning `false` from the first frame instead would
   be worse than it looks: one `false` sheds the **entire** server turn from the last boundary to the next
   final, not just the pre-setup frames (`LiveTranscriptionEngine.kt:310`, `:324`, `:343-350`).
6. **Frame size: pair to 1,024 samples; do not coalesce to 100 ms.** The app's native 32 ms frames
   (`LiveTranscriptionEngine.kt:26`, `:200`) already satisfy the best-practices page; 64 ms satisfies both
   pages; the bandwidth saving of pairing is ~1.2 kB/s. Flush the odd half-frame on `rotate()`/`reset()`
   outside the lock (the deadlock discipline, `SonioxRealtimeProtocol.kt:132-133`, `:206`).
7. **Interim semantics: ship both fold modes behind one constructor boolean**, default set by P3. The repo
   contains both idioms — fold (`OpenAiRealtimeProtocol.kt:23-35`, `:71-74`) and replace
   (`ElevenLabsRealtimeProtocol.kt:113`, `SonioxRealtimeProtocol.kt:229-232`) — and the strip renders
   replace-only (`FloatingBubbleService.kt:2961`), so either way it grows rather than flickers. INFERRED
   reading: a "hypothesis" that is "updated" is a whole string (replace), and finals are per-segment
   (cookbook) — good for the one-sentence-per-chunk goal.
8. **The live default moves every existing Gemini user.** `sttLiveMode` defaults **true**
   (`PreferencesManager.kt:378`) and `decideEngineChoice` routes `CLOUD_LIVE` for any provider with
   `supportsStreaming` (`FloatingBubbleService.kt:119`; set derived at `:147-148`; the one call site passes
   the pref straight through, `:2575-2580`). A Gemini-only user could never have opted out: the live
   affordance renders only `if (provider.supportsStreaming)` (`CloudProvidersScreen.kt:361`, `:210`). So
   flipping `ProviderCatalog.kt:60` moves them from ~$0.005/min batch to ~$0.009/min live on the paid tier,
   or to free-with-training. **Owner ruling: keep the default, or seed `sttLiveMode = false` once for
   Gemini users.** A release-note item either way.
9. **Close codes — the seam change that is mandatory, and the transport fact underneath it (DOCUMENTED
   code).** `scheduleReconnect()` is called from exactly two places: `rotate()` (`RealtimeTransport.kt:189`)
   and `onFailure` (`:330`). `onClosing` (`:335-341`) only completes the handshake; **`onClosed` (`:343-353`)
   nulls the socket and calls `listener.onDisconnected()` — it schedules nothing.** OkHttp 4.12.0 (pinned,
   `app/build.gradle.kts:897-909`) delivers a server close frame as `onClosing → onClosed`, not `onFailure`
   (`RealWebSocket.kt` `onReadClose` ~350-353; `failWebSocket → onFailure` ~431 is the exception path only;
   https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/ws/RealWebSocket.kt).
   Therefore **today any post-upgrade close frame — 1008 bad key, 1007 bad setup, 1000/1001 at the cap
   without GoAway, or the sibling model's "The operation was aborted." — ends the cloud half of the session
   with ZERO reconnects**: the socket stays null, every `sendAppend` returns false (`:219`), the session
   rides the local engine, and the stop-time latch toast reads a null `lastFatal()`
   (`FloatingBubbleService.kt:3227-3240`) — no toast. (If a server instead drops TCP so the close surfaces
   as `onFailure`, `onOpen`'s counter reset at `:274` makes it an **unbounded 500 ms loop**, not six-then-
   stop — INFERRED; which branch a given close takes is P2 territory.) The gemini map's and the analysis's
   "six reconnects, 23.5 s, then local" is wrong in both. The `Listener.onDisconnected` KDoc ("a reconnect
   has been scheduled", `:99-104`) is true only for the `onFailure` path, and **no test drives
   `onClosing`/`onClosed`** (`RealtimeTransportTest.kt:111-425` — all drop tests go through `onFailure`).
   **Fix:** `classifyClose(code, reason)` (default body `null`); `onClosing`/`onClosed` consult it;
   non-null → no reconnect, `listener.onFatal(kind, code)`; **null → `onDisconnected()` AND `scheduleReconnect()`**
   (the branch the analysis's sketch missed); one transport test per branch, driven through
   `onClosing`/`onClosed`; the KDoc corrected. One interface line, one transport method, two tests; the
   other three providers inherit `null` and keep today's behaviour on `onFailure`.
10. **Rotation: three independent, protocol-local triggers.** (a) `goAway` → `control.rotate()`;
    (b) **proactive at ≤ ~500 s of connection age** — not the analysis's 540 s and not the spec's 9:30
    (570 s): the sibling model's silent deaths were at **523.1 s and 533.1 s**, so a 540 s deadline has
    negative margin against the very data cited; taken at the first quiet frame (energy below a speech
    threshold) so the cut lands in a pause — INFERRED improvement; the timestamp comes from `openedAtNanos`
    set in `bootstrap`, the check runs in `onAppend`, no timer thread; (c) the stall watchdog (11). What a
    healthy rotation costs, from the code: `rotate()` closes with 1000, nulls the socket, shuts the gate,
    `scheduleReconnect()` (`RealtimeTransport.kt:182-190`); `reconnectAttempts` is 0 after the last open
    (`:274`, pinned by `RealtimeTransportTest.kt:368`) so the delay is `delayFor(0)` = 500 ms (`:118-119`,
    `:126`), then a fresh handshake and one `setup → setupComplete` round trip (INFERRED ~1 s total) during
    which `sendAppend` returns false; the engine's `onDisconnected` snapshots the tail under a fresh seq and
    resolves pending turns Lost so the mirror rescues them (`LiveTranscriptionEngine.kt:379-396`). Net
    user-visible cost per rotation: one sentence from whisper instead of Gemini plus ~1 s of shed audio
    folded into it; nothing dropped; an hour of dictation (~7 rotations at 500 s) never approaches
    `DEFAULT_MAX_RECONNECTS = 6` (`:361`) because the ceiling counts consecutive failures only.
    **Resumption** is not needed for correctness — the ledger is ours and every open sends a full `setup`
    (`RealtimeProtocol.kt:35-37`); carry `newHandle` in a field cleared by `reset()` only if P5 says the
    model honours it, noting the thread above reports stale handles accepted then failing.
11. **The stall watchdog is a design requirement, and its action needs a guard.** A stall is the one
    failure the fallback architecture cannot see by construction: sends succeed, no `onCommitted` means no
    seq is allocated (`LiveTranscriptionEngine.kt:343-350`), nothing is pending, nothing fires; the stalled
    audio sits in the mirror and is rescued at stop as one long local segment — delayed, not lost, but
    invisible during the session. Rule: if frames with speech energy have been sent for **~8 s** (INFERRED
    from the sibling model's 8–51 s freezes; interim events otherwise arrive "rapidly") with no inbound
    `serverContent`, `rotate()` once; silence never triggers it (Gemini sends nothing during silence under
    automatic VAD). **The hazard (INFERRED from code + OkHttp source):** `rotate()` uses
    `webSocket?.close(1000, null)` (`:186`); OkHttp 4.12 cancels an unanswered close only after
    `CANCEL_AFTER_CLOSE_MILLIS = 60 s` (`RealWebSocket.kt` ~407-410, ~447 → `failWebSocket` → `onFailure`
    ~431), and the transport's `onFailure` (`:302-333`) and `onClosed` (`:343-353`) null
    `this@RealtimeTransport.webSocket` **without any socket-identity check** (`:328`, `:347`) — so on a peer
    that has stopped responding, the OLD socket's late `onFailure` (up to 60 s after the rotation) clobbers
    the REPLACEMENT socket's reference, fires a spurious `onDisconnected` (another whisper sentence + shed),
    schedules yet another open, and leaves the clobbered socket feeding `protocol.onText` (`:297`) — a late
    duplicate final becomes possible. Pre-existing for Soniox's 413 rotation (`SonioxRealtimeProtocol.kt:238-249`).
    **Fix:** guard the callbacks with `if (webSocket !== this@RealtimeTransport.webSocket) return`, or
    `cancel()` rather than `close()` in the stall path.
12. **The free-tier badge and the training sentence travel together.** Owner ruling #1 wants the badge
    (spec `:97-99`) and pricing confirms "Free of charge"; the catalog already records
    `trainsOnDataByDefault = true` with the reason (`ProviderCatalog.kt:62-64`) and the privacy page states
    it (`docs/privacy.html:110`). The honest row is "Free tier available — and on the free tier Google may
    use your audio to improve its products." Owner wording.
13. **Leave `GeminiStt` on `gemini-3.6-flash` + `:generateContent`** (`GeminiStt.kt:119`) out of this
    change: spec §3's "same seam" is wrong (§3.1, last row), and the dedicated model's REST paths have an
    unresolved empty-output report. Note `GeminiStt`'s `language` parameter is accepted and unused
    (`:45`) — the batch language work rides with the routing work, per ruling #3, not with live.

### 3.4 Cost per minute (DOCUMENTED rates; derivations shown)

| provider / model | source | list | per minute | per hour | vs Gemini live |
|---|---|---|---|---|---|
| Gemini `gemini-3.5-transcribe-live`, paid | pricing page (2026-09-08) | $0.005 in + $0.004 out (25 audio tok/s × $3.50/1M; 175 text tok/min × $21/1M) | **$0.009** | $0.54 | 1.0× |
| same, free tier | same | $0 | **$0** | $0 | audio "used to improve our products" |
| OpenAI `gpt-transcribe` (the row the app pins, `RealtimeEvents.kt:122-132`) | https://developers.openai.com/api/docs/pricing | "$0.0045/minute" | $0.0045 | $0.27 | Gemini 2.0× |
| OpenAI `gpt-live-transcribe` (rejected by the app) | same | "$0.017/minute" | $0.017 | $1.02 | Gemini 0.53× |
| ElevenLabs Scribe v2 Realtime | https://elevenlabs.io/pricing/api | "$0.39" / hour | $0.0065 | $0.39 | Gemini 1.38× |
| Soniox `stt-rt-v5` | https://soniox.com/pricing (June 2026) | "$0.12/hr" | $0.0020 | $0.12 | Gemini 4.5× |

**Open mic bills wall-clock, not speech:** under automatic VAD the app streams continuously while
recording (`LiveTranscriptionEngine.kt:208-221`), so a 10-minute connection of mostly silence bills
10 × $0.005 = $0.05 of input; an hour of dictation ≈ $0.30 input + ≤ $0.24 output. Not Gemini-specific (the
app already streams continuously to the other three) — but Gemini is the priciest paid live row, so this
is where the shape first hurts; the honest levers are hybrid VAD (later) or the free tier. Do not gate
audio under automatic VAD: the server's `prefixPaddingMs` needs the lead-in. The 3.8 spec's "~$0.005/min"
(spec `:49`) is the **batch** figure and must not appear on the live row; `PreferencesManager.kt:370-376`'s
per-provider cost comment gains a Gemini line.

### 3.5 Task list (days are INFERRED sizing; the Soniox protocol + two suites, 307 + 384 + 114 lines, landed on one calendar day, 2026-07-31, so history does not calibrate this)

| # | task | days | depends on |
|---|---|---|---|
| T0 | **PC probes P1–P9** with a free-tier key and a ~150-line Python `websockets` script (no device, no build): header vs `?key=` auth; bad-key status/close code; interim cumulative-vs-fragment and finals per utterance; audio after `audioStreamEnd`; `sessionResumption` / `contextWindowCompression` accepted?; the 10-minute close (GoAway? `timeLeft`? code?); bare vs region codes; a 10-minute continuous stream for stalls; `gemini-3.5-transcribe` batch text for a short WAV (for the parked spec §3) → `docs/measurements/2026-09-xx-gemini-live-probes.md` | 1.0 | — |
| T1 | **Cloud-"en" leak fix** (spec §1): extract `sessionLanguageFor(installedModel, selection, engineKind)` beside the service's pure helpers; JVM truth table | 0.5 | — |
| T2 | `GeminiLiveEvents` codec: setup (auto / language / SMART / handle), audio frame, inbound parse (all wrappers incl. `usageMetadata` co-presence), exact-shape tests | 1.0 | T0 (P3, P7) |
| T3 | `GeminiRealtimeProtocol`: header auth, ready gate + 2 s ring, 1,024-sample pairing with flush, both fold modes, boundary + final mapping, `modelTurn` ignore, GoAway rotate, **≤ ~500 s** quiet-frame proactive rotation (deadline re-derived from P6 before the copy is written), 8 s speech-gated watchdog, `classifyFatal` / `classifyClose` maps, `reset`; test suite (§3.6) | 2.0 | T2 |
| T4 | **Seam + transport:** `classifyClose(code, reason)` default-body member; `onClosing`/`onClosed` consult it; **transient close → `onDisconnected()` + `scheduleReconnect()`**; **socket-identity guard (or `cancel()` in the stall path)**; fix the `Listener.onDisconnected` KDoc (`:99-104`); transport tests that drive `onClosing`/`onClosed` for both branches and the late-callback clobber | 1.0 | — |
| T5 | **Flip:** `ProviderCatalog.kt:60` → `true`, rewrite `:57-59`; `FloatingBubbleService.kt:2664` → `GeminiRealtimeProtocol()`; rewrite the stale comments at `FloatingBubbleService.kt:100-103` and `:143-144` and `PreferencesManager.kt:370-371`; re-spec the pinned tests `ProviderCatalogTest.kt:41`, `EngineSelectionTest.kt:164-168, :175`, `CloudProvidersScreenLogicTest.kt:409-413`, `ModeDashboardLogicTest.kt:55-68`, `LiveTurnPolicyTest.kt:16-20` (comment); provider-row copy: free-tier badge **paired with** the training sentence, "~$0.009/min paid", "connections rotate automatically before the 10-minute cap"; the live-default ruling applied | 1.0 | T3, T4 |
| T6 | Docs: `docs/privacy.html`, `docs/PLAY-DECLARATIONS.md` (Gemini already disclosed; add that live streams continuously), release notes | 0.25 | T5 |
| T7 | Owner device acceptance on the internal track (§3.6) | 0.5 | T5 |
| | **Total ≈ 7.25 days + 1 contingency** (header auth refused → `endpointFor`, +0.5; fragment interims with multi-final turns, +0.5) | | |

Ordering follows owner ruling #3 (spec `:103-107`): the leak fix first, Gemini live last in 3.8. Tests,
modelled on `SonioxRealtimeProtocolTest` (`RecordingControl` / `RecordingListener`, `:24-49`): wire facts;
setup shapes for auto / language / SMART / handle; ready-gate buffering, overflow shed, re-close on
rotation; 1,024-sample pairing + odd-frame flush + base64 round-trip; interim → `onDelta` only; final →
`onCommitted` then `onCompleted` with fresh ids; empty final → empty not failed; `modelTurn` ignored even
with text parts; `usageMetadata` co-presence; both fold modes + the preview/final character-for-character
invariant (`SonioxRealtimeProtocolTest.kt:218-228`); GoAway once per open; no rotation before the deadline,
quiet-frame wait, deadline reset on bootstrap; watchdog fires on speech-with-no-inbound, never on silence,
resets on any inbound; `classifyFatal` / `classifyClose` maps; close-reason content never crosses the
seam; the no-log reflection sweep (`SonioxNoLogDisciplineTest.kt:65-86`); the two new transport tests.

### 3.6 Acceptance on the phone (owner, Play internal track; SoC-agnostic)

| # | do | expect |
|---|---|---|
| A1 | save a free-tier Gemini key | validated (`KeyValidator`); the live-mode row appears for Gemini (`CloudProvidersScreen.kt:361` is `supportsStreaming`-derived) |
| A2 | dictate three sentences, language auto | the strip grows as you speak; each sentence lands once; no `[…]`; no duplicated words; `WE-DIAG realtime session live`; three `perceived:` lines (`FloatingBubbleService.kt:3018-3021`) |
| A3 | set German, dictate | setup carried `["de"]` (or the P7 form); German text |
| A4 | auto; switch language mid-session | both languages transcribed (auto per utterance) |
| A5 | dictate 11 minutes | at least one rotation before 10:00 (rotate / `realtime http` lines); at most one sentence visibly from whisper per rotation; sentence count = spoken count |
| A6 | airplane mode for 20 s mid-dictation | sentences in the gap arrive from whisper; backoff lines; reconnect; no toast; no stall |
| A7 | revoke the key, start a session | toast "Key rejected" within ~2 s (the close-1008 path), session continues on-device |
| A8 | stop mid-sentence | `finalize-timing: cloud-drain=` small; tail sentence present (local rescue) |
| A9 | silent room 2 minutes | no rotation (watchdog energy-gated), no markers |
| A10 | read AI Studio usage after A5 | minutes billed ≈ wall-clock of the session |
| A11 | device-audio (MediaProjection) session | server VAD cuts device-audio turns as for the other providers (`FloatingBubbleService.kt:2687-2692`) |
| A12 | the same sentences on the OpenAI row | latency and cadence side by side (`silenceDurationMs` 500 on both) — listing copy waits for this number (spec `:93`) |

### 3.7 Not possible, possible, unknown

**Not possible on the Live path (DOCUMENTED):** word-level timestamps (batch has them, up to 30 min per
file); speaker diarization; client-controlled turn boundaries under automatic VAD (only hybrid or manual
give the client the cut); a single connection longer than ~10 minutes (whether a *session* can span
connections for this model is P5 and not needed); ephemeral tokens without a backend.

**Possible (DOCUMENTED):** true partials during speech (`interimInputTranscription`); per-utterance
detected language (`languageCode`); automatic code-switching; 81 listed BCP-47 codes ("85+" claimed);
`SMART` clean-up mode; up to 1,000 custom-vocabulary terms; native 16 kHz PCM16 input; header and query
auth; a free tier.

**Unknown until probed (T0):** P1 header auth from OkHttp; P2 the bad-key surface (HTTP at upgrade vs
101-then-close, and `onClosed` vs `onFailure`); P3 interim cumulative-vs-fragment and finals per
utterance; P4 audio after `audioStreamEnd`; P5 resumption / compression on this model; P6 GoAway
`timeLeft` and the close code at the cap; P7 bare language codes; P8 stall cadence over a 10-minute
stream; P9 whether the dedicated batch model returns text at all (parked spec §3). No item marked UNKNOWN
reaches code before its probe.

---

## 4. THE PLAN

### 4.1 The tier ladder

**What exists today (DOCUMENTED).** The lineup is decided by `WhisperCatalog.pickableFor`
(`WhisperModel.kt:368-375`): when `ONE_TIER_ID` (= `npu-turbo`, `:316`) is in the offered set the chooser
shows that one card; every other device gets the plain lineup. The offered set is installed gated tiers ∩
hardware capability (`WhisperEverywhereApp.kt:160-166`), and capability is Qualcomm-only by construction
(`NpuGate.kt:54`, `:82`). Cadence is per tier (`CommitCadencePolicy.kt:186-233`).

| Rung | Tier | Artefact | Floor (ms) | Status |
|---|---|---|---|---|
| 1 | `npu-turbo` | large-v3-turbo w8a16 QNN pair, 1,071,685,632 B (`WhisperModel.kt:280`) | 2,000 / 3,200 under backpressure (`CommitCadencePolicy.kt:112`, `:131`) | in production on four Qualcomm families (`app/device_targeting_config.xml:2-35`); F = 1.89 s on the Fold6 |
| 2 | `multi` / `pro` | ggml-small-q5_1 190,085,487 B / small.en 190,098,681 B | 6,000 (`:134`, `:229`) | the universal floor; **what the Tab runs** (17.6 s chunk ≈ 6.05 s) |
| — | `npu` | whisper-small QNN pair 358,244,352 B (`:249`), hidden by one-tier | 1,200 | reserved for the streaming arc |
| — | `eco` / `base` / `extreme` / `ultra` | retired; `ultra` = turbo q5_0 CPU, retired + unsupported | — | CPU turbo owner-dropped 2026-09-04 |
| 3 | cloud batch | OpenAI / ElevenLabs / Soniox / Gemini `generateContent` | 3,000 (`:163`) | with local fallback (`FloatingBubbleService.kt:2693`) |
| 4 | cloud live | OpenAI Realtime / ElevenLabs / Soniox | server-driven (`:2672`) | with local fallback + server-turn rotation (`:2698`); Gemini excluded (`ProviderCatalog.kt:60`, `FloatingBubbleService.kt:2664`) |

**The ladder to build toward (proposal):**

```
 accelerator tiers          ┌─ npu-turbo   Qualcomm Hexagon (QNN)              SHIPPING
 (turbo, one card,          └─ mtk-turbo   MediaTek APU via LiteRT NeuroPilot   GATED: E0 → E3 → E4 (§1.4); none passed
  two vendors)
 premium local (12 GB+)        gemma-e2b   Gemma 4 E2B audio via LiteRT-LM      GATED: G2.1 (§2.6); a typewriter-after-endpoint tier, never word-for-word
 streaming local (optional)    zf-stream   streaming Zipformer via sherpa-onnx   OWNER RULING (§2.7): the only on-device word-for-word shape; per-language
 universal floor               multi/pro   whisper-small CPU                    SHIPPING; the Tab's tier today (§4.6: cadence slack)
 cloud batch                   4 providers                                       SHIPPING
 cloud live                    OpenAI / ElevenLabs / Soniox / + Gemini live      Gemini = build 88 (§4.3)
```

**Why the accelerator rung splits by vendor instead of gaining a fifth census family (DOCUMENTED).** The
census is a Qualcomm-QNN concept — per-family HTP stub in `lib/` and skel in `assets/`
(`app/build.gradle.kts:174-200`), a `NpuSocFamily` row with `skelAsset` and `htpVersion`
(`NpuFleetCensus.kt:27-35`), and `NpuGate.familyFor` refuses any manufacturer but Qualcomm before it looks
at the model string (`NpuGate.kt:82`). A MediaTek tier is a different runtime, a different artefact, a
different capability probe and a different decode loop (§1.5) — a **parallel gate and a parallel pack
family**, selected in `NpuBackendSelector.backendFor` (`NpuBackendSelector.kt:136-148`), never a widened
allowlist.

**Why Gemma is a premium rung, not the fallback rung (DOCUMENTED sizes, INFERRED placement).** A phone that
"cannot run turbo" is, in this fleet, an 8 Gen 2 or older Qualcomm, a Tensor, an Exynos or a MediaTek —
the population where 6–8 GB RAM is common. Holding ~2–3 GB behind a foreground-service bubble on those
devices is the wrong side of every trade (§2.4). The devices that *can* hold it (12 GB tablets, 16 GB
flagships) are mostly the ones that already have turbo — so Gemma's real slot is *above* turbo on RAM and
*beside* it on quality, on hardware where the user picks quality over footprint: the Tab S10+ itself.
It overlaps direction (1) rather than substituting for it.

**What each local candidate can and cannot do (DOCUMENTED unless marked):**

| | `npu-turbo` (QNN) | `mtk-turbo` (LiteRT) | `gemma-e2b` (LiteRT-LM) | `zf-stream` (sherpa-onnx) | `multi` (whisper.cpp) |
|---|---|---|---|---|---|
| Partials during speech | none (`NpuWhisperBackend.kt:714-741`) | none expected (same shape) | none from the API; delayed cumulative rewrites via re-transcription at multi-second ticks (§2.3) | **yes** (`OnlineRecognizer` / `OnlineStream`) | per native segment, cumulative (`TranscriptionEngine.kt:228-244`), dropped for non-live sessions (`FloatingBubbleService.kt:2941`) |
| Tokens out while decoding | no | no | **yes** (`Flow<Message>`) — a typewriter after the endpoint | n/a (true streaming) | no |
| Per-utterance language ID | yes (`detectsPerUtterance = fallbackBackend == null`, `NpuWhisperBackend.kt:762`) | same policy code reusable | **no documented primitive**; owner ruling (§2.5) | per model; `SpokenLanguageIdentification` (whisper-based) is in the same AAR | session latch (`LanguagePin`) |
| Word timestamps | no | no | no | yes (transducer) | segment-level |
| Silence hallucination guard | `HallucinationPolicy` + speech-evidence gate (4.3.2) | reusable | **a new class of guard** (refusals, prose, loops — §2.5) | emits nothing on silence (INFERRED from the model class) | native Silero pre-filter |
| Languages | 100 | 100 | 140+ pretrain; audio evaluated on 12 | **per model** (en, zh-en, ko, zh, bn) | 100 |
| Download | 1,072 MB | TBD (fp16 turbo ≈ 1.6 GB-class, over the pack cap — INFERRED) | 2,588 MB | en int8 ≈ 70 MB; zh-en int8 ≈ 190 MB | 190 MB |

### 4.2 Sequence — measurement first; the first two weeks

Owner device time is the scarce resource. **No product code for (1) or (2) is written before a number
exists; (3) proceeds without any device until its sheet.**

| Day | Who | What | Output |
|---|---|---|---|
| **1** | owner (~1 h) | Upload 87 → internal track; H1/H2 on the Fold6 (memory note: "promote when H1+H2 pass"); promote; `git push`. Create and restrict a Gemini key in AI Studio. On the Tab: **E0** (the three adb reads, §1.4). | 87 in production; E0 recorded in `docs/measurements/` |
| **1–3** | engineer (PC) | **T0**: Gemini probes P1–P9 with a Python websocket script and the free key, including a > 10-min run for P6/P8 | `docs/measurements/2026-09-xx-gemini-live-probes.md` |
| **2–7** | engineer | **T1** the leak fix; **T4** the seam + transport work (independent of the probes); **T2/T3** codec + protocol once P1/P3/P7 are in; **T5** the flip + copy + re-specced tests; **T6** docs; identity 88 / name per ruling; suite; two-stage AAB | 88 on the internal track by day 6–7 |
| **3–7** | engineer (PC) + one Tab session | The throwaway **`litertlm-probe`** app (own `applicationId`, debuggable): LiteRT-LM dependency; a screen that loads a chosen `.litertlm` on a chosen backend, streams a WAV/PCM file as `Content.AudioBytes`, prints TTFT, tok/s, wall time, `Debug.MemoryInfo` summary. Run **E3** (Gemma3-1B MT6989 on `Backend.NPU`) then **G2.1** (Gemma 4 E2B on GPU, then CPU) with the canary WAV, the dense clip, the 17.6 s chunk, 0.3/1/2 s words, 15 s room tone, one wrong-language clip, one no-language clip; plus the owner's zero-code Gallery pass (§2.6 step 1) if not already done | E3 go/no-go; the G2.1 table filled; the byte-format answer |
| **7–10** | engineer (Ubuntu) | LiteRT AOT toolchain (`ai-edge-litert` + `-sdk-mediatek`, Bazel 7.4.1); AOT-compile `whisper_base_30s_f32.tflite` for MT6989; run `encode` on the Tab via `CompiledModel` + `Accelerator.NPU` in the probe app; ≥ 20 warm + two cold runs; record whether the runtime accepted the AOT bytecode or fell back to JIT; **a JIT control run**; optionally **E5** (the turbo `.tflite` on LiteRT CPU and GPU) in the same session | **E4** against §1.4's direction-finder table |
| **8–12** | owner (2 short sessions) | The Gemini live sheet A1–A12 on the Fold6 from the track (§3.6) | rows pass/fail; promote 88 when they pass |
| **10–14** | owner + engineer (optional) | **E2** (the in-app bench with the approved Tab sideload; the turbo q5_0 number for the record and `multi`'s F at the floor, §4.6) and **E6** (Vulkan, after the `CEIL_DIV` patch) | two numbers for the tablet's own tier |
| **12–14** | owner | **Decision session**, numbers on the table: (1) MediaTek turbo — go only if E0, E3 and E4 all pass, else record and close; (2) Gemma — go only as a 12 GB-class premium tier if G2.1 passes on GPU or beats 6.1 s on CPU, with a delivery decision (§4.3); (3) the strip-ownership rule — build it if (2) or the 4.4 fast-partials shape is ruled in (§4.5); (4) the Zipformer tier — wanted or not (§2.7); (5) the 88 name and the Gemini live default | the next milestone list |

Deliberately **not** in the two weeks: any `MtkWhisperBackend` code; any Gemma tier code in the product;
the 25 s finalizer; the per-seq journal; the batch `GeminiStt` swap; anything on the S23 Ultra or the
Galaxy XR (parked, owner 2026-09-09).

```
4.0  ship 87 (upload, H1/H2, promote, push main)                          owner, ~1 h
 │
 ├─ (3) T0 probes (PC, free key) ──► T1 leak fix + T4 seam ──► T2/T3 protocol ──► T5 flip ──► 88 to the track ──► sheet ──► promote
 │
 ├─ E0 three adb lines on the Tab ──┐
 │                                  ├─► throwaway litertlm-probe app on the Tab:
 ├─ Gallery zero-code pass (owner) ─┤     E3   Gemma3-1B-MT6989 on NPU   → direction (1) alive?
 │                                  └─►   G2.1 Gemma-4-E2B audio          → direction (2) alive?
 │                                            │
 │                                            └─ E4 whisper-base AOT for MT6989 (Ubuntu) + JIT control → encode ms → §1.4 table
 │
 ├─ strip-ownership rule (§4.5) — only when a local streaming tier is ruled in
 │
 └─ E2 / E6 on the Tab (approved sideload) — optional, for the record
```

### 4.3 Versioning and release implications

Conventions inherited from the train (DOCUMENTED): every AAB consumes a versionCode
(`ReleaseIdentityTest.kt:8-45` narrates 81→87); the name moves when what the user sees changes;
`verifyNpuPacks` gates every bundle on the pack payload matching the census (`app/build.gradle.kts:795-804`)
and is deliberately not wired to `assembleDebug`; the device-targeting XML lives inside the bundle, so a new
device group is a new AAB.

- **Build 88 — Gemini live + the language-leak fix.** Pack shape unchanged: no native code, no new runtime,
  no asset pack (`settings.gradle.kts:23` and `device_targeting_config.xml` untouched). Surface: the
  existing Gemini key row (`ProviderCatalog.kt:61` `keyHelpUrl`), the existing live toggle, the owner-ruled
  free-tier badge paired with the data-use sentence. **Name: proposed 4.4.0** (a provider gains a live mode
  and existing Gemini users change behaviour); **4.3.4** is the patch-style alternative if the owner keeps
  "4.4" for the streaming split — owner rules. Release-note items: Gemini live on by default for Gemini
  users (or not — ruling); connections rotate before the 10-minute cap; free tier trains; ~2× OpenAI live
  per minute on paid. Audit item: the review must state whether the key ever rides a URL (it does not on
  the header route) and that `RealtimeTransport` never logs one.
- **A MediaTek turbo tier — a new AAB with a new pack module, and a fourth runtime.** If §1.4's gates pass:
  a new on-demand pack module (`mtk_turbo`) carrying the AOT-compiled model (a different file from the QNN
  pair — a new catalog row or a per-family artefact indirection; `ONE_TIER_ID` must learn that the turbo
  card has two vendors); the LiteRT + MediaTek runtime libraries in the base module (500 MB cap) or a
  feature module (the first in this app if chosen); a new device group **only when a second MediaTek SoC
  variant ships** (groups select variants inside one pack, `2026-08-29-pad-soc-delivery.md:88-95`); a
  parallel `MtkGate` + probe, a second census concept for `verifyNpuPacks`, the `MtkWhisperBackend` with
  its own decode loop, PODAI (beta) if AI packs are used. **Minor version (4.5.x or later), internal track,
  a Tab device sheet.** The quantisation answer (§1.5) precedes all of it.
- **A Gemma tier — the delivery problem comes before the tier.** 2,588 MB does not fit one pack (§2.4);
  the existing single-file HF `download()` path or an N-pack split with on-device concatenation; a
  `runtime` field in the catalog before the row exists (§2.5); `minRamBytes ≥ 7e9` (12 GB honest); the
  chooser copy and a 2.6 GB badge; `com.google.ai.edge.litertlm:litertlm-android` as a Kotlin dependency.
  **A minor, after 88, after G2.1's number, and never on a critical path.**

### 4.4 Owner actions and rulings — consolidated

| # | Action / ruling | Direction | When |
|---|---|---|---|
| O1 | Upload 87, run H1/H2, promote, push `main` | train | day 1 |
| O2 | Create + restrict a Gemini API key (free tier) — https://ai.google.dev/gemini-api/docs/api-key (2026-09-02: dormant unrestricted keys are blocked from 2026-05-07) | (3) | day 1 |
| O3 | **E0**: the three adb reads on the Tab | (1) | day 1 |
| O4 | **Ruling: the live default** for existing Gemini users (keep `sttLiveMode = true`, or seed `false` once for Gemini) | (3) | before T5 |
| O5 | **Ruling: the 88 name** (4.4.0 vs 4.3.4) | (3) | before the AAB |
| O6 | The zero-code Gallery pass on the Tab (§2.6 step 1, ~30 min) | (2) | days 3–7 |
| O7 | One Tab session for E3 / G2.1 / E4 / E5 (the probe app; no Play-copy uninstall needed) | (1)(2) | days 3–10 |
| O8 | The Gemini live sheet A1–A12 on the Fold6 (§3.6) | (3) | days 8–12 |
| O9 | **Rulings before any Gemma code** (§2.8): tier purpose; word-for-word as a requirement; auto-language; the 2.6 GB download; strip ownership; beside-turbo | (2) | decision session |
| O10 | **Ruling: the Zipformer tier** — wanted or not (§2.7) | (2) | decision session |
| O11 | **Ruling: MediaTek turbo go/no-go** on E0 + E3 + E4 | (1) | decision session |
| O12 | Optional: the approved Tab Play-copy uninstall for E2/E6 (memory note 2026-09-04; **never on the Fold6**) | (1) | days 10–14 |
| O13 | Obtain for E4: an Ubuntu 22.04 host + Bazel 7.4.1; `pip install ai-edge-litert ai-edge-litert-sdk-mediatek` (Apache-2.0); the `litert_npu_runtime_libraries.zip` — no MediaTek account, no NDA, no NeuroPilot SDK | (1) | before day 7 |

### 4.5 The shared streaming plumbing — one pinned rule plus one product ruling

**The contract as pinned today (DOCUMENTED).** `onDelta` is preview-only running text; blank = clear;
committed text arrives only via `onSegmentResolved` (`TranscriptionEngine.kt:109-121`). The strip is
**replace-only** (`transcriptionDeltaText.text = text`, `FloatingBubbleService.kt:2961`) — a producer must
emit cumulative partials (OpenAI's protocol folds its fragments for exactly this reason,
`OpenAiRealtimeProtocol.kt:23-35`). Ownership is **live-only**: `deltaOwnsPreviewStrip(sessionIsLive) = sessionIsLive`
(`:277`), checked at `:2941` (non-live deltas dropped) and at `:3429` (non-live sessions paint
"Transcribing… (N in queue)", labels `:288-292`); finals clear the strip only when deltas own it
(`resolvedTextClearsStrip`, `:348-349`). All four rules are pure and pinned
(`app/src/test/java/com/whispereverywhere/service/InFlightStripTest.kt:16, :25, :128-144`;
`InFlightStripWiringPinTest.kt:12-13`). The local seam already speaks cumulative
(`TranscriptionEngine.kt:228-244`), throttled at ~150 ms (`DeltaThrottle.kt:18`); the local engine emits
one blank delta before a resolution when the segment streamed (`LocalWhisperEngine.kt:582-603`). The
accumulating window is append-only with the seq dropped at drain — a per-seq journal "is the whole
feature" for replace-in-window (memory note, 2026-09-02).

| Direction | Session kind | Partial shape | Strip change | Window change |
|---|---|---|---|---|
| (3) Gemini live | live (`sessionIsLive = true` in the CLOUD_LIVE arm, `FloatingBubbleService.kt:2685`) | interim → `onDelta`; final → `onCommitted` + `onCompleted`; fold in the protocol if fragments | **none** | none |
| (1) MediaTek turbo | local | none on the accelerator arm | none | none |
| (2) Gemma tier | local | cumulative per tick (b1 via `transcribeStreaming`, or b2 via `onDelta`) — matches the seam | **yes**: a local session must be allowed to own the strip, and the queue-depth label it displaces (3.7 G's deliberate choice) needs a home — a second input to the pure rule and one more pinned case | none for a first cut |
| the 4.4 fast-chunks shape / 25 s finalizer | local | fast finals replaced by a 25 s re-decode | same as (2) if rendered via `onDelta` — a design choice not yet made (the only 4.4 reference in the tree is a cadence comment, `CommitCadencePolicy.kt:196`) | **yes** — the per-seq journal |

**Conclusion (INFERRED).** The shared plumbing is the strip-ownership rule. It is needed by (2), by the 4.4
fast-partials shape and — for free — by the CPU `multi` tier, whose partial stream is already produced and
deliberately left running (`FloatingBubbleService.kt:2934-2940`). It is ≤ a day of pure-function work plus
one product ruling: *may a local session's partials displace the "(N in queue)" backpressure label, or do
the two share the strip?* It is **not** needed by (3) or (1). Build it **before** any local streaming tier
and **not** on 88's critical path — unless the owner wants local partials on the Tab in the same release,
a defensible quick win that makes the 190 MB tier *feel* live on every non-NPU device at zero engine cost
(the cost is re-speccing 3.7 G). The per-seq journal stays with the finalizer.

### 4.6 The Tab quick win the ladder already contains (INFERRED, measurement-first)

The tier the Tab runs is `multi`, at a 6,000 ms floor "derived from F = 2.3 s at a 0.70 duty ceiling"
(`CommitCadencePolicy.kt:133-134`) on the **Fold6**. On the tablet's own datum (§1.3: ≤ 24.1–27.1 ms/GFLOP)
`multi` at the 512 floor is F ≈ 113.3 × 0.0241 + 0.227 = **3.0 s** to 113.3 × 0.0271 + 0.227 = **3.3 s**;
under `F/floor + m ≤ 0.70` (m = 0.037 from the floor bench) the floor need only be ≥ **4.5–5.0 s** — the
6 s row carries ~1–1.5 s of slack on this silicon. The table is tier-keyed and pure by design, and a
per-device floor is a design change with its own bench (`2026-08-19-audio-ctx-floor-bench.md` is the
pattern) — but it is the cheapest felt-speed candidate for the tablet, and it needs one measured `encode:`
line for `multi` at the floor on the Tab (E2's sideload session), not an argument. The engine map's
observation stands: on this tablet the felt slowness is the 6 s cadence floor more than the encoder.

### 4.7 What NOT to do

1. **Do not put turbo on the Tab's CPU** (§1.3); the `ultra` row stays retired; the owner dropped it
   2026-09-04.
2. **Do not use NNAPI** as a tier (ORT's EP or the TFLite delegate) even though the shim runs at feature
   level 7: deprecated in Android 15, silent CPU fallback — worse than the CPU tier. One probe run only.
3. **Do not widen `NpuGate.SUPPORTED_SOC_MANUFACTURERS`** (`NpuGate.kt:54`); a MediaTek tier is a parallel
   gate.
4. **Do not write `MtkWhisperBackend` before E0, E3 and E4 pass**; the precedent's worst failure is
   "another model's transcript, with nothing failing" (`NpuWhisperBackend.kt:490-498`).
5. **Do not build the Gemini reply-suppression path**; ignore `serverContent.modelTurn` always.
6. **Do not swap `GeminiStt` batch to `gemini-3.5-transcribe` in build 88** (§3.3.13).
7. **Do not set `wordTimestamp` / `diarization`** in the live setup.
8. **Do not inject the key into a protocol field**; header auth first, `endpointFor` as the fallback.
9. **Do not ship 88 with `onClosed` still reconnecting nothing** — the close frame is Gemini's error
   channel (§3.3.9).
10. **Do not rotate at 540 s or 9:30**; the observed early deaths were at 523 s (§3.3.10).
11. **Do not promise "word for word" from a Gemma tier** in any copy; no primary source supports streamed
    audio into Gemma as of 2026-09-09.
12. **Do not frame Gemma as the tier for phones that cannot run turbo**; it is heavier than turbo by every
    footprint measure (§2.4).
13. **Do not add a Gemma catalog row before the `runtime` field exists** — `isCpuFallbackEligible` would
    admit it as the mel donor (§2.5).
14. **Do not let 88 silently move Gemini users to live** without ruling O4 — a billing and data-use change.
15. **Do not `adb install` anything on the Fold6**; never `:app:installDebug` / `:app:connectedAndroidTest`
    anywhere (build-env note).
16. **Do not run E6 without the `CEIL_DIV` patch**, and never ship `-DGGML_VULKAN=ON`
    (`app/build.gradle.kts:96-98`).
17. **Do not schedule the Zipformer tier** without ruling O10.

---

## 5. RISKS, RANKED

Severity × likelihood, across all three directions; the mitigation names the section that carries it.

| # | Risk | Dir. | Sev × Lik | Evidence | Mitigation |
|---|---|---|---|---|---|
| 1 | **Gemini's error channel is invisible to the transport.** A post-upgrade close frame (bad key 1008, bad setup 1007, a cap close with a reason) lands in `onClosed`, which never reconnects and never latches — the cloud half dies silently with zero retries and no toast | (3) | high × high (users will paste bad keys) | `RealtimeTransport.kt:189`, `:330`, `:343-353`; OkHttp 4.12 `RealWebSocket.kt` close path; §3.3.9 | `classifyClose` + reconnect-on-transient-close + tests that drive `onClosing`/`onClosed` (T4) — **mandatory before 88** |
| 2 | **Silent stalls on `gemini-3.5-transcribe-live`** — staff-acknowledged 2026-09-02, unresolved; the one failure the fallback cannot see (every send succeeds, no seq allocated) | (3) | high × medium | forum 180230; `LiveTranscriptionEngine.kt:310`, `:343-350`; §3.3.11 | speech-gated 8 s watchdog + a socket-identity guard or `cancel()` so the rotation on a stalled peer cannot clobber the replacement socket (T3/T4) |
| 3 | **Direction 1's only live route is unproven and the toolchain is known-broken.** No ASR number on any MediaTek APU; #6462's two AOT gaps acknowledged 2026-03-18 and unfixed; INT8 Whisper encoders garbage on MDLA; vendor-library / APU-node access unread on this tablet | (1) | high × high | §1.2 R1; #6462; Genio 1461/2361; inventory | three kill-gates (E0, E3, E4 with a JIT control) before any product code; the precedent's "another model's transcript" failure shape (`NpuWhisperBackend.kt:490-498`) argues for a canary from day one |
| 4 | **GoAway absent / connections dying early** (523–533 s on the sibling model; hard aborts at 151 s in a later regression) | (3) | medium × medium | forum 177402; §3.3.10 | proactive rotation at ≤ ~500 s on a quiet frame + GoAway + the watchdog; the mirror rescues the cut |
| 5 | **Flipping the flag moves every existing Gemini user to live** — a billing (~$0.005 → ~$0.009/min paid) and data-use (free tier trains) change discovered from a support email | (3) | medium × high | `PreferencesManager.kt:378`; `FloatingBubbleService.kt:119`, `:147-148`; pricing page | ruling O4; release note; badge + training sentence together |
| 6 | **Gemma is heavier than the tier it would replace** and the phones that cannot run turbo are the ones least able to hold it (2.6 GB download; ≥ 8 GB-class RAM behind a foreground bubble; no audio-inclusive RAM number exists) | (2) | high × high for the owner's stated framing | §2.4 | reframe as a 12 GB-class premium tier or drop; `minRamBytes ≥ 7e9`; measure RSS with audio loaded (G2.1) |
| 7 | **Gemma cannot meet the "word for word" bar** — no streamed-audio API on any Google surface; the roadmap line of 2025 has no follow-up | (2) | high × certain | §2.2, §2.3 | say so before anything is built (ruling O9); the Zipformer route (§2.7) if the bar is real |
| 8 | **An LLM decoder can refuse, answer in prose, think, or loop** — none of it is stripped by `TranscriptText.clean`; a refusal sentence would be typed into the user's field | (2) | high × medium | ollama #16584; note.com hands-on; HF 3n #42; `TranscriptText.kt:36-40`; §2.5 | thinking off, system instruction, refusal detector, minimum-clip rule, Silero + evidence gates mandatory — a new guard class, designed before the tier |
| 9 | **`isCpuFallbackEligible` would admit a Gemma row as the mel donor / CPU fallback** and `cpuTierModelPath()` would pick a selected Gemma tier first | (2) | high × certain if a row lands first | `WhisperModel.kt:401-402`; `WhisperModelManager.kt:195-201`; `NpuWhisperBackend.kt:328`, `:352`, `:904` | a catalog `runtime` field before any non-ggml row exists |
| 10 | **Mali GPU correctness under LiteRT-LM is unverified** — three non-Adreno GPU failure reports, none answered; nothing on Mali-G720; the Tab may be CPU-only at 2–3× the GPU cost | (2) | medium × medium | #1850, #2202, #2611; §2.2 | G2.1's numeral/contraction canary on GPU first; CPU as the fallback number |
| 11 | **Interim semantics unknown** (fragment vs whole; finals per utterance) | (3) | medium × low | §3.1; P3 | both fold modes shipped and pinned; the cookbook shows per-segment finals |
| 12 | **The MediaTek tier's quantisation has no answer**: fp16 turbo is over the 1.5 GB pack cap (INFERRED), INT8 is the precision that produced garbage on MDLA, and the QNN w8a16 recipe does not transfer | (1) | high × medium (only if the gates pass) | §1.5; Genio 1461 | a compiler-side question to settle during E4 (fp16 vs w8 variants of base), not a packaging detail |
| 13 | **E4's thresholds fire early or late**: encoder-only vs full-F bars; sub-linear NPU scaling (0.74–0.83× on Hexagon); a slow number may be the #6462 toolchain, not the silicon | (1) | medium × high | §1.4 E4 | treat E4 as a direction-finder with a JIT control and a decode measurement, not a kill gate |
| 14 | **The CPU-turbo cross-build (E1) has never been exercised** on the vendored 1.9.1 tree with the NDK | (1) | low × medium | §1.4 E1 | build the two targets only; E2 (the approved sideload) is the alternative with no gradle change |
| 15 | **Header auth refused by the WS gateway from OkHttp** | (3) | medium × low (Google's SDK uses it) | §3.2 | `endpointFor` default-body seam (+0.5 d) with a redaction rule |
| 16 | **Free-tier limits undocumented** (no Live/transcribe rows on the rate-limits page); 429 semantics; handshake 409 after quick restarts | (3) | medium × medium | §3.2 `classifyFatal`; forum 180489 | 429 copy says "rate limit or credit"; 409 → transient |
| 17 | **Ordering: audio before `setupComplete`** — the transport's gate opens on send | (3) | low × high | `RealtimeTransport.kt:291-292`; §3.3.5 | protocol-local ready gate + 2 s ring |
| 18 | **Vulkan E6 aborts at load** without the `CEIL_DIV` patch | (1) | low × high | `ggml-vulkan.cpp:132`; PR #25245 | patch first; it is an experiment either way |
| 19 | **PODAI is beta** (`ai-delivery:0.1.1-alpha01`) and a MediaTek tier would be this app's first LiteRT runtime in the APK | (1) | medium × medium (only if the gates pass) | PODAI page 2026-09-01; §1.5 | base-module bundling is the documented alternative; AGP 8.13.2 already clears the floor |
| 20 | **Bare ISO-639-1 codes ignored or rejected** by `languageCodes` | (3) | low × medium | §3.3.3; P7 | the spec's minimal exceptions map |
| 21 | **`.litertlm` churn** — a LiteRT-LM bump can invalidate a 2.6 GB download already paid for | (2) | low × medium | HF card (2026-05-05 re-download note) | pin the runtime and the artefact commit together; a re-download policy before the tier |

---

## 6. WHAT WAS REFUTED (and what was corrected on the way)

Primary wins. Each row names the loser, the primary that decided it, and what the synthesis now says.

| # | Claim | Said by | Primary / code | Ruling |
|---|---|---|---|---|
| 1 | The Gemini Live cap is 15 minutes for audio-only; the 3.8 spec's 10 minutes is stale | `tg-map-engine.md` §0, §11; `ProviderCatalog.kt:57-59` comment | live-transcribe (2026-08-26) "up to 10 minutes"; session-management (2026-09-04) "lifetime of a connection … around 10 minutes"; ephemeral-tokens (2026-07-30) "reconnect … every 10 minutes"; model card "10 minutes per session" | **10 minutes per connection is operative**; 15 is the general Live API session cap and is not stated to apply to this model. The spec is right; the comment is wrong. |
| 2 | `?key=` BYOK "is not supported by the doc page I fetched"; ephemeral tokens are required | `tg-map-engine.md` §10 | get-started-websocket (2026-09-04) prints `?key=`; Google's Python SDK sends `x-goog-api-key` as an upgrade header with no query (`live.py:970-996`, `:1117-1119`) | Two documented client routes exist; ephemeral tokens are a recommendation protecting a developer's key from that developer's users — moot in BYOK. |
| 3 | `interimInputTranscription` is "not what the current docs show" | `tg-map-engine.md` §10 | `https://ai.google.dev/api/live`: `BidiGenerateContentServerContent.interimInputTranscription` | The field exists with the spec's exact name. |
| 4 | "The key has to ride the URL" and `endpoint` being a key-less `val` is "the one real seam problem" | `tg-map-gemini.md` §0, §5.1 | Python SDK header route | Not necessarily; header auth is the design, `endpointFor` the fallback. |
| 5 | "Coalesce appends to ~100 ms inside the protocol" | `tg-map-gemini.md` §4.2; spec §4 "~100ms cadence" | best-practices (2026-09-04): "20ms to 40ms" and "20ms - 100ms"; live-transcribe's "100ms (1,024 to 2,048 frames)" is internally inconsistent (100 ms = 1,600 frames) | Pair to 1,024 samples (64 ms); coalescing to 100 ms is not required. |
| 6 | Rotate "reactively on `goAway`, not proactively at 9:30" | `tg-map-gemini.md` §4.4 | forum 177402: GoAway ceased 2026-08-04, closes at 523/533 s with no GoAway, unresolved 2026-09-07 | Three triggers; GoAway alone is unsafe. |
| 7 | Proactive rotation at **540 s** "leaves 50 s of margin" | `tg-analysis-gemini.md` §8 | the same thread: silent deaths at 523.1 s and 533.1 s | Negative margin against the data cited; **≤ ~500 s**. The spec's 9:30 (570 s) is worse still. |
| 8 | A bad Gemini key today yields "six silent reconnects (23.5 s) then a silent slide to local" | `tg-map-gemini.md` §5.3; `tg-analysis-gemini.md` C3 | `scheduleReconnect` is called only at `RealtimeTransport.kt:189` and `:330`; `onClosed` (`:343-353`) schedules nothing; OkHttp delivers a server close as `onClosing → onClosed` | **Zero reconnects** on a close frame (or an unbounded 500 ms loop if it surfaces as `onFailure`); the `classifyClose` sketch's null branch must also reconnect; the `onDisconnected` KDoc (`:99-104`) is wrong for the `onClosed` path; no test drives `onClosed`. |
| 9 | "Cap reached, close 1000/1006 or GoAway … works today" | `tg-analysis-gemini.md` §9 | same code | Only a frameless 1006 (→ `onFailure`) reconnects today; a close **frame** at the cap does not. |
| 10 | Gemini 3.5 Transcribe is "public preview"; "~$0.005/min" applies to live; "120+ languages" | `tg-map-gemma.md` §5.3; spec `:49`; `tg-map-gemini.md` §2.9 | changelog (GA 2026-08-26); pricing page (live ≈ $0.009/min, batch ≈ $0.005/min); the live-transcribe table has 81 codes, the card says "85+" | GA; $0.009/min on the live row; 81 / "85+". |
| 11 | Implied audio tokenisation "23.8 tokens/s … 190 text tokens/min" | `tg-analysis-gemini.md` §7 | pricing footnote: 25 audio tokens/s, 175 text tokens/min | Use the documented basis. |
| 12 | "All three clauses" of the `ProviderCatalog.kt:57-59` comment are stale | `tg-analysis-plan.md` C3 | ephemeral-tokens page still recommends them for client-to-server | Two clauses ("preview", "15 minutes") and the conclusion are stale; the token clause is literally true and irrelevant to BYOK. |
| 13 | "No `small`, no `turbo` in `litert-community`" | `tg-map-mediatek.md` §3.3 | HF: `whisper-large-v3-turbo` (2026-07-23), `whisper-large-v3`, `whisper-medium`, `whisper-base`, `whisper-tiny` | Wrong twice over; the turbo `.tflite` is a CPU/GPU artefact (fp32 activations, no KV cache, no vendor variant), so the larger conclusion — no NPU-ready turbo — stands. |
| 14 | "Exactly one route" to the APU (LiteRT AOT) | `tg-map-mediatek.md` §0; `tg-analysis-plan.md` C7 | the LiteRT page documents AOT **and** on-device compilation; ExecuTorch lists D9300/D9400 with two public wheels | Three live routes, all high-risk; R1 remains the one worth a spike. |
| 15 | #6462 is "open, unanswered / no maintainer reply" | `tg-map-mediatek.md`; `tg-analysis-tab.md`; `tg-analysis-plan.md` | the issue's first comment (2026-03-18) acknowledges two toolchain gaps and pings a Googler; still unfixed 2026-08-27 | "Acknowledged as two toolchain bugs, unfixed" — arguably worse news, but not "no reply". |
| 16 | LiteRT#3188 is "open, stale" | `tg-map-mediatek.md` R8; `tg-analysis-tab.md` ruling 4 | the issue is **closed** as stale, labels `status:awaiting user response` + `status:stale` | Closed without a fix; "no fix on record" is right, "open" is not. |
| 17 | "Any VAD trim reduces the billed frames, so 26.9 ms/GFLOP is an upper bound on the cost" | `tg-map-mediatek.md` §5 | fewer frames under the same wall time means a *higher* ms/GFLOP; and the capture shows **zero trim** (`VAD: 281088 -> 281088`) | The direction was backwards; moot — the datum is no-trim, single-encode, 942 frames, ≈ 6.05 s service time. |
| 18 | The bundled ORT has the NNAPI EP "and no XNNPACK EP" | `tg-analysis-tab.md` ruling 2, C4 | `llvm-strings libonnxruntime.so` contains `XnnpackExecutionProvider`; the XNNPACK EP is string-registered and exports no C symbol; Microsoft's Android AAR builds `--use_nnapi --use_xnnpack` together; sherpa's `session.cc` has a `kXnnpack` case | Both EPs are present; the probe's CPU arm has two providers. |
| 19 | E2 "needs a different `applicationId`" because a locally-signed APK cannot install over the Play copy | `tg-analysis-tab.md` §2, E2 | memory note 2026-09-04: the owner approved uninstalling the Play copy on the Tab to sideload a debug build | No gradle edit needed on the Tab (never on the Fold6). |
| 20 | ggml Vulkan on Mali is "a documented ~15× loss"; whether the vendored ggml has the G720 fix is "unchecked" | `tg-analysis-tab.md` R4 | one 2024 unnamed-Mali LLM datum; Mali-G57 3× (2025); no G720 Android number; `ggml-vulkan.cpp:132` still has the pre-#25245 `CEIL_DIV` | Magnitude stale and device-unspecific; the fix is **known missing**. |
| 21 | The app is on AGP 8.7.3 and needs a toolchain bump for PODAI | `tg-analysis-tab.md` §7 (via the build-env note) | `build.gradle.kts:3`: `com.android.application` **8.13.2** | No bump needed (PODAI wants 8.8+ / 8.10+). |
| 22 | Whisper-base APU thresholds (74 / 145 / 193 ms) are a kill gate | `tg-analysis-plan.md` C9; `tg-analysis-tab.md` §3.5 | the repo's own Hexagon pair scales 0.74–0.83× of the FLOP ratio (`2026-08-28-npu-spike-g1-results.md:15, :27`; `2026-08-27-npu-whisper-turbo-research.md:35`); the bars are full-F, the projection encoder-only; #6462 confounds a slow number | A direction-finder; the calibrated close line is ~240–260 ms; a JIT control and an APU decode number are required before "kill". |
| 23 | A MediaTek tier is "the app's first Play feature module by construction" with "a new in-bundle device group" | `tg-analysis-plan.md` C8 | PODAI page: "base module **or** a feature module"; LiteRT v2.1.3: bundling MediaTek libraries in the app binary; groups select variants within one pack (`pad-soc-delivery.md:88-95`) | Base-module bundling is a documented alternative; a group is needed only for a second MediaTek variant. The census/gate half stands. |
| 24 | "There is no published FLEURS number for whisper-small" | `tg-map-gemma.md` §1.4; `tg-analysis-gemma.md` C6 | Whisper paper Appendix D.2.4 Table 13 (read from the PDF) | Published; E2B leads on 11/12 and trails on **English** (8.0 vs 6.1); normaliser and q5_1 caveats carried. |
| 25 | #2202's ~2.86 GB is "with audio" / "the one audio-era field report"; it is "the only Mali datapoint"; "Xclipse: nothing published" | `tg-analysis-gemma.md` C10, §2.2; `tg-analysis-plan.md` C12 | #2202 was a text-only tool-calling suite ("No audio input involved"); #1850 is a second Mali-G715 report; #2611 is an Xclipse 530 report | Label 2.86 GB text-only (audio residency is unmeasured and higher); three non-Adreno GPU failure reports, none answered. |
| 26 | Per-utterance language detection "is exactly what this tier cannot do"; an auto session "hands null to the prompt forever" | `tg-analysis-gemma.md` C9 | the `{LANGUAGE}` template is a recommended prompt, not an API constraint; a hands-on transcribed with no language named (secondary) | "Undocumented", not "cannot"; the null consequence is a prompt-construction choice; the owner ruling stands. |
| 27 | A local Gemma tier can "never" show text while the user speaks | `tg-analysis-plan.md` C5 | the gemma map's own shape A (re-transcribe the growing window, cumulative-replace) inside the 30 s cap | Overstated: delayed multi-second rewrites are possible; true word-for-word is not. |
| 28 | The npu-turbo pair is "342 MB (127 + 215)" | `tg-map-gemma.md` §0, §3 | `WhisperModel.kt:280` = 1,071,685,632 B; 342 MB is the `npu` whisper-small pair (`:249`) | Ratios are 13.6× `multi` and 2.4× turbo. |
| 29 | "Gemma 4 E2B on NPU exists only for Google Tensor" / "2,967 MB NPU variant" | `tg-map-gemma.md` §2.4; `tg-map-engine.md` §9 | HF tree: Tensor G5/G6, Qualcomm SM8750/QCS8275, Intel builds (2.95–3.31 GB); no MediaTek | The catalogue page lags the tree; still no MediaTek build — the fact that matters for the Tab. |
| 30 | "Gemma 3n with native audio input via LiteRT-LM / MediaPipe" as the candidate; "Mali is its home turf" | the brief | LiteRT-LM lists Gemma 4 only; MediaPipe is maintenance-only; #3188 on the turbo encoder; three LiteRT-LM non-Adreno GPU reports | Gemma 4 + LiteRT-LM or nothing; Mali is untested, not home turf. |
| 31 | The Tab is "CPU only, forever" | memory note 2026-09-04 | the MediaTek Neuron stack is on the device; Google names MT6989 as a LiteRT NPU target | True of the Qualcomm path only; the APU is reachable in principle, which is what E0/E3 decide. |
| 32 | Spec §3: the batch swap is "same auth, same seam" | 3.8 spec `:43-49` | transcribe page: `POST /v1beta/interactions` with a Files-API URI; forum 179937's empty output | Different seam; leave `GeminiStt` alone in 88. |
| 33 | Spec §2: "the same 57-entry list" | 3.8 spec `:34` | `OnboardingLogic.kt:76-77`, `:89-94`: 54 languages + auto, shipped as 4.2 F6 | Stale count; §2 is done. |
| 34 | Memory index: "4.3.1/83 on local main, UNPUSHED" | `MEMORY.md` index line | `app/build.gradle.kts:53-54`: 87 / 4.3.3 at `e4e9627`; the note body agrees | Index stale; the note body is current. |
| 35 | `RealtimeProtocol` members at `:107-142` | `tg-map-engine.md` §4 | `RealtimeProtocol.kt:40-73` (read 2026-09-09) | The gemini map's numbering is the correct one. |
| 36 | "Dimensity 9300" | the brief | the device's 3.4 GHz prime clock matches MediaTek's **9300+** sheet; both are MT6989 | Recorded so a later reader is not confused by the clock; nothing depends on the "+". |

---

## 7. E0 RESULT — read from the tablet on 2026-09-09 22:45 (after §0-§6 were written)

The three reads in §1.4 were run (`C:/Users/bastr/.androidbuild/tab-s10-e0.txt`), plus the system-partition
lists this document did not ask for. **The gate passes, and by a route this document under-weighted.**

- `ro.soc.manufacturer` = `Mediatek`, `ro.soc.model` = `MT6989` — exactly the strings LiteRT's device CSV expects.
- `/vendor/etc/public.libraries.txt` (208 B) whitelists `libnir_neon_driver_ndk.mtk.vndk.so`, `libarmnn_ndk.mtk.vndk.so`,
  `libcmdl_ndk.mtk.vndk.so`, `libOpenCL.so` and three Samsung camera libs. **`libneuron_adapter_mgvi.so` is NOT
  whitelisted** — the runtime's first dlopen candidate is not app-loadable, as §1.4 allowed for.
- **But `/system/etc/public.libraries-mtk.txt` (955 B) whitelists MediaTek's third-party stack on the SYSTEM
  partition**, and every file exists in `/system/lib64/`: **`libneuronusdk_adapter.mtk.so` (13,659,272 B)**,
  `libneuron_graph_delegate.mtk.so`, `libneuronservice_adapter.mtk.so`, `libneuron_sys_util.mtk.so`,
  `libtflite_mtk.mtk.so`, the `libapuware{apusys,utils,xrp,hmp}*.mtk.so` set,
  `vendor.mediatek.hardware.neuropilot.neuronservice-V1-ndk.so`, and the MVPU runtime family. Under Android's
  rule for `public.libraries-<company>.txt` (names ending `.<company>.so`, loadable by any app via
  `System.loadLibrary` / `dlopen`), **a Play app on this tablet may load the NeuroPilot USDK adapter directly.**
  §1.5's claim (from `tg-analysis-tab.md:416`) that `libneuronusdk_adapter.mtk.so` is absent was an artefact of an
  inventory that listed `/vendor/lib64` only — **refuted by the device.**
- `/dev/apusys` is `crw-rw---- system camera`, label `apusys_device`; `/dev/apusys_apummu` and `_sapu` are root-only.
  An untrusted app cannot open the nodes itself — irrelevant if access goes through the USDK adapter → the
  `neuronservice` / `apuware` AIDL services (their NDK client libs are in the same public list), which is what the
  adapter exists for. Whether that IPC path is sepolicy-open to `untrusted_app` is the one thing E0 cannot read;
  **E3 answers it empirically**, and E3 is unchanged.
- The NNAPI shim is running (feature level 7), so the deprecated NNAPI route also exists.

**What changes:** R1's (a) is answered YES pending E3; the "bundle MediaTek libraries in the app binary" fork
(LiteRT v2.1.3) is a fallback, not the main line; E3's probe should log which adapter name the runtime opened.
Nothing else in §1 moves — the toolchain gap (#6462), the absence of any Whisper-on-MediaTek result, and the
direction-finder thresholds in E4 all stand.
