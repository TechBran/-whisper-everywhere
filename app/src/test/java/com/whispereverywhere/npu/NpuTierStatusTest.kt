package com.whispereverywhere.npu

import org.junit.After
import org.junit.Assert.assertEquals
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
        // proved.
        NpuTierStatus.publish("npu-turbo", "init: could not deserialise the 740 MB encoder")
        val note = NpuTierStatus.cardNote(NpuTierStatus.reasonFor("npu-turbo"))!!
        assertTrue("the note names the stage of THIS tier's decline: $note", note.contains("init"))
        assertTrue("and the way back: $note", note.contains("Restart the app"))
        assertNull(
            "while the sibling tier — never declined — composes NO note from its own null " +
                "record, which is what keeps the warning off the card it is not about",
            NpuTierStatus.cardNote(NpuTierStatus.reasonFor("npu")),
        )
    }
}
