import java.security.MessageDigest
import java.util.Properties

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
        versionCode = 78
        versionName = "3.7.0"  // VAD endpointing: Silero cuts at real pauses, per-tier commit cadence, eco/base retired

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

            // QNN/QAIRT runtime (4.0 NPU tier). The AAR ships every backend for every Hexagon
            // architecture; this app deserialises ONE precompiled context binary built for
            // SM8650 / HTP v75, and AI Hub context binaries are not portable across HTP arch
            // anyway. Everything below is dead weight in the APK.
            //
            // libQnnHtpPrepare.so alone is 79 MB and exists only to COMPILE a graph on device —
            // we never compile one.
            excludes += "**/libQnnHtpPrepare.so"
            // Non-HTP backends: unused.
            excludes += "**/libQnnDsp.so"
            excludes += "**/libQnnDspV66Skel.so"
            excludes += "**/libQnnDspV66Stub.so"
            excludes += "**/libQnnGpu.so"
            // Other HTP architectures. V75 is the only one kept.
            excludes += "**/libQnnHtpV68Skel.so"
            excludes += "**/libQnnHtpV68Stub.so"
            excludes += "**/libQnnHtpV69Skel.so"
            excludes += "**/libQnnHtpV69Stub.so"
            excludes += "**/libQnnHtpV73Skel.so"
            excludes += "**/libQnnHtpV73Stub.so"
            excludes += "**/libQnnHtpV79Skel.so"
            excludes += "**/libQnnHtpV79Stub.so"
            excludes += "**/libQnnHtpV81Skel.so"
            excludes += "**/libQnnHtpV81Stub.so"
        }
    }

    testOptions {
        // Diagnostic logging uses android.util.Log, which is not available in plain JVM unit
        // tests; return default (no-op) values instead of throwing "Method not mocked".
        unitTests.isReturnDefaultValues = true
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
tasks.withType<Test>().configureEach {
    inputs.files(
        "src/main/cpp/whisper_jni.cpp",
        "src/main/cpp/whisper.cpp/src/whisper.cpp",
        "src/main/cpp/whisper.cpp/include/whisper.h",
        "src/main/cpp/qnn_asr.cpp",
        "src/main/AndroidManifest.xml",
        "src/main/assets/whisper_vocab.json",
        "src/main/java/com/whispereverywhere/transcription/NpuWhisperBackend.kt",
        "src/main/java/com/whispereverywhere/model/WhisperModelManager.kt",
        "src/main/java/com/whispereverywhere/ui/screens/SettingsScreen.kt",
        "src/main/java/com/whispereverywhere/ui/screens/OnboardingFlowScreen.kt",
        "src/main/java/com/whispereverywhere/ui/screens/OnboardingModelScreen.kt",
        "src/main/java/com/whispereverywhere/WhisperEverywhereApp.kt",
        "src/main/java/com/whispereverywhere/MainActivity.kt",
        "src/main/java/com/whispereverywhere/data/local/PreferencesManager.kt",
        rootProject.file(".gitignore"),
    ).withPropertyName("nativeSourceContract").withPathSensitivity(PathSensitivity.RELATIVE)
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

    // QNN/QAIRT runtime (4.0 NPU tier): libQnnHtp.so, libQnnSystem.so, libQnnHtpV75Skel/Stub.so.
    // These are dlopen()ed by our own libqnnasr.so — never linked at build time — so no import
    // library is needed, only the headers (fetched by fetchQnnHeaders above, never committed).
    // The version MUST stay in step with the pinned literal in tools/fetch_qnn_headers.py.
    // Most of the AAR's payload is excluded in packaging.jniLibs above; see the note there.
    implementation("com.qualcomm.qti:qnn-runtime:2.49.0")

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
