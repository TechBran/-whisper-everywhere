package com.whispereverywhere.transcription.stream

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * THE PICK REGISTER (4.5.0 Task 3b) — the process-scoped record of *"the user chose this
 * language while this process was running"*, which is the one fact
 * `PreferencesManager.selectedLanguage` can never report.
 *
 * Small enough to read in one screen and load-bearing enough to spend a 73 MB cellular transfer,
 * so every property it has is asserted here rather than argued in its KDoc. (Fix 1 narrowed what
 * it buys — there is no metered test left for it to gate — but it did not retire it: the unasked
 * path still waits for a working network and still defers to the back-off.)
 */
class PreviewPicksTest {

    @Before fun clean() = PreviewPicks.forgetAll()

    @After fun tidy() = PreviewPicks.forgetAll()

    @Test fun nothingIsPickedInAFreshProcess() {
        // The default is what decides a cold start, and it must be "nobody asked": the language
        // already in the preference at launch is a standing SELECTION and not a gesture this
        // process watched happen. An empty register means TOP_UP — which since Fix 1 fetches on
        // any network that works, and differs from a pick only in waiting for one at all and in
        // deferring to the 24 h back-off.
        assertEquals(emptySet<String>(), PreviewPicks.picked.value)
        assertFalse(PreviewPicks.wasPicked("en"))
        assertFalse(PreviewPicks.wasPicked("auto"))
    }

    @Test fun aNotedLanguageIsPickedAndNoOtherLanguageIs() {
        PreviewPicks.note("en")
        assertTrue(PreviewPicks.wasPicked("en"))
        assertFalse(
            "the register is PER LANGUAGE, like the pack, the declined flag and the launch latch " +
                "— picking English is not consent to spend 73 MB of French",
            PreviewPicks.wasPicked("fr"),
        )
        assertEquals(setOf("en"), PreviewPicks.picked.value)
    }

    @Test fun twoLanguagesPickedInOneProcessAreBothRemembered() {
        // A user who picks French, changes their mind and picks German has consented to both, and
        // the day two packs can arrive neither may be invisible to the decision.
        PreviewPicks.note("fr")
        PreviewPicks.note("de")
        assertEquals(setOf("fr", "de"), PreviewPicks.picked.value)
        assertTrue(PreviewPicks.wasPicked("fr"))
        assertTrue(PreviewPicks.wasPicked("de"))
    }

    @Test fun aPickSurvivesEveryLaterPickSoOneOwnerAnswersWhetherItWasActedOn() {
        // Nothing removes one language from this set, deliberately: the pick's consent is SPENT by
        // the once-per-launch latch (`PreviewTrigger.latchedForTheLaunch`), not by this object. A
        // register that cleared itself on actuation would be a second answer to "has this been
        // acted on?", and the two would disagree the first time `start` refused an attempt on
        // `busy()` — leaving a pick both spent and never tried.
        //
        // The behavioural form of that: a pick stays picked through everything except the
        // test-only reset, so "the user chose this language in this process" keeps meaning what
        // it says however many times they change their mind afterwards.
        PreviewPicks.note("en")
        for (other in listOf("fr", "de", "auto", "zh", "en")) PreviewPicks.note(other)
        assertTrue("the first pick is still a pick", PreviewPicks.wasPicked("en"))
        assertEquals(setOf("en", "fr", "de", "auto", "zh"), PreviewPicks.picked.value)
    }

    @Test fun aRepeatedPickOfTheSameLanguageChangesTheStateExactlyOnce() {
        // This is the case the selection flow itself cannot see: a `StateFlow` conflates equal
        // values, so re-picking the language ALREADY selected emits no new selection at all. The
        // first such tap DOES change this set — which is what makes "tap your own language again"
        // start the pack a backed-off top-up would have stayed quiet about — and the second
        // changes neither, which is the honest answer to a gesture that changed nothing.
        val before = PreviewPicks.picked.value
        PreviewPicks.note("en")
        val afterFirst = PreviewPicks.picked.value
        PreviewPicks.note("en")
        val afterSecond = PreviewPicks.picked.value
        assertFalse("the first pick is a change", before == afterFirst)
        assertTrue("the second is not", afterFirst == afterSecond)
        assertEquals(1, afterSecond.size)
    }

    @Test fun autoCanBeNotedAndIsHarmlessBecauseNoPackSpeaksIt() {
        // Auto is a pick like any other — the user chose it — and it reaches this register through
        // the same one writer. It costs nothing, because `decide`'s FIRST refusal is that the pack
        // must be the selected language's and no pack's language is "auto".
        PreviewPicks.note("auto")
        assertTrue(PreviewPicks.wasPicked("auto"))
        assertEquals(
            "and there is no catalogue row to fetch for it",
            null,
            StreamingPackCatalog.forLanguage("auto"),
        )
        assertEquals(
            PreviewAutoFetch.Decision.NONE,
            PreviewAutoFetch.decide(
                selectedLanguage = "auto",
                packLanguage = null,
                state = StreamingPackState.PackFetchable,
                userSaidNo = false,
                showLiveWords = true,
                localTierInstalled = true,
                starter = PreviewStarter.PICK,
                workingNetwork = false,
                sessionActive = false,
                batchJobActive = false,
                packWorkInFlight = false,
                attemptedThisLaunch = false,
                backedOff = false,
            ),
        )
    }

    @Test fun theRegisterIsWhatTurnsATopUpIntoAPickAndNothingElseDoes() {
        // The seam, end to end on the pure side: the same inputs, the same unusable network, and
        // the answer turns on membership in this set. This is the assertion that would fail if a
        // future edit derived the starter from the preference instead — the mistake the register
        // exists to make unnecessary.
        //
        // (Fix 1) The cell that tells the two apart is no longer a METERED connection — the owner
        // ruled that test away — but the register is still a live decision input, because the
        // unasked path still waits for a network that works and still defers to the 24 h
        // back-off, and a pick does neither.
        fun decide(picked: Boolean) = PreviewAutoFetch.decide(
            selectedLanguage = "en",
            packLanguage = "en",
            state = StreamingPackState.PackFetchable,
            userSaidNo = false,
            showLiveWords = true,
            localTierInstalled = true,
            starter = if (picked) PreviewStarter.PICK else PreviewStarter.TOP_UP,
            workingNetwork = false,
            sessionActive = false,
            batchJobActive = false,
            packWorkInFlight = false,
            attemptedThisLaunch = false,
            backedOff = false,
        )
        assertEquals(
            "unpicked: the unasked top-up waits for a network that works, and offers meanwhile",
            PreviewAutoFetch.Decision.OFFER,
            decide(PreviewPicks.wasPicked("en")),
        )
        PreviewPicks.note("en")
        assertEquals(
            "picked: the pack downloads at once, with no card in the way",
            PreviewAutoFetch.Decision.FETCH,
            decide(PreviewPicks.wasPicked("en")),
        )
    }
}
