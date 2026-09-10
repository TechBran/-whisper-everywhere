package com.whispereverywhere.probe

import android.content.Intent

/**
 * Driven entirely by `am start` extras so every run is scriptable from the PC (see drive.py):
 *   --es mode      info | litert | lm
 *   --es model     absolute path of the .tflite / .litertlm (pushed into files/ via run-as)
 *   --es accel     cpu | gpu | npu          (litert: Accelerator.*; lm: Backend.*)
 *   --ei threads   CPU threads (litert CpuOptions.numThreads / lm Backend.CPU(threadCount))
 *   --ei warm      warm runs after the cold one (litert)
 *   --es signature tflite signature to run (default "encode")
 *   --es tag       result file name stem (files/results/<tag>.json)
 *   --es prompt    lm prompt text
 *   --ei maxtokens lm max decode tokens
 *   --es gpuprec   default | fp16 | fp32     (litert GpuOptions.precision)
 *   --es dispatchdir override the dispatch/compiler-plugin dir (default applicationInfo.nativeLibraryDir)
 *   --ez nofallback  litert: request ONLY the named accelerator (default true)
 *   --ez bench     lm: also run the runtime's own BenchmarkKt.benchmark() after the conversation (default true)
 *   --ei prefill   lm: prefillTokens for benchmark() (default 64)
 */
data class ProbeArgs(
    val mode: String?,
    val model: String?,
    val accel: String,
    val threads: Int,
    val warm: Int,
    val signature: String,
    val tag: String,
    val prompt: String,
    val maxTokens: Int,
    val gpuPrecision: String,
    val dispatchDir: String?,
    val noFallback: Boolean,
    val bench: Boolean,
    val prefillTokens: Int,
) {
    companion object {
        fun from(intent: Intent?): ProbeArgs = ProbeArgs(
            mode = intent?.getStringExtra("mode"),
            model = intent?.getStringExtra("model"),
            accel = intent?.getStringExtra("accel") ?: "cpu",
            threads = intent?.getIntExtra("threads", 4) ?: 4,
            warm = intent?.getIntExtra("warm", 20) ?: 20,
            signature = intent?.getStringExtra("signature") ?: "encode",
            tag = intent?.getStringExtra("tag") ?: "run",
            prompt = intent?.getStringExtra("prompt") ?: "Write one short sentence about the sea.",
            maxTokens = intent?.getIntExtra("maxtokens", 64) ?: 64,
            gpuPrecision = intent?.getStringExtra("gpuprec") ?: "default",
            dispatchDir = intent?.getStringExtra("dispatchdir"),
            noFallback = intent?.getBooleanExtra("nofallback", true) ?: true,
            bench = intent?.getBooleanExtra("bench", true) ?: true,
            prefillTokens = intent?.getIntExtra("prefill", 64) ?: 64,
        )
    }
}
