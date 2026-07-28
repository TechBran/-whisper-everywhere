# TTS Diagnostics (Release 0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the instrumentation that proves or refutes the TTS playback-gap diagnosis on the owner's real device, plus the one correctness bug that strands the playback thread — without tuning a single buffering constant.

**Architecture:** All derived numbers and all log formatting live in two pure Kotlin objects (`TtsDiagMath`, `TtsDiag`) with no `android.*` imports, unit-tested on the JVM in the existing `SpeechSegmenterTest` / `TranscriptSinkTest` idiom. `TtsEngine` only calls them and emits `Log.i`. This keeps the load-bearing arithmetic — especially the audible-silence formula — testable without a device.

**Tech Stack:** Kotlin 2.0.21, JUnit 4, Android `AudioTrack` (`getUnderrunCount`, `playbackHeadPosition`), sherpa-onnx v1.13.4, Gradle 8.14.4 / AGP 8.7.3.

## Global Constraints

- **Do NOT change any buffering behaviour in this release.** No watermarks, no prebuffer, no `THREAD_PRIORITY_URGENT_AUDIO`, no AudioTrack resizing. This release must measure the *current* pipeline. Landing a behaviour change here contaminates the baseline it exists to capture.
- **Keep `PERFORMANCE_MODE_LOW_LATENCY`.** Spec §6A.2 — switching to `NONE` enables `FLAG_DEEP_BUFFER`, and `flush()` cannot recall HAL-resident audio, which breaks the instant-stop guarantee.
- **Do not touch the sherpa callback's type.** It MUST remain an explicit `object : Function1<FloatArray, Int>`. A Kotlin lambda there is a proven on-device SIGABRT (commit `b19233c`).
- **Preserve these hard-won invariants:** C1 generation-token cancellation, C2 playback thread is the AudioTrack's sole owner, I3 focus-ownership check before `abandonFocus()`.
- **Build:** JDK 17 required. Always pass `--no-daemon` (OneDrive locks the build directory otherwise). Build output is already relocated outside OneDrive via `build.gradle.kts:20`.
- **minSdk = 26**, so `AudioTrack.getUnderrunCount()` (API 24) is available unconditionally — no version guard needed.
- **Log tag:** `WE-TTS`. Every diagnostic line starts with the literal `TTSDIAG ` so one `logcat | grep TTSDIAG` captures the whole session.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/whispereverywhere/tts/TtsDiagMath.kt` | **Create.** Pure arithmetic: audio duration, RTF, audible silence, percentiles. No Android. |
| `app/src/test/java/com/whispereverywhere/tts/TtsDiagMathTest.kt` | **Create.** JVM unit tests for the above. |
| `app/src/main/java/com/whispereverywhere/tts/TtsDiag.kt` | **Create.** Pure log-line formatters, one per record kind. No Android. |
| `app/src/test/java/com/whispereverywhere/tts/TtsDiagTest.kt` | **Create.** JVM unit tests for line format stability. |
| `app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt` | **Modify.** Task 1 (bug fix, ~line 291); Task 4 (wire diagnostics into the playback loop and the sherpa callback). |
| `app/src/androidTest/java/com/whispereverywhere/tts/TtsPlaybackThreadTest.kt` | **Create.** Instrumented regression test: no `tts-playback` thread outlives an utterance. |

---

## Task 1: Stop the playback thread being stranded on synthesis failure

**Why this is first:** it is a correctness bug independent of the gap diagnosis, and it is live today on the OOM path (spec §6A.7). If synthesis throws, `doneFlag.set(true)` is skipped, the playback thread's `readAt` keeps returning null forever, the loop never breaks, the `AudioTrack` leaks, and `onDone` tears down the stop button while banked audio keeps playing — with audio focus already abandoned.

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt:288-294`
- Test: `app/src/androidTest/java/com/whispereverywhere/tts/TtsPlaybackThreadTest.kt` (create)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: nothing later tasks depend on. Purely a behaviour fix inside `speak()`.

- [ ] **Step 1: Read the current code to confirm the defect**

Open `app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt` and confirm lines 288-294 read:

```kotlin
                engine.generateWithCallback(
                    text = clean, sid = speakerId, speed = speed, callback = callback,
                )
                doneFlag.set(true)
                // Playback outlives synthesis by up to the whole retained read; a newer speak()
                // or stop() bumps the generation and this join returns within a slice.
                playbackThread.join(RETAIN_CAP_JOIN_MS)
```

The defect: if `generateWithCallback` throws (OOM on the whole-utterance `float[]`, native error), control jumps to the outer `catch` at line 295 and **`doneFlag` is never set**.

- [ ] **Step 2: Write the failing instrumented test**

Create `app/src/androidTest/java/com/whispereverywhere/tts/TtsPlaybackThreadTest.kt`:

```kotlin
package com.whispereverywhere.tts

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression guard for the stranded-playback-thread bug: no thread named "tts-playback" may
 * outlive an utterance, on ANY exit path. A leaked thread also leaks its AudioTrack.
 */
class TtsPlaybackThreadTest {

    private fun livePlaybackThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.name == "tts-playback" && it.isAlive }

    @Test fun playback_thread_does_not_outlive_a_normal_utterance() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val mgr = TtsModelManager(ctx)
        assumeTrue("voice model not installed on this device", mgr.installedDir() != null)

        val engine = TtsEngine(ctx, mgr)
        val done = CountDownLatch(1)
        assertTrue(engine.speak("Short test sentence.") { done.countDown() })
        assertTrue("speak did not finish in 60s", done.await(60, TimeUnit.SECONDS))

        // onDone fires on the main thread from the finally block, which has already joined.
        Thread.sleep(500)
        val leaked = livePlaybackThreads()
        assertTrue("leaked playback threads: ${leaked.map { it.name }}", leaked.isEmpty())
        engine.shutdown()
    }

    @Test fun playback_thread_terminates_after_stop_mid_utterance() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val mgr = TtsModelManager(ctx)
        assumeTrue("voice model not installed on this device", mgr.installedDir() != null)

        val engine = TtsEngine(ctx, mgr)
        val done = CountDownLatch(1)
        engine.speak(
            "One. Two. Three. Four. Five. Six. Seven. Eight. Nine. Ten.",
        ) { done.countDown() }
        Thread.sleep(1500)
        engine.stop()
        assertTrue("stop did not settle in 30s", done.await(30, TimeUnit.SECONDS))

        Thread.sleep(500)
        val leaked = livePlaybackThreads()
        assertTrue("leaked playback threads after stop: ${leaked.map { it.name }}", leaked.isEmpty())
        engine.shutdown()
    }
}
```

- [ ] **Step 3: Run the test on a connected device to establish the baseline**

Run:
```bash
./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.whispereverywhere.tts.TtsPlaybackThreadTest
```

Expected: both tests **PASS** on the happy path (the bug only manifests when synthesis throws). This test is a *regression guard*, not a reproduction — record that plainly. If the device has no voice model installed, both tests will be skipped via `assumeTrue`; install the voice first through the app's Settings screen.

- [ ] **Step 4: Apply the fix**

In `app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt`, replace lines 288-294 with:

```kotlin
                try {
                    engine.generateWithCallback(
                        text = clean, sid = speakerId, speed = speed, callback = callback,
                    )
                } finally {
                    // MUST be in a finally. If generateWithCallback throws (OOM on sherpa's
                    // whole-utterance float[], or a native error), skipping this leaves the
                    // playback loop's readAt() returning null forever: the thread never exits,
                    // the AudioTrack leaks, and onDone tears down the stop button while banked
                    // audio keeps playing with focus already abandoned.
                    doneFlag.set(true)
                }
                // Playback outlives synthesis by up to the whole retained read; a newer speak()
                // or stop() bumps the generation and this join returns within a slice.
                // On the throw path we still reach the outer catch AFTER banked audio drains,
                // which is deliberate: the user keeps the words already synthesized.
                playbackThread.join(RETAIN_CAP_JOIN_MS)
```

- [ ] **Step 5: Verify the thread is joined even when synthesis throws**

The outer `finally` at lines 297-310 already calls `playbackThread?.let { if (it.isAlive) runCatching { pt.join(2_000) } }`. With `doneFlag` now always set, that join completes instead of timing out after 2 s with a live thread. Confirm by reading lines 297-301 that the join is present and unconditional.

- [ ] **Step 6: Re-run the instrumented test**

Run:
```bash
./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.whispereverywhere.tts.TtsPlaybackThreadTest
```

