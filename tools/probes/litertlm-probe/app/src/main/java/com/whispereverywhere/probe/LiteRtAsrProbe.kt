package com.whispereverywhere.probe

import android.content.Context
import com.whispereverywhere.npu.LiteRtAsrNative
import com.whispereverywhere.npu.NpuDecodePolicy
import com.whispereverywhere.npu.NpuDecodeStats
import com.whispereverywhere.npu.WhisperTokens
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * `mode=litertasr`: P1's DEVICE GATE (plan P1b task 9) - the app's `liblitertasr.so`, in the product's
 * shape, on the tablet:
 *
 *  - the library and the JNI names are the app's: `com.whispereverywhere.npu.LiteRtAsrNative` is the app's own
 *    source, compiled into this APK verbatim by the `stageAppSeam` task, and `liblitertasr.so` (with the app's
 *    `libc++_shared.so` and `libLiteRt.so` 2.1.1) is staged into jniLibs by
 *    `tools/mtk-apu/stage_litertasr_into_probe.py` from the app's built APK;
 *  - the dispatch loads from `files/litert_dispatch/` (staged here from this APK's own copy when absent; the
 *    directory must hold exactly that one file), no compiler plugin is configured, and the merged manifest
 *    declares only `libneuronusdk_adapter.mtk.so` among the MediaTek libraries;
 *  - every buffer is requirement-typed (that is the library's only way to make one);
 *  - the prompt, the masks, the ladder and the guard constants come from the app's own `NpuDecodePolicy` over
 *    `WhisperTokens.LARGE_V3` - the call `NpuWhisperBackend.transcribe` makes, argument for argument.
 *
 * Sequence: probe (the adapter's 5 s, timed) -> init (runtime, environment, stamps, census, both restores,
 * buffers) -> for each round and each mel: encode -> [detect] -> decodeSegment -> release -> (rearm: init
 * again, one encode + decode, release - the re-arm after a trim, which must NOT pay the 5 s again).
 *
 * Logged per utterance: encode ms, detect ms, decode ms, steps, ms per step, the ids (timestamps included),
 * whether they equal the t8 reference, the timestamp pairing, the six stats, PSS. The per-step times one by
 * one are native's `npu-debug: steptime` lines on WE-DIAG (diag=true), and the driver line is native's
 * `apu:` line there too; drive.py's filter keeps both.
 *
 * The ONE-set arm of "per-step time with one and with two self-KV sets" is `mode=e2eqc` on the same pair
 * (one set, a Kotlin copy per step, t8's 21.0 ms + 9.3 ms); this mode is the two-set, zero-copy arm. The
 * library deliberately has no single-set switch - its JNI surface is exactly the product's.
 */
class LiteRtAsrProbe(private val ctx: Context, private val args: ProbeArgs) {
    companion object {
        // NpuModelSpec.TURBO's five nativeInit scalars. The probe does not carry NpuModelSpec (it drags in the
        // pack census); the family's vocabulary comes from WhisperTokens.LARGE_V3, the app's own reading.
        const val MEL_BINS = 128
        const val DEC_LAYERS = 4
        const val HEADS = 20
        const val MEL_FRAMES = 3000
        const val DISPATCH = "libLiteRtDispatch_MediaTek.so"

        /** The v2.1.1 release zip member (fetch_mediatek_runtime.py). AGP strips the copy it packages. */
        const val DISPATCH_ZIP_SHA256 = "9e963c56a65b6146b0e94aed82dd0f73dbaee6805fc6ae090580565b57680706"

        /** The tablet's ids in the app's decode mode (t8 / t8b / t11, identical across all three). */
        val REFERENCE: Map<String, IntArray> = mapOf(
            "jfk_mel128.bin" to intArrayOf(
                50365, 400, 370, 11, 452, 7177, 6280, 11, 1029, 406, 437, 428, 1941, 393, 360, 337, 291, 11,
                1029, 437, 291, 393, 360, 337, 428, 1941, 13, 50915,
            ),
            "canary_mel128.bin" to intArrayOf(50365, 1485, 11, 732, 11, 1045, 11, 1451, 11, 1732, 13, 50493),
        )
    }

    private val family = WhisperTokens.LARGE_V3

    fun run(res: JSONObject) {
        val encPath = requireNotNull(args.model) { "--es model (the AOT-compiled encoder) is required" }
        val decPath = requireNotNull(args.dec) { "--es dec (the AOT-compiled decoder) is required" }
        val nld = ctx.applicationInfo.nativeLibraryDir
        val dispatchDir = args.dispatchDir ?: File(ctx.filesDir, "litert_dispatch").absolutePath
        stageDispatch(dispatchDir, res)
        for (lib in listOf("liblitertasr.so", "libLiteRt.so", "libc++_shared.so")) {
            val f = File(nld, lib)
            val sha = if (f.isFile) sha256(f) else "absent"
            ProbeLog.i("litertasr|lib|$lib|bytes=${f.length()}|sha256=$sha")
            res.put("sha256_$lib", sha)
        }
        res.put("perfmode", args.perfMode)
        res.put("socstamp", args.socStamp)
        res.put("wantmajor", args.wantMajor)

        Metrics.snapshot(ctx, "before_load").let { res.put("mem_before_load", it) }
        var t0 = System.nanoTime()
        LiteRtAsrNative.nativeSetDiag(args.diag)   // the first touch: System.loadLibrary("litertasr")
        res.put("load_ms", ms(t0))

        // ---- the driver check: the adapter's own 5 s, on this (background) thread
        t0 = System.nanoTime()
        val probe = LiteRtAsrNative.nativeProbe(dispatchDir, nld, args.wantMajor)
        val probeMs = ms(t0)
        ProbeLog.i("litertasr|probe_ms=${f1(probeMs)}|result=${probe.ifEmpty { "pass" }}")
        res.put("probe_ms", probeMs)
        res.put("probe", probe.ifEmpty { "pass" })
        check(probe.isEmpty()) { "nativeProbe refused: $probe" }
        Metrics.snapshot(ctx, "after_probe").let { res.put("mem_after_probe", it) }

        // ---- arm
        val initMs = arm(encPath, decPath, dispatchDir, nld)
        res.put("init_ms", initMs)
        res.put("epoch", LiteRtAsrNative.nativeEpoch())
        Metrics.snapshot(ctx, "after_init").let { res.put("mem_after_init", it) }

        // ---- the utterances
        val mels = (args.mels ?: "jfk_mel128.bin,canary_mel128.bin").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val melData = mels.associateWith { loadMel(it) }
        val utterances = JSONArray()
        val warmEncode = ArrayList<Double>()
        val warmStep = ArrayList<Double>()
        var index = 0
        var allMatch = true
        for (round in 0 until args.utts) for (name in mels) {
            val uo = runOne(name, melData.getValue(name), index, round)
            utterances.put(uo)
            if (uo.optBoolean("matches_reference", true).not()) allMatch = false
            if (index > 0) {
                warmEncode.add(uo.getDouble("encode_ms"))
                warmStep.add(uo.getDouble("step_ms_mean"))
            }
            index++
        }
        res.put("utterances", utterances)
        res.put("warm_encode", Metrics.stats(warmEncode))
        res.put("warm_step", Metrics.stats(warmStep))
        res.put("all_match_reference", allMatch)
        ProbeLog.i("litertasr|warm(excluding utt 0)|encode=${fmtStats(Metrics.stats(warmEncode))}|" +
            "step=${fmtStats(Metrics.stats(warmStep))}|all_match_reference=$allMatch")

        // ---- release, and the re-arm a trim would cause
        val epoch = LiteRtAsrNative.nativeEpoch()
        t0 = System.nanoTime()
        LiteRtAsrNative.nativeRelease(epoch)
        res.put("release_ms", ms(t0))
        check(LiteRtAsrNative.nativeEpoch() == 0L) { "nativeRelease($epoch) left a live session" }
        Metrics.snapshot(ctx, "after_release").let { res.put("mem_after_release", it) }
        if (args.rearm) {
            val rearm = JSONObject()
            rearm.put("init_ms", arm(encPath, decPath, dispatchDir, nld))
            Metrics.snapshot(ctx, "after_rearm").let { rearm.put("mem_after_rearm", it) }
            val first = mels.first()
            rearm.put("utterance", runOne(first, melData.getValue(first), index, -1))
            val e2 = LiteRtAsrNative.nativeEpoch()
            LiteRtAsrNative.nativeRelease(e2)
            check(LiteRtAsrNative.nativeEpoch() == 0L) { "nativeRelease($e2) left a live session after the re-arm" }
            res.put("rearm", rearm)
            ProbeLog.i("litertasr|rearm|init_ms=${f1(rearm.getDouble("init_ms"))}|" +
                "matches_reference=${rearm.getJSONObject("utterance").optBoolean("matches_reference", true)}")
        }
        Metrics.snapshot(ctx, "end").let { res.put("mem_end", it) }
    }

    /** nativeInit with the product's arguments; returns its wall time. */
    private fun arm(encPath: String, decPath: String, dispatchDir: String, nld: String): Double {
        val t0 = System.nanoTime()
        val init = LiteRtAsrNative.nativeInit(
            encPath, decPath, dispatchDir, nld,
            MEL_BINS, DEC_LAYERS, HEADS, family.vocab, family.maxPositions,
            args.socStamp, args.wantMajor, args.perfMode,
        )
        val initMs = ms(t0)
        ProbeLog.i("litertasr|init_ms=${f1(initMs)}|result=${init.ifEmpty { "OK" }}|epoch=${LiteRtAsrNative.nativeEpoch()}")
        check(init.isEmpty()) { "nativeInit refused: $init" }
        return initMs
    }

    /** One window: encode -> [detect] -> decodeSegment, exactly NpuWhisperBackend.transcribe's calls. */
    private fun runOne(name: String, mel: ByteBuffer, index: Int, round: Int): JSONObject {
        val uo = JSONObject()
        uo.put("index", index); uo.put("mel", name); uo.put("round", round)
        var t0 = System.nanoTime()
        val enc = LiteRtAsrNative.nativeEncode(mel)
        val encMs = ms(t0)
        check(enc.isEmpty()) { "nativeEncode refused: $enc" }
        uo.put("encode_ms", encMs)

        var detected = -1
        if (args.detect || args.lang == "auto") {
            t0 = System.nanoTime()
            detected = LiteRtAsrNative.nativeDetectLanguage()
            uo.put("detect_ms", ms(t0))
            uo.put("detected", detected)
            uo.put("detected_code", family.codeForToken(detected) ?: JSONObject.NULL)
        }
        val prompt = if (args.lang == "auto") {
            check(detected >= 0) { "detect failed: ${LiteRtAsrNative.nativeLastError()}" }
            NpuDecodePolicy.promptTokens(family, detected)
        } else {
            NpuDecodePolicy.promptTokens(family, args.lang)
        }
        val out = IntArray(NpuDecodePolicy.maxTokensFor(family, prompt.size))
        val stats = NpuDecodeStats.newArray()
        t0 = System.nanoTime()
        val written = LiteRtAsrNative.nativeDecodeSegment(
            prompt,
            NpuDecodePolicy.suppressList(family),
            NpuDecodePolicy.beginSuppressList(family),
            out.size,
            out,
            NpuDecodePolicy.TEMPERATURES,
            NpuDecodePolicy.ENTROPY_THOLD,
            NpuDecodePolicy.LOGPROB_THOLD,
            NpuDecodePolicy.NO_SPEECH_THOLD,
            family.noSpeech,
            NpuDecodePolicy.CYCLE_MAX_DISTINCT,
            stats,
        )
        val decMs = ms(t0)
        check(written >= 0) { "nativeDecodeSegment returned $written: ${LiteRtAsrNative.nativeLastError()}" }
        val ids = out.copyOf(written)
        val steps = stats[NpuDecodeStats.STEPS].toInt()
        val stepMs = if (steps > 0) decMs / steps else 0.0

        // The acceptance's second half: timestamps paired and monotonic.
        val stamps = ids.filter { it >= family.timestampBegin }
        val paired = stamps.size % 2 == 0 && stamps.isNotEmpty()
        val monotonic = stamps.zipWithNext().all { (a, b) -> b >= a }
        val ref = REFERENCE[name]
        val matches = ref?.contentEquals(ids)

        uo.put("prompt", JSONArray(prompt.toList()))
        uo.put("ids", JSONArray(ids.toList()))
        uo.put("tokens", written)
        uo.put("hit_eot", stats[NpuDecodeStats.TERMINATOR] == NpuDecodeStats.TERM_EOT)
        uo.put("decode_ms", decMs)
        uo.put("steps", steps)
        uo.put("step_ms_mean", stepMs)
        uo.put("cache_copy_ms_mean", 0.0)   // two self-KV sets swap by re-binding: nothing is copied
        uo.put("timestamps", JSONArray(stamps.map { (it - family.timestampBegin) * 0.02 }))
        uo.put("timestamps_paired", paired)
        uo.put("timestamps_monotonic", monotonic)
        if (matches != null) uo.put("matches_reference", matches)
        val st = JSONObject()
        st.put("nsp", stats[NpuDecodeStats.NO_SPEECH_PROB].toDouble())
        st.put("avg_logprob", stats[NpuDecodeStats.AVG_LOGPROB].toDouble())
        st.put("entropy", stats[NpuDecodeStats.ENTROPY].toDouble())
        st.put("rung", stats[NpuDecodeStats.RUNG].toInt())
        st.put("terminator", NpuDecodeStats.terminatorName(stats[NpuDecodeStats.TERMINATOR]))
        uo.put("stats", st)
        uo.put("mem", Metrics.snapshot(ctx, "utt_$index"))
        ProbeLog.i("litertasr|utt=$index|mel=$name|encode_ms=${f1(encMs)}|detect=${uo.optInt("detected", -1)}|" +
            "decode_ms=${f1(decMs)}|tokens=$written|steps=$steps|step_ms=${"%.2f".format(stepMs)}|" +
            "nsp=${"%.3f".format(st.getDouble("nsp"))}|lp=${"%.3f".format(st.getDouble("avg_logprob"))}|" +
            "rung=${st.getInt("rung")}|term=${st.getString("terminator")}|stamps_paired=$paired|" +
            "stamps_monotonic=$monotonic|matches_reference=${matches ?: "n/a"}")
        ProbeLog.i("litertasr|utt=$index|ids=" + ids.joinToString(","))
        return uo
    }

    /**
     * The product's dispatch shape: `dir` holds exactly libLiteRtDispatch_MediaTek.so, because LiteRT scans it
     * and the adapter loader's fourth candidate is `<dir>/libneuron_adapter.so`. When absent it is copied from
     * this APK's own packaged copy (legacy packaging leaves a real file in nativeLibraryDir) through a temp
     * file OUTSIDE the scanned directory, then renamed in.
     */
    private fun stageDispatch(dir: String, res: JSONObject) {
        val d = File(dir).apply { mkdirs() }
        val target = File(d, DISPATCH)
        var staged = false
        if (!target.isFile) {
            val src = File(ctx.applicationInfo.nativeLibraryDir, DISPATCH)
            require(src.isFile) { "no $DISPATCH in $dir, and none in nativeLibraryDir to stage from" }
            val tmp = File(ctx.filesDir, "$DISPATCH.staging")
            src.copyTo(tmp, overwrite = true)
            check(tmp.renameTo(target)) { "could not move $tmp into $d" }
            staged = true
        }
        val others = d.list()?.filter { it != DISPATCH }.orEmpty()
        require(others.isEmpty()) {
            "$dir must hold exactly $DISPATCH (LiteRT scans it; the adapter's fourth candidate is " +
                "<dir>/libneuron_adapter.so) - it also holds $others"
        }
        val sha = sha256(target)
        val note = if (sha == DISPATCH_ZIP_SHA256) "the v2.1.1 release zip member" else "not the zip member (AGP-stripped copy?)"
        ProbeLog.i("litertasr|dispatch|dir=$dir|staged_now=$staged|bytes=${target.length()}|sha256=$sha|$note")
        res.put("dispatch_dir", dir)
        res.put("dispatch_sha256", sha)
        res.put("dispatch_staged_now", staged)
    }

    /** The float mel exactly as pcmToMel leaves it: melBins x 3000 float32, direct, native order. */
    private fun loadMel(name: String): ByteBuffer {
        val f = File(ctx.filesDir, name)
        require(f.isFile) { "mel not found: ${f.absolutePath}" }
        val bytes = f.readBytes()
        require(bytes.size == MEL_BINS * MEL_FRAMES * 4) { "$name is ${bytes.size} B, expected ${MEL_BINS * MEL_FRAMES * 4}" }
        // The files are little-endian float32; so is arm64, so native order is a byte copy.
        check(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) { "the mel files are little-endian" }
        val b = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        b.put(bytes).rewind()
        ProbeLog.i("litertasr|mel|$name|bytes=${bytes.size}|sha256=${sha256(bytes)}")
        return b
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6
    private fun f1(v: Double) = "%.1f".format(v)
    private fun fmtStats(o: JSONObject): String =
        if (o.has("n")) "n=${o.getInt("n")} mean=${f1(o.getDouble("mean_ms"))} median=${f1(o.getDouble("median_ms"))} " +
            "min=${f1(o.getDouble("min_ms"))} max=${f1(o.getDouble("max_ms"))} sd=${f1(o.getDouble("sd_ms"))}" else "n=0"

    private fun sha256(f: File): String = f.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
