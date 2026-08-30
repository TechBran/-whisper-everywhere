# 4.1 Model Lab — device acceptance: run-book + A/B sheet

Build under test: **4.1.0 / versionCode 80** (`feat/4.1-npu-model-lab`). Everything below is the
OWNER's device session; the implementer prepared this sheet and claims none of it as done. Every
log read in this document is one capture:

```
adb logcat -s WE-DIAG
```

(The whole native file moved onto WE-DIAG in 4.1 L2; there is no second tag to chase.)

One side-effect of the version bump, stated up front: GpuPolicy keys its permanent canary latches
on `BuildConfig.VERSION_CODE`, so 78 → 80 clears every recorded GPU verdict. With the experimental
multilingual-GPU toggle OFF (the shipped default) nothing re-runs; with it ON, the canary runs once
more on the first cold `multi` load.

---

## §1 — THE `adb push` ROUTE, AND ITS ONE DESTRUCTIVE HAZARD (read before touching anything)

This section comes before the install step because the push is the one action in the whole session
that can destroy data, and the only one with no undo.

`adb push` is how the owner provisioned 4.0 (Q10a; the SAF import has still never run on a
device), it is deliberately **hash-exempt**, and it **bypasses the import allow-list entirely** —
so *nothing in the app can stop it landing a file on the wrong name*. The rename that keeps the
two pairs apart is enforced in the catalog, in `classifyEntry` and in `tools/pack_npu_zip.py`, and
**none of those three is on this route.**

