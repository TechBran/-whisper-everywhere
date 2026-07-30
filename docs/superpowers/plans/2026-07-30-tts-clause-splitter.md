# TTS Clause Splitter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound every string handed to sherpa's `generateWithCallback` so no single synthesis unit can outrun the audio already banked (the underrun law, spec §6A.1) and so sherpa's discarded whole-utterance `float[]` stays small (the OOM bound, spec §6A.5). This eliminates the single measured 9,031 ms gap (`docs/measurements/2026-07-27-tts-baseline-fold6.log`) without altering short-sentence audio.

**Architecture:** A new pure Kotlin object `ClauseSplitter` — no `android.*`, no state, unit-tested on the JVM in the `TtsDiagMath` idiom — takes the trimmed selection and returns an ordered `List<String>` of submission units, each `≤ SPLIT_MAX_CHARS`. `TtsEngine.speak()` replaces its single `generateWithCallback(clean)` call with a loop over those units at the recon-identified seam (`TtsEngine.kt:405-408`). All splitting arithmetic and every boundary rule live in the pure object; `TtsEngine` only iterates and checks cancellation. The retained-store pipeline, the playback thread, the `Function1` callback, and `TtsDiag` are untouched.

**Tech Stack:** Kotlin 2.0.21, JUnit 4, sherpa-onnx v1.13.4 (Kokoro, `batch_size=1`, splits on `.`/`?`/`!`, no sub-sentence streaming), Gradle 8.14.4 / AGP 8.7.3, JDK 21.

## Global Constraints

- **This is the CLAUSE SPLITTER ONLY.** No cloud TTS, no voice picker, no provider code, no UI. sherpa's `batch_size=1` / whole-sentence-burst behaviour is a fact to work *with*.
- **Short-sentence behaviour must stay bit-identical in feed order.** The splitter may only change what a *long* sentence becomes. Two mechanical guarantees enforce this: (1) a selection that is itself `≤ SPLIT_MAX_CHARS` is returned as a single element equal to the input — the fast path takes the *exact* current code line; (2) when splitting does occur, each short sentence is emitted as a verbatim substring, in order. The one residual assumption — that sherpa/espeak phonemise per sentence with no cross-sentence coarticulation, so feeding sentences individually yields per-sentence audio identical to feeding them in one blob — is **load-bearing and is confirmed by the device A/B in Task 3**. It is well-founded: Kokoro synthesises one sentence per callback (`batch_size=1`), and espeak-ng phonemisation is sentence-scoped.
- **Do not touch the sherpa callback's type.** It MUST remain the explicit `object : Function1<FloatArray, Int>` at `TtsEngine.kt:363`. A lambda there is a proven on-device SIGABRT.
- **No credential/content logging.** TTS text NEVER reaches logcat — lengths only, as today. `ClauseSplitter` does no logging at all. `TtsDiag` is NOT modified (its format is pinned by tests); the device re-measure compares the existing `end` record before/after.
- **Preserve the hard-won invariants:** C1 generation-token cancellation, C2 playback thread is the AudioTrack's sole owner. The loop re-checks `cancelled()` between units so `stop()` still lands within one sherpa call.
- **Build:** JDK 21 (Android Studio's bundled JBR). Always `--no-daemon` (OneDrive locks otherwise). PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"` then `.\gradlew.bat ...`. **JVM unit tests only** for verification here (`:app:testDebugUnitTest`); `assembleRelease` for the R8 check. **NEVER** `connectedAndroidTest` / `installDebug` — they uninstall and have twice destroyed user data. Instrumented files are compile-check only.
- **Baseline: 436 tests / 0 failures.** This wave adds one JVM test class; the count must only rise.
- Commit ONLY the named files. Never `git add -A`. Retry once on `index.lock`.

---

## Deriving the constants (all arithmetic from the baseline log)

Source: `docs/measurements/2026-07-27-tts-baseline-fold6.log` (SM-F956U, gen=10, 11 sentences).

| Quantity | Value | Where |
|---|---|---|
| selection characters | `chars=2198` | `open`, line 5 |
| total audio | `audioMs=96362` (Σ `sent audMs` = 96 357) | `end`, line 421 |
| first-burst bank (seq 0 audio) | `audMs=3262` | `sent seq=0`, line 6 |
| worst per-unit RTF | `rtfMax=0.73` (also `sent seq=10 rtf=0.73`) | `end`, line 421 / line 377 |
| median RTF | `rtfP50=0.62` | `end`, line 421 |
| the starving unit (seq 1) | `audMs=23603 synthMs=12347 rtf=0.52` | `sent seq=1`, line 33 |
| the single stall | `audibleMs=9031` | `under seq=2`, line 34 |

**Character rate.** `2198 chars / 96.357 s = 22.81 chars/s → 43.84 ms/char`. Round **up** to `MS_PER_CHAR = 45` — over-estimating a unit's duration makes the splitter cut *earlier*, i.e. conservative. (This is the fact that overturns spec §6A's parenthetical "`SPLIT_MAX_CHARS = 300` ~3.5 s of audio": that implies 85.7 chars/s. The device says 22.8 — 3.75× slower — so 300 chars is ~13.2 s of audio, which at RTF 0.73 needs 9.6 s of bank and would **still underrun** behind the 3.26 s first burst. The nominal 300 does not satisfy the law it was proposed to satisfy; the measured rate does.)

