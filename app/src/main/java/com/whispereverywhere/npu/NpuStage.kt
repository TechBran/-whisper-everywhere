package com.whispereverywhere.npu

/**
 * THE STAGES AN NPU-CLASS TIER CAN DECLINE AT — a closed set, declared in decline order (P1a, the
 * engine seam; design `docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md` §2.4).
 *
 * Each constant carries its [wire] word: the one greppable token `npu: unavailable stage=<wire>`
 * prints ([NpuDiag.unavailable]) and the card's `"<wire>: <detail>"` leads with
 * ([NpuTierStatus.stageOf]). **The wire words are the words devices have always printed**, and they
 * are spelled here as literals rather than read off the constant: `Enum.name` is the Kotlin
 * identifier (`MEL_DONOR`), and a decline line that printed it would break every capture, grep and
 * support reply written against `stage=mel-donor` — which is exactly what the Qualcomm regression
 * gate compares, character for character, against a capture taken before the seam existed.
 *
 * **The set is derived, never retyped.** It is the set `NpuDiagTest` used to re-derive from the
 * backend's `fallBackToCpuTier`/`fallBackAndRun` call sites (4.2 F5, folding 4.1 L1 m5), plus
 * [DISPATCH]. `NpuStageTest` now carries that derivation — over the decline sites on both sides of
 * the seam, the backend's own literals and each engine's [Refusal]s — and holds this enum equal to
 * it, in this declaration order; `NpuDiagTest` holds [NpuDiag.unavailable]'s KDoc equal to this
 * enum. A new stage, a renamed one or a retired one fails there by name instead of rotting here.
 */
enum class NpuStage(val wire: String) {
    COMPANION("companion"),
    MEL_DONOR("mel-donor"),
    MEL_ASSET("mel-asset"),
    MEL_INIT("mel-init"),
    VOCAB("vocab"),

    /** The QNN engine's prepare: this family's DSP-side skel, staged into `filesDir`. */
    SKEL("skel"),

    /**
     * The LiteRT engine's prepare (P1b/P2): its MediaTek dispatch library, staged. **RESERVED** —
     * no Qualcomm session produces it, and `NpuStageTest` says so until an engine does. It sits
     * beside [SKEL] because the two are one stage of `load` for two vendors: the runtime's own
     * staging, after every cheap refusal and before the expensive init.
     */
    DISPATCH("dispatch"),

    INIT("init"),

    /**
     * The encoder's input quantisation, read once per arm (P1a: `QnnAsrEngine.init`, directly
     * after `nativeInit`). It declined per segment, after `mel`, until the seam moved the read.
     */
    QUANT("quant"),

    EPOCH("epoch"),
    SESSION("session"),
    MEL("mel"),
    ENCODE("encode"),
    LANG("lang"),
    DECODE("decode"),
    ;

    /**
     * The wire word — never the identifier (P1a review). `Enum.toString()` is `name` by default,
     * so a template that interpolated the stage itself — `"${refusal.stage}"`, or a [Refusal]
     * logged whole — would print `SKEL` where every device prints `skel`, and nothing about such a
     * line would look wrong in review. Overridden, the only spelling left that yields the
     * identifier is `.name`, which `NpuStageTest` holds off the backend's live lines.
     */
    override fun toString(): String = wire
}

/**
 * One engine stage's refusal: which [stage] declined, and why.
 *
 * [detail] is carried VERBATIM into the backend's one decline funnel — the
 * `npu: unavailable stage=<wire> detail=<detail>` line and the card's `"<wire>: <detail>"` — so it
 * is a native `"stage: detail"` string or an equivalent one-line reason, and never transcript
 * content. A refusal is a value rather than an exception for the same reason the native seams
 * return strings: the owner has no adb, and a failure has to arrive as text that names its stage.
 */
data class Refusal(val stage: NpuStage, val detail: String)
