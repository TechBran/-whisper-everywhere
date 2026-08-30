# 4.2 Fleet Onboarding — device acceptance: run-book + A/B sheet

Build under test: **4.2.0 / versionCode 81** (`feat/4.2-fleet-onboarding`). Everything below is the
OWNER's device session; the implementer prepared this sheet and claims none of it as done.

**What is new about this sheet, and it changes how you read every line of it: the acceptance is a
RELEASE build installed from Google Play, not a debug build installed over adb.** Every previous
run-book in this program was written against `assembleDebug` + `adb install -r`, where nothing is
stripped and `adb logcat -s WE-DIAG` is the instrument. The 4.2 acceptance is the spec's own
sentence — *install on the Fold6 from the internal track and reach turbo dictation without
touching adb* — so the build under test is minified, shrunk and Play-signed, and §0.5 states
exactly which evidence still exists in it and which does not. Read §0.5 before §3.

One side-effect of the version bump, stated up front: `GpuPolicy` keys its permanent canary
latches on `BuildConfig.VERSION_CODE`, so 80 → 81 clears every recorded GPU verdict on every
device. With the experimental multilingual-GPU toggle OFF (the shipped default) nothing re-runs;
with it ON, the canary runs once more on the first cold `multi` load. Inert either way for this
session.

---

## §0 — Build the payload and the bundle (controller machine, before any device work)

```powershell
# The cd is not decoration: every command in this sheet that is not an absolute interpreter is
# relative to the repo root — the script paths here, .\gradlew.bat, and keystore.properties in §1.
cd "C:\Users\bastr\OneDrive\Desktop\whisper Everywhere"
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
& "C:\Users\bastr\AppData\Local\Programs\Python\Python313\python.exe" tools\build_asset_packs.py measure
& "C:\Users\bastr\AppData\Local\Programs\Python\Python313\python.exe" tools\build_asset_packs.py build
.\gradlew.bat :app:bundleRelease --no-daemon      # runs verifyNpuPacks first, by task graph
```

`measure` re-proves every vendor zip through the HEAD/length/CRC gates; `build` assembles the
eight `#group_soc_*` variants and re-verifies what it wrote; `verifyNpuPacks` re-proves the
payload against the census one more time inside the bundle build, and refuses if any variant is
missing, stale or if either default variant gained content. **On a warm workspace the two script
steps are idempotent and cheap — the payload was already on disk and gate-green when this sheet
was written, so the first bundle needs no script run.**

**What this produced when the sheet was written** (recorded so a future run has something to
disagree with, not as a promise):

| artifact | value |
|---|---|
| `verifyNpuPacks` | `all 8 pack variants match the census byte counts and both default variants are empty` |
| signed AAB | `C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\bundle\release\app-release.aab` |
| AAB size | **~4.68 GB** — 4,677,577,479 B at the commit this sheet ships with (the exact count moves by a few bytes per build; the unsigned intermediary is ~5.99 GB) |
| release APK (the `assembleRelease` rung) | 120,305,933 B |
| `bundletool validate` | clean; both asset packs listed with five variants each |

> **THE UPLOAD SIZE — ANSWERED, and the answer is that we are well inside the budget.** The AAB is
> ~4.68 GB (the same number the table above states; ~4.36 **GiB** if your tool reports binary units)
> because it carries **all four** device-group variants of **both** packs. That total is the right
> thing to compare, and the branch's own research already did the comparison —
> `docs/superpowers/research/2026-08-29-pad-soc-delivery.md` §1, from the Play size-limits page
> (support answer 9859372):
>
> | Play limit | value | us |
> |---|---|---|
> | Individual asset pack | **1.5 GB** compressed | turbo's largest variant ≈ **860 MB** compressed |
> | Cumulative **on-demand + fast-follow** packs | **30 GB** | 4 turbo + 4 small ≈ **4.6 GB** — about **15 %** of the budget |
> | Total compressed download per app | 34 GB | far below |
>
> **The 4 GB row in that table is the cumulative INSTALL-TIME cap and does not apply here**: both
> pack modules are `deliveryType.set("on-demand")`. So do not go into the upload expecting a
> refusal, and — more importantly — **if the upload fails for some other reason, do not misdiagnose
> it as the size cap**: that mistake would escalate an unrelated fault into a delivery-architecture
> task the research says is unnecessary. The Console remains the only authority that can *confirm*
> it, so treat a successful upload as the confirmation and nothing more.
>
> **ORDERING, and the sheet means this exactly.** The upload *attempt* may come first — it is a
> cheap size probe and it is worth knowing the Console accepts the file before the owner spends
> three uninstalls. **The TRACK INSTALL (§2) must not happen until §1 rung 1 has passed.** Rung 1 is
> the gate that proves a release-built, release-signed app reaches turbo dictation at all; installing
> from the track before it would mean discovering a release-only fault with the packs already
> published and the device already wiped. Upload early if you like; install late.

### §0.1 — THE RELEASE RUNG (4.1 residual risk 7, now due and now DISCHARGED locally)

