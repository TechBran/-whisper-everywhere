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
 * WHAT A STORED DRIVER VERDICT ANSWERS FOR — the four facts it is reused under, and re-probed the
 * moment any one of them moves (design `docs/superpowers/specs/2026-09-24-mediatek-apu-tier-design.md`
 * §2.3 item 3, corrected at P2-2 and again at P2-7).
 *
 * @property fingerprint `Build.FINGERPRINT` — the key §2.3 names. An OTA moves it, and a verdict
 *   taken on another ROM's driver answers for nothing.
 * @property appBuild the app's `versionCode` — the answer depends on THIS BUILD as well as the ROM:
 *   the adapter loads only once the manifest declares it (`<uses-native-library>`, P2-6) and a
 *   pass needs `libLiteRt.so` packaged (P2-6), so a refusal stored by a build without them must not
 *   be inherited by the build that adds them.
 * @property appUpdatedAtMs `PackageInfo.lastUpdateTime` — when this install last changed (P2-7, the
 *   P2a review's L3). The versionCode alone does not separate two builds that share one: every
 *   debug and internal-sharing build installed on the tablet in a cycle carries the same code (112,
 *   then 113), so an `adapter-missing` stored by an earlier build of that code would be inherited by
 *   a later one that fixed it — and a stale REFUSAL never corrects itself (a stale pass does, at
 *   `nativeInit`, which re-judges the driver). Every install and every update moves this.
 * @property wantMajor the family's `neuronMajor` the driver is judged against — the verdict's
 *   meaning (a pass says "major == this").
 */
data class NpuApuKey(
    val fingerprint: String,
    val appBuild: Int,
    val appUpdatedAtMs: Long,
    val wantMajor: Int,
) {
    /**
     * The in-flight marker's one spelling (the P2a review's L4) — compared whole with the key of
     * the launch that finds it, never parsed. The three numbers follow the one free-form field, so
     * no two keys spell one marker.
     */
    fun marker(): String = "$fingerprint|$appBuild|$appUpdatedAtMs|$wantMajor"
}

/**
 * One answer of the MediaTek driver check (P2; design §2.3, the owner's "version checker there to
 * make sure we're hitting the right chip for the driver") — what `LiteRtAsrNative.nativeProbe`
 * said, through `LiteRtAsrEngine.probe`, stored so a later process can answer the capability
 * question without walking the Neuron adapter again.
 *
 * **What the record keeps, and what it cannot.** The probe answers exactly one thing to Kotlin:
 * `""` for a pass, or `"probe: <reason>"` for a refusal (`adapter-missing`, `adapter-<name>`,
 * `driver-version-unreadable`, `driver-major-<got>-want-<want>`, `runtime: <detail>`). The
 * driver's own name and version (`libneuronusdk_adapter.mtk.so 8.2.26`) and the APU device names
 * are printed natively, on the `apu:` line of `WE-DIAG` (§2.3 item 5), and are not in the probe's
 * return value — so they are not in this record, and a later process that reuses it reports the
 * verdict and when it was taken ([NpuDiag.apuVerdict]), not the driver line a second time. One
 * more reason is this app's own, never native's: [NpuApuDriverCheck.PROBE_CRASHED], recorded when
 * a walk started and never finished (L4).
 *
 * [fingerprint], [appBuild], [appUpdatedAtMs] and [wantMajor] are the record's [key]
 * ([NpuApuKey] documents each).
 *
 * @property refusal null for a pass; otherwise the probe's reason, the text after `probe: `.
 * @property probedAtMs the wall-clock time the answer was taken, for the diag line.
 */
data class NpuApuVerdict(
    val fingerprint: String,
    val appBuild: Int,
    val appUpdatedAtMs: Long,
    val wantMajor: Int,
    val refusal: String?,
    val probedAtMs: Long,
) {
    /** An answer taken under [key]. */
    constructor(key: NpuApuKey, refusal: String?, probedAtMs: Long) :
        this(key.fingerprint, key.appBuild, key.appUpdatedAtMs, key.wantMajor, refusal, probedAtMs)

    /** True exactly when the driver check passed. */
    val passed: Boolean get() = refusal == null

    /** What this answer was taken under — the one thing [NpuApuDriverCheck.reusableOrNull] compares. */
    val key: NpuApuKey get() = NpuApuKey(fingerprint, appBuild, appUpdatedAtMs, wantMajor)

    /** The one stored spelling: a single JSON object, so a write can never be torn in half. */
    fun encode(): String = buildJsonObject {
        put("format", FORMAT)
        put("fingerprint", fingerprint)
        put("appBuild", appBuild)
        put("appUpdatedAtMs", appUpdatedAtMs)
        put("wantMajor", wantMajor)
        put("refusal", refusal)
        put("probedAtMs", probedAtMs)
    }.toString()

    companion object {
        /**
         * The stored format this build writes and the only one it reads. 2 since P2-7 (the
         * install-update key, [appUpdatedAtMs]); a format-1 record — which cannot say which install
         * it was taken by — reads as no record, so the first launch of this build probes once.
         */
        const val FORMAT: Int = 2

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
            val appUpdatedAtMs = longOf(obj, "appUpdatedAtMs") ?: return null
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
            return NpuApuVerdict(fingerprint, appBuild, appUpdatedAtMs, wantMajor, refusal, probedAtMs)
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
 * Where the driver check keeps what outlives a process — the device-local store
 * (`PreferencesManager`), behind an interface so [NpuApuDriverCheck.settle] runs on a JVM against
 * a map. Device-local by that store's rule: a fact about THIS device's driver, never restored
 * onto another.
 */
interface NpuApuVerdictStore {

    /** The stored verdict, or null — none stored, or unreadable; both mean "probe again". */
    val npuApuVerdict: NpuApuVerdict?

    /**
     * The in-flight marker of a walk that started and has not finished, or null (L4) — the key's
     * [NpuApuKey.marker] with the walk's attempt ([NpuApuDriverCheck.inFlightMarker], since the P2c
     * review's later item). Present with no reusable verdict under the same key, it can mean one
     * thing only: the process died inside the walk — killed or crashed, which one marker cannot
     * tell apart, and two in a row can.
     */
    val npuApuProbeInFlight: String?

    /**
     * Marks a walk under [marker] as started — SYNCHRONOUSLY (`commit()`, never `apply()`): it
     * must be on disk before the walk begins, or the crash it exists to catch leaves no trace.
     * The GPU crash sentinel's rule (`GpuPolicy`), for the same reason.
     */
    fun markNpuApuProbeInFlight(marker: String)

    /** Stores [verdict] AND retires the in-flight marker, in ONE synchronous write. */
    fun recordNpuApuVerdict(verdict: NpuApuVerdict)

    /** Retires the in-flight marker and stores nothing — the walk that threw (L3). Synchronous. */
    fun clearNpuApuProbeInFlight()
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
 * **Who fills it.** `WhisperEverywhereApp`, on MediaTek families only: at `onCreate` the stored
 * record when [reusableOrNull] says it still answers for this ROM, this build, this install and
 * this major (published on Main, so a later launch's chooser never sees "unknown"), and then ONE
 * [settle] per process, off Main and outside `NativeComputeGate` — started by `onCreate` on a
 * thread of its own, and awaited by the service's boot prewarm, which settles it itself if that
 * thread has not (design §2.3 item 1). Every other device never touches this object's probe path,
 * and `liblitertasr.so` is never loaded on them.
 *
 * **Pure, so it is executed.** The probe arrives as a function and the store as an interface, which
 * is what lets a JVM test drive every branch of [settle] — the one call that loads
 * `liblitertasr.so` is `LiteRtAsrEngine.probe`, handed in by the app, pinned as source.
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

    /**
     * The refusal a launch records when it finds a walk that started under its own key and never
     * finished (the P2a review's L4) — this app's word, never native's. `runCatching` cannot catch
     * a native crash inside the adapter walk, so a ROM whose adapter crashes on load would
     * otherwise re-probe and die on every launch, taking the CPU tiers down with it; recorded
     * once, it is reused like any other refusal until the key moves (an update, an OTA). Printed
     * as `refuse(probe-crashed)` on the `apu:` line and `probe=fail:probe-crashed` on the offer
     * line.
     *
     * **Recorded on the SECOND find, not the first (the P2c review, a LATER item — kill vs
     * crash).** A process KILLED inside the ~200 ms walk — not crashed, killed — leaves the same
     * marker a crash does, and the walk runs exactly when a kill is likeliest: the first launch
     * after an update or an OTA, often a `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` start under
     * memory pressure. Recorded on the first find, one kill hid the tier until the next update.
     * So the marker carries an attempt counter ([inFlightMarker]): a launch that finds its own
     * key's marker from ONE unfinished walk re-walks once, and only a second consecutive
     * unfinished walk under the key ([CRASH_FINDS]) records this. The price, stated: a real crash
     * loop costs one extra crash before the guard holds.
     */
    const val PROBE_CRASHED: String = "probe-crashed"

    /**
     * How many unfinished walks under one key it takes to record [PROBE_CRASHED] — two: one may be
     * a kill, two in a row is the crash loop the guard exists for.
     */
    const val CRASH_FINDS: Int = 2

    /**
     * The in-flight marker written before walk number [attempt] (1, then 2) under [key]: the key's
     * one spelling ([NpuApuKey.marker]) and the attempt, `#`-joined after it, so the marker is
     * still compared whole and never parsed for the key.
     */
    fun inFlightMarker(key: NpuApuKey, attempt: Int): String = "${key.marker()}#$attempt"

    /**
     * How many walks under [key] started and never finished, as [inFlight] records it: 0 when there
     * is no marker or it is another key's; the marker's own attempt otherwise — and 1 for a marker
     * of this key whose attempt cannot be read, which is the reading that walks again rather than
     * the one that gives up.
     */
    fun unfinishedWalks(inFlight: String?, key: NpuApuKey): Int {
        if (inFlight == null) return 0
        val marker = key.marker()
        if (inFlight == marker) return 1
        if (!inFlight.startsWith("$marker#")) return 0
        return inFlight.removePrefix("$marker#").toIntOrNull()?.takeIf { it >= 1 } ?: 1
    }

    private val _verdict = MutableStateFlow<NpuApuVerdict?>(null)

    /** This process's verdict — null (unknown) until the check has answered. */
    val verdict: StateFlow<NpuApuVerdict?> = _verdict.asStateFlow()

    /** The check answered: every reader of [verdict] (and every chooser keyed on it) moves. */
    fun publish(verdict: NpuApuVerdict) {
        _verdict.value = verdict
    }

    /**
     * The stored record when it still answers for this device — taken under THIS [key]: this
     * ROM, this build, this install, this family's major — else null, which means probe again.
     * Pass and refusal alike are reused: the answer cannot change until one of the four does.
     */
    fun reusableOrNull(stored: NpuApuVerdict?, key: NpuApuKey): NpuApuVerdict? =
        stored?.takeIf { it.key == key }

    /**
     * Asks the driver once and answers the verdict. BLOCKING — the first walk holds bionic's
     * loader lock — so never on Main.
     *
     * [probe] is `LiteRtAsrEngine.probe` in production (`LiteRtAsrNative.nativeProbe`), handed
     * [dispatchDir] of [filesDir], [libDir] and the key's major. Anything it throws is a refusal
     * too, named, never a crash: on a build whose CMake skipped `liblitertasr.so` its first touch
     * throws `UnsatisfiedLinkError` and every later one `NoClassDefFoundError` — both mean the
     * same thing here, no tier in this process. ([settle] publishes such a refusal and never
     * stores it — L3.)
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
        key: NpuApuKey,
        clock: () -> Long,
    ): NpuApuVerdict {
        val answer = runCatching { probe(dispatchDir(filesDir).absolutePath, libDir, key.wantMajor) }
            .getOrElse { cause ->
                "${PROBE_PREFIX}runtime: ${cause.javaClass.simpleName}: ${cause.message}"
            }
        val refusal = if (answer.isEmpty()) {
            null
        } else {
            answer.removePrefix(PROBE_PREFIX).ifBlank { "unreadable" }
        }
        return NpuApuVerdict(key, refusal, clock())
    }

    /** What [settle] answered, and whether the answer was a stored one (the `source=` word). */
    data class Settled(val verdict: NpuApuVerdict, val reused: Boolean)

    /**
     * THE ONE SETTLE OF A PROCESS'S DRIVER VERDICT (design §2.3 items 1–3; the P2a review's L3 and
     * L4) — decided from what [store] holds, in this order:
     *
     *  1. **a stored verdict that still answers** ([reusableOrNull] under [key]) — reused, no walk;
     *  2. **the in-flight marker of THIS key, from [CRASH_FINDS] unfinished walks in a row, with no
     *     verdict** — the process died inside the walk twice running: a crash loop, most likely a
     *     native crash in the adapter walk that nothing in Kotlin can catch. [PROBE_CRASHED] is
     *     recorded and the adapter is never walked again under this key. ONE unfinished walk is not
     *     that: it may be a kill (see [PROBE_CRASHED]), so the launch that finds it walks again, as
     *     attempt 2 (the P2c review's later item);
     *  3. **otherwise one walk**: the marker ([inFlightMarker], carrying the attempt) is committed
     *     FIRST, synchronously; then [probeNow]; then the verdict is recorded and the marker retired
     *     in one synchronous write — unless the probe THREW (an out-of-memory, a missing
     *     `liblitertasr.so`), whose refusal is answered for this process but never stored: a stored
     *     refusal outlives its cause, and one that came from an exception is the likeliest to (L3).
     *     The marker is retired either way — a throw is not a crash — so the next launch probes
     *     again. **And on EVERY exit that is not a death** — including an `Error` thrown out of
     *     [probeNow] itself, after the marker is on disk — the marker is retired (`finally`), so the
     *     same process's retry (the boot prewarm settling after the launch thread's settle threw)
     *     never reads its own marker as a crash.
     *
     * BLOCKING when it walks (169–239 ms, bionic's loader lock held): never on Main, and never
     * inside `NativeComputeGate`, which would park a session's native work behind a driver walk.
     * Not concurrency-guarded: its caller holds one lock across it, so a process settles once.
     */
    fun settle(
        store: NpuApuVerdictStore,
        key: NpuApuKey,
        probe: (dispatchDir: String, libDir: String, wantMajor: Int) -> String,
        filesDir: File,
        libDir: String,
        clock: () -> Long,
    ): Settled {
        reusableOrNull(store.npuApuVerdict, key)?.let { return Settled(it, reused = true) }
        val unfinished = unfinishedWalks(store.npuApuProbeInFlight, key)
        if (unfinished >= CRASH_FINDS) {
            val crashed = NpuApuVerdict(key, PROBE_CRASHED, clock())
            store.recordNpuApuVerdict(crashed)
            return Settled(crashed, reused = false)
        }
        store.markNpuApuProbeInFlight(inFlightMarker(key, unfinished + 1))
        var retired = false
        try {
            var threw = false
            val verdict = probeNow(
                probe = { dispatchDir, lib, major ->
                    try {
                        probe(dispatchDir, lib, major)
                    } catch (cause: Throwable) {
                        threw = true
                        throw cause
                    }
                },
                filesDir = filesDir,
                libDir = libDir,
                key = key,
                clock = clock,
            )
            if (threw) store.clearNpuApuProbeInFlight() else store.recordNpuApuVerdict(verdict)
            retired = true
            return Settled(verdict, reused = false)
        } finally {
            // Not a death — the process is still here to run this — so not a crash: whatever threw,
            // the marker must not outlive this exit and be read as one by this process's retry.
            if (!retired) runCatching { store.clearNpuApuProbeInFlight() }
        }
    }

    /**
     * Tests only: back to unknown. Production never forgets a verdict within a process — the
     * check answers once, and the process keeps that answer.
     */
    internal fun resetToUnknownForTest() {
        _verdict.value = null
    }
}
