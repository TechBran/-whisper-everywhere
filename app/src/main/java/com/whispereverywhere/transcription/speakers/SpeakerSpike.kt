package com.whispereverywhere.transcription.speakers

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale

/**
 * THE SPIKE LATCH, and the one constant a release has to turn off (4.10 speaker spike, session 2).
 *
 * `docs/measurements/2026-09-18-speaker-spike.md` ended with a verdict and a plan: CAM++ costs what
 * the spec budgeted (130-310 ms per fingerprint) but *"quality with fixed thresholds: not
 * shippable"* — one voice split into five speakers, and a same-speaker similarity tail (0.04-0.45)
 * that overlaps the cross-speaker range (0.33-0.42), so **no pair of thresholds separates them**.
 * Five changes are asked for, and every one of them is a change to [SpeakerTracker]'s arithmetic:
 * a recent-fingerprints match instead of a lone centroid, a lower band, a confirm-then-merge pass.
 * The owner's device sessions are the scarce resource in this project, so those five are to be
 * tried **offline, on dumped embeddings** — on the PC, against the three sessions he already ran —
 * and not by handing him a new build per threshold pair.
 *
 * That is what this file is for and the whole of what it is for: it writes the fingerprints out.
 * [SPEAKER_SPIKE] is the compile-time switch that makes it exist at all.
 */
object SpeakerSpike {

    /**
     * TRUE while this is a spike build, and the guard on every line of dump machinery.
     *
     * The release that ships speaker labels cannot also ship the dump — nothing in the app ever
     * reads a dump back, and a diagnostic that works perfectly is exactly the kind of thing that
     * ships by accident. Because it is a `const`, flipping it to `false` lets the compiler strip
     * every guarded call rather than leaving dead-but-reachable file writers in the APK.
     */
    const val SPEAKER_SPIKE: Boolean = true

    /** The one directory name, under `filesDir` and under `getExternalFilesDir(null)` alike. */
    const val DIR_NAME: String = "speaker-spike"

    /** The model the fingerprints in a dump came from — the adapter's bundled asset, by name. */
    const val MODEL_ASSET: String = "speaker_campplus_en_16k.onnx"

    /**
     * How long a dump survives: 24 h, swept at the next session start.
     *
     * A session's jsonl is 5-6 KB per fingerprint and a session's WAVs are megabytes, and the
     * thing that reads them is a person with `adb pull` who has either already pulled them or
     * lost interest. Nothing in the app ever reads a dump back.
     */
    const val MAX_AGE_MS: Long = 24L * 60L * 60L * 1_000L

    /** The extensions a sweep or a purge may delete. Never anything else in the directory. */
    val DUMP_EXTENSIONS: List<String> = listOf("jsonl")

    /**
     * Deletes every dump file in [dirs] — both dirs, whatever their age. Anything else in the
     * directory is left alone. Returns how many files went, for a caller that wants to say so.
     */
    fun purge(vararg dirs: File?): Int {
        var gone = 0
        for (dir in dirs) {
            val files = runCatching { dir?.listFiles() }.getOrNull() ?: continue
            for (file in files) {
                if (!file.isFile || file.extension.lowercase(Locale.ROOT) !in DUMP_EXTENSIONS) continue
                if (runCatching { file.delete() }.getOrDefault(false)) gone++
            }
        }
        return gone
    }
}

/**
 * ONE fingerprint, as the tuning loop on the PC needs to read it (4.10 speaker spike, session 2).
 *
 * One of these exists for every VAD segment that was actually FINGERPRINTED — a segment under
 * [SpeakerTracker.MIN_EMBED_SECONDS] and a segment whose embedding came back null have no vector
 * and therefore no row. Everything the offline tuner needs to re-decide the segment is here:
 *
 *  - [emb] is the input to every one of the five changes the spike doc asks for;
 *  - [assigned] and [best] are what the SHIPPED tracker decided, so a candidate can be scored
 *    against the build the owner actually heard;
 *  - [durSec] is the gate in changes 1 and 4 (*"short (< 2 s) … never open a speaker"*, *"two
 *    segments >= 2 s"*);
 *  - [origStart] / [origEnd] name the slice on the original timeline, which is where the audio
 *    the vector came from was.
 *
 * **There is no text field, and there will not be one.** A fingerprint dump is not a transcript;
 * the assigner's callback has no text in it either ([SpeakerAssignment]), so a row here could not
 * carry a word even if somebody asked for it.
 */
data class SpikeFingerprint(
    val seq: Long,
    val seg: Int,
    val durSec: Float,
    val origStart: Int,
    val origEnd: Int,
    val assigned: Int,
    val best: Float,
    val confirmed: Boolean,
    val embedMs: Long,
    val emb: FloatArray,
)

