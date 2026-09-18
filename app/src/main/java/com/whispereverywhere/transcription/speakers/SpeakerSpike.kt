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
 * [SPEAKER_SPIKE] is the compile-time switch that makes it exist at all, and
 * `SpeakerSpikePinTest` is the reason it cannot be forgotten.
 *
 * ### It worked, and the answer was the MODEL
 *
 * Session 2 of that same doc is what came back: three clips (exactly one, two and three
 * speakers), 132 segments' fingerprints and audio pulled to the PC, and five embedding models
 * scored on them offline — which is the loop this file exists to close, and it cost the owner one
 * device session instead of one per candidate. The verdict was not a band: CAM++ scores its own
 * single voice against itself as low as **0.04**, so *"the rules cannot rescue the signal"*, and
 * the model was swapped to NeMo TitaNet-small. [MODEL_ASSET] names whatever is bundled TODAY, and
 * a dump's header carries it, so a jsonl pulled off the device can never be scored against a
 * model it did not come from.
 *
 * ### The audio half stores speech, and the app's rule is that audio is never retained
 *
 * A jsonl of 192-float vectors is not recoverable audio. A WAV is. Item 5 of the spike's plan —
 * *"if CAM++ still splits one voice after 1-4, try WeSpeaker ResNet34 / ERes2Net on the same
 * dumped audio"* — needs the original slices, and there is no way to get a different model's
 * embeddings out of this one's. So the audio dump exists, and it is:
 *
 *  - **off unless a file exists** ([AUDIO_FLAG], see [SpeakerSpikeDump]) — no preference, no
 *    Settings row, nothing a user can turn on by accident and nothing they can leave on;
 *  - **deleted with everything else** when detection is switched off, and after 24 h;
 *  - **gone before 4.10.0 ships.** [SPEAKER_SPIKE] must be `false` by then, and the pin asserts
 *    exactly that pairing rather than trusting anyone to remember it.
 *
 * **The standing warning, in the words the plan asked for it in.** The audio dump is a
 * SPIKE-ONLY DIAGNOSTIC, off by default, and it STORES SPEECH AUDIO.
 * In this app, audio is deliberately never retained — that is the promise the whole product
 * rests on — so the mechanism
 * must be removed or put behind explicit user consent before any production release.
 * `SpeakerSpikePinTest` is the enforcement and this paragraph is the reason.
 */
object SpeakerSpike {

    /**
     * TRUE while this is a spike build, and the guard on every line of dump machinery.
     *
     * `SpeakerSpikePinTest` asserts `!(SPEAKER_SPIKE && versionName == "4.10.0")`: the release that
     * ships speaker labels cannot also ship the dump. Flipping this to `false` is what makes that
     * test green — and because it is a `const`, flipping it lets the compiler strip every call
     * below it rather than leaving dead-but-reachable file writers in the APK.
     */
    const val SPEAKER_SPIKE: Boolean = true

    /** The one directory name, under `filesDir` and under `getExternalFilesDir(null)` alike. */
    const val DIR_NAME: String = "speaker-spike"

    /**
     * The flag file whose mere EXISTENCE turns audio dumping on for a session.
     *
     * A file rather than a preference because of what the owner's devices actually are: he runs
     * RELEASE builds off the internal track, `run-as` is unavailable on those, and there is no
     * broadcast receiver worth adding to the manifest for a diagnostic. `getExternalFilesDir` is
     * the one app-private directory `adb` can write without `run-as`, so the controller enables a
     * session's audio dump with one `adb shell touch` and disables it with one `adb shell rm`.
     */
    const val AUDIO_FLAG: String = "DUMP_AUDIO"

    /** The model the fingerprints in a dump came from — the adapter's bundled asset, by name. */
    const val MODEL_ASSET: String = "speaker_titanet_small_16k.onnx"

    /**
     * How long a dump survives: 24 h, swept at the next session start.
     *
     * A session's jsonl is ~1.4 KB per fingerprint and a session's WAVs are megabytes, and the
     * thing that reads them is a person with `adb pull` who has either already pulled them or
     * lost interest. Nothing in the app ever reads a dump back.
     */
    const val MAX_AGE_MS: Long = 24L * 60L * 60L * 1_000L

    /** The two extensions a sweep or a purge may delete. Never the flag file, never anything else. */
    val DUMP_EXTENSIONS: List<String> = listOf("jsonl", "wav")

