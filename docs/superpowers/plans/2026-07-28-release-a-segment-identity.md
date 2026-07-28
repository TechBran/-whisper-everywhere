# Release A — Segment Identity & Ordering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every committed audio segment a monotonic identity and a guaranteed single resolution, so that when C2 adds concurrent cloud transcription, out-of-order results cannot silently delete the user's text — with **zero user-visible change** to the shipped local pipeline.

**Architecture:** `commit()` allocates a `seq` inside the same lock that snapshots the PCM, which alone fixes an existing enqueue race. Every allocated `seq` resolves exactly once through a new `onSegmentResolved(seq, outcome)` callback — including blank results, which today emit nothing. A pure `SegmentOrderer` holds completed-but-blocked outcomes and releases only a contiguous run from the head; at local's permanent `maxInFlight = 1` it is a provable pass-through, so timing is unchanged.

**Tech Stack:** Kotlin 2.0.21, JUnit 4, pure-JVM logic classes (no Android), existing `LocalWhisperEngine` single-thread executor.

## Global Constraints

- **ZERO user-visible regression.** This release ships no feature. A local-only user must not be able to tell it happened. Any behaviour change that is not a listed bug fix is a defect.
- **`java` is NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`. Use `.\gradlew.bat --no-daemon`.
- **NEVER run `connectedAndroidTest` or `installDebug`.** AGP's instrumented task uninstalls the app on teardown and has twice destroyed the user's 500+ MB of models. Compile-check with `:app:compileDebugAndroidTestKotlin`. To actually RUN an instrumented test, use `adb install -r` for both APKs then `adb shell am instrument -w -e class <FQCN> com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner` — that path does **not** uninstall.
- **`assembleRelease` is the signal that matters.** R8 runs only there; this project has a history of release-only failures invisible to debug builds.
- **Unit tests run with `unitTests.isReturnDefaultValues = true`** (`app/build.gradle.kts:166`) — an `android.*` call in JVM-unit-tested code returns a type default instead of throwing, so a broken dependency can PASS silently. Keep the new logic classes free of `android.*`.
- **Baseline: 20 suites / 169 tests / 0 failures**, `assembleDebug` and `assembleRelease` both green.
- **Preserve these hard-won invariants:** C1 generation-token cancellation, C2 playback thread as the AudioTrack's sole owner, I3 focus-ownership check, and the explicit `object : Function1<FloatArray, Int>` at the sherpa JNI boundary (a lambda there is a proven SIGABRT, commit `b19233c`).
- **`LocalWhisperEngine`'s executor MUST stay single-threaded.** Its KDoc says so three times: all native `whisper_context` access is serialised on that thread, and a second context is a multi-hundred-MB OOM path in a foreground service that already wires `releaseContext()` to `onTrimMemory`.
- **Do not touch** `TtsEngine.kt`, `TtsDiag.kt`, `TtsDiagMath.kt`, the model catalog, or any C1 provider/credential file.

---

## Why this release exists

C2 adds cloud transcription with more than one request in flight. Cloud results **will** complete out of order — a short utterance overtakes a long one, or one retries after a 429.

Injection is a full-field read-modify-write: `WhisperAccessibilityService.kt` reads the entire field, splices, and writes the whole rebuilt string back with `ACTION_SET_TEXT`. So two out-of-order injections do not merely scramble word order — **the second reads pre-first text and writes it back, silently deleting the first segment.** `ACTION_SET_SELECTION` appears zero times in this codebase and nothing records where a segment landed, so retroactive repair is impossible.

Three concrete gaps block C2 today, all verified 2026-07-28:

1. **Four lifecycle methods escape the interface.** `prewarm`/`shutdown`/`awaitIdle`/`releaseContext` are reached via `(x as? LocalWhisperEngine)?` at `FloatingBubbleService.kt:259, :510, :1528, :1607`. For any second implementation they silently no-op. `:1528` is the dangerous one — `awaitIdle` is the fence that drains in-flight transcribes before `close()` detaches the listener; skipping it drops every pending segment via the identity guard, reproducing the "No speech detected despite valid audio" bug the in-code comment documents as already fixed once.
2. **An enqueue race.** `commit()` snapshots the buffer under `bufferLock` (`LocalWhisperEngine.kt:151-154`) but calls `executor.execute` outside it. `commit()` is invoked from the audio thread and from the main thread (`switchSource`, projection consent, `stopRecording`), so two callers can snapshot A-then-B and enqueue B-then-A.
3. **Blank results emit no callback at all** (`LocalWhisperEngine.kt:181-184` logs "dropped"). Harmless today; under an orderer that seq never resolves and every later segment is held forever.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/whispereverywhere/transcription/SegmentOutcome.kt` | **Create.** Sealed outcome type. Pure. |
| `app/src/main/java/com/whispereverywhere/transcription/SegmentOrderer.kt` | **Create.** Strict in-order release. Pure, no Android. |
| `app/src/test/java/com/whispereverywhere/transcription/SegmentOrdererTest.kt` | **Create.** JVM tests. |
| `app/src/main/java/com/whispereverywhere/transcription/SegmentQuality.kt` | **Create.** Repetition/plausibility gate. Pure. |
| `app/src/test/java/com/whispereverywhere/transcription/SegmentQualityTest.kt` | **Create.** JVM tests. |
| `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt` | **Modify.** Lift lifecycle onto the interface; `commit(): Long`; `onSegmentResolved`. |
| `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt` | **Modify.** Allocate seq under the lock; resolve every seq exactly once. |
| `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt` | **Modify.** Drop the four downcasts; feed the orderer. |
| `app/src/main/java/com/whispereverywhere/util/AudioMath.kt` | **Modify.** Add `peak`. |
| `app/src/main/java/com/whispereverywhere/util/RetryPolicy.kt` | **Modify.** Add `delayOverrideMs`. |
| `app/src/main/java/com/whispereverywhere/util/SpeechSegmenter.kt` | **Modify.** Adaptive silence floor. |
| `app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt` | **Create.** Measure real STT latency. |

---

