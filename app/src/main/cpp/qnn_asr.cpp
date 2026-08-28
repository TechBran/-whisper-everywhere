// libqnnasr.so - the 4.0 NPU tier's seam onto Qualcomm's QAIRT (QNN) C API.
//
// No ONNX Runtime. We dlopen() libQnnSystem.so and libQnnHtp.so, reach every entry point through
// the provider function-pointer tables (exactly as Qualcomm's own samples do), and feed the two
// precompiled QAIRT context binaries - 127 MB encoder, 215 MB decoder - straight into
// QnnContext_createFromBinary.
//
// Nothing here links against QNN at build time, so a different QAIRT .so set can be dropped in
// without relinking, and a missing library is a readable message rather than a load-time crash.
//
// Every QNN call is checked. On failure the stage name, the numeric error code and the decoded
// error family go back to Kotlin as "stage: detail" - the owner has no adb, so an unexplained
// failure is a wasted round trip.
//
// PROVENANCE: the helper block below (qnnErr, the tensor accessors, tensorRepoint,
// tensorSetClientBuf, elementSize, dtypeName, tensorBytes, shapeStr, AlignedBuf) is ported from the
// proven G1 spike, which measured 404.6 ms sustained encoder latency over 9 device runs. Those
// helpers encode four lessons that each cost a device round trip; the comments naming them are part
// of the port and must not be trimmed.
//
// SCOPE OF THIS FILE TODAY (Q1): probe, load, enumerate, log. nativeEncode / nativeInputQuant land
// in Q3; nativeDetectLanguage / nativeDecodeSegment in Q4. What exists here is deliberately the
// part that can be reasoned about before any of it has run on device, which happens at Q10a.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cerrno>
#include <chrono>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#if !defined(__has_include)
#error "compiler must support __has_include"
#endif

#if !__has_include("QnnInterface.h")
#error "QNN headers not found. Run tools/fetch_qnn_headers.py to populate app/src/main/cpp/include/QNN."
#endif

#include "QnnInterface.h"
#include "QnnBackend.h"
#include "QnnCommon.h"
#include "QnnContext.h"
#include "QnnDevice.h"
#include "QnnGraph.h"
#include "QnnTensor.h"
#include "QnnTypes.h"
#include "System/QnnSystemInterface.h"
#include "System/QnnSystemContext.h"
// HTP/QnnHtpDevice.h + HTP/QnnHtpPerfInfrastructure.h arrive in Q3 with the sustained power vote.
// Q1 reaches the HTP entirely through the generic provider table, so it needs neither.

#define TAG "WE-NPU"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

using Clock = std::chrono::steady_clock;

double msSince(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}

/// 64-byte aligned client buffer. The HTP prefers aligned memory for RAW client buffers, and
/// owning the allocation explicitly keeps the pointer stable for the whole session.
///
/// Lesson 7: own the buffers, 64-byte aligned, RAII, copy deleted. Q3 and Q4 allocate ~34 MiB of
/// these (cross-KV, self-KV ping-pong, mel, logits) and alias several of them across two graphs;
/// a copy that silently double-freed, or a buffer whose address moved between bind and execute,
/// would be a use-after-free on the DSP side of a FastRPC call.
struct AlignedBuf {
    void *p = nullptr;
    size_t n = 0;

    bool alloc(size_t bytes) {
        free();
        void *q = nullptr;
        if (posix_memalign(&q, 64, bytes) != 0 || !q) return false;
        memset(q, 0, bytes);
        p = q;
        n = bytes;
        return true;
    }

    void free() {
        if (p) ::free(p);
        p = nullptr;
        n = 0;
    }

    ~AlignedBuf() { free(); }

    AlignedBuf() = default;
    AlignedBuf(const AlignedBuf &) = delete;
    AlignedBuf &operator=(const AlignedBuf &) = delete;
    AlignedBuf(AlignedBuf &&o) noexcept : p(o.p), n(o.n) { o.p = nullptr; o.n = 0; }
};

// ---------------------------------------------------------------- error decoding

/// QNN splits its error space by subsystem; the raw code alone is close to unreadable, so name
/// the family and keep the number for the vendor.
std::string qnnErr(Qnn_ErrorHandle_t e) {
    char buf[160];
    const char *family = "UNKNOWN";
    auto code = static_cast<uint32_t>(e);
    if (e == QNN_SUCCESS) family = "SUCCESS";
    else if (code >= QNN_BACKEND_MIN_ERROR && code < QNN_BACKEND_MAX_ERROR) family = "BACKEND";
    else if (code >= QNN_CONTEXT_MIN_ERROR && code < QNN_CONTEXT_MAX_ERROR) family = "CONTEXT";
    else if (code >= QNN_GRAPH_MIN_ERROR && code < QNN_GRAPH_MAX_ERROR) family = "GRAPH";
    else if (code >= QNN_TENSOR_MIN_ERROR && code < QNN_TENSOR_MAX_ERROR) family = "TENSOR";
    else if (code >= QNN_DEVICE_MIN_ERROR && code < QNN_DEVICE_MAX_ERROR) family = "DEVICE";
    else if (code >= QNN_PROPERTY_MIN_ERROR && code < QNN_PROPERTY_MAX_ERROR) family = "PROPERTY";
    snprintf(buf, sizeof(buf), "%s (0x%" PRIx64 " / %" PRIu64 ")", family,
             static_cast<uint64_t>(e), static_cast<uint64_t>(e));
    return buf;
}

