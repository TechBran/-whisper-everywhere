package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE CLEARANCE GATE, executed (4.5.0 Task 5). The owner's legal work becomes a red suite instead
 * of a memory.
 *
 * The decision this file serves, verbatim (owner, 2026-09-12): *"Let's set up all 6 languages and
 * before we publish to customers I will do the research and the email."* And the constraint that
 * shapes it, restated the same day: *"I still will need to be able to test on internal testing
 * track before the legal stuff."*
 *
 * So the suite has to hold two things at once, and they pull in opposite directions:
 *
 *  1. **Every one of the six languages ships, fetches and arms on the internal track TODAY**, with
 *     five of six clearances still outstanding. That half is not tested here — it is tested by
 *     every other suite in this package NOT mentioning clearance at all, and by
 *     [theClearanceStateReachesNothingTheAppRuns], which is the only test that can prove a negative
 *     about it.
 *  2. **A promotion to PRODUCTION with that work unfinished must fail the build.** That is
 *     [thePromotionGateIsWithheldTodayAndNamesTheFiveLanguagesHoldingIt] and
 *     [authorisingALanguageWhoseRecordIsNotClearedIsARedSuite].
 *
 * Four tests carry the four brief requirements, and the fifth is the one that keeps them honest:
 *
 * | requirement | test |
 * |---|---|
 * | no catalogue row may have a blank verdict | [everyShippedLanguageHasARecordedVerdictAndNoBlankEvidence] |
 * | the record may not claim production-readiness while a row is uncleared | [authorisingALanguageWhoseRecordIsNotClearedIsARedSuite] |
 * | the promotion gate blocks production and NOT the internal track | [thePromotionGateIsWithheldTodayAndNamesTheFiveLanguagesHoldingIt] |
 * | a clearance is granted over BYTES, not over a repo name | [everyClearanceIsPinnedToTheCommitTheCatalogueDownloads] |
 * | the state must never reach the bundle or the app | [theClearanceStateReachesNothingTheAppRuns] |
 *
 * **What this suite deliberately cannot do.** It cannot tell a true clearance from an invented one
 * — no test can read a lawyer's letter. What it can do is make an invention COST three separate
 * edits in three files and show up as a diff in a test that says why it exists
 * ([onlyEnglishAndFrenchAreClearedOnThisBranch]). That is the whole of the protection against the
 * one unrecoverable error in this build, and it is stated rather than implied.
 */
class StreamingPackClearanceTest {

    private val shipped: List<String> = StreamingPackCatalog.packs.map { it.language }

    // ------------------------------------------------------------------ 1. no blank verdicts

    /**
     * **A blank cell is a red suite, not an omission.** Every row the app can fetch has a recorded
     * verdict AND the evidence behind it: the licence as declared, the URL it was read at, the date
     * of that read, the corpora, and — for a cleared row — who granted it and when.
     *
     * The one-to-one is deliberate in BOTH directions. A seventh language added to
     * [StreamingPackCatalog.packs] without a record fails here (the brief's requirement). A record
     * for a language with no row fails here too, because a clearance nobody can point at a pack is
     * a claim nobody can check, and a stale one is how a withdrawn row's grant gets reused.
     */
    @Test fun everyShippedLanguageHasARecordedVerdictAndNoBlankEvidence() {
        assertEquals(
            "every language in StreamingPackCatalog.packs needs a clearance record, and every " +
                "record needs a pack: add the row to PackClearanceRecord.RECORD (or remove the " +
                "stale record) — a blank verdict is not an omission, it is a red suite",
            shipped.sorted(),
            PackClearanceRecord.RECORD.map { it.language }.sorted(),
        )
        // Two rows may not claim the same language: a duplicate would let one of them be edited
        // while the gate reads the other.
        assertEquals(
            "one record per language",
            PackClearanceRecord.RECORD.size,
            PackClearanceRecord.RECORD.map { it.language }.toSet().size,
        )
        for (record in PackClearanceRecord.RECORD) {
            val where = "clearance record for '${record.language}'"
            assertTrue("$where: licence must say what is declared upstream", record.licence.isNotBlank())
            assertTrue(
                "$where: readAt must be the URL the grant was read at",
                record.readAt.startsWith("https://"),
            )
            assertTrue(
                "$where: readOn must be an ISO date (the read has to be datable to be re-doable)",
                Regex("""^\d{4}-\d{2}-\d{2}$""").matches(record.readOn),
            )
            assertTrue(
                "$where: provenance must say how the grant read at ${record.readAt} covers the bytes this " +
                    "app downloads — three of the seven rows have a gap there and say so",
                record.provenance.isNotBlank(),
            )
            assertTrue("$where: name the corpora, or say they are undisclosed", record.corpora.isNotEmpty())
            for (corpus in record.corpora) assertTrue("$where: a blank corpus line", corpus.isNotBlank())
            assertTrue("$where: pinnedCommit must be a 40-hex commit", Regex("""^[0-9a-f]{40}$""").matches(record.pinnedCommit))
            when (val verdict = record.verdict) {
                is ClearanceVerdict.Cleared -> {
                    assertTrue("$where: a clearance needs a grantor", verdict.grantedBy.isNotBlank())
                    assertTrue("$where: a clearance needs the reason it was granted", verdict.because.isNotBlank())
                    assertTrue(
                        "$where: a clearance needs the date it was granted",
                        Regex("""^\d{4}-\d{2}-\d{2}$""").matches(verdict.grantedOn),
                    )
                }
                is ClearanceVerdict.Outstanding -> {
                    assertTrue("$where: an outstanding row must say WHAT is outstanding", verdict.question.isNotBlank())
                    assertTrue("$where: and the action that answers it", verdict.action.isNotBlank())
                }
            }
        }
    }

