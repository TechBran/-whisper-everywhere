#include <jni.h>
#include <atomic>
#include <chrono>
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

// ---------------------------------------------------------------------------------------------
// Encoder audio_ctx floor (3.6.0 Workstream G; lowered per Task G4). History: raised 256 -> 768
// for a documented accuracy regression, then 768 -> 512 on 2026-08-20 after the G3 on-device
// sweep PASSED 512 on BOTH 190 MB tiers (maxWer 0.000, 4/4 binding slices each, production
// backends) while 384/256 FAILED multi — see the audio_ctx block in transcribeRaw and
// docs/superpowers/specs/2026-08-19-audio-ctx-floor-bench.md (RESULT: PASS floor=512).
// Measured payoff: multi-CPU fixed per-commit cost 3.5 s -> 2.3 s; pro-GPU 0.96 s -> 0.77 s.
// The setter exists ONLY for the WhisperBenchTest A-B harness: it lets an instrumented bench
// measure lower floors against the default on the SAME device/model WITHOUT changing production
// behavior. Production code never calls it. Changing the DEFAULT is gated on that bench's
// recorded per-tier accuracy verdict (WerMath.floorQualifies) — never done blind.
// ---------------------------------------------------------------------------------------------
static std::atomic<int> g_audio_ctx_floor{512};

extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_setAudioCtxFloor(
        JNIEnv * /*env*/, jobject /* this */, jint floor) {
    int f = static_cast<int>(floor);
    if (f < 64)   f = 64;    // below the +64 headroom would be self-defeating
    if (f > 1500) f = 1500;  // the model maximum
    g_audio_ctx_floor.store(f, std::memory_order_relaxed);
    LOGI("audio_ctx floor set to %d (bench override; production default 512)", f);
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
        // n_threads = 1 is a straight latency win, not a tuning preference.
        // ggml_backend_cpu_set_threadpool is never called for a VAD context, so cpu_ctx->threadpool
        // is NULL and ggml_graph_compute takes the disposable path: it spawns + joins n_threads-1
        // real pthreads on EVERY graph compute (ggml-cpu.c:3319-3324) — and that compute is inside
        // the per-window frame loop (whisper.cpp:5170), once per 512 samples. At the default 4 this
        // costs 375 create/join cycles per 4 s commit and 1,407 per 15 s commit, for a ~74-node /
        // ~1.36 MFLOP graph with a ggml_barrier between every node, which cannot benefit from 4-way
        // splitting anyway. whisper_jni.cpp already encodes the softer version of this lesson for
        // whisper itself below ("extra efficiency-core threads a NET LOSS"); VAD needs the hard one.
        // FIELD NAME: n_threads (whisper.h:683). The initializer comment at whisper.cpp:4445 says
        // ".n_thread" — that is the comment, not the field, and it does not compile.
        vcp.n_threads = 1;
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

    // B' MEASURING INSTRUMENT. whisper.cpp's own "vad time = %.2f ms" line lives inside
    // whisper_vad_detect_speech_no_reset and is now WHISPER_LOG_DEBUG (compiled out) because at the
    // 3.7 streaming cadence it fired 31.25x/second. That demotion removed the only readout of the
    // very thing the n_threads = 1 pin above changes, so the measurement is restored HERE instead:
    // once per chunk, on the WE tag, around the whole segmentation call. Same quantity, ~1/125th
    // the log volume, and it survives any future upstream merge of the fork.
    const auto t_vad_start = std::chrono::steady_clock::now();
    whisper_vad_segments *segs =
        whisper_vad_segments_from_samples(g_vad_ctx, vp, pcm.data(), static_cast<int>(pcm.size()));
    const auto t_vad_us = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - t_vad_start).count();
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

    LOGI("VAD: %zu -> %zu samples (%d segments) wallMs=%.1f", pcm.size(), filtered.size(), nseg,
         static_cast<double>(t_vad_us) / 1000.0);
    pcm.swap(filtered);
    return true;
}

