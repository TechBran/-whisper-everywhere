// 4.2 F4: verifyNpuPacks parses each pack variant's metadata.json; Groovy's JsonSlurper is
// already on the buildscript classpath, so no new dependency rides in with the gate.
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.Properties
// 4.1 L6: extractQnnSkel reads the skel entry straight out of the resolved AAR. Imported here
// because `java` inside the script body resolves to the Gradle DSL accessor, not the package.
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing secrets live OUTSIDE the repo and OneDrive:
//  - keystore:  C:\Users\bastr\.keystores\whispereverywhere-release.jks
//  - passwords: <project root>\keystore.properties  (gitignored)
// Never hardcode credentials here — this file is tracked in a repo with a public remote.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// The HTP skels' generated-assets home (4.1 L6 — the I5 answer; fleet-wide at 4.2 F2).
// extractQnnSkel (below the android block) writes every census family's skel here out of the
// resolved AAR, each size- and digest-asserted; the dir is registered as an assets srcDir inside
// android{} and lives in the BUILD directory — outside the repo, so the proprietary blobs
// structurally cannot be committed.
val qnnSkelAssetDir = layout.buildDirectory.dir("generated/qnnSkel/assets")

// (P2-6, the MediaTek APU tier) THE LITERT RUNTIME'S TWO GENERATED SOURCE DIRS, declared here for
// the reason qnnSkelAssetDir is: the source set inside android{} reads them. libLiteRt.so lands in
// a generated JNILIBS dir (extractLiteRtRuntime, below the android block) and ships in
// lib/arm64-v8a/ like every library the app loads by name; the MediaTek dispatch lands in a
// generated ASSETS dir (extractLiteRtDispatch) because it must be staged as a real file into the
// directory LiteRT scans, and because AGP's strip rewrites a lib/ copy (same length, other bytes)
// while the stage verifies the release zip's own. Both in the BUILD directory, outside the repo.
val litertJniLibDir = layout.buildDirectory.dir("generated/litertRuntime/jniLibs")
val litertDispatchAssetDir = layout.buildDirectory.dir("generated/litertDispatch/assets")

// ============================ PER-MACHINE TOOLCHAIN LOCATIONS ============================
// Resolved rather than hardcoded, since the 2026-09-22 Linux port. Three of these paths were
// absolute Windows paths, and `file()` resolves a RELATIVE path against the project directory
// — so "C:/Users/..." on Linux did not fail loudly, it silently asked for
// `<project>/app/C:/Users/...`. Each resolver takes an explicit override first, then the known
// machines, so a new machine needs a property rather than an edit.

/**
 * A Python 3 interpreter. Needed twice: by CMake (which otherwise picks the Windows-Store alias
 * stub and fails) and by [fetchQnnHeaders]. Override with `-Ppython3=/path/to/python3`.
 * Falls back to the bare name so PATH resolution applies on a machine neither listed.
 */
val python3Executable: String =
    (findProperty("python3") as String?)
        ?: sequenceOf(
            "C:/Users/bastr/AppData/Local/Programs/Python/Python313/python.exe", // Windows dev box
            "/usr/bin/python3",                                                   // MS-02 Ultra
        ).firstOrNull { File(it).canExecute() }
        ?: "python3"

/**
 * The Khronos OpenCL headers plus the aarch64 Android link stub that `GGML_OPENCL=ON` needs
 * (OpenCL-Headers 2024.10.24, Apache-2.0; the real `libOpenCL.so` comes from the device vendor at
 * runtime). Override with `-PopenclRoot=/path/to/opencl`.
 *
 * NULL is a legitimate answer and must stay one: a checkout that only runs the JVM tests has no
 * business needing OpenCL headers, and failing here would block them. What it must NOT do is
 * quietly produce a RELEASE artifact with a backend missing — so [requireOpenClForRelease] below
 * makes the release bundle refuse instead.
 */
val hasOpenCl: (File) -> Boolean = {
    File(it, "include/CL/cl.h").isFile && File(it, "lib/libOpenCL.so").isFile
}

val openClRoot: File? =
    // The predicate belongs INSIDE firstOrNull. A bare firstOrNull() returns the first element
    // unconditionally, so the trailing takeIf only ever examined the Windows candidate and this
    // answered null on Linux even with the headers in place — caught by requireOpenClForRelease
    // on the first release build (2026-09-22), which is the whole reason that guard exists.
    (findProperty("openclRoot") as String?)?.let(::File)?.takeIf(hasOpenCl)
        ?: sequenceOf(
            File("D:/gemma-inference/tools/opencl"),                        // Windows dev box
            File(System.getProperty("user.home"), "toolchains/opencl"),     // MS-02 Ultra
        ).firstOrNull(hasOpenCl)

