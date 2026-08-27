package com.whispereverywhere.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE UNSUPPORTED-TIER GATE AND THE CARD IT DRIVES, pinned structurally (3.7 Workstream H, Task H2).
 *
 * `ModelMigrationTest` pins `decide()` exhaustively, because it is pure. What no test in this suite
 * could see before this class is the two links BETWEEN that decision and the screen:
 *
 *  1. `WhisperModelManager.unsupportedInstalledModel()` — which flag the manager reads.
 *  2. `SettingsScreen`'s `retiredModel` derivation and the `!= null` that renders the card.
 *
 * Neither is reachable from a JVM unit test. `WhisperModelManager` takes a `Context` and a
 * `PreferencesManager(Context)`, calls `android.util.Log` and `DownloadManager`, and this project has
 * **no Robolectric and no mocking framework** on its unit-test classpath (`junit:4.13.2` and
 * `kotlinx-coroutines-test` are the whole of `testImplementation`), so the gate cannot be constructed
 * with a fake store. `SettingsScreen` is a `@Composable` and Compose UI testing is `androidTest`-only
 * here. Task H1's review measured exactly that hole — finding **m2**: `retiredInstalledModel()` was
 * asserted by *no* test in `app/src/test`, so the `retired` -> `unsupported` flip "would land with a
 * fully green 1336-test suite whether or not it is correct".
 *
 * The remedy is Task F8's and it is the house idiom — pin the CALL, structurally, not the thing being
 * called; the same instrument and the same argument as `CapSeamPinTest`, `CommitFunnelPinTest` and
 * `InFlightStripWiringPinTest`.
 *
 * **The two mutations this class closes**, both of which compile and both of which leave every other
 * test in the suite green:
 *  - *The gate reverted.* `if (model.unsupported)` -> `if (model.retired)` in the manager. That is
 *    the H1->H2 interim state itself: every installed eco/base user — the entire former-default
 *    cohort, on a working 60 MB model — is shown "This model is no longer supported" plus the copy
 *    "Pro (small.en) is much faster", which is FALSE for them (pro is the slower, 190 MB tier), above
 *    a button that does nothing at all because `decide()` correctly returns `None`. Renaming the
 *    function does not defend against this: the rename is caught by the compiler, the *predicate
 *    inside it* is caught by nothing else.
 *  - *The card's condition inverted.* `if (retiredModel != null)` -> `== null` hides the card from
 *    the extreme/ultra users it exists for and raises it, with a `!!` on a null tier, for everyone
 *    else. `assembleDebug` is happy either way.
 *
 * **The source is read LF-NORMALISED.** `core.autocrlf=true` checks this repo out with CRLF, so a
 * needle written with a bare `\n` finds nothing and every assertion would pass or fail for the wrong
 * reason. The normalisation happens once, at each read site below.
 *
 * **Everything here is SYMBOL-SCOPED and no line numbers are used** — every anchor this workstream
 * inherited from the plan had drifted, by up to ~155 lines.
 */
class UnsupportedTierGatePinTest {

    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun read(relative: String): String =
        source(relative).readText().replace("\r\n", "\n")

    private val manager: String by lazy {
        read("src/main/java/com/whispereverywhere/model/WhisperModelManager.kt")
    }

    private val settings: String by lazy {
        read("src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt")
    }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun indexOfOrFail(haystack: String, what: String, needle: String): Int {
        val i = haystack.indexOf(needle)
        assertTrue("missing from $what: <<$needle>>", i >= 0)
        return i
    }

    /**
     * One declaration to its own closing brace, matched by INDENTATION rather than by counting
     * braces: the closer is the first line at the declaration's own nesting depth, which no nested
     * block can produce. Members of these classes close on four spaces.
     */
    private fun body(haystack: String, what: String, declaration: String): String {
        val start = indexOfOrFail(haystack, what, declaration)
        val close = haystack.indexOf("\n    }\n", start)
        assertTrue("the closing brace of <<$declaration>> moved", close > start)
        return haystack.substring(start, close + "\n    }\n".length)
    }

    @Test
    fun theManagerGateReadsUnsupportedAndNeverRetired() {
        // `retired` hides a tier from the chooser; `unsupported` is what moves people OFF one
        // (WhisperModel's KDoc, Task H1). The prompt gate must read the second flag: eco and base
        // carry only the first, and they still dictate perfectly well.
        val gate = body(
            manager,
            "WhisperModelManager.kt",
            "    fun unsupportedInstalledModel(): WhisperModel? {",
        )
        assertEquals(
            "the migration gate returns the selected tier only when it is UNSUPPORTED",
            1,
            count(gate, "return if (model.unsupported) model else null"),
        )
        assertEquals(
            "the manager never gates the migration prompt on `retired`: that is the H1->H2 interim " +
                "defect — it raises the card, and the false \"much faster\" claim, for every " +
                "installed eco/base user",
            0,
            count(manager, "model.retired"),
        )
        assertEquals(
            "the old name is gone, so no caller can be left on the retired-flag gate",
            0,
            count(manager, "retiredInstalledModel"),
        )
    }

    @Test
    fun theSettingsCardIsDrivenByTheUnsupportedGate() {
        // The rename's call site. A compiler catches a MISSING follow-up; nothing catches a call
        // site that was pointed back at a re-added `retiredInstalledModel()`.
        assertEquals(
            "the card's driver is the unsupported gate, read exactly once",
            1,
            count(settings, "modelManager.unsupportedInstalledModel()"),
        )
        assertEquals(
            "Settings never reads the retired-flag gate",
            0,
            count(settings, "retiredInstalledModel"),
        )
    }

    @Test
    fun theCardRendersOnAPresentUnsupportedTierAndIsNeverInverted() {
        // A separate defect from the derivation above, so a separate test: inverting this condition
        // hides the card from the extreme/ultra cohort it exists for and raises it — with a `!!` on
        // a null tier one line later — for everybody else.
        assertEquals(
            "the migration card renders when the gate returned a tier, not when it returned null",
            1,
            count(settings, "if (retiredModel != null) {"),
        )
        // The target resolution sits INSIDE that guard: it is a non-null read of `retiredModel`, so
        // an inverted or hoisted guard is a crash rather than a cosmetic slip.
        assertTrue(
            "the scope-matched target is resolved inside the card's guard",
            indexOfOrFail(settings, "SettingsScreen.kt", "if (retiredModel != null) {") <
                indexOfOrFail(
                    settings,
                    "SettingsScreen.kt",
                    "ModelMigration.targetIdFor(retiredModel.scope)",
                ),
        )
    }
}