## Task 1: Small prerequisites — `AudioMath.peak` and `RetryPolicy.delayOverrideMs`

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/util/AudioMath.kt`
- Modify: `app/src/main/java/com/whispereverywhere/util/RetryPolicy.kt`
- Test: `app/src/test/java/com/whispereverywhere/AudioMathTest.kt`, `app/src/test/java/com/whispereverywhere/util/RetryPolicyTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, used later and by C2:
  - `AudioMath.peak(pcm: ByteArray): Float` — max |sample| / 32768f, 0f for empty
  - `RetryPolicy.retry(shouldRetry, delayOverrideMs, block)` — new middle parameter, defaulted so existing call sites compile unchanged

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/com/whispereverywhere/AudioMathTest.kt` (read it first, match its idiom):

```kotlin
    @Test fun peak_is_the_largest_absolute_sample_normalised() {
        // PCM16 LE. 0x7FFF = 32767 -> ~1.0
        val pcm = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x7F)
        assertEquals(1.0f, AudioMath.peak(pcm), 0.001f)
    }

    @Test fun peak_of_silence_is_zero() {
        assertEquals(0.0f, AudioMath.peak(ByteArray(64)), 0.0001f)
    }

    @Test fun peak_of_empty_is_zero_not_a_crash() {
        assertEquals(0.0f, AudioMath.peak(ByteArray(0)), 0.0001f)
    }

    @Test fun peak_handles_a_trailing_odd_byte_without_throwing() {
        // AudioRecord can hand back an odd length; a naive stride-2 read would run off the end.
        assertEquals(0.5f, AudioMath.peak(byteArrayOf(0x00, 0x40, 0x11)), 0.01f)
    }

    @Test fun peak_treats_negative_full_scale_as_full_scale() {
        // -32768 has no positive counterpart; abs() of it overflows in Int if done naively.
        val pcm = byteArrayOf(0x00, 0x80.toByte())
        assertTrue(AudioMath.peak(pcm) > 0.99f)
    }
```

Append to `app/src/test/java/com/whispereverywhere/util/RetryPolicyTest.kt`:

```kotlin
    @Test fun delay_override_wins_over_the_computed_backoff() = runBlocking {
        // A server saying "wait 8 seconds" must be honoured. Without this hook the client waits
        // ~0.2s then ~0.4s, burning every attempt inside a window that is still closed.
        val policy = RetryPolicy(maxAttempts = 2, baseDelayMs = 10, maxDelayMs = 20)
        val seen = mutableListOf<Long>()
        var attempts = 0
        runCatching {
            policy.retry(
                shouldRetry = { true },
                delayOverrideMs = { _, _ -> 1L }.also { seen.add(1L) },
            ) { attempts++; throw RuntimeException("boom") }
        }
        assertEquals(2, attempts)
    }

    @Test fun a_null_override_falls_back_to_the_computed_backoff() = runBlocking {
        val policy = RetryPolicy(maxAttempts = 2, baseDelayMs = 1, maxDelayMs = 2)
        var attempts = 0
        runCatching {
            policy.retry(shouldRetry = { true }, delayOverrideMs = { _, _ -> null }) {
                attempts++; throw RuntimeException("boom")
            }
        }
        assertEquals(2, attempts)
    }

    @Test fun existing_two_arg_call_sites_still_compile_and_behave() = runBlocking {
        // LocalWhisperEngine calls retry { ... } with no override. The new parameter must be
        // defaulted, not required.
        val policy = RetryPolicy(maxAttempts = 1)
        assertEquals("ok", policy.retry { "ok" })
    }
```

- [ ] **Step 2: Run to verify they fail**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.AudioMathTest" --tests "com.whispereverywhere.util.RetryPolicyTest"
```
Expected: FAIL — `Unresolved reference: peak`, and `delayOverrideMs` unknown.

- [ ] **Step 3: Implement `AudioMath.peak`**

Add to `AudioMath.kt`:

```kotlin
    /**
     * Largest absolute sample, normalised to 0f..1f. Mirrors the native peak-energy gate in
     * whisper_jni.cpp so the same "is there anything here at all" question can be asked in Kotlin
     * before an expensive or billable operation.
     *
     * Reads whole samples only — [AudioRecord] can return an odd byte count, and a stride-2 loop
     * that ignores that runs off the end. Uses [kotlin.math.abs] on an Int, not a Short: the Short
     * -32768 has no positive counterpart and abs() of it stays negative.
     */
    fun peak(pcm: ByteArray): Float {
        var max = 0
        var i = 0
        val end = pcm.size - 1
        while (i < end) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val a = kotlin.math.abs(s)
            if (a > max) max = a
            i += 2
        }
        return (max / 32768f).coerceIn(0f, 1f)
    }
```

- [ ] **Step 4: Implement `RetryPolicy.delayOverrideMs`**

Read `RetryPolicy.kt` first. Add the parameter **between** `shouldRetry` and `block` so existing positional/trailing-lambda call sites are unaffected:

```kotlin
    /**
     * @param delayOverrideMs given (throwable, attempt), returns a delay to use INSTEAD of the
     *   computed backoff, or null to keep the computed one. Exists so a server-stated `Retry-After`
     *   can be honoured: without it a 429 asking for 8 seconds is answered with ~0.2s then ~0.4s,
     *   burning every attempt inside a window that is still closed and extending the outage the
     *   client is reacting to.
     */
    suspend fun <T> retry(
        shouldRetry: (Throwable) -> Boolean = { true },
        delayOverrideMs: (Throwable, Int) -> Long? = { _, _ -> null },
        block: suspend (attempt: Int) -> T,
    ): T
```

Inside the retry loop, replace the delay computation with:

```kotlin
            val wait = delayOverrideMs(t, attempt) ?: delayForAttempt(attempt)
            kotlinx.coroutines.delay(wait)
```

- [ ] **Step 5: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: **177 tests** (169 + 8), 0 failures. Then `.\gradlew.bat :app:assembleRelease --no-daemon` green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/util/AudioMath.kt \
        app/src/main/java/com/whispereverywhere/util/RetryPolicy.kt \
        app/src/test/java/com/whispereverywhere/AudioMathTest.kt \
        app/src/test/java/com/whispereverywhere/util/RetryPolicyTest.kt
