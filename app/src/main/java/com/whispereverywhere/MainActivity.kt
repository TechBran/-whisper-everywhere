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
import com.whispereverywhere.ui.screens.BatchTranscribeScreen
import com.whispereverywhere.ui.screens.EnginesAndVoicesScreen
import com.whispereverywhere.ui.screens.HomeScreen
import com.whispereverywhere.ui.screens.LegalDocumentScreen
import com.whispereverywhere.ui.screens.OnboardingModelScreen
import com.whispereverywhere.ui.screens.SettingsScreen
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

    // Compute the start destination once, at launch: if there is no installed
    // speech model, gate the app behind the model-download onboarding wizard.
    val startDestination = remember {
        val app = WhisperEverywhereApp.getInstance()
        if (app.whisperModelManager.installedModel() == null) {
            "onboarding_model"
        } else {
            "home"
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("onboarding_model") {
            OnboardingModelScreen(
                onModelReady = {
                    navController.navigate("home") {
                        popUpTo("onboarding_model") { inclusive = true }
                    }
                }
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
