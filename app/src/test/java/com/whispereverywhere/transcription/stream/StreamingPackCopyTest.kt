package com.whispereverywhere.transcription.stream

import com.whispereverywhere.npu.NpuPackFetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every user-facing string of the feature (spec §9 as AMENDED on 2026-09-10), pinned verbatim
 * and scanned for the banned words.
 *
 * The amendment is what makes the install sentence a TABLE rather than one constant: *"the
 * previewer's install row says 'included with the app' on Play builds (it is fetched, not
 * downloaded from a third party); the fallback wording only on non-Play builds"*. So the row's
 * words are keyed by [StreamingPackState] — the one state machine that already knows which
 * source this install has — and the spec's single `SETTINGS_INSTALL` survives as the
 * `Downloadable` row, which is the only row where a third-party download is what happens.
 */
class StreamingPackCopyTest {

    private val everyState = listOf(
        StreamingPackState.Installed,
        StreamingPackState.PackDelivered,
        StreamingPackState.PackFetchable,
        StreamingPackState.Downloadable,
        StreamingPackState.Repair(StreamingPackState.PackDelivered),
        StreamingPackState.Repair(StreamingPackState.PackFetchable),
        StreamingPackState.Repair(StreamingPackState.Downloadable),
    )

    private val everyFetchState = listOf(
        NpuPackFetch.FetchState.Idle,
        NpuPackFetch.FetchState.Pending,
        NpuPackFetch.FetchState.Downloading(1_000_000L, 72_654_782L),
        NpuPackFetch.FetchState.Downloading(0L, 0L),
        NpuPackFetch.FetchState.Transferring,
        NpuPackFetch.FetchState.Verifying(0L, 72_654_782L),
        NpuPackFetch.FetchState.NeedsConfirmation,
        NpuPackFetch.FetchState.Installed,
        NpuPackFetch.FetchState.Cancelled,
        NpuPackFetch.FetchState.Failed("the shell's own re-told refusal."),
    )

    /** Everything the user can read, from every surface — the scan below is over ALL of it. */
    private val all: List<String>
        get() = listOf(
            StreamingPackCopy.SETTINGS_TITLE,
            StreamingPackCopy.SETTINGS_INSTALLED,
            StreamingPackCopy.SETTINGS_INSTALL_FROM_PACK,
            StreamingPackCopy.SETTINGS_INSTALL_FETCH,
            StreamingPackCopy.SETTINGS_INSTALL_DOWNLOAD,
            StreamingPackCopy.SETTINGS_REPAIR,
            StreamingPackCopy.SETTINGS_REPAIR_FROM_PACK,
            StreamingPackCopy.SETTINGS_REPAIR_FETCH,
            StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE,
            StreamingPackCopy.SWITCH_TITLE,
            StreamingPackCopy.LANGUAGE_STEP_SENTENCE,
            StreamingPackCopy.LANGUAGE_CHIP,
            StreamingPackCopy.DELETE_TITLE,
            StreamingPackCopy.DELETE_SUBTITLE,
            StreamingPackCopy.PROGRESS_STARTING,
            StreamingPackCopy.PROGRESS_INSTALLING,
            StreamingPackCopy.INSTALL_FAILED,
            StreamingPackCopy.downloadProgress(1_000_000L, 72_654_782L),
            StreamingPackCopy.downloadProgress(0L, 0L),
        ) + everyState.map { StreamingPackCopy.settingsTitle(it) } +
            everyState.map { StreamingPackCopy.settingsSubtitle(it) } +
            everyFetchState.mapNotNull { StreamingPackCopy.fetchLine(it) }

    // ------------------------------------------------------------------ the strings themselves

