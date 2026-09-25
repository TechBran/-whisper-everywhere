// litert_stamp.h - reads the "LiteRtStamp" metadata out of an AOT-compiled LiteRT model, for
// liblitertasr.so's chip check (design §2.3, rule 4).
//
// Header-only and free of Android, JNI and LiteRT ON PURPOSE, like band_scan.h: the parser walks a
// flatbuffer in memory, so tools/mtk-apu/litert_stamp_check.cpp compiles THIS code on the MS-02 and
// runs it over the real compiled pair (and over an uncompiled file, which must be refused) - the only
// test it can have short of the tablet. litert_asr.cpp maps the file and calls parseLiteRtStamp.
//
// THE STAMP, as LiteRT 2.1.1's compiler writes it and as read off both of the pair's files: the SoC
// vendor, then the SoC model, each in a NUL-padded 125-byte field - 250 bytes (`MediaTek` at byte 0,
// `mt6989` at byte 125). Bytecode compiled for one chip does not restore on another, and that failure
// is a slow, opaque one inside the dispatch; the stamp says which chip a file is for before LiteRT
// ever opens it.
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <cstring>

#include <string>
#include <vector>

namespace litert_stamp {

constexpr const char *kStampKey = "LiteRtStamp";
constexpr size_t kStampFieldBytes = 125;
constexpr size_t kStampBytes = 2 * kStampFieldBytes;

/// A bounds-checked reader over a flatbuffer in memory. Every accessor answers false rather than
/// reading past the end: a truncated or foreign file must be a refusal, not a SIGBUS.
struct Flatbuffer {
    const uint8_t *p = nullptr;
    size_t n = 0;