git commit -m "feat(util): AudioMath.peak and RetryPolicy.delayOverrideMs

Both are prerequisites for segment identity and, later, cloud dispatch.

peak() mirrors the native peak-energy gate in whisper_jni.cpp so the same
'is there anything here' question can be asked in Kotlin before an
expensive or billable operation. It reads whole samples only (AudioRecord
can return an odd byte count) and abs()es an Int, not a Short — Short
-32768 has no positive counterpart and abs() of it stays negative.

delayOverrideMs lets a server-stated Retry-After be honoured. Without it a
429 asking for 8 seconds is answered with ~0.2s then ~0.4s, burning every
attempt inside a window that is still closed. Defaulted, so existing call
sites are untouched."
```

---

## Task 2: `SegmentOutcome` + `SegmentOrderer`

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/SegmentOutcome.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/SegmentOrderer.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/SegmentOrdererTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, used by Tasks 3-4:
  - `sealed interface SegmentOutcome { data class Text(val text: String); data object EmptyExpected; data object EmptyUnexpected; data class Lost(val reason: String) }`
  - `class SegmentOrderer(lostMarker: String = "[…]")`
  - `fun onResolved(seq: Long, outcome: SegmentOutcome): Release`
  - `fun skip(seq: Long): Release`
  - `fun flush(): Release`
  - `fun pendingCount(): Int`
  - `data class Release(val text: String, val lostSegments: Int)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/transcription/SegmentOrdererTest.kt`:

```kotlin
package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentOrdererTest {

    private fun text(s: String) = SegmentOutcome.Text(s)

    @Test fun in_order_results_release_immediately() {
        val o = SegmentOrderer()
        assertEquals("one", o.onResolved(0, text("one")).text)
        assertEquals("two", o.onResolved(1, text("two")).text)
    }

    @Test fun an_out_of_order_result_is_held_until_its_predecessor_lands() {
        // THE reason this class exists. Injection is a full-field read-modify-write, so releasing
        // seq 1 before seq 0 would make the next write read pre-seq-0 text and write it back —
        // silently DELETING seq 0 rather than merely reordering it.
        val o = SegmentOrderer()
        assertEquals("", o.onResolved(1, text("second")).text)
        assertEquals(1, o.pendingCount())
        assertEquals("first second", o.onResolved(0, text("first")).text)
        assertEquals(0, o.pendingCount())
    }

    @Test fun a_whole_held_run_releases_at_once_as_one_string() {
        // One injection per burst is strictly better than one per segment: fewer full-field
        // rewrites, fewer chances to clobber concurrent typing, fewer cursor jumps.
        val o = SegmentOrderer()
        o.onResolved(3, text("d"))
        o.onResolved(1, text("b"))
        o.onResolved(2, text("c"))
        assertEquals("a b c d", o.onResolved(0, text("a")).text)
    }

    @Test fun expected_empties_contribute_nothing_but_still_unblock_the_head() {
        // A silence-only segment is not a loss — it must resolve so later segments can release.
        val o = SegmentOrderer()
        assertEquals("", o.onResolved(0, SegmentOutcome.EmptyExpected).text)
        assertEquals("after", o.onResolved(1, text("after")).text)
    }

    @Test fun an_unexpected_empty_emits_the_lost_marker() {
        // Real voiced audio that came back empty IS a lost sentence and must be visible.
        val o = SegmentOrderer()
        assertEquals("[…]", o.onResolved(0, SegmentOutcome.EmptyUnexpected).text)
    }

    @Test fun consecutive_losses_collapse_to_a_single_marker() {
        // A 90-second outage must produce one ellipsis, not thirty.
        val o = SegmentOrderer()
        val sb = StringBuilder()
        repeat(5) { sb.append(o.onResolved(it.toLong(), SegmentOutcome.Lost("offline")).text) }
        assertEquals("[…]", sb.toString())
    }

    @Test fun a_loss_between_two_texts_yields_exactly_one_marker() {
        val o = SegmentOrderer()
        val sb = StringBuilder()
        sb.append(o.onResolved(0, text("before")).text)
        sb.append(o.onResolved(1, SegmentOutcome.Lost("timeout")).text)
        sb.append(o.onResolved(2, text("after")).text)
        assertEquals("before […] after", sb.toString().trim().replace(Regex("\\s+"), " "))
    }

    @Test fun skip_unblocks_a_seq_that_will_never_resolve() {
        // A merged-away or wrong-generation segment must not stall the head forever.
        val o = SegmentOrderer()
        o.onResolved(1, text("b"))
        assertEquals("b", o.skip(0).text)
    }

    @Test fun flush_releases_everything_held_and_skips_the_holes() {
        // Held text is uniquely fragile: unlike per-segment injection, the buffer deliberately
        // accumulates finished work in RAM whose only exit is this call.
        val o = SegmentOrderer()
        o.onResolved(1, text("b"))
        o.onResolved(3, text("d"))
        val r = o.flush()
        assertEquals("b d", r.text)
        assertEquals(0, o.pendingCount())
    }

    @Test fun flush_on_an_empty_orderer_is_harmless() {
        assertEquals("", SegmentOrderer().flush().text)
    }

    @Test fun a_duplicate_seq_is_ignored_rather_than_double_injected() {
        // A retry that timed out client-side but succeeded server-side would otherwise inject twice.
        val o = SegmentOrderer()
        assertEquals("one", o.onResolved(0, text("one")).text)
        assertEquals("", o.onResolved(0, text("one")).text)
    }

    @Test fun release_reports_how_many_segments_were_lost() {
        val o = SegmentOrderer()
        val r = o.onResolved(0, SegmentOutcome.Lost("offline"))
        assertEquals(1, r.lostSegments)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.SegmentOrdererTest"
```
Expected: FAIL — `Unresolved reference: SegmentOrderer`.

- [ ] **Step 3: Write `SegmentOutcome`**

Create `app/src/main/java/com/whispereverywhere/transcription/SegmentOutcome.kt`:

```kotlin
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
```

- [ ] **Step 4: Write `SegmentOrderer`**

Create `app/src/main/java/com/whispereverywhere/transcription/SegmentOrderer.kt`:

```kotlin
package com.whispereverywhere.transcription

/**
 * Releases segment outcomes into the user's text field in STRICT seq order, never
 * speculate-then-correct.
 *
 * This is forced by the injection mechanism, not chosen for tidiness. Injection is a full-field
 * read-modify-write (`ACTION_SET_TEXT` with the entire rebuilt string), so two out-of-order
 * injections do not merely scramble word order — the second reads pre-first text and writes it
 * back, SILENTLY DELETING the first segment. Retroactive repair is impossible: nothing records
 * where a segment landed, `ACTION_SET_SELECTION` is never used, and two of three delivery paths
 * are clipboard+PASTE with no position control at all.
 *
 * Pure and Android-free so every ordering case is unit-testable. Main-thread confined in
 * production — callers marshal through the service's existing Dispatchers.Main hop.
 *
 * At local's permanent maxInFlight = 1 this is a provable pass-through: results always arrive with
 * seq == head, so local delivery timing is unchanged by its presence.
 */
class SegmentOrderer(private val lostMarker: String = LOST_MARKER) {

    private var head = 0L
    private val resolved = HashMap<Long, SegmentOutcome>()
    /** True when the immediately preceding released segment was a loss, so runs collapse. */
    private var lastReleasedWasLost = false

    data class Release(val text: String, val lostSegments: Int)

    fun onResolved(seq: Long, outcome: SegmentOutcome): Release {
        // Late duplicate (a retry that timed out client-side but succeeded server-side) — dropping
        // it is the whole benefit of having identity.
        if (seq < head || resolved.containsKey(seq)) return EMPTY
        resolved[seq] = outcome
        return drain()
    }

    /** Unblock a seq that will never resolve — merged away, or from a superseded generation. */
    fun skip(seq: Long): Release {
        if (seq < head) return EMPTY
        resolved[seq] = SKIPPED
        return drain()
    }

    /**
     * Release everything held, skipping the holes. MUST be called on every session exit path —
     * stop, generation bump, teardown, drain timeout. Held text is uniquely fragile: unlike
     * per-segment injection it accumulates finished work in RAM whose only exit is this call, and
     * the pile is largest exactly when the user is most likely to end the session.
     */
    fun flush(): Release {
        if (resolved.isEmpty()) return EMPTY
        val maxSeq = resolved.keys.max()
        var s = head
        while (s <= maxSeq) { resolved.putIfAbsent(s, SKIPPED); s++ }
        return drain()
    }

    fun pendingCount(): Int = resolved.size

    private fun drain(): Release {
        val sb = StringBuilder()
        var lost = 0
        while (true) {
            val outcome = resolved.remove(head) ?: break
            head++
            when (outcome) {
                is SegmentOutcome.Text -> {
                    if (outcome.text.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(outcome.text.trim())
                        lastReleasedWasLost = false
                    }
                }
                SegmentOutcome.EmptyExpected -> Unit          // silence: contributes nothing
                SKIPPED -> Unit                               // never existed for the user
                SegmentOutcome.EmptyUnexpected, is SegmentOutcome.Lost -> {
                    lost++
                    // Collapse consecutive losses: a 90-second outage must produce ONE marker,
                    // not thirty, or the user is left deleting a wall of ellipses.
                    if (!lastReleasedWasLost) {
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(lostMarker)
                        lastReleasedWasLost = true
                    }
                }
            }
        }
        return if (sb.isEmpty() && lost == 0) EMPTY else Release(sb.toString(), lost)
    }

    private companion object {
        const val LOST_MARKER = "[…]"
        val EMPTY = Release("", 0)
        /** Internal marker for a seq that was skipped; never surfaced to callers. */
        val SKIPPED = SegmentOutcome.Text("")
    }
}
```

- [ ] **Step 5: Run to verify it passes**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.SegmentOrdererTest"
```
Expected: PASS, 12 tests. Full suite: **189 tests**, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/SegmentOutcome.kt \
        app/src/main/java/com/whispereverywhere/transcription/SegmentOrderer.kt \
        app/src/test/java/com/whispereverywhere/transcription/SegmentOrdererTest.kt
git commit -m "feat(transcription): SegmentOutcome + SegmentOrderer — strict in-order release

Forced by the injection mechanism, not chosen for tidiness. Injection is a
full-field read-modify-write, so two out-of-order injections do not merely
scramble word order — the second reads pre-first text and writes it back,
SILENTLY DELETING the first segment. Retroactive repair is impossible:
nothing records where a segment landed and ACTION_SET_SELECTION is never
used in this codebase.

The two empties are deliberately distinct. Silence that VAD proved empty
contributes nothing; real voiced audio that came back empty is a lost
sentence and gets a marker. Consecutive losses collapse to ONE marker so a
90-second outage does not leave the user deleting thirty ellipses.

At local's permanent maxInFlight = 1 this is a provable pass-through —
results always arrive with seq == head, so local timing is unchanged."
```

---

## Task 3: `SegmentQuality` — the HTTP-200-garbage gate

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/SegmentQuality.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/SegmentQualityTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, used by Task 4 and C2:
  - `enum class QualityVerdict { ACCEPT, REJECT_REPETITION, REJECT_IMPLAUSIBLE }`
  - `SegmentQuality.assess(text: String, voicedMs: Int): QualityVerdict`
  - `SegmentQuality.compressionRatio(text: String): Double`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/transcription/SegmentQualityTest.kt`:

```kotlin
package com.whispereverywhere.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentQualityTest {

    @Test fun ordinary_speech_is_accepted() {
        assertEquals(
            QualityVerdict.ACCEPT,
            SegmentQuality.assess("The quick brown fox jumps over the lazy dog.", voicedMs = 3000),
        )
    }

    @Test fun a_degenerate_repetition_loop_is_rejected() {
        // The failure this app actually hit on a 10-minute YouTube capture on 2026-07-18.
        val looped = "thank you for watching ".repeat(40)
        assertEquals(QualityVerdict.REJECT_REPETITION, SegmentQuality.assess(looped, voicedMs = 8000))
    }

    @Test fun compression_ratio_separates_looped_from_natural_text() {
        // Ported from whisper.cpp's own heuristic — the gate it trips on internally, so it is
        // pre-calibrated on exactly this failure mode.
        val natural = SegmentQuality.compressionRatio(
            "She sells sea shells by the sea shore and the shells she sells are surely seashells.",
        )
        val looped = SegmentQuality.compressionRatio("ha ".repeat(200))
        assertTrue("natural=$natural must be below the 2.4 gate", natural < 2.4)
        assertTrue("looped=$looped must be above the 2.4 gate", looped > 2.4)
    }

    @Test fun an_implausible_word_rate_is_rejected() {
        // 40 words in 1 second of voiced audio is not speech.
        val words = (1..40).joinToString(" ") { "word$it" }
        assertEquals(QualityVerdict.REJECT_IMPLAUSIBLE, SegmentQuality.assess(words, voicedMs = 1000))
    }

    @Test fun a_short_utterance_is_not_penalised_for_being_short() {
        // "Yes." in 400 ms is 2.5 w/s — well within range and must not be rejected.
        assertEquals(QualityVerdict.ACCEPT, SegmentQuality.assess("Yes.", voicedMs = 400))
    }

    @Test fun blank_text_is_accepted_because_emptiness_is_not_a_quality_problem() {
        // The orderer classifies empties; this gate must not also claim them.
        assertEquals(QualityVerdict.ACCEPT, SegmentQuality.assess("", voicedMs = 1000))
    }

    @Test fun zero_voiced_ms_does_not_divide_by_zero() {
        assertEquals(QualityVerdict.ACCEPT, SegmentQuality.assess("hello", voicedMs = 0))
    }

    @Test fun a_long_legitimate_sentence_is_not_mistaken_for_a_loop() {
        val real = "In the morning we walked along the river and talked about the plans " +
            "for the summer, which seemed impossibly far away at the time."
        assertEquals(QualityVerdict.ACCEPT, SegmentQuality.assess(real, voicedMs = 9000))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.SegmentQualityTest"
```
Expected: FAIL — `Unresolved reference: SegmentQuality`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/whispereverywhere/transcription/SegmentQuality.kt`:

```kotlin
package com.whispereverywhere.transcription

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Catches a transcript that is syntactically fine but semantically garbage.
 *
 * `TranscriptText.clean()` is the entire post-processing pipeline today: it strips bracketed groups
 * and a fixed keyword list. "Thank you for watching." matches neither, is not blank, and is typed
 * straight into the user's outgoing message — and it sets `sessionProducedText = true`, which
 * disarms the app's only remaining safety net.
 *
 * Pure and Android-free. Applied to EVERY engine, local and cloud: local has whisper's own
 * temperature fallback as a partial defence, but a cloud response is a finished string with no
 * equivalent.
 */
enum class QualityVerdict { ACCEPT, REJECT_REPETITION, REJECT_IMPLAUSIBLE }

object SegmentQuality {

    /** Above this, the text is compressing far too well to be natural language. */
    private const val COMPRESSION_GATE = 2.4

    /** Words per second above which the text cannot be speech at the stated duration. */
    private const val MAX_WORDS_PER_SECOND = 8.0

    /** Below this much text, ratios are meaningless — a short string always compresses badly. */
    private const val MIN_CHARS_FOR_RATIO = 32

    fun assess(text: String, voicedMs: Int): QualityVerdict {
        val trimmed = text.trim()
        // Emptiness is the orderer's business, not a quality failure. Claiming it here would
        // double-report the same condition.
        if (trimmed.isEmpty()) return QualityVerdict.ACCEPT

        if (trimmed.length >= MIN_CHARS_FOR_RATIO && compressionRatio(trimmed) > COMPRESSION_GATE) {
            return QualityVerdict.REJECT_REPETITION
        }

        if (voicedMs > 0) {
            val words = trimmed.split(Regex("\\s+")).count { it.isNotBlank() }
            val wps = words / (voicedMs / 1000.0)
            if (wps > MAX_WORDS_PER_SECOND) return QualityVerdict.REJECT_IMPLAUSIBLE
        }

        return QualityVerdict.ACCEPT
    }

    /**
     * Ratio of raw bytes to DEFLATE-compressed bytes. This is literally the heuristic whisper.cpp
     * trips on internally (see the entropy/compression note in whisper_jni.cpp), so the 2.4 gate is
     * pre-calibrated on the exact degenerate-loop failure this app hit on 2026-07-18.
     */
    fun compressionRatio(text: String): Double {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.isEmpty()) return 1.0
        val deflater = Deflater()
        val out = ByteArrayOutputStream(raw.size)
        val buf = ByteArray(1024)
        try {
            deflater.setInput(raw)
            deflater.finish()
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        } finally {
            deflater.end()
        }
        val compressed = out.size()
        return if (compressed == 0) 1.0 else raw.size.toDouble() / compressed.toDouble()
    }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.SegmentQualityTest"
```
Expected: PASS, 8 tests. Full suite: **197 tests**, 0 failures.

If `natural < 2.4` fails, the sample text is too short or too repetitive to be a fair test — report the actual ratios rather than moving the gate. The gate value is inherited from whisper.cpp and must not be tuned to make a test pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/SegmentQuality.kt \
        app/src/test/java/com/whispereverywhere/transcription/SegmentQualityTest.kt
git commit -m "feat(transcription): SegmentQuality — reject degenerate repetition loops

TranscriptText.clean() is the entire post-processing pipeline today: it
strips bracketed groups and a fixed keyword list. 'Thank you for watching.'
matches neither, is not blank, and gets typed into the user's outgoing
message — while setting sessionProducedText = true, which disarms the
app's only remaining safety net.

The compression-ratio gate is ported from whisper.cpp's own heuristic, so
2.4 is pre-calibrated on exactly the degenerate loop this app hit on a
10-minute YouTube capture on 2026-07-18. Do not tune it to make a test
pass.

Applied to every engine: local has whisper's temperature fallback as a
partial defence, but a cloud response is a finished string with none."
```

---

## Task 4: Segment identity in the engine, and the terminal-callback contract

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt`
- Modify: `app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt`
- Modify: `app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt`

**Interfaces:**
- Consumes: `SegmentOutcome`, `SegmentOrderer` (Task 2), `SegmentQuality` (Task 3), `AudioMath.peak` (Task 1).
- Produces: the interface C2's `CloudTranscriptionEngine` implements.

**This is the highest-risk task in the plan.** It touches the shipped dictation path. Read `LocalWhisperEngine.kt` and the relevant parts of `FloatingBubbleService.kt` in full before editing.

- [ ] **Step 1: Lift the four lifecycle methods onto the interface**

In `TranscriptionEngine.kt`, add to the interface with **no-op defaults**, so a second implementation is not forced to care:

```kotlin
    /**
     * Warm any expensive backing resource. Default no-op.
     *
     * These four were previously reached via `(engine as? LocalWhisperEngine)?` downcasts at
     * FloatingBubbleService.kt:259, :510, :1528 and :1607. For any second implementation those
     * silently no-op — and :1528 is [awaitIdle], the fence that drains in-flight transcribes
     * before close() detaches the listener. Skipping it drops every pending segment via the
     * identity guard, which is exactly the "No speech detected despite valid audio" bug the
     * in-code comment there records as already fixed once.
     */
    fun prewarm() {}

    /** Release the engine permanently. Default no-op. */
    fun shutdown() {}

    /**
     * Block until every already-submitted segment has resolved, or [timeoutMs] elapses.
     * Returns true if it drained. Default: nothing outstanding, so true.
     */
    fun awaitIdle(timeoutMs: Long): Boolean = true

    /** Release heavy resources under memory pressure, keeping the engine reusable. Default no-op. */
    fun releaseContext() {}
```

Then change `commit()` and the listener:

```kotlin
    /**
     * Cut a segment. Returns the monotonic seq allocated for it, or -1 if there was nothing to
     * commit. Every returned seq is guaranteed to reach [Listener.onSegmentResolved] exactly once.
     */
    fun commit(): Long
```

```kotlin
    interface Listener {
        fun onOpen()
        fun onDelta(text: String)
        /**
         * Terminal result for exactly one committed segment. EVERY seq returned by [commit] MUST
         * arrive here exactly once — including empties and failures. A seq that never resolves
         * stalls the SegmentOrderer head forever and holds every later segment with it.
         */
        fun onSegmentResolved(seq: Long, outcome: SegmentOutcome)
        fun onError(message: String)
        fun onClosed()
    }
```

Remove `onCompleted(text: String)` — there is exactly one implementation to update.

- [ ] **Step 2: Allocate seq inside the lock, and resolve every seq**

In `LocalWhisperEngine.kt`, add a session-scoped counter beside the existing fields:

```kotlin
    /** Allocated INSIDE bufferLock with the PCM snapshot — see commit(). */
    private var nextSeq = 0L
```

Reset it to 0 in `connect()` alongside the other per-session state.

Rewrite `commit()` so the seq is allocated under the same lock that snapshots the PCM:

```kotlin
    override fun commit(): Long {
        val myListener = listener ?: return -1L
        // seq is allocated INSIDE bufferLock with the snapshot. That alone fixes a pre-existing
        // race: commit() previously snapshotted under the lock but called executor.execute
        // OUTSIDE it, and commit() is invoked from the audio thread AND the main thread
        // (switchSource, projection consent, stopRecording) — so two callers could snapshot
        // A-then-B and enqueue B-then-A, emitting the transcript out of order. Ordering is now a
        // function of audio order, not enqueue order.
        val (seq, pcm) = synchronized(bufferLock) {
            val snapshot = buffer.toByteArray()
            if (snapshot.isEmpty()) return -1L
            buffer.reset()
            (nextSeq++) to snapshot
        }
        executor.execute { runSegment(seq, pcm, myListener) }
        return seq
    }
```

Then rewrite the executor body as `runSegment`, whose **every** exit path resolves the seq:

```kotlin
    /**
     * Runs one segment to a terminal outcome. EVERY path through this function must call
     * onSegmentResolved exactly once — a seq that never resolves permanently stalls the orderer.
     * That is why the blank case, which previously just logged "dropped", now resolves explicitly.
     */
    private fun runSegment(seq: Long, pcm: ByteArray, myListener: Listener) {
        val outcome: SegmentOutcome = try {
            val ctx = ctxPtr
            if (ctx == 0L) {
                SegmentOutcome.Lost("engine not loaded")
            } else {
                val samples = AudioMath.pcm16ToFloat(pcm)
                val text = runBlocking { retry.retry { backend.transcribe(ctx, samples, lang) } }
                val cleaned = TranscriptText.clean(text)
                when {
                    cleaned.isNotBlank() -> {
                        val voicedMs = (pcm.size / 32)
                        if (SegmentQuality.assess(cleaned, voicedMs) == QualityVerdict.ACCEPT) {
                            SegmentOutcome.Text(cleaned)
                        } else {
                            // Degenerate repetition or an impossible word rate. Not a loss the
                            // user should see a marker for — it is garbage we chose not to type.
                            SegmentOutcome.EmptyExpected
                        }
                    }
                    // Distinguish "there was nothing there" from "we lost real speech" using the
                    // same peak-energy question the native side asks.
                    AudioMath.peak(pcm) < SILENCE_PEAK -> SegmentOutcome.EmptyExpected
                    else -> SegmentOutcome.EmptyUnexpected
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("WE-DIAG", "transcribe THREW", t)
            SegmentOutcome.Lost(t.message ?: "transcription failed")
        }
        if (listener === myListener) myListener.onSegmentResolved(seq, outcome)
    }
```

Add to the companion object:

```kotlin
        /** Ports the native peak gate in whisper_jni.cpp so both sides agree on "digital silence". */
        private const val SILENCE_PEAK = 0.005f
```

- [ ] **Step 3: Update the service**

In `FloatingBubbleService.kt`:

1. **Delete the four downcasts** at `:259`, `:510`, `:1528`, `:1607` — call the interface methods directly (`engine.prewarm()`, `transcriptionEngine?.shutdown()`, `transcriptionEngine?.awaitIdle(FINALIZE_TIMEOUT_MS)`, `transcriptionEngine?.releaseContext()`).
2. Add a `SegmentOrderer` field, recreated per session next to the other per-session state.
3. Replace the anonymous listener's `onCompleted` with:

```kotlin
            override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
                serviceScope.launch(Dispatchers.Main) {
                    val release = segmentOrderer.onResolved(seq, outcome)
                    if (release.text.isNotBlank()) {
                        sessionProducedText = true
                        if (sessionContext != BubbleContext.TEXT_FIELD) {
                            transcriptSink?.append(release.text)
                        }
                        handleTranscriptionResult(release.text)
                    }
                }
            }
```

4. **Flush on every exit path.** In `stopRecording`'s finalize block, after `awaitIdle` returns, and in `teardownRealtime`/`onDestroy`, call:

```kotlin
                val tail = segmentOrderer.flush()
                if (tail.text.isNotBlank()) handleTranscriptionResult(tail.text)
```

Missing any exit path silently discards completed-but-held text. Grep for every place the session ends and confirm each one flushes.

- [ ] **Step 4: Verify nothing regressed**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: 197 tests, 0 failures; both builds green.

Then confirm the downcasts are gone:
```bash
grep -n "as? LocalWhisperEngine" app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt || echo "OK: no downcasts remain"
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt \
        app/src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt \
        app/src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt
git commit -m "feat(transcription): segment identity + terminal-callback contract

Three gaps that would each break the moment a second engine exists.

1. prewarm/shutdown/awaitIdle/releaseContext were reached via
   (x as? LocalWhisperEngine)? downcasts and would silently no-op for any
   other implementation. awaitIdle is the fence that drains in-flight
   transcribes before close() detaches the listener — skipping it drops
   every pending segment, which is the 'No speech detected despite valid
   audio' bug the in-code comment records as already fixed once. Now on
   the interface with no-op defaults.

2. commit() allocated no identity and enqueued OUTSIDE bufferLock while
   being callable from both the audio and main threads, so two callers
   could snapshot A-then-B and enqueue B-then-A. seq is now allocated
   inside the same lock as the PCM snapshot, making ordering a function of
   audio order rather than enqueue order.

3. A blank result emitted NO callback at all — harmless with one engine,
   a permanent orderer head-of-line stall with more than one. Every seq
   now reaches onSegmentResolved exactly once, and the peak-energy gate
   distinguishes 'nothing was there' from 'we lost real speech'.

Local stays single-threaded, so the orderer is a pass-through and delivery
timing is unchanged."
```

---

## Task 5: Adaptive silence floor

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/util/SpeechSegmenter.kt`
- Test: `app/src/test/java/com/whispereverywhere/SpeechSegmenterTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: no new API; behaviour only.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/whispereverywhere/SpeechSegmenterTest.kt`:

```kotlin
    @Test fun a_quiet_room_behaves_exactly_as_before() {
        // The adaptive floor must be provably non-regressive: with a low noise floor the effective
        // silence threshold stays at the original 250.
        val s = SpeechSegmenter()
        var t = 0L
        assertFalse(s.onAmplitude(600, t))          // speech opens the segment
        t += 900
        assertTrue("must close on a quiet sample after the hangover", s.onAmplitude(100, t))
    }

    @Test fun a_noisy_room_can_still_close_a_segment() {
        // THE bug this fixes. With a fixed 250 threshold, a room whose floor sits at ~350 can
        // never satisfy the close condition: speech opens at >=500 but nothing ever drops to
        // <=250, so dispatch silently degrades to 15-second wall-clock chunks.
        val s = SpeechSegmenter()
        var t = 0L
        repeat(70) { s.onAmplitude(350, t); t += 32 }   // establish a ~350 noise floor
        assertFalse(s.onAmplitude(600, t))              // speech
        t += 900
        assertTrue("noisy room must still close", s.onAmplitude(350, t))
    }

    @Test fun the_adaptive_floor_never_rises_to_swallow_speech() {
        // effSilence is clamped below voiceThreshold, so a loud room cannot make speech itself
        // count as silence.
        val s = SpeechSegmenter()
        var t = 0L
        repeat(70) { s.onAmplitude(490, t); t += 32 }
        assertFalse("a sample at the voice threshold must never close a segment", s.onAmplitude(500, t))
    }
```

- [ ] **Step 2: Run to verify the noisy-room test fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.SpeechSegmenterTest"
```
Expected: `a_noisy_room_can_still_close_a_segment` FAILS; the other two pass.

- [ ] **Step 3: Implement the adaptive floor**

In `SpeechSegmenter.kt`, add an EMA of non-voiced amplitude and derive the effective threshold:

```kotlin
    /** EMA of non-voiced chunk amplitude — the room's noise floor. */
    private var floorEma = 0f

    /**
     * Effective silence threshold. `max()` with the configured floor makes regression IMPOSSIBLE
     * by construction: in a quiet room floorEma * 1.6 is below silenceThreshold, so this returns
     * exactly the original value and behaviour is byte-identical. `min()` with
     * voiceThreshold - 1 stops a loud room from raising the bar until speech itself counts as
     * silence.
     *
     * Fixes a real dead band: with a fixed 250, any room whose floor sits between 251 and 499
     * opens a segment (>=500) but can NEVER satisfy the close condition, so dispatch silently
     * degrades to the 15-second wall-clock cap with no log distinguishing it.
     *
     * Only the SILENCE threshold adapts. voiceThreshold stays fixed — raising it would hurt soft
     * talkers, which is the opposite of the goal.
     */
    private fun effectiveSilence(): Int =
        minOf(maxOf(silenceThreshold, (floorEma * NOISE_FLOOR_MULTIPLIER).toInt()), voiceThreshold - 1)
```

Update `floorEma` on every non-voiced sample, before the close check:

```kotlin
        if (amplitude < voiceThreshold) {
            floorEma = if (floorEma == 0f) amplitude.toFloat()
                       else floorEma + FLOOR_ALPHA * (amplitude - floorEma)
        }
```

and use `effectiveSilence()` in place of the literal `silenceThreshold` in the close condition.

Add to the companion object:

```kotlin
        /** ~2 s of 32 ms chunks to converge. */
        private const val FLOOR_ALPHA = 0.02f
        /** Headroom above the measured floor before a sample counts as silence. */
        private const val NOISE_FLOOR_MULTIPLIER = 1.6f
```

Reset `floorEma = 0f` in `reset()`.

- [ ] **Step 4: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon
```
Expected: **200 tests**, 0 failures — including the pre-existing `SpeechSegmenterTest` cases unchanged.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/util/SpeechSegmenter.kt \
        app/src/test/java/com/whispereverywhere/SpeechSegmenterTest.kt
git commit -m "fix(vad): adaptive silence floor closes the 251-499 RMS dead band

With a fixed silenceThreshold of 250, any room whose noise floor sits
between 251 and 499 opens a segment (amplitude >= 500) but can NEVER
satisfy the close condition (amplitude <= 250). Dispatch silently degrades
to the 15-second wall-clock cap, and the log line does not distinguish it
from a normal commit — so real-time feel dies in a noisy room with no
symptom anyone can see.

max() with the configured floor makes regression impossible by
construction: in a quiet room the adaptive value is below 250, so the
effective threshold is exactly the original and behaviour is byte-
identical. Only the SILENCE threshold adapts — raising voiceThreshold
would hurt soft talkers, which is the opposite of the goal."
```

---

## Task 6: `WhisperBenchTest` — measure what has only ever been modelled

**Files:**
- Create: `app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: measured numbers, not code.

Every local-vs-cloud latency claim in the spec is **modelled**, not measured. `app/src/androidTest/assets/jfk.wav` has been sitting unused since the repo's first commit. The TTS side was measured on 2026-07-27; the STT side never has been.

- [ ] **Step 1: Write the bench**

Create `app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt`, modelled on the existing `app/src/androidTest/java/com/whispereverywhere/tts/KokoroBenchTest.kt` — **read that file first and mirror its structure, logging tag, and skip behaviour.**

It must:
- `assumeTrue` a whisper model is installed, exactly as `KokoroBenchTest` does for the voice.
- Load `jfk.wav` from `androidTest/assets`, decode to PCM16 mono 16 kHz.
- Transcribe slices of roughly 1 s, 3 s, 8 s and 15 s, timing each.
- Log one grep-able line per slice with the tag `WE-BENCH`:
  `BENCH stt tier=<id> slice=<n>s audioMs=<a> wallMs=<w> rtf=<r>`
- Log a summary line with p50/p95/max RTF across the slices.
- Assert only that transcription produced non-blank text — **do not assert a latency threshold.** A bench that fails on a slow device is a flaky test, not a measurement.

- [ ] **Step 2: Compile-check**

```bash
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon
```

**Do NOT run `connectedAndroidTest`.** To actually execute it later:
```bash
adb install -r <buildDir>/app/outputs/apk/debug/app-debug.apk
adb install -r <buildDir>/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.whispereverywhere.whisper.WhisperBenchTest \
  com.whispereverywhere.test/androidx.test.runner.AndroidJUnitRunner
```
That path does not uninstall the app, so the user's models survive.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/com/whispereverywhere/whisper/WhisperBenchTest.kt
git commit -m "test(bench): WhisperBenchTest — measure STT latency for the first time

Every local-vs-cloud latency claim in the spec is modelled, not measured;
jfk.wav has been sitting unused in androidTest/assets since the first
commit. The TTS side was measured on 2026-07-27 and immediately overturned
five conclusions — including one wrong figure that had already reached the
spec. The STT side deserves the same treatment before C2 argues that cloud
is faster.

Asserts only that text came back. A bench that fails on a slow device is a
flaky test, not a measurement."
```

---

## Self-Review

**Spec coverage** (spec §3.6, §5.1, §5.2, §5.3, §9 Release A):

| Spec requirement | Task |
|---|---|
| Lift `prewarm`/`shutdown`/`awaitIdle`/`releaseContext` onto the interface | Task 4 Step 1 |
| Segment identity, seq allocated inside `bufferLock` | Task 4 Step 2 |
| Enqueue-race fix | Task 4 Step 2 (same change) |
| Terminal-callback contract — every seq resolves exactly once | Task 4 Step 2 |
| `SegmentOrderer`, strict in-order release, consecutive-loss collapse | Task 2 |
| `flush()` on every exit path | Task 4 Step 3 |
| `SegmentQuality` repetition gate | Task 3 |
| `AudioMath.peak` | Task 1 |
| `RetryPolicy.delayOverrideMs` | Task 1 |
| Adaptive silence floor | Task 5 |
| `WhisperBenchTest` | Task 6 |
| `pauseMs` injectable, default 800 | **Deferred.** The measured capture showed the hangover is a TTS-side concern; D12 defers the flip to a later release on evidence, and nothing in C2 depends on it. |
| argmin-RMS hard cut, SOFT/HARD cap split | **Deferred to C2**, where the cap interacts with per-request upload limits and is testable against a real provider |
| `SegmentDispatcher`, backpressure ladder, coalescing | **C2** — they exist to manage concurrency, which local (permanently `maxInFlight = 1`) does not have |

**Placeholder scan:** none. Every code step carries complete code, except Task 6, which specifies structure and required output because it must mirror an existing bench file the implementer will read.

**Type consistency:** `SegmentOutcome`'s four cases are used identically in `SegmentOrderer.drain`, `LocalWhisperEngine.runSegment`, and the service listener. `SegmentOrderer.{onResolved,skip,flush,pendingCount}` signatures in Task 2 match every call site in Task 4. `commit(): Long` returning `-1L` for "nothing committed" is consistent between the interface, the engine, and the service. `QualityVerdict.ACCEPT` is the only value `runSegment` branches on.

**One risk stated rather than hidden:** Task 4 changes the `Listener` interface and the shipped dictation path. Local remains single-threaded, so the orderer is a provable pass-through and timing should be identical — but "should be" is not "is". The on-device regression sweep (dictate into a field, device-audio capture, stop/start cycles) is mandatory before this merges, and it is the same sweep that caught nothing on the toolchain bump precisely because it was run.
