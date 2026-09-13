package com.whispereverywhere

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE NOTICES, EXECUTED (4.5.2 Task 3). The half of the owner's decision of 2026-09-13 that is a
 * CONDITION rather than a caveat.
 *
 * Apache-2.0 §4 and MIT are not permission slips, they are **trades**. §4(a) asks a distributor to
 * include a copy of the Licence — a copy, not a link to one. §4(b) asks for prominent notice on
 * modified files. §4(c) asks that copyright, patent, trademark and attribution notices be retained.
 * §4(d) asks that a NOTICE file's contents travel along, *where the upstream supplies one*. MIT
 * asks that its copyright line and permission notice be included. And AI-Hub's published FAQ grants
 * commercial distribution of a model trained on AI-Hub data **only** on condition that the
 * dataset's official name and AI-Hub (aihub.or.kr) are cited as the source.
 *
 * So the owner's decision is complete when these ship and not before, and **Korean ships only if
 * its acknowledgement ships with it** ([PackClearanceRecord.KO]'s own `because` says so).
 *
 * ### What this suite establishes, and what it cannot
 *
 * | it establishes | it does NOT establish |
 * |---|---|
 * | the two licence texts are present in FULL, clause by clause, rather than summarised or linked | that reproducing them discharges §4 — that is a legal conclusion, and no test makes one |
 * | every pack the catalogue can fetch has an attribution row naming its upstream, its pinned revision and its declared licence | that the attribution is CORRECT about the upstream — it is checked against this repo's own record, which is the thing under review |
 * | the corpora that ask for credit are credited by the name each asks to be named by | that the corpus list is complete. Three of the seven rows have an UNDISCLOSED corpus and the page says so |
 * | the modifications we actually make are stated, and no modification we do not make is claimed | that §4(b) is satisfied, for the same reason as row one |
 * | the page is reachable: a Settings row opens a route that opens THIS asset | that a human can see it on a device. Only a device session can say that |
 *
 * **And the sharpest negative: a green suite here is not legal clearance, and it is not proof the
 * asset reaches the AAB's `base/`.** This reads the file in the source tree. Only an inspection of
 * a built bundle can say what was packaged, which is Task 4's job and is stated as such there.
 *
 * ### Why the pins are DERIVED from the catalogue rather than typed
 *
 * The failure mode this exists to catch is an eighth language: a pack module added, a catalogue row
 * added, a clearance verdict written — and no attribution. That is one omission on a legal surface,
 * it compiles clean, and nothing else in the build notices. So
 * [everyPackTheAppCanFetchHasAnAttributionRowWithItsPinnedRevision] loops
 * [StreamingPackCatalog.packs] and demands a row per pack, with that pack's own 40-character
 * revision in it. Adding the eighth row to the catalogue without adding it to the page is red.
 *
 * `oss_licenses.html` is already a declared input of the test task (`app/build.gradle.kts`, the 4.0
 * Q8 entry), which is what stops an edit confined to the asset from leaving this suite UP-TO-DATE.
 */
class OssNoticeTest {

    // ------------------------------------------------------------------ 1. it is reachable

    /**
     * **A notice that ships but that no screen opens is not delivered.** This walks the whole
     * chain, in the app's own sources, because every link of it is one line long and each one
     * breaks silently:
     *
     *  1. the asset exists and is not blank;
     *  2. `SettingsScreen` has a row the user can press, and it calls `onNavigateToLicenses`;
     *  3. `MainActivity` wires that callback to the `open_source_licenses` route;
     *  4. that route opens `oss_licenses.html` — THIS file, not another asset.
     *
     * Step 4 is the one worth spelling out: the three legal routes are identical apart from the
     * asset name, so a copy-paste that left `terms_of_service.html` on the licences route would
     * ship a licences menu entry that opens the terms. Nothing would crash and nothing else in the
     * build would fail.
     */
    @Test fun theLicencesScreenIsReachableFromSettingsAndOpensThisAsset() {
        val page = noticeAsset().readText()
        assertTrue(
            "app/src/main/assets/oss_licenses.html is blank — the notices Apache-2.0 §4 and the " +
                "AI-Hub FAQ ask for are the condition on the owner's decision of 2026-09-13, and " +
                "an empty file pays none of it",
            page.isNotBlank() && page.contains("<h1>Licenses</h1>"),
        )

        val settings = repoFile("app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt")
            .readText().replace("\r\n", "\n")
        val row = Regex("""title = "Open-Source Licenses",\s*\n\s*onClick = onNavigateToLicenses""")
        assertTrue(
            "SettingsScreen must carry a pressable row titled \"Open-Source Licenses\" wired to " +
                "onNavigateToLicenses. Without the row the page ships and no user can open it, " +
                "which pays the attribution condition into a file nobody reads",
            row.containsMatchIn(settings),
        )
        assertTrue(
            "…and the callback must be a parameter of the screen, so the row is not wired to a " +
                "no-op default at the only call site",
            settings.contains("onNavigateToLicenses: () -> Unit"),
        )

        val activity = repoFile("app/src/main/java/com/whispereverywhere/MainActivity.kt")
            .readText().replace("\r\n", "\n")
        assertTrue(
            "MainActivity must route onNavigateToLicenses to the open_source_licenses " +
                "destination — the Settings row is inert until something answers it",
            Regex("""onNavigateToLicenses = \{\s*\n\s*navController\.navigate\("open_source_licenses"\)""")
                .containsMatchIn(activity),
        )
        val destination = activity
            .substringAfter("""composable("open_source_licenses")""", "")
            .substringBefore("}")
        assertTrue(
            "the open_source_licenses destination is missing from MainActivity's graph",
            destination.isNotBlank(),
        )
        assertTrue(
            "the licences destination must open oss_licenses.html. The three legal routes differ " +
                "only in this one string, so a copy-paste that left terms_of_service.html here " +
                "would ship a licences entry that opens the terms — nothing would crash, and " +
                "nothing else in this build would fail. Found: $destination",
            destination.contains("""assetFileName = "oss_licenses.html""""),
        )
    }

    // ------------------------------------------------------------------ the house source walker

    /** The shipped notice asset. Read as a [File] so its length is assertable, not just its text. */
    private fun noticeAsset(): File = repoFile("app/src/main/assets/oss_licenses.html")

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate the repository root from ${System.getProperty("user.dir")}")
    }

    private fun repoFile(relative: String): File {
        val file = File(repoRoot(), relative)
        assertTrue("$relative does not exist under ${repoRoot()}", file.isFile)
        return file
    }
}
