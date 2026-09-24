package com.whispereverywhere.probe

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `mode=e2eqc`: real speech through the PRODUCT-SHAPED turbo pair (Qualcomm's HfWhisper IO exported through
 * litert-torch) with a true KV cache — the correctness gate. The encoder (`model`, signature `encode`,
 * `input_features` → 8 cross-KV outputs in k0,v0,k1,v1,… order) runs on `accel`; the decoder (`dec`,
 * signature `decode`) on `decaccel` (`same` | `npu` | `gpu` | `cpu`). Greedy, one token per step:
 *
 *   step t writes input_ids, position_ids=t and an additive mask whose LAST t+1 columns are open, runs the
 *   decoder, argmaxes the logits, and copies the 8 returned self-caches back into the inputs (the window shifts
 *   left by one; the newest key sits in the last column). The prompt tokens are fed the same way first.
 *
 * Emits the token ids per utterance (detokenised on the PC), encode ms, per-step ms and the cache-copy cost.
 */
class E2eQcProbe(private val ctx: Context, private val args: ProbeArgs) {
    companion object {
        const val SIG_ENCODE = "encode"
        const val SIG_DECODE = "decode"
        const val N_FRAMES = 3000
        const val WINDOW = 200
        const val MASK_NEG = -1e4f       // fp16-safe (the APU relaxes everything to fp16)
    }

    private fun accelOf(s: String, fallback: Accelerator): Accelerator = when (s.lowercase()) {
        "npu" -> Accelerator.NPU
        "gpu" -> Accelerator.GPU
        "cpu" -> Accelerator.CPU
        "same" -> fallback
        else -> throw IllegalArgumentException("accel must be cpu|gpu|npu|same")
    }

