package com.whispereverywhere.service

/**
 * THE BUBBLE'S THREE USER-OWNED PRESENTATION FACTS — the live words' colour, the committed
 * words' colour, and how opaque the panel behind them is — and the one computed invariant that
 * bounds every combination of them.
 *
 * Owner, 2026-09-12: *"For all live transcribe moments, the preview words … I think we should
 * make those words red. Red will help with eye fatigue. … And if it seems like a good idea, we
 * could just put this as a setting inside the system so users can change the colour of their
 * live words and of their transcribed committed words as well. So we have two different colours
 * in that bubble. And maybe even the clarity of the black bubble background."*
 *
 * The two roles were already two separate views, so nothing here separates anything:
 * `R.id.transcription_delta_text` is the LIVE strip — handed to the on-device previewer when
 * there is one and to the four live CLOUD providers' `onDelta` when there is not, by
 * `deltaOwnsPreviewStrip` — and `R.id.transcription_edit_text` is the COMMITTED transcript. One
 * colour on one view therefore covers the previewer and every live cloud path at once.
 *
 * ### Why this is a PALETTE and a floor, and not a list of approved colours
 *
 * Asked directly about the palette the owner ruled: *"Definitely no black text on black
 * background. Definitely won't work. Just about every other colour works, though."* So the rule
 * is **not** a short list. What actually fails is not a colour, it is **low contrast** — and
 * contrast is computable. [CONTRAST_FLOOR] is asserted by `BubbleColoursTest` against every
 * entry of [PALETTE] at every step of [OPACITY_STEPS] over both extreme backdrops. Black-on-black
 * is unreachable because it fails that arithmetic; so do pure red and the app's own brand red,
 * which is the whole reason [LIVE_DEFAULT] is not `#FF0000`.
 *
 * ### Why the maths lives here and not in `android.graphics.Color`
 *
 * So the invariant is a JVM unit test rather than a device observation. Nothing in this file
 * touches the Android framework; the four channel reads are bit shifts on the same ARGB Int the
 * framework uses, and [compositeOver] models the same straight-alpha, gamma-space blend a
 * `PixelFormat.TRANSLUCENT` overlay window actually performs.
 */
object BubbleColours {

    /**
     * The legibility floor, as a WCAG 2.1 contrast ratio.
     *
     * 4.5:1 is AA for NORMAL-size text, and both views are 14sp regular/italic — normal text,
     * so the nominal standard applies and is not softened to widen a slider. The brief asked
     * for *"a real contrast ratio between the text colour and the composited background,
     * asserted by a test, not judged by eye"*; this is the number that assertion is against.
     */
    const val CONTRAST_FLOOR: Double = 4.5

    /**
     * How opaque the panel may be, in percent. The user picks one of these; nothing else is
     * reachable, because [opacityPercent] snaps every stored value onto this ladder.
     *
     * Four steps rather than a continuous slider for the same reason the palette is curated:
     * every reachable value is then enumerable, so the invariant is a cross product a test can
     * walk rather than a range it has to sample.
     */
    val OPACITY_STEPS: List<Int> = listOf(85, 90, 95, 100)

    /**
     * THE FLOOR, AND WHY IT IS NOT CAUTION — state this before raising it.
     *
     * The bubble floats over arbitrary third-party apps. As the panel becomes more transparent
     * the backdrop stops being *our* black and becomes **someone else's screen**, whose colour
     * we cannot know, cannot test and cannot choose. Below some opacity, therefore, **no** text
     * colour can be guaranteed readable — over a white app even white text falls under
     * [CONTRAST_FLOOR] at 50%, and the guarantee is not weakened at that point, it is *gone*.
     *
     * 85% is not a round number somebody liked: it is the **lowest 5%-step at which the whole
     * of [PALETTE] still clears the floor over a white backdrop**, and the entry that binds it
     * is the owner's own red ([LIVE_DEFAULT], 4.74:1 here). One step lower, 80%, and the red
     * falls to 3.96:1 — `BubbleColoursTest` asserts both directions, so the floor cannot be
     * moved without the palette being re-argued.
     *
     * Because the floor makes every palette entry safe at every step, the two settings are
     * **independent**: there is no cross-validation anywhere in the app, and lowering the
     * opacity can never make a colour the user already chose illegible.
     *
     * If more transparency than this is wanted, that is the owner's ruling to make with the
     * trade in front of him — the trade being that below 85% the app stops being able to promise
     * the words are readable at all.
     */
    const val OPACITY_FLOOR_PERCENT: Int = 85