/// dlerror() CLEARS the error it reports, so the spike's `dlerror() ? dlerror() : "?"` idiom always
/// produced "?" on a real failure - the first call consumed the message, the second returned null.
/// Read it exactly once.
std::string dlErr() {
    const char *e = dlerror();
    return e ? std::string(e) : std::string("no dlerror detail");
}

// ---------------------------------------------------------------- tensor helpers

// Qnn_Tensor_t is a versioned union. Read through the version tag rather than assuming v1, so a
// newer QAIRT that bumps the tensor version does not silently read garbage.
/// The accessors below fall back to zero/null on an unknown version, which would present as a
/// bogus zero-sized tensor rather than as an error. Check explicitly first and fail loudly.
///
/// LESSON 3, and it cost a device round trip: every v2 branch here was once wrapped in
/// `#ifdef QNN_TENSOR_VERSION_2`. QNN_TENSOR_VERSION_* are ENUM CONSTANTS, not macros, so #ifdef
/// was always false and all v2 handling was compiled out -- while the error text still claimed
/// "reader knows 1,2". Both of this app's context binaries carry v2 tensors, so the build rejected
/// a version it advertised as supported. Never use #ifdef to probe an enumerator.
/// NpuNativeContractTest pins the absence of any preprocessor conditional in this function.
bool tensorVersionKnown(const Qnn_Tensor_t &t) {
    if (t.version == QNN_TENSOR_VERSION_1) return true;
    if (t.version == QNN_TENSOR_VERSION_2) return true;
    return false;
}

uint32_t tensorRank(const Qnn_Tensor_t &t) {
    if (t.version == QNN_TENSOR_VERSION_1) return t.v1.rank;
    if (t.version == QNN_TENSOR_VERSION_2) return t.v2.rank;
    return 0;
}

const uint32_t *tensorDims(const Qnn_Tensor_t &t) {
    if (t.version == QNN_TENSOR_VERSION_1) return t.v1.dimensions;
    if (t.version == QNN_TENSOR_VERSION_2) return t.v2.dimensions;
    return nullptr;
}

const char *tensorName(const Qnn_Tensor_t &t) {
    if (t.version == QNN_TENSOR_VERSION_1) return t.v1.name;
    if (t.version == QNN_TENSOR_VERSION_2) return t.v2.name;
    return "?";
}

Qnn_DataType_t tensorDataType(const Qnn_Tensor_t &t) {
    if (t.version == QNN_TENSOR_VERSION_1) return t.v1.dataType;
    if (t.version == QNN_TENSOR_VERSION_2) return t.v2.dataType;
    return QNN_DATATYPE_UNDEFINED;
}

/// Point a copied descriptor at storage WE own, so it survives systemContextFree().
void tensorRepoint(Qnn_Tensor_t &t, std::string &name, std::vector<uint32_t> &dims) {
    if (t.version == QNN_TENSOR_VERSION_1) {
        t.v1.name = name.c_str();
        t.v1.dimensions = dims.empty() ? nullptr : dims.data();
    } else if (t.version == QNN_TENSOR_VERSION_2) {
        t.v2.name = name.c_str();
        t.v2.dimensions = dims.empty() ? nullptr : dims.data();
        // Dynamic dimensions and sparsity are not used by these graphs; null them rather than
        // leave dangling pointers into the freed system context.
        t.v2.isDynamicDimensions = nullptr;
    }
}

/// Unused in Q1 - Q3 binds the encoder's mel and cross-KV buffers with it, Q4 re-binds the
/// decoder's self-KV ping-pong 48 times per token. Ported now because it belongs with the accessors
/// above and carries the same versioned-union discipline.
[[maybe_unused]] void tensorSetClientBuf(Qnn_Tensor_t &t, void *data, uint32_t bytes) {
    if (t.version == QNN_TENSOR_VERSION_1) {
        t.v1.memType = QNN_TENSORMEMTYPE_RAW;
        t.v1.clientBuf.data = data;
        t.v1.clientBuf.dataSize = bytes;
    }
    else if (t.version == QNN_TENSOR_VERSION_2) {
        t.v2.memType = QNN_TENSORMEMTYPE_RAW;
        t.v2.clientBuf.data = data;
        t.v2.clientBuf.dataSize = bytes;
    }
}

