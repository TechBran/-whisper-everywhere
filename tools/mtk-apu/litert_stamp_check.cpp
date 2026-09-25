// litert_stamp_check.cpp - the host check for app/src/main/cpp/litert_stamp.h, liblitertasr.so's chip check.
//
// The SAME header the .so compiles, run over real files on the MS-02 (Linux; the compiled pair lives there):
//
//   g++ -std=c++17 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined
//       -I app/src/main/cpp tools/mtk-apu/litert_stamp_check.cpp -o /tmp/litert_stamp_check
//   P=~/mtk-whisper/out/pair
//   /tmp/litert_stamp_check
//       $P/aot_mt6989/turbo_encoder_qcio_f32_MediaTek_MT6989_apply_plugin.tflite=MediaTek/mt6989
//       $P/aot_mt6989/turbo_decoder_mtk_f32_MediaTek_MT6989_apply_plugin.tflite=MediaTek/mt6989
//       $P/aot_mt6991/turbo_decoder_mtk_f32_MediaTek_MT6991_apply_plugin.tflite=MediaTek/mt6991
//       $P/turbo_decoder_mtk_f32.tflite=refuse
//   (one command line each; the arguments are shown one per line)
//
// Each argument is `path=expectation`: `Vendor/soc` must parse to exactly that, `refuse` must be refused (an
// uncompiled model carries no LiteRtStamp). Then, for every file that parsed, two robustness passes: truncated
// lengths (every one up to 2 KB, a doubling series to the whole file, every one across the stamp's bytes), and
// 20,000 single-byte corruptions near the head and near the stamp. Each must refuse or parse, never read out of
// bounds; under -fsanitize=address,undefined an out-of-bounds read is a crash, so "no crash" is the result.
//
// Exit code: 0 when every expectation holds; 1 otherwise.
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <random>
#include <string>
#include <vector>

#include "litert_stamp.h"

int main(int argc, char **argv) {
    int failures = 0;
    for (int a = 1; a < argc; ++a) {
        const std::string arg = argv[a];
        const size_t eq = arg.rfind('=');
        if (eq == std::string::npos) {
            std::printf("usage: path=Vendor/soc | path=refuse\n");
            return 1;
        }
        const std::string path = arg.substr(0, eq);
        const std::string expect = arg.substr(eq + 1);
        const int fd = open(path.c_str(), O_RDONLY);
        struct stat sb {};
        if (fd < 0 || fstat(fd, &sb) != 0) {
            std::printf("FAIL %s: cannot open\n", path.c_str());
            ++failures;
            continue;
        }
        // PROT_WRITE on a MAP_PRIVATE mapping is copy-on-write: the corruption pass below edits pages in
        // memory only, and the file on disk is never touched.
        void *m = mmap(nullptr, static_cast<size_t>(sb.st_size), PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
        close(fd);
        if (m == MAP_FAILED) {
            std::printf("FAIL %s: cannot map\n", path.c_str());
            ++failures;
            continue;
        }
        auto *w = static_cast<uint8_t *>(m);
        const uint8_t *p = w;
        const size_t n = static_cast<size_t>(sb.st_size);
        std::string vendor, soc;
        const std::string err = litert_stamp::parseLiteRtStamp(p, n, path, &vendor, &soc);
        const bool ok = expect == "refuse" ? !err.empty() : (err.empty() && vendor + "/" + soc == expect);
        std::printf("%s %s (%zu B): %s\n", ok ? "ok  " : "FAIL", path.c_str(), n,
                    err.empty() ? (vendor + " / " + soc).c_str() : err.c_str());
        failures += ok ? 0 : 1;

        if (err.empty()) {
            // Truncations: every length up to 2 KB, then a doubling series to the whole file, plus
            // every length across the stamp's own bytes - each must refuse or parse, never fault.
            const uint8_t needle[12] = {'M', 'e', 'd', 'i', 'a', 'T', 'e', 'k', 0, 0, 0, 0};
            const void *hit = memmem(p, n, needle, sizeof(needle));
            const size_t stampAt = hit ? static_cast<size_t>(static_cast<const uint8_t *>(hit) - p) : 0;
            std::vector<size_t> lengths;
            for (size_t len = 0; len < 2048 && len < n; ++len) lengths.push_back(len);
            for (size_t len = 2048; len < n; len *= 2) lengths.push_back(len);
            for (size_t len = stampAt; hit && len < stampAt + 300 && len < n; ++len) lengths.push_back(len);
            size_t refusedTruncations = 0;
            for (size_t len : lengths) {
                std::string v2, s2;
                if (!litert_stamp::parseLiteRtStamp(p, len, "trunc", &v2, &s2).empty()) ++refusedTruncations;
            }
            // Corruptions, in place on the copy-on-write mapping and restored after each parse: random
            // single bytes in the first 2 KB (the root table, the vtables, the vector offsets) and
            // within 4 KB either side of the stamp (the metadata and buffer tables that lead to it).
            std::mt19937 rng(7);
            size_t refusedCorrupt = 0, parsedCorrupt = 0, sameAnswer = 0;
            for (int k = 0; k < 20000; ++k) {
                size_t at = rng() % 2048;
                if (hit && (k & 1)) at = stampAt - (stampAt < 4096 ? stampAt : 4096) + rng() % 8192;
                if (at >= n) continue;
                const uint8_t keep = w[at];
                w[at] = static_cast<uint8_t>(rng());
                std::string v2, s2;
                if (litert_stamp::parseLiteRtStamp(p, n, "corrupt", &v2, &s2).empty()) {
                    ++parsedCorrupt;
                    if (v2 == vendor && s2 == soc) ++sameAnswer;
                } else {
                    ++refusedCorrupt;
                }
                w[at] = keep;
            }
            std::printf("     stamp bytes at %zu; %zu truncations tried, %zu refused; 20000 corruptions: %zu refused, "
                        "%zu parsed (%zu to the same stamp); 0 faults\n", stampAt, lengths.size(),
                        refusedTruncations, refusedCorrupt, parsedCorrupt, sameAnswer);
        }
        munmap(m, n);
    }
    std::printf("%s\n", failures == 0 ? "RESULT: PASS" : "RESULT: FAIL");
    return failures == 0 ? 0 : 1;
}
