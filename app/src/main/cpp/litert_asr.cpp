// liblitertasr.so - the MediaTek APU tier's seam onto LiteRT 2.1.1's C API (P1b of
// docs/superpowers/plans/2026-09-24-mediatek-apu-tier-plan.md, design §2.3 / §2.5 / §2.6).
//
// The twin of libqnnasr.so, for the other vendor. The same JNI contract - probe, init, encode, the
// whole greedy decode loop in one call, the language detect, epoch, release, lastError, diag - and
// the same error convention ("" or "stage: detail"), so NpuWhisperBackend's policy body can sit on
// either engine. What is different is below the contract, and it is different on purpose:
//
//   * NOTHING HERE LINKS AGAINST LITERT. libLiteRt.so (2.1.1, the last release with a published
//     MediaTek dispatch) is dlopen()ed from the lib dir Kotlin passes and every entry point is
//     dlsym()ed into one table. A missing library or symbol is a readable "stage: detail", never a
//     load-time crash - the libqnnasr.so discipline, for the same reason: the owner has no adb.
//   * THE LOOP IS FLOAT. The pair is f32 at its boundary (the APU computes in fp16 inside), so the
//     logits are real log-odds: the mask's -infinity is the real one, the scale is 1.0 and never 0,
//     and a non-finite logit is a failed step rather than a code that happens to be low. qnn_asr.cpp's
//     loop is ufixed16 through and through and is NOT templated here (design §2.5): it runs on every
//     shipping Qualcomm family, and the two converge only after both are device-proven.
//   * EVERY BUFFER COMES FROM THE COMPILED MODELS' OWN REQUIREMENTS. The v2.1.1 MediaTek dispatch
//     accepts only AHardwareBuffer / DMA-BUF tensor buffers; a host-memory buffer is "Unsupported
//     buffer type". So nothing is allocated by hand: requirements -> managed buffer, shared buffers
//     from the JOIN of both sides' requirements, host access only under Lock/Unlock.
//   * THE DRIVER IS CHECKED BEFORE ANY MODEL IS OPENED (the owner's ruling, design §2.3), and the
//     chip is checked against the file's own LiteRtStamp before LiteRT sees the file.
//
// PROCESS STATE VERSUS SESSION STATE, and the line between them is the lifecycle rule of design
// §2.6. The Neuron adapter handles (the 5 s dlopen), the libLiteRt.so handle and the
// LiteRtEnvironment are created at most once per process and NEVER destroyed: there is no
// LiteRtDestroyEnvironment in the symbol table below and no dlclose anywhere in this file.
// nativeRelease frees the compiled models, their options, the models and every tensor buffer - the
// session - so a re-arm after a trim pays the restore (~1.3 s + 0.9 s), never the adapter's 5 s.
//
// This is the first execution of any of it: the device gate is the probe app's mode=litertasr.

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <limits>
#include <map>
#include <mutex>
#include <random>
#include <string>
#include <vector>

#include "band_scan.h"
#include "litert_stamp.h"

#if !defined(__has_include)
#error "compiler must support __has_include"
#endif

#if !__has_include("litert/c/litert_compiled_model.h")
#error "LiteRT 2.1.1 headers not found. They are vendored at app/src/main/cpp/third_party/litert-2.1.1."
#endif

// Signatures and enums only. Every function below is reached through the dlsym table; a direct call
// to any of these declarations would be an undefined symbol, and the NDK links shared libraries
// with --no-undefined, so it cannot slip through as a silent static dependency on libLiteRt.so.
#include "litert/c/litert_common.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_environment_options.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"
#include "litert/c/options/litert_mediatek_options.h"

// THE HOUSE TAG, the one qnn_asr.cpp settled on after 4.0 put 37 lines where the owner's
// `adb logcat -s WE-DIAG` capture could not see them. Same tag, same four macros, same split: LOGI
// and friends are the shipped lines, LOGDIAG is the g.diag-gated instrumentation.
#define TAG "WE-DIAG"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGDIAG(...) __android_log_print(ANDROID_LOG_INFO, "WE-DIAG", __VA_ARGS__)

