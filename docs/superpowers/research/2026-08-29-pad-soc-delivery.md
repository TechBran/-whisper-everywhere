# Play Asset Delivery × Per-SoC Model Packs — Verified Facts for the 4.2 Fleet-Onboarding Spec

**Web + live-asset verification, 2026-08-29.** Follows the [2026-08-27 NPU decision brief](2026-08-27-npu-whisper-turbo-research.md)
and the [2026-08-29 model-lab asset survey](2026-08-29-npu-model-lab-assets.md). Owner ruling in force:
**Google Play Asset Delivery, no self-hosting.** Everything below was checked today against live docs,
live Maven, and the live Qualcomm S3 bucket — not recalled from training data. Method in §9.

---

## 0. Answers in five lines

1. **Mode:** **on-demand asset pack** (or its ML-flavored twin, an **AI pack**). Per-pack cap is
   **1.5 GB compressed download**; our turbo variant is ~**860 MB compressed / 1.02 GiB unpacked** —
   fits with ~640 MB headroom. Install-time and fast-follow also fit by size but are wrong UX (§2).
2. **SoC targeting:** **EXISTS and works, still BETA** (both docs say "beta" as of today).
   `<config:system-on-chip manufacturer="QTI" model="SM8650"/>` selectors, API 31+ only.
   **Fallback:** unmatched devices get the *default variant* of the pack — which we make **empty** —
   and under on-demand they additionally download nothing unless the app calls `fetch()`. Non-NPU
   devices get no bytes. (§3)
3. **Testing:** local = bundletool `--local-testing` + `install-apks --device-groups=<name>`
   (group is *asserted*, not evaluated, locally); real targeting = internal app sharing or the
   **internal test track**, which is full production behavior — **the Fold6 on the internal track can
   receive the 8gen3 variant end-to-end**, provided its exact `Build.SOC_MODEL` string is in the group. (§5)