// ---------------------------------------------------------------------------------------------
// 3.7 Workstream A - streaming VAD probe surface (vadProbeInit / vadProbeFrame / vadProbeReset /
// vadProbeFree).
//
// A dedicated Silero context driven ONE 512-sample frame at a time from the audio capture thread,
// so the Kotlin endpointer can cut segments where the user actually stopped talking instead of
// where a wall clock ran out. It is deliberately SEPARATE from g_vad_ctx above, and that is a
// correctness requirement rather than an optimisation: we_vad_filter reaches
// whisper_vad_detect_speech, which resets the LSTM state on entry (whisper.cpp:5193) and resizes
// probs to hundreds of entries from index 0. Sharing one context corrupts the probe three
// independent ways - state wipe, probs clobber, and a ggml_backend_sched data race (sched is not
// thread-safe and both callers write the same "frame" input tensor). A mutex fixes only the
// third. Two contexts cost ~2.6 MB RSS; the process already carries one for the batch filter.
//
// Division of labour with that batch filter, which still runs on every commit: the PROBE decides
// WHEN to cut, the FILTER decides WHAT audio inside the cut reaches the encoder. Independent
// jobs, independent knobs - the filter keeps its own 0.40 / 150 ms onset tuning untouched.
//
// PROBE SAFETY: these four functions run OUTSIDE NativeComputeGate. Every other whisper call in
// this process is wrapped by it; the probe alone is not. The argument:
//   1. The gate exists for exactly two named reasons (NativeComputeGate.kt:15-21): concurrent
//      submits on the shared Adreno OpenCL command queue racing GpuPolicy's crash sentinel, and
//      two full contexts doubling the KV/compute buffers inside a foreground service.
//   2. Reason one is unreachable. whisper_vad_init_context hard-forces use_gpu = false at
//      whisper.cpp:4671-4674 ("GPU VAD is forced disabled until the performance is improved"),
//      REGARDLESS of what the params ask for; belt and braces,
//      whisper_vad_default_context_params() already defaults it false. A VAD context cannot
//      touch OpenCL, so it cannot race the sentinel.
//   3. Reason two is unreachable. The VAD context builds its own CPU backend with its own work
//      buffers, sched and galloc - no mutable state shared with any whisper_context - and 2.6 MB
//      is not an OOM risk against a 190-574 MB model tier the user already loaded.
//   4. Taking the gate would be actively harmful. It is a FAIR ReentrantLock
//      (NativeComputeGate.kt:34), so each 32 ms frame would queue behind whatever holds it: a
//      4-15 s whisper_full, or one of BatchTranscriber's ~54 s per-chunk holds. That is precisely
//      the stall 3.7 exists to remove, recreated inside the mechanism meant to remove it.
// Nothing else may follow the probe through this hole: NativeComputeGate still wraps every
// whisper_full, load and release.
//
// Thread-safety here is g_probe_mutex, which guards ONLY these four functions and the probe
// context. It is never taken while g_vad_mutex is held and never the reverse, so the two VAD
// paths cannot deadlock against each other.
// ---------------------------------------------------------------------------------------------

static std::mutex             g_probe_mutex;
static whisper_vad_context  * g_probe_ctx = nullptr;

// Releases the probe context (~2.6 MB). Idempotent, and safe after a failed vadProbeInit.
// Blocks briefly if a frame is in flight, which is why the caller runs it on the capture-thread
// teardown path rather than on Main.
extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_vadProbeFree(
        JNIEnv * /*env*/, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_probe_mutex);
    if (g_probe_ctx != nullptr) {
        whisper_vad_free(g_probe_ctx);
        g_probe_ctx = nullptr;
        LOGI("vad probe: context freed");
    }
}