android {
    namespace = "com.whispereverywhere"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            // Keep the ninja build tree OUT of OneDrive: the default .cxx staging dir lives in
            // the (OneDrive-synced) project folder, and OneDrive's placeholder/reparse handling
            // corrupts hard-linked build outputs ("Cannot snapshot ... not a regular file").
            //
            // CONDITIONAL SINCE THE LINUX PORT (2026-09-22), and it has to be. `file()` resolves
            // a relative path against the project directory, and "C:/Users/..." IS relative
            // anywhere but Windows — so on Linux this line silently asked for
            // `<project>/app/C:/Users/bastr/.androidbuild/...` rather than failing. The root
            // build.gradle.kts guards its own relocation on `localBuildRoot.isDirectory` for the
            // same reason; this is that guard, applied to the half that was missing it.
            // Follows the same `-PlocalBuildRoot` a worktree passes to the root script (2026-09-24).
            val oneDriveEscape = File((providers.gradleProperty("localBuildRoot").orNull ?: "C:/Users/bastr/.androidbuild/WhisperEverywhere") + "/cxx-staging")
            if (oneDriveEscape.parentFile?.isDirectory == true) {
                buildStagingDirectory = oneDriveEscape
            }
        }
    }

    defaultConfig {
        applicationId = "com.whispereverywhere"
        minSdk = 26
        targetSdk = 36
        versionCode = 112
        versionName = "4.15.1"  // THE AI-CHIP ENGINE GETS A SEAM, AND THE REFRESH NOTICE COMES DOWN. A PATCH on 4.15.0, and the REGRESSION GATE for the MediaTek tier that follows it (owner ruling 2026-09-24: "you can seam it up for 112"). The QNN path is now driven only through the NpuAsrEngine seam: QnnAsrEngine owns what was QNN-shaped in the backend - the skel stage, the input-quant read (now once per arm rather than once per segment; it reads the same cached values), the mel quantisation and the DEBUG mel probe - while NpuWhisperBackend keeps the policy body and calls the engine through ten members; the fourteen decline words phones print as stage=<word> are a closed enum (NpuStage, plus the reserved "dispatch"), and qnn_asr.cpp is BYTE-IDENTICAL. Three thousand four hundred tests, twelve deliberate mutations against the re-pointed pins and a read-only review of the old and new backends call by call say no Qualcomm device shows a difference - and this build exists to PROVE that on the internal track: the owner's canary and jfk clips on the Z Fold6 and the S23 Ultra, their diag lines and transcripts equal to a capture taken on 4.15.0 (docs/superpowers/plans/2026-09-24-mediatek-apu-tier-plan.md P1a-6), before any MediaTek code reaches a track. Second: the "faster version" notification (4.15.0) is taken down the moment the pair it asked for lands - on 111 the owner re-downloaded through the app's own gate and the notice stood in his shade for hours afterwards, because it is AUTO_CANCEL and only a tap cleared it; the shared finalise now cancels it on the line that clears the re-download record, only when the record it announced was the one that cleared. Also in the APK, unreferenced by any product code path: liblitertasr.so, the LiteRT 2.1.1 engine for MediaTek APUs, measured in the product's shape on the owner's Tab S10+ (docs/measurements/2026-09-24-tab-apu-turbo-encoder.md §6: encode 1.72 s, ~30 ms a token, a 2.8-3.6 s cold arm, transcripts identical to the reference) - the tier itself (the mt6989 census row, the two pack modules, the runtime, the copy) ships as 4.16.0 at the next free code. versionCode 112 because 111 = 4.15.0 went to the internal track on 2026-09-24 (the owner's upload; the Fold6 measured the v0.63.0 pack at 260 ms per encode against 4.14's 1,752-1,874, docs/measurements/2026-09-24-qnn-250-8gen1-refresh.md §12), so the code is spent; the name takes a PATCH because nothing a user is offered changes. Previous: THE AI CHIP GETS ITS FIRST REBUILD, AND THE 8 GEN 1 GETS THE AI CHIP. Three owner rulings of 2026-09-24 in one release. ONE: the QNN runtime moves from 2.49.0 to 2.50.0, and the QAIRT headers move with it to the same build, v2.50.0.260828221209, which is also the build the new packs were compiled with - the runtime, the headers and the blobs now name one QAIRT build, where 4.0-4.14 read QAIRT 2.45 blobs under a 2.49 runtime. Every skel is a new blob at 2.50 and was re-measured out of the Maven AAR. TWO: every family's packs move to Qualcomm AI Hub v0.63.0. It is a REBUILD, not a re-release: no 0.62.2 digest reproduces, every encoder is 11.5-22.1% smaller, every decoder within 0.06%, and the graph IO census is unchanged on all twelve packs; AI Hub's own profiles show the turbo encoder 4-7x faster on every family (the vendor's numbers - no device here has run a v0.63.0 pair yet). A phone that already holds a pair sees its NPU tier as not installed after this update (the old encoder is 13% over the new one's size gate) until it fetches the new pack. THREE: the sixth census family, 8gen1 - SM8450, the Galaxy S22, S22+, S22 Ultra, Tab S8, S8+, S8 Ultra and the S23 FE's Snapdragon build, 67 rows in Play's catalog - on Qualcomm's own w8a16 packs for HTP v69 / soc_model 36, published for the first time at v0.63.0; no v69 binary has executed in this program yet, in an AI Hub job of ours or in-app (Qualcomm's own pipeline profiled a w8a16 turbo on a hosted Galaxy S22; that is the vendor's run, not ours). versionCode 111 because 4.14.2 is 110 - the bubble branch, merged to main on 2026-09-24 by owner ruling, and on the internal track - so 111 is the next code; the minor moves because a silicon generation gains the best tier, as it did for 4.12.0 and 4.13.0. The bundle is estimated at ~7.5 GB against 106's 6.63 GB, unverified against Play's limits. Previous: THE PANEL DEFAULTS TO 80%. Owner, testing 109 on the device: "80% is what I'm testing at. And that seems like about the best balance" (Spring green confirmed as the committed default). 109 may already be on the internal track, so this takes 110 rather than risk a refused code. Previous: THE CORNER CONTROLS GET THEIR OWN LITTLE BUBBLES, AND NEW DEFAULTS. Owner rulings on 108 (2026-09-22): the mute mic and resize arrow each sit on a small disc and the committed text flows around them to the top with no header band (CornerWrap); the waveform tab loses its 12dp neck; the defaults become Spring #69F0AE committed text on a 75% panel. 108 went to the internal track, so the code is spent; the patch moves because this refines 4.14.0's own feature. Previous: THE BUBBLE JOINS THE WINDOW, AND A MUTE. Owner ruling 2026-09-22: the waveform bubble connects to the transcript window "so it looks like one unified piece" (a tab under the window, keeping the rippling rim), its black background mimics the window's opacity setting, and a mic toggle top-left of the window mutes everything going into the app. Muting is silence at the head of onAudioChunk (CaptureMute): what was said before the tap still commits, nothing muted reaches whisper, live words, speakers, a cloud provider or the waveform, and every session starts unmuted. 107 (4.13.0, the S25/S26 generations) went to the internal track the same day, so the code is spent; the minor moves because mute is a new capability. Previous: THE GALAXY S25 AND S26 GENERATIONS GET THE AI CHIP - and with them every Snapdragon 8 Elite and 8 Elite Gen 5 phone. 106 went to the internal track on 2026-09-22, so the code is spent. The two census rows for these chips named "SM8750-AC" and "SM8850-AD", which are Qualcomm AI Hub chipset ALIASES, and no device reports either: the 2026-09-22 device census (docs/measurements/2026-09-22-npu-device-census.md) read plain SM8750 on every S25, S25+, S25 Ultra, S25 Edge and Z Fold7 it found, plain SM8850 on every S26, S26 Ultra and Z Fold8, and Play's own device catalog - 25,016 rows, the list Play targets against - holds ZERO suffixed strings. So from 4.2 to 4.12 both rows matched nothing: NpuGate denied the phones those packs were built for, and Play delivered their variants to no one. Build.SOC_MODEL is copied from the die's fused chip id, so the plain string cannot tell a Galaxy bin from a OnePlus 13's; the owner ruled to admit them all, on the same die answering to the same QNN soc_model (69, 87) at the same HTP version, with the loud CPU fallback behind any context that still refuses. Neither family has run on a device yet. The census's maintenance rule 1 said the opposite - "the 8 Elite for Galaxy reports SM8750-AC, never plain SM8750" - and is rewritten: a string is what a DEVICE REPORTS. The minor moves, as 4.12.0's did, because a silicon generation gaining the best tier is a new capability. Previous: THE 8 GEN 2 GETS THE AI CHIP - the S23, S23+ and S23 Ultra, and every other SM8550 phone. NpuFleetCensus.CPU_BY_CENSUS had carried this silicon as "no published w8a16 package" since 2026-08-29 and named its own reopening condition: an 8 Gen 2 device turning up for the experiment. Two things then changed on 2026-09-22. The owner's S23 Ultra turned up, and the QUESTION improved. The cross-load that ledger rejected was the 7 Gen 4's binary - right HTP major, wrong soc_model (86) - and AI Hub v0.62.2 publishes a qcs8550-proxy package whose metadata reads soc_model 43 / htp_version 73. 43 is the SM8550's OWN number, so the objection did not apply to it. DEVICE-EXECUTED BEFORE IT WAS WRITTEN DOWN (docs/measurements/2026-09-22-s23-8gen2-qcs8550-spike.md): `npu: offer soc=SM8550:pass probe=pass`, then encode p50 2,472 ms and decode p50 452 across 12 chunks - 37% of the 8 s commit floor, worst 45%, at 1.40x the Fold6's cost one HTP generation back. Sentence-granularity windows (p50 3, max 8, where 4.10.1 returned 1 always), a speaker change landing INSIDE one chunk, and live words all worked. Owner: "it felt really fast." qcs8550 is the FIFTH census family - packGroup soc_qcs8550, HTP v73, socModels {SM8550, SM8550-AC}, sharing 7 Gen 4's libQnnHtpV73Skel.so byte for byte - and it carries BOTH tiers like its four siblings, because the owner's ruling was that the other models "stay hidden, just like we do on the CPU tier", and on the CPU tier hidden rungs stay catalogued. WhisperCatalog.ONE_TIER_ID already narrows a capable device's chooser to npu-turbo alone, so this family inherits turbo-only PRESENTATION by existing; an earlier cut deleted the small artifact to make it undeliverable and WhisperCatalogHelpersTest refused it in so many words. AND THE CENSUS IS RE-MEASURED AT v0.62.2, which is what made a fifth family possible: build_asset_packs.py asserts ONE release string for the whole census, and four guards refused the new bytes in turn - the release string, the zip-length pins, the HEAD Last-Modified day, then four tests holding August's values. Each moved deliberately. The finding was that nothing moved: all sixteen existing binary digests reproduce the 2026-08-30 measurement exactly, the four small zips too, and only the four TURBO zip lengths changed - one byte each, archive wrapper only. 0.62.2 advertises QAIRT 2.45.0 and ships the same artifacts, so the graphs the decoder is built around did not move; the io-census gate proved it pack by pack. The packs are therefore REPRODUCIBLE FROM THE REPO again on any machine, which they had not been since the vendor re-released. TWO ASSERTIONS THAT WERE TRUE ONLY BY COINCIDENCE are now stated properly: four families had four distinct HTP versions, so "one skel per family" and "one skel per architecture" were the same sentence, and the 8 Gen 2 is v73 exactly as the 7 Gen 4 is. The distinctness test asserts the BIJECTION (families sharing a skel must share an architecture, and no skel may appear under two - a v73 blob staged for a v75 device is the real hazard), and the skel-extraction table asserts one row per ARCHITECTURE while the per-family loop still proves every family's skel is present. NpuGateTest's canonical "most likely false allow" was SM8550 itself; that part now correctly allows, so the role passes to the 8+ Gen 1 with the 8 Gen 2 asserted as a TRUE allow beside it. THE BUNDLE GREW TO ~6.63 GB from 105's 5.48, and one 8 Gen 2 downloads 1.07 GB of it - the turbo variant, under the per-pack ceiling. Whether the TOTAL crosses a Play limit is UNVERIFIED and is the one thing to check before an upload; if it does, the lever is delivery scope rather than the census, since no device needs more than one variant of any pack and 5.43 GB of the bundle is npu_turbo's six variants side by side. 105 = 4.11.3 IS IN PRODUCTION (promoted 2026-09-22 after the owner tested the internal track), so it is spent twice over, and the name takes a MINOR because a whole silicon generation gaining the best tier is a new capability rather than a fix to one. Previous: THE PANEL FOLLOWS THE BOTTOM AGAIN, AND STOPS CUTTING TEXT OFF (owner, 2026-09-20, two reports in one). FIRST: "after a while ... the scrolling of the window will just drift off and then not follow the bottom. If the user scrolls to the bottom, then the bottom should be locked to automatically scrolling on committed text. But if you scroll away from the bottom ... stay where you are. But if you go back down to the bottom, then you should definitely be carried with the committed text." The cause was a read taken across a text change. Through 4.11.2 the panel answered "is the reader at the bottom?" fresh on every repaint: read scrollY, compare it against the layout, apply the verdict in a deferred post. But a fixed-size TextView rebuilds its layout SYNCHRONOUSLY inside setText while that correction waits for the post, and _preview is a StateFlow with four repaint triggers collected by collectLatest - which cancels the coroutine body but not a post already on the queue. A second repaint arriving in between therefore read the OLD offset against the NEW taller layout, concluded the reader had left, and the panel stopped following for the rest of the session. A relabel landing right after a commit is enough, which is why it took a while and never recovered. 105 replaces the derivation with PanelFollowLatch: one boolean that ONLY A FINGER may write, armed once per session in showSessionPreview and otherwise moved only when a gesture lands. Nothing is computed across a text change, so there is no window for two sources of truth to disagree. The scrubber gained an onTargetScrolled hook because View.setOnScrollChangeListener is a SINGLE-SLOT setter it already owns on both transcript views - a second owner would have silently unhooked the thumb - and the service fences its own scroll out of that report through scrollPanelTo, the one place in the service that scrolls the panel. Following now also re-runs on a bounds change, so the lock survives a resize or a rotation and not only new words. A REVIEW CAUGHT A REGRESSION IN THE FIRST CUT OF THAT AND IT IS FIXED HERE: the deleted followScrollY had clamped a non-following reader's offset into range on every repaint, and TextView clamps nothing itself - so a reader who scrolled up and then grew the panel, or whose content shrank when an ordinary remap collapsed paragraph breaks and labels, was left drawing blank space with the scrubber hidden and nothing to heal it until the next session. targetScrollY now returns the end for a stranded view and null otherwise, so a reader who is where they want to be is still never written to. SECOND: "it should be a PIN only - anything done, processing wise, should just be a PIN only. That way we can show the entire window of text of what you have transcribed already ... They're gonna think the app is broken if things get cut off." TranscriptSink.PREVIEW_CAP_CHARS was 4,000, then 20,000 at 4.10.0 after he watched the start of a session vanish; it is now SpeakerLabels.NO_CAP and the panel renders the WHOLE session, because any ceiling is only a session length at which the same report comes back. The record never needed it - the file and the runs were always whole. What grows is stated rather than discovered: the incremental tail buffer is now a second full copy of the text beside runs (~180 KB of UTF-16 for two hours), and TWO O(session) passes land per commit - the sink building the string and the StaticLayout the panel measures for it, the latter on Main. A `panel: chars= renderMs= setTextMs=` line reports both halves past 20,000 characters, through the native export so a Play build still shows it; if it ever bites, the answer is incremental rendering rather than the ceiling back. 104 IS SPENT - built, sideloaded onto the Tab S10+ and downloaded by the owner - so the code takes the next integer; the name takes a PATCH because both halves are fixes to behaviour 4.11.2 already had. Previous: A SESSION MAY HOLD SIXTEEN SPEAKERS, NOT EIGHT (owner ruling 2026-09-20, after testing 102 on both devices: "certain podcasts will have, like, almost ten people. And I did that intentionally, and that part did work pretty well"). 8 was the spec's cap and it had never been tested against his own material; his material has ten voices in it. 102 answered those sessions correctly only BECAUSE nothing capped the retrospective pass's answer - it returned 10 and 11 clusters where it found them - so 103, which made the two halves agree at 8, would have merged the ninth and tenth people into whoever they most resembled and taken away the result he had just confirmed works. 104 raises SpeakerTracker.MAX_SPEAKERS to 16, which is one number and reaches everything: the reclusterer is handed it by the assigner, the online path opens new voices under it, and the trim added in 103 becomes a backstop that podcast material never reaches. WHY 16 AND NOT 32: cost is not what sets it - each extra live voice is RECENT_K vectors of 192 floats, about 3.8 KB, and five more dot products per window against a ~320 ms embedding, which is unmeasurable - what sets it is that a phantom speaker needs MIN_CLUSTER_SECONDS of misattributed speech to earn a label, and a higher cap leaves more room for one to appear on music or crowd noise. 16 is clear headroom over ten with that risk still bounded. Nothing else moves, and the two 103 fixes stand: the cap still binds the ANSWER rather than only the seeds, and a cluster absorbed into another still contributes its LABEL and never its VOICE. The cap tests are now written against MAX_SPEAKERS + 1 rather than a fixed nine dimensions, so the number can move again without rewriting them. 103 IS SPENT - it was built, sideloaded onto the Tab S10+ at 02:38 and downloaded by the owner - so the code takes the next integer; the name takes a PATCH because sixteen speakers is the same capability at a different number, and because 4.11.1 shipped a regression against 102 on his own material that this removes. Previous: THE SPEAKER CAP BINDS THE ANSWER, NOT ONLY THE SEEDS (Tab S10+, 4.11.0/102, 2026-09-20 01:35: `speaker-recluster: n=505 clusters=11 confirmed=11`). The retrospective pass capped its SEEDS at 600 and said so in its own KDoc; nothing capped its ANSWER. SpeakerAssigner reseeds the tracker with one LIVE voice per cluster, and the online path may open a new speaker only while fewer than SpeakerTracker.MAX_SPEAKERS (8) are live - so a pass answering eleven does not merely overcount, it permanently retires that tracker's ability to find anyone new for the rest of the session: every later unheard voice is handed to the closest voice it already knows and, per that line's own comment, teaches it nothing. The tracker's own corrective merge in endChunk is inert at the same moment, because it skips CONFIRMED voices and a reseed marks every voice confirmed. Retrospective labelling still recovers - recluster reads the stored fingerprint vectors and never tracker state - so what degrades is the LIVE label between passes, which is also the one that drives paragraph breaks in text typed into another app. 103 makes the cap a parameter of recluster, defaulted to SpeakerTracker.MAX_SPEAKERS and passed by the assigner as the tracker's OWN maxSpeakers, so the two halves cannot disagree about how many people a session may hold. Over the cap the speakers who SPOKE LONGEST keep their identity and the rest fall into the absorption loop every sub-bar cluster already goes through, so no window is dropped and no label is lost. WHAT THAT EARNS, STATED EXACTLY, because the neighbouring claim is easy to make and wrong: AT the cap the opening guard is false too, so capping to 8 does NOT give the tracker back the ability to open a ninth voice. What it gives is that the tracker's live count honours the bound it documents about itself, that the label space handed to the panel stops drifting upward, and that because every pass re-decides WHICH speakers survive, a person who out-speaks the weakest survivor takes that slot at the next pass instead of being locked out for the session. AND WHAT IT COSTS, on material the cap is genuinely too small for: nine real people used to come back as nine clusters, all separated, with only the online path jammed - now the ninth is merged into whoever they most resemble. That is the cap's price rather than the trim's, and MAX_SPEAKERS is the ONE place to change it; the session that prompted this held one or two real voices and answered eleven, which is the over-split the other way. ONE MORE DEFECT CAME WITH IT AND IS FIXED IN THE SAME BREATH: Cluster.longest is the seed set SpeakerTracker.reseed rebuilds a live voice from, and it was computed AFTER absorption - but absorption is by construction a merge of two things the pass just proved are NOT one voice (step 3 already merged every pair reaching RECLUSTER_SIM 0.30), so an absorbed cluster's windows were becoming the survivor's own identity evidence. A sub-bar leftover is short and rarely won the duration sort; a cluster the CAP trims cleared the 6 s mass bar and lost only on relative mass, so its long windows would routinely have taken the survivor's seed slots and made the tracker answer ~1.0 to the wrong person - credit would then have evicted the survivor from its own id. Identity is now snapshotted from a cluster's OWN pre-absorption windows, which also keeps the file's own rule that a 1.0 s window is labelled and never a voter. The absorbed windows still take the survivor's LABEL; that disposal rule is unchanged. Nothing else moves: 102's timing layer, its text guarantees and every acceptance row stand. THE TABLET PASSED AO11 on the way to finding this: one voice stayed one speaker across nine chunks, so the bisection invented nobody; windows ran p50 2.2 s and p95 3.5 s with only 4 of 1,240 over the 4.0 s cut; embedMs was p50 320 and worst 600 against the 3,000 ms fence; and four stop taps drained in 178-448 ms, all settled (docs/measurements/2026-09-20-411-timing-layer-field.md). 102 IS SPENT - it was sideloaded onto the Tab S10+ at 2026-09-20 00:55 and the five sessions this fix comes from were run on it - so the code takes the next integer and the name takes a PATCH, because a cap that binds is a fix to 4.11.0 rather than a capability 4.11.0 lacked. Previous: THE TIMING LAYER: A SPEAKER CHANGE CAN LAND INSIDE A CHUNK (owner, 2026-09-19, his controlled 3 min 20 s run of 4.10.1/101 on the Z Fold6 - "after about two minutes they just stopped, and everything just becomes one speaker"). The tracker was never wrong in that session: ids 2 and 3 alternated to the end and the reclusterer found three confirmed clusters. What collapsed was the GRANULARITY AT WHICH TEXT COULD CARRY A LABEL. Hard-cut media has no pauses, so the standalone VAD 101 gave the NPU tier returned ONE 9-15 s segment per chunk, so windows=1, so the chunk-level rule gave fifteen seconds of two people one name. 102 retires that ceiling on both tiers, by making every tier say WHEN it said each piece of text. ON THE CPU TIERS (small/medium/turbo Q8) whisper.cpp is asked for `params.token_timestamps` and the JNI exports a `[t0cs, t1cs, byteStart, byteEnd]` quad PER TOKEN beside the geometry it already exported, so a fingerprint window may now end at a WORD: any stretch still spanning 4.0 s (`2 * LONG_SEGMENT_SECONDS`) is bisected at the nearest token edge, recursively, and the segment's text is cut with it so the label and the words move together. ON THE NPU TIER the QNN decoder stops being told `<|notimestamps|>` and stops having the timestamp range masked - it always had those 1,501 slots in its vocabulary - and `NpuSentences` parses the emitted pairs into sentence bounds, so the VAD route makes ONE WINDOW PER SENTENCE instead of one per chunk: session 7's `segs=1 windows=1` becomes `segs=1 windows=4`, which is the single line on the device that says the fix is live. Nothing downstream moved - SpeakerTracker, SpeakerReclusterer, SpeakerRuns, SpeakerLabels and TranscriptSink all key on window indices, which is what made the layer affordable. THE TEXT GUARANTEE IS KEPT ON CPU AND KNOWINGLY TRADED ON NPU, and the asymmetry is deliberate: on CPU timing is additive (no logit and no segmentation changes, `result += seg` stays the sole author of the returned bytes, a segment whose token walk cannot reproduce its own text drops its quads rather than rebuild them, and SegmentGeometryPinTest fails the build if that stops being true), while on NPU dropping `<|notimestamps|>` RE-CONDITIONS the decode and each emitted timestamp spends one of the 197 budget positions, so a long chunk can truncate at a different token than it did at 4.10.1. There is no version of the fix that keeps that tier bit-identical; one label for a 15 s chunk is the worse transcript, so the spec wins and the acceptance rows are scoped to match - unchanged text asked of CPU, sane and complete text asked of NPU. One guard was re-based rather than left alone: `qnn_asr.cpp`'s 4.3.1 repetition cut histogrammed the last 32 GENERATED ids, and timestamps are ever-increasing singletons, so a `<|t|><|t|> Thank you.` loop would have pushed both `distinct` past CYCLE_MAX_DISTINCT and the entropy past ENTROPY_THOLD and disarmed the trip exactly when a runaway needed it; the window now holds 32 TEXT ids and the last-rung cut drops to that window's own start, and on a stream with no timestamps it is byte-for-byte the pre-4.11 guard. `dtw_token_timestamps` IS STILL NEVER SET - whisper.cpp gates the new-segment callback on `!dtw_token_timestamps`, so enabling DTW would silently kill the live words strip with no error anywhere; a pin asserts the flag is absent from the whole translation unit and an acceptance row asks a device to prove the strip still fills. 101 = 4.10.1 is spent: the owner installed it on his Z Fold6 and ran the 2026-09-19 20:37-20:41 session on it, so only a higher code replaces it, and the name takes a MINOR because a label that can land inside a chunk is a new capability rather than a fix to 4.10.1's. Previous: SPEAKER LABELS REACH THE NPU TIER (owner, 2026-09-19, testing 4.10.0/100 from the internal track on his Z Fold6: "it doesn't seem like I'm getting any speaker changes at all"). The cause was a design gap, not a defect in the tier: every speaker window came from the whisper.cpp geometry `transcribeRaw` exports, and the NPU arm runs its own encoder and decoder on the HTP with no whisper.cpp VAD anywhere in it, so `lastGeometry` answered null and the assigner was never called. 101 gives that tier a VAD of its own - one segmenter in the JNI with two callers, `vadSegmentsOf` on the speaker thread (~60 ms a chunk) - fingerprints its speech windows with the same tracker, the same gates and the same retrospective second look as the CPU tiers, and gives the chunk ONE speaker: the window holding the most SPEECH, ties to the earliest. It is coarser there and says so: the QNN decoder exposes no token or sentence timestamps, so a chunk's text cannot be split between two voices and a change lands on a 6-8 s chunk boundary instead of a sentence. The owner ruled that granularity sufficient ("at least that would be good enough"). THE CPU TIERS DO NOT MOVE - the route is gated on what the backend CAN publish rather than on what one read returned, and four tests fail if the per-window path changes. 100 = 4.10.0 went to the INTERNAL TRACK on 2026-09-19 and is spent there. Previous: SPEAKER LABELS ON COMMITTED TEXT (owner, 2026-09-18: "we should be able to detect when a new speaker or if a previous speaker was speaking and switch between speaker one, paragraph, then speaker two ... If there's only one speaker then we keep that"; "live preview can just stay exactly as it is"). WHAT A USER SEES, and the first half is the half that matters: with ONE voice the output is 4.9's byte for byte - no labels, no extra paragraphs, nothing. With two or more, the transcript panel breaks a paragraph at every speaker change and starts each one `Speaker N:`, the FIRST paragraph relabelled `Speaker 1:` the moment a second voice is CONFIRMED - the panel is rewritten from the session start, because the panel's text is ours to rewrite. The live preview strip and every cloud session are untouched. Text typed into another app's field gets the paragraph breaks and NEVER the labels, the owner's ruling that a text field is not a transcript. TWO SETTINGS, and their defaults are the whole of the policy: "Detect speakers" defaults ON (off restores 4.9 everywhere from the next recording and never loads the model at all); "Speaker labels in copied and saved text" defaults OFF - the clipboard and a saved transcript always get the paragraph breaks, which are the feature, and the labels only with the switch on, applied at EXPORT time so a transcript already on disk gains them when it is flipped. HOW: the native layer returns the VAD segment geometry and whisper's per-segment timestamps beside the text; a BUNDLED 40.3 MB NVIDIA NeMo TitaNet-small (speaker_titanet_small_16k.onnx, stored uncompressed, chosen by scoring five candidate models offline against 132 dumped segments - CC-BY-4.0, attribution PAID in oss_licenses.html, clearance row PENDING OWNER SIGN-OFF and gating production rather than the internal track) fingerprints one window per sentence on its own `speaker-embed` thread, never the whisper thread, so the commit floors spec S3.3 measured are untouched; a pure SpeakerTracker assigns ids under graded duration gates and the band T_SAME 0.50 / T_NEW 0.30; and every few chunks and once inside the finalize fence a retrospective reclusterer re-clusters the session's fingerprints and relabels the panel through the same remap path - the only correction for the failure the owner met, an online matcher locking onto one id and giving fifty windows of two voices the same number (spike doc session 6, the 03:27 dump: two voices found retrospectively 40/14, one run-on paragraph online). The fingerprint/audio dump that chose the model is DISARMED in this build and its purge made unconditional, so a device that ran a spike build is cleaned at the first launch after the update. AND THE PANEL, from the same sessions: its window is 20,000 characters with an exact fit (the earlier text stopped disappearing) and it follows the newest line only for a reader who is already there, so scrolling back up stays put. 99 = 4.9.1 IS IN PRODUCTION - uploaded by the owner on 2026-09-17 - so it is spent twice over: Play refuses a second upload at the same code and every installed phone already carries it. 100 is the next integer and the name takes a MINOR, because speaker labels are a new capability rather than a fix to one. Previous: PLUS PLAIN MODEL CARDS (owner, 2026-09-17, same session: "The copy for each one of the local models ... It's too technical for people that don't know anything about it. Our headlines are perfectly fine, and just about everything else doesn't need to be shown"): the five bodies are one to three plain sentences, headlines and badges unchanged, and every measurement, twin fact and dated report the old bodies carried moved VERBATIM into the KDoc beside its card - ModelTierCopyTest pins the bodies, the absence of technical tokens, and the KDoc's evidence; the NPU cards keep their measured claims at exactly their scope; same build, same versionCode. A PATCH: THE TRANSCRIPT WINDOW'S RESIZE AND SCROLLBAR (owner, 2026-09-17, on his Tab S10+ on 4.9.0/98: "the resizing arrow, we need to make that a color where we can actually see it. I say red ... while text is transcribing it wants to drag the window around ... touching the resize portion and moving up should resize and lock the window vertically, and the same horizontally, and moving in combination should of course also work ... if we touch the slider, we should be able to slide it up and down"). FOUR THINGS, none of them a model, a pack or a payload. (1) THE PANEL TAKES ITS CHOSEN HEIGHT EMPTY OR FULL: applyPreviewSize set maxHeight on a wrap_content TextView, so the panel was the chosen size only when the text filled it, while handleResizeTouch moved params.y by the height change regardless - on a short panel (a session's first words) the compensation ran without the growth and the whole window walked with the finger. The height is layoutParams.height now, with the width. (2) THE AXIS LOCK in ResizeMath.resize (AXIS_LOCK_RATIO 2.5, about 22 degrees): a clearly vertical drag holds the width, a clearly horizontal one holds the height and moves the window not at all, between is the diagonal; judged on the total drag from the start point per move. (3) THE HANDLE IS RED at full alpha - #FF5252, the literal behind BubbleColours.LIVE_DEFAULT - same 28dp target (ResizeHandlePinTest). (4) THE SCROLLBAR CAN BE GRABBED: TranscriptScrubberView beside each transcript view (the committed text below the handle, the live strip in its own wrapper), the 4.8.0 look at 4dp on a 12dp lane, thumb-relative so it does not jump, owning its gesture so the root drag never sees it, hidden and touch-inert when the text fits, and scrolling its TextView only under a finger so the service's scroll-to-newest is followed, never fought; the TextViews' own scrollbars are none (BubbleScrollbarPinTest rewritten; TranscriptScrubberMath tested). 98 = 4.9.0 was uploaded by the owner on 2026-09-17 and is spent; 99 is its plain successor. Previous: THE THREE-TIER LADDER SHIPS TO PRODUCTION (owner, 2026-09-17, after his own dictation on all three rungs on his Tab S10+: "all three actually work very well" - his report, not a WER; and on turbo, "we definitely wanna keep that one ... six to maybe nine second drain time, which is totally manageable and doable. And users would definitely like to select between these"). FOUR THINGS. (1) THE LABELS are his words, made true: small "Fastest, less accurate", medium "Balanced speed and accuracy", turbo "Highest accuracy, slower than the other two" - his "slightly slower" amended by controller ruling, because the tablet measurement (docs/measurements/2026-09-17-tab-cpu-ladder.md) puts turbo at 3.6x medium and 4.0x small per commit (4,849 ms against 1,341 and 1,217 - over three times either), and his own reported drain on turbo was six to nine seconds against the doc's 1.2-1.3 s per-commit medians for the other two (the doc's figure, not one he reported); his report is on turbo's card as his report, dated. The green RAM chip reads "Fits your device" (a RAM fit, on every rung whose floor the device meets) and each RAM-floored card says "Offered where", not "Recommended where": the recommendation is the steer's "Our pick" alone. Every body names the tablet and the date; "fastest" as a claim about every device stays forbidden (ModelTierCopyTest). (2) THE FIRST-RUN LINEUP IS CUMULATIVE BY RAM ("if you can fit the medium model, you should also be able to see the small model ... if you can see v3 turbo, of course, you should see all three tiers"): every Q8 rung whose own floor the device meets - small always, medium and turbo at 4.5 GB, one constant per rung so turbo's can be raised alone (WhisperCatalog.MEDIUM_Q8_MIN_RAM_BYTES / ULTRA_Q8_MIN_RAM_BYTES) - so under the floor small alone, at or over it all three in ladder order with medium still steered ("Our pick"). NPU detection untouched ("they should absolutely get the NPU tier - that's unmatched"). ultra-q8 is an ordinary rung: no longer an instrument, badged "Fits your device" like its siblings where the floor is met. (3) THE AUTHORISATION, recorded where the gate reads it: TierThroughputRecord.PRODUCTION_PROMOTABLE names all three; ultra-q8's verdict is STILL KEPT_UP_WITHOUT_MARGIN and clears on a ThroughputVerdict.OwnerRuling recorded beside the number - a decision written next to the evidence it overrides, named and dated, never an edit to the evidence; the gate reports Promotable for the first time; the acceptance sheet's AN0 carries the same words. (4) THE STRIP IS NEVER BLANK FOR A WHOLE SESSION: 4.8.1 armed the session on the posted warm and accepted that a load which then throws, or a canary that fails, left that session with nothing on the strip (the tee swallowed whisper's deltas for a previewer that never painted); now the previewer's open() reports it cannot open (LocalPreview.open's onUnavailable), the tee switches to pass-through one-way for the session and tells the service, and the service clears the session's local-preview flag so the strip returns to the ordinary in-flight label - the pre-4.8.1 strip, on CPU and NPU alike (the NPU tier emits no whisper deltas, so the flag, not the pass-through, is what closes it). AND ONE CADENCE ROW MOVED: medium-q8 paces on the 6 000 ms MULTI row (CommitCadencePolicy) by the owner's ruling of 2026-09-17 after testing medium Q8 on his tablet - "six seconds for medium, since I can handle it" - its worst Tab commit (2,508 ms) is 0.42 of that floor; through 4.8.x it paced at 8 000 via the LARGE row. ultra-q8 stays at 8 000 by his same-day ruling ("keep it the way it is"; its worst commit was 7,930 ms, so 7 000 is not supported). versionCode STAYS 98: the 4.8.1 name was set earlier today by the first-session round and 98 never left this machine, so the name moves and the code does not (ReleaseIdentityTest says why). Previous: A PATCH: THE FIRST-SESSION LIVE-WORDS GATE (owner, 2026-09-17, on a fresh sideloaded 4.8.0/97: after onboarding and the tap that installs the live-words pack, the FIRST dictation showed no live words and the second did - "users will just think it's broken"; the re-run "sailed right through", which is the timing). The wrap site read the engine's isWarmFor() in the same Main pass that had just posted the load, and on a fresh install the first warm is the boot prewarm's (onboarding never starts the service; the install collector drops the replayed record by design), so a tap inside the ~2.5-3.5 s after the bubble toggle lost the whole first session. 98 arms the session on the POSTED warm - the engine's single FIFO executor runs it before the session's open(), and every entry point is safe on a cold recognizer - so the strip fills the moment the load lands; Home's card retires only when a session opened warm; warm_now= joins the stream-gate line as the proof. Nothing about which pack warms, when, the busy refusal or the release rules moved, and onboarding still does not start the service. 97 was sideloaded only (never on a Play track), so it is spent there as 95 and 96 were. Owner ruling: "let's fix it into this next build right now, and then I'll push that to production, that way everyone gets a smooth install" - the promotable set (TierThroughputRecord.PRODUCTION_PROMOTABLE) is untouched by this patch and is his call at promotion time. Previous: FOUR RULINGS FROM THE 96 TABLET SESSION (owner, 2026-09-17, after seeing 4.7.0/96 on his Tab S10+: "Everything looks good"). (1) The transcript window's scrollbars are VISIBLE - persistent, 4dp, a drawn thumb on a faint track on both the committed text and the live strip; the framework's fade-at-rest default is why earlier builds "don't seem to have that". (2) "Keep bubble always on screen" defaults to OFF for NEW installs; an install with no stored value has its answer written once at construction - `true` for an existing install (it was living in always-on), `false` for a fresh one - so nobody's bubble changes mode on upgrade, and a fresh install that has since finished onboarding is not mistaken for an existing one at its next process start. (3) The opacity ladder goes down to 20% so a video plays through the panel; the legibility guarantee is kept honest at 85% and above (OPACITY_GUARANTEED_PERCENT) and the slider states the trade below it. (4) First-run model choice: NPU-capable devices see turbo alone (already true); every other device is gated at the owner's 4.5 GB - under it the smallest Q8 rung, over it medium and turbo (medium steered - a controller call on the throughput measurements, not the owner's; OnboardingLogic.FIRST_RUN_STEER_ABOVE_GATE_ID is the val to flip). 96 was 4.7.0, sideloaded to the tablet only, never on a Play track; 97 is for the internal track. Production authorisation is still withheld pending the owner's accuracy pass (the promotable set is empty). Previous: THE Q8 LADDER. On 2026-09-17 five CPU rungs were timed on the owner's Tab S10+ against the same talk (docs/measurements/2026-09-17-tab-cpu-ladder.md) and the owner ruled the same day: "Q8 for everything" - "Q5 is definitely off the table". Small Q8_0 becomes the floor for every device and the default (the same weights as the 190 MB model, 2.2x faster per commit on that tablet), medium Q8_0 becomes the medium tier recommended above a provisional 5.5 GB RAM threshold, and large-v3-turbo Q8_0 stays an optional top rung - it kept up on a flagship with no margin, so it is offered for its accuracy and never advocated. The four Q5 rungs (the shipped 190 MB default among them) are retired the gentle way: hidden from the chooser, untouched for anyone who has one. PRODUCTION AUTHORISATION IS WITHHELD: the promotable set is empty pending the owner's accuracy pass on small and medium, so 96 goes to the internal track and a sideloaded tablet only. 95 was 4.6.0, the instrument ladder, never promoted..

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Ship symbol tables with the AAB so native whisper/ggml crashes in Play vitals
            // arrive symbolicated instead of as raw addresses. Cheapest observability win.
            debugSymbolLevel = "SYMBOL_TABLE"
        }
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Ensure the 16 KB page-size flag reaches every native target (incl. ggml/whisper),
                // not just whisper_jni — required for Play Store Android 15+ compliance.
                arguments += "-DANDROID_STL=c++_shared"
                // Compile ggml's fast quantized kernels (ARM dot-product + int8 matmul). These are
                // runtime-dispatched via getauxval, so ONE .so is fast on capable CPUs (2-4x for
                // quantized whisper) and falls back safely on older ones. Baseline stays armv8-a
                // (crash-safe across the minSdk-26 range); +fp16 is intentionally omitted (it is
                // NOT runtime-gated and would SIGILL on the oldest armv8.0 cores).
                arguments += "-DGGML_CPU_ARM_ARCH=armv8-a+dotprod+i8mm"
                // GPU = Qualcomm's ggml OpenCL backend (Adreno 750 officially supported; kernels
                // embedded via Python; Adreno-optimized matmuls ON — safe for .en models whose
                // vocab 51864 is %4==0; the multilingual model needs re-test re upstream #3708).
                // Headers: Khronos OpenCL-Headers; lib: libOpenCL.so pulled from the Fold 6.
                // Backends as dlopen-able MODULES (Track B): libggml no longer hard-links the
                // OpenCL backend, so ONE apk runs everywhere — the JNI scans the native-lib dir
                // at startup and loads every backend that CAN load (CPU always; OpenCL only on
                // devices whose vendor ships libOpenCL.so). Without this, System.loadLibrary
                // died on Tensor/Mali devices before CPU transcription could even exist.
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_OPENCL=" + if (openClRoot != null) "ON" else "OFF"
                openClRoot?.let {
                    arguments += "-DOpenCL_INCLUDE_DIR=" + File(it, "include").absolutePath
                    arguments += "-DOpenCL_LIBRARY=" + File(it, "lib/libOpenCL.so").absolutePath
                }
                // CMake otherwise picks the Windows-Store python alias stub and fails.
                arguments += "-DPython3_EXECUTABLE=$python3Executable"
                // Vulkan CLOSED on Adreno (driver-compiler aborts + DeviceLost at Queue::submit,
                // proven on-device 2026-07-17). Revisit only for Mali/Xclipse experiments.
                arguments += "-DGGML_VULKAN=OFF"
                // 16 KB page-size alignment (Play requirement): the dlopen backend MODULES
                // (libggml-cpu/opencl) linked at 4 KB — cmake MODULE targets dodge the flag the
                // SHARED targets get. Force it on both linker classes (verified via
                // llvm-readelf LOAD p_align, 2026-07-18).
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                arguments += "-DCMAKE_MODULE_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                cppFlags += "-std=c++17"
            }
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
            // keystore.properties absent -> unsigned release (CI / fresh checkout); never fall
            // back to hardcoded credentials.
        }
    }

    // The bundled speaker-embedding model (speaker_titanet_small_16k.onnx, 40.3 MB) must stay

    // STORED, not deflated: sherpa-onnx opens assets through the AssetManager, and a

    // compressed asset cannot be mapped or read as a file. The .bin assets ride the

    // same rule. (4.10 speaker labels, Task 2 review finding.)

    androidResources {

        noCompress += listOf("onnx", "bin")

    }


    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Dev machine only (props present): sign debug with the release key so debug/test
            // APKs install straight over the release build without uninstalling (which would
            // wipe the downloaded model). CI/fresh checkouts keep the default debug keystore.
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // NEVER bundle the vendor OpenCL loader we link against at build time — the app must
            // use the DEVICE's own /vendor copy (see uses-native-library in the manifest).
            // Bundling it breaks dlopen: our namespace can't resolve its private libc++.so dep.
            excludes += "**/libOpenCL.so"
            // sherpa-onnx AAR dead weight: the Kotlin API needs only libsherpa-onnx-jni.so
            // (+ libonnxruntime.so, its sole non-system DT_NEEDED — verified via llvm-readelf).
            // The C/CXX API libs and the parakeet model runtime are ~5 MB of unused payload.
            excludes += "**/libsherpa-onnx-c-api.so"
            excludes += "**/libsherpa-onnx-cxx-api.so"
            excludes += "**/libparakeet.so"

            // QNN/QAIRT runtime (4.0 NPU tier; the fleet census since 4.2 F2). The AAR ships
            // every backend for every Hexagon architecture; this app deserialises precompiled
            // context binaries only for the census families (NpuFleetCensus). THE RULE — stated
            // as a rule so the next family's edit has one to follow, not an example to copy:
            //
            //   ONE STUB PER CENSUS ARCHITECTURE IN lib/, ONE SKEL PER CENSUS ARCHITECTURE IN
            //   assets/.
            //
            // Architecture, not family: the stub and skel are the HTP version's, so six
            // families ship five of each (qcs8550 and 7gen4 share V73), and a family that joins
            // on an architecture already covered adds no library at all.
            //
            // The stub is the CPU-side half, dlopen()ed by libQnnHtp.so straight out of the APK
            // — page-aligned, works without extraction — and a family whose stub is excluded
            // arms all the way to nativeInit and then dies inside the QNN loader with nothing
            // naming why. The skel is the DSP-side half: under this app's
            // extractNativeLibs="false" packaging the FastRPC loader (which needs a real file on
            // disk and searches only ADSP_LIBRARY_PATH) could never open a lib/ copy, so every
            // census family's skel is excluded here, re-materialised into generated assets by
            // extractQnnSkel below, and NpuWhisperBackend stages exactly the device family's own
            // into filesDir — the first ADSP_LIBRARY_PATH entry — at first arm. Everything else
            // the AAR carries is dead weight in the APK:
            //
            // libQnnHtpPrepare.so alone is 85 MB (85,026,392 B at 2.50) and exists only to
            // COMPILE a graph on device — we never compile one.
            excludes += "**/libQnnHtpPrepare.so"
            // Non-HTP backends: unused.
            excludes += "**/libQnnDsp.so"
            excludes += "**/libQnnDspV66Skel.so"
            excludes += "**/libQnnDspV66Stub.so"
            excludes += "**/libQnnGpu.so"
            // The census families' DSP-side skels — relocated to assets per the rule above.
            // Their stubs are deliberately NOT excluded. V69 joined on 2026-09-24 with the 8gen1
            // family (the S22 generation): its skel moved up here from the uncovered list below,
            // and its stub's exclude was deleted, so libQnnHtpV69Stub.so now ships in lib/ like
            // every other census stub.
            excludes += "**/libQnnHtpV69Skel.so"
            excludes += "**/libQnnHtpV73Skel.so"
            excludes += "**/libQnnHtpV75Skel.so"
            excludes += "**/libQnnHtpV79Skel.so"
            excludes += "**/libQnnHtpV81Skel.so"
            // HTP architectures with no covered family: skel AND stub stay excluded — and these
            // presences are what keep the census families' stub live-zeros honest in
            // NpuSkelPackagingTest. V68 is the only one left since V69 became a census
            // architecture.
            excludes += "**/libQnnHtpV68Skel.so"
            excludes += "**/libQnnHtpV68Stub.so"
        }
    }

    sourceSets {
        // 4.1 L6: the generated qnnSkel assets dir — produced by extractQnnSkel, consumed by
        // merge*Assets (the task ordering lives beside the task). Without this registration the
        // merge never sees the dir, the APK ships without the skel, and every device dies at
        // stage=skel while the build looks green.
        getByName("main") { assets.srcDir(qnnSkelAssetDir) }
        // (P2-6) libLiteRt.so's generated JNILIBS dir — a jniLibs source like any other, so it is
        // merged, aligned and packaged into lib/arm64-v8a/ with the app's own libraries, and ordered
        // before merge*JniLibFolders beside its task (NOT the skels' assets route: their comment
        // above and at extractQnnSkel records why the order is against the consuming task).
        getByName("main") { jniLibs.srcDir(litertJniLibDir) }
        // (P2-6) The MediaTek dispatch's generated ASSETS dir, ordered before merge*Assets beside
        // its task, exactly as the skels' is.
        getByName("main") { assets.srcDir(litertDispatchAssetDir) }
    }

    testOptions {
        // Diagnostic logging uses android.util.Log, which is not available in plain JVM unit
        // tests; return default (no-op) values instead of throwing "Method not mocked".
        unitTests.isReturnDefaultValues = true
    }

    // (4.2 F4) The two on-demand NPU asset packs. Play delivers ONE #group_ variant of each —
    // resolved server-side against device_targeting_config.xml — and under on-demand only when
    // the app calls fetch(), which the census gate never does on a non-NPU device: unmatched
    // devices get the EMPTY default variant AND no fetch, two independent mechanisms. An APK
    // build (assembleDebug) carries no packs at all; only bundle builds demand the payload,
    // and verifyNpuPacks below is what demands it.
    //
    // (4.4.0, the 2026-09-10 amendment) :preview_en joins them — the streaming previewer's four
    // ONNX files, on-demand, but NOT device-targeted: the same bytes on every device, so the pack
    // carries one untargeted directory (assets/preview_en/) and device_targeting_config.xml is
    // deliberately untouched. ONE assetPacks statement, because a second list is a second thing
    // to keep correct and a pack missing from it ships no variants at all, silently.
    // verifyPreviewPack is its gate, wired below beside verifyNpuPacks'.
    //
    // (4.4.0, Task 2b) :tts_kokoro joins on the same terms — the read-aloud voice's
    // kokoro-multi-lang-v1_0.tar.bz2 carried AS-IS, on-demand, untargeted — so the 2026-09-08
    // rolling-tag incident cannot recur: the archive rides the AAB and a voice update becomes a
    // deliberate release. verifyTtsPack is its gate, wired below beside the other two.
    //
    // (4.5.0 Task 2; owner ruling 2026-09-12, "let's set up all 6 languages") The six LANGUAGE
    // packs join on :preview_en's exact terms — four raw files each, on-demand, untargeted,
    // 421,369,659 B of new payload across the six, taking this bundle's asset packs from 4 of the
    // 96 available slots to 10. verifyPreviewPack, extended below, gates all seven preview packs.
    //
    // They are APPENDED BY CONCATENATION rather than inserted into the list above, and that is
    // mechanical rather than stylistic: the four names above are pinned as CONTIGUOUS TEXT by
    // NpuPackLayoutTest, PreviewPackLayoutTest and TtsPackLayoutTest, so a name added inside that
    // `listOf(` would retire three pins whose whole job is to catch a pack silently leaving the
    // bundle. Concatenation adds names and disturbs none of the three. Still ONE assetPacks
    // statement, for the reason stated above it.
    //
    // EVERY ONE OF THE SIX IS UNCONDITIONAL, and PreviewPackLayoutTest holds this expression to a
    // flat literal to keep it that way: no build type, no flavour, no gradle property, no
    // environment read and no per-language term of any kind may enter it. The owner tests all six
    // languages on the internal track, so a language must be PRESENT and FETCHABLE in every bundle
    // this repo can build; anything that gates PUBLICATION is a promotion decision and does not
    // live where the bundle is assembled.
    //
    // (P2-5, the MediaTek APU tier) The mt6989 turbo pair's two packs join the same way, by a
    // third concatenated list, for the same mechanical reason: the prefix and the six languages
    // stay contiguous text for the pins that read them. They are UNTARGETED — each module one
    // payload directory, no #group_ folder (bundletool's DeviceGroupParityValidator requires every
    // group-targeted module to carry the same set of groups) — and the census gate decides who
    // fetches them. Unconditional like every entry here: the NeuroPilot licence ruling gates
    // PUBLICATION, and publication is not decided where the bundle is assembled.
    assetPacks += listOf(":npu_turbo", ":npu_small", ":preview_en", ":tts_kokoro") + listOf(
        ":preview_fr", ":preview_de", ":preview_ru",
        ":preview_id", ":preview_ko", ":preview_zh",
    ) + listOf(":npu_turbo_mt6989_enc", ":npu_turbo_mt6989_dec")

    bundle {
        // The census spelled for Play — committed, and byte-pinned to NpuFleetCensus by
        // NpuPackLayoutTest: a census edit that forgets to regenerate the XML fails the suite,
        // which is maintenance rule 1's teeth. Play targeting stays a bandwidth optimization;
        // the app gate remains the correctness authority.
        deviceTargetingConfig = file("device_targeting_config.xml")
        deviceGroup {
            enableSplit = true
            // Unmatched devices land in "other" and receive the packs' DEFAULT variants —
            // which verifyNpuPacks holds EMPTY, because Play cannot be told to deliver
            // nothing: a device that can't be prevented from receiving the default must
            // find nothing worth receiving in it.
            defaultGroup = "other"
        }
    }
}

