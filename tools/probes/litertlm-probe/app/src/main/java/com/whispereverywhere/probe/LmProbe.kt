package com.whispereverywhere.probe

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * E3: load a .litertlm through LiteRT-LM on Backend.NPU(nativeLibraryDir) / GPU / CPU, send one short
 * prompt, and read the runtime's own BenchmarkInfo (init time, TTFT, prefill/decode token counts and
 * tokens per second). The backend the runtime ACTUALLY used is read from its native log lines in
 * logcat (drive.py keeps them), not from this class.
 */
@OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
class LmProbe(private val ctx: Context, private val args: ProbeArgs) {
    fun run(res: JSONObject) {
        val modelPath = requireNotNull(args.model) { "--es model is required" }
        val nld = args.dispatchDir ?: ctx.applicationInfo.nativeLibraryDir
        Engine.setNativeMinLogSeverity(LogSeverity.INFO)
        val backend: Backend = when (args.accel.lowercase()) {
            "npu" -> Backend.NPU(nativeLibraryDir = nld)
            "gpu" -> Backend.GPU()
            "cpu" -> Backend.CPU(threadCount = args.threads)
            else -> throw IllegalArgumentException("accel must be cpu|gpu|npu")
        }
        val cacheDir = File(ctx.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
        ProbeLog.i("lm|backend=${backend.name}|nativeLibraryDir=$nld|cacheDir=$cacheDir|maxTokens=${args.maxTokens}")
        val cfg = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            maxNumTokens = args.maxTokens + 256,
            cacheDir = cacheDir,
        )
        Metrics.snapshot(ctx, "before_init").let { res.put("mem_before_init", it) }
        val engine = Engine(cfg)
        val t0 = System.nanoTime()
        engine.initialize()
        val initMs = (System.nanoTime() - t0) / 1e6
        ProbeLog.i("lm|initialize_ms=${"%.1f".format(initMs)}|initialized=${engine.isInitialized()}")
        res.put("initialize_ms", initMs)
        Metrics.snapshot(ctx, "after_init").let { res.put("mem_after_init", it) }

        val conv = engine.createConversation(ConversationConfig())
        val sb = StringBuilder()
        var firstNs = -1L
        var chunks = 0
        val s0 = System.nanoTime()
        runBlocking {
            conv.sendMessageAsync(args.prompt).collect { m: Message ->
                if (firstNs < 0) firstNs = System.nanoTime()
                chunks++
                sb.append(textOf(m))
            }
        }
        val totalMs = (System.nanoTime() - s0) / 1e6
        val ttftMs = if (firstNs > 0) (firstNs - s0) / 1e6 else -1.0
        ProbeLog.i("lm|stream|chunks=$chunks|ttft_ms=${"%.1f".format(ttftMs)}|total_ms=${"%.1f".format(totalMs)}|chars=${sb.length}")
        res.put("stream_chunks", chunks)
        res.put("stream_ttft_ms", ttftMs)
        res.put("stream_total_ms", totalMs)
        res.put("response_chars", sb.length)
        res.put("response_head", sb.toString().take(160))
        ProbeLog.i("lm|response_head=" + sb.toString().take(160).replace('\n', ' '))

        // Stream-derived rates (chunks are one token each for a text conversation): the probe's own numbers,
        // independent of the runtime's benchmark plumbing.
        val decodeS = (totalMs - ttftMs) / 1000.0
        val streamDecodeTps = if (chunks > 1 && decodeS > 0) (chunks - 1) / decodeS else -1.0
        res.put("stream_decode_tps", streamDecodeTps)
        ProbeLog.i("lm|stream_rates|decode_chunks_per_s=${"%.2f".format(streamDecodeTps)}|(chunks-1)/(total-ttft)")

        // Conversation.getBenchmarkInfo() needs BenchmarkParams in the native EngineSettings, which the 0.17.0
        // Kotlin EngineConfig does not expose (measured 2026-09-10: "Benchmark is not enabled. Please make sure
        // the BenchmarkParams is set in the EngineSettings."). Try it, record the outcome, never fail the run.
        try {
            val bi = conv.getBenchmarkInfo()
            res.put("benchmark", benchJson(bi))
            ProbeLog.i("lm|benchmark|" + benchLine(bi))
        } catch (t: Throwable) {
            res.put("benchmark_error", t.toString())
            ProbeLog.w("lm|benchmark_unavailable|" + t.toString().replace('\n', ' ').take(200))
        }
        Metrics.snapshot(ctx, "after_generate").let { res.put("mem_after_generate", it) }

        conv.close()
        engine.close()
        Metrics.snapshot(ctx, "after_close").let { res.put("mem_after_close", it) }

        // The runtime's own benchmark entry point (BenchmarkKt.benchmark: modelPath, backend, prefillTokens,
        // decodeTokens, cacheDir, prompt -> nativeCreateBenchmark) -- a fresh engine with BenchmarkParams set.
        if (args.bench) {
            try {
                val t1 = System.nanoTime()
                val bi = com.google.ai.edge.litertlm.benchmark(
                    modelPath, backend, args.prefillTokens, args.maxTokens, cacheDir, args.prompt)
                val benchMs = (System.nanoTime() - t1) / 1e6
                res.put("benchmark_fn", benchJson(bi))
                res.put("benchmark_fn_wall_ms", benchMs)
                ProbeLog.i("lm|benchmark_fn|wall_ms=${"%.1f".format(benchMs)}|" + benchLine(bi))
            } catch (t: Throwable) {
                res.put("benchmark_fn_error", t.toString())
                ProbeLog.w("lm|benchmark_fn_failed|" + t.toString().replace('\n', ' ').take(300))
            }
            Metrics.snapshot(ctx, "after_benchmark_fn").let { res.put("mem_after_benchmark_fn", it) }
        }
    }

    private fun benchJson(bi: com.google.ai.edge.litertlm.BenchmarkInfo): JSONObject {
        val b = JSONObject()
        b.put("init_time_s", bi.initTimeInSecond)
        b.put("ttft_s", bi.timeToFirstTokenInSecond)
        b.put("prefill_tokens", bi.lastPrefillTokenCount)
        b.put("decode_tokens", bi.lastDecodeTokenCount)
        b.put("prefill_tps", bi.lastPrefillTokensPerSecond)
        b.put("decode_tps", bi.lastDecodeTokensPerSecond)
        return b
    }

    private fun benchLine(bi: com.google.ai.edge.litertlm.BenchmarkInfo): String =
        "init_s=${bi.initTimeInSecond}|ttft_s=${bi.timeToFirstTokenInSecond}|prefill_tokens=${bi.lastPrefillTokenCount}|prefill_tps=${bi.lastPrefillTokensPerSecond}|decode_tokens=${bi.lastDecodeTokenCount}|decode_tps=${bi.lastDecodeTokensPerSecond}"

    private fun textOf(m: Message): String =
        m.contents.contents.joinToString("") { c -> if (c is Content.Text) c.text else "" }
}
