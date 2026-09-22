# The NPU device census, and why two families matched no device

**Result.** `Build.SOC_MODEL` (the system property `ro.soc.model`) carries **no bin suffix** on any
device we could find. Every Galaxy S25-family phone reports plain `SM8750` and every Galaxy
S26-family phone reports plain `SM8850`. The census rows for those chips named only `SM8750-AC`
and `SM8850-AD`, so from 4.2 to 4.12 **`8elite_galaxy` and `8elite5_galaxy` matched zero
devices**: the gate denied the phones the packs were built for, and Play delivered their pack
variants to no one. Fixed in 4.13.0 (versionCode 107) by adding the plain strings, which by owner
ruling admits every 8 Elite / 8 Elite Gen 5 bin.

**Method.** A research workflow on 2026-09-22 (`wf_edfbb9f7-9f1`, 24 agents): one researcher per
census family plus one hunting literal `ro.soc.model` reads, skeptical fact-checkers re-sourcing
every device row, and a completeness pass. Then cross-checked against Google Play's own device
catalog export (snapshot 2026-08-13, 25,016 rows,
[hossain-khan/android-device-catalog-parser](https://github.com/hossain-khan/android-device-catalog-parser)).
Play's docs say to take system-on-chip strings from that catalog, and its System on Chip column
is what device targeting matches.

## 1. What devices actually report

| device | model | `ro.soc.model` | source (literal read) |
|---|---|---|---|
| Galaxy S25 | SC-51F (×2 builds), SM-S931B | `SM8750` | mouseos/ota-archive getprop JSON; onnxruntime#28899 |
| Galaxy S25+ | SM-S936B | `SM8750` | mouseos/ota-archive |
| Galaxy S25 Ultra | SM-S938B, SM-S9380, SM-S938N (to an Aug-2026 build), SM-S938U1 | `SM8750` | mouseos/ota-archive; two independent adb logs |
| Galaxy S25 Edge | SM-S937N | `SM8750` | LinDeX test fixture from a real unit (medium confidence) |
| Galaxy Z Fold7 | SM-F966B, SM-F9660 | `SM8750` | mouseos/ota-archive; AndroidSystemPropertyCollect |
| Galaxy S26 | SM-S942Q | `SM8850` | hf-to-litertlm device_classes.json (adb, 2026-09-02) |
| Galaxy S26 Ultra | SM-S948U (full getprop), SM-S948U1, SM-S948B, SM-S948N | `SM8850` | GhostHand device-props.txt; AWS Device Farm probe; two more |
| Galaxy Z Fold8 | SM-F971U | `SM8850` | Prey docs/DEVICE.md (Android 17, 2026-09-21) |
| Galaxy S24 / S24+ / S24 Ultra, Z Flip6, Z Fold6 (docomo SC-55E) | several | `SM8650` | mouseos/ota-archive; firmware dumps |
| Galaxy S23 / S23+ / S23 Ultra | several, incl. the owner's SM-S918U | `SM8550` | first-hand adb read (owner's S23 Ultra); dumps |
| REDMAGIC 10S Pro (an "8 Elite Leading Version" bin) | NX789J | `SM8750` | AndroidSystemPropertyCollect |
| OnePlus 13T, Xiaomi 15, Xperia 1 VII | — | `SM8750` | same archives |
| Xiaomi 17 Pro Max, OnePlus 15, nubia Z80 | — | `SM8850` | same archives |

**Not one source anywhere shows a device reporting `SM8750-AC`, `SM8850-AD`, `SM8650-AC` or
`SM8550-AC`.** Qualcomm's own GenieX docs state it: *"Variant suffixes are not exposed by
ro.soc.model: a Galaxy S25 reports SM8750, not SM8750-AC."*

**The mechanism**, read from Samsung stock vendor dumps (31 builds): `init.qti.qcv.rc` runs
`setprop ro.soc.model ${ro.vendor.qti.soc_model}`. On kalama and pineapple vendors (S23, S24,
Fold5/6, Flip5/6, Tab S9) the script hard-codes the plain name. On sun and canoe vendors (S25,
S26) it copies `/sys/devices/soc0/chip_id`, the die's fused id. That id is the same for a Galaxy
bin and a OnePlus bin, so **no string can tell them apart**.

## 2. Play's catalog agrees, row for row

| catalog System on Chip | rows | in the census? |
|---|---|---|
| `QTI SM8550` | 87 | yes (qcs8550) |
| `QTI SM8650` | 71 | yes (8gen3) |
| `QTI SM8750` | 58 | **from 4.13.0** (8elite_galaxy) |
| `QTI SM8850` | 39 | **from 4.13.0** (8elite5_galaxy) |
| `QTI SM7750` | 15 | yes (7gen4) |
| any `-A…` suffix | **0** | — |

Near misses, not in the census: `SM8650Q` (Lenovo Yoga Tab 2025 / Xiaoxin Pad Pro GT, 1 row),
`SM8850P` (Honor MagicPad 3 Pro 13.3", Legion Tab Gen 5, 2 rows), and `SM8845` (8 Gen 5 non-Elite,
15 rows). Handhelds on QCS8550 silicon (AYN Odin 2, Retroid Pocket 6) report `QCS8550` and are not
Play-certified.

## 3. A correction this surfaced

The owner's **Galaxy Z Fold6 reports plain `SM8650`**, not `SM8650-AC`. No adb read of it was
ever recorded. "SM8650-AC" was its spec-sheet part number, repeated as if it were a read. The
8gen3 row has always carried plain `SM8650`, so nothing changed for it.

## 4. What the fix rests on, and what it does not

- **Rests on:** the same die answers to the same QNN `soc_model` (69 for SM8750, 87 for SM8850) at
  the same HTP version, so a Galaxy-targeted context binary is compiled for the silicon a plain
  bin carries. The 8gen3 and qcs8550 rows already serve plain and Galaxy bins from one binary, and
  both are device-executed. A context that still refuses takes `NpuWhisperBackend`'s CPU
  fallback, which is loud: `npu: unavailable stage=init` plus the card note. On a phone with no
  CPU model installed, the card says that instead.
- **Does not rest on:** a device run. Neither 8 Elite family has executed on any phone, and the
  7gen4 family hasn't either. Borrowing an S25 (or any 8 Elite phone) is the highest-value test.

## 5. The device list

The full roster is in the published page "AI Chip Device Roster". It lists every qualifying
phone, foldable and tablet by family, with regional Exynos splits, Google Play availability,
lookalikes that do not qualify, and a Samsung model-number decoder.