This branch's first release build is also R8's first meeting with the JNI surface. It has now been
run, and the result is recorded here because it is the one rung the owner does not have to repeat:

- `:app:assembleRelease` and `:app:bundleRelease` both **succeed**.
- **All 23 `Java_com_whispereverywhere_*` symbols exported by the shipped `.so` files resolve
  against the R8-processed dex** — `QnnAsrNative` and `WhisperNative` are both unrenamed and every
  native method keeps its name (cross-checked mechanically, symbol by symbol, against
  `classes.dex`'s own class and method tables).
- The two other release-only surfaces this project has historically been bitten by also hold:
  `WhisperNative$NewSegmentCallback` keeps `onRunningText` on the renamed lambda that implements it
  (the 3.6.0 preview-delta hazard), and `com.k2fsa.sherpa.onnx.**` plus the
  `Function1.invoke(float[])` bridge are kept intact (the 2026-07-18 SIGABRT hazard).

**Three release-only defects were found and fixed on the way to that green, and all three were
invisible to every APK build and every JVM test** — they are recorded in the F8 report with
evidence. Two of them changed the pack layout, which is why the *shape* below differs from F4's:
each pack's assets now live under **the pack's own name** (`assets/npu_small/…`,
`assets/npu_turbo/…`) and the empty default is the explicit **`#group_other`** variant.

### §0.2 — THE AAB INSPECTION RUNG (F4's M11 / the F4 review's one pre-upload residual)

AGP configures clean with `android.experimental.enableDeviceTargetingConfigApi` **off**, so the
`gradle.properties` flag pin was the only local guard that the device-targeting config reaches the
bundle at all. **Run this against the built AAB before every upload.** It is cheap, it reads the
artifact you are actually about to ship, and a MISSING line means the packs would be delivered to
everyone or to no one:

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$aab = 'C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\bundle\release\app-release.aab'
$z = [System.IO.Compression.ZipFile]::OpenRead($aab)
$e = $z.Entries | Where-Object { $_.FullName -eq 'BUNDLE-METADATA/com.android.tools.build.bundletool/DeviceGroupConfig.pb' }
if (-not $e) { "DeviceGroupConfig.pb ABSENT — DO NOT UPLOAD" } else {
  $ms = New-Object System.IO.MemoryStream; $e.Open().CopyTo($ms)
  $txt = -join ($ms.ToArray() | ForEach-Object { if ($_ -ge 32 -and $_ -lt 127) { [char]$_ } else { '.' } })
  foreach ($n in 'soc_8gen3','soc_8elite_galaxy','soc_8elite5_galaxy','soc_7gen4',
                 'SM8650','SM8650-AC','SM8750-AC','SM8850-AD','SM7750','QTI','Qualcomm') {
    "{0,-20} {1}" -f $n, $(if ($txt.Contains($n)) { 'present' } else { 'MISSING' })
  }
}
$z.Dispose()
```

**Result when this sheet was written: present, all eleven** — the four census group names and every
SoC string from `app/device_targeting_config.xml`, inside a 294-byte `DeviceGroupConfig.pb`. The
flag pin is no longer the only guard.

The companion listing, which shows the delivery shape in one screen:

```powershell
$z = [System.IO.Compression.ZipFile]::OpenRead($aab)
$z.Entries | Where-Object { $_.FullName -match '^npu_(small|turbo)/assets/' } |
  Group-Object { ($_.FullName -split '/')[2] } |
  ForEach-Object { "{0,-52} {1} file(s)" -f $_.Name, $_.Count }
$z.Dispose()
```

Expect **ten directories**: four `#group_soc_*` per pack with **3 files** each (the two binaries +
`metadata.json`), and one `#group_other` per pack with **1 file** (`.gitkeep`, 0 B). A
`#group_other` with more than `.gitkeep` in it means every unmatched device on earth is about to
download those bytes; a `#group_soc_*` with fewer than 3 means a device in that family gets a
refusal instead of a model.

---

## §0.5 — WHAT A RELEASE BUILD DOES NOT SAY (read before §3, and before you conclude anything from a silent capture)

`app/proguard-rules.pro` carries `-assumenosideeffects class android.util.Log { v/d/i/w/e }` — a
deliberate production-posture rule, older than this branch: *"Strip ALL android.util.Log calls from
release builds (incl. the WE-DIAG operational logs) — production posture for Play. Diagnosis
happens on debug builds, where nothing is stripped."*

**That rule is doing its job, and the consequence for this sheet is that most of the landmark lines
every previous run-book was built on DO NOT EXIST in the build the owner is about to test.** This
was verified against the actual release `classes.dex`, not inferred: exactly **one** `Log.i` call
site survives R8 in the entire app, and it is an unrelated bubble line. The `pack:` family, the
`npu: offer` line, `npu: encode=`, `segment-timing:` and `mel: bins=` are all Kotlin-side emissions
and all gone.

**The native half survives untouched** — `__android_log_print` is not something R8 can reach — and
it is more than enough to prove the NPU ran:

| still present in a RELEASE build (native, `WE-DIAG`) | what it proves |
|---|---|
| `nativeInit OK - encoder graph '…' (… in / … out), decoder graph '…'` | both context binaries deserialised on this silicon |
| `<label>: contextCreateFromBinary OK - cold load … ms` | the per-graph cold load, with its timing |
| `alias guard: N cross-KV pairs identical across encoder-out/decoder-in` | the cross-KV aliasing is sound (turbo: 8, npu: 24) |
| `mask: attention_mask scale … -> attend code … = 0.0000, blocked code … = -100.0000` | the mask codes dequantise correctly |
| `vote: …` | the HTP performance vote; `the HTP is running UNVOTED` is the warning twin |
| `encode: graphExecute OK in <ms> ms (vote: …)` | **one per segment — the NPU encoder actually ran** |
| `decode: <n> tokens in <ms> ms (<x> ms/token), terminated by …` | **one per segment — the NPU decoder actually ran** |
| `detect: language token <tok> (offset N in the language block)` | which language the model itself chose |
| `<label>: IO totals in … B, out … B` + the per-tensor IN/OUT enumeration | the census the guard checks against |

| ABSENT in a RELEASE build (Kotlin-side) | read this instead |
|---|---|
| `pack: fetch …` / `pack: ok …` / `pack: refused …` | **the card**: progress text, byte counts and the refusal sentence all render on screen |
| `npu: offer soc=… installed=… offered=…` | whether the gated cards appear at all in the chooser |
| `npu: encode=… decode=… tokens=… lang=…` | `encode: graphExecute OK` + `decode: N tokens` + `detect: language token` above |
| `segment-timing: seq=… transcribe=… rtf=…` | wall-clock feel, plus the two native per-segment timings |
| `mel: bins=… row0=… rowMid=… rowLast=…` | *(no release substitute — see the residual below)* |
| `npu: unavailable stage=…` | the tier card's own decline reason, which is the user-facing copy anyway |
| `npu: import ok entries=2 bytes=…` | the import UI's completion state |

> **Two consequences, both named rather than papered over.**
>
> 1. **The `pack:` line formats are pinned by JVM tests and are never executed on a device in this
>    program.** F5's format contract (`pack: fetch` per transition and per decile, `pack: ok
>    tier=… entries=… bytes=…`, `pack: refused tier=… reason=…`) is proven by unit tests and by
>    nothing else. The acceptance below therefore rests on the CARD, which is the stronger place to
>    rest it — the card is what a user sees — but the log format itself stays unexercised.
> 2. **`mel: bins=` has no release substitute, and it is the only numeric proof the 128-bin
>    filterbank applied.** If the owner ever wants to re-run 4.1's §3.7 stride check, it must be on
>    a debug build; it cannot be done in this session.
>
> **If the owner wants the `pack:` family observable on the track build, that is a one-line
> decision and it is theirs, not the implementer's**: narrowing the rule to
> `-assumenosideeffects class android.util.Log { public static int v(...); public static int d(...); }`
> keeps INFO/WARN/ERROR — every WE-DIAG line — in release. The cost is that a shipped app writes
> operational lines to logcat (never transcript content: that is removed at the call sites, at every
> level). **It was NOT changed here**, because the shipped app's log posture is a product decision
> and this task had no mandate to make it.

---

## §1 — bundletool local-testing: the plumbing, the wrong variant, the empty default

Three rungs on ONE phone, and they are possible on one phone because **`--device-groups` is
ASSERTED, not evaluated**: bundletool does not read the SoC, it simply installs the variant you
name. That is what turns a four-family fleet into three testable rungs here — and it is also why a
green here is *not* evidence that Play's server-side resolution works (§2 is the only thing that
tests that).

Local-testing limits, quoted from the research: the app fetches packs from external storage rather
than from Play, so there is **no network-error testing and no cellular-consent testing** on this
route, and **updates are unsupported — uninstall between installs.**

> # ⚠ THE UNINSTALL WINDOW — READ BEFORE THE FIRST COMMAND
>
> **Every `install-apks` over an existing install requires an uninstall, and an uninstall destroys
> `filesDir` — every model on the device.** This has destroyed the owner's models twice before.
> Outside the window below, the never-uninstall rule is absolute and no other document in this
> program may prescribe one.
>
> **This window contains THREE mandatory uninstalls and one conditional — a floor of three, a
> ceiling of four.** Each is named here with its restore path:
>
> | # | when | mandatory? | what it destroys | how it is restored |
> |---|---|---|---|---|
> | **u1** | before the first local install | **conditional** — only if a dev build is already on the device | whatever is on disk now | nothing is hand-restored; rung 1 lands its own turbo pair from the pack |
> | **u2** | before rung 2 (wrong variant) | **yes** | rung 1's turbo pair | nothing needs restoring: rung 2's whole point is a REFUSAL, which requires nothing on disk |
> | **u3** | before rung 3 (empty default) | **yes** | nothing (rung 2 installed nothing) | same — a refusal rung |
> | **u4** | before the §2 track install | **yes** | nothing (rung 3 installed nothing) | **the recovery IS the feature under test**: onboarding → language → turbo fetched from Play → the ggml tier re-downloaded in-app |
>
> **Nothing is ever hand-restored, and that is deliberate: a hand-restore would un-test the flow.**
> The window is scheduled as the last device work of the branch. If the session is abandoned
> part-way, the device is left with whatever the last rung installed and the in-app downloads are
> the way back — never `adb push`.

Both commands need absolute interpreters (`java` is not on PATH, and `install-apks` shells out to
`adb`, which is not on PATH either).

### Rung 1 — the plumbing (and the §0 release gate)

```powershell
# keystore.properties below is RELATIVE to the repo root, like §0's script paths.
cd "C:\Users\bastr\OneDrive\Desktop\whisper Everywhere"
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
# Build the APK set once, signed with the RELEASE key: the dex, the native libs and the R8 output
# are the same ones the store build carries, so this really is the release gate. (It is not a
# byte-identical twin of the uploaded artifact — `--local-testing` injects local_testing metadata
# into the base APK's manifest. That is the only difference, and it is why this set can be
# side-loaded at all.)
# Read the three secrets out of keystore.properties; never type or echo them.
$p = @{}; Get-Content keystore.properties | ForEach-Object {
  if ($_ -match '^\s*([^#=]+)=(.*)$') { $p[$matches[1].Trim()] = $matches[2].Trim() } }
