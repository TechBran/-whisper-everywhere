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

    /**
     * The language the app's one pack is in, as the picker spells it — every language-bearing
     * sentence below is rendered with it, so the verbatim pins read as the user reads them.
     */
    private val en = "English"

    /** A second language, to prove the sentences are parameterised and not merely spelled once. */
    private val es = "Spanish"

    /** Everything the user can read, from every surface — the scan below is over ALL of it. */
    private val all: List<String>
        get() = listOf(
            StreamingPackCopy.featureTitle(en),
            StreamingPackCopy.installed(en),
            StreamingPackCopy.SETTINGS_INSTALL_FROM_PACK,
            StreamingPackCopy.SETTINGS_INSTALL_FETCH,
            StreamingPackCopy.installDownload(en),
            StreamingPackCopy.SETTINGS_REPAIR,
            StreamingPackCopy.SETTINGS_REPAIR_FROM_PACK,
            StreamingPackCopy.SETTINGS_REPAIR_FETCH,
            StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE,
            StreamingPackCopy.SWITCH_TITLE,
            StreamingPackCopy.LANGUAGE_STEP_SENTENCE,
            StreamingPackCopy.LANGUAGE_CHIP,
            StreamingPackCopy.AUTO_ROW_TITLE,
            StreamingPackCopy.AUTO_NO_LIVE_WORDS,
            // Rendered for a language the app has NO pack for, because that is the only way a
            // user ever reads them (4.4.1 pass 3, ITEM 1). The null arm of the same two
            // functions is AUTO_ROW_TITLE / AUTO_NO_LIVE_WORDS, already above.
            StreamingPackCopy.noLiveWordsTitle(es),
            StreamingPackCopy.noLiveWordsSubtitle(es),
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
            StreamingPackCopy.cardWorking(en),
            StreamingPackCopy.CARD_INSTALLED_TITLE,
            StreamingPackCopy.cardInstalled(en),
            StreamingPackCopy.cardLanguageNote(en),
            StreamingPackCopy.CARD_DISMISS,
            StreamingPackCopy.CARD_ANSWER_PLAY,
        ) + everyState.map { StreamingPackCopy.settingsTitle(it, en) } +
            everyState.map { StreamingPackCopy.settingsSubtitle(it, en) } +
            everyState.map { StreamingPackCopy.cardOffer(it, en) } +
            everyState.map { StreamingPackCopy.cardAction(it, en) } +
            everyFetchState.mapNotNull { StreamingPackCopy.fetchLine(it) } +
            // (4.5.0 Task 1) The one observable's own sentences, from every route and every
            // phase, and the delete row's four. The scan's contract is "everything the user can
            // read, from every surface", and after this task these are the words BOTH surfaces
            // render for work in flight.
            PreviewDeleteCase.entries.map {
                StreamingPackCopy.deleteSubtitle(it, en, StreamingPackCatalog.EN.totalBytes)
            } +
            PreviewRoute.entries.flatMap { route ->
                PreviewPhase.entries.mapNotNull { phase ->
                    StreamingPackCopy.workLine(
                        PreviewWork(
                            language = "en",
                            route = route,
                            starter = PreviewStarter.PICK,
                            step = PreviewStep(phase, 12_000_000L, 72_654_782L, reason = "a refusal."),
                        ),
                    )
                }
            }

    // ------------------------------------------------------------------ the strings themselves

    @Test fun theStringsArePinnedVerbatim() {
        assertEquals("Live words while you speak (English)", StreamingPackCopy.featureTitle(en))
        assertEquals(
            "Installed (73 MB). Words appear on the bubble as you speak English; the typed transcript is unchanged.",
            StreamingPackCopy.installed(en),
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
            StreamingPackCopy.installDownload(en),
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
            "Live words on the bubble follow the language you pick: English has a preview model today, and Auto-detect shows none at all. Your typed transcript is the same either way.",
            StreamingPackCopy.LANGUAGE_STEP_SENTENCE,
        )
        assertEquals("Live words need a chosen language", StreamingPackCopy.AUTO_ROW_TITLE)
        assertEquals(
            "On Auto-detect there are none at all: pick your transcription language to see words on the bubble as you speak. Your typed transcript is unchanged either way.",
            StreamingPackCopy.AUTO_NO_LIVE_WORDS,
        )
        assertEquals(
            "Live words are not available in Spanish yet",
            StreamingPackCopy.noLiveWordsTitle(es),
        )
        assertEquals(
            "The bubble shows live words only for a language with a preview model, and there is none for Spanish yet. Your typed transcript in Spanish is unchanged.",
            StreamingPackCopy.noLiveWordsSubtitle(es),
        )
        assertEquals("Live words on the bubble while you speak — preview model installed.", StreamingPackCopy.LANGUAGE_CHIP)
        assertEquals("Delete the preview model", StreamingPackCopy.DELETE_TITLE)
    }

    @Test fun theBadgeIsTheCatalogsNotARetypedNumber() {
        val badge = StreamingPackCatalog.sizeBadge(StreamingPackCatalog.EN.totalBytes)
        assertEquals("73 MB", badge)
        assertTrue(StreamingPackCopy.SETTINGS_INSTALL_FROM_PACK.contains(badge))
        assertTrue(StreamingPackCopy.SETTINGS_INSTALL_FETCH.contains(badge))
        assertTrue(StreamingPackCopy.installDownload(en).contains(badge))
        assertTrue(StreamingPackCopy.installed(en).contains(badge))
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
        assertTrue(StreamingPackCopy.installDownload(en).contains("typed transcript"))
        assertTrue(StreamingPackCopy.installed(en).contains("typed transcript"))
        assertTrue(StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE.contains("transcripts are unaffected"))
    }

    // ------------------------------------------------------------------ the amendment's own rule

    @Test fun thePlayRoutesPromiseNoThirdPartyDownload_andOnlyTheDeliveredOneSaysItIsAlreadyHere() {
        // The amendment, verbatim: "the previewer's install row says 'included with the app' on
        // Play builds (it is fetched, not downloaded from a third party)". Its parenthetical is
        // about PROVENANCE, and naming Play is what keeps it: no Play row may offer a
        // third-party download.
        for (state in listOf(StreamingPackState.PackDelivered, StreamingPackState.PackFetchable)) {
            val sub = StreamingPackCopy.settingsSubtitle(state, en)
            assertFalse(
                "and must promise no third-party download: on a Play route the bytes are the " +
                    "app's own, which is the whole point of the amendment: $sub",
                sub.lowercase().contains("download"),
            )
            assertFalse(
                "nor may its title offer one: ${StreamingPackCopy.settingsTitle(state, en)}",
                StreamingPackCopy.settingsTitle(state, en).lowercase().contains("download"),
            )
        }
        // Fix round 1, B1. Provenance is NOT a licence to claim the bytes are already paid for.
        // `preview_en` is deliveryType "on-demand" (preview_en/build.gradle.kts:35), so on
        // PackFetchable the pack rides the AAB we UPLOADED, not the install the user HAS: the
        // tap starts a real 73 MB transfer, which is exactly why this row has to answer
        // NeedsConfirmation. Only the DELIVERED row may say the bytes came with the app.
        val delivered = StreamingPackCopy.settingsSubtitle(StreamingPackState.PackDelivered, en)
        assertTrue("the delivered row is the one that really is already here: $delivered", delivered.contains("Included with the app"))
        assertTrue(delivered.contains("already on this device"))
        val fetchable = StreamingPackCopy.settingsSubtitle(StreamingPackState.PackFetchable, en)
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
        val repairFetch =
            StreamingPackCopy.settingsSubtitle(StreamingPackState.Repair(StreamingPackState.PackFetchable), en)
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
        val sub = StreamingPackCopy.settingsSubtitle(StreamingPackState.Downloadable, en)
        assertTrue("the fallback is honest about being one: $sub", sub.startsWith("Download a 73 MB"))
        val saysDownload = everyState.filter {
            StreamingPackCopy.settingsSubtitle(it, en).lowercase().contains("download")
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
            assertTrue("$state has a title", StreamingPackCopy.settingsTitle(state, en).isNotBlank())
            assertTrue("$state has a subtitle", StreamingPackCopy.settingsSubtitle(state, en).isNotBlank())
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
            offers.map { StreamingPackCopy.settingsSubtitle(it, en) }.distinct().size,
        )
        assertEquals(
            "and so do the three repairs — a repair from the delivered pack costs no network " +
                "and must not be offered as a download",
            3,
            offers.drop(1)
                .map { StreamingPackCopy.settingsSubtitle(StreamingPackState.Repair(it), en) }
                .distinct().size,
        )
    }

    @Test fun aDamagedInstallAlwaysSaysSoWhateverWouldRepairIt() {
        for (via in listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            val sub = StreamingPackCopy.settingsSubtitle(StreamingPackState.Repair(via), en)
            assertTrue("a repair names the damage first: $sub", sub.startsWith("The preview model is damaged."))
        }
        assertEquals(
            "and the title is the same one whatever the source — the user is repairing, not choosing",
            1,
            listOf(
                StreamingPackState.PackDelivered,
                StreamingPackState.PackFetchable,
                StreamingPackState.Downloadable,
            ).map { StreamingPackCopy.settingsTitle(StreamingPackState.Repair(it), en) }
                .distinct().size,
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
            "The English preview model is arriving now; the typed transcript is unchanged. " +
                "Live words follow your transcription language: English shows them, and " +
                "Auto-detect shows none at all.",
            StreamingPackCopy.cardWorking(en),
        )
        assertEquals("Live words are on", StreamingPackCopy.CARD_INSTALLED_TITLE)
        assertEquals(
            "You'll see them on the bubble as you speak English; the typed transcript is " +
                "unchanged.",
            StreamingPackCopy.cardInstalled(en),
        )
        assertEquals(
            "Live words follow your transcription language: English shows them, and Auto-detect " +
                "shows none at all.",
            StreamingPackCopy.cardLanguageNote(en),
        )
        assertEquals("Dismiss", StreamingPackCopy.CARD_DISMISS)
        assertEquals("Answer Google Play", StreamingPackCopy.CARD_ANSWER_PLAY)
    }

    @Test fun everyCardStateNamesTheSelectedLanguageAndNoneOfThemHardcodesOne() {
        // CONTROLLER RULING 2026-09-11, superseding review r1's nit 1: *"Say what the selected
        // language is, on every card state, and say what Auto costs."* Rendered for a language
        // the app has no pack for yet, so a sentence that still spelled "English" in a literal
        // fails here rather than shipping as a lie the day a second row lands.
        val states = listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )
        val rendered = listOf(
            StreamingPackCopy.cardWorking(es),
            StreamingPackCopy.cardInstalled(es),
            StreamingPackCopy.cardLanguageNote(es),
            StreamingPackCopy.featureTitle(es),
            StreamingPackCopy.installed(es),
            StreamingPackCopy.installDownload(es),
        ) + states.map { StreamingPackCopy.cardAction(it, es) } +
            states.map { StreamingPackCopy.settingsTitle(it, es) }
        for (s in rendered) {
            assertTrue("<<$s>> must name the language it is about", s.contains(es))
            assertFalse("<<$s>> still carries an English literal", s.contains(en))
        }
    }

    @Test fun whatAutoCostsIsSaidWhereverAutoCanBeChosen() {
        // Owner ruling 1, 2026-09-11: *"if they leave it in auto, then you get no live streaming
        // at all. And that will seem to be a very fair trade-off."* A trade the user is never
        // told about is not a trade — and a user on Auto sees no card, no progress and no offer,
        // so without these sentences the feature reads as missing rather than declined.
        for (s in listOf(
            StreamingPackCopy.LANGUAGE_STEP_SENTENCE,
            StreamingPackCopy.AUTO_NO_LIVE_WORDS,
            StreamingPackCopy.cardLanguageNote(en),
        )) {
            assertTrue("<<$s>> must say what Auto costs", s.contains("Auto-detect"))
            assertTrue("<<$s>> must say it costs live words", s.lowercase().contains("none"))
        }
        // ...and none of them may imply Auto degrades the TRANSCRIPT, which it does not: the
        // additive promise is what the whole feature is built around.
        for (s in listOf(
            StreamingPackCopy.LANGUAGE_STEP_SENTENCE,
            StreamingPackCopy.AUTO_NO_LIVE_WORDS,
        )) {
            assertTrue(
                "<<$s>> must keep the typed transcript out of the trade",
                s.contains("typed transcript"),
            )
        }
        assertTrue(
            "the language step says it BEFORE any row is offered, so it names what the pick buys",
            StreamingPackCopy.LANGUAGE_STEP_SENTENCE.startsWith("Live words on the bubble follow"),
        )
    }

    @Test fun aSelectedLanguageWithNoPackIsToldTheTruthAndAutoKeepsItsOwnSentence() {
        // (4.4.1 pass 3, ITEM 1 — review r2's nit 3.) Until this pass the only "no live words"
        // sentence the app had was Auto's, and the Settings rows gated it on `== "auto"`. So a
        // user who picked French was offered "Get the English preview model", spent 73 MB, and was
        // then told "Installed. Words appear on the bubble as you speak English" — which owner
        // ruling 1 has already decided can never happen for them on any tier. These two sentences
        // are what the honest predicate (no catalogue row for the selection) says instead.
        for (name in listOf(es, "French", "Chinese")) {
            val title = StreamingPackCopy.noLiveWordsTitle(name)
            val subtitle = StreamingPackCopy.noLiveWordsSubtitle(name)
            assertTrue("<<$title>> must name the language it is about", title.contains(name))
            assertTrue("<<$subtitle>> must name the language it is about", subtitle.contains(name))
            // It must NOT sell the one model that does exist. Naming English here is one short
            // step from offering it, which is the whole of this item — and it is also the literal
            // that would become a lie the day a second catalogue row lands.
            assertFalse("<<$title>> must not name English", title.contains(en))
            assertFalse("<<$subtitle>> must not name English", subtitle.contains(en))
            assertFalse("nor the badge: nothing here is for sale", subtitle.contains("73 MB"))
            // ...and it must keep the typed transcript out of the trade, exactly as Auto's does:
            // a language with no preview model still transcribes perfectly.
            assertTrue(
                "<<$subtitle>> must keep the typed transcript out of it",
                subtitle.contains("typed transcript"),
            )
        }
        // AUTO IS A CHOICE; A LANGUAGE WITH NO PACK IS A GAP. Two different facts about the
        // world, so the null arm is Auto's own pair and not a language-shaped version of it.
        assertEquals(StreamingPackCopy.AUTO_ROW_TITLE, StreamingPackCopy.noLiveWordsTitle(null))
        assertEquals(
            StreamingPackCopy.AUTO_NO_LIVE_WORDS,
            StreamingPackCopy.noLiveWordsSubtitle(null),
        )
        assertFalse(
            "and the two are not the same sentence: Auto tells the user to pick, which is no " +
                "help at all to someone who has already picked",
            StreamingPackCopy.noLiveWordsSubtitle(es) == StreamingPackCopy.AUTO_NO_LIVE_WORDS,
        )
        assertTrue(
            "Auto's sentence is the one that asks for a pick",
            StreamingPackCopy.AUTO_NO_LIVE_WORDS.contains("pick your transcription language"),
        )
        assertFalse(
            "and the gap's sentence must not, because the pick has already been made",
            StreamingPackCopy.noLiveWordsSubtitle(es).contains("pick"),
        )
    }

    @Test fun everyPacksLanguageHasAWordThePickerCanSpell() {
        // The card renders `languageDisplayName(code)`, so a catalogue row whose language the
        // picker does not offer would read "... the en preview model" on the surface most users
        // ever see. The two lists are separate files; this is the only thing holding them
        // together, and it is the check a second pack row must pass on the day it is added.
        for (pack in StreamingPackCatalog.packs) {
            val name = com.whispereverywhere.data.local.PreferencesManager
                .languageDisplayName(pack.language)
            assertTrue(
                "the pack's language must be a code the picker offers, or the card renders the " +
                    "code itself: ${pack.language}",
                !name.isNullOrBlank(),
            )
        }
        assertEquals(
            "and today that word is English, the one row the catalogue has",
            listOf(en),
            StreamingPackCatalog.packs.map {
                com.whispereverywhere.data.local.PreferencesManager.languageDisplayName(it.language)
            },
        )
    }

    @Test fun theAskBecomesAReceiptWhereTheRowHasNoTapToGive() {
        // Review r3, H3-B3. NeedsConfirmation is tappable BY STATE, but the Settings row withholds
        // the tap once the selection moves off this pack's language — and then "tap to answer"
        // instructed a gesture the app had decided to refuse, with no ripple and no feedback when
        // it was performed. Off-selection the line must be a receipt, and must name the one thing
        // that unlocks it, because Home renders no card for a selection with no pack, so there is
        // no other surface anywhere in the app that says so.
        val everyOtherState = everyFetchState.filter { it !is NpuPackFetch.FetchState.NeedsConfirmation }
        val asking = StreamingPackCopy.fetchLine(NpuPackFetch.FetchState.NeedsConfirmation, tappable = true)
        val telling = StreamingPackCopy.fetchLine(NpuPackFetch.FetchState.NeedsConfirmation, tappable = false)
        assertTrue("with a tap, it asks for the tap", asking?.contains("tap to answer") == true)
        assertTrue("without one, it must NOT ask for a tap", telling?.contains("tap") == false)
        assertTrue(
            "and it must say what unlocks it — the selection is the only key, and nothing else " +
                "on screen names it",
            telling?.contains("Pick that language again") == true,
        )
        assertTrue(
            "the reason is still stated: the user is owed why nothing is moving",
            telling?.contains("confirmation") == true,
        )
        assertEquals(
            "every OTHER state reads identically either way — this parameter buys exactly one " +
                "sentence, and a caller that forgets it changes nothing else",
            everyOtherState.map { StreamingPackCopy.fetchLine(it, tappable = true) },
            everyOtherState.map { StreamingPackCopy.fetchLine(it, tappable = false) },
        )
        assertEquals(
            "and the default is the asking one, so the card and every existing caller are untouched",
            asking,
            StreamingPackCopy.fetchLine(NpuPackFetch.FetchState.NeedsConfirmation),
        )
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
                StreamingPackCopy.settingsSubtitle(state, en),
                StreamingPackCopy.cardOffer(state, en),
            )
            assertEquals(
                "and its action names the source that action will actually use",
                StreamingPackCopy.settingsTitle(state, en),
                StreamingPackCopy.cardAction(state, en),
            )
        }
    }

    @Test fun theCardKeepsTheTwoPromisesTheBriefNames() {
        // "must say that the typed transcript is unchanged (the additive promise)" — and, since
        // the acquisition amendment, that live words follow the language you picked rather than
        // "that it is English for now".
        assertTrue(StreamingPackCopy.cardWorking(en).contains("typed transcript is unchanged"))
        assertTrue(StreamingPackCopy.cardInstalled(en).contains("typed transcript is unchanged"))
        for (state in listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            assertTrue(
                "the offer body carries the additive promise for $state",
                StreamingPackCopy.cardOffer(state, en).contains("typed transcript"),
            )
            assertTrue(
                "and its action names the language: ${StreamingPackCopy.cardAction(state, en)}",
                StreamingPackCopy.cardAction(state, en).contains("English"),
            )
        }
        assertTrue(
            "the language caveat is ONE sentence used on every card state, so the trade cannot " +
                "be stated two ways on two cards",
            StreamingPackCopy.cardWorking(en).contains(StreamingPackCopy.cardLanguageNote(en)),
        )
        assertFalse(
            "and the announcement no longer INSTRUCTS: the pack only ever arrives for a language " +
                "the user has already selected, so \"Pick English\" would be telling them to do " +
                "the thing they just did",
            StreamingPackCopy.cardInstalled(en).startsWith("Pick "),
        )
    }

    @Test fun theCardNeverAnnouncesAnInstallAsSomethingTheUserMustDo() {
        // The card exists because the Settings row was never found. Its working line must not
        // send the reader anywhere: there is nothing to do, which is the whole ruling.
        val working = StreamingPackCopy.cardWorking(en).lowercase()
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

    // ------------------------------------------- the delete row's FOUR cases (4.5.0 Task 1)

    @Test fun theDeleteRowHasATrueSentenceForEachOfItsFourCases() {
        // 4.4.1 rendered ONE sentence across all four facts the row can be looking at, and three
        // of them made it false: the model is installed for a language the user is NOT
        // transcribing (so "live words stop" is already untrue), the install is DAMAGED (same),
        // or a write is in flight (so "Frees 73 MB" frees nothing at all). Four cases, four
        // sentences, and the case is derived from the one observable rather than guessed at here.
        assertEquals(
            "Frees 73 MB. Live words stop; the typed transcript is unchanged.",
            StreamingPackCopy.deleteSubtitle(PreviewDeleteCase.LIVE, en, bytes),
        )
        assertEquals(
            "Frees 73 MB. Live words are already off: they appear only while English is the " +
                "language you pick. The typed transcript is unchanged.",
            StreamingPackCopy.deleteSubtitle(PreviewDeleteCase.OFF_SELECTION, en, bytes),
        )
        assertEquals(
            "Frees 73 MB. The English model is damaged and live words are already off; the " +
                "typed transcript is unchanged.",
            StreamingPackCopy.deleteSubtitle(PreviewDeleteCase.DAMAGED, en, bytes),
        )
        assertEquals(
            "Nothing to free yet: the English model is being written right now. Deleting " +
                "becomes available when it finishes; the typed transcript is unchanged either way.",
            StreamingPackCopy.deleteSubtitle(PreviewDeleteCase.WORKING, en, bytes),
        )
    }

    @Test fun onlyTheThreeCasesThatActuallyFreeBytesClaimToFreeThem() {
        for (case in listOf(
            PreviewDeleteCase.LIVE,
            PreviewDeleteCase.OFF_SELECTION,
            PreviewDeleteCase.DAMAGED,
        )) {
            val line = StreamingPackCopy.deleteSubtitle(case, en, bytes)
            assertTrue("$case names the size it frees: $line", line.startsWith("Frees 73 MB."))
            assertTrue(
                "and the figure is the CATALOG's, never a retyped number — English is 73 MB, " +
                    "German 71 MB and French 128 MB, so one literal here is wrong for two of them",
                line.contains(StreamingPackCatalog.sizeBadge(bytes)),
            )
        }
        val working = StreamingPackCopy.deleteSubtitle(PreviewDeleteCase.WORKING, en, bytes)
        assertFalse(
            "the one case that frees nothing must not say it frees anything — this is the " +
                "largest untrue sentence the feature rendered: $working",
            working.contains("Frees"),
        )
        assertFalse("nor may it carry a size at all: $working", working.contains("MB"))
    }

    @Test fun everyDeleteCaseKeepsTheAdditivePromiseAndNamesItsLanguage() {
        for (case in PreviewDeleteCase.entries) {
            val line = StreamingPackCopy.deleteSubtitle(case, es, bytes)
            assertTrue(
                "$case must keep the one promise that matters (spec §10): $line",
                line.contains("typed transcript is unchanged"),
            )
        }
        // Three of the four are ABOUT a particular language's model, and say so; LIVE is about
        // the pack the user is actually transcribing in, where the rows around it already name it.
        for (case in listOf(
            PreviewDeleteCase.OFF_SELECTION,
            PreviewDeleteCase.DAMAGED,
            PreviewDeleteCase.WORKING,
        )) {
            assertTrue(
                "$case names the language it is about, so the day a second row lands it cannot " +
                    "describe one pack under another's name",
                StreamingPackCopy.deleteSubtitle(case, es, bytes).contains(es),
            )
        }
    }

    // ------------------------------------------- the one observable's own line (4.5.0 Task 1)

    @Test fun theWorkLineIsTotalOverTheObservableAndSilentOnlyWhenItIsOver() {
        val silent = listOf(PreviewPhase.INSTALLED, PreviewPhase.CANCELLED)
        for (phase in silent) {
            assertNull(
                "at rest the row goes back to its own offer — a stale 'fetching…' line under an " +
                    "installed preview model is a lie the user cannot dismiss",
                StreamingPackCopy.workLine(work(PreviewRoute.PLAY_FETCH, phase)),
            )
        }
        for (phase in PreviewPhase.entries.filterNot { it in silent }) {
            assertTrue(
                "$phase must narrate itself: a running phase with no sentence is the 73 MB that " +
                    "was invisible in Settings",
                !StreamingPackCopy.workLine(work(PreviewRoute.PLAY_FETCH, phase)).isNullOrBlank(),
            )
        }
        assertEquals(
            "a FAILED shows the refusal VERBATIM — the shell has already re-told it in this " +
                "feature's words, so re-wording it here would be a second copy of the copy",
            "the shell's own re-told refusal.",
            StreamingPackCopy.workLine(
                work(PreviewRoute.PLAY_FETCH, PreviewPhase.FAILED, reason = "the shell's own re-told refusal."),
            ),
        )
        assertEquals(
            "and a failure that named nothing still says something",
            StreamingPackCopy.INSTALL_FAILED,
            StreamingPackCopy.workLine(work(PreviewRoute.PLAY_FETCH, PreviewPhase.FAILED)),
        )
    }

    @Test fun theRouteChoosesTheVerbSoTheProvenancePromiseSurvivesTheProgressLine() {
        // The amendment's parenthetical — "it is fetched, not downloaded from a third party" — is
        // the whole reason the offer is a table. The PROGRESS line has to keep it: a Play fetch
        // that said "Downloading" would contradict SETTINGS_INSTALL_FETCH's "never from a third
        // party" while those very bytes were moving, and the fallback is the one route where a
        // third party really is serving them (installDownload is its sentence).
        val play = StreamingPackCopy.workLine(
            work(PreviewRoute.PLAY_FETCH, PreviewPhase.DOWNLOADING, 12_000_000L, 72_654_782L),
        )
        val direct = StreamingPackCopy.workLine(
            work(PreviewRoute.DIRECT_DOWNLOAD, PreviewPhase.DOWNLOADING, 12_000_000L, 72_654_782L),
        )
        assertEquals("Fetching the preview model: 12 of 73 MB", play)
        assertEquals("Downloading the preview model: 12 of 73 MB", direct)
        assertTrue("a Play route never says 'download'", play?.lowercase()?.contains("download") == false)
    }

    @Test fun theProgressLineRoundsThroughTheCatalogsOneRuleAndInventsNoDenominator() {
        // Play's own line divided by 1,000,000 and TRUNCATED until 4.5.0, so a finished fetch read
        // "72 of 72 MB" under a row that had just promised 73 — the exact defect
        // StreamingPackCatalog.megabytes exists to prevent, still live on the primary route.
        assertEquals(
            "Fetching the preview model: 73 of 73 MB",
            StreamingPackCopy.workLine(
                work(PreviewRoute.PLAY_FETCH, PreviewPhase.DOWNLOADING, 72_654_782L, 72_654_782L),
            ),
        )
        for (route in listOf(PreviewRoute.PLAY_FETCH, PreviewRoute.DIRECT_DOWNLOAD)) {
            val unknown = StreamingPackCopy.workLine(
                work(route, PreviewPhase.DOWNLOADING, 0L, 0L),
            )
            assertFalse("$route invents no denominator: $unknown", unknown!!.contains("of 0"))
            assertTrue("and still says what is happening: $unknown", unknown.endsWith("…"))
        }
    }

    @Test fun theAskBecomesAReceiptWhereTheRowHasNoTapToGive_onTheObservable() {
        // Review r3's H3-B3, carried onto the one observable. NeedsConfirmation is tappable BY
        // PHASE, but the Settings row withholds the tap once the selection moves off this pack's
        // language — and then "tap to answer" instructed a gesture the app had decided to refuse.
        val awaiting = work(PreviewRoute.PLAY_FETCH, PreviewPhase.AWAITING_ANSWER)
        val asking = StreamingPackCopy.workLine(awaiting, tappable = true)
        val telling = StreamingPackCopy.workLine(awaiting, tappable = false)
        assertTrue("with a tap, it asks for the tap", asking?.contains("tap to answer") == true)
        assertTrue("without one, it must NOT ask for a tap", telling?.contains("tap") == false)
        assertTrue(
            "and it must say what unlocks it — the selection is the only key",
            telling?.contains("Pick that language again") == true,
        )
        assertTrue("the reason is still stated", telling?.contains("confirmation") == true)
        val everyOther = PreviewPhase.entries.filterNot { it == PreviewPhase.AWAITING_ANSWER }
        assertEquals(
            "every OTHER phase reads identically either way — this parameter buys exactly one " +
                "sentence, and a caller that forgets it changes nothing else",
            everyOther.map { StreamingPackCopy.workLine(work(PreviewRoute.PLAY_FETCH, it), tappable = true) },
            everyOther.map { StreamingPackCopy.workLine(work(PreviewRoute.PLAY_FETCH, it), tappable = false) },
        )
        assertEquals(
            "and the default is the asking one, so the card reads as it did",
            asking,
            StreamingPackCopy.workLine(awaiting),
        )
    }

    @Test fun onlyTheTerminalRetryAndPlaysOwnDialogAreTappable_onTheObservable() {
        // TtsModelManager.fetchLineTappable's B1 lesson, inherited rather than re-learned: the row
        // renders a line for every phase and SettingsItem makes itself clickable the moment it is
        // handed an onClick, so a tap during the copy+hash used to start a SECOND install into
        // the same temp dir.
        assertTrue(
            StreamingPackCopy.workLineTappable(work(PreviewRoute.PLAY_FETCH, PreviewPhase.FAILED, reason = "x")),
        )
        assertTrue(
            StreamingPackCopy.workLineTappable(work(PreviewRoute.PLAY_FETCH, PreviewPhase.AWAITING_ANSWER)),
        )
        for (phase in listOf(
            PreviewPhase.ASKING,
            PreviewPhase.DOWNLOADING,
            PreviewPhase.TRANSFERRING,
            PreviewPhase.INSTALLING,
            PreviewPhase.INSTALLED,
            PreviewPhase.CANCELLED,
        )) {
            assertFalse(
                "$phase: a tap here can only duplicate work in flight",
                StreamingPackCopy.workLineTappable(work(PreviewRoute.PLAY_FETCH, phase)),
            )
        }
        assertFalse(
            "and NO work is no tap: the row that is not narrating anything is the row that is " +
                "offering the install, and that is a different onClick",
            StreamingPackCopy.workLineTappable(null),
        )
    }

    // ------------------------------------------------------------------ helpers

    /** The English pack's real size, so the delete sentences read as the user reads them. */
    private val bytes = StreamingPackCatalog.EN.totalBytes

    private fun work(
        route: PreviewRoute,
        phase: PreviewPhase,
        soFar: Long = 0L,
        total: Long = 0L,
        reason: String? = null,
    ): PreviewWork = PreviewWork(
        language = "en",
        route = route,
        starter = PreviewStarter.PICK,
        step = PreviewStep(phase, soFar, total, reason),
    )

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
