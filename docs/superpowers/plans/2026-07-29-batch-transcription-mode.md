# Batch Transcription Mode — pick an audio file, transcribe it all at once

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **RESCOPED 2026-07-29 by owner ruling** (spec §6.6, revised the same day): *"it just needs to be a
> file picker that you can select an audio recording and push it into a transcription. That's it.
> No need to save recordings."* The original record-screen + recordings-library plan is recoverable
> at git `f020d0a`. Consequences threaded through every task below: the SAF picker is the ONLY
> inlet; there is NO in-app recording, NO saved-recordings library, NO retention policy; the
> decode workspace is transient cache, deleted when the job completes; `BatchRouting` (the
> PLAYBACK provenance gate) is CUT because batch no longer touches captured audio at all — the
> live path's `SourceRoutedTranscriptionEngine` remains the sole enforcement point for the
> device-audio promise; and a codec/decode surface (the reason import was originally cut) is IN.

**Goal:** In the app's **main UI (not the bubble)**, let the user pick an audio file (any format Android can decode), and transcribe it all at once through whichever engine they choose for that job (the on-device model, or their selected cloud provider). The transcript is saved into the existing text-only `TranscriptStore` history with Copy/Share; the decoded audio is transient cache, deleted on completion. Works fully offline with the local model; on-device stays the default.

**Architecture:** A `recording/` package holds one **transient decode workspace** per job at `cacheDir/batch/<uuid>/{audio.pcm, manifest.json}` (`RecordingStore` + `RecordingMeta`, kotlinx.serialization) — a checkpoint surface, not a library. The foreground `BatchTranscriptionService` decodes the picked file (`MediaExtractor`+`MediaCodec` → pure `Downmix`/`Resampler` → 16 kHz mono PCM16 through `PcmSink`), then a one-shot `BatchTranscriber` (NOT a `TranscriptionEngine` — connect/sendAudio/commit is a lie for a batch job) plans chunks with two pure, unit-tested objects — `SilenceScanner` (pause offsets) and `ChunkPlanner` (packs under the byte ceiling, hard-cutting continuous speech) — and transcribes **strictly sequentially**, checkpointing each finished chunk to the manifest so Retry resumes rather than restarts (a `Done` cloud chunk is never re-uploaded).

**Tech Stack:** Kotlin 2.0.21, kotlinx-serialization-json 1.7.3, JUnit 4, coroutines, Android foreground `Service`, `MediaExtractor`/`MediaCodec`/`MediaMetadataRetriever`, `StatFs`. No new third-party dependency. OkHttp stays pinned at 4.12.0 (reused via the existing `OpenAiStt`/`HttpTransport`; no direct HTTP is added here).

---

## Global Constraints

Carried over from C2a and Release A, still binding here, plus the new batch-specific ones.

