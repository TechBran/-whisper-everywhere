package com.whispereverywhere.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.7 Decision Gate 2 (owner call 2026-08-20): the "Try GPU for multilingual (experimental)"
 * Settings row is retired. The 2026-08-19 A/B closed the question — the multilingual GPU arm is
 * correct but ~9x slower, and the canary is corruption-only by design, so a PASS routes a
 * toggle-ON user straight to the slow backend.
 *
 * The deliverable is a Compose row that no longer exists, which has no runtime surface to assert
 * against — so this pins it at the source level, in both directions. The second half matters more
 * than the first: the row is gone, but the PREFERENCE must stay readable, or an existing `true`
 * on a shipped device becomes unreadable state and GpuPolicy's multilingual branch changes
 * meaning rather than merely losing its switch.
 *
 * **The source is read LF-NORMALISED**, at the single read site below. `core.autocrlf=true` checks
 * this repo out with CRLF, so a needle written with a bare `\n` finds nothing and the assertion
 * would pass or fail for the wrong reason. Every needle here happens to be single-line, which makes
 * the normalisation currently inert — it is present anyway, because the next needle added to this
 * class must not have to rediscover the rule (H4 handoff, carried item 2).
 */
class GpuExperimentRowRetiredTest {

    /**
     * The house walk-up idiom, shared with `ChooserSteerWiringPinTest`,
     * `UnsupportedTierGatePinTest`, `EndpointerFactoryTest`, `EndpointerTuningTest`,
     * `SileroEndpointerTest` and `VadProbeLifecycleTest`. The plan's H5 variant walked up from
     * `File(".")` with no `app/$relative` fallback and assigned the platform type `File!` to a
     * non-null `File`, which compiled with a `Java type mismatch` warning — the only warning
     * Workstream H introduced (H4 review o2, H5 close-out).
     */
    private fun source(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun read(relative: String): String =
        source(relative).readText().replace("\r\n", "\n")

    @Test fun settings_no_longer_offers_the_multilingual_gpu_toggle() {
        val text = read("src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt")
        assertFalse(
            "the GPU experiment row is back in SettingsScreen",
            text.contains("Try GPU for multilingual"),
        )
        assertFalse(
            "SettingsScreen still writes the GPU experiment preference",
            text.contains("setGpuMultilingualExperiment"),
        )
        assertFalse(
            "SettingsScreen still observes the GPU experiment preference",
            text.contains("gpuMultilingualExperiment"),
        )
    }

    @Test fun the_preference_stays_readable_so_existing_true_values_still_mean_something() {
        val prefs = read("src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt")
        assertTrue(
            "the GPU experiment getter was deleted — an existing `true` is now unreadable",
            prefs.contains("fun isGpuMultilingualExperimentEnabled()"),
        )
        assertTrue(
            "the GPU experiment preference KEY was deleted",
            prefs.contains("KEY_GPU_MULTI_EXPERIMENT"),
        )
        // The constant's NAME is only a proxy; the STORED STRING is what a shipped device's
        // SharedPreferences is keyed by. H5 battery row (f) measured the name-only needle above
        // staying green while the literal was changed to "gpu_multi_experiment" — which orphans
        // every `true` already on a user's phone, silently, and is the precise harm this test
        // exists to prevent. Pin the literal, not the identifier that happens to hold it.
        assertTrue(
            "the GPU experiment preference key's STORED STRING changed — every `true` already " +
                "written on a shipped device is now orphaned, which reads as a silent opt-out " +
                "rather than the preserved state this retirement promised",
            prefs.contains("\"gpu_multilingual_experiment\""),
        )
    }

    @Test fun gpu_policy_still_consults_the_stored_value() {
        val policy = read("src/main/java/com/whispereverywhere/transcription/GpuPolicy.kt")
        assertTrue(
            "GpuPolicy no longer reads the experiment preference — the machinery is not inert, " +
                "it is changed",
            policy.contains("isGpuMultilingualExperimentEnabled()"),
        )
        assertTrue(
            "the canary gate that a stored `true` opens was removed",
            policy.contains("canaryVerdict("),
        )
    }
}