// NativeVadSourceContractTest asserts over C++ SOURCE TEXT, but Gradle cannot see that: the .cpp
// files are inputs to the CMake tasks, never to the JVM test task, so a change confined to native
// sources leaves :app:testDebugUnitTest UP-TO-DATE and the contract goes unchecked. Verified: a
// one-line perturbation of the vendored fork produced "Task :app:testDebugUnitTest UP-TO-DATE /
// 27 actionable tasks: 27 up-to-date / BUILD SUCCESSFUL" without running a single test — which is
// precisely the shape of an upstream merge that re-promotes the demoted VAD logs. Declaring the two
// guarded files as explicit test inputs makes that change invalidate the task, so the guard fires.
// (4.0) NpuNativeContractTest asserts over qnn_asr.cpp, the manifest and the root .gitignore for
// exactly the same reason and with exactly the same blind spot: none of the three is an input to
// the JVM test task by default, so an edit confined to any of them leaves this task UP-TO-DATE and
// the guard passes against stale evidence. The manifest and .gitignore go in the same list as the
// .cpp files — the rule is about what the tests READ, not about the file extension.
// (4.0 Q2) The fork's include/whisper.h joins them, and it is the sharpest case yet: the only
// thing MelExportContractTest can read to know that whisper_get_mel_segment is DECLARED is that
// header, and "add a declaration to a header" is precisely the shape of the change that would
// otherwise leave this task UP-TO-DATE. Deleting the declaration would have left the guard green.
// (4.0 Q5) whisper_vocab.json is the first ASSET in this list and it belongs here for exactly the
// reason stated above — the rule is about what the tests READ. Unit tests run with
// `unitTests.isIncludeAndroidResources` at its default (false), so assets are NOT on the test
// classpath and WhisperBpeDecoderTest reads the file straight out of the source tree with the house
// `source(relative)` walker. That is what lets it pin the SHIPPED vocabulary rather than a fixture —
// and without this line the pin is decoration. MEASURED, not assumed: with the file absent from
// this list, corrupting one language code in the asset (`"<|sl|>"` -> `"<|XX|>"`) produced
// "Task :app:testDebugUnitTest UP-TO-DATE / BUILD SUCCESSFUL" without running a single test, so the
// three tests that exist to catch a wrong or edited vocabulary all "passed" against stale evidence.
// (4.0 Q6) NpuWhisperBackend.kt is the first KOTLIN file in this list, and it is here for a
// narrower reason than the rest. Kotlin main sources normally need no entry: they are compiled into
// the test task's classpath, so any real edit invalidates it. The exception is a COMMENT-only edit,
// which produces byte-identical .class files and leaves the task UP-TO-DATE — and the residency pin
// on this file is a NEGATIVE assertion over the whole file INCLUDING comments (`WhisperNative.init(`
// must appear nowhere at all, so that the KDoc cannot re-teach the 190 MB mistake it exists to
// forbid). Without this line, the single mutation that pin is for is the single mutation that never
// re-runs it.
// (4.0 Q7a) The two files UnsupportedTierGatePinTest reads join for the same reason, and it was
// MEASURED here rather than inferred: with WhisperModelManager.kt absent from this list, a
// comment-only edit to it produced "Task :app:testDebugUnitTest UP-TO-DATE" — so that class's
// negative assertions (`model.retired` must appear nowhere; `f.length(), model.approxBytes` must
// appear nowhere in isInstalled) could be broken by a KDoc line that never re-runs them. The pins
// predate this entry; the gap was found by Q7a's battery and is closed for both files at once.
// (4.0 Q7b) The two chooser screens ChooserSteerWiringPinTest reads join by the same stated rule —
// what the tests READ — and the honest note is that here the gap was MEASURED ABSENT rather than
// present: with OnboardingModelScreen.kt off this list, a comment-only edit to it still produced
// "> Task :app:compileDebugKotlin / bundleDebugClassesToRuntimeJar / testDebugUnitTest", i.e. the
// Compose-compiled classes are not byte-stable across a recompile, so the runtime jar changed and
// the task re-ran anyway. That is an incidental property of the Compose plugin's output, not a
// contract — SettingsScreen.kt is a Compose file already in this list for the same reason — and
// this pin's needles are INDENTATION-sensitive block matches, the one mutation shape most likely
// to leave semantics untouched. Declared, so the re-run stops depending on a compiler accident.
// WhisperEverywhereApp.kt joins them in the Q7b micro-round: the same class now pins the offer
// gate's two halves and the API-31 guard on both Build SOC fields there, and unlike the two
// Compose screens it is a plain Kotlin class — exactly the shape Q7a measured going UP-TO-DATE.
// (4.0 Q8) MainActivity.kt joins by the same stated rule — NpuImportWiringPinTest reads it, to pin
// that the SAF launcher exists, that the Uri it yields reaches `importNpuAssetPair`, and that the
// state the importer returns is what the screen renders. A launcher whose result nothing consumes
// is a picker that opens, closes and silently does nothing, which is the one failure shape an
// import is never allowed to have; it must not be possible to introduce it in a file the guard
// does not re-read.
// (4.0 Q9) FloatingBubbleService.kt joins by the same stated rule — NpuBackendWiringTest reads it,
// because it is the ONE construction site of LocalWhisperEngine in the bubble path and no JVM test
// can instantiate a Service. What is pinned there is the `backend =` argument, the ORDER of the
// rebuild (shutdown BEFORE construct, which is I11 arriving through the service), and that the
// offer gate is read off Main. It is a plain Kotlin file — exactly the shape Q7a MEASURED going
// UP-TO-DATE on a comment-only edit — and the needles are indentation-sensitive block matches, the
// mutation shape most likely to leave semantics untouched.
// (4.1 L1) QnnAsrNative.kt joins because NpuNativeContractTest now READS it — that file is the only
// place the Kotlin and native halves of `nativeRelease(epoch)` / `nativeEpoch()` can be compared
// before a device links them, and a `jlong` added native-side while Kotlin still declared the
// zero-argument form would link (the JNI name is unmangled for a non-overloaded method) and reach
// the guard with whatever was in the argument register. Stated honestly: today's assertions there
// are all LIVE-line scoped, so a comment-only edit could not break them and this entry is not yet
// load-bearing. It is here because the rule this list is built on is about what the tests READ, and
// the next assertion added to that pin is not required to remember the distinction.
// (4.1 L2) NpuDecodePolicy.kt joins because NpuDecodePolicyTest now READS it: the absence of a
// default on the `family` parameter is a property of the DECLARATION and no call can observe it —
// a call that omitted the argument would not compile, and a test cannot assert about code that
// does not exist. That default is the whole hazard the parameter was added to remove (a turbo
// prompt built out of whisper-small's ids puts the model in the wrong TASK), so the one mutation
// this list has to guarantee re-runs the pin is a one-character addition to that line.
// (4.10 Task 0) WhisperNative.kt joins, and it is overdue: NativeSegmentStatsContractTest and
// NativeVadSourceContractTest have both been READING it as source for the Kotlin half of a
// prose-only contract, and SegmentGeometryPinTest now reads it for a third. Those two classes pin
// KDoc PHRASES — "PROCESS-GLOBAL", "inside the gate", the blocking-width sentence — and a KDoc edit
// is the purest comment-shaped mutation there is: it compiles to a byte-identical class, so without
// this entry deleting the sentence that tells Workstream C/D how to read these statics leaves
// `:app:testDebugUnitTest` UP-TO-DATE and every phrase assertion passing against the documentation
// as it used to be. Exactly the shape Q7a measured.
tasks.withType<Test>().configureEach {
    inputs.files(
        "src/main/cpp/whisper_jni.cpp",
        "src/main/java/com/whispereverywhere/whisper/WhisperNative.kt",
        "src/main/cpp/whisper.cpp/src/whisper.cpp",
        "src/main/cpp/whisper.cpp/include/whisper.h",
        "src/main/cpp/qnn_asr.cpp",
        "src/main/AndroidManifest.xml",
        "src/main/assets/whisper_vocab.json",
        // (4.0 Q8) The second ASSET, and it is here for the sharpest version of the stated reason:
        // WhisperBpeDecoderTest now pins that the shipped licence page attributes the vocabulary
        // above under Apache-2.0 (the Q5 review's I1, a 4.0 ship gate). An HTML asset is an input to
        // no compile task at all, so an edit confined to it would leave this task UP-TO-DATE and
        // the gate would pass against the page as it used to be — which is precisely the change
        // being guarded against.
        "src/main/assets/oss_licenses.html",
        // (4.1 L3) The 128-bin filterbank, and it is the sharpest case this list has. It is a
        // BINARY asset that is an input to no compile task at all, so regenerating it — wrongly,
        // from a different model, or at a different truncation — changes not one .class file.
        // MelbankAssetTest is its only reader (length to the byte, sha256, magic, and the two
        // header agreements the fork loader itself checks), and without this entry the task
        // reports UP-TO-DATE and every one of those assertions passes against the file as it used
        // to be. That is precisely the change being guarded against.
        "src/main/assets/melbank-128.bin",
        // (4.5.0 T3) The canary CLIPS, by the melbank's reason and with a sharper consequence.
        // They are binary assets that are inputs to no compile task, and each one is the input to
        // a VERDICT: PreviewCanaryClipsTest holds every clip's length, sha256, PCM16/16 kHz/mono
        // format and the text the real pack produced from it, and a `PreviewCanary` Fail switches
        // that language's live words off for the process. Without these entries a re-encoded, a
        // re-recorded or a truncated clip leaves :app:testDebugUnitTest UP-TO-DATE and every one
        // of those pins passes against the audio as it used to be — which is precisely the change
        // being guarded against. (canary_digits.wav has been shipping since 3.6.0 and joins now
        // for the same reason: this is the first test that reads it off disk.)
        "src/main/assets/canary_digits.wav",
        "src/main/assets/canary_fr_digits.wav",
        "src/main/assets/canary_de_fleurs.wav",
        "src/main/assets/canary_ru_fleurs.wav",
        "src/main/assets/canary_id_fleurs.wav",
        "src/main/assets/canary_ko_fleurs.wav",
        // (4.1 L4) The turbo vocabulary, for exactly the melbank's reason one asset over: a JSON
        // asset is an input to no compile task, so regenerating it wrongly — from the wrong base,
        // without <|yue|>, with HF's <|nospeech|> spelling — changes not one .class file.
        // TurboVocabAssetTest (base identity, special layout, digest, the id-188 NUL token) and
        // WhisperBpeDecoderTest (golden vectors through the turbo decoder) are its only readers,
        // and without this entry the task reports UP-TO-DATE and every one of those assertions
        // passes against the file as it used to be.
        "src/main/assets/whisper_vocab_turbo.json",
        // (4.10 Task 2) The BUNDLED speaker model and the one file that loads it, and they join
        // together because they are two halves of the same claim. The model is a 40.3 MB binary
        // asset — an input to no compile task at all — and `SpeakerEmbedderPinTest` is the only
        // reader of those bytes anywhere in this repo: its length and sha256 are what stands
        // between the APK and the four OTHER embedding models the spike's session 2 scored beside
        // this one (26.5-39.6 MB, all in one directory on the PC while the swap was made), or a
        // copy some future round interrupted. Nothing on device can tell us which file shipped;
        // the owner ruled the model is bundled precisely so there is no download step to notice a
        // wrong one at.
        //
        // The entry was RE-POINTED when TitaNet-small replaced CAM++
        // (docs/measurements/2026-09-18-speaker-spike.md, session 2). A rename that missed this
        // list would leave the new model undeclared and the old path naming a file that no longer
        // exists — Gradle tolerates the second half silently, which is the whole hazard.
        //
        // SpeakerEmbedder.kt is the other half, for the reason Q6 added NpuWhisperBackend.kt: no
        // test may REFERENCE it (sherpa's SpeakerEmbeddingExtractor loads libsherpa-onnx-jni.so in
        // its companion initialiser), so every assertion about it is a source-text assertion —
        // literal counts on `provider = "cpu"` / `numThreads = 1`, the two load arms, and the
        // digest the KDoc has to keep agreeing with the asset. All of those are comment-shaped
        // mutations that compile to a byte-identical class, which is exactly the shape that leaves
        // :app:testDebugUnitTest UP-TO-DATE with the pins green against the file as it used to be.
        "src/main/assets/speaker_titanet_small_16k.onnx",
        "src/main/java/com/whispereverywhere/transcription/speakers/SpeakerEmbedder.kt",
        // (4.10 Task 3) The ASSIGNER and the PREVIEW TEE, by this list's stated rule — membership
        // follows what the tests READ. `SpeakerWiringPinTest` reads both as text: the assigner for
        // its executor NAME (a device trace and a logcat thread column are read by it) and for the
        // zero-counts that keep it pure — no `import android.`, no sherpa, no reference to the
        // adapter — and the tee for the negative half of the spec's untouched-preview ruling
        // (§2, §4), where the assertion is that a whole package NEVER names the speaker pipeline.
        // A zero-count over comments is satisfied by a comment, so the mutation each of those
        // pins exists to catch is exactly the comment-shaped one that compiles to a byte-identical
        // class and leaves :app:testDebugUnitTest UP-TO-DATE.
        "src/main/java/com/whispereverywhere/transcription/speakers/SpeakerAssigner.kt",
        // (4.10 spike session 6) The RECLUSTERER, by this list's stated rule. `SpeakerWiringPinTest`
        // reads it as text for the pins that keep the retrospective pass pure — no thread of its
        // own, no `import android.`, no sherpa — because it re-seeds a tracker that is confined
        // to the `speaker-embed` executor, and a thread added here would put two writers on that
        // state with every behavioural test still green. Those are zero-counts, which a comment
        // satisfies, so the mutation the pin exists to catch is exactly the comment-shaped one
        // that compiles to a byte-identical class.
        "src/main/java/com/whispereverywhere/transcription/speakers/SpeakerReclusterer.kt",
        "src/main/java/com/whispereverywhere/transcription/stream/PreviewTeeEngine.kt",
        // (4.10 spike session 2) THE DUMP's two files, by this list's stated rule.
        // `SpeakerSpikePinTest` reads both as text, and every pin on them is the shape that
        // compiles to a byte-identical class: that the writer is confined to the `speaker-embed`
        // executor's own body (no unit test can watch a file write land on the wrong thread of a
        // service it cannot start), that the audio half is guarded by the `SPEAKER_SPIKE`
        // compile-time constant everywhere it appears, and that the KDoc carries the standing
        // warning about storing speech audio. Without these entries the one edit each of those
        // pins exists to catch is the one that leaves :app:testDebugUnitTest UP-TO-DATE.
        "src/main/java/com/whispereverywhere/transcription/speakers/SpeakerSpike.kt",
        "src/main/java/com/whispereverywhere/transcription/speakers/SpeakerSpikeStore.kt",
        "src/main/java/com/whispereverywhere/npu/NpuAssetStage.kt",
        // (4.1 L3) NpuModelSpec.kt joins for the same reason L2 added NpuDecodePolicy.kt:
        // MelbankAssetTest now READS it, because the absence of a default on `melAsset` is a
        // property of the DECLARATION and no call can observe it — a construction that omitted the
        // argument would not compile, so there is nothing to execute. That default is the whole
        // hazard the required field removes (a 128-bin row silently taking the 80-bin donor arm),
        // and the one mutation this list has to guarantee re-runs the pin is a five-character
        // addition to that line.
        "src/main/java/com/whispereverywhere/npu/NpuModelSpec.kt",
        // (4.1 L8) NpuAssetImport.kt joins per the L6 review's rider and this list's own doctrine
        // (the QnnAsrNative.kt entry above states it): membership follows what tests READ, not
        // whether today's assertions could be fooled. NpuAssetImportTest reads it (the
        // PAIRED_TIER_IDS derivation live line, the "npu-turbo" live-zero); all its pins are
        // live-line-scoped today, so this entry is not yet load-bearing — and the next assertion
        // added there is not required to remember the distinction.
        "src/main/java/com/whispereverywhere/npu/NpuAssetImport.kt",
        // (4.15) The launch stale-pair sweep, and the two backup rule files its record must stay
        // out of, by this list's stated rule. `NpuStalePairSweepTest` executes the sweep and ALSO
        // reads it as text — its verdict must be the one `passesInstalledGate` call and it must
        // never name `PART_SUFFIX`, a live-line claim and a zero-count, both comment-proof but
        // not rule-proof — and it reads both rule XMLs to hold that neither names the
        // device-local store. An XML edit changes no .class file at all, so without these
        // entries the one edit that pin exists to catch (the record's file added to the
        // allowlist, where it would travel to a phone that never held the pair) is the one that
        // leaves `:app:testDebugUnitTest` UP-TO-DATE.
        "src/main/java/com/whispereverywhere/npu/NpuStalePairSweep.kt",
        "src/main/res/xml/backup_rules.xml",
        "src/main/res/xml/data_extraction_rules.xml",
        // (4.15) The refresh notice's three files, by the same rule. `NpuRefreshNoticeTest` reads
        // BootReceiver.kt as text (it is a BroadcastReceiver no JVM test can deliver a broadcast
        // to): the model-missing arm posts instead of returning, the channel, the distinct id and
        // request code, no EXTRA_START_BUBBLE on the tap, the notified key written after the
        // notify, and ZERO copy literals in the receiver — the last a zero-count a comment could
        // break. It reads NpuRefreshNotice.kt for the zero-literal rule on the size (the number
        // is derived from the census), and strings.xml for the channel's name. AccessibilityOptional-
        // WiringPinTest has read BootReceiver.kt since 4.3.3 without this entry; it is overdue.
        "src/main/java/com/whispereverywhere/receiver/BootReceiver.kt",
        "src/main/java/com/whispereverywhere/npu/NpuRefreshNotice.kt",
        "src/main/res/values/strings.xml",
        // (4.15) And the app-wide gate's one routing line, which the same test reads to prove the
        // onboarding flow is the screen a modelless install lands on — the reason the in-app
        // sentence lives there and not on Home.
        "src/main/java/com/whispereverywhere/ui/screens/ModeDashboard.kt",
        // (4.1 L8) NpuBackendSelector.kt — the plan's own found-while-writing hole, the same one
        // Q7a MEASURED and I3 named, on the one file that carries the routing decision:
        // NpuBackendWiringTest source-pins this file (the routesToNpu signature, the zero-literal
        // rule, the production construction site), yet it was never a test input, so a
        // comment-only edit left the suite UP-TO-DATE and every one of those pins passing against
        // stale evidence.
        "src/main/java/com/whispereverywhere/transcription/NpuBackendSelector.kt",
        "src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt",
        // (P1a review) TranscriptionEngine.kt — overdue, by this list's stated rule. Three pins read
        // it as text: NpuBackendWiringTest's whole-file, comment-inclusive zero-count (the CPU tier
        // never names HallucinationPolicy), SpeakerWiringPinTest's publishesGeometry defaults and
        // NativeSegmentStatsSeamTest's declarations. The first is exactly the shape a comment-only
        // edit breaks while compiling to identical bytes, so without this entry the one edit that
        // pin exists to catch is the one that leaves :app:testDebugUnitTest UP-TO-DATE.
        "src/main/java/com/whispereverywhere/transcription/TranscriptionEngine.kt",
        // (P1a, the engine seam) The seam's files, by this list's stated rule — membership follows
        // what the tests READ. NpuStageTest reads NpuStage.kt as text for the spelling rule the
        // derivation cannot see (each wire word a literal beside its constant, `Refusal` the
        // design's exact shape). Stated honestly, the QnnAsrNative.kt discipline: today's needles
        // there are live declarations, so an edit to them changes the class and re-runs the suite
        // anyway — the entry is here because the rule is about what the tests READ, and the next
        // needle added there is not required to remember the distinction.
        "src/main/java/com/whispereverywhere/npu/NpuStage.kt",
        // NpuAsrEngineSeamTest reads NpuAsrEngine.kt for properties of the DECLARATIONS that no
        // call can observe — no default on any parameter, no member beyond design 2.4's ten, no
        // vendor's name on a live line — and a default argument is precisely the one-token edit
        // every existing caller compiles straight past.
        "src/main/java/com/whispereverywhere/transcription/NpuAsrEngine.kt",
        // QnnAsrEngine.kt carries what was QNN-shaped in NpuWhisperBackend.kt, and with it the
        // pins that read it there, re-pointed here: the skel stage (NpuSkelPackagingTest — whole
        // file and comment-inclusive for the sha256 and the deleted companions, the backend's own
        // reason for being on this list), the melprobe order (NpuDiagTest), every QnnAsrNative
        // entry point and the arm-time cleanup (NpuNativeContractTest), the stage derivation
        // (NpuStageTest). No JVM test may name the class — it touches QnnAsrNative — so source
        // is the only instrument, and a comment-only edit must still re-run it.
        "src/main/java/com/whispereverywhere/transcription/QnnAsrEngine.kt",
        // (P2-7) LiteRtAsrEngine.kt — the seam's second engine, by the same rule and for the same
        // reason: it touches LiteRtAsrNative (System.loadLibrary("litertasr")), so no JVM test may
        // name it, and LiteRtAsrEngineContractTest (every entry point reached from here and from
        // nowhere else in main, the scalar order, the two literal defaults, the dispatch stage,
        // the teardown census) and NpuStageTest (the MediaTek session's stage derivation) read it
        // as text. Several of those pins are comment-shaped or count call sites, so without this
        // entry the one edit each exists to catch is the one that never re-runs it.
        "src/main/java/com/whispereverywhere/transcription/LiteRtAsrEngine.kt",
        // (4.1 L7) LocalWhisperEngine.kt joins because PerUtteranceLanguageTest now READS it:
        // the languageFor-exactly-once-inside-the-conditional claim is what stops a second,
        // unconditional pin consult from reinstating the 3.7 latch under a per-utterance
        // backend. Stated honestly, the QnnAsrNative.kt discipline below: today's assertions
        // there (and SegmentTimingTest's older ones) are all LIVE-line scoped, so a comment-only
        // edit could not break them — the entry is here because the rule this list is built on
        // is about what the tests READ, and the next assertion added to those pins is not
        // required to remember the distinction.
        "src/main/java/com/whispereverywhere/transcription/LocalWhisperEngine.kt",
        "src/main/java/com/whispereverywhere/npu/QnnAsrNative.kt",
        "src/main/java/com/whispereverywhere/npu/NpuDecodePolicy.kt",
        // (P1b, the MediaTek APU tier) LiteRtNativeContractTest's read set, by this list's rule.
        // litert_asr.cpp and LiteRtAsrNative.kt are the two halves of a JNI seam no JVM test may
        // load (System.loadLibrary("litertasr")), so the symbol set, the arities, the adapter walk,
        // "the environment is never destroyed" and the output-order assertion are all pinned as
        // SOURCE; the KDoc of the Kotlin half is comment-shaped and compiles to identical bytes.
        // CMakeLists.txt carries the target's name, link set and header guard, and is an input to
        // no Gradle compile task at all. band_scan.h joins with them: its float twin is the detect
        // pass's, and NpuNativeContractTest already read the header for the Android-free property
        // without listing it - an edit confined to it left the suite UP-TO-DATE. litert_stamp.h is
        // the chip check's parser, pinned Android-free for its host check.
        "src/main/cpp/litert_asr.cpp",
        "src/main/cpp/litert_stamp.h",
        "src/main/cpp/band_scan.h",
        "src/main/cpp/CMakeLists.txt",
        "src/main/java/com/whispereverywhere/npu/LiteRtAsrNative.kt",
        // (4.2 F1) NpuGate.kt joins by this list's stated rule — membership follows what the
        // tests READ. NpuGateTest now source-pins the gate's derivation (SUPPORTED_SOCS spelled
        // as the census flatMap, isSocSupported spelled as familyFor != null, zero hand-typed
        // soc literals on live lines): the doctrine that keeps the offer gate and the family
        // resolution one reading of one census. Today every needle there is live-line-scoped,
        // so a comment-only edit could not fool them — the entry is here because the next
        // assertion added is not required to remember the distinction.
        "src/main/java/com/whispereverywhere/npu/NpuGate.kt",
        // (4.2 F3) NpuFleetCensus.kt joins by the list's stated rule — membership follows what
        // the tests READ. NpuFleetCensusTest and NpuAssetImportTest execute against the census
        // object (compiled, so an edit re-runs them anyway); the entry is here because the
        // census's artifact rows are now also pinned AGAINST A SCRIPT (build_asset_packs.py
        // below), and the next assertion that reads this file as text is not required to
        // remember the distinction.
        "src/main/java/com/whispereverywhere/npu/NpuFleetCensus.kt",
        // (P2, the gate on the row) The MediaTek driver check — NpuApuDriverCheckTest reads it as
        // text to hold the dispatch directory's name to ONE live spelling (the constant) and the
        // probe's path to the one function built from it. Both are claims a comment-shaped edit
        // or a second literal could break without changing what any executed test observes.
        "src/main/java/com/whispereverywhere/npu/NpuApuDriverCheck.kt",
        // (P2-7, the P2a review's L5) The release shrinker's rules, by the same rule: the driver
        // verdict's `apu: verdict` line goes out through WhisperNative.diag BECAUSE these rules
        // strip android.util.Log from every release build, and NpuApuDriverCheckTest reads the
        // file to hold that reason to the rule it rests on. It is an input to no compile task.
        "proguard-rules.pro",
        // (P2-6, runtime packaging) The LiteRT runtime's Kotlin home — NpuAssetStageDirTest reads it
        // as text to hold the dispatch stage to the one dispatch directory (NpuApuDriverCheck's) and
        // to no second spelling of its name; comment-shaped edits compile to identical bytes.
        "src/main/java/com/whispereverywhere/npu/LiteRtRuntime.kt",
        // (4.2 F5) NpuPackController.kt — the Play fetch flow's Android shell. It is
        // AssetPackManager-bound (no JVM test can construct it), so NpuDiagTest pins its
        // emission sites and the remove-after-install ORDER as source text; without this
        // entry an edit confined to the shell leaves the task UP-TO-DATE and those pins pass
        // against the file as it used to be.
        "src/main/java/com/whispereverywhere/npu/NpuPackController.kt",
        // (4.2 F5) NpuDiag.kt joins by the list's stated rule — membership follows what the
        // tests READ. NpuDiagTest has read it as text since 4.0 (the contiguous-literal pins)
        // and now also re-derives the unavailable() stage enumeration from it; a comment-only
        // edit to this file changes no .class file, so without this entry the one mutation
        // those pins exist to catch is the one that never re-runs them.
        "src/main/java/com/whispereverywhere/npu/NpuDiag.kt",
        "src/main/java/com/whispereverywhere/model/WhisperModelManager.kt",
        "src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt",
        "src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt",
        "src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt",
        // (4.4.0, Task 2b fix round 1, B2) HomeScreen.kt joins by the list's stated rule —
        // membership follows what the tests READ. TtsPackShellPinTest now pins Home's
        // missing-voice row as source: that it reads the Application's TtsModelManager (a
        // private one carries a private Play-refusal latch), that it raises Play's own
        // confirmation dialog for the voice fetch it can start, and that it quotes no
        // hand-written archive size. Every one of those mutations is Compose-shaped or
        // comment-shaped, so without this entry the edit each pin exists to catch is the one
        // that never re-runs it.
        "src/main/java/com/whispereverywhere/ui/screens/HomeScreen.kt",
        // (4.2 F6) OnboardingSetupViewModel.kt joins by the list's stated rule — membership
        // follows what the tests READ. ChooserSteerWiringPinTest now source-pins the gated
        // fetch branch (ensureSpeech hands gated tiers to NpuPackController, mirrors its state
        // through the one pure mapping, and stops at the first terminal state); the branch is
        // viewModelScope-bound so no JVM test can execute it, and without this entry an edit
        // confined to this file leaves the task UP-TO-DATE and those pins passing against
        // stale evidence.
        "src/main/java/com/whispereverywhere/ui/onboarding/OnboardingSetupViewModel.kt",
        "src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt",
        "src/main/java/com/whispereverywhere/MainActivity.kt",
        "src/main/java/com/whispereverywhere/service/FloatingBubbleService.kt",
        // (4.5.1 Task 2) The bubble's LAYOUT, by the list's stated rule — membership follows what
        // the tests READ. `BubbleColoursWiringPinTest` reads this XML to hold three facts that are
        // invisible to every other test: the panel's two text colours and the panel's own fill
        // are still declared there as the PRE-FIRST-APPLY defaults (delete them as dead and a
        // device renders the panel with no colour at all for the frames before
        // applyBubbleColours runs, and forever on any device where the background is not the
        // shape drawable), and the "RUNTIME-OWNED" note that says so. A resource-only edit
        // changes no .class file, so without this entry the one edit that pin exists to catch is
        // the one that leaves `:app:testDebugUnitTest` UP-TO-DATE.
        "src/main/res/layout/floating_bubble.xml",
        // (2026-09-22, the mute toggle) The window's corner-control drawables, by the list's
        // stated rule. MuteTogglePinTest reads both mic vectors' literal fills (faint white for
        // audio flowing, the live red for muted), and ResizeHandlePinTest has read the arrow's red
        // since 4.9.1 without an entry here — so an edit to that colour alone would never re-run
        // the pin that exists to catch it. Resource files change no .class file.
        "src/main/res/drawable/ic_mic_live.xml",
        "src/main/res/drawable/ic_mic_muted.xml",
        "src/main/res/drawable/ic_resize_handle.xml",
        "src/main/res/drawable/control_disc.xml",
        "src/main/res/drawable/ic_processing_ring.xml",
        // (4.3.1 B) BubbleHideWiringPinTest reads the controller for speakFromTrigger's Boolean.
        "src/main/java/com/whispereverywhere/tts/TtsController.kt",
        // (4.4.0, Task 2b) The voice manager, by the list's stated rule and overdue: this file has
        // been read as text by TtsModelManagerPinTest since the 2026-09-08 incident's fix without
        // being declared, and Task 2b's pins are ORDER and ZERO/ONE-count assertions over the
        // whole file (the storage gate above the extract, remove-after-land, ONE
        // verifyExtractInstall, ONE Play discriminator, every tar.delete() guarded). Several of
        // those mutations are comment-shaped, so without this entry the one edit each pin exists
        // to catch is the one that never re-runs it.
        "src/main/java/com/whispereverywhere/tts/TtsModelManager.kt",
        // (4.4.0, Task 2b) And the voice's FETCH SHELL, by the comment-only rule: every
        // TtsPackShellPinTest assertion over it is an ORDER or ZERO-count claim (register before
        // fetch, the re-told refusal is the one published, no removePack, no borrowed NpuPackFetch
        // sentence, both latch sites present) and several of those mutations produce
        // byte-identical .class files.
        "src/main/java/com/whispereverywhere/tts/TtsPackController.kt",
        // (4.0 Q9 fix round, I1) BatchTranscriber.kt joins for the NARROW reason, the same one
        // NpuWhisperBackend.kt is here for: BatchLocalModelTest's wiring pin includes NEGATIVE
        // assertions over the whole file INCLUDING comments (`installedModelPath()` must not be read
        // straight in loadCtx; the refusal string must not be re-spelled there and drift from the
        // policy that owns it). A comment-only edit produces byte-identical .class files, so without
        // this entry the one mutation those pins exist to catch is the one that never re-runs them.
        "src/main/java/com/whispereverywhere/transcription/batch/BatchTranscriber.kt",
        "src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt",
        rootProject.file(".gitignore"),
        // (4.1 L6) THIS build script, and it is the strangest member yet: NpuSkelPackagingTest
        // reads it, because the skel mechanism is four spellings that must agree (the jniLibs
        // exclude, the extract task's two asserted values, the srcDir registration, the
        // dependency coordinate pair) and none of them is an input to any compile task. An edit
        // confined to this file invalidates configuration, not the test task's inputs — so
        // without this entry, deleting the exclude or loosening a check( would leave
        // testDebugUnitTest UP-TO-DATE and the pins passing against stale evidence.
        "build.gradle.kts",
        // (4.1 L3) The extractor, for the same reason as the asset above and one step further out:
        // it lives outside the app module, so it is not even a candidate input by convention.
        // MelbankAssetTest asserts it carries BOTH pinned digests as literals — the provenance
        // claim and the reproducibility claim — and loosening either of those to a threshold is a
        // pure-Python edit that no Kotlin or C++ task would notice.
        rootProject.file("tools/extract_melbank.py"),
        // (4.1 L4) The vocabulary builder, same rule as the extractor above it: it lives outside
        // the app module, TurboVocabAssetTest asserts its verification literals (the exact 50,257
        // base count and the id-188 known mismatch — the pins that stop the cross-check being
        // loosened to a threshold), and loosening either is a pure-Python edit no compile notices.
        rootProject.file("tools/build_turbo_vocab.py"),
        // (4.1 L8) The delivery-zip repacker, same rule as the two scripts above it: it lives
        // outside the app module, NpuAssetImportTest asserts it carries both tiers' four delivery
        // filenames and the catalog's four digests as literals (the census that stops a repack
        // from quietly renaming or re-hashing a ~GB artefact), and loosening any of that is a
        // pure-Python edit no compile task would notice.
        rootProject.file("tools/pack_npu_zip.py"),
        // (4.2 F3) The pack measure/build instrument, same rule as the three scripts above it:
        // it lives outside the app module, NpuFleetCensusTest asserts its embedded CENSUS table
        // carries every artifact digest and byte count as literals (the cross-pin that stops
        // the committed census and the instrument that fills the packs drifting apart), and
        // loosening any of that is a pure-Python edit no compile task would notice.
        // (4.2 F4) NpuPackLayoutTest joins its readers: the FAMILIES htp↔packGroup pairing, the
        // metadata-first and declared-size writer pins, and the self-verification needles.
        rootProject.file("tools/build_asset_packs.py"),
        // (4.5.0 T3) The canary-clip builder, same rule as the four scripts above it: it lives
        // outside the app module, PreviewCanaryClipsTest holds its table equal to the Kotlin
        // one (each clip's asset name, byte count, digest and MEASURED DECODE, plus the pinned
        // FLEURS revision and licence), and every one of those is a pure-Python edit no compile
        // task would notice. A clip is a verdict input: if the two tables drift, the record of
        // where a verdict input came from stops describing the file that is shipping.
        rootProject.file("tools/build_canary_clips.py"),
        // (P2-6) The two scripts that staged the LiteRT runtime into P1b's device gate, by the same
        // rule: LiteRtPackagingTest holds their pinned libLiteRt.so digest and dispatch zip/member
        // digests equal to this build's, so the product ships the files the tier was measured with,
        // and a pure-Python edit to either is one no compile task would notice.
        rootProject.file("tools/mtk-apu/stage_litertasr_into_probe.py"),
        rootProject.file("tools/probes/litertlm-probe/fetch_mediatek_runtime.py"),
        // (4.2 F4) The device-group XML — the sharpest asset case since the melbank: it is an
        // input to no compile task (it enters the AAB, not the APK), and NpuPackLayoutTest holds
        // it byte-equal to the census rendering. Without this entry, an edit confined to the XML
        // leaves the task UP-TO-DATE and the store ships strings the census never named while
        // every pin passes against the file as it used to be.
        "device_targeting_config.xml",
        // (4.2 F4) The rest of NpuPackLayoutTest's read set, by the list's stated rule —
        // membership follows what the tests READ, and none of these is an input to any compile
        // task: the two pack module build files (packName/on-demand pins), their .gitignores
        // (the payload wall), the settings include line, and the gradle.properties flag that
        // the whole device-targeting mechanism silently vanishes without.
        rootProject.file("settings.gradle.kts"),
        rootProject.file("gradle.properties"),
        rootProject.file("npu_turbo/build.gradle.kts"),
        rootProject.file("npu_small/build.gradle.kts"),
        rootProject.file("npu_turbo/.gitignore"),
        rootProject.file("npu_small/.gitignore"),
        // (P2-5) The MediaTek pair's two untargeted modules, by the same rule: NpuPackLayoutTest
        // reads their build files (packName / on-demand) and their payload walls, and neither half
        // of a pack module is an input to any compile task.
        rootProject.file("npu_turbo_mt6989_enc/build.gradle.kts"),
        rootProject.file("npu_turbo_mt6989_dec/build.gradle.kts"),
        rootProject.file("npu_turbo_mt6989_enc/.gitignore"),
        rootProject.file("npu_turbo_mt6989_dec/.gitignore"),
        // (4.4.0, the 2026-09-10 amendment) PreviewPackLayoutTest's own two, by the same rule:
        // the third pack module's build file (packName/on-demand/no-#group_ pins) and its payload
        // wall are inputs to no compile task, so without these entries an edit confined to either
        // would leave testDebugUnitTest UP-TO-DATE and the layout pins green against the files as
        // they used to be. (The script, settings.gradle.kts and this build file are already here.)
        rootProject.file("preview_en/build.gradle.kts"),
        rootProject.file("preview_en/.gitignore"),
        // (4.4.0, Task 2b) TtsPackLayoutTest's own two, by exactly the same rule: the fourth pack
        // module's build file and its payload wall are inputs to no compile task, so without
        // these entries an edit confined to either would leave testDebugUnitTest UP-TO-DATE and
        // the voice pack's layout pins green against the files as they used to be.
        rootProject.file("tts_kokoro/build.gradle.kts"),
        rootProject.file("tts_kokoro/.gitignore"),
        // (4.5.0 Task 2) The six LANGUAGE pack modules' twelve unbuilt files, by exactly the same
        // rule, and PreviewPackLayoutTest now reads all fourteen (these plus preview_en's) in one
        // loop over the catalogue. Neither half of a pack module is an input to any compile task:
        // `packName.set("preview_ko")` changed to another language's name, or a `.gitignore` wall
        // that stops walling 128 MB of French encoder, are both edits that leave
        // :app:testDebugUnitTest UP-TO-DATE — the first ships a bundle whose Korean pack can never
        // be fetched by the name the catalogue asks for, and the second puts model payload in a
        // repo with a public remote. Without these entries the one edit each pin exists to catch is
        // the one that never re-runs it.
        rootProject.file("preview_fr/build.gradle.kts"),
        rootProject.file("preview_fr/.gitignore"),
        rootProject.file("preview_de/build.gradle.kts"),
        rootProject.file("preview_de/.gitignore"),
        rootProject.file("preview_ru/build.gradle.kts"),
        rootProject.file("preview_ru/.gitignore"),
        rootProject.file("preview_id/build.gradle.kts"),
        rootProject.file("preview_id/.gitignore"),
        rootProject.file("preview_ko/build.gradle.kts"),
        rootProject.file("preview_ko/.gitignore"),
        rootProject.file("preview_zh/build.gradle.kts"),
        rootProject.file("preview_zh/.gitignore"),
        // (4.4.0) The previewer's FETCH SHELL, by the comment-only rule BatchTranscriber.kt is
        // here for: StreamingPackShellPinTest's pins include ORDER and ZERO-count assertions over
        // the whole file (registerListener before fetch, installFromPack before Installed, no
        // removePack, no NpuDiag line, both latch sites present). Several of those mutations are
        // comment-shaped or produce byte-identical .class files, so without this entry the one
        // edit each pin exists to catch is the one that never re-runs it.
        "src/main/java/com/whispereverywhere/transcription/stream/StreamingPackController.kt",
        // (4.4.0) And the manager, for its own ORDER pin: the free-space gate must sit above the
        // verify and the copy on the pack route, and neither route may re-derive the 1.1 x
        // headroom. Reordering three statements inside one suspend function is invisible to every
        // behavioural test — there is no JVM path through `StatFs` — and it is the difference
        // between a refusal that costs nothing and ~146 MB of dead bytes on a full device.
        "src/main/java/com/whispereverywhere/transcription/stream/StreamingPackManager.kt",
        // (4.4.1) The connectivity monitor, for the narrowest reason on this list: its new
        // isUnmetered() is the ONE predicate the auto-fetch's consent rule hangs on, and no JVM
        // test can call it (ConnectivityManager). LivePreviewDeclinedPinTest pins the capability
        // it reads and — the part that matters — that no network, no capabilities and a throwing
        // service all default to METERED. Every one of those mutations is a one-token edit that
        // compiles clean and changes nothing any other test observes.
        "src/main/java/com/whispereverywhere/net/ConnectivityMonitor.kt",
        // (4.4.1) The auto-fetch's ACTUATOR, by the comment-only rule StreamingPackController.kt
        // is here for: LiveWordsCardPinTest's pins over it are ORDER and ZERO/ONE-count claims
        // (the single-flight guard above the route, the latch on the auto path only, one write of
        // the back-off stamp, one actuation per route, no give-back of the pack, one diagnostic
        // line). Several of those mutations are comment-shaped or produce byte-identical .class
        // files, so without this entry the one edit each pin exists to catch is the one that
        // never re-runs it.
        "src/main/java/com/whispereverywhere/transcription/stream/PreviewAutoFetchController.kt",
        // (4.5.0 Task 3c) The progress strip above the LANGUAGE SELECTOR, by this list's stated
        // rule. `LivePreviewSelectorStripPinTest`'s pins over it are ORDER and ZERO/ONE-count
        // claims on a Compose file — the strip ABOVE the dropdown and above the onboarding rows,
        // one board collector, no decision, no tap, no sentence of its own. A strip that drifted
        // BELOW the control that caused the spend renders every correct sentence and is still the
        // silent spend ruling 3c closes, and that edit is layout-shaped: without this entry it
        // would leave testDebugUnitTest UP-TO-DATE and every pin green against the old file.
        "src/main/java/com/whispereverywhere/ui/components/LivePreviewSelectorStrip.kt",
        // (4.5.0) The previewer's sherpa ADAPTER — the one file in the app that imports
        // com.k2fsa, whose static init loads libsherpa-onnx-jni.so, so no JVM test may reference
        // it and SherpaPreviewLoaderPinTest pins it as source. What that pin protects is a single
        // token: `modelType = ""`. A "fix" to "zipformer2" is an UNCATCHABLE _Exit(-1) on every
        // zipformer v1 pack (fr, both zh-en rows) that no catch, no onLoadFailure, no markCorrupt
        // and no crash sentinel can see. Without this entry the one edit that pin exists to catch
        // is the one that never re-runs it.
        "src/main/java/com/whispereverywhere/transcription/stream/SherpaPreviewRecognizer.kt",
        // (4.5.0 Task 4 fix round 2, review r2's B2) The DEVICE axis's two facts and their one
        // home each. `PreviewUnreachableTest` now reads these two files as source to hold them
        // there — the mechanism (no word reaches the bubble, and the reason is NOT the previewer's
        // gate) and the pointer from the announcement's own input to the flag's KDoc. Both files
        // are pure Kotlin, so every mutation those pins exist to catch is COMMENT-SHAPED and
        // compiles to a byte-identical class: without these entries the one edit each pin exists
        // to catch is the one that leaves `:app:testDebugUnitTest` UP-TO-DATE. The refuted
        // mechanism surviving a whole fix round in a `@param` is that edit, observed.
        "src/main/java/com/whispereverywhere/transcription/stream/PreviewUnreachable.kt",
        "src/main/java/com/whispereverywhere/transcription/stream/PreviewAutoFetch.kt",
        // (4.10 Task 5) The saved transcript's export. `TranscriptsExportPinTest` reads this
        // screen as text because it is the one @Composable that decides whether the user's
        // "Speaker labels in copied and saved text" switch is honoured — and every way of getting
        // that wrong is a LITERAL edit that compiles to a byte-identical class: `labels = true`
        // in place of the flag, the sidecar read dropped, the flag missing from the producer's
        // keys, or the three buttons reading three different strings. Without this entry that
        // edit is the one that leaves `:app:testDebugUnitTest` UP-TO-DATE.
        "src/main/java/com/whispereverywhere/ui/screens/TranscriptsScreen.kt",
        // (4.5.0 Task 5) THE TWO DOCUMENTS THE CLEARANCE GATE LIVES IN — the only entries on this
        // list that are prose, and they are here for the list's stated reason: membership follows
        // what the tests READ. `StreamingPackClearanceTest` asserts that the acceptance sheet's
        // promotion gate names `PackClearanceRecord.PRODUCTION_CLEARED`, that AF2 is rewritten
        // rather than deleted, that the Play-prompt row names Play's two statuses, that every
        // pack's own size badge appears, and that the owner's checklist carries a section for each
        // outstanding language with the commit its evidence was read at.
        //
        // MEASURED, not assumed: with these two files absent from this list, mutating all three —
        // the sheet's promotion gate, the Korean section's answerer and the Chinese commit — left
        // `:app:testDebugUnitTest UP-TO-DATE / BUILD SUCCESSFUL in 13s` without running a single
        // test. The clearance gate is the one thing in this build that a five-language legal
        // question hangs on, and a pin over it that never re-runs is worse than no pin: it reports
        // green over a document that has been edited out from under it.
        //
        // The price, stated: the acceptance sheet is hand-edited during a device session, so
        // ticking a box re-runs the suite. That is the right trade for a gate on a store
        // promotion, and the sheet is the place a promotion decision is actually made.
        rootProject.file("docs/LANGUAGE-CLEARANCE.md"),
        rootProject.file("docs/superpowers/sdd/2026-09-02-431-guards-tts/acceptance.md"),
    // RENAMED from `nativeSourceContract` (4.1 L2, Q7a M4(ii)). The list stopped being about
    // native sources several tasks ago: it holds two ASSETS, a manifest, a .gitignore and twelve
    // Kotlin files, and only four of its entries are C++ at all. A property name that describes a
    // quarter of its contents is a name the next person adding to it reads as a reason NOT to —
    // which is exactly how a source-reading test ends up passing against stale evidence. L3, L4,
    // L6 and L8 each add to this list, so it is renamed now, before they do.
    ).withPropertyName("sourcePinnedInputs").withPathSensitivity(PathSensitivity.RELATIVE)
}

