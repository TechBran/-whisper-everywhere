# Batch STT for ALL providers + the batch-only VAD bypass

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Assert the fresh green suite count at the gate — do not hardcode it here.

**Goal:** Two owner asks in one wave. (1) **Widen batch cloud transcription from OpenAI-only to all four STT providers** (OpenAI, Gemini, ElevenLabs, Soniox) — the same set live dictation already offers — reusing every inherited gate exactly (triad + disclosure v3 + cost confirm + notifications, per job, per provider). (2) **Bypass the Silero VAD on the BATCH path only** so a user-chosen file is transcribed *everything*, quiet music and low speech included; live dictation's VAD is untouched (it suppresses noise hallucination there — a batch file is deliberately chosen, so transcribe all of it).

**Architecture:** Unchanged seams. `SttProvider.transcribe(pcm, language): SttResult` is still the whole cloud contract; `SttProviderFactory` is still the ONE construction point; `BatchTranscriber` is still a sequential per-chunk runner with a one-way cloud→local fallback valve. What changes: the OpenAI **clamp** in `resolveBatchSttProvider` drops (batch admits the same set as live); the single global **cloud ceiling** becomes per-provider (derived from each adapter's already-base64-aware `maxRequestBytes`); the flat **cost rate** becomes per-provider with an UNKNOWN policy; `EngineUsed` widens; and `WhisperBackend.transcribe` gains a `useVad` flag that batch's local paths pass `false`. **No native / CMake edit** — the recon proved the VAD seam is skippable from Kotlin (`vadModelPath = null` short-circuits `we_vad_filter` at `whisper_jni.cpp:188`).

**Tech Stack:** Kotlin 2.0.21, OkHttp 4.12.0 (**pinned**), kotlinx-serialization-json 1.7.3, JUnit 4. **No new dependencies.**

## Global constraints (carried, still binding)

- **`java` NOT on PATH.** PowerShell: `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"`; `.\gradlew.bat --no-daemon`. `assembleRelease`/`bundleRelease` (R8) must stay green — R8 is where a widened enum or a dropped clamp bites.
- **NEVER `connectedAndroidTest` or `installDebug`** (they uninstall — destroyed user models twice). Instrumented = compile-check only.
- `unitTests.isReturnDefaultValues = true`; **`org.json` BANNED**, **`android.util.Base64` BANNED** — kotlinx.serialization / `java.util.Base64` only.
- **Commit ONLY named files, never `git add -A`. Retry once on `index.lock`. Branch `main`.**
- **Baseline: assert the green suite count FRESH at the gate**; 3.3.0/73 already bumped. The count grows by the new suites, never regresses. Assert `assembleDebug` + `assembleRelease` green before AND after.
- **No credential/content logging** (no key, header, body, or Uri). **No speed claims.** Every price says "about".
- **All inherited gates EXACTLY, per job, per provider:** `BatchCloudGate` triad + disclosure **v3** + `BatchCostEstimator` cost confirm + notifications-enabled. The per-job On-device pick stays a hard floor (`BatchEngineDecision`). Nothing in this wave loosens a gate; the cost gate gets *more* conservative (per-provider rate, UNKNOWN→most-expensive-known).
- **The VAD bypass is BATCH-ONLY** — both `runLocal` (small-planned local job) and `runLocalSliced` (the cloud-chunk fallback re-slice). Live's default stays `useVad = true`.
- **Soniox batch** is the poll-based path per chunk (upload→create→poll→fetch→delete). Its per-chunk poll budget bounds, `NonCancellable` delete-after, and the honest "Chunk i of N" progress all already hold inside the adapter — batch inherits them unchanged.

---

## Pinned STT pricing (fetched live 2026-07-31)

Rates are per pinned model, converted to ¢/min. Every figure the UI shows says **"about"**. An UNKNOWN price does **not** block the provider — it is priced for the *confirm decision only* at the **most-expensive-known** rate (conservative: asks sooner, never under-warns).

| Provider (pinned model) | Published rate | ¢/min | Source |
|---|---|---|---|
| **OpenAI** `gpt-transcribe` | $0.006/min ($2.50 /1M audio-in) — transcribe-family est. | **0.60** | developers.openai.com/api/docs/pricing |
| **ElevenLabs** `scribe_v2` | $0.22/hr | **0.37** | elevenlabs.io/pricing/api (PAYG, Jun-2026 cut) |
| **Soniox** `stt-async-v5` | $0.10/hr async | **0.17** | soniox.com/pricing (async file) |
| **Gemini** `gemini-3.6-flash` | audio-input rate **NOT separately published** (std input $1.50/1M) | **UNKNOWN → 0.60** | ai.google.dev/gemini-api/docs/pricing |

- `gpt-transcribe` publishes no distinct per-minute figure; the transcribe family (`gpt-4o-transcribe`) is **$0.006/min** on the live docs page — used as OpenAI's known rate. It is also the **most-expensive-known** rate, so it governs Gemini's confirm decision.
- The stale in-code `CENTS_PER_MINUTE = 0.45` (dated 2026-07-29) is **below** current OpenAI pricing; replacing it with 0.60 tightens the gate.

---

## File Structure

| File | Change |
|---|---|
| `transcription/TranscriptionEngine.kt` | **Modify.** `WhisperBackend.transcribe` gains `useVad: Boolean = true`; `WhisperNativeBackend` passes `if (useVad) VadModel.path() else null` at both native call sites (`:133`,`:140`). |
| `transcription/batch/BatchTranscriber.kt` | **Modify.** `runLocal`/`runLocalSliced` pass `useVad = false`; `EngineUsed.OPENAI` hardcodes → the resolved provider's engine; ceiling comes from the provider. |
| `transcription/batch/BatchCostEstimator.kt` | **Modify.** Per-provider `centsPerMinute(ProviderId?)` + UNKNOWN policy; `estimatedCents`/`needsConfirmation` take the provider. |
| `transcription/batch/BatchChunkCeiling.kt` | **Create.** Provider-aware max chunk bytes, derived from `SttProvider.maxRequestBytes` (already base64-aware), capped to a memory bound. |
| `transcription/batch/BatchEngineDecision.kt` | **Modify.** `cloudAllowed` threads the `ProviderId` into the cost gate; KDoc "provider" not "OpenAI". |
| `transcription/batch/BatchCloudGate.kt` | **Modify (comment only).** KDoc "disclosure v2" → **v3** (drift). |
| `service/BatchTranscriptionService.kt` | **Modify.** Drop the OpenAI `takeIf` clamp in `resolveBatchSttProvider`; `resolveCloud` builds the ceiling+cost from the resolved provider; flip the "guaranteed OPENAI" comments. |
| `ui/screens/BatchTranscribeScreen.kt` | **Modify.** Cloud row + cost dialog render the SELECTED provider's name & rate, not hardcoded "OpenAI". |
| `recording/RecordingMeta.kt` | **Modify.** `EngineUsed` widens (`GEMINI/ELEVENLABS/SONIOX`) + `fromProviderId`. Old manifests (LOCAL/OPENAI, by name) still parse — no migration. |
| `transcription/cloud/SttProviderFactory.kt` | **Modify (comment only).** Drop "for OpenAI only — the batch service". |
| `transcription/live/RealtimeTransport.kt` | **Modify.** `ExecutorReconnectScheduler.shutdown()` → `exec.shutdownNow()` (Minor A). |
| `service/FloatingBubbleService.kt` | **Modify.** `onDestroy` calls the scheduler shutdown before nulling (Minor A). |
| `AndroidManifest.xml` | **Modify.** Rewrite the stale 3.2.0 INTERNET comment (Minor B). |
| `docs/PLAY-DECLARATIONS.md` | **Modify.** Flip the "Batch cloud is OpenAI-only" line + add the 3.3.0 batch-all-providers ledger entry. |
| **Tests** | `BatchCostEstimatorTest`, `BatchTranscriberTest`, `EngineSelectionTest` rewritten/extended; new `BatchChunkCeilingTest`, `ExecutorReconnectSchedulerTest`, `VadPlumbingTest`. Every `WhisperBackend` fake gains the `useVad` param. |

Untouched (re-asserted, not edited): `SttProvider`/`SttError`/`SttResult`, the four adapters, `CloudTranscriptionEngine`, `SilenceScanner`/`ChunkPlanner` logic (only the ceiling *source* moves), live-mode `resolveSttProvider`, live word-for-word's OpenAI-only rows.

---

## Task 1: The batch-only VAD bypass (Kotlin param threading — no native/CMake edit)

**Recon proof:** `whisper_jni.cpp:188` runs `we_vad_filter` only when `vadPathStr` is non-empty; a null/empty `vadModelPath` from Kotlin leaves it `""` (`:179-186`) and the VAD is short-circuited. `VadModel.path()` is already `String?`, so null flows cleanly. The no-VAD energy gate (`:194-202`, peak < 0.005f nukes a *fully* silent chunk) still fires — that is intended; any one sample ≥0.005 passes the whole chunk, so "transcribe everything" holds. **Do NOT touch native; flag nothing device-verification-required — this is pure Kotlin plumbing.**

- [ ] **Step 1 — widen the seam** (`TranscriptionEngine.kt:82`):

```kotlin
interface WhisperBackend {
    fun load(modelPath: String): Long
    /**
     * @param useVad true (live default) runs the Silero VAD before the encoder to suppress noise
     *   hallucination on always-open mic capture. Batch passes FALSE: a user-chosen file is
     *   transcribed in full (quiet music / low speech included), so the VAD must not trim it.
     *   Threads to WhisperNative.transcribe as vadModelPath = if (useVad) VadModel.path() else null,
     *   which short-circuits we_vad_filter natively (whisper_jni.cpp:188). BATCH-ONLY bypass.
     */
    fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean = true): String
    fun release(ctx: Long)
}
```

- [ ] **Step 2 — pass the flag through both native call sites** (`WhisperNativeBackend.transcribe`, `:128-147`). Add `useVad: Boolean = true` to the override; replace both `vadModelPath = VadModel.path()` with `vadModelPath = if (useVad) VadModel.path() else null`:

```kotlin
override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String =
    NativeComputeGate.serialized {
        val vad = if (useVad) VadModel.path() else null   // batch passes false -> no native VAD
        val validating = GpuPolicy.needsComputeValidation()
        if (!validating) {
            return@serialized WhisperNative.transcribe(ctx, samples, lang, translate = false, vadModelPath = vad)
        }
        GpuPolicy.onGpuComputeStarting()
        var ok = false
        try {
            val text = WhisperNative.transcribe(ctx, samples, lang, translate = false, vadModelPath = vad)
            ok = true; text
        } finally { GpuPolicy.onGpuComputeFinished(ok) }
    }
```

- [ ] **Step 3 — batch requests no-VAD** (`BatchTranscriber.kt:258-262`, `:272-283`). Both local helpers pass `useVad = false`:

```kotlin
private suspend fun runLocal(ctx: Long, pcm: ByteArray, language: String?): String =
    withContext(nativeDispatcher) {
        val samples = AudioMath.pcm16ToFloat(pcm)
        TranscriptText.clean(backend.transcribe(ctx, samples, language, useVad = false)) // BATCH: transcribe everything
    }
```

…and inside `runLocalSliced`, the per-slice call is `runLocal(ctx, pcm.copyOfRange(start, end), language)` — it already routes through `runLocal`, so the single edit above covers both the small-planned local job and the cloud-fallback re-slice. **Live dictation** (`LocalWhisperEngine`/`FloatingBubbleService`) calls `backend.transcribe(...)` without `useVad`, taking the `true` default — **untouched**.

- [ ] **Step 4 — update every `WhisperBackend` fake** to the widened signature (`useVad: Boolean = true`). Known fakes: `FakeBackend` (test util) and the inline `object : WhisperBackend` in `BatchTranscriberTest.kt:117`. A fake need not act on the flag except the plumbing test below.

- [ ] **Step 5 — pin the plumbing** (`VadPlumbingTest.kt`, new). A recording fake proves batch's local path asks for **no** VAD and live's default asks for VAD:

```kotlin
class VadPlumbingTest {
    private class RecordingBackend : WhisperBackend {
        val vadFlags = mutableListOf<Boolean>()
        override fun load(modelPath: String) = 1L
        override fun transcribe(ctx: Long, samples: FloatArray, lang: String?, useVad: Boolean): String {
            vadFlags += useVad; return "x"
        }
        override fun release(ctx: Long) {}
    }
    @Test fun batch_local_path_requests_no_vad() = runBlocking {
        val (store, id) = storeWith(pcmBytes = 8000)               // shared helper
        val backend = RecordingBackend()
        BatchTranscriber(store, cloud = null, backend = backend, modelPathProvider = modelPath)
            .apply { testCloudCeiling = 3000; testLocalChunk = 3000 }.transcribe(id)
        assertTrue("every batch local transcribe asked for NO vad", backend.vadFlags.isNotEmpty())
        assertTrue(backend.vadFlags.all { it == false })
    }
    @Test fun default_signature_keeps_vad_on_for_live() {
        // The interface default is the live contract: a caller that omits useVad gets VAD.
        val b = RecordingBackend(); b.transcribe(1L, FloatArray(0), "en"); assertEquals(listOf(true), b.vadFlags)
    }
}
```

---

## Task 2: Provider-aware `BatchCostEstimator`

Replace the flat rate with a per-provider lookup + UNKNOWN policy. `null` provider = on-device = free.

- [ ] **Step 1 — rewrite `BatchCostEstimator.kt`:**

```kotlin
package com.whispereverywhere.transcription.batch

import com.whispereverywhere.provider.ProviderId

/**
 * §6.5's "cloud is never a surprise charge" as math the UI and the service both call. Estimates
 * derive from byteLength at the PCM16/16 kHz byte rate, priced PER PROVIDER. They are ESTIMATES —
 * copy always says "about". An UNKNOWN provider price does not block the provider; for the CONFIRM
 * decision it is priced at the most-expensive-KNOWN rate (conservative — asks sooner, never under).
 *
 * Rates verified against live docs 2026-07-31 (¢/min):
 *   OpenAI gpt-transcribe 0.60 ($0.006/min) · ElevenLabs scribe_v2 0.37 ($0.22/hr)
 *   Soniox stt-async-v5   0.17 ($0.10/hr)   · Gemini gemini-3.6-flash audio input NOT published -> UNKNOWN.
 */
object BatchCostEstimator {
    /** 16 kHz x 2 bytes, mono PCM16. */
    const val BYTES_PER_SECOND = 32_000

    const val OPENAI_CENTS_PER_MIN = 0.60
    const val ELEVENLABS_CENTS_PER_MIN = 0.37
    const val SONIOX_CENTS_PER_MIN = 0.17
    /** The dearest KNOWN rate; governs any provider whose price we could not pin (Gemini audio). */
    const val MOST_EXPENSIVE_KNOWN_CENTS_PER_MIN = OPENAI_CENTS_PER_MIN

    const val CONFIRM_CENTS = 10.0
    const val CONFIRM_MINUTES = 10.0

    /** ¢/min for [providerId]; null (on-device) is free; an unpriced provider uses the dearest known rate. */
    fun centsPerMinute(providerId: ProviderId?): Double = when (providerId) {
        ProviderId.OPENAI -> OPENAI_CENTS_PER_MIN
        ProviderId.ELEVENLABS -> ELEVENLABS_CENTS_PER_MIN
        ProviderId.SONIOX -> SONIOX_CENTS_PER_MIN
        ProviderId.GEMINI -> MOST_EXPENSIVE_KNOWN_CENTS_PER_MIN // UNKNOWN audio-input price -> conservative
        null -> 0.0
    }

    fun minutes(byteLength: Long): Double = byteLength / (BYTES_PER_SECOND * 60.0)

    fun estimatedCents(byteLength: Long, providerId: ProviderId?): Double =
        minutes(byteLength) * centsPerMinute(providerId)

    fun needsConfirmation(byteLength: Long, providerId: ProviderId?): Boolean =
        estimatedCents(byteLength, providerId) >= CONFIRM_CENTS || minutes(byteLength) >= CONFIRM_MINUTES

    /** Pre-flight bridge: decoded PCM16 @16 kHz mono is exactly 32 bytes/ms. */
    fun bytesForDuration(durationMs: Long): Long = durationMs * (BYTES_PER_SECOND / 1000L)
}
```

- [ ] **Step 2 — tests** (`BatchCostEstimatorTest.kt`, extend): assert each known ¢/min constant; assert `centsPerMinute(GEMINI) == centsPerMinute(OPENAI)` (UNKNOWN→dearest-known) and `== MOST_EXPENSIVE_KNOWN_CENTS_PER_MIN`; assert `centsPerMinute(null) == 0.0`; assert the minutes bound still binds first at today's rates (10¢ ≈ 27 min at 0.37, but 10 min binds earlier) so a sub-10-min ElevenLabs/Soniox job never trips cents; assert a >10-min job of ANY provider (incl. `null`) needs confirmation via the minutes bound.

---

## Task 3: Provider-aware chunk ceiling (`maxRequestBytes`, base64 already baked in)

The single global `ChunkPlanner.CLOUD_CEILING_BYTES` (20 MB−44, "under OpenAI's 25 MB") becomes per-provider. **Key insight:** each adapter's `maxRequestBytes` already encodes its own request-shape math — Gemini's is **14 MB raw**, not 20 MB, precisely because base64 inflates the JSON body:

```
raw R bytes -> WAV(R+44) -> base64 = ceil((R+44)/3)*4  (~1.333x) -> + JSON envelope  MUST be < 20 MB
=> R < ~15 MB ; the adapter pins 14 MB (GeminiStt.kt:42) as the safe raw cap.
```

So batch must **not** redo base64 math — it inherits it by reading `maxRequestBytes` off the resolved adapter. The adapter also self-guards (`pcm.size + 44 > maxRequestBytes -> BadSegment -> FallBack -> local re-slice`), so under-sizing is only an efficiency issue (avoid needless fallbacks), never a correctness one.

- [ ] **Step 1 — create `BatchChunkCeiling.kt`:**

```kotlin
package com.whispereverywhere.transcription.batch

import com.whispereverywhere.transcription.cloud.SttProvider

/**
 * The per-chunk byte ceiling for a batch cloud job, derived from the RESOLVED adapter's
 * maxRequestBytes (which already accounts for that provider's request shape, incl. Gemini's
 * base64 inflation). Two clamps on top:
 *   - subtract the 44-byte WAV header the dispatch path adds, so pcm+header never trips the
 *     adapter's own BadSegment guard;
 *   - cap at MEMORY_BOUND: a chunk's PCM is read whole into a ByteArray then pcm16ToFloat'd, so an
 *     ElevenLabs 5 GB cap must NOT become a 5 GB allocation. 20 MB (~10.5 min) matches the prior
 *     global bound and the long-feed OOM budget the local ceiling was chosen against.
 * A null provider (on-device job) never calls this — the transcriber uses LOCAL_CHUNK_BYTES.
 */
object BatchChunkCeiling {
    const val WAV_HEADER_BYTES = 44
    /** Chosen batch bound; caps ElevenLabs' 5 GB adapter cap to a sane per-chunk allocation. */
    const val MEMORY_BOUND_BYTES = 20 * 1024 * 1024

    fun forProvider(provider: SttProvider): Int {
        val raw = (provider.maxRequestBytes - WAV_HEADER_BYTES).coerceAtMost(MEMORY_BOUND_BYTES.toLong())
        val bounded = raw.coerceIn(2L, MEMORY_BOUND_BYTES.toLong())
        return (bounded - (bounded % 2)).toInt()   // even -> never shears a PCM16 sample
    }
}
```

Resulting ceilings: OpenAI `min(25 MB−44, 20 MB)=20 MB` (unchanged behavior), Gemini `min(14 MB−44, 20 MB)=14 MB−44` (the base64-safe raw cap), ElevenLabs `min(5 GB−44, 20 MB)=20 MB`, Soniox `min(25 MB−44, 20 MB)=20 MB`.

- [ ] **Step 2 — consume it** in `BatchTranscriber` (`:105`, `:121`). Keep `testCloudCeiling` as the test seam but default it from the provider when one is present:

```kotlin
// default the cloud ceiling from the resolved adapter; tests still override via testCloudCeiling.
internal var testCloudCeiling: Int = cloud?.let { BatchChunkCeiling.forProvider(it) } ?: ChunkPlanner.CLOUD_CEILING_BYTES
```

`ChunkPlanner.CLOUD_CEILING_BYTES` stays as the harmless default when `cloud == null` (a local job never uses it — `ceiling` picks `testLocalChunk`). The comment at `ChunkPlanner.kt:77-78` updates: "default cloud ceiling; the real per-job ceiling is `BatchChunkCeiling.forProvider` from the resolved adapter."

- [ ] **Step 3 — tests** (`BatchChunkCeilingTest.kt`, new): a fake `SttProvider` per `maxRequestBytes` value; assert OpenAI/Soniox→20 MB, Gemini→14 MB−44, ElevenLabs→20 MB (capped, NOT 5 GB); assert every result is even and ≤ `MEMORY_BOUND_BYTES`; assert `forProvider` result + 44 ≤ that provider's `maxRequestBytes` (never trips the adapter's own guard).

