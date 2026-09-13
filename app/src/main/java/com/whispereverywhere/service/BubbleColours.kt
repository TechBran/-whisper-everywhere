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
 * contrast is computable. [CONTRAST_FLOOR] is asserted by `BubbleColoursTest` over
 * [panelTextArgbs] — EVERY colour that lands on the panel, not merely every palette entry — for
 * every pair of choices in [PALETTE] at every step of [OPACITY_STEPS] over both extreme
 * backdrops. Black-on-black is unreachable because it fails that arithmetic; so do pure red and
 * the app's own brand red, which is the whole reason [LIVE_DEFAULT] is not `#FF0000`.
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
     * the default 90% it reaches 4.40:1 and at the 85% floor 3.78:1, both under
     * [CONTRAST_FLOOR]; `#FF5252` reaches 5.51:1 and 4.74:1. (The app's own brand red `#EF4444`
     * fails too, at 4.67:1 / 4.02:1, which is why the default is not reused from `colors.xml`.)
     * Every ratio quoted in this file is what the functions below return — recomputed against
     * them, because a KDoc number that came from a third implementation is what the next reader
     * trusts.
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

    /**
     * The *"Listening…"* hint on `transcription_edit_text` — `#99FFFFFF`, FIXED, and the ONE
     * colour on this panel that is **not** a term of the user's choice.
     *
     * ### Why it is fixed, and why that is the interesting half
     *
     * It has an alpha of its own (`0x99`), so what the eye receives is the hint composited over
     * the panel composited over an app we do not own — and **the palette's guarantee does not
     * extend to a colour given a second alpha**. [PALETTE]'s proof is that every entry is
     * *opaque* and clears [CONTRAST_FLOOR]; derive a translucent colour from an entry and 60% of
     * that entry is all that reaches the eye, which is a different colour and a different
     * number. An earlier draft of this build derived the hint from [COMMITTED_DEFAULT]'s setting
     * so the hint would follow an amber committed text rather than staying white. The arithmetic
     * refused it: at `0x99`, **16 of the 25 palette entries put *"Listening…"* on screen under
     * the floor** — the owner's own red worst at **2.50:1** — while this fixed `#99FFFFFF` never
     * drops below **6.36:1**. The lowest alpha at which the whole palette's derived hint clears
     * 4.5:1 is `0xF7` (97%), by which point a hint is indistinguishable from committed text and
     * has stopped being a hint. So consistency with the user's hue and legibility of the first
     * word on the panel cannot both be had, and legibility wins: *"Listening…"* is the signal
     * that the app is listening at all and it is on screen in every session.
     *
     * If the owner ever wants the hint to follow the committed colour, that is a RULING and not
     * an edit: it cuts the palette to the nine entries whose derived hint clears the floor, and
     * takes his red out of it.
     *
     * This is also the whole class of defect [panelTextArgbs] exists to close — see its KDoc.
     */
    val HINT_ARGB: Int = 0x99FFFFFF.toInt()

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
     * palette the single authority over the two colours the USER picks, so what a caller has to
     * check is membership and nothing else. A second legibility check here would be a second
     * authority, and the two would disagree the first time the palette is edited.
     *
     * What membership does NOT prove is that every colour on the panel is legible — only that
     * the two picked ones are. [panelTextArgbs] is where that stronger claim lives, and the
     * difference between the two is a defect this build shipped once.
     */
    fun textColour(stored: Int, fallback: Int): Int =
        if (PALETTE.any { it.argb == stored }) stored else fallback

    /**
     * EVERY COLOUR THAT LANDS ON THE PANEL, for the user's chosen [live] and [committed] — the
     * list the invariant is asserted over, and the answer to the class of defect that
     * *"every palette entry clears the floor"* does not cover.
     *
     * The guard's authority is palette MEMBERSHIP ([textColour]), and the proof membership
     * carries is about colours taken **straight** from [PALETTE]. Any colour **derived** from a
     * setting — above all any given an alpha of its own — leaves that authority and lands
     * outside every assertion *silently*: the arithmetic still answers, it just answers about a
     * colour the screen never shows. A derived hint is how that happened once already
     * ([HINT_ARGB]).
     *
     * So the invariant is stated over this list rather than over the palette: three terms, the
     * two the user owns plus the one fixed hint, and the delta strip's STATUS role is the
     * committed term because that is literally the colour it takes. **A colour added to this
     * panel is added here**, and `BubbleColoursTest` walks the whole list across
     * [PALETTE] x [PALETTE] x [OPACITY_STEPS] x both extreme backdrops — so a new derivation is
     * either inside the cross product or it is a red suite.
     */
    fun panelTextArgbs(live: Int, committed: Int): List<Int> = listOf(live, committed, HINT_ARGB)

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
     * The panel at [percent] composited over [backdropArgb] — i.e. [composite] with the panel's
     * own fill in front, which is the one blend this file performs.
     */
    fun compositeOver(percent: Int, backdropArgb: Int): Int =
        composite(panelArgb(percent), backdropArgb)

    /**
     * [foregroundArgb] over an OPAQUE [opaqueBackgroundArgb] — straight alpha, per channel, in
     * 8-bit gamma space, which is what a `PixelFormat.TRANSLUCENT` overlay window does. The
     * result is opaque, so it can be handed straight to [contrastRatio].
     *
     * It takes the foreground's alpha FROM the foreground, which is the whole point: a text
     * colour with an alpha of its own is measured as the eye receives it and not as though it
     * were opaque. The panel's own blend is the same operation ([compositeOver] is this function
     * with [panelArgb] in front), so there is one blend in this file and not two that can drift.
     */
    fun composite(foregroundArgb: Int, opaqueBackgroundArgb: Int): Int {
        val a = ((foregroundArgb ushr 24) and 0xFF) / 255.0
        fun mix(shift: Int): Int {
            val foreground = (foregroundArgb ushr shift) and 0xFF
            val background = (opaqueBackgroundArgb ushr shift) and 0xFF
            return Math.round(a * foreground + (1 - a) * background).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /**
     * The WORST contrast [textArgb] can reach anywhere the bubble is allowed to be — the minimum
     * over every [OPACITY_STEPS] step and both [EXTREME_BACKDROPS].
     *
     * The two extremes bound every screen, but NOT by monotonicity of the ratio — composite
     * *luminance* is monotone in the backdrop's, the contrast *ratio* is not: it falls and then
     * rises, with an interior minimum where the backdrop's luminance meets the text's. The
     * bounding argument is arithmetic instead, and worth writing down so a future palette edit is
     * not licensed by a theorem that does not hold: the LIGHTEST composite reachable here is grey
     * `38` (L = 0.019) because [OPACITY_FLOOR_PERCENT] stops the panel going thinner, and the
     * DARKEST entry on [PALETTE] is L = 0.279 — so no intermediate backdrop can get near a text
     * colour's luminance, and any colour dark enough for that to be possible already fails over
     * the black extreme.
     *
     * Requiring both extremes is what excludes dark text at *any* opacity (over a black backdrop
     * the composite is black at every alpha, so a dark colour fails there regardless) and what
     * makes the lowest opacity the binding case for light text.
     *
     * [textArgb]'s OWN alpha is honoured, through [composite]: what is measured is the pixel the
     * eye receives against the panel behind it. A translucent text colour therefore gets its real
     * number rather than the opaque colour's — the silent hole a derived hint fell into once.
     */
    fun worstContrast(textArgb: Int): Double =
        OPACITY_STEPS.minOf { percent ->
            EXTREME_BACKDROPS.minOf { backdrop ->
                val background = compositeOver(percent, backdrop)
                contrastRatio(composite(textArgb, background), background)
            }
        }

    /** Whether [textArgb] clears [CONTRAST_FLOOR] everywhere the bubble can be. */
    fun legibleEverywhere(textArgb: Int): Boolean = worstContrast(textArgb) >= CONTRAST_FLOOR
}
