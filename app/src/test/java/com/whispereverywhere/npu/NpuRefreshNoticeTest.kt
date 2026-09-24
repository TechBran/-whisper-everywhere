package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE REFRESH NOTICE (4.15) — its copy pinned exactly, its claim held to the house rules, its
 * post-or-skip decision executed as a truth table, and the receiver that renders it pinned as
 * source.
 *
 * The copy half is the `ModelTierCopyTest` discipline applied to three new strings: exact
 * `assertEquals` pins (so any wording change is a decision somebody made), the size DERIVED from
 * the census rather than restated (so the next census moves the text instead of leaving a number
 * for a pin to chase), and the claim rules — one comparative, no superlative, no absolute, no
 * device scope, no multiplier, no other app.
 */
class NpuRefreshNoticeTest {

    private val gen3 = NpuFleetCensus.familyById("8gen3")!!

    // ------------------------------------------------------------------ the copy, pinned exactly

    @Test
    fun theThreeStringsArePinnedWordForWord() {
        assertEquals("Your AI-chip model has a faster version", NpuRefreshNotice.NOTIFICATION_TITLE)
        assertEquals(
            "Your AI-chip model has a faster version — download it again (one-time).",
            NpuRefreshNotice.IN_APP_NOTE,
        )
        // The 8 Gen 3's turbo row — the Fold6's — is the brief's own example, to the megabyte.
        assertEquals(
            "One-time download of about 824 MB. Tap to open Whisper Everywhere and get it.",
            NpuRefreshNotice.notificationText(
                NpuRefreshNotice.downloadBytesFor(gen3, "npu-turbo")!!
            ),
        )
        assertEquals(
            "and the small tier's, from its own row",
            "One-time download of about 285 MB. Tap to open Whisper Everywhere and get it.",
            NpuRefreshNotice.notificationText(NpuRefreshNotice.downloadBytesFor(gen3, "npu")!!),
        )
    }

    @Test
    fun theSizeIsEveryFamilysOwnVendorZipRoundedToTheNearestMegabyte() {
        // Derived, per family, from the one census field that measures the transfer: the vendor
        // zip's Content-Length, asserted at HEAD on every measure run. Never a literal.
        for (a in NpuFleetCensus.artifacts) {
            val family = NpuFleetCensus.familyById(a.familyId)!!
            val bytes = NpuRefreshNotice.downloadBytesFor(family, a.tierId)
            assertEquals("${a.familyId}/${a.tierId}: the size is the row's vendor zip", a.vendorZipBytes, bytes)
            val mb = NpuRefreshNotice.downloadMb(bytes!!)
            assertTrue(
                "${a.familyId}/${a.tierId}: $mb MB is the nearest whole SI megabyte to ${a.vendorZipBytes} B",
                kotlin.math.abs(mb * 1_000_000.0 - a.vendorZipBytes) <= 500_000.0,
            )
            assertTrue(
                "${a.familyId}/${a.tierId}: the text states that number",
                NpuRefreshNotice.notificationText(bytes).contains("about $mb MB."),
            )
        }
        // Three rows by value, so the rounding rule is a decision and not an accident: 823.72 is
        // 824 (floor would say 823, against the sheet's own "about 824 MB"), 828.03 is 828, and
        // 821.90 is 822.
        assertEquals(824L, NpuRefreshNotice.downloadMb(823_721_812L))
        assertEquals(828L, NpuRefreshNotice.downloadMb(828_034_458L))
        assertEquals(822L, NpuRefreshNotice.downloadMb(821_903_663L))
        assertEquals("the half rounds up", 2L, NpuRefreshNotice.downloadMb(1_500_000L))
        assertEquals(1L, NpuRefreshNotice.downloadMb(1_499_999L))
    }

    @Test
    fun noPackMeansNoSize_andNoSizeMeansNoNotice() {
        assertNull("no family", NpuRefreshNotice.downloadBytesFor(null, "npu-turbo"))
        assertNull("no tier", NpuRefreshNotice.downloadBytesFor(gen3, null))
        assertNull("a CPU rung has no pack row", NpuRefreshNotice.downloadBytesFor(gen3, "small-q8"))
    }

