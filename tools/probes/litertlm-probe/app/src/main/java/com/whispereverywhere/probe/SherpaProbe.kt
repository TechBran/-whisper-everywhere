package com.whispereverywhere.probe

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.locks.LockSupport

/**
 * Rung 3 of the streaming-local-tier plan (research doc 2026-09-09 §5.4): the sherpa-onnx streaming
 * Zipformer (`streaming-zipformer-en-2023-06-26`, four int8 files) through the AAR's `OnlineRecognizer`,
 * on this device, fed the way the app's capture path would feed it.
 *
 * The feed (§3.2 / rung 1 §1.5): 512-sample (32 ms) chunks — `StreamingAudioRecorder` reads 1024-byte
 * PCM16 buffers — `acceptWaveform`, then `decode` while `isReady` (a drain, never one decode), then
 * `getResult().text` (the cumulative text of the open segment). `pace=realtime` sleeps each chunk to
 * the clip's own clock (chunk i is fed no earlier than t0 + (i+1)·32 ms, and late if the previous
 * burst overran — the queue the app would carry); `pace=max` feeds as fast as the loop turns, which is
 * the compute-only RTF read. At the end of every clip: `padms` of zeros, `inputFinished`, drain, take
 * the final — then `release()` the stream and `createStream()` the next one; never `reset` (§3.9-3.10).
 * `enableEndpoint = false` throughout: the app's Silero endpointer cuts, sherpa's never does.
 *
 * Recorded per run: every partial change with its wall time (from the run's t0) and audio time; every
 * `decode()` call's duration; the decode burst per chunk; the final text, tokens and token timestamps;
 * WER against the fixture reference through the app's own `WerMath` (ported verbatim); retractions
 * (a partial whose predecessor is not its prefix); and the per-word partial latency — the wall time
 * of the first partial in which the word appears complete, minus the word's audio time (its last
 * token's timestamp; the rung-1 definition, so the numbers compare). `load=N` spins N busy threads
 * for the whole run as the stand-in for whisper `multi`'s four; `duration=S` loops the clip list for
 * S seconds (the thermal run) with a memory/thermal snapshot every minute.
 *
 * No transcript beyond the fixture clips' own text ever exists here.
 */
class SherpaProbe(private val ctx: Context, private val args: ProbeArgs) {

    private class Clip(val name: String, val samples: FloatArray, val ref: String?) {
        val audioS: Double get() = samples.size / SAMPLE_RATE.toDouble()
    }

    private class Partial(val chunk: Int, val audioS: Double, val wallS: Double, val text: String, val nTokens: Int, val phase: String)

    fun run(res: JSONObject) {
        val modelDir = requireNotNull(args.model) { "--es model is required (the directory holding the four model files)" }
        val clipNames = requireNotNull(args.clips) { "--es clips is required (comma list of files/<name>.wav)" }
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        require(args.pace == "realtime" || args.pace == "max") { "pace must be realtime|max" }
        require(args.padMs >= 0) { "padms must be >= 0" }

        // ---- the environment: CPU flags (the FEAT_SME question of §1.4), sherpa + ORT versions from the AAR itself
        val cpuFeatures = runCatching {
            File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }?.substringAfter(":")?.trim()
        }.getOrNull() ?: "?"
        val sme = cpuFeatures.split(" ").any { it == "sme" || it.startsWith("sme") }
        res.put("cpu_features", cpuFeatures)
        res.put("cpu_sme", sme)
        ProbeLog.i("sherpa|cpuinfo|sme=$sme|features=$cpuFeatures")
        val ver = JSONObject()
        ver.put("sherpa_version", safe { VersionInfo.version })
        ver.put("sherpa_git_sha1", safe { VersionInfo.gitSha1 })
        ver.put("sherpa_git_date", safe { VersionInfo.gitDate })
        // getOnnxruntimeVersion() exists from 1.13.5 on; reflection so the 1.13.4 comparison arm still compiles and runs.
        ver.put("onnxruntime_version", safe {
            VersionInfo.Companion.javaClass.getMethod("getOnnxruntimeVersion").invoke(VersionInfo.Companion) as String
        })
        res.put("versions", ver)
        ProbeLog.i("sherpa|versions|sherpa=${ver.optString("sherpa_version")}|git=${ver.optString("sherpa_git_sha1")} ${ver.optString("sherpa_git_date")}|onnxruntime=${ver.optString("onnxruntime_version")}")