    // ------------------------------------------------- 2. the switch may not outrun the evidence

    /**
     * **The record may not claim production-readiness while any row is uncleared.** The switch
     * ([PackClearanceRecord.PRODUCTION_CLEARED]) is an AUTHORISATION and the verdicts are the
     * EVIDENCE; this is the test that stops the first from outrunning the second.
     *
     * Both directions are exercised against fabricated records, because the committed switch is
     * `{en, fr}` and a test that only ever saw the committed values would pass on a gate that did
     * nothing at all.
     */
    @Test fun authorisingALanguageWhoseRecordIsNotClearedIsARedSuite() {
        val record = PackClearanceRecord.RECORD
        // The real record with a switch that authorises German — exactly the edit the owner must
        // not be able to make before the email comes back.
        assertEquals(
            PromotionState.Overreached(listOf("de")),
            PackClearanceRecord.state(setOf("en", "fr", "de"), shipped, record),
        )
        // And a switch naming a language that is not in the bundle at all: a stale authorisation,
        // the same defect from the other side.
        assertEquals(
            PromotionState.Overreached(listOf("tr")),
            PackClearanceRecord.state(setOf("en", "fr", "tr"), shipped, record),
        )
        // The positive control: with every row cleared AND every row authorised, the gate opens.
        // Without this cell the gate could be a constant `false` and nothing here would notice.
        val allCleared = record.map { it.copy(verdict = clearedForTest()) }
        assertEquals(
            PromotionState.Promotable,
            PackClearanceRecord.state(shipped.toSet(), shipped, allCleared),
        )
        // Evidence in, authorisation not yet given: still WITHHELD. Research does not publish.
        assertEquals(
            PromotionState.Withheld(shipped.filterNot { it == "en" }),
            PackClearanceRecord.state(setOf("en"), shipped, allCleared),
        )
    }

    /**
     * A shipped language with NO record at all is the worst case and outranks the other three: the
     * switch cannot be checked against evidence that does not exist, so the gate reports the
     * missing record rather than a reassuring "withheld".
     */
    @Test fun aShippedLanguageWithNoRecordIsNeitherClearedNorMerelyWithheld() {
        val withoutKorean = PackClearanceRecord.RECORD.filterNot { it.language == "ko" }
        assertEquals(
            PromotionState.Unrecorded(listOf("ko")),
            PackClearanceRecord.state(setOf("en", "fr"), shipped, withoutKorean),
        )
        // Even an empty switch does not excuse it — the record is incomplete either way.
        assertEquals(
            PromotionState.Unrecorded(listOf("ko")),
            PackClearanceRecord.state(emptySet(), shipped, withoutKorean),
        )
    }

    // ------------------------------------------------------ 3. what the committed state actually is

