package com.whispereverywhere.transcription.stream

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * THE WARM REGISTER (4.5.1 Task 1): what the previewer's engine can be asked, and what it answered,
 * published where a SURFACE can read it.
 *
 * It exists for [PreviewDisabled]'s reason, one fact over: the engine is a private field of
 * `FloatingBubbleService`, so `isWarmFor(pack)` — the answer the session gate itself reads — had no
 * reader outside the service, while the strip above the language selector renders a present-tense
 * promise off a terminal board record (*"English is ready: words appear on the bubble whenever you
 * pick it"*). Until this build that receipt meant *the files landed*. The owner's complaint is
 * exactly the gap between those two facts: the files land, the strip says ready, and the next tap
 * shows nothing.
 *
 * ### Why THREE states and not a nullable language (fix round 1, review r1's B1)
 *
 * Because two facts have two owners. The ENGINE owns whether it is warm and for what; the SERVICE
 * owns whether an engine exists at all, and it must, because nothing can report its own absence.
 * The first version published only the engine's half, so *"no engine exists in this process"* and
 * *"the engine says no"* were the same null — and the copy read both as *not ready*. The bubble
 * service is not started when the app launches, so on AF5's own flow (onboarding picks, the
 * progress shows on Home, the user has not tapped to start the bubble) the install completes with
 * no engine ever built: the receipt disappeared, the strip's row with it, and the strip renders
 * nothing when it has no rows. A user who watches 128 MB arrive and is then told nothing concludes
 * the feature did not work — which is the complaint this task exists to answer.
 *
 * ### Why ONE language and not a set
 *
 * The engine holds ONE recognizer (the one-pack-per-process invariant, `StreamingPreviewEngine
 * .warm`: a different pack releases the resident one inside the engine's own task before the new
 * one allocates). A set would say something the engine cannot do, and a surface reading it would
 * eventually promise words for two languages at once. [PreviewDisabled] is a set for the opposite
 * reason: its verdicts accumulate and never come back.
 *
 * ### Why the warm half is written by the ENGINE's own answer
 *
 * `isWarm()` is `warm && !off`, and both halves move on the engine's executor thread: a load that
 * armed, a release on trim, a language change, a canary that failed, three decode throws. So the
 * register is fed by the engine handing its OWN answer over — never by Main writing what it
 * believes it asked for, which is the stale-pack defect `onLoadFailure` and `onDisabled` were each
 * re-shaped to avoid.
 */
class PreviewWarmTest {

    @Before fun clean() {
        PreviewWarm.forgetAll()
        PreviewWorkboard.forgetAll()
        PreviewDisabled.forgetAll()
    }

    @After fun tidy() {
        PreviewWarm.forgetAll()
        PreviewWorkboard.forgetAll()
        PreviewDisabled.forgetAll()
    }

    @Test fun aFreshProcessHasNOENGINE_whichIsNotTheSameAsNothingBeingWarm() {
        // THE DISTINCTION B1 WAS ABOUT. A process that has built no engine has not answered "no" —
        // nobody was asked. The bubble service owns the engine and is started by the Home toggle,
        // the Settings toggle, the boot notification and BootReceiver: never by the app launching.
        // So this is the state Home is read in by every user who has not started the bubble yet.
        assertEquals(PreviewWarmth.NoEngine, PreviewWarm.warmth.value)
        assertFalse("and nothing is warm either — the two are just not one value", PreviewWarm.isWarm("en"))
    }

    @Test fun anEngineThatEXISTSButHasLoadedNothingIsCOLD() {
        // Now there is something to ask, and the answer is a real no: this is the 802-860 ms load
        // window the receipt is deliberately silent through.
        PreviewWarm.engineBuilt()
        assertEquals(PreviewWarmth.Cold, PreviewWarm.warmth.value)
        assertFalse(PreviewWarm.isWarm("en"))
    }

    @Test fun theNotedLanguageIsWarmAndNoOtherLanguageIs() {
        PreviewWarm.engineBuilt()
        PreviewWarm.note("fr")
        assertTrue(PreviewWarm.isWarm("fr"))
        assertFalse(
            "per LANGUAGE: a French recognizer is not an English one, and a gate that read " +
                "isWarm() alone let the tee borrow the wrong model (T2 defect 4)",
            PreviewWarm.isWarm("en"),
        )
        assertEquals(PreviewWarmth.Warm("fr"), PreviewWarm.warmth.value)
    }

    @Test fun oneLanguageAtATime_becauseTheEngineHoldsOneRecognizer() {
        // The one-pack-per-process invariant, read back: warming German is releasing French, so
        // the register cannot hold both. This is the difference from PreviewDisabled's growing set.
        PreviewWarm.engineBuilt()
        PreviewWarm.note("fr")
        PreviewWarm.note("de")
        assertEquals(PreviewWarmth.Warm("de"), PreviewWarm.warmth.value)
        assertFalse("and French is no longer warm — its recognizer was freed", PreviewWarm.isWarm("fr"))
    }