/**
 * The dump's WIRE FORMAT, pure — one JSON object per line, and nothing else in the file.
 *
 * It is a formatter with a test for the same reason [SpeakerDiag] is: the file is an INSTRUMENT.
 * `json.loads` on the PC is the only reader, and every one of these rules exists because breaking
 * it costs an owner device session that cannot be re-run:
 *
 *  - **[Locale.ROOT] on every number.** `String.format` without one takes the device's default and
 *    a German tablet writes `0,1234`, which is not a JSON number — it is two of them.
 *  - **Four decimals on a float.** CAM++'s embeddings are unit-scale; four decimals is about 1e-4
 *    of resolution on a value whose cosine thresholds are being read to two, and it keeps a
 *    512-float row near 5 KB instead of 8.
 *  - **`null`, never `NaN`, for a value that was not measured.** `best` is genuinely absent for the
 *    first speaker of a session (nobody to be compared with), and 0 is a real reading — two
 *    orthogonal voices. `NaN` is not JSON at all in the strict grammar.
 *  - **One header line first**, carrying the thresholds THIS session ran under, so a dump is
 *    self-describing: a tuning run against a jsonl whose band nobody recorded is a measurement of
 *    an unknown build.
 */
object SpikeJson {

    /** The file's first line: what produced the rows below it. */
    fun header(
        session: Long,
        model: String,
        dim: Int,
        tSame: Float,
        tNew: Float,
        minEmbed: Float,
        minNew: Float,
        cap: Int,
    ): String = buildString {
        append("{\"session\":").append(session)
        append(",\"model\":\"").append(model).append('"')
        append(",\"dim\":").append(dim)
        append(",\"tSame\":").append(num(tSame))
        append(",\"tNew\":").append(num(tNew))
        append(",\"minEmbed\":").append(num(minEmbed))
        append(",\"minNew\":").append(num(minNew))
        append(",\"cap\":").append(cap)
        append('}')
    }

    /** One fingerprint. The field order is the spike plan's, so a `head -2` reads like the plan. */
    fun line(record: SpikeFingerprint): String = buildString(12 + record.emb.size * 8) {
        append("{\"seq\":").append(record.seq)
        append(",\"seg\":").append(record.seg)
        append(",\"durSec\":").append(num(record.durSec))
        append(",\"origStart\":").append(record.origStart)
        append(",\"origEnd\":").append(record.origEnd)
        append(",\"assigned\":").append(record.assigned)
        append(",\"best\":").append(num(record.best))
        append(",\"confirmed\":").append(if (record.confirmed) 1 else 0)
        append(",\"emb\":[")
        for (i in record.emb.indices) {
            if (i > 0) append(',')
            append(num(record.emb[i]))
        }
        append(']')
        append(",\"embedMs\":").append(record.embedMs)
        append('}')
    }

    /** Four decimals under [Locale.ROOT], or `null` for anything that is not a finite reading. */
    private fun num(value: Float): String =
        if (value.isFinite()) String.format(Locale.ROOT, "%.4f", value) else "null"
}

/**
 * Where ONE session's dump lives — the two directories and the session's own clock reading.
 *
 * [internalDir] is `filesDir/speaker-spike`, the dump's home. [externalDir] is
 * `getExternalFilesDir(null)/speaker-spike` or null when external storage is not mounted; it is
 * where the jsonl is MIRRORED — see [SpeakerSpikeDump] for why a mirror rather than one or the
 * other.
 *
 * [sessionStartMs] names every file of the session and is the header's `session` field, so a pull
 * off the device sorts by session without any other index.
 */
data class SpeakerSpikeDirs(
    val sessionStartMs: Long,
    val internalDir: File,
    val externalDir: File?,
)