---

## Task 4: Widen the clamp — batch admits all four STT providers

- [ ] **Step 1 — drop the clamp** (`BatchTranscriptionService.kt:73-74`). Batch now mirrors live's selectable set exactly:

```kotlin
/**
 * Batch cloud STT resolves the SAME provider set as live dictation (OpenAI, Gemini, ElevenLabs,
 * Soniox) as of 3.3.0. Each is constructed through the shared [SttProviderFactory], gated by the
 * identical triad + disclosure v3 + cost confirm + notifications, and rides the one-way local
 * fallback. There is no longer a batch-specific clamp — resolveBatchSttProvider IS resolveSttProvider.
 */
internal fun resolveBatchSttProvider(raw: String?): ProviderId? = resolveSttProvider(raw)
```

- [ ] **Step 2 — build ceiling + cost + provider in `resolveCloud`** (`:298-321`). Flip the "guaranteed OPENAI" comments; thread the resolved `providerId` into the cost gate:

```kotlin
private fun resolveCloud(byteLength: Long, costConfirmed: Boolean, useCloud: Boolean): SttProvider? {
    val prefs = app.preferencesManager
    // Batch resolves the same STT set as live (3.3.0). A selection with no adapter/no key still
    // degrades to on-device via the gate below — never mis-keyed.
    val providerId = resolveBatchSttProvider(prefs.sttProviderId)
    val key = providerId?.let { prefs.providerAccounts.key(it) }

    val allowed = BatchEngineDecision.cloudAllowed(
        useCloud = useCloud,
        providerId = providerId,          // now carries identity for the per-provider cost gate
        key = key,
        disclosureAccepted = prefs.cloudDisclosureAccepted,
        byteLength = byteLength,
        costConfirmed = costConfirmed,
        hasValidatedNetwork = { ConnectivityMonitor(this).hasValidatedNetwork() },
        notificationsEnabled = { NotificationManagerCompat.from(this).areNotificationsEnabled() },
    )
    if (!allowed) return null
    // The gate guaranteed a non-null providerId with a stored key. Construction goes through the ONE
    // factory both services share — any of the four STT adapters may be built here now.
    return SttProviderFactory.create(providerId!!, transport(), key!!)
}
```

