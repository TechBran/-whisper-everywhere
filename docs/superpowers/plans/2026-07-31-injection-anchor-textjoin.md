# Session-Anchored Injection + TextJoin Formatting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kill the mid-dictation cursor-teleport corruption and the melt-together formatting by moving all cursor placement into a pure session-anchored state machine and routing every text join through one pure melt-proof policy.

**Architecture:** Two new pure, Android-free, JVM-tested objects in `com.whispereverywhere.text` — `TextJoin` (the boundary-spacing policy) and `InjectionAnchor` (the per-session cursor state machine that ignores the live app cursor). The accessibility service consumes them thinly: one unified SET_TEXT injection path replaces the two duplicated sites, pins the cursor with `ACTION_SET_SELECTION` after each write, and never reads the live selection or runs the old delete branch again. Part 2 routes the enumerated assembly sites through `TextJoin`, leaving ordering/exactly-once/loss semantics untouched.

**Tech Stack:** Kotlin, Android AccessibilityService, JUnit4 (plain, `unitTests.isReturnDefaultValues = true`), kotlinx.serialization. No new dependencies; OkHttp pinned.

## Global Constraints

- Build: `java` is NOT on PATH. Every gradle command is `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"` then `.\gradlew.bat --no-daemon <task>`. OneDrive can lock `app/build`; retry once.
- **NEVER** `connectedAndroidTest` or `installDebug`. Instrumented code is compile-check only. `assembleRelease` (R8) must stay green.
- No new dependencies. `org.json` BANNED. Framework classes (`AccessibilityNodeInfo`) are untestable in JVM — the anchor machine and `TextJoin` are PURE and the service consumes them thinly.
- Branch `main`-derived `feature/on-device-whisper`; commit ONLY named files; never `git add -A`; retry once on `index.lock`.
- Baseline: **775 tests / 0 failures**, version 3.3.0/73. Every existing test keeps its meaning.
- **The melt invariant (absolute):** after this wave no two alphanumeric runs may touch across any chunk boundary on any SET_TEXT surface; no double spaces; a chunk starting with closing punctuation `.,!?;:)]}` attaches with no leading space.
- **Re-sync never deletes.** The ONLY deletion the injector may ever perform is the one-shot session-start selection replace (user selected text, then dictated = replace intent). The live-selection delete branch DIES.
- `ACTION_SET_SELECTION` pin after every successful `SET_TEXT`; its failure is non-fatal (count/label log only, never content).
- No credential/content logging; no speed claims. Clipboard/doc/social/FB-mention paste paths are a documented carve-out — the anchor cannot govern PASTE and does not touch those branches.

---

## File Structure

- **Create** `app/src/main/java/com/whispereverywhere/text/TextJoin.kt` — pure boundary-spacing policy object. One responsibility: decide spacing between two text runs.
- **Create** `app/src/main/java/com/whispereverywhere/text/InjectionAnchor.kt` — pure per-session cursor state machine. Depends only on `TextJoin`.
- **Create** `app/src/test/java/com/whispereverywhere/text/TextJoinTest.kt`
- **Create** `app/src/test/java/com/whispereverywhere/text/InjectionAnchorTest.kt`
- **Modify** `app/src/main/java/com/whispereverywhere/service/WhisperAccessibilityService.kt` — unify the two SET_TEXT sites into one anchor-driven path; add `sessionAnchor` state + `pinCursor`; remove the delete branch and live-cursor read.
- **Modify** `app/src/main/java/com/whispereverywhere/transcription/cloud/GeminiStt.kt:84` — melt-fix the multi-part join.
- **Modify** `app/src/main/java/com/whispereverywhere/transcription/SegmentOrderer.kt` — route the two space decisions through `TextJoin`, gates unchanged.
- **Modify** `app/src/main/java/com/whispereverywhere/recording/RecordingStore.kt:70-73` and `app/src/main/java/com/whispereverywhere/transcription/batch/BatchTranscriber.kt:297` — route through `TextJoin.assemble`.
- **Modify** `app/src/main/java/com/whispereverywhere/transcription/TranscriptSink.kt:26-28` and `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt:2076-2079` — route segment normalization/join through `TextJoin`, append shape unchanged.
- **Modify** `docs/PLAY-DECLARATIONS.md` — ledger entry.
- **Unchanged (verified safe, guard-tested):** `SonioxStt.kt:146`, `SonioxRealtimeProtocol.kt:207`, ElevenLabs/OpenAI realtime protocols — tokens/turns carry their own spacing.

---

