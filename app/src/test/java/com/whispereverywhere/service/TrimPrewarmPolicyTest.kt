package com.whispereverywhere.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "May the prewarm re-arm now?" — the one decision the post-trim re-arm takes (4.4.0 startup
 * amendment, Task S1). Pure, so the six-state table is read here instead of out of a 4,000-line
 * service, and so the gate can be shown to be the SAME gate the model-switch re-prewarm already
 * applies: IDLE or ERROR, i.e. *"mid-session triggers are skipped, never deferred"*
 * (`FloatingBubbleService` onCreate's re-prewarm collector).
 *
 * Why the state is the WHOLE input. The other two conditions the amendment names are already
 * respected, and respected elsewhere: "a local tier selected" is `LocalWhisperEngine.prewarm`'s own
 * first line (`installedModelPath() ?: return`), and no metered/battery-saver constraint exists on
 * either existing prewarm trigger — so re-deciding either here would be inventing policy, not
 * matching it. The trim LEVEL is likewise not re-decided: it belongs to the release guard in
 * `onTrimMemory`, which is what makes a re-arm reachable at all.
 */
class TrimPrewarmPolicyTest {

    @Test
    fun anIdleServiceRearms() {
        // The case the owner's report is about: the trim fired while nothing was happening, the
        // context went away, and the next tap would otherwise pay the full cold load (4,107 ms
        // measured on npu-turbo — docs/superpowers/research/2026-09-10-startup-cutoff-investigation.md).
        assertTrue(prewarmRearmsAfterTrim(FloatingBubbleService.BubbleState.IDLE))
    }

    @Test
    fun anErrorStateRearmsToo() {
        // ERROR is an idle state with a red bubble: no session owns the context, and the user's
        // next action is a tap. The model-switch collector treats it identically.
        assertTrue(prewarmRearmsAfterTrim(FloatingBubbleService.BubbleState.ERROR))
    }

    @Test
    fun aSessionInFlightIsSkippedAndNeverDeferred() {
        // A trim can only release the context outside RECORDING/FINALIZING/CONNECTING, but the
        // re-arm fires later, so a session can have STARTED in between. Skipping loses nothing:
        // the thing that starts a session is the thing that loads the context, so that session's
        // own connect() re-warms the slot this re-arm would have filled.
        assertFalse(prewarmRearmsAfterTrim(FloatingBubbleService.BubbleState.CONNECTING))
        assertFalse(prewarmRearmsAfterTrim(FloatingBubbleService.BubbleState.RECORDING))
        assertFalse(prewarmRearmsAfterTrim(FloatingBubbleService.BubbleState.FINALIZING))
        assertFalse(prewarmRearmsAfterTrim(FloatingBubbleService.BubbleState.PROCESSING))
    }

    @Test
    fun theTableIsTotalAndExactlyTwoStatesRearm() {
        // A seventh BubbleState must trip a deliberate red here rather than silently inherit a
        // yes: the loads this gate guards are 342 MiB to 1.02 GiB of native context.
        assertEquals(
            listOf(FloatingBubbleService.BubbleState.IDLE, FloatingBubbleService.BubbleState.ERROR),
            FloatingBubbleService.BubbleState.values().filter { prewarmRearmsAfterTrim(it) },
        )
    }
}