Also flip the class KDoc (`:66`) — delete "Batch mode stays OpenAI-only this wave" and its rationale; state batch resolves the same set as live behind the same gates.

- [ ] **Step 3 — cost gate carries the provider** (`BatchEngineDecision.kt`). Replace `providerName: String?` with `providerId: ProviderId?` (the triad only needs non-null + key + disclosure; the cost step needs the id):

```kotlin
fun cloudAllowed(
    useCloud: Boolean,
    providerId: ProviderId?,
    key: String?,
    disclosureAccepted: Boolean,
    byteLength: Long,
    costConfirmed: Boolean,
    hasValidatedNetwork: () -> Boolean,
    notificationsEnabled: () -> Boolean,
): Boolean {
    if (!useCloud) return false
    if (!BatchCloudGate.cloudEligible(providerId?.name, key, disclosureAccepted)) return false
    if (!hasValidatedNetwork()) return false
    if (!notificationsEnabled()) return false
    if (BatchCostEstimator.needsConfirmation(byteLength, providerId) && !costConfirmed) return false
    return true
}
```

- [ ] **Step 4 — widen `EngineUsed`** (`RecordingMeta.kt:12`). Enums serialize by NAME (never ordinal); old manifests carrying `LOCAL`/`OPENAI` still parse — no migration. Assert that explicitly (below):

