package com.whispereverywhere.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whispereverywhere.transcription.TranscriptStore
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Transcription history: list of saved sessions (rolling 14-day/10MB retention) with a
 *  full-text detail dialog offering copy / share / delete. Text only — audio is never kept. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val store = remember { TranscriptStore(File(context.filesDir, "transcripts")) }
    var refresh by remember { mutableStateOf(0) }
    val entries = remember(refresh) { store.list() }
    var selected by remember { mutableStateOf<TranscriptStore.Entry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transcriptions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No transcriptions yet.\nSessions are kept for 14 days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.startedAtMs }) { entry ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = entry }
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                DateFormat.getDateTimeInstance().format(Date(entry.startedAtMs)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entry.preview.ifBlank { "(empty)" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        // Read off the main thread: a long media-transcription session can hold a LOT of text,
        // and a synchronous file read here would jank the dialog-open animation.
        // Suppression: the producer DOES assign `value`; the compose-runtime checker can't see
        // assignments that follow a suspend call in this lint version.
        @Suppress("ProduceStateDoesNotAssignValue")
        val fullText by produceState(initialValue = "", key1 = entry) {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { store.read(entry) }.getOrDefault("")
            }
            value = text
        }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(DateFormat.getDateTimeInstance().format(Date(entry.startedAtMs))) },
            text = {
                Column(Modifier.heightIn(max = 380.dp)) {
                    Text(
                        fullText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                Row {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(fullText)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, fullText)
                                },
                                "Share transcription"
                            )
                        )
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = {
                        store.delete(entry)
                        selected = null
                        refresh++
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text("Close") }
            },
        )
    }
}
