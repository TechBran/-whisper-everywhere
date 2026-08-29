package com.whispereverywhere.npu

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Copies an APK asset into `filesDir` **once**, verified, and hands back a path (4.1 L3).
 *
 * ### Why a path at all
 *
 * `assets/` entries are not files on disk: they live compressed inside the APK and `AssetManager`
 * hands out streams. [com.whispereverywhere.whisper.WhisperNative.initMelOnly] takes a **path**, and
 * so does the FastRPC skel loader L6 brings, so something has to materialise the bytes. The
 * interesting part is not the copy — it is that the copy must verify itself, must be idempotent,
 * and must never leave a half-written file where a valid one is expected.
 *
 * ### Three decisions, and what each one is for
 *
 * **Streaming sha256, never a second read.** The digest is computed from the bytes as they pass
 * through to the destination. Hashing the file afterwards would re-read 103 KB off flash to check a
 * different thing — what landed, rather than what arrived — and those differ exactly when it
 * matters.
 *
 * **`.part`, then rename.** The destination name never exists holding partial content. A process
 * killed mid-copy leaves a `.part` and the next arm re-stages; the rename is within one directory,
 * which is what makes it atomic on every filesystem this app runs on. `initMelOnly` on a
 * half-written filterbank is the silent case: the fork's truncation guard catches a *short* file,
 * but a copy interrupted at exactly the right length is not short.
 *
 * **Idempotent by size AND digest.** The second arm of a session costs one stat and one hash of
 * 103 KB and then returns the path it already had. Size alone would accept a corrupted file of the
 * right length — a partially-flushed write, or an APK upgrade that changed an asset without
 * changing its length; digest alone would be the same work as re-staging.
 *
 * ### Every failure is a refusal, never a throw
 *
 * `AssetManager.open` throws for an asset that is absent, which is a real state (a build that
 * excluded it, an aggressive shrink, a partially-installed split). [stagedPath] answers **null** for
 * every one of them and emits one `WE-DIAG` line naming the asset and the reason. The caller treats
 * that as an ordinary declined stage and falls back to the CPU tier; an exception escaping here
 * would instead propagate out of `NpuWhisperBackend.load`, through `NativeComputeGate`, and take
 * the session with it.
 *
 * ### Testing
 *
 * [stage] takes the destination and a stream factory, so `NpuAssetStageTest` executes every decision
 * against a temp dir. The `Context`-bound half — `filesDir` plus `assets.open` — is pinned as source
 * there, the same split [NpuAssetImport] uses and for the same reason: this project has no
 * Robolectric and no mocking framework on its unit-test classpath.
 */
object NpuAssetStage {

    /** The suffix the copy is written under until its length and digest verify. */
    const val PART_SUFFIX: String = ".part"

    /**
     * The sibling file recording a VERIFIED stage — `<name>.staged`, holding [markerLine]
     * (4.1 L6).
     *
     * It exists because [stage]'s idempotent arm full-hashes the destination on EVERY arm. At the
     * melbank's 103 KB that is free; at the skel's 17.9 MiB it is a per-session flash read on
     * `load`, which is exactly the cost the L3 handoff warned this object's second caller about —
     * and the answer it prescribed is a stored marker, not a weaker check. A marker that matches
     * ([markerVouches]) lets the arm settle for a handful of stats; anything else — absent, a
     * different digest expectation, a changed length or mtime — falls through to the FULL
     * verification and a fresh marker.
     *
     * **The stated trade:** a file corrupted in place at the same length and mtime passes the
     * fast arm. That is the same trust every mtime-based build system extends, it is exactly what
     * the full hash bought at 103 KB, and the failure it admits is one `nativeInit` meets as a
     * refusal — while the cost it removes is paid on every single session.
     */
    const val MARKER_SUFFIX: String = ".staged"

    /** The marker beside [dest]. */
    fun markerFile(dest: File): File = File(dest.parentFile, dest.name + MARKER_SUFFIX)