```kotlin
/** Which engine produced a chunk's text. Serialized by NAME; additive members keep old manifests parsing. */
enum class EngineUsed {
    LOCAL, OPENAI, GEMINI, ELEVENLABS, SONIOX;
    companion object {
        fun fromProviderId(id: com.whispereverywhere.provider.ProviderId): EngineUsed = when (id) {
            com.whispereverywhere.provider.ProviderId.OPENAI -> OPENAI
            com.whispereverywhere.provider.ProviderId.GEMINI -> GEMINI
            com.whispereverywhere.provider.ProviderId.ELEVENLABS -> ELEVENLABS
            com.whispereverywhere.provider.ProviderId.SONIOX -> SONIOX
        }
    }
}
```

- [ ] **Step 5 — record the real provider** in `BatchTranscriber`. Compute the cloud engine once (`transcribe()`, near `:119`): `val cloudEngine = cloud?.let { EngineUsed.fromProviderId(it.id) }`. Replace the two hardcodes:
  - `:161` `r.text to EngineUsed.OPENAI` → `r.text to cloudEngine!!` (non-null whenever `effectiveCloud != null`).
  - `:213` `usedCloud -> EngineUsed.OPENAI` → `usedCloud -> cloudEngine!!`. Mixed (`usedCloud && usedLocal`) still collapses to `LOCAL` (at least one chunk stayed on-device) — unchanged.

