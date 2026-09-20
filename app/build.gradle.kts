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
            buildStagingDirectory = file("C:/Users/bastr/.androidbuild/WhisperEverywhere/cxx-staging")
        }
    }

    defaultConfig {
        applicationId = "com.whispereverywhere"
        minSdk = 26
        targetSdk = 36
        versionCode = 103
        versionName = "4.11.1"  // THE SPEAKER CAP BINDS THE ANSWER, NOT ONLY THE SEEDS (Tab S10+, 4.11.0/102, 2026-09-20 01:35: `speaker-recluster: n=505 clusters=11 confirmed=11`). The retrospective pass capped its SEEDS at 600 and said so in its own KDoc; nothing capped its ANSWER. SpeakerAssigner reseeds the tracker with one LIVE voice per cluster, and the online path may open a new speaker only while fewer than SpeakerTracker.MAX_SPEAKERS (8) are live - so a pass answering eleven does not merely overcount, it permanently retires that tracker's ability to find anyone new for the rest of the session: every later unheard voice is handed to the closest voice it already knows and, per that line's own comment, teaches it nothing. The tracker's own corrective merge in endChunk is inert at the same moment, because it skips CONFIRMED voices and a reseed marks every voice confirmed. Retrospective labelling still recovers - recluster reads the stored fingerprint vectors and never tracker state - so what degrades is the LIVE label between passes, which is also the one that drives paragraph breaks in text typed into another app. 103 makes the cap a parameter of recluster, defaulted to SpeakerTracker.MAX_SPEAKERS and passed by the assigner as the tracker's OWN maxSpeakers, so the two halves cannot disagree about how many people a session may hold. Over the cap the speakers who SPOKE LONGEST keep their identity and the rest fall into the absorption loop every sub-bar cluster already goes through, so no window is dropped and no label is lost. WHAT THAT EARNS, STATED EXACTLY, because the neighbouring claim is easy to make and wrong: AT the cap the opening guard is false too, so capping to 8 does NOT give the tracker back the ability to open a ninth voice. What it gives is that the tracker's live count honours the bound it documents about itself, that the label space handed to the panel stops drifting upward, and that because every pass re-decides WHICH speakers survive, a person who out-speaks the weakest survivor takes that slot at the next pass instead of being locked out for the session. AND WHAT IT COSTS, on material the cap is genuinely too small for: nine real people used to come back as nine clusters, all separated, with only the online path jammed - now the ninth is merged into whoever they most resemble. That is the cap's price rather than the trim's, and MAX_SPEAKERS is the ONE place to change it; the session that prompted this held one or two real voices and answered eleven, which is the over-split the other way. ONE MORE DEFECT CAME WITH IT AND IS FIXED IN THE SAME BREATH: Cluster.longest is the seed set SpeakerTracker.reseed rebuilds a live voice from, and it was computed AFTER absorption - but absorption is by construction a merge of two things the pass just proved are NOT one voice (step 3 already merged every pair reaching RECLUSTER_SIM 0.30), so an absorbed cluster's windows were becoming the survivor's own identity evidence. A sub-bar leftover is short and rarely won the duration sort; a cluster the CAP trims cleared the 6 s mass bar and lost only on relative mass, so its long windows would routinely have taken the survivor's seed slots and made the tracker answer ~1.0 to the wrong person - credit would then have evicted the survivor from its own id. Identity is now snapshotted from a cluster's OWN pre-absorption windows, which also keeps the file's own rule that a 1.0 s window is labelled and never a voter. The absorbed windows still take the survivor's LABEL; that disposal rule is unchanged. Nothing else moves: 102's timing layer, its text guarantees and every acceptance row stand. THE TABLET PASSED AO11 on the way to finding this: one voice stayed one speaker across nine chunks, so the bisection invented nobody; windows ran p50 2.2 s and p95 3.5 s with only 4 of 1,240 over the 4.0 s cut; embedMs was p50 320 and worst 600 against the 3,000 ms fence; and four stop taps drained in 178-448 ms, all settled (docs/measurements/2026-09-20-tab-411-timing-layer.md). 102 IS SPENT - it was sideloaded onto the Tab S10+ at 2026-09-20 00:55 and the five sessions this fix comes from were run on it - so the code takes the next integer and the name takes a PATCH, because a cap that binds is a fix to 4.11.0 rather than a capability 4.11.0 lacked. Previous: THE TIMING LAYER: A SPEAKER CHANGE CAN LAND INSIDE A CHUNK (owner, 2026-09-19, his controlled 3 min 20 s run of 4.10.1/101 on the Z Fold6 - "after about two minutes they just stopped, and everything just becomes one speaker"). The tracker was never wrong in that session: ids 2 and 3 alternated to the end and the reclusterer found three confirmed clusters. What collapsed was the GRANULARITY AT WHICH TEXT COULD CARRY A LABEL. Hard-cut media has no pauses, so the standalone VAD 101 gave the NPU tier returned ONE 9-15 s segment per chunk, so windows=1, so the chunk-level rule gave fifteen seconds of two people one name. 102 retires that ceiling on both tiers, by making every tier say WHEN it said each piece of text. ON THE CPU TIERS (small/medium/turbo Q8) whisper.cpp is asked for `params.token_timestamps` and the JNI exports a `[t0cs, t1cs, byteStart, byteEnd]` quad PER TOKEN beside the geometry it already exported, so a fingerprint window may now end at a WORD: any stretch still spanning 4.0 s (`2 * LONG_SEGMENT_SECONDS`) is bisected at the nearest token edge, recursively, and the segment's text is cut with it so the label and the words move together. ON THE NPU TIER the QNN decoder stops being told `<|notimestamps|>` and stops having the timestamp range masked - it always had those 1,501 slots in its vocabulary - and `NpuSentences` parses the emitted pairs into sentence bounds, so the VAD route makes ONE WINDOW PER SENTENCE instead of one per chunk: session 7's `segs=1 windows=1` becomes `segs=1 windows=4`, which is the single line on the device that says the fix is live. Nothing downstream moved - SpeakerTracker, SpeakerReclusterer, SpeakerRuns, SpeakerLabels and TranscriptSink all key on window indices, which is what made the layer affordable. THE TEXT GUARANTEE IS KEPT ON CPU AND KNOWINGLY TRADED ON NPU, and the asymmetry is deliberate: on CPU timing is additive (no logit and no segmentation changes, `result += seg` stays the sole author of the returned bytes, a segment whose token walk cannot reproduce its own text drops its quads rather than rebuild them, and SegmentGeometryPinTest fails the build if that stops being true), while on NPU dropping `<|notimestamps|>` RE-CONDITIONS the decode and each emitted timestamp spends one of the 197 budget positions, so a long chunk can truncate at a different token than it did at 4.10.1. There is no version of the fix that keeps that tier bit-identical; one label for a 15 s chunk is the worse transcript, so the spec wins and the acceptance rows are scoped to match - unchanged text asked of CPU, sane and complete text asked of NPU. One guard was re-based rather than left alone: `qnn_asr.cpp`'s 4.3.1 repetition cut histogrammed the last 32 GENERATED ids, and timestamps are ever-increasing singletons, so a `<|t|><|t|> Thank you.` loop would have pushed both `distinct` past CYCLE_MAX_DISTINCT and the entropy past ENTROPY_THOLD and disarmed the trip exactly when a runaway needed it; the window now holds 32 TEXT ids and the last-rung cut drops to that window's own start, and on a stream with no timestamps it is byte-for-byte the pre-4.11 guard. `dtw_token_timestamps` IS STILL NEVER SET - whisper.cpp gates the new-segment callback on `!dtw_token_timestamps`, so enabling DTW would silently kill the live words strip with no error anywhere; a pin asserts the flag is absent from the whole translation unit and an acceptance row asks a device to prove the strip still fills. 101 = 4.10.1 is spent: the owner installed it on his Z Fold6 and ran the 2026-09-19 20:37-20:41 session on it, so only a higher code replaces it, and the name takes a MINOR because a label that can land inside a chunk is a new capability rather than a fix to 4.10.1's. Previous: SPEAKER LABELS REACH THE NPU TIER (owner, 2026-09-19, testing 4.10.0/100 from the internal track on his Z Fold6: "it doesn't seem like I'm getting any speaker changes at all"). The cause was a design gap, not a defect in the tier: every speaker window came from the whisper.cpp geometry `transcribeRaw` exports, and the NPU arm runs its own encoder and decoder on the HTP with no whisper.cpp VAD anywhere in it, so `lastGeometry` answered null and the assigner was never called. 101 gives that tier a VAD of its own - one segmenter in the JNI with two callers, `vadSegmentsOf` on the speaker thread (~60 ms a chunk) - fingerprints its speech windows with the same tracker, the same gates and the same retrospective second look as the CPU tiers, and gives the chunk ONE speaker: the window holding the most SPEECH, ties to the earliest. It is coarser there and says so: the QNN decoder exposes no token or sentence timestamps, so a chunk's text cannot be split between two voices and a change lands on a 6-8 s chunk boundary instead of a sentence. The owner ruled that granularity sufficient ("at least that would be good enough"). THE CPU TIERS DO NOT MOVE - the route is gated on what the backend CAN publish rather than on what one read returned, and four tests fail if the per-window path changes. 100 = 4.10.0 went to the INTERNAL TRACK on 2026-09-19 and is spent there. Previous: SPEAKER LABELS ON COMMITTED TEXT (owner, 2026-09-18: "we should be able to detect when a new speaker or if a previous speaker was speaking and switch between speaker one, paragraph, then speaker two ... If there's only one speaker then we keep that"; "live preview can just stay exactly as it is"). WHAT A USER SEES, and the first half is the half that matters: with ONE voice the output is 4.9's byte for byte - no labels, no extra paragraphs, nothing. With two or more, the transcript panel breaks a paragraph at every speaker change and starts each one `Speaker N:`, the FIRST paragraph relabelled `Speaker 1:` the moment a second voice is CONFIRMED - the panel is rewritten from the session start, because the panel's text is ours to rewrite. The live preview strip and every cloud session are untouched. Text typed into another app's field gets the paragraph breaks and NEVER the labels, the owner's ruling that a text field is not a transcript. TWO SETTINGS, and their defaults are the whole of the policy: "Detect speakers" defaults ON (off restores 4.9 everywhere from the next recording and never loads the model at all); "Speaker labels in copied and saved text" defaults OFF - the clipboard and a saved transcript always get the paragraph breaks, which are the feature, and the labels only with the switch on, applied at EXPORT time so a transcript already on disk gains them when it is flipped. HOW: the native layer returns the VAD segment geometry and whisper's per-segment timestamps beside the text; a BUNDLED 40.3 MB NVIDIA NeMo TitaNet-small (speaker_titanet_small_16k.onnx, stored uncompressed, chosen by scoring five candidate models offline against 132 dumped segments - CC-BY-4.0, attribution PAID in oss_licenses.html, clearance row PENDING OWNER SIGN-OFF and gating production rather than the internal track) fingerprints one window per sentence on its own `speaker-embed` thread, never the whisper thread, so the commit floors spec S3.3 measured are untouched; a pure SpeakerTracker assigns ids under graded duration gates and the band T_SAME 0.50 / T_NEW 0.30; and every few chunks and once inside the finalize fence a retrospective reclusterer re-clusters the session's fingerprints and relabels the panel through the same remap path - the only correction for the failure the owner met, an online matcher locking onto one id and giving fifty windows of two voices the same number (spike doc session 6, the 03:27 dump: two voices found retrospectively 40/14, one run-on paragraph online). The fingerprint/audio dump that chose the model is DISARMED in this build and its purge made unconditional, so a device that ran a spike build is cleaned at the first launch after the update. AND THE PANEL, from the same sessions: its window is 20,000 characters with an exact fit (the earlier text stopped disappearing) and it follows the newest line only for a reader who is already there, so scrolling back up stays put. 99 = 4.9.1 IS IN PRODUCTION - uploaded by the owner on 2026-09-17 - so it is spent twice over: Play refuses a second upload at the same code and every installed phone already carries it. 100 is the next integer and the name takes a MINOR, because speaker labels are a new capability rather than a fix to one. Previous: PLUS PLAIN MODEL CARDS (owner, 2026-09-17, same session: "The copy for each one of the local models ... It's too technical for people that don't know anything about it. Our headlines are perfectly fine, and just about everything else doesn't need to be shown"): the five bodies are one to three plain sentences, headlines and badges unchanged, and every measurement, twin fact and dated report the old bodies carried moved VERBATIM into the KDoc beside its card - ModelTierCopyTest pins the bodies, the absence of technical tokens, and the KDoc's evidence; the NPU cards keep their measured claims at exactly their scope; same build, same versionCode. A PATCH: THE TRANSCRIPT WINDOW'S RESIZE AND SCROLLBAR (owner, 2026-09-17, on his Tab S10+ on 4.9.0/98: "the resizing arrow, we need to make that a color where we can actually see it. I say red ... while text is transcribing it wants to drag the window around ... touching the resize portion and moving up should resize and lock the window vertically, and the same horizontally, and moving in combination should of course also work ... if we touch the slider, we should be able to slide it up and down"). FOUR THINGS, none of them a model, a pack or a payload. (1) THE PANEL TAKES ITS CHOSEN HEIGHT EMPTY OR FULL: applyPreviewSize set maxHeight on a wrap_content TextView, so the panel was the chosen size only when the text filled it, while handleResizeTouch moved params.y by the height change regardless - on a short panel (a session's first words) the compensation ran without the growth and the whole window walked with the finger. The height is layoutParams.height now, with the width. (2) THE AXIS LOCK in ResizeMath.resize (AXIS_LOCK_RATIO 2.5, about 22 degrees): a clearly vertical drag holds the width, a clearly horizontal one holds the height and moves the window not at all, between is the diagonal; judged on the total drag from the start point per move. (3) THE HANDLE IS RED at full alpha - #FF5252, the literal behind BubbleColours.LIVE_DEFAULT - same 28dp target (ResizeHandlePinTest). (4) THE SCROLLBAR CAN BE GRABBED: TranscriptScrubberView beside each transcript view (the committed text below the handle, the live strip in its own wrapper), the 4.8.0 look at 4dp on a 12dp lane, thumb-relative so it does not jump, owning its gesture so the root drag never sees it, hidden and touch-inert when the text fits, and scrolling its TextView only under a finger so the service's scroll-to-newest is followed, never fought; the TextViews' own scrollbars are none (BubbleScrollbarPinTest rewritten; TranscriptScrubberMath tested). 98 = 4.9.0 was uploaded by the owner on 2026-09-17 and is spent; 99 is its plain successor. Previous: THE THREE-TIER LADDER SHIPS TO PRODUCTION (owner, 2026-09-17, after his own dictation on all three rungs on his Tab S10+: "all three actually work very well" - his report, not a WER; and on turbo, "we definitely wanna keep that one ... six to maybe nine second drain time, which is totally manageable and doable. And users would definitely like to select between these"). FOUR THINGS. (1) THE LABELS are his words, made true: small "Fastest, less accurate", medium "Balanced speed and accuracy", turbo "Highest accuracy, slower than the other two" - his "slightly slower" amended by controller ruling, because the tablet measurement (docs/measurements/2026-09-17-tab-cpu-ladder.md) puts turbo at 3.6x medium and 4.0x small per commit (4,849 ms against 1,341 and 1,217 - over three times either), and his own reported drain on turbo was six to nine seconds against the doc's 1.2-1.3 s per-commit medians for the other two (the doc's figure, not one he reported); his report is on turbo's card as his report, dated. The green RAM chip reads "Fits your device" (a RAM fit, on every rung whose floor the device meets) and each RAM-floored card says "Offered where", not "Recommended where": the recommendation is the steer's "Our pick" alone. Every body names the tablet and the date; "fastest" as a claim about every device stays forbidden (ModelTierCopyTest). (2) THE FIRST-RUN LINEUP IS CUMULATIVE BY RAM ("if you can fit the medium model, you should also be able to see the small model ... if you can see v3 turbo, of course, you should see all three tiers"): every Q8 rung whose own floor the device meets - small always, medium and turbo at 4.5 GB, one constant per rung so turbo's can be raised alone (WhisperCatalog.MEDIUM_Q8_MIN_RAM_BYTES / ULTRA_Q8_MIN_RAM_BYTES) - so under the floor small alone, at or over it all three in ladder order with medium still steered ("Our pick"). NPU detection untouched ("they should absolutely get the NPU tier - that's unmatched"). ultra-q8 is an ordinary rung: no longer an instrument, badged "Fits your device" like its siblings where the floor is met. (3) THE AUTHORISATION, recorded where the gate reads it: TierThroughputRecord.PRODUCTION_PROMOTABLE names all three; ultra-q8's verdict is STILL KEPT_UP_WITHOUT_MARGIN and clears on a ThroughputVerdict.OwnerRuling recorded beside the number - a decision written next to the evidence it overrides, named and dated, never an edit to the evidence; the gate reports Promotable for the first time; the acceptance sheet's AN0 carries the same words. (4) THE STRIP IS NEVER BLANK FOR A WHOLE SESSION: 4.8.1 armed the session on the posted warm and accepted that a load which then throws, or a canary that fails, left that session with nothing on the strip (the tee swallowed whisper's deltas for a previewer that never painted); now the previewer's open() reports it cannot open (LocalPreview.open's onUnavailable), the tee switches to pass-through one-way for the session and tells the service, and the service clears the session's local-preview flag so the strip returns to the ordinary in-flight label - the pre-4.8.1 strip, on CPU and NPU alike (the NPU tier emits no whisper deltas, so the flag, not the pass-through, is what closes it). AND ONE CADENCE ROW MOVED: medium-q8 paces on the 6 000 ms MULTI row (CommitCadencePolicy) by the owner's ruling of 2026-09-17 after testing medium Q8 on his tablet - "six seconds for medium, since I can handle it" - its worst Tab commit (2,508 ms) is 0.42 of that floor; through 4.8.x it paced at 8 000 via the LARGE row. ultra-q8 stays at 8 000 by his same-day ruling ("keep it the way it is"; its worst commit was 7,930 ms, so 7 000 is not supported). versionCode STAYS 98: the 4.8.1 name was set earlier today by the first-session round and 98 never left this machine, so the name moves and the code does not (ReleaseIdentityTest says why). Previous: A PATCH: THE FIRST-SESSION LIVE-WORDS GATE (owner, 2026-09-17, on a fresh sideloaded 4.8.0/97: after onboarding and the tap that installs the live-words pack, the FIRST dictation showed no live words and the second did - "users will just think it's broken"; the re-run "sailed right through", which is the timing). The wrap site read the engine's isWarmFor() in the same Main pass that had just posted the load, and on a fresh install the first warm is the boot prewarm's (onboarding never starts the service; the install collector drops the replayed record by design), so a tap inside the ~2.5-3.5 s after the bubble toggle lost the whole first session. 98 arms the session on the POSTED warm - the engine's single FIFO executor runs it before the session's open(), and every entry point is safe on a cold recognizer - so the strip fills the moment the load lands; Home's card retires only when a session opened warm; warm_now= joins the stream-gate line as the proof. Nothing about which pack warms, when, the busy refusal or the release rules moved, and onboarding still does not start the service. 97 was sideloaded only (never on a Play track), so it is spent there as 95 and 96 were. Owner ruling: "let's fix it into this next build right now, and then I'll push that to production, that way everyone gets a smooth install" - the promotable set (TierThroughputRecord.PRODUCTION_PROMOTABLE) is untouched by this patch and is his call at promotion time. Previous: FOUR RULINGS FROM THE 96 TABLET SESSION (owner, 2026-09-17, after seeing 4.7.0/96 on his Tab S10+: "Everything looks good"). (1) The transcript window's scrollbars are VISIBLE - persistent, 4dp, a drawn thumb on a faint track on both the committed text and the live strip; the framework's fade-at-rest default is why earlier builds "don't seem to have that". (2) "Keep bubble always on screen" defaults to OFF for NEW installs; an install with no stored value has its answer written once at construction - `true` for an existing install (it was living in always-on), `false` for a fresh one - so nobody's bubble changes mode on upgrade, and a fresh install that has since finished onboarding is not mistaken for an existing one at its next process start. (3) The opacity ladder goes down to 20% so a video plays through the panel; the legibility guarantee is kept honest at 85% and above (OPACITY_GUARANTEED_PERCENT) and the slider states the trade below it. (4) First-run model choice: NPU-capable devices see turbo alone (already true); every other device is gated at the owner's 4.5 GB - under it the smallest Q8 rung, over it medium and turbo (medium steered - a controller call on the throughput measurements, not the owner's; OnboardingLogic.FIRST_RUN_STEER_ABOVE_GATE_ID is the val to flip). 96 was 4.7.0, sideloaded to the tablet only, never on a Play track; 97 is for the internal track. Production authorisation is still withheld pending the owner's accuracy pass (the promotable set is empty). Previous: THE Q8 LADDER. On 2026-09-17 five CPU rungs were timed on the owner's Tab S10+ against the same talk (docs/measurements/2026-09-17-tab-cpu-ladder.md) and the owner ruled the same day: "Q8 for everything" - "Q5 is definitely off the table". Small Q8_0 becomes the floor for every device and the default (the same weights as the 190 MB model, 2.2x faster per commit on that tablet), medium Q8_0 becomes the medium tier recommended above a provisional 5.5 GB RAM threshold, and large-v3-turbo Q8_0 stays an optional top rung - it kept up on a flagship with no margin, so it is offered for its accuracy and never advocated. The four Q5 rungs (the shipped 190 MB default among them) are retired the gentle way: hidden from the chooser, untouched for anyone who has one. PRODUCTION AUTHORISATION IS WITHHELD: the promotable set is empty pending the owner's accuracy pass on small and medium, so 96 goes to the internal track and a sideloaded tablet only. 95 was 4.6.0, the instrument ladder, never promoted..

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
                arguments += "-DGGML_OPENCL=ON"
                arguments += "-DOpenCL_INCLUDE_DIR=D:/gemma-inference/tools/opencl/include"
                arguments += "-DOpenCL_LIBRARY=D:/gemma-inference/tools/opencl/lib/libOpenCL.so"
                // CMake otherwise picks the Windows-Store python alias stub and fails.
                arguments += "-DPython3_EXECUTABLE=C:/Users/bastr/AppData/Local/Programs/Python/Python313/python.exe"
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
            //   ONE STUB PER CENSUS FAMILY IN lib/, ONE SKEL PER CENSUS FAMILY IN assets/.
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
            // libQnnHtpPrepare.so alone is 79 MB and exists only to COMPILE a graph on device —
            // we never compile one.
            excludes += "**/libQnnHtpPrepare.so"
            // Non-HTP backends: unused.
            excludes += "**/libQnnDsp.so"
            excludes += "**/libQnnDspV66Skel.so"
            excludes += "**/libQnnDspV66Stub.so"
            excludes += "**/libQnnGpu.so"
            // The census families' DSP-side skels — relocated to assets per the rule above.
            // Their stubs are deliberately NOT excluded.
            excludes += "**/libQnnHtpV73Skel.so"
            excludes += "**/libQnnHtpV75Skel.so"
            excludes += "**/libQnnHtpV79Skel.so"
            excludes += "**/libQnnHtpV81Skel.so"
            // HTP architectures with no covered family: skel AND stub stay excluded — and these
            // presences are what keep the census families' stub live-zeros honest in
            // NpuSkelPackagingTest.
            excludes += "**/libQnnHtpV68Skel.so"
            excludes += "**/libQnnHtpV68Stub.so"
            excludes += "**/libQnnHtpV69Skel.so"
            excludes += "**/libQnnHtpV69Stub.so"
        }
    }

    sourceSets {
        // 4.1 L6: the generated qnnSkel assets dir — produced by extractQnnSkel, consumed by
        // merge*Assets (the task ordering lives beside the task). Without this registration the
        // merge never sees the dir, the APK ships without the skel, and every device dies at
        // stage=skel while the build looks green.
        getByName("main") { assets.srcDir(qnnSkelAssetDir) }
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
    assetPacks += listOf(":npu_turbo", ":npu_small", ":preview_en", ":tts_kokoro") + listOf(
        ":preview_fr", ":preview_de", ":preview_ru",
        ":preview_id", ":preview_ko", ":preview_zh",
    )

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
        // (4.1 L8) NpuBackendSelector.kt — the plan's own found-while-writing hole, the same one
        // Q7a MEASURED and I3 named, on the one file that carries the routing decision:
        // NpuBackendWiringTest source-pins this file (the routesToNpu signature, the zero-literal
        // rule, the production construction site), yet it was never a test input, so a
        // comment-only edit left the suite UP-TO-DATE and every one of those pins passing against
        // stale evidence.
        "src/main/java/com/whispereverywhere/transcription/NpuBackendSelector.kt",
        "src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt",
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
val fetchQnnHeaders = tasks.register<Exec>("fetchQnnHeaders") {
    description = "Fetches the pinned QAIRT (QNN) C API headers into src/main/cpp/include/QNN."
    inputs.file(rootProject.file("tools/fetch_qnn_headers.py"))
    outputs.dir(file("src/main/cpp/include/QNN"))
    // Absolute interpreter: `python` is not on PATH here, and CMake in this same build already
    // pins Python3_EXECUTABLE to this exact binary for the same reason (the Windows-Store alias
    // stub resolves first otherwise).
    commandLine(
        "C:/Users/bastr/AppData/Local/Programs/Python/Python313/python.exe",
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
// generated assets (4.1 L6 — the I5 answer; the fleet at 4.2 F2: one APK covers four families,
// and the device stages exactly its own row's skel at arm time). PROPRIETARY: the blobs land in
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
        // THE FLEET TABLE (4.2 F2): one row per census family, full literals. These are the same
        // four (bytes, sha256) pairs NpuFleetCensus.families carries — restated here because a
        // build script cannot read the app's classes, and pinned EQUAL to the census by
        // NpuSkelPackagingTest (executed set-equality, both directions), the same two-spellings
        // discipline as the qnn-runtime coordinate below. A row joins when a family joins the
        // census, never alone.
        val qnnSkels = listOf(
            Triple("libQnnHtpV73Skel.so", 17_909_588L, "7be4f8a4ec21a9d8d51f59c73094154f42d2f8fc91cfaadaef03441b77d7ddb1"),
            Triple("libQnnHtpV75Skel.so", 17_913_608L, "a56519d6ef8510c47bf955f919a119eb3d249f4845576f723cfb40ee8010ed5c"),
            Triple("libQnnHtpV79Skel.so", 17_721_548L, "9cad65a621d154e5282ea9d2849d0a8838932ed91dc7e2514db4e992e2d933c6"),
            Triple("libQnnHtpV81Skel.so", 18_844_384L, "b3453265c4574c69bb446bcb98dda117ded531b86b2307e0f02c595050fab8b1"),
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
                        "(measured from qnn-runtime-2.49.0.aar). A runtime bump must " +
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

// The Play pack gate (4.2 F4): every bundle build re-proves that the pack payload on disk IS
// the census before AGP packages it. The payload is a BUILD artifact — tools/build_asset_packs.py
// build assembles the eight #group_ variants from the measured vendor zips, hash-verifying every
// byte on the way in and out — so the committed tree carries no payload at all, and a bundle
// built on a machine that never ran the script fails HERE with every missing variant named,
// instead of shipping packs whose targeted variants are silently empty.
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
// NpuPackFetch.PACK_BY_TIER, the same map that names the pack to fetch).
//
// THE PACK TABLE: one row per variant — module, Play device group, encoder bytes, decoder
// bytes. The byte counts are NpuFleetCensus.artifacts' own, restated because a build script
// cannot read the app's classes, and pinned EQUAL to the census by NpuPackLayoutTest (the
// extractQnnSkel fleet-table discipline, one gate over). sha256 of ~4.3 GB per bundle build is
// deliberately NOT taken here: the script's own build step hash-verifies what it writes, the
// app's arrival hash stays the invariant on device, and this gate's job is missing, stale or
// swapped VARIANTS — which exact byte counts catch in milliseconds.
val npuPackDeliveryNames = mapOf(
    "npu_small" to listOf("encoder_qairt_context.bin", "decoder_qairt_context.bin"),
    "npu_turbo" to listOf("turbo_encoder_qairt_context.bin", "turbo_decoder_qairt_context.bin"),
)
val npuPackCensusRows = listOf(
    listOf("npu_small", "soc_8gen3", 132_927_488L, 225_316_864L),
    listOf("npu_small", "soc_8elite_galaxy", 132_333_568L, 225_234_944L),
    listOf("npu_small", "soc_8elite5_galaxy", 133_554_176L, 225_411_072L),
    listOf("npu_small", "soc_7gen4", 147_595_264L, 225_382_400L),
    listOf("npu_turbo", "soc_8gen3", 775_831_552L, 295_854_080L),
    listOf("npu_turbo", "soc_8elite_galaxy", 775_544_832L, 295_821_312L),
    listOf("npu_turbo", "soc_8elite5_galaxy", 777_441_280L, 295_911_424L),
    listOf("npu_turbo", "soc_7gen4", 846_360_576L, 295_895_040L),
)
val verifyNpuPacks = tasks.register("verifyNpuPacks") {
    description = "Verifies every NPU asset-pack variant against the census byte counts and " +
        "that both default variants are EMPTY. Runs before every bundle packaging task."
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
            "verifyNpuPacks: all ${npuPackCensusRows.size} pack variants match the census " +
                "byte counts and both default variants are empty."
        )
    }
}
// Wired before bundle PACKAGING only. assembleDebug must NOT depend on this gate: an APK build
// carries no packs at all, and the everyday build must never demand 4.3 GB of payload.
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
    implementation("com.qualcomm.qti:qnn-runtime:2.49.0")
    // 4.1 L6: extractQnnSkel resolves the SAME artifact through its own configuration to pull
    // the census families' skels out of the AAR (see the task above the dependencies block).
    // The restated coordinate is pinned equal to the line above by NpuSkelPackagingTest.
    qnnSkelSource("com.qualcomm.qti:qnn-runtime:2.49.0")

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
