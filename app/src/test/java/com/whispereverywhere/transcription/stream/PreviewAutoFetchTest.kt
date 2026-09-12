package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 4.4.1 auto-fetch decision and the card's state mapping, pinned EXHAUSTIVELY — every cell of
 * installed-state × user-said-no × switch × local tier × STARTER × metered × session × batch ×
 * in-flight × tried-this-launch × backed-off, and every cell of the card's own five inputs.
 *
 * The cross product is walked in full (6 language pairs × 7 states × 2^10 = 43,008 cells for the
 * decision, 2^4 × 3 = 48 for the card) and each cell is checked against the rules stated
 * INDEPENDENTLY as a flat conjunction — not against a copy of the implementation's `if` ladder,
 * which would pass for any
 * ordering of it. What that catches is precisely the bug an example-based test cannot: a refusal
 * answered in the wrong order (a metered check reached before the "the user said no" check, say,
 * so a deleted model comes back on wifi), and a state added to `StreamingPackState` later that
 * falls through into a silent 73 MB transfer.
 *
 * Then the acceptance rows are named cells of their own (AF1–AF4, plus AF2b for ruling 3b's own
 * row), because a sheet row a human will run on a device deserves to be readable as one
 * assertion.
 */
class PreviewAutoFetchTest {

    private val bools = listOf(false, true)

    private val everyState = listOf(
        StreamingPackState.Installed,
        StreamingPackState.PackDelivered,
        StreamingPackState.PackFetchable,
        StreamingPackState.Downloadable,
        StreamingPackState.Repair(StreamingPackState.PackDelivered),
        StreamingPackState.Repair(StreamingPackState.PackFetchable),
        StreamingPackState.Repair(StreamingPackState.Downloadable),
    )

    /**
     * The LANGUAGE half of the product (owner rulings 2026-09-11, consequence 2): the code the
     * user has selected, and the language of the pack whose [StreamingPackState] was read. They
     * are two inputs rather than one because the question the decision has to answer is not "is a
     * pack missing" but *"does the SELECTED language have a pack, and is THAT pack the one
     * missing?"* — and a decision that could only compare a pack to itself could not refuse the
     * case this input exists for: a user who only ever dictates in Chinese being pushed 73 MB of
     * English they can never use.
     */
    private data class Lang(val selected: String, val pack: String?)

    private val everyLangPair = listOf(
        // The ordinary case, and the only one reachable today.
        Lang("en", "en"),
        // A second language, once its pack module exists.
        Lang("es", "es"),
        // AUTO: the fair trade the owner named — no selected language, no pack to choose, no live
        // words, and therefore nothing to fetch.
        Lang("auto", null),
        // A selected language the catalogue has no row for.
        Lang("zh", null),
        // ...and the two MISMATCHES, which the call site cannot construct (it resolves the pack
        // FROM the selection) and which the decision must still refuse, because a decision that
        // trusts its caller to have matched them is not a gate.
        Lang("auto", "en"),
        Lang("zh", "en"),
    )

    /** One cell of the cross product, named so a failure says which of the 43,008 broke. */
    private data class Cell(
        val lang: Lang,
        val state: StreamingPackState,
        val userSaidNo: Boolean,
        val showLiveWords: Boolean,
        val localTierInstalled: Boolean,
        // (4.5.0 Task 3b) WHO caused this look. The one input the connection rule and the
        // back-off branch on, and the whole of the 3a/3b asymmetry.
        val starter: PreviewStarter,
        val unmetered: Boolean,
        val sessionActive: Boolean,
        val batchJobActive: Boolean,
        val packWorkInFlight: Boolean,
        val attemptedThisLaunch: Boolean,
        val backedOff: Boolean,
    )

