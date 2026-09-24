package com.whispereverywhere.probe

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random

/**
 * `mode=sig`: time ANY signature of a .tflite on CPU / GPU / NPU with synthetic inputs, binding every
 * input by name. Written for the product-shaped turbo pair (Qualcomm's HfWhisper IO exported through
 * litert-torch): `decode` has 19 inputs (ids, mask, 8 self-cache, 8 cross-cache, position) and 9 outputs,
 * none of them `args_0`, so `mode=litert` cannot drive it.
 *
 *   --es inputs   comma list of input names; floats get java.util.Random(42) in [-1,1] (or zeros with
 *                 a `:0` suffix, e.g. `attention_mask:0`), int32 inputs get zeros
 *   --es outputs  comma list of output names (default output_0)
 *
 * The first output is fingerprinted like `mode=litert` so a host reference can be compared.
 */
class SigProbe(private val ctx: Context, private val args: ProbeArgs) {
    fun run(res: JSONObject) {
        val modelPath = requireNotNull(args.model) { "--es model is required" }
        val nld = args.dispatchDir ?: ctx.applicationInfo.nativeLibraryDir
        val accel = when (args.accel.lowercase()) {
            "npu" -> Accelerator.NPU
            "gpu" -> Accelerator.GPU
            "cpu" -> Accelerator.CPU
            else -> throw IllegalArgumentException("accel must be cpu|gpu|npu")
        }
        val inputSpecs = requireNotNull(args.inputs) { "--es inputs is required for mode=sig" }
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .map { spec -> val p = spec.split(":"); p[0] to (p.getOrNull(1) == "0") }
        val outputNames = (args.outputs ?: "output_0").split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val envOpts = HashMap<Environment.Option, String>()
        if (accel == Accelerator.NPU) {
            envOpts[Environment.Option.DispatchLibraryDir] = nld
            envOpts[Environment.Option.CompilerPluginLibraryDir] = nld
        }
        val env = Environment.create(envOpts)
        val opts = if (args.noFallback) CompiledModel.Options(accel) else CompiledModel.Options(accel, Accelerator.CPU)
        opts.cpuOptions = CompiledModel.CpuOptions(numThreads = args.threads)

        Metrics.snapshot(ctx, "before_create").let { res.put("mem_before_create", it) }
        val t0 = System.nanoTime()
        val model = CompiledModel.create(modelPath, opts, env)
        val createMs = (System.nanoTime() - t0) / 1e6
        ProbeLog.i("sig|create_ms=${"%.1f".format(createMs)}|accel=$accel|threads=${args.threads}")
        res.put("create_ms", createMs)
        Metrics.snapshot(ctx, "after_create").let { res.put("mem_after_create", it) }

        val sig = args.signature
        val rnd = Random(42)
        val inputs = LinkedHashMap<String, com.google.ai.edge.litert.TensorBuffer>()
        val inDesc = JSONObject()
        for ((name, zeros) in inputSpecs) {
            val t = model.getInputTensorType(name, sig)
            val dims = t.layout?.dimensions ?: emptyList()
            val n = dims.fold(1L) { a, b -> a * b }.toInt()
            val et = t.elementType.toString()
            val buf = model.createInputBuffer(name, sig)
            if (et.contains("INT")) {
                buf.writeInt(IntArray(n))
            } else {
                buf.writeFloat(if (zeros) FloatArray(n) else FloatArray(n) { rnd.nextFloat() * 2f - 1f })
            }
            inputs[name] = buf
            inDesc.put(name, "$et $dims${if (zeros) " zeros" else ""}")
            ProbeLog.i("sig|in=$name $et $dims${if (zeros) " zeros" else ""}")
        }
        val outputs = LinkedHashMap<String, com.google.ai.edge.litert.TensorBuffer>()
        val outDesc = JSONObject()
        for (name in outputNames) {
            val t = model.getOutputTensorType(name, sig)
            outputs[name] = model.createOutputBuffer(name, sig)
            outDesc.put(name, "${t.elementType} ${t.layout?.dimensions}")
            ProbeLog.i("sig|out=$name ${t.elementType} ${t.layout?.dimensions}")
        }
        res.put("inputs", inDesc)
        res.put("outputs", outDesc)
        val first = outputNames.first()

        val c0 = System.nanoTime()
        model.run(inputs, outputs, sig)
        val coldRunMs = (System.nanoTime() - c0) / 1e6
        var out = outputs[first]!!.readFloat()
        val coldMs = (System.nanoTime() - c0) / 1e6
        ProbeLog.i("sig|cold_run_ms=${"%.1f".format(coldRunMs)}|cold_run_plus_read_ms=${"%.1f".format(coldMs)}")
        res.put("cold_run_only_ms", coldRunMs)
        res.put("cold_run_ms", coldMs)
        res.put("fingerprint_cold", fingerprint(out))
        Metrics.snapshot(ctx, "after_cold").let { res.put("mem_after_cold", it) }

        val times = ArrayList<Double>()
        val runOnly = ArrayList<Double>()
        for (i in 0 until args.warm) {
            val s = System.nanoTime()
            model.run(inputs, outputs, sig)
            runOnly.add((System.nanoTime() - s) / 1e6)
            out = outputs[first]!!.readFloat()
            times.add((System.nanoTime() - s) / 1e6)
        }
        val st = Metrics.stats(times)
        val stRun = Metrics.stats(runOnly)
        res.put("warm", st)
        res.put("warm_run_only", stRun)
        res.put("warm_runs_ms", JSONArray(times))
        ProbeLog.i("sig|warm|n=${times.size}|mean_ms=${fmt(st, "mean_ms")}|median_ms=${fmt(st, "median_ms")}|min_ms=${fmt(st, "min_ms")}|max_ms=${fmt(st, "max_ms")}|sd_ms=${fmt(st, "sd_ms")}|(run+read of $first)")
        ProbeLog.i("sig|warm_run_only|n=${runOnly.size}|mean_ms=${fmt(stRun, "mean_ms")}|median_ms=${fmt(stRun, "median_ms")}|min_ms=${fmt(stRun, "min_ms")}|max_ms=${fmt(stRun, "max_ms")}")
        val fp = fingerprint(out)
        res.put("fingerprint_warm", fp)
        ProbeLog.i("sig|fingerprint|$first|" + fp.toString())
        Metrics.snapshot(ctx, "after_warm").let { res.put("mem_after_warm", it) }

        inputs.values.forEach { it.close() }
        outputs.values.forEach { it.close() }
        model.close()
        env.close()
        Metrics.snapshot(ctx, "after_close").let { res.put("mem_after_close", it) }
    }

    private fun fmt(o: JSONObject, k: String): String = if (o.has(k)) "%.1f".format(o.getDouble(k)) else "-"

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
        o.put("head", JSONArray(out.take(8).map { it.toDouble() }))
        return o
    }
}