> **The models directory is `filesDir/models`** — `/data/data/com.whispereverywhere/files/models`
> (`/data/user/0/…` is the same directory on a single-user device). It is **not**
> `/sdcard/Android/data/…`, which is `getExternalFilesDir` and is read by nothing here. App-
> private storage is not writable by a plain `adb push` at all; the debuggable-build route is
> `push` to `/data/local/tmp/` then `run-as … cp`, which is what 4.0 actually executed
> (`task-Q8-report.md:578-583`, `task-Q9-report.md:519-524`).
>
> **Push turbo's binaries under `turbo_encoder_qairt_context.bin` and
> `turbo_decoder_qairt_context.bin`. Never under their vendor names.** The rename happens at
> the `cp`, which is the only step on this route that names a destination:
>
> ```
> adb push <extracted>/encoder_qairt_context.bin /data/local/tmp/
> adb push <extracted>/decoder_qairt_context.bin /data/local/tmp/
> adb shell run-as com.whispereverywhere mkdir -p files/models
> adb shell run-as com.whispereverywhere cp /data/local/tmp/encoder_qairt_context.bin files/models/turbo_encoder_qairt_context.bin
> adb shell run-as com.whispereverywhere cp /data/local/tmp/decoder_qairt_context.bin files/models/turbo_decoder_qairt_context.bin
> adb shell rm /data/local/tmp/encoder_qairt_context.bin /data/local/tmp/decoder_qairt_context.bin
> ```
>
> **Why the names matter:** the AI Hub zips for *both* families call their binaries
> `encoder_qairt_context.bin` / `decoder_qairt_context.bin`, and both pairs live in this one
> directory. A `cp` that keeps the vendor names **overwrites the installed `npu` pair** — 358 MB,
> hand-provisioned, and the only copy on the device. There is no verification on this route to
> catch it and no rollback: the encoder is simply gone, `isInstalled(npu)` goes false, and the
> card disappears from the chooser with nothing on screen to explain why.
>
> **The gate, and it is the only check this route has** — four files, four exact lengths:
>
> ```
> adb shell run-as com.whispereverywhere ls -l files/models
> ```
>
> | file | bytes |
> |---|---|
> | `encoder_qairt_context.bin` | 132,927,488 |
> | `decoder_qairt_context.bin` | 225,316,864 |
> | `turbo_encoder_qairt_context.bin` | 775,831,552 |
> | `turbo_decoder_qairt_context.bin` | 295,854,080 |
>
> **A push does NOT bump `ModelInstallSignal`** — only `notifyModelInstalled()` does, and
> nothing calls it on this route (4.0's Q8/Q9 reports both say so). Re-enter the chooser or
> restart the app before reading "the card did not appear" as a gate failure, and check the
> `npu: offer …` line first. Note the offer line's own refresh rule (§3): it re-arms on the
> INSTALL SIGNAL, which a push never sends — after a push, the landmark line refreshes only at
> the next process restart. Restart the app after pushing; then read the line.

The preferred route where possible is the **delivery zip + in-app import** (§6), which verifies
every byte and cannot land a wrong name. The push route exists because it is the 4.0 EXECUTED
recipe and the SAF import's very first device run happens in this same session.

## §2 — Install

```
.\gradlew.bat :app:assembleDebug --no-daemon        (JAVA_HOME = Android Studio1\jbr)
adb install -r C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\apk\debug\app-debug.apk
```

- `assembleDebug`, like every other task on this branch, and for the reason 4.0's Q10b recorded:
  `release` would put R8 on the JNI surface for the first time at the acceptance run.
  `keystore.properties` is present on this machine, so the debug APK is already signed with the
  release key and installs straight over the previous build.
- **`adb install -r` and never `:app:installDebug` / `connectedAndroidTest`** — those uninstall
  first, which wipes app storage including every model on the device. This has happened twice.

## §3 — First reads (before any dictation)

1. **The offer line** — the run-book's first read, emitted at the gate's first evaluation:

   ```
   npu: offer soc=SM8650:pass probe=pass installed=npu,npu-turbo offered=npu,npu-turbo
   ```

   The three "card never showed" causes stay separable: `soc=…:fail probe=skipped` = wrong
   silicon; `soc=…:pass probe=skipped installed=none` = nothing on disk (the dlopen never ran);
   `soc=…:pass probe=fail installed=<ids>` = the QNN stack did not load — an
   `ADSP_LIBRARY_PATH` / `libqnnasr.so` question. `installed=`/`offered=` are sorted tier ids.

   **New in 4.1.0 (L5 review I1): the line re-arms on the install signal.** One line per install
   epoch: after an in-app import lands, the next gate evaluation emits a FRESH line with the new
   `installed=` set and a real probe verdict — so a mid-process import followed by a probe failure
   is on record in that same process. The `adb push` route sends no install signal (§1): after a
   push, restart the app to refresh the line.

2. **Both context binaries deserialise.** Turbo's decoder blob has **never** been run under the
   2.49 runtime; its first `nativeInit` on this device is this session. Success landmark:

   ```
   nativeInit: session armed with epoch N
   ```

3. **The census guard's structured error is the expected first-trip on ANY surprise.** It is the
   first guard a re-exported or mis-provisioned asset meets, and `npu-turbo` differs from small at
   every census value by construction. The line that must NOT appear (shown here for turbo's
   encoder; the label is the graph's):

   ```
   encoder io: differs from expected census - expected 1 in / 8 out, 768000 B in / 15360000 B out; got …
   ```

   Turbo's healthy census: encoder **1 in / 8 out, 768,000 B in / 15,360,000 B out**; decoder
   **19 in / 9 out, 17,398,168 B in / 2,141,492 B out**. (npu's, for contrast: 1/24,
   480,000/27,648,000 and 51/25, 31,316,376/3,771,698.) A trip here is an ASSET or SCALAR
   problem, not a decoder one — it is attributable in one glance, surfaces as
   `npu: unavailable stage=init detail=<the line above>`, and the session still works on the CPU
   model.

4. **The alias guard** passes for all cross-KV pairs — turbo: `alias guard: 8 cross-KV pairs
   identical across encoder-out/decoder-in` (npu: 24).

5. **The mask codes**: `mask: attention_mask scale … -> attend code 65535 = 0.0000, blocked code
   0 = -100.0000` (shape per tier; attend must dequantise to ~0, blocked to a large negative).

6. **The vote line** present and OK: `vote: …` — never silently absent; an unvoted session is
   slow-but-correct and the note says why.

7. **The mel line, first 128-bin look** — the only place the melbank asset is proved numerically:

   ```
   mel: bins=128 frames=3000 row0=… rowMid=… rowLast=…
   ```

   The three sums must be **distinct and non-zero** on real speech. `rowMid == rowLast` is the
   stride signature (the upper half of the spectrogram untouched); all-zero rows mean the
   filterbank never applied. On the `npu` tier the same line reads `bins=80`.

8. **The skel staged** — no FastRPC refusal with nothing hand-pushed: the APK's own
   `libQnnHtpV75Skel.so` (17,913,608 B) stages from assets into `filesDir` on first arm. This is
   the FIRST end-to-end exercise of the packaged skel route; `stage=skel` in a capture means
   "assets → filesDir staging failed", a state that did not exist before L6. A hand-pushed skel of
   a different runtime version is silently replaced by the APK's own — that is the design.

## §4 — The greps

PowerShell, against a saved capture; `-SimpleMatch` because several needles carry `(` `)` `>`:

```
Select-String -SimpleMatch "npu: offer "      capture.txt
Select-String -SimpleMatch "npu: import "     capture.txt
Select-String -SimpleMatch "npu: encode="     capture.txt
Select-String -SimpleMatch "segment-timing: " capture.txt
Select-String -SimpleMatch "mel: bins="       capture.txt
Select-String -SimpleMatch "auto->"           capture.txt
Select-String -SimpleMatch "(detected)"       capture.txt
Select-String -SimpleMatch "(locale)"         capture.txt
Select-String -SimpleMatch "(fallback)"       capture.txt
Select-String -SimpleMatch "tier rebuild"     capture.txt
Select-String -SimpleMatch "fallback rebuild" capture.txt
Select-String -SimpleMatch "epoch"            capture.txt
```

## §5 — The import (first SAF run ever, and the landmarks)

The SAF import has never executed on a device; its first real run is turbo's ~1.07 GB pair — the
largest input it will ever see. Delivery zips come from the repack script (§10). Landmarks, format
unchanged from 4.0:

| event | exact line |
|---|---|
| npu pair imported | `npu: import ok entries=2 bytes=358244352` |
| turbo pair imported | `npu: import ok entries=2 bytes=1071685632` |
| any refusal | `npu: import refused reason=…` |

Import-failure rows quote the refusal vocabulary: `wrongSizeRefusal` (both numbers),
`wrongDigestRefusal` (both hashes, truthfully labelled, no path), `duplicateEntryRefusal` (a
repack fault), `overLengthRefusal`, `missingEntriesRefusal` (per-pair wording), the two rollback
messages and the stranded-state message. **One bound to know when reading a refusal card (L6
review m4): the `unreadableRefusal`'s 80-char cause bound is length-only, not content-aware — the
first 80 chars of a `FileNotFoundException` message can carry most of an app-internal `.part`
path into the card copy, truncated with a visible `…`. That is expected, not a leak of anything
beyond a filesystem path the app itself printed.**

A completed import proves, per file, the exact published length AND sha256 — and it DOES bump the
install signal, so the card appears (and a fresh offer line prints) without a restart.

## §6 — The A/B sheet

**The measurement is the transcript comparison, not the timing.** Timing is published for turbo
(1.37–1.57 s/segment class) and unmeasured for accuracy; accuracy is the thing nobody has a
number for (residual risk 5), and the owner's judgement on their own speech is the instrument.

One fixed passage, dictated three times per tier. Between rows, switch tiers in the chooser and
confirm the narration line (§7) before dictating. Per run record: the transcript, the `npu:`
line's `encode=`/`decode=`/`tokens=`, `segment-timing:`'s `transcribeMs`, one subjective note.
(`multi` rows have no `npu:` line — record `segment-timing:` only.)

| # | tier | transcript (verbatim) | encode | decode | tokens | transcribeMs | note |
|---|---|---|---|---|---|---|---|
| 1 | multi | | — | — | — | | |
| 2 | multi | | — | — | — | | |
| 3 | multi | | — | — | — | | |
| 4 | npu | | | | | | |
| 5 | npu | | | | | | |
| 6 | npu | | | | | | |
| 7 | npu-turbo | | | | | | |
| 8 | npu-turbo | | | | | | |
| 9 | npu-turbo | | | | | | |

Plus one deliberately code-switched utterance per npu-class tier (start in English, finish in
another language) — **the acceptance for L7**:

| tier | code-switched transcript | lang notes seen (in order) |
|---|---|---|
| npu | | |
| npu-turbo | | |

Expected on an npu-class tier under auto: every segment carries its own honest note, and the
switch appears as `auto->en(detected)` → `auto->xx(detected)` — two different `(detected)`
codes, no bare-code note (bare code = user-chosen), no repetition of the first code after the
switch. The CPU `multi` tier by contrast LATCHES: first usable detection pins the session
(`language-pin: detected=…`), later segments ride it — both behaviours are correct, per backend.

## §7 — The switch narration and the cross-tier epoch row

Every A/B tier change rebuilds the cached engine, and every rebuild narrates itself (Q9 M3,
closed at L8):

- a switch: `npu: tier rebuild from=npu to=npu-turbo (the cached local engine is rebuilt for
  the selected tier)` — `cpu` stands for every CPU tier at once. This is the line for EVERY
  switch, **including a switch away from a tier that has declined** (it names the actual target
  tier);
- a decline-driven rebuild — the declined tier still selected, so the replacement really IS the
  CPU tier: `npu: fallback rebuild stage=… (the cached local engine is rebuilt on the CPU
  tier)`.

A rebuild with NEITHER line is a bug; a `tier rebuild` line on every sheet-row switch is the
expected rhythm of §6, decline or no decline.

**The epoch row — the L1 mechanism's first cross-tier exercise, and the reason two npu tiers are
safe at all.** On an `npu → npu-turbo` (or reverse) switch, the stale engine's queued release may
reach native AFTER the new tier armed. Healthy capture on that interleaving:

```
nativeInit: session armed with epoch M        (the NEW tier)
nativeRelease: epoch N is not the live session (M) - ignored     (the STALE engine's teardown)
```

The `ignored` line is the epoch doing its job — a destroyed-successor session would instead show
the new tier armed and then every transcribe failing. When the stale release wins the race
normally, the capture shows `nativeRelease complete (epoch N)` before the new arm — also healthy.
Record which of the two shapes each switch produced:

| switch | armed epoch | stale release line seen (`ignored` / `complete` / none) |
|---|---|---|
| npu → npu-turbo | | |
| npu-turbo → npu | | |
| npu → multi → npu-turbo | | |

## §8 — Language checks per family (the sharpest single check of the family threading)

- **Cantonese on turbo**: select `npu-turbo`, explicit language Cantonese (`yue`). It must
  transcribe — `yue` occupies slot **50358** in the large-v3 family (the same id that is
  `<|translate|>` in whisper-small's family, which is why no per-id check could ever catch a
  family mix-up).
- **Beside it, small refusing yue**: on the `npu` tier an explicit `yue` is a language the
  whisper-small asset cannot name — expect the `lang` stage decline and the CPU tier running
  that segment (`npu: unavailable stage=lang …`), not a silent English transcript.
- **After a forced decline** (any stage): confirm the preview strip still renders during CPU
  segments and `segment-timing:` lines carry the `vadIn/vadOut/ctxFrames` suffix (Q6 M4's
  delegating overrides). While the NPU is live those two are honestly absent — never forged.

## §9 — Peak RSS on both npu-class tiers

**RESTART THE APP BEFORE THIS SECTION.** §8 deliberately declined the npu tier (the
small-refuses-yue check is a real `stage=lang` decline), and a decline is PROCESS-scoped by
design — nothing clears it short of an app restart; there is no in-app route back (the card's
own F3 wording says exactly this). Without the restart, `npu` routes to the CPU backend and its
RSS row below would silently measure a CPU session — the tell is a `fallback rebuild` line and
missing `npu: encode=` lines, which is not a row you want to discover was hollow after the
session ends.

Turbo is ~1,043 MiB of NPU-side residency against npu's ~376 MiB, and I11's no-co-residency rule
was written for the smaller one. After a few segments on each tier:

```
adb shell dumpsys meminfo com.whispereverywhere | Select-String -SimpleMatch "TOTAL"
```

| tier | steady RSS | during a tier switch (worst seen) |
|---|---|---|
| npu | | |
| npu-turbo | | |

A switch-time spike is bounded and survivable on 8 Gen 3-class RAM (the epoch prevents the
destructive interleaving, not the transient); record it rather than fearing it.

## §10 — Producing the delivery zips (controller/owner machine, not device)

```
C:/Users/bastr/AppData/Local/Programs/Python/Python313/python.exe tools/pack_npu_zip.py npu-turbo `
  C:/Users/bastr/.androidbuild/npu-model-lab/whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-qualcomm_snapdragon_8gen3.zip `
  C:/Users/bastr/.androidbuild/npu-model-lab/npu-turbo-pair.zip
```

The script strips the vendor directory prefix, renames turbo's two entries to the catalog's
`turbo_*` filenames, re-verifies its own output through the importer's allow-list-and-digest
logic, and prints the zip's sha256 to publish beside the file. **npu's zip is regenerated the
same way** (`npu` as the tier argument) — the 4.0 zip predates the prefix discovery; the npu
vendor zip re-downloads from the catalog-pinned AI Hub URL if no local copy remains.

---

## Sign-off

| gate | owner verdict |
|---|---|
| §1 four-file/four-length gate after provisioning | |
| §3 all eight first-reads healthy on both tiers | |
| §5 turbo import ok landmark (first SAF run) | |
| §6 A/B rows complete; owner's tier pick | |
| §7 every switch narrated; epoch rows recorded | |
| §8 yue-on-turbo + small-refuses-yue + post-decline previews | |
| §9 RSS rows recorded | |