// ---------------------------------------------------------------------------------------------
// 3.6.0 Workstream D: incremental new-segment delivery. whisper_full invokes
// new_segment_callback ON THE CALLING THREAD (the engine's single native-executor thread) after
// each accepted segment; we forward the FULL running text of THIS call to Kotlin — the same
// "replace the whole preview" shape the cloud-live protocols use — as raw UTF-8 bytes
// (NewStringUTF aborts on 4-byte UTF-8; multilingual segments contain it). Global ref +
// CallVoidMethod; no native lock is held here (g_vad_mutex lives entirely inside we_vad_filter,
// which finished before whisper_full started).
// ---------------------------------------------------------------------------------------------

struct we_segment_cb_ctx {
    JavaVM   *vm       = nullptr;
    jobject   callback = nullptr;   // global ref to the Kotlin NewSegmentCallback
    jmethodID method   = nullptr;   // onRunningText([B)V
    bool      disabled = false;     // latched true after a throwing callback (see below)
};

static void we_on_new_segment(struct whisper_context * /*ctx*/, struct whisper_state *state,
                              int /*n_new*/, void *user_data) {
    auto *cb = static_cast<we_segment_cb_ctx *>(user_data);
    if (cb == nullptr || cb->callback == nullptr || state == nullptr) {
        return;
    }
    // Latched off by an earlier failure in THIS whisper_full — a callback that threw once will
    // almost certainly throw on every remaining segment, so stop paying for it (and stop
    // spamming ExceptionDescribe) instead of retrying silently per segment.
    if (cb->disabled) {
        return;
    }
    JNIEnv *env = nullptr;
    bool attachedHere = false;
    if (cb->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        // Defensive only: whisper_full calls this on the thread that entered transcribeRaw,
        // which is already attached — GetEnv succeeds there. This fallback keeps a future
        // whisper.cpp worker-thread callback from crashing instead of degrading.
        if (cb->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attachedHere = true;
    }
    std::string running;
    const int nSeg = whisper_full_n_segments_from_state(state);
    for (int i = 0; i < nSeg; ++i) {
        const char *seg = whisper_full_get_segment_text_from_state(state, i);
        if (seg != nullptr) {
            running += seg;
        }
    }
    const jsize len = static_cast<jsize>(running.size());
    jbyteArray arr = env->NewByteArray(len);
    if (arr != nullptr) {
        if (len > 0) {
            env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(running.data()));
        }
        env->CallVoidMethod(cb->callback, cb->method, arr);
        env->DeleteLocalRef(arr);
    }
    // UNCONDITIONAL — must sit OUTSIDE the block above. A failed NewByteArray (OOM) leaves an
    // OutOfMemoryError pending and SKIPS that block, so a check nested inside it would never run:
    // the error would still be in flight on the NEXT segment's JNI calls, which CheckJNI turns
    // into a process abort. This one check covers both that path and a throwing Kotlin callback.
    // A preview must never abort the transcribe.
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();   // survives R8 (JNI, not android.util.Log) — the only
                                    // diagnostic a broken callback ever gets
        env->ExceptionClear();
        // Latch streaming off for the remainder of this whisper_full. Deliberately NOT done by
        // nulling cb->callback: transcribeRaw's DeleteGlobalRef gates on exactly that field, so
        // clearing it here would leak the global ref.
        cb->disabled = true;
    }
    if (attachedHere) {
        cb->vm->DetachCurrentThread();
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_transcribeRaw(
        JNIEnv *env, jobject /* this */,
        jlong ctxPtr, jfloatArray samples, jstring lang, jboolean translate,
        jstring vadModelPath, jobject segmentCallback) {
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
        // Floor history: raised 256 -> 768 because STOCK whisper models lose accuracy under
        // aggressive audio_ctx reduction (positional-embedding mismatch; FUTO's ACFT models are
        // fine-tuned to tolerate it, ours are not) — user-visible as garbled short phrases.
        // Lowered 768 -> 512 (Task G4, 2026-08-20) after the on-device G3 sweep proved 512 holds
        // maxWer 0.000 on both 190 MB tiers while 384/256 garble short multi fragments
        // (wer 0.500 at the 1 s slice) — the cliff sits between 512 and 384. The value lives in
        // g_audio_ctx_floor so the bench harness can A-B floors — see setAudioCtxFloor above.
        const int floorFrames = g_audio_ctx_floor.load(std::memory_order_relaxed);
        if (neededFrames < floorFrames) neededFrames = floorFrames;
        if (neededFrames > 1500) neededFrames = 1500;
        params.audio_ctx = neededFrames;
    }

    // D (3.6.0): arm the new-segment trampoline. cbCtx is stack-local — whisper_full is
    // synchronous and no callback can fire after it returns, which is also why the global ref
    // is deleted immediately after, on every path.
    // LANDMINE: whisper.cpp gates this callback on !dtw_token_timestamps (whisper.cpp:7678,
    // :7726), so enabling DTW token timestamps in the context params SILENTLY turns streaming
    // off — and the DTW branch's own callback loop is buggy upstream. We never set it; keep it
    // that way, or partial previews die with no error anywhere.
    we_segment_cb_ctx cbCtx;
    if (segmentCallback != nullptr) {
        env->GetJavaVM(&cbCtx.vm);
        jclass cbClass = env->GetObjectClass(segmentCallback);
        cbCtx.method = env->GetMethodID(cbClass, "onRunningText", "([B)V");
        env->DeleteLocalRef(cbClass);
        if (cbCtx.vm != nullptr && cbCtx.method != nullptr) {
            cbCtx.callback = env->NewGlobalRef(segmentCallback);
            params.new_segment_callback           = we_on_new_segment;
            params.new_segment_callback_user_data = &cbCtx;
        } else {
            LOGE("new-segment callback wiring failed (no vm/method) — transcribing without preview");
            // GetMethodID raised (NoSuchMethodError) — degrade, never abort. Leaving it pending
            // makes the NewByteArray/SetByteArrayRegion below run with an exception in flight,
            // which CheckJNI turns into a process abort: the wrong failure direction for a
            // preview-only feature, and exactly the release-only R8 slip the proguard keeps
            // in Step 3 exist to prevent.
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    const int fullRc = whisper_full(ctx, params, pcm.data(), static_cast<int>(pcm.size()));
    if (cbCtx.callback != nullptr) {
        env->DeleteGlobalRef(cbCtx.callback);
    }
    if (fullRc != 0) {
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_detectedLanguage(
        JNIEnv *env, jobject /* this */, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) {
        return nullptr;
    }
    // state->lang_id from the LAST completed whisper_full on this ctx. It PERSISTS across calls —
    // even across sessions — so the Kotlin side must only trust it right after a transcribe that
    // demonstrably ran: the early-return paths in transcribeRaw above (VAD found zero speech,
    // the no-VAD energy gate, empty input) never touch whisper_full and would leave a stale id.
    // Threading: a plain field read, but it reads the ctx — call it only on the single thread
    // that runs transcribe for this ctx (LocalWhisperEngine's native executor).
    const int langId = whisper_full_lang_id(ctx);
    // NOT a "has it run yet" signal: state->lang_id starts at 0 (whisper.cpp:894, "english by
    // default"), so a ctx that never completed whisper_full reports "en", NOT nullptr — nullptr
    // means only an id outside g_lang, which auto-detect cannot produce. The caller's non-blank
    // transcribe guard is the only thing separating a real detection from that English default.
    const char *code = whisper_lang_str(langId);   // nullptr for an unknown id (whisper logs it)
    if (code == nullptr) {
        return nullptr;
    }
    // ISO 639-1 codes are plain ASCII — safe for NewStringUTF (no 4-byte UTF-8 here).
    return env->NewStringUTF(code);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_whisper_WhisperNative_free(
        JNIEnv *env, jobject /* this */, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}
