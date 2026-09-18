package com.whispereverywhere.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TWO SPEAKER SETTINGS — their defaults, their independence and their round trip (4.10 Task 3,
 * spec §3.5 and §7 item 1).
 *
 * `SharedPreferences` is a framework class and this module runs plain JVM unit tests, so the store
 * is a HashMap driven through the same reads `PreferencesManager` performs, the
 * `PreferencesBubbleColoursTest` way — plus a source pin that the real properties really do
 * perform those reads, because a getter that hard-coded `true` would satisfy every pure assertion
 * here and ship the defect.
 *
 * ### The two defaults are opposites, and that is the whole ruling
 *
 * `detectSpeakers` is **ON** (spec §7 item 1, taken as proposed): paragraph breaks at a speaker
 * change are the improvement the owner asked for — *"right now the text just comes out as a big
 * blob"* — and a feature nobody switches on is a feature nobody sees. `speakerLabelsInExport` is
 * **OFF** (spec §2, owner's words: *"when we copy it out, no need for that unless we see fit —
 * maybe a setting … if someone flips the toggle on, then they get the speaker labels"*): the
 * clipboard and the saved file are what leaves the app, so their shape does not change under
 * anyone who did not ask for it.
 *
 * A one-speaker session is byte-for-byte today's output whichever way both switches are set, which
 * is why ON is safe as a default at all.
 */
class PreferencesSpeakerSettingsTest {

    // ------------------------------------------------------------------ the round trip

    /** The store, and the two reads exactly as `PreferencesManager` performs them. */
    private val store = HashMap<String, Boolean>()

    private fun readDetect() =
        store["detect_speakers"] ?: PreferencesManager.DETECT_SPEAKERS_DEFAULT

    private fun readLabelsInExport() =
        store["speaker_labels_in_export"] ?: PreferencesManager.SPEAKER_LABELS_IN_EXPORT_DEFAULT

    @Test
    fun aFreshInstallDETECTSSpeakersAndEXPORTSNoLabels() {
        assertTrue("detection is on by default (spec §7 item 1)", readDetect())
        assertFalse("labels stay out of copied and saved text until asked for", readLabelsInExport())
        assertTrue(PreferencesManager.DETECT_SPEAKERS_DEFAULT)
        assertFalse(PreferencesManager.SPEAKER_LABELS_IN_EXPORT_DEFAULT)
    }

    @Test
    fun eachSwitchRoundTripsInBothDirections() {
        for (value in listOf(false, true, false)) {
            store["detect_speakers"] = value
            assertEquals(value, readDetect())
        }
        for (value in listOf(true, false, true)) {
            store["speaker_labels_in_export"] = value
            assertEquals(value, readLabelsInExport())
        }
    }

    @Test
    fun theTwoKeysAreDISTINCTSoOneSwitchCannotReadTheOthers() {
        store["detect_speakers"] = false
        store["speaker_labels_in_export"] = true
        assertFalse(readDetect())
        assertTrue(readLabelsInExport())
        assertEquals(2, store.keys.size)
    }

    // ------------------------------------------------------------------ the source pin

    /** The source, with every run of whitespace collapsed to one space (the colours test's rule). */
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
    fun bothPropertiesReadTheirOwnKeyWithTheirOwnDefaultExactlyOnce() {
        needle("var detectSpeakers: Boolean")
        needle("var speakerLabelsInExport: Boolean")
        needle("prefs.getBoolean(KEY_DETECT_SPEAKERS, DETECT_SPEAKERS_DEFAULT)")
        needle("prefs.getBoolean(KEY_SPEAKER_LABELS_IN_EXPORT, SPEAKER_LABELS_IN_EXPORT_DEFAULT)")
        needle("putBoolean(KEY_DETECT_SPEAKERS, value)")
        needle("putBoolean(KEY_SPEAKER_LABELS_IN_EXPORT, value)")
        assertEquals("ONE read of the detection key", 1, count("prefs.getBoolean(KEY_DETECT_SPEAKERS"))
        assertEquals("ONE read of the export key", 1, count("prefs.getBoolean(KEY_SPEAKER_LABELS_IN_EXPORT"))
    }

    @Test
    fun theDefaultsAreNAMEDConstantsSoTheRulingHasOneHome() {
        // A literal `true` inside the getBoolean call is the same behaviour and no longer a
        // ruling anyone can find, or that a test can assert against without re-reading source.
        needle("const val DETECT_SPEAKERS_DEFAULT: Boolean = true")
        needle("const val SPEAKER_LABELS_IN_EXPORT_DEFAULT: Boolean = false")
    }

    @Test
    fun eachSettingHasAReactiveMirrorSoTheSettingsRowFollowsATap() {
        for (name in listOf("detectSpeakers", "speakerLabelsInExport")) {
            needle("val ${name}Flow: StateFlow<Boolean> = _$name.asStateFlow()")
            needle("get() = _$name.value")
            assertEquals("ONE mirror write for $name", 1, count("_$name.value = "))
        }
    }

    @Test
    fun theKeysAreNamespacedAndNeverReuseAShippedOne() {
        needle("private const val KEY_DETECT_SPEAKERS = \"detect_speakers\"")
        needle("private const val KEY_SPEAKER_LABELS_IN_EXPORT = \"speaker_labels_in_export\"")
        val keys = Regex("private const val KEY_[A-Z_]+ = \"([^\"]+)\"").findAll(text)
            .map { it.groupValues[1] }.toList()
        assertEquals("no duplicate preference keys in the whole file", keys.size, keys.toSet().size)
    }

    // ------------------------------------------------------------------ the two rows

    private val settings: String by lazy {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        val relative = "src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt"
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

    @Test
    fun bothRowsExistUnderPreferencesAndSayWhatTheSurfacesActuallyDo() {
        // The rows are the only place a user learns the §2 table, so the two sentences are pinned
        // rather than left to drift: the panel gets labels once a second voice is heard, a text
        // field gets paragraphs and never labels, and the export switch names BOTH destinations.
        assertTrue(settings.contains("title = \"Detect speakers\""))
        assertTrue(settings.contains("title = \"Speaker labels in copied and saved text\""))
        assertTrue("the detection row promises paragraphs first", settings.contains("Splits the transcript into paragraphs"))
        assertTrue("…and labels only once a second voice is heard", settings.contains("once a second voice is heard"))
        assertTrue("the export row names its OFF state", settings.contains("Off: paragraphs only"))
        assertTrue("…and its ON state by the label form", settings.contains("Speaker 1, Speaker 2"))
        // Both rows write through the manager, and each writes its own property exactly once.
        assertEquals(1, settings.split("preferencesManager.detectSpeakers = it").size - 1)
        assertEquals(1, settings.split("preferencesManager.speakerLabelsInExport = it").size - 1)
        // Under Preferences, beside the other session switches — not in a section of their own.
        val section = settings.indexOf("SettingsSection(title = \"Preferences\")")
        assertTrue("the Preferences section exists", section > 0)
        assertTrue(settings.indexOf("title = \"Detect speakers\"") > section)
        assertTrue(settings.indexOf("title = \"Speaker labels in copied and saved text\"") > section)
    }
}