## Task 1: `TextJoin` — the pure melt-proof spacing policy

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/text/TextJoin.kt`
- Test: `app/src/test/java/com/whispereverywhere/text/TextJoinTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `const val TextJoin.CLOSING: String` = `".,!?;:)]}"`
  - `fun TextJoin.normalize(chunk: String): String` — `chunk.trim()`.
  - `fun TextJoin.needsSpace(left: CharSequence, right: CharSequence): Boolean` — true iff a single space belongs between `left` and `right`.
  - `fun TextJoin.join(left: String, right: String): String` — `left` + optional space + `right`.
  - `fun TextJoin.assemble(chunks: List<String>): String` — normalize, drop blanks, pairwise-join.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/whispereverywhere/text/TextJoinTest.kt`:

```kotlin
package com.whispereverywhere.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextJoinTest {

    // --- needsSpace ---------------------------------------------------------

    @Test fun two_alphanumeric_runs_need_a_space() {
        assertTrue(TextJoin.needsSpace("Hello", "world"))
    }

    @Test fun an_already_trailing_space_needs_none() {
        assertFalse(TextJoin.needsSpace("Hello ", "world"))
    }

    @Test fun an_already_leading_space_needs_none() {
        assertFalse(TextJoin.needsSpace("Hello", " world"))
    }

    @Test fun closing_punctuation_attaches_without_a_space() {
        for (p in listOf(".", ",", "!", "?", ";", ":", ")", "]", "}")) {
            assertFalse("chunk starting '$p' must attach", TextJoin.needsSpace("Hello", p))
        }
    }

    @Test fun an_opening_bracket_still_needs_a_space() {
        assertTrue(TextJoin.needsSpace("Hello", "(note)"))
    }

    @Test fun empty_either_side_needs_no_space() {
        assertFalse(TextJoin.needsSpace("", "world"))
        assertFalse(TextJoin.needsSpace("Hello", ""))
    }

    // --- join ---------------------------------------------------------------

    @Test fun join_inserts_exactly_one_space_between_words() {
        assertEquals("Hello world", TextJoin.join("Hello", "world"))
    }

    @Test fun join_never_doubles_a_space() {
        assertEquals("Hello world", TextJoin.join("Hello ", "world"))
        assertEquals("Hello world", TextJoin.join("Hello", " world"))
    }

    @Test fun join_attaches_closing_punctuation() {
        assertEquals("Wait.", TextJoin.join("Wait", "."))
    }

    // --- assemble -----------------------------------------------------------

    @Test fun assemble_trims_drops_blanks_and_single_spaces() {
        // Pins RecordingStoreTest's "one three" (blank dropped, single space).
        assertEquals("one three", TextJoin.assemble(listOf("one ", "  ", " three")))
    }

    @Test fun assemble_would_fail_under_a_bare_concatenation() {
        // The Gemini melt bug in miniature: bare joinToString("") would give "Helloworld".
        assertEquals("Hello world", TextJoin.assemble(listOf("Hello", "world")))
    }

    @Test fun assemble_attaches_a_punctuation_chunk() {
        // Differs from an unconditional " " join, which would give "hello .".
        assertEquals("hello.", TextJoin.assemble(listOf("hello", ".")))
    }

    // --- no-op-safety proof for pre-spaced token streams (Soniox) -----------

    @Test fun join_is_a_no_op_over_a_pre_spaced_token_stream() {
        // Soniox emits its own spacing (space tokens and/or leading-space tokens). A defensive
        // reduce(join) must neither strip nor double it — this pins that guarantee so Soniox's
        // existing joinToString("") stays provably safe.
        val tokens = listOf("Hello", " ", "world")   // dedicated space token
        assertEquals("Hello world", tokens.reduce { a, b -> TextJoin.join(a, b) })
        val leading = listOf("Hello", " are", " you")  // leading-space tokens
        assertEquals("Hello are you", leading.reduce { a, b -> TextJoin.join(a, b) })
    }

    // --- the melt invariant, property-style ---------------------------------

    @Test fun melt_invariant_holds_across_generated_pairs() {
        val shapes = listOf("word", "cat", "a", ".", ",", "!", ")", "]", "(", "word ", " word")
        for (a in shapes) for (b in shapes) {
            val na = TextJoin.normalize(a)
            val nb = TextJoin.normalize(b)
            if (na.isEmpty() || nb.isEmpty()) continue
            val joined = TextJoin.assemble(listOf(na, nb))
            // No double space anywhere.
            assertFalse("double space in '$joined' from '$a'+'$b'", joined.contains("  "))
            // No two alphanumerics touching across the original boundary.
            val boundary = joined.indexOf(nb.first(), na.length - 1)
            if (na.last().isLetterOrDigit() && nb.first().isLetterOrDigit()) {
                assertTrue("melt in '$joined' from '$a'+'$b'", joined.contains("${na.last()} ${nb.first()}"))
            }
            assertTrue(boundary >= 0)
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest --tests "com.whispereverywhere.text.TextJoinTest"`
Expected: FAIL — `Unresolved reference: TextJoin`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/whispereverywhere/text/TextJoin.kt`:

```kotlin
package com.whispereverywhere.text

/**
 * The single melt-proof joining policy for the whole app. Given text runs, it decides the ONE
 * question every assembly site keeps re-answering ad hoc: does a space belong at this boundary?
 *
 * Rules (the melt invariant): two runs are joined so that
 *  - two alphanumeric characters never touch across the boundary,
 *  - a single space is inserted only when needed — never a double,
 *  - a run beginning with closing punctuation [CLOSING] attaches with NO leading space,
 *  - an existing space on either side is respected (the join is a no-op there).
 *
 * Pure and Android-free; every case is a JVM unit test.
 */
object TextJoin {

    /** A run starting with one of these attaches to the previous run WITHOUT a leading space. */
    const val CLOSING: String = ".,!?;:)]}"

    /** Trim a single chunk to the shape the join rules assume (no leading/trailing whitespace). */
    fun normalize(chunk: String): String = chunk.trim()

    /** True iff a single space belongs between [left]'s last char and [right]'s first char. */
    fun needsSpace(left: CharSequence, right: CharSequence): Boolean {
        if (left.isEmpty() || right.isEmpty()) return false
        val lc = left[left.length - 1]
        val rc = right[0]
        if (lc.isWhitespace() || rc.isWhitespace()) return false
        if (rc in CLOSING) return false
        return true
    }

    /** Join two runs verbatim, inserting one space only when [needsSpace]. */
    fun join(left: String, right: String): String =
        if (needsSpace(left, right)) "$left $right" else left + right

    /** Normalize, drop blank chunks, and pairwise-join a whole list under the melt invariant. */
    fun assemble(chunks: List<String>): String {
        val sb = StringBuilder()
        for (c in chunks) {
            val n = normalize(c)
            if (n.isEmpty()) continue
            if (sb.isNotEmpty() && needsSpace(sb, n)) sb.append(' ')
            sb.append(n)
        }
        return sb.toString()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest --tests "com.whispereverywhere.text.TextJoinTest"`
Expected: PASS (all TextJoinTest cases).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/text/TextJoin.kt app/src/test/java/com/whispereverywhere/text/TextJoinTest.kt
git commit -m "feat(text): TextJoin — the pure melt-proof boundary-spacing policy"
```

---

## Task 2: `InjectionAnchor` — the pure session cursor state machine

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/text/InjectionAnchor.kt`
- Test: `app/src/test/java/com/whispereverywhere/text/InjectionAnchorTest.kt`

**Interfaces:**
- Consumes: `TextJoin.needsSpace`, `TextJoin.normalize` (Task 1).
- Produces:
  - `class InjectionAnchor`
  - `InjectionAnchor.Placement(val newFieldText: String, val newSelection: Int)` — the full field string to SET_TEXT and the cursor index to pin.
  - `fun InjectionAnchor.start(fieldText: String, selStart: Int, selEnd: Int)` — begin a session at record start.
  - `fun InjectionAnchor.plan(fieldText: String, segment: String): Placement` — PURE (no mutation): compute the write for one segment.
  - `fun InjectionAnchor.commit(placement: Placement)` — adopt a placement AFTER a successful SET_TEXT (advances the anchor, clears the one-shot replace).
  - `val InjectionAnchor.anchor: Int`, `val InjectionAnchor.expectedText: String` — inspectable state (test/read only).

**Design (per owner brief):** The machine holds `anchor` (where WE last left the cursor), `expectedText` (exactly what WE last wrote), and `pendingReplace` (a start-only selection range, honored at most once). `plan` ignores the live app cursor entirely: if the field still equals `expectedText`, it inserts at OUR anchor; if the user edited mid-session it RE-SYNCs (longest common prefix/suffix; fallback = end), and NEVER deletes. Spacing guards BOTH boundaries via `TextJoin`. `plan` is pure so a failed SET_TEXT leaves state untouched (retry-safe); `commit` is the only mutator.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/whispereverywhere/text/InjectionAnchorTest.kt`:

```kotlin
package com.whispereverywhere.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectionAnchorTest {

    private fun place(a: InjectionAnchor, field: String, seg: String): InjectionAnchor.Placement {
        val p = a.plan(field, seg)
        a.commit(p)
        return p
    }

    // --- start --------------------------------------------------------------

    @Test fun start_on_empty_field_anchors_at_zero() {
        val a = InjectionAnchor()
        a.start("", -1, -1)
        assertEquals(0, a.anchor)
    }

    @Test fun start_with_no_selection_anchors_at_end() {
        val a = InjectionAnchor()
        a.start("Hello", -1, -1)
        assertEquals(5, a.anchor)
    }

    @Test fun start_with_a_caret_anchors_there_for_deliberate_mid_text() {
        val a = InjectionAnchor()
        a.start("Hello there", 5, 5)   // caret after "Hello"
        assertEquals(5, a.anchor)
    }

    // --- append flow (the common case) --------------------------------------

    @Test fun successive_segments_append_with_one_space_each() {
        val a = InjectionAnchor()
        a.start("", -1, -1)
        assertEquals("Hello", place(a, "", "Hello").newFieldText)
        assertEquals("Hello world", place(a, "Hello", "world").newFieldText)
        assertEquals("Hello world again", place(a, "Hello world", "again").newFieldText)
    }

    @Test fun the_pinned_cursor_sits_at_the_end_of_what_we_wrote() {
        val a = InjectionAnchor()
        a.start("Hello", -1, -1)
        val p = place(a, "Hello", "world")
        assertEquals("Hello world".length, p.newSelection)
    }

    // --- THE bug: app moved the cursor between segments ---------------------

    @Test fun a_moved_live_cursor_is_ignored_when_the_field_is_unchanged() {
        // plan takes NO live-cursor argument by design — the app can teleport the caret to 0 or
        // mid-text and we still append at OUR anchor. This is the fix.
        val a = InjectionAnchor()
        a.start("Hello", -1, -1)
        place(a, "Hello", "there")           // field now "Hello there", anchor at end
        val p = place(a, "Hello there", "friend")
        assertEquals("Hello there friend", p.newFieldText)
    }

    // --- both-boundary guard (mid-text) -------------------------------------

    @Test fun a_mid_text_insert_spaces_both_sides() {
        val a = InjectionAnchor()
        a.start("Hello there", 5, 5)          // caret after "Hello"
        val p = place(a, "Hello there", "big")
        assertEquals("Hello big there", p.newFieldText)
    }

    @Test fun a_mid_text_insert_creates_no_double_space() {
        val a = InjectionAnchor()
        a.start("Hello  there".take(6), 6, 6) // field "Hello " caret at 6 (trailing space)
        val p = place(a, "Hello ", "big")
        assertEquals("Hello big", p.newFieldText)
        assertTrue(!p.newFieldText.contains("  "))
    }

    @Test fun a_closing_punctuation_segment_attaches_clean() {
        val a = InjectionAnchor()
        a.start("Hello", -1, -1)
        val p = place(a, "Hello", ".")
        assertEquals("Hello.", p.newFieldText)
    }

    // --- one-shot replace ---------------------------------------------------

    @Test fun a_start_selection_range_is_replaced_exactly_once() {
        val a = InjectionAnchor()
        a.start("delete me keep", 0, 9)       // "delete me" selected
        val first = place(a, "delete me keep", "new")
        assertEquals("new keep", first.newFieldText)
        // Second segment must NOT delete anything — replace was one-shot.
        val second = place(a, "new keep", "again")
        assertEquals("new again keep", second.newFieldText)
    }

    @Test fun no_start_selection_means_no_deletion_ever() {
        val a = InjectionAnchor()
        a.start("keep all", 3, 3)             // caret only, no range
        val p = place(a, "keep all", "x")
        assertTrue("nothing deleted", p.newFieldText.contains("keep"))
        assertTrue(p.newFieldText.contains("all"))
    }

    // --- re-sync on user edit: NEVER deletes --------------------------------

    @Test fun a_user_edit_after_the_anchor_keeps_the_anchor() {
        val a = InjectionAnchor()
        a.start("abc", 3, 3)                  // anchor at end
        place(a, "abc", "d")                  // -> "abc d", anchor 5
        // User types " zzz" at the very end (after our anchor).
        val p = place(a, "abc d zzz", "e")
        assertTrue("user text intact", p.newFieldText.contains("zzz"))
        assertTrue(p.newFieldText.contains("abc"))
        assertTrue("nothing deleted", p.newFieldText.length >= "abc d zzz".length + 1)
    }

    @Test fun a_user_edit_before_the_anchor_shifts_the_anchor_forward() {
        val a = InjectionAnchor()
        a.start("abc", 3, 3)                  // anchor at end of "abc"
        // User prepends "X " before dictating again -> field "X abc".
        val p = place(a, "X abc", "d")
        assertEquals("X abc d", p.newFieldText)
    }

    @Test fun an_unrecognizable_edit_falls_back_to_the_end_and_never_deletes() {
        val a = InjectionAnchor()
        a.start("hello world", 11, 11)
        place(a, "hello world", "one")        // -> "hello world one"
        // User rewrites the middle wholesale (mention expansion): straddles the anchor.
        val edited = "hello @SomeName replaced entirely"
        val p = place(a, edited, "two")
        assertTrue("appended, nothing deleted", p.newFieldText.startsWith(edited))
        assertTrue(p.newFieldText.endsWith("two"))
    }

    // --- empty segment ------------------------------------------------------

    @Test fun an_empty_segment_is_a_no_op_that_leaves_the_field_and_anchor() {
        val a = InjectionAnchor()
        a.start("Hello", -1, -1)
        val p = place(a, "Hello", "   ")
        assertEquals("Hello", p.newFieldText)
        assertEquals(5, a.anchor)
    }

    // --- the property: placement never deletes, never melts -----------------

    @Test fun sequential_placement_never_shrinks_and_never_melts() {
        val segs = listOf("Hello", "world", ".", "again", ")", "more")
        val a = InjectionAnchor()
        a.start("", -1, -1)
        var field = ""
        for (s in segs) {
            val before = field.length
            val p = place(a, field, s)
            assertTrue("never shrinks", p.newFieldText.length >= before)
            assertTrue("no double space", !p.newFieldText.contains("  "))
            field = p.newFieldText
        }
        assertEquals("Hello world. again) more", field)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest --tests "com.whispereverywhere.text.InjectionAnchorTest"`
Expected: FAIL — `Unresolved reference: InjectionAnchor`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/whispereverywhere/text/InjectionAnchor.kt`:

```kotlin
package com.whispereverywhere.text

/**
 * Per-dictation-session cursor state machine. The bug it kills: every segment used to re-read the
 * LIVE app cursor and insert there, so any editor that moves the caret after our SET_TEXT
 * (Facebook mentions, drafts moving it to 0 or mid-text) corrupted the next segment.
 *
 * This machine remembers where WE left the cursor ([anchor]) and exactly what WE last wrote
 * ([expectedText]). It ignores the live cursor entirely. If the field still equals what we wrote,
 * it appends at our anchor. If the user edited mid-session it RE-SYNCs against the diff and NEVER
 * deletes. The one legal deletion is a selection range captured at [start] (user selected text
 * then dictated = replace intent), honored at most once.
 *
 * [plan] is pure (no mutation) so a failed SET_TEXT is retry-safe; [commit] is the only mutator
 * and is called ONLY after the write lands. Spacing guards BOTH boundaries via [TextJoin].
 *
 * Pure and Android-free; every case is a JVM unit test.
 */
class InjectionAnchor {

    var anchor: Int = 0
        private set
    var expectedText: String = ""
        private set
    private var pendingReplace: IntRange? = null

    /** What to write for one segment: the full new field string and the cursor to pin. */
    data class Placement(val newFieldText: String, val newSelection: Int)

    /**
     * Begin a session. [fieldText] is the field's current text (hint text already stripped by the
     * caller). A real selection RANGE ([selEnd] > [selStart]) is recorded as the one legal replace.
     */
    fun start(fieldText: String, selStart: Int, selEnd: Int) {
        anchor = if (selStart in 0..fieldText.length) selStart else fieldText.length
        expectedText = fieldText
        pendingReplace =
            if (selStart in 0..fieldText.length && selEnd in (selStart + 1)..fieldText.length) {
                selStart until selEnd
            } else null
    }

    /** Compute the write for [segment] against the field's current [fieldText]. Pure. */
    fun plan(fieldText: String, segment: String): Placement {
        val seg = TextJoin.normalize(segment)

        // Locate where our text goes. Replace range wins (start-only, unchanged field); else the
        // anchor if the field is still ours; else a non-deleting re-sync.
        val replace = pendingReplace?.takeIf { fieldText == expectedText }
        val removeStart: Int
        val removeEnd: Int
        val insertAt: Int
        if (replace != null) {
            removeStart = replace.first
            removeEnd = replace.last + 1
            insertAt = replace.first
        } else {
            removeStart = -1
            removeEnd = -1
            insertAt = if (fieldText == expectedText) {
                anchor.coerceIn(0, fieldText.length)
            } else {
                resync(fieldText)
            }
        }

        if (seg.isEmpty()) {
            // No-op: keep the field, hold the anchor at the resolved point.
            return Placement(fieldText, insertAt.coerceIn(0, fieldText.length))
        }

        val head = if (removeStart >= 0) fieldText.substring(0, removeStart) else fieldText.substring(0, insertAt)
        val tail = if (removeStart >= 0) fieldText.substring(removeEnd) else fieldText.substring(insertAt)

        val leftSpace = if (TextJoin.needsSpace(head, seg)) " " else ""
        val rightSpace = if (TextJoin.needsSpace(seg, tail)) " " else ""

        val newText = head + leftSpace + seg + rightSpace + tail
        val cursor = head.length + leftSpace.length + seg.length   // end of OUR text, before the right space
        return Placement(newText, cursor)
    }

    /** Adopt [placement] after a successful SET_TEXT: advance the anchor, clear the one-shot replace. */
    fun commit(placement: Placement) {
        anchor = placement.newSelection
        expectedText = placement.newFieldText
        pendingReplace = null
    }

    /**
     * The field no longer matches what we wrote — the user edited mid-session. Find where our
     * anchor moved WITHOUT ever deleting: keep it if the edit was after it, shift it if the edit
     * was entirely before it, else fall back to the field end (append). Never returns a delete.
     */
    private fun resync(fieldText: String): Int {
        val old = expectedText
        val maxLen = minOf(old.length, fieldText.length)
        var p = 0
        while (p < maxLen && old[p] == fieldText[p]) p++
        var s = 0
        while (s < maxLen - p &&
            old[old.length - 1 - s] == fieldText[fieldText.length - 1 - s]) s++
        return when {
            anchor <= p -> anchor                                   // edit after the anchor: unchanged
            anchor >= old.length - s -> fieldText.length - (old.length - anchor) // edit before it: shift
            else -> fieldText.length                                // edit straddles it: append (never delete)
        }.coerceIn(0, fieldText.length)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest --tests "com.whispereverywhere.text.InjectionAnchorTest"`
Expected: PASS (all InjectionAnchorTest cases).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/text/InjectionAnchor.kt app/src/test/java/com/whispereverywhere/text/InjectionAnchorTest.kt
git commit -m "feat(text): InjectionAnchor — pure session cursor state machine, never deletes on re-sync"
```

---

## Task 3: Service unification — one anchor-driven SET_TEXT path

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/service/WhisperAccessibilityService.kt`
  - Fields near `:39-40` (add `sessionAnchor`).
  - `beginInjectionSessionInternal` `:346-355` / `endInjectionSessionInternal` `:357-360`.
  - `injectTextToFocusedField` `:628-699` (collapse to delegate).
  - `injectTextWithResultInternal` `:987-1122` (SET_TEXT block replaced by anchor path).
  - Add `pinCursor` and `setTextViaAnchor` helpers.

**Interfaces:**
- Consumes: `com.whispereverywhere.text.InjectionAnchor` (Task 2), `com.whispereverywhere.text.TextJoin` (Task 1, indirectly).
- Produces: no signature changes to any public companion method (`injectText`, `injectTextWithResult`, `beginInjectionSession`, `endInjectionSession` unchanged).

**Why no unit test here:** `AccessibilityNodeInfo` is a framework class — untestable in JVM (`isReturnDefaultValues`). Correctness lives in the Task 1/2 pure tests; this task is verified by `assembleRelease` (R8) + the full unit suite staying green. The service consumes the machine thinly.

- [ ] **Step 1: Add the import and the session-anchor field**

Add to the imports block (top of file):

```kotlin
import com.whispereverywhere.text.InjectionAnchor
```

Add beside the existing session fields (`WhisperAccessibilityService.kt:39-40`):

```kotlin
    private var sessionTargetEditText: AccessibilityNodeInfo? = null
    private var sessionTargetPackage: String? = null
    // The cursor state machine for the active dictation session (null outside a session). Captured
    // at record start from the live field; every segment places against it, ignoring the live caret.
    private var sessionAnchor: InjectionAnchor? = null
```

- [ ] **Step 2: Capture the anchor at session start; drop it at session end**

Replace `beginInjectionSessionInternal` (`:346-355`) body's tail so the anchor is captured with the node, and clear it in `endInjectionSessionInternal`:

```kotlin
    private fun beginInjectionSessionInternal() {
        // Main-thread only (all begin/resolve/end callers run on the service main thread).
        val target = findFocusedEditText() ?: lastFocusedEditText
        sessionTargetEditText = target
        sessionTargetPackage = if (target != null) {
            target.packageName?.toString() ?: currentPackage
        } else null
        // Pin the cursor anchor from the field as it is RIGHT NOW. A selection range here is the
        // one legal replace (user selected text then started dictating). No field => no anchor.
        sessionAnchor = if (target != null) {
            val raw = target.text?.toString() ?: ""
            val hint = target.hintText?.toString() ?: ""
            val isHint = raw.isNotEmpty() && (raw == hint || raw.equals(hint, ignoreCase = true))
            val current = if (isHint) "" else raw
            InjectionAnchor().apply { start(current, target.textSelectionStart, target.textSelectionEnd) }
        } else null
    }

    private fun endInjectionSessionInternal() {
        sessionTargetEditText = null
        sessionTargetPackage = null
        sessionAnchor = null
    }
```

- [ ] **Step 3: Add the `pinCursor` and `setTextViaAnchor` helpers**

Add these private methods (place them just above `formatTextForInjection` at `:961`). `setTextViaAnchor` is the ONE SET_TEXT path both sites now share:

```kotlin
    /**
     * Pin the caret to [pos] after a successful SET_TEXT: resets the app's cursor games and makes
     * the next segment's anchor visibly correct. Non-fatal — a rejecting field just keeps its caret.
     */
    private fun pinCursor(node: AccessibilityNodeInfo, pos: Int) {
        try {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            if (!ok) android.util.Log.d(TAG, "SET_SELECTION rejected (non-fatal)")
        } catch (e: Exception) {
            android.util.Log.d(TAG, "SET_SELECTION threw (non-fatal)")
        }
    }

    /**
     * The unified SET_TEXT injection: read the field, plan the write through the session anchor
     * (or a transient anchor started from the live caret when there is no session), SET_TEXT the
     * full string, then pin the caret. The anchor is committed ONLY on a successful write, so a
     * failed SET_TEXT leaves the machine untouched and the clipboard fallback runs clean.
     *
     * The old live-selection read and the delete branch are GONE: the only deletion possible is the
     * anchor's one-shot start-selection replace.
     */
    private fun setTextViaAnchor(targetNode: AccessibilityNodeInfo, text: String): InjectionResult {
        val rawText = targetNode.text?.toString() ?: ""
        val hintText = targetNode.hintText?.toString() ?: ""
        val isHintText = rawText.isNotEmpty() && (rawText == hintText || rawText.equals(hintText, ignoreCase = true))
        val currentText = if (isHintText) "" else rawText

        val anchor = sessionAnchor ?: InjectionAnchor().apply {
            start(currentText, targetNode.textSelectionStart, targetNode.textSelectionEnd)
        }
        val placement = anchor.plan(currentText, text)

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, placement.newFieldText)
        }
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return if (success) {
            anchor.commit(placement)           // adopt only after the write lands
            pinCursor(targetNode, placement.newSelection)
            InjectionResult.SUCCESS
        } else {
            if (injectViaClipboard(text)) InjectionResult.SUCCESS else InjectionResult.CLIPBOARD_ONLY
        }
    }
```

Note: confirm `TAG` exists in the file; if the file uses a different constant (e.g. a string literal), match it. If no tag constant exists, use the literal `"WhisperAccessibilityService"`.

- [ ] **Step 4: Replace the SET_TEXT block in `injectTextWithResultInternal`**

In `injectTextWithResultInternal` (`:987-1122`), the doc-app branch (`:993-1030`), social branch (`:1032-1035`), no-target branch (`:1038-1052`), and no-SET_TEXT branch (`:1054-1069`) are UNCHANGED (clipboard/paste carve-outs). Replace ONLY the final `return try { ... }` SET_TEXT block (`:1071-1122`) with:

```kotlin
        return try {
            setTextViaAnchor(targetNode, text)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))
                InjectionResult.CLIPBOARD_ONLY
            } catch (e2: Exception) {
                InjectionResult.FAILED
            }
        }
```

This deletes the live-cursor read (`:1080-1084`), `formatTextForInjection` call (`:1086`), and the delete branch (`:1093-1096`) from this site.

- [ ] **Step 5: Collapse Site A onto Site B (kill the duplication)**

Replace the whole body of `injectTextToFocusedField` (`:628-699`) — Site A is not reached by any live session caller, so it delegates to the unified path and maps to Boolean:

```kotlin
    /**
     * Inject text into the currently focused text field. Delegates to the unified result path so
     * there is exactly ONE SET_TEXT implementation (the anchor path); maps the result to Boolean.
     */
    fun injectTextToFocusedField(text: String): Boolean {
        return injectTextWithResultInternal(text) != InjectionResult.FAILED
    }
```

Now `formatTextForInjection` (`:964-982`) has no callers. Delete it (its right-boundary-blind logic is fully replaced by the anchor's both-boundary guard). If a stray reference remains, the compiler will flag it.

- [ ] **Step 6: Verify it compiles, R8 links, and the whole suite is green**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest`
Expected: PASS — 775 baseline + TextJoinTest + InjectionAnchorTest, 0 failures.

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon assembleRelease`
Expected: BUILD SUCCESSFUL (R8 proves no dead-reference to the removed `formatTextForInjection` / delete branch).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/service/WhisperAccessibilityService.kt
git commit -m "fix(inject): session-anchored SET_TEXT — one path, SET_SELECTION pin, delete branch removed"
```

---

## Task 4: GeminiStt melt fix + Soniox no-op-safety proof

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/transcription/cloud/GeminiStt.kt:84`
- Test: reuse `TextJoinTest` (the melt + no-op-safe cases from Task 1 already pin the policy).

**Interfaces:**
- Consumes: `TextJoin.join` (Task 1).
- Produces: no signature change to `GeminiStt.parse`.

**Note on Soniox:** `SonioxStt.kt:146` and `SonioxRealtimeProtocol.kt:207` are UNCHANGED. Soniox tokens carry their own spacing (dedicated space tokens and/or leading-space tokens), so `joinToString(""){it.text}` reproduces correct spacing; `TextJoinTest.join_is_a_no_op_over_a_pre_spaced_token_stream` pins that a defensive `reduce(join)` would neither strip nor double it — the current code is provably melt-free. Re-spacing (trim-per-token / `assemble`) would DESTROY Soniox spacing and is forbidden here.

- [ ] **Step 1: Add the import**

Add to `GeminiStt.kt` imports:

```kotlin
import com.whispereverywhere.text.TextJoin
```

- [ ] **Step 2: Route the multi-part join through TextJoin**

Replace the join at `GeminiStt.kt:84`. Current:

```kotlin
        if (texts.isEmpty()) SttResult.Failed(SttError.Transient(null))
        else SttResult.Text(texts.joinToString(""))
```

New (melt-proof at each part boundary; internal spacing stays verbatim, only the boundary is guarded — a bare `joinToString("")` melted "Hello"+"world" into "Helloworld"):

```kotlin
        if (texts.isEmpty()) SttResult.Failed(SttError.Transient(null))
        else SttResult.Text(texts.reduce { acc, part -> TextJoin.join(acc, part) })
```

Update the comment above (`:76-77`) so it no longer claims "verbatim (no separator)":

```kotlin
        // Join ALL text parts in order via the shared melt-proof policy: Gemini may split one
        // transcript across several text parts (long audio, or a thinking/answer split); a bare
        // concatenation melted the parts together ("...meeting toMorrow..."). TextJoin.join guards
        // only the boundary — pre-existing leading/trailing spaces are respected, never doubled.
```

- [ ] **Step 3: Run the pinning tests to verify they pass**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest --tests "com.whispereverywhere.text.TextJoinTest"`
Expected: PASS — `assemble_would_fail_under_a_bare_concatenation` and `join_is_a_no_op_over_a_pre_spaced_token_stream` both green.

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest --tests "com.whispereverywhere.transcription.cloud.*"`
Expected: PASS — any existing Gemini/Soniox adapter tests still green (Soniox unchanged).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/cloud/GeminiStt.kt
git commit -m "fix(stt): Gemini multi-part join routed through TextJoin (melt fix); Soniox proven no-op-safe"
```

---

## Task 5: Route SegmentOrderer's joins through TextJoin (tests intact)

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/transcription/SegmentOrderer.kt:69-104`
- Test: `app/src/test/java/com/whispereverywhere/transcription/SegmentOrdererTest.kt` (existing — must stay green) + one new case.

**Interfaces:**
- Consumes: `TextJoin.needsSpace` (Task 1).
- Produces: no change to `onResolved`/`skip`/`flush`/`Release` signatures or semantics.

**The routing rule:** the orderer's cross-call GATES (`sb.isNotEmpty()`, `lastReleasedWasLost`, `hasEmittedText`) decide WHETHER to separate; they stay verbatim because they encode stateful look-ahead a stateless joiner cannot. Only the WITHIN-burst space (`sb.isNotEmpty()`) is upgraded to ask `TextJoin.needsSpace(sb, token)` so a punctuation chunk attaches instead of gluing a stray space. The cross-call separators stay unconditional `' '` (the reference behavior for text↔marker across calls).

- [ ] **Step 1: Write the new pinning test**

Add to `SegmentOrdererTest.kt`:

```kotlin
    @Test fun a_punctuation_only_segment_attaches_without_a_stray_space() {
        // Melt-proof routing: within one released burst, "a" then "." must read "a.", not "a .".
        val o = SegmentOrderer()
        o.onResolved(1, text("."))
        assertEquals("a.", o.onResolved(0, text("a")).text)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest --tests "com.whispereverywhere.transcription.SegmentOrdererTest"`
Expected: FAIL — current code appends an unconditional space, producing `"a ."`.

- [ ] **Step 3: Route the two space decisions through TextJoin**

Add the import at the top of `SegmentOrderer.kt`:

```kotlin
import com.whispereverywhere.text.TextJoin
```

In `drain()` (`:69-104`), replace the text-append arm (`:77-85`):

```kotlin
                is SegmentOutcome.Text -> {
                    if (outcome.text.isNotBlank()) {
                        val tok = outcome.text.trim()
                        // Within-burst spacing is melt-proofed (punctuation attaches); the
                        // cross-call gate (a preceding loss marker from an earlier call) stays an
                        // unconditional space — text must not glue onto "[…]".
                        when {
                            sb.isNotEmpty() -> if (TextJoin.needsSpace(sb, tok)) sb.append(' ')
                            lastReleasedWasLost -> sb.append(' ')
                        }
                        sb.append(tok)
                        lastReleasedWasLost = false
                        hasEmittedText = true
                    }
                }
```

And replace the loss-marker arm (`:89-100`):

```kotlin
                SegmentOutcome.EmptyUnexpected, is SegmentOutcome.Lost -> {
                    lost++
                    if (!lastReleasedWasLost) {
                        // Same rule for the marker: melt-proof within-burst, unconditional space
                        // across calls when real text was already emitted earlier.
                        when {
                            sb.isNotEmpty() -> if (TextJoin.needsSpace(sb, lostMarker)) sb.append(' ')
                            hasEmittedText -> sb.append(' ')
                        }
                        sb.append(lostMarker)
                        lastReleasedWasLost = true
                    }
                }
```

- [ ] **Step 4: Run the whole orderer suite to verify meaning is intact**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest --tests "com.whispereverywhere.transcription.SegmentOrdererTest"`
Expected: PASS — all 12 original cases (`"first second"`, `"a b c d"`, `"[…]"`, collapse, `"before […] after"`, `"b d"`, etc.) plus the new `a.` case.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/SegmentOrderer.kt app/src/test/java/com/whispereverywhere/transcription/SegmentOrdererTest.kt
git commit -m "refactor(orderer): route within-burst spacing through TextJoin; gates and loss rules intact"
```

---

## Task 6: Route RecordingStore + BatchTranscriber through TextJoin.assemble

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/recording/RecordingStore.kt:70-73`
- Modify: `app/src/main/java/com/whispereverywhere/transcription/batch/BatchTranscriber.kt:297`
- Test: `app/src/test/java/com/whispereverywhere/recording/RecordingStoreTest.kt` (existing — `"one three"` must stay green) + one new case.

**Interfaces:**
- Consumes: `TextJoin.assemble` (Task 1).
- Produces: no signature change to `RecordingStore.assembledText` or `BatchTranscriber.runLocalSliced`.

- [ ] **Step 1: Write the new pinning test**

Add to `RecordingStoreTest.kt` (mirror its existing `assembledText` fixture style; adapt field names to the file's `RecordingMeta`/`ChunkPlanEntry` shape as used by the existing `:68` test):

```kotlin
    @Test fun assembled_text_attaches_a_punctuation_chunk_without_a_stray_space() {
        // Would be "hello ." under the old unconditional " " join.
        val meta = metaOf(listOf("hello" to true, "." to true))  // helper mirroring the :68 test
        assertEquals("hello.", store.assembledText(meta))
    }
```

If the existing test builds `RecordingMeta` inline rather than via a helper, copy that exact construction and substitute the two chunk texts `"hello"` and `"."` (both `Done`, non-blank).

- [ ] **Step 2: Run it to verify it fails**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest --tests "com.whispereverywhere.recording.RecordingStoreTest"`
Expected: FAIL — old `joinToString(" ")` yields `"hello ."`.

- [ ] **Step 3: Route both sites**

Add the import to `RecordingStore.kt`:

```kotlin
import com.whispereverywhere.text.TextJoin
```

Replace `assembledText` (`:70-73`):

```kotlin
    fun assembledText(meta: RecordingMeta): String =
        TextJoin.assemble(
            meta.chunkPlan.sortedBy { it.index }
                .filter { it.status == ChunkStatus.Done && it.text.isNotBlank() }
                .map { it.text }
        )
```

Add the import to `BatchTranscriber.kt`:

```kotlin
import com.whispereverywhere.text.TextJoin
```

Replace the return at `BatchTranscriber.kt:297` (`return parts.joinToString(" ")`):

```kotlin
        return TextJoin.assemble(parts)
```

- [ ] **Step 4: Run the affected suites to verify meaning is intact**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest --tests "com.whispereverywhere.recording.RecordingStoreTest" --tests "com.whispereverywhere.transcription.batch.*"`
Expected: PASS — `"one three"` (blank dropped, single space) still holds; new `"hello."` case green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/recording/RecordingStore.kt app/src/main/java/com/whispereverywhere/transcription/batch/BatchTranscriber.kt app/src/test/java/com/whispereverywhere/recording/RecordingStoreTest.kt
git commit -m "refactor(assembly): RecordingStore + BatchTranscriber join via TextJoin.assemble"
```

---

## Task 7: Align TranscriptSink + FloatingBubbleService normalization with the policy

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/transcription/TranscriptSink.kt:26-28`
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt:2076-2079`
- Test: `app/src/test/java/com/whispereverywhere/transcription/TranscriptSinkTest.kt` (existing — empty→`""` must stay green).

**Interfaces:**
- Consumes: `TextJoin.normalize`, `TextJoin.needsSpace` (Task 1).
- Produces: no signature change. TranscriptSink keeps its one-segment-at-a-time TRAILING-space append shape (its `_preview` StateFlow and on-disk `fullTextFile` consumers depend on that shape) — only the per-segment normalization routes through the policy.

- [ ] **Step 1: Route TranscriptSink's normalization**

Add the import to `TranscriptSink.kt`:

```kotlin
import com.whispereverywhere.text.TextJoin
```

Replace the head of `append` (`:26-28`):

```kotlin
    @Synchronized
    fun append(segment: String) {
        val s = TextJoin.normalize(segment)
        if (s.isEmpty()) return
```

The trailing-space `writer.write(s); writer.write(" ")` / `tail.append(s).append(' ')` shape is UNCHANGED (a between-chunks joiner would need look-ahead this one-at-a-time sink does not have; its consumers expect the trailing space).

- [ ] **Step 2: Route FloatingBubbleService's history join**

Add the import to `FloatingBubbleService.kt` (if not already present):

```kotlin
import com.whispereverywhere.text.TextJoin
```

Replace the history-accumulation block (`:2076-2079`):

```kotlin
        val historyTok = TextJoin.normalize(text)
        if (historyTok.isNotEmpty()) {
            if (sessionTranscript.isNotEmpty() && TextJoin.needsSpace(sessionTranscript, historyTok)) {
                sessionTranscript.append(' ')
            }
            sessionTranscript.append(historyTok)
        }
```

- [ ] **Step 3: Run the sink suite + full suite to verify meaning is intact**

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest --tests "com.whispereverywhere.transcription.TranscriptSinkTest"`
Expected: PASS — empty→`""` (`:41`) still holds.

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon testDebugUnitTest`
Expected: PASS — full suite (775 baseline + all new cases), 0 failures.

Run: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon assembleRelease`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/TranscriptSink.kt app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt
git commit -m "refactor(assembly): align TranscriptSink + history normalization with TextJoin"
```

---

## Task 8: Ledger entry

**Files:**
- Modify: `docs/PLAY-DECLARATIONS.md`

**Interfaces:** none (docs).

This wave is delivery-end + formatting only: no new data collection, no new recipient, no disclosure-version change, no speed claim. The ledger records that.

- [ ] **Step 1: Add the release-ledger entry**

Append to the release-ledger block in `docs/PLAY-DECLARATIONS.md` (after the "Realtime all-providers" entry, matching the house style of the surrounding entries):

```markdown
**Release ledger — Session-anchored injection + TextJoin (2026-07-31):** the mid-dictation
cursor-teleport corruption (segments landing mid-text on editors that move the caret after our
SET_TEXT, Facebook named) is fixed by a pure per-session cursor state machine (`InjectionAnchor`)
that ignores the live app cursor, re-syncs against user edits WITHOUT ever deleting, and honors a
one-shot start-selection replace. The two duplicated SET_TEXT sites collapse to ONE anchor-driven
path that pins the caret with `ACTION_SET_SELECTION` after each write (failure non-fatal). All text
joins route through one melt-proof policy (`TextJoin`): no two alphanumeric runs touch across a
boundary, no double spaces, closing punctuation attaches clean — fixing the Gemini multi-part melt
and unifying orderer/store/sink/history spacing (Soniox left as-is, proven no-op-safe). **This is a
delivery-end + formatting change only: no new collected/shared data type, no new recipient, no
disclosure-version change (v3 unchanged), no speed claim.** Clipboard/document/social/Facebook-mention
paste paths are a documented carve-out — the anchor cannot govern PASTE and does not touch them.
```

- [ ] **Step 2: Commit**

```bash
git add docs/PLAY-DECLARATIONS.md
git commit -m "docs: ledger — session-anchored injection + TextJoin (delivery+formatting, no data-safety change)"
```

---

## Self-Review

**1. Spec coverage — every brief requirement → task + pin:**

| Brief requirement | Task | Pin |
|---|---|---|
| Pure `InjectionAnchor`: start/plan/re-sync/one-shot replace | Task 2 | `InjectionAnchorTest` (all cases) |
| Anchor ignores live cursor when field == expected | Task 2 | `a_moved_live_cursor_is_ignored_when_the_field_is_unchanged` |
| Re-sync NEVER deletes | Task 2 | `a_user_edit_*`, `an_unrecognizable_edit_falls_back_to_the_end_and_never_deletes`, `sequential_placement_never_shrinks` |
| One-shot start replace fires once | Task 2 | `a_start_selection_range_is_replaced_exactly_once`, `no_start_selection_means_no_deletion_ever` |
| Deliberate mid-text dictation still works | Task 2 | `start_with_a_caret_anchors_there_*`, `a_mid_text_insert_spaces_both_sides` |
| BOTH-boundary spacing guard | Tasks 1+2 | `a_mid_text_insert_spaces_both_sides`, `a_mid_text_insert_creates_no_double_space`, `melt_invariant_holds_across_generated_pairs` |
| Melt invariant absolute (no adjacent alnum, no double space, closing punct attaches) | Task 1 | `melt_invariant_holds_across_generated_pairs`, `closing_punctuation_attaches_without_a_space`, `join_never_doubles_a_space` |
| ONE unified injection function; both sites route through it | Task 3 | `injectTextToFocusedField` delegates; `setTextViaAnchor` is the sole SET_TEXT |
| `ACTION_SET_SELECTION` pin after SET_TEXT, failure non-fatal | Task 3 | `pinCursor` (log-only, count/label, no content) |
| Live-selection delete branch DIES | Task 3 | delete branch + live-cursor read removed; `formatTextForInjection` deleted |
| Session begin/end carries anchor state | Task 3 | `sessionAnchor` field + begin/end wiring |
| Clipboard/doc/social/FB-mention carve-out untouched | Task 3 | those branches left verbatim |
| Gemini multi-part melt fixed | Task 4 | `assemble_would_fail_under_a_bare_concatenation`; Gemini uses `reduce(join)` |
| Soniox join spacing-preserving, not re-spacing (no-op-safe) | Task 4 | `join_is_a_no_op_over_a_pre_spaced_token_stream`; SonioxStt/RealtimeProtocol unchanged |
| Orderer routed, every existing test keeps meaning | Task 5 | full `SegmentOrdererTest` green + new `a.` case; gates/loss rules verbatim |
| RecordingStore / BatchTranscriber routed | Task 6 | `"one three"` intact + new `"hello."` case |
| TranscriptSink normalization aligned, append shape kept | Task 7 | `TranscriptSinkTest:41` empty→`""` intact |
| History (FloatingBubbleService) join aligned | Task 7 | routed via `TextJoin` |
| Ordering/exactly-once/loss untouchable | Tasks 5-7 | only join decisions changed; no ordering/identity edits |
| No credential/content logging; no speed claims | Tasks 3, 8 | `pinCursor` logs label only; ledger states no speed claim |
| Baseline 775 tests / 0 failures preserved; R8 green | Tasks 3, 7 | full `testDebugUnitTest` + `assembleRelease` gates |

**2. Placeholder scan:** No `TBD`/`TODO`/"add error handling"/"similar to Task N". Two adaptation notes are explicit and bounded: Task 3 Step 3 ("confirm `TAG` constant; else use the literal") and Task 6 Step 1 ("mirror the existing `:68` fixture construction") — both name the exact fallback, not a vague gap. Every code step shows complete code.

**3. Type/signature consistency:** `TextJoin.needsSpace(CharSequence, CharSequence)`, `join(String, String)`, `assemble(List<String>)`, `normalize(String)`, `CLOSING` — used identically in Tasks 3-7. `InjectionAnchor.start(String, Int, Int)`, `plan(String, String): Placement`, `commit(Placement)`, `Placement(newFieldText, newSelection)`, `anchor`/`expectedText` — used identically in Task 3 and the tests. `sessionAnchor: InjectionAnchor?` set in begin, read in `setTextViaAnchor`, cleared in end. `pinCursor(AccessibilityNodeInfo, Int)` and `setTextViaAnchor(AccessibilityNodeInfo, String): InjectionResult` names consistent across all references. No public companion signature changes — `deliverReleasedText` call sites and orderer/engine semantics untouched, honoring the scope reconciliation note.
