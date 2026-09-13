package com.whispereverywhere.data.local

import com.whispereverywhere.service.BubbleColours
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE THREE BUBBLE-COLOUR PREFERENCES — their defaults, their persistence, and the guard that
 * every read runs through (4.5.1 Task 2).
 *
 * `SharedPreferences` is a framework class and this project runs plain JVM unit tests, so the
 * round-trip is proven the way `PreferencesTtsCloudTest` proves the cloud-voice one: a HashMap
 * standing in for the store, driven through the same pure resolvers the real getters delegate
 * to — plus a source pin that they really do delegate, because a getter that read
 * `prefs.getInt(key, 0)` raw would pass every pure test above and put an invisible bubble on a
 * real screen.
 */
class PreferencesBubbleColoursTest {

    // ------------------------------------------------------------------ the round trip

    /** The store, and the three reads exactly as `PreferencesManager` performs them. */
    private val store = HashMap<String, Int>()

    private fun readLive() = BubbleColours.textColour(
        store["bubble_live_colour"] ?: BubbleColours.LIVE_DEFAULT,
        BubbleColours.LIVE_DEFAULT,
    )

    private fun readCommitted() = BubbleColours.textColour(
        store["bubble_committed_colour"] ?: BubbleColours.COMMITTED_DEFAULT,
        BubbleColours.COMMITTED_DEFAULT,
    )

    private fun readOpacity() = BubbleColours.opacityPercent(
        store["bubble_opacity_percent"] ?: BubbleColours.OPACITY_DEFAULT_PERCENT,
    )

    @Test
    fun aFreshInstallGetsTHEOWNERSDefaultsAndNothingElse() {
        assertEquals(BubbleColours.LIVE_DEFAULT, readLive())
        assertEquals(BubbleColours.COMMITTED_DEFAULT, readCommitted())
        assertEquals(BubbleColours.OPACITY_DEFAULT_PERCENT, readOpacity())
        // ...and the defaults are today's bubble: white committed text and the 0xE6 panel the
        // shipped drawable carried. Only the LIVE strip changes, which is the one thing the
        // owner asked for.
        assertEquals(0xFFFFFFFF.toInt(), readCommitted())
        assertEquals(0xE6, BubbleColours.alphaByte(readOpacity()))
    }

    @Test
    fun everyPaletteEntryAndEveryStepRoundTrips() {
        for (swatch in BubbleColours.PALETTE) {
            store["bubble_live_colour"] = swatch.argb
            store["bubble_committed_colour"] = swatch.argb
            assertEquals(swatch.name, swatch.argb, readLive())
            assertEquals(swatch.name, swatch.argb, readCommitted())
        }
        for (percent in BubbleColours.OPACITY_STEPS) {
            store["bubble_opacity_percent"] = percent
            assertEquals(percent, readOpacity())
        }
    }

    @Test
    fun theTHREEKeysAreDISTINCTSoOneSettingCannotReadAnothers() {
        store["bubble_live_colour"] = 0xFFFFEE58.toInt() // Yellow
        store["bubble_committed_colour"] = 0xFF64FFDA.toInt() // Teal
        store["bubble_opacity_percent"] = 100
        assertEquals(0xFFFFEE58.toInt(), readLive())
        assertEquals(0xFF64FFDA.toInt(), readCommitted())
        assertEquals(100, readOpacity())
        assertEquals(3, store.keys.size)
    }

