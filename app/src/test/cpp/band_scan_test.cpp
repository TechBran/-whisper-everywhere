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
    return 0;
}

}  // namespace

// Two entry points because two builds exist: tools/band_scan_check.py links freestanding on Windows
// through the NDK's clang and lld (`mainCRTStartup`, no CRT), and a hosted clang++/g++ anywhere
// else wants `main`. Both return run()'s case number.
#if defined(BAND_SCAN_FREESTANDING)
extern "C" int mainCRTStartup() { return run(); }
#else
int main() { return run(); }
#endif
