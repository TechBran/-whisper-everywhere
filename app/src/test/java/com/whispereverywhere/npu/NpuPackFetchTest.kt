package com.whispereverywhere.npu

import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * THE PLAY FETCH FLOW'S PURE MACHINE (4.2 F5) — executed, the L5-precedent split.
 *
 * `NpuPackController` is Android-bound (AssetPackManager, Activity, a process-scoped coroutine
 * owner), so no JVM test can run it; its wiring is pinned as source by `NpuDiagTest`. Everything
 * the controller DECIDES lives in [NpuPackFetch] precisely so it can be proved here instead —
 * which status becomes which state, which error code becomes which sentence, when a progress
 * line may print. The same split, for the same reason, as `NpuImportController`/`NpuAssetImport`.
 *
 * **The library's own classes are the reference.** These tests enumerate
 * [AssetPackStatus]/[AssetPackErrorCode] REFLECTIVELY (both are plain constant-carrying classes,
 * loadable on the JVM — verified at this suite's red step), so a library upgrade that adds,
 * removes or renumbers a value fails a named assertion here rather than shipping a silent remap.
 */
class NpuPackFetchTest {

    /** Every public static int constant of a Play model class, by name. */
    private fun intConstants(clazz: Class<*>): Map<String, Int> =
        clazz.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Integer.TYPE }
            .associate { it.name to it.getInt(null) }

    private val soFar = 105_906_176L
    private val total = 901_775_360L

    // ------------------------------------------------------------------ the status machine

    @Test
    fun everyAssetPackStatusMapsToExactlyOneFetchState() {
        // THE RED (4.2 F5). `advance` is total over Int: all TEN AssetPackStatus values map to
        // exactly one honest state, and completion of DELIVERY is the start of OUR verification
        // — never Installed. The table below is keyed by the library's own field NAMES, so a
        // value this build has never heard of fails as "unmapped status", loudly, here.
        val statuses = intConstants(AssetPackStatus::class.java)
        assertEquals(
            "the library carries exactly the ten documented statuses — an eleventh means the " +
                "dependency moved and this machine must be re-specified, not left to the else arm",
            10,
            statuses.size,
        )
        val expected: Map<String, NpuPackFetch.FetchState> = mapOf(
            // UNKNOWN is a real answer Play can give and silence is not a state: the same
            // discipline as the error table — named, numbered, rendered.
            "UNKNOWN" to NpuPackFetch.FetchState.Failed("Google Play reported status 0 (unknown)"),
            "PENDING" to NpuPackFetch.FetchState.Pending,
            "DOWNLOADING" to NpuPackFetch.FetchState.Downloading(soFar, total),
            "TRANSFERRING" to NpuPackFetch.FetchState.Transferring,
            // COMPLETED means DELIVERED. The pack sits on disk unverified; our metadata
            // cross-check and stream hash start here, so the state is Verifying from byte zero.
            "COMPLETED" to NpuPackFetch.FetchState.Verifying(0, total),
            "FAILED" to NpuPackFetch.FetchState.Failed(
                NpuPackFetch.failureReason(NpuPackFetch.ERROR_NETWORK_ERROR, total)
            ),
            "CANCELED" to NpuPackFetch.FetchState.Cancelled,
            // Both consent-shaped statuses land on the SAME state: the answer to each is Play's
            // own confirmation dialog, and the card offers exactly that.
            "WAITING_FOR_WIFI" to NpuPackFetch.FetchState.NeedsConfirmation,
            "REQUIRES_USER_CONFIRMATION" to NpuPackFetch.FetchState.NeedsConfirmation,
            "NOT_INSTALLED" to NpuPackFetch.FetchState.Idle,
        )
        assertEquals(
            "every library status is mapped by name — an unmapped one is a hole in this table",
            statuses.keys,
            expected.keys,
        )
        statuses.forEach { (name, value) ->
            assertEquals(
                "AssetPackStatus.$name ($value) maps to exactly its one honest state",
                expected.getValue(name),
                NpuPackFetch.advance(value, NpuPackFetch.ERROR_NETWORK_ERROR, soFar, total),
            )
        }
        // THE UNRECOGNIZED-INT ARM. `advance` is total over Int whatever the library documents:
        // a value off the table lands in Failed with its number, never in a `when` nobody wrote
        // an else for and never in silence.
        assertEquals(
            NpuPackFetch.FetchState.Failed("Google Play reported status 99"),
            NpuPackFetch.advance(99, 0, 0L, 0L),
        )
        assertEquals(
            "and a negative surprise is rendered the same way",
            NpuPackFetch.FetchState.Failed("Google Play reported status -3"),
            NpuPackFetch.advance(-3, 0, 0L, 0L),
        )
    }

    @Test
    fun theMirroredStatusConstantsEqualTheLibrarysOwn() {
        // The machine spells statuses as its own documented constants (so the pure half never
        // imports Play classes), and THIS is what keeps the mirror honest: each constant is
        // asserted equal to the library's same-named field, so a renumbering upgrade goes red.
        val library = intConstants(AssetPackStatus::class.java)
        val mirrored = mapOf(
            "UNKNOWN" to NpuPackFetch.STATUS_UNKNOWN,
            "PENDING" to NpuPackFetch.STATUS_PENDING,
            "DOWNLOADING" to NpuPackFetch.STATUS_DOWNLOADING,
            "TRANSFERRING" to NpuPackFetch.STATUS_TRANSFERRING,
            "COMPLETED" to NpuPackFetch.STATUS_COMPLETED,
            "FAILED" to NpuPackFetch.STATUS_FAILED,
            "CANCELED" to NpuPackFetch.STATUS_CANCELED,
            "WAITING_FOR_WIFI" to NpuPackFetch.STATUS_WAITING_FOR_WIFI,
            "NOT_INSTALLED" to NpuPackFetch.STATUS_NOT_INSTALLED,
            "REQUIRES_USER_CONFIRMATION" to NpuPackFetch.STATUS_REQUIRES_USER_CONFIRMATION,
        )
        assertEquals("one mirrored constant per library status", library.keys, mirrored.keys)
        mirrored.forEach { (name, value) ->
            assertEquals("STATUS_$name mirrors the library exactly", library.getValue(name), value)
        }
    }

    @Test
    fun completedMeansDeliveredNotInstalledForEveryStatusTheLibraryCanReport() {
        // No status Play can report — documented or not — may EVER answer Installed: that state
        // exists only past our own verification (`installFromPack` returning Installed), which
        // no Play callback can testify to. The delivered-means-installed collapse is the exact
        // mutant this test exists to kill.
        (intConstants(AssetPackStatus::class.java).values + listOf(99, -3)).forEach { status ->
            assertFalse(
                "status $status must not map to Installed — delivery is not installation",
                NpuPackFetch.advance(status, 0, soFar, total)
                    is NpuPackFetch.FetchState.Installed,
            )
        }
        assertEquals(
            "COMPLETED starts OUR verification at byte zero of the pair",
            NpuPackFetch.FetchState.Verifying(0, total),
            NpuPackFetch.advance(NpuPackFetch.STATUS_COMPLETED, 0, soFar, total),
        )
    }

    @Test
    fun downloadingCarriesTheBytesPlayReported() {
        val state = NpuPackFetch.advance(NpuPackFetch.STATUS_DOWNLOADING, 0, soFar, total)
        assertEquals(NpuPackFetch.FetchState.Downloading(soFar, total), state)
        assertEquals(
            "a fresh download starts at zero of an as-yet-unknown total and must still be a state",
            NpuPackFetch.FetchState.Downloading(0L, 0L),
            NpuPackFetch.advance(NpuPackFetch.STATUS_DOWNLOADING, 0, 0L, 0L),
        )
    }

    // ------------------------------------------------------------------ the failure table

    @Test
    fun theFailureTableNamesEveryAssetPackErrorCodeInUserWords() {
        // The library's own error class is the reference, and EVERY code it declares must
        // render as a sentence a user can act on — the generic numbered fallback is reserved
        // for codes this build has never heard of. A new code in an upgraded library fails
        // here by name rather than shipping as "error -16".
        val library = intConstants(AssetPackErrorCode::class.java)
        val mirrored = mapOf(
            "NO_ERROR" to NpuPackFetch.ERROR_NO_ERROR,
            "APP_UNAVAILABLE" to NpuPackFetch.ERROR_APP_UNAVAILABLE,
            "PACK_UNAVAILABLE" to NpuPackFetch.ERROR_PACK_UNAVAILABLE,
            "INVALID_REQUEST" to NpuPackFetch.ERROR_INVALID_REQUEST,
            "DOWNLOAD_NOT_FOUND" to NpuPackFetch.ERROR_DOWNLOAD_NOT_FOUND,
            "API_NOT_AVAILABLE" to NpuPackFetch.ERROR_API_NOT_AVAILABLE,
            "NETWORK_ERROR" to NpuPackFetch.ERROR_NETWORK_ERROR,
            "ACCESS_DENIED" to NpuPackFetch.ERROR_ACCESS_DENIED,
            "INSUFFICIENT_STORAGE" to NpuPackFetch.ERROR_INSUFFICIENT_STORAGE,
            "APP_NOT_OWNED" to NpuPackFetch.ERROR_APP_NOT_OWNED,
            "CONFIRMATION_NOT_REQUIRED" to NpuPackFetch.ERROR_CONFIRMATION_NOT_REQUIRED,
            "UNRECOGNIZED_INSTALLATION" to NpuPackFetch.ERROR_UNRECOGNIZED_INSTALLATION,
            "INTERNAL_ERROR" to NpuPackFetch.ERROR_INTERNAL_ERROR,
        )
        assertEquals(
            "one mirrored constant per library error code — a code added by an upgrade must be " +
                "given words here, not inherited by the numbered fallback. MEASURED at this " +
                "task's red step: 2.3.0 declares THIRTEEN codes — PLAY_STORE_NOT_FOUND is a " +
                "Play Core 1.x code the current class no longer carries (asserted below).",
            library.keys,
            mirrored.keys,
        )
        assertFalse(
            "the 1.x PLAY_STORE_NOT_FOUND is genuinely absent from the 2.3.0 class — if an " +
                "upgrade brings it back, fold it into the mirror map above",
            library.containsKey("PLAY_STORE_NOT_FOUND"),
        )
        assertEquals(
            "and the -11 arm is kept anyway — the table is total over Int, the service side " +
                "can still surface the 1.x number, and its meaning is the sideload truth",
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_NOT_OWNED, total),
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_PLAY_STORE_NOT_FOUND, total),
        )
        mirrored.forEach { (name, value) ->
            assertEquals("ERROR_$name mirrors the library exactly", library.getValue(name), value)
        }
        library.forEach { (name, value) ->
            val reason = NpuPackFetch.failureReason(value, total)
            assertTrue("AssetPackErrorCode.$name renders words, not a blank", reason.isNotBlank())
            assertFalse(
                "AssetPackErrorCode.$name ($value) must NOT fall through to the numbered " +
                    "fallback — every known code gets user words: $reason",
                reason.contains("Google Play reported error"),
            )
        }
    }

    @Test
    fun theSideloadTruthIsTheExactCopyForTheCodesThatMeanNotInstalledFromPlay() {
        // APP_NOT_OWNED / PLAY_STORE_NOT_FOUND / API_NOT_AVAILABLE are what a sideloaded build
        // (the owner's own adb-installed debug APK included) sees on every fetch. The copy is
        // the truth stated as the PATH FORWARD — the SAF import — never a dead end, and it is
        // pinned exactly because it is the one failure the primary test device will actually
        // show. UNRECOGNIZED_INSTALLATION is the same fact in the library's newer spelling.
        val sideload = "Google Play can't deliver the model to this install — it wasn't " +
            "installed from Play. Use 'Import model pair…' below instead."
        listOf(
            NpuPackFetch.ERROR_APP_NOT_OWNED,
            NpuPackFetch.ERROR_PLAY_STORE_NOT_FOUND,
            NpuPackFetch.ERROR_API_NOT_AVAILABLE,
            NpuPackFetch.ERROR_UNRECOGNIZED_INSTALLATION,
        ).forEach { code ->
            assertEquals("code $code carries the sideload answer, exactly",
                sideload, NpuPackFetch.failureReason(code, total))
        }
    }

    @Test
    fun insufficientStorageNamesThePairsSize() {
        val reason = NpuPackFetch.failureReason(NpuPackFetch.ERROR_INSUFFICIENT_STORAGE, total)
        assertTrue(
            "the storage refusal names the download's real size — 901 MB here — because " +
                "'not enough space' without a number is not actionable: $reason",
            reason.contains("901 MB"),
        )
        assertTrue("and says what to do about it: $reason", reason.contains("retry"))
        val sizeless = NpuPackFetch.failureReason(NpuPackFetch.ERROR_INSUFFICIENT_STORAGE, 0L)
        assertFalse(
            "when Play reported no size there is no number to name, and inventing one would be " +
                "worse than omitting it: $sizeless",
            sizeless.contains(" 0 MB"),
        )
        assertTrue("the sizeless form is still a sentence: $sizeless", sizeless.isNotBlank())
    }

    @Test
    fun networkErrorCarriesTheExactRetryCopy() {
        assertEquals(
            "The download couldn't reach Google Play. Check your connection and retry.",
            NpuPackFetch.failureReason(NpuPackFetch.ERROR_NETWORK_ERROR, total),
        )
    }

    @Test
    fun anUnknownErrorCodeRendersItsNumberNeverSilence() {
        assertEquals("Google Play reported error -777", NpuPackFetch.failureReason(-777, total))
        assertEquals(
            "the fallback is total over Int in both directions",
            "Google Play reported error 42",
            NpuPackFetch.failureReason(42, 0L),
        )
    }

    @Test
    fun theFailedStateIsTheRefusalCarrierAndCarriesTheTablesWordsVerbatim() {
        // The certification's carrier ruling (supersession (b)): a fetch that fails — including
        // a fetched-but-corrupt pack — surfaces its reason through the fetch card's OWN Failed
        // state. The `unavailableReason` machinery keeps its existing job (a tier that
        // INSTALLED and then declined at load) and structurally cannot carry a pack that never
        // installed. So Failed.reason must be the error table's sentence VERBATIM — the card
        // renders it and nothing else.
        val sideload = NpuPackFetch.advance(
            NpuPackFetch.STATUS_FAILED, NpuPackFetch.ERROR_APP_NOT_OWNED, 0L, total,
        )
        assertEquals(
            NpuPackFetch.FetchState.Failed(
                NpuPackFetch.failureReason(NpuPackFetch.ERROR_APP_NOT_OWNED, total)
            ),
            sideload,
        )
        val storage = NpuPackFetch.advance(
            NpuPackFetch.STATUS_FAILED, NpuPackFetch.ERROR_INSUFFICIENT_STORAGE, 0L, total,
        ) as NpuPackFetch.FetchState.Failed
        assertTrue(
            "the pair's size survives into the carried state: ${storage.reason}",
            storage.reason.contains("901 MB"),
        )
    }

    // ------------------------------------------------------------------ the tier homes

    @Test
    fun packByTierSpellsTheTwoCommittedPackNamesThroughTheTierIdHomes() {
        // The pack names are committed facts (F4's modules pin `packName.set(…)`); the tier ids
        // are spelled through their HOMES — the npu constant and the turbo spec — so this map
        // cannot drift from either side without a compile error or this red.
        assertEquals(
            mapOf(
                NpuAssetImport.TIER_ID to "npu_small",
                NpuModelSpec.TURBO.tierId to "npu_turbo",
            ),
            NpuPackFetch.PACK_BY_TIER,
        )
        assertEquals(
            "every paired tier has a pack and no pack serves a tier the catalog cannot pair — " +
                "the next npu-class tier joins this map the day it joins PAIRED_TIER_IDS",
            NpuAssetImport.PAIRED_TIER_IDS.toSet(),
            NpuPackFetch.PACK_BY_TIER.keys,
        )
    }

    // ------------------------------------------------------------------ progress decisions

    @Test
    fun shouldLogProgressAllowsAtMostOneLinePerDecile() {
        // The throttle is the SHELL's, but the decision is pure and lives here: one `pack:`
        // line per status transition plus at most one per 10% of progress — a per-tick line
        // would bury the run-book's landmarks under ~200 lines per fetch.
        assertTrue("the first tick of a fetch is a landmark", NpuPackFetch.shouldLogProgress(-1, 0))
        assertFalse("4% after 0% is the same decile", NpuPackFetch.shouldLogProgress(0, 4))
        assertFalse("9% after 0% is still the same decile", NpuPackFetch.shouldLogProgress(0, 9))
        assertTrue("10% after 0% is a new decile", NpuPackFetch.shouldLogProgress(0, 10))
        assertFalse("19% after 10% is not", NpuPackFetch.shouldLogProgress(10, 19))
        assertTrue("20% after 10% is", NpuPackFetch.shouldLogProgress(10, 20))
        assertTrue("a decile can be SKIPPED and still logs once", NpuPackFetch.shouldLogProgress(10, 47))
        assertTrue("completion always logs", NpuPackFetch.shouldLogProgress(99, 100))
        assertFalse("but only once", NpuPackFetch.shouldLogProgress(100, 100))
    }

    @Test
    fun pctIsTotalSafeAndClamped()  {
        assertEquals("an unknown total is 0%, never a division by zero", 0, NpuPackFetch.pct(0L, 0L))
        assertEquals(0, NpuPackFetch.pct(0L, total))
        assertEquals(25, NpuPackFetch.pct(50L, 200L))
        assertEquals(11, NpuPackFetch.pct(soFar, total))
        assertEquals(100, NpuPackFetch.pct(total, total))
        assertEquals(
            "a soFar past total (a resumed fetch's bookkeeping) clamps rather than reporting 104%",
            100,
            NpuPackFetch.pct(total + 1L, total),
        )
    }

    @Test
    fun statusWordsAreOneGreppableWordPerState() {
        // The `pack:` line's status field — one lowercase greppable token per state, so
        // `pack: fetch tier=npu-turbo` lines parse on spaces forever.
        val words = mapOf<NpuPackFetch.FetchState, String>(
            NpuPackFetch.FetchState.Idle to "idle",
            NpuPackFetch.FetchState.Pending to "pending",
            NpuPackFetch.FetchState.Downloading(soFar, total) to "downloading",
            NpuPackFetch.FetchState.Transferring to "transferring",
            NpuPackFetch.FetchState.Verifying(0, total) to "verifying",
            NpuPackFetch.FetchState.Installed to "installed",
            NpuPackFetch.FetchState.Failed("x") to "failed",
            NpuPackFetch.FetchState.Cancelled to "cancelled",
            NpuPackFetch.FetchState.NeedsConfirmation to "needs-confirmation",
        )
        words.forEach { (state, word) ->
            assertEquals(word, NpuPackFetch.statusWord(state))
        }
        assertEquals(
            "every state has its own word — two states sharing one would make the line lie",
            words.size,
            words.values.toSet().size,
        )
        words.values.forEach { word ->
            assertTrue("'$word' is one token", !word.contains(" ") && word == word.lowercase())
        }
    }

    // ------------------------------------------------------------------ the empty delivery

    @Test
    fun theEmptyDeliveryRefusalNamesTheTruthAndTheImportPath() {
        // A device outside every census group receives the EMPTY default variant (F4's
        // fail-safe): the pack "arrives" carrying no metadata and no model. The refusal states
        // that as Play's answer — not as corruption, not as a mystery — and names the way
        // forward, because a dead end on the fetch card is the failure the copy rules forbid.
        val refusal = NpuPackFetch.emptyDeliveryRefusal()
        assertTrue(
            "the missing metadata IS the empty-default signature and the copy says so: $refusal",
            refusal.contains("Google Play delivered no model for this device"),
        )
        assertTrue(
            "the import fallback is named as the path forward: $refusal",
            refusal.contains("Import model pair…"),
        )
        assertTrue("and the no-install promise is stated, truthfully: $refusal",
            refusal.contains("Nothing was installed"))
    }
}