**The bank bound.** The underrun law: no stall at a boundary ⟺ `banked_audio ≥ RTF × duration(next_unit)`. The tightest boundary is the first: only the first burst (3,262 ms) is banked, and the worst per-unit RTF is 0.73. So

```
duration(next_unit) ≤ 3262 / 0.73 = 4468 ms   →  D_MAX_AUDIO = 4468 ms
```

Pick the cap comfortably under that:

```
SPLIT_MAX_CHARS = 80   → est 80 × 45 = 3600 ms → 0.73 × 3600 = 2628 ms ≤ 3262 ms   (19% headroom)
                                                 0.62 × 3600 = 2232 ms ≤ 3262 ms   (32% headroom at median RTF)
SPLIT_TARGET_CHARS = 60 → est 2700 ms         → 0.73 × 2700 = 1971 ms ≤ 3262 ms   (aim point, comfortable)
```

**Corroboration.** The starving unit is `23603 / 45 ≈ 524` chars. `ceil(524 / 80) = 7` chunks of ~75 chars ≈ 3.4 s each — which reproduces §6A's own prose "turns the 23.6 s unit into **~7 chunks**". That "~7" is arithmetically consistent with a ~75-char cap and **inconsistent** with the same paragraph's stated 300 (`524 / 300 = 2` chunks). The intended cap was always ~75–80 chars; only the char→seconds conversion in the spec was wrong. We follow the measured rate.

**Memory bound (§6A.5, "not optional").** Each `generateWithCallback` call allocates a whole-utterance `float[]` for its argument. Looping per ≤80-char unit caps that array at ~3.6 s ≈ 86 k samples ≈ **345 KB** per call, instead of the whole selection (the OOM-class array today on a no-`largeHeap` app). The bound is delivered by *feeding units separately*, which is why integration loops rather than rewriting one blob.

**Residual, out of scope (deferred D21).** If the *first* natural sentence is much shorter than 3,262 ms (e.g. a one-word heading), even a capped next unit can exceed `RTF × tiny_bank` at that first boundary. §6A defers the fixed start-prebuffer watermark noting "the first burst was already 3,262 ms". That watermark, not the splitter, closes this edge; it is out of this wave. The splitter still strictly improves every such case (smaller `D_max`, smaller array, better TTFW).

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/whispereverywhere/tts/ClauseSplitter.kt` | **Create.** Pure splitter: constants, `estimateAudioMs`, `plan(clean): List<String>`, boundary scanners. No Android. |
| `app/src/test/java/com/whispereverywhere/tts/ClauseSplitterTest.kt` | **Create.** Exhaustive JVM tests, incl. the pinned 23.6 s case and the bank-bound invariant. |
| `app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt` | **Modify.** Replace the single `generateWithCallback` call (lines 405-408) with a loop over `ClauseSplitter.plan(clean)`. Nothing else. |

---

## Task 1: `ClauseSplitter` — pure, exhaustively tested

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/tts/ClauseSplitter.kt`
- Test: `app/src/test/java/com/whispereverywhere/tts/ClauseSplitterTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, used by Task 2:
  - `ClauseSplitter.SPLIT_MAX_CHARS: Int`, `SPLIT_TARGET_CHARS: Int`, `MS_PER_CHAR: Long`
  - `ClauseSplitter.estimateAudioMs(chars: Int): Long`
  - `ClauseSplitter.plan(clean: String): List<String>` — ordered units, each `≤ SPLIT_MAX_CHARS`; returns `listOf(clean)` unchanged when `clean.length ≤ SPLIT_MAX_CHARS`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/tts/ClauseSplitterTest.kt`:

