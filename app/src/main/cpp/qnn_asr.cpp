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
// SCOPE OF THIS FILE TODAY (Q4): probe, load, enumerate, log, ALIAS-GUARD, vote, encode, BIND THE
// DECODER BY NAME, and the whole greedy decode loop - suppression mask applied to the logits before
// the argmax is scanned, self-KV ping-ponged by re-binding, cross-KV aliased onto the encoder's
// output buffers with nothing copied between the passes.
//
// WHY THE DECODE LOOP IS NATIVE, AND IT IS NOT A PERFORMANCE ARGUMENT (C2). Whisper's suppression
// is by construction a PRE-ARGMAX mask. A Kotlin loop calling a per-step native `decodeStep` would
// receive only the argmax, and an argmax has no runner-up: on discovering that the winner is a
// suppressed token the caller can do nothing useful, because re-running the step is deterministic
// and returns the same token. Such a loop either emits the suppressed token or hangs. The JNI
// crossing was never the issue - a two-int transition is ~100 ns against a ~4.5 ms graphExecute -
// the boundary was simply in the wrong place for correctness.
//
// Q10a is still the first execution of any of this on device.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cerrno>
#include <chrono>
#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
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
// The two HTP-specific headers, and the ONLY two things in this file that are not generic QNN:
// the power vote is a Hexagon concept and has no equivalent in the backend-agnostic API. Everything
// else - contexts, graphs, tensors, execute - still goes through the provider table, so a
// non-HTP backend would fail only at the vote, which is a warning rather than an error (below).
#include "HTP/QnnHtpDevice.h"
#include "HTP/QnnHtpPerfInfrastructure.h"

#define TAG "WE-NPU"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// The owner has no adb and their acceptance capture is `adb logcat -s WE-DIAG` run by someone else,
// so an instrumentation line that lands under WE-NPU is a line nobody will ever see. Same tag and
// same spelling as whisper_jni.cpp's LOGDIAG, deliberately: one grep, one capture, both halves of
// the pipeline in it.
#define LOGDIAG(...) __android_log_print(ANDROID_LOG_INFO, "WE-DIAG", __VA_ARGS__)

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

/// The tensor's quantisation parameters, read through the version tag like every other accessor
/// here. Null on an unknown version - callers must treat that as an error, never as "unquantised".
///
/// This is the ONLY source of `input_features`' scale and zero point (Q3 step 3) and the field the
/// C7 alias guard compares across the encoder/decoder boundary. Both graphs are w8a16: a tensor
/// whose encoding cannot be read is not a tensor this seam can drive.
const Qnn_QuantizeParams_t *tensorQuantParams(const Qnn_Tensor_t &t) {
    if (t.version == QNN_TENSOR_VERSION_1) return &t.v1.quantizeParams;
    if (t.version == QNN_TENSOR_VERSION_2) return &t.v2.quantizeParams;
    return nullptr;
}

