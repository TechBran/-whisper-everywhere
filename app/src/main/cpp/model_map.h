// model_map.h - maps a model file read-only for liblitertasr.so's LiteRtCreateModelFromBuffer (4.16.1: the
// Tab S10+ ship sheet's F2, 2026-09-25).
//
// Header-only and free of Android, JNI and LiteRT ON PURPOSE, like litert_stamp.h and band_scan.h:
// app/src/test/cpp/model_map_test.cpp compiles THIS code for the host and runs it over real files with the
// real open, mmap, madvise, mincore and munmap (tools/model_map_check.py) - the only test it can have short
// of the tablet. litert_asr.cpp supplies the LiteRT half: the two loaders and the restore, as the callables
// the two fallbacks below take.
//
// WHY A MAPPING. LiteRtCreateModelFromFile (LiteRT v2.1.1, litert/core/model/model_load.cc) maps the file
// and then COPIES every DISPATCH_OP's bytecode into an owned heap buffer that only model serialization ever
// reads - the dispatch restores from the model's own bytes, at the op's bytecode offset. That copy is
// anonymous memory, cold from the restore on, and all the kernel can do with it is swap it: on the MediaTek
// pair 1,302,604,120 + 317,005,992 B. LiteRtCreateModelFromBuffer makes no copy ("The caller must ensure
// that the buffer remains valid for the lifetime of the model", litert/c/litert_model.h), so the model's
// pages stay FILE-BACKED - clean, and dropped under pressure without a swap write. Hence the one rule the
// caller must keep: the mapping outlives the model, and the model the compiled model.
//
// THE ADVICE, in two phases. The compile pass reads the file once, front to back (the verifier, the
// unpack, the dispatch handing the bytecode to Neuron): SEQUENTIAL, whose readahead carries that read, then
// WILLNEED, which only starts the first window early - the kernel caps it at max(io_pages, ra_pages) of the
// range (measured: 8,192 KB of a 256 MB file under read_ahead_kb 8192, WSL2's 6.18), so it never pulls a
// whole 1.3 GB file in ahead of its reader. After the restore the run
// phase reads almost none of it - the encoder nothing, the decoder a row of its CPU-side embedding tables
// per step: NORMAL (the pass is over, and SEQUENTIAL's readahead and its "read once" reclaim bias end with
// it), then COLD, which deactivates the pages so reclaim takes them first. Never DONTNEED or PAGEOUT: the
// compiled model does read these bytes again - the decoder's CPU ops every step, and the dispatch's Neuron
// model keeps a pointer into the bytecode (NeuronModel_setOperandValue) - so the pages are demoted, not
// dropped: reclaim takes them first when there is pressure, and nothing is thrown away while there is none.
#pragma once

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstring>
#include <string>
#include <vector>