```kotlin
package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClauseSplitterTest {

    private fun cap() = ClauseSplitter.SPLIT_MAX_CHARS
    private fun each(units: List<String>) = units.forEach {
        assertTrue("unit over cap (${it.length}): \"$it\"", it.length <= cap())
    }

    // --- The bit-identical guarantee ---------------------------------------------------------

    @Test fun a_short_sentence_is_returned_unchanged() {
        val s = "This is a short sentence."
        assertEquals(listOf(s), ClauseSplitter.plan(s))
    }

    @Test fun a_sentence_exactly_at_the_cap_is_unchanged() {
        val s = "x".repeat(cap())
        assertEquals(listOf(s), ClauseSplitter.plan(s))
    }

    @Test fun one_char_over_the_cap_is_split() {
        val s = "word ".repeat(20).trim() // 99 chars of "word word ..."
        assertTrue(s.length > cap())
        val units = ClauseSplitter.plan(s)
        assertTrue(units.size >= 2)
        each(units)
    }

    @Test fun empty_and_blank_do_not_crash() {
        assertEquals(listOf(""), ClauseSplitter.plan(""))
        assertEquals(listOf("   "), ClauseSplitter.plan("   "))
    }

    // --- Boundary preference: punctuation before conjunctions before hard cuts ----------------

    @Test fun splits_prefer_a_comma_boundary() {
        // Two ~55-char clauses joined by a comma; the seam must fall at the comma.
        val a = "the morning was cold and the streets were completely empty"
        val b = "so the whole city felt like it belonged to no one at all"
        val units = ClauseSplitter.plan("$a, $b.")
        each(units)
        assertTrue("first unit should end at the comma clause", units.first().endsWith(","))
    }

    @Test fun falls_back_to_a_conjunction_when_no_punctuation() {
        val a = "the engine warmed slowly under a grey and unpromising winter sky"
        val b = "the driver waited by the door without any sign of real impatience"
        val units = ClauseSplitter.plan("$a and $b")
        each(units)
        // "and" starts the SECOND unit — the cut is before the conjunction.
        assertTrue(units.any { it.startsWith("and ") || it.contains(" and ").not() })
    }

    @Test fun falls_back_to_a_word_boundary_when_no_clause_marker() {
        val s = ("alpha bravo charlie delta echo foxtrot golf hotel india juliet " +
            "kilo lima mike november oscar papa quebec romeo").trim()
        val units = ClauseSplitter.plan(s)
        each(units)
        units.forEach { assertTrue("cut mid-word: \"$it\"", !it.startsWith(" ") && !it.endsWith(" ")) }
        // reassembled tokens are preserved and in order
        assertEquals(s.split(" "), units.joinToString(" ").split(" "))
    }

    @Test fun a_single_unbroken_token_is_hard_cut_at_the_cap() {
        val s = "x".repeat(cap() * 2 + 5) // no spaces anywhere
        val units = ClauseSplitter.plan(s)
        each(units)
        assertEquals(s, units.joinToString(""))
    }

    // --- Never split inside numbers or abbreviations ------------------------------------------

    @Test fun a_decimal_is_never_a_sentence_boundary() {
        val a = "the reading settled at 3.5 volts after the regulator finally stabilised itself"
        val b = "and the current stayed flat for the remainder of the long overnight test run"
        val units = ClauseSplitter.plan("$a, $b.")
        each(units)
        assertTrue("3.5 was split", units.none { it.endsWith("3.") || it.startsWith("5 ") })
    }

    @Test fun common_abbreviations_are_not_sentence_boundaries() {
        listOf("e.g.", "i.e.", "Dr.", "Mr.", "etc.").forEach { abbr ->
            val a = "consider the smaller portable devices $abbr the handheld field units we shipped"
            val b = "which all shared one battery design across the entire product family last year"
            val units = ClauseSplitter.plan("$a $b.")
            each(units)
            assertTrue("$abbr treated as a full stop", units.none { it.trim().endsWith(abbr) && it.length < 40 })
        }
    }

    // --- Unicode / quotes -------------------------------------------------------------------

    @Test fun a_surrogate_pair_is_never_split_by_a_hard_cut() {
        val emoji = "😀" // U+1F600, a surrogate pair
        val s = emoji.repeat(cap()) // > cap, no spaces -> forces hard cuts
        val units = ClauseSplitter.plan(s)
        each(units)
        units.forEach {
            assertTrue("unit ends on a lone high surrogate", it.isEmpty() || !it.last().isHighSurrogate())
            assertTrue("unit starts on a lone low surrogate", it.isEmpty() || !it.first().isLowSurrogate())
        }
        assertEquals(s, units.joinToString(""))
    }

    @Test fun a_period_inside_a_closing_quote_is_not_a_naive_boundary() {
        val a = "she said “we leave at dawn.” and nobody in the small kitchen argued with her"
        val b = "because the road ahead was long and the weather was turning against the travellers"
        val units = ClauseSplitter.plan("$a $b.")
        each(units)
    }

    // --- The load-bearing derivation, pinned -------------------------------------------------

    @Test fun estimate_uses_the_measured_rate() {
        // 45 ms/char (measured 43.84, rounded up). 80 chars -> 3600 ms.
        assertEquals(3600L, ClauseSplitter.estimateAudioMs(80))
        assertEquals(45L, ClauseSplitter.estimateAudioMs(1))
    }

    @Test fun the_cap_satisfies_the_bank_bound_from_the_baseline() {
        // Underrun law at the first boundary: RTF_max * dur(cap) must fit the 3262 ms first burst.
        // RTF_max = 0.73 (end line, 2026-07-27 baseline); first burst = 3262 ms (sent seq=0).
        val worstSynthMs = (0.73 * ClauseSplitter.estimateAudioMs(ClauseSplitter.SPLIT_MAX_CHARS)).toLong()
        assertTrue("cap violates the bank bound: $worstSynthMs ms > 3262 ms", worstSynthMs <= 3262L)
    }

    @Test fun the_23_6_second_sentence_splits_into_bank_safe_chunks() {
        // Reconstruct the shape of baseline seq=1: audMs=23603 -> ~524 chars of comma-spliced prose.
        val clause = "the road climbed steadily past the old quarry and the light kept fading, "
        val giant = clause.repeat(7).trim() + "." // ~511 chars, one sentence, comma-spliced
        assertTrue(giant.length > 400)
        val units = ClauseSplitter.plan(giant)

        each(units) // every chunk within the cap
        assertTrue("expected several chunks, got ${units.size}", units.size >= 6)
        units.forEach { u ->
            val synthMs = (0.73 * ClauseSplitter.estimateAudioMs(u.length)).toLong()
            assertTrue("chunk would starve the bank: $synthMs ms > 3262 ms", synthMs <= 3262L)
        }
        // Order and content preserved (ignoring the whitespace the splitter trims at seams).
        assertEquals(giant.filter { !it.isWhitespace() }, units.joinToString("").filter { !it.isWhitespace() })
    }

    @Test fun units_never_exceed_the_cap_on_realistic_prose() {
        val prose = ("The committee met at noon. It reviewed the quarterly figures, which had " +
            "slipped, and debated whether to raise the fee; opinions were sharply divided. " +
            "Afterwards, e.g. in the smaller working groups, the mood was calmer and more " +
            "practical, and a compromise slowly took shape over the course of the afternoon.")
        val units = ClauseSplitter.plan(prose)
        each(units)
        assertEquals(prose.filter { !it.isWhitespace() }, units.joinToString(" ").filter { !it.isWhitespace() })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.tts.ClauseSplitterTest"
```