    /**
     * What a marker records: the digest the bytes VERIFIED against, the length, and the mtime of
     * the verified copy. The digest is in the line so that an APK upgrade that changes the
     * expected digest invalidates every old marker by content, not by convention.
     */
    fun markerLine(expectedSha256: String, bytes: Long, mtime: Long): String =
        "$expectedSha256 $bytes $mtime"

    /** Does the marker vouch for [dest] as it is RIGHT NOW, against THIS expectation? */
    fun markerVouches(dest: File, expectedBytes: Long, expectedSha256: String): Boolean {
        if (!dest.isFile || dest.length() != expectedBytes) return false
        val marker = markerFile(dest)
        if (!marker.isFile) return false
        val recorded = try {
            marker.readText()
        } catch (cause: IOException) {
            return false
        }
        return recorded == markerLine(expectedSha256, dest.length(), dest.lastModified())
    }

    /**
     * [stage], behind the marker fast path — the arm for the 17.9 MiB class of asset.
     *
     * Not concurrency-guarded, exactly like [stage]: its one caller is `NpuWhisperBackend.load`,
     * which runs inside `NativeComputeGate` — the same argument the mel arm and `pcmToMel` rely
     * on. A caller outside that gate must re-check the argument, not inherit it (the L3 handoff's
     * words).
     */
    fun stageWithMarker(
        dest: File,
        assetName: String,
        expectedBytes: Long,
        expectedSha256: String,
        open: () -> InputStream,
    ): StageResult {
        if (markerVouches(dest, expectedBytes, expectedSha256)) {
            return StageResult.Staged(dest)
        }
        // The marker did not vouch, so it must not survive a failed re-stage to vouch later:
        // delete FIRST, re-create only after a full verification has passed again.
        val marker = markerFile(dest)
        marker.delete()
        val outcome = stage(dest, assetName, expectedBytes, expectedSha256, open)
        if (outcome is StageResult.Staged) {
            try {
                marker.writeText(
                    markerLine(expectedSha256, dest.length(), dest.lastModified())
                )
            } catch (cause: IOException) {
                // The stage itself verified; a missing marker only costs the next arm one full
                // hash. A half-written marker must not vouch, so it is removed.
                marker.delete()
            }
        }
        return outcome
    }

    /** What one staging attempt did. */
    sealed interface StageResult {
        /** The asset is on disk at [file], at the expected length and digest. */
        data class Staged(val file: File) : StageResult

        /** Nothing was staged; [reason] names the asset and what went wrong. */
        data class Refused(val reason: String) : StageResult
    }