/// How the tensor's elements are laid out in the client buffer, read through the version tag.
///
/// **Q10a-D2 added this, and it is a load-bearing reading rather than a curiosity.** Everything
/// this seam does with `input_features` assumes the buffer is a DENSE row-major block: quantise
/// 240,000 floats in order, `memcpy` them, execute. `Qnn_TensorDataFormat_t` is the field that says
/// whether that assumption holds - QNN also defines sparse, codebook, MX and a family of UBWC
/// (compressed) layouts, and under any of them a flat block of correctly-quantised values is read
/// as something else entirely. That failure has no error and no crash: it is noise the encoder
/// transcribes fluently. `QNN_TENSOR_DATA_FORMAT_DENSE` is 0, so a zero-initialised descriptor
/// reads as dense whether or not anyone checked - which is exactly why it is now printed rather
/// than assumed.
Qnn_TensorDataFormat_t tensorDataFormat(const Qnn_Tensor_t &t) {
    if (t.version == QNN_TENSOR_VERSION_1) return t.v1.dataFormat;
    if (t.version == QNN_TENSOR_VERSION_2) return t.v2.dataFormat;
    return QNN_TENSOR_DATA_FORMAT_DENSE;
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

/// Q3 binds the encoder's mel and cross-KV buffers with it; Q4 re-binds the decoder's self-KV
/// ping-pong 48 times per token. Same versioned-union discipline as the accessors above.
void tensorSetClientBuf(Qnn_Tensor_t &t, void *data, uint32_t bytes) {
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

/// Names the quantisation encoding family so an unexpected one is readable rather than an integer.
const char *encodingName(Qnn_QuantizationEncoding_t enc) {
    switch (enc) {
        case QNN_QUANTIZATION_ENCODING_SCALE_OFFSET:      return "scale-offset";
        case QNN_QUANTIZATION_ENCODING_AXIS_SCALE_OFFSET: return "axis-scale-offset";
        case QNN_QUANTIZATION_ENCODING_BW_SCALE_OFFSET:   return "bw-scale-offset";
        case QNN_QUANTIZATION_ENCODING_BLOCK:             return "block";
        case QNN_QUANTIZATION_ENCODING_UNDEFINED:         return "undefined";
        default:                                          return "other";
    }
}

/// Names the memory layout so a non-dense format is readable rather than an integer nobody looks up.
const char *dataFormatName(Qnn_TensorDataFormat_t f) {
    switch (f) {
        case QNN_TENSOR_DATA_FORMAT_DENSE:    return "dense";
        case QNN_TENSOR_DATA_FORMAT_SPARSE:   return "sparse";
        case QNN_TENSOR_DATA_FORMAT_CODEBOOK: return "codebook";
        case QNN_TENSOR_DATA_FORMAT_MX:       return "mx";
        default:                              return "non-dense";
    }
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

    /// Client buffers, one per tensor, index-parallel with `inputs` / `outputs`. Empty until the
    /// slot is bound - Q3 binds the encoder's; the decoder's are Q4's, and are NOT this shape
    /// (its 24 cross-KV inputs alias `enc.outBufs` and allocate nothing of their own).
    std::vector<AlignedBuf> inBufs;
    std::vector<AlignedBuf> outBufs;

    /// LESSON 5: name -> index, never a positional constant. The decoder has 51 inputs, and an
    /// off-by-one there is a silent wrong answer rather than a crash. Built once at load for both
    /// graphs, because the alias guard has to look tensors up by name on both sides of the seam.
    std::map<std::string, size_t> inIndex;
    std::map<std::string, size_t> outIndex;

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
        // Frees every client buffer: AlignedBuf is RAII and vector::clear destroys its elements.
        inBufs.clear();
        outBufs.clear();
        inIndex.clear();
        outIndex.clear();
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

/// The encoder's one input. Looked up by name (lesson 5), never by index, even though there is
/// only one of it: "there is only one" is an asset fact, and asset facts are what change.
constexpr const char *kInputFeatures = "input_features";

/// The 24 cross-attention KV tensors, per layer: `k_cache_cross_0..11`, `v_cache_cross_0..11`.
/// Encoder OUTPUTS and decoder INPUTS carry the same 24 names, and the whole zero-copy design
/// rests on the two sides being the same tensor in every respect. See aliasGuardLocked.
constexpr uint32_t kCrossKvLayers = 12;

// ---- the decoder's four non-KV tensors, all looked up BY NAME (lesson 5) ---------------------
//
// The decoder has 51 inputs. A positional constant among 51 is not a crash when it slips, it is a
// silent wrong answer: the graph executes happily against whatever tensor index 37 happens to be
// this export. Every one of them is bound through g.dec.inIndex / g.dec.outIndex.
constexpr const char *kInputIds = "input_ids";
constexpr const char *kPositionIds = "position_ids";
constexpr const char *kAttentionMask = "attention_mask";
constexpr const char *kLogits = "logits";

/// `attention_mask` codes. Its quantisation is exact - scale 0.0015259021893143654, zero point
/// 65535 - so code 65535 dequantises to 0.0 (attend) and code 0 to -100.0 (masked). These are the
/// two codes the asset was calibrated for; anything between them is a partially-attended position,
/// which is not a thing whisper has.
constexpr uint16_t kMaskAttend = 65535;
constexpr uint16_t kMaskBlocked = 0;

/// The bottom of the `ufixed16` logits domain, and this seam's `-inf`.
///
/// Dequantisation is `scale x (q - zeroPoint)` with `scale > 0`, i.e. strictly monotonic, so the
/// smallest code IS the smallest logit and an argmax over the raw codes is the argmax over the real
/// values - exactly, not approximately. Writing 0 into a suppressed slot therefore cannot make it
/// win unless every other slot is also 0, which is a dead graph output and is reported as one.
constexpr uint16_t kLogitFloor = 0;

// ---- token ids native has to know for itself ------------------------------------------------
//
// These four are the SECOND reading of an asset fact whose first reading is `WhisperTokens` in
// Kotlin. They are not passed in because the contract's argument list is fixed (prompt, suppress,
// beginSuppress, maxTokens, out) and because a terminator smuggled in through a data array is a
// terminator nobody can see. NpuNativeContractTest cross-checks kEotToken against
// WhisperTokens.EOT by source text, so the two copies cannot drift apart silently.
constexpr int32_t kEotToken = 50257;        // <|endoftext|>
constexpr int32_t kSotToken = 50258;        // <|startoftranscript|>
constexpr int32_t kLangTokenFirst = 50259;  // <|en|>
constexpr int32_t kLangTokenLast = 50357;   // <|su|>, 50259 + 98

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

    // ---- the sustained power vote (Q3 step 2) ------------------------------------------------
    // Armed ONCE per session in nativeInit and released in nativeRelease - not per segment. The
    // config it holds is a governor SETTING, not a clock pin: it costs nothing while idle, which
    // is precisely what makes it holdable for the length of a dictation session.
    QnnHtpDevice_PerfInfrastructure_t perf{};
    bool perfAvailable = false;
    uint32_t powerConfigId = 0;
    bool voted = false;
    /// Human-readable outcome of the vote, always logged, never silently empty (lesson 6).
    std::string voteNote = "not attempted";

    // ---- the encoder's input quantisation, read from metadata at load (Q3 step 3) --------------
    size_t encInputIdx = 0;
    float encInputScale = 0.0f;
    int32_t encInputZeroPoint = 0;

    // ---- the decoder's binding (Q4) -----------------------------------------------------------
    // Every one of these is filled by bindDecoderLocked() at init and is stable for the session.
    bool decBound = false;
    size_t decInputIdsIdx = 0;
    size_t decPositionIdsIdx = 0;
    size_t decMaskIdx = 0;
    size_t decLogitsIdx = 0;

    /// The 24 self-KV tensor indices, `k_cache_self_0..11` then `v_cache_self_0..11`, into
    /// `dec.inputs` (`_in`) and `dec.outputs` (`_out`). Parallel: entry i of one names the same
    /// layer and kind as entry i of the other.
    std::vector<size_t> selfInIdx;
    std::vector<size_t> selfOutIdx;

    /// THE PING-PONG. Two sets of 24 buffers; each step binds one set as the decoder's self-KV
    /// INPUTS and the other as its OUTPUTS, then swaps. QNN cannot alias one buffer as both, and
    /// the alternative - one set plus a memcpy - would move 3.6 MB per token, ~350 MB per segment,
    /// to achieve precisely nothing.
    std::vector<AlignedBuf> selfKv[2];
    uint32_t selfKvBytes = 0;
    /// Which set is currently bound as the INPUT side.
    int selfInSet = 0;

    /// Read from the asset, never assumed: `attention_mask`'s length (200) and `logits`' vocabulary
    /// (51,865). The decode loop's position bound and argmax bound both come from here, so a
    /// re-exported asset with a different context window drives a loop that matches it.
    uint32_t maskLen = 0;
    uint32_t vocab = 0;

    /// THE ENCODE VALIDITY FLAG. True only while the 24 cross-KV buffers hold a segment that a
    /// successful nativeEncode put there.
    ///
    /// The decoder reads those buffers IN PLACE - that is the whole zero-copy design - so a decode
    /// without a preceding encode does not fail, does not crash and does not return garbage: it
    /// transcribes the PREVIOUS segment, fluently, or (before the first encode) AlignedBuf's zeros.
    /// A caller cannot detect that from the outside, which makes it the worst failure shape in this
    /// tier and the most likely integration mistake in it.
    ///
    /// IT IS A VALIDITY FLAG, NOT A ONE-SHOT TOKEN: a decode does NOT consume it. Q6's flow is one
    /// encode, then nativeDetectLanguage, then nativeDecodeSegment against that same encode, and a
    /// detect pass that consumed the flag would break the only sequence the tier actually runs.
    /// Cleared on entry to nativeEncode (a FAILED execute may have left the cross-KV half written),
    /// set on its success, and cleared by releaseLocked and by a fresh nativeInit.
    bool encoded = false;

    /// THE Q10a-D1 INSTRUMENTATION GATE. Off unless Kotlin turns it on (`nativeSetDiag`, wired to
    /// `BuildConfig.DEBUG`), so a release build emits not one extra line and pays not one extra
    /// full-vocabulary scan.
    ///
    /// The scans this gates are ~51,865 uint16 reads each, five times per segment - microseconds
    /// against a ~4.5 ms graphExecute - but the reason it is gated is not cost. These lines describe
    /// the model's own output distribution, and a shipping build has no business narrating that.
    bool diag = false;

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

/// name -> index over one tensor list. LESSON 5: everything downstream binds by name.
///
/// A DUPLICATE NAME IS A HARD ERROR, not a last-one-wins overwrite. `map::emplace` keeps the first
/// and reports the collision; if two tensors ever shared a name, silently binding one of them
/// twice and the other never is the exact class of failure - correct-looking, wrong answer - that
/// binding by name exists to prevent. Better to refuse the asset.
std::string buildNameIndex(const std::vector<Qnn_Tensor_t> &ts, std::map<std::string, size_t> &out,
                           const char *label, const char *what) {
    out.clear();
    for (size_t i = 0; i < ts.size(); ++i) {
        const char *nm = tensorName(ts[i]);
        if (!nm || !*nm) {
            return std::string(label) + " " + what + "[" + std::to_string(i) + "]: unnamed tensor";
        }
        auto r = out.emplace(nm, i);
        if (!r.second) {
            return std::string(label) + " " + what + ": duplicate tensor name '" + nm +
                   "' at indices " + std::to_string(r.first->second) + " and " + std::to_string(i);
        }
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

    // ---- name -> index, both directions ---------------------------------------------------
    // Built from the REPOINTED descriptors, so the keys are copies of strings we own rather than
    // views into the system context. Built here rather than at first use because the alias guard
    // runs before anything binds and needs both graphs already mapped.
    err = buildNameIndex(slot.inputs, slot.inIndex, expect.label, "input");
    if (!err.empty()) return err;
    err = buildNameIndex(slot.outputs, slot.outIndex, expect.label, "output");
    if (!err.empty()) return err;

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

// ---------------------------------------------------------------- C7: the cross-KV alias guard

/// One cross-KV pair, compared field by field. Returns "" when the two descriptors are the SAME
/// TENSOR in every respect that matters to an alias, or an `alias: <name> <field> <a> != <b>`
/// string naming exactly what diverged.
std::string aliasCompare(const std::string &name, const Qnn_Tensor_t &e, const Qnn_Tensor_t &d) {
    char buf[224];

    if (tensorDataType(e) != tensorDataType(d)) {
        snprintf(buf, sizeof(buf), "alias: %s dtype %s != %s", name.c_str(),
                 dtypeName(tensorDataType(e)), dtypeName(tensorDataType(d)));
        return buf;
    }

    const uint32_t re = tensorRank(e), rd = tensorRank(d);
    if (re != rd) {
        snprintf(buf, sizeof(buf), "alias: %s rank %u != %u", name.c_str(), re, rd);
        return buf;
    }
    const uint32_t *de = tensorDims(e);
    const uint32_t *dd = tensorDims(d);
    if (!de || !dd || re == 0) return "alias: " + name + " has no readable dimensions";
    for (uint32_t i = 0; i < re; ++i) {
        if (de[i] != dd[i]) {
            snprintf(buf, sizeof(buf), "alias: %s dim[%u] %u != %u", name.c_str(), i, de[i], dd[i]);
            return buf;
        }
    }

    const Qnn_QuantizeParams_t *qe = tensorQuantParams(e);
    const Qnn_QuantizeParams_t *qd = tensorQuantParams(d);
    if (!qe || !qd) return "alias: " + name + " quantize params unreadable (unknown tensor version)";
    if (qe->encodingDefinition != QNN_DEFINITION_DEFINED ||
        qd->encodingDefinition != QNN_DEFINITION_DEFINED) {
        snprintf(buf, sizeof(buf), "alias: %s encodingDefinition %d != %d (both must be DEFINED)",
                 name.c_str(), static_cast<int>(qe->encodingDefinition),
                 static_cast<int>(qd->encodingDefinition));
        return buf;
    }
    if (qe->quantizationEncoding != qd->quantizationEncoding) {
        snprintf(buf, sizeof(buf), "alias: %s encoding %s != %s", name.c_str(),
                 encodingName(qe->quantizationEncoding), encodingName(qd->quantizationEncoding));
        return buf;
    }
    // Anything but per-tensor scale-offset means the two sides could agree on the fields below and
    // still disagree on the transform (a per-axis encoding hides its scales behind a pointer this
    // comparison never reads). Refuse rather than compare the wrong thing.
    if (qe->quantizationEncoding != QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        snprintf(buf, sizeof(buf),
                 "alias: %s encoding %s is not per-tensor scale-offset; the alias cannot be "
                 "verified", name.c_str(), encodingName(qe->quantizationEncoding));
        return buf;
    }
    // EXACT float equality, deliberately. There is no tolerance that is correct here: the two
    // descriptors are supposed to have been produced by the same export of the same tensor, so any
    // difference at all - however small - means they no longer are, and the alias is unsound.
    if (qe->scaleOffsetEncoding.scale != qd->scaleOffsetEncoding.scale) {
        // %.8f is the brief's mandated text; the %.9g pair after it is not decoration. The
        // comparison above is EXACT, so the two values can differ by a single ulp far below the
        // eighth decimal - and the message would then read "scale 0.06918466 != 0.06918466",
        // which is a bug report nobody can act on. %.9g round-trips a float exactly.
        snprintf(buf, sizeof(buf), "alias: %s scale %.8f != %.8f (exact: %.9g != %.9g)",
                 name.c_str(),
                 qe->scaleOffsetEncoding.scale, qd->scaleOffsetEncoding.scale,
                 static_cast<double>(qe->scaleOffsetEncoding.scale),
                 static_cast<double>(qd->scaleOffsetEncoding.scale));
        return buf;
    }
    if (qe->scaleOffsetEncoding.offset != qd->scaleOffsetEncoding.offset) {
        snprintf(buf, sizeof(buf), "alias: %s offset %d != %d", name.c_str(),
                 qe->scaleOffsetEncoding.offset, qd->scaleOffsetEncoding.offset);
        return buf;
    }
    return "";
}

/// THE ALIAS GUARD (C7). Runs at load, after both graphs are enumerated and BEFORE anything binds.
///
/// The whole zero-copy design of this tier rests on one claim: the encoder's 24 cross-KV OUTPUT
/// buffers can be handed to the decoder as its 24 cross-KV INPUTS untouched, because the two sides
/// describe the same tensor. Re-feeding them instead would move 26.4 MiB x ~100 tokens per segment.
///
/// THAT CLAIM WAS A PLAN-TIME OBSERVATION - read out of the asset's metadata.json by hand, once.
/// If a future asset regeneration shifts one side's scale, the alias feeds the decoder cross-KV
/// under the wrong affine transform. The result is not a crash and not garbage: it is PLAUSIBLE
/// WRONG TEXT, the worst possible failure for a dictation app and the one a single-sentence
/// acceptance test is least likely to catch. So the observation becomes a guard, and the guard
/// runs before the first bind rather than after the first transcript.
std::string aliasGuardLocked() {
    uint32_t checked = 0;
    for (uint32_t layer = 0; layer < kCrossKvLayers; ++layer) {
        for (const char *kind : {"k_cache_cross_", "v_cache_cross_"}) {
            const std::string name = std::string(kind) + std::to_string(layer);

            auto eit = g.enc.outIndex.find(name);
            if (eit == g.enc.outIndex.end()) {
                return "alias: " + name + " missing from the encoder's outputs";
            }
            auto dit = g.dec.inIndex.find(name);
            if (dit == g.dec.inIndex.end()) {
                return "alias: " + name + " missing from the decoder's inputs";
            }

            const std::string err = aliasCompare(name, g.enc.outputs[eit->second],
                                                 g.dec.inputs[dit->second]);
            if (!err.empty()) return err;
            ++checked;
        }
    }
    // The loop above verifies the 24 tensors this seam KNOWS ABOUT. It cannot, by construction,
    // say anything about a 25th - and a whisper variant with more than 12 layers would carry one.
    // Its extra cross-KV tensors would be aliased by Q4's bind-by-name pass and never checked here,
    // which is the guard silently covering less than it appears to. So count both sides too: the
    // number of cross-KV tensors present must be exactly the number verified.
    auto countCross = [](const std::map<std::string, size_t> &idx) {
        uint32_t n = 0;
        for (const auto &kv : idx) {
            if (kv.first.find("_cache_cross_") != std::string::npos) ++n;
        }
        return n;
    };
    const uint32_t encCross = countCross(g.enc.outIndex);
    const uint32_t decCross = countCross(g.dec.inIndex);
    if (encCross != checked || decCross != checked) {
        char buf[224];
        snprintf(buf, sizeof(buf),
                 "alias: verified %u pairs but the asset carries %u cross-KV encoder outputs and "
                 "%u cross-KV decoder inputs; %u layers assumed", checked, encCross, decCross,
                 kCrossKvLayers);
        return buf;
    }
    LOGI("alias guard: %u cross-KV pairs identical across encoder-out/decoder-in "
         "(dtype, rank, every dim, scale, offset)", checked);
    return "";
}

// ---------------------------------------------------------------- buffers and input quantisation

/// Allocate one 64-byte-aligned client buffer per tensor, sized EXACTLY from dims x dtype, and bind
/// it. Ported from the spike's `bindAll`.
///
/// This is the SIMPLE case, and it is the encoder's case only. The decoder's binding (Q4) is not
/// this shape: its 24 cross-KV inputs alias `g.enc.outBufs` and allocate nothing, and its self-KV
/// inputs/outputs ping-pong between two sets that are re-bound every step. Calling this over
/// `dec.inputs` would quietly allocate 26.4 MiB of buffers the decoder must not read from.
std::string allocateAndBind(std::vector<Qnn_Tensor_t> &ts, std::vector<AlignedBuf> &bufs,
                            const char *label, const char *what) {
    bufs.clear();
    bufs.resize(ts.size());
    for (size_t i = 0; i < ts.size(); ++i) {
        const uint64_t need = tensorBytes(ts[i]);
        const char *nm = tensorName(ts[i]) ? tensorName(ts[i]) : "?";
        if (need == 0) {
            return std::string(label) + " bind " + what + "[" + std::to_string(i) + "] '" + nm +
                   "': computed 0 bytes";
        }
        // clientBuf.dataSize is a uint32_t; a tensor that does not fit one would be bound with a
        // truncated size and overrun on the DSP side. 27 MiB is nowhere near it, but the cast is
        // where it would happen, so the check lives on the cast.
        if (need > 0xFFFFFFFFull) {
            return std::string(label) + " bind " + what + "[" + std::to_string(i) + "] '" + nm +
                   "': " + std::to_string(need) + " B exceeds the 32-bit clientBuf size";
        }
        if (!bufs[i].alloc(static_cast<size_t>(need))) {
            return std::string(label) + " bind " + what + "[" + std::to_string(i) + "] '" + nm +
                   "': alloc " + std::to_string(need) + " B failed";
        }
        tensorSetClientBuf(ts[i], bufs[i].p, static_cast<uint32_t>(need));
        LOGI("  bind %s %s[%2zu] %-24s %9" PRIu64 " B @ %p", label, what, i, nm, need, bufs[i].p);
    }
    return "";
}

/// Reads `input_features`' scale and zero point off the encoder's OWN tensor metadata and caches
/// them for nativeInputQuant. Q3 step 3, and the reason that step exists at all.
///
/// The quantisation parameters belong to the asset. A hardcoded scale in the Kotlin quantiser
/// would survive an asset re-export unchanged, keep running, and feed the encoder a spectrogram
/// scaled by the wrong constant - which it transcribes fluently into different words. Nothing
/// downstream can detect that, so the numbers travel from the metadata to the quantiser and are
/// never written down anywhere in between.
///
/// Read at LOAD, not per segment: if the asset cannot supply them, the tier must decline while the
/// backend selector can still fall back, not mid-dictation.
std::string readEncoderInputQuantLocked() {
    auto it = g.enc.inIndex.find(kInputFeatures);
    if (it == g.enc.inIndex.end()) {
        return std::string("quant: '") + kInputFeatures +
               "' not found among the encoder's inputs (found " +
               std::to_string(g.enc.inputs.size()) + " inputs)";
    }
    g.encInputIdx = it->second;
    const Qnn_Tensor_t &t = g.enc.inputs[g.encInputIdx];

    // The DOMAIN - 0..65535 - is a compile-time constant in NpuQuantize, so it is native's job to
    // refuse anything else. An 8-bit input tensor would be clamped against rails 256 times too
    // wide and every loud bin would wrap into a quiet one.
    if (tensorDataType(t) != QNN_DATATYPE_UFIXED_POINT_16) {
        return std::string("quant: '") + kInputFeatures + "' is " +
               dtypeName(tensorDataType(t)) +
               ", not ufixed16; NpuQuantize's 0..65535 rails would be wrong for it";
    }

    const Qnn_QuantizeParams_t *q = tensorQuantParams(t);
    if (!q) {
        return std::string("quant: '") + kInputFeatures +
               "' quantize params unreadable (unknown tensor version)";
    }
    if (q->encodingDefinition != QNN_DEFINITION_DEFINED) {
        return std::string("quant: '") + kInputFeatures +
               "' carries no defined quantization encoding (encodingDefinition " +
               std::to_string(static_cast<int>(q->encodingDefinition)) + ")";
    }
    if (q->quantizationEncoding != QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        return std::string("quant: '") + kInputFeatures + "' encoding is " +
               encodingName(q->quantizationEncoding) +
               ", not per-tensor scale-offset; one (scale, zeroPoint) pair cannot describe it";
    }

    const float scale = q->scaleOffsetEncoding.scale;
    const int32_t offset = q->scaleOffsetEncoding.offset;
    if (!(scale > 0.0f) || !std::isfinite(scale)) {
        char buf[128];
        snprintf(buf, sizeof(buf), "quant: '%s' scale %.9g is not strictly positive and finite",
                 kInputFeatures, static_cast<double>(scale));
        return buf;
    }

    // TWO CONVENTIONS, AND THEY DIFFER BY A SIGN. QNN's header states
    // `float_value = (quantized_value + offset) * scale`, while the AI Hub metadata, ONNX and the
    // Kotlin quantiser all use `float_value = scale * (q - zero_point)`. So zeroPoint = -offset,
    // and the shipped encoder's offset of -32072 is the published zero_point of 32072.
    //
    // The range check below is the whole defence against that sign being wrong. If a future QAIRT
    // ever emitted the offset in the other convention, -offset would be -32072: outside the
    // uint16 domain, caught here, loudly - instead of quantising every mel value to the bottom
    // rail and producing a confident transcript of silence.
    const int64_t zeroPoint = -static_cast<int64_t>(offset);
    if (zeroPoint < 0 || zeroPoint > 65535) {
        return std::string("quant: '") + kInputFeatures + "' zero point " +
               std::to_string(zeroPoint) + " (from QNN offset " + std::to_string(offset) +
               ") is outside the ufixed16 domain 0..65535";
    }

    g.encInputScale = scale;
    g.encInputZeroPoint = static_cast<int32_t>(zeroPoint);
    LOGI("quant: %s %s %s scale %.12g zeroPoint %d (QNN offset %d), %" PRIu64 " B",
         kInputFeatures, dtypeName(tensorDataType(t)), shapeStr(t).c_str(),
         static_cast<double>(scale), g.encInputZeroPoint, offset, tensorBytes(t));
    return "";
}

// ---------------------------------------------------------------- the decoder's binding (Q4)

/// THE MASK CODES, VERIFIED AGAINST THE ASSET'S OWN QUANTISATION - step 1's argument applied to the
/// one tensor this loop rewrites 199 times per segment.
///
/// `kMaskAttend` (65535) and `kMaskBlocked` (0) were a PLAN-TIME OBSERVATION: the asset's
/// attention_mask is quantised scale 0.0015259021893143654, zero point 65535, so code 65535
/// dequantises to 0.0 (no penalty, attend) and code 0 to -100.0 (masked). That is precisely the
/// class of fact the C7 guard exists to distrust. If a re-export flips this tensor's offset, 65535
/// becomes the BLOCKED code, the decoder attends to nothing at every position, and the symptom is
/// fluent wrong text with no error anywhere - the same failure shape, on a tensor we rewrite two
/// hundred times a segment instead of binding once.
///
/// So the two literals are checked against the metadata that defines them, at load, before the
/// first execute. QNN's convention is `float = (q + offset) * scale`.
std::string checkMaskCodesLocked() {
    const Qnn_Tensor_t &t = g.dec.inputs[g.decMaskIdx];
    const Qnn_QuantizeParams_t *q = tensorQuantParams(t);
    if (!q) return "mask: attention_mask quantize params unreadable (unknown tensor version)";
    if (q->encodingDefinition != QNN_DEFINITION_DEFINED) {
        return "mask: attention_mask carries no defined quantization encoding (encodingDefinition " +
               std::to_string(static_cast<int>(q->encodingDefinition)) + ")";
    }
    if (q->quantizationEncoding != QNN_QUANTIZATION_ENCODING_SCALE_OFFSET) {
        return std::string("mask: attention_mask encoding is ") +
               encodingName(q->quantizationEncoding) +
               ", not per-tensor scale-offset; its two codes cannot be checked";
    }
    const float scale = q->scaleOffsetEncoding.scale;
    const int32_t offset = q->scaleOffsetEncoding.offset;
    if (!(scale > 0.0f) || !std::isfinite(scale)) {
        char b[128];
        snprintf(b, sizeof(b), "mask: attention_mask scale %.9g is not strictly positive and finite",
                 static_cast<double>(scale));
        return b;
    }

    const double attend = (static_cast<double>(kMaskAttend) + offset) * scale;
    const double blocked = (static_cast<double>(kMaskBlocked) + offset) * scale;
    char buf[256];
    // The attend code must be a NO-OP additive mask. A tolerance rather than == 0.0 because a
    // re-calibration that moved the zero point by a code or two is harmless; an inversion is not,
    // and an inversion puts +100 here, four orders of magnitude outside this window.
    if (!(attend > -0.01 && attend < 0.01)) {
        snprintf(buf, sizeof(buf),
                 "mask: code %u is supposed to ATTEND but dequantises to %.4f (scale %.12g, offset "
                 "%d). If the mask's offset flipped, this seam attends to nothing at every position "
                 "and the transcript is fluent and wrong.", kMaskAttend, attend,
                 static_cast<double>(scale), offset);
        return buf;
    }
    if (!(blocked <= -1.0)) {
        snprintf(buf, sizeof(buf),
                 "mask: code %u is supposed to BLOCK but dequantises to %.4f (scale %.12g, offset "
                 "%d), which masks nothing.", kMaskBlocked, blocked, static_cast<double>(scale),
                 offset);
        return buf;
    }
    LOGI("mask: attention_mask scale %.12g offset %d -> attend code %u = %.4f, blocked code %u = "
         "%.4f", static_cast<double>(scale), offset, kMaskAttend, attend, kMaskBlocked, blocked);
    return "";
}

/// Binds EVERY decoder tensor - all 51 inputs and all 25 outputs - BY NAME, exactly once, at load.
///
/// Three different kinds of binding live here, and the differences are the design:
///
///   * `input_ids`, `position_ids`, `attention_mask`, `logits` - we own the buffer. Tiny.
///   * the 24 cross-KV inputs - **bound straight onto `g.enc.outBufs`**. Zero copy, allocated
///     nothing, never rewritten. Re-feeding them per token would move 26.4 MiB x ~100 tokens
///     = 2.6 GB per segment. `aliasGuardLocked()` has already proved the two sides are the same
///     tensor in dtype, rank, every dimension, scale and offset; this function is what consumes
///     that proof, which is why the guard runs first and the init fails before reaching here.
///   * the 24 self-KV `_in`/`_out` pairs - bound to the ping-pong sets, re-bound every step.
///
/// AND IT CHECKS THAT NOTHING WAS MISSED. Every input and output index is marked as it is bound and
/// the unmarked ones are named in the failure. An unbound QNN tensor is not a null-pointer crash:
/// `clientBuf.data` is whatever the deserialised descriptor came with, and the graph executes
/// against it. With 51 inputs, "I bound the ones I thought of" is not a claim worth making.
std::string bindDecoderLocked() {
    GraphSlot &d = g.dec;
    d.inBufs.clear();
    d.inBufs.resize(d.inputs.size());
    d.outBufs.clear();
    d.outBufs.resize(d.outputs.size());
    std::vector<bool> inDone(d.inputs.size(), false);
    std::vector<bool> outDone(d.outputs.size(), false);

    // Allocate + bind one tensor we own the storage for, looked up by name.
    //
    // THE TYPE CHECK IS AN EQUALITY ON dataType, NOT ON elementSize(). Those are not the same
    // question and the difference is the monotonic-argmax argument. elementSize() collapses
    // INT_32 / UINT_32 / SFIXED_32 / UFIXED_32 / FLOAT_32 to 4 and the five 16-bit types to 2, so a
    // width-only check accepts an asset re-export that made `logits` SFIXED_POINT_16 - and read as
    // signed two's complement the raw-code ordering INVERTS, so every argmax in this file is
    // silently wrong while every size, every bind and every buffer stays correct. Likewise a
    // FLOAT_32 `input_ids` would take our int32 store as a denormal. Four comparisons.
    auto own = [&](const char *nm, std::vector<Qnn_Tensor_t> &ts, std::vector<AlignedBuf> &bufs,
                   std::map<std::string, size_t> &index, std::vector<bool> &done, const char *what,
                   size_t &idxOut, Qnn_DataType_t wantType) -> std::string {
        auto it = index.find(nm);
        if (it == index.end()) {
            return std::string("decoder ") + what + " '" + nm + "' not found by name";
        }
        idxOut = it->second;
        const Qnn_DataType_t dt = tensorDataType(ts[idxOut]);
        if (dt != wantType) {
            return std::string("decoder ") + what + " '" + nm + "' is " + dtypeName(dt) +
                   ", expected " + dtypeName(wantType) +
                   " (same width is not the same type: a signed or float re-export keeps every "
                   "size correct and inverts the raw-code ordering the argmax depends on)";
        }
        const uint64_t need = tensorBytes(ts[idxOut]);
        if (need == 0 || need > 0xFFFFFFFFull) {
            return std::string("decoder ") + what + " '" + nm + "': computed " +
                   std::to_string(need) + " B, which cannot be bound";
        }
        if (!bufs[idxOut].alloc(static_cast<size_t>(need))) {
            return std::string("decoder ") + what + " '" + nm + "': alloc " +
                   std::to_string(need) + " B failed";
        }
        tensorSetClientBuf(ts[idxOut], bufs[idxOut].p, static_cast<uint32_t>(need));
        done[idxOut] = true;
        LOGI("  bind decoder %s %-16s %-9s %-20s %" PRIu64 " B @ %p", what, nm, dtypeName(dt),
             shapeStr(ts[idxOut]).c_str(), need, bufs[idxOut].p);
        return "";
    };

    // input_ids and position_ids are PLAIN int32 - un-quantised, written as integers. The decoder
    // takes the token id and the position as numbers, not as codes. attention_mask and logits are
    // ufixed16: the mask because its two codes are quantised (see checkMaskCodesLocked), the logits
    // because UNSIGNED fixed point is what makes an argmax over the raw codes exact.
    std::string err = own(kInputIds, d.inputs, d.inBufs, d.inIndex, inDone, "IN",
                          g.decInputIdsIdx, QNN_DATATYPE_INT_32);
    if (!err.empty()) return err;
    err = own(kPositionIds, d.inputs, d.inBufs, d.inIndex, inDone, "IN", g.decPositionIdsIdx,
              QNN_DATATYPE_INT_32);
    if (!err.empty()) return err;
    err = own(kAttentionMask, d.inputs, d.inBufs, d.inIndex, inDone, "IN", g.decMaskIdx,
              QNN_DATATYPE_UFIXED_POINT_16);
    if (!err.empty()) return err;
    err = own(kLogits, d.outputs, d.outBufs, d.outIndex, outDone, "OUT", g.decLogitsIdx,
              QNN_DATATYPE_UFIXED_POINT_16);
    if (!err.empty()) return err;

    // THE POSITION BOUND AND THE ARGMAX BOUND COME FROM THE ASSET, not from a constant here. The
    // mask is [1,1,1,200] and the self-KV is 199 deep: positions 0..198 execute, which is 199 of
    // the mask's 200 columns and an exact fit for the cache.
    g.maskLen = static_cast<uint32_t>(d.inBufs[g.decMaskIdx].n / 2);
    g.vocab = static_cast<uint32_t>(d.outBufs[g.decLogitsIdx].n / 2);
    if (g.maskLen < 2) {
        return "decoder attention_mask is " + std::to_string(g.maskLen) +
               " positions wide; a decode needs at least 2";
    }
    if (g.vocab == 0) return "decoder logits carries no vocabulary";
    if (kLangTokenLast >= static_cast<int32_t>(g.vocab) || kEotToken >= static_cast<int32_t>(g.vocab)) {
        return "decoder logits vocabulary " + std::to_string(g.vocab) +
               " does not contain this tokenizer's special ids (EOT " + std::to_string(kEotToken) +
               ", last language token " + std::to_string(kLangTokenLast) + ")";
    }

    // The two mask codes this loop writes 199 times per segment, checked against the asset's own
    // quantisation rather than against a comment.
    err = checkMaskCodesLocked();
    if (!err.empty()) return err;

    // ---- the 24 cross-KV inputs: ALIASED ONTO THE ENCODER'S OUTPUT BUFFERS, zero copy ----------
    for (uint32_t layer = 0; layer < kCrossKvLayers; ++layer) {
        for (const char *kind : {"k_cache_cross_", "v_cache_cross_"}) {
            const std::string name = std::string(kind) + std::to_string(layer);
            auto dit = d.inIndex.find(name);
            if (dit == d.inIndex.end()) return "decoder cross-KV '" + name + "' not found by name";
            auto eit = g.enc.outIndex.find(name);
            if (eit == g.enc.outIndex.end()) return "encoder cross-KV '" + name + "' not found";
            AlignedBuf &src = g.enc.outBufs[eit->second];
            if (!src.p) return "cross-KV '" + name + "': the encoder's buffer is not allocated";
            const uint64_t need = tensorBytes(d.inputs[dit->second]);
            if (need != src.n) {
                return "cross-KV '" + name + "': the decoder wants " + std::to_string(need) +
                       " B but the encoder's bound buffer is " + std::to_string(src.n) + " B";
            }
            tensorSetClientBuf(d.inputs[dit->second], src.p, static_cast<uint32_t>(need));
            inDone[dit->second] = true;
        }
    }
    LOGI("  bind decoder cross-KV: %u tensors aliased onto the encoder's output buffers (0 B copied)",
         kCrossKvLayers * 2);

    // ---- the 24 self-KV pairs and the two ping-pong sets ---------------------------------------
    g.selfInIdx.clear();
    g.selfOutIdx.clear();
    g.selfKv[0].clear();
    g.selfKv[1].clear();
    g.selfKvBytes = 0;
    for (const char *kind : {"k_cache_self_", "v_cache_self_"}) {
        const bool isK = (kind[0] == 'k');
        for (uint32_t layer = 0; layer < kCrossKvLayers; ++layer) {
            const std::string base = std::string(kind) + std::to_string(layer);
            auto iit = d.inIndex.find(base + "_in");
            if (iit == d.inIndex.end()) return "decoder self-KV '" + base + "_in' not found";
            auto oit = d.outIndex.find(base + "_out");
            if (oit == d.outIndex.end()) return "decoder self-KV '" + base + "_out' not found";

            // THE "EXACT FIT" IS A RELATION BETWEEN TWO DIFFERENT TENSORS, SO IT IS CHECKED
            // BETWEEN THEM. `lastPosition` is derived from attention_mask (maskLen - 2 = 198), and
            // the whole "positions 0..198 write cache slots 0..198, an exact fit" argument depends
            // on the self-KV being maskLen - 1 = 199 deep. Nothing else in this file compares the
            // two: g.selfKvBytes is only checked for consistency ACROSS the 24 buffers, so a
            // re-export that changed the cache depth without changing the mask width would drive
            // the loop past the end of the cache with the graph doing the indexing and no signal
            // at load or on the host.
            // k is [heads, 1, headDim, depth]; v is [heads, 1, depth, headDim].
            const uint32_t rank = tensorRank(d.inputs[iit->second]);
            const uint32_t *sdims = tensorDims(d.inputs[iit->second]);
            if (rank < 3 || !sdims) {
                return "decoder self-KV '" + base + "_in' has rank " + std::to_string(rank) +
                       "; the cache depth cannot be located";
            }
            const uint32_t depth = isK ? sdims[rank - 1] : sdims[rank - 2];
            if (depth != g.maskLen - 1) {
                return "decoder self-KV '" + base + "_in' is " + std::to_string(depth) +
                       " deep but attention_mask is " + std::to_string(g.maskLen) +
                       " wide; positions 0.." + std::to_string(g.maskLen - 2) +
                       " execute and the cache must be exactly " + std::to_string(g.maskLen - 1) +
                       " deep for that to be an exact fit";
            }

            // THE SECOND ALIAS THIS DESIGN RESTS ON, and it gets the same treatment as the first.
            // The ping-pong hands step N's `_out` buffer to step N+1 as its `_in`, which is only
            // sound if the two descriptors are the same tensor - same dtype, same shape, same
            // affine transform. If a re-export ever gave the two sides different scales, the
            // decoder would read its own cache back under the wrong transform every step: not a
            // crash, not garbage, just steadily wrong attention and a fluent wrong transcript.
            const std::string cmp = aliasCompare(base, d.inputs[iit->second],
                                                 d.outputs[oit->second]);
            if (!cmp.empty()) return cmp + " (self-KV _in vs _out; the ping-pong requires them to " +
                                     "be the same tensor)";

            const uint64_t need = tensorBytes(d.inputs[iit->second]);
            if (need == 0 || need > 0xFFFFFFFFull) {
                return "decoder self-KV '" + base + "': computed " + std::to_string(need) + " B";
            }
            if (g.selfKvBytes == 0) {
                g.selfKvBytes = static_cast<uint32_t>(need);
            } else if (g.selfKvBytes != need) {
                return "decoder self-KV '" + base + "' is " + std::to_string(need) +
                       " B but the others are " + std::to_string(g.selfKvBytes) +
                       " B; the ping-pong sets are one size";
            }
            g.selfInIdx.push_back(iit->second);
            g.selfOutIdx.push_back(oit->second);
            inDone[iit->second] = true;
            outDone[oit->second] = true;
        }
    }
    const size_t selfCount = g.selfInIdx.size();
    for (int s = 0; s < 2; ++s) {
        g.selfKv[s].resize(selfCount);
        for (size_t i = 0; i < selfCount; ++i) {
            if (!g.selfKv[s][i].alloc(g.selfKvBytes)) {
                return "decoder self-KV set " + std::to_string(s) + " buffer " +
                       std::to_string(i) + ": alloc " + std::to_string(g.selfKvBytes) + " B failed";
            }
        }
    }
    LOGI("  bind decoder self-KV: 2 sets x %zu x %u B = %zu B ping-pong", selfCount, g.selfKvBytes,
         2 * selfCount * static_cast<size_t>(g.selfKvBytes));

    // ---- nothing may be left unbound ----------------------------------------------------------
    for (size_t i = 0; i < inDone.size(); ++i) {
        if (!inDone[i]) {
            const char *nm = tensorName(d.inputs[i]);
            return std::string("decoder input '") + (nm ? nm : "?") +
                   "' (index " + std::to_string(i) + " of " + std::to_string(inDone.size()) +
                   ") was never bound; the graph would execute against whatever its descriptor "
                   "arrived with";
        }
    }
    for (size_t i = 0; i < outDone.size(); ++i) {
        if (!outDone[i]) {
            const char *nm = tensorName(d.outputs[i]);
            return std::string("decoder output '") + (nm ? nm : "?") +
                   "' (index " + std::to_string(i) + " of " + std::to_string(outDone.size()) +
                   ") was never bound";
        }
    }

    g.decBound = true;
    LOGI("decoder bound: %zu inputs / %zu outputs, all by name; mask %u positions, vocab %u, "
         "positions 0..%u execute", d.inputs.size(), d.outputs.size(), g.maskLen, g.vocab,
         g.maskLen - 2);
    return "";
}

/// Points the 24 self-KV inputs at set [inSet] and the 24 outputs at the other one. 48
/// `tensorSetClientBuf` calls per token, and not one byte moved.
void bindSelfKvLocked(int inSet) {
    const int outSet = 1 - inSet;
    for (size_t i = 0; i < g.selfInIdx.size(); ++i) {
        tensorSetClientBuf(g.dec.inputs[g.selfInIdx[i]], g.selfKv[inSet][i].p, g.selfKvBytes);
        tensorSetClientBuf(g.dec.outputs[g.selfOutIdx[i]], g.selfKv[outSet][i].p, g.selfKvBytes);
    }
    g.selfInSet = inSet;
}

/// Zeroes BOTH ping-pong sets and re-binds set 0 as the input side.
///
/// The zero is a quantisation code, not the value zero - but every cache slot above the current
/// position is masked out by `attention_mask` anyway, so what is in them cannot reach the
/// attention. Zeroing is about determinism: the previous segment's cache must not be able to leak
/// into this one through a slot that was written once and then masked inconsistently.
void zeroSelfKvLocked() {
    for (int s = 0; s < 2; ++s) {
        for (auto &b : g.selfKv[s]) {
            if (b.p) memset(b.p, 0, b.n);
        }
    }
    bindSelfKvLocked(0);
}

// ---------------------------------------------------------------- C2: mask, THEN argmax

/// THE SUPPRESSION MASK IS APPLIED TO THE LOGITS, AND ONLY THEN IS THE ARGMAX SCANNED.
///
/// This ordering is the reason the whole decode loop lives on this side of the JNI boundary. Both
/// halves have to be here, in this order, in one function: a caller that receives an argmax has
/// already lost the information it would need to honour a mask, because the runner-up is gone.
///
/// The mask writes [kLogitFloor] rather than subtracting or skipping, because a skip-list inside
/// the scan is O(vocab x suppress) and a "mask" that the scan consults is the same construct
/// spelled in a way that lets a future edit move the two apart.
///
/// Returns the winning token id, or -1 when every logit is at the floor - which is a dead graph
/// output, not a token.
int32_t suppressThenArgmax(uint16_t *logits, uint32_t vocab,
                           const std::vector<int32_t> &suppress,
                           const std::vector<int32_t> &beginSuppress,
                           bool applyBegin) {
    // ---- the mask, first. Every id was range-checked once, before the loop started.
    for (int32_t id : suppress) {
        logits[id] = kLogitFloor;
    }
    if (applyBegin) {
        for (int32_t id : beginSuppress) {
            logits[id] = kLogitFloor;
        }
    }
    // ---- and only now the scan.
    int32_t best = -1;
    uint16_t bestVal = kLogitFloor;
    for (uint32_t i = 0; i < vocab; ++i) {
        if (logits[i] > bestVal) {
            bestVal = logits[i];
            best = static_cast<int32_t>(i);
        }
    }
    return best;
}

/// Argmax restricted to `[lo, hi)`, for the language-detect pass.
///
/// A range restriction is strictly simpler than the 1589-entry mask above, and it sits on the SAME
/// side of the boundary for the same reason: the caller must never be handed an unrestricted argmax
/// and asked to decide whether it counts.
int32_t argmaxInRange(const uint16_t *logits, uint32_t lo, uint32_t hi) {
    int32_t best = -1;
    uint16_t bestVal = kLogitFloor;
    for (uint32_t i = lo; i < hi; ++i) {
        if (logits[i] > bestVal) {
            bestVal = logits[i];
            best = static_cast<int32_t>(i);
        }
    }
    return best;
}

// ------------------------------------------------- Q10a-D1: instrumentation, content-safe by
// ------------------------------------------------- construction

/// Renders a token id for a diagnostic line WITHOUT ever printing a text token's id.
///
/// **This is where the privacy rule lives, so that no call site has to remember it.** Every id
/// below EOT is a piece of what the user just said: print one and this log contains transcript
/// content, print a hundred and it contains the transcript. Ids at or above EOT (50257) are prompt
/// scaffolding, language tags, control tokens and timestamps - configuration and metadata, never
/// words - so those are named verbatim, and everything else collapses to the constant string
/// "text-token".
///
/// Applied even to the PROMPT, which today is four specials and therefore prints in full. That is
/// belt and braces with a reason: whisper's prompt format has a `<|startofprev|>` form that carries
/// the previous segment's TEXT tokens, and if this tier ever adopts it the prompt echo below would
/// start printing transcript. Routing the prompt through the same rule makes that impossible rather
/// than merely unlikely.
const char *diagToken(int32_t id, char *buf, size_t n) {
    if (id < 0) snprintf(buf, n, "none");
    else if (id >= kEotToken) snprintf(buf, n, "%d", id);
    else snprintf(buf, n, "text-token");
    return buf;
}

/// The prompt as a bracketed list, each id through [diagToken]. Capped, because a caller may pass
/// up to 198 ids and a log line is not a place for them.
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

/// Raw, PRE-MASK logits health: the two rails and where the peak sits.
struct LogitsHealth {
    uint16_t lo = 0;
    uint16_t hi = 0;
    int32_t argmax = -1;
};

/// Scans the whole vocabulary once. **`min == max` is the single most decisive thing this round can
/// report**: a constant distribution is not a decoding fault at all - it means the decoder graph
/// produced nothing, which puts the defect in the cross-KV / encoder-output wiring and rules the
/// prompt out entirely.
///
/// Ties resolve to the FIRST index, exactly as `suppressThenArgmax` and `argmaxInRange` do, so the
/// argmax reported here is the one the decoder would actually have picked rather than a second
/// opinion that could disagree for its own reasons.
LogitsHealth scanLogitsRaw(const uint16_t *logits, uint32_t vocab) {
    LogitsHealth h;
    if (vocab == 0) return h;
    h.lo = logits[0];
    h.hi = logits[0];
    h.argmax = 0;
    for (uint32_t i = 1; i < vocab; ++i) {
        const uint16_t v = logits[i];
        if (v < h.lo) h.lo = v;
        if (v > h.hi) {
            h.hi = v;
            h.argmax = static_cast<int32_t>(i);
        }
    }
    return h;
}

// ------------------------------------------------- Q10a-D2: the ENCODER's input path
//
// D1 instrumented the decode loop and cleared it: the prompt arrives intact, the positions walk
// correctly, the logits are structured (min ~11,000, max ~23,500) and they VARY BY SEGMENT under an
// identical prompt and a zeroed self-KV - which can only mean the cross-KV carries per-segment
// encoder output. And yet the raw argmax is EOT at every position including position 0 given bare
// SOT. A mechanically healthy decoder that says "no speech" to every segment is a decoder attending
// noise, so the remaining window is this file's own input path: quantise -> copy -> layout ->
// execute. The mel FLOATS are already exonerated (the same spectrogram feeds the CPU tier, which
// transcribes this phone perfectly, and the `mel:` row sums check out numerically).
//
// NOTE ON THE SPIKE, because it is the reason this window was never closed: the spike measured
// encoder TIMING. Its input was a deterministic pseudo-random fill and its output was never compared
// against anything. 404.6 ms is a true statement about a graph that has never been shown to compute
// the right answer. This is the first correctness read of the encoder.
//
// Everything below is measurement only. No fix is attempted in this round.

/// Aggregate health of a `uint16` block. Rail counts are separate from min/max deliberately: `min=0
/// max=65535` says the extremes were touched, `atZero=180000` says most of the spectrogram is
/// pinned there, and those are completely different diagnoses.
struct U16Stats {
    uint32_t lo = 0;
    uint32_t hi = 0;
    double mean = 0.0;
    uint32_t atZero = 0;
    uint32_t atMax = 0;
};

U16Stats scanU16Stats(const uint16_t *p, size_t n) {
    U16Stats s;
    if (!p || n == 0) return s;
    uint64_t sum = 0;
    s.lo = p[0];
    s.hi = p[0];
    for (size_t i = 0; i < n; ++i) {
        const uint32_t v = p[i];
        if (v < s.lo) s.lo = v;
        if (v > s.hi) s.hi = v;
        sum += v;
        if (v == 0u) ++s.atZero;
        if (v == 0xFFFFu) ++s.atMax;
    }
    s.mean = static_cast<double>(sum) / static_cast<double>(n);
    return s;
}

/// Aggregate health of a `uint8` block - the cross-KV and self-KV caches.
///
/// `nonzero` answers "was this buffer ever written at all", because `AlignedBuf` memsets to zero and
/// an unwritten buffer is therefore exactly 0.000. `mean` answers the harder question: these tensors
/// are asymmetrically quantised at zero point 128, so a HEALTHY written buffer has a mean near 128,
/// while a buffer full of small values is nonzero everywhere and still wrong.
struct U8Stats {
    uint32_t lo = 0;
    uint32_t hi = 0;
    double mean = 0.0;
    double nonzero = 0.0;
};

U8Stats scanU8Stats(const uint8_t *p, size_t n) {
    U8Stats s;
    if (!p || n == 0) return s;
    uint64_t sum = 0, nz = 0;
    s.lo = p[0];
    s.hi = p[0];
    for (size_t i = 0; i < n; ++i) {
        const uint32_t v = p[i];
        if (v < s.lo) s.lo = v;
        if (v > s.hi) s.hi = v;
        sum += v;
        if (v != 0u) ++nz;
    }
    s.mean = static_cast<double>(sum) / static_cast<double>(n);
    s.nonzero = static_cast<double>(nz) / static_cast<double>(n);
    return s;
}

/// The `(bins, frames)` split this seam is assuming for `input_features`, taken from the tensor's
/// OWN trailing two dimensions rather than from a constant.
///
/// **The split is printed on the line that uses it, and that is the point.** `NpuQuantize` writes
/// 80 rows of 3000 because the plan says the tensor is `[1,80,3000]`. If a re-export ever declared
/// `[1,3000,80]` instead - the NWC form a QAIRT conversion can legitimately produce - the byte count
/// would be IDENTICAL, `kEncoderExpectation`'s 480,000 B check would still pass, the alias guard
/// would still pass, and the encoder would read our row-major block transposed. Nothing in this
/// codebase would say a word. So the geometry is derived here, reported, and compared against
/// Kotlin's independent arithmetic by eye.
struct MelGeometry {
    uint32_t bins = 0;
    uint32_t frames = 0;
    size_t values = 0;
    bool consistent = false;
};

MelGeometry encoderMelGeometryLocked() {
    MelGeometry m;
    if (g.encInputIdx >= g.enc.inputs.size()) return m;
    const Qnn_Tensor_t &t = g.enc.inputs[g.encInputIdx];
    const uint32_t rank = tensorRank(t);
    const uint32_t *d = tensorDims(t);
    if (!d || rank < 2) return m;
    m.bins = d[rank - 2];
    m.frames = d[rank - 1];
    m.values = g.enc.inBufs.empty() ? 0 : g.enc.inBufs[g.encInputIdx].n / sizeof(uint16_t);
    m.consistent = m.bins > 0 && m.frames > 0 &&
                   static_cast<size_t>(m.bins) * m.frames == m.values;
    return m;
}

/// One decoder execute at [position] with [tokenId] as `input_ids`. Writes the three step inputs
/// and runs the graph; the caller owns the argmax and the ping-pong swap.
std::string decodeStepLocked(int32_t tokenId, uint32_t position) {
    *static_cast<int32_t *>(g.dec.inBufs[g.decInputIdsIdx].p) = tokenId;
    *static_cast<int32_t *>(g.dec.inBufs[g.decPositionIdsIdx].p) = static_cast<int32_t>(position);

    // Attend to 0..position inclusive, block everything above. At the last executing position
    // (maskLen - 2) that is maskLen - 1 columns; the final column is never used, which is the
    // arithmetic behind "199 positions in a 200-wide mask".
    uint16_t *mask = static_cast<uint16_t *>(g.dec.inBufs[g.decMaskIdx].p);
    for (uint32_t i = 0; i < g.maskLen; ++i) {
        mask[i] = (i <= position) ? kMaskAttend : kMaskBlocked;
    }

    Qnn_ErrorHandle_t e = g.qnn.graphExecute(
            g.dec.graph,
            g.dec.inputs.data(), static_cast<uint32_t>(g.dec.inputs.size()),
            g.dec.outputs.data(), static_cast<uint32_t>(g.dec.outputs.size()),
            nullptr, nullptr);
    if (e != QNN_SUCCESS) {
        return "graphExecute at position " + std::to_string(position) + ": " + qnnErr(e);
    }
    return "";
}

/// Copies a Java `int[]` into a vector. A null array is an empty vector, deliberately: an empty
/// suppression list is a legitimate (if unwise) configuration, while a null one is the caller
/// having nothing to say.
std::vector<int32_t> jintsToVector(JNIEnv *env, jintArray a) {
    std::vector<int32_t> v;
    if (!a) return v;
    const jsize n = env->GetArrayLength(a);
    if (n <= 0) return v;
    v.resize(static_cast<size_t>(n));
    env->GetIntArrayRegion(a, 0, n, reinterpret_cast<jint *>(v.data()));
    return v;
}

/// Every id the mask will write must be a valid index into the logits buffer. Checked ONCE per
/// segment rather than per token: `suppressThenArgmax` writes `logits[id]` unguarded, so an
/// out-of-range id is a heap write past a 103,730-byte buffer roughly a hundred times over.
std::string checkTokenIdsLocked(const std::vector<int32_t> &ids, const char *what) {
    for (size_t i = 0; i < ids.size(); ++i) {
        if (ids[i] < 0 || ids[i] >= static_cast<int32_t>(g.vocab)) {
            return std::string(what) + "[" + std::to_string(i) + "] = " + std::to_string(ids[i]) +
                   " is outside the vocabulary 0.." + std::to_string(g.vocab - 1);
        }
    }
    return "";
}

// ---------------------------------------------------------------- the sustained power vote

/// Acquire the HTP perf infrastructure. Never fatal: a device that will not offer it runs at the
/// governor's default clocks, which is slow (the spike measured 1007 ms unvoted against 405 ms
/// voted) but correct. Slow and right beats refusing to transcribe.
void acquirePerfInfrastructureLocked() {
    g.perfAvailable = false;
    QnnDevice_Infrastructure_t devInfra = nullptr;
    if (!g.qnn.deviceGetInfrastructure) {
        g.voteNote = "UNAVAILABLE (backend exposes no deviceGetInfrastructure)";
        return;
    }
    Qnn_ErrorHandle_t e = g.qnn.deviceGetInfrastructure(&devInfra);
    if (e != QNN_SUCCESS || !devInfra) {
        g.voteNote = "UNAVAILABLE (deviceGetInfrastructure " + qnnErr(e) + ")";
        return;
    }
    auto *htp = reinterpret_cast<QnnHtpDevice_Infrastructure_t *>(devInfra);
    if (htp->infraType != QNN_HTP_DEVICE_INFRASTRUCTURE_TYPE_PERF ||
        !htp->perfInfra.createPowerConfigId || !htp->perfInfra.setPowerConfig) {
        g.voteNote = "UNAVAILABLE (infraType " + std::to_string(static_cast<int>(htp->infraType)) +
                     ", perf entry points missing)";
        return;
    }
    g.perf = htp->perfInfra;
    g.perfAvailable = true;
}

/// THE SUSTAINED VOTE, exactly as measured (spike run 9: 404.6 ms sustained vs 369.2 ms burst,
/// +9.6% - and that 9.6% is the accepted trade, not a defect to tune out).
///
/// Every field below is a decision, and the burst recipe differs in every one of them:
///   dcvsEnable = 1        DCVS STAYS ON. Between segments the governor may drop the clock, which
///                         is the entire point of a config that gets held for MINUTES. Pinning the
///                         clock is what burns the battery, and a dictation session is not a
///                         benchmark loop.
///   powerMode PERFORMANCE the header's "lower thresholds for maximum performance": DCVS still
///                         governs, but ramps eagerly, so a segment arriving after idle does not
///                         spend its first inference climbing.
///   corners min = SVS     real headroom to fall to while idle.
///   corners target/max    TURBO, NOT MAX_VOLTAGE_CORNER. The top corner is a burst affordance;
///                         TURBO is the highest corner a phone can actually hold.
///   sleepDisable = 0      sleep explicitly ALLOWED (set-flag on, value off): the DSP may idle
///                         between segments.
///   setSleepLatency = 0   leave the platform default rather than force 40 us.
///   rpcControlLatency     100 us. Costs nothing when idle and removes a per-call FastRPC wake.
///   NO RPC POLLING        polling spins to avoid interrupt latency and burns power continuously,
///                         process-wide. It is strictly a burst trick and it is not here.
std::string applySustainedVoteLocked(uint32_t id) {
    std::vector<QnnHtpPerfInfrastructure_PowerConfig_t> cfgs;

    QnnHtpPerfInfrastructure_PowerConfig_t dcvs{};
    dcvs.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_DCVS_V3;
    auto &d = dcvs.dcvsV3Config;
    d.contextId = id;
    d.setDcvsEnable = 1;
    d.dcvsEnable = 1;
    d.powerMode = QNN_HTP_PERF_INFRASTRUCTURE_POWERMODE_PERFORMANCE_MODE;
    d.setSleepLatency = 0;
    d.setSleepDisable = 1;
    d.sleepDisable = 0;
    d.setBusParams = 1;
    d.busVoltageCornerMin = DCVS_VOLTAGE_VCORNER_SVS;
    d.busVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_TURBO;
    d.busVoltageCornerMax = DCVS_VOLTAGE_VCORNER_TURBO;
    d.setCoreParams = 1;
    d.coreVoltageCornerMin = DCVS_VOLTAGE_VCORNER_SVS;
    d.coreVoltageCornerTarget = DCVS_VOLTAGE_VCORNER_TURBO;
    d.coreVoltageCornerMax = DCVS_VOLTAGE_VCORNER_TURBO;
    cfgs.push_back(dcvs);

    QnnHtpPerfInfrastructure_PowerConfig_t lat{};
    lat.option = QNN_HTP_PERF_INFRASTRUCTURE_POWER_CONFIGOPTION_RPC_CONTROL_LATENCY;
    lat.rpcControlLatencyConfig = 100;
    cfgs.push_back(lat);

    std::vector<const QnnHtpPerfInfrastructure_PowerConfig_t *> ptrs;
    ptrs.reserve(cfgs.size() + 1);
    for (auto &c : cfgs) ptrs.push_back(&c);
    ptrs.push_back(nullptr);  // the list is null-terminated, not counted

    Qnn_ErrorHandle_t e = g.perf.setPowerConfig(id, ptrs.data());
    if (e != QNN_SUCCESS) return qnnErr(e);
    return "";
}

/// Arms the vote ONCE for the session and LOGS THE RESULT ON ITS OWN LINE (lesson 6).
///
/// The log line is not decoration. Spike run 7 measured 1007 ms with a median spread of 838-1275 ms
/// because the vote had silently not been applied - and an unvoted Hexagon is indistinguishable
/// from slow silicon from the outside. Whatever happens here, `vote:` appears in logcat exactly
/// once per session with the reason in it, so Q10a never has to guess which of the two it is
/// looking at.
///
/// A failed vote NEVER fails nativeInit. The vote is a performance request; the session is correct
/// without it.
void armSustainedVoteLocked() {
    acquirePerfInfrastructureLocked();
    if (g.perfAvailable) {
        Qnn_ErrorHandle_t ce = g.perf.createPowerConfigId(0, 0, &g.powerConfigId);
        if (ce != QNN_SUCCESS) {
            g.voteNote = "CREATE_FAILED " + qnnErr(ce);
        } else {
            const std::string ve = applySustainedVoteLocked(g.powerConfigId);
            if (!ve.empty()) {
                g.voteNote = "SET_FAILED " + ve;
                if (g.perf.destroyPowerConfigId) g.perf.destroyPowerConfigId(g.powerConfigId);
                g.powerConfigId = 0;
            } else {
                g.voted = true;
                g.voteNote = "OK sustained: dcvsEnable=1 PERFORMANCE corners=SVS..TURBO "
                             "sleepAllowed defaultSleepLatency rpc=100us NO-polling";
            }
        }
    }
    LOGI("vote: %s", g.voteNote.c_str());
    if (!g.voted) {
        LOGW("the HTP is running UNVOTED - expect roughly 2.5x the measured encode latency "
             "(spike run 7: 1007 ms unvoted vs 405 ms sustained). This is a power-saver floor, "
             "not slow silicon.");
    }
}

/// Releases the vote. Called from teardown BEFORE anything else, so the config id is handed back
/// while the backend that issued it is still alive.
void releaseSustainedVoteLocked() {
    if (g.voted && g.perfAvailable && g.perf.destroyPowerConfigId) {
        Qnn_ErrorHandle_t de = g.perf.destroyPowerConfigId(g.powerConfigId);
        LOGI("vote: released (%s)", de == QNN_SUCCESS ? "OK" : qnnErr(de).c_str());
    }
    g.voted = false;
    g.powerConfigId = 0;
    g.perfAvailable = false;
    g.perf = {};
    g.voteNote = "not attempted";
}

// ---------------------------------------------------------------- teardown

/// Releases everything in reverse order of creation. Safe to call on a partially-built state (every
/// handle is null-checked) and safe to call twice, which is what makes the failure paths in
/// nativeInit able to just call it and return.
void releaseLocked() {
    // The vote goes first: it is a session-scoped request against the backend that is about to be
    // freed, and handing the config id back afterwards would be handing it to a dead backend.
    releaseSustainedVoteLocked();

    if (g.enc.context) g.qnn.contextFree(g.enc.context, nullptr);
    if (g.dec.context) g.qnn.contextFree(g.dec.context, nullptr);
    // Frees the client buffers too - the contexts that could have been executing against them are
    // gone by this line, and the order matters in that direction only.
    g.enc.clear();
    g.dec.clear();
    g.encInputIdx = 0;
    g.encInputScale = 0.0f;
    g.encInputZeroPoint = 0;

    // The decoder's own state. The ping-pong sets are freed here and NOT by GraphSlot::clear() -
    // they are not one-per-tensor and never lived in dec.inBufs/outBufs, precisely because the
    // 24 cross-KV inputs must never own a buffer (they alias the encoder's, which g.enc.clear()
    // above has just freed - so nothing may still be pointing at them after this line).
    g.selfKv[0].clear();
    g.selfKv[1].clear();
    g.selfInIdx.clear();
    g.selfOutIdx.clear();
    g.selfKvBytes = 0;
    g.selfInSet = 0;
    g.decBound = false;
    g.decInputIdsIdx = 0;
    g.decPositionIdsIdx = 0;
    g.decMaskIdx = 0;
    g.decLogitsIdx = 0;
    g.maskLen = 0;
    g.vocab = 0;
    g.encoded = false;

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

    // C7 — BEFORE ANYTHING BINDS. Both graphs are now enumerated and name-indexed; nothing has been
    // allocated yet. If the 24 cross-KV pairs are not identical, the zero-copy alias the whole tier
    // is built on is unsound, and the symptom would be plausible wrong text rather than an error.
    err = aliasGuardLocked();
    if (!err.empty()) {
        releaseLocked();
        return env->NewStringUTF(failure("init: " + err).c_str());
    }

    // The encoder's quantisation parameters, read from its own metadata and cached for
    // nativeInputQuant. Read before the buffers so a session that cannot be fed correctly is
    // refused before it allocates 27 MiB.
    err = readEncoderInputQuantLocked();
    if (!err.empty()) {
        releaseLocked();
        return env->NewStringUTF(failure("init: " + err).c_str());
    }

    // Session-scoped buffers, allocated ONCE. The 24 output buffers are the cross-KV, and Q4 binds
    // the decoder's cross-KV inputs to these very pointers - never a copy, never re-fed per token.
    // That is why they are owned by the session rather than by an encode call.
    err = allocateAndBind(g.enc.inputs, g.enc.inBufs, kEncoderExpectation.label, "IN");
    if (!err.empty()) {
        releaseLocked();
        return env->NewStringUTF(failure("init: " + err).c_str());
    }
    err = allocateAndBind(g.enc.outputs, g.enc.outBufs, kEncoderExpectation.label, "OUT");
    if (!err.empty()) {
        releaseLocked();
        return env->NewStringUTF(failure("init: " + err).c_str());
    }

    // Every decoder tensor, by name, once. This is where the 24 cross-KV inputs are pointed at the
    // encoder's output buffers - the bind the alias guard above exists to make safe.
    err = bindDecoderLocked();
    if (!err.empty()) {
        releaseLocked();
        return env->NewStringUTF(failure("init: " + err).c_str());
    }

    // Armed ONCE, here, at the end - not per segment, and not before the 342 MB deserialise (which
    // the spike also measured unvoted, so the 525 ms cold-load figure still means what it says).
    // Released in nativeRelease. Never fails the init.
    armSustainedVoteLocked();

    g.initialised = true;
    // A fresh session has encoded nothing. Stated rather than inherited from releaseLocked, so the
    // invariant is readable at the point the session becomes usable.
    g.encoded = false;
    g.lastError.clear();
    LOGI("nativeInit OK - encoder graph '%s' (%zu in / %zu out), decoder graph '%s' "
         "(%zu in / %zu out)",
         g.enc.name.c_str(), g.enc.inputs.size(), g.enc.outputs.size(),
         g.dec.name.c_str(), g.dec.inputs.size(), g.dec.outputs.size());
    return env->NewStringUTF("");
}

/// The encoder's input quantisation as `[scale, zeroPoint]`, read from `input_features`' own
/// `Qnn_QuantizeParams_t` at load. Empty array on failure, with the reason in nativeLastError.
///
/// THIS TRANSPORT IS THE POINT OF Q3 STEP 3. The Kotlin quantiser needs two numbers that belong to
/// the asset; without a declared way to fetch them, "read them from metadata, never hardcode" is
/// an instruction with no mechanism, and the two literals from the plan's baked-facts block end up
/// pasted into NpuQuantize where they will outlive the asset that produced them.
///
/// `zeroPoint` is exactly representable as a float (integers below 2^24 are), so the FloatArray
/// carries it without loss.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_whispereverywhere_npu_QnnAsrNative_nativeInputQuant(
        JNIEnv *env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g.mu);
    if (!g.initialised) {
        failure("quant: session not initialised");
        return env->NewFloatArray(0);
    }
    jfloatArray out = env->NewFloatArray(2);
    if (!out) {
        // EMPTY, never null. The KDoc promises "an empty array on failure", and Kotlin's
        // `FloatArray` return type is non-nullable: handing back nullptr here makes a
        // platform-type null cross the boundary and the caller's `.size` throws a
        // NullPointerException from a line that reads like it cannot throw.
        failure("quant: NewFloatArray(2) failed");
        return env->NewFloatArray(0);
    }
    const jfloat v[2] = {g.encInputScale, static_cast<jfloat>(g.encInputZeroPoint)};
    env->SetFloatArrayRegion(out, 0, 2, v);
    // Success must not leave an older stage's message readable from nativeLastError() - a caller
    // that checks the error text after a call that worked would read someone else's failure.
    g.lastError.clear();
    return out;
}

/// One encoder pass. [jMel] is the ALREADY-QUANTISED `ufixed16` block NpuQuantize produced - not
/// the float mel - and it is copied into the bound `input_features` buffer, then executed.
///
/// After this returns "", the 24 cross-KV output buffers hold this segment's encoder state AND ARE
/// ALREADY THE DECODER'S BOUND CROSS-KV INPUTS (Q4). There is nothing further to move: the decode
/// loop reads them in place.
///
/// ~405 ms on a voted 8 Gen 3 (spike run 9, 9-run median). Never call this from Main; it holds the
/// session mutex for the whole execute.
extern "C" JNIEXPORT jstring JNICALL
Java_com_whispereverywhere_npu_QnnAsrNative_nativeEncode(
        JNIEnv *env, jobject /* this */, jobject jMel) {
    std::lock_guard<std::mutex> lock(g.mu);
    // Cleared FIRST, on every path. A graphExecute that fails part way may have written some of the
    // 24 cross-KV buffers, and half a segment decodes as fluently as a whole one.
    g.encoded = false;
    if (!g.initialised) {
        return env->NewStringUTF(failure("encode: session not initialised").c_str());
    }
    if (!jMel) {
        return env->NewStringUTF(failure("encode: quantised mel buffer is null").c_str());
    }
    void *src = env->GetDirectBufferAddress(jMel);
    const jlong cap = env->GetDirectBufferCapacity(jMel);
    if (!src || cap < 0) {
        // GetDirectBufferAddress returns null for a heap ByteBuffer, and a heap buffer is the one
        // mistake that would otherwise reach here silently - allocate() and allocateDirect() differ
        // by four characters. NpuQuantize.newInputFeaturesBuffer() exists so nobody types either.
        return env->NewStringUTF(failure(
                "encode: quantised mel is not a direct ByteBuffer (use "
                "NpuQuantize.newInputFeaturesBuffer())").c_str());
    }
    if (g.encInputIdx >= g.enc.inBufs.size()) {
        return env->NewStringUTF(failure("encode: input_features is not bound").c_str());
    }
    AlignedBuf &dst = g.enc.inBufs[g.encInputIdx];
    if (!dst.p || static_cast<jlong>(dst.n) != cap) {
        return env->NewStringUTF(failure(
                "encode: quantised mel is " + std::to_string(static_cast<long long>(cap)) +
                " B, input_features needs " + std::to_string(dst.n) +
                " B (the 960,000 B float mel is not this buffer)").c_str());
    }
    memcpy(dst.p, src, dst.n);

    // ---- Q10a-D2: read the buffer the DSP is about to read, from the pointer it is bound to -----
    //
    // POST-COPY, PRE-EXECUTE, and both halves of that are load-bearing. Before the copy this block
    // holds the PREVIOUS segment (it is session-scoped and never cleared), so a summary taken above
    // the memcpy describes the wrong audio while looking identical. After the execute the encoder
    // may have touched it. This is the only window in which the numbers mean what they say.
    //
    // And it reads `dst.p` - the pointer that was handed to `tensorSetClientBuf` at init - not the
    // JNI source address. Reading the source would confirm what Kotlin wrote and prove nothing
    // about what the graph will see, which is the entire question this round is asking.
    if (g.diag) {
        const Qnn_Tensor_t &inT = g.enc.inputs[g.encInputIdx];
        const Qnn_QuantizeParams_t *inQ = tensorQuantParams(inT);
        const float mScale = inQ ? inQ->scaleOffsetEncoding.scale : 0.0f;
        const int32_t mOffset = inQ ? inQ->scaleOffsetEncoding.offset : 0;

        // ITEM 6 - the params AS READ, beside the params the copy path actually used. `metaScale`
        // and `usedScale` are the same number read twice by different routes: the descriptor now,
        // and the value cached at init that nativeInputQuant handed to Kotlin. They can only differ
        // if something overwrote the cache, and if they ever do, every other line here is suspect.
        // The DIMS and the DATA FORMAT ride along because they are the transpose hypothesis stated
        // in full, and because every earlier printing of them went to WE-NPU - a tag the owner's
        // `adb logcat -s WE-DIAG` capture has been filtering out since Q1.
        const Qnn_Tensor_t *xT = nullptr;
        auto xit = g.enc.outIndex.find("k_cache_cross_0");
        if (xit != g.enc.outIndex.end()) xT = &g.enc.outputs[xit->second];
        const Qnn_QuantizeParams_t *xQ = xT ? tensorQuantParams(*xT) : nullptr;
        LOGDIAG("npu-debug: quant in=%s dims=%s dtype=%s fmt=%s metaScale=%.9g metaOffset=%d "
                "usedScale=%.9g usedZp=%d | k_cache_cross_0 dims=%s dtype=%s fmt=%s scale=%.9g "
                "offset=%d",
                tensorName(inT), shapeStr(inT).c_str(), dtypeName(tensorDataType(inT)),
                dataFormatName(tensorDataFormat(inT)), static_cast<double>(mScale), mOffset,
                static_cast<double>(g.encInputScale), g.encInputZeroPoint,
                xT ? shapeStr(*xT).c_str() : "?", xT ? dtypeName(tensorDataType(*xT)) : "?",
                xT ? dataFormatName(tensorDataFormat(*xT)) : "?",
                xQ ? static_cast<double>(xQ->scaleOffsetEncoding.scale) : 0.0,
                xQ ? xQ->scaleOffsetEncoding.offset : 0);

        const auto *q = static_cast<const uint16_t *>(dst.p);
        const MelGeometry geo = encoderMelGeometryLocked();
        const size_t values = dst.n / sizeof(uint16_t);

        // ITEM 1 - the distribution. A healthy quantised mel spans a wide interior band with its
        // mean somewhere near the zero point and NO pile-up on either rail. atZero == values is a
        // buffer of silence; a large atMax is a scale applied the wrong way round.
        const U16Stats s = scanU16Stats(q, values);
        LOGDIAG("npu-debug: inbuf bytes=%zu values=%zu min=%u max=%u mean=%.1f atZero=%u atMax=%u zp=%d",
                dst.n, values, s.lo, s.hi, s.mean, s.atZero, s.atMax, g.encInputZeroPoint);

        // ITEM 2 - THE TRANSPOSE DETECTOR, and the decisive line of this round.
        //
        // `sumFirstRow` is the first `frames` codes as stored. `sumColStride` is one code per row,
        // taken `frames` apart. Kotlin computes the SAME two quantities from the float mel by its
        // own arithmetic and prints them on `npu-debug: melprobe`. Read the pair:
        //   both match         -> the copy is byte-exact and the DSP is bound to the buffer Kotlin
        //                         filled. (It does NOT prove the encoder wants this layout; only
        //                         the device can settle that, and `dims=` above is the other half.)
        //   crossed over       -> the buffer holds Kotlin's data transposed.
        //   neither matches    -> endianness, a wrong offset, or a different buffer entirely.
        if (geo.consistent) {
            uint64_t sumRow = 0, sumCol = 0;
            for (uint32_t f = 0; f < geo.frames; ++f) sumRow += q[f];
            for (uint32_t b = 0; b < geo.bins; ++b) sumCol += q[static_cast<size_t>(b) * geo.frames];
            LOGDIAG("npu-debug: layout bins=%u frames=%u sumFirstRow=%" PRIu64 " sumColStride=%" PRIu64
                    " (row = q[0..%u], col = q[0,%u,..])",
                    geo.bins, geo.frames, sumRow, sumCol, geo.frames - 1, geo.frames);

            // ITEM 3 - three cells dequantised through the metadata's own affine transform, to be
            // read beside the float mel's same three cells on the melprobe line. This is the one
            // reading that catches a sign or offset misapplication exactly: a zero point applied
            // with the wrong sign leaves every aggregate above looking plausible and moves every
            // dequantised value by a constant 2 x zp x scale.
            const size_t c0 = 0;
            const size_t c1 = geo.frames / 2;
            const size_t c2 = static_cast<size_t>(geo.bins / 2) * geo.frames + geo.frames / 2;
            const double sc = static_cast<double>(g.encInputScale);
            const int32_t zp = g.encInputZeroPoint;
            LOGDIAG("npu-debug: dequant i=%zu q=%u->%.6f | i=%zu q=%u->%.6f | i=%zu q=%u->%.6f "
                    "(scale=%.9g zp=%d)",
                    c0, q[c0], sc * (static_cast<double>(q[c0]) - zp),
                    c1, q[c1], sc * (static_cast<double>(q[c1]) - zp),
                    c2, q[c2], sc * (static_cast<double>(q[c2]) - zp),
                    sc, zp);
        } else {
            LOGDIAG("npu-debug: layout UNUSABLE bins=%u frames=%u values=%zu (bins*frames must equal "
                    "values; the transpose and dequant reads are skipped rather than indexed blind)",
                    geo.bins, geo.frames, geo.values);
        }
    }

    auto t0 = Clock::now();
    Qnn_ErrorHandle_t e = g.qnn.graphExecute(
            g.enc.graph,
            g.enc.inputs.data(), static_cast<uint32_t>(g.enc.inputs.size()),
            g.enc.outputs.data(), static_cast<uint32_t>(g.enc.outputs.size()),
            nullptr, nullptr);
    const double ms = msSince(t0);
    if (e != QNN_SUCCESS) {
        return env->NewStringUTF(failure("encode: graphExecute " + qnnErr(e)).c_str());
    }
    LOGI("encode: graphExecute OK in %.1f ms (vote: %s)", ms, g.voteNote.c_str());

    // ITEM 4 - did the encoder actually WRITE its outputs, and with what dynamic range?
    //
    // These two buffers ARE the decoder's cross-KV inputs; nothing is copied between the passes, so
    // whatever is here is exactly what the decode loop attends. Both are looked up BY NAME and their
    // output INDICES are printed beside them, because "buffers 0 and 12" is an assumption about the
    // graph's output ordering and this line is the place to stop assuming it.
    //   nonzero=0.000        -> the encoder never wrote them; the decoder is attending AlignedBuf's
    //                           zeros and would say "no speech" to every segment, forever.
    //   nonzero~1.0 mean~128 -> written, and centred on the zero point these tensors are quantised
    //                           around: healthy.
    //   nonzero~1.0 mean~0   -> written, but with a distribution nothing sane produced.
    if (g.diag) {
        auto kit = g.enc.outIndex.find("k_cache_cross_0");
        auto vit = g.enc.outIndex.find("v_cache_cross_0");
        const bool haveK = kit != g.enc.outIndex.end() && g.enc.outBufs[kit->second].p;
        const bool haveV = vit != g.enc.outIndex.end() && g.enc.outBufs[vit->second].p;
        const U8Stats ks = haveK
                ? scanU8Stats(static_cast<const uint8_t *>(g.enc.outBufs[kit->second].p),
                              g.enc.outBufs[kit->second].n)
                : U8Stats{};
        const U8Stats vs = haveV
                ? scanU8Stats(static_cast<const uint8_t *>(g.enc.outBufs[vit->second].p),
                              g.enc.outBufs[vit->second].n)
                : U8Stats{};
        LOGDIAG("npu-debug: crossKV k_cache_cross_0[out=%zu bytes=%zu min=%u max=%u mean=%.1f "
                "nonzero=%.3f] v_cache_cross_0[out=%zu bytes=%zu min=%u max=%u mean=%.1f nonzero=%.3f]",
                haveK ? kit->second : 0u, haveK ? g.enc.outBufs[kit->second].n : 0u,
                ks.lo, ks.hi, ks.mean, ks.nonzero,
                haveV ? vit->second : 0u, haveV ? g.enc.outBufs[vit->second].n : 0u,
                vs.lo, vs.hi, vs.mean, vs.nonzero);
    }
    // The 24 cross-KV buffers now hold THIS segment, and they are already the decoder's bound
    // inputs. Only now may a decode run.
    g.encoded = true;
    g.lastError.clear();
    return env->NewStringUTF("");
}

/// THE WHOLE GREEDY DECODE LOOP FOR ONE SEGMENT, in one JNI call.
///
/// [jPrompt] is `[SOT, <|lang|>, TRANSCRIBE, NO_TIMESTAMPS]` from `NpuDecodePolicy`; [jSuppress] is
/// the always-on mask (88 generation-config ids + 1501 timestamps); [jBeginSuppress] is `[220,
/// EOT]`, applied at the FIRST GENERATED step only. Writes at most [maxTokens] ids into [jOut] and
/// returns the count, or a negative number with the reason in `nativeLastError()`.
///
/// `position` is the single counter and the prompt consumes it too: positions 0..promptLen-1 feed
/// the prompt through the same execute path, and the argmax produced at `position == promptLen - 1`
/// is the FIRST GENERATED TOKEN. Positions 0..maskLen-2 execute (0..198 for this asset - an exact
/// fit for the 199-deep self-KV); maskLen-1 is the termination threshold and never runs.
extern "C" JNIEXPORT jint JNICALL
Java_com_whispereverywhere_npu_QnnAsrNative_nativeDecodeSegment(
        JNIEnv *env, jobject /* this */, jintArray jPrompt, jintArray jSuppress,
        jintArray jBeginSuppress, jint maxTokens, jintArray jOut) {
    std::lock_guard<std::mutex> lock(g.mu);
    if (!g.initialised || !g.decBound) {
        failure("decode: session not initialised");
        return -1;
    }
    if (!jPrompt || !jOut) {
        failure("decode: prompt and out must not be null");
        return -1;
    }
    // The cross-KV is read IN PLACE. Without an encode this would transcribe the previous segment,
    // fluently, with nothing else wrong. Refused rather than trusted - the same standard the `out`
    // bounds check below applies to a caller who got the array size wrong.
    if (!g.encoded) {
        failure("decode: no encoded segment - call nativeEncode first. The decoder reads the "
                "encoder's 24 cross-KV buffers in place, so decoding without an encode transcribes "
                "whatever the previous segment left there (or silence, before the first encode) - "
                "fluently, and with no other symptom.");
        return -1;
    }

    const std::vector<int32_t> prompt = jintsToVector(env, jPrompt);
    const std::vector<int32_t> suppress = jintsToVector(env, jSuppress);
    const std::vector<int32_t> beginSuppress = jintsToVector(env, jBeginSuppress);

    // The last position that EXECUTES. 198 for this asset.
    const uint32_t lastPosition = g.maskLen - 2;
    if (prompt.empty() || prompt.size() > lastPosition) {
        failure("decode: prompt is " + std::to_string(prompt.size()) +
                " tokens; it must be 1.." + std::to_string(lastPosition) +
                " so at least one position is left to generate in");
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
    // BOUNDS-CHECKED, NOT TRUSTED. The caller is supposed to size `out` from
    // NpuDecodePolicy.maxTokensFor(prompt.size); this is the line that turns a caller who did not
    // into a readable refusal rather than a write past the end of a Java array.
    const jsize outLen = env->GetArrayLength(jOut);
    if (outLen < maxTokens) {
        failure("decode: out has room for " + std::to_string(static_cast<long long>(outLen)) +
                " ids but maxTokens is " + std::to_string(maxTokens) +
                " (size it with NpuDecodePolicy.maxTokensFor(prompt.size))");
        return -1;
    }

    // Both sets, on entry. Cross-KV is NOT touched: it belongs to the current segment's encode and
    // survives across as many decodes as the caller runs against it.
    zeroSelfKvLocked();

    uint16_t *logits = static_cast<uint16_t *>(g.dec.outBufs[g.decLogitsIdx].p);
    const uint32_t promptLen = static_cast<uint32_t>(prompt.size());
    std::vector<int32_t> out(static_cast<size_t>(maxTokens), 0);
    int32_t count = 0;
    int32_t next = prompt[0];
    bool hitEot = false;
    const auto t0 = Clock::now();

    // THE PROMPT ECHO. These are the ids as this function actually received them, not as the caller
    // believes it sent them, which is the whole point of echoing them.
    if (g.diag) {
        LOGDIAG("npu-debug: prompt ids=%s len=%u maxTokens=%d positions=0..%u vocab=%u mask=%u",
                diagIdList(prompt, 8).c_str(), promptLen, maxTokens, lastPosition, g.vocab,
                g.maskLen);
    }

    int32_t firstGenerated = -1;
    uint32_t lastPositionRun = 0;
    uint32_t stepsRun = 0;

    for (uint32_t position = 0; position <= lastPosition; ++position) {
        const int32_t tokenIn = (position < promptLen) ? prompt[position] : next;
        // The set bound as the decoder's self-KV INPUT for the execute about to run. Captured
        // before the step, because the swap after it is what this is meant to prove alternates.
        const int inSetForStep = g.selfInSet;
        err = decodeStepLocked(tokenIn, position);
        if (!err.empty()) {
            failure("decode: " + err);
            return -2;
        }
        lastPositionRun = position;
        ++stepsRun;

        // ITEM 5 (Q10a-D2) - DID THE DECODER GRAPH WRITE ITS NEW SELF-KV SLOT? One line, at
        // position 0 only, and it settles D1's H2 for the price of one buffer scan per segment.
        //
        // The step that just ran had set `inSetForStep` bound as its INPUT side, so its OUTPUT side
        // is the other set - that is `bindSelfKvLocked`'s whole contract, and reading the wrong one
        // here would report the ZEROED set and manufacture the failure it is looking for. The swap
        // that follows only re-points descriptors; it moves no bytes, so the set identified here
        // stays the one the graph wrote.
        //
        // At position 0 with a zeroed cache, `nonzero=0.000` means the graph produced no cache at
        // all and every later position attends nothing - which would be a decoder fault. Anything
        // else means the self-KV path works and the defect is upstream, in the encoder's input.
        if (g.diag && position == 0 && !g.selfOutIdx.empty()) {
            const int outSetForStep = 1 - inSetForStep;
            const AlignedBuf &b = g.selfKv[outSetForStep][0];
            const U8Stats ss = b.p ? scanU8Stats(static_cast<const uint8_t *>(b.p), b.n) : U8Stats{};
            LOGDIAG("npu-debug: selfkv pos=0 inSet=%d outSet=%d tensor=%s bytes=%u min=%u max=%u "
                    "mean=%.1f nonzero=%.3f",
                    inSetForStep, outSetForStep,
                    tensorName(g.dec.outputs[g.selfOutIdx[0]]), g.selfKvBytes,
                    ss.lo, ss.hi, ss.mean, ss.nonzero);
        }

        // The prompt walk plus one step past it. Bounded: for the shipped 4-token prompt that is
        // five lines per segment and then silence, whatever the segment's length.
        //
        // A HEALTHY WALK IS READABLE AT A GLANCE, which is why the raw argmax is here rather than
        // just the final token: after bare SOT the model must want a LANGUAGE token; after the
        // language token it must want <|transcribe|> (50359); after that <|notimestamps|> (50363);
        // and only at position promptLen-1 should the answer become a text token. Any step where
        // that chain breaks is the step where the prompt stopped taking.
        const bool trace = g.diag && position <= promptLen;
        LogitsHealth h;
        if (trace) h = scanLogitsRaw(logits, g.vocab);
        char inName[24], rawName[24], maskedName[24];

        if (position + 1 < promptLen) {
            // Still feeding the prompt. This step's argmax is discarded - the self-KV slot it just
            // wrote is the whole reason the step ran. BEGIN_SUPPRESS deliberately does NOT apply
            // here: it belongs to the first GENERATED step, which is position promptLen - 1, not
            // position 0.
            if (trace) {
                LOGDIAG("npu-debug: step pos=%u in=%s inSet=%d raw[min=%u max=%u argmax=%s] "
                        "masked=prefill-skipped",
                        position, diagToken(tokenIn, inName, sizeof(inName)), inSetForStep,
                        h.lo, h.hi, diagToken(h.argmax, rawName, sizeof(rawName)));
            }
            bindSelfKvLocked(1 - g.selfInSet);
            continue;
        }

        const int32_t tok = suppressThenArgmax(logits, g.vocab, suppress, beginSuppress,
                                               position == promptLen - 1);
        if (trace) {
            LOGDIAG("npu-debug: step pos=%u in=%s inSet=%d raw[min=%u max=%u argmax=%s] masked=%s "
                    "beginSuppress=%d",
                    position, diagToken(tokenIn, inName, sizeof(inName)), inSetForStep,
                    h.lo, h.hi, diagToken(h.argmax, rawName, sizeof(rawName)),
                    diagToken(tok, maskedName, sizeof(maskedName)),
                    position == promptLen - 1 ? 1 : 0);
        }
        if (tok < 0) {
            failure("decode: every logit is at the bottom rail at position " +
                    std::to_string(position) + "; the graph produced no token");
            return -3;
        }
        if (firstGenerated < 0) firstGenerated = tok;
        if (tok == kEotToken) {
            hitEot = true;
            break;
        }
        out[static_cast<size_t>(count)] = tok;
        ++count;
        if (count >= maxTokens) break;
        next = tok;
        bindSelfKvLocked(1 - g.selfInSet);
    }

    if (count > 0) env->SetIntArrayRegion(jOut, 0, count, reinterpret_cast<const jint *>(out.data()));
    const double ms = msSince(t0);
    const char *terminator =
            hitEot ? "eot" : (count >= maxTokens ? "count" : "cap");
    LOGI("decode: %d tokens in %.1f ms (%.2f ms/token), terminated by %s (vote: %s)",
         count, ms, count > 0 ? ms / count : 0.0,
         hitEot ? "EOT" : (count >= maxTokens ? "the token budget" : "the position cap"),
         g.voteNote.c_str());
    if (g.diag) {
        char firstName[24];
        LOGDIAG("npu-debug: result count=%d first=%s terminator=%s steps=%u posFirst=0 posLast=%u",
                count, diagToken(firstGenerated, firstName, sizeof(firstName)), terminator,
                stepsRun, lastPositionRun);
    }
    g.lastError.clear();
    return count;
}

/// ONE decode step at position 0 with `input_ids = SOT`, argmax RESTRICTED to the language block
/// 50259..50357, then both self-KV sets zeroed so the real loop starts clean.
///
/// The restriction is on this side of the boundary for the same reason the suppression mask is: an
/// unrestricted argmax handed to Kotlin would already have thrown away the information needed to
/// decide whether the winner was a language at all.
///
/// @return the winning `<|xx|>` token id, or < 0 with the reason in nativeLastError().
extern "C" JNIEXPORT jint JNICALL
Java_com_whispereverywhere_npu_QnnAsrNative_nativeDetectLanguage(
        JNIEnv *env, jobject /* this */) {
    (void) env;
    std::lock_guard<std::mutex> lock(g.mu);
    if (!g.initialised || !g.decBound) {
        failure("detect: session not initialised");
        return -1;
    }
    // Same in-place cross-KV read as nativeDecodeSegment, same refusal. Detecting a language off
    // the PREVIOUS segment's encoder state would then pick the prompt language for this one.
    if (!g.encoded) {
        failure("detect: no encoded segment - call nativeEncode first. This reads the encoder's "
                "cross-KV in place and would otherwise detect the previous segment's language.");
        return -1;
    }
    // NOT consumed: the flag stays set, so the caller may detect and then decode against the same
    // encode. That sequence - encode, detect, decode - is the tier's actual flow.

    zeroSelfKvLocked();
    const std::string err = decodeStepLocked(kSotToken, 0);
    if (!err.empty()) {
        failure("detect: " + err);
        return -2;
    }
    const uint16_t *logits = static_cast<const uint16_t *>(g.dec.outBufs[g.decLogitsIdx].p);
    const int32_t best = argmaxInRange(logits, static_cast<uint32_t>(kLangTokenFirst),
                                       static_cast<uint32_t>(kLangTokenLast) + 1);

    // THE DETECT ECHO, and the field that matters is `margin`.
    //
    // `en` is 50259 - the FIRST id in the band - and every argmax in this file resolves ties to the
    // first index. So "detected en" has two completely different meanings that the returned id
    // cannot distinguish: the model genuinely chose English, or every language logit was equal and
    // the scan fell out at its starting index. The runner-up and the margin separate them, and
    // there is no other reading in the system that can. The full-vocabulary rails come along on the
    // same line because a band that is flat inside a vocabulary that is also flat is a different
    // diagnosis from a band that is flat inside one that is not.
    if (g.diag) {
        int32_t runnerUp = -1;
        uint16_t runnerVal = 0;
        bool haveRunner = false;
        for (uint32_t i = static_cast<uint32_t>(kLangTokenFirst);
             i <= static_cast<uint32_t>(kLangTokenLast); ++i) {
            if (static_cast<int32_t>(i) == best) continue;
            if (!haveRunner || logits[i] > runnerVal) {
                runnerVal = logits[i];
                runnerUp = static_cast<int32_t>(i);
                haveRunner = true;
            }
        }
        const uint16_t bestVal = best >= 0 ? logits[best] : 0;
        const LogitsHealth h = scanLogitsRaw(logits, g.vocab);
        char rawName[24];
        // Language ids are specials, so best/runnerUp print verbatim by the same rule diagToken
        // applies - they are metadata about which language, never about what was said.
        LOGDIAG("npu-debug: detect band=[%d..%d] best=%d val=%u runnerUp=%d val=%u margin=%d "
                "raw[min=%u max=%u argmax=%s]",
                kLangTokenFirst, kLangTokenLast, best, bestVal, runnerUp, runnerVal,
                static_cast<int32_t>(bestVal) - static_cast<int32_t>(runnerVal),
                h.lo, h.hi, diagToken(h.argmax, rawName, sizeof(rawName)));
    }

    // The step above wrote a self-KV slot for position 0 into set 1. Leave nothing behind: the
    // real decode is entitled to assume it starts from an empty cache, and a detect pass that
    // primed position 0 with SOT would silently give the transcript a phantom first token.
    zeroSelfKvLocked();

    if (best < 0) {
        failure("detect: every language logit is at the bottom rail; no language was produced");
        return -3;
    }
    LOGI("detect: language token %d (offset %d in the language block)", best,
         best - kLangTokenFirst);
    g.lastError.clear();
    return best;
}

/// Turns the `npu-debug:` instrumentation on or off. Off until this says otherwise.
///
/// Kotlin owns the decision because Kotlin is where `BuildConfig.DEBUG` exists; native cannot see it
/// without a JNI round trip of its own, and a build-type ifdef here would be a second, separate
/// definition of "is this a debug build" that could disagree with the app's.
extern "C" JNIEXPORT void JNICALL
Java_com_whispereverywhere_npu_QnnAsrNative_nativeSetDiag(
        JNIEnv *env, jobject /* this */, jboolean enabled) {
    (void) env;
    std::lock_guard<std::mutex> lock(g.mu);
    g.diag = (enabled == JNI_TRUE);
    LOGI("npu-debug instrumentation %s", g.diag ? "ENABLED" : "disabled");
}

/// The last "stage: detail" recorded by any entry point, or "" if none. nativeDecodeSegment
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