Expected: FAIL — `Unresolved reference: ClauseSplitter`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/whispereverywhere/tts/ClauseSplitter.kt`:

```kotlin
package com.whispereverywhere.tts

/**
 * Pure, JVM-tested. Bounds every string handed to sherpa's generateWithCallback so no single
 * synthesis unit can outrun the audio already banked (the underrun law, spec 6A.1) and so
 * sherpa's discarded whole-utterance float[] stays small (the OOM bound, spec 6A.5).
 *
 * Constants are DERIVED from docs/measurements/2026-07-27-tts-baseline-fold6.log:
 *   character rate = 2198 chars / 96.357 s = 22.81 chars/s -> 43.84 ms/char, rounded UP to 45
 *                    (a longer estimate cuts earlier -> conservative).
 *   first-burst bank (sent seq=0 audMs) = 3262 ms.
 *   worst per-unit RTF (end rtfMax)      = 0.73.
 *   Underrun-free at the tightest (first) boundary  <=>  RTF * dur(next) <= bank:
 *       dur(next) <= 3262 / 0.73 = 4468 ms.
 *   SPLIT_MAX_CHARS = 80  -> est 3600 ms -> 0.73 * 3600 = 2628 ms <= 3262 ms (19% headroom).
 *
 * The measured rate overturns spec 6A's nominal "300 chars ~ 3.5 s" (that assumes 85.7 chars/s;
 * the device shows 22.8, so 300 chars is ~13.2 s and would still starve). The starving unit was
 * ~524 chars, which at this cap becomes ~7 chunks -- reproducing 6A's own "~7 chunks" prose.
 */