    @Test
    fun theInstalledSizeIsNotTheDownloadAndIsNotStated() {
        // "About 824 MB" is what crosses the network (the vendor zip, the best measured proxy for
        // Play's deflated transfer); the pair INSTALLS at 981,968,552 B. The sentence names the
        // download, and neither the installed size nor the pair's byte count may leak into it.
        val text = NpuRefreshNotice.notificationText(NpuRefreshNotice.downloadBytesFor(gen3, "npu-turbo")!!)
        assertFalse(text.contains("981"))
        assertFalse(text.contains("982"))
        assertTrue("and it says it is an estimate", text.contains("about "))
    }

    @Test
    fun theCopyCarriesNoLiteralSize() {
        // The derivation is the only source of the number: a literal in the object would be a
        // second record of a census value, and the one that drifted would be the one users read.
        val source = read("src/main/java/com/whispereverywhere/npu/NpuRefreshNotice.kt")
        listOf("824", "285", "828", "822", "981").forEach { n ->
            assertEquals(
                "no live line of NpuRefreshNotice.kt spells $n — the size is derived from the census",
                0,
                liveLineCount(source, n),
            )
        }
    }

    // ------------------------------------------------------------------ the claim rules

    private val userFacing = listOf(
        NpuRefreshNotice.NOTIFICATION_TITLE,
        NpuRefreshNotice.IN_APP_NOTE,
        NpuRefreshNotice.notificationText(823_721_812L),
    )

    @Test
    fun faster_isTheOneComparative_andNothingIsASuperlativeAnAbsoluteOrAMultiplier() {
        // THE APP'S CLAIM RULES (ModelTierCopyTest's vocabulary): "faster" compares the user's
        // model with its own previous version — the vendor's AI Hub profiles of v0.63.0, turbo
        // encoder 4-7x on every family, and the owner's ruling on that figure — and it is the
        // only speed word allowed. No superlative, no absolute, no multiplier (our own measurement
        // does not stand behind one yet), no other product.
        val superlative = Regex("\\b(fastest|best|highest|most|quickest|top)\\b")
        val absolutes = listOf(
            "instant", "real-time", "realtime", "no delay", "no lag", "zero lag",
            "guaranteed", "always", "never", "unlimited",
        )
        val multiplier = Regex("\\b\\d+\\s*(x|×|times)\\b|\\b\\d+\\s*-\\s*\\d+\\s*x\\b")
        val crossApp = listOf("other app", "any app", "competitor", "gboard", "google", "apple", "siri", "otter")
        userFacing.forEach { s ->
            val l = s.lowercase()
            assertFalse("superlative in <<$s>>", superlative.containsMatchIn(l))
            absolutes.forEach { assertFalse("absolute '$it' in <<$s>>", l.contains(it)) }
            assertFalse("multiplier in <<$s>>", multiplier.containsMatchIn(l))
            crossApp.forEach { assertFalse("'$it' in <<$s>>", l.contains(it)) }
        }
        assertEquals("the title's one comparative", 1, Regex("\\bfaster\\b").findAll(NpuRefreshNotice.NOTIFICATION_TITLE.lowercase()).count())
        assertEquals("the in-app note's one comparative", 1, Regex("\\bfaster\\b").findAll(NpuRefreshNotice.IN_APP_NOTE.lowercase()).count())
        assertFalse(
            "the notification text makes no speed claim at all — the title already did",
            NpuRefreshNotice.notificationText(823_721_812L).lowercase().contains("fast"),
        )
    }

    @Test
    fun theCopyIsDeviceNeutral() {
        // The notice reaches six census families: it names no phone, no chip, and no "this
        // device" / "every device" scope (ModelTierCopyTest's ABSOLUTE_SCOPE vocabulary). The
        // app's own name is removed first — "Whisper Everywhere" is a name, not a scope claim.
        val scope = Regex(
            "\\b(every|any|all|each|this|that|these|those|your|most|a|an|the)\\s+(phone|device|tablet|handset|hardware)s?\\b" +
                "|\\b(phones|devices|tablets|handsets)\\b" +
                "|\\b(everywhere|anywhere)\\b",
        )
        val devices = listOf("fold", "galaxy", "pixel", "snapdragon", "sm8", "8 gen", "8gen", "elite", "tab s")
        userFacing.forEach { s ->
            val l = s.replace("Whisper Everywhere", "").lowercase()
            assertFalse("device scope in <<$s>>", scope.containsMatchIn(l))
            devices.forEach { assertFalse("device name '$it' in <<$s>>", l.contains(it)) }
        }
    }

    // ------------------------------------------------------------------ the decision, executed

    private val record = NpuRedownload("npu-turbo", "8gen3:c9403eaa")

