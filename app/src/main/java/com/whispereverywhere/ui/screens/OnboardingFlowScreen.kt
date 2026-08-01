package com.whispereverywhere.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
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
import com.whispereverywhere.service.MediaNotificationListener
import com.whispereverywhere.service.WhisperAccessibilityService
import com.whispereverywhere.ui.onboarding.OnboardingLogic
import com.whispereverywhere.ui.onboarding.OnboardingLogic.Step
import com.whispereverywhere.ui.onboarding.OnboardingSetupViewModel
import com.whispereverywhere.ui.onboarding.OnboardingSetupViewModel.EngineState
import com.whispereverywhere.ui.theme.Primary

// ---------------------------------------------------------------------------------------------
// Guided first-run onboarding (owner decision 2026-08-01): everything the app needs, configured
// in one pass on first startup. Three steps — permissions (all four, granted in place), engines
// (the Base multilingual model + the read-aloud voice, downloaded AUTOMATICALLY with no button
// presses), and optional cloud provider keys. Replaces the two-path chooser: the chooser made
// setup a fork; this makes it a walk, and the cloud fork is simply the last step.
//
// It NEVER blocks: Skip is always present, back-press on the first step is a skip, a failed
// download unblocks Continue (with a Retry on the row), and Home's setup banner + Settings remain
// the manual paths for anything skipped. No speed claims anywhere.
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

    // Back walks the flow backwards; on the first step it is a skip, mirroring the old chooser's
    // never-block contract.
    BackHandler {
        step = OnboardingLogic.previous(step) ?: run { onFinish(); return@BackHandler }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (step) {
                            Step.PERMISSIONS -> "Welcome — permissions"
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
                    Step.PERMISSIONS -> PermissionsStep(lifecycleOwner = lifecycleOwner)
                    Step.ENGINES -> EnginesStep(setupVm)
                    Step.CLOUD -> CloudStep(onCloudSetup = onCloudSetup, onFinish = onFinish)
                }
            }

            // Pinned footer: primary action + always-available skip. The CLOUD step carries its
            // own two full-size choices, so its footer is skip-only.
            Spacer(Modifier.height(12.dp))
            if (step != Step.CLOUD) {
                val speech by setupVm.speechState.collectAsState()
                val voice by setupVm.voiceState.collectAsState()
                val continueEnabled = when (step) {
                    Step.PERMISSIONS -> true
                    else -> OnboardingLogic.enginesContinueEnabled(
                        speechReady = speech is EngineState.Ready,
                        speechFailed = speech is EngineState.Failed,
                    )
                }
                if (step == Step.ENGINES) {
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
                    onClick = { OnboardingLogic.next(step)?.let { step = it } },
                    enabled = continueEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue")
                }
            }
            TextButton(
                onClick = onFinish,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Skip setup")
            }
        }
    }
}

// ------------------------------------------------------------------------------- permissions

/**
 * All four permission rows with live state and an in-place grant action each — the same set, same
 * checks, and same intents as Settings' Permissions section, so what the user grants here is
 * exactly what Settings later reports. Re-checked on every ON_RESUME because overlay,
 * accessibility, and notification access are granted in system Settings and the user bounces
 * there and back per row.
 */
@Composable
private fun PermissionsStep(lifecycleOwner: androidx.lifecycle.LifecycleOwner) {
    val context = LocalContext.current

    var mic by remember { mutableStateOf(hasMic(context)) }
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var accessibility by remember { mutableStateOf(WhisperAccessibilityService.isEnabled()) }
    var notifListener by remember { mutableStateOf(MediaNotificationListener.isEnabled()) }

    fun refresh() {
        mic = hasMic(context)
        overlay = Settings.canDrawOverlays(context)
        accessibility = WhisperAccessibilityService.isEnabled()
        notifListener = MediaNotificationListener.isEnabled()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> mic = granted }

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
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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

private fun hasMic(context: android.content.Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.RECORD_AUDIO
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

// ------------------------------------------------------------------------------- engines

/**
 * The automatic step: entering it IS the action. Both downloads start themselves (idempotently),
 * the user just watches two progress rows fill. Sizes are stated plainly since nobody tapped a
 * download button.
 */
@Composable
private fun EnginesStep(vm: OnboardingSetupViewModel) {
    LaunchedEffect(Unit) { vm.beginAutoSetup() }

    val speech by vm.speechState.collectAsState()
    val voice by vm.voiceState.collectAsState()

    Text(
        "Downloading everything the app needs to work fully on-device, in any language — " +
            "nothing to press. Both stay on your phone; audio never has to leave it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    EngineRow(
        title = "Speech model — Base multilingual",
        subtitle = "Transcribes your dictation on-device (about 60 MB)",
        state = speech,
        onRetry = { vm.ensureSpeech() },
    )
    Spacer(Modifier.height(12.dp))
    EngineRow(
        title = "Read-aloud voice",
        subtitle = "Speaks text aloud on-device (about 365 MB)",
        state = voice,
        onRetry = { vm.ensureVoice() },
    )
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
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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

/** The final fork, as two full cards — the same idiom as the old chooser, in its rightful place. */
@Composable
private fun CloudStep(onCloudSetup: () -> Unit, onFinish: () -> Unit) {
    Text(
        "Want real-time streaming transcription or cloud voices? Add your own provider keys now — " +
            "any or all of OpenAI, Gemini, ElevenLabs, and Soniox. You can always do this later " +
            "in Engines & voices.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))

    ChoiceCard(
        icon = Icons.Filled.CloudQueue,
        title = "Set up cloud providers",
        subtitle = "Bring your own keys — billed to your own accounts, only when you use them.",
        onClick = onCloudSetup,
    )
    Spacer(Modifier.height(16.dp))
    ChoiceCard(
        icon = Icons.Filled.PhoneAndroid,
        title = "Finish — on-device only",
        subtitle = "Free and private. Everything runs on your phone.",
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
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
