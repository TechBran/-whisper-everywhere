// model_map_test.cpp - the host check for app/src/main/cpp/model_map.h, liblitertasr.so's model mapping.
//
// THE SAME HEADER THE .so COMPILES, run with the REAL open, fstat, mmap, madvise, mincore and munmap over
// real files in $TMPDIR (else /var/tmp, else /tmp) - and the header's two fallbacks, driven by scripted
// loaders standing in for LiteRT's. Run it with tools/model_map_check.py: on Linux with the host's own
// compiler, and on the owner's Windows machine through the NDK's clang (a static x86_64 executable built
// against bionic's headers, the product's) run inside WSL, i.e. on a real Linux kernel. Linux only:
// MADV_COLD, /proc/self/maps and /proc/self/smaps are Linux's.
//
// /var/tmp BEFORE /tmp, because /tmp is often a tmpfs (WSL's is) and a tmpfs file's pages are shmem -
// swap-backed, like the anonymous memory this header exists to avoid - while the models live on the
// tablet's disk. The first line names the filesystem the cases ran on.
//
// Exit code: 0 when every case holds, otherwise the number of the first case that failed; every case
// prints its own line either way.
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "../../main/cpp/model_map.h"

namespace {

int firstFailure = 0;
int failures = 0;

void check(int n, bool ok, const char *what, const std::string &detail = "") {
    printf("%s %2d - %s%s%s\n", ok ? "ok    " : "FAILED", n, what, detail.empty() ? "" : " | ", detail.c_str());
    if (!ok) {
        ++failures;
        if (firstFailure == 0) firstFailure = n;
    }
}

bool contains(const std::string &s, const std::string &needle) { return s.find(needle) != std::string::npos; }

size_t pageBytes() {
    const long p = sysconf(_SC_PAGESIZE);
    return p > 0 ? static_cast<size_t>(p) : 4096;
}

std::string tmpDir() {
    const char *t = getenv("TMPDIR");
    if (t && *t) return std::string(t);
    return access("/var/tmp", W_OK) == 0 ? std::string("/var/tmp") : std::string("/tmp");
}

/// The filesystem [dir] is on, by its statfs magic: the three that matter here, else the number.
std::string fsName(const std::string &dir) {
    struct statfs sf {};
    if (statfs(dir.c_str(), &sf) != 0) return "?";
    const unsigned long long magic = static_cast<unsigned long long>(sf.f_type);
    if (magic == 0xEF53ull) return "ext4";
    if (magic == 0xF2F52010ull) return "f2fs";
    if (magic == 0x01021994ull) return "tmpfs (shmem pages: swap-backed, not the tablet's case)";
    char b[32];
    snprintf(b, sizeof(b), "magic 0x%llx", magic);
    return b;
}

uint8_t pattern(size_t i) { return static_cast<uint8_t>((i * 31u + 7u) & 0xFFu); }

/// A fresh file of [n] bytes of pattern() in tmpDir(); "" when it could not be made.
std::string makeFile(size_t n) {
    std::string path = tmpDir() + "/we_model_map_XXXXXX";
    std::vector<char> tmpl(path.begin(), path.end());
    tmpl.push_back('\0');
    const int fd = mkstemp(tmpl.data());
    if (fd < 0) return "";
    std::vector<uint8_t> bytes(n);
    for (size_t i = 0; i < n; ++i) bytes[i] = pattern(i);
    size_t done = 0;
    while (done < n) {
        const ssize_t w = write(fd, bytes.data() + done, n - done);
        if (w <= 0) {
            close(fd);
            unlink(tmpl.data());
            return "";
        }
        done += static_cast<size_t>(w);
    }
    close(fd);
    return std::string(tmpl.data());
}

/// The lowest free descriptor: equal before and after a call means the call left no fd open.
int lowestFreeFd() {
    const int fd = open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) close(fd);
    return fd;
}

/// The permission field /proc/self/maps shows for the mapping that starts at [addr] ("" when none does).
std::string permsAt(const void *addr) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return "";
    char line[1024];
    std::string perms;
    while (fgets(line, sizeof(line), f)) {
        char *end = nullptr;
        const unsigned long long start = strtoull(line, &end, 16);
        if (start != reinterpret_cast<uintptr_t>(addr) || !end || *end != '-') continue;
        char *sp = strchr(end, ' ');
        if (sp && strlen(sp) > 5) perms.assign(sp + 1, 4);
        break;
    }
    fclose(f);
    return perms;
}