// QNN/QAIRT C API headers (4.0 NPU tier). PROPRIETARY — fetched, never committed (.gitignore:
// app/src/main/cpp/include/QNN/). The script pins the version as a literal and asserts the fetched
// QnnSdkBuildId.h matches it, because a silent 2.45-vs-2.49 header/runtime skew COMPILES CLEAN;
// same discipline as fetchSherpaAar's sha256 check below.
val qnnHeaderRoot = file("src/main/cpp/include")
/**
 * A RELEASE ARTIFACT MUST NOT QUIETLY LOSE A BACKEND. `openClRoot` is allowed to be null so that a
 * checkout with no OpenCL headers can still run the JVM tests — but `GGML_OPENCL=OFF` changes what
 * ships, and a build that silently drops the Adreno GPU backend would be indistinguishable from one
 * that kept it until a device told us. So the release bundle depends on this and refuses instead.
 */
val requireOpenClForRelease = tasks.register("requireOpenClForRelease") {
    doFirst {
        if (openClRoot == null) {
            error(
                "GGML_OPENCL would be OFF: no OpenCL headers found, so this release would ship " +
                    "without the Adreno GPU backend. Pass -PopenclRoot=/path/to/opencl (needs " +
                    "include/CL/cl.h and lib/libOpenCL.so) or build a debug variant instead.",
            )
        }
    }
}

