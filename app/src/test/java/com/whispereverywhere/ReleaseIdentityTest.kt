package com.whispereverywhere

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The release identity, pinned. This is the one piece of ship mechanics that fails SILENTLY when
 * it is forgotten: a 4.2.0 build still carrying versionCode 80 is rejected only AFTER the upload,
 * and a 4.2.0 build still NAMED "4.1.0" ships release notes that the in-app About screen
 * contradicts. Both have a one-line fix and no other detector.
 *
 * **versionCode 81 — the plain successor to 80.** 4.1's 80 was itself a deliberate skip over 79
 * (left for the 4.0 NPU release, per the certified 4.1 plan); nothing about 4.2 needs a second
 * skip, so the fleet build takes the next integer.
 *
 * **What 81 buys, stated precisely, because an earlier draft of this KDoc got it backwards.** It
 * buys an upgrade over the 4.1 builds already on the device: 81 > 80, so a track install replaces
 * them. It does NOT let Play install over an 81-signed LOCAL build — Play offers no update path at
 * equal versionCode, and a bundletool `install-apks` set is not a Play-managed install in the first
 * place. That is exactly why the run-book's §2 makes u4 (uninstall before the track install) a
 * MANDATORY step rather than a conditional one. If this KDoc and
 * docs/superpowers/sdd/2026-08-29-fleet-onboarding/acceptance.md ever disagree, the sheet is the
 * instrument and the sheet is right.
 *
 * It is also load-bearing beyond cosmetics. GpuPolicy keys its PERMANENT canary latches on
 * BuildConfig.VERSION_CODE (GpuPolicy.kt:101, 188, 261, 275, 284, 295), so moving 80 -> 81 clears
 * every recorded GPU verdict on every device — the same side effect 78 -> 80 had at 4.1, and it
 * recurs on every bump by design. With the experimental multilingual-GPU toggle OFF (the shipped
 * default) nothing re-runs; with it ON, the canary runs once more on the first cold multi load.
 * The acceptance sheet says so where it matters
 * (docs/superpowers/sdd/2026-08-29-fleet-onboarding/acceptance.md).
 */
class ReleaseIdentityTest {

    @Test
    fun release_identity_is_4_2_0_at_version_code_81() {
        assertEquals(
            "versionName must be 4.2.0 for this release (app/build.gradle.kts defaultConfig)",
            "4.2.0",
            BuildConfig.VERSION_NAME,
        )
        assertEquals(
            "versionCode must be 81 for this release (app/build.gradle.kts defaultConfig)",
            81,
            BuildConfig.VERSION_CODE,
        )
    }
}