& "C:\Program Files\Android\Android Studio1\jbr\bin\java.exe" -Xmx8g -jar `
  C:\Users\bastr\.androidbuild\fleet-packs\bundletool-all-1.18.3.jar build-apks `
  --bundle=C:\Users\bastr\.androidbuild\WhisperEverywhere\app\outputs\bundle\release\app-release.aab `
  --output=C:\Users\bastr\.androidbuild\fleet-packs\fleet.apks --local-testing --overwrite `
  --ks='C:\Users\bastr\.keystores\whispereverywhere-release.jks' `
  --ks-pass="pass:$($p['storePassword'])" --ks-key-alias=$($p['keyAlias']) `
  --key-pass="pass:$($p['keyPassword'])"
# The keystore PATH is spelled out rather than read from $p['storeFile'] on purpose, and please
# leave it that way: keystore.properties is a Java properties file, so its storeFile value carries
# Java backslash escaping that Gradle's Properties.load() resolves and the naive regex above does
# NOT. Only the three secrets are read from the file; the path is the one field it would get wrong.

# u1 here IF a dev build is present:
# & "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe" uninstall com.whispereverywhere
& "C:\Program Files\Android\Android Studio1\jbr\bin\java.exe" -jar `
  C:\Users\bastr\.androidbuild\fleet-packs\bundletool-all-1.18.3.jar install-apks `
  --apks=C:\Users\bastr\.androidbuild\fleet-packs\fleet.apks `
  --device-groups=soc_8gen3 `
  --adb="C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"
```

**`build-apks` has been run and its output inspected** (no device needed for that half), so the
`--device-groups` rungs below are asserting against slices that are known to exist. It produced
`fleet.apks`, 5,897,628,265 B, containing exactly the ten targeted slices plus the two empty ones:

| asset slice | bytes |
|---|---|
| `asset-slices/npu_turbo-group_soc_8gen3.apk` | 1,071,694,374 |
| `asset-slices/npu_turbo-group_soc_7gen4.apk` | 1,142,264,358 |
| `asset-slices/npu_turbo-group_soc_8elite_galaxy.apk` | 1,071,374,886 |
| `asset-slices/npu_turbo-group_soc_8elite5_galaxy.apk` | 1,073,361,446 |
| `asset-slices/npu_small-group_soc_8gen3.apk` | 358,253,082 |
| `asset-slices/npu_small-group_soc_7gen4.apk` | 372,986,394 |
| `asset-slices/npu_small-group_soc_8elite_galaxy.apk` | 357,577,242 |
| `asset-slices/npu_small-group_soc_8elite5_galaxy.apk` | 358,973,978 |
| `asset-slices/npu_turbo-group_other.apk` | **8,549** |
| `asset-slices/npu_small-group_other.apk` | **8,549** |

The two `group_other` slices at ~8.5 KB (manifest and nothing else) are the empty-default rule
visible as a number: that is what an unmatched device downloads.

**And the one assumption the F8 layout fix made load-bearing has been checked against a real
generated slice rather than trusted.** Inside `npu_turbo-group_soc_8gen3.apk`:

```
assets/npu_turbo/metadata.json                        484 B
assets/npu_turbo/turbo_decoder_qairt_context.bin  295,854,080 B
assets/npu_turbo/turbo_encoder_qairt_context.bin  775,831,552 B
```

The `#group_soc_8gen3` suffix **is stripped at delivery**, so the pack materialises under
`assets/npu_turbo/` — which is exactly the directory `installFromPack` opens (it asks the same map
that named the pack to fetch). The two binaries sum to **1,071,685,632 B**, the landmark figure.
If the suffix were *not* stripped, every fetch would end in the empty-delivery refusal; it is
stripped, and this is the evidence.

