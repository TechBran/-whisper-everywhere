package com.whispereverywhere.ui.onboarding

/**
 * Where the accessibility service can be enabled AT ALL — the pure half of 4.3.3's
 * platform-aware copy (accessibility-optional-spec §2). Android-free so every rule is a JVM
 * test ([AccessibilityAvailabilityTest] walks all eight input combinations); the three reads
 * that feed it are one small adapter, [AccessibilityAvailabilityProbe].
 *
 * Why this exists: the owner's Galaxy XR (SM-I610) has a device policy that permits NO
 * third-party accessibility service — `AccessibilityManagerService: Skipping enabling service
 * disallowed by device admin policy` — and its Settings list shows only TalkBack. Sending that
 * user to Settings with "Enable" is a dead end, so the card says what the device can do instead.
 * On Android 13+ phones a second, unrelated block exists: Restricted Settings refuses the toggle
 * for an install whose installer did not declare PACKAGE_SOURCE_STORE (`ACCESS_RESTRICTED_SETTINGS:
 * ignore`), and the remedy is App info -> menu -> "Allow restricted settings". Two blocks, two
 * sentences, one rule.
 */
object AccessibilityAvailability {

    enum class Availability {
        /** Nothing known stands in the way: Settings' toggle should work. */
        ENABLEABLE,

        /** A headset / XR device whose policy forbids third-party accessibility services. */
        BLOCKED_BY_DEVICE,

        /** Android 13+ Restricted Settings is suspected to be refusing the toggle. */
        RESTRICTED_SETTINGS,
    }

    /** The system feature the Galaxy XR declares (`android.software.xr.api.spatial=3`). */
    const val XR_SPATIAL_FEATURE = "android.software.xr.api.spatial"

    /** The build property whose token list carries `HMD` on the headset. */
    const val CHARACTERISTICS_PROPERTY = "ro.build.characteristics"

    /** Restricted Settings shipped with Android 13 (API 33). */
    const val RESTRICTED_SETTINGS_MIN_API = 33

    /**
     * The classification. The DEVICE veto wins: a headset cannot enable the service by any
     * route, so the restricted-settings guidance ("then try again") must never render there.
     */
    fun classify(
        isHmd: Boolean,
        hasXrSpatialFeature: Boolean,
        restrictedSettingsSuspected: Boolean,
    ): Availability = when {
        isHmd || hasXrSpatialFeature -> Availability.BLOCKED_BY_DEVICE
        restrictedSettingsSuspected -> Availability.RESTRICTED_SETTINGS
        else -> Availability.ENABLEABLE
    }

    /**
     * Whether Android's Restricted Settings is SUSPECTED to be refusing the toggle. This is the
     * ONLY signal an app has (brief §2): Android exposes no "this service was disallowed" API,
     * so the inference is "the user went to the accessibility screen and came back with the
     * service still off, on an API >= 33 device that is not a headset". It is an inference, and
     * the sentence it gates is worded as guidance rather than a diagnosis for that reason.
     *
     * @param returnedFromSettings the flow saw an ON_RESUME after its own Enable tap.
     * @param serviceEnabled `WhisperAccessibilityService.isEnabled()` at that resume.
     * @param apiLevel `Build.VERSION.SDK_INT`.
     * @param isHmd the headset read — a headset's "still off" is the device veto, not this.
     */
    fun restrictedSettingsSuspected(
        returnedFromSettings: Boolean,
        serviceEnabled: Boolean,
        apiLevel: Int,
        isHmd: Boolean,
    ): Boolean =
        returnedFromSettings && !serviceEnabled && apiLevel >= RESTRICTED_SETTINGS_MIN_API && !isHmd

    /**
     * Whether a `ro.build.characteristics` value names a head-mounted display. The property is a
     * comma-separated token list (`HMD` on the Galaxy XR; `tablet,nosdcard` elsewhere), so this
     * is a case-insensitive TOKEN match — a token that merely contains the letters is not a
     * headset, and a null (the hidden-API read failed) is not one either.
     */
    fun isHmdCharacteristic(characteristics: String?): Boolean =
        characteristics.orEmpty()
            .split(',')
            .any { it.trim().equals("hmd", ignoreCase = true) }
}