    /**
     * Stages one asset into [dest], or refuses.
     *
     * @param dest the destination path. Its parent must exist — on the real call site it is
     *        `filesDir`, which always does.
     * @param assetName named in every refusal. This function is generic and L6 stages a second
     *        asset through it, so a message that omits the name leaves a `WE-DIAG` reader unable to
     *        say which file declined.
     * @param expectedBytes the published length, to the byte.
     * @param expectedSha256 the published digest, lower-case hex.
     * @param open opens the asset. Called at most once, and only when the destination does not
     *        already verify; it may throw [IOException], which is caught and becomes a refusal.
     */
    fun stage(
        dest: File,
        assetName: String,
        expectedBytes: Long,
        expectedSha256: String,
        open: () -> InputStream,
    ): StageResult {
        // THE IDEMPOTENT ARM, and it is first because it is the common one: `load` runs on every
        // session and the asset changes only when the APK does. One stat and one hash of 103 KB.
        if (dest.isFile && dest.length() == expectedBytes && digestOf(dest) == expectedSha256) {
            return StageResult.Staged(dest)
        }

        val part = File(dest.parentFile, dest.name + PART_SUFFIX)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            open().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // BOUNDED, not trusting — NpuAssetImport's overLengthRefusal one seam
                        // along, for the same reason: a stream that never ends would fill the
                        // device and then be refused for being the wrong size, which is a true
                        // statement made far too late. One byte of headroom, because reaching it
                        // is the proof and nothing smaller is.
                        val room = (expectedBytes + 1L) - written
                        if (room <= 0L) break
                        val want = if (room < buffer.size) room.toInt() else buffer.size
                        val read = input.read(buffer, 0, want)
                        if (read < 0) break
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        written += read.toLong()
                    }
                }
            }
        } catch (cause: IOException) {
            part.delete()
            return StageResult.Refused(
                "$assetName could not be read from the APK: " +
                    "${cause.javaClass.simpleName}: ${cause.message}"
            )
        } catch (cause: SecurityException) {
            part.delete()
            return StageResult.Refused(
                "$assetName could not be written to ${dest.parent}: " +
                    "${cause.javaClass.simpleName}: ${cause.message}"
            )
        }

        // LENGTH FIRST, then digest. Both are refusals, but a wrong length is a different fact from
        // wrong bytes — a truncated copy versus a substituted asset — and naming the two lengths is
        // what makes the first one diagnosable at a glance.
        if (written != expectedBytes) {
            part.delete()
            return StageResult.Refused(
                "$assetName staged $written bytes, expected exactly $expectedBytes. Short means " +
                    "the stream ended early; long means it kept producing data past the published " +
                    "length and the copy was stopped."
            )
        }
        val actual = hex(digest.digest())
        if (actual != expectedSha256) {
            part.delete()
            return StageResult.Refused(
                "$assetName is the right length and the wrong bytes: expected sha256 " +
                    "$expectedSha256, got $actual"
            )
        }

        // The rename is what PUBLISHES the file, and nothing above it may leave the destination
        // name in existence: every refusal path deletes the .part and returns before this line.
        dest.delete()
        if (!part.renameTo(dest)) {
            part.delete()
            return StageResult.Refused(
                "$assetName verified but could not be renamed into place at ${dest.absolutePath}"
            )
        }
        return StageResult.Staged(dest)
    }

    /**
     * The staged path for [assetName] under `filesDir`, or **null** when it could not be staged.
     *
     * @param expectedBytes / [expectedSha256] the published pair. For the 128-bin filterbank these
     *        are [NpuModelSpec.MELBANK_128_BYTES] and [NpuModelSpec.MELBANK_128_SHA256], which are
     *        the same two values `tools/extract_melbank.py` asserts on the way out and
     *        `MelbankAssetTest` asserts against the shipped bytes.
     */
    fun stagedPath(
        context: Context,
        assetName: String,
        expectedBytes: Long,
        expectedSha256: String,
    ): String? {
        val dest = File(context.filesDir, assetName)
        val outcome = stage(dest, assetName, expectedBytes, expectedSha256) {
            context.assets.open(assetName)
        }
        return pathOrRefusal(outcome)
    }

    /**
     * [stagedPath] through the marker fast path — same `filesDir` destination, same null-means-
     * declined contract, one full verification per install instead of one per arm. The skel's
     * entry point (4.1 L6); see [MARKER_SUFFIX] for the trade this buys and the cost it removes.
     */
    fun stagedPathWithMarker(
        context: Context,
        assetName: String,
        expectedBytes: Long,
        expectedSha256: String,
    ): String? {
        val dest = File(context.filesDir, assetName)
        val outcome = stageWithMarker(dest, assetName, expectedBytes, expectedSha256) {
            context.assets.open(assetName)
        }
        return pathOrRefusal(outcome)
    }

    /** One rendering of an outcome, so both `Context` entry points refuse identically. */
    private fun pathOrRefusal(outcome: StageResult): String? = when (outcome) {
        is StageResult.Staged -> outcome.file.absolutePath
        is StageResult.Refused -> {
            android.util.Log.w(NpuDiag.TAG, "npu: asset stage refused ${outcome.reason}")
            null
        }
    }

    /** Streaming sha256 of an existing file — the idempotent arm's only read. */
    private fun digestOf(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        hex(digest.digest())
    } catch (cause: IOException) {
        // Unreadable is not "matches": fall through to a fresh stage, which will refuse loudly if
        // the directory is genuinely broken.
        null
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
