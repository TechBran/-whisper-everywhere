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
import com.whispereverywhere.npu.LiteRtAsrNative
import com.whispereverywhere.npu.NpuApuDriverCheck
import com.whispereverywhere.npu.NpuDiag
import com.whispereverywhere.npu.NpuFleetCensus
import com.whispereverywhere.npu.NpuGate
import com.whispereverywhere.npu.NpuRuntimeNeeds
import com.whispereverywhere.npu.NpuSocFamily
import com.whispereverywhere.npu.NpuVendor
import com.whispereverywhere.transcription.NpuWhisperBackend
import com.whispereverywhere.transcription.speakers.SpeakerSpikeStore

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
     * 4.4.0: the streaming-previewer pack (spec §6) — a sibling of the TTS voice, keyed by
     * LANGUAGE and carrying no tier identity, so it touches nothing [whisperModelManager] owns.
     * Pack-first since the 2026-09-10 amendment: the four files ride the `preview_en` Play asset
     * pack, and the commit-pinned download is the fallback for builds with no Play.
     */
    val streamingPackManager: com.whispereverywhere.transcription.stream.StreamingPackManager by lazy {
        com.whispereverywhere.transcription.stream.StreamingPackManager(this)
    }

    /**
     * 4.4.0 (Task 2b): the read-aloud voice's manager, PROCESS-scoped for two reasons Settings
     * cannot provide with a `remember { }` — the Play-refusal latch that moves the row from "ask
     * Play" to "download directly" must outlive the Compose tree, and [TtsPackController] has to
     * read the SAME instance the row reads or a refusal Play named would never reach the offer.
     * Lazy for [whisperModelManager]'s reason: created on first use, not at process start.
     */
    val ttsModelManager: com.whispereverywhere.tts.TtsModelManager by lazy {
        com.whispereverywhere.tts.TtsModelManager(this)
    }

    /**
     * Whether this device's HARDWARE can run the NPU tiers: the SoC gate, then the runtime's own
     * check — VENDOR-DISPATCHED since P2 (the MediaTek APU tier, design §2.2–2.3).
     *
     * **Qualcomm: the QNN probe, memoised in [qnnCapableDevice], exactly as before P2.** `by lazy`
     * because the probe dlopens `libQnnSystem.so` and `libQnnHtp.so` — a real load the first time,
     * and a chooser that recomposes must not repeat it. Computed at most once per process, and on
     * the overwhelming majority of devices not at all past the first branch: the SoC gate is
     * checked first inside [NpuWhisperBackend.isTierAvailable] and a Tensor or an Exynos never
     * reaches a dlopen. Every device that is not a MediaTek row — Qualcomm and off-census alike —
     * reads that memo, so their answer is the 4.15 answer by construction.
     *
     * **MediaTek: the driver check's STORED verdict, re-read on every call.** Its answer is filled
     * at process start, off Main, by [settleApuDriverVerdict] (design §2.3), and it moves exactly
     * once — from unknown to a verdict — so a memo would freeze "unknown" for the life of the
     * process. Re-reading costs a table lookup and a StateFlow read: [isTierAvailable]'s MediaTek
     * arm never dlopens. The 4.0 note that this "cannot change within a process" holds for
     * Qualcomm and is amended for MediaTek, whose choosers key their producers on
     * [NpuApuDriverCheck.verdict] to re-read when it lands.
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

    val npuCapableDevice: Boolean
        get() = if (npuSocFamily?.vendor == NpuVendor.MEDIATEK) npuTierAvailable() else qnnCapableDevice

    /**
     * The QNN answer's memo — [npuCapableDevice] for every device that is not a MediaTek row,
     * computed at most once per process because its first read dlopens two QNN libraries.
     */
    private val qnnCapableDevice: Boolean by lazy { npuTierAvailable() }

    /** The gate's one call site: both arms of [npuCapableDevice] ask it, one memoised, one fresh. */
    private fun npuTierAvailable(): Boolean = NpuWhisperBackend.isTierAvailable(
        socModel = npuSocModel,
        socManufacturer = npuSocManufacturer,
        libDir = applicationInfo.nativeLibraryDir,
    )

    /**
     * The census row this device's silicon resolves to, or null off the census (4.2 F2).
     *
     * Memoised for IDENTITY, not for cost — `NpuGate.familyFor` is a pure table lookup and
     * cannot dlopen, so unlike [npuCapableDevice] this is Main-safe. Everything per-family
     * downstream reads THIS one resolution: `NpuBackendSelector` hands it to the backend, whose
     * engine stages the row's own runtime needs (for QNN, the skel in its `runtime`) — so the
     * skel a session stages and the gate that offered the session can never come from two
     * readings of the census.
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
     * and cannot change within a process on Qualcomm (on MediaTek it moves once, from unknown to
     * the driver check's verdict — see its KDoc), but the files can — Q8's importer creates them
     * while the app is running, and a chooser that cached "not installed" would keep hiding the
     * card the import just earned. It is a few `File` stats, then a memoised (or stored) read.
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
     * way: on a Qualcomm census device the `capable` argument forces [npuCapableDevice], whose
     * first read dlopens two QNN libraries (a MediaTek device reads its stored driver verdict
     * instead, and since P2 [NpuFleetCensus.fetchableTierIds] offers only the family's own
     * `tiers` — turbo alone on a MediaTek row). The family conjunct is evaluated FIRST, so the whole
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

        // (P2, the MediaTek APU tier) THE DRIVER CHECK, settled once per process — on MediaTek
        // families only; every other device returns on its first line and never touches the
        // LiteRT seam. Here because npuCapableDevice's MediaTek half READS this verdict and must
        // never produce it on a chooser's path (design §2.3). Wrapped for the launch promise the
        // sweep above makes: losing the tier may never cost the app its launch.
        runCatching { settleApuDriverVerdict() }
            .onFailure { Log.w(NpuDiag.TAG, "apu: the driver check could not be started", it) }

        // (4.10.0) THE SPEAKER SPIKE'S DUMP IS PURGED AT EVERY LAUNCH, and this call is gated on
        // NOTHING — see SpeakerSpike.purge for the whole argument. The 4.10 spike wrote
        // per-segment fingerprints, and behind a flag file the controller touched with `adb` the
        // SPEECH AUDIO those fingerprints came from, into its own directory under both filesDir
        // and getExternalFilesDir. Its compile-time switch is off from 4.10.0/100 on, so nothing
        // in this build can write another one; but the 24 h sweep that deleted the old ones lived
        // inside the dump's own open(), on the way to writing a line, so the disarm compiled the
        // deleter away with the writer. A phone that ran a spike build off the internal track and
        // then took this update would otherwise keep that audio for good, and this app's whole
        // promise is that audio is never retained. The cost is two listFiles on a directory that
        // does not exist on any device that never ran a spike build, on a daemon thread of its
        // own (purgeAsync resolves the dirs there — this is cold-start time and
        // getExternalFilesDir touches the volume). Wrapped for the same reason the sweep above
        // is: a filesystem surprise may never cost the app its launch.
        runCatching { SpeakerSpikeStore.purgeAsync(this) }
            .onFailure { Log.w("WE-DIAG", "speaker dump: launch purge failed", it) }

        // Create notification channel for foreground service
        createNotificationChannel()
    }

    /**
     * THE MEDIATEK DRIVER CHECK'S VERDICT FOR THIS PROCESS (P2; design §2.3 items 1–3) — what
     * [npuCapableDevice]'s MediaTek half reads.
     *
     *  1. **Not a MediaTek row → nothing.** The first two lines return on every Qualcomm and
     *     off-census device, so their launch is exactly 4.15's: no thread, no LiteRT.
     *  2. **A stored verdict that still answers → published now.** Taken on this ROM
     *     (`Build.FINGERPRINT`), by this build, against this family's Neuron major — the
     *     [NpuApuDriverCheck.reusableOrNull] rule, whose keys say why each is one. The read is
     *     the device-local store `PreferencesManager` loaded at construction, so this is Main-safe
     *     and a later launch's chooser never sees "unknown" at all.
     *  3. **Otherwise one probe, on a daemon thread of its own** — `LiteRtAsrNative.nativeProbe`,
     *     whose first walk holds bionic's loader lock for 169–239 ms, so never Main — then the
     *     verdict is published (every chooser keyed on it re-reads) and persisted device-locally.
     *     Until it lands the verdict is unknown, which [NpuGate.runtimeAvailable] answers as
     *     not-yet-capable.
     *
     * The dispatch directory it hands the probe is [NpuApuDriverCheck.dispatchDir] — the name's one
     * home, which the dispatch staging and the LiteRT engine reuse. At P2-2 nothing was staged there
     * and the manifest did not declare the adapter, so on a MediaTek device the answer was a
     * refusal (`adapter-missing`, or `runtime: …`), which a later build re-probes rather than
     * inherits (the verdict is keyed on the build as well as the ROM). Since P2-6 (the adapter
     * declared, `libLiteRt.so` in `lib/`) the Tab S10+ can PASS, and since P2-7 the selector builds
     * the LiteRT engine for the row, so an offered tier arms on the APU.
     *
     * The service's boot prewarm running the probe when this has not (design §2.3 item 1) is
     * P2-7's; the `apu:` driver line is the native probe's own, and this adds one
     * [NpuDiag.apuVerdict] line saying which verdict the process holds and where it came from.
     */
    private fun settleApuDriverVerdict() {
        val family = npuSocFamily ?: return
        val needs = family.runtime as? NpuRuntimeNeeds.LiteRtMediatek ?: return
        val fingerprint = Build.FINGERPRINT
        val build = BuildConfig.VERSION_CODE
        val stored = NpuApuDriverCheck.reusableOrNull(
            preferencesManager.npuApuVerdict, fingerprint, build, needs.neuronMajor,
        )
        if (stored != null) {
            NpuApuDriverCheck.publish(stored)
            Log.i(NpuDiag.TAG, NpuDiag.apuVerdict(stored, reused = true))
            return
        }
        val files = filesDir
        val libDir = applicationInfo.nativeLibraryDir
        val prefs = preferencesManager
        val thread = Thread(
            {
                // Wrapped whole: an uncaught throw on this thread would take the PROCESS down
                // (Android's default handler), and the tier may never cost the app its life.
                // probeNow already turns anything the probe throws into a named refusal.
                runCatching {
                    val verdict = NpuApuDriverCheck.probeNow(
                        probe = { dispatchDir, lib, wantMajor ->
                            LiteRtAsrNative.nativeProbe(dispatchDir, lib, wantMajor)
                        },
                        filesDir = files,
                        libDir = libDir,
                        fingerprint = fingerprint,
                        appBuild = build,
                        wantMajor = needs.neuronMajor,
                        clock = System::currentTimeMillis,
                    )
                    NpuApuDriverCheck.publish(verdict)
                    Log.i(NpuDiag.TAG, NpuDiag.apuVerdict(verdict, reused = false))
                    runCatching { prefs.recordNpuApuVerdict(verdict) }
                        .onFailure { Log.w(NpuDiag.TAG, "apu: the verdict could not be stored", it) }
                }.onFailure { Log.w(NpuDiag.TAG, "apu: the driver check failed", it) }
            },
            "npu-apu-driver-check",
        )
        thread.isDaemon = true
        thread.start()
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

        // (4.15) THE MODEL-UPDATES CHANNEL — a second channel, not the service's, because the two
        // say different kinds of thing. The service channel is LOW on purpose: it carries the
        // bubble's standing foreground notice, which must never make a sound. The refresh notice
        // (BootReceiver, NpuRefreshNotice) is the one this app posts because something the user
        // relies on stopped working — their bubble did not come back after the update — so it is
        // DEFAULT, visible in the shade and the status bar, and the user can silence it on its own
        // without muting the service notice or the other way round.
        val modelUpdates = NotificationChannel(
            MODEL_UPDATES_CHANNEL_ID,
            getString(R.string.model_updates_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.model_updates_channel_description)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        notificationManager.createNotificationChannel(modelUpdates)
    }

    companion object {
        private const val TAG = "WhisperEverywhereApp"

        const val NOTIFICATION_CHANNEL_ID = "whisper_everywhere_service"
        const val NOTIFICATION_ID = 1001

        /** The refresh notice's channel (4.15) — see [createNotificationChannel]. */
        const val MODEL_UPDATES_CHANNEL_ID = "model_updates"

        @Volatile
        private var instance: WhisperEverywhereApp? = null

        fun getInstance(): WhisperEverywhereApp {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }
}
