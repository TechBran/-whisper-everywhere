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
 * reachable combination of {palette entry} x {opacity step} x {the two extreme backdrops} clears
 * it. Black-on-black is excluded because it fails the arithmetic, not because it is off a list;
 * so are pure red and the app's own brand red, and that is the whole of why the default live
 * colour is not `#FF0000`.
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
    fun theDefaultsAreTHEOWNERSRulingAndTodaysUsersSeeNOTHINGTheyDidNotAskFor() {
        // Live words: RED per the ruling, and NOT pure red — the shade is derived below, not
        // chosen by eye.
        assertNotEquals("pure red is ruled out by the floor, not by taste", 0xFFFF0000.toInt(), BubbleColours.LIVE_DEFAULT)
        val r = (BubbleColours.LIVE_DEFAULT ushr 16) and 0xFF
        val g = (BubbleColours.LIVE_DEFAULT ushr 8) and 0xFF
        val b = BubbleColours.LIVE_DEFAULT and 0xFF
        assertTrue("the default live colour must READ as red", r > g + 60 && r > b + 60)
        assertEquals("and it is a palette entry", true, BubbleColours.PALETTE.any { it.argb == BubbleColours.LIVE_DEFAULT })

        // Committed words: unchanged white.
        assertEquals(0xFFFFFFFF.toInt(), BubbleColours.COMMITTED_DEFAULT)

        // Background: the DEFAULT step must composite to the exact byte the shipped drawable
        // carried (`preview_bubble_background` is #E6000000), so a user who never opens this
        // setting sees the panel they have today, bit for bit.
        assertEquals(90, BubbleColours.OPACITY_DEFAULT_PERCENT)
        assertEquals(0xE6, BubbleColours.alphaByte(BubbleColours.OPACITY_DEFAULT_PERCENT))
        assertEquals(0xE6000000.toInt(), BubbleColours.panelArgb(BubbleColours.OPACITY_DEFAULT_PERCENT))
        assertEquals(0xFF000000.toInt(), BubbleColours.panelArgb(100))
    }

    // ---------------------------------------------------------------- THE INVARIANT

    @Test
    fun everyPALETTEEntryClearsTheFloorAtEVERYReachableOpacityOverEITHERExtremeBackdrop() {
        // THE WHOLE GUARD. The bubble floats over arbitrary third-party apps, so the backdrop is
        // unknowable; composite luminance is monotone in the backdrop's, so black and white
        // BOUND every screen that can ever be behind it. Requiring both is what excludes dark
        // text at any opacity (over a black backdrop the composite is black at every alpha) and
        // what makes the LOWEST opacity the binding case for light text.
        for (swatch in BubbleColours.PALETTE) {
            for (percent in BubbleColours.OPACITY_STEPS) {
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
    fun theTWOSettingsAreINDEPENDENTBecauseTheFLOORCarriesTheWholeGuarantee() {
        // The brief's two consequences: a colour chosen at one opacity must not become
        // illegible when the opacity is LOWERED later. This is the branch built — the floor
        // makes every valid colour safe at every valid opacity — so there is no cross-validation
        // anywhere in the app and no state in which one setting invalidates the other. The
        // proof is that the WORST step is the floor step, and the palette clears it.
        val floorStep = BubbleColours.OPACITY_STEPS.min()
        assertEquals(BubbleColours.OPACITY_FLOOR_PERCENT, floorStep)
        for (swatch in BubbleColours.PALETTE) {
            val atFloor = BubbleColours.OPACITY_STEPS.minOf { percent ->
                listOf(black, white).minOf {
                    BubbleColours.contrastRatio(swatch.argb, BubbleColours.compositeOver(percent, it))
                }
            }
            val atFloorStepOnly = listOf(black, white).minOf {
                BubbleColours.contrastRatio(swatch.argb, BubbleColours.compositeOver(floorStep, it))
            }
            assertEquals("${swatch.name}: the floor step IS the worst case", atFloorStepOnly, atFloor, 1e-9)
        }
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
        // and fails it over a white one at the default opacity (4.38:1) — so "red for our live
        // words" is right and `#FF0000` is the wrong red, by arithmetic. The app's own brand red
        // `#EF4444` (`gradient_red`/`secondary`/`error`) fails too, which is why the default is
        // not simply reused from colors.xml.
        assertFalse("pure red", BubbleColours.legibleEverywhere(0xFFFF0000.toInt()))
        assertFalse("brand red #EF4444", BubbleColours.legibleEverywhere(0xFFEF4444.toInt()))
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
    fun theOPACITYFloorIsTheLOWESTStepTheWholePaletteSurvives_andNotARoundNumberSomebodyLiked() {
        // One step below the floor, the palette breaks — so the floor is DERIVED from the
        // darkest colour the owner's ruling asks for (the red) rather than picked.
        val below = BubbleColours.OPACITY_FLOOR_PERCENT - 5
        val worstBelow = BubbleColours.PALETTE.minOf { swatch ->
            listOf(black, white).minOf {
                BubbleColours.contrastRatio(swatch.argb, BubbleColours.compositeOver(below, it))
            }
        }
        assertTrue(
            "at $below%% the palette's worst entry is $worstBelow:1 — if this passed, the floor is too high",
            worstBelow < BubbleColours.CONTRAST_FLOOR,
        )
        val worstAtFloor = BubbleColours.PALETTE.minOf { swatch ->
            listOf(black, white).minOf {
                BubbleColours.contrastRatio(swatch.argb, BubbleColours.compositeOver(BubbleColours.OPACITY_FLOOR_PERCENT, it))
            }
        }
        assertTrue(worstAtFloor >= BubbleColours.CONTRAST_FLOOR)
    }

    @Test
    fun belowSomeOpacityNOColourAtAllCanBeGuaranteed_whichIsWHYThereIsAFloor() {
        // The reason the floor is not caution. As the panel becomes more transparent the
        // backdrop stops being OUR black and becomes someone else's screen. Past a point even
        // WHITE — the lightest thing there is — cannot clear the floor over a white app, so the
        // guarantee is not weakened, it is gone. Nothing in the app may reach these values.
        assertTrue(
            BubbleColours.contrastRatio(white, BubbleColours.compositeOver(50, white)) < BubbleColours.CONTRAST_FLOOR,
        )
        assertTrue(
            BubbleColours.contrastRatio(white, BubbleColours.compositeOver(0, white)) < BubbleColours.CONTRAST_FLOOR,
        )
        assertTrue(BubbleColours.OPACITY_STEPS.none { it < BubbleColours.OPACITY_FLOOR_PERCENT })
    }

    // ---------------------------------------------------------------- the stored values

    @Test
    fun aStoredOpacityIsSNAPPEDToTheLadderAndCanNEVERComeBackUnderTheFloor() {
        // Every read re-clamps, the way `applyPreviewSize` re-clamps the panel geometry: a
        // value written by an older build, a corrupted preferences file or a future palette
        // edit must not be able to put an unreadable panel on screen.
        assertEquals(85, BubbleColours.opacityPercent(0))
        assertEquals(85, BubbleColours.opacityPercent(-1))
        assertEquals(85, BubbleColours.opacityPercent(40))
        assertEquals(85, BubbleColours.opacityPercent(84))
        assertEquals(85, BubbleColours.opacityPercent(85))
        assertEquals(85, BubbleColours.opacityPercent(86))
        assertEquals(90, BubbleColours.opacityPercent(89))
        assertEquals(90, BubbleColours.opacityPercent(92))
        assertEquals(95, BubbleColours.opacityPercent(96))
        assertEquals(100, BubbleColours.opacityPercent(100))
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
    fun theHINTFollowsTheCOMMITTEDColour_becauseListeningSitsInThatView() {
        // `transcription_edit_text`'s hint ("Listening...") is shipped `#99FFFFFF` — white at
        // 60%. Left alone it would stay white under a user who made the committed text amber,
        // which reads as a bug in the panel rather than a choice. Derived, so there is no
        // fourth setting and no second authority.
        assertEquals(0x99FFFFFF.toInt(), BubbleColours.hintArgb(0xFFFFFFFF.toInt()))
        assertEquals(0x99FF5252.toInt(), BubbleColours.hintArgb(0xFFFF5252.toInt()))
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
