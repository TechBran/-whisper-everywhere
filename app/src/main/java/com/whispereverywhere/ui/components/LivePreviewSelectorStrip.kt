package com.whispereverywhere.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.transcription.stream.PreviewWorkboard
import com.whispereverywhere.transcription.stream.StreamingPackCopy

/**
 * THE PREVIEWER'S PROGRESS, ABOVE THE LANGUAGE SELECTOR — owner ruling 3c, 2026-09-11:
 *
 * > *"And you can incorporate the status for that model being downloaded right there above the
 * > language selector. That way users can see the progress right away and know that their language
 * > is ready for selection. That way, it's just less friction for the users."*
 *
 * It is the honest half of ruling 3b. A selection now starts a 73 MB transfer on any connection,
 * so the place the user picked is the place that must show it happening; a spend they cannot watch
 * where they caused it is a silent spend. This retires AF5's Home-not-onboarding compromise —
 * the progress no longer hides on Home's card.
 *
 * ### Why ONE composable used at BOTH selection sites
 *
 * The in-app picker (`HomeScreen.LanguageSelectionCard`) and the onboarding language step
 * (`OnboardingFlowScreen.LanguageStep`) are the two places a language is chosen, and the ruling
 * names the placement rather than the screen. Two copies of this block would be two chances for
 * one of them to fall behind the observable — which is the shape of the defect 4.5.0 Task 1
 * exists to retire, one surface further out.
 *
 * ### What it holds: nothing
 *
 * One collector of the ONE observable (`PreviewWorkboard`, Task 1), one row per record, and every
 * word from `StreamingPackCopy`. No decision, no actuator, no tap, no state of its own, and no
 * Application handle — so it can be dropped into any Compose tree, including onboarding's, where
 * there is no app-scoped view model in scope. `LivePreviewSelectorStripPinTest` holds it to that
 * as source.
 *
 * ### Why a row PER LANGUAGE, and why it renders nothing at all most of the time
 *
 * The board is keyed by language because the next build has N packs and *"two languages must be
 * able to arrive without either becoming invisible"*. So this reads every record rather than the
 * selected one's: a transfer keeps its surface when the selection moves off it, and each row
 * names the pack it is about. With no record — the ordinary case, on every screen, for every user
 * whose pack is already installed — it draws nothing, which is why it is safe to place above a
 * selector that has no progress to report.
 *
 * **At the onboarding language step it is silent by construction today**, and that is worth
 * knowing rather than discovering: the ENGINES step comes AFTER the language step, so no on-device
 * tier exists while it is on screen, and `PreviewAutoFetch.decide` refuses on
 * `!localTierInstalled`. The pick made there is recorded (`PreviewPicks`) and honoured the moment
 * Home first composes with a tier installed. The strip is placed there anyway because the ruling
 * places it there, because it is the same component reading the same observable, and because the
 * day that order changes it will already be right.
 */
@Composable
fun LivePreviewSelectorStrip(modifier: Modifier = Modifier) {
    val board by PreviewWorkboard.work.collectAsState()
    // Every arrival the feature is currently doing something about, in the words of the one
    // observable. The display name comes from the picker's own table — the same owner of
    // code-to-word the card and the Settings row read, so three surfaces cannot name one language
    // three ways — and the fallback is the code itself, unreachable for a catalogue row.
    val rows = board.values.mapNotNull { work ->
        val language = PreferencesManager.languageDisplayName(work.language) ?: work.language
        StreamingPackCopy.selectorLine(work, language)?.let { line ->
            StreamingPackCopy.featureTitle(language) to line
        }
    }
    if (rows.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        rows.forEach { (title, line) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
