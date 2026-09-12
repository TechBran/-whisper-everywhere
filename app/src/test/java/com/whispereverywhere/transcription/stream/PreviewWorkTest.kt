package com.whispereverywhere.transcription.stream

import com.whispereverywhere.npu.NpuPackFetch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * THE ONE OBSERVABLE, exhaustively pinned (4.5.0 Task 1).
 *
 * The defect three review rounds established: "is work running?" was answered from TWO
 * composition-local values — the Settings row's own `previewInstallStatus` and
 * `StreamingPackController.state` — while THREE starters can begin a 73 MB transfer. Home
 * collected one of them, Settings collected the other, and every blocker of rounds 1-3 was a
 * different consequence of that gap. So the answer is now ONE record per pack, and this test is
 * the whole of its contract: the mapping from the Play machine, the route reduction, the cancel
 * table, the in-flight predicate, the per-language keying, and the delete row's five cases.
 */
class PreviewWorkTest {

    private val everyFetchState = listOf(
        NpuPackFetch.FetchState.Idle,
        NpuPackFetch.FetchState.Pending,
        NpuPackFetch.FetchState.Downloading(1_000_000L, 72_654_782L),
        NpuPackFetch.FetchState.Transferring,
        NpuPackFetch.FetchState.Verifying(0L, 72_654_782L),
        NpuPackFetch.FetchState.Installed,
        NpuPackFetch.FetchState.Failed("the shell's own re-told refusal."),
        NpuPackFetch.FetchState.Cancelled,
        NpuPackFetch.FetchState.NeedsConfirmation,
    )

    private val everyState = listOf(
        StreamingPackState.Installed,
        StreamingPackState.PackDelivered,
        StreamingPackState.PackFetchable,
        StreamingPackState.Downloadable,
        StreamingPackState.Repair(StreamingPackState.PackDelivered),
        StreamingPackState.Repair(StreamingPackState.PackFetchable),
        StreamingPackState.Repair(StreamingPackState.Downloadable),
    )

    /** The board is process-scoped, like the two controllers that write it. */
    @Before fun clean() = PreviewWorkboard.forgetAll()

    @After fun tidy() = PreviewWorkboard.forgetAll()

    // ------------------------------------------------- the Play machine, as a step of this board

    @Test fun everyStateOfThePlayMachineBecomesExactlyOneStepAndNoneFallsThrough() {
        assertEquals(
            "NOT_INSTALLED for a pack we are actively fetching means Play no longer holds a " +
                "fetch for it: nothing installed, no failure named — the same outcome as its own " +
                "CANCELED status, which is how a cancel actually arrives",
            PreviewPhase.CANCELLED,
            PreviewStep.of(NpuPackFetch.FetchState.Idle).phase,
        )
        assertEquals(PreviewPhase.ASKING, PreviewStep.of(NpuPackFetch.FetchState.Pending).phase)
        assertEquals(
            PreviewPhase.DOWNLOADING,
            PreviewStep.of(NpuPackFetch.FetchState.Downloading(1L, 2L)).phase,
        )
        assertEquals(
            PreviewPhase.TRANSFERRING,
            PreviewStep.of(NpuPackFetch.FetchState.Transferring).phase,
        )
        assertEquals(
            "COMPLETED is a DELIVERY, and NpuPackFetch.advance maps it to Verifying — which is " +
                "the start of OUR verify + copy, the one phase no route can cancel",
            PreviewPhase.INSTALLING,
            PreviewStep.of(NpuPackFetch.FetchState.Verifying(0L, 2L)).phase,
        )
        assertEquals(
            PreviewPhase.INSTALLED,
            PreviewStep.of(NpuPackFetch.FetchState.Installed).phase,
        )
        assertEquals(
            PreviewPhase.FAILED,
            PreviewStep.of(NpuPackFetch.FetchState.Failed("x")).phase,
        )
        assertEquals(
            PreviewPhase.CANCELLED,
            PreviewStep.of(NpuPackFetch.FetchState.Cancelled).phase,
        )
        assertEquals(
            "Play's own dialog (a size or cellular confirmation, or a wait for wifi) is a phase " +
                "where NO byte has moved yet — the row must be able to say so",
            PreviewPhase.AWAITING_ANSWER,
            PreviewStep.of(NpuPackFetch.FetchState.NeedsConfirmation).phase,
        )
    }

