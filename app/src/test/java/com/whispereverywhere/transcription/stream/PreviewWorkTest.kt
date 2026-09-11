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
 * table, the in-flight predicate, the per-language keying, and the delete row's four cases.
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

    @Test fun everyPhaseBeforeTheCopyIsCancellableOnTheTwoRoutesThatCanStop() {
        val beforeTheCopy = listOf(
            PreviewPhase.ASKING,
            PreviewPhase.AWAITING_ANSWER,
            PreviewPhase.DOWNLOADING,
            PreviewPhase.TRANSFERRING,
        )
        for (phase in beforeTheCopy) {
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

    @Test fun inFlightIsEveryTransportPhaseAndNoTerminalOne() {
        for (phase in listOf(
            PreviewPhase.ASKING,
            PreviewPhase.AWAITING_ANSWER,
            PreviewPhase.DOWNLOADING,
            PreviewPhase.TRANSFERRING,
            PreviewPhase.INSTALLING,
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

    // ------------------------------------------------------------------ the delete row's cases

    @Test fun thereIsNoDeleteRowWhereThereAreNoBytes() {
        for (state in listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            for (selected in listOf(true, false)) {
                assertNull(
                    "$state has nothing under filesDir to free, so the row promises nothing",
                    PreviewDeleteCase.of(state, selected, null),
                )
                assertNull(
                    "and a FIRST install in flight is still nothing to free",
                    PreviewDeleteCase.of(
                        state,
                        selected,
                        work(PreviewRoute.PLAY_FETCH, PreviewPhase.DOWNLOADING),
                    ),
                )
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
                assertEquals(
                    "$state, selected=$selected",
                    PreviewDeleteCase.WORKING,
                    PreviewDeleteCase.of(state, selected, writing),
                )
            }
        }
        assertEquals(
            "and a FINISHED entry is not a write: the row comes back the moment the bytes settle",
            PreviewDeleteCase.LIVE,
            PreviewDeleteCase.of(
                StreamingPackState.Installed,
                true,
                work(PreviewRoute.DELIVERED_PACK, PreviewPhase.INSTALLED),
            ),
        )
    }

    @Test fun theFourCasesAreTheFourFactsTheRowCanBeLookingAt() {
        assertEquals(
            "installed, and it is the picked language's pack: live words are showing today",
            PreviewDeleteCase.LIVE,
            PreviewDeleteCase.of(StreamingPackState.Installed, true, null),
        )
        assertEquals(
            "installed for a language the user is NOT transcribing (they picked another, or " +
                "Auto): the bytes buy nothing today, so 'Live words stop' is false",
            PreviewDeleteCase.OFF_SELECTION,
            PreviewDeleteCase.of(StreamingPackState.Installed, false, null),
        )
        for (via in listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            for (selected in listOf(true, false)) {
                assertEquals(
                    "markCorrupt removes the marker and LEAVES the bytes, so a Repair is up to " +
                        "73 MB with live words already off — whatever the selection is",
                    PreviewDeleteCase.DAMAGED,
                    PreviewDeleteCase.of(StreamingPackState.Repair(via), selected, null),
                )
            }
        }
        assertEquals(
            "and the four cases are four, so a fifth fact cannot arrive without a sentence",
            4,
            PreviewDeleteCase.entries.size,
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