    /**
     * **The committed state, pinned.** Today the gate is WITHHELD and it names the five languages
     * holding it. The two things this asserts are the two halves of the owner's order:
     *
     *  - `Withheld` is **not** a failure and **not** a build-time exclusion. Every one of the six
     *    languages is in `StreamingPackCatalog.packs`, in `assetPacks`, in the bundle and fetchable
     *    on the internal track while this says Withheld. That is the whole point.
     *  - It is **not** `Promotable`, and it cannot become `Promotable` until the five names below
     *    have a Cleared verdict AND appear in the switch.
     *
     * If this test ever reports `Overreached`, someone has authorised a language whose research is
     * unfinished; that is the one unrecoverable error in this build and the message says so.
     */
    @Test fun thePromotionGateIsWithheldTodayAndNamesTheFiveLanguagesHoldingIt() {
        assertEquals(
            "the committed clearance state over the SHIPPED catalogue — five languages hold " +
                "production and all six ship to the internal track regardless",
            PromotionState.Withheld(listOf("de", "ru", "id", "ko", "zh")),
            PackClearanceRecord.stateOfRecord(shipped),
        )
        // The catalogue's own order, restated so the list above is read as a census and not as a
        // sorted set: `zh` is the row the brief calls `zh-en`, and its language code is the one the
        // picker selects by (`StreamingPackCatalog.kt:1095`).
        assertEquals(listOf("en", "fr", "de", "ru", "id", "ko", "zh"), shipped)
    }

    /**
     * **No pack is marked cleared on this branch except `en` and `fr`** — the controller brief's
     * global constraint, as a test.
     *
     * This is the pin that makes an invented clearance expensive. Granting one takes three edits:
     * the verdict in `StreamingPackClearance.kt`, the switch beside it, and this literal — and the
     * third shows up in a diff under a docblock explaining why a subagent must not write it. The
     * suite cannot read a lawyer's letter; it can make the forgery visible.
     *
     * When a real clearance arrives, `docs/LANGUAGE-CLEARANCE.md` names all three edits for that
     * language and the evidence each one needs.
     */
    @Test fun onlyEnglishAndFrenchAreClearedOnThisBranch() {
        assertEquals(
            "a clearance on this branch is the OWNER's to grant — if this list grew, the diff " +
                "that grew it must carry the grantor, the date and where the evidence was read " +
                "(docs/LANGUAGE-CLEARANCE.md)",
            listOf("en", "fr"),
            PackClearanceRecord.RECORD.filter { it.verdict is ClearanceVerdict.Cleared }.map { it.language },
        )
        assertEquals(
            "the switch authorises only what the record clears",
            setOf("en", "fr"),
            PackClearanceRecord.PRODUCTION_CLEARED,
        )
        // The five outstanding rows each name a question and who can answer it. A row that said
        // "not cleared" without saying what is missing would leave the owner nothing to do.
        val outstanding = PackClearanceRecord.RECORD
            .mapNotNull { r -> (r.verdict as? ClearanceVerdict.Outstanding)?.let { r.language to it.answerer } }
        assertEquals(
            listOf(
                "de" to ClearanceAnswerer.UPSTREAM_AUTHOR,
                "ru" to ClearanceAnswerer.OWNER,
                "id" to ClearanceAnswerer.COUNSEL,
                "ko" to ClearanceAnswerer.COUNSEL,
                "zh" to ClearanceAnswerer.COUNSEL,
            ),
            outstanding,
        )
    }

    // ------------------------------------------------------------ 4. a clearance is over BYTES

    /**
     * **A clearance is granted over the bytes the app downloads, not over a repository name.**
     * Every record pins the commit its evidence was read at, and it must be the commit
     * [StreamingPackCatalog] actually downloads from.
     *
     * The failure this closes is real and cheap to cause: re-pin a row to a newer commit (an
     * upstream re-upload, a "fix the 404" edit) and every licence read, every corpus claim and
     * every grant in the record is about bytes that are no longer shipped. The repo history this
     * branch sits on has one such incident already — the 2026-09-08 voice-archive re-upload, which
     * is why `TtsModelManager` carries a known-good digest set at all.
     *
     * Verified live on 2026-09-12: for all seven repos the HF API's `sha` (the current `main`)
     * still equals the commit pinned here, i.e. nothing has been re-uploaded under any of these
     * grants since the qualification table read them.
     */
    @Test fun everyClearanceIsPinnedToTheCommitTheCatalogueDownloads() {
        for (pack in StreamingPackCatalog.packs) {
            val record = PackClearanceRecord.forLanguage(pack.language)
            assertNotNull("no clearance record for '${pack.language}'", record)
            assertTrue(
                "the clearance for '${pack.language}' was read at ${record!!.pinnedCommit}, which " +
                    "is not the commit the catalogue downloads (${pack.baseUrl}) — a re-pin voids " +
                    "the grant, the corpus claim and the licence read all at once",
                pack.baseUrl.contains("/resolve/${record.pinnedCommit}/"),
            )
            // And the evidence URL has to be about the same repository as the download. A grant
            // read on a sibling mirror is the exact mistake the table records for `zh-en` (a v1
            // and a v2 export under two names) and for `ru` (an untagged mirror of a tagged
            // upstream).
            assertTrue(
                "the clearance for '${pack.language}' cites ${record.readAt}, which is not on " +
                    "huggingface.co — say where a reader can re-read it",
                record.readAt.startsWith("https://huggingface.co/"),
            )
        }
    }