    bool u16(size_t off, uint16_t *out) const {
        if (off > n || n - off < 2) return false;
        memcpy(out, p + off, 2);
        return true;
    }
    bool u32(size_t off, uint32_t *out) const {
        if (off > n || n - off < 4) return false;
        memcpy(out, p + off, 4);
        return true;
    }
    bool u64(size_t off, uint64_t *out) const {
        if (off > n || n - off < 8) return false;
        memcpy(out, p + off, 8);
        return true;
    }
    /// The absolute position of table field [field] in [*out], or 0 when the field is absent.
    bool field(size_t table, uint32_t field, size_t *out) const {
        uint32_t soff = 0;
        if (!u32(table, &soff)) return false;
        const int64_t vt = static_cast<int64_t>(table) - static_cast<int32_t>(soff);
        if (vt < 0) return false;
        uint16_t vtSize = 0;
        if (!u16(static_cast<size_t>(vt), &vtSize)) return false;
        const size_t slot = 4 + 2 * static_cast<size_t>(field);
        if (slot + 2 > vtSize) {
            *out = 0;
            return true;
        }
        uint16_t fo = 0;
        if (!u16(static_cast<size_t>(vt) + slot, &fo)) return false;
        *out = fo ? table + fo : 0;
        return true;
    }
    /// Follows the uoffset stored at [at].
    bool deref(size_t at, size_t *out) const {
        uint32_t rel = 0;
        if (!u32(at, &rel)) return false;
        if (rel >= n || at >= n - rel) return false;
        *out = at + rel;
        return true;
    }
};

/// Parses [p, p+n) - a whole TFLite file, or at least its flatbuffer head - into [vendor] and [soc].
/// "" on success, else the reason, naming [label] (the file) in it.
///
/// TFLite schema: Model.buffers = field 4, Model.metadata = field 6; Metadata { name 0, buffer 1 };
/// Buffer { data 0, offset 1, size 2 } - offset/size being the >2 GB form, relative to the file.
inline std::string parseLiteRtStamp(const uint8_t *p, size_t n, const std::string &label,
                                    std::string *vendor, std::string *soc) {
    const Flatbuffer fb{p, n};
    auto bad = [&](const char *what) {
        return "LiteRtStamp: " + label + " is not a readable TFLite flatbuffer (" + what + ")";
    };
    if (n < 8 || memcmp(p + 4, "TFL3", 4) != 0) return bad("no TFL3 identifier");
    size_t root = 0, metaField = 0, bufField = 0, metaVec = 0, bufVec = 0;
    uint32_t metaCount = 0, bufCount = 0;
    if (!fb.deref(0, &root) || !fb.field(root, 6, &metaField) || !fb.field(root, 4, &bufField)) {
        return bad("model table");
    }
    if (!metaField) return "LiteRtStamp: " + label + " carries no metadata at all - not an AOT-compiled model";
    if (!bufField || !fb.deref(metaField, &metaVec) || !fb.u32(metaVec, &metaCount) ||
        !fb.deref(bufField, &bufVec) || !fb.u32(bufVec, &bufCount)) {
        return bad("metadata / buffers vectors");
    }
    std::vector<uint8_t> stamp;
    bool found = false;
    for (uint32_t i = 0; i < metaCount && !found; ++i) {
        size_t md = 0, nameField = 0, nameStr = 0, bufIdxField = 0;
        uint32_t nameLen = 0, bufIdx = 0;
        if (!fb.deref(metaVec + 4 + 4 * static_cast<size_t>(i), &md) || !fb.field(md, 0, &nameField) ||
            !nameField || !fb.deref(nameField, &nameStr) || !fb.u32(nameStr, &nameLen) ||
            nameLen > n || nameStr + 4 > n - nameLen) {
            return bad("metadata entry");
        }
        if (nameLen != strlen(kStampKey) || memcmp(p + nameStr + 4, kStampKey, nameLen) != 0) continue;
        found = true;
        if (!fb.field(md, 1, &bufIdxField)) return bad("metadata buffer index");
        if (bufIdxField && !fb.u32(bufIdxField, &bufIdx)) return bad("metadata buffer index");
        if (bufIdx >= bufCount) return bad("metadata buffer index out of range");
        size_t buf = 0, dataField = 0, offField = 0, sizeField = 0;
        if (!fb.deref(bufVec + 4 + 4 * static_cast<size_t>(bufIdx), &buf) || !fb.field(buf, 0, &dataField) ||
            !fb.field(buf, 1, &offField) || !fb.field(buf, 2, &sizeField)) {
            return bad("buffer table");
        }
        uint64_t off = 0, size = 0;
        if (offField && !fb.u64(offField, &off)) return bad("buffer offset");
        if (sizeField && !fb.u64(sizeField, &size)) return bad("buffer size");
        if (dataField) {
            size_t vec = 0;
            uint32_t len = 0;
            if (!fb.deref(dataField, &vec) || !fb.u32(vec, &len) || len > n || vec + 4 > n - len) {
                return bad("buffer data");
            }
            stamp.assign(p + vec + 4, p + vec + 4 + len);
        } else if (off > 1 && size > 0 && size <= n && off <= n - size) {
            stamp.assign(p + off, p + off + size);
        } else {
            return bad("the stamp's buffer has neither data nor an in-file offset");
        }
    }
    if (!found) return "LiteRtStamp: " + label + " carries no LiteRtStamp - not an AOT-compiled model";
    if (stamp.size() != kStampBytes) {
        return "LiteRtStamp: " + label + " stamp is " + std::to_string(stamp.size()) + " B; LiteRT 2.1.1 writes " +
               std::to_string(kStampBytes) + " (two NUL-padded " + std::to_string(kStampFieldBytes) +
               "-byte fields)";
    }
    const char *v = reinterpret_cast<const char *>(stamp.data());
    const char *s = v + kStampFieldBytes;
    const size_t vl = strnlen(v, kStampFieldBytes);
    const size_t sl = strnlen(s, kStampFieldBytes);
    if (vl == kStampFieldBytes || sl == kStampFieldBytes) {
        return "LiteRtStamp: " + label + " has a field with no NUL terminator";
    }
    vendor->assign(v, vl);
    soc->assign(s, sl);
    return "";
}

}  // namespace litert_stamp
