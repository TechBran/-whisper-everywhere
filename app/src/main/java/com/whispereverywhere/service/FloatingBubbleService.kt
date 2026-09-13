package com.whispereverywhere.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.whispereverywhere.MainActivity
import com.whispereverywhere.R
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.audio.EndpointCut
import com.whispereverywhere.audio.Endpointer
import com.whispereverywhere.audio.EndpointerFactory
import com.whispereverywhere.audio.SileroEndpointer
import com.whispereverywhere.audio.StartupRing
import com.whispereverywhere.audio.StartupSeam
import com.whispereverywhere.net.ConnectivityMonitor
import com.whispereverywhere.net.OkHttpTransport
import com.whispereverywhere.npu.NpuDiag
import com.whispereverywhere.npu.NpuTierStatus
import com.whispereverywhere.provider.ProviderCatalog
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.text.TextJoin
import com.whispereverywhere.transcription.LocalWhisperEngine
import com.whispereverywhere.transcription.NpuBackendSelector
import com.whispereverywhere.transcription.SegmentOutcome
import com.whispereverywhere.transcription.SpeechEvidence
import com.whispereverywhere.transcription.TranscriptionEngine
import com.whispereverywhere.transcription.cloud.CloudTranscriptionEngine
import com.whispereverywhere.transcription.cloud.FallbackTranscriptionEngine
import com.whispereverywhere.transcription.cloud.SttProviderFactory
import com.whispereverywhere.ui.components.BarWaveformView
import com.whispereverywhere.util.StreamingAudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which engine a session should use, decided from three inputs that are otherwise entangled with
 * Android services (SharedPreferences, SecureStore, ConnectivityManager). Kept pure and free of
 * Context so the branch table itself is unit-testable without Robolectric — see
 * `EngineSelectionTest`.
 *
 * On-device is the default AND the only reachable outcome when [decideEngineChoice] is given a
 * null `sttProviderId` — that is the one-way valve: no combination of `hasKey` /
 * `hasValidatedNetwork` can select cloud without a provider having been explicitly chosen first,
 * and there is no path back from [CLOUD_WITH_FALLBACK] to itself once local has taken over — the
 * fallback happens INSIDE [FallbackTranscriptionEngine], never by re-selecting here.
 */
internal enum class EngineChoice { LOCAL_ONLY, LOCAL_NO_KEY, LOCAL_OFFLINE, CLOUD_WITH_FALLBACK, CLOUD_LIVE }

/**
 * Decision table (brief: Release C2a Task 6; C4 added the live-streaming leaf; the realtime
 * all-providers wave widened it from OpenAI-only to every streaming-capable provider):
 *  - no provider selected                       -> local only (unchanged, pre-cloud path)
 *  - provider selected, no key                  -> local, one-time toast
 *  - provider selected, key, offline             -> local, one-time toast
 *  - provider selected, key, online              -> cloud batch POST, wrapped in the local fallback
 *  - + live flag on AND provider is realtime-capable -> cloud LIVE stream, wrapped in the SAME fallback
 *
 * That last leaf is now the ORDINARY path, not the exception: `sttLiveMode` defaults to true, so a
 * user who picks OpenAI, ElevenLabs or Soniox and pastes a key streams word-for-word immediately.
 * Batch remains one toggle away. Gemini (live since 4.3.4) reads the same way since the owner's
 * 2026-09-10 ruling — a stored Gemini key streams too — on its own preference rather than the
 * shared one ([liveModeFor]), which is what lets its opt-out stand alone.
 *
 * The live leaf sits AFTER the local guards on purpose: the one-way valve is untouched, so no key
 * or no network still resolves to on-device — live never opens a socket the batch path would have
 * refused. That ordering is what makes the true default safe, and EngineSelectionTest now passes
 * `liveMode = true` through every local-guard case to pin it. Live is realtime-capable-provider-only
 * because only those providers have a native BYOK realtime WebSocket behind a
 * [com.whispereverywhere.transcription.live.RealtimeProtocol] — all four since 4.3.4.
 */
internal fun decideEngineChoice(
    sttProviderId: String?,
    hasKey: Boolean,
    hasValidatedNetwork: Boolean,
    /**
     * No default, deliberately. It used to default to false, which stopped being truthful when
     * `sttLiveMode` began defaulting to TRUE: a caller that omitted the axis would silently decide
     * batch for a user whose preference says stream. Every caller must state it.
     */
    liveMode: Boolean,
): EngineChoice = when {
    sttProviderId == null -> EngineChoice.LOCAL_ONLY
    !hasKey -> EngineChoice.LOCAL_NO_KEY
    !hasValidatedNetwork -> EngineChoice.LOCAL_OFFLINE
    liveMode && isRealtimeStt(sttProviderId) -> EngineChoice.CLOUD_LIVE
    else -> EngineChoice.CLOUD_WITH_FALLBACK
}

/**
 * The stored preference names a usable provider only when it matches one of the STT adapters this
 * release ships — OpenAI, Gemini, ElevenLabs, or Soniox (C2b widened the set from OpenAI-only;
 * Soniox adds a 4th). Anything else — null, a foreign provider name, a stale/corrupt value —
 * resolves to null, which
 * [decideEngineChoice] then treats exactly like "no key": a build that predates a provider's
 * adapter can never attempt a cloud call for it. [SttProviderFactory] has a mapping for every id in
 * [STT_PROVIDERS], so a non-null result is always constructible.
 */
internal fun resolveSttProvider(raw: String?): ProviderId? =
    raw?.let { runCatching { ProviderId.valueOf(it) }.getOrNull() }
        ?.takeIf { it in STT_PROVIDERS }

private val STT_PROVIDERS =
    setOf(ProviderId.OPENAI, ProviderId.GEMINI, ProviderId.ELEVENLABS, ProviderId.SONIOX)

/**
 * STT providers with a shipped BYOK realtime adapter — the [EngineChoice.CLOUD_LIVE] gate. Derived
 * from [ProviderCatalog.supportsStreaming] rather than hand-listed, so it can never drift from the
 * catalog. All four since 4.3.4 (Gemini joined behind GeminiRealtimeProtocol). Kept in lockstep
 * with `ModeDashboard.dictationLiveActive` and the CLOUD_LIVE construction in
 * [FloatingBubbleService.resolveTranscriptionEngine], which selects the matching
 * [com.whispereverywhere.transcription.live.RealtimeProtocol] for whichever id is in this set.
 */
internal val REALTIME_STT_PROVIDERS: Set<ProviderId> =
    ProviderCatalog.all.filter { it.supportsStreaming }.map { it.id }.toSet()

/**
 * The RESOLVED live flag for the selected provider — the `liveMode` axis [decideEngineChoice] takes.
 *
 * `sttLiveMode` ([sharedLive]) defaults to true and is shared by OpenAI, ElevenLabs and Soniox
 * (owner decision 2026-07-31: a streaming provider streams the moment its key is in). Gemini
 * ([geminiLive], `sttLiveModeGemini`) now reads the same way — owner ruling 2026-09-10: *"Gemini
 * Live must be the DEFAULT whenever a Gemini key is present — we shouldn't even have to select."*
 * So this function's job is no longer to hold Gemini back; it is to keep Gemini's explicit on/off
 * on its OWN preference, because a Gemini-only user could never have expressed one through the
 * shared flag (Gemini's live row did not render before 4.3.4). Both flags default true, so the
 * resolved answer for an untouched install is the same either side of the branch; the branch is
 * what lets a Gemini user opt OUT (or a three-provider user opt out) without moving the other.
 * The three other providers keep the shared flag byte-identically.
 */
internal fun liveModeFor(sttProviderIdName: String?, sharedLive: Boolean, geminiLive: Boolean): Boolean =
    if (resolveSttProvider(sttProviderIdName) == ProviderId.GEMINI) geminiLive else sharedLive

/** True when [sttProviderIdName] both resolves to a shipped adapter AND streams in real time. */
internal fun isRealtimeStt(sttProviderIdName: String?): Boolean =
    resolveSttProvider(sttProviderIdName)?.let { it in REALTIME_STT_PROVIDERS } == true

/**
 * The CONNECTING status label (3.6.0, Workstream E3). A LOCAL session whose engine still has to
 * load the model gets the honest "Loading speech model…" — naming the ~7 s cold wait — instead
 * of a bare spinner. A warm local engine, or ANY cloud session (whose CONNECTING wait is the
 * socket/handshake, not a model load), gets null: spinner only, exactly as before. Pure so the
 * branch is JVM-pinned (ConnectingLabelTest); the warm flag comes from LocalWhisperEngine.isWarm.
 */
internal fun connectingStatusLabel(isCloudSession: Boolean, localEngineWarm: Boolean): String? =
    if (!isCloudSession && !localEngineWarm) "Loading speech model…" else null

/**
 * Which engine will run a session's audio through its weights, for [sessionLanguageFor]: LOCAL is
 * the on-device engine — as the session's transcriber OR as the English-only local MIRROR that
 * rescues a cloud session's lost turns; CLOUD is a batch or live provider.
 */
internal enum class TranscribingEngine { LOCAL, CLOUD }

/**
 * The session language for one engine — the 3.8 cloud-"en" leak fix (spec §1, pinned by
 * SessionLanguageTest).
 *
 * The ENGLISH-scope override is a fact about whisper's `.en` weights (auto-detect is unreliable on
 * them, and they cannot transcribe another language anyway), so it applies ONLY to the engine that
 * runs those weights. Until 4.3.4 the service computed ONE language from the LOCAL installed
 * model's scope and fed it to every engine, so a cloud user with the English-only default `eco`
 * installed had "en" forced on the provider whatever they had chosen — and a Gemini live user with
 * `pro` installed would have sent `languageCodes:["en"]` forever. Now the cloud half of a session
 * gets the user's own [selection] (null = auto) and only the LOCAL engine — the on-device
 * transcriber, or a cloud session's local mirror — keeps the "en" pin. Mirror and cloud language
 * therefore diverge deliberately inside one fallback session; the mirror's language is mapped
 * through this function again by `FallbackTranscriptionEngine`'s `mirrorLanguage` hook.
 *
 * (4.4.1) The PREVIEWER does not take this pin at all — [localPreviewArms] reads the user's
 * selection directly, because the owner's per-language ruling makes Auto show no live words
 * whatever whisper happens to type. This function stays whisper's, and only whisper's.
 */
internal fun sessionLanguageFor(
    installedScope: com.whispereverywhere.model.ModelScope?,
    selection: String?,
    engine: TranscribingEngine,
): String? =
    if (engine == TranscribingEngine.LOCAL && installedScope == com.whispereverywhere.model.ModelScope.ENGLISH) "en"
    else selection

/**
 * Does this session get the on-device word-for-word previewer (4.4.0, spec §5)? Pure, pinned as
 * a truth table by LocalPreviewGateTest; the ONE caller is the wrap site in startRecording, which
 * logs every input on the `stream-gate:` line.
 *
 * OWNER RULING 2026-09-11, which RETIRES 4.4.0's assumed R4: [sessionLanguage] is the user's OWN
 * selection — `getLanguageForApi()`, null on Auto — and NOT whisper's resolved local language.
 * *"Now if they leave it in auto, then you get no live streaming at all. And that will seem to be
 * a very fair trade-off."* Under R4 the call site fed `sessionLanguageFor(scope, selection, LOCAL)`,
 * whose ENGLISH-scope pin turns Auto into "en" on the `.en` tiers (eco, pro) — so Auto armed for
 * the default selection on the majority local tier, which is the one route by which 4.4.1's copy
 * ("Auto-detect shows none at all") could have been false, and for a pack the acquisition side —
 * which reads the SELECTION — would never have fetched for those users. Whisper's own resolution
 * is untouched: an Auto session on eco still TYPES English, it just shows no live words.
 * RULING ASSUMED (R3): [userEnabled] defaults true in PreferencesManager.
 *
 * Cloud sessions (batch or live) keep today's strip; a running batch file job vetoes (two CPU
 * consumers beside whisper's bursts is the research's §3.9 refusal); [previewReady] is the
 * resident recognizer's `isWarmFor(thisPack)` — false while it loads, false when the resident
 * recognizer is a DIFFERENT language's, and false for the rest of the process after this pack's
 * canary fails (4.5.0 T2, defect 4: another pack's failed canary is not this one's verdict).
 *
 * ### 4.4.1's one change to this predicate: the English literal is gone
 *
 * Owner rulings 2026-09-11 make packs PER LANGUAGE and the store a SET — *"a user can have
 * multiple languages loaded onto their app"* — so `sessionLanguage == "en" && packInstalled`
 * becomes the one question those two terms were always asking: is THIS session's language one of
 * the installed pack languages? The catalogue lookup that decides which languages can be
 * installed at all lives in the set's one producer
 * ([com.whispereverywhere.transcription.stream.StreamingPackManager.installedLanguages]), so it
 * is asked once, by the object that reads the disk, and no row can be invented here.
 *
 * NO OTHER TERM CHANGED, deliberately: the owner tested this gate on device and found it correct
 * once explained, so the cloud, batch, switch and readiness vetoes read exactly as they did.
 *
 * @param installedPackLanguages the language codes whose previewer packs are installed. Empty on
 *        a device with none; a SET rather than a Boolean because installing Spanish must not
 *        remove English and a Spanish session must not arm on English's model.
 */
internal fun localPreviewArms(
    sessionLanguage: String?,
    installedPackLanguages: Set<String>,
    isCloudSession: Boolean,
    batchJobActive: Boolean,
    userEnabled: Boolean,
    previewReady: Boolean,
): Boolean =
    sessionLanguage != null && sessionLanguage in installedPackLanguages &&
        !isCloudSession && !batchJobActive && userEnabled && previewReady

/**
 * WHICH previewer pack this process should hold resident — [localPreviewArms]' twin, and the whole
 * of CONTROLLER RULING **CHANGE 5** (2026-09-11): *"the warm site takes the SAME catalogue lookup
 * as the arm site. Warm the pack for the selected language, or warm nothing."* Pure, so the two
 * gates can be held equal by a test (LocalPreviewGateTest) rather than by two readings of two
 * call sites.
 *
 * Until 4.4.1 both warm sites asked only *is the ENGLISH pack on disk* and never read the
 * language, so every user whose selection is not an installed pack language — Auto above all, the
 * owner's own habit — paid the 802-860 ms load and **+169 MB RSS** for a recognizer the gate then
 * refused on its first conjunct. The bytes bought nothing at all. This is not a re-ruling of Auto;
 * it is declining to pay for what Auto does not use.
 *
 * The three language-ish terms are exactly the arm site's: a language ([previewLanguage] — the
 * user's selection, null on Auto), that language's pack being installed, and the switch. The
 * session vetoes (cloud, batch) are NOT here, deliberately: a cloud session's warm is what arms
 * the NEXT local one, and refusing to load during one would cost that session its live words.
 * The gate's `previewReady` has no meaning here either — it is the answer this call produces.
 *
 * **(4.4.1 pass 3, ITEM 2) It is also the RELEASE condition.** A null answer means there is
 * nothing to warm for this selection — and therefore nothing that should stay resident for it, so
 * the selection collector in `onCreate` frees the recognizer on exactly this null. One function,
 * asked by both warm sites and by the release, is what keeps a release from ever disagreeing with
 * a warm and thrashing the 802-860 ms load between them.
 *
 * @return the pack to hand `StreamingPreviewEngine.warm`, or null to warm nothing at all. Never a
 *   pack for a language that is not [previewLanguage]: the catalogue is asked for that language
 *   and no other, so a warm can never load a model the gate will refuse.
 */
internal fun previewPackToWarm(
    previewLanguage: String?,
    installedPackLanguages: Set<String>,
    userEnabled: Boolean,
): com.whispereverywhere.transcription.stream.StreamingPack? {
    if (!userEnabled) return null
    if (previewLanguage == null || previewLanguage !in installedPackLanguages) return null
    return com.whispereverywhere.transcription.stream.StreamingPackCatalog.forLanguage(previewLanguage)
}

/**
 * Should a pack that JUST FINISHED INSTALLING be warmed right now? (4.5.1 Task 1 — the
 * first-session fix.) Pure, pinned as a truth table by LocalPreviewGateTest; the ONE caller is the
 * board collector in `onCreate`, which logs its answer.
 *
 * ### The defect
 *
 * > *"What can we do about having to transcribe a second time to get the live to work? … That's a
 * > friction point for users. People are going to think that it doesn't work."*
 *
 * `warmStreamingPreview` had exactly two callers — the boot prewarm and the wrap site — and the
 * wrap site's own KDoc says it *"arms NEXT session, not this one, because warm() is asynchronous
 * and the gate reads isWarmFor() now"*. So an install that completed mid-process warmed nothing
 * until a session had already started and ended: session one armed nothing, session two worked.
 * The 4.5.0 acceptance sheet recorded that miss as EXPECTED (AF6), which was the wrong call — a
 * user who picks a language, watches 73-128 MB arrive and then sees no words concludes the feature
 * is broken, and nothing in the design requires it. This function is the third trigger.
 *
 * ### It decides WHEN, and never WHICH
 *
 * **[previewPackToWarm] is the one owner of *"which pack should be resident"*** — the boot prewarm,
 * the wrap site, the release-on-selection-change collector and this all take their answer from it,
 * so no two of them can disagree and thrash the 802-860 ms load between them. This adds only the
 * three terms an EVENT has and a session does not:
 *
 *  - **the phase that landed** — [com.whispereverywhere.transcription.stream.PreviewPhase.INSTALLED]
 *    and nothing else. It is the phase whose own KDoc says *"the marker landed and the recognizer
 *    can open the install"*; every other phase has no model on disk yet, or never will.
 *  - **[sessionActive] / [batchJobActive]** — the existing refusals, inherited and not re-decided.
 *    A load beside a live session would run under the very recognizer `PreviewTeeEngine` has
 *    BORROWED, and a batch file job is the research's §3.9 refusal. Mid-trigger is SKIPPED rather
 *    than deferred, exactly like the trim re-prewarm and the model-switch collector: the next
 *    session's wrap site fills this slot, at the cost of that one session's live words.
 *  - **[residentWarmPack]** — the pack the ENGINE is warm for right now, so a repair install over a
 *    resident pack posts no second load. A skip, not a mechanism: `warm` is idempotent on the pack.
 *    Deliberately the engine's own `isWarmFor` answer and not `streamingPreviewPack`, which still
 *    names the pack after an `onTrimMemory` freed the recognizer — refusing on that field would
 *    leave the previewer cold behind a receipt promising words.
 *
 * @param installedLanguage the language of the board record that just changed phase. Compared with
 *        [previewLanguage] rather than assumed equal to it: *"never warm a language that is not the
 *        selection"*, and two packs can be arriving at once.
 * @return the pack to hand [warmStreamingPreview], or null to do nothing at all.
 */
internal fun warmOnPackInstalled(
    installedLanguage: String,
    phase: com.whispereverywhere.transcription.stream.PreviewPhase,
    previewLanguage: String?,
    installedPackLanguages: Set<String>,
    userEnabled: Boolean,
    sessionActive: Boolean,
    batchJobActive: Boolean,
    residentWarmPack: com.whispereverywhere.transcription.stream.StreamingPack?,
): com.whispereverywhere.transcription.stream.StreamingPack? {
    if (phase != com.whispereverywhere.transcription.stream.PreviewPhase.INSTALLED) return null
    if (previewLanguage == null || installedLanguage != previewLanguage) return null
    if (sessionActive || batchJobActive) return null
    val pack = previewPackToWarm(previewLanguage, installedPackLanguages, userEnabled) ?: return null
    return if (pack == residentWarmPack) null else pack
}

/**
 * The states whose elapsed ticker runs (3.6.0, Workstream E4). PROCESSING kept for the legacy
 * branch that has always owned the ticker UI; FINALIZING added so the stop-tap drain counts up
 * visibly alongside the "Finishing…" status line instead of an unchanging spinner. The ticker's
 * while-loop re-reads the live state through this each tick, so BOTH FINALIZING exits (IDLE,
 * ERROR — each of which also hides the text and cancels the job) terminate it. Pure and
 * JVM-pinned (ProcessingTimerPolicyTest).
 */
internal fun processingTimerRunsIn(state: FloatingBubbleService.BubbleState): Boolean =
    state == FloatingBubbleService.BubbleState.PROCESSING ||
        state == FloatingBubbleService.BubbleState.FINALIZING

/**
 * May the prewarm re-arm now, after `onTrimMemory` freed the native context? (4.4.0 startup
 * amendment, Task S1. Pure and JVM-pinned — TrimPrewarmPolicyTest.)
 *
 * The bug this answers: the trim handler releases the context while the service is idle and
 * NOTHING warms it again, so the next tap pays the full cold load — **4,107 ms** measured on
 * npu-turbo (`docs/superpowers/research/2026-09-10-startup-cutoff-investigation.md` §3a), silently,
 * because on the local tiers the recorder does not open until `onOpen` fires. A background overlay
 * service holding 342 MiB to 1.02 GiB makes trims routine, which is exactly the *"every once in a
 * while"* in the owner's report.
 *
 * The gate is the model-switch re-prewarm's own gate, not a new one: IDLE or ERROR, i.e. *"mid-session
 * triggers are skipped, never deferred"*. Skipping costs nothing, because the thing that starts a
 * session is the thing that loads the context — that session's `connect()` fills the slot this
 * re-arm would have filled.
 *
 * The amendment's other two conditions are already respected, elsewhere, and re-deciding them here
 * would be inventing policy rather than matching it: **a local tier selected** is
 * [LocalWhisperEngine.prewarm]'s own first line (`installedModelPath() ?: return`), and neither
 * existing prewarm trigger respects a metered/battery-saver constraint, so this one does not
 * either. The trim LEVEL is not re-decided here: it belongs to the release guard in
 * `onTrimMemory`, which is what makes a re-arm reachable at all.
 */
internal fun prewarmRearmsAfterTrim(state: FloatingBubbleService.BubbleState): Boolean =
    state == FloatingBubbleService.BubbleState.IDLE ||
        state == FloatingBubbleService.BubbleState.ERROR

/**
 * How long the post-trim re-arm waits before it reloads (4.4.0 startup amendment, Task S1) — the
 * BOOT prewarm's own delay, matched rather than invented, ahead of the boot prewarm's own call.
 * See `FloatingBubbleService.rearmPrewarmAfterTrim` for what the wait buys at this second site.
 */
private const val TRIM_REARM_DELAY_MS = 1_500L

/**
 * Whether a WALL-CAP cut consumes the session's first-cap window (3.7, Workstream D — the
 * predicate only; the `else if` branch it sits under is unchanged).
 *
 * A cap cut on silence-only audio still commits the buffer — that part is unconditional. What is
 * conditional is the bookkeeping:
 *  - real speech (any session): consume the window and restart the clock;
 *  - CLOUD silence: also consume — the 4 s window must NEVER re-open on cloud, because a 4 s
 *    cloud cut is an extra billable provider request and `cap=4000ms` in a cloud session is the
 *    documented regression signature;
 *  - LOCAL silence: re-arm, so a user who pauses to think still gets the 4 s first cut on their
 *    first real speech (3.5.0 parity guarantee).
 *
 * Pure and JVM-pinned (CapCutBookkeepingTest). This matters more under 3.7 than it did under
 * 3.6.0: `hasPendingSpeech()` becomes HONEST — the soft talker in a noisy room, permanently false
 * under the amplitude segmenter, now reports true — so this branch changes behaviour for exactly
 * the users it was mis-serving, with no edit to the branch itself.
 *
 * That honest-input assumption has ONE documented breach, which whoever edits this branch must
 * know: once [com.whispereverywhere.audio.SileroEndpointer.isProbeCutout] latches, every predicate
 * on that endpointer freezes and the first post-latch commit/reset pins `hasPendingSpeech()` FALSE
 * for the rest of the session, so each later LOCAL cap cut arrives here as `(false, false)` and
 * re-arms the 4 s window perpetually. CLOSED in Task D9, and closed at the CALL SITE rather than
 * here: the wall-cap branch ORs `isProbeCutout()` into the `hasPendingSpeech` argument it passes,
 * so a latched session reads `(true, …)` and consumes the window. This predicate therefore stays
 * SYMMETRIC — its 4-row truth table is unchanged, and a positional call to it remains inert.
 */
internal fun capCutConsumesWindow(hasPendingSpeech: Boolean, isCloudSession: Boolean): Boolean =
    hasPendingSpeech || isCloudSession

/**
 * How many trailing milliseconds the wall-cap commit retains, given the endpointer's remembered
 * micro-pause at cap time [nowMs] (3.7, Workstream D — the M4b follow-up, landed at Task F8).
 *
 * The wall-cap branch used to ask [CommitCadencePolicy.capCutRetainMs] directly, and that call
 * takes TWO adjacent same-typed `Long`s whose order nothing behavioural pinned: a swapped pair
 * returns `0L` for EVERY input, which silently reverts the cap-cut split to 3.6.0 with the whole
 * suite green (D2's M22 survived exactly that). Named arguments make a POSITIONAL swap inert, but
 * they cannot make a mis-bound VALUE inert, and `FloatingBubbleService` cannot be instantiated in a
 * JVM test — so the only guard over the binding was an exact-match source needle written in the
 * same session as the code.
 *
 * Lifting the computation here fixes that at the type level and at the value level both. The two
 * parameters are now a `Long` and an [Endpointer], so a positional swap does not COMPILE; and the
 * binding of `nowMs`/`cutPointMs` inside this one line is pinned behaviourally by
 * `CapCutRetainWindowTest` (no-offer -> 0, a fresh micro-pause -> the elapsed ms, a reversed pair
 * -> 0). `CapSeamPinTest`'s needle stays, as belt to those braces.
 *
 * It reads [Endpointer.pendingCutPointMs] itself rather than taking the offer as a second `Long`,
 * because a `(nowMs, cutPointMs)` signature would have re-created the very hazard it exists to
 * remove one call frame further out. Pure and Context-free; it must be called BEFORE
 * `endpointer.reset()`, which clears the offer.
 */
internal fun capCutRetainWindowMs(nowMs: Long, endpointer: Endpointer): Long =
    CommitCadencePolicy.capCutRetainMs(nowMs = nowMs, cutPointMs = endpointer.pendingCutPointMs())

/**
 * WHEN the user actually stopped speaking, for the segment [ec] cut (3.7, Workstream F — Task F9).
 *
 * [EndpointCut.trailMs] is `nowMs - tempEndMs` evaluated on the frame that FIRED the cut, so the
 * speech ended exactly that many milliseconds before that frame's clock — and [nowMs] here is that
 * same frame clock, handed to the funnel by the capture site that also handed it to
 * `endpointer.onFrame`. The whole `perceived:` metric rests on those two numbers sharing one
 * instant; see [PerceivedLatency].
 *
 * Lifted out of the funnel for the same reason [capCutRetainWindowMs] was, and with the same two
 * consequences. `FloatingBubbleService` cannot be instantiated in a JVM test, so an inline
 * `nowMs - ec.trailMs` would have had an exact-match source needle over it and nothing else — and
 * a REVERSED subtraction is not a small error: it reports a wait of about 54 years on every
 * endpoint cut, which is a headline metric that lies, with the whole suite green. Here it is a
 * pure function three rows of `PerceivedLatencyTest` call directly. The parameters are a `Long`
 * and an [EndpointCut], so a positional swap does not compile either.
 */
internal fun speechEndMs(nowMs: Long, ec: EndpointCut): Long = nowMs - ec.trailMs

/**
 * Who owns the preview strip's TextView (3.7, Workstream G). Only a SERVER-DRIVEN LIVE session
 * still streams deltas onto it: its partials arrive as the words are spoken, which is the whole
 * point of the surface. A LOCAL session's native deltas all arrive in one burst at ~100 % of the
 * transcribe's wall time — whisper.cpp fires `new_segment_callback` after the window's decode —
 * and `LocalWhisperEngine` follows them with a terminal `onDelta("")`, so at utterance cadence
 * the strip was set and hidden inside a single Choreographer frame. That is the "flicker" H2
 * filed as accepted cosmetic; it is the render being pointless, not slow.
 *
 * D4's plumbing stays exactly where it is — `transcribeStreaming`, the JNI new-segment callback
 * and [com.whispereverywhere.transcription.DeltaThrottle] are untouched and still feed CLOUD_LIVE;
 * only this render decision moved. Cloud BATCH loses nothing here: it emits no deltas
 * (its `CloudRelay` forwards a callback its engine never fires, and the fallback's `LocalRelay`
 * swallows the rescue engine's).
 *
 * **What this rule hands over is EVERY NON-LIVE SESSION, not just the local one.** `false` here
 * means [renderInFlightStrip] owns the strip, and `sessionIsLive` is false for CLOUD_WITH_FALLBACK
 * (batch) as well as for local. So a cloud-batch session now shows "Transcribing… (N in queue)"
 * where 3.6.0 showed nothing at all — intended, and the reason [inFlightStripLabel] names no
 * provider and makes no speed claim. `commitSegment` is the one funnel for every engine, so the
 * depth is as true on the batch path as on the local one.
 *
 * Pure so the rule is a pinned contract rather than a buried `if` ([InFlightStripTest]), the same
 * discipline as [connectingStatusLabel], [processingTimerRunsIn] and
 * [com.whispereverywhere.transcription.live.LiveTurnPolicy].
 *
 * **4.4.0 P0 — the second input.** A LOCAL PREVIEW (`PreviewTeeEngine`, spec
 * 2026-09-10-streaming-previewer-design.md §4.2) emits partials every 320 ms — the live
 * session's shape — so it takes the live session's render path. `sessionHasLocalPreview` is a
 * second session flag beside `sessionIsLive`, set only at the one wrap site; `sessionIsLive`
 * keeps its three other jobs untouched. Until a producer sets it, every reader answers as
 * before: this commit is behaviour-neutral by the truth table InFlightStripTest pins.
 */
internal fun deltaOwnsPreviewStrip(sessionIsLive: Boolean, sessionHasLocalPreview: Boolean): Boolean =
    sessionIsLive || sessionHasLocalPreview

/**
 * The in-flight line for [depth] committed-but-unresolved segments, or null when the queue
 * is empty (3.7, Workstream G). Shown in every NON-LIVE session — local AND cloud batch.
 *
 * Under VAD endpointing there is a genuinely new repeating state that did not exist before:
 * "the endpoint fired, this sentence is in flight", lasting ~1.3–4.3 s and recurring ~16×/minute,
 * during which nothing on screen changes. The depth suffix appears only past one, and it is the
 * only surface that makes a growing backlog visible WHILE it grows rather than at stop.
 */
internal fun inFlightStripLabel(depth: Int, sessionHasLocalPreview: Boolean): String? = when {
    // RULING ASSUMED (R2): while a local preview paints the words, the label is DISPLACED —
    // the strip IS the pending text, and the `queue:` diag line keeps the depth. A flip to
    // "shared" (label after the words) or "moved" (into the window) is THIS row and the render's
    // row: [renderInFlightStrip]'s owner gate returns early whenever the flag is set, so this row
    // never fires in production and is the flip's declaration rather than the whole of it.
    sessionHasLocalPreview -> null
    depth <= 0 -> null
    depth == 1 -> "Transcribing…"
    else -> "Transcribing… ($depth in queue)"
}

/**
 * Does this `commit()` return value represent a segment the queue should count (3.7, G)?
 *
 * [TranscriptionEngine.commit] returns `-1L` for "nothing to cut" — the ordinary outcome of the
 * unconditional stop flush and of `switchSource` on an already-drained buffer. (The engine names
 * that value `NO_SEGMENT` in a private companion; it is not visible here, so the contract is the
 * documented `-1`, compared directly.) Counting one would leave the in-flight line up for the rest
 * of the session with no resolution able to take it down. seq 0 is a REAL segment: connect()
 * restarts numbering at zero every session.
 *
 * [SegmentQueueDepth] applies the same rule to its own set; this names it for the SCREEN, which
 * must not post a repaint for a commit that changed nothing.
 */
internal fun commitAdvancesQueueDepth(seq: Long): Boolean = seq >= 0L

/** What the preview strip should be doing for a given in-flight line (3.7, Workstream G). */
internal enum class StripVisibility { HIDDEN, OCCUPYING_BLANK, SHOWING }

/**
 * The anti-churn rule. Once the in-flight line has revealed the strip, an empty queue leaves it
 * OCCUPYING_BLANK (View.INVISIBLE) rather than hidden: at utterance cadence the queue empties and
 * refills roughly every 2.4 s, so a VISIBLE↔GONE flap would change the window's height — and
 * therefore post `reclampNow()` — on every single utterance. Under the 15 s wall cap that
 * happened once per cap window; the delta strip's own show/hide did it per segment boundary.
 * Revealing once per session and then only swapping text is what removes it.
 *
 * Reachability, measured at the G-section close: SHOWING and OCCUPYING_BLANK both occur in
 * production; **HIDDEN does not**. It needs an empty queue AND a still-GONE strip, and the funnel's
 * repaint for a seq is always enqueued on Main before any resolution for that seq can be, so the
 * first paint of a session is always the reveal. HIDDEN is kept as the defensive no-op for that
 * ordering assumption — it is a bare `return`, and a rule with a hole where its third row should be
 * is worse than one unreachable row. All three rows stay pinned by [InFlightStripTest].
 */
internal fun inFlightStripVisibility(label: String?, currentlyHidden: Boolean): StripVisibility =
    when {
        label != null -> StripVisibility.SHOWING
        currentlyHidden -> StripVisibility.HIDDEN
        else -> StripVisibility.OCCUPYING_BLANK
    }

/**
 * What a BLANK delta does to the strip (4.4.0 P0). A server-driven live session keeps 3.6.0's
 * behaviour — GONE, byte for byte (the three live providers are out of this release's scope).
 * A local preview blanks between utterances by design (its frozen text resolved, no partial
 * yet), so it composes with [inFlightStripVisibility]'s anti-churn rule: parked INVISIBLE once
 * revealed, never revealed for nothing. SHOWING is unreachable here — a blank never shows.
 */
