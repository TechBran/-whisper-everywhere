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
// contract): Continue on the permissions step is gated on the bubble's three permissions, the
// engines step releases only once the speech model is Ready (a failed download shows Retry and
// holds), and "Skip setup" exists ONLY on the cloud step — cloud is the one genuinely optional
// part. Back walks backwards; on the first step it leaves the activity WITHOUT recording
// completion, so onboarding returns on next launch. No speed claims anywhere.
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingFlowScreen(
    onFinish: () -> Unit,
    onCloudSetup: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ACTIVITY-scoped on purpose: the ~365 MB voice download must survive both step changes and
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

    // Permission state lives at flow level (3.5.x): the pinned footer gates Continue on the
    // bubble's three permissions, so the step and the footer read the same truth. Re-checked on
    // every ON_RESUME because overlay, accessibility, and notification access are granted in
    // system Settings and the user bounces there and back per row.
    var mic by remember { mutableStateOf(hasMic(context)) }
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var accessibility by remember { mutableStateOf(WhisperAccessibilityService.isEnabled()) }
    var notifListener by remember { mutableStateOf(MediaNotificationListener.isEnabled()) }

    fun refreshPermissions() {
        mic = hasMic(context)
        overlay = Settings.canDrawOverlays(context)
        accessibility = WhisperAccessibilityService.isEnabled()
        notifListener = MediaNotificationListener.isEnabled()
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
                    )
                    Step.LANGUAGE -> LanguageStep(
                        languageTag = languageTag,
                        picked = pickedLanguage,
                        onPick = { pickedLanguage = it },
                    )
                    Step.ENGINES -> EnginesStep(
                        vm = setupVm,
                        languageTag = languageTag,
                        pickedTierId = pickedTierId,
                        oneTierDeliveryFailed = oneTierDeliveryFailed,
                        onPick = { pickedTierId = it },
                        onChooseAgain = {
                            oneTierDeliveryFailed = true
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
                    val missing = OnboardingLogic.missingBubblePermissions(mic, overlay, accessibility)
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
                        enabled = OnboardingLogic.permissionsContinueEnabled(mic, overlay, accessibility),
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
 */
@Composable
private fun PermissionsStep(
    mic: Boolean,
    overlay: Boolean,
    accessibility: Boolean,
    notifListener: Boolean,
    onMicGranted: (Boolean) -> Unit,
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
    PermissionRow(
        title = "Accessibility service",
        why = "Types the transcribed text into the app you're using",
        granted = accessibility,
        onGrant = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
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
 * the 54-language list carries it, auto one tap away with its cost honestly subtitled — the
 * ruled text, verbatim. No preselection, the model pick's own discipline: the badge suggests,
 * the user still taps, and the footer's Continue stays locked until they do.
 */
@Composable
private fun LanguageStep(
    languageTag: String,
    picked: String?,
    onPick: (String) -> Unit,
) {
    Text(
        OnboardingLogic.LANGUAGE_HINT,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    val deviceCode = OnboardingLogic.deviceLanguageCode(languageTag)
    OnboardingLogic.languageRows(languageTag).forEach { (code, displayName) ->
        LanguageRow(
            title = displayName,
            subtitle = if (code == "auto") OnboardingLogic.AUTO_LANGUAGE_SUBTITLE else null,
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
    onPick: (String) -> Unit,
    onChooseAgain: () -> Unit,
) {
    val speech by vm.speechState.collectAsState()
    val voice by vm.voiceState.collectAsState()

    if (speech is EngineState.Pending) {
        // ---- choose phase: nothing downloads until the user has made an informed pick.
        Text(
            "Pick your speech model — dictation runs on your phone, and audio never has to " +
                "leave it. The read-aloud voice (about 365 MB) downloads alongside whichever " +
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
        ModelTierCopy.orderedForLanguageTagFor(languageTag, npuTierIds, alsoOfferedIds)
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
            TextButton(onClick = onChooseAgain) {
                Text(OnboardingLogic.CHOOSE_DIFFERENT_MODEL)
            }
        }
        Spacer(Modifier.height(12.dp))
        EngineRow(
            title = "Read-aloud voice",
            subtitle = "Speaks text aloud on-device (about 365 MB)",
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
