package com.whispereverywhere.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE SAVED TRANSCRIPT'S EXPORT, pinned structurally (4.10 Task 5).
 *
 * `SpeakerLabelsTest` proves the formatter and `TranscriptStoreTest` proves the sidecar. What
 * neither can see is the one @Composable that puts them together — a Compose screen this project
 * has no way to execute in a JVM test — and the mistakes available there are all silent:
 *
 *  - `labels = true` instead of the user's flag: every copy carries `Speaker N:`, and the
 *    setting's default-off promise is broken for everybody;
 *  - the sidecar read dropped: the switch does nothing at all, with the whole suite green;
 *  - the flag left out of the producer's keys: flipping the switch with a transcript open shows
 *    the old render, so the user reads one thing and copies another;
 *  - the three buttons reading three different strings: what is displayed stops being what is
 *    copied, which is the one thing a transcript dialog has to guarantee.
 *
 * `TranscriptsScreen.kt` is therefore on `sourcePinnedInputs` in app/build.gradle.kts — this file
 * reads it as TEXT, and the edits it exists to catch are literal ones that compile to a
 * byte-identical class, so without that entry the one edit this pin exists to catch is the one
 * that leaves `:app:testDebugUnitTest` UP-TO-DATE.
 */
class TranscriptsExportPinTest {

    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private val text: String by lazy {
        source("src/main/java/com/whispereverywhere/ui/screens/TranscriptsScreen.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun count(needle: String) = text.split(needle).size - 1

    private fun liveCount(needle: String) = text.split("\n").count { line ->
        val trimmed = line.trimStart()
        !(trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) &&
            needle in line
    }

    private fun indexOfOrFail(needle: String): Int {
        val i = text.indexOf(needle)
        assertTrue("missing from TranscriptsScreen.kt: <<$needle>>", i >= 0)
        return i
    }

    @Test
    fun theLabelsComeFromTheUsersSwitchAndNeverFromALiteral() {
        indexOfOrFail("                            mode = SpeakerLabels.Mode.Export(labels = labelsInExport),")
        assertEquals("ONE render site", 1, count("SpeakerLabels.render("))
        assertEquals(0, count("Mode.Export(labels = true)"))
        // A saved transcript is neither a panel nor a field: the other two modes have no business
        // in this file, and reaching for one here would mean the export switch had been bypassed.
        assertEquals(0, count("Mode.Panel"))
        assertEquals(0, count("Mode.Field"))
    }

    @Test
    fun theSwitchIsReadAsAFlowAndIsAKeyOfTheProducer() {
        indexOfOrFail("        .preferencesManager.speakerLabelsInExportFlow.collectAsState()")
        // The KEY is what makes flipping the switch with a dialog open honest: without it the
        // producer keeps the render it made under the old value and the user copies something
        // other than what is on screen.
        indexOfOrFail("key1 = entry, key2 = labelsInExport")
        assertEquals(1, liveCount("speakerLabelsInExportFlow"))
    }

    @Test
    fun aSessionWithNoSidecarIsStillTheStoredTextByteForByte() {
        // One voice, or a session recorded before 4.10. `readRuns` answers null and the stored
        // text is used unchanged — the 4.9 path, which is most of history.
        indexOfOrFail("                    val runs = store.readRuns(entry)")
        indexOfOrFail("                    if (runs == null) {\n                        store.read(entry)")
        assertEquals(1, count("store.readRuns("))
        assertEquals(1, count("store.read("))
    }

    @Test
    fun theDialogTheCopyAndTheShareAllHandOverTheSameString() {
        // What you read is what you copy. Four live occurrences: the producer's declaration, the
        // dialog body, the clipboard write and the share extra.
        assertEquals(4, liveCount("fullText"))
        indexOfOrFail("clipboard.setText(AnnotatedString(fullText))")
        indexOfOrFail("putExtra(Intent.EXTRA_TEXT, fullText)")
        val declared = indexOfOrFail("val fullText by produceState(")
        assertTrue(declared < indexOfOrFail("clipboard.setText(AnnotatedString(fullText))"))
    }

    @Test
    fun theRenderStaysOffTheMainThread() {
        // A multi-hour transcript is hundreds of KB; the read was already on IO and the render
        // joins it there rather than on the dialog-open frame.
        val io = indexOfOrFail("kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO)")
        assertTrue("the render is inside the IO block", io < indexOfOrFail("SpeakerLabels.render("))
    }

    @Test
    fun theScreenIsADeclaredInputOfTheTestTask() {
        // See the class KDoc: this pin is decoration without the entry.
        val build = source("build.gradle.kts").readText()
        assertTrue(
            build.contains("\"src/main/java/com/whispereverywhere/ui/screens/TranscriptsScreen.kt\""),
        )
    }
}