4. **Deal-breakers:** none found. Watch items: beta status; exact-string SoC matching (must enumerate
   `-AC`/`-AD` bins); sideloads get no packs (SAF import stays); AI-pack models must be app-exclusive
   (helps, not hurts — it matches Qualcomm's no-standalone-distribution clause). (§6)
5. **Qualcomm coverage (w8a16 turbo, verified today):** 4 mobile families — 8 Gen 3 (HTP v75),
   8 Elite for Galaxy (v79), 8 Elite Gen 5 for Galaxy (v81), 7 Gen 4 (v73). All assets hash-stable
   since the 2026-08-25 re-upload. Model zips carry **no skels**; the single `qnn-runtime` 2.49.0 AAR
   carries skels for **every** HTP arch v68–v81. (§7, §8)

---

## 1. Play Asset Delivery limits — current numbers

From the Play Console size-limits page ([support.google.com answer 9859372](https://support.google.com/googleplay/android-developer/answer/9859372), fetched today):

| Limit | Value |
|---|---|
| Base module | 500 MB |
| Individual feature module | 500 MB |
| **Individual asset pack** | **1.5 GB** |
| Cumulative: all modules + **install-time** packs | 4 GB |
| Cumulative: **on-demand + fast-follow** packs | **30 GB** |
| Total max compressed download per app | 34 GB |
| Max asset packs per bundle | 100 |
| Apps > 1 GB | must have minSdk ≥ 21 (we are far above) |

All limits are on **compressed download size, as computed by Play Console at upload** — not the
unpacked size. Our numbers against them:

| Our pack | Unpacked | Play-compressed (≈ deflate, measured from the vendor zips) | Fits 1.5 GB? |
|---|---|---|---|
| turbo w8a16, per SoC | 1.02–1.04 GiB | **~860 MB** (encoder 776→599 MB, decoder 296→261 MB) | **Yes, ~640 MB headroom** |
| small w8a16, per SoC | 342 MiB | ~280 MB | Trivially |
| *(hypothetical full large-v3)* | ~1.82 GiB | ~1.46 GB | Grazes the cap — one more reason §6 of the lab doc said don't |

Even 4 turbo variants + 4 small variants ≈ 4.6 GB compressed — 15 % of the 30 GB on-demand budget.

**Compression handling:** QNN context binaries are *not* already-compressed — they deflate ~23 %
(measured: the vendor's own zips). Ship the **raw `.bin` files** in the pack; Play compresses in
transit and delta-patches across app updates. Do not pre-zip: it would double on-device disk during
unpack, break Play's delta patching, and add an unzip step to our load path for zero size win.

## 2. Mode verdict: on-demand

- **install-time** — counts against the 4 GB cumulative cap and the store-listed app size, forces
  ~860 MB onto every matched device at install, cannot be removed by the user. Wrong for an opt-in tier.
- **fast-follow** — auto-downloads ~860 MB right after install without the user opening the app.
  Wrong for an opt-in tier (and for goodwill).
- **on-demand** — downloaded only when the app calls `fetch()`, i.e. **after our R0–R3 gates pass
  and the user opts in**. Does not inflate listed size. Removable (`removePack()`). Play requires
  user confirmation for >200 MB on cellular (`REQUIRES_USER_CONFIRMATION` / `WAITING_FOR_WIFI` →
  `showConfirmationDialog()`), which is exactly the consent flow a 1 GB model download should have.

**Verdict: on-demand.** This also makes the fallback story air-tight (§3): a pack that is never
fetched is never delivered, whatever group the device lands in.

## 3. Device targeting by SoC — current state

Source: [Device targeting (beta)](https://developer.android.com/google/play/device-targeting) and
[Device targeting for asset delivery (beta)](https://developer.android.com/guide/playcore/asset-delivery/device-targeting), both fetched today.

- **Status: BETA**, explicitly, on both pages. It has been beta for years; the API surface is stable
  and it is the documented mechanism Google itself points ML deliveries at (§4), but there is no GA
  commitment and Google reserves breaking changes. Not a blocker; a watch item.
- **Targeting dimensions** (per device-*group* selector; up to 5 selectors per group, OR between
  selectors, AND between properties inside one selector):
  - `ram-min-bytes` / `ram-max-bytes`
  - included/excluded device ids (`brand` + `device`, max 10,000/group)
  - required/forbidden **system features** (max 100/group)
  - **`<config:system-on-chip manufacturer="…" model="…"/>` — API 31+ only**, matched against
    `Build.SOC_MANUFACTURER` / `Build.SOC_MODEL`. (No GPU dimension exists.)
- **Groups vs tiers:** device *groups* are the mechanism for asset-pack content variants (suffix
  `#group_<name>` on directories inside ONE pack). Device *tiers* (the older leveled system via the
  Play Developer API `deviceTierConfigs` resource) still exist but the current AGP-embedded flow is
  groups. Same pack name, per-group contents — exactly our shape.
- **Priority:** a device matching multiple groups gets the group **listed first in the XML**.
- **Fallback semantics (the critical one):** a device matching **no group** receives the **default
  variant** — the unsuffixed directory (or `#group_other`). Two facts matter:
  1. *"It is not possible to prevent any variant from being delivered"* — every device maps to
     *some* variant. So we make the default variant **empty** (a bytes-free stub). A non-NPU device
     that somehow fetched the pack would receive ~nothing.
  2. Under **on-demand**, delivery only happens on `fetch()` — which our gate never calls on
     non-NPU devices. Defense in depth: **non-NPU devices get no pack, by two independent mechanisms.**
- **API 31+ floor for SoC selectors is a feature for us, not a bug:** devices below 31 cannot match
  an SoC group → they land in the empty default. Our runtime gate R0 already requires SDK ≥ 31 for
  `Build.SOC_MODEL`. The two floors coincide.

### Group design sketch (one group per Hexagon package, priority order)

```xml
<config:device-targeting-config xmlns:config="http://schemas.android.com/apk/config">
  <config:device-group name="soc_8e_gen5">   <!-- v81 pack -->
    <config:device-selector>
      <config:system-on-chip manufacturer="QTI" model="SM8850-AD"/>
      <config:system-on-chip manufacturer="QTI" model="SM8850"/>   <!-- see §6 caveat -->
    </config:device-selector>
  </config:device-group>
  <config:device-group name="soc_8elite">    <!-- v79 pack -->
    <config:device-selector>
      <config:system-on-chip manufacturer="QTI" model="SM8750-AC"/>
      <config:system-on-chip manufacturer="QTI" model="SM8750"/>
    </config:device-selector>
  </config:device-group>
  <config:device-group name="soc_8gen3">     <!-- v75 pack -->
    <config:device-selector>
      <config:system-on-chip manufacturer="QTI" model="SM8650"/>
      <config:system-on-chip manufacturer="QTI" model="SM8650-AC"/>
    </config:device-selector>
  </config:device-group>
  <config:device-group name="soc_7gen4">     <!-- v73 pack -->
    <config:device-selector>
      <config:system-on-chip manufacturer="QTI" model="SM7750"/>
      <config:system-on-chip manufacturer="QTI" model="SM7750-AB"/>
    </config:device-selector>
  </config:device-group>
  <!-- everything else → auto default group "other" → empty variant -->
</config:device-targeting-config>
```

*(Manufacturer string: the docs' own Snapdragon example uses `QTI`; our runtime gate already accepts
{QTI, Qualcomm}. The `model` strings above need the §6 device-session verification.)*

## 4. Play for On-device AI — the purpose-built wrapper, and probably our vehicle

[Play for On-device AI (beta)](https://developer.android.com/google/play/on-device-ai) is PAD
specialized for exactly our payload: **"AI packs"** are asset packs that contain **custom ML models
only** (no code/libs allowed inside), with the same three delivery modes, the same 1.5 GB compressed
per-pack cap, and **first-class device-targeting integration** (`#group_` dirs, same
`device_targeting_config.xml`). Arbitrary binary model formats are explicitly fine — nothing
LiteRT-specific. Extra facts verified today:

- Client library: `com.google.android.play:ai-delivery` — **0.2.0-beta01, published 2026-08-06 on
  Google Maven** (graduated from 0.1.1-alpha01; the beta track is moving).
- Runtime API mirrors AssetPackManager 1:1: `AiPackManager.fetch()`, `getPackLocation().assetsPath()`,
  `AiPackStateUpdateListener` (PENDING/DOWNLOADING/TRANSFERRING/COMPLETED/FAILED/
  WAITING_FOR_WIFI/REQUIRES_USER_CONFIRMATION), `showConfirmationDialog()`, `removePack()`.
- Module type: `com.android.ai-pack` Gradle plugin, `aiPack { packName; dynamicDelivery { deliveryType } }`.
- Requirements: AGP ≥ 8.8 for AI packs, **≥ 8.10 for device targeting** + 
  `android.experimental.enableDeviceTargetingConfigApi=true` in `gradle.properties`.
  **Our repo is on AGP 8.13.2** (root `build.gradle.kts`) — compatible.
- Delta patching: unchanged pack across app updates → only diffs download; changed pack → full
  re-download, old deleted.
- Policy string that matters: *"Models downloaded by Play for On-device AI should only be used by
  your apps; models shouldn't be offered to other apps."* We load them in-process only. Note the
  happy alignment: Qualcomm's license bars *standalone* redistribution, Google's bars *cross-app*
  serving — PAD-inside-our-app satisfies both by construction (the 08-27 brief §6 called this).

**AI pack vs plain asset pack:** functionally near-identical for us. AI pack pros: semantic fit,
policy clarity, Google's ML-delivery investment path. Con: the client lib is `0.2.0-beta01` while
`asset-delivery` is long-GA (only the *targeting* is beta there). Either works; the spec should pick
one and note the other as a one-day fallback — the bundle layout (`#group_` dirs +
`device_targeting_config.xml`) is identical in both.

## 5. Developer workflow + the testing story

**Declaring the pack** (same pack name, per-SoC contents):

```
npu_turbo/                                   ← AI-pack (or asset-pack) module
  build.gradle: com.android.ai-pack, packName "npu_turbo", deliveryType "on-demand"
  src/main/assets/
    model#group_soc_8gen3/    encoder.bin decoder.bin metadata.json
    model#group_soc_8elite/   …
    model#group_soc_8e_gen5/  …
    model#group_soc_7gen4/    …
    model/                    ← default variant: EMPTY (or a 1 KB marker file)
app/build.gradle.kts:
  assetPacks += ":npu_turbo"
  bundle { deviceTargetingConfig = file("device_targeting_config.xml")
           deviceGroup { enableSplit = true; defaultGroup = "other" } }
```

App code references `npu_turbo/assets/model/…` — the suffix is stripped at build time; each device
sees exactly one variant under the unsuffixed path. Bundle carries them as
`npu_turbo.config.group_soc_8gen3` etc. splits. **Play Console side: nothing extra** — the targeting
XML travels inside the AAB with AGP ≥ 8.10 (the older separate upload via the Play Developer API
`applications.deviceTierConfigs` REST resource still exists but is not needed).

**Testing, three rungs** ([test doc](https://developer.android.com/guide/playcore/asset-delivery/test) + PODAI doc):

1. **Local, no Play:** `bundletool build-apks --bundle=app.aab --output=app.apks --local-testing`
   then `bundletool install-apks --apks=app.apks --device-groups=soc_8gen3`. The group membership is
   **asserted on the command line, not evaluated from the device's SoC** — bundletool does not read
   `ro.soc.model`. Good for plumbing, wrong-variant, and empty-default tests on any device.
   Local-testing limits: fast-follow behaves as on-demand; packs come from external storage (no
   network-error / wifi-consent testing); **updates unsupported — uninstall between installs**.
   Verify what landed with `adb shell pm path <pkg>` (install-time splits) or the pack's
   `assetsPath()` + our sha256 (on-demand).
2. **Internal app sharing:** upload AAB, tap link on device — documented as *"the exact same
   behaviour as your users"*, i.e. real server-side group resolution. Fastest real-targeting check.
3. **Internal test track:** full production path. **The owner's Fold6 on the internal track gets
   real end-to-end delivery: Play resolves its SoC server-side against the group and `fetch()`
   pulls the 8gen3 variant** — conditional only on the group containing the Fold6's exact
   `Build.SOC_MODEL` string (SM8650 vs SM8650-AC — one `adb shell getprop ro.soc.model` in the next
   device session settles it; put both in the group regardless).

## 6. Constraints that could bite, and why none break us

| Constraint | Finding | Our posture |
|---|---|---|
| **Byte integrity of delivered files** | Play compresses **in transit** and delta-patches **between versions**, but the materialized files at `assetsPath()` (on-demand/fast-follow) are the bytes from the AAB — assets are not transcoded. Install-time variants sit inside split APKs, read byte-exact via `AssetManager`. | Keep the existing **sha256-verify-before-load** as the invariant: any surprise → treat pack as absent → SAF path. Integrity failure becomes a clean fallback, not a crash. |
| **Signature/verification** | Split APKs are Play-signed like the base; on-demand pack payloads are Play-integrity-checked in the Play pipeline. Nothing app-visible changes for file contents. | Nothing to do beyond the hash check we already require. |
| **Sideloaded / non-Play installs** | Asset/AI packs are delivered **only** by Play. A sideloaded build gets `getPackLocation() == null` / fetch failure. | **The SAF import path must remain**, permanently, as the non-Play fallback — same loader, same hash gate, source-agnostic model directory. |
| **Devices matching no group** | Get the **empty default variant**, and under on-demand only if they fetch — which the gate prevents. | Correct-by-construction; add a CI check that the default variant stays empty. |
| **Exact-string SoC matching** | Play matches literal `Build.SOC_MODEL` strings. Samsung bins (`-AC`, `-AD`, `-AB`) are *distinct strings*; a missing string silently lands a capable device in the empty default (fail-safe, but lost coverage — never a wrong pack, because our loader also checks the pack's own `metadata.json` `soc_model`/`htp_version` before load). | Enumerate all known bins per family; log `SOC_MODEL` in gate telemetry to catch misses. |
| **Beta status** (targeting + PODAI + ai-delivery lib) | No GA date. API surface stable for years; Google is actively investing (ai-delivery beta01 on 2026-08-06). | Accept; pin lib versions; keep SAF path as the permanent hedge. |
| **Policy on large ML packs** | No size-specific policy beyond the caps in §1. PODAI: models are for our app only. >200 MB cellular fetch requires user confirmation (built-in dialog). | Both align with our design. |

**No deal-breaker.** The one *architectural* consequence: because unmatched devices can never be
*prevented* from getting the (empty) default variant, the app-side gate — not Play — remains the
authority on whether the NPU path runs. Play targeting is a bandwidth optimization, not a
correctness mechanism. That is exactly how the R−1…R6 gating design already treats it.

## 7. Qualcomm w8a16 turbo coverage — re-verified today, per SoC family

`release_assets.json` (via HF `resolve/main`) re-fetched for both w8a16 models today; identical
12-key chipset lists, still release **v0.61.0**, QAIRT **2.45.0.260326154327**, three flavours
(`precompiled_qnn_onnx` / `qnn_context_binary` / `voice_ai`). Mobile rows below verified by HTTP
HEAD **and** Range-reading each zip's `metadata.json` (no full downloads):

| SoC family | HTP | `soc_model` (alias) | Reference device | turbo zip (compressed) | Status today |
|---|---|---|---|---|---|
| **Snapdragon 8 Gen 3** (`8gen3`) | **v75** | 57 (`sm8650`) | Galaxy S24 family | 859,786,903 B | 200 OK, Last-Modified 2026-08-25 04:04:27 |
| **Snapdragon 8 Elite for Galaxy** (`8-elite-for-galaxy`) | **v79** | 69 (`sm8750-ac`) | Galaxy S25 family | 859,689,781 B | 200 OK, 2026-08-25 04:04:26 |
| **Snapdragon 8 Elite Gen 5 for Galaxy** (`8-elite-gen5-for-galaxy`) | **v81** | 87 (`sm8850-ad`) | Galaxy S26 family | 860,709,426 B | 200 OK, 2026-08-25 04:04:27 |
| **Snapdragon 7 Gen 4** (`7gen4`) | **v73** | 86 (`sm7750`) | 7 Gen 4 QRD | 871,118,306 B | 200 OK, 2026-08-25 04:04:28 |
| Snapdragon X Elite / X2 Elite | — | — | compute (Windows) | — | not Android-relevant |
| 8 IoT/auto keys (qcm6690, qcs8275, qcs8550-proxy, qcs9075, sa7255p, sa8775p, …) | — | — | — | — | not phone-relevant |
| 8 Gen 2 / 8 Gen 1 / 888 (w8a16) | — | — | — | — | **still absent** |

- **Nothing newer since our 08-25/08-29 work** — no new chipset keys, no plain (non-Galaxy)
  8 Elite / 8 Elite Gen 5 keys, no 8gen4-style key (naming went 8 Gen 3 → 8 Elite → 8 Elite Gen 5).
- **Hash-stable:** every mobile zip's `Last-Modified` is still the 2026-08-25 04:04–04:05 GMT
  re-upload event; the 8gen3 turbo and small sizes are byte-identical to the copies we sha256'd
  (`1e0e05c3…` for turbo, sizes 859,786,903 / 293,598,974). ETags recorded (multipart — not md5,
  use size+our sha256 as identity). **Pin our own sha256 per variant at pack-build time**; the
  bucket has re-uploaded in place before and nothing prevents it doing so again.
- Two curiosities for the spec, *not* claims: (a) **7 Gen 4 is HTP v73 — the same arch as
  8 Gen 2** — but its binary is compiled for `soc_model 86`; whether it loads on SM8550 is
  unverified (`QNN_COMMON_ERROR_INCOMPATIBLE_BINARIES` risk) — a 30-minute device experiment if an
  8 Gen 2 device ever materializes, not a coverage claim. (b) Whether `-for-galaxy` binaries load
  on plain SM8750/SM8850 remains the 08-27 brief's open question #8.

## 8. The skel story — settled by inventory

Downloaded `com.qualcomm.qti:qnn-runtime:2.49.0` from Maven Central today (67,007,460 B AAR — still
the latest; repo1 `maven-metadata.xml` lastUpdated 2026-08-12) and listed all 28 entries:

- **The model packages carry no skels — no `.so` at all** (verified for all four mobile turbo zips
  today: each contains exactly `encoder_qairt_context.bin`, `decoder_qairt_context.bin`,
  `metadata.json`, two tiny ONNX wrappers).
- **The single qnn-runtime AAR carries the skel + stub for *every* HTP arch** (arm64-v8a only):
  `libQnnHtpV68Skel` 10.6 MB, `V69` 12.0 MB, `V73` 17.9 MB, **`V75` 17.9 MB**, `V79` 17.7 MB,
  `V81` 18.8 MB, stubs ~0.77 MB each, plus `libQnnHtp` 3.8 MB, `libQnnSystem` 4.1 MB,
  `libQnnHtpPrepare` **79.3 MB** (only needed for on-device graph prepare — droppable with
  precompiled contexts, per the 08-27 brief), `libQnnGpu`/`libQnnDsp*` (droppable).
- **Consequence for the pack design:** per-SoC packs contain *only* the two context binaries
  (+ metadata). One runtime AAR in the base APK covers all four families, 7 Gen 4's v73 included.
  Optionally use packagingOptions excludes to strip V68/V69 skels + Prepare + GPU/DSP (~105 MB of
  never-used libs) from the base — an APK-size decision, not a correctness one.

## 9. Method

- Docs fetched today: PAD overview, Play size limits (answer 9859372), Device targeting (beta),
  Device targeting for asset delivery (beta), Play for On-device AI (beta), Asset-delivery test doc.
- Maven verified via repo1 `maven-metadata.xml` (qnn-runtime → 2.49.0) and Google Maven
  (`ai-delivery` → 0.2.0-beta01, 2026-08-06); qnn-runtime AAR downloaded and inventoried with
  Python `zipfile`.
- Qualcomm S3: `curl -I` on five zips (status/size/Last-Modified/ETag); `metadata.json` of all four
  mobile turbo zips read via HTTP-Range-backed `zipfile` (~1 MB transferred against ~3.4 GB of
  remote zips); both `release_assets.json` manifests re-fetched via HF.
- Scripts in the session scratchpad; nothing added to the repo but this file.

## 10. Sources

- https://developer.android.com/guide/playcore/asset-delivery
- https://support.google.com/googleplay/android-developer/answer/9859372
- https://developer.android.com/google/play/device-targeting
- https://developer.android.com/guide/playcore/asset-delivery/device-targeting
- https://developer.android.com/google/play/on-device-ai
- https://developer.android.com/guide/playcore/asset-delivery/test
- https://developers.google.com/android-publisher/api-ref/rest/v3/applications.deviceTierConfigs
- https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/ · https://dl.google.com/dl/android/maven2/com/google/android/play/ai-delivery/
- https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo-Quantized · …/Whisper-Small-Quantized (`resolve/main/release_assets.json`)
- `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/whisper_large_v3_turbo_quantized/releases/v0.61.0/…` (live HEAD + Range reads)
