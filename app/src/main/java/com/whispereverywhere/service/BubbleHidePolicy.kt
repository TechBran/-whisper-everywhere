package com.whispereverywhere.service

/**
 * Whether a request to hide the floating bubble may proceed right now (4.3.1 B).
 *
 * `FloatingBubbleService.hideBubble(reason)` is the single sink every hide goes through, and this
 * is the single decision it takes. The rule exists because a read-aloud leaves `currentState` at
 * IDLE — only `isSpeakingNow` marks the read — and two callers (a text-field unfocus driven by any
 * foreign window event, and a media-stopped) hid the pill mid-read while the audio played on
 * (owner 2026-09-01). Pure so the guard order is a truth table in `BubbleHidePolicyTest`.
 *
 * Order: always-on never hides; a bubble that is not visible has nothing to hide and nothing to
 * park; a read in progress DEFERS (the reason is parked and replayed when the read ends); else hide.
 */
object BubbleHidePolicy {

    enum class Decision { IGNORE, DEFER, HIDE }

    fun decide(speaking: Boolean, alwaysOn: Boolean, visible: Boolean): Decision = when {
        alwaysOn -> Decision.IGNORE
        !visible -> Decision.IGNORE
        speaking -> Decision.DEFER
        else -> Decision.HIDE
    }

    /**
     * When the read ends, a parked hide replays only where the bubble would have hidden anyway:
     * never in always-on, never off a focused text field (that bubble is the user's). A
     * clipboard-summoned bubble — context NONE — therefore still leaves after the read, which is
     * what the summon promises ("for long enough to tap the pulsing speaker lobe, then leave").
     */
    fun replay(contextIsTextField: Boolean, alwaysOn: Boolean): Boolean =
        !alwaysOn && !contextIsTextField
}
