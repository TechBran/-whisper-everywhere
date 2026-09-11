# 4.4.1 — controller adjudication of the round-3 blockers (2026-09-11)

Pass 3's fix loop ran its full three rounds and the final review is still FIX_REQUIRED (H3-B1, H3-B2, H3-B3).
The breaker tripped, so the call is the controller's. Branch head: c5068d5, 205 suites / 2,501 tests / 0 failures.

## The pattern, which matters more than any one blocker

Three rounds, three NEW real findings, all in one place: `LivePreviewRows`' answer to *"is work running right
now?"*. Each round's fix was correct and each round found another combination the fix did not cover. That is not a
reviewer spiral - every finding was true - and it is not an implementer failure. It is a **state-model** problem:

- There are now **three** starters of a 73 MB transfer: the Settings row (`StreamingPackController`), and the
  auto-fetch controller's TWO routes (`landDeliveredPack`, `downloadFallback`, narrating through
  `PreviewAutoFetchController.line`).
- `LivePreviewRows` answers "is work running?" from **two composition-local variables** that only see work THIS
  composition started - `previewInstallStatus` (its own `remember`) and `StreamingPackController.state`.
- Home collects `PreviewAutoFetchController.line`. **Settings does not.**

So the section is structurally unable to see two of the three starters, and every blocker in rounds 1-3 is a
different consequence of that one gap. Patching the next discovered combination is how a fourth round happens.

## Rulings

**H3-B3 — FIXED NOW, at c5068d5.** A false instruction is a lie the app tells, it is one string plus one hoisted
condition, and it needs none of the state model. Done, pinned, suite green.

**H3-B1 (the delete row draws over a running repair) and H3-B2 (Settings starts a SECOND 73 MB over the
controller's own work) — NOT patched in 4.4.1. Both are adjudicated into the multilingual build as its FIRST
task, and the task is the unification, not the two patches.**

The reasoning, stated so it can be overruled on the record:

1. **Neither is a regression 4.4.1 introduces.** Both are pre-existing shapes - the Settings section had this same
   two-variable answer in 4.4.0 - that 4.4.1 makes newly *reachable* (a second starter now exists) rather than
   newly *wrong*. 4.4.0 shipped with the same blindness plus the discovery gap plus the English-only sell.
   **4.4.1 as it stands is a strict improvement on the shipped app in every state either blocker names.** Holding
   it back to fix a pre-existing weakness, while the owner waits on languages, is the wrong trade.
2. **The honest fix is bigger than a patch and is already required elsewhere.** The multilingual build needs a
   per-pack job map and a per-pack progress line anyway - that is the parked "one job, one line, two languages
   cannot arrive at once" contract from pass 2. One observable owned by the feature, read by BOTH surfaces,
   answers H3-B1, H3-B2, pass-2 nit 2, r2 nit 4, r3's Settings-blindness nit and the multilingual requirement in
   one stroke. Three of those are already written down as future work; doing it once, at its real size, is
   cheaper and safer than four more one-line guards.
3. **Reachability is narrow for both.** H3-B2 needs the download fallback live - a debug or sideload build, or a
   refusal Play named - and H3-B1 needs a previewer load to have thrown once, then a delete tapped during the
   repair. Real, and worth closing; not worth blocking a release whose whole purpose is that the pack arrives on
   its own.

**Carried forward with them, same task:** r2 nit 1 (`DELETE_SUBTITLE` now has four states and is the largest
untrue sentence the feature renders - "Frees 73 MB" frees nothing mid-transfer), H1 (the X on the download
fallback leaves the DownloadManager row alive, so `delete()` and the X disagree about the same transfer), H3 (the
switch does not release the resident engine), H4/r2 nit 1 (`previewReady` stale-true across a release), r2 nit 4
(the disk census, asked three times per warm now), and the whole TIER/ENGINE axis (the rows still promise live
words to a cloud-only device) which the reviewer itself says "wants a ruling, not a patch".

## H6 - the two deliberate narrowings, upheld

An Auto user can no longer install the pack from Settings, and an Auto or French picker with the pack installed
sees the caveat and the delete but not the switch. Both follow ruling 1 directly: the picker is the single
acquisition point, and a switch that governs nothing should not be offered. Neither hides bytes nor the way to
reclaim them. Upheld - and worth one line to the owner, because he is a 4.4.0 user who tests on Auto.

## What must NOT be read out of this document

Every item above is a real finding. None is dismissed; all are scheduled. The one thing being declined is the
claim that 4.4.1 cannot ship until they are closed.