**Expected:** the app installs; onboarding runs; the model step offers turbo with the steer badge;
the turbo pack is already on the device (local testing side-loads it), so the "fetch" completes
essentially instantly and goes straight to **Verifying** — a streamed sha256 over 1,071,685,632 B,
which takes **minutes** and is the honest cost of the guarantee. Then the card flips to installed
and turbo dictation works. **This is §0's gate: a release-built, release-signed app reaching turbo
dictation before anything is INSTALLED FROM THE TRACK.** A failure here is a proguard-rules or
packaging task, not a track surprise — which is exactly why the gate sits before §2 rather than
after it. (Per §0's ordering note: the upload *attempt* may already have happened as a size probe;
what this rung gates is the track INSTALL, not the upload.)

### Rung 2 — the wrong variant, refused BY NAME

```powershell
& "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe" uninstall com.whispereverywhere   # u2
& "C:\Program Files\Android\Android Studio1\jbr\bin\java.exe" -jar `
  C:\Users\bastr\.androidbuild\fleet-packs\bundletool-all-1.18.3.jar install-apks `
  --apks=C:\Users\bastr\.androidbuild\fleet-packs\fleet.apks `
  --device-groups=soc_7gen4 `
  --adb="C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"
```

The Fold6 is 8gen3 silicon; the asserted group hands it the **7gen4** variant. The app resolves its
own family from the SoC (never from the pack), reads the delivered `metadata.json` FIRST, and dies
on identity before a single binary byte is hashed.