    private fun everyCell(): List<Cell> = buildList {
        for (lang in everyLangPair) for (state in everyState) {
            for (no in bools) for (sw in bools) for (tier in bools) for (un in bools) {
                for (sess in bools) for (batch in bools) for (busy in bools) {
                    for (tried in bools) for (back in bools) {
                        for (starter in PreviewStarter.entries) {
                            add(
                                Cell(
                                    lang, state, no, sw, tier, starter, un, sess, batch, busy,
                                    tried, back,
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun decide(c: Cell): PreviewAutoFetch.Decision = PreviewAutoFetch.decide(
        selectedLanguage = c.lang.selected,
        packLanguage = c.lang.pack,
        state = c.state,
        userSaidNo = c.userSaidNo,
        showLiveWords = c.showLiveWords,
        localTierInstalled = c.localTierInstalled,
        starter = c.starter,
        unmetered = c.unmetered,
        sessionActive = c.sessionActive,
        batchJobActive = c.batchJobActive,
        packWorkInFlight = c.packWorkInFlight,
        attemptedThisLaunch = c.attemptedThisLaunch,
        backedOff = c.backedOff,
    )

    // ------------------------------------------------------------------ the whole cross product

    @Test fun theDecisionIsTheStatedRuleInEveryCellOfItsInputs() {
        val cells = everyCell()
        assertEquals("the walk must be the full cross product", 6 * 7 * 512 * 2, cells.size)
        for (c in cells) {
            // The rules, spelled independently of the implementation's ordering.
            val nothingToDo = c.state.isInstalled || c.state is StreamingPackState.Repair
            // The pack must be the one for the language the user actually picked. Stated as its
            // own clause, so the walk still checks an independently written rule.
            val wrongLanguage = c.lang.pack == null || c.lang.pack != c.lang.selected
            val refused = wrongLanguage ||
                c.userSaidNo || !c.showLiveWords || !c.localTierInstalled
            val notNow = c.sessionActive || c.batchJobActive || c.packWorkInFlight
            // A third party's bytes are never moved silently, whatever the network reads.
            val thirdParty = c.state == StreamingPackState.Downloadable
            val spendsData = c.state == StreamingPackState.PackFetchable
            val unasked = c.starter == PreviewStarter.TOP_UP
            val expected = when {
                nothingToDo || refused || notNow -> PreviewAutoFetch.Decision.NONE
                thirdParty -> PreviewAutoFetch.Decision.OFFER
                // The UNASKED top-up on a metered connection OFFERS — 4.4.1's card with a tap,
                // which ruling 3a keeps by name. (3b) A PICK is exempt: the pick IS the consent,
                // so it spends the connection at once and with no card in the way.
                spendsData && !c.unmetered && unasked -> PreviewAutoFetch.Decision.OFFER
                // The loop guard binds on both starters...
                c.attemptedThisLaunch -> PreviewAutoFetch.Decision.OFFER
                // ...and the 24 h back-off silences the unasked path only.
                c.backedOff && unasked -> PreviewAutoFetch.Decision.OFFER
                else -> PreviewAutoFetch.Decision.FETCH
            }
            assertEquals("$c", expected, decide(c))
        }
    }

    @Test fun anUnaskedSilentFetchIsOnlyEverReachedWithEveryConditionOpenAtOnce() {
        // The same walk read the other way round: the NECESSARY conditions for spending a user's
        // resources WITHOUT ASKING. Every one of these has its own named cell below; this is the
        // claim that no combination anywhere in the product sneaks past all of them.
        //
        // (4.5.0 Task 3b) Scoped to the TOP_UP starter, because a PICK is not without asking —
        // the pick IS the asking. Its own necessary conditions are the test below, which differs
        // from this one in exactly the two inputs the ruling names.
        for (c in everyCell()) {
            if (c.starter != PreviewStarter.TOP_UP) continue
            if (decide(c) != PreviewAutoFetch.Decision.FETCH) continue
            assertEquals(
                "$c: the pack fetched is the SELECTED language's, and no other — a 73 MB model " +
                    "for a language nobody picked is a download the user can never use",
                c.lang.selected, c.lang.pack,
            )
            assertFalse("$c: an installed pack is never re-fetched", c.state.isInstalled)
            assertFalse("$c: a damaged install is never silently re-fetched", c.state is StreamingPackState.Repair)
            assertFalse("$c: a delete or a dismissal is never undone", c.userSaidNo)
            assertTrue("$c: the switch must be on", c.showLiveWords)
            assertTrue(
                "$c: a device with no on-device speech model gets nothing — no word reaches " +
                    "its bubble at all (`PreviewUnreachable`'s KDoc for why, and it is not the " +
                    "previewer's gate)",
                c.localTierInstalled,
            )
            assertFalse("$c: never during a session", c.sessionActive)
            assertFalse("$c: never while a batch job runs", c.batchJobActive)
            assertFalse("$c: never on top of work already in flight", c.packWorkInFlight)
            assertFalse("$c: once per launch at most", c.attemptedThisLaunch)
            assertFalse("$c: never inside the back-off after a failure", c.backedOff)
            assertTrue(
                "$c: a transfer over the user's own connection needs an UNMETERED one — the " +
                    "CONTROLLER RULING; a delivered pack spends nothing and is exempt",
                c.unmetered || c.state == StreamingPackState.PackDelivered,
            )
            assertTrue(
                "$c: and a silent fetch is never a THIRD PARTY's bytes — the app's own asset " +
                    "pack (Play's fetch, or a delivered pack's local install) or nothing",
                c.state == StreamingPackState.PackFetchable ||
                    c.state == StreamingPackState.PackDelivered,
            )
        }
    }

    @Test fun aPicksFetchKeepsEveryRefusalAndDropsExactlyTheTwoThatProtectAnUnaskedOne() {
        // (4.5.0 Task 3b) THE PICK'S OWN necessary conditions. It is the same list as the
        // unasked one MINUS the connection and the back-off — the two inputs that exist because
        // nobody asked — and minus nothing else. A pick is a consent to spend DATA; it is not a
        // consent to undo a delete, override the switch, fetch for a language nobody picked, or
        // run 73 MB beside a live transcription.
        for (c in everyCell()) {
            if (c.starter != PreviewStarter.PICK) continue
            if (decide(c) != PreviewAutoFetch.Decision.FETCH) continue
            assertEquals("$c: still the SELECTED language's pack", c.lang.selected, c.lang.pack)
            assertFalse("$c: an installed pack is never re-fetched", c.state.isInstalled)
            assertFalse("$c: a damaged install is still the row's", c.state is StreamingPackState.Repair)
            assertFalse("$c: a delete or a dismissal is still never undone", c.userSaidNo)
            assertTrue("$c: the switch must still be on", c.showLiveWords)
            assertTrue(
                "$c: a device with no on-device speech model still gets nothing",
                c.localTierInstalled,
            )
            assertFalse("$c: still never during a session", c.sessionActive)
            assertFalse("$c: still never while a batch job runs", c.batchJobActive)
            assertFalse("$c: still never on top of work in flight", c.packWorkInFlight)
            assertFalse(
                "$c: AND STILL ONCE PER LAUNCH. This is the one guard a pick does NOT escape: " +
                    "it is decided in composition and performed by an effect keyed on that " +
                    "decision, so a latch-exempt pick whose transfer failed would return to " +
                    "FETCH the moment busy() cleared — for the life of the process",
                c.attemptedThisLaunch,
            )
            assertTrue(
                "$c: and a pick still never moves a THIRD PARTY's bytes — that consent is " +
                    "about WHO serves them, and the pick says nothing about Hugging Face",
                c.state == StreamingPackState.PackFetchable ||
                    c.state == StreamingPackState.PackDelivered,
            )
        }
    }

    @Test fun aPickDiffersFromAnUnaskedTopUpOnExactlyTheTwoInputsTheRulingNames() {
        // The asymmetry, characterised over the whole product instead of asserted cell by cell:
        // wherever the two starters disagree, the disagreement is the metered spend or the
        // back-off, and the pick's answer is never the more restrictive of the two. Anything
        // else would be a second rule riding on this one input.
        fun rank(d: PreviewAutoFetch.Decision): Int = when (d) {
            PreviewAutoFetch.Decision.NONE -> 0
            PreviewAutoFetch.Decision.OFFER -> 1
            PreviewAutoFetch.Decision.FETCH -> 2
        }
        var seenMetered = 0
        var seenBackOff = 0
        for (c in everyCell()) {
            if (c.starter != PreviewStarter.TOP_UP) continue
            val topUp = decide(c)
            val pick = decide(c.copy(starter = PreviewStarter.PICK))
            if (topUp == pick) continue
            val meteredSpend = c.state == StreamingPackState.PackFetchable && !c.unmetered
            assertTrue(
                "$c: the starter may only change the answer where the ruling says it does",
                meteredSpend || c.backedOff,
            )
            assertTrue(
                "$c: and a pick is never told LESS than an unasked top-up would be",
                rank(pick) >= rank(topUp),
            )
            if (meteredSpend) seenMetered++ else seenBackOff++
        }
        assertTrue("both halves of the asymmetry must be reachable", seenMetered > 0)
        assertTrue(seenBackOff > 0)
    }

    @Test fun everyRefusalIsAnAbsoluteSilenceAndNotMerelyADemotionToTheOffer() {
        // The difference that matters on a device: OFFER still renders a card with an action.
        // "The user said no" and "the feature is switched off" must produce no card at all.
        for (c in everyCell()) {
            val d = decide(c)
            // THE METERED TOP-UP IS DELIBERATELY NOT ON THIS LIST (4.5.0 Task 3 review r1, B1).
            // It answers OFFER — a card with a sized one-tap fetch — because the user's data is
            // the one condition a tap can answer, and that is the behaviour 4.4.1 shipped and the
            // owner validated on device as AF2. Ruling 3a keeps it by name; an earlier reading of
            // that ruling put it on this list, and the owner withdrew the instruction.
            if (c.lang.pack == null || c.lang.pack != c.lang.selected ||
                c.userSaidNo || !c.showLiveWords || !c.localTierInstalled ||
                c.sessionActive || c.batchJobActive || c.packWorkInFlight ||
                c.state.isInstalled || c.state is StreamingPackState.Repair
            ) {
                assertEquals("$c", PreviewAutoFetch.Decision.NONE, d)
            } else {
                assertTrue("$c: everything else is an offer or a fetch", d != PreviewAutoFetch.Decision.NONE)
            }
        }
    }

    // ------------------------------------------------------------------ the individual rules

    private fun open(
        lang: Lang = Lang("en", "en"),
        state: StreamingPackState = StreamingPackState.PackFetchable,
        userSaidNo: Boolean = false,
        showLiveWords: Boolean = true,
        localTierInstalled: Boolean = true,
        // The UNASKED path is the default, so every named cell below still states only its own
        // rule — and the cells that are about a pick say so.
        starter: PreviewStarter = PreviewStarter.TOP_UP,
        unmetered: Boolean = true,
        sessionActive: Boolean = false,
        batchJobActive: Boolean = false,
        packWorkInFlight: Boolean = false,
        attemptedThisLaunch: Boolean = false,
        backedOff: Boolean = false,
    ) = decide(
        Cell(
            lang, state, userSaidNo, showLiveWords, localTierInstalled, starter, unmetered,
            sessionActive, batchJobActive, packWorkInFlight, attemptedThisLaunch, backedOff,
        )
    )

    @Test fun autoFetchesNothingBecauseAutoArmsNothing() {
        // Owner ruling 2026-09-11: *"Now if they leave it in auto, then you get no live streaming
        // at all. And that will seem to be a very fair trade-off."* No selected language means no
        // pack to choose, so there is nothing to fetch and nothing to say — and the card is
        // silent too, because a card with no pack behind it has no offer to make.
        assertEquals(PreviewAutoFetch.Decision.NONE, open(lang = Lang("auto", null)))
        assertEquals(
            "not even with a pack sitting undelivered on the device",
            PreviewAutoFetch.Decision.NONE,
            open(lang = Lang("auto", null), state = StreamingPackState.PackDelivered),
        )
    }

    @Test fun aSelectedLanguageWithNoPackOfItsOwnIsNeverFetchedFor() {
        // Only English has a pack today, so this is every other language in the picker: no row in
        // the catalogue, nothing to fetch, and no card. The multilingual build adds rows; it does
        // not change this rule.
        assertEquals(PreviewAutoFetch.Decision.NONE, open(lang = Lang("zh", null)))
    }

    @Test fun anotherLanguagesPackIsNeverPushedToSomeoneWhoDidNotPickIt() {
        // The reason this input exists, in the amendment's own words: without it *"a user who
        // only ever dictates in Chinese gets 73 MB of English model pushed to their phone on
        // wifi — a download they can never use"*. Unreachable from the one call site, which
        // resolves the pack FROM the selection; refused here anyway, because a gate that trusts
        // its caller to have matched them is not a gate.
        assertEquals(PreviewAutoFetch.Decision.NONE, open(lang = Lang("zh", "en")))
        assertEquals(PreviewAutoFetch.Decision.NONE, open(lang = Lang("auto", "en")))
    }

    @Test fun aSecondLanguagesOwnPackFetchesOnItsOwnTerms() {
        // The machinery is not English-shaped: a row whose language the user has selected fetches
        // exactly as English does, which is what makes languages additive rather than a rewrite.
        assertEquals(PreviewAutoFetch.Decision.FETCH, open(lang = Lang("es", "es")))
    }

    @Test fun anInstalledPackIsNeverFetchedAgain() {
        assertEquals(PreviewAutoFetch.Decision.NONE, open(state = StreamingPackState.Installed))
    }

    @Test fun aDamagedInstallIsTheSettingsRowsToRepairNeverAnAutoFetch() {
        // The loop this closes: markCorrupt withdraws the verdict after a failed load, so a
        // device that keeps corrupting the install would re-fetch 73 MB on every single open.
        for (via in listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            assertEquals(
                "Repair(via=$via)",
                PreviewAutoFetch.Decision.NONE,
                open(state = StreamingPackState.Repair(via)),
            )
        }
    }

    @Test fun aDeletedModelStaysDeleted() {
        // AF3. The delete sets the flag; the flag is absolute and no auto-fetch undoes it.
        assertEquals(PreviewAutoFetch.Decision.NONE, open(userSaidNo = true))
        assertEquals(
            "not even on an unmetered connection with a delivered pack sitting on the device",
            PreviewAutoFetch.Decision.NONE,
            open(state = StreamingPackState.PackDelivered, userSaidNo = true),
        )
    }

    @Test fun theSwitchBeingOffSilencesItCompletely() {
        assertEquals(PreviewAutoFetch.Decision.NONE, open(showLiveWords = false))
    }

    @Test fun aCloudOnlySetupIsNeverSent73MbItCannotUse() {
        // No on-device speech model => nothing transcribes on this device at all (the session
        // dies at connect; `PreviewUnreachable`'s KDoc, and NOT `localPreviewArms`, which has no
        // tier term) => 73 MB buys nothing, whatever the language or the pack.
        assertEquals(PreviewAutoFetch.Decision.NONE, open(localTierInstalled = false))
    }

    /**
     * THE TIER AXIS, OVER THE WHOLE PRODUCT OF BOTH DECISIONS AT ONCE (4.5.0 Task 4).
     *
     * The task's deliverable is an enumeration of every sentence the feature can render against
     * {selection} × {packs installed} × {tier installed} × {cloud-only}, and two of its rows are
     * *absent by construction* rather than gated: `Card.OFFER` (`cardOffer` → the three per-source
     * subtitles, all of which carry `ADDITIVE`'s *"Words appear on the bubble as you talk"*) and
     * `Card.INSTALLED` (*"Live words are on"*). The first is unreachable because [decide] refuses
     * before it can answer OFFER; the second because `card` asks the tier on its own cell.
     *
     * Neither claim is visible to a test of either function alone: `card` will happily render
     * OFFER for a `decision` handed to it, and `decide`'s refusal is four lines above the branch
     * that would answer OFFER. So this walks the two TOGETHER, the way the one call site wires
     * them, over the full 43,008-cell product — because *"the reviewer's first job is to attack
     * the enumeration"*, and an ordering edit in [decide] (moving the `Downloadable` arm above
     * the tier refusal, say) is exactly the one-line change that would put an untrue offer back
     * on a cloud-only phone with nothing failing.
     */
    @Test fun aDeviceWithNoTierCanOnlyEverSeeSilenceOrItsOwnTransfer() {
        var reachedWorking = false
        var reachedNone = false
        for (c in everyCell().filter { !it.localTierInstalled }) {
            val decision = decide(c)
            assertEquals(
                "no tier means no spend, in every cell: $c",
                PreviewAutoFetch.Decision.NONE,
                decision,
            )
            for (armed in bools) for (busy in bools) {
                val answer = PreviewAutoFetch.card(
                    // The call site's own derivation: the pack is resolved FROM the selection,
                    // so "has a pack for the selection" is the two agreeing.
                    hasPackForSelection = c.lang.pack != null && c.lang.pack == c.lang.selected,
                    installed = c.state.isInstalled,
                    previewHasArmed = armed,
                    localTierInstalled = false,
                    userSaidNo = c.userSaidNo,
                    showLiveWords = c.showLiveWords,
                    workInFlight = busy,
                    decision = decision,
                )
                assertTrue(
                    "a phone that can never show a word may see only silence or the transfer " +
                        "it started itself — never the offer's ADDITIVE promise and never " +
                        "\"Live words are on\": armed=$armed inFlight=$busy $c gave $answer",
                    answer == PreviewAutoFetch.Card.NONE ||
                        answer == PreviewAutoFetch.Card.WORKING,
                )
                assertTrue(
                    "and the only WORKING card is one the USER's own tap put in flight — the " +
                        "decision can never produce one here: $c",
                    answer != PreviewAutoFetch.Card.WORKING || busy,
                )
                if (answer == PreviewAutoFetch.Card.WORKING) reachedWorking = true
                if (answer == PreviewAutoFetch.Card.NONE) reachedNone = true
            }
        }
        // Both halves reachable, so neither assertion above is vacuous: the WORKING one is the
        // cell `cardWorking`'s tier term exists for.
        assertTrue("the working card must be reachable with no tier", reachedWorking)
        assertTrue(reachedNone)
    }

    @Test fun neverDuringASessionAndNeverWhileABatchJobRuns() {
        assertEquals(PreviewAutoFetch.Decision.NONE, open(sessionActive = true))
        assertEquals(PreviewAutoFetch.Decision.NONE, open(batchJobActive = true))
    }

    @Test fun workAlreadyInFlightIsNeverDuplicated() {
        assertEquals(PreviewAutoFetch.Decision.NONE, open(packWorkInFlight = true))
    }

    @Test fun anUnmeteredConnectionFetchesSilently() {
        // AF1, the ruling's own case: the app's own asset pack, from Google Play.
        assertEquals(PreviewAutoFetch.Decision.FETCH, open(state = StreamingPackState.PackFetchable))
    }

    @Test fun theThirdPartyDownloadIsNeverSilentOnAnyConnection() {
        // The fallback route's bytes come from the catalog's commit-pinned base, not from the
        // app's own asset pack — and settingsInstallFetch promises the user "never from a third
        // party", while installDownload() is the one sentence that admits one. A silent
        // fetch is exactly the case where that sentence is never read. So it OFFERS, on wifi as
        // well as on cellular, and the tap is the consent (review r1, B1).
        assertEquals(
            "on an unmetered connection too",
            PreviewAutoFetch.Decision.OFFER,
            open(state = StreamingPackState.Downloadable, unmetered = true),
        )
        assertEquals(
            PreviewAutoFetch.Decision.OFFER,
            open(state = StreamingPackState.Downloadable, unmetered = false),
        )
    }

    @Test fun aMeteredConnectionOffersInsteadOfSpendingTheUsersData() {
        // AF2 — the CONTROLLER RULING. Nothing moves until the card is tapped.
        //
        // (4.5.0 ruling 3a) UNCHANGED, and unchangeable without an owner ruling: *"the unasked
        // foreground top-up KEEPS EXACTLY WHAT 4.4.1 SHIPPED. Do not touch it."* The owner's
        // *"skip the Wi-Fi"* was about the SELECTION path (the cell below), and he validated this
        // row on device.
        assertEquals(
            PreviewAutoFetch.Decision.OFFER,
            open(state = StreamingPackState.PackFetchable, unmetered = false),
        )
        assertEquals(
            "and the third-party route offers on EVERY connection, because its consent is about " +
                "WHO serves the bytes rather than what they cost",
            PreviewAutoFetch.Decision.OFFER,
            open(state = StreamingPackState.Downloadable, unmetered = false),
        )
    }

    @Test fun aPickDownloadsOnAMeteredConnectionBecauseThePickIsTheConsent() {
        // (4.5.0 Task 3b) The other half of the ruling, and the reason 3a's silence is principled
        // rather than a feature being withheld: *"And if you select a different language, then
        // automatically download and set up the language pack for that language automatically …
        // No one's gonna care about sixty more megabytes."*
        assertEquals(
            PreviewAutoFetch.Decision.FETCH,
            open(
                state = StreamingPackState.PackFetchable,
                starter = PreviewStarter.PICK,
                unmetered = false,
            ),
        )
    }

    @Test fun aPickIsNotSilencedByTheBackOffAnUnaskedFailureWrote() {
        // The back-off's own KDoc scopes it to *"a failed AUTO-fetch"* and to the across-launch
        // loop of a user reopening the app all afternoon. A pick is not that loop: it is a new
        // gesture, and answering it with the offer card would be the app remembering a failure
        // the user has not seen and cannot act on.
        assertEquals(
            PreviewAutoFetch.Decision.OFFER,
            open(backedOff = true),
        )
        assertEquals(
            PreviewAutoFetch.Decision.FETCH,
            open(backedOff = true, starter = PreviewStarter.PICK),
        )
    }

    @Test fun aPickIsStillBoundByTheOncePerLaunchLatchSoAFailedPickCannotLoop() {
        // The one guard both starters share, and the reason it must: the pick is decided in
        // composition and performed by an effect keyed on that decision, so an exempt pick whose
        // transfer failed would go straight back to FETCH the instant busy() cleared — once per
        // failure, for the life of the process. The offer card's tap is the way back, and a TAP
        // never reaches this decision at all.
        for (starter in PreviewStarter.entries) {
            assertEquals(
                "$starter",
                PreviewAutoFetch.Decision.OFFER,
                open(attemptedThisLaunch = true, starter = starter),
            )
        }
    }

    @Test fun noConditionAnywhereInTheProductTakesTheMeteredOfferAWAY() {
        // THE OWNER-VALIDATED STATE, pinned over the whole product rather than one cell, because
        // it has already been deleted once (4.5.0 Task 3 review r1, B1): the branch went, four
        // KDoc blocks were rewritten to assert the silence, and AF2's own acceptance row was
        // renamed to assert its negation. So this walks every cell where a metered connection is
        // THE ONLY thing between an unasked top-up and a fetch, and requires the card — never a
        // silence. A cell answering NONE here is 4.4.1's validated behaviour gone again.
        var seen = 0
        for (c in everyCell()) {
            if (c.state != StreamingPackState.PackFetchable || c.unmetered) continue
            if (c.starter != PreviewStarter.TOP_UP) continue
            // Every OTHER refusal open: those are absolute and are their own tests.
            if (c.lang.pack == null || c.lang.pack != c.lang.selected) continue
            if (c.userSaidNo || !c.showLiveWords || !c.localTierInstalled) continue
            if (c.sessionActive || c.batchJobActive || c.packWorkInFlight) continue
            assertEquals(
                "$c: the metered unasked top-up is a CARD WITH A TAP, and ruling 3a keeps it",
                PreviewAutoFetch.Decision.OFFER,
                decide(c),
            )
            seen++
        }
        assertTrue("the claim must be reachable at all", seen > 0)
    }

    @Test fun aDeliveredPackInstallsOnAnyConnectionBecauseItSpendsNone() {
        // Play has the bytes on the device already: installFromPack is a verify + local copy with
        // no network at any point, so there is nothing for the metering rule to protect.
        assertEquals(
            PreviewAutoFetch.Decision.FETCH,
            open(state = StreamingPackState.PackDelivered, unmetered = false),
        )
    }

    @Test fun oncePerLaunchAtMostAndTheSecondLookIsAnOfferNotASilence() {
        assertEquals(PreviewAutoFetch.Decision.OFFER, open(attemptedThisLaunch = true))
        assertEquals(
            "and the offer is still the offer for a delivered pack",
            PreviewAutoFetch.Decision.OFFER,
            open(state = StreamingPackState.PackDelivered, attemptedThisLaunch = true),
        )
    }

    @Test fun afterAFailureItBacksOffButTheTapStaysAvailable() {
        assertEquals(PreviewAutoFetch.Decision.OFFER, open(backedOff = true))
    }

    // ------------------------------------------------------------------ the back-off window

    @Test fun theBackOffWindowIsADay() {
        assertEquals(86_400_000L, PreviewAutoFetch.BACK_OFF_MS)
    }

    @Test fun noStoredFailureIsNeverABackOff() {
        // 0 is the pref's default, and it must not read as "failed at the epoch".
        assertFalse(PreviewAutoFetch.backedOff(lastFailureAtMs = 0L, nowMs = 0L))
        assertFalse(PreviewAutoFetch.backedOff(lastFailureAtMs = 0L, nowMs = 1_800_000_000_000L))
        assertFalse("a negative stamp is not a failure either", PreviewAutoFetch.backedOff(-1L, 1_000L))
    }

    @Test fun aFailureSilencesTheAutoFetchForExactlyOneDay() {
        val at = 1_800_000_000_000L
        assertTrue(PreviewAutoFetch.backedOff(at, at))
        assertTrue(PreviewAutoFetch.backedOff(at, at + 1L))
        assertTrue(PreviewAutoFetch.backedOff(at, at + PreviewAutoFetch.BACK_OFF_MS - 1L))
        assertFalse(
            "the window is half-open: a day later the auto-fetch is allowed again",
            PreviewAutoFetch.backedOff(at, at + PreviewAutoFetch.BACK_OFF_MS),
        )
        assertFalse(PreviewAutoFetch.backedOff(at, at + PreviewAutoFetch.BACK_OFF_MS + 1L))
    }

    @Test fun aStampFromTheFutureEndsTheBackOffRatherThanExtendingIt() {
        // A clock moved back, or a backup restored onto a device with an earlier clock. Treating
        // a future stamp as "inside the window" would silence the feature until the clock caught
        // up — potentially years. The cost of the other choice is one extra attempt.
        val at = 1_800_000_000_000L
        assertFalse(PreviewAutoFetch.backedOff(at, at - 1L))
        assertFalse(PreviewAutoFetch.backedOff(at, at - PreviewAutoFetch.BACK_OFF_MS * 365L))
    }

    // ------------------------------------------------------------------ the card's own mapping

    /** [open]'s twin for the card: everything open, so a named cell states only its own rule. */
    private fun card(
        hasPackForSelection: Boolean = true,
        installed: Boolean = false,
        previewHasArmed: Boolean = false,
        localTierInstalled: Boolean = true,
        userSaidNo: Boolean = false,
        showLiveWords: Boolean = true,
        workInFlight: Boolean = false,
        decision: PreviewAutoFetch.Decision = PreviewAutoFetch.Decision.NONE,
    ) = PreviewAutoFetch.card(
        hasPackForSelection, installed, previewHasArmed, localTierInstalled, userSaidNo,
        showLiveWords, workInFlight, decision,
    )

    @Test fun theCardMappingIsTotalOverItsEightInputs() {
        var cells = 0
        for (has in bools) for (installed in bools) for (armed in bools) for (tier in bools) {
            for (no in bools) for (sw in bools) for (busy in bools) {
                for (d in PreviewAutoFetch.Decision.entries) {
                    cells++
                    val expected = when {
                        !has -> PreviewAutoFetch.Card.NONE
                        no -> PreviewAutoFetch.Card.NONE
                        !sw -> PreviewAutoFetch.Card.NONE
                        installed ->
                            if (armed || !tier) PreviewAutoFetch.Card.NONE
                            else PreviewAutoFetch.Card.INSTALLED
                        busy || d == PreviewAutoFetch.Decision.FETCH ->
                            PreviewAutoFetch.Card.WORKING
                        d == PreviewAutoFetch.Decision.OFFER -> PreviewAutoFetch.Card.OFFER
                        else -> PreviewAutoFetch.Card.NONE
                    }
                    assertEquals(
                        "hasPack=$has installed=$installed armed=$armed tier=$tier saidNo=$no " +
                            "switch=$sw inFlight=$busy decision=$d",
                        expected,
                        // Positionally, and deliberately: the helper above has defaults, and a
                        // default is a value this walk must supply rather than inherit.
                        PreviewAutoFetch.card(has, installed, armed, tier, no, sw, busy, d),
                    )
                }
            }
        }
        assertEquals("the full product of the card's inputs", 384, cells)
    }

    @Test fun theAnnouncementIsSilentForAUserWhosePreviewerCanNeverArm() {
        // (4.4.1 pass 3, ITEM 3 — review r1's nit 2.) "Live words are on" is false, permanently,
        // for a user with the pack and NO on-device whisper tier: nothing transcribes on their
        // device at all (the session dies at connect — `PreviewUnreachable`'s KDoc, not the
        // previewer's gate, which has no tier term), and nothing will ever write
        // `livePreviewArmedOnce`, because the write is `onOpen`'s and that session never opens —
        // so `previewHasArmed` could not retire it either (4.5.0 T4 fix round 1). `decide`
        // already reads this input and would never have FETCHED the pack for them, but the pack
        // can be there anyway: the Settings row installs on demand, and a 4.4.0 user may have had
        // it before they went cloud-only.
        assertEquals(
            "the model is installed and the flag is unwritten — and nothing can ever write it " +
                "here, because the write is `onOpen`'s and this session never opens",
            PreviewAutoFetch.Card.NONE,
            card(installed = true, previewHasArmed = false, localTierInstalled = false),
        )
        assertEquals(
            "and with a tier, the same cell is the announcement it always was",
            PreviewAutoFetch.Card.INSTALLED,
            card(installed = true, previewHasArmed = false, localTierInstalled = true),
        )
        // It silences the ANNOUNCEMENT and nothing else. `workInFlight` spans the Settings row's
        // own fetch, so a cloud-only user who taps "Get the English preview model" and returns to
        // Home is watching a transfer THEY started: hiding its progress would hide their own
        // action from them, and the X would be the only thing left to press.
        assertEquals(
            "a transfer they started still narrates itself",
            PreviewAutoFetch.Card.WORKING,
            card(localTierInstalled = false, workInFlight = true),
        )
        // ...and it can never invent a card either: `decide` answers NONE without a tier, so the
        // OFFER and the silent FETCH are already out of reach for them.
        assertEquals(
            PreviewAutoFetch.Card.NONE,
            card(localTierInstalled = false, decision = PreviewAutoFetch.Decision.NONE),
        )
        assertEquals(
            "the no is still the no",
            PreviewAutoFetch.Card.NONE,
            card(installed = true, localTierInstalled = false, userSaidNo = true),
        )
    }

    @Test fun aLanguageWithNoPackSaysNothingEvenWhileAnotherLanguagesFetchIsRunning() {
        // The hole this input closes, and it is REACHABLE: a user on Auto opens Settings, taps
        // "Get the English preview model", and returns to Home while it transfers. `workInFlight`
        // is true (busy() spans both starters, deliberately), `installed` is false and the
        // decision is NONE — so without this input the card answered WORKING and rendered "The
        // Auto-detect preview model is arriving now… Auto-detect shows them, and Auto-detect
        // shows none at all", which is false twice over and contradicts itself.
        //
        // A card is about ONE language's pack. With no pack for the selection there is no true
        // sentence to spell, so the answer is silence — as absolutely as a dismissal.
        for (installed in bools) for (armed in bools) for (busy in bools) {
            for (d in PreviewAutoFetch.Decision.entries) {
                assertEquals(
                    "installed=$installed armed=$armed inFlight=$busy decision=$d",
                    PreviewAutoFetch.Card.NONE,
                    card(
                        hasPackForSelection = false,
                        installed = installed,
                        previewHasArmed = armed,
                        workInFlight = busy,
                        decision = d,
                    ),
                )
            }
        }
    }

    @Test fun theAnnouncementRetiresItselfOnceTheUserHasSeenLiveWordsWithTheirOwnEyes() {
        // CONTROLLER RULING 2026-09-11, CHANGE 4 (round-1 nits 1/2). The brief asked for a
        // ONE-TIME announcement — "then it stops appearing" — and that was true only via the X,
        // which is also the permanent no. So a 4.4.0 user who already had the pack had to choose
        // between being told about live words on every open and declining the feature forever.
        assertEquals(
            "the first time, with the model installed and the previewer never yet armed",
            PreviewAutoFetch.Card.INSTALLED,
            card(installed = true, previewHasArmed = false),
        )
        assertEquals(
            "and never again once the previewer has actually armed for a session: at that point " +
                "the user has watched the words appear, and announcing them is noise",
            PreviewAutoFetch.Card.NONE,
            card(installed = true, previewHasArmed = true),
        )
    }

    @Test fun havingSeenLiveWordsSilencesTheAnnouncementAndNothingElse() {
        // It is NOT a "no" — a user who has seen live words and then deletes the model must still
        // be offered it back by the Settings row, and a fetch they start must still narrate
        // itself. Only the announcement is retired, so the flag may only ever be read under
        // `installed`.
        assertEquals(
            PreviewAutoFetch.Card.OFFER,
            card(previewHasArmed = true, decision = PreviewAutoFetch.Decision.OFFER),
        )
        assertEquals(
            PreviewAutoFetch.Card.WORKING,
            card(previewHasArmed = true, decision = PreviewAutoFetch.Decision.FETCH),
        )
        assertEquals(
            PreviewAutoFetch.Card.WORKING,
            card(previewHasArmed = true, workInFlight = true),
        )
    }

    @Test fun theSwitchBeingOffSaysNothingRatherThanSomethingUntrue() {
        // Review r1, B2: the reachable case is an INSTALLED pack with "Show live words" off — a
        // 4.4.0 user who installed from the Settings row and turned the switch off, upgrading;
        // or a 4.4.1 user whose pack auto-fetched and who then turned it off. Neither wrote
        // userSaidNo (the switch is not the X and not the delete), and "Live words are on" is
        // flatly untrue for both. Every other state is false there too, so the card is silent.
        for (installed in bools) for (armed in bools) for (busy in bools) {
            for (d in PreviewAutoFetch.Decision.entries) {
                assertEquals(
                    "installed=$installed armed=$armed inFlight=$busy decision=$d",
                    PreviewAutoFetch.Card.NONE,
                    card(
                        installed = installed,
                        previewHasArmed = armed,
                        showLiveWords = false,
                        workInFlight = busy,
                        decision = d,
                    ),
                )
            }
        }
    }

    @Test fun aDismissedCardStaysGoneThroughEverythingElse() {
        // AF4, and the reason userSaidNo outranks the work in flight as well as the decision: a
        // user who dismissed this card and then installed the model from the Settings row must
        // not have it reappear as a progress card.
        for (installed in bools) for (armed in bools) for (busy in bools) {
            for (d in PreviewAutoFetch.Decision.entries) {
                assertEquals(
                    "installed=$installed armed=$armed inFlight=$busy decision=$d",
                    PreviewAutoFetch.Card.NONE,
                    card(
                        installed = installed,
                        previewHasArmed = armed,
                        userSaidNo = true,
                        workInFlight = busy,
                        decision = d,
                    ),
                )
            }
        }
    }

    @Test fun aLandedInstallOutranksAnyStaleWorkLine() {
        assertEquals(
            "a stale 'arriving…' over a working model is a lie the user cannot dismiss",
            PreviewAutoFetch.Card.INSTALLED,
            card(installed = true, workInFlight = true),
        )
    }

    @Test fun theCardIsWorkingTheMomentTheHookDecidesToFetch() {
        // AF1's "the card appears, the model fetches with no taps": the card must not wait for
        // the shell to publish its first state, or the fetch would begin invisibly.
        assertEquals(
            PreviewAutoFetch.Card.WORKING,
            card(decision = PreviewAutoFetch.Decision.FETCH),
        )
    }

    @Test fun theOfferCardIsShownForTheOfferDecisionAndNothingElse() {
        assertEquals(
            PreviewAutoFetch.Card.OFFER,
            card(decision = PreviewAutoFetch.Decision.OFFER),
        )
        assertEquals(
            PreviewAutoFetch.Card.NONE,
            card(decision = PreviewAutoFetch.Decision.NONE),
        )
    }

    // ------------------------------------------------------------------ the acceptance rows

    @Test fun af1_wifiOpenFetchesWithNoTaps() {
        val d = open(state = StreamingPackState.PackFetchable, unmetered = true)
        assertEquals(PreviewAutoFetch.Decision.FETCH, d)
        assertEquals(PreviewAutoFetch.Card.WORKING, card(decision = d))
    }

    @Test fun af2b_cellularPickDownloadsAtOnceAndTheCardSaysSo() {
        // The acceptance row ruling 3b adds beside AF2, and the pair is the whole asymmetry: same
        // cellular connection, same missing pack, and the answer turns on who asked.
        val d = open(
            state = StreamingPackState.PackFetchable,
            starter = PreviewStarter.PICK,
            unmetered = false,
        )
        assertEquals(PreviewAutoFetch.Decision.FETCH, d)
        assertEquals(PreviewAutoFetch.Card.WORKING, card(decision = d))
    }

    @Test fun af2_cellularOpenShowsTheOfferAndMovesNothing() {
        // THE ROW THE OWNER SIGNED OFF ON DEVICE, 2026-09-11 — *"it works, everything you told
        // me works exactly like you said"*. Ruling 3a keeps it word for word, and AF2b below is
        // the pick's own row beside it: same cellular connection, same missing pack, and the
        // answer turns on who asked.
        val d = open(state = StreamingPackState.PackFetchable, unmetered = false)
        assertEquals(PreviewAutoFetch.Decision.OFFER, d)
        assertEquals(PreviewAutoFetch.Card.OFFER, card(decision = d))
    }

    @Test fun af3_aDeletedModelDoesNotComeBackAndSaysNothing() {
        val d = open(state = StreamingPackState.PackFetchable, userSaidNo = true)
        assertEquals(PreviewAutoFetch.Decision.NONE, d)
        assertEquals(
            PreviewAutoFetch.Card.NONE,
            card(userSaidNo = true, decision = d),
        )
    }

    @Test fun af4_aDismissedCardStaysGoneAndTheModelStaysInstallable() {
        val d = open(userSaidNo = true)
        assertEquals(PreviewAutoFetch.Decision.NONE, d)
        assertEquals(
            PreviewAutoFetch.Card.NONE,
            card(userSaidNo = true, decision = d),
        )
        // ...and the Settings row is untouched by all of this: its own action reads the state
        // machine, not this decision. The pin for that is LivePreviewRowsPinTest, unchanged.
        assertEquals(
            "the state machine still offers the install the manual path acts on",
            StreamingPackState.PackFetchable,
            StreamingPackInstall.sourceOf(StreamingPackState.PackFetchable),
        )
    }

    @Test fun theInstalledCardAnnouncesItselfOnceAndThenTheDismissalEndsIt() {
        assertEquals(PreviewAutoFetch.Card.INSTALLED, card(installed = true))
        assertEquals(
            "its X sets the declined flag, which is what makes it one-time AND permanent",
            PreviewAutoFetch.Card.NONE,
            card(installed = true, userSaidNo = true),
        )
        assertEquals(
            "and the previewer arming for one real session ends it WITHOUT declining anything " +
                "(CONTROLLER RULING 2026-09-11, CHANGE 4) — the X is no longer the only way out",
            PreviewAutoFetch.Card.NONE,
            card(installed = true, previewHasArmed = true),
        )
    }
}
