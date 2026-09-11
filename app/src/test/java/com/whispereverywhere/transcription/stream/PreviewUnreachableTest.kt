package com.whispereverywhere.transcription.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * THE STANDING CAVEAT, over the whole of its two inputs (4.5.0 Task 4).
 *
 * The enumeration this task exists for has four axes — {selection} × {packs installed} ×
 * {tier installed} × {cloud-only} — and this is the one decision that answers the first and the
 * third TOGETHER, which is the whole reason it exists: 4.4.1 answered the selection axis (*"no
 * language may be offered a model that has already been decided cannot arm for it"*) and left the
 * DEVICE axis unanswered, so a phone that can never run the previewer at all was still offered
 * its 73 MB and still told that words would appear.
 */
class PreviewUnreachableTest {

    private val bools = listOf(false, true)

    @Test fun theTierOutranksTheSelectionEverywhereInTheProduct() {
        for (tier in bools) for (pack in bools) {
            val answer = PreviewUnreachable.of(
                localTierInstalled = tier,
                hasPackForSelection = pack,
            )
            val expected = when {
                // The tier is FIRST and it is absolute. With no on-device speech model every
                // session is a cloud session, `localPreviewArms` refuses on `!isCloudSession`,
                // and NO pick can change that — so naming the selection there would tell the
                // user to do something that cannot help them.
                !tier -> PreviewUnreachable.NO_LOCAL_TIER
                !pack -> PreviewUnreachable.NO_PACK_FOR_SELECTION
                else -> null
            }
            assertEquals("tier=$tier pack=$pack", expected, answer)
        }
    }

    @Test fun bothFactsMissingIsTheTIERsCaseAndNotTheSelectionS() {
        // The cell that makes the ordering load-bearing rather than tidy: a cloud-only user
        // standing on Auto. `noLiveWordsSubtitle(null)` instructs them to *"pick your
        // transcription language to see words on the bubble as you speak"* — which is false for
        // them on every language, so the instruction has to be outranked rather than merely
        // joined.
        assertEquals(
            PreviewUnreachable.NO_LOCAL_TIER,
            PreviewUnreachable.of(localTierInstalled = false, hasPackForSelection = false),
        )
    }

    @Test fun aDeviceThatCanArmAndAPickWithAPackIsNotBlockedAtAll() {
        assertNull(PreviewUnreachable.of(localTierInstalled = true, hasPackForSelection = true))
    }

    @Test fun theEnumIsTheTwoStandingFactsAndNoMomentaryOne() {
        // Two values, deliberately: a STANDING fact about this device or this selection, which is
        // what a caveat row may be drawn from. The momentary facts (a session running, a batch
        // job, a transfer in flight, the switch, the previewer's per-process verdict) are answered
        // by `PreviewAutoFetch.decide`, `PreviewAutoFetch.card` and
        // `StreamingPackCopy.selectorLine`; a third value here would be a second answer to a
        // question those already own.
        assertEquals(
            listOf(
                PreviewUnreachable.NO_LOCAL_TIER,
                PreviewUnreachable.NO_PACK_FOR_SELECTION,
            ),
            PreviewUnreachable.entries.toList(),
        )
    }
}
