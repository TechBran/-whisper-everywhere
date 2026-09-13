package com.whispereverywhere.ui.screens

import com.whispereverywhere.service.BubbleColours
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SETTINGS SURFACE for the bubble's three colours (4.5.1 Task 2) — `LivePreviewRowsPinTest`'s
 * instrument, verbatim, turned on the rows the owner's 2026-09-12 ruling asks for.
 *
 * The rows are `@Composable` and reach `WhisperEverywhereApp`, so no JVM test can execute them.
 * Every DECISION is elsewhere and already tested: the palette, the opacity ladder, the contrast
 * floor and the sample's words are all [BubbleColours], asserted by `BubbleColoursTest`; the
 * storage and its guard are `PreferencesBubbleColoursTest`. What remains, and what would be
 * invisible to any other test while being wrong on a device:
 *
 *  - a colour grid built from hex literals written here, which no contrast assertion can reach;
 *  - a sample that does not render at the CURRENT opacity, so the user approves something other
 *    than what they will see;
 *  - a sample built from the last real transcript, which would put a user's dictation on a
 *    Settings screen and in every screenshot of one;
 *  - an opacity control whose range starts below the floor, which is the one setting that can
 *    void the guarantee for every colour at once;
 *  - a legibility judgement made here, a second opinion beside the palette's.
 */
class BubbleColourRowsPinTest {

    // ------------------------------------------------------------------ source helpers
    // (LivePreviewRowsPinTest's own, verbatim: the same walk, the same LF normalisation, the same
    // comment-blind live-line rule — a pin a commented-out line can satisfy is not a pin.)

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun liveLineCount(scope: String, needle: String): Int =
        scope.lineSequence().count { line ->
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            !commented && line.contains(needle)
        }

    private fun scopeOf(text: String, from: String, to: String): String {
        val a = text.indexOf(from)
        assertTrue("cannot find `$from`", a >= 0)
        val b = text.indexOf(to, a + from.length)
        assertTrue("cannot find `$to` after `$from`", b >= 0)
        return text.substring(a, b)
    }

    private val settings: String by lazy {
        source("src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt")
    }

    private val rows: String by lazy { scopeOf(settings, "private fun BubbleColourRows(", "\n@Composable") }
    private val sample: String by lazy { scopeOf(settings, "private fun BubbleColourSample(", "\n@Composable") }
    private val grid: String by lazy { scopeOf(settings, "private fun BubblePaletteGrid(", "\n@Composable") }
    private val opacity: String by lazy { scopeOf(settings, "private fun BubbleOpacityRow(", "\n@Composable") }

    // ------------------------------------------------------------------ the section exists

    @Test
    fun theRowsHaveTheirOWNSectionAndAreReachedFromIt() {
        // Its own section and NOT a fourth row under "Live words", for the same reason "Live
        // words" is not a row under "Read aloud" (the existing comment there): only ONE of these
        // three settings is about live words. The committed colour and the background apply to
        // every session the bubble shows, local or cloud, live or batch.
        assertEquals(1, liveLineCount(settings, "SettingsSection(title = \"Bubble colours\")"))
        assertEquals(1, liveLineCount(settings, "BubbleColourRows(app = app)"))
    }

    @Test
    fun allThreeValuesAreCOLLECTEDAndNoneIsRememberedOrFaked() {
        // `collectAsState` on the flows the one setter writes: the sample has to redraw on the
        // tap that changed a colour, and a `remember` would freeze it at whatever was stored when
        // the screen opened — which is precisely the "a setting whose effect you can only see by
        // starting a dictation" failure the sample exists to prevent.
        assertEquals(1, liveLineCount(rows, "app.preferencesManager.bubbleLiveColourFlow.collectAsState()"))
        assertEquals(1, liveLineCount(rows, "app.preferencesManager.bubbleCommittedColourFlow.collectAsState()"))
        assertEquals(1, liveLineCount(rows, "app.preferencesManager.bubbleOpacityPercentFlow.collectAsState()"))
        assertEquals("nothing here is remembered", 0, liveLineCount(rows, "remember"))
        // The writes, one each, through the guarded setters.
        assertEquals(1, liveLineCount(rows, "app.preferencesManager.bubbleLiveColour = it"))
        assertEquals(1, liveLineCount(rows, "app.preferencesManager.bubbleCommittedColour = it"))
        assertEquals(1, liveLineCount(rows, "app.preferencesManager.bubbleOpacityPercent = it"))
    }

    // ------------------------------------------------------------------ the sample

