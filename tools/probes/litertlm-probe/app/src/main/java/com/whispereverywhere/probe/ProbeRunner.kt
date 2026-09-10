package com.whispereverywhere.probe

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File

/** One run = one result JSON under files/results/<tag>.json and a `PROBE|DONE|...` / `PROBE|FAIL|...` line. */
class ProbeRunner(private val ctx: Context, private val args: ProbeArgs) {
    fun run() {
        val res = JSONObject()
        res.put("tag", args.tag)
        res.put("mode", args.mode)
        res.put("accel", args.accel)
        res.put("model", args.model)
        res.put("threads", args.threads)
        res.put("warm", args.warm)
        res.put("signature", args.signature)
        res.put("gpuprec", args.gpuPrecision)
        try {
            logDevice(res)
            when (args.mode) {
                "info" -> LiteRtProbe(ctx, args).info(res)
                "litert" -> LiteRtProbe(ctx, args).run(res)
                "lm" -> LmProbe(ctx, args).run(res)
                "e2e" -> E2eProbe(ctx, args).run(res)
                else -> throw IllegalArgumentException("unknown mode ${args.mode}")
            }
            res.put("ok", true)
            write(res)
            ProbeLog.i("DONE|tag=${args.tag}|ok=true")
        } catch (t: Throwable) {
            res.put("ok", false)
            res.put("error", t.toString())
            res.put("error_class", t.javaClass.name)
            res.put("error_message", t.message ?: "")
            ProbeLog.e("FAIL|tag=${args.tag}|" + t.toString(), t)
            write(res)
            ProbeLog.i("DONE|tag=${args.tag}|ok=false")
        }
    }

    private fun logDevice(res: JSONObject) {
        val nld = ctx.applicationInfo.nativeLibraryDir
        val d = JSONObject()
        d.put("soc_manufacturer", Build.SOC_MANUFACTURER)
        d.put("soc_model", Build.SOC_MODEL)
        d.put("model", Build.MODEL)
        d.put("sdk_int", Build.VERSION.SDK_INT)
        d.put("native_library_dir", nld)
        val libs = File(nld).list()?.sorted() ?: emptyList()
        d.put("native_libs", libs.joinToString(","))
        res.put("device", d)
        ProbeLog.i("device|soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}|model=${Build.MODEL}|sdk=${Build.VERSION.SDK_INT}|nativeLibraryDir=$nld|libs=${libs.joinToString(",")}")
        val m = args.model
        if (m != null) {
            val f = File(m)
            ProbeLog.i("model|path=$m|exists=${f.exists()}|bytes=${f.length()}")
            res.put("model_bytes", f.length())
        }
        Metrics.snapshot(ctx, "start").let { res.put("mem_start", it) }
    }

    private fun write(res: JSONObject) {
        try {
            val dir = File(ctx.filesDir, "results").apply { mkdirs() }
            File(dir, args.tag + ".json").writeText(res.toString(2))
        } catch (t: Throwable) {
            ProbeLog.e("result write failed", t)
        }
    }
}
