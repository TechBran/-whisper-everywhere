# 4.16.0/113 on the Galaxy Tab S10+ — the MediaTek APU tier's ship sheet

**Device:** the owner's Galaxy Tab S10+ (SM-X828U, `gts10p`, MediaTek Dimensity 9300+ = `MT6989`, Neuron driver
`libneuronusdk_adapter.mtk.so` 8.2.26), Android 16 (`samsung/gts10psqw/gts10p:16/BP2A.250605.031.A3/X828USQS6CZA3`),
11.7 GB RAM. `ro.soc.manufacturer=Mediatek`, `ro.soc.model=MT6989` (bundletool's device spec, 2026-09-25 03:46).
It never had the Play copy: 4.11.3/105 was sideloaded on 2026-09-13 (`installerPackageName=null`), last updated
2026-09-20; the owner's downloaded CPU models live in its app data, so every install here is an update in place.

**Build:** `whisper-everywhere-4.16.0-113.aab` — 9,162,580,293 B, sha256 `22aaf0184f8562d5ba53930d964e34e0679088f9f39f94eba062b668e4c31520`, built 2026-09-25 04:40 from `feat/mediatek-apu-tier` @ c5934f77 (P1a + P2a + P2b + P2c + P3a, each reviewed), suite 263 classes / 3,556 / 0 before the bundle, `verifyNpuPacks` green with both vendors' payloads.
**Install set:** `bundletool build-apks --device-spec tab-s10plus-spec.json --local-testing`, signed with the upload
key: base splits + the two untargeted MediaTek pack slices (`npu_turbo_mt6989_enc-master.apk` 1,302,614,494 B,
`npu_turbo_mt6989_dec-master.apk` 584,872,327 B) + the preview/TTS packs; the Qualcomm packs collapse to their
8.5 KB `group_other` placeholders. `install-apks` pushes the packs to Play Core's local-testing directory and
updates the app in place (`firstInstallTime` unchanged is the proof).

**Protocol:** the head installs and starts a detached `WE-DIAG` capture over adb BEFORE the first launch (the
tablet's log ring is short); the owner drives the device; the head reads the capture afterwards
(owner-reproduces rule). Rows 1–4 need only the install and one launch; rows 5–15 are the owner's session.

## The rows

| # | row | expected | result |
|---|---|---|---|
| 1 | offer line on first launch | with no pack on disk the gate returns before the check: `npu: offer soc=MT6989:pass probe=skipped installed=none offered=none` (NpuDiag: `skipped` = not evaluated, never `fail`); after the pack lands: `… probe=pass installed=npu-turbo offered=npu-turbo` | 09:23:44 `npu: offer soc=MT6989:pass probe=skipped installed=none offered=none` — PASS (first half) |
| 2 | driver line | `apu: driver=libneuronusdk_adapter.mtk.so 8.2.26 want=8 devices=3 device=mtk-gpu+mtk-dsp+mtk-mdla … pass`, walk ≤ 300 ms (the gate: 165–239 ms) | 09:23:43 `… walk=258ms query=2ms pass` — PASS; `runtime: libLiteRt.so loaded … by SONAME … every entry point resolved` (the path form under `nativeLibraryDir` did not load — the APK's libs are not extracted; the SONAME fallback is the working route) |
| 3 | verdict stored | `apu: verdict … source=probe`; second launch `source=stored`, no walk | 09:23:43 `apu: verdict=pass want=8 source=probe build=113` — PASS (first half; the stored half at the next process start) |
| 4 | `Waiting for service` lines | ZERO (the merged manifest carries no `libneuron_sys_util`) | 0 — PASS |
| 5 | the pack fetch (owner: the turbo card's Get) | two parts; the card shows Downloading with a bar for the 1.3 GB encoder, not Pending; install only after both; `pack: ok` | landed by 10:08:01 (`npu: offer soc=MT6989:pass probe=pass installed=npu-turbo offered=npu-turbo`); the owner: "I've downloaded the NPU tier. It's there." — the card's states are his to describe |
| 6 | cold arm, no prior probe in the process | init ≤ 4.0 s (the gate: 2.75–3.63 s) | 10:08:07.44 → 10:08:11.24 = **3.8 s** (encoder opened 1,112 ms + restore 1,599 ms NPU "fully accelerated: yes"; decoder 308 + 549 ms NPU\|CPU; 29 buffers, 16 joins; APU check 42.8 ms) — PASS |
| 7 | cold arm, verdict stored | the same minus the walk | second process 10:08:23 `apu: verdict=pass … source=stored`; arm 10:08:25.36 → 10:08:29.33 = **4.0 s** (open 1,396 + restore 1,417; 247 + 656; APU check 28.5 ms) — PASS (the engine's own adapter walk inside init took 198 ms) |
| 8 | cold-tap loss | the StartupRing at 12 s for MediaTek rows: the first words of a tap during the arm are kept | **FAIL — and not MediaTek's.** The owner, after the re-Get (pack landed 10:51:27, re-arm 10:51:36 in ≈ 4 s, first commit only at 10:52:02): "the four second startup tax — it skips over everything within that four seconds… that even happens on all of my other devices while the model is loading." The ring's own drain/overflow lines are `android.util.Log.i` (FloatingBubbleService ~:5329-5336, :3517, :3902), which R8 strips from a Play build, so this capture cannot show what the ring held or drained. See F3. |
| 9 | per-commit | encode ≈ 1.72 s, decode ≈ 30 ms/token (23–37), a 20-token commit ≈ 2.3–2.4 s | **255 commits, 10:09:17–10:41:33 (32 min, a YouTube video via device audio):** encode n=255 min 1,740 / p50 1,752 / p95 1,761 / max 1,841 ms; decode p50 33.4 ms/token (min 27.5, p95 40.8, one 112.8 on a 3-token commit); the native step ≈ 21 ms + the 8 ms cache copy; 8,041 tokens, p50 23 per commit, max 89 — a 23-token commit ≈ 2.5 s — PASS |
| 10 | canary + jfk (the head played the wav files on the tablet through its player; the app captured device audio) | transcripts equal to the reference decode (t8) — in the PRODUCT the comparison is by text: the endpointer chunks by voice activity where the gate fed one 30 s window, so token counts and lp differ by construction | jfk 10:47:59 → four VAD chunks at 10:48:10/12/16/19: 9 + 5 + 10 + 11 tokens, encode 1,740–1,762 ms, lp −0.24/−0.41/−0.16/−0.04, all EOT; canary 10:48:25 → one commit 10:48:34: **12 tokens** (= the reference's count), encode 1,736 ms, lp −0.07, EOT. Text: the owner, reading the panel: "the display text came out perfect. Every word accounted for." — PASS |
| 11 | the unstripped dispatch | the first in-app arm runs the zip-member bytes `9e963c56…` (the gate ran the stripped copy `f47bd9c0…`): identical transcripts prove them equivalent | the product staged and ran the zip-member copy (`assets/libLiteRtDispatch_MediaTek.so` 409,728 B → `files/litert_dispatch/`), and row 10's text equals the reference — PASS |
| 12 | 30-minute session (owner: a video via device audio) | thermal status 0–1, PSS 4.5–5.6 GB armed beside a foreground app, no lmkd kill, every commit EOT | **32 minutes, one process (pid 7711) throughout, 255/255 EOT, rung 0 on 254 (one temperature retry), nsp 0.00 on all, thermal status 0 at the end (battery 28.4 °C), PSS 5.17 GB (EGL 1.26 GB, swap PSS 3.31 GB), MemAvailable 1.45 GB, no lmkd kill of the app, no release, no refusal, 0 waits.** The owner: "APU on the tablet works fantastic. Very consistent. Speed increases, of course, are a different level than CPU." — PASS |
| 13 | re-arm after a trim | the two restores only (≈ 1.4 + 0.45 s), no adapter re-load | |
| 14 | fallback (packs removed via the card) | the tier reads not installed, the CPU tier answers, no crash | the owner removed the pair (10:50:38 `nativeRelease complete (epoch 1)`); with NO CPU model on this tablet the app-wide gate asked for a download instead of answering on the CPU — the owner: "the fallback just lets you know that you need to download a model." No crash, no stale offer; the re-Get landed at 10:51:27 and re-armed at 10:51:36 (restore 1,200 + 397 ms, APU check 35.9 ms). PASS as the gate is designed (a CPU answer needs a CPU model on disk) |
| 15 | the copy (owner looks) | turbo card "Best AI-chip accuracy" / "The most accurate model that runs on this device's AI chip." / badge "1887 MB"; onboarding "about 1.9 GB"; no import offered | the owner, in the chooser: "Multilingual on NPU, large V3 Turbo, the most accurate model that runs on this device's AI chip. And that looks good. No need for a model import or anything like that since we're supplying the models." — PASS (the body verbatim; no import surface seen) |

## Findings during the session

**F1 — one native crash, in the GPU driver, not in the engine (10:08:22).** Eight seconds after the first arm the
engine was released (`nativeRelease complete (epoch 1)` at 10:08:19 — a trim under the memory pressure below, or
the chooser closing), and three seconds later the process died with `SIGSEGV (SEGV_MAPERR) fault addr
0xffffffffffffffff` on the **RenderThread**, every frame inside `/vendor/lib64/egl/mt6989/libGLES_mali.so`
(`eglDestroySurface+264` at #12) — the framework's HWUI tearing down a window surface while lmkd was reaping
processes (dozens of `lowmemorykiller process_mrelease` lines 10:07:53–10:08:18). A −1 fault address is a
failed allocation used as a pointer inside the driver. The service restarted within a second, read the stored
verdict, re-armed in 4.0 s and the session ran from 10:09:17 with no further incident (watched live). Whether it
recurs decides its row; the app's own code is not in the trace.

**F2 — the memory footprint, measured in the product (10:14, armed, 21 commits in, YouTube in the foreground).**
`dumpsys meminfo`: TOTAL PSS 4,788,076 KB, RSS 1,619,283 KB, **SWAP PSS 3,232,952 KB**. Native Heap RSS 217,924 KB
with **3,227,283 KB swapped**; **EGL mtrack 1,263,875 KB** (the APU's DMA buffers — the compiled encoder lives
there). System: MemTotal 11,445,484 KB, MemAvailable 1,516,076 KB, swap 16 GB with 6.4 GB in use. Reading: LiteRT
(or the dispatch) reads the two model files into anonymous heap memory (~1.9 GB), the dispatch copies the
bytecode into APU memory, and the heap copy is then cold — the kernel swaps it to zram, which is why the engine
"costs" 4.8 GB PSS on paper while its resident set is 1.6 GB. Engineering item for 4.16.x: load the models
through `LiteRtCreateModelFromBuffer` over an mmap of the file (file-backed pages are reclaimable without swap),
and measure PSS/swap again; the encoder's bytecode is needed only until compile, the decoder's CPU-partition
tables (its 23 gathers) stay needed.

**F3 — the cold-tap words are lost on every tier, on every device (owner's report; pre-existing, not 113's).**
The design since 4.5.x: a tap opens the session and the microphone at once, audio accumulates in `StartupRing`
(6 s Qualcomm/CPU, 12 s MediaTek) until the engine's `connect()` callback, then `onAudioChunk` takes the DRAIN
route and replays the ring through `feedEngine`, four buffered chunks per live one (`FloatingBubbleService`
~:3490-3517, :5318-5337). The owner reports the words spoken during the arm are skipped, on the tablet and on
all his phones. The ring's evidence lines (`drainLine`, `overflowLine`, `switchFlushLine`) are `android.util.Log.i`
and never reach a Play build's log, so the field has never been able to show whether the ring drained. Two
steps for 4.16.1: (1) route those three lines through `WhisperNative.diag` (the repo's rule for lines a track
build must show); (2) reproduce with the lines visible and look where the drained chunks die — the likely
suspects are the endpointer receiving historical `nowMs` from the replay (a wall-clock cap or the FLATLINE cut
reading a burst of old timestamps), or the NPU tier's VAD-standalone route being armed only after the drain.
`StartupRingTest` proves the ring itself (exactly-once, in-order); nothing proves the endpointer accepts the replay.

## Notes

- The capture: `docs/measurements/raw/2026-09-25-tab-113-ship-capture.txt` (the diag/tombstone lines; the full log stays on the MS-02 and the PC under `~/.androidbuild/probe-logs/`).
- Ship when every row passes; the owner's rulings still open at the time of writing: the speed-claim wording (the
  card carries no speed claim until then) and the NeuroPilot Express notice on the licences page.

## Verdict (2026-09-25 10:55)

Thirteen of fifteen rows pass on the owner's tablet; the two that do not are not the tier's: row 8 is a
pre-existing, fleet-wide loss of the words spoken during the arm (F3, a 4.16.1 item), and F1 was one GPU-driver
crash under memory pressure before the session proper, not repeated in 45 minutes of use (F2's mmap item is the
lever). The owner, after 32 minutes on the APU: "works fantastic. Very consistent. Speed increases, of course,
are a different level than CPU." 4.16.0/113 goes to the internal track once the owner's last ruling of the
session is in the build — see below — beside the two rulings still open (the speed-claim wording; the
NeuroPilot notice).

**Owner ruling 2026-09-25 (row 15's addendum):** the tablet's chooser showed the three Q8 CPU rungs beside the
NPU card, because they are INSTALLED on this tablet (the 2026-09-17 ladder session) and the 4.3 one-tier rule
keeps installed cards (`pickableFor`'s non-disturbance producer). The owner: "if the NPU multilingual is here,
then we hide all of the other CPU models so users don't get confused about which model to download". So: on a
device offered the one tier, installed CPU rungs no longer render a card (they are not deleted; the delivery-failure
escape and the decline recovery are untouched). Built into 113 before its upload — the final build
(9,162,582,027 B, sha256 `25400c8f…`, `feat/mediatek-apu-tier` @ adaf8eab, suite 263/3,561/0) was reinstalled in place
at 12:29 (the app re-probed the driver on the new build as the verdict key requires: `walk=284ms … pass`, `source=probe`,
and armed on its own in 3.6 s); the owner, in the chooser: "the chip is right" — the AI-chip card alone. PASS.

**Row 8, a second observation on the final build (12:3x):** with the engine ALREADY ARMED by the boot prewarm
(12:29:20, before any tap), the owner reports "now the audio correctly picks up the first bytes of my audio". So
the loss of F3 is specific to a tap that lands DURING an arm (the first tap after an install, a verdict, a trim
or a process start), which is what row 8's re-Get exercised; a pre-armed engine keeps the first words. The 4.16.1
item stands as written (make the ring's evidence visible, then reproduce the in-session arm).