tasks.matching { it.name == "bundleRelease" || it.name == "assembleRelease" }.configureEach {
    dependsOn(requireOpenClForRelease)
}

val fetchQnnHeaders = tasks.register<Exec>("fetchQnnHeaders") {
    description = "Fetches the pinned QAIRT (QNN) C API headers into src/main/cpp/include/QNN."
    inputs.file(rootProject.file("tools/fetch_qnn_headers.py"))
    outputs.dir(file("src/main/cpp/include/QNN"))
    // The interpreter is RESOLVED (see python3Executable): `python` is not on PATH on the
    // Windows box, and CMake in this same build pins Python3_EXECUTABLE to the same value for the
    // same reason (the Windows-Store alias stub resolves first otherwise).
    commandLine(
        python3Executable,
        rootProject.file("tools/fetch_qnn_headers.py").absolutePath,
        qnnHeaderRoot.absolutePath,
    )
    // I-1 (Q1 review). A plain Exec fails the build on ANY non-zero exit, which made the
    // "a network outage must not brick the CPU tiers" guarantee undeliverable: the build died
    // here, configureCMakeDebug never ran, and the `if(EXISTS ${QNN_INCLUDE_DIR}/QnnInterface.h)`
    // guard in CMakeLists.txt — written for exactly this case — was unreachable dead code on the
    // only path it existed for. Reproduced: portal unreachable => `> Task :app:fetchQnnHeaders
    // FAILED / BUILD FAILED`, with a full header tree sitting on disk.
    //
    // The two failure classes must stay apart, and the script now exits differently for them:
    //   3 => could not obtain the headers by any route. The tree is left EMPTY, so CMake skips
    //        libqnnasr.so and the CPU/GPU tiers — 100% of shipped transcription — still build.
    //   2 => the headers on disk are NOT the pinned build. STILL FATAL, offline or not: a
    //        2.45-vs-2.49 header/runtime skew compiles clean and misreads every versioned struct
    //        on device, which is the entire reason the pin exists.
    // Anything else is a real defect in the script and is rethrown unchanged.
    isIgnoreExitValue = true
    val exitCode = executionResult.map { it.exitValue }
    doLast {
        val code = exitCode.get()
        if (code == 3) {
            logger.warn(
                "fetchQnnHeaders: the QAIRT headers could not be obtained (see the warning " +
                    "above). The NPU tier is SKIPPED for this build; the CPU and GPU tiers are " +
                    "unaffected. Restore network access, or copy the header tree manually, and " +
                    "re-run to build libqnnasr.so."
            )
        } else if (code != 0) {
            throw GradleException(
                "fetchQnnHeaders failed with exit code $code — see the FATAL line above. Exit 2 " +
                    "means the QNN headers on disk are not the pinned build id; that is a " +
                    "silent-ABI hazard and is deliberately not tolerated."
            )
        }
    }
}

