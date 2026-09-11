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

    fun onPartial(text: String): String {
        partial = text
        return compose()
    }

    /** A commit for [seq]: its padded text is frozen; the partial (which it supersedes) resets. */
    fun freeze(seq: Long, text: String): String {
        if (text.isNotBlank()) frozen[seq] = text
        partial = ""
        return compose()
    }

    /** `local` resolved [seq] (Text, EmptyExpected, Lost alike): its prefix — and anything older — leaves the strip. */
    fun resolve(seq: Long): String {
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

    fun reset() {
        frozen.clear()
        partial = ""
    }
}
