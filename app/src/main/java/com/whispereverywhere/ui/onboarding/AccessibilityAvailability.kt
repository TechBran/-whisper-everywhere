package com.whispereverywhere.ui.onboarding

/**
 * Where the accessibility service can be enabled AT ALL — the pure half of 4.3.3's
 * platform-aware copy (accessibility-optional-spec §2). Android-free so every rule is a JVM
 * test ([AccessibilityAvailabilityTest] walks all eight input combinations); the four reads
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
     * Whether Android's Restricted Settings is SUSPECTED to be refusing the toggle. Two signals,
     * both required, and still an inference — Android exposes no "this service was disallowed"
     * API (the exact AppOps signal, `ACCESS_RESTRICTED_SETTINGS`, has no public op string):
     *
     *  - **Can the block apply?** Restricted Settings only guards installs whose installer did
     *    not declare `PACKAGE_SOURCE_STORE`, and that field IS public on API 33+ —
     *    `PackageManager.getInstallSourceInfo(pkg).packageSource` (the probe reads it). A Play
     *    install that declared STORE can never read RESTRICTED, whatever the user did in Settings.
     *  - **Did it apply?** The user went to the accessibility screen from this step's Enable and
     *    came back with the service still off. On its own this is also exactly what pressing
     *    Back without touching the toggle looks like — the most ordinary return there is — which
     *    is why it was never sufficient (fix round 1, B1), and why the sentence it gates is
     *    guidance ("may be blocking"), not a diagnosis.
     *
     * @param returnedFromSettings the flow saw an ON_RESUME after its own Enable tap.
     * @param serviceEnabled `WhisperAccessibilityService.isEnabled()` at that resume.
     * @param apiLevel `Build.VERSION.SDK_INT`.
     * @param isHmd the headset read — a headset's "still off" is the device veto, not this.
     * @param installNotFromStore the install's `packageSource` is not `PACKAGE_SOURCE_STORE` —
     *        the probe answers `false` below API 33 and on any failed read, the direction that
     *        keeps Enable primary.
     */
    fun restrictedSettingsSuspected(
        returnedFromSettings: Boolean,
        serviceEnabled: Boolean,
        apiLevel: Int,
        isHmd: Boolean,
        installNotFromStore: Boolean,
    ): Boolean =
        returnedFromSettings && !serviceEnabled && apiLevel >= RESTRICTED_SETTINGS_MIN_API &&
            !isHmd && installNotFromStore

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
