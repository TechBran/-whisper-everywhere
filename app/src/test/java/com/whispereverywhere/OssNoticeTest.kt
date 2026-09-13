package com.whispereverywhere

import com.whispereverywhere.transcription.stream.PackClearanceRecord
import com.whispereverywhere.transcription.stream.StreamingPackCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    // ------------------------------------------------- 3. one attribution row per fetchable pack

    /**
     * **THE FAILURE MODE THIS EXISTS FOR IS AN EIGHTH LANGUAGE.** A pack module added, a catalogue
     * row added, a clearance verdict written — and no attribution. That is one omission, on a legal
     * surface, in a build where nothing else would notice: the pack downloads, installs, arms and
     * puts words on the strip whether or not anybody credited the people who trained it.
     *
     * So nothing here is typed. The loop is [StreamingPackCatalog.packs] itself, and each row is
     * demanded **by the pack's own identity**:
     *
     *  - the row exists, at `id="pack-<language>"`, and it is inside the live-words section rather
     *    than filed under the transcription model or the bundled audio;
     *  - it carries the **forty-character revision the bytes are actually downloaded at**, taken
     *    out of [StreamingPack.baseUrl]. Not the short form and not a tag: `resolve/main` is a
     *    mutable ref, so a revision recorded to eight characters names a commit nobody can be sure
     *    they have. A re-pin to a new upstream commit that leaves the page saying the old one is
     *    red here;
     *  - it names **the repository the four files come from**, which for Russian is NOT the
     *    repository the grant is read at — the bytes come from an untagged mirror. Both belong on
     *    that row, and [PackClearanceRecord]'s `readAt` is held on the page by
     *    `StreamingPackClearanceTest.theLicencePageNamesTheWeightsOfEveryLanguageTheAppCanFetch`,
     *    so between the two tests the row cannot drop either half;
     *  - it spells the licence **as the upstream declares it** (`license: apache-2.0`,
     *    `license: mit`), from [PackClearance.licence] rather than from prose, because "Apache
     *    License 2.0" is what the licence asks to be called and `license: apache-2.0` is what the
     *    model card actually says. The page carries both, and the two tests split them.
     *
     * ### The NOTICE finding, and why it is a finding rather than an omission
     *
     * §4(d) asks that a NOTICE file's contents be carried *where the upstream supplies one*, and
     * §4(c) asks that copyright notices be retained. **None of the seven repositories contains a
     * `NOTICE`, `LICENSE` or `COPYING` file**, and each declares its grant as a bare identifier in
     * its model card's front matter with no copyright line attached. That was read from each
     * repository's complete recursive file listing at the revision pinned on its row.
     *
     * A page that simply said nothing about it would be indistinguishable from a page whose author
     * never looked, so the page states the finding — and states it without inventing a holder or a
     * year for any row, which is the specific thing the owner's decision of 2026-09-13 forbids.
     * This asserts the statement is there and that it is scoped to what was read.
     *
     * **What this does NOT establish:** that the upstreams' declarations are valid, that the
     * revisions are the right ones to have pinned, or that no notice obligation exists that this
     * page has missed. It establishes that every pack the app can fetch is credited, at the
     * revision it is fetched at, and that an eighth one cannot arrive uncredited.
     */
    @Test fun everyPackTheAppCanFetchHasAnAttributionRowWithItsPinnedRevision() {
        val page = noticeAsset().readText().replace("\r\n", "\n")
        val liveWords = page.indexOf("<h2>Live words (the streaming preview)</h2>")
        assertTrue("the live-words section is gone from the page", liveWords >= 0)

        for (pack in StreamingPackCatalog.packs) {
            val marker = "id=\"pack-${pack.language}\""
            assertTrue(
                "the licences page has no attribution row $marker. Every pack the app can fetch " +
                    "needs one, and this is the test that stops an eighth language from arriving " +
                    "in the bundle uncredited — it downloads, installs and arms whether or not " +
                    "anybody credited the people who trained it",
                page.contains(marker),
            )
            assertTrue(
                "'${pack.language}'s attribution row is outside the live-words section — a " +
                    "preview pack is neither the transcription model nor the bundled audio",
                page.indexOf(marker) > liveWords,
            )
            val row = page.substringAfter(marker).substringBefore("</div>")

            assertTrue(
                "'${pack.language}'s row must name the model. Found: $row",
                row.contains("Streaming Zipformer"),
            )

            // The revision the BYTES come from, in full, out of the catalogue's own URL.
            val revision = pack.baseUrl.substringAfter("/resolve/").substringBefore("/")
            assertEquals(
                "'${pack.language}'s baseUrl is not commit-pinned to a 40-character sha — " +
                    "nothing on this page can be pinned to a revision that does not exist",
                40,
                revision.length,
            )
            assertTrue(
                "'${pack.language}'s attribution row does not carry its pinned revision " +
                    "$revision in full. Eight characters names a commit nobody can be sure they " +
                    "have, and a re-pin that leaves this page on the old revision is a notice " +
                    "about weights the app no longer ships. Found: $row",
                row.contains(revision),
            )

            // The repository the four files are downloaded from — which for `ru` is the untagged
            // mirror, not the tagged upstream the grant is read at.
            val repo = pack.baseUrl.removePrefix("https://huggingface.co/").substringBefore("/resolve/")
            assertTrue(
                "'${pack.language}'s row does not name $repo, the repository its four files are " +
                    "downloaded from. Found: $row",
                row.contains(repo),
            )

            // The licence spelled as the upstream declares it, from the record rather than prose.
            val record = PackClearanceRecord.forLanguage(pack.language)
            assertNotNull("'${pack.language}' ships with no clearance record at all", record)
            assertTrue(
                "'${pack.language}'s row must reproduce the declaration the upstream actually " +
                    "makes — \"license: ${record!!.licence}\" — and not only the licence's " +
                    "prose name. Found: $row",
                row.contains("license: ${record.licence}"),
            )

            assertEquals(
                "'${pack.language}' is no longer a four-file pack, so the page's \"four files\" " +
                    "is stale",
                4,
                pack.files.size,
            )
        }

        // The NOTICE finding: stated, and scoped to what was read.
        for (phrase in listOf(
            "NOTICE",
            "COPYING",
            "complete recursive file listing",
            "no copyright line",
        )) {
            assertTrue(
                "the page must record what the upstreams supply as a notice and what they do " +
                    "not, in terms a reader can check — \"$phrase\" is missing. §4(d) carries a " +
                    "NOTICE file's contents where one exists; a page silent on the question is " +
                    "indistinguishable from a page whose author never looked",
                page.contains(phrase),
            )
        }
        assertTrue(
            "…and it must say that nothing was invented where nothing was supplied. That is the " +
                "one thing the owner's decision of 2026-09-13 forbids outright",
            page.contains("none has been invented") || page.contains("nothing has been invented"),
        )
    }

    // ------------------------------------------- 4. the corpora that ask for credit, credited

    /**
     * **A MODEL'S WEIGHTS AND ITS TRAINING DATA ARE LICENSED SEPARATELY**, and three of these
     * corpora ask for something in return for their use.
     *
     *  - **LibriSpeech** and **FLEURS** are CC BY 4.0 and **YODAS2** is CC BY 3.0 — attribution is
     *    a term of all three. LibriSpeech's is asserted down to its four named authors, because
     *    "attribution" to a corpus is not attribution to anybody.
     *  - **KsponSpeech** is the row where attribution is the CONDITION OF THE GRANT rather than a
     *    courtesy. AI-Hub's published FAQ permits secondary works — AI models trained on AI-Hub
     *    data — to be used, sold and distributed commercially, *provided the dataset's official
     *    name and AI Hub (aihub.or.kr) are cited as the source*. [PackClearanceRecord.KO]'s own
     *    `because` says Korean ships only if that credit ships, so this is the test that makes the
     *    sentence true rather than aspirational, alongside
     *    `StreamingPackClearanceTest.theKoreanRowRestsOnTheFaqGrantAndItsAttributionCondition`.
     *  - **Mozilla Common Voice** is CC0 and asks for nothing. It is credited anyway, and the page
     *    says which of the two situations it is in — a page that credits a CC0 corpus in the same
     *    breath as a CC BY one teaches a reader nothing about what is obligatory.
     *
     * ### And the two rows that name no corpus at all
     *
     * Russian's and Chinese-English's are UNDISCLOSED in [PackClearanceRecord], so the page says
     * undisclosed. **The failure mode here is generosity**: an undisclosed corpus is exactly the
     * row where a plausible-looking corpus gets attributed on the strength of a resemblance — which
     * is the error this build's Task 2 corrected, in the other direction, for Chinese-English. The
     * set is derived from the record and pinned, so a row that becomes disclosed, or a new row that
     * is not, forces the page's sentence to be rewritten deliberately.
     *
     * **What this does NOT establish:** that the corpus lists are complete or correct. They are the
     * publishers' own declarations, and two publishers declare nothing. It establishes that what
     * this project believes it knows is on the page, credited as each licence asks, with the
     * unknowns labelled as unknown.
     */
    @Test fun theCorporaThatAskForCreditAreCreditedByTheNameEachAsksFor() {
        val page = noticeAsset().readText().replace("\r\n", "\n")

        // CC BY 4.0 attribution is to PEOPLE, not to a corpus name.
        for (author in listOf("Vassil Panayotov", "Guoguo Chen", "Daniel Povey", "Sanjeev Khudanpur")) {
            assertTrue(
                "LibriSpeech is CC BY 4.0 and its attribution names $author. \"Trained on " +
                    "LibriSpeech\" credits a dataset, which is not what the licence asks for",
                page.contains(author),
            )
        }
        for (corpus in listOf("LibriSpeech", "FLEURS", "YODAS2", "Common Voice", "KsponSpeech")) {
            assertTrue("the page does not credit $corpus", page.contains(corpus))
        }
        assertTrue(
            "the page must say which corpus credits are OBLIGATORY and which are given anyway. " +
                "Common Voice is CC0 and asks for nothing; LibriSpeech, FLEURS and YODAS2 " +
                "require attribution. A page that presents the two identically teaches a reader " +
                "nothing about what it owes",
            page.contains("CC0") && page.contains("CC BY 4.0") && page.contains("CC BY 3.0"),
        )
        for (version in listOf("Common Voice 12.0", "Common Voice 17.0")) {
            assertTrue(
                "the page must name the Common Voice release each pack declares — $version is " +
                    "missing. A corpus credited without its version is a credit to a moving target",
                page.contains(version),
            )
        }

        // Korean: the FAQ's grant, its condition, and the prohibition we are on the right side of.
        assertTrue(
            "the Korean credit must name the dataset by its OFFICIAL name and AI-Hub " +
                "(aihub.or.kr) as the source — those two, together, are what AI-Hub's published " +
                "FAQ makes the grant conditional on. A credit naming NIA but not AI-Hub pays half",
            page.contains("KsponSpeech") && page.contains("aihub.or.kr") &&
                page.contains("한국지능정보사회진흥원"),
        )
        assertTrue(
            "the Korean row must rest on the FAQ's secondary-works grant and say the credit is " +
                "its CONDITION. It used to rest on the dataset's terms of use, which govern " +
                "access to the data — something this project never applied for and does not hold",
            page.contains("official name") && page.contains("condition"),
        )
        assertTrue(
            "…and it must state the thing the FAQ actually prohibits, which is redistributing the " +
                "original recordings. This app has never held them. Without that sentence the " +
                "credit reads as a claim to more than we rely on",
            page.contains("KsponSpeech recordings"),
        )

        // The two undisclosed rows, derived from the record so generosity cannot creep in.
        val undisclosed = PackClearanceRecord.RECORD
            .filter { record -> record.corpora.any { it.startsWith("UNDISCLOSED") } }
            .map { it.language }
            .toSet()
        assertEquals(
            "the set of rows whose training corpus is UNDISCLOSED has changed. That is not a " +
                "failure — it is the page's \"the Russian and the Chinese-English packs name no " +
                "training corpus\" sentence going out of date, and it must be rewritten " +
                "deliberately rather than left to drift. An undisclosed corpus is exactly the row " +
                "where a plausible corpus gets attributed on a resemblance",
            setOf("ru", "zh"),
            undisclosed,
        )
        assertTrue(
            "the page must say, of the rows that disclose no corpus, that none is disclosed — and " +
                "must not fill the gap in",
            page.contains("name no training corpus") && page.contains("training_subset"),
        )
    }

    // ----------------------------------------------- 5. our own changes, and only our own changes

    /**
     * **§4(b) ASKS FOR PROMINENT NOTICE OF MODIFIED FILES — AND THE HONEST ANSWER HERE IS MOSTLY
     * "NOTHING".** That is worth a test of its own, because the tempting sentence to write is the
     * one that sounds diligent: *"the weights are re-exported and int8-quantised into a four-file
     * pack layout"*. It is false. It is the same class of error as inventing an approval, pointed
     * the other way: a statement on a legal surface that the project did something it did not do.
     *
     * What `tools/build_asset_packs.py` actually does is download each file from the pinned
     * revision and refuse it unless its sha256 matches the digest in [StreamingPackCatalog] —
     * checked when the pack is assembled, again from the placed directory, again by
     * `verifyPreviewPack` before a bundle is built, and again on the device before installation.
     * The `int8` in these filenames is the upstream publisher's own export; the Russian decoder is
     * not quantised at all.
     *
     * So what this asserts is the true, narrower list — **selection, naming, packaging** — and it
     * derives the naming half from [PackFile.path] vs [PackFile.name], which is where the fact
     * lives:
     *
     *  - the total file count comes from the catalogue, so a fifth file per pack makes the page's
     *    number stale rather than quietly wrong;
     *  - every upstream directory a file is lifted out of must be named, so a future row that
     *    relocates files silently fails;
     *  - and the count of relocating rows is pinned, because the page says *two of the seven* and
     *    a third would make that sentence false.
     *
     * The forbidden-phrase scan at the end is the guard the prose needs: it fails the build if the
     * page starts claiming a modification this project does not make.
     *
     * **What this does NOT establish:** that §4(b) is satisfied, or that the bytes in a built
     * bundle are the bytes this describes. The first is a legal conclusion. The second needs an
     * inspection of a real AAB, which is a separate job and is reported as one.
     */
    @Test fun theModificationsStatedAreTheOnesWeActuallyMake() {
        val page = noticeAsset().readText().replace("\r\n", "\n")

        val files = StreamingPackCatalog.packs.sumOf { it.files.size }
        assertEquals("the packs are no longer four files each", 28, files)
        assertTrue(
            "the page must state how many files it is talking about, and $files is the " +
                "catalogue's own count. A notice about \"the weights\" in the abstract is a " +
                "notice about nothing in particular",
            page.contains("$files files"),
        )
        assertTrue(
            "the page must state that the bytes are unmodified AND how that is known — the " +
                "digest check is the whole basis of the claim",
            page.contains("byte-for-byte") && page.contains("sha256"),
        )
        assertTrue(
            "…and it must say plainly that we do not re-export or re-quantise, because the " +
                "filenames say int8 and a reader would otherwise reasonably assume we did it",
            page.contains("re-quantise") && page.contains("int8"),
        )

        // The naming change, derived from the row where the fact lives.
        val relocated = StreamingPackCatalog.packs.filter { pack ->
            pack.files.any { it.path != it.name }
        }
        assertEquals(
            "the number of packs whose files are lifted out of an upstream subdirectory has " +
                "changed, and the page's \"two of the seven\" sentence is now false. Rewrite it " +
                "rather than relaxing this",
            2,
            relocated.size,
        )
        val directories = StreamingPackCatalog.packs
            .flatMap { it.files }
            .filter { it.path != it.name }
            .map { it.path.substringBeforeLast('/') + "/" }
            .toSortedSet()
        for (directory in directories) {
            assertTrue(
                "the page must name $directory — a file placed under a name other than its " +
                    "upstream one is the only change this project makes to these artefacts, and " +
                    "an unnamed one is an unstated change",
                page.contains(directory),
            )
        }
        for (section in listOf("Selection", "Naming", "Packaging")) {
            assertTrue("the modifications notice is missing its $section item", page.contains(section))
        }

        // And the guard: no claiming a modification we do not make.
        val forbidden = mapOf(
            "re-exported by" to "we do not re-export these weights; upstream published them",
            "we re-export" to "we do not re-export these weights; upstream published them",
            "we quantise" to "the int8 export is the upstream publisher's, not ours",
            "we re-quantise" to "the int8 export is the upstream publisher's, not ours",
            "quantised here" to "the int8 export is the upstream publisher's, not ours",
            "re-trained" to "nothing in this app re-trains a published checkpoint",
        )
        for ((needle, why) in forbidden) {
            assertTrue(
                "the licences page claims a modification this project does not make: " +
                    "\"$needle\". $why. Overstating what we did to somebody else's artefact is " +
                    "the same class of error as inventing an approval, pointed the other way",
                !page.lowercase().contains(needle),
            )
        }
    }

    // ------------------------------------- 6. the checklist a promotion is actually read from

    /**
     * **THE NOTICES ARE A STEP IN THE PROMOTION CHECKLIST, SO THE CHECKLIST HAS TO KNOW WHAT THEY
     * ARE.** `docs/LANGUAGE-CLEARANCE.md` is the document the owner fills in before a release goes
     * to production, and its step 3 already said *"check the notices are still there"*. What it did
     * not say is what "there" now means — five tests, each holding a different half of Apache-2.0
     * §4 and the AI-Hub condition — so a promotion could tick step 3 on the strength of a sentence
     * written before any of them existed.
     *
     * Two things are asserted, and the second matters more than the first:
     *
     *  1. the checklist names all five tests **by their method names**, so a rename here forces the
     *     document to move with it rather than rotting into a pointer at something that no longer
     *     exists — the failure this project has hit before and now refuses by test;
     *  2. the checklist states **what a green suite does not establish**: that it reads the file in
     *     the source tree and is not evidence the asset was packaged into a bundle's `base/`. A
     *     promotion checklist that overstates its own evidence is worse than one that says nothing,
     *     because it is the document somebody ticks instead of looking.
     */
    @Test fun theOwnersChecklistNamesTheNoticeTestsAndWhatTheyCannotProve() {
        val checklist = repoFile("docs/LANGUAGE-CLEARANCE.md").readText().replace("\r\n", "\n")
        for (test in listOf(
            "theTwoLicenceTextsAreIncludedInFullRatherThanLinked",
            "everyPackTheAppCanFetchHasAnAttributionRowWithItsPinnedRevision",
            "theCorporaThatAskForCreditAreCreditedByTheNameEachAsksFor",
            "theModificationsStatedAreTheOnesWeActuallyMake",
            "theLicencesScreenIsReachableFromSettingsAndOpensThisAsset",
        )) {
            assertTrue(
                "the promotion checklist must name $test, the test that holds one half of the " +
                    "notices condition. A checklist step that says \"check the notices\" without " +
                    "naming what checks them is a step somebody ticks instead of looking — and a " +
                    "rename that leaves this document behind is how the pointer rots",
                checklist.contains(test),
            )
        }
        assertTrue(
            "…and the checklist must say what the suite CANNOT establish: it reads the asset in " +
                "the source tree, so it is neither legal clearance nor evidence that the file was " +
                "packaged into a bundle's base/. Only an inspection of a built AAB can say that",
            checklist.contains("base/") &&
                checklist.contains("condition written down rather than a condition observed"),
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
