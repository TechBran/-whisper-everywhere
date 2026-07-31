# Soniox TTS — 4th Cloud Read-Aloud Provider

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:test-driven-development. Every task is
> RED → GREEN → REFACTOR: write the failing test first (against recorded fake HTTP bodies, never a
> live key), make it pass, self-review, commit ONLY the named files. Build gate is
> `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"; .\gradlew.bat --no-daemon …`.
> Baseline HEAD a957e71, **832 tests / 0 failures**, 3.3.0/73. `unitTests.isReturnDefaultValues =
> true`; **org.json BANNED** (kotlinx.serialization only); NO new dependencies (OkHttp pinned).

**Goal:** Make Soniox a fourth selectable cloud read-aloud voice provider, riding the existing
`TtsProvider` seam/bank exactly like OpenAI, Gemini, and ElevenLabs — same clause-bounded unit path,
same one-way Kokoro fallback, same soft-latch toast, same 24 kHz PCM contract, same protective
patterns — plus the compliance three→four enumeration. Multilingual language-switching is the
selling point, so the adapter must NOT force a language (defaults to `auto`).

**Pinned endpoint (fact-sheet 2026-07-31):** `POST https://tts-rt.soniox.com/tts`,
`Authorization: Bearer <key>`, model `tts-rt-v1`, `audio_format:"pcm_s16le"`, `sample_rate:24000` →
headerless 24 kHz 16-bit **LE** PCM (drops straight into the bank via `PcmBytes.toShortArrayLE`, no
decode, no Resampler). Voices: **{Maya (default/recommended), Adrian}** — static, small, confirmed
set (a full roster is not published; do NOT rely on `GET /v1/voices` to enumerate presets). `text`
max 5000 chars (≤220-char clause units → ~22× headroom). `speed` 0.7–1.3 (default 1.0) — Soniox DOES
take a request-time speed field (unlike ElevenLabs/Gemini), so it mirrors OpenAI: pass a coerced
speed, show NO no-speed note. Errors are JSON `{"error_code",…}`: **400** one bucket (bad params /
unknown voice / unknown model → primary MODEL_UNAVAILABLE mapping rides 400, note the shared bucket),
**401** bad key, **402** payment → **OUT_OF_CREDIT** (STT taxonomy — NOT 404), **403** session
expired, **408** timeout, **429** rate, **5xx** transient. Keep a defensive **404 →
MODEL_UNAVAILABLE** guard even though the primary mapping is 400/402.

## Global Constraints (this wave)

- Reuse everything provider-agnostic: `TtsProvider` seam, `PcmBytes.toShortArrayLE`,
  `TtsProviderFactory`, `TtsController.resolveTtsProvider`/latch/toast, `ttsCloudVoiceKey` prefs,
  `selectionAfterKeyRemoval`. The single gate that unlocks the whole engine for Soniox is
  `supportsTts=true` + membership in `TTS_CAPABLE_PROVIDERS` (recon §1, §12).
- Key comes from the EXISTING Soniox account entry (Bearer header already configured,
  `ProviderCatalog.kt:89–90`). NO new key UI.
- `language` defaults to `auto` and is never hard-pinned (multilingual selling point). `auto` is NOT
  documented for TTS generate → attempt `auto`, and on a 400 whose body names the language field,
  retry the SAME unit once with a default code (`en`). Verify against the owner's live key before
  ship; do NOT block the plan on it (the fallback keeps it honest either way).
- Prefer `pcm_s16le` @ 24000 (no decode/resample). mp3-style fallback is NOT built — PCM is genuinely
  available, so `context` is NOT threaded to `SonioxTts` (unlike ElevenLabs).
- Undecodable/unparseable body → fail THIS unit (`TtsError.BadUnit`) → engine re-synthesizes locally
  on Kokoro (never silent skip). Status-code + unitLen logging ONLY — never key, headers, or text.
- Branch `feature/on-device-whisper`; commit ONLY named files; never `git add -A`; retry once on
  index.lock. Commit trailer (Co-Authored-By + Claude-Session) on every commit.