    @Test fun theBytesTravelWithTheStepAndOnlyAFailureCarriesAReason() {
        val downloading = PreviewStep.of(NpuPackFetch.FetchState.Downloading(1_000_000L, 72_654_782L))
        assertEquals(1_000_000L, downloading.soFar)
        assertEquals(72_654_782L, downloading.total)
        val verifying = PreviewStep.of(NpuPackFetch.FetchState.Verifying(4L, 8L))
        assertEquals(4L, verifying.soFar)
        assertEquals(8L, verifying.total)
        assertEquals(
            "a Failed carries the shell's own re-told refusal VERBATIM — the shell has already " +
                "put this feature's words on it, and a second re-wording would be a copy of a copy",
            "the shell's own re-told refusal.",
            PreviewStep.of(NpuPackFetch.FetchState.Failed("the shell's own re-told refusal.")).reason,
        )
        for (state in everyFetchState.filterNot { it is NpuPackFetch.FetchState.Failed }) {
            assertNull("$state names no reason", PreviewStep.of(state).reason)
        }
        for (state in everyFetchState.filterNot {
            it is NpuPackFetch.FetchState.Downloading || it is NpuPackFetch.FetchState.Verifying
        }) {
            val step = PreviewStep.of(state)
            assertEquals("$state invents no byte count", 0L, step.soFar)
            assertEquals("$state invents no denominator", 0L, step.total)
        }
    }

    // ------------------------------------------------------------------ the route

    @Test fun theRouteIsTheSourceTheInstallWouldTakeAndARepairTakesItsOwn() {
        assertNull(
            "an installed pack is on no route: there is nothing arriving to observe",
            PreviewRoute.of(StreamingPackState.Installed),
        )
        assertEquals(PreviewRoute.DELIVERED_PACK, PreviewRoute.of(StreamingPackState.PackDelivered))
        assertEquals(PreviewRoute.PLAY_FETCH, PreviewRoute.of(StreamingPackState.PackFetchable))
        assertEquals(PreviewRoute.DIRECT_DOWNLOAD, PreviewRoute.of(StreamingPackState.Downloadable))
        assertEquals(
            "a repair takes the route a FIRST install would have taken — the one reduction, " +
                "StreamingPackInstall.sourceOf, and never a second when() over the machine",
            listOf(
                PreviewRoute.DELIVERED_PACK,
                PreviewRoute.PLAY_FETCH,
                PreviewRoute.DIRECT_DOWNLOAD,
            ),
            listOf(
                PreviewRoute.of(StreamingPackState.Repair(StreamingPackState.PackDelivered)),
                PreviewRoute.of(StreamingPackState.Repair(StreamingPackState.PackFetchable)),
                PreviewRoute.of(StreamingPackState.Repair(StreamingPackState.Downloadable)),
            ),
        )
        for (state in everyState) {
            val route = PreviewRoute.of(state)
            assertTrue(
                "$state must be answered, not wildcarded: a route that fell through would be " +
                    "observed under another route's cancel contract",
                state.isInstalled == (route == null),
            )
        }
    }

    // ------------------------------------------------------------------ ONE cancel, ONE meaning

    @Test fun theCancelTableIsTotalOverTheRoutesAndTheCopyIsTheOneNobodyCanStop() {
        assertTrue(
            "PLAY_FETCH: AssetPackManager.cancel stops Play's own download, and the shell " +
                "publishes Cancelled",
            PreviewRoute.PLAY_FETCH.stopsBeforeTheCopy,
        )
        assertTrue(
            "DIRECT_DOWNLOAD: the poll loop's delay sees the cancel within one poll AND the " +
                "DownloadManager row is removed, so the bytes really stop (4.5.0 — 4.4.1's " +
                "`keepRow = true` left them moving after the X)",
            PreviewRoute.DIRECT_DOWNLOAD.stopsBeforeTheCopy,
        )
        assertFalse(
            "DELIVERED_PACK: there is no transfer to stop. Play has already put those bytes on " +
                "the device and the route's only phase is the local verify + copy",
            PreviewRoute.DELIVERED_PACK.stopsBeforeTheCopy,
        )
    }

    @Test fun theCopyPhaseIsCancellableOnNoRouteAndNothingTerminalIsEither() {
        for (route in PreviewRoute.entries) {
            assertFalse(
                "$route's verify + copy has no suspension point between " +
                    "withContext(Dispatchers.IO)'s entry and its return, so a cancel during it " +
                    "lets the marker land — the UI must not offer one",
                work(route, PreviewPhase.INSTALLING).cancellable,
            )
            assertFalse(
                "$route at TRANSFERRING: the bytes are already ON THE DEVICE and Play is moving " +
                    "them into its own storage, so AssetPackManager.cancel has no download left " +
                    "to cancel — DELIVERED_PACK's own reason, arriving on another route (fix " +
                    "round 1, review r1's B1a)",
                work(route, PreviewPhase.TRANSFERRING).cancellable,
            )
            assertFalse(
                "$route at ABANDONED: the cancel has already been accepted and the store has " +
                    "not answered it — there is nothing left for a second press to ask for " +
                    "(fix round 2, review r2's B1a)",
                work(route, PreviewPhase.ABANDONED).cancellable,
            )
            for (phase in listOf(
                PreviewPhase.INSTALLED,
                PreviewPhase.FAILED,
                PreviewPhase.CANCELLED,
            )) {
                assertFalse(
                    "$route at $phase is over: there is nothing in flight to cancel",
                    work(route, phase).cancellable,
                )
            }
        }
    }