    /**
     * The shipped default, and it is the panel users have today: `preview_bubble_background` is
     * `#E6000000`, and `0xE6` is exactly [alphaByte] of 90. So a user who never opens this
     * setting sees no change they did not ask for — the same promise [COMMITTED_DEFAULT] keeps
     * for the committed text.
     */
    const val OPACITY_DEFAULT_PERCENT: Int = 90

    /** A palette entry: the colour, and the word a user reads under it. */
    data class Swatch(val name: String, val argb: Int)

    /**
     * The palette. Generous by ruling — an earlier brief said six to eight options and that was
     * WITHDRAWN — and ordered around the wheel so the grid reads as a spectrum: reds first
     * (where the live default lives), then warm, cool, and the neutrals ending in white (where
     * the committed default lives).
     *
     * Every entry is opaque and every entry clears [CONTRAST_FLOOR] at 85% over both a black and
     * a white backdrop; the darkest is `Red` at 4.74:1 and it is what sets [OPACITY_FLOOR_PERCENT].
     * Adding an entry means re-running `BubbleColoursTest`, which will reject it if it is too dark
     * — that is the intended way to extend this list.
     */
    val PALETTE: List<Swatch> = listOf(
        Swatch("Red", 0xFFFF5252.toInt()),
        Swatch("Coral", 0xFFFF6B6B.toInt()),
        Swatch("Salmon", 0xFFFF8A80.toInt()),
        Swatch("Orange", 0xFFFFAB40.toInt()),
        Swatch("Amber", 0xFFFFD740.toInt()),
        Swatch("Yellow", 0xFFFFEE58.toInt()),
        Swatch("Lime", 0xFFD4E157.toInt()),
        Swatch("Mint", 0xFFA5D6A7.toInt()),
        Swatch("Spring", 0xFF69F0AE.toInt()),
        Swatch("Teal", 0xFF64FFDA.toInt()),
        Swatch("Cyan", 0xFF4DD0E1.toInt()),
        Swatch("Sky", 0xFF80D8FF.toInt()),
        Swatch("Azure", 0xFF64B5F6.toInt()),
        Swatch("Periwinkle", 0xFF82B1FF.toInt()),
        Swatch("Indigo", 0xFF9FA8DA.toInt()),
        Swatch("Violet", 0xFFB388FF.toInt()),
        Swatch("Orchid", 0xFFCE93D8.toInt()),
        Swatch("Magenta", 0xFFFF80AB.toInt()),
        Swatch("Pink", 0xFFF48FB1.toInt()),
        Swatch("Rose", 0xFFFFC1E3.toInt()),
        Swatch("Sand", 0xFFE6D3A3.toInt()),
        Swatch("Taupe", 0xFFBCAAA4.toInt()),
        Swatch("Silver", 0xFFCFD8DC.toInt()),
        Swatch("Grey", 0xFFB0BEC5.toInt()),
        Swatch("White", 0xFFFFFFFF.toInt()),
    )

    /**
     * The live words' default: **red**, per the ruling, and `#FF5252` rather than `#FF0000`.
     *
     * Pure red is not a caution-driven rejection — it fails the arithmetic. Over a white app at
     * the default 90% it reaches 4.38:1 and at the 85% floor 3.77:1, both under
     * [CONTRAST_FLOOR]; `#FF5252` reaches 5.48:1 and 4.74:1. (The app's own brand red `#EF4444`
     * fails too, at 4.65:1 / 4.01:1, which is why the default is not reused from `colors.xml`.)
     * Pure red is perfectly legible over a *dark* app — the failure is entirely the backdrop we
     * cannot know, which is the same fact [OPACITY_FLOOR_PERCENT] exists for.
     *
     * It is also the darkest entry on [PALETTE], so the owner can overrule the shade in this one
     * constant; moving it to anything darker will fail `BubbleColoursTest` and force the opacity
     * floor to be re-argued with it, which is the correct coupling.
     */
    val LIVE_DEFAULT: Int = 0xFFFF5252.toInt()

    /**
     * The committed words' default: `#FFFFFF`, unchanged from the shipped layout, so today's
     * users see no difference they did not ask for.
     */
    val COMMITTED_DEFAULT: Int = 0xFFFFFFFF.toInt()

    /** The panel's colour. Only its alpha is user-owned — the fill stays black by ruling. */
    private const val PANEL_RGB: Int = 0x000000

    /** The hint's alpha, the `#99FFFFFF` the shipped layout used for *"Listening…"*. */
    private const val HINT_ALPHA: Int = 0x99

    /**
     * The words the Settings sample renders as already-committed transcript.
     *
     * FIXED, and that is a rule rather than a convenience: a colour sample is not transcript
     * content, but a sample built from the user's LAST REAL transcript would be — it would put
     * their dictation on a Settings screen and into any screenshot of it. These are the sample.
     */
    const val SAMPLE_COMMITTED: String = "Meet me at the station"