    fun run(res: JSONObject) {
        val encPath = requireNotNull(args.model) { "--es model (encoder) is required" }
        val decPath = requireNotNull(args.dec) { "--es dec (decoder) is required" }
        val nld = args.dispatchDir ?: ctx.applicationInfo.nativeLibraryDir
        val encAccel = accelOf(args.accel, Accelerator.CPU)
        val decAccel = accelOf(args.decAccel, encAccel)
        val envOpts = HashMap<Environment.Option, String>()
        if (encAccel == Accelerator.NPU || decAccel == Accelerator.NPU) {
            envOpts[Environment.Option.DispatchLibraryDir] = nld
            envOpts[Environment.Option.CompilerPluginLibraryDir] = nld
        }
        val env = Environment.create(envOpts)
        fun opts(a: Accelerator): CompiledModel.Options {
            val o = if (args.noFallback) CompiledModel.Options(a) else CompiledModel.Options(a, Accelerator.CPU)
            o.cpuOptions = CompiledModel.CpuOptions(numThreads = args.threads)
            return o
        }
        Metrics.snapshot(ctx, "before_create").let { res.put("mem_before_create", it) }
        var t0 = System.nanoTime()
        val enc = CompiledModel.create(encPath, opts(encAccel), env)
        val encCreateMs = (System.nanoTime() - t0) / 1e6
        ProbeLog.i("e2eqc|encoder_create_ms=${"%.1f".format(encCreateMs)}|accel=$encAccel")
        t0 = System.nanoTime()
        val dec = CompiledModel.create(decPath, opts(decAccel), env)
        val decCreateMs = (System.nanoTime() - t0) / 1e6
        ProbeLog.i("e2eqc|decoder_create_ms=${"%.1f".format(decCreateMs)}|accel=$decAccel")
        res.put("encoder_create_ms", encCreateMs)
        res.put("decoder_create_ms", decCreateMs)
        res.put("decaccel", decAccel.toString())
        Metrics.snapshot(ctx, "after_create").let { res.put("mem_after_create", it) }

        // ---- shapes
        val encInType = enc.getInputTensorType("input_features", SIG_ENCODE)
        val encInDims = encInType.layout?.dimensions ?: emptyList()
        require(encInDims.size == 3 && encInDims[2] == N_FRAMES) { "encode input is not [1,n_mels,3000]: $encInDims" }
        val nMels = encInDims[1]
        val logitsDims = dec.getOutputTensorType("output_0", SIG_DECODE).layout?.dimensions ?: emptyList()
        val vocab = logitsDims.fold(1L) { a, b -> a * b }.toInt()
        val layers = 4
        ProbeLog.i("e2eqc|encode in=input_features $encInDims|decode logits=$logitsDims vocab=$vocab|layers=$layers|window=$WINDOW")
        res.put("n_mels", nMels); res.put("vocab", vocab)

        // ---- buffers (created once)
        val encIn = enc.createInputBuffer("input_features", SIG_ENCODE)
        val encOuts = (0 until 2 * layers).map { enc.createOutputBuffer("output_$it", SIG_ENCODE) }
        val encInputs = mapOf("input_features" to encIn)
        val encOutputs = encOuts.mapIndexed { i, b -> "output_$i" to b }.toMap()

        val dIn = LinkedHashMap<String, TensorBuffer>()
        dIn["input_ids"] = dec.createInputBuffer("input_ids", SIG_DECODE)
        dIn["attention_mask"] = dec.createInputBuffer("attention_mask", SIG_DECODE)
        for (i in 0 until layers) {
            dIn["k_cache_self_${i}_in"] = dec.createInputBuffer("k_cache_self_${i}_in", SIG_DECODE)
            dIn["v_cache_self_${i}_in"] = dec.createInputBuffer("v_cache_self_${i}_in", SIG_DECODE)
        }
        for (i in 0 until layers) {
            dIn["k_cache_cross_$i"] = dec.createInputBuffer("k_cache_cross_$i", SIG_DECODE)
            dIn["v_cache_cross_$i"] = dec.createInputBuffer("v_cache_cross_$i", SIG_DECODE)
        }
        dIn["position_ids"] = dec.createInputBuffer("position_ids", SIG_DECODE)
        val dOut = LinkedHashMap<String, TensorBuffer>()
        for (i in 0 until 1 + 2 * layers) dOut["output_$i"] = dec.createOutputBuffer("output_$i", SIG_DECODE)
        val selfK = (0 until layers).map { dec.getInputTensorType("k_cache_self_${it}_in", SIG_DECODE).layout?.dimensions?.fold(1L) { a, b -> a * b }?.toInt() ?: 0 }
        val selfV = (0 until layers).map { dec.getInputTensorType("v_cache_self_${it}_in", SIG_DECODE).layout?.dimensions?.fold(1L) { a, b -> a * b }?.toInt() ?: 0 }
        ProbeLog.i("e2eqc|self cache floats per layer k=${selfK[0]} v=${selfV[0]}")

        val mels = (args.mels ?: "jfk_mel128.bin").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val prompt = args.tokens.split(",").map { it.trim().toInt() }
        val eot = args.eot
        val melData = mels.associateWith { name ->
            val f = File(ctx.filesDir, name)
            require(f.exists()) { "mel not found: ${f.absolutePath}" }
            val bytes = f.readBytes()
            require(bytes.size == nMels * N_FRAMES * 4) { "$name is ${bytes.size} B, expected ${nMels * N_FRAMES * 4}" }
            val fa = FloatArray(nMels * N_FRAMES)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(fa)
            fa
        }
        ProbeLog.i("e2eqc|mels=${mels.joinToString(",")}|prompt=${prompt.joinToString(",")}|eot=$eot|maxtokens=${args.maxTokens}")

        val utterances = JSONArray()
        var index = 0
        val allEnc = ArrayList<Double>(); val allStep = ArrayList<Double>(); val allCopy = ArrayList<Double>()
        val mask = FloatArray(WINDOW)
        for (u in 0 until args.utts) for (name in mels) {
            val uo = JSONObject(); uo.put("index", index); uo.put("mel", name); uo.put("round", u)
            // encode
            encIn.writeFloat(melData[name]!!)
            var e0 = System.nanoTime()
            enc.run(encInputs, encOutputs, SIG_ENCODE)
            val encRunOnly = (System.nanoTime() - e0) / 1e6
            val cross = encOuts.map { it.readFloat() }
            val encMs = (System.nanoTime() - e0) / 1e6
            e0 = System.nanoTime()
            for (i in 0 until layers) {
                dIn["k_cache_cross_$i"]!!.writeFloat(cross[2 * i])
                dIn["v_cache_cross_$i"]!!.writeFloat(cross[2 * i + 1])
            }
            val handoffMs = (System.nanoTime() - e0) / 1e6
            uo.put("encode_ms", encMs); uo.put("encode_run_only_ms", encRunOnly); uo.put("handoff_ms", handoffMs)
            uo.put("cross_k0_fingerprint", fingerprint(cross[0]))
            // reset the self cache
            for (i in 0 until layers) {
                dIn["k_cache_self_${i}_in"]!!.writeFloat(FloatArray(selfK[i]))
                dIn["v_cache_self_${i}_in"]!!.writeFloat(FloatArray(selfV[i]))
            }
            // decode, greedy, KV-cached
            val out = ArrayList<Int>()
            val stepMs = ArrayList<Double>(); val copyMs = ArrayList<Double>()
            var t = 0
            var hitEot = false
            var next = prompt[0]
            val d0 = System.nanoTime()
            while (t < WINDOW - 1 && out.size < args.maxTokens) {
                val tok = if (t < prompt.size) prompt[t] else next
                dIn["input_ids"]!!.writeInt(intArrayOf(tok))
                dIn["position_ids"]!!.writeInt(intArrayOf(t))
                java.util.Arrays.fill(mask, MASK_NEG)
                for (c in WINDOW - 1 - t until WINDOW) mask[c] = 0f
                dIn["attention_mask"]!!.writeFloat(mask)
                val s0 = System.nanoTime()
                dec.run(dIn, dOut, SIG_DECODE)
                val logits = dOut["output_0"]!!.readFloat()
                val s1 = System.nanoTime()
                for (i in 0 until layers) {
                    dIn["k_cache_self_${i}_in"]!!.writeFloat(dOut["output_${1 + 2 * i}"]!!.readFloat())
                    dIn["v_cache_self_${i}_in"]!!.writeFloat(dOut["output_${2 + 2 * i}"]!!.readFloat())
                }
                val s2 = System.nanoTime()
                stepMs.add((s1 - s0) / 1e6); copyMs.add((s2 - s1) / 1e6)
                next = argmax(logits, vocab)
                if (index == 0 && t < prompt.size + 6) ProbeLog.i("e2eqc|diag|utt=0|t=$t|in=$tok|" + rowDiag(logits, vocab, next))
                t += 1
                if (t < prompt.size) continue         // still feeding the prompt
                if (next == eot) { hitEot = true; break }
                out.add(next)
            }
            val decodeMs = (System.nanoTime() - d0) / 1e6
            val st = Metrics.stats(stepMs); val ct = Metrics.stats(copyMs)
            uo.put("ids", JSONArray(out)); uo.put("tokens", out.size); uo.put("hit_eot", hitEot); uo.put("steps", stepMs.size)
            uo.put("decode_ms", decodeMs)
            uo.put("step_ms_mean", if (stepMs.isEmpty()) JSONObject.NULL else st.getDouble("mean_ms"))
            uo.put("cache_copy_ms_mean", if (copyMs.isEmpty()) JSONObject.NULL else ct.getDouble("mean_ms"))
            uo.put("step_ms", JSONArray(stepMs))
            uo.put("total_ms", encMs + handoffMs + decodeMs)
            uo.put("mem", Metrics.snapshot(ctx, "utt_$index"))
            ProbeLog.i("e2eqc|utt=$index|mel=$name|encode_ms=${"%.1f".format(encMs)}|handoff_ms=${"%.1f".format(handoffMs)}|tokens=${out.size}|steps=${stepMs.size}|eot=$hitEot|decode_ms=${"%.1f".format(decodeMs)}|step_ms=${fmt(st, "mean_ms")}|cache_copy_ms=${fmt(ct, "mean_ms")}|total_ms=${"%.1f".format(encMs + handoffMs + decodeMs)}")
            ProbeLog.i("e2eqc|utt=$index|ids=" + out.joinToString(","))
            utterances.put(uo)
            if (index > 0) { allEnc.add(encMs); allStep.addAll(stepMs); allCopy.addAll(copyMs) }
            index++
        }
        res.put("utterances", utterances)
        res.put("warm_encode", Metrics.stats(allEnc)); res.put("warm_step", Metrics.stats(allStep)); res.put("warm_cache_copy", Metrics.stats(allCopy))
        ProbeLog.i("e2eqc|warm(excluding utt 0)|encode=${fmtStats(Metrics.stats(allEnc))}|step=${fmtStats(Metrics.stats(allStep))}|cache_copy=${fmtStats(Metrics.stats(allCopy))}")
        Metrics.snapshot(ctx, "end").let { res.put("mem_end", it) }
        (listOf(encIn) + encOuts + dIn.values + dOut.values).forEach { it.close() }
        dec.close(); enc.close(); env.close()
        Metrics.snapshot(ctx, "after_close").let { res.put("mem_after_close", it) }
    }

