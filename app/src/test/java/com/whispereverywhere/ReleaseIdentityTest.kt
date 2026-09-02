package com.whispereverywhere

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The release identity, pinned. This is the one piece of ship mechanics that fails SILENTLY when
 * it is forgotten: a 4.2.0 build still carrying versionCode 80 is rejected only AFTER the upload,
 * and a 4.2.0 build still NAMED "4.1.0" ships release notes that the in-app About screen
 * contradicts. Both have a one-line fix and no other detector.
 *
 * **versionCode 82 — the plain successor to 81.** 81 was consumed by the first internal-track
 * upload (the fleet-onboarding build the owner installed and validated on 2026-08-30), so it is
 * spent: Play will refuse a second upload at the same code. 4.3 takes the next integer, and its
 * NAME moves with it — the trunk now carries the one-tier-per-device lineup, and a build named for
 * the release before the one it contains is the exact silent mismatch this test exists to catch.
 *
 * **versionCode 83 — the plain successor to 82.** 82 went to PRODUCTION on 2026-08-30 as 4.3.0,
 * so it is spent twice over: Play refuses a second upload at the same code, and every installed
 * phone already carries it. 4.3.1 is a patch (three field reports, no new surface), so the NAME
 * moves by one in the last place and the code by one integer; 83 > 82 is what lets the track
 * install replace the production build on the owner's own phone.
 *
 * **What 82 buys, stated precisely.** It buys an upgrade over the 81 build now sitting on the
 * track AND on the owner's phone: 82 > 81, so the next track install replaces it — which is a real
 * change from 4.2's position, where u4 (uninstall before the track install) was MANDATORY because
 * the local build was 81-signed and Play offers no update path at equal versionCode. A local
 * bundletool `install-apks` set is still not a Play-managed install, so u4 remains mandatory
 * whenever a LOCAL 82 build has been installed by hand. If this KDoc and
 * docs/superpowers/sdd/2026-08-29-fleet-onboarding/acceptance.md ever disagree, the sheet is the
 * instrument and the sheet is right.
 *
 * It is also load-bearing beyond cosmetics. GpuPolicy keys its PERMANENT canary latches on
 * BuildConfig.VERSION_CODE (GpuPolicy.kt:101, 188, 261, 275, 284, 295), so moving 81 -> 82 clears
 * every recorded GPU verdict on every device — the same side effect 78 -> 80 and 80 -> 81 had, and it
 * recurs on every bump by design. With the experimental multilingual-GPU toggle OFF (the shipped
 * default) nothing re-runs; with it ON, the canary runs once more on the first cold multi load.
 * The acceptance sheet says so where it matters
 * (docs/superpowers/sdd/2026-08-29-fleet-onboarding/acceptance.md).
 */
class ReleaseIdentityTest {

    @Test
    fun release_identity_is_4_3_1_at_version_code_83() {
        assertEquals(
            "versionName must be 4.3.1 for this release (app/build.gradle.kts defaultConfig)",
            "4.3.1",
            BuildConfig.VERSION_NAME,
        )
        assertEquals(
            "versionCode must be 83 for this release (app/build.gradle.kts defaultConfig)",
            83,
            BuildConfig.VERSION_CODE,
        )
    }
}
