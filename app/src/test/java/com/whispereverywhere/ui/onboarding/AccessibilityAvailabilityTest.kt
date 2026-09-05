package com.whispereverywhere.ui.onboarding

import com.whispereverywhere.ui.onboarding.AccessibilityAvailability.Availability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the accessibility service can be enabled AT ALL (4.3.3, accessibility-optional-spec §2).
 * Pure and exhaustive: `classify` is walked over every combination of its three inputs, the
 * restricted-settings signal over every combination of its four, and the characteristics parse
 * over the shapes `ro.build.characteristics` actually takes. The Android reads that produce these
 * inputs live in one small adapter (AccessibilityAvailabilityProbe) and are pinned as source.
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

    @Test fun a_phone_reads_enableable_until_the_one_restricted_settings_signal_arrives() {
        assertEquals(
            Availability.ENABLEABLE,
            AccessibilityAvailability.classify(isHmd = false, hasXrSpatialFeature = false, restrictedSettingsSuspected = false),
        )
        assertEquals(
            Availability.RESTRICTED_SETTINGS,
            AccessibilityAvailability.classify(isHmd = false, hasXrSpatialFeature = false, restrictedSettingsSuspected = true),
        )
    }

    @Test fun restricted_settings_is_suspected_only_on_a_returned_still_off_api_33_non_hmd() {
        // THE ONLY SIGNAL AN APP HAS (brief §2): the user went to the accessibility screen and
        // came back with the service still off, on an API >= 33 device that is not a headset.
        // Android exposes no "this toggle was disallowed" API; this is inference, and the copy it
        // gates says "Android is blocking this" only where all four hold. Exhaustive.
        for (returned in listOf(true, false)) {
            for (enabled in listOf(true, false)) {
                for (api in listOf(26, 32, 33, 34, 36)) {
                    for (hmd in listOf(true, false)) {
                        val expected = returned && !enabled && api >= 33 && !hmd
                        assertEquals(
                            "returned=$returned enabled=$enabled api=$api hmd=$hmd",
                            expected,
                            AccessibilityAvailability.restrictedSettingsSuspected(
                                returnedFromSettings = returned,
                                serviceEnabled = enabled,
                                apiLevel = api,
                                isHmd = hmd,
                            ),
                        )
                    }
                }
            }
        }
        // Restricted Settings shipped with Android 13 — the threshold is pinned, not implied.
        assertEquals(33, AccessibilityAvailability.RESTRICTED_SETTINGS_MIN_API)
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