    @Test fun everyArrivalNotYetDeliveredIsCancellableOnTheTwoRoutesThatCanStop() {
        // TRANSFERRING is NOT one of them — see theCopyPhaseIsCancellableOnNoRouteAndNothingTerminalIsEither.
        // ASKING and AWAITING_ANSWER ARE, and they are why the rule is "not yet delivered" and
        // not "the bytes are moving": no byte has moved at either, and Play still holds a fetch
        // it can drop (fix round 2, review r2's nit 1).
        val notYetDelivered = listOf(
            PreviewPhase.ASKING,
            PreviewPhase.AWAITING_ANSWER,
            PreviewPhase.DOWNLOADING,
        )
        for (phase in notYetDelivered) {
            assertTrue(
                "PLAY_FETCH at $phase",
                work(PreviewRoute.PLAY_FETCH, phase).cancellable,
            )
            assertTrue(
                "DIRECT_DOWNLOAD at $phase",
                work(PreviewRoute.DIRECT_DOWNLOAD, phase).cancellable,
            )
            assertFalse(
                "DELIVERED_PACK at $phase — unreachable, and answered anyway, because a route " +
                    "that fell through the table would be offered a cancel it cannot honour",
                work(PreviewRoute.DELIVERED_PACK, phase).cancellable,
            )
        }
    }

    /**
     * THE TABLE CANNOT ANSWER THE SAME FACT TWO WAYS (fix round 1, review r1's B1a).
     *
     * `PreviewRoute.DELIVERED_PACK.stopsBeforeTheCopy` is false for one stated reason — *"Play
     * has already put those bytes on the device"* — and `TRANSFERRING` is that same fact on the
     * PLAY_FETCH route: Play's `STATUS_TRANSFERRING`, after the download and before `COMPLETED`,
     * a phase every delivery passes through. Until this round the table said `true` there, so
     * `AssetPackManager.cancel` was offered with no download left to cancel and the brief's
     * *"make the BEHAVIOUR match the table"* was discharged against nothing.
     *
     * The CRITERION is *"this arrival has not yet delivered its bytes"*, not *"the bytes are
     * still moving"* (fix round 2, review r2's nit 1): the second is false at `ASKING` and
     * `AWAITING_ANSWER`, which are cancellable, and a rule the file does not follow is how the
     * table came to answer one fact two ways in the first place.
     *
     * Stated as a grid so the classification is TOTAL: every phase, on every route, is either a
     * cancel the route can honour or a stated refusal.
     */
    @Test fun onlyTheArrivalsNotYetDeliveredAreEverCancellableAnywhere() {
        val notYetDelivered = setOf(
            PreviewPhase.ASKING,
            PreviewPhase.AWAITING_ANSWER,
            PreviewPhase.DOWNLOADING,
        )
        for (route in PreviewRoute.entries) {
            for (phase in PreviewPhase.entries) {
                assertEquals(
                    "$route at $phase: a cancel is offered exactly where the arrival has not " +
                        "yet delivered its bytes AND the route can stop it",
                    phase in notYetDelivered && route.stopsBeforeTheCopy,
                    work(route, phase).cancellable,
                )
            }
        }
        assertEquals(
            "and the in-flight phases the record refuses on EVERY route are exactly three: " +
                "TRANSFERRING (Play's move, bytes already on the device), INSTALLING (ours) and " +
                "ABANDONED (a cancel already accepted, waiting on the store)",
            setOf(
                PreviewPhase.TRANSFERRING,
                PreviewPhase.INSTALLING,
                PreviewPhase.ABANDONED,
            ),
            PreviewPhase.entries
                .filter { it.inFlight && it !in notYetDelivered }
                .toSet(),
        )
    }

