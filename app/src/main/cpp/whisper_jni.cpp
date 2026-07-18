#include <jni.h>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <android/log.h>
#include "whisper.h"
#include "ggml.h"
#include "ggml-backend.h"

#define LOG_TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward ggml + whisper internal logging (normally stderr, which Android discards) to logcat.
// This is how the OpenCL/CPU backends narrate their init (platform/device selection, kernel
// compilation, fallbacks) — essential for diagnosing backend behavior on-device.
static void we_native_log(enum ggml_log_level level, const char *text, void * /*user*/) {
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_WARN)  prio = ANDROID_LOG_WARN;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    __android_log_write(prio, "ggml", text);
}

static void we_install_native_logging() {
    static bool done = false;
    if (done) return;
    done = true;
    ggml_log_set(we_native_log, nullptr);
    whisper_log_set(we_native_log, nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_loadBackends(
        JNIEnv *env, jobject /* this */, jstring nativeLibDir) {
    we_install_native_logging();
    const char *dir = env->GetStringUTFChars(nativeLibDir, nullptr);
    if (dir == nullptr) {
        return;
    }
    // GGML_BACKEND_DL mode: backends are standalone modules. Load them by BARE SONAME, which
    // resolves through the app's linker namespace — the only mechanism that works when native
    // libs are NOT extracted to disk (modern APKs: android:extractNativeLibs=false keeps the
    // .so inside base.apk, so a directory scan of nativeLibraryDir finds nothing and
    // whisper_init aborts with zero registered backends — the 2.8.1 crash loop).
    // CPU must load; OpenCL is best-effort (absent/failing on non-OpenCL devices -> skipped,
    // which is the whole point: no hard DT_NEEDED chain killing the app on those phones).
    (void) dir; // kept for logging/back-compat; bare-SONAME loading needs no path
    ggml_backend_reg_t cpu = ggml_backend_load("libggml-cpu.so");
    ggml_backend_reg_t ocl = ggml_backend_load("libggml-opencl.so");
    const size_t count = ggml_backend_reg_count();
    LOGI("ggml backends loaded: cpu=%s opencl=%s total_regs=%zu",
         cpu ? "ok" : "FAILED", ocl ? "ok" : "absent", count);
    if (cpu == nullptr) {
        LOGE("CPU backend failed to load — transcription cannot work");
    }
    env->ReleaseStringUTFChars(nativeLibDir, dir);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_init(
        JNIEnv *env, jobject /* this */, jstring modelPath, jboolean useGpu) {
    we_install_native_logging();
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        return 0;
    }
    whisper_context_params cparams = whisper_context_default_params();
    // GPU = the Qualcomm-maintained ggml OpenCL backend (whisper.cpp v1.9.1). The Kotlin side
    // (GpuPolicy) allowlists Adreno 7xx/8xx/X and arms crash sentinels; ggml additionally falls
    // back to CPU if OpenCL init fails. Vulkan remains CLOSED on Adreno (driver-compiler aborts
    // + vk::DeviceLostError on every graph dispatch — proven on-device; see git history).
    cparams.use_gpu = (useGpu == JNI_TRUE);
    // Flash attention produces garbage + ~10x slowdown on Adreno OpenCL -- keep OFF explicitly.
    cparams.flash_attn = false;
    LOGI("init: use_gpu=%d flash_attn=0", cparams.use_gpu ? 1 : 0);
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

// ---------------------------------------------------------------------------------------------
// Manual Silero VAD (review-driven rework). Running VAD ourselves — instead of whisper_full's
// params.vad — fixes four confirmed defects at once:
//   1. audio_ctx is now computed from the FILTERED audio, so the encoder is no longer billed
//      for the buffered silence the VAD exists to remove.
//   2. The VAD context is created ONCE and cached (params.vad reloaded the ~0.9 MB model from
//      disk on every whisper_full call).
//   3. The sub-1.1s zero-pad is applied AFTER filtering, so short utterances survive.
//   4. A VAD failure (bad model file, init error) degrades gracefully to no-VAD transcription
//      instead of erroring out 100% of output.
// ---------------------------------------------------------------------------------------------

static std::mutex             g_vad_mutex;
static whisper_vad_context  * g_vad_ctx = nullptr;
static std::string            g_vad_path;

// Filters [pcm] down to speech-only (with 100 ms inter-segment gaps, mirroring whisper_full's
// own VAD assembly). Returns false when VAD is unavailable (caller proceeds unfiltered). On
// success pcm holds the filtered audio — possibly EMPTY when no speech at all was detected.
static bool we_vad_filter(const std::string &vadPath, std::vector<float> &pcm) {
    std::lock_guard<std::mutex> lock(g_vad_mutex);
    if (g_vad_ctx == nullptr || g_vad_path != vadPath) {
        if (g_vad_ctx != nullptr) {
            whisper_vad_free(g_vad_ctx);
            g_vad_ctx = nullptr;
        }
        whisper_vad_context_params vcp = whisper_vad_default_context_params();
        g_vad_ctx = whisper_vad_init_from_file_with_params(vadPath.c_str(), vcp);
        g_vad_path = vadPath;
        if (g_vad_ctx == nullptr) {
            LOGE("VAD init failed for %s — transcribing without VAD", vadPath.c_str());
            return false;
        }
        LOGI("VAD context loaded (%s)", vadPath.c_str());
    }

    whisper_vad_params vp = whisper_vad_default_params();
    // Onset tuning (user-reported start clipping with defaults): more pre-speech pad + a more
    // permissive threshold keep the first word's attack intact; suppress_nst absorbs noise risk.
    vp.threshold     = 0.40f;
    vp.speech_pad_ms = 150;

    whisper_vad_segments *segs =
        whisper_vad_segments_from_samples(g_vad_ctx, vp, pcm.data(), static_cast<int>(pcm.size()));
    if (segs == nullptr) {
        LOGE("VAD segmentation failed — transcribing without VAD");
        return false;
    }

    const int nseg = whisper_vad_segments_n_segments(segs);
    constexpr int kGapSamples = 1600; // 100 ms of silence between stitched segments
    std::vector<float> filtered;
    filtered.reserve(pcm.size());
    for (int i = 0; i < nseg; ++i) {
        // t0/t1 are centiseconds -> samples at 16 kHz = cs * 160.
        auto s0 = static_cast<int64_t>(whisper_vad_segments_get_segment_t0(segs, i)) * 160;
        auto s1 = static_cast<int64_t>(whisper_vad_segments_get_segment_t1(segs, i)) * 160;
        if (s0 < 0) s0 = 0;
        if (s1 > static_cast<int64_t>(pcm.size())) s1 = static_cast<int64_t>(pcm.size());
        if (s1 <= s0) continue;
        filtered.insert(filtered.end(), pcm.begin() + s0, pcm.begin() + s1);
        if (i + 1 < nseg) filtered.insert(filtered.end(), kGapSamples, 0.0f);
    }
    whisper_vad_free_segments(segs);

    LOGI("VAD: %zu -> %zu samples (%d segments)", pcm.size(), filtered.size(), nseg);
    pcm.swap(filtered);
    return true;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_transcribeRaw(
        JNIEnv *env, jobject /* this */,
        jlong ctxPtr, jfloatArray samples, jstring lang, jboolean translate,
        jstring vadModelPath) {
    auto emptyResult = [env]() { return env->NewByteArray(0); };
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) {
        return emptyResult();
    }
    if (samples == nullptr) {
        return emptyResult();
    }

    const jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(static_cast<size_t>(n));
    if (n > 0) {
        env->GetFloatArrayRegion(samples, 0, n, pcm.data());
    }

    std::string vadPathStr;
    if (vadModelPath != nullptr) {
        const char *rawVad = env->GetStringUTFChars(vadModelPath, nullptr);
        if (rawVad != nullptr) {
            vadPathStr = rawVad;
            env->ReleaseStringUTFChars(vadModelPath, rawVad);
        }
    }

    const bool vadApplied = !vadPathStr.empty() && we_vad_filter(vadPathStr, pcm);
    if (vadApplied && pcm.empty()) {
        // VAD found zero speech — nothing to transcribe, and skipping whisper entirely makes
        // silence-only commits (unconditional stop-flush, wall-clock cap) essentially free.
        return emptyResult();
    }
    if (!vadApplied) {
        // No VAD available: cheap energy gate so pure-silence commits don't reach whisper,
        // where they'd risk hallucinated text (the unconditional flush assumes SOME gate).
        float peak = 0.0f;
        for (float v : pcm) peak = std::max(peak, std::fabs(v));
        if (peak < 0.005f) {
            return emptyResult();
        }
    }

    // whisper_full silently produces 0 segments for audio under ~1s. Short commits (quick "yes",
    // final flush fragments) are real user speech — pad with trailing silence to 1.1s instead of
    // dropping the words. Applied AFTER VAD so the filter cannot strip the pad.
    constexpr size_t kMinSamples = 17600; // 1.1 s @ 16 kHz
    if (!pcm.empty() && pcm.size() < kMinSamples) {
        pcm.resize(kMinSamples, 0.0f);
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = (translate == JNI_TRUE);
    params.single_segment   = false;
    params.no_context       = true;
    // Decode quality/latency balance. temperature fallback ON (default 0.2 steps): it re-decodes
    // a chunk ONLY when whisper's own quality gates trip (compression ratio > 2.4 / avg logprob
    // < -1.0), so clean dictation pays zero — but it is the sole defense against degenerate
    // repetition loops ("word word word x50"), which a 10-minute YouTube capture hit on-device
    // (2026-07-18) with the fallback disabled. The old FUTO-style temperature_inc=0 latency
    // tuning traded that safety away; never re-disable it for long-form audio. suppress_nst
    // stops non-speech tokens ([BLANK_AUDIO], music notes) at the source instead of post-filtering.
    params.temperature_inc = 0.2f;
    params.suppress_nst    = true;

    int cores = static_cast<int>(std::thread::hardware_concurrency());
    if (cores <= 0) {
        cores = 4;
    }
    // On mobile big.LITTLE, ggml's per-op barriers make extra efficiency-core threads a NET LOSS:
    // 4 threads (the performance-core count on typical flagships) beats 6-8. Cap at 4.
    params.n_threads = (cores < 4) ? cores : 4;

    // NOTE: VAD already ran manually above (we_vad_filter) — params.vad stays false; pcm here
    // is the speech-only filtered audio.

    // Language handling: null / "auto" / "" -> auto-detect; otherwise force the code.
    std::string langStr;
    const char *langC = nullptr;
    if (lang != nullptr) {
        const char *raw = env->GetStringUTFChars(lang, nullptr);
        if (raw != nullptr) {
            langStr = raw;
            env->ReleaseStringUTFChars(lang, raw);
        }
    }
    if (langStr.empty() || langStr == "auto") {
        params.language     = "auto";
        // IMPORTANT: do NOT set detect_language = true. In whisper.cpp, detect_language makes
        // whisper_full() run language detection and RETURN immediately, WITHOUT transcribing
        // (0 segments -> empty text). With language="auto" and detect_language=false, whisper_full
        // auto-detects the language AND then transcribes.
        params.detect_language = false;
    } else {
        langC = langStr.c_str();
        params.language     = langC;
        params.detect_language = false;
    }

    // Encoder audio context. Default (0 -> full 1500 frames = 30s) makes whisper pay the full 30s
    // encoder cost even for a 2s clip — the dominant per-segment latency. Compute only the frames
    // this audio actually needs (~1 frame per 20 ms of 16 kHz audio => samples/320) plus headroom,
    // capped at the model max (1500). All real audio stays within context so quality is preserved;
    // we simply skip encoding the empty tail padding. Big latency cut for short dictation segments.
    {
        int neededFrames = static_cast<int>(pcm.size() / 320) + 64;
        // Floor raised 256 -> 768: STOCK whisper models lose accuracy under aggressive audio_ctx
        // reduction (positional-embedding mismatch; FUTO's ACFT models are fine-tuned to tolerate
        // it, ours are not) — user-visible as garbled short phrases. 768 halves the encoder cost
        // vs full context while keeping a wide safety margin; the GPU makes the rest cheap.
        if (neededFrames < 768)  neededFrames = 768;
        if (neededFrames > 1500) neededFrames = 1500;
        params.audio_ctx = neededFrames;
    }

    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        LOGE("whisper_full failed");
        return emptyResult();
    }

    std::string result;
    const int nSeg = whisper_full_n_segments(ctx);
    for (int i = 0; i < nSeg; ++i) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg != nullptr) {
            result += seg;
        }
    }
    // Return raw UTF-8 bytes, decoded to String on the Kotlin side. NewStringUTF expects
    // Modified UTF-8 and ABORTS the process (CheckJNI) on 4-byte sequences — emoji and rare
    // CJK from the multilingual models are valid UTF-8 that would crash it.
    const jsize outLen = static_cast<jsize>(result.size());
    jbyteArray out = env->NewByteArray(outLen);
    if (out == nullptr) {
        return emptyResult();
    }
    if (outLen > 0) {
        env->SetByteArrayRegion(out, 0, outLen, reinterpret_cast<const jbyte *>(result.data()));
    }
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_free(
        JNIEnv *env, jobject /* this */, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}