uint32_t elementSize(Qnn_DataType_t dt) {
    switch (dt) {
        case QNN_DATATYPE_INT_8:
        case QNN_DATATYPE_UINT_8:
        case QNN_DATATYPE_SFIXED_POINT_8:
        case QNN_DATATYPE_UFIXED_POINT_8:
        case QNN_DATATYPE_BOOL_8:
            return 1;
        case QNN_DATATYPE_INT_16:
        case QNN_DATATYPE_UINT_16:
        case QNN_DATATYPE_SFIXED_POINT_16:
        case QNN_DATATYPE_UFIXED_POINT_16:
        case QNN_DATATYPE_FLOAT_16:
            return 2;
        case QNN_DATATYPE_INT_32:
        case QNN_DATATYPE_UINT_32:
        case QNN_DATATYPE_SFIXED_POINT_32:
        case QNN_DATATYPE_UFIXED_POINT_32:
        case QNN_DATATYPE_FLOAT_32:
            return 4;
        case QNN_DATATYPE_INT_64:
        case QNN_DATATYPE_UINT_64:
        case QNN_DATATYPE_FLOAT_64:
            return 8;
        default:
            return 0;
    }
}

const char *dtypeName(Qnn_DataType_t dt) {
    switch (dt) {
        case QNN_DATATYPE_UFIXED_POINT_16: return "ufixed16";
        case QNN_DATATYPE_UFIXED_POINT_8:  return "ufixed8";
        case QNN_DATATYPE_SFIXED_POINT_16: return "sfixed16";
        case QNN_DATATYPE_SFIXED_POINT_8:  return "sfixed8";
        case QNN_DATATYPE_UINT_16:         return "uint16";
        case QNN_DATATYPE_UINT_8:          return "uint8";
        case QNN_DATATYPE_INT_32:          return "int32";
        case QNN_DATATYPE_FLOAT_32:        return "float32";
        case QNN_DATATYPE_FLOAT_16:        return "float16";
        default:                           return "other";
    }
}

uint64_t tensorBytes(const Qnn_Tensor_t &t) {
    uint32_t rank = tensorRank(t);
    const uint32_t *dims = tensorDims(t);
    if (!dims || rank == 0) return 0;
    uint64_t n = 1;
    for (uint32_t i = 0; i < rank; ++i) n *= dims[i];
    return n * elementSize(tensorDataType(t));
}

std::string shapeStr(const Qnn_Tensor_t &t) {
    std::string s = "[";
    uint32_t rank = tensorRank(t);
    const uint32_t *dims = tensorDims(t);
    for (uint32_t i = 0; i < rank && dims; ++i) {
        if (i) s += ",";
        s += std::to_string(dims[i]);
    }
    return s + "]";
}

// ---------------------------------------------------------------- session state

/// One deserialised graph (encoder or decoder) and the descriptors we own for it.
///
/// NEVER MOVED, NEVER COPIED, NEVER STORED IN A GROWABLE CONTAINER. `ownedNames` holds the strings
/// the descriptors' `name` pointers point AT, and libc++ stores a short string inline in the object
/// - "input_features" is 14 characters and lives in the small-string buffer. Moving a GraphSlot,
/// or letting a vector<GraphSlot> reallocate, would move that buffer and dangle every name pointer
/// while leaving the code looking correct. Two fixed members below, and reserve() before fill.
struct GraphSlot {
    std::string name;
    Qnn_ContextHandle_t context = nullptr;
    Qnn_GraphHandle_t graph = nullptr;
    std::vector<Qnn_Tensor_t> inputs;
    std::vector<Qnn_Tensor_t> outputs;
    std::vector<std::string> ownedNames;
    std::vector<std::vector<uint32_t>> ownedDims;

    GraphSlot() = default;
    GraphSlot(const GraphSlot &) = delete;
    GraphSlot &operator=(const GraphSlot &) = delete;

    void clear() {
        name.clear();
        context = nullptr;
        graph = nullptr;
        inputs.clear();
        outputs.clear();
        ownedNames.clear();
        ownedDims.clear();
    }
};

/// What the plan's asset analysis says each graph must look like. Logged and compared, not
/// enforced: an AI Hub asset regenerated with a different IO shape should produce a loud, named
/// warning here rather than a hard failure in a build that has not yet reached the device.
/// Lesson 4's addendum - verify every precondition the design rests on, not just the versioned ones.
struct GraphExpectation {
    const char *label;
    uint32_t numIn;
    uint32_t numOut;
    uint64_t inBytes;
    uint64_t outBytes;
};

constexpr GraphExpectation kEncoderExpectation{"encoder", 1, 24, 480000, 27648000};
constexpr GraphExpectation kDecoderExpectation{"decoder", 51, 25, 31316376, 3771698};

struct NpuState {
    std::mutex mu;

    void *sysLib = nullptr;
    void *htpLib = nullptr;
    QNN_INTERFACE_VER_TYPE qnn{};
    QNN_SYSTEM_INTERFACE_VER_TYPE sys{};
    bool ifaceReady = false;

    QnnSystemContext_Handle_t sysCtx = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;

    GraphSlot enc;
    GraphSlot dec;
    bool initialised = false;

    std::string lastError;
};

NpuState g;

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

/// Records [err] as the last error and hands it straight back. Every String-returning entry point
/// funnels through here so nativeLastError() and the return value can never disagree.
std::string failure(const std::string &err) {
    g.lastError = err;
    LOGE("%s", err.c_str());
    return err;
}

// ---------------------------------------------------------------- library + provider tables

