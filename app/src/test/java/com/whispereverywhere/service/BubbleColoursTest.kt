package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE BUBBLE'S THREE USER-OWNED PRESENTATION FACTS, and the one invariant that bounds all of
 * them (4.5.1 Task 2, owner 2026-09-12).
 *
 * The owner's ruling on the palette was *"Definitely no black text on black background.
 * Definitely won't work. Just about every other colour works, though."* — so this class does not
 * hold a short approved list. It holds a **contrast floor**, computed, and asserts that every
 * reachable combination of {every colour the panel paints} x {live choice} x {committed choice} x
 * {opacity step} x {the two extreme backdrops} clears it. Black-on-black is excluded because it
 * fails the arithmetic, not because it is off a list; so are pure red and the app's own brand
 * red, and that is the whole of why the default live colour is not `#FF0000`.
 *
 * The guarded set is *every colour the panel paints* and not *every palette entry* for a reason
 * this build learned the hard way: palette membership says nothing about a colour DERIVED from a
 * choice, and a derived translucent hint sat outside the whole cross product while the suite was
 * green. See `everyCOLOURThePANELPaintsClearsTheFloor_notOnlyTheTwoTheUserPICKED` and
 * `theHINTIsFIXEDBecauseITSOWNAlphaPutsItOUTSIDEThePalettesGuarantee`.
 */
class BubbleColoursTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    // ---------------------------------------------------------------- the maths itself

    @Test
    fun theContrastMathsAgreesWithTHESTANDARDSOwnWorkedNumbers() {
        // WCAG 2.1's own two anchors: the extreme pair is 21:1 and any colour against itself
        // is 1:1. If these drift, every assertion below is measuring something else.
        assertEquals(21.0, BubbleColours.contrastRatio(white, black), 0.001)
        assertEquals(1.0, BubbleColours.contrastRatio(white, white), 0.001)
        assertEquals(1.0, BubbleColours.contrastRatio(black, black), 0.001)
        // Relative luminance, the two fixed points plus one mid-grey off the linear branch.
        assertEquals(0.0, BubbleColours.relativeLuminance(black), 0.0001)
        assertEquals(1.0, BubbleColours.relativeLuminance(white), 0.0001)
        assertEquals(0.2159, BubbleColours.relativeLuminance(0xFF808080.toInt()), 0.001)
        // The ratio is symmetric — it is max/min, not first/second.
        assertEquals(
            BubbleColours.contrastRatio(white, black),
            BubbleColours.contrastRatio(black, white),
            0.0001,
        )
    }

    @Test
    fun theCompositeIsSTRAIGHTALPHAInEIGHTBITSpaceTheWayTheOVERLAYWindowActuallyBlends() {
        // The panel is a black fill at `percent` over an app we do not own, in a
        // PixelFormat.TRANSLUCENT window: the blend is per-channel straight alpha in gamma
        // space. Model it wrong and every ratio below is a ratio against a colour no screen
        // ever shows.
        assertEquals(black, BubbleColours.compositeOver(100, white))
        assertEquals(black, BubbleColours.compositeOver(100, black))
        assertEquals(black, BubbleColours.compositeOver(85, black))
        // 85% black over white: 15% of 255 in every channel, and opaque.
        val over = BubbleColours.compositeOver(85, white)
        assertEquals(0xFF, (over ushr 24) and 0xFF)
        assertEquals(38, (over ushr 16) and 0xFF)
        assertEquals(38, (over ushr 8) and 0xFF)
        assertEquals(38, over and 0xFF)
    }

    // ---------------------------------------------------------------- the defaults

    @Test
    fun theDefaultsAreTHEOWNERSRuling() {
        // Live words: RED per the ruling, and NOT pure red — the shade is derived below, not
        // chosen by eye.
        assertNotEquals("pure red is ruled out by the floor, not by taste", 0xFFFF0000.toInt(), BubbleColours.LIVE_DEFAULT)
        val r = (BubbleColours.LIVE_DEFAULT ushr 16) and 0xFF
        val g = (BubbleColours.LIVE_DEFAULT ushr 8) and 0xFF
        val b = BubbleColours.LIVE_DEFAULT and 0xFF
        assertTrue("the default live colour must READ as red", r > g + 60 && r > b + 60)
        assertEquals("and it is a palette entry", true, BubbleColours.PALETTE.any { it.argb == BubbleColours.LIVE_DEFAULT })

        // Committed words: Spring, the owner's "emerald green, almost neon green" (2026-09-22,
        // on 108). Through 4.14 this was white — a deliberate change for every user who never
        // picked a colour — and it is a palette entry, so "never picked" and "picked Spring" look
        // the same.
        assertEquals(0xFF69F0AE.toInt(), BubbleColours.COMMITTED_DEFAULT)
        assertEquals("Spring", BubbleColours.PALETTE.single { it.argb == BubbleColours.COMMITTED_DEFAULT }.name)

        // Background: 75% (owner, same session: "with the transparency set to seventy-five").
        // Through 4.14 it was 90, the shipped #E6000000; 75 is BELOW the legibility guarantee,
        // and that trade is the owner's, stated on the slider.
        assertEquals(75, BubbleColours.OPACITY_DEFAULT_PERCENT)
        assertTrue("the default is his call, below the guarantee", BubbleColours.OPACITY_DEFAULT_PERCENT < BubbleColours.OPACITY_GUARANTEED_PERCENT)
        assertEquals(0xBF, BubbleColours.alphaByte(BubbleColours.OPACITY_DEFAULT_PERCENT))
        assertEquals(0xBF000000.toInt(), BubbleColours.panelArgb(BubbleColours.OPACITY_DEFAULT_PERCENT))
        assertEquals(0xFF000000.toInt(), BubbleColours.panelArgb(100))
        // The default committed green still clears the floor over a white app at the default —
        // it is the live red, not the committed text, that the 75% trade puts at risk.
        assertTrue(
            BubbleColours.contrastRatio(BubbleColours.COMMITTED_DEFAULT, BubbleColours.compositeOver(75, 0xFFFFFFFF.toInt())) >= BubbleColours.CONTRAST_FLOOR,
        )
    }

    @Test
    fun theCornerDiscsAreThePanelsBlackNeverClearerThanNinety() {
        // A disc sits OVER the transcript, so it has to hide what passes beneath it.
        assertEquals(90, BubbleColours.CONTROL_DISC_MIN_PERCENT)
        for (percent in BubbleColours.OPACITY_STEPS) {
            val disc = BubbleColours.controlDiscArgb(percent)
            assertEquals("black", 0, disc and 0xFFFFFF)
            assertTrue("never clearer than 90% (at $percent)", (disc ushr 24) >= BubbleColours.alphaByte(90))
            assertTrue("never clearer than the panel it sits on (at $percent)", (disc ushr 24) >= (BubbleColours.panelArgb(percent) ushr 24))
        }
        assertEquals("at and above the minimum it IS the panel's fill", BubbleColours.panelArgb(95), BubbleColours.controlDiscArgb(95))
    }

    // ---------------------------------------------------------------- THE INVARIANT

    @Test
    fun everyPALETTEEntryClearsTheFloorAtEVERYGuaranteedOpacityOverEITHERExtremeBackdrop() {
        // THE WHOLE GUARD. The bubble floats over arbitrary third-party apps, so the backdrop is
        // unknowable; composite luminance is monotone in the backdrop's, so black and white
        // BOUND every screen that can ever be behind it. Requiring both is what excludes dark
        // text at any opacity (over a black backdrop the composite is black at every alpha) and
        // what makes the LOWEST opacity the binding case for light text.
        //
        // 4.8.0: the product is over GUARANTEED_STEPS — every step from OPACITY_GUARANTEED_PERCENT
        // up — which is exactly the ladder this test walked before the owner extended it
        // downward (asserted in theLadderGoesDownTo20AndTheGuaranteeStartsAt85). The strength
        // here is unchanged; the steps below 85 are outside the promise by construction.
        assertEquals(listOf(85, 90, 95, 100), BubbleColours.GUARANTEED_STEPS)
        for (swatch in BubbleColours.PALETTE) {
            for (percent in BubbleColours.GUARANTEED_STEPS) {
                for (backdrop in listOf(black, white)) {
                    val bg = BubbleColours.compositeOver(percent, backdrop)
                    val ratio = BubbleColours.contrastRatio(swatch.argb, bg)
                    assertTrue(
                        "${swatch.name} at $percent%% over ${if (backdrop == black) "black" else "white"} " +
                            "is $ratio:1, under the ${BubbleColours.CONTRAST_FLOOR}:1 floor",
                        ratio >= BubbleColours.CONTRAST_FLOOR,
                    )
                }
            }
        }
        // ...and the helper the app actually calls says the same thing, so no caller has to
        // re-derive the cross product above.
        for (swatch in BubbleColours.PALETTE) {
            assertTrue(swatch.name, BubbleColours.legibleEverywhere(swatch.argb))
            assertTrue(
                swatch.name,
                BubbleColours.worstContrast(swatch.argb) >= BubbleColours.CONTRAST_FLOOR,
            )
        }
    }

    @Test
    fun everyCOLOURThePANELPaintsClearsTheFloor_notOnlyTheTwoTheUserPICKED() {
        // THE INVARIANT IN ITS FULL FORM, and the test above is only half of it. Palette
        // membership proves the two PICKED colours are legible; it proves nothing about a colour
        // DERIVED from one of them, and a derived colour — above all one given an alpha of its
        // own — leaves the palette's authority and lands outside every assertion SILENTLY. That
        // is not hypothetical: this build shipped a hint derived from the committed colour and it
        // put "Listening..." on screen at 2.50:1 for the owner's own red.
        //
        // So the guarded set is `panelTextArgbs` — every colour that lands on the panel — walked
        // over the real reachable product: every LIVE choice x every COMMITTED choice x every
        // opacity step x both extreme backdrops, with each colour's own alpha honoured. A colour
        // added to the panel is added to that list, or this test is red.
        assertTrue(
            "the hint is a colour the panel paints and must be inside the guard",
            BubbleColours.HINT_ARGB in BubbleColours.panelTextArgbs(black, white),
        )
        var worst = Double.MAX_VALUE
        var worstWhere = ""
        for (live in BubbleColours.PALETTE) {
            for (committed in BubbleColours.PALETTE) {
                for (argb in BubbleColours.panelTextArgbs(live.argb, committed.argb)) {
                    for (percent in BubbleColours.GUARANTEED_STEPS) {
                        for (backdrop in listOf(black, white)) {
                            val bg = BubbleColours.compositeOver(percent, backdrop)
                            val ratio = BubbleColours.contrastRatio(BubbleColours.composite(argb, bg), bg)
                            if (ratio < worst) {
                                worst = ratio
                                worstWhere = "${Integer.toHexString(argb)} on live=${live.name}/" +
                                    "committed=${committed.name} at $percent% over " +
                                    (if (backdrop == black) "black" else "white")
                            }
                            assertTrue(
                                "$argb on live=${live.name}/committed=${committed.name} at $percent%% " +
                                    "over ${if (backdrop == black) "black" else "white"} is $ratio:1, " +
                                    "under the ${BubbleColours.CONTRAST_FLOOR}:1 floor",
                                ratio >= BubbleColours.CONTRAST_FLOOR,
                            )
                            // The helper the app calls has to agree with the product above, or a
                            // caller trusting it is trusting a different guarantee.
                            assertTrue(
                                Integer.toHexString(argb),
                                BubbleColours.legibleEverywhere(argb),
                            )
                        }
                    }
                }
            }
        }
        // The binding case is the owner's own red at the GUARANTEED step over a white app — the
        // same 4.74:1 that DERIVED that step. If the worst case ever moves off it, the guarantee
        // and the palette are no longer one decision.
        assertEquals("the worst case on the whole panel is $worstWhere", 4.7424, worst, 0.001)
    }

    @Test
    fun theARITHMETICHonoursATextColoursOWNAlphaRatherThanIgnoringIt() {
        // The mechanism that closes the class above: a translucent colour is measured as the eye
        // RECEIVES it. Ignore the alpha and `legibleEverywhere` answers about a colour the screen
        // never shows — which is exactly how a 2.50:1 hint passed a 4.5:1 guard.
        //
        // An opaque foreground is itself, whatever is behind it, so nothing the user can pick
        // changes meaning.
        assertEquals(white, BubbleColours.composite(white, black))
        assertEquals(black, BubbleColours.composite(black, white))
        assertEquals(BubbleColours.LIVE_DEFAULT, BubbleColours.composite(BubbleColours.LIVE_DEFAULT, white))
        // 60% white over the 85% panel over a white app is grey 168 — not white.
        assertEquals(
            0xFFA8A8A8.toInt(),
            BubbleColours.composite(0x99FFFFFF.toInt(), BubbleColours.compositeOver(85, white)),
        )
        // ...and the same red, opaque and at 60%, get different answers. Both are true; only one
        // of them is about a pixel.
        assertTrue(BubbleColours.legibleEverywhere(0xFFFF5252.toInt()))
        assertFalse(BubbleColours.legibleEverywhere(0x99FF5252.toInt()))
        // A fully transparent colour is the background: ratio 1:1, never legible.
        assertEquals(1.0, BubbleColours.worstContrast(0x00FFFFFF), 1e-9)
    }

    @Test
    fun theTWOSettingsAreINDEPENDENTWithinTheGuaranteeBecauseTheGUARANTEEDStepCarriesItWhole() {
        // The brief's two consequences: a colour chosen at one opacity must not become
        // illegible when the opacity is LOWERED later. This is the branch built — the guaranteed
        // step makes every valid colour safe at every guaranteed opacity — so there is no
        // cross-validation anywhere in the app and no state in which one setting invalidates
        // the other WITHIN THE BAND. The proof is that the WORST guaranteed step is the
        // guaranteed step itself, and the palette clears it.
        //
        // 4.8.0 RE-SPELL: this used to say `OPACITY_FLOOR_PERCENT == OPACITY_STEPS.min()` and
        // that the floor carries the guarantee. The floor is 20 now and carries nothing — the
        // owner's ruling — so the claim moves, unchanged in strength, onto
        // OPACITY_GUARANTEED_PERCENT, which is the same 85 it always was.
        val guaranteed = BubbleColours.GUARANTEED_STEPS.min()
        assertEquals(BubbleColours.OPACITY_GUARANTEED_PERCENT, guaranteed)
        assertTrue("the guaranteed step is ON the ladder", guaranteed in BubbleColours.OPACITY_STEPS)
        for (swatch in BubbleColours.PALETTE) {
            val acrossBand = BubbleColours.GUARANTEED_STEPS.minOf { percent ->
                listOf(black, white).minOf {
                    BubbleColours.contrastRatio(swatch.argb, BubbleColours.compositeOver(percent, it))
                }
            }
            val atGuaranteedOnly = listOf(black, white).minOf {
                BubbleColours.contrastRatio(swatch.argb, BubbleColours.compositeOver(guaranteed, it))
            }
            assertEquals("${swatch.name}: the guaranteed step IS the worst case in the band", atGuaranteedOnly, acrossBand, 1e-9)
            assertTrue("${swatch.name} clears the floor there", atGuaranteedOnly >= BubbleColours.CONTRAST_FLOOR)
        }
        // And the independence claim is honest about its scope: it is NOT made below the band.
        // The step directly under it takes the owner's red under the floor over a white app.
        assertTrue(
            BubbleColours.contrastRatio(
                BubbleColours.LIVE_DEFAULT,
                BubbleColours.compositeOver(BubbleColours.OPACITY_GUARANTEED_PERCENT - 5, white),
            ) < BubbleColours.CONTRAST_FLOOR,
        )
    }

    @Test
    fun theLadderGoesDownTo20AndTheGuaranteeStartsAt85() {
        // Owner ruling 2026-09-17: "we can go even clearer than that, much clearer, honestly,
        // where we're barely seeing that dark background and your video … will just play through
        // that." The ladder's SHAPE, pinned: sorted, 20 at the bottom, the old four steps still
        // on it (nobody's stored value moves on upgrade), and 85 — the guarantee — on it too.
        val steps = BubbleColours.OPACITY_STEPS
        assertEquals("sorted ascending", steps.sorted(), steps)
        assertEquals("no duplicate step", steps.size, steps.toSet().size)
        assertEquals(BubbleColours.OPACITY_FLOOR_PERCENT, steps.min())
        assertEquals(20, BubbleColours.OPACITY_FLOOR_PERCENT)
        assertEquals(100, steps.max())
        // 75 joined on 2026-09-22, as the new default's own step (owner: "seventy-five").
        assertEquals(listOf(20, 30, 40, 50, 60, 70, 75, 80, 85, 90, 95, 100), steps)
        assertTrue("85 is on the ladder", 85 in steps)
        assertTrue("90 is on the ladder", 90 in steps)
        assertTrue("the default is on the ladder", BubbleColours.OPACITY_DEFAULT_PERCENT in steps)
        assertEquals("the default is the owner's 75", 75, BubbleColours.OPACITY_DEFAULT_PERCENT)
        // Every step the 4.5.1 ladder had is still reachable, so an upgrade snaps nobody.
        listOf(85, 90, 95, 100).forEach { assertEquals(it, BubbleColours.opacityPercent(it)) }
        // The guarantee: 85, the old floor, and the band above it is exactly the old ladder.
        assertEquals(85, BubbleColours.OPACITY_GUARANTEED_PERCENT)
        assertEquals(listOf(85, 90, 95, 100), BubbleColours.GUARANTEED_STEPS)
        assertTrue(BubbleColours.OPACITY_GUARANTEED_PERCENT > BubbleColours.OPACITY_FLOOR_PERCENT)
        // And the ladder is UNEVEN — 10s below 80, 5s from 80 — which is why the Settings slider
        // is index-driven (BubbleColourRowsPinTest) rather than a percent range with even stops.
        val strides = steps.zipWithNext { a, b -> b - a }
        assertTrue("uneven by design", strides.toSet().size > 1)
    }

    @Test
    fun theCOLOURSTheOWNERRuledOutAreUNREACHABLE_andSoAreTheOnesTheARITHMETICRulesOut() {
        // "Definitely no black text on black background." It fails at every opacity, and it
        // fails because a black composite over a black backdrop is black — not because it is
        // named here.
        assertFalse("black text", BubbleColours.legibleEverywhere(black))
        assertFalse("near-black navy (the app's own `background`)", BubbleColours.legibleEverywhere(0xFF0F172A.toInt()))
        assertFalse(black in BubbleColours.PALETTE.map { it.argb })

        // THE SHADE RULING, DERIVED. Pure red clears the floor over a BLACK backdrop (5.25:1)
        // and fails it over a white one at the default opacity (4.40:1) — so "red for our live
        // words" is right and `#FF0000` is the wrong red, by arithmetic. The app's own brand red
        // `#EF4444` (`gradient_red`/`secondary`/`error`) fails too, which is why the default is
        // not simply reused from colors.xml.
        assertFalse("pure red", BubbleColours.legibleEverywhere(0xFFFF0000.toInt()))
        assertFalse("brand red #EF4444", BubbleColours.legibleEverywhere(0xFFEF4444.toInt()))
        // The three shades' numbers, PINNED — they are quoted in `LIVE_DEFAULT`'s KDoc, which is
        // what the next reader trusts, and an unasserted number in a KDoc drifts from the
        // arithmetic beside it. Over a white app at the default step and at the guaranteed step.
        val white90 = BubbleColours.compositeOver(90, white)
        val white85 = BubbleColours.compositeOver(BubbleColours.OPACITY_GUARANTEED_PERCENT, white)
        assertEquals(4.40, BubbleColours.contrastRatio(0xFFFF0000.toInt(), white90), 0.01)
        assertEquals(3.78, BubbleColours.contrastRatio(0xFFFF0000.toInt(), white85), 0.01)
        assertEquals(5.51, BubbleColours.contrastRatio(BubbleColours.LIVE_DEFAULT, white90), 0.01)
        assertEquals(4.74, BubbleColours.contrastRatio(BubbleColours.LIVE_DEFAULT, white85), 0.01)
        assertEquals(4.67, BubbleColours.contrastRatio(0xFFEF4444.toInt(), white90), 0.01)
        assertEquals(4.02, BubbleColours.contrastRatio(0xFFEF4444.toInt(), white85), 0.01)
        assertTrue(
            "pure red is fine over a DARK app — the failure is the unknowable backdrop",
            BubbleColours.contrastRatio(0xFFFF0000.toInt(), black) >= BubbleColours.CONTRAST_FLOOR,
        )

        // A few mid-tone house colours that a future palette edit might reach for.
        for (ruledOut in listOf(0xFF3B82F6.toInt(), 0xFF8B5CF6.toInt(), 0xFF64748B.toInt())) {
            assertFalse(Integer.toHexString(ruledOut), BubbleColours.legibleEverywhere(ruledOut))
        }
    }

    @Test
    fun theGUARANTEEDStepIsTheLOWESTStepTheWholePaletteSurvives_andNotARoundNumberSomebodyLiked() {
        // One step below the guaranteed step, the palette breaks — so 85 is DERIVED from the
        // darkest colour the owner's ruling asks for (the red) rather than picked. 4.8.0 moved
        // the FLOOR under it; this argues the 85 boundary so it stays argued, not assumed.
        val below = BubbleColours.OPACITY_GUARANTEED_PERCENT - 5
        assertEquals(80, below)
        assertTrue("80 is a step the slider can now reach", below in BubbleColours.OPACITY_STEPS)
        val worstBelow = BubbleColours.PALETTE.minOf { swatch ->
            listOf(black, white).minOf {
                BubbleColours.contrastRatio(swatch.argb, BubbleColours.compositeOver(below, it))
            }
        }
        assertTrue(
            "at $below%% the palette's worst entry is $worstBelow:1 — if this passed, the guarantee starts too high",
            worstBelow < BubbleColours.CONTRAST_FLOOR,
        )
        // THE BINDING ENTRY BY NAME: the owner's red over a white app at 80 is 3.96:1, under
        // the 4.5:1 floor — the number the KDoc on OPACITY_GUARANTEED_PERCENT quotes.
        val redAt80OverWhite = BubbleColours.contrastRatio(
            BubbleColours.LIVE_DEFAULT, BubbleColours.compositeOver(80, white),
        )
        assertEquals(3.96, redAt80OverWhite, 0.01)
        assertTrue("the owner's red FAILS the floor at 80 over white", redAt80OverWhite < BubbleColours.CONTRAST_FLOOR)
        val worstAtGuaranteed = BubbleColours.PALETTE.minOf { swatch ->
            listOf(black, white).minOf {
                BubbleColours.contrastRatio(swatch.argb, BubbleColours.compositeOver(BubbleColours.OPACITY_GUARANTEED_PERCENT, it))
            }
        }
        assertTrue(worstAtGuaranteed >= BubbleColours.CONTRAST_FLOOR)
    }

    @Test
    fun belowSomeOpacityNOColourAtAllCanBeGuaranteed_whichIsWHYTheGuaranteeStopsWhereItDoes() {
        // The reason the guarantee is not caution. As the panel becomes more transparent the
        // backdrop stops being OUR black and becomes someone else's screen. Past a point even
        // WHITE — the lightest thing there is — cannot clear the floor over a white app, so the
        // guarantee is not weakened there, it is gone.
        //
        // 4.8.0: those values ARE reachable now — the owner ruled the panel may go nearly clear
        // so a video plays through it — and this test's claim changes from "nothing may reach
        // them" to "the app does not PROMISE anything there, and says so". The arithmetic is
        // scoped, not overruled: `legibleEverywhere` walks GUARANTEED_STEPS, so the steps below
        // 85 are outside every legibility answer the app gives, and the slider copy states the
        // trade (BubbleColourRowsPinTest). White over white at the 20% floor is the honest
        // worst case of what the user is choosing.
        assertTrue(
            BubbleColours.contrastRatio(white, BubbleColours.compositeOver(50, white)) < BubbleColours.CONTRAST_FLOOR,
        )
        assertTrue(
            BubbleColours.contrastRatio(white, BubbleColours.compositeOver(BubbleColours.OPACITY_FLOOR_PERCENT, white)) < BubbleColours.CONTRAST_FLOOR,
        )
        assertTrue(
            BubbleColours.contrastRatio(white, BubbleColours.compositeOver(0, white)) < BubbleColours.CONTRAST_FLOOR,
        )
        // Over BLACK the words still read at the floor — the slider's "over black the words
        // still read". BLACK, and the copy says black, not "dark content": the first cut of the
        // sentence promised readability over dark content, and the arithmetic does not hold it
        // — the default red at the 20% floor is under the contrast floor over an ordinary dark
        // grey (#404040, a dimly lit video), asserted below so the wording stays argued. Below
        // 85 the app promises nothing; black is the one backdrop the fact is stated for.
        assertTrue(
            BubbleColours.contrastRatio(white, BubbleColours.compositeOver(BubbleColours.OPACITY_FLOOR_PERCENT, black)) >= BubbleColours.CONTRAST_FLOOR,
        )
        assertTrue(
            BubbleColours.contrastRatio(BubbleColours.LIVE_DEFAULT, BubbleColours.compositeOver(BubbleColours.OPACITY_FLOOR_PERCENT, black)) >= BubbleColours.CONTRAST_FLOOR,
        )
        val darkGrey = 0xFF404040.toInt()
        assertTrue(
            "the default red at the floor over dark grey is under the contrast floor — which is " +
                "why the slider says \"over black\" and promises nothing about dark content",
            BubbleColours.contrastRatio(BubbleColours.LIVE_DEFAULT, BubbleColours.compositeOver(BubbleColours.OPACITY_FLOOR_PERCENT, darkGrey)) < BubbleColours.CONTRAST_FLOOR,
        )
        // The steps below the guarantee exist (the ruling) and none is below the floor (the clamp).
        assertTrue(BubbleColours.OPACITY_STEPS.any { it < BubbleColours.OPACITY_GUARANTEED_PERCENT })
        assertTrue(BubbleColours.OPACITY_STEPS.none { it < BubbleColours.OPACITY_FLOOR_PERCENT })
        // And the guard's scope is exactly the band: a colour legible at 85 and above is
        // `legibleEverywhere` even though it is NOT legible over white at 20 — because 20 is not
        // a step the app promises anything about.
        assertTrue(BubbleColours.legibleEverywhere(white))
        assertTrue(
            BubbleColours.contrastRatio(white, BubbleColours.compositeOver(20, white)) < BubbleColours.CONTRAST_FLOOR,
        )
    }

    // ---------------------------------------------------------------- the stored values

    @Test
    fun aStoredOpacityIsSNAPPEDToTheLadderAndCanNEVERComeBackUnderTheFloor() {
        // Every read re-clamps, the way `applyPreviewSize` re-clamps the panel geometry: a
        // value written by an older build, a corrupted preferences file or a future palette
        // edit must not be able to put an off-ladder value on screen. Since 4.8.0 the floor is
        // 20 and the ladder is UNEVEN, so the snap is "nearest step" and the boundaries between
        // 80 and 85 are asserted by name: 82 -> 80, 83 -> 85.
        assertEquals(20, BubbleColours.opacityPercent(0))
        assertEquals(20, BubbleColours.opacityPercent(-1))
        assertEquals(20, BubbleColours.opacityPercent(23))
        assertEquals(30, BubbleColours.opacityPercent(26))
        assertEquals(40, BubbleColours.opacityPercent(40))
        assertEquals(80, BubbleColours.opacityPercent(82))
        assertEquals(85, BubbleColours.opacityPercent(83))
        assertEquals(85, BubbleColours.opacityPercent(84))
        assertEquals(85, BubbleColours.opacityPercent(85))
        assertEquals(85, BubbleColours.opacityPercent(86))
        assertEquals(90, BubbleColours.opacityPercent(89))
        assertEquals(90, BubbleColours.opacityPercent(92))
        assertEquals(95, BubbleColours.opacityPercent(96))
        assertEquals(100, BubbleColours.opacityPercent(100))
        assertEquals(100, BubbleColours.opacityPercent(200))
        assertEquals(100, BubbleColours.opacityPercent(255))
        // Every answer is on the ladder, for every input in a wide sweep.
        for (stored in -50..200) {
            assertTrue(stored.toString(), BubbleColours.opacityPercent(stored) in BubbleColours.OPACITY_STEPS)
        }
    }

    @Test
    fun aStoredCOLOUROutsideThePaletteFallsBackToItsDEFAULT() {
        // The guard is PALETTE MEMBERSHIP and not `legibleEverywhere`, deliberately: it makes
        // the palette the single authority, so the invariant above ("every palette entry clears
        // the floor at every step") is the whole proof that no reachable combination fails. A
        // legibility test here would be a second authority, and the two could disagree the day
        // the palette is edited.
        assertEquals(
            BubbleColours.LIVE_DEFAULT,
            BubbleColours.textColour(black, BubbleColours.LIVE_DEFAULT),
        )
        assertEquals(
            BubbleColours.COMMITTED_DEFAULT,
            BubbleColours.textColour(0xFFEF4444.toInt(), BubbleColours.COMMITTED_DEFAULT),
        )
        assertEquals(
            "a legible colour that is not on the palette is still not reachable",
            BubbleColours.LIVE_DEFAULT,
            BubbleColours.textColour(0xFF00FF00.toInt(), BubbleColours.LIVE_DEFAULT),
        )
        assertEquals(
            "and 0 — what an unset Int preference reads as — is the default, not transparent black",
            BubbleColours.COMMITTED_DEFAULT,
            BubbleColours.textColour(0, BubbleColours.COMMITTED_DEFAULT),
        )
        // A palette entry survives, alpha included: the palette is opaque ARGB and the views
        // take ARGB.
        for (swatch in BubbleColours.PALETTE) {
            assertEquals(swatch.argb, BubbleColours.textColour(swatch.argb, BubbleColours.LIVE_DEFAULT))
            assertEquals("${swatch.name} must be fully opaque", 0xFF, (swatch.argb ushr 24) and 0xFF)
        }
    }

    @Test
    fun theHINTIsFIXEDBecauseITSOWNAlphaPutsItOUTSIDEThePalettesGuarantee() {
        // The panel's THIRD text colour: "Listening...", the hint on `transcription_edit_text`,
        // which is on screen in EVERY session before the first word lands and is the signal that
        // the app is listening at all. It is `#99FFFFFF`, fixed, and it does NOT follow the
        // committed colour — and the reason is arithmetic, not taste.
        assertEquals(0x99FFFFFF.toInt(), BubbleColours.HINT_ARGB)
        assertEquals(0x99, (BubbleColours.HINT_ARGB ushr 24) and 0xFF)
        assertTrue("the fixed hint is legible everywhere", BubbleColours.legibleEverywhere(BubbleColours.HINT_ARGB))
        assertEquals("and never drops below 6.36:1", 6.36, BubbleColours.worstContrast(BubbleColours.HINT_ARGB), 0.01)

        // DERIVING it from the committed colour at the same alpha — the shape an earlier round of
        // this build shipped — takes 16 of the 25 palette entries UNDER the floor, the owner's
        // own red worst at 2.50:1. Both halves are asserted, the count and the number, so the
        // class cannot reopen quietly.
        fun derived(committed: Int) = (0x99 shl 24) or (committed and 0x00FFFFFF)
        assertFalse("a hint derived from the red", BubbleColours.legibleEverywhere(derived(BubbleColours.LIVE_DEFAULT)))
        assertEquals(2.50, BubbleColours.worstContrast(derived(BubbleColours.LIVE_DEFAULT)), 0.01)
        assertEquals(
            "a derived hint fails for two thirds of the palette",
            16,
            BubbleColours.PALETTE.count { !BubbleColours.legibleEverywhere(derived(it.argb)) },
        )

        // And it is NOT fixable by moving the alpha constant: the lowest alpha at which the whole
        // palette's derived hint clears the floor is 0xF7 (97%), by which point a hint is
        // indistinguishable from committed text and has stopped being a hint. So the hint's hue
        // following the user's choice and "Listening..." being readable cannot both be had, and
        // the first word on the panel wins. Making the hint follow the committed colour is
        // therefore a RULING — it cuts the palette to nine entries — and not an edit.
        val lowestSafeAlpha = (0x99..0xFF).first { alpha ->
            BubbleColours.PALETTE.all { BubbleColours.legibleEverywhere((alpha shl 24) or (it.argb and 0x00FFFFFF)) }
        }
        assertEquals(0xF7, lowestSafeAlpha)
        assertEquals(
            "nine entries survive a derived hint at the shipped alpha",
            9,
            BubbleColours.PALETTE.count { BubbleColours.legibleEverywhere(derived(it.argb)) },
        )
    }

    // ---------------------------------------------------------------- the palette and the sample

    @Test
    fun thePaletteIsGENEROUSAndEveryEntryIsNamedAndDistinct() {
        // "Just about every other colour works, though." An earlier brief said six to eight
        // options and was WITHDRAWN, so a short list is a regression, not caution.
        assertTrue("the palette is meant to be generous: ${BubbleColours.PALETTE.size}", BubbleColours.PALETTE.size >= 20)
        assertEquals(
            "no duplicate colours",
            BubbleColours.PALETTE.size,
            BubbleColours.PALETTE.map { it.argb }.toSet().size,
        )
        assertEquals(
            "no duplicate names",
            BubbleColours.PALETTE.size,
            BubbleColours.PALETTE.map { it.name }.toSet().size,
        )
        for (swatch in BubbleColours.PALETTE) {
            assertTrue("every entry needs a word a user can read", swatch.name.isNotBlank())
        }
        // Both defaults are ON the palette, so the grid can always ring the current choice.
        assertTrue(BubbleColours.PALETTE.any { it.argb == BubbleColours.LIVE_DEFAULT })
        assertTrue(BubbleColours.PALETTE.any { it.argb == BubbleColours.COMMITTED_DEFAULT })
        // A real spread of hues rather than twenty greys: at least eight entries whose
        // dominant channel differs.
        val dominants = BubbleColours.PALETTE.map { swatch ->
            val r = (swatch.argb ushr 16) and 0xFF
            val g = (swatch.argb ushr 8) and 0xFF
            val b = swatch.argb and 0xFF
            Triple(r > g + 24, g > b + 24, b > r + 24)
        }.toSet()
        assertTrue("the palette must cover the wheel", dominants.size >= 4)
    }

    @Test
    fun theSAMPLEWordsAreFIXEDAndSayNothingAboutAnyonesTranscript() {
        // A colour sample is not transcript content — but a sample built from the LAST REAL
        // transcript would be, and it would put a user's dictation on a Settings screen and in
        // any screenshot of it. These two constants are the sample, and they are literals.
        assertTrue(BubbleColours.SAMPLE_COMMITTED.isNotBlank())
        assertTrue(BubbleColours.SAMPLE_LIVE.isNotBlank())
        assertNotEquals(BubbleColours.SAMPLE_COMMITTED, BubbleColours.SAMPLE_LIVE)
        // They have to READ like a dictation caught mid-utterance, or the sample does not show
        // what the two colours are for.
        assertTrue(BubbleColours.SAMPLE_COMMITTED.split(" ").size >= 3)
        assertTrue(BubbleColours.SAMPLE_LIVE.split(" ").size >= 3)
    }
}
