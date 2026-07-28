package com.whispereverywhere.transcription

/**
 * How one committed audio segment ended. EVERY allocated seq must reach exactly one of these —
 * a seq that never resolves stalls the [SegmentOrderer] head forever and holds every later
 * segment with it.
 *
 * The distinction between the two empties is load-bearing: silence that VAD proved had nothing in
 * it is not a loss and must contribute nothing, whereas real voiced audio that came back empty is
 * a lost sentence the user needs to see.
 */
sealed interface SegmentOutcome {
    data class Text(val text: String) : SegmentOutcome
    /** VAD/energy proved there was nothing to transcribe. Silent, contributes nothing. */
    data object EmptyExpected : SegmentOutcome
    /** Real voiced audio produced no text. A lost sentence — must be visible. */
    data object EmptyUnexpected : SegmentOutcome
    /** Terminally lost after every engine failed or was unavailable. */
    data class Lost(val reason: String) : SegmentOutcome
}