- [ ] **Step 6 — screen mirrors `sttSelectableProviders`** (`BatchTranscribeScreen.kt`). `cloudEligible` already routes through `resolveBatchSttProvider` (now wide) — no predicate change, but capture the id/name/rate so the row and dialog stop hardcoding "OpenAI":

```kotlin
val batchProvider = remember {                       // the resolved batch STT provider, or null
    resolveBatchSttProvider(WhisperEverywhereApp.getInstance().preferencesManager.sttProviderId)
}
val providerName = batchProvider?.let { ProviderCatalog.byId(it).displayName } ?: ""
val providerCents = BatchCostEstimator.centsPerMinute(batchProvider)
```

Cloud `EngineRow` (`:277-278`): `title = providerName`, `subtitle = "about ¢${providerCents}/min"`. Cost dialog (`:226-228`): `"…for about ${formatCents(pc.cents)} with your $providerName key?"`. `beginJob`/`PendingConfirm` pass `batchProvider` into `estimatedCents`/`needsConfirmation`. The KDoc comment block `:69-74` flips from the OpenAI-clamp rationale to "the row mirrors the globally selected STT provider; the service resolves the same one."

- [ ] **Step 7 — `SttProviderFactory.kt:8` comment**: delete "and — for OpenAI only — the batch service"; both services now route the full set through here.

