package com.whispereverywhere

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The release identity, pinned. This is the one piece of ship mechanics that fails SILENTLY when
 * it is forgotten: a 4.1.0 build still carrying versionCode 78 is rejected only AFTER the upload,
 * and a 4.1.0 build still NAMED "3.7.0" ships release notes that the in-app About screen
 * contradicts. Both have a one-line fix and no other detector.
 *
 * **versionCode 80, not 79 — a deliberate skip, per the certified 4.1 plan.** No commit has ever
 * used 79 (verified in history: 78 was 3.7.0's); it is left for the 4.0 NPU release this branch
 * builds on, so the Model Lab ships above it whatever order the two reach the store in.
 *
 * It is also load-bearing beyond cosmetics. GpuPolicy keys its PERMANENT canary latches on
 * BuildConfig.VERSION_CODE (GpuPolicy.kt:101, 188, 261, 275, 284, 295), so moving 78 -> 80 clears
 * every recorded GPU verdict on every device — including the 3.6.0 "GPU-VERDICT: BAN
 * reason=slower" latch for multi. With the experimental multilingual-GPU toggle OFF (the shipped
 * default) nothing re-runs; with it ON, the canary runs once more on the first cold multi load.
 * The acceptance sheet says so where it matters.
 */
class ReleaseIdentityTest {

    @Test
    fun release_identity_is_4_1_0_at_version_code_80() {
        assertEquals(
            "versionName must be 4.1.0 for this release (app/build.gradle.kts defaultConfig)",
            "4.1.0",
            BuildConfig.VERSION_NAME,
        )
        assertEquals(
            "versionCode must be 80 for this release (app/build.gradle.kts defaultConfig)",
            80,
            BuildConfig.VERSION_CODE,
        )
    }
}
