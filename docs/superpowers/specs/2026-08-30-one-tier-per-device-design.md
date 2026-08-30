# 4.3 — One tier per device (design)

Owner ruling 2026-08-30, after the internal-track session passed end to end:
*"If a phone is powerful enough with the NPU, we should only support the
multilingual v3 turbo. Users shouldn't even see the 190 MB model or even the
358 MB model... The only time we should show the 190 MB model is if a user doesn't
have an NPU."* Small branch off main (6cb90a4).

## Goal

The chooser stops being a menu and becomes an answer:

| device | what the user sees | why |
|---|---|---|
| NPU-capable (the four families) | **Multilingual on NPU (turbo)** — the only offer | measured best on this hardware; a gigabyte is unremarkable in 2026 |
| everything else | today's CPU story, `multi` the best available | accuracy caveat accepted by the owner, explicitly |

## The one consequence, and its resolution

`NpuWhisperBackend`'s decline path falls back to a **ggml CPU model**
(`cpuTierModelPath()`), and the mel path for the 80-bin tiers uses an installed
ggml as its filterbank donor. Turbo's mel comes from the shipped
`melbank-128.bin` (L3), so the donor is not an issue — **but the decline fallback
is**: a capable device that only ever installs turbo has nothing to fall back to.

**Resolution: hidden until relevant.** The CPU tier is absent from the chooser on
capable devices, and appears *only* when a decline makes it the answer — the
tier-unavailable card gains a one-tap "Download the standard model" action, with
copy naming why (the AI chip is unavailable on this device/session). This keeps the
loud-fallback doctrine (a decline is never silent) and the owner's ruling (the
model is never clutter). A decline with no CPU model installed says so plainly
rather than failing mute.

## What changes

1. **The offer set on capable devices** = `{npu-turbo}`. `npu` (small) and the CPU
   tiers are not offered. Non-capable devices: byte-identical to today.
2. **`npu` stays in the catalog**, hidden from the chooser — the streaming arc
   (partials on small + finals on turbo) needs it, and hiding is not retiring.
   Its census/pack/import machinery is untouched.
3. **The decline card** gains the CPU-recovery action (fetch/download `multi`,
   the existing download path, not a new one).
4. **Existing installs are not disturbed**: a capable device already running
   `multi` or `npu` keeps transcribing on it — no silent switch, no deletion of a
   model the user already downloaded. The chooser simply offers turbo going
   forward, and the steer names it. (Deleting a gigabyte the user paid bandwidth
   for is not ours to do.)
5. **Onboarding on capable devices** goes language → turbo → fetch → done: one
   model card, no comparison, no decision the hardware already made.

## Non-goals

Retiring the `npu` tier (the streaming arc needs it); changing the CPU tiers' own
lineup for non-capable devices; touching the delivery/verification machinery;
re-opening the turbo-vs-small measurement (the owner's A/B settled it).

## Testing & acceptance

JVM: the offer-set truth table (capable × installed-state × locale); the decline
card's recovery action; the existing-install non-disturbance; the census alarm
that `npu` remains catalogued-but-unoffered. Device (the owner, on the internal
track): a fresh capable install sees exactly one model card and reaches dictation
without a choice; a forced decline offers the standard model and recovers.
