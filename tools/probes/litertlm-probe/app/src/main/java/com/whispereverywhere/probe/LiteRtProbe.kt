package com.whispereverywhere.probe

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.NpuCompatibilityChecker
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random

/**
 * E5 / E4-lite: run one signature of a .tflite through LiteRT's CompiledModel on CPU (XNNPACK), GPU
 * (libLiteRtClGlAccelerator.so on Mali-G720) or NPU (the MediaTek dispatch + compiler plugin from the
 * app's nativeLibraryDir, JIT -- no AOT bytecode). One cold run (the first invoke after create), then
 * `warm` timed runs. The input is a fixed seeded pseudo-random tensor so every backend sees the same
 * bytes and the output fingerprint can be compared across backends.
 */
class LiteRtProbe(private val ctx: Context, private val args: ProbeArgs) {

    fun info(res: JSONObject) {
        val nld = args.dispatchDir ?: ctx.applicationInfo.nativeLibraryDir
        ProbeLog.i("info|NpuCompatibilityChecker.Mediatek.isDeviceSupported=${safe { NpuCompatibilityChecker.Mediatek.isDeviceSupported() }}|Qualcomm=${safe { NpuCompatibilityChecker.Qualcomm.isDeviceSupported() }}|GoogleTensor=${safe { NpuCompatibilityChecker.GoogleTensor.isDeviceSupported() }}|Default=${safe { NpuCompatibilityChecker.Default.isDeviceSupported() }}")
        // Environment.create(Map) exists in both litert 2.1.1 and 2.2.0 (the Context overload is 2.2.0-only).
        val env = Environment.create(mapOf(
            Environment.Option.DispatchLibraryDir to nld,
            Environment.Option.CompilerPluginLibraryDir to nld,
        ))
        val avail = env.getAvailableAccelerators()
        ProbeLog.i("info|availableAccelerators=${avail.joinToString(",")}|dispatchDir=$nld")
        res.put("available_accelerators", avail.joinToString(","))
        env.close()
    }

    private fun safe(f: () -> Any?): String = try { f().toString() } catch (t: Throwable) { "EXC:" + t }

    fun run(res: JSONObject) {
        val modelPath = requireNotNull(args.model) { "--es model is required" }
        val nld = args.dispatchDir ?: ctx.applicationInfo.nativeLibraryDir
        val accel = when (args.accel.lowercase()) {
            "npu" -> Accelerator.NPU
            "gpu" -> Accelerator.GPU
            "cpu" -> Accelerator.CPU
            else -> throw IllegalArgumentException("accel must be cpu|gpu|npu")
        }
        val envOpts = HashMap<Environment.Option, String>()
        if (accel == Accelerator.NPU) {
            envOpts[Environment.Option.DispatchLibraryDir] = nld
            envOpts[Environment.Option.CompilerPluginLibraryDir] = nld
        }
        val env = Environment.create(envOpts)
        val avail = env.getAvailableAccelerators()
        ProbeLog.i("litert|availableAccelerators=${avail.joinToString(",")}|requested=$accel|dispatchDir=$nld")
        res.put("available_accelerators", avail.joinToString(","))

        // Request ONLY the named accelerator so a refusal is an exception, not a silent CPU run.
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
        ProbeLog.i("litert|create_ms=${"%.1f".format(createMs)}|accel=$accel|threads=${args.threads}")
        res.put("create_ms", createMs)
        Metrics.snapshot(ctx, "after_create").let { res.put("mem_after_create", it) }

        val sig = args.signature
        val inType = model.getInputTensorType("args_0", sig)
        val outType = model.getOutputTensorType("output_0", sig)
        val inDims = (inType.layout?.dimensions ?: emptyList())
        val outDims = (outType.layout?.dimensions ?: emptyList())
        ProbeLog.i("litert|signature=$sig|in=args_0 ${inType.elementType} $inDims|out=output_0 ${outType.elementType} $outDims")
        res.put("in_dims", JSONArray(inDims))
        res.put("out_dims", JSONArray(outDims))

        val n = inDims.fold(1L) { a, b -> a * b }.toInt()
        val rnd = Random(42)
        val input = FloatArray(n) { rnd.nextFloat() * 2f - 1f }   // whisper log-mel is normalised into about [-1, 1]

        val inputs = model.createInputBuffers(sig)
        val outputs = model.createOutputBuffers(sig)
        inputs[0].writeFloat(input)

        // Cold: the first invoke after create (on the NPU this is where a deferred JIT compile lands).
        // Every timed run ends with a readFloat() of the output: the GPU accelerator's run() returns
        // before the OpenCL queue drains (measured 2026-09-09: 2-4 ms "runs" on Mali until the output
        // was read), so run_ms alone is only honest for synchronous backends; run+read is the number.
        val c0 = System.nanoTime()
        model.run(inputs, outputs, sig)
        val coldRunMs = (System.nanoTime() - c0) / 1e6
        val out = outputs[0].readFloat()
        val coldMs = (System.nanoTime() - c0) / 1e6
        ProbeLog.i("litert|cold_run_ms=${"%.1f".format(coldRunMs)}|cold_run_plus_read_ms=${"%.1f".format(coldMs)}")
        res.put("cold_run_only_ms", coldRunMs)
        res.put("cold_run_ms", coldMs)
        res.put("cold_total_ms", coldMs + createMs)
        res.put("fingerprint_cold", fingerprint(out))

        Metrics.snapshot(ctx, "after_cold").let { res.put("mem_after_cold", it) }

        val times = ArrayList<Double>()
        val runOnly = ArrayList<Double>()
        var out2 = out
        for (i in 0 until args.warm) {
            val s = System.nanoTime()
            model.run(inputs, outputs, sig)
            runOnly.add((System.nanoTime() - s) / 1e6)
            out2 = outputs[0].readFloat()
            times.add((System.nanoTime() - s) / 1e6)
        }
        val st = Metrics.stats(times)
        val stRun = Metrics.stats(runOnly)
        res.put("warm", st)
        res.put("warm_run_only", stRun)
        res.put("warm_runs_ms", JSONArray(times))
        res.put("warm_run_only_ms", JSONArray(runOnly))
        ProbeLog.i("litert|warm|n=${times.size}|mean_ms=${fmt(st, "mean_ms")}|median_ms=${fmt(st, "median_ms")}|min_ms=${fmt(st, "min_ms")}|max_ms=${fmt(st, "max_ms")}|sd_ms=${fmt(st, "sd_ms")}|(run+read)")
        ProbeLog.i("litert|warm_run_only|n=${runOnly.size}|mean_ms=${fmt(stRun, "mean_ms")}|median_ms=${fmt(stRun, "median_ms")}|min_ms=${fmt(stRun, "min_ms")}|max_ms=${fmt(stRun, "max_ms")}|sd_ms=${fmt(stRun, "sd_ms")}")
        ProbeLog.i("litert|warm_runs_ms=" + times.joinToString(",") { "%.1f".format(it) })

        val fp = fingerprint(out2)
        res.put("fingerprint_warm", fp)
        ProbeLog.i("litert|fingerprint|" + fp.toString())

        Metrics.snapshot(ctx, "after_warm").let { res.put("mem_after_warm", it) }

        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
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
