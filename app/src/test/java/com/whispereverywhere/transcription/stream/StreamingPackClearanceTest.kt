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
 *     [theCommittedPromotionStateWithholdsProductionAndNeverOverreaches] and
 *     [authorisingALanguageWhoseRecordIsNotClearedIsARedSuite].
 *
 * Four tests carry the four brief requirements, and the fifth is the one that keeps them honest:
 *
 * | requirement | test |
 * |---|---|
 * | no catalogue row may have a blank verdict | [everyShippedLanguageHasARecordedVerdictAndNoBlankEvidence] |
 * | the record may not claim production-readiness while a row is uncleared | [authorisingALanguageWhoseRecordIsNotClearedIsARedSuite] |
 * | the promotion gate blocks production and NOT the internal track | [theCommittedPromotionStateWithholdsProductionAndNeverOverreaches] |
 * | a clearance is granted over BYTES, not over a repo name | [everyClearanceIsPinnedToTheCommitTheCatalogueDownloads] |
 * | the state must never reach the bundle or the app | [theClearanceStateReachesNothingTheAppRuns] |
 *
 * **The two documents this class reads are in the test task's `sourcePinnedInputs`**
 * (`app/build.gradle.kts`), and that entry is not a formality: measured before adding it, mutating
 * the sheet's promotion gate, the Korean section's answerer and the Chinese commit all at once left
 * `:app:testDebugUnitTest UP-TO-DATE / BUILD SUCCESSFUL in 13s` without running a single test. A
 * pin over a document that never re-runs reports green over a document that has been edited out
 * from under it.
 *
 * **What this suite deliberately cannot do.** It cannot tell a true clearance from an invented one
 * — no test can read a lawyer's letter. What it can do is make an invention COST three separate
 * edits and show up as a diff in a test that says why it exists
 * ([onlyEnglishAndFrenchAreClearedOnThisBranch]). That is the whole of the protection against the
 * one unrecoverable error in this build, and it is stated rather than implied.
 *
 * **WHERE THE LITERALS LIVE, and why it is exactly one test** (fix round 1, blocker B1).
 * [onlyEnglishAndFrenchAreClearedOnThisBranch] is the only test here that retypes the state of the
 * record: its three literals — the cleared list, the switch, and the outstanding rows with their
 * answerers — ARE the friction, and `docs/LANGUAGE-CLEARANCE.md` prices all three. Every other
 * test in this class derives what it expects FROM the record, because a literal that is merely
 * incidental costs the owner an unpriced edit months from now with no session open: measured, the
 * checklist's three documented edits for a German yes left this suite **red in three tests**, one
 * of them the positive control for [PromotionState.Overreached] — the single assertion that keeps
 * the switch from outrunning the evidence, and the last thing that should be edited by a reader
 * who was told the suite would be green. The rule for anything added here: **if an assertion would
 * have to change when a clearance legitimately arrives, either it is in
 * [onlyEnglishAndFrenchAreClearedOnThisBranch] and the checklist names it, or it is derived.**
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
     *
     * **Every example here is DERIVED from the record** (fix round 1, B1). The first draft named
     * German as *the* unauthorised language, so the first real clearance turned this — the positive
     * control for [PromotionState.Overreached] — red, in a file the owner had been told would be
     * green. The uncleared examples are now every outstanding row there is, and the cell is
     * exercised against a fabricated record as well, so it keeps working on the day the last
     * clearance lands and there is no outstanding row left to borrow.
     */
    @Test fun authorisingALanguageWhoseRecordIsNotClearedIsARedSuite() {
        val record = PackClearanceRecord.RECORD
        val clearedNow = record
            .filter { it.verdict is ClearanceVerdict.Cleared }
            .map { it.language }
            .toSet()
        // The real record with a switch that authorises one row whose research is unfinished —
        // exactly the edit the owner must not be able to make before the answer comes back. Taken
        // from the record rather than named, and taken for EVERY outstanding row rather than one.
        for (row in record.filter { it.verdict is ClearanceVerdict.Outstanding }) {
            assertEquals(
                "authorising '${row.language}' while its verdict is Outstanding must be a red " +
                    "suite — that is the one unrecoverable error in this build",
                PromotionState.Overreached(listOf(row.language)),
                PackClearanceRecord.state(clearedNow + row.language, shipped, record),
            )
        }
        // The same cell against a FABRICATED record, so it is exercised in every state the real
        // record can reach — including the one where every row is cleared and the loop above is
        // empty.
        val lastShipped = shipped.last()
        val allButOneCleared = record.map {
            it.copy(verdict = if (it.language == lastShipped) outstandingForTest() else clearedForTest())
        }
        assertEquals(
            PromotionState.Overreached(listOf(lastShipped)),
            PackClearanceRecord.state(shipped.toSet(), shipped, allButOneCleared),
        )
        // And a switch naming a language that is not in the bundle at all: a stale authorisation,
        // the same defect from the other side.
        assertTrue("this case needs a code the catalogue does not ship", "tr" !in shipped)
        assertEquals(
            PromotionState.Overreached(listOf("tr")),
            PackClearanceRecord.state(clearedNow + "tr", shipped, record),
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
        // Which row is dropped is derived, not named: a refused language is DELETED from both the
        // catalogue and the record (`docs/LANGUAGE-CLEARANCE.md`, the refusal path), and a named
        // row here would make that deletion redden a test the checklist does not price.
        val dropped = PackClearanceRecord.RECORD.last().language
        val incomplete = PackClearanceRecord.RECORD.filterNot { it.language == dropped }
        assertEquals(
            PromotionState.Unrecorded(listOf(dropped)),
            PackClearanceRecord.state(PackClearanceRecord.PRODUCTION_CLEARED, shipped, incomplete),
        )
        // Even an empty switch does not excuse it — the record is incomplete either way.
        assertEquals(
            PromotionState.Unrecorded(listOf(dropped)),
            PackClearanceRecord.state(emptySet(), shipped, incomplete),
        )
    }

    // ------------------------------------------------------ 3. what the committed state actually is

    /**
     * **The committed state, checked in every state it can reach.** Today the gate is WITHHELD and
     * the languages holding it are named in the failure message. The two things this asserts are
     * the two halves of the owner's order:
     *
     *  - `Withheld` is **not** a failure and **not** a build-time exclusion. Every one of the six
     *    languages is in `StreamingPackCatalog.packs`, in `assetPacks`, in the bundle and fetchable
     *    on the internal track while this says Withheld. That is the whole point.
     *  - It is **not** `Promotable`, and it cannot become `Promotable` until every shipped row has
     *    a Cleared verdict AND appears in the switch.
     *
     * If this test ever reports `Overreached`, someone has authorised a language whose research is
     * unfinished; that is the one unrecoverable error in this build and the message says so.
     * `Unrecorded` means a shipped language has no record at all. **Both are defects and neither
     * may be committed, in any state** — which is why what this test expects is DERIVED from the
     * switch rather than pinned as one value (fix round 1, B1): a pinned
     * `Withheld([de, ru, id, ko, zh])` turns red on the first legitimate clearance, in a file the
     * owner has just been told by `docs/LANGUAGE-CLEARANCE.md` would be green. Derived, it stays
     * green through every clearance and through the last one — where the correct answer becomes
     * `Promotable` — and red on either defect in every one of those states. Measured both ways: the
     * checklist's three edits for a German yes leave it green, and authorising German without
     * clearing it leaves it red naming `Overreached(de)`.
     */
    @Test fun theCommittedPromotionStateWithholdsProductionAndNeverOverreaches() {
        val outstanding = PackClearanceRecord.RECORD
            .filter { it.verdict is ClearanceVerdict.Outstanding }
            .map { it.language }
        val unauthorised = shipped.filterNot { it in PackClearanceRecord.PRODUCTION_CLEARED }
        val census = "shipped: $shipped; authorised: ${PackClearanceRecord.PRODUCTION_CLEARED}; " +
            "research outstanding: $outstanding"
        // FIRST, because it is the sharpest diagnosis of the one unrecoverable error and the
        // reader of a red suite should meet it before anything else: the switch names nothing the
        // evidence has not cleared. Stated on the committed values, not on a fabricated record.
        assertEquals(
            "the switch authorises a language whose verdict is not Cleared — the switch is the " +
                "AUTHORISATION and the verdicts are the EVIDENCE, and it may never outrun them " +
                "($census)",
            emptyList<String>(),
            PackClearanceRecord.PRODUCTION_CLEARED
                .filterNot { PackClearanceRecord.forLanguage(it)?.verdict is ClearanceVerdict.Cleared },
        )
        assertEquals(
            "the committed clearance state over the SHIPPED catalogue must withhold production " +
                "from every language the switch does not name, and every language ships to the " +
                "internal track regardless ($census)",
            if (unauthorised.isEmpty()) PromotionState.Promotable else PromotionState.Withheld(unauthorised),
            PackClearanceRecord.stateOfRecord(shipped),
        )
        // And the gate may only open when nothing is outstanding — the same thing from the other
        // side, so that neither half can be satisfied by a `state()` that lost a check.
        if (PackClearanceRecord.stateOfRecord(shipped) == PromotionState.Promotable) {
            assertEquals(
                "the gate reports Promotable while a row is still outstanding ($census)",
                emptyList<String>(),
                outstanding,
            )
        }
    }

    /**
     * **No pack is marked cleared on this branch except `en` and `fr`** — the controller brief's
     * global constraint, as a test.
     *
     * This is the pin that makes an invented clearance expensive. Granting one takes three edits:
     * the verdict in `StreamingPackClearance.kt`, the switch beside it, and this test — and the
     * third shows up in a diff under a docblock explaining why a subagent must not write it. The
     * suite cannot read a lawyer's letter; it can make the forgery visible.
     *
     * **THIS TEST IS THE ONLY ONE IN THE CLASS THAT RETYPES THE RECORD, and it holds THREE
     * literals** — deliberately, and all three are priced in `docs/LANGUAGE-CLEARANCE.md`:
     *
     *  1. the cleared languages, **in the record's own order** (not sorted),
     *  2. the switch,
     *  3. the outstanding rows with their answerers — a row that stops being outstanding leaves
     *     this list, which is the edit that makes "one fewer question" visible in a diff.
     *
     * Everything else here derives from the record. If you add a fourth literal to this class, put
     * it in this test and price it in the checklist, or derive it (fix round 1, B1).
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
        // The pack modules come from the CATALOGUE, not from a typed list of language codes: a
        // refused language is deleted from the catalogue and its module goes with it (the refusal
        // path in `docs/LANGUAGE-CLEARANCE.md`), and a typed list would turn that deletion into a
        // failure in a file the checklist does not mention — and an eighth language's module would
        // be scanned by nobody (fix round 1, B1).
        val modules = StreamingPackCatalog.packs.mapNotNull { it.packName }.map { "$it/build.gradle.kts" }
        assertEquals(
            "every shipped language has a pack module and this scan must cover all of them",
            shipped.size,
            modules.size,
        )
        val buildFiles = listOf(
            "app/build.gradle.kts",
            "settings.gradle.kts",
            "tools/build_asset_packs.py",
        ) + modules
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

    // ------------------------------------------- 7. the sheet the promotion decision is made on

    /**
     * **The gate is only a gate if the promotion decision reads it.** The acceptance sheet is where
     * a promotion is actually decided in this repo — §Z, §AF and now §AL each end in a "promote
     * when…" line — so §AL0 names the record by its symbol, and this holds it there.
     *
     * The rest of what is asserted is the sheet's own honesty about the app:
     *
     *  - **AF2 survives and is marked REWRITTEN.** It passed on device in 91 and now describes the
     *    opposite behaviour, after the owner ruled the metered test out of the feature. A deleted
     *    row is how a regression gets mistaken for a fix, so the rule is that it is rewritten in
     *    place with its old text still readable.
     *  - **The Play-prompt row exists and names Play's two statuses.** Play may interpose its own
     *    confirmation or wifi-wait for an on-demand pack this size, nothing in the app can suppress
     *    it, and a device session must not read that as 91's metered card surviving.
     *  - **Every pack's own size badge appears in §AL**, derived here from the pack rather than
     *    typed, so a re-pinned pack whose bytes changed leaves a visibly stale sheet instead of a
     *    quiet one. Scoped to §AL because §AF's prose names three of the same sizes.
     *  - **At least one of the app's own sentences is quoted verbatim** — French's, in full — which
     *    pins the convention that the sheet quotes the copy rather than paraphrasing it. A
     *    paraphrase is how a device session ends up passing a row the app does not satisfy.
     */
    @Test fun theAcceptanceSheetsPromotionGateReadsTheClearanceRecord() {
        val sheet = repoFile("docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md")
            .readText().replace("\r\n", "\n")
        assertTrue(
            "the acceptance sheet's promotion gate must name the record it consults",
            sheet.contains("PackClearanceRecord.PRODUCTION_CLEARED"),
        )
        assertTrue("§AL must exist — the six languages need their own rows", sheet.contains("## AL —"))
        assertTrue(
            "AF2 passed on device in 91 and now describes the opposite: rewrite it in place and " +
                "say so, never delete it",
            sheet.contains("AF2.") && sheet.contains("REWRITTEN"),
        )
        // And the PRESERVATION, not just the label (review round 1, nit 1). "Rewritten in place"
        // means the text that passed on device is still readable; struck through, it is the only
        // record of what 91 did, and a reader who deletes it leaves a row that says the opposite of
        // what a device session once confirmed, with nothing to compare against.
        assertTrue(
            "AF2's struck-through 91 text is gone — the rule is that a row which once passed is " +
                "rewritten with its old expectation still readable, and that sentence " +
                "(\"nothing downloads until you tap it\") is the only record of what the device " +
                "session actually confirmed",
            sheet.contains("~~What it said in 91:") && sheet.contains("downloads until you tap it.**"),
        )
        for (status in listOf("REQUIRES_USER_CONFIRMATION", "WAITING_FOR_WIFI")) {
            assertTrue(
                "the sheet needs the Play-prompt row naming $status — nothing in the app can " +
                    "suppress that dialog, and a device session must not read it as the metered " +
                    "card surviving",
                sheet.contains(status),
            )
        }
        // Scoped to §AL, not to the whole document (review round 1, nit 2): §AF's own prose
        // already contains "73 MB", "71 MB" and "128 MB" — it is where the unasked-cellular cost
        // is stated — so a document-wide search would report green over a §AL row that had lost
        // its badge, which is exactly the row a tester reads the size from.
        val sectionAL = sheet.substringAfter("## AL —", "")
        assertTrue("§AL is where the six language rows live and it is not in the sheet", sectionAL.isNotBlank())
        for (pack in StreamingPackCatalog.packs) {
            val badge = StreamingPackCatalog.sizeBadge(pack.totalBytes)
            assertTrue(
                "§AL never names '${pack.language}'s size ($badge) — a tester cannot check a " +
                    "fetch whose size the row does not state",
                sectionAL.contains(badge),
            )
        }
        // Whitespace-collapsed, because the sheet wraps its prose at ~100 columns and a quoted
        // sentence crosses a line break there. What is being pinned is the WORDS, not the wrapping.
        val flowed = sheet.replace(Regex("""\s+"""), " ")
        assertTrue(
            "the sheet must quote the app's own per-pack sentence verbatim, not paraphrase it",
            flowed.contains(
                StreamingPackCopy.stripNote("French", StreamingPackCatalog.FR.stripShape),
            ),
        )
    }

    // ------------------------------------------- 8. the document the owner actually fills in

    /**
     * **The checklist, held to the record.** `docs/LANGUAGE-CLEARANCE.md` is the document the
     * owner works from: one section per outstanding language, the exact question to answer, where
     * the evidence lives, and the three edits that record an answer. A record with no checklist is
     * a record nobody can act on, and a checklist that has drifted from the record is worse than
     * none — so what is pinned is the JOIN between them:
     *
     *  - every outstanding language has a section, found by its English name;
     *  - every outstanding language's section names its answerer, so "who can close this" is never
     *     a thing the reader has to infer;
     *  - the commit each row's evidence was read at appears in the document, so the checklist and
     *     the catalogue cannot disagree about which bytes are being cleared;
     *  - both keys are named by symbol, because the instruction "flip the switch" is useless
     *     without the switch's name.
     *
     * And the refusal path, which is the half a checklist usually omits: a NO is not a switch, it
     * is the removal of a row, and the document has to say so — otherwise the first refusal gets
     * implemented as the build-time exclusion the owner's ruling forbids.
     */
    @Test fun theOwnersChecklistNamesEveryOutstandingLanguageAndTheEditsThatCloseIt() {
        val checklist = repoFile("docs/LANGUAGE-CLEARANCE.md").readText().replace("\r\n", "\n")
        for (symbol in listOf("PackClearanceRecord", "PRODUCTION_CLEARED", "ClearanceVerdict.Cleared")) {
            assertTrue("the checklist must name $symbol — a switch with no name cannot be flipped", checklist.contains(symbol))
        }
        assertTrue(
            "the checklist must say that a REFUSAL removes the row rather than setting a flag — " +
                "otherwise the first no gets built as the build-time exclusion the owner forbade",
            checklist.contains("refusal") || checklist.contains("Refusal"),
        )
        // The English section name of every language the catalogue ships — all seven, not only the
        // five outstanding today (fix round 1, B1). WITHDRAWING a clearance is a documented path in
        // this very checklist (French's, at the end of it), and it makes a cleared row outstanding:
        // the first draft answered that with `throw AssertionError("'fr' is outstanding and this
        // test does not know its section name")`, which is a red suite where the document promised
        // a green one. Every language has a section, so a withdrawal costs the edits the checklist
        // names and nothing else.
        val sectionNames = mapOf(
            "en" to "English",
            "fr" to "French",
            "de" to "German",
            "ru" to "Russian",
            "id" to "Indonesian",
            "ko" to "Korean",
            "zh" to "Chinese",
        )
        for (record in PackClearanceRecord.RECORD) {
            val open = record.verdict as? ClearanceVerdict.Outstanding ?: continue
            val name = sectionNames[record.language]
                ?: throw AssertionError(
                    "'${record.language}' is outstanding and this test does not know its section " +
                        "name — add it here and add its section to docs/LANGUAGE-CLEARANCE.md",
                )
            assertTrue("the checklist has no '## $name' section", checklist.contains("## $name"))
            // Who can close it, read from the record's own answerer rather than retyped here, and
            // in the words the document uses rather than the enum's.
            val says = when (open.answerer) {
                ClearanceAnswerer.UPSTREAM_AUTHOR -> "uploader"
                ClearanceAnswerer.OWNER -> "your own risk call"
                ClearanceAnswerer.COUNSEL -> "counsel"
            }
            assertTrue(
                "$name's section must say who can answer it ('$says' — ${open.answerer.name})",
                checklist.substringAfter("## $name").substringBefore("\n## ").contains(says),
            )
            assertTrue(
                "$name's section must name the commit its evidence was read at " +
                    "(${record.pinnedCommit}) — the checklist and the catalogue must not disagree " +
                    "about which bytes are being cleared",
                checklist.contains(record.pinnedCommit),
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun clearedForTest(): ClearanceVerdict.Cleared = ClearanceVerdict.Cleared(
        grantedBy = "a fabricated grant, inside this test only",
        because = "the positive control for the gate",
        grantedOn = "2026-09-12",
    )

    /**
     * The other half of the fabricated record: an unfinished row. It exists so the
     * [PromotionState.Overreached] control keeps working on the day the committed record has no
     * outstanding row left to borrow — a gate whose defect cell is only ever exercised while the
     * defect happens to exist in the committed values is a gate that stops being tested exactly
     * when it matters most.
     */
    private fun outstandingForTest(): ClearanceVerdict.Outstanding = ClearanceVerdict.Outstanding(
        question = "a fabricated open question, inside this test only",
        action = "the negative control for the gate",
        answerer = ClearanceAnswerer.OWNER,
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