**Expected on the card, close to verbatim** (the family ids are the census's own):

> **Wrong family variant: this pack is the 7gen4 variant and this device is 8gen3. Its binaries are
> compiled for different silicon, so get the 8gen3 pack instead. Nothing was installed.**

**What it would mean if this rung fetched, verified and INSTALLED instead:** the identity
cross-check is not on the delivery path, and every guarantee in the fleet design rests on a
different family's binaries never reaching a device's HTP. Stop and report — do not proceed to §2.
**What it would mean if the refusal appeared but named a hash or a size instead of the family:**
the metadata-before-a-byte ordering has been lost; the refusal is still correct but it arrived
after ~1 GB of hashing, and the ordering pin has drifted from the code.

Also check: the two **CPU** tiers are untouched and dictation still works on them. A per-tier
decline that takes the CPU tiers with it is the failure mode the whole ladder exists to prevent.

### Rung 3 — the empty default, refused BY NAME

```powershell
& "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe" uninstall com.whispereverywhere   # u3
& "C:\Program Files\Android\Android Studio1\jbr\bin\java.exe" -jar `
  C:\Users\bastr\.androidbuild\fleet-packs\bundletool-all-1.18.3.jar install-apks `
  --apks=C:\Users\bastr\.androidbuild\fleet-packs\fleet.apks `
  --adb="C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe"
```

No `--device-groups` flag at all → the **default** variant → `#group_other`, which contains one
0-byte `.gitkeep` and no `metadata.json`. That absence *is* the empty-delivery signature.

**Expected on the card, verbatim:**

> **Google Play delivered no model for this device — it is not in any device group this app
> publishes a pack for, so the pack arrived empty. Use 'Import model pair…' below instead. Nothing
> was installed.**

**And the sentence has to be true where it is standing:** an `Import model pair…` control must be
visible *below* that copy on the chooser card. This is the one rung that tests the F5/F7 adjacency
rule end to end — the copy names an affordance, and the affordance is either there or the copy is a
lie. On the ONBOARDING surface the wording is deliberately different (that surface has no import
control): it should read *"Google Play couldn't deliver this model — finish setup with an on-device
model and import from Settings later."*

**What it would mean if a model installed here:** the default variant is not empty, which means
every unmatched device on the store is downloading model bytes it can never use.

---

## §2 — THE INTERNAL-TRACK SESSION (this is the acceptance)

Upload the AAB to the **internal test track** (if the size probe in §0 has not already done it).
**Do not start this section until §1 rung 1 has passed.** Then, on the Fold6:

- **u4 first — mandatory, not conditional.** Rung 3 leaves the local-testing build installed, so
  it is always present at this point, and Play cannot install over it: same versionCode, and a
  local `install-apks` set is not a Play-managed install in the first place.
- **Start the capture now** if you want §5.1's and §5.3's native evidence (§0.5 explains why these
  lines are the session's only NPU telemetry on a release build). Leave it running through step 8:

  ```powershell
  cd "C:\Users\bastr\OneDrive\Desktop\whisper Everywhere"
  & "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat -c
  & "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat -s WE-DIAG *> capture.txt
  ```

  `logcat -c` clears the buffer first so the file holds this session and nothing else; `*>`
  redirects both streams (a bare `2>&1` on a native exe is forbidden here — PowerShell 5.1 wraps
  stderr into ErrorRecords). **Ctrl-C to stop it after step 8.** `capture.txt` is what §3's greps
  read and what fills §5.3's two timing columns; without it those columns cannot be filled and
  §5.1 loses half its instrument. **This is the only adb use in §2 besides the getprop below, it
  is passive, and it touches nothing on the device** — the acceptance itself is still "reach turbo
  dictation without adb", and it is satisfied whether or not you take the capture.
- Install **from the track**, in the Play Store app, like a user.
- **Then put the laptop down. No adb for the flow itself** — the capture just runs.

The walk, in order, with what to record at each step:

| step | what to do | what to record |
|---|---|---|
| 1 | Grant permissions | — |
| 2 | **The language step** (new in 4.2, and it comes FIRST) | **the first row shown** — the device's own language, badged; and that the auto row's subtitle reads the ruled text; and that **Continue stays locked until a row is tapped** |
| 3 | **The model step** | that **turbo heads the list with the steer badge**, and the byte figure on its card (turbo's 8gen3 pair is **1,071,685,632 B**) |
| 4 | Tap Get / Download on turbo | the progress copy as it moves: *"Starting the download from Google Play…"* → *"Downloading from Google Play… N%  (x / y)"* with **Play's own byte counts** |
| 5 | **If on cellular**: the >200 MB consent dialog | **screenshot it.** This is its first exercise ever — it cannot be tested locally (research §5), so this is the only place it is ever seen |
| 6 | Verifying | *"Verifying… N%"* — minutes on a 1.07 GB pair, and **no Cancel button** in this phase (deliberate, and NOT a hang; see **§4 watch item 7**) |
| 7 | The card flips to installed | **that it flips at all, without leaving the screen** — see watch item 4 |
| 8 | Dictate on turbo | **the transcript, verbatim** |

**The one adb READ in the whole session, and it is evidence-gathering, not acceptance** (the
capture above is passive; this is the only command that asks the device a question). After the flow
is finished, with nothing left to prove:

```powershell
& "C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell getprop ro.soc.model
```

Record whether the Fold6 answers **`SM8650`** or **`SM8650-AC`**. This closes the research §5
question the census has carried since 4.0. **Both strings stay listed in the census either way** —
the group carries both by design — so this changes an `evidence` string and nothing else. And note
what has already happened by the time you read it: **Play's server-side group resolution delivering
the 8gen3 variant to this phone IS the `-AC` suffix check executing in production.** The getprop
just tells us which side of it fired.

`capture.txt` (started before the install, stopped after step 8) holds the native lines from
§0.5's first table — `encode: graphExecute OK`, `decode: N tokens`, `vote:`,
`detect: language token`. It holds nothing from the second table, and that is not a fault. §3 greps
it; §5.1 and §5.3 read their numbers out of it.

---

## §3 — The greps

Run against `capture.txt` — the file §2 tells you to start before the install and stop after step
8. `-SimpleMatch` because several needles carry `(` `)` `>`.

**On a RELEASE build (the track install and the §1 local installs) — these are the ones that exist:**

```powershell
Select-String -SimpleMatch "nativeInit OK"        capture.txt
Select-String -SimpleMatch "cold load"            capture.txt
Select-String -SimpleMatch "alias guard:"         capture.txt
Select-String -SimpleMatch "mask: attention_mask" capture.txt
Select-String -SimpleMatch "vote: "               capture.txt
Select-String -SimpleMatch "encode: graphExecute" capture.txt
Select-String -SimpleMatch "decode: "             capture.txt
Select-String -SimpleMatch "detect: language"     capture.txt
```

**On a DEBUG build only** — kept here because they are the greps every earlier sheet used, and
because a future debug session should not have to re-derive them. **Expect zero hits from a release
capture; zero hits is the designed behaviour, not a finding:**

```powershell
Select-String -SimpleMatch "pack: fetch "     capture.txt
Select-String -SimpleMatch "pack: ok "        capture.txt
Select-String -SimpleMatch "pack: refused "   capture.txt
Select-String -SimpleMatch "npu: offer "      capture.txt
Select-String -SimpleMatch "npu: encode="     capture.txt
Select-String -SimpleMatch "segment-timing: " capture.txt
Select-String -SimpleMatch "mel: bins="       capture.txt
```

The success landmark those greps were written for, for the record and for any future debug run:
`pack: ok tier=npu-turbo entries=2 bytes=1071685632`.

---

## §4 — The watch list: eight rungs this branch's tasks and reviews accumulated

Each is a thing to look at, and what it would mean. None of them is a pass/fail gate on its own
except where it says so.

**1. The AAB inspection (F4 M11 + the F4 review's one pre-upload residual).** §0.2. Covered above
and green when this sheet was written; it stays in the run-book because it must run before *every*
upload, not once.

**2. The post-`removePack` replay, chooser edition (F5 review + F7 §2.3).** After a successful
turbo install, watch the card for the next several seconds while `removePack` runs. The chooser
renders `NpuPackController.state` **directly** (the deliberate choice for a persistent surface — a
stop-at-first-terminal collector would freeze the card against the *next* fetch the same screen can
start), so a Play replay could in principle reach it. **Acceptable:** at most a momentary flash of
the resting "Get on Google Play" button before the installed card lands. **A finding:** an
*installed* card REVERTING to fetch UI — that would falsify the containment argument (tier-equality
gate + the installed arm reading disk before the fetch arm) and must be reported.

**3. The `NOT_INSTALLED`-replay arrival into Idle (F6 M-2).** Cancel a fetch mid-download from the
ONBOARDING model step. Play can replay `CANCELED → NOT_INSTALLED`, and StateFlow conflation can
skip the terminal `Cancelled` and park the card at *"Preparing"*. **If the card is stuck on
Preparing with nothing moving, relaunch the app — that is the recovery**, and the mapping is
deliberately non-terminal (mapping Idle to Failed would flash false refusals in the start gap).
Narrow and device-only; report it if seen, do not treat it as a blocker.

**4. The installed-card generation flip (F7).** Step 7 of §2. `notifyModelInstalled()` bumps the
install generation, the producers re-run, and the card becomes the installed card. **This is the
one transition no JVM test can execute.** Watch also for the brief window where the action area
renders *nothing at all* (deliberate, F7 micro-round m-2): it should read as a short blank, not a
flicker of a tappable button.

**5. THE TURBO SAF IMPORT — reachable from the chooser for the FIRST time (F7 fix I-2).** On the
turbo card, resting state, tap **"Import model pair…"** and pick a turbo delivery zip. Until this
branch that control did not exist on the turbo card, and **no JVM test can execute a document
pick**. What must be true: the import lands turbo's OWN pair (`turbo_encoder_qairt_context.bin` /
`turbo_decoder_qairt_context.bin`), not npu's. Read the whole card top to bottom in both its resting
and its failed state first: every route it names ("Import model pair…", the bridge sentence *"You
can also import the model pair from a zip below."*, the ruled adjacency copy) must resolve to a
control **on that card**.

**6. The M-3 cross-surface walk (F6 M-3 → F7 §2.1, and the two-card contention from F7 I-1).**
Start a fetch on one gated tier, then immediately try the other — from the other surface, and again
from the same one:
- chooser → onboarding: the engine card must read *"Another model is downloading from Google Play
  right now…"* and must **never mirror the other tier's progress**;
- chooser → chooser (both gated cards show Get at once on this device): the tapped card must read
  *"Another model is already downloading from Google Play. Wait for it to finish, then tap Get
  again."* with its own Get button still live below it, and the fetching card unaffected.
- **Both windows, same sentence:** tap the sibling during DOWNLOAD and again during VERIFY. The
  sentence must be identical and must **not** mention Cancel (the verify arm shows none).
- **The refusal must retire itself:** leave it on screen and let the blocking fetch finish. The
  sentence must disappear **without the user touching the card**.
- Then Retry on the first card: it must proceed normally.

**7. THE VERIFYING-CANCEL QUESTION — open by design, and NOT a session task (F7 micro-round m-1).**
`cancel()` during **Verifying** is currently a no-op because the phase renders no Cancel control.
**Current behaviour, which is what the sheet asserts:** the Verifying phase is non-cancellable; the
user sees *"Verifying… N%"* with no cancel affordance, for minutes on a 1.07 GB pair; the only way
out is to leave the screen (the fetch continues) or to let it finish. **Why it is not simply made
cancellable:** `cancel()` would cancel the install coroutine mid-`installFromPack`, whose tail is
the shared parking transaction (`finalizeVerifiedPair` — park, rename into place, verify, roll
back) that both arrival routes depend on. Kotlin cancellation is cooperative, so where it lands
inside that transaction depends on which calls suspend, and *"the pair is renamed into place"* is
exactly the invariant the transaction exists to protect. **Making it cancellable requires proving
that transaction's cancellation safety first — a named follow-up task, not something to try during
this session.** If the wait is judged unacceptable, that is the trigger; record the judgement here.

**8. The census evidence rung.** `adb shell getprop ro.soc.model` — §2's one adb read. `SM8650` or
`SM8650-AC`; the answer is written into the census's 8gen3 `evidence` string and nothing else moves.

---

## §5 — The owner's own acceptance criteria

These are the owner's, not the plan's, and they were named at the ship-first ruling.

### 5.1 Language switching, re-tested (the per-utterance ruling, L7)

On an npu-class tier under **auto**, every segment carries its own honest note and there is **no
session latch** — a switch mid-session must be picked up per utterance, not pinned by the first
detection. The CPU `multi` tier by contrast latches deliberately; both behaviours are correct, per
backend.

On a release build the Kotlin `lang=` note is stripped, so the per-utterance evidence is the
**transcript itself** plus the native `detect: language token …` line (one per segment) — the
second half of that comes from `capture.txt`, so **keep §2's capture running for these rows too**
(or restart it the same way before dictating). The transcript alone still carries the ruling if you
would rather not. Dictate one deliberately code-switched utterance per npu-class tier — start in
English, finish in another language:

| tier | code-switched transcript (verbatim) | switched cleanly? | notes |
|---|---|---|---|
| npu-turbo | | | |
| npu | | | |

**A repeated first-language transcript after the switch is the latch regression** and is a finding.

### 5.2 The A/B lineup order — turbo heads where offered

Confirm by eye, on the capable device, in **both** surfaces:

| surface | turbo first? | steer badge on turbo? | byte figure on the card |
|---|---|---|---|
| onboarding model step | | | |
| Settings → model chooser | | | |

And on the same screens, the honest negative: the byte badge must state the **family's measured
pair bytes** (8gen3 turbo: 1,071,685,632 B), not a catalog approximation.

### 5.3 The A/B rows

One fixed passage, three times per tier, switching tiers between rows. **The two timing columns are
read out of `capture.txt`** (§2's capture, running): `encode: graphExecute OK in <ms> ms` and
`decode: <n> tokens in <ms> ms (<x> ms/token)`, one of each per segment. Without the capture those
two columns cannot be filled — the transcript column and the subjective note still can, and a sheet
returned with only those is a partial row, not a failed one.

| # | tier | transcript (verbatim) | encode ms (native `encode:` line) | decode ms / tokens | subjective note |
|---|---|---|---|---|---|
| 1 | multi | | — | — | |
| 2 | multi | | — | — | |
| 3 | multi | | — | — | |
| 4 | npu | | | | |
| 5 | npu | | | | |
| 6 | npu | | | | |
| 7 | npu-turbo | | | | |
| 8 | npu-turbo | | | | |
| 9 | npu-turbo | | | | |

---

## §6 — Coverage: what this session proves, and what it does not

**Stated plainly, because a sign-off that implies more than it covers is worse than no sign-off.**

- **Three of the four families ship on BUILD-TIME evidence only.** `8elite_galaxy` (SM8750-AC, v79),
  `8elite5_galaxy` (SM8850-AD, v81) and `7gen4` (SM7750, v73) have no device anywhere in this
  program. Their packs are measured, digest-verified, metadata-cross-checked and refused on
  mismatch — **and never executed.** The first execution of those three variants is a customer's.
  This session exercises **exactly one family's row**: 8gen3.
- **The four-family gate walk is BUILD-verified only.** The census→gate→pack→XML agreement is proven
  by JVM tests and by the AAB inspection; no device has ever resolved to any family but 8gen3.
- **The 7gen4 tolerance fix is BUILD-verified only.** F3 measured both 7gen4 encoders sitting
  outside the ±5% `isInstalled` window around the catalog reference, and F5 fixed the gate to read
  per-family census bytes. That fix is proven by JVM tests over all four families. **The rung that
  would execute it — a correct 7gen4 import finishing instead of rolling itself back — cannot run:
  there is no 7gen4 device.** Rung 2 above installs the 7gen4 *variant* and is REFUSED by design, so
  it does not exercise the tolerance fix either.
- **Play-side group resolution is observable only through the track** (§2). bundletool asserts
  groups; §1 proves plumbing, not resolution.
- **The empty-default refusal is exercised locally, never in production.** A real unmatched device
  fetching is prevented by the gate; §1's rung 3 simulates it.
- **The cellular-consent dialog's first ever exercise is §2 step 5**, on cellular, and nowhere else.
- **The `pack:` log formats are never executed on a device** (§0.5).
- **One path survives the entire guard ladder and it is irreducible**: a vendor-published binary that
  is metadata-consistent, deserialises on that family's HTP, and is semantically wrong (bad weights,
  a subtle bin miscompile) produces fluent wrong text and passes every check. **Only a human reading
  a transcript detects it** — which is exactly what §5.3 is for on 8gen3, and what nobody has ever
  done for the other three families.

---

## Sign-off

| gate | owner verdict |
|---|---|
| §0 payload built, `verifyNpuPacks` green, AAB signed | |
| §0.1 release rung: release-built app reaches turbo dictation locally | |
| §0.2 AAB inspection: `DeviceGroupConfig.pb` present, all names, ten variant dirs | |
| §1 rung 1 — right variant installs and dictates | |
| §1 rung 2 — **wrong variant refused BY NAME** (the family sentence), CPU tiers unharmed | |
| §1 rung 3 — **empty default refused BY NAME**, with the import control actually below it | |
| AAB uploaded to the internal track and **accepted** by the Console (the size answer confirmed, §0) | |
| §2 u4 performed (mandatory) before the track install | |
| §2 track install → language step → turbo recommended → fetch → verify → **turbo dictation, no adb** | |
| §2 cellular consent dialog seen and screenshotted (if on cellular) | |
| §2 `getprop ro.soc.model` recorded: `SM8650` / `SM8650-AC` | |
| §4 watch items 2–7 observed; anything anomalous written down | |
| §5.1 language switching — per-utterance, no latch | |
| §5.2 lineup order — turbo heads both surfaces, real byte figures | |
| §5.3 A/B rows complete; owner's tier verdict | |
| **Owner verdict: ship / hold** | |
