package com.whispereverywhere.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "KEEP BUBBLE ALWAYS ON SCREEN" DEFAULTS TO OFF — FOR NEW INSTALLS ONLY (4.8.0, owner ruling
 * 2026-09-17: *"That's defaulted as on. We want it to not be on the screen all the time as the
 * default."*).
 *
 * Two halves, and the second is the one that matters. The default is one constant. The
 * MIGRATION is what keeps the flip from being a behaviour change nobody asked for: an existing
 * user who never opened the toggle has been living in always-on since the setting was born, and
 * an update that silently moved them to pop-up would take the bubble off their screen. So the
 * rule is pure ([PreferencesManager.bubbleAlwaysOnBackfill]) and walked over all four inputs
 * here, the `PreferencesBubbleColoursTest` way — `SharedPreferences` is a framework class, so
 * the store is a map and the wiring (that `init` asks the rule BEFORE the flow is built) is
 * pinned as source.
 */
class PreferencesBubbleAlwaysOnTest {

    // ------------------------------------------------------------------ the rule

    @Test
    fun aFreshInstallGetsTheNewDefault_off() {
        assertFalse("the 4.8 default is OFF", PreferencesManager.BUBBLE_ALWAYS_ON_DEFAULT)
        // No stored value, onboarding not completed: nothing is written, the read falls through
        // to the default, and the new user gets the pop-up bubble the owner ruled for.
        assertNull(
            PreferencesManager.bubbleAlwaysOnBackfill(hasStoredValue = false, onboardingCompleted = false),
        )
    }

    @Test
    fun anExistingInstallThatNeverTouchedTheToggleKeepsAlwaysOn() {
        // THE MIGRATION. Onboarding completed and no stored value = an install that has been
        // living under the old `true` default. It is written down once, so the flow's first read
        // — and every read after — answers what it answered before the update.
        assertEquals(
            true,
            PreferencesManager.bubbleAlwaysOnBackfill(hasStoredValue = false, onboardingCompleted = true),
        )
    }

    @Test
    fun aStoredChoiceIsNeverOverwrittenInEitherDirection() {
        // A user who set the toggle — to either value — has a stored key, and the backfill has
        // nothing to say about it. Both onboarding states, because a stored value can predate
        // onboarding completion (the toggle is reachable from Settings at any time).
        assertNull(PreferencesManager.bubbleAlwaysOnBackfill(hasStoredValue = true, onboardingCompleted = true))
        assertNull(PreferencesManager.bubbleAlwaysOnBackfill(hasStoredValue = true, onboardingCompleted = false))
    }

    @Test
    fun theRoundTripThroughAMapStoreLandsEveryInstallWhereItWas() {
        // The store as the manager drives it: `contains` for the stored-value question, the
        // onboarding flag, a conditional write, then the read with the constant default.
        fun read(store: MutableMap<String, Boolean>): Boolean {
            PreferencesManager.bubbleAlwaysOnBackfill(
                hasStoredValue = store.containsKey("bubble_always_on"),
                onboardingCompleted = store["onboarding_completed"] ?: false,
            )?.let { store["bubble_always_on"] = it }
            return store["bubble_always_on"] ?: PreferencesManager.BUBBLE_ALWAYS_ON_DEFAULT
        }
        // Fresh install: off, and NOTHING written (a written `false` would look like a choice).
        val fresh = mutableMapOf<String, Boolean>()
        assertFalse(read(fresh))
        assertFalse("a fresh install must not have a value written for it", fresh.containsKey("bubble_always_on"))
        // Existing install, toggle untouched: still on, and now written so a second construction
        // — every app start from here on — reads the same answer without re-deciding.
        val existing = mutableMapOf("onboarding_completed" to true)
        assertTrue(read(existing))
        assertEquals(true, existing["bubble_always_on"])
        assertTrue("idempotent", read(existing))
        // Existing install that chose OFF before 4.8: still off.
        val choseOff = mutableMapOf("onboarding_completed" to true, "bubble_always_on" to false)
        assertFalse(read(choseOff))
        // Existing install that chose ON explicitly: still on.
        val choseOn = mutableMapOf("onboarding_completed" to true, "bubble_always_on" to true)
        assertTrue(read(choseOn))
    }

    // ------------------------------------------------------------------ the source pin

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
    fun theBackfillRunsInInitBeforeTheFlowIsBuiltAndTheFlowReadsTheConstant() {
        // ORDER is the whole mechanism: Kotlin runs `init` blocks and property initialisers in
        // declaration order, so the backfill's write is visible to the flow's first read only if
        // the init block precedes the `_bubbleAlwaysOn` declaration. Both are pinned, and the
        // init call must come first in the file.
        needle("init { purgeLegacyCredentialStores() backfillBubbleAlwaysOn() }")
        needle("private val _bubbleAlwaysOn = MutableStateFlow(prefs.getBoolean(KEY_BUBBLE_ALWAYS_ON, BUBBLE_ALWAYS_ON_DEFAULT))")
        assertTrue(
            "the backfill must run before the flow's initialiser",
            text.indexOf("backfillBubbleAlwaysOn() }") < text.indexOf("private val _bubbleAlwaysOn ="),
        )
        // The backfill asks the pure rule with the two real facts, and writes ONLY when told to.
        needle("bubbleAlwaysOnBackfill( hasStoredValue = prefs.contains(KEY_BUBBLE_ALWAYS_ON), onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false), ) ?: return")
        needle("prefs.edit().putBoolean(KEY_BUBBLE_ALWAYS_ON, write).apply()")
        // No `true` literal default anywhere on the key: the old default must not survive as a
        // second read site that disagrees with the constant.
        assertEquals("no read of the key with a literal default", 0, count("getBoolean(KEY_BUBBLE_ALWAYS_ON, true)"))
        assertEquals("no read of the key with a literal default", 0, count("getBoolean(KEY_BUBBLE_ALWAYS_ON, false)"))
        assertEquals("ONE read of the always-on key", 1, count("prefs.getBoolean(KEY_BUBBLE_ALWAYS_ON"))
        // The 4.3.3 N1 OR in the service is untouched by this build: without the accessibility
        // service the bubble is always-on regardless of the preference. AccessibilityOptionalWiringPinTest
        // holds that line; this only records that the default flip did not reach it.
    }
}