- **Batch mode has NO capture path — and that is a constraint, not an accident.** It never touches the microphone or MediaProjection; its only input is a file the user explicitly picks and pushes at an explicitly chosen engine. The device-audio promise (privacy §6–§7: screen-capture audio never leaves the device) is therefore unreachable from batch BY CONSTRUCTION, and the live path's `SourceRoutedTranscriptionEngine` remains its sole enforcement point. **Do not add a capture inlet to batch** — doing so re-opens the provenance question this rescope dissolved (the original `BatchRouting` gate design is at git `f020d0a` if that ever happens). **The MF1 lesson still governs every constraint below that names a behavior: it must have a task that builds it and a test that pins it.**
- **Cloud requires ALL THREE: a stored key AND explicit provider selection AND accepted disclosure v2** — asserted through the pinned `BatchCloudGate` predicate (Task 4), never re-derived in prose. The engine used for a job is additionally shown and choosable per job at the picker result; pushing a named file at a named provider is explicit per-file consent, so the global disclosure v2's meaning is unchanged and needs no version bump.
- **The fallback valve is ONE-WAY: cloud→local only, never local→cloud.** A per-chunk cloud failure falls to the local model. A local chunk never escalates to cloud.
- **On-device is the default and must work fully offline.** With no provider selected, batch transcribes every file locally with zero configuration and zero network.
- **The decode surface is Android framework code and is NOT unit-testable.** Under `unitTests.isReturnDefaultValues = true`, `MediaExtractor`/`MediaCodec`/`MediaMetadataRetriever` are stubs returning type defaults — a JVM test against them passes vacuously (the §3.4/org.json trap, framework-wide). So the decode task splits: the sample math (`Downmix`, `Resampler`) is PURE Kotlin with real unit tests; the `MediaCodec` loop is a thin wrapper verified ON DEVICE in Task 9 against a codec zoo (mp3, m4a, wav, ogg). Never write a JVM unit test that instantiates a framework media class and believe its green.
- **No credential or transcript CONTENT in logcat — lengths only.** Never log a key, a header, a chunk's text, or the assembled transcript. Chunk hard-cuts and progress are logged as counts/lengths only.
- **No speed claims in any user-facing copy.** Status reads "Transcribing…", "Chunk 3 of 8", never "fast".
- **Cloud batch is cost-transparent and never a surprise charge (§6.5).** The engine choice at the picker result shows the per-minute price whenever a cloud engine is chosen; a job whose estimate crosses **10¢ or 10 minutes** — via the pure `BatchCostEstimator`, pre-flight from `MediaMetadataRetriever` duration, re-checked by the service on the DECODED byte length — requires an explicit confirm, carried to the service as `EXTRA_COST_CONFIRMED` so no stale intent can start a large cloud job silently. Cloud batch also requires `POST_NOTIFICATIONS` (Android 13+) so the foreground-service spend indicator is visible; denied, it falls to local. The local/offline path stays zero-friction: no price, no confirm, no permission.
- **No audio is STORED — the workspace is transient cache.** Decoded PCM + the checkpoint manifest live in `cacheDir/batch/<uuid>/`, are deleted on `Done` and on user dismissal, and are stale-swept (>24 h) at app start. Cache is non-backed-up and OS-clearable by definition, so the Auto-Backup leak the library design guarded against cannot occur. There is no retention policy because there is no retention. The privacy delta shrinks to ONE clause (Task 8): §6's "only ever receives audio you dictate yourself through the microphone" gains "or an audio file you explicitly choose to transcribe with that provider".
- **kotlinx.serialization for ALL JSON — `org.json` is BANNED.** Unit tests run with `unitTests.isReturnDefaultValues = true` (`app/build.gradle.kts:166`); `org.json` ships in `android.jar` and returns type defaults (`optString` → `""`) under that config, so broken code passes silently. The manifest is `@Serializable` data classes via `Json`. This exact trap cost tasks in C1 (`android.util.Base64`) and is called out in C2a.
- **25 MB / ~13.1 min is the OpenAI batch hard cap** (16 kHz PCM16 mono WAV = 32 KB/s). The cloud chunk **ceiling is 20 MB (~10.5 min)** — a deliberate margin below the cap, computed on the raw PCM plus the 44-byte WAV header added at dispatch. Local chunks are ~90 s (`LOCAL_CHUNK_BYTES`) to bound native memory to ~2.9 MB/chunk. **A cloud chunk that falls back to local is re-sliced to `LOCAL_CHUNK_BYTES` sub-chunks before it reaches the native model** — feeding a 20 MB cloud chunk straight to whisper would allocate a ~40 MB `FloatArray` plus a native copy plus minutes of encoder buffers in a foreground service, reintroducing the exact long-feed OOM the local ceiling exists to prevent. `gpt-transcribe` is the batch model ($0.0045/min), already `OpenAiStt.DEFAULT_MODEL`; **`gpt-live-transcribe` is realtime-only and must NEVER be used here.**
> Superseded 2026-07-31 by docs/superpowers/plans/2026-07-31-batch-all-providers.md — batch is no longer OpenAI-only: the cloud ceiling and price are per-provider (from each adapter's base64-aware maxRequestBytes), and the batch on-device path bypasses the Silero VAD.
- **Seq-exactly-once does NOT apply and `SegmentOrderer` is deliberately SKIPPED.** Batch output lands on a **screen, not an injected IME field**, so the orderer's sole job (stopping out-of-order injection from deleting text) is absent. Chunks run strictly sequentially — chunk N awaits before N+1 — so results always arrive in order and a `StringBuilder` join is provably correct. This is confirmed in the pipeline recon and unanimous across the design candidates.
- **All native `whisper_context` access stays single-threaded — enforced inside `BatchTranscriber`, not by convention.** `BatchTranscriber` owns a private single-thread dispatcher and wraps **every** native call (`load`, `transcribe`, `release`) in `withContext(nativeDispatcher)`, so even though `transcribe()` suspends on the cloud path and can resume on any thread, native code is only ever touched from that one confined thread. It loads one ctx per job, transcribes every local (sub-)chunk on that thread, and releases it in a `finally` (also confined, under `NonCancellable`). It calls `backend.transcribe` directly (bypassing `LocalWhisperEngine.sendAudio`'s 30 s self-commit) so chunk cuts stay plan-aligned. The dispatcher is created and closed by the class, so a caller cannot break the invariant by choosing a multi-threaded dispatcher — "sequential" no longer has to imply "thread-confined" by luck.
- **`java` is NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`, then `.\gradlew.bat --no-daemon`.
- **NEVER run `connectedAndroidTest` or `installDebug`** — AGP's instrumented task uninstalls the app on teardown and has twice destroyed the user's 500+ MB of models. To run instrumented: `adb install -r` both APKs, then `adb shell am instrument -w …`.
- **Do not touch** `docs/PLAY-LISTING.md` (live Console copy plus an owner-decision draft). The privacy/ToS owner-lock from the C2a fix wave is LIFTED — Task 8 edits the one §6 clause in both privacy copies and the ToS, keeping asset↔docs pairs content-identical. Do not touch Release A (`SegmentOrderer`/`SegmentOutcome`) or the cloud engines from C2a. `TranscriptStore` gains ONLY a call site (Task 7 saves the finished transcript through its existing API) — its schema and its text-only contract are untouched: the transcript IS text.
- **Commit ONLY the files each task names, by exact path. Never `git add -A`.** If `git commit` fails on `index.lock`, wait a moment and retry once.

---

## What batch mode deliberately does NOT do (scope cuts — one release)

In-app recording UI; saved-recordings library / retention / deletion UI; any capture path at all (mic or MediaProjection); audio playback / scrubbing / waveform; audio-file export / `FileProvider`; parallel chunk upload / any batch use of `CloudTranscriptionEngine`'s 3-in-flight or `SegmentOrderer`; `SegmentQuality`; cloud TTS; C4 streaming (`gpt-live-transcribe`); Gemini/ElevenLabs batch (C2b); translation; audio trimming. Resist all of them.

---

## Owner questions

**None.** The four questions in the original plan were all answered by the owner ruling of
2026-07-29: file import IS the feature (the only inlet); no playback; no library; no retention.

---

## Design decision the recon forced (recorded, not hidden)

The synthesized design specifies chunk boundaries from the **Silero VAD seam** (`we_vad_filter`, decision log #8). The pipeline recon shows that seam is **native-internal to `whisper_full` and has no JNI export returning boundaries** — and, decisively, running VAD over a whole 30-minute recording to *find* boundaries would require the entire clip as a `FloatArray` + a native copy (the ~38 MB × N OOM that chunking exists to avoid). Using VAD for coarse cut-planning is therefore both unreachable from Kotlin and self-defeating on memory.

**Resolution (plan author's call):** coarse cut-planning uses a **streaming Kotlin energy scan** (`SilenceScanner`, built on the existing pure `AudioMath`) — memory-bounded, JVM-unit-testable, no native surgery. The Silero VAD **still runs unchanged inside `whisper_full` per chunk at transcription time**, so speech is still VAD-trimmed before the encoder; we simply do not use VAD for the coarse cut. Transcription quality is unaffected; correctness and memory bounds are guaranteed by the hard-cut fallback. This deviates from decision log #8 and is reported to the owner. (This is the same class of gap the design itself warns about — a declared mechanism with no reachable implementation — caught and closed here rather than shipped.)

---

## File Structure

| File | Action |
|---|---|
| `app/src/main/java/com/whispereverywhere/recording/RecordingMeta.kt` | Create — `@Serializable` model, `BatchStatus`, `ChunkStatus`, `ChunkEntry`, `EngineUsed` |
| `app/src/main/java/com/whispereverywhere/recording/RecordingStore.kt` | Create — the TRANSIENT workspace: save/read/delete/sweepStale over `cacheDir/batch` |
| `app/src/test/java/com/whispereverywhere/recording/RecordingStoreTest.kt` | Create |
| `app/src/main/java/com/whispereverywhere/recording/PcmSink.kt` | Create — PCM→disk + free-space gate |
| `app/src/test/java/com/whispereverywhere/recording/StorageGuardTest.kt` | Create |
| `app/src/main/java/com/whispereverywhere/recording/AudioDecoder.kt` | Create — `MediaExtractor`+`MediaCodec` → `Downmix` → `Resampler` → `PcmSink` (device-verified) |
| `app/src/main/java/com/whispereverywhere/recording/SampleMath.kt` | Create — pure `Downmix` + `Resampler` (JVM-tested) |
| `app/src/test/java/com/whispereverywhere/recording/SampleMathTest.kt` | Create — the resample/downmix math |
| `app/src/main/java/com/whispereverywhere/transcription/batch/ChunkPlanner.kt` | Create — pure `SilenceScanner` + `ChunkPlanner` |
| `app/src/test/java/com/whispereverywhere/transcription/batch/ChunkPlannerTest.kt` | Create — the boundary math, all edge cases |
| `app/src/main/java/com/whispereverywhere/transcription/batch/BatchCloudGate.kt` | Create — invariant (key+provider+consent) as a pure predicate |
| `app/src/test/java/com/whispereverywhere/transcription/batch/BatchCloudGateTest.kt` | Create — pinning the triad |
| `app/src/main/java/com/whispereverywhere/transcription/batch/BatchCostEstimator.kt` | Create — §6.5 spend estimate + confirm threshold (pure) |
| `app/src/test/java/com/whispereverywhere/transcription/batch/BatchCostEstimatorTest.kt` | Create — pinning the estimate/threshold math |
| `app/src/main/java/com/whispereverywhere/transcription/batch/BatchTranscriber.kt` | Create — sequential, checkpoint/resume/cancel, per-chunk fallback |
| `app/src/test/java/com/whispereverywhere/transcription/batch/BatchTranscriberTest.kt` | Create — resume, cancel, fallback, re-slice pinning |
| `app/src/main/java/com/whispereverywhere/service/BatchTranscriptionService.kt` | Create — foreground host: decode phase + transcribe phase |
| `app/src/main/AndroidManifest.xml` | Modify — declare the foreground service |
| `app/src/main/java/com/whispereverywhere/ui/BatchJobViewModel.kt` | Create |
| `app/src/main/java/com/whispereverywhere/ui/screens/BatchTranscribeScreen.kt` | Create — picker result, engine choice, progress, transcript |
| `app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt` | Modify — "Transcribe audio file" card |
| `app/src/main/java/com/whispereverywhere/MainActivity.kt` | Modify — `batch_transcribe` route + SAF picker launcher |
| `app/src/main/assets/privacy_policy.html` + `docs/privacy.html` §6 | Modify (Task 8) — ONE clause; keep the pair content-identical |
| `app/src/main/assets/terms_of_service.html` + `docs/terms.html` §3 | Modify (Task 8) — mirror the same clause; keep the pair content-identical |

**CUT by the rescope (do not create):** `BatchRouting.kt`/`BatchRoutingTest.kt` (no capture path in batch — see Global Constraints), `RecordScreen.kt`, `RecordingsScreen.kt`, `RecordingDetailScreen.kt`, `RecordingsViewModel.kt`.

**Test-count note:** the running suite total is whatever the current tree reports (C2a is in final fix-up, so it is above the C2a plan's 233). Each task below states the number of NEW tests it adds; the implementer confirms `= previous total + N, 0 failures` after each.

---

## Task 1: `RecordingMeta` + `RecordingStore` — the TRANSIENT decode workspace

> **RESCOPE NOTE:** this is a checkpoint surface for one in-flight job, NOT a library. No listing
> UI ever shows it, nothing is retained after `Done`, and the only sweep is a stale-workspace
> collector for jobs orphaned by a crash.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/recording/RecordingMeta.kt`
- Create: `app/src/main/java/com/whispereverywhere/recording/RecordingStore.kt`
- Test: `app/src/test/java/com/whispereverywhere/recording/RecordingStoreTest.kt`

**Interfaces:**
- Consumes: kotlinx.serialization. (NOT `ActiveSource` — batch has no capture path; provenance died with the rescope.)
- Produces, used by Tasks 2/5/6/7:
  - `enum class BatchStatus { Recorded, Transcribing, PartiallyDone, Done, Failed }` (`Recorded` = decoded-and-ready; kept name to avoid churn)
  - `enum class ChunkStatus { Pending, Done, Failed }`
  - `enum class EngineUsed { LOCAL, OPENAI }`
  - `@Serializable data class ChunkEntry(index, startByte, endByte, hardCut, status = Pending, text = "")`
  - `@Serializable data class RecordingMeta(id, createdAtMs, durationMs, displayName, sampleRate = 16000, channels = 1, byteLength, status = Recorded, engineUsed = null, modelId = null, language = null, chunkPlan = emptyList())` — `displayName` is the picked file's user-visible name (from the Uri's `OpenableColumns.DISPLAY_NAME`), for the progress screen; there is no `source` field.
  - `class RecordingStore(root: File, clock = System::currentTimeMillis)` with `dir(id)`, `audioFile(id)`, `save(meta)`, `read(id): RecordingMeta?`, `list(): List<RecordingMeta>` (used only by `sweepStale`), `delete(id)`, `sweepStale(maxAgeMs = STALE_MS)`, `assembledText(meta): String`.
  - `RecordingStore.forApp(context, clock = …): RecordingStore` — the **ONLY** production constructor path, hard-coding **`cacheDir/batch`**: cache is non-backed-up and OS-clearable, which is exactly right for a transient workspace. The raw `root: File` ctor is unit-tests only.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/recording/RecordingStoreTest.kt`:

```kotlin
package com.whispereverywhere.recording

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
        RecordingStore(File(tmp.root, "batch"), clock = { now })

    private fun meta(id: String, createdAtMs: Long) =
        RecordingMeta(
            id = id,
            createdAtMs = createdAtMs,
            durationMs = 3_000L,
            displayName = "clip.m4a",
            byteLength = 96_000,
        )

    @Test fun save_then_read_round_trips_every_field() {
        val s = store()
        val m = meta("a", 500L).copy(
            status = BatchStatus.Done,
            engineUsed = EngineUsed.LOCAL,
            language = "en",
            chunkPlan = listOf(ChunkEntry(0, 0, 96_000, hardCut = false, ChunkStatus.Done, "hello")),
        )
        s.save(m)
        assertEquals(m, s.read("a"))
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

    @Test fun delete_removes_the_whole_workspace_directory() {
        val s = store()
        s.save(meta("d", 1L))
        s.audioFile("d").writeBytes(ByteArray(10))
        assertTrue(s.dir("d").exists())
        s.delete("d")
        assertFalse("cleanup must remove audio.pcm too — no audio outlives its job", s.dir("d").exists())
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

    @Test fun sweepStale_collects_workspaces_orphaned_by_a_crash() {
        // The ONLY sweep. A workspace older than STALE_MS belongs to a job whose process died
        // without cleanup (normal completion deletes eagerly); a young one may be a live job.
        val now = 3L * 24 * 60 * 60 * 1000
        val s = RecordingStore(File(tmp.root, "b"), clock = { now })
        s.save(meta("orphan", now - RecordingStore.STALE_MS - 1))
        s.save(meta("live", now - 60_000L))
        s.sweepStale()
        assertEquals(listOf("live"), s.list().map { it.id })
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

import kotlinx.serialization.Serializable

/** Lifecycle of one batch job. [Recorded] = decoded PCM is on disk and ready to chunk. */
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
 * The manifest.json for one job's workspace. kotlinx.serialization, NOT org.json — org.json
 * ships in android.jar and returns type defaults under this project's unitTests.returnDefaultValues
 * config, so a manifest parsed with it would silently come back blank.
 *
 * There is deliberately NO capture-source field: batch transcribes user-picked FILES, never
 * captured audio, so provenance does not exist here. [displayName] is the picked file's
 * user-visible name (OpenableColumns.DISPLAY_NAME), for the progress screen. Enums serialize by
 * constant NAME (never the ordinal) — the same rule ProviderId relies on.
 */
@Serializable
data class RecordingMeta(
    val id: String,
    val createdAtMs: Long,
    val durationMs: Long,
    val displayName: String,
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

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The TRANSIENT decode workspace: one directory per in-flight batch job under [root], each holding
 * audio.pcm (decoded 16 kHz mono PCM16LE) and manifest.json (a [RecordingMeta]).
 *
 * NOT a library. Nothing here outlives its job: the service deletes the workspace on Done and on
 * user dismissal, and [sweepStale] collects anything orphaned by a crash. In production [root] is
 * ONLY ever cacheDir/batch via the [forApp] factory — cache is non-backed-up and OS-clearable,
 * which is exactly the contract a transient workspace wants. The raw [root] ctor exists for unit
 * tests.
 *
 * Separate from TranscriptStore on purpose: that store is TEXT-ONLY and keeps its "audio never
 * retained" contract — the finished transcript is saved THERE (Task 7), while this directory and
 * its audio are deleted.
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

    /** Recursively removes the whole <uuid>/ directory — audio.pcm included. */
    fun delete(id: String) { File(root, id).deleteRecursively() }

    /**
     * The transcript so far: Done chunks, in index order, joined with ONE space.
     *
     * The join is " " and never "" because chunk text arrives TRIMMED — TranscriptText.clean
     * collapses whitespace runs and .trim()s the result (TranscriptText.kt:27-31), so a ""
     * join would glue chunk N's last word to chunk N+1's first word ("…meeting toMorrow…").
     * Blank chunks (silence) are dropped so they cannot double the spacing.
     */
    fun assembledText(meta: RecordingMeta): String =
        meta.chunkPlan.sortedBy { it.index }
            .filter { it.status == ChunkStatus.Done && it.text.isNotBlank() }
            .joinToString(" ") { it.text.trim() }

    /**
     * The only sweep: collect workspaces orphaned by a crash. Normal completion deletes eagerly;
     * anything older than [STALE_MS] belongs to a process that died mid-job.
     */
    fun sweepStale(maxAgeMs: Long = STALE_MS) {
        val now = clock()
        list().forEach { m -> if (now - m.createdAtMs > maxAgeMs) delete(m.id) }
    }

    companion object {
        /** The subdirectory name under cacheDir. Centralized so it can't drift. */
        const val DIR_NAME = "batch"

        /** A workspace this old was orphaned by a crash — no live job runs for a day. */
        const val STALE_MS: Long = 24L * 60 * 60 * 1000

        /**
         * The ONLY production entry point. Hard-codes cacheDir/batch — non-backed-up and
         * OS-clearable, the right contract for a transient workspace. EVERY caller — the service
         * and the ViewModel — uses this; passing a bare File is a unit-test-only affordance.
         */
        fun forApp(context: Context, clock: () -> Long = System::currentTimeMillis): RecordingStore =
            RecordingStore(File(context.cacheDir, DIR_NAME), clock)
    }
}
```

> **Centralization is still the pin for the storage boundary.** Because `forApp` is the single
> production constructor and hard-codes `cacheDir`, decoded voice audio can never drift into
> `filesDir` (which Auto Backup can upload). A JVM test cannot assert an Android path, so the
> code-review checklist for this plan MUST include: "every production `RecordingStore` is built via
> `forApp`; no call site constructs it with a bare `File`."

- [ ] **Step 5: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.recording.RecordingStoreTest"
```
Expected: PASS, 7 tests. Full suite: previous total **+7**, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/recording/RecordingMeta.kt \
        app/src/main/java/com/whispereverywhere/recording/RecordingStore.kt \
        app/src/test/java/com/whispereverywhere/recording/RecordingStoreTest.kt
git commit -m "feat(recording): RecordingStore — the transient batch decode workspace

One directory per in-flight job under cacheDir/batch/<uuid>/ — cache is
non-backed-up and OS-clearable, the right contract for audio that must not
outlive its job. The manifest is kotlinx.serialization, not org.json, which
returns type defaults under this project's unit-test config.

Not a library: no retention policy, no listing UI. delete() removes the
whole directory, audio included; sweepStale() only collects workspaces
orphaned by a crash. No capture-source field — batch transcribes files the
user picks, never captured audio."
```

---

## Task 2: The decode pipeline — `SampleMath` (pure) + `PcmSink` + `StorageGuard` + `AudioDecoder`

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/recording/SampleMath.kt` — pure `Downmix` + `Resampler`
- Test: `app/src/test/java/com/whispereverywhere/recording/SampleMathTest.kt`
- Create: `app/src/main/java/com/whispereverywhere/recording/PcmSink.kt` — also holds `StorageGuard`
- Test: `app/src/test/java/com/whispereverywhere/recording/StorageGuardTest.kt`
- Create: `app/src/main/java/com/whispereverywhere/recording/AudioDecoder.kt` — the `MediaCodec` wrapper

**Interfaces:**
- Consumes: `android.media.MediaExtractor/MediaCodec/MediaFormat` (decoder only), `java.io`.
- Produces, used by Tasks 5/6:
  - `object Downmix { fun toMono(input: ShortArray, channels: Int): ShortArray }`
  - `object Resampler { fun to16k(input: ShortArray, srcRate: Int): ShortArray }`
  - `class PcmSink(file: File)` with `append(pcm: ByteArray, len: Int)`, `bytesWritten(): Long`, `close()`.
  - `object StorageGuard { fun enoughSpace(availableBytes: Long, requiredBytes: Long): Boolean; fun availableBytesAt(path: File): Long }`.
  - `class AudioDecoder { fun decodeTo(context: Context, uri: Uri, sink: PcmSink, onProgress: (Float) -> Unit): DecodeResult }` where `sealed interface DecodeResult { data class Ok(val byteLength: Long, val durationMs: Long) : DecodeResult; data class Unsupported(val reason: String) : DecodeResult }`.

**Why this split — read before writing any test:** the sample MATH (stereo→mono downmix,
arbitrary-rate→16 kHz resample) is pure Kotlin and fully JVM-unit-tested here. The `MediaCodec`
decode loop is Android framework code, and under `unitTests.isReturnDefaultValues = true` every
framework media class is a stub returning type defaults — a JVM test against `AudioDecoder` would
pass vacuously no matter how broken it is (the org.json trap, framework-wide). So `AudioDecoder`
gets NO JVM unit test on purpose; it is compile-checked here and verified on-device in Task 9
against a codec zoo. `StorageGuard` gates the decode: estimated decoded size =
`durationMs × 32 bytes/ms` (16 kHz × 2 B mono) from `MediaMetadataRetriever`, checked before the
first sink write so a full disk fails cleanly instead of truncating mid-decode.

Resampling is linear interpolation — adequate for speech into whisper (which VAD-trims and
mel-bins anyway), dependency-free, and O(n). 48 kHz and 44.1 kHz (the two rates that exist in
practice) both land within a fraction of a semitone of exact; this is not a hi-fi path.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/whispereverywhere/recording/SampleMathTest.kt`:

```kotlin
package com.whispereverywhere.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SampleMathTest {

    // ---------------- Downmix ----------------

    @Test fun mono_input_passes_through_untouched() {
        val mono = shortArrayOf(1, -2, 3, -4)
        assertArrayEquals(mono, Downmix.toMono(mono, channels = 1))
    }

    @Test fun stereo_downmix_averages_the_pair() {
        // L,R interleaved: (100,200) -> 150; (-100,100) -> 0; (5,6) -> 5 (integer floor is fine).
        val stereo = shortArrayOf(100, 200, -100, 100, 5, 6)
        assertArrayEquals(shortArrayOf(150, 0, 5), Downmix.toMono(stereo, channels = 2))
    }

    @Test fun downmix_of_full_scale_stereo_does_not_overflow() {
        // Short.MAX + Short.MAX averaged in Int space must come back as Short.MAX, not wrap.
        val loud = shortArrayOf(Short.MAX_VALUE, Short.MAX_VALUE, Short.MIN_VALUE, Short.MIN_VALUE)
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE), Downmix.toMono(loud, 2))
    }

    // ---------------- Resampler ----------------

    @Test fun sixteen_k_input_is_the_identity() {
        val pcm = shortArrayOf(10, 20, 30)
        assertArrayEquals(pcm, Resampler.to16k(pcm, srcRate = 16_000))
    }

    @Test fun output_length_matches_the_rate_ratio() {
        // 48 kHz -> 16 kHz is exactly 3:1.
        val out = Resampler.to16k(ShortArray(48_000), srcRate = 48_000)
        assertEquals(16_000, out.size)
        // 44.1 kHz -> 16 kHz: 44_100 / 2.75625; one second in -> one second out (±1 sample).
        val out441 = Resampler.to16k(ShortArray(44_100), srcRate = 44_100)
        assertTrue("expected ~16000, got ${out441.size}", abs(out441.size - 16_000) <= 1)
    }

    @Test fun a_constant_signal_stays_constant_through_resampling() {
        // Linear interpolation between equal values is that value — any deviation is a math bug.
        val dc = ShortArray(44_100) { 1000 }
        Resampler.to16k(dc, 44_100).forEach { assertEquals(1000, it.toInt()) }
    }

    @Test fun a_linear_ramp_resamples_onto_the_same_line() {
        // Values lie on y = x (in source-sample units); after resampling, sample k of the output
        // must sit at y ≈ k * (srcRate/16000), because linear interpolation reproduces lines exactly.
        val ramp = ShortArray(4_800) { it.toShort() }
        val out = Resampler.to16k(ramp, srcRate = 48_000)
        for (k in out.indices) {
            val expected = k * 3.0
            assertTrue("sample $k: ${out[k]} !~ $expected", abs(out[k] - expected) <= 1.0)
        }
    }
}
```

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
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.recording.SampleMathTest"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.recording.StorageGuardTest"
```
Expected: FAIL — `Unresolved reference: Downmix` / `StorageGuard`.

- [ ] **Step 3a: Implement `SampleMath.kt`**

```kotlin
package com.whispereverywhere.recording

/**
 * Interleaved multi-channel PCM16 -> mono, by per-frame average. Averaged in Int space so
 * full-scale inputs cannot overflow Short arithmetic.
 */
object Downmix {
    fun toMono(input: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return input
        val frames = input.size / channels
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += input[f * channels + c].toInt()
            out[f] = (acc / channels).toShort()
        }
        return out
    }
}

/**
 * Mono PCM16 at any source rate -> 16 kHz, by linear interpolation.
 *
 * Speech-adequate on purpose: whisper VAD-trims and mel-bins its input, so a windowed-sinc
 * resampler buys nothing audible here and costs a dependency. Linear interpolation reproduces
 * lines exactly (pinned by test) and is O(n).
 */
object Resampler {
    const val TARGET_RATE = 16_000

    fun to16k(input: ShortArray, srcRate: Int): ShortArray {
        require(srcRate > 0) { "srcRate must be positive" }
        if (srcRate == TARGET_RATE || input.isEmpty()) return input
        val outLen = ((input.size.toLong() * TARGET_RATE) / srcRate).toInt()
        val out = ShortArray(outLen)
        val step = srcRate.toDouble() / TARGET_RATE
        for (k in 0 until outLen) {
            val pos = k * step
            val i = pos.toInt()
            val frac = pos - i
            val a = input[i].toInt()
            val b = input[minOf(i + 1, input.size - 1)].toInt()
            out[k] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }
}
```

- [ ] **Step 3b: Implement `PcmSink.kt`** (contains both `PcmSink` and `StorageGuard`)

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

    /** Writes exactly [len] bytes of [pcm]. The decoder's buffers vary per codec frame. */
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

- [ ] **Step 3c: Implement `AudioDecoder.kt`** — NO JVM unit test, on purpose (framework stubs; see the task's Why). Compile-checked here, device-verified in Task 9.

```kotlin
package com.whispereverywhere.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes ANY audio Android can demux (mp3, m4a/aac, wav, ogg/opus, flac...) from a SAF Uri to
 * 16 kHz mono PCM16 streamed through [PcmSink]. Synchronous; the caller (BatchTranscriptionService)
 * runs it off-main and reports [onProgress] from extractor position / duration.
 *
 * Framework code end to end, so there is deliberately NO JVM unit test — under
 * unitTests.isReturnDefaultValues these classes are stubs and any test would pass vacuously. The
 * sample MATH is delegated to [Downmix]/[Resampler], which ARE unit-tested. Verified on-device
 * (Task 9) against a codec zoo.
 */
class AudioDecoder {

    sealed interface DecodeResult {
        data class Ok(val byteLength: Long, val durationMs: Long) : DecodeResult
        data class Unsupported(val reason: String) : DecodeResult
    }

    fun decodeTo(context: Context, uri: Uri, sink: PcmSink, onProgress: (Float) -> Unit): DecodeResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return DecodeResult.Unsupported("no audio track")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else 0L
            extractor.selectTrack(track)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            // Output format is authoritative AFTER the first INFO_OUTPUT_FORMAT_CHANGED — the
            // track format's rate/channels can be wrong for some containers.
            var outRate = format.getInt(MediaFormat.KEY_SAMPLE_RATE)
            var outChannels = format.getInt(MediaFormat.KEY_CHANNEL_COUNT)

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress(extractor.sampleTime / durationUs.toFloat())
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        outRate = f.getInt(MediaFormat.KEY_SAMPLE_RATE)
                        outChannels = f.getInt(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIdx >= 0) {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            val shorts = ShortArray(info.size / 2)
                            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                            val mono16k = Resampler.to16k(Downmix.toMono(shorts, outChannels), outRate)
                            val bytes = ByteArray(mono16k.size * 2)
                            for (i in mono16k.indices) {
                                bytes[i * 2] = (mono16k[i].toInt() and 0xFF).toByte()
                                bytes[i * 2 + 1] = (mono16k[i].toInt() shr 8).toByte()
                            }
                            sink.append(bytes, bytes.size)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                }
            }
            return DecodeResult.Ok(sink.bytesWritten(), durationUs / 1_000)
        } catch (t: Throwable) {
            // Corrupt file, unsupported codec, revoked Uri grant — one honest failure, no partials
            // presented as success. Message is generic; never log the Uri (it can embed a filename).
            return DecodeResult.Unsupported(t.javaClass.simpleName)
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
```

> **Known simplification, accepted:** per-buffer resampling interpolates within each codec output
> buffer independently (no carry of the last sample across buffers). At worst this shifts ONE
> sample per buffer boundary — inaudible and irrelevant to STT — and it keeps the loop stateless.
> Do not "fix" this with a stateful resampler unless Task 9's device run shows real artifacts.

- [ ] **Step 4: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.recording.SampleMathTest"
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.recording.StorageGuardTest"
.\gradlew.bat :app:assembleDebug --no-daemon
```
Expected: PASS — SampleMath 7, StorageGuard 3; debug green. Full suite: previous **+10**, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/recording/SampleMath.kt \
        app/src/test/java/com/whispereverywhere/recording/SampleMathTest.kt \
        app/src/main/java/com/whispereverywhere/recording/PcmSink.kt \
        app/src/test/java/com/whispereverywhere/recording/StorageGuardTest.kt \
        app/src/main/java/com/whispereverywhere/recording/AudioDecoder.kt
git commit -m "feat(recording): the decode pipeline — SampleMath, PcmSink, StorageGuard, AudioDecoder

MediaExtractor+MediaCodec decode any Android-supported audio file to 16 kHz
mono PCM16 streamed to the cache workspace. The sample math (downmix in Int
space, linear-interpolation resample) is pure Kotlin and unit-tested; the
MediaCodec loop deliberately has NO JVM test — framework media classes are
stubs under returnDefaultValues and any test would pass vacuously — and is
device-verified against a codec zoo instead. StorageGuard gates the decode
on estimated size (32 bytes/ms) with 10% headroom."
```

---

## Task 3: `SilenceScanner` + `ChunkPlanner` — the boundary math (riskiest pure logic)

> The planner is fed the DECODED 16 kHz mono PCM16 from Task 2 — by the time audio reaches here,
> its original container/rate/channel-count no longer exist. Nothing in this task changes with the
> input format, which is exactly why the decode pipeline normalizes first.

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
Expected: PASS, 15 tests. Full suite: previous **+15**, 0 failures.

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

## Task 4: The pure batch gates — `BatchCloudGate` (consent triad) + `BatchCostEstimator` (§6.5)

Two pure, Android-free, JUnit-testable policy objects in `transcription/batch/`, each giving one
constraint a code gate AND a pinning test — the MF1 lesson applied rather than left as header prose.

> **`BatchRouting` was CUT by the 2026-07-29 rescope, deliberately.** It was the PLAYBACK
> provenance gate for a recordings library. Import-only batch has NO capture path — no microphone,
> no MediaProjection — so there is no capture source to route on, and a provenance gate here would
> be dead code implying a hazard that cannot occur. The device-audio promise remains enforced,
> solely and sufficiently, by the live path's `SourceRoutedTranscriptionEngine` (audit-verified in
> the C2a fix wave). If batch EVER gains a capture inlet, resurrect the gate from git `f020d0a`.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/batch/BatchCloudGate.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/batch/BatchCloudGateTest.kt`
- Create: `app/src/main/java/com/whispereverywhere/transcription/batch/BatchCostEstimator.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/batch/BatchCostEstimatorTest.kt`

**Interfaces:**
- Consumes: nothing (pure).
- Produces, used by Tasks 5/6/7:
  - `object BatchCloudGate { fun cloudEligible(providerId: String?, key: String?, disclosureAccepted: Boolean): Boolean }` — the C2a consent triad as one predicate.
  - `object BatchCostEstimator { fun minutes(byteLength): Double; fun estimatedCents(byteLength): Double; fun needsConfirmation(byteLength): Boolean; fun bytesForDuration(durationMs: Long): Long }` — §6.5 spend estimate + confirm threshold; `bytesForDuration` lets the UI pre-flight from `MediaMetadataRetriever` duration before any decode exists.

**Why pure predicates:** the consent triad (stored key AND provider selection AND accepted
disclosure v2) must not live as prose in the service's provider-resolution step — prose is how MF1
happened. The cost math likewise becomes a pinned object the UI and service BOTH call, so the
"never a surprise charge" rule has one mechanism, not two copies.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/whispereverywhere/transcription/batch/BatchCloudGateTest.kt`:

```kotlin
package com.whispereverywhere.transcription.batch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Invariant #2 pinned: cloud requires ALL THREE of a selected provider, a stored key, and accepted
 * disclosure v2. The service consults this ONE predicate instead of re-deriving the triad in prose —
 * FloatingBubbleService.resolveTranscriptionEngine is private and does NOT itself read the
 * disclosure flag (that gating lives upstream in provider setup), so batch needs its own gate.
 */
class BatchCloudGateTest {

    @Test fun the_full_triad_is_eligible() {
        assertTrue(BatchCloudGate.cloudEligible("OPENAI", "sk-test", disclosureAccepted = true))
    }

    @Test fun no_selected_provider_is_never_eligible() {
        assertFalse(BatchCloudGate.cloudEligible(null, "sk-test", disclosureAccepted = true))
    }

    @Test fun no_stored_key_is_never_eligible() {
        assertFalse(BatchCloudGate.cloudEligible("OPENAI", null, disclosureAccepted = true))
    }

    @Test fun a_blank_key_is_never_eligible() {
        // decideEngineChoice treats a blank key as "no key"; the batch gate must agree.
        assertFalse(BatchCloudGate.cloudEligible("OPENAI", "   ", disclosureAccepted = true))
    }

    @Test fun without_disclosure_v2_is_never_eligible() {
        // The upgrade case: key stored under C1's future-tense v1 consent, v2 never accepted.
        assertFalse(BatchCloudGate.cloudEligible("OPENAI", "sk-test", disclosureAccepted = false))
    }

    @Test fun the_default_state_is_ineligible() {
        assertFalse(BatchCloudGate.cloudEligible(null, null, disclosureAccepted = false))
    }
}
```

Create `app/src/test/java/com/whispereverywhere/transcription/batch/BatchCostEstimatorTest.kt`:

```kotlin
package com.whispereverywhere.transcription.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.5's "never a surprise charge" as pinned math. Estimates derive from the recording's
 * byteLength at the PCM16/16 kHz rate (32,000 bytes/s) and the published gpt-transcribe batch
 * price ($0.0045/min) — an estimate shown to the user, never a promise.
 */
class BatchCostEstimatorTest {

    private fun bytesForMinutes(min: Double): Long =
        (min * 60 * BatchCostEstimator.BYTES_PER_SECOND).toLong()

    @Test fun minutes_math_matches_the_pcm_rate() {
        assertEquals(1.0, BatchCostEstimator.minutes(bytesForMinutes(1.0)), 1e-9)
    }

    @Test fun estimated_cents_uses_the_published_batch_price() {
        // 10 minutes at $0.0045/min = 4.5 cents.
        assertEquals(4.5, BatchCostEstimator.estimatedCents(bytesForMinutes(10.0)), 1e-6)
    }

    @Test fun a_five_minute_clip_needs_no_confirmation() {
        assertFalse(BatchCostEstimator.needsConfirmation(bytesForMinutes(5.0)))
    }

    @Test fun a_ten_minute_clip_needs_confirmation() {
        // The minutes threshold binds first with today's price (10¢ ≈ 22 min); both are OR-ed so a
        // future price rise cannot silently widen the unconfirmed window.
        assertTrue(BatchCostEstimator.needsConfirmation(bytesForMinutes(10.0)))
    }

    @Test fun zero_bytes_is_free_and_unconfirmed() {
        assertEquals(0.0, BatchCostEstimator.estimatedCents(0L), 0.0)
        assertFalse(BatchCostEstimator.needsConfirmation(0L))
    }

    @Test fun duration_preflight_agrees_with_the_byte_math() {
        // The UI estimates from MediaMetadataRetriever duration BEFORE any decode exists; the
        // service re-checks on decoded bytes. Both must land on the same answer for clean audio.
        val tenMinutesMs = 10L * 60 * 1000
        assertEquals(bytesForMinutes(10.0), BatchCostEstimator.bytesForDuration(tenMinutesMs))
        assertTrue(BatchCostEstimator.needsConfirmation(BatchCostEstimator.bytesForDuration(tenMinutesMs)))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.batch.*"
```
Expected: FAIL — `Unresolved reference: BatchCloudGate` (and `BatchCostEstimator`).

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/whispereverywhere/transcription/batch/BatchCloudGate.kt`:

```kotlin
package com.whispereverywhere.transcription.batch

/**
 * Invariant #2 written as code: batch cloud transcription requires ALL of a selected provider, a
 * stored key, and accepted disclosure v2 — the same triad live dictation enforces.
 *
 * This exists because the live path's enforcement is not reusable here:
 * FloatingBubbleService.resolveTranscriptionEngine is a private member returning a fully-wired
 * engine, and it does NOT itself read cloudDisclosureAccepted (that gating lives upstream, in
 * provider setup, where a key cannot be stored and a provider cannot be selected without
 * acceptance). Batch constructs its own provider, so it re-asserts the whole triad in one pinned
 * predicate rather than trusting the upstream implication from a different screen's flow.
 *
 * Deliberately the ONLY gate class here: batch has no capture path, so there is no capture-source
 * dimension to gate on. Everything this predicate does not cover (cost confirm, notifications) is
 * a separate explicit check in the service, never an implication.
 */
object BatchCloudGate {
    fun cloudEligible(providerId: String?, key: String?, disclosureAccepted: Boolean): Boolean =
        providerId != null && !key.isNullOrBlank() && disclosureAccepted
}
```

Create `app/src/main/java/com/whispereverywhere/transcription/batch/BatchCostEstimator.kt`:

```kotlin
package com.whispereverywhere.transcription.batch

/**
 * §6.5's "cloud is never a surprise charge" as math the UI and the service both call.
 *
 * Estimates derive from the recording's byteLength (retained in the manifest) at the PCM16/16 kHz
 * byte rate, priced at gpt-transcribe's published batch rate. They are ESTIMATES shown to the
 * user, never a promise — copy must say "about".
 *
 * needsConfirmation is an OR of a cents threshold and a minutes threshold: with today's price the
 * minutes bound binds first (10¢ ≈ 22 min), but OR-ing both means a future price change cannot
 * silently widen the unconfirmed window.
 */
object BatchCostEstimator {
    /** 16 kHz × 2 bytes, mono PCM16. */
    const val BYTES_PER_SECOND = 32_000

    /** gpt-transcribe batch: $0.0045/min (verified against live docs 2026-07-29). */
    const val CENTS_PER_MINUTE = 0.45

    const val CONFIRM_CENTS = 10.0
    const val CONFIRM_MINUTES = 10.0

    fun minutes(byteLength: Long): Double = byteLength / (BYTES_PER_SECOND * 60.0)

    fun estimatedCents(byteLength: Long): Double = minutes(byteLength) * CENTS_PER_MINUTE

    fun needsConfirmation(byteLength: Long): Boolean =
        estimatedCents(byteLength) >= CONFIRM_CENTS || minutes(byteLength) >= CONFIRM_MINUTES

    /**
     * Pre-flight bridge: the UI knows only MediaMetadataRetriever's duration before any decode
     * exists. Decoded PCM16 at 16 kHz mono is exactly 32 bytes per millisecond.
     */
    fun bytesForDuration(durationMs: Long): Long = durationMs * (BYTES_PER_SECOND / 1000L)
}
```

- [ ] **Step 4: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.batch.*"
```
Expected: PASS, 12 tests (BatchCloudGate 6, BatchCostEstimator 6). Full suite: previous **+12**, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/batch/BatchCloudGate.kt \
        app/src/test/java/com/whispereverywhere/transcription/batch/BatchCloudGateTest.kt \
        app/src/main/java/com/whispereverywhere/transcription/batch/BatchCostEstimator.kt \
        app/src/test/java/com/whispereverywhere/transcription/batch/BatchCostEstimatorTest.kt
git commit -m "feat(batch): the pure batch gates — consent triad and cost

The consent triad (provider+key+disclosure-v2) and the spend estimate, each
as one pure function with its own pinning test rather than prose in a
header. No provenance gate: import-only batch has no capture path, so the
device-audio promise stays enforced solely by the live path's
SourceRoutedTranscriptionEngine."
```

---

## Task 5: `BatchTranscriber` — sequential, checkpointed, resumable, one-way fallback

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/transcription/batch/BatchTranscriber.kt`
- Test: `app/src/test/java/com/whispereverywhere/transcription/batch/BatchTranscriberTest.kt`

**Interfaces:**
- Consumes: `RecordingStore`, `RecordingMeta`/`ChunkEntry`/`ChunkStatus`/`BatchStatus`/`EngineUsed` (Task 1), `SilenceScanner`/`ChunkPlanner` (Task 3), `SttProvider`/`SttResult`/`SttError`/`FatalKind` (C2a), `WhisperBackend`/`ModelPathProvider` (Release A seams), `AudioMath`, `TranscriptText.clean`.
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

    private fun storeWith(pcmBytes: Int): Pair<RecordingStore, String> {
        val s = RecordingStore(File(tmp.root, "batch"))
        val id = "clip1"
        s.audioFile(id).writeBytes(ByteArray(pcmBytes) { (it % 251).toByte() })
        s.save(RecordingMeta(id = id, createdAtMs = 1L, durationMs = 3000L, displayName = "clip.m4a", byteLength = pcmBytes))
        return s to id
    }

    // Small ceilings so tests make several chunks without megabytes of PCM. Injected via a subclass
    // of the planner call in BatchTranscriber (the transcriber exposes chunk-ceiling overrides for tests).

    @Test fun local_only_transcribes_every_chunk_and_marks_done() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 20_000)
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

    @Test fun cloud_happy_path_uses_the_provider_and_marks_openai() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 8000)
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
        val (store, id) = storeWith(pcmBytes = 4000)
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
        val (store, id) = storeWith(pcmBytes = 9000)
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
        val (store, id) = storeWith(pcmBytes = 9000)
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
        val (store, id) = storeWith(pcmBytes = 6000)
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
        val (store, id) = storeWith(pcmBytes = 30_000)
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

    @Test fun a_fallback_cloud_chunk_is_re_sliced_to_local_sized_sub_chunks() = runBlocking {
        // THE OOM GUARD (review Critical): chunks are planned at the CLOUD ceiling when a provider
        // is present. A chunk that then falls back must never reach the native model whole —
        // pcm16ToFloat on a 20 MB chunk alone allocates a ~40 MB FloatArray. Every native call must
        // see at most LOCAL_CHUNK_BYTES/2 samples.
        val (store, id) = storeWith(pcmBytes = 24_000)
        val fake = FakeHttpTransport { _, _ -> HttpResult.HttpError(503, "down") } // always falls back
        val maxSamplesSeen = AtomicInteger(0)
        val backend = object : WhisperBackend {
            override fun load(modelPath: String) = 1L
            override fun transcribe(ctx: Long, samples: FloatArray, lang: String?): String {
                maxSamplesSeen.getAndUpdate { seen -> maxOf(seen, samples.size) }
                return "s"
            }
            override fun release(ctx: Long) {}
        }
        val t = BatchTranscriber(store, cloud = OpenAiStt(fake, "sk-k"), backend = backend,
            modelPathProvider = modelPath)
            .apply { testCloudCeiling = 24_000; testLocalChunk = 4000; testMaxCloudRetries = 0 }
        t.transcribe(id)
        val m = store.read(id)!!
        assertEquals(BatchStatus.Done, m.status)
        assertTrue(
            "native saw ${maxSamplesSeen.get()} samples; local ceiling is ${4000 / 2}",
            maxSamplesSeen.get() <= 4000 / 2,
        )
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.util.concurrent.Executors

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
 * Cloud eligibility is decided UPSTREAM: the service passes a non-null [cloud] only when
 * BatchCloudGate's consent triad holds (and the cost confirm, and notifications). This class does
 * not re-derive policy — with cloud == null it is a purely local job runner. The fallback valve is
 * one-way (cloud -> local per chunk); a cloud Fatal latches.
 *
 * Threading: the native single-thread invariant is enforced INSIDE this class, not by caller
 * convention. transcribe() is a suspend function that hops threads at every cloud await, so "the
 * service launched me on a single-thread dispatcher" is NOT enough — the resume after a cloud call
 * may land elsewhere. Every native touch (load/transcribe/release) is therefore wrapped in
 * withContext(nativeDispatcher), a private single-thread executor this class owns, creates, and
 * closes; a caller cannot break the invariant by choosing a bad dispatcher. Release runs under
 * NonCancellable so a cancelled job still frees the ctx, on the confined thread.
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

    /**
     * The ONLY thread that may touch the native whisper ctx. Owned here (created and closed by
     * this class) so the confinement cannot be broken by a caller's dispatcher choice — see the
     * class KDoc.
     */
    private val nativeDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "batch-native").apply { isDaemon = true } }
            .asCoroutineDispatcher()

    /** Call once when the owning service is destroyed. Idempotent. */
    fun shutdown() = nativeDispatcher.close()

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

        // (1) The engine was decided UPSTREAM (service: BatchCloudGate + cost confirm +
        // notifications). Non-null cloud here means "this job is allowed to upload".
        val effectiveCloud: SttProvider? = cloud
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
                                    // (RE-SLICE) This chunk was planned at the CLOUD ceiling.
                                    usedLocal = true; runLocalSliced(ctx, pcm, meta.language) to EngineUsed.LOCAL
                                }
                            }
                        }
                        else -> {
                            // Planned at the LOCAL ceiling (see (1)) — safe to feed whole.
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
            // Confined AND NonCancellable: a cancelled job must still free the native ctx, and
            // must free it from the one thread allowed to touch it.
            if (ctx != 0L) withContext(nativeDispatcher + NonCancellable) { backend.release(ctx) }
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

    private suspend fun loadCtx(): Long = withContext(nativeDispatcher) {
        val path = modelPathProvider.installedModelPath()
            ?: error("No on-device model installed — cannot transcribe locally")
        backend.load(path)
    }

    /** One local-sized piece. A local run for a PLAYBACK recording is always fine — the gate is on CLOUD dispatch. */
    private suspend fun runLocal(ctx: Long, pcm: ByteArray, language: String?): String =
        withContext(nativeDispatcher) {
            val samples = AudioMath.pcm16ToFloat(pcm)
            TranscriptText.clean(backend.transcribe(ctx, samples, language))
        }

    /**
     * (RE-SLICE) A chunk planned at the CLOUD ceiling (up to 20 MB / ~10.5 min) that falls back must
     * never reach the native model whole: pcm16ToFloat alone would allocate a ~40 MB FloatArray on
     * top of the 20 MB source, plus minutes of native encoder buffers, inside a foreground service —
     * the exact long-feed OOM LOCAL_CHUNK_BYTES exists to prevent. Slice to the local ceiling at an
     * even offset (a PCM16 sample is 2 bytes; an odd cut would shear every later sample) and join
     * the pieces with a space, matching assembledText's convention for trimmed chunk text.
     */
    private suspend fun runLocalSliced(ctx: Long, pcm: ByteArray, language: String?): String {
        val step = testLocalChunk - (testLocalChunk % 2)
        val parts = ArrayList<String>()
        var start = 0
        while (start < pcm.size) {
            val end = minOf(start + step, pcm.size)
            val text = runLocal(ctx, pcm.copyOfRange(start, end), language)
            if (text.isNotBlank()) parts.add(text)
            start = end
        }
        return parts.joinToString(" ")
    }

    companion object {
        private const val MAX_CLOUD_RETRIES = 3
        private const val BASE_BACKOFF_MS = 400L
    }
}
```

> **Where the upload gates live, precisely:** consent triad (`BatchCloudGate`), the cost confirm,
> and the notifications check are all enforced in the SERVICE before a non-null `cloud` is ever
> constructed (Task 6) — this class treats a non-null provider as an upstream decision already
> made. There is no capture-source gate because batch has no capture path (see Task 4's rescope
> note); the pinning tests here are re-slice, resume-not-rerun, fatal latching, and cancel.

- [ ] **Step 4: Verify**

```bash
.\gradlew.bat :app:testDebugUnitTest --no-daemon --tests "com.whispereverywhere.transcription.batch.BatchTranscriberTest"
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: PASS, 8 tests; release green. Full suite: previous **+8**, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/transcription/batch/BatchTranscriber.kt \
        app/src/test/java/com/whispereverywhere/transcription/batch/BatchTranscriberTest.kt