    @Test
    fun theSampleUsesTHEFIXEDWordsAndNeverAnyonesTranscript() {
        // "a COLOUR sample is not transcript content, but a sample built from the last real
        // transcript WOULD be: use fixed sample words." Both come from BubbleColours, where
        // BubbleColoursTest holds them, so no edit here can reach for a real one.
        assertEquals(1, liveLineCount(sample, "BubbleColours.SAMPLE_COMMITTED"))
        assertEquals(1, liveLineCount(sample, "BubbleColours.SAMPLE_LIVE"))
        for (forbidden in listOf(
            "TranscriptSink",
            "transcriptSink",
            "lastTranscript",
            "transcript_session",
            "sink.preview",
        )) {
            assertEquals(
                "<<$forbidden>> — the sample must never read a real transcript",
                0, liveLineCount(sample, forbidden),
            )
        }
    }

    @Test
    fun theSampleRendersAtTheCURRENTOpacityAndInTheTWOChosenColours() {
        // "the sample in Settings must render at the CURRENT alpha so what the user approves is
        // what they will see."
        assertEquals(1, liveLineCount(sample, "BubbleColours.panelArgb(opacityPercent)"))
        assertEquals(1, liveLineCount(sample, "Color(committedColour)"))
        assertEquals(1, liveLineCount(sample, "Color(liveColour)"))
        // The live line is ITALIC and the committed one is not — the bubble's own distinction
        // (`floating_bubble.xml` gives the delta strip textStyle="italic"), so the sample shows
        // which line is which even to a user who set both colours the same.
        assertEquals(1, liveLineCount(sample, "FontStyle.Italic"))
    }

    @Test
    fun theSampleIsShownOverBOTHExtremeBackdropsBecauseThatIsWhatTheGuaranteeIsAgainst() {
        // The panel is transparent over an app we do not own, so a sample on the Settings
        // surface alone would show the alpha's effect against exactly one backdrop — and the one
        // the invariant is NOT computed against. Both halves, so the see-through setting is
        // honest about what it costs.
        assertEquals(1, liveLineCount(sample, "Color.White"))
        assertEquals(1, liveLineCount(sample, "Color.Black"))
    }

    // ------------------------------------------------------------------ the controls

    @Test
    fun theGridIsDERIVEDFromThePaletteAndSpellsNoColourOfItsOwn() {
        // A hex literal here is a colour no contrast assertion can reach, on the surface that
        // actually ships. Every swatch comes from the one list BubbleColoursTest walks.
        assertEquals(1, liveLineCount(grid, "BubbleColours.PALETTE"))
        assertEquals(
            "no hex colour literal on the rows",
            0,
            liveLineCount(settings, "Color(0x") + liveLineCount(settings, "parseColor("),
        )
        // The selected swatch is ringed, so the current choice is findable in a grid of 25.
        assertTrue(liveLineCount(grid, "swatch.argb == selected") >= 1)
        // And named, or a colour is unreachable for a screen reader.
        assertTrue(liveLineCount(grid, "swatch.name") >= 1)
        // Chunked so the grid covers the whole palette whatever its length — a hardcoded row
        // count would silently drop entries the day one is added.
        assertTrue(liveLineCount(grid, ".chunked(") >= 1)
    }

    @Test
    fun theOpacityControlCannotReachBelowTheFLOOR() {
        // The one setting that can void the guarantee for every colour at once. Its range starts
        // at the floor and its resolution is the ladder — both read from BubbleColours, so the
        // control cannot disagree with the object that computed them.
        assertEquals(1, liveLineCount(opacity, "BubbleColours.OPACITY_FLOOR_PERCENT.toFloat()"))
        assertTrue(liveLineCount(opacity, "BubbleColours.OPACITY_STEPS") >= 1)
        assertEquals("no literal bound", 0, liveLineCount(opacity, "0f..") + liveLineCount(opacity, "..100f"))
        // The floor's REASON is said to the user, not only to the next reader of the KDoc: a
        // slider that stops at 85% with no explanation reads as an arbitrary limit. The number in
        // that sentence is INTERPOLATED from the constant rather than typed, so moving the floor
        // cannot leave the copy claiming the old one.
        assertTrue(
            "the row must say what the floor buys",
            liveLineCount(opacity, "OPACITY_FLOOR_PERCENT}%") >= 1,
        )
        assertEquals(
            "the floor must not be spelled as a literal in the copy",
            0,
            liveLineCount(opacity, "\"${BubbleColours.OPACITY_FLOOR_PERCENT}%"),
        )
    }

    @Test
    fun theSurfaceMakesNOJudgementAboutLegibility() {
        // The palette IS the guard (membership, not a second contrast test — see
        // BubbleColours.textColour's KDoc). A judgement here would be a second authority, and
        // the two would disagree the first time the palette is edited.
        for (forbidden in listOf("legibleEverywhere", "contrastRatio", "relativeLuminance", "CONTRAST_FLOOR", "compositeOver")) {
            assertEquals(
                "<<$forbidden>> — the rule belongs in BubbleColours, not on the screen",
                0, liveLineCount(settings, forbidden),
            )
        }
    }
}
