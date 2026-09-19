package com.whispereverywhere.transcription

import com.whispereverywhere.transcription.speakers.SpeakerSpan
import com.whispereverywhere.transcription.speakers.SpeakerWindow

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
    /**
     * Committed text, and — since 4.10 — optionally the speaker geometry it came out of.
     *
     * [text] IS THE CONTRACT AND IS UNCHANGED. Every delivery surface reads it and nothing else;
     * the two fields below are additive, default to null, and no reader is required to look at
     * them. A `SegmentOutcome.Text("hello")` written before 4.10 still constructs, still compares
     * equal to one this engine produces on a backend without geometry, and still delivers the
     * same bytes.
     *
     * @param spans the chunk's text cut into `(windowIndex, text)` runs, in TEXT order.
     *        Concatenating them under `TextJoin` reproduces [text] for every chunk whose geometry
     *        was coherent; nothing depends on that, which is the point — a wrong span costs a
     *        paragraph break, never a word.
     * @param windows the chunk's FINGERPRINT WINDOWS, in CHUNK order, so the assigner can find
     *        the raw audio each span was decoded from. `spans`' `windowIndex` indexes THIS list.
     *        One window per VAD segment, except for the long ones spike session 4 splits along
     *        whisper's own boundaries — the two lists are carried as ONE because a second,
     *        independently recomputed partition that disagreed with this one would put the ids
     *        of one window onto another's text.
     *
     * BOTH NULL OR BOTH SET, and null means "no geometry for this chunk" — the cloud engines, the
     * NPU tier while it is live, every fake, and any chunk the VAD did not run on. Null is NOT
     * "one speaker": a chunk with no segments has no timeline to attribute text to, and reading
     * it as a single speaker would put a `Speaker 1:` label on a session that never earned one.
     */
    data class Text(
        val text: String,
        val spans: List<SpeakerSpan>? = null,
        val windows: List<SpeakerWindow>? = null,
    ) : SegmentOutcome
    /** VAD/energy proved there was nothing to transcribe. Silent, contributes nothing. */
    data object EmptyExpected : SegmentOutcome
    /** Real voiced audio produced no text. A lost sentence — must be visible. */
    data object EmptyUnexpected : SegmentOutcome
    /** Terminally lost after every engine failed or was unavailable. */
    data class Lost(val reason: String) : SegmentOutcome
}
