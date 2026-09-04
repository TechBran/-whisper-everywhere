// band_scan.h - the language band's top two, for libqnnasr.so's detect pass (build 85 B1).
//
// Header-only and free of Android, JNI and QNN ON PURPOSE. This is the one piece of the detect
// pass with no device in it, and keeping it apart lets tools/band_scan_check.py compile the SAME
// code the .so carries with the NDK's own clang into a host executable and run
// app/src/test/cpp/band_scan_test.cpp against it - the only test a C++ change here can have short
// of a phone. qnn_asr.cpp includes it; nothing else does. NpuNativeContractTest pins the
// Android-free property by source text.
#pragma once

#include <stdint.h>

/// What one `detect:` line is made of: the band's argmax, its runner-up and the tie count.
///
/// `best` is the language the pass answers with. Ties resolve to the FIRST index, as every argmax
/// on this seam does - and the first index of the band is `<|en|>`, which is why `ties` exists:
/// "detected en" with `ties > 1` is a flat band that fell out at its starting index, not a
/// decision, and nothing but this count can tell the two apart. `second` is the highest entry
/// other than `best` (ties to the first index again); `bestVal - secondVal` is the raw-code
/// margin the caller scales into the model's own units.
struct BandTop2 {
    /// Argmax over the band; -1 when every entry sits at the floor (the seam's -infinity).
    int32_t best = -1;
    /// Runner-up; -1 when there is no `best`, or the band has a single entry.
    int32_t second = -1;
    /// The raw codes behind the two ids. 0 whenever the id beside them is -1.
    uint16_t bestVal = 0;
    uint16_t secondVal = 0;
    /// Entries equal to `bestVal`, `best` itself included: 1 is a clean win, > 1 is a tie, 0 means
    /// there was no `best`.
    uint32_t ties = 0;
};

/// Two O(band) passes over `[lo, hi)`: the argmax, then the runner-up and the tie count.
///
/// `floor` is the code that counts as -infinity. An entry AT the floor never wins, so a band that
/// is entirely at the floor answers `best = -1` - exactly what the range-restricted argmax this
/// replaced did, and for the same reason: a dead graph output must be reported as one, not
/// resolved to `<|en|>`. The restriction to a RANGE sits on this side of the boundary for the
/// same reason the decode's suppression mask does: the caller must never be handed an
/// unrestricted argmax and asked to decide whether it counts.
///
/// The runner-up pass does not exclude the floor: the margin is a statement about the band as
/// the model emitted it, and a runner-up at the floor is a real reading (an enormous margin), not
/// a missing one.
inline BandTop2 scanBandTop2(const uint16_t *logits, uint32_t lo, uint32_t hi, uint16_t floor) {
    BandTop2 r;
    uint16_t bestVal = floor;
    for (uint32_t i = lo; i < hi; ++i) {
        if (logits[i] > bestVal) {
            bestVal = logits[i];
            r.best = static_cast<int32_t>(i);
        }
    }
    if (r.best < 0) return r;
    r.bestVal = bestVal;
    bool haveSecond = false;
    for (uint32_t i = lo; i < hi; ++i) {
        const uint16_t v = logits[i];
        if (v == bestVal) ++r.ties;
        if (static_cast<int32_t>(i) == r.best) continue;
        if (!haveSecond || v > r.secondVal) {
            r.secondVal = v;
            r.second = static_cast<int32_t>(i);
            haveSecond = true;
        }
    }
    return r;
}