    /**
     * Deletes every dump file in [dirs] — both dirs, both extensions, whatever their age.
     *
     * The flag file is LEFT: it is the controller's switch, not a dump, and a purge that silently
     * disarmed it would make the next session's missing audio look like a bug in the dumper.
     * Returns how many files went, for a caller that wants to say so.
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
 *  - [origStart] / [origEnd] name the slice on the original timeline, which is what joins a row to
 *    its WAV when the audio half is armed.
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
 *  - **Four decimals on a float.** The embeddings are unit-scale; four decimals is about 1e-4
 *    of resolution on a value whose cosine thresholds are being read to two, and it keeps a
 *    192-float row near 1.4 KB instead of 2.
 *  - **`null`, never `NaN`, for a value that was not measured.** `best` is genuinely absent for the
 *    first speaker of a session (nobody to be compared with), and 0 is a real reading — two
 *    orthogonal voices. `NaN` is not JSON at all in the strict grammar.
 *  - **One header line first**, carrying every RULE this session ran under — the band, the
 *    open floor, the recent-fingerprint window, the confirm count, the cap — so a dump is
 *    self-describing: a tuning run against a jsonl whose rules nobody recorded is a
 *    measurement of an unknown build.
 */
object SpikeJson {

    /**
     * The file's first line: what produced the rows below it.
     *
     * Every RULE the session ran under, not only its two thresholds. Session 2 changed four of
     * them at once — the open floor, the recent-fingerprint window, the confirm count and the
     * band — so a header that carried the band alone would leave a dump indistinguishable from
     * one taken under a different tracker, which is the one thing a tuning loop cannot recover
     * from.
     */
    fun header(
        session: Long,
        model: String,
        dim: Int,
        tSame: Float,
        tNew: Float,
        minEmbed: Float,
        minOpen: Float,
        recentK: Int,
        confirmN: Int,
        cap: Int,
    ): String = buildString {
        append("{\"session\":").append(session)
        append(",\"model\":\"").append(model).append('"')
        append(",\"dim\":").append(dim)
        append(",\"tSame\":").append(num(tSame))
        append(",\"tNew\":").append(num(tNew))
        append(",\"minEmbed\":").append(num(minEmbed))
        append(",\"minOpen\":").append(num(minOpen))
        append(",\"recentK\":").append(recentK)
        append(",\"confirmN\":").append(confirmN)
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
 * A float slice as a 16 kHz mono 16-bit PCM WAV — the ONE audio writer in the spike (item 5).
 *
 * 44-byte canonical header, little-endian throughout, `audioFormat = 1` (uncompressed PCM). Written
 * by hand rather than through any library because the reader is `soundfile`/`librosa` on the PC and
 * the only thing that can go wrong is arithmetic: a wrong `dataSize` truncates the tail, a wrong
 * `byteRate` plays a voice at the wrong pitch, and either makes a second model's embeddings on this
 * audio meaningless without being obviously broken.
 *
 * Samples arrive as the floats the embedder saw — mono, nominally in [-1, 1] — and are CLAMPED
 * before scaling by 32_767. whisper's own buffers can carry a sample slightly past 1.0, and an
 * unclamped `toInt()` on 1.00003 wraps a positive peak to a large negative sample: an audible click
 * that a listener would attribute to the recording rather than to this function.
 */
object SpikeWav {

    /** The canonical PCM header: 12-byte RIFF chunk, 24-byte `fmt `, 8-byte `data` preamble. */
    const val HEADER_BYTES: Int = 44

    const val BITS_PER_SAMPLE: Int = 16
    const val CHANNELS: Int = 1

    /** [pcm] at [sampleRate], mono, as a complete WAV file. */
    fun mono16(pcm: FloatArray, sampleRate: Int = SpeakerAssigner.SAMPLE_RATE): ByteArray {
        val bytesPerSample = BITS_PER_SAMPLE / 8
        val dataBytes = pcm.size * bytesPerSample * CHANNELS
        val out = ByteArray(HEADER_BYTES + dataBytes)
        var at = 0

        fun ascii(s: String) { for (c in s) out[at++] = c.code.toByte() }
        fun le32(v: Int) {
            out[at++] = (v and 0xFF).toByte()
            out[at++] = ((v ushr 8) and 0xFF).toByte()
            out[at++] = ((v ushr 16) and 0xFF).toByte()
            out[at++] = ((v ushr 24) and 0xFF).toByte()
        }
        fun le16(v: Int) {
            out[at++] = (v and 0xFF).toByte()
            out[at++] = ((v ushr 8) and 0xFF).toByte()
        }

        ascii("RIFF")
        // Everything after this field: the 4-byte "WAVE" tag plus both remaining chunks.
        le32(HEADER_BYTES - 8 + dataBytes)
        ascii("WAVE")
        ascii("fmt ")
        le32(16)                                              // PCM fmt chunk body size
        le16(1)                                               // audioFormat: uncompressed PCM
        le16(CHANNELS)
        le32(sampleRate)
        le32(sampleRate * CHANNELS * bytesPerSample)          // byteRate
        le16(CHANNELS * bytesPerSample)                       // blockAlign
        le16(BITS_PER_SAMPLE)
        ascii("data")
        le32(dataBytes)

        for (sample in pcm) {
            val clamped = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
            le16((clamped * 32_767f).toInt() and 0xFFFF)
        }
        return out
    }
}

/**
 * Where ONE session's dump lives — the two directories and the session's own clock reading.
 *
 * [internalDir] is `filesDir/speaker-spike`, the dump's home. [externalDir] is
 * `getExternalFilesDir(null)/speaker-spike` or null when external storage is not mounted; it is
 * where the flag file is looked for, where the WAVs go, and where the jsonl is MIRRORED — see
 * [SpeakerSpikeDump] for why a mirror rather than one or the other.
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
 * ONE session's fingerprint dump on disk (4.10 speaker spike, session 2) — a jsonl of embeddings
 * and, only behind the flag file, the audio they were computed from.
 *
 * ### Everything here runs on the `speaker-embed` executor
 *
 * This object is created, opened, written and closed by [SpeakerAssigner] inside the body that
 * runs on its single-thread `speaker-embed` executor, BELOW the chunk's text delivery — never on
 * Main and never on the whisper thread. That is not a convenience: a `flush()` on a session's only
 * embed thread is a few hundred microseconds and costs a label nothing, and the same flush on the
 * whisper thread would sit inside the commit floors spec §3.3 measured without it.
 * `SpeakerSpikePinTest` pins the confinement as source, because no unit test can observe a file
 * write happening on the wrong thread of a service it cannot start.
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
 * ### The audio half is a file flag, and it is read ONCE per session
 *
 * `<externalDir>/DUMP_AUDIO` — see [SpeakerSpike.AUDIO_FLAG]. Its existence is read at [open] and
 * held for the session, so a flag created or removed mid-session changes nothing until the next
 * one: a dump whose audio starts appearing at chunk 9 is worse than one with none.
 *
 * This half is a SPIKE-ONLY DIAGNOSTIC, off by default, and it STORES SPEECH AUDIO.
 * In this app, audio is deliberately never retained, so the mechanism
 * must be removed or put behind explicit user consent before any production release —
 * see [SpeakerSpike] for the whole of that argument.
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
    private val minOpen: Float,
    private val recentK: Int,
    private val confirmN: Int,
    private val cap: Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private var primary: BufferedWriter? = null
    private var mirror: BufferedWriter? = null
    private var opened = false

    /** Whether the flag file was present when this session opened. Read once, at [open]. */
    var audioArmed: Boolean = false
        private set

    /**
     * Writes one fingerprint. Opens the file — and sweeps the old dumps — on the first call, which
     * is what keeps a session that never fingerprints anything from creating a file at all.
     *
     * [audio] is the ORIGINAL slice the embedder was handed. It is written as a WAV beside the
     * jsonl only when [audioArmed]; otherwise it is ignored and nothing about it is retained.
     *
     * Never throws. A dump is a diagnostic on a path whose text has already reached the user.
     */
    fun write(record: SpikeFingerprint, audio: FloatArray?) {
        if (!SpeakerSpike.SPEAKER_SPIKE) return
        if (!opened) open(record.emb.size)
        val line = SpikeJson.line(record)
        runCatching { primary?.write(line); primary?.write("\n") }
        runCatching { mirror?.write(line); mirror?.write("\n") }
        if (audioArmed && audio != null && audio.isNotEmpty()) writeWav(record, audio)
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
        audioArmed = runCatching {
            external != null && File(external, SpeakerSpike.AUDIO_FLAG).exists()
        }.getOrDefault(false)
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
            minOpen = minOpen,
            recentK = recentK,
            confirmN = confirmN,
            cap = cap,
        )
        runCatching { primary?.write(header); primary?.write("\n") }
        runCatching { mirror?.write(header); mirror?.write("\n") }
    }

    /** Appending, UTF-8, buffered. Null — never an exception — when the file cannot be opened. */
    private fun writer(file: File): BufferedWriter? = runCatching {
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8), BUFFER)
    }.getOrNull()

    private fun writeWav(record: SpikeFingerprint, audio: FloatArray) {
        val dir = dirs.externalDir ?: return
        val file = File(dir, "${dirs.sessionStartMs}-${record.seq}-${record.seg}.wav")
        runCatching { file.writeBytes(SpikeWav.mono16(audio)) }
    }

    /**
     * Deletes dumps older than [SpeakerSpike.MAX_AGE_MS] from both directories — never the flag
     * file (it is the controller's switch, not a dump) and never this session's own files, which
     * do not exist yet when this runs.
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
        /** One jsonl line is ~1.4 KB at 192 floats; 64 KB holds a whole chunk before a flush. */
        const val BUFFER = 64 * 1024
    }
}
