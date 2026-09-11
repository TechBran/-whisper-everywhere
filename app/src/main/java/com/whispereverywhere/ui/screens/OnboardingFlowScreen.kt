package com.whispereverywhere.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.model.ModelInstallSignal
import com.whispereverywhere.model.ModelTierCopy
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.model.WhisperModel
import com.whispereverywhere.npu.NpuPackController
import com.whispereverywhere.npu.NpuPackFetch
import com.whispereverywhere.service.MediaNotificationListener
import com.whispereverywhere.service.WhisperAccessibilityService
import com.whispereverywhere.tts.TtsPackController
import com.whispereverywhere.ui.onboarding.AccessibilityAvailabilityProbe
import com.whispereverywhere.ui.onboarding.OnboardingLogic
import com.whispereverywhere.ui.onboarding.OnboardingLogic.Step
import com.whispereverywhere.ui.onboarding.OnboardingSetupViewModel
import com.whispereverywhere.ui.onboarding.OnboardingSetupViewModel.EngineState
import com.whispereverywhere.ui.theme.Primary
import com.whispereverywhere.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Guided first-run onboarding (owner decision 2026-08-01): everything the app needs, configured
// in one pass on first startup. Four steps — permissions (all four, granted in place), language
// (4.2 F6, the 3.8 owner ruling: the pick lands BEFORE the model step, device-locale-first with
// auto one tap away and honestly subtitled, and Continue writes the EXISTING selected_language
// pref — no new storage), engines (3.5.0: the user PICKS a speech tier from honest cards — no
// preselection — and that single confirmed pick starts BOTH downloads, chosen tier + read-aloud
// voice, with no further button presses; since 4.2 F6 a capable device's lineup is offered UNION
// fetchable, and a gated pick fetches from Google Play inside the flow with Play's own consent
// dialog), and the cloud-keys teaching step. Replaces the two-path chooser: the chooser made
// setup a fork; this makes it a walk, and the cloud fork is simply the last step.
//
// EXISTING INSTALLS DO NOT RE-ENTER THIS FLOW BY UPGRADING: the launch gate is
// firstRunStartDestination's hasModel rule (ModeDashboard — a model on disk routes to Home;
// onboardingCompleted is deliberately NOT consulted there), and F6 leaves it untouched. A
// MODELLESS install (Auto-Backup restore, model deleted in Settings) re-enters by design —
// pre-4.2, owner-mandated — and now also meets the language step, which is safe: the forced
// tap writes the same selected_language pref that install's Settings picker edits.
//
// MANDATORY except the cloud step (owner decision 2026-08-18, reversing the earlier never-block
// contract): Continue on the permissions step is gated on the two permissions the bubble needs
// to EXIST — mic and overlay; since 4.3.3 the accessibility service is RECOMMENDED, not required
// (accessibility-optional-spec: typing degrades to a clipboard copy, and a device whose policy
// forbids third-party accessibility services must still finish setup) — the engines step
// releases only once the speech model is Ready (a failed download shows Retry and holds), and
// "Skip setup" exists ONLY on the cloud step — cloud is the one genuinely optional part. Back
// walks backwards; on the first step it leaves the activity WITHOUT recording completion, so
// onboarding returns on next launch. No speed claims anywhere.
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingFlowScreen(
    onFinish: () -> Unit,
    onCloudSetup: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ACTIVITY-scoped on purpose: the ~350 MB voice install must survive both step changes and
    // the navigation to the cloud-keys screen. See OnboardingSetupViewModel's class doc.
    val setupVm: OnboardingSetupViewModel =
        viewModel(viewModelStoreOwner = context as ComponentActivity)

    var step by remember { mutableStateOf(Step.PERMISSIONS) }

    // The chooser's transient pick (3.5.0). Deliberately NOT persisted until the confirm tap:
    // prefs.selectedModelId is written the moment Download is pressed, never before.
    var pickedTierId by remember { mutableStateOf<String?>(null) }

    // The language step's transient pick (4.2 F6). Same discipline as the tier pick: nothing is
    // preselected and nothing persists until Continue — setSelectedLanguage is written then.
    var pickedLanguage by remember { mutableStateOf<String?>(null) }

    // 4.3 — THE NO-WEDGE ESCAPE'S OTHER HALF. 4.3 narrows a capable device's chooser to one card;
    // a sideloaded capable device is offered that card and Play then refuses to deliver it, and
    // the F6 escape sends the user back to a chooser that would hold exactly one undeliverable
    // tier — a MANDATORY step with no completable path. This latch says "the one tier's delivery
    // already failed here", which suspends the narrowing (OnboardingLogic.chooserAlsoOfferedIds).
    //
    // DURABLE, and it has to be: `resetSpeechForReChoice()` returns the engine state to Pending on
    // the way back, so by the time the chooser renders the failure is over — only the reason the
    // user is standing here is still true. It is never cleared: a delivery that failed once has
    // told us something about this install that a later Retry succeeding does not un-tell, and the
    // cost of remembering is two extra cards on a chooser the user has already been sent back to.
    var oneTierDeliveryFailed by remember { mutableStateOf(false) }

    // Read ONCE at flow level: the language step's row order and the engines step's steer must
    // answer from the same tag (and the one-read pin in ChooserSteerWiringPinTest stays true).
    val languageTag = java.util.Locale.getDefault().toLanguageTag()

    // 4.4.0: does the English row get the live-words chip? Read ONCE, beside the tag above and
    // for the same reason — `isInstalled` is a marker plus four File.length() calls, and the
    // language step recomposes on every tap.
    val livePackInstalled = remember {
        (context.applicationContext as WhisperEverywhereApp).streamingPackManager
            .isInstalled(com.whispereverywhere.transcription.stream.StreamingPackCatalog.EN)
    }

    // Permission state lives at flow level (3.5.x): the pinned footer gates Continue on the
    // bubble's two required permissions (mic, overlay — 4.3.3 made accessibility a
    // recommendation), so the step and the footer read the same truth. Re-checked on every
    // ON_RESUME because overlay, accessibility, and notification access are granted in system
    // Settings and the user bounces there and back per row.
    var mic by remember { mutableStateOf(hasMic(context)) }
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var accessibility by remember { mutableStateOf(WhisperAccessibilityService.isEnabled()) }
    var notifListener by remember { mutableStateOf(MediaNotificationListener.isEnabled()) }

    // 4.3.3: the BOUNCE half of the restricted-settings suspicion — the user opened the
    // accessibility screen from this step's Enable and came back with the service still off.
    // (The other half, the install-source read, is the probe's: a Play install can never read
    // RESTRICTED, however it bounced — fix round 1, B1.) `opened` is set by the tap; `returned`
    // by the next ON_RESUME after it (the refresh below). Neither is ever cleared: the guidance
    // stays until the service is actually on, and once it is on the card renders a check and no
    // note at all, so a stale flag can never show a stale sentence.
    var accessibilitySettingsOpened by remember { mutableStateOf(false) }
    var returnedFromAccessibilitySettings by remember { mutableStateOf(false) }

    fun refreshPermissions() {
        mic = hasMic(context)
        overlay = Settings.canDrawOverlays(context)
        accessibility = WhisperAccessibilityService.isEnabled()
        notifListener = MediaNotificationListener.isEnabled()
        if (accessibilitySettingsOpened) returnedFromAccessibilitySettings = true
    }

    // What THIS device can do about the service (brief §2): the pure rule, fed by the one
    // adapter. Keyed on the two inputs that move; the device reads behind it are cheap.
    val accessibilityAvailability = remember(accessibility, returnedFromAccessibilitySettings) {
        AccessibilityAvailabilityProbe.classify(
            context,
            returnedFromSettings = returnedFromAccessibilitySettings,
            serviceEnabled = accessibility,
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler {
        step = OnboardingLogic.previous(step) ?: run {
            // First step: leave the app WITHOUT recording completion — onboarding is mandatory
            // (owner decision 2026-08-18) and returns on next launch.
            (context as ComponentActivity).finish()
            return@BackHandler
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (step) {
                            Step.PERMISSIONS -> "Welcome — permissions"
                            Step.LANGUAGE -> "What language will you speak?"
                            Step.ENGINES -> "Setting up your engines"
                            Step.CLOUD -> "Cloud providers (optional)"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when (step) {
                    Step.PERMISSIONS -> PermissionsStep(
                        mic = mic,
                        overlay = overlay,
                        accessibility = accessibility,
                        notifListener = notifListener,
                        onMicGranted = { mic = it },
                        // 4.3.3: the card's note is the platform-aware sentence (brief §2), and
                        // its Enable tap arms the returned-from-Settings signal above.
                        accessibilityNote = OnboardingLogic.accessibilityNote(accessibilityAvailability),
                        onAccessibilitySettingsOpened = { accessibilitySettingsOpened = true },
                        // 4.3.3: the accessibility card's plain secondary action is the SAME
                        // advance as the footer's Continue, behind the SAME two-permission gate
                        // — one rule, two places to tap it, and neither can outrun the other.
                        continueWithoutEnabled = OnboardingLogic.permissionsContinueEnabled(mic, overlay),
                        onContinueWithout = { OnboardingLogic.next(step)?.let { next -> step = next } },
                    )
                    Step.LANGUAGE -> LanguageStep(
                        languageTag = languageTag,
                        picked = pickedLanguage,
                        onPick = { pickedLanguage = it },
                        livePackInstalled = livePackInstalled,
                    )
                    Step.ENGINES -> EnginesStep(
                        vm = setupVm,
                        languageTag = languageTag,
                        pickedTierId = pickedTierId,
                        oneTierDeliveryFailed = oneTierDeliveryFailed,
                        // 4.3 fix round (I-3): nullable, because the step must be able to DROP a
                        // pick whose card the narrowing removed under it.
                        onPick = { pickedTierId = it },
                        // 4.3 fix round (I-2): the step hands up whether THIS failure was a
                        // delivery failure — it is the only caller that can see the reason and
                        // the tier together. The latch is OR-ed, never overwritten: one genuine
                        // undeliverable answer stands however many cancels follow it.
                        onChooseAgain = { deliveryFailed ->
                            if (deliveryFailed) oneTierDeliveryFailed = true
                            pickedTierId = null
                            setupVm.resetSpeechForReChoice()
                        },
                    )
                    Step.CLOUD -> CloudStep(onCloudSetup = onCloudSetup, onFinish = onFinish)
                }
            }

            // Pinned footer: primary action; Skip exists only on the CLOUD step (its own two
            // full-size choices + skip).
            Spacer(Modifier.height(12.dp))
            if (step != Step.CLOUD) {
                val speech by setupVm.speechState.collectAsState()
                val voice by setupVm.voiceState.collectAsState()
                if (step == Step.PERMISSIONS) {
                    // 4.3.3: the count and the gate read mic + overlay ONLY — the accessibility
                    // service is recommended, and the card above carries its own way past.
                    val missing = OnboardingLogic.missingBubblePermissions(mic, overlay)
                    OnboardingLogic.permissionsContinueHint(missing)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = { OnboardingLogic.next(step)?.let { next -> step = next } },
                        enabled = OnboardingLogic.permissionsContinueEnabled(mic, overlay),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Continue")
                    }
                } else if (step == Step.LANGUAGE) {
                    // LANGUAGE (4.2 F6): Continue is locked until a row is picked — the 3.8
                    // mandate is a forced choice — and the tap writes the EXISTING
                    // selected_language store (the same pref Settings' picker edits; nothing
                    // new is stored anywhere) before advancing.
                    Button(
                        onClick = {
                            pickedLanguage?.let { picked ->
                                WhisperEverywhereApp.getInstance()
                                    .preferencesManager.setSelectedLanguage(picked)
                                OnboardingLogic.next(step)?.let { next -> step = next }
                            }
                        },
                        enabled = OnboardingLogic.languageContinueEnabled(pickedLanguage),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Continue")
                    }
                } else {
                    // ENGINES: one primary action — "Download" (gated on a pick) until downloads
                    // begin, then mandatory-model gating (owner decision 2026-08-18): Continue
                    // only once the speech model is Ready — Failed shows Retry and holds.
                    val action = OnboardingLogic.enginesPrimaryAction(
                        downloadsBegun = speech !is EngineState.Pending,
                        tierPicked = pickedTierId != null,
                        speechReady = speech is EngineState.Ready,
                    )
                    if (!action.startsDownloads) {
                        OnboardingLogic.enginesContinueHint(
                            speechReady = speech is EngineState.Ready,
                            voiceReady = voice is EngineState.Ready,
                        )?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    Button(
                        onClick = {
                            if (action.startsDownloads) {
                                pickedTierId?.let { picked ->
                                    // Contract: the pick is persisted BEFORE beginAutoSetup so
                                    // ensureSpeech resolves it as the one source of truth.
                                    WhisperEverywhereApp.getInstance()
                                        .preferencesManager.selectedModelId = picked
                                    setupVm.beginAutoSetup()
                                }
                            } else {
                                OnboardingLogic.next(step)?.let { step = it }
                            }
                        },
                        enabled = action.enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(action.label)
                    }
                }
            }
            if (step == Step.CLOUD) {
                TextButton(
                    onClick = onFinish,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Skip setup")
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- permissions

/**
 * Four grantable permission rows plus one informational row, with live state and an in-place
 * grant action each on the grantable four — the same set, same checks, and same intents as
 * Settings' Permissions section, so what the user grants here is exactly what Settings later
 * reports. Live state is hoisted to the flow (3.5.x): the pinned footer gates Continue on it, so
 * the step and the footer read the same truth.
 *
 * 4.3.3: the accessibility row is RECOMMENDED, not required ([AccessibilityRow]). It says what
 * the service buys and what happens without it, keeps Enable as the primary action, and offers
 * `Continue without it` as a plain secondary one — [onContinueWithout], gated by
 * [continueWithoutEnabled], which the flow binds to the footer's own two-permission rule.
 */
@Composable
private fun PermissionsStep(
    mic: Boolean,
    overlay: Boolean,
    accessibility: Boolean,
    notifListener: Boolean,
    onMicGranted: (Boolean) -> Unit,
    accessibilityNote: String,
    onAccessibilitySettingsOpened: () -> Unit,
    continueWithoutEnabled: Boolean,
    onContinueWithout: () -> Unit,
) {
    val context = LocalContext.current

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onMicGranted(granted) }

    Text(
        "Whisper Everywhere types wherever you are, so it needs a few permissions up front. " +
            "Grant each one here — you'll come straight back.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    PermissionRow(
        title = "Microphone",
        why = "Hears you dictate",
        granted = mic,
        onGrant = { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
    )
    PermissionRow(
        title = "Display over other apps",
        why = "Shows the floating bubble",
        granted = overlay,
        onGrant = {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        },
    )
    AccessibilityRow(
        granted = accessibility,
        note = accessibilityNote,
        continueWithoutEnabled = continueWithoutEnabled,
        onEnable = {
            onAccessibilitySettingsOpened()
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        onContinueWithout = onContinueWithout,
    )
    PermissionRow(
        title = "Notification access",
        why = "Detects when media is playing, for video transcription",
        granted = notifListener,
        onGrant = {
            context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        },
    )
    PermissionInfoRow(
        title = "Device audio",
        why = "For media transcription — Android asks for this the first time you transcribe " +
            "playing media. It can't be granted in advance.",
    )
}

@Composable
private fun PermissionRow(
    title: String,
    why: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Granted", tint = Primary)
            } else {
                OutlinedButton(onClick = onGrant) { Text("Grant") }
            }
        }
    }
}

/**
 * The accessibility row (4.3.3, accessibility-optional-spec §1): RECOMMENDED, not required. The
 * [PermissionRow] visual family with three additions — the "Recommended" chip, a [note] line
 * saying what happens without the service (platform-aware: the flow passes the copy for what
 * THIS device can do), and `Continue without it` as a plain secondary action under it. Enable
 * stays the primary action and keeps its outlined button; the without-it path is a text button,
 * enabled on exactly the footer's gate, so neither path is a dark pattern for the other. Once the
 * service is on, the row reads like every granted row: a check, and nothing to decide.
 */
@Composable
private fun AccessibilityRow(
    granted: Boolean,
    note: String,
    continueWithoutEnabled: Boolean,
    onEnable: () -> Unit,
    onContinueWithout: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Accessibility service",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!granted) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = Primary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    OnboardingLogic.ACCESSIBILITY_RECOMMENDED_BADGE,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Text(
                        OnboardingLogic.ACCESSIBILITY_WHY,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (granted) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Enabled", tint = Primary)
                } else {
                    OutlinedButton(onClick = onEnable) { Text("Enable") }
                }
            }
            if (!granted) {
                Spacer(Modifier.height(8.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onContinueWithout,
                    enabled = continueWithoutEnabled,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(OnboardingLogic.CONTINUE_WITHOUT_ACCESSIBILITY)
                }
            }
        }
    }
}

@Composable
private fun PermissionInfoRow(title: String, why: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun hasMic(context: android.content.Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

// ------------------------------------------------------------------------------- language

/**
 * The language step (4.2 F6 — the 3.8 owner ruling folds in: language BEFORE model download).
 * The rows come from [OnboardingLogic.languageRows]: the device's language first and badged when
 * the 54-language list carries it, auto one tap away with what it does honestly subtitled — the
 * ruled text, verbatim (the owner's 2026-09-03 re-rule: true for the AI chip model, and scoped to
 * it). No preselection, the model pick's own discipline: the badge suggests,
 * the user still taps, and the footer's Continue stays locked until they do.
 */
@Composable
private fun LanguageStep(
    languageTag: String,
    picked: String?,
    onPick: (String) -> Unit,
    livePackInstalled: Boolean,
) {
    Text(
        OnboardingLogic.LANGUAGE_HINT,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    // (4.4.1) What live words cost on Auto, and which language has them today — said HERE, where
    // the language is picked and before any row is offered, because a caveat read after the tap
    // is a caveat that changed nothing. (Until 4.4.1 this sentence said the previewer was
    // English-only; the per-language ruling made it about the pick instead.)
    Text(
        com.whispereverywhere.transcription.stream.StreamingPackCopy.LANGUAGE_STEP_SENTENCE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    // (4.5.0 Task 3c) THE SAME PROGRESS STRIP THE IN-APP PICKER SHOWS, above the rows that are
    // this step's selector — the ruling names the PLACEMENT, not the screen, and one component at
    // both sites is one fewer chance for a surface to fall behind the observable. It is silent by
    // construction here today: the ENGINES step comes after this one, so no on-device tier exists
    // while these rows are on screen and `PreviewAutoFetch.decide` refuses without one. The pick
    // made here is recorded by the selection's one writer and honoured on Home, where the tier
    // is. See the component's own KDoc.
    com.whispereverywhere.ui.components.LivePreviewSelectorStrip()
    val deviceCode = OnboardingLogic.deviceLanguageCode(languageTag)
    OnboardingLogic.languageRows(languageTag).forEach { (code, displayName) ->
        LanguageRow(
            title = displayName,
            subtitle = when {
                code == "auto" -> OnboardingLogic.AUTO_LANGUAGE_SUBTITLE
                // Only where the model is actually on the device: the chip claims an INSTALLED
                // model, and offering it without one is a promise the first session would break.
                code == "en" && livePackInstalled ->
                    com.whispereverywhere.transcription.stream.StreamingPackCopy.LANGUAGE_CHIP
                else -> null
            },
            badged = code == deviceCode,
            selected = picked == code,
            onClick = { onPick(code) },
        )
    }
}

/** One selectable language card — the [PermissionRow] visual family, selectable like a tier card. */
@Composable
private fun LanguageRow(
    title: String,
    subtitle: String?,
    badged: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) Primary else MaterialTheme.colorScheme.outline,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 1.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (badged) {
                Spacer(Modifier.width(12.dp))
                Surface(
                    color = Primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        OnboardingLogic.DEVICE_LANGUAGE_BADGE,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (selected) {
                Spacer(Modifier.width(12.dp))
                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = Primary)
            }
        }
    }
}

// ------------------------------------------------------------------------------- engines

/**
 * The model-choice step (3.5.0): four tier cards from [ModelTierCopy], no preselection, one pick.
 * Until downloads begin it renders the chooser; from the first beginAutoSetup() the SAME step
 * renders the two progress rows and nothing further needs pressing. Re-entering the step after
 * the confirm shows progress, never the chooser again — the activity-scoped VM's speechState
 * (Pending = not yet begun) is the phase truth.
 */
@Composable
private fun EnginesStep(
    vm: OnboardingSetupViewModel,
    languageTag: String,
    pickedTierId: String?,
    oneTierDeliveryFailed: Boolean,
    onPick: (String?) -> Unit,
    onChooseAgain: (deliveryFailed: Boolean) -> Unit,
) {
    val speech by vm.speechState.collectAsState()
    val voice by vm.voiceState.collectAsState()
    // (4.4.0, Task 2b fix round 1, B2) The voice's SOURCE-and-size clause, from the one pure
    // route-keyed table every voice surface reads: "350 MB fetched from Google Play" on a Play
    // build, where the archive comes from our own on-demand tts_kokoro pack and not from a third
    // party — it does not claim to be "included with the app" there, because an on-demand pack
    // is not in the install until Play delivers it (Task 6, B1). Both phases of this step used
    // to say "about 365 MB" and "downloads", wrong on both halves. Held
    // for the step in a remember — it reads Play's delivery state and the disk — and the answer
    // does not depend on the tier pick, so the choose phase may quote it too.
    val voiceClause = remember { vm.voiceSourceClause() }

    if (speech is EngineState.Pending) {
        // ---- choose phase: nothing downloads until the user has made an informed pick.
        Text(
            "Pick your speech model — dictation runs on your phone, and audio never has to " +
                "leave it. The read-aloud voice ($voiceClause) arrives alongside whichever " +
                "model you choose.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        // 3.7 Workstream H: the steered tier first — English locale -> pro, everything else ->
        // multi. A steer, not a lock: both cards stay tappable and TIER_SWITCH_HINT below still
        // promises the switch.
        //
        // 4.0/4.1: on a device that passes the NPU gate AND already holds a gated tier's own
        // context binaries, that tier joins the lineup — the answer is a SET of tier ids because
        // two gated tiers can be independently installed. Since L9 (the owner's measured pick)
        // `npu-turbo` heads the steer wherever it is offered, `npu` rides second; with turbo
        // absent, the non-English steer becomes `npu` where offered — exactly the pre-pick
        // order. The probe dlopens two QNN libraries and QnnAsrNative forbids Main for every
        // entry point, so the answer is produced OFF Main. Empty until it arrives.
        // `pickedTierId` still starts null: the steer moves a card to the top and badges it,
        // and the user still has to tap it.
        //
        // 4.2 F6: the set is offered UNION fetchable — a DISPLAY/steer set, and the union is
        // the ONLY change here. On a capable fresh Play install the offered half is empty and
        // the fetchable half names both gated tiers, so L9's ordering (unchanged in body) puts
        // turbo at the head wearing the steer badge — "turbo recommended", ridden entirely on
        // the existing rules. Routing never reads the union: everything that routes a session
        // keeps reading offeredNpuTierIds alone, because a fetchable tier has nothing on disk
        // to run.
        //
        // KEYED on the install generation, for the reason spelled out at the Settings picker's
        // copy of this block: an unkeyed produceState samples once per composition entry, so an
        // import landing while the chooser is on screen would never reach the lineup.
        val installGeneration by ModelInstallSignal.generation.collectAsState()
        val npuTierIds by produceState(initialValue = emptySet<String>(), key1 = installGeneration) {
            value = withContext(Dispatchers.IO) {
                val app = WhisperEverywhereApp.getInstance()
                app.offeredNpuTierIds() + app.fetchableNpuTierIds()
            }
        }
        // 4.3: what is already ON DISK, so a capable device whose chooser is now one card long
        // still shows a model the user already downloaded (the non-disturbance rule — deleting a
        // gigabyte someone paid bandwidth for is not ours to do). Same producer shape and same
        // key as the gate above: off Main because `isInstalled` stats one or two files per tier,
        // keyed on the install generation so a landing pack reaches the lineup without leaving
        // the screen. On the fresh install this step exists for it is empty, which is exactly
        // how a capable device reaches "one model card, no comparison".
        val installedIds by produceState(initialValue = emptySet<String>(), key1 = installGeneration) {
            value = withContext(Dispatchers.IO) {
                val app = WhisperEverywhereApp.getInstance()
                WhisperCatalog.entries.filter { app.whisperModelManager.isInstalled(it) }
                    .map { it.id }.toSet()
            }
        }
        // 4.3: what joins the one-card lineup anyway — what is on disk, plus the CPU tiers once
        // the one tier's delivery has failed here. The pure rule owns the decision; this surface
        // owns only the two facts it is made of. Without the second producer a sideloaded capable
        // device wedges the mandatory step behind one card Play will not deliver (F6 I-1).
        val alsoOfferedIds =
            OnboardingLogic.chooserAlsoOfferedIds(installedIds, oneTierDeliveryFailed)
        val steerId = ModelTierCopy.steerIdForLanguageTagFor(languageTag, npuTierIds)
        val lineup = ModelTierCopy.orderedForLanguageTagFor(languageTag, npuTierIds, alsoOfferedIds)
        // 4.3 fix round (I-3): THE LINEUP CAN SHRINK UNDER A PICK. Both producers above are
        // async — the gate's first read dlopens ~7.9 MiB of QNN — so a capable device renders
        // [pro, multi] for that window and then narrows to [npu-turbo]. A tap inside the window
        // used to survive the narrowing and Download then wrote a CPU tier on a capable device,
        // with no card on screen for it. Keyed on the lineup, so it re-runs exactly when the
        // list moves; the rule is pure and drops the pick rather than choosing a new one.
        LaunchedEffect(lineup) {
            val kept = OnboardingLogic.revalidatePick(pickedTierId, lineup)
            if (kept != pickedTierId) onPick(kept)
        }
        lineup
            .mapNotNull { WhisperCatalog.byId(it) }
            .forEach { model ->
                TierChoiceCard(
                    model = model,
                    copy = ModelTierCopy.forId(model.id),
                    steered = model.id == steerId,
                    selected = pickedTierId == model.id,
                    onClick = { onPick(model.id) },
                )
                Spacer(Modifier.height(12.dp))
            }
        Text(
            OnboardingLogic.TIER_SWITCH_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        // ---- download phase: entered by the one confirmed pick; nothing further to press.
        val chosen = WhisperCatalog.byId(
            WhisperEverywhereApp.getInstance().preferencesManager.selectedModelId
        ) ?: WhisperCatalog.byId(WhisperCatalog.DEFAULT_MODEL_ID)!!
        if (chosen.gated) {
            // 4.2 F6: Play's own confirmation dialog — wifi-wait and the >200 MB cellular
            // consent both — shown ONCE PER ENTRY into NeedsConfirmation. The LaunchedEffect
            // key is the state VALUE: entering the state changes the key and fires the dialog
            // once; staying in it re-fires nothing; leaving and re-entering fires again.
            // Deliberately NO custom re-ask anywhere: the consent is Play's to word and to
            // size (the controller's own contract), and the engine row meanwhile reads the
            // mapped "Waiting for your OK in the Google Play dialog" label.
            val context = LocalContext.current
            val fetch by NpuPackController.state.collectAsState()
            LaunchedEffect(fetch) {
                if (fetch is NpuPackFetch.FetchState.NeedsConfirmation) {
                    NpuPackController.confirm(context as ComponentActivity)
                }
            }
        }
        // (4.4.0, Task 2b fix round 1, B2) The VOICE's pack fetch needs Play's dialog for the
        // same reason and on the same once-per-ENTRY key rule — 350 MB is over the cellular
        // consent threshold, and a wifi-wait raises it too. Without this the voice card would
        // sit on "Waiting for your OK in the Google Play dialog" with no dialog to answer, and
        // the Working guard refuses the Retry that might have cleared it.
        //
        // Deliberately the gated block's shape rather than a shared raise site: Play's
        // confirmation is ONE dialog for every pack it is holding, so if a gated tier is waiting
        // on it too, whichever of the two effects raises it first covers both — and a raise
        // while that dialog is up is refused by Play (showConfirmationDialog returns false),
        // after which this effect re-fires on the voice's next state.
        val voiceContext = LocalContext.current
        val voiceFetch by TtsPackController.state.collectAsState()
        LaunchedEffect(voiceFetch) {
            if (voiceFetch is NpuPackFetch.FetchState.NeedsConfirmation) {
                TtsPackController.confirm(voiceContext as ComponentActivity)
            }
        }
        Text(
            "Downloading your engines — nothing to press. Both stay on your phone; audio " +
                "never has to leave it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        EngineRow(
            title = "Speech model — ${chosen.displayName}",
            subtitle = "Transcribes your dictation on-device (${formatBytes(chosen.approxBytes)})",
            state = speech,
            onRetry = { vm.ensureSpeech() },
        )
        if (OnboardingLogic.showChooseDifferentModel(speech)) {
            // The no-wedge escape (F6 fix round 1, I-1): EVERY Failed terminal — a Play
            // refusal on a sideloaded install included — leaves the step completable. Back to
            // the chooser, where the CPU tiers are always pickable; Retry above stays the
            // primary action and Continue stays locked (the mandatory-model gate holds).
            // 4.3 fix round (I-2): the escape is offered for EVERY Failed terminal — that part is
            // unchanged and is the no-wedge contract — but only a genuine DELIVERY failure of the
            // gated tier suspends the one-tier rule. The reason and the tier are both in scope
            // here and nowhere above, which is why the answer is computed here and handed up.
            val deliveryFailed = OnboardingLogic.oneTierDeliveryFailed(
                pickedTierId, (speech as? EngineState.Failed)?.message.orEmpty(),
            )
            TextButton(onClick = { onChooseAgain(deliveryFailed) }) {
                Text(OnboardingLogic.CHOOSE_DIFFERENT_MODEL)
            }
        }
        Spacer(Modifier.height(12.dp))
        EngineRow(
            title = "Read-aloud voice",
            subtitle = "Speaks text aloud on-device ($voiceClause)",
            state = voice,
            onRetry = { vm.ensureVoice() },
        )
    }
}

/** One selectable tier card rendering [ModelTierCopy] — the same copy Settings' picker shows. */
@Composable
private fun TierChoiceCard(
    model: WhisperModel,
    copy: ModelTierCopy.TierCopy?,
    steered: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) Primary else MaterialTheme.colorScheme.outline,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        copy?.headline ?: model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        model.displayName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (selected) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = Primary)
                }
            }
            copy?.let { c ->
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chips = if (steered) listOf(ModelTierCopy.STEER_BADGE) + c.badges else c.badges
                    chips.forEach { badge ->
                        Surface(
                            color = Primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                badge,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    c.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EngineRow(
    title: String,
    subtitle: String,
    state: EngineState,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                when (state) {
                    is EngineState.Ready -> Icon(Icons.Filled.CheckCircle, contentDescription = "Ready", tint = Primary)
                    is EngineState.Failed -> OutlinedButton(onClick = onRetry) { Text("Retry") }
                    else -> Unit
                }
            }
            when (state) {
                is EngineState.Working -> {
                    Spacer(Modifier.height(10.dp))
                    if (state.pct >= 0) {
                        LinearProgressIndicator(
                            progress = { state.pct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${state.label} — ${state.pct}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${state.label}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is EngineState.Failed -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> Unit
            }
        }
    }
}

// ------------------------------------------------------------------------------- cloud

/**
 * The teaching card (3.5.0, spec A3): before this step existed a user could finish onboarding
 * never learning cloud keys exist. Copy contract — own API key, the four providers by name, top
 * accuracy + widest language coverage, billed to the USER's provider account, entirely optional,
 * on-device always works and remains the default. NO speed claims: the old copy's "real-time
 * streaming" hook was retired with it.
 */
@Composable
private fun CloudStep(onCloudSetup: () -> Unit, onFinish: () -> Unit) {
    Text(
        "One more thing worth knowing: you can plug in your own API key from OpenAI, " +
            "Google Gemini, ElevenLabs, or Soniox. The big cloud models offer top accuracy " +
            "and the widest language coverage, and usage is billed to your own provider " +
            "account at the provider's rates. It's entirely optional — the on-device model " +
            "always works and remains the default.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    ChoiceCard(
        icon = Icons.Filled.CloudQueue,
        title = "Set up cloud providers",
        subtitle = "Bring your own keys — top accuracy and the widest language coverage, " +
            "billed to your own accounts.",
        onClick = onCloudSetup,
    )
    Spacer(Modifier.height(16.dp))
    ChoiceCard(
        icon = Icons.Filled.PhoneAndroid,
        title = "Finish — on-device only",
        subtitle = "Free and private. Everything runs on your phone; add keys anytime in " +
            "Engines & voices.",
        onClick = onFinish,
    )
}

@Composable
private fun ChoiceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
