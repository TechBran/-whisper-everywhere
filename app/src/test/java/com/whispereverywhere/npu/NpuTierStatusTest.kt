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
        val note = NpuTierStatus.cardNote(NpuTierStatus.reasonFor("npu-turbo"), true, stillSelected = true, vendor = NpuVendor.QUALCOMM)!!
        assertTrue("the note names the stage of THIS tier's decline: $note", note.contains("init"))
        assertTrue("and the way back: $note", note.contains("Restart the app"))
        assertNull(
            "while the sibling tier — never declined — composes NO note from its own null " +
                "record, which is what keeps the warning off the card it is not about",
            NpuTierStatus.cardNote(NpuTierStatus.reasonFor("npu"), true, stillSelected = true, vendor = NpuVendor.QUALCOMM),
        )
        // ...and a never-declined tier composes nothing in the no-fallback state either: the
        // absence of a CPU model is not itself a decline, and a device that has never armed the
        // tier has no measurement to report (the class KDoc's oldest rule).
        assertNull(NpuTierStatus.cardNote(NpuTierStatus.reasonFor("npu"), false, false, NpuVendor.QUALCOMM))
    }

    // ------------------------------------------------------------- 4.3: the decline's recovery

    @Test
    fun theCpuModelPresentArmStatesTheREALTradeAndPinsItVerbatim() {
        // This assertion carried the 4.0/F3 sentence byte for byte and forbade editing it, on the
        // reasoning that a device falling back "exactly as it always has" should read exactly what
        // it always did. That reasoning outlived its fact. In 4.0 the only NPU tier ran
        // whisper-small — the SAME checkpoint the CPU multilingual model carries — so "Accuracy is
        // unchanged; it is slower" was measurably true. 4.1 shipped npu-turbo (large-v3-turbo) and
        // 4.3 made turbo the ONLY tier a capable device is offered, so the overwhelmingly likely
        // decline is now turbo -> CPU, where accuracy IS lost: the owner's own A/B called turbo
        // "much more accurate", and their production field report (2026-08-30, an S23 Ultra on
        // 8 Gen 2 and a MediaTek tablet, both correctly declined and both on the CPU model) read
        // "accuracy just suffers a bit". The pin stays verbatim — the string is still one edit away
        // from a comfortable lie — but it pins the TRUE sentence now.
        assertEquals(
            "The AI chip is unavailable on this device right now (stage: init), so speech is " +
                "running on the multilingual CPU model. It is slower, and a little less accurate " +
                "than the AI chip model. Restart the app to try the AI chip again.",
            NpuTierStatus.cardNote("init: nativeInit failed at 0", true, stillSelected = true, vendor = NpuVendor.QUALCOMM),
        )
    }

    @Test
    fun theRestartPromiseIsMadeONLYWhereARestartWouldActuallyRetryThisTier() {
        // THE MICRO-ROUND. "Restart the app to try the AI chip again" is true for exactly one
        // reason — the decline record dies with the process — and that reasoning has a second
        // premise nobody had written down: routesToNpu reads the SELECTION, so a restart only
        // re-tries this tier while the selection still names it.
        //
        // The recovery is what breaks it. After the user taps "Download the standard model",
        // selectedModelId is `multi` and hasCpuFallback flips true, so the note silently swaps to
        // the fallback-installed arm — which kept promising a restart that routes straight to the
        // CPU, printed inches below the green switch note carrying the CORRECT way back. Two
        // sentences on one screen, disagreeing about how to reach the same tier.
        listOf(true, false).forEach { installed ->
            val selected = NpuTierStatus.cardNote("init: x", installed, stillSelected = true, vendor = NpuVendor.QUALCOMM)!!
            assertTrue(
                "selected/$installed: a restart DOES re-try this tier and must be offered: $selected",
                selected.contains("Restart the app to try the AI chip again."),
            )
            val moved = NpuTierStatus.cardNote("init: x", installed, stillSelected = false, vendor = NpuVendor.QUALCOMM)!!
            assertFalse(
                "MOVED/$installed: the selection is elsewhere, so a restart re-tries NOTHING — " +
                    "this promise must not be printed: $moved",
                moved.contains("Restart the app"),
            )
            assertTrue(
                "MOVED/$installed: and the remedy that IS true must be printed instead: $moved",
                moved.contains("Pick it again on this screen to try the AI chip."),
            )
        }
        // The stage and the arm's own load-bearing clause survive the split untouched.
        val moved = NpuTierStatus.cardNote("encode: boom", true, stillSelected = false, vendor = NpuVendor.QUALCOMM)!!
        assertTrue(moved.contains("stage: encode"))
        assertTrue(moved.contains("running on the multilingual CPU model"))
    }

    @Test
    fun theCardNoteAndTheSwitchNoteNAMETHESAMEWAYBACK() {
        // The consistency the micro-round exists to guarantee, executed rather than trusted: the
        // two sentences a user sees TOGETHER after a recovery must point at the same place. The
        // green note says "pick it again from this screen"; the card note, now that the selection
        // has moved, must say the same thing and must not contradict it with a restart.
        val afterRecovery = NpuTierStatus.cardNote(
            "init: nativeInit failed at 0", cpuFallbackInstalled = true, stillSelected = false,
            vendor = NpuVendor.QUALCOMM,
        )!!
        val green = NpuTierStatus.RECOVERY_SWITCH_NOTE
        assertTrue("the green note names the screen: $green", green.contains("this screen"))
        assertTrue("and so does the card note: $afterRecovery", afterRecovery.contains("this screen"))
        assertTrue("both say to pick it again", green.contains("pick it again"))
        assertTrue(afterRecovery.contains("Pick it again"))
        assertFalse(
            "and NEITHER may send the user to a restart that would not re-try the tier",
            afterRecovery.contains("Restart the app") || green.contains("Restart the app"),
        )
        // The pre-recovery pair is consistent too, in the other direction: nothing has switched
        // yet, so the restart is the true remedy and the green note is not on screen at all.
        val beforeRecovery = NpuTierStatus.cardNote(
            "init: nativeInit failed at 0", cpuFallbackInstalled = false, stillSelected = true,
            vendor = NpuVendor.QUALCOMM,
        )!!
        assertTrue(beforeRecovery.contains("Restart the app to try the AI chip again."))
        assertFalse(
            "the pre-recovery note must not tell the user to pick a tier they are already on",
            beforeRecovery.contains("Pick it again"),
        )
    }

    @Test
    fun theNoFallbackArmSaysSoPlainlyAndNeverClaimsTheCpuModelIsRunning() {
        // THE STATE 4.3 CREATES: the chooser offers a capable device `npu-turbo` alone, so a
        // fresh capable install can hold turbo and nothing else — and `fallBackToCpuTier` then
        // returns 0L at `paths.cpuTierModelPath() ?: return 0L`, leaving the session with no
        // backend. The old note's load-bearing clause ("speech is running on the multilingual CPU
        // model") would be FALSE there, which is the whole reason the arm exists.
        val note = NpuTierStatus.cardNote("init: nativeInit failed at 0", false, stillSelected = true, vendor = NpuVendor.QUALCOMM)!!
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
        val note = NpuTierStatus.cardNote("init: nativeInit failed at 0", false, stillSelected = true, vendor = NpuVendor.QUALCOMM)!!
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
        val other = NpuTierStatus.cardNote("init: nativeInit failed at 0", true, stillSelected = true, vendor = NpuVendor.QUALCOMM)!!
        assertFalse("the fallback arm must not talk about switching", other.contains("switches you"))
    }

    @Test
    fun theRecoveryConstantsNameOneTierAndOneAction() {
        // `small-q8` and nothing else (4.7; `multi` until the Q8 ruling of 2026-09-17 retired it):
        // it is MULTILINGUAL (so the recovery never hands a non-English speaker an English-only
        // model — the Bengali-review discipline), it is a single-file URL tier the existing
        // download path can actually install, it is a legal 80-bin CPU fallback, which is the
        // entire point of downloading it — and it is PICKABLE, which a retired `multi` would not
        // be: `pickableFor` runs `!it.retired` before `alsoOfferedIds`, so a recovery onto a
        // retired tier would install a model the capable chooser then renders no card for.
        assertEquals("small-q8", NpuTierStatus.RECOVERY_TIER_ID)
        val model = WhisperCatalog.byId(NpuTierStatus.RECOVERY_TIER_ID)
        assertNotNull("the recovery tier must resolve in the catalog", model)
        assertEquals(ModelScope.MULTILINGUAL, model!!.scope)
        assertFalse("the recovery tier must not be retired — its card has to render after the download", model.retired)
        assertFalse("nor an instrument — the recovery is the app choosing, so it must choose a rung it stands behind", model.instrument)
        assertTrue("it is in the pickable lineup", WhisperCatalog.pickable.any { it.id == model.id })
        assertEquals("and it is the catalogue default", WhisperCatalog.DEFAULT_MODEL_ID, model.id)
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
                val note = NpuTierStatus.cardNote(reason, installed, stillSelected = true, vendor = NpuVendor.QUALCOMM)
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
    // ------------------------------------------------ P3a: the MediaTek families' side of the card

    /**
     * THE DECLINE NOTE STATES THE SPEED HALF ONLY WHERE IT WAS MEASURED (P3a). "It is slower" is
     * measured on Qualcomm silicon (the Fold6's NPU commit against the CPU tier) and FALSE on a
     * MediaTek family: the Tab S10+'s CPU fallbacks commit faster than its APU turbo (`small-q8`
     * 1,217 ms, `medium-q8` 1,341 ms against ~2.3 s — docs/measurements/2026-09-17-tab-cpu-ladder.md
     * and 2026-09-24-tab-apu-turbo-encoder.md §6). So a MediaTek decline — the chip-stamp mismatch
     * or a refused restore at `init`, a failed encode — says the accuracy half alone, and its
     * sentences are pinned exactly here. The Qualcomm note is byte-for-byte what it was.
     */
    @Test
    fun aMediatekDeclineNoteClaimsNoSpeedAndTheQualcommNoteIsUnchanged() {
        val stamp = "init: stamp: /data/user/0/com.whispereverywhere/files/models/turbo_encoder_qairt_context.bin " +
            "was compiled for MediaTek / mt6991; this family is MediaTek / mt6989"
        assertEquals(
            "The AI chip is unavailable on this device right now (stage: init), so speech is running " +
                "on the multilingual CPU model. It is a little less accurate than the AI chip model. " +
                "Restart the app to try the AI chip again.",
            NpuTierStatus.cardNote(stamp, true, stillSelected = true, vendor = NpuVendor.MEDIATEK),
        )
        assertEquals(
            "The AI chip is unavailable on this device right now (stage: encode), so speech is running " +
                "on the multilingual CPU model. It is a little less accurate than the AI chip model. " +
                "Pick it again on this screen to try the AI chip.",
            NpuTierStatus.cardNote("encode: run failed", true, stillSelected = false, vendor = NpuVendor.MEDIATEK),
        )
        assertEquals(
            "the Qualcomm note, unchanged",
            "The AI chip is unavailable on this device right now (stage: init), so speech is running " +
                "on the multilingual CPU model. It is slower, and a little less accurate than the AI chip " +
                "model. Restart the app to try the AI chip again.",
            NpuTierStatus.cardNote("init: nativeInit failed at 0", true, stillSelected = true, vendor = NpuVendor.QUALCOMM),
        )
        // Off the census no chip ever arms, so no decline is reachable there — and if one were,
        // it would get the claim-free note, never the measured-on-Qualcomm one.
        assertEquals(
            NpuTierStatus.cardNote(stamp, true, stillSelected = true, vendor = NpuVendor.MEDIATEK),
            NpuTierStatus.cardNote(stamp, true, stillSelected = true, vendor = null),
        )
        // The no-fallback arm made no speed claim to begin with: identical on every vendor.
        for (selected in listOf(true, false)) {
            assertEquals(
                NpuTierStatus.cardNote(stamp, false, stillSelected = selected, vendor = NpuVendor.QUALCOMM),
                NpuTierStatus.cardNote(stamp, false, stillSelected = selected, vendor = NpuVendor.MEDIATEK),
            )
        }
        // The claim rules over every note a MediaTek device can read.
        val speed = Regex(
            "\\b(fast|faster|fastest|quick|quicker|quickest|slow|slower|slowest|instant|instantly|" +
                "real-time|realtime|sooner|responsive)\\b",
        )
        for (installed in listOf(true, false)) {
            for (selected in listOf(true, false)) {
                val note = NpuTierStatus.cardNote(stamp, installed, stillSelected = selected, vendor = NpuVendor.MEDIATEK)!!
                assertNull("<<$note>> claims speed on a MediaTek row", speed.find(note.lowercase())?.value)
                assertFalse(
                    "<<$note>> says phone or tablet",
                    note.lowercase().contains("phone") || note.lowercase().contains("tablet"),
                )
            }
        }
        assertNull("the recovery's own sentence claims no speed", speed.find(NpuTierStatus.RECOVERY_SWITCH_NOTE.lowercase())?.value)
    }

    /**
     * A MEDIATEK REFUSAL IS READ IN THE DIAG AND INVENTS NO CARD (P3a; design §2.3). The driver
     * check's refusals — `adapter-missing`, `adapter-<name>`, `driver-major-<got>-want-<want>`,
     * `probe-crashed` — refuse the tier at the GATE: no backend is ever built, nothing publishes a
     * reason here, and the tier is simply not offered, which is the QNN behaviour for a failed
     * probe. What a reader has is the diag: the offer line's `probe=fail:<reason>` and the process's
     * `apu: verdict=refuse(<reason>)`, both carrying the word verbatim. The chip-stamp mismatch is
     * different in kind: it refuses at `init`, a stage, so it IS a decline and gets the note above.
     */
    @Test
    fun aMediatekRefusalIsReadableInTheDiagAndReachesNoCard() {
        val refusals = listOf(
            "adapter-missing", "adapter-libneuron_adapter_mgvi.so", "driver-major-9-want-8",
            NpuApuDriverCheck.PROBE_CRASHED,
        )
        for (word in refusals) {
            val verdict = NpuApuVerdict(
                fingerprint = "samsung/gts10pxx/gts10p:16/X828USQS6CZA3:user/release-keys",
                appBuild = 113, appUpdatedAtMs = 1L, wantMajor = 8, refusal = word, probedAtMs = 2L,
            )
            val offer = NpuDiag.offer("MT6989", true, false, setOf("npu-turbo"), NpuDiag.OfferDriverCheck(verdict))
            assertTrue("the offer line carries <<$word>>: $offer", offer.contains("probe=fail:$word"))
            assertTrue("…and offers nothing: $offer", offer.endsWith("offered=none"))
            val line = NpuDiag.apuVerdict(verdict, reused = false)
            assertTrue("the verdict line carries <<$word>>: $line", line.contains("verdict=refuse($word)"))
            // Never a card: the record the card reads is empty — a refusal publishes nothing.
            assertTrue(NpuTierStatus.declinedTiers.isEmpty())
            assertNull(
                NpuTierStatus.cardNote(
                    NpuTierStatus.reasonFor("npu-turbo"), true, stillSelected = true, vendor = NpuVendor.MEDIATEK,
                ),
            )
        }
        // Structurally: nothing that produces a driver verdict can reach the card's record.
        for (relative in listOf(
            "src/main/java/com/whispereverywhere/npu/NpuApuDriverCheck.kt",
            "src/main/java/com/whispereverywhere/npu/NpuGate.kt",
            "src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt",
            "src/main/java/com/whispereverywhere/transcription/LiteRtAsrEngine.kt",
        )) {
            val live = source(relative).lines()
                .filterNot { it.trimStart().let { t -> t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") } }
            assertEquals("$relative publishes to the card's record", 0, live.count { it.contains("NpuTierStatus.") })
        }
    }

    private fun source(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(java.io.File(dir, relative), java.io.File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }
}