    // ------------------------------------------- 5. the state reaches nothing the app runs

    /**
     * **THE HARD REQUIREMENT, as a test.** The owner must be able to hear all six languages work
     * on his own devices before he spends a lawyer's time on them, so the clearance state must not
     * reach anything that decides what the app ships, fetches, installs or arms:
     *
     * > *"I still will need to be able to test on internal testing track before the legal stuff"*
     * > — owner, 2026-09-12
     *
     * A build-time exclusion of an uncleared pack is FORBIDDEN, and the amendment of 2026-09-12
     * names the six places the state must not reach: `assetPacks` in `app/build.gradle.kts`, the
     * six pack modules, `tools/build_asset_packs.py`, `StreamingPackCatalog.packs`, the verify
     * gates, and anything the app reads at runtime.
     *
     * **This test is the only way to prove that negative.** Every other test in this package
     * observes behaviour, and behaviour cannot distinguish "the previewer arms German" from "the
     * previewer arms German TODAY, because the switch happens to be read in a branch that is
     * currently false". So this reads the app's own sources and the build files and asserts the
     * clearance vocabulary appears in NO live line outside its own file — comments may point at it
     * (they should), code may not touch it.
     *
     * Why a source scan rather than an architecture rule: the mutation being guarded against is
     * `if (PackClearanceRecord.PRODUCTION_CLEARED.contains(pack.language))` added to a filter, a
     * pack module, or `assetPacks`. That is one line, it compiles clean, and on the internal track
     * it would quietly ship two languages instead of six — which the owner would discover by not
     * hearing German, with nothing to point at.
     */
    @Test fun theClearanceStateReachesNothingTheAppRuns() {
        val vocabulary = listOf(
            "PackClearanceRecord",
            "PackClearance",
            "ClearanceVerdict",
            "ClearanceAnswerer",
            "PromotionState",
            "PRODUCTION_CLEARED",
        )
        val home = "StreamingPackClearance.kt"
        val scanned = mutableListOf<String>()
        for (file in appSources()) {
            if (file.name == home) continue
            scanned += file.name
            val text = file.readText().replace("\r\n", "\n")
            for (needle in vocabulary) {
                val live = text.lineSequence().filter { line ->
                    val trimmed = line.trimStart()
                    val commented = trimmed.startsWith("//") || trimmed.startsWith("*") ||
                        trimmed.startsWith("/*")
                    !commented && line.contains(needle)
                }.toList()
                assertEquals(
                    "${file.name} has a LIVE reference to '$needle'. The clearance state is a " +
                        "PROMOTION record: it must not reach assetPacks, a pack module, " +
                        "build_asset_packs.py, StreamingPackCatalog.packs, a verify gate or " +
                        "anything the app reads at runtime — all six languages ship to the " +
                        "internal track while five clearances are outstanding, by the owner's " +
                        "ruling of 2026-09-12. A COMMENT pointing at it is fine and welcome.",
                    emptyList<String>(),
                    live.map { it.trim() },
                )
            }
        }
        // The scan has to have found the app, or it proves nothing: a broken path would make this
        // test pass over zero files. The main source set is well over 200 Kotlin files.
        assertTrue("the source scan found only ${scanned.size} files — it is not reading the app", scanned.size > 100)
        assertTrue(
            "the scan must cover the files the amendment names",
            scanned.containsAll(listOf("StreamingPackCatalog.kt", "StreamingPackManager.kt", "HomeScreen.kt")),
        )
    }