    /**
     * THE CONTROL IS ENABLED BY THE RECORD OR IT IS NOT OFFERED (fix round 2, review r2's B1c).
     *
     * Home's X is ONE gesture with TWO halves — the permanent no
     * (`PreferencesManager.setLivePreviewDeclined`) and the abandon — and only the second is
     * refusable. Fix round 1 made `TRANSFERRING` and `INSTALLING` uncancellable on every route
     * and left the X live there, so pressing it wrote the declined flag, the card vanished
     * (`card`'s `userSaidNo` outranks `workInFlight`), the install landed anyway and
     * `localPreviewArms` — which has no declined term — armed live words for the user who had
     * just refused them. *Installed AND declined*, from the only control on the screen.
     *
     * So `dismissable` is the answer both the X and this test read: no work, finished work, or
     * work that can still be stopped.
     */
    @Test fun theDismissIsOfferedExactlyWhereTheAbandonHalfOfItWouldHappen() {
        for (route in PreviewRoute.entries) {
            for (phase in PreviewPhase.entries) {
                val record = work(route, phase)
                assertEquals(
                    "$route at $phase: the X may be offered only where the gesture's abandon " +
                        "half would actually happen — or where there is nothing in flight for " +
                        "the no to contradict",
                    !record.inFlight || record.cancellable,
                    record.dismissable,
                )
            }
        }
        assertFalse(
            "the two phases fix round 1 moved OUT of cancellable are exactly where the X used " +
                "to invert the user's answer: INSTALLING is a streamed sha256 of 72,654,782 B " +
                "plus a copy, a window of many seconds and not a race",
            work(PreviewRoute.PLAY_FETCH, PreviewPhase.INSTALLING).dismissable,
        )
        assertFalse(
            "TRANSFERRING is the other one: the bytes are on the device and Play is moving them " +
                "into its own storage",
            work(PreviewRoute.PLAY_FETCH, PreviewPhase.TRANSFERRING).dismissable,
        )
        assertFalse(
            "a cancel already accepted has nothing left to accept",
            work(PreviewRoute.PLAY_FETCH, PreviewPhase.ABANDONED).dismissable,
        )
        assertFalse(
            "and a DELIVERED pack's local copy is unstoppable on its own route, so the X goes " +
                "there too — the declined flag it wrote was the half that survived",
            work(PreviewRoute.DELIVERED_PACK, PreviewPhase.INSTALLING).dismissable,
        )
        assertTrue(
            "work that CAN still be stopped keeps its X, because there both halves of the " +
                "gesture take effect",
            work(PreviewRoute.PLAY_FETCH, PreviewPhase.DOWNLOADING).dismissable,
        )
        for (phase in listOf(
            PreviewPhase.INSTALLED,
            PreviewPhase.FAILED,
            PreviewPhase.CANCELLED,
        )) {
            assertTrue(
                "$phase is over, so the X is a plain dismiss again — which is what lets the " +
                    "announcement that follows an unstoppable install carry a working no",
                work(PreviewRoute.DELIVERED_PACK, phase).dismissable,
            )
        }
    }

    /**
     * THE ABANDON IS IN FLIGHT, AND NOTHING OF OURS CAN STILL LAND UNDER IT (fix round 2, review
     * r2's B1a) — the one phase where those two answers differ, which is why the delete row asks
     * `writeCanStillLand` and the single-flight guard asks `inFlight`.
     */
    @Test fun theAbandonedPhaseIsBusyButNoLongerWriting() {
        assertTrue(
            "Play may still be delivering, so no second transfer may start over it — and that " +
                "term is what StreamingPackController.isBusy() now reads from this board " +
                "instead of from a private field of its own",
            PreviewPhase.ABANDONED.inFlight,
        )
        assertFalse(
            "but the install that would follow a delivery is cancelled and a delivery that " +
                "lands anyway is suppressed, so a delete races nothing",
            work(PreviewRoute.PLAY_FETCH, PreviewPhase.ABANDONED).writeCanStillLand,
        )
        assertEquals(
            "ABANDONED is the ONLY phase where the two answers differ: everywhere else 'work " +
                "is running' and 'a write can still land' are the same fact",
            setOf(PreviewPhase.ABANDONED),
            PreviewPhase.entries
                .filter { phase ->
                    phase.inFlight != work(PreviewRoute.PLAY_FETCH, phase).writeCanStillLand
                }
                .toSet(),
        )
        assertEquals(
            "so the delete row promises the bytes back there rather than claiming the model is " +
                "'being written right now', which would be false",
            PreviewDeleteCase.DAMAGED,
            PreviewDeleteCase.of(
                StreamingPackState.Repair(StreamingPackState.PackFetchable),
                selectedForThisPack = true,
                showLiveWords = true,
                localTierInstalled = true,
                work = work(PreviewRoute.PLAY_FETCH, PreviewPhase.ABANDONED),
            ),
        )
    }

    @Test fun inFlightIsEveryTransportPhaseAndNoTerminalOne() {
        for (phase in listOf(
            PreviewPhase.ASKING,
            PreviewPhase.AWAITING_ANSWER,
            PreviewPhase.DOWNLOADING,
            PreviewPhase.TRANSFERRING,
            PreviewPhase.INSTALLING,
            // The user's no, not yet answered by Play: work is NOT over, because a second
            // transfer must not start over a delivery Play may still be making (fix round 2).
            PreviewPhase.ABANDONED,
        )) {
            assertTrue("$phase is work running", phase.inFlight)
        }
        for (phase in listOf(
            PreviewPhase.INSTALLED,
            PreviewPhase.FAILED,
            PreviewPhase.CANCELLED,
        )) {
            assertFalse("$phase is work finished", phase.inFlight)
        }
        assertEquals(
            "every phase is classified: an unclassified one would read as running forever, " +
                "which is the state that hides the offer AND the delete",
            PreviewPhase.entries.size,
            PreviewPhase.entries.count { it.inFlight } + PreviewPhase.entries.count { !it.inFlight },
        )
    }

    // ------------------------------------------------------------------ the board is PER PACK