    @Test fun theStringsArePinnedVerbatim() {
        assertEquals("Live words while you speak (English)", StreamingPackCopy.SETTINGS_TITLE)
        assertEquals(
            "Installed (73 MB). Words appear on the bubble as you speak English; the typed transcript is unchanged.",
            StreamingPackCopy.SETTINGS_INSTALLED,
        )
        assertEquals(
            "Included with the app (73 MB) and already on this device — nothing to fetch. Words appear on the bubble as you talk; the typed transcript is still the speech model's.",
            StreamingPackCopy.SETTINGS_INSTALL_FROM_PACK,
        )
        assertEquals(
            "Included with the app (73 MB), delivered by Google Play when you ask for it. Words appear on the bubble as you talk; the typed transcript is still the speech model's.",
            StreamingPackCopy.SETTINGS_INSTALL_FETCH,
        )
        assertEquals(
            "Download a 73 MB English preview model. Words appear on the bubble as you talk; the typed transcript is still the speech model's.",
            StreamingPackCopy.SETTINGS_INSTALL_DOWNLOAD,
        )
        assertEquals(
            "The preview model is damaged. Download it again to restore live words.",
            StreamingPackCopy.SETTINGS_REPAIR,
        )
        assertEquals(
            "The preview model is damaged. Install it again from the copy included with the app to restore live words.",
            StreamingPackCopy.SETTINGS_REPAIR_FROM_PACK,
        )
        assertEquals(
            "The preview model is damaged. Get it again from Google Play to restore live words.",
            StreamingPackCopy.SETTINGS_REPAIR_FETCH,
        )
        assertEquals(
            "Live words are off on this device: the preview model did not pass its start-up check. Your transcripts are unaffected.",
            StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE,
        )
        assertEquals("Show live words", StreamingPackCopy.SWITCH_TITLE)
        assertEquals(
            "Live words on the bubble are English-only for now; other languages show a progress line while each sentence is transcribed.",
            StreamingPackCopy.LANGUAGE_STEP_SENTENCE,
        )
        assertEquals("Live words on the bubble while you speak — preview model installed.", StreamingPackCopy.LANGUAGE_CHIP)
        assertEquals("Delete the preview model", StreamingPackCopy.DELETE_TITLE)
    }

    @Test fun theBadgeIsTheCatalogsNotARetypedNumber() {
        val badge = StreamingPackCatalog.sizeBadge(StreamingPackCatalog.EN.totalBytes)
        assertEquals("73 MB", badge)
        assertTrue(StreamingPackCopy.SETTINGS_INSTALL_FROM_PACK.contains(badge))
        assertTrue(StreamingPackCopy.SETTINGS_INSTALL_FETCH.contains(badge))
        assertTrue(StreamingPackCopy.SETTINGS_INSTALL_DOWNLOAD.contains(badge))
        assertTrue(StreamingPackCopy.SETTINGS_INSTALLED.contains(badge))
        all.forEach { assertFalse("never the tarball's size under the small badge", it.contains("310")) }
    }

    @Test fun noStringClaimsASpeedOrNamesALatency() {
        // The union of the app's banned lists: HowToGuideTest, InFlightStripTest, ModelTierCopyTest,
        // CloudProvidersScreenLogicTest. "live" is the app's own word for the surface (CLOUD_LIVE).
        val banned = listOf("faster", "fastest", "quicker", "quickest", "instant", "real-time", "blazing", "lightning", "speed")
        all.forEach { s ->
            val lower = s.lowercase()
            banned.forEach { b -> assertFalse("'$b' in <<$s>>", lower.contains(b)) }
            assertFalse("no millisecond claim in copy", Regex("\\d+\\s?ms").containsMatchIn(lower))
        }
    }

    @Test fun everyInstallStringSaysTheTypedTranscriptIsUntouched() {
        // The one promise that matters: the previewer is additive (spec §10). It is made on every
        // route, because every route ends in the same feature.
        assertTrue(StreamingPackCopy.SETTINGS_INSTALL_FROM_PACK.contains("typed transcript"))
        assertTrue(StreamingPackCopy.SETTINGS_INSTALL_FETCH.contains("typed transcript"))
        assertTrue(StreamingPackCopy.SETTINGS_INSTALL_DOWNLOAD.contains("typed transcript"))
        assertTrue(StreamingPackCopy.SETTINGS_INSTALLED.contains("typed transcript"))
        assertTrue(StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE.contains("transcripts are unaffected"))
    }

    // ------------------------------------------------------------------ the amendment's own rule

