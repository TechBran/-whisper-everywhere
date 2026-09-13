package com.whispereverywhere.transcription.stream

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * THE WARM REGISTER (4.5.1 Task 1): which language's previewer is LOADED AND USABLE right now,
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
 * ### Why ONE language and not a set
 *
 * The engine holds ONE recognizer (the one-pack-per-process invariant, `StreamingPreviewEngine
 * .warm`: a different pack releases the resident one inside the engine's own task before the new
 * one allocates). A set would say something the engine cannot do, and a surface reading it would
 * eventually promise words for two languages at once. [PreviewDisabled] is a set for the opposite
 * reason: its verdicts accumulate and never come back.
 *
 * ### Why it is written by the ENGINE's own answer
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

    @Test fun nothingIsWarmInAFreshProcess() {
        // The honest default, and the one that matters: a process that has loaded nothing promises
        // nothing. The boot prewarm's own 802-860 ms is spent AFTER this.
        assertNull(PreviewWarm.language.value)
        assertFalse(PreviewWarm.isWarm("en"))
    }

    @Test fun theNotedLanguageIsWarmAndNoOtherLanguageIs() {
        PreviewWarm.note("fr")
        assertTrue(PreviewWarm.isWarm("fr"))
        assertFalse(
            "per LANGUAGE: a French recognizer is not an English one, and a gate that read " +
                "isWarm() alone let the tee borrow the wrong model (T2 defect 4)",
            PreviewWarm.isWarm("en"),
        )
        assertEquals("fr", PreviewWarm.language.value)
    }

    @Test fun oneLanguageAtATime_becauseTheEngineHoldsOneRecognizer() {
        // The one-pack-per-process invariant, read back: warming German is releasing French, so
        // the register cannot hold both. This is the difference from PreviewDisabled's growing set.
        PreviewWarm.note("fr")
        PreviewWarm.note("de")
        assertEquals("de", PreviewWarm.language.value)
        assertFalse("and French is no longer warm — its recognizer was freed", PreviewWarm.isWarm("fr"))
    }

    @Test fun itIsWITHDRAWN_becauseWarmIsNotAVerdictAndComesAndGoes() {
        // The whole reason this is not PreviewDisabled's shape. A trim frees the recognizer, a
        // language change frees it, a failed canary takes the language off: all of them mean
        // nothing is warm, and all of them are reachable while a selection surface is composed.
        PreviewWarm.note("en")
        PreviewWarm.note(null)
        assertNull(PreviewWarm.language.value)
        assertFalse(PreviewWarm.isWarm("en"))
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
            warmLanguage = PreviewWarm.language.value,
        )
        assertNull(
            "the files have landed and NOTHING is loaded: no promise, which is the honest half " +
                "of the fix — the strip is silent for the 802-860 ms the load takes",
            line(),
        )
        PreviewWarm.note("en")
        assertEquals(
            "and the receipt appears when the engine is warm — download finishes, the strip says " +
                "ready, the next tap works",
            StreamingPackCopy.selectorReady("English", StreamingPackCatalog.EN.stripUnit),
            line(),
        )
        PreviewWarm.note("fr")
        assertNull(
            "another language's model resident is not this one's promise: the tee would borrow " +
                "the wrong recognizer, which is exactly what isWarmFor refuses",
            line(),
        )
    }
}