- [ ] **Step 8 — tests** (`EngineSelectionTest.kt`): the `resolveBatchSttProvider` clamp tests (`:174-189`) **invert** — GEMINI/ELEVENLABS/SONIOX now resolve to their own `ProviderId`, only garbage/`null` → null. `batchScreenCloudEligible` (`:197-213`) now lights for all four with key+disclosure. `BatchTranscriberTest`: keep `cloud_happy_path…marks_openai` (still valid — it uses `OpenAiStt`), and ADD per-provider happy-paths asserting `engineUsed == GEMINI/ELEVENLABS/SONIOX` (construct each adapter over a `FakeHttpTransport` scripted to its success body; Soniox uses the upload→create→poll→fetch→delete script + `FakeHttpTransport.deletedUrls`). Add a manifest-compat test: a JSON manifest string with `"engineUsed":"OPENAI"` and one with `"LOCAL"` both still deserialize (no migration).

---

## Task 5: The two release-audit Minors

### A) Reconnect scheduler leak (`FloatingBubbleService` ~`:645`)

`ExecutorReconnectScheduler` (`RealtimeTransport.kt:344-352`) owns a `newSingleThreadScheduledExecutor` daemon ("realtime-reconnect") but exposes no shutdown; `onDestroy` only nulls the field. The `fun interface ReconnectScheduler` has only `schedule` — adding `shutdown()` to it would break the SAM lambdas tests use, so keep it **concrete on the class**.