git commit -m "feat(batch): BatchTranscriber — sequential, checkpointed, resumable

Each chunk checkpoints status+text to the manifest, so Retry resumes and a
Done cloud chunk is never re-uploaded — safe on a paid API. Sequential
means no SegmentOrderer; output is an in-order join. Cloud eligibility is
decided upstream (BatchCloudGate + cost confirm in the service); a non-null
provider here means the job is allowed to upload.

Fallback is one-way per chunk (cloud -> local on Offline/exhausted-Transient
/BadSegment), re-sliced to the local ceiling so a 20 MB cloud chunk can
never hit the native model whole; a cloud Fatal latches, stops, and leaves
finished chunks for resume. One native ctx per job, confined to the class's
own single thread; released in finally under NonCancellable. Cancel stops
between chunks, keeps partial results, and never deletes audio."
```

---

## Task 6: `BatchTranscriptionService` — the foreground host + manifest entry

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/service/BatchTranscriptionService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: everything above, `WhisperEverywhereApp.getInstance()`, `WhisperNativeBackend`, `WhisperModelManager` (as `ModelPathProvider`), `OpenAiStt`, `OkHttpTransport`, `PreferencesManager`, `AudioDecoder` (Task 2).
- Produces: a started service that runs ONE job — **decode phase, then transcribe phase** — to completion off the main thread, publishing progress the ViewModel observes, and deleting the workspace on `Done`.

**Why a foreground service and not a plain coroutine:** an hour-long file decodes and transcribes for many minutes; backgrounded plain coroutines are killed by the OS mid-job. Foreground keeps it alive; the per-chunk checkpoint is the second belt for the case where even a foreground service dies. Both are needed.

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

- `onStartCommand` handles two intents:
  - **New job:** `intent.data` = the picked content Uri (set with `FLAG_GRANT_READ_URI_PERMISSION` by the UI — the grant rides the intent to the service), plus `EXTRA_DISPLAY_NAME` (String), `EXTRA_DURATION_MS` (Long, from the UI's retriever pre-flight), optional `EXTRA_COST_CONFIRMED` (Boolean, default false).
  - **Retry:** `EXTRA_RECORDING_ID` (String) of an existing workspace, optional `EXTRA_RESET` (Boolean), optional `EXTRA_COST_CONFIRMED`. `audio.pcm` already exists — the decode phase is skipped.
  - Neither present → `stopSelf()`, `START_NOT_STICKY`.
- Call `startForeground(NOTIF_ID, notification)` immediately with a low-priority, ongoing notification: title "Transcribing audio file", text from the job phase ("Preparing audio…" during decode, "Chunk i of N" after). **No speed claims.** Reuse the app's existing notification channel pattern.
- **Decode phase (new job only):** `store.sweepStale()`; new `id = UUID.randomUUID().toString()`; `StorageGuard.enoughSpace(availableBytesAt(store.dir(id)), BatchCostEstimator.bytesForDuration(durationMs))` — too little space → `Failed("Not enough storage")`, workspace deleted, stop. Then `AudioDecoder().decodeTo(this, uri, PcmSink(store.audioFile(id)), onProgress)`. `Unsupported` → `Failed("Couldn't read this audio file")`, workspace deleted, stop (never log the Uri — it can embed a filename). `Ok(byteLength, realDurationMs)` → `store.save(RecordingMeta(id, now, realDurationMs, displayName, byteLength = byteLength.toInt()))`.
- Build the transcriber once per start:
  - `store = RecordingStore.forApp(this)` — the Task 1 factory; never a bare `File` in production.
  - `provider`: **do NOT imitate `FloatingBubbleService.resolveTranscriptionEngine`** — it is a private member returning a fully-wired live engine, and it does not itself read `cloudDisclosureAccepted` (that gating lives upstream in provider setup). Batch asserts the whole triad explicitly through Task 4's pinned predicate: gather `providerId = prefs.sttProviderId` (validated via the top-level `resolveSttProvider`, FloatingBubbleService.kt:99), `key = providerAccounts.key(id)`, `disclosureAccepted = prefs.cloudDisclosureAccepted`, then `cloud = if (BatchCloudGate.cloudEligible(providerId?.name, key, disclosureAccepted) && network validated) OpenAiStt(transport, key!!) else null`.
  - **Cloud also requires two more service-side checks, each degrading to local (never failing the job):**
    (a) **POST_NOTIFICATIONS on 33+** (spec §6.5): if notifications are denied, the FGS spend indicator is invisible while the user is charged — so `cloud = null` and the job runs local. Check `NotificationManagerCompat.from(this).areNotificationsEnabled()`.
    (b) **The §6.5 cost confirm:** if `BatchCostEstimator.needsConfirmation(meta.byteLength)` and `EXTRA_COST_CONFIRMED` was not passed `true`, `cloud = null`. The UI (Task 7) shows the confirm dialog and sets the extra; the service check means no path — a stale intent, a future caller — can start a large cloud job unconfirmed.
  - `backend = WhisperNativeBackend`, `modelPathProvider = app.whisperModelManager` (already implements `ModelPathProvider`).
- Launch off-main: `serviceScope.launch(Dispatchers.Default) { try { /* decode phase first for a new job */ transcriber.transcribe(id, reset); deliver(id) } finally { stopForeground(...); stopSelf() } }`. **The service does NOT create a native-confinement dispatcher** — that confinement lives INSIDE `BatchTranscriber` (its own `nativeDispatcher`, Task 5) and cannot be affected by the launch context; `Default` is only the host for the suspend loop.
- **`deliver(id)`:** when the final status is `Done`, save `store.assembledText(meta)` into the existing `TranscriptStore` through its existing public API (the transcript is TEXT — the store's text-only contract is untouched), then `store.delete(id)`: **no audio outlives its job.** On `Failed`/`PartiallyDone` the workspace is KEPT so Retry can resume; it is deleted when the user dismisses the job in the UI, or by `sweepStale()` after 24 h.
- Expose progress to the UI via a process-scoped singleton the `BatchJobViewModel` reads (e.g. `BatchJobController` object holding the active `BatchTranscriber`'s `progress` StateFlow and a `cancel()` passthrough), OR bind — the simplest is a small `object BatchJobController { val progress = MutableStateFlow<BatchProgress?>(null); fun cancelActive() }` updated by the service. Keep it minimal; no binder.
- `onDestroy` cancels the active job cooperatively (`transcriber.cancel()`) and then calls `transcriber.shutdown()` to close its native dispatcher.

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
git commit -m "feat(batch): foreground BatchTranscriptionService — decode, then transcribe

An hour-long file decodes and transcribes for many minutes; a foreground
service keeps the job alive and the per-chunk manifest checkpoint is the
second belt if even that dies. Decode phase: SAF Uri -> AudioDecoder ->
cache workspace, gated by StorageGuard on the retriever-duration estimate.
Cloud is passed only when BatchCloudGate's triad holds, notifications are
enabled (the spend indicator must be visible while the user is charged),
and the cost confirm rode the intent. On Done the transcript is saved to
TranscriptStore and the workspace deleted — no audio outlives its job."
```