/// The Rss (kB) /proc/self/smaps shows for the mapping that starts at [addr], or -1. Unlike mincore - which
/// for a file the caller owns reports the PAGE CACHE, and so cannot tell COLD from DONTNEED - this is what
/// the process itself still has mapped: COLD leaves it, DONTNEED and PAGEOUT take it away.
long rssKbAt(const void *addr) {
    FILE *f = fopen("/proc/self/smaps", "r");
    if (!f) return -1;
    char line[1024];
    bool inside = false;
    long rss = -1;
    while (fgets(line, sizeof(line), f)) {
        char *end = nullptr;
        const unsigned long long start = strtoull(line, &end, 16);
        if (end && *end == '-' && end != line) {   // a mapping's header line
            if (inside) break;
            inside = start == reinterpret_cast<uintptr_t>(addr);
            continue;
        }
        if (inside && strncmp(line, "Rss:", 4) == 0) {
            rss = strtol(line + 4, nullptr, 10);
            break;
        }
    }
    fclose(f);
    return rss;
}

/// True when no page of [addr, addr + size) is mapped any more (mincore's ENOMEM).
bool gone(const void *addr, size_t size) {
    std::vector<unsigned char> v((size + pageBytes() - 1) / pageBytes());
    return mincore(const_cast<void *>(addr), size, v.data()) != 0 && errno == ENOMEM;
}

bool holdsPattern(const model_map::Mapping &m, size_t n) {
    if (m.size != n) return false;
    const uint8_t *p = static_cast<const uint8_t *>(m.addr);
    for (size_t i = 0; i < n; ++i) {
        if (p[i] != pattern(i)) return false;
    }
    return true;
}

}  // namespace