    @Test fun twoLanguagesAreObservedSeparatelyAndNeitherHidesTheOther() {
        PreviewWorkboard.begin("en", PreviewRoute.PLAY_FETCH, PreviewStarter.TOP_UP, PreviewStep(PreviewPhase.ASKING))
        PreviewWorkboard.begin(
            "de",
            PreviewRoute.DIRECT_DOWNLOAD,
            PreviewStarter.PICK,
            PreviewStep(PreviewPhase.DOWNLOADING, soFar = 5L, total = 71_000_000L),
        )
        assertEquals(setOf("en", "de"), PreviewWorkboard.work.value.keys)
        assertEquals(PreviewRoute.PLAY_FETCH, PreviewWorkboard.of("en")?.route)
        assertEquals(PreviewRoute.DIRECT_DOWNLOAD, PreviewWorkboard.of("de")?.route)
        assertEquals(PreviewStarter.TOP_UP, PreviewWorkboard.of("en")?.starter)
        assertEquals(PreviewStarter.PICK, PreviewWorkboard.of("de")?.starter)
        // The whole point of the map: one language reaching its terminal phase leaves the other
        // exactly where it was. With one `job` and one `line` the second arrival was invisible.
        PreviewWorkboard.note("en", PreviewStep(PreviewPhase.INSTALLED))
        assertFalse(PreviewWorkboard.inFlight("en"))
        assertTrue(PreviewWorkboard.inFlight("de"))
        assertEquals(5L, PreviewWorkboard.of("de")?.soFar)
        assertTrue(PreviewWorkboard.anyInFlight())
        PreviewWorkboard.note("de", PreviewStep(PreviewPhase.CANCELLED))
        assertFalse("and with both finished, nothing is running", PreviewWorkboard.anyInFlight())
    }

    @Test fun theStarterAndTheRouteSurviveEveryPhaseChange() {
        // "who started it — an unasked top-up or a user's pick" has to still be answerable at the
        // phase the user is looking at, or the copy cannot tell a silent transfer from a chosen one.
        PreviewWorkboard.begin("en", PreviewRoute.PLAY_FETCH, PreviewStarter.TOP_UP, PreviewStep(PreviewPhase.ASKING))
        for (phase in listOf(
            PreviewPhase.AWAITING_ANSWER,
            PreviewPhase.DOWNLOADING,
            PreviewPhase.TRANSFERRING,
            PreviewPhase.INSTALLING,
            PreviewPhase.INSTALLED,
        )) {
            PreviewWorkboard.note("en", PreviewStep(phase))
            assertEquals("at $phase", PreviewStarter.TOP_UP, PreviewWorkboard.of("en")?.starter)
            assertEquals("at $phase", PreviewRoute.PLAY_FETCH, PreviewWorkboard.of("en")?.route)
            assertEquals("at $phase", "en", PreviewWorkboard.of("en")?.language)
        }
    }

    @Test fun aStepForALanguageNobodyStartedWritesNothing() {
        PreviewWorkboard.note("fr", PreviewStep(PreviewPhase.DOWNLOADING, 1L, 2L))
        assertTrue(
            "a phase for work no starter began is a row from nowhere — the board is written by " +
                "whoever STARTED the work, and only then narrated",
            PreviewWorkboard.work.value.isEmpty(),
        )
        assertNull(PreviewWorkboard.of("fr"))
        assertFalse(PreviewWorkboard.inFlight("fr"))
        assertNull("and a null language is not a key", PreviewWorkboard.of(null))
    }

    @Test fun aTerminalEntryStaysReadableAndForgetTakesOnlyItsOwnLanguage() {
        PreviewWorkboard.begin("en", PreviewRoute.PLAY_FETCH, PreviewStarter.PICK, PreviewStep(PreviewPhase.ASKING))
        PreviewWorkboard.begin("de", PreviewRoute.PLAY_FETCH, PreviewStarter.PICK, PreviewStep(PreviewPhase.ASKING))
        PreviewWorkboard.note("en", PreviewStep(PreviewPhase.FAILED, reason = "Google Play lost track of this download. Retry it."))
        assertEquals(
            "the refusal stays on the board, because the row that has to show it is rendered " +
                "from the board and a Toast the user has walked away from shows nothing",
            "Google Play lost track of this download. Retry it.",
            PreviewWorkboard.of("en")?.reason,
        )
        PreviewWorkboard.forget("en")
        assertNull(PreviewWorkboard.of("en"))
        assertEquals(setOf("de"), PreviewWorkboard.work.value.keys)
    }