    @Test fun thePlayRoutesSayIncludedWithTheAppAndPromiseNoThirdPartyDownload() {
        // The amendment, verbatim: "the previewer's install row says 'included with the app' on
        // Play builds (it is fetched, not downloaded from a third party)". On a Play install the
        // 73 MB rides the `preview_en` asset pack inside the AAB the user already installed —
        // telling them they are about to download it from somewhere is simply false.
        for (state in listOf(StreamingPackState.PackDelivered, StreamingPackState.PackFetchable)) {
            val sub = StreamingPackCopy.settingsSubtitle(state)
            assertTrue("$state must say where the bytes really come from: $sub", sub.contains("Included with the app"))
            assertFalse(
                "and must promise no download: on a Play install there is no third-party " +
                    "transfer at all, which is the whole point of the amendment: $sub",
                sub.lowercase().contains("download"),
            )
            assertFalse(
                "nor may its title offer one: ${StreamingPackCopy.settingsTitle(state)}",
                StreamingPackCopy.settingsTitle(state).lowercase().contains("download"),
            )
        }
    }

    @Test fun theFallbackWordingAppearsOnTheNonPlayRowAndNowhereElse() {
        // "the fallback wording only on non-Play builds" — Downloadable is reached from exactly
        // one place (StreamingPackInstall.playCanDeliver: a debug build, a sideload, or a refusal
        // Play named as this install's own fault), so this is the only honest "Download" row.
        val sub = StreamingPackCopy.settingsSubtitle(StreamingPackState.Downloadable)
        assertTrue("the fallback is honest about being one: $sub", sub.startsWith("Download a 73 MB"))
        val saysDownload = everyState.filter {
            StreamingPackCopy.settingsSubtitle(it).lowercase().contains("download")
        }
        assertEquals(
            "exactly two rows may say 'download': the fallback offer and the repair that would " +
                "use it — every other row is a Play install or an install already on disk",
            listOf(StreamingPackState.Downloadable, StreamingPackState.Repair(StreamingPackState.Downloadable)),
            saysDownload,
        )
    }

    @Test fun theRowIsTotalOverTheStateMachineAndNoTwoRoutesReadAlike() {
        // A row that cannot tell the user which of the five things is about to happen is a row
        // that will surprise them; a `when` that is not total is a state that falls into the
        // third-party download by accident.
        for (state in everyState) {
            assertTrue("$state has a title", StreamingPackCopy.settingsTitle(state).isNotBlank())
            assertTrue("$state has a subtitle", StreamingPackCopy.settingsSubtitle(state).isNotBlank())
        }
        val offers = listOf(
            StreamingPackState.Installed,
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )
        assertEquals(
            "the four sources read differently",
            offers.size,
            offers.map { StreamingPackCopy.settingsSubtitle(it) }.distinct().size,
        )
        assertEquals(
            "and so do the three repairs — a repair from the delivered pack costs no network " +
                "and must not be offered as a download",
            3,
            offers.drop(1).map { StreamingPackCopy.settingsSubtitle(StreamingPackState.Repair(it)) }
                .distinct().size,
        )
    }

    @Test fun aDamagedInstallAlwaysSaysSoWhateverWouldRepairIt() {
        for (via in listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            val sub = StreamingPackCopy.settingsSubtitle(StreamingPackState.Repair(via))
            assertTrue("a repair names the damage first: $sub", sub.startsWith("The preview model is damaged."))
        }
        assertEquals(
            "and the title is the same one whatever the source — the user is repairing, not choosing",
            1,
            listOf(
                StreamingPackState.PackDelivered,
                StreamingPackState.PackFetchable,
                StreamingPackState.Downloadable,
            ).map { StreamingPackCopy.settingsTitle(StreamingPackState.Repair(it)) }.distinct().size,
        )
    }

    // ------------------------------------------------------------------ our own work in flight