- [ ] **Step 1** — add to `ExecutorReconnectScheduler`:

```kotlin
) : ReconnectScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit) {
        exec.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    }
    /** Stop the daemon executor. onDestroy must call this or the "realtime-reconnect" thread leaks. */
    fun shutdown() { exec.shutdownNow() }
}
```

- [ ] **Step 2** — in `FloatingBubbleService.onDestroy`, shut down **before** nulling:

```kotlin
(liveReconnectScheduler as? ExecutorReconnectScheduler)?.shutdown()
liveReconnectScheduler = null
```

- [ ] **Step 3** — test (`ExecutorReconnectSchedulerTest.kt`, new): inject a fake `ScheduledExecutorService` recording `shutdownNow()`; assert `schedule` delegates and `shutdown()` calls `shutdownNow()` exactly once.

### B) Stale INTERNET-permission manifest comment (`AndroidManifest.xml:12-13`)

- [ ] Rewrite the 3.2.0-era comment (still claims "download the on-device model … one-time. No audio or transcription is ever sent") to the honest 3.3.0 reality:

```xml
<!-- Internet: (1) one-time download of the on-device speech model from Hugging Face;
     (2) OPTIONAL, user-keyed cloud STT / TTS / live transcription when the user configures a
     provider and accepts the disclosure. Nothing is ever sent to us; on-device is the default. -->
<uses-permission android:name="android.permission.INTERNET" />
```

---

## Task 6: Doc / comment flips + ledger