/// dlopen one QNN library: absolute path first, then by SONAME.
///
/// THE SONAME FALLBACK IS NOT BELT-AND-BRACES, IT IS THE PATH THAT ACTUALLY WORKS HERE. This app
/// packages with extractNativeLibs=false, and under that packaging `nativeLibraryDir` holds NO REAL
/// FILES - the linker maps each .so straight out of the APK. It cost this project a crash loop once
/// already (docs/PLAN.md: "always load by SONAME on Android", after
/// ggml_backend_load_all_from_path scanned that directory and found nothing). An absolute-path
/// dlopen of libDir + "/libQnnHtp.so" therefore fails on the shipping packaging, while the SONAME
/// form resolves through the app's own linker namespace either way.
///
/// The absolute path is still tried first, and deliberately: if the packaging is ever flipped to
/// extractNativeLibs=true - which the DSP-side skel loader independently requires, see the note in
/// loadInterfacesLocked - the path form is the one that names a real file, and both spellings then
/// land on the same library anyway.
void *dlopenQnn(const std::string &libDir, const char *soname, std::string &err) {
    const std::string path = libDir + "/" + soname;
    void *h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (h) return h;
    const std::string pathErr = dlErr();

    h = dlopen(soname, RTLD_NOW | RTLD_LOCAL);
    if (h) {
        LOGW("%s: absolute path failed (%s); loaded by SONAME instead", soname, pathErr.c_str());
        return h;
    }
    err = std::string("dlopen(") + soname + "): path=[" + pathErr + "] soname=[" + dlErr() + "]";
    return nullptr;
}

/// dlopen both QNN libraries and resolve their provider tables. Idempotent: the handles are process
/// -global and dlopen refcounts, so nativeProbe followed by nativeInit costs one real load.
///
/// This is ALL nativeProbe does. No backend, no device, no context - the probe is the gate Q6 calls
/// on every device that passes NpuGate's SoC allowlist, including devices where the answer is no.
std::string loadInterfacesLocked(const std::string &libDir) {
    if (g.ifaceReady) return "";

    std::string err;
    if (!g.sysLib) {
        g.sysLib = dlopenQnn(libDir, "libQnnSystem.so", err);
        if (!g.sysLib) return err;
    }
    if (!g.htpLib) {
        // OPEN ITEM FOR Q10a, and it is not fixable from this file. Loading libQnnHtp.so is only
        // half the problem: the backend then pulls its DSP-side skel (libQnnHtpV75Skel.so) through
        // the FastRPC loader, which searches ADSP_LIBRARY_PATH - set in
        // WhisperEverywhereApp.onCreate to point at nativeLibraryDir - and which needs a REAL FILE
        // on disk. Under extractNativeLibs=false there is none. The spike ran with
        // useLegacyPackaging=true (its report flags the app-wide flip as "a real decision for the
        // shipping app"), and the 4.0 plan does not carry that decision. Whatever the answer is -
        // the manifest flip, or shipping the skel in the asset pack and pointing
        // ADSP_LIBRARY_PATH there - it is owner-facing and belongs to the tier's packaging, not to
        // the seam. Until then this dlopen may well succeed and the HTP still fail to come up.
        g.htpLib = dlopenQnn(libDir, "libQnnHtp.so", err);
        if (!g.htpLib) return err;
    }

    using GetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t ***, uint32_t *);
    using GetSysProvidersFn = Qnn_ErrorHandle_t (*)(const QnnSystemInterface_t ***, uint32_t *);

    auto getProviders = reinterpret_cast<GetProvidersFn>(
            dlsym(g.htpLib, "QnnInterface_getProviders"));
    if (!getProviders) return "dlsym(QnnInterface_getProviders): " + dlErr();
    auto getSysProviders = reinterpret_cast<GetSysProvidersFn>(
            dlsym(g.sysLib, "QnnSystemInterface_getProviders"));
    if (!getSysProviders) return "dlsym(QnnSystemInterface_getProviders): " + dlErr();

    const QnnInterface_t **providers = nullptr;
    uint32_t numProviders = 0;
    Qnn_ErrorHandle_t e = getProviders(&providers, &numProviders);
    if (e != QNN_SUCCESS || !providers || numProviders == 0) {
        return "QnnInterface_getProviders: " + qnnErr(e) + " providers=" +
               std::to_string(numProviders);
    }
    LOGI("QNN providers: %u; provider[0] name=%s backendId=%u api=%u.%u.%u",
         numProviders,
         providers[0]->providerName ? providers[0]->providerName : "?",
         providers[0]->backendId,
         providers[0]->apiVersion.coreApiVersion.major,
         providers[0]->apiVersion.coreApiVersion.minor,
         providers[0]->apiVersion.coreApiVersion.patch);

    const QnnSystemInterface_t **sysProviders = nullptr;
    uint32_t numSysProviders = 0;
    e = getSysProviders(&sysProviders, &numSysProviders);
    if (e != QNN_SUCCESS || !sysProviders || numSysProviders == 0) {
        return "QnnSystemInterface_getProviders: " + qnnErr(e) + " providers=" +
               std::to_string(numSysProviders);
    }
    LOGI("QNN system providers: %u", numSysProviders);

    g.qnn = providers[0]->QNN_INTERFACE_VER_NAME;
    g.sys = sysProviders[0]->QNN_SYSTEM_INTERFACE_VER_NAME;
    g.ifaceReady = true;
    return "";
}

