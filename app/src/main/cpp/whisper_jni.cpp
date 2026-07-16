#include <jni.h>
#include <string>
#include <thread>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_init(
        JNIEnv *env, jobject /* this */, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        return 0;
    }
    whisper_context_params cparams = whisper_context_default_params();
    // CPU path for v1 (no GPU/OpenCL); mmap the model from filesDir.
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_transcribe(
        JNIEnv *env, jobject /* this */,
        jlong ctxPtr, jfloatArray samples, jstring lang, jboolean translate) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }

    const jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(static_cast<size_t>(n));
    if (n > 0) {
        env->GetFloatArrayRegion(samples, 0, n, pcm.data());
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = (translate == JNI_TRUE);
    params.single_segment   = false;
    params.no_context       = true;

    int cores = static_cast<int>(std::thread::hardware_concurrency());
    if (cores <= 0) {
        cores = 4;
    }
    params.n_threads = cores;

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
        params.detect_language = true;
    } else {
        langC = langStr.c_str();
        params.language     = langC;
        params.detect_language = false;
    }

    if (whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size())) != 0) {
        LOGE("whisper_full failed");
        return env->NewStringUTF("");
    }

    std::string result;
    const int nSeg = whisper_full_n_segments(ctx);
    for (int i = 0; i < nSeg; ++i) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg != nullptr) {
            result += seg;
        }
    }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_free(
        JNIEnv *env, jobject /* this */, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}
