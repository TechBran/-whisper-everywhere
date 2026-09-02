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
        versionCode = 83
        versionName = "4.3.1"  // One tier per device: a capable phone is offered turbo and nothing else; the CPU model is hidden until a decline makes it the answer. (4.2.0/81 was Fleet Onboarding: the four-family census + gate, the NPU packs on Play Asset Delivery, language-first onboarding, the chooser's fetch affordance.) (unchanged in 4.3.1, a patch)

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
    assetPacks += listOf(":npu_turbo", ":npu_small")

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
tasks.withType<Test>().configureEach {
    inputs.files(
        "src/main/cpp/whisper_jni.cpp",
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
        // (4.1 L4) The turbo vocabulary, for exactly the melbank's reason one asset over: a JSON
        // asset is an input to no compile task, so regenerating it wrongly — from the wrong base,
        // without <|yue|>, with HF's <|nospeech|> spelling — changes not one .class file.
        // TurboVocabAssetTest (base identity, special layout, digest, the id-188 NUL token) and
        // WhisperBpeDecoderTest (golden vectors through the turbo decoder) are its only readers,
        // and without this entry the task reports UP-TO-DATE and every one of those assertions
        // passes against the file as it used to be.
        "src/main/assets/whisper_vocab_turbo.json",
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
        // (4.3.1 B) BubbleHideWiringPinTest reads the controller for speakFromTrigger's Boolean.
        "src/main/java/com/whispereverywhere/tts/TtsController.kt",
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
val sherpaAar = file("libs/sherpa-onnx-1.13.4.aar")
val sherpaAarSha256 = "03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780"
val fetchSherpaAar = tasks.register("fetchSherpaAar") {
    outputs.file(sherpaAar)
    doLast {
        if (!sherpaAar.exists()) {
            sherpaAar.parentFile.mkdirs()
            uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.4/sherpa-onnx-1.13.4.aar")
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

dependencies {
    // On-device TTS (Track F): sherpa-onnx runs Kokoro-82M on CPU (fetched above). arm64
    // native payload only reaches the APK because of the abiFilters above.
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))

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
