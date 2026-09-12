package com.whispereverywhere.transcription.stream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE STANDING CAVEAT, over the whole of its two inputs (4.5.0 Task 4).
 *
 * The enumeration this task exists for has four axes — {selection} × {packs installed} ×
 * {tier installed} × {cloud-only} — and this is the one decision that answers the first and the
 * third TOGETHER, which is the whole reason it exists: 4.4.1 answered the selection axis (*"no
 * language may be offered a model that has already been decided cannot arm for it"*) and left the
 * DEVICE axis unanswered, so a phone no live word can ever reach was still offered its 73 MB and
 * still told that words would appear. (Not a phone that *"can never run the previewer"* — review
 * r2's nit 3: on such a phone the previewer warms, the tee is built and the gate arms; what never
 * happens is that audio reaches it, because the session dies at connect. [PreviewUnreachable]'s
 * KDoc is the one home for that, and it defines the shorthand the rest of the feature uses.)
 *
 * The last two tests here are not about the function at all: they hold the two FACTS this axis
 * rests on to one home each, because stating a model and then applying it by editing the sites
 * one reviewer happened to cite is how the refuted version survived a whole fix round in six
 * places (review r2's B2).
 */
class PreviewUnreachableTest {

    private val bools = listOf(false, true)

    @Test fun theTierOutranksTheSelectionEverywhereInTheProduct() {
        for (tier in bools) for (pack in bools) {
            val answer = PreviewUnreachable.of(
                localTierInstalled = tier,
                hasPackForSelection = pack,
            )
            val expected = when {
                // The tier is FIRST and it is absolute. With no on-device speech model nothing
                // transcribes on this device at all — the session dies at connect, which is the
                // mechanism `PreviewUnreachable`'s KDoc states and NOT the previewer's gate — and
                // NO pick can change that, so naming the selection there would tell the user to
                // do something that cannot help them.
                !tier -> PreviewUnreachable.NO_LOCAL_TIER
                !pack -> PreviewUnreachable.NO_PACK_FOR_SELECTION
                else -> null
            }
            assertEquals("tier=$tier pack=$pack", expected, answer)
        }
    }

    @Test fun bothFactsMissingIsTheTIERsCaseAndNotTheSelectionS() {
        // The cell that makes the ordering load-bearing rather than tidy: a cloud-only user
        // standing on Auto. `noLiveWordsSubtitle(null)` instructs them to *"pick your
        // transcription language to see words on the bubble as you speak"* — which is false for
        // them on every language, so the instruction has to be outranked rather than merely
        // joined.
        assertEquals(
            PreviewUnreachable.NO_LOCAL_TIER,
            PreviewUnreachable.of(localTierInstalled = false, hasPackForSelection = false),
        )
    }

    @Test fun aDeviceThatCanArmAndAPickWithAPackIsNotBlockedAtAll() {
        assertNull(PreviewUnreachable.of(localTierInstalled = true, hasPackForSelection = true))
    }

    @Test fun theEnumIsTheTwoStandingFactsAndNoMomentaryOne() {
        // Two values, deliberately: a STANDING fact about this device or this selection, which is
        // what a caveat row may be drawn from. The momentary facts (a session running, a batch
        // job, a transfer in flight, the switch, the previewer's per-process verdict) are answered
        // by `PreviewAutoFetch.decide`, `PreviewAutoFetch.card` and
        // `StreamingPackCopy.selectorLine`; a third value here would be a second answer to a
        // question those already own.
        assertEquals(
            listOf(
                PreviewUnreachable.NO_LOCAL_TIER,
                PreviewUnreachable.NO_PACK_FOR_SELECTION,
            ),
            PreviewUnreachable.entries.toList(),
        )
    }

    // --------------------------------------- one fact, one home (fix round 2, review r2's B2)

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private val unreachable = "src/main/java/com/whispereverywhere/transcription/stream/PreviewUnreachable.kt"
    private val autoFetch = "src/main/java/com/whispereverywhere/transcription/stream/PreviewAutoFetch.kt"
    private val strip = "src/main/java/com/whispereverywhere/ui/components/LivePreviewSelectorStrip.kt"
    private val prefs = "src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt"
    private val home = "src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt"

    private fun text(relative: String): String =
        repoFile(relative).readText().replace("\r\n", "\n")

    /**
     * Every Kotlin source in the app — main AND test — anchored on two files rather than on a
     * directory name, so a moved tree fails here loudly instead of walking an empty tree to a
     * vacuous pass (`NativeVadSourceContractTest`'s own rule for the same instrument).
     *
     * THIS FILE IS THE ONE EXCLUSION, and it has to be: the two tests below quote the retired
     * spellings in order to forbid them, so a walk that included itself would ban its own needles.
     */
    private fun packageRootOf(relative: String): File {
        // .../com/whispereverywhere/<layer>/<dir>/Thing.kt — three levels up is the package root.
        val root = repoFile(relative).parentFile?.parentFile?.parentFile
        assertTrue("cannot reach the package root from $relative", root?.isDirectory == true)
        return requireNotNull(root)
    }

    private val everySource: List<File> by lazy {
        val roots = listOf(
            unreachable,
            "src/test/java/com/whispereverywhere/transcription/stream/PreviewUnreachableTest.kt",
        ).map { packageRootOf(it) }
        val files = roots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .filter { it.name != "PreviewUnreachableTest.kt" }
        assertTrue(
            "both source trees must be walked, and they are ~500 files: found ${files.size}",
            files.size > 200,
        )
        files
    }

    private fun filesSaying(phrase: String): List<String> =
        everySource.filter { it.readText().replace("\r\n", "\n").contains(phrase) }
            .map { it.name }.distinct().sorted()

    @Test fun theMECHANISMIsSpelledOnlyWhereItIsRefutedAndItsReadersPointHere() {
        // THE GUARD REVIEW r2's B2 ASKED FOR, and it is a walk rather than a list of citations
        // because a list of citations is what failed: the fix round corrected the nine sites the
        // review named and left the same refuted sentence standing in three more, one of them on
        // the `@param` of the component this axis generalises. A grep refuted the claim that the
        // sweep was exhaustive, so the grep is a test now.
        //
        // The refuted sentence is *"with no on-device whisper tier every session is a cloud
        // session, so the previewer can never arm"*. It is false: `decideEngineChoice` answers
        // LOCAL_ONLY with no provider configured, `cloudWrapper` stays null, and
        // `localPreviewArms` has no tier term — so on a modelless phone the gate ARMS. It may
        // appear ONLY where it is quoted in order to be refuted.
        val quoted = filesSaying("every session is a cloud session")
        assertEquals(
            "this sentence may be written only where it is refuted — this enum's KDoc, which is " +
                "its one home, and the test that EXECUTES the refutation. Anywhere else it is an " +
                "assertion about the feature and it is false. Found: $quoted",
            listOf("LocalPreviewGateTest.kt", "PreviewUnreachable.kt"),
            quoted,
        )
        for (spelling in listOf("previewer can never arm", "can never arm the previewer")) {
            val found = filesSaying(spelling)
            assertEquals(
                "<<$spelling>> is the refuted mechanism in short form and has no home at all: " +
                    "the true short thing is that no word reaches the bubble, and the reason " +
                    "belongs to PreviewUnreachable's KDoc. Found: $found",
                emptyList<String>(),
                found,
            )
        }
        // The home still holds the fact...
        val mechanism = text(unreachable)
        assertTrue(
            "PreviewUnreachable's KDoc is the home and must still state the mechanism",
            mechanism.contains("### THE MECHANISM"),
        )
        assertTrue(
            "including the cell where the gate ARMS, which is the half the first model denied",
            mechanism.contains("the gate ARMS here"),
        )
        // ...and the two files whose `@param` used to re-spell it point at the home instead.
        for (reader in listOf(strip, autoFetch)) {
            assertTrue(
                "$reader must point at the home rather than re-spelling the mechanism",
                text(reader).contains("KDoc is that fact's one home"),
            )
        }
    }

    @Test fun theWRITESiteHasOneHomeAndNoFileStillNamesTheGatesCallSite() {
        // The second fact this axis rests on, and the one that was made newly false by the fix
        // round's own change (review r2's B2): `livePreviewArmedOnce` moved from the previewer
        // gate's call site to `onOpen`, and three files went on documenting the old site — one of
        // them the flag's own KDoc, which the fix round had quoted as its authority for moving the
        // write. A reader who trusted it would have moved the write back.
        val stale = filesSaying("the gate's own call")
        assertEquals(
            "no file may name the gate's call site as the write site: the gate's answer is " +
                "necessary and NOT sufficient (it arms on a modelless phone), so the write is " +
                "`onOpen`'s. Found: $stale",
            emptyList<String>(),
            stale,
        )
        val flag = text(prefs)
        assertTrue(
            "PreferencesManager.livePreviewArmedOnce's KDoc is the home for WHEN it is written",
            flag.contains("THE ONE HOME FOR WHEN IT IS WRITTEN, AND THE WRITE IS `onOpen`'s"),
        )
        assertTrue(
            "and it must say why arming alone is not enough, which is the fact that moved it",
            flag.contains("NECESSARY and it is NOT SUFFICIENT"),
        )
        assertTrue(
            "the announcement's own input points at that home rather than naming a site",
            text(autoFetch).contains("whose own KDoc is the one home"),
        )
        assertTrue(
            "and so does the card's read on Home, the only reader of the flag",
            text(home).contains("`livePreviewArmedOnce`'s own KDoc"),
        )
    }
}
