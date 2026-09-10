package com.whispereverywhere.probe

import android.content.Intent

/**
 * Driven entirely by `am start` extras so every run is scriptable from the PC (see drive.py):
 *   --es mode      info | litert | lm | e2e
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
 *   --es mels      e2e: comma list of log-mel files under files/ (f32 [n_mels*3000], from whisper_e2e.py mel)
 *   --ei utts      e2e: rounds over the mel list (utterance 0 is the cold one; default 5)
 *   --es tokens    e2e: prompt ids, comma list (default large-v3's <|sot|>,<|en|>,<|transcribe|>,<|notimestamps|>)
 *   --ei eot       e2e: <|endoftext|> id (default 50257)
 *   --es tokenizer e2e: PC path of the tokenizer.json, echoed into the JSON for summarize/detok
 *   --es maskneg   e2e: the additive causal mask's off-diagonal value (default -1e9; -1e4 is fp16-safe)
 *   --ez freshbufs e2e: create new decode input/output TensorBuffers every step (GPU staleness diagnostic)
 *   --ez rewriteall e2e: rewrite enc states + mask (not only ids) before every decode step (diagnostic)
 *   --ei pad       e2e: the id filling the window beyond the current position (default = eot; a causal mask
 *                  must make it irrelevant -- a different answer with pad=0 means the mask leaks)
 *   --es decaccel  e2e: same | cpu -- run `decode` on a SECOND CompiledModel on the CPU (encoder stays on accel)
 *   --ez dumpstates e2e: write utterance 0's encoder states (f32 LE) to files/results/<tag>.states.bin for the PC
 *   --ei decbench  e2e: after utterance 0, enqueue N decode runs with the same inputs and read ONCE -- separates
 *                  the accelerator's per-step compute from the 26 MB logits readback the Kotlin API forces (default 0)
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
    val mels: String?,
    val utts: Int,
    val tokens: String,
    val eot: Int,
    val tokenizer: String?,
    val maskNeg: Float,
    val freshBufs: Boolean,
    val rewriteAll: Boolean,
    val pad: Int?,
    val decAccel: String,
    val dumpStates: Boolean,
    val decBench: Int,
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
            mels = intent?.getStringExtra("mels"),
            utts = intent?.getIntExtra("utts", 5) ?: 5,
            tokens = intent?.getStringExtra("tokens") ?: "50258,50259,50360,50364",
            eot = intent?.getIntExtra("eot", 50257) ?: 50257,
            tokenizer = intent?.getStringExtra("tokenizer"),
            maskNeg = intent?.getStringExtra("maskneg")?.toFloatOrNull() ?: -1e9f,
            freshBufs = intent?.getBooleanExtra("freshbufs", false) ?: false,
            rewriteAll = intent?.getBooleanExtra("rewriteall", false) ?: false,
            pad = intent?.let { if (it.hasExtra("pad")) it.getIntExtra("pad", 0) else null },
            decAccel = intent?.getStringExtra("decaccel") ?: "same",
            dumpStates = intent?.getBooleanExtra("dumpstates", false) ?: false,
            decBench = intent?.getIntExtra("decbench", 0) ?: 0,
        )
    }
}
