package com.whispereverywhere.npu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE IMPORT'S OWNER, executed (4.0, Q8 fix round 1, I3).
 *
 * The defect this closes was invisible to every other test on the branch: the import's state and
 * its coroutine lived in the Compose tree, and `MainActivity` declares no `android:configChanges`,
 * so a rotation three minutes into a 358 MB copy cancelled it and left the panel back at
 * "Import model pair…" with no message. Nothing failed loudly, because nothing failed at all — the
 * owner simply stopped existing.
 *
 * The fix is a process-scoped owner, and the reason the work arrives as a **lambda** is so that the
 * state machine around it is testable without a `Context`: single-flight, `Running` published before
 * the work begins, the terminal state published after, and an unexpected throw becoming a refusal
 * rather than a crashed process. That is the part that was wrong, so that is the part that is run.
 *
 * The object is process-global, so every test here drives it explicitly and [tearDown] returns it
 * to rest — the same discipline `ModelInstallSignalTest` applies to the other global in this
 * package.
 */
class NpuImportControllerTest {

    @After
    fun tearDown() {
        NpuImportController.cancel()
    }

    private fun awaitTerminal(): NpuAssetImport.ImportState = runBlocking {
        withTimeout(5_000) {
            NpuImportController.state.first {
                it is NpuAssetImport.ImportState.Installed ||
                    it is NpuAssetImport.ImportState.Refused
            }
        }
    }

    @Test
    fun theOwnerOutlivesItsCallerAndPublishesRunningBeforeTheWorkBegins() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val accepted = NpuImportController.start {
            started.complete(Unit)
            release.await()
            NpuAssetImport.ImportState.Installed
        }
        assertTrue("the first start is accepted", accepted)
        // Running is published SYNCHRONOUSLY, before the coroutine is even dispatched: otherwise
        // there is a window in which the button is back and the import has already begun.
        assertTrue(
            "Running is visible the moment start() returns, not when the copy gets scheduled",
            NpuImportController.state.value is NpuAssetImport.ImportState.Running,
        )
        runBlocking { withTimeout(5_000) { started.await() } }
        assertTrue("and the controller knows it is running", NpuImportController.isRunning())
        release.complete(Unit)
        assertEquals(
            "the terminal state the work returned is what the panel ends on",
            NpuAssetImport.ImportState.Installed,
            awaitTerminal(),
        )
        assertFalse("and nothing is running afterwards", NpuImportController.isRunning())
    }

    @Test
    fun aSecondStartWhileOneIsRunningIsRefusedRatherThanRunConcurrently() {
        // Single-flight is not decoration: the picker stays reachable while a copy is in flight
        // (and a recreation re-arms the button for a frame), and two concurrent imports would write
        // the same two .part files from two threads and then both try to finalise them.
        val release = CompletableDeferred<Unit>()
        var secondRan = false
        assertTrue(NpuImportController.start { release.await(); NpuAssetImport.ImportState.Installed })
        val second = NpuImportController.start {
            secondRan = true
            NpuAssetImport.ImportState.Installed
        }
        assertFalse("the second start is refused while the first is in flight", second)
        assertFalse("and its work is never invoked", secondRan)
        release.complete(Unit)
        awaitTerminal()
        assertTrue(
            "once the first finishes, a new import is accepted again — the guard is single-flight, " +
                "not once-per-process",
            NpuImportController.start { NpuAssetImport.ImportState.Installed },
        )
        awaitTerminal()
    }

    @Test
    fun progressFromTheWorkBecomesTheStateThePanelRenders() {
        val release = CompletableDeferred<Unit>()
        val reported = CompletableDeferred<Unit>()
        NpuImportController.start { onProgress ->
            onProgress(120_000_000L, 358_244_352L)
            reported.complete(Unit)
            release.await()
            NpuAssetImport.ImportState.Installed
        }
        runBlocking { withTimeout(5_000) { reported.await() } }
        val running = NpuImportController.state.value as NpuAssetImport.ImportState.Running
        assertEquals("the bytes copied reach the panel", 120_000_000L, running.soFar)
        assertEquals("and the total they are measured against", 358_244_352L, running.total)
        release.complete(Unit)
        awaitTerminal()
    }

    @Test
    fun aRefusalIsCarriedThroughAndAnUnexpectedThrowBecomesOneRatherThanACrash() {
        val refusal = NpuAssetImport.ImportState.Refused("nope")
        NpuImportController.start { refusal }
        assertEquals("a refusal the work returns is published verbatim", refusal, awaitTerminal())

        // The importer promises not to throw for a bad file, but this owner is process-scoped: an
        // unexpected exception escaping into its scope must become a message on the card, not a
        // silent dead panel — which is the same defect as I3 arriving by a different route.
        NpuImportController.start { throw IllegalStateException("boom") }
        val caught = awaitTerminal() as NpuAssetImport.ImportState.Refused
        assertTrue("the refusal names the failure: ${caught.reason}", caught.reason.contains("boom"))
        assertTrue(
            "and says nothing was installed: ${caught.reason}",
            caught.reason.contains("Nothing was installed"),
        )
    }

    @Test
    fun cancellingReturnsTheOwnerToRestSoARetryStartsClean() {
        val release = CompletableDeferred<Unit>()
        NpuImportController.start { release.await(); NpuAssetImport.ImportState.Installed }
        assertTrue(NpuImportController.isRunning())
        NpuImportController.cancel()
        assertFalse("the job is gone", NpuImportController.isRunning())
        assertEquals(
            "and the panel is back at rest — from the user's point of view the import they " +
                "cancelled is over the moment they say so",
            NpuAssetImport.ImportState.Idle,
            NpuImportController.state.value,
        )
        assertTrue(
            "a fresh import is accepted immediately after a cancel",
            NpuImportController.start { NpuAssetImport.ImportState.Installed },
        )
        awaitTerminal()
    }
}