    @Test fun retireDropsATerminalRecordAndNEVERARunningOne() {
        // (fix round 1, review r1's B2) "Kept until then" had no THEN: `forget` had zero
        // production callers, so a terminal record was kept forever — and ruling 3c's strip is
        // the first surface to render a PRESENT-TENSE promise off one. `retire` is the door the
        // two gestures that contradict a record use (the delete, and the permanent no).
        for (phase in PreviewPhase.entries) {
            PreviewWorkboard.forgetAll()
            PreviewWorkboard.begin(
                "en", PreviewRoute.PLAY_FETCH, PreviewStarter.PICK, PreviewStep(phase),
            )
            PreviewWorkboard.retire("en")
            if (phase.inFlight) {
                assertEquals(
                    "$phase: a RUNNING record is never dropped. `busy()` reads this board, so " +
                        "dropping one would tell the actuator and both surfaces that nothing is " +
                        "happening while Play is still delivering — review r2's B1a, through a " +
                        "new door",
                    phase, PreviewWorkboard.of("en")?.phase,
                )
            } else {
                assertNull("$phase: the receipt goes, because it has been contradicted", PreviewWorkboard.of("en"))
            }
        }
    }

    @Test fun retireTakesOnlyItsOwnLanguageAndAnUnknownOneIsHarmless() {
        PreviewWorkboard.begin("en", PreviewRoute.PLAY_FETCH, PreviewStarter.PICK, PreviewStep(PreviewPhase.INSTALLED))
        PreviewWorkboard.begin("de", PreviewRoute.PLAY_FETCH, PreviewStarter.PICK, PreviewStep(PreviewPhase.INSTALLED))
        PreviewWorkboard.retire("fr")
        assertEquals(
            "a language with no record is not an error: the delete row and the X are per " +
                "language and either can be pressed with nothing on the board",
            setOf("en", "de"), PreviewWorkboard.work.value.keys,
        )
        PreviewWorkboard.retire("en")
        assertEquals(
            "deleting English's model says nothing about German's — the board is keyed by " +
                "language for exactly this reason",
            setOf("de"), PreviewWorkboard.work.value.keys,
        )
    }

    // ------------------------------------------------------------------ the delete row's cases