    /**
     * The same negative over the BUILD — the one place a Kotlin source scan cannot see, and where a
     * build-time exclusion would actually live.
     *
     * **What this does NOT re-test.** `PreviewPackLayoutTest` already holds the `assetPacks`
     * expression to a FLAT LITERAL of ten module names, with no build type, flavour, gradle
     * property or environment read in it (`PreviewPackLayoutTest.kt:207-213`, and
     * `app/build.gradle.kts:261-266` states the rule in prose). That pin is the one that makes
     * "all six ship" true, and duplicating it here would give the amendment two homes.
     *
     * What is left uncovered by it, and covered here: the clearance vocabulary reaching the
     * PLACEMENT script or one of the seven pack modules — neither of which `assetPacks` can see. A
     * `build_asset_packs.py` that skipped an uncleared pack would leave the module present, the
     * `assetPacks` literal intact, every layout pin green, and the payload absent.
     */
    @Test fun theBundleIsBuiltWithoutAskingWhetherALanguageIsCleared() {
        val buildFiles = listOf(
            "app/build.gradle.kts",
            "settings.gradle.kts",
            "tools/build_asset_packs.py",
        ) + listOf("en", "fr", "de", "ru", "id", "ko", "zh").map { "preview_$it/build.gradle.kts" }
        for (relative in buildFiles) {
            val text = repoFile(relative).readText().replace("\r\n", "\n")
            for (needle in listOf("Clearance", "PRODUCTION_CLEARED")) {
                val hits = text.lineSequence().filter { line ->
                    val trimmed = line.trimStart()
                    val commented = trimmed.startsWith("//") || trimmed.startsWith("#") ||
                        trimmed.startsWith("*")
                    !commented && line.contains(needle)
                }.toList()
                assertEquals(
                    "$relative mentions '$needle' in a live line. The bundle is built the same " +
                        "way whatever the clearance record says — six languages in, always, by " +
                        "the owner's ruling of 2026-09-12.",
                    emptyList<String>(),
                    hits.map { it.trim() },
                )
            }
        }
    }

    // ---------------------------------------------- 6. the obligations the record names, discharged

    /**
     * **The licence page is where a clearance stops being paperwork.** `oss_licenses.html` is a
     * ship gate in this repo (the 4.0 Q5 review's I1, and `PreviewCanaryClipsTest`'s own precedent
     * for the canary clips), and the previewer's weights had no entry on it at all — not even
     * English's, which has been distributed since 4.4.0. Seven sets of model weights now ride in
     * the bundle under an Apache-2.0 or MIT grant, and both of those licences ask for their notice
     * to travel with the thing they cover.
     *
     * Pinned **per language, against that language's own evidence**, so the page cannot drift from
     * the record: the URL asserted here is the record's [PackClearance.readAt] — the place the
     * grant was actually read — which is why Russian's entry names the tagged upstream rather than
     * the untagged mirror the bytes come from.
     *
     * Korean's line carries one thing more, and it is the half of a counsel question that is
     * answered by doing rather than by asking: the **NIA** acknowledgement. AI-Hub's terms of use
     * require attribution to NIA *and* require it of derivative works; whether that obligation
     * reaches this app through the weights is question (ii) of the Korean row, and the
     * qualification table's instruction is to act on it regardless, at 0.1 d with no downside. The
     * record's own `action` string says the acknowledgement is on the licences screen — this is
     * what stops that from being a claim nobody checked.
     */
    @Test fun theLicencePageNamesTheWeightsOfEveryLanguageTheAppCanFetch() {
        val page = repoFile("app/src/main/assets/oss_licenses.html").readText().replace("\r\n", "\n")
        assertTrue(
            "the previewer's weights need a section of their own: they are neither the " +
                "transcription model above them nor the bundled audio below",
            page.contains("<h2>Live words (the streaming preview)</h2>"),
        )
        // The spelling each licence asks to be named by, not our shorthand for it.
        val spelling = mapOf("apache-2.0" to "Apache License 2.0", "mit" to "MIT License")
        for (record in PackClearanceRecord.RECORD) {
            val cited = record.readAt.removePrefix("https://")
            assertTrue(
                "the licence page does not cite $cited, which is where '${record.language}'s " +
                    "grant was read — an Apache-2.0 or MIT notice has to travel with the weights " +
                    "it covers, and the page had no entry for these at all before 4.5.0",
                page.contains(cited),
            )
            val named = spelling[record.licence]
                ?: throw AssertionError("no page spelling recorded for licence '${record.licence}'")
            assertTrue("the page must name the $named that '${record.language}' ships under", page.contains(named))
        }
        // Korean's mandatory attribution, in the language the policy is written in.
        assertTrue(
            "the NIA acknowledgement is missing — AI-Hub's terms make attribution mandatory and " +
                "extend it to derivative works, and the Korean clearance record says this page " +
                "carries it",
            page.contains("한국지능정보사회진흥원") && page.contains("KsponSpeech"),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun clearedForTest(): ClearanceVerdict.Cleared = ClearanceVerdict.Cleared(
        grantedBy = "a fabricated grant, inside this test only",
        because = "the positive control for the gate",
        grantedOn = "2026-09-12",
    )

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

    private fun appSources(): List<File> =
        File(repoRoot(), "app/src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}
