package com.whispereverywhere

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.whispereverywhere.WhisperEverywhereApp
import com.whispereverywhere.ui.screens.HomeScreen
import com.whispereverywhere.ui.screens.LegalDocumentScreen
import com.whispereverywhere.ui.screens.OnboardingModelScreen
import com.whispereverywhere.ui.screens.SettingsScreen
import com.whispereverywhere.ui.theme.WhisperEverywhereTheme

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

        // Request permissions
        requestPermissions()

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
                }
            )
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
