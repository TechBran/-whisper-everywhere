package com.whispereverywhere.ui.onboarding

import android.os.Build
import com.whispereverywhere.ui.onboarding.AccessibilityAvailability.Availability
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the accessibility service can be enabled AT ALL (4.3.3, accessibility-optional-spec §2).
 * Pure and exhaustive: `classify` is walked over every combination of its three inputs, the
 * restricted-settings signal over every combination of its five, and the characteristics parse
 * over the shapes `ro.build.characteristics` actually takes. The Android reads that produce these
 * inputs live in one small adapter (AccessibilityAvailabilityProbe); the install-source read —
 * the half of the restricted-settings signal that is public API — is pinned there as source.
 */
class AccessibilityAvailabilityTest {

    @Test fun every_combination_of_the_three_inputs_is_classified_and_the_device_veto_wins() {
        for (hmd in listOf(true, false)) {
            for (xr in listOf(true, false)) {
                for (restricted in listOf(true, false)) {
                    val expected = when {
                        hmd || xr -> Availability.BLOCKED_BY_DEVICE
                        restricted -> Availability.RESTRICTED_SETTINGS
                        else -> Availability.ENABLEABLE
                    }
                    assertEquals(
                        "hmd=$hmd xr=$xr restricted=$restricted",
                        expected,
                        AccessibilityAvailability.classify(
                            isHmd = hmd,
                            hasXrSpatialFeature = xr,
                            restrictedSettingsSuspected = restricted,
                        ),
                    )
                }
            }
        }
    }

    @Test fun the_galaxy_xr_reads_blocked_by_either_of_its_two_signals() {
        // The owner's headset (SM-I610, Android 14, product xrvst2) carries BOTH:
        // ro.build.characteristics=HMD and android.software.xr.api.spatial=3. Its device policy
        // permits no third-party accessibility service ("Skipping enabling service disallowed
        // by device admin policy"), so the honest answer is blocked, whatever else is true.
        assertEquals(
            Availability.BLOCKED_BY_DEVICE,
            AccessibilityAvailability.classify(isHmd = true, hasXrSpatialFeature = true, restrictedSettingsSuspected = false),
        )
        // Either signal alone is enough — a future headset may carry one and not the other, and
        // the HMD read is a hidden-API reflection that can fail (see the probe).
        assertEquals(
            Availability.BLOCKED_BY_DEVICE,
            AccessibilityAvailability.classify(isHmd = true, hasXrSpatialFeature = false, restrictedSettingsSuspected = false),
        )
        assertEquals(
            Availability.BLOCKED_BY_DEVICE,
            AccessibilityAvailability.classify(isHmd = false, hasXrSpatialFeature = true, restrictedSettingsSuspected = false),
        )
        // And the restricted-settings guidance never shows on a device that cannot enable the
        // service by ANY route: "then try again" would be a lie there.
        assertEquals(
            Availability.BLOCKED_BY_DEVICE,
            AccessibilityAvailability.classify(isHmd = true, hasXrSpatialFeature = true, restrictedSettingsSuspected = true),
        )
    }

    @Test fun a_phone_reads_enableable_until_restricted_settings_is_suspected() {
        assertEquals(
            Availability.ENABLEABLE,
            AccessibilityAvailability.classify(isHmd = false, hasXrSpatialFeature = false, restrictedSettingsSuspected = false),
        )
        assertEquals(
            Availability.RESTRICTED_SETTINGS,
            AccessibilityAvailability.classify(isHmd = false, hasXrSpatialFeature = false, restrictedSettingsSuspected = true),
        )
    }

    @Test fun restricted_settings_is_suspected_only_for_a_non_store_install_that_bounced_still_off_on_api_33_non_hmd() {
        // TWO signals, both required (fix round 1, B1). The bounce — the user went to the
        // accessibility screen and came back with the service still off — is on its own exactly
        // what pressing Back without touching the toggle looks like, so it was never enough: a
        // Play phone read the sideload sentence after the most ordinary return there is. The
        // install-source read (public API on 33+, `getInstallSourceInfo(pkg).packageSource`) says
        // whether Restricted Settings can apply AT ALL, and a store install cannot be restricted.
        // Android still exposes no "this toggle was disallowed" API, so the conjunction is an
        // inference and the sentence it gates says "may be". Exhaustive over all five inputs.
        for (returned in listOf(true, false)) {
            for (enabled in listOf(true, false)) {
                for (api in listOf(26, 32, 33, 34, 36)) {
                    for (hmd in listOf(true, false)) {
                        for (notFromStore in listOf(true, false)) {
                            val expected = returned && !enabled && api >= 33 && !hmd && notFromStore
                            assertEquals(
                                "returned=$returned enabled=$enabled api=$api hmd=$hmd notFromStore=$notFromStore",
                                expected,
                                AccessibilityAvailability.restrictedSettingsSuspected(
                                    returnedFromSettings = returned,
                                    serviceEnabled = enabled,
                                    apiLevel = api,
                                    isHmd = hmd,
                                    installNotFromStore = notFromStore,
                                ),
                            )
                        }
                    }
                }
            }
        }
        // Restricted Settings shipped with Android 13 — the threshold is pinned, not implied,
        // and the probe's guard spells the same level through the platform constant.
        assertEquals(33, AccessibilityAvailability.RESTRICTED_SETTINGS_MIN_API)
        assertEquals(AccessibilityAvailability.RESTRICTED_SETTINGS_MIN_API, Build.VERSION_CODES.TIRAMISU)
    }

