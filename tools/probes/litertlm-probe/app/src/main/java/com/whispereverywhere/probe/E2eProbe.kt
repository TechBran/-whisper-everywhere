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
 * E6: whisper END-TO-END on one backend -- `encode` once, then the litert-community graphs' `decode`
 * signature greedily, token by token, until EOT or `maxtokens`.
 *
 * The decoder of these graphs (`whisper_*_30s_*.tflite`) has NO KV cache and a FIXED window: it takes
 * `args_0` = encoder states `[1,1500,d]`, `args_1` = the whole token window `i32[1,128]`, `args_2` = an
 * ADDITIVE causal mask `f32[1,1,128,128]` (0 where position j may attend to i <= j, -1e9 above the
 * diagonal -- proven on the PC 2026-09-10: a 0/1 mask decodes " (D." for jfk, an all-zero mask decodes
 * nothing, the additive one decodes the sentence), and returns logits for ALL 128 positions
 * `f32[1,128,vocab]`. So every step re-runs the full 128-position decoder and the loop reads the
 * argmax at the current position: per-step cost is a constant (the window), not a growing sequence.
 *
 * Input: a log-mel the PC computed with `whisper_e2e.py mel` (row-major f32 `[n_mels][3000]`, the
 * graph's `[1,n_mels,3000]`), pushed into files/. The prompt is the four ids the caller passes
 * (`<|startoftranscript|>,<|xx|>,<|transcribe|>,<|notimestamps|>` for the family: 50258,50259,50360,50364
 * for large-v3's 51866-vocab, 50258,50259,50359,50363 for base's 51865). Token ids are logged and
 * written to the JSON; the PC detokenises (`whisper_e2e.py detok` / `summarize`) -- no transcript
 * beyond the two test fixtures ever exists here.
 *
 * Timing: `encode_ms` and every decode step are run+read (the GPU accelerator's `run()` returns before
 * the OpenCL queue drains). The decoder's output buffer is the whole `[128 x vocab]` logits block
 * (26.6 MB for turbo), and the Kotlin `TensorBuffer` API can only read it whole, so `readback_ms` is
 * reported beside `run_only_ms` -- a production loop with the C API would read one row.
 */
class E2eProbe(private val ctx: Context, private val args: ProbeArgs) {

    private class DecIo(val enc: String, val ids: String, val mask: String)

    fun run(res: JSONObject) {
        val modelPath = requireNotNull(args.model) { "--es model is required" }
        val mels = requireNotNull(args.mels) { "--es mels is required (comma list of files/<name>.bin)" }
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val prompt = args.tokens.split(",").map { it.trim().toInt() }.toIntArray()
        require(prompt.isNotEmpty() && prompt.size < DECODE_WINDOW) { "tokens must be 1..${DECODE_WINDOW - 1} ids" }
        val eot = args.eot
        val accel = when (args.accel.lowercase()) {
            "npu" -> Accelerator.NPU
            "gpu" -> Accelerator.GPU
            "cpu" -> Accelerator.CPU
            else -> throw IllegalArgumentException("accel must be cpu|gpu|npu")
        }
        val nld = args.dispatchDir ?: ctx.applicationInfo.nativeLibraryDir
        val envOpts = HashMap<Environment.Option, String>()
        if (accel == Accelerator.NPU) {
            envOpts[Environment.Option.DispatchLibraryDir] = nld
            envOpts[Environment.Option.CompilerPluginLibraryDir] = nld
        }
        val env = Environment.create(envOpts)
        ProbeLog.i("e2e|availableAccelerators=${env.getAvailableAccelerators().joinToString(",")}|requested=$accel|noFallback=${args.noFallback}")
        res.put("no_fallback", args.noFallback)
        res.put("tokenizer", args.tokenizer)
        res.put("prompt", JSONArray(prompt.toList()))
        res.put("eot", eot)
        res.put("max_tokens", args.maxTokens)
        res.put("mels", JSONArray(mels))
        res.put("utts", args.utts)

        val opts = if (args.noFallback) CompiledModel.Options(accel) else CompiledModel.Options(accel, Accelerator.CPU)
        opts.cpuOptions = CompiledModel.CpuOptions(numThreads = args.threads)
        if (accel == Accelerator.GPU) {
            val prec = when (args.gpuPrecision.lowercase()) {
                "fp16" -> CompiledModel.GpuOptions.Precision.FP16
                "fp32" -> CompiledModel.GpuOptions.Precision.FP32
                else -> CompiledModel.GpuOptions.Precision.DEFAULT
            }
            opts.gpuOptions = CompiledModel.GpuOptions(precision = prec)
        }

        Metrics.snapshot(ctx, "before_create").let { res.put("mem_before_create", it) }
        val t0 = System.nanoTime()
        val model = CompiledModel.create(modelPath, opts, env)
        val createMs = (System.nanoTime() - t0) / 1e6
        ProbeLog.i("e2e|create_ms=${"%.1f".format(createMs)}|accel=$accel|threads=${args.threads}")
        res.put("create_ms", createMs)
        Metrics.snapshot(ctx, "after_create").let { res.put("mem_after_create", it) }

        // decaccel=cpu: the decoder runs on a SECOND CompiledModel of the same file, CPU/XNNPACK only. That
        // is the shape a product would take if the accelerator's decoder is wrong (it is, on Mali, 2026-09-10);
        // its price is the file mapped twice and XNNPACK's repacked weights on top of the OpenCL copies.
        val decModel: CompiledModel = if (args.decAccel.lowercase() == "cpu") {
            val dOpts = CompiledModel.Options(Accelerator.CPU)
            dOpts.cpuOptions = CompiledModel.CpuOptions(numThreads = args.threads)
            val d0 = System.nanoTime()
            val m = CompiledModel.create(modelPath, dOpts, env)
            val dMs = (System.nanoTime() - d0) / 1e6
            ProbeLog.i("e2e|decode_model_create_ms=${"%.1f".format(dMs)}|decaccel=CPU|threads=${args.threads}")
            res.put("decode_model_create_ms", dMs)
            Metrics.snapshot(ctx, "after_decode_create").let { res.put("mem_after_decode_create", it) }
            m
        } else model
        res.put("decaccel", args.decAccel)

        // ---- shapes, by name, verified by shape (the model card warns names can differ across LiteRT versions)
        val encIn = model.getInputTensorType("args_0", SIG_ENCODE)
        val encOut = model.getOutputTensorType("output_0", SIG_ENCODE)
        val encInDims = encIn.layout?.dimensions ?: emptyList()
        val encOutDims = encOut.layout?.dimensions ?: emptyList()
        ProbeLog.i("e2e|encode|in=args_0 ${encIn.elementType} $encInDims|out=output_0 ${encOut.elementType} $encOutDims")
        require(encInDims.size == 3 && encInDims[2] == N_FRAMES) { "encode input is not [1,n_mels,3000]: $encInDims" }
        val nMels = encInDims[1]
        val encStates = encOutDims.fold(1L) { a, b -> a * b }.toInt()

        val decNames = listOf("args_0", "args_1", "args_2")
        val decTypes = decNames.associateWith { decModel.getInputTensorType(it, SIG_DECODE) }
        for ((n, t) in decTypes) ProbeLog.i("e2e|decode|in=$n ${t.elementType} ${t.layout?.dimensions}")
        val decOut = decModel.getOutputTensorType("output_0", SIG_DECODE)
        val decOutDims = decOut.layout?.dimensions ?: emptyList()
        ProbeLog.i("e2e|decode|out=output_0 ${decOut.elementType} $decOutDims")
        fun dims(n: String) = decTypes[n]?.layout?.dimensions ?: emptyList()
        val io = DecIo(
            enc = decNames.first { dims(it) == encOutDims },
            ids = decNames.first { dims(it).size == 2 && dims(it)[1] == DECODE_WINDOW },
            mask = decNames.first { dims(it).size == 4 && dims(it)[3] == DECODE_WINDOW },
        )
        require(decOutDims.size == 3 && decOutDims[1] == DECODE_WINDOW) { "decode output is not [1,128,vocab]: $decOutDims" }
        val vocab = decOutDims[2]
        ProbeLog.i("e2e|decode|bound enc=${io.enc} ids=${io.ids} mask=${io.mask}|window=$DECODE_WINDOW|vocab=$vocab|n_mels=$nMels|enc_states=$encStates")
        res.put("n_mels", nMels)
        res.put("vocab", vocab)
        res.put("decode_window", DECODE_WINDOW)
        res.put("decode_binding", JSONObject().put("enc", io.enc).put("ids", io.ids).put("mask", io.mask))
        res.put("enc_out_dims", JSONArray(encOutDims))
        res.put("dec_out_dims", JSONArray(decOutDims))

        // ---- buffers, created once and reused for every utterance
        val encInBuf = model.createInputBuffer("args_0", SIG_ENCODE)
        val encOutBuf = model.createOutputBuffer("output_0", SIG_ENCODE)
        val decEncBuf = decModel.createInputBuffer(io.enc, SIG_DECODE)
        val decIdsBuf = decModel.createInputBuffer(io.ids, SIG_DECODE)
        val decMaskBuf = decModel.createInputBuffer(io.mask, SIG_DECODE)
        val decOutBuf = decModel.createOutputBuffer("output_0", SIG_DECODE)
        val encInputs = mapOf("args_0" to encInBuf)
        val encOutputs = mapOf("output_0" to encOutBuf)
        val decInputs = mapOf(io.enc to decEncBuf, io.ids to decIdsBuf, io.mask to decMaskBuf)
        val decOutputs = mapOf("output_0" to decOutBuf)

        // Additive causal mask, written once: 0 on and below the diagonal, `maskneg` (default -1e9) above.
        val mask = FloatArray(DECODE_WINDOW * DECODE_WINDOW)
        for (i in 0 until DECODE_WINDOW) for (j in i + 1 until DECODE_WINDOW) mask[i * DECODE_WINDOW + j] = args.maskNeg
        decMaskBuf.writeFloat(mask)
        res.put("mask_neg", args.maskNeg.toDouble())
        res.put("fresh_bufs", args.freshBufs)
        res.put("rewrite_all", args.rewriteAll)
        val pad = args.pad ?: eot
        res.put("pad", pad)
        ProbeLog.i("e2e|mask_neg=${args.maskNeg}|freshbufs=${args.freshBufs}|rewriteall=${args.rewriteAll}|pad=$pad|decaccel=${args.decAccel}")

        val melData = mels.associateWith { name ->
            val f = File(ctx.filesDir, name)
            require(f.exists()) { "mel not found: ${f.absolutePath}" }
            val bytes = f.readBytes()
            require(bytes.size == nMels * N_FRAMES * 4) { "$name is ${bytes.size} B, expected ${nMels * N_FRAMES * 4} (n_mels=$nMels)" }
            val fa = FloatArray(nMels * N_FRAMES)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(fa)
            fa
        }
        ProbeLog.i("e2e|mels loaded: " + mels.joinToString(",") { "$it(${melData[it]!!.size})" })

        val utterances = JSONArray()
        var index = 0
        val allEncodeMs = ArrayList<Double>()
        val allStepMs = ArrayList<Double>()
        val allRunOnlyMs = ArrayList<Double>()
        for (u in 0 until args.utts) {
            for (name in mels) {
                val uo = JSONObject()
                uo.put("index", index)
                uo.put("mel", name)
                uo.put("round", u)

                // encode: write mel, run, read (run+read = the honest number on the async GPU backend)
                encInBuf.writeFloat(melData[name]!!)
                val e0 = System.nanoTime()
                model.run(encInputs, encOutputs, SIG_ENCODE)
                val encRunOnly = (System.nanoTime() - e0) / 1e6
                val states = encOutBuf.readFloat()
                val encMs = (System.nanoTime() - e0) / 1e6
                val h0 = System.nanoTime()
                decEncBuf.writeFloat(states)
                val handoffMs = (System.nanoTime() - h0) / 1e6
                uo.put("encode_run_only_ms", encRunOnly)
                uo.put("encode_ms", encMs)
                uo.put("handoff_ms", handoffMs)
                uo.put("encode_fingerprint", fingerprint(states))
                if (args.dumpStates && index == 0) {
                    val f = File(File(ctx.filesDir, "results").apply { mkdirs() }, args.tag + ".states.bin")
                    val bb = ByteBuffer.allocate(states.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    bb.asFloatBuffer().put(states)
                    f.writeBytes(bb.array())
                    ProbeLog.i("e2e|dumped encoder states of utt 0 to ${f.absolutePath} (${f.length()} B)")
                }

                // decode: greedy, full-window re-run per step
                val ids = IntArray(DECODE_WINDOW) { pad }
                System.arraycopy(prompt, 0, ids, 0, prompt.size)
                val out = ArrayList<Int>()
                val stepMs = ArrayList<Double>()
                val runOnlyMs = ArrayList<Double>()
                val readMs = ArrayList<Double>()
                var pos = prompt.size - 1
                var hitEot = false
                val d0 = System.nanoTime()
                while (pos < DECODE_WINDOW - 1 && out.size < args.maxTokens) {
                    var inputs = decInputs
                    var outputs = decOutputs
                    var fresh: List<TensorBuffer>? = null
                    if (args.freshBufs) {
                        val fe = decModel.createInputBuffer(io.enc, SIG_DECODE)
                        val fi = decModel.createInputBuffer(io.ids, SIG_DECODE)
                        val fm = decModel.createInputBuffer(io.mask, SIG_DECODE)
                        val fo = decModel.createOutputBuffer("output_0", SIG_DECODE)
                        fe.writeFloat(states); fm.writeFloat(mask)
                        inputs = mapOf(io.enc to fe, io.ids to fi, io.mask to fm)
                        outputs = mapOf("output_0" to fo)
                        fresh = listOf(fe, fi, fm, fo)
                    } else if (args.rewriteAll) {
                        decEncBuf.writeFloat(states); decMaskBuf.writeFloat(mask)
                    }
                    inputs[io.ids]!!.writeInt(ids)
                    val s0 = System.nanoTime()
                    decModel.run(inputs, outputs, SIG_DECODE)
                    val s1 = System.nanoTime()
                    val logits = outputs["output_0"]!!.readFloat()
                    val s2 = System.nanoTime()
                    fresh?.forEach { it.close() }
                    runOnlyMs.add((s1 - s0) / 1e6)
                    readMs.add((s2 - s1) / 1e6)
                    stepMs.add((s2 - s0) / 1e6)
                    val next = argmax(logits, pos * vocab, vocab)
                    if (index == 0 && stepMs.size <= 8) ProbeLog.i("e2e|diag|utt=0|step=${stepMs.size}|pos=$pos|" + rowDiag(logits, pos * vocab, vocab, next))
                    if (next == eot) { hitEot = true; break }
                    out.add(next)
                    pos += 1
                    ids[pos] = next
                }
                val decodeMs = (System.nanoTime() - d0) / 1e6
                val stepSt = Metrics.stats(stepMs)
                val runSt = Metrics.stats(runOnlyMs)
                val readSt = Metrics.stats(readMs)
                uo.put("ids", JSONArray(out))
                uo.put("tokens", out.size)
                uo.put("hit_eot", hitEot)
                uo.put("steps", stepMs.size)
                uo.put("decode_ms", decodeMs)
                uo.put("decode_ms_per_token", if (stepMs.isEmpty()) JSONObject.NULL else stepSt.getDouble("mean_ms"))
                uo.put("decode_run_only_ms_per_token", if (runOnlyMs.isEmpty()) JSONObject.NULL else runSt.getDouble("mean_ms"))
                uo.put("readback_ms_per_token", if (readMs.isEmpty()) JSONObject.NULL else readSt.getDouble("mean_ms"))
                uo.put("step_ms", JSONArray(stepMs))
                uo.put("step_run_only_ms", JSONArray(runOnlyMs))
                uo.put("total_ms", encMs + handoffMs + decodeMs)
                val mem = Metrics.snapshot(ctx, "utt_$index")
                uo.put("mem", mem)
                ProbeLog.i("e2e|utt=$index|mel=$name|encode_ms=${"%.1f".format(encMs)}|encode_run_only_ms=${"%.1f".format(encRunOnly)}|handoff_ms=${"%.1f".format(handoffMs)}|tokens=${out.size}|steps=${stepMs.size}|eot=$hitEot|decode_ms=${"%.1f".format(decodeMs)}|ms_per_token=${fmt(stepSt, "mean_ms")}|run_only_ms_per_token=${fmt(runSt, "mean_ms")}|readback_ms_per_token=${fmt(readSt, "mean_ms")}|total_ms=${"%.1f".format(encMs + handoffMs + decodeMs)}")
                ProbeLog.i("e2e|utt=$index|ids=" + out.joinToString(","))
                ProbeLog.i("e2e|utt=$index|step_ms=" + stepMs.joinToString(",") { "%.1f".format(it) })
                utterances.put(uo)
                if (index > 0) { allEncodeMs.add(encMs); allStepMs.addAll(stepMs); allRunOnlyMs.addAll(runOnlyMs) }
                if (index == 0 && args.decBench > 0) {
                    // N enqueued runs of the same step, one read at the end: on the asynchronous GPU backend the
                    // runs queue up and (total - one readback) / N is the per-step compute; on the CPU run() is
                    // synchronous and the same arithmetic holds.
                    decIdsBuf.writeInt(ids)
                    val r0 = System.nanoTime()
                    decModel.run(decInputs, decOutputs, SIG_DECODE)
                    decOutBuf.readFloat()
                    val single = (System.nanoTime() - r0) / 1e6
                    val b0 = System.nanoTime()
                    for (i in 0 until args.decBench) decModel.run(decInputs, decOutputs, SIG_DECODE)
                    val b1 = System.nanoTime()
                    decOutBuf.readFloat()
                    val b2 = System.nanoTime()
                    val runsMs = (b1 - b0) / 1e6
                    val readMsB = (b2 - b1) / 1e6
                    val totalMs = (b2 - b0) / 1e6
                    val perStep = (totalMs - readMsB) / args.decBench
                    val bo = JSONObject()
                    bo.put("n", args.decBench); bo.put("single_run_plus_read_ms", single); bo.put("runs_ms", runsMs)
                    bo.put("final_read_ms", readMsB); bo.put("total_ms", totalMs); bo.put("per_step_ms", perStep)
                    res.put("decbench", bo)
                    ProbeLog.i("e2e|decbench|n=${args.decBench}|single_run_plus_read_ms=${"%.1f".format(single)}|runs_ms=${"%.1f".format(runsMs)}|final_read_ms=${"%.1f".format(readMsB)}|total_ms=${"%.1f".format(totalMs)}|per_step_ms=${"%.1f".format(perStep)}")
                }
                index++
            }
        }
        res.put("utterances", utterances)
        res.put("warm_encode", Metrics.stats(allEncodeMs))
        res.put("warm_step", Metrics.stats(allStepMs))
        res.put("warm_step_run_only", Metrics.stats(allRunOnlyMs))
        ProbeLog.i("e2e|warm(excluding utt 0)|encode=${fmtStats(Metrics.stats(allEncodeMs))}|step=${fmtStats(Metrics.stats(allStepMs))}|step_run_only=${fmtStats(Metrics.stats(allRunOnlyMs))}")
        Metrics.snapshot(ctx, "end").let { res.put("mem_end", it) }

        listOf(encInBuf, encOutBuf, decEncBuf, decIdsBuf, decMaskBuf, decOutBuf).forEach { it.close() }
        if (decModel !== model) decModel.close()
        model.close()
        env.close()
        Metrics.snapshot(ctx, "after_close").let { res.put("mem_after_close", it) }
    }

    private fun argmax(logits: FloatArray, off: Int, n: Int): Int {
        var best = 0
        var bv = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val v = logits[off + i]
            if (v > bv) { bv = v; best = i }
        }
        return best
    }

    /** NaN count, max, argmax, runner-up and the logit at EOT for one row -- the GPU-decoder diagnostic. */
    private fun rowDiag(logits: FloatArray, off: Int, n: Int, best: Int): String {
        var nan = 0; var second = -1; var sv = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val v = logits[off + i]
            if (v.isNaN() || v.isInfinite()) { nan++; continue }
            if (i != best && v > sv) { sv = v; second = i }
        }
        return "nan_inf=$nan|argmax=$best(${"%.3f".format(logits[off + best])})|second=$second(${"%.3f".format(sv)})|eot_logit=${"%.3f".format(logits[off + args.eot])}"
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
        o.put("count", out.size)
        o.put("nan_or_inf", nan)
        o.put("mean", sum / out.size)
        o.put("mean_abs", sumAbs / out.size)
        o.put("min", mn.toDouble())
        o.put("max", mx.toDouble())
        return o
    }

    companion object {
        const val SIG_ENCODE = "encode"
        const val SIG_DECODE = "decode"
        const val N_FRAMES = 3000
        const val DECODE_WINDOW = 128
    }
}
