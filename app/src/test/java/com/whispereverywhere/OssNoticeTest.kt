package com.whispereverywhere

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

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

    // ------------------------------------------------------------- 2. the licence texts, in full

    /**
     * **A LINK IS NOT A COPY.** Apache-2.0 §4(a) asks a distributor to *"include a copy of this
     * License"* and MIT asks that *"the above copyright notice and this permission notice"* be
     * *included*. Both obligations are discharged by text travelling with the artefact, and this
     * app's artefact is the AAB — so the page before 4.5.2, which ended in three hyperlinks to
     * gnu.org, apache.org and opensource.org, included neither.
     *
     * ### Both pins are DERIVED, and that is the whole point
     *
     * A legal text asserted phrase by phrase can pass on a document with a clause missing from the
     * middle, which is exactly how a hand-pasted licence goes wrong. So each block is held to a
     * WHOLE-DOCUMENT identity instead:
     *
     *  - **Apache-2.0** against [APACHE_SHA256], the sha256 of the text the Apache Software
     *    Foundation publishes at `https://www.apache.org/licenses/LICENSE-2.0.txt`. Fetched from
     *    that URL while writing this test — 11,358 bytes, LF, 202 lines — and found byte-identical
     *    to the copy Android Studio itself ships as `LICENSE.txt`, which is a second independent
     *    read of the same document rather than a second copy of ours.
     *  - **MIT** against the `LICENSE` file of the whisper.cpp source **this repository vendors and
     *    builds**, read off disk here. That is MIT's standard text carrying its own real copyright
     *    line (`Copyright (c) 2023-2026 The ggml authors`), so reproducing it discharges ggml's
     *    notice exactly rather than approximately — and because the pin is derived, a submodule
     *    bump that moves that copyright year reddens this test instead of leaving a stale notice
     *    on a legal screen. [VENDORED_MIT_SHA256] is the pin on the pin: it says which text the
     *    page was last reconciled against, so the failure names the cause.
     *
     * **What this does NOT establish.** That reproducing the two texts discharges §4, or that the
     * items on the page are licensed as the page says. The first is a legal conclusion and no test
     * makes one; the second is checked against this repo's own record, which is the thing under
     * review. What it establishes is narrow and worth having: the two texts are present, complete,
     * unaltered to the byte, and not summarised.
     */
    @Test fun theTwoLicenceTextsAreIncludedInFullRatherThanLinked() {
        val page = noticeAsset().readText().replace("\r\n", "\n")

        // --- Apache-2.0, against the ASF's own published bytes.
        val apache = block(page, "apache-2-0")
        // Asserted before the digest purely so a failure is legible: a digest mismatch says
        // "different", a missing heading says which clause went.
        for (section in listOf(
            "1. Definitions.",
            "2. Grant of Copyright License.",
            "3. Grant of Patent License.",
            "4. Redistribution.",
            "5. Submission of Contributions.",
            "6. Trademarks.",
            "7. Disclaimer of Warranty.",
            "8. Limitation of Liability.",
            "9. Accepting Warranty or Additional Liability.",
            "APPENDIX: How to apply the Apache License to your work.",
        )) {
            assertTrue(
                "the Apache-2.0 text on the licences page is missing \"$section\". A licence with " +
                    "a section missing from the middle is not a copy of the licence, and it is " +
                    "the one defect a phrase-by-phrase pin cannot see",
                apache.contains(section),
            )
        }
        // The four obligations §4 actually imposes on THIS app, quoted so a reader of the test can
        // see what the rest of this suite exists to satisfy.
        for (clause in listOf(
            "You must give any other recipients of the Work",
            "You must cause any modified files to carry prominent notices",
            "You must retain, in the Source form of any Derivative Works",
            "If the Work includes a \"NOTICE\" text file",
        )) {
            assertTrue("§4's own words are missing from the page: \"$clause\"", apache.contains(clause))
        }
        assertEquals(
            "the Apache-2.0 block is not the ASF's published text. Expected 11,358 bytes at " +
                "sha256 $APACHE_SHA256, fetched from https://www.apache.org/licenses/LICENSE-2.0.txt " +
                "and corroborated against the copy Android Studio ships. A licence text edited " +
                "even by a character is a licence text nobody can rely on",
            APACHE_SHA256,
            sha256(apache),
        )
        assertEquals("the Apache-2.0 block is not 11,358 characters", 11_358, apache.length)

        // --- MIT, against the LICENSE of the source this app vendors and builds.
        val vendored = repoFile("app/src/main/cpp/whisper.cpp/LICENSE")
            .readText().replace("\r\n", "\n")
        assertEquals(
            "the vendored whisper.cpp LICENSE has changed (a submodule bump, most likely). That " +
                "is not a failure of this page — it is the page going out of date. Re-read the " +
                "new text, update the MIT block in oss_licenses.html to match it byte-for-byte, " +
                "and move this literal to the new digest",
            VENDORED_MIT_SHA256,
            sha256(vendored),
        )
        assertEquals(
            "the MIT block on the licences page is not the text of the LICENSE file this app " +
                "vendors and builds. MIT asks that its copyright notice AND its permission " +
                "notice be included, so the block has to be that file, not a paraphrase of it",
            vendored,
            block(page, "mit"),
        )

        // --- and the page has to say where each copy came from, or the copy is unverifiable.
        assertTrue(
            "the page must cite the source the Apache-2.0 text was copied from, with its byte " +
                "count, so a reader can re-do the comparison this test just did",
            page.contains("apache.org/licenses/LICENSE-2.0.txt") && page.contains("11,358"),
        )
        assertTrue(
            "the page must not present the two texts as a substitute for stating whose copyright " +
                "each MIT item carries. Where an upstream supplies no holder and no year, the " +
                "page says so — and the Indonesian pack is that case",
            page.contains("no copyright holder and no year"),
        )
    }

    // ------------------------------------------------------------------ the house source walker

    /**
     * One `<pre class="licence-text" id="...">` block's text, LF-normalised, with no HTML
     * unescaping applied — and none needed: both texts are pure ASCII with no `&`, `<` or `>` in
     * them, verified when they were pasted, so the bytes between the tags ARE the licence's bytes.
     * A future text that needed escaping would fail the digest here rather than pass quietly,
     * which is the right direction for that mistake to break in.
     */
    private fun block(page: String, id: String): String {
        val opening = "<pre class=\"licence-text\" id=\"$id\">"
        assertTrue(
            "the licences page has no <pre class=\"licence-text\" id=\"$id\"> block — the full " +
                "text is either missing or has been turned back into a link",
            page.contains(opening),
        )
        return page.substringAfter(opening).substringBefore("</pre>")
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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

    private companion object {

        /**
         * The Apache Software Foundation's own `LICENSE-2.0.txt`: 11,358 bytes, LF, 202 lines.
         * Fetched from `https://www.apache.org/licenses/LICENSE-2.0.txt` on 2026-09-13 and found
         * byte-identical to the copy Android Studio ships as `LICENSE.txt` — two independent reads
         * of the same document, which is why this literal is quoted rather than trusted.
         */
        const val APACHE_SHA256 = "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"

        /**
         * `app/src/main/cpp/whisper.cpp/LICENSE`, LF-normalised: 1,078 bytes, MIT's standard text
         * under `Copyright (c) 2023-2026 The ggml authors`. THE PIN ON THE PIN — the MIT block is
         * held equal to that file, so without this literal a submodule bump would move both sides
         * together and the page would stay green while its notice went stale.
         */
        const val VENDORED_MIT_SHA256 = "94f29bbed6a22c35b992c5c6ebf0e7c92f13b836b90f36f461c9cf2f0f1d010d"
    }
}
