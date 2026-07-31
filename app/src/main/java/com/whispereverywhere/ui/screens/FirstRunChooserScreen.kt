package com.whispereverywhere.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whispereverywhere.ui.theme.Primary

// ---------------------------------------------------------------------------------------------
// Two-path first-run chooser (Task 4). Shown at most once — every path (and Skip, and back-press)
// records onboardingCompleted before leaving, so a user is never dragged back here (the
// firstRunStartDestination onboardingCompleted short-circuit). This screen is purely presentational:
// it fires one of three callbacks; MainActivity owns persisting the flag and the popUpTo("first_run")
// navigation. It NEVER blocks — Skip is always present and back-press is a skip. No speed claim
// anywhere; the free path sells privacy (on-device), the key path sells "your own account", neither
// sells being fast (measured on-device is a tie at best).
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstRunChooserScreen(
    onFreeAndPrivate: () -> Unit,
    onBringYourOwnKey: () -> Unit,
    onSkip: () -> Unit,
) {
    // Back-press never blocks: it is a skip that lands on Home with the setup banner.
    BackHandler { onSkip() }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Get started", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            Text(
                text = "Choose how you'd like to transcribe. You can change this any time in " +
                    "Engines & voices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            ChooserCard(
                icon = Icons.Filled.CloudOff,
                title = "Free & private",
                subtitle = "Download a model once. Everything runs on your device — no audio " +
                    "ever leaves your phone.",
                onClick = onFreeAndPrivate,
            )

            Spacer(modifier = Modifier.height(16.dp))

            ChooserCard(
                icon = Icons.Filled.VpnKey,
                title = "Bring your own key",
                subtitle = "Use your own provider account (OpenAI, Gemini, or ElevenLabs).",
                onClick = onBringYourOwnKey,
            )

            Spacer(modifier = Modifier.height(24.dp))

            TextButton(
                onClick = onSkip,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Skip for now")
            }
        }
    }
}

/**
 * One large nav-card in the chooser: an accent icon, a title, a one-line subtitle, and a chevron.
 * The whole card is the tap target — mirrors the mode-card idiom on the dashboard.
 */
@Composable
private fun ChooserCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