    private fun argmax(logits: FloatArray, n: Int): Int {
        var best = 0; var bv = Float.NEGATIVE_INFINITY
        for (i in 0 until n) { val v = logits[i]; if (v > bv) { bv = v; best = i } }
        return best
    }

    private fun rowDiag(logits: FloatArray, n: Int, best: Int): String {
        var nan = 0; var second = -1; var sv = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val v = logits[i]
            if (v.isNaN() || v.isInfinite()) { nan++; continue }
            if (i != best && v > sv) { sv = v; second = i }
        }
        return "nan_inf=$nan|argmax=$best(${"%.3f".format(logits[best])})|second=$second(${"%.3f".format(sv)})|eot_logit=${"%.3f".format(logits[args.eot])}"
    }

    private fun fmt(o: JSONObject, k: String): String = if (o.has(k)) "%.1f".format(o.getDouble(k)) else "-"
    private fun fmtStats(o: JSONObject): String =
        if (o.has("n")) "n=${o.getInt("n")} mean=${fmt(o, "mean_ms")} median=${fmt(o, "median_ms")} min=${fmt(o, "min_ms")} max=${fmt(o, "max_ms")} sd=${fmt(o, "sd_ms")}" else "n=0"

    private fun fingerprint(out: FloatArray): JSONObject {
        val o = JSONObject()
        var nan = 0; var sum = 0.0; var sumAbs = 0.0
        var mn = Float.POSITIVE_INFINITY; var mx = Float.NEGATIVE_INFINITY
        for (v in out) {
            if (v.isNaN() || v.isInfinite()) { nan++; continue }
            sum += v; sumAbs += Math.abs(v)
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        o.put("count", out.size); o.put("nan_or_inf", nan); o.put("mean", sum / out.size); o.put("mean_abs", sumAbs / out.size)
        o.put("min", mn.toDouble()); o.put("max", mx.toDouble()); o.put("head", JSONArray(out.take(8).map { it.toDouble() }))
        return o
    }
}