    @Test fun the_b1_return_a_play_phone_backing_out_of_the_accessibility_list_is_not_restricted() {
        // The blocker's exact trigger: an API 34 phone, a Play install (PACKAGE_SOURCE_STORE), the
        // user opened the list from Enable and pressed Back. Before the install-source input this
        // read RESTRICTED and told them Android was blocking a sideload. Now it cannot.
        assertFalse(
            AccessibilityAvailability.restrictedSettingsSuspected(
                returnedFromSettings = true,
                serviceEnabled = false,
                apiLevel = 34,
                isHmd = false,
                installNotFromStore = false,
            ),
        )
        // The same bounce on a genuine sideload IS the suspicion — the one case the guidance is for.
        assertTrue(
            AccessibilityAvailability.restrictedSettingsSuspected(
                returnedFromSettings = true,
                serviceEnabled = false,
                apiLevel = 34,
                isHmd = false,
                installNotFromStore = true,
            ),
        )
        // The headset's Play install reads packageSource=0 (not STORE), so its input is true —
        // and the device veto still wins in classify, so "then try again" never renders there,
        // even if the hmd input handed to the suspicion were mis-read.
        assertEquals(
            Availability.BLOCKED_BY_DEVICE,
            AccessibilityAvailability.classify(
                isHmd = true,
                hasXrSpatialFeature = true,
                restrictedSettingsSuspected = AccessibilityAvailability.restrictedSettingsSuspected(
                    returnedFromSettings = true,
                    serviceEnabled = false,
                    apiLevel = 34,
                    isHmd = false,
                    installNotFromStore = true,
                ),
            ),
        )
    }

    /**
     * THE PROBE'S INSTALL-SOURCE READ, PINNED AS SOURCE. The pure rule cannot see whether its
     * `installNotFromStore` input is really fed from `PackageManager.getInstallSourceInfo` — a
     * probe that passed `true` (the old four-input answer) compiles and re-opens B1. So the
     * adapter's one new Android read is pinned by shape: the public API field, compared to STORE,
     * guarded on API 33, inside `runCatching`, failing towards "from the store" (Enable stays
     * primary), and handed to the rule by name. Read LF-normalised (`core.autocrlf=true`).
     * Symbol-scoped, no line numbers.
     */
    @Test fun the_probe_reads_the_install_source_through_the_public_api_and_fails_towards_the_store() {
        val probe = probeSource()
        assertEquals(
            "the read is the public API field Restricted Settings keys on, compared to STORE, " +
                "and a failed read is 'from the store'",
            1,
            probe.split(
                "        context.packageManager.getInstallSourceInfo(context.packageName).packageSource !=\n" +
                    "            PackageInstaller.PACKAGE_SOURCE_STORE\n" +
                    "    }.getOrDefault(false)",
            ).size - 1,
        )
        assertEquals(
            "the read is guarded on API 33 — below it the field does not exist, and neither does the block",
            1,
            probe.split(
                "Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && packageSourceIsNotStore(context)",
            ).size - 1,
        )
        assertEquals(
            "and the pure rule receives it by name — no literal, no stand-in",
            1,
            probe.split("                installNotFromStore = installNotFromStore(context),").size - 1,
        )
        assertEquals("no literal feeds the rule", 0, probe.split("installNotFromStore = true").size - 1)
    }

    private fun probeSource(): String {
        val relative = "src/main/java/com/whispereverywhere/ui/onboarding/AccessibilityAvailabilityProbe.kt"
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    @Test fun the_hmd_characteristic_is_a_token_match_any_case_any_position() {
        // `ro.build.characteristics` is a comma-separated list; the brief's "contains hmd" is a
        // TOKEN test, so a token that merely contains the letters is not a headset.
        assertTrue("the Galaxy XR's exact value", AccessibilityAvailability.isHmdCharacteristic("HMD"))
        assertTrue(AccessibilityAvailability.isHmdCharacteristic("hmd"))
        assertTrue(AccessibilityAvailability.isHmdCharacteristic("nosdcard,hmd"))
        assertTrue(AccessibilityAvailability.isHmdCharacteristic("hmd, tablet"))
        assertFalse(AccessibilityAvailability.isHmdCharacteristic("tablet"))
        assertFalse(AccessibilityAvailability.isHmdCharacteristic("nosdcard"))
        assertFalse(AccessibilityAvailability.isHmdCharacteristic("default"))
        assertFalse(AccessibilityAvailability.isHmdCharacteristic("phone,nosdcard"))
        assertFalse(AccessibilityAvailability.isHmdCharacteristic(""))
        assertFalse("a failed hidden-API read is not a headset", AccessibilityAvailability.isHmdCharacteristic(null))
        assertFalse(AccessibilityAvailability.isHmdCharacteristic("shmdx"))
    }

    @Test fun the_xr_feature_name_is_the_one_the_headset_declares() {
        assertEquals("android.software.xr.api.spatial", AccessibilityAvailability.XR_SPATIAL_FEATURE)
        assertEquals("ro.build.characteristics", AccessibilityAvailability.CHARACTERISTICS_PROPERTY)
    }
}