    private fun decide(
        trigger: NpuRefreshNotice.Trigger = NpuRefreshNotice.Trigger.PACKAGE_REPLACED,
        installedModelNull: Boolean = true,
        selectedTierId: String? = "npu-turbo",
        record: NpuRedownload? = this.record,
        notifiedCensusKey: String? = null,
        downloadBytes: Long? = 823_721_812L,
        canPost: Boolean = true,
    ) = NpuRefreshNotice.decide(
        trigger, installedModelNull, selectedTierId, record, notifiedCensusKey, downloadBytes, canPost,
    )

    @Test
    fun theTruthTableIsExhaustive() {
        // Every combination of the seven inputs, against an independently spelled rule: post
        // exactly when the model is missing, a record stands for the SELECTED tier, the device
        // has a pack to get, notifications may be posted, and — on an update only — this event
        // has not already been announced.
        var posts = 0
        var total = 0
        for (trigger in NpuRefreshNotice.Trigger.values())
            for (missing in listOf(true, false))
                for (selected in listOf("npu-turbo", "small-q8", null))
                    for (rec in listOf(record, null))
                        for (notified in listOf(null, record.censusKey, "8gen3:older"))
                            for (bytes in listOf(823_721_812L, null))
                                for (canPost in listOf(true, false)) {
                                    total++
                                    val expectPost = missing && rec != null && rec.tierId == selected &&
                                        bytes != null && canPost &&
                                        !(trigger == NpuRefreshNotice.Trigger.PACKAGE_REPLACED && notified == rec.censusKey)
                                    val d = NpuRefreshNotice.decide(trigger, missing, selected, rec, notified, bytes, canPost)
                                    assertEquals(
                                        "trigger=$trigger missing=$missing selected=$selected record=$rec " +
                                            "notified=$notified bytes=$bytes canPost=$canPost",
                                        expectPost,
                                        d is NpuRefreshNotice.Decision.Post,
                                    )
                                    if (d is NpuRefreshNotice.Decision.Post) {
                                        posts++
                                        assertEquals(NpuRefreshNotice.NOTIFICATION_TITLE, d.title)
                                        assertEquals(NpuRefreshNotice.notificationText(bytes!!), d.text)
                                    }
                                }
        assertEquals(2 * 2 * 3 * 2 * 3 * 2 * 2, total)
        assertEquals("update posts for two of three notified states, boot for all three", 2 + 3, posts)
    }

    @Test
    fun eachSkipNamesItsOwnReason() {
        fun reason(d: NpuRefreshNotice.Decision) = (d as NpuRefreshNotice.Decision.Skip).reason
        assertEquals(NpuRefreshNotice.SKIP_INSTALLED, reason(decide(installedModelNull = false)))
        assertEquals("never when the pref is clear", NpuRefreshNotice.SKIP_NO_RECORD, reason(decide(record = null)))
        assertEquals(NpuRefreshNotice.SKIP_NOT_SELECTED, reason(decide(selectedTierId = "small-q8")))
        assertEquals(NpuRefreshNotice.SKIP_NO_PACK, reason(decide(downloadBytes = null)))
        assertEquals(NpuRefreshNotice.SKIP_ALREADY_POSTED, reason(decide(notifiedCensusKey = record.censusKey)))
        assertEquals(NpuRefreshNotice.SKIP_NO_PERMISSION, reason(decide(canPost = false)))
        val tokens = listOf(
            NpuRefreshNotice.SKIP_INSTALLED, NpuRefreshNotice.SKIP_NO_RECORD, NpuRefreshNotice.SKIP_NOT_SELECTED,
            NpuRefreshNotice.SKIP_NO_PACK, NpuRefreshNotice.SKIP_ALREADY_POSTED, NpuRefreshNotice.SKIP_NO_PERMISSION,
        )
        assertEquals("six distinct reasons", 6, tokens.toSet().size)
        tokens.forEach { assertTrue("'$it' is one greppable word", it.matches(Regex("[a-z]+(-[a-z]+)*"))) }
    }