namespace model_map {

/// One whole model file, mapped PROT_READ | MAP_PRIVATE. Empty (addr == nullptr, size == 0) when nothing is
/// mapped. It holds no fd: a mapping keeps its own reference to the file, and survives an unlink of the path.
struct Mapping {
    void *addr = nullptr;
    size_t size = 0;
};

inline bool isEmpty(const Mapping &m) { return m.addr == nullptr; }

inline std::string errnoText(int e) { return std::string(strerror(e)) + " (errno " + std::to_string(e) + ")"; }

/// Maps the whole of [path] read-only into [*out]: open, fstat, mmap(PROT_READ, MAP_PRIVATE), close. "" on
/// success. Otherwise the reason, and [*out] is as it was - nothing open, nothing mapped. An [*out] that
/// already holds a mapping is refused, never overwritten: the old mapping would leak, and a model made from
/// it would be left pointing at addresses nothing tracks.
///
/// Read-only is not a new constraint on LiteRT: its own file loader maps the same file PROT_READ (TFLite's
/// MMAPAllocation without allow_modifications, which is LiteRtCreateModelFromFile's case), so nothing that
/// reads a model today writes into its bytes.
inline std::string mapReadOnly(const std::string &path, Mapping *out) {
    if (!isEmpty(*out)) return "map " + path + ": this slot already holds a mapping";
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return "open " + path + ": " + errnoText(errno);
    struct stat sb {};
    if (fstat(fd, &sb) != 0) {
        const int e = errno;
        close(fd);
        return "fstat " + path + ": " + errnoText(e);
    }
    if (!S_ISREG(sb.st_mode)) {
        close(fd);
        return path + " is not a regular file";
    }
    if (sb.st_size <= 0) {
        close(fd);
        return path + " is empty";
    }
    const size_t size = static_cast<size_t>(sb.st_size);
    if (static_cast<uint64_t>(size) != static_cast<uint64_t>(sb.st_size)) {
        close(fd);
        return path + " is too large to map here (" + std::to_string(static_cast<long long>(sb.st_size)) + " B)";
    }
    void *m = mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    const int e = errno;
    close(fd);   // the mapping keeps the file; no fd outlives this call, on any path
    if (m == MAP_FAILED) return "mmap " + path + " (" + std::to_string(size) + " B): " + errnoText(e);
    out->addr = m;
    out->size = size;
    return "";
}

/// THE COMPILE PASS'S ADVICE: SEQUENTIAL (aggressive readahead; a page read once is not held as referenced),
/// then WILLNEED (the first readahead window now - one window, not the file: see the top of this file).
/// Advice only - a refusal changes speed, never correctness - so it answers the first errno, or 0. An empty
/// mapping is 0.
inline int adviseCompilePass(const Mapping &m) {
    if (isEmpty(m)) return 0;
    int err = 0;
    if (madvise(m.addr, m.size, MADV_SEQUENTIAL) != 0) err = errno;
    if (madvise(m.addr, m.size, MADV_WILLNEED) != 0 && err == 0) err = errno;
    return err;
}

/// THE RUN PHASE'S ADVICE, after the restore: NORMAL, then COLD - the pages are deactivated, so reclaim takes
/// them before anything anonymous, and they leave without a swap write (never DONTNEED: see the top of this
/// file). MADV_COLD is Linux 5.4; an older kernel answers EINVAL and the pages simply age as they would have.
/// The first errno, or 0. An empty mapping is 0.
inline int adviseRunPhase(const Mapping &m) {
    if (isEmpty(m)) return 0;
    int err = 0;
    if (madvise(m.addr, m.size, MADV_NORMAL) != 0) err = errno;
    if (madvise(m.addr, m.size, MADV_COLD) != 0 && err == 0) err = errno;
    return err;
}

/// The bytes of [m] resident right now, by mincore: 0 with [*out] filled, else the errno. Whole pages,
/// capped at the mapping's size, so a fully resident file reads as exactly its size. An empty mapping is 0 B.
inline int residentBytes(const Mapping &m, size_t *out) {
    *out = 0;
    if (isEmpty(m)) return 0;
    const long pageSize = sysconf(_SC_PAGESIZE);
    const size_t page = pageSize > 0 ? static_cast<size_t>(pageSize) : 4096;
    std::vector<unsigned char> resident((m.size + page - 1) / page);
    if (mincore(m.addr, m.size, resident.data()) != 0) return errno;
    size_t pages = 0;
    for (unsigned char r : resident) pages += (r & 1u);
    *out = pages * page < m.size ? pages * page : m.size;
    return 0;
}

/// Unmaps [*m] and empties it; an empty mapping is a no-op. 0, or munmap's errno - and the mapping is emptied
/// either way: after a failed munmap nothing may use those addresses again.
inline int unmap(Mapping *m) {
    if (isEmpty(*m)) return 0;
    const int rc = munmap(m->addr, m->size) == 0 ? 0 : errno;
    *m = Mapping{};
    return rc;
}

/// Which loader opened a model: the mapping (LiteRtCreateModelFromBuffer) or LiteRT's own file loader.
enum class Via { None, Mapped, File };

/// The word the `opened in N ms via ...` line prints.
inline const char *viaName(Via v) { return v == Via::Mapped ? "mmap" : v == Via::File ? "file" : "none"; }

/// THE LOAD AND ITS FALLBACK. [path] is mapped (mapReadOnly, then adviseCompilePass) and the mapping is handed
/// to [fromBuffer](addr, size); if the mapping cannot be made, or [fromBuffer] refuses it, the mapping is
/// released FIRST and [fromFile]() loads the model instead. Each callable answers "" or its reason, and a
/// refusing [fromBuffer] must leave no model behind.
///
/// Returns "" when a loader succeeded - [*via] names it, [*why] says why the mapping was not used ("" when it
/// was), [*adviceErrno] is adviseCompilePass's answer - else [fromFile]'s reason with the mapped route's
/// appended. On EVERY return [*map] is the mapping the model was made from, or empty: a refused mapping never
/// outlives this call. A [*map] that is not empty on entry is refused before anything is loaded.
template <typename FromBuffer, typename FromFile>
std::string loadPreferMapped(const std::string &path, Mapping *map, FromBuffer &&fromBuffer, FromFile &&fromFile,
                             Via *via, std::string *why, int *adviceErrno) {
    *via = Via::None;
    *adviceErrno = 0;
    why->clear();
    if (!isEmpty(*map)) return "load " + path + ": this slot already holds a mapping";
    *why = mapReadOnly(path, map);
    if (why->empty()) {
        *adviceErrno = adviseCompilePass(*map);
        *why = fromBuffer(static_cast<const void *>(map->addr), map->size);
        if (why->empty()) {
            *via = Via::Mapped;
            return "";
        }
        unmap(map);
    }
    const std::string err = fromFile();
    if (!err.empty()) return err + " (the mapped load before it: " + *why + ")";
    *via = Via::File;
    return "";
}

/// THE RESTORE AND ITS FALLBACK. [compile]() runs on the model as loaded; if a MAPPED model is refused,
/// [reopenFromFile]() rebuilds the slot through the file loader - releasing its model, then its mapping - and
/// [compile]() runs once more. A file-loaded model's refusal is final: there is nothing left to fall back to.
/// Returns "" on success, with [*why] the mapped model's refusal when the fallback was taken ("" otherwise);
/// else the last reason, with the mapped model's refusal appended when there was one.
template <typename Compile, typename ReopenFromFile>
std::string compilePreferMapped(Via via, Compile &&compile, ReopenFromFile &&reopenFromFile, std::string *why) {
    why->clear();
    std::string err = compile();
    if (err.empty() || via != Via::Mapped) return err;
    *why = err;
    err = reopenFromFile();
    if (err.empty()) err = compile();
    if (err.empty()) return "";
    return err + " (after the mapped model was refused: " + *why + ")";
}

}  // namespace model_map