---

## Task 7: UI — the picker, `BatchTranscribeScreen`, ViewModel, Home card

> ONE screen. No recordings list, no record screen, no detail screen — the rescope cut them all.
> No audio playback, no scrubber, no audio export.

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/ui/BatchJobViewModel.kt`
- Create: `app/src/main/java/com/whispereverywhere/ui/screens/BatchTranscribeScreen.kt`
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt`
- Modify: `app/src/main/java/com/whispereverywhere/MainActivity.kt`

**Interfaces:**
- Consumes: `RecordingStore`, `RecordingMeta`, `BatchCostEstimator`, `BatchCloudGate`, `BatchTranscriptionService`, `BatchJobController`, `TranscriptStore` (read-only here — the SERVICE saves the transcript).
- Produces: a working feature reachable from Home.

**Behavior spec** (Material3, `Scaffold`+`TopAppBar`, no new theme; the implementer matches `TranscriptsScreen.kt` and `HomeScreen.kt`):

- [ ] **Step 1: `BatchJobViewModel`** — observes `BatchJobController.progress` (StateFlow<BatchProgress?>), exposes `cancel()` (→ `BatchJobController.cancelActive()`), `startNew(uri, displayName, durationMs, costConfirmed)` and `retry(id, reset, costConfirmed)`, each firing the matching intent at `BatchTranscriptionService` (`startForegroundService`; for `startNew`, set `intent.data = uri` and `addFlags(FLAG_GRANT_READ_URI_PERMISSION)` — the read grant must ride the intent to the service). Also `dismiss(id)` → `RecordingStore.forApp(app).delete(id)` off-main, for abandoning a Failed job.