    @Test
    fun aCORRUPTOrLEGACYStoredValueCannotPutAnUnreadableBubbleOnScreen() {
        // The READ guard is the load-bearing one: the palette and the opacity ladder can be
        // edited in a later build UNDER a value a user already stored, and `0` is what an Int
        // preference reads as when some other code path clears the file.
        store["bubble_live_colour"] = 0
        store["bubble_committed_colour"] = 0xFF000000.toInt() // the one thing the owner ruled out
        store["bubble_opacity_percent"] = 0
        assertEquals(BubbleColours.LIVE_DEFAULT, readLive())
        assertEquals(BubbleColours.COMMITTED_DEFAULT, readCommitted())
        assertEquals(BubbleColours.OPACITY_FLOOR_PERCENT, readOpacity())

        store["bubble_live_colour"] = 0xFFEF4444.toInt() // the app's brand red: fails the floor
        store["bubble_opacity_percent"] = 40
        assertEquals(BubbleColours.LIVE_DEFAULT, readLive())
        assertEquals(BubbleColours.OPACITY_FLOOR_PERCENT, readOpacity())

        // Whatever is stored, the value the bubble is handed is always legible.
        for (poison in listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, 0xFF000000.toInt(), 0x00FF5252)) {
            store["bubble_live_colour"] = poison
            store["bubble_committed_colour"] = poison
            assertTrue(BubbleColours.legibleEverywhere(readLive()))
            assertTrue(BubbleColours.legibleEverywhere(readCommitted()))
        }
        for (poison in listOf(0, -100, 7, 84, 101, 1000, Int.MIN_VALUE, Int.MAX_VALUE)) {
            store["bubble_opacity_percent"] = poison
            assertTrue(readOpacity() in BubbleColours.OPACITY_STEPS)
            assertTrue(readOpacity() >= BubbleColours.OPACITY_FLOOR_PERCENT)
        }
    }

    // ------------------------------------------------------------------ the source pin

    /**
     * The source, with every run of whitespace collapsed to one space.
     *
     * The pins below are about WHICH CALL is made, never about how it is wrapped, so a reformat
     * must not be able to break them — and a pin that breaks on indentation is a pin the next
     * reader deletes. Line endings are normalised for the same reason the house's other source
     * pins normalise them: this repo is mixed CRLF/LF.
     */
    private val text: String by lazy {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        val relative = "src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt"
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) {
                    return@lazy candidate.readText().replace("\r\n", "\n").replace(Regex("\\s+"), " ")
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative")
    }

    private fun needle(s: String) =
        assertTrue("missing from PreferencesManager.kt: <<$s>>", text.contains(s))

    private fun count(s: String) = text.split(s).size - 1

    @Test
    fun theGETTERSDelegateToTheGUARDAndTheSETTERSWriteThroughIt() {
        // The pin exists because every pure assertion above is about the resolvers, and a getter
        // that skipped them would satisfy all of them while shipping the defect.
        needle("var bubbleLiveColour: Int")
        needle("var bubbleCommittedColour: Int")
        needle("var bubbleOpacityPercent: Int")

        // THE READ GUARD on all three, and each with its OWN default — a committed text that
        // fell back to the live red would be a colour nobody chose.
        needle("BubbleColours.textColour( prefs.getInt(KEY_BUBBLE_LIVE_COLOUR, BubbleColours.LIVE_DEFAULT), BubbleColours.LIVE_DEFAULT,")
        needle("BubbleColours.textColour( prefs.getInt(KEY_BUBBLE_COMMITTED_COLOUR, BubbleColours.COMMITTED_DEFAULT), BubbleColours.COMMITTED_DEFAULT,")
        needle("BubbleColours.opacityPercent( prefs.getInt(KEY_BUBBLE_OPACITY_PERCENT, BubbleColours.OPACITY_DEFAULT_PERCENT),")

        // THE WRITE GUARD: hygiene rather than the invariant (the read is what holds it), but it
        // keeps an out-of-range value from ever reaching the file at all.
        needle("putInt(KEY_BUBBLE_LIVE_COLOUR, BubbleColours.textColour(value, BubbleColours.LIVE_DEFAULT))")
        needle("putInt(KEY_BUBBLE_COMMITTED_COLOUR, BubbleColours.textColour(value, BubbleColours.COMMITTED_DEFAULT))")
        needle("putInt(KEY_BUBBLE_OPACITY_PERCENT, BubbleColours.opacityPercent(value))")

        // No raw read anywhere: three keys, each read exactly once, and always inside a guard.
        assertEquals("ONE read of the live key", 1, count("prefs.getInt(KEY_BUBBLE_LIVE_COLOUR"))
        assertEquals("ONE read of the committed key", 1, count("prefs.getInt(KEY_BUBBLE_COMMITTED_COLOUR"))
        assertEquals("ONE read of the opacity key", 1, count("prefs.getInt(KEY_BUBBLE_OPACITY_PERCENT"))
    }

    @Test
    fun eachSettingHasAREACTIVEMirrorSoTheSettingsSampleCanFollowATap() {
        // `localPreviewEnabledFlow`'s shape, for the same reason: the sample in Settings has to
        // redraw on the tap that changed the colour, and a plain `var` read in composition
        // recomposes nothing. The setter is the one writer of both the file and the mirror.
        for (name in listOf("bubbleLiveColour", "bubbleCommittedColour", "bubbleOpacityPercent")) {
            needle("val ${name}Flow: StateFlow<Int> = _$name.asStateFlow()")
            needle("get() = _$name.value")
            assertEquals("ONE mirror write for $name", 1, count("_$name.value = "))
        }
    }

    @Test
    fun theKeysAreNAMESPACEDAndNeverReuseAShippedOne() {
        needle("private const val KEY_BUBBLE_LIVE_COLOUR = \"bubble_live_colour\"")
        needle("private const val KEY_BUBBLE_COMMITTED_COLOUR = \"bubble_committed_colour\"")
        needle("private const val KEY_BUBBLE_OPACITY_PERCENT = \"bubble_opacity_percent\"")
        // The store is shared with every other preference, and an Int written under a key some
        // shipped build used for something else is a corruption no guard above can see.
        val keys = Regex("private const val KEY_[A-Z_]+ = \"([^\"]+)\"").findAll(text)
            .map { it.groupValues[1] }.toList()
        assertEquals("no duplicate preference keys in the whole file", keys.size, keys.toSet().size)
    }
}
