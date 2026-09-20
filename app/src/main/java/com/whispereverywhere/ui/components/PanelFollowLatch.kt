package com.whispereverywhere.ui.components

/**
 * DOES THE TRANSCRIPT PANEL FOLLOW ITS NEWEST LINE? — one boolean, and the only thing allowed to
 * change it is a finger.
 *
 * ### The defect this replaces (owner, 2026-09-20)
 *
 * > "after a while … the scrolling of the window will just drift off and then not follow the
 * > bottom. If the user scrolls to the bottom, then the bottom should be locked to automatically
 * > scrolling on committed text. But if you scroll away … stay where you are. But if you go back
 * > down to the bottom, then you should definitely be carried with the committed text."
 *
 * Through 4.11.2 the panel answered that question fresh on every repaint: read `scrollY`, compare
 * it against the layout, and carry the reader only if the two said "at the bottom"
 * (`TranscriptScrubberMath.atBottom` + a since-deleted `followScrollY`). That comparison spans a
 * text change, and the two halves come apart. `TranscriptSink._preview` is a `MutableStateFlow`
 * with four repaint triggers, collected with `collectLatest`, and the scroll correction is
 * deferred to a `post {}`; but a fixed-size `TextView` rebuilds its layout INSIDE `setText`. So a
 * repaint arriving before that post runs reads the OLD `scrollY` against the NEW, taller layout.
 * One commit adds far more than the slack, the comparison says "not at the bottom", and the panel
 * stops following for the rest of the session — a relabel landing right after a commit is enough,
 * which is why it took a while and never recovered.
 *
 * ### Why a latch fixes it rather than papering over it
 *
 * Nothing here is derived across a text change, so there is no window in which two sources of
 * truth can disagree. The state moves only on [arm] (a new session) and [onUserScroll] (a finger
 * that has landed), and [targetScrollY] is a pure read.
 *
 * ### The one rule the caller must keep
 *
 * [onUserScroll] means A FINGER DID THIS. The panel's own scroll fires the same
 * `View.OnScrollChangeListener` as a drag of the text, so the caller has to fence its own
 * `scrollTo` and not report it — see `FloatingBubbleService.scrollPanelTo`. Reporting a
 * programmatic scroll here would re-create the defect in a new place: the app would be answering
 * the question again instead of the user.
 *
 * Per-session state, so this is a class with an [arm] rather than an object — the house shape for
 * anything that remembers (compare `LanguagePin`, `ProjectionConsentBudget`).
 */
class PanelFollowLatch {

    /**
     * TRUE while the panel should ride its newest line. Starts true: a session that has printed
     * nothing is at its own bottom, and the first thing a reader sees should be the newest words.
     */
    var following: Boolean = true
        private set

    /** A new session: the panel is empty and the reader is, by definition, at the end of it. */
    fun arm() {
        following = true
    }

    /**
     * A FINGER HAS MOVED THE PANEL and this is where it landed — from a drag of the text
     * (`ScrollingMovementMethod`) or of the scrubber, which are the only two ways a user can
     * scroll it.
     *
     * Landing within [slackPx] of the bottom re-arms; landing anywhere above it disarms. Both
     * directions, every time, which is what makes "go back down to the bottom and you are carried
     * again" true without anything else having to remember that the reader once left.
     *
     * Content that fits has `maxScroll == 0` and is at the bottom by definition, so a session
     * whose text does not yet overflow the panel can never be disarmed by a stray touch.
     *
     * @param slackPx how close to the bottom still counts as riding it, in PIXELS, passed per
     *        call rather than held. Density is a runtime fact and it is not constant for the life
     *        of a session: a fold changes displays, and the inner and cover screens of a Z Fold6
     *        do not share one. A latch that snapshotted the density at construction would carry
     *        the wrong slack for the rest of the session after an unfold. Slack is not
     *        sloppiness: a view can sit a pixel or two off its own maximum after a layout pass,
     *        and a reader one partial line from the end means "keep going" as plainly as one
     *        exactly on it.
     */
    fun onUserScroll(scrollY: Int, maxScroll: Int, slackPx: Int) {
        following = TranscriptScrubberMath.atBottom(scrollY, maxScroll, slackPx)
    }

    /**
     * WHERE THE PANEL BELONGS NOW, or NULL to leave it exactly where it is.
     *
     * Following: the bottom. Not following: **null, unless the view is stranded past the end of
     * its own content** — and that exception is not tidiness, it is a defect this class shipped
     * without and a review caught.
     *
     * `TextView` does not clamp `scrollY` when `setText` shortens the content or when the view
     * grows, and the deleted `followScrollY` used to clamp on every repaint
     * (`previousScrollY.coerceIn(0, maxScroll)`). Without that, a reader who had scrolled up and
     * then resized the panel taller — or whose content shrank, which an ordinary per-chunk
     * `applyRemap` does by collapsing paragraph breaks and labels — was left with `scrollY`
     * beyond `maxScroll`, drawing blank space where the transcript should be, with no bar to drag
     * back (the scrubber hides itself once the content fits) and nothing to heal it until the
     * next session.
     *
     * Returning null when the offset IS in range still matters: a reader who is where they want
     * to be is never written to at all, so no clamp, no rounding, no one-pixel nudge that a later
     * [onUserScroll] could misread as a deliberate landing at the bottom.
     */
    fun targetScrollY(maxScroll: Int, currentScrollY: Int): Int? {
        val max = maxScroll.coerceAtLeast(0)
        if (following) return max
        return if (currentScrollY > max) max else null
    }
}