internal fun deltaBlankVisibility(sessionHasLocalPreview: Boolean, currentlyHidden: Boolean): StripVisibility =
    if (sessionHasLocalPreview) inFlightStripVisibility(label = null, currentlyHidden = currentlyHidden)
    else StripVisibility.HIDDEN

/**
 * Should a released segment's text CLEAR the preview strip (3.7, Workstream G)?
 *
 * Only when deltas own it: there the strip was carrying this very utterance's words and leaving
 * them would read as duplicated text under the next one. When the in-flight line owns it, the
 * caller repaints from the queue depth instead — clearing would blank the backlog signal on every
 * resolution. FINALIZING never clears in either case: the stop tap's status line owns the strip
 * for the whole drain (the pre-existing 3.6.0 D guard, preserved verbatim).
 *
 * This is also the other half of [inFlightStripVisibility]'s anti-churn rule. Hiding the strip here
 * put it back to `currentlyHidden`, so every commit paid the reveal — and its `reclampNow()` — all
 * over again; the rule can only cost one geometry change per session if nothing returns the strip
 * to GONE mid-session.
 *
 * **4.4.0 P0:** a LOCAL PREVIEW never clears here. Its tee recomposes the strip after every
 * resolution (the resolved seq's frozen prefix dropped, the live partial kept) and emits that
 * as the next delta; a clear in between would GONE the strip and pay the reveal — and its
 * reclampNow() — once per utterance, which is exactly the churn this rule's other half removes.
 */
internal fun resolvedTextClearsStrip(sessionIsLive: Boolean, sessionHasLocalPreview: Boolean, isFinalizing: Boolean): Boolean =
    !isFinalizing && deltaOwnsPreviewStrip(sessionIsLive, sessionHasLocalPreview) && !sessionHasLocalPreview