    @Test
    fun onceOnTheUpdate_againOnEveryBoot_neverWithoutARecord() {
        // The lifecycle the receiver lives through, driven with the receiver's own bookkeeping:
        // the notified key is written only after a post.
        var notified: String? = null
        var current: NpuRedownload? = record
        fun on(trigger: NpuRefreshNotice.Trigger): Boolean {
            val d = decide(trigger = trigger, record = current, notifiedCensusKey = notified)
            if (d is NpuRefreshNotice.Decision.Post) notified = current!!.censusKey
            return d is NpuRefreshNotice.Decision.Post
        }
        val update = NpuRefreshNotice.Trigger.PACKAGE_REPLACED
        val boot = NpuRefreshNotice.Trigger.BOOT_COMPLETED
        assertTrue("the update that brought the refresh posts", on(update))
        assertFalse("a second update with the same event pending does not repeat it", on(update))
        assertTrue("a boot while the record stands posts again, in the restart notice's place", on(boot))
        assertTrue("and every boot after", on(boot))
        // The pair lands: the shared finalise clears the record AND the notified key.
        current = null
        notified = null
        assertFalse("no record, no notice — on a boot", on(boot))
        assertFalse("or on an update", on(update))
        // A later census makes the NEW pair stale: a new event, a new key, a new notice.
        current = NpuRedownload("npu-turbo", "8gen3:a-later-row")
        assertTrue("a new stale event is announced on its own update", on(update))
        assertFalse("once", on(update))
    }

    @Test
    fun theInAppNoteShowsExactlyWhileTheRecordStandsForTheSelectedTierAndAPackExists() {
        assertTrue(NpuRefreshNotice.showsInAppNote(record, "npu-turbo", 823_721_812L))
        assertFalse("cleared with the pref", NpuRefreshNotice.showsInAppNote(null, "npu-turbo", 823_721_812L))
        assertFalse("the user picked another tier", NpuRefreshNotice.showsInAppNote(record, "small-q8", 823_721_812L))
        assertFalse("no selection", NpuRefreshNotice.showsInAppNote(record, null, 823_721_812L))
        assertFalse("nothing to get on this device", NpuRefreshNotice.showsInAppNote(record, "npu-turbo", null))
    }

    @Test
    fun theDiagLineNamesTheTriggerAndTheVerdictInOneWordEach() {
        assertEquals(
            "npu: refresh notice trigger=update post",
            NpuDiag.refreshNotice(NpuRefreshNotice.Trigger.PACKAGE_REPLACED, decide()),
        )
        assertEquals(
            "npu: refresh notice trigger=boot skip=no-permission",
            NpuDiag.refreshNotice(NpuRefreshNotice.Trigger.BOOT_COMPLETED, decide(canPost = false)),
        )
    }

    // ------------------------------------------------------------------ the receiver, as source

