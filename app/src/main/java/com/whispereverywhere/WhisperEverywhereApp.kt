package com.whispereverywhere

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.whispereverywhere.data.local.PreferencesManager
import com.whispereverywhere.data.local.UsageTracker
import com.whispereverywhere.model.ModelInstallSignal
import com.whispereverywhere.model.WhisperCatalog
import com.whispereverywhere.model.WhisperModelManager
import com.whispereverywhere.npu.NpuDiag
import com.whispereverywhere.npu.NpuFleetCensus
import com.whispereverywhere.npu.NpuGate
import com.whispereverywhere.npu.NpuSocFamily
import com.whispereverywhere.transcription.NpuWhisperBackend

class WhisperEverywhereApp : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var usageTracker: UsageTracker
    lateinit var cloudCostTracker: com.whispereverywhere.data.local.CloudCostTracker
        private set

    /**
     * Process-lifetime model manager. Lazy so it is created on first use
     * (first recording / onboarding) after [preferencesManager] is initialized.
     */
    val whisperModelManager: WhisperModelManager by lazy {
        WhisperModelManager(this, preferencesManager)
    }

    /**
     * Whether this device's HARDWARE can run the 4.0 `npu` tier: the SoC gate, then the QNN probe.
     *
     * **`by lazy` because the probe dlopens `libQnnSystem.so` and `libQnnHtp.so`** — a real load
     * the first time, and a chooser that recomposes must not repeat it. Computed at most once per
     * process, and on the overwhelming majority of devices not at all past the first branch: the
     * SoC gate is checked first inside [NpuWhisperBackend.isTierAvailable] and a Tensor, an Exynos
     * or a MediaTek never reaches the dlopen.
     *
     * **Not Main-safe** — `QnnAsrNative`'s threading contract forbids Main for every entry point,
     * so every reader forces this off the main thread. [offeredNpuTierIds] is the reader that
     * matters and its callers do exactly that.
     *
     * The API-31 guard lives HERE rather than in `NpuGate`, which is a pure two-string table on
     * purpose: `minSdk` is 26, `SOC_MODEL` arrived in API 31, and the gate's null → deny handles
     * the whole pre-S population in one branch. Reading either field unguarded throws
     * `NoSuchFieldError` on every pre-S device that opens the chooser, so both reads are counted
     * by `ChooserSteerWiringPinTest` — the guarded form against the total, which is what makes a
     * second, unguarded read impossible to add quietly.
     *
     * This file is already where the NPU's process-scoped setup lives — see
     * [configureFastRpcLibraryPath], which is here for the same reason: it has to happen once, per
     * process, before anything touches the backend.
     */
    /**
     * The two `Build` SOC fields, each read in exactly ONE place so the API-31 guard has exactly
     * one site to be correct at. Two readers need them — the gate and its diagnostic — and two
     * inline guarded reads would be two chances to get it wrong, plus a pin that could no longer
     * count them.
     */
    private val npuSocModel: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null

    private val npuSocManufacturer: String?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null

    val npuCapableDevice: Boolean by lazy {
        NpuWhisperBackend.isTierAvailable(
            socModel = npuSocModel,
            socManufacturer = npuSocManufacturer,
            libDir = applicationInfo.nativeLibraryDir,
        )
    }

    /**
     * The census row this device's silicon resolves to, or null off the census (4.2 F2).
     *
     * Memoised for IDENTITY, not for cost — `NpuGate.familyFor` is a pure table lookup and
     * cannot dlopen, so unlike [npuCapableDevice] this is Main-safe. Everything per-family
     * downstream reads THIS one resolution: `NpuBackendSelector` hands it to the backend, which
     * stages the row's own `skelAsset`/`skelBytes`/`skelSha256` — so the skel a session stages
     * and the gate that offered the session can never come from two readings of the census.
     *
     * It reads the two guarded getters above and adds NO new SOC read site — the API-31 guard
     * keeps exactly one site per field, and `ChooserSteerWiringPinTest` proves it by the same
     * count it already runs. And it can never disagree with [npuCapableDevice]'s SoC half:
     * `isSocSupported` IS `familyFor != null` (F1's derivation), so a capable device always
     * resolves a row.
     */
    val npuSocFamily: NpuSocFamily? by lazy {
        NpuGate.familyFor(npuSocModel, npuSocManufacturer)
    }

    /**
     * The [ModelInstallSignal] generation the [NpuDiag.offer] line was last emitted at, or
     * [Int.MIN_VALUE] before the first emission.
     *
     * **Once per process per INSTALL EPOCH, not once per process (4.1 L8, L5 review I1).** The
     * flipped gate's first evaluation on a fresh device is its least informative state
     * (`probe=skipped installed=none`), and a plain once-per-process latch spent the line there:
     * a mid-process import followed by a probe FAILURE then logged nowhere until restart —
     * `runCatching` discards the reason, no session routes to the tier so `npu: unavailable`
     * never fires, and the only offer line on record said "nothing installed" about a device
     * that had the pair. Re-arming on the install signal makes the landmark self-consistent in
     * exactly the session that matters (L8's own device script imports mid-process and then
     * reads the line). Bounded: one extra line per install event, never one per chooser open —
     * the generation only moves when `notifyModelInstalled()` fires. The `adb push` dev route
     * does not bump the signal, so a pushed pair still refreshes the line only at restart; the
     * run-book says so where it prescribes that route.
     */
    private val npuOfferLoggedGeneration =
        java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)

    /**
     * The gated tiers a chooser may OFFER: every gated catalog tier whose own files are on disk,
     * provided this device's hardware can run the NPU class at all. Empty for every other device,
     * which is the answer the ungated lineup has always rendered.
     *
     * **The Boolean became a set in 4.1 (L5)** because two gated tiers (`npu`, `npu-turbo`) can
     * be independently installed and one bit cannot say which. The set feeds
     * `WhisperCatalog.pickableFor` / `ModelTierCopy.*For` directly, so each tier's card appears
     * exactly when ITS pair is on disk.
     *
     * **The installed half runs FIRST, and the probe is conditional on it** (Q7b NEW-1 / m3,
     * folded here because the shape changed anyway). The installed half is a handful of `File`
     * stats; [npuCapableDevice]'s first read dlopens ~7.9 MiB of QNN. The old conjunction forced
     * the dlopen on every 8 Gen 3 at bubble-service start whether or not a pair was ever
     * imported; now a device with no gated pair on disk never pays it. `capable == null` records
     * that the probe was NOT evaluated — which the offer line reports as `skipped` rather than
     * inventing a verdict.
     *
     * **The installed half is not redundant with the hardware half, and it is not Q8's job.** The
     * pairs are SAF-imported from a zip, never downloaded — `WhisperModel.url` on those tiers is
     * provenance, not a source — so a card offered before the assets exist would put a Download
     * button in front of a user for whom downloading cannot work. Offering a tier whose assets
     * cannot yet arrive is the one thing this gate exists to prevent.
     *
     * Re-read on every call rather than memoised: [npuCapableDevice] is a fact about the silicon
     * and cannot change within a process, but the files can — Q8's importer creates them while the
     * app is running, and a chooser that cached "not installed" would keep hiding the card the
     * import just earned. It is a few `File` stats, then a memoised read.
     *
     * **Never call from Main** — with anything installed it forces [npuCapableDevice], which
     * dlopens.
     *
     * **Emits one `WE-DIAG` line at its first evaluation per install epoch** (4.0, Q7b fix
     * round, I3; the tier-id set since 4.1 L5; re-armed on the install signal since L8 — see
     * [npuOfferLoggedGeneration]). Three predicates collapse into one answer here, so without it
     * a report of "the card never showed" cannot be told apart from "wrong SoC", "the QNN stack
     * did not load" and "nothing installed" — three different next actions. See [NpuDiag.offer].
     */
    fun offeredNpuTierIds(): Set<String> {
        val installed = WhisperCatalog.entries
            .filter { it.gated && whisperModelManager.isInstalled(it) }
            .map { it.id }
            .toSet()
        val capable: Boolean? = if (installed.isEmpty()) null else npuCapableDevice
        val offered: Set<String> = if (capable == true) installed else emptySet()
        // Once per install epoch — see [npuOfferLoggedGeneration]. MONOTONIC since 4.2 F6 (4.1
        // L8 review M3, folded): getAndSet could REGRESS the latch when two concurrent
        // evaluations held different generations — the older writer landing second re-armed the
        // line and bought a spurious extra emission; max() cannot go backwards, and concurrent
        // evaluations of the same generation still emit exactly once.
        val generation = ModelInstallSignal.generation.value
        if (npuOfferLoggedGeneration.getAndUpdate { maxOf(it, generation) } < generation) {
            // isSocSupported is called here for REPORTING only — it is a pure two-string table
            // lookup, it cannot dlopen, and the DECISION is `capable` above. The gate is not
            // re-run and is not duplicated: this only recovers which HALF of `capable` answered,
            // which the value itself has thrown away.
            Log.i(
                NpuDiag.TAG,
                NpuDiag.offer(
                    socModel = npuSocModel,
                    socSupported = NpuGate.isSocSupported(npuSocModel, npuSocManufacturer),
                    capable = capable,
                    installedTierIds = installed,
                ),
            )
        }
        return offered
    }

    // isNpuTierOffered() — the 4.0 Boolean view of the gate — is GONE (4.1 L8, its named
    // trigger): routing takes the set now (`NpuBackendSelector.routesToNpu(tierId, npuTierIds,
    // declinedTiers)`, fed by the service's own offeredNpuTierIds() memo), so the shim's one
    // consumer went with it. Nothing may re-grow a Boolean view: it is a second derivation of
    // the gate, and one bit cannot say WHICH of two independently-installed tiers is offered.

    /**
     * The gated tiers a chooser may offer to FETCH from Google Play (4.2 F6): every gated
     * catalog tier the DEVICE FAMILY has a measured artifact row for, minus the ones already
     * installed — [NpuFleetCensus.fetchableTierIds]'s executed truth table, bound to this
     * device. Empty off the census, empty when the probe fails: every non-capable device
     * answers empty, and since the chooser's set is offered UNION fetchable, empty means this
     * function cannot change that device's model step by a byte.
     *
     * **This is a CHOOSER fact — display and steer — and it must never route.** A fetchable
     * tier has nothing on disk to run; everything that routes a session (the service's memo,
     * the selector) keeps reading [offeredNpuTierIds], which is untouched — offered still
     * means installed AND capable. `ChooserSteerWiringPinTest` holds the routing files to
     * zero live reads of this set.
     *
     * **Never call from Main** — the same contract as [offeredNpuTierIds], pinned the same
     * way: on a census device the `capable` argument forces [npuCapableDevice], whose first
     * read dlopens two QNN libraries. The family conjunct is evaluated FIRST, so the whole
     * off-census fleet answers empty without ever paying the probe — the same cost shape the
     * offer gate's installed-first ordering bought.
     *
     * Re-read on every call rather than memoised, [offeredNpuTierIds]'s own reasoning: the
     * installed subtraction changes while the app runs (a pack lands, an import lands), and a
     * chooser that cached "fetchable" would keep a Get button on a tier the user just
     * installed. It is a handful of `File` stats, a table lookup, and a memoised probe read.
     */
    fun fetchableNpuTierIds(): Set<String> {
        val gated = WhisperCatalog.entries.filter { it.gated }
        return NpuFleetCensus.fetchableTierIds(
            family = npuSocFamily,
            capable = npuSocFamily != null && npuCapableDevice,
            gatedTierIds = gated.map { it.id }.toSet(),
            installedGatedIds = gated
                .filter { whisperModelManager.isInstalled(it) }
                .map { it.id }
                .toSet(),
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Must run before anything can dlopen the QNN backend.
        configureFastRpcLibraryPath()

        // Initialize managers
        preferencesManager = PreferencesManager(this)
        usageTracker = UsageTracker(this)
        cloudCostTracker = com.whispereverywhere.data.local.CloudCostTracker(this)

        // Settle any paired tier's interrupted import transaction NOW, not only from inside a
        // later import of the same tier (4.1 L6, Q8 M1 + m4). A process death between the park
        // and the rename used to leave the tier's primary under a `.prev` name: isInstalled read
        // false, the card silently vanished from the chooser, and nothing on screen explained
        // why — the one failure shape the import is written to never have. On a healthy launch
        // this is a handful of File stats; wrapped so a filesystem surprise can never cost the
        // app its launch (the same promise configureFastRpcLibraryPath documents).
        runCatching { whisperModelManager.reconcileNpuStagingDebris() }
            .onFailure { Log.w(NpuDiag.TAG, "npu: launch staging sweep failed", it) }

        // Create notification channel for foreground service
        createNotificationChannel()
    }

    /**
     * Points the FastRPC loader at the app's own files directory (4.0 NPU tier; the skel's real
     * home since 4.1 L6).
     *
     * The HTP backend (libQnnHtp.so) loads its DSP-side skel — libQnnHtpV75Skel.so — through the
     * FastRPC loader, and that loader does NOT search the app's native library directory. It
     * searches `ADSP_LIBRARY_PATH`, and only that. The variable has to be set before the backend
     * is ever dlopen()ed, which is why this lives in Application.onCreate rather than anywhere
     * near the NPU code itself.
     *
     * **The app's FILES directory comes first (4.0 Q8), and since 4.1 L6 it is where the skel
     * actually lives.** A jniLibs copy was provably unreachable under this app's
     * `extractNativeLibs="false"` packaging — the FastRPC loader needs a real file on disk and
     * `nativeLibraryDir` contains none (Q1's open concern for Q10a; the 4.0 tier armed only
     * because the owner `adb push`ed a skel by hand). L6's answer: `packaging.jniLibs` excludes
     * the skel, the `extractQnnSkel` Gradle task re-materialises it from the resolved AAR into
     * the APK's assets, and `NpuWhisperBackend.load` stages it into `filesDir` — this first
     * entry — before `nativeInit`. The entry costs nothing when the directory holds no skel, and
     * it is also what keeps the Q10a `adb push` dev route working unchanged.
     *
     * Then the app's native library directory, then the stock vendor locations, which is where a
     * device exposing its own HTP skels keeps them.
     *
     * Runs on EVERY device, including the overwhelming majority that will never arm the NPU tier:
     * it is two setenv calls and no I/O, and making it conditional would mean predicting NPU
     * support before [NpuGate] has run. A failure here is logged and swallowed — the CPU and GPU
     * tiers do not read this variable, and losing the NPU tier must never cost the app its launch.
     */
    private fun configureFastRpcLibraryPath() {
        val nativeLibDir = applicationInfo.nativeLibraryDir
        // Q8 M4 (4.1 L6): filesDir is read ABOVE the try. getFilesDir() throws
        // IllegalStateException, never ErrnoException, so inside the try it sat under a catch
        // that could not catch it while LOOKING covered by the "logged and swallowed" promise
        // above. Hoisted, the try covers exactly what its catch can catch — the two setenv
        // calls — and the filesDir read stands where its (theoretical, Context-is-broken)
        // failure is visibly an app-wide fact rather than an NPU-tier loss.
        val filesDirPath = filesDir.absolutePath
        try {
            // Semicolon-separated, unlike LD_LIBRARY_PATH — this is the FastRPC loader's own format.
            Os.setenv(
                "ADSP_LIBRARY_PATH",
                filesDirPath +
                    ";" + nativeLibDir +
                    ";/vendor/lib/rfsa/adsp" +
                    ";/vendor/dsp/cdsp" +
                    ";/system/lib/rfsa/adsp" +
                    ";/system/vendor/lib/rfsa/adsp" +
                    ";/dsp",
                true
            )
        } catch (e: ErrnoException) {
            Log.w(TAG, "ADSP_LIBRARY_PATH setenv failed; the NPU tier will not come up", e)
        }
        try {
            // Not strictly required (the linker resolves DT_NEEDED from the app lib dir already),
            // but it makes the environment self-describing in a bug report.
            val existing = Os.getenv("LD_LIBRARY_PATH")
            val merged =
                if (existing.isNullOrEmpty()) nativeLibDir else "$nativeLibDir:$existing"
            Os.setenv("LD_LIBRARY_PATH", merged, true)
        } catch (e: ErrnoException) {
            Log.w(TAG, "LD_LIBRARY_PATH setenv failed", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "WhisperEverywhereApp"

        const val NOTIFICATION_CHANNEL_ID = "whisper_everywhere_service"
        const val NOTIFICATION_ID = 1001

        @Volatile
        private var instance: WhisperEverywhereApp? = null

        fun getInstance(): WhisperEverywhereApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
}