// ---------------------------------------------------------------- context loading

std::string readWholeFile(const std::string &path, std::vector<uint8_t> &out) {
    FILE *f = fopen(path.c_str(), "rb");
    if (!f) return "fopen: " + path + " (" + strerror(errno) + ")";
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return "fseek: " + path;
    }
    long size = ftell(f);
    if (size <= 0) {
        fclose(f);
        return "ftell: " + path + " reported " + std::to_string(size) + " bytes";
    }
    rewind(f);
    out.resize(static_cast<size_t>(size));
    size_t got = fread(out.data(), 1, out.size(), f);
    fclose(f);
    if (got != out.size()) {
        return "fread: " + path + " short read " + std::to_string(got) + "/" +
               std::to_string(out.size());
    }
    return "";
}

/// Deserialise one context binary into [slot]: parse its self-describing metadata, log every
/// versioned struct it carries, deep-copy the IO descriptors into storage we own, then create the
/// QNN context and retrieve the graph.
///
/// The binary describes itself; nothing here guesses at the IO. What Q3 and Q4 build on top is a
/// name -> index map over slot.inputs / slot.outputs (lesson 5: the decoder has 51 inputs and
/// binding by index would be a silent mis-wire).
std::string loadGraphSlot(GraphSlot &slot, const std::string &path,
                          const GraphExpectation &expect) {
    std::vector<uint8_t> blob;
    auto t0 = Clock::now();
    std::string err = readWholeFile(path, blob);
    if (!err.empty()) return expect.label + std::string(" read: ") + err;
    LOGI("%s: %zu bytes read in %.0f ms", expect.label, blob.size(), msSince(t0));

    const QnnSystemContext_BinaryInfo_t *binInfo = nullptr;
    Qnn_ContextBinarySize_t binInfoSize = 0;
    t0 = Clock::now();
    Qnn_ErrorHandle_t e = g.sys.systemContextGetBinaryInfo(
            g.sysCtx, blob.data(), blob.size(), &binInfo, &binInfoSize);
    if (e != QNN_SUCCESS || !binInfo) {
        return expect.label + std::string(" systemContextGetBinaryInfo: ") + qnnErr(e);
    }
    LOGI("%s: binary info parsed in %.0f ms", expect.label, msSince(t0));

    // The struct versions are printed even on success: the AI Hub assets are regenerated
    // periodically, and a bumped version is the thing that silently breaks a reader (lesson 4).
    LOGI("%s: binary info version %d", expect.label, static_cast<int>(binInfo->version));

    const QnnSystemContext_GraphInfo_t *graphs = nullptr;
    uint32_t numGraphs = 0;
    const char *buildId = nullptr;
    const char *socVersion = nullptr;

    // V1/V2/V3 all expose the same graph list; V2 and V3 only add fields around it. Read each
    // through its own union member -- they are distinct types, so aliasing one for another would
    // be undefined behaviour even though the leading layout happens to line up.
    switch (binInfo->version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
            graphs = binInfo->contextBinaryInfoV1.graphs;
            numGraphs = binInfo->contextBinaryInfoV1.numGraphs;
            buildId = binInfo->contextBinaryInfoV1.buildId;
            break;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
            graphs = binInfo->contextBinaryInfoV2.graphs;
            numGraphs = binInfo->contextBinaryInfoV2.numGraphs;
            buildId = binInfo->contextBinaryInfoV2.buildId;
            socVersion = binInfo->contextBinaryInfoV2.socVersion;
            break;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
            graphs = binInfo->contextBinaryInfoV3.graphs;
            numGraphs = binInfo->contextBinaryInfoV3.numGraphs;
            buildId = binInfo->contextBinaryInfoV3.buildId;
            socVersion = binInfo->contextBinaryInfoV3.socVersion;
            LOGI("%s: socModel=%u contextBlobSize=%" PRIu64 " numContextTensors=%u",
                 expect.label,
                 binInfo->contextBinaryInfoV3.socModel,
                 binInfo->contextBinaryInfoV3.contextBlobSize,
                 binInfo->contextBinaryInfoV3.numContextTensors);
            break;
        default:
            return expect.label + std::string(" binary info version: unsupported version ") +
                   std::to_string(static_cast<int>(binInfo->version)) + " (reader knows 1,2,3)";
    }
    // R7 lives on this line: the blobs were produced by QAIRT 2.45 and this runtime is 2.49. The
    // pairing is proven for the encoder only; the decoder has never been deserialised under 2.49.
    LOGI("%s: produced by QAIRT build %s (runtime headers are v2.49.0.260730134355), socVersion %s",
         expect.label, buildId ? buildId : "?", socVersion ? socVersion : "?");

    if (!graphs || numGraphs == 0) return expect.label + std::string(" binary info: no graphs");
    if (numGraphs != 1) {
        LOGW("%s: binary carries %u graphs; graph[0] is the one this seam drives",
             expect.label, numGraphs);
    }

    LOGI("%s: graph info version %d", expect.label, static_cast<int>(graphs[0].version));

    const char *gName = nullptr;
    uint32_t numIn = 0, numOut = 0;
    Qnn_Tensor_t *gIn = nullptr;
    Qnn_Tensor_t *gOut = nullptr;

    switch (graphs[0].version) {
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
            gName = graphs[0].graphInfoV1.graphName;
            numIn = graphs[0].graphInfoV1.numGraphInputs;
            gIn = graphs[0].graphInfoV1.graphInputs;
            numOut = graphs[0].graphInfoV1.numGraphOutputs;
            gOut = graphs[0].graphInfoV1.graphOutputs;
            break;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
            gName = graphs[0].graphInfoV2.graphName;
            numIn = graphs[0].graphInfoV2.numGraphInputs;
            gIn = graphs[0].graphInfoV2.graphInputs;
            numOut = graphs[0].graphInfoV2.numGraphOutputs;
            gOut = graphs[0].graphInfoV2.graphOutputs;
            break;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3:
            gName = graphs[0].graphInfoV3.graphName;
            numIn = graphs[0].graphInfoV3.numGraphInputs;
            gIn = graphs[0].graphInfoV3.graphInputs;
            numOut = graphs[0].graphInfoV3.numGraphOutputs;
            gOut = graphs[0].graphInfoV3.graphOutputs;
            break;
        default:
            return expect.label + std::string(" graph info version: unsupported version ") +
                   std::to_string(static_cast<int>(graphs[0].version)) + " (reader knows 1,2,3)";
    }
    if (!gIn || !gOut) return expect.label + std::string(" graph info: null tensor lists");

    // Tensors are their own versioned union; surface that too rather than trusting v1.
    LOGI("%s: tensor version in[0]=%d out[0]=%d", expect.label,
         static_cast<int>(gIn[0].version), static_cast<int>(gOut[0].version));
    for (uint32_t i = 0; i < numIn; ++i) {
        if (!tensorVersionKnown(gIn[i])) {
            return expect.label + std::string(" tensor version (input ") + std::to_string(i) +
                   "): unsupported version " + std::to_string(static_cast<int>(gIn[i].version)) +
                   " (reader knows 1,2)";
        }
    }
    for (uint32_t i = 0; i < numOut; ++i) {
        if (!tensorVersionKnown(gOut[i])) {
            return expect.label + std::string(" tensor version (output ") + std::to_string(i) +
                   "): unsupported version " + std::to_string(static_cast<int>(gOut[i].version)) +
                   " (reader knows 1,2)";
        }
    }

    slot.name = gName ? gName : "";
    LOGI("%s: graph[0] name=%s inputs=%u outputs=%u",
         expect.label, slot.name.c_str(), numIn, numOut);

    // Every tensor is logged by NAME, dtype, shape and size. Q3 and Q4 bind by name (lesson 5) and
    // Q10a is the first time any of this runs on device: this log IS the evidence for both.
    uint64_t inBytesTotal = 0, outBytesTotal = 0;
    for (uint32_t i = 0; i < numIn; ++i) {
        uint64_t b = tensorBytes(gIn[i]);
        inBytesTotal += b;
        LOGI("  %s IN  [%2u] %-24s %-9s %-20s %" PRIu64 " B", expect.label, i,
             tensorName(gIn[i]) ? tensorName(gIn[i]) : "?",
             dtypeName(tensorDataType(gIn[i])), shapeStr(gIn[i]).c_str(), b);
    }
    for (uint32_t i = 0; i < numOut; ++i) {
        uint64_t b = tensorBytes(gOut[i]);
        outBytesTotal += b;
        LOGI("  %s OUT [%2u] %-24s %-9s %-20s %" PRIu64 " B", expect.label, i,
             tensorName(gOut[i]) ? tensorName(gOut[i]) : "?",
             dtypeName(tensorDataType(gOut[i])), shapeStr(gOut[i]).c_str(), b);
    }
    LOGI("%s: IO totals in %" PRIu64 " B, out %" PRIu64 " B", expect.label,
         inBytesTotal, outBytesTotal);

    // Compared, not enforced. Every downstream sizing decision in Q3/Q4 was made against these
    // numbers, so a regenerated asset that differs has to be seen - but it must be seen as a
    // warning naming both numbers, not as a build that mysteriously stops working.
    if (numIn != expect.numIn || numOut != expect.numOut ||
        inBytesTotal != expect.inBytes || outBytesTotal != expect.outBytes) {
        LOGW("%s: IO DIFFERS FROM THE PLANNED ASSET. expected %u in / %u out, %" PRIu64
             " B in / %" PRIu64 " B out; got %u in / %u out, %" PRIu64 " B in / %" PRIu64 " B out. "
             "Q3/Q4 buffer sizing was derived from the expected figures - re-derive before trusting "
             "any transcript from this asset.",
             expect.label, expect.numIn, expect.numOut, expect.inBytes, expect.outBytes,
             numIn, numOut, inBytesTotal, outBytesTotal);
    }

    // ---- DEEP copy the descriptors -----------------------------------------------------
    //
    // LESSON 2, and it is where the spike's run 6 crashed. Qnn_Tensor_t is not self-contained:
    // `name` and `dimensions` are POINTERS into storage owned by the system context. The build
    // before it shallow-copied the structs, called systemContextFree() immediately, and then
    // dereferenced `dimensions` from tensorBytes() during buffer binding - a use-after-free, which
    // is exactly where it died: right after graphRetrieve.
    //
    // Two independent fixes, because this one cost a device round trip:
    //   1. own every pointer we keep (names and dimension arrays copied into our own storage,
    //      then repointed), and
    //   2. do not free the system context until teardown - the only systemContextFree in this
    //      file is in releaseLocked() below, and NpuNativeContractTest pins that ordering.
    slot.ownedNames.reserve(numIn + numOut);
    slot.ownedDims.reserve(numIn + numOut);
    const std::string *namesBase = slot.ownedNames.data();
    const std::vector<uint32_t> *dimsBase = slot.ownedDims.data();

    auto deepCopy = [&](const Qnn_Tensor_t *src, uint32_t n) {
        std::vector<Qnn_Tensor_t> v(src, src + n);
        for (uint32_t i = 0; i < n; ++i) {
            const char *nm = tensorName(v[i]);
            slot.ownedNames.emplace_back(nm ? nm : "");
            uint32_t rank = tensorRank(v[i]);
            const uint32_t *d = tensorDims(v[i]);
            slot.ownedDims.emplace_back(d ? std::vector<uint32_t>(d, d + rank)
                                          : std::vector<uint32_t>());
        }
        return v;
    };
    slot.inputs = deepCopy(gIn, numIn);
    slot.outputs = deepCopy(gOut, numOut);

    // The reserve() above is load-bearing, not a micro-optimisation: a reallocation between the
    // two deepCopy calls would move every string and vector the repoint below is about to hand to
    // QNN, and the resulting dangling pointers would look exactly like the run-6 crash we already
    // paid for. Prove it did not happen rather than trusting the arithmetic.
    if (slot.ownedNames.data() != namesBase || slot.ownedDims.data() != dimsBase) {
        return expect.label + std::string(" deep copy: owned storage reallocated (reserved ") +
               std::to_string(numIn + numOut) + ")";
    }

    // Repoint only after BOTH vectors are populated, so the storage can never move again.
    {
        size_t k = 0;
        for (auto &t : slot.inputs) {
            tensorRepoint(t, slot.ownedNames[k], slot.ownedDims[k]);
            ++k;
        }
        for (auto &t : slot.outputs) {
            tensorRepoint(t, slot.ownedNames[k], slot.ownedDims[k]);
            ++k;
        }
    }

    // ---- deserialise ---------------------------------------------------------------------
    t0 = Clock::now();
    e = g.qnn.contextCreateFromBinary(g.backend, g.device, nullptr,
                                      blob.data(), blob.size(), &slot.context, nullptr);
    double coldMs = msSince(t0);
    if (e != QNN_SUCCESS) {
        return expect.label + std::string(" contextCreateFromBinary: ") + qnnErr(e);
    }
    LOGI("%s: contextCreateFromBinary OK - cold load %.0f ms", expect.label, coldMs);

    e = g.qnn.graphRetrieve(slot.context, slot.name.c_str(), &slot.graph);
    if (e != QNN_SUCCESS) {
        return expect.label + std::string(" graphRetrieve(") + slot.name + "): " + qnnErr(e);
    }
    LOGI("%s: graphRetrieve OK", expect.label);

    // `blob` dies here, and that is deliberate: holding both binaries would add 342 MiB of dead
    // RSS on top of the ~376 MiB the NPU path already needs. QNN's own sample app frees the buffer
    // at the same point. Nothing we keep points into it - the descriptors were deep-copied above,
    // and the system context (which owns the metadata storage) outlives this function.
    return "";
}

