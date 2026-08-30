# 4.2 — Fleet Onboarding: the detector, Play Asset Delivery, one flow (design)

Owner rulings 2026-08-29: a detector chooses NPU-vs-CPU per device ("if no NPU,
default to the CPU model variants"); turbo is the standard NPU offering ("stick to
the bigger turbo model — most any phone can hold a gigabyte"); delivery via
**Google Play Asset Delivery** ("no hosting here on my own"). Research basis:
`2026-08-29-pad-soc-delivery.md` (all facts re-verified live). Executes on
`feat/4.2-fleet-onboarding` off main (431ab85). The 3.8 onboarding language step
(owner-ruled: language BEFORE download) folds into this flow.

## Goal

Install from Play → the app detects the chip → capable devices are offered turbo
(the owner-picked recommended tier), delivered by Play automatically → the user
picks their language → dictating. Non-NPU devices flow to the CPU tiers exactly as
today. No self-hosting, no sideload gymnastics for customers.

## The delivery design (decisions bound by research)

1. **On-demand asset packs** (not install-time, not fast-follow): per-pack cap
   1.5 GB compressed — our largest variant compresses to ~860 MB (ship RAW bins in
   the pack; Play deflates in transit; never pre-zip). On-demand gives the built-in
   >200 MB cellular-consent dialog and makes every fetch gate-controlled. Standard
   asset packs over the "AI packs" beta twin for tooling maturity; the layout is
   identical if we later migrate.
2. **SoC-targeted variants of one pack** via device groups
   (`<config:system-on-chip manufacturer="QTI" model="..."/>`, API 31+ — matches
   our gate's existing floor): four groups today —
   | group | HTP | turbo variant |
   |---|---|---|
   | soc_8gen3 (SM8650 + SM8650-AC) | v75 | 859,786,903 B |
   | soc_8elite_galaxy (SM8750-AC) | v79 | 859,689,781 B |
   | soc_8elite5_galaxy (SM8850-AD) | v81 | 860,709,426 B |
   | soc_7gen4 (SM7750) | v73 | 871,118,306 B |
   Every group lists ALL suffix variants of its soc_model (the -AC/-AD trap:
   missing suffixes land devices in the empty default — fail-safe but lost
   coverage; the group lists are a maintained census with a pin).
   **The DEFAULT variant is EMPTY** — unmatched devices can never receive a wrong
   pack, and on-demand means nothing downloads unless the app calls fetch().
3. **The app gate stays the correctness authority**: Play targeting is bandwidth
   optimization only. The load path keeps sha256-verify-on-load (Play doesn't
   transcode asset bytes, but the invariant is ours, not Play's) and the pack's
   own metadata soc/htp cross-check; the census guard validates IO as always.
4. **A small-quantized pack per family rides along** (~293 MB, on-demand, fetched
   only if the user picks small in the chooser) — the lab lineup survives on every
   capable device. Cumulative budget trivial vs the 30 GB cap.
5. **The existing routes stay permanently**: SAF zip import (sideloaded installs
   get no Play packs — the import IS their path), adb (dev), GitHub zips (the
   lab). One loader, several arrival routes, one verification.

## The detector (the fleet ladder)

`NpuGate` expands from the owner-device allowlist to the four-family census
(exact-string soc_model matching incl. suffixes) + the existing runtime probe +
per-family pack-group mapping. Devices outside the census: the gate answers CPU —
no NPU UI, no pack fetch, the CPU tiers exactly as shipped today. 8 Gen 2 / 8 Gen
1 / 888 / non-Galaxy 8 Elite have NO published quantized packages: they are CPU
devices, stated in the census with the evidence date. (The 7gen4-v73-on-8gen2
cross-load curiosity from the research is explicitly OUT — unverified.)

## The onboarding flow (folds the 3.8 owner ruling)

1. Welcome → **the language step** (the 3.8 ruling: BEFORE model download; the
   device-locale-first list, auto available with the honest subtitle).
2. The model step, shaped by the detector:
   - Capable: turbo recommended (the owner-picked head, the existing H3-compliant
     copy), small and the CPU tiers selectable; choosing an NPU tier triggers the
     Play fetch with progress + the cellular-consent dialog; the pack lands →
     verified → staged → ready.
   - Not capable: today's CPU flow unchanged (multi default for non-English,
     pro/multi as shipped).
3. Done → the bubble. Existing installs see no forced re-onboarding; the chooser
   gains the fetch affordance where the gate passes (replacing the import-only
   story for Play installs).

## Failure honesty

Fetch failures surface Play's error + a retry affordance, never a silent fallback;
a fetched-but-corrupt pack fails the sha256 at load → the tier card carries the
reason (the existing unavailableReason machinery); the offer line + npu: diag
family unchanged. Cancel mid-fetch is a real affordance (Play supports it).

## Testing & acceptance

JVM: the census/mapping truth tables, the flow logic, the pack-name/variant
contracts, the group-census pin. Mechanics: bundletool --local-testing with
asserted device groups. THE REAL PASS: the Play **internal test track** — the
Fold6 receives the 8gen3 variant end-to-end (install → detect → fetch → verify →
dictate on turbo), which doubles as the first full rehearsal of the store pipeline
before launch. Acceptance: the owner installs from the internal track on the Fold6
and reaches turbo dictation without touching adb.

## Non-goals

GA-waiting on the targeting beta (we ship on the beta; the app gate makes it
safe); the cross-load experiment; expanding the census beyond published packages;
Play listing/launch work (its own track); the 3.8 cloud/Gemini items.
