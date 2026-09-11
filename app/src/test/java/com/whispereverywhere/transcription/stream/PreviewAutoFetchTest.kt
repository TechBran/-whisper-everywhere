package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 4.4.1 auto-fetch decision and the card's state mapping, pinned EXHAUSTIVELY — every cell of
 * installed-state × user-said-no × switch × local tier × metered × session × batch × in-flight ×
 * tried-this-launch × backed-off, and every cell of the card's own five inputs.
 *
 * The cross product is walked in full (7 × 2^9 = 3,584 cells for the decision, 2^4 × 3 = 48 for
 * the card) and each cell is checked against the rules stated INDEPENDENTLY as a flat
 * conjunction — not against a copy of the implementation's `if` ladder, which would pass for any
 * ordering of it. What that catches is precisely the bug an example-based test cannot: a refusal
 * answered in the wrong order (a metered check reached before the "the user said no" check, say,
 * so a deleted model comes back on wifi), and a state added to `StreamingPackState` later that
 * falls through into a silent 73 MB transfer.
 *
 * Then the four acceptance rows are named cells of their own (AF1–AF4), because a sheet row a
 * human will run on a device deserves to be readable as one assertion.
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

    /** One cell of the cross product, named so a failure says which of the 3,584 broke. */
    private data class Cell(
        val state: StreamingPackState,
        val userSaidNo: Boolean,
        val showLiveWords: Boolean,
        val localTierInstalled: Boolean,
        val unmetered: Boolean,
        val sessionActive: Boolean,
        val batchJobActive: Boolean,
        val packWorkInFlight: Boolean,
        val attemptedThisLaunch: Boolean,
        val backedOff: Boolean,
    )

    private fun everyCell(): List<Cell> = buildList {
        for (state in everyState) {
            for (no in bools) for (sw in bools) for (tier in bools) for (un in bools) {
                for (sess in bools) for (batch in bools) for (busy in bools) {
                    for (tried in bools) for (back in bools) {
                        add(Cell(state, no, sw, tier, un, sess, batch, busy, tried, back))
                    }
                }
            }
        }
    }

    private fun decide(c: Cell): PreviewAutoFetch.Decision = PreviewAutoFetch.decide(
        state = c.state,
        userSaidNo = c.userSaidNo,
        showLiveWords = c.showLiveWords,
        localTierInstalled = c.localTierInstalled,
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
        assertEquals("the walk must be the full cross product", 7 * 512, cells.size)
        for (c in cells) {
            // The rules, spelled independently of the implementation's ordering.
            val nothingToDo = c.state.isInstalled || c.state is StreamingPackState.Repair
            val refused = c.userSaidNo || !c.showLiveWords || !c.localTierInstalled
            val notNow = c.sessionActive || c.batchJobActive || c.packWorkInFlight
            // A third party's bytes are never moved silently, whatever the network reads.
            val thirdParty = c.state == StreamingPackState.Downloadable
            val spendsData = c.state == StreamingPackState.PackFetchable
            val expected = when {
                nothingToDo || refused || notNow -> PreviewAutoFetch.Decision.NONE
                thirdParty -> PreviewAutoFetch.Decision.OFFER
                spendsData && !c.unmetered -> PreviewAutoFetch.Decision.OFFER
                c.attemptedThisLaunch || c.backedOff -> PreviewAutoFetch.Decision.OFFER
                else -> PreviewAutoFetch.Decision.FETCH
            }
            assertEquals("$c", expected, decide(c))
        }
    }

    @Test fun aSilentFetchIsOnlyEverReachedWithEveryConditionOpenAtOnce() {
        // The same walk read the other way round: the NECESSARY conditions for spending a user's
        // resources without asking. Every one of these has its own named cell below; this is the
        // claim that no combination anywhere in the product sneaks past all of them.
        for (c in everyCell()) {
            if (decide(c) != PreviewAutoFetch.Decision.FETCH) continue
            assertFalse("$c: an installed pack is never re-fetched", c.state.isInstalled)
            assertFalse("$c: a damaged install is never silently re-fetched", c.state is StreamingPackState.Repair)
            assertFalse("$c: a delete or a dismissal is never undone", c.userSaidNo)
            assertTrue("$c: the switch must be on", c.showLiveWords)
            assertTrue("$c: a cloud-only setup can never arm the previewer", c.localTierInstalled)
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

    @Test fun everyRefusalIsAnAbsoluteSilenceAndNotMerelyADemotionToTheOffer() {
        // The difference that matters on a device: OFFER still renders a card with an action.
        // "The user said no" and "the feature is switched off" must produce no card at all.
        for (c in everyCell()) {
            val d = decide(c)
            if (c.userSaidNo || !c.showLiveWords || !c.localTierInstalled ||
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
        state: StreamingPackState = StreamingPackState.PackFetchable,
        userSaidNo: Boolean = false,
        showLiveWords: Boolean = true,
        localTierInstalled: Boolean = true,
        unmetered: Boolean = true,
        sessionActive: Boolean = false,
        batchJobActive: Boolean = false,
        packWorkInFlight: Boolean = false,
        attemptedThisLaunch: Boolean = false,
        backedOff: Boolean = false,
    ) = decide(
        Cell(
            state, userSaidNo, showLiveWords, localTierInstalled, unmetered,
            sessionActive, batchJobActive, packWorkInFlight, attemptedThisLaunch, backedOff,
        )
    )

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
        // No on-device tier => every session is a cloud session => localPreviewArms can never
        // arm, whatever the language or the pack.
        assertEquals(PreviewAutoFetch.Decision.NONE, open(localTierInstalled = false))
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
        // app's own asset pack — and SETTINGS_INSTALL_FETCH promises the user "never from a third
        // party", while SETTINGS_INSTALL_DOWNLOAD is the one sentence that admits one. A silent
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
        assertEquals(
            PreviewAutoFetch.Decision.OFFER,
            open(state = StreamingPackState.PackFetchable, unmetered = false),
        )
        assertEquals(
            PreviewAutoFetch.Decision.OFFER,
            open(state = StreamingPackState.Downloadable, unmetered = false),
        )
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
        installed: Boolean = false,
        previewHasArmed: Boolean = false,
        userSaidNo: Boolean = false,
        showLiveWords: Boolean = true,
        workInFlight: Boolean = false,
        decision: PreviewAutoFetch.Decision = PreviewAutoFetch.Decision.NONE,
    ) = PreviewAutoFetch.card(
        installed, previewHasArmed, userSaidNo, showLiveWords, workInFlight, decision,
    )

    @Test fun theCardMappingIsTotalOverItsSixInputs() {
        var cells = 0
        for (installed in bools) for (armed in bools) for (no in bools) for (sw in bools) {
            for (busy in bools) for (d in PreviewAutoFetch.Decision.entries) {
                cells++
                val expected = when {
                    no -> PreviewAutoFetch.Card.NONE
                    !sw -> PreviewAutoFetch.Card.NONE
                    installed ->
                        if (armed) PreviewAutoFetch.Card.NONE else PreviewAutoFetch.Card.INSTALLED
                    busy || d == PreviewAutoFetch.Decision.FETCH -> PreviewAutoFetch.Card.WORKING
                    d == PreviewAutoFetch.Decision.OFFER -> PreviewAutoFetch.Card.OFFER
                    else -> PreviewAutoFetch.Card.NONE
                }
                assertEquals(
                    "installed=$installed armed=$armed saidNo=$no switch=$sw inFlight=$busy " +
                        "decision=$d",
                    expected,
                    // Positionally, and deliberately: the helper above has defaults, and a
                    // default is a value this walk must supply rather than inherit.
                    PreviewAutoFetch.card(installed, armed, no, sw, busy, d),
                )
            }
        }
        assertEquals("the full product of the card's inputs", 96, cells)
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

    @Test fun af2_cellularOpenShowsTheOfferAndMovesNothing() {
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