namespace {

using Clock = std::chrono::steady_clock;

double msSince(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

/// dlerror() clears what it reports; read it exactly once (qnn_asr.cpp's dlErr, for its reason).
std::string dlErr() {
    const char *e = dlerror();
    return e ? std::string(e) : std::string("no dlerror detail");
}

// ================================================================ the runtime portal
//
// EVERY LiteRT ENTRY POINT THIS FILE CALLS, in one list, resolved once by loadRuntimeLocked(). The
// member types are `decltype(&LiteRtX)` of the vendored 2.1.1 declarations, so a signature in this
// table cannot disagree with the header it was compiled against. Each name was confirmed exported by
// libLiteRt.so 2.1.1 (sha256 6ddc1b3d...) at VERS_1.0 before it went on this list.
//
// NOT on it, deliberately: LiteRtDestroyEnvironment. The environment is process state (design
// §2.6); a symbol that is never resolved is a call that cannot be written by accident.
#define LITERT_SYMBOLS(X)                                   \
    X(LiteRtGetStatusString)                                \
    X(LiteRtCreateEnvironment)                              \
    X(LiteRtCreateModelFromFile)                            \
    X(LiteRtDestroyModel)                                   \
    X(LiteRtGetNumModelSignatures)                          \
    X(LiteRtGetModelSignature)                              \
    X(LiteRtGetSignatureKey)                                \
    X(LiteRtGetNumSignatureInputs)                          \
    X(LiteRtGetNumSignatureOutputs)                         \
    X(LiteRtGetSignatureInputName)                          \
    X(LiteRtGetSignatureOutputName)                         \
    X(LiteRtGetSignatureInputTensorByIndex)                 \
    X(LiteRtGetSignatureOutputTensorByIndex)                \
    X(LiteRtGetRankedTensorType)                            \
    X(LiteRtCreateOptions)                                  \
    X(LiteRtDestroyOptions)                                 \
    X(LiteRtSetOptionsHardwareAccelerators)                 \
    X(LiteRtAddOpaqueOptions)                               \
    X(LiteRtDestroyOpaqueOptions)                           \
    X(LiteRtMediatekOptionsCreate)                          \
    X(LiteRtMediatekOptionsGet)                             \
    X(LiteRtMediatekOptionsSetPerformanceMode)              \
    X(LiteRtCreateCompiledModel)                            \
    X(LiteRtDestroyCompiledModel)                           \
    X(LiteRtRunCompiledModel)                               \
    X(LiteRtCompiledModelIsFullyAccelerated)                \
    X(LiteRtGetCompiledModelInputBufferRequirements)        \
    X(LiteRtGetCompiledModelOutputBufferRequirements)       \
    X(LiteRtJoinTensorBufferRequirements)                   \
    X(LiteRtDestroyTensorBufferRequirements)                \
    X(LiteRtGetTensorBufferRequirementsBufferSize)          \
    X(LiteRtGetTensorBufferRequirementsStrides)             \
    X(LiteRtGetNumTensorBufferRequirementsSupportedBufferTypes) \
    X(LiteRtGetTensorBufferRequirementsSupportedTensorBufferType) \
    X(LiteRtCreateManagedTensorBufferFromRequirements)      \
    X(LiteRtDestroyTensorBuffer)                            \
    X(LiteRtGetTensorBufferType)                            \
    X(LiteRtGetTensorBufferPackedSize)                      \
    X(LiteRtLockTensorBuffer)                               \
    X(LiteRtUnlockTensorBuffer)

struct LiteRtApi {
#define LITERT_MEMBER(name) decltype(&::name) name = nullptr;
    LITERT_SYMBOLS(LITERT_MEMBER)
#undef LITERT_MEMBER
};

/// PROCESS STATE: the runtime library and the environment. Filled once, never reset.
struct Runtime {
    void *lib = nullptr;
    LiteRtApi api{};
    bool ready = false;
    std::string loadedFrom;
    /// Created at most ONCE per process, by the first nativeInit, and NEVER destroyed (design §2.6).
    /// Not created by the probe: the environment scans its dispatch directory, which P2's prepare
    /// stage fills, and a probe that ran first would have bound the process to an empty directory for
    /// good - the one mistake a never-destroyed object cannot be recovered from.
    LiteRtEnvironment env = nullptr;
    std::string envDispatchDir;
};

Runtime rt;

/// A LiteRtStatus as text: the runtime's own name for it plus the number.
std::string st(LiteRtStatus s) {
    const char *name = rt.api.LiteRtGetStatusString ? rt.api.LiteRtGetStatusString(s) : nullptr;
    return std::string(name ? name : "status") + " (" + std::to_string(static_cast<int>(s)) + ")";
}

/// dlopen libLiteRt.so by absolute path, then by SONAME - the order dlopenQnn uses and for its
/// reason: under the shipping extractNativeLibs=false packaging, nativeLibraryDir holds no real
/// files and only the SONAME form resolves (through the app's own linker namespace, straight out of
/// the APK). Then dlsym every entry point on the list. Idempotent.
std::string loadRuntimeLocked(const std::string &libDir) {
    if (rt.ready) return "";
    if (!rt.lib) {
        const std::string path = libDir + "/libLiteRt.so";
        rt.lib = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (rt.lib) {
            rt.loadedFrom = path;
        } else {
            const std::string pathErr = dlErr();
            rt.lib = dlopen("libLiteRt.so", RTLD_NOW | RTLD_LOCAL);
            if (!rt.lib) {
                return "runtime: dlopen(libLiteRt.so): path=[" + pathErr + "] soname=[" + dlErr() + "]";
            }
            rt.loadedFrom = "libLiteRt.so (by SONAME; " + path + " did not load)";
        }
    }
    LiteRtApi api{};
#define LITERT_RESOLVE(name)                                                             \
    api.name = reinterpret_cast<decltype(&::name)>(dlsym(rt.lib, #name));                \
    if (!api.name) return std::string("runtime: dlsym(" #name "): ") + dlErr();
    LITERT_SYMBOLS(LITERT_RESOLVE)
#undef LITERT_RESOLVE
    rt.api = api;
    rt.ready = true;
    LOGI("runtime: libLiteRt.so loaded from %s; every entry point resolved", rt.loadedFrom.c_str());
    return "";
}

/// The environment, once, with the dispatch directory P2's prepare stage fills
/// (`filesDir/litert_dispatch/`, holding exactly libLiteRtDispatch_MediaTek.so). A second caller
/// naming a DIFFERENT directory is refused rather than obeyed: the environment cannot be rebuilt, so
/// the only honest answer is that this process is bound to the first one.
std::string ensureEnvironmentLocked(const std::string &dispatchDir) {
    if (rt.env) {
        if (rt.envDispatchDir != dispatchDir) {
            return "environment: already created with dispatch dir " + rt.envDispatchDir +
                   " and it is never destroyed; this process cannot switch to " + dispatchDir;
        }
        return "";
    }
    LiteRtEnvOption opt{};
    opt.tag = kLiteRtEnvOptionTagDispatchLibraryDir;
    opt.value.type = kLiteRtAnyTypeString;
    // The string must outlive the environment, and the environment outlives everything: it points
    // into rt.envDispatchDir, which is process state and is written exactly once, here.
    rt.envDispatchDir = dispatchDir;
    opt.value.str_value = rt.envDispatchDir.c_str();
    const auto t0 = Clock::now();
    LiteRtEnvironment env = nullptr;
    const LiteRtStatus s = rt.api.LiteRtCreateEnvironment(1, &opt, &env);
    if (s != kLiteRtStatusOk || !env) {
        rt.envDispatchDir.clear();
        return "environment: LiteRtCreateEnvironment " + st(s);
    }
    rt.env = env;
    LOGI("environment: created in %.0f ms, dispatch dir %s (process lifetime; never destroyed)",
         msSince(t0), rt.envDispatchDir.c_str());
    return "";
}

// ================================================================ the driver check (design §2.3)
//
// The Neuron runtime's version API, declared here from MediaTek's own header rather than included:
// NeuronAdapter.h (NeuroPilot v8_0_10 usdk, lines 876-888 and 2191-2218) is under MediaTek's SDK
// licence and is not vendored. Three uint8_t, and an int return where NEURON_NO_ERROR is 0.
struct NeuronRuntimeVersion {
    uint8_t major;
    uint8_t minor;
    uint8_t patch;
};
struct NeuronDevice;
using NeuronGetVersionFn = int (*)(NeuronRuntimeVersion *);
using NeuronGetDeviceCountFn = int (*)(uint32_t *);
using NeuronGetDeviceFn = int (*)(uint32_t, NeuronDevice **);
using NeuronDeviceGetNameFn = int (*)(const NeuronDevice *, const char **);
constexpr int kNeuronNoError = 0;

/// LiteRT v2.1.1's adapter candidates, IN ITS ORDER (neuron_adapter_api.cc:109-116), and the loop
/// there has no `break`: THE LAST CANDIDATE THAT LOADS IS THE ONE LITERT USES. The fourth candidate,
/// `<dispatch dir>/libneuron_adapter.so`, is appended at the walk because it depends on the
/// directory. The one the tier was measured on - and the only one the product's manifest declares,
/// so under targetSdk >= 31 the only one the app's namespace can open at all - is the first.
///
/// One difference from LiteRT, stated: v2.1.1 tries `.9` only when libneuron_sys_util.mtk.so's magic
/// number says the ROM is v9-class; this walk tries it unconditionally. The effect can only be a
/// REFUSAL LiteRT would not have made (a loadable `.9` wins here and is refused below), never a pass
/// it would not have - and the product does not declare `.9`, so it cannot load in the first place.
constexpr const char *kAdapterCandidates[] = {
    "libneuronusdk_adapter.mtk.so",
    "libneuronusdk_adapter.9.mtk.so",
    "libneuron_adapter_mgvi.so",
};
constexpr const char *kDispatchDirAdapter = "libneuron_adapter.so";

/// The adapter the tier was measured on (Tab S10+, Neuron 8.2.26). Any other winner is refused.
constexpr const char *kMeasuredAdapter = "libneuronusdk_adapter.mtk.so";

/// PROCESS STATE: the walk's result. Walked once; every handle that loaded is kept for the life of
/// the process (RTLD_NODELETE, and never closed), so LiteRT's own dlopen of the same name later only
/// raises a refcount instead of paying the adapter's 5 s constructor again (P0, run t7).
struct AdapterWalk {
    bool walked = false;
    std::string dispatchDir;
    std::vector<void *> handles;
    void *winner = nullptr;
    std::string winnerName;
    std::string candidates;
    bool haveVersion = false;
    NeuronRuntimeVersion version{};
    uint32_t deviceCount = 0;
    std::string deviceNames;
    double walkMs = 0.0;
    double queryMs = 0.0;
};

AdapterWalk adapter;

/// Walks the candidates exactly once per process. THIS IS THE 5 s WAIT: dlopen of
/// libneuronusdk_adapter.mtk.so runs MediaTek's own constructor, which waits on a binder service
/// this ROM never registers before falling back to the apuware path (P0, run t7). Bionic holds its
/// loader lock for all of it, so this belongs on a background thread and nowhere else (the design's
/// Application.onCreate prewarm), and nothing else may dlopen meanwhile.
void walkAdapterCandidatesLocked(const std::string &dispatchDir) {
    if (adapter.walked) return;
    adapter.walked = true;
    adapter.dispatchDir = dispatchDir;
    const auto t0 = Clock::now();
    std::vector<std::string> names(std::begin(kAdapterCandidates), std::end(kAdapterCandidates));
    names.push_back(dispatchDir + "/" + kDispatchDirAdapter);
    for (const std::string &name : names) {
        void *h = dlopen(name.c_str(), RTLD_NOW | RTLD_NODELETE);
        if (!adapter.candidates.empty()) adapter.candidates += ",";
        if (h) {
            adapter.handles.push_back(h);   // retained for the life of the process
            adapter.winner = h;             // the LAST one that loads, as LiteRT's loop decides
            adapter.winnerName = name;
            adapter.candidates += name + "=loaded";
        } else {
            const std::string why = dlErr();
            adapter.candidates += name + "=no";
            LOGI("apu: candidate %s did not load (%s)", name.c_str(), why.c_str());
        }
    }
    adapter.walkMs = msSince(t0);
    if (!adapter.winner) return;

    const auto q0 = Clock::now();
    auto getVersion = reinterpret_cast<NeuronGetVersionFn>(dlsym(adapter.winner, "Neuron_getVersion"));
    if (getVersion) {
        NeuronRuntimeVersion v{};
        if (getVersion(&v) == kNeuronNoError) {
            adapter.version = v;
            adapter.haveVersion = true;
        }
    }
    // The device list is for the diag line only; nothing decides on it, so a miss is "?".
    auto getCount = reinterpret_cast<NeuronGetDeviceCountFn>(dlsym(adapter.winner, "Neuron_getDeviceCount"));
    auto getDevice = reinterpret_cast<NeuronGetDeviceFn>(dlsym(adapter.winner, "Neuron_getDevice"));
    auto getName = reinterpret_cast<NeuronDeviceGetNameFn>(dlsym(adapter.winner, "NeuronDevice_getName"));
    uint32_t n = 0;
    if (getCount && getCount(&n) == kNeuronNoError) {
        adapter.deviceCount = n;
        for (uint32_t i = 0; i < n && getDevice && getName; ++i) {
            NeuronDevice *d = nullptr;
            const char *nm = nullptr;
            if (getDevice(i, &d) == kNeuronNoError && d && getName(d, &nm) == kNeuronNoError && nm) {
                if (!adapter.deviceNames.empty()) adapter.deviceNames += "+";
                adapter.deviceNames += nm;
            }
        }
    }
    if (adapter.deviceNames.empty()) adapter.deviceNames = "?";
    // Timed apart from the dlopens: the walk is MediaTek's constructor, this is the adapter's own
    // version and device queries, and the device gate needs to know which of the two costs what.
    adapter.queryMs = msSince(q0);
}

/// The basename of a candidate: the fourth is a path, and the refusal names the library, not the
/// app's private directory.
std::string baseName(const std::string &p) {
    const size_t slash = p.rfind('/');
    return slash == std::string::npos ? p : p.substr(slash + 1);
}

/// THE VERDICT (design §2.3, rule 2), pure: "" is a pass, anything else is the refusal's reason.
///   no candidate loaded                     -> adapter-missing
///   the winner is not the measured adapter  -> adapter-<name>
///   the winner exposes no readable version  -> driver-version-unreadable
///   major != the family's neuronMajor       -> driver-major-<got>-want-<want>
std::string verdictFor(const std::string &winnerName, bool haveVersion, int gotMajor, int wantMajor) {
    if (winnerName.empty()) return "adapter-missing";
    const std::string base = baseName(winnerName);
    if (base != kMeasuredAdapter) return "adapter-" + base;
    if (!haveVersion) return "driver-version-unreadable";
    if (gotMajor != wantMajor) {
        return "driver-major-" + std::to_string(gotMajor) + "-want-" + std::to_string(wantMajor);
    }
    return "";
}

/// Walks (once) and judges (every call): the walk's answer cannot change within a process, the
/// wanted major is the caller's. The `apu:` line is logged on every call, pass or refuse - it is the
/// line the ship gate reads, and a verdict without it is a verdict nobody can check.
std::string probeLocked(const std::string &dispatchDir, int wantMajor) {
    walkAdapterCandidatesLocked(dispatchDir);
    const std::string reason = verdictFor(adapter.winnerName, adapter.haveVersion,
                                          adapter.haveVersion ? adapter.version.major : -1, wantMajor);
    char ver[32] = "?";
    if (adapter.haveVersion) {
        snprintf(ver, sizeof(ver), "%u.%u.%u", static_cast<unsigned>(adapter.version.major),
                 static_cast<unsigned>(adapter.version.minor), static_cast<unsigned>(adapter.version.patch));
    }
    const std::string driver = adapter.winner ? baseName(adapter.winnerName) : std::string("none");
    if (reason.empty()) {
        LOGI("apu: driver=%s %s want=%d devices=%u device=%s walk=%.0fms query=%.0fms pass", driver.c_str(),
             ver, wantMajor, adapter.deviceCount, adapter.deviceNames.c_str(), adapter.walkMs, adapter.queryMs);
    } else {
        LOGE("apu: driver=%s %s want=%d devices=%u device=%s walk=%.0fms query=%.0fms refuse(%s) "
             "candidates=[%s]", driver.c_str(), ver, wantMajor, adapter.deviceCount,
             adapter.deviceNames.c_str(), adapter.walkMs, adapter.queryMs, reason.c_str(),
             adapter.candidates.c_str());
    }
    return reason;
}

// ================================================================ the chip check (design §2.3, rule 4)
//
// The parser is litert_stamp.h, header-only and Android-free so tools/mtk-apu/litert_stamp_check.cpp
// can run the SAME code over the real compiled pair on the MS-02. This half only maps the file.
constexpr const char *kStampVendor = "MediaTek";

/// Reads [path]'s LiteRtStamp into [vendor] and [soc]. "" on success, else the reason.
std::string readLiteRtStamp(const std::string &path, std::string *vendor, std::string *soc) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return "open " + path + ": " + strerror(errno);
    struct stat sb {};
    if (fstat(fd, &sb) != 0 || sb.st_size < 16) {
        close(fd);
        return "fstat " + path + ": not a model (" + std::to_string(static_cast<long long>(sb.st_size)) + " B)";
    }
    // Mapped, not read: the file is 0.6-1.3 GB and the stamp is a few hundred bytes near its head.
    // Only the pages the walk touches are faulted in, and the mapping is gone before LiteRT opens
    // the same file (which maps it again, sharing the page cache).
    void *m = mmap(nullptr, static_cast<size_t>(sb.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (m == MAP_FAILED) return "mmap " + path + ": " + strerror(errno);
    const std::string err = litert_stamp::parseLiteRtStamp(static_cast<const uint8_t *>(m),
                                                           static_cast<size_t>(sb.st_size), path, vendor, soc);
    munmap(m, static_cast<size_t>(sb.st_size));
    return err;
}

// ================================================================ the session

/// The three factors that are not passed in, qnn_asr.cpp's and for its reason: identical on every
/// published whisper asset, and a number that cannot vary is a number a caller can get wrong.
/// NpuModelSpec carries them as fields; LiteRtNativeContractTest holds these literals equal to them.
constexpr uint32_t kHeadDim = 64;
constexpr uint32_t kAudioCtx = 1500;
constexpr uint32_t kMelFrames = 3000;

constexpr const char *kEncodeSignature = "encode";
constexpr const char *kDecodeSignature = "decode";
constexpr const char *kInputFeatures = "input_features";
constexpr const char *kInputIds = "input_ids";
constexpr const char *kPositionIds = "position_ids";
constexpr const char *kAttentionMask = "attention_mask";

/// The additive mask's two values. -1e4, not -inf: the APU computes in fp16, where -1e4 is
/// representable and a softmax over it underflows to exactly 0, and it is the value the pair was
/// exported with (export_decoder_mtk.py) and every tablet run used.
constexpr float kMaskAttend = 0.0f;
constexpr float kMaskBlocked = -1e4f;

/// THE FLOAT SEAM'S -INFINITY, and it is the real one. qnn_asr.cpp's kLogitFloor is code 0 because
/// ufixed16 has no -inf; here the mask writes -inf, the argmax never picks it, the log-sum-exp skips
/// it and the sampler gives it no mass. A raw logit is never -inf - non-finite raw logits are a failed
/// step (checkFiniteLocked) - so an entry at this floor is always one the mask wrote.
constexpr float kLogitFloor = -std::numeric_limits<float>::infinity();

/// THE LOGITS' SCALE: 1.0, and never 0. qnn_asr.cpp reads a per-tensor scale off the ufixed16 logits
/// and uses 0 to mean "no probability gate this segment". Float logits ARE log-odds, so the scale is
/// the identity - and a 0 here would silently switch off p(nospeech), avg_logprob and the ladder,
/// i.e. the 4.3.2 silence fix, on every segment this tier decodes.
constexpr float kLogitScale = 1.0f;
static_assert(kLogitScale > 0.0f, "a zero scale switches the probability gates off");

// The six OUT slots and four terminator codes - qnn_asr.cpp's literals, mirroring NpuDecodeStats.kt.
constexpr int kStatNoSpeechProb = 0;
constexpr int kStatAvgLogprob = 1;
constexpr int kStatEntropy = 2;
constexpr int kStatRung = 3;
constexpr int kStatTerminator = 4;
constexpr int kStatSteps = 5;
constexpr int kStatSize = 6;
constexpr float kTermEot = 0.0f;
constexpr float kTermBudget = 1.0f;
constexpr float kTermCap = 2.0f;
constexpr float kTermCut = 3.0f;
constexpr int32_t kEntropyWindow = 32;
constexpr float kStatUnreadable = -1.0f;

// Token ids native must know for itself - the same three fixed ids and two derived counts as
// qnn_asr.cpp (see its comment on why the band is derived from vocab and not a constant).
constexpr int32_t kEotToken = 50257;
constexpr int32_t kSotToken = 50258;
constexpr int32_t kLangTokenBase = 50259;
constexpr int32_t kTimestampSlots = 1501;
constexpr int32_t kSpecialsAboveLangBand = 6;

/// One compiled model and everything this session owns for it.
struct Slot {
    const char *label = "";
    LiteRtModel model = nullptr;
    LiteRtOptions options = nullptr;
    LiteRtCompiledModel compiled = nullptr;
    LiteRtParamIndex sig = 0;
    std::vector<std::string> inNames;
    std::vector<std::string> outNames;
    std::vector<LiteRtRankedTensorType> inTypes;
    std::vector<LiteRtRankedTensorType> outTypes;
    /// Inputs are bound BY NAME (qnn_asr.cpp's lesson 5), and here it is not a style: the f32 export
    /// lists the decoder's 19 inputs alphabetically while the compiled file lists them in export order
    /// (both read on the MS-02). A positional input table would be right for one file and wrong for
    /// the other, silently.
    std::map<std::string, size_t> inIndex;
};

struct AsrState {
    std::mutex mu;
    Slot enc;
    Slot dec;
    bool initialised = false;
    /// The arming epoch, qnn_asr.cpp's (4.1 L1) - 0 is "no session", nextEpoch never rewinds.
    uint64_t epoch = 0;

    // ---- the census, from nativeInit's five scalars
    uint32_t melBins = 0;
    uint32_t layers = 0;
    uint32_t heads = 0;
    uint32_t vocab = 0;
    uint32_t maskLen = 0;
    int32_t langTokenFirst = 0;
    int32_t langTokenLast = 0;
    int performanceMode = -1;

    // ---- the buffers. EVERY one is a managed buffer created from requirements and listed in
    // `owned`, which is the only thing releaseLocked walks; `joined` holds the requirement joins this
    // session created (the models' own requirements belong to the compiled models).
    std::vector<LiteRtTensorBuffer> owned;
    std::vector<LiteRtTensorBufferRequirements> joined;
    LiteRtTensorBuffer mel = nullptr;
    size_t melBytes = 0;
    LiteRtTensorBuffer inputIds = nullptr;
    LiteRtTensorBuffer positionIds = nullptr;
    LiteRtTensorBuffer mask = nullptr;
    LiteRtTensorBuffer logitsBuf = nullptr;
    /// The eight cross-KV buffers, k0,v0,k1,v1,... - the encoder's outputs AND the decoder's inputs,
    /// one buffer each, created from the join of both sides' requirements. Nothing is copied between
    /// the passes.
    std::vector<LiteRtTensorBuffer> cross;
    /// THE PING-PONG: two sets of the 2*layers self-KV buffers (k0,v0,k1,v1,...), each created from
    /// the join of its `_in` input and `_out` output requirements so either set can play either role.
    /// Each step binds one set as the inputs and the other as the outputs, then swaps - re-binding
    /// the run arrays' handles, moving not one byte (qnn_asr.cpp's bindSelfKvLocked, mirrored).
    std::vector<LiteRtTensorBuffer> selfKv[2];
    std::vector<size_t> selfKvBytes;
    int selfInSet = 0;

    // ---- the run arrays, in each signature's own order: what LiteRtRunCompiledModel receives.
    std::vector<LiteRtTensorBuffer> encIn;
    std::vector<LiteRtTensorBuffer> encOut;
    std::vector<LiteRtTensorBuffer> decIn;
    std::vector<LiteRtTensorBuffer> decOut;
    size_t decInputIdsIdx = 0;
    size_t decPositionIdsIdx = 0;
    size_t decMaskIdx = 0;
    std::vector<size_t> selfInIdx;    // into decIn, k0,v0,k1,v1,...
    std::vector<size_t> selfOutIdx;   // into decOut, the same order

    /// The host copy of the step's logits, read under lock right after the run.
    std::vector<float> logits;
    std::vector<float> maskHost;

    /// The per-segment timing the decode line reports: run is LiteRtRunCompiledModel, io is the
    /// three input writes plus the logits read.
    double runMs = 0.0;
    double ioMs = 0.0;

    /// The encode-validity flag, qnn_asr.cpp's: set only by a successful encode, cleared on entry to
    /// every encode, by release and by a fresh init, and NOT consumed by a decode or a detect.
    bool encoded = false;
    bool diag = false;
    std::string lastError;
};

AsrState g;

/// PROCESS state, beside `g` for qnn_asr.cpp's reason: releaseLocked zeroes the session, and a
/// counter inside it would be one plausible line away from being reused.
uint64_t nextEpoch = 1;

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

/// Records [err] as the last error and hands it back, so the return value and nativeLastError()
/// cannot disagree.
std::string failure(const std::string &err) {
    g.lastError = err;
    LOGE("%s", err.c_str());
    return err;
}

// ---------------------------------------------------------------- the census (pure)

/// What one signature tensor must be: a name (and, for outputs, the positional alias litert-torch
/// gives them), an element type and exact dims.
struct TensorExpect {
    std::string name;
    std::string alias;
    LiteRtElementType type;
    std::vector<int32_t> dims;
};

struct PairCensus {
    std::vector<TensorExpect> encIn;
    std::vector<TensorExpect> encOut;
    std::vector<TensorExpect> decIn;
    std::vector<TensorExpect> decOut;
    int32_t langTokenFirst = 0;
    int32_t langTokenLast = 0;
};

/// The pair's whole IO, derived from nativeInit's five scalars, BEFORE anything is released or
/// opened (qnn_asr.cpp's deriveCensus discipline: a refusal below the release would cost the caller
/// a working session on its way to reporting a typo). Qualcomm's HfWhisper IO, which is what the
/// MediaTek pair was exported in so that NpuModelSpec describes both vendors' assets unchanged:
///
///   encode: input_features f32[1, melBins, 3000]
///        -> output_{2i}   = k_cache_cross_i f32[heads, 1, 64, 1500]
///           output_{2i+1} = v_cache_cross_i f32[heads, 1, 1500, 64]           (i = 0..layers-1)
///   decode: input_ids i32[1,1], attention_mask f32[1,1,1,maxPositions],
///           k_cache_self_i_in f32[heads,1,64,maxPositions-1], v_cache_self_i_in f32[heads,1,maxPositions-1,64],
///           k_cache_cross_i, v_cache_cross_i (as above), position_ids i32[1]    (inputs bound BY NAME)
///        -> output_0 = logits f32[1, vocab, 1, 1], output_{1+2i} = k_cache_self_i_out,
///           output_{2+2i} = v_cache_self_i_out                                 (outputs checked BY POSITION)
std::string derivePairCensus(int32_t melBins, int32_t decLayers, int32_t heads, int32_t vocab,
                             int32_t maxPositions, PairCensus &out) {
    if (melBins != 80 && melBins != 128) {
        return "spec: melBins is " + std::to_string(melBins) + "; every published whisper asset is 80 or 128";
    }
    if (decLayers < 1 || decLayers > 64) return "spec: decLayers is " + std::to_string(decLayers) + "; expected 1..64";
    if (heads < 1 || heads > 64) return "spec: heads is " + std::to_string(heads) + "; expected 1..64";
    if (vocab < 1 || vocab > 65535) return "spec: vocab is " + std::to_string(vocab) + "; expected 1..65535";
    if (maxPositions < 2 || maxPositions > 1024) {
        return "spec: maxPositions is " + std::to_string(maxPositions) + "; expected 2..1024";
    }
    const int32_t langCount = vocab - kTimestampSlots - kSpecialsAboveLangBand - kLangTokenBase;
    if (langCount < 1) {
        return "spec: vocab " + std::to_string(vocab) + " leaves no language band above " +
               std::to_string(kLangTokenBase);
    }
    const int32_t h = heads, hd = static_cast<int32_t>(kHeadDim), ac = static_cast<int32_t>(kAudioCtx);
    const int32_t depth = maxPositions - 1;
    out.langTokenFirst = kLangTokenBase;
    out.langTokenLast = kLangTokenBase + langCount - 1;
    out.encIn = {{kInputFeatures, "", kLiteRtElementTypeFloat32, {1, melBins, static_cast<int32_t>(kMelFrames)}}};
    out.encOut.clear();
    out.decIn.clear();
    out.decOut.clear();
    out.decIn.push_back({kInputIds, "", kLiteRtElementTypeInt32, {1, 1}});
    out.decIn.push_back({kAttentionMask, "", kLiteRtElementTypeFloat32, {1, 1, 1, maxPositions}});
    out.decOut.push_back({"logits", "output_0", kLiteRtElementTypeFloat32, {1, vocab, 1, 1}});
    for (int32_t i = 0; i < decLayers; ++i) {
        const std::string n = std::to_string(i);
        out.encOut.push_back({"k_cache_cross_" + n, "output_" + std::to_string(2 * i), kLiteRtElementTypeFloat32,
                              {h, 1, hd, ac}});
        out.encOut.push_back({"v_cache_cross_" + n, "output_" + std::to_string(2 * i + 1),
                              kLiteRtElementTypeFloat32, {h, 1, ac, hd}});
        out.decIn.push_back({"k_cache_self_" + n + "_in", "", kLiteRtElementTypeFloat32, {h, 1, hd, depth}});
        out.decIn.push_back({"v_cache_self_" + n + "_in", "", kLiteRtElementTypeFloat32, {h, 1, depth, hd}});
        out.decIn.push_back({"k_cache_cross_" + n, "", kLiteRtElementTypeFloat32, {h, 1, hd, ac}});
        out.decIn.push_back({"v_cache_cross_" + n, "", kLiteRtElementTypeFloat32, {h, 1, ac, hd}});
        out.decOut.push_back({"k_cache_self_" + n + "_out", "output_" + std::to_string(1 + 2 * i),
                              kLiteRtElementTypeFloat32, {h, 1, hd, depth}});
        out.decOut.push_back({"v_cache_self_" + n + "_out", "output_" + std::to_string(2 + 2 * i),
                              kLiteRtElementTypeFloat32, {h, 1, depth, hd}});
    }
    out.decIn.push_back({kPositionIds, "", kLiteRtElementTypeInt32, {1}});
    return "";
}

size_t elementBytes(LiteRtElementType t) {
    switch (t) {
        case kLiteRtElementTypeFloat32:
        case kLiteRtElementTypeInt32:
            return 4;
        default:
            return 0;
    }
}

/// Packed bytes of a ranked type: what a Lock hands back, whatever the hardware buffer's padding.
size_t tensorBytes(const LiteRtRankedTensorType &t) {
    size_t n = elementBytes(t.element_type);
    for (unsigned i = 0; i < t.layout.rank; ++i) {
        if (t.layout.dimensions[i] < 0) return 0;
        n *= static_cast<size_t>(t.layout.dimensions[i]);
    }
    return n;
}

std::string dimsStr(const LiteRtRankedTensorType &t) {
    std::string s = "[";
    for (unsigned i = 0; i < t.layout.rank; ++i) {
        if (i) s += ",";
        s += std::to_string(t.layout.dimensions[i]);
    }
    return s + "]";
}

std::string dimsStr(const std::vector<int32_t> &d) {
    std::string s = "[";
    for (size_t i = 0; i < d.size(); ++i) {
        if (i) s += ",";
        s += std::to_string(d[i]);
    }
    return s + "]";
}

bool matches(const LiteRtRankedTensorType &t, const TensorExpect &e) {
    if (t.element_type != e.type || t.layout.rank != e.dims.size()) return false;
    for (size_t i = 0; i < e.dims.size(); ++i) {
        if (t.layout.dimensions[i] != e.dims[i]) return false;
    }
    return true;
}

bool sameType(const LiteRtRankedTensorType &a, const LiteRtRankedTensorType &b) {
    if (a.element_type != b.element_type || a.layout.rank != b.layout.rank) return false;
    for (unsigned i = 0; i < a.layout.rank; ++i) {
        if (a.layout.dimensions[i] != b.layout.dimensions[i]) return false;
    }
    return true;
}

// ---------------------------------------------------------------- models, signatures, IO

/// Opens one model, finds its signature by key, and reads every IO tensor's name and ranked type.
std::string openModelLocked(Slot &slot, const std::string &path, const char *sigKey) {
    const auto t0 = Clock::now();
    LiteRtStatus s = rt.api.LiteRtCreateModelFromFile(path.c_str(), &slot.model);
    if (s != kLiteRtStatusOk || !slot.model) {
        slot.model = nullptr;
        return std::string(slot.label) + " LiteRtCreateModelFromFile(" + path + "): " + st(s);
    }
    LiteRtParamIndex nsig = 0;
    s = rt.api.LiteRtGetNumModelSignatures(slot.model, &nsig);
    if (s != kLiteRtStatusOk) return std::string(slot.label) + " signatures: " + st(s);
    LiteRtSignature sig = nullptr;
    bool found = false;
    std::string keys;
    for (LiteRtParamIndex i = 0; i < nsig; ++i) {
        LiteRtSignature cand = nullptr;
        const char *key = nullptr;
        if (rt.api.LiteRtGetModelSignature(slot.model, i, &cand) != kLiteRtStatusOk ||
            rt.api.LiteRtGetSignatureKey(cand, &key) != kLiteRtStatusOk || !key) {
            continue;
        }
        if (!keys.empty()) keys += ",";
        keys += key;
        if (strcmp(key, sigKey) == 0) {
            sig = cand;
            slot.sig = i;
            found = true;
        }
    }
    if (!found) {
        return std::string(slot.label) + ": no signature '" + sigKey + "' (the file has: " + keys + ")";
    }
    LiteRtParamIndex nin = 0, nout = 0;
    if (rt.api.LiteRtGetNumSignatureInputs(sig, &nin) != kLiteRtStatusOk ||
        rt.api.LiteRtGetNumSignatureOutputs(sig, &nout) != kLiteRtStatusOk) {
        return std::string(slot.label) + ": signature IO counts unreadable";
    }
    for (int pass = 0; pass < 2; ++pass) {
        const bool in = pass == 0;
        const LiteRtParamIndex count = in ? nin : nout;
        for (LiteRtParamIndex i = 0; i < count; ++i) {
            const char *nm = nullptr;
            LiteRtTensor tensor = nullptr;
            LiteRtRankedTensorType type{};
            s = in ? rt.api.LiteRtGetSignatureInputName(sig, i, &nm) : rt.api.LiteRtGetSignatureOutputName(sig, i, &nm);
            if (s != kLiteRtStatusOk || !nm) {
                return std::string(slot.label) + (in ? " input " : " output ") + std::to_string(i) + ": no name";
            }
            s = in ? rt.api.LiteRtGetSignatureInputTensorByIndex(sig, i, &tensor)
                   : rt.api.LiteRtGetSignatureOutputTensorByIndex(sig, i, &tensor);
            if (s != kLiteRtStatusOk || !tensor || rt.api.LiteRtGetRankedTensorType(tensor, &type) != kLiteRtStatusOk) {
                return std::string(slot.label) + " '" + nm + "': not a ranked tensor";
            }
            if (in) {
                if (!slot.inIndex.emplace(nm, slot.inNames.size()).second) {
                    return std::string(slot.label) + ": duplicate input name '" + nm + "'";
                }
                slot.inNames.emplace_back(nm);
                slot.inTypes.push_back(type);
            } else {
                slot.outNames.emplace_back(nm);
                slot.outTypes.push_back(type);
            }
        }
    }
    LOGI("%s: %s opened in %.0f ms, signature '%s' #%zu, %zu in / %zu out", slot.label, path.c_str(),
         msSince(t0), sigKey, static_cast<size_t>(slot.sig), slot.inNames.size(), slot.outNames.size());
    return "";
}

/// THE IO CENSUS: every input present BY NAME with the exact type and dims, and every output AT ITS
/// POSITION with its semantic name or litert-torch's positional alias (`output_i`) - and the exact
/// type and dims. The outputs are the half that needs the order: the encoder's eight cross-KV
/// tensors reach the decoder's inputs by export order alone, and a swapped k/v pair has different
/// dims ([.,.,64,1500] against [.,.,1500,64]) so it cannot pass. This is the runtime twin of the pack
/// IO gate (design §2.7) and the LiteRT twin of qnn_asr.cpp's graph census.
std::string checkIoLocked(const Slot &slot, const std::vector<TensorExpect> &ins,
                          const std::vector<TensorExpect> &outs) {
    if (slot.inNames.size() != ins.size()) {
        return std::string(slot.label) + " has " + std::to_string(slot.inNames.size()) + " inputs; the spec expects " +
               std::to_string(ins.size());
    }
    for (const TensorExpect &e : ins) {
        auto it = slot.inIndex.find(e.name);
        if (it == slot.inIndex.end()) {
            return std::string(slot.label) + " has no input named '" + e.name + "'";
        }
        const LiteRtRankedTensorType &t = slot.inTypes[it->second];
        if (!matches(t, e)) {
            return std::string(slot.label) + " input '" + e.name + "' is " + dimsStr(t) + " type " +
                   std::to_string(static_cast<int>(t.element_type)) + "; the spec expects " + dimsStr(e.dims) +
                   " type " + std::to_string(static_cast<int>(e.type));
        }
    }
    if (slot.outNames.size() != outs.size()) {
        return std::string(slot.label) + " has " + std::to_string(slot.outNames.size()) +
               " outputs; the spec expects " + std::to_string(outs.size());
    }
    for (size_t i = 0; i < outs.size(); ++i) {
        const std::string &got = slot.outNames[i];
        if (got != outs[i].name && got != outs[i].alias) {
            return std::string(slot.label) + " output " + std::to_string(i) + " is named '" + got + "'; the pair's " +
                   "order puts '" + outs[i].name + "' (or '" + outs[i].alias + "') there";
        }
        if (!matches(slot.outTypes[i], outs[i])) {
            return std::string(slot.label) + " output " + std::to_string(i) + " ('" + got + "', meaning " +
                   outs[i].name + ") is " + dimsStr(slot.outTypes[i]) + "; the spec expects " + dimsStr(outs[i].dims);
        }
    }
    return "";
}

/// Options: NPU ONLY - a refusal to delegate is an error, never a quiet CPU run of a 1.3 GB graph -
/// plus the MediaTek performance mode when one is asked for (-1 leaves LiteRT's default, which is
/// the arm the tablet numbers so far were taken on).
std::string buildOptionsLocked(Slot &slot, int performanceMode) {
    LiteRtStatus s = rt.api.LiteRtCreateOptions(&slot.options);
    if (s != kLiteRtStatusOk || !slot.options) {
        slot.options = nullptr;
        return std::string(slot.label) + " LiteRtCreateOptions: " + st(s);
    }
    s = rt.api.LiteRtSetOptionsHardwareAccelerators(slot.options, kLiteRtHwAcceleratorNpu);
    if (s != kLiteRtStatusOk) return std::string(slot.label) + " accelerators=NPU: " + st(s);
    if (performanceMode < 0) return "";
    LiteRtOpaqueOptions mtk = nullptr;
    s = rt.api.LiteRtMediatekOptionsCreate(&mtk);
    if (s != kLiteRtStatusOk || !mtk) return std::string(slot.label) + " LiteRtMediatekOptionsCreate: " + st(s);
    LiteRtMediatekOptions data = nullptr;
    s = rt.api.LiteRtMediatekOptionsGet(mtk, &data);
    if (s == kLiteRtStatusOk && data) {
        s = rt.api.LiteRtMediatekOptionsSetPerformanceMode(
                data, static_cast<LiteRtMediatekNeuronAdapterPerformanceMode>(performanceMode));
    }
    if (s != kLiteRtStatusOk) {
        rt.api.LiteRtDestroyOpaqueOptions(mtk);
        return std::string(slot.label) + " MediaTek performance mode " + std::to_string(performanceMode) + ": " + st(s);
    }
    s = rt.api.LiteRtAddOpaqueOptions(slot.options, mtk);   // ownership moves to the options on success
    if (s != kLiteRtStatusOk) {
        rt.api.LiteRtDestroyOpaqueOptions(mtk);
        return std::string(slot.label) + " LiteRtAddOpaqueOptions: " + st(s);
    }
    return "";
}

std::string compileLocked(Slot &slot, double *ms) {
    const auto t0 = Clock::now();
    const LiteRtStatus s = rt.api.LiteRtCreateCompiledModel(rt.env, slot.model, slot.options, &slot.compiled);
    *ms = msSince(t0);
    if (s != kLiteRtStatusOk || !slot.compiled) {
        slot.compiled = nullptr;
        return std::string(slot.label) + " LiteRtCreateCompiledModel (the bytecode restore): " + st(s) +
               " after " + std::to_string(static_cast<long long>(*ms)) + " ms";
    }
    bool full = false;
    const bool known = rt.api.LiteRtCompiledModelIsFullyAccelerated(slot.compiled, &full) == kLiteRtStatusOk;
    LOGI("%s: compiled for the NPU in %.0f ms (fully accelerated: %s)", slot.label, *ms,
         known ? (full ? "yes" : "no - the leftover ops run on LiteRT's CPU kernels") : "?");
    return "";
}

// ---------------------------------------------------------------- buffers from requirements

void bindSelfKvLocked(int inSet);   // below, with the other host-side handle work

std::string reqDesc(LiteRtTensorBufferRequirements r) {
    size_t size = 0;
    int ntypes = 0, nstrides = 0;
    const uint32_t *strides = nullptr;
    rt.api.LiteRtGetTensorBufferRequirementsBufferSize(r, &size);
    rt.api.LiteRtGetNumTensorBufferRequirementsSupportedBufferTypes(r, &ntypes);
    rt.api.LiteRtGetTensorBufferRequirementsStrides(r, &nstrides, &strides);
    std::string s = "size=" + std::to_string(size) + " types=[";
    for (int i = 0; i < ntypes; ++i) {
        LiteRtTensorBufferType t = kLiteRtTensorBufferTypeUnknown;
        rt.api.LiteRtGetTensorBufferRequirementsSupportedTensorBufferType(r, i, &t);
        if (i) s += ",";
        s += std::to_string(static_cast<int>(t));
    }
    s += "] strides=" + std::to_string(nstrides);
    return s;
}

/// One managed buffer from [req], typed [type], owned by the session. Its packed size must be the
/// tensor's exact bytes: that is what every Lock below reads or writes, nothing more.
std::string createBufferLocked(LiteRtTensorBufferRequirements req, const LiteRtRankedTensorType &type,
                               const std::string &what, LiteRtTensorBuffer *out) {
    LiteRtTensorBuffer b = nullptr;
    const LiteRtStatus s = rt.api.LiteRtCreateManagedTensorBufferFromRequirements(rt.env, &type, req, &b);
    if (s != kLiteRtStatusOk || !b) {
        return "buffer " + what + ": LiteRtCreateManagedTensorBufferFromRequirements " + st(s) + " (" + reqDesc(req) + ")";
    }
    g.owned.push_back(b);
    size_t packed = 0;
    if (rt.api.LiteRtGetTensorBufferPackedSize(b, &packed) != kLiteRtStatusOk || packed != tensorBytes(type)) {
        return "buffer " + what + ": packed size " + std::to_string(packed) + " B, the tensor is " +
               std::to_string(tensorBytes(type)) + " B";
    }
    *out = b;
    return "";
}

/// THE SHARED-BUFFER GUARD, the LiteRT twin of qnn_asr.cpp's C7 alias guard: two tensors that are
/// to be ONE buffer must be the same tensor in every respect this API can state - element type and
/// dims, requirement size, strides - and their requirements must join. A failed join is a refusal,
/// never a fallback to two buffers and a copy.
std::string joinLocked(LiteRtTensorBufferRequirements a, const LiteRtRankedTensorType &ta,
                       LiteRtTensorBufferRequirements b, const LiteRtRankedTensorType &tb,
                       const std::string &what, LiteRtTensorBufferRequirements *out) {
    if (!sameType(ta, tb)) {
        return "join " + what + ": the two sides are " + dimsStr(ta) + " and " + dimsStr(tb);
    }
    size_t sa = 0, sb = 0;
    int na = 0, nb = 0;
    const uint32_t *pa = nullptr, *pb = nullptr;
    if (rt.api.LiteRtGetTensorBufferRequirementsBufferSize(a, &sa) != kLiteRtStatusOk ||
        rt.api.LiteRtGetTensorBufferRequirementsBufferSize(b, &sb) != kLiteRtStatusOk ||
        rt.api.LiteRtGetTensorBufferRequirementsStrides(a, &na, &pa) != kLiteRtStatusOk ||
        rt.api.LiteRtGetTensorBufferRequirementsStrides(b, &nb, &pb) != kLiteRtStatusOk) {
        return "join " + what + ": requirements unreadable";
    }
    if (sa != sb) return "join " + what + ": sizes " + std::to_string(sa) + " and " + std::to_string(sb);
    if (na != nb || (na > 0 && (!pa || !pb || memcmp(pa, pb, sizeof(uint32_t) * static_cast<size_t>(na)) != 0))) {
        return "join " + what + ": the two sides' strides differ";
    }
    LiteRtTensorBufferRequirements j = nullptr;
    const LiteRtStatus s = rt.api.LiteRtJoinTensorBufferRequirements(a, b, &j);
    if (s != kLiteRtStatusOk || !j) {
        return "join " + what + ": LiteRtJoinTensorBufferRequirements " + st(s) + " (" + reqDesc(a) + " / " +
               reqDesc(b) + ")";
    }
    g.joined.push_back(j);
    size_t sj = 0;
    if (rt.api.LiteRtGetTensorBufferRequirementsBufferSize(j, &sj) != kLiteRtStatusOk || sj != sa) {
        return "join " + what + ": the joined size " + std::to_string(sj) + " is not the sides' " + std::to_string(sa);
    }
    *out = j;
    return "";
}

std::string inReq(const Slot &slot, size_t idx, LiteRtTensorBufferRequirements *r) {
    const LiteRtStatus s = rt.api.LiteRtGetCompiledModelInputBufferRequirements(slot.compiled, slot.sig, idx, r);
    if (s != kLiteRtStatusOk || !*r) {
        return std::string(slot.label) + " input '" + slot.inNames[idx] + "' requirements: " + st(s);
    }
    return "";
}

std::string outReq(const Slot &slot, size_t idx, LiteRtTensorBufferRequirements *r) {
    const LiteRtStatus s = rt.api.LiteRtGetCompiledModelOutputBufferRequirements(slot.compiled, slot.sig, idx, r);
    if (s != kLiteRtStatusOk || !*r) {
        return std::string(slot.label) + " output " + std::to_string(idx) + " requirements: " + st(s);
    }
    return "";
}

/// Allocates every buffer and fills the four run arrays. After this the encoder writes the eight
/// cross-KV buffers that ARE the decoder's cross-KV inputs, and each decode step only rewrites
/// three small inputs and swaps which self-KV set is which.
std::string allocateLocked() {
    const size_t L = g.layers;
    g.encIn.assign(g.enc.inNames.size(), nullptr);
    g.encOut.assign(g.enc.outNames.size(), nullptr);
    g.decIn.assign(g.dec.inNames.size(), nullptr);
    g.decOut.assign(g.dec.outNames.size(), nullptr);

    // The mel.
    const size_t melIdx = g.enc.inIndex.at(kInputFeatures);
    LiteRtTensorBufferRequirements r = nullptr;
    std::string err = inReq(g.enc, melIdx, &r);
    if (err.empty()) err = createBufferLocked(r, g.enc.inTypes[melIdx], kInputFeatures, &g.mel);
    if (!err.empty()) return err;
    g.melBytes = tensorBytes(g.enc.inTypes[melIdx]);
    g.encIn[melIdx] = g.mel;

    // The cross-KV: encoder output j (k0,v0,k1,v1,...) and decoder input k/v_cache_cross_i, ONE buffer.
    g.cross.assign(2 * L, nullptr);
    for (size_t j = 0; j < 2 * L; ++j) {
        const std::string name = std::string(j % 2 == 0 ? "k" : "v") + "_cache_cross_" + std::to_string(j / 2);
        const size_t di = g.dec.inIndex.at(name);
        LiteRtTensorBufferRequirements er = nullptr, dr = nullptr, jr = nullptr;
        err = outReq(g.enc, j, &er);
        if (err.empty()) err = inReq(g.dec, di, &dr);
        if (err.empty()) err = joinLocked(er, g.enc.outTypes[j], dr, g.dec.inTypes[di], name, &jr);
        if (err.empty()) err = createBufferLocked(jr, g.enc.outTypes[j], name, &g.cross[j]);
        if (!err.empty()) return err;
        g.encOut[j] = g.cross[j];
        g.decIn[di] = g.cross[j];
    }

    // The self-KV ping-pong: input k/v_cache_self_i_in with output 1+2i / 2+2i, two buffers each.
    g.selfInIdx.assign(2 * L, 0);
    g.selfOutIdx.assign(2 * L, 0);
    g.selfKvBytes.assign(2 * L, 0);
    g.selfKv[0].assign(2 * L, nullptr);
    g.selfKv[1].assign(2 * L, nullptr);
    for (size_t j = 0; j < 2 * L; ++j) {
        const std::string name = std::string(j % 2 == 0 ? "k" : "v") + "_cache_self_" + std::to_string(j / 2);
        const size_t di = g.dec.inIndex.at(name + "_in");
        const size_t dout = 1 + j;
        LiteRtTensorBufferRequirements ir = nullptr, orq = nullptr, jr = nullptr;
        err = inReq(g.dec, di, &ir);
        if (err.empty()) err = outReq(g.dec, dout, &orq);
        if (err.empty()) err = joinLocked(ir, g.dec.inTypes[di], orq, g.dec.outTypes[dout], name + "_in/_out", &jr);
        for (int set = 0; set < 2 && err.empty(); ++set) {
            err = createBufferLocked(jr, g.dec.inTypes[di], name + " set " + std::to_string(set), &g.selfKv[set][j]);
        }
        if (!err.empty()) return err;
        g.selfInIdx[j] = di;
        g.selfOutIdx[j] = dout;
        g.selfKvBytes[j] = tensorBytes(g.dec.inTypes[di]);
    }

    // The three step inputs and the logits.
    struct One { const char *name; LiteRtTensorBuffer *buf; size_t *idx; };
    const One ones[] = {{kInputIds, &g.inputIds, &g.decInputIdsIdx},
                        {kPositionIds, &g.positionIds, &g.decPositionIdsIdx},
                        {kAttentionMask, &g.mask, &g.decMaskIdx}};
    for (const One &o : ones) {
        const size_t di = g.dec.inIndex.at(o.name);
        err = inReq(g.dec, di, &r);
        if (err.empty()) err = createBufferLocked(r, g.dec.inTypes[di], o.name, o.buf);
        if (!err.empty()) return err;
        *o.idx = di;
        g.decIn[di] = *o.buf;
    }
    err = outReq(g.dec, 0, &r);
    if (err.empty()) err = createBufferLocked(r, g.dec.outTypes[0], "logits", &g.logitsBuf);
    if (!err.empty()) return err;
    g.decOut[0] = g.logitsBuf;
    g.logits.assign(g.vocab, 0.0f);
    g.maskHost.assign(g.maskLen, kMaskBlocked);

    // Every run-array slot must hold a buffer: an unbound slot would run against a null handle. The
    // self-KV slots are filled by the first bind, so it goes first.
    bindSelfKvLocked(0);
    for (size_t i = 0; i < g.encIn.size(); ++i) {
        if (!g.encIn[i]) return "encoder input '" + g.enc.inNames[i] + "' was never bound";
    }
    for (size_t i = 0; i < g.encOut.size(); ++i) {
        if (!g.encOut[i]) return "encoder output " + std::to_string(i) + " was never bound";
    }
    for (size_t i = 0; i < g.decIn.size(); ++i) {
        if (!g.decIn[i]) return "decoder input '" + g.dec.inNames[i] + "' was never bound";
    }
    for (size_t i = 0; i < g.decOut.size(); ++i) {
        if (!g.decOut[i]) return "decoder output " + std::to_string(i) + " was never bound";
    }

    // What the compiled models asked for, once per session: the buffer TYPES are the evidence that
    // the requirements path, not a host allocation, produced them (2 = AHardwareBuffer, 4 = DMA-BUF).
    LiteRtTensorBufferRequirements melReq = nullptr, logitsReq = nullptr;
    inReq(g.enc, melIdx, &melReq);
    outReq(g.dec, 0, &logitsReq);
    LOGI("buffers: %zu managed, %zu joins; mel {%s}; cross-KV 0 {%s}; self-KV 0 {%s}; logits {%s}",
         g.owned.size(), g.joined.size(), melReq ? reqDesc(melReq).c_str() : "?",
         g.joined.empty() ? "?" : reqDesc(g.joined.front()).c_str(),
         g.joined.size() > 2 * L ? reqDesc(g.joined[2 * L]).c_str() : "?",
         logitsReq ? reqDesc(logitsReq).c_str() : "?");
    return "";
}

// ---------------------------------------------------------------- host access, only under lock

std::string writeLocked(LiteRtTensorBuffer b, const void *src, size_t n, const char *what) {
    void *p = nullptr;
    LiteRtStatus s = rt.api.LiteRtLockTensorBuffer(b, &p, kLiteRtTensorBufferLockModeWrite);
    if (s != kLiteRtStatusOk || !p) return std::string("lock ") + what + ": " + st(s);
    if (src) memcpy(p, src, n); else memset(p, 0, n);
    s = rt.api.LiteRtUnlockTensorBuffer(b);
    if (s != kLiteRtStatusOk) return std::string("unlock ") + what + ": " + st(s);
    return "";
}

std::string readLocked(LiteRtTensorBuffer b, void *dst, size_t n, const char *what) {
    void *p = nullptr;
    LiteRtStatus s = rt.api.LiteRtLockTensorBuffer(b, &p, kLiteRtTensorBufferLockModeRead);
    if (s != kLiteRtStatusOk || !p) return std::string("lock ") + what + ": " + st(s);
    memcpy(dst, p, n);
    s = rt.api.LiteRtUnlockTensorBuffer(b);
    if (s != kLiteRtStatusOk) return std::string("unlock ") + what + ": " + st(s);
    return "";
}

/// Binds set [inSet] as the decoder's self-KV INPUTS and the other set as its OUTPUTS - 2 x 2L
/// handle stores into the run arrays, and not one byte moved.
void bindSelfKvLocked(int inSet) {
    const int outSet = 1 - inSet;
    for (size_t j = 0; j < g.selfInIdx.size(); ++j) {
        g.decIn[g.selfInIdx[j]] = g.selfKv[inSet][j];
        g.decOut[g.selfOutIdx[j]] = g.selfKv[outSet][j];
    }
    g.selfInSet = inSet;
}

/// Zeroes BOTH sets and binds set 0 as the input side. The window is a right-aligned shift register
/// and the never-written columns are exactly the ones the mask blocks, so this is determinism, not
/// correctness: a previous segment's cache must not reach this one through any slot.
std::string zeroSelfKvLocked() {
    for (int s = 0; s < 2; ++s) {
        for (size_t j = 0; j < g.selfKv[s].size(); ++j) {
            const std::string err = writeLocked(g.selfKv[s][j], nullptr, g.selfKvBytes[j], "self-KV (zero)");
            if (!err.empty()) return err;
        }
    }
    bindSelfKvLocked(0);
    return "";
}

// ---------------------------------------------------------------- one step

/// One decoder run at [position] with [tokenId]; the step's logits land in g.logits. The caller owns
/// the masks, the argmax and the swap.
///
/// THE MASK: 200 columns = 199 cache slots + the current token, and the cache is a RIGHT-ALIGNED
/// shift register (qnn_asr.cpp's decodeStepLocked says how each link was established; the MediaTek
/// decoder was exported with the same concat-on-the-right + drop-the-oldest cache, and the host
/// check in export_decoder_mtk.py closed it against HF's own decoder at steps 0 and 1). So at
/// position p the LAST p+1 columns attend and the rest are blocked:
///   p = 0 -> column 199 only;  p = 3 -> 196..199;  p = 198 -> 1..199.
std::string decodeStepLocked(int32_t tokenId, uint32_t position) {
    const auto t0 = Clock::now();
    const int32_t pos = static_cast<int32_t>(position);
    std::string err = writeLocked(g.inputIds, &tokenId, sizeof(tokenId), kInputIds);
    if (err.empty()) err = writeLocked(g.positionIds, &pos, sizeof(pos), kPositionIds);
    const uint32_t firstLive = (position < g.maskLen) ? (g.maskLen - 1 - position) : 0;
    for (uint32_t i = 0; i < g.maskLen; ++i) g.maskHost[i] = (i >= firstLive) ? kMaskAttend : kMaskBlocked;
    if (err.empty()) err = writeLocked(g.mask, g.maskHost.data(), g.maskLen * sizeof(float), kAttentionMask);
    if (!err.empty()) return err;
    const auto t1 = Clock::now();
    const LiteRtStatus s = rt.api.LiteRtRunCompiledModel(g.dec.compiled, g.dec.sig, g.decIn.size(), g.decIn.data(),
                                                         g.decOut.size(), g.decOut.data());
    const auto t2 = Clock::now();
    if (s != kLiteRtStatusOk) return "LiteRtRunCompiledModel at position " + std::to_string(position) + ": " + st(s);
    err = readLocked(g.logitsBuf, g.logits.data(), g.vocab * sizeof(float), "logits");
    g.runMs += std::chrono::duration<double, std::milli>(t2 - t1).count();
    g.ioMs += std::chrono::duration<double, std::milli>(t1 - t0).count() + msSince(t2);
    return err;
}

/// THE PER-STEP NON-FINITE CHECK. A NaN or an infinity in the raw logits is an fp16 overflow or a
/// broken restore, and a float argmax over it is not a token - NaN compares false with everything,
/// so it would quietly pick whatever the scan met first. It fails the step, and the segment falls
/// back loudly (design §4); the session itself stays armed for the next segment.
std::string checkFiniteLocked(uint32_t position) {
    uint32_t bad = 0;
    int32_t first = -1;
    for (uint32_t i = 0; i < g.vocab; ++i) {
        if (!std::isfinite(g.logits[i])) {
            if (first < 0) first = static_cast<int32_t>(i);
            ++bad;
        }
    }
    if (bad == 0) return "";
    return "the decoder's logits hold " + std::to_string(bad) + " non-finite values at position " +
           std::to_string(position) + " (first at id " + std::to_string(first) +
           ") - an fp16 overflow or a broken bytecode restore; this segment falls back";
}

// ---------------------------------------------------------------- mask, THEN argmax (float)

/// qnn_asr.cpp's C2 rule, in float: the mask writes -inf into the logits, and ONLY THEN is the
/// argmax scanned, in one function, so the two cannot drift apart. Ties go to the first index.
/// -1 when every logit is at the floor.
int32_t suppressThenArgmaxF(float *logits, uint32_t vocab, const std::vector<int32_t> &suppress,
                            const std::vector<int32_t> &beginSuppress, bool applyBegin) {
    for (int32_t id : suppress) logits[id] = kLogitFloor;
    if (applyBegin) {
        for (int32_t id : beginSuppress) logits[id] = kLogitFloor;
    }
    int32_t best = -1;
    float bestVal = kLogitFloor;
    for (uint32_t i = 0; i < vocab; ++i) {
        if (logits[i] > bestVal) {
            bestVal = logits[i];
            best = static_cast<int32_t>(i);
        }
    }
    return best;
}

/// log(sum(exp(scale * v))) over the vocabulary. `excludeFloor` drops the mask's -inf entries - the
/// masked distribution; false for the RAW read at the SOT step, where nothing is masked (and every
/// raw logit is finite, checked). false when nothing is live.
bool logSumExpF(const float *logits, uint32_t vocab, float scale, bool excludeFloor, double *outLogZ) {
    double mx = 0.0;
    bool any = false;
    for (uint32_t i = 0; i < vocab; ++i) {
        if (excludeFloor && logits[i] == kLogitFloor) continue;
        const double v = static_cast<double>(scale) * logits[i];
        if (!any || v > mx) { mx = v; any = true; }
    }
    if (!any) return false;
    double sum = 0.0;
    for (uint32_t i = 0; i < vocab; ++i) {
        if (excludeFloor && logits[i] == kLogitFloor) continue;
        sum += std::exp(static_cast<double>(scale) * logits[i] - mx);
    }
    *outLogZ = mx + std::log(sum);
    return true;
}

/// p(<|nospeech|>) from the RAW logits of the SOT step (whisper.cpp reads it before any filtering).
float noSpeechProbabilityF(const float *logits, uint32_t vocab, int32_t noSpeechToken) {
    double logZ = 0.0;
    if (!logSumExpF(logits, vocab, kLogitScale, /*excludeFloor=*/false, &logZ)) return kStatUnreadable;
    return static_cast<float>(std::exp(static_cast<double>(kLogitScale) * logits[noSpeechToken] - logZ));
}

/// log p(id) under the MASKED distribution, temperature-scaled on the T > 0 rungs exactly as
/// qnn_asr.cpp's maskedLogprobLocked does it (whisper.cpp:6460-6462): a rung is judged on the
/// distribution it drew from.
double maskedLogprobF(const float *logits, uint32_t vocab, float temperature, int32_t id) {
    const float s = temperature > 0.0f ? kLogitScale / temperature : kLogitScale;
    double logZ = 0.0;
    if (!logSumExpF(logits, vocab, s, /*excludeFloor=*/true, &logZ)) return 0.0;
    return static_cast<double>(s) * logits[id] - logZ;
}

/// MASK, THEN DRAW, for the ladder's T > 0 rungs: the same rng seeding and the same draw as
/// qnn_asr.cpp's suppressThenSample, over float logits. -inf entries carry no mass.
int32_t suppressThenSampleF(float *logits, uint32_t vocab, const std::vector<int32_t> &suppress,
                            const std::vector<int32_t> &beginSuppress, bool applyBegin, float temperature,
                            std::mt19937 &rng) {
    for (int32_t id : suppress) logits[id] = kLogitFloor;
    if (applyBegin) {
        for (int32_t id : beginSuppress) logits[id] = kLogitFloor;
    }
    double mx = 0.0;
    bool any = false;
    for (uint32_t i = 0; i < vocab; ++i) {
        if (logits[i] == kLogitFloor) continue;
        const double v = static_cast<double>(kLogitScale) * logits[i];
        if (!any || v > mx) { mx = v; any = true; }
    }
    if (!any) return -1;
    double sum = 0.0;
    for (uint32_t i = 0; i < vocab; ++i) {
        if (logits[i] == kLogitFloor) continue;
        sum += std::exp((static_cast<double>(kLogitScale) * logits[i] - mx) / temperature);
    }
    std::uniform_real_distribution<double> uni(0.0, 1.0);
    double target = uni(rng) * sum;
    int32_t last = -1;
    for (uint32_t i = 0; i < vocab; ++i) {
        if (logits[i] == kLogitFloor) continue;
        target -= std::exp((static_cast<double>(kLogitScale) * logits[i] - mx) / temperature);
        last = static_cast<int32_t>(i);
        if (target <= 0.0) return last;
    }
    return last;
}

/// whisper_sequence_score's entropy over the last kEntropyWindow TEXT ids - qnn_asr.cpp's
/// trailingEntropy, verbatim in behaviour (4.11 Task 3: timestamps are skipped, so a runaway that
/// carries them cannot dilute the window past the trip).
double trailingEntropy(const std::vector<int32_t> &ids, int32_t count, int32_t *outDistinct,
                       int32_t *outWindowStart) {
    std::map<int32_t, int> hist;
    int32_t n = 0;
    int32_t start = count;
    for (int32_t i = count - 1; i >= 0 && n < kEntropyWindow; --i) {
        if (ids[static_cast<size_t>(i)] >= kEotToken) continue;
        hist[ids[static_cast<size_t>(i)]]++;
        ++n;
        start = i;
    }
    *outDistinct = static_cast<int32_t>(hist.size());
    *outWindowStart = start;
    if (n <= 0) return 0.0;
    double h = 0.0;
    for (const auto &kv : hist) {
        const double p = kv.second / static_cast<double>(n);
        h -= p * std::log(p);
    }
    return h;
}

// ---------------------------------------------------------------- content-safe instrumentation

/// qnn_asr.cpp's privacy rule, the same helper: ids at or above EOT print verbatim, every text id
/// is the constant "text-token". No id that could be a word reaches a log line.
const char *diagToken(int32_t id, char *buf, size_t n) {
    if (id < 0) snprintf(buf, n, "none");
    else if (id >= kEotToken) snprintf(buf, n, "%d", id);
    else snprintf(buf, n, "text-token");
    return buf;
}

std::string diagIdList(const std::vector<int32_t> &ids, size_t cap) {
    std::string s = "[";
    char b[24];
    for (size_t i = 0; i < ids.size() && i < cap; ++i) {
        if (i) s += ",";
        s += diagToken(ids[i], b, sizeof(b));
    }
    if (ids.size() > cap) s += ",...";
    return s + "]";
}

std::vector<int32_t> jintsToVector(JNIEnv *env, jintArray a) {
    std::vector<int32_t> v;
    if (!a) return v;
    const jsize n = env->GetArrayLength(a);
    if (n <= 0) return v;
    v.resize(static_cast<size_t>(n));
    env->GetIntArrayRegion(a, 0, n, reinterpret_cast<jint *>(v.data()));
    return v;
}

std::string checkTokenIdsLocked(const std::vector<int32_t> &ids, const char *what) {
    for (size_t i = 0; i < ids.size(); ++i) {
        if (ids[i] < 0 || ids[i] >= static_cast<int32_t>(g.vocab)) {
            return std::string(what) + "[" + std::to_string(i) + "] = " + std::to_string(ids[i]) +
                   " is outside the vocabulary 0.." + std::to_string(g.vocab - 1);
        }
    }
    return "";
}

const char *perfModeName(int m) {
    switch (m) {
        case -1: return "default";
        case kLiteRtMediatekNeuronAdapterPerformanceModeNeuronPreferLowPower: return "PreferLowPower";
        case kLiteRtMediatekNeuronAdapterPerformanceModeNeuronPreferFastSingleAnswer: return "PreferFastSingleAnswer";
        case kLiteRtMediatekNeuronAdapterPerformanceModeNeuronPreferSustainedSpeed: return "PreferSustainedSpeed";
        case kLiteRtMediatekNeuronAdapterPerformanceModeNeuronPreferTurboBoost: return "PreferTurboBoost";
        default: return "?";
    }
}

// ---------------------------------------------------------------- teardown

/// Frees THE SESSION - every tensor buffer, the requirement joins, both compiled models, their
/// options and both models - and nothing else. The environment, libLiteRt.so and the Neuron adapter
/// handles are process state and outlive every session (design §2.6): nothing here, or anywhere in
/// this file, destroys the one or closes the others. Safe on a partial state and safe twice.
///
/// Buffers first, then the compiled models: the order the probe's e2eqc mode released in on every
/// tablet run (t6-t11), i.e. the order that is known not to crash the v2.1.1 dispatch.
void releaseLocked() {
    for (LiteRtTensorBuffer b : g.owned) {
        if (b) rt.api.LiteRtDestroyTensorBuffer(b);
    }
    g.owned.clear();
    for (LiteRtTensorBufferRequirements r : g.joined) {
        if (r) rt.api.LiteRtDestroyTensorBufferRequirements(r);
    }
    g.joined.clear();
    for (Slot *slot : {&g.dec, &g.enc}) {
        if (slot->compiled) rt.api.LiteRtDestroyCompiledModel(slot->compiled);
        if (slot->options) rt.api.LiteRtDestroyOptions(slot->options);
        if (slot->model) rt.api.LiteRtDestroyModel(slot->model);
        const char *label = slot->label;
        *slot = Slot{};
        slot->label = label;
    }
    g.mel = g.inputIds = g.positionIds = g.mask = g.logitsBuf = nullptr;
    g.melBytes = 0;
    g.cross.clear();
    g.selfKv[0].clear();
    g.selfKv[1].clear();
    g.selfKvBytes.clear();
    g.selfInSet = 0;
    g.encIn.clear();
    g.encOut.clear();
    g.decIn.clear();
    g.decOut.clear();
    g.selfInIdx.clear();
    g.selfOutIdx.clear();
    g.decInputIdsIdx = g.decPositionIdsIdx = g.decMaskIdx = 0;
    g.logits.clear();
    g.maskHost.clear();
    g.melBins = g.layers = g.heads = g.vocab = g.maskLen = 0;
    g.langTokenFirst = g.langTokenLast = 0;
    g.performanceMode = -1;
    g.encoded = false;
    g.initialised = false;
    g.epoch = 0;
}

}  // namespace

// ================================================================ JNI surface
//
// Kotlin `object LiteRtAsrNative` -> instance methods on the singleton, hence `jobject`. Every
// String return is "" on success or "stage: detail" on failure, the same text readable afterwards
// from nativeLastError() - QnnAsrNative's convention, so the backend reads both engines one way.

/// THE DRIVER CHECK, and the tier's warm-up (design §2.3). Walks LiteRT v2.1.1's adapter candidates
/// once per process, keeping every handle, reads the winner's Neuron version and judges it against
/// [wantMajor]; then loads libLiteRt.so and resolves every entry point, so a pass means the whole
/// runtime is reachable. The first call waits ~5 s inside MediaTek's adapter constructor - call it
/// from a background thread, never from Main - and every later call answers from the cached walk.
///
/// Creates no environment, opens no model (see Runtime::env for why the environment waits for init).
extern "C" JNIEXPORT jstring JNICALL
Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeProbe(
        JNIEnv *env, jobject /* this */, jstring jDispatchDir, jstring jLibDir, jint wantMajor) {
    const std::string dispatchDir = jstr(env, jDispatchDir);
    const std::string libDir = jstr(env, jLibDir);
    std::lock_guard<std::mutex> lock(g.mu);
    const std::string reason = probeLocked(dispatchDir, wantMajor);
    if (!reason.empty()) return env->NewStringUTF(failure("probe: " + reason).c_str());
    const std::string err = loadRuntimeLocked(libDir);
    if (!err.empty()) return env->NewStringUTF(failure("probe: " + err).c_str());
    g.lastError.clear();
    return env->NewStringUTF("");
}

/// Arms the session: the driver verdict (walking the adapter if no probe has), the runtime, the
/// environment (once per process), both files' LiteRtStamp against [socStamp], both models, the IO
/// census, NPU-only options with the MediaTek [performanceMode], both compiled models (the bytecode
/// restores), and every buffer from the compiled models' requirements. Idempotent by releasing
/// first - after the scalars are refused, never before.
extern "C" JNIEXPORT jstring JNICALL
Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeInit(
        JNIEnv *env, jobject /* this */,
        jstring jEncoderPath, jstring jDecoderPath, jstring jDispatchDir, jstring jLibDir,
        jint melBins, jint decLayers, jint heads, jint vocab, jint maxPositions,
        jstring jSocStamp, jint wantMajor, jint performanceMode) {
    const std::string encoderPath = jstr(env, jEncoderPath);
    const std::string decoderPath = jstr(env, jDecoderPath);
    const std::string dispatchDir = jstr(env, jDispatchDir);
    const std::string libDir = jstr(env, jLibDir);
    const std::string socStamp = jstr(env, jSocStamp);

    std::lock_guard<std::mutex> lock(g.mu);

    // Refused FIRST, before the release below and before anything is opened (qnn_asr.cpp's order).
    PairCensus census{};
    std::string err = derivePairCensus(melBins, decLayers, heads, vocab, maxPositions, census);
    if (err.empty() && (performanceMode < -1 ||
                        performanceMode > kLiteRtMediatekNeuronAdapterPerformanceModeNeuronPreferTurboBoost)) {
        err = "spec: performanceMode is " + std::to_string(performanceMode) + "; expected -1 (LiteRT's default) or " +
              "0..3 (LiteRtMediatekNeuronAdapterPerformanceMode)";
    }
    if (err.empty() && socStamp.empty()) err = "spec: socStamp is empty; the family names the chip its bytecode is for";
    if (err.empty() && (wantMajor < 1 || wantMajor > 255)) {
        err = "spec: wantMajor is " + std::to_string(wantMajor) + "; a Neuron major is 1..255";
    }
    if (!err.empty()) return env->NewStringUTF(failure("init: " + err).c_str());

    if (g.initialised) {
        LOGW("nativeInit called on an already-initialised session; releasing it first");
        releaseLocked();
    }
    g.enc.label = "encoder";
    g.dec.label = "decoder";
    g.melBins = static_cast<uint32_t>(melBins);
    g.layers = static_cast<uint32_t>(decLayers);
    g.heads = static_cast<uint32_t>(heads);
    g.vocab = static_cast<uint32_t>(vocab);
    g.maskLen = static_cast<uint32_t>(maxPositions);
    g.langTokenFirst = census.langTokenFirst;
    g.langTokenLast = census.langTokenLast;
    g.performanceMode = performanceMode;
    LOGI("nativeInit spec: melBins=%d decLayers=%d heads=%d vocab=%d maxPositions=%d socStamp=%s wantMajor=%d "
         "performanceMode=%s; language band %d..%d", melBins, decLayers, heads, vocab, maxPositions,
         socStamp.c_str(), wantMajor, perfModeName(performanceMode), g.langTokenFirst, g.langTokenLast);

    auto refuse = [&](const std::string &why) {
        releaseLocked();
        return env->NewStringUTF(failure("init: " + why).c_str());
    };

    // 1. The driver. A probe normally ran at process start; if none did, this walk is the 5 s.
    if (adapter.walked && adapter.dispatchDir != dispatchDir) {
        return refuse("probe: the adapter was walked against " + adapter.dispatchDir + ", not " + dispatchDir);
    }
    const std::string reason = probeLocked(dispatchDir, wantMajor);
    if (!reason.empty()) return refuse("probe: " + reason);

    // 2. The runtime and the environment.
    err = loadRuntimeLocked(libDir);
    if (err.empty()) err = ensureEnvironmentLocked(dispatchDir);
    if (!err.empty()) return refuse(err);

    // 3. THE CHIP: both files' own stamp, before LiteRT opens either of them.
    for (const std::string *path : {&encoderPath, &decoderPath}) {
        std::string vendor, soc;
        err = readLiteRtStamp(*path, &vendor, &soc);
        if (!err.empty()) return refuse(err);
        if (vendor != kStampVendor || soc != socStamp) {
            return refuse("stamp: " + *path + " was compiled for " + vendor + " / " + soc + "; this family is " +
                          kStampVendor + " / " + socStamp);
        }
        LOGI("apu: stamp=%s %s matches the family", vendor.c_str(), soc.c_str());
    }

    // 4. The models, their signatures, and the census.
    err = openModelLocked(g.enc, encoderPath, kEncodeSignature);
    if (err.empty()) err = openModelLocked(g.dec, decoderPath, kDecodeSignature);
    if (err.empty()) err = checkIoLocked(g.enc, census.encIn, census.encOut);
    if (err.empty()) err = checkIoLocked(g.dec, census.decIn, census.decOut);
    if (!err.empty()) return refuse(err);

    // 5. Options and the two restores. The first restore in a process is where LiteRT's dispatch
    //    reaches the adapter - after a probe, only a refcount.
    double encMs = 0.0, decMs = 0.0;
    err = buildOptionsLocked(g.enc, performanceMode);
    if (err.empty()) err = buildOptionsLocked(g.dec, performanceMode);
    if (err.empty()) err = compileLocked(g.enc, &encMs);
    if (err.empty()) err = compileLocked(g.dec, &decMs);
    if (!err.empty()) return refuse(err);

    // 6. Every buffer, from requirements.
    err = allocateLocked();
    if (err.empty()) err = zeroSelfKvLocked();
    if (!err.empty()) return refuse(err);

    g.initialised = true;
    g.epoch = nextEpoch++;
    g.encoded = false;
    g.lastError.clear();
    LOGI("nativeInit OK - encoder '%s' (%zu in / %zu out, restore %.0f ms), decoder '%s' (%zu in / %zu out, "
         "restore %.0f ms), %zu buffers, performance mode %s", kEncodeSignature, g.enc.inNames.size(),
         g.enc.outNames.size(), encMs, kDecodeSignature, g.dec.inNames.size(), g.dec.outNames.size(), decMs,
         g.owned.size(), perfModeName(performanceMode));
    LOGDIAG("nativeInit: session armed with epoch %llu", (unsigned long long) g.epoch);
    return env->NewStringUTF("");
}

/// One encoder pass. [jMel] is the FLOAT mel - melBins x 3000 float32, direct, native order, the
/// buffer whisper.cpp's pcmToMel fills - and nothing is quantised: the pair is f32 at its boundary.
/// Copied into the mel buffer under lock, then run; the eight cross-KV buffers it writes are
/// already the decoder's inputs.
extern "C" JNIEXPORT jstring JNICALL
Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeEncode(
        JNIEnv *env, jobject /* this */, jobject jMel) {
    std::lock_guard<std::mutex> lock(g.mu);
    g.encoded = false;   // cleared FIRST: a failed run may leave the cross-KV half written
    if (!g.initialised) return env->NewStringUTF(failure("encode: session not initialised").c_str());
    if (!jMel) return env->NewStringUTF(failure("encode: mel buffer is null").c_str());
    void *src = env->GetDirectBufferAddress(jMel);
    const jlong cap = env->GetDirectBufferCapacity(jMel);
    if (!src || cap < 0) {
        return env->NewStringUTF(failure("encode: the mel is not a direct ByteBuffer").c_str());
    }
    if (static_cast<size_t>(cap) != g.melBytes) {
        return env->NewStringUTF(failure("encode: the mel is " + std::to_string(static_cast<long long>(cap)) +
                                         " B; input_features is " + std::to_string(g.melBytes) +
                                         " B of float32 (no quantised block reaches this engine)").c_str());
    }
    const auto t0 = Clock::now();
    std::string err = writeLocked(g.mel, src, g.melBytes, kInputFeatures);
    if (!err.empty()) return env->NewStringUTF(failure("encode: " + err).c_str());
    const double copyMs = msSince(t0);
    const auto t1 = Clock::now();
    const LiteRtStatus s = rt.api.LiteRtRunCompiledModel(g.enc.compiled, g.enc.sig, g.encIn.size(), g.encIn.data(),
                                                         g.encOut.size(), g.encOut.data());
    const double ms = msSince(t1);
    if (s != kLiteRtStatusOk) return env->NewStringUTF(failure("encode: LiteRtRunCompiledModel " + st(s)).c_str());
    LOGI("encode: run OK in %.1f ms (mel copy-in %.1f ms, performance mode %s)", ms, copyMs,
         perfModeName(g.performanceMode));
    g.encoded = true;
    g.lastError.clear();
    return env->NewStringUTF("");
}

/// THE WHOLE DECODE LOOP FOR ONE SEGMENT - QnnAsrNative.nativeDecodeSegment's contract, argument
/// for argument and slot for slot, as its own float loop:
///
///   * [jPrompt] `[SOT, <|lang|>, TRANSCRIBE]` is fed through the same step path, positions
///     0..promptLen-1, and the argmax at promptLen-1 is the first generated token;
///   * [jSuppress] is written as -inf at every generated step and [jBeginSuppress] at the first
///     generated step only, BEFORE the argmax (the C2 rule);
///   * timestamps are emitted; they stay out of the text-only entropy window and out of
///     avg_logprob, exactly as qnn_asr.cpp keeps them out (4.11 Task 3 and its fix round 2);
///   * the temperature ladder re-decodes against the same encode with both self-KV sets zeroed;
///   * p(nospeech) at the SOT step and avg_logprob are computed in float with the floor at -inf and
///     the scale at 1.0 - never 0, so the no-speech gate is always live on this tier;
///   * positions 0..maskLen-2 execute (0..198) - the 199-slot window - and maskLen-1 never runs;
///   * every step's raw logits are checked for non-finite values first.
///
/// Returns the count written into [jOut] (0 = EOT first = silence), or < 0 with the reason in
/// nativeLastError(): -1 arguments or state, -2 a run/lock failure, -3 every logit at the floor,
/// -4 non-finite logits.
extern "C" JNIEXPORT jint JNICALL
Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeDecodeSegment(
        JNIEnv *env, jobject /* this */, jintArray jPrompt, jintArray jSuppress,
        jintArray jBeginSuppress, jint maxTokens, jintArray jOut,
        jfloatArray jTemperatures, jfloat entropyThold, jfloat logprobThold,
        jfloat noSpeechThold, jint noSpeechToken, jint cycleMaxDistinct, jfloatArray jStats) {
    std::lock_guard<std::mutex> lock(g.mu);
    if (!g.initialised) {
        failure("decode: session not initialised");
        return -1;
    }
    if (!jPrompt || !jOut) {
        failure("decode: prompt and out must not be null");
        return -1;
    }
    if (!g.encoded) {
        failure("decode: no encoded segment - call nativeEncode first. The decoder reads the encoder's "
                "cross-KV buffers in place, so decoding without an encode transcribes whatever the "
                "previous segment left there - fluently, and with no other symptom.");
        return -1;
    }
    const std::vector<int32_t> prompt = jintsToVector(env, jPrompt);
    const std::vector<int32_t> suppress = jintsToVector(env, jSuppress);
    const std::vector<int32_t> beginSuppress = jintsToVector(env, jBeginSuppress);

    // The same two caps on one expression as qnn_asr.cpp: positions 0..maskLen-2 execute, and a
    // prompt of maskLen-1 tokens still generates one.
    const uint32_t lastPosition = g.maskLen - 2;
    const uint32_t maxPromptLen = g.maskLen - 1;
    if (prompt.empty() || prompt.size() > maxPromptLen) {
        failure("decode: prompt is " + std::to_string(prompt.size()) + " tokens; it must be 1.." +
                std::to_string(maxPromptLen) + " so at least one position is left to generate in");
        return -1;
    }
    std::string err = checkTokenIdsLocked(prompt, "prompt");
    if (err.empty()) err = checkTokenIdsLocked(suppress, "suppress");
    if (err.empty()) err = checkTokenIdsLocked(beginSuppress, "beginSuppress");
    if (!err.empty()) {
        failure("decode: " + err);
        return -1;
    }
    if (maxTokens <= 0) {
        failure("decode: maxTokens is " + std::to_string(maxTokens) +
                "; a non-positive budget returns zero tokens, which reads exactly like silence");
        return -1;
    }
    const jsize outLen = env->GetArrayLength(jOut);
    if (outLen < maxTokens) {
        failure("decode: out has room for " + std::to_string(static_cast<long long>(outLen)) + " ids but maxTokens is " +
                std::to_string(maxTokens) + " (size it with NpuDecodePolicy.maxTokensFor(prompt.size))");
        return -1;
    }
    std::vector<float> temperatures;
    if (jTemperatures) {
        const jsize n = env->GetArrayLength(jTemperatures);
        if (n > 0) {
            temperatures.resize(static_cast<size_t>(n));
            env->GetFloatArrayRegion(jTemperatures, 0, n, temperatures.data());
        }
    }
    if (temperatures.empty() || temperatures[0] != 0.0f) {
        failure("decode: temperatures must start with 0 (the greedy rung); got " +
                std::to_string(temperatures.size()) + " entries");
        return -1;
    }
    for (size_t i = 1; i < temperatures.size(); ++i) {
        if (!(temperatures[i] > temperatures[i - 1]) || !std::isfinite(temperatures[i])) {
            failure("decode: temperatures must be finite and ascending at index " + std::to_string(i));
            return -1;
        }
    }
    if (!std::isfinite(entropyThold) || !std::isfinite(logprobThold) || !std::isfinite(noSpeechThold)) {
        failure("decode: a guard threshold is not finite");
        return -1;
    }
    if (noSpeechToken < 0 || noSpeechToken >= static_cast<int32_t>(g.vocab)) {
        failure("decode: noSpeechToken " + std::to_string(noSpeechToken) + " is outside the vocabulary");
        return -1;
    }
    if (cycleMaxDistinct < 1) {
        failure("decode: cycleMaxDistinct must be >= 1");
        return -1;
    }
    if (!jStats || env->GetArrayLength(jStats) < kStatSize) {
        failure("decode: stats must have room for " + std::to_string(kStatSize) + " values");
        return -1;
    }

    float *logits = g.logits.data();
    const uint32_t promptLen = static_cast<uint32_t>(prompt.size());
    std::vector<int32_t> out(static_cast<size_t>(maxTokens), 0);
    int32_t count = 0;
    bool hitEot = false;
    const auto t0 = Clock::now();
    g.runMs = 0.0;
    g.ioMs = 0.0;
    if (g.diag) {
        LOGDIAG("npu-debug: prompt ids=%s len=%u maxTokens=%d positions=0..%u vocab=%u mask=%u",
                diagIdList(prompt, 8).c_str(), promptLen, maxTokens, lastPosition, g.vocab, g.maskLen);
    }

    int32_t firstGenerated = -1;
    uint32_t lastPositionRun = 0;
    uint32_t stepsRun = 0;
    const int32_t timestampBegin = static_cast<int32_t>(g.vocab) - kTimestampSlots;
    float noSpeechProb = kStatUnreadable;
    double avgLogprob = 0.0;
    int32_t scored = 0;
    double entropyLast = 0.0;
    int32_t distinctLast = 0;
    bool entropyMeasured = false;
    size_t rungUsed = 0;
    float terminator = kTermEot;
    for (size_t rung = 0; rung < temperatures.size(); ++rung) {
        const float temperature = temperatures[rung];
        std::mt19937 rng(0x5EEDu ^ static_cast<uint32_t>(rung));   // qnn_asr.cpp's seeding, per rung
        err = zeroSelfKvLocked();
        if (!err.empty()) {
            failure("decode: " + err);
            return -2;
        }
        count = 0;
        int32_t textCount = 0;
        firstGenerated = -1;
        hitEot = false;
        double sumLogprob = 0.0;
        int32_t scoredIds = 0;
        bool failedEntropy = false;
        int32_t windowStart = 0;
        int32_t cutTo = 0;
        int32_t next = prompt[0];
        entropyLast = 0.0;
        distinctLast = 0;
        entropyMeasured = false;
        rungUsed = rung;

        for (uint32_t position = 0; position <= lastPosition; ++position) {
            const int32_t tokenIn = (position < promptLen) ? prompt[position] : next;
            const int inSetForStep = g.selfInSet;
            const auto s0 = Clock::now();
            err = decodeStepLocked(tokenIn, position);
            if (!err.empty()) {
                failure("decode: " + err);
                return -2;
            }
            lastPositionRun = position;
            ++stepsRun;
            err = checkFiniteLocked(position);
            if (!err.empty()) {
                failure("decode: " + err);
                return -4;
            }
            if (g.diag) {
                LOGDIAG("npu-debug: steptime pos=%u inSet=%d ms=%.2f", position, inSetForStep, msSince(s0));
            }

            // p(<|nospeech|>): once, at the SOT step of the first rung, from the RAW logits.
            if (rung == 0 && position == 0) {
                noSpeechProb = noSpeechProbabilityF(logits, g.vocab, noSpeechToken);
            }

            const bool trace = g.diag && position <= promptLen;
            int32_t rawArgmax = -1;
            float rawLo = 0.0f, rawHi = 0.0f;
            if (trace) {
                rawLo = rawHi = logits[0];
                rawArgmax = 0;
                for (uint32_t i = 1; i < g.vocab; ++i) {
                    if (logits[i] < rawLo) rawLo = logits[i];
                    if (logits[i] > rawHi) { rawHi = logits[i]; rawArgmax = static_cast<int32_t>(i); }
                }
            }
            char inName[24], rawName[24], maskedName[24];

            if (position + 1 < promptLen) {
                // Still feeding the prompt: this argmax is discarded, the self-KV slot is the point.
                if (trace) {
                    LOGDIAG("npu-debug: step pos=%u in=%s inSet=%d raw[min=%.3f max=%.3f argmax=%s] "
                            "masked=prefill-skipped", position, diagToken(tokenIn, inName, sizeof(inName)),
                            inSetForStep, rawLo, rawHi, diagToken(rawArgmax, rawName, sizeof(rawName)));
                }
                bindSelfKvLocked(1 - g.selfInSet);
                continue;
            }

            const bool applyBegin = (position == promptLen - 1);
            const int32_t tok = (temperature == 0.0f)
                    ? suppressThenArgmaxF(logits, g.vocab, suppress, beginSuppress, applyBegin)
                    : suppressThenSampleF(logits, g.vocab, suppress, beginSuppress, applyBegin, temperature, rng);
            if (trace) {
                LOGDIAG("npu-debug: step pos=%u in=%s inSet=%d raw[min=%.3f max=%.3f argmax=%s] masked=%s "
                        "beginSuppress=%d", position, diagToken(tokenIn, inName, sizeof(inName)), inSetForStep,
                        rawLo, rawHi, diagToken(rawArgmax, rawName, sizeof(rawName)),
                        diagToken(tok, maskedName, sizeof(maskedName)), applyBegin ? 1 : 0);
            }
            if (tok < 0) {
                failure("decode: every logit is at the floor at position " + std::to_string(position) +
                        "; the graph produced no token");
                return -3;
            }
            // avg_logprob IS AN AVERAGE OVER TEXT (and the EOT): the timestamps stay out of both the
            // sum and the count, for qnn_asr.cpp's reason - the no-speech gate was tuned on text alone.
            if (tok < timestampBegin) {
                sumLogprob += maskedLogprobF(logits, g.vocab, temperature, tok);
                ++scoredIds;
            }
            if (firstGenerated < 0) firstGenerated = tok;
            if (tok == kEotToken) {
                hitEot = true;
                break;
            }
            out[static_cast<size_t>(count)] = tok;
            ++count;
            if (tok < kEotToken) ++textCount;
            // THE REPETITION GUARD, in-loop, over TEXT ids only (4.11 Task 3).
            if (textCount > kEntropyWindow) {
                entropyLast = trailingEntropy(out, count, &distinctLast, &windowStart);
                entropyMeasured = true;
                if (entropyLast < entropyThold && distinctLast <= cycleMaxDistinct) {
                    failedEntropy = true;
                    cutTo = windowStart;
                    break;
                }
            }
            if (count >= maxTokens) break;
            next = tok;
            bindSelfKvLocked(1 - g.selfInSet);
        }

        scored = scoredIds;
        avgLogprob = scored > 0 ? sumLogprob / scored : 0.0;
        // whisper.cpp:7835 - re-decode hotter only when the model does not think the segment silent.
        const bool lowConfidence = scored > 0 && avgLogprob < static_cast<double>(logprobThold) &&
                                   noSpeechProb < noSpeechThold;
        const bool lastRung = rung + 1 == temperatures.size();
        if (!failedEntropy && !lowConfidence) break;
        if (lastRung) {
            if (failedEntropy) {
                count = cutTo;
                terminator = kTermCut;
            }
            break;
        }
    }

    if (terminator != kTermCut) {
        terminator = hitEot ? kTermEot : (count >= maxTokens ? kTermBudget : kTermCap);
    }
    if (count > 0) env->SetIntArrayRegion(jOut, 0, count, reinterpret_cast<const jint *>(out.data()));

    float stats[kStatSize];
    stats[kStatNoSpeechProb] = noSpeechProb;
    stats[kStatAvgLogprob] = scored > 0 ? static_cast<float>(avgLogprob) : NAN;
    stats[kStatEntropy] = entropyMeasured ? static_cast<float>(entropyLast) : NAN;
    stats[kStatRung] = static_cast<float>(rungUsed);
    stats[kStatTerminator] = terminator;
    stats[kStatSteps] = static_cast<float>(stepsRun);
    env->SetFloatArrayRegion(jStats, 0, kStatSize, stats);

    const double ms = msSince(t0);
    const char *termName = terminator == kTermCut ? "cut" : (hitEot ? "eot" : (count >= maxTokens ? "count" : "cap"));
    LOGI("decode: %d tokens in %.1f ms (%.2f ms/token), terminated by %s (apu: %s) nsp=%.2f lp=%.2f ent=%.2f "
         "rung=%zu steps=%u step=%.2f ms (run %.2f, io %.2f)",
         count, ms, count > 0 ? ms / count : 0.0,
         terminator == kTermCut ? "the repetition cut" :
         (hitEot ? "EOT" : (count >= maxTokens ? "the token budget" : "the position cap")),
         perfModeName(g.performanceMode), stats[kStatNoSpeechProb], stats[kStatAvgLogprob], stats[kStatEntropy],
         rungUsed, stepsRun, stepsRun ? ms / stepsRun : 0.0, stepsRun ? g.runMs / stepsRun : 0.0,
         stepsRun ? g.ioMs / stepsRun : 0.0);
    if (g.diag) {
        char firstName[24];
        LOGDIAG("npu-debug: result count=%d first=%s terminator=%s steps=%u posFirst=0 posLast=%u",
                count, diagToken(firstGenerated, firstName, sizeof(firstName)), termName, stepsRun, lastPositionRun);
    }
    g.lastError.clear();
    return count;
}

/// ONE decode step at position 0 with SOT, the argmax restricted to THIS SESSION'S language band
/// (derived from vocab), through band_scan.h's float scan; then both self-KV sets zeroed. The same
/// contract, refusals and always-on line prefix as QnnAsrNative.nativeDetectLanguage; the margin is
/// already in log-odds (scale 1.0).
extern "C" JNIEXPORT jint JNICALL
Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeDetectLanguage(
        JNIEnv *env, jobject /* this */) {
    (void) env;
    std::lock_guard<std::mutex> lock(g.mu);
    if (!g.initialised) {
        failure("detect: session not initialised");
        return -1;
    }
    if (!g.encoded) {
        failure("detect: no encoded segment - call nativeEncode first. This reads the encoder's "
                "cross-KV in place and would otherwise detect the previous segment's language.");
        return -1;
    }
    std::string err = zeroSelfKvLocked();
    if (err.empty()) err = decodeStepLocked(kSotToken, 0);
    if (!err.empty()) {
        failure("detect: " + err);
        return -2;
    }
    err = checkFiniteLocked(0);
    if (!err.empty()) {
        failure("detect: " + err);
        return -4;
    }
    const BandTop2F band = scanBandTop2(g.logits.data(), static_cast<uint32_t>(g.langTokenFirst),
                                        static_cast<uint32_t>(g.langTokenLast) + 1, kLogitFloor);
    const int32_t best = band.best;
    const int32_t runnerUp = band.second;
    if (g.diag) {
        char bestName[24], runnerName[24];
        LOGDIAG("npu-debug: detect band=[%d..%d] best=%s val=%.3f runnerUp=%s val=%.3f", g.langTokenFirst,
                g.langTokenLast, diagToken(best, bestName, sizeof(bestName)), band.bestVal,
                diagToken(runnerUp, runnerName, sizeof(runnerName)), band.secondVal);
    }
    // The step wrote position 0's slot; the real decode starts from an empty cache.
    err = zeroSelfKvLocked();
    if (!err.empty()) {
        failure("detect: " + err);
        return -2;
    }
    if (best < 0) {
        failure("detect: every language logit is at the floor; no language was produced");
        return -3;
    }
    const float margin = runnerUp >= 0 ? kLogitScale * (band.bestVal - band.secondVal) : kStatUnreadable;
    char tiesNote[24] = "";
    if (band.ties > 1) snprintf(tiesNote, sizeof(tiesNote), " ties=%u", band.ties);
    char bestTokenName[24], secondTokenName[24];
    LOGI("detect: language token %s (offset %d in the language block) second=%s margin=%.3f%s",
         diagToken(best, bestTokenName, sizeof(bestTokenName)), best - g.langTokenFirst,
         diagToken(runnerUp, secondTokenName, sizeof(secondTokenName)), margin, tiesNote);
    g.lastError.clear();
    return best;
}

/// Turns the `npu-debug:` instrumentation on or off; off until this says otherwise.
extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeSetDiag(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    (void) env;
    std::lock_guard<std::mutex> lock(g.mu);
    g.diag = (enabled == JNI_TRUE);
    LOGDIAG("npu-debug: instrumentation %s", g.diag ? "ENABLED" : "disabled");
}

/// The last "stage: detail" any entry point recorded, or "".
extern "C" JNIEXPORT jstring JNICALL
Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeLastError(
        JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g.mu);
    return env->NewStringUTF(g.lastError.c_str());
}

/// The live session's epoch, or 0 when there is none. A reader and nothing else.
extern "C" JNIEXPORT jlong JNICALL
Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeEpoch(
        JNIEnv *env, jobject /* this */) {
    (void) env;
    std::lock_guard<std::mutex> lock(g.mu);
    return static_cast<jlong>(g.epoch);
}

/// Tears the session down IF [epoch] names the live one (0 and any stale epoch are logged and
/// ignored - qnn_asr.cpp's 4.1 L1 guard, for the same interleaving). Frees the models and buffers
/// only; the environment and the adapter stay for the next arm.
extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_npu_LiteRtAsrNative_nativeRelease(
        JNIEnv *env, jobject /* this */, jlong epoch) {
    (void) env;
    std::lock_guard<std::mutex> lock(g.mu);
    const uint64_t want = static_cast<uint64_t>(epoch);
    if (want == 0 || want != g.epoch) {
        LOGDIAG("nativeRelease: epoch %llu is not the live session (%llu) - ignored",
                (unsigned long long) want, (unsigned long long) g.epoch);
        return;
    }
    releaseLocked();
    LOGDIAG("nativeRelease complete (epoch %llu); the environment and the adapter stay loaded",
            (unsigned long long) want);
}