- [ ] **Step 2: The picker flow** — in `MainActivity`, a `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` for `arrayOf("audio/*")`. On result: query `OpenableColumns.DISPLAY_NAME` for the file name; read duration with `MediaMetadataRetriever` (`setDataSource(context, uri)`, `METADATA_KEY_DURATION`, off-main, `runCatching` — a broken file yields duration 0 and the service's decoder produces the honest failure). Navigate to `batch_transcribe` carrying `{uri, displayName, durationMs}`.

- [ ] **Step 3: `BatchTranscribeScreen`** — one screen, four states:
  - **Ready:** file name + duration; an **engine row for THIS job**: "On-device (free, private)" and — only when `BatchCloudGate.cloudEligible(prefs.sttProviderId?.let(::resolveSttProvider)?.name, key, prefs.cloudDisclosureAccepted)` — the provider row with its price: "OpenAI · about ¢0.45/min" (`BatchCostEstimator.CENTS_PER_MINUTE`; "about", never a promise; **no speed claims**). Defaults to the global selection. A big "Transcribe" button.
  - On Transcribe with the cloud row chosen and `BatchCostEstimator.needsConfirmation(bytesForDuration(durationMs))`: an `AlertDialog` — "Transcribe about N min in the cloud for about ¢X with your OpenAI key?" / "Use cloud" / "Use on-device" — and `startNew(..., costConfirmed = true)` only on "Use cloud" ("Use on-device" starts without the flag; the service then runs local). Below threshold, or on-device chosen, start directly.
  - **Working:** phase label ("Preparing audio…" then "Chunk i of N"), linear progress, Cancel.
  - **Done:** the transcript (scrollable), Copy / Share (`ACTION_SEND`, `text/plain` — the existing text-only share), and a "Saved to Transcriptions" caption (the service already saved it; this screen just shows it).
  - **Failed / PartiallyDone:** honest reason ("Couldn't read this audio file" / "Transcription stopped — N of M parts done"), **Retry** primary (`retry(id, reset=false)` — resumes; applies the same cost gate, estimating on the FULL byteLength — an over-estimate when chunks are already Done, erring toward asking), and **Discard** (`dismiss(id)`, back to Home).

- [ ] **Step 4: Home card + nav** — In `HomeScreen.kt`, add a `Card` cloning the Transcriptions-card template (lines ~187-224) titled **"Transcribe audio file"**, subtitle "Pick a recording and turn it into text", `clickable { onPickAudioFile() }` (launches the Step 2 picker), threading a new `onPickAudioFile` param. In `MainActivity.kt`, add the `batch_transcribe` route exactly as `transcripts` was added (lines 136-146).

- [ ] **Step 5: Verify**

```bash
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleRelease --no-daemon
```
Expected: both assemble green; full unit suite unchanged from Task 5's total, 0 failures (UI is not unit-tested here; its logic is pinned upstream). Then read `BatchTranscribeScreen.kt` and confirm the cloud row is **absent** (not merely disabled) when the `BatchCloudGate` triad does not hold.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/whispereverywhere/ui/BatchJobViewModel.kt \
        app/src/main/java/com/whispereverywhere/ui/screens/BatchTranscribeScreen.kt \
        app/src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt \
        app/src/main/java/com/whispereverywhere/MainActivity.kt
git commit -m "feat(ui): Transcribe audio file — SAF picker into one batch screen

A Home card opens the system audio picker; one screen carries the job:
per-job engine choice (cloud row shown only when the BatchCloudGate triad
holds, with its per-minute price), cost confirm past the Sec6.5 threshold,
decode+chunk progress with Cancel, the finished transcript with Copy/Share
(also saved to the normal Transcriptions history by the service), and
Retry-resume on failure. No recordings library, no playback, no export."
```

---

## Task 8: Compliance — one clause in four documents, and two no-change determinations

**Files:**
- Modify: `app/src/main/assets/privacy_policy.html` + `docs/privacy.html` (must stay content-identical)
- Modify: `app/src/main/assets/terms_of_service.html` + `docs/terms.html` (must stay content-identical)

- [ ] **Step 1: The privacy §6 clause.** Today §6 states a selected provider *"only ever receives audio you dictate yourself through the microphone"* — false the moment a picked file can be pushed to cloud. Change that sentence (in BOTH privacy copies) to: *"…only ever receives audio you dictate yourself through the microphone, or an audio file you explicitly choose to transcribe with that provider."* Leave the MediaProjection carve-out sentence untouched — it stays absolute, and batch (which has no capture path) cannot affect it. Bump Last-Updated.

- [ ] **Step 2: The ToS mirror.** §3's bullet says *"Your dictated audio is sent to a third party only if…"* — extend the same way: *"Your dictated audio, or an audio file you choose to transcribe, is sent…"* in BOTH ToS copies. Bump Last-Updated.

- [ ] **Step 3: Verify the pairs agree** — `diff --strip-trailing-cr` each asset against its `docs/` twin; paste both results (expected: identical) into the task report.

- [ ] **Step 4: Record the two NO-CHANGE determinations** in the release ledger so a reviewer does not re-open them:
  - **No Data Safety flip.** The user-initiated audio upload to a user-keyed provider was declared in C2a; a picked file rides the identical, identically-gated transmission. Transient cache processing of a user-selected file is not new collection.
  - **No disclosure v2→v3 bump.** The dialog's meaning is unchanged (same triad, same provider, same class of user-directed audio); the per-job engine row in `BatchTranscribeScreen` — where the user pushes a NAMED file at a NAMED provider — is the explicit per-file consent surface. (Contrast MF3, where the meaning DID change and the bump was mandatory.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/privacy_policy.html docs/privacy.html \
        app/src/main/assets/terms_of_service.html docs/terms.html
git commit -m "docs(privacy): cloud may also receive audio files the user chooses to transcribe

Batch import makes 'only ever receives audio you dictate yourself through
the microphone' false — extend the sentence in both privacy copies and its
ToS mirror to cover files the user explicitly pushes at their provider.
MediaProjection carve-out untouched: batch has no capture path. No Data
Safety flip (same declared user-initiated transmission) and no disclosure
version bump (meaning unchanged; the per-job engine row is the per-file
consent surface) — both determinations recorded."
```

This task exists so the compliance obligation has an owner and a due date rather than living only in a plan header — the MF1 lesson applied to docs.

---

## Task 9: On-device verification

**Files:** none.

- [ ] **Step 1: Signature preflight, then install.** Follow the preflight in the TTS-diagnostics plan. Use `adb install -r`. **Never `connectedAndroidTest`.**

- [ ] **Step 2: The checks only a device can make**

1. **The codec zoo — the decode pipeline's real test** (it deliberately has no JVM tests; this is where it earns trust). Prepare four short clips of the SAME spoken sentence: `.mp3`, `.m4a` (AAC), `.wav` (44.1 kHz stereo — exercises downmix AND resample), `.ogg` (Opus). Pick each through the UI with no cloud provider: all four must produce essentially the same transcript. A garbled transcript on exactly one format = decoder bug for that codec path; garbled on all = resampler bug.
2. **A non-audio / corrupt file fails honestly.** Rename a `.jpg` to `.mp3` and pick it: "Couldn't read this audio file", no crash, workspace deleted (check `cacheDir/batch` is empty after).
3. **Long file chunks and checkpoints.** Pick a 15+ minute file (local engine). Confirm multiple chunks, "Chunk i of N" progress, and that force-killing the app mid-job then retrying resumes from the last `Done` chunk (no re-transcription of finished chunks).
4. **Cloud happy path.** Select OpenAI for the job, pick a short file, transcribe. Text appears; the upload succeeds. This is the only thing that proves the endpoint/model against live billing — and the first-ever live exercise of `gpt-transcribe` if C2a's device test has not run yet.
5. **The cost confirm.** Pick a >10-minute file with cloud chosen: the confirm dialog must appear BEFORE any upload, with an "about" price; "Use on-device" must run the whole job locally (watch for zero requests).
6. **Retry never re-bills a Done cloud chunk.** Force a partial failure (airplane mode mid-cloud-job so later chunks fall to local or fail), then Retry: confirm already-`Done` chunks are not re-uploaded (watch request count / spend).
7. **Fatal latches.** Save a mangled key, cloud-transcribe: the job stops at `Failed` after one fatal request, finished chunks are kept, and Retry (after fixing the key) resumes.
8. **No audio outlives its job.** After a successful transcription, confirm the transcript is in the normal Transcriptions history AND `cacheDir/batch` holds no directory for that job. After Discard on a failed job, same.
9. **No speed claims** anywhere in the batch UI or notification.

- [ ] **Step 3: Record the outcome** in the plan or the release ledger, including the live confirmation of `gpt-transcribe` on the batch endpoint, per-codec results, and anything deferred.

---

## Self-Review

**Spec/invariant coverage** — every requirement has an implementing task AND (where it names a behavior) a pinning test:

| Requirement (owner intent / invariant) | Task | Pinned by |
|---|---|---|
| Pick an audio file, push it into transcription | 7 (picker + screen) | on-device Task 9.1 |
| Any Android-decodable format → 16 kHz mono PCM | 2 | `SampleMathTest` (math) + Task 9.1 codec zoo (codec loop) |
| Transcribe all at once through the chosen engine | 5, 6 | `BatchTranscriberTest` local+cloud |
| No saved recordings — audio never outlives its job | 1, 6 | `delete_removes_the_whole_workspace_directory`, `sweepStale_*`, Task 9.8 |
| Retry resumes from the checkpoint | 5, 7 | `retry_resumes_and_never_re_runs_a_done_chunk` |
| Cloud needs key+provider+consent v2 | 4, 6 | `BatchCloudGateTest` (incl. the v1-upgrader case) |
| Cloud is never a surprise charge | 4, 6, 7 | `BatchCostEstimatorTest` + Task 9.5 |
| Fallback one-way cloud→local, re-sliced | 5 | `a_cloud_chunk_that_fails_transiently_falls_back`, `a_fallback_cloud_chunk_is_re_sliced_*` |
| No credential/content in logcat | all | code review — lengths/counts only; never the Uri |
| No speed claims | 6, 7 | Task 9.9 |
| Works offline, local default | 5, 6 | `local_only_transcribes_every_chunk` + Task 9.1 |
| 25 MB / ~13.1 min chunk math | 3 | `no_chunk_ever_exceeds_the_ceiling`, `cloud_ceiling_stays_under_the_openai_hard_cap` |
| Sequential, SegmentOrderer skipped | 5 | design + sequential loop (no orderer imported) |
| Checkpoint/resume, no double-bill | 5 | `retry_resumes_*`, `a_fatal_cloud_error_latches_*` |
| Cancel keeps partial, never deletes audio mid-job | 5 | `cancel_stops_between_chunks_*` |
| Foreground host for long jobs | 6 | Task 9.3 (kill/resume) |
| Policy truthfulness (files may go to cloud) | 8 | pair-diff in Task 8 Step 3 |

**Placeholder scan:** none. Tasks 1–5 carry complete Kotlin (model, store, sample math, decoder, scanner, planner, gates, transcriber) and complete tests. Tasks 6–7 specify Android wiring and Compose behavior precisely against named existing files (matching how C2a Tasks 6–7 did), because their logic is already unit-pinned upstream. Task 8 is two one-clause edits plus two recorded determinations. Task 9 is manual verification.

**Type consistency across tasks:** `RecordingMeta`/`ChunkEntry`/`ChunkStatus`/`BatchStatus`/`EngineUsed` are declared once (Task 1) and used identically in `RecordingStore`, `BatchTranscriber`, and the UI. `Downmix.toMono(ShortArray, Int)` / `Resampler.to16k(ShortArray, Int)` match `AudioDecoder`'s call sites. `ChunkPlanner.plan(totalBytes, maxChunkBytes, minChunkBytes, boundaries)` and `SilenceScanner.scan(pcm)` signatures match their call sites in `BatchTranscriber`. `BatchCloudGate.cloudEligible(String?, String?, Boolean)` matches its service and UI callers. `SttProvider.transcribe(pcm, language): SttResult` and `SttError`/`FatalKind` are consumed exactly as C2a defines them. `WhisperBackend.load/transcribe/release` and `ModelPathProvider.installedModelPath()` match Release A. `BatchProgress` is produced by `BatchTranscriber` and consumed by `BatchJobViewModel`.

**The MF1 lesson, applied:** the consent triad and the cost rule each have a code gate with its own pinning test (Task 4) consumed at the single decision site (Task 6); the "no audio outlives its job" rule has an implementing mechanism (`deliver()` + `sweepStale`) and a device check (9.8); the policy edit is a task with a diff-verified hand-off, not a header sentence with no owner. The PLAYBACK provenance gate is deliberately ABSENT rather than vestigial — batch has no capture path, and dead gates rot into false confidence.

**One risk recorded rather than hidden.** The design's "Silero VAD boundaries" is unreachable from Kotlin and memory-unsafe over long clips; this plan substitutes a memory-bounded energy scan for the *coarse* cut while the Silero VAD still runs per chunk inside `whisper_full`. This is a deliberate deviation from decision log #8, surfaced to the owner. It cannot affect correctness or the 25 MB bound (the hard-cut fallback guarantees both); it can only make a coarse cut land slightly off an ideal pause, which whisper's per-chunk VAD and 1.1 s pad absorb.