Expected: PASS (both tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt \
        app/src/androidTest/java/com/whispereverywhere/tts/TtsPlaybackThreadTest.kt
git commit -m "fix(tts): doneFlag in a finally — synthesis failure no longer strands playback

If generateWithCallback throws, doneFlag was never set, so the playback
loop's readAt() returned null forever: the thread never exited, the
AudioTrack leaked, and onDone tore down the stop button while banked
audio kept playing with audio focus already abandoned. Live today on the
OOM path (sherpa returns a whole-utterance float[] the app discards —
~173 MB at RETAIN_CAP on a no-largeHeap app).

Banked audio still drains before the error propagates, so the user keeps
the words already synthesized."
```

---

## Task 2: `TtsDiagMath` — the pure arithmetic

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/tts/TtsDiagMath.kt`
- Test: `app/src/test/java/com/whispereverywhere/tts/TtsDiagMathTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces, used by Tasks 3 and 4:
  - `TtsDiagMath.audioMs(samples: Int, sampleRate: Int): Long`
  - `TtsDiagMath.rtf(synthMs: Long, audioMs: Long): Double`
  - `TtsDiagMath.audibleSilenceMs(wallStalledMs: Long, renderedDuringStallMs: Long): Long`
  - `TtsDiagMath.percentile(sorted: List<Double>, p: Double): Double`
  - `TtsDiagMath.dutyPct(audioMs: Long, wallMs: Long): Int`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/tts/TtsDiagMathTest.kt`:

```kotlin
package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsDiagMathTest {

    @Test fun audio_ms_converts_samples_at_the_track_rate() {
        // 24 000 samples at 24 kHz is exactly one second.
        assertEquals(1000L, TtsDiagMath.audioMs(24_000, 24_000))
        assertEquals(500L, TtsDiagMath.audioMs(12_000, 24_000))
        assertEquals(0L, TtsDiagMath.audioMs(0, 24_000))
    }

    @Test fun audio_ms_is_zero_for_a_nonsense_rate_instead_of_dividing_by_zero() {
        assertEquals(0L, TtsDiagMath.audioMs(24_000, 0))
    }

    @Test fun rtf_is_synthesis_time_over_audio_produced() {
        // The bench figure: 8106 ms of synthesis for 14 050 ms of audio.
        assertEquals(0.577, TtsDiagMath.rtf(8_106, 14_050), 0.001)
        assertEquals(1.0, TtsDiagMath.rtf(3_000, 3_000), 0.0001)
    }

    @Test fun rtf_is_zero_when_no_audio_was_produced() {
        // A callback that yields nothing must not produce Infinity and poison a percentile.
        assertEquals(0.0, TtsDiagMath.rtf(500, 0), 0.0001)
    }

    @Test fun audible_silence_is_wall_time_minus_what_the_hardware_still_rendered() {
        // THE load-bearing formula. Stalled 1846 ms while the track still had 160 ms queued:
        // the user heard 1686 ms of silence, not 1846.
        assertEquals(1_686L, TtsDiagMath.audibleSilenceMs(1_846, 160))
    }

    @Test fun audible_silence_never_goes_negative() {
        // The track can report more rendered than we stalled (coarse HAL head reporting).
        assertEquals(0L, TtsDiagMath.audibleSilenceMs(100, 400))
    }

    @Test fun percentile_picks_the_expected_ranks() {
        val xs = listOf(0.50, 0.55, 0.58, 0.60, 0.95)
        assertEquals(0.58, TtsDiagMath.percentile(xs, 0.50), 0.0001)
        assertEquals(0.95, TtsDiagMath.percentile(xs, 0.95), 0.0001)
        assertEquals(0.50, TtsDiagMath.percentile(xs, 0.0), 0.0001)
    }

    @Test fun percentile_of_empty_is_zero() {
        assertEquals(0.0, TtsDiagMath.percentile(emptyList(), 0.5), 0.0001)
    }

    @Test fun duty_pct_is_speech_over_wall_clock() {
        // 12.5 s of speech across 14.2965 s of wall clock = 87%.
        assertEquals(87, TtsDiagMath.dutyPct(12_500, 14_296))
        assertEquals(100, TtsDiagMath.dutyPct(5_000, 5_000))
        assertEquals(0, TtsDiagMath.dutyPct(5_000, 0))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.tts.TtsDiagMathTest"
```

Expected: FAIL — compilation error, `Unresolved reference: TtsDiagMath`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/whispereverywhere/tts/TtsDiagMath.kt`:

```kotlin
package com.whispereverywhere.tts

import kotlin.math.roundToInt

/**
 * Pure arithmetic behind the TTSDIAG records. No Android, no state — unit-tested on the JVM in
 * the SpeechSegmenter / AudioSourcePolicy idiom.
 *
 * Every helper is total: a zero denominator returns 0 rather than throwing or producing
 * Infinity, because a single poisoned sample would corrupt the percentile summary that the
 * whole diagnostic pass depends on.
 */
object TtsDiagMath {

    /** Milliseconds of audio in [samples] at [sampleRate] Hz (mono). */
    fun audioMs(samples: Int, sampleRate: Int): Long =
        if (sampleRate <= 0) 0L else samples.toLong() * 1000L / sampleRate.toLong()

    /**
     * Real-time factor: wall-clock synthesis time divided by audio produced.
     * < 1 means synthesis outruns playback. >= 1 means the pipeline cannot be smooth by
     * arithmetic, regardless of buffering (spec 6A.4).
     */
    fun rtf(synthMs: Long, audioMs: Long): Double =
        if (audioMs <= 0L) 0.0 else synthMs.toDouble() / audioMs.toDouble()

    /**
     * The only honest silence measurement. While the playback loop is stalled, the AudioTrack
     * keeps rendering whatever was already queued — so the user hears silence only for the
     * remainder. underrunCount alone says we fed the track late; it never says how much silence
     * reached the ear.
     */
    fun audibleSilenceMs(wallStalledMs: Long, renderedDuringStallMs: Long): Long =
        (wallStalledMs - renderedDuringStallMs).coerceAtLeast(0L)

    /** Nearest-rank percentile of an ASCENDING-sorted list. [p] in 0..1. */
    fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = (p.coerceIn(0.0, 1.0) * (sorted.size - 1)).roundToInt()
        return sorted[idx]
    }

    /** Percentage of wall clock that was actually speech. 100 means gapless. */
    fun dutyPct(audioMs: Long, wallMs: Long): Int =
        if (wallMs <= 0L) 0 else ((audioMs.toDouble() / wallMs.toDouble()) * 100.0)
            .roundToInt().coerceIn(0, 100)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.tts.TtsDiagMathTest"
```

Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/tts/TtsDiagMath.kt \
        app/src/test/java/com/whispereverywhere/tts/TtsDiagMathTest.kt
git commit -m "feat(tts): TtsDiagMath — pure arithmetic for playback diagnostics

audibleSilenceMs is the load-bearing one: underrunCount alone says we fed
the track late, never how much silence the user actually heard. Every
helper is total — a zero denominator returns 0 rather than Infinity,
because one poisoned sample would corrupt the percentile summary."
```

---

## Task 3: `TtsDiag` — the log-line formatters

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/tts/TtsDiag.kt`
- Test: `app/src/test/java/com/whispereverywhere/tts/TtsDiagTest.kt`

**Interfaces:**
- Consumes: `TtsDiagMath` (Task 2) — specifically `audioMs`, `rtf`, `audibleSilenceMs`, `percentile`, `dutyPct`.
- Produces, used by Task 4:
  - `TtsDiag.open(gen: Long, bufFrames: Int, perfMode: Int, chars: Int, sampleRate: Int): String`
  - `TtsDiag.sent(gen: Long, seq: Int, samples: Int, audMs: Long, synthMs: Long): String`
  - `TtsDiag.play(gen: Long, seq: Int, leadMs: Long): String`
  - `TtsDiag.under(gen: Long, seq: Int, atMs: Long, wallMs: Long, renderMs: Long, hwUnderD: Int): String`
  - `TtsDiag.end(gen: Long, ttfwMs: Long, underN: Int, underMs: Long, maxGapMs: Long, audioMs: Long, wallMs: Long, rtfs: List<Double>, hwUnderTotal: Int): String`
  - `TtsDiag.TAG` = `"WE-TTS"`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/tts/TtsDiagTest.kt`:

```kotlin
package com.whispereverywhere.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsDiagTest {

    @Test fun every_line_starts_with_the_grep_prefix_and_a_kind() {
        assertTrue(TtsDiag.open(1, 7744, 1, 250, 24_000).startsWith("TTSDIAG open "))
        assertTrue(TtsDiag.sent(1, 0, 24_000, 1000, 577).startsWith("TTSDIAG sent "))
        assertTrue(TtsDiag.play(1, 0, 1200).startsWith("TTSDIAG play "))
        assertTrue(TtsDiag.under(1, 3, 10_240, 1846, 160, 1).startsWith("TTSDIAG under "))
        assertTrue(
            TtsDiag.end(1, 1882, 2, 2400, 1800, 12_500, 14_296, listOf(0.55, 0.58), 3)
                .startsWith("TTSDIAG end "),
        )
    }

    @Test fun no_line_contains_a_comma_so_the_format_stays_splittable() {
        // Space-separated key=value only. A comma would break naive parsing of the paste-back.
        val lines = listOf(
            TtsDiag.open(1, 7744, 1, 250, 24_000),
            TtsDiag.sent(1, 0, 24_000, 1000, 577),
            TtsDiag.play(1, 0, 1200),
            TtsDiag.under(1, 3, 10_240, 1846, 160, 1),
            TtsDiag.end(1, 1882, 2, 2400, 1800, 12_500, 14_296, listOf(0.55, 0.58), 3),
        )
        lines.forEach { assertTrue("comma in: $it", !it.contains(",")) }
    }

    @Test fun sent_line_reports_the_derived_per_sentence_rtf() {
        // 577 ms of synthesis for 1000 ms of audio => rtf 0.58 at two decimals.
        val line = TtsDiag.sent(gen = 4, seq = 2, samples = 24_000, audMs = 1000, synthMs = 577)
        assertTrue(line, line.contains("gen=4"))
        assertTrue(line, line.contains("seq=2"))
        assertTrue(line, line.contains("audMs=1000"))
        assertTrue(line, line.contains("synthMs=577"))
        assertTrue(line, line.contains("rtf=0.58"))
    }

    @Test fun under_line_reports_audible_silence_not_just_wall_time() {
        // Stalled 1846 ms with 160 ms still queued in the track => 1686 ms actually heard.
        val line = TtsDiag.under(gen = 7, seq = 3, atMs = 10_240, wallMs = 1846, renderMs = 160, hwUnderD = 1)
        assertTrue(line, line.contains("wallMs=1846"))
        assertTrue(line, line.contains("renderMs=160"))
        assertTrue(line, line.contains("audibleMs=1686"))
        assertTrue(line, line.contains("hwUnderD=1"))
    }

    @Test fun end_line_summarises_percentiles_and_duty() {
        val rtfs = listOf(0.50, 0.55, 0.58, 0.60, 0.95)
        val line = TtsDiag.end(
            gen = 1, ttfwMs = 1882, underN = 2, underMs = 2400, maxGapMs = 1800,
            audioMs = 12_500, wallMs = 14_296, rtfs = rtfs, hwUnderTotal = 3,
        )
        assertTrue(line, line.contains("ttfwMs=1882"))
        assertTrue(line, line.contains("underN=2"))
        assertTrue(line, line.contains("underMs=2400"))
        assertTrue(line, line.contains("maxGapMs=1800"))
        assertTrue(line, line.contains("dutyPct=87"))
        assertTrue(line, line.contains("rtfP50=0.58"))
        assertTrue(line, line.contains("rtfP95=0.95"))
        assertTrue(line, line.contains("rtfMax=0.95"))
        assertTrue(line, line.contains("hwUnder=3"))
    }

    @Test fun end_line_survives_an_utterance_with_no_sentences() {
        // Cancelled before the first callback: must not throw, must not print NaN.
        val line = TtsDiag.end(1, 0, 0, 0, 0, 0, 0, emptyList(), 0)
        assertTrue(line, !line.contains("NaN"))
        assertTrue(line, !line.contains("Infinity"))
        assertTrue(line, line.contains("rtfP50=0.00"))
    }

    @Test fun rtfs_are_sorted_internally_so_callers_need_not_be_careful() {
        val unsorted = listOf(0.95, 0.50, 0.58)
        val line = TtsDiag.end(1, 0, 0, 0, 0, 1000, 1000, unsorted, 0)
        assertTrue(line, line.contains("rtfP50=0.58"))
        assertTrue(line, line.contains("rtfMax=0.95"))
    }

    @Test fun tag_is_the_existing_engine_tag() {
        assertEquals("WE-TTS", TtsDiag.TAG)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.tts.TtsDiagTest"
```

Expected: FAIL — compilation error, `Unresolved reference: TtsDiag`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/whispereverywhere/tts/TtsDiag.kt`:

```kotlin
package com.whispereverywhere.tts

/**
 * TTSDIAG log-line formatting. Pure strings — no Android, no I/O — so the format is pinned by
 * unit tests and the owner's paste-back stays machine-readable.
 *
 * Format contract: `TTSDIAG <kind> key=value key=value ...`, space-separated, NO COMMAS, so a
 * whole session is recoverable with `adb logcat -s WE-TTS | grep TTSDIAG` and splittable on
 * whitespace. Kinds: open, sent, play, under, end.
 */
object TtsDiag {

    const val TAG = "WE-TTS"

    private fun d2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    /** Track created. Records what the framework actually GRANTED, not what we asked for. */
    fun open(gen: Long, bufFrames: Int, perfMode: Int, chars: Int, sampleRate: Int): String =
        "TTSDIAG open gen=$gen bufFrames=$bufFrames bufMs=${TtsDiagMath.audioMs(bufFrames, sampleRate)} " +
            "perfMode=$perfMode rate=$sampleRate chars=$chars"

    /** One sherpa callback landed: a whole sentence of audio. */
    fun sent(gen: Long, seq: Int, samples: Int, audMs: Long, synthMs: Long): String =
        "TTSDIAG sent gen=$gen seq=$seq samples=$samples audMs=$audMs synthMs=$synthMs " +
            "rtf=${d2(TtsDiagMath.rtf(synthMs, audMs))}"

    /** Playback cursor crossed into sentence [seq]; [leadMs] is the bank ahead of the cursor. */
    fun play(gen: Long, seq: Int, leadMs: Long): String =
        "TTSDIAG play gen=$gen seq=$seq leadMs=$leadMs"

    /**
     * A stall ended. [wallMs] is how long the loop waited; [renderMs] is how much audio the
     * track still rendered during that wait; the difference is what the user actually heard as
     * silence. [hwUnderD] is the getUnderrunCount() delta across the stall.
     */
    fun under(gen: Long, seq: Int, atMs: Long, wallMs: Long, renderMs: Long, hwUnderD: Int): String =
        "TTSDIAG under gen=$gen seq=$seq atMs=$atMs wallMs=$wallMs renderMs=$renderMs " +
            "audibleMs=${TtsDiagMath.audibleSilenceMs(wallMs, renderMs)} hwUnderD=$hwUnderD"

    /** Utterance summary. [rtfs] may arrive in any order; sorted here. */
    fun end(
        gen: Long,
        ttfwMs: Long,
        underN: Int,
        underMs: Long,
        maxGapMs: Long,
        audioMs: Long,
        wallMs: Long,
        rtfs: List<Double>,
        hwUnderTotal: Int,
    ): String {
        val sorted = rtfs.sorted()
        return "TTSDIAG end gen=$gen ttfwMs=$ttfwMs underN=$underN underMs=$underMs " +
            "maxGapMs=$maxGapMs audioMs=$audioMs wallMs=$wallMs " +
            "dutyPct=${TtsDiagMath.dutyPct(audioMs, wallMs)} " +
            "rtfP50=${d2(TtsDiagMath.percentile(sorted, 0.50))} " +
            "rtfP95=${d2(TtsDiagMath.percentile(sorted, 0.95))} " +
            "rtfMax=${d2(TtsDiagMath.percentile(sorted, 1.0))} " +
            "hwUnder=$hwUnderTotal"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.tts.TtsDiagTest"
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/tts/TtsDiag.kt \
        app/src/test/java/com/whispereverywhere/tts/TtsDiagTest.kt
git commit -m "feat(tts): TtsDiag — pinned TTSDIAG log-line format

Space-separated key=value, no commas, every line prefixed TTSDIAG so one
grep recovers a whole session. Format is pinned by unit tests because the
owner pastes these back and they must stay machine-readable."
```

---

## Task 4: Wire the diagnostics into `TtsEngine`

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt`
  - the sherpa callback (lines 263-287) — per-sentence timing
  - the playback thread (lines 176-257) — track open, boundary lead, stall accounting
  - the `finally` block (lines 297-310) — the `end` summary

**Interfaces:**
- Consumes: `TtsDiag` (Task 3), `TtsDiagMath` (Task 2), and the `doneFlag`-in-`finally` fix (Task 1).
- Produces: nothing later tasks depend on — this is the terminal task of Release 0.

- [ ] **Step 1: Add the diagnostic state next to the existing session state**

In `speak()`, immediately after the existing `val doneFlag = ...` line (currently line 158), add:

```kotlin
                // --- TTSDIAG session state (Release 0 instrumentation; no behaviour change) ---
                val diagRtfs = java.util.Collections.synchronizedList(ArrayList<Double>())
                val diagSentSeq = java.util.concurrent.atomic.AtomicInteger(0)
                // Callback EXIT -> next ENTRY. A one-element array, not @Volatile: Kotlin does
                // not permit @Volatile on a local, and the sherpa callback is this value's only
                // reader and only writer (it runs on the executor thread, one call at a time).
                val diagLastCallbackExitMs = longArrayOf(0L)
                val diagT0 = System.currentTimeMillis()
                val diagTtfwMs = java.util.concurrent.atomic.AtomicLong(-1)
                val diagUnderN = java.util.concurrent.atomic.AtomicInteger(0)
                val diagUnderMs = java.util.concurrent.atomic.AtomicLong(0)
                val diagMaxGapMs = java.util.concurrent.atomic.AtomicLong(0)
                val diagHwUnder = java.util.concurrent.atomic.AtomicInteger(0)
```

- [ ] **Step 2: Instrument the sherpa callback for per-sentence RTF**

Replace the body of `override fun invoke(samples: FloatArray): Int` (lines 264-286) so the timing brackets the *model*, not the backpressure hold. Insert at the very top of `invoke`:

```kotlin
                        val entryMs = System.currentTimeMillis()
```

and immediately before the closing `return 1`, after the `synchronized(store) { ... }` block, add:

```kotlin
                        // Measure callback EXIT -> ENTRY so the AHEAD_CAP backpressure hold below
                        // never reads as slow synthesis (spec 6A.3). The FIRST burst of an
                        // utterance is still logged but is excluded from the summary percentiles
                        // by the seq==0 check, because it carries whole-text espeak phonemisation
                        // whose cost scales with SELECTION length, not sentence length.
                        val prevExit = diagLastCallbackExitMs[0]
                        val synthMs = if (prevExit == 0L) entryMs - diagT0 else entryMs - prevExit
                        val seq = diagSentSeq.getAndIncrement()
                        val audMs = TtsDiagMath.audioMs(pcm.size, engine.sampleRate())
                        android.util.Log.i(TtsDiag.TAG, TtsDiag.sent(myGen, seq, pcm.size, audMs, synthMs))
                        if (seq > 0) diagRtfs.add(TtsDiagMath.rtf(synthMs, audMs))
                        diagLastCallbackExitMs[0] = System.currentTimeMillis()
```

Note the ordering: `synthMs` is computed from the *entry* timestamp captured before any work, and `diagLastCallbackExitMs` is stamped at the very end, after the store append. The backpressure `while` loop sits between them and is therefore excluded.

- [ ] **Step 3: Log the granted track configuration**

In the playback thread, immediately after `val localTrack = newTrack(engine.sampleRate())` (line 177), add:

```kotlin
                    android.util.Log.i(
                        TtsDiag.TAG,
                        TtsDiag.open(
                            gen = myGen,
                            bufFrames = localTrack.bufferSizeInFrames,
                            perfMode = localTrack.performanceMode,
                            chars = clean.length,
                            sampleRate = localTrack.sampleRate,
                        ),
                    )
```

Do **not** add any other tracking variable here. `underrunCount` is sampled per-stall in Step 5,
not against a session baseline — a session-wide baseline would be dead code.

`bufferSizeInFrames` and `performanceMode` are what the framework actually granted, which may differ from what `newTrack` requested — that difference is itself a finding.

- [ ] **Step 4: Record time-to-first-word and per-boundary lead**

Replace the existing `if (!started) { localTrack.play(); started = true }` block (lines 217-220) with:

```kotlin
                            if (!started) {
                                localTrack.play()
                                started = true
                                diagTtfwMs.set(System.currentTimeMillis() - diagT0)
                            }
```

Then, immediately after `cursor += off` and `playedSamples = cursor` (lines 240-241), add the boundary-lead record:

```kotlin
                            // Bank ahead of the cursor, sampled on the same ~100 ms cadence as
                            // the progress callback so the log volume stays bounded.
                            if (now - lastProgressMs >= 100) {
                                val leadMs = TtsDiagMath.audioMs(
                                    (availableSamples - cursor).toInt().coerceAtLeast(0),
                                    localTrack.sampleRate,
                                )
                                android.util.Log.i(
                                    TtsDiag.TAG,
                                    TtsDiag.play(myGen, diagSentSeq.get(), leadMs),
                                )
                            }
```

Place this **inside** the existing `if (now - lastProgressMs >= 100)` block that already exists at lines 243-246, reusing its guard rather than adding a second clock. The final shape of that block is:

```kotlin
                            val now = System.currentTimeMillis()
                            if (now - lastProgressMs >= 100) {
                                lastProgressMs = now
                                onProgress?.invoke(cursor, availableSamples, doneFlag.get())
                                val leadMs = TtsDiagMath.audioMs(
                                    (availableSamples - cursor).toInt().coerceAtLeast(0),
                                    localTrack.sampleRate,
                                )
                                android.util.Log.i(
                                    TtsDiag.TAG,
                                    TtsDiag.play(myGen, diagSentSeq.get(), leadMs),
                                )
                            }
```

- [ ] **Step 5: Instrument the stall with paired underrun and head-position sampling**

Replace the stall branch (lines 204-212) with:

```kotlin
                            if (pcm == null) {
                                if (doneFlag.get()) break@loop
                                if (started && !stalled) {
                                    stalled = true
                                    stallStartMs = System.currentTimeMillis()
                                    stallHeadStart = localTrack.playbackHeadPosition
                                    stallUnderStart = localTrack.underrunCount
                                    onBuffering?.invoke(true)
                                }
                                try { Thread.sleep(50) } catch (_: InterruptedException) {}
                                continue@loop
                            }
```

and replace the stall-exit branch (lines 213-216) with:

```kotlin
                            if (stalled) {
                                stalled = false
                                val wallMs = System.currentTimeMillis() - stallStartMs
                                // playbackHeadPosition is an UNSIGNED 32-bit frame count in an
                                // Int; mask before subtracting or a wrap reads as a huge
                                // negative and audibleMs silently clamps to 0.
                                val framesRendered =
                                    ((localTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL) -
                                        (stallHeadStart.toLong() and 0xFFFFFFFFL)).coerceAtLeast(0L)
                                val renderMs = TtsDiagMath.audioMs(
                                    framesRendered.toInt(), localTrack.sampleRate,
                                )
                                val hwD = localTrack.underrunCount - stallUnderStart
                                val audible = TtsDiagMath.audibleSilenceMs(wallMs, renderMs)
                                diagUnderN.incrementAndGet()
                                diagUnderMs.addAndGet(audible)
                                diagHwUnder.addAndGet(hwD)
                                if (audible > diagMaxGapMs.get()) diagMaxGapMs.set(audible)
                                android.util.Log.i(
                                    TtsDiag.TAG,
                                    TtsDiag.under(
                                        gen = myGen,
                                        seq = diagSentSeq.get(),
                                        atMs = System.currentTimeMillis() - diagT0,
                                        wallMs = wallMs,
                                        renderMs = renderMs,
                                        hwUnderD = hwD,
                                    ),
                                )
                                onBuffering?.invoke(false)
                            }
```

Declare the three new stall locals alongside `var stalled = false` (line 181):

```kotlin
                    var stallStartMs = 0L
                    var stallHeadStart = 0
                    var stallUnderStart = 0
```

- [ ] **Step 6: Re-baseline the head after every flush**

`flush()` resets `playbackHeadPosition` to 0, so a seek that lands during a stall would make the
Step 5 subtraction read a post-flush head against a pre-flush baseline and report a wildly wrong
`renderMs`. In the seek branch, inside the existing `if (started) { ... }` guard and immediately
after `runCatching { localTrack.pause(); localTrack.flush(); localTrack.play() }`, add this single
statement:

```kotlin
                                    stallHeadStart = 0
```

The resulting seek branch reads:

```kotlin
                            val seek = seekRequest.getAndSet(-1)
                            if (seek >= 0) {
                                cursor = seek.coerceIn(0L, availableSamples)
                                if (started) {
                                    runCatching { localTrack.pause(); localTrack.flush(); localTrack.play() }
                                    stallHeadStart = 0   // flush() zeroed the head; re-baseline
                                }
                            }
```

- [ ] **Step 7: Emit the end-of-utterance summary**

In the outer `finally` block, immediately before `main.post(onDone)` (line 309), add:

```kotlin
                android.util.Log.i(
                    TtsDiag.TAG,
                    TtsDiag.end(
                        gen = myGen,
                        ttfwMs = diagTtfwMs.get().coerceAtLeast(0),
                        underN = diagUnderN.get(),
                        underMs = diagUnderMs.get(),
                        maxGapMs = diagMaxGapMs.get(),
                        audioMs = TtsDiagMath.audioMs(availableSamples.toInt(), 24_000),
                        wallMs = System.currentTimeMillis() - diagT0,
                        rtfs = ArrayList(diagRtfs),
                        hwUnderTotal = diagHwUnder.get(),
                    ),
                )
```

`ArrayList(diagRtfs)` copies under the synchronized list's own lock, avoiding a `ConcurrentModificationException` if a late callback is still appending.

- [ ] **Step 8: Build and verify no behaviour changed**

Run:
```bash
./gradlew :app:assembleDebug --no-daemon
```

Expected: BUILD SUCCESSFUL.

Then run the full unit suite to confirm nothing regressed:
```bash
./gradlew :app:testDebugUnitTest --no-daemon
```

Expected: PASS.

Then confirm no buffering constant moved:
```bash
git diff --stat
git diff app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt | grep -E "^\+" | grep -iE "minBuf|PERFORMANCE_MODE|AHEAD_CAP|RETAIN_CAP|setBufferSize|THREAD_PRIORITY|sleep\(" || echo "OK: no buffering or scheduling constant touched"
```

Expected: `OK: no buffering or scheduling constant touched`. If this prints anything else, a behaviour change slipped in — revert it. This release must measure the *current* pipeline.

- [ ] **Step 9: Run the instrumented regression test**

Run:
```bash
./gradlew :app:connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.class=com.whispereverywhere.tts.TtsPlaybackThreadTest
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/tts/TtsEngine.kt
git commit -m "feat(tts): TTSDIAG instrumentation — measure the gap before fixing it

Emits open/sent/play/under/end records so one read-aloud on the owner's
device settles whether the playback gaps are producer starvation.

Deliberately NO behaviour change: no watermark, no prebuffer, no thread
priority, no track resize. Every buffering constant in the spec is
arithmetic on one cold Fold 6 bench; this captures the baseline they must
be tuned against.

Per-sentence RTF is measured callback-EXIT to callback-ENTRY so the
AHEAD_CAP backpressure hold is excluded, and sentence 0 is dropped from
the percentiles because it carries whole-text espeak phonemisation whose
cost scales with selection length, not sentence length.

playbackHeadPosition is masked to unsigned before subtraction and
re-baselined after every flush()."
```

---

## Task 5: Capture the baseline on the owner's device

This task produces no code. It produces the measurement that Release 0.1 depends on.

**Files:** none.

**Interfaces:**
- Consumes: the shipped instrumentation from Tasks 1-4.
- Produces: a logcat capture that decides whether the §6A diagnosis stands.

> ### ⚠️ MANDATORY SIGNATURE PREFLIGHT — read before ANY install or instrumented test
>
> **This destroyed a real user's data on 2026-07-27. Do not skip it.**
>
> `app/build.gradle.kts:120-128` signs the debug build with the release key so debug APKs
> "install straight over the release build without uninstalling (which would wipe the downloaded
> model)." **That comment is only true when the installed build came from a locally-built APK.**
> If the app was installed from Google Play, **Play App Signing has re-signed it with Google's
> key**, not your upload key — so the on-device signature can never match a locally-signed debug
> build, and the presence of `keystore.properties` proves nothing.
>
> What happens if you skip this: `./gradlew :app:connectedDebugAndroidTest` fails with
> `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, **but Gradle's `AndroidTestApkInstallerPlugin`
> uninstalls the existing package first** — so you end up with neither build installed and
> **all app data destroyed**: the 60-574 MB whisper model, the ~330 MB Kokoro voice, the
> transcript history (unrecoverable), and any stored credentials.
>
> **Run this first, every time:**
>
> ```bash
> adb shell pm path com.whispereverywhere            # is it installed at all?
> adb shell pm list packages -i | grep whisper       # installer: com.android.vending == from Play
> adb shell dumpsys package com.whispereverywhere | grep -A2 signatures
> ```
>
> **STOP and ask the user** if the app is installed AND its signer does not match the local
> keystore. Never let a build tool resolve a signature conflict for you — surface the uninstall
> consequence and let the human decide. If they accept, have them export anything they want to
> keep first.

> ### ⚠️ ORDERING: `connectedAndroidTest` UNINSTALLS THE APP WHEN IT FINISHES
>
> AGP's instrumented-test task installs the app APK + test APK, runs the tests, and then
> **removes both as teardown**. So installing the app for a capture and *then* running the
> instrumented tests leaves you with no app — which looks exactly like a failed install.
>
> **Correct order:**
> 1. Run any instrumented tests FIRST (they self-install and self-clean).
> 2. THEN install the build for the capture.
> 3. Do NOT run instrumented tests again until the capture is saved.

- [ ] **Step 1: Install the instrumented build**

Only after the preflight above passes, or after an explicit informed decision to accept the
uninstall — and only after any instrumented-test runs are finished:

```bash
./gradlew :app:installDebug --no-daemon
```

If the package is absent (fresh device, or a prior uninstall), install the built APK directly —
this avoids Gradle's installer plugin entirely:

```bash
adb install <buildDir>/app/outputs/apk/debug/app-debug.apk
```

Verify it is actually there before handing the device back:

```bash
adb shell pm path com.whispereverywhere
adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.whispereverywhere
```

- [ ] **Step 2: Start a clean capture**

```bash
adb logcat -c
adb logcat -s WE-TTS > tts-baseline.log
```

- [ ] **Step 3: Perform the read-aloud that reproduces the gaps**

Use content that reproduces the reported symptom — ideally an article with **headings or short sentences followed by long ones**, which is the boundary shape the diagnosis predicts will starve. Read for at least 60 seconds. Note subjectively where breaks were heard.

- [ ] **Step 4: Stop the capture and read the verdict**

Stop with Ctrl-C, then:

```bash
grep "TTSDIAG end" tts-baseline.log
grep -c "TTSDIAG under" tts-baseline.log
```

Apply the triangulation table from spec §6A.6:

| `hwUnder` | `underMs` (audible) | Verdict |
|---|---|---|
| > 0 | > 0 | **Starvation confirmed.** Proceed to Release 0.1; tune watermarks against the observed `rtfP95` and `maxGapMs`. |
| > 0 | ~0 | **Thread descheduling**, not starvation. The buffering fix is aimed wrong — investigate scheduling and `THREAD_PRIORITY_URGENT_AUDIO` instead. |
| ~0 | ~0 | **Diagnosis refuted.** Do NOT build the watermark policy. Next suspects: sherpa's `silence_scale=0.2` inter-sentence padding, and the end-of-utterance truncation race. |

- [ ] **Step 5: Record the numbers that set the constants**

From the `end` line, note `rtfP50`, `rtfP95`, `rtfMax`, `dutyPct`, `ttfwMs`, `maxGapMs`. From the `sent` lines, note the longest `audMs` observed — that is the real `D_max` that `MAX_WM_MS` must accommodate.

**Known blind spot:** `audibleMs` cannot resolve gaps shorter than one AudioTrack buffer (~160-400 ms). If `underN` is 0 and `dutyPct` is ~100 but breaks are still audible, the complaint is sub-buffer chopping and this instrumentation will report a clean pass. Record that outcome explicitly rather than concluding "fixed".

- [ ] **Step 6: Commit the captured baseline**

```bash
mkdir -p docs/measurements
cp tts-baseline.log docs/measurements/2026-07-27-tts-baseline.log
git add docs/measurements/2026-07-27-tts-baseline.log
git commit -m "measure(tts): baseline TTSDIAG capture before any buffering change

Device and content noted in the commit body. This is the reference the
Release 0.1 watermark constants are tuned against, replacing arithmetic
on a single cold Fold 6 bench run."
```

---

## Self-Review

**Spec coverage (§6A.6 commit 1 + §6A.7):**

| Spec requirement | Task |
|---|---|
| `TTSDIAG` `open` record (granted `bufferSizeInFrames`, performance mode) | Task 4 Step 3 |
| `sent` record (seq, samples, audMs, synthMs, per-sentence RTF) | Task 4 Step 2 |
| `play` record (lead ms at boundary crossing) | Task 4 Step 4 |
| `under` record | Task 4 Step 5 |
| `end` record (ttfwMs, underN, underMs, maxGapMs, dutyPct, rtf p50/p95/max) | Task 4 Step 7 |
| `getUnderrunCount()` at creation / stall entry / stall exit / end | Task 4 Steps 3, 5, 7 |
| Paired `playbackHeadPosition`, re-baselined after `flush()` | Task 4 Steps 5, 6 |
| `audibleMs = wallStalled − renderedDuringStall` | Task 2 (`TtsDiagMath.audibleSilenceMs`), Task 4 Step 5 |
| RTF excludes phonemization contamination (skip first burst) | Task 4 Step 2 (`if (seq > 0)`) |
| RTF measured exit-to-entry, excluding backpressure hold | Task 4 Step 2 |
| `doneFlag` in a `finally` | Task 1 |
| No behaviour change in this release | Global Constraints + Task 4 Step 8 guard |
| `PcmStore` single-appender assertion | **Deliberately out of scope** — `PcmStore` does not exist; spec §6A.7 corrected 2026-07-27 to defer this to the store extraction. |

**Placeholder scan:** No TBD/TODO. Every code step contains complete code. Task 5 produces a measurement rather than code, and says so explicitly.

**Type consistency:** `TtsDiagMath.audioMs(samples: Int, sampleRate: Int): Long` is used identically in Task 3 (`TtsDiag.open`, `.sent`) and Task 4 (Steps 4, 5, 7). `TtsDiag.sent(gen, seq, samples, audMs, synthMs)` parameter order matches the call site in Task 4 Step 2. `TtsDiag.end(...)` takes `rtfs: List<Double>` unsorted and sorts internally, matching Task 4 Step 7 passing `ArrayList(diagRtfs)`. `TtsDiag.TAG` is used at every call site rather than a bare `"WE-TTS"` literal.

**One correctness note carried into the code:** `playbackHeadPosition` returns an unsigned 32-bit frame count boxed in a signed `Int`. Task 4 Step 5 masks with `and 0xFFFFFFFFL` before subtracting; without it a wrap reads as a large negative, `audibleSilenceMs` clamps to 0, and every stall would be silently reported as inaudible — producing exactly the "diagnosis refuted" verdict by measurement error.