object ClauseSplitter {

    const val MS_PER_CHAR = 45L
    const val SPLIT_MAX_CHARS = 80
    const val SPLIT_TARGET_CHARS = 60

    /** Below this, a candidate boundary is ignored so we never emit a tiny sliver at a seam. */
    private const val MIN_CHARS = 20

    /** Estimated audio duration of [chars] characters at the measured device rate. */
    fun estimateAudioMs(chars: Int): Long = chars.toLong() * MS_PER_CHAR

    /**
     * Split [clean] into ordered submission units, each <= SPLIT_MAX_CHARS, text preserved.
     * A selection already within the cap is returned unchanged as a single element -- this is the
     * bit-identical fast path the caller feeds through the exact current code line. Longer input
     * is cut greedily at the strongest boundary in each window: sentence terminator, then clause
     * punctuation, then a conjunction, then any word boundary, then (last resort) a hard cut that
     * never lands inside a surrogate pair.
     */
    fun plan(clean: String): List<String> {
        if (clean.length <= SPLIT_MAX_CHARS) return listOf(clean)
        val units = ArrayList<String>()
        var i = 0
        val n = clean.length
        while (i < n) {
            if (n - i <= SPLIT_MAX_CHARS) {
                units.add(clean.substring(i))
                break
            }
            val cut = chooseCut(clean, i, i + SPLIT_MAX_CHARS)
            units.add(clean.substring(i, cut))
            i = cut
        }
        // Trim seams (sherpa trims anyway); drop nothing that carried text.
        val trimmed = units.map { it.trim() }.filter { it.isNotEmpty() }
        return if (trimmed.isEmpty()) listOf(clean) else trimmed
    }

    /** Index in (start, end] at which to end the current unit, by descending boundary priority. */
    private fun chooseCut(s: String, start: Int, end: Int): Int {
        bestTerminator(s, start, end)?.let { return it }
        bestClausePunct(s, start, end)?.let { return it }
        bestConjunction(s, start, end)?.let { return it }
        bestSpace(s, start, end)?.let { return it }
        return hardCut(s, start, end)
    }