// ---------------------------------------------------------------- teardown

/// Releases everything in reverse order of creation. Safe to call on a partially-built state (every
/// handle is null-checked) and safe to call twice, which is what makes the failure paths in
/// nativeInit able to just call it and return.
void releaseLocked() {
    if (g.enc.context) g.qnn.contextFree(g.enc.context, nullptr);
    if (g.dec.context) g.qnn.contextFree(g.dec.context, nullptr);
    g.enc.clear();
    g.dec.clear();

    if (g.device) g.qnn.deviceFree(g.device);
    g.device = nullptr;
    if (g.backend) g.qnn.backendFree(g.backend);
    g.backend = nullptr;

    // Freed only now. The tensor descriptors' backing storage lived here, and holding it for the
    // whole session removes any doubt about dangling metadata pointers even though everything we
    // kept was deep-copied above. Belt and braces, deliberately (lesson 2).
    if (g.sysCtx) g.sys.systemContextFree(g.sysCtx);
    g.sysCtx = nullptr;

    g.initialised = false;

    // The two libraries stay dlopen()ed on purpose. dlclose on a backend that has registered
    // process-wide FastRPC state is not something the QNN API promises is safe, the handles are
    // refcounted so re-arming costs nothing, and NpuGate may arm this tier several times in one
    // session. Nothing device-side is held by the mapping alone.
}

}  // namespace

