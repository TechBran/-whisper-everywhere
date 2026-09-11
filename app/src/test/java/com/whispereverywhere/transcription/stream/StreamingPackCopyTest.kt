package com.whispereverywhere.transcription.stream

import com.whispereverywhere.transcription.stream.StreamingPackCopy.AnswerGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            StreamingPackCopy.installed(en, bytes),
            StreamingPackCopy.settingsInstallFromPack(bytes),
            StreamingPackCopy.settingsInstallFetch(bytes),
            StreamingPackCopy.installDownload(en, bytes),
            StreamingPackCopy.SETTINGS_REPAIR,
            StreamingPackCopy.SETTINGS_REPAIR_FROM_PACK,
            StreamingPackCopy.settingsRepairFetch(bytes),
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
            // (4.5.0 Task 4) The TIER axis's own pair — the sentence a device that can never arm
            // reads instead of an offer.
            StreamingPackCopy.NO_TIER_TITLE,
            StreamingPackCopy.NO_TIER_SUBTITLE,
            StreamingPackCopy.DELETE_TITLE,
            StreamingPackCopy.PROGRESS_STARTING,
            StreamingPackCopy.PROGRESS_INSTALLING,
            StreamingPackCopy.INSTALL_FAILED,
            // 4.4.1: Home's card, the discovery surface. Its words join the scan because the
            // scan's contract is "everything the user can read, from every surface" — and this
            // is the surface most users will ever read about the previewer.
            StreamingPackCopy.CARD_TITLE,
            StreamingPackCopy.cardWorking(en, true),
            // (4.5.0 Task 4) ...and its other half, which is the one card sentence a device that
            // can never arm can read.
            StreamingPackCopy.cardWorking(en, false),
            StreamingPackCopy.CARD_INSTALLED_TITLE,
            StreamingPackCopy.cardInstalled(en),
            StreamingPackCopy.cardLanguageNote(en),
            StreamingPackCopy.CARD_DISMISS,
            StreamingPackCopy.CARD_ANSWER_PLAY,
            // (4.5.0 Task 3c) The strip above the language selector. Its running phases delegate
            // to `workLine` (already in the scan below), so what joins here is the one sentence
            // of its own — the READY receipt.
            StreamingPackCopy.selectorReady(en),
            // (4.5.0 Task 3d) The picker's own two: the deal, and one language's size badge.
            StreamingPackCopy.PICKER_DEAL,
            StreamingPackCopy.pickerRowBadge(StreamingPackCatalog.EN.totalBytes),
        ) + everyState.map { StreamingPackCopy.settingsTitle(it, en) } +
            everyState.map { StreamingPackCopy.settingsSubtitle(it, en, bytes) } +
            everyState.map { StreamingPackCopy.cardOffer(it, en, bytes) } +
            everyState.map { StreamingPackCopy.cardAction(it, en) } +
            // (4.5.0 Task 1) The one observable's own sentences, from every route and every
            // phase, and the delete row's five. The scan's contract is "everything the user can
            // read, from every surface", and after this task these are the words BOTH surfaces
            // render for work in flight.
            PreviewDeleteCase.entries.map {
                StreamingPackCopy.deleteSubtitle(it, en, StreamingPackCatalog.EN.totalBytes)
            } +
            PreviewRoute.entries.flatMap { route ->
                PreviewPhase.entries.flatMap { phase ->
                    // (fix round 2, review r2's N1) Every SURFACE ANSWER too, because each of
                    // them is a sentence a reader can read — and the third one was added for a
                    // surface that had been rendering another surface's instruction.
                    AnswerGesture.entries.mapNotNull { answer ->
                        StreamingPackCopy.workLine(
                            PreviewWork(
                                language = "en",
                                route = route,
                                starter = PreviewStarter.PICK,
                                step = PreviewStep(phase, 12_000_000L, 72_654_782L, reason = "a refusal."),
                            ),
                            answer = answer,
                        )
                    }
                }
            }

    // ------------------------------------------------------------------ the strings themselves

    @Test fun theStringsArePinnedVerbatim() {
        assertEquals("Live words while you speak (English)", StreamingPackCopy.featureTitle(en))
        assertEquals(
            "Installed (73 MB). Words appear on the bubble as you speak English; the typed transcript is unchanged.",
            StreamingPackCopy.installed(en, bytes),
        )
        assertEquals(
            "Included with the app (73 MB) and already on this device — nothing to fetch. Words appear on the bubble as you talk; the typed transcript is still the speech model's.",
            StreamingPackCopy.settingsInstallFromPack(bytes),
        )
        assertEquals(
            "The app's own 73 MB model, fetched from Google Play over your connection when you ask for it — never from a third party. Words appear on the bubble as you talk; the typed transcript is still the speech model's.",
            StreamingPackCopy.settingsInstallFetch(bytes),
        )
        assertEquals(
            "Download a 73 MB English preview model. Words appear on the bubble as you talk; the typed transcript is still the speech model's.",
            StreamingPackCopy.installDownload(en, bytes),
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
            StreamingPackCopy.settingsRepairFetch(bytes),
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
        assertTrue(StreamingPackCopy.settingsInstallFromPack(bytes).contains(badge))
        assertTrue(StreamingPackCopy.settingsInstallFetch(bytes).contains(badge))
        assertTrue(StreamingPackCopy.installDownload(en, bytes).contains(badge))
        assertTrue(StreamingPackCopy.installed(en, bytes).contains(badge))
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
        assertTrue(StreamingPackCopy.settingsInstallFromPack(bytes).contains("typed transcript"))
        assertTrue(StreamingPackCopy.settingsInstallFetch(bytes).contains("typed transcript"))
        assertTrue(StreamingPackCopy.installDownload(en, bytes).contains("typed transcript"))
        assertTrue(StreamingPackCopy.installed(en, bytes).contains("typed transcript"))
        assertTrue(StreamingPackCopy.SETTINGS_DISABLED_ON_DEVICE.contains("transcripts are unaffected"))
    }

    // ------------------------------------------------------------------ the amendment's own rule

    @Test fun thePlayRoutesPromiseNoThirdPartyDownload_andOnlyTheDeliveredOneSaysItIsAlreadyHere() {
        // The amendment, verbatim: "the previewer's install row says 'included with the app' on
        // Play builds (it is fetched, not downloaded from a third party)". Its parenthetical is
        // about PROVENANCE, and naming Play is what keeps it: no Play row may offer a
        // third-party download.
        for (state in listOf(StreamingPackState.PackDelivered, StreamingPackState.PackFetchable)) {
            val sub = StreamingPackCopy.settingsSubtitle(state, en, bytes)
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
        val delivered = StreamingPackCopy.settingsSubtitle(StreamingPackState.PackDelivered, en, bytes)
        assertTrue("the delivered row is the one that really is already here: $delivered", delivered.contains("Included with the app"))
        assertTrue(delivered.contains("already on this device"))
        val fetchable = StreamingPackCopy.settingsSubtitle(StreamingPackState.PackFetchable, en, bytes)
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
            StreamingPackCopy.settingsSubtitle(
                StreamingPackState.Repair(StreamingPackState.PackFetchable), en, bytes,
            )
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
        val sub = StreamingPackCopy.settingsSubtitle(StreamingPackState.Downloadable, en, bytes)
        assertTrue("the fallback is honest about being one: $sub", sub.startsWith("Download a 73 MB"))
        val saysDownload = everyState.filter {
            StreamingPackCopy.settingsSubtitle(it, en, bytes).lowercase().contains("download")
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
            assertTrue("$state has a subtitle", StreamingPackCopy.settingsSubtitle(state, en, bytes).isNotBlank())
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
            offers.map { StreamingPackCopy.settingsSubtitle(it, en, bytes) }.distinct().size,
        )
        assertEquals(
            "and so do the three repairs — a repair from the delivered pack costs no network " +
                "and must not be offered as a download",
            3,
            offers.drop(1)
                .map { StreamingPackCopy.settingsSubtitle(StreamingPackState.Repair(it), en, bytes) }
                .distinct().size,
        )
    }

    @Test fun aDamagedInstallAlwaysSaysSoWhateverWouldRepairIt() {
        for (via in listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            val sub = StreamingPackCopy.settingsSubtitle(StreamingPackState.Repair(via), en, bytes)
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

    @Test fun theTwoStandingSentencesOfWorkInFlightArePinned() {
        assertEquals("Starting…", StreamingPackCopy.PROGRESS_STARTING)
        assertEquals("Verifying and installing…", StreamingPackCopy.PROGRESS_INSTALLING)
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
            StreamingPackCopy.cardWorking(en, true),
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
            StreamingPackCopy.cardWorking(es, true),
            StreamingPackCopy.cardInstalled(es),
            StreamingPackCopy.cardLanguageNote(es),
            StreamingPackCopy.featureTitle(es),
            StreamingPackCopy.installed(es, bytes),
            StreamingPackCopy.installDownload(es, bytes),
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

    // ------------------------------------------------ the DEVICE axis (4.5.0 Task 4)

    @Test fun aDeviceThatCanNeverArmIsToldTheRuleAndIsOfferedNothing() {
        assertEquals(
            "Live words need an on-device speech model",
            StreamingPackCopy.NO_TIER_TITLE,
        )
        assertEquals(
            "Live words appear only while transcription runs on this device, and this device " +
                "has no speech model. Download a speech model and live words follow the " +
                "language you pick. Your typed transcript is unchanged.",
            StreamingPackCopy.NO_TIER_SUBTITLE,
        )
        // IT NAMES THE RULE, NOT THE MISSING FILE — and the rule is true of BOTH mechanisms
        // (`PreviewUnreachable`'s KDoc): nothing transcribes on this device at all, and a
        // configured cloud provider additionally makes every session one `localPreviewArms`
        // refuses. Naming the rule is what keeps this sentence true word for word if the open
        // ruling ever widens its input to a device that HAS a tier and runs every session in the
        // cloud.
        assertTrue(
            "the rule: words only while transcription runs here",
            StreamingPackCopy.NO_TIER_SUBTITLE.contains(
                "only while transcription runs on this device",
            ),
        )
        // NOTHING IS FOR SALE ON THIS DEVICE — 4.4.1 pass 3's ITEM 1, one axis over: 73 MB buys
        // it nothing at all, so neither sentence carries a size, a pack or an instruction to get
        // one.
        for (s in listOf(StreamingPackCopy.NO_TIER_TITLE, StreamingPackCopy.NO_TIER_SUBTITLE)) {
            assertFalse("<<$s>> must not name a size", s.contains("MB"))
            assertFalse("<<$s>> must not name the preview model as a thing to get", s.contains("preview model"))
            assertFalse("<<$s>> must not name a language", s.contains(en))
            assertFalse("nor Google Play", s.contains("Google Play"))
        }
        assertTrue(
            "and the transcript stays out of the trade, like every other sentence here",
            StreamingPackCopy.NO_TIER_SUBTITLE.contains("typed transcript"),
        )
    }

    @Test fun theCaveatRowAnswersTheTIERFirstAndDelegatesTheSELECTIONUnchanged() {
        // ONE pair of functions over `PreviewUnreachable`, because two surfaces draw this row
        // (the Settings section and Home's language card) and a second table is how two
        // surfaces come to answer one pair of facts differently.
        assertEquals(
            StreamingPackCopy.NO_TIER_TITLE,
            StreamingPackCopy.unreachableTitle(PreviewUnreachable.NO_LOCAL_TIER, es),
        )
        assertEquals(
            StreamingPackCopy.NO_TIER_SUBTITLE,
            StreamingPackCopy.unreachableSubtitle(PreviewUnreachable.NO_LOCAL_TIER, es),
        )
        // The tier arm IGNORES the language, and that is the precedence made visible: it is about
        // the device, so it reads identically for a pick, for a gap and for Auto.
        for (name in listOf(es, "French", null)) {
            assertEquals(
                StreamingPackCopy.NO_TIER_TITLE,
                StreamingPackCopy.unreachableTitle(PreviewUnreachable.NO_LOCAL_TIER, name),
            )
            assertEquals(
                StreamingPackCopy.NO_TIER_SUBTITLE,
                StreamingPackCopy.unreachableSubtitle(PreviewUnreachable.NO_LOCAL_TIER, name),
            )
        }
        // ...and the SELECTION arm is 4.4.1's own pair, byte for byte. This task moved no
        // validated copy; it gave the pair one more reader and one fact that outranks it.
        for (name in listOf(es, "French", null)) {
            assertEquals(
                StreamingPackCopy.noLiveWordsTitle(name),
                StreamingPackCopy.unreachableTitle(PreviewUnreachable.NO_PACK_FOR_SELECTION, name),
            )
            assertEquals(
                StreamingPackCopy.noLiveWordsSubtitle(name),
                StreamingPackCopy.unreachableSubtitle(
                    PreviewUnreachable.NO_PACK_FOR_SELECTION, name,
                ),
            )
        }
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

    @Test fun theCardsOfferIsTheSAMEPerSourceTableTheRowUses() {
        // The 4.4.1 brief's rule: "Copy must be true per the delivered-vs-fetch distinction
        // StreamingPackCopy already makes". The strongest form of that is not a second set of
        // sentences held to the same rule by a second test — it is the SAME table, so a card
        // that promised "included with the app" for an undelivered on-demand pack (fix round
        // 1's B1, on the row) is not expressible.
        for (state in everyState) {
            assertEquals(
                "the card's body for $state",
                StreamingPackCopy.settingsSubtitle(state, en, bytes),
                StreamingPackCopy.cardOffer(state, en, bytes),
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
        assertTrue(StreamingPackCopy.cardWorking(en, true).contains("typed transcript is unchanged"))
        assertTrue(StreamingPackCopy.cardInstalled(en).contains("typed transcript is unchanged"))
        for (state in listOf(
            StreamingPackState.PackDelivered,
            StreamingPackState.PackFetchable,
            StreamingPackState.Downloadable,
        )) {
            assertTrue(
                "the offer body carries the additive promise for $state",
                StreamingPackCopy.cardOffer(state, en, bytes).contains("typed transcript"),
            )
            assertTrue(
                "and its action names the language: ${StreamingPackCopy.cardAction(state, en)}",
                StreamingPackCopy.cardAction(state, en).contains("English"),
            )
        }
        assertTrue(
            "the language caveat is ONE sentence used on every card state, so the trade cannot " +
                "be stated two ways on two cards",
            StreamingPackCopy.cardWorking(en, true).contains(StreamingPackCopy.cardLanguageNote(en)),
        )
        // (4.5.0 Task 4) ...AND ON A DEVICE THAT CAN NEVER ARM IT IS THE ONE HALF THAT WAS FALSE.
        // WORKING is the one card state reachable with no on-device tier (`card` refuses the
        // announcement on it, and `decide` refuses before it can answer OFFER), because
        // `workInFlight` is a disjunct on purpose: hiding a transfer the user started would hide
        // their own action from them (D17). So the arriving clause has to survive and the promise
        // has to go.
        val workingNoTier = StreamingPackCopy.cardWorking(en, false)
        assertEquals(
            "The English preview model is arriving now; the typed transcript is unchanged. " +
                "Live words appear only while transcription runs on this device, and this " +
                "device has no speech model.",
            workingNoTier,
        )
        assertTrue(
            "the transfer is still narrated, word for word as it is with a tier — a running " +
                "73 MB is never hidden (ruling 3c)",
            workingNoTier.startsWith(
                "The English preview model is arriving now; the typed transcript is unchanged.",
            ),
        )
        assertFalse(
            "and the promise is gone: 'English shows them' cannot be said on a phone where " +
                "nothing transcribes on-device at all",
            workingNoTier.contains(StreamingPackCopy.cardLanguageNote(en)),
        )
        assertTrue(
            "what replaces it is the SAME clause the section's caveat row and the delete row " +
                "carry, so one fact has one spelling",
            workingNoTier.contains(
                "Live words appear only while transcription runs on this device, and this " +
                    "device has no speech model.",
            ) && StreamingPackCopy.NO_TIER_SUBTITLE.startsWith(
                "Live words appear only while transcription runs on this device, and this " +
                    "device has no speech model.",
            ),
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
        val working = StreamingPackCopy.cardWorking(en, true).lowercase()
        for (fragment in listOf("settings", "tap", "open the")) {
            assertFalse("<<$working>> contains '$fragment'", working.contains(fragment))
        }
    }

    // ------------------------------- the delete row's SIX cases (4.5.0 Task 1, Task 4)

    @Test fun theDeleteRowHasATrueSentenceForEachOfItsSixCases() {
        // 4.4.1 rendered ONE sentence across every fact the row can be looking at, and four of
        // them made it false: the model is installed for a language the user is NOT transcribing
        // (so "live words stop" is already untrue), the SWITCH is off (same — review r1's B2),
        // the install is DAMAGED (same), or a write is in flight (so "Frees 73 MB" frees nothing
        // at all). Five cases, five sentences, and the case is derived from the one observable
        // plus the switch rather than guessed at here.
        assertEquals(
            "Frees 73 MB. Live words stop; the typed transcript is unchanged.",
            StreamingPackCopy.deleteSubtitle(PreviewDeleteCase.LIVE, en, bytes),
        )
        assertEquals(
            "Frees 73 MB. Live words are already off: 'Show live words' is switched off. " +
                "Deleting the English model stops nothing; the typed transcript is unchanged.",
            StreamingPackCopy.deleteSubtitle(PreviewDeleteCase.OFF_SWITCH, en, bytes),
        )
        assertTrue(
            "and it quotes the switch's OWN title, so a rename cannot leave the sentence " +
                "pointing at a control that no longer says that",
            StreamingPackCopy.deleteSubtitle(PreviewDeleteCase.OFF_SWITCH, en, bytes)
                .contains("'${StreamingPackCopy.SWITCH_TITLE}'"),
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
        // (4.5.0 Task 4) The sixth: the reason this screen's own controls cannot reach.
        assertEquals(
            "Frees 73 MB. Live words appear only while transcription runs on this device, and " +
                "this device has no speech model. Deleting the English model stops nothing; " +
                "the typed transcript is unchanged.",
            StreamingPackCopy.deleteSubtitle(PreviewDeleteCase.OFF_TIER, en, bytes),
        )
        assertTrue(
            "and it is the SAME clause the section's caveat row and Home's working card carry, " +
                "so one fact has one spelling across three surfaces",
            StreamingPackCopy.deleteSubtitle(PreviewDeleteCase.OFF_TIER, en, bytes).contains(
                "Live words appear only while transcription runs on this device, and this " +
                    "device has no speech model.",
            ),
        )
        assertFalse(
            "it does NOT borrow the other two's 'already off' opening: that implies a state " +
                "this screen could turn back on, and the remedy is a speech-model download",
            StreamingPackCopy.deleteSubtitle(PreviewDeleteCase.OFF_TIER, en, bytes)
                .contains("already off"),
        )
        assertEquals(
            "Nothing to free yet: the English model is being written right now. Deleting " +
                "becomes available when it finishes; the typed transcript is unchanged either way.",
            StreamingPackCopy.deleteSubtitle(PreviewDeleteCase.WORKING, en, bytes),
        )
    }

    @Test fun onlyTheCasesThatActuallyFreeBytesClaimToFreeThem() {
        for (case in listOf(
            PreviewDeleteCase.LIVE,
            PreviewDeleteCase.OFF_SWITCH,
            PreviewDeleteCase.OFF_SELECTION,
            PreviewDeleteCase.DAMAGED,
            PreviewDeleteCase.OFF_TIER,
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
        // Five of the six are ABOUT a particular language's model, and say so; LIVE is about
        // the pack the user is actually transcribing in, where the rows around it already name it.
        for (case in listOf(
            PreviewDeleteCase.OFF_SWITCH,
            PreviewDeleteCase.OFF_SELECTION,
            PreviewDeleteCase.DAMAGED,
            PreviewDeleteCase.WORKING,
            PreviewDeleteCase.OFF_TIER,
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
        // (fix round 2, review r2's B1a) THE ABANDON HAS A SENTENCE, and that is the whole point
        // of it being a phase: with a null here the Settings row saw no work line, fell into its
        // OFFER arm and drew a live 73 MB tap that the actuator then refused in silence.
        assertEquals(
            "Cancelling the preview model…",
            StreamingPackCopy.workLine(work(PreviewRoute.PLAY_FETCH, PreviewPhase.ABANDONED)),
        )
        assertEquals(
            "and it is route-NEUTRAL, because no bytes are moving in either direction: the " +
                "provenance verb belongs to the two phases that are carrying them",
            1,
            PreviewRoute.entries
                .map { StreamingPackCopy.workLine(work(it, PreviewPhase.ABANDONED)) }
                .distinct().size,
        )
    }

    @Test fun theRouteChoosesTheVerbSoTheProvenancePromiseSurvivesTheProgressLine() {
        // The amendment's parenthetical — "it is fetched, not downloaded from a third party" — is
        // the whole reason the offer is a table. The PROGRESS line has to keep it: a Play fetch
        // that said "Downloading" would contradict settingsInstallFetch's "never from a third
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

    @Test fun theAskBecomesAReceiptWhereTheRowHasNoTapToGive() {
        // Review r3's H3-B3 and (fix round 2) review r2's N1, on one parameter. AWAITING_ANSWER is
        // tappable BY PHASE, but a SURFACE is what has a tap: the Settings row withholds its own
        // once the selection moves off this pack's language, and the strip above the selector has
        // no `onClick` at all and is pinned never to grow one. Three surface answers, three
        // sentences, one fact under all three.
        val awaiting = work(PreviewRoute.PLAY_FETCH, PreviewPhase.AWAITING_ANSWER)
        val asking = StreamingPackCopy.workLine(awaiting, answer = AnswerGesture.ON_THIS_SURFACE)
        val telling = StreamingPackCopy.workLine(awaiting, answer = AnswerGesture.RE_PICK)
        val stating = StreamingPackCopy.workLine(awaiting, answer = AnswerGesture.NONE)
        assertTrue("with a tap, it asks for the tap", asking?.contains("tap to answer") == true)
        assertTrue("without one, it must NOT ask for a tap", telling?.contains("tap") == false)
        assertTrue(
            "and it must say what unlocks it — the selection is the only key",
            telling?.contains("Pick that language again") == true,
        )
        // (fix round 2, review r2's N1) The third form is for a surface with NO gesture and no
        // gesture its reader can perform on it: it states the fact and instructs nothing. Both
        // other forms would be false there, and one of them shipped in fix round 1 — on a row
        // pinned to have no tap, which is the defect CARD_ANSWER_PLAY was created for.
        assertEquals(
            "the no-gesture form is the bare fact, and it is the SAME fact the other two open " +
                "with — one Play state, one description of it",
            "Google Play needs your confirmation before it fetches the preview model.", stating,
        )
        assertTrue("it asks for no tap", stating?.contains("tap") == false)
        assertTrue(
            "and it instructs no re-pick either: on the surface this form exists for, the " +
                "re-pick is INERT (the record's language is the selection)",
            stating?.contains("Pick that language again") == false,
        )
        for (form in listOf(asking, telling, stating)) {
            assertTrue("the reason is stated in every form: <<$form>>", form?.contains("confirmation") == true)
        }
        val everyOther = PreviewPhase.entries.filterNot { it == PreviewPhase.AWAITING_ANSWER }
        for (answer in AnswerGesture.entries) {
            assertEquals(
                "every OTHER phase reads identically for <<$answer>> — this parameter buys " +
                    "exactly one sentence, and a caller that forgets it changes nothing else",
                everyOther.map {
                    StreamingPackCopy.workLine(work(PreviewRoute.PLAY_FETCH, it), answer = AnswerGesture.ON_THIS_SURFACE)
                },
                everyOther.map { StreamingPackCopy.workLine(work(PreviewRoute.PLAY_FETCH, it), answer = answer) },
            )
        }
        assertEquals(
            "and the default is the asking one, so the card — which draws CARD_ANSWER_PLAY — " +
                "reads as it did",
            asking,
            StreamingPackCopy.workLine(awaiting),
        )
    }

    @Test fun onlyTheTerminalRetryAndPlaysOwnDialogAreTappable() {
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
            // A cancel already accepted: a tap would re-enter the install the user has just
            // refused, which is the opposite of what the row is a receipt for (fix round 2).
            PreviewPhase.ABANDONED,
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

    // --------------------------------- above the language selector (4.5.0 Task 3c)

    /**
     * The strip's line, with the ordinary case as the default: the record's own language is the
     * selected one (which is WHY the record exists), the switch is on and a tier is installed.
     * Every test below states only the fact it is about.
     */
    private fun strip(
        work: PreviewWork,
        language: String = en,
        selectedLanguage: String? = "en",
        showLiveWords: Boolean = true,
        localTierInstalled: Boolean = true,
        disabledLanguages: Set<String> = emptySet(),
    ): String? = StreamingPackCopy.selectorLine(
        work = work,
        language = language,
        selectedLanguage = selectedLanguage,
        showLiveWords = showLiveWords,
        localTierInstalled = localTierInstalled,
        disabledLanguages = disabledLanguages,
    )

    @Test fun theSelectorStripSaysWhatIsArrivingForEveryPhaseThatIsArriving() {
        // Ruling 3c: *"incorporate the status for that model being downloaded right there above
        // the language selector … and know that their language is ready for selection."* The
        // strip's title names the language (`featureTitle`, the same head the Settings in-flight
        // row uses), so this line is the PHASE and the bytes — delegated to the one work line
        // rather than re-worded, because a parallel table of Play's phases is how two surfaces
        // come to describe one transfer differently.
        for (phase in PreviewPhase.entries) {
            val line = strip(
                work(PreviewRoute.PLAY_FETCH, phase, 12_000_000L, 72_654_782L, reason = "no room."),
            )
            when (phase) {
                // The user's own no — the dismissal was its own receipt.
                PreviewPhase.CANCELLED -> assertNull("$phase must be silent here", line)
                // The one sentence the work line has no phase for.
                PreviewPhase.INSTALLED ->
                    assertEquals(StreamingPackCopy.selectorReady(en), line)
                // Everything else is the ONE work line, verbatim, in the form this SURFACE can
                // honestly render — here the record's language is the selected one, which is the
                // case an AWAITING_ANSWER record exists in, and the strip has no gesture at all.
                else -> assertEquals(
                    "$phase must be the work line itself, not a second wording of it",
                    StreamingPackCopy.workLine(
                        work(
                            PreviewRoute.PLAY_FETCH, phase, 12_000_000L, 72_654_782L,
                            reason = "no room.",
                        ),
                        answer = AnswerGesture.NONE,
                    ),
                    line,
                )
            }
        }
        assertEquals(
            "and the bytes reach it, through the catalog's one rounding rule",
            "Fetching the preview model: 12 of 73 MB",
            strip(work(PreviewRoute.PLAY_FETCH, PreviewPhase.DOWNLOADING, 12_000_000L, 72_654_782L)),
        )
    }

    @Test fun theReadyReceiptIsRefusedWhereTheFEATURECannotKeepIt() {
        // (fix round 1, review r1's B2) `selectorReady` is a PRESENT-TENSE PROMISE — *"words
        // appear on the bubble whenever you pick it"* — rendered from a TERMINAL record that the
        // board keeps. `PreviewAutoFetch.card` twelve lines away on the same screen answers
        // `Card.NONE` on both of these before it will say anything, each guard earned by a review
        // round, and the two composables narrate the same pack. So the strip asks them too.
        val installed = work(PreviewRoute.PLAY_FETCH, PreviewPhase.INSTALLED)
        assertEquals(
            "the ordinary case still says it",
            StreamingPackCopy.selectorReady(en), strip(installed),
        )
        assertNull(
            "the switch is OFF: no word will reach the bubble, so \"words appear\" is false — " +
                "4.4.1 review r1's B2, on the card, re-committed on this surface until now",
            strip(installed, showLiveWords = false),
        )
        assertNull(
            "no on-device TIER: nothing transcribes on this device at all, so the promise is " +
                "permanently untrue however installed the pack is (4.4.1 pass 3's ITEM 3, the " +
                "card's own reason; the mechanism is PreviewUnreachable's KDoc and it is not " +
                "the previewer's gate, which has no tier term)",
            strip(installed, localTierInstalled = false),
        )
        assertNull(strip(installed, showLiveWords = false, localTierInstalled = false))
        // (fix round 2, review r2's N2) ...AND THE PREVIEWER'S OWN VERDICT, which is the fact
        // NOBODY says. `StreamingPreviewEngine.disable` takes a language off for the rest of the
        // process on a load that threw, a failed canary, a missing clip or three decode throws —
        // and the bytes stay installed and valid, so `state()` answers `Installed` and the
        // Settings row is right to call it installed. The only false claim is this promise, and
        // until now the strip was the one surface still making it.
        assertNull(
            "English is OFF for this process: no word can appear until the app restarts, so " +
                "\"words appear on the bubble whenever you pick it\" is false — with the pack " +
                "still on disk and nothing else in the app saying so",
            strip(installed, disabledLanguages = setOf("en")),
        )
        assertEquals(
            "and it is PER LANGUAGE, like the engine's own set: another language going off says " +
                "nothing about this one",
            StreamingPackCopy.selectorReady(en),
            strip(installed, disabledLanguages = setOf("fr", "de")),
        )
        assertNull(
            "the verdict is read off the RECORD's language, never the selection's — the record " +
                "outlives the selection and each row is about its own pack",
            strip(installed, selectedLanguage = "fr", disabledLanguages = setOf("en")),
        )
        // ...and the three facts silence ONLY the promise. A transfer that is actually happening
        // is narrated whoever started it and whatever the device can arm: hiding a running 73 MB
        // from the user is the silent spend ruling 3c exists to close. A repair fetch for a
        // language this process has disabled is exactly that case — the bytes are moving.
        for (phase in PreviewPhase.entries) {
            if (phase == PreviewPhase.INSTALLED || phase == PreviewPhase.CANCELLED) continue
            val running = work(PreviewRoute.PLAY_FETCH, phase, 12_000_000L, 72_654_782L, reason = "no room.")
            assertEquals(
                "$phase: an in-flight line is a fact, not a promise",
                strip(running),
                strip(
                    running,
                    showLiveWords = false,
                    localTierInstalled = false,
                    disabledLanguages = setOf("en"),
                ),
            )
            assertNotNull("$phase must still say something", strip(running, showLiveWords = false))
        }
    }

    @Test fun theStripInstructsNoGestureItHasNotGotAndNoRePickThatWouldChangeNothing() {
        // (fix round 2, review r2's N1) THE STRIP HAS NO GESTURE AT ALL — no `onClick`, no
        // `clickable`, no `Button`, pinned to zero by
        // `LivePreviewSelectorStripPinTest.theStripDecidesNothingAndActuatesNothing` — so its own
        // answer to *"does this row have a tap"* is always false and can never be anything else.
        //
        // Fix round 1 fixed the wrong half: it stopped the strip instructing an INERT re-pick
        // on-selection (right) by having it instruct a TAP instead (wrong, and the ordinary case
        // on this surface — an AWAITING_ANSWER record exists BECAUSE that language was picked).
        // That is the defect CARD_ANSWER_PLAY was created for, one card down: *"it left a user
        // who back-pressed out of Play's dialog on a note reading 'tap to answer' with nothing to
        // tap"*. And it is reachable with no gesture anywhere on the screen: with *"Show live
        // words"* off, `PreviewAutoFetch.card` answers Card.NONE and takes the button with it,
        // while this line is deliberately ungated because the transfer is real.
        val awaiting = work(PreviewRoute.PLAY_FETCH, PreviewPhase.AWAITING_ANSWER)
        val onSelection = strip(awaiting, selectedLanguage = "en")
        assertEquals(
            "on-selection the line is the bare fact: no tap on this row, and a re-pick of the " +
                "already-selected language emits nothing at all",
            "Google Play needs your confirmation before it fetches the preview model.",
            onSelection,
        )
        for (elsewhere in listOf("fr", "auto", null)) {
            val offSelection = strip(awaiting, selectedLanguage = elsewhere)
            assertTrue(
                "selected=$elsewhere: the selection has moved off this pack, so re-picking it " +
                    "really does move the selection, emit, and re-raise Play's dialog — and the " +
                    "selector that does it is immediately below this strip",
                offSelection?.contains("Pick that language again") == true,
            )
        }
        // The whole-surface claim, because one cell is what fix round 1 got wrong: NOTHING this
        // strip can render asks for a tap, in any phase, at any selection, on or off. The strip
        // is pinned to have no tap to give.
        for (phase in PreviewPhase.entries) {
            for (selected in listOf("en", "fr", "auto", null)) {
                for (tier in listOf(true, false)) {
                    for (switch in listOf(true, false)) {
                        val line = strip(
                            work(PreviewRoute.PLAY_FETCH, phase, 12_000_000L, 72_654_782L, reason = "no room."),
                            selectedLanguage = selected,
                            showLiveWords = switch,
                            localTierInstalled = tier,
                        )
                        assertFalse(
                            "$phase/selected=$selected/tier=$tier/switch=$switch <<$line>>: a " +
                                "surface with no onClick may not instruct a tap",
                            line?.contains("tap to answer") == true,
                        )
                    }
                }
            }
        }
    }

    @Test fun aFailedArrivalStaysOnTheStripBecauseNothingElseWouldSayIt() {
        // Under 3b the user CAUSED this transfer by picking, so the place they picked is the place
        // that owes them the news — the card's retry offer is reached by a different route and a
        // user who scrolled past it would read nothing. The refusal is rendered VERBATIM, for the
        // work line's own reason: the shell has already re-told it in this feature's words.
        assertEquals(
            "There is not enough room for the preview model.",
            strip(
                work(
                    PreviewRoute.PLAY_FETCH, PreviewPhase.FAILED,
                    reason = "There is not enough room for the preview model.",
                ),
            ),
        )
    }

    @Test fun theReadySentenceConfirmsRatherThanInstructsAndNamesItsLanguage() {
        assertEquals(
            "English is ready: words appear on the bubble whenever you pick it, and the typed " +
                "transcript is unchanged.",
            StreamingPackCopy.selectorReady(en),
        )
        assertEquals(
            "Spanish is ready: words appear on the bubble whenever you pick it, and the typed " +
                "transcript is unchanged.",
            StreamingPackCopy.selectorReady(es),
        )
        assertTrue(
            "it says the language is READY, which is the ruling's own word for the end of this " +
                "strip's job",
            StreamingPackCopy.selectorReady(en).contains("ready"),
        )
        assertFalse(
            "and it does not INSTRUCT: the pack only ever arrives for a language the user has " +
                "already picked, so \"pick English\" would tell them to do what they just did " +
                "(cardInstalled's own rule)",
            StreamingPackCopy.selectorReady(en).startsWith("Pick "),
        )
        assertTrue(
            "\"whenever you pick it\" is true for the user transcribing in it now AND for one " +
                "who has since moved on — which is reachable, because the record outlives the " +
                "selection",
            StreamingPackCopy.selectorReady(en).contains("whenever you pick it"),
        )
        assertTrue(
            "and it keeps the additive promise, like every other sentence in this object",
            StreamingPackCopy.selectorReady(en).contains("typed transcript is unchanged"),
        )
    }

    // --------------------------------- the picker states the deal (4.5.0 Task 3d)

    @Test fun thePickerStatesTheDealVerbatimAndCarriesNoFigureOfItsOwn() {
        // Owner ruling 3d: *"we could just say that in the copy for the drop down for the multi
        // languages, we could just say that when you switch language, a new light model will be
        // downloaded, and it will be used as your preview model."*
        assertEquals(
            "Switch to a language with a preview model and that model is downloaded and becomes " +
                "your preview model — words appear on the bubble as you speak. The menu names " +
                "the size of each language that has one; your typed transcript is the same " +
                "either way.",
            StreamingPackCopy.PICKER_DEAL,
        )
        // THE FIGURE IS THE ONE THING THIS SENTENCE MUST NOT CARRY. The owner said "sixty
        // megabytes" twice; the real sizes are English 73 MB, German 71 and French 128, so a
        // number in a sentence about "whichever language you switch to" is wrong for most of
        // them. The brief: *"use the pack's OWN size, never a fixed number."*
        assertFalse("no size at all: $en", StreamingPackCopy.PICKER_DEAL.contains("MB"))
        assertFalse(Regex("\\d").containsMatchIn(StreamingPackCopy.PICKER_DEAL))
        assertTrue(
            "it says the switch DOWNLOADS something, which is the half of ruling 3b a user has " +
                "to be told before they tap",
            StreamingPackCopy.PICKER_DEAL.contains("downloaded"),
        )
        assertTrue(
            "and that the thing downloaded becomes their preview model, which is the owner's own " +
                "second clause",
            StreamingPackCopy.PICKER_DEAL.contains("becomes your preview model"),
        )
        assertTrue(
            "and it keeps the typed transcript out of the trade",
            StreamingPackCopy.PICKER_DEAL.contains("typed transcript is the same either way"),
        )
        assertFalse(
            "it names no language either — the menu below it names all of them, and a literal " +
                "here would be a lie the day a second row lands",
            StreamingPackCopy.PICKER_DEAL.contains(en),
        )
    }

    @Test fun theRowBadgeIsThePacksOwnSizeAndCannotBeALiteral() {
        assertEquals(
            "Live words · 73 MB",
            StreamingPackCopy.pickerRowBadge(StreamingPackCatalog.EN.totalBytes),
        )
        // The three real sizes from the qualification table, each rounded by the ONE rule. This is
        // the assertion that fails if anyone re-introduces a shared badge: English 73, German 71,
        // French 128 — one constant would be wrong for two of them.
        assertEquals("Live words · 71 MB", StreamingPackCopy.pickerRowBadge(71_000_000L))
        assertEquals("Live words · 128 MB", StreamingPackCopy.pickerRowBadge(128_000_000L))
        for (b in listOf(72_654_782L, 71_000_000L, 128_000_000L)) {
            assertTrue(
                "every badge rounds through the catalog's one rule, so a row and its progress " +
                    "line can never disagree about the same pack's size",
                StreamingPackCopy.pickerRowBadge(b).contains(StreamingPackCatalog.sizeBadge(b)),
            )
        }
        assertTrue(
            "and it names the feature, because a bare size beside a language name says nothing " +
                "about what the size is for",
            StreamingPackCopy.pickerRowBadge(72_654_782L).startsWith("Live words"),
        )
    }

    @Test fun aLanguageWithNoPackStillSaysSoAndIsNotCollapsedIntoAutos() {
        // Ruling 3d's last clause, and it is a REFUSAL to add copy: *"A language with no pack
        // still says so — 4.4.1's AF8 sentence stands and must not be collapsed into Auto's,
        // because a missing model and a deliberate Auto are different facts about the world."*
        // So the two sentences are still two, they are still the same two, and the badge above
        // does not invent a third ("no model") for fifty rows to wear.
        assertEquals(
            "Auto's arm is unchanged",
            StreamingPackCopy.AUTO_NO_LIVE_WORDS,
            StreamingPackCopy.noLiveWordsSubtitle(null),
        )
        assertFalse(
            "and the gap's is still a different sentence",
            StreamingPackCopy.noLiveWordsSubtitle(es) == StreamingPackCopy.AUTO_NO_LIVE_WORDS,
        )
        assertTrue(
            "the gap's names its language, so it cannot describe one language under another's name",
            StreamingPackCopy.noLiveWordsSubtitle(es).contains(es),
        )
        assertEquals(
            "and the catalogue is what tells them apart — null for Auto and for every language " +
                "with no row, which is the predicate both the Settings row and the picker read",
            null,
            StreamingPackCatalog.forLanguage("auto") ?: StreamingPackCatalog.forLanguage(null),
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
}