- [ ] **`BatchCloudGate.kt:5` KDoc** — "disclosure v2" → **v3** (the inherited gate is v3; comment drift). Also fix the same "disclosure v2" phrase if it recurs in `resolveCloud`'s gate KDoc.
- [ ] **`PLAY-DECLARATIONS.md:216-218`** — flip the compliance line:

```
- **Batch cloud STT now offers all four providers** (OpenAI, Gemini, ElevenLabs, Soniox) — the
  same set as live dictation, behind the identical triad + disclosure v3 + cost confirm +
  notifications, per job. The batch screen's cloud row and price reflect the GLOBALLY SELECTED
  STT provider (not always OpenAI). The §5/§6 recipient narrative already names all four for
  dictation — batch now uses the same recipients, so no recipient-list change is needed; confirm
  the batch sentence no longer implies OpenAI-only.
```

- [ ] **`PLAY-DECLARATIONS.md` ledger** — add:

```
**Release ledger — Batch all-providers + VAD bypass (2026-07-31):** batch cloud STT widened from
OpenAI-only to all four providers (identical gates); per-provider chunk ceilings (from each
adapter's base64-aware maxRequestBytes) and per-provider "about" pricing (UNKNOWN -> most-expensive-
known for the confirm decision only). The batch on-device path now bypasses the Silero VAD so a
user-chosen file is transcribed in full; LIVE dictation's VAD is unchanged. No new recipient, no
disclosure-version change (same audio-to-a-provider meaning under v3). Two audit Minors folded in:
reconnect-scheduler shutdown; honest 3.3.0 INTERNET manifest comment.
```

- [ ] **Historical plan docs** (`2026-07-29-batch-transcription-mode.md`, `2026-07-30-c2b-…`, `2026-07-30-soniox-provider.md`) carry dated "batch stays OpenAI-only this wave" caveats that were TRUE when written. Do **not** rewrite the history; append a one-line pointer at each cited caveat: `> Superseded 2026-07-31 by docs/superpowers/plans/2026-07-31-batch-all-providers.md (batch now all-providers).` Grep-verify none read as a live constraint after the sweep: `grep -rin "batch.*openai-only\|openai-only.*batch" app docs`.

---

## Self-review (inline)

- **VAD scope is exactly batch.** Only `runLocal` gets `useVad=false`; `runLocalSliced` routes through it; live's default is `true`. `VadPlumbingTest` pins both directions. No native/CMake touch — the recon proved the seam; nothing is device-verification-required.
- **Energy gate untouched.** The no-VAD peak gate (`whisper_jni.cpp:194-202`) still nukes a fully-silent chunk. Acceptable and intended ("some gate" per the native comment); not removed.
- **Gates preserved, tightened not loosened.** Same triad/disclosure v3/notifications/cost-confirm order in `BatchEngineDecision`; the only change is the cost rate becoming per-provider and the OpenAI rate rising 0.45→0.60, which asks *sooner*. UNKNOWN (Gemini) is priced at the dearest known rate — never under-warns, and does NOT block the provider.
- **Ceiling correctness.** Batch reads `maxRequestBytes` off the resolved adapter, so Gemini's base64 math is inherited, not re-derived; `forProvider(p)+44 ≤ p.maxRequestBytes` is asserted so a chunk never trips the adapter's own `BadSegment` (which would silently fall local). ElevenLabs' 5 GB is capped to a 20 MB allocation bound.
- **`engineUsed` fidelity + compat.** Records the real provider; mixed jobs still collapse to LOCAL; additive enum + name serialization keeps old manifests parsing (asserted).
- **No new deps, OkHttp pinned, `assembleRelease` gated both sides.** Soniox batch inherits its poll budget / `NonCancellable` delete / honest progress from the adapter — no new UX.
- **Commit hygiene:** only the named files; `git add -A` never; retry once on `index.lock`.

## Verification gate (assert fresh — evidence before claims)

- [ ] `$env:JAVA_HOME=…; .\gradlew.bat --no-daemon testDebugUnitTest` green; record the fresh count (must exceed the pre-wave baseline by the new suites).
- [ ] `.\gradlew.bat --no-daemon assembleDebug assembleRelease` both green (R8).
- [ ] `grep -rin "openai-only" app docs` shows no LIVE batch constraint (only superseded-pointers / genuinely-live-mode OpenAI rows).
- [ ] Commit ONLY the files named in File Structure. Message notes: batch all-providers, batch-only VAD bypass, two Minors; no speed claims.