    /** The words the sample renders on the live strip — a dictation caught mid-utterance. */
    const val SAMPLE_LIVE: String = "at half past three"

    /** The two backdrops that BOUND every screen the bubble can float over. See [worstContrast]. */
    private val EXTREME_BACKDROPS = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())

    /**
     * The stored opacity, clamped to [OPACITY_FLOOR_PERCENT] and snapped onto [OPACITY_STEPS].
     *
     * Every read goes through this, exactly as every panel-geometry read goes through
     * `applyPreviewSize`'s re-clamp: a value written by an older build, a corrupted preferences
     * file, or a future edit of the ladder must not be able to put an unreadable panel on
     * screen. `0` — what an unset Int preference reads as — becomes the floor, not full
     * transparency.
     */
    fun opacityPercent(stored: Int): Int {
        val clamped = stored.coerceIn(OPACITY_FLOOR_PERCENT, OPACITY_STEPS.max())
        return OPACITY_STEPS.minByOrNull { kotlin.math.abs(it - clamped) } ?: OPACITY_DEFAULT_PERCENT
    }

    /** [percent] as an 8-bit alpha. 90 -> `0xE6`, the byte the shipped drawable carried. */
    fun alphaByte(percent: Int): Int = Math.round(percent.coerceIn(0, 100) * 255f / 100f)

    /** The panel's ARGB fill at [percent] — black, at the user's alpha. */
    fun panelArgb(percent: Int): Int = (alphaByte(percent) shl 24) or PANEL_RGB

    /**
     * The stored text colour if it is a [PALETTE] entry, else [fallback].
     *
     * The guard is palette MEMBERSHIP and not a legibility test, deliberately. It makes the
     * palette the single authority, so *"every palette entry clears the floor at every step"* is
     * the whole proof that no reachable combination fails. A second legibility check here would
     * be a second authority, and the two would disagree the first time the palette is edited.
     */
    fun textColour(stored: Int, fallback: Int): Int =
        if (PALETTE.any { it.argb == stored }) stored else fallback

    /**
     * The hint colour for the committed view — [committed] at the shipped hint's 60% alpha.
     *
     * Derived rather than a fourth setting: `transcription_edit_text`'s *"Listening…"* hint was
     * `#99FFFFFF`, and left alone it would stay white under a user who made the committed text
     * amber, which reads as a bug in the panel rather than as their choice. The hint is a fixed
     * word and is never transcript content.
     */
    fun hintArgb(committed: Int): Int = (HINT_ALPHA shl 24) or (committed and 0x00FFFFFF)

    /** WCAG relative luminance of an opaque ARGB colour. */
    fun relativeLuminance(argb: Int): Double {
        val r = channel((argb ushr 16) and 0xFF)
        val g = channel((argb ushr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun channel(value: Int): Double {
        val s = value / 255.0
        return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    /** WCAG contrast ratio between two opaque colours. Symmetric — it is max/min, not a/b. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (kotlin.math.max(la, lb) + 0.05) / (kotlin.math.min(la, lb) + 0.05)
    }

    /**
     * The panel at [percent] composited over [backdropArgb] — straight alpha, per channel, in
     * 8-bit gamma space, which is what a `PixelFormat.TRANSLUCENT` overlay window does.
     */
    fun compositeOver(percent: Int, backdropArgb: Int): Int {
        val a = alphaByte(percent) / 255.0
        fun mix(shift: Int): Int {
            val backdrop = (backdropArgb ushr shift) and 0xFF
            val panel = (PANEL_RGB ushr shift) and 0xFF
            return Math.round(a * panel + (1 - a) * backdrop).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /**
     * The WORST contrast [textArgb] can reach anywhere the bubble is allowed to be — the minimum
     * over every [OPACITY_STEPS] step and both [EXTREME_BACKDROPS].
     *
     * The two extremes bound every screen: composite luminance is monotone in the backdrop's, so
     * no real app can land outside them. Requiring both is what excludes dark text at *any*
     * opacity (over a black backdrop the composite is black at every alpha, so a dark colour
     * fails there regardless) and what makes the lowest opacity the binding case for light text.
     */
    fun worstContrast(textArgb: Int): Double =
        OPACITY_STEPS.minOf { percent ->
            EXTREME_BACKDROPS.minOf { contrastRatio(textArgb, compositeOver(percent, it)) }
        }

    /** Whether [textArgb] clears [CONTRAST_FLOOR] everywhere the bubble can be. */
    fun legibleEverywhere(textArgb: Int): Boolean = worstContrast(textArgb) >= CONTRAST_FLOOR
}