int main() {
    using model_map::Mapping;
    using model_map::Via;
    const size_t page = pageBytes();
    const size_t n = 256 * page + 100;   // 257 pages, the last one partial
    const std::string path = makeFile(n);
    if (path.empty()) {
        printf("FAILED  0 - could not create a temp file in %s (%s)\n", tmpDir().c_str(), strerror(errno));
        return 99;
    }
    printf("model_map_test: %s on %s, %zu B, page %zu B\n", path.c_str(), fsName(tmpDir()).c_str(), n, page);

    // ---- the mapping itself, over a real file
    const int fdBefore = lowestFreeFd();
    Mapping m;
    const std::string err = model_map::mapReadOnly(path, &m);
    check(1, err.empty() && m.addr && m.size == n && reinterpret_cast<uintptr_t>(m.addr) % page == 0 &&
                 holdsPattern(m, n),
          "mapReadOnly maps the whole file, page-aligned, byte for byte", err);
    check(2, lowestFreeFd() == fdBefore, "and leaves no fd open (the mapping holds the file)");
    const std::string perms = permsAt(m.addr);
    check(3, perms == "r--p", "the mapping is read-only and private: /proc/self/maps shows r--p", "got '" + perms + "'");
    int rc = model_map::adviseCompilePass(m);
    check(4, rc == 0, "the compile pass's advice (SEQUENTIAL, WILLNEED) is accepted", model_map::errnoText(rc));
    size_t res = 0;
    rc = model_map::residentBytes(m, &res);
    const long rssBefore = rssKbAt(m.addr);   // every page was touched by holdsPattern: all of them mapped
    const long rssWhole = static_cast<long>(((n + page - 1) / page) * page / 1024);
    check(5, rc == 0 && res == n && rssBefore == rssWhole,
          "residentBytes reads the touched file as exactly its size, and smaps maps every page",
          std::to_string(res) + " B of " + std::to_string(n) + ", Rss " + std::to_string(rssBefore) + " kB");
    rc = model_map::adviseRunPhase(m);
    check(6, rc == 0 || rc == EINVAL,
          "the run phase's advice (NORMAL, COLD) is accepted - EINVAL only on a kernel older than 5.4",
          rc == 0 ? "accepted" : model_map::errnoText(rc));
    res = 0;
    const int rc7 = model_map::residentBytes(m, &res);
    const long rssAfter = rssKbAt(m.addr);
    check(7, rc7 == 0 && res == n && rssAfter == rssBefore && holdsPattern(m, n),
          "COLD demotes and drops nothing: every page still mapped (smaps Rss) and resident, the file's bytes",
          "Rss " + std::to_string(rssBefore) + " -> " + std::to_string(rssAfter) + " kB, " + std::to_string(res) +
              " B resident");
    check(8, unlink(path.c_str()) == 0 && holdsPattern(m, n),
          "the mapping outlives an unlink of its path (the inode stays until the unmap)");
    const void *oldAddr = m.addr;
    const size_t oldSize = m.size;
    rc = model_map::unmap(&m);
    check(9, rc == 0 && model_map::isEmpty(m) && m.size == 0 && gone(oldAddr, oldSize),
          "unmap releases the range and empties the mapping");
    check(10, model_map::unmap(&m) == 0 && model_map::isEmpty(m), "a second unmap is a no-op");
    {
        Mapping none;
        size_t r = 1;
        check(11, model_map::adviseCompilePass(none) == 0 && model_map::adviseRunPhase(none) == 0 &&
                      model_map::residentBytes(none, &r) == 0 && r == 0 && model_map::unmap(&none) == 0,
              "an empty mapping: both advices, residentBytes and unmap are no-ops answering 0");
    }

    // ---- the refusals: each one leaves nothing mapped and nothing open
    {
        Mapping x;
        const int before = lowestFreeFd();
        const std::string e = model_map::mapReadOnly(tmpDir() + "/we_model_map_no_such_file", &x);
        check(12, contains(e, "open ") && contains(e, strerror(ENOENT)) && model_map::isEmpty(x) &&
                      lowestFreeFd() == before,
              "a missing file is refused at open, with its errno", e);
    }
    {
        Mapping x;
        const int before = lowestFreeFd();
        const std::string e = model_map::mapReadOnly(tmpDir(), &x);
        check(13, contains(e, "is not a regular file") && model_map::isEmpty(x) && lowestFreeFd() == before,
              "a directory is refused, and its fd closed", e);
    }
    {
        const std::string emptyPath = makeFile(0);
        Mapping x;
        const int before = lowestFreeFd();
        const std::string e = model_map::mapReadOnly(emptyPath, &x);
        check(14, !emptyPath.empty() && contains(e, "is empty") && model_map::isEmpty(x) && lowestFreeFd() == before,
              "an empty file is refused, and its fd closed", e);
        if (!emptyPath.empty()) unlink(emptyPath.c_str());
    }
    const std::string path2 = makeFile(n);
    {
        Mapping held;
        const std::string e1 = model_map::mapReadOnly(path2, &held);
        const void *heldAddr = held.addr;
        const std::string e2 = model_map::mapReadOnly(path2, &held);
        check(15, e1.empty() && contains(e2, "already holds a mapping") && held.addr == heldAddr && holdsPattern(held, n),
              "a live mapping is never overwritten: the second map is refused and the first is untouched", e2);
        model_map::unmap(&held);
    }

    // ---- THE LOAD AND ITS FALLBACK, with scripted loaders in LiteRT's place
    {
        Mapping map;
        Via via = Via::None;
        std::string why = "stale";
        int advice = -1;
        int buffers = 0, files = 0;
        bool sawBytes = false;
        const std::string e = model_map::loadPreferMapped(
                path2, &map,
                [&](const void *addr, size_t size) {
                    ++buffers;
                    Mapping view{const_cast<void *>(addr), size};
                    sawBytes = holdsPattern(view, n);
                    return std::string();
                },
                [&] { ++files; return std::string(); }, &via, &why, &advice);
        check(16, e.empty() && via == Via::Mapped && why.empty() && advice == 0 && buffers == 1 && files == 0 &&
                      sawBytes && !model_map::isEmpty(map) && holdsPattern(map, n),
              "loadPreferMapped: the mapped route hands the loader the whole file and KEEPS the mapping", e);
        model_map::unmap(&map);
    }
    {
        Mapping map;
        Via via = Via::None;
        std::string why;
        int advice = 0;
        const void *seen = nullptr;
        size_t seenSize = 0;
        bool releasedFirst = false;
        const std::string e = model_map::loadPreferMapped(
                path2, &map,
                [&](const void *addr, size_t size) {
                    seen = addr;
                    seenSize = size;
                    return std::string("LiteRtCreateModelFromBuffer kLiteRtStatusErrorInvalidFlatbuffer (scripted)");
                },
                [&] {
                    releasedFirst = model_map::isEmpty(map) && gone(seen, seenSize);
                    return std::string();
                },
                &via, &why, &advice);
        check(17, e.empty() && via == Via::File && contains(why, "InvalidFlatbuffer") && releasedFirst &&
                      model_map::isEmpty(map),
              "a refused mapping is released BEFORE the file loader runs, and the reason is kept", why);
    }
    {
        Mapping map;
        Via via = Via::None;
        std::string why;
        int advice = 0;
        int buffers = 0, files = 0;
        const std::string e = model_map::loadPreferMapped(
                tmpDir() + "/we_model_map_no_such_file", &map,
                [&](const void *, size_t) { ++buffers; return std::string(); },
                [&] { ++files; return std::string(); }, &via, &why, &advice);
        check(18, e.empty() && via == Via::File && buffers == 0 && files == 1 && contains(why, "open ") &&
                      model_map::isEmpty(map),
              "a file that cannot be mapped goes straight to the file loader", why);
    }
    {
        Mapping map;
        Via via = Via::Mapped;
        std::string why;
        int advice = 0;
        const std::string e = model_map::loadPreferMapped(
                path2, &map, [&](const void *, size_t) { return std::string("refused (scripted)"); },
                [&] { return std::string("encoder LiteRtCreateModelFromFile(x): kLiteRtStatusErrorFileIO (scripted)"); },
                &via, &why, &advice);
        check(19, contains(e, "LiteRtCreateModelFromFile") && contains(e, "(the mapped load before it: refused") &&
                      via == Via::None && model_map::isEmpty(map),
              "both loaders refused: the file loader's reason, the mapped one appended, nothing mapped", e);
    }
    {
        Mapping held;
        const std::string e1 = model_map::mapReadOnly(path2, &held);
        const void *heldAddr = held.addr;
        Via via = Via::Mapped;
        std::string why;
        int advice = 0;
        int calls = 0;
        const std::string e = model_map::loadPreferMapped(
                path2, &held, [&](const void *, size_t) { ++calls; return std::string(); },
                [&] { ++calls; return std::string(); }, &via, &why, &advice);
        check(20, e1.empty() && contains(e, "already holds a mapping") && calls == 0 && via == Via::None &&
                      held.addr == heldAddr && holdsPattern(held, n),
              "a slot that already holds a mapping is refused before anything loads", e);
        model_map::unmap(&held);
    }
    unlink(path2.c_str());

    // ---- THE RESTORE AND ITS FALLBACK
    {
        int compiles = 0, reopens = 0;
        std::string why = "stale";
        const std::string e = model_map::compilePreferMapped(
                Via::Mapped, [&] { ++compiles; return std::string(); }, [&] { ++reopens; return std::string(); },
                &why);
        check(21, e.empty() && compiles == 1 && reopens == 0 && why.empty(),
              "compilePreferMapped: a mapped model that restores is never re-opened");
    }
    {
        std::vector<std::string> order;
        std::string why;
        const std::string e = model_map::compilePreferMapped(
                Via::Mapped,
                [&] {
                    order.push_back("compile");
                    return std::string(order.size() == 1 ? "LiteRtCreateCompiledModel refused (scripted)" : "");
                },
                [&] { order.push_back("reopen"); return std::string(); }, &why);
        const bool shape = order.size() == 3 && order[0] == "compile" && order[1] == "reopen" && order[2] == "compile";
        check(22, e.empty() && shape && contains(why, "refused (scripted)"),
              "a refused mapped model: re-opened through the file loader, restored once more, the refusal kept", why);
    }
    {
        int compiles = 0, reopens = 0;
        std::string why;
        const std::string e = model_map::compilePreferMapped(
                Via::File, [&] { ++compiles; return std::string("the dispatch refused (scripted)"); },
                [&] { ++reopens; return std::string(); }, &why);
        check(23, e == "the dispatch refused (scripted)" && compiles == 1 && reopens == 0 && why.empty(),
              "a FILE model's refusal is final: no re-open, the reason unchanged", e);
    }
    {
        int compiles = 0, reopens = 0;
        std::string why;
        const std::string e = model_map::compilePreferMapped(
                Via::Mapped, [&] { ++compiles; return std::string("mapped refused (scripted)"); },
                [&] { ++reopens; return std::string("decoder LiteRtCreateModelFromFile: failed (scripted)"); }, &why);
        check(24, contains(e, "LiteRtCreateModelFromFile: failed") &&
                      contains(e, "(after the mapped model was refused: mapped refused") && compiles == 1 &&
                      reopens == 1,
              "the re-open fails: its reason, with the mapped refusal appended, and no second restore", e);
    }
    {
        int compiles = 0, reopens = 0;
        std::string why;
        const std::string e = model_map::compilePreferMapped(
                Via::Mapped,
                [&] {
                    ++compiles;
                    return std::string(compiles == 1 ? "first refusal (scripted)" : "second refusal (scripted)");
                },
                [&] { ++reopens; return std::string(); }, &why);
        check(25, contains(e, "second refusal") && contains(e, "(after the mapped model was refused: first refusal") &&
                      compiles == 2 && reopens == 1,
              "the file model is refused too: the last reason, the mapped one appended", e);
    }
    check(26,
          strcmp(model_map::viaName(Via::Mapped), "mmap") == 0 && strcmp(model_map::viaName(Via::File), "file") == 0 &&
              strcmp(model_map::viaName(Via::None), "none") == 0,
          "viaName prints mmap / file / none");

    if (failures == 0) {
        printf("model_map_test: every case holds\n");
        return 0;
    }
    printf("model_map_test: %d case(s) FAILED; the first is %d\n", failures, firstFailure);
    return firstFailure;
}
