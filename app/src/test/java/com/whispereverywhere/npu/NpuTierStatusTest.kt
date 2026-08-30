package com.whispereverywhere.npu

import com.whispereverywhere.model.ModelScope
import com.whispereverywhere.model.WhisperCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PER-TIER DECLINE RECORD (4.1 L8, step 1) — the executable half of the re-spec that lets two
 * npu-class tiers be A/B'd in one process.
 *
 * Until L8, [NpuTierStatus] held ONE process-wide reason. Correct while one npu-class tier
 * existed; with two, a turbo decline — *"init: could not deserialise 740 MB"* — fed the same
 * decline input that gated `npu`, banning the SMALL tier for the rest of the process and wearing
 * turbo's note on whichever card asked. In a lab whose purpose is comparing the two, the first
 * tier to hiccup would have silently removed the other from the comparison. The record is now a
 * map keyed by tier id; `routesToNpu` reads [NpuTierStatus.declinedTiers] membership and the card
 * reads [NpuTierStatus.reasonFor] its OWN id.
 *
 * The wiring INTO this object (the backend's setter publishing under `spec.tierId`) is pinned as
 * source by `NpuDiagTest.theBackendAnnouncesEveryWriteOfItsReasonThroughOneFunnel` — no JVM test
 * may name `NpuWhisperBackend`. Everything downstream of the funnel is executed here.
 *
 * **The `@After` resets the process singleton** (Q8 M5, which landed neither with F3 nor since):
 * these tests mutate shared JVM state, and a leaked reason would turn the independence claims
 * below into order-dependent flakes — the exact defect class the reset was asked for.
 */
class NpuTierStatusTest {

    @After
    fun resetTheProcessSingleton() {
        NpuTierStatus.declinedTiers.forEach { NpuTierStatus.publish(it, null) }
        assertEquals(
            "the reset itself must leave the record empty — a leaked reason here poisons " +
                "whatever test the JVM runs next",
            emptySet<String>(),
            NpuTierStatus.declinedTiers,
        )
    }

    @Test
    fun aDeclineIsRecordedUnderItsOwnTierAndReadableOnlyThere() {
        NpuTierStatus.publish("npu-turbo", "init: could not deserialise the 740 MB encoder")

        assertEquals(
            "the declining tier's record is its own",
            "init: could not deserialise the 740 MB encoder",
            NpuTierStatus.reasonFor("npu-turbo"),
        )
        assertNull(
            "THE COUPLING THE RE-SPEC REMOVES: turbo's decline must not be readable as npu's — " +
                "under the 4.0 single-reason mirror this exact read answered turbo's reason and " +
                "npu's card wore it",
            NpuTierStatus.reasonFor("npu"),
        )
        assertEquals(
            "and the routing input sees exactly the declining tier",
            setOf("npu-turbo"),
            NpuTierStatus.declinedTiers,
        )
    }

    @Test
    fun twoTiersDeclinesStandIndependentlyAndClearIndependently() {
        NpuTierStatus.publish("npu", "encode: graphExecute failed at 0")
        NpuTierStatus.publish("npu-turbo", "init: nativeInit failed at 0")

        assertEquals("both records stand at once", setOf("npu", "npu-turbo"), NpuTierStatus.declinedTiers)
        assertEquals("encode: graphExecute failed at 0", NpuTierStatus.reasonFor("npu"))
        assertEquals("init: nativeInit failed at 0", NpuTierStatus.reasonFor("npu-turbo"))

        // The arm path's clear (`unavailableReason = null` in load) is per-tier too: re-arming
        // npu clears npu's record and MUST leave turbo's standing — a clear that emptied the map
        // would re-open turbo's retry loop from npu's successful arm.
        NpuTierStatus.publish("npu", null)
        assertNull("npu's record cleared by npu's own arm", NpuTierStatus.reasonFor("npu"))
        assertEquals(
            "turbo's decline survives npu's re-arm — the records are independent in BOTH " +
                "directions, clear as well as set",
            setOf("npu-turbo"),
            NpuTierStatus.declinedTiers,
        )
        assertEquals("init: nativeInit failed at 0", NpuTierStatus.reasonFor("npu-turbo"))
    }

    @Test
    fun aRepublishForTheSameTierKeepsOneRecordWithTheLatestReason() {
        // The backend's own funnel is at-most-once per session (fallBackToCpuTier's guard), but
        // a NEW session of the same tier can decline at a different stage after a restart-less
        // re-arm. One tier, one record: the map cannot grow a history, and the latest reason is
        // the one that is true for the card.
        NpuTierStatus.publish("npu", "encode: graphExecute failed at 0")
        NpuTierStatus.publish("npu", "decode: graphExecute failed at 3")
        assertEquals(setOf("npu"), NpuTierStatus.declinedTiers)
        assertEquals("decode: graphExecute failed at 3", NpuTierStatus.reasonFor("npu"))
    }

    @Test
    fun declinedTiersIsExactlyTheKeySetAndEmptyMeansNothingDeclined() {
        assertEquals(
            "a fresh process has declined nothing — and this emptiness is what routesToNpu's " +
                "`tierId !in declinedTiers` clause answers true through",
            emptySet<String>(),
            NpuTierStatus.declinedTiers,
        )
        NpuTierStatus.publish("npu-turbo", "skel: stage refused")
        assertEquals(setOf("npu-turbo"), NpuTierStatus.declinedTiers)
        assertTrue(
            "membership is the routing question, verbatim",
            "npu-turbo" in NpuTierStatus.declinedTiers && "npu" !in NpuTierStatus.declinedTiers,
        )
    }

    @Test
    fun reasonForAnswersNullForNullAndUnknownIds() {
        NpuTierStatus.publish("npu", "init: nativeInit failed at 0")
        assertNull(
            "a null tier id (selectedModelId before onboarding) has no record and must not throw",
            NpuTierStatus.reasonFor(null),
        )
        assertNull("an unknown id has no record", NpuTierStatus.reasonFor("nope"))
        assertNull("a CPU tier has no record — it cannot decline", NpuTierStatus.reasonFor("multi"))
    }

    @Test
    fun theCardHelpersReadThePerTierRecordUnchanged() {
        // stageOf/cardNote are byte-unchanged by the re-spec (their contract is F3's, corrected
        // to PROCESS lifetime); what changed is only WHICH record they are handed. Asserted
        // through the per-tier read so the composition the screen actually performs is the thing
        // proved. 4.3 added the fallback argument; the CPU-model-present arm below is the note
        // this test has always asserted, verbatim.
        NpuTierStatus.publish("npu-turbo", "init: could not deserialise the 740 MB encoder")
        val note = NpuTierStatus.cardNote(NpuTierStatus.reasonFor("npu-turbo"), true)!!
        assertTrue("the note names the stage of THIS tier's decline: $note", note.contains("init"))
        assertTrue("and the way back: $note", note.contains("Restart the app"))
        assertNull(
            "while the sibling tier — never declined — composes NO note from its own null " +
                "record, which is what keeps the warning off the card it is not about",
            NpuTierStatus.cardNote(NpuTierStatus.reasonFor("npu"), true),
        )
        // ...and a never-declined tier composes nothing in the no-fallback state either: the
        // absence of a CPU model is not itself a decline, and a device that has never armed the
        // tier has no measurement to report (the class KDoc's oldest rule).
        assertNull(NpuTierStatus.cardNote(NpuTierStatus.reasonFor("npu"), false))
    }

    // ------------------------------------------------------------- 4.3: the decline's recovery

    @Test
    fun theCpuModelPresentArmIsTheOldNoteVERBATIM() {
        // The 4.0/F3 sentence, byte for byte. 4.3 added an arm; it may not have edited this one —
        // a device WITH a CPU model still falls back exactly as it always has, and the copy that
        // says so is not this branch's to reword.
        assertEquals(
            "The AI chip is unavailable on this device right now (stage: init), so speech is " +
                "running on the multilingual CPU model. Accuracy is unchanged; it is slower. " +
                "Restart the app to try the AI chip again.",
            NpuTierStatus.cardNote("init: nativeInit failed at 0", true),
        )
    }

    @Test
    fun theNoFallbackArmSaysSoPlainlyAndNeverClaimsTheCpuModelIsRunning() {
        // THE STATE 4.3 CREATES: the chooser offers a capable device `npu-turbo` alone, so a
        // fresh capable install can hold turbo and nothing else — and `fallBackToCpuTier` then
        // returns 0L at `paths.cpuTierModelPath() ?: return 0L`, leaving the session with no
        // backend. The old note's load-bearing clause ("speech is running on the multilingual CPU
        // model") would be FALSE there, which is the whole reason the arm exists.
        val note = NpuTierStatus.cardNote("init: nativeInit failed at 0", false)!!
        assertTrue("it still names the stage: $note", note.contains("stage: init"))
        assertFalse(
            "it must NOT claim speech is running on the CPU model — nothing is installed: $note",
            note.contains("running on the multilingual CPU model"),
        )
        assertTrue(
            "it says plainly that there is nothing to fall back to: $note",
            note.contains("no CPU speech model is installed to fall back to"),
        )
        assertTrue(
            "it says what that costs, rather than failing mute: $note",
            note.contains("dictation cannot run until one is"),
        )
        assertTrue(
            "and it points at the control that fixes it, which the card renders directly " +
                "below this sentence: $note",
            note.contains("download the standard multilingual model below"),
        )
        assertTrue(
            "the restart route survives beside it: $note",
            note.contains("Restart the app to try the AI chip again"),
        )
    }

    @Test
    fun theTwoRemediesAreTrueTOGETHERAndTheSwitchIsNamedBeforeItHappens() {
        // 4.3 fix round, I-1(b). The first shipping wording offered the download and the restart
        // as if both survived the tap. They do not: the download persists selectedModelId onto
        // the CPU tier, so "restart the app to try the AI chip again" went FALSE the instant the
        // user acted on the button printed directly beneath it — a remedy that expires when you
        // use the remedy next to it, on a note whose whole job is to be honest about a decline.
        val note = NpuTierStatus.cardNote("init: nativeInit failed at 0", false)!!
        assertTrue(
            "the restart must be offered as the remedy that keeps the AI chip, and FIRST — it " +
                "costs nothing and a process-scoped decline is exactly what it fixes: $note",
            note.indexOf("Restart the app") < note.indexOf("download the standard"),
        )
        assertTrue(
            "and the download must carry its CONSEQUENCE, so the two are alternatives rather " +
                "than a promise the second one breaks: $note",
            note.contains("that switches you to it"),
        )
        assertTrue(
            "...including that the gigabyte the user already paid for is not lost: $note",
            note.contains("leaves your AI chip model installed"),
        )
        assertTrue(
            "...and where the way back is, which must be a place they are actually standing: $note",
            note.contains("one tap away on this screen"),
        )
        // The switch is then RE-stated at the moment it happens, because a sentence read before
        // a tap is not a receipt for what the tap did.
        val switched = NpuTierStatus.RECOVERY_SWITCH_NOTE
        assertEquals(
            "Switched to the standard model. Your AI chip model stays installed — pick it " +
                "again from this screen any time.",
            switched,
        )
        assertTrue("it states the change", switched.contains("Switched to the standard model"))
        assertTrue("it states what was NOT lost", switched.contains("stays installed"))
        assertTrue("and it states the way back", switched.contains("pick it again"))
        // The CPU-model-present arm makes no switch claim at all: nothing switches there.
        val other = NpuTierStatus.cardNote("init: nativeInit failed at 0", true)!!
        assertFalse("the fallback arm must not talk about switching", other.contains("switches you"))
    }

    @Test
    fun theRecoveryConstantsNameOneTierAndOneAction() {
        // `multi` and nothing else: it is MULTILINGUAL (so the recovery never hands a non-English
        // speaker an English-only model — the Bengali-review discipline), it is a single-file URL
        // tier the existing download path can actually install, and it is a legal 80-bin CPU
        // fallback, which is the entire point of downloading it.
        assertEquals("multi", NpuTierStatus.RECOVERY_TIER_ID)
        val model = WhisperCatalog.byId(NpuTierStatus.RECOVERY_TIER_ID)
        assertNotNull("the recovery tier must resolve in the catalog", model)
        assertEquals(ModelScope.MULTILINGUAL, model!!.scope)
        assertTrue(
            "the recovery must be installable by the EXISTING download path — a paired tier " +
                "cannot be, and download()'s first act would delete the file at `fileName`",
            WhisperCatalog.isInstallableByDownload(model),
        )
        assertTrue(
            "and what it installs must actually BE a fallback, or the recovery fixes nothing",
            WhisperCatalog.isCpuFallbackEligible(model),
        )
        assertTrue(
            "downloading it is what flips the question the note asked",
            WhisperCatalog.hasCpuFallback(setOf(NpuTierStatus.RECOVERY_TIER_ID)),
        )
        assertEquals("Download the standard model", NpuTierStatus.RECOVERY_ACTION)
    }

    @Test
    fun theButtonAndTheSentenceCanNeverDisagree() {
        // needsCpuRecovery is the SAME predicate cardNote splits its arms on, and this executes
        // the equivalence over the whole input space rather than trusting two functions to be
        // edited together. The mutation it closes is a one-word drift in either direction: a
        // button beside the "already falling back" sentence, or the "download it below" sentence
        // with no control below it.
        val reasons = listOf(
            null, "", "   ", ":", "init: nativeInit failed at 0",
            "encode: graphExecute failed at 0", "skel", "  decode: x  ",
        )
        listOf(true, false).forEach { installed ->
            reasons.forEach { reason ->
                val note = NpuTierStatus.cardNote(reason, installed)
                val needs = NpuTierStatus.needsCpuRecovery(reason, installed)
                if (needs) {
                    assertNotNull("<<$reason>>/$installed: a button with no note", note)
                    assertTrue(
                        "<<$reason>>/$installed: the button rides the wrong arm",
                        note!!.contains("no CPU speech model is installed"),
                    )
                } else {
                    assertFalse(
                        "<<$reason>>/$installed: the no-fallback sentence renders without the " +
                            "control it names",
                        note?.contains("no CPU speech model is installed") == true,
                    )
                }
                // And the recovery is never offered where there is nothing to recover FROM: no
                // decline on record means no note and no button, whatever the disk holds.
                if (NpuTierStatus.stageOf(reason) == null) {
                    assertNull("<<$reason>>: no decline, no note", note)
                    assertFalse("<<$reason>>: no decline, no button", needs)
                }
            }
        }
        // The card's two reads compose: an undeclined tier on a device with no CPU model at all
        // still shows nothing. The recovery belongs to a decline, not to an empty disk.
        assertFalse(NpuTierStatus.needsCpuRecovery(null, false))
    }
}
