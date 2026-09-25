package com.whispereverywhere.npu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * One answer of the MediaTek driver check (P2; design
 * `docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md` §2.3, the owner's "version
 * checker there to make sure we're hitting the right chip for the driver") — what
 * `LiteRtAsrNative.nativeProbe` said, stored so a later process can answer the capability
 * question without walking the Neuron adapter again.
 *
 * **What the record keeps, and what it cannot.** The probe answers exactly one thing to Kotlin:
 * `""` for a pass, or `"probe: <reason>"` for a refusal (`adapter-missing`, `adapter-<name>`,
 * `driver-version-unreadable`, `driver-major-<got>-want-<want>`, `runtime: <detail>`). The
 * driver's own name and version (`libneuronusdk_adapter.mtk.so 8.2.26`) and the APU device names
 * are printed natively, on the `apu:` line of `WE-DIAG` (§2.3 item 5), and are not in the probe's
 * return value — so they are not in this record, and a later process that reuses it reports the
 * verdict and when it was taken ([NpuDiag.apuVerdict]), not the driver line a second time.
 *
 * @property fingerprint `Build.FINGERPRINT` when the probe ran: the key §2.3 names. An OTA moves
 *   it, and a verdict taken on another ROM's driver answers for nothing.
 * @property appBuild the app's `versionCode` when the probe ran — a second key, beyond the
 *   design's "fingerprint only", and for a reason the design's P2 order makes concrete: the answer
 *   depends on THIS BUILD as well as the ROM. The adapter loads only once the manifest declares it
 *   (`<uses-native-library>`, P2-6) and a pass needs `libLiteRt.so` packaged (P2-6), so a refusal
 *   stored by a build without them, reused by the build that adds them, would hide the tier on the
 *   owner's tablet until the next OTA. A new build re-probes once — 169–239 ms, off Main.
 * @property wantMajor the family's `neuronMajor` the driver was judged against — the verdict's
 *   meaning (a pass says "major == this"), and a third key for the same reason as [appBuild].
 * @property refusal null for a pass; otherwise the probe's reason, the text after `probe: `.
 * @property probedAtMs the wall-clock time the answer was taken, for the diag line.
 */
data class NpuApuVerdict(
    val fingerprint: String,
    val appBuild: Int,
    val wantMajor: Int,
    val refusal: String?,
    val probedAtMs: Long,
) {
    /** True exactly when the driver check passed. */
    val passed: Boolean get() = refusal == null

    /** The one stored spelling: a single JSON object, so a write can never be torn in half. */
    fun encode(): String = buildJsonObject {
        put("format", FORMAT)
        put("fingerprint", fingerprint)
        put("appBuild", appBuild)
        put("wantMajor", wantMajor)
        put("refusal", refusal)
        put("probedAtMs", probedAtMs)
    }.toString()

    companion object {
        /** The stored format this build writes and the only one it reads. */
        const val FORMAT: Int = 1

        /**
         * The stored record, or null — and null is always safe: it means "probe again". Strict,
         * because a record that half-parses is not a verdict anyone took: a missing or mistyped
         * field, another format, a blank refusal or anything that is not one JSON object reads
         * as no record at all.
         */
        fun decode(text: String?): NpuApuVerdict? {
            if (text.isNullOrBlank()) return null
            val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return null
            if (intOf(obj, "format") != FORMAT) return null
            val fingerprint = stringOf(obj, "fingerprint") ?: return null
            val appBuild = intOf(obj, "appBuild") ?: return null
            val wantMajor = intOf(obj, "wantMajor") ?: return null
            val probedAtMs = longOf(obj, "probedAtMs") ?: return null
            val refusal = when (val element = obj["refusal"]) {
                JsonNull -> null
                is JsonPrimitive -> if (element.isString && element.content.isNotBlank()) {
                    element.content
                } else {
                    return null
                }
                else -> return null
            }
            return NpuApuVerdict(fingerprint, appBuild, wantMajor, refusal, probedAtMs)
        }

        private fun stringOf(obj: JsonObject, name: String): String? =
            (obj[name] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content

        private fun intOf(obj: JsonObject, name: String): Int? =
            (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

        private fun longOf(obj: JsonObject, name: String): Long? =
            (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
    }
}

/**
 * THE MEDIATEK DRIVER CHECK, as this process holds it (P2; design §2.3 items 1–3) — the stored
 * verdict `WhisperEverywhereApp.npuCapableDevice` reads on a MediaTek family instead of dlopening
 * anything itself.
 *
 * **Unknown until probed.** [verdict] starts null — nobody has asked the driver yet in this
 * process — and "unknown" is NOT capable: [NpuGate.runtimeAvailable] answers false for it, so a
 * chooser that samples before the check lands renders the ungated lineup, and re-reads when it
 * lands because it keys its producers on this flow (a flow, not a lazy val: the lazy
 * `npuCapableDevice` memo's "cannot change within a process" holds for Qualcomm and is amended
 * for MediaTek, which changes exactly once, from unknown to a verdict).
 *
 * **Who fills it.** `WhisperEverywhereApp.onCreate`, on MediaTek families only: the stored record
 * when [reusableOrNull] says it still answers for this ROM and this build, else one [probeNow] on a
 * background thread (the first walk holds bionic's loader lock for 169–239 ms, so never Main),
 * whose verdict is published here and persisted device-locally. Every other device never touches
 * this object's probe path, and `liblitertasr.so` is never loaded on them.
 *
 * **Pure, so it is executed.** The probe arrives as a function ([probeNow]'s `probe`), which is
 * what lets a JVM test drive every branch — the one call that loads `liblitertasr.so` is the
 * app's, pinned as source.
 */
object NpuApuDriverCheck {

    /**
     * The staged dispatch directory's name under `filesDir` — its ONE home. LiteRT scans this
     * directory for `libLiteRtDispatch_MediaTek.so`, and the probe walks its
     * `libneuron_adapter.so` as the adapter loader's fourth candidate, so the probe, the dispatch
     * staging (P2-6) and the LiteRT engine must all name one directory: every later reader takes
     * [dispatchDir], never a second spelling of the name.
     */
    const val DISPATCH_DIR_NAME: String = "litert_dispatch"

    /** `filesDir/litert_dispatch` — the one directory the probe, the stage and the engine share. */
    fun dispatchDir(filesDir: File): File = File(filesDir, DISPATCH_DIR_NAME)

    /** The prefix `LiteRtAsrNative.nativeProbe` puts before every refusal reason. */
    private const val PROBE_PREFIX: String = "probe: "

    private val _verdict = MutableStateFlow<NpuApuVerdict?>(null)

    /** This process's verdict — null (unknown) until the check has answered. */
    val verdict: StateFlow<NpuApuVerdict?> = _verdict.asStateFlow()

    /** The check answered: every reader of [verdict] (and every chooser keyed on it) moves. */
    fun publish(verdict: NpuApuVerdict) {
        _verdict.value = verdict
    }

    /**
     * The stored record when it still answers for this device: taken on this ROM
     * ([NpuApuVerdict.fingerprint]), by this build ([NpuApuVerdict.appBuild]), against this
     * family's major — else null, which means probe again. Pass and refusal alike are reused:
     * the answer cannot change until one of the three does.
     */
    fun reusableOrNull(
        stored: NpuApuVerdict?,
        fingerprint: String,
        appBuild: Int,
        wantMajor: Int,
    ): NpuApuVerdict? = stored?.takeIf {
        it.fingerprint == fingerprint && it.appBuild == appBuild && it.wantMajor == wantMajor
    }

    /**
     * Asks the driver once and answers the verdict. BLOCKING — the first walk holds bionic's
     * loader lock — so never on Main.
     *
     * [probe] is `LiteRtAsrNative.nativeProbe` in production, handed [dispatchDir] of [filesDir].
     * Anything it throws is a refusal too, named, never a crash: on a build whose CMake skipped
     * `liblitertasr.so` its first touch throws `UnsatisfiedLinkError` and every later one
     * `NoClassDefFoundError` — both mean the same thing here, no tier.
     *
     * WHAT IT ANSWERED AT P2-2, stated so nobody mistook it for a defect: a refusal. The dispatch
     * library was not packaged or staged, the manifest did not declare
     * `libneuronusdk_adapter.mtk.so`, and `libLiteRt.so` was not in `lib/` (all P2-6), so on a
     * MediaTek device the walk found no loadable adapter (`adapter-missing`), or — were one to
     * load — the runtime load failed (`runtime: …`).
     *
     * SINCE P2-6 the manifest declares the adapter and `libLiteRt.so` ships in `lib/`, so on the
     * Tab S10+ this CAN PASS — and a pass makes the tier offered there. SINCE P2-7 the selector
     * builds `LiteRtAsrEngine` for the row, so an offered tier arms on the APU. (Between the two,
     * a pass would have offered a tier the QNN engine then refused at `stage=skel` after the pair
     * had downloaded — the reason no build of that stretch went to a track.)
     */
    fun probeNow(
        probe: (dispatchDir: String, libDir: String, wantMajor: Int) -> String,
        filesDir: File,
        libDir: String,
        fingerprint: String,
        appBuild: Int,
        wantMajor: Int,
        clock: () -> Long,
    ): NpuApuVerdict {
        val answer = runCatching { probe(dispatchDir(filesDir).absolutePath, libDir, wantMajor) }
            .getOrElse { cause ->
                "${PROBE_PREFIX}runtime: ${cause.javaClass.simpleName}: ${cause.message}"
            }
        val refusal = if (answer.isEmpty()) {
            null
        } else {
            answer.removePrefix(PROBE_PREFIX).ifBlank { "unreadable" }
        }
        return NpuApuVerdict(fingerprint, appBuild, wantMajor, refusal, clock())
    }

    /**
     * Tests only: back to unknown. Production never forgets a verdict within a process — the
     * check answers once, and the process keeps that answer.
     */
    internal fun resetToUnknownForTest() {
        _verdict.value = null
    }
}
