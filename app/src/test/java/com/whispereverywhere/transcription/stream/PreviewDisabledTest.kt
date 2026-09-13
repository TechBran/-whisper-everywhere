package com.whispereverywhere.transcription.stream

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * THE OFF REGISTER (4.5.0 Task 3, fix round 2 — review r2's N2): the previewer's own per-language
 * verdict for this process, published where a SURFACE can read it.
 *
 * It exists because the verdict had no reader at all outside `StreamingPreviewEngine`, which is a
 * private field of `FloatingBubbleService` — while the strip above the language selector renders
 * a present-tense PROMISE off a terminal board record (*"English is ready: words appear on the
 * bubble whenever you pick it"*). A failed canary leaves 73 MB installed and valid, so no read of
 * the DISK can answer this question and no user gesture retires the record: it is the fact nobody
 * says.
 */
class PreviewDisabledTest {

    @Before fun clean() {
        PreviewDisabled.forgetAll()
        PreviewWorkboard.forgetAll()
    }

    @After fun tidy() {
        PreviewDisabled.forgetAll()
        PreviewWorkboard.forgetAll()
    }

    @Test fun nothingIsOffInAFreshProcess() {
        // The default decides every ordinary case — the engine's verdict self-heals on restart,
        // so a new process starts by believing every installed pack can produce words.
        assertEquals(emptySet<String>(), PreviewDisabled.languages.value)
        assertFalse(PreviewDisabled.isOff("en"))
    }

    @Test fun aNotedLanguageIsOffAndNoOtherLanguageIs() {
        PreviewDisabled.note("en")
        assertTrue(PreviewDisabled.isOff("en"))
        assertFalse(
            "per LANGUAGE, like the engine's own set: a failed English canary is rendered on the " +
                "English clip and says nothing about a French model (T2 defect 4)",
            PreviewDisabled.isOff("fr"),
        )
        assertEquals(setOf("en"), PreviewDisabled.languages.value)
    }

    @Test fun theSetOnlyGrowsBecauseTheEnginesVerdictNeverComesBackWithinAProcess() {
        // `warm` is a no-op once THIS pack is disabled, so even a repair install cannot bring the
        // language back before the next process. Removing a language here would be a second
        // opinion about a decision only the engine can reverse — and it cannot.
        PreviewDisabled.note("en")
        PreviewDisabled.note("en")
        PreviewDisabled.note("de")
        assertEquals(setOf("en", "de"), PreviewDisabled.languages.value)
    }

    @Test fun theVerdictIsWHATSILENCESTheReadyReceiptAndNothingElseAboutTheRow() {
        // The seam, end to end: the board's record is untouched (the arrival really happened and
        // the bytes are still installed — the axis is stated in `PreviewWorkboard.retire`), and
        // the one sentence that becomes false is the promise.
        val installed = PreviewWork(
            language = "en",
            route = PreviewRoute.PLAY_FETCH,
            starter = PreviewStarter.PICK,
            step = PreviewStep(PreviewPhase.INSTALLED),
        )
        val ready = StreamingPackCopy.selectorLine(
            work = installed,
            language = "English",
            selectedLanguage = "en",
            showLiveWords = true,
            localTierInstalled = true,
            disabledLanguages = PreviewDisabled.languages.value,
            // (4.5.1 Task 1) The fifth fact, and this class is about the verdict: English is the
            // warm language throughout, so the only thing that moves below is the verdict.
            warmLanguage = "en",
        )
        // The receipt names what the ARRIVED pack puts on the strip (4.5.0 Task 4), and the
        // record above is English's, whose unit its own tokens.txt proves.
        assertEquals(
            StreamingPackCopy.selectorReady("English", StreamingPackCatalog.EN.stripUnit),
            ready,
        )
        PreviewDisabled.note("en")
        assertNull(
            "the same record, the same switch, the same tier — and now no promise, because no " +
                "word can reach the bubble until the process restarts",
            StreamingPackCopy.selectorLine(
                work = installed,
                language = "English",
                selectedLanguage = "en",
                showLiveWords = true,
                localTierInstalled = true,
                disabledLanguages = PreviewDisabled.languages.value,
                warmLanguage = "en",
            ),
        )
        // ...and an IN-FLIGHT line for the same language still says what is happening: a repair
        // fetch moves real bytes whatever this process has decided about the model it will land.
        val fetching = installed.at(PreviewStep(PreviewPhase.DOWNLOADING, 12_000_000L, 72_654_782L))
        assertEquals(
            StreamingPackCopy.workLine(fetching, answer = StreamingPackCopy.AnswerGesture.NONE),
            StreamingPackCopy.selectorLine(
                work = fetching,
                language = "English",
                selectedLanguage = "en",
                showLiveWords = true,
                localTierInstalled = true,
                disabledLanguages = setOf("en"),
                warmLanguage = null,
            ),
        )
    }
}