class FloatingBubbleService : Service(),
    WhisperAccessibilityService.OnTextFieldFocusListener,
    WhisperAccessibilityService.OnClipboardChangedListener,
    MediaSessionDetector.MediaPlaybackListener {

    // Read-aloud (Track F): true while OUR read-aloud audio is playing (speaker lobe, clipboard
    // copy, or PROCESS_TEXT toolbar). The idle bubble is ALWAYS a mic (owner rule 2026-08-08);
    // speaker behavior never arrives via text selection.
    @Volatile private var isSpeakingNow = false
    /** 4.3.1 B: a hide refused because a read was in progress; replayed by the IDLE branch. */
    @Volatile private var deferredHideReason: String? = null
    private lateinit var speechStopIcon: ImageView
    private lateinit var speakClipIcon: ImageView
    private lateinit var lockLobe: View
    private lateinit var keyboardLobe: View
    private lateinit var speakerLobe: View
    private lateinit var ttsScrubber: com.whispereverywhere.ui.components.TtsScrubberView

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var bubbleContainer: FrameLayout
    private lateinit var bubbleIcon: ImageView
    private lateinit var processingRing: ImageView
    private lateinit var waveformView: BarWaveformView
    private lateinit var blobView: com.whispereverywhere.ui.components.BlobView
    private lateinit var recordingTimerText: android.widget.TextView
    private var recordingTimerJob: Job? = null
    private lateinit var processingTimeText: android.widget.TextView
    private lateinit var transcriptionPreviewContainer: View
    private lateinit var transcriptionEditText: android.widget.TextView
    private lateinit var transcriptionDeltaText: android.widget.TextView

    private lateinit var audioRecorder: StreamingAudioRecorder

    // Device-audio (playback) capture source — active INSTEAD of the mic during media sessions.
    private var playbackCapturer: com.whispereverywhere.audio.PlaybackAudioCapturer? = null
    @Volatile private var activeSource = com.whispereverywhere.audio.ActiveSource.MIC
    /** 4.3.1 D: the screen-capture dialog may be raised at most twice per session. */
    private val consentBudget = com.whispereverywhere.audio.ProjectionConsentBudget()
    /** One "microphone for this session" toast per session, not one per media event. */
    private var consentExhaustedToastShown = false
    private var transcriptionEngine: TranscriptionEngine? = null

    // The on-device engine, ALWAYS reachable independently of whatever composite engine the user's
    // provider choice produced.
    private var localEngine: LocalWhisperEngine? = null

    // 4.4.0: THE RESIDENT PREVIEWER (spec §4.1 step 1, §7.4) — the sherpa recognizer and its own
    // executor, Main-confined like localEngine. Built lazily by warmStreamingPreview() when the
    // SELECTED language's pack is installed and the switch is on (4.4.1, CHANGE 5); released on
    // onTrimMemory (outside a session), on onDestroy, and — 4.4.1 pass 3, ITEM 2 — when the
    // selection moves to a language there is nothing to warm for; BORROWED per session by
    // PreviewTeeEngine, never owned by it, which is why every release site skips mid-session.
    private var streamingPreview: com.whispereverywhere.transcription.stream.StreamingPreviewEngine? = null

    // (4.4.1, CHANGE 5) WHICH pack the resident previewer above holds — ONE PACK PER PROCESS.
    //
    // (4.5.0 T2, defect 1) It is no longer the mechanism for that invariant: the ENGINE now
    // carries its own pack identity, warm() is idempotent on the PACK rather than on the engine,
    // and a different pack releases the old recognizer inside the engine's own task. What this
    // field still does is let Main SKIP posting a release it knows is unnecessary, which is what
    // keeps the release collector's "re-pick the language you just left" path from releasing an
    // already-released engine. It is Main's cache of the engine's answer, not the answer.
    //
    // No longer read from the engine's executor thread — the load-failure hook is handed the pack
    // that failed (4.4.1's read of this field marked B corrupt for A's failure whenever the
    // selection moved during a load) — but @Volatile stays: the cost is nothing and the field is
    // written on Main while the engine it describes lives on another thread.
    @Volatile private var streamingPreviewPack: com.whispereverywhere.transcription.stream.StreamingPack? = null

    // WHICH npu-class tier [localEngine] was built on, or null for the shared CPU backend
    // (4.0 Q9 as a Boolean; a tier ID since 4.1 L8). It cannot be asked of the engine — `backend`
    // is a private val there — and it is what makes "the cached engine is still the right one" a
    // comparison rather than a guess. An ID rather than a Boolean because a Boolean cannot tell
    // `npu` from `npu-turbo`: under the old field an npu -> npu-turbo switch read as "no change",
    // the engine never rebuilt, and the user kept dictating on the tier they had just left while
    // the card showed the other one. Null deliberately means EVERY CPU tier at once — they all
    // share WhisperNativeBackend, so a CPU -> CPU switch keeps the cached engine and re-prewarms,
    // exactly as it always has. Main-confined, like localEngine itself.
    private var localEngineNpuTierId: String? = null

    // WhisperEverywhereApp.offeredNpuTierIds()'s answer, memoised because warmLocalEngine() reads
    // it on Main and that gate must never be evaluated there. Written only by
    // refreshNpuTierOffer(); empty until the first refresh lands, which is the safe direction
    // (the CPU tier always works). The name is the chooser producers' own — the value carries ONE
    // name end to end (4.1 L8).
    //
    // @Volatile is DEFENSIVE, not load-bearing, and the earlier comment here claiming otherwise was
    // wrong (Q9 review, M1). `withContext(Dispatchers.IO) { … }` returns to the CALLER's context,
    // and both call sites are `serviceScope.launch { }` on Dispatchers.Main — so today the write
    // and every read are alike Main-confined and no publication is needed. It is kept because it
    // costs one reference read and it is the difference between "correct" and "correct until
    // someone moves the refresh onto a background scope", a one-line change with no other symptom.
    @Volatile private var npuTierIds: Set<String> = emptySet()

    // The cloud wrapper (FallbackTranscriptionEngine) of the CURRENT/previous session, held so it
    // can be close()d — resolving everything it still owes — without shutting down the local engine
    // it wraps. Non-null only for cloud sessions; on-device-only users keep the pre-cloud path.
    private var cloudWrapper: FallbackTranscriptionEngine? = null

    // ONE HTTP client for the service's whole life. OkHttpTransport's default constructor builds a
    // fresh OkHttpClient — each with its own dispatcher threads and connection pool — so building a
    // transport per session would leak both on a bubble that stays up for days.
    private var httpTransport: com.whispereverywhere.net.OkHttpTransport? = null

    // The engine shape the user was last TOLD about, so a degraded mode is announced when it
    // changes rather than on every single recording.
    private var notifiedChoice: EngineChoice? = null

    // This session-family's cloud engine, kept ONLY so finalize can read its latched fatal — the
    // composite engine deliberately hides which member answered. Rebuilt with the wrapper each
    // session; nulled with it in onDestroy.
    private var lastCloudEngine: com.whispereverywhere.transcription.cloud.CloudTranscriptionEngine? = null

    // The C4 live engine, kept for the SAME reason as lastCloudEngine — finalize's latch-toast —
    // but a separate field because LiveTranscriptionEngine is not a CloudTranscriptionEngine (its
    // lastFatal() returns a FatalKind, not an SttError.Fatal). Exactly one of the two is non-null
    // per session: resolveTranscriptionEngine nulls both, then sets whichever branch built.
    private var lastLiveEngine: com.whispereverywhere.transcription.live.LiveTranscriptionEngine? = null

    // Frozen per session in resolveTranscriptionEngine: true only for a CLOUD_LIVE session. Read by
    // the delta surface to lift the preview strip into a TEXT_FIELD session (deltas render, never
    // inject). Batch/on-device sessions leave it false, so their behavior is byte-unchanged.
    @Volatile private var sessionIsLive = false

    // 4.4.0 P0: frozen per session at the one wrap site in startRecording (Task 7 of the
    // streaming-previewer plan sets it; this commit only declares and resets it). True only when a
    // PreviewTeeEngine is this session's engine. Read by onDelta, the render and delivery through
    // the four pure rules above — never directly.
    @Volatile private var sessionHasLocalPreview = false

    // The live WS transport + reconnect executor, held for the service's life for the SAME reason
    // httpTransport is: a fresh OkHttpClient (dispatcher threads + connection pool) or a fresh
    // daemon scheduler thread per session would leak on a bubble that stays up for days. Built
    // lazily so on-device-only and batch-only users never allocate the streaming client.
    private var liveWsFactory: com.whispereverywhere.transcription.live.WebSocketFactory? = null
    private var liveReconnectScheduler: com.whispereverywhere.transcription.live.ReconnectScheduler? = null

    // The latched-fatal kind the user was last TOLD about — once per latch, not once per session:
    // dictating ten times against a dead key should produce one toast, not ten.
    private var notifiedFatalKind: com.whispereverywhere.transcription.cloud.FatalKind? = null
    /**
     * The ONE commit-decision surface for this service's life (3.7, Task D2 seam, wired in D9). Chosen HERE,
     * at construction, on nothing but whether the bundled Silero model resolved: a null path
     * yields AmplitudeEndpointer, which wraps the very SpeechSegmenter this field used to hold —
     * so "VAD model missing" is byte-identical shipped behaviour rather than a new path.
     *
     * Deliberately not per-session: the model path is process-constant. What IS per-session is the
     * cadence floor and the probe's native context, both handed over in onOpen via
     * [Endpointer.onSessionStart] and torn down in stopRecording via [Endpointer.onSessionEnd].
     *
     * Cost note: `VadModel.path()` may copy the 885 KB asset on the FIRST service construction
     * after an install; every later call returns its cached @Volatile path.
     */
    private val endpointer: Endpointer =
        EndpointerFactory.create(com.whispereverywhere.transcription.VadModel.path())
    private lateinit var mediaDetector: MediaSessionDetector

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recordingJob: Job? = null
    private var amplitudeJob: Job? = null
    private var pulseAnimator: ValueAnimator? = null
    private var connectionMonitorJob: Job? = null
    private var processingTimerJob: Job? = null

    private var currentState: BubbleState = BubbleState.IDLE
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastAction = 0
    private var isDragging = false
    private var isBubbleVisible = false
    private var shouldHideOnIdle = false
    private var showAnimator: ValueAnimator? = null
    private var hideAnimator: ValueAnimator? = null

    // Pin/lock state — kept in sync with PreferencesManager.overlayPinned
    private var isOverlayPinned = false

    // Long-press detection for pin toggle (500 ms threshold)
    private var longPressJob: kotlinx.coroutines.Job? = null
    private val LONG_PRESS_MS = 500L

    // Resize-handle gesture state (W3). Deliberately separate from the root-drag fields
    // (initialX/initialTouchX/...): the handle consumes its own gesture stream so the two never
    // interleave, but sharing fields would make that invariant load-bearing and invisible.
    private lateinit var resizeHandle: ImageView
    private var resizeStartWidthDp = 0f
    private var resizeStartHeightDp = 0f
    private var resizeStartTouchX = 0f
    private var resizeStartTouchY = 0f
    private var resizeStartWindowY = 0
    private var isResizing = false
    private var lastResizeResult: ResizeMath.Result? = null
    private var resizeLongPressJob: kotlinx.coroutines.Job? = null

    // Max time finalize waits for the transcription backlog to drain before force-ending the
    // session. Generous because a slow model (e.g. the large tier) can lag several segments behind
    // real time; bounded so a pathological run can't hang the bubble in FINALIZING forever.
    // Since 3.6.0 the fallback engine may run up to 60 s past this when a rescue was armed only
    // during the cloud drain — its local reserve floor (FallbackTranscriptionEngine.awaitIdle,
    // localDrainReserveMs). Still bounded: worst case is this value + 60 s.
    private val FINALIZE_TIMEOUT_MS = 300_000L

    /**
     * Client-VAD live (Gemini, 4.3.4): how long the finalize drain waits for the provider's final
     * of the stop-cut tail before rescuing it locally. Gemini's final follows our activityEnd in
     * 0.26 s p50 / 0.44 s max on a PC (T0 P9, n = 28); 2 s leaves room for the phone's radio RTT.
     * Passed as LiveTranscriptionEngine.tailGraceMs; server-driven sessions never consult it.
     */
    private val LIVE_CLIENT_TAIL_GRACE_MS = 2_000L

    // Wall-clock cap per uncommitted stretch. Continuous loud audio (media playback, music) never
    // dips below the segmenter's silence floor, so its pause-based commit never fires — without
    // this cap the engine buffer grows unbounded and produces one giant end-of-session segment.
    // 3.6.0 (Workstream A): the cap is first-commit-aware — the session's FIRST stretch cuts at
    // 4 s so first visible text lands fast under continuous speech; every later stretch keeps the
    // old 15 s. The 800 ms pause cut is untouched and still wins when a real pause happens.
    // First-vs-later rule and per-session reset are JVM-pinned in SegmentCapPolicyTest.
    private val segmentCapPolicy = SegmentCapPolicy()

    /**
     * 3.7 Workstream F: the committed-but-unresolved backlog. Fed by every commit site and by
     * onSegmentResolved; the only surface that shows a growing multi-tier backlog while it grows.
     *
     * Build 85: it is also THE BACKPRESSURE GOVERNOR's signal. The counter publishes its depth
     * after every mutation, from inside its own monitor, to the endpointer's depth member — so a
     * commit on the capture thread and a resolution on Main can never publish out of order, and
     * the endpointer steps its fast/slow floor from a depth the queue actually holds
     * (`BackpressureWiringPinTest`). Declared BELOW [endpointer] because the bound reference
     * captures the instance at construction. Every resolution decrements — `EmptyExpected` and
     * `Lost` included — because the decrement lives above the blank guard in onSegmentResolved.
     */
    private val segmentQueueDepth = SegmentQueueDepth(onDepth = endpointer::onQueueDepth)

    /**
     * 3.7 Workstream F: speech-end -> text-visible per segment, the headline acceptance metric.
     * Stamped at the endpoint cut, read where the text actually renders.
     */
    private val perceivedLatency = PerceivedLatency()

    // Set true when any transcription text is produced during a recording session; drives the
    // "No speech detected" feedback on stop so the user is not left with silent nothing.
    @Volatile private var sessionProducedText = false

    // The cloud provider serving THIS session, or null for on-device sessions. Set by
    // resolveTranscriptionEngine's cloud branches, read once at finalize to credit the month's
    // cloud-cost estimate. An estimate, deliberately: segments the fallback rescued locally are
    // still credited (the audio was sent), and that over-count is within "about".
    @Volatile private var sessionCloudProviderId: ProviderId? = null

    // Releases segment outcomes into the user's text in STRICT seq order. Recreated per session in
    // startRecording, because it starts at head 0 and the engine restarts seq numbering at 0 too.
    // Main-thread confined: every touch below is inside a Dispatchers.Main block.
    //
    // With the on-device engine this is a PROVABLE PASS-THROUGH — LocalWhisperEngine's executor is
    // single-threaded, so results always arrive with seq == head, each drain releases exactly the
    // segment that just resolved, and flush() below is always a no-op. Local delivery timing is
    // therefore unchanged by its presence; it earns its keep only when a second engine can have
    // more than one segment in flight.
    private var segmentOrderer = com.whispereverywhere.transcription.SegmentOrderer()

    // Transcription history (text only — user decision 2026-07-17): per-session accumulator,
    // persisted via TranscriptStore at finalize with rolling 14-day/10MB retention.
    private val transcriptStore by lazy {
        com.whispereverywhere.transcription.TranscriptStore(java.io.File(filesDir, "transcripts"))
    }
    private val sessionTranscript = StringBuilder()
    private var sessionStartMs = 0L

    /**
     * THE STARTUP RING (4.4.0 startup amendment, Task S2) — the audio captured between the tap and
     * the engine being ready for it. One instance for the service's life, cleared at every session
     * open and every session exit; see [com.whispereverywhere.audio.StartupRing] for why it exists,
     * what the cap is, and which end overflow eats. Touched by the capture thread (append, paced
     * drain) and by Main (clear, and the stop path's flush behind the capture joins); the ring
     * synchronises itself.
     */
    private val startupRing = StartupRing()

    /**
     * Has the session's engine reported `onOpen`? The capture thread's only readiness question,
     * and the whole reason `startAudioInput()` may now run above `connect()`.
     *
     * @Volatile because Main writes it (session open, `onOpen`, teardown) and the capture thread
     * reads it once per 32 ms chunk. A stale `false` costs one chunk of extra buffering and a
     * stale `true` cannot happen before the write it follows — the drain is idempotent against
     * either, because the ring is the single source of truth for what has not been fed yet.
     */
    @Volatile private var engineReady = false

    /**
     * Has this session already said it is dropping audio? The overflow line is emitted ONCE per
     * session rather than once per dropped chunk — at 31.25 Hz the per-chunk version would be a
     * log flood burying the fact it reports. Written by the capture thread at the first drop and
     * by Main at session open.
     */
    @Volatile private var startupOverflowLogged = false

    /**
     * 4.4.0 S2, round 1 (B3): media began while the session was still CONNECTING, so the "media
     * transcription cuts the mic" handover could not run — its gate is RECORDING. Main-thread only
     * (the media callback and `onOpen`'s body are both `Dispatchers.Main` blocks); written at
     * session open, at the missed edge, and the moment it is consumed.
     *
     * Why a latch rather than simply re-reading the detector at `onOpen`: `MediaSessionDetector` is
     * EDGE-triggered, so the notification the state gate drops is the only one that app sends while
     * it keeps playing — but an UNCONDITIONAL re-read at readiness would also re-put the
     * device-audio question the tap already answered (a consent sheet still up from
     * `startAudioInput`, a budget already spent). The latch records that a NEW start arrived inside
     * the blind window; the playing LEVEL is re-read when it is consumed, so media that started and
     * stopped during the load takes nothing.
     */
    private var pendingDeviceAudioHandover = false

    // Pin icon view reference (lateinit; populated in createBubbleView)
    private lateinit var pinIcon: ImageView

    // Track the context for bubble display
    private var currentContext: BubbleContext = BubbleContext.NONE
    private var mediaTitle: String? = null

    // The session's ROUTING mode (inject into a field vs preview+clipboard+history), frozen at
    // the moment recording starts. currentContext keeps tracking focus/media live for the idle
    // bubble, but mid-session clicks (e.g. tapping a prompt field while a YouTube transcription
    // runs) must NOT reroute later segments — that split one transcript across two destinations.
    // Every routing decision between startRecording and finalize reads THIS, never currentContext.
    // Corollary (intended): a session STARTED on a focused field keeps typing into that field
    // even if media begins mid-session and the source hands over mic→stream — the session types
    // where the user aimed it; history accumulates via sessionTranscript in both modes.
    private var sessionContext: BubbleContext = BubbleContext.NONE

    // TEXT_FIELD session whose FINAL write degraded to clipboard (document apps, dead targets):
    // set by deliverFinalTranscript when the one stop-time write can't type. Records the
    // OUTCOME (for the degraded toast wording); the decide() degraded row it could feed is
    // future-proofing only — finalDelivered makes a second decide() impossible in production.
    @Volatile private var sessionClipboardFallback = false

    // The final write fires EXACTLY once per session — stopRecording's finalize normally, or
    // onDestroy's best-effort if the service dies mid-session. This flag closes the race
    // between those two paths (destroy can cancel the finalize coroutine at any point).
    @Volatile private var finalDelivered = false

    // Bounded-memory sink for non-text-field sessions (Task 7)
    private var transcriptSink: com.whispereverywhere.transcription.TranscriptSink? = null

    // Tracks the preview-collector coroutine so a second recording doesn't leave two collectors running (Fix I1)
    private var previewJob: kotlinx.coroutines.Job? = null

    private lateinit var params: WindowManager.LayoutParams

    private val app by lazy { WhisperEverywhereApp.getInstance() }
    private val connectivityMonitor by lazy { ConnectivityMonitor(this) }
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        audioRecorder = StreamingAudioRecorder(this)
        mediaDetector = MediaSessionDetector(this)
        // Our read-aloud plays on the music stream; the detector must never count it as media.
        // TtsController is the ONE authority: it covers every trigger, including
        // SpeakTextActivity's toolbar reads, which the service-local isSpeakingNow never saw —
        // those were classified as transcribable media over our own voice.
        mediaDetector.selfAudioActive = { com.whispereverywhere.tts.TtsController.isSpeechActive() }

        // The dictation-first toggle applies to the LIVE bubble (owner report 2026-08-01: the
        // keyboard lobe only appeared after disabling/re-enabling the bubble — its visibility was
        // evaluated solely in updateBubbleState's IDLE branch, which nothing re-ran on a pref
        // flip). Re-render the idle chrome whenever the pref changes; other states re-evaluate on
        // their natural next transition to IDLE.
        serviceScope.launch(Dispatchers.Main) {
            app.preferencesManager.dictationFirstKeyboard.collect {
                if (currentState == BubbleState.IDLE) updateBubbleState(BubbleState.IDLE)
            }
        }

        // Foreground FIRST (satisfies the startForegroundService contract), and guarded: on
        // Android 12+/14+ this throws when the start context is disallowed or RECORD_AUDIO was
        // revoked (mic-type FGS). Without the guard a START_STICKY service crash-loops forever
        // after a permission revocation. Fail soft: log + stop, never crash.
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, WhisperEverywhereApp.NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(WhisperEverywhereApp.NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            android.util.Log.w("WE-DIAG",
                "startForeground rejected (${t.javaClass.simpleName}: ${t.message}) — stopping instead of crash-looping")
            stopSelf()
            return
        }

        // Overlay permission can be revoked while the service is not running; addView without it
        // throws. Same fail-soft treatment.
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.util.Log.w("WE-DIAG", "overlay permission missing — stopping bubble service")
            stopSelf()
            return
        }
        createBubbleView()

        // Always-on mode: the bubble appears immediately at the user's spot and lives there.
        // (Auto mode leaves it hidden until a text field / media event summons it.)
        if (alwaysOnMode()) {
            bubbleView.post { showBubbleAtRest() }
        }

        // Register for text field focus events
        registerFocusListener()

        // Start media playback detection
        mediaDetector.setListener(this)
        mediaDetector.startMonitoring()

        // Start monitoring accessibility service connection
        startConnectionMonitor()

        // Read-aloud exclusivity (Track F): the arbiter queries live session state and can ask
        // a capture session to finish gracefully (same path as a stop tap). The speech side is
        // registered by TtsController when its engine is first created.
        com.whispereverywhere.audio.AudioArbiter.isCapturing = {
            currentState == BubbleState.RECORDING || currentState == BubbleState.FINALIZING ||
                currentState == BubbleState.CONNECTING
        }
        com.whispereverywhere.audio.AudioArbiter.stopCaptureGracefully = {
            serviceScope.launch(Dispatchers.Main) {
                if (currentState == BubbleState.RECORDING) stopRecording()
            }
        }

        // Pre-warm the whisper context (model mmap + Adreno OpenCL kernel compile, ~7 s on the
        // GPU path) so the FIRST recording connects instantly instead of paying it inside
        // CONNECTING. Slightly delayed to keep service startup/view inflation snappy; queued on
        // the engine's own single-thread executor, so it never races a session.
        // Deliberately the LOCAL engine, not the session engine: the native context is the only
        // expensive thing to warm, and resolving the session engine here would (a) decide the
        // cloud/local question ~1.5 s after boot and then cache that answer for the service's
        // whole life, and (b) toast about a degraded mode before the user has asked for anything.
        serviceScope.launch {
            // (4.0, Q9) The offer gate BEFORE the prewarm it decides, and off Main — its first
            // evaluation dlopens two QNN libraries. Without this the first engine of every process
            // is built on the CPU backend and an npu user pays a rebuild for it.
            refreshNpuTierOffer()
            delay(1500)
            warmLocalEngine().prewarm()
            // 4.4.0: the previewer's ~0.8 s load and its canary (spec §7.2), off the session's
            // critical path. (4.4.1, CHANGE 5) For the SELECTED language's pack and no other —
            // the same lookup the arm site takes, so Auto warms nothing and hands back both the
            // load and the +169 MB RSS. RULING ASSUMED (R3): the switch defaults on.
            previewPackToWarm(
                previewLanguage = app.preferencesManager.getLanguageForApi(),
                installedPackLanguages = app.streamingPackManager.installedLanguages(),
                userEnabled = app.preferencesManager.localPreviewEnabled,
            )?.let { warmStreamingPreview(it) }
        }

        // (4.4.1 pass 3, ITEM 2) THE OTHER HALF OF CHANGE 5: the +169 MB comes back when the
        // selection moves AWAY from an installed pack language. CHANGE 5 stopped the boot warm
        // for a user on Auto, so nothing is LOADED for them at boot — but nothing released a
        // RESIDENT engine, so a user who dictated in English and then switched to Auto kept the
        // recognizer until `onTrimMemory` or `onDestroy` (review r2, nit 2). CHANGE 5's words
        // were met; its purpose was not.
        //
        // ONE site, ONE condition, and the condition is the warm gate's OWN ANSWER: nothing to
        // warm for the new selection is nothing that should stay resident for it. It is the exact
        // COMPLEMENT of `warmStreamingPreview`'s release — that one frees the old recognizer when
        // the new selection has a DIFFERENT pack, this one frees it when the new selection has
        // NONE — so between them a language change can never leave the wrong model, or an unused
        // one, in memory. `streamingPreviewPack` is deliberately left pointing at what the engine
        // last held: that is exactly the `onTrimMemory` shape, so re-picking that language reloads
        // through the `==` branch instead of releasing an already-released engine first.
        //
        // `drop(1)` skips the replay of the value already in place — the prewarm above has just
        // asked the same question of it and warmed nothing. Mid-session triggers are SKIPPED
        // rather than deferred, like the model-switch collector's below and for a stronger
        // reason: the session's `PreviewTeeEngine` BORROWS this recognizer, so freeing it under a
        // live session would leave the strip up with nothing left to draw on it. The next trim,
        // or the next selection change, hands the memory back instead. The disk census is off
        // Main because it is a marker read plus four exact byte counts per catalogue row.
        serviceScope.launch(Dispatchers.Main) {
            app.preferencesManager.selectedLanguage.drop(1).collect {
                if (currentState != BubbleState.IDLE && currentState != BubbleState.ERROR) return@collect
                if (streamingPreview == null) return@collect
                val keep = withContext(Dispatchers.IO) {
                    previewPackToWarm(
                        previewLanguage = app.preferencesManager.getLanguageForApi(),
                        installedPackLanguages = app.streamingPackManager.installedLanguages(),
                        userEnabled = app.preferencesManager.localPreviewEnabled,
                    )
                }
                // THE SKIP, RE-READ BELOW THE SUSPENSION (pass 3 fix round 1, H-B1) — the
                // model-switch collector's own lesson 50 lines below, for the identical shape.
                // The check at the top of this block was taken before the census hopped to IO and
                // back, so by here it is stale: across that window the user can re-pick the
                // pack's language AND start a session, which finds the pack installed and
                // isWarmFor() true, arms, and lets PreviewTeeEngine BORROW this recognizer — and the
                // stale body would then free it under that live session, leaving the strip up with
                // nothing left to draw on. This read is what makes the release below safe, and it
                // is safe *because* nothing between it and the release suspends: currentState is
                // Main-confined with one writer, `keep` is already in hand, and Log.i and
                // release() are ordinary calls. One uninterrupted run on Main.
                if (currentState != BubbleState.IDLE && currentState != BubbleState.ERROR) return@collect
                if (streamingPreview == null) return@collect
                if (keep != null) return@collect
                android.util.Log.i(
                    "WE-DIAG",
                    "stream-warm: the selection has no pack to warm — resident previewer released",
                )
                streamingPreview?.release()
            }
        }

        // (4.5.1 TASK 1) THE THIRD WARM TRIGGER — and the only one that is an EVENT rather than a
        // boot or a session start. A freshly installed pack arms the VERY NEXT session.
        //
        // > *"What can we do about having to transcribe a second time to get the live to work? Once
        // > we get the preview model downloaded, why can't we just refresh things to where the very
        // > next transcribe is already on? People are going to think that it doesn't work."*
        //
        // He is right. The two triggers above are a boot and a session START, and the wrap site's
        // own KDoc says it "arms NEXT session, not this one, because warm() is asynchronous and the
        // gate reads isWarmFor() now" — so an install that completed mid-process warmed nothing
        // until a session had already been and gone. 4.5.0's acceptance sheet recorded that miss as
        // expected behaviour (AF6); this collector and that row's rewrite retire it together.
        //
        // NOT a service restart, which the owner offered as the other option: it would tear down
        // the overlay he is looking at, and warming is sufficient — the load is 802-860 ms and the
        // gate reads it at the next tap.
        //
        // ONE MORE COLLECTOR OF THE SAME SHAPE as the release-on-selection-change collector above,
        // and it decides nothing of its own: `warmOnPackInstalled` answers whether to warm, and
        // `previewPackToWarm` inside it stays the ONE OWNER of "which pack should be resident" —
        // the same function the boot warm, the wrap site and that release all take their answer
        // from. So this trigger and that release cannot disagree: a null is the release's condition
        // and this one's refusal, which is why they can never thrash the load between them. A
        // language change still releases first, and the ENGINE is what does it — `warm` is
        // idempotent on the PACK and frees a resident recognizer of another language inside its own
        // single-thread task before the new one allocates (warmStreamingPreview's KDoc).
        //
        // The FLOW is narrowed to phase changes before the body runs, and that narrowing carries no
        // rule: the board ticks on every progress callback of a 73-128 MB transfer, and the census
        // below is a marker read plus four exact byte counts per catalogue row. De-duplication, not
        // policy — every TERM of the decision is in the pure function.
        serviceScope.launch(Dispatchers.Main) {
            com.whispereverywhere.transcription.stream.PreviewWorkboard.work
                .map { board -> board.values.associate { it.language to it.phase } }
                .distinctUntilChanged()
                // ...and `drop(1)` for the release collector's own reason, which is stronger here:
                // the board is process-scoped and outlives this service, so a StateFlow replay at
                // onCreate would warm from a record that landed before the service existed — work
                // the boot prewarm above already does, for the same pack, through the same
                // function. It does it after `delay(1500)`, deliberately, "to keep service
                // startup/view inflation snappy"; warming from the replay here would pay the
                // 802-860 ms load and its canary DURING that startup and call it an install event.
                // The value already in place is the prewarm's; this collector is for what CHANGES
                // while the service is up, which is the only case AF6 was ever about.
                .drop(1)
                .collect {
                    val selection = app.preferencesManager.getLanguageForApi()
                    val record = com.whispereverywhere.transcription.stream.PreviewWorkboard.of(selection)
                        ?: return@collect
                    val installed = withContext(Dispatchers.IO) { app.streamingPackManager.installedLanguages() }
                    // THE TERMS THAT MOVE ARE READ BELOW THE SUSPENSION — the release collector's
                    // own H-B1 lesson, for the identical shape. Across the census hop a session can
                    // have started, and a load posted under it would allocate 169 MB beside the
                    // recognizer `PreviewTeeEngine` has BORROWED. Nothing between this call and the
                    // warm suspends: `Log.i` and `warmStreamingPreview` are ordinary calls, and the
                    // warm itself only posts to the engine's executor.
                    val pack = warmOnPackInstalled(
                        installedLanguage = record.language,
                        phase = record.phase,
                        previewLanguage = selection,
                        installedPackLanguages = installed,
                        userEnabled = app.preferencesManager.localPreviewEnabled,
                        sessionActive = currentState != BubbleState.IDLE && currentState != BubbleState.ERROR,
                        batchJobActive = BatchJobController.active != null,
                        residentWarmPack = streamingPreviewPack?.takeIf { streamingPreview?.isWarmFor(it) == true },
                    ) ?: return@collect
                    android.util.Log.i(
                        "WE-DIAG",
                        "stream-warm: the selected language's pack finished installing — warming now, " +
                            "so the next session arms",
                    )
                    warmStreamingPreview(pack)
                }
        }

        // Re-prewarm on model switch OR first install (3.6.0, Workstream E1). TWO triggers, ONE
        // collector, both ending in the same prewarmModelSwitch():
        //
        //  (a) every selectedModelId write — onboarding pick, Home's missing-engine row,
        //      Settings' migration/switch/delete — through the ONE prefs mirror, zero per-writer
        //      wiring. Debounced 750 ms: rapid successive writes (the migration's swap,
        //      onboarding's double write) restart the wait and only the final selection loads;
        //      the StateFlow additionally conflates same-value rewrites into no emission at all.
        //      drop(1) skips the replay of the current value at collect time — the prewarm above
        //      already covers service start.
        //  (b) modelInstalled — emitted once per VERIFIED download. This is the case (a) cannot
        //      see: onboarding writes the id before the file exists (a re-prewarm then finds no
        //      installed path and no-ops) and rewrites the same id after, which the StateFlow
        //      conflates away.
        //
        // Both arms carry the SAME 750 ms debounce, because the install signal LEADS the id write
        // on the download-then-select path: WhisperModelManager emits from verifyDest, and only
        // once download() has returned does ModelDownloadViewModel persist the new id. Firing the
        // install arm immediately would therefore prewarm the tier being switched AWAY from —
        // wasting a full ~7 s load on a cold slot (fresh start, or after onTrimMemory released
        // the context) and delaying the switch that actually matters. Debounced, collectLatest
        // coalesces the install signal into the id write that lands milliseconds later, so the
        // switch path does exactly one load, of the right tier. Onboarding is unaffected: there
        // the post-download write is the SAME id, the StateFlow conflates it away, and the
        // install signal is the sole trigger — it simply waits 750 ms first.
        //
        // Mid-session triggers are skipped, never deferred: releasing a context a live session is
        // using would Lost every later segment, and connect() at the next session start reloads
        // exactly as it always has. A null id (model deleted) no-ops inside prewarmModelSwitch.
        // Main-dispatched, so the currentState read is main-confined.
        serviceScope.launch {
            merge(
                app.preferencesManager.selectedModelIdFlow.drop(1)
                    .map { id -> 750L to "selectedModelId -> $id" },
                app.preferencesManager.modelInstalled.map { 750L to "model installed" },
            ).collectLatest { (debounceMs, reason) ->
                delay(debounceMs)
                if (currentState != BubbleState.IDLE && currentState != BubbleState.ERROR) {
                    android.util.Log.i("WE-DIAG", "$reason mid-session — connect() will reload")
                    return@collectLatest
                }
                android.util.Log.i("WE-DIAG", "$reason: re-prewarming engine")
                // (4.0, Q9) Both triggers can change the offer gate's answer, and the INSTALL one
                // is the case a memo taken at service start cannot see: Q8's importer writes the
                // 358 MB pair into files/models while this service is up, and the gate's installed
                // half is a live stat. Re-read before warmLocalEngine() decides which backend the
                // engine it may be about to rebuild is built on.
                refreshNpuTierOffer()
                // THE GATE, RE-READ BELOW THE SUSPENSION (4.0, Q9 fix round 2). The check at the
                // top of this block was taken before refreshNpuTierOffer() hopped to IO and back,
                // so by here it is stale — a session can have started across that suspension. This
                // read is what makes the rebuild below safe, and it is safe *because* nothing
                // between it and the call suspends: currentState is a plain non-volatile var with
                // a single writer (updateBubbleState), i.e. already Main-confined by the whole
                // file's existing correctness, warmLocalEngine and prewarmModelSwitch are ordinary
                // functions, and collectLatest can only cancel at a suspension point. One
                // uninterrupted run on Main, so the state cannot change underneath it.
                if (currentState != BubbleState.IDLE && currentState != BubbleState.ERROR) {
                    android.util.Log.i("WE-DIAG", "$reason: session started while re-reading the gate — connect() will reload")
                    return@collectLatest
                }
                // AND THE REBUILD IS PERMITTED HERE. Without it this collector gets the cached
                // engine back UNCHANGED after a tier switch and calls prewarmModelSwitch() on it —
                // which loads the NEW tier's file through the OLD tier's backend. Both directions
                // are wrong and one of them is loud: an npu-backed engine handed a ggml path
                // resolves a null companion and publishes `unavailable stage=companion`, i.e. a
                // FALSE decline card, a process-sticky routing block, and a spurious line in the
                // one log the Q10a run-book tells the owner to trust.
                //
                // prewarmModelSwitch queues onto the engine's executor, which onDestroy shuts
                // down — a rejection escaping here would kill this collector for good. Belt and
                // braces only: onDestroy cancels serviceScope BEFORE it touches the engine, so
                // this body cannot run against a shut-down executor. Caught narrowly, never as a
                // blanket Throwable, so a CancellationException is always free to propagate.
                try {
                    warmLocalEngine(allowRebuild = true).prewarmModelSwitch()
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    android.util.Log.i("WE-DIAG", "$reason: re-prewarm skipped (engine shut down)")
                }
            }
        }
    }

    /**
     * Register this service as the focus listener.
     */
    private fun registerFocusListener() {
        WhisperAccessibilityService.setFocusListener(this)
        WhisperAccessibilityService.setClipboardListener(this)
    }

    // Attention pulse on the speaker lobe when something lands on the clipboard (user design
    // 2026-07-18): alternating red/blue with a breathing scale — "tap me to hear this".
    private var clipPulseAnimator: android.animation.ValueAnimator? = null

    /** The auto-hide for a clipboard-summoned bubble; re-copying restarts it. */
    private var clipboardAutoHideJob: kotlinx.coroutines.Job? = null

    override fun onClipboardChanged() {
        serviceScope.launch(Dispatchers.Main) {
            if (currentState != BubbleState.IDLE || isSpeakingNow ||
                com.whispereverywhere.tts.TtsController.isSpeechActive()
            ) return@launch
            if (!com.whispereverywhere.tts.TtsController.isVoiceInstalled(this@FloatingBubbleService)) return@launch
            // Warm the ~2 s voice-model load inside the copy -> lobe-tap think-time (this
            // preload used to ride the deleted selection morph; the copy that pulses the
            // speaker lobe below is the new warm signal). No-op if already loaded.
            com.whispereverywhere.tts.TtsController.preload(this@FloatingBubbleService)
            // Auto pop-up mode with the bubble hidden: a copy is a read-aloud OPPORTUNITY the user
            // cannot take on an invisible bubble (owner gap report 2026-08-01). Summon it at the
            // remembered spot for long enough to tap the pulsing speaker lobe, then leave. The
            // Rect is context-only — positioning is the user's own remembered spot.
            val summoned = !alwaysOnMode() && !isBubbleVisible
            if (summoned) showBubbleNearTextField(Rect())
            pulseSpeakerLobe()
            if (summoned) {
                clipboardAutoHideJob?.cancel()
                clipboardAutoHideJob = serviceScope.launch(Dispatchers.Main) {
                    // The pulse runs ~7 s; give the full window plus a beat to decide.
                    delay(8_000)
                    // A read the user DID start keeps the bubble through the whole job.
                    while (isSpeakingNow) delay(500)
                    // Leave quietly — unless the user meanwhile put the bubble to real use
                    // (recording, a focused field, playing media), in which case it is theirs now.
                    if (currentState == BubbleState.IDLE &&
                        currentContext == BubbleContext.NONE &&
                        !alwaysOnMode()
                    ) {
                        stopClipPulse()
                        hideBubble("clipboard-autohide")
                    }
                }
            }
        }
    }

    private fun pulseSpeakerLobe() {
        clipPulseAnimator?.cancel()
        speakerLobe.visibility = View.VISIBLE
        var flip = false
        clipPulseAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            repeatCount = 15 // ~7 s of attention, then settle
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                val scale = 1f + 0.18f * kotlin.math.sin(v * Math.PI.toFloat())
                speakerLobe.scaleX = scale
                speakerLobe.scaleY = scale
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: android.animation.Animator) {
                    flip = !flip
                    speakClipIcon.setColorFilter(
                        if (flip) 0xFFFF3B30.toInt() else 0xFF4FC3F7.toInt(),
                    )
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    speakerLobe.scaleX = 1f
                    speakerLobe.scaleY = 1f
                    speakClipIcon.setColorFilter(0xFF4FC3F7.toInt())
                }
            })
            start()
        }
    }

    private fun stopClipPulse() {
        clipPulseAnimator?.cancel()
        clipPulseAnimator = null
        speakerLobe.scaleX = 1f
        speakerLobe.scaleY = 1f
        speakClipIcon.setColorFilter(0xFF4FC3F7.toInt())
    }

    // ========== Read-aloud (Track F): speaker lobe / clipboard copy -> speak/stop ==========

    /**
     * Read whatever is on the clipboard aloud. Android 10+ blocks background clipboard reads,
     * but the FOCUSED window is allowed — so the overlay grabs input focus for one beat, reads
     * the clip, and releases. (Steals focus momentarily; acceptable for an explicit tap.)
     */
    private fun readClipboardAndSpeak() {
        if (isSpeakingNow || currentState != BubbleState.IDLE) return
        stopClipPulse()
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        runCatching { windowManager.updateViewLayout(bubbleView, params) }
        bubbleView.postDelayed({
            val clip = runCatching {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            }.getOrNull()
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { windowManager.updateViewLayout(bubbleView, params) }
            if (!clip.isNullOrBlank()) {
                startSpeaking(clip.take(100_000))
            } else {
                showToast("Nothing readable on the clipboard yet — copy some text first")
            }
        }, 300)
    }

    /** Speaking pill: aurora waveform driven by the SYNTHESIZED audio + stop control. */
    private fun enterSpeakingVisuals() {
        bubbleIcon.visibility = View.GONE
        processingRing.visibility = View.GONE
        lockLobe.visibility = View.GONE
        keyboardLobe.visibility = View.GONE
        speakerLobe.visibility = View.GONE
        setBubbleWidth(160)
        waveformView.visibility = View.VISIBLE
        waveformView.start()
        blobView.fillColor = android.graphics.Color.parseColor("#000000")
        blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.RECORDING)
        speechStopIcon.visibility = View.VISIBLE
        ttsScrubber.setProgress(0, 0, 0, false)
        ttsScrubber.visibility = View.VISIBLE
    }

    private fun exitSpeakingVisuals() {
        speechStopIcon.visibility = View.GONE
        ttsScrubber.visibility = View.GONE
        processingRing.visibility = View.GONE
        processingRing.clearAnimation()
        waveformView.stop()
        // The IDLE branch restores icon/width/blob for the current speaking state.
        if (currentState == BubbleState.IDLE) updateBubbleState(BubbleState.IDLE)
    }

    private fun startSpeaking(text: String) {
        isSpeakingNow = true
        val engine = com.whispereverywhere.tts.TtsController.engine(this)
        // Feed the pill's aurora from the synthesized slices (same contract as mic chunks:
        // thread-safe field writes only — redraw is ticker-driven on main).
        engine.onPcmChunk = { bytes, amp ->
            val bands = if (amp > 350) {
                com.whispereverywhere.util.AudioBands.analyze(bytes, bytes.size)
            } else {
                com.whispereverywhere.util.AudioBands.ZERO
            }
            waveformView.updateBands(bands)
            blobView.updateBands(bands)
            waveformView.updateAmplitude(amp)
            blobView.updateAmplitude(amp)
        }
        // "Not frozen, still working": when synthesis ever falls behind playback, the pill
        // shows the processing ring until audio flows again (user feedback 2026-07-18).
        engine.onBuffering = { buffering ->
            serviceScope.launch(Dispatchers.Main) {
                if (isSpeakingNow) {
                    processingRing.visibility = if (buffering) View.VISIBLE else View.GONE
                    if (buffering) startRotationAnimation() else processingRing.clearAnimation()
                }
            }
        }
        // Scrubber feed: played/available/total/done at ~10 Hz from the playback thread
        // (4.3.1 C: the bar spans the estimated read; ScrubberMath does the geometry).
        engine.onProgress = { played, available, total, done ->
            ttsScrubber.setProgress(played, available, total, done)
        }
        enterSpeakingVisuals()
        val started = com.whispereverywhere.tts.TtsController.speakFromTrigger(this, text) {
            // onDone (main thread): tear down the pill; selection may still be live.
            isSpeakingNow = false
            engine.onPcmChunk = null
            engine.onBuffering = null
            engine.onProgress = null
            exitSpeakingVisuals()
        }
        if (!started) {
            // The trigger bailed (voice not installed, arbiter busy): onDone will never fire, so
            // undo the speaking state set above or the pill stays "speaking" with nothing playing.
            isSpeakingNow = false
            engine.onPcmChunk = null
            engine.onBuffering = null
            engine.onProgress = null
            exitSpeakingVisuals()
        }
    }

    /**
     * Monitor the accessibility service connection and re-register if needed.
     */
    private fun startConnectionMonitor() {
        connectionMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(5000)
                registerFocusListener()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Explicit user stop (notification action): record the intent here — onDestroy
                // must NOT do it, or programmatic restarts (mode toggle) clobber the preference.
                app.preferencesManager.setBubbleEnabled(false)
                stopSelf()
            }
        }
        registerFocusListener()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Order matters (final-review fix C3): kill the 5-second re-register monitor BEFORE
        // nulling the listeners, or it can re-install this dying service as the listener.
        connectionMonitorJob?.cancel()
        WhisperAccessibilityService.setFocusListener(null)
        WhisperAccessibilityService.setClipboardListener(null)
        // Detach the arbiter's capture side — its lambdas close over this dying instance.
        com.whispereverywhere.audio.AudioArbiter.isCapturing = { false }
        com.whispereverywhere.audio.AudioArbiter.stopCaptureGracefully = {}
        com.whispereverywhere.tts.TtsController.stop()
        mediaDetector.setListener(null)
        mediaDetector.stopMonitoring()
        serviceScope.cancel()
        pulseAnimator?.cancel()
        showAnimator?.cancel()
        hideAnimator?.cancel()
        audioRecorder.stop()
        stopPlaybackCapturer()
        com.whispereverywhere.audio.MediaProjectionGate.listener = null
        com.whispereverywhere.audio.MediaProjectionGate.clear()
        // W2 best-effort: a service destroyed mid-session (system kill, mode toggle) still
        // delivers what it accumulated, through the SAME single-delivery policy as a normal
        // stop — and it must run BEFORE teardownRealtime, which ends the injection-session
        // binding the SESSION_BOUND write needs. Deliberately synchronous (serviceScope is
        // already cancelled, so there is nothing to post to; toasts inside no-op harmlessly)
        // and failure-swallowed: destroy must never block or throw. finalDelivered (set by a
        // finalize that already delivered, then IDLE) keeps the write once-only.
        if (currentState == BubbleState.RECORDING || currentState == BubbleState.FINALIZING) {
            runCatching {
                deliverReleasedText(segmentOrderer.flush().text)
                transcriptSink?.close()
                val full = transcriptSink?.fullTextFile()?.readText()?.trim() ?: ""
                // Empty ⇒ either nothing was said or the finalize coroutine already detached the
                // sink mid-read — either way, never claim finalDelivered with nothing delivered.
                if (full.isNotEmpty()) deliverFinalTranscript(full)
            }
        }
        teardownRealtime()
        // Fully release on service end: free the native context and stop its worker thread
        // (teardownRealtime only detaches the session listener, for reuse).
        //
        // Order and target both matter. The wrapper is CLOSED (resolving anything it still owes,
        // and cleaning up the cloud engine, whose shutdown() is close()), then the local engine —
        // the sole owner of the native context — is SHUT DOWN exactly once. Going through
        // `transcriptionEngine` instead would leak the context whenever the service is destroyed
        // after prewarm but before any recording, because that field is still null at that point.
        cloudWrapper?.close()
        cloudWrapper = null
        transcriptionEngine = null
        localEngine?.shutdown()
        localEngine = null
        streamingPreview?.release()
        streamingPreview = null
        httpTransport = null
        liveWsFactory = null
        // Shut the reconnect scheduler's daemon executor down BEFORE nulling the field, or the
        // "realtime-reconnect" thread leaks for the life of the process (release-audit Minor A).
        (liveReconnectScheduler as? com.whispereverywhere.transcription.live.ExecutorReconnectScheduler)?.shutdown()
        liveReconnectScheduler = null
        notifiedChoice = null
        lastCloudEngine = null
        lastLiveEngine = null
        notifiedFatalKind = null
        try {
            windowManager.removeView(bubbleView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // NOTE: deliberately NOT setBubbleEnabled(false) here. onDestroy also runs for
        // programmatic restarts (bubble-mode toggle) and system kills — only explicit user
        // stops (HomeScreen toggle, notification Stop action) record disabled intent.
    }

    // ========== Configuration changes (rotation / fold — drift hardening) ==========

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-clamp bubble position to the updated screen bounds after a rotation or fold event.
        // Post to the next layout pass so displayMetrics already reflects the new orientation.
        bubbleView.post {
            // Re-apply the panel size FIRST: the size clamps re-derive from the new
            // orientation's metrics (a landscape-max panel must shrink for portrait,
            // or its top-right handle lands off-screen with no way to grab it).
            if (transcriptionPreviewContainer.visibility == View.VISIBLE) {
                applyPreviewSize()
            }
            reclampAfterConfigChange()
        }
    }

    // ========== Bubble display mode ==========

    /**
     * True = "always on screen": the bubble lives wherever the user placed/pinned it and NEVER
     * auto-moves or auto-hides; focus/media events only change what a tap dictates into.
     * False = "auto pop-up": classic behavior (appear near focused fields / during media, hide
     * when idle). User-facing toggle in Settings -> Preferences.
     *
     * WITHOUT THE ACCESSIBILITY SERVICE THE PREFERENCE CANNOT BE HONOURED (4.3.3, review N1):
     * auto pop-up has exactly two summons — the service's text-field focus callback and media —
     * so a user with always-on OFF and no service enabled gets "Bubble is active" and nothing on
     * screen, with no way to start a recording. Clipboard mode is a VISIBLE bubble the user taps.
     * The service-off read is therefore an OR, not a gate: with the service ON `isEnabled()` is
     * true, `!isEnabled()` is false, and this is the preference alone — every caller (onCreate's
     * start-at-rest, the summon arithmetic, focus/media auto-show, [BubbleHidePolicy]'s `alwaysOn`)
     * behaves exactly as it did before this build.
     */
    private fun alwaysOnMode(): Boolean =
        app.preferencesManager.isBubbleAlwaysOn() || !WhisperAccessibilityService.isEnabled()

    // ========== Text Field Focus Listener ==========

    override fun onTextFieldFocused(rect: Rect) {
        serviceScope.launch(Dispatchers.Main) {
            // Device-audio capture is its own activity (user decision 2026-07-18): focusing a
            // text field while capturing media FINISHES that session gracefully (full single
            // transcript -> clipboard + history). Dictation into the field then starts fresh on
            // the next bubble tap. Mic sessions are untouched — only the playback source
            // auto-stops — and focus events from our own app/overlay never trigger it.
            if (currentState == BubbleState.RECORDING &&
                activeSource == com.whispereverywhere.audio.ActiveSource.PLAYBACK &&
                WhisperAccessibilityService.lastFocusedFieldPackage() != packageName
            ) {
                android.util.Log.i("WE-DIAG", "field focused during media capture -> finishing session")
                stopRecording()
            }
            currentContext = BubbleContext.TEXT_FIELD
            shouldHideOnIdle = false
            // Always-on: the bubble is already where the user wants it — do not reposition.
            if (!alwaysOnMode()) showBubbleNearTextField(rect)
        }
    }

    override fun onTextFieldUnfocused() {
        serviceScope.launch(Dispatchers.Main) {
            if (currentContext == BubbleContext.TEXT_FIELD) {
                if (currentState == BubbleState.IDLE) {
                    delay(200)
                    if (currentState == BubbleState.IDLE && !WhisperAccessibilityService.hasActiveFocusedField()) {
                        // Check if media is playing - if so, switch to media context
                        if (mediaDetector.isCurrentlyPlaying()) {
                            currentContext = BubbleContext.MEDIA_PLAYBACK
                            if (!alwaysOnMode()) showBubbleForMedia()
                        } else {
                            currentContext = BubbleContext.NONE
                            hideBubble("field-unfocused")
                        }
                    }
                } else {
                    shouldHideOnIdle = true
                }
            }
        }
    }

    // ========== Media Playback Listener ==========

    override fun onMediaPlaybackStarted(packageName: String, title: String?) {
        serviceScope.launch(Dispatchers.Main) {
            mediaTitle = title ?: getAppNameFromPackage(packageName)

            // Only show for media if no text field is focused
            if (currentContext != BubbleContext.TEXT_FIELD) {
                currentContext = BubbleContext.MEDIA_PLAYBACK
                if (!alwaysOnMode()) showBubbleForMedia()
            }

            // User decision: media transcription cuts the mic. If a mic recording is live when
            // playback begins (mic-button-first flow), hand over to the device stream.
            //
            // 4.4.0 S2, round 1 (B3) — THE STATE GATE HAS A BLIND WINDOW NOW, AND THIS DETECTOR
            // DOES NOT REPEAT ITSELF. Until S2 the source was decided inside onOpen, so a video
            // started during the model load was seen by the DECISION itself
            // (AudioSourcePolicy.decide's mediaPlaying) and the session simply opened on device
            // audio. The decision is sampled at the TAP now, which leaves this callback as the only
            // compensating trigger — and RECORDING is false for the whole CONNECTING window:
            // 4,107 ms on npu-turbo cold, up to 11,672 ms for the first ggml load in a process
            // (investigation §3a/§3b). MediaSessionDetector.handlePlaybackStateChanged is
            // EDGE-triggered (`if (!isMediaPlaying || currentMediaPackage != packageName)`), so a
            // notification dropped here is the only one that app will send while it keeps playing:
            // the session would stay on the microphone for its whole life, recording the room and
            // the speaker bleed instead of the stream, on the very flow this comment calls
            // supported. So the edge is LATCHED and re-offered at readiness — see
            // [pendingDeviceAudioHandover] and its consumption in onOpen.
            if (currentState == BubbleState.RECORDING) {
                handOverMicToDeviceAudio()
            } else if (currentState == BubbleState.CONNECTING && consentBudget.asked == 0) {
                // The budget clause is the one case a latch would be wrong: during CONNECTING an
                // ask can only have come from the TAP's own decision (startAudioInput's
                // RequestConsent branch), so the device-audio question is already in front of the
                // user and stacking a second consent sheet over it at readiness would spend the
                // 4.3.1 D budget on the same question. Cost, named: a NEW app starting playback
                // during the connect of a session whose consent sheet is already up does not get a
                // second handover offer.
                pendingDeviceAudioHandover = true
                android.util.Log.i("WE-DIAG", "media started during connect -> handover latched for readiness")
            }
        }
    }

    /**
     * "MEDIA TRANSCRIPTION CUTS THE MIC" (owner decision) — the one handover the device-audio latch
     * allows, in the one place both of its triggers reach. Main thread only.
     *
     * The triggers are the two moments the question can be put: [onMediaPlaybackStarted], when
     * playback begins during a live session, and `onOpen`, for a start that arrived while the
     * session was still CONNECTING and was latched there (4.4.0 S2, round 1 —
     * [pendingDeviceAudioHandover]). The STATE gate belongs to the callers: the media callback
     * requires RECORDING, and `onOpen` has just set it.
     *
     * Everything else is re-read HERE, in the callee, and that is the point of the shape: between a
     * latch and readiness the source may already have become PLAYBACK (a consent grant landed
     * meanwhile), or the preference may have changed, and a handover decided on a stale world is
     * how the latch would become its own bug.
     */
    private fun handOverMicToDeviceAudio() {
        if (activeSource != com.whispereverywhere.audio.ActiveSource.MIC ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            !app.preferencesManager.isPreferDeviceAudio()
        ) {
            // ROUND 2 (round-1 review, nit 6): SAY SO. Both triggers log a handover on the line
            // that calls this, and `onOpen`'s says "media still playing at readiness" BEFORE the
            // call — so a latch consumed into a no-op here (the source is already PLAYBACK because
            // a consent grant landed during the load, or the preference was turned off mid-load,
            // or pre-Q) otherwise reads in the log as a handover that happened. State only; when
            // neither number below explains it, the preference is the reason.
            android.util.Log.i("WE-DIAG",
                "handover declined at re-read: source=$activeSource sdk=${Build.VERSION.SDK_INT}")
            return
        }
        if (com.whispereverywhere.audio.MediaProjectionGate.hasProjection()) {
            switchSource(to = com.whispereverywhere.audio.ActiveSource.PLAYBACK)
        } else if (consentBudget.mayAsk()) {
            // Stop the mic NOW, flush what it captured, cut the boundary (never mix room audio
            // into a media session), then ask; capture starts when consent lands.
            //
            // 4.4.0 S2 — THIS BRANCH IS switchSource's SIBLING AND NEEDS switchSource's FLUSH, on
            // a straight-line path rather than a race. It is reachable through the whole
            // ~1.4-2.0 s paced catch-up (switchSource's gate and this one are the same gate), and
            // it STOPS the microphone: after that nothing drains the ring, because the drain is
            // driven by live chunks alone. So a ring left standing here survives until consent
            // lands, and startPlaybackSource()'s first chunk then takes the DRAIN route and
            // replays up to 6 s of ROOM AUDIO into the device-audio segment. Same order as every
            // other handover: stop+join, flush, cut — see flushStartupRingAtSourceHandover().
            audioRecorder.stop()
            flushStartupRingAtSourceHandover()
            transcriptionEngine?.let { commitSegment(it, EndpointDiag.SWITCH) }
            consentBudget.noteAsked()
            android.util.Log.i("WE-DIAG", "projection consent: asked=${consentBudget.asked}/${com.whispereverywhere.audio.ProjectionConsentBudget.MAX_ASKS_PER_SESSION}")
            com.whispereverywhere.audio.MediaProjectionGate.listener = projectionListener
            com.whispereverywhere.audio.MediaProjectionGate.requestConsent(this@FloatingBubbleService)
            showToast("Allow screen capture to transcribe device audio")
        } else {
            // 4.3.1 D: the budget is spent — the session is the microphone's. Say so ONCE;
            // the video resuming after every cancel would otherwise toast on every resume.
            if (!consentExhaustedToastShown) {
                consentExhaustedToastShown = true
                android.util.Log.i("WE-DIAG", "projection consent: budget spent -> microphone for this session")
                showToast("Using the microphone for this session — screen capture was declined")
            }
        }
    }

    override fun onMediaPlaybackStopped() {
        serviceScope.launch(Dispatchers.Main) {
            mediaTitle = null

            // Only hide if we were showing for media
            if (currentContext == BubbleContext.MEDIA_PLAYBACK) {
                if (currentState == BubbleState.IDLE) {
                    currentContext = BubbleContext.NONE
                    hideBubble("media-stopped")
                } else {
                    // Recording in progress - will hide when done
                    shouldHideOnIdle = true
                }
            }

            // THE DEVICE-AUDIO LATCH (owner rule, 2026-09-02). A session capturing device audio
            // NEVER hands back to the microphone — not on a pause, not on a scrub, not when the
            // video ends. It used to: this branch called switchSource(MIC) whenever the media
            // session stopped reporting playback, which a scrub does routinely. The projection
            // was still live, so the app recorded THE ROOM into the video's transcript until
            // playback resumed, and the resume cut a second segment — two spurious cuts and the
            // user's own speech in a transcript that was supposed to contain only the video's.
            // The owner's rule: "we're trying to keep the microphone out of it so someone could
            // transcribe a YouTube video without their actual spoken words being dictated."
            //
            // The mic returns the only way that cannot bleed: the user ends this transcription
            // and starts another. stopRecording releases the projection there (the 2026-08-01
            // decision — a live projection interferes with mic capture, and the sharing indicator
            // must not outlive the transcript), so the next session begins clean.
            //
            // A silent stretch costs nothing: the capturer yields silence, the VAD emits no
            // segments, and DeviceAudioLatchPinTest pins the absence of any switch here.
        }
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            "Media"
        }
    }

    // ========== Bubble Display ==========

    private fun showBubbleForMedia() {
        if (isBubbleVisible && currentState != BubbleState.IDLE) {
            return
        }

        hideAnimator?.cancel()

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        // The REMEMBERED spot, exactly like the text-field pop-up (owner 2026-08-01: the bubble
        // appears "right where I placed it last" — the old bottom-right media default was the
        // final teleporter left after the drag snap was removed). Pinned still wins. Clamped
        // through THE clamp against the window's REAL size (W3), not the old 56dp pill guess.
        val (winW, winH) = currentWindowSize()
        val restored = clampToBounds(
            (app.preferencesManager.bubblePositionX * screenWidth).toInt(),
            (app.preferencesManager.bubblePositionY * screenHeight).toInt(),
            winW,
            winH,
        )
        var targetX = restored.first
        var targetY = restored.second
        if (isOverlayPinned) {
            val pinned = savedPinnedPosition()
            targetX = pinned.first
            targetY = pinned.second
        }

        if (!isBubbleVisible) {
            params.x = targetX
            params.y = targetY
            bubbleView.alpha = 0f
            bubbleView.scaleX = 0.5f
            bubbleView.scaleY = 0.5f
            bubbleView.visibility = View.VISIBLE

            try {
                windowManager.updateViewLayout(bubbleView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            showAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = OvershootInterpolator(1.2f)
                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    bubbleView.alpha = value
                    bubbleView.scaleX = 0.5f + (0.5f * value)
                    bubbleView.scaleY = 0.5f + (0.5f * value)
                }
                start()
            }

            isBubbleVisible = true

            // Show toast to inform user
            showToast("Tap bubble to transcribe audio")
        } else {
            // Animate to position
            animateBubbleTo(targetX, targetY)
        }
    }

    private fun showBubbleNearTextField(rect: Rect) {
        if (isBubbleVisible && currentState != BubbleState.IDLE) {
            return
        }

        hideAnimator?.cancel()

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        // The bubble pops up WHERE IT WAS LAST — the user's dragged/remembered spot — never at a
        // field-derived position (owner decision 2026-08-01: "it should just pop up where it
        // popped up last time, or where the user moved the bubble to... a new location can be
        // annoying — users won't know where their bubble's gonna be"). The rect parameter is now
        // context-only (what focused, not where to go); bubblePositionX/Y is already written by
        // every drag, so the spot tracks the user for free. Pinned keeps its own stronger spot.
        // Clamped through THE clamp against the window's REAL size (W3) — this site used to skip
        // the navbar term the media site subtracted; the shared clamp ends that disagreement.
        val (winW, winH) = currentWindowSize()
        val restored = clampToBounds(
            (app.preferencesManager.bubblePositionX * screenWidth).toInt(),
            (app.preferencesManager.bubblePositionY * screenHeight).toInt(),
            winW,
            winH,
        )
        var targetX = restored.first
        var targetY = restored.second
        if (isOverlayPinned) {
            val pinned = savedPinnedPosition()
            targetX = pinned.first
            targetY = pinned.second
        }

        if (!isBubbleVisible) {
            params.x = targetX
            params.y = targetY
            bubbleView.alpha = 0f
            bubbleView.scaleX = 0.5f
            bubbleView.scaleY = 0.5f
            bubbleView.visibility = View.VISIBLE

            try {
                windowManager.updateViewLayout(bubbleView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            showAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = OvershootInterpolator(1.2f)
                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    bubbleView.alpha = value
                    bubbleView.scaleX = 0.5f + (0.5f * value)
                    bubbleView.scaleY = 0.5f + (0.5f * value)
                }
                start()
            }

            isBubbleVisible = true
        } else {
            animateBubbleTo(targetX, targetY)
        }
    }

    /**
     * Show the bubble at its resting spot: the saved/pinned position (prefs-persisted; defaults
     * to bottom-right for fresh installs). Used by always-on mode, where the bubble never moves
     * itself — this is the only placement it ever gets.
     */
    private fun showBubbleAtRest() {
        if (isBubbleVisible) return
        hideAnimator?.cancel()

        val pos = savedPinnedPosition()
        params.x = pos.first
        params.y = pos.second
        bubbleView.alpha = 0f
        bubbleView.scaleX = 0.5f
        bubbleView.scaleY = 0.5f
        bubbleView.visibility = View.VISIBLE

        try {
            windowManager.updateViewLayout(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        showAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                bubbleView.alpha = value
                bubbleView.scaleX = 0.5f + (0.5f * value)
                bubbleView.scaleY = 0.5f + (0.5f * value)
            }
            start()
        }

        isBubbleVisible = true
    }

    private fun animateBubbleTo(targetX: Int, targetY: Int) {
        val startX = params.x
        val startY = params.y

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                params.x = (startX + (targetX - startX) * progress).toInt()
                params.y = (startY + (targetY - startY) * progress).toInt()
                try {
                    windowManager.updateViewLayout(bubbleView, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            start()
        }
    }

    /**
     * THE ONE SINK every hide goes through (4.3.1 B). The decision is [BubbleHidePolicy]'s: always-on
     * and an already-hidden bubble are ignored; a read in progress PARKS the reason instead of
     * taking the pill away mid-playback (the owner's bug — a foreign window event during a read hid
     * the bubble while the audio played on); otherwise the hide animates. One WE-DIAG line per call
     * names the caller, so the next field report is one grep.
     */
    private fun hideBubble(reason: String) {
        val decision = BubbleHidePolicy.decide(
            speaking = isSpeakingNow, alwaysOn = alwaysOnMode(), visible = isBubbleVisible,
        )
        android.util.Log.i(
            "WE-DIAG",
            "bubble hide: reason=$reason decision=$decision state=$currentState context=$currentContext speaking=$isSpeakingNow",
        )
        when (decision) {
            BubbleHidePolicy.Decision.IGNORE -> return
            BubbleHidePolicy.Decision.DEFER -> { deferredHideReason = reason; return }
            BubbleHidePolicy.Decision.HIDE -> Unit
        }

        showAnimator?.cancel()

        hideAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                bubbleView.alpha = value
                bubbleView.scaleX = 0.5f + (0.5f * value)
                bubbleView.scaleY = 0.5f + (0.5f * value)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    bubbleView.visibility = View.GONE
                    isBubbleVisible = false
                }
            })
            start()
        }
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    private fun getNavigationBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return result
    }

    private fun createBubbleView() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        bubbleContainer = bubbleView.findViewById(R.id.bubble_container)
        bubbleIcon = bubbleView.findViewById(R.id.bubble_icon)
        processingRing = bubbleView.findViewById(R.id.processing_ring)
        waveformView = bubbleView.findViewById(R.id.waveform_view)
        blobView = bubbleView.findViewById(R.id.blob_view)
        recordingTimerText = bubbleView.findViewById(R.id.recording_timer_text)
        blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_background)
        processingTimeText = bubbleView.findViewById(R.id.processing_time_text)
        transcriptionPreviewContainer = bubbleView.findViewById(R.id.transcription_preview_container)
        transcriptionEditText = bubbleView.findViewById(R.id.transcription_edit_text)
        transcriptionDeltaText = bubbleView.findViewById(R.id.transcription_delta_text)
        resizeHandle = bubbleView.findViewById(R.id.resize_handle)
        resizeHandle.setOnTouchListener { _, event -> handleResizeTouch(event) }
        pinIcon = bubbleView.findViewById(R.id.pin_icon)
        speechStopIcon = bubbleView.findViewById(R.id.speech_stop_icon)
        speechStopIcon.setOnClickListener { com.whispereverywhere.tts.TtsController.stop() }
        speakClipIcon = bubbleView.findViewById(R.id.speak_clip_icon)
        lockLobe = bubbleView.findViewById(R.id.lock_lobe)
        keyboardLobe = bubbleView.findViewById(R.id.keyboard_lobe)
        speakerLobe = bubbleView.findViewById(R.id.speaker_lobe)
        lockLobe.setOnClickListener { togglePin() }
        // Dictation-first: summon (or re-hide) the system keyboard for the current field.
        keyboardLobe.setOnClickListener {
            val shown = WhisperAccessibilityService.toggleSummonedKeyboard()
            android.util.Log.i("WE-DIAG", "keyboard lobe: summoned=$shown")
        }
        speakerLobe.setOnClickListener { readClipboardAndSpeak() }
        ttsScrubber = bubbleView.findViewById(R.id.tts_scrubber)
        ttsScrubber.onSeek = { fraction ->
            com.whispereverywhere.tts.TtsController.engine(this).seekToFraction(fraction)
        }

        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        // Restore position and clamp to current screen bounds (drift hardening)
        val displayMetrics = resources.displayMetrics
        val rawX = (app.preferencesManager.bubblePositionX * displayMetrics.widthPixels).toInt()
        val rawY = (app.preferencesManager.bubblePositionY * displayMetrics.heightPixels).toInt()
        // Size-aware estimate for clamping before the view is measured (the preview is GONE at
        // inflate, so this resolves to the same 64dp pill estimate as before); the measured size
        // takes over at the next reclamp (config change / preview show).
        val (estW, estH) = currentWindowSize()
        val clamped = clampToBounds(rawX, rawY, estW, estH)
        params.x = clamped.first
        params.y = clamped.second

        // Restore pinned state
        isOverlayPinned = app.preferencesManager.overlayPinned
        applyPinIndicator()

        // Wire up the pin icon tap — toggles pin state without triggering the bubble click

        bubbleView.setOnTouchListener { _, event ->
            handleTouch(event)
        }

        transcriptionEditText.movementMethod = android.text.method.ScrollingMovementMethod()
        applyPreviewSize()

        windowManager.addView(bubbleView, params)

        bubbleView.visibility = View.GONE
        bubbleView.alpha = 0f
        isBubbleVisible = false

        updateBubbleState(BubbleState.IDLE)
    }

    // ========== Transcript preview sizing (W3) ==========

    /**
     * Apply the persisted (or, mid-resize, the in-flight) preview panel size to both transcript
     * views. dp -> px against the CURRENT displayMetrics and re-clamped to the CURRENT screen on
     * every call, so a stale/corrupt pref or a rotation can never produce an off-screen or
     * zero-size panel. Runtime owns the panel's width and max height; the XML 280dp width is only
     * the pre-first-apply default and android:maxHeight was removed from the layout entirely.
     */
    private fun applyPreviewSize(
        widthDp: Float = app.preferencesManager.bubbleTextWidthDp,
        heightDp: Float = app.preferencesManager.bubbleTextHeightDp,
    ) {
        val dm = resources.displayMetrics
        val maxW = ResizeMath.maxWidthDp(dm.widthPixels, dm.density)
            .coerceAtLeast(ResizeMath.MIN_WIDTH_DP)
        val maxH = ResizeMath.maxHeightDp(dm.heightPixels, dm.density)
            .coerceAtLeast(ResizeMath.MIN_HEIGHT_DP)
        val widthPx = (widthDp.coerceIn(ResizeMath.MIN_WIDTH_DP, maxW) * dm.density).toInt()
        val heightPx = (heightDp.coerceIn(ResizeMath.MIN_HEIGHT_DP, maxH) * dm.density).toInt()
        transcriptionEditText.layoutParams = transcriptionEditText.layoutParams.apply { width = widthPx }
        transcriptionEditText.maxHeight = heightPx
        transcriptionDeltaText.layoutParams = transcriptionDeltaText.layoutParams.apply { width = widthPx }
    }

    /**
     * Estimated full-window dims (px) for a GIVEN panel size — used when the view isn't laid out
     * yet or mid-resize when the measured size lags a frame. Base: the 64dp pill estimate the old
     * clamps used. When the preview is visible: width = panel + 48dp chrome (16dp container
     * padding per side + 8dp root padding per side); height stacks panel + 48dp chrome (12dp
     * container padding top/bottom + 8dp bottom margin + 8dp root padding top/bottom) on the
     * pill; the live-mode delta strip, when visible, gets its own ~100dp allowance (it is not
     * part of heightDp). A slight OVER-estimate by design: clamping with a too-big window only
     * keeps it further from the screen edge — the safe direction.
     */
    private fun estimatedWindowSize(widthDp: Float, heightDp: Float): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        val pillEstimate = (64 * density).toInt()
        if (transcriptionPreviewContainer.visibility != View.VISIBLE) {
            return Pair(pillEstimate, pillEstimate)
        }
        val panelW = ((widthDp + 48f) * density).toInt()
        // Live sessions stack the delta strip (maxLines=5, italic) below the main panel —
        // without this allowance the estimate UNDER-shoots in live mode and a mid-resize
        // clamp could leave the window bottom off-screen.
        val stripAllowance =
            // != GONE, not == VISIBLE: 3.7's in-flight line parks the strip at INVISIBLE between
            // utterances, which still occupies its height in the layout. Reading that as "no
            // strip" would UNDER-shoot the estimate — the unsafe direction for a clamp.
            if (transcriptionDeltaText.visibility != View.GONE) (100 * density).toInt() else 0
        val panelH = ((heightDp + 48f) * density).toInt() + stripAllowance
        return Pair(maxOf(pillEstimate, panelW), pillEstimate + panelH)
    }

    /** The window's REAL current dims for clamping: measured when laid out, estimated otherwise. */
    private fun currentWindowSize(): Pair<Int, Int> {
        if (bubbleView.width > 0 && bubbleView.height > 0) {
            return Pair(bubbleView.width, bubbleView.height)
        }
        return estimatedWindowSize(
            app.preferencesManager.bubbleTextWidthDp,
            app.preferencesManager.bubbleTextHeightDp,
        )
    }

    /** Re-clamp params to bounds for the window's CURRENT size (preview shown/hidden, resize,
     *  reset). Post from a geometry-changing site so the measure pass has run first. */
    private fun reclampNow() {
        val (winW, winH) = currentWindowSize()
        val clamped = clampToBounds(params.x, params.y, winW, winH)
        if (clamped.first == params.x && clamped.second == params.y) return
        params.x = clamped.first
        params.y = clamped.second
        try {
            windowManager.updateViewLayout(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * The resize-handle gesture (W3). The handle CONSUMES its stream (child-first dispatch), so
     * the root drag / long-press-to-pin listener never sees these events. Width follows the
     * finger; height grows when dragging UP, and params.y compensates by the clamped growth so
     * the TOP edge follows the finger while the mic pill below stays put. Live-apply per move;
     * persist on ACTION_UP (size AND the moved y, exactly like the root drag-end persists
     * position); long-press without a drag resets to the 280x120dp defaults. Pin locks POSITION,
     * not size — resizing while pinned is allowed, and its y-compensation is part of resizing.
     */
    private fun handleResizeTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                resizeStartWidthDp = app.preferencesManager.bubbleTextWidthDp
                resizeStartHeightDp = app.preferencesManager.bubbleTextHeightDp
                resizeStartTouchX = event.rawX
                resizeStartTouchY = event.rawY
                resizeStartWindowY = params.y
                isResizing = false
                lastResizeResult = null
                // Same 500 ms threshold as the pin gesture; cancelled the moment a drag starts.
                resizeLongPressJob?.cancel()
                resizeLongPressJob = serviceScope.launch {
                    delay(LONG_PRESS_MS)
                    if (!isResizing) resetPreviewSize()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - resizeStartTouchX
                val dy = event.rawY - resizeStartTouchY
                if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                    isResizing = true
                    resizeLongPressJob?.cancel()
                }
                if (!isResizing) return true
                val dm = resources.displayMetrics
                val result = ResizeMath.resize(
                    startWidthDp = resizeStartWidthDp,
                    startHeightDp = resizeStartHeightDp,
                    dragDxPx = dx,
                    dragDyPx = dy,
                    density = dm.density,
                    screenWidthPx = dm.widthPixels,
                    screenHeightPx = dm.heightPixels,
                )
                // Live-apply through the SAME code path the persisted size uses, then move the
                // window's top edge with the finger and re-clamp against the NEW estimated dims.
                applyPreviewSize(result.widthDp, result.heightDp)
                val est = estimatedWindowSize(result.widthDp, result.heightDp)
                val clamped = clampToBounds(
                    params.x, resizeStartWindowY + result.windowDyPx, est.first, est.second,
                )
                params.x = clamped.first
                params.y = clamped.second
                try {
                    windowManager.updateViewLayout(bubbleView, params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                lastResizeResult = result
                return true
            }
            MotionEvent.ACTION_UP -> {
                resizeLongPressJob?.cancel(); resizeLongPressJob = null
                val result = lastResizeResult
                if (isResizing && result != null) {
                    app.preferencesManager.bubbleTextWidthDp = result.widthDp
                    app.preferencesManager.bubbleTextHeightDp = result.heightDp
                    // Persist the compensated spot exactly like the root drag-end does: the
                    // window's y moved with the resize, and pop-up restore reads these fractions.
                    val dm = resources.displayMetrics
                    if (dm.widthPixels > 0) {
                        app.preferencesManager.bubblePositionX = params.x.toFloat() / dm.widthPixels
                    }
                    if (dm.heightPixels > 0) {
                        app.preferencesManager.bubblePositionY = params.y.toFloat() / dm.heightPixels
                    }
                }
                isResizing = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                resizeLongPressJob?.cancel(); resizeLongPressJob = null
                isResizing = false
                return true
            }
        }
        return false
    }

    /** Long-press on the handle: back to the stock 280x120dp panel, persisted immediately —
     *  insurance against wedging the window tiny. */
    private fun resetPreviewSize() {
        app.preferencesManager.bubbleTextWidthDp = ResizeMath.DEFAULT_WIDTH_DP
        app.preferencesManager.bubbleTextHeightDp = ResizeMath.DEFAULT_HEIGHT_DP
        applyPreviewSize()
        bubbleView.post { reclampNow() }
        showToast("Preview size reset")
    }

    // ========== Pin / Lock ==========

    /** Toggle pinned state, persist, show feedback. */
    private fun togglePin() {
        isOverlayPinned = !isOverlayPinned
        app.preferencesManager.overlayPinned = isOverlayPinned
        if (isOverlayPinned) {
            // Persist the current spot so the pinned position survives hide/show and app restarts.
            val dm = resources.displayMetrics
            if (dm.widthPixels > 0) app.preferencesManager.bubblePositionX = params.x.toFloat() / dm.widthPixels
            if (dm.heightPixels > 0) app.preferencesManager.bubblePositionY = params.y.toFloat() / dm.heightPixels
        }
        applyPinIndicator()
        flashLockLobe()
        showToast(if (isOverlayPinned) "Bubble locked in place" else "Bubble unlocked")
    }

    /** The transient pin confirmation: the lock lobe appears for a beat, then leaves. */
    private var lockFlashJob: kotlinx.coroutines.Job? = null

    private fun flashLockLobe() {
        lockFlashJob?.cancel()
        lockLobe.visibility = View.VISIBLE
        lockFlashJob = serviceScope.launch(Dispatchers.Main) {
            delay(1_500)
            lockLobe.visibility = View.GONE
        }
    }

    /**
     * Lock metaphor (user decisions 2026-07-18): closed RED lock = position locked,
     * open white/gray lock = free placement.
     */
    private fun applyPinIndicator() {
        pinIcon.setImageResource(
            if (isOverlayPinned) R.drawable.ic_lock_closed else R.drawable.ic_lock_open,
        )
        if (isOverlayPinned) {
            pinIcon.setColorFilter(0xFFFF3B30.toInt())
            pinIcon.alpha = 1.0f
        } else {
            pinIcon.clearColorFilter()
            pinIcon.alpha = 0.6f
        }
    }

    // ========== Drift hardening helpers ==========

    /**
     * THE clamp: (x, y) coerced so a viewW x viewH window stays fully on the USABLE screen.
     * The navbar term lives HERE now, so every caller agrees on the bottom edge — the two
     * show-site y-clamps used to disagree on it. Falls back gracefully (0..0) when the screen
     * size is not yet determined.
     */
    private fun clampToBounds(x: Int, y: Int, viewW: Int, viewH: Int): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - viewW).coerceAtLeast(0)
        val maxY = (dm.heightPixels - viewH - getNavigationBarHeight()).coerceAtLeast(0)
        return Pair(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    /** The user's pinned bubble position from prefs (stored as screen fractions), in px, clamped
     *  against the window's REAL current size — not the old 56dp pill guess. */
    private fun savedPinnedPosition(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val x = (app.preferencesManager.bubblePositionX * dm.widthPixels).toInt()
        val y = (app.preferencesManager.bubblePositionY * dm.heightPixels).toInt()
        val (winW, winH) = currentWindowSize()
        return clampToBounds(x, y, winW, winH)
    }

    /**
     * Re-clamp after a configuration change (rotation / fold). Deliberately does NOT persist:
     * the saved fractions are written only by user drag-end, resize-end, and pin — a rotation
     * mid-session must never rewrite the user's spot using transient preview-inflated
     * dimensions. Restores clamp anyway, so nothing is lost by not writing here.
     */
    private fun reclampAfterConfigChange() {
        val (viewW, viewH) = currentWindowSize()
        val clamped = clampToBounds(params.x, params.y, viewW, viewH)
        params.x = clamped.first
        params.y = clamped.second
        try {
            windowManager.updateViewLayout(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                lastAction = event.action
                isDragging = false

                // Long-press fires after LONG_PRESS_MS to toggle pin, but only if we haven't
                // started dragging in the meantime (cancelled in ACTION_MOVE / ACTION_UP).
                longPressJob?.cancel()
                longPressJob = serviceScope.launch {
                    delay(LONG_PRESS_MS)
                    // Only fire if still holding down and not dragging
                    if (!isDragging) {
                        togglePin()
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                    isDragging = true
                    longPressJob?.cancel()   // Real drag — cancel long-press
                }

                // When pinned, suppress all drag movement; only taps (and long-press) register.
                // Clamped per step against the REAL window size, so a drag can no longer park
                // the preview-widened window partly off-screen (drag-end persists params as-is).
                if (!isOverlayPinned && isDragging) {
                    val (winW, winH) = currentWindowSize()
                    val clamped = clampToBounds((initialX + dx).toInt(), (initialY + dy).toInt(), winW, winH)
                    params.x = clamped.first
                    params.y = clamped.second
                    windowManager.updateViewLayout(bubbleView, params)
                }
                lastAction = event.action
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressJob?.cancel()
                longPressJob = null
                if (!isDragging) {
                    handleBubbleClick()
                } else if (!isOverlayPinned) {
                    // FREE placement in EVERY mode (owner 2026-08-01: the edge snap felt like the
                    // bubble "wants to lock to certain areas"). The snap also had a real bug: it
                    // ANIMATED to the edge over 200 ms while the position below saved the
                    // PRE-animation spot, so the remembered position and the visible bubble
                    // disagreed — every later hide/show "teleported" it. Where you drop it is
                    // where it stays, and exactly what pop-up restores.
                    val displayMetrics = resources.displayMetrics
                    app.preferencesManager.bubblePositionX = params.x.toFloat() / displayMetrics.widthPixels
                    app.preferencesManager.bubblePositionY = params.y.toFloat() / displayMetrics.heightPixels
                }
                lastAction = event.action
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressJob?.cancel()
                longPressJob = null
                lastAction = event.action
                return true
            }
        }
        return false
    }

    private fun setBubbleWidth(dp: Int) {
        val target = (dp * resources.displayMetrics.density).toInt()
        val lp = bubbleContainer.layoutParams
        if (lp.width == target) return
        // The blob body tracks the container with its 24dp ripple headroom (12dp per side).
        val blobLp = blobView.layoutParams
        val blobExtra = (24 * resources.displayMetrics.density).toInt()
        ValueAnimator.ofInt(lp.width, target).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                lp.width = it.animatedValue as Int
                bubbleContainer.layoutParams = lp
                blobLp.width = lp.width + blobExtra
                blobView.layoutParams = blobLp
            }
            start()
        }
    }

    private fun handleBubbleClick() {
        when (currentState) {
            BubbleState.IDLE -> when {
                // Tap while speaking = pause/resume (the small ✕ stops); user decision.
                isSpeakingNow -> {
                    val nowPaused = com.whispereverywhere.tts.TtsController
                        .engine(this).togglePause()
                    bubbleIcon.visibility = if (nowPaused) View.VISIBLE else View.GONE
                    if (nowPaused) bubbleIcon.setImageResource(R.drawable.ic_speaker)
                }
                else -> startRecording()
            }
            BubbleState.RECORDING -> stopRecording()
            BubbleState.CONNECTING, BubbleState.FINALIZING, BubbleState.PROCESSING -> { /* ignore */ }
            BubbleState.ERROR -> updateBubbleState(BubbleState.IDLE)
        }
    }

    // ========== Audio source state machine (mic <-> device-audio playback capture) ==========

    /** Shared downstream for BOTH audio sources (mic and playback capture).
     *  Runs on the capture/recorder THREAD, and from 4.4.0 S2 it can run BEFORE the engine has a
     *  loaded context — see the startup-seam block inside. The three things it does, in order: it
     *  routes the chunk (ring / paced replay / straight through), it feeds whatever is due to the
     *  engine through the one [feedEngine], and it paints the LIVE chunk's visuals. */
    private fun onAudioChunk(chunk: ByteArray, amp: Int) {
        // Route by SOURCE, never by preference. Playback capture is OTHER APPS' audio — the person
        // on the other end of the call, the podcast host — so the only engines allowed to receive
        // ONE engine serves the whole session, whatever the capture source. Device audio follows
        // the user's provider selection exactly as mic audio does (owner decision 2026-08-01):
        // selecting a cloud engine IS the consent that audio you transcribe goes to that provider,
        // and the disclosure + privacy policy say so for both sources in the same sentence. The
        // per-session MediaProjection consent sheet marks each capture. Cost is the user's own key,
        // knowingly. The old source-router (which forced device audio on-device regardless of
        // settings) was retired with this decision — see the 2026-08-01 commit for its rationale.
        val nowMs = System.currentTimeMillis()
        val engine = transcriptionEngine
        // ===================== 4.4.0 S2 — THE STARTUP SEAM's three-way head =====================
        // The recorder now opens ABOVE connect() (startRecording), so this callback can be running
        // before the engine has a loaded context. The three routes, and the pure decision
        // StartupSeam.route makes between them, are pinned behaviourally by StartupRingTest
        // (exactly-once, in-order, under live audio) and structurally by StartupRingWiringPinTest.
        //
        //  BUFFER — no engine yet: the chunk goes into the bounded ring with its OWN stamp.
        //  DRAIN  — engine ready, backlog outstanding: replay a PACED slice through this same
        //           feedEngine path, then queue this live chunk BEHIND whatever survived the
        //           slice (order is exactly-once and capture-ordered), or feed it straight
        //           through when the slice emptied the ring — which is what retires the seam
        //           instead of leaving the session permanently one chunk behind.
        //  LIVE   — level again: the pre-S2 path, byte for byte.
        //
        // The replay runs HERE, on the capture thread, and that is a correctness requirement, not
        // a convenience: VadProbeLifecycle keeps ONE shared direct buffer for the Silero probe, so
        // a second thread replaying into feedEngine would write it concurrently with the real
        // capture thread — teardown-bill T8 (torn frames) and T9 (cross-session LSTM
        // contamination), the same intra-session hazard switchSource carries as residue.
        when (StartupSeam.route(engineReady = engineReady, ringEmpty = startupRing.isEmpty())) {
            StartupSeam.Route.BUFFER -> bufferStartupChunk(chunk, amp, nowMs)
            StartupSeam.Route.DRAIN -> if (engine != null) {
                startupRing.drainSlice(StartupRing.DRAIN_CHUNKS_PER_TICK) { pcm, pcmAmp, pcmNowMs ->
                    feedEngine(engine, pcm, pcmAmp, pcmNowMs)
                }
                if (startupRing.isEmpty()) feedEngine(engine, chunk, amp, nowMs)
                else startupRing.append(chunk, amp, nowMs)
            }
            StartupSeam.Route.LIVE -> if (engine != null) feedEngine(engine, chunk, amp, nowMs)
        }
        // ALWAYS the LIVE chunk, on all three routes. The visuals are the one thing that must not
        // be replayed: painting the ring's 4-second-old audio at 4x during the catch-up would make
        // the bubble lie about what the microphone is hearing right now, and it is also what keeps
        // the bubble moving during CONNECTING, where before S2 there was nothing to paint at all.
        paintVisuals(chunk, amp)
    }

    /**
     * The engine is not ready yet (4.4.0 S2): keep the chunk. Capture thread.
     *
     * The overflow line is emitted at most ONCE per session — see [startupOverflowLogged].
     */
    private fun bufferStartupChunk(chunk: ByteArray, amp: Int, nowMs: Long) {
        val dropped = startupRing.append(chunk, amp, nowMs)
        if (dropped > 0 && !startupOverflowLogged) {
            startupOverflowLogged = true
            android.util.Log.w("WE-DIAG", StartupRing.overflowLine(StartupRing.msOf(dropped)))
        }
    }

    /**
     * The visuals for ONE chunk. Capture/recorder thread: the View calls below are thread-safe
     * field writes only (redraw is ticker-driven on Main) — do not add invalidate() here.
     *
     * 4-band spectral drive (microseconds per 32 ms frame): each aurora sheet + rim region follows
     * its own slice of the audio. Silence gate: below the noise floor send explicit zeros so the
     * AGC never normalizes room noise into fake motion.
     */
    private fun paintVisuals(chunk: ByteArray, amp: Int) {
        val bands = if (amp > 350) {
            com.whispereverywhere.util.AudioBands.analyze(chunk, chunk.size)
        } else {
            com.whispereverywhere.util.AudioBands.ZERO
        }
        waveformView.updateBands(bands)
        blobView.updateBands(bands)
        waveformView.updateAmplitude(amp)
        blobView.updateAmplitude(amp)
    }

    /**
     * ONE chunk into the engine and the endpointer — the pre-S2 body of [onAudioChunk], statement
     * for statement, minus the visuals. Capture thread only.
     *
     * **BOTH live audio and the replayed ring arrive here, and that is what "the drain goes
     * through the same `sendAudio` path live audio uses" means.** `sendAudio` stays UNCONDITIONAL
     * and FIRST (CapSeamPinTest), and [now] is the chunk's OWN capture stamp — the ring's for a
     * replayed chunk, `System.currentTimeMillis()` for a live one. Re-stamping a replay at drain
     * time is the mistake this signature exists to prevent: every wall-clock term below is
     * `now - <an earlier stamp>` (the hangover, the micro-pause promotion, the cadence floor, the
     * wall cap), so a 4 s burst restamped inside ~1 s would read as a 1 s burst and every dip in
     * it as shorter than it was. The terms that are NOT wall-clock — the flatline trigger's frame
     * COUNT and `speechEvidenceMs`'s `evidenceFrames * FRAME_MS` — are indifferent either way.
     */
    private fun feedEngine(engine: TranscriptionEngine, chunk: ByteArray, amp: Int, now: Long) {
        engine.sendAudio(chunk)
        // Client VAD per audio chunk. Commit on a natural pause, or on the wall-clock cap when
        // the amplitude never dips (continuous media). Server-driven live sessions (OpenAI,
        // ElevenLabs, Soniox) bypass this ENTIRELY — the SERVER cuts turns via its own VAD for
        // every capture source (device audio rides the same socket as mic audio since
        // 2026-08-01), and the stop button / session end is the outer bound. `sendAudio` above
        // stays UNCONDITIONAL, so the engine is always fed; only the turn CUT moves to the server.
        // Local + batch keep this block byte-identical — and so does a GEMINI live session
        // (4.3.4): its server VAD goes deaf after the first sentence, so this endpointer's cut is
        // the activityEnd the protocol sends (LiveTurnPolicy.clientCutsLiveTurns). Device-audio
        // capture in all of those sessions is cut here too.
        if (com.whispereverywhere.transcription.live.LiveTurnPolicy.runClientVad(sessionIsLive, sessionCloudProviderId)) {
            if (endpointer.onFrame(chunk, amp, now)) {
                android.util.Log.i("WE-DIAG", "VAD -> commit (rms=$amp)")
                segmentCapPolicy.onCommit(now)
                commitSegment(engine, EndpointDiag.VAD, nowMs = now)
            } else if (segmentCapPolicy.capExceeded(now)) {
                // currentCapMs() is read BEFORE onCommit flips first->later, so the line names
                // the cap that actually fired (4000ms for the session's first LOCAL stretch;
                // cloud sessions closed the first-cap window at onOpen and always read 15000ms).
                android.util.Log.i("WE-DIAG", EndpointDiag.capCommitLine(segmentCapPolicy.currentCapMs()))
                // A cap cut on SILENCE-ONLY audio still commits the buffer (bounded, and whisper's
                // VAD returns empty fast); only the policy bookkeeping below is conditional.
                // hasPendingSpeech() and pendingCutPointMs() are both read BEFORE
                // endpointer.reset(), which clears them.
                // The SegmentCapPolicy contract is unchanged (any onCommit consumes the window);
                // the silence exception lives here at the call site.
                //
                // Cap-cut policy bookkeeping:
                //  - real speech (any session): consume the first-cap window and restart the clock;
                //  - CLOUD silence: also consume/restart — the 4s window must NEVER re-open on cloud
                //    (a 4s cloud cut = an extra billable provider request; cap=4000ms in a cloud
                //    session is the bug signature);
                //  - LOCAL silence: re-arm the window so a user who pauses to think still gets the
                //    4s first cut on their first real speech (3.5.0 parity guarantee).
                //
                // 3.7 (Workstream D) — the CUTOUT term, and why it is an OR here rather than a
                // third parameter: see SileroEndpointer.isProbeCutout()'s KDoc. Once that latch
                // trips, every predicate on the endpointer freezes and hasPendingSpeech() is
                // pinned FALSE for the rest of the session, so without this term every later LOCAL
                // cap cut arrives as (false, false) and RE-ARMS the 4 s first-cap window —
                // a perpetual 4 s cadence, ~3.75x the encoder passes, on exactly the device that
                // could not afford the probe. Treating a cutout as "consumes the window" restores
                // the 4s-first/15s-steady cadence; the cost is that a genuinely silent cutout
                // session pays a few near-free empty commits at 15 s instead of at 4 s. The OR
                // lives HERE, at the call site, so capCutConsumesWindow itself stays SYMMETRIC and
                // its 4-row truth table keeps holding unchanged.
                // 4.4 — the SECOND way hasPendingSpeech() can be pinned false while real speech is
                // sitting in the buffer, and the reason a third disjunct joined the two above. The
                // latch above covers a dead probe; this covers a live one. `pendingSpeech` is set
                // only in the ONSET branch and only once MIN_SPEECH_MS of WALL TIME has elapsed
                // since the current gate-open, and the MIN_SPEECH discard calls closeGate(), which
                // re-anchors that clock. So audio made of sub-300 ms bursts separated by dips long
                // enough to reach the hangover — a percussive music bed, a sung syllable, an
                // effect sting — latches it FALSE forever: every cap cut then arrives as
                // (false, false), re-arms the 4 s first-cap window and the app cuts every four
                // seconds through the music for the rest of the session. That is the owner's one
                // named must-keep behaviour (the 15 s wall under background noise) inverted, at
                // ~3.75x the encoder passes, on audio that decodes to nothing.
                //
                // `pendingCutPointMs()` supplies the evidence `pendingSpeech` can no longer carry,
                // out of a field that already exists: `prevEndMs` is written at exactly one place,
                // past `onProb`'s `if (!speaking) return false`, so a non-sentinel offer means "the
                // gate really opened and a dip really outlived MICRO_PAUSE_MS inside this window".
                // It is cleared on every commit and every reset, so it cannot leak across windows,
                // and it is already read three lines below for the retain. The 3.5.0 LOCAL-silence
                // guarantee is untouched: a user who opens the session and says nothing never
                // reaches that write at all, so the 4 s window still re-arms for their first real
                // speech — the guarantee is scoped to SILENCE, and silence is exactly what still
                // satisfies it.
                if (capCutConsumesWindow(
                        hasPendingSpeech = endpointer.hasPendingSpeech() ||
                            endpointer.pendingCutPointMs() > Endpointer.NO_CUT_POINT ||
                            (endpointer as? SileroEndpointer)?.isProbeCutout() == true,
                        isCloudSession = cloudWrapper != null,
                    )
                ) {
                    segmentCapPolicy.onCommit(now)
                } else {
                    segmentCapPolicy.onSessionStart(now)
                }
                // 3.7 (Workstream D): when the endpointer remembers a micro-pause inside this
                // window, cut THERE and keep the tail — a strictly better boundary for the same
                // latency bound, because `no_context = true` makes a mid-word cap cut permanently
                // unrepairable. capCutRetainMs returns 0 for no offer, a stale offer, and for the
                // amplitude endpointer always; commitRetainingTailMs(0) IS engine.commit(). So an
                // endpointer that never fires leaves this branch byte-identical to 3.6.0.
                // The two same-typed Longs this used to pass CommitCadencePolicy directly (a swap
                // returns 0 for EVERY input, silently reverting the split to 3.6.0 — D2's M22
                // survived exactly that) now live inside capCutRetainWindowMs, where the binding is
                // pinned BEHAVIOURALLY by CapCutRetainWindowTest and a positional swap here cannot
                // even compile. See that function's KDoc.
                val retainMs = capCutRetainWindowMs(nowMs = now, endpointer = endpointer)
                // The funnel call inherits the same hazard and the same discipline: it ALSO ends in
                // two adjacent Longs, and swapping them degrades to a plain full commit (D6's
                // `cut <= 0` guard clamps) — silently, which is why both names are spelled out and
                // pinned verbatim by CapSeamPinTest and CommitFunnelPinTest.
                //
                // The `nowMs` half of that swap is still INERT here, and Task F9 did not change
                // that: the funnel's speech-end stamp is gated on `cut == EndpointDiag.VAD`, and
                // this is the CAP site. (An earlier version of this comment predicted F9 would hand
                // Workstream F's clock a 0-3000ms value; it does not, because a cap cut has no
                // speech-end instant to stamp in the first place. Measured, not assumed — F9's
                // battery ran the swap.) The live clock binding is the VAD site's `nowMs = now`,
                // which has no swap partner: its other two arguments are an engine and a String.
                commitSegment(engine, EndpointDiag.CAP, retainMs = retainMs, nowMs = now)
                // Residue inherited from D8 (report §5): the factory's epoch gate covers the NATIVE
                // probe reset alone. A stale capture thread reaching this line still writes EIGHT
                // of the live session's SileroEndpointer fields — including the cost governor's
                // anchor (lastCommitMs/hasCommitted, worth up to a full cadence interval) and both
                // of this branch's own inputs (pendingSpeech, prevEndMs). Reachability is bounded
                // by the T2-SHARPENED join argument, not by this gate; the cleanup is C-side.
                endpointer.reset()
            }
        }
    }

    /**
     * Stop and drop the playback capturer. The capturer class is @RequiresApi(Q); instances
     * only ever exist on Q+ (startPlaybackSource is version-gated), so the check here is for
     * the linter's benefit — it can't prove the field is null below Q.
     */
    private fun stopPlaybackCapturer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) playbackCapturer?.stop()
        playbackCapturer = null
    }

    /** Start the correct source for the current world (mic vs device-audio). */
    private fun startAudioInput(): Result<Unit> {
        val decision = com.whispereverywhere.audio.AudioSourcePolicy.decide(
            mediaPlaying = mediaDetector.isCurrentlyPlaying(),
            hasProjection = com.whispereverywhere.audio.MediaProjectionGate.hasProjection(),
            sdkInt = Build.VERSION.SDK_INT,
            preferDeviceAudio = app.preferencesManager.isPreferDeviceAudio(),
            consentAvailable = consentBudget.mayAsk(),
        )
        android.util.Log.i("WE-DIAG", "startAudioInput: decision=$decision")
        return when (decision) {
            com.whispereverywhere.audio.SourceDecision.UseMic -> startMicSource()
            com.whispereverywhere.audio.SourceDecision.UsePlayback ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startPlaybackSource() else startMicSource()
            com.whispereverywhere.audio.SourceDecision.RequestConsent -> {
                // Ask once; capture begins when consent arrives (projectionListener). The mic
                // is NOT opened meanwhile — media capture must never mix room audio.
                // KNOWN GAP (accepted): until the user answers (typically 1-3s) this session has
                // no live source at all — and since 4.4.0 S2 this branch runs at the TAP, so the
                // sheet stands over a bubble that reads CONNECTING while the model loads, not
                // RECORDING. A grant starts capture there; deny/back falls back to the mic. Both
                // answers are honoured for CONNECTING as well as RECORDING, which is what makes
                // the previous sentence true at all — see sessionStillWantsASource() (round 2, B4).
                // NOTE: MediaProjectionGate.listener is a single process-global
                // slot — safe because only one FloatingBubbleService instance is ever live.
                com.whispereverywhere.audio.MediaProjectionGate.listener = projectionListener
                consentBudget.noteAsked()
                android.util.Log.i("WE-DIAG", "projection consent: asked=${consentBudget.asked}/${com.whispereverywhere.audio.ProjectionConsentBudget.MAX_ASKS_PER_SESSION}")
                com.whispereverywhere.audio.MediaProjectionGate.requestConsent(this)
                showToast("Allow screen capture to transcribe device audio")
                Result.success(Unit)
            }
        }
    }

    /**
     * The ONE place `activeSource` changes while a session is live, so the engine route can never
     * drift from the physical source. Routing is switched BEFORE the new capturer is started: a
     * capturer delivers its first chunk from its own thread the moment it starts, and a chunk that
     * arrives while the route still names the previous source is exactly the leak this guards.
     *
     * 4.4 THE FLATLINE CUT is armed HERE, and only here, because its rule is a rule about the
     * source: ARMED IFF THE ACTIVE SOURCE IS CAPTURED PLAYBACK. Device audio is other people's
     * edited audio — a video editor's gate leaves 100-300 ms of digital silence at every cut, far
     * too short for the 350 ms hangover, and the bubble visibly flatlines on the very `amp` the
     * endpointer used to ignore — while a microphone in a room never reaches digital zero, so on the
     * mic the trigger could only fire on a muted input and stays off. Routing this through the one
     * source-change site means it follows every path the source takes: the pick at session start
     * (after `endpointer.onSessionStart`, which opens the session disarmed, and before the capturer
     * delivers a frame), `switchSource` in both directions, the projection-consent grant, and the
     * DRM silent-stream handover back to the microphone. Main-only, like the source itself.
     */
    private fun setActiveSource(source: com.whispereverywhere.audio.ActiveSource) {
        activeSource = source
        val armed = source == com.whispereverywhere.audio.ActiveSource.PLAYBACK
        endpointer.armFlatline(armed)
        android.util.Log.i("WE-DIAG", "flatline: ${if (armed) "armed" else "disarmed"} source=$source")
    }

    /**
     * "IS THERE STILL A SESSION THAT WANTS A CAPTURE SOURCE?" — the question every asynchronous
     * source callback has to ask, and the question `currentState == BubbleState.RECORDING` stopped
     * answering at 4.4.0 S2 (round 2, B4).
     *
     * Until S2, `startAudioInput()` ran INSIDE `onOpen`, with `updateBubbleState(BubbleState.RECORDING)`
     * as the next-but-one SYNCHRONOUS statement: the consent sheet went up and the state became
     * RECORDING inside one Main block — microseconds — so no human answer could possibly arrive
     * before the gate passed. S2 hoisted the source decision to the TAP, so the sheet is now up for
     * the WHOLE CONNECTING window: 4,107 ms on a cold npu-turbo load, up to 11,672 ms for the first
     * ggml load in a process (investigation §3a/§3b), against the 1-3 s a person takes to answer.
     * The answer therefore lands mid-load, and `MediaProjectionGate.deliverResult` has no queue — it
     * calls the listener on the spot. Read as "is this session over?", a RECORDING test answers YES
     * for exactly the window that matters, and the answer was thrown away: a GRANT stopped the
     * projection it had just been handed, a DENY fell back to nothing, and either way the session ran
     * to the user's stop tap with NO recorder and NO capturer — the "listening" cue firing at a shut
     * microphone — with one of the two per-session asks already spent on it. That is the amendment's
     * own S2 acceptance row ("and on device audio: start a video and tap"), and it is the ONLY door
     * into a first device-audio session: `stopRecording` releases the projection, so `hasProjection()`
     * is false at every tap and `AudioSourcePolicy.decide` can never answer `UsePlayback` there.
     *
     * CONNECTING and RECORDING are exactly the two states a LIVE session occupies. The states left
     * out are the ones that really are over — IDLE, ERROR, FINALIZING, PROCESSING — and leaving them
     * out is what keeps the 2026-08-01 rule intact: a token stored for a finished session would light
     * the system's sharing indicator with nothing capturing, and the sharing indicator must not
     * outlive the transcript.
     *
     * THIS IS THE THIRD GATE S2's HOIST INVALIDATED (round 1's B1 and B3 were the first two). Each
     * was silent when it broke and each was found by a reviewer rather than by an instrument, which
     * is why the answer is a NAMED predicate rather than three widened comparisons: a fourth gate of
     * this family is now a call site, and `DeviceAudioLatchPinTest` counts them.
     */
    private fun sessionStillWantsASource(): Boolean =
        currentState == BubbleState.RECORDING || currentState == BubbleState.CONNECTING

    private fun startMicSource(): Result<Unit> {
        setActiveSource(com.whispereverywhere.audio.ActiveSource.MIC)
        return audioRecorder.start(::onAudioChunk)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun startPlaybackSource(): Result<Unit> {
        val projection = com.whispereverywhere.audio.MediaProjectionGate.projectionOrNull()
            ?: return startMicSource()
        val capturer = com.whispereverywhere.audio.PlaybackAudioCapturer(projection) {
            // DRM opt-out / silent stream: fall back to the microphone, on the main thread.
            serviceScope.launch(Dispatchers.Main) {
                if (activeSource == com.whispereverywhere.audio.ActiveSource.PLAYBACK &&
                    sessionStillWantsASource()
                ) {
                    // THE ONE MIC HANDOVER THE LATCH ALLOWS, and it is announced rather than
                    // silent. This app blocks capture (Netflix, Hulu, Sling — DRM licensing), or
                    // its audio is voice-communication, which playback capture never carries at
                    // all (Teams, Meet). Either way the stream is digital silence and the ONLY
                    // route to that audio is the speakers into the microphone — so refusing here
                    // would mean those apps simply cannot be transcribed. The toast names the
                    // consequence the owner's rule cares about: the user's own voice is now in
                    // the transcript. SilentStreamPolicy guarantees this fires only for a stream
                    // that NEVER carried audio, so a paused or quiet video can never reach it.
                    //
                    // 4.4.0 S2 round 2 (B4) — THE WATCHDOG IS ONE-SHOT, so this gate gets exactly
                    // one chance and may not be the RECORDING test it was. A capturer can now
                    // start during CONNECTING (a projection grant answered mid-load), and the
                    // watchdog fires SilentStreamPolicy.SILENT_TIMEOUT_MS = 3,000 ms after it
                    // starts — inside a 4,107 ms load, and far inside an 11,672 ms one. With a
                    // RECORDING test here, `silentFired` would latch on a refusal that did
                    // nothing and never fire again, and a Netflix or Teams session would capture
                    // digital silence for its whole life with no fallback and no toast — for
                    // exactly the apps this fallback exists for.
                    showToast("This app blocks audio capture — using the microphone, so your voice is included too")
                    fallBackFromSilentStreamToMic()
                }
            }
        }
        // Route FIRST, capture second. capturer.start() spawns its own thread and can deliver a
        // chunk before the next statement runs, so flipping the source afterwards would hand the
        // first frames of other people's audio to whatever engine the microphone was using.
        setActiveSource(com.whispereverywhere.audio.ActiveSource.PLAYBACK)
        val started = capturer.start(::onAudioChunk)
        return if (started.isSuccess) {
            playbackCapturer = capturer
            showToast("Capturing device audio")
            started
        } else {
            android.util.Log.w("WE-DIAG", "playback capture failed to start -> mic fallback")
            // Restores the microphone route as well: nothing was captured, so nothing is lost.
            startMicSource()
        }
    }

    /**
     * THE DRM/SILENT-STREAM HANDOVER BACK TO THE MICROPHONE, in both of the states a session can be
     * in when the one-shot watchdog fires (4.4.0 S2 round 2, B4). Main thread only.
     *
     * RECORDING is [switchSource]'s case and stays [switchSource]'s: the engine is open, whatever it
     * has accumulated is cut on the OLD source's side of the boundary, the ring is flushed there too,
     * and the D9/D10 endpointer reset follows the cut.
     *
     * CONNECTING is a DIFFERENT case and deliberately does NOT go through [switchSource], which
     * before readiness would do three wrong things: `sendAudio` into an engine whose context is still
     * loading (the exact thing the ring exists to avoid), `commit` a segment that has never been fed
     * a byte, and reset an endpointer no frame has reached — `onAudioChunk`'s BUFFER route does not
     * drive the probe at all, so there is no LSTM recurrence here to carry across the acoustic change
     * and no fourth `endpointer.reset()` site to justify.
     *
     * What the ring holds on this path is what the silent capturer put there, and
     * `SilentStreamPolicy`'s whole guarantee is that this stream NEVER carried audio: it is digital
     * silence, so it is DROPPED rather than replayed into the microphone's own segment — where it has
     * no boundary to sit behind (there is no engine to cut one) and would evict up to 3 s of the
     * user's real words from a 6 s ring on the slowest loads. The line reports the DURATION dropped,
     * like every other ring line.
     */
    private fun fallBackFromSilentStreamToMic() {
        if (currentState == BubbleState.RECORDING) {
            switchSource(to = com.whispereverywhere.audio.ActiveSource.MIC)
            return
        }
        stopPlaybackCapturer()
        val droppedMs = StartupRing.msOf(startupRing.byteSize())
        startupRing.clear()
        android.util.Log.i(
            "WE-DIAG",
            "silent stream before readiness: dropped ${droppedMs}ms of captured silence -> mic",
        )
        val started = startMicSource()
        if (started.isFailure) {
            android.util.Log.w(
                "WE-DIAG",
                "silent-stream mic fallback failed to start (${started.exceptionOrNull()?.message})",
            )
        }
    }

    /**
     * A SOURCE HANDOVER's flush of the startup ring (4.4.0 S2). Main thread only, and it has
     * exactly two call sites — the two places a live session takes the microphone away from
     * itself: [switchSource], and `onMediaPlaybackStarted`'s consent-ask branch, which is
     * [switchSource]'s sibling for the case where there is no projection token yet.
     *
     * THE RULE IT CARRIES: whatever is still in the ring is the OLD source's audio, so it is fed
     * and cut on the OLD source's side of the boundary. Leave a ring standing across a handover and
     * the NEXT source's capture thread finds it non-empty, takes the DRAIN route, and replays up to
     * 6 s of MICROPHONE audio into a device-audio segment — the owner's headline rule ("keep the
     * microphone out of it so someone could transcribe a YouTube video without their actual spoken
     * words being dictated") broken by a buffer. That is why this is a function and not two copies:
     * the rule belongs to the handover, not to one of its branches.
     *
     * CALL IT AFTER THE OLD SOURCE IS STOPPED AND JOINED AND BEFORE THE BOUNDARY COMMIT — the same
     * two-sided position `stopRecording`'s flush occupies, for the same two reasons. A capture
     * thread still delivering would land old-source PCM past the cut and out of capture order (the
     * ring's monitor keeps exactly-once, not order), and the commit is what puts this audio in the
     * segment it belongs to. `sendAudio` ONLY: the CUT is the caller's commit, and the endpointer's
     * native probe may not be driven from Main (VadProbeLifecycle's one shared direct buffer
     * belongs to the capture thread).
     */
    private fun flushStartupRingAtSourceHandover() {
        transcriptionEngine?.let { sessionEngine ->
            val pendingMs = StartupRing.msOf(startupRing.byteSize())
            val flushed = startupRing.drainAll { pcm, _, _ -> sessionEngine.sendAudio(pcm) }
            if (flushed > 0) {
                android.util.Log.i("WE-DIAG", StartupRing.switchFlushLine(flushed, pendingMs))
            }
        }
    }

    /** Commit the pending segment, stop the current source, start the other. Main thread only. */
    private fun switchSource(to: com.whispereverywhere.audio.ActiveSource) {
        if (activeSource == to || currentState != BubbleState.RECORDING) return
        android.util.Log.i("WE-DIAG", "switchSource: $activeSource -> $to")
        // One engine serves both sources, so this commit is what keeps mic and device audio in
        // SEPARATE segments at the boundary. In a live (server-driven) session it cuts a client
        // turn mid-stream — the same mechanism stopRecording's tail commit uses.
        //
        // 3.7 (Workstream D9/D10): the endpointer reset below is a CORRECTNESS requirement, not
        // hygiene. This function swaps mic <-> device audio, and the streaming VAD's LSTM
        // recurrence must never carry across an acoustic-source change: the state it accumulated
        // from one acoustic path is not evidence about the other, and carrying it makes the next
        // few verdicts arbitrary. This is the ONE reset site where a miss is a wrong answer rather
        // than a slack window.
        //
        // UNDISCHARGED, and deliberately not closed here: the native probe's EPOCH is not bumped
        // across this swap, so a stale capture thread from the OLD source can still reach the
        // probe. D10 does not close it alone because a bump without a matching `armed` re-publish
        // fails CLOSED — every post-switch frame is refused and the VAD goes silently off for the
        // rest of the session, which is worse than the residue. E2 additionally showed the join's
        // outcome is unobservable (Thread.join(ms) returns identically on termination and on
        // timeout), so the timing argument cannot be upgraded to a guarantee. Routed to final
        // review with the rest of the D-section residue.
        // 4.4.0 S2 — THE RING IS FLUSHED ON THE OLD SOURCE'S SIDE OF THE BOUNDARY, and this is the
        // one hazard the startup ring would otherwise hand the device-audio latch. This function
        // requires RECORDING, and the paced catch-up spends ~1.4-2.0 s inside RECORDING, so media
        // starting in that window swaps to playback capture WHILE THE RING STILL HOLDS MICROPHONE
        // AUDIO — and the new capturer's thread, taking the DRAIN route, would replay that
        // microphone pre-roll on the far side of the commit below, into the device-audio segment.
        // That is the owner's headline rule ("keep the microphone out of it so someone could
        // transcribe a YouTube video without their actual spoken words being dictated") broken by
        // a 6 s buffer.
        //
        // Flushing keeps the audio AND the rule: the pre-roll is the OLD source's audio, so it
        // belongs to the segment the old source was recording, and the commit below cuts it there.
        //
        // ROUND 1, B2 — THE OLD SOURCE IS STOPPED AND JOINED FIRST, and the order of these four
        // statements is the rest of that rule. The flush used to run AHEAD of the stop, with the
        // old source's capture thread still live: StartupRing.drainSlice releases its monitor
        // before calling the sink (it has to — the sink is sendAudio plus a Silero probe call per
        // chunk), so that thread routinely holds a 4-chunk slice in flight, ~10 ms of sink work per
        // 32 ms period. Interleaved with this drainAll that produced two windows the ring's monitor
        // cannot close, because the monitor gives exactly-once and says nothing about ORDER:
        // old-source PCM appended by that thread AFTER the boundary commit and after the
        // endpointer.reset() D9/D10 calls a correctness requirement (up to 128 ms of microphone
        // audio inside the device-audio segment), and out-of-capture-order PCM in one accumulating
        // engine buffer (chunks 5..187 preceding 2,3,4). Stop+join -> flush -> cut -> reset is the
        // same shape stopRecording has always had, and StreamingAudioRecorder.stop()'s own comment
        // names this hazard class as the reason it stops the record before joining the thread.
        //
        // The join is BEST-EFFORT (T2-SHARPENED: Thread.join(ms) returns identically on termination
        // and on timeout), so this is the safe half of a bound and not a claim about it — the same
        // standing this ordering has at stopRecording, and the residue named above.
        when (activeSource) {
            com.whispereverywhere.audio.ActiveSource.MIC -> audioRecorder.stop()
            com.whispereverywhere.audio.ActiveSource.PLAYBACK -> stopPlaybackCapturer()
        }
        flushStartupRingAtSourceHandover()
        transcriptionEngine?.let { commitSegment(it, EndpointDiag.SWITCH) }
        endpointer.reset()
        segmentCapPolicy.onCommit(System.currentTimeMillis())
        val started = when (to) {
            com.whispereverywhere.audio.ActiveSource.MIC -> startMicSource()
            com.whispereverywhere.audio.ActiveSource.PLAYBACK ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startPlaybackSource() else startMicSource()
        }
        if (started.isFailure) {
            android.util.Log.w("WE-DIAG",
                "switchSource: $to failed to start (${started.exceptionOrNull()?.message})")
        }
    }

    private val projectionListener = object : com.whispereverywhere.audio.MediaProjectionGate.Listener {
        override fun onConsentGranted(resultCode: Int, data: Intent) {
            serviceScope.launch(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@launch
                // ANDROID 14+ ORDERING: foreground with mediaProjection type BEFORE
                // getMediaProjection. Guarded like onCreate's startForeground — this call can
                // throw on 14+ (restricted start / revoked permission) and an uncaught throw
                // would crash-loop the START_STICKY service. On failure: mic fallback.
                try {
                    ServiceCompat.startForeground(
                        this@FloatingBubbleService,
                        WhisperEverywhereApp.NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                    )
                } catch (t: Throwable) {
                    android.util.Log.w("WE-DIAG",
                        "projection foreground upgrade rejected (${t.javaClass.simpleName}) -> mic")
                    if (sessionStillWantsASource()) startMicSource()
                    return@launch
                }
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                val projection = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
                if (projection == null) {
                    android.util.Log.w("WE-DIAG", "getMediaProjection returned null -> mic")
                    if (sessionStillWantsASource()) startMicSource()
                    return@launch
                }
                projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        com.whispereverywhere.audio.MediaProjectionGate.clear()
                    }
                }, null)
                // A grant that arrives AFTER the session ended must not be kept: the session-end
                // policy is "screen share ends with the session", and a stored token here would
                // light the system's sharing indicator with nothing capturing — exactly the lie
                // that policy exists to prevent. Stop it on the spot; the next media session asks
                // again.
                //
                // 4.4.0 S2 round 2 (B4) — "AFTER THE SESSION ENDED" IS NOT "NOT RECORDING" ANY
                // MORE. This branch used to fire for the entire CONNECTING window, where the
                // session has not ended but has not opened either: it threw away the token it had
                // just been handed, logged a session end that had not happened, and left the
                // media-first flow ("start a video, then tap") with no capture source for its
                // whole life. CONNECTING is a session that WANTS this grant, so it takes it; the
                // states that really are over keep the stop. See sessionStillWantsASource().
                if (!sessionStillWantsASource()) {
                    android.util.Log.i("WE-DIAG", "consent arrived after session end -> stopping projection")
                    runCatching { projection.stop() }
                    return@launch
                }
                com.whispereverywhere.audio.MediaProjectionGate.storeProjection(projection)
                if (activeSource != com.whispereverywhere.audio.ActiveSource.PLAYBACK) {
                    // Starting the capturer BEFORE readiness is exactly what the startup ring is
                    // for: its first chunks take the BUFFER route and the paced DRAIN releases
                    // them when the engine opens, so the seconds of the video that played while
                    // the model loaded are in the transcript instead of lost. Nothing to flush or
                    // cut first — this path never opened the microphone (pinned by
                    // DeviceAudioLatchPinTest.waiting_for_projection_consent_opens_no_source_at_all),
                    // so the ring is empty and no boundary is needed.
                    startPlaybackSource()
                }
            }
        }

        override fun onConsentDenied() {
            serviceScope.launch(Dispatchers.Main) {
                // 4.4.0 S2 round 2 (B4) — the same widening as the grant, for the same reason and
                // with more at stake: the ask was raised at the TAP, so a deny or a back press
                // almost always lands during CONNECTING, and a RECORDING test here meant the
                // promise the ask's own KDoc makes ("deny/back falls back to mic") was kept only
                // on paper. No toast, no recorder, and a session that produced nothing.
                if (sessionStillWantsASource()) {
                    showToast("Using microphone (capture permission declined)")
                    startMicSource()
                }
            }
        }
    }

    /**
     * The warm on-device engine. Built once and kept for the service's whole life, because loading
     * the native context costs seconds (model mmap + ~7 s Adreno OpenCL kernel compile). It
     * OUTLIVES every cloud wrapper built around it, and it is the only object here that owns a
     * native resource — so it is also the only one that may be `shutdown()`.
     *
     * ### Why the tier is decided HERE, and why "decided" means "rebuilt" (4.0, Q9)
     *
     * `LocalWhisperEngine.backend` is a **`val` with a constructor default**, and this is its only
     * construction site in the bubble path. There is no factory to route through and no field to
     * reassign: the tier a session runs on is fixed the moment the engine is built. So both of the
     * things that can change it — the user selecting a different tier, and the NPU declining
     * mid-session — are handled the same way, by dropping the cached engine and building another.
     *
     * **rebuild-on-fallback, chosen over making `backend` a `var`.** A `var` would let a fallback
     * swap the backend under an in-flight `transcribe` on the engine's native executor, i.e. hand a
     * running segment a half-torn-down native context. Rebuilding cannot: the swap happens between
     * sessions, at the next warm, and the segment that triggered the fallback was already rescued
     * inside `NpuWhisperBackend.fallBackAndRun`.
     *
     * **The ORDER below is an invariant, not a formatting choice (I11).** The stale engine is
     * `shutdown()` first and only then is the replacement constructed, so the two native operations
     * are *issued* in the order the ~570 MB transient requires.
     *
     * ### What that order does NOT give you, and the invariant that actually holds this together
     *
     * **SOURCE ORDER IS NOT HAPPENS-BEFORE, and the final review is right that the earlier wording
     * here elided it.** `LocalWhisperEngine.shutdown()` *queues* `backend.release` onto the **stale
     * engine's** single-thread executor and returns; the replacement's `WhisperNative.init` runs
     * later on the **new** engine's executor. Two executors, no happens-before between them —
     * `NativeComputeGate` bounds concurrency, not order. So this order makes the teardown the
     * earlier *issue*, not the earlier *execution*, and on a tier change away from an armed npu
     * session the ~570 MB co-residency is reachable if the load wins the gate. Bounded and
     * survivable on 8 Gen 3-class RAM; stated because it was previously claimed to be excluded.
     *
     * **The consequence that matters is worse, and 4.1 L1 closed it structurally.** The QNN session
     * is a process-global and `nativeInit` releases any existing one, so an `npu -> npu-class`
     * rebuild has a losing interleaving: the new `load` tears down the old session and builds a new
     * one, and *then* the stale engine's queued `release` reaches native and destroys **the session
     * that just came up**, leaving a backend `armed` with nothing behind it. Ordering cannot fix
     * that — the two effects are not ordered by the two statements that cause them. **Identity
     * can, and now does:** every successful `nativeInit` issues a monotonic *arming epoch*, the
     * release names the epoch its own instance was armed with, and native ignores any release that
     * is not the live session. A stale teardown is a `WE-DIAG` line rather than a destroyed
     * session, and a stale `transcribe` refuses rather than encoding into another model's session.
     *
     * **The invariant that replaced it (4.1 L8): the rebuild guard compares routed tier IDS, not
     * a Boolean.** Two npu-class tiers route now, so "is the cached engine still right" is no
     * longer a yes/no about the NPU — it is *which* npu tier, and `routedNpuTierId ==
     * localEngineNpuTierId` is the comparison that makes an `npu -> npu-turbo` switch a rebuild
     * instead of a "no change" that leaves the user dictating on the tier they just left. The
     * `npu -> npu-class` interleaving that made a same-backend rebuild dangerous is closed by the
     * arming epoch above, which is exactly what it was built for; `NpuBackendWiringTest` asserts
     * the routing census directly (the set of tier ids that route to the NPU is exactly the two
     * spec rows), so a third npu-class tier trips a deliberate red there rather than a heisenbug
     * on a device. The null-vs-null case is deliberately unchanged: every CPU tier shares
     * `WhisperNativeBackend`, so a CPU -> CPU switch keeps the cached engine and re-prewarms.
     *
     * ### [allowRebuild] — the permission, and why it is a parameter rather than a state check
     *
     * This function used to be `localEngine ?: LocalWhisperEngine(…)`: pure, idempotent, safe from
     * anywhere. It is now **destructive**, and `LocalWhisperEngine.shutdown()` does not merely free
     * the context — it calls `executor.shutdown()`, after which the next `commit()` throws an
     * uncaught `RejectedExecutionException` from the **capture thread**, which has no handler.
     * Rebuilding under a live session is therefore not a degraded experience; it is a crash with
     * the user's audio still accumulating in a buffer nobody will ever cut.
     *
     * So the destructive branch requires explicit permission, and **exactly two callers have it,
     * each with its own proof that no session can be in flight**:
     *
     *  1. [resolveTranscriptionEngine] — session start, before any audio exists and before any
     *     `commit()` can be queued.
     *  2. the model-switch collector — after an `IDLE`/`ERROR` read taken **below** its last
     *     suspension point, in one uninterrupted run on Main (fix round 2). It needs the permission
     *     because the alternative is worse than a stale engine: handed the cached engine unchanged
     *     after a tier switch, it calls `prewarmModelSwitch()`, which loads the NEW tier's file
     *     through the OLD tier's backend — and an npu-backed engine given a ggml path publishes a
     *     FALSE `unavailable stage=companion`.
     *
     * The **boot prewarm** deliberately does not have it: it only ever fills an empty slot, so it
     * has nothing to tear down, and a rebuild there would be the un-gated one C1 was about.
     *
     * Nothing is lost by a caller *not* having the permission: a deferred rebuild always resolves
     * before the next segment can exist, because the thing that creates segments is the thing that
     * carries permission 1. That is the *"mid-session triggers are skipped, never deferred"*
     * doctrine the collector already states, applied inside the function that needed it.
     *
     * **NOT a `currentState` check, and that is the trap.** `startRecording` sets `CONNECTING`
     * *before* calling [resolveTranscriptionEngine], so a state-keyed guard would block the one
     * rebuild that must happen and permit none. The permission has to travel with the caller.
     *
     * **The guard sits ABOVE the teardown**, which is an ORDER invariant rather than a presence
     * one: a permission check that runs after `shutdown()` has already run is not a check.
     */
    private fun warmLocalEngine(allowRebuild: Boolean = false): LocalWhisperEngine {
        val tierId = app.preferencesManager.selectedModelId
        // Process state, read from the mirror NpuWhisperBackend publishes from its own setter —
        // PER TIER since 4.1 L8: membership means that tier has already declined and is not
        // re-armed, and one tier's decline says nothing about the other's routing.
        val declined = NpuTierStatus.declinedTiers
        // The selector's decision, recorded as WHICH tier routed (null = the shared CPU backend).
        // The predicate stays the selector's; this line only names the id the yes was about.
        val routedNpuTierId = if (NpuBackendSelector.routesToNpu(tierId, npuTierIds, declined)) tierId else null
        val cached = localEngine
        if (cached != null && (routedNpuTierId == localEngineNpuTierId || !allowRebuild)) return cached
        if (cached != null) {
            // ONCE PER SESSION, never once per segment: these fire only on the transition. Two
            // narrations for two different events (Q9 M3, folded at 4.1 L8; partition re-specced
            // by the L8 review's I2): the stage-carrying fallback line fires only when the cached
            // engine's OWN npu tier declined AND that decline is what the replacement routes on —
            // i.e. the new engine really is the CPU tier. Every other rebuild — a tier switch,
            // which the A/B makes the ordinary path, INCLUDING a switch away from a tier that
            // had declined — names the from-tier and the ACTUAL target tier, the selector's own
            // answer. The old partition keyed on the decline alone, so decline-then-switch
            // printed "rebuilt on the CPU tier" while the replacement routed to the OTHER npu
            // tier: false text on the one log the A/B sheet reads as its instrument.
            val reason = NpuTierStatus.reasonFor(localEngineNpuTierId)
            if (localEngineNpuTierId != null && reason != null && routedNpuTierId == null) {
                android.util.Log.w(NpuDiag.TAG, NpuDiag.fallbackRebuild(NpuTierStatus.stageOf(reason)))
            } else {
                android.util.Log.i(NpuDiag.TAG, NpuDiag.tierRebuild(localEngineNpuTierId, routedNpuTierId))
            }
            cached.shutdown()
            localEngine = null
        }
        val built = LocalWhisperEngine(
            app.whisperModelManager,
            // The selector resolves the DEVICE FAMILY through this context's applicationContext
            // (4.2 F2) — so this site hands it the app object itself, and the resolution is
            // structural rather than an accident of which Context a Service happens to wrap. A
            // Service's applicationContext IS the app today; "safe by a property of a different
            // object" is not a lean this call site gets to take.
            backend = NpuBackendSelector.backendFor(
                tierId = tierId,
                offeredNpuTierIds = npuTierIds,
                declinedTiers = declined,
                paths = app.whisperModelManager,
                appContext = app,
            ),
        )
        localEngine = built
        localEngineNpuTierId = routedNpuTierId
        return built
    }

    /**
     * 4.4.0: build (once) and warm the resident previewer — load + canary on its own executor —
     * for [pack]; null when that pack is not installed after all (the gate then says pack=0).
     * Idempotent ON THE PACK (4.5.0 T2, defect 1): warming the pack the engine already holds is
     * a no-op, warming a DIFFERENT one releases the old recognizer inside the engine and loads
     * the new one, a RELEASED engine (onTrimMemory) reloads on the next call — spec §4.1 step 10
     * — and a pack whose verdict went against it never reloads in this process, while every
     * OTHER language still can (§7.2; the verdict survives release() and is now per-language).
     * Called from the prewarm coroutine at service start and again at the wrap site; the latter
     * arms NEXT session, not this one, because warm() is asynchronous and the gate reads
     * isWarmFor() now — a session started under a second after the service came up is exactly
     * today's session, by design.
     *
     * **(4.4.1, CHANGE 5) WHICH pack is the caller's decision, and [previewPackToWarm] is the one
     * that makes it** — the same lookup the gate takes, so this never loads a model the gate will
     * refuse.
     *
     * **(4.5.0 T2, defect 1) The one-pack-per-process invariant is the ENGINE's now**, not this
     * function's: `warm` is idempotent on the PACK, and a different pack releases the resident
     * recognizer inside the engine's own task before it loads. The release below is therefore a
     * skip, not a mechanism — it spares Main a posted task when the pack has not moved — and it is
     * what keeps `streamingPreviewPack` honest for the release collector. If the two ever
     * disagree, the engine wins, by construction: it is the only one holding the recognizer.
     */
    private fun warmStreamingPreview(
        pack: com.whispereverywhere.transcription.stream.StreamingPack,
    ): com.whispereverywhere.transcription.stream.StreamingPreviewEngine? {
        val dir = app.streamingPackManager.installedDir(pack) ?: return null
        val resident = streamingPreview
        // A DIFFERENT pack than the one Main last asked for: post the release. The engine would
        // do this itself — its warm() releases a resident recognizer whose pack is not the one
        // being warmed — so this is an early, cheap agreement with it and never a second opinion:
        // release() is not a verdict (`disabled` is untouched) and both it and the reload land on
        // the engine's own single-thread executor in this order. Unreachable with one catalogue
        // row.
        if (resident != null && streamingPreviewPack != pack) resident.release()
        val engine = resident ?: com.whispereverywhere.transcription.stream.StreamingPreviewEngine(
            factory = com.whispereverywhere.transcription.stream.SherpaPreviewRecognizerFactory(),
            // The clip is the PACK's (4.5.0 T2, defect 4): the English digits clip cannot pass for
            // a non-English model, and a non-pass reads exactly like the corruption signature the
            // canary exists to catch — so a shared clip would refuse every non-English pack and
            // report it as a failure. One WAV per language in main assets, 81,998 B each.
            // A row whose clip has not been sourced yet carries `canary = null`, and that is NO
            // VERDICT (PackCanary's docblock) — never a Fail, which would switch live words off
            // for that language on a clip nobody has made.
            canaryClip = { p -> p.canary?.let { com.whispereverywhere.transcription.CanaryAudio.samples(it.asset) } },
            // The pack whose load actually failed, HANDED OVER by the engine. Neither a closure
            // over `pack` (the engine is built once and outlives any one language, so that would
            // mark ENGLISH corrupt for a Spanish failure) nor a read of `streamingPreviewPack`
            // (Main writes that field when the SELECTION moves, so a switch to B while A is still
            // loading marked B corrupt for A's failure — deleting a healthy marker and leaving the
            // broken pack installed). The engine's own load task is the only place that knows.
            onLoadFailure = { failed -> app.streamingPackManager.markCorrupt(failed) },
            // (4.5.0 Task 3 fix round 2, review r2's N2) ...and the language the engine has taken
            // OFF for the rest of this process, which is NOT the same event as a load that threw:
            // a failed canary and a three-strike session leave 73 MB of valid bytes installed, so
            // `markCorrupt` would be wrong and `state()` goes on answering `Installed`. Published
            // because the verdict lived on this private field and no surface could read it, while
            // the strip above the language selector promises *"words appear on the bubble
            // whenever you pick it"*. Same hand-over discipline as the hook above: the pack the
            // ENGINE named, never a field this class may have moved since.
            onDisabled = { wentOff ->
                com.whispereverywhere.transcription.stream.PreviewDisabled.note(wentOff.language)
            },
            // (4.5.1 Task 1) THE THIRD HAND-OVER, and the same discipline: what is RESIDENT AND
            // USABLE now, named by the engine rather than by this class. `isWarmFor(pack)` is the
            // answer the session gate itself reads and it had no reader outside this service
            // either, so the strip above the language selector could only promise words off *the
            // files landed* — the owner's *"having to transcribe a second time to get the live to
            // work"*. Published here, read there, and the READY receipt is a term of it.
            onWarm = { resident ->
                com.whispereverywhere.transcription.stream.PreviewWarm.note(resident?.language)
            },
        ).also { streamingPreview = it }
        streamingPreviewPack = pack
        engine.warm(dir, pack)
        return engine
    }

    /**
     * Re-reads [WhisperEverywhereApp.offeredNpuTierIds] into [npuTierIds], off Main.
     *
     * **The dispatcher hop is the point.** That gate forces the memoised capability probe, which
     * `dlopen`s `libQnnSystem.so` and `libQnnHtp.so` on its first call, and `QnnAsrNative`'s
     * threading contract forbids Main for every entry point — while [warmLocalEngine], which needs
     * the answer, runs on Main from `startRecording`. Hence a memo written here and only read
     * there, exactly as the two chooser screens do it with `produceState { withContext(IO) { … } }`.
     *
     * **The hop moves the GATE off Main, not the assignment.** `withContext` returns to the
     * caller's context, so the write to [npuTierIds] lands back on Main — which is where every
     * read of it happens too. Stated because the first version of this KDoc claimed a cross-thread
     * publication that does not exist (Q9 review, M1).
     *
     * Re-read rather than cached forever because the *installed* half can change under a live
     * service: Q8's importer writes a gated pair into files/models while the app is running.
     */
    private suspend fun refreshNpuTierOffer() {
        npuTierIds = withContext(Dispatchers.IO) { app.offeredNpuTierIds() }
    }

    /** One shared client for the service's life — see the [httpTransport] field comment. */
    private fun sharedTransport(): com.whispereverywhere.net.OkHttpTransport =
        httpTransport ?: com.whispereverywhere.net.OkHttpTransport().also { httpTransport = it }

    /**
     * One WebSocket factory for the service's life — see the [liveWsFactory] field comment. Reuses
     * the standard client CONFIG via [com.whispereverywhere.net.OkHttpTransport.defaultClient]
     * (OkHttpTransport keeps its own client private, and HttpTransport.kt is outside this task's
     * files); [com.whispereverywhere.transcription.live.OkHttpWebSocketFactory] then derives the
     * no-timeout, ping-kept streaming variant the long-lived socket needs.
     */
    private fun sharedLiveWsFactory(): com.whispereverywhere.transcription.live.WebSocketFactory =
        liveWsFactory ?: com.whispereverywhere.transcription.live.OkHttpWebSocketFactory(
            com.whispereverywhere.net.OkHttpTransport.defaultClient(),
        ).also { liveWsFactory = it }

    /** One reconnect scheduler (a single daemon thread) for the service's life — see the field comment. */
    private fun sharedLiveReconnectScheduler(): com.whispereverywhere.transcription.live.ReconnectScheduler =
        liveReconnectScheduler
            ?: com.whispereverywhere.transcription.live.ExecutorReconnectScheduler().also { liveReconnectScheduler = it }

    /**
     * The LOCAL leg of the 3.8 cloud-"en" leak fix ([sessionLanguageFor]): the language the
     * English-only local MIRROR of a cloud session runs under. The cloud half received the user's
     * selection at connect(); the mirror re-pins "en" on an ENGLISH-scope tier because that is the
     * one thing its weights can transcribe. Wired as FallbackTranscriptionEngine's `mirrorLanguage`.
     */
    private fun localMirrorLanguage(sessionLanguage: String?): String? =
        sessionLanguageFor(
            installedScope = app.whisperModelManager.installedModel()?.scope,
            selection = sessionLanguage,
            engine = TranscribingEngine.LOCAL,
        )

    /**
     * User-facing copy for a live-session [com.whispereverywhere.transcription.cloud.FatalKind],
     * kept word-for-word identical to the batch providers' `SttError.Fatal` messages so the
     * latch-toast reads the same whichever transport failed (batch carries its own message string;
     * the live WS carries only a kind — the handshake body is off-limits for credential safety).
     */
    private fun liveFatalMessage(kind: com.whispereverywhere.transcription.cloud.FatalKind): String =
        when (kind) {
            com.whispereverywhere.transcription.cloud.FatalKind.INVALID_KEY -> "Key rejected"
            com.whispereverywhere.transcription.cloud.FatalKind.FORBIDDEN -> "Access denied for this key"
            com.whispereverywhere.transcription.cloud.FatalKind.OUT_OF_CREDIT -> "Account has no remaining credit"
            com.whispereverywhere.transcription.cloud.FatalKind.MODEL_UNAVAILABLE -> "Transcription model unavailable"
        }

    /**
     * Resolves this session's transcription engine, RE-DECIDING the cloud/local question at every
     * session start.
     *
     * It used to decide once and cache for the service's lifetime, which made three user actions
     * silently ineffective on a bubble that stays up for days: selecting "On-device" did not stop
     * uploads, deleting the API key did not stop uploads (OpenAiStt captures the key by value at
     * construction), and selecting cloud did nothing until the service was restarted. The privacy
     * policy ships the sentence "switching back to on-device or removing the key stops all
     * transmission to that provider immediately" — this function is what makes that true. It also
     * re-samples connectivity, so a bubble started in a tunnel is no longer pinned to on-device
     * for the rest of the day.
     *
     * Rebuilding is cheap BECAUSE the expensive part is deliberately not rebuilt: [warmLocalEngine]
     * and [sharedTransport] persist, and only the thin wrappers that carry the user's choice (and
     * the key) are recreated.
     *
     * Cloud is opt-in per provider and ALWAYS wrapped in the local fallback: [decideEngineChoice]
     * has exactly one path to [EngineChoice.CLOUD_WITH_FALLBACK] and it requires a provider to
     * have been selected first (`prefs.sttProviderId != null`) — on-device stays reachable with
     * zero configuration and cloud can never be chosen implicitly. No key or no validated network
     * is never presented as a failure: the on-device model still answers.
     */
    private fun resolveTranscriptionEngine(): TranscriptionEngine {
        val providerId = app.preferencesManager.sttProviderId
        val provider = resolveSttProvider(providerId)
        val key = provider?.let { app.preferencesManager.providerAccounts.key(it) }

        val choice = decideEngineChoice(
            sttProviderId = providerId,
            hasKey = !key.isNullOrBlank(),
            hasValidatedNetwork = connectivityMonitor.hasValidatedNetwork(),
            // The shared flag for OpenAI/ElevenLabs/Soniox; Gemini's own flag (see liveModeFor).
            // Both default true, so a stored key streams whichever of the four is selected.
            liveMode = liveModeFor(
                providerId,
                sharedLive = app.preferencesManager.sttLiveMode,
                geminiLive = app.preferencesManager.sttLiveModeGemini,
            ),
        )
        android.util.Log.i("WE-DIAG", "resolveTranscriptionEngine: providerId=$providerId choice=$choice")

        // Retire the previous session's wrapper. close(), NEVER shutdown(): close() resolves
        // everything the wrapper still owes and detaches it, whereas shutdown() cascades to
        // LocalWhisperEngine.shutdown() and releases the native context — the one thing that must
        // survive between sessions.
        cloudWrapper?.close()
        cloudWrapper = null
        // A stale latch must not outlive its engine: after switching to on-device, finalize must
        // not toast about a fatal from a previous session's cloud engine. Both cloud references are
        // cleared here; the chosen branch below re-sets exactly one (or neither, for local).
        lastCloudEngine = null
        lastLiveEngine = null
        sessionCloudProviderId = null
        // Frozen fresh each session and read by the delta surface; default off so batch/on-device
        // sessions keep their exact behavior. Only the CLOUD_LIVE branch flips it on.
        sessionIsLive = false
        sessionHasLocalPreview = false

        // THE ONLY CALLER PERMITTED TO REBUILD (4.0, Q9 fix round, C1). This runs at session start,
        // from startRecording, before a single byte of audio exists and before any commit() can be
        // queued — so tearing the previous engine down here cannot reject a segment. Every other
        // caller passes the default `false` and takes the cached engine as it is; a tier change or
        // a fallback they observed is applied HERE, at the next session, which always precedes the
        // next segment.
        val local = warmLocalEngine(allowRebuild = true)
        // Announced when the SHAPE CHANGES, not once per service and not once per session: a user
        // who loses signal at noon should be told, and a user who dictates forty times should not
        // be told forty times.
        fun announceIfNew(message: String) {
            if (notifiedChoice != choice) showToast(message)
        }
        val engine: TranscriptionEngine = when (choice) {
            EngineChoice.LOCAL_ONLY -> local
            EngineChoice.LOCAL_NO_KEY -> {
                announceIfNew("No key saved — using the on-device model")
                local
            }
            EngineChoice.LOCAL_OFFLINE -> {
                announceIfNew("Offline — using the on-device model")
                local
            }
            EngineChoice.CLOUD_WITH_FALLBACK -> {
                // Non-null/non-blank is guaranteed here: CLOUD_WITH_FALLBACK is reachable only
                // when hasKey was true above, which in turn required `provider` to be non-null.
                // Construction goes through the ONE factory both services share — the only line
                // that widened for C2b; the wrap below is provider-agnostic and unchanged.
                // requireNotNull, not `?: OPENAI`: decideEngineChoice only returns
                // CLOUD_WITH_FALLBACK when hasKey was true, and hasKey is false whenever `provider`
                // failed to resolve (an unresolvable id yields a null key). The Elvis default hid a
                // future gate-loosening bug — it would silently hand OPENAI another provider's key.
                val stt = SttProviderFactory.create(
                    requireNotNull(provider) { "CLOUD_WITH_FALLBACK reached with a null provider" },
                    sharedTransport(), requireNotNull(key),
                )
                val cloud = CloudTranscriptionEngine(stt, serviceScope)
                lastCloudEngine = cloud
                sessionCloudProviderId = provider
                // ONE engine for the whole session, both capture sources. Device (playback) audio
                // follows the user's provider selection exactly as mic audio does — owner decision
                // 2026-08-01, and the four statements that used to say device audio never leaves
                // the phone (assets/privacy_policy.html, docs/privacy.html §6,
                // docs/PLAY-DECLARATIONS.md §3/§5) were rewritten IN THE SAME COMMIT to disclose
                // it. Consent is the same triad as mic audio (key + selection + disclosure) plus
                // the per-session MediaProjection sheet, and the local fallback rescues failures
                // for both sources alike.
                FallbackTranscriptionEngine(cloud, local, serviceScope, mirrorLanguage = ::localMirrorLanguage)
                    .also { cloudWrapper = it }
            }
            EngineChoice.CLOUD_LIVE -> {
                // Realtime-capable-and-key-present, both guaranteed by decideEngineChoice: the live
                // leaf is reachable ONLY when the selected provider is in REALTIME_STT_PROVIDERS and
                // hasKey was true. Same mic audio, same provider, same v3 disclosure as batch — this
                // swaps the transport (a per-provider Realtime WebSocket) and the cost tier, nothing
                // about what data leaves. `protocol` is the ONE place a provider selects its
                // RealtimeProtocol; OpenAI/ElevenLabs/Gemini authenticate via the upgrade header
                // (the key passed below), Soniox rides its key in the first config message instead
                // — see SonioxRealtimeProtocol's no-log discipline.
                val liveProviderId = requireNotNull(provider) { "CLOUD_LIVE reached with a null provider" }
                val protocol: com.whispereverywhere.transcription.live.RealtimeProtocol = when (liveProviderId) {
                    ProviderId.OPENAI -> com.whispereverywhere.transcription.live.OpenAiRealtimeProtocol()
                    ProviderId.ELEVENLABS ->
                        com.whispereverywhere.transcription.live.ElevenLabsRealtimeProtocol()
                    ProviderId.SONIOX ->
                        com.whispereverywhere.transcription.live.SonioxRealtimeProtocol()
                    ProviderId.GEMINI ->
                        com.whispereverywhere.transcription.live.GeminiRealtimeProtocol()
                }
                // WHO CUTS THE TURNS. OpenAI / ElevenLabs / Soniox: the SERVER (server VAD /
                // endpoint detection) — the engine allocates seqs from server turn events via the
                // rotation callback wired below, and the client VAD/commit + wall-cap are disabled
                // for the session (onAudioChunk's LiveTurnPolicy gate). Gemini (4.3.4): the APP —
                // its server VAD drops most speech after the first sentence (T0 2026-09-10), so the
                // endpointer keeps running and each of its commits becomes a manual-VAD
                // activityEnd; the engine keeps its client-VAD ledger (serverDriven = false).
                val serverCutsTurns =
                    !com.whispereverywhere.transcription.live.LiveTurnPolicy.clientCutsLiveTurns(liveProviderId)
                val cloud = com.whispereverywhere.transcription.live.LiveTranscriptionEngine(
                    apiKey = requireNotNull(key),
                    scope = serviceScope,
                    serverDriven = serverCutsTurns,
                    // Client-VAD live only: the provider's final for the stop-cut tail is due within
                    // ~0.5 s (Gemini: 0.44 s max, T0 P9); past this the tail goes to the local
                    // rescue instead of holding the finalize for its whole 300 s budget.
                    tailGraceMs = if (serverCutsTurns) Long.MAX_VALUE else LIVE_CLIENT_TAIL_GRACE_MS,
                    makeTransport = { transportListener ->
                        com.whispereverywhere.transcription.live.LiveTranscriptionEngine.realTransport(
                            com.whispereverywhere.transcription.live.RealtimeTransport(
                                factory = sharedLiveWsFactory(),
                                scheduler = sharedLiveReconnectScheduler(),
                                listener = transportListener,
                                protocol = protocol,
                            ),
                        )
                    },
                )
                lastLiveEngine = cloud
                sessionIsLive = true
                sessionCloudProviderId = liveProviderId
                // Wired IDENTICALLY to batch: one FallbackTranscriptionEngine serves the whole
                // session, both capture sources (owner decision 2026-08-01 — device audio follows
                // the provider selection; see the batch branch above for the consent/docs story).
                // A dropped socket resolves its turn Lost and the fallback rescues it locally from
                // the mirrored PCM, mic and device audio alike. In live mode the turn cutter —
                // the server, or for Gemini this app's endpointer — cuts device-audio turns exactly
                // as it cuts mic turns: same socket, same VAD.
                val fallback = FallbackTranscriptionEngine(cloud, local, serviceScope, mirrorLanguage = ::localMirrorLanguage)
                // Server turns rotate the SAME fallback that mirrors this engine's PCM — the seq the
                // callback returns is the paired seq the mirror just retained. Wired here, where both
                // objects exist, so FallbackTranscriptionEngine stays provider-agnostic and
                // byte-identical (it never knows about server-driven turns). A client-VAD live
                // session (Gemini) has no server turn events: its commits come through
                // fallback.commit() from the endpointer, exactly like batch, so nothing is wired.
                if (serverCutsTurns) cloud.attachServerTurnRotation { fallback.commit() }
                fallback.also { cloudWrapper = it }
            }
        }
        notifiedChoice = choice
        transcriptionEngine = engine
        return engine
    }

    /**
     * The ONE preview pipeline (W2 unified preview): EVERY session — TEXT_FIELD, MEDIA_PLAYBACK,
     * NONE, live or batch — shows the accumulating transcript window and gets a bounded-memory
     * TranscriptSink. Mid-session text goes here and NOWHERE else; the single external write
     * happens at stop (deliverFinalTranscript). For a TEXT_FIELD session this window is the
     * user's only live feedback now, since segments no longer inject as they resolve.
     * [live] sessions additionally stream the in-flight utterance onto the delta strip via
     * onDelta; here the strip is only reset so the last session's text never flashes back.
     */
    private fun showSessionPreview(live: Boolean) {
        applyPreviewSize()
        // The preview appearing is a geometry change: the window just grew upward/rightward.
        // Posted so the measure pass has run and currentWindowSize() sees the REAL new dims.
        bubbleView.post { reclampNow() }
        android.util.Log.i("WE-DIAG", "showSessionPreview: live=$live context=$sessionContext")
        transcriptionEditText.visibility = View.VISIBLE
        transcriptionEditText.text = ""
        transcriptionDeltaText.text = ""
        transcriptionDeltaText.visibility = View.GONE
        transcriptionPreviewContainer.visibility = View.VISIBLE

        // Bounded-memory sink for the session; the file on disk is the full transcript.
        val sessionFile = java.io.File(filesDir, "transcript_session.txt").apply { if (exists()) delete() }
        val sink = com.whispereverywhere.transcription.TranscriptSink(sessionFile)
        transcriptSink = sink
        previewJob?.cancel()
        previewJob = serviceScope.launch(Dispatchers.Main) {
            sink.preview.collectLatest { text ->
                transcriptionEditText.text = text
                // TextView has no setSelection; scroll to reveal the newest text.
                transcriptionEditText.post {
                    val lc = transcriptionEditText.lineCount
                    val layout = transcriptionEditText.layout
                    if (lc > 0 && layout != null) {
                        val dy = layout.getLineBottom(lc - 1) - transcriptionEditText.height
                        transcriptionEditText.scrollTo(0, dy.coerceAtLeast(0))
                    }
                }
            }
        }
    }

    private fun startRecording() {
        if (!audioRecorder.hasPermission()) {
            vibrateError(); showToast("Microphone permission required"); return
        }

        updateBubbleState(BubbleState.CONNECTING)
        // 4.4.0 S2 (the amendment's haptic ruling): the TAP gets an acknowledgement, and the
        // "listening" cue moves onto TRUE readiness — it fires from onOpen now. See vibrateTap().
        vibrateTap()
        // 4.4.0 S2: a new session opens with an empty ring and an unready engine. Both are read by
        // the capture thread, which from this task starts BEFORE connect() returns, so they are
        // written here — above everything that could reach startAudioInput() — and nowhere later.
        engineReady = false
        startupOverflowLogged = false
        startupRing.clear()
        // Round 1 (B3): and with no handover owed from a previous session's connect window.
        pendingDeviceAudioHandover = false
        // Capture wins instantly over read-aloud (Track F exclusivity rule).
        com.whispereverywhere.audio.AudioArbiter.requestCapture()
        isSpeakingNow = false
        sessionProducedText = false
        // 4.3.1 D: a new session starts with a fresh screen-capture consent budget, and may say
        // "microphone for this session" once more.
        consentBudget.reset()
        consentExhaustedToastShown = false
        sessionTranscript.setLength(0)
        // Per-session ordering state. MUST be recreated: the orderer drops any seq below its head,
        // and the engine restarts seq numbering at 0 in connect() — a reused orderer sitting at
        // head N would silently discard the whole next session.
        segmentOrderer = com.whispereverywhere.transcription.SegmentOrderer()
        // 3.7 Workstream F: same reason the orderer is recreated. A depth carried over from a
        // torn-down session would render a phantom backlog on the next one's first commit, and a
        // reset at stop would blank the diagnostic for the whole drain.
        segmentQueueDepth.reset()
        // Session START, not stop, and for a sharper reason than the two above: resetting at stop
        // would drop every stamp for the segments still in flight when the user taps it, and those
        // are systematically the SLOWEST samples (at pro's utterance cadence the last utterance is
        // always in flight; on multi several are). onVisible would answer null for all of them, no
        // perceived: line would be emitted, and S3 Check 2's p50/p95 grid would be biased low by
        // exactly the tail it exists to measure.
        perceivedLatency.reset()
        sessionStartMs = System.currentTimeMillis()
        // Freeze this session's routing mode and injection target at the tap. Segments finish
        // seconds later and the user may click anywhere in between; the session must not follow.
        // Both released/re-captured per session (injection session in teardownRealtime).
        sessionContext = currentContext
        // A typing session needs a REAL input target at the tap: keyboard up, or a focused
        // editable in ANY window (the cached-node-only check demoted working fields — user
        // regression report 2026-07-18). No target -> preview session: window + ONE clipboard
        // save at the end.
        if (sessionContext == BubbleContext.TEXT_FIELD &&
            !WhisperAccessibilityService.hasLiveInputTarget()
        ) {
            android.util.Log.i("WE-DIAG", "no live input target at tap -> preview session")
            sessionContext = BubbleContext.NONE
        }
        sessionClipboardFallback = false
        finalDelivered = false
        WhisperAccessibilityService.beginInjectionSession()
        android.util.Log.i("WE-DIAG", "startRecording: sessionContext=$sessionContext")

        // On-device engine. connect() resolves the installed model and loads the
        // native context off-thread; CONNECTING covers that model-load wait and
        // onOpen() fires only once the context is ready.
        // Reuse a single engine across sessions so the native model context is loaded once and
        // reused (spec: "loaded once and reused"); it is released only on memory pressure
        // (onTrimMemory) or on service destroy (onDestroy), not at the end of each recording.
        val baseEngine: TranscriptionEngine = resolveTranscriptionEngine()

        // Honest CONNECTING (3.6.0, Workstream E3): a cold local engine is about to pay the ~7 s
        // model load inside CONNECTING — name the wait. The engine itself reports which branch
        // its connect() will take (isWarm(), the same check connect() runs — a surfaced flag,
        // never log parsing); cloud sessions (cloudWrapper != null) are excluded because their
        // CONNECTING wait is the socket/handshake. The strip is the label surface, exactly like
        // the FINALIZING status line: onOpen's showSessionPreview() resets it, and every failure
        // exit (recorder start failure, connect-time fatal) runs teardownRealtime(), which
        // brings the container down.
        connectingStatusLabel(
            isCloudSession = cloudWrapper != null,
            localEngineWarm = localEngine?.isWarm() == true,
        )?.let { label ->
            // Size BEFORE showing, exactly like showSessionPreview() does (applyPreviewSize is
            // its first call): the container's width/height come from bubbleTextWidthDp/HeightDp
            // clamped against the live screen, and without this the first session after a service
            // start renders the panel at whatever geometry was left over, then jumps when
            // showSessionPreview runs at onOpen.
            applyPreviewSize()
            transcriptionEditText.visibility = View.GONE
            transcriptionDeltaText.text = label
            transcriptionDeltaText.scrollTo(0, 0)
            transcriptionDeltaText.visibility = View.VISIBLE
            transcriptionPreviewContainer.visibility = View.VISIBLE
            // The strip appearing is a geometry change — posted so the measure pass ran first.
            bubbleView.post { reclampNow() }
        }

        // Resolve the transcription language for the engine that will TRANSCRIBE this session
        // (sessionLanguageFor — the 3.8 cloud-"en" leak fix). English-only (.en) models must NOT
        // use auto-detect and cannot transcribe another language, so "en" is forced for
        // ENGLISH-scope tiers ONLY when the on-device engine is the transcriber; a cloud session
        // (cloudWrapper != null — batch or live) gets the user's setting (auto / specific) as is,
        // and its English-only local MIRROR is re-pinned to "en" through the fallback's
        // mirrorLanguage hook, wired in resolveTranscriptionEngine.
        val installedModel = app.whisperModelManager.installedModel()
        // The user's own pick, read ONCE and then spent twice with deliberately different
        // meanings: whisper transcribes in the RESOLVED language below (the `.en` pin), and the
        // previewer's gate reads this RAW value (4.4.1 — see `previewLanguage` at the wrap site).
        val selection = app.preferencesManager.getLanguageForApi()
        val lang = sessionLanguageFor(
            installedScope = installedModel?.scope,
            selection = selection,
            engine = if (cloudWrapper != null) TranscribingEngine.CLOUD else TranscribingEngine.LOCAL,
        )
        android.util.Log.i("WE-DIAG", "connect lang resolved=$lang (modelScope=${installedModel?.scope} cloud=${cloudWrapper != null})")

        // 4.4.0: THE ONE WRAP SITE (spec §4.1 step 2, §5). After the language resolved, before
        // connect: the gate reads the SELECTED language (4.4.1, below), the pack, the session kind
        // (cloudWrapper != null IS the right predicate here — cloud batch and live keep today's
        // strip), a running batch job, the switch (R3) and the resident previewer's readiness
        // (false while loading; false forever after a failed canary — R1). When it arms, the
        // session engine becomes the tee and transcriptionEngine is RE-POINTED at it, so the
        // capture callback, the commit funnel and the stop path all drive the tee. The strip
        // rules read sessionHasLocalPreview (Task 1's second input) — assigned the gate's answer,
        // never a constant. Every input is logged, so a "why no words?" is one grep.
        //
        // (4.4.1 acquisition amendment, owner ruling 1: *"if they leave it in auto, then you get
        // no live streaming at all"*) THE PREVIEWER'S LANGUAGE IS THE USER'S OWN SELECTION — null
        // on Auto — and NOT whisper's `lang`. The two diverge on exactly one tier shape:
        // sessionLanguageFor pins an ENGLISH-scope tier (eco, pro — the `.en` weights) to "en"
        // whatever the user picked, because those weights can neither auto-detect nor transcribe
        // anything else. Handing that pin to the previewer armed live words for every Auto user on
        // a `.en` tier — the default selection on the majority local tier — while this release's
        // copy tells them on the card, in the picker, in Settings and at onboarding that Auto
        // shows none at all, and while the acquisition side (which reads the SELECTION) would
        // never fetch that pack for them. One pick keyed two ways is a product contradicting
        // itself, so the previewer reads the selection and whisper's own resolution is untouched:
        // an Auto session on eco still TYPES English, it just shows no live words.
        val previewLanguage = selection
        // (4.4.1 acquisition amendment) WHICH previewer packs are on disk, read ONCE for the gate
        // and the warm-up below: the gate's language term is now "is this session's language one
        // of these" rather than an English literal, and the set is the catalogue's own answer.
        val installedPreviewLanguages = app.streamingPackManager.installedLanguages()
        val packInstalled = previewLanguage != null && previewLanguage in installedPreviewLanguages
        val userEnabled = app.preferencesManager.localPreviewEnabled
        // (4.4.1, CHANGE 5) The warm site takes the SAME lookup as the arm site — warm the pack
        // for the selected language, or warm nothing — so the load and its +169 MB are never
        // spent on a recognizer the gate below then refuses. Called unconditionally when that
        // lookup answers a pack, not `streamingPreview ?: warm…`: after an onTrimMemory the field
        // still holds the engine with its recognizer freed, and only a second warm() reloads it.
        // Spec §4.1 step 10 — "the next eligible session re-warms" — is that call, and CHANGE 5's
        // second bullet is that it re-warms for the SELECTED language only, which this lookup is.
        // It is a no-op when the engine is already warm on this pack or permanently disabled, so
        // the cost here is one executor post.
        val packToWarm = previewPackToWarm(
            previewLanguage = previewLanguage,
            installedPackLanguages = installedPreviewLanguages,
            userEnabled = userEnabled,
        )
        val preview = if (packToWarm != null) warmStreamingPreview(packToWarm) else streamingPreview
        // (4.5.0 T2, defect 4) `isWarmFor`, not `isWarm`: the engine's verdict and its recognizer
        // are per-PACK now, so "is the previewer ready" has to name the pack it is ready FOR.
        // `isWarm()` alone would answer yes for a French recognizer during an English session —
        // the tee would then borrow the wrong language's model. The armed answer below is
        // unchanged today either way: a null `packToWarm` means the selection has no installed
        // pack or the switch is off, and `localPreviewArms` refuses on those same terms.
        val previewReady = packToWarm != null && preview?.isWarmFor(packToWarm) == true
        val previewArmed = localPreviewArms(
            sessionLanguage = previewLanguage,
            installedPackLanguages = installedPreviewLanguages,
            isCloudSession = cloudWrapper != null,
            batchJobActive = BatchJobController.active != null,
            userEnabled = userEnabled,
            previewReady = previewReady,
        )
        // `lang=` on this line is the PREVIEWER's language — the user's selection, `auto` when
        // there is none — and not whisper's resolved pin, which the `connect lang resolved=` line
        // above carries: this line exists to explain the gate's own answer, so it logs the gate's
        // own input. `pack=` is now THIS language's pack being on disk, not 4.4.0's "the English
        // pack is on disk": CHANGE 5 makes the warm-up above the selected language's, and this
        // field's whole reason for existing is to say what the warm-up and the gate read. So on
        // Auto it reads 0 with the English pack installed — which is the honest line for a session
        // that neither warms nor arms, and the one difference a reader comparing against a 4.4.0
        // capture will see.
        android.util.Log.i(
            "WE-DIAG",
            com.whispereverywhere.transcription.stream.StreamDiag.gateLine(
                previewLanguage, packInstalled, cloudWrapper != null, BatchJobController.active != null,
                userEnabled, previewReady, previewArmed,
            ),
        )
        sessionHasLocalPreview = previewArmed
        // (4.4.1, CONTROLLER RULING 2026-09-11, CHANGE 4 — the write MOVED to onOpen in 4.5.0 T4
        // fix round 1, review r1's B1.) THE ANNOUNCEMENT RETIRES ITSELF WHEN THE SESSION OPENS,
        // and deliberately not here. Home's card says "Live words are on" until the user has
        // actually seen them; arming is necessary for that and it is NOT sufficient. This gate has
        // no tier term (`PreviewUnreachable`'s KDoc), so on a device with no speech model and no
        // configured provider `decideEngineChoice` answers LOCAL_ONLY, `cloudWrapper` stays null
        // and the gate answers TRUE — on the one phone where `LocalWhisperEngine.connect` is about
        // to answer "No speech model installed", the session is torn down out of CONNECTING and
        // not one word is ever rendered. Writing the flag here marked that user as having watched
        // live words appear; the flag is global and permanent, so the day they took this feature's
        // own advice and downloaded a model, `Card.INSTALLED` was suppressed forever — for exactly
        // the reader the announcement exists for. It is written in onOpen now, where the session
        // has reached RECORDING and the startup ring begins draining into the engine: before that
        // instant no audio reaches the previewer at all (`StartupSeam.route` buffers until
        // `engineReady`), so "armed AND opened" is the earliest moment a word can have appeared,
        // which is the claim the flag makes.
        val engine: TranscriptionEngine = if (previewArmed) {
            com.whispereverywhere.transcription.stream.PreviewTeeEngine(requireNotNull(preview), baseEngine)
                .also { transcriptionEngine = it }
        } else {
            baseEngine
        }

        // ======================= 4.4.0 S2 — THE STARTUP SEAM's session open =======================
        // THE RECORDER OPENS HERE, above connect(), and the capture thread starts with it. Until
        // this task all four statements below lived inside the engine's onOpen(), so on the local
        // tiers the microphone stayed shut for the whole native context load — 4,107 ms cold on
        // npu-turbo (docs/superpowers/research/2026-09-10-startup-cutoff-investigation.md §3a,
        // measured in capture-yt-84-flatline-0903-1936.txt) — and the audio spoken into that
        // window was never captured at all. It is now captured into startupRing and replayed
        // through the same feedEngine path at onOpen.
        //
        // THE MOVE IS LEGAL because nothing here depends on connect(): AudioSourcePolicy.decide
        // reads only mediaPlaying / hasProjection / sdkInt / preferDeviceAudio / consentAvailable
        // (investigation §2 fact 3), transcriptionEngine is already assigned (resolveTranscriptionEngine
        // above, and the tee's own `.also` when the previewer arms — §2 fact 1), and sendAudio was
        // already unconditional with only a 30 s accumulation backstop (§2 fact 2). The one thing
        // the move DOES change, deliberately: mediaDetector.isCurrentlyPlaying() is now sampled at
        // the tap instead of up to 4 s later, which is the more faithful reading of intent.
        //
        // THE ORDER OF THESE FOUR STATEMENTS IS THE TASK'S CORRECTNESS, and three of the four
        // hazards fail SILENTLY. StartupRingWiringPinTest, EndpointerLifecyclePinTest and
        // BackpressureWiringPinTest hold every one of them:
        //
        //  1. BOTH SESSION CLOCKS ARE ANCHORED AT THE TAP (sessionStartMs), not at engine-ready.
        //     That is what makes the replayed stamps arithmetically sound — every wall-clock term
        //     downstream is `nowMs - <an earlier nowMs>` and the ring's chunks carry their own
        //     capture stamps, all of which are >= this anchor. It is also a deliberate BEHAVIOUR
        //     CHANGE: the 4 s first-segment cap window now starts when the user started talking,
        //     which is what it always meant to measure, so on a slow cold connect it can fire
        //     DURING the replay and produce the cut the user would have got had the engine been
        //     warm. The cloud suppression MOVES WITH THE ANCHOR (statement 2) or 3.6.0 A2's "no
        //     extra billable first request" regresses.
        //  2. endpointer.onSessionStart MUST PRECEDE startAudioInput(), because it fires probeArm()
        //     and the ordering that carries probeArm's precondition is a THREAD START, not
        //     reachability (audio/VadProbeLifecycle.kt precondition 2, EndpointerFactory's `mine`):
        //     the capture thread snapshots its epoch at its first probe call, so a thread started
        //     before arm() snapshots NO_SESSION, ensureReady(session) is false forever, and the
        //     Silero VAD is OFF for the whole session — amplitude fallback, i.e. the pre-3.7
        //     machine, with no error and no log beyond an absent `probe:` line.
        //  3. endpointer.onSessionStart MUST ALSO STAY ABOVE setActiveSource, which
        //     startAudioInput() reaches through startMicSource / startPlaybackSource:
        //     onSessionStart writes flatlineArmed = false and setActiveSource writes
        //     armFlatline(source == PLAYBACK), so reversed, the 4.4 flatline cut is permanently
        //     disarmed for every device-audio session. Keeping startAudioInput() BELOW this call is
        //     what preserves it — which is why the two moved together and not one of them.
        //
        // Per-session reset: the FIRST-segment 4 s cap applies again from here.
        segmentCapPolicy.onSessionStart(sessionStartMs)
        // 3.6.0 (Workstream A) — the 4 s first cap is LOCAL-ONLY. This VAD/cap path also runs for
        // CLOUD_WITH_FALLBACK (runClientVad is true for it; only CLOUD_LIVE sets sessionIsLive),
        // and there an extra first segment means an extra provider round-trip, an extra fallback
        // mirror and an extra BILLABLE request — while the user's first-text wait is the network,
        // not inference. Closing the first-cap window immediately (the same "any commit ends it"
        // rule SegmentCapPolicyTest pins) leaves cloud sessions on the pre-existing 15 s cap for
        // every segment: byte-identical to 3.5.0. cloudWrapper is already resolved here —
        // resolveTranscriptionEngine() ran above — and it is the same cloud predicate stopRecording
        // uses. 4.4.0 S2: it moved down here WITH the anchor, and had to.
        if (cloudWrapper != null) segmentCapPolicy.onCommit(sessionStartMs)
        // 3.7 (Workstream D3): the endpointer's paced-commit floor is the MEASURED cost governor,
        // and it is per-session because it depends on BOTH the installed tier and whether every
        // commit becomes a provider request. cloudWrapper is already resolved here — see the note
        // above — and installedModel was resolved just before this block. Armed BEFORE
        // startAudioInput() so the first captured frame already sees this session's cadence; the
        // native probe itself initialises lazily on that first frame, i.e. on the capture thread,
        // never here on Main.
        //
        // This REPLACES the endpointer.reset() that used to open the session here, rather than
        // joining it: onSessionStart is a documented strict superset of reset() ("Everything
        // [reset] clears, plus…" — its KDoc), so a pair would clear twice and put two probeResets
        // inside one session open. Three documents already describe the service as resetting from
        // THREE sites with the session open carrying onSessionStart instead: SileroEndpointer's
        // Threading section, SileroEndpointerTest's volatility pin, and
        // SileroEndpointerConcurrencyTest's class KDoc, which carries the count.
        //
        // NAMED arguments, not positional: onSessionStart takes three same-typed Longs whose order
        // nothing else pins, and minCommitIntervalMs takes a nullable String beside a Boolean.
        // EndpointerLifecyclePinTest quotes these names.
        //
        // isCloudBatch = (cloudWrapper != null) is BROADER than "batch": it is also true for
        // CLOUD_LIVE. For a SERVER-driven live session that is harmless — LiveTurnPolicy.runClientVad
        // is false, onFrame never runs, this cadence is never consulted. A GEMINI live session
        // (4.3.4) DOES run the client VAD, so the predicate is split here as the old note demanded:
        // isCloudLive wins and selects the live row
        // (CommitCadencePolicy.MIN_COMMIT_INTERVAL_CLOUD_LIVE_MS) — a turn boundary there is an
        // activityEnd, not a billable request, so the batch REQUEST floor would only have paired
        // short sentences for nothing.
        endpointer.onSessionStart(
            nowMs = sessionStartMs,
            minCommitIntervalMs = CommitCadencePolicy.minCommitIntervalMs(
                tierId = installedModel?.id,
                isCloudBatch = cloudWrapper != null,
                isCloudLive = sessionIsLive,
            ),
            // Build 85 — THE BACKPRESSURE GOVERNOR's slow row, from the SAME two facts: 3 200 on
            // npu-turbo, equal to the fast row on every other tier (inert by construction), the
            // flat 3 000 in a cloud-batch session. The parameter is defaulted on the interface
            // (slow == fast), so omitting it compiles and ships turbo without the guard;
            // BackpressureWiringPinTest holds it here, resolved before startAudioInput() like the
            // fast row.
            slowCommitIntervalMs = CommitCadencePolicy.slowCommitIntervalMs(
                tierId = installedModel?.id,
                isCloudBatch = cloudWrapper != null,
                isCloudLive = sessionIsLive,
            ),
        )
        val started = startAudioInput()
        android.util.Log.i("WE-DIAG", "recorder start success=${started.isSuccess}")
        if (started.isFailure) {
            showToast("Recording failed: ${started.exceptionOrNull()?.message}")
            // teardownRealtime() closes both sources and discards the ring; connect() is never
            // called, so no engine is left holding a session that cannot produce anything.
            teardownRealtime(); updateBubbleState(BubbleState.ERROR)
            return
        }

        engine.connect(lang, object : TranscriptionEngine.Listener {
            override fun onOpen() {
                android.util.Log.i("WE-DIAG", "onOpen handler: state=$currentState")
                serviceScope.launch(Dispatchers.Main) {
                    if (currentState != BubbleState.CONNECTING) return@launch
                    // 4.4.0 S2 — TRUE READINESS, and the only thing left to do here.
                    //
                    // Everything this handler used to do at session open now runs ABOVE connect()
                    // in startRecording — the cap anchor, the cloud suppression, the cadence
                    // handover and startAudioInput() itself — because on a cold local tier this
                    // callback arrives up to 4,107 ms after the tap and the microphone may not
                    // wait for it. What is left is the one flag the capture thread reads: from the
                    // next chunk on, onAudioChunk takes the DRAIN route and replays the ring
                    // through the same feedEngine path, paced, four buffered chunks per live one.
                    //
                    // ONE line, naming what the ring is holding and what this session lost at the
                    // cap. It is emitted BEFORE the flag so its numbers are the ones the drain is
                    // about to release, rather than whatever is left after the first slice.
                    android.util.Log.i(
                        "WE-DIAG",
                        StartupRing.drainLine(
                            chunks = startupRing.chunkCount(),
                            bufferedMs = StartupRing.msOf(startupRing.byteSize()),
                            droppedMs = StartupRing.msOf(startupRing.droppedBytes()),
                        ),
                    )
                    engineReady = true

                    // One preview pipeline for every session context (W2): the accumulating
                    // window + sink, always. Live sessions additionally stream onto the strip.
                    showSessionPreview(live = sessionIsLive)

                    updateBubbleState(BubbleState.RECORDING)
                    // 4.4.0 S2 (the amendment's haptic ruling) — THE "LISTENING" CUE, and this is
                    // the site that makes it true. It used to fire at the tap, before connect() was
                    // even called, so on every cold local session it preceded the first captured
                    // sample by the whole model load (4,107 ms measured on npu-turbo) and the app
                    // buzzed "go" at a shut microphone. Note what was ALREADY right: the bubble's
                    // colour and waveform switch here, at real readiness — only the haptic was
                    // early. With the startup ring in place the user has been recorded since the
                    // tap's own vibrateTap(), so moving this is honesty about state, not a gate on
                    // capture: the cue now means "the engine has your words", and the words spoken
                    // before it are in the ring on their way through.
                    vibrateStart()
                    // (4.4.1 CHANGE 4; MOVED here from the gate's call site by 4.5.0 T4 fix round
                    // 1 — the argument is at the gate, above.) THE USER HAS SEEN LIVE WORDS.
                    // Guarded by the gate's own answer and never a re-derivation of it, written at
                    // the one instant that makes the claim true: this session is RECORDING, so the
                    // ring is draining into the tee and the previewer is composing. A session that
                    // arms and never opens — every session on a device with no speech model —
                    // writes nothing, so the announcement survives for the day a tier lands.
                    if (previewArmed) app.preferencesManager.livePreviewArmedOnce = true
                    amplitudeJob = serviceScope.launch {
                        audioRecorder.amplitude.collectLatest { amp ->
                            if (currentState != BubbleState.RECORDING) return@collectLatest
                            // Waveform only; the VAD/commit runs per-chunk in the recorder callback.
                            waveformView.updateAmplitude(amp)
                            blobView.updateAmplitude(amp)
                        }
                    }
                    // 4.4.0 S2, round 1 (B3) — THE HANDOVER THE CONNECT WINDOW SWALLOWED. Media
                    // that began while this session was CONNECTING could not take the mic: the
                    // "media transcription cuts the mic" gate requires RECORDING, and the detector
                    // is edge-triggered, so that notification never comes again while the same app
                    // plays. It was latched instead, and this is the first instant the gate can
                    // pass it — the state became RECORDING three lines up, and the ring is still
                    // whole, so the handover's flush cuts the microphone's pre-roll into the
                    // MICROPHONE's own segment rather than replaying it past a boundary.
                    //
                    // The playing LEVEL is re-read rather than trusted from the latch: a video
                    // started and stopped during the load is no reason to take the stream, and
                    // reading it here is exactly what pre-S2 sampled at this point (the decision
                    // itself ran inside this handler).
                    if (pendingDeviceAudioHandover) {
                        pendingDeviceAudioHandover = false
                        if (mediaDetector.isCurrentlyPlaying()) {
                            android.util.Log.i("WE-DIAG", "latched handover: media still playing at readiness")
                            handOverMicToDeviceAudio()
                        } else {
                            android.util.Log.i("WE-DIAG", "latched handover: media stopped during connect -> staying on the mic")
                        }
                    }
                }
            }
            override fun onDelta(text: String) {
                // 3.7 G: deltas no longer drive the strip for any NON-LIVE session — the
                // commit/resolve in-flight line does. In practice the only deltas turned away here
                // are the local engine's, because cloud batch emits none at all; the gate is
                // written on the session kind rather than on the engine so it matches the render's.
                // The callback itself, DeltaThrottle and transcribeStreaming's JNI plumbing are
                // deliberately left running: CLOUD_LIVE still renders from here, and the local
                // stream stays available for the next surface that wants it.
                if (!deltaOwnsPreviewStrip(sessionIsLive = sessionIsLive, sessionHasLocalPreview = sessionHasLocalPreview)) return
                // Local partial streaming (3.6.0 D) joined cloud-live here. The unified preview
                // (W2) keeps the container up for EVERY session context, so the strip renders
                // wherever deltas exist — no context gate. Resolved turns accumulate into the
                // window below it.
                serviceScope.launch(Dispatchers.Main) {
                    // 3.6.0 D: the strip carries the FINALIZING status line; local tail deltas must not clobber it.
                    // The stop tap writes "Finishing… (waiting on provider)" / "Finishing
                    // transcript…" here and THEN flushes the tail segment, which now streams —
                    // ungated, its deltas would replace that line and its terminal blank would
                    // hide the strip for the whole drain, next to E6's counting-up ticker. Same
                    // guard deliverReleasedText already uses for its sibling reset.
                    if (currentState == BubbleState.FINALIZING) return@launch
                    if (text.isNotBlank()) {
                        // 4.4.0 P0: `== GONE`, never `!= VISIBLE`, and the same predicate the
                        // label render uses. A local preview parks the strip at INVISIBLE between
                        // utterances (deltaBlankVisibility's OCCUPYING_BLANK row, below), and
                        // INVISIBLE already occupies layout height — estimatedWindowSize grants
                        // the strip its ~100dp on `!= GONE` — so a parked reveal is not the
                        // window-growing step a reveal from GONE is. Not a promise of NO growth:
                        // currentWindowSize prefers the measured view once laid out, and a
                        // five-line partial landing on a one-line parked strip does grow the
                        // window — un-reclamped, exactly as every delta-to-delta text change
                        // already is, and spec:139 budgets one reclamp per session rather than one
                        // per repaint. Widened to `!= VISIBLE` this fires on every parked reveal,
                        // paying one reclampNow() per utterance and making the park inert: exactly
                        // the churn the park exists to remove. Both spellings above drop the
                        // `View.` prefix deliberately: the wiring pin holds the View-prefixed
                        // spelling of the widened predicate at ZERO occurrences in this method, so
                        // prose that quotes it in full breaks that census — as this very sentence
                        // did on its first draft.
                        val wasHidden = transcriptionDeltaText.visibility == View.GONE
                        transcriptionDeltaText.visibility = View.VISIBLE
                        if (wasHidden) {
                            // the strip just grew the window downward — keep it on-screen
                            bubbleView.post { reclampNow() }
                        }
                        transcriptionDeltaText.text = text
                        // Keep the newest words in view. The panel grows to maxLines then
                        // scrolls; without this it would hold the TOP of a long utterance and
                        // the live words would stream out of sight — the opposite of the point.
                        // Posted so the scroll runs after layout has measured the new text.
                        transcriptionDeltaText.post {
                            val overflow = transcriptionDeltaText.layout?.let { l ->
                                l.getLineBottom(l.lineCount - 1) - transcriptionDeltaText.height
                            } ?: 0
                            transcriptionDeltaText.scrollTo(0, overflow.coerceAtLeast(0))
                        }
                    } else {
                        // 4.4.0 P0: a local preview parks, a live session hides (deltaBlankVisibility).
                        when (deltaBlankVisibility(
                            sessionHasLocalPreview = sessionHasLocalPreview,
                            currentlyHidden = transcriptionDeltaText.visibility == View.GONE,
                        )) {
                            StripVisibility.HIDDEN -> transcriptionDeltaText.visibility = View.GONE
                            StripVisibility.OCCUPYING_BLANK -> {
                                transcriptionDeltaText.text = ""
                                transcriptionDeltaText.visibility = View.INVISIBLE
                            }
                            StripVisibility.SHOWING -> Unit
                        }
                    }
                }
            }
            override fun onSegmentResolved(seq: Long, outcome: SegmentOutcome) {
                android.util.Log.i("WE-DIAG", "onSegmentResolved: seq=$seq outcome=${outcome.javaClass.simpleName}")
                // Hop to Main FIRST: the orderer is main-thread confined, and the engine calls
                // this from its executor thread. The hop is the same one the old onCompleted did,
                // so delivery timing is unchanged.
                serviceScope.launch(Dispatchers.Main) {
                    // The Release is captured rather than inlined so the perceived stamp can be
                    // read at the moment this seq's words reached the user. deliverReleasedText
                    // early-returns on blank and otherwise hands the resolved text to the preview
                    // sink; the TextView write itself lands on the NEXT Main dispatch (the sink's
                    // StateFlow -> previewJob's collectLatest), so the metric excludes one Main
                    // hop and one frame — single-digit to ~20 ms against a 1.3-2.8 s number, and
                    // biased low. Reading the stamp above the call would instead time the START of
                    // delivery. The SegmentOrderer's release rules are untouched — this only names
                    // its result.
                    val release = segmentOrderer.onResolved(seq, outcome)
                    // 3.7 G: the decrement runs ahead of BOTH painters — this one and the one
                    // inside deliverReleasedText — because either would otherwise paint a
                    // PRE-decrement depth and show a backlog one deeper than it is. (The decrement
                    // itself was never at risk of being skipped: it lives inside this log call, and
                    // deliverReleasedText's blank guard returns from THAT METHOD, not from this
                    // coroutine. The `queue:` line moves as the decrement's passenger, not as the
                    // reason.) The repaint lives HERE, and not only in delivery, for the blank
                    // case: an EmptyExpected or a Lost segment releases no text, delivery's painter
                    // sits below its blank guard, and without this call that resolution would
                    // count down in logcat while the strip stayed at the old depth.
                    android.util.Log.i(
                        "WE-DIAG",
                        EndpointDiag.queueLine(segmentQueueDepth.onResolved(seq)),
                    )
                    renderInFlightStrip()
                    deliverReleasedText(release.text)
                    // The stamp is consumed ONLY on a non-blank release, and that gate is load
                    // bearing rather than cosmetic. Resolutions arrive OUT OF ORDER on cloud
                    // (CloudTranscriptionEngine runs Semaphore(3) and completes in network order),
                    // so an overtaking seq reaches here while the orderer still holds it and its
                    // release is blank. Consuming the stamp there would drop that seq's number AND
                    // prune its predecessor's, losing BOTH perceived: lines — measured, and pinned
                    // by PerceivedLatencyTest's resolution-order rows. G5 moves the queue line and
                    // the repaint; it must NOT move or re-shape this gate, which
                    // PerceivedStampPinTest#theStampIsConsumedOnlyOnANonBlankRelease pins verbatim.
                    if (release.text.isNotBlank()) {
                        perceivedLatency.onVisible(seq, System.currentTimeMillis())?.let { waited ->
                            android.util.Log.i("WE-DIAG", EndpointDiag.perceivedLine(seq, waited))
                        }
                    }
                }
            }
            override fun onError(message: String) {
                android.util.Log.w("WE-DIAG", "onError: state=$currentState msg=$message")
                if (currentState == BubbleState.RECORDING) {
                    // mid-session segment failure -> log and keep recording; do NOT tear down
                    android.util.Log.w("FloatingBubble", "Transcription segment failed (continuing): $message")
                    return
                }
                // connect-time / fatal (e.g. no model installed)
                serviceScope.launch(Dispatchers.Main) {
                    updateBubbleState(BubbleState.ERROR)
                    // W2: deliver best-effort BEFORE teardown nulls the sink and ends the
                    // session binding — a fatal error mid-FINALIZING must not eat the
                    // transcript. Connect-time fatals have an empty/absent sink, so the
                    // isNotEmpty gate keeps them delivery-free. finalDelivered keeps this
                    // once-only against the finalize coroutine, which skips its own delivery
                    // when it wakes in ERROR state (its FINALIZING guard fails).
                    runCatching {
                        deliverReleasedText(segmentOrderer.flush().text)
                        transcriptSink?.close()
                        val full = transcriptSink?.fullTextFile()?.readText()?.trim() ?: ""
                        if (full.isNotEmpty()) deliverFinalTranscript(full)
                    }
                    teardownRealtime()
                }
            }
            override fun onClosed() { /* expected on manual stop */ }
        })
    }

    private fun stopRecording() {
        // C1 finalize-timing: every phase of the stop path is measured from this instant.
        // Permanent diagnosis capability (spec 2026-08-18 C1) — grep "finalize-timing:".
        val stopTapNs = System.nanoTime()
        android.util.Log.i("WE-DIAG", "stopRecording: state=$currentState")
        vibrateStop()
        amplitudeJob?.cancel(); amplitudeJob = null
        waveformView.stop()
        audioRecorder.stop()

        stopPlaybackCapturer()
        // A finished session ENDS the screen share (owner decision 2026-08-01). The token used to
        // be retained so the next media session could skip re-consent — but that kept Android's
        // "sharing screen" indicator alive indefinitely after the capture the user actually wanted,
        // and a later mic tap looked like it was still screen-sharing. Releasing here is safe for
        // the drain below: the capturer above has already stopped reading, and transcription runs
        // on PCM already buffered. Cost: the next device-audio session shows the consent sheet
        // again — one tap, and the honest trade for the indicator never lying.
        // clear() is also what the system-side stop path calls, so both exits converge.
        if (com.whispereverywhere.audio.MediaProjectionGate.hasProjection()) {
            android.util.Log.i("WE-DIAG", "stopRecording: releasing screen-capture projection")
            com.whispereverywhere.audio.MediaProjectionGate.clear()
        }
        // Deliberately the raw field: the session is ending and the final commit + drain below
        // still belong to this session's engine. Every new session starts on the mic — 4.4.0 S2:
        // startAudioInput() picks the capturer at the TAP now, above connect(), and it picks it
        // from AudioSourcePolicy.decide() rather than from this field.
        activeSource = com.whispereverywhere.audio.ActiveSource.MIC

        updateBubbleState(BubbleState.FINALIZING)

        // Keep the preview VISIBLE through the drain: the backlog of committed segments keeps
        // streaming into it, which is the honest "still working" signal. Hiding it here left
        // only the spinner, and after a long capture users read that as a hang (user feedback
        // 2026-07-18, 25-min video). The status line below names what's happening; the preview
        // and the line come down together when the drain completes.
        // Every session shows the closing status now (W2 unified preview) — the accumulating
        // window is up for TEXT_FIELD sessions too, and this line is its "still working" signal.
        // Cloud/live sessions name the actual wait (the tail segment's provider round-trip) so an
        // honest two-second drain never reads as a hang; cloudWrapper is non-null exactly for
        // CLOUD_WITH_FALLBACK / CLOUD_LIVE sessions and is not retired until the next session.
        transcriptionDeltaText.text = if (cloudWrapper != null) {
            "Finishing… (waiting on provider)"
        } else {
            "Finishing transcript…"
        }
        transcriptionDeltaText.visibility = View.VISIBLE
        
        // 4.4.0 S2 — THE RING'S LAST FLUSH, and it is not hygiene. stopRecording is reachable only
        // from RECORDING, which since S2 means "the engine reported ready", so a user who taps stop
        // within ~2 s of the cue still has a backlog the paced drain has not caught up on: tap,
        // two words, stop, on a 4 s cold load would otherwise lose exactly the words this task
        // exists to save. sendAudio ONLY — the CUT is the unconditional commit immediately below,
        // and the endpointer's native probe may not be driven from Main (VadProbeLifecycle's one
        // shared direct buffer belongs to the capture thread).
        //
        // SAFE HERE AND NOWHERE EARLIER: audioRecorder.stop() and stopPlaybackCapturer() above
        // have each asked their capture thread to stop and joined it. That join is BEST-EFFORT
        // (T2-SHARPENED — Thread.join(ms) returns identically on termination and on timeout), and
        // this is the safe half of that bound rather than a claim about it: the ring synchronises
        // itself and removes each chunk under its own monitor, so a survivor racing this flush
        // cannot double-deliver a chunk or tear the byte tally — it just finds less to replay.
        transcriptionEngine?.let { sessionEngine ->
            val pendingMs = StartupRing.msOf(startupRing.byteSize())
            val flushed = startupRing.drainAll { pcm, _, _ -> sessionEngine.sendAudio(pcm) }
            if (flushed > 0) {
                android.util.Log.i("WE-DIAG", StartupRing.stopFlushLine(flushed, pendingMs))
            }
        }

        // Flush whatever is buffered, UNCONDITIONALLY. The amplitude segmenter misses quiet
        // speech below its fixed thresholds — gating this flush on hasPendingSpeech() silently
        // discarded whole sessions for soft talkers ("No speech detected" despite real speech).
        // The native Silero VAD inside whisper_full now makes the unconditional flush safe: a
        // silence-only tail is trimmed to nothing and returns empty, fast, with no junk tokens.
        transcriptionEngine?.let { commitSegment(it, EndpointDiag.STOP) }
        android.util.Log.i(
            "WE-DIAG",
            "finalize-timing: commit-dispatch=${(System.nanoTime() - stopTapNs) / 1_000_000}ms",
        )
        endpointer.reset()
        // 3.7 (Workstream D5/D8/D10, teardown bill T12): the probe's native context is freed HERE,
        // and only here on the record-stop path — the ~2.6 MB the context holds is released at
        // record stop rather than at process death.
        //
        // The POSITION is the pin, and it is a position in SOURCE, not a proof. Above this line,
        // audioRecorder.stop() and stopPlaybackCapturer() have each asked their capture thread to
        // stop and joined it, and the unconditional flush above still belongs to this session — so
        // freeing any earlier would race the audio it is flushing, and freeing after the drain
        // would hold the context across an arbitrarily long backlog. What the ordering does NOT
        // give is exclusion: T2-SHARPENED — Thread.join(ms) returns identically on termination and
        // on timeout and stopThenJoin returns Unit, so the join is BEST-EFFORT and its outcome is
        // unobservable from here. A late free landing after the NEXT session's vadProbeInit is an
        // ownership hazard the BOUND LAMBDA must survive (VadProbeLifecycle, Tasks D4/D5); this
        // line may not claim the join prevents it.
        //
        // Main-thread budget (T1 RESIDUAL): the two capture stops above are COMPOSITE, not one
        // join — 2 x CaptureThreadPolicy.CAPTURE_JOIN_MS, ~4 s worst case, under the 5 s
        // input-dispatch ANR window with ~1 s headroom. onSessionEnd adds probeStats' instance
        // monitor (a handful of int ops) and the free itself; both are budgeted against that
        // composite, not against a single join.
        endpointer.onSessionEnd()

        // Server-driven live only: the stop commit above cut the final open utterance under a tail
        // seq, but in server-driven mode NO server VAD endpoint can ever resolve it now — the mic is
        // closed, so no silence frame / `<end>` / committed_transcript will arrive. Resolve that tail
        // (and any still-in-flight committed turn) HERE, while the fallback's retained PCM is valid,
        // so the drain below rescues each on-device instead of looping the whole FINALIZE_TIMEOUT_MS
        // on a pending that can never empty and then dropping the tail as a bare marker at teardown.
        // No-op for batch/local (finishServerTurns guards on serverDriven, and lastLiveEngine is only
        // non-null for a CLOUD_LIVE session) — and for a client-VAD live session (Gemini): there
        // the stop commit's activityEnd DOES draw the provider's final within ~0.5 s, so the drain
        // below waits for it under the engine's tailGraceMs and only then rescues locally.
        if (sessionIsLive) lastLiveEngine?.finishServerTurns()

        // Drain the ENTIRE transcription backlog before detaching the listener. A slow model (e.g.
        // the large tier) lags several segments behind real time; those queued transcribes finish
        // after the last utterance, and without waiting they'd complete post-teardown and be dropped
        // by the stale-listener guard (the user saw "No speech detected" despite valid audio). Each
        // result injects live as it finishes, so the earlier chunks keep appearing during the
        // finalize wait. Bounded by FINALIZE_TIMEOUT_MS + the local drain reserve (see FallbackTranscriptionEngine.awaitIdle). The blocking await runs on IO; UI on Main.
        serviceScope.launch(Dispatchers.Main) {
            val drained = withContext(Dispatchers.IO) {
                transcriptionEngine?.awaitIdle(FINALIZE_TIMEOUT_MS) ?: true
            }
            if (!drained) {
                android.util.Log.w("WE-DIAG", "finalize: drain timed out after ${FINALIZE_TIMEOUT_MS}ms")
            }
            // Release anything the orderer is still holding, BEFORE the final delivery reads
            // the sink — held text's only exit is flush(), and the pile is largest exactly
            // here, at the end of a session. (Provably empty for the on-device engine, which
            // resolves in order.)
            val flushStartNs = System.nanoTime()
            deliverReleasedText(segmentOrderer.flush().text)
            android.util.Log.i(
                "WE-DIAG",
                "finalize-timing: orderer-flush=${(System.nanoTime() - flushStartNs) / 1_000_000}ms",
            )

            // ---- W2 single delivery: the ONE external write of the session. Runs BEFORE
            // teardownRealtime, because teardown ends the injection-session binding captured
            // at beginInjectionSession and the SESSION_BOUND write must resolve against it.
            // The sink is closed first (full flush; teardown's later close is a swallowed
            // no-op), then its file is read back as the one transcript source every session
            // kind shares. Preview hide / sink close / endInjectionSession all FOLLOW delivery.
            // Detach the sink FIRST so a late segment's append no-ops on null (the pre-reorder
            // contract: close+null were one statement). Then close (full flush) and read back.
            val deliveryStartNs = System.nanoTime()
            val finishedSink = transcriptSink
            transcriptSink = null
            finishedSink?.close()
            val fullTranscript = finishedSink?.let { sink ->
                withContext(Dispatchers.IO) { sink.fullTextFile().readText().trim() }
            } ?: ""
            if (currentState == BubbleState.FINALIZING) {
                // Guarded like its two sibling callers (onDestroy, fatal onError): a delivery
                // failure must never wedge the session in FINALIZING — teardown always runs.
                runCatching { deliverFinalTranscript(fullTranscript) }
                    .onFailure { android.util.Log.e("WE-DIAG", "final delivery threw", it) }
            }
            android.util.Log.i(
                "WE-DIAG",
                "finalize-timing: delivery=${(System.nanoTime() - deliveryStartNs) / 1_000_000}ms",
            )
            teardownRealtime()
            android.util.Log.i("WE-DIAG", "finalize: state=$currentState producedText=$sessionProducedText")

            // Feed the stats the Home screen has been showing since 1.x. The tracker itself has
            // existed all along, but NOTHING ever called its record methods — the panel sat at
            // zero forever (owner report 2026-08-01, screenshot: 0:00 / 0 sec / 0 after weeks of
            // real use). Session seconds are wall-clock from startRecording to here (what a user
            // means by "time transcribing"), counted once per session at finalize; a session
            // that produced text counts as one transcription.
            // Capture the session stamp BEFORE the stats block zeroes it — the history persist
            // below names its file by this value, and saving with 0 meant "0.txt", which the
            // sweep on the next line deleted as decades stale (the vanishing-history bug:
            // every transcription silently erased at finalize since the stats fix landed).
            val sessionStamp = sessionStartMs
            if (sessionStartMs > 0L) {
                val sessionSeconds = ((System.currentTimeMillis() - sessionStartMs) / 1000L).toInt()
                if (sessionSeconds > 0) {
                    app.usageTracker.addUsage(sessionSeconds)
                    app.usageTracker.addToTotalUsage(sessionSeconds)
                    // Month cost estimate: only cloud sessions cost anything; the rate follows the
                    // transport the session actually used (live WS vs batch POST).
                    sessionCloudProviderId?.let {
                        app.cloudCostTracker.recordCloudSeconds(it, live = sessionIsLive, seconds = sessionSeconds)
                    }
                }
                sessionStartMs = 0L // exactly once per session, even if finalize re-enters
            }
            if (sessionProducedText) app.usageTracker.incrementTranscriptionCount()

            // If the cloud provider latched a fatal this session, SAY SO — once per latch, not per
            // session. Without this the local fallback masks the failure completely: the
            // 2026-07-29 device test ran an entire "cloud" session on latched fatals and the
            // owner had no way to know. Read at finalize, after the drain, so the latch state is
            // final. (Pulled forward from C2c's degradation UX for exactly this reason.)
            lastCloudEngine?.lastFatal()?.let { fatal ->
                if (notifiedFatalKind != fatal.kind) {
                    notifiedFatalKind = fatal.kind
                    showToast("${fatal.message} — used the on-device model instead")
                }
            }
            // Same latch-toast for a live session: the live engine reports a bare FatalKind (its
            // WS handshake carries no safe body to build a message from — credential safety), so the
            // kind is mapped to the same copy the batch providers use. Exactly one of the two
            // engines is ever non-null per session, and the once-per-latch dedup is shared.
            lastLiveEngine?.lastFatal()?.let { kind ->
                if (notifiedFatalKind != kind) {
                    notifiedFatalKind = kind
                    showToast("${liveFatalMessage(kind)} — used the on-device model instead")
                }
            }

            // History: persist the session (text only) + apply rolling retention.
            // NOTE: history inherits the FINALIZE_TIMEOUT_MS bound above — a segment still
            // transcribing when the 300s drain times out is dropped from injection AND history
            // (pre-existing late-result semantics; the awaitIdle fence guarantees everything
            // that completes in time IS in sessionTranscript before this persist).
            if (sessionTranscript.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    transcriptStore.save(sessionStamp, sessionTranscript.toString())
                    transcriptStore.sweep()
                }
            }
            // C1 finalize-timing: total spans stop-tap → teardown/stats/history done. Logged
            // OUTSIDE the FINALIZING guard so every exit of the finalize coroutine reports it;
            // total minus (commit-dispatch + drains + orderer-flush + delivery) exposes the
            // teardown/stats/history span without needing its own phase name.
            android.util.Log.i(
                "WE-DIAG",
                "finalize-timing: total=${(System.nanoTime() - stopTapNs) / 1_000_000}ms",
            )
            if (currentState == BubbleState.FINALIZING) {
                // Delivery already happened above, pre-teardown, through FinalDeliveryPolicy.
                if (!sessionProducedText) {
                    showToast("No speech detected — try again a bit louder or closer to the mic.")
                }
                vibrateSuccess()
                updateBubbleState(BubbleState.IDLE)
            }
        }
    }

    private fun teardownRealtime() {
        // 4.4.0 S2 — FIRST, and new to this task, because the recorder can now be open on a path
        // that never reached RECORDING. This function is the convergence point of every session
        // exit (normal drain end, recorder-start failure, connect-time fatal, onDestroy), and on a
        // CONNECT-TIME FATAL it is the only one: before S2 the capture thread did not exist on that
        // path, so nothing there had to close it. Without these calls a session that failed to
        // connect — no model installed, a dead key — would leave the microphone open and the
        // system's mic indicator up for a session that produces nothing.
        //
        // Both are idempotent (StreamingAudioRecorder.stop() returns immediately on !recording;
        // stopPlaybackCapturer() nulls its field), so the normal stop path — which stopped and
        // joined both long before it gets here — is unchanged.
        //
        // NOT freed here: the Silero probe's ~2.6 MB native context, which a fatal connect leaves
        // armed because the probe free is stopRecording's alone. That is the BOUNDED,
        // already-documented orphan case — VadProbeLifecycle's KDoc and WhisperNative.kt record
        // that vadProbeInit "frees the previous context first", so the next session's first frame
        // reclaims it and they cannot accumulate — and it is left as a named residue rather than a
        // second onSessionEnd site, which would double a census three documents carry.
        audioRecorder.stop()
        stopPlaybackCapturer()
        // And the startup ring is DISCARDED, never replayed: a failed connect has no engine to
        // replay into, and the next session must never open holding the previous one's audio.
        engineReady = false
        startupRing.clear()
        // Backstop flush: teardown is the LAST thing every session-exit path runs — normal drain
        // end (already flushed above, so this returns empty), recorder start failure, fatal
        // onError, and onDestroy. Held text is uniquely fragile: unlike per-segment injection it
        // accumulates finished work in RAM whose only exit is this call. Must run before the sink
        // is closed and before the injection session ends, or the released text has nowhere to go.
        deliverReleasedText(segmentOrderer.flush().text)
        previewJob?.cancel(); previewJob = null
        // The preview stays up through FINALIZING (live "still working" signal); EVERY teardown
        // path — normal drain end, error, start-failure, destroy — brings it down here.
        transcriptionDeltaText.visibility = View.GONE
        transcriptionPreviewContainer.visibility = View.GONE
        // Geometry change in the other direction (window shrinks back to the pill) — posted so
        // the re-measure has run. Harmless when nothing moved: reclampNow() no-ops on equality.
        bubbleView.post { reclampNow() }
        transcriptSink?.close(); transcriptSink = null
        WhisperAccessibilityService.endInjectionSession()
        // Detach the session listener but KEEP the engine + its loaded native context so the next
        // recording reuses it (no multi-hundred-MB reload per session). Full release (context +
        // worker thread) happens in onDestroy; the context is also freed under memory pressure
        // in onTrimMemory, reloading lazily on the next connect().
        transcriptionEngine?.close()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // CONNECTING is excluded alongside RECORDING/FINALIZING: a trim here can free ctxPtr
        // before the very first segment of the session runs, making the ctx==0 branch in
        // LocalWhisperEngine.runSegment reachable on segment #1 instead of only mid-teardown.
        if (level >= TRIM_MEMORY_RUNNING_LOW &&
            currentState != BubbleState.RECORDING &&
            currentState != BubbleState.FINALIZING &&
            currentState != BubbleState.CONNECTING
        ) {
            // The local engine directly: it owns the native context, and after a prewarm with no
            // recording yet `transcriptionEngine` is still null — so releasing through that field
            // would ignore memory pressure in exactly the idle state where it matters most.
            localEngine?.releaseContext()
            // 4.4.0 (spec §4.1 step 10, §7.4): the previewer's +170 MB goes back under the same
            // three-state guard. Not a verdict — the next eligible session re-warms it.
            streamingPreview?.release()
            // 4.4.0 startup amendment S1: and the counterpart the release never had — without it
            // the next tap pays the full cold load (4,107 ms on npu-turbo), silently.
            rearmPrewarmAfterTrim(level)
        }
    }

    /**
     * The pending post-trim re-arm, so a trim STORM collapses into one load (see
     * [rearmPrewarmAfterTrim]). Main-confined, like every other timer handle in this file;
     * `serviceScope.cancel()` in onDestroy retires it with the rest.
     */
    private var trimRearmJob: Job? = null

    /**
     * RE-ARM THE PREWARM AFTER A MEMORY TRIM (4.4.0 startup amendment, Task S1).
     *
     * [onTrimMemory] frees the native context while the service is idle and, until this existed,
     * nothing warmed it again: the only prewarm triggers were service start and a model
     * switch/install. So the next tap paid the whole cold load —
     * `docs/superpowers/research/2026-09-10-startup-cutoff-investigation.md` measures **4,107 ms**
     * on npu-turbo (§3a) and 237 ms–11,672 ms on CPU small — and paid it *silently*, because on the
     * local tiers `startAudioInput()` is nested inside the engine's `onOpen()`, so the microphone
     * is not open for any of it. The report's *"every once in a while"* is exactly which cold window
     * the tap lands in, and §3e names the post-trim one as the most frequent by construction: a
     * background overlay holding 342 MiB (`npu`) to 1.02 GiB (`npu-turbo`) is trimmed routinely.
     *
     * **It re-arms the boot prewarm's path, not a new one.** [warmLocalEngine] and then `prewarm`,
     * without the rebuild permission (the tier did not change — only the slot emptied), and
     * `prewarm` fills an EMPTY slot only. That last property is what makes the ordering free: the release
     * above and this load both queue on the engine's single native executor, so the load cannot
     * overtake the release, and if a `connect()` has already refilled the slot this no-ops.
     *
     * **And only when there is an engine to re-arm** (`localEngine != null`) — a correctness guard,
     * not a micro-optimisation. [warmLocalEngine] does not look an engine up: when the field is null
     * it BUILDS one, and it picks the backend from [npuTierIds], which is `emptySet()` until the
     * first [refreshNpuTierOffer] lands. A trim in the service's first seconds is not exotic —
     * `TRIM_MEMORY_UI_HIDDEN` is 20, so merely leaving the app right after starting the bubble
     * clears the release guard's `>= TRIM_MEMORY_RUNNING_LOW` (10) — and an unguarded re-arm fires
     * at `T_trim + 1500` while the boot prewarm fires at `refresh + 1500`, so it wins whenever the
     * trim beats the refresh. It would then build an npu-turbo user's FIRST engine on
     * `WhisperNativeBackend`, record `localEngineNpuTierId = null`, burn a ggml load immediately
     * after a memory trim, and hand the next session's [resolveTranscriptionEngine] a teardown plus
     * the 4,107 ms NPU load INSIDE `CONNECTING` — the exact cost this function exists to remove,
     * with a spurious `NpuDiag.tierRebuild` line in the log the A/B sheet reads as its instrument.
     * Null also says it exactly: `localEngine?.releaseContext()` above released nothing, so there is
     * nothing to re-arm and the boot prewarm is still coming. The guard is the fix, NOT a second
     * [refreshNpuTierOffer] here — a trim changes neither the installed set nor the device's
     * capability, and the refresh that decides this process's first build is the boot one, placed
     * adjacently above it for this very reason.
     *
     * **The wait is the boot prewarm's own 1,500 ms** ([TRIM_REARM_DELAY_MS]) — matched rather than
     * invented, which is the amendment's instruction — and it buys two things here. A trim arrives
     * as a storm (RUNNING_LOW, then RUNNING_CRITICAL, then BACKGROUND), and cancelling the pending
     * job collapses the storm into ONE load 1.5 s after the LAST trim, which is what the
     * model-switch collector gets from its 750 ms `collectLatest` debounce. It also keeps the
     * release honest: handing the pressure back a gigabyte in the same Main pass would not be
     * re-arming the prewarm, it would be deleting the trim.
     *
     * **The gate is re-read below the suspension**, for the reason the model-switch collector
     * states in its own fix round 2: a state read taken before the wait is not a check. Below it,
     * [prewarmRearmsAfterTrim] and the call are one uninterrupted run on Main — `currentState` has
     * a single writer (`updateBubbleState`) and nothing between the read and the call suspends — so
     * the state cannot change underneath it. A session that started in the gap is skipped, never
     * deferred: its own `connect()` loads the context this would have loaded.
     *
     * No `RejectedExecutionException` guard, unlike the model-switch collector: this is the boot
     * prewarm's call, which has none either, and this body can never hold a reference to a
     * shut-down engine. TWO shutdowns of that executor exist, not one. `onDestroy` cancels
     * `serviceScope` BEFORE it touches the engine, on this same (Main) thread, so those cannot
     * interleave; and a rebuild's `cached.shutdown()` inside [warmLocalEngine] is likewise
     * Main-confined and replaces `localEngine` synchronously within one Main run, so the field this
     * body reads is never the shut-down one.
     */
    private fun rearmPrewarmAfterTrim(level: Int) {
        trimRearmJob?.cancel()
        trimRearmJob = serviceScope.launch {
            delay(TRIM_REARM_DELAY_MS)
            val rearm = prewarmRearmsAfterTrim(currentState)
            android.util.Log.i("WE-DIAG", "trim re-prewarm: level=$level state=$currentState rearm=$rearm")
            if (rearm && localEngine != null) warmLocalEngine().prewarm()
        }
    }

    /**
     * THE ONE COMMIT FUNNEL (3.7 Workstream F). Every commit site in this service goes through
     * here, so the seq [TranscriptionEngine.commit] has always returned is captured exactly once
     * and in one place — which is what lets `queue:` (and, from Tasks F8/F9, `endpoint:` and
     * `perceived:`) join to `segment-timing:` on one key. No plumbing was required; the number was
     * always there and every call site threw it away.
     *
     * It adds NOTHING to the commit DECISION: the wall caps, the cloud 4 s suppression and the
     * unconditional stop flush all decide whether to call it exactly as before, and
     * `commitRetainingTailMs(0)` is `commit()` by first line and by test — so with an endpointer
     * that never fires, this is byte-identical to 3.6.0.
     *
     * [cut] is one of [EndpointDiag]'s four cut kinds and names WHY this commit happened.
     * [retainMs] is non-zero only at the wall-cap site, where the endpointer offered a micro-pause
     * to cut at. [nowMs] is the FRAME's clock at the two capture-thread sites and defaults to the
     * wall clock at the three Main-side ones; it exists so Task F9's speech-end stamp is measured
     * against the same instant the endpointer's `trailMs` was, not against a clock re-read after a
     * ~960 KB buffer snapshot. Returns exactly what the engine returned — the seq, or the -1
     * "nothing was cut" answer documented on [TranscriptionEngine.commit], which contributes
     * nothing to the backlog because it will never resolve.
     *
     * The `endpoint:` line is emitted even for a `-1` seq — "the endpointer fired and there was
     * nothing buffered" is exactly the kind of thing this family exists to make visible — while the
     * backlog deliberately ignores it, because that seq will never resolve.
     *
     * The speech-end instant is DERIVED from the same `trailMs` the `endpoint:` line reports and
     * from the FRAME clock the caller handed in ([speechEndMs]), so the perceived metric needs no
     * accessor of its own on the endpointer and no second read of its state — and no second read of
     * the clock, which would land after the commit's buffer snapshot and bias every number low.
     * Only `cut=vad` is stamped: the other three cut kinds have no speech-end instant, and a stamp
     * with no honest instant behind it would be a number the acceptance sheet could not use.
     *
     * Callable from the CAPTURE thread (the endpoint and cap cuts) and from Main (switchSource,
     * stopRecording, the projection-consent flush) — [SegmentQueueDepth] is synchronized.
     *
     * THE SPEECH EVIDENCE (4.3.2) is the funnel's ONE other read of the endpointer, and it adds
     * nothing to the commit DECISION either: [Endpointer.speechEvidenceMs] is read once, BEFORE
     * the engine's commit — the buffer it describes is the one about to be cut — and travels
     * INTO the commit as a [SpeechEvidence], where `LocalWhisperEngine` alone acts on it (a KNOWN
     * count under `EndpointerTuning.MIN_SPEECH_EVIDENCE_MS` is resolved `EmptyExpected` without an
     * encode; the cloud engines drop it on the interface default). Then, AFTER the commit,
     * [Endpointer.onBufferCommitted] re-bases the count for the next buffer — here and not inside
     * the endpointer's own cut path, because `onFrame` clears its gate before it returns true and
     * this funnel reads the count after that; and here for every cut kind, because the cap,
     * switch and stop sites each commit a buffer the endpointer counted. The cap site passes
     * `tailRetained` so the audio it kept back carries its own evidence into the next segment.
     * The amplitude endpointer answers UNKNOWN and takes the re-base as a no-op: never skipped.
     * Pinned structurally by CommitFunnelPinTest, beside the cut-record read.
     */
    private fun commitSegment(
        engine: TranscriptionEngine,
        cut: String,
        retainMs: Long = 0L,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val evidence = SpeechEvidence.of(endpointer.speechEvidenceMs())
        val seq = if (retainMs > 0L) engine.commitRetainingTailMs(retainMs, evidence) else engine.commit(evidence)
        endpointer.onBufferCommitted(tailRetained = retainMs > 0L)
        // Only a VAD cut has a probe behind it. lastCut() is read IMMEDIATELY after the verdict,
        // because the state machine re-arms as it returns true; an amplitude endpointer is not a
        // SileroEndpointer and yields null, which renders the unknown shape (p=-1.00).
        //
        // The gate is on the CUT KIND and must never be "simplified" to non-nullness (Task C8's
        // read contract): lastCut() holds the LAST vad cut and survives until the next one, so a
        // cap/stop/switch commit asking for it would be handed an OLDER cut's numbers and report
        // them as this segment's. Pinned structurally by CommitFunnelPinTest.
        val ec = if (cut == EndpointDiag.VAD) (endpointer as? SileroEndpointer)?.lastCut() else null
        android.util.Log.i("WE-DIAG", EndpointDiag.endpointLine(seq, cut, ec))
        // The perceived-latency stamp, from the SAME `ec` the line above reports and the SAME
        // `nowMs` the caller measured `trailMs` against. NOT a second read of the endpointer's cut
        // record (Task C8's read contract, pinned at exactly one such read in the whole file) and
        // NOT a second read of the wall clock: the commit above takes a full ~960 KB buffer
        // snapshot under bufferLock, so a fresh clock read here would silently subtract that
        // snapshot's cost from every reported wait — on the exact metric S3 Check 2 and S4's
        // release-notes contingency read. Both no-reread properties are pinned, structurally, by
        // PerceivedStampPinTest.
        if (cut == EndpointDiag.VAD && ec != null) {
            perceivedLatency.onCommitted(seq, speechEndMs(nowMs = nowMs, ec = ec))
        }
        android.util.Log.i("WE-DIAG", EndpointDiag.queueLine(segmentQueueDepth.onCommitted(seq)))
        // 3.7 G: the depth is now in the log AND on screen from one place. The repaint hops to
        // Main because this funnel is also called from the capture thread; it is skipped entirely
        // for a commit that cut nothing, which cannot have changed the depth.
        if (commitAdvancesQueueDepth(seq)) serviceScope.launch(Dispatchers.Main) { renderInFlightStrip() }
        return seq
    }

    /**
     * Paint the in-flight line onto the preview strip (3.7, Workstream G). Main thread only.
     *
     * **Runs for EVERY NON-LIVE session — LOCAL and cloud BATCH alike**, because the guard below is
     * [deltaOwnsPreviewStrip] and `sessionIsLive` is false for CLOUD_WITH_FALLBACK too. A
     * cloud-batch session therefore gains an in-flight line it did not have in 3.6.0. That is
     * deliberate: the backlog is real on that path (the same `commitSegment` funnel counts it), and
     * the label names no provider and claims no speed, so it reads the same either way.
     *
     * Phase ownership, exactly as CONNECTING and FINALIZING already practise it on this same
     * TextView: this paints only while RECORDING, so `connectingStatusLabel`'s "Loading speech
     * model…" and stopRecording's "Finishing transcript…" keep the strip in their own phases. A
     * live session never reaches the body — its deltas own the strip.
     *
     * The reveal is the session's ONE geometry change; from then on the line swaps text or goes
     * INVISIBLE, so nothing re-measures and no reclamp is posted per utterance. That last clause
     * only became true at Task G5: while [deliverReleasedText] still returned the strip to GONE on
     * every release, the next commit read `currentlyHidden` and paid the reveal again, so the cost
     * was one reclamp per UTTERANCE. Delivery now repaints through here instead of hiding, which is
     * what makes "once per session" the measured behaviour rather than the intended one.
     *
     * ONE body, MANY callers: the funnel posts it (it can be called from the capture thread), and
     * the resolve path and delivery call it bare, both already on Main. All three are pinned —
     * `CommitFunnelPinTest` for the posted one, `InFlightStripWiringPinTest` for the bare pair.
     */
    private fun renderInFlightStrip() {
        if (currentState != BubbleState.RECORDING) return
        if (deltaOwnsPreviewStrip(sessionIsLive = sessionIsLive, sessionHasLocalPreview = sessionHasLocalPreview)) return
        val label = inFlightStripLabel(depth = segmentQueueDepth.depth(), sessionHasLocalPreview = sessionHasLocalPreview)
        val wasHidden = transcriptionDeltaText.visibility == View.GONE
        when (inFlightStripVisibility(label = label, currentlyHidden = wasHidden)) {
            StripVisibility.HIDDEN -> return
            StripVisibility.OCCUPYING_BLANK -> {
                transcriptionDeltaText.text = ""
                transcriptionDeltaText.visibility = View.INVISIBLE
            }
            StripVisibility.SHOWING -> {
                transcriptionDeltaText.text = label
                transcriptionDeltaText.visibility = View.VISIBLE
                // Posted ONLY on the reveal — the one time the window actually grew.
                if (wasHidden) bubbleView.post { reclampNow() }
            }
        }
    }

    /**
     * The ONE routing point for text the [segmentOrderer] releases — used by onSegmentResolved and
     * by every flush() site, so text that was held and released late is delivered exactly like an
     * in-order segment (preview sink + history) rather than a subset of that. Main
     * thread only.
     */
    private fun deliverReleasedText(text: String) {
        if (text.isBlank()) return
        sessionProducedText = true
        // The strip carried this utterance while it was in flight (live deltas); its resolved
        // text moves into the accumulating window via the sink below, so reset the strip for
        // the next one — or the finished words linger UNDER the next utterance as it streams
        // in, reading as duplicated text. Held during FINALIZING, where the strip carries the
        // "finishing transcript" status instead. Scroll reset too, so the next turn starts at
        // the top of the panel rather than wherever the last one left it parked.
        val finalizing = currentState == BubbleState.FINALIZING
        if (resolvedTextClearsStrip(sessionIsLive = sessionIsLive, sessionHasLocalPreview = sessionHasLocalPreview, isFinalizing = finalizing)) {
            transcriptionDeltaText.text = ""
            transcriptionDeltaText.scrollTo(0, 0)
            transcriptionDeltaText.visibility = View.GONE
        } else if (!finalizing) {
            // 3.7 G: the strip is carrying the in-flight line, whose truth is the queue depth —
            // repaint it, never hide it. Returning it to GONE here is what made Task G4's reveal
            // cost a reclamp per UTTERANCE rather than one per session: GONE is exactly the state
            // the anti-churn rule reads as "not revealed yet", so every commit paid the reveal
            // again. NOT hiding is this branch's real contribution; the resolve path above has
            // usually already painted the same depth, and a second identical paint is free.
            // This branch is also what covers the four flush() sites that never go through
            // onSegmentResolved at all. And it is where the per-utterance scrollTo(0, 0) goes away
            // — the in-flight line is one short string, and in a NON-LIVE session (local or cloud
            // batch) nothing scrolls this strip any more now that onDelta is gated: the CONNECTING
            // label already reset it to the top and no delta ever reaches it.
            renderInFlightStrip()
        }
        handleTranscriptionResult(text)
    }

    /**
     * Mid-session accumulation — and ONLY accumulation (W2 final-only commit). Every resolved
     * segment lands in exactly two places, for EVERY session context: the bounded-memory sink
     * (whose file is the transcript the final delivery reads) and sessionTranscript (history's
     * source, persisted at finalize). NOTHING leaves the app until stopRecording's
     * deliverFinalTranscript — no injection, no clipboard write, no caret pinning. FINALIZING
     * counts as in-session: drain-released segments and orderer flushes land here too.
     */
    private fun handleTranscriptionResult(text: String) {
        android.util.Log.i("WE-DIAG", "handleResult: session=$sessionContext live=$currentContext len=${text.length}")
        val historyTok = TextJoin.normalize(text)
        if (historyTok.isEmpty()) return
        if (sessionTranscript.isNotEmpty() && TextJoin.needsSpace(sessionTranscript, historyTok)) {
            sessionTranscript.append(' ')
        }
        sessionTranscript.append(historyTok)
        transcriptSink?.append(text)
        if (transcriptSink == null) {
            android.util.Log.i("WE-DIAG", "late segment after final read — kept in history only (${text.length} chars)")
        }
    }

    /**
     * The ONE external write of the session (W2 final-only commit). Called from stopRecording's
     * finalize block BEFORE teardownRealtime — teardown ends the injection-session binding, and
     * the SESSION_BOUND write must resolve the field captured at beginInjectionSession — or
     * best-effort from onDestroy. [finalDelivered] makes once-only hold even if destroy races
     * the finalize coroutine. Main thread.
     */
    private fun deliverFinalTranscript(full: String) {
        if (finalDelivered) return
        finalDelivered = true
        // 4.3.3 (accessibility-optional-spec §4): the service-off read comes FIRST and reaches
        // the policy as its first input, so without the service no injection is attempted and
        // the plan lands in the consolidated-copy arm below — the clipboard write and the
        // "copied" toast the CLIPBOARD_ONLY path already performs. Pinned as source by
        // AccessibilityOptionalDeliveryPinTest: this read precedes both injection sites.
        val serviceEnabled = WhisperAccessibilityService.isEnabled()
        val plan = com.whispereverywhere.transcription.FinalDeliveryPolicy.decide(
            serviceEnabled = serviceEnabled,
            isTextFieldSession = sessionContext == BubbleContext.TEXT_FIELD,
            degradedToClipboard = sessionClipboardFallback,
            hasLiveInputTarget = WhisperAccessibilityService.hasLiveInputTarget(),
            transcriptBlank = full.isEmpty(),
        )
        android.util.Log.i(
            "WE-DIAG",
            "finalDelivery: service=$serviceEnabled inject=${plan.inject} " +
                "copy=${plan.copyWholeToClipboard} len=${full.length}",
        )
        when (plan.inject) {
            com.whispereverywhere.transcription.InjectTarget.SESSION_BOUND -> {
                // Field session: one write through the session-bound target. Document/social
                // apps run their paste strategy exactly once; a dead node falls back to the
                // focused field INSIDE injectTextWithResult. Result handling mirrors the old
                // per-segment handler, adapted to stop-time copy.
                when (WhisperAccessibilityService.injectTextWithResult(full)) {
                    WhisperAccessibilityService.InjectionResult.SUCCESS -> {
                        // Typed where the user aimed it — no toast needed.
                    }
                    WhisperAccessibilityService.InjectionResult.CLIPBOARD_ONLY -> {
                        // The strategy already left the FULL transcript on the clipboard.
                        sessionClipboardFallback = true
                        showToast("Can't type here — full transcript copied to clipboard")
                    }
                    WhisperAccessibilityService.InjectionResult.FAILED -> {
                        sessionClipboardFallback = true
                        runCatching {
                            val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
                        }
                        showToast("Can't type here — full transcript copied to clipboard")
                    }
                }
            }
            com.whispereverywhere.transcription.InjectTarget.FINALIZE_FOCUS -> {
                // Preview session that ends with a live field focused: clipboard once, plus the
                // opportunistic finalize-time inject (targeting the CURRENT focus is BY DESIGN
                // here — covers capture-video-then-tap-into-prompt end to end; clipboard stays
                // set either way). Delivery now runs BEFORE teardown, so the record-start
                // session binding is still alive and would win inside resolveInjectionTarget —
                // end it first (idempotent; teardown's later call no-ops) so this write
                // resolves the field focused NOW, not the one focused when recording began.
                WhisperAccessibilityService.endInjectionSession()
                runCatching {
                    val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
                }
                val injected = WhisperAccessibilityService.injectTextWithResult(full) ==
                    WhisperAccessibilityService.InjectionResult.SUCCESS
                showToast(
                    if (injected) "Transcription inserted — also on your clipboard"
                    else "Transcription copied to clipboard",
                )
            }
            null -> if (plan.copyWholeToClipboard) {
                // Target-less preview session: ONE consolidated copy — genuinely the only
                // clipboard write of the session now. (The TEXT_FIELD wording below is
                // future-proofing: the degraded decide() row can't fire before delivery today,
                // because sessionClipboardFallback is only ever set BY deliverFinalTranscript.)
                runCatching {
                    val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", full))
                }
                showToast(
                    if (sessionContext == BubbleContext.TEXT_FIELD) "Full transcription copied to clipboard"
                    else "Transcription copied to clipboard",
                )
            }
        }
    }

    private fun updateBubbleState(newState: BubbleState) {
        currentState = newState

        serviceScope.launch(Dispatchers.Main) {
            pulseAnimator?.cancel()
            bubbleContainer.scaleX = 1f
            bubbleContainer.scaleY = 1f

            // Recording timer defaults off; the RECORDING branch turns it back on.
            recordingTimerJob?.cancel(); recordingTimerJob = null
            recordingTimerText.visibility = View.GONE
            // Satellite lobes are idle-only; the IDLE branch turns them back on.
            stopClipPulse()
            lockLobe.visibility = View.GONE
            keyboardLobe.visibility = View.GONE
            speakerLobe.visibility = View.GONE

            when (newState) {
                BubbleState.IDLE -> {
                    bubbleIcon.visibility = View.VISIBLE
                    bubbleIcon.setImageResource(
                        if (isSpeakingNow) R.drawable.ic_stop_speech else R.drawable.ic_mic,
                    )
                    // Lock lobe is TRANSIENT (owner 2026-08-01): long-press already pins, so a
                    // permanent lock hanging off the blob was redundant chrome. It flashes for
                    // ~1.5 s as confirmation whenever the pin state changes — see togglePin.
                    lockLobe.visibility = View.GONE
                    // The lobe's ONE action is WhisperAccessibilityService.toggleSummonedKeyboard(),
                    // which returns false immediately when the service is not bound — a dead
                    // control (4.3.3, review N2). The pref alone is not enough to show it; with the
                    // service ON isEnabled() is true and this is the pref alone, as before.
                    keyboardLobe.visibility =
                        if (app.preferencesManager.isDictationFirstKeyboard() &&
                            WhisperAccessibilityService.isEnabled()
                        ) View.VISIBLE
                        else View.GONE
                    speakerLobe.visibility = if (!isSpeakingNow &&
                        com.whispereverywhere.tts.TtsController.isVoiceInstalled(this@FloatingBubbleService)
                    ) View.VISIBLE else View.GONE
                    waveformView.visibility = View.GONE
                    waveformView.stop()
                    setBubbleWidth(56)
                    blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_background)
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.IDLE)
                    processingRing.visibility = View.GONE
                    processingRing.clearAnimation()
                    processingTimeText.visibility = View.GONE
                    stopProcessingTimer()
                    if (shouldHideOnIdle) {
                        shouldHideOnIdle = false
                        if (mediaDetector.isCurrentlyPlaying()) {
                            currentContext = BubbleContext.MEDIA_PLAYBACK; showBubbleForMedia()
                        } else {
                            currentContext = BubbleContext.NONE; hideBubble("idle-after-session")
                        }
                    }
                    // 4.3.1 B: a hide that arrived mid-read replays now — only where the bubble
                    // would have hidden anyway (never off a focused field, never in always-on).
                    deferredHideReason?.let { parked ->
                        if (!isSpeakingNow) {
                            deferredHideReason = null
                            // MEDIA_PLAYBACK parks a hide too; a bubble that media summoned
                            // mid-read is media's, not the read's.
                            if (currentContext == BubbleContext.NONE && BubbleHidePolicy.replay(
                                    contextIsTextField = currentContext == BubbleContext.TEXT_FIELD,
                                    alwaysOn = alwaysOnMode(),
                                )
                            ) {
                                currentContext = BubbleContext.NONE
                                hideBubble("deferred:$parked")
                            }
                        }
                    }
                }
                BubbleState.CONNECTING -> {
                    bubbleIcon.visibility = View.GONE
                    waveformView.visibility = View.GONE
                    processingRing.visibility = View.VISIBLE
                    blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_processing)
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.PROCESSING)
                    startRotationAnimation()
                }
                BubbleState.RECORDING -> {
                    bubbleIcon.visibility = View.GONE
                    processingRing.visibility = View.GONE
                    processingRing.clearAnimation()
                    setBubbleWidth(160)
                    waveformView.visibility = View.VISIBLE
                    waveformView.start()
                    // Deep black pill per the design reference — the aurora waves carry all the
                    // color; the red recording accent lives in the timer dot.
                    blobView.fillColor = android.graphics.Color.parseColor("#000000")
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.RECORDING)
                    // Live recording timer: red dot + mm:ss, bottom-left in the pill.
                    recordingTimerText.visibility = View.VISIBLE
                    recordingTimerJob = serviceScope.launch(Dispatchers.Main) {
                        val startMs = System.currentTimeMillis()
                        while (true) {
                            val s = ((System.currentTimeMillis() - startMs) / 1000).toInt()
                            val label = android.text.SpannableString(
                                String.format("● %02d:%02d", s / 60, s % 60)
                            )
                            label.setSpan(
                                android.text.style.ForegroundColorSpan(0xFFFF3B30.toInt()),
                                0, 1, 0
                            )
                            recordingTimerText.text = label
                            delay(1000)
                        }
                    }
                    // NO container pulse: the old x1.15 scale loop inflated the ribbon past the
                    // blob's rim (the "ribbon won't stay inside" bug). The blob's voice-driven
                    // swell IS the recording pulse now. Reset any leftover scale defensively.
                    pulseAnimator?.cancel(); pulseAnimator = null
                    bubbleContainer.scaleX = 1f
                    bubbleContainer.scaleY = 1f
                }
                BubbleState.FINALIZING -> {
                    pulseAnimator?.cancel()
                    waveformView.stop()
                    waveformView.visibility = View.GONE
                    setBubbleWidth(56)
                    processingRing.visibility = View.VISIBLE
                    blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_processing)
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.PROCESSING)
                    startRotationAnimation()
                    // 3.6.0 (Workstream E4): the previously-dead elapsed ticker now counts the
                    // drain up next to the "Finishing…" status line — a long drain visibly makes
                    // progress. Both exits (IDLE, ERROR) hide the text and cancel the job.
                    // The mic glyph goes with it, mirroring the PROCESSING branch: the pill is
                    // 56 dp wide here, so the elapsed text would otherwise render over the icon.
                    bubbleIcon.visibility = View.GONE
                    processingTimeText.visibility = View.VISIBLE
                    startProcessingTimer()
                }
                BubbleState.PROCESSING -> {
                    bubbleIcon.visibility = View.GONE
                    waveformView.visibility = View.GONE
                    waveformView.stop()
                    blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.bubble_processing)
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.PROCESSING)
                    processingRing.visibility = View.VISIBLE
                    processingTimeText.visibility = View.VISIBLE
                    startRotationAnimation()
                    startProcessingTimer()
                }
                BubbleState.ERROR -> {
                    bubbleIcon.visibility = View.VISIBLE
                    bubbleIcon.setImageResource(R.drawable.ic_error)
                    waveformView.visibility = View.GONE
                    waveformView.stop()
                    blobView.fillColor = androidx.core.content.ContextCompat.getColor(this@FloatingBubbleService, R.color.error)
                    blobView.setMode(com.whispereverywhere.ui.components.BlobView.Mode.ERROR)
                    processingRing.visibility = View.GONE
                    processingRing.clearAnimation()
                    processingTimeText.visibility = View.GONE
                    stopProcessingTimer()

                    serviceScope.launch {
                        delay(2000)
                        if (currentState == BubbleState.ERROR) {
                            updateBubbleState(BubbleState.IDLE)
                        }
                    }
                }
            }
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val scale = animation.animatedValue as Float
                bubbleContainer.scaleX = scale
                bubbleContainer.scaleY = scale
            }
            start()
        }
    }

    private fun startRotationAnimation() {
        val rotateAnimation = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        rotateAnimation.duration = 1000
        rotateAnimation.repeatCount = Animation.INFINITE
        rotateAnimation.interpolator = LinearInterpolator()
        processingRing.startAnimation(rotateAnimation)
    }

    private var processingStartTime: Long = 0L

    private fun startProcessingTimer() {
        processingStartTime = System.currentTimeMillis()
        processingTimeText.text = "0s"

        processingTimerJob?.cancel()
        processingTimerJob = serviceScope.launch {
            while (isActive && processingTimerRunsIn(currentState)) {
                val elapsed = (System.currentTimeMillis() - processingStartTime) / 1000
                processingTimeText.text = "${elapsed}s"
                delay(100) // Update every 100ms for smooth display
            }
        }
    }

    private fun stopProcessingTimer() {
        processingTimerJob?.cancel()
        processingTimerJob = null
    }

    /**
     * THE "LISTENING" CUE. Fired from `onOpen`'s Main body since 4.4.0 S2 — at true readiness,
     * beside the bubble's own RECORDING flip, which was always there. Unchanged pattern, so a
     * warm session feels exactly as it did; a cold one now buzzes a few seconds later, and every
     * word spoken in that gap still arrives (the startup ring).
     */
    private fun vibrateStart() {
        if (app.preferencesManager.isVibrationEnabled()) {
            vibrate(longArrayOf(0, 50))
        }
    }

    /**
     * THE TAP ACKNOWLEDGEMENT (4.4.0 S2, the amendment's haptic ruling: "the tap gets its own
     * distinct short acknowledgement if the house style wants one").
     *
     * Deliberately SHORTER than [vibrateStart] — 20 ms against 50 — because the two cues now say
     * two different things and the user has to be able to tell them apart by feel alone: this one
     * is "got it, you are being recorded", [vibrateStart] is "the engine has your words". Without
     * it the tap would feel dead for the whole cold model load, which is the one thing Option B
     * of the investigation was criticised for on its own (§6, "makes the app feel slower by
     * exactly the connect time").
     */
    private fun vibrateTap() {
        if (app.preferencesManager.isVibrationEnabled()) {
            vibrate(longArrayOf(0, 20))
        }
    }

    private fun vibrateStop() {
        if (app.preferencesManager.isVibrationEnabled()) {
            vibrate(longArrayOf(0, 30, 50, 30))
        }
    }

    private fun vibrateSuccess() {
        if (app.preferencesManager.isVibrationEnabled()) {
            vibrate(longArrayOf(0, 50, 50, 100))
        }
    }

    private fun vibrateError() {
        if (app.preferencesManager.isVibrationEnabled()) {
            vibrate(longArrayOf(0, 100, 50, 100, 50, 100))
        }
    }

    private fun vibrate(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun showToast(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, WhisperEverywhereApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_close, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    enum class BubbleState {
        IDLE, CONNECTING, RECORDING, FINALIZING, PROCESSING, ERROR
    }

    enum class BubbleContext {
        NONE,           // No specific context
        TEXT_FIELD,     // User is focused on a text field
        MEDIA_PLAYBACK  // Media is playing
    }

    companion object {
        const val ACTION_STOP = "com.whispereverywhere.STOP_BUBBLE"

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.stopService(intent)
        }
    }
}
