// The host-side check for band_scan.h - the one piece of libqnnasr.so's detect pass with no device
// in it. Run it with tools/band_scan_check.py; there is no gradle task and no JVM test for C++.
//
// FREESTANDING BY DESIGN: no CRT, no <cstdio>, no allocation. The exit code IS the report - 0 when
// every case holds, otherwise the NUMBER OF THE FIRST CASE THAT FAILED, so a red run names its
// case without printing anything. That is what lets the script build it on a machine with only
// the NDK's clang (which ships no host C library): the same header, the same compiler family, the
// same -Wall -Wextra -Werror, on the host.
#include <stdint.h>

#include "../../main/cpp/band_scan.h"

namespace {

struct Expect {
    int32_t best;
    int32_t second;
    uint16_t bestVal;
    uint16_t secondVal;
    uint32_t ties;
};

bool holds(const uint16_t *a, uint32_t lo, uint32_t hi, uint16_t floor, Expect e) {
    const BandTop2 r = scanBandTop2(a, lo, hi, floor);
    return r.best == e.best && r.second == e.second && r.bestVal == e.bestVal &&
           r.secondVal == e.secondVal && r.ties == e.ties;
}

// Every band sits inside a larger array with live values on BOTH sides of [lo, hi), so a scan that
// strays past either bound is caught by the case itself and not by luck.
const uint16_t kClearWin[] = {900, 3, 12, 7, 1, 900};
const uint16_t kTieAtTop[] = {900, 4, 9, 9, 2, 900};
const uint16_t kThreeWayTie[] = {900, 9, 9, 9, 900};
const uint16_t kAllFloor[] = {900, 0, 0, 0, 0, 900};
const uint16_t kFloorFirst[] = {900, 0, 0, 4, 0, 900};
const uint16_t kSingle[] = {900, 7, 900};
const uint16_t kRunnerTie[] = {900, 9, 5, 5, 900};
const uint16_t kRunnerBefore[] = {900, 7, 9, 3, 900};
const uint16_t kRaisedFloor[] = {65535, 11000, 11500, 11200, 65535};
const uint16_t kTopOfDomain[] = {0, 65535, 65534, 0};
const uint16_t kAllAtRaisedFloor[] = {65535, 11000, 11000, 65535};

// The float twin (P1b): the MediaTek tier's logits are float32 and its floor is the real -infinity.
struct ExpectF {
    int32_t best;
    int32_t second;
    float bestVal;
    float secondVal;
    uint32_t ties;
};

bool holdsF(const float *a, uint32_t lo, uint32_t hi, ExpectF e) {
    const BandTop2F r = scanBandTop2(a, lo, hi, -__builtin_inff());
    return r.best == e.best && r.second == e.second && r.bestVal == e.bestVal &&
           r.secondVal == e.secondVal && r.ties == e.ties;
}

const float kNegInf = -__builtin_inff();
const float kFClearWin[] = {90.0f, 3.5f, 12.25f, 7.0f, -1.5f, 90.0f};
const float kFTieAtTop[] = {90.0f, 4.0f, 9.5f, 9.5f, 2.0f, 90.0f};
const float kFAllFloor[] = {90.0f, kNegInf, kNegInf, kNegInf, 90.0f};
const float kFFloorFirst[] = {90.0f, kNegInf, kNegInf, 4.0f, kNegInf, 90.0f};
const float kFNegatives[] = {90.0f, -9.0f, -3.0f, -3.5f, 90.0f};
const float kFNanInBand[] = {90.0f, __builtin_nanf(""), 2.0f, 1.0f, 90.0f};

int run() {
    // 1. A clear winner: the argmax, the runner-up, one entry at the top.
    if (!holds(kClearWin, 1, 5, 0, {2, 3, 12, 7, 1})) return 1;
    // 2. A tie at the top resolves to the FIRST index; the other tied entry is the runner-up, the
    //    code margin is 0, and ties counts both. This is the <|en|>-by-fall-out shape.
    if (!holds(kTieAtTop, 1, 5, 0, {2, 3, 9, 9, 2})) return 2;
    // 3. A three-way tie: first index wins, second is the next, ties is all three.
    if (!holds(kThreeWayTie, 1, 4, 0, {1, 2, 9, 9, 3})) return 3;
    // 4. The whole band at the floor: no winner, no runner-up, no ties - the refusal case.
    if (!holds(kAllFloor, 1, 5, 0, {-1, -1, 0, 0, 0})) return 4;
    // 5. Floor entries never WIN, but they are legitimate runners-up: the first of them is the
    //    runner-up and the margin is the whole distance to the floor.
    if (!holds(kFloorFirst, 1, 5, 0, {3, 1, 4, 0, 1})) return 5;
    // 6. A single-entry band has a winner and no runner-up.
    if (!holds(kSingle, 1, 2, 0, {1, -1, 7, 0, 1})) return 6;
    // 7. A tie among the runners-up resolves to the first of them.
    if (!holds(kRunnerTie, 1, 4, 0, {1, 2, 9, 5, 1})) return 7;
    // 8. The runner-up may sit BEFORE the winner.
    if (!holds(kRunnerBefore, 1, 4, 0, {2, 1, 9, 7, 1})) return 8;
    // 9. A non-zero floor: the entry at it cannot win, the winner is above it, the runner-up is
    //    the highest of the rest.
    if (!holds(kRaisedFloor, 1, 4, 11000, {2, 3, 11500, 11200, 1})) return 9;
    // 10. The top of the ufixed16 domain: no overflow, a one-code margin.
    if (!holds(kTopOfDomain, 1, 3, 0, {1, 2, 65535, 65534, 1})) return 10;
    // 11. An empty range is the refusal case too.
    if (!holds(kClearWin, 3, 3, 0, {-1, -1, 0, 0, 0})) return 11;
    // 12. Every entry at a RAISED floor is the refusal case: the floor is whatever the caller says
    //     it is, not 0.
    if (!holds(kAllAtRaisedFloor, 1, 3, 11000, {-1, -1, 0, 0, 0})) return 12;
    // 13. Float: a clear winner inside live neighbours on both sides.
    if (!holdsF(kFClearWin, 1, 5, {2, 3, 12.25f, 7.0f, 1})) return 13;
    // 14. Float: a tie at the top resolves to the first index and counts both.
    if (!holdsF(kFTieAtTop, 1, 5, {2, 3, 9.5f, 9.5f, 2})) return 14;
    // 15. Float: every entry at -infinity is the refusal case.
    if (!holdsF(kFAllFloor, 1, 4, {-1, -1, 0.0f, 0.0f, 0})) return 15;
    // 16. Float: -infinity never wins but is a legitimate runner-up (the first of them).
    if (!holdsF(kFFloorFirst, 1, 5, {3, 1, 4.0f, kNegInf, 1})) return 16;
    // 17. Float: an all-negative band still has a winner - 0 is not a floor for float logits.
    if (!holdsF(kFNegatives, 1, 4, {2, 3, -3.0f, -3.5f, 1})) return 17;
    // 18. Float: an empty range is the refusal case.
    if (!holdsF(kFClearWin, 3, 3, {-1, -1, 0.0f, 0.0f, 0})) return 18;
    // 19. Float: a NaN never wins (every comparison with it is false); the winner is the best real
    //     value. (It can be the runner-up only as the first entry scanned, which the NaN-refusing
    //     caller never lets happen - checked here only for the winner.)
    {
        const BandTop2F r = scanBandTop2(kFNanInBand, 1, 4, kNegInf);
        if (r.best != 2 || r.bestVal != 2.0f || r.ties != 1) return 19;
    }
    return 0;
}

}  // namespace

// Two entry points because two builds exist: tools/band_scan_check.py links freestanding on Windows
// through the NDK's clang and lld (`mainCRTStartup`, no CRT), and a hosted clang++/g++ anywhere
// else wants `main`. Both return run()'s case number.
#if defined(BAND_SCAN_FREESTANDING)
// The MSVC target references `_fltused` from any object that touches floating point, and the CRT
// that normally defines it is exactly what this build leaves out. The float cases (13-19) made it
// necessary; its value is never read.
extern "C" int _fltused = 0;
extern "C" int mainCRTStartup() { return run(); }
#else
int main() { return run(); }
#endif
