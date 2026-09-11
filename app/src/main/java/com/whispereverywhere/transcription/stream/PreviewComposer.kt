package com.whispereverywhere.transcription.stream

import java.util.TreeMap

/**
 * The strip's composition rule (spec §4.2): `frozen[every committed-but-unresolved seq, oldest
 * first] + " " + partial` — "what the window does not have yet". Whisper's final for seq N lands
 * 2-6 s after N's cut (F ≈ 2.3 s Fold6 CPU, ≤ 6 s Tab, ≈ 1.9 s turbo) and the strip is
 * replace-only, so N's words stay on the strip until N resolves, and N+1's partial grows behind
 * them. A blank composition is the strip's "nothing pending" (parked INVISIBLE by the service).
 *
 * Not thread-safe by itself: `PreviewTeeEngine` serialises the three writers (the preview
 * executor's partial/freeze, the local executor's resolve) under one lock.
 */
class PreviewComposer {
    private val frozen = TreeMap<Long, String>()
    private var partial = ""

    /**
     * The highest seq [resolve] has swept; `-1` until the session's first resolution, the
     * spelling of `LocalWhisperEngine.NO_SEGMENT` (seqs start at 0, LocalWhisperEngine.kt:75).
     * A freeze at or below it is refused — see [freeze].
     */
    private var resolvedThrough = -1L

    fun onPartial(text: String): String {
        partial = text
        return compose()
    }

    /**
     * A commit for [seq]: its padded text is frozen; the partial (which it supersedes) resets.
     *
     * A freeze for an ALREADY-RESOLVED seq stores NOTHING (review B1). The two arms do not race
     * fairly — on a segment whisper skips, the resolution always wins: `local.commit` on evidence
     * under `EndpointerTuning.MIN_SPEECH_EVIDENCE_MS` (192 ms, EndpointerTuning.kt:197) never
     * decodes and resolves `SegmentOutcome.EmptyExpected` off local's executor within ~1 ms of
     * the cut (LocalWhisperEngine.kt:375-382), and a `Lost` on a missing context
     * (LocalWhisperEngine.kt:431) is just as immediate, while `onFrozen` comes back only after
     * PAD_MS of zeros plus a drain plus a decode (StreamingPreviewEngine.kt:166-205). Stored
     * unconditionally, that late freeze re-inserts a seq [resolve] has already swept and nothing
     * but a LATER resolution can reach it — so the strip would LEAD with words whisper has
     * declared it will never type, from the freeze until some seq >= N+1 resolves (a whole next
     * segment, up to the 15 s cap, plus F). Non-blank text there is not hypothetical: the
     * previewer's Zipformer has no VAD, and the retained tail re-fed to the fresh stream is
     * emitted as a partial (StreamingPreviewEngine.kt:198-204 — `priming` suppresses only the
     * accounting), so a sub-floor segment can carry real words into its freeze.
     */
    fun freeze(seq: Long, text: String): String {
        if (text.isNotBlank() && seq > resolvedThrough) frozen[seq] = text
        partial = ""
        return compose()
    }

    /**
     * `local` resolved [seq] (Text, EmptyExpected, Lost alike): its prefix — and anything older —
     * leaves the strip, and nothing may freeze at or below it afterwards.
     *
     * Precondition: resolutions arrive in commit order. `LocalWhisperEngine` guarantees it (one
     * single-thread executor, through which `dispatch` routes BOTH the ran and the skipped arm),
     * and it is what makes "and anything older" a sweep rather than a leak — and the high-water
     * mark a mark rather than a blackhole. A tee over some other engine owes this check again.
     */
    fun resolve(seq: Long): String {
        if (seq > resolvedThrough) resolvedThrough = seq
        frozen.headMap(seq, true).clear()
        return compose()
    }

    fun compose(): String {
        val sb = StringBuilder()
        for (t in frozen.values) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
        }
        if (partial.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(partial)
        }
        return sb.toString()
    }

    fun pending(): Int = frozen.size

    /**
     * A new session. The mark goes with the window: seqs restart at 0 every session
     * (LocalWhisperEngine.kt:135), so a mark carried over would refuse the new session's first
     * segments outright.
     */
    fun reset() {
        frozen.clear()
        partial = ""
        resolvedThrough = -1L
    }
}
