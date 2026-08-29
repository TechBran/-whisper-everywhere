package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * [NpuAssetStage] — an APK asset becomes a path, once, and verifies itself on the way (4.1 L3).
 *
 * ### Why this exists at all
 *
 * `assets/` entries are not files on disk. They live compressed inside the APK and `AssetManager`
 * hands out streams, while `WhisperNative.initMelOnly` — like the FastRPC skel loader L6 brings —
 * takes a **path**. Something has to copy the bytes out, and the interesting part is not the copy:
 * it is that the copy must be *verified*, must be *idempotent*, and must never leave a
 * half-written file where a valid one is expected.
 *
 * ### The shape, and the three decisions in it
 *
 *  - **Streaming sha256, never a second read.** The digest is computed from the same bytes as they
 *    pass through to the destination. Hashing the file afterwards would be a second read of a
 *    different thing — 103 KB re-read off flash — and, worse, it would verify what landed rather
 *    than what arrived, which is a distinction that matters exactly when it matters.
 *  - **`.part`, then rename.** The destination name never exists holding partial content, so a
 *    process killed mid-copy leaves a `.part` and the next arm re-stages. `initMelOnly` on a
 *    half-written filterbank is the silent case: the fork's truncation guard catches a short file,
 *    but a copy interrupted at exactly the right length would not be short.
 *  - **Idempotent by size AND digest.** The second arm of a session costs one stat and one hash of
 *    103 KB and then returns the path it already had. Size alone would accept a corrupted file of
 *    the right length; digest alone would be the same work as re-staging.
 *
 * ### What is executed here and what is pinned
 *
 * Everything except the `Context` — [NpuAssetStage.stage] takes the destination and a stream
 * factory, so a temp dir and a `ByteArrayInputStream` exercise every decision. The `Context`-bound
 * half ([NpuAssetStage.stagedPath], which is `filesDir` plus `assets.open`) is pinned as source in
 * the last test, the same split `NpuAssetImport` uses for the same reason: there is no Robolectric
 * and no mocking framework on this project's unit-test classpath.
 */
class NpuAssetStageTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    /** 103 KB is the real asset's size; 4 KB here, because the arithmetic is size-independent. */
    private val payload: ByteArray = ByteArray(4096) { (it * 31 % 251).toByte() }

    private fun sha256(of: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(of).joinToString("") { "%02x".format(it) }

    private val digest: String by lazy { sha256(payload) }

    private fun dest(): File = File(temp.root, "melbank-128.bin")

    private fun part(): File = File(temp.root, "melbank-128.bin" + NpuAssetStage.PART_SUFFIX)

    private fun stage(
        dest: File = dest(),
        expectedBytes: Long = payload.size.toLong(),
        expectedSha256: String = digest,
        open: () -> InputStream = { ByteArrayInputStream(payload) },
    ): NpuAssetStage.StageResult =
        NpuAssetStage.stage(dest, "melbank-128.bin", expectedBytes, expectedSha256, open)

    private fun refusalOf(result: NpuAssetStage.StageResult): String {
        assertTrue(
            "expected a refusal, got $result",
            result is NpuAssetStage.StageResult.Refused,
        )
        return (result as NpuAssetStage.StageResult.Refused).reason
    }

    /** Reads a repo file, LF-normalised at this single read site. */
    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun liveLines(scope: String, needle: String): List<String> =
        scope.split("\n").map { it.trimStart() }.filter { line ->
            !(line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) &&
                line.contains(needle)
        }

    // ---------------------------------------------------------------- the happy path

    @Test
    fun aFreshStageCopiesTheAssetVerifiesItAndRenamesItIntoPlace() {
        val result = stage()
        assertTrue("a matching asset must stage: got $result", result is NpuAssetStage.StageResult.Staged)
        val staged = (result as NpuAssetStage.StageResult.Staged).file
        assertEquals("the staged file is the destination, not the .part", dest(), staged)
        assertTrue("…and it exists", staged.isFile)
        assertEquals("…at exactly the expected length", payload.size.toLong(), staged.length())
        assertTrue(
            "…holding the asset's bytes, not a truncation of them",
            staged.readBytes().contentEquals(payload),
        )
        assertFalse(
            "…and NOTHING is left under the ${NpuAssetStage.PART_SUFFIX} suffix. A rename that " +
                "copies instead of moving would leave a second 103 KB file in filesDir forever.",
            part().exists(),
        )
    }

    /**
     * The second arm is a **no-op**, asserted by mtime rather than by output.
     *
     * Output cannot see the difference: a re-copy of the same bytes produces a byte-identical file,
     * so `readBytes().contentEquals(payload)` is green whether the copy ran or not. The modification
     * time is the only witness that the work was actually skipped — and skipping it is the point,
     * because `load` runs on every session and re-writing 103 KB to flash each time is a cost with
     * no purpose.
     */
    @Test
    fun aDestinationThatAlreadyMatchesIsNotRewritten() {
        assertTrue(stage() is NpuAssetStage.StageResult.Staged)
        val settled = dest()
        val stamp = 1_500_000_000_000L
        assertTrue("the test needs a settable mtime to measure anything", settled.setLastModified(stamp))

        val second = stage()
        assertTrue("the second arm must still answer with the file", second is NpuAssetStage.StageResult.Staged)
        assertEquals(
            "…and must NOT have rewritten it. Same bytes either way, so mtime is the only " +
                "evidence: a stage that re-copies on every session pays 103 KB of flash writes " +
                "per recording session for a file that was already correct.",
            stamp,
            settled.lastModified(),
        )
    }

    /**
     * A destination of the RIGHT LENGTH and the WRONG BYTES is staged again — which is why the
     * idempotence test is size **and** digest.
     *
     * This is the case a size-only check gets wrong, and it is not hypothetical: a copy interrupted
     * by a kill at exactly the right offset, a partially-flushed write, or an APK upgrade that
     * changed the asset without changing its length all land here. The old file is not short, so
     * nothing downstream would notice; `initMelOnly` would read structured garbage as float32
     * coefficients and produce a mel that is wrong in a way only a transcript comparison could see.
     */
    @Test
    fun aDestinationOfTheRightLengthAndTheWrongBytesIsStagedAgain() {
        val corrupt = payload.copyOf()
        corrupt[corrupt.size / 2] = (corrupt[corrupt.size / 2] + 1).toByte()
        dest().writeBytes(corrupt)
        val stamp = 1_500_000_000_000L
        dest().setLastModified(stamp)

        val result = stage()
        assertTrue("a same-length, different-content destination must be replaced", result is NpuAssetStage.StageResult.Staged)
        assertTrue(
            "…with the asset's real bytes. The length matched, so a size-only idempotence check " +
                "would have returned this corrupt file as though it were the filterbank.",
            dest().readBytes().contentEquals(payload),
        )
    }

    // ---------------------------------------------------------------- the refusals

    /**
     * A digest mismatch refuses, **names the asset**, and leaves no `.part`.
     *
     * The name matters because this function is generic: L6 stages a second asset through it, and a
     * refusal that says only "digest mismatch" leaves the reader of a `WE-DIAG` capture unable to
     * say which file. The absent `.part` matters because the next arm must not find a stale
     * fragment and must not accumulate one per attempt.
     */
    @Test
    fun aDigestMismatchRefusesNamesTheAssetAndLeavesNoPartFile() {
        val wanted = "0".repeat(64)
        val reason = refusalOf(stage(expectedSha256 = wanted))
        assertTrue("the refusal must name the asset. Found: $reason", reason.contains("melbank-128.bin"))
        assertTrue("…and the digest it wanted. Found: $reason", reason.contains(wanted))
        assertTrue("…and the digest it got, so the two can be compared. Found: $reason", reason.contains(digest))
        assertFalse(
            "no .part may survive a refusal: the next arm would find a full-length fragment of " +
                "an asset that was already rejected once",
            part().exists(),
        )
        assertFalse(
            "and the destination must never have been created — the rename is what publishes it, " +
                "and a refused stage does not reach the rename",
            dest().exists(),
        )
    }

    /**
     * A short source refuses **before** it can be mistaken for the asset.
     *
     * This is the one an unverified copy gets wrong in the worst way. A truncated filterbank is not
     * detectably absent: the fork's `whisper_init_from_file_mel_only` would refuse it (its
     * `loaded && !fin` guard is the net under this whole design) — but only because it happens to
     * have one. The length is checked here so that the refusal names the copy rather than the load.
     */
    @Test
    fun aShortSourceRefusesAndNamesBothLengths() {
        val short = payload.copyOf(payload.size - 1)
        val reason = refusalOf(stage(open = { ByteArrayInputStream(short) }))
        assertTrue("the refusal must name the length it got. Found: $reason", reason.contains("${short.size}"))
        assertTrue(
            "…and the length it expected, because one number is not a comparison. Found: $reason",
            reason.contains("${payload.size}"),
        )
        assertFalse("and no .part survives it", part().exists())
        assertFalse("nor a destination", dest().exists())
    }

    /**
     * A source that keeps producing bytes past the expected length refuses too — the OTHER
     * direction, and the one a length check written as `bytesRead < expected` misses entirely.
     *
     * The copy is bounded rather than trusting, for `NpuAssetImport.overLengthRefusal`'s reason one
     * seam along: a stream that never ends fills the device and is then refused for being the wrong
     * size, which is a true statement made far too late.
     */
    @Test
    fun aSourceLongerThanTheAssetRefusesRatherThanWritingItAll() {
        // THE BOUND IS MEASURED, NOT INFERRED — and it has to be, because the OUTCOME cannot see
        // it. An unbounded copy of an over-long stream reads it to the end, writes every byte,
        // then compares the total against the expected length and refuses: same refusal, same
        // absent `.part`, same everything this test could otherwise assert. The difference is only
        // in how much was written before the refusal, which on a hostile or corrupt stream is the
        // difference between one wasted buffer and a full device.
        //
        // So the source counts what it delivered. With the bound, the loop stops at expected + 1 —
        // one byte of headroom, because reaching it is the proof that the stream is over-long and
        // nothing smaller is.
        var delivered = 0L
        val over = ByteArray(payload.size * 16) { payload[it % payload.size] }
        val counted = object : InputStream() {
            private val inner = ByteArrayInputStream(over)
            override fun read(): Int = inner.read().also { if (it >= 0) delivered++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                inner.read(b, off, len).also { if (it > 0) delivered += it }
        }
        val reason = refusalOf(stage(open = { counted }))
        assertTrue(
            "the refusal must name the expected length. Found: $reason",
            reason.contains("${payload.size}"),
        )
        assertTrue(
            "and the copy must have stopped at expected + 1 bytes rather than draining the " +
                "stream: it read $delivered of ${over.size} available, and the bound is what " +
                "stops a stream that never ends from filling the device before being refused for " +
                "the wrong size — a true statement made far too late.",
            delivered <= payload.size + 1L,
        )
        assertFalse("and no .part survives it", part().exists())
        assertFalse("nor a destination", dest().exists())
    }

    /**
     * An asset that cannot be opened is a **refusal, not a throw**.
     *
     * `AssetManager.open` throws `FileNotFoundException` for an asset that is absent — which is a
     * real state: a build that excluded it, an aggressive resource shrink, a partially-installed
     * split. The caller treats a stage refusal as an ordinary declined stage and falls back to the
     * CPU tier; an exception escaping here would instead propagate out of `load`, through
     * `NativeComputeGate`, and take the session with it.
     */
    @Test
    fun anAssetThatCannotBeOpenedIsARefusalRatherThanAThrow() {
        val reason = refusalOf(stage(open = { throw IOException("asset missing from this build") }))
        assertTrue("the refusal must name the asset. Found: $reason", reason.contains("melbank-128.bin"))
        assertTrue(
            "…and carry the cause, which is the only thing that separates 'absent from the APK' " +
                "from 'unreadable'. Found: $reason",
            reason.contains("asset missing from this build"),
        )
        assertFalse("and nothing is left behind", part().exists() || dest().exists())
    }

    /**
     * The `Context`-bound half, pinned as source: it reads the asset through `AssetManager`, writes
     * under `filesDir`, and answers **null** on every refusal.
     *
     * Not executable here — `Context` cannot be constructed on this classpath — and each of the
     * three claims is a live defect if reversed. Reading the asset by *path* would work in no build
     * at all; writing outside `filesDir` would fail on a device where the app has no other writable
     * directory; and throwing instead of answering null would turn a declined stage into a lost
     * session, since `load`'s callers treat null as "this stage declined" and nothing else.
     */
    @Test
    fun theContextBoundHalfStagesFromAssetsIntoFilesDirAndAnswersNullOnRefusal() {
        val kt = source("src/main/java/com/whispereverywhere/npu/NpuAssetStage.kt")
        assertTrue(
            "stagedPath must open the asset through the AssetManager — `context.assets.open(`. " +
                "An asset is not a file on disk; there is no path form of it to read. Found: " +
                liveLines(kt, "assets.open("),
            liveLines(kt, "context.assets.open(").isNotEmpty(),
        )
        assertTrue(
            "…and the destination must be under context.filesDir, the app's own private storage " +
                "and the only directory guaranteed writable without a permission. Found: " +
                liveLines(kt, "filesDir"),
            liveLines(kt, "context.filesDir").isNotEmpty(),
        )
        assertTrue(
            "…and stagedPath must answer a NULLABLE String. `: String` would force every refusal " +
                "to become an exception or a sentinel path, and NpuWhisperBackend branches on null " +
                "and on nothing else to decline the stage. Found: " +
                liveLines(kt, "fun stagedPath("),
            liveLines(kt, "): String? {").isNotEmpty(),
        )
        // THE ABSENCE IS THE ASSERTION, and it is whole-file rather than scoped to one function.
        // "Every failure is a refusal" is a property of the OBJECT: a throw added to the digest
        // helper, or to the idempotent arm, escapes stagedPath just as surely as one added to the
        // copy loop, and it escapes NpuWhisperBackend.load and NativeComputeGate with it — turning
        // a declined stage, which costs a CPU fallback, into a lost session. A presence check on
        // the `null` branch cannot see that; only the absence of the alternative can.
        assertTrue(
            "NpuAssetStage must not `throw` on any live line. Every failure here — an asset " +
                "missing from the APK, a short read, a wrong digest, a rename that fails — is a " +
                "REFUSAL, because the caller's contract is that a declined stage costs a fallback " +
                "and never a session. Found: " + liveLines(kt, "throw"),
            liveLines(kt, "throw ").isEmpty(),
        )
        assertEquals(
            "and the in-flight name must be a SUFFIX of the destination, not a name of its own: " +
                "the rename that publishes it has to be within one directory, which is what makes " +
                "it atomic on every filesystem this app runs on",
            ".part",
            NpuAssetStage.PART_SUFFIX,
        )
    }
}
