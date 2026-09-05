package com.whispereverywhere.ui.onboarding

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The ONE Android adapter for [AccessibilityAvailability] (4.3.3, accessibility-optional-spec
 * §2): four guarded reads feeding the pure rule. Every DECISION lives in the pure object; this
 * file only answers "what does this device say", and every read fails towards "not a headset" /
 * "no feature" / "from the store", which is the direction that keeps the Enable path open.
 *
 *  - `ro.build.characteristics` has no public accessor. It is read through
 *    `android.os.SystemProperties.get` by reflection — a hidden API that is unsupported but
 *    accessible (it is not on the blocklist). A failed read is `null`, which
 *    [AccessibilityAvailability.isHmdCharacteristic] answers `false` for; that is why the XR
 *    system feature is a second, INDEPENDENT signal for the same device veto.
 *  - `PackageManager.hasSystemFeature(android.software.xr.api.spatial)` — public API.
 *  - `Build.VERSION.SDK_INT` — the Restricted Settings threshold.
 *  - `PackageManager.getInstallSourceInfo(pkg).packageSource` — public API on 33+, and the very
 *    field Restricted Settings keys on (fix round 1, B1): an install whose installer declared
 *    `PACKAGE_SOURCE_STORE` is never restricted, so the guidance cannot render on a Play phone
 *    that merely backed out of the Accessibility list. A failed read is "from the store".
 */
object AccessibilityAvailabilityProbe {

    fun isHmd(): Boolean = AccessibilityAvailability.isHmdCharacteristic(buildCharacteristics())

    fun hasXrSpatialFeature(context: Context): Boolean = runCatching {
        context.packageManager.hasSystemFeature(AccessibilityAvailability.XR_SPATIAL_FEATURE)
    }.getOrDefault(false)

    /**
     * Whether this install's declared source is anything other than `PACKAGE_SOURCE_STORE` — the
     * precondition for Android 13+ Restricted Settings to refuse the toggle at all (fix round 1,
     * B1). Below API 33 the field does not exist and neither does the block; a failed read is
     * `false`, because "from the store" is the answer that keeps Enable primary.
     */
    fun installNotFromStore(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && packageSourceIsNotStore(context)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun packageSourceIsNotStore(context: Context): Boolean = runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).packageSource !=
            PackageInstaller.PACKAGE_SOURCE_STORE
    }.getOrDefault(false)

    /**
     * The device's answer, composed through the pure rule.
     *
     * @param returnedFromSettings the caller saw an ON_RESUME after its own Enable tap — the
     *        bounce half of the Restricted Settings suspicion (the install-source half is read
     *        here; see the pure object's KDoc). A surface with no such tap (Settings' row) passes
     *        `false` and can never read RESTRICTED.
     * @param serviceEnabled `WhisperAccessibilityService.isEnabled()` at that moment.
     */
    fun classify(
        context: Context,
        returnedFromSettings: Boolean,
        serviceEnabled: Boolean,
    ): AccessibilityAvailability.Availability {
        val hmd = isHmd()
        return AccessibilityAvailability.classify(
            isHmd = hmd,
            hasXrSpatialFeature = hasXrSpatialFeature(context),
            restrictedSettingsSuspected = AccessibilityAvailability.restrictedSettingsSuspected(
                returnedFromSettings = returnedFromSettings,
                serviceEnabled = serviceEnabled,
                apiLevel = Build.VERSION.SDK_INT,
                isHmd = hmd,
                installNotFromStore = installNotFromStore(context),
            ),
        )
    }

    @SuppressLint("PrivateApi")
    private fun buildCharacteristics(): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, AccessibilityAvailability.CHARACTERISTICS_PROPERTY) as? String
    }.getOrNull()
}
