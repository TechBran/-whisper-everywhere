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
            // 4.4.1: Home's card, the discovery surface. Its words join the scan because the
            // scan's contract is "everything the user can read, from every surface" — and this
            // is the surface most users will ever read about the previewer.
            StreamingPackCopy.CARD_TITLE,
            StreamingPackCopy.CARD_WORKING,
            StreamingPackCopy.CARD_INSTALLED_TITLE,
            StreamingPackCopy.CARD_INSTALLED,
            StreamingPackCopy.CARD_DISMISS,
            StreamingPackCopy.CARD_ANSWER_PLAY,
        ) + everyState.map { StreamingPackCopy.settingsTitle(it) } +
            everyState.map { StreamingPackCopy.settingsSubtitle(it) } +
            everyState.map { StreamingPackCopy.cardOffer(it) } +
            everyState.map { StreamingPackCopy.cardAction(it) } +
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
            "The app's own 73 MB model, fetched from Google Play over your connection when you ask for it — never from a third party. Words appear on the bubble as you talk; the typed transcript is still the speech model's.",
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
            "The preview model is damaged. Get it again from Google Play (73 MB over your connection) to restore live words.",
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

    @Test fun thePlayRoutesPromiseNoThirdPartyDownload_andOnlyTheDeliveredOneSaysItIsAlreadyHere() {
        // The amendment, verbatim: "the previewer's install row says 'included with the app' on
        // Play builds (it is fetched, not downloaded from a third party)". Its parenthetical is
        // about PROVENANCE, and naming Play is what keeps it: no Play row may offer a
        // third-party download.
        for (state in listOf(StreamingPackState.PackDelivered, StreamingPackState.PackFetchable)) {
            val sub = StreamingPackCopy.settingsSubtitle(state)
            assertFalse(
                "and must promise no third-party download: on a Play route the bytes are the " +
                    "app's own, which is the whole point of the amendment: $sub",
                sub.lowercase().contains("download"),
            )
            assertFalse(
                "nor may its title offer one: ${StreamingPackCopy.settingsTitle(state)}",
                StreamingPackCopy.settingsTitle(state).lowercase().contains("download"),
            )
        }
        // Fix round 1, B1. Provenance is NOT a licence to claim the bytes are already paid for.
        // `preview_en` is deliveryType "on-demand" (preview_en/build.gradle.kts:35), so on
        // PackFetchable the pack rides the AAB we UPLOADED, not the install the user HAS: the
        // tap starts a real 73 MB transfer, which is exactly why this row has to answer
        // NeedsConfirmation. Only the DELIVERED row may say the bytes came with the app.
        val delivered = StreamingPackCopy.settingsSubtitle(StreamingPackState.PackDelivered)
        assertTrue("the delivered row is the one that really is already here: $delivered", delivered.contains("Included with the app"))
        assertTrue(delivered.contains("already on this device"))
        val fetchable = StreamingPackCopy.settingsSubtitle(StreamingPackState.PackFetchable)
        assertFalse(
            "an on-demand pack that has not been delivered is NOT included with the install the " +
                "user has, and saying so hides 73 MB of their data: $fetchable",
            fetchable.lowercase().contains("included with the app"),
        )
        assertFalse("nor is it already on the device: $fetchable", fetchable.contains("already on this device"))
        assertTrue("it names Play: $fetchable", fetchable.contains("Google Play"))
        assertTrue(
            "the size the tap will cost, from the one badge formatter: $fetchable",
            fetchable.contains(StreamingPackCatalog.sizeBadge(StreamingPackCatalog.EN.totalBytes)),
        )
        assertTrue("and that it travels over the user's own connection: $fetchable", fetchable.contains("your connection"))
        // The repair that re-fetches costs the same 73 MB and says so too.
        val repairFetch = StreamingPackCopy.settingsSubtitle(StreamingPackState.Repair(StreamingPackState.PackFetchable))
        assertTrue(repairFetch.contains("Google Play"))
        assertTrue(
            "a repair that re-fetches the whole pack is not free either: $repairFetch",
            repairFetch.contains(StreamingPackCatalog.sizeBadge(StreamingPackCatalog.EN.totalBytes)),
        )
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

    // ------------------------------------------------------------------ Home's card (4.4.1)

    @Test fun theCardStringsArePinnedVerbatim() {
        assertEquals("Live words on the bubble", StreamingPackCopy.CARD_TITLE)
        assertEquals(
            "The English preview model is arriving now; the typed transcript is unchanged.",
            StreamingPackCopy.CARD_WORKING,
        )
        assertEquals("Live words are on", StreamingPackCopy.CARD_INSTALLED_TITLE)
        assertEquals(
            "Pick English as your transcription language to see them on the bubble as you " +
                "speak; the typed transcript is unchanged.",
            StreamingPackCopy.CARD_INSTALLED,
        )
        assertEquals("Dismiss", StreamingPackCopy.CARD_DISMISS)
        assertEquals("Answer Google Play", StreamingPackCopy.CARD_ANSWER_PLAY)
    }

    @Test fun theOneInFlightStateThatAsksForAGestureHasOneToOffer() {
        // Review r1, B3: fetchLine(NeedsConfirmation) ends in "tap to answer", and the working
        // card had no action at all — so the sentence named a gesture that did not exist, on the
        // metered tap-to-fetch path, with the permanent-no X as the only thing left to press.
        // The pure half of the fix: that state is the one in-flight state the row calls tappable,
        // and it is the one the card labels.
        assertTrue(
            "the state that asks is the state that is tappable",
            StreamingPackCopy.fetchLineTappable(NpuPackFetch.FetchState.NeedsConfirmation),
        )
        assertTrue(
            "and its line is the one that asks for the tap",
            StreamingPackCopy.fetchLine(NpuPackFetch.FetchState.NeedsConfirmation)
                ?.contains("tap to answer") == true,
        )
        assertTrue(
            "the label names Play, because the dialog and the decision in it are Play's",
            StreamingPackCopy.CARD_ANSWER_PLAY.contains("Google Play"),
        )
        // Every other in-flight state is work with nothing to ask, and carries no action: a
        // button on those would re-enter the fetch mid-transfer (the row's own B1 lesson).
        for (state in listOf(
            NpuPackFetch.FetchState.Pending,
            NpuPackFetch.FetchState.Downloading(1_000_000L, 72_654_782L),
            NpuPackFetch.FetchState.Transferring,
            NpuPackFetch.FetchState.Verifying(0L, 72_654_782L),
        )) {
            assertFalse("$state asks for nothing", StreamingPackCopy.fetchLineTappable(state))
        }
    }

    @Test fun theCardsOfferIsTheSAMEPerSourceTableTheRowUses() {
        // The 4.4.1 brief's rule: "Copy must be true per the delivered-vs-fetch distinction
        // StreamingPackCopy already makes". The strongest form of that is not a second set of
        // sentences held to the same rule by a second test — it is the SAME table, so a card
        // that promised "included with the app" for an undelivered on-demand pack (fix round
        // 1's B1, on the row) is not expressible.
        for (state in everyState) {
            assertEquals(
                "the card's body for $state",
                StreamingPackCopy.settingsSubtitle(state),
                StreamingPackCopy.cardOffer(state),
            )
            assertEquals(
                "and its action names the source that action will actually use",
                StreamingPackCopy.settingsTitle(state),
                StreamingPackCopy.cardAction(state),
            )
        }
    }

    @Test fun theCardKeepsTheTwoPromisesTheBriefNames() {
        // "must say that the typed transcript is unchanged (the additive promise) and that it is
        // English for now".
        assertTrue(StreamingPackCopy.CARD_WORKING.contains("typed transcript is unchanged"))
        assertTrue(StreamingPackCopy.CARD_INSTALLED.contains("typed transcript is unchanged"))
        for (state in listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            assertTrue(
                "the offer body carries the additive promise for $state",
                StreamingPackCopy.cardOffer(state).contains("typed transcript"),
            )
            assertTrue(
                "and its action names the language: ${StreamingPackCopy.cardAction(state)}",
                StreamingPackCopy.cardAction(state).contains("English"),
            )
        }
        assertTrue(
            "the English-only caveat is the language step's own sentence, not a second wording " +
                "of it — the card renders this one",
            StreamingPackCopy.LANGUAGE_STEP_SENTENCE.contains("English-only for now"),
        )
        assertTrue(
            "and the installed card is where the owner's 'pick English' sentence lives (the " +
                "gate itself is deliberately unchanged)",
            StreamingPackCopy.CARD_INSTALLED.startsWith("Pick English"),
        )
    }

    @Test fun theCardNeverAnnouncesAnInstallAsSomethingTheUserMustDo() {
        // The card exists because the Settings row was never found. Its working line must not
        // send the reader anywhere: there is nothing to do, which is the whole ruling.
        val working = StreamingPackCopy.CARD_WORKING.lowercase()
        for (fragment in listOf("settings", "tap", "open the")) {
            assertFalse("<<$working>> contains '$fragment'", working.contains(fragment))
        }
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