// Ordered before CMAKE, not merely before preBuild. `preBuild` gates the compile* tasks; it does
// NOT gate AGP's configureCMake*/buildCMake* tasks, and those are the ones that actually need the
// headers on disk — configureCMake evaluates the EXISTS() guard in CMakeLists.txt that decides
// whether libqnnasr.so is built at all. preBuild is wired too, so a build that never reaches CMake
// still leaves the tree populated.
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(fetchQnnHeaders) }
tasks.named("preBuild") { dependsOn(fetchQnnHeaders) }

// sherpa-onnx AAR (on-device TTS, Track F): no official Maven coordinates exist (verified
// 2026-07-18) and *.aar is gitignored, so the pinned upstream release asset is fetched on
// demand and sha256-verified — self-healing for CI and fresh clones alike.
//
// 1.13.4 -> 1.13.7 (B0, 2026-09-10). THE REASON IS THE RUNTIME INSIDE, NOT THE API: 1.13.4
// bundles ONNX Runtime 1.27.0, whose KleidiAI ConvolveSme kernel is WRONG for Conv
// pads=[0,1,0,1] on FEAT_SME CPUs — the first node of a Zipformer2 frontend. ORT 1.27.1 fixes
// it (microsoft/onnxruntime#28571); sherpa-onnx picked that up in 1.13.5 (#3861). Symptoms
// upstream: silent garbage or empty text for a whole stream, no crash and no NaN
// (k2-fsa/sherpa-onnx#3845, #3791). No FEAT_SME device is in the test fleet, but SM8850 is an
// app census family (NpuFleetCensus), so the app would ship a runtime that is documented-broken
// there the moment anything Zipformer2 lands. TTS (Kokoro) is the only consumer today and is
// unaffected either way — this bump is the floor a streaming local tier needs, taken early and
// alone so its blast radius is one dependency.
//
// The verified deltas, arm64-v8a, AAR-internal sizes:
//   libonnxruntime.so     21,688,920 -> 21,684,880 B   (ORT 1.27.0 -> 1.27.1, symbol tag VERS_*)
//   libsherpa-onnx-jni.so  4,710,728 ->  4,761,536 B
// Shipped payload therefore moves +46,768 B. classes.jar keeps all 122 classes with no removal
// and no signature change to anything TTS touches: OfflineTtsConfig, OfflineTtsModelConfig,
// OfflineTtsKokoroModelConfig, GeneratedAudio and GenerationConfig are BYTE-IDENTICAL across the
// two AARs. OfflineTts itself gains one `require(ptr != 0L)` per construction path, which turns a
// failed native init from a later SIGABRT into an early IllegalArgumentException — both app call
// sites already catch it (TtsEngine.kt:165 runCatching, :225-755 try/catch), so no Kotlin change.
val sherpaAar = file("libs/sherpa-onnx-1.13.7.aar")
// sha256 of the upstream release asset, computed 2026-09-10 (49,113,869 B, size as published).
val sherpaAarSha256 = "c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8"
val fetchSherpaAar = tasks.register("fetchSherpaAar") {
    outputs.file(sherpaAar)
    doLast {
        if (!sherpaAar.exists()) {
            sherpaAar.parentFile.mkdirs()
            uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar")
                .toURL().openStream().use { input ->
                    sherpaAar.outputStream().use { input.copyTo(it) }
                }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sherpaAar.readBytes())
            .joinToString("") { b -> "%02x".format(b) }
        check(digest == sherpaAarSha256) {
            "sherpa-onnx AAR sha256 mismatch ($digest) — delete app/libs and re-run"
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchSherpaAar) }

// Every census family's HTP skel, re-materialised from the RESOLVED qnn-runtime AAR into
// generated assets (4.1 L6 — the I5 answer; the fleet at 4.2 F2: one APK covers every census
// family — six families on five HTP architectures since 2026-09-24 — and the device stages exactly
// its own row's skel at arm time). PROPRIETARY: the blobs land in
// the build directory, outside the repo, and the root .gitignore is hardened with the blob
// shapes besides.
//
// The configuration restates the dependency's exact coordinate ON PURPOSE — the dependency
// line itself stays byte-unchanged, and NpuSkelPackagingTest pins the two spellings equal so
// they cannot drift apart.
val qnnSkelSource: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}

val extractQnnSkel = tasks.register("extractQnnSkel") {
    description =
        "Re-materialises every census family's HTP skel from the resolved qnn-runtime AAR into generated assets."
    inputs.files(qnnSkelSource)
    outputs.dir(qnnSkelAssetDir)
    doLast {
        // THE FLEET TABLE (4.2 F2): one row per census ARCHITECTURE, full literals — the skel is
        // the HTP version's blob, so qcs8550 and 7gen4 (both v73) share one row. These are the
        // same five (bytes, sha256) pairs NpuFleetCensus.families carries — restated here because
        // a build script cannot read the app's classes, and pinned EQUAL to the census by
        // NpuSkelPackagingTest (executed set-equality, both directions), the same two-spellings
        // discipline as the qnn-runtime coordinate below. A row joins when an architecture joins
        // the census, never alone. All five measured out of qnn-runtime-2.50.0.aar on 2026-09-24
        // (Maven Central, 71,270,746 B, sha256 b507656e…c9d743); V69 is the 8gen1 family's.
        val qnnSkels = listOf(
            Triple("libQnnHtpV69Skel.so", 12_529_660L, "262f3e8807ea969cfc446ea8717500475ea1ea1be6205201486a5431ffcb490e"),
            Triple("libQnnHtpV73Skel.so", 18_709_712L, "024a0aea3d8d44fc5b59ffab20bde4348d07d05ad7d23f27c8bd06aa3d240d8a"),
            Triple("libQnnHtpV75Skel.so", 18_693_300L, "3e9774b74769915b4f54364f8fc25887b3439561a970dca57c9f4dc9612b38af"),
            Triple("libQnnHtpV79Skel.so", 18_513_604L, "860c9d2e7c937c9fb8f8f18daa9a79cab6c566066a2d36f235f6c8708fdc75bd"),
            Triple("libQnnHtpV81Skel.so", 19_708_192L, "02047c9fef8a22801c0eefaa79188e87b600372c9813dea3f621ba256d1ddce0"),
        )
        val aar = qnnSkelSource.singleFile
        val outDir = qnnSkelAssetDir.get().asFile
        outDir.mkdirs()
        ZipFile(aar).use { zip ->
            for ((name, bytes, sha256) in qnnSkels) {
                val skel = File(outDir, name)
                val entry = zip.getEntry("jni/arm64-v8a/$name")
                    ?: throw GradleException(
                        "extractQnnSkel: jni/arm64-v8a/$name is missing from ${aar.name} — a " +
                            "runtime version bump changed the AAR layout. Re-measure every " +
                            "skel and update this table AND NpuFleetCensus's rows, which the " +
                            "runtime staging checks against at arm time."
                    )
                zip.getInputStream(entry).use { input ->
                    skel.outputStream().use { output -> input.copyTo(output) }
                }
                // The same assert-the-pinned-value discipline fetchSherpaAar applies to its
                // hardcoded digest, per entry, over the same values NpuWhisperBackend checks
                // again at arm time through the family row: a runtime bump produces a NAMED
                // build failure here and a NAMED stage refusal there — never a mystery on a
                // device.
                check(skel.length() == bytes) {
                    "extractQnnSkel: $name is ${skel.length()} bytes, expected $bytes " +
                        "(measured from qnn-runtime-2.50.0.aar). A runtime bump must " +
                        "re-measure and update this table AND NpuFleetCensus's row for $name together."
                }
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(skel.readBytes())
                    .joinToString("") { b -> "%02x".format(b) }
                check(digest == sha256) {
                    "extractQnnSkel: $name sha256 mismatch ($digest). A runtime bump must " +
                        "re-measure and update this table AND NpuFleetCensus's row for $name together."
                }
            }
        }
    }
}
// Ordered before the task that actually NEEDS the asset — merge*Assets — and not merely
// preBuild: preBuild gates the compile* tasks and does NOT gate AGP's asset merging, which is
// the exact lesson fetchQnnHeaders paid for with the CMake tasks one asset class over.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(extractQnnSkel) }
// preBuild too, so a build that never reaches the merge still leaves the blob materialised.
tasks.named("preBuild") { dependsOn(extractQnnSkel) }