        // ---- the four files, hashed on the device (the PC hashes them too; both sides must agree)
        val enc = File(modelDir, ENCODER)
        val dec = File(modelDir, DECODER)
        val joi = File(modelDir, JOINER)
        val tok = File(modelDir, TOKENS)
        val files = JSONObject()
        for (f in listOf(enc, dec, joi, tok)) {
            require(f.exists()) { "model file missing: ${f.absolutePath}" }
            val h = sha256(f)
            files.put(f.name, JSONObject().put("bytes", f.length()).put("sha256", h))
            ProbeLog.i("sherpa|file|${f.name}|bytes=${f.length()}|sha256=$h")
        }
        res.put("model_files", files)

        // ---- provider: cpu | nnapi | nospin (cpu:<cfg> with ORT's intra/inter-op spinning off, forwarded on >= 1.13.5 only)
        val provider = when (args.provider) {
            "nospin" -> {
                val cfg = File(ctx.filesDir, "ort-nospin.cfg")
                cfg.writeText("SessionConfig.session.intra_op.allow_spinning=0\nSessionConfig.session.inter_op.allow_spinning=0\nDEBUG=1\n")
                "cpu:" + cfg.absolutePath
            }
            else -> args.provider
        }
        res.put("provider", provider)
        res.put("pace", args.pace)
        res.put("pad_ms", args.padMs)
        res.put("loops", args.loops)
        res.put("duration_s", args.duration)
        res.put("load_threads", args.load)
        res.put("chunk_samples", CHUNK)
        res.put("clips", JSONArray(clipNames))

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = enc.absolutePath, decoder = dec.absolutePath, joiner = joi.absolutePath),
                tokens = tok.absolutePath,
                numThreads = args.threads,
                debug = true,          // sherpa logs the config and the model metadata it read — the proving lines
                provider = provider,
                modelType = "zipformer2",
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )
        ProbeLog.i("sherpa|config|threads=${args.threads}|provider=$provider|modelType=zipformer2|decoding=greedy_search|enableEndpoint=false|featureDim=80|dither=0|pace=${args.pace}|padMs=${args.padMs}|loops=${args.loops}|duration=${args.duration}|load=${args.load}")

        Metrics.snapshot(ctx, "before_load").let { res.put("mem_before_load", it) }
        val t0 = System.nanoTime()
        val rec = OnlineRecognizer(null, config)
        val loadMs = (System.nanoTime() - t0) / 1e6
        res.put("load_ms", loadMs)
        ProbeLog.i("sherpa|load_ms=${"%.1f".format(loadMs)}")
        Metrics.snapshot(ctx, "after_load").let { res.put("mem_after_load", it) }

        val clips = clipNames.map { name ->
            val f = File(ctx.filesDir, name)
            require(f.exists()) { "clip not found: ${f.absolutePath}" }
            val samples = readWav16kMono(f)
            val stem = name.removeSuffix(".wav")
            ProbeLog.i("sherpa|clip|$name|bytes=${f.length()}|samples=${samples.size}|audio_s=${"%.3f".format(samples.size / SAMPLE_RATE.toDouble())}|sha256=${sha256(f)}|ref=${REFS[stem] != null}")
            Clip(stem, samples, REFS[stem])
        }

        // ---- warm-up: the first second of the first clip, max pace, its own stream, released. The first
        // decode of a fresh recognizer pays ORT's first-call cost; the app would pay it at load, not mid-utterance.
        val w0 = System.nanoTime()
        val warm = runOnce(rec, Clip("warmup", clips[0].samples.copyOf(minOf(SAMPLE_RATE, clips[0].samples.size)), null), "max", args.padMs, w0)
        res.put("warmup", JSONObject().put("wall_ms", warm.getDouble("total_wall_ms")).put("decode_ms", warm.getDouble("decode_total_ms")).put("n_decodes", warm.getInt("n_decodes")).put("first_decode_ms", warm.optDouble("first_decode_ms")))
        ProbeLog.i("sherpa|warmup|wall_ms=${"%.1f".format(warm.getDouble("total_wall_ms"))}|decode_ms=${"%.1f".format(warm.getDouble("decode_total_ms"))}|first_decode_ms=${"%.1f".format(warm.optDouble("first_decode_ms"))}|text=${warm.getString("final")}")
        Metrics.snapshot(ctx, "after_warm").let { res.put("mem_after_warm", it) }

        // ---- the busy-loop stand-in for whisper multi's n_threads = 4 (a steady 100 % on N cores; multi is burstier)
        val loadStop = java.util.concurrent.atomic.AtomicBoolean(false)
        val loadThreads = (0 until args.load).map { i ->
            Thread({
                var x = 1.0000001
                var n = 0L
                while (!loadStop.get()) {
                    x = x * 1.0000001 + 1e-9
                    if (x > 1e300) x = 1.0
                    n++
                }
                LOAD_SINK = x + n
            }, "busy-$i").apply { isDaemon = true; start() }
        }
        if (loadThreads.isNotEmpty()) { Thread.sleep(500); Metrics.snapshot(ctx, "under_load_idle").let { res.put("mem_under_load_idle", it) } }

        // ---- the runs
        val runs = JSONArray()
        val snapshots = JSONArray()
        val runStart = System.nanoTime()
        var lastSnapNs = runStart
        var index = 0
        fun oneRound() {
            for (c in clips) {
                val r = runOnce(rec, c, args.pace, args.padMs, System.nanoTime())
                r.put("index", index)
                r.put("t_start_s", (r.getLong("t0_ns") - runStart) / 1e9)
                r.remove("t0_ns")
                runs.put(r)
                ProbeLog.i("sherpa|run=$index|clip=${c.name}|pace=${args.pace}|pad_ms=${args.padMs}|threads=${args.threads}|audio_s=${"%.3f".format(c.audioS)}|decode_ms=${"%.1f".format(r.getDouble("decode_total_ms"))}|rtf=${"%.4f".format(r.getDouble("rtf_compute"))}|rtf_wall=${"%.4f".format(r.getDouble("rtf_wall"))}|decodes=${r.getInt("n_decodes")}|partials=${r.getInt("n_partials")}|retractions=${r.getInt("n_retractions")}|late_chunks=${r.getInt("late_chunks")}|max_late_ms=${"%.1f".format(r.getDouble("max_late_ms"))}|burst_p50=${fmt(r.optJSONObject("decode_burst_ms"), "p50")}|burst_p95=${fmt(r.optJSONObject("decode_burst_ms"), "p95")}|burst_max=${fmt(r.optJSONObject("decode_burst_ms"), "max")}|lag_wall_p50=${fmt(r.optJSONObject("lag_wall_s"), "p50")}|lag_wall_p95=${fmt(r.optJSONObject("lag_wall_s"), "p95")}|lag_audio_p95=${fmt(r.optJSONObject("lag_audio_s"), "p95")}|wer=${r.optString("wer", "-")}|canary=${r.optString("canary_passes", "-")}")
                ProbeLog.i("sherpa|run=$index|final=${r.getString("final")}")
                if (index == 0 || args.duration > 0 && index < 2) {
                    val ps = r.getJSONArray("partials")
                    for (i in 0 until ps.length()) {
                        val p = ps.getJSONObject(i)
                        ProbeLog.i("sherpa|run=$index|partial|audio_s=${"%.3f".format(p.getDouble("audio_s"))}|wall_s=${"%.3f".format(p.getDouble("wall_s"))}|${p.optString("phase", "feed")}|${p.getString("text")}")
                    }
                }
                index++
                val now = System.nanoTime()
                if (args.duration > 0 && now - lastSnapNs >= 60_000_000_000L) {
                    lastSnapNs = now
                    snapshots.put(Metrics.snapshot(ctx, "t+${((now - runStart) / 1e9).toInt()}s").put("t_s", (now - runStart) / 1e9).put("runs_done", index))
                }
            }
        }
        if (args.duration > 0) {
            while ((System.nanoTime() - runStart) / 1e9 < args.duration) oneRound()
        } else {
            repeat(args.loops) { oneRound() }
        }
        val runsWallS = (System.nanoTime() - runStart) / 1e9
        loadStop.set(true)
        loadThreads.forEach { it.join(2000) }
        res.put("runs", runs)
        res.put("n_runs", index)
        res.put("runs_wall_s", runsWallS)
        res.put("snapshots", snapshots)
        Metrics.snapshot(ctx, "end").let { res.put("mem_end", it) }

        // ---- aggregate: RTF across runs (all, and the last half = "sustained"), lag distribution across every word
        val rtfs = ArrayList<Double>()
        val lagsWall = ArrayList<Double>()
        val lagsAudio = ArrayList<Double>()
        val bursts = ArrayList<Double>()
        var retractions = 0
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            rtfs.add(r.getDouble("rtf_compute"))
            retractions += r.getInt("n_retractions")
            r.optJSONArray("lag_wall_all")?.let { a -> for (j in 0 until a.length()) lagsWall.add(a.getDouble(j)) }
            r.optJSONArray("lag_audio_all")?.let { a -> for (j in 0 until a.length()) lagsAudio.add(a.getDouble(j)) }
            r.optJSONArray("decode_burst_all")?.let { a -> for (j in 0 until a.length()) bursts.add(a.getDouble(j)) }
        }
        val agg = JSONObject()
        agg.put("rtf_compute", pct(rtfs))
        agg.put("rtf_compute_last_half", pct(rtfs.drop(rtfs.size / 2)))
        agg.put("lag_wall_s", pct(lagsWall))
        agg.put("lag_audio_s", pct(lagsAudio))
        agg.put("decode_burst_ms", pct(bursts))
        agg.put("retractions", retractions)
        res.put("aggregate", agg)
        ProbeLog.i("sherpa|aggregate|runs=$index|wall_s=${"%.1f".format(runsWallS)}|rtf_mean=${fmt(agg.getJSONObject("rtf_compute"), "mean")}|rtf_max=${fmt(agg.getJSONObject("rtf_compute"), "max")}|rtf_last_half_mean=${fmt(agg.getJSONObject("rtf_compute_last_half"), "mean")}|lag_wall_p50=${fmt(agg.getJSONObject("lag_wall_s"), "p50")}|lag_wall_p95=${fmt(agg.getJSONObject("lag_wall_s"), "p95")}|lag_wall_max=${fmt(agg.getJSONObject("lag_wall_s"), "max")}|lag_audio_p95=${fmt(agg.getJSONObject("lag_audio_s"), "p95")}|burst_p95=${fmt(agg.getJSONObject("decode_burst_ms"), "p95")}|burst_max=${fmt(agg.getJSONObject("decode_burst_ms"), "max")}|retractions=$retractions")

        rec.release()
        Metrics.snapshot(ctx, "after_release").let { res.put("mem_after_release", it) }
    }

    /** One clip through one fresh stream: feed, pad, finish, drain, final, release. */
    private fun runOnce(rec: OnlineRecognizer, clip: Clip, pace: String, padMs: Int, t0: Long): JSONObject {
        val samples = clip.samples
        val n = samples.size
        val nChunks = (n + CHUNK - 1) / CHUNK
        val stream: OnlineStream = rec.createStream("")
        val partials = ArrayList<Partial>()
        val perDecodeMs = ArrayList<Double>()
        val burstMs = ArrayList<Double>()
        var nDecodes = 0
        var decodeFeedNs = 0L
        var lateChunks = 0
        var maxLateNs = 0L
        var prev = ""
        var firstDecodeMs = -1.0
        val chunk = FloatArray(CHUNK)
        val realtime = pace == "realtime"
        for (i in 0 until nChunks) {
            if (realtime) {
                val deadline = t0 + (i + 1) * CHUNK_NS
                var now = System.nanoTime()
                if (now < deadline) {
                    while (now < deadline) { LockSupport.parkNanos(deadline - now); now = System.nanoTime() }
                } else if (i > 0) {
                    lateChunks++
                    if (now - deadline > maxLateNs) maxLateNs = now - deadline
                }
            }
            val len = minOf(CHUNK, n - i * CHUNK)
            val buf = if (len == CHUNK) { System.arraycopy(samples, i * CHUNK, chunk, 0, CHUNK); chunk } else samples.copyOfRange(i * CHUNK, i * CHUNK + len)
            stream.acceptWaveform(buf, SAMPLE_RATE)
            var nd = 0
            val b0 = System.nanoTime()
            while (rec.isReady(stream)) {
                val d0 = System.nanoTime()
                rec.decode(stream)
                val dMs = (System.nanoTime() - d0) / 1e6
                perDecodeMs.add(dMs)
                if (firstDecodeMs < 0) firstDecodeMs = dMs
                nDecodes++; nd++
            }
            val b1 = System.nanoTime()
            if (nd > 0) {
                decodeFeedNs += b1 - b0
                burstMs.add((b1 - b0) / 1e6)
                val r = rec.getResult(stream)
                val text = r.text
                if (text != prev) {
                    partials.add(Partial(i + 1, minOf((i + 1) * CHUNK, n) / SAMPLE_RATE.toDouble(), (System.nanoTime() - t0) / 1e9, text, r.tokens.size, "feed"))
                    prev = text
                }
            }
        }
        val feedWallNs = System.nanoTime() - t0
        // the tail: pad >= T frames of zeros (rung 1 measured the floor at 500 ms on the canary), inputFinished, drain
        val pad = FloatArray(padMs * SAMPLE_RATE / 1000)
        val p0 = System.nanoTime()
        if (pad.isNotEmpty()) stream.acceptWaveform(pad, SAMPLE_RATE)
        stream.inputFinished()
        var tailDecodes = 0
        val tb0 = System.nanoTime()
        while (rec.isReady(stream)) {
            val d0 = System.nanoTime()
            rec.decode(stream)
            perDecodeMs.add((System.nanoTime() - d0) / 1e6)
            nDecodes++; tailDecodes++
        }
        val decodeTailNs = System.nanoTime() - tb0
        val fin = rec.getResult(stream)
        val totalWallNs = System.nanoTime() - t0
        val tailWallNs = System.nanoTime() - p0
        if (fin.text != prev) partials.add(Partial(nChunks, n / SAMPLE_RATE.toDouble(), totalWallNs / 1e9, fin.text, fin.tokens.size, "tail"))
        stream.release()

        val o = JSONObject()
        o.put("clip", clip.name)
        o.put("pace", pace)
        o.put("pad_ms", padMs)
        o.put("t0_ns", t0)
        o.put("audio_s", clip.audioS)
        o.put("n_chunks", nChunks)
        o.put("final", fin.text)
        o.put("tokens", JSONArray(fin.tokens.toList()))
        o.put("timestamps", JSONArray(fin.timestamps.map { it.toDouble() }))
        o.put("n_decodes", nDecodes)
        o.put("tail_decodes", tailDecodes)
        o.put("decode_feed_ms", decodeFeedNs / 1e6)
        o.put("decode_tail_ms", decodeTailNs / 1e6)
        o.put("decode_total_ms", (decodeFeedNs + decodeTailNs) / 1e6)
        o.put("feed_wall_ms", feedWallNs / 1e6)
        o.put("tail_wall_ms", tailWallNs / 1e6)
        o.put("total_wall_ms", totalWallNs / 1e6)
        o.put("rtf_compute", (decodeFeedNs + decodeTailNs) / 1e9 / clip.audioS)
        o.put("rtf_compute_feed", decodeFeedNs / 1e9 / clip.audioS)
        o.put("rtf_wall", totalWallNs / 1e9 / clip.audioS)
        o.put("first_decode_ms", firstDecodeMs)
        o.put("late_chunks", lateChunks)
        o.put("max_late_ms", maxLateNs / 1e6)
        o.put("per_decode_ms", pct(perDecodeMs))
        o.put("decode_burst_ms", pct(burstMs))
        o.put("decode_burst_all", JSONArray(burstMs))
        val ps = JSONArray()
        for (p in partials) ps.put(JSONObject().put("chunk", p.chunk).put("audio_s", p.audioS).put("wall_s", p.wallS).put("text", p.text).put("n_tokens", p.nTokens).put("phase", p.phase))
        o.put("partials", ps)
        o.put("n_partials", partials.size)
        // retractions: a partial whose immediate predecessor is not a prefix of it (a rewrite, not a growth)
        val retr = JSONArray()
        var pv = ""
        for (p in partials) {
            if (pv.isNotEmpty() && !p.text.startsWith(pv)) retr.put(JSONObject().put("audio_s", p.audioS).put("was", pv).put("now", p.text))
            pv = p.text
        }
        o.put("retractions", retr)
        o.put("n_retractions", retr.length())
        // WER against the fixture reference, through the app's WerMath
        if (clip.ref != null) {
            o.put("ref", clip.ref)
            o.put("wer", WerMath.wer(clip.ref, fin.text))
            o.put("wer_ref_tokens", WerMath.tokens(clip.ref).size)
            o.put("wer_edits", Math.round(WerMath.wer(clip.ref, fin.text) * WerMath.tokens(clip.ref).size))
        }
        if (clip.name == "canary_digits") {
            o.put("canary_exact", WerMath.tokens(fin.text) == WerMath.tokens(REFS["canary_digits"]!!))
            o.put("canary_passes", canaryPasses(fin.text))
        }
        // partial latency per word: the word's audio time = its LAST token's timestamp (rung 1's definition);
        // the partial = the first whose k-th WerMath word EQUALS the final's k-th word (a half-piece like COUNT
        // for COUNTRY does not count — the strip has not shown the word yet).
        val finalWords = WerMath.tokens(fin.text)
        val wordEnd = wordEndTimes(fin.tokens, fin.timestamps)
        o.put("word_map_ok", wordEnd.size == finalWords.size)
        o.put("word_ends_s", JSONArray(wordEnd))
        o.put("n_word_ends", wordEnd.size)
        o.put("n_final_words", finalWords.size)
        val lagWall = ArrayList<Double>()
        val lagAudio = ArrayList<Double>()
        val perWord = JSONArray()
        if (wordEnd.size == finalWords.size) {
            val firstWall = DoubleArray(finalWords.size) { Double.NaN }
            val firstAudio = DoubleArray(finalWords.size) { Double.NaN }
            for (p in partials) {
                val pw = WerMath.tokens(p.text)
                for (k in finalWords.indices) {
                    if (firstWall[k].isNaN() && k < pw.size && pw[k] == finalWords[k]) { firstWall[k] = p.wallS; firstAudio[k] = p.audioS }
                }
            }
            for (k in finalWords.indices) {
                if (!firstWall[k].isNaN()) {
                    val lw = firstWall[k] - wordEnd[k]
                    val la = firstAudio[k] - wordEnd[k]
                    lagWall.add(lw); lagAudio.add(la)
                    perWord.put(JSONObject().put("word", finalWords[k]).put("end_s", wordEnd[k]).put("first_wall_s", firstWall[k]).put("first_audio_s", firstAudio[k]).put("lag_wall_s", lw).put("lag_audio_s", la))
                }
            }
        }
        o.put("words", perWord)
        o.put("lag_wall_s", pct(lagWall))
        o.put("lag_audio_s", pct(lagAudio))
        o.put("lag_wall_all", JSONArray(lagWall))
        o.put("lag_audio_all", JSONArray(lagAudio))
        return o
    }

    /**
     * Words from the BPE pieces: a piece starting with U+2581 opens a word; each word's end = its last piece's
     * timestamp. Measured on the Tab (rung 3, first run): the AAR's JNI hands `tokens` back with the U+2581
     * already turned into an ASCII space (`" ONE", " TWO", " F", "OUR", " FI", "VE"`), so a leading space opens a
     * word too — without that every piece folded into one word and `word_map_ok` was false on every clip.
     */
    private fun wordEndTimes(tokens: Array<String>, ts: FloatArray): List<Double> {
        val ends = ArrayList<Double>()
        var open = false
        for (i in tokens.indices) {
            val t = tokens[i]
            val startsWord = t.startsWith("▁") || t.startsWith(" ")
            val body = t.removePrefix("▁").removePrefix(" ")
            if (startsWord || !open) {
                if (body.isEmpty()) continue        // a bare "▁" piece is a space, not a word
                ends.add(ts.getOrElse(i) { Float.NaN }.toDouble()); open = true
            } else {
                ends[ends.size - 1] = ts.getOrElse(i) { Float.NaN }.toDouble()
            }
        }
        return ends
    }

    private fun canaryPasses(text: String): Boolean {
        // GpuCanaryPolicy.canaryPasses, ported: its NON_WORD excludes the apostrophe (WerMath's includes it).
        val tokens = text.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 20) return false
        val seen = tokens.toMutableSet()
        for (t in tokens) if (t.length >= 2 && t.all { it.isDigit() }) for (c in t) seen.add(c.toString())
        val expected = listOf(setOf("one", "1"), setOf("two", "2"), setOf("three", "3"), setOf("four", "4"), setOf("five", "5"))
        return expected.count { al -> al.any { it in seen } } >= 4
    }

    private fun pct(v: List<Double>): JSONObject {
        val o = JSONObject()
        o.put("n", v.size)
        if (v.isEmpty()) return o
        val s = v.sorted()
        fun q(p: Double): Double { val idx = Math.ceil(p * s.size).toInt() - 1; return s[idx.coerceIn(0, s.size - 1)] }
        o.put("mean", v.average()); o.put("p50", q(0.50)); o.put("p90", q(0.90)); o.put("p95", q(0.95)); o.put("p99", q(0.99))
        o.put("min", s.first()); o.put("max", s.last())
        return o
    }

    private fun fmt(o: JSONObject?, k: String): String = if (o != null && o.has(k)) "%.3f".format(o.getDouble(k)) else "-"
    private fun safe(f: () -> String): String = try { f() } catch (t: Throwable) { "n/a (" + t.javaClass.simpleName + ")" }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val b = ByteArray(1 shl 16); while (true) { val r = ins.read(b); if (r < 0) break; md.update(b, 0, r) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** PCM16 mono 16 kHz WAV -> float [-1, 1). Walks the chunk list (CanaryAudio.dataChunk's rule), refuses any other format. */
    private fun readWav16kMono(f: File): FloatArray {
        val b = f.readBytes()
        require(b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WAVE") { "${f.name}: not a RIFF/WAVE file" }
        var i = 12
        var fmtOk = false
        var data: ByteArray? = null
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(b, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val start = i + 8
            if (id == "fmt " && size >= 16) {
                val bb = ByteBuffer.wrap(b, start, 16).order(ByteOrder.LITTLE_ENDIAN)
                val fmt = bb.short.toInt(); val ch = bb.short.toInt(); val sr = bb.int; bb.int; bb.short; val bits = bb.short.toInt()
                require(fmt == 1 && ch == 1 && sr == SAMPLE_RATE && bits == 16) { "${f.name}: want PCM16 mono 16 kHz, got fmt=$fmt ch=$ch sr=$sr bits=$bits" }
                fmtOk = true
            } else if (id == "data") {
                data = b.copyOfRange(start, minOf(b.size, start + size))
            }
            i = start + size + (size and 1)
        }
        require(fmtOk && data != null) { "${f.name}: fmt/data chunk missing" }
        val sb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(sb.remaining()) { sb.get(it) / 32768f }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK = 512                       // 32 ms: StreamingAudioRecorder's 1024-byte PCM16 read
        const val CHUNK_NS = CHUNK * 1_000_000_000L / SAMPLE_RATE
        const val ENCODER = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
        const val DECODER = "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
        const val JOINER = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
        const val TOKENS = "tokens.txt"
        @Volatile @JvmStatic var LOAD_SINK = 0.0
        /** The fixture references (rung 1 §1.7): the canary's asset contract is the WORDS one..five; jfk is whisper.cpp's sample sentence. */
        val REFS = mapOf(
            "canary_digits" to "one two three four five",
            "jfk" to "And so my fellow Americans, ask not what your country can do for you, ask what you can do for your country.",
            "jfk-gated" to "And so my fellow Americans, ask not what your country can do for you, ask what you can do for your country.",
        )
    }
}