// ================================================================ JNI surface
//
// Kotlin `object QnnAsrNative` -> instance methods on the singleton, hence `jobject`.
// Every String return is "" on success or "stage: detail" on failure, and the same text is
// retrievable afterwards from nativeLastError().

/// Cheap, side-effect-free gate: dlopen the QNN libraries and confirm at least one provider on each
/// table. Creates NO backend, NO device and NO context - it is what Q6 calls before deciding
/// whether the NPU tier can be offered at all, on every launch, on devices where the answer is no.
extern "C" JNIEXPORT jstring JNICALL
Java_com_whispereverywhere_npu_QnnAsrNative_nativeProbe(
        JNIEnv *env, jobject /* this */, jstring jLibDir) {
    const std::string libDir = jstr(env, jLibDir);
    std::lock_guard<std::mutex> lock(g.mu);
    const std::string err = loadInterfacesLocked(libDir);
    if (!err.empty()) return env->NewStringUTF(failure("probe: " + err).c_str());
    LOGI("probe OK (libDir=%s)", libDir.c_str());
    return env->NewStringUTF("");
}

/// Loads BOTH context binaries: 127 MB encoder and 215 MB decoder, ~342 MiB resident once this
/// returns. Idempotent - an existing session is released first, so a model swap or a restarted
/// session cannot leak a context.
extern "C" JNIEXPORT jstring JNICALL
Java_com_whispereverywhere_npu_QnnAsrNative_nativeInit(
        JNIEnv *env, jobject /* this */,
        jstring jEncoderPath, jstring jDecoderPath, jstring jLibDir) {
    const std::string encoderPath = jstr(env, jEncoderPath);
    const std::string decoderPath = jstr(env, jDecoderPath);
    const std::string libDir = jstr(env, jLibDir);

    std::lock_guard<std::mutex> lock(g.mu);
    if (g.initialised) {
        LOGW("nativeInit called on an already-initialised session; releasing it first");
        releaseLocked();
    }

    std::string err = loadInterfacesLocked(libDir);
    if (!err.empty()) return env->NewStringUTF(failure("init: " + err).c_str());

    Qnn_ErrorHandle_t e = g.sys.systemContextCreate(&g.sysCtx);
    if (e != QNN_SUCCESS) {
        releaseLocked();
        return env->NewStringUTF(failure("systemContextCreate: " + qnnErr(e)).c_str());
    }

    auto t0 = Clock::now();
    e = g.qnn.backendCreate(nullptr, nullptr, &g.backend);
    if (e != QNN_SUCCESS) {
        releaseLocked();
        return env->NewStringUTF(failure("backendCreate: " + qnnErr(e)).c_str());
    }
    LOGI("backendCreate OK (%.0f ms)", msSince(t0));

    t0 = Clock::now();
    e = g.qnn.deviceCreate(nullptr, nullptr, &g.device);
    if (e != QNN_SUCCESS && e != QNN_DEVICE_ERROR_UNSUPPORTED_FEATURE) {
        releaseLocked();
        return env->NewStringUTF(failure("deviceCreate: " + qnnErr(e)).c_str());
    }
    LOGI("deviceCreate %s (%.0f ms)",
         e == QNN_SUCCESS ? "OK" : "unsupported-on-this-backend (continuing)", msSince(t0));
    if (e != QNN_SUCCESS) g.device = nullptr;

    err = loadGraphSlot(g.enc, encoderPath, kEncoderExpectation);
    if (!err.empty()) {
        releaseLocked();
        return env->NewStringUTF(failure("init: " + err).c_str());
    }
    err = loadGraphSlot(g.dec, decoderPath, kDecoderExpectation);
    if (!err.empty()) {
        releaseLocked();
        return env->NewStringUTF(failure("init: " + err).c_str());
    }

    g.initialised = true;
    g.lastError.clear();
    LOGI("nativeInit OK - encoder graph '%s' (%zu in / %zu out), decoder graph '%s' "
         "(%zu in / %zu out)",
         g.enc.name.c_str(), g.enc.inputs.size(), g.enc.outputs.size(),
         g.dec.name.c_str(), g.dec.inputs.size(), g.dec.outputs.size());
    return env->NewStringUTF("");
}

/// The last "stage: detail" recorded by any entry point, or "" if none. Q4's nativeDecodeSegment
/// reports failure as a negative return value and leans on this for the text.
extern "C" JNIEXPORT jstring JNICALL
Java_com_whispereverywhere_npu_QnnAsrNative_nativeLastError(
        JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g.mu);
    return env->NewStringUTF(g.lastError.c_str());
}

/// Tears the session down. MUST be called before the CPU tier re-arms: the plan's memory budget
/// forbids the NPU and `multi` being co-resident in either direction (I11).
extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_npu_QnnAsrNative_nativeRelease(
        JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g.mu);
    releaseLocked();
    LOGI("nativeRelease complete");
}
