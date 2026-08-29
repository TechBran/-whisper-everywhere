package com.whispereverywhere

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.npu.NpuAssetImport
import com.whispereverywhere.ui.screens.BatchTranscribeScreen
import com.whispereverywhere.ui.screens.EnginesAndVoicesScreen
import com.whispereverywhere.ui.screens.OnboardingFlowScreen
import com.whispereverywhere.ui.screens.HomeScreen
import com.whispereverywhere.ui.screens.LegalDocumentScreen
import com.whispereverywhere.ui.screens.OnboardingModelScreen
import com.whispereverywhere.ui.screens.SettingsScreen
import com.whispereverywhere.ui.screens.firstRunStartDestination
import com.whispereverywhere.ui.theme.WhisperEverywhereTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permission results
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            // Some permissions were denied
            // The UI will show appropriate prompts
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 (targetSdk 35) enforces edge-to-edge; opt in explicitly so pre-15 devices
        // render identically. Every screen sits in a Material3 Scaffold, which pads for the
        // system bars itself.
        enableEdgeToEdge()

        // Request permissions
        requestPermissions()

        // Boot-notification tap: BootReceiver cannot start a microphone FGS from BOOT_COMPLETED
        // (Android 15 ban; silenced mic on 12-14), so its notification routes here — with this
        // activity foreground the start is unrestricted and the mic fully usable.
        if (intent?.getBooleanExtra(
                com.whispereverywhere.receiver.BootReceiver.EXTRA_START_BUBBLE, false
            ) == true &&
            android.provider.Settings.canDrawOverlays(this)
        ) {
            com.whispereverywhere.service.FloatingBubbleService.start(this)
        }

        setContent {
            WhisperEverywhereTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WhisperEverywhereNavigation()
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@Composable
fun WhisperEverywhereNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The picked file rides here from the SAF picker into the batch screen. Passing a content Uri
    // through a nav route string would need encoding; a remembered holder is simpler and the screen
    // is single-purpose anyway.
    var pickedAudio by remember { mutableStateOf<PickedAudio?>(null) }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                // DISPLAY_NAME + MediaMetadataRetriever duration, both off-main; a broken file just
                // yields duration 0 and the service's decoder produces the honest failure.
                val info = withContext(Dispatchers.IO) { queryPickedAudio(context, uri) }
                pickedAudio = PickedAudio(uri, info.first, info.second)
                navController.navigate("batch_transcribe")
            }
        }
    }

    // The npu tier's asset-pair import (4.0, Q8). Same contract as the audio picker above —
    // ACTION_OPEN_DOCUMENT, no permission, and the app receives exactly the one file the owner
    // picked — and the same reason it lives HERE: a launcher must be registered from the activity's
    // composition, not from a screen that may not be on the back stack when the result returns.
    //
    // The whole 358 MB inflate is inside WhisperModelManager.importNpuAssetPair on Dispatchers.IO;
    // this coroutine only moves its progress into Compose state. A refusal is a RETURNED state, not
    // an exception: letting a user pick any file on the device means a wrong file is an ordinary
    // outcome, and one the card has to be able to explain.
    var npuImportState by remember {
        mutableStateOf<NpuAssetImport.ImportState>(NpuAssetImport.ImportState.Idle)
    }

    val npuAssetImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            // Dismissed the picker. Not a failure, and not worth a message.
            npuImportState = NpuAssetImport.ImportState.Idle
        } else {
            scope.launch {
                npuImportState = NpuAssetImport.ImportState.Running(0L, 0L)
                npuImportState = WhisperEverywhereApp.getInstance()
                    .whisperModelManager
                    .importNpuAssetPair(uri) { soFar, total ->
                        npuImportState = NpuAssetImport.ImportState.Running(soFar, total)
                    }
            }
        }
    }

    // Compute the start destination once, at launch. Onboarding is mandatory: no installed model
    // always opens the two-path chooser, even if onboardingCompleted was restored by Auto Backup
    // (backup never restores the model file). See firstRunStartDestination for the exact rule
    // (pinned by ModeDashboardLogicTest).
    val startDestination = remember {
        val app = WhisperEverywhereApp.getInstance()
        firstRunStartDestination(
            hasModel = app.whisperModelManager.installedModel() != null
        )
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Guided first-run onboarding (2026-08-01; mandatory since 2026-08-18): permissions ->
        // engines (four-tier chooser; one pick starts both downloads) -> optional cloud keys.
        // The only exits are on the CLOUD step (Finish / cloud setup / Skip setup) — each records
        // onboardingCompleted then pops "first_run" inclusive. Back on the first step leaves the
        // activity WITHOUT recording completion, so the flow returns on next launch; a failed
        // model download holds the engines step with Retry (the app cannot work without a model).
        composable("first_run") {
            val app = WhisperEverywhereApp.getInstance()
            OnboardingFlowScreen(
                onFinish = {
                    app.preferencesManager.onboardingCompleted = true
                    navController.navigate("home") {
                        popUpTo("first_run") { inclusive = true }
                    }
                },
                onCloudSetup = {
                    app.preferencesManager.onboardingCompleted = true
                    navController.navigate("engines_voices") {
                        popUpTo("first_run") { inclusive = true }
                    }
                },
            )
        }

        composable("onboarding_model") {
            OnboardingModelScreen(
                onModelReady = {
                    navController.navigate("home") {
                        popUpTo("onboarding_model") { inclusive = true }
                    }
                },
                npuImportState = npuImportState,
                // The zip is often labelled application/octet-stream by the provider that wrote it,
                // and some file managers use neither name, so the last entry keeps the file
                // reachable rather than leaving the owner staring at a greyed-out row. The picked
                // file is validated hard by the importer either way — the MIME filter is a
                // convenience, never a guard.
                onImportNpuAssets = {
                    npuAssetImportLauncher.launch(
                        arrayOf(
                            "application/zip",
                            "application/x-zip-compressed",
                            "application/octet-stream",
                            "*/*",
                        )
                    )
                },
            )
        }

        composable("home") {
            HomeScreen(
                onNavigateToSettings = {
                    navController.navigate("settings")
                },
                onNavigateToOnboardingModel = {
                    navController.navigate("onboarding_model")
                },
                onNavigateToTranscripts = {
                    navController.navigate("transcripts")
                },
                onNavigateToEnginesVoices = {
                    navController.navigate("engines_voices")
                },
                onPickAudioFile = {
                    audioPickerLauncher.launch(arrayOf("audio/*"))
                }
            )
        }

        composable("transcripts") {
            com.whispereverywhere.ui.screens.TranscriptsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("batch_transcribe") {
            val picked = pickedAudio
            if (picked == null) {
                // No selection survived (e.g. process death) — return to Home.
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                BatchTranscribeScreen(
                    uri = picked.uri,
                    displayName = picked.displayName,
                    durationMs = picked.durationMs,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToPrivacyPolicy = {
                    navController.navigate("privacy_policy")
                },
                onNavigateToTerms = {
                    navController.navigate("terms_of_service")
                },
                onNavigateToModelOnboarding = {
                    navController.navigate("onboarding_model")
                },
                onNavigateToLicenses = {
                    navController.navigate("open_source_licenses")
                },
                onNavigateToCloudProviders = {
                    navController.navigate("cloud_providers")
                }
            )
        }

        // Engines & voices hub (transcription engine + read-aloud voice + API keys). The
        // `cloud_providers` route is kept as a back-compat alias resolving to the SAME composable,
        // so any retained deep link / back-stack entry still resolves.
        composable("engines_voices") {
            EnginesAndVoicesScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToPrivacyPolicy = {
                    navController.navigate("privacy_policy")
                }
            )
        }

        composable("cloud_providers") {
            EnginesAndVoicesScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToPrivacyPolicy = {
                    navController.navigate("privacy_policy")
                }
            )
        }

        composable("privacy_policy") {
            LegalDocumentScreen(
                title = "Privacy Policy",
                assetFileName = "privacy_policy.html",
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("terms_of_service") {
            LegalDocumentScreen(
                title = "Terms of Service",
                assetFileName = "terms_of_service.html",
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("open_source_licenses") {
            LegalDocumentScreen(
                title = "Open-Source Licenses",
                assetFileName = "oss_licenses.html",
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

/** One picked audio file on its way from the SAF picker to the batch screen. */
private data class PickedAudio(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
)

/**
 * Reads the picked file's display name (OpenableColumns.DISPLAY_NAME) and duration
 * (MediaMetadataRetriever). Call OFF the main thread. Everything is guarded — a name that cannot be
 * read falls back to "audio"; a duration that cannot be read falls back to 0 (the decoder then
 * produces the honest failure). The Uri is never logged.
 */
private fun queryPickedAudio(context: Context, uri: Uri): Pair<String, Long> {
    val name = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "audio"

    val durationMs = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            mmr.release()
        }
    }.getOrDefault(0L)

    return name to durationMs
}