// (P2-6, the MediaTek APU tier; design §2.6) THE LITERT RUNTIME: libLiteRt.so 2.1.1, the library
// liblitertasr.so dlopens (by path from nativeLibraryDir, then by SONAME — the form that resolves
// straight out of the APK under extractNativeLibs="false"). It lives in the litert AAR, and the AAR
// is resolved through a configuration of its OWN and is NEVER an implementation dependency, for two
// reasons that are each sufficient: its manifest would be MERGED — re-adding
// libneuron_sys_util.mtk.so, whose magic-number read costs a 5 s binder wait on every cold arm
// (sheet §4b), plus .9 and mgvi — and its transitive graph (lifecycle 2.10, guava, ai-delivery) would
// ride into the app. isTransitive = false keeps that graph out of even this configuration.
//
// Pinned three ways: the AAR's length as published, the library's exact length and sha256 (the
// file P1b's device gate ran — tools/mtk-apu/stage_litertasr_into_probe.py pins the same digest),
// and "the two coordinates agree": this coordinate's version and the dispatch zip's release tag
// below are one LiteRT release, LiteRtRuntime.VERSION, because the v2.1.1 dispatch loads only
// against its own release's libLiteRt.so. LiteRtPackagingTest holds all of it. No compiler plugin
// and no OpenCL accelerator: the extraction takes the one library and the generated dir holds
// nothing else.
val litertRuntime: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}