    // A '.'/'?'/'!' that genuinely ends a sentence: followed by whitespace/end, not a decimal,
    // not a known abbreviation or single-letter initial. Cut AFTER it (keep the terminator).
    private fun isTerminatorAt(s: String, p: Int): Boolean {
        val c = s[p]
        if (c != '.' && c != '?' && c != '!') return false
        if (p + 1 < s.length && !s[p + 1].isWhitespace()) return false // ".5", ".”" etc. excluded
        if (c == '.') {
            if (p > 0 && s[p - 1].isDigit()) return false // decimal left side
            var j = p - 1
            while (j >= 0 && s[j].isLetter()) j--
            val token = s.substring(j + 1, p).lowercase()
            if (token.length == 1 || token in ABBREV) return false
        }
        return true
    }

    private val ABBREV = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "vs", "etc", "eg", "ie",
        "no", "fig", "dept", "inc", "ltd", "co", "jr", "sr",
    )

    private val CLAUSE = setOf(',', ';', ':', '—', '–') // comma semicolon colon em/en dash

    private val CONJ = setOf(
        "and", "but", "or", "nor", "so", "yet", "for", "because", "which", "that",
        "while", "when", "where", "if", "though", "although", "however", "therefore",
    )

    private fun bestTerminator(s: String, start: Int, end: Int): Int? {
        var p = end - 1
        while (p >= start) {
            if (isTerminatorAt(s, p) && p + 1 - start >= MIN_CHARS) return p + 1
            p--
        }
        return null
    }

    private fun bestClausePunct(s: String, start: Int, end: Int): Int? {
        var p = end - 1
        while (p >= start) {
            val c = s[p]
            // A hyphen counts only when spaced (punctuation dash), never inside "state-of-the-art".
            val isDash = c == '-' && p + 1 < s.length && s[p + 1].isWhitespace()
            if ((c in CLAUSE || isDash) && p + 1 - start >= MIN_CHARS) return p + 1
            p--
        }
        return null
    }

    private fun bestConjunction(s: String, start: Int, end: Int): Int? {
        var p = end - 1
        while (p > start) {
            if (s[p].isWhitespace() && p - start >= MIN_CHARS) {
                var e = p + 1
                while (e < s.length && s[e].isLetter()) e++
                if (s.substring(p + 1, e).lowercase() in CONJ) return p // cut before the conjunction
            }
            p--
        }
        return null
    }

    private fun bestSpace(s: String, start: Int, end: Int): Int? {
        var p = end - 1
        while (p > start) {
            if (s[p].isWhitespace() && p - start >= MIN_CHARS) return p
            p--
        }
        return null
    }

    private fun hardCut(s: String, start: Int, end: Int): Int {
        var e = end
        if (e in 1 until s.length && s[e - 1].isHighSurrogate()) e-- // never split a surrogate pair
        return e.coerceAtLeast(start + 1)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.tts.ClauseSplitterTest"
```

Expected: PASS (all tests). If a boundary test fails, fix the scanner — do NOT relax an assertion that encodes the bank bound (`the_cap_satisfies_the_bank_bound_from_the_baseline`, `the_23_6_second_sentence_splits_into_bank_safe_chunks`); those pin the derivation.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/whispereverywhere/tts/ClauseSplitter.kt app/src/test/java/com/whispereverywhere/tts/ClauseSplitterTest.kt
git commit -m "feat(tts): ClauseSplitter -- bound synthesis units so the bank never starves

Pure, JVM-tested. Caps every string handed to sherpa at SPLIT_MAX_CHARS=80.
Derived from the 2026-07-27 Fold 6 baseline: 22.8 chars/s (45 ms/char),
first-burst bank 3262 ms, worst RTF 0.73 -> dur(next) <= 4468 ms, so an
80-char cap (est 3600 ms, 0.73*3600=2628 ms) leaves 19% headroom. Cuts at
sentence terminators, then clauses, then conjunctions, then word
boundaries, never inside numbers/abbreviations or a surrogate pair. Short
input is returned unchanged -- the bit-identical guarantee."
```

---

## Task 2: Feed the units through the existing seam

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt` (lines 405-408 only)

**Interfaces:**
- Consumes: `ClauseSplitter.plan` (Task 1); the existing `callback`, `clean`, `cancelled()`, `doneFlag`.
- Produces: nothing later tasks depend on — terminal code change of this wave.

- [ ] **Step 1: Confirm the seam**

Read `app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt:405-416`. Confirm it reads exactly:

```kotlin
                try {
                    engine.generateWithCallback(
                        text = clean, sid = speakerId, speed = speed, callback = callback,
                    )
                } finally {
```

`clean` is `text.trim()` (line 125); the callback appends per-callback PCM to the retained `store`; `cancelled()` is the generation-token check (line 133).

- [ ] **Step 2: Replace the single call with the bounded loop**

Change ONLY the `try` body (lines 405-408). Leave the `finally { doneFlag.set(true) }` and everything else byte-for-byte:

```kotlin
                try {
                    // Bound each synthesis unit so sherpa's whole-utterance float[] stays small
                    // (OOM bound, 6A.5) and no unit outruns the banked audio (underrun law, 6A.1).
                    // A selection within the cap yields a single unit equal to `clean`, so short
                    // text takes the exact prior path; only long sentences become multiple units.
                    // Feed order is preserved and cancellation is re-checked between units so
                    // stop() still lands within one sherpa call (C1).
                    for (unit in ClauseSplitter.plan(clean)) {
                        if (cancelled()) break
                        engine.generateWithCallback(
                            text = unit, sid = speakerId, speed = speed, callback = callback,
                        )
                    }
                } finally {
```

Rationale for looping (not one rewritten blob): the memory bound is delivered only when each `generateWithCallback` argument is itself small — one call per ≤80-char unit. Per-sentence audio is unchanged because Kokoro synthesises one sentence per callback (`batch_size=1`) and espeak phonemisation is sentence-scoped; this is the assumption the Task 3 device A/B confirms.

- [ ] **Step 3: Confirm no other line moved**

```powershell
git diff app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt
```

Expected: exactly one hunk, inside the `try` body. If the callback type, the `store`/`availableSamples` accounting, the playback thread, `TtsDiag`, or any cap constant appears in the diff, revert it — this task changes only what feeds the queue.

- [ ] **Step 4: Build and run the full JVM suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```

Expected: unit tests PASS (437 tests / 0 failures — the prior 436 plus this class); `assembleRelease` BUILD SUCCESSFUL (R8 keeps `ClauseSplitter`; it is reachable from `TtsEngine`). Do **NOT** run `connectedAndroidTest` or `installDebug`.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt
git commit -m "fix(tts): feed sherpa in bounded units -- close the measured 9 s bank gap

Replaces the single generateWithCallback(clean) call with a loop over
ClauseSplitter.plan(clean). Short selections yield one unit equal to clean
(bit-identical), so only long sentences change; cancellation is re-checked
between units. Each sherpa call now allocates a <=345 KB float[] instead of
the whole selection, and no unit can outrun the banked audio."
```

---

## Task 3: Device re-measure (owner, on the Fold 6) — verdict, not code

Produces no code. Confirms the fix against the same flow that produced the baseline. **Follow the signature preflight and the `connectedAndroidTest`-uninstalls warnings from `2026-07-27-tts-diagnostics-release-0.md` before ANY install — data loss has happened here twice.** This wave never runs instrumented tests; it installs a release build and reads logcat.

- [ ] **Step 1:** Install the `assembleRelease` build over the existing app (only after the preflight confirms the on-device signer matches — otherwise STOP and ask). `adb` is at `C:\Users\bastr\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
- [ ] **Step 2:** `adb logcat -c; adb logcat -s WE-TTS > tts-after.log`.
- [ ] **Step 3:** Read aloud the SAME content shape as the baseline — an article with a short sentence followed by a very long comma-spliced one (the seq0→seq1 boundary that starved). Then read a paragraph of ordinary short sentences.
- [ ] **Step 4:** Compare the `end` record against the baseline (`docs/measurements/2026-07-27-tts-baseline-fold6.log:421`, `underN=1 underMs=9031 maxGapMs=9031`).

**The re-measure MUST show:**
1. **The 9 s case eliminated or sub-second.** On the long-sentence read: `underN=0`, or `underMs`/`maxGapMs` reduced to < 1000 ms. The `sent` lines show the former 23,603 ms unit replaced by ~7 units each `audMs ≲ 3600` with `rtf < 1`.
2. **No new gaps on normal text.** The short-sentence paragraph keeps `underN=0`, `dutyPct` ~100, and `ttfwMs` no worse than baseline `1997` (expected better — the first unit is now small).
3. **Short-sentence audio unchanged (the bit-identical check).** A/B a fixed short multi-sentence string built vs. this build: the per-sentence `sent audMs` values must match within sherpa's rounding. If they diverge, the per-sentence phonemisation assumption is wrong — fall back to feeding one rewritten `clean'` (clause boundaries inside long sentences only), accepting that the memory bound then needs a separate follow-up.

- [ ] **Step 5:** Save the capture: `git add docs/measurements/2026-07-30-tts-after-fold6.log && git commit -m "measure(tts): post-splitter capture -- 9 s gap closed"`.

---

## Self-Review

**Scope law:** Splitter only. No cloud TTS, no voice picker, no provider code, no UI (`plan()` slots inside `speak()`; entry points at `FloatingBubbleService:543`, `SpeakTextActivity:23`, `SettingsScreen:770` pass full text as before — recon §6 confirms the engine owns splitting). `TtsDiag` format untouched, so its pinned tests do not churn. sherpa's `batch_size=1` / whole-sentence bursts are worked *with* — the loop just hands sherpa smaller strings.

**Bit-identical guarantee, mechanically:** (1) `clean.length ≤ SPLIT_MAX_CHARS` ⇒ `plan` returns `listOf(clean)` ⇒ the loop makes exactly one `generateWithCallback(clean, …)` call — identical to today. (2) Longer input emits verbatim, in-order substrings; the only audio that changes is inside sentences that exceed the cap. (3) The residual assumption (per-sentence phonemisation independence) is stated, justified (`batch_size=1`, sentence-scoped espeak), and verified by the Task 3 A/B — with a named fallback if it fails.

**Derivation pinned in tests, not just prose:** `estimate_uses_the_measured_rate` fixes 45 ms/char; `the_cap_satisfies_the_bank_bound_from_the_baseline` fails if `SPLIT_MAX_CHARS` is ever raised past what the 3,262 ms bank / 0.73 RTF allows; `the_23_6_second_sentence_splits_into_bank_safe_chunks` reproduces the exact failing unit and asserts every chunk is bank-safe. A future edit that reintroduces the gap breaks a JVM test before it reaches a device.

**Deviation from spec §6A, with evidence:** §6A's nominal `SPLIT_MAX_CHARS = 300` / `TARGET = 190` assume 85.7 chars/s; the baseline log measures 22.8 (line 5 `chars=2198`, line 421 `audioMs=96362`), so 300 chars would still starve behind the 3.26 s first burst. We use 80/60, which the same §6A's "~7 chunks" prose corroborates (524 chars ÷ 80 ≈ 7; ÷ 300 ≈ 2). Deviation is quoted-evidence-backed, per the wave's rule.

**Edge cases covered by tests:** no punctuation (word-boundary fallback), no spaces at all (hard cut), decimals (`3.5`), abbreviations (`e.g.`, `Dr.`, `etc.`), surrogate pairs (emoji, never split), quotes (`.”` not a naive boundary), exactly-at-cap (unchanged), one-over-cap (split), empty/blank (no crash), realistic prose (reassembles, never exceeds cap).

**Residual, declared:** a first sentence far shorter than 3,262 ms can still under-bank at the very first boundary; that is the deferred D21 start-prebuffer watermark, out of this wave. The splitter strictly improves every such case and never regresses one.

**Build law honoured:** JVM `:app:testDebugUnitTest` + `:app:assembleRelease` (R8) only; JDK 21; `--no-daemon`; never `connectedAndroidTest`/`installDebug`; commit only named files. Baseline 436 → 437 tests.

**Placeholder scan:** No TBD/TODO. Task 1 and Task 2 contain complete code; Task 3 is a measurement and says so.