    @Test fun theRowNarratesOurOwnInstallWithoutInventingADenominator() {
        assertEquals("Starting…", StreamingPackCopy.PROGRESS_STARTING)
        assertEquals("Verifying and installing…", StreamingPackCopy.PROGRESS_INSTALLING)
        assertEquals(
            "12 of 73 MB",
            StreamingPackCopy.downloadProgress(12_000_000L, StreamingPackCatalog.EN.totalBytes),
        )
        assertEquals(
            "and the two halves round the same way, so the line can finish where the badge says",
            "73 of 73 MB",
            StreamingPackCopy.downloadProgress(
                StreamingPackCatalog.EN.totalBytes,
                StreamingPackCatalog.EN.totalBytes,
            ),
        )
        assertFalse(
            "an unknown total invents no denominator, exactly as the fetch line does not",
            StreamingPackCopy.downloadProgress(0L, 0L).contains("of 0"),
        )
        assertTrue(StreamingPackCopy.downloadProgress(0L, 0L).isNotBlank())
        assertEquals(
            "the failure the row falls back to when the exception carried no sentence of its own",
            "The preview model could not be installed.",
            StreamingPackCopy.INSTALL_FAILED,
        )
    }

    @Test fun theDeleteRowSaysWhatIsLostAndWhatIsNot() {
        assertEquals(
            "Frees 73 MB. Live words stop; the typed transcript is unchanged.",
            StreamingPackCopy.DELETE_SUBTITLE,
        )
        assertTrue(
            "the figure is the catalog's, like every other number on this row",
            StreamingPackCopy.DELETE_SUBTITLE
                .contains(StreamingPackCatalog.sizeBadge(StreamingPackCatalog.EN.totalBytes)),
        )
    }

    // ------------------------------------------------------------------ the fetch's own line

    @Test fun theFetchLineIsTotalOverTheFetchMachineAndSilentOnlyAtRest() {
        val silent = listOf(
            NpuPackFetch.FetchState.Idle,
            NpuPackFetch.FetchState.Installed,
            NpuPackFetch.FetchState.Cancelled,
        )
        for (state in silent) {
            assertNull(
                "at rest the row goes back to its own offer — a stale 'fetching…' line under an " +
                    "installed preview model is a lie the user cannot dismiss",
                StreamingPackCopy.fetchLine(state),
            )
        }
        val speaking = everyFetchState.filterNot { it in silent }
        for (state in speaking) {
            assertTrue("$state must narrate itself", !StreamingPackCopy.fetchLine(state).isNullOrBlank())
        }
        assertEquals(
            "a Failed shows the refusal VERBATIM — StreamingPackController has already re-told " +
                "it in this feature's words, so re-wording it here would be a second copy of the copy",
            "the shell's own re-told refusal.",
            StreamingPackCopy.fetchLine(NpuPackFetch.FetchState.Failed("the shell's own re-told refusal.")),
        )
        assertFalse(
            "and an unknown total invents no denominator",
            StreamingPackCopy.fetchLine(NpuPackFetch.FetchState.Downloading(0L, 0L))!!.contains("of 0"),
        )
    }

    @Test fun onlyTheTerminalRetryAndPlaysOwnDialogAreTappable() {
        // TtsModelManager.fetchLineTappable's B1 lesson, which this row inherits verbatim: the
        // in-flight row renders a line for every state and SettingsItem makes itself clickable
        // the moment it is handed an onClick, so a tap during the copy+hash used to start a
        // SECOND installFromPack into the same temp dir.
        assertTrue(StreamingPackCopy.fetchLineTappable(NpuPackFetch.FetchState.Failed("x")))
        assertTrue(StreamingPackCopy.fetchLineTappable(NpuPackFetch.FetchState.NeedsConfirmation))
        for (state in listOf(
            NpuPackFetch.FetchState.Idle,
            NpuPackFetch.FetchState.Pending,
            NpuPackFetch.FetchState.Downloading(1L, 2L),
            NpuPackFetch.FetchState.Transferring,
            NpuPackFetch.FetchState.Verifying(1L, 2L),
            NpuPackFetch.FetchState.Installed,
            NpuPackFetch.FetchState.Cancelled,
        )) {
            assertFalse("$state: a tap here can only duplicate work in flight", StreamingPackCopy.fetchLineTappable(state))
        }
    }
}