/**
 * ONE session's fingerprint dump on disk (4.10 speaker spike, session 2) — one jsonl line per
 * fingerprint, and not one character of anything else.
 *
 * ### Everything here runs on the `speaker-embed` executor
 *
 * This object is created, opened, written and closed by [SpeakerAssigner] inside the body that
 * runs on its single-thread `speaker-embed` executor, BELOW the chunk's text delivery — never on
 * Main and never on the whisper thread. That is not a convenience: a `flush()` on a session's only
 * embed thread is a few hundred microseconds and costs a label nothing, and the same flush on the
 * whisper thread would sit inside the commit floors spec §3.3 measured without it. No unit test
 * can observe a file write landing on the wrong thread of a service it cannot start, so the
 * confinement lives in [SpeakerAssigner]'s structure: only the body the executor runs names this
 * object at all.
 *
 * Nothing here is synchronised, for the same reason [SpeakerTracker] is not: one instance per
 * session, one thread.
 *
 * ### Two destinations, one line
 *
 * The jsonl is written to [SpeakerSpikeDirs.internalDir] — where the plan put it — AND mirrored
 * into [SpeakerSpikeDirs.externalDir] when there is one. The mirror is not redundancy for its own
 * sake: the builds the owner runs come off the internal Play track, they are not debuggable, so
 * `adb shell run-as` cannot reach `filesDir` at all and a dump that existed only there could never
 * be pulled to the PC the tuning loop runs on. The mirror is best-effort in both directions — a
 * failure to open it, or to write one line to it, never touches the primary and never throws.
 *
 * ### Ages out, and goes entirely when the switch goes
 *
 * [open] sweeps both directories of anything older than [SpeakerSpike.MAX_AGE_MS] before it writes
 * — the sweep is at session start because that is the only moment in the app's life that is already
 * paying for I/O and is off every user-visible path. [SpeakerSpike.purge] is the other half, called
 * when the user turns speaker detection off.
 */
class SpeakerSpikeDump(
    private val dirs: SpeakerSpikeDirs,
    private val model: String,
    private val tSame: Float,
    private val tNew: Float,
    private val minEmbed: Float,
    private val minNew: Float,
    private val cap: Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private var primary: BufferedWriter? = null
    private var mirror: BufferedWriter? = null
    private var opened = false

    /**
     * Writes one fingerprint. Opens the file — and sweeps the old dumps — on the first call, which
     * is what keeps a session that never fingerprints anything from creating a file at all.
     *
     * Never throws. A dump is a diagnostic on a path whose text has already reached the user.
     */
    fun write(record: SpikeFingerprint) {
        if (!SpeakerSpike.SPEAKER_SPIKE) return
        if (!opened) open(record.emb.size)
        val line = SpikeJson.line(record)
        runCatching { primary?.write(line); primary?.write("\n") }
        runCatching { mirror?.write(line); mirror?.write("\n") }
    }

    /**
     * End of a chunk: both writers hit the disk.
     *
     * Per CHUNK and not per line — a chunk is 1-10 fingerprints and one flush amortises the
     * syscall over all of them — and per chunk rather than only at [close] because a session that
     * ends in a crash, an OOM kill or a battery death must still leave every completed chunk
     * behind. Those are exactly the sessions worth reading.
     */
    fun flush() {
        if (!opened) return
        runCatching { primary?.flush() }
        runCatching { mirror?.flush() }
    }

    /** Session end. Flushes and closes both writers; safe to call twice and safe with none open. */
    fun close() {
        runCatching { primary?.flush() }
        runCatching { primary?.close() }
        runCatching { mirror?.flush() }
        runCatching { mirror?.close() }
        primary = null
        mirror = null
    }

    // ------------------------------------------------------------------ internals

    private fun open(dim: Int) {
        opened = true
        val external = dirs.externalDir
        runCatching { dirs.internalDir.mkdirs() }
        runCatching { external?.mkdirs() }
        sweep()

        val name = "${dirs.sessionStartMs}.jsonl"
        primary = writer(File(dirs.internalDir, name))
        mirror = external?.let { writer(File(it, name)) }
        val header = SpikeJson.header(
            session = dirs.sessionStartMs,
            model = model,
            dim = dim,
            tSame = tSame,
            tNew = tNew,
            minEmbed = minEmbed,
            minNew = minNew,
            cap = cap,
        )
        runCatching { primary?.write(header); primary?.write("\n") }
        runCatching { mirror?.write(header); mirror?.write("\n") }
    }

    /** Appending, UTF-8, buffered. Null — never an exception — when the file cannot be opened. */
    private fun writer(file: File): BufferedWriter? = runCatching {
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), BUFFER)
    }.getOrNull()

    /**
     * Deletes dumps older than [SpeakerSpike.MAX_AGE_MS] from both directories — never anything
     * else in them, and never this session's own files, which do not exist yet when this runs.
     */
    private fun sweep() {
        val cutoff = nowMs() - SpeakerSpike.MAX_AGE_MS
        for (dir in listOf(dirs.internalDir, dirs.externalDir)) {
            val files = runCatching { dir?.listFiles() }.getOrNull() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                if (file.extension.lowercase(Locale.ROOT) !in SpeakerSpike.DUMP_EXTENSIONS) continue
                if (file.lastModified() >= cutoff) continue
                runCatching { file.delete() }
            }
        }
    }

    private companion object {
        /** One jsonl line is 5-6 KB at 512 floats; 64 KB holds a whole chunk before a flush. */
        const val BUFFER = 64 * 1024
    }
}