    @Test fun itIsWITHDRAWN_becauseWarmIsNotAVerdictAndComesAndGoes() {
        // The whole reason this is not PreviewDisabled's shape. A trim frees the recognizer, a
        // language change frees it, a failed canary takes the language off: all of them mean
        // nothing is warm, and all of them are reachable while a selection surface is composed.
        // The engine still EXISTS through every one of them, so the withdrawal is COLD — a real
        // no from something that was really asked.
        PreviewWarm.engineBuilt()
        PreviewWarm.note("en")
        PreviewWarm.note(null)
        assertEquals(PreviewWarmth.Cold, PreviewWarm.warmth.value)
        assertFalse(PreviewWarm.isWarm("en"))
    }

    @Test fun theSERVICEDroppingItsEngineGoesBackToNOENGINE_andTheDeadEnginesLastWordIsNotHeard() {
        // `StreamingPreviewEngine.release()` POSTS its withdrawal to the engine's own executor, and
        // `onDestroy` releases and then nulls the field — so a note from an engine that no longer
        // exists lands AFTER the service said it was gone. Believing it would write "not warm" over
        // "no engine" and suppress the receipt for the rest of the process: B1 again, one ordering
        // down. So a note nobody claims is dropped.
        PreviewWarm.engineBuilt()
        PreviewWarm.note("en")
        PreviewWarm.engineGone()
        assertEquals(PreviewWarmth.NoEngine, PreviewWarm.warmth.value)
        PreviewWarm.note(null)
        assertEquals(
            "the withdrawal the dying engine had already posted must not become a NO from a " +
                "process that has nothing to ask",
            PreviewWarmth.NoEngine,
            PreviewWarm.warmth.value,
        )
        PreviewWarm.note("en")
        assertEquals(
            "and neither may a stale ARM — a freed recognizer cannot promise words",
            PreviewWarmth.NoEngine,
            PreviewWarm.warmth.value,
        )
        // ...and the next service builds an engine, which claims the register again.
        PreviewWarm.engineBuilt()
        PreviewWarm.note("en")
        assertEquals(PreviewWarmth.Warm("en"), PreviewWarm.warmth.value)
    }

    @Test fun theWARMFactIsWHATMAKESTheReadyReceiptTrue_andInstalledAloneNoLongerIs() {
        // THE SEAM, END TO END — and the defect this build retires. The board record below is the
        // arrival: the marker landed, the bytes are verified, `state()` answers `Installed`. In
        // 4.5.0 that alone printed *"English is ready: words appear on the bubble whenever you pick
        // it"*, and the very next tap showed nothing, because the engine was still cold and the
        // gate reads `isWarmFor()` now.
        val installed = PreviewWork(
            language = "en",
            route = PreviewRoute.PLAY_FETCH,
            starter = PreviewStarter.PICK,
            step = PreviewStep(PreviewPhase.INSTALLED),
        )
        fun line() = StreamingPackCopy.selectorLine(
            work = installed,
            language = "English",
            selectedLanguage = "en",
            showLiveWords = true,
            localTierInstalled = true,
            disabledLanguages = PreviewDisabled.languages.value,
            warmth = PreviewWarm.warmth.value,
        )
        val ready = StreamingPackCopy.selectorReady("English", StreamingPackCatalog.EN.stripUnit)
        // (fix round 1, review r1's B1) NO ENGINE IN THE PROCESS — the ordinary state of the screen
        // this line is rendered on, because the bubble service owns the engine and nothing has
        // started it. 4.5.0's receipt stands, and here it is TRUE: the promise is that the language
        // is ready to SELECT (ruling 3c's own words), and starting the bubble arms it through the
        // prewarm. Suppressing it took the whole strip off screen the instant a download finished.
        assertEquals(
            "with nothing to ask, the 4.5.0 receipt is the honest sentence — and it is the one " +
                "the surface was ruled in for",
            ready,
            line(),
        )
        // ...and the moment there IS an engine, the receipt is its answer.
        PreviewWarm.engineBuilt()
        assertNull(
            "the files have landed, an engine exists and NOTHING is loaded: no promise, which is " +
                "the honest half of the fix — the strip is silent for the 802-860 ms the load takes",
            line(),
        )
        PreviewWarm.note("en")
        assertEquals(
            "and the receipt appears when the engine is warm — download finishes, the strip says " +
                "ready, the next tap works",
            ready,
            line(),
        )
        PreviewWarm.note("fr")
        assertNull(
            "another language's model resident is not this one's promise: the tee would borrow " +
                "the wrong recognizer, which is exactly what isWarmFor refuses",
            line(),
        )
        // ...and when the service goes, the sentence goes back to being about selection.
        PreviewWarm.engineGone()
        assertEquals(
            "the bubble stopped: nothing can be asked again, and the pack really is ready to pick",
            ready,
            line(),
        )
    }
}