    private fun read(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private val receiver: String by lazy { read("src/main/java/com/whispereverywhere/receiver/BootReceiver.kt") }
    private val app: String by lazy { read("src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt") }

    private fun count(haystack: String, needle: String) = haystack.split(needle).size - 1

    private fun liveLineCount(haystack: String, needle: String): Int =
        haystack.lineSequence().count { line ->
            val trimmed = line.trimStart()
            val commented = trimmed.startsWith("//") || trimmed.startsWith("/*") ||
                trimmed.startsWith("*")
            !commented && line.contains(needle)
        }

    private fun liveIndexOfOrFail(haystack: String, what: String, needle: String): Int {
        var offset = 0
        for (line in haystack.lineSequence()) {
            val trimmed = line.trimStart()
            val commented =
                trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")
            val at = line.indexOf(needle)
            if (!commented && at >= 0) return offset + at
            offset += line.length + 1
        }
        throw AssertionError("missing from $what as a LIVE line: <<$needle>>")
    }

    /** One member to its own closer, by the four-space rule these classes close on. */
    private fun body(haystack: String, what: String, declaration: String): String {
        val start = haystack.indexOf(declaration)
        assertTrue("missing from $what: <<$declaration>>", start >= 0)
        val close = haystack.indexOf("\n    }\n", start)
        assertTrue("the closing brace of <<$declaration>> moved", close > start)
        return haystack.substring(start, close + "\n    }\n".length)
    }

    @Test
    fun theModelMissingArmPostsTheNoticeInsteadOfReturningSilently() {
        val receive = body(receiver, "BootReceiver.kt", "    override fun onReceive(context: Context, intent: Intent) {")
        assertEquals(
            "BOTH broadcasts route the model-missing verdict to the notice, each with its own trigger",
            1,
            count(receive, "postRefreshNoticeIfDue(context, NpuRefreshNotice.Trigger.BOOT_COMPLETED)"),
        )
        assertEquals(1, count(receive, "postRefreshNoticeIfDue(context, NpuRefreshNotice.Trigger.PACKAGE_REPLACED)"))
        assertEquals(
            "each from the model-missing arm, and from nowhere else",
            2,
            liveLineCount(receive, "Eligibility.MODEL_MISSING ->"),
        )
        assertEquals(
            "the restore path is untouched: boot posts the restart notice, the update starts the service",
            1,
            liveLineCount(receive, "Eligibility.RESTORE -> postRestartNotification(context)"),
        )
        assertEquals(1, liveLineCount(receive, "FloatingBubbleService.start(context)"))
        val eligible = body(receiver, "BootReceiver.kt", "    private fun eligible(context: Context): Eligibility {")
        assertEquals(
            "the one model-missing verdict is the installedModel() == null branch — the old silent return",
            1,
            liveLineCount(eligible, "return Eligibility.MODEL_MISSING"),
        )
        assertTrue(
            "and it is the LAST check: the notice reaches only a user whose bubble would otherwise have come back",
            liveIndexOfOrFail(eligible, "eligible", "Manifest.permission.RECORD_AUDIO") <
                liveIndexOfOrFail(eligible, "eligible", "return Eligibility.MODEL_MISSING"),
        )
    }

    @Test
    fun theNoticeIsWhatTheDecisionSaysOnItsOwnChannelWithItsOwnId() {
        val notice = body(
            receiver, "BootReceiver.kt",
            "    private fun postRefreshNoticeIfDue(context: Context, trigger: NpuRefreshNotice.Trigger) {",
        )
        assertEquals("the decision is the pure one", 1, liveLineCount(notice, "val decision = NpuRefreshNotice.decide("))
        assertEquals(
            "fed the device family's own pack size for the RECORD's tier",
            1,
            liveLineCount(notice, "downloadBytes = NpuRefreshNotice.downloadBytesFor(app.npuSocFamily, record?.tierId),"),
        )
        assertEquals(
            "and the SAME permission guard the restart notice uses",
            1,
            liveLineCount(notice, "canPost = canPostNotifications(context),"),
        )
        assertEquals(1, liveLineCount(body(receiver, "BootReceiver.kt", "    private fun postRestartNotification(context: Context) {"), "if (canPostNotifications(context)) {"))
        assertEquals("the verdict is logged natively, where a Play build still prints it", 1, liveLineCount(notice, "WhisperNative.diag(NpuDiag.refreshNotice(trigger, decision))"))
        assertEquals("only a Post posts", 1, liveLineCount(notice, "if (decision !is NpuRefreshNotice.Decision.Post || record == null) return"))
        assertEquals(
            "on the model-updates channel",
            1,
            liveLineCount(notice, "NotificationCompat.Builder(context, WhisperEverywhereApp.MODEL_UPDATES_CHANNEL_ID)"),
        )
        assertEquals("with the decision's own words", 1, liveLineCount(notice, ".setContentTitle(decision.title)"))
        assertEquals(1, liveLineCount(notice, ".setContentText(decision.text)"))
        assertEquals(
            "the copy lives in NpuRefreshNotice alone — the receiver spells none of it",
            0,
            liveLineCount(receiver, "faster version") + liveLineCount(receiver, "One-time download"),
        )
        assertEquals("auto-cancel", 1, liveLineCount(notice, ".setAutoCancel(true)"))
        assertEquals("default priority", 1, liveLineCount(notice, ".setPriority(NotificationCompat.PRIORITY_DEFAULT)"))
        assertEquals(
            "the tap opens the app WITHOUT starting a bubble: the gate shows the Download button",
            0,
            liveLineCount(notice, "EXTRA_START_BUBBLE"),
        )
        assertEquals(1, liveLineCount(notice, "val tap = Intent(context, MainActivity::class.java).apply {"))
        assertEquals(
            "its PendingIntent has its OWN request code — sharing the restart notice's 0 would make the " +
                "two one PendingIntent, and FLAG_UPDATE_CURRENT would rewrite the other's extras",
            1,
            liveLineCount(notice, "context, MODEL_UPDATE_NOTIFICATION_ID, tap,"),
        )
        assertEquals(
            "a distinct notification id",
            1,
            liveLineCount(notice, "NotificationManagerCompat.from(context).notify(MODEL_UPDATE_NOTIFICATION_ID, notification)"),
        )
        assertEquals(1, liveLineCount(receiver, "private const val MODEL_UPDATE_NOTIFICATION_ID = 1004"))
        assertEquals(1, liveLineCount(receiver, "private const val RESTART_NOTIFICATION_ID = 1002"))
        assertTrue(
            "the once-per-event key is written only AFTER the notify — a dropped notice is not an announced one",
            liveIndexOfOrFail(notice, "postRefreshNoticeIfDue", "notify(MODEL_UPDATE_NOTIFICATION_ID, notification)") <
                liveIndexOfOrFail(notice, "postRefreshNoticeIfDue", "prefs.npuRefreshNotifiedCensusKey = record.censusKey"),
        )
    }

    @Test
    fun theOnboardingFlowTheGateLandsOnShowsTheSentenceWhileTheRecordStands() {
        // The app-wide gate is firstRunStartDestination(hasModel): no installed model routes to
        // first_run, so a phone whose selected pair the sweep removed opens THIS screen — its
        // permissions step first, the Download button on its engines step. HomeScreen's missing-
        // engine row and the Settings picker are reachable only from Home, which the gate never
        // shows a modelless install.
        val gate = read("src/main/java/com/whispereverywhere/ui/screens/ModeDashboard.kt")
        assertEquals(1, liveLineCount(gate, "if (hasModel) ROUTE_HOME else ROUTE_FIRST_RUN"))
        val flow = read("src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt")
        assertEquals(
            "the visibility is the pure rule, once",
            1,
            liveLineCount(flow, "NpuRefreshNotice.showsInAppNote(refreshRecord, selectedTierForNote, refreshPackBytes)"),
        )
        assertEquals(
            "COLLECTED, not remembered: the record clears when the pair lands and the sentence must go with it",
            1,
            liveLineCount(flow, "val refreshRecord by notePrefs.npuRedownloadFlow.collectAsState()"),
        )
        assertEquals(
            "and the selection too: a pick of another tier takes the sentence away",
            1,
            liveLineCount(flow, "val selectedTierForNote by notePrefs.selectedModelIdFlow.collectAsState()"),
        )
        assertEquals(
            "the pack size is the device family's, so the sentence never names a download the phone cannot get",
            1,
            liveLineCount(flow, "NpuRefreshNotice.downloadBytesFor("),
        )
        assertEquals("the card renders the pinned sentence", 1, liveLineCount(flow, "NpuRefreshNotice.IN_APP_NOTE,"))
        assertEquals(
            "and the screen spells none of the copy itself",
            0,
            liveLineCount(flow, "faster version"),
        )
        // The step content's `when` — not the top bar's, which also switches on the step — is the
        // one whose first arm is the permissions step.
        val note = flow.indexOf("                if (showRefreshNote) {\n                    RefreshNote()")
        val steps = flow.indexOf("                when (step) {\n                    Step.PERMISSIONS -> PermissionsStep(")
        assertTrue("the note block is where the step content starts", note >= 0)
        assertTrue("the step content's when", steps >= 0)
        assertTrue(
            "ABOVE every step, so the permissions step the user meets first already says why",
            note < steps,
        )
    }

    @Test
    fun theModelUpdatesChannelIsItsOwnAtDefaultImportance() {
        assertEquals(1, liveLineCount(app, "const val MODEL_UPDATES_CHANNEL_ID = \"model_updates\""))
        val create = body(app, "WhisperEverywhereApp.kt", "    private fun createNotificationChannel() {")
        assertEquals(
            "created beside the service channel, at DEFAULT — the notice has to be seen in the shade",
            1,
            count(
                create,
                "            MODEL_UPDATES_CHANNEL_ID,\n" +
                    "            getString(R.string.model_updates_channel_name),\n" +
                    "            NotificationManager.IMPORTANCE_DEFAULT",
            ),
        )
        assertEquals(
            "and the service channel stays LOW: its standing notice must never make a sound",
            1,
            count(
                create,
                "            NOTIFICATION_CHANNEL_ID,\n" +
                    "            getString(R.string.notification_channel_name),\n" +
                    "            NotificationManager.IMPORTANCE_LOW",
            ),
        )
        assertEquals(1, liveLineCount(create, "notificationManager.createNotificationChannel(modelUpdates)"))
        val strings = read("src/main/res/values/strings.xml")
        assertEquals(1, count(strings, "<string name=\"model_updates_channel_name\">Model updates</string>"))
        assertEquals(1, count(strings, "<string name=\"model_updates_channel_description\">"))
    }
}
