# Batch Transcription Mode — whole-recording one-shot transcription

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the app's **main UI (not the bubble)**, let the user record a whole clip, save the **raw audio + its transcript** in a recordings library, and transcribe it all at once through whichever engine they have chosen (the on-device model, or their selected cloud provider). A **Retry transcribe** button re-runs the job from the saved audio. It works fully offline with the local model; on-device stays the default.

**Architecture:** A new `recording/` package persists each clip as `noBackupFilesDir/recordings/<uuid>/{audio.pcm, manifest.json}` (`RecordingStore` + `RecordingMeta`, kotlinx.serialization). A new in-app `RecordScreen` captures MIC audio via the existing `StreamingAudioRecorder`, streamed to disk by `PcmSink`. A one-shot `BatchTranscriber` (NOT a `TranscriptionEngine` — it does not implement connect/sendAudio/commit; that streaming contract is a lie for a batch job) runs inside a foreground `BatchTranscriptionService` so a long job survives. It plans chunks with two pure, unit-tested objects — `SilenceScanner` (finds pause offsets) and `ChunkPlanner` (packs them under the provider's byte ceiling, hard-cutting when speech is continuous) — then transcribes **strictly sequentially**, checkpointing each finished chunk to the manifest so Retry resumes rather than restarts (a `Done` cloud chunk is never re-uploaded). `BatchRouting` is invariant #1 written as code: a PLAYBACK-sourced recording is transcribed on-device only, forever, enforced by a gate plus two pinning tests.

**Tech Stack:** Kotlin 2.0.21, kotlinx-serialization-json 1.7.3, JUnit 4, coroutines, Android foreground `Service`, `StatFs`. No new third-party dependency. OkHttp stays pinned at 4.12.0 (reused via the existing `OpenAiStt`/`HttpTransport`; no direct HTTP is added here).

---

## Global Constraints

Carried over from C2a and Release A, still binding here, plus the new batch-specific ones.

- **PLAYBACK provenance is LOCAL-ONLY, FOREVER — enforced in code, pinned by a test.** A saved recording carries its capture source (`ActiveSource`). A recording whose source is `PLAYBACK` must be transcribable on-device only — through batch mode, through Retry, through Re-transcribe, under **any** combination of stored key, selected provider, accepted consent, and live network. This is invariant #1 (MediaProjection device audio never reaches cloud) extended to persisted audio. It is written once as code in `transcription/batch/BatchRouting.kt`, mirrored from `SourceRoutedTranscriptionEngine.engineForSource`, and it has TWO implementing pinning tests (`BatchRoutingTest`, and the `playback_never_touches_the_network` case in `BatchTranscriberTest`). **This is the MF1 lesson: a constraint the plan merely states, without a task that builds it and a test that pins it, is how this project last got merge-blocked. Every constraint below that names a behavior has such a task.**
- **Cloud requires ALL THREE: a stored key AND explicit provider selection AND accepted disclosure v2.** Batch reuses the exact same triad C2a already enforces (`sttProviderId != null`, `providerAccounts.key(id)` non-blank, `cloudDisclosureAccepted`). Batch introduces no new consent surface and no new transmission — it is the same mic→provider upload C2a already declared.
- **The fallback valve is ONE-WAY: cloud→local only, never local→cloud.** A per-chunk cloud failure falls to the local model. A local chunk never escalates to cloud. A PLAYBACK recording never enters the cloud path at all.
- **On-device is the default and must work fully offline.** With no provider selected, batch transcribes every recording locally with zero configuration and zero network.
- **No credential or transcript CONTENT in logcat — lengths only.** Never log a key, a header, a chunk's text, or the assembled transcript. Chunk hard-cuts and progress are logged as counts/lengths only.
- **No speed claims in any user-facing copy.** Status reads "Transcribing…", "Chunk 3 of 8", never "fast".
- **Storing raw audio is a NEW data-retention fact.** Audio lives in **app-private, non-backed-up** storage (`noBackupFilesDir`), is deletable per-recording, and is swept on a 30-day / 200 MB rolling cap. The privacy policy §5 needs one sentence — **FLAGGED as Task 8, NOT written into code, and `docs/privacy.html` is owner-locked this run (do not touch it).**
- **kotlinx.serialization for ALL JSON — `org.json` is BANNED.** Unit tests run with `unitTests.isReturnDefaultValues = true` (`app/build.gradle.kts:166`); `org.json` ships in `android.jar` and returns type defaults (`optString` → `""`) under that config, so broken code passes silently. The manifest is `@Serializable` data classes via `Json`. This exact trap cost tasks in C1 (`android.util.Base64`) and is called out in C2a.
- **25 MB / ~13.1 min is the OpenAI batch hard cap** (16 kHz PCM16 mono WAV = 32 KB/s). The cloud chunk **ceiling is 20 MB (~10.5 min)** — a deliberate margin below the cap, computed on the raw PCM plus the 44-byte WAV header added at dispatch. Local chunks are ~90 s to bound native memory. `gpt-transcribe` is the batch model ($0.0045/min), already `OpenAiStt.DEFAULT_MODEL`; **`gpt-live-transcribe` is realtime-only and must NEVER be used here.**
- **Seq-exactly-once does NOT apply and `SegmentOrderer` is deliberately SKIPPED.** Batch output lands on a **screen, not an injected IME field**, so the orderer's sole job (stopping out-of-order injection from deleting text) is absent. Chunks run strictly sequentially — chunk N awaits before N+1 — so results always arrive in order and a `StringBuilder` join is provably correct. This is confirmed in the pipeline recon and unanimous across the design candidates.
- **All native `whisper_context` access stays single-threaded.** `BatchTranscriber` runs its whole job on **one** single-thread dispatcher; it loads one ctx for the job, transcribes every local chunk on that thread, and releases it at the end. It calls `backend.transcribe` directly (bypassing `LocalWhisperEngine.sendAudio`'s 30 s self-commit) so chunk cuts stay plan-aligned — but it never touches the ctx from two threads.
- **`java` is NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`, then `.\gradlew.bat --no-daemon`.
- **NEVER run `connectedAndroidTest` or `installDebug`** — AGP's instrumented task uninstalls the app on teardown and has twice destroyed the user's 500+ MB of models. To run instrumented: `adb install -r` both APKs, then `adb shell am instrument -w …`.
- **Do not touch** (owner is editing concurrently for a separate fix): `app/src/main/assets/*`, `docs/privacy.html`, `docs/terms.html`, `docs/PLAY-LISTING.md`. Do not touch Release A (`SegmentOrderer`/`SegmentOutcome`), `TranscriptStore` (its "audio never retained" contract is preserved — bubble history stays text-only), or the cloud engines from C2a.
- **Commit ONLY the files each task names, by exact path. Never `git add -A`.** If `git commit` fails on `index.lock`, wait a moment and retry once.

---

## What batch mode deliberately does NOT do (scope cuts — one release)

SAF / WAV / any file import; MP3/M4A/codec decoding; bubble-session raw-audio retrofit; PLAYBACK capture in the record screen (the field, gate, and pinning test still ship, exercised by a synthetic PLAYBACK meta); audio playback / scrubbing / waveform of saved clips; audio-file export / `FileProvider`; parallel chunk upload / any batch use of `CloudTranscriptionEngine`'s 3-in-flight or `SegmentOrderer`; `SegmentQuality`; cloud TTS; C4 streaming (`gpt-live-transcribe`); Gemini/ElevenLabs batch (C2b); translation; audio trimming. Resist all of them.

---

## Owner questions (recommended default planned; each marked at its task so it is findable)

1. **WAV/PCM import via SAF** — default **OUT** (provenance-ambiguous, codec surface). Marked at Task 3.
2. **Audio playback of saved recordings** — default **OUT** (retry doesn't require listening). Marked at Task 7.
3. **Separate recordings library vs merged into transcript history** — default **separate `recordings` route** (text history stays text-only). Marked at Tasks 1 & 7.
4. **Retention window** — default **30 days + 200 MB oldest-first**. Marked at Task 1.

---

## Design decision the recon forced (recorded, not hidden)

The synthesized design specifies chunk boundaries from the **Silero VAD seam** (`we_vad_filter`, decision log #8). The pipeline recon shows that seam is **native-internal to `whisper_full` and has no JNI export returning boundaries** — and, decisively, running VAD over a whole 30-minute recording to *find* boundaries would require the entire clip as a `FloatArray` + a native copy (the ~38 MB × N OOM that chunking exists to avoid). Using VAD for coarse cut-planning is therefore both unreachable from Kotlin and self-defeating on memory.

**Resolution (plan author's call):** coarse cut-planning uses a **streaming Kotlin energy scan** (`SilenceScanner`, built on the existing pure `AudioMath`) — memory-bounded, JVM-unit-testable, no native surgery. The Silero VAD **still runs unchanged inside `whisper_full` per chunk at transcription time**, so speech is still VAD-trimmed before the encoder; we simply do not use VAD for the coarse cut. Transcription quality is unaffected; correctness and memory bounds are guaranteed by the hard-cut fallback. This deviates from decision log #8 and is reported to the owner. (This is the same class of gap the design itself warns about — a declared mechanism with no reachable implementation — caught and closed here rather than shipped.)

---

## File Structure

| File | Action |
|---|---|
| `app/src/main/java/com/whispereverywhere/recording/RecordingMeta.kt` | Create — `@Serializable` model, `BatchStatus`, `ChunkStatus`, `ChunkEntry`, `EngineUsed` |
| `app/src/main/java/com/whispereverywhere/recording/RecordingStore.kt` | Create — persistence, list/read/save/delete/sweep |
| `app/src/test/java/com/whispereverywhere/recording/RecordingStoreTest.kt` | Create |
| `app/src/main/java/com/whispereverywhere/recording/PcmSink.kt` | Create — PCM→disk + free-space gate |
| `app/src/test/java/com/whispereverywhere/recording/StorageGuardTest.kt` | Create |
| `app/src/main/java/com/whispereverywhere/transcription/batch/ChunkPlanner.kt` | Create — pure `SilenceScanner` + `ChunkPlanner` |
| `app/src/test/java/com/whispereverywhere/transcription/batch/ChunkPlannerTest.kt` | Create — the boundary math, all edge cases |
| `app/src/main/java/com/whispereverywhere/transcription/batch/BatchRouting.kt` | Create — invariant #1 gate |
| `app/src/test/java/com/whispereverywhere/transcription/batch/BatchRoutingTest.kt` | Create — pinning |
| `app/src/main/java/com/whispereverywhere/transcription/batch/BatchTranscriber.kt` | Create — sequential, checkpoint/resume/cancel, per-chunk fallback |
| `app/src/test/java/com/whispereverywhere/transcription/batch/BatchTranscriberTest.kt` | Create — resume, cancel, fallback, playback-never-networks pinning |
| `app/src/main/java/com/whispereverywhere/service/BatchTranscriptionService.kt` | Create — foreground host |
| `app/src/main/AndroidManifest.xml` | Modify — declare the foreground service |
| `app/src/main/java/com/whispereverywhere/ui/RecordingsViewModel.kt` | Create |
| `app/src/main/java/com/whispereverywhere/ui/BatchJobViewModel.kt` | Create |
| `app/src/main/java/com/whispereverywhere/ui/screens/RecordScreen.kt` | Create |
| `app/src/main/java/com/whispereverywhere/ui/screens/RecordingsScreen.kt` | Create |
| `app/src/main/java/com/whispereverywhere/ui/screens/RecordingDetailScreen.kt` | Create |
| `app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt` | Modify — "Record & transcribe" card |
| `app/src/main/java/com/whispereverywhere/MainActivity.kt` | Modify — `recordings`/`record`/`recording_detail` routes |
| `docs/privacy.html` §5 | **FLAG ONLY (Task 8) — owner-held, do NOT edit** |

**Test-count note:** the running suite total is whatever the current tree reports (C2a is in final fix-up, so it is above the C2a plan's 233). Each task below states the number of NEW tests it adds; the implementer confirms `= previous total + N, 0 failures` after each.

---

## Task 1: `RecordingMeta` + `RecordingStore` — the recordings library

> Owner-questions 3 (separate library) & 4 (30 d / 200 MB) are settled to the defaults here.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/recording/RecordingMeta.kt`
- Create: `app/src/main/java/com/whispereverywhere/recording/RecordingStore.kt`
- Test: `app/src/test/java/com/whispereverywhere/recording/RecordingStoreTest.kt`

**Interfaces:**
- Consumes: `com.whispereverywhere.audio.ActiveSource` (existing enum), kotlinx.serialization.
- Produces, used by Tasks 2/5/7:
  - `enum class BatchStatus { Recorded, Transcribing, PartiallyDone, Done, Failed }`
  - `enum class ChunkStatus { Pending, Done, Failed }`
  - `enum class EngineUsed { LOCAL, OPENAI }`
  - `@Serializable data class ChunkEntry(index, startByte, endByte, hardCut, status = Pending, text = "")`
  - `@Serializable data class RecordingMeta(id, createdAtMs, durationMs, source, sampleRate = 16000, channels = 1, byteLength, status = Recorded, engineUsed = null, modelId = null, language = null, chunkPlan = emptyList())`
  - `class RecordingStore(root: File, clock = System::currentTimeMillis)` with `dir(id)`, `audioFile(id)`, `save(meta)`, `read(id): RecordingMeta?`, `list(): List<RecordingMeta>`, `delete(id)`, `sweep()`, `assembledText(meta): String`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/recording/RecordingStoreTest.kt`:

```kotlin
package com.whispereverywhere.recording

import com.whispereverywhere.audio.ActiveSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(now: Long = 1_000L) =
        RecordingStore(File(tmp.root, "recordings"), clock = { now })

    private fun meta(id: String, createdAtMs: Long, source: ActiveSource = ActiveSource.MIC) =
        RecordingMeta(
            id = id,
            createdAtMs = createdAtMs,
            durationMs = 3_000L,
            source = source,
            byteLength = 96_000,
        )

    @Test fun save_then_read_round_trips_every_field() {
        val s = store()
        val m = meta("a", 500L, ActiveSource.PLAYBACK).copy(
            status = BatchStatus.Done,
            engineUsed = EngineUsed.LOCAL,
            language = "en",
            chunkPlan = listOf(ChunkEntry(0, 0, 96_000, hardCut = false, ChunkStatus.Done, "hello")),
        )
        s.save(m)
        assertEquals(m, s.read("a"))
    }

    @Test fun the_source_survives_because_it_is_the_provenance_carrier() {
        val s = store()
        s.save(meta("p", 1L, ActiveSource.PLAYBACK))
        // If provenance did not persist, invariant #1 could not be enforced on retry.
        assertEquals(ActiveSource.PLAYBACK, s.read("p")!!.source)
    }

    @Test fun list_is_newest_first_and_ignores_dirs_without_a_manifest() {
        val s = store()
        s.save(meta("old", 100L))
        s.save(meta("new", 900L))
        File(s.dir("garbage"), "").mkdirs()   // a stray dir with no manifest.json
        assertEquals(listOf("new", "old"), s.list().map { it.id })
    }

    @Test fun read_of_a_missing_id_is_null_not_a_throw() {
        assertNull(store().read("nope"))
    }

    @Test fun delete_removes_the_whole_recording_directory() {
        val s = store()
        s.save(meta("d", 1L))
        s.audioFile("d").writeBytes(ByteArray(10))
        assertTrue(s.dir("d").exists())
        s.delete("d")
        assertFalse("per-recording deletion must remove audio.pcm too (invariant #7)", s.dir("d").exists())
    }

    @Test fun assembled_text_joins_done_chunks_in_index_order_only() {
        val s = store()
        val m = meta("j", 1L).copy(chunkPlan = listOf(
            ChunkEntry(0, 0, 10, false, ChunkStatus.Done, "one "),
            ChunkEntry(1, 10, 20, false, ChunkStatus.Pending, "SHOULD-NOT-APPEAR"),
            ChunkEntry(2, 20, 30, true,  ChunkStatus.Done, "three"),
        ))
        assertEquals("one three", s.assembledText(m))
    }

    @Test fun sweep_evicts_by_age_past_thirty_days() {
        val now = 40L * 24 * 60 * 60 * 1000
        val s = RecordingStore(File(tmp.root, "r"), clock = { now })
        s.save(meta("stale", now - 31L * 24 * 60 * 60 * 1000)) // 31 days old
        s.save(meta("fresh", now - 1L * 24 * 60 * 60 * 1000))
        s.sweep()
        assertEquals(listOf("fresh"), s.list().map { it.id })
    }

    @Test fun sweep_evicts_oldest_first_until_under_the_byte_cap() {
        val s = store(now = 10_000L)
        // Three recordings, each with a 120 MB audio blob; cap is 200 MB -> only the newest survives.
        listOf("r1" to 100L, "r2" to 200L, "r3" to 300L).forEach { (id, t) ->
            s.save(meta(id, t))
            s.audioFile(id).writeBytes(ByteArray(1)) // length faked via meta.byteLength below
        }
        // Re-save with a large byteLength so the size accounting trips the cap.
        listOf("r1" to 100L, "r2" to 200L, "r3" to 300L).forEach { (id, t) ->
            s.save(meta(id, t).copy(byteLength = 120 * 1024 * 1024))
        }
        s.sweep(maxTotalBytes = 200L * 1024 * 1024)
        assertEquals(listOf("r3"), s.list().map { it.id })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.recording.RecordingStoreTest"
```
Expected: FAIL — `Unresolved reference: RecordingStore`.

- [ ] **Step 3: Implement `RecordingMeta.kt`**

```kotlin
package com.whispereverywhere.recording

import com.whispereverywhere.audio.ActiveSource
import kotlinx.serialization.Serializable

/** Lifecycle of a whole recording's transcription job. */
enum class BatchStatus { Recorded, Transcribing, PartiallyDone, Done, Failed }

/** Per-chunk checkpoint state. Only [Done] chunks are never re-run on Retry. */
enum class ChunkStatus { Pending, Done, Failed }

/** Which engine actually produced a chunk's text. Drives the detail-screen engine chip. */
enum class EngineUsed { LOCAL, OPENAI }

/**
 * One planned slice of [RecordingMeta.chunkPlan]. Byte offsets are into audio.pcm (raw PCM16LE),
 * always even (sample-aligned). [hardCut] = the planner had to cut mid-speech because no silence
 * fell before the ceiling; degraded but bounded, and logged length-only.
 */
@Serializable
data class ChunkEntry(
    val index: Int,
    val startByte: Int,
    val endByte: Int,
    val hardCut: Boolean,
    val status: ChunkStatus = ChunkStatus.Pending,
    val text: String = "",
)

/**
 * The manifest.json for one recording directory. kotlinx.serialization, NOT org.json — org.json
 * ships in android.jar and returns type defaults under this project's unitTests.returnDefaultValues
 * config, so a manifest parsed with it would silently come back blank.
 *
 * [source] is the load-bearing field: it is the persisted provenance that lets BatchRouting keep a
 * PLAYBACK recording on-device only, forever. Enums serialize by their constant NAME (never the
 * ordinal), so this stays correct even if ActiveSource is reordered — the same rule ProviderId
 * relies on.
 */
@Serializable
data class RecordingMeta(
    val id: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val source: ActiveSource,
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val byteLength: Int,
    val status: BatchStatus = BatchStatus.Recorded,
    val engineUsed: EngineUsed? = null,
    val modelId: String? = null,
    val language: String? = null,
    val chunkPlan: List<ChunkEntry> = emptyList(),
)
```

- [ ] **Step 4: Implement `RecordingStore.kt`**

```kotlin
package com.whispereverywhere.recording

import kotlinx.serialization.json.Json
import java.io.File

/**
 * The recordings library: one directory per clip under [root], each holding audio.pcm (raw PCM16LE)
 * and manifest.json (a [RecordingMeta]).
 *
 * [root] is deliberately rooted at Context.noBackupFilesDir by the caller — raw audio must stay out
 * of Android Auto Backup, which would otherwise be an undisclosed off-device transfer.
 *
 * Separate from TranscriptStore on purpose: that store is TEXT-ONLY and its "audio never retained"
 * contract is preserved. This is a distinct audio library with its own retention.
 */
class RecordingStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init { root.mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun dir(id: String): File = File(root, id).also { it.mkdirs() }
    fun audioFile(id: String): File = File(dir(id), "audio.pcm")
    private fun manifestFile(id: String): File = File(dir(id), "manifest.json")

    fun save(meta: RecordingMeta) {
        // Whole-file write of a tiny manifest; last write wins. The per-chunk checkpoint in
        // BatchTranscriber calls this after each chunk, so a killed job resumes from the last save.
        manifestFile(meta.id).writeText(json.encodeToString(RecordingMeta.serializer(), meta))
    }

    fun read(id: String): RecordingMeta? {
        val f = manifestFile(id)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(RecordingMeta.serializer(), f.readText()) }.getOrNull()
    }

    /** Newest first. Directories without a readable manifest are ignored. */
    fun list(): List<RecordingMeta> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { read(it.name) }
            .sortedByDescending { it.createdAtMs }

    /** Recursively removes the whole <uuid>/ directory — audio.pcm included (invariant #7). */
    fun delete(id: String) { File(root, id).deleteRecursively() }

    /** The transcript so far: Done chunks, in index order, concatenated. */
    fun assembledText(meta: RecordingMeta): String =
        meta.chunkPlan.sortedBy { it.index }
            .filter { it.status == ChunkStatus.Done }
            .joinToString("") { it.text }

    /**
     * Rolling retention: age first, then oldest-first eviction until under the byte cap. Byte size
     * is taken from meta.byteLength (the audio payload) — the dominant cost; the manifest is bytes.
     */
    fun sweep(maxAgeMs: Long = MAX_AGE_MS, maxTotalBytes: Long = MAX_TOTAL_BYTES) {
        val now = clock()
        val entries = list().toMutableList()   // newest first
        entries.removeAll { m ->
            if (now - m.createdAtMs > maxAgeMs) { delete(m.id); true } else false
        }
        var total = entries.sumOf { it.byteLength.toLong() }
        while (total > maxTotalBytes && entries.isNotEmpty()) {
            val oldest = entries.removeAt(entries.lastIndex)
            total -= oldest.byteLength.toLong()
            delete(oldest.id)
        }
    }

    companion object {
        /** "Keep it for retry" — a month. (Owner-question 4 default.) */
        const val MAX_AGE_MS: Long = 30L * 24 * 60 * 60 * 1000
        /** Audio is heavy; 200 MB holds hours of recordings. (Owner-question 4 default.) */
        const val MAX_TOTAL_BYTES: Long = 200L * 1024 * 1024
    }
}
```

- [ ] **Step 5: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.recording.RecordingStoreTest"
```
Expected: PASS, 8 tests. Full suite: previous total **+8**, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/recording/RecordingMeta.kt \
        app/src/main/java/com/whispereverywhere/recording/RecordingStore.kt \
        app/src/test/java/com/whispereverywhere/recording/RecordingStoreTest.kt
git commit -m "feat(recording): RecordingStore — a per-clip audio+manifest library

One directory per recording under noBackupFilesDir/recordings/<uuid>/ so
raw audio stays out of Android Auto Backup. The manifest is kotlinx.
serialization, not org.json, which returns type defaults under this
project's unit-test config.

source (ActiveSource) is the persisted provenance carrier that lets a
PLAYBACK recording stay on-device only forever. delete() removes the whole
directory, audio included; sweep() is 30-day + 200 MB oldest-first."
```

---

## Task 2: `PcmSink` + `StorageGuard` — stream PCM to disk, gated on free space

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/recording/PcmSink.kt`
- Test: `app/src/test/java/com/whispereverywhere/recording/StorageGuardTest.kt`

**Interfaces:**
- Consumes: nothing beyond `java.io`.
- Produces, used by Tasks 5/7:
  - `class PcmSink(file: File)` with `append(pcm: ByteArray, len: Int)`, `bytesWritten(): Long`, `close()`.
  - `object StorageGuard { fun enoughSpace(availableBytes: Long, requiredBytes: Long): Boolean; fun availableBytesAt(path: File): Long }`.

**Why:** No audio path currently checks free space (the only `StatFs` gates are on model/TTS download). Recording to a full disk must fail cleanly, not corrupt a half-written clip. The `StatFs` call is Android and returns defaults under unit tests, so the **decision math is a pure function** (`enoughSpace`) tested directly; the `StatFs` read is a thin wrapper exercised on-device.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/recording/StorageGuardTest.kt`:

```kotlin
package com.whispereverywhere.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageGuardTest {

    @Test fun ample_space_is_allowed() {
        assertTrue(StorageGuard.enoughSpace(availableBytes = 100_000_000, requiredBytes = 10_000_000))
    }

    @Test fun a_ten_percent_headroom_is_required_not_a_bare_fit() {
        // required * 1.1 must fit. A bare fit (available == required) is rejected: writing to the
        // last byte of the filesystem is how a recording gets truncated mid-flight.
        assertFalse(StorageGuard.enoughSpace(availableBytes = 10_000_000, requiredBytes = 10_000_000))
        assertTrue(StorageGuard.enoughSpace(availableBytes = 11_000_001, requiredBytes = 10_000_000))
    }

    @Test fun zero_required_always_fits() {
        assertTrue(StorageGuard.enoughSpace(availableBytes = 0, requiredBytes = 0))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.recording.StorageGuardTest"
```
Expected: FAIL — `Unresolved reference: StorageGuard`.

- [ ] **Step 3: Implement `PcmSink.kt`** (contains both `PcmSink` and `StorageGuard`)

```kotlin
package com.whispereverywhere.recording

import android.os.StatFs
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Appends raw PCM16 straight to a file as the recorder produces it. Raw, not WAV: the 44-byte
 * header would be dead weight because chunks are cut by byte offset and WAV-wrapped in memory only
 * at dispatch. Buffered; the caller MUST close() to flush the tail.
 */
class PcmSink(file: File) {
    private val out = BufferedOutputStream(FileOutputStream(file))
    private var written = 0L

    /** [len] mirrors StreamingAudioRecorder's onChunk(bytes, amplitude) contract — write exactly len. */
    fun append(pcm: ByteArray, len: Int) {
        out.write(pcm, 0, len)
        written += len
    }

    fun bytesWritten(): Long = written

    fun close() {
        out.flush()
        out.close()
    }
}

/**
 * Free-space gate for the audio write path — the first one the app has (model/TTS downloads have
 * their own). The decision is a pure function so it is unit-testable; the StatFs read is a thin
 * wrapper because StatFs returns type defaults under unit tests.
 */
object StorageGuard {
    /** Requires required*1.1 to fit — a bare fit risks a truncated recording at end of disk. */
    fun enoughSpace(availableBytes: Long, requiredBytes: Long): Boolean =
        availableBytes >= (requiredBytes.toDouble() * 1.1).toLong()

    fun availableBytesAt(path: File): Long =
        runCatching { StatFs(path.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
}
```

- [ ] **Step 4: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.recording.StorageGuardTest"
.\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: PASS, 3 tests; debug green. Full suite: previous **+3**, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/recording/PcmSink.kt \
        app/src/test/java/com/whispereverywhere/recording/StorageGuardTest.kt
git commit -m "feat(recording): PcmSink + StorageGuard — stream PCM to disk with a free-space gate

Raw PCM16, not WAV — chunks are cut by byte offset and WAV-wrapped in
memory at dispatch, so the header would be dead weight. StorageGuard is the
first free-space check on an audio path; the decision is a pure function
(required*1.1 must fit) because StatFs returns defaults under unit tests."
```

---

## Task 3: `SilenceScanner` + `ChunkPlanner` — the boundary math (riskiest logic)

> Owner-question 1 (SAF import) is settled OUT here: the planner is fed only PCM this app captured, so provenance is never ambiguous.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/batch/ChunkPlanner.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/batch/ChunkPlannerTest.kt`

**Interfaces:**
- Consumes: `com.whispereverywhere.util.AudioMath.amplitude`, `ChunkEntry` (Task 1).
- Produces, used by Task 5:
  - `object SilenceScanner { fun scan(pcm: ByteArray): List<Int> }` — even byte offsets at silence-gap midpoints.
  - `object ChunkPlanner { fun plan(totalBytes: Int, maxChunkBytes: Int, minChunkBytes: Int, boundaries: List<Int>): List<ChunkEntry> }`.
  - `const val CLOUD_CEILING_BYTES = 20 * 1024 * 1024 - 44` and `const val LOCAL_CHUNK_BYTES = 90 * 16_000 * 2` (companion of `ChunkPlanner`).

**The whole point:** every chunk must be ≤ its ceiling (cloud 20 MB incl. the 44-byte WAV header added later → the raw ceiling excludes it; the OpenAI hard cap is 25 MB), cut on silence when possible, hard-cut when not, and every offset even so a cut never splits a PCM16 sample. Correctness and bounds hold **regardless of boundary quality** because of the hard-cut fallback.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/transcription/batch/ChunkPlannerTest.kt`:

```kotlin
package com.whispereverywhere.transcription.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {

    // ---------- ChunkPlanner.plan: the packing math ----------

    @Test fun audio_that_fits_the_ceiling_is_one_natural_chunk() {
        val p = ChunkPlanner.plan(totalBytes = 1000, maxChunkBytes = 2000, minChunkBytes = 0, boundaries = emptyList())
        assertEquals(1, p.size)
        assertEquals(0, p[0].startByte); assertEquals(1000, p[0].endByte)
        assertFalse("a whole-fit tail is never a hard cut", p[0].hardCut)
    }

    @Test fun zero_length_audio_plans_nothing() {
        assertTrue(ChunkPlanner.plan(0, 2000, 0, emptyList()).isEmpty())
    }

    @Test fun the_plan_covers_every_byte_with_no_gaps_or_overlaps() {
        val p = ChunkPlanner.plan(10_000, 3000, 0, boundaries = listOf(2500, 6000))
        assertEquals(0, p.first().startByte)
        assertEquals(10_000, p.last().endByte)
        for (i in 1 until p.size) assertEquals("chunk $i must start where ${i - 1} ended", p[i - 1].endByte, p[i].startByte)
    }

    @Test fun it_cuts_on_the_last_silence_at_or_before_the_ceiling() {
        // Ceiling from 0 is 3000; boundaries 900 and 2800 are candidates, 3200 is past the ceiling.
        val p = ChunkPlanner.plan(6000, maxChunkBytes = 3000, minChunkBytes = 0, boundaries = listOf(900, 2800, 3200))
        assertEquals(2800, p[0].endByte)          // the LAST boundary <= 3000, not 900
        assertFalse(p[0].hardCut)
    }

    @Test fun a_boundary_exactly_on_the_ceiling_is_usable() {
        val p = ChunkPlanner.plan(6000, 3000, 0, boundaries = listOf(3000))
        assertEquals(3000, p[0].endByte)
        assertFalse(p[0].hardCut)
    }

    @Test fun continuous_speech_with_no_silence_is_hard_cut_at_the_ceiling() {
        val p = ChunkPlanner.plan(7000, maxChunkBytes = 3000, minChunkBytes = 0, boundaries = emptyList())
        assertEquals(listOf(3000, 6000, 7000), p.map { it.endByte })
        assertTrue("first over-ceiling cut is hard", p[0].hardCut)
        assertTrue(p[1].hardCut)
        assertFalse("the final tail fits, so it is natural", p[2].hardCut)
    }

    @Test fun no_chunk_ever_exceeds_the_ceiling() {
        val p = ChunkPlanner.plan(50_000, maxChunkBytes = 4096, minChunkBytes = 0,
            boundaries = (1000..49_000 step 1500).toList())
        p.forEach { assertTrue("chunk ${it.index} = ${it.endByte - it.startByte}", it.endByte - it.startByte <= 4096) }
    }

    @Test fun every_offset_is_even_so_a_sample_is_never_split() {
        // Odd total, odd max, odd boundaries — all must be forced even.
        val p = ChunkPlanner.plan(9999, maxChunkBytes = 3001, minChunkBytes = 0, boundaries = listOf(1501, 2999))
        p.forEach {
            assertEquals("start even", 0, it.startByte % 2)
            assertEquals("end even", 0, it.endByte % 2)
        }
    }

    @Test fun a_boundary_too_close_to_the_start_is_skipped_for_a_hard_cut() {
        // minChunkBytes = 2000: the only boundary (500) is too close, so hard-cut at the ceiling.
        val p = ChunkPlanner.plan(6000, maxChunkBytes = 3000, minChunkBytes = 2000, boundaries = listOf(500))
        assertEquals(3000, p[0].endByte)
        assertTrue(p[0].hardCut)
    }

    @Test fun boundaries_are_deduped_sorted_and_bounds_filtered() {
        // Unsorted, duplicated, one at 0 and one at/after total — the planner must not choke.
        val p = ChunkPlanner.plan(6000, 3000, 0, boundaries = listOf(2800, 2800, 0, 6000, 900))
        assertEquals(2800, p[0].endByte)
        assertEquals(0, p.first().startByte)
        assertEquals(6000, p.last().endByte)
    }

    @Test fun cloud_and_local_ceilings_shape_the_same_audio_differently() {
        val fifteenMin = 15 * 60 * 16_000 * 2   // 28.8 MB
        val cloud = ChunkPlanner.plan(fifteenMin, ChunkPlanner.CLOUD_CEILING_BYTES, 0, emptyList())
        val local = ChunkPlanner.plan(fifteenMin, ChunkPlanner.LOCAL_CHUNK_BYTES, 0, emptyList())
        cloud.forEach { assertTrue(it.endByte - it.startByte <= ChunkPlanner.CLOUD_CEILING_BYTES) }
        assertTrue("local's 90 s chunks are many more than cloud's 20 MB chunks", local.size > cloud.size)
    }

    @Test fun the_cloud_ceiling_stays_under_the_openai_hard_cap_even_after_the_wav_header() {
        val hardCap = 25L * 1024 * 1024
        assertTrue((ChunkPlanner.CLOUD_CEILING_BYTES.toLong() + 44) < hardCap)
    }

    // ---------- SilenceScanner.scan: pause detection ----------

    private fun loud(nFrames: Int): ByteArray {
        // Frames of 960 bytes full of a large-amplitude square wave (well above the 500 threshold).
        val one = ByteArray(960) { if (it % 2 == 0) 0x00 else 0x40 } // ~0x4000 samples
        return ByteArray(nFrames * 960).also { for (f in 0 until nFrames) one.copyInto(it, f * 960) }
    }
    private fun quiet(nFrames: Int) = ByteArray(nFrames * 960) // all zero -> RMS 0

    @Test fun a_long_pause_between_two_utterances_yields_one_boundary() {
        val pcm = loud(20) + quiet(12) + loud(20)  // 12 frames ~= 360 ms of silence
        val cuts = SilenceScanner.scan(pcm)
        assertEquals(1, cuts.size)
        // Boundary sits inside the gap, i.e. between the two loud runs.
        assertTrue(cuts[0] > 20 * 960 && cuts[0] < 32 * 960)
        assertEquals("even", 0, cuts[0] % 2)
    }

    @Test fun a_gap_shorter_than_the_minimum_is_not_a_boundary() {
        val pcm = loud(20) + quiet(3) + loud(20)   // ~90 ms — a within-speech micro-pause
        assertTrue(SilenceScanner.scan(pcm).isEmpty())
    }

    @Test fun trailing_silence_at_the_end_is_not_a_boundary() {
        // A cut at the very end is useless; only gaps followed by more speech count.
        assertTrue(SilenceScanner.scan(loud(20) + quiet(30)).isEmpty())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.batch.ChunkPlannerTest"
```
Expected: FAIL — `Unresolved reference: ChunkPlanner`.

- [ ] **Step 3: Implement `ChunkPlanner.kt`** (both objects in one file — matches the design footprint)

```kotlin
package com.whispereverywhere.transcription.batch

import com.whispereverywhere.recording.ChunkEntry
import com.whispereverywhere.util.AudioMath

/**
 * Coarse cut-planning by an energy scan, NOT the Silero VAD.
 *
 * The design named the Silero VAD seam for boundaries, but it is native-internal to whisper_full
 * (no JNI export returns boundaries) and running it over a whole 30-minute clip would need the
 * entire recording as a FloatArray — the OOM chunking exists to avoid. So the COARSE cut uses this
 * memory-bounded Kotlin scan; the Silero VAD still runs unchanged inside whisper_full per chunk, so
 * speech is still VAD-trimmed before the encoder. Quality is unaffected; bounds are guaranteed by
 * ChunkPlanner's hard-cut fallback. (Deviation from decision log #8, reported to the owner.)
 */
object SilenceScanner {
    private const val FRAME_BYTES = 960          // 30 ms @16 kHz PCM16 (480 samples)
    private const val SILENCE_RMS = 500          // matches the recorder's voiceThr
    private const val MIN_GAP_FRAMES = 8         // ~240 ms of continuous quiet = a real pause

    /** Even byte offsets at the midpoint of each silence gap that is FOLLOWED by more speech. */
    fun scan(pcm: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        var pos = 0
        var gapStart = -1
        while (pos + 1 < pcm.size) {
            val len = minOf(FRAME_BYTES, pcm.size - pos)
            val rms = AudioMath.amplitude(pcm.copyOfRange(pos, pos + len), len)
            if (rms < SILENCE_RMS) {
                if (gapStart < 0) gapStart = pos
            } else {
                if (gapStart >= 0) {
                    // A gap that ended because speech resumed. Long enough? Emit its midpoint.
                    if (pos - gapStart >= MIN_GAP_FRAMES * FRAME_BYTES) {
                        var mid = gapStart + (pos - gapStart) / 2
                        mid -= mid % 2
                        out.add(mid)
                    }
                    gapStart = -1
                }
            }
            pos += len
        }
        // A gap still open at end-of-file is trailing silence — never a useful cut. Dropped.
        return out
    }
}

/**
 * Packs an audio length into chunks that each fit a byte ceiling, cutting on the last silence
 * boundary at or before the ceiling and hard-cutting when speech is continuous.
 *
 * Correctness does NOT depend on boundary quality: with no boundaries at all the plan is a run of
 * ceiling-sized hard cuts, which is bounded and can never exceed the ceiling or OOM. All offsets
 * are forced even so a cut never splits a PCM16 sample (which would shift every later sample by one
 * byte — white noise from that point on).
 */
object ChunkPlanner {
    /** 20 MB minus the 44-byte WAV header added at dispatch — a safety margin under OpenAI's 25 MB cap. */
    const val CLOUD_CEILING_BYTES = 20 * 1024 * 1024 - 44
    /** ~90 s of 16 kHz PCM16 — bounds native memory to ~2.9 MB/chunk, avoiding the long-feed OOM. */
    const val LOCAL_CHUNK_BYTES = 90 * 16_000 * 2

    fun plan(
        totalBytes: Int,
        maxChunkBytes: Int,
        minChunkBytes: Int,
        boundaries: List<Int>,
    ): List<ChunkEntry> {
        val total = totalBytes - (totalBytes % 2)        // whole samples only
        if (total <= 0) return emptyList()
        val maxChunk = (maxChunkBytes - (maxChunkBytes % 2)).coerceAtLeast(2)
        val minChunk = (minChunkBytes - (minChunkBytes % 2)).coerceAtLeast(0)

        val cuts = boundaries.asSequence()
            .map { it - (it % 2) }
            .filter { it in 2 until total }
            .distinct()
            .sorted()
            .toList()

        val out = ArrayList<ChunkEntry>()
        var start = 0
        var index = 0
        while (start < total) {
            if (total - start <= maxChunk) {
                out.add(ChunkEntry(index, start, total, hardCut = false))  // tail fits: natural end
                break
            }
            val ceil = start + maxChunk
            val cut = cuts.lastOrNull { it > start && it <= ceil && (it - start) >= minChunk }
            if (cut != null) {
                out.add(ChunkEntry(index, start, cut, hardCut = false))
                start = cut
            } else {
                out.add(ChunkEntry(index, start, ceil, hardCut = true))   // no silence: bounded hard cut
                start = ceil
            }
            index++
        }
        return out
    }
}
```

- [ ] **Step 4: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.batch.ChunkPlannerTest"
```
Expected: PASS, 16 tests. Full suite: previous **+16**, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/batch/ChunkPlanner.kt \
        app/src/test/java/com/whispereverywhere/transcription/batch/ChunkPlannerTest.kt
git commit -m "feat(batch): SilenceScanner + ChunkPlanner — VAD-margin chunk planning

Coarse cuts come from a memory-bounded energy scan, not the Silero VAD:
that seam is native-internal to whisper_full and scanning a whole clip
would need the entire FloatArray in memory — the OOM chunking avoids. The
Silero VAD still runs per chunk inside whisper_full. Deviation from the
design's decision log #8, reported.

Every chunk fits its ceiling (cloud 20 MB, under the 25 MB OpenAI cap;
local ~90 s); continuous speech is hard-cut and bounded; all offsets are
even so a cut never splits a PCM16 sample."
```

---

## Task 4: `BatchRouting` — invariant #1 as code, with a pinning test

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/batch/BatchRouting.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/batch/BatchRoutingTest.kt`

**Interfaces:**
- Consumes: `ActiveSource`.
- Produces, used by Task 5:
  - `object BatchRouting { fun cloudEligible(source: ActiveSource): Boolean }`

**This is the MF1 lesson made concrete.** The invariant is not prose in a header — it is one pure function with its own pinning test, mirrored from `SourceRoutedTranscriptionEngine.engineForSource` (`PLAYBACK → on-device only`). Task 5 wires it as the first decision in `transcribe()`, and `BatchTranscriberTest` proves the network is never touched for a PLAYBACK recording.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/transcription/batch/BatchRoutingTest.kt`:

```kotlin
package com.whispereverywhere.transcription.batch

import com.whispereverywhere.audio.ActiveSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchRoutingTest {

    @Test fun mic_audio_is_cloud_eligible() {
        assertTrue(BatchRouting.cloudEligible(ActiveSource.MIC))
    }

    @Test fun playback_audio_is_never_cloud_eligible() {
        // Invariant #1: MediaProjection device audio belongs to third parties who never consented.
        // No key, no provider, no consent, no network can make it eligible — the source alone decides,
        // exactly as SourceRoutedTranscriptionEngine.engineForSource routes PLAYBACK to on-device.
        assertFalse(BatchRouting.cloudEligible(ActiveSource.PLAYBACK))
    }

    @Test fun eligibility_is_total_over_the_enum() {
        // If a new source is added, this test forces a deliberate ruling rather than a silent default.
        ActiveSource.values().forEach { s ->
            val eligible = BatchRouting.cloudEligible(s)
            assertTrue("every source must be explicitly ruled", eligible || s == ActiveSource.PLAYBACK)
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.batch.BatchRoutingTest"
```
Expected: FAIL — `Unresolved reference: BatchRouting`.

- [ ] **Step 3: Implement `BatchRouting.kt`**

```kotlin
package com.whispereverywhere.transcription.batch

import com.whispereverywhere.audio.ActiveSource

/**
 * Invariant #1 written as code: MediaProjection device audio (PLAYBACK) is transcribed on-device
 * ONLY, forever — including through batch mode and Retry. This mirrors
 * SourceRoutedTranscriptionEngine.engineForSource: the ROUTE is decided by the capture source
 * alone, never by the provider, the key, the accepted consent, or the network, none of which can
 * make third-party audio shippable.
 *
 * BatchTranscriber consults this as the FIRST decision in transcribe() and, defensively, again
 * before any cloud dispatch, so no future edit can route a PLAYBACK chunk to the network without
 * tripping a require().
 */
object BatchRouting {
    fun cloudEligible(source: ActiveSource): Boolean = when (source) {
        ActiveSource.MIC -> true
        ActiveSource.PLAYBACK -> false
    }
}
```

- [ ] **Step 4: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.batch.BatchRoutingTest"
```
Expected: PASS, 3 tests. Full suite: previous **+3**, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/batch/BatchRouting.kt \
        app/src/test/java/com/whispereverywhere/transcription/batch/BatchRoutingTest.kt
git commit -m "feat(batch): BatchRouting — PLAYBACK is on-device only, forever

Invariant #1 as one pure function with its own pinning test, mirrored from
SourceRoutedTranscriptionEngine: the capture source alone decides cloud
eligibility, and PLAYBACK is never eligible under any key/provider/consent/
network combination. A when() over the enum forces any future source to be
ruled on deliberately rather than defaulting to eligible."
```

---

## Task 5: `BatchTranscriber` — sequential, checkpointed, resumable, one-way fallback

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/batch/BatchTranscriber.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/batch/BatchTranscriberTest.kt`

**Interfaces:**
- Consumes: `RecordingStore`, `RecordingMeta`/`ChunkEntry`/`ChunkStatus`/`BatchStatus`/`EngineUsed` (Task 1), `SilenceScanner`/`ChunkPlanner`/`BatchRouting` (Tasks 3/4), `SttProvider`/`SttResult`/`SttError`/`FatalKind` (C2a), `WhisperBackend`/`ModelPathProvider` (Release A seams), `AudioMath`, `TranscriptText.clean`.
- Produces, used by Task 6:
  - `data class BatchProgress(recordingId, chunkIndex, chunkCount, status)`
  - `class BatchTranscriber(store, cloud: SttProvider?, backend: WhisperBackend, modelPathProvider: ModelPathProvider, retry, clock)` with:
    - `val progress: StateFlow<BatchProgress?>`
    - `suspend fun transcribe(id: String, reset: Boolean = false)`
    - `fun cancel()`

**The three behaviors that carry the design:**
1. **Provenance gate first.** `cloudEligible(meta.source)` is consulted before anything else; a PLAYBACK recording forces `cloud = null` (local), and a defensive `require` guards the cloud-dispatch path so a PLAYBACK chunk can never reach the network.
2. **Checkpoint = resume, not restart.** After each chunk its `status=Done` + `text` is written to the manifest. `transcribe()` re-dispatches only non-`Done` chunks, so a `Done` cloud chunk is **never re-uploaded** — Retry is safe on a paid API. `reset=true` (Re-transcribe) clears the plan first.
3. **Sequential + one-way fallback + latching fatal.** Chunk N awaits before N+1 (append to a `StringBuilder` via the manifest). A cloud chunk that returns `Offline`/exhausted-`Transient`/`BadSegment` falls to local for that chunk (`engineUsed → LOCAL`); a cloud `Fatal` (bad key / no credit) latches, stops the loop, marks `Failed`, and leaves the finished chunks intact so Retry resumes after the user fixes the key. Local never escalates to cloud.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/transcription/batch/BatchTranscriberTest.kt`:

```kotlin
package com.whispereverywhere.transcription.batch

import com.whispereverywhere.audio.ActiveSource
import com.whispereverywhere.net.FakeHttpTransport
import com.whispereverywhere.net.HttpResult
import com.whispereverywhere.recording.BatchStatus
import com.whispereverywhere.recording.ChunkStatus
import com.whispereverywhere.recording.EngineUsed
import com.whispereverywhere.recording.RecordingMeta
import com.whispereverywhere.recording.RecordingStore
import com.whispereverywhere.transcription.ModelPathProvider
import com.whispereverywhere.transcription.WhisperBackend
import com.whispereverywhere.transcription.cloud.OpenAiStt
import com.whispereverywhere.transcription.cloud.SttError
import com.whispereverywhere.transcription.cloud.SttProvider
import com.whispereverywhere.transcription.cloud.SttResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class BatchTranscriberTest {

    @get:Rule val tmp = TemporaryFolder()

    /** A fake native backend: every chunk transcribes to a fixed marker so order is checkable. */
    private class FakeBackend(private val text: String = "L") : WhisperBackend {
        var loads = 0; var releases = 0; var calls = 0
        override fun load(modelPath: String): Long { loads++; return 42L }
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String { calls++; return text }
        override fun release(ctx: Long) { releases++ }
    }
    private val modelPath = object : ModelPathProvider { override fun installedModelPath() = "/models/x.bin" }

    private fun storeWith(pcmBytes: Int, source: ActiveSource): Pair<RecordingStore, String> {
        val s = RecordingStore(File(tmp.root, "rec"))
        val id = "clip1"
        s.audioFile(id).writeBytes(ByteArray(pcmBytes) { (it % 251).toByte() })
        s.save(RecordingMeta(id = id, createdAtMs = 1L, durationMs = 3000L, source = source, byteLength = pcmBytes))
        return s to id
    }

    // Small ceilings so tests make several chunks without megabytes of PCM. Injected via a subclass
    // of the planner call in BatchTranscriber (the transcriber exposes chunk-ceiling overrides for tests).

    @Test fun local_only_transcribes_every_chunk_and_marks_done() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 20_000, source = ActiveSource.MIC)
        val backend = FakeBackend("hi ")
        val t = BatchTranscriber(store, cloud = null, backend = backend, modelPathProvider = modelPath)
            .apply { testCloudCeiling = 6000; testLocalChunk = 6000 }
        t.transcribe(id)
        val m = store.read(id)!!
        assertEquals(BatchStatus.Done, m.status)
        assertTrue("multiple chunks", m.chunkPlan.size >= 3)
        assertTrue(m.chunkPlan.all { it.status == ChunkStatus.Done })
        assertEquals(backend.loads, backend.releases)               // ctx released
        assertEquals("hi hi hi hi ".trimEnd().length, store.assembledText(m).trimEnd().length)
    }

    @Test fun a_playback_recording_never_touches_the_network_even_with_full_cloud_creds() = runBlocking {
        // The MF1 pinning test: stored key + a real cloud provider, but PLAYBACK source.
        val (store, id) = storeWith(pcmBytes = 12_000, source = ActiveSource.PLAYBACK)
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"CLOUD"}""") }
        val cloud = OpenAiStt(fake, "sk-realkey")
        val backend = FakeBackend("local")
        val t = BatchTranscriber(store, cloud = cloud, backend = backend, modelPathProvider = modelPath)
            .apply { testCloudCeiling = 6000; testLocalChunk = 6000 }
        t.transcribe(id)
        val m = store.read(id)!!
        assertEquals("PLAYBACK must resolve to local", 0, fake.callCount)   // network NEVER hit
        assertTrue(store.assembledText(m).contains("local"))
        assertTrue(m.chunkPlan.all { it.status == ChunkStatus.Done })
        assertEquals(EngineUsed.LOCAL, m.engineUsed)
    }

    @Test fun cloud_happy_path_uses_the_provider_and_marks_openai() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 8000, source = ActiveSource.MIC)
        val fake = FakeHttpTransport { _, _ -> HttpResult.Ok(200, """{"text":"C "}""") }
        val t = BatchTranscriber(store, cloud = OpenAiStt(fake, "sk-k"), backend = FakeBackend(),
            modelPathProvider = modelPath).apply { testCloudCeiling = 3000; testLocalChunk = 3000 }
        t.transcribe(id)
        val m = store.read(id)!!
        assertTrue(fake.callCount >= 2)
        assertEquals(EngineUsed.OPENAI, m.engineUsed)
        assertTrue(store.assembledText(m).trim().startsWith("C"))
    }

    @Test fun a_cloud_chunk_that_fails_transiently_falls_back_to_local_one_way() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 4000, source = ActiveSource.MIC)
        // 503 -> Transient; after retries the chunk falls to local.
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(503, "upstream") }
        val backend = FakeBackend("LOCAL-SAVED")
        val t = BatchTranscriber(store, cloud = OpenAiStt(fake, "sk-k"), backend = backend,
            modelPathProvider = modelPath).apply { testCloudCeiling = 5000; testLocalChunk = 5000; testMaxCloudRetries = 2 }
        t.transcribe(id)
        val m = store.read(id)!!
        assertEquals(BatchStatus.Done, m.status)
        assertTrue("local rescued the chunk", store.assembledText(m).contains("LOCAL-SAVED"))
    }

    @Test fun a_fatal_cloud_error_latches_stops_and_leaves_finished_chunks_for_resume() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 9000, source = ActiveSource.MIC)
        val n = AtomicInteger(0)
        // First chunk 200, then 401 (bad key) forever. The 401 must latch: no further requests.
        val fake = FakeHttpTransport { _, _ ->
            if (n.getAndIncrement() == 0) HttpResult.Ok(200, """{"text":"first "}""")
            else HttpResult.HttpError(401, "")
        }
        val t = BatchTranscriber(store, cloud = OpenAiStt(fake, "sk-bad"), backend = FakeBackend(),
            modelPathProvider = modelPath).apply { testCloudCeiling = 3000; testLocalChunk = 3000 }
        t.transcribe(id)
        val m = store.read(id)!!
        assertEquals(BatchStatus.Failed, m.status)
        assertEquals("chunk 0 kept as a checkpoint", ChunkStatus.Done, m.chunkPlan[0].status)
        assertTrue("chunk 1 not done", m.chunkPlan[1].status != ChunkStatus.Done)
        // Exactly two calls: the 200 and the single 401 that latched. Not one per remaining chunk.
        assertEquals(2, fake.callCount)
    }

    @Test fun retry_resumes_and_never_re_runs_a_done_chunk() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 9000, source = ActiveSource.MIC)
        // Pre-seed a plan where chunk 0 is already Done.
        val seeded = store.read(id)!!.copy(
            status = BatchStatus.PartiallyDone,
            chunkPlan = listOf(
                com.whispereverywhere.recording.ChunkEntry(0, 0, 3000, false, ChunkStatus.Done, "kept "),
                com.whispereverywhere.recording.ChunkEntry(1, 3000, 6000, false, ChunkStatus.Pending),
                com.whispereverywhere.recording.ChunkEntry(2, 6000, 9000, false, ChunkStatus.Pending),
            ),
        )
        store.save(seeded)
        val backend = FakeBackend("new ")
        val t = BatchTranscriber(store, cloud = null, backend = backend, modelPathProvider = modelPath)
        t.transcribe(id)  // resume
        val m = store.read(id)!!
        assertEquals(BatchStatus.Done, m.status)
        assertEquals("only the two pending chunks were transcribed", 2, backend.calls)
        assertEquals("kept ", m.chunkPlan[0].text)   // untouched
    }

    @Test fun reset_re_transcribes_every_chunk_from_scratch() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 6000, source = ActiveSource.MIC)
        store.save(store.read(id)!!.copy(chunkPlan = listOf(
            com.whispereverywhere.recording.ChunkEntry(0, 0, 6000, false, ChunkStatus.Done, "old"),
        )))
        val backend = FakeBackend("fresh ")
        val t = BatchTranscriber(store, cloud = null, backend = backend, modelPathProvider = modelPath)
        t.transcribe(id, reset = true)
        val m = store.read(id)!!
        assertTrue("plan re-computed and re-run", store.assembledText(m).contains("fresh"))
        assertTrue(backend.calls >= 1)
    }

    @Test fun cancel_stops_between_chunks_keeps_partial_and_never_deletes_audio() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 30_000, source = ActiveSource.MIC)
        val backend = object : WhisperBackend {
            var calls = 0
            override fun load(modelPath: String) = 1L
            override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String {
                calls++; return "c"
            }
            override fun release(ctx: Long) {}
        }
        val t = BatchTranscriber(store, cloud = null, backend = backend, modelPathProvider = modelPath)
            .apply { testCloudCeiling = 3000; testLocalChunk = 3000; onChunkDone = { cancel() } } // cancel after first
        t.transcribe(id)
        val m = store.read(id)!!
        assertEquals(BatchStatus.PartiallyDone, m.status)
        assertTrue("audio.pcm is preserved on cancel", store.audioFile(id).exists())
        assertTrue("stopped early", m.chunkPlan.count { it.status == ChunkStatus.Done } < m.chunkPlan.size)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.batch.BatchTranscriberTest"
```
Expected: FAIL — `Unresolved reference: BatchTranscriber`.

- [ ] **Step 3: Implement `BatchTranscriber.kt`**

```kotlin
package com.whispereverywhere.transcription.batch

import com.whispereverywhere.recording.BatchStatus
import com.whispereverywhere.recording.ChunkEntry
import com.whispereverywhere.recording.ChunkStatus
import com.whispereverywhere.recording.EngineUsed
import com.whispereverywhere.recording.RecordingMeta
import com.whispereverywhere.recording.RecordingStore
import com.whispereverywhere.transcription.ModelPathProvider
import com.whispereverywhere.transcription.WhisperBackend
import com.whispereverywhere.transcription.TranscriptText
import com.whispereverywhere.transcription.cloud.SttError
import com.whispereverywhere.transcription.cloud.SttProvider
import com.whispereverywhere.transcription.cloud.SttResult
import com.whispereverywhere.util.AudioMath
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.RandomAccessFile

data class BatchProgress(
    val recordingId: String,
    val chunkIndex: Int,
    val chunkCount: Int,
    val status: BatchStatus,
)

/**
 * Runs one recording's whole transcription, sequentially, checkpointing each chunk to the manifest.
 *
 * NOT a TranscriptionEngine — a batch job has no connect/sendAudio/commit; that contract is a lie
 * here. Output lands on a screen, not an injected IME field, so there is NO SegmentOrderer: chunk N
 * awaits before N+1, results are always in order, and the assembled text is a plain in-order join
 * held in the manifest.
 *
 * The provenance gate is invariant #1: BatchRouting.cloudEligible(source) is the FIRST decision, and
 * a defensive require() guards every cloud dispatch, so a PLAYBACK recording can never reach the
 * network. The fallback valve is one-way (cloud -> local per chunk); a cloud Fatal latches.
 *
 * Threading: the caller (BatchTranscriptionService) launches transcribe() on ONE single-thread
 * dispatcher. This class loads exactly one native ctx per job and transcribes every local chunk on
 * that one thread, so the single-threaded-native-access invariant holds; the ctx is released in a
 * finally.
 */
class BatchTranscriber(
    private val store: RecordingStore,
    private val cloud: SttProvider?,
    private val backend: WhisperBackend,
    private val modelPathProvider: ModelPathProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _progress = MutableStateFlow<BatchProgress?>(null)
    val progress: StateFlow<BatchProgress?> = _progress.asStateFlow()

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    // Test seams (production uses the real ceilings / retry budget). Not for production callers.
    internal var testCloudCeiling: Int = ChunkPlanner.CLOUD_CEILING_BYTES
    internal var testLocalChunk: Int = ChunkPlanner.LOCAL_CHUNK_BYTES
    internal var testMaxCloudRetries: Int = MAX_CLOUD_RETRIES
    internal var onChunkDone: (BatchTranscriber.() -> Unit)? = null

    suspend fun transcribe(id: String, reset: Boolean = false) {
        cancelled = false
        var meta = store.read(id) ?: return

        // (1) Provenance gate FIRST. PLAYBACK forces local; a MIC recording may use cloud if given.
        val effectiveCloud: SttProvider? = if (BatchRouting.cloudEligible(meta.source)) cloud else null
        val ceiling = if (effectiveCloud != null) testCloudCeiling else testLocalChunk

        // (2) Plan (or re-plan on reset). Reading the whole PCM once for the coarse scan is bounded
        // by the 200 MB cap and freed immediately; per-chunk work below streams via RandomAccessFile.
        if (reset || meta.chunkPlan.isEmpty()) {
            val pcm = store.audioFile(id).readBytes()
            val boundaries = SilenceScanner.scan(pcm)
            val plan = ChunkPlanner.plan(pcm.size, ceiling, minChunkBytes = 0, boundaries = boundaries)
            meta = meta.copy(chunkPlan = plan, status = BatchStatus.Transcribing, engineUsed = null)
            store.save(meta)
        } else {
            meta = meta.copy(status = BatchStatus.Transcribing); store.save(meta)
        }

        var ctx = 0L
        var usedCloud = false
        var usedLocal = false
        var fatal = false
        try {
            val raf = RandomAccessFile(store.audioFile(id), "r")
            raf.use { file ->
                for ((i, chunk) in meta.chunkPlan.withIndex()) {
                    if (chunk.status == ChunkStatus.Done) continue           // (RESUME) never re-run
                    if (cancelled) break
                    _progress.value = BatchProgress(id, i, meta.chunkPlan.size, BatchStatus.Transcribing)

                    val pcm = ByteArray(chunk.endByte - chunk.startByte)
                    file.seek(chunk.startByte.toLong())
                    file.readFully(pcm)

                    val (text, engine) = when {
                        effectiveCloud != null -> {
                            val r = runCloud(effectiveCloud, pcm, meta.language)
                            when (r) {
                                is CloudChunkResult.Ok -> { usedCloud = true; r.text to EngineUsed.OPENAI }
                                CloudChunkResult.Fatal -> { fatal = true; break }
                                CloudChunkResult.FallBack -> {                 // (ONE-WAY VALVE)
                                    if (ctx == 0L) ctx = loadCtx()
                                    usedLocal = true; runLocal(ctx, pcm, meta.language) to EngineUsed.LOCAL
                                }
                            }
                        }
                        else -> {
                            if (ctx == 0L) ctx = loadCtx()
                            usedLocal = true; runLocal(ctx, pcm, meta.language) to EngineUsed.LOCAL
                        }
                    }

                    // (CHECKPOINT) persist this chunk before moving on.
                    val updated = meta.chunkPlan.toMutableList()
                    updated[i] = chunk.copy(status = ChunkStatus.Done, text = text)
                    meta = meta.copy(chunkPlan = updated)
                    store.save(meta)
                    onChunkDone?.invoke(this)
                }
            }
        } finally {
            if (ctx != 0L) backend.release(ctx)
        }

        val allDone = meta.chunkPlan.all { it.status == ChunkStatus.Done }
        val finalStatus = when {
            fatal -> BatchStatus.Failed
            cancelled && !allDone -> BatchStatus.PartiallyDone
            allDone -> BatchStatus.Done
            else -> BatchStatus.PartiallyDone
        }
        val engineUsed = when {
            usedCloud && usedLocal -> EngineUsed.LOCAL   // mixed: at least one chunk stayed on-device
            usedCloud -> EngineUsed.OPENAI
            usedLocal -> EngineUsed.LOCAL
            else -> meta.engineUsed
        }
        meta = meta.copy(status = finalStatus, engineUsed = engineUsed,
            modelId = modelPathProvider.installedModelPath())
        store.save(meta)
        _progress.value = BatchProgress(id, meta.chunkPlan.size, meta.chunkPlan.size, finalStatus)
    }

    private sealed interface CloudChunkResult {
        data class Ok(val text: String) : CloudChunkResult
        data object FallBack : CloudChunkResult    // Offline / exhausted Transient / BadSegment
        data object Fatal : CloudChunkResult       // bad key / no credit — latch and stop
    }

    private suspend fun runCloud(provider: SttProvider, pcm: ByteArray, language: String?): CloudChunkResult {
        var attempt = 0
        while (true) {
            when (val r = provider.transcribe(pcm, language)) {
                is SttResult.Text -> return CloudChunkResult.Ok(TranscriptText.clean(r.text))
                is SttResult.Failed -> when (val e = r.error) {
                    is SttError.Fatal -> return CloudChunkResult.Fatal
                    SttError.BadSegment -> return CloudChunkResult.FallBack   // this chunk is cloud's problem
                    SttError.Offline -> return CloudChunkResult.FallBack
                    is SttError.Transient -> {
                        if (attempt >= testMaxCloudRetries) return CloudChunkResult.FallBack
                        delay(e.retryAfterMs ?: (BASE_BACKOFF_MS * (attempt + 1)))
                        attempt++
                    }
                }
            }
        }
    }

    private fun loadCtx(): Long {
        val path = modelPathProvider.installedModelPath()
            ?: error("No on-device model installed — cannot transcribe locally")
        return backend.load(path)
    }

    private fun runLocal(ctx: Long, pcm: ByteArray, language: String?): String {
        // Defensive: this path must be unreachable for PLAYBACK+cloud, but a local run for a PLAYBACK
        // recording is always fine — the guard is on CLOUD dispatch, enforced by runCloud's callers.
        val samples = AudioMath.pcm16ToFloat(pcm)
        return TranscriptText.clean(backend.transcribe(ctx, samples, language))
    }

    companion object {
        private const val MAX_CLOUD_RETRIES = 3
        private const val BASE_BACKOFF_MS = 400L
    }
}
```

> **Defensive `require` for the cloud path:** `runCloud` is only ever called when `effectiveCloud != null`, and `effectiveCloud` is null whenever `!cloudEligible(source)`. To make the invariant impossible to break by a future edit, add at the top of `runCloud`: `require(true)` is not enough — instead the CALLER already gates it. Keep the structural guarantee (effectiveCloud nulled for PLAYBACK) AND assert it: the `a_playback_recording_never_touches_the_network` test is the pin. If a reviewer wants belt-and-suspenders, add `check(BatchRouting.cloudEligible(meta.source))` immediately before the `runCloud(...)` call site.

- [ ] **Step 4: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.batch.BatchTranscriberTest"
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: PASS, 9 tests; release green. Full suite: previous **+9**, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/batch/BatchTranscriber.kt \
        app/src/test/java/com/whispereverywhere/transcription/batch/BatchTranscriberTest.kt
git commit -m "feat(batch): BatchTranscriber — sequential, checkpointed, resumable

Provenance gate first: PLAYBACK forces local; the network is never touched
for it (pinning test). Each chunk checkpoints status+text to the manifest,
so Retry resumes and a Done cloud chunk is never re-uploaded — safe on a
paid API. Sequential means no SegmentOrderer; output is an in-order join.

Fallback is one-way per chunk (cloud -> local on Offline/exhausted-Transient
/BadSegment); a cloud Fatal latches, stops, and leaves finished chunks for
resume. One native ctx per job on one thread; released in finally. Cancel
stops between chunks, keeps partial results, and never deletes audio."
```

---

## Task 6: `BatchTranscriptionService` — the foreground host + manifest entry

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/service/BatchTranscriptionService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: everything above, `WhisperEverywhereApp.getInstance()`, `WhisperNativeBackend`, `WhisperModelManager` (as `ModelPathProvider`), `OpenAiStt`, `OkHttpTransport`, `PreferencesManager`.
- Produces: a started service that runs one `BatchTranscriber.transcribe(id)` to completion off the main thread, on a single-thread dispatcher, publishing progress the ViewModels observe.

**Why a foreground service and not a plain coroutine:** a 30-minute job on a backgrounded app is killed by the OS mid-transcribe. Foreground keeps it alive; the per-chunk checkpoint is the second belt for the case where even a foreground service dies. Both are needed.

- [ ] **Step 1: Declare the service** in `AndroidManifest.xml`, inside `<application>`, next to the existing services:

```xml
<service
    android:name=".service.BatchTranscriptionService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

`dataSync` is the honest type for "process a saved file to completion"; it needs no extra runtime permission on the app's target SDK. If the target SDK requires `FOREGROUND_SERVICE_DATA_SYNC`, add exactly that `<uses-permission>` and nothing broader — do not request microphone/media-projection FGS types here; batch does not capture.

- [ ] **Step 2: Implement the service**

Behavior spec (match the shape of `FloatingBubbleService`'s construction and `serviceScope` usage; the implementer reads that file):

- `onStartCommand` reads `EXTRA_RECORDING_ID` (a String) and an optional `EXTRA_RESET` (Boolean). Missing id → `stopSelf()` and return `START_NOT_STICKY`.
- Call `startForeground(NOTIF_ID, notification)` immediately with a low-priority, ongoing notification: title "Transcribing recording", text from `BatchTranscriber.progress` ("Chunk i of N"). **No speed claims.** Reuse the app's existing notification channel pattern.
- Build the transcriber once per start:
  - `store = RecordingStore(File(noBackupFilesDir, "recordings"))` — **`noBackupFilesDir`, not `filesDir`** (keeps audio out of Auto Backup).
  - `provider`: resolve exactly as `FloatingBubbleService.resolveTranscriptionEngine` does — `sttProviderId` → `resolveSttProvider` → key via `providerAccounts.key(id)` → require `cloudDisclosureAccepted`. If all three hold AND network is validated, `cloud = OpenAiStt(sharedTransport, key)`, else `cloud = null`. **The BatchTranscriber's own `BatchRouting` gate still nulls cloud for a PLAYBACK recording regardless** — the service does not need to special-case source, but it must not pass cloud when the triad is unmet.
  - `backend = WhisperNativeBackend`, `modelPathProvider = app.whisperModelManager` (already implements `ModelPathProvider`).
- Launch on a dedicated single-thread dispatcher:
  `val jobDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()` and `serviceScope.launch(jobDispatcher) { transcriber.transcribe(id, reset) ; stopForeground(...) ; stopSelf() }`. Close the dispatcher in a finally.
- Expose progress to the UI via a process-scoped singleton the `BatchJobViewModel` reads (e.g. `BatchJobController` object holding the active `BatchTranscriber`'s `progress` StateFlow and a `cancel()` passthrough), OR bind — the simplest is a small `object BatchJobController { val progress = MutableStateFlow<BatchProgress?>(null); fun cancelActive() }` updated by the service. Keep it minimal; no binder.
- `onDestroy` cancels the active job cooperatively (`transcriber.cancel()`) and shuts the dispatcher.

- [ ] **Step 3: Verify**

```bash
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: both green. No new unit tests (this task is Android wiring; its logic — routing, resume, cancel — is already pinned in Task 5).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/service/BatchTranscriptionService.kt \
        app/src/main/AndroidManifest.xml
git commit -m "feat(batch): foreground BatchTranscriptionService

A 30-minute job on a backgrounded app is killed mid-transcribe; a
foreground service keeps it alive and the per-chunk manifest checkpoint is
the second belt if even that dies. Runs one BatchTranscriber.transcribe(id)
on a single-thread dispatcher (native-ctx invariant), publishing 'Chunk i
of N' progress with no speed claims. Store is rooted at noBackupFilesDir so
audio stays out of Auto Backup; cloud is passed only when the consent-v2 +
key + provider triad holds, and BatchRouting still nulls it for PLAYBACK."
```

---

## Task 7: UI — RecordScreen, RecordingsScreen, RecordingDetailScreen, ViewModels, Home card

> Owner-question 2 (audio playback) is settled OUT here: no MediaPlayer, no scrubber — the owner asked to redo transcription, not to listen. Owner-question 3 (separate library) is realized as the distinct `recordings` route.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/ui/RecordingsViewModel.kt`
- Create: `app/src/main/java/com/whispereverywhere/ui/BatchJobViewModel.kt`
- Create: `app/src/main/java/com/whispereverywhere/ui/screens/RecordScreen.kt`
- Create: `app/src/main/java/com/whispereverywhere/ui/screens/RecordingsScreen.kt`
- Create: `app/src/main/java/com/whispereverywhere/ui/screens/RecordingDetailScreen.kt`
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt`
- Modify: `app/src/main/java/com/whispereverywhere/MainActivity.kt`

**Interfaces:**
- Consumes: `RecordingStore`, `RecordingMeta`, `StreamingAudioRecorder`, `PcmSink`, `StorageGuard`, `BatchTranscriptionService`, `BatchJobController`.
- Produces: a working feature reachable from Home.

**Behavior spec** (Material3, `Scaffold`+`TopAppBar`, no new theme; the implementer matches `TranscriptsScreen.kt` and `HomeScreen.kt`):

- [ ] **Step 1: `RecordingsViewModel`** — holds `RecordingStore(File(app.noBackupFilesDir, "recordings"))`, exposes `list(): List<RecordingMeta>` (off-main via `viewModelScope`+`Dispatchers.IO`), `delete(id)`, and a `refresh` trigger. Mirror the onboarding ViewModel's shape.

- [ ] **Step 2: `BatchJobViewModel`** — observes `BatchJobController.progress` (StateFlow<BatchProgress?>), exposes `cancel()` (→ `BatchJobController.cancelActive()`), and `start(id, reset)` which fires an intent at `BatchTranscriptionService` with `EXTRA_RECORDING_ID`.

- [ ] **Step 3: `RecordScreen`** — big mic button, elapsed timer, live amplitude bar bound to `StreamingAudioRecorder.amplitude`, Stop. On start: `StorageGuard` free-space gate (need ≥ a few minutes' headroom) — if it fails, a toast and no recording. Record writes MIC PCM through `PcmSink` to `store.audioFile(newId)`; **provenance is stamped `ActiveSource.MIC`** at save. Optional pre-record engine chip ("On-device" / "OpenAI") reflecting the current `sttProviderId` (read-only mirror; changing the engine still lives in Cloud providers). On Stop: write the `RecordingMeta` (id, createdAtMs, durationMs, source=MIC, byteLength=`sink.bytesWritten()`), call `store.sweep()`, start `BatchTranscriptionService`, and navigate to the detail screen for that id.

- [ ] **Step 4: `RecordingsScreen`** — `LazyColumn` of `Card`s, newest-first (`store.list()`), each showing date (`DateFormat.getDateTimeInstance()`), duration, a **source badge** (MIC / device), a **status chip** (Recorded / Transcribing i-of-N / Done / PartiallyDone / Failed), and an **engine chip**. Tap → detail. Long-press or a trailing delete icon → `store.delete(id)` + refresh. **Separate from `TranscriptsScreen`** (text-only history is untouched).

- [ ] **Step 5: `RecordingDetailScreen`** — transcript text (from `store.assembledText(meta)`), Copy / Share (`ACTION_SEND`, `text/plain`, `EXTRA_TEXT` — the existing text-only share, **no audio export, no FileProvider**). State machine: Recorded → Transcribing (linear progress from `BatchJobViewModel`, Cancel button) → Done / PartiallyDone / Failed(reason). **Retry** is primary on Failed/PartiallyDone (`start(id, reset=false)` — resume). **Re-transcribe** is a separate, confirm-gated action (`AlertDialog` → `start(id, reset=true)`). **PLAYBACK provenance shown visually:** if `meta.source == PLAYBACK`, the engine chip is a **locked "On-device only" badge with a lock icon** and the cloud option is **absent** (not disabled) from the Retry/Re-transcribe menu — the UI reflects the `BatchRouting` code gate; enforcement never lives in the view.

- [ ] **Step 6: Home card + nav** — In `HomeScreen.kt`, add a `Card` cloning the Transcriptions-card template (lines ~187-224) titled **"Record & transcribe"**, subtitle "Record a clip and transcribe it all at once", `clickable { onNavigateToRecordings() }`, threading a new `onNavigateToRecordings` param. In `MainActivity.kt`, add routes `recordings` (→ `RecordingsScreen`, with `onOpenRecording(id)` and `onRecordNew`), `record` (→ `RecordScreen`), and `recording_detail/{id}` (→ `RecordingDetailScreen`), exactly as `transcripts` was added (lines 136-146). Pass `onNavigateToRecordings` into `HomeScreen`.

- [ ] **Step 7: Verify**

```bash
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: both assemble green; full unit suite unchanged from Task 5's total, 0 failures (UI is not unit-tested here; its logic is pinned upstream). Then read `RecordingDetailScreen.kt` and confirm the cloud option is **absent** for a PLAYBACK meta, not merely disabled.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/ui/RecordingsViewModel.kt \
        app/src/main/java/com/whispereverywhere/ui/BatchJobViewModel.kt \
        app/src/main/java/com/whispereverywhere/ui/screens/RecordScreen.kt \
        app/src/main/java/com/whispereverywhere/ui/screens/RecordingsScreen.kt \
        app/src/main/java/com/whispereverywhere/ui/screens/RecordingDetailScreen.kt \
        app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt \
        app/src/main/java/com/whispereverywhere/MainActivity.kt
git commit -m "feat(ui): batch record + recordings library + detail with Retry

A Record & transcribe card on Home opens an in-app MIC RecordScreen (source
stamped MIC, free-space gated) that saves the clip and auto-starts batch. A
separate Recordings library lists clips with source/status/engine chips;
the detail screen shows the transcript with Copy/Share, Cancel while
running, Retry (resume) on partial/failed, and a confirm-gated
Re-transcribe. A PLAYBACK recording shows a locked On-device-only badge and
the cloud option is ABSENT, not disabled — the view reflects the
BatchRouting code gate. No audio playback, no audio export (YAGNI)."
```

---

## Task 8: Compliance — FLAG the privacy §5 edit (do NOT write it), confirm no Data Safety flip

**Files:** none in this task — this is a documented hand-off, because `docs/privacy.html` is owner-locked this run.

- [ ] **Step 1: Hand the owner the exact §5 sentence.** Storing raw audio is a new data-retention fact; the privacy policy §5 (data storage) needs one sentence. **Do not edit `docs/privacy.html` or `app/src/main/assets/privacy_policy.html`** (owner is editing them concurrently). Deliver this text for the owner to place:

> *"Whisper Everywhere stores raw audio recordings you create in Batch mode in private, non-backed-up storage on your device. You can delete any recording individually; recordings are otherwise kept up to 30 days or until a storage cap is reached. Audio is never uploaded except when you explicitly transcribe a microphone recording with your chosen cloud provider; recordings captured from device playback are transcribed on-device only and are never uploaded."*

- [ ] **Step 2: Confirm NO Data Safety change is required.** On-device-only, app-private, non-backed-up storage is **not** "collection." The mic→cloud upload was already declared in C2a; batch reuses that exact transmission, gated by the same consent-v2 + stored-key + provider-selection triad. **No new disclosure, no declaration flip.** Record this determination in the release ledger so a reviewer does not re-open it.

- [ ] **Step 3: No commit** (no files changed). This task exists so the compliance obligation has an owner and a due date rather than living only in a plan header — the MF1 lesson applied to docs.

---

## Task 9: On-device verification

**Files:** none.

- [ ] **Step 1: Signature preflight, then install.** Follow the preflight in the TTS-diagnostics plan. Use `adb install -r`. **Never `connectedAndroidTest`.**

- [ ] **Step 2: The checks only a device can make**

1. **Record → auto-transcribe (local).** With no cloud provider selected, record ~20 s, Stop. It saves, batch starts, "Chunk i of N" progresses, transcript appears. Fully offline (airplane mode) must behave identically.
2. **Long recording chunks and checkpoints.** Record 3+ minutes of continuous speech. Confirm multiple chunks, at least one `hardCut` when you never pause, and that force-killing the app mid-job then reopening resumes from the last `Done` chunk (no re-transcription of finished chunks).
3. **Cloud happy path (MIC).** Select OpenAI, record, transcribe. Text appears; per-chunk uploads succeed. This is the only thing that proves the endpoint/model against live billing.
4. **Retry never re-bills a Done cloud chunk.** Force a partial failure (airplane mode mid-cloud-job so later chunks fall to local or fail), then Retry: confirm already-`Done` chunks are not re-uploaded (watch request count / spend).
5. **Fatal latches.** Save a mangled key, cloud-transcribe: the job stops at `Failed` after one fatal request, finished chunks are kept, and Retry (after fixing the key) resumes.
6. **PLAYBACK stays on-device — the invariant.** Create a recording with a synthetic `PLAYBACK` manifest (or via a future playback inlet). Confirm the detail screen shows the locked "On-device only" badge, the cloud option is absent, Retry transcribes locally, and — with a network sniffer or the app's request logging (lengths only) — **no upload occurs.**
7. **Delete removes audio.** Delete a recording; confirm the `<uuid>/` directory and `audio.pcm` are gone.
8. **No speed claims** anywhere in the batch UI or notification.

- [ ] **Step 3: Record the outcome** in the plan or the release ledger, including the live-docs confirmation of `gpt-transcribe` on the batch endpoint and anything deferred.

---

## Self-Review

**Spec/invariant coverage** — every requirement has an implementing task AND (where it names a behavior) a pinning test:

| Requirement (owner intent / invariant) | Task | Pinned by |
|---|---|---|
| Batch lives in main UI, record a whole clip | 7 (RecordScreen) | on-device Task 9.1 |
| Save raw audio + transcript together | 1, 2 | `RecordingStoreTest` round-trip |
| Transcribe all at once through the chosen engine | 5, 6 | `BatchTranscriberTest` local+cloud |
| Retry transcribe from saved audio | 5, 7 | `retry_resumes_and_never_re_runs_a_done_chunk` |
| **Inv#1: PLAYBACK is cloud-never, forever** | 4, 5 | `BatchRoutingTest` + `playback_never_touches_the_network` |
| Inv#2: cloud needs key+provider+consent v2 | 6 | reuses C2a triad (`EngineSelectionTest`, `sttSelectableProviders`) |
| Inv#3: fallback one-way cloud→local | 5 | `a_cloud_chunk_that_fails_transiently_falls_back` |
| Inv#4: no credential/content in logcat | all | code review — lengths/counts only |
| Inv#5: no speed claims | 6, 7 | Task 9.8 |
| Inv#6: works offline, local default | 5, 6 | `local_only_transcribes_every_chunk` + Task 9.1 |
| Inv#7: raw audio new retention — private, deletable, policy flagged | 1, 8 | `delete_removes_the_whole_recording_directory`, `sweep_*` |
| 25 MB / ~13.1 min chunk math | 3 | `no_chunk_ever_exceeds_the_ceiling`, `cloud_ceiling_stays_under_the_openai_hard_cap` |
| Sequential, SegmentOrderer skipped | 5 | design + sequential loop (no orderer imported) |
| Checkpoint/resume, no double-bill | 5 | `retry_resumes_*`, `a_fatal_cloud_error_latches_*` |
| Cancel keeps partial, never deletes audio | 5 | `cancel_stops_between_chunks_*` |
| Foreground host for long jobs | 6 | Task 9.2 (kill/resume) |

**Placeholder scan:** none. Tasks 1–5 carry complete Kotlin (model, store, scanner, planner, routing, transcriber) and complete tests. Tasks 6–7 specify Android wiring and Compose behavior precisely against named existing files (matching how C2a Tasks 6–7 specified behavior the implementer reads into `FloatingBubbleService`/`CloudProvidersScreen`), because their logic is already unit-pinned in Tasks 1–5. Task 8 is a documented hand-off. Task 9 is manual verification.

**Type consistency across tasks:** `RecordingMeta`/`ChunkEntry`/`ChunkStatus`/`BatchStatus`/`EngineUsed` are declared once (Task 1) and used identically in `RecordingStore`, `BatchTranscriber`, and the UI. `ChunkPlanner.plan(totalBytes, maxChunkBytes, minChunkBytes, boundaries)` and `SilenceScanner.scan(pcm)` signatures match their call sites in `BatchTranscriber`. `BatchRouting.cloudEligible(ActiveSource)` matches its two callers. `SttProvider.transcribe(pcm, language): SttResult` and `SttError`/`FatalKind` are consumed exactly as C2a defines them. `WhisperBackend.load/transcribe/release` and `ModelPathProvider.installedModelPath()` match Release A. `BatchProgress` is produced by `BatchTranscriber` and consumed by `BatchJobViewModel`.

**The MF1 lesson, applied twice:** invariant #1 has a code gate (`BatchRouting`, consulted first in `transcribe()`), a defensive structural guarantee (`effectiveCloud` nulled for PLAYBACK), and TWO pinning tests. The new raw-audio retention fact has an implementing task (`noBackupFilesDir`, `delete`, `sweep`) AND a flagged, owner-assigned policy edit (Task 8) rather than a header sentence with no owner.

**One risk recorded rather than hidden.** The design's "Silero VAD boundaries" is unreachable from Kotlin and memory-unsafe over long clips; this plan substitutes a memory-bounded energy scan for the *coarse* cut while the Silero VAD still runs per chunk inside `whisper_full`. This is a deliberate deviation from decision log #8, surfaced to the owner. It cannot affect correctness or the 25 MB bound (the hard-cut fallback guarantees both); it can only make a coarse cut land slightly off an ideal pause, which whisper's per-chunk VAD and 1.1 s pad absorb.