val extractLiteRtRuntime = tasks.register("extractLiteRtRuntime") {
    description = "Extracts libLiteRt.so (LiteRT 2.1.1) from the resolved litert AAR into generated jniLibs."
    inputs.files(litertRuntime)
    outputs.dir(litertJniLibDir)
    doLast {
        val aar = litertRuntime.singleFile
        check(aar.length() == 7_569_479L) {
            "extractLiteRtRuntime: ${aar.name} is ${aar.length()} bytes, expected 7_569_479 (the " +
                "litert 2.1.1 AAR as published). A coordinate bump is a new runtime: re-measure " +
                "libLiteRt.so, and move the dispatch with it — they are one release."
        }
        val outDir = litertJniLibDir.get().asFile
        outDir.deleteRecursively()
        val lib = File(outDir, "arm64-v8a/libLiteRt.so")
        lib.parentFile.mkdirs()
        ZipFile(aar).use { zip ->
            val entry = zip.getEntry("jni/arm64-v8a/libLiteRt.so")
                ?: throw GradleException(
                    "extractLiteRtRuntime: jni/arm64-v8a/libLiteRt.so is missing from ${aar.name}"
                )
            zip.getInputStream(entry).use { input -> lib.outputStream().use { input.copyTo(it) } }
        }
        check(lib.length() == 5_104_832L) {
            "extractLiteRtRuntime: libLiteRt.so is ${lib.length()} bytes, expected 5_104_832 " +
                "(LiteRT 2.1.1, the runtime P1b's device gate measured)."
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(lib.readBytes())
            .joinToString("") { b -> "%02x".format(b) }
        check(digest == "6ddc1b3df38f3f0e039558c023f3bd9ec2f7bec55b67cfd6be4131130481a5d5") {
            "extractLiteRtRuntime: libLiteRt.so sha256 mismatch ($digest) — not the 2.1.1 runtime " +
                "the tier was measured on."
        }
    }
}
// Ordered before the task that actually CONSUMES the jniLibs source — merge*JniLibFolders — and
// not merely preBuild, the lesson extractQnnSkel's comment records one source class over.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(extractLiteRtRuntime) }
tasks.named("preBuild") { dependsOn(extractLiteRtRuntime) }

// (P2-6) THE MEDIATEK DISPATCH: libLiteRtDispatch_MediaTek.so v2.1.1, which LiteRT loads out of
// the directory it is told to scan (filesDir/litert_dispatch/, staged by LiteRtRuntime). It is not
// on Maven — Google Maven's litert group carries no vendor runtime — and v2.1.1's
// litert_npu_runtime_libraries_jit.zip is the LAST release zip that ships the MediaTek pair
// (tools/probes/litertlm-probe/fetch_mediatek_runtime.py, the probe's copy of the same three pins).
// The zip is fetched once into a CACHE OUTSIDE build/ (below), held to its length and sha256, and
// the one member is extracted into the generated ASSETS dir and held to LiteRtRuntime's
// DISPATCH_BYTES and DISPATCH_SHA256 — the zip member's own digest. An asset is packaged as it is; a
// lib/ copy would be stripped into a different file of the same length, which is why this is not
// jniLibs.
//
// THE CACHE (the P2b review's small 2). The zip used to live in build/, so every build after a
// `clean` needed GitHub, and `--offline` could not help: the fetch is this task's own, not a Gradle
// dependency. It lives under the user's home now — ~/.androidbuild, the parent the QNN headers'
// offline tree (tools/fetch_qnn_headers.py) and this machine's other build caches already share —
// so a clean costs nothing, every worktree shares one copy, and an offline build passes whenever
// the cache holds the pinned bytes. The pins are unchanged and apply to the cached copy every time:
// a copy that fails its length or digest is deleted by the check that refuses it, so the next
// build fetches it afresh rather than failing on the same bad bytes forever; a download lands in a
// `.part` beside it and is renamed in only once complete; and an offline build with no cached copy
// fails naming the path to put one.
val litertDispatchZipUrl =
    "https://github.com/google-ai-edge/LiteRT/releases/download/v2.1.1/litert_npu_runtime_libraries_jit.zip"
val litertDispatchZip = File(System.getProperty("user.home"), ".androidbuild/litert-cache/litert_npu_runtime_libraries_jit-2.1.1.zip")
val extractLiteRtDispatch = tasks.register("extractLiteRtDispatch") {
    description = "Fetches LiteRT v2.1.1's NPU runtime zip (cached outside build/) and extracts the MediaTek dispatch into generated assets."
    inputs.property("url", litertDispatchZipUrl)
    outputs.dir(litertDispatchAssetDir)
    val offline = gradle.startParameter.isOffline
    doLast {
        val zip = litertDispatchZip
        if (!zip.isFile || zip.length() != 2_847_687L) {
            check(!offline) {
                "extractLiteRtDispatch: offline, and no cached ${zip.name} at ${zip.parentFile.absolutePath}. " +
                    "Run once with network access, or copy the v2.1.1 release zip there."
            }
            zip.parentFile.mkdirs()
            val part = File(zip.parentFile, zip.name + ".part")
            uri(litertDispatchZipUrl).toURL().openStream().use { input ->
                part.outputStream().use { input.copyTo(it) }
            }
            zip.delete()
            check(part.renameTo(zip)) {
                "extractLiteRtDispatch: the download could not be moved into the cache at ${zip.absolutePath}"
            }
        }
        check(zip.length() == 2_847_687L) {
            val got = zip.length()
            zip.delete()
            "extractLiteRtDispatch: ${zip.name} is $got bytes, expected 2_847_687 (the v2.1.1 " +
                "release asset). The cached copy was removed; re-run to fetch it again."
        }
        val zipDigest = MessageDigest.getInstance("SHA-256")
            .digest(zip.readBytes())
            .joinToString("") { b -> "%02x".format(b) }
        check(zipDigest == "4d6433eceb0e9c97388e5d10af9c71a1f97cf93f4a0f21acc492b97a342d45c3") {
            zip.delete()
            "extractLiteRtDispatch: ${zip.name} sha256 mismatch ($zipDigest) — the cached copy was " +
                "removed; re-run to fetch it again. If it fails again, the release asset moved."
        }
        val outDir = litertDispatchAssetDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()
        val dispatch = File(outDir, "libLiteRtDispatch_MediaTek.so")
        ZipFile(zip).use { z ->
            val entry = z.getEntry("mediatek_runtime/src/main/jni/arm64-v8a/libLiteRtDispatch_MediaTek.so")
                ?: throw GradleException(
                    "extractLiteRtDispatch: the MediaTek dispatch is missing from ${zip.name}"
                )
            z.getInputStream(entry).use { input -> dispatch.outputStream().use { input.copyTo(it) } }
        }
        check(dispatch.length() == 409_728L) {
            "extractLiteRtDispatch: libLiteRtDispatch_MediaTek.so is ${dispatch.length()} bytes, " +
                "expected 409_728 — LiteRtRuntime.DISPATCH_BYTES, which the stage verifies at arm time."
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(dispatch.readBytes())
            .joinToString("") { b -> "%02x".format(b) }
        check(digest == "9e963c56a65b6146b0e94aed82dd0f73dbaee6805fc6ae090580565b57680706") {
            "extractLiteRtDispatch: libLiteRtDispatch_MediaTek.so sha256 mismatch ($digest) — " +
                "LiteRtRuntime.DISPATCH_SHA256 is the zip member's, which the stage verifies at arm time."
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(extractLiteRtDispatch) }
tasks.named("preBuild") { dependsOn(extractLiteRtDispatch) }

// (P2-6; design §2.3's last paragraph) THE MERGED MANIFEST'S MEDIATEK SET, pinned over the BUILD
// OUTPUT — the manifest every APK and AAB actually carries — not over the source file. The source
// declares libneuronusdk_adapter.mtk.so alone; what this guards is everything that could add to it
// on the way: a dependency whose own manifest declares the rest of the set (the litert AAR's does:
// libneuron_sys_util.mtk.so — whose NeuroPilot magic-number read cost every P0 run a 5 s binder wait
// on the cold arm, sheet §4b — libneuronusdk_adapter.9.mtk.so and libneuron_adapter_mgvi.so). So the
// guard is also a guard on the tier's cold-arm time.
//
// A TRANSFORM of every variant's MERGED_MANIFEST, so no APK or bundle can be packaged without it
// running: it reads the merged manifest (comments stripped — a commented-out element is not a
// declaration), requires the adapter declared exactly once and the other three absent, and hands the
// manifest on byte for byte. The probe app's stripAarMediatekDeclarations is its model; this one
// strips nothing, because the product never merges that AAR in the first place — it only refuses.
abstract class VerifyMediatekNativeLibraries : DefaultTask() {
    @get:InputFile abstract val mergedManifest: RegularFileProperty
    @get:OutputFile abstract val checkedManifest: RegularFileProperty
    @get:Input abstract val declared: Property<String>
    @get:Input abstract val undeclared: ListProperty<String>

    @TaskAction
    fun verify() {
        val bytes = mergedManifest.get().asFile.readBytes()
        val text = String(bytes, Charsets.UTF_8)
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val names = Regex("<uses-native-library\\b[^>]*?android:name\\s*=\\s*\"([^\"]+)\"")
            .findAll(text).map { it.groupValues[1] }.toList()
        check(names.count { it == declared.get() } == 1) {
            "the merged manifest must declare ${declared.get()} exactly once — the Neuron adapter " +
                "the MediaTek tier reaches the APU through — but its native libraries are $names"
        }
        val present = undeclared.get().filter { it in names }
        check(present.isEmpty()) {
            "the merged manifest declares $present — a dependency's manifest merged them in. None " +
                "may ship: libneuron_sys_util.mtk.so costs a 5 s binder wait on every cold arm " +
                "(sheet §4b), and the others are not the driver the tier was measured on."
        }
        checkedManifest.get().asFile.writeBytes(bytes)
    }
}

androidComponents {
    onVariants { variant ->
        val verifyMediatek = project.tasks.register<VerifyMediatekNativeLibraries>(
            "verifyMediatekNativeLibraries${variant.name.replaceFirstChar { it.uppercase() }}"
        ) {
            declared.set("libneuronusdk_adapter.mtk.so")
            undeclared.set(
                listOf(
                    "libneuron_sys_util.mtk.so",
                    "libneuronusdk_adapter.9.mtk.so",
                    "libneuron_adapter_mgvi.so",
                )
            )
        }
        variant.artifacts.use(verifyMediatek)
            .wiredWithFiles(
                VerifyMediatekNativeLibraries::mergedManifest,
                VerifyMediatekNativeLibraries::checkedManifest,
            )
            .toTransform(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
    }
}

// The Play pack gate (4.2 F4): every bundle build re-proves that the pack payload on disk IS
// the census before AGP packages it. The payload is a BUILD artifact — tools/build_asset_packs.py
// build assembles every #group_ variant the census names (twelve since 2026-09-24) from the
// measured vendor zips, hash-verifying every byte on the way in and out — so the committed tree
// carries no payload at all, and a bundle built on a machine that never ran the script fails
// HERE with every missing variant named, instead of shipping packs whose targeted variants are
// silently empty.
//
// THE VARIANT DIRECTORY IS NAMED AFTER THE PACK (4.2 F8), and the reason is a rule, not a
// preference: an AAB merges nothing, but bundletool validates that any entry path appearing in
// two modules carries the SAME bytes in both. Both packs used to write
// assets/model#group_<g>/metadata.json — one path, two different documents — and the first
// bundleRelease ever attempted died with "Modules 'npu_small' and 'npu_turbo' contain entry
// 'assets/model#group_soc_7gen4/metadata.json' with different content". The two BINARIES were
// already safe, but only by the turbo_ rename F4 introduced for the SAF import's flat directory;
// metadata.json had no such prefix and nothing before a real bundle build could have said so.
// Naming each pack's directory after the pack retires the whole clash class — no entry under
// assets/npu_small/ can ever share a path with one under assets/npu_turbo/ — instead of adding a
// second prefix and waiting for the third file. Play strips the #group_<g> suffix on delivery,
// so the device sees assets/<packName>/, which is what installFromPack opens (through
// NpuPackFetch.packsFor since P2-4 — the same census parts that name the packs to fetch).
//
// THE PACK TABLE: one row per variant — module, Play device group, encoder bytes, decoder
// bytes. The byte counts are NpuFleetCensus.artifacts' own, restated because a build script
// cannot read the app's classes, and pinned EQUAL to the census by NpuPackLayoutTest (the
// extractQnnSkel fleet-table discipline, one gate over). sha256 of ~7.9 GB per bundle build is
// deliberately NOT taken here: the script's own build step hash-verifies what it writes, the
// app's arrival hash stays the invariant on device, and this gate's job is missing, stale or
// swapped VARIANTS — which exact byte counts catch in milliseconds.
val npuPackDeliveryNames = mapOf(
    "npu_small" to listOf("encoder_qairt_context.bin", "decoder_qairt_context.bin"),
    "npu_turbo" to listOf("turbo_encoder_qairt_context.bin", "turbo_decoder_qairt_context.bin"),
)
// Every length below is the v0.63.0 measurement (2026-09-24, QAIRT 2.50 rebuilds): all ten
// pre-existing rows moved with it, the encoders 11.5-22.1% smaller and the decoders within 0.06%.
val npuPackCensusRows = listOf(
    listOf("npu_small", "soc_8gen3", 113_123_776L, 225_298_736L),
    listOf("npu_small", "soc_8elite_galaxy", 113_091_008L, 225_151_280L),
    listOf("npu_small", "soc_8elite5_galaxy", 113_770_944L, 225_290_544L),
    listOf("npu_small", "soc_7gen4", 115_028_408L, 225_397_032L),
    listOf("npu_turbo", "soc_8gen3", 686_112_520L, 295_856_032L),
    listOf("npu_turbo", "soc_8elite_galaxy", 685_997_832L, 295_765_920L),
    listOf("npu_turbo", "soc_8elite5_galaxy", 687_283_976L, 295_847_840L),
    listOf("npu_turbo", "soc_7gen4", 703_946_504L, 295_917_472L),
    // 8 Gen 2 (SM8550), added 2026-09-22 — device-executed on an S23 Ultra (the v0.62.2 pair).
    listOf("npu_small", "soc_qcs8550", 113_127_872L, 225_298_736L),
    listOf("npu_turbo", "soc_qcs8550", 686_108_424L, 295_847_840L),
    // 8 Gen 1 (SM8450), added 2026-09-24 at v0.63.0 — the sixth family, HTP v69.
    listOf("npu_small", "soc_8gen1", 111_915_456L, 223_562_032L),
    listOf("npu_turbo", "soc_8gen1", 681_574_152L, 294_692_768L),
)
// (P2-5, the MediaTek APU tier) THE UNTARGETED RULE, a second rule beside the targeted one above
// and weakening nothing of it. A MediaTek pair is 1.88 GB — over Play's 1.5 GB per-pack cap — so it
// arrives in TWO packs, and they are UNTARGETED modules of the family's own: bundletool's
// DeviceGroupParityValidator requires every module with device-group targeting to support the same
// set of groups, which a MediaTek pair can never share with npu_small/npu_turbo. So each module
// holds ONE payload directory named after the pack, assets/<module>/ (delivered as itself), carrying
// exactly its part's entry — plus metadata.json in the pair's first part, which lists both — plus
// the tracked .gitkeep anchor, and no #group_ folder at all. There is no default variant to keep
// empty: nothing targets, and the census gate is what decides which device fetches these.
//
// THE PARTS TABLE: one row per PART — module, census family, the one delivery name it carries, its
// exact bytes, and whether metadata.json rides in it. The values are NpuFleetCensus.artifacts' own
// parts, restated because a build script cannot read the app's classes, and pinned EQUAL to the
// census by NpuPackLayoutTest.
val npuPackPartRows = listOf(
    listOf("npu_turbo_mt6989_enc", "mt6989", "turbo_encoder_qairt_context.bin", 1_302_606_488L, true),
    listOf("npu_turbo_mt6989_dec", "mt6989", "turbo_decoder_qairt_context.bin", 584_862_184L, false),
)
val verifyNpuPacks = tasks.register("verifyNpuPacks") {
    description = "Verifies every NPU asset-pack variant and untargeted part against the census " +
        "byte counts and that both default variants are EMPTY. Runs before every bundle packaging task."
    doLast {
        val problems = mutableListOf<String>()
        for (row in npuPackCensusRows) {
            val module = row[0] as String
            val group = row[1] as String
            val encoderBytes = row[2] as Long
            val decoderBytes = row[3] as Long
            val names = npuPackDeliveryNames.getValue(module)
            val variantDir = rootProject.file("$module/src/main/assets/$module#group_$group")
            if (!variantDir.isDirectory) {
                problems += "$module: $module#group_$group is MISSING"
                continue
            }
            val listed = (variantDir.listFiles() ?: emptyArray()).map { it.name }.sorted()
            val expected = (names + "metadata.json").sorted()
            if (listed != expected) {
                problems += "$module/$module#group_$group: carries $listed; a pack variant is " +
                    "exactly $expected"
                continue
            }
            val encoder = File(variantDir, names[0])
            if (encoder.length() != encoderBytes) {
                problems += "$module/$module#group_$group: ${names[0]} is ${encoder.length()} B, " +
                    "the census says $encoderBytes"
            }
            val decoder = File(variantDir, names[1])
            if (decoder.length() != decoderBytes) {
                problems += "$module/$module#group_$group: ${names[1]} is ${decoder.length()} B, " +
                    "the census says $decoderBytes"
            }
            val meta = try {
                JsonSlurper().parse(File(variantDir, "metadata.json")) as? Map<*, *>
            } catch (bad: Exception) {
                null
            }
            when {
                meta == null ->
                    problems += "$module/$module#group_$group: metadata.json is not parseable JSON"
                meta["packGroup"] != group ->
                    problems += "$module/$module#group_$group: metadata.json names packGroup " +
                        "'${meta["packGroup"]}' — the variant dir and its own metadata disagree"
            }
        }
        // (P2-5) THE UNTARGETED PARTS: one payload directory per module, exactly its part's entry
        // (+ metadata.json in part 1) + the anchor, and nothing that looks like a group variant.
        for (row in npuPackPartRows) {
            val module = row[0] as String
            val familyId = row[1] as String
            val name = row[2] as String
            val bytes = row[3] as Long
            val carriesMetadata = row[4] as Boolean
            val assetsDir = rootProject.file("$module/src/main/assets")
            val payloadDir = File(assetsDir, module)
            if (!payloadDir.isDirectory) {
                problems += "$module: assets/$module/ is MISSING"
                continue
            }
            val beside = (assetsDir.listFiles() ?: emptyArray()).map { it.name }.filter { it != module }
            if (beside.isNotEmpty()) {
                problems += "$module: an untargeted module carries assets/$module/ and nothing " +
                    "else, but it also carries $beside"
            }
            val listed = (payloadDir.listFiles() ?: emptyArray()).map { it.name }.sorted()
            val expected = (listOf(name, ".gitkeep") +
                if (carriesMetadata) listOf("metadata.json") else emptyList()).sorted()
            if (listed != expected) {
                problems += "$module/assets/$module: carries $listed; this part is exactly $expected"
                continue
            }
            val part = File(payloadDir, name)
            if (part.length() != bytes) {
                problems += "$module/assets/$module: $name is ${part.length()} B, the census says $bytes"
            }
            if (carriesMetadata) {
                val meta = try {
                    JsonSlurper().parse(File(payloadDir, "metadata.json")) as? Map<*, *>
                } catch (bad: Exception) {
                    null
                }
                when {
                    meta == null ->
                        problems += "$module/assets/$module: metadata.json is not parseable JSON"
                    meta["familyId"] != familyId ->
                        problems += "$module/assets/$module: metadata.json names familyId " +
                            "'${meta["familyId"]}' — the module is $familyId's own"
                }
            }
        }
        // THE EMPTY-DEFAULT RULE (the research §6 CI check). Play cannot be told to deliver
        // nothing: an unmatched device can never be prevented from receiving the default
        // variant, so the default must contain nothing worth receiving — a bundle whose
        // default variant gained content would hand those bytes to every unmatched device.
        //
        // (4.2 F8) The default variant is the EXPLICIT `#group_other` directory, not an
        // unsuffixed sibling. bundletool assigns a group-targeted directory's unsuffixed
        // neighbour an empty DeviceGroupTargeting and then refuses it by name — "Directory
        // 'assets/npu_small' must have exactly one device group, but found []" — so the
        // fallback has to name the group it serves. `other` is bundletool's IMPLICIT group —
        // it must not appear in device_targeting_config.xml, and the bundle block's own
        // default-group line above is what routes unmatched devices into it — which is why
        // this is a spelling change and not a targeting change: the same devices receive the
        // same nothing, and the app still finds no metadata.json and refuses by name.
        // (The default-group line is spelled exactly once in this file, and a pin says so;
        // quoting it again here would answer that pin from a comment.)
        for (module in npuPackDeliveryNames.keys) {
            val defaultDir = rootProject.file("$module/src/main/assets/$module#group_other")
            val extras = (defaultDir.listFiles() ?: emptyArray()).map { it.name }
                .filter { it != ".gitkeep" }
            if (extras.isNotEmpty()) {
                problems += "$module: the DEFAULT variant (assets/$module#group_other/) must " +
                    "stay EMPTY but carries $extras"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "verifyNpuPacks: the pack payload is not the census — a bundle built now " +
                    "would ship wrong, stale or missing variants.\n  " +
                    problems.joinToString("\n  ") +
                    "\n  Assemble the payload with: python tools/build_asset_packs.py build"
            )
        }
        logger.lifecycle(
            "verifyNpuPacks: all ${npuPackCensusRows.size} targeted pack variants and " +
                "${npuPackPartRows.size} untargeted parts match the census byte counts, and both " +
                "default variants are empty."
        )
    }
}
// Wired before bundle PACKAGING only. assembleDebug must NOT depend on this gate: an APK build
// carries no packs at all, and the everyday build must never demand 7.9 GB of payload.
tasks.matching { it.name.startsWith("package") && it.name.endsWith("Bundle") }
    .configureEach { dependsOn(verifyNpuPacks) }

// (4.4.0, the 2026-09-10 amendment; ALL SEVEN packs since 4.5.0 Task 2) The SAME gate, seven packs
// over: each preview pack's four files are a BUILD artifact placed by `python
// tools/build_asset_packs.py preview`, and an AAB whose payload for ANY of them is missing or stale
// would ship a language that can never install — invisible in every APK build, because an APK
// carries no packs at all. The 2026-09-12 ruling makes that the likeliest way to fail the owner:
// all six new languages must be fetchable on the internal track, and a language whose 71 MB never
// made it into the bundle looks, on a device, exactly like a language that is broken.
//
// THE PLACEMENT TABLE: one row per pinned file, name and byte count, grouped by pack. The literals
// are each catalogue row's own, restated because a build script cannot read the app's classes, and
// pinned EQUAL to the catalog by PreviewPackLayoutTest (the verifyNpuPacks discipline).
// sha256 of 494 MB per bundle build is deliberately NOT taken here: the script hash-verifies what
// it places, StreamingPackInstall.verify re-hashes on the device before a byte is installed, and
// this gate's job is a MISSING or wrong-sized payload, which exact byte counts catch instantly.
//
// TWO PACKS CANNOT BE TOLD APART BY SIZE ALONE, and this gate knows it does not try to: the Korean
// repo's chunk-32 and chunk-64 exports ship decoder and joiner files byte-identical to the
// chunk-16 ones, and every row's joiner is within a kilobyte of two others'. Byte counts catch the
// missing and the truncated; the digest, taken by the script and again on the device, is what
// catches the WRONG file. Neither gate is the other's substitute.
//
// Every payload directory is UNTARGETED — one variant, every device — so unlike the NPU gate there
// is no empty-default rule to hold here: there is no default variant to keep empty, and the
// .gitkeep anchor is the only non-payload entry any of these directories may carry.
val previewPackPayloads = mapOf(
    "preview_en" to listOf(
        listOf("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 71_083_163L),
        listOf("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_307_236L),
        listOf("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 259_335L),
        listOf("tokens.txt", 5_048L),
    ),
    "preview_fr" to listOf(
        listOf("encoder-epoch-29-avg-9-with-averaged-model.int8.onnx", 126_655_903L),
        listOf("decoder-epoch-29-avg-9-with-averaged-model.int8.onnx", 1_307_157L),
        listOf("joiner-epoch-29-avg-9-with-averaged-model.int8.onnx", 259_572L),
        listOf("tokens.txt", 4_819L),
    ),
    "preview_de" to listOf(
        listOf("encoder-epoch-30-avg-5.int8.onnx", 70_133_342L),
        listOf("decoder-epoch-30-avg-5.int8.onnx", 540_689L),
        listOf("joiner-epoch-30-avg-5.int8.onnx", 259_417L),
        listOf("tokens.txt", 5_086L),
    ),
    "preview_ru" to listOf(
        listOf("encoder.int8.onnx", 26_214_060L),
        listOf("decoder.onnx", 2_093_080L),
        listOf("joiner.int8.onnx", 259_417L),
        listOf("tokens.txt", 6_388L),
    ),
    "preview_id" to listOf(
        listOf("encoder-iter-100000-avg-15-chunk-32-left-256.int8.onnx", 70_103_186L),
        listOf("decoder-iter-100000-avg-15-chunk-32-left-256.int8.onnx", 540_688L),
        listOf("joiner-iter-100000-avg-15-chunk-32-left-256.int8.onnx", 259_417L),
        listOf("tokens.txt", 5_403L),
    ),
    "preview_ko" to listOf(
        listOf("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 70_133_869L),
        listOf("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_544_210L),
        listOf("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_270_777L),
        listOf("tokens.txt", 20_844L),
    ),
    "preview_zh" to listOf(
        listOf("encoder-epoch-99-avg-1.int8.onnx", 42_980_793L),
        listOf("decoder-epoch-99-avg-1.int8.onnx", 3_486_740L),
        listOf("joiner-epoch-99-avg-1.int8.onnx", 3_228_485L),
        listOf("tokens.txt", 56_317L),
    ),
)
val verifyPreviewPack = tasks.register("verifyPreviewPack") {
    description = "Verifies all ${previewPackPayloads.size} preview asset packs' payloads against " +
        "the streaming catalog's byte counts. Runs before every bundle packaging task."
    doLast {
        val problems = mutableListOf<String>()
        // EVERY pack is checked before anything is reported, rather than failing on the first:
        // the person reading this is about to re-run a placement that takes minutes, and one
        // message naming all seven states is worth six re-runs.
        for ((module, rows) in previewPackPayloads) {
            val payloadDir = rootProject.file("$module/src/main/assets/$module")
            if (!payloadDir.isDirectory) {
                problems += "$module: assets/$module/ is MISSING"
                continue
            }
            val listed = (payloadDir.listFiles() ?: emptyArray()).map { it.name }.sorted()
            val expected = (rows.map { it[0] as String } + ".gitkeep").sorted()
            if (listed != expected) {
                problems += "$module/assets/$module: carries $listed; the pack is exactly $expected"
                continue
            }
            for (row in rows) {
                val name = row[0] as String
                val bytes = row[1] as Long
                val placed = File(payloadDir, name)
                if (placed.length() != bytes) {
                    problems += "$module/assets/$module: $name is ${placed.length()} B, " +
                        "the catalog says $bytes"
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "verifyPreviewPack: a preview pack's payload is not the catalog — a bundle " +
                    "built now would ship a language that can never install.\n  " +
                    problems.joinToString("\n  ") +
                    "\n  Place the payload with: python tools/build_asset_packs.py preview"
            )
        }
        val files = previewPackPayloads.values.sumOf { it.size }
        logger.lifecycle(
            "verifyPreviewPack: all $files files across ${previewPackPayloads.size} preview " +
                "packs match the streaming catalog's byte counts."
        )
    }
}
// Its own clause, for the same reason the include line is its own statement: the NPU gate's
// single wiring line stays exactly what it was, and each gate says when it runs.
tasks.matching { it.name.startsWith("package") && it.name.endsWith("Bundle") }
    .configureEach { dependsOn(verifyPreviewPack) }

// (4.4.0, Task 2b) The THIRD gate, one pack over: the read-aloud voice's archive is a BUILD
// artifact placed by `python tools/build_asset_packs.py tts`, and an AAB whose tts_kokoro payload
// is missing or stale would ship a voice that can never install — which is precisely the
// 2026-09-08 production incident, reproduced deliberately and invisibly, because an APK build
// carries no packs at all and every JVM test would stay green.
//
// THE PLACEMENT TABLE: one row, name and byte count. The literals are TtsModelManager.TAR_NAME
// and TAR_BYTES, restated because a build script cannot read the app's classes, and pinned EQUAL
// to them by TtsPackLayoutTest (the verifyNpuPacks discipline). sha256 of 350 MB per bundle build
// is deliberately NOT taken here: the script hash-verifies what it places against the app's own
// KNOWN_GOOD_TAR_SHA256 literal, TtsModelManager.verifyExtractInstall re-hashes on the device
// before a byte is extracted, and this gate's job is a MISSING or wrong-sized payload, which an
// exact byte count catches instantly.
//
// The payload directory is UNTARGETED — one variant, every device — so, as with the previewer,
// there is no empty-default rule to hold here: there is no default variant to keep empty, and the
// .gitkeep anchor is the only non-payload entry the directory may carry.
val ttsPackFiles = listOf(
    listOf("kokoro-multi-lang-v1_0.tar.bz2", 349_906_910L),
)
val verifyTtsPack = tasks.register("verifyTtsPack") {
    description = "Verifies the tts_kokoro asset pack's payload against TtsModelManager's " +
        "archive byte count. Runs before every bundle packaging task."
    doLast {
        val problems = mutableListOf<String>()
        val payloadDir = rootProject.file("tts_kokoro/src/main/assets/tts_kokoro")
        if (!payloadDir.isDirectory) {
            problems += "tts_kokoro: assets/tts_kokoro/ is MISSING"
        } else {
            val listed = (payloadDir.listFiles() ?: emptyArray()).map { it.name }.sorted()
            val expected = (ttsPackFiles.map { it[0] as String } + ".gitkeep").sorted()
            if (listed != expected) {
                problems += "tts_kokoro/assets/tts_kokoro: carries $listed; the pack is exactly " +
                    "$expected"
            } else {
                for (row in ttsPackFiles) {
                    val name = row[0] as String
                    val bytes = row[1] as Long
                    val placed = File(payloadDir, name)
                    if (placed.length() != bytes) {
                        problems += "tts_kokoro/assets/tts_kokoro: $name is ${placed.length()} B, " +
                            "TtsModelManager says $bytes"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "verifyTtsPack: the voice pack's payload is not the pinned archive — a bundle " +
                    "built now would ship a read-aloud voice that can never install.\n  " +
                    problems.joinToString("\n  ") +
                    "\n  Place the payload with: python tools/build_asset_packs.py tts"
            )
        }
        logger.lifecycle(
            "verifyTtsPack: the voice pack's ${ttsPackFiles.size} file matches " +
                "TtsModelManager's archive byte count."
        )
    }
}
// Its own clause, like the previewer's: the two older gates' single wiring lines stay exactly
// what they were, and each gate says when it runs.
tasks.matching { it.name.startsWith("package") && it.name.endsWith("Bundle") }
    .configureEach { dependsOn(verifyTtsPack) }

dependencies {
    // On-device TTS (Track F): sherpa-onnx runs Kokoro-82M on CPU (fetched above). arm64
    // native payload only reaches the APK because of the abiFilters above.
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))

    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // tar.bz2 extraction for the TTS voice archive (Track F)
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // QNN/QAIRT runtime (4.0 NPU tier; the fleet since 4.2 F2): libQnnHtp.so, libQnnSystem.so,
    // and the census families' HTP stubs and skels. These are dlopen()ed by our own libqnnasr.so
    // — never linked at build time — so no import library is needed, only the headers (fetched
    // by fetchQnnHeaders above, never committed). The version MUST stay in step with the pinned
    // literal in tools/fetch_qnn_headers.py. Most of the AAR's payload is excluded in
    // packaging.jniLibs above; see the rule there.
    implementation("com.qualcomm.qti:qnn-runtime:2.50.0")
    // 4.1 L6: extractQnnSkel resolves the SAME artifact through its own configuration to pull
    // the census families' skels out of the AAR (see the task above the dependencies block).
    // The restated coordinate is pinned equal to the line above by NpuSkelPackagingTest.
    qnnSkelSource("com.qualcomm.qti:qnn-runtime:2.50.0")

    // (P2-6, the MediaTek APU tier) libLiteRt.so's source: the litert AAR, through its OWN
    // configuration and never as an implementation dependency — see extractLiteRtRuntime for why
    // (its manifest's MediaTek declarations, its transitive graph). The one coordinate of it, and
    // LiteRtPackagingTest holds its version equal to the dispatch zip's release tag.
    litertRuntime("com.google.ai.edge.litert:litert:2.1.1")

    // Play Asset Delivery (4.2 F5): the on-demand fetch of the two NPU pack modules the F4
    // bundle declares. The pure state machine (NpuPackFetch) mirrors AssetPackStatus /
    // AssetPackErrorCode as documented constants, and NpuPackFetchTest asserts the mirror
    // against THIS library's own classes — so a version bump that renumbers either enum fails
    // a JVM test rather than shipping a silent remap.
    implementation("com.google.android.play:asset-delivery-ktx:2.3.0")

    // (4.2 F8) THE RELEASE-ONLY CONSEQUENCE of the line above, and it is here because it has
    // exactly one cause. asset-delivery-ktx drags `androidx.fragment:fragment:1.1.0` onto the
    // classpath — directly, and again through play-services-basement:18.4.0 — and those are the
    // ONLY two paths to androidx.fragment in this graph (verified against the resolved
    // releaseRuntimeClasspath; `main` has no path to fragment at all).
    //
    // WHAT ACTUALLY FAILED, stated as what it was rather than as the defect the check is named
    // after. androidx.activity ships a FATAL lint check, InvalidFragmentVersionForActivityResult,
    // that fires whenever androidx.fragment BELOW 1.3.0 is on the classpath and ActivityResult
    // APIs are called. It keys on the CLASSPATH VERSION, not on our code — and the underlying
    // defect it is named for (FragmentActivity mishandling onRequestPermissionsResult) cannot
    // reach this app at all: there is no Fragment, no FragmentActivity and no appcompat here, and
    // both activities lint flagged are plain ComponentActivity. The reason to fix it is therefore
    // the plain one, which is sufficient on its own: this is a FATAL check, it runs in
    // lintVitalRelease and NOT in assembleDebug, so adding the Play client made every RELEASE
    // build fail on a branch whose acceptance is a store upload — and nothing before F8's first
    // release build could have said so.
    //
    // The version is raised rather than the check silenced or the transitive excluded. Silencing
    // (a baseline, or abortOnError=false) turns off a gate for the whole app to get past one
    // stale coordinate; excluding trades a build failure for a runtime NoClassDefFoundError,
    // because play-services-basement genuinely references fragment classes. Raising a stale
    // transitive to its contemporary release is the only option that neither hides a check nor
    // risks the app. 1.8.5 is the fragment release contemporary with activity 1.9.3 /
    // lifecycle 2.8.7 above; compileSdk 36 clears its floor.
    //
    // WHAT THIS SHIPS: nothing. Measured, because "it is only a version raise" is exactly the
    // kind of claim that turns out to be false. (1) ZERO androidx.fragment classes survive R8 in
    // the release dex — the app calls none of it, so the library lives on the compile and lint
    // classpath and nowhere else. (2) The merged RELEASE manifest is BYTE-IDENTICAL with and
    // without this line (same sha256, built both ways). In particular the profileinstaller
    // receiver and startup initializer in that manifest are NOT ours: profileinstaller:1.3.1 was
    // already on the release classpath through androidx.core:core-ktx -> lifecycle-runtime-android
    // and through compose.ui/activity, and viewpager/loader arrived with fragment 1.1.0 long
    // before this line existed. This raise adds no shipped surface of any kind.
    implementation("androidx.fragment:fragment:1.8.5")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Accompanist for permissions
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // OkHttp is PINNED TO 4.12.0 — do not "upgrade" it to 5.x without also moving Kotlin.
    //
    // okhttp 5.4.0's Android artifact depends on kotlin-stdlib 2.2.21, and Gradle's conflict
    // resolution then forces the whole project to 2.2.21. That breaks this project's Kotlin
    // 2.0.21 compiler outright: `compileDebugKotlin` fails with "metadata is 2.2.0, expected
    // 2.0.0". Verified by `:app:dependencies`, which shows `kotlin-stdlib:2.0.21 -> 2.2.21`.
    // 4.12.0 leaves the stdlib at 2.0.21 and has everything needed here, including WebSocket
    // support for the streaming work.
    //
    // Also do NOT add okhttp-coroutines: it pulls kotlinx-coroutines 1.11.0, whose metadata has
    // the same problem. The Call.await() bridge in net/HttpTransport.kt is hand-rolled for that
    // reason.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