    @Test fun thereIsNoDeleteRowWhereThereAreNoBytes() {
        for (state in listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            for (selected in listOf(true, false)) {
                for (switch in listOf(true, false)) {
                    // (4.5.0 Task 4) ...and with or without an on-device tier: no bytes on disk
                    // is no row, whatever this device could do with them.
                    for (tier in listOf(true, false)) {
                        assertNull(
                            "$state has nothing under filesDir to free, so the row promises nothing",
                            PreviewDeleteCase.of(state, selected, switch, tier, null),
                        )
                        assertNull(
                            "and a FIRST install in flight is still nothing to free",
                            PreviewDeleteCase.of(
                                state,
                                selected,
                                switch,
                                tier,
                                work(PreviewRoute.PLAY_FETCH, PreviewPhase.DOWNLOADING),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test fun aWriteInFlightOutranksEveryOtherDeleteCase() {
        // Review r3's H3-B1. `previewState` is remembered on keys our own install does not change,
        // so through a repair install it stays Repair — and the delete row rendered beside the
        // running copy, where `delete` clears the install dir under a verify + copy that is not
        // cancellation-cooperative. The copy lands anyway: "Frees 73 MB" frees nothing and the
        // pack ends up installed AND declined. The work answer comes FIRST, whatever the state.
        val writing = work(PreviewRoute.DELIVERED_PACK, PreviewPhase.INSTALLING)
        for (state in listOf(
            StreamingPackState.Installed,
            StreamingPackState.Repair(StreamingPackState.PackDelivered),
            StreamingPackState.Repair(StreamingPackState.PackFetchable),
            StreamingPackState.Repair(StreamingPackState.Downloadable),
        )) {
            for (selected in listOf(true, false)) {
                for (switch in listOf(true, false)) {
                    // (4.5.0 Task 4) THE WRITE OUTRANKS THE DEVICE AXIS TOO. Reachable: a fetch
                    // authorised while a tier was installed, and the tier deleted from this same
                    // screen while the 73 MB is still moving. *"Nothing to free yet"* is the true
                    // sentence there and the one with no tap, which is what makes it the top of
                    // the order rather than the most specific.
                    for (tier in listOf(true, false)) {
                        assertEquals(
                            "$state, selected=$selected, switch=$switch, tier=$tier",
                            PreviewDeleteCase.WORKING,
                            PreviewDeleteCase.of(state, selected, switch, tier, writing),
                        )
                    }
                }
            }
        }
        assertEquals(
            "and a FINISHED entry is not a write: the row comes back the moment the bytes settle",
            PreviewDeleteCase.LIVE,
            PreviewDeleteCase.of(
                StreamingPackState.Installed,
                true,
                true,
                true,
                work(PreviewRoute.DELIVERED_PACK, PreviewPhase.INSTALLED),
            ),
        )
        assertEquals(
            "nor is an ABANDONED entry, which is in flight but writing nothing (fix round 2): " +
                "the install that would follow a delivery is cancelled, so the row can promise " +
                "the bytes back instead of claiming a write that is not happening",
            PreviewDeleteCase.LIVE,
            PreviewDeleteCase.of(
                StreamingPackState.Installed,
                true,
                true,
                true,
                work(PreviewRoute.PLAY_FETCH, PreviewPhase.ABANDONED),
            ),
        )
    }

    /**
     * THE SIX CASES, over {installed} x {selected} x {switch} x {tier} — the grid review r1's B2
     * showed no test could reach (the switch was not an input to anything under test), plus the
     * DEVICE axis Task 1 left open by name.
     *
     * The two cells that shipped untrue:
     *  - *"Show live words"* OFF, English selected, the pack installed. `of` answered LIVE and
     *    the delete row — immediately under the OFF switch — read *"Frees 73 MB. Live words
     *    stop"*. Nothing stopped. (Fixed in Task 1's fix round 1.)
     *  - **no on-device speech model**, English selected, the switch on, the pack installed.
     *    `of` answered LIVE and the row read the same sentence, on a device where nothing
     *    transcribes at all and no word has ever appeared — the session dies at connect, which is
     *    the mechanism `PreviewUnreachable`'s KDoc states and NOT the previewer's gate, which has
     *    no tier term. (Task 4, corrected in its fix round 1.)
     */
    @Test fun theSixCasesAreTheSixFactsTheRowCanBeLookingAt() {
        assertEquals(
            "installed, the picked language's pack, the switch on, AND a tier this session " +
                "could run on: live words are showing today, so deleting really does stop them",
            PreviewDeleteCase.LIVE,
            PreviewDeleteCase.of(StreamingPackState.Installed, true, true, true, null),
        )
        assertEquals(
            "THE CELL THAT SHIPPED UNTRUE (review r1's B2): the switch this same section draws " +
                "one row above is OFF, so nothing stops — no device, tier or connection " +
                "requirement, and one tap away on the default surface",
            PreviewDeleteCase.OFF_SWITCH,
            PreviewDeleteCase.of(StreamingPackState.Installed, true, false, true, null),
        )
        assertEquals(
            "installed for a language the user is NOT transcribing (they picked another, or " +
                "Auto): the bytes buy nothing today, so 'Live words stop' is false",
            PreviewDeleteCase.OFF_SELECTION,
            PreviewDeleteCase.of(StreamingPackState.Installed, false, true, true, null),
        )
        assertEquals(
            "and with BOTH off the switch is named, not the selection: both sentences are true " +
                "there, and the switch is the one the user can see and the one tap that would " +
                "change the answer — the same precedence PreviewAutoFetch.card takes",
            PreviewDeleteCase.OFF_SWITCH,
            PreviewDeleteCase.of(StreamingPackState.Installed, false, false, true, null),
        )
        assertEquals(
            "THE SECOND CELL THAT SHIPPED UNTRUE (4.5.0 Task 4): everything this screen can see " +
                "is armable and no word can ever appear, because nothing transcribes on this " +
                "device at all",
            PreviewDeleteCase.OFF_TIER,
            PreviewDeleteCase.of(StreamingPackState.Installed, true, true, false, null),
        )
        for (via in listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            for (selected in listOf(true, false)) {
                for (switch in listOf(true, false)) {
                    assertEquals(
                        "markCorrupt removes the marker and LEAVES the bytes, so a Repair is up " +
                            "to 73 MB with live words already off — whatever the selection and " +
                            "the switch are, and the repair is the more actionable fact",
                        PreviewDeleteCase.DAMAGED,
                        PreviewDeleteCase.of(
                            StreamingPackState.Repair(via), selected, switch, true, null,
                        ),
                    )
                }
            }
        }
        assertEquals(
            "and the six cases are six, so a seventh fact cannot arrive without a sentence",
            6,
            PreviewDeleteCase.entries.size,
        )
        // No cell of the grid is unanswered, and only the one that is actually armable is LIVE.
        for (selected in listOf(true, false)) {
            for (switch in listOf(true, false)) {
                for (tier in listOf(true, false)) {
                    assertEquals(
                        "installed, selected=$selected, switch=$switch, tier=$tier: LIVE is the " +
                            "ONE cell where 'Live words stop' is a true consequence",
                        selected && switch && tier,
                        PreviewDeleteCase.of(
                            StreamingPackState.Installed, selected, switch, tier, null,
                        ) == PreviewDeleteCase.LIVE,
                    )
                }
            }
        }
    }

    /**
     * THE PRECEDENCE THE DEVICE AXIS TAKES, and why it is not merely "most specific wins".
     *
     * `OFF_SWITCH`, `OFF_SELECTION` and `DAMAGED` are all still TRUE with no tier — words are
     * off, and deleting stops nothing — so this order decides only which REASON is named. Each of
     * those three names a fact whose remedy is a control on THIS screen (the switch one row up,
     * the picker on Home, the repair row above), and on this device none of those three gestures
     * would change the answer. `DAMAGED` loses its rank by its own stated reason: it outranks the
     * switch because *"the repair is the more actionable fact"*, and `LivePreviewRows` withdraws
     * the whole offer/repair branch on a device that can never arm, so there is nothing more
     * actionable about it.
     *
     * **A fact no gesture can change outranks a fact a gesture would** — `PreviewUnreachable`'s
     * rule, which is the same rule fix round 1 ran the OTHER way when it put the switch above the
     * selection.
     */
    @Test fun theDeviceThatCanNeverArmOutranksEveryReasonAScreenControlWouldChange() {
        for (state in listOf(
            StreamingPackState.Installed,
            StreamingPackState.Repair(StreamingPackState.PackDelivered),
            StreamingPackState.Repair(StreamingPackState.PackFetchable),
            StreamingPackState.Repair(StreamingPackState.Downloadable),
        )) {
            for (selected in listOf(true, false)) {
                for (switch in listOf(true, false)) {
                    assertEquals(
                        "$state, selected=$selected, switch=$switch, no tier",
                        PreviewDeleteCase.OFF_TIER,
                        PreviewDeleteCase.of(state, selected, switch, false, null),
                    )
                }
            }
        }
        // ...and it is not the top of the order: a write that can still land outranks it, because
        // *"Frees 73 MB"* is the claim that would be false there and the row has no tap.
        assertEquals(
            PreviewDeleteCase.WORKING,
            PreviewDeleteCase.of(
                StreamingPackState.Installed,
                true,
                true,
                false,
                work(PreviewRoute.DELIVERED_PACK, PreviewPhase.INSTALLING),
            ),
        )
        // The tier is the ONLY input that changes this row's case on every one of the four
        // {selected} x {switch} cells at once — which is what makes it an axis rather than a
        // fifth reason on the same list.
        assertEquals(
            setOf(PreviewDeleteCase.OFF_TIER),
            buildSet {
                for (selected in listOf(true, false)) {
                    for (switch in listOf(true, false)) {
                        add(
                            PreviewDeleteCase.of(
                                StreamingPackState.Installed, selected, switch, false, null,
                            ),
                        )
                    }
                }
            },
        )
    }

    // ------------------------------------------------- the CAUSE, and its two answers (Task 3b)

    @Test fun theThreeCausesMapToTwoStartersAndTwoLatchAnswersAndTheTableIsTotal() {
        // (4.5.0 Task 3b) `auto: Boolean` could carry one of these two questions, not both — and
        // the row it could not express is the SELECTION, which skips the unasked path's two
        // cautions like a tap does (the user just made this gesture) and is latched like a top-up
        // is (it is decided in composition, so an exempt pick whose transfer failed would retry
        // itself for the life of the process). Three causes, two answers each, and both mappings
        // live on the enum rather than at a call site, so no surface can spell either one
        // differently.
        //
        // The two cautions are the wait for a network that WORKS and the 24 h back-off after a
        // failure — NOT a metering test, which the owner deleted from this feature in pass 2's
        // Fix 1: *"Yes. I wanted to silently download on cellular and Wi Fi."* Until that sweep
        // reached this comment it said the selection spent the user's metered bytes the way a tap
        // does, which was the middle of three rulings and is not the live one.
        assertEquals(PreviewStarter.TOP_UP, PreviewTrigger.TOP_UP.starter)
        assertEquals(PreviewStarter.PICK, PreviewTrigger.SELECTION.starter)
        assertEquals(PreviewStarter.PICK, PreviewTrigger.TAP.starter)
        assertTrue(PreviewTrigger.TOP_UP.latchedForTheLaunch)
        assertTrue(
            "the row `auto: Boolean` could not express: latched like a top-up, because a pick is " +
                "re-decided by every recomposition",
            PreviewTrigger.SELECTION.latchedForTheLaunch,
        )
        assertFalse(
            "and a tap is exempt: consent may be repeated, and a latched tap would answer a " +
                "user's retry with the same offer card they just pressed",
            PreviewTrigger.TAP.latchedForTheLaunch,
        )
        assertEquals(
            "three causes, so a fourth cannot arrive without answering both questions",
            3,
            PreviewTrigger.entries.size,
        )
        assertEquals(
            "exactly one cause is unasked, and it is the only one the two cautions apply to — " +
                "the wait for a working network and the 24 h back-off, never a metering test",
            listOf(PreviewTrigger.TOP_UP),
            PreviewTrigger.entries.filter { it.starter == PreviewStarter.TOP_UP },
        )
        assertEquals(
            "and exactly one is a finger on a control, which is the only latch-exempt one",
            listOf(PreviewTrigger.TAP),
            PreviewTrigger.entries.filterNot { it.latchedForTheLaunch },
        )
    }

    // ------------------------------------------------------------------ helper

    private fun work(route: PreviewRoute, phase: PreviewPhase): PreviewWork = PreviewWork(
        language = "en",
        route = route,
        starter = PreviewStarter.PICK,
        step = PreviewStep(phase),
    )
}