---

### Task 1: `SonioxTts` adapter + static voice catalog (RED → GREEN)

**Files:**
- Create: `app/src/main/java/com/whispereverywhere/tts/cloud/SonioxTts.kt`
- Create: `app/src/test/java/com/whispereverywhere/tts/cloud/SonioxTtsTest.kt`

**Interfaces produced:** `class SonioxTts(transport, apiKey, model=DEFAULT_MODEL, defaultLanguage="en")
: TtsProvider` with `sampleRate = 24_000`; `object SonioxTtsVoices { val ALL: List<CloudVoice> }`.

**Consumes:** `HttpTransport.postForBytes` (recorded fakes in test — never a live key), `HttpResultBytes`,
`PcmBytes.toShortArrayLE`, `FatalKind`, `ProviderCatalog.byId(SONIOX)` for the Bearer header.

**Step 1 (RED):** `SonioxTtsTest` mirroring the sibling rigor bar (recon §13). Assert, against a
`FakeHttpTransport` returning recorded bodies:
- `sampleRate == 24_000` (the bank's hard contract; recon §3).
- Blank unit → `Failed(BadUnit)` with NO request made (mirrors OpenAI `:35`).
- Happy path: `Ok(pcm bytes)` → `onPcm` called with `PcmBytes.toShortArrayLE(bytes)`; `onPcm`
  returning false → `Cancelled`; true → `Done`.
- **Request shape** captured from the fake: JSON body has `model=="tts-rt-v1"`,
  `audio_format=="pcm_s16le"`, `sample_rate==24000`, `voice==<voiceId>`, `text==<unit>`,
  `language=="auto"` (the default — NOT forced), `speed` coerced into `0.7..1.3`; URL ==
  `https://tts-rt.soniox.com/tts`; header `Authorization: Bearer <key>`.
- **Language auto-fallback:** first `POST` (language `auto`) → HTTP 400 whose body names the language
  field → adapter retries the SAME unit ONCE with `language==defaultLanguage` ("en") and succeeds;
  assert exactly two requests and the second's language. A 400 that does NOT name language →
  `Failed(BadUnit)`, ONE request only (no wasted retry).
- **Error taxonomy** via `classify`: 401 → `Fatal(INVALID_KEY)`; 403 → `Fatal(FORBIDDEN)`; 402 →
  `Fatal(OUT_OF_CREDIT)`; 429 → `Transient` (bare) / `Fatal(OUT_OF_CREDIT)` when body carries a quota
  marker; 408 & 5xx → `Transient`; 404 → `Fatal(MODEL_UNAVAILABLE)` (defensive guard);
  a 400 body naming an unknown MODEL → `Fatal(MODEL_UNAVAILABLE)` (primary mapping; note shared bucket).
- **Oversized guard:** a unit > 5000 chars → `Failed(BadUnit)` with NO request (don't spend a request
  the server will 400).
- **Undecodable/short body:** an odd-length byte body decodes via `toShortArrayLE` (trailing byte
  dropped) → still `Done`; a `NetworkError` → `Failed(Offline)`.
- **Voice catalog:** `SonioxTtsVoices.ALL.size == 2`, ids exactly `["Maya","Adrian"]`,
  `displayName == voiceId`, `Maya.recommended == true` && `Adrian.recommended == false`.
- **No-leak:** capture the `Log` tag args in the fake and assert the classify log carries only the
  status code + unitLen — never the key, header, body, or unit text.

**Step 2 (GREEN):** implement `SonioxTts.kt` (complete):

```kotlin
package com.whispereverywhere.tts.cloud

import com.whispereverywhere.net.HttpResultBytes
import com.whispereverywhere.net.HttpTransport
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.cloud.FatalKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Soniox real-time speech synthesis via `tts-rt-v1`. One JSON POST per clause-bounded unit to
 * `tts-rt.soniox.com/tts`, `Authorization: Bearer` (same header family as Soniox STT — the existing
 * account key). `audio_format=pcm_s16le` @ `sample_rate=24000` returns a HEADERLESS 24 kHz 16-bit
 * little-endian mono body that drops straight into the bank via [PcmBytes.toShortArrayLE] — no
 * decoder, no Resampler, so no [android.content.Context] is threaded here (unlike ElevenLabs' mp3
 * tier).
 *
 * MULTILINGUAL is the selling point, so [language] is NEVER hard-pinned: the request defaults to
 * "auto". "auto" is not documented for the TTS generate endpoint, so a 400 that names the language
 * field triggers ONE retry of the same unit with [defaultLanguage] — the adapter degrades to a code
 * rather than forcing one up front. (Verify "auto" acceptance against the owner's live key; the
 * fallback keeps every other language honest meanwhile.)
 *
 * Soniox DOES expose a request-time `speed` (0.7–1.3), so unlike ElevenLabs/Gemini it honors [speed]
 * (coerced) — it mirrors OpenAI, and the UI shows no "no speed control" note. No speed CLAIM is made
 * anywhere in the copy.
 *
 * NEVER log the key, the headers, or the unit text — unit LENGTH and status codes only.
 */
class SonioxTts(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val defaultLanguage: String = FALLBACK_LANGUAGE,
) : TtsProvider {

    override val id = ProviderId.SONIOX
    override val sampleRate = 24_000

    override suspend fun synth(
        unit: String,
        voiceId: String,
        speed: Float,
        onPcm: (ShortArray) -> Boolean,
    ): TtsResult {
        // Fail locally rather than spending a request on an empty or over-long clause. 5000 is the
        // documented text max; a ≤220-char clause never trips it, but guard defensively.
        if (unit.isBlank()) return TtsResult.Failed(TtsError.BadUnit)
        if (unit.length > MAX_TEXT) return TtsResult.Failed(TtsError.BadUnit)

        // Attempt 1: language "auto" (the multilingual default — never forced).
        return when (val r = request(unit, voiceId, speed, LANGUAGE_AUTO)) {
            is HttpResultBytes.NetworkError -> TtsResult.Failed(TtsError.Offline)
            is HttpResultBytes.Ok -> deliver(PcmBytes.toShortArrayLE(r.bytes), onPcm)
            is HttpResultBytes.HttpError ->
                // "auto" is undocumented for generate: a 400 that names the language field means this
                // host rejected "auto" — retry ONCE with a concrete code rather than forcing one up
                // front. Any other 400 (and every other status) classifies normally, no wasted retry.
                if (r.code == 400 && LANGUAGE_MARKERS.any { r.body.contains(it, ignoreCase = true) }) {
                    when (val r2 = request(unit, voiceId, speed, defaultLanguage)) {
                        is HttpResultBytes.NetworkError -> TtsResult.Failed(TtsError.Offline)
                        is HttpResultBytes.Ok -> deliver(PcmBytes.toShortArrayLE(r2.bytes), onPcm)
                        is HttpResultBytes.HttpError ->
                            TtsResult.Failed(classify(r2.code, r2.body, unit.length))
                    }
                } else {
                    TtsResult.Failed(classify(r.code, r.body, unit.length))
                }
        }
    }

    private suspend fun request(
        unit: String,
        voiceId: String,
        speed: Float,
        language: String,
    ): HttpResultBytes {
        val provider = ProviderCatalog.byId(ProviderId.SONIOX)
        val headers = mapOf(provider.authHeaderName to provider.authHeaderValue(apiKey))
        val body = JSON.encodeToString(
            SonioxTtsReq.serializer(),
            SonioxTtsReq(
                model = model,
                language = language,
                voice = voiceId,
                audio_format = AUDIO_FORMAT,
                text = unit,
                sample_rate = 24_000,
                speed = speed.coerceIn(MIN_SPEED, MAX_SPEED),
            ),
        )
        return transport.postForBytes(ENDPOINT, headers, body)
    }

    private fun deliver(shorts: ShortArray, onPcm: (ShortArray) -> Boolean): TtsResult =
        if (!onPcm(shorts)) TtsResult.Cancelled else TtsResult.Done

    private fun classify(code: Int, body: String, unitLength: Int): TtsError {
        // STATUS CODE + unit LENGTH only — never the body or the unit text (class doc / global rule).
        android.util.Log.w("WE-DIAG", "soniox tts http $code unitLen=$unitLength")
        return when (code) {
            401 -> TtsError.Fatal(FatalKind.INVALID_KEY, "Key rejected")
            // 403 = temporary API-key session expired; treat as a forbidden/key fault, not transient.
            403 -> TtsError.Fatal(FatalKind.FORBIDDEN, "Access denied for this key")
            // Payment is 402 on this host (NOT 404) — matches Soniox STT's OUT_OF_CREDIT taxonomy.
            402 -> TtsError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            // Soniox folds unknown-voice AND unknown-model into one 400 bucket (docs carve no
            // distinct code). A model marker → MODEL_UNAVAILABLE (parity with the other adapters'
            // model-retirement latch); otherwise a malformed unit. NB: an "auto"-language 400 is
            // caught in synth() before it ever reaches here.
            400 -> if (MODEL_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                TtsError.Fatal(FatalKind.MODEL_UNAVAILABLE, "Speech synthesis model unavailable")
            } else {
                TtsError.BadUnit
            }
            // No documented 404→MODEL_UNAVAILABLE on this host, but keep the guard defensively so a
            // future host change can't silently misclassify a retired model as a generic transient.
            404 -> TtsError.Fatal(FatalKind.MODEL_UNAVAILABLE, "Speech synthesis model unavailable")
            408 -> TtsError.Transient(null)
            429 -> if (QUOTA_MARKERS.any { body.contains(it, ignoreCase = true) }) {
                TtsError.Fatal(FatalKind.OUT_OF_CREDIT, "Account has no remaining credit")
            } else {
                TtsError.Transient(null)
            }
            in 500..599 -> TtsError.Transient(null)
            else -> TtsError.Transient(null)
        }
    }

    companion object {
        /** Pinned against live docs 2026-07-31. The single model constant for this adapter. */
        const val DEFAULT_MODEL = "tts-rt-v1"

        private const val ENDPOINT = "https://tts-rt.soniox.com/tts"
        private const val AUDIO_FORMAT = "pcm_s16le" // raw signed 16-bit LE @ 24 kHz — no decode
        private const val MAX_TEXT = 5000            // documented text max; clause units clear it ~22×

        private const val LANGUAGE_AUTO = "auto"     // multilingual default — never forced
        private const val FALLBACK_LANGUAGE = "en"   // used only if the host rejects "auto"

        // Soniox's documented speed range; values outside it 400.
        private const val MIN_SPEED = 0.7f
        private const val MAX_SPEED = 1.3f

        private val JSON = Json { encodeDefaults = true } // kotlinx, NOT org.json (banned)

        private val LANGUAGE_MARKERS = listOf("language", "\"language\"", "lang")
        private val MODEL_MARKERS = listOf("model", "\"model\"")
        private val QUOTA_MARKERS = listOf("insufficient", "quota", "balance", "credit")
    }
}

@Serializable
private data class SonioxTtsReq(
    val model: String,
    val language: String,
    val voice: String,
    val audio_format: String,
    val text: String,
    val sample_rate: Int,
    val speed: Float,
)

/**
 * The confirmed built-in Soniox voices (live docs 2026-07-31): Maya (the documented default, used in
 * all primary examples) and Adrian. A full roster is NOT published and `GET /v1/voices` lists only
 * project/cloned voices, so this is a deliberate static set of the two confirmed presets — mirroring
 * Gemini's static shape (displayName == the literal voiceId the request expects). Maya is flagged
 * recommended (the documented default), not a speed or quality claim about Adrian.
 */
object SonioxTtsVoices {
    private val RECOMMENDED = setOf("Maya")

    val ALL: List<CloudVoice> = listOf("Maya", "Adrian").map { name ->
        CloudVoice(
            providerId = ProviderId.SONIOX,
            voiceId = name,
            displayName = name,
            recommended = name in RECOMMENDED,
        )
    }
}
```

**Step 3 (self-review + commit):** confirm no `context`, no `org.json`, no key/text logged, speed
coerced, `auto` default, oversized guard, 402/404 mapping. Run the new suite green.
Commit: `feat(tts): SonioxTts adapter + static Maya/Adrian voices` (files: `SonioxTts.kt`,
`SonioxTtsTest.kt`).

---

### Task 2: Catalog flip + factory + picker + formatters + prefs mirror (RED → GREEN)

**Files:**
- Modify: `app/src/main/java/com/whispereverywhere/provider/ProviderCatalog.kt`
- Modify: `app/src/main/java/com/whispereverywhere/tts/cloud/TtsProviderFactory.kt`
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/EnginesAndVoicesScreen.kt`
- Modify: `app/src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt`
- Modify: `app/src/test/java/com/whispereverywhere/provider/ProviderCatalogTest.kt`
- Modify: `app/src/test/java/com/whispereverywhere/tts/cloud/TtsProviderFactoryTest.kt`

**Step 1 (RED — flip the guard tests first):**
- `ProviderCatalogTest.kt:62` `assertFalse(… s.supportsTts)` → `assertTrue`; add an assertion that
  `TTS_CAPABLE_PROVIDERS == setOf(OPENAI, GEMINI, ELEVENLABS, SONIOX)`.
- `TtsProviderFactoryTest.kt:40–45` set assertion gains `SONIOX`; the `:33–37` loop iterating
  `TTS_CAPABLE_PROVIDERS` asserting `create(id,…).sampleRate == 24_000` now covers Soniox (satisfied
  by Task 1). Add `soniox_id_maps_to_the_soniox_adapter` mirroring `:20–30` (asserts
  `create(SONIOX, …) is SonioxTts`).

**Step 2 (GREEN — production edits):**

- `ProviderCatalog.kt`: `:98` `supportsTts = false` → `true` (drop the "STT-only this wave" comment;
  add "TTS ships this wave via SonioxTts — tts-rt-v1, pcm_s16le @ 24 kHz"). `:123–124`
  `TTS_CAPABLE_PROVIDERS` gains `ProviderId.SONIOX`; update the doc line "All three" → "All four".

- `TtsProviderFactory.kt:33`: replace the `error(...)` with `ProviderId.SONIOX -> SonioxTts(transport,
  apiKey)`. `context` is deliberately NOT passed (Soniox has no mp3 tier); update the class doc's
  "[context] is threaded only because [ElevenLabsTts]…" note to name Soniox among the context-free
  adapters (OpenAI, Gemini, Soniox).

- `SettingsScreen.kt`:
  - `ttsProviderPriceNote` `:71–72` `SONIOX ->` becomes the honest price:
    `"About \$0.70 per hour of audio (about 1.2¢ per minute), billed to your Soniox key."` — no speed
    claim (fact-sheet §6, the ~$0.70/hr "about" hourly framing; per-minute ≈1.2¢/min).
  - `ttsNoSpeedControlNote` `:85` `SONIOX -> null` STAYS null — Soniox HAS a request-time speed field
    (0.7–1.3), so it honors the speed setting like OpenAI; no "no speed control" note is shown (add a
    one-line comment recording that determination so a later reader doesn't "fix" it into the
    ELEVENLABS/GEMINI branch).
  - `cloudVoiceDisplayName` `:104` `SONIOX -> emptyList()` → `SonioxTtsVoices.ALL` (static, powers the
    home/mode chip fallback like Gemini's `:102`).

- `EnginesAndVoicesScreen.kt`:
  - Static-catalog `when` `:440` `ProviderId.SONIOX -> null` → `SonioxTtsVoices.ALL` (static — the
    lower-risk fit; the set is fixed and small, so NO dynamic fetch / spinner / error-row wiring is
    added, unlike ElevenLabs `:454–499`). The existing `TtsCloudVoiceRow` `:734`, preview
    (`previewCloudVoice` `:336`), select (`selectCloudVoice` `:330`), and the price/preview/no-speed
    caption block `:412–433` are all provider-agnostic and light up for Soniox automatically once the
    catalog returns non-null and `TTS_CAPABLE_PROVIDERS` admits it.
  - No edit to the `LaunchedEffect` `:309–327` (ElevenLabs-only dynamic fetch — Soniox is static).

- **Prefs + key-removal mirror (no code, record the determination):** `ttsCloudVoiceKey`
  (`TtsController.kt:172`, name-namespaced) and `selectionAfterKeyRemoval` (`CloudProvidersScreen.kt:138`,
  TTS branch `EnginesAndVoicesScreen.kt:246–263`) are provider-agnostic over `ProviderId.entries` —
  Soniox is covered for FREE. `PreferencesTtsCloudTest.kt:42–58` already asserts
  `tts_cloud_voice_SONIOX`; `CloudProvidersScreenLogicTest.kt:311–379` already loops all ids. Confirm
  both suites stay green — no new prefs code.

- **Chip + latch/toast:** `EnginesAndVoicesScreen.kt:203–206` chip string and the whole
  `TtsController.applyCloudVoice`/`onCloudFallback`/`onCloudSoftLatch` latch path are agnostic; the
  single gate `resolveTtsProvider` `.takeIf { it in TTS_CAPABLE_PROVIDERS }` (`TtsController.kt:163`)
  now admits Soniox from the Task-2 catalog flip. No per-provider edit.

**Step 3 (self-review + commit):** run `ProviderCatalogTest`, `TtsProviderFactoryTest`,
`PreferencesTtsCloudTest`, `CloudProvidersScreenLogicTest` green. Commit:
`feat(tts): wire Soniox into factory, picker, price + chip` (files: the six above).

---

### Task 3: Compliance — three→four enumeration + no-bump determination + ledger

**Files:**
- Modify: `docs/PLAY-DECLARATIONS.md`
- Modify: `docs/privacy.html`
- Modify: `app/src/main/assets/privacy_policy.html`

The determination (record it, don't over-edit): Soniox joins a data class that is ALREADY declared —
"Other user-generated content → text you select for read-aloud", Shared = Yes, Optional
(`PLAY-DECLARATIONS.md:143–152, 214–219`). A same-class new recipient means **NO new Data Safety
type, NO disclosure-version bump, NO re-prompt** — exactly the call already made for the Soniox STT
recipient (`:106–118`). Only the recipient ENUMERATION grows.

**Step 1 — PLAY-DECLARATIONS.md (the one hard enumeration edit + ledger):**
- `:224–226` §7 checklist: "and the three TTS voice providers (OpenAI, Google Gemini, ElevenLabs)" →
  "**and the four TTS voice providers (OpenAI, Google Gemini, ElevenLabs, Soniox)**".
- The ephemeral-exemption recipient list `:156` already names Soniox — no edit (it was already a
  read-aloud-eligible recipient enumeration).
- Add a release-ledger entry (pattern `:106–118` / `:128–136`):
  > **Release ledger — Soniox TTS voice provider (2026-07-31):** Soniox becomes a fourth cloud
  > read-aloud recipient (`tts-rt.soniox.com`, `tts-rt-v1`, pcm_s16le @ 24 kHz) behind the identical
  > key + cloud-voice-selection + disclosure-v3 triad. **Determination: SAME data class already
  > declared — "Other user-generated content → text you select for read-aloud", Shared = Yes,
  > Optional — to one additional recipient.** NO new Data Safety class, NO new shared type, NO
  > disclosure-version bump, NO re-prompt (same read-aloud-text-to-a-provider meaning). The recipient
  > set grows, so both privacy §6 copies and the §7 checklist now enumerate four TTS providers.
  > Soniox's TTS-input-text stance: its policy says inputs are "never used to improve Soniox models
  > or services" and real-time (`tts-rt`) is not the async/storage path, so read-aloud text is not
  > retained — the wording is audio/transcript-centric, disclosed honestly on the privacy line.
  > Ledger entry: **Soniox TTS: fourth read-aloud recipient, same UGC-read-aloud class, no new class,
  > v3 unchanged; Console narrative names four TTS providers.**

**Step 2 — privacy §6, BOTH copies (must stay byte-identical):** the read-aloud paragraph (`:104`) is
already GENERIC ("a cloud voice for a provider you have configured") and Soniox is already in the
key-add list (`:101`) and the training-stance bullet (`:112`). So the ONLY change is making the §6
training/retention bullet honest for read-aloud TEXT (its existing Soniox line is audio-worded).
Apply the SAME diff to `docs/privacy.html` AND `app/src/main/assets/privacy_policy.html` (the
`app/build/intermediates/.../privacy_policy.html` copy is a build artifact — ignore it). Paste the
identical inserted clause into both, e.g. append to Soniox's stance line:
`"— and, for read-aloud, Soniox states inputs are not used to improve its models and real-time
synthesis is not the stored async path, so the text you have read aloud is not retained (Soniox's
wording is audio/transcript-centric; treated as covered)."`

**Step 3 — verify byte-identity + commit:** diff the two privacy copies to prove they match
(`fc` / `git diff --no-index`), confirm the build-artifact copy is untouched. Commit:
`docs(compliance): Soniox as 4th TTS recipient — enumeration + no-bump ledger`
(files: `docs/PLAY-DECLARATIONS.md`, `docs/privacy.html`, `app/src/main/assets/privacy_policy.html`).

---

### Task 4: Final gate

- [ ] Full unit suites + lint + `assembleRelease` AND `bundleRelease` (R8 — the adapter's kotlinx
  DTOs and `encodeDefaults` must survive shrink). Test count ≥ 832 + the new SonioxTts cases, 0
  failures.
- [ ] Read-only reviewer subagent on the whole diff; fix Critical/Important.
- [ ] Instrumented = compile-check ONLY (never `connectedAndroidTest`/`installDebug`).
- [ ] Owner on-device acceptance (Task #24): pick a Soniox voice → preview spends the key → read a
  multilingual selection → confirm `auto` works on the live key (or the `en` fallback fires cleanly),
  402/soft-latch toast path, one-way Kokoro fallback on an induced failure. NO live verification until
  the owner tests — every protective pattern ships regardless.

## Self-review (inline)

- **Speed:** fact-sheet §1 gives Soniox a `speed` 0.7–1.3 field — so Soniox is OpenAI-shaped (honors
  speed, `ttsNoSpeedControlNote → null`), NOT ElevenLabs/Gemini-shaped. Recon §6's "move to the
  no-speed branch" was conditional on there being no speed field; there IS one, so it stays null.
  "No speed claims" (a marketing-copy rule) is still honored — the price note makes no speed claim.
- **404 vs 402:** primary payment mapping is 402→OUT_OF_CREDIT (fact-sheet §5); the 404→
  MODEL_UNAVAILABLE guard is kept defensively but the real model-unavailable signal is a 400 model
  marker — both covered in `classify`, both tested.
- **auto language:** not directly satisfiable from published fields (the one flagged risk). The
  attempt-auto→fallback-on-400 design keeps the multilingual promise without forcing a language and
  without a wasted retry on non-language 400s; owner live-key verification is the acceptance gate,
  not a plan blocker.
- **No context / no mp3 / no Resampler:** PCM is genuinely available (`pcm_s16le`), so none of the
  ElevenLabs mp3 machinery is forked in — the simplest correct path (OpenAI-shaped).
- **Compliance:** exactly one hard enumeration edit (§7 three→four); everything else is a recorded
  no-bump determination + ledger + one honest read-aloud-text clause pasted identically into both
  privacy copies. No Data Safety change.
- **Free inheritance:** prefs key, key-removal mirror, chip, latch, toast, preview, captions are all
  provider-agnostic and already test-covered for `ProviderId.entries` — Task 2 only flips the gate.
